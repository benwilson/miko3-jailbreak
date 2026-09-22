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

    /** Eyes held on the direction he is about to move (lookX/lookY), set before a turn (R7). */
    static final String LOOK = "look";
    /** The startle: a quick squint as he stops at an edge or obstacle (R11). */
    static final String FLINCH = "flinch";
    /** No usable sensor readings, so he will not drive: visibly "not moving", not frozen (R10). */
    static final String EYES_ONLY = "eyes-only";
    /** The cornered cool-down (KTD8): drowsy, distinct from the no-sensors look. */
    static final String RESTING = "resting";

    /** Every state the brain publishes, for the page tests. */
    static final String[] ALL_STATES = {IDLE, LOOK, FLINCH, EYES_ONLY, RESTING};

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
    // idle is the other modes' look unchanged (R6). The animations go on
    // .glow-core, never .glow: .glow's transform carries the gaze, and a keyframe
    // transform on the same element would override it.
    private static final String STATE_CSS =
            // Look: a slightly brighter core while he eyes the way ahead.
            "#rig.s-look .glow-core{filter:brightness(1.15)}"
            // Flinch: one quick squint that springs back.
            + "@keyframes flinch{0%{transform:scale(1,1)}25%{transform:scale(1.18,.3)}"
            + "60%{transform:scale(.95,1.08)}100%{transform:scale(1,1)}}"
            + "#rig.s-flinch .glow-core{animation:flinch .55s cubic-bezier(.3,1.4,.5,1) 1}"
            // Eyes-only (no sensors, not driving): half-closed and dimmed. The
            // glances continue (see GAZE_JS), so it reads as awake but staying put.
            + "#rig.s-eyes-only .glow-core{transform:scale(1,.45);opacity:.7;transition:transform .6s,opacity .6s}"
            // Resting (cornered cool-down): drowsy, slowly breathing lids.
            + "@keyframes drowse{from{transform:scale(1,.6);opacity:.85}to{transform:scale(1,.3);opacity:.55}}"
            + "#rig.s-resting .glow-core{animation:drowse 2.4s ease-in-out infinite alternate}";

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

    // Runs after the eyes define gazeTo: every glance goes through it.
    // - look: every glance goes to the published direction, so the eyes stay on
    //   where he is about to move (R7). lookX/lookY in [-1, 1] map to the glance's
    //   own range (x about +-10vmin, y about +-9vmin).
    // - flinch: eyes snap to centre.
    // - eyes-only / resting: glances are damped, not stopped, so the eyes never
    //   look frozen (R10).
    // showExploreState is wrapped too, so a look moves the eyes the moment it
    // arrives rather than at the next glance, which can be seconds away; the
    // brain only waits ~500ms before turning (KTD10).
    private static final String GAZE_JS =
            "var eyesGazeTo=gazeTo;"
            + "gazeTo=function(x,y,speedMs){"
            + "if(exploreState.state==='look'){x=exploreState.lookX*10;y=exploreState.lookY*9;}"
            + "else if(exploreState.state==='flinch'){x=0;y=0;}"
            + "else if(exploreState.state==='eyes-only'||exploreState.state==='resting'){x*=0.35;y*=0.35;}"
            + "eyesGazeTo(x,y,speedMs);"
            + "};"
            + "var showExploreStateBase=showExploreState;"
            + "showExploreState=function(s){"
            + "var was=exploreState;"
            + "showExploreStateBase(s);"
            + "if(s.state==='look'&&(was.state!=='look'||was.lookX!==s.lookX||was.lookY!==s.lookY))gazeTo(0,0,260);"
            + "else if(s.state==='flinch'&&was.state!=='flinch')gazeTo(0,0,90);"
            + "};";

    /** GET /device-view (and /): the shared eyes plus the state hook. */
    static final String DEVICE_VIEW_HTML = EyesPage.build(
            "Explore Mode", STATE_CSS, "", POLL_JS, GAZE_JS);
}
