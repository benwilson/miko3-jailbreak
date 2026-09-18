package com.miko3.shared;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An outbound RFC 6455 WebSocket client (KTD3): one {@code Socket}, the
 * HTTP/1.1 Upgrade handshake, then {@link WebSocketConnection} in client mode
 * (masked sends) for framing. Plain {@code ws://} only.
 *
 * <p>Single-use: {@link #connect} once, and on {@link Listener#onConnectFailed}
 * or {@link Listener#onLinkLost} make a new instance to reconnect. All
 * network I/O runs on the client's own threads, and every error arrives
 * through the listener; no method throws on the caller's thread.
 *
 * <p>Threads, all daemon, all named "ws-client-*": "ws-client-io" connects,
 * then reads and runs every listener callback in order; "ws-client-watchdog"
 * and "ws-client-ping" keep the link honest while it is open. Liveness is a
 * ping every {@link #PING_INTERVAL_MS} whatever else is flowing, since a
 * steady uplink would otherwise hide a dead peer until TCP's retransmission
 * timeout; {@link #MAX_MISSED_PONGS} unanswered pings in a row is link loss.
 * The watchdog never writes, so a send stuck on a full TCP window cannot
 * stall it: it closes the socket, which unblocks that send.
 */
public final class WebSocketClient {
    private static final String TAG = "WebSocketClient";
    public static final int PING_INTERVAL_MS = 2000;
    public static final int MAX_MISSED_PONGS = 3;
    private static final byte[] PING_PAYLOAD = "lane".getBytes(StandardCharsets.US_ASCII);
    /** How long a local close() lets the close frame try to go out before
     * the socket is closed under it. */
    private static final long CLOSE_FRAME_GRACE_MS = 1000;

    /**
     * Callbacks run on "ws-client-io", one at a time and in wire order,
     * except that a link-lost found by the watchdog is still delivered from
     * that same thread once its read unblocks. Exactly one of
     * onConnectFailed / onLinkLost ends a client's life, and neither fires
     * after the caller's own {@link #close()}. A callback that throws is
     * logged and the client carries on.
     */
    public interface Listener {
        void onOpen(WebSocketClient client);

        void onText(WebSocketClient client, String text);

        void onBinary(WebSocketClient client, byte[] data);

        /** TCP connect or the Upgrade handshake failed or timed out; no
         * client thread is left running once this returns. */
        void onConnectFailed(WebSocketClient client, String reason);

        /** An open link ended without the caller closing it: the server's
         * close frame (answered first; the reason names its status code), a
         * dropped or reset socket, a failed send, or missed pongs. */
        void onLinkLost(WebSocketClient client, String reason);
    }

    private final String host;
    private final int port;
    private final String path;
    private final Listener listener;

    private final AtomicBoolean connectCalled = new AtomicBoolean();
    /** Set once a terminal callback has fired or been suppressed by close(). */
    private final AtomicBoolean ended = new AtomicBoolean();
    private volatile boolean closedLocally;
    private volatile Socket socket;
    /** Non-null from the handshake's success on. */
    private volatile WebSocketConnection conn;
    /** Why the link is being torn down from outside the read loop (watchdog,
     * failed send); first writer wins. */
    private volatile String failReason;

    private final Object pingSignal = new Object();
    private boolean pingRequested; // guarded by pingSignal
    private volatile boolean keepaliveStopped;
    private volatile Thread watchdogThread;

    /** {@code path} is the request target, e.g. "/lane" or "/lane?robot=miko3". */
    public WebSocketClient(String host, int port, String path, Listener listener) {
        this.host = host;
        this.port = port;
        this.path = path;
        this.listener = listener;
    }

    /**
     * Starts connecting in the background and returns at once. The TCP
     * connect and the handshake each get {@code timeoutMs}; the listener then
     * hears onOpen or onConnectFailed. A second call is ignored.
     */
    public void connect(final int timeoutMs) {
        if (!connectCalled.compareAndSet(false, true)) {
            Log.w(TAG, "connect() called twice; ignoring (clients are single-use)");
            return;
        }
        Thread io = new Thread(new Runnable() {
            @Override
            public void run() {
                runIo(timeoutMs);
            }
        }, "ws-client-io");
        io.setDaemon(true);
        io.start();
    }

    /** True from onOpen until the link ends or {@link #close()} is called. */
    public boolean isOpen() {
        return conn != null && !closedLocally && !ended.get() && failReason == null;
    }

    /** Sends one text frame. Blocks while the socket is congested, so never
     * call it on the main thread. Returns false if the link is not open or
     * the send failed (the failure then ends the link via onLinkLost). */
    public boolean sendText(String text) {
        WebSocketConnection c = conn;
        if (c == null || !isOpen()) {
            return false;
        }
        try {
            c.sendText(text);
            return true;
        } catch (IOException e) {
            fail("send failed: " + e.getMessage());
            return false;
        }
    }

    /** Sends one binary frame; same contract as {@link #sendText}. */
    public boolean sendBinary(byte[] data) {
        return sendBinary(data, 0, data.length);
    }

    /** Sends {@code len} bytes of {@code data} from {@code off} as one binary frame. */
    public boolean sendBinary(byte[] data, int off, int len) {
        WebSocketConnection c = conn;
        if (c == null || !isOpen()) {
            return false;
        }
        try {
            c.sendBinary(data, off, len);
            return true;
        } catch (IOException e) {
            fail("send failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Ends the client from the caller's side: no further callbacks, a
     * best-effort close frame, and every client thread gone within about a
     * second. Returns without doing network I/O on the caller's thread.
     * Safe to call at any time, any number of times.
     */
    public void close() {
        if (closedLocally) {
            return;
        }
        closedLocally = true;
        stopKeepalive();
        final WebSocketConnection c = conn;
        if (c == null) {
            // Still connecting (or never started): closing the socket aborts the
            // connect/handshake, and the io thread exits without a callback.
            closeSocketQuietly();
            return;
        }
        Thread closer = new Thread(new Runnable() {
            @Override
            public void run() {
                Thread frame = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        c.close();
                    }
                }, "ws-client-close-frame");
                frame.setDaemon(true);
                frame.start();
                try {
                    frame.join(CLOSE_FRAME_GRACE_MS);
                } catch (InterruptedException ignored) {
                }
                // A close frame stuck behind a full TCP window is abandoned here:
                // closing the socket unblocks that write too.
                closeSocketQuietly();
            }
        }, "ws-client-close");
        closer.setDaemon(true);
        closer.start();
    }

    // ---- io thread ----------------------------------------------------------

    private void runIo(int timeoutMs) {
        WebSocketConnection c;
        try {
            c = handshake(timeoutMs);
        } catch (IOException e) {
            connectFailed(e.toString());
            return;
        } catch (RuntimeException e) {
            connectFailed(e.toString());
            return;
        }
        if (closedLocally) {
            c.close();
            return;
        }
        conn = c;
        startKeepalive(c);
        Log.i(TAG, "connected to " + host + ":" + port + path);
        try {
            listener.onOpen(this);
        } catch (RuntimeException e) {
            Log.e(TAG, "listener onOpen threw", e);
        }
        String reason;
        try {
            WebSocketConnection.Message m;
            while ((m = c.readMessage()) != null) {
                try {
                    if (m.binary) {
                        listener.onBinary(this, m.data);
                    } else {
                        listener.onText(this, m.text());
                    }
                } catch (RuntimeException e) {
                    Log.e(TAG, "listener threw handling a " + (m.binary ? "binary" : "text") + " message", e);
                }
            }
            int code = c.peerCloseCode();
            reason = code >= 0
                    ? "closed by server: " + code + (c.peerCloseReason().isEmpty() ? "" : " " + c.peerCloseReason())
                    : "connection closed by server without a close frame";
        } catch (IOException e) {
            reason = "read failed: " + e;
        }
        String cause = failReason;
        if (cause != null) {
            reason = cause; // the watchdog or a sender closed the socket under the read
        }
        stopKeepalive();
        c.close();
        if (!closedLocally && ended.compareAndSet(false, true)) {
            Log.w(TAG, "link lost: " + reason);
            try {
                listener.onLinkLost(this, reason);
            } catch (RuntimeException e) {
                Log.e(TAG, "listener onLinkLost threw", e);
            }
        }
    }

    private WebSocketConnection handshake(int timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        Socket s = new Socket();
        socket = s;
        if (closedLocally) {
            throw new IOException("closed before connecting");
        }
        s.setTcpNoDelay(true); // small control frames must not wait on Nagle
        s.connect(new InetSocketAddress(host, port), timeoutMs);
        s.setSoTimeout((int) Math.max(1, deadline - System.currentTimeMillis()));
        OutputStream out = s.getOutputStream();
        InputStream in = new BufferedInputStream(s.getInputStream());

        byte[] nonce = new byte[16];
        new SecureRandom().nextBytes(nonce);
        String key = Base64.getEncoder().encodeToString(nonce);
        String request = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + host + ":" + port + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n";
        out.write(request.getBytes(StandardCharsets.US_ASCII));
        out.flush();

        String status = LineReader.readLine(in);
        if (status == null) {
            throw new IOException("server closed the connection during the handshake");
        }
        String[] parts = status.split(" ");
        if (parts.length < 2 || !"101".equals(parts[1])) {
            throw new IOException("handshake refused: " + status);
        }
        Map<String, String> headers = new HashMap<String, String>();
        String line;
        while ((line = LineReader.readLine(in)) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(line.substring(0, colon).trim().toLowerCase(), line.substring(colon + 1).trim());
            }
        }
        String accept = headers.get("sec-websocket-accept");
        if (!RoutingHttpServer.computeAcceptKey(key).equals(accept)) {
            throw new IOException("handshake answered with a wrong Sec-WebSocket-Accept: " + accept);
        }
        s.setSoTimeout(0); // an open lane idles between conversations; liveness is the pings' job
        return new WebSocketConnection(s, in, out, true);
    }

    private void connectFailed(String reason) {
        closeSocketQuietly();
        if (!closedLocally && ended.compareAndSet(false, true)) {
            Log.w(TAG, "connect to " + host + ":" + port + path + " failed: " + reason);
            try {
                listener.onConnectFailed(this, reason);
            } catch (RuntimeException e) {
                Log.e(TAG, "listener onConnectFailed threw", e);
            }
        }
    }

    // ---- keepalive ------------------------------------------------------------

    private void startKeepalive(final WebSocketConnection c) {
        Thread pinger = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    synchronized (pingSignal) {
                        while (!pingRequested && !keepaliveStopped) {
                            try {
                                pingSignal.wait();
                            } catch (InterruptedException e) {
                                return;
                            }
                        }
                        if (keepaliveStopped) {
                            return;
                        }
                        pingRequested = false;
                    }
                    try {
                        c.sendPing(PING_PAYLOAD);
                    } catch (IOException e) {
                        fail("ping send failed: " + e.getMessage());
                        return;
                    }
                }
            }
        }, "ws-client-ping");
        pinger.setDaemon(true);

        Thread watchdog = new Thread(new Runnable() {
            @Override
            public void run() {
                long lastPongs = c.pongsReceived();
                boolean pingOutstanding = false;
                int missed = 0;
                while (!keepaliveStopped) {
                    try {
                        Thread.sleep(PING_INTERVAL_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (keepaliveStopped) {
                        return;
                    }
                    // A ping counts as missed if no pong at all arrived in the interval
                    // after it was requested -- including when the pinger could not
                    // even send it because a writer is stuck on a full TCP window.
                    long pongs = c.pongsReceived();
                    missed = (pingOutstanding && pongs == lastPongs) ? missed + 1 : 0;
                    if (missed >= MAX_MISSED_PONGS) {
                        fail(missed + " pongs missed in a row (ping every " + PING_INTERVAL_MS + " ms)");
                        return;
                    }
                    lastPongs = pongs;
                    pingOutstanding = true;
                    synchronized (pingSignal) {
                        pingRequested = true;
                        pingSignal.notifyAll();
                    }
                }
            }
        }, "ws-client-watchdog");
        watchdog.setDaemon(true);
        watchdogThread = watchdog;
        pinger.start();
        watchdog.start();
    }

    private void stopKeepalive() {
        keepaliveStopped = true;
        synchronized (pingSignal) {
            pingSignal.notifyAll();
        }
        Thread w = watchdogThread;
        if (w != null && w != Thread.currentThread()) {
            w.interrupt();
        }
    }

    /** Tears the link down from outside the read loop; the io thread then
     * reports onLinkLost with this reason. */
    private void fail(String reason) {
        synchronized (this) {
            if (failReason == null) {
                failReason = reason;
            }
        }
        closeSocketQuietly();
    }

    private void closeSocketQuietly() {
        Socket s = socket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }
}
