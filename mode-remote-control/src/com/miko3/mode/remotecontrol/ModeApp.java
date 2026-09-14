package com.miko3.mode.remotecontrol;

import android.app.Application;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Starts the mode's HTTP server once per process, independent of Activity
 * lifecycle — mirrors launcher/LauncherApp.java's shape. Also owns the
 * camera capture + MJPEG broadcaster (U6): one shared capture session for
 * the whole process, started/stopped by MainActivity's lifecycle rather
 * than per HTTP connection, so R4's "camera view whenever the mode is
 * active" holds independent of whether any viewer is currently attached.
 */
public class ModeApp extends Application {
    private static final String TAG = "ModeApp";
    static final int PORT = 8090;

    private ModeHttpServer server;
    private final MjpegBroadcaster mjpegBroadcaster = new MjpegBroadcaster();
    private CameraCapture cameraCapture;
    private volatile String cameraError;
    private volatile DriveController driveController;
    private volatile Runnable exitRunnable;
    private final java.util.Random tokenRandom = new java.util.Random();

    @Override
    public void onCreate() {
        super.onCreate();
        server = new ModeHttpServer(this, PORT);
        server.route("/", new ModeHttpServer.RouteHandler() {
            @Override
            public void handle(HttpRequest req, HttpResponse res) throws IOException {
                String token = Long.toHexString(tokenRandom.nextLong());
                DriveController dc = driveController;
                if (dc != null) {
                    // A fresh page load is "a newer connection" (R17) — it takes over
                    // control outright rather than waiting to be first to send a command.
                    dc.claimClient(token);
                }
                res.sendText(200, "OK", "text/html; charset=utf-8", ModePage.buildIndexHtml(token));
            }
        });
        server.route("/drive", new ModeHttpServer.RouteHandler() {
            @Override
            public void handle(HttpRequest req, HttpResponse res) throws IOException {
                DriveController dc = driveController;
                if (dc == null) {
                    res.sendText(503, "Service Unavailable", "text/plain; charset=utf-8", "mode not ready");
                    return;
                }
                String token = req.queryParam("ct", null);
                if (!dc.acceptsClient(token)) {
                    res.sendText(409, "Conflict", "text/plain; charset=utf-8",
                            "control taken by another connection");
                    return;
                }
                int linear = parseIntOr(req.queryParam("linear", "0"), 0);
                int angular = parseIntOr(req.queryParam("angular", "0"), 0);
                try {
                    dc.drive(linear, angular);
                    res.sendText(200, "OK", "text/plain; charset=utf-8", "ok");
                } catch (android.os.RemoteException e) {
                    res.sendText(502, "Bad Gateway", "text/plain; charset=utf-8",
                            "drive failed: " + e.getMessage());
                }
            }
        });
        server.route("/keepalive", new ModeHttpServer.RouteHandler() {
            @Override
            public void handle(HttpRequest req, HttpResponse res) throws IOException {
                DriveController dc = driveController;
                if (dc == null) {
                    res.sendText(503, "Service Unavailable", "text/plain; charset=utf-8", "mode not ready");
                    return;
                }
                String token = req.queryParam("ct", null);
                if (!dc.acceptsClient(token)) {
                    res.sendText(409, "Conflict", "text/plain; charset=utf-8",
                            "control taken by another connection");
                    return;
                }
                dc.keepalive();
                res.sendText(200, "OK", "text/plain; charset=utf-8", "ok");
            }
        });
        server.route("/exit", new ModeHttpServer.RouteHandler() {
            @Override
            public void handle(HttpRequest req, HttpResponse res) throws IOException {
                DriveController dc = driveController;
                String token = req.queryParam("ct", null);
                if (dc != null && !dc.acceptsClient(token)) {
                    res.sendText(409, "Conflict", "text/plain; charset=utf-8",
                            "control taken by another connection");
                    return;
                }
                res.sendText(200, "OK", "text/plain; charset=utf-8", "ok");
                Runnable r = exitRunnable;
                if (r != null) {
                    r.run();
                }
            }
        });
        server.route("/assets/pico.min.css", new ModeHttpServer.RouteHandler() {
            @Override
            public void handle(HttpRequest req, HttpResponse res) throws IOException {
                byte[] css = readAsset("pico.min.css");
                res.sendBytes(200, "OK", "text/css; charset=utf-8", css);
            }
        });
        server.route("/stream.mjpeg", new ModeHttpServer.RouteHandler() {
            @Override
            public void handle(HttpRequest req, HttpResponse res) throws IOException {
                if (cameraError != null) {
                    res.sendText(503, "Service Unavailable", "text/plain; charset=utf-8",
                            "camera unavailable: " + cameraError);
                    return;
                }
                res.startStreaming(200, "OK", mjpegBroadcaster.contentType(), null);
                OutputStream out = res.rawOutputStream();
                final Object doneLock = new Object();
                final boolean[] done = {false};
                mjpegBroadcaster.subscribe(out, new Runnable() {
                    @Override
                    public void run() {
                        synchronized (doneLock) {
                            done[0] = true;
                            doneLock.notifyAll();
                        }
                    }
                });
                // Block this connection thread until publishFrame() observes a write
                // failure on `out` (the client disconnected) and notifies us, rather
                // than returning immediately and letting ModeHttpServer close the
                // socket out from under a still-live stream.
                synchronized (doneLock) {
                    while (!done[0]) {
                        try {
                            doneLock.wait(5000);
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                }
            }
        });

        Thread t = new Thread(server, "mode-http-server");
        t.setDaemon(true);
        t.start();
    }

    ModeHttpServer server() {
        return server;
    }

    void startCamera() {
        cameraError = null;
        cameraCapture = new CameraCapture(this, mjpegBroadcaster, new CameraCapture.ErrorListener() {
            @Override
            public void onCameraError(String reason) {
                cameraError = reason;
                Log.e(TAG, "camera error: " + reason);
            }
        });
        cameraCapture.start();
    }

    void stopCamera() {
        if (cameraCapture != null) {
            cameraCapture.stop();
            cameraCapture = null;
        }
    }

    void setDriveController(DriveController controller) {
        driveController = controller;
    }

    void setExitRunnable(Runnable runnable) {
        exitRunnable = runnable;
    }

    private static int parseIntOr(String s, int fallback) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
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
