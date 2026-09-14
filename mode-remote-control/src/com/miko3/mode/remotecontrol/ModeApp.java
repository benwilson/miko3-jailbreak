package com.miko3.mode.remotecontrol;

import android.app.Application;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Starts the mode's HTTP server once per process, independent of Activity
 * lifecycle — mirrors launcher/LauncherApp.java's shape.
 */
public class ModeApp extends Application {
    private static final String TAG = "ModeApp";
    static final int PORT = 8090;

    private ModeHttpServer server;

    @Override
    public void onCreate() {
        super.onCreate();
        server = new ModeHttpServer(this, PORT);
        server.route("/", new ModeHttpServer.RouteHandler() {
            @Override
            public void handle(HttpRequest req, HttpResponse res) throws IOException {
                res.sendText(200, "OK", "text/html; charset=utf-8", ModePage.buildIndexHtml());
            }
        });
        server.route("/assets/pico.min.css", new ModeHttpServer.RouteHandler() {
            @Override
            public void handle(HttpRequest req, HttpResponse res) throws IOException {
                byte[] css = readAsset("pico.min.css");
                res.sendBytes(200, "OK", "text/css; charset=utf-8", css);
            }
        });

        Thread t = new Thread(server, "mode-http-server");
        t.setDaemon(true);
        t.start();
    }

    ModeHttpServer server() {
        return server;
    }

    private byte[] readAsset(String name) throws IOException {
        InputStream in = getAssets().open(name);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }
}
