package com.miko3.mode.explore;

import com.miko3.shared.EyesPage;

/**
 * What the explore mode's eyes show (KTD10), plus the device-view page that
 * shows it. The brain publishes a state on ModeApp's Holder; ModeApp serves
 * it as JSON at /state, and the page below polls that route and sets a
 * "s-NAME" class on the shared eyes' #rig.
 *
 * A state is a name plus a look direction: lookX/lookY in [-1, 1] (0,0 is
 * straight ahead), which the brain sets before a turn so the eyes lead it
 * (R7). Immutable, so publishing one is a single volatile write and the
 * /state route never takes a lock the brain holds.
 *
 * U4 scaffold: only IDLE is published, and the page applies the class
 * without per-state rules. U6 adds the per-state CSS (STATE_CSS) and the
 * gaze wrap (GAZE_JS) that holds the eyes on the look direction; the brain
 * (U3/U5) adds its own state names through {@link #of}/{@link #look}.
 *
 * Polling rather than pushing into the WebView so the same page works in a
 * LAN browser, as the voice mode's does. No android.* here: host-JVM tests
 * compile this class directly.
 */
final class ExploreState {
    /** Idle glancing, the other modes' look (R6). The state before the brain first reports. */
    static final String IDLE = "idle";

    static final ExploreState IDLE_STATE = new ExploreState(IDLE, 0, 0);

    /** Page poll interval while the mode is up (KTD10: about 150 ms, so a "look"
     * lands within one poll of the brain's ~500 ms lead before a turn). */
    static final int POLL_MS = 150;

    /** The page's "s-" class suffix and /state's "state" field: lowercase letters,
     * digits and dashes only, so it drops into CSS and JSON without escaping. */
    final String name;
    final double lookX;
    final double lookY;

    private ExploreState(String name, double lookX, double lookY) {
        this.name = name;
        this.lookX = lookX;
        this.lookY = lookY;
    }

    /** A state with the eyes' own glancing (no held look direction). */
    static ExploreState of(String name) {
        return new ExploreState(checkName(name), 0, 0);
    }

    /** A state holding the eyes on (x, y), each clamped to [-1, 1]; NaN reads as 0. */
    static ExploreState look(String name, double x, double y) {
        return new ExploreState(checkName(name), clamp(x), clamp(y));
    }

    /** /state's body, e.g. {"state":"idle","lookX":0.0,"lookY":0.0}. */
    String toJson() {
        return "{\"state\":\"" + name + "\",\"lookX\":" + lookX + ",\"lookY\":" + lookY + "}";
    }

    private static String checkName(String name) {
        if (name == null || !name.matches("[a-z0-9-]+")) {
            throw new IllegalArgumentException("state name must match [a-z0-9-]+: " + name);
        }
        return name;
    }

    private static double clamp(double v) {
        if (Double.isNaN(v)) {
            return 0;
        }
        return Math.max(-1, Math.min(1, v));
    }

    /**
     * The current state, shared between the brain thread that sets it and the
     * HTTP threads that read it. A single volatile reference: reads never block.
     */
    static final class Holder {
        private volatile ExploreState current = IDLE_STATE;

        void set(ExploreState state) {
            if (state == null) {
                throw new NullPointerException("state");
            }
            current = state;
        }

        ExploreState get() {
            return current;
        }

        /** /state's body. */
        String json() {
            return current.toJson();
        }
    }

    // Per-state looks, as overrides on the shared eyes keyed by a class on #rig.
    // Empty in U4: idle is the other modes' look unchanged. U6 fills this in.
    private static final String STATE_CSS = "";

    // The poll. setTimeout chained off each answer (not setInterval) and
    // XMLHttpRequest with a timeout, as in the voice mode, so a hung request
    // still ends in loadend and the poll carries on. Anything but a 200 with a
    // parseable state leaves the eyes as they are. exploreState holds the last
    // answer ({state, lookX, lookY}) for the after-eyes script to read.
    private static final String POLL_JS =
            "var rig=document.getElementById('rig');"
            + "var exploreState={state:'',lookX:0,lookY:0};"
            + "function showExploreState(s){"
            + "if(s.state!==exploreState.state)rig.className='s-'+s.state;"
            + "exploreState=s;"
            + "}"
            + "function pollExploreState(){"
            + "var x=new XMLHttpRequest();"
            + "x.open('GET','/state',true);"
            + "x.timeout=1000;"
            + "x.onloadend=function(){"
            + "if(x.status===200){try{var s=JSON.parse(x.responseText);"
            + "if(s&&typeof s.state==='string')showExploreState(s);}catch(e){}}"
            + "setTimeout(pollExploreState," + POLL_MS + ");"
            + "};"
            + "x.send();"
            + "}"
            + "showExploreState({state:'" + IDLE + "',lookX:0,lookY:0});"
            + "pollExploreState();";

    // Runs after the eyes define gazeTo. Empty in U4; U6 wraps gazeTo here to
    // hold the eyes on exploreState.lookX/lookY during a look (R7).
    private static final String GAZE_JS = "";

    /** GET /device-view (and /): the shared eyes plus the state hook. */
    static final String DEVICE_VIEW_HTML = EyesPage.build(
            "Explore Mode", STATE_CSS, "", POLL_JS, GAZE_JS);
}
