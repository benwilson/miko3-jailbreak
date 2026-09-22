package com.miko3.mode.explore;

/**
 * Every number the wander brain runs on (KTD3, KTD5, KTD8, KTD9, KTD10), in one
 * immutable place. Plain Java, so the host harness builds its own variants.
 *
 * The defaults start conservative and are tuned on the robot in U8: forward
 * speed is fixed (KTD5), so the robot's pace comes only from hop length and
 * pause length (R1), and the blind back-off is a single tick (R4).
 *
 * calibration is null until the U8 QA script has written thresholds from live
 * readings. With no calibration the classifier reports the sensors unavailable,
 * so a fresh install runs eyes-only and never drives on guessed thresholds (KTD9).
 */
final class ExploreTuning {
    /** Forward ticks per hop; each tick is one driveContinuous(+2,0) resend. */
    final int hopTicks;
    /** Gap between hop ticks: the forward recipe must be resent every 250 ms. */
    final long hopTickMs;
    /** Reverse ticks per hazard back-off (R4: short and fixed). */
    final int backTicks;
    final long backTickMs;
    /** Look-around pause length, drawn uniformly from [pauseMinMs, pauseMaxMs] (R2). */
    final long pauseMinMs;
    final long pauseMaxMs;
    /** Chance that a pause ends in a turn rather than a hop on the same heading. */
    final double turnChance;
    /** Discretionary turn length, drawn from [turnMinMs, turnMaxMs]; short ones read as a glance around (R2). */
    final long turnMinMs;
    final long turnMaxMs;
    /** Turn away from a hazard. There is no heading feedback, so a turn is only ever a duration (KTD5). */
    final long escapeTurnMs;
    /** The larger turn tried after a cornered cool-down (KTD8). */
    final long corneredTurnMs;
    /** How long the eyes look toward a new heading before the turn starts (KTD10, R7). */
    final long lookLeadMs;
    /** How long the startle holds still, sound and flinch, before the back-off (R11). */
    final long startleMs;
    /** No reading for this long means the sensors are unavailable: three missed 100 ms polls (KTD3). */
    final long staleMs;
    /** tof's fault value (KTD3). */
    final int tofFault;
    /** tof unchanged for this long means frozen (leftover TOFDS); 0 turns the rule off (KTD3). */
    final long frozenTofWindowMs;
    /** Good readings in a row needed to go from unavailable back to available (KTD3). */
    final int recoveryStreak;
    /** Cornered cap: capHazards reactions within capWindowMs, no successful hop between, rest for cooldownMs (KTD8). */
    final int capHazards;
    final long capWindowMs;
    final long cooldownMs;
    /** Whether ir1 is the left-hand edge sensor. Unverified until U1/U8; only steers which way to turn away. */
    final boolean ir1IsLeft;
    /** Edge and obstacle thresholds from the device, or null when uncalibrated (KTD9). */
    final Calibration calibration;

    private ExploreTuning(Builder b) {
        hopTicks = b.hopTicks;
        hopTickMs = b.hopTickMs;
        backTicks = b.backTicks;
        backTickMs = b.backTickMs;
        pauseMinMs = b.pauseMinMs;
        pauseMaxMs = Math.max(b.pauseMinMs, b.pauseMaxMs);
        turnChance = b.turnChance;
        turnMinMs = b.turnMinMs;
        turnMaxMs = Math.max(b.turnMinMs, b.turnMaxMs);
        escapeTurnMs = b.escapeTurnMs;
        corneredTurnMs = b.corneredTurnMs;
        lookLeadMs = b.lookLeadMs;
        startleMs = b.startleMs;
        staleMs = b.staleMs;
        tofFault = b.tofFault;
        frozenTofWindowMs = b.frozenTofWindowMs;
        recoveryStreak = Math.max(1, b.recoveryStreak);
        capHazards = Math.max(1, b.capHazards);
        capWindowMs = b.capWindowMs;
        cooldownMs = b.cooldownMs;
        ir1IsLeft = b.ir1IsLeft;
        calibration = b.calibration;
    }

    /** The shipped defaults with the given calibration (null = uncalibrated). */
    static ExploreTuning defaults(Calibration calibration) {
        return new Builder().calibration(calibration).build();
    }

    /**
     * The thresholds that turn raw readings into hazards. Which direction means
     * "edge" is not settled by research (KTD2), so each rule carries its own
     * sense, and a negative threshold switches that rule off.
     */
    static final class Calibration {
        /** tof below this is an obstacle ahead; negative = no obstacle rule. */
        final int obstacleTofBelow;
        /** tof above this is an edge ahead (a front sensor seeing past the desk); negative = off. */
        final int edgeTofAbove;
        /** ir1 or ir2 past this is an edge on that side; negative = off. */
        final int edgeIr;
        /** true: ir above edgeIr is an edge; false: below it is. */
        final boolean edgeIrAbove;

        Calibration(int obstacleTofBelow, int edgeTofAbove, int edgeIr, boolean edgeIrAbove) {
            this.obstacleTofBelow = obstacleTofBelow;
            this.edgeTofAbove = edgeTofAbove;
            this.edgeIr = edgeIr;
            this.edgeIrAbove = edgeIrAbove;
        }

        /** Driving needs both an obstacle rule and at least one edge rule. */
        boolean complete() {
            return obstacleTofBelow >= 0 && (edgeTofAbove >= 0 || edgeIr >= 0);
        }

        @Override
        public String toString() {
            return "obstacleTofBelow=" + obstacleTofBelow + " edgeTofAbove=" + edgeTofAbove
                    + " edgeIr" + (edgeIrAbove ? ">" : "<") + edgeIr;
        }
    }

    /** Starts from the shipped defaults; every setter returns the builder. */
    static final class Builder {
        private int hopTicks = 2;
        private long hopTickMs = 250;
        private int backTicks = 1;
        private long backTickMs = 250;
        private long pauseMinMs = 1500;
        private long pauseMaxMs = 4000;
        private double turnChance = 0.4;
        private long turnMinMs = 300;
        private long turnMaxMs = 900;
        private long escapeTurnMs = 1200;
        private long corneredTurnMs = 2400;
        private long lookLeadMs = 500;
        private long startleMs = 400;
        private long staleMs = 300;
        private int tofFault = 16383;
        // Placeholder until U1's passive baseline measures tof jitter (KTD3).
        private long frozenTofWindowMs = 3000;
        private int recoveryStreak = 3;
        private int capHazards = 3;
        private long capWindowMs = 20000;
        private long cooldownMs = 30000;
        private boolean ir1IsLeft = true;
        private Calibration calibration;

        Builder hopTicks(int v) { hopTicks = v; return this; }
        Builder hopTickMs(long v) { hopTickMs = v; return this; }
        Builder backTicks(int v) { backTicks = v; return this; }
        Builder backTickMs(long v) { backTickMs = v; return this; }
        Builder pauseMs(long min, long max) { pauseMinMs = min; pauseMaxMs = max; return this; }
        Builder turnChance(double v) { turnChance = v; return this; }
        Builder turnMs(long min, long max) { turnMinMs = min; turnMaxMs = max; return this; }
        Builder escapeTurnMs(long v) { escapeTurnMs = v; return this; }
        Builder corneredTurnMs(long v) { corneredTurnMs = v; return this; }
        Builder lookLeadMs(long v) { lookLeadMs = v; return this; }
        Builder startleMs(long v) { startleMs = v; return this; }
        Builder staleMs(long v) { staleMs = v; return this; }
        Builder tofFault(int v) { tofFault = v; return this; }
        Builder frozenTofWindowMs(long v) { frozenTofWindowMs = v; return this; }
        Builder recoveryStreak(int v) { recoveryStreak = v; return this; }
        Builder cap(int hazards, long windowMs, long cooldown) {
            capHazards = hazards;
            capWindowMs = windowMs;
            cooldownMs = cooldown;
            return this;
        }
        Builder ir1IsLeft(boolean v) { ir1IsLeft = v; return this; }
        Builder calibration(Calibration v) { calibration = v; return this; }

        ExploreTuning build() {
            return new ExploreTuning(this);
        }
    }
}
