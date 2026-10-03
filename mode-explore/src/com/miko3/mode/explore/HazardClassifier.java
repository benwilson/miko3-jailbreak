package com.miko3.mode.explore;

/**
 * Whether the sensors can be trusted right now, and if so whether anything is
 * ahead (KTD3). Plain Java with no android.* imports, so it runs on the host
 * JVM in scripts/tests (fixtures/explore_brain_harness). ExploreBrain owns the
 * only instance and calls it from its own thread; not thread-safe on its own.
 *
 * Unavailable, the conservative answer, whenever any of these holds:
 *  - no calibration, or an incomplete one (KTD9);
 *  - no reading for staleMs (three missed 100 ms polls);
 *  - the latest reading is flagged as a fault, or tof is at its fault value
 *    without a calibrated IR edge flag agreeing;
 *  - tof has not changed for frozenTofWindowMs, the leftover-TOFDS symptom.
 *    This rule is tof-only: ir1 and ir2 sitting still on a flat desk is normal.
 *
 * Going back to available needs recoveryStreak good readings in a row, each
 * within staleMs of the one before, so a flapping feed cannot bounce the robot
 * in and out of driving. Any bad reading or staleness restarts the count.
 *
 * Once available, a hazard is a calibrated threshold crossed, CPL=2 (the
 * controller refused a forward command, R9) in the latest reading, or the
 * charger latch (CPL=3, meeting plan U1, KTD6): docked, the controller refuses
 * every motion, so it reads as motion refused too, and charger() tells the
 * brain which it was.
 *
 * During an approach the brain asks approach() instead (KTD4): the same ir flag
 * and CPL=2 refusal fire both for something close and for a drop-off, and the
 * direction tof left the controller's safe band tells them apart.
 *
 * Dark-floor mode (setDarkFloor; owner 2026-10-02, drop-free floors only): on a black
 * floor the ToF gets no return and reads its fault value all the time, and the MCU's own
 * check is switched off (TOFDS), so:
 *  - tof at its fault value means "no floor reading": neither unavailable, nor an edge,
 *    nor frozen;
 *  - a valid tof still gives an obstacle below obstacleTofBelow; edgeTofAbove and the
 *    ir edge flag are ignored;
 *  - CPL=2 and the charger latch are still motion refused;
 *  - the accelerometer stands in for the cliff sensor: darkTiltReadings readings in a row
 *    with z below darkFlatAccelZ * cos(darkTiltDeg) are a TILT hazard ahead (nose dip or
 *    climbing something); below cos(darkLiftDeg) he is lifted or tipped over, and the
 *    sensors are unavailable until recoveryStreak flat readings in a row.
 */
final class HazardClassifier {
    enum Status { UNAVAILABLE, HAZARD, CLEAR }

    /** TILT: dark-floor mode's accelerometer nose-dip guard (treated as a hazard ahead). */
    enum Kind { EDGE, OBSTACLE, CPL, TILT }

    /**
     * The verdict while approaching something (KTD4). CLOSE and CLOSE_REFUSED are
     * both arrival; CLOSE_REFUSED means the controller refused forward (CPL=2), which
     * the brain must honour even where it ignores ir/low-tof arrival (leg start).
     */
    enum ApproachVerdict {
        UNAVAILABLE, CLEAR, CLOSE, CLOSE_REFUSED, EDGE;

        boolean isClose() {
            return this == CLOSE || this == CLOSE_REFUSED;
        }
    }

    /** What is ahead, and on which side when the sensor says (null = dead ahead or unknown). */
    static final class Hazard {
        final Kind kind;
        final ExploreBrain.Direction side;

        Hazard(Kind kind, ExploreBrain.Direction side) {
            this.kind = kind;
            this.side = side;
        }

        @Override
        public String toString() {
            return kind + (side == null ? "" : "/" + side);
        }
    }

    private final ExploreTuning tuning;
    private final ExploreTuning.Calibration cal;

    private SensorReading latest;
    private int streak;
    private boolean available;
    /** When tof took its current value, for the frozen rule. */
    private long tofSinceMs;
    private String reason = "no readings yet";
    private boolean darkFloor;
    /** Dark-floor mode: accelerometer readings in a row past the tilt and the lift limits. */
    private int tiltRun;
    private int liftRun;
    private final int tiltBelowZ;
    private final int liftBelowZ;

    HazardClassifier(ExploreTuning tuning) {
        this.tuning = tuning;
        ExploreTuning.Calibration c = tuning.calibration;
        this.cal = c != null && c.complete() ? c : null;
        if (cal == null) {
            reason = c == null ? "uncalibrated" : "calibration incomplete";
        }
        tiltBelowZ = (int) Math.round(tuning.darkFlatAccelZ * Math.cos(Math.toRadians(tuning.darkTiltDeg)));
        liftBelowZ = (int) Math.round(tuning.darkFlatAccelZ * Math.cos(Math.toRadians(tuning.darkLiftDeg)));
    }

    /**
     * Dark-floor mode on or off (see the class comment). Either way the readings must earn
     * a fresh recovery streak under the new rules before the sensors are available again.
     */
    void setDarkFloor(boolean on) {
        if (on == darkFloor) {
            return;
        }
        darkFloor = on;
        streak = 0;
        available = false;
        tiltRun = 0;
        liftRun = 0;
        if (cal != null) {
            reason = on ? "dark floor: on, waiting for readings" : "dark floor: off, waiting for readings";
        }
    }

    boolean darkFloor() {
        return darkFloor;
    }

    /** Dark-floor mode: the latest readings tip past the tilt limit (a TILT hazard while available). */
    boolean tilted() {
        return darkFloor && tiltRun >= tuning.darkTiltReadings;
    }

    /** A new reading. Out-of-order or duplicate readings are ignored. */
    void offer(SensorReading r) {
        if (r == null || (latest != null && r.timestampMs <= latest.timestampMs)) {
            return;
        }
        SensorReading prev = latest;
        latest = r;
        if (prev == null || r.tof != prev.tof) {
            tofSinceMs = r.timestampMs;
        }
        if (prev == null || r.timestampMs - prev.timestampMs >= tuning.staleMs) {
            streak = 0;
        }
        if (r.hasAccel) {
            tiltRun = r.accelZ < tiltBelowZ ? tiltRun + 1 : 0;
            liftRun = r.accelZ < liftBelowZ ? liftRun + 1 : 0;
        }
        String bad = badReason(r);
        if (bad != null) {
            streak = 0;
            available = false;
            if (cal != null) {
                reason = bad;
            }
            return;
        }
        streak++;
        if (streak >= tuning.recoveryStreak) {
            available = true;
        }
    }

    /** The verdict at nowMs, from the latest reading. */
    Status status(long nowMs) {
        if (cal == null) {
            return Status.UNAVAILABLE;
        }
        if (latest == null || nowMs - latest.timestampMs >= tuning.staleMs) {
            if (latest != null) {
                reason = "stale: no reading for " + (nowMs - latest.timestampMs) + " ms";
            }
            streak = 0;
            available = false;
            return Status.UNAVAILABLE;
        }
        if (!available) {
            if (badReason(latest) == null) {
                reason = "recovering: " + streak + "/" + tuning.recoveryStreak + " good readings";
            }
            return Status.UNAVAILABLE;
        }
        return hazard() != null ? Status.HAZARD : Status.CLEAR;
    }

    /** The hazard in the latest reading, or null. Meaningful only while available. */
    Hazard hazard() {
        SensorReading r = latest;
        if (r == null || cal == null) {
            return null;
        }
        if (darkFloor) {
            return darkHazard(r);
        }
        if (cal.edgeIr >= 0) {
            boolean e1 = irEdge(r.ir1);
            boolean e2 = irEdge(r.ir2);
            if (e1 || e2) {
                ExploreBrain.Direction side = null;
                // A side only when both channels report; with one absent there is
                // nothing to compare, and guessing pins every escape to one direction.
                if (e1 != e2 && r.ir1 >= 0 && r.ir2 >= 0) {
                    boolean left = e1 == tuning.ir1IsLeft;
                    side = left ? ExploreBrain.Direction.LEFT : ExploreBrain.Direction.RIGHT;
                }
                return new Hazard(Kind.EDGE, side);
            }
        }
        if (cal.edgeTofAbove >= 0 && r.tof > cal.edgeTofAbove) {
            return new Hazard(Kind.EDGE, null);
        }
        if (cal.obstacleTofBelow >= 0 && r.tof < cal.obstacleTofBelow) {
            return new Hazard(Kind.OBSTACLE, null);
        }
        if (refused(r)) {
            return new Hazard(Kind.CPL, null);
        }
        return null;
    }

    /** hazard() in dark-floor mode: tilt, a valid low tof, or a refusal; no edge rules. */
    private Hazard darkHazard(SensorReading r) {
        if (tilted()) {
            return new Hazard(Kind.TILT, null);
        }
        if (cal.obstacleTofBelow >= 0 && r.tof > 0 && r.tof != tuning.tofFault && r.tof < cal.obstacleTofBelow) {
            return new Hazard(Kind.OBSTACLE, null);
        }
        if (refused(r)) {
            return new Hazard(Kind.CPL, null);
        }
        return null;
    }

    /** The controller refused motion: a forward refusal (CPL=2) or the charger latch. */
    private static boolean refused(SensorReading r) {
        return (r.cpl != null && r.cpl == 2) || r.charger;
    }

    /** True while the latest reading carries the charger latch (meeting plan U1, KTD6):
     * he is docked and no motion will be honoured. False with no reading. */
    boolean charger() {
        SensorReading r = latest;
        return r != null && r.charger;
    }

    /**
     * The latest reading is ordinary floor by our own sensor: no fault, tof at or
     * above obstacleTofBelow and not above edgeTofAbove, no ir edge flag (with at
     * least one edge threshold calibrated). The brain
     * retries a CPL=2 refusal once only then (owner-approved 2026-09-25); false with
     * no calibration or no reading.
     */
    boolean plainFloor() {
        SensorReading r = latest;
        if (darkFloor) {
            // No floor reading is the black floor itself; a valid one must not read close.
            return r != null && cal != null && !r.fault && !tilted()
                    && (r.tof == tuning.tofFault || cal.obstacleTofBelow < 0 || r.tof >= cal.obstacleTofBelow);
        }
        if (r == null || cal == null || r.fault || r.tof == tuning.tofFault || cal.obstacleTofBelow < 0
                || (cal.edgeTofAbove < 0 && cal.edgeIr < 0)) {
            return false;
        }
        if (cal.edgeIr >= 0 && (irEdge(r.ir1) || irEdge(r.ir2))) {
            return false;
        }
        if (cal.edgeTofAbove >= 0 && r.tof > cal.edgeTofAbove) {
            return false;
        }
        return r.tof >= cal.obstacleTofBelow;
    }

    /**
     * The approach-mode verdict at nowMs (KTD4). Unavailable exactly when status()
     * is. Otherwise, from the latest reading:
     *  - tof at its fault value, or above a calibrated edgeTofAbove: EDGE;
     *  - tof below the calibrated obstacleTofBelow: CLOSE;
     *  - an ir edge flag or CPL=2 with tof below the band's lower bound: CLOSE;
     *    above its upper bound: EDGE; inside the band: EDGE, the safe reading;
     *  - otherwise CLEAR.
     * A CLOSE with CPL=2 or the charger latch present is reported as CLOSE_REFUSED.
     */
    ApproachVerdict approach(long nowMs) {
        if (status(nowMs) == Status.UNAVAILABLE) {
            return ApproachVerdict.UNAVAILABLE;
        }
        SensorReading r = latest;
        boolean refused = refused(r);
        ApproachVerdict close = refused ? ApproachVerdict.CLOSE_REFUSED : ApproachVerdict.CLOSE;
        if (darkFloor) {
            // A tilt is the edge-like verdict he backs away from; no floor reading is clear.
            if (tilted()) {
                return ApproachVerdict.EDGE;
            }
            if (cal.obstacleTofBelow >= 0 && r.tof > 0 && r.tof != tuning.tofFault && r.tof < cal.obstacleTofBelow) {
                return close;
            }
            return refused ? ApproachVerdict.CLOSE_REFUSED : ApproachVerdict.CLEAR;
        }
        if (r.tof == tuning.tofFault) {
            return ApproachVerdict.EDGE;
        }
        if (cal.edgeTofAbove >= 0 && r.tof > cal.edgeTofAbove) {
            return ApproachVerdict.EDGE;
        }
        if (cal.obstacleTofBelow >= 0 && r.tof < cal.obstacleTofBelow) {
            return close;
        }
        if (irEdgeFlagged(r) || refused) {
            if (r.tof < tuning.approachBandLower) {
                return close;
            }
            // Above the band it went toward a drop-off; inside it the flag says
            // nothing about which way tof went, so it is treated as the safe EDGE too.
            return ApproachVerdict.EDGE;
        }
        return ApproachVerdict.CLEAR;
    }

    /** Why the sensors are unavailable, for the log. */
    String reason() {
        return reason;
    }

    SensorReading latest() {
        return latest;
    }

    /** An absent IR field (all-'X' padding, -1) is never an edge, whichever way the rule points. */
    private boolean irEdge(int ir) {
        if (ir < 0) {
            return false;
        }
        return cal.edgeIrAbove ? ir > cal.edgeIr : ir < cal.edgeIr;
    }

    private boolean irEdgeFlagged(SensorReading r) {
        return cal != null && cal.edgeIr >= 0 && (irEdge(r.ir1) || irEdge(r.ir2));
    }

    /** Why this reading cannot be trusted, or null if it can. */
    private String badReason(SensorReading r) {
        if (r.fault) {
            return "fault reply";
        }
        if (darkFloor) {
            if (liftRun >= tuning.darkTiltReadings) {
                return "dark floor: lifted or tipped (accel z " + r.accelZ + ", flat " + tuning.darkFlatAccelZ + ")";
            }
            // No return from a black floor is constant by nature: only a valid tof can freeze.
            if (r.tof != tuning.tofFault && tuning.frozenTofWindowMs > 0
                    && r.timestampMs - tofSinceMs >= tuning.frozenTofWindowMs) {
                return "tof frozen at " + r.tof + " for " + (r.timestampMs - tofSinceMs) + " ms";
            }
            return null;
        }
        // Over an edge the ToF can read its out-of-range value; when a calibrated IR
        // edge flag agrees, the reading is an edge to back away from, not a dead sensor.
        if (r.tof == tuning.tofFault && !irEdgeFlagged(r)) {
            return "tof at fault value " + tuning.tofFault;
        }
        // The out-of-range value is naturally constant while he faces past an edge; with
        // the IR edge flag agreeing it is an edge (a hazard he turns away from), not a
        // stuck sensor. Treating it as frozen left him in eyes-only at the edge for good.
        boolean edgeReading = r.tof == tuning.tofFault && irEdgeFlagged(r);
        if (!edgeReading && tuning.frozenTofWindowMs > 0
                && r.timestampMs - tofSinceMs >= tuning.frozenTofWindowMs) {
            return "tof frozen at " + r.tof + " for " + (r.timestampMs - tofSinceMs) + " ms";
        }
        return null;
    }
}
