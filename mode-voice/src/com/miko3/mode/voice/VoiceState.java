package com.miko3.mode.voice;

import com.miko3.shared.EyesPage;

/**
 * The voice mode's conversation state as the eyes show it (U7, KTD9), plus the
 * device-view page that shows it. The voice engine (U8) computes the state and
 * sets it on ModeApp's Holder; ModeApp serves it as plain text at /voice-state,
 * and the page below polls that route and restyles the shared eyes to match.
 *
 * Polling rather than pushing into the WebView because the same page must work
 * in a LAN browser (U10's QA), where no in-process bridge exists. The poll runs
 * at 1 s in the two resting states (listening, unreachable) and at 250 ms while
 * a conversation is open, so the speaking cue lands within a quarter second
 * without paying the fast rate while idle on this SoC.
 *
 * Wire names are the KTD9 names and are what /voice-state returns verbatim.
 * No android.* here: the host-JVM tests compile this class directly.
 */
enum VoiceState {
    /** Waiting for "Hey Miko"; the idle eyes, exactly as the remote-control mode shows them. */
    LISTENING("listening", "Listening for \"Hey Miko\"", true),
    /** Wake word heard, opening the conversation: eyes widen and look straight ahead. */
    CONNECTING("connecting", "Wake word heard, connecting to the relay", false),
    /** Conversation open, the person has the floor: warmer glow, steady gaze. */
    CONVERSING("conversing", "In a conversation", false),
    /** The robot is talking (U8 derives this from the playback head advancing): pulsing glow. */
    SPEAKING("speaking", "Speaking", false),
    /** Conversation ending ("Goodbye Miko" or silence), last audio draining: slow pulse. */
    CLOSING("closing", "Ending the conversation", false),
    /** Relay unreachable, retrying in the background: dim, cold, slow blink. */
    UNREACHABLE("unreachable", "Relay unreachable, retrying", true);

    private final String wireName;
    private final String label;
    private final boolean slowPoll;

    VoiceState(String wireName, String label, boolean slowPoll) {
        this.wireName = wireName;
        this.label = label;
        this.slowPoll = slowPoll;
    }

    /** The KTD9 name: /voice-state's body and the page's "s-" class suffix. */
    String wireName() {
        return wireName;
    }

    /** Human wording for the settings page's state line. */
    String label() {
        return label;
    }

    /** Whether the page polls at 1 s (true) or 250 ms (false) while in this state. */
    boolean slowPoll() {
        return slowPoll;
    }

    /** The state with this exact wire name, or null. */
    static VoiceState fromWireName(String name) {
        for (VoiceState s : values()) {
            if (s.wireName.equals(name)) {
                return s;
            }
        }
        return null;
    }

    /**
     * The current state, shared between the engine thread that sets it and the
     * HTTP threads that read it. A single volatile reference: reads never block
     * and never wait on the settings or audio locks (KTD9's lock-free state route).
     */
    static final class Holder {
        // null until the engine first reports, so the settings page can still say
        // it has not started; the eyes show the idle look meanwhile.
        private volatile VoiceState current;

        void set(VoiceState state) {
            if (state == null) {
                throw new NullPointerException("state");
            }
            current = state;
        }

        VoiceState get() {
            VoiceState s = current;
            return s == null ? LISTENING : s;
        }

        /** /voice-state's plain-text body: the wire name alone, no newline. */
        String text() {
            return get().wireName();
        }

        /** The settings page's state line: the state's label (or the not-started
         * placeholder), then ": detail" when the engine gave one. */
        String settingsLine(String detail) {
            VoiceState s = current;
            String base = s == null ? SettingsPage.STATE_PLACEHOLDER : s.label();
            return detail == null || detail.isEmpty() ? base : base + ": " + detail;
        }
    }

    // Per-state looks, as overrides on the shared eyes keyed by a class on #rig.
    // Listening has no rules: it is the remote-control mode's idle look unchanged.
    // Only opacity is animated (the speaking/closing pulse), the same cheap
    // compositor-only kind of animation the blink already runs.
    private static final String STATE_CSS =
            // Engaged: a warmer, brighter core than the idle red.
            "#rig.s-connecting .glow-core,#rig.s-conversing .glow-core,#rig.s-speaking .glow-core{"
            + "background:radial-gradient(circle at center,#fffdf0 0%,#ffe36b 12%,#ffab33 30%,"
            + "#f2571f 50%,#8f1d0d 66%,rgba(0,0,0,0) 80%)}"
            // Connecting (wake acknowledged): the glow widens, like eyes opening up.
            + "#rig.s-connecting .glow{width:84%;height:84%;left:8%;top:8%}"
            + "@keyframes talk{from{opacity:1}to{opacity:.45}}"
            + "#rig.s-speaking .glow{animation:talk .45s ease-in-out infinite alternate}"
            // Closing keeps the pulse (the goodbye may still be playing) but slower,
            // on the idle colors, so it reads as winding down.
            + "#rig.s-closing .glow{animation:talk 1.2s ease-in-out infinite alternate}"
            // Unreachable: dimmed bezel, cold grey-blue core, and a slower blink.
            // !important because the blink's duration is set inline by the eyes' script.
            + "#rig.s-unreachable .housing{opacity:.55}"
            + "#rig.s-unreachable .glow{opacity:.7}"
            + "#rig.s-unreachable .glow-core{"
            + "background:radial-gradient(circle at center,#e3edf5 0%,#a9bccd 10%,#6784a0 28%,"
            + "#314b66 48%,#111e2c 64%,rgba(0,0,0,0) 78%);animation-duration:13s!important}";

    // The poll. setTimeout chained off each answer (not setInterval) so the next
    // delay follows the state that answer just showed: the rate switches on the
    // transition the page observes. XMLHttpRequest with a timeout rather than
    // fetch so a hung request still ends in loadend and the poll carries on.
    // Anything but a 200 naming a known state leaves the eyes as they are.
    private static final String POLL_JS =
            "var rig=document.getElementById('rig');"
            + "var voiceState='';"
            + "function showVoiceState(s){"
            + "if(s===voiceState)return;"
            + "voiceState=s;rig.className='s-'+s;"
            + "if(s==='connecting'&&glows)gazeTo(0,0,250);"
            + "}"
            + "function pollVoiceState(){"
            + "var x=new XMLHttpRequest();"
            + "x.open('GET','/voice-state',true);"
            + "x.timeout=1000;"
            + "x.onloadend=function(){"
            + "if(x.status===200){var s=x.responseText.trim();if(voiceSlow.hasOwnProperty(s))showVoiceState(s);}"
            + "setTimeout(pollVoiceState,voiceSlow[voiceState]?1000:250);"
            + "};"
            + "x.send();"
            + "}"
            + "showVoiceState('listening');"
            + "pollVoiceState();";

    // Runs after the eyes define gazeTo: every glance goes through it, so wrapping
    // it steadies the gaze while engaged without touching the shared eyes' timing.
    // Connecting looks straight ahead; conversing and speaking keep small movements
    // so the eyes stay on the person rather than freezing.
    private static final String GAZE_JS =
            "var eyesGazeTo=gazeTo;"
            + "gazeTo=function(x,y,speedMs){"
            + "if(voiceState==='connecting'){x=0;y=0;}"
            + "else if(voiceState==='conversing'||voiceState==='speaking'){x*=0.3;y*=0.3;}"
            + "eyesGazeTo(x,y,speedMs);"
            + "};";

    /** GET /device-view: the shared eyes plus the state hook. */
    static final String DEVICE_VIEW_HTML = EyesPage.build(
            "Voice Mode", STATE_CSS, "", slowPollTable() + POLL_JS, GAZE_JS);

    /** "var voiceSlow={'listening':true,...};" — every known state and whether it
     * polls slowly, generated from the enum so the page and slowPoll() agree. */
    private static String slowPollTable() {
        StringBuilder b = new StringBuilder("var voiceSlow={");
        VoiceState[] all = values();
        for (int i = 0; i < all.length; i++) {
            if (i > 0) {
                b.append(',');
            }
            b.append('\'').append(all[i].wireName).append("':").append(all[i].slowPoll);
        }
        return b.append("};").toString();
    }
}
