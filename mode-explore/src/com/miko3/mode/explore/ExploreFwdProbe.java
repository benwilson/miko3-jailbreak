package com.miko3.mode.explore;

/**
 * The MikoExploreFwdProbe debug hook's driving (owner 2026-10-02 at home: a black floor
 * the front ToF never sees, so it reads 16383 and Explore stays eyes-only). While the
 * hook is on, ExploreLoop hands the wheels to this instead of the brain: a still spell,
 * forward ticks for legMs, a still spell, back ticks for legMs, and round again. Each
 * phase ends with one trace note of what the motor board did and what the sensors read
 * during it ("fwdprobe FORWARD: wheels L=.. R=.., cpl2 n/m, tof min/max, ir1 .., ir2 ..,
 * accel z ..."), so a capture says whether the MCU refuses forward on that floor and
 * whether the ToF ever returns while the chassis moves.
 *
 * Only for a floor the owner has said has no drops: it drives forward whatever the ToF
 * reads. It still stops when the lease is lost or the readings go stale. Plain Java;
 * brain thread only.
 *
 * It also switches the MCU's own ToF check off (TOFDS) each time it starts or resumes
 * after a halt, and back on (TOFEN) when it ends (dark-floor mode, 2026-10-02): the
 * FORWARD report then says whether the MCU still refuses forward on the black floor
 * (cpl2, wheels) and whether tof/ir2 change with the check off. Each report says
 * whether the check was off ("tof check off") during it.
 */
final class ExploreFwdProbe {
    private enum Phase { OFF, HALTED, STILL, FORWARD, BACK }

    private final DriveGate motor;
    private final ExploreBrain.Trace trace;
    private final long staleMs;
    private final long stillMs;
    private final long legMs;
    private final long tickMs;

    private Phase phase = Phase.OFF;
    private Phase next = Phase.FORWARD;
    private String haltReason;
    private long phaseStartMs;
    private long lastTickMs;
    /** A TOFDS went out and no TOFEN since. */
    private boolean tofCheckOff;

    // What the current leg saw.
    private long startLeft;
    private long startRight;
    private boolean haveStart;
    private long lastLeft;
    private long lastRight;
    private int readings;
    private int refusals;
    private int tofMin;
    private int tofMax;
    private int tofValid;
    private int ir1Ones;
    private int ir2Ones;
    private int accelZMin;
    private int accelZMax;

    ExploreFwdProbe(DriveGate motor, ExploreBrain.Trace trace, ExploreTuning tuning) {
        this.motor = motor;
        this.trace = trace;
        this.staleMs = tuning.staleMs;
        this.stillMs = 2000;
        this.legMs = 1500;
        this.tickMs = tuning.hopTickMs;
    }

    /** One loop pass while the hook is on. */
    void onTick(long nowMs, SensorReading latest, boolean leaseHeld) {
        String unsafe = !leaseHeld ? "no lease"
                : latest == null || nowMs - latest.timestampMs > staleMs ? "readings stale"
                : null;
        if (unsafe != null) {
            if (phase != Phase.HALTED || !unsafe.equals(haltReason)) {
                motor.stop();
                haltReason = unsafe;
                enter(Phase.HALTED, nowMs, "fwdprobe halted: " + unsafe);
            }
            return;
        }
        observe(latest);
        long elapsed = nowMs - phaseStartMs;
        if (phase == Phase.OFF || phase == Phase.HALTED) {
            motor.stop();
            // Starting, or resuming after a halt (the MCU may have reset): the check off again.
            if (motor.tofCheckOff()) {
                tofCheckOff = true;
                note("fwdprobe: TOFDS sent (MCU ToF check off)");
            } else {
                note("fwdprobe: TOFDS not sent");
            }
            enter(Phase.STILL, nowMs, "fwdprobe still");
        } else if (phase == Phase.STILL && elapsed >= stillMs) {
            report("STILL");
            Phase leg = next;
            next = leg == Phase.FORWARD ? Phase.BACK : Phase.FORWARD;
            enter(leg, nowMs, "fwdprobe " + leg);
            lastTickMs = Long.MIN_VALUE;
        } else if (phase == Phase.FORWARD || phase == Phase.BACK) {
            if (elapsed >= legMs) {
                motor.stop();
                report(phase.name());
                enter(Phase.STILL, nowMs, "fwdprobe still");
            } else if (lastTickMs == Long.MIN_VALUE || nowMs - lastTickMs >= tickMs) {
                lastTickMs = nowMs;
                if (phase == Phase.FORWARD) {
                    motor.hopTick();
                } else {
                    motor.backTick();
                }
            }
        }
    }

    /** The hook went off: stop, and leave the wheels to the brain. */
    void end() {
        if (phase == Phase.OFF) {
            return;
        }
        motor.stop();
        if (tofCheckOff) {
            note(motor.tofCheckOn() ? "fwdprobe: TOFEN sent (MCU ToF check on)" : "fwdprobe: TOFEN not sent");
            tofCheckOff = false;
        }
        phase = Phase.OFF;
        next = Phase.FORWARD;
        haltReason = null;
        note("fwdprobe off");
    }

    boolean active() {
        return phase != Phase.OFF;
    }

    private void observe(SensorReading r) {
        if (r.hasWheels()) {
            if (!haveStart) {
                startLeft = r.wheelLeft;
                startRight = r.wheelRight;
                haveStart = true;
            }
            lastLeft = r.wheelLeft;
            lastRight = r.wheelRight;
        }
        readings++;
        if (r.cpl != null && r.cpl == 2) {
            refusals++;
        }
        if (r.tof != 16383) {
            tofValid++;
            tofMin = Math.min(tofMin, r.tof);
            tofMax = Math.max(tofMax, r.tof);
        }
        if (r.ir1 == 1) {
            ir1Ones++;
        }
        if (r.ir2 == 1) {
            ir2Ones++;
        }
        if (r.hasAccel) {
            accelZMin = Math.min(accelZMin, r.accelZ);
            accelZMax = Math.max(accelZMax, r.accelZ);
        }
    }

    private void report(String what) {
        note("fwdprobe " + what + ": wheels L=" + (haveStart ? lastLeft - startLeft : 0)
                + " R=" + (haveStart ? lastRight - startRight : 0)
                + ", cpl2 " + refusals + "/" + readings
                + ", tof valid " + tofValid + "/" + readings
                + (tofValid > 0 ? " (" + tofMin + "-" + tofMax + ")" : "")
                + ", ir1=1 " + ir1Ones + ", ir2=1 " + ir2Ones
                + (accelZMin <= accelZMax ? ", accel z " + accelZMin + ".." + accelZMax : "")
                + (tofCheckOff ? ", tof check off" : ""));
    }

    private void enter(Phase to, long nowMs, String message) {
        phase = to;
        phaseStartMs = nowMs;
        haveStart = false;
        readings = 0;
        refusals = 0;
        tofMin = Integer.MAX_VALUE;
        tofMax = Integer.MIN_VALUE;
        tofValid = 0;
        ir1Ones = 0;
        ir2Ones = 0;
        accelZMin = Integer.MAX_VALUE;
        accelZMax = Integer.MIN_VALUE;
        note(message);
    }

    private void note(String message) {
        if (trace != null) {
            trace.note(message);
        }
    }
}
