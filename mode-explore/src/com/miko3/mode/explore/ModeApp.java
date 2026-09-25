package com.miko3.mode.explore;

import android.app.Application;
import android.util.Log;

import com.miko3.shared.HttpRequest;
import com.miko3.shared.HttpResponse;
import com.miko3.shared.HttpsSupport;
import com.miko3.shared.LauncherProtocol;
import com.miko3.shared.ModeRegistry;
import com.miko3.shared.RoutingHttpServer;

import java.io.File;
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
    private ExploreDrive drive;
    private ExploreLoop loop;
    private ClipPlayer clips;
    private ExploreCamera camera;
    // Claude, the launcher's voice, ears and people store at curiosity stops (explore on Claude U6).
    private ClaudeCuriosity curiosity;

    /** How often the brain loop runs; well under a hop tick (ExploreBrain.onTick). */
    private static final long BRAIN_TICK_MS = 20;
    /** The stop timer's window (KTD6): no brain tick for this long stops the wheels. */
    private static final long STOP_TIMER_MS = 600;

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
     * Connects the driver (whose keepalive replies carry the readings), asks for
     * the drive lease, and starts the brain loop. With no calibration file the
     * brain stays in eyes-only (KTD9): the same loop runs, it just never drives.
     */
    void startExplore() {
        synchronized (exploreLock) {
            if (exploring) {
                return;
            }
            exploring = true;
            setExploreState(ExploreState.of(ExploreState.EYES_ONLY));
            ExploreTuning.Calibration calibration =
                    ExploreCalibration.read(new File(getFilesDir(), ExploreCalibration.FILE_NAME));
            Log.i(TAG, calibration == null
                    ? "no sensor calibration -- eyes only until scripts/qa-explore-mode.py calibrates"
                    : "sensor calibration: " + calibration);
            final ExploreTuning tuning = ExploreTuning.defaults(calibration);
            clips = new ClipPlayer(this);
            // Opened only during curiosity stops (camera curiosity KTD3); the
            // recognizer loads on the camera's detect thread on first use.
            camera = new ExploreCamera(this, ExploreDrive.CLOCK, new ExploreCamera.RecognizerFactory() {
                @Override
                public Recognizer create() throws Exception {
                    return new OnnxRecognizer(ModeApp.this, tuning.unsureFloor);
                }
            });
            drive = new ExploreDrive(this);
            drive.start();
            // Fetches the Claude settings now and again every stop; with none set up, stops run as before.
            curiosity = new ClaudeCuriosity(this);
            loop = new ExploreLoop(tuning, ExploreDrive.CLOCK, drive, drive, drive, eyes, sound, camera, curiosity,
                    drive, trace, BRAIN_TICK_MS, STOP_TIMER_MS);
            loop.start();
            Log.i(TAG, "explore started");
        }
    }

    /**
     * Stops everything startExplore() started, synchronously, leaving the robot
     * stopped (R5). Only the current generation's exit calls this
     * (MainActivity), so a stale instance can't stop a newer one's wander.
     *
     * Ends the brain thread and waits for it (which leaves the wheels stopped
     * and the camera closed), releases the camera and recognizer, then the
     * lease and the driver (KTD7), all before returning.
     */
    void stopExplore() {
        synchronized (exploreLock) {
            if (!exploring) {
                return;
            }
            exploring = false;
            loop.stop();
            curiosity.release();
            camera.release();
            drive.release();
            clips.release();
            loop = null;
            curiosity = null;
            camera = null;
            drive = null;
            clips = null;
            setExploreState(ExploreState.IDLE_STATE);
            Log.i(TAG, "explore stopped");
        }
    }

    // What the brain drives on the page (U6): its eye states mapped to the page's.
    // LEFT is the robot's own left, which a viewer facing the screen sees as the
    // eyes moving to their right, so it maps to +x.
    private final ExploreBrain.Eyes eyes = new ExploreBrain.Eyes() {
        @Override
        public void show(ExploreBrain.EyeState state, ExploreBrain.Direction gaze) {
            // He sings while parked: resting after being cornered, or eyes-only.
            ClipPlayer c = clips;
            if (c != null) {
                if (state == ExploreBrain.EyeState.RESTING || state == ExploreBrain.EyeState.EYES_ONLY) {
                    c.startSinging();
                } else {
                    c.stopSinging();
                }
            }
            switch (state) {
                case LOOK:
                    setExploreState(ExploreState.look(ExploreState.LOOK,
                            gaze == ExploreBrain.Direction.LEFT ? 1 : gaze == ExploreBrain.Direction.RIGHT ? -1 : 0, 0));
                    break;
                case FLINCH:
                    setExploreState(ExploreState.of(ExploreState.FLINCH));
                    break;
                case RESTING:
                    setExploreState(ExploreState.of(ExploreState.RESTING));
                    break;
                case STARE:
                    break; // set by stare(), with where to look
                case EYES_ONLY:
                    setExploreState(ExploreState.of(ExploreState.EYES_ONLY));
                    break;
                case THINKING:
                    setExploreState(ExploreState.of(ExploreState.THINKING));
                    break;
                default:
                    setExploreState(ExploreState.IDLE_STATE);
                    break;
            }
        }

        // The box's x is negative toward the robot's left, which the page shows as +x (see above).
        @Override
        public void stare(float x, float y) {
            ClipPlayer c = clips;
            if (c != null) {
                c.stopSinging();
            }
            setExploreState(ExploreState.look(ExploreState.LOOK, -x, y));
        }
    };

    private final ExploreBrain.Sound sound = new ExploreBrain.Sound() {
        @Override
        public void playStartle() {
            ClipPlayer c = clips;
            if (c != null) {
                c.playStartle();
            }
        }

        @Override
        public void playReaction(String group) {
            ClipPlayer c = clips;
            if (c != null) {
                c.playReaction(group);
            }
        }

        @Override
        public void playName(String label) {
            ClipPlayer c = clips;
            if (c != null) {
                c.playName(label);
            }
        }
    };

    private final ExploreBrain.Trace trace = new ExploreBrain.Trace() {
        @Override
        public void note(String message) {
            Log.i("ExploreBrain", message);
        }
    };

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
