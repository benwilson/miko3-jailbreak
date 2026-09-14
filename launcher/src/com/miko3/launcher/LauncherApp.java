package com.miko3.launcher;

import android.app.Application;

import com.miko3.shared.HttpRequest;
import com.miko3.shared.HttpResponse;
import com.miko3.shared.RoutingHttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

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
    static final int PORT = 8080;

    private RoutingHttpServer server;
    private WifiHttpHandler wifi;

    @Override
    public void onCreate() {
        super.onCreate();
        wifi = new WifiHttpHandler(this);
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
                res.sendBytes(200, "OK", "text/css; charset=utf-8", readAsset("pico.min.css"));
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
                int id = parseIntOr(req.queryParam("id", ""), -1);
                if (id >= 0) {
                    wifi.connectSaved(id);
                }
                res.redirect("/?status=" + urlEncode("Connecting...") + "&pending=1");
            }
        });
        server.route("/wifi/forget", new RoutingHttpServer.RouteHandler() {
            @Override
            public void handle(HttpRequest req, HttpResponse res) throws IOException {
                int id = parseIntOr(req.queryParam("id", ""), -1);
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
        server.route("/wifi/status", new RoutingHttpServer.RouteHandler() {
            @Override
            public void handle(HttpRequest req, HttpResponse res) throws IOException {
                res.sendText(200, "OK", "text/plain; charset=utf-8", wifi.connectionStatus());
            }
        });

        Thread t = new Thread(server, "launcher-http-server");
        t.setDaemon(true);
        t.start();

        // DriveLeaseService is started lazily by the first mode's bindService()
        // (BIND_AUTO_CREATE), not explicitly here: Application.onCreate() is not
        // guaranteed to run in a foreground-exempted context (confirmed live —
        // startService() here threw IllegalStateException "not allowed to start
        // service ... app is in background" when the launcher process was created
        // by launching its own Activity right after a force-stop, which Android's
        // background-service-start restrictions on API 26+ can classify as
        // background depending on device idle state). A bound service has no such
        // restriction and Android keeps it alive for as long as a client holds the
        // bind, which is exactly the coordinator's required lifetime.
    }

    private static int parseIntOr(String s, int fallback) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
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
