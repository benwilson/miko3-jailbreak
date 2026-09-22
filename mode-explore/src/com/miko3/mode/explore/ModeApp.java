package com.miko3.mode.explore;

import android.app.Application;
import android.util.Log;

import com.miko3.shared.HttpRequest;
import com.miko3.shared.HttpResponse;
import com.miko3.shared.HttpsSupport;
import com.miko3.shared.LauncherProtocol;
import com.miko3.shared.ModeRegistry;
import com.miko3.shared.RoutingHttpServer;

import java.io.IOException;

/**
 * Starts the explore mode's HTTP server once per process, independent of
 * Activity lifecycle — the same shape as the voice and remote-control modes'
 * ModeApp. Serves the eyes page, the state route it polls (KTD10), and the
 * presence route the launcher probes (KTD8). There is no settings page and
 * no exit route: the mode is exited through the launcher (R5).
 *
 * Routes (8083 HTTP, forwarding to 8446 HTTPS once that is up):
 *   GET  /                   the eyes page (same as /device-view, for a LAN browser)
 *   GET  /device-view        on-device WebView page: the shared eyes, restyled per
 *                            explore state (ExploreState.DEVICE_VIEW_HTML)
 *   GET  /state              the current ExploreState as JSON; polled by the page
 *   GET  /presence           {"mode":"explore","active":true|false}
 *
 * Also owns the wander between startExplore() and stopExplore(), which
 * MainActivity calls under its generation guard; it persists across Activity
 * recreation but not across an exit.
 */
public class ModeApp extends Application {
    private static final String TAG = "ExploreModeApp";
    // The launcher owns 8080/8443, remote-control 8081/8444 and voice 8082/8445;
    // this is the next pair (KTD12). Keep these literals in sync with the
    // launcher's mode registry (ModeRegistry.EXPLORE).
    static final int PORT = 8083;
    static final int HTTPS_PORT = 8446;

    private RoutingHttpServer server;

    // What the eyes show (KTD10). The brain sets it; /state reads it without
    // taking any lock.
    private final ExploreState.Holder exploreState = new ExploreState.Holder();

    // The wander (U5), running between startExplore() and stopExplore().
    private final Object exploreLock = new Object();
    private boolean exploring;

    // Same stale-instance guard as the voice mode's ModeApp: with
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
        startServer();
    }

    /**
     * Creates and starts the RoutingHttpServer, registering every route.
     * Called once from onCreate(); runs for the lifetime of the process (this
     * app's own port pair means it never needs to stop for another app).
     */
    private void startServer() {
        server = new RoutingHttpServer(this, PORT);
        RoutingHttpServer.RouteHandler eyes = new RoutingHttpServer.RouteHandler() {
            @Override
            public void handle(HttpRequest req, HttpResponse res) throws IOException {
                res.sendText(200, "OK", "text/html; charset=utf-8", ExploreState.DEVICE_VIEW_HTML);
            }
        };
        server.route("/", eyes);
        server.route("/device-view", eyes);
        // Polled by the eyes page every ExploreState.POLL_MS. One volatile read, so
        // it answers promptly however busy the brain's thread is.
        server.route("/state", new RoutingHttpServer.RouteHandler() {
            @Override
            public void handle(HttpRequest req, HttpResponse res) throws IOException {
                res.sendText(200, "OK", "application/json; charset=utf-8", exploreState.json());
            }
        });
        server.route(LauncherProtocol.PRESENCE_PATH, new RoutingHttpServer.RouteHandler() {
            @Override
            public void handle(HttpRequest req, HttpResponse res) throws IOException {
                res.sendText(200, "OK", "application/json; charset=utf-8",
                        ModeRegistry.presenceJson(LauncherProtocol.MODE_EXPLORE, active));
            }
        });

        Thread t = new Thread(server, "explore-http-server");
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

    /** Sets what the eyes show. Any thread, never blocks. */
    void setExploreState(ExploreState state) {
        exploreState.set(state);
    }

    /**
     * Starts the wander if it is not already running. Called by the active
     * MainActivity; idempotent, so a newer instance activating over an older
     * one keeps the running wander.
     *
     * U4 scaffold: eyes only. U5 starts the drive adapter (lease holder, stop
     * timer) and the brain thread here.
     */
    void startExplore() {
        synchronized (exploreLock) {
            if (exploring) {
                return;
            }
            exploring = true;
            setExploreState(ExploreState.IDLE_STATE);
            Log.i(TAG, "explore started (eyes only)");
        }
    }

    /**
     * Stops everything startExplore() started, synchronously, leaving the robot
     * stopped (R5). Only the current generation's exit calls this
     * (MainActivity), so a stale instance can't stop a newer one's wander.
     *
     * U5 joins the brain thread here, then sends stop() and releases the lease
     * (KTD7), all before this returns.
     */
    void stopExplore() {
        synchronized (exploreLock) {
            if (!exploring) {
                return;
            }
            exploring = false;
            setExploreState(ExploreState.IDLE_STATE);
            Log.i(TAG, "explore stopped");
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
     * Called from an instance's exit and teardown paths. Clears presence only
     * when gen is still the current generation, and returns whether it was —
     * the caller tears down shared state only then, so a stale instance can't
     * undo a newer instance's activation.
     */
    synchronized boolean deactivate(long gen) {
        if (gen != generation) {
            return false;
        }
        active = false;
        return true;
    }
}
