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
    /** Forward ticks per leg of driving, at least; each tick is one driveContinuous(+2,0)
     * resend, so consecutive ticks keep the robot rolling without stopping. */
    final int hopTicks;
    /** Forward ticks per leg, at most: each leg drives a random length in between. */
    final int hopTicksMax;
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
    /**
     * Escaping (owner request 2026-09-24, docs/TODO.md): an escape turn keeps going
     * until the way ahead reads clear for escapeClearMs; no clear way within
     * escapeSweepMaxMs (about a full circle) is a failed escape, and
     * escapeFailuresMax failures within escapeFailWindowMs make him rest.
     */
    final long escapeClearMs;
    final long escapeSweepMaxMs;
    final int escapeFailuresMax;
    final long escapeFailWindowMs;
    /**
     * Stall (blocked by something too low for the front sensor): during a leg,
     * after stallGraceMs, fewer than stallMinCounts encoder counts (both wheels)
     * in the last stallWindowMs. On the robot a blocked leg moved 0 counts; a
     * short free burst moved ~10 counts per 125 ms reply.
     */
    final long stallGraceMs;
    final long stallWindowMs;
    final long stallMinCounts;
    /**
     * After a stall: back off stallBackTicks, then turn at least stallTurnMs, and
     * stallTurnStepMs longer for each further stall before a clean leg. The front
     * sensor can't see what stalled him, so these set how far he gets from it.
     */
    final int stallBackTicks;
    final long stallTurnMs;
    final long stallTurnStepMs;
    /** Cornered backstop: capHazards reactions within capWindowMs, no successful hop between, rest for cooldownMs (KTD8). */
    final int capHazards;
    final long capWindowMs;
    final long cooldownMs;
    /** Whether ir1 is the left-hand edge sensor. Unverified until U1/U8; only steers which way to turn away. */
    final boolean ir1IsLeft;
    /**
     * The lower bound of the controller's safe tof band (KTD4; its upper bound is
     * about 280). During an approach an ir flag or CPL=2 with tof below it means
     * something close ahead (arrival); at or above it, a drop-off is assumed.
     */
    final int approachBandLower;
    /**
     * Recognition thresholds (KTD6), tuned on the robot in U8: a detection at or
     * above confidenceFloor is a thing; the best between unsureFloor and it makes
     * a look unsure (R13). A box at least fillHeight of the frame's height or
     * fillArea of its area has arrived by the camera (R6).
     */
    final float confidenceFloor;
    final float unsureFloor;
    final float fillHeight;
    final float fillArea;
    /**
     * Camera curiosity (KTD5). A curiosity stop comes curiosityMin..MaxMs of
     * wandering after the last one ends, however it ends. A scan takes up to scanLooks looks with a
     * scanTurnMs step turn between them. A look counts only if its frame was
     * captured lookSettleMs after he stopped moving; no such look within
     * firstLookTimeoutMs of opening the camera (lookTimeoutMs later) is a camera
     * failure, which turns curiosity off for cameraBackoffMs (KTD8).
     */
    final long curiosityMinMs;
    final long curiosityMaxMs;
    final int scanLooks;
    final long scanTurnMs;
    final long lookSettleMs;
    final long firstLookTimeoutMs;
    final long lookTimeoutMs;
    final long cameraBackoffMs;
    /**
     * Facing and approaching (R5, R6). A target within centreTolerance of the
     * frame's centre (box centre, -1..1) is ahead; otherwise he turns toward it
     * for turnMsPerUnit x its offset, at most faceTurnsMax times. Each approach
     * leg is approachTicks forward ticks, at most approachLegsMax legs; sensor
     * arrival is ignored for the first approachGraceMs of a leg (KTD4). The
     * target missing from lostLooksMax looks in a row ends the approach (R7).
     */
    final float centreTolerance;
    final long turnMsPerUnit;
    final int faceTurnsMax;
    final int approachTicks;
    final int approachLegsMax;
    final long approachGraceMs;
    final int lostLooksMax;
    /** How long each reaction clip is given before the next (U6's clips run 0.7-1.4 s, names up to 2.5 s). */
    final long curiousMs;
    final long thinkingMs;
    final long nameMs;
    final long delightedMs;
    final long disappointedMs;
    final long puzzledMs;
    /** After greeting a person or pet, people and pets are ignored this long (R12). */
    final long peopleCooldownMs;
    /**
     * Asking Claude (explore on Claude KTD6): askAttempts tries of askTimeoutMs
     * each before falling back to the detector (R7, R8). A spoken line is given
     * up on after sayTimeoutMs if the finished callback never comes (KTD8). The
     * look request carries the last recentPicksMax picks (KTD2).
     */
    final int askAttempts;
    final long askTimeoutMs;
    final long sayTimeoutMs;
    /** A line waits at most this long for the camera and detector to go quiet (R6; a detector run is ~1 s). */
    final long quietWaitMs;
    final int recentPicksMax;
    /**
     * Meeting a person (explore on Claude U5, KTD3, KTD4): the match request and
     * each later Claude request get meetTimeoutMs; he listens for up to listenMs
     * and waits listenMarginMs more for the launcher's answer before giving up.
     */
    final long meetTimeoutMs;
    final long listenMs;
    final long listenMarginMs;
    /** Claude's box and a detector box are the same thing at this overlap (KTD7). */
    final float pickMatchIou;
    /**
     * The camera waits this long after closing before it opens again
     * (ExploreCamera.REOPEN_GAP_MS). Reopening it mid-stop for FACE counts the
     * rest of the gap toward the first look's deadline (KTD6).
     */
    final long reopenGapMs;
    /** Edge and obstacle thresholds from the device, or null when uncalibrated (KTD9). */
    final Calibration calibration;

    private ExploreTuning(Builder b) {
        hopTicks = b.hopTicks;
        hopTicksMax = Math.max(b.hopTicks, b.hopTicksMax);
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
        stallGraceMs = b.stallGraceMs;
        stallWindowMs = b.stallWindowMs;
        stallMinCounts = b.stallMinCounts;
        stallBackTicks = b.stallBackTicks;
        stallTurnMs = b.stallTurnMs;
        stallTurnStepMs = b.stallTurnStepMs;
        escapeClearMs = b.escapeClearMs;
        escapeSweepMaxMs = Math.max(b.escapeTurnMs, b.escapeSweepMaxMs);
        escapeFailuresMax = Math.max(1, b.escapeFailuresMax);
        escapeFailWindowMs = b.escapeFailWindowMs;
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
        approachBandLower = b.approachBandLower;
        confidenceFloor = b.confidenceFloor;
        unsureFloor = Math.min(b.unsureFloor, b.confidenceFloor);
        fillHeight = b.fillHeight;
        fillArea = b.fillArea;
        curiosityMinMs = b.curiosityMinMs;
        curiosityMaxMs = Math.max(b.curiosityMinMs, b.curiosityMaxMs);
        scanLooks = Math.max(1, b.scanLooks);
        scanTurnMs = b.scanTurnMs;
        lookSettleMs = b.lookSettleMs;
        firstLookTimeoutMs = b.firstLookTimeoutMs;
        lookTimeoutMs = b.lookTimeoutMs;
        cameraBackoffMs = b.cameraBackoffMs;
        centreTolerance = b.centreTolerance;
        turnMsPerUnit = b.turnMsPerUnit;
        faceTurnsMax = b.faceTurnsMax;
        approachTicks = Math.max(1, b.approachTicks);
        approachLegsMax = Math.max(1, b.approachLegsMax);
        approachGraceMs = b.approachGraceMs;
        lostLooksMax = Math.max(1, b.lostLooksMax);
        curiousMs = b.curiousMs;
        thinkingMs = b.thinkingMs;
        nameMs = b.nameMs;
        delightedMs = b.delightedMs;
        disappointedMs = b.disappointedMs;
        puzzledMs = b.puzzledMs;
        peopleCooldownMs = b.peopleCooldownMs;
        askAttempts = Math.max(1, b.askAttempts);
        askTimeoutMs = b.askTimeoutMs;
        sayTimeoutMs = b.sayTimeoutMs;
        quietWaitMs = Math.max(0, b.quietWaitMs);
        recentPicksMax = Math.max(0, b.recentPicksMax);
        meetTimeoutMs = b.meetTimeoutMs;
        listenMs = b.listenMs;
        listenMarginMs = b.listenMarginMs;
        pickMatchIou = b.pickMatchIou;
        reopenGapMs = b.reopenGapMs;
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
        // Continuous legs rather than hops: 4-10 s of driving (16-40 ticks of 250 ms),
        // then a short pause and usually a new heading. Fewer starts and stops also means
        // fewer nose bobs pushing the ToF out of the controller's band.
        private int hopTicks = 16;
        private int hopTicksMax = 40;
        private long hopTickMs = 250;
        private int backTicks = 1;
        private long backTickMs = 250;
        private long pauseMinMs = 300;
        private long pauseMaxMs = 700;
        private double turnChance = 0.7;
        private long turnMinMs = 300;
        private long turnMaxMs = 900;
        private long escapeTurnMs = 1200;
        private long corneredTurnMs = 2400;
        private long stallGraceMs = 1000;
        private long stallWindowMs = 1000;
        private long stallMinCounts = 10;
        private int stallBackTicks = 3;
        private long stallTurnMs = 2000;
        private long stallTurnStepMs = 1000;
        private long escapeClearMs = 300;
        // No heading feedback: 6 s of turning is assumed to be about a full circle.
        private long escapeSweepMaxMs = 6000;
        private int escapeFailuresMax = 3;
        private long escapeFailWindowMs = 60000;
        private long lookLeadMs = 500;
        private long startleMs = 400;
        private long staleMs = 300;
        private int tofFault = 16383;
        // A still robot's tof jitters by tens of counts, so identical values this
        // long mean a stuck sensor (docs/hardware/tof-sensor.md).
        private long frozenTofWindowMs = 3000;
        private int recoveryStreak = 3;
        // A backstop now that failed escapes decide cornering: hazards met right after
        // clean escapes (a cluttered corner) still end in a rest eventually.
        private int capHazards = 8;
        private long capWindowMs = 20000;
        private long cooldownMs = 30000;
        private boolean ir1IsLeft = true;
        // docs/solutions/best-practices/miko3-tof-is-a-downward-cliff-sensor-inside-mcu-safe-band.md
        private int approachBandLower = 170;
        // U1's plant frame: the pots scored 0.87 (as "vase"), the plants 0.34-0.43,
        // and a false "table" along the bottom edge 0.38.
        private float confidenceFloor = 0.35f;
        private float unsureFloor = 0.2f;
        private float fillHeight = 0.7f;
        private float fillArea = 0.4f;
        // 45-90 s of wandering between stops: the owner likes him driving around.
        private long curiosityMinMs = 45000;
        private long curiosityMaxMs = 90000;
        private int scanLooks = 3;
        private long scanTurnMs = 700;
        private long lookSettleMs = 400;
        // Up to ExploreCamera.REOPEN_GAP_MS (3 s) before the camera reopens, camera start,
        // then the recognizer's first run (~0.75 s load, ~1.4 s first frame, U1).
        private long firstLookTimeoutMs = 10000;
        // A look needs a frame taken after he stopped, and live looks take up to ~2.5 s
        // on the robot, so the first usable one can be ~5 s away.
        private long lookTimeoutMs = 8000;
        private long cameraBackoffMs = 120000;
        private float centreTolerance = 0.25f;
        private long turnMsPerUnit = 900;
        private int faceTurnsMax = 3;
        private int approachTicks = 3;
        private int approachLegsMax = 8;
        private long approachGraceMs = 500;
        private int lostLooksMax = 2;
        private long curiousMs = 1100;
        private long thinkingMs = 1500;
        private long nameMs = 2500;
        private long delightedMs = 1600;
        private long disappointedMs = 1400;
        private long puzzledMs = 1100;
        private long peopleCooldownMs = 120000;
        // Two tries of 15 s (R7): live, look requests took ~3 s but two in a row ran
        // past 10 s. A stop with Claude unreachable falls back within ~30 s (AE4).
        private int askAttempts = 2;
        private long askTimeoutMs = 15000;
        // A few seconds of speech (R5), plus the launcher's synthesis; only a backstop.
        private long sayTimeoutMs = 15000;
        private long quietWaitMs = 1500;
        private int recentPicksMax = 8;
        // One try each; the match sends up to 11 small images, so it gets a little longer than a look try.
        private long meetTimeoutMs = 12000;
        // KTD4's 6 s cap, and room for the launcher to wait out the speech queue and decode.
        private long listenMs = 6000;
        private long listenMarginMs = 6000;
        private float pickMatchIou = 0.3f;
        private long reopenGapMs = 3000;
        private Calibration calibration;

        /** A fixed leg length. */
        Builder hopTicks(int v) { hopTicks = v; hopTicksMax = v; return this; }
        /** A random leg length between min and max ticks. */
        Builder hopTicks(int min, int max) { hopTicks = min; hopTicksMax = max; return this; }
        Builder hopTickMs(long v) { hopTickMs = v; return this; }
        Builder backTicks(int v) { backTicks = v; return this; }
        Builder backTickMs(long v) { backTickMs = v; return this; }
        Builder pauseMs(long min, long max) { pauseMinMs = min; pauseMaxMs = max; return this; }
        Builder turnChance(double v) { turnChance = v; return this; }
        Builder turnMs(long min, long max) { turnMinMs = min; turnMaxMs = max; return this; }
        Builder escapeTurnMs(long v) { escapeTurnMs = v; return this; }
        Builder corneredTurnMs(long v) { corneredTurnMs = v; return this; }
        Builder stall(long graceMs, long windowMs, long minCounts) {
            stallGraceMs = graceMs;
            stallWindowMs = windowMs;
            stallMinCounts = minCounts;
            return this;
        }
        Builder stallEscape(int backTicks, long turnMs, long turnStepMs) {
            stallBackTicks = backTicks;
            stallTurnMs = turnMs;
            stallTurnStepMs = turnStepMs;
            return this;
        }
        Builder escape(long clearMs, long sweepMaxMs, int failuresMax, long failWindowMs) {
            escapeClearMs = clearMs;
            escapeSweepMaxMs = sweepMaxMs;
            escapeFailuresMax = failuresMax;
            escapeFailWindowMs = failWindowMs;
            return this;
        }
        Builder lookLeadMs(long v) { lookLeadMs = v; return this; }
        Builder startleMs(long v) { startleMs = v; return this; }
        Builder staleMs(long v) { staleMs = v; return this; }
        Builder frozenTofWindowMs(long v) { frozenTofWindowMs = v; return this; }
        Builder recoveryStreak(int v) { recoveryStreak = v; return this; }
        Builder cap(int hazards, long windowMs, long cooldown) {
            capHazards = hazards;
            capWindowMs = windowMs;
            cooldownMs = cooldown;
            return this;
        }
        Builder ir1IsLeft(boolean v) { ir1IsLeft = v; return this; }
        Builder approachBandLower(int v) { approachBandLower = v; return this; }
        Builder recognition(float confidence, float unsure) {
            confidenceFloor = confidence;
            unsureFloor = unsure;
            return this;
        }
        Builder fill(float height, float area) {
            fillHeight = height;
            fillArea = area;
            return this;
        }
        Builder curiosityMs(long min, long max) { curiosityMinMs = min; curiosityMaxMs = max; return this; }
        Builder scan(int looks, long turnMs) { scanLooks = looks; scanTurnMs = turnMs; return this; }
        Builder lookTiming(long settle, long firstTimeout, long timeout) {
            lookSettleMs = settle;
            firstLookTimeoutMs = firstTimeout;
            lookTimeoutMs = timeout;
            return this;
        }
        Builder cameraBackoffMs(long v) { cameraBackoffMs = v; return this; }
        Builder facing(float tolerance, long msPerUnit, int turnsMax) {
            centreTolerance = tolerance;
            turnMsPerUnit = msPerUnit;
            faceTurnsMax = turnsMax;
            return this;
        }
        Builder approach(int ticks, int legsMax, long graceMs, int lostMax) {
            approachTicks = ticks;
            approachLegsMax = legsMax;
            approachGraceMs = graceMs;
            lostLooksMax = lostMax;
            return this;
        }
        /** One length for every reaction clip, for scripted timelines. */
        Builder reactionMs(long v) {
            curiousMs = v;
            thinkingMs = v;
            nameMs = v;
            delightedMs = v;
            disappointedMs = v;
            puzzledMs = v;
            return this;
        }
        Builder peopleCooldownMs(long v) { peopleCooldownMs = v; return this; }
        Builder ask(int attempts, long timeoutMs) { askAttempts = attempts; askTimeoutMs = timeoutMs; return this; }
        Builder sayTimeoutMs(long v) { sayTimeoutMs = v; return this; }
        Builder quietWaitMs(long v) { quietWaitMs = v; return this; }
        Builder recentPicksMax(int v) { recentPicksMax = v; return this; }
        Builder meet(long timeoutMs, long listenMs, long listenMarginMs) {
            meetTimeoutMs = timeoutMs;
            this.listenMs = listenMs;
            this.listenMarginMs = listenMarginMs;
            return this;
        }
        Builder pickMatchIou(float v) { pickMatchIou = v; return this; }
        Builder reopenGapMs(long v) { reopenGapMs = v; return this; }
        Builder calibration(Calibration v) { calibration = v; return this; }

        ExploreTuning build() {
            return new ExploreTuning(this);
        }
    }
}
