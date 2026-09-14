package com.miko3.launcher;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Minimal single-purpose HTTP server: answers every request with the same
 * read-only device-info page (DeviceInfo.buildHtmlPage). Deliberately narrow
 * for a proof of concept exposed on the local Wi-Fi network — no routing, no
 * file access, no query/header/body parsing beyond the request line, and no
 * user input is ever reflected back into the page.
 */
final class InfoHttpServer implements Runnable {

    private static final String TAG = "Miko3Launcher";
    static final int PORT = 8080;

    private final Context appContext;
    private volatile boolean running = true;
    private volatile ServerSocket serverSocket;

    InfoHttpServer(Context ctx) {
        this.appContext = ctx.getApplicationContext();
    }

    void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
    }

    @Override
    public void run() {
        try (ServerSocket server = new ServerSocket(PORT)) {
            serverSocket = server;
            Log.i(TAG, "listening on port " + PORT);
            while (running) {
                try {
                    final Socket client = server.accept();
                    Thread t = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            handle(client);
                        }
                    }, "info-http-conn");
                    t.setDaemon(true);
                    t.start();
                } catch (IOException e) {
                    if (running) Log.w(TAG, "accept failed", e);
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "failed to start server on port " + PORT, e);
        }
    }

    private void handle(Socket client) {
        try (Socket s = client) {
            s.setSoTimeout(5000);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
            String requestLine = in.readLine(); // e.g. "GET / HTTP/1.1" — method/path are ignored
            if (requestLine == null) return;
            Log.i(TAG, "request: " + requestLine);

            String body = DeviceInfo.buildHtmlPage(appContext);
            byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);

            String headers = "HTTP/1.0 200 OK\r\n"
                    + "Content-Type: text/html; charset=utf-8\r\n"
                    + "Content-Length: " + bodyBytes.length + "\r\n"
                    + "Connection: close\r\n"
                    + "\r\n";
            OutputStream out = s.getOutputStream();
            out.write(headers.getBytes(StandardCharsets.US_ASCII));
            out.write(bodyBytes);
            out.flush();
        } catch (IOException e) {
            Log.w(TAG, "connection error", e);
        }
    }
}
