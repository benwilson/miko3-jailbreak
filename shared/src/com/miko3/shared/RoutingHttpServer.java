package com.miko3.shared;

import android.content.Context;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
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

    private final Context appContext;
    private final int port;
    private final Map<String, RouteHandler> routes = new HashMap<String, RouteHandler>();
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
     * Binds with SO_REUSEADDR (so a just-closed socket on this same port
     * from another process — e.g. the launcher and the mode app trading
     * off the same port pair on a mode launch/exit, see LauncherApp's and
     * ModeApp's startServer()/stopServer() — doesn't leave the new bind
     * failing on a lingering TIME_WAIT) plus a short retry: the outgoing
     * side's close() and the incoming side's bind() are two independent
     * process's calls with no direct handoff signal between them, so a
     * few hundred ms of overlap is expected, not a bug.
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
            if (!isTls && httpsPort > 0) {
                String host = headers.get("host");
                if (host != null) {
                    int colon = host.indexOf(':');
                    if (colon >= 0) host = host.substring(0, colon);
                    res.redirect("https://" + host + ":" + httpsPort + rawPath);
                    client.close();
                    return;
                }
            }

            InputStream body = bodyStream(rawIn, headers);
            HttpRequest req = new HttpRequest(method, path, HttpRequest.parseQuery(queryString), headers, body);

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
