package com.miko3.launcher;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import com.miko3.shared.ClaudeApi;
import com.miko3.shared.ClaudeHttpsTransport;
import com.miko3.shared.HttpRequest;
import com.miko3.shared.HttpResponse;
import com.miko3.shared.HttpsSupport;
import com.miko3.shared.HttpUtil;
import com.miko3.shared.LauncherProtocol;
import com.miko3.shared.ModeRegistry;
import com.miko3.shared.PageToken;
import com.miko3.shared.RoutingHttpServer;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

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
    // Robot Home's dedicated port pair (U12): every app — launcher included —
    // now gets its own fixed ports instead of sharing one pair (U11's shared-port
    // design was reverted after live testing kept surfacing handoff-timing bugs:
    // dropped listeners, stale reactivated instances, races between which
    // process currently owned the port). 8443 is the fixed "always reachable"
    // home HTTPS port every mode's page links back to (see mode-remote-control's
    // ModePage.java/DriveSection.java); other apps increment from here (see
    // ModeApp.PORT/HTTPS_PORT). No handoff logic needed anymore — this server
    // just runs for the launcher process's whole lifetime.
    static final int PORT = 8080;
    static final int HTTPS_PORT = LauncherProtocol.LAUNCHER_HTTPS_PORT;

    // Bound for each loopback presence probe (connect and read each). A live
    // mode answers in a few ms; a dead one refuses at once. Only a wedged
    // process ever uses the whole bound.
    private static final int PRESENCE_TIMEOUT_MS = 500;

    private RoutingHttpServer server;
    private WifiHttpHandler wifi;
    private ClaudeSettings claudeSettings;
    private SpeechEngine speech;
    private ListenEngine listen;
    private PeopleStore people;
    // The Settings page's tokens (KTD6). Four, so the robot's own WebView sitting
    // on the page doesn't expire a LAN browser's form, or the other way round.
    private final PageToken settingsToken = new PageToken(4);
    private final ClaudeApi claudeApi = new ClaudeApi(new ClaudeHttpsTransport());
    private volatile byte[] cssBytes;

    // The Settings page's Voice section speaks in-process, straight into the
    // engine's queue (no binding to our own SpeechService). Its lines are owned
    // by this object, so a mode's cancel() never drops them.
    private final SettingsPage.Speaker settingsSpeaker = new SettingsSpeaker();

    private final class SettingsSpeaker implements SettingsPage.Speaker {
        private volatile String voiceName;

        @Override
        public boolean ready() {
            return speech.isReady();
        }

        @Override
        public boolean failed() {
            return speech.hasFailed();
        }

        @Override
        public String voiceName() {
            String name = voiceName;
            if (name == null) {
                name = speech.voiceName();
                voiceName = name;
            }
            return name;
        }

        @Override
        public void say(String text) {
            long id = speech.queue().speak(this, text, new SpeechQueue.Listener() {
                @Override
                public void finished() {
                }

                @Override
                public void cancelled() {
                }

                @Override
                public void failed(String reason) {
                }
            });
            Log.i(TAG, "settings page queued line " + id);
        }
    }

    // Held only to create and keep alive DriveLeaseService, the modes' motor
    // arbiter (R3/KTD3). The launcher never queries the holder: which mode is
    // running comes from presence (KTD8), since the voice mode never takes the
    // lease (R18).
    private final ServiceConnection leaseConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        // Before the server starts, so no request can see it unset.
        claudeSettings = new ClaudeSettings(
                new PrefsStore(getSharedPreferences(ClaudeSettings.PREFS_NAME, MODE_PRIVATE)),
                new ClaudeSettings.Clock() {
                    @Override
                    public long nowMillis() {
                        return System.currentTimeMillis();
                    }
                });
        // The people the robot remembers (explore-on-claude plan U2): faces and
        // an index in launcher-private files, for PeopleService and the People
        // section. Loaded before the server starts, like the settings.
        people = new PeopleStore(new File(getFilesDir(), "people"), new PeopleStore.Clock() {
            @Override
            public long nowMillis() {
                return System.currentTimeMillis();
            }
        });
        // The robot's voice (voice plan U5): loaded once, on its own thread, so
        // SpeechService is ready by the time a mode asks it to speak.
        speech = new SpeechEngine(this);
        speech.start();
        // The robot's ears (explore-on-claude plan U3): the recognizer loads on
        // the listen thread; each listen waits for the speech queue to go idle.
        listen = new ListenEngine(this, speech.queue());
        listen.start();
        wifi = new WifiHttpHandler(this);
        startServer();

        // Binds to (and so creates) DriveLeaseService itself, via bindService()
        // rather than startService(): Application.onCreate() is not guaranteed to
        // run in a foreground-exempted context (confirmed live — startService()
        // here threw IllegalStateException "not allowed to start service ... app
        // is in background" depending on device idle state at launch), but a bound
        // service has no such restriction.
        bindService(new Intent(this, DriveLeaseService.class), leaseConnection, Context.BIND_AUTO_CREATE);
    }

    /** The robot's Claude API settings (settings plan U2), for the settings
     * page and the settings service. */
    ClaudeSettings claudeSettings() {
        return claudeSettings;
    }

    /** The people the robot remembers, for PeopleService and the Settings page. */
    PeopleStore people() {
        return people;
    }

    /** The robot's voice and its queue of lines, for SpeechService. */
    SpeechEngine speech() {
        return speech;
    }

    /** The robot's ears, for ListenService. */
    ListenEngine listen() {
        return listen;
    }

    /**
     * Creates and starts the RoutingHttpServer, registering every route.
     * Called once from onCreate() — the launcher's own dedicated port pair
     * (see class javadoc) means this server never needs to stop for another
     * app's sake, so it just runs for the lifetime of the process.
     */
    private void startServer() {
        server = new RoutingHttpServer(this, PORT);

        server.route("/", new RoutingHttpServer.RouteHandler() {
            @Override
            public void handle(HttpRequest req, HttpResponse res) throws IOException {
                String status = req.queryParam("status", null);
                boolean pending = "1".equals(req.queryParam("pending", null));
                List<ModeRegistry.Mode> running = ModeRegistry.activeModes(ModeRegistry.all(),
                        ModeRegistry.probeAll(ModeRegistry.all(), PRESENCE_TIMEOUT_MS));
                res.sendText(200, "OK", "text/html; charset=utf-8",
                        LauncherPage.buildIndexHtml(LauncherApp.this, wifi, status, pending, running));
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
                res.sendText(200, "OK", "text/plain; charset=utf-8",
                        batteryStr + "|" + DeviceInfo.uptime() + "|" + DeviceInfo.wifiIp(LauncherApp.this));
            }
        });
        server.route("/wifi/status", new RoutingHttpServer.RouteHandler() {
            @Override
            public void handle(HttpRequest req, HttpResponse res) throws IOException {
                res.sendText(200, "OK", "text/plain; charset=utf-8", wifi.connectionStatus());
            }
        });
        // The Settings page and its actions (settings plan U4). One handler for
        // all of them; SettingsPage dispatches on the path. Refresh and Test call
        // the endpoint synchronously, which is fine on the server's thread per
        // connection.
        RoutingHttpServer.RouteHandler settingsHandler = new RoutingHttpServer.RouteHandler() {
            @Override
            public void handle(HttpRequest req, HttpResponse res) throws IOException {
                SettingsPage.handle(req, res, settingsToken, claudeSettings, claudeApi, settingsSpeaker,
                        people);
            }
        };
        server.route(LauncherProtocol.SETTINGS_PATH, settingsHandler);
        server.route(LauncherProtocol.SETTINGS_CLAUDE_PATH, settingsHandler);
        server.route(LauncherProtocol.SETTINGS_CLAUDE_MODELS_PATH, settingsHandler);
        server.route(LauncherProtocol.SETTINGS_CLAUDE_TEST_PATH, settingsHandler);
        server.route(LauncherProtocol.SETTINGS_CLAUDE_FORGET_PATH, settingsHandler);
        server.route(LauncherProtocol.SETTINGS_VOICE_SAY_PATH, settingsHandler);
        server.route(LauncherProtocol.SETTINGS_PEOPLE_RENAME_PATH, settingsHandler);
        server.route(LauncherProtocol.SETTINGS_PEOPLE_FORGET_PATH, settingsHandler);
        server.route(LauncherProtocol.SETTINGS_PEOPLE_FACE_PATH, settingsHandler);
        server.route(LauncherProtocol.LAUNCH_MODE_PATH, new RoutingHttpServer.RouteHandler() {
            @Override
            public void handle(HttpRequest req, HttpResponse res) throws IOException {
                // ?mode=<registry id>; no parameter means remote-control, so links
                // and bookmarks from before the registry keep working.
                String modeParam = req.queryParam(LauncherProtocol.LAUNCH_MODE_PARAM, null);
                ModeRegistry.Mode target = ModeRegistry.resolveLaunchTarget(modeParam);
                if (target == null) {
                    Log.w(TAG, "launch-mode: unknown mode '" + modeParam + "', nothing launched");
                    res.redirect("/?status=" + urlEncode("Unknown mode: " + modeParam));
                    return;
                }
                launchModeGracefully(target);
                // Sends the browser to the mode's own page on its own port (U12:
                // separate port pairs, so unlike under U11's shared port, "/" here
                // would just reload the launcher's own home page instead). Uses the
                // request's own Host header rather than a hardcoded hostname, same
                // pattern RoutingHttpServer's HTTPS redirect already uses, since the
                // robot's WiFi IP isn't known at build time.
                String host = req.headers.get("host");
                if (host != null) {
                    int colon = host.indexOf(':');
                    if (colon >= 0) host = host.substring(0, colon);
                    res.redirect("http://" + host + ":" + target.httpPort + "/");
                } else {
                    res.redirect("/");
                }
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
    }

    /**
     * Launches the target mode (KTD8), handing off gracefully from whichever mode is running — never a process
     * kill (the plan's own cited learning: docs/solutions/runtime-errors/
     * kill-9-on-watched-service-permanently-disables-restart.md). Asks every
     * registered mode's presence route which one is active, requests that one
     * exit through its own normal release path (the same one its own "Exit
     * mode" control uses), waits for its presence to go inactive, then
     * launches the target — release-then-acquire, never both at once.
     *
     * Presence is the "has it exited" signal; for the remote-control mode it
     * clears after its drive release and its mic release. Polled every 150 ms,
     * bounded at 5 s, then a 400 ms grace. The drive lease still arbitrates the
     * motors. synchronized: two overlapping /launch-mode requests would
     * otherwise interleave their exit and launch steps.
     */
    private synchronized void launchModeGracefully(ModeRegistry.Mode target) {
        List<ModeRegistry.Mode> all = ModeRegistry.all();
        List<ModeRegistry.Mode> running = ModeRegistry.activeModes(all,
                ModeRegistry.probeAll(all, PRESENCE_TIMEOUT_MS));

        if (!running.isEmpty()) {
            for (ModeRegistry.Mode outgoing : running) {
                Log.i(TAG, "mode switch: requesting '" + outgoing.id + "' to exit before launching '"
                        + target.id + "'");
                Intent exitRequest = new Intent();
                exitRequest.setClassName(outgoing.packageName, outgoing.activityClassName);
                exitRequest.putExtra(LauncherProtocol.EXTRA_FORCE_EXIT, true);
                exitRequest.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                try {
                    startActivity(exitRequest);
                } catch (Exception e) {
                    Log.w(TAG, "could not deliver exit request to '" + outgoing.id + "'", e);
                }
            }

            // A probe that stops answering (the process died mid-exit) counts as
            // exited: activeModes() only keeps modes that say ACTIVE.
            long deadline = SystemClock.elapsedRealtime() + 5000;
            boolean exited = false;
            while (SystemClock.elapsedRealtime() < deadline) {
                Map<String, ModeRegistry.Presence> now = ModeRegistry.probeAll(running, PRESENCE_TIMEOUT_MS);
                if (ModeRegistry.activeModes(running, now).isEmpty()) {
                    exited = true;
                    break;
                }
                try {
                    Thread.sleep(150);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (!exited) {
                Log.w(TAG, "outgoing mode still reports active after 5s — launching '" + target.id + "' anyway");
            }
            // Presence clearing (above) confirms the outgoing mode's release ran,
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

        // No server stop/handoff needed here (U12 reverted U11's shared-port design:
        // each app now has its own dedicated port pair — see ModeApp.PORT/HTTPS_PORT
        // comment for why). The launcher's own server keeps running the whole time a
        // mode is active, so "Robot Home" is reachable at every point in this flow,
        // not just once the launcher's Activity happens to be back in the foreground.
        Intent launch = new Intent();
        launch.setClassName(target.packageName, target.activityClassName);
        launch.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(launch);

        // startActivity() returns long before the mode's own process has actually
        // started and bound its port — the /launch-mode route redirects the
        // browser there right after this method returns, so without this wait the
        // browser's very first request would hit a nothing's-listening-yet refusal
        // and (depending on the browser) silently fail, looking like "nothing
        // happened" when the link was clicked.
        waitForModePortReady(target.httpPort);
    }

    private static void waitForModePortReady(int port) {
        long deadline = SystemClock.elapsedRealtime() + 5000;
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                java.net.Socket probe = new java.net.Socket();
                try {
                    probe.connect(new java.net.InetSocketAddress("127.0.0.1", port), 200);
                    return; // connected — the mode's listener is up
                } finally {
                    probe.close();
                }
            } catch (IOException e) {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        Log.w(TAG, "mode's HTTP port " + port + " never came up within 5s — redirecting the browser there anyway");
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    /** ClaudeSettings.Store over the launcher's SharedPreferences. One commit()
     * per call, not apply(), so a save is on disk before the POST's redirect is
     * sent, and survives a reboot or reinstall right after (R15). */
    private static final class PrefsStore implements ClaudeSettings.Store {
        private final SharedPreferences prefs;

        PrefsStore(SharedPreferences prefs) {
            this.prefs = prefs;
        }

        @Override
        public String getString(String key, String def) {
            try {
                return prefs.getString(key, def);
            } catch (ClassCastException e) {
                // A hand-written non-string value (the file is reachable over root adb).
                Log.w(TAG, "preference " + key + " is not a string; using the default");
                return def;
            }
        }

        @Override
        public void putStrings(Map<String, String> entries) {
            SharedPreferences.Editor editor = prefs.edit();
            for (Map.Entry<String, String> e : entries.entrySet()) {
                editor.putString(e.getKey(), e.getValue());
            }
            if (!editor.commit()) {
                Log.w(TAG, "Claude settings commit failed");
            }
        }
    }
}
