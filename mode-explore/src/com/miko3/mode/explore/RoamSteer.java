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
 * Plain Java (no Android or shared-driver imports), so it runs on the host JVM.
 * Its only state is how many turn-only plans came in a row. Not thread-safe; the
 * brain calls it from its one thread.
 */
final class RoamSteer {
    /** Sides, as signs of the heading change (Heading.LEFT / Heading.RIGHT); 0 is straight on. */
    static final int LEFT = Heading.LEFT;
    static final int RIGHT = Heading.RIGHT;
    static final int STRAIGHT = 0;

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

        Plan(int side, double bendDeg, float open, boolean turnOnly, boolean shortLeg, float lengthFactor) {
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
                            ", leg x%.2f", lengthFactor));
        }
    }

    private final ExploreTuning tuning;
    private int turnsOnly;

    RoamSteer(ExploreTuning tuning) {
        this.tuning = tuning;
    }

    /** Whether this profile is trusted at all. */
    boolean confident(Openness.Profile p) {
        return p != null && p.bins != null && p.bins.length > 0 && p.confidence >= tuning.steerMinConfidence;
    }

    /** The next leg from p, or null when p is missing or not confident (choose as before). */
    Plan plan(Openness.Profile p) {
        if (!confident(p)) {
            return null;
        }
        int n = p.bins.length;
        int w = Math.min(tuning.steerBandBins, n);
        float ahead = aheadOpen(p);
        int best = -1;
        float bestScore = -1f;
        double bestOffset = 0;
        for (int i = 0; i + w <= n; i++) {
            float score = mean(p.bins, i, w);
            double offset = offset(i, w, n);
            // Ties go to the band nearest straight ahead.
            if (score > bestScore + 1e-6f || (Math.abs(score - bestScore) <= 1e-6f
                    && Math.abs(offset) < Math.abs(bestOffset))) {
                best = i;
                bestScore = score;
                bestOffset = offset;
            }
        }
        if (bestScore <= tuning.steerBlocked) {
            int side = bestOffset < 0 ? LEFT : bestOffset > 0 ? RIGHT : LEFT;
            if (turnsOnly < tuning.steerTurnsOnlyMax) {
                turnsOnly++;
                double deg = Math.max(tuning.steerBlockedTurnDeg, Math.abs(bestOffset) * tuning.cameraHalfFovDeg);
                return new Plan(side, deg, bestScore, true, false, 0f);
            }
            // Turned enough with nothing open in view: a short leg, guarded by the floor sensor.
            turnsOnly = 0;
            return new Plan(STRAIGHT, 0, ahead, false, true, 0f);
        }
        turnsOnly = 0;
        int side = STRAIGHT;
        double deg = 0;
        float open = ahead;
        if (bestScore - ahead >= tuning.steerMinGain) {
            deg = Math.abs(bestOffset) * tuning.cameraHalfFovDeg;
            if (deg >= tuning.turnToleranceDeg) {
                side = bestOffset < 0 ? LEFT : RIGHT;
                open = bestScore;
            } else {
                deg = 0;
            }
        }
        if (open <= tuning.steerBlocked) {
            return new Plan(side, deg, open, false, true, 0f);
        }
        float f = (open - tuning.steerBlocked) / Math.max(1e-6f, tuning.steerOpen - tuning.steerBlocked);
        return new Plan(side, deg, open, false, false, Math.max(0f, Math.min(1f, f)));
    }

    /** The leg's forward ticks after this plan, given the leg length drawn as before (0: turn only). */
    int legTicks(Plan plan, int drawnTicks) {
        if (plan.turnOnly) {
            return 0;
        }
        int shortTicks = Math.min(tuning.steerShortTicks, drawnTicks);
        if (plan.shortLeg) {
            return Math.max(1, shortTicks);
        }
        return Math.max(Math.max(1, shortTicks), Math.round(shortTicks + plan.lengthFactor * (drawnTicks - shortTicks)));
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
