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
    /** Turn away from a hazard, as a duration: used while the gyro is uncalibrated (KTD5; explore nav plan U2). */
    final long escapeTurnMs;
    /** The larger turn tried after a cornered cool-down (KTD8). */
    final long corneredTurnMs;
    /** How long the eyes look toward a new heading before the turn starts (KTD10, R7). */
    final long lookLeadMs;
    /** How long the startle holds still, sound and flinch, before the back-off (R11). */
    final long startleMs;
    /** No reading for this long means the sensors are unavailable: three missed 100 ms polls (KTD3). */
    final long staleMs;
    /** The MikoExploreSpin hook's still spell (the gyro bias) and each one-way turn
     * (explore nav plan U1): long enough for a few full turns the owner marks. */
    final long spinStillMs;
    final long spinLegMs;
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
    /**
     * Still pinned (live: every turn blocked, a ladder with its two Claude asks every
     * ~40 s): after a failed escape ladder's rest, a wedge within pinnedWindowMs of
     * the rest ending means he never got anywhere. The next ladder then waits
     * cooldownMs x 2^k (k failed ladders in a row, capped at pinnedMaxRestMs);
     * a clean drive-off or clean leg resets k.
     */
    final long pinnedWindowMs;
    final long pinnedMaxRestMs;
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
    /**
     * Claude's line for a pick whose turn or approach a hazard cut short is still
     * said once the escape is over, if the pick is younger than this.
     */
    final long heldLineFreshMs;
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
    /**
     * The gyro's yaw calibration from the device (explore nav plan U1), or null:
     * then every turn is the timed duration above, as before U2.
     */
    final ExploreCalibration.Gyro gyro;
    /**
     * The heading tracker (explore nav plan U2, KTD1). A stop's bias samples start
     * headingSettleMs after the wheels stood still with nothing commanded; the bias
     * is their mean once there are biasSamplesMin (readings are ~100 ms apart).
     */
    final long headingSettleMs;
    final int biasSamplesMin;
    /**
     * Measured turns: an angle within turnToleranceDeg needs no turn; turnBackstopMs
     * ends any measured turn that never gets there (a turn blocked by something).
     * The coast after a stop is learned: after each turn the overshoot moves
     * overshootGain of the way toward what was seen once the coast has died down
     * (or overshootSettleMs), capped at overshootMaxDeg.
     */
    final double turnToleranceDeg;
    final long turnBackstopMs;
    final double overshootGain;
    final double overshootMaxDeg;
    final long overshootSettleMs;
    /**
     * The measured twins of the timed turns above, used once the gyro is calibrated:
     * discretionary turns, the escape turn's minimum, the cornered turn, the stall
     * turn and its step, a full escape sweep, and the scan step. The timed defaults
     * assumed about 60 deg/s (escapeSweepMaxMs's "6 s is about a full circle").
     */
    final double turnMinDeg;
    final double turnMaxDeg;
    final double escapeTurnDeg;
    final double corneredTurnDeg;
    final double stallTurnDeg;
    final double stallTurnStepDeg;
    final double escapeSweepDeg;
    final double scanTurnDeg;
    /** Half the camera's horizontal view: a box at the frame's edge is this far off ahead. */
    final double cameraHalfFovDeg;
    /** The leg log's length (KTD6): the oldest legs drop off. */
    final int legsMax;
    /**
     * How the camera serves roaming (explore nav plan U4, KTD2, KTD7): CONTINUOUS
     * keeps it open whenever he roams or escapes and steers each leg from the
     * newest look; LOOK_THEN_GO, the fallback (the MikoExploreLookThenGo hook),
     * opens it only at each leg decision and closes it before the leg.
     */
    enum Navigation { CONTINUOUS, LOOK_THEN_GO }

    final Navigation navigation;
    /**
     * The roaming steer (RoamSteer; explore nav plan U4, KTD9). A look older than
     * steerLookFreshMs, or taken before the last turn ended, is not used. Below
     * steerMinConfidence the profile is ignored and the leg is chosen as before
     * the camera roamed. Bins are grouped steerBandBins at a time; a band at or
     * below steerBlocked reads blocked, at or above steerOpen fully open, and in
     * between the leg is shortened in proportion. The best band must beat the
     * band ahead by steerMinGain to bend toward it. Everything blocked is a turn
     * of at least steerBlockedTurnDeg with no leg, at most steerTurnsOnlyMax times
     * in a row; then a leg of steerShortTicks (the floor sensor guards it).
     */
    final long steerLookFreshMs;
    final float steerMinConfidence;
    final int steerBandBins;
    final float steerBlocked;
    final float steerOpen;
    final float steerMinGain;
    final double steerBlockedTurnDeg;
    final int steerTurnsOnlyMax;
    final int steerShortTicks;
    /**
     * Floor teaching (explore nav plan U3, KTD3): a look's bottom-of-frame floor
     * is taught once he has driven this many encoder counts (mean of the two
     * wheels) forward since the look arrived, with no hazard or stall in between.
     */
    final long floorTeachCounts;
    /**
     * A measured turn the gyro says isn't turning (explore nav plan U5; live 2026-09-25,
     * wedged under a desk: "asked 120 deg, turned 0 deg", each waiting out the 15 s
     * backstop): less than turnStallDeg of progress for turnStallMs, from the turn's
     * start or its last progress, is a blocked turn. It stops at once and counts as
     * wedged. 5 deg in 1.5 s is far below any real turn (~60 deg/s) and well above
     * the gyro's drift while still.
     */
    final double turnStallDeg;
    final long turnStallMs;
    /**
     * Wedged (explore nav plan U5, R11-R14): with the heading usable, wedgeHazards
     * hazard reactions within capWindowMs with no clean leg between, wedgeStalls
     * stalls in a row, wedgeFailedEscapes failed escape sweeps, or a blocked turn
     * start the escape planner (EscapePlanner) instead of today's escape turns; the
     * cornered cap above still applies uncalibrated.
     */
    final int wedgeHazards;
    final int wedgeStalls;
    final int wedgeFailedEscapes;
    /**
     * The retrace (KTD6): back along the leg log, newest leg first, turning to face
     * each leg's reverse and driving it forward, up to escapeRetraceCounts in all
     * (encoder counts, the mean of both wheels). A move under escapeRetraceMinCounts
     * is encoder slack and skipped. Legs within escapeLineToleranceDeg of opposite
     * (a leg and its back-off) cancel; within it of his facing they lie on his line
     * for a straight back-out when he cannot turn.
     */
    final long escapeRetraceCounts;
    final long escapeRetraceMinCounts;
    final double escapeLineToleranceDeg;
    /**
     * Each escape step's time budget (U5's table, ~30 s to driving off after the
     * circle's way-out answer); a step out of time has failed and the next starts.
     * The second ask comes after the target has been missed, so its own budget is
     * outside it; its drive-off gets escapeDriveOffMs like the first.
     */
    final long escapeRetraceMs;
    final long escapeCircleMs;
    final long escapeAskMs;
    final long escapeDriveOffMs;
    final long escapeSecondAskMs;
    /**
     * A step's budget also covers the measured turns it makes (live 2026-09-25: on
     * carpet he turns ~40 deg/s, not the ~60 the timed turns assume, and the 3 s
     * drive-off ran out 125 deg into a 208 deg turn that would have freed him). Each
     * escape turn adds its angle at the escape turn rate to its step's budget: the
     * rate learned from recent measured turns (Heading.turnRateDegS()), never above
     * escapeTurnRateDegS nor below escapeTurnRateFloorDegS, the extra capped at
     * escapeTurnAllowanceMaxMs per step. The ~30 s target above is the fixed
     * allowances; the real one is that plus the ladder's turning at this rate (a
     * retrace turn of up to 180 deg, the circle's 300, a drive-off's up to 180: ~19 s
     * more at the default 35 deg/s). A measured turn still making progress is never
     * cut off by a budget (only the blocked-turn rule or turnBackstopMs end it), and
     * a drive-off whose turn is done drives its ticks whatever its budget says.
     */
    final double escapeTurnRateDegS;
    final double escapeTurnRateFloorDegS;
    final long escapeTurnAllowanceMaxMs;
    /**
     * Forward first: when an escape step runs out of time, when a ladder is about to
     * end in rest, and when a cornered rest ends, he first tries escapeProbeTicks
     * forward ticks if the floor sensor reads clear, the camera (if up) does not read
     * the way ahead blocked, and (except after a rest) no forward drive within
     * escapeProbeClearDeg of this heading has just hit a hazard or stalled. Driven
     * cleanly (the encoders moved, no hazard or stall), he is free and roams on;
     * blocked, the ladder, the rest or the turn goes on as before.
     */
    final int escapeProbeTicks;
    final double escapeProbeClearDeg;
    /**
     * The measured circle: escapeCircleSteps looks escapeCircleStepDeg apart, each
     * from a frame captured escapeSettleMs after he stopped (the gyro's bias is
     * re-estimated in that stillness: headingSettleMs plus a few readings).
     */
    final int escapeCircleSteps;
    final double escapeCircleStepDeg;
    final long escapeSettleMs;
    /**
     * A drive-off is escapeDriveTicks forward ticks clear of hazards and stalls. A step
     * whose budget ends while he is driving forward cleanly, escapeFreeTicks in, has
     * freed him rather than failed.
     */
    final int escapeDriveTicks;
    final int escapeFreeTicks;
    /** The on-robot way out: a heading within escapeTriedDeg of one tried this escape loses escapeTriedPenalty. */
    final double escapeTriedDeg;
    final float escapeTriedPenalty;
    /** A back-out is blind: never longer than this, whatever the leg log allows. */
    final long backOutMaxMs;
    /**
     * A measured turn that will not turn backs up blind this many back ticks first
     * (bounded by their time, stopped early by a stall), once per turn, then tries the
     * same turn again (live 2026-09-25: pinned after a CPL stop with no leg to back out
     * along, "all he needed was to back up a tiny bit and then spin"). A wedged escape
     * ladder also starts with one such back-up, whatever the leg log holds. 0: no back-up.
     */
    final int blockedTurnBackTicks;
    /**
     * A retrace turn that would go the long way round past a blocked side, further than
     * this, is skipped for the circle (live 2026-09-25: a 327 deg turn at the learned
     * rate spent 20 s of the step turning).
     */
    final double retraceLongWayMaxDeg;
    /**
     * Open doorways through Claude (explore nav plan U6, R7, R8, KTD4, KTD5). While
     * he roams with the heading usable, one frame is asked about at most every
     * doorwayAskMs (the interval restarts when an answer, a failure or a drop ends
     * the ask; the brain gives up on one after doorwayAskTimeoutMs). An answer
     * becomes a remembered heading; the steer adds up to doorwayWeight to the open
     * bands nearest it. Within doorwayFacingDeg of it, the band there must read
     * open or it is dropped (the door was closed since). It is forgotten after
     * doorwayExpireMs or doorwayExpireCounts forward encoder counts (mean of the
     * wheels), or once a full leg is driven toward it with the way reading open.
     */
    final long doorwayAskMs;
    final long doorwayAskTimeoutMs;
    final float doorwayWeight;
    final double doorwayFacingDeg;
    final long doorwayExpireMs;
    final long doorwayExpireCounts;
    /**
     * People while roaming (explore nav plan U7, R9, R10, KTD8). A person box he sees
     * while roaming is approached until it is politeHeight of the frame's height (any
     * person approach stops there). Everyone he meets is left alone for
     * metLeaveAloneMs from the end of their meeting: while anyone is on that list, a
     * person is approached only after Claude's recently-met check answers "none of
     * them", at most one check per metCheckIntervalMs, each given up after
     * metCheckTimeoutMs; a "none of them" clears a roaming person for metClearedMs.
     */
    final float politeHeight;
    final long metLeaveAloneMs;
    final long metCheckIntervalMs;
    final long metCheckTimeoutMs;
    final long metClearedMs;
    /**
     * Going somewhere new (explore nav plan U10, R18). Coverage dead-reckons a rough
     * position from the gyro heading and the signed encoder distance, at
     * coverageCountsPerMetre (mean of the two wheels), and marks coverageCellM cells
     * visited; a visit fades out over coverageFadeMs. A heading's novelty (0..1) is
     * how little the next coverageLookaheadM along it runs over recently visited
     * cells. The steer adds up to coverageWeight x novelty to each open band (a
     * blocked band never gains); when the way the steer would take is at or below
     * coverageStaleNovelty, a heading out of view at least coverageMinGain more novel
     * is turned to (never two such turns in a row), and a turn from a view with
     * nothing open may grow to face ground coverageMinGain newer. A fully open leg drives up to
     * coverageLongTicksMax, in proportion to its novelty. Without a camera plan, a
     * way ahead at least coverageNovelAhead novel cuts turnChance by
     * coverageTurnScale, and a turn goes the most novel way. coverageWeight 0 turns
     * all of it off; with no usable heading none of it applies.
     */
    final double coverageCountsPerMetre;
    final double coverageCellM;
    final long coverageFadeMs;
    final double coverageLookaheadM;
    final float coverageWeight;
    final float coverageStaleNovelty;
    final float coverageMinGain;
    final int coverageLongTicksMax;
    final float coverageNovelAhead;
    final double coverageTurnScale;

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
        spinStillMs = b.spinStillMs;
        spinLegMs = b.spinLegMs;
        tofFault = b.tofFault;
        frozenTofWindowMs = b.frozenTofWindowMs;
        recoveryStreak = Math.max(1, b.recoveryStreak);
        capHazards = Math.max(1, b.capHazards);
        capWindowMs = b.capWindowMs;
        cooldownMs = b.cooldownMs;
        pinnedWindowMs = b.pinnedWindowMs;
        pinnedMaxRestMs = b.pinnedMaxRestMs;
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
        heldLineFreshMs = Math.max(0, b.heldLineFreshMs);
        recentPicksMax = Math.max(0, b.recentPicksMax);
        meetTimeoutMs = b.meetTimeoutMs;
        listenMs = b.listenMs;
        listenMarginMs = b.listenMarginMs;
        pickMatchIou = b.pickMatchIou;
        reopenGapMs = b.reopenGapMs;
        calibration = b.calibration;
        gyro = b.gyro;
        headingSettleMs = b.headingSettleMs;
        biasSamplesMin = Math.max(1, b.biasSamplesMin);
        turnToleranceDeg = b.turnToleranceDeg;
        turnBackstopMs = b.turnBackstopMs;
        overshootGain = b.overshootGain;
        overshootMaxDeg = b.overshootMaxDeg;
        overshootSettleMs = b.overshootSettleMs;
        turnMinDeg = b.turnMinDeg;
        turnMaxDeg = Math.max(b.turnMinDeg, b.turnMaxDeg);
        escapeTurnDeg = b.escapeTurnDeg;
        corneredTurnDeg = b.corneredTurnDeg;
        stallTurnDeg = b.stallTurnDeg;
        stallTurnStepDeg = b.stallTurnStepDeg;
        escapeSweepDeg = Math.max(b.escapeTurnDeg, b.escapeSweepDeg);
        scanTurnDeg = b.scanTurnDeg;
        cameraHalfFovDeg = b.cameraHalfFovDeg;
        legsMax = Math.max(1, b.legsMax);
        navigation = b.navigation;
        steerLookFreshMs = b.steerLookFreshMs;
        steerMinConfidence = b.steerMinConfidence;
        steerBandBins = Math.max(1, b.steerBandBins);
        steerBlocked = b.steerBlocked;
        steerOpen = Math.max(b.steerBlocked, b.steerOpen);
        steerMinGain = b.steerMinGain;
        steerBlockedTurnDeg = b.steerBlockedTurnDeg;
        steerTurnsOnlyMax = Math.max(0, b.steerTurnsOnlyMax);
        steerShortTicks = Math.max(1, b.steerShortTicks);
        floorTeachCounts = b.floorTeachCounts;
        turnStallDeg = b.turnStallDeg;
        turnStallMs = b.turnStallMs;
        wedgeHazards = Math.max(1, b.wedgeHazards);
        wedgeStalls = Math.max(1, b.wedgeStalls);
        wedgeFailedEscapes = Math.max(1, b.wedgeFailedEscapes);
        escapeRetraceCounts = Math.max(0, b.escapeRetraceCounts);
        escapeRetraceMinCounts = Math.max(1, b.escapeRetraceMinCounts);
        escapeLineToleranceDeg = b.escapeLineToleranceDeg;
        escapeRetraceMs = b.escapeRetraceMs;
        escapeCircleMs = b.escapeCircleMs;
        escapeAskMs = b.escapeAskMs;
        escapeDriveOffMs = b.escapeDriveOffMs;
        escapeSecondAskMs = b.escapeSecondAskMs;
        escapeTurnRateDegS = Math.max(1, b.escapeTurnRateDegS);
        escapeTurnRateFloorDegS = Math.max(1, Math.min(b.escapeTurnRateFloorDegS, escapeTurnRateDegS));
        escapeTurnAllowanceMaxMs = Math.max(0, b.escapeTurnAllowanceMaxMs);
        escapeProbeTicks = Math.max(0, b.escapeProbeTicks);
        escapeProbeClearDeg = b.escapeProbeClearDeg;
        escapeCircleSteps = Math.max(1, b.escapeCircleSteps);
        escapeCircleStepDeg = b.escapeCircleStepDeg;
        escapeSettleMs = b.escapeSettleMs;
        escapeDriveTicks = Math.max(1, b.escapeDriveTicks);
        escapeFreeTicks = Math.max(1, b.escapeFreeTicks);
        escapeTriedDeg = b.escapeTriedDeg;
        escapeTriedPenalty = b.escapeTriedPenalty;
        backOutMaxMs = b.backOutMaxMs;
        blockedTurnBackTicks = Math.max(0, b.blockedTurnBackTicks);
        retraceLongWayMaxDeg = b.retraceLongWayMaxDeg;
        doorwayAskMs = Math.max(0, b.doorwayAskMs);
        doorwayAskTimeoutMs = Math.max(1, b.doorwayAskTimeoutMs);
        doorwayWeight = Math.max(0f, b.doorwayWeight);
        doorwayFacingDeg = b.doorwayFacingDeg;
        doorwayExpireMs = b.doorwayExpireMs;
        doorwayExpireCounts = b.doorwayExpireCounts;
        politeHeight = b.politeHeight;
        metLeaveAloneMs = Math.max(0, b.metLeaveAloneMs);
        metCheckIntervalMs = Math.max(0, b.metCheckIntervalMs);
        metCheckTimeoutMs = Math.max(1, b.metCheckTimeoutMs);
        metClearedMs = Math.max(0, b.metClearedMs);
        coverageCountsPerMetre = Math.max(1, b.coverageCountsPerMetre);
        coverageCellM = Math.max(0.05, b.coverageCellM);
        coverageFadeMs = Math.max(1, b.coverageFadeMs);
        coverageLookaheadM = Math.max(0, b.coverageLookaheadM);
        coverageWeight = Math.max(0f, b.coverageWeight);
        coverageStaleNovelty = b.coverageStaleNovelty;
        coverageMinGain = b.coverageMinGain;
        coverageLongTicksMax = Math.max(0, b.coverageLongTicksMax);
        coverageNovelAhead = b.coverageNovelAhead;
        coverageTurnScale = Math.max(0, Math.min(1, b.coverageTurnScale));
    }

    /** The shipped defaults with the given calibration (null = uncalibrated). */
    static ExploreTuning defaults(Calibration calibration) {
        return defaults(calibration, null);
    }

    /** The shipped defaults with the floor calibration and the gyro's (either may be null). */
    static ExploreTuning defaults(Calibration calibration, ExploreCalibration.Gyro gyro) {
        return defaults(calibration, gyro, Navigation.CONTINUOUS);
    }

    /** ...and the navigation mode (the MikoExploreLookThenGo hook picks LOOK_THEN_GO). */
    static ExploreTuning defaults(Calibration calibration, ExploreCalibration.Gyro gyro, Navigation navigation) {
        return new Builder().calibration(calibration).gyro(gyro).navigation(navigation).build();
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
        private long spinStillMs = 3000;
        private long spinLegMs = 20000;
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
        private long pinnedWindowMs = 10000;
        private long pinnedMaxRestMs = 240000;
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
        // Two tries of about 10 s (R7): a stop with Claude unreachable falls back
        // within ~20 s (AE4). Owner's call after live tests: a look usually takes ~3 s,
        // and a slow one is retried rather than waited on longer.
        private int askAttempts = 2;
        private long askTimeoutMs = 10000;
        // A few seconds of speech (R5), plus the launcher's synthesis; only a backstop.
        private long sayTimeoutMs = 15000;
        private long quietWaitMs = 1500;
        // Long enough for a startle, back-off and escape turn; stale after that.
        private long heldLineFreshMs = 30000;
        private int recentPicksMax = 8;
        // One try each; the match sends up to 11 small images, so it gets a little longer than a look try.
        private long meetTimeoutMs = 12000;
        // KTD4's 6 s cap, and room for the launcher to wait out the speech queue and decode.
        private long listenMs = 6000;
        private long listenMarginMs = 6000;
        private float pickMatchIou = 0.3f;
        private long reopenGapMs = 3000;
        private Calibration calibration;
        private ExploreCalibration.Gyro gyro;
        // Readings come ~100 ms apart; the stationary fixture's bias drifts by a
        // count or two, so a few samples after half a second of stillness will do.
        private long headingSettleMs = 500;
        private int biasSamplesMin = 3;
        // About one reading's step at 60 deg/s; the robot check wants four 90 deg
        // turns back within 15 deg (plan U2 verification).
        private double turnToleranceDeg = 5;
        private long turnBackstopMs = 15000;
        private double overshootGain = 0.5;
        private double overshootMaxDeg = 45;
        private long overshootSettleMs = 1000;
        // The timed defaults at ~60 deg/s: 300-900 ms, 1200 ms, 2400 ms, 2000 + 1000 ms, 700 ms.
        private double turnMinDeg = 20;
        private double turnMaxDeg = 55;
        private double escapeTurnDeg = 70;
        private double corneredTurnDeg = 145;
        private double stallTurnDeg = 120;
        private double stallTurnStepDeg = 60;
        private double escapeSweepDeg = 360;
        private double scanTurnDeg = 40;
        // Not measured on this camera: "right third" of the frame is about 20 deg
        // right (explore nav plan U6), i.e. a 60 deg view.
        private double cameraHalfFovDeg = 30;
        private int legsMax = 16;
        private Navigation navigation = Navigation.CONTINUOUS;
        // A live look takes ~0.6 s and up to ~2.5 s; older than this he has moved on.
        private long steerLookFreshMs = 3000;
        // Openness gives 0.3 with no floor taught yet and 0.9 once taught (full light):
        // until the floor is taught he steers only on what the boxes and horizon say
        // strongly enough, i.e. not at all.
        private float steerMinConfidence = 0.5f;
        // A quarter of the frame, ~15 deg of the ~60 deg view: about his own width a few feet out.
        private int steerBandBins = 4;
        private float steerBlocked = 0.35f;
        private float steerOpen = 0.7f;
        private float steerMinGain = 0.15f;
        // Past the edge of the view: nothing in it is open.
        private double steerBlockedTurnDeg = 60;
        private int steerTurnsOnlyMax = 2;
        // About 1 s of driving.
        private int steerShortTicks = 4;
        // ~650-880 counts/s per wheel forward (docs/hardware/tof-sensor.md): ~1.3 s of
        // driving, about the distance to the frame's bottom rows with this tilted-up
        // camera ~10 cm off the floor. Not measured; U8 checks it.
        private long floorTeachCounts = 1000;
        private double turnStallDeg = 5;
        private long turnStallMs = 1500;
        // Three hazards with no clean leg between: the wall-and-plant nook (AE1) gives
        // exactly that, where today's cap (8) took minutes of failed sweeps.
        private int wedgeHazards = 3;
        private int wedgeStalls = 2;
        private int wedgeFailedEscapes = 1;
        // ~2 s of driving at 650-880 counts/s (docs/hardware/tof-sensor.md): out of a
        // nook the size of the robot or two. Not measured; U8 checks it.
        private long escapeRetraceCounts = 1500;
        private long escapeRetraceMinCounts = 60;
        private double escapeLineToleranceDeg = 20;
        // U5's step budget table: 6 + 12 + 6 + 3 = 27 s, inside the ~30 s target, which
        // assumes the default escape turn rate below; each step adds its turns' time.
        private long escapeRetraceMs = 6000;
        private long escapeCircleMs = 12000;
        private long escapeAskMs = 6000;
        private long escapeDriveOffMs = 3000;
        private long escapeSecondAskMs = 6000;
        // Below the ~40 deg/s measured on carpet (live 2026-09-25), so a turn fits its step;
        // a learned slower rate counts down to the floor, and one step's extra is capped
        // at 20 s (the circle's 300 deg at the floor).
        private double escapeTurnRateDegS = 35;
        private double escapeTurnRateFloorDegS = 15;
        private long escapeTurnAllowanceMaxMs = 20000;
        // ~0.75 s forward: enough for the encoders and the floor sensor to rule.
        private int escapeProbeTicks = 3;
        private double escapeProbeClearDeg = 30;
        private int escapeCircleSteps = 6;
        private double escapeCircleStepDeg = 60;
        // Heading U2: the bias needs ~0.8 s still (headingSettleMs + biasSamplesMin readings).
        private long escapeSettleMs = 800;
        // ~1.5 s of driving off; 1 s of it clean is free when a budget ends mid-drive.
        private int escapeDriveTicks = 6;
        private int escapeFreeTicks = 4;
        private double escapeTriedDeg = 45;
        private float escapeTriedPenalty = 0.5f;
        private long backOutMaxMs = 4000;
        // ~2 s at backTickMs: the owner's manual reverse that freed him (live 2026-09-25,
        // 8 frames at 250 ms). Still blind with no rear sensor, so bounded by its time, and
        // the stall watch stops it early against something behind him.
        private int blockedTurnBackTicks = 8;
        private double retraceLongWayMaxDeg = 200;
        // R8 and the owner's cap (Key Decisions): about once a minute, so room pictures
        // sent to Claude stay infrequent. A navigation ask answers in ~3-6 s live (U5).
        private long doorwayAskMs = 60000;
        private long doorwayAskTimeoutMs = 15000;
        // Enough to beat a band ahead that reads as open (0.9 vs 0.9 + 0.3 > steerMinGain),
        // never enough to lift a blocked band (bands at or below steerBlocked get none).
        private float doorwayWeight = 0.3f;
        // About one steer band (~15 deg of the ~60 deg view).
        private double doorwayFacingDeg = 15;
        // A minute, or ~8 s of driving at 650-880 counts/s (docs/hardware/tof-sensor.md):
        // the next ask by then says afresh. Not measured; U8 checks it.
        private long doorwayExpireMs = 60000;
        private long doorwayExpireCounts = 6000;
        // A person's box reaching 60% of the frame's height: roughly an arm's length or
        // two from a standing adult for a camera ~25 cm off the floor, short of fillHeight.
        // Not measured; U8 checks it on the floor with the owner.
        private float politeHeight = 0.6f;
        // The owner's 10 minutes (Key Decisions, user-approved), from each meeting's end.
        private long metLeaveAloneMs = 600000;
        // KTD8: one recently-met check a minute keeps face pictures sent to Claude rare.
        private long metCheckIntervalMs = 60000;
        // Like a look try (askTimeoutMs): a person request answers in ~3-6 s live.
        private long metCheckTimeoutMs = 10000;
        // Long enough to reach the next leg decision after the answer comes back.
        private long metClearedMs = 15000;
        // ~650-880 counts/s per wheel forward (docs/hardware/tof-sensor.md) at a guessed
        // ~0.25 m/s. Not measured: U8 measures a leg with a tape; it only scales the
        // grid, so an error here makes cells bigger or smaller, not wrong.
        private double coverageCountsPerMetre = 3000;
        // About two of his body lengths: coarse enough that gyro drift barely moves a cell.
        private double coverageCellM = 0.5;
        // A few minutes (R18): the 4 ft area the owner saw on 2026-09-25 took 15; after
        // this an old area is fair game again, and dead-reckoning drift is forgotten.
        private long coverageFadeMs = 180000;
        // The next leg or two.
        private double coverageLookaheadM = 1.5;
        // Like doorwayWeight: a fully new open band beats an equally open visited one by
        // more than steerMinGain, but never lifts a blocked one.
        private float coverageWeight = 0.3f;
        // Half the ground ahead covered lately: worth a turn toward ground at least 0.2
        // newer (host room runs, 2026-09-25: ~1.4-1.6x the cells of novelty off).
        private float coverageStaleNovelty = 0.5f;
        private float coverageMinGain = 0.2f;
        // 15 s of driving (~4 m at the guessed speed), vs 4-10 s drawn today; a look
        // reading blocked ahead still ends it at the next tick, the floor sensor any time.
        private int coverageLongTicksMax = 60;
        private float coverageNovelAhead = 0.7f;
        // turnChance 0.7 becomes ~0.2 while the way ahead is new ground.
        private double coverageTurnScale = 0.3;

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
        Builder spinMs(long still, long leg) { spinStillMs = still; spinLegMs = leg; return this; }
        Builder frozenTofWindowMs(long v) { frozenTofWindowMs = v; return this; }
        Builder recoveryStreak(int v) { recoveryStreak = v; return this; }
        Builder cap(int hazards, long windowMs, long cooldown) {
            capHazards = hazards;
            capWindowMs = windowMs;
            cooldownMs = cooldown;
            return this;
        }
        Builder pinned(long windowMs, long maxRestMs) {
            pinnedWindowMs = windowMs;
            pinnedMaxRestMs = maxRestMs;
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
        Builder heldLineFreshMs(long v) { heldLineFreshMs = v; return this; }
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
        Builder gyro(ExploreCalibration.Gyro v) { gyro = v; return this; }
        Builder heading(long settleMs, int biasSamples) {
            headingSettleMs = settleMs;
            biasSamplesMin = biasSamples;
            return this;
        }
        Builder measuredTurns(double toleranceDeg, long backstopMs) {
            turnToleranceDeg = toleranceDeg;
            turnBackstopMs = backstopMs;
            return this;
        }
        Builder overshoot(double gain, double maxDeg, long settleMs) {
            overshootGain = gain;
            overshootMaxDeg = maxDeg;
            overshootSettleMs = settleMs;
            return this;
        }
        Builder turnDeg(double min, double max) { turnMinDeg = min; turnMaxDeg = max; return this; }
        Builder escapeDeg(double turn, double cornered, double sweep) {
            escapeTurnDeg = turn;
            corneredTurnDeg = cornered;
            escapeSweepDeg = sweep;
            return this;
        }
        Builder stallTurnDeg(double turn, double step) { stallTurnDeg = turn; stallTurnStepDeg = step; return this; }
        Builder scanTurnDeg(double v) { scanTurnDeg = v; return this; }
        Builder cameraHalfFovDeg(double v) { cameraHalfFovDeg = v; return this; }
        Builder legsMax(int v) { legsMax = v; return this; }
        Builder navigation(Navigation v) { navigation = v; return this; }
        Builder steerLookFreshMs(long v) { steerLookFreshMs = v; return this; }
        Builder steer(float minConfidence, int bandBins, float blocked, float open, float minGain) {
            steerMinConfidence = minConfidence;
            steerBandBins = bandBins;
            steerBlocked = blocked;
            steerOpen = open;
            steerMinGain = minGain;
            return this;
        }
        Builder steerBlockedTurn(double deg, int turnsOnlyMax, int shortTicks) {
            steerBlockedTurnDeg = deg;
            steerTurnsOnlyMax = turnsOnlyMax;
            steerShortTicks = shortTicks;
            return this;
        }
        Builder floorTeachCounts(long v) { floorTeachCounts = v; return this; }
        Builder turnStall(double deg, long ms) { turnStallDeg = deg; turnStallMs = ms; return this; }
        Builder wedge(int hazards, int stalls, int failedEscapes) {
            wedgeHazards = hazards;
            wedgeStalls = stalls;
            wedgeFailedEscapes = failedEscapes;
            return this;
        }
        Builder escapeRetrace(long counts, long minCounts) {
            escapeRetraceCounts = counts;
            escapeRetraceMinCounts = minCounts;
            return this;
        }
        Builder escapeLineToleranceDeg(double v) { escapeLineToleranceDeg = v; return this; }
        Builder escapeBudgets(long retraceMs, long circleMs, long askMs, long driveOffMs, long secondAskMs) {
            escapeRetraceMs = retraceMs;
            escapeCircleMs = circleMs;
            escapeAskMs = askMs;
            escapeDriveOffMs = driveOffMs;
            escapeSecondAskMs = secondAskMs;
            return this;
        }
        Builder escapeTurnRate(double defaultDegS, double floorDegS, long allowanceMaxMs) {
            escapeTurnRateDegS = defaultDegS;
            escapeTurnRateFloorDegS = floorDegS;
            escapeTurnAllowanceMaxMs = allowanceMaxMs;
            return this;
        }
        Builder escapeProbe(int ticks, double clearDeg) {
            escapeProbeTicks = ticks;
            escapeProbeClearDeg = clearDeg;
            return this;
        }
        Builder escapeCircle(int steps, double stepDeg, long settleMs) {
            escapeCircleSteps = steps;
            escapeCircleStepDeg = stepDeg;
            escapeSettleMs = settleMs;
            return this;
        }
        Builder escapeDrive(int ticks, int freeTicks) {
            escapeDriveTicks = ticks;
            escapeFreeTicks = freeTicks;
            return this;
        }
        Builder escapeTried(double deg, float penalty) { escapeTriedDeg = deg; escapeTriedPenalty = penalty; return this; }
        Builder backOutMaxMs(long v) { backOutMaxMs = v; return this; }
        Builder blockedTurnBackTicks(int v) { blockedTurnBackTicks = v; return this; }
        Builder retraceLongWayMaxDeg(double v) { retraceLongWayMaxDeg = v; return this; }
        Builder doorwayAsk(long intervalMs, long timeoutMs) {
            doorwayAskMs = intervalMs;
            doorwayAskTimeoutMs = timeoutMs;
            return this;
        }
        Builder doorwaySteer(float weight, double facingDeg) {
            doorwayWeight = weight;
            doorwayFacingDeg = facingDeg;
            return this;
        }
        Builder doorwayExpire(long ms, long counts) {
            doorwayExpireMs = ms;
            doorwayExpireCounts = counts;
            return this;
        }

        Builder politeHeight(float v) { politeHeight = v; return this; }
        Builder coverageGrid(double countsPerMetre, double cellM, long fadeMs, double lookaheadM) {
            coverageCountsPerMetre = countsPerMetre;
            coverageCellM = cellM;
            coverageFadeMs = fadeMs;
            coverageLookaheadM = lookaheadM;
            return this;
        }
        Builder coverageSteer(float weight, float staleNovelty, float minGain, int longTicksMax) {
            coverageWeight = weight;
            coverageStaleNovelty = staleNovelty;
            coverageMinGain = minGain;
            coverageLongTicksMax = longTicksMax;
            return this;
        }
        /** Novelty steering and long legs off: the roaming before U10 (the grid is still kept). */
        Builder coverageOff() { coverageWeight = 0f; return this; }
        Builder coverageTurns(float novelAhead, double turnScale) {
            coverageNovelAhead = novelAhead;
            coverageTurnScale = turnScale;
            return this;
        }
        Builder recentlyMet(long leaveAloneMs, long checkIntervalMs, long checkTimeoutMs, long clearedMs) {
            metLeaveAloneMs = leaveAloneMs;
            metCheckIntervalMs = checkIntervalMs;
            metCheckTimeoutMs = checkTimeoutMs;
            metClearedMs = clearedMs;
            return this;
        }

        ExploreTuning build() {
            return new ExploreTuning(this);
        }
    }
}
