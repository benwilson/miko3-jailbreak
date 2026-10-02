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
     * failure, which turns curiosity off for cameraBackoffMs (KTD8). A stop's own
     * miss is gentler (the remark rate, owner 2026-10-01): the first is retried
     * curiosityRetryMs later, and only a second in a row turns curiosity off, for
     * curiosityBackoffMs. Roaming and the call's search keep cameraBackoffMs.
     */
    final long curiosityMinMs;
    final long curiosityMaxMs;
    final int scanLooks;
    final long scanTurnMs;
    final long lookSettleMs;
    final long firstLookTimeoutMs;
    final long lookTimeoutMs;
    final long cameraBackoffMs;
    final long curiosityRetryMs;
    final long curiosityBackoffMs;
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
     * A roaming person pick whose face check found no face is a phantom (robot 2026-09-30:
     * motion-blurred frames scored person about 0.5 while roaming): the meeting is dropped
     * quietly and roaming person picks are ignored this long, so he does not go straight
     * back to the same blur.
     */
    final long phantomPersonCooldownMs;
    /**
     * Asking Claude (explore on Claude KTD6): askAttempts tries of askTimeoutMs
     * each before falling back to the detector (R7, R8). A spoken line is given
     * up on after sayTimeoutMs if the finished callback never comes (KTD8). The
     * look request carries the last recentPicksMax picks (KTD2).
     */
    final int askAttempts;
    final long askTimeoutMs;
    /**
     * Robot 2026-10-01 (the key hit 429s): a soft cap on Claude look requests (a curiosity
     * stop's ask and its second try, a seek, a doorway ask, a way-out ask) in any 60 s.
     * One past it takes that kind's no-Claude path. 0: no cap.
     */
    final int claudeLooksPerMinute;
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
     * Robot 2026-10-01 (a familiar office, 2 remarks in 30 min): the look request
     * also carries the labels of the last reactedLabelsMax things he reacted to and
     * his last saidLinesMax remarks about things, most recent first, so Claude can
     * prefer something new and otherwise give a fresh line, never a repeat.
     */
    final int reactedLabelsMax;
    final int saidLinesMax;
    /**
     * Docked (owner 2026-10-02): on the charger he sits still and quiet, and every
     * dockLookMs he takes one camera look without moving. He speaks only for
     * something he has not reacted to this session, with a line he has not said.
     */
    final long dockLookMs;
    /**
     * Docked by POWER (2026-10-02): how many readings in a row whose POWER says off
     * the dock it takes to leave DOCKED. Entering needs one (POWER=2 or a charging
     * current is unmistakable); leaving needs more, so one odd reading (the current
     * dipping as the battery tops off, a rocked contact) does not set him roaming off
     * the charger. Two at the ~100 ms poll is a fifth of a second: no visible delay.
     * Readings without a readable POWER count neither way.
     */
    final int dockOffReadings;
    /**
     * Meeting a person (explore on Claude U5, KTD3, KTD4): the match request and
     * each later Claude request get meetTimeoutMs; he listens for up to listenMs
     * and waits listenMarginMs more for the launcher's answer before giving up.
     */
    final long meetTimeoutMs;
    /**
     * The first roam waits at most this long for the start-up face migration
     * (face plan U6, KTD11), with the object detector not yet loaded.
     */
    final long faceHoldMs;
    final long listenMs;
    final long listenMarginMs;
    /**
     * Cues and the turn to the voice (meeting plan Assumptions, KTD3, KTD4; U6
     * pins them, U7 uses them). A held cue expires after cueHoldMs and an
     * expired strong cue degrades to a lean-in; a weak cue's lean-in has
     * leanInMs after the camera is ready to find a face. During CHAT states a
     * strong utterance whose latched angle magnitude exceeds newcomerAngleDeg is
     * a newcomer cue (KTD8). CUE_TURN stops when the angle magnitude is under
     * cueStopBandDeg or starts growing; a strong cue gets strongCueLooks looks
     * (side, rear, other side) and a weak cue weakCueLooks (side, opposite).
     */
    final long cueHoldMs;
    final long leanInMs;
    /**
     * The lean-in cooldown (robot 2026-10-01 15:12-15:17: under a desk while the owner
     * talked nearby, 15 lean-ins in 5 minutes, each interrupting the escape or leg he was
     * on). A lean-in (a weak cue, or a strong one that is not a call) that ends with no
     * meeting (nobody facing him, no usable face, a dropped meeting) ignores voices that
     * are not calls for leanInCooldownMs; and lean-ins start at most one per
     * leanInMinGapMs, after a meeting too. Calls are never ignored. 0: off.
     */
    final long leanInCooldownMs;
    final long leanInMinGapMs;
    /**
     * Boxed in (robot 2026-10-01: minutes under a desk, turns working, forward refused):
     * boxedInRefusals or more forward refusals by the controller (CPL hiccups and CPL
     * hazards) within boxedInWindowMs, or (boxedInLookAround) a full look-around that
     * found no heading open over boxedInOpen (robot 2026-10-01 15:55: three closed
     * readings looking one way only covered the half in front of him, with the open
     * floor behind). He then leaves the way he came: faces
     * the reverse of his last clean forward leg and drives it back (the escape ladder's
     * retrace move; refused, the ladder goes on). Once out, the view there counts as
     * where a seek went, and the novelty steer reads 0 within boxedAvoidDeg of the way
     * back in for boxedAvoidMs. boxedInRefusals 0: off.
     */
    final int boxedInRefusals;
    final long boxedInWindowMs;
    final double boxedInOpen;
    final boolean boxedInLookAround;
    /**
     * Look around (robot 2026-10-01 15:55): a leg decision whose whole view reads
     * closed (no band over steerBlocked) turns lookAroundStepDeg at a time, the
     * same way, and reads each look, up to lookAroundLooks looks in all (the first
     * is the closed one), stopping early on a look whose most open band reads
     * lookAroundOpen or more. He then faces the most open heading found (a
     * remembered doorway seen open in a look first) and goes on as a normal leg.
     * Only with the heading usable. lookAroundLooks under 2: off (the steer's
     * blind turns as before).
     */
    final int lookAroundLooks;
    final double lookAroundStepDeg;
    final float lookAroundOpen;
    /**
     * Review 2026-10-01 (P2-5): no new look-around within lookAroundCooldownMs of the
     * last one's end unless he has driven lookAroundCooldownCounts forward since; the
     * closed view then gets the steer's own plan (a short leg or a turn). 60 s: a full
     * look-around is 6 looks and 5 turns, about 15-20 s, so in clutter that reads 0.1-0.35
     * everywhere he spends about a quarter of his time looking around instead of nearly
     * all of it (harness: 3 in 3 min, was 11), and a spot just found hopeless is not
     * re-read within the same horizon boxedInWindowMs uses. 1500 counts (about 0.5 m, the
     * retrace distance) re-arms it at once: from a new spot the view all round has
     * changed and a fresh look-around can find something new. 0 ms: no cooldown.
     */
    final long lookAroundCooldownMs;
    final long lookAroundCooldownCounts;
    final long boxedAvoidMs;
    final double boxedAvoidDeg;
    final float newcomerAngleDeg;
    final float cueStopBandDeg;
    final int strongCueLooks;
    final int weakCueLooks;
    /**
     * "A face turned toward him" (KTD4): a face box whose width-to-height ratio
     * is at least facingFaceMinRatio and whose height is at least
     * facingFaceMinHeight of the frame; profile faces are narrower and do not
     * count. Placeholders until U2's frontal, 45-degree and profile captures at
     * 1.5 m set them.
     */
    final float facingFaceMinRatio;
    final float facingFaceMinHeight;
    /**
     * The turn to the voice and the shove cue (meeting plan U7; KTD4, KTD5,
     * KTD14). CUE_TURN turns toward the side in steps of at most cueTurnStepDeg,
     * re-reading the latched angle's trend between them; with no angle it turns
     * cueTurnDefaultDeg toward the side the mics gave. A shove arms a weak cue
     * only while the wheels are commanded stopped and at least shoveBlankingMs
     * after the last motor command; "sorry" or "oops" within bumpApologyMs of a
     * shove or a collision stop is a strong cue. The acknowledgement clip that
     * plays when a facing face is found lasts about ackClipMs, the deaf window
     * the port opens for it.
     */
    final double cueTurnStepDeg;
    final double cueTurnDefaultDeg;
    final long shoveBlankingMs;
    final long bumpApologyMs;
    final long ackClipMs;
    /**
     * The answer to a call (hey-miko plan KTD3): "Oh hi?", "What?" or "Yes?" from the
     * answer group, about half a second each (react-answer-*.webm, 0.50 to 0.53 s);
     * answerClipMs is the deaf window the port opens for it.
     */
    final long answerClipMs;
    /**
     * Finding the caller (hey-miko plan U6; KTD6-KTD9). Each look of the call's search
     * takes any frame captured once the turn stopped (no lookSettleMs wait) and has
     * callLookMs to see a person when no such frame arrives (the first look after the
     * camera opens has firstLookTimeoutMs, since a camera just opened has no look yet);
     * a stale look arriving during the stop means the frame captured since is on its way,
     * so the stop waits for it too. A person in a stale look counts when the heading it
     * was captured at is within callStaleLookDeg of where he faces now.
     * Found is a person box at least callPersonMinHeight of the frame tall, with no
     * aspect-ratio gate; one shorter than callNearHeight (about 1.5 m) is approached to
     * politeHeight, a taller one is met where it stands. After a search that finds
     * nobody the "where" clip plays (about whereClipMs) and he listens for callListenMs.
     * The call's angle is corrected for his own turning since it was heard from a
     * heading history of headingHistoryMs, sampled every headingSampleMs.
     * PLACEHOLDERS until the owner's QA (U7): callLookMs, callPersonMinHeight,
     * callNearHeight and callListenMs.
     */
    final long callLookMs;
    final double callStaleLookDeg;
    /**
     * Robot 2026-10-01: a caller in a look captured farther than callStaleLookDeg from where
     * he faces is a bearing to turn to (one confirming look there), at most this many times a call.
     */
    final int callSeenRetargetsMax;
    /**
     * Robot 2026-10-01 (the motor board latched): a call whose search turns were blocked
     * (turned under callBlockedTurnDeg) this many times meets the caller where he is.
     */
    final int callBlockedTurnsMax;
    final double callBlockedTurnDeg;
    /**
     * Fully jammed (robot 2026-10-01, "constantly getting stuck under this chair": head
     * against the seat, every wheel wedged): in one escape, a back-up that moved under
     * stallMinCounts and measured turns both ways that turned under jamTurnDeg. He stops
     * pushing at once (repeated stalled pushing latches the motor board), says the help
     * line at most every jamHelpEveryMs, rests, then tries one short back-up of
     * jamProbeTicks back ticks; it moving frees him, else he rests again. The back-ups come
     * at each of jamProbeAtMs after he was found jammed, then every jammedRestMs. Moved from
     * outside while resting (jamMovedCounts wheel counts, or jamMovedDeg of heading), he
     * probes at once. jamTurnDeg 0: off (the escape ladder and its rests, as before).
     */
    final double jamTurnDeg;
    final long jammedRestMs;
    final long[] jamProbeAtMs;
    final long jamHelpEveryMs;
    final int jamProbeTicks;
    final long jamMovedCounts;
    final double jamMovedDeg;
    /**
     * The long wriggle (robot 2026-10-01, wedged under an office chair): the escape's
     * ~1.5 s measured turns read "turned 0" and gave up, yet one continuous 11 s turn
     * worked him loose (1952 counts, then a back-up moved). When fully jammed, or when an
     * escape's measured turn is blocked twice in a row, he turns one way for up to
     * wriggleMs, then the other way for up to wriggleMs, before the help line. A heading
     * change of wriggleFreeDeg frees him; so does wriggleFreeCounts wheel counts (summed,
     * both wheels) followed by a short back-up (jamProbeTicks) that moves. Fewer than
     * wriggleMinCounts in any wriggleStillMs: the wheels are not moving, and that way stops
     * at once (repeated stalled pushing latches the motor board). At most one wriggle per
     * wriggleEveryMs. wriggleMs 0 (or jam detection off): no wriggle.
     */
    final long wriggleMs;
    final long wriggleMinCounts;
    final long wriggleStillMs;
    final double wriggleFreeDeg;
    final long wriggleFreeCounts;
    final long wriggleEveryMs;
    /**
     * The post-stall recovery wait (robot 2026-10-01, 14:08 and 14:33): after a drive
     * stall the motor board refused all motion for somewhere between ~9 s and 29 s, then
     * recovered by itself, so an escape run at once read 0 counts and 0 deg everywhere and
     * misjudged a jam. After a stall (forward or a back-up that went nowhere), or a turn
     * whose wheels read nothing within stallRecoverWindowMs of a stall or a collision stop,
     * he stops and waits, probing with one short turn (stallRecoverProbeMs) at each of
     * stallRecoverProbesMs after the stall. A probe that moves stallMinCounts wheel counts
     * (both wheels) or stallRecoverProbeDeg of heading: the board is back, and the normal
     * escape runs (see stallRecoverProbeBackTicks for which probes back up). None by the last: a real jam (the wriggle, then the help line). Once per
     * stuck spell (until he drives off cleanly or is freed). Empty: off. The probe turns in
     * place: a wedged wheel still counts when the board is alive (the 14:34 spin: ~100
     * counts per 0.5 s), and a turn can't back him blind into what stopped the back-up.
     */
    final long[] stallRecoverProbesMs;
    final long stallRecoverProbeMs;
    final double stallRecoverProbeDeg;
    /**
     * Robot 2026-10-01 15:19, owner: under the desk "all he has to do is back up". A probe
     * is a short back-up of stallRecoverProbeBackTicks back ticks (the way he came in is
     * usually clear; stallMinCounts wheel counts: the board is back). Only after two in a
     * row moved nothing is a probe the short turn. A turn probe whose wheels move but whose
     * heading does not (109 counts, 0 deg live) hit a desk leg: that way is blocked, and
     * the probes left back up again. Without encoders every probe turns, as before.
     */
    final int stallRecoverProbeBackTicks;
    /**
     * After a blocked turn's short back-up, he waits this long before trying the other way
     * (robot 15:19: a blocked turn is a fresh stall and re-arms the board's cutout, so the
     * move straight after it reads nothing). Also before the ladder's turn after its first
     * back-up when a turn was what wedged him. 0: no wait.
     */
    final long blockedTurnWaitMs;
    final long stallRecoverWindowMs;
    /**
     * Robot 2026-10-01 16:42: the cutout re-arms on every attempt that reads zero (a measured
     * turn under stallRecoverProbeDeg with still wheels, a back-up stalled at 0, a forward
     * stall), so each such attempt waits in RECOVER again, up to this many waits a stuck
     * spell (until he drives off cleanly). A wait whose probes never move tries the escape
     * again while waits are left; only after the cap (or a probe that found the board alive
     * but the way blocked) do the jam, wriggle and help path run. After each recovery the
     * first move is still the straight back-out.
     */
    final int recoverMaxPerSpell;
    /**
     * Robot 2026-10-02 11:57-12:00: three recoveries a minute apart, with driving between,
     * never reset the spell's count; the fourth stall went to the escape ladder inside the
     * cutout and he jammed falsely. The stuck spell ends (its recovery count back to 0) when a
     * forward leg drives recoverSpellResetCounts cleanly (mean of the wheels), or after
     * recoverSpellResetMs with no stall and no zero-movement attempt.
     */
    final long recoverSpellResetMs;
    final long recoverSpellResetCounts;
    final float callPersonMinHeight;
    /**
     * The call search's own person-score floor, used instead of confidenceFloor only
     * when looking for a caller (robot QA 2026-09-30: from the floor the detector scored
     * a caller 0.27 and 0.32, below the curiosity floor). Curiosity keeps confidenceFloor.
     */
    final float callPersonMinScore;
    final float callNearHeight;
    final long callListenMs;
    final long whereClipMs;
    final long headingHistoryMs;
    final long headingSampleMs;
    /**
     * The conversation (meeting plan Assumptions, KTD7, KTD9; U8 uses them). An
     * unanswered listen is unansweredListenMs with no utterance, kept by the
     * brain; two of them end the conversation. A sensor stall that persists
     * chatStallGraceMs ends it with the local sign-off. Each turn request has
     * turnBudgetMs, then one retry of turnRetryMs. A line is cut to sentenceCap
     * sentences. The transcript window is transcriptWindow exchanges. The deaf
     * window runs from a line's start to playback idle plus deafTailMs (KTD1),
     * a default until U2 measures it.
     */
    final long unansweredListenMs;
    /**
     * Robot 2026-10-01: how long from its start a listen whose answer has started
     * (CuriosityPort.answering()) is held for the words: the launcher's hard cap on
     * a conversation listen's answer (LauncherProtocol.EARS_LISTEN_HARD_CAP_MS, 60 s) plus 3 s
     * for the decode and the delivery. The brain cannot see shared/, so the value
     * is mirrored here; LauncherProtocol.EARS_ANSWER_HOLD_MS is the one the port uses, and a
     * wiring test pins the two equal.
     */
    final long answerHoldMs;
    /**
     * Robot 2026-10-01: a conversation that opened with no usable face retries the face
     * check on a fresh look up to chatFaceTries times: each try's look is asked for
     * chatFaceDelayMs into a listen (time to crouch down to him), at least chatFaceGapMs
     * after the last try ended. Only a usable face lets him ask the name and store it.
     */
    final int chatFaceTries;
    final long chatFaceDelayMs;
    final long chatFaceGapMs;
    /**
     * Owner 2026-10-02: conversation first. A call opens the conversation at once (no search
     * before it) and he looks for the caller during it, between utterances; false keeps the
     * search-first call (find them, then the meeting). callUtteranceWaitMs: an early wake cue's
     * answer clip waits up to this long for the utterance's end, so its deaf window never cuts
     * off the words said with the wake word (they are the first message); 0 answers at once.
     * callChatUnansweredMax: a call's conversation ends after this many unanswered listens in a
     * row (not seeing them never ends it); a wordless answer gets one "didn't catch that" first.
     */
    final boolean callChatFirst;
    final long callUtteranceWaitMs;
    final int callChatUnansweredMax;
    /**
     * Owner 2026-10-02: a conversation with no message said to him (Claude's "addressed")
     * for this long ends politely, however the unanswered count stands: office chatter
     * nearby kept a call's conversation going for 20+ turns.
     */
    final long chatNoReplyMs;
    final long chatStallGraceMs;
    final long turnBudgetMs;
    final long turnRetryMs;
    /**
     * Robot 2026-10-01: a turn that meets a Claude rate-limit pause this long or shorter
     * says "one sec" and waits it out; a longer pause ends the conversation with the sign-off.
     */
    final long chatPauseWaitMs;
    final int sentenceCap;
    final int transcriptWindow;
    final long deafTailMs;
    /**
     * The conversation's line clips (U8, KTD12: the sign-off, "one sec", the
     * deflection, "nothing kept") each get a clip window of chatClipMs, and an
     * unnamed conversation leaves that side alone for unnamedLeaveAloneMs (KTD10).
     */
    final long chatClipMs;
    final long unnamedLeaveAloneMs;
    /** The first leg after a conversation turns this far away from the person (R16). */
    final double chatAwayDeg;
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
    /**
     * Continuous mode: a leg decision with the camera open (not backed off) and no
     * usable look yet (none taken since the last turn settled, or none fresh) waits
     * in PAUSE up to steerWaitMs for one, so the steer, the doorway and going
     * somewhere new get their say; none in time chooses as before. 0: never waits.
     */
    final long steerWaitMs;
    /**
     * Mid-leg re-aim (owner-approved 2026-09-25; continuous mode, roaming legs
     * only): a fresh look taken during the leg whose best open band (the steer's
     * own scoring) lies at least reaimMinDeg off centre stops the leg, turns (by
     * the gyro) toward it by at most reaimMaxDeg, and drives the leg's remaining
     * ticks. At most one re-aim per reaimGapMs; none with fewer than
     * reaimMinTicks left. reaimMinDeg 0: off.
     */
    final double reaimMinDeg;
    final double reaimMaxDeg;
    final long reaimGapMs;
    final int reaimMinTicks;
    /**
     * CPL hiccups (owner-approved 2026-09-25): the controller refusing forward
     * (CPL=2) mid-leg while our own floor sensor reads plain floor (no edge, no
     * obstacle, no fault) stops him, pauses cplRetryPauseMs and retries the leg's
     * remaining ticks once, not counted as a hazard. CPL again within
     * cplRetryWindowMs of that stop is a hazard as always. cplRetryPauseMs
     * negative: never retried.
     */
    final long cplRetryPauseMs;
    final long cplRetryWindowMs;
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
    /**
     * A visual place memory (owner, 2026-10-01: "if he's been somewhere in the last
     * 30 minutes, he should try and find somewhere else to go"), the complement to
     * the drifting grid. Each look's print (PlaceMemory) is kept for placeFadeMs, at
     * most one per placeRecordMs and placeMax in all (oldest dropped first). A look's
     * similarity is its best match among prints at least placeMinAgeMs old taken
     * within placeHeadingDeg of its heading (any heading when either is unusable).
     * Similarity placeSimLow or less is new (novelty 1), placeSimHigh or more is
     * familiar (novelty 0), linear between; placeSeenSim or more is noted as seen
     * before. A look's novelty also stands for its heading for placeGlanceMs (a scan's
     * looks score the headings it turned through). The steer takes the lower of this
     * and the grid's novelty. placeMax 0 turns it off.
     */
    final long placeFadeMs;
    final int placeMax;
    final long placeRecordMs;
    final long placeMinAgeMs;
    final double placeHeadingDeg;
    final double placeSimLow;
    final double placeSeenSim;
    final double placeSimHigh;
    final long placeGlanceMs;
    /**
     * The camera's upward pitch (measured on the robot, 2026-10-01): a frame position
     * x (-1 left .. 1 right) lies atan2(x tan(cameraHalfFovDeg), cos(cameraPitchDeg))
     * off his facing (RoamSteer.bearingOf), used where an exact bearing matters (the seek).
     */
    final double cameraPitchDeg;
    /**
     * Seeking the unfamiliar (owner, 2026-10-01: "if everything around him is familiar,
     * he should find something that's unfamiliar and drive towards it"). A curiosity
     * stop that ends with nothing new to react to is familiar when at least seekMinLooks
     * of its looks were scored by the place memory and every one of them had novelty
     * seekFamiliarNovelty or less; seekFamiliarScans such stops in a row (0: off) start
     * a seek, at most one per seekGapMs (from the last seek's end). Claude picks the
     * most unexplored-looking place among the stop's frames (seekAskTimeoutMs; offline,
     * none or late: the least familiar frame's most open band). He turns to it and
     * drives legs toward it, the steer adding up to seekWeight to the open bands nearest
     * it, re-centring after each leg on the box Claude named or the most open band
     * within seekRecentreDeg of where it should be. It ends on arrival (a look after a
     * leg with novelty seekArriveNovelty or more, or through a remembered doorway), after
     * seekMaxLegs legs or seekMaxCounts forward counts, when blocked, or when anything
     * takes him out of roaming (a call, a person, a stop).
     */
    final int seekFamiliarScans;
    final double seekFamiliarNovelty;
    final int seekMinLooks;
    final long seekGapMs;
    final long seekAskTimeoutMs;
    final int seekMaxLegs;
    final long seekMaxCounts;
    final double seekRecentreDeg;
    final double seekArriveNovelty;
    final float seekWeight;
    /**
     * The first real seek (robot 2026-10-01 16:00). An open doorway Claude reported in
     * the last seekDoorwayMs (any heading) is the seek's target, before asking Claude
     * where to go or in place of the frame it picked (likely the most unexplored way
     * out), once per report. A target that reads blocked when faced is re-aimed at the
     * most open band within seekBlockedAimDeg of it; nothing open there ends the seek.
     *
     * Openness never vetoes Claude's target (robot 2026-10-01 16:35: open hallway carpet
     * read 0.00, a white wall 0.81). A target Claude chose (its pick, or a doorway it
     * reported; not the least familiar fallback) that reads blocked is driven anyway, in
     * legs of steerShortTicks at most, so the floor sensor, CPL, stall and RECOVER rules
     * get a fresh say often; up to seekTrustedLegsMax such legs per seek (they don't count
     * toward seekMaxLegs; seekMaxCounts still caps the distance). A real hazard or CPL
     * refusal toward it (or a hazard there just now) turns the seek seekBlockedAimDeg off
     * the target, away from the hazard, once; the next one ends it. The same short leg
     * goes toward a remembered doorway outside a seek that reads blocked, and toward a
     * doorway Claude reported in the last seekDoorwayMs when a look-around finds nothing
     * open (instead of boxed in).
     */
    final long seekDoorwayMs;
    final double seekBlockedAimDeg;
    final int seekTrustedLegsMax;
    /**
     * Seeking somewhere new by time and ground (owner, 2026-10-01: "if he's been somewhere
     * in the last 30 minutes, he should try and find somewhere else to go"). Live, Claude
     * had a remark at almost every stop and the place memory read most views as new, so the
     * familiar trigger above never fired in 30+ minutes. Two more triggers, checked as any
     * roaming curiosity stop ends (after its remark, if it had one), each still at most one
     * seek per seekGapMs: seekEveryMs of roaming without a seek (0: off), or, over the last
     * seekAreaWindowMs of roaming (0: off), the dead-reckoned positions he passed through
     * spanning less than seekAreaSpanM along both axes or covering seekAreaCells
     * coverageCellM cells or fewer. The seek then asks Claude about that stop's frames.
     */
    final long seekEveryMs;
    final long seekAreaWindowMs;
    final double seekAreaSpanM;
    final int seekAreaCells;

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
        curiosityRetryMs = b.curiosityRetryMs;
        curiosityBackoffMs = b.curiosityBackoffMs;
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
        phantomPersonCooldownMs = Math.max(0, b.phantomPersonCooldownMs);
        askAttempts = Math.max(1, b.askAttempts);
        claudeLooksPerMinute = Math.max(0, b.claudeLooksPerMinute);
        askTimeoutMs = b.askTimeoutMs;
        sayTimeoutMs = b.sayTimeoutMs;
        quietWaitMs = Math.max(0, b.quietWaitMs);
        heldLineFreshMs = Math.max(0, b.heldLineFreshMs);
        recentPicksMax = Math.max(0, b.recentPicksMax);
        reactedLabelsMax = Math.max(0, b.reactedLabelsMax);
        saidLinesMax = Math.max(0, b.saidLinesMax);
        dockLookMs = Math.max(1000, b.dockLookMs);
        dockOffReadings = Math.max(1, b.dockOffReadings);
        meetTimeoutMs = b.meetTimeoutMs;
        faceHoldMs = Math.max(0, b.faceHoldMs);
        listenMs = b.listenMs;
        listenMarginMs = b.listenMarginMs;
        cueHoldMs = Math.max(0, b.cueHoldMs);
        leanInMs = Math.max(0, b.leanInMs);
        leanInCooldownMs = Math.max(0, b.leanInCooldownMs);
        leanInMinGapMs = Math.max(0, b.leanInMinGapMs);
        boxedInRefusals = Math.max(0, b.boxedInRefusals);
        boxedInWindowMs = Math.max(0, b.boxedInWindowMs);
        boxedInOpen = b.boxedInOpen;
        boxedInLookAround = b.boxedInLookAround;
        lookAroundLooks = Math.max(0, b.lookAroundLooks);
        lookAroundStepDeg = Math.max(1, b.lookAroundStepDeg);
        lookAroundOpen = b.lookAroundOpen;
        lookAroundCooldownMs = Math.max(0, b.lookAroundCooldownMs);
        lookAroundCooldownCounts = Math.max(0, b.lookAroundCooldownCounts);
        boxedAvoidMs = Math.max(0, b.boxedAvoidMs);
        boxedAvoidDeg = Math.max(0, b.boxedAvoidDeg);
        newcomerAngleDeg = Math.max(0f, b.newcomerAngleDeg);
        cueStopBandDeg = Math.max(0f, b.cueStopBandDeg);
        strongCueLooks = Math.max(1, b.strongCueLooks);
        weakCueLooks = Math.max(1, b.weakCueLooks);
        facingFaceMinRatio = Math.max(0f, b.facingFaceMinRatio);
        facingFaceMinHeight = Math.max(0f, b.facingFaceMinHeight);
        cueTurnStepDeg = Math.max(1, b.cueTurnStepDeg);
        cueTurnDefaultDeg = Math.max(0, b.cueTurnDefaultDeg);
        shoveBlankingMs = Math.max(0, b.shoveBlankingMs);
        bumpApologyMs = Math.max(0, b.bumpApologyMs);
        ackClipMs = Math.max(0, b.ackClipMs);
        answerClipMs = Math.max(0, b.answerClipMs);
        callLookMs = Math.max(1, b.callLookMs);
        callStaleLookDeg = Math.max(0, b.callStaleLookDeg);
        callSeenRetargetsMax = Math.max(0, b.callSeenRetargetsMax);
        callBlockedTurnsMax = Math.max(1, b.callBlockedTurnsMax);
        callBlockedTurnDeg = Math.max(0, b.callBlockedTurnDeg);
        jamTurnDeg = Math.max(0, b.jamTurnDeg);
        jammedRestMs = Math.max(1, b.jammedRestMs);
        jamHelpEveryMs = Math.max(0, b.jamHelpEveryMs);
        jamProbeTicks = Math.max(1, b.jamProbeTicks);
        jamMovedCounts = Math.max(1, b.jamMovedCounts);
        jamMovedDeg = Math.max(1, b.jamMovedDeg);
        wriggleMs = Math.max(0, b.wriggleMs);
        wriggleMinCounts = Math.max(1, b.wriggleMinCounts);
        wriggleStillMs = Math.max(100, b.wriggleStillMs);
        wriggleFreeDeg = Math.max(1, b.wriggleFreeDeg);
        wriggleFreeCounts = Math.max(1, b.wriggleFreeCounts);
        wriggleEveryMs = Math.max(0, b.wriggleEveryMs);
        stallRecoverProbesMs = positiveSorted(b.stallRecoverProbesMs);
        stallRecoverProbeMs = Math.max(50, b.stallRecoverProbeMs);
        stallRecoverProbeDeg = Math.max(1, b.stallRecoverProbeDeg);
        stallRecoverProbeBackTicks = Math.max(0, b.stallRecoverProbeBackTicks);
        blockedTurnWaitMs = Math.max(0, b.blockedTurnWaitMs);
        jamProbeAtMs = positiveSorted(b.jamProbeAtMs);
        stallRecoverWindowMs = Math.max(0, b.stallRecoverWindowMs);
        recoverMaxPerSpell = Math.max(1, b.recoverMaxPerSpell);
        recoverSpellResetMs = Math.max(0, b.recoverSpellResetMs);
        recoverSpellResetCounts = Math.max(0, b.recoverSpellResetCounts);
        callPersonMinHeight = Math.max(0f, b.callPersonMinHeight);
        callPersonMinScore = Math.max(0f, b.callPersonMinScore);
        callNearHeight = Math.max(0f, b.callNearHeight);
        callListenMs = Math.max(1, b.callListenMs);
        whereClipMs = Math.max(0, b.whereClipMs);
        headingHistoryMs = Math.max(0, b.headingHistoryMs);
        headingSampleMs = Math.max(10, b.headingSampleMs);
        unansweredListenMs = Math.max(1, b.unansweredListenMs);
        answerHoldMs = Math.max(unansweredListenMs, b.answerHoldMs);
        chatFaceTries = Math.max(0, b.chatFaceTries);
        chatFaceDelayMs = Math.max(0, b.chatFaceDelayMs);
        chatFaceGapMs = Math.max(0, b.chatFaceGapMs);
        callChatFirst = b.callChatFirst;
        callUtteranceWaitMs = Math.max(0, b.callUtteranceWaitMs);
        callChatUnansweredMax = Math.max(1, b.callChatUnansweredMax);
        chatNoReplyMs = Math.max(0, b.chatNoReplyMs);
        chatStallGraceMs = Math.max(0, b.chatStallGraceMs);
        turnBudgetMs = Math.max(1, b.turnBudgetMs);
        turnRetryMs = Math.max(0, b.turnRetryMs);
        chatPauseWaitMs = Math.max(0, b.chatPauseWaitMs);
        sentenceCap = Math.max(1, b.sentenceCap);
        transcriptWindow = Math.max(1, b.transcriptWindow);
        deafTailMs = Math.max(0, b.deafTailMs);
        chatClipMs = Math.max(0, b.chatClipMs);
        unnamedLeaveAloneMs = Math.max(0, b.unnamedLeaveAloneMs);
        chatAwayDeg = Math.max(1, b.chatAwayDeg);
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
        steerWaitMs = Math.max(0, b.steerWaitMs);
        reaimMinDeg = Math.max(0, b.reaimMinDeg);
        reaimMaxDeg = Math.max(b.reaimMinDeg, b.reaimMaxDeg);
        reaimGapMs = Math.max(0, b.reaimGapMs);
        reaimMinTicks = Math.max(1, b.reaimMinTicks);
        cplRetryPauseMs = b.cplRetryPauseMs;
        cplRetryWindowMs = Math.max(0, b.cplRetryWindowMs);
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
        placeFadeMs = Math.max(1, b.placeFadeMs);
        placeMax = Math.max(0, b.placeMax);
        placeRecordMs = Math.max(0, b.placeRecordMs);
        placeMinAgeMs = Math.max(0, b.placeMinAgeMs);
        placeHeadingDeg = b.placeHeadingDeg;
        placeSimLow = b.placeSimLow;
        placeSeenSim = b.placeSeenSim;
        placeSimHigh = Math.max(b.placeSimLow + 1e-3, b.placeSimHigh);
        placeGlanceMs = Math.max(0, b.placeGlanceMs);
        cameraPitchDeg = b.cameraPitchDeg;
        seekFamiliarScans = Math.max(0, b.seekFamiliarScans);
        seekFamiliarNovelty = b.seekFamiliarNovelty;
        seekMinLooks = Math.max(1, b.seekMinLooks);
        seekGapMs = Math.max(0, b.seekGapMs);
        seekAskTimeoutMs = Math.max(1, b.seekAskTimeoutMs);
        seekMaxLegs = Math.max(1, b.seekMaxLegs);
        seekMaxCounts = Math.max(1, b.seekMaxCounts);
        seekRecentreDeg = Math.max(0, b.seekRecentreDeg);
        seekDoorwayMs = Math.max(0, b.seekDoorwayMs);
        seekBlockedAimDeg = Math.max(0, b.seekBlockedAimDeg);
        seekTrustedLegsMax = Math.max(1, b.seekTrustedLegsMax);
        seekArriveNovelty = b.seekArriveNovelty;
        seekWeight = Math.max(0f, b.seekWeight);
        seekEveryMs = Math.max(0, b.seekEveryMs);
        seekAreaWindowMs = Math.max(0, b.seekAreaWindowMs);
        seekAreaSpanM = Math.max(0, b.seekAreaSpanM);
        seekAreaCells = Math.max(0, b.seekAreaCells);
    }

    /** The shipped defaults with the given calibration (null = uncalibrated). */
    /** The positive values, ascending (a recovery probe at or before the stall is meaningless). */
    private static long[] positiveSorted(long[] v) {
        long[] out = new long[v == null ? 0 : v.length];
        int n = 0;
        for (int i = 0; i < out.length; i++) {
            if (v[i] > 0) {
                out[n++] = v[i];
            }
        }
        out = java.util.Arrays.copyOf(out, n);
        java.util.Arrays.sort(out);
        return out;
    }

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
        // 18-28 s of wandering from one stop's end to the next (a stop itself takes
        // about 8-15 s): the owner wants a remark about every 30 s when nobody is
        // talking to him (robot 2026-10-01: 45-90 s, with the losses below, gave one
        // every 5-15 min).
        private long curiosityMinMs = 18000;
        private long curiosityMaxMs = 28000;
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
        // One slow look in a stop is retried soon; two in a row are a camera worth resting, briefly.
        private long curiosityRetryMs = 3000;
        private long curiosityBackoffMs = 30000;
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
        // Robot 2026-09-30: four phantom meetings in four minutes; 20 s lets him roam off the blur.
        private long phantomPersonCooldownMs = 20000;
        // Two tries of about 10 s (R7): a stop with Claude unreachable falls back
        // within ~20 s (AE4). Owner's call after live tests: a look usually takes ~3 s,
        // and a slow one is retried rather than waited on longer.
        private int askAttempts = 2;
        private int claudeLooksPerMinute = 0; // off (owner, 2026-10-01: too much throttling)
        private long askTimeoutMs = 10000;
        // A few seconds of speech (R5), plus the launcher's synthesis; only a backstop.
        private long sayTimeoutMs = 15000;
        private long quietWaitMs = 1500;
        // Long enough for a startle, back-off and escape turn; stale after that.
        private long heldLineFreshMs = 30000;
        private int recentPicksMax = 8;
        private int reactedLabelsMax = 20;
        private int saidLinesMax = 10;
        // About one look a minute on the charger: new things get noticed, the look budget barely notices.
        private long dockLookMs = 60000;
        // Two off readings in a row leave the dock: one stray reading is not enough.
        private int dockOffReadings = 2;
        // One try each; the match sends up to 11 small images, so it gets a little longer than a look try.
        private long meetTimeoutMs = 12000;
        // KTD11: the migration usually takes a few seconds; 30 s caps the wait for the first roam.
        private long faceHoldMs = 30000;
        // KTD4's 6 s cap, and room for the launcher to wait out the speech queue and decode.
        private long listenMs = 6000;
        private long listenMarginMs = 6000;
        // Meeting plan Assumptions: a held cue lasts 10 s; the lean-in has 4 s after the camera is ready;
        // a newcomer needs an angle magnitude above 45 degrees; the turn stops inside about 10 degrees;
        // a strong cue gets three looks and a weak cue two.
        private long cueHoldMs = 10000;
        private long leanInMs = 4000;
        // Robot 2026-10-01: a voice every ~20 s restarted a lean-in each time; a minute's quiet after one that
        // found nobody, and never more than one per 30 s.
        private long leanInCooldownMs = 60000;
        private long leanInMinGapMs = 30000;
        // Robot 2026-10-01 15:12-15:17: 8 refusals in 5 min, steer "open 0.00"/"turn only" for most decisions.
        private int boxedInRefusals = 3;
        private long boxedInWindowMs = 60000;
        private double boxedInOpen = 0.1;
        private boolean boxedInLookAround = true;
        private int lookAroundLooks = 6;
        private double lookAroundStepDeg = 60;
        private float lookAroundOpen = 0.5f;
        private long lookAroundCooldownMs = 60000;
        private long lookAroundCooldownCounts = 1500;
        private long boxedAvoidMs = 60000;
        private double boxedAvoidDeg = 45;
        private float newcomerAngleDeg = 45f;
        private float cueStopBandDeg = 10f;
        private int strongCueLooks = 3;
        private int weakCueLooks = 2;
        // Placeholders until U2's captures: a frontal face box is about as wide as it is tall
        // (0.75 to 0.85), a profile one narrower (about 0.55); 1.5 m fills about an eighth of the frame.
        private float facingFaceMinRatio = 0.65f;
        private float facingFaceMinHeight = 0.12f;
        // Meeting plan U7: 45-degree steps toward the voice, a quarter turn when the mics only gave
        // a side, half a second of blanking after a motor command, KTD3's 2 s apology window, and
        // a short "hm?" (the asset is U8's; the window is opened either way).
        private double cueTurnStepDeg = 45;
        private double cueTurnDefaultDeg = 90;
        private long shoveBlankingMs = 500;
        private long bumpApologyMs = 2000;
        private long ackClipMs = 600;
        // Hey-miko plan KTD3: the answer clips run about half a second.
        private long answerClipMs = 600;
        // Hey-miko plan U6, KTD6 and KTD9: PLACEHOLDERS until U7's QA on the robot. A 5000 ms
        // cap on a look with no fresh frame (robot 2026-09-30: detection took 1.8-4.7 s a frame and
        // the camera skips frames while one is detected, so the first frame captured after a stop
        // was often published 2-5 s later; QA earlier the same day saw "look in 2353 ms", "look in
        // 1392 ms", "look in 1984 ms", when the old 700 ms ended most stops before any fresh frame);
        // a stale look with the caller in it counts when captured within 30 deg of where he faces
        // (the plan's looks are 45 deg apart); a person box an eighth of the frame tall is someone; under
        // 0.35 of it (the facing box at about 1.5 m is 0.4) is far enough to go over to; a 4 s listen
        // after "Where'd you go?" (react-where-1.webm is 0.8 s). KTD7's heading history
        // keeps 30 s (a call handed back by an escape is retaken with it), a sample a reading.
        private long callLookMs = 5000;
        private double callStaleLookDeg = 30;
        // One turn to a seen caller, one more if a later look shows them again: no ping-pong.
        private int callSeenRetargetsMax = 2;
        private int callBlockedTurnsMax = 2;
        private double callBlockedTurnDeg = 10;
        private double jamTurnDeg = 10;
        private long jammedRestMs = 120000;
        private long jamHelpEveryMs = 300000;
        // ~1 s at backTickMs: gentle, half the escape's first back-up.
        private int jamProbeTicks = 4;
        private long jamMovedCounts = 30;
        private double jamMovedDeg = 25;
        // Robot 2026-10-01: an 11 s turn freed him where 1.5 s ones read "turned 0".
        private long wriggleMs = 10000;
        // A wedged wheel that truly turns moves hundreds of counts a second (~4000 in 20 s).
        private long wriggleMinCounts = 30;
        private long wriggleStillMs = 1500;
        private double wriggleFreeDeg = 20;
        private long wriggleFreeCounts = 800;
        private long wriggleEveryMs = 120000;
        // Robot 2026-10-01: the board came back between ~9 s and 29 s after the stall. The 2 s
        // probe keeps a stall with no cutout (the common case on the desk) to ~2.5 s of waiting.
        private long[] stallRecoverProbesMs = {2000, 5000, 10000, 20000};
        // Half a second of turning: ~100 counts on a live board, too little to push a latched one.
        private long stallRecoverProbeMs = 500;
        private double stallRecoverProbeDeg = 5;
        // Two back ticks: about 50 counts a wheel on a live board, a few cm back the way he came.
        private int stallRecoverProbeBackTicks = 2;
        // About half the 6 s cutout a blocked turn re-arms, after the back-up's own time.
        private long blockedTurnWaitMs = 3000;
        // Robot 15:19: probing only at 120 s left him stuck for minutes after the cutout cleared.
        private long[] jamProbeAtMs = {30000, 60000, 120000};
        // Just past the longest cutout seen (29 s).
        private long stallRecoverWindowMs = 30000;
        private int recoverMaxPerSpell = 3;
        private long recoverSpellResetMs = 45000;
        private long recoverSpellResetCounts = 300;
        private float callPersonMinHeight = 0.12f;
        // Robot QA 2026-09-30: a floor-level caller scored person 0.27 and 0.32.
        private float callPersonMinScore = 0.25f;
        private float callNearHeight = 0.35f;
        private long callListenMs = 4000;
        private long whereClipMs = 900;
        private long headingHistoryMs = 30000;
        private long headingSampleMs = 100;
        // Meeting plan Assumptions: an unanswered listen is 4 s; the chat sensor-stall grace 5 s;
        // a turn has 5 s plus a 3 s retry; two sentences; a window of 30 exchanges; a 500 ms deaf tail.
        private long unansweredListenMs = 4000;
        // Robot 2026-10-01: LauncherProtocol.EARS_ANSWER_HOLD_MS (the answer cap, 60 s since 2026-10-02, plus 3 s).
        private long answerHoldMs = 63000;
        // Robot 2026-10-01: about three tries, a few seconds apart, after the crouch invitation.
        private int chatFaceTries = 3;
        private long chatFaceDelayMs = 1000;
        private long chatFaceGapMs = 3000;
        // Owner 2026-10-02: conversation first; the answer waits for the wake utterance's end
        // (a worded "Hey Miko, ..." runs about 1.5-2 s past the wake); three unanswered listens.
        private boolean callChatFirst = true;
        private long callUtteranceWaitMs = 2500;
        private int callChatUnansweredMax = 3;
        private long chatNoReplyMs = 45000;
        private long chatStallGraceMs = 5000;
        private long turnBudgetMs = 5000;
        private long turnRetryMs = 3000;
        private long chatPauseWaitMs = 10000;
        private int sentenceCap = 2;
        private int transcriptWindow = 30;
        private long deafTailMs = 500;
        private long chatClipMs = 1500;
        private long unnamedLeaveAloneMs = 120000;
        private double chatAwayDeg = 120;
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
        // Measured on the robot (2026-10-01, scripts/calibrate-camera-fov.py, 6 turn
        // pairs): a 62.6 deg horizontal view, focal length about 526 px on the 640 px
        // frame, with the camera pitched up about 18 deg. Half of 62.6; the linear
        // centerX x halfFov mapping is within about 2 deg of the exact bearing.
        private double cameraHalfFovDeg = 31.3;
        private double cameraPitchDeg = 18;
        private int legsMax = 16;
        private Navigation navigation = Navigation.CONTINUOUS;
        // A live look takes ~0.6 s and up to ~2.5 s; older than this he has moved on.
        private long steerLookFreshMs = 3000;
        // Looks arrive every ~1-2 s live: after a turn settles (lookSettleMs) the
        // next one is usually within this.
        private long steerWaitMs = 2000;
        // The drive can't curve: a small in-place turn toward open space mid-leg.
        private double reaimMinDeg = 15;
        private double reaimMaxDeg = 30;
        private long reaimGapMs = 2000;
        private int reaimMinTicks = 2;
        // Live, the controller refuses forward on open carpet as the nose dips at
        // a start or stop, with our own tof reading ordinary floor.
        private long cplRetryPauseMs = 400;
        private long cplRetryWindowMs = 1500;
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
        // 30 minutes (owner, 2026-10-01: "if he's been somewhere in the last 30 minutes,
        // he should try and find somewhere else to go"; was a few minutes under R18).
        // Dead-reckoning drift builds up over that long, so the grid is only a rough
        // guide; the visual place memory (PlaceMemory, place* below) is its complement.
        private long coverageFadeMs = 1800000;
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
        // The same 30 minutes as the grid. 300 prints of ~1 KB; one every 6 s fills 30 min.
        private long placeFadeMs = 1800000;
        private int placeMax = 300;
        private long placeRecordMs = 6000;
        // Looks a minute apart: the view he just had is not a revisit.
        private long placeMinAgeMs = 60000;
        private double placeHeadingDeg = 45;
        // Set on 624 real frames from the 2026-10-01 roam (113 hand-labelled pairs, and 42
        // looks' best matches): at 0.75, 0.78 precision and 0.74 recall; nearly every pair
        // at 0.85 or more was the same place, and pairs at 0.65 or less hardly ever were.
        private double placeSimLow = 0.65;
        private double placeSeenSim = 0.75;
        private double placeSimHigh = 0.85;
        // Seeking the unfamiliar: two familiar stops in a row (one scan can be a wall or a
        // corner), every scored look at 0.3 or less (similarity 0.79 or more: nearly always
        // the same place on the 2026-10-01 frames), at most once every three minutes.
        private int seekFamiliarScans = 2;
        private double seekFamiliarNovelty = 0.3;
        private int seekMinLooks = 2;
        private long seekGapMs = 180000;
        // As the doorway ask: a few frames take Claude ~3-10 s live.
        private long seekAskTimeoutMs = 15000;
        // About 3 m (coverageCountsPerMetre 3000) or six legs, whichever comes first.
        private int seekMaxLegs = 6;
        private long seekMaxCounts = 9000;
        private double seekRecentreDeg = 20;
        private long seekDoorwayMs = 180000;
        private double seekBlockedAimDeg = 45;
        private int seekTrustedLegsMax = 16;
        // As coverageNovelAhead: a view at least this new is somewhere else.
        private double seekArriveNovelty = 0.7;
        // Twice the doorway's pull: the target beats an equally open band anywhere in view,
        // but a blocked band never gains.
        private float seekWeight = 0.6f;
        // Five minutes of roaming without a seek, or three minutes within about two body
        // lengths (1.5 m, three 0.5 m cells): he looks for somewhere new.
        private long seekEveryMs = 300000;
        private long seekAreaWindowMs = 180000;
        private double seekAreaSpanM = 1.5;
        private int seekAreaCells = 3;
        // A scan's looks stand for their headings about as long as a stop and the next leg.
        private long placeGlanceMs = 120000;

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
        Builder curiosityRetry(long retryMs, long backoffMs) {
            curiosityRetryMs = retryMs;
            curiosityBackoffMs = backoffMs;
            return this;
        }
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
        Builder phantomPersonCooldownMs(long v) { phantomPersonCooldownMs = v; return this; }
        Builder leanInCooldown(long cooldownMs, long minGapMs) {
            leanInCooldownMs = cooldownMs;
            leanInMinGapMs = minGapMs;
            return this;
        }
        Builder boxedIn(int refusals, long windowMs, double open, boolean lookAround) {
            boxedInRefusals = refusals;
            boxedInWindowMs = windowMs;
            boxedInOpen = open;
            boxedInLookAround = lookAround;
            return this;
        }
        Builder lookAround(int looks, double stepDeg, float open) {
            lookAroundLooks = looks;
            lookAroundStepDeg = stepDeg;
            lookAroundOpen = open;
            return this;
        }
        /** No look-around: a closed view gets the steer's blind turns, as before 2026-10-01 15:55. */
        Builder lookAroundOff() { lookAroundLooks = 0; return this; }
        /** Review P2-5: the look-around cooldown (0 ms: none, every closed decision looks around). */
        Builder lookAroundCooldown(long ms, long counts) {
            lookAroundCooldownMs = ms;
            lookAroundCooldownCounts = counts;
            return this;
        }
        Builder boxedAvoid(long ms, double deg) { boxedAvoidMs = ms; boxedAvoidDeg = deg; return this; }
        /** Never boxed in: the refusals and closed look-arounds run as before 2026-10-01. */
        Builder boxedInOff() { boxedInRefusals = 0; boxedInLookAround = false; return this; }
        Builder callSeenRetargetsMax(int v) { callSeenRetargetsMax = v; return this; }
        Builder callBlockedTurns(int max, double deg) { callBlockedTurnsMax = max; callBlockedTurnDeg = deg; return this; }
        Builder jam(double turnDeg, long restMs, long helpEveryMs, int probeTicks) {
            jamTurnDeg = turnDeg;
            jammedRestMs = restMs;
            jamHelpEveryMs = helpEveryMs;
            jamProbeTicks = probeTicks;
            return this;
        }
        Builder jamMoved(long counts, double deg) { jamMovedCounts = counts; jamMovedDeg = deg; return this; }
        /** No jam detection: a fully jammed escape runs the ladder and its rests, as before 2026-10-01. */
        Builder jamOff() { jamTurnDeg = 0; return this; }
        Builder wriggle(long ms, long minCounts, long stillMs) {
            wriggleMs = ms;
            wriggleMinCounts = minCounts;
            wriggleStillMs = stillMs;
            return this;
        }
        Builder wriggleFree(double deg, long counts) { wriggleFreeDeg = deg; wriggleFreeCounts = counts; return this; }
        Builder wriggleEveryMs(long v) { wriggleEveryMs = v; return this; }
        /** No long wriggle: fully jammed goes straight to the help line, as before 2026-10-01's chair. */
        Builder wriggleOff() { wriggleMs = 0; return this; }
        /** The recovery wait's probe times, in ms after the stall (each > 0). */
        Builder stallRecover(long... probesMs) { stallRecoverProbesMs = probesMs.clone(); return this; }
        Builder stallRecoverProbe(long ms, double deg) {
            stallRecoverProbeMs = ms;
            stallRecoverProbeDeg = deg;
            return this;
        }
        Builder stallRecoverWindowMs(long v) { stallRecoverWindowMs = v; return this; }
        Builder recoverMaxPerSpell(int v) { recoverMaxPerSpell = v; return this; }
        Builder chatNoReplyMs(long v) { chatNoReplyMs = v; return this; }
        Builder recoverSpellReset(long ms, long counts) {
            recoverSpellResetMs = ms;
            recoverSpellResetCounts = counts;
            return this;
        }
        Builder stallRecoverProbeBackTicks(int v) { stallRecoverProbeBackTicks = v; return this; }
        Builder blockedTurnWaitMs(long v) { blockedTurnWaitMs = v; return this; }
        /** The jammed rest's probe times, in ms after he was found jammed; then every jammedRestMs. */
        Builder jamProbeAt(long... ms) { jamProbeAtMs = ms.clone(); return this; }
        /** No recovery wait: the escape runs at once after a stall, as before 2026-10-01's cutouts. */
        Builder stallRecoverOff() { stallRecoverProbesMs = new long[0]; return this; }
        Builder ask(int attempts, long timeoutMs) { askAttempts = attempts; askTimeoutMs = timeoutMs; return this; }
        Builder claudeLooksPerMinute(int v) { claudeLooksPerMinute = v; return this; }
        Builder sayTimeoutMs(long v) { sayTimeoutMs = v; return this; }
        Builder quietWaitMs(long v) { quietWaitMs = v; return this; }
        Builder heldLineFreshMs(long v) { heldLineFreshMs = v; return this; }
        Builder recentPicksMax(int v) { recentPicksMax = v; return this; }
        Builder reactedLabelsMax(int v) { reactedLabelsMax = v; return this; }
        Builder saidLinesMax(int v) { saidLinesMax = v; return this; }
        Builder dockLookMs(long v) { dockLookMs = v; return this; }
        Builder dockOffReadings(int v) { dockOffReadings = v; return this; }
        Builder meet(long timeoutMs, long listenMs, long listenMarginMs) {
            meetTimeoutMs = timeoutMs;
            this.listenMs = listenMs;
            this.listenMarginMs = listenMarginMs;
            return this;
        }
        Builder faceHoldMs(long v) { faceHoldMs = v; return this; }
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
        Builder steerWaitMs(long v) { steerWaitMs = v; return this; }
        Builder reaim(double minDeg, double maxDeg, long gapMs, int minTicks) {
            reaimMinDeg = minDeg;
            reaimMaxDeg = maxDeg;
            reaimGapMs = gapMs;
            reaimMinTicks = minTicks;
            return this;
        }
        Builder reaimOff() { reaimMinDeg = 0; return this; }
        Builder cplRetry(long pauseMs, long windowMs) { cplRetryPauseMs = pauseMs; cplRetryWindowMs = windowMs; return this; }
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
        Builder placeMemory(long fadeMs, int max, long recordMs, long minAgeMs) {
            placeFadeMs = fadeMs;
            placeMax = max;
            placeRecordMs = recordMs;
            placeMinAgeMs = minAgeMs;
            return this;
        }
        Builder placeMatch(double headingDeg, double simLow, double seenSim, double simHigh, long glanceMs) {
            placeHeadingDeg = headingDeg;
            placeSimLow = simLow;
            placeSeenSim = seenSim;
            placeSimHigh = simHigh;
            placeGlanceMs = glanceMs;
            return this;
        }
        Builder cameraPitchDeg(double v) { cameraPitchDeg = v; return this; }
        Builder seekTrigger(int familiarScans, double familiarNovelty, int minLooks, long gapMs) {
            seekFamiliarScans = familiarScans;
            seekFamiliarNovelty = familiarNovelty;
            seekMinLooks = minLooks;
            seekGapMs = gapMs;
            return this;
        }
        Builder seekDrive(int maxLegs, long maxCounts, double recentreDeg, double arriveNovelty, float weight) {
            seekMaxLegs = maxLegs;
            seekMaxCounts = maxCounts;
            seekRecentreDeg = recentreDeg;
            seekArriveNovelty = arriveNovelty;
            seekWeight = weight;
            return this;
        }
        Builder seekAskTimeoutMs(long v) { seekAskTimeoutMs = v; return this; }
        Builder seekDoorway(long ms, double blockedAimDeg) {
            seekDoorwayMs = ms;
            seekBlockedAimDeg = blockedAimDeg;
            return this;
        }
        Builder seekTrustedLegsMax(int v) { seekTrustedLegsMax = v; return this; }
        /** Never seeks the unfamiliar: roaming as before it. */
        Builder seekOff() {
            seekFamiliarScans = 0;
            seekEveryMs = 0;
            seekAreaWindowMs = 0;
            return this;
        }
        /** A seek after everyMs of roaming without one (0: never by time). */
        Builder seekEvery(long everyMs) { seekEveryMs = everyMs; return this; }
        /** A seek when the last windowMs of roaming stayed within spanM, or within cells cells (windowMs 0: never). */
        Builder seekArea(long windowMs, double spanM, int cells) {
            seekAreaWindowMs = windowMs;
            seekAreaSpanM = spanM;
            seekAreaCells = cells;
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

        Builder cues(long holdMs, long leanInMs, float newcomerAngleDeg, float stopBandDeg, int strongLooks,
                     int weakLooks) {
            this.cueHoldMs = holdMs;
            this.leanInMs = leanInMs;
            this.newcomerAngleDeg = newcomerAngleDeg;
            this.cueStopBandDeg = stopBandDeg;
            this.strongCueLooks = strongLooks;
            this.weakCueLooks = weakLooks;
            return this;
        }
        Builder facingFace(float minRatio, float minHeight) {
            this.facingFaceMinRatio = minRatio;
            this.facingFaceMinHeight = minHeight;
            return this;
        }
        Builder cueTurn(double stepDeg, double defaultDeg, long shoveBlankingMs, long bumpApologyMs, long ackClipMs) {
            this.cueTurnStepDeg = stepDeg;
            this.cueTurnDefaultDeg = defaultDeg;
            this.shoveBlankingMs = shoveBlankingMs;
            this.bumpApologyMs = bumpApologyMs;
            this.ackClipMs = ackClipMs;
            return this;
        }
        Builder answerClipMs(long ms) {
            this.answerClipMs = ms;
            return this;
        }
        Builder call(long lookMs, float personMinHeight, float nearHeight, long listenMs) {
            this.callLookMs = lookMs;
            this.callPersonMinHeight = personMinHeight;
            this.callNearHeight = nearHeight;
            this.callListenMs = listenMs;
            return this;
        }
        Builder callPersonMinScore(float score) {
            this.callPersonMinScore = score;
            return this;
        }
        Builder whereClipMs(long ms) {
            this.whereClipMs = ms;
            return this;
        }
        Builder headingHistory(long historyMs, long sampleMs) {
            this.headingHistoryMs = historyMs;
            this.headingSampleMs = sampleMs;
            return this;
        }
        Builder chatPauseWaitMs(long v) { chatPauseWaitMs = v; return this; }
        Builder chat(long unansweredListenMs, long stallGraceMs, long turnBudgetMs, long turnRetryMs, int sentenceCap,
                     int transcriptWindow, long deafTailMs) {
            this.unansweredListenMs = unansweredListenMs;
            this.chatStallGraceMs = stallGraceMs;
            this.turnBudgetMs = turnBudgetMs;
            this.turnRetryMs = turnRetryMs;
            this.sentenceCap = sentenceCap;
            this.transcriptWindow = transcriptWindow;
            this.deafTailMs = deafTailMs;
            return this;
        }

        Builder callChatFirst(boolean on) {
            this.callChatFirst = on;
            return this;
        }

        Builder callUtteranceWaitMs(long ms) {
            this.callUtteranceWaitMs = ms;
            return this;
        }

        Builder callChatUnansweredMax(int n) {
            this.callChatUnansweredMax = n;
            return this;
        }

        Builder answerHoldMs(long ms) {
            this.answerHoldMs = ms;
            return this;
        }

        Builder chatClips(long chatClipMs, long unnamedLeaveAloneMs, double chatAwayDeg) {
            this.chatClipMs = chatClipMs;
            this.unnamedLeaveAloneMs = unnamedLeaveAloneMs;
            this.chatAwayDeg = chatAwayDeg;
            return this;
        }

        ExploreTuning build() {
            return new ExploreTuning(this);
        }
    }
}
