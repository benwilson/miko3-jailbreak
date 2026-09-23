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
 * Once available, a hazard is a calibrated threshold crossed or CPL=2 (the
 * controller refused a forward command, R9) in the latest reading.
 *
 * During an approach the brain asks approach() instead (KTD4): the same ir flag
 * and CPL=2 refusal fire both for something close and for a drop-off, and the
 * direction tof left the controller's safe band tells them apart.
 */
final class HazardClassifier {
    enum Status { UNAVAILABLE, HAZARD, CLEAR }

    enum Kind { EDGE, OBSTACLE, CPL }

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

    HazardClassifier(ExploreTuning tuning) {
        this.tuning = tuning;
        ExploreTuning.Calibration c = tuning.calibration;
        this.cal = c != null && c.complete() ? c : null;
        if (cal == null) {
            reason = c == null ? "uncalibrated" : "calibration incomplete";
        }
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
        if (r.cpl != null && r.cpl == 2) {
            return new Hazard(Kind.CPL, null);
        }
        return null;
    }

    /**
     * The approach-mode verdict at nowMs (KTD4). Unavailable exactly when status()
     * is. Otherwise, from the latest reading:
     *  - tof at its fault value, or above a calibrated edgeTofAbove: EDGE;
     *  - tof below the calibrated obstacleTofBelow: CLOSE;
     *  - an ir edge flag or CPL=2 with tof below the band's lower bound: CLOSE;
     *    above its upper bound: EDGE; inside the band: EDGE, the safe reading;
     *  - otherwise CLEAR.
     * A CLOSE with CPL=2 present is reported as CLOSE_REFUSED.
     */
    ApproachVerdict approach(long nowMs) {
        if (status(nowMs) == Status.UNAVAILABLE) {
            return ApproachVerdict.UNAVAILABLE;
        }
        SensorReading r = latest;
        boolean refused = r.cpl != null && r.cpl == 2;
        ApproachVerdict close = refused ? ApproachVerdict.CLOSE_REFUSED : ApproachVerdict.CLOSE;
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
            if (r.tof > tuning.approachBandUpper) {
                return ApproachVerdict.EDGE;
            }
            // Inside the band the flag says nothing about which way tof went.
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
