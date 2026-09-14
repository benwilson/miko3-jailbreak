package com.miko3.launcher;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import com.miko3.shared.DriveLease;
import com.miko3.shared.HttpRequest;
import com.miko3.shared.HttpResponse;
import com.miko3.shared.HttpsSupport;
import com.miko3.shared.HttpUtil;
import com.miko3.shared.LauncherProtocol;
import com.miko3.shared.RoutingHttpServer;

import java.io.IOException;

/**
 * Starts the launcher's HTTP server and the drive-lease coordinator once
 * per process, independent of Activity lifecycle — the coordinator
 * (R3/KTD3) must survive exactly the events (a mode's crash or exit) it
 * arbitrates, and the server must be reachable whether or not the
 * launcher's own screen is currently in front.
 *
 * Replaces the original single-route InfoHttpServer (a read-only device
 * page) with RoutingHttpServer (U9): the served page now combines device
 * info and the Wi-Fi section (R8/R11), and Wi-Fi actions are HTTP routes
 * instead of View callbacks, reachable on-device and from a remote
 * browser (R10) alike.
 */
public class LauncherApp extends Application {
    private static final String TAG = "LauncherApp";
    static final int PORT = 8080;
    static final int HTTPS_PORT = 8443;

    // The one mode that exists today (U10). A future second mode needs a real
    // registry mapping lease-holder clientId -> package/Activity; not built
    // speculatively ahead of there being a second mode to design it against.
    private static final String MODE_PACKAGE = "com.miko3.mode.remotecontrol";
    private static final String MODE_ACTIVITY = MODE_PACKAGE + ".MainActivity";

    private RoutingHttpServer server;
    private WifiHttpHandler wifi;
    private volatile DriveLease leaseClient;
    private volatile byte[] cssBytes;

    private final ServiceConnection leaseConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            leaseClient = DriveLease.Stub.asInterface(binder);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            leaseClient = null;
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        wifi = new WifiHttpHandler(this);
        startServer();

        // Binds to (and so creates) DriveLeaseService itself, via bindService()
        // rather than startService(): Application.onCreate() is not guaranteed to
        // run in a foreground-exempted context (confirmed live — startService()
        // here threw IllegalStateException "not allowed to start service ... app
        // is in background" depending on device idle state at launch), but a bound
        // service has no such restriction. This also gives /launch-mode below a
        // DriveLease handle to query the current holder before switching modes.
        bindService(new Intent(this, DriveLeaseService.class), leaseConnection, Context.BIND_AUTO_CREATE);
    }

    /**
     * Creates a fresh RoutingHttpServer, registers every route, and starts
     * both its listeners. Idempotent-safe to call again after stopServer()
     * (e.g. MainActivity.onResume() reclaiming the port once a mode exits
     * back to the launcher) — a stopped RoutingHttpServer isn't reused, a
     * new one is built instead, since re-registering a handful of route()
     * calls is simpler than making the class itself restart-safe.
     */
    boolean startServer() {
        if (server != null) {
            return false; // already running — a mode's exit and this Activity's
                           // onResume can both race to reclaim the port; only the
                           // first should act, and the caller (MainActivity) uses
                           // this return value to decide whether its WebView needs
                           // reloading (server was actually down, not just already up).
        }
        server = new RoutingHttpServer(this, PORT);

        server.route("/", new RoutingHttpServer.RouteHandler() {
            @Override
            public void handle(HttpRequest req, HttpResponse res) throws IOException {
                String status = req.queryParam("status", null);
                boolean pending = "1".equals(req.queryParam("pending", null));
                res.sendText(200, "OK", "text/html; charset=utf-8",
                        LauncherPage.buildIndexHtml(LauncherApp.this, wifi, status, pending));
            }
        });
        server.route("/assets/pico.min.css", new RoutingHttpServer.RouteHandler() {
            @Override
            public void handle(HttpRequest req, HttpResponse res) throws IOException {
                if (cssBytes == null) {
                    cssBytes = HttpUtil.readAssetBytes(LauncherApp.this, "pico.min.css");
                }
                res.sendBytes(200, "OK", "text/css; charset=utf-8", cssBytes);
            }
        });
        server.route("/wifi/connect", new RoutingHttpServer.RouteHandler() {
            @Override
            public void handle(HttpRequest req, HttpResponse res) throws IOException {
                String ssid = req.queryParam("ssid", "").trim();
                String password = req.queryParam("password", "");
                if (ssid.isEmpty()) {
                    res.redirect("/?status=" + urlEncode("Enter an SSID"));
                    return;
                }
                boolean ok = wifi.connectManual(ssid, password);
                String msg = ok ? "Connecting to " + ssid + "..." : "Failed to add network";
                res.redirect("/?status=" + urlEncode(msg) + (ok ? "&pending=1" : ""));
            }
        });
        server.route("/wifi/connect-saved", new RoutingHttpServer.RouteHandler() {
            @Override
            public void handle(HttpRequest req, HttpResponse res) throws IOException {
                int id = HttpUtil.parseIntOr(req.queryParam("id", ""), -1);
                if (id >= 0) {
                    wifi.connectSaved(id);
                }
                res.redirect("/?status=" + urlEncode("Connecting...") + "&pending=1");
            }
        });
        server.route("/wifi/forget", new RoutingHttpServer.RouteHandler() {
            @Override
            public void handle(HttpRequest req, HttpResponse res) throws IOException {
                int id = HttpUtil.parseIntOr(req.queryParam("id", ""), -1);
                if (id >= 0) {
                    wifi.forget(id);
                }
                res.redirect("/");
            }
        });
        server.route("/wifi/disconnect", new RoutingHttpServer.RouteHandler() {
            @Override
            public void handle(HttpRequest req, HttpResponse res) throws IOException {
                wifi.disconnect();
                res.redirect("/?status=" + urlEncode("Disconnected"));
            }
        });
        server.route("/wifi/rescan", new RoutingHttpServer.RouteHandler() {
            @Override
            public void handle(HttpRequest req, HttpResponse res) throws IOException {
                boolean started = wifi.startScan();
                res.redirect("/?status=" + urlEncode(started ? "Scanning..." : "Scan request failed (throttled?)"));
            }
        });
        server.route("/device-status", new RoutingHttpServer.RouteHandler() {
            @Override
            public void handle(HttpRequest req, HttpResponse res) throws IOException {
                int battery = DeviceInfo.batteryPercent(LauncherApp.this);
                String batteryStr = battery < 0 ? "unknown" : (battery + "%");
                res.sendText(200, "OK", "text/plain; charset=utf-8", batteryStr + "|" + DeviceInfo.uptime());
            }
        });
        server.route("/wifi/status", new RoutingHttpServer.RouteHandler() {
            @Override
            public void handle(HttpRequest req, HttpResponse res) throws IOException {
                res.sendText(200, "OK", "text/plain; charset=utf-8", wifi.connectionStatus());
            }
        });
        server.route("/launch-mode", new RoutingHttpServer.RouteHandler() {
            @Override
            public void handle(HttpRequest req, HttpResponse res) throws IOException {
                launchModeGracefully();
                res.redirect("/");
            }
        });

        Thread t = new Thread(server, "launcher-http-server");
        t.setDaemon(true);
        t.start();

        // See mode-remote-control/ModeApp's identical block for why: getUserMedia
        // needs a secure context, which plain HTTP to a WiFi LAN IP doesn't
        // qualify as. Once bound, the plain listener above starts redirecting here.
        javax.net.ssl.SSLContext httpsContext = HttpsSupport.loadServerContext(this);
        if (httpsContext != null) {
            server.startHttps(HTTPS_PORT, httpsContext);
        } else {
            Log.w(TAG, "HTTPS certificate failed to load — serving plain HTTP only");
        }
        return true;
    }

    /**
     * Stops the current server and drops the reference so startServer() can
     * rebuild it later. Called from launchModeGracefully() right before
     * handing off to the mode app, so the mode's own RoutingHttpServer can
     * bind the same port the launcher was just using.
     */
    void stopServer() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    /**
     * U10: launches the mode app, handing off gracefully if another mode
     * currently holds the drive lease — never a process kill (the plan's own
     * cited learning: docs/solutions/runtime-errors/
     * kill-9-on-watched-service-permanently-disables-restart.md). Instead
     * requests the outgoing mode exit through its own normal release path
     * (the same one its own "Exit mode" control uses), waits for the lease
     * to actually clear, then launches the new mode — release-then-acquire,
     * never a race where both could hold it.
     */
    private void launchModeGracefully() {
        DriveLease lease = leaseClient;
        String holder = null;
        if (lease != null) {
            try {
                holder = lease.getHolder();
            } catch (RemoteException e) {
                Log.w(TAG, "getHolder() failed, proceeding as if unheld", e);
            }
        }

        if (holder != null) {
            Log.i(TAG, "mode switch: requesting outgoing mode (holder='" + holder + "') to exit");
            Intent exitRequest = new Intent();
            exitRequest.setClassName(MODE_PACKAGE, MODE_ACTIVITY);
            exitRequest.putExtra(LauncherProtocol.EXTRA_FORCE_EXIT, true);
            exitRequest.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            try {
                startActivity(exitRequest);
            } catch (Exception e) {
                Log.w(TAG, "could not deliver exit request to outgoing mode", e);
            }

            long deadline = SystemClock.elapsedRealtime() + 5000;
            while (SystemClock.elapsedRealtime() < deadline) {
                try {
                    if (lease.getHolder() == null) {
                        break;
                    }
                } catch (RemoteException e) {
                    break; // coordinator unreachable — nothing left to wait on
                }
                try {
                    Thread.sleep(150);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            // The lease clearing (above) confirms the outgoing mode's release() ran,
            // but its finish()/onDestroy() teardown is a separate, unsynchronized
            // Android lifecycle step — confirmed live: launching the new mode
            // immediately after only the lease cleared could still land on the
            // outgoing Activity's singleTop instance mid-teardown (delivered via
            // onNewIntent with no FORCE_EXIT extra, which it doesn't otherwise
            // handle), leaving neither instance in front. A short grace period lets
            // that teardown finish first.
            try {
                Thread.sleep(400);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }

        // Releases the shared port pair (PORT/HTTPS_PORT) before the mode app tries
        // to bind them — both apps now serve on the same ports (U11: "why is remote
        // control on a different port" had no good answer once asked), so exactly
        // one of the two processes can hold the listeners at a time. bindWithRetry()
        // on the mode's side absorbs the brief TIME_WAIT/handoff race after this.
        stopServer();

        Intent launch = new Intent();
        launch.setClassName(MODE_PACKAGE, MODE_ACTIVITY);
        launch.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(launch);
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }
}
