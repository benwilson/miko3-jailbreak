package com.miko3.mode.voice;

import android.app.Application;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.util.Log;

import com.miko3.shared.HttpRequest;
import com.miko3.shared.HttpResponse;
import com.miko3.shared.HttpsSupport;
import com.miko3.shared.HttpUtil;
import com.miko3.shared.LauncherProtocol;
import com.miko3.shared.ModeRegistry;
import com.miko3.shared.RoutingHttpServer;

import java.io.IOException;

/**
 * Starts the voice mode's HTTP server once per process, independent of
 * Activity lifecycle — the same shape as mode-remote-control's ModeApp.
 * Serves the settings page (KTD10), the presence route the launcher probes
 * (KTD8), and the page-token-gated exit route.
 *
 * Routes (8082 HTTP, forwarding to 8445 HTTPS once that is up):
 *   GET  /                   settings form (SettingsPage; mints the page token)
 *   POST /                   save settings (page token required)
 *   POST /exit               exit the mode (page token required), then the browser
 *                            is redirected to the launcher's page
 *   GET  /presence           {"mode":"voice","active":true|false}
 *   GET  /device-view        on-device WebView page: the shared eyes, restyled per
 *                            conversation state (VoiceState.DEVICE_VIEW_HTML)
 *   GET  /voice-state        the current VoiceState's wire name as plain text, no
 *                            newline (e.g. "speaking"); polled by /device-view
 *   GET  /assets/pico.min.css
 *
 * Also owns the running voice engine and relay client (U8) between
 * startVoice() and stopVoice(), which MainActivity calls under its
 * generation guard; they persist across Activity recreation but not across
 * an exit.
 *
 * Never touches the camera, the drive lease, AIDL, or any motor class (R18).
 */
public class ModeApp extends Application {
    private static final String TAG = "VoiceModeApp";
    // The launcher owns 8080/8443 and the remote-control mode 8081/8444; this is
    // the next pair (KTD10). No shared constant between the apps' build units —
    // keep these literals in sync with the launcher's mode registry (U9).
    static final int PORT = 8082;
    static final int HTTPS_PORT = 8445;

    private RoutingHttpServer server;
    private VoiceSettings settings;
    private final PageToken pageToken = new PageToken();
    private volatile Runnable exitRunnable;
    // What the eyes show (U7, KTD9). U8's engine sets it; /voice-state reads it
    // without taking any lock. stateText is optional detail the engine adds to
    // the settings page's state line after the state's own label.
    private final VoiceState.Holder voiceState = new VoiceState.Holder();
    private volatile String stateText;
    private volatile byte[] cssBytes;

    // The conversation loop (U8), non-null between startVoice() and stopVoice().
    // Each side reports its own detail; the settings page shows both.
    private final Object voiceLock = new Object();
    private VoiceEngine engine;
    private ConversationClient client;
    private volatile String clientDetail;
    private volatile String engineDetail;

    // Same stale-instance guard as mode-remote-control's ModeApp: with
    // launchMode="singleTop", an old MainActivity's teardown can run after a newer
    // instance has activated. Each instance captures the generation activate()
    // returns and deactivate() only acts when that is still the current one.
    //
    // Presence is a separate flag, not derived from the counter: the counter only
    // ever increases, so it can't say "nothing is active now". Set by activate(),
    // cleared by the current generation's deactivate(). The launcher reads it via
    // /presence because a cleanly exited mode's process (and so its port) stays up.
    private long generation = 0;
    private volatile boolean active;

    @Override
    public void onCreate() {
        super.onCreate();
        settings = new VoiceSettings(new PrefsStore(getSharedPreferences(VoiceSettings.PREFS_NAME, MODE_PRIVATE)));
        if (settings.relayAddress().isEmpty()) {
            Log.w(TAG, "no relay address set — open the settings page on port " + PORT + " to set one");
        } else {
            Log.i(TAG, "relay address: " + settings.relayAddress());
        }
        startServer();
    }

    /**
     * Creates and starts the RoutingHttpServer, registering every route.
     * Called once from onCreate(); runs for the lifetime of the process (this
     * app's own port pair means it never needs to stop for another app).
     */
    private void startServer() {
        server = new RoutingHttpServer(this, PORT);
        server.route("/", new RoutingHttpServer.RouteHandler() {
            @Override
            public void handle(HttpRequest req, HttpResponse res) throws IOException {
                SettingsPage.handleRoot(req, res, pageToken, settings, voiceState.settingsLine(stateText));
            }
        });
        server.route("/exit", new RoutingHttpServer.RouteHandler() {
            @Override
            public void handle(HttpRequest req, HttpResponse res) throws IOException {
                if (!SettingsPage.handleExit(req, res, pageToken)) {
                    Log.w(TAG, "/exit refused: not a POST carrying the issued page token");
                    return;
                }
                Runnable r = exitRunnable;
                if (r != null) {
                    r.run();
                }
            }
        });
        server.route(LauncherProtocol.PRESENCE_PATH, new RoutingHttpServer.RouteHandler() {
            @Override
            public void handle(HttpRequest req, HttpResponse res) throws IOException {
                res.sendText(200, "OK", "application/json; charset=utf-8",
                        ModeRegistry.presenceJson(LauncherProtocol.MODE_VOICE, active));
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
        // The robot's own screen (MainActivity's WebView) and any LAN browser.
        // Deliberately not the settings page: rendering that mints a new page token,
        // which would invalidate the token in a LAN browser's copy of the form.
        server.route("/device-view", new RoutingHttpServer.RouteHandler() {
            @Override
            public void handle(HttpRequest req, HttpResponse res) throws IOException {
                res.sendText(200, "OK", "text/html; charset=utf-8", VoiceState.DEVICE_VIEW_HTML);
            }
        });
        // Polled by /device-view at 1 s or 250 ms (KTD9). One volatile read, so it
        // answers promptly however busy the engine's threads are.
        server.route("/voice-state", new RoutingHttpServer.RouteHandler() {
            @Override
            public void handle(HttpRequest req, HttpResponse res) throws IOException {
                res.sendText(200, "OK", "text/plain; charset=utf-8", voiceState.text());
            }
        });

        Thread t = new Thread(server, "voice-http-server");
        t.setDaemon(true);
        t.start();

        // See mode-remote-control/ModeApp's identical block: once the HTTPS listener
        // is bound, the plain one only redirects to it.
        javax.net.ssl.SSLContext httpsContext = HttpsSupport.loadServerContext(this);
        if (httpsContext != null) {
            server.startHttps(HTTPS_PORT, httpsContext);
        } else {
            Log.w(TAG, "HTTPS certificate failed to load — serving plain HTTP only");
        }
    }

    /** Sets what the eyes and the settings page's state line show. Called by the
     * relay client on every state change; any thread, never blocks. */
    void setVoiceState(VoiceState state) {
        voiceState.set(state);
    }

    /** Optional detail shown after the state's label on the settings page (e.g.
     * the last relay error); null or "" for none. Does not change the eyes. */
    void setStateText(String text) {
        stateText = text;
    }

    /**
     * Starts the microphone, the spotter and the relay link if they are not
     * already running. Called by the active MainActivity; idempotent, so a
     * newer instance activating over an older one keeps the running engine.
     */
    void startVoice() {
        synchronized (voiceLock) {
            if (engine != null) {
                return;
            }
            clientDetail = null;
            engineDetail = null;
            VoiceEngine e = new VoiceEngine(this, settings, new VoiceEngine.DetailListener() {
                @Override
                public void onEngineDetail(String detail) {
                    engineDetail = detail;
                    updateStateText();
                }
            });
            ConversationClient.Config cfg = new ConversationClient.Config();
            cfg.robotId = robotId();
            cfg.appVersion = appVersion();
            ConversationClient c = new ConversationClient(settings, e, new ConversationClient.StateListener() {
                @Override
                public void onState(VoiceState state, String detail) {
                    setVoiceState(state);
                    clientDetail = detail;
                    updateStateText();
                }
            }, new ClientLog(), cfg);
            e.setClient(c);
            engine = e;
            client = c;
            e.start();
            c.start();
            Log.i(TAG, "voice engine started (robot id " + cfg.robotId + ")");
        }
    }

    /**
     * Stops everything startVoice() started, synchronously: the speaker is
     * silent first, then an open conversation is closed (robot_request, best
     * effort) with the link, then the microphone is released. Only the current
     * generation's exit calls this (MainActivity), so a stale instance can't
     * stop a newer one's engine.
     */
    void stopVoice() {
        synchronized (voiceLock) {
            if (engine == null) {
                return;
            }
            engine.closePlayer();
            client.stop();
            engine.stop();
            engine = null;
            client = null;
            Log.i(TAG, "voice engine stopped");
        }
    }

    private void updateStateText() {
        String c = clientDetail;
        String e = engineDetail;
        if (c != null && e != null) {
            setStateText(c + "; " + e);
        } else {
            setStateText(c != null ? c : e);
        }
    }

    /** Stable per device, sent in hello; the relay keys its link on it. */
    private String robotId() {
        String id = null;
        try {
            id = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        } catch (RuntimeException e) {
            Log.w(TAG, "no ANDROID_ID: " + e);
        }
        return id == null || id.isEmpty() ? "miko3" : "miko3-" + id;
    }

    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "unknown";
        }
    }

    /** ConversationClient's log, to logcat under VoiceClient. */
    private static final class ClientLog implements ConversationClient.Logger {
        private static final String CLIENT_TAG = "VoiceClient";

        @Override
        public void info(String msg) {
            Log.i(CLIENT_TAG, msg);
        }

        @Override
        public void warn(String msg) {
            Log.w(CLIENT_TAG, msg);
        }

        @Override
        public void error(String msg, Throwable t) {
            Log.e(CLIENT_TAG, msg, t);
        }
    }

    /** Called when a MainActivity instance (re)establishes itself as the active
     * one: bumps and returns the new generation and marks the mode present. */
    synchronized long activate() {
        active = true;
        return ++generation;
    }

    /** Whether gen is still the current generation (no newer instance has
     * activated since). */
    synchronized boolean isCurrent(long gen) {
        return gen == generation;
    }

    /**
     * Called from an instance's exit and teardown paths. Clears presence and
     * the exit hook only when gen is still the current generation, and returns
     * whether it was — the caller tears down shared state only then, so a
     * stale instance can't undo a newer instance's activation.
     */
    synchronized boolean deactivate(long gen) {
        if (gen != generation) {
            return false;
        }
        active = false;
        exitRunnable = null;
        return true;
    }

    void setExitRunnable(Runnable runnable) {
        exitRunnable = runnable;
    }

    /** VoiceSettings.Store over this app's SharedPreferences. commit(), not apply(),
     * so a saved value is on disk before the POST's redirect is sent. */
    private static final class PrefsStore implements VoiceSettings.Store {
        private final SharedPreferences prefs;

        PrefsStore(SharedPreferences prefs) {
            this.prefs = prefs;
        }

        @Override
        public String getString(String key, String def) {
            return prefs.getString(key, def);
        }

        @Override
        public boolean getBoolean(String key, boolean def) {
            return prefs.getBoolean(key, def);
        }

        @Override
        public int getInt(String key, int def) {
            try {
                return prefs.getInt(key, def);
            } catch (ClassCastException e) {
                // A hand-written <string> instead of <int> (U10 writes these over adb).
                Log.w(TAG, "preference " + key + " is not an int; using " + def);
                return def;
            }
        }

        @Override
        public void putString(String key, String value) {
            prefs.edit().putString(key, value).commit();
        }

        @Override
        public void putBoolean(String key, boolean value) {
            prefs.edit().putBoolean(key, value).commit();
        }
    }
}
