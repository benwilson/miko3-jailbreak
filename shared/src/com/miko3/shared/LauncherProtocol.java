package com.miko3.shared;

/**
 * Cross-app string constants for the launcher-to-mode protocol (KTD3,
 * U10) — declared once so the launcher and every mode app can't drift out
 * of sync on an action string or extra key with no compiler error to
 * catch it.
 */
public final class LauncherProtocol {
    private LauncherProtocol() {
    }

    /** Intent action a mode app binds to reach the launcher's DriveLeaseService. */
    public static final String DRIVE_LEASE_ACTION = "com.miko3.launcher.DRIVE_LEASE";

    /** Intent action a mode app binds to reach the launcher's RobotSettingsService
     * (settings plan U5). Modes go through RobotSettingsClient, not this directly. */
    public static final String ROBOT_SETTINGS_ACTION = "com.miko3.launcher.ROBOT_SETTINGS";

    /** Intent action a mode app binds to reach the launcher's SpeechService
     * (voice plan U5). Modes go through RobotSpeechClient, not this directly. */
    public static final String ROBOT_SPEECH_ACTION = "com.miko3.launcher.ROBOT_SPEECH";

    /** Intent action a mode app binds to reach the launcher's PeopleService
     * (explore-on-claude plan U2). Modes go through RobotPeopleClient, not this directly. */
    public static final String ROBOT_PEOPLE_ACTION = "com.miko3.launcher.ROBOT_PEOPLE";

    /** Intent action a mode app binds to reach the launcher's ListenService
     * (explore-on-claude plan U3). Modes go through RobotListenClient, not this directly. */
    public static final String ROBOT_LISTEN_ACTION = "com.miko3.launcher.ROBOT_LISTEN";

    /** The launcher's continuous ears session (meeting plan U3, RobotEars):
     * served by the same ListenService, picked by this bind action. */
    public static final String ROBOT_EARS_ACTION = "com.miko3.launcher.ROBOT_EARS";

    public static final String LAUNCHER_PACKAGE = "com.miko3.launcher";

    /**
     * Robot 2026-10-01: an ears conversation listen's maxMs is the window to start
     * answering; an answer begun in it runs until 2 s with no speech
     * (EarsSession.ANSWER_SILENCE_MS, owner 2026-10-02), but the launcher cuts it
     * this long after the listen opened (EarsSession.LISTEN_HARD_CAP_MS).
     */
    public static final long EARS_LISTEN_HARD_CAP_MS = 60000;
    /**
     * How long from its start a mode holds an ears listen whose answer has started
     * (RobotEars.Callback.answering): the hard cap plus 3 s for the decode and the
     * delivery. Explore's brain mirrors it as ExploreTuning.answerHoldMs.
     */
    public static final long EARS_ANSWER_HOLD_MS = EARS_LISTEN_HARD_CAP_MS + 3000;

    /** A mode's fixed reason when the launcher does not answer a Binder
     * transaction the mode's build knows (meeting plan U4, KTD11): the two
     * APKs are installed together, and every proxy method added since checks
     * the transaction result, so an old launcher is named rather than failing
     * silently. */
    public static final String LAUNCHER_TOO_OLD = "launcher too old: install the current launcher";

    /** Boolean extra on a launch Intent the launcher sends a mode's Activity, asking
     * it to release its lease and exit through its own normal release path (U10). */
    public static final String EXTRA_FORCE_EXIT = "com.miko3.launcher.EXTRA_FORCE_EXIT";

    /** Registry ids (KTD8, U9): the value of the launcher's /launch-mode?mode= query
     * parameter and the "mode" field of each mode's presence answer. See ModeRegistry. */
    public static final String MODE_REMOTE_CONTROL = "remote-control";
    public static final String MODE_VOICE = "voice";
    public static final String MODE_EXPLORE = "explore";

    /** Launcher route that exits whichever mode is running and launches the one
     * named by LAUNCH_MODE_PARAM (remote-control when absent, for old links). */
    public static final String LAUNCH_MODE_PATH = "/launch-mode";
    public static final String LAUNCH_MODE_PARAM = "mode";

    /** Every mode's presence route: GET answers {"mode":"<id>","active":true|false}
     * from an explicit flag set while one of its Activity instances is current
     * (KTD8). RoutingHttpServer serves this path on the plain listener even once
     * HTTPS is up, so the launcher's loopback probe never sees the HTTPS redirect. */
    public static final String PRESENCE_PATH = "/presence";

    /** The launcher's Settings page (settings plan U4): GET renders it. */
    public static final String SETTINGS_PATH = "/settings";

    /** Settings actions. Each takes a POST carrying the page token in its body
     * and redirects back to SETTINGS_PATH with a status line (KTD6). */
    public static final String SETTINGS_CLAUDE_PATH = "/settings/claude";
    public static final String SETTINGS_CLAUDE_MODELS_PATH = "/settings/claude/models";
    public static final String SETTINGS_CLAUDE_TEST_PATH = "/settings/claude/test";
    public static final String SETTINGS_CLAUDE_FORGET_PATH = "/settings/claude/forget";
    /** Voice: the robot says the typed line through the launcher's speech queue. */
    public static final String SETTINGS_VOICE_SAY_PATH = "/settings/voice/say";
    /** People (explore-on-claude plan U2): rename or forget one person, by id. */
    public static final String SETTINGS_PEOPLE_RENAME_PATH = "/settings/people/rename";
    public static final String SETTINGS_PEOPLE_FORGET_PATH = "/settings/people/forget";
    /** GET ?id=<person id> answers that person's face JPEG. Under SETTINGS_PATH,
     * so it is TLS-only like the rest (isTlsOnlyPath): a face never crosses the
     * network in cleartext. */
    public static final String SETTINGS_PEOPLE_FACE_PATH = "/settings/people/face";
    /** The ears probe (meeting plan U1): POST with the page token and the nonce held by
     * the launcher's debug property answers per-second direction and recogniser rows;
     * 404 otherwise. Under SETTINGS_PATH, so TLS-only. Owner tooling: scripts/qa-ears-probe.py. */
    public static final String SETTINGS_EARS_PROBE_PATH = "/settings/ears-probe";
    /** Conversation (meeting plan U4, KTD11): the persona box and the "answers
     * when spoken to" switch, saved together. */
    public static final String SETTINGS_CONVERSATION_PATH = "/settings/conversation";
    /** Face plan U5 (KTD8): GET ?id=<check handle> answers that face check's
     * crop JPEG, 404 once it has rolled off the ring. Under SETTINGS_PATH, so TLS-only. */
    public static final String SETTINGS_FACE_CHECK_CROP_PATH = "/settings/face/check-crop";
    /** GET ?id=<person id>&slot=<n>&at=<added-at ms> answers that stored photo while
     * the slot still holds the photo added at that time, else a "replaced" placeholder. */
    public static final String SETTINGS_PEOPLE_PHOTO_PATH = "/settings/people/photo";
    /** POST with the page token, id and slot deletes one photo; never a person's last. */
    public static final String SETTINGS_PEOPLE_PHOTO_DELETE_PATH = "/settings/people/photo/delete";
    /** Face plan U5 (KTD5): POST with the page token and any of confident, close,
     * margin, min_width, dark_floor, dim_level, blur_floor sets the face thresholds.
     * Loopback callers only (the CLI over adb forward); 403 for anyone else. */
    public static final String SETTINGS_FACE_THRESHOLDS_PATH = "/settings/face/thresholds";
    /** POST with the page token answers the recent face checks, the people with
     * their photo counts and the thresholds as JSON, names included, no images.
     * Owner tooling: scripts/robot-faces.py. */
    public static final String SETTINGS_FACE_STATE_PATH = "/settings/face/state";

    /** POST with the page token deletes every entry in the feedback log
     * (owner 2026-10-02: what people told the robot about himself). */
    public static final String SETTINGS_FEEDBACK_CLEAR_PATH = "/settings/feedback/clear";

    /** POST with the page token answers the feedback log as JSON, newest
     * first, with no person ids. Owner tooling: scripts/pull-feedback.py. */
    public static final String SETTINGS_FEEDBACK_STATE_PATH = "/settings/feedback/state";

    /** The launcher's fixed HTTPS port, where the Settings page lives. */
    public static final int LAUNCHER_HTTPS_PORT = 8443;

    /**
     * True for SETTINGS_PATH and everything under it. These carry the API key,
     * so RoutingHttpServer serves them over TLS only: on the plain listener a
     * GET is redirected once HTTPS is up, and anything else gets a 503 without
     * its body being read (a redirected POST would already have sent the key
     * in cleartext, and would lose its body anyway).
     */
    public static boolean isTlsOnlyPath(String path) {
        return path != null && (path.equals(SETTINGS_PATH) || path.startsWith(SETTINGS_PATH + "/"));
    }

    /**
     * The plain-listener refusal for a TLS-only path. Fixed text apart from the
     * host, which comes from the request's Host header and is used only when
     * it is a plain host name or IPv4 address.
     */
    public static String settingsNeedHttpsMessage(String hostHeader) {
        String host = hostHeader == null ? "" : hostHeader;
        int colon = host.indexOf(':');
        if (colon >= 0) {
            host = host.substring(0, colon);
        }
        if (!host.matches("[A-Za-z0-9.-]{1,253}")) {
            host = "<robot address>";
        }
        return "The settings page needs HTTPS. Retry at https://" + host + ":" + LAUNCHER_HTTPS_PORT
                + SETTINGS_PATH + "\n";
    }
}
