package com.miko3.mode.explore;

import java.util.ArrayDeque;
import java.util.Random;

/**
 * The explore mode's whole behavior: when to pause, look, turn, hop, startle
 * and back off (U3; KTD4, KTD5, KTD8, KTD10). Plain Java with no android.*
 * imports and everything injected -- clock, motors, eyes, sound, randomness --
 * so every rule runs on the host JVM in scripts/tests
 * (fixtures/explore_brain_harness). ExploreLoop (U5) owns the instance and
 * makes every call from its one thread; this class is not thread-safe. A
 * callback made from inside one of the brain's own motor calls (the drive
 * adapter reporting a lost lease) is safe: it is applied as soon as the
 * current step returns, before any further command.
 *
 * States, following the Key Flows diagram:
 *
 *   EYES_ONLY  sensors unavailable or no lease: never drives, eyes show STILL (R10, R5)
 *   PAUSE      standing, eyes glancing (R2); ends on a fresh reading
 *   LOOK       eyes on the new heading for lookLeadMs before the turn (R7)
 *   TURN       turning in place for a fixed duration (R3)
 *   HOP        hopTicks forward ticks, hopTickMs apart, then stop (R1)
 *   STARTLE    stopped, startle clip and flinch (R11)
 *   BACK_OFF   the only reversing: backTicks, time-bounded and blind (R4, R12)
 *   CORNERED   too many hazards too fast: resting eyes, no motion (KTD8)
 *   STOPPED    after shutdown(); inert
 *
 * The rules that make it safe:
 *  - It decides on every reading and every tick, so the stop that follows a
 *    hazard or a lost feed happens in the same event that revealed it.
 *  - Hops and turns start only in onReading, on a fresh reading, and a hop or
 *    a discretionary turn only on a clear one. A hazard at that moment means
 *    no hop: it turns away instead.
 *  - Any hazard aborts a hop or a discretionary turn. A turn away from a
 *    hazard is allowed to start with the hazard still in view (that is how it
 *    escapes); it is aborted only if a hazard reappears after the view cleared.
 *  - Unavailable sensors or a lost lease stop everything, from every state, and
 *    driving resumes only through PAUSE, never mid-motion.
 *  - CPL=2 (the controller refusing forward, R9) is a hazard like any other:
 *    the brain never retries forward against it, and the cornered cap stops a
 *    stuck CPL=2 from looping.
 *
 * It keeps no stop timer of its own: the drive adapter (U5, KTD6) stops the
 * motors if the brain stops calling it.
 */
final class ExploreBrain {
    interface Clock {
        long nowMs();
    }

    /** The drive adapter (U5). Commands go out only under the lease; stop() always goes out. */
    interface Motor {
        /** One forward tick: driveContinuous(+2,0), which must be resent every hopTickMs. */
        void hopTick();

        /** Start turning in place; self-sustaining until stop(). */
        void turn(Direction direction);

        /** One reverse tick. */
        void backTick();

        void stop();
    }

    /** The eyes page's state (U6). gaze is null except for LOOK. */
    interface Eyes {
        void show(EyeState state, Direction gaze);
    }

    interface Sound {
        void playStartle();
    }

    /** Where transitions are narrated, for logcat. */
    interface Trace {
        void note(String message);
    }

    enum Direction {
        LEFT, RIGHT;

        Direction opposite() {
            return this == LEFT ? RIGHT : LEFT;
        }
    }

    /** What the eyes show: glancing, leading a turn, startled, resting (cornered), not moving (R10). */
    enum EyeState { IDLE, LOOK, FLINCH, RESTING, STILL }

    enum State { EYES_ONLY, PAUSE, LOOK, TURN, HOP, STARTLE, BACK_OFF, CORNERED, STOPPED }

    private final ExploreTuning tuning;
    private final Clock clock;
    private final Motor motor;
    private final Eyes eyes;
    private final Sound sound;
    private final Random random;
    private final HazardClassifier classifier;
    /** Times of the hazard reactions since the last successful hop, for the cornered cap. */
    private final ArrayDeque<Long> hazardTimes = new ArrayDeque<Long>();

    private Trace trace;
    private State state = State.EYES_ONLY;
    private boolean started;
    private boolean leaseHeld;
    private boolean moving;
    private boolean stepping;
    private boolean again;

    /** When the current state's timed part ends: pause, look lead, turn, hop, startle, back-off, rest. */
    private long phaseUntil;
    private long nextTickAt;
    private int ticksLeft;

    /** The heading being looked at and turned to, and whether that turn is an escape. */
    private Direction heading;
    private boolean escape;
    private long turnMs;
    private boolean sawClearDuringTurn;
    /** A discretionary turn just ended: the next move is its hop, eyes still on the heading. */
    private boolean hopNext;
    /** Which way the post-startle turn goes, and whether the startle tripped the cap. */
    private Direction escapeDir;
    private boolean corneredAfterStartle;
    /** Side of the last hazard, so the next discretionary turn steers away from it. */
    private Direction lastHazardSide;

    private EyeState shownState;
    private Direction shownGaze;

    ExploreBrain(ExploreTuning tuning, Clock clock, Motor motor, Eyes eyes, Sound sound, Random random) {
        this.tuning = tuning;
        this.clock = clock;
        this.motor = motor;
        this.eyes = eyes;
        this.sound = sound;
        this.random = random;
        this.classifier = new HazardClassifier(tuning);
    }

    void setTrace(Trace trace) {
        this.trace = trace;
    }

    State state() {
        return state;
    }

    /** Starts in EYES_ONLY; it leaves only once the lease is held and the sensors are available. */
    void start() {
        if (started) {
            return;
        }
        started = true;
        enterEyesOnly(classifier.reason());
    }

    /** A new reading from the keepalive reply. Motion only ever starts here. */
    void onReading(SensorReading reading) {
        if (state == State.STOPPED) {
            return;
        }
        classifier.offer(reading);
        step(true);
    }

    /** The loop's regular tick, well under a hop tick apart: timing, staleness, and ticks. */
    void onTick() {
        step(false);
    }

    /** The drive lease came or went (U5). Losing it stops the robot; regaining it restarts at PAUSE. */
    void onLeaseChanged(boolean held) {
        if (state == State.STOPPED) {
            return;
        }
        leaseHeld = held;
        step(false);
    }

    /** Exit (R5): stop the motors and ignore everything from now on. */
    void shutdown() {
        if (state == State.STOPPED) {
            return;
        }
        moving = false;
        motor.stop();
        state = State.STOPPED;
        note("shutdown");
    }

    // ---- the step ----

    private void step(boolean fresh) {
        if (!started || state == State.STOPPED) {
            return;
        }
        if (stepping) {
            // Called back from inside one of our own motor calls: finish this step, then re-run.
            again = true;
            return;
        }
        stepping = true;
        try {
            boolean f = fresh;
            do {
                again = false;
                stepOnce(f);
                f = false;
            } while (again && state != State.STOPPED);
        } finally {
            stepping = false;
        }
    }

    private void stepOnce(boolean fresh) {
        long now = clock.nowMs();
        HazardClassifier.Status s = classifier.status(now);
        if (state == State.EYES_ONLY) {
            if (leaseHeld && s != HazardClassifier.Status.UNAVAILABLE) {
                note("sensors available and lease held");
                enterPause(now, pauseMs(), false);
            }
            return;
        }
        if (!leaseHeld) {
            enterEyesOnly("lease lost");
            return;
        }
        if (s == HazardClassifier.Status.UNAVAILABLE) {
            enterEyesOnly("sensors unavailable: " + classifier.reason());
            return;
        }
        boolean hazard = s == HazardClassifier.Status.HAZARD;
        switch (state) {
            case PAUSE:
                if (fresh && now >= phaseUntil) {
                    decide(now, hazard);
                }
                break;
            case LOOK:
                if (fresh && now >= phaseUntil) {
                    if (escape || !hazard) {
                        startTurn(now, hazard);
                    } else {
                        refuse(now);
                    }
                }
                break;
            case TURN:
                sawClearDuringTurn |= !hazard;
                if (hazard && (!escape || sawClearDuringTurn)) {
                    hazardInMotion(now);
                } else if (now >= phaseUntil) {
                    stopMotors();
                    if (escape) {
                        enterPause(now, pauseMs(), false);
                    } else {
                        // The eyes stay on the heading until the hop is under way (R7).
                        hopNext = true;
                        enterPause(now, 0, true);
                    }
                }
                break;
            case HOP:
                if (hazard) {
                    hazardInMotion(now);
                } else if (now >= phaseUntil) {
                    stopMotors();
                    hazardTimes.clear();
                    enterPause(now, pauseMs(), false);
                } else if (ticksLeft > 0 && now >= nextTickAt) {
                    ticksLeft--;
                    nextTickAt += tuning.hopTickMs;
                    motor.hopTick();
                }
                break;
            case STARTLE:
                if (now >= phaseUntil) {
                    if (corneredAfterStartle) {
                        enterCornered(now);
                    } else {
                        startBackOff(now);
                    }
                }
                break;
            case BACK_OFF:
                // Blind (nothing watches behind him): bounded by time only, hazards ignored.
                if (now >= phaseUntil) {
                    stopMotors();
                    enterLook(now, escapeDir, true, tuning.escapeTurnMs);
                } else if (ticksLeft > 0 && now >= nextTickAt) {
                    ticksLeft--;
                    nextTickAt += tuning.backTickMs;
                    motor.backTick();
                }
                break;
            case CORNERED:
                if (now >= phaseUntil) {
                    hazardTimes.clear();
                    Direction d = lastHazardSide != null ? lastHazardSide.opposite() : randomDirection();
                    note("cool-down over, trying a wider turn " + d);
                    enterLook(now, d, true, tuning.corneredTurnMs);
                }
                break;
            default:
                break;
        }
    }

    /** End of a pause, on a fresh reading: hop, turn, or turn away from what is ahead. */
    private void decide(long now, boolean hazard) {
        if (hazard) {
            refuse(now);
        } else if (hopNext) {
            startHop(now);
        } else if (random.nextDouble() < tuning.turnChance) {
            Direction d;
            if (lastHazardSide != null) {
                d = lastHazardSide.opposite();
                lastHazardSide = null;
            } else {
                d = randomDirection();
            }
            enterLook(now, d, false, between(tuning.turnMinMs, tuning.turnMaxMs));
        } else {
            startHop(now);
        }
    }

    /** A hazard in view when a move would start: no move, turn away (or rest, if cornered). */
    private void refuse(long now) {
        HazardClassifier.Hazard h = classifier.hazard();
        note("hazard at start: " + h);
        hopNext = false;
        lastHazardSide = h == null ? null : h.side;
        if (recordHazard(now)) {
            enterCornered(now);
            return;
        }
        enterLook(now, away(h), true, tuning.escapeTurnMs);
    }

    /** A hazard while moving: stop in this same event, then startle (R11). */
    private void hazardInMotion(long now) {
        stopMotors();
        HazardClassifier.Hazard h = classifier.hazard();
        note("hazard while " + state + ": " + h);
        hopNext = false;
        lastHazardSide = h == null ? null : h.side;
        escapeDir = away(h);
        corneredAfterStartle = recordHazard(now);
        sound.playStartle();
        show(EyeState.FLINCH, null);
        state = State.STARTLE;
        phaseUntil = now + tuning.startleMs;
    }

    /** Records one hazard reaction; true when that trips the cornered cap (KTD8). */
    private boolean recordHazard(long now) {
        hazardTimes.addLast(now);
        while (!hazardTimes.isEmpty() && now - hazardTimes.peekFirst() > tuning.capWindowMs) {
            hazardTimes.pollFirst();
        }
        return hazardTimes.size() >= tuning.capHazards;
    }

    // ---- entering states ----

    private void enterEyesOnly(String why) {
        stopMotors();
        hopNext = false;
        if (state != State.EYES_ONLY || shownState == null) {
            note("eyes only: " + why);
        }
        state = State.EYES_ONLY;
        show(EyeState.STILL, null);
    }

    private void enterPause(long now, long ms, boolean keepEyes) {
        state = State.PAUSE;
        phaseUntil = now + ms;
        if (!keepEyes) {
            show(EyeState.IDLE, null);
        }
    }

    private void enterLook(long now, Direction d, boolean escapeTurn, long ms) {
        state = State.LOOK;
        heading = d;
        escape = escapeTurn;
        turnMs = ms;
        phaseUntil = now + tuning.lookLeadMs;
        show(EyeState.LOOK, d);
    }

    private void startTurn(long now, boolean hazardInView) {
        state = State.TURN;
        sawClearDuringTurn = !hazardInView;
        phaseUntil = now + turnMs;
        moving = true;
        motor.turn(heading);
    }

    private void startHop(long now) {
        hopNext = false;
        show(EyeState.IDLE, null);
        state = State.HOP;
        ticksLeft = tuning.hopTicks - 1;
        nextTickAt = now + tuning.hopTickMs;
        phaseUntil = now + tuning.hopTicks * tuning.hopTickMs;
        moving = true;
        motor.hopTick();
    }

    private void startBackOff(long now) {
        if (tuning.backTicks <= 0) {
            enterLook(now, escapeDir, true, tuning.escapeTurnMs);
            return;
        }
        state = State.BACK_OFF;
        ticksLeft = tuning.backTicks - 1;
        nextTickAt = now + tuning.backTickMs;
        phaseUntil = now + tuning.backTicks * tuning.backTickMs;
        moving = true;
        motor.backTick();
    }

    private void enterCornered(long now) {
        stopMotors();
        note("cornered: " + hazardTimes.size() + " hazards in " + tuning.capWindowMs + " ms, resting");
        hazardTimes.clear();
        state = State.CORNERED;
        phaseUntil = now + tuning.cooldownMs;
        show(EyeState.RESTING, null);
    }

    // ---- helpers ----

    private void stopMotors() {
        if (moving) {
            moving = false;
            motor.stop();
        }
    }

    private void show(EyeState s, Direction gaze) {
        if (s == shownState && gaze == shownGaze) {
            return;
        }
        shownState = s;
        shownGaze = gaze;
        eyes.show(s, gaze);
    }

    private Direction away(HazardClassifier.Hazard h) {
        return h != null && h.side != null ? h.side.opposite() : randomDirection();
    }

    private Direction randomDirection() {
        return random.nextBoolean() ? Direction.LEFT : Direction.RIGHT;
    }

    private long pauseMs() {
        return between(tuning.pauseMinMs, tuning.pauseMaxMs);
    }

    private long between(long min, long max) {
        return max > min ? min + (long) (random.nextDouble() * (max - min)) : min;
    }

    private void note(String message) {
        if (trace != null) {
            trace.note(message);
        }
    }
}
