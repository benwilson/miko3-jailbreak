package com.miko3.shared;

import android.content.Context;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import javax.net.ssl.SSLContext;

/**
 * A small routing HTTP/1.0 server, originally factored out of the mode
 * app's first hand-rolled server (which itself extended launcher's
 * original InfoHttpServer ServerSocket/per-connection-thread shape) to
 * support multiple routes and streaming (not close-after-one-response)
 * responses — needed for U6's MJPEG stream, U8's continuous audio feed,
 * and now U9's launcher Wi-Fi/system pages, all of which need more than
 * one route. Shared here (KTD2-style: every APK compiles its own copy)
 * so the launcher and every mode app use the identical implementation.
 *
 * Deliberately narrow: no keep-alive, no gzip, minimal header parsing.
 * Every route handler is registered up front; unmatched paths get a
 * plain 404.
 *
 * Can additionally serve the exact same routes over TLS — call {@link
 * #startHttps} with an {@link SSLContext} from {@link
 * HttpsSupport#loadServerContext} after registering routes and before/after
 * starting the primary {@code run()} thread, on a second port, from a
 * second daemon thread this method spawns itself. Needed so `getUserMedia`
 * (operator webcam capture, see mode-remote-control's ToggleSection) has a
 * secure context once the robot is reached over a real WiFi IP instead of
 * the http://127.0.0.1 adb-tunnel loopback exception — plain HTTP to a LAN
 * IP does not qualify as secure, self-signed HTTPS does (once the
 * browser's certificate warning is accepted). Both listeners share this
 * instance's one `routes` map and `handle()` dispatch — {@link
 * javax.net.ssl.SSLServerSocket} extends {@link ServerSocket}, so the same
 * accept-loop shape and {@code Socket}-based `handle()` work unchanged for
 * either.
 */
public final class RoutingHttpServer implements Runnable {
    private static final String TAG = "RoutingHttpServer";

    public interface RouteHandler {
        void handle(HttpRequest req, HttpResponse res) throws IOException;
    }

    /** U13: a persistent, low-latency channel for drive commands — see
     * mode-remote-control/ModeApp's "/drive-ws" route for why a WebSocket
     * replaced repeated short-lived HTTPS requests (each one paying a fresh
     * TLS handshake, which over WiFi was slow enough to routinely blow past
     * the drive watchdog and cause visible start/stop jank). */
    public interface WebSocketHandler {
        void handle(HttpRequest req, WebSocketConnection ws) throws IOException;
    }

    private static final String WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final Context appContext;
    private final int port;
    private final Map<String, RouteHandler> routes = new HashMap<String, RouteHandler>();
    private final Map<String, WebSocketHandler> wsRoutes = new HashMap<String, WebSocketHandler>();
    private volatile boolean running = true;
    private volatile ServerSocket serverSocket;
    private volatile ServerSocket httpsServerSocket;
    /** Set by startHttps(); &gt; 0 once HTTPS is actually up, so the plain
     * listener knows to redirect instead of serving directly. */
    private volatile int httpsPort = -1;

    public RoutingHttpServer(Context ctx, int port) {
        this.appContext = ctx.getApplicationContext();
        this.port = port;
    }

    public void route(String path, RouteHandler handler) {
        routes.put(path, handler);
    }

    public void websocketRoute(String path, WebSocketHandler handler) {
        wsRoutes.put(path, handler);
    }

    public Context appContext() {
        return appContext;
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
        try {
            if (httpsServerSocket != null) httpsServerSocket.close();
        } catch (IOException ignored) {
        }
    }

    @Override
    public void run() {
        ServerSocket server = null;
        try {
            server = bindWithRetry(new ServerSocket(), port);
            serverSocket = server;
            Log.i(TAG, "listening on port " + port);
            acceptLoop(server, false);
        } catch (IOException e) {
            Log.e(TAG, "failed to start server on port " + port, e);
        } finally {
            if (server != null) {
                try {
                    server.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * Binds with SO_REUSEADDR (so a quick app restart reusing this same port
     * — an APK reinstall, a crash-and-relaunch — doesn't fail its bind on a
     * lingering TIME_WAIT from the previous process's socket) plus a short
     * retry, since that previous socket's teardown isn't instantaneous.
     */
    private static ServerSocket bindWithRetry(ServerSocket socket, int port) throws IOException {
        socket.setReuseAddress(true);
        IOException lastError = null;
        for (int attempt = 0; attempt < 15; attempt++) {
            try {
                socket.bind(new java.net.InetSocketAddress(port));
                return socket;
            } catch (IOException e) {
                lastError = e;
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw lastError != null ? lastError : new IOException("bind failed, no attempts made");
    }

    /**
     * Starts a second, TLS-wrapped listener on its own daemon thread,
     * sharing this instance's routes. Safe to call any time after
     * construction (route registration order relative to this call doesn't
     * matter — routes are looked up per-request, not snapshotted). Logs and
     * returns (does not throw) if the port is already in use or the
     * context fails to bind, since HTTPS is additive — plain HTTP already
     * works regardless.
     */
    public void startHttps(final int httpsPortArg, final SSLContext sslContext) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                ServerSocket server = null;
                try {
                    server = bindWithRetry(
                            sslContext.getServerSocketFactory().createServerSocket(), httpsPortArg);
                    httpsServerSocket = server;
                    // Set only once bound: if this fails (port in use, bad context), the
                    // plain listener keeps serving directly rather than redirecting
                    // everyone to a port nothing is actually listening on.
                    httpsPort = httpsPortArg;
                    Log.i(TAG, "listening on port " + httpsPortArg + " (https)");
                    acceptLoop(server, true);
                } catch (IOException e) {
                    Log.e(TAG, "failed to start HTTPS server on port " + httpsPortArg, e);
                } finally {
                    if (server != null) {
                        try {
                            server.close();
                        } catch (IOException ignored) {
                        }
                    }
                }
            }
        }, "routing-https-server");
        t.setDaemon(true);
        t.start();
    }

    private void acceptLoop(ServerSocket server, final boolean isTls) {
        while (running) {
            try {
                final Socket client = server.accept();
                Thread t = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        handle(client, isTls);
                    }
                }, "routing-http-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (running) Log.w(TAG, "accept failed", e);
            }
        }
    }

    private void handle(Socket client, boolean isTls) {
        try {
            client.setSoTimeout(30000);
            // Nagle's algorithm (on by default) batches small writes to reduce packet
            // count, at the cost of delaying them — the wrong tradeoff for every route
            // this server has, but especially "/drive-ws" (mode-remote-control): each
            // drive command is one small frame sent every 80ms while a control is
            // held, and any added delay eats directly into DriveController's 750ms
            // watchdog margin. Confirmed live: repeated commands intermittently
            // stopped reaching the server for 750ms+ even while a real browser kept
            // sending them on schedule.
            client.setTcpNoDelay(true);
            InputStream rawIn = new BufferedInputStream(client.getInputStream());
            String requestLine = LineReader.readLine(rawIn);
            if (requestLine == null || requestLine.isEmpty()) {
                client.close();
                return;
            }
            Log.i(TAG, "request: " + requestLine);
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                client.close();
                return;
            }
            String method = parts[0];
            String rawPath = parts[1];
            String path = rawPath;
            String queryString = "";
            int qIdx = rawPath.indexOf('?');
            if (qIdx >= 0) {
                path = rawPath.substring(0, qIdx);
                queryString = rawPath.substring(qIdx + 1);
            }

            Map<String, String> headers = new HashMap<String, String>();
            String headerLine;
            while ((headerLine = LineReader.readLine(rawIn)) != null && !headerLine.isEmpty()) {
                int colon = headerLine.indexOf(':');
                if (colon > 0) {
                    headers.put(headerLine.substring(0, colon).trim().toLowerCase(),
                            headerLine.substring(colon + 1).trim());
                }
            }

            OutputStream out = client.getOutputStream();
            HttpResponse res = new HttpResponse(out);

            // Once HTTPS is actually up, the plain listener only ever redirects —
            // never serves content directly — so getUserMedia (which requires a
            // secure context) always ends up used from an https:// origin, not a
            // page that happened to load over http:// this one time. Uses the
            // request's own Host header (host:port as the client sent it) rather
            // than a hardcoded hostname, since the robot's WiFi IP isn't known at
            // build time; strips any :port suffix and substitutes the HTTPS one.
            //
            // One exception (KTD8): the presence route is answered here directly.
            // The launcher probes it on loopback plain HTTP and would otherwise only
            // ever see this 302; it's a tiny JSON status no page loads, so it gains
            // nothing from the secure context. Keyed off the shared constant so no
            // mode needs code of its own for this.
            //
            // Settings paths (TLS-only, they carry the API key) redirect a GET
            // only; any other method falls through to the 503 below.
            if (!isTls && httpsPort > 0 && !LauncherProtocol.PRESENCE_PATH.equals(path)
                    && (!LauncherProtocol.isTlsOnlyPath(path) || "GET".equals(method))) {
                String host = headers.get("host");
                if (host != null) {
                    int colon = host.indexOf(':');
                    if (colon >= 0) host = host.substring(0, colon);
                    res.redirect("https://" + host + ":" + httpsPort + rawPath);
                    client.close();
                    return;
                }
            }

            // A TLS-only path that wasn't redirected above is refused outright on
            // the plain listener, before HTTPS binds or if its certificate failed
            // to load: its body (which may hold the key) is never read or dispatched.
            if (!isTls && LauncherProtocol.isTlsOnlyPath(path)) {
                res.sendText(503, "Service Unavailable", "text/plain; charset=utf-8",
                        LauncherProtocol.settingsNeedHttpsMessage(headers.get("host")));
                client.close();
                return;
            }

            InputStream body = bodyStream(rawIn, headers);
            HttpRequest req = new HttpRequest(method, path, HttpRequest.parseQuery(queryString), headers, body);

            WebSocketHandler wsHandler = wsRoutes.get(path);
            if (wsHandler != null && isWebSocketUpgrade(headers)) {
                String wsKey = headers.get("sec-websocket-key");
                if (wsKey == null) {
                    res.sendText(400, "Bad Request", "text/plain; charset=utf-8", "missing Sec-WebSocket-Key");
                    client.close();
                    return;
                }
                String handshake = "HTTP/1.1 101 Switching Protocols\r\n"
                        + "Upgrade: websocket\r\n"
                        + "Connection: Upgrade\r\n"
                        + "Sec-WebSocket-Accept: " + computeAcceptKey(wsKey) + "\r\n\r\n";
                out.write(handshake.getBytes(StandardCharsets.US_ASCII));
                out.flush();
                // No read timeout on a WS connection: it's meant to sit open and idle
                // between drive commands (that's the whole point — no per-command
                // reconnect cost), so the 30s timeout below (meant for an ordinary
                // request/response exchange) would otherwise kill it while the
                // operator simply isn't pressing anything.
                client.setSoTimeout(0);
                WebSocketConnection ws = new WebSocketConnection(client, rawIn, out);
                try {
                    wsHandler.handle(req, ws);
                } catch (IOException e) {
                    Log.w(TAG, "websocket handler for " + path + " failed", e);
                } finally {
                    ws.close();
                }
                return;
            }

            RouteHandler handler = routes.get(path);
            if (handler == null) {
                res.sendText(404, "Not Found", "text/plain; charset=utf-8", "not found");
                client.close();
                return;
            }
            try {
                handler.handle(req, res);
            } catch (IOException e) {
                Log.w(TAG, "handler for " + path + " failed", e);
            }
            // Streaming handlers hold the connection open themselves and close it when
            // their stream ends; a normal handler's response is already complete here.
            client.close();
        } catch (IOException e) {
            Log.w(TAG, "connection error", e);
            try {
                client.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static boolean isWebSocketUpgrade(Map<String, String> headers) {
        String upgrade = headers.get("upgrade");
        return upgrade != null && upgrade.toLowerCase().contains("websocket");
    }

    /** RFC 6455 §1.3: base64(SHA-1(client's Sec-WebSocket-Key + the spec's fixed GUID)).
     * Package-private so WebSocketClient can check the server's answer with it. */
    static String computeAcceptKey(String wsKey) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest((wsKey + WEBSOCKET_GUID).getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-1 is a mandatory JCA algorithm on every Android version this targets.
            throw new AssertionError(e);
        }
    }

    private InputStream bodyStream(InputStream raw, Map<String, String> headers) {
        String transferEncoding = headers.get("transfer-encoding");
        if (transferEncoding != null && transferEncoding.toLowerCase().contains("chunked")) {
            return new ChunkedInputStream(raw);
        }
        String contentLengthHeader = headers.get("content-length");
        if (contentLengthHeader != null) {
            try {
                long len = Long.parseLong(contentLengthHeader.trim());
                return new BoundedInputStream(raw, len);
            } catch (NumberFormatException ignored) {
            }
        }
        return new BoundedInputStream(raw, 0);
    }

    /** Reads exactly `limit` bytes from the underlying stream, then reports EOF. */
    private static final class BoundedInputStream extends InputStream {
        private final InputStream raw;
        private long remaining;

        BoundedInputStream(InputStream raw, long limit) {
            this.raw = raw;
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) return -1;
            int b = raw.read();
            if (b != -1) remaining--;
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            if (remaining <= 0) return -1;
            int n = raw.read(buf, off, (int) Math.min(len, remaining));
            if (n > 0) remaining -= n;
            return n;
        }
    }
}
