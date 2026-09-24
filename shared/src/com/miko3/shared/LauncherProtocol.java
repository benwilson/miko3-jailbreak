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

    public static final String LAUNCHER_PACKAGE = "com.miko3.launcher";

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
}
