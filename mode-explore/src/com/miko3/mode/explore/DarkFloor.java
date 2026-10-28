package com.miko3.mode.explore;

/**
 * Dark-floor mode's switch and the MCU's ToF check (owner 2026-10-02, drop-free floors
 * only). On a black floor the front ToF gets no return (16383 all the time), so the MCU
 * refuses forward (CPL=2) and flags ir2. With persist.miko3.explore.dark_floor=1 this
 * sends TOFDS, which switches the MCU's own ToF check off (its readings keep flowing),
 * and the brain drives on the camera, wheel stalls and the accelerometer instead
 * (ExploreBrain.setDarkFloor, HazardClassifier).
 *
 * The property is read when Explore starts and again every pollMs, so it can be flipped
 * live. While it is on and the lease is held, TOFDS goes out once per drive session and
 * again whenever the MCU might have reset (an MCU reset switches the check back on):
 * after the lease comes back (a fresh driver), after the readings stopped for
 * staleGapMs and resumed, and when ir2=1 or CPL=2 shows up again (at most every
 * resendGapMs). TOFEN goes out when it is switched off and when Explore stops (end());
 * ExploreDrive also sends TOFEN before it lets a driver go with the check still off, so a
 * lost lease never leaves TOFDS behind.
 *
 * Plain Java, brain thread only (end() after the brain thread has finished).
 */
final class DarkFloor {
    static final String PROPERTY = "persist.miko3.explore.dark_floor";
    static final long POLL_MS = 5000;
    static final long STALE_GAP_MS = 1000;
    static final long RESEND_GAP_MS = 5000;

    /** Whether dark-floor mode is wanted (the system property on the robot). */
    interface Source {
        boolean darkFloor();
    }

    static final Source OFF = new Source() {
        @Override
        public boolean darkFloor() {
            return false;
        }
    };

    /** "1", "true", "on" or "yes" (any case, trimmed) is on; anything else off. */
    static boolean parse(String value) {
        String v = value == null ? "" : value.trim().toLowerCase(java.util.Locale.US);
        return v.equals("1") || v.equals("true") || v.equals("on") || v.equals("yes");
    }

    private final Source source;
    private final DriveGate gate;
    private final ExploreBrain.Trace trace;
    private final long pollMs;

    private boolean on;
    private long nextPollMs = Long.MIN_VALUE;
    /** A TOFDS went out in this drive session and no TOFEN since. */
    private boolean checkOff;
    private boolean wasHeld;
    private long lastSentMs = Long.MIN_VALUE;
    private long lastReadingMs = Long.MIN_VALUE;
    /** When a TOFDS last failed to go out, so the retry waits a little instead of every pass. */
    private long lastFailedMs = Long.MIN_VALUE;

    DarkFloor(Source source, DriveGate gate, ExploreBrain.Trace trace, long pollMs) {
        this.source = source == null ? OFF : source;
        this.gate = gate;
        this.trace = trace;
        this.pollMs = Math.max(1, pollMs);
    }

    boolean on() {
        return on;
    }

    /** Reads the property when due; true when dark-floor mode just changed (the caller tells the brain). */
    boolean poll(long nowMs) {
        if (nowMs < nextPollMs) {
            return false;
        }
        nextPollMs = nowMs + pollMs;
        boolean wanted;
        try {
            wanted = source.darkFloor();
        } catch (RuntimeException e) {
            wanted = false;
        }
        if (wanted == on) {
            return false;
        }
        on = wanted;
        note("dark floor: " + (on ? "on" : "off"));
        if (!on) {
            restore("dark floor switched off");
        }
        return true;
    }

    /** One loop pass: keep the MCU's check off while on and driving, re-sending when it may have reset. */
    void onPass(long nowMs, boolean leaseHeld, SensorReading latest) {
        boolean regained = leaseHeld && !wasHeld;
        if (!leaseHeld && wasHeld) {
            // ExploreDrive has stopped and let the driver go (with a TOFEN if the check was
            // off); the next driver starts with the MCU's check on.
            checkOff = false;
        }
        wasHeld = leaseHeld;
        String why = null;
        if (latest != null && latest.timestampMs != lastReadingMs) {
            if (lastReadingMs != Long.MIN_VALUE && latest.timestampMs - lastReadingMs >= STALE_GAP_MS) {
                why = "readings resumed after " + (latest.timestampMs - lastReadingMs) + " ms";
            } else if (latest.ir2 == 1 || (latest.cpl != null && latest.cpl == 2)) {
                if (lastSentMs == Long.MIN_VALUE || nowMs - lastSentMs >= RESEND_GAP_MS) {
                    why = "the MCU flag is back (" + (latest.ir2 == 1 ? "ir2=1" : "cpl=2") + ")";
                }
            }
            lastReadingMs = latest.timestampMs;
        }
        if (!on || !leaseHeld) {
            return;
        }
        if (!checkOff) {
            if (!regained && lastFailedMs != Long.MIN_VALUE && nowMs - lastFailedMs < STALE_GAP_MS) {
                return; // the last one failed to go out: try again shortly, not every pass
            }
            why = regained && lastSentMs != Long.MIN_VALUE ? "lease re-acquired" : "drive session start";
        }
        if (why == null) {
            return;
        }
        lastSentMs = nowMs;
        if (gate.tofCheckOff()) {
            checkOff = true;
            lastFailedMs = Long.MIN_VALUE;
            note("dark floor: TOFDS sent (" + why + ")");
        } else {
            lastFailedMs = nowMs;
            note("dark floor: TOFDS not sent (" + why + ")");
        }
    }

    /** Someone else switched the MCU's check (the fwd probe's TOFEN): send TOFDS again if on. */
    void checkUnknown() {
        checkOff = false;
    }

    /** Explore is stopping: the MCU's check back on. */
    void end() {
        restore("explore stopped");
    }

    private void restore(String why) {
        if (!checkOff) {
            return;
        }
        checkOff = false;
        note(gate.tofCheckOn() ? "dark floor: TOFEN sent (" + why + ")" : "dark floor: TOFEN not sent (" + why + ")");
    }

    private void note(String message) {
        if (trace != null) {
            trace.note(message);
        }
    }
}
