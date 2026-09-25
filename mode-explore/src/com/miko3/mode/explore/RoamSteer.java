package com.miko3.mode.explore;

/**
 * The roaming steer (explore nav plan U4, KTD9): from the newest look's openness
 * profile it picks the next leg's bend toward the most open band of columns and
 * shortens the leg when the way it will drive reads blocked; during a leg it says
 * whether the columns straight ahead read blocked, which ends the leg early.
 *
 * It only slows and bends him: the floor sensor, stall sensing and CPL=2 still
 * decide every stop near his nose (KTD9), and the brain starts every motion on a
 * fresh reading as before. A profile below steerMinConfidence (no floor taught,
 * too dark) gives no plan, and the brain chooses the leg as it did before.
 *
 * The image's left is the robot's left: bin 0 is the frame's left edge, and a
 * band's offset from the centre, times cameraHalfFovDeg, is the bend.
 *
 * A remembered open doorway (explore nav plan U6), given as its bearing from where
 * he faces, weights the choice: in view, the open bands nearest it gain up to
 * doorwayWeight (a blocked band never gains); out of view, the plan is a turn to
 * face it and look again.
 *
 * Going somewhere new (explore nav plan U10, R18): given a Novelty (how little a
 * heading leads back over ground Coverage has seen him cover), each open band
 * gains up to coverageWeight x its novelty (a blocked band never gains), a fully
 * open leg grows toward coverageLongTicksMax with its novelty, a turn from a
 * blocked view goes the more novel way, and a view that is all visited ground
 * turns him (once) to face a clearly newer heading out of view. Without one
 * (no usable heading, or coverageWeight 0) it plans exactly as before.
 *
 * Plain Java (no Android or shared-driver imports), so it runs on the host JVM.
 * Its only state is how many turn-only plans came in a row, and whether the
 * last plan was a turn toward new ground. Not thread-safe; the
 * brain calls it from its one thread.
 */
final class RoamSteer {
    /** Sides, as signs of the heading change (Heading.LEFT / Heading.RIGHT); 0 is straight on. */
    static final int LEFT = Heading.LEFT;
    static final int RIGHT = Heading.RIGHT;
    static final int STRAIGHT = 0;

    /** How new the ground is along a heading bearingDeg off his facing (left positive): 0..1, NaN unknown. */
    interface Novelty {
        double at(double bearingDeg);
    }

    /** The out-of-view headings a turn toward new ground may face, off his facing. */
    private static final double[] AWAY_DEG = {60, -60, 120, -120, 180};

    /** The next leg: a bend (side and degrees; STRAIGHT and 0 for none) and how long to drive after it. */
    static final class Plan {
        final int side;
        final double bendDeg;
        /** Openness of the band the leg will drive toward, 0..1. */
        final float open;
        /** Turn only, no leg: nothing in view is open. */
        final boolean turnOnly;
        /** A leg of steerShortTicks at most: the way reads blocked. */
        final boolean shortLeg;
        /** Share of the drawn leg to drive (1 = all of it), when neither of the above. */
        final float lengthFactor;
        /** The leg (or the turn) heads for the remembered doorway. */
        final boolean towardDoorway;
        /** The novelty of the way the leg (or the turn) faces, 0..1; NaN: not scored. */
        double novelty = Double.NaN;

        Plan(int side, double bendDeg, float open, boolean turnOnly, boolean shortLeg, float lengthFactor) {
            this(side, bendDeg, open, turnOnly, shortLeg, lengthFactor, false);
        }

        Plan(int side, double bendDeg, float open, boolean turnOnly, boolean shortLeg, float lengthFactor,
             boolean towardDoorway) {
            this.towardDoorway = towardDoorway;
            this.side = side;
            this.bendDeg = bendDeg;
            this.open = open;
            this.turnOnly = turnOnly;
            this.shortLeg = shortLeg;
            this.lengthFactor = lengthFactor;
        }

        /** Numbers and labels only, for the trace. */
        @Override
        public String toString() {
            String s = side == LEFT ? "left" : side == RIGHT ? "right" : "straight";
            return String.format(java.util.Locale.US, "%s %.0f deg, open %.2f%s", s, bendDeg, open,
                    turnOnly ? ", turn only" : shortLeg ? ", short leg" : String.format(java.util.Locale.US,
                            ", leg x%.2f", lengthFactor)) + (towardDoorway ? ", toward the doorway" : "")
                    + (Double.isNaN(novelty) ? "" : String.format(java.util.Locale.US, ", new %.2f", novelty));
        }
    }

    private final ExploreTuning tuning;
    private int turnsOnly;
    private boolean turnedToNew;

    RoamSteer(ExploreTuning tuning) {
        this.tuning = tuning;
    }

    /** Whether this profile is trusted at all. */
    boolean confident(Openness.Profile p) {
        return p != null && p.bins != null && p.bins.length > 0 && p.confidence >= tuning.steerMinConfidence;
    }

    /** The next leg from p, or null when p is missing or not confident (choose as before). */
    Plan plan(Openness.Profile p) {
        return plan(p, Double.NaN);
    }

    /**
     * The next leg from p, weighted toward a remembered doorway doorwayDeg off his
     * facing (left positive; NaN: none), or null when p is missing or not confident.
     */
    Plan plan(Openness.Profile p, double doorwayDeg) {
        return plan(p, doorwayDeg, null);
    }

    /**
     * As above, also weighted toward new ground by novelty (null, or coverageWeight
     * 0: as before).
     */
    Plan plan(Openness.Profile p, double doorwayDeg, Novelty novelty) {
        return choose(p, doorwayDeg, tuning.coverageWeight > 0 ? novelty : null);
    }

    private Plan choose(Openness.Profile p, double doorwayDeg, Novelty nov) {
        if (!confident(p)) {
            return null;
        }
        boolean justTurnedToNew = turnedToNew;
        turnedToNew = false;
        int n = p.bins.length;
        int w = Math.min(tuning.steerBandBins, n);
        float ahead = aheadOpen(p);
        boolean door = !Double.isNaN(doorwayDeg);
        if (door && Math.abs(doorwayDeg) > tuning.cameraHalfFovDeg) {
            // Out of view: face it, then look again (the next look must read it open).
            return new Plan(doorwayDeg > 0 ? LEFT : RIGHT, Math.abs(doorwayDeg), ahead, true, false, 0f, true);
        }
        double doorX = door ? -doorwayDeg / tuning.cameraHalfFovDeg : 0;
        int best = -1;
        float bestScore = -1f;
        float bestWeighted = -1f;
        double bestOffset = 0;
        for (int i = 0; i + w <= n; i++) {
            float score = mean(p.bins, i, w);
            double offset = offset(i, w, n);
            float weighted = score + (door ? doorwayBonus(score, offset, doorX, w, n) : 0f)
                    + noveltyBonus(nov, score, offset);
            // Ties go to the band nearest straight ahead.
            if (weighted > bestWeighted + 1e-6f || (Math.abs(weighted - bestWeighted) <= 1e-6f
                    && Math.abs(offset) < Math.abs(bestOffset))) {
                best = i;
                bestScore = score;
                bestWeighted = weighted;
                bestOffset = offset;
            }
        }
        if (bestScore <= tuning.steerBlocked) {
            int side = bestOffset < 0 ? LEFT : bestOffset > 0 ? RIGHT : LEFT;
            if (turnsOnly < tuning.steerTurnsOnlyMax) {
                turnsOnly++;
                double deg = Math.max(tuning.steerBlockedTurnDeg, Math.abs(bestOffset) * tuning.cameraHalfFovDeg);
                double turnNew = novelty(nov, side == LEFT ? deg : -deg);
                if (!Double.isNaN(turnNew)) {
                    // Nothing open in view: of the turns at least this big, the one facing
                    // clearly newer ground (the look after it says whether it is open).
                    for (double a : AWAY_DEG) {
                        double v = novelty(nov, a);
                        if (Math.abs(a) >= deg && v >= turnNew + tuning.coverageMinGain) {
                            side = a > 0 ? LEFT : RIGHT;
                            deg = Math.abs(a);
                            turnNew = v;
                        }
                    }
                }
                Plan plan = new Plan(side, deg, bestScore, true, false, 0f);
                plan.novelty = turnNew;
                return plan;
            }
            // Turned enough with nothing open in view: a short leg, guarded by the floor sensor.
            turnsOnly = 0;
            Plan plan = new Plan(STRAIGHT, 0, ahead, false, true, 0f);
            plan.novelty = novelty(nov, 0);
            return plan;
        }
        turnsOnly = 0;
        int side = STRAIGHT;
        double deg = 0;
        float open = ahead;
        double aheadOffset = offset((n - w) / 2, w, n);
        float aheadWeighted = ahead + (door ? doorwayBonus(ahead, aheadOffset, doorX, w, n) : 0f)
                + noveltyBonus(nov, ahead, aheadOffset);
        double goOffset = aheadOffset;
        if (bestWeighted - aheadWeighted >= tuning.steerMinGain) {
            deg = Math.abs(bestOffset) * tuning.cameraHalfFovDeg;
            if (deg >= tuning.turnToleranceDeg) {
                side = bestOffset < 0 ? LEFT : RIGHT;
                open = bestScore;
                goOffset = bestOffset;
            } else {
                deg = 0;
            }
        }
        double goNew = side == STRAIGHT ? novelty(nov, 0) : novelty(nov, bearing(goOffset));
        if (!door && !justTurnedToNew && !Double.isNaN(goNew) && goNew <= tuning.coverageStaleNovelty) {
            // All he can see open is ground he just covered: face clearly newer ground, then look again.
            double awayDeg = 0;
            double awayNew = -1;
            for (double a : AWAY_DEG) {
                double v = novelty(nov, a);
                if (!Double.isNaN(v) && v > awayNew) {
                    awayNew = v;
                    awayDeg = a;
                }
            }
            if (awayNew >= goNew + tuning.coverageMinGain) {
                turnedToNew = true;
                Plan plan = new Plan(awayDeg > 0 ? LEFT : RIGHT, Math.abs(awayDeg), ahead, true, false, 0f);
                plan.novelty = awayNew;
                return plan;
            }
        }
        boolean toward = door && open > tuning.steerBlocked && Math.abs(goOffset - doorX) < 2.0 * w / n;
        if (open <= tuning.steerBlocked) {
            Plan plan = new Plan(side, deg, open, false, true, 0f);
            plan.novelty = goNew;
            return plan;
        }
        float f = (open - tuning.steerBlocked) / Math.max(1e-6f, tuning.steerOpen - tuning.steerBlocked);
        Plan plan = new Plan(side, deg, open, false, false, Math.max(0f, Math.min(1f, f)), toward);
        plan.novelty = goNew;
        return plan;
    }

    /** A band's pull toward new ground: coverageWeight x its novelty, none on a blocked band. */
    private float noveltyBonus(Novelty nov, float score, double offset) {
        if (nov == null || score <= tuning.steerBlocked) {
            return 0f;
        }
        double v = novelty(nov, bearing(offset));
        return Double.isNaN(v) ? 0f : (float) (tuning.coverageWeight * v);
    }

    /** nov's novelty bearingDeg off his facing, clamped to 0..1; NaN without one. */
    private static double novelty(Novelty nov, double bearingDeg) {
        if (nov == null) {
            return Double.NaN;
        }
        double v = nov.at(bearingDeg);
        return Double.isNaN(v) ? v : Math.max(0, Math.min(1, v));
    }

    /** A band offset (-1 frame left .. 1 frame right) as a bearing off his facing, left positive. */
    private double bearing(double offset) {
        return -offset * tuning.cameraHalfFovDeg;
    }

    /**
     * Facing within doorwayFacingDeg of a remembered doorway (doorwayDeg off his
     * facing), a confident look reads the band there blocked: it is closed now.
     */
    boolean doorwayReadsBlocked(Openness.Profile p, double doorwayDeg) {
        if (!confident(p) || Double.isNaN(doorwayDeg) || Math.abs(doorwayDeg) > tuning.doorwayFacingDeg) {
            return false;
        }
        int n = p.bins.length;
        int w = Math.min(tuning.steerBandBins, n);
        double x = -doorwayDeg / tuning.cameraHalfFovDeg;
        int start = (int) Math.round((x + 1) / 2 * n - w / 2.0);
        start = Math.max(0, Math.min(n - w, start));
        return mean(p.bins, start, w) <= tuning.steerBlocked;
    }

    /** The doorway's pull on a band: full at its column, none a band's width away or on a blocked band. */
    private float doorwayBonus(float score, double offset, double doorX, int w, int n) {
        if (score <= tuning.steerBlocked) {
            return 0f;
        }
        double span = 2.0 * w / n;
        return (float) (tuning.doorwayWeight * Math.max(0, 1 - Math.abs(offset - doorX) / span));
    }

    /** The leg's forward ticks after this plan, given the leg length drawn as before (0: turn only);
     * longer on open new ground (U10). */
    int legTicks(Plan plan, int drawnTicks) {
        if (plan.turnOnly) {
            return 0;
        }
        int shortTicks = Math.min(tuning.steerShortTicks, drawnTicks);
        if (plan.shortLeg) {
            return Math.max(1, shortTicks);
        }
        int ticks = Math.max(Math.max(1, shortTicks),
                Math.round(shortTicks + plan.lengthFactor * (drawnTicks - shortTicks)));
        if (plan.lengthFactor >= 1f && !Double.isNaN(plan.novelty) && tuning.coverageLongTicksMax > ticks) {
            // Open and new ground ahead (U10): drive on, up to the cap, in proportion to how new.
            ticks += (int) Math.round(plan.novelty * (tuning.coverageLongTicksMax - ticks));
        }
        return ticks;
    }

    /** During a leg: the columns straight ahead read blocked (a confident profile only). */
    boolean blockedAhead(Openness.Profile p) {
        return confident(p) && aheadOpen(p) <= tuning.steerBlocked;
    }

    /** The mean openness of the band centred straight ahead. */
    float aheadOpen(Openness.Profile p) {
        int n = p.bins.length;
        int w = Math.min(tuning.steerBandBins, n);
        return mean(p.bins, (n - w) / 2, w);
    }

    /** A band's centre, -1 (frame left) .. 1 (frame right). */
    private static double offset(int start, int w, int n) {
        return (start + w / 2.0) / n * 2.0 - 1.0;
    }

    private static float mean(float[] bins, int start, int w) {
        float s = 0;
        for (int i = start; i < start + w; i++) {
            s += Math.max(0f, Math.min(1f, bins[i]));
        }
        return s / w;
    }
}
