package com.miko3.mode.explore;

/**
 * The MikoExploreSpin debug hook's driving (explore nav plan U1, KTD1). While the
 * hook is on, ExploreLoop hands the wheels to this instead of the brain, and he
 * turns in place: a still spell, left for spinLegMs, a still spell, right, and
 * round again. scripts/qa-explore-sensors.py --gyro-circle takes the gyro bias
 * from the still spells and the yaw axis, sign and scale from the turns.
 *
 * The brain's safety rules still hold: motion goes through the lease-gated
 * DriveGate, and he turns only while the lease is held, the floor sensors are
 * calibrated (a fresh install never moves, R10) and the newest reading is fresh.
 * When any of those fails he stops and, once it clears, starts over with a still
 * spell. Each phase change is one trace note ("spin still", "spin LEFT",
 * "spin RIGHT", "spin halted: <reason>", "spin off"), which the QA script
 * segments its capture by.
 *
 * Plain Java; brain thread only.
 */
final class ExploreSpin {
    private enum Phase { OFF, HALTED, STILL, TURNING }

    private final ExploreBrain.Motor motor;
    private final ExploreBrain.Trace trace;
    private final long staleMs;
    private final long stillMs;
    private final long legMs;
    private final boolean calibrated;

    private Phase phase = Phase.OFF;
    private String haltReason;
    private long phaseStartMs;
    /** The way the current turn goes, or the next one after a still spell. */
    private ExploreBrain.Direction direction = ExploreBrain.Direction.LEFT;

    ExploreSpin(ExploreBrain.Motor motor, ExploreBrain.Trace trace, ExploreTuning tuning) {
        this.motor = motor;
        this.trace = trace;
        this.staleMs = tuning.staleMs;
        this.stillMs = tuning.spinStillMs;
        this.legMs = tuning.spinLegMs;
        this.calibrated = tuning.calibration != null && tuning.calibration.complete();
    }

    /** One loop pass while the hook is on. */
    void onTick(long nowMs, SensorReading latest, boolean leaseHeld) {
        String unsafe = !calibrated ? "sensors uncalibrated"
                : !leaseHeld ? "no lease"
                : latest == null || nowMs - latest.timestampMs > staleMs ? "readings stale"
                : null;
        if (unsafe != null) {
            if (phase != Phase.HALTED || !unsafe.equals(haltReason)) {
                motor.stop();
                haltReason = unsafe;
                enter(Phase.HALTED, nowMs, "spin halted: " + unsafe);
            }
            return;
        }
        long elapsed = nowMs - phaseStartMs;
        if (phase == Phase.OFF || phase == Phase.HALTED) {
            motor.stop();
            enter(Phase.STILL, nowMs, "spin still");
        } else if (phase == Phase.STILL && elapsed >= stillMs) {
            motor.turn(direction);
            enter(Phase.TURNING, nowMs, "spin " + direction);
        } else if (phase == Phase.TURNING && elapsed >= legMs) {
            motor.stop();
            direction = direction.opposite();
            enter(Phase.STILL, nowMs, "spin still");
        }
    }

    /** The hook went off: stop, and leave the wheels to the brain. */
    void end() {
        if (phase == Phase.OFF) {
            return;
        }
        motor.stop();
        phase = Phase.OFF;
        haltReason = null;
        direction = ExploreBrain.Direction.LEFT;
        note("spin off");
    }

    boolean active() {
        return phase != Phase.OFF;
    }

    private void enter(Phase next, long nowMs, String message) {
        phase = next;
        phaseStartMs = nowMs;
        note(message);
    }

    private void note(String message) {
        if (trace != null) {
            trace.note(message);
        }
    }
}
