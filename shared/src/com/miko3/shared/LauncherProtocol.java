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

    public static final String LAUNCHER_PACKAGE = "com.miko3.launcher";

    /** Boolean extra on a launch Intent the launcher sends a mode's Activity, asking
     * it to release its lease and exit through its own normal release path (U10). */
    public static final String EXTRA_FORCE_EXIT = "com.miko3.launcher.EXTRA_FORCE_EXIT";
}
