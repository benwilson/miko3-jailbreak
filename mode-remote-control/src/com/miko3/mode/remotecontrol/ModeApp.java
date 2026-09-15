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
    private volatile CameraCapture cameraCapture;
    // Serializes camera open/close off whichever thread calls startCamera()/
    // stopCamera() — confirmed live: CameraCapture.stop() can now block for up to
    // 2s waiting for the HAL to actually release the device (see its own field
    // comment), and startCamera() calling that synchronously when called from
    // MainActivity.activateDriveController() (the main/UI thread, on a
    // reactivation where a camera was already running) blocked the whole
    // Activity — the WebView's own page load, DriveController's setup, and
    // anything else on that thread — for up to 2s, degrading into exactly the
    // "page never finishes loading, drive says not ready" state this was
    // supposed to prevent, not fix.
    private final java.util.concurrent.ExecutorService cameraExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private volatile String cameraError;
    private volatile DriveController driveController;
    private volatile Runnable exitRunnable;
    // Applies drive commands off "/drive-ws"'s own read loop (see that route's
    // comment) — a single worker that always runs the most recently submitted
    // command and drops anything superseded before it got to run, rather than a
    // plain queue that would fall behind and replay stale commands late. Every
    // "drive x y" sent while a control is held is a redundant re-assertion of the
    // same intent anyway, so dropping a stale one costs nothing.
    private final Object driveTaskLock = new Object();
    private Runnable pendingDriveTask;
    private boolean driveWorkerBusy;
    private final java.util.concurrent.ExecutorService driveExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    // Confirmed live: MainActivity's launchMode="singleTop" + onNewIntent()
    // reactivation means an old instance's teardown (onPause/onDestroy) can run
    // AFTER a newer instance's setup (onResume/onNewIntent's reactivation) when
    // launch intents arrive in quick succession (e.g. repeated /launch-mode
    // hits) — the old instance's teardown then wipes out the camera/drive
    // state the new instance just established, even though it's still active.
    // Each MainActivity captures the current generation when it (re)activates
    // and only tears down shared state here if it's still current.
    private volatile long generation = 0;
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
                    // No longer exclusive as of U15 (see DriveController.acceptsClient()'s
                    // comment) — this just registers the token as known/seen so this
                    // page's own first drive command isn't rejected for looking unfamiliar.
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
        // U13: a persistent channel for drive commands, replacing the old
        // repeated-fetch()-every-300ms approach — each of those fetches paid a
        // fresh HTTPS/TLS handshake, which over WiFi was slow enough to
        // routinely blow past DriveController's 750ms watchdog and cause
        // visible start/stop jank (confirmed live: "straight, jank, jank,
        // straight, jank"). The client still resends "drive x y" every ~200ms
        // while a control is held (same repeat-while-held shape as before), but
        // now as a cheap WS frame over an already-open socket instead of a new
        // connection each time.
        server.websocketRoute("/drive-ws", new RoutingHttpServer.WebSocketHandler() {
            @Override
            public void handle(HttpRequest req, com.miko3.shared.WebSocketConnection ws) throws IOException {
                final String token = req.queryParam("ct", null);
                final DriveController dc = driveController;
                if (dc == null || token == null) {
                    return;
                }
                String msg;
                try {
                    while ((msg = ws.readText()) != null) {
                        // Dispatched onto driveExecutor (coalescing — see its field
                        // comment) rather than calling dc.drive() inline here: that call
                        // makes a synchronous AIDL Binder call into ServiceExam, and
                        // under this session's confirmed heavy system load that call
                        // could itself be slow. Blocking THIS loop on it would stop
                        // draining the socket, backing up every WS frame behind it —
                        // exactly the "works once, then nothing for 750ms+" failure
                        // pattern confirmed live, even though the browser kept sending
                        // right on schedule the whole time.
                        if (msg.startsWith("drive ")) {
                            String[] parts = msg.substring(6).trim().split("\\s+");
                            if (parts.length >= 2) {
                                submitDrive(dc, token, HttpUtil.parseIntOr(parts[0], 0),
                                        HttpUtil.parseIntOr(parts[1], 0));
                            }
                        } else if ("stop".equals(msg)) {
                            submitDrive(dc, token, 0, 0);
                        }
                    }
                } finally {
                    // Best-effort final stop as soon as the connection ends (tab closed,
                    // network dropped, "stop" never made it) — defense in depth alongside
                    // DriveController's own 750ms watchdog, which would also catch this.
                    try {
                        dc.drive(token, 0, 0);
                    } catch (Exception ignored) {
                    }
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

        server.route("/device-view", new RoutingHttpServer.RouteHandler() {
            @Override
            public void handle(HttpRequest req, HttpResponse res) throws IOException {
                res.sendText(200, "OK", "text/html; charset=utf-8", DeviceViewPage.HTML);
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
    /** Coalescing dispatch for "/drive-ws" — see driveExecutor's field comment. */
    private void submitDrive(final DriveController dc, final String token, final int linear, final int angular) {
        synchronized (driveTaskLock) {
            pendingDriveTask = new Runnable() {
                @Override
                public void run() {
                    try {
                        dc.drive(token, linear, angular);
                    } catch (Exception e) {
                        Log.w(TAG, "drive-ws drive() failed", e);
                    }
                }
            };
            if (!driveWorkerBusy) {
                driveWorkerBusy = true;
                driveExecutor.execute(driveWorkerLoop);
            }
        }
    }

    private final Runnable driveWorkerLoop = new Runnable() {
        @Override
        public void run() {
            while (true) {
                Runnable task;
                synchronized (driveTaskLock) {
                    task = pendingDriveTask;
                    pendingDriveTask = null;
                    if (task == null) {
                        driveWorkerBusy = false;
                        return;
                    }
                }
                task.run();
            }
        }
    };

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

    /** Bumps and returns the new "generation" — call once whenever a
     * MainActivity instance (re)establishes itself as the active one (its own
     * onCreate() or onNewIntent()'s reactivation branch), then compare against
     * currentGeneration() before tearing down camera/drive state so a stale,
     * still-finishing older instance can't clobber a newer one's setup. */
    long bumpGeneration() {
        return ++generation;
    }

    long currentGeneration() {
        return generation;
    }

    // U18's re-enable (moot ServiceExam lock, fixed CameraCapture lifecycle bugs) was
    // half right and half wrong: those were real, but NOT the whole story. Re-enabling
    // surfaced a live, ongoing vendor camerahalserver bug — confirmed via logcat, a
    // continuous "pixel rate should not be zero" HAL error firing many times per
    // second, each one forking an AEE crash-report worker, independent of whether any
    // client is even watching /stream.mjpeg (capture runs for the mode's whole
    // lifetime per this class's own javadoc). Confirmed via `top`: camerahalserver
    // alone pegged ~76-86% of one core, aggregate CPU with this app running climbed
    // from ~13% to ~63%+, idle across all 4 cores dropped to ~17% (67/400%) — severe
    // enough to starve the drive WebSocket connection's own thread, surfacing as held
    // drive buttons "sticking" (repeat commands still processed, but so delayed that
    // release's stop trails badly behind, or never visibly catches up). This is a
    // vendor HAL/driver-level fault, not something fixable from this class — back off
    // until either the HAL bug itself is addressed (firmware/vendor blob update, or a
    // capture-parameter tweak that avoids whatever triggers "pixel rate should not be
    // zero") or a real fix is found. Do not re-enable on the same "isolation is done"
    // reasoning without first confirming this specific HAL error loop is gone.
    private static final boolean CAMERA_ENABLED = false;

    void startCamera() {
        if (!CAMERA_ENABLED) {
            cameraError = "camera temporarily disabled for diagnostics";
            return;
        }
        // Guards against a leaked, still-open camera handle if this is somehow
        // called twice without an intervening stopCamera() — the camera HAL is
        // exclusive-access, so an unreleased prior CameraCapture here would make
        // the new one fail with "no camera found" (confirmed live). The actual
        // stop() (and the fresh start() right after) run on cameraExecutor, off
        // the calling thread — see that field's comment for why.
        final CameraCapture old = cameraCapture;
        cameraCapture = null;
        cameraError = null;
        cameraExecutor.execute(new Runnable() {
            @Override
            public void run() {
                if (old != null) {
                    old.stop();
                }
                CameraCapture fresh = new CameraCapture(ModeApp.this, mjpegBroadcaster,
                        new CameraCapture.ErrorListener() {
                            @Override
                            public void onCameraError(String reason) {
                                cameraError = reason;
                                Log.e(TAG, "camera error: " + reason);
                            }
                        });
                cameraCapture = fresh;
                fresh.start();
            }
        });
    }

    void stopCamera() {
        final CameraCapture old = cameraCapture;
        cameraCapture = null;
        if (old != null) {
            cameraExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    old.stop();
                }
            });
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
