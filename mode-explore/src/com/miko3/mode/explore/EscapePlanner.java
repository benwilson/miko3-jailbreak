package com.miko3.mode.explore;

import java.util.ArrayList;
import java.util.List;

/**
 * The plan for getting out of a wedge (explore nav plan U5; R11-R14, KTD4-KTD6):
 * which step he is on, each step's time budget, and every heading the steps
 * choose. The brain wires it: it runs the motors, the camera and the Claude
 * request, and tells the planner how each step went.
 *
 *   RETRACE    back along the leg log, newest leg first: face each leg's reverse
 *              and drive it FORWARD (KTD6), up to escapeRetraceCounts in all. When
 *              he cannot turn, he first reverses straight along the most recent
 *              forward leg(s), capped at their logged distance: ground he just drove
 *              over. Done: freed. Nothing logged, a hazard or out of time: CIRCLE.
 *   CIRCLE     escapeCircleSteps stops, escapeCircleStepDeg apart, each with one
 *              fresh stationary look (openness, frame, heading). Then WAY_OUT.
 *   WAY_OUT    the circle's frames to Claude; the answer (frame and position) is a
 *              heading. Offline, out of time or no usable answer: the best on-robot
 *              openness heading, headings already tried this escape penalised.
 *   DRIVE_OFF  turn to that heading and drive off: freed, else SECOND_ASK.
 *   SECOND_ASK one more ask with the frame he sees now (never twice): its heading,
 *              SECOND_DRIVE_OFF; else REST.
 *   REST       the cornered rest, as before U5; one failed escape. Still pinned when
 *              it ends (the first move blocked too), the brain rests longer before the
 *              next ladder (ExploreTuning.pinnedWindowMs, pinnedMaxRestMs).
 *
 * A drive-off frees him only if the encoders moved at least the stall rate over
 * it: its ticks end before the stall watch can rule.
 *
 * Every step has a budget (ExploreTuning.escape*Ms, adding to ~30 s up to the
 * first drive-off); a step out of time has failed and the next starts, and a
 * hazard at any point moves on the same way instead of restarting.
 *
 * Plain Java, no android.* or shared imports; brain thread only.
 */
final class EscapePlanner {
    enum Phase { IDLE, RETRACE, CIRCLE, WAY_OUT, DRIVE_OFF, SECOND_ASK, SECOND_DRIVE_OFF, FREED, REST }

    /** One retrace move: face heading, drive counts forward. */
    static final class Move {
        final double heading;
        final long counts;

        Move(double heading, long counts) {
            this.heading = heading;
            this.counts = counts;
        }

        @Override
        public String toString() {
            return Math.round(heading) + " deg for " + counts + " counts";
        }
    }

    private final ExploreTuning tuning;
    private Phase phase = Phase.IDLE;
    private long startedAt;
    private long stepUntil;
    /** Counts driven back along the way in (the retrace and any back-out). */
    private long retraced;
    /** A back-out stalled: no more this escape. */
    private boolean backOutSpent;
    private final List<Double> tried = new ArrayList<Double>();
    private final List<Double> lookHeadings = new ArrayList<Double>();
    private final List<Openness.Profile> profiles = new ArrayList<Openness.Profile>();
    private final List<byte[]> jpegs = new ArrayList<byte[]>();
    private double driveHeading = Double.NaN;

    EscapePlanner(ExploreTuning tuning) {
        this.tuning = tuning;
    }

    /** Wedged now, facing `facing` (the way that is blocked: tried already). Starts at RETRACE. */
    void begin(long now, double facing) {
        reset();
        startedAt = now;
        tried.add(facing);
        to(Phase.RETRACE, now);
    }

    void reset() {
        phase = Phase.IDLE;
        retraced = 0;
        backOutSpent = false;
        tried.clear();
        lookHeadings.clear();
        profiles.clear();
        jpegs.clear();
        driveHeading = Double.NaN;
    }

    boolean active() {
        return phase != Phase.IDLE;
    }

    Phase phase() {
        return phase;
    }

    long startedAt() {
        return startedAt;
    }

    /** When the current step's budget runs out. */
    long stepUntil() {
        return stepUntil;
    }

    /** The current step failed (a hazard, out of time, nothing to do): the next one. */
    void next(long now) {
        switch (phase) {
            case RETRACE:
                to(Phase.CIRCLE, now);
                break;
            case CIRCLE:
                to(Phase.WAY_OUT, now);
                break;
            case WAY_OUT:
            case DRIVE_OFF:
                to(Phase.SECOND_ASK, now);
                break;
            default:
                to(Phase.REST, now);
                break;
        }
    }

    /** A way out was chosen (by Claude or on the robot): turn to it and drive off. */
    void driveOff(long now, double heading) {
        driveHeading = heading;
        tried(heading);
        to(phase == Phase.SECOND_ASK ? Phase.SECOND_DRIVE_OFF : Phase.DRIVE_OFF, now);
    }

    double driveHeading() {
        return driveHeading;
    }

    void freed() {
        phase = Phase.FREED;
    }

    private void to(Phase p, long now) {
        phase = p;
        stepUntil = now + budget(p);
    }

    long budget(Phase p) {
        switch (p) {
            case RETRACE:
                return tuning.escapeRetraceMs;
            case CIRCLE:
                return tuning.escapeCircleMs;
            case WAY_OUT:
                return tuning.escapeAskMs;
            case DRIVE_OFF:
            case SECOND_DRIVE_OFF:
                return tuning.escapeDriveOffMs;
            case SECOND_ASK:
                return tuning.escapeSecondAskMs;
            default:
                return 0;
        }
    }

    // ---- the retrace (KTD6) ----

    long retraced() {
        return retraced;
    }

    void addRetraced(long counts) {
        retraced += Math.max(0, counts);
    }

    void tried(double heading) {
        tried.add(Heading.wrap(heading));
    }

    /**
     * The next retrace move from the leg log as it is now (the retrace's own legs
     * included, so what is already driven back cancels), capped at what is left of
     * escapeRetraceCounts; null when nothing is left.
     */
    Move nextRetraceMove(List<Heading.Leg> legs) {
        long left = tuning.escapeRetraceCounts - retraced;
        if (left < tuning.escapeRetraceMinCounts) {
            return null;
        }
        List<Move> path = retracePath(legs);
        if (path.isEmpty()) {
            return null;
        }
        Move m = path.get(0);
        return new Move(m.heading, Math.min(m.counts, left));
    }

    /**
     * The way back, in the order to drive it: each leg newest first, reversed. A leg
     * next to one in the opposite direction (a leg and its back-off, or a leg and
     * the retrace that already drove it back) cancels as far as they overlap, like
     * brackets. Moves under escapeRetraceMinCounts are slack and dropped.
     */
    List<Move> retracePath(List<Heading.Leg> legs) {
        List<Move> stack = new ArrayList<Move>();
        for (int i = legs.size() - 1; i >= 0; i--) {
            Heading.Leg l = legs.get(i);
            Move cur = new Move(Heading.wrap(l.heading + 180), l.counts);
            while (cur != null && !stack.isEmpty() && opposite(stack.get(stack.size() - 1).heading, cur.heading)) {
                Move top = stack.remove(stack.size() - 1);
                long net = top.counts - cur.counts;
                cur = net > 0 ? new Move(top.heading, net) : net < 0 ? new Move(cur.heading, -net) : null;
            }
            if (cur != null) {
                stack.add(cur);
            }
        }
        List<Move> out = new ArrayList<Move>();
        for (Move m : stack) {
            if (m.counts >= tuning.escapeRetraceMinCounts) {
                out.add(m);
            }
        }
        return out;
    }

    /**
     * How far he may reverse straight back, facing `facing`, when he cannot turn:
     * the recent legs along his line, newest first (forward ones add, back-offs along
     * it subtract), stopping at the first leg off the line; capped at what is left of
     * the retrace distance. 0 when the log has no such leg or a back-out stalled.
     */
    long backOutCounts(List<Heading.Leg> legs, double facing) {
        if (backOutSpent) {
            return 0;
        }
        long avail = 0;
        boolean forward = false;
        for (int i = legs.size() - 1; i >= 0; i--) {
            Heading.Leg l = legs.get(i);
            double d = Math.abs(Heading.delta(facing, l.heading));
            if (d <= tuning.escapeLineToleranceDeg) {
                avail += l.counts;
                forward = true;
            } else if (d >= 180 - tuning.escapeLineToleranceDeg) {
                avail -= l.counts;
            } else {
                break;
            }
        }
        long left = tuning.escapeRetraceCounts - retraced;
        long counts = forward ? Math.min(avail, left) : 0;
        return counts >= tuning.escapeRetraceMinCounts ? counts : 0;
    }

    /** A back-out stalled: the wheels can't move him back either. */
    void backOutStalled() {
        backOutSpent = true;
    }

    private boolean opposite(double a, double b) {
        return Math.abs(Heading.delta(a, b)) >= 180 - tuning.escapeLineToleranceDeg;
    }

    // ---- the circle ----

    void addLook(double heading, Openness.Profile openness, byte[] jpeg) {
        lookHeadings.add(heading);
        profiles.add(openness);
        jpegs.add(jpeg);
    }

    int looks() {
        return lookHeadings.size();
    }

    boolean circleDone() {
        return looks() >= tuning.escapeCircleSteps;
    }

    /** The circle's frames for the way-out ask, in order; Frame.look is the look's index. */
    List<CuriosityPort.Frame> frames() {
        List<CuriosityPort.Frame> out = new ArrayList<CuriosityPort.Frame>();
        for (int i = 0; i < jpegs.size(); i++) {
            if (jpegs.get(i) != null) {
                out.add(new CuriosityPort.Frame(i, jpegs.get(i)));
            }
        }
        return out;
    }

    double lookHeading(int look) {
        return lookHeadings.get(look);
    }

    /** The heading of a point x across a frame (-1 left .. 1 right) taken at lookHeading (left positive). */
    static double aim(double lookHeading, float x, double halfFovDeg) {
        return Heading.wrap(lookHeading - x * halfFovDeg);
    }

    /**
     * The on-robot way out: the most open band of columns across the circle's
     * confident looks (as RoamSteer reads a profile), less escapeTriedPenalty within
     * escapeTriedDeg of a heading tried this escape; ties go to the band nearest its
     * look's centre, then the earlier look. With no confident look, the look (or,
     * with no looks at all, the circle step) farthest from everything tried.
     */
    double bestOpenHeading(double facing) {
        double best = Double.NaN;
        double bestScore = -Double.MAX_VALUE;
        double bestOffset = 0;
        for (int k = 0; k < profiles.size(); k++) {
            Openness.Profile p = profiles.get(k);
            if (p == null || p.bins == null || p.bins.length == 0 || p.confidence < tuning.steerMinConfidence) {
                continue;
            }
            int n = p.bins.length;
            int w = Math.min(tuning.steerBandBins, n);
            for (int i = 0; i + w <= n; i++) {
                double offset = (i + w / 2.0) / n * 2.0 - 1.0;
                double h = aim(lookHeadings.get(k), (float) offset, tuning.cameraHalfFovDeg);
                double score = mean(p.bins, i, w) - (nearTried(h) ? tuning.escapeTriedPenalty : 0);
                if (score > bestScore + 1e-6 || (Math.abs(score - bestScore) <= 1e-6
                        && Math.abs(offset) < Math.abs(bestOffset))) {
                    best = h;
                    bestScore = score;
                    bestOffset = offset;
                }
            }
        }
        if (!Double.isNaN(best)) {
            return best;
        }
        List<Double> candidates = new ArrayList<Double>(lookHeadings);
        if (candidates.isEmpty()) {
            for (int k = 1; k < Math.max(2, tuning.escapeCircleSteps); k++) {
                candidates.add(Heading.wrap(facing + k * 360.0 / Math.max(2, tuning.escapeCircleSteps)));
            }
        }
        double far = -1;
        for (double c : candidates) {
            double d = 180;
            for (double t : tried) {
                d = Math.min(d, Math.abs(Heading.delta(c, t)));
            }
            if (d > far + 1e-6) {
                far = d;
                best = c;
            }
        }
        return best;
    }

    private boolean nearTried(double h) {
        for (double t : tried) {
            if (Math.abs(Heading.delta(h, t)) <= tuning.escapeTriedDeg) {
                return true;
            }
        }
        return false;
    }

    private static double mean(float[] bins, int start, int w) {
        double s = 0;
        for (int i = start; i < start + w; i++) {
            s += Math.max(0f, Math.min(1f, bins[i]));
        }
        return s / w;
    }
}
