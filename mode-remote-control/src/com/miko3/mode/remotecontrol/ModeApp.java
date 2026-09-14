package com.miko3.mode.remotecontrol;

import android.app.Application;
import android.util.Log;

import com.miko3.shared.HttpRequest;
import com.miko3.shared.HttpResponse;
import com.miko3.shared.HttpsSupport;
import com.miko3.shared.HttpUtil;
import com.miko3.shared.RoutingHttpServer;

import java.io.IOException;
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
    // U12: each app now gets its own dedicated port pair rather than sharing
    // one (see launcher/LauncherApp.java's PORT/HTTPS_PORT comment for why
    // U11's shared-port design was reverted). launcher/LauncherApp owns the
    // fixed 8080/8443 "Robot Home" pair; this is the next app, incrementing
    // by one from there. No shared constant between the two apps' build units
    // (separate APKs, only shared/ is compiled into both) — keep this literal
    // in sync with LauncherApp.PORT/HTTPS_PORT + 1 if either ever changes.
    static final int PORT = 8081;
    static final int HTTPS_PORT = 8444;

    private RoutingHttpServer server;
    private final MjpegBroadcaster mjpegBroadcaster = new MjpegBroadcaster();
    // Reuses MjpegBroadcaster's generic JPEG fan-out for the reverse direction:
    // a remote browser's own webcam (captured client-side via getUserMedia, since
    // only it has both the camera and the user's consent) uploads frames here one
    // at a time; the on-device WebView's operator-video-img subscribes to the
    // resulting stream exactly like the main camera view does to mjpegBroadcaster.
    private final MjpegBroadcaster operatorVideoBroadcaster = new MjpegBroadcaster();
    private CameraCapture cameraCapture;
    private volatile String cameraError;
    private volatile DriveController driveController;
    private volatile Runnable exitRunnable;
    private final java.util.Random tokenRandom = new java.util.Random();
    private final AudioBroadcaster audioBroadcaster = new AudioBroadcaster();
    private MicCapture micCapture;
    private volatile String micError;
    private volatile byte[] cssBytes;

    @Override
    public void onCreate() {
        super.onCreate();
        startServer();
    }

    /**
     * Creates and starts the RoutingHttpServer, registering every route.
     * Called once from onCreate() — this app's own dedicated port pair (see
     * class field comment) means this server never needs to stop for the
     * launcher's sake, so it just runs for the lifetime of the process.
     */
    private void startServer() {
        server = new RoutingHttpServer(this, PORT);
        server.route("/", new RoutingHttpServer.RouteHandler() {
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
        server.route("/audio.pcm", new RoutingHttpServer.RouteHandler() {
            @Override
            public void handle(HttpRequest req, HttpResponse res) throws IOException {
                if (micError != null) {
                    res.sendText(503, "Service Unavailable", "text/plain; charset=utf-8",
                            "mic unavailable: " + micError);
                    return;
                }
                res.startStreaming(200, "OK", "application/octet-stream",
                        "X-Sample-Rate: " + MicCapture.SAMPLE_RATE + "\r\n"
                        + "X-Sample-Format: s16le\r\n"
                        + "X-Channels: 1\r\n");
                OutputStream out = res.rawOutputStream();
                final Object doneLock = new Object();
                final boolean[] done = {false};
                audioBroadcaster.subscribe(out, new Runnable() {
                    @Override
                    public void run() {
                        synchronized (doneLock) {
                            done[0] = true;
                            doneLock.notifyAll();
                        }
                    }
                });
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
        server.route("/toggle-mic", new RoutingHttpServer.RouteHandler() {
            @Override
            public void handle(HttpRequest req, HttpResponse res) throws IOException {
                authorizeClient(req, res, false);
                if (res.isHeadersSent()) {
                    return;
                }
                boolean on = "true".equals(req.queryParam("on", "false"));
                if (on) {
                    startMic();
                } else {
                    stopMic();
                }
                res.sendText(200, "OK", "text/plain; charset=utf-8", "ok");
            }
        });
        server.route("/drive", new RoutingHttpServer.RouteHandler() {
            @Override
            public void handle(HttpRequest req, HttpResponse res) throws IOException {
                // R17's client-token check happens INSIDE dc.drive() itself, atomically
                // with the actual dispatch (see DriveController.StaleClientException) —
                // not as a separate pre-check here, since a pre-check-then-act split
                // leaves a window where a newer page's claimClient() lands in between,
                // letting an already-authorized stale request still reach the motors.
                DriveController dc = driveController;
                if (dc == null) {
                    res.sendText(503, "Service Unavailable", "text/plain; charset=utf-8", "mode not ready");
                    return;
                }
                int linear = HttpUtil.parseIntOr(req.queryParam("linear", "0"), 0);
                int angular = HttpUtil.parseIntOr(req.queryParam("angular", "0"), 0);
                String token = req.queryParam("ct", null);
                try {
                    dc.drive(token, linear, angular);
                    res.sendText(200, "OK", "text/plain; charset=utf-8", "ok");
                } catch (DriveController.StaleClientException e) {
                    res.sendText(409, "Conflict", "text/plain; charset=utf-8",
                            "control taken by another connection");
                } catch (android.os.RemoteException e) {
                    res.sendText(502, "Bad Gateway", "text/plain; charset=utf-8",
                            "drive failed: " + e.getMessage());
                }
            }
        });
        server.route("/keepalive", new RoutingHttpServer.RouteHandler() {
            @Override
            public void handle(HttpRequest req, HttpResponse res) throws IOException {
                DriveController dc = driveController;
                if (dc == null) {
                    res.sendText(503, "Service Unavailable", "text/plain; charset=utf-8", "mode not ready");
                    return;
                }
                String token = req.queryParam("ct", null);
                try {
                    dc.keepalive(token);
                    res.sendText(200, "OK", "text/plain; charset=utf-8", "ok");
                } catch (DriveController.StaleClientException e) {
                    res.sendText(409, "Conflict", "text/plain; charset=utf-8",
                            "control taken by another connection");
                }
            }
        });
        server.route("/exit", new RoutingHttpServer.RouteHandler() {
            @Override
            public void handle(HttpRequest req, HttpResponse res) throws IOException {
                authorizeClient(req, res, false);
                if (res.isHeadersSent()) {
                    return;
                }
                res.sendText(200, "OK", "text/plain; charset=utf-8", "ok");
                Runnable r = exitRunnable;
                if (r != null) {
                    r.run();
                }
            }
        });
        server.route("/assets/pico.min.css", new RoutingHttpServer.RouteHandler() {
            @Override
            public void handle(HttpRequest req, HttpResponse res) throws IOException {
                if (cssBytes == null) {
                    cssBytes = HttpUtil.readAssetBytes(ModeApp.this, "pico.min.css");
                }
                res.sendBytes(200, "OK", "text/css; charset=utf-8", cssBytes);
            }
        });
        server.route("/stream.mjpeg", new RoutingHttpServer.RouteHandler() {
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
                // than returning immediately and letting RoutingHttpServer close the
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
        server.route("/operator-video-upload", new RoutingHttpServer.RouteHandler() {
            @Override
            public void handle(HttpRequest req, HttpResponse res) throws IOException {
                // No client-token gate here (unlike /drive): this only ever publishes
                // frames into a broadcaster real viewers pull from, so a stray/late
                // upload from a client that lost the drive lease is harmless, not a
                // safety issue the way a stray motor command would be.
                byte[] jpeg = HttpUtil.readAll(req.body);
                if (jpeg.length > 0) {
                    operatorVideoBroadcaster.publishFrame(jpeg);
                }
                res.sendText(200, "OK", "text/plain; charset=utf-8", "ok");
            }
        });
        server.route("/operator-video-stream", new RoutingHttpServer.RouteHandler() {
            @Override
            public void handle(HttpRequest req, HttpResponse res) throws IOException {
                res.startStreaming(200, "OK", operatorVideoBroadcaster.contentType(), null);
                OutputStream out = res.rawOutputStream();
                final Object doneLock = new Object();
                final boolean[] done = {false};
                operatorVideoBroadcaster.subscribe(out, new Runnable() {
                    @Override
                    public void run() {
                        synchronized (doneLock) {
                            done[0] = true;
                            doneLock.notifyAll();
                        }
                    }
                });
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

        // getUserMedia (operator webcam capture) needs a secure context once this
        // page is reached over the robot's real WiFi IP rather than the
        // http://127.0.0.1 adb-tunnel loopback exception. The plain HTTP listener
        // above starts redirecting to this the moment it's actually bound (see
        // RoutingHttpServer.startHttps's javadoc).
        javax.net.ssl.SSLContext httpsContext = HttpsSupport.loadServerContext(this);
        if (httpsContext != null) {
            server.startHttps(HTTPS_PORT, httpsContext);
        } else {
            Log.w(TAG, "HTTPS certificate failed to load — serving plain HTTP only");
        }
    }

    RoutingHttpServer server() {
        return server;
    }

    /**
     * R17's client-token check, shared by every route that gates on the
     * currently-authorized client: writes a 409 if a live controller says the
     * request's token isn't current. When requireReady is true, a null
     * controller (mode not yet ready) also writes a 503, and the returned
     * value alone tells the caller whether to proceed. When requireReady is
     * false, a null controller is not itself a failure (the caller proceeds
     * regardless) — callers there must check res.isHeadersSent() instead,
     * since a null return no longer means "stop."
     */
    private DriveController authorizeClient(HttpRequest req, HttpResponse res, boolean requireReady)
            throws IOException {
        DriveController dc = driveController;
        if (dc == null) {
            if (requireReady) {
                res.sendText(503, "Service Unavailable", "text/plain; charset=utf-8", "mode not ready");
            }
            return null;
        }
        if (!dc.acceptsClient(req.queryParam("ct", null))) {
            res.sendText(409, "Conflict", "text/plain; charset=utf-8", "control taken by another connection");
            return null;
        }
        return dc;
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

    private void startMic() {
        if (micCapture != null) {
            return;
        }
        micError = null;
        micCapture = new MicCapture(this, audioBroadcaster, new MicCapture.ErrorListener() {
            @Override
            public void onMicError(String reason) {
                micError = reason;
                Log.e(TAG, "mic error: " + reason);
            }
        });
        micCapture.start();
    }

    private void stopMic() {
        if (micCapture != null) {
            micCapture.stop();
            micCapture = null;
        }
    }

    void setDriveController(DriveController controller) {
        driveController = controller;
    }

    void setExitRunnable(Runnable runnable) {
        exitRunnable = runnable;
    }

}
