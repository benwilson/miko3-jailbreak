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
    }

    @Override
    public void run() {
        try (ServerSocket server = new ServerSocket(port)) {
            serverSocket = server;
            Log.i(TAG, "listening on port " + port);
            while (running) {
                try {
                    final Socket client = server.accept();
                    Thread t = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            handle(client);
                        }
                    }, "routing-http-conn");
                    t.setDaemon(true);
                    t.start();
                } catch (IOException e) {
                    if (running) Log.w(TAG, "accept failed", e);
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "failed to start server on port " + port, e);
        }
    }

    private void handle(Socket client) {
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

            InputStream body = bodyStream(rawIn, headers);
            HttpRequest req = new HttpRequest(method, path, HttpRequest.parseQuery(queryString), headers, body);
            OutputStream out = client.getOutputStream();
            HttpResponse res = new HttpResponse(out);

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
