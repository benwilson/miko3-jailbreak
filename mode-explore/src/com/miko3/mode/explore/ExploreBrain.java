package com.miko3.mode.explore;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

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
 *   EYES_ONLY  sensors unavailable or no lease: never drives, eyes show EYES_ONLY (R10, R5)
 *   PAUSE      standing, eyes glancing (R2); ends on a fresh reading
 *   LOOK       eyes on the new heading for lookLeadMs before the turn (R7)
 *   TURN       turning in place for a fixed duration (R3)
 *   HOP        one leg of continuous driving: a random hopTicks..hopTicksMax forward ticks,
 *              hopTickMs apart (each resend keeps it rolling), then stop (R1)
 *   STARTLE    stopped, startle clip and flinch (R11)
 *   BACK_OFF   the only reversing: backTicks, time-bounded and blind (R4, R12)
 *   CORNERED   too many hazards too fast: resting eyes, no motion (KTD8)
 *   STOPPED    after shutdown(); inert
 *
 * Camera curiosity (camera curiosity plan KTD5), entered from PAUSE when a
 * curiosity stop is due; the camera is open in these states and only these:
 *
 *   SCAN       step turns with a look after each; the first look that sees
 *              something decides, and nothing after the last look ends it
 *   FACE       eyes on the target, then turn toward it until it is ahead
 *   APPROACH   short forward legs with a look between them to re-centre;
 *              arrives on the front sensor (KTD4) or the target filling the frame
 *   INSPECT    arrived: "ooh", thinking babble and its name, or a delighted
 *              greeting for a person or pet (R8, R11)
 *   REACT_HERE seen before (disappointed) or unsure (puzzled), from where he is
 *
 * Claude at curiosity stops (explore on Claude plan U4, KTD6, KTD7), when the
 * CuriosityPort can ask. The scan then takes all its looks, keeping each
 * look's JPEG and detections, and the camera is closed in these states:
 *
 *   ASK        thinking eyes; one look request with every scan frame, polled
 *              each tick; askAttempts tries of askTimeoutMs, then the fallback
 *   ORIENT     turn to the picked frame's scan heading plus the box's offset
 *              (or, on the fallback, back to the look that held the detector's pick)
 *   MEET       a person pick (U5, KTD3): thinking eyes; the match request with
 *              the face from the picked frame, one try of meetTimeoutMs; on a
 *              failure or refusal, one text-only request for the two lines
 *              a stranger needs
 *   SPEAK      Claude's line through the port; waits for its finished flag,
 *              with sayTimeoutMs as the backstop
 *   ASK_NAME   a new person: says the ask line, like SPEAK
 *   LISTEN     once that has finished: the launcher listens for the reply
 *              (listenMs, plus listenMarginMs for its answer)
 *   NAME       a reply was heard: the name in it (the port's patterns, then Claude)
 *   REMEMBER   the face is stored with the name (or unnamed) and Claude writes
 *              the "I'll remember you" line, then SPEAK
 *   NAME_CLIP  both person requests failed: the detector's name clip, no asking
 *
 *   MEET -> known: SPEAK the named line ({name} filled in here) or the unnamed
 *                  line, and touch them
 *        -> new:   ASK_NAME -> LISTEN -> nothing heard: SPEAK the no-reply line,
 *                  store nothing (R12)
 *                            -> words: NAME -> REMEMBER -> SPEAK
 *        -> failed: lines request -> ASK_NAME ..., or NAME_CLIP
 *   Every failure or missed deadline ends the stop and he carries on exploring.
 *   Names and lines never go into the trace.
 *
 *   SCAN -> ASK -> nothing interesting: PAUSE, silent
 *              -> a pick the detector also boxed (same broad kind): ORIENT -> FACE
 *                 (camera reopens) -> APPROACH -> SPEAK (or MEET -> SPEAK)
 *              -> any other pick: ORIENT -> SPEAK (or MEET -> SPEAK), no driving
 *              -> both tries failed: ORIENT back to the detector's look, then
 *                 the pre-U4 path (FACE / REACT_HERE, name clip), or PAUSE if
 *                 the detector saw nothing
 *
 * Without a port that can ask, a stop runs exactly as before U4.
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
 * Curiosity keeps every rule above: its turns and legs start on fresh readings
 * and stop on a hazard like any other (an edge during an approach startles and
 * backs off, R7), and losing the lease or the sensors goes to EYES_ONLY with
 * the camera closed. Arrival during an approach is not a hazard (R6). A camera
 * that yields no look in time turns curiosity off for a while and he keeps
 * wandering (KTD8).
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

        /** Eyes on something the camera sees: its box centre, x and y in -1..1 of the
         * frame, x negative toward the robot's left, y negative toward the top. */
        void stare(float x, float y);
    }

    interface Sound {
        void playStartle();

        /** A reaction clip group: "curious", "thinking", "disappointed", "delighted" or "puzzled". */
        void playReaction(String group);

        /** Say a vocabulary name ("ooh, a plant"). */
        void playName(String label);
    }

    /**
     * The on-demand camera and its recognizer (camera curiosity KTD3). open() and
     * close() return at once; the camera works on its own threads, and the brain
     * polls latest() as it does sensor readings.
     */
    interface Camera {
        /** False when curiosity can never work (no camera, no permission, no detector). */
        boolean available();

        void open();

        void close();

        /** The newest recognized frame since open(), or null. */
        Look latest();
    }

    /**
     * One recognized camera frame: when it was captured (brain clock), what was in
     * it, and the frame's JPEG (null when the camera didn't keep it), which the
     * look request sends to Claude (explore on Claude U4).
     */
    static final class Look {
        final long frameMs;
        final List<Detection> detections;
        final byte[] jpeg;

        Look(long frameMs, List<Detection> detections) {
            this(frameMs, detections, null);
        }

        Look(long frameMs, List<Detection> detections, byte[] jpeg) {
            this.frameMs = frameMs;
            this.detections = detections;
            this.jpeg = jpeg;
        }
    }

    /** No camera: curiosity never starts. */
    static final Camera NO_CAMERA = new Camera() {
        public boolean available() {
            return false;
        }

        public void open() {
        }

        public void close() {
        }

        public Look latest() {
            return null;
        }
    };

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
    /** ...and STARE: on something the camera sees (Eyes.stare). */
    /** ...and THINKING: waiting for Claude's answer (explore on Claude R7). */
    enum EyeState { IDLE, LOOK, FLINCH, RESTING, EYES_ONLY, STARE, THINKING }

    enum State {
        EYES_ONLY, PAUSE, LOOK, TURN, HOP, STARTLE, BACK_OFF, CORNERED, STOPPED,
        SCAN, FACE, APPROACH, INSPECT, REACT_HERE,
        ASK, ORIENT, MEET, SPEAK,
        ASK_NAME, LISTEN, NAME, REMEMBER, NAME_CLIP;

        /** The camera is open in exactly these (R2, AE6). */
        boolean curious() {
            return this == SCAN || this == FACE || this == APPROACH || this == INSPECT || this == REACT_HERE;
        }
    }

    /** Within SCAN, FACE and APPROACH: what he is doing right now. */
    private enum Step { WAIT_LOOK, LEAD, TURNING, READY_LEG, LEG }

    private final ExploreTuning tuning;
    private final Clock clock;
    private final Motor motor;
    private final Eyes eyes;
    private final Sound sound;
    private final Random random;
    private final Camera camera;
    private final CuriosityPort port;
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
    /** The way escapes turn until he next drives off cleanly (escapeSide()); null when not escaping. */
    private Direction escapeSide;
    /** When the current escape turn started, and since when the way ahead has read clear (-1: not clear). */
    private long turnStartedAt;
    private long clearSince = -1;
    /** Wheel progress this leg (trackWheels): when it started, the last reading with
     * encoder counts, when those began, and (time, counts moved) per reading. */
    private long hopStartedAt;
    private SensorReading lastWheels;
    private long wheelsSince;
    private final ArrayDeque<long[]> wheelMoves = new ArrayDeque<long[]>();
    /** Stalls since the last cleanly finished leg, and whether the current startle is one. */
    private int stallStreak;
    private boolean stalledNow;
    /** Times of recent failed escapes (a full sweep with no clear way), for the cornered rest. */
    private final ArrayDeque<Long> escapeFailures = new ArrayDeque<Long>();
    /** Side of the last hazard, so the next discretionary turn steers away from it. */
    private Direction lastHazardSide;

    private EyeState shownState;
    private Direction shownGaze;
    private float shownX;
    private float shownY;

    // ---- curiosity ----
    private boolean cameraOpen;
    /** When the next curiosity stop is due, and when a camera failure's back-off ends. */
    private long curiosityAt = Long.MAX_VALUE;
    private long curiosityOffUntil;
    private Step step;
    /** A look counts only if its frame was captured at or after lookAfter; none by lookDeadline is a failure. */
    private long lookAfter;
    private long lookDeadline;
    private boolean firstLook;
    private int scanLooksLeft;
    private Direction scanDir;
    /** What he is investigating: the latest box for it, and its name. */
    private Detection target;
    private int faceTurns;
    private int legs;
    private long legStartedAt;
    private int lostLooks;
    /** What INSPECT and REACT_HERE play, in order: a reaction clip group, or the target's name. */
    private enum Cue { CURIOUS, THINKING, DELIGHTED, DISAPPOINTED, PUZZLED, NAME }

    private final ArrayDeque<Cue> cues = new ArrayDeque<Cue>();
    /** Names inspected this session (R9), and when the person/pet cool-down ends (R12). */
    private final Set<String> seen = new HashSet<String>();
    private long peopleIgnoredUntil;

    // ---- asking Claude (explore on Claude U4) ----
    /** This stop asks Claude: decided as the scan starts. */
    private boolean claudeStop;
    /** The scan's looks in order, and the first detector sighting among them (the fallback). */
    private final List<Look> scanned = new ArrayList<Look>();
    private Sighting detectorPick;
    private int detectorPickLook = -1;
    /** The frames sent (in request order), the tries made, and this try's deadline. */
    private final List<CuriosityPort.Frame> askedFrames = new ArrayList<CuriosityPort.Frame>();
    private int askTries;
    private long askDeadline;
    private boolean asking;
    /** Claude's pick this stop; null on the fallback and on stops without Claude. */
    private CuriosityPort.Answer pick;
    private enum Then { SPEAK, FACE, SIGHTING }
    private Then afterOrient;
    /** When the backstop ends SPEAK. */
    private long sayUntil;
    // ---- meeting a person (explore on Claude U5) ----
    /** MEET waits for the match request, then (if that failed) the lines-only request. */
    private boolean meetLines;
    /** The deadline of the person request, listen, name or remember being waited on. */
    private long meetDeadline;
    /** A new person's lines: the ask line was said, the no-reply line may be. */
    private CuriosityPort.MatchAnswer stranger;
    /** When the camera last closed, for its reopen gap (tuning.reopenGapMs). */
    private long cameraClosedAt = Long.MIN_VALUE / 4;

    /** What he reacted to this session, oldest first, for the look request (KTD2). */
    private static final class Picked {
        final String label;
        final CuriosityPort.Kind kind;
        final long atMs;

        Picked(String label, CuriosityPort.Kind kind, long atMs) {
            this.label = label;
            this.kind = kind;
            this.atMs = atMs;
        }
    }

    private final ArrayDeque<Picked> picked = new ArrayDeque<Picked>();

    ExploreBrain(ExploreTuning tuning, Clock clock, Motor motor, Eyes eyes, Sound sound, Random random) {
        this(tuning, clock, motor, eyes, sound, NO_CAMERA, random);
    }

    ExploreBrain(ExploreTuning tuning, Clock clock, Motor motor, Eyes eyes, Sound sound, Camera camera,
                 Random random) {
        this(tuning, clock, motor, eyes, sound, camera, CuriosityPort.NONE, random);
    }

    ExploreBrain(ExploreTuning tuning, Clock clock, Motor motor, Eyes eyes, Sound sound, Camera camera,
                 CuriosityPort port, Random random) {
        this.tuning = tuning;
        this.port = port;
        this.clock = clock;
        this.motor = motor;
        this.eyes = eyes;
        this.sound = sound;
        this.camera = camera;
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
        trackWheels(reading);
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
        cancelAsk();
        state = State.STOPPED;
        syncCamera();
        note("shutdown");
    }

    /** A curiosity stop is due now (the U8 debug hook); it still waits for the pause to end. */
    void requestCuriosity() {
        long now = clock.nowMs();
        if (curiosityAt > now) {
            curiosityAt = now;
        }
    }

    /** Names inspected this session, for tests and logs. */
    Set<String> seen() {
        return seen;
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
            syncCamera();
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
                if (curiosityAt == Long.MAX_VALUE) {
                    scheduleCuriosity(now);
                }
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
                if (escape) {
                    escapeStep(now, hazard);
                    break;
                }
                sawClearDuringTurn |= !hazard;
                if (hazard) {
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
                } else if (wheelsStalled(now)) {
                    // Pushing against something too low for the front sensor to see.
                    // The sensor can't say when he is past it, so each stall in a row
                    // backs off further and turns a set, growing amount (escapeTurn()).
                    stallStreak++;
                    note("wheels stalled while driving: blocked by something low (" + stallStreak + " in a row)");
                    hazardInMotion(now, null);
                } else if (now >= phaseUntil) {
                    stopMotors();
                    // Driven away cleanly: whatever cornered him is behind him.
                    hazardTimes.clear();
                    stallStreak = 0;
                    escapeFailures.clear();
                    escapeSide = null;
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
                    enterLook(now, escapeDir, true, escapeTurnMs());
                } else if (ticksLeft > 0 && now >= nextTickAt) {
                    ticksLeft--;
                    nextTickAt += tuning.backTickMs;
                    motor.backTick();
                }
                break;
            case SCAN:
            case FACE:
            case APPROACH:
                curiosityStep(now, fresh, hazard);
                break;
            case ORIENT:
                curiosityStep(now, fresh, hazard);
                break;
            case INSPECT:
            case REACT_HERE:
                if (now >= phaseUntil) {
                    nextCue(now);
                }
                break;
            // Standing still, like INSPECT: a hazard ahead matters only once he moves again.
            case ASK:
                askStep(now);
                break;
            case MEET:
                meetStep(now);
                break;
            case ASK_NAME:
                if (port.sayFinished() || now >= sayUntil) {
                    startListening(now);
                }
                break;
            case LISTEN:
                listenStep(now);
                break;
            case NAME:
                nameStep(now);
                break;
            case REMEMBER:
                rememberStep(now);
                break;
            case NAME_CLIP:
                if (now >= phaseUntil) {
                    finishPick(now);
                }
                break;
            case SPEAK:
                if (port.sayFinished()) {
                    finishPick(now);
                } else if (now >= sayUntil) {
                    note("speech never reported finished; moving on");
                    finishPick(now);
                }
                break;
            case CORNERED:
                if (now >= phaseUntil) {
                    hazardTimes.clear();
                    Direction d = lastHazardSide != null ? lastHazardSide.opposite() : randomDirection();
                    note("cool-down over, trying a wider turn " + d);
                    escapeSide = d;
                    enterLook(now, d, true, tuning.corneredTurnMs);
                }
                break;
            default:
                break;
        }
    }

    /** End of a pause, on a fresh reading: hop, turn, look around, or turn away from what is ahead. */
    private void decide(long now, boolean hazard) {
        if (hazard) {
            refuse(now);
        } else if (!hopNext && camera.available() && now >= curiosityAt && now >= curiosityOffUntil) {
            enterScan(now);
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
        enterLook(now, escapeSide(h), true, tuning.escapeTurnMs);
    }

    /** A hazard while moving: stop in this same event, then startle (R11). */
    private void hazardInMotion(long now) {
        HazardClassifier.Hazard h = classifier.hazard();
        note("hazard while " + state + ": " + h);
        hazardInMotion(now, h);
    }

    /** As above, for a hazard the classifier did not see (h null: side unknown). */
    private void hazardInMotion(long now, HazardClassifier.Hazard h) {
        stopMotors();
        stalledNow = h == null && state == State.HOP && stallStreak > 0;
        hopNext = false;
        lastHazardSide = h == null ? null : h.side;
        escapeDir = escapeSide(h);
        corneredAfterStartle = recordHazard(now);
        // One "whoa" per stall streak: repeat stalls flinch quietly.
        if (!stalledNow || stallStreak == 1) {
            sound.playStartle();
        }
        show(EyeState.FLINCH, null);
        state = State.STARTLE;
        phaseUntil = now + tuning.startleMs;
    }

    /**
     * Which way to escape: away from this hazard's side for the first hazard, then
     * the same way for every hazard until he drives off cleanly, so he doesn't
     * swing back and forth into the same wall (owner request, docs/TODO.md).
     */
    private Direction escapeSide(HazardClassifier.Hazard h) {
        if (escapeSide == null) {
            escapeSide = away(h);
        }
        return escapeSide;
    }

    /**
     * An escape turn: keep turning the same way until the way ahead has read clear
     * for escapeClearMs (and at least the turn's own length has passed). A hazard
     * seen again mid-turn just means "not clear yet": turning in place is how he
     * gets out, so it doesn't startle. No clear way within escapeSweepMaxMs is a
     * failed escape; he tries the other way, and rests only after
     * escapeFailuresMax failures within escapeFailWindowMs.
     */
    private void escapeStep(long now, boolean hazard) {
        if (hazard) {
            clearSince = -1;
        } else if (clearSince < 0) {
            clearSince = now;
        }
        if (clearSince >= 0 && now - clearSince >= tuning.escapeClearMs && now >= phaseUntil) {
            stopMotors();
            enterPause(now, pauseMs(), false);
        } else if (now - turnStartedAt >= tuning.escapeSweepMaxMs) {
            stopMotors();
            escapeFailures.addLast(now);
            while (!escapeFailures.isEmpty() && now - escapeFailures.peekFirst() > tuning.escapeFailWindowMs) {
                escapeFailures.pollFirst();
            }
            note("no clear way turning " + heading + " (" + escapeFailures.size() + " failed escapes)");
            if (escapeFailures.size() >= tuning.escapeFailuresMax) {
                enterCornered(now);
                return;
            }
            escapeSide = heading.opposite();
            enterLook(now, escapeSide, true, tuning.escapeTurnMs);
        }
    }

    /**
     * Wheel progress during a leg, from the encoder counts in each reading. Only
     * while hopping: turns and back-offs move the wheels differently.
     */
    private void trackWheels(SensorReading r) {
        if (state != State.HOP || !r.hasWheels()) {
            return;
        }
        if (lastWheels != null) {
            wheelMoves.addLast(new long[]{r.timestampMs,
                    Math.abs(r.wheelLeft - lastWheels.wheelLeft) + Math.abs(r.wheelRight - lastWheels.wheelRight)});
        } else {
            wheelsSince = r.timestampMs;
        }
        lastWheels = r;
        while (!wheelMoves.isEmpty() && r.timestampMs - wheelMoves.peekFirst()[0] > tuning.stallWindowMs) {
            wheelMoves.pollFirst();
        }
    }

    /**
     * The controller keeps taking forward ticks but the wheels have barely turned
     * for stallWindowMs (past the leg's first stallGraceMs): he is stuck against
     * something low (owner report 2026-09-24; on the robot the encoders stood still
     * for 8 s of acknowledged forward while tof read clear floor). Off without
     * encoder data.
     */
    private boolean wheelsStalled(long now) {
        if (lastWheels == null || now - hopStartedAt < tuning.stallGraceMs
                || now - wheelsSince < tuning.stallWindowMs) {
            return false;
        }
        long moved = 0;
        for (long[] m : wheelMoves) {
            moved += m[1];
        }
        return moved < tuning.stallMinCounts;
    }

    /** Records one hazard reaction; true when that trips the cornered cap (KTD8). */
    private boolean recordHazard(long now) {
        hazardTimes.addLast(now);
        while (!hazardTimes.isEmpty() && now - hazardTimes.peekFirst() > tuning.capWindowMs) {
            hazardTimes.pollFirst();
        }
        return hazardTimes.size() >= tuning.capHazards;
    }

    // ---- curiosity ----

    private void scheduleCuriosity(long now) {
        curiosityAt = now + between(tuning.curiosityMinMs, tuning.curiosityMaxMs);
    }

    private void enterScan(long now) {
        note("curiosity stop: scanning");
        // The next stop is scheduled now, so one cut short by a hazard is not retried at once.
        scheduleCuriosity(now);
        state = State.SCAN;
        scanLooksLeft = tuning.scanLooks;
        scanDir = randomDirection();
        target = null;
        claudeStop = port.canAsk();
        scanned.clear();
        detectorPick = null;
        detectorPickLook = -1;
        pick = null;
        firstLook = true;
        show(EyeState.IDLE, null);
        waitForLook(now);
    }

    /** Stand still until a look taken after now + settle arrives. */
    private void waitForLook(long now) {
        step = Step.WAIT_LOOK;
        lookAfter = now + tuning.lookSettleMs;
        // A camera about to (re)open yields nothing until its reopen gap has passed
        // (KTD6): the rest of the gap counts toward this look's deadline.
        long ready = cameraOpen ? now : Math.max(now, cameraClosedAt + tuning.reopenGapMs);
        lookDeadline = ready + (firstLook ? tuning.firstLookTimeoutMs : tuning.lookTimeoutMs);
        firstLook = false;
    }

    /** SCAN, FACE and APPROACH: waiting for a look, leading with the eyes, turning, or driving a leg. */
    private void curiosityStep(long now, boolean fresh, boolean hazard) {
        switch (step) {
            case WAIT_LOOK: {
                Look look = camera.latest();
                if (look != null && look.frameMs >= lookAfter) {
                    // Acted on only with a fresh reading in hand: any move it starts must start here.
                    if (fresh) {
                        onLook(now, look);
                    }
                } else if (now >= lookDeadline) {
                    if (look != null) {
                        // Looks are coming, just not a new enough one: a slow detector,
                        // not a broken camera, so only this stop ends.
                        note("no new look in time; ending this curiosity stop");
                    } else {
                        note("camera gave no look in time; curiosity off for " + tuning.cameraBackoffMs + " ms");
                        curiosityOffUntil = now + tuning.cameraBackoffMs;
                    }
                    giveUp(now);
                }
                break;
            }
            case LEAD:
                if (fresh && now >= phaseUntil) {
                    beginCuriosityTurn(now);
                }
                break;
            case TURNING:
                // Like a discretionary turn: any hazard stops it.
                if (hazard) {
                    hazardInMotion(now);
                } else if (now >= phaseUntil) {
                    stopMotors();
                    if (state == State.APPROACH) {
                        step = Step.READY_LEG;
                    } else if (state == State.ORIENT) {
                        oriented(now);
                    } else {
                        waitForLook(now);
                    }
                }
                break;
            case READY_LEG:
                if (fresh) {
                    startLeg(now);
                }
                break;
            case LEG:
                legStep(now);
                break;
            default:
                break;
        }
    }

    /** A new look arrived (on a fresh reading): decide what the state does with it. */
    private void onLook(long now, Look look) {
        boolean ignorePeople = now < peopleIgnoredUntil;
        if (state == State.SCAN && claudeStop) {
            scanLook(now, look, ignorePeople);
            return;
        }
        if (state == State.SCAN) {
            Sighting sighting = Sighting.choose(look.detections, tuning, ignorePeople);
            if (sighting.kind == Sighting.Kind.NOTHING) {
                if (--scanLooksLeft > 0) {
                    startCuriosityTurn(now, scanDir, tuning.scanTurnMs, false);
                } else {
                    note("nothing interesting here");
                    endCuriosity(now);
                }
                return;
            }
            target = sighting.target;
            note("saw " + sighting);
            if (sighting.kind == Sighting.Kind.UNSURE) {
                note("unsure what the " + target.label + " is: puzzled");
                react(now, State.REACT_HERE, Cue.PUZZLED);
            } else if (!sighting.isPersonOrPet() && seen.contains(target.label)) {
                note("seen the " + target.label + " already: disappointed");
                react(now, State.REACT_HERE, Cue.DISAPPOINTED);
            } else {
                state = State.FACE;
                faceTurns = 0;
                lostLooks = 0;
                face(now);
            }
            return;
        }
        Detection found = find(look.detections, target);
        if (found == null) {
            if (++lostLooks >= tuning.lostLooksMax) {
                note("lost sight of the " + target.label);
                giveUp(now);
            } else {
                waitForLook(now);
            }
            return;
        }
        lostLooks = 0;
        target = found;
        stare(target);
        if (state == State.FACE) {
            face(now);
        } else if (Sighting.fillsFrame(target, tuning)) {
            note("arrived: the " + target.label + " fills the frame");
            arrive(now);
        } else if (classifier.approach(now).isClose()) {
            // Touching it but off-centre (after a refused leg, say): a turn would see
            // the ir flag as a hazard and escape from the very thing he came to see.
            note("arrived: close to the " + target.label);
            arrive(now);
        } else if (legs >= tuning.approachLegsMax) {
            note("never got close to the " + target.label + "; giving up");
            giveUp(now);
        } else if (Math.abs(target.centerX()) > tuning.centreTolerance) {
            note("re-centring " + sideOf(target) + " on the " + target.label);
            startCuriosityTurn(now, sideOf(target), turnMsFor(target), false);
        } else {
            step = Step.READY_LEG;
            startLeg(now);
        }
    }

    /** FACE: turn toward the target until it is ahead (or enough tries), then approach. */
    private void face(long now) {
        stare(target);
        if (Sighting.fillsFrame(target, tuning)) {
            note("arrived: the " + target.label + " already fills the frame");
            arrive(now);
            return;
        }
        if (Math.abs(target.centerX()) <= tuning.centreTolerance || faceTurns >= tuning.faceTurnsMax) {
            note("approaching the " + target.label);
            state = State.APPROACH;
            legs = 0;
            step = Step.READY_LEG;
            startLeg(now);
            return;
        }
        faceTurns++;
        note("turning " + sideOf(target) + " to face the " + target.label);
        // Eyes are already on it; they lead the turn by lookLeadMs (R5).
        startCuriosityTurn(now, sideOf(target), turnMsFor(target), true);
    }

    private void startCuriosityTurn(long now, Direction d, long ms, boolean lead) {
        heading = d;
        turnMs = ms;
        if (lead) {
            step = Step.LEAD;
            phaseUntil = now + tuning.lookLeadMs;
            return;
        }
        beginCuriosityTurn(now);
    }

    /** On a fresh reading: turn to heading for turnMs, or turn away if something is in the way. */
    private void beginCuriosityTurn(long now) {
        if (classifier.status(now) == HazardClassifier.Status.HAZARD) {
            refuse(now);
            return;
        }
        moving = true;
        motor.turn(heading);
        step = Step.TURNING;
        phaseUntil = now + turnMs;
    }

    /** Start one approach leg, on a fresh reading, if nothing is in the way (KTD4). */
    private void startLeg(long now) {
        HazardClassifier.ApproachVerdict v = classifier.approach(now);
        if (v.isClose()) {
            note("arrived: close to the " + target.label + " (" + v + ")");
            arrive(now);
            return;
        }
        if (v == HazardClassifier.ApproachVerdict.EDGE) {
            refuse(now);
            return;
        }
        step = Step.LEG;
        legs++;
        legStartedAt = now;
        ticksLeft = tuning.approachTicks - 1;
        nextTickAt = now + tuning.hopTickMs;
        phaseUntil = now + tuning.approachTicks * tuning.hopTickMs;
        moving = true;
        motor.hopTick();
    }

    /** One approach leg: an edge startles (R7), close arrives (R6) after the leg's grace period. */
    private void legStep(long now) {
        HazardClassifier.ApproachVerdict v = classifier.approach(now);
        boolean inGrace = now - legStartedAt < tuning.approachGraceMs;
        if (v == HazardClassifier.ApproachVerdict.EDGE) {
            hazardInMotion(now);
        } else if (v == HazardClassifier.ApproachVerdict.CLOSE_REFUSED && inGrace) {
            // The controller refused forward: never resend it; look again instead (KTD4).
            note("forward refused at leg start; looking again");
            stopMotors();
            waitForLook(now);
        } else if (v.isClose() && !inGrace) {
            note("arrived: close to the " + target.label + " (" + v + ")");
            arrive(now);
        } else if (now >= phaseUntil) {
            stopMotors();
            waitForLook(now);
        } else if (ticksLeft > 0 && now >= nextTickAt) {
            ticksLeft--;
            nextTickAt += tuning.hopTickMs;
            motor.hopTick();
        }
    }

    /** Arrived (not a hazard, R6): inspect it. */
    private void arrive(long now) {
        stopMotors();
        hazardTimes.clear();
        if (pick != null) {
            speakPick(now);
        } else if (Sighting.isPersonOrPet(target.label)) {
            react(now, State.INSPECT, Cue.DELIGHTED, Cue.NAME, Cue.DELIGHTED);
        } else {
            react(now, State.INSPECT, Cue.CURIOUS, Cue.THINKING, Cue.NAME);
        }
    }

    /** Stand still, eyes on the target, and play the cues in order. */
    private void react(long now, State s, Cue... sequence) {
        stopMotors();
        state = s;
        stare(target);
        cues.clear();
        for (Cue c : sequence) {
            cues.addLast(c);
        }
        nextCue(now);
    }

    private void nextCue(long now) {
        Cue cue = cues.pollFirst();
        if (cue == null) {
            if (state == State.INSPECT) {
                if (Sighting.isPersonOrPet(target.label)) {
                    peopleIgnoredUntil = now + tuning.peopleCooldownMs;
                } else {
                    seen.add(target.label);
                }
                remember(target.label, CuriosityPort.Kind.of(target.label), now);
            }
            endCuriosity(now);
            return;
        }
        if (cue == Cue.NAME) {
            sound.playName(target.label);
        } else {
            // The clip group is the cue's name: "curious", "thinking", ...
            sound.playReaction(cue.name().toLowerCase(java.util.Locale.US));
        }
        phaseUntil = now + cueMs(cue);
    }

    private long cueMs(Cue cue) {
        switch (cue) {
            case CURIOUS:
                return tuning.curiousMs;
            case THINKING:
                return tuning.thinkingMs;
            case DELIGHTED:
                return tuning.delightedMs;
            case DISAPPOINTED:
                return tuning.disappointedMs;
            case PUZZLED:
                return tuning.puzzledMs;
            default:
                return tuning.nameMs;
        }
    }

    /** Back to wandering; the camera closes as the state leaves curiosity. */
    private void endCuriosity(long now) {
        stopMotors();
        cancelAsk();
        target = null;
        pick = null;
        stranger = null;
        afterOrient = null;
        scanned.clear();
        askedFrames.clear();
        cues.clear();
        scheduleCuriosity(now);
        enterPause(now, pauseMs(), false);
    }

    /** Open the camera in the curiosity states, close it everywhere else (R2, AE6). */
    private void syncCamera() {
        boolean want = state.curious();
        if (want != cameraOpen) {
            cameraOpen = want;
            if (want) {
                camera.open();
            } else {
                camera.close();
                cameraClosedAt = clock.nowMs();
            }
        }
    }

    // ---- asking Claude (explore on Claude U4) ----

    /** A Claude stop's scan look: keep it (and the detector's first sighting), and take them all (R1). */
    private void scanLook(long now, Look look, boolean ignorePeople) {
        scanned.add(look);
        if (detectorPick == null) {
            Sighting s = Sighting.choose(look.detections, tuning, ignorePeople);
            if (s.kind != Sighting.Kind.NOTHING) {
                detectorPick = s;
                detectorPickLook = scanned.size() - 1;
                note("detector saw " + s + " in look " + scanned.size());
            }
        }
        if (--scanLooksLeft > 0) {
            startCuriosityTurn(now, scanDir, tuning.scanTurnMs, false);
        } else {
            enterAsk(now);
        }
    }

    /** Scan done: the camera closes and he thinks while Claude looks (R6, R7). */
    private void enterAsk(long now) {
        stopMotors();
        state = State.ASK;
        show(EyeState.THINKING, null);
        askTries = 0;
        askedFrames.clear();
        for (int i = 0; i < scanned.size(); i++) {
            if (scanned.get(i).jpeg != null) {
                askedFrames.add(new CuriosityPort.Frame(i, scanned.get(i).jpeg));
            }
        }
        if (askedFrames.isEmpty()) {
            note("no frames kept to show Claude");
            fallback(now);
            return;
        }
        startAsk(now);
    }

    private void startAsk(long now) {
        askTries++;
        asking = true;
        askDeadline = now + tuning.askTimeoutMs;
        boolean cooling = now < peopleIgnoredUntil;
        note("asking Claude (try " + askTries + " of " + tuning.askAttempts + ", " + askedFrames.size() + " frames)");
        port.ask(new CuriosityPort.LookRequest(new ArrayList<CuriosityPort.Frame>(askedFrames), recent(now), cooling),
                tuning.askTimeoutMs);
    }

    /** ASK, each tick: poll the answer; a failure or a missed deadline is another try, then the fallback (R7). */
    private void askStep(long now) {
        CuriosityPort.Answer a = port.answer();
        if (a == null) {
            if (now >= askDeadline) {
                note("no answer from Claude in " + tuning.askTimeoutMs + " ms");
                cancelAsk();
                retryOrFallback(now);
            }
            return;
        }
        asking = false;
        if (a.status == CuriosityPort.Answer.Status.NOTHING) {
            note("Claude: nothing interesting here");
            endCuriosity(now);
        } else if (a.status == CuriosityPort.Answer.Status.PICK && validPick(a)) {
            onPick(now, a);
        } else {
            note("Claude's answer failed or was unusable: " + a);
            retryOrFallback(now);
        }
    }

    private boolean validPick(CuriosityPort.Answer a) {
        return a.frame >= 0 && a.frame < askedFrames.size() && a.box != null && a.kind != null
                && a.line != null && !a.line.trim().isEmpty();
    }

    private void retryOrFallback(long now) {
        if (askTries < tuning.askAttempts) {
            startAsk(now);
        } else {
            fallback(now);
        }
    }

    /** Claude picked something: face it, approaching only if the detector boxed it too (KTD7). */
    private void onPick(long now, CuriosityPort.Answer a) {
        int look = askedFrames.get(a.frame).look;
        // A person's label is Claude's description of them: kept out of the trace.
        note("Claude picked " + (a.kind == CuriosityPort.Kind.PERSON ? "a person" : a.toString())
                + " (look " + (look + 1) + ")");
        if (a.kind.isLiving() && now < peopleIgnoredUntil) {
            note("greeted people and animals recently; carrying on");
            endCuriosity(now);
            return;
        }
        pick = a;
        remember(a.box.label, a.kind, now);
        float cx = a.box.centerX();
        long offset = Math.abs(cx) > tuning.centreTolerance
                ? (cx < 0 ? -1 : 1) * Math.max(100, (long) (Math.abs(cx) * tuning.turnMsPerUnit)) : 0;
        Detection agree = detectorAgrees(scanned.get(look).detections, a);
        if (agree != null) {
            note("the detector sees it too, as a " + agree.label + ": approaching");
            target = agree;
            orient(now, look, offset, Then.FACE);
        } else {
            target = null;
            orient(now, look, offset, Then.SPEAK);
        }
    }

    /** The detector's box in that look overlapping Claude's, of the same broad kind (KTD7), or null. */
    private Detection detectorAgrees(List<Detection> detections, CuriosityPort.Answer a) {
        Detection best = null;
        float bestIou = -1f;
        float px0 = Math.min(a.box.x0, a.box.x1);
        float px1 = Math.max(a.box.x0, a.box.x1);
        float py0 = Math.min(a.box.y0, a.box.y1);
        float py1 = Math.max(a.box.y0, a.box.y1);
        for (Detection d : detections) {
            if (d.score < tuning.confidenceFloor || Sighting.BACKGROUND.contains(d.label)
                    || !CuriosityPort.Kind.of(d.label).sameBroadKind(a.kind)) {
                continue;
            }
            float iou = d.iou(a.box);
            float dx = (d.x0 + d.x1) / 2;
            float dy = (d.y0 + d.y1) / 2;
            boolean inside = dx >= px0 && dx <= px1 && dy >= py0 && dy <= py1;
            if ((iou >= tuning.pickMatchIou || inside) && iou > bestIou) {
                best = d;
                bestIou = iou;
            }
        }
        return best;
    }

    /** Both tries failed (R8): back to the look that held the detector's pick, then the old path. */
    private void fallback(long now) {
        pick = null;
        if (detectorPick == null) {
            note("no answer from Claude and the detector saw nothing; carrying on");
            endCuriosity(now);
            return;
        }
        note("no answer from Claude; falling back to the detector's " + detectorPick);
        orient(now, detectorPickLook, 0, Then.SIGHTING);
    }

    /**
     * Turn from the last scan look's heading to the given look's, plus offsetMs
     * (positive = right), then carry on with `then`. The scan stepped scanTurnMs
     * toward scanDir between looks.
     */
    private void orient(long now, int look, long offsetMs, Then then) {
        stopMotors();
        state = State.ORIENT;
        afterOrient = then;
        long scanSign = scanDir == Direction.RIGHT ? 1 : -1;
        long ms = (look - (scanned.size() - 1)) * tuning.scanTurnMs * scanSign + offsetMs;
        if (Math.abs(ms) < MIN_ORIENT_MS) {
            oriented(now);
            return;
        }
        Direction d = ms > 0 ? Direction.RIGHT : Direction.LEFT;
        note("turning " + d + " " + Math.abs(ms) + " ms toward it");
        show(EyeState.LOOK, d);
        startCuriosityTurn(now, d, Math.abs(ms), true);
    }

    private static final long MIN_ORIENT_MS = 50;

    private void oriented(long now) {
        Then then = afterOrient;
        afterOrient = null;
        if (then == Then.SPEAK) {
            speakPick(now);
        } else if (then == Then.FACE) {
            enterFace(now);
        } else {
            sighted(now, detectorPick);
        }
    }

    /** FACE with the camera reopening: wait for a look (its reopen gap counted, KTD6), then face as before. */
    private void enterFace(long now) {
        state = State.FACE;
        faceTurns = 0;
        lostLooks = 0;
        firstLook = true;
        waitForLook(now);
    }

    /** The pre-U4 reaction to the detector's sighting, from the fallback. */
    private void sighted(long now, Sighting s) {
        target = s.target;
        if (s.kind == Sighting.Kind.UNSURE) {
            note("unsure what the " + target.label + " is: puzzled");
            react(now, State.REACT_HERE, Cue.PUZZLED);
        } else if (!s.isPersonOrPet() && seen.contains(target.label)) {
            note("seen the " + target.label + " already: disappointed");
            react(now, State.REACT_HERE, Cue.DISAPPOINTED);
        } else {
            enterFace(now);
        }
    }

    /** FACE or APPROACH ended without arriving: with Claude's pick he still says its line from here. */
    private void giveUp(long now) {
        if (pick != null) {
            stopMotors();
            speakPick(now);
        } else {
            endCuriosity(now);
        }
    }

    private void speakPick(long now) {
        if (pick.kind == CuriosityPort.Kind.PERSON) {
            enterMeet(now);
            return;
        }
        speak(now, pick.line);
    }

    // ---- meeting a person (explore on Claude U5; R9-R14, KTD3, KTD4) ----

    /** MEET: thinking eyes while Claude compares the face in the picked frame with the stored ones. */
    private void enterMeet(long now) {
        stopMotors();
        state = State.MEET;
        stranger = null;
        meetLines = false;
        show(EyeState.THINKING, null);
        meetDeadline = now + tuning.meetTimeoutMs;
        note("a person: checking whether we've met");
        port.match(askedFrames.get(pick.frame).jpeg, pick.box, tuning.meetTimeoutMs);
    }

    private void meetStep(long now) {
        CuriosityPort.MatchAnswer a = meetLines ? port.linesAnswer() : port.matchAnswer();
        if (a == null) {
            if (now < meetDeadline) {
                return;
            }
            note("no answer about the person in " + tuning.meetTimeoutMs + " ms");
            a = CuriosityPort.MatchAnswer.FAILED;
        }
        if (!meetLines && a.status == CuriosityPort.MatchAnswer.Status.KNOWN) {
            greet(now, a);
        } else if (a.status == CuriosityPort.MatchAnswer.Status.NEW && usable(a.askLine)) {
            askName(now, a);
        } else if (!meetLines) {
            // A refusal, a failure, or a stranger with no ask line: one text-only try (KTD3).
            note("the person request failed; asking for the lines alone");
            meetLines = true;
            meetDeadline = now + tuning.meetTimeoutMs;
            port.lines(tuning.meetTimeoutMs);
        } else {
            note("no lines for the person either; just the name clip");
            nameClip(now);
        }
    }

    /** Known (R10): the named line with the stored name filled in, or the unnamed line. */
    private void greet(long now, CuriosityPort.MatchAnswer a) {
        String line = null;
        if (a.name != null && !a.name.trim().isEmpty() && usable(a.namedLine)) {
            line = a.namedLine.replace("{name}", a.name.trim());
            note("someone we've met, with a name");
        } else if (usable(a.unnamedLine)) {
            line = a.unnamedLine;
            note("someone we've met, without a name");
        }
        if (line == null) {
            note("someone we've met, but no line to greet them with");
            nameClip(now);
            return;
        }
        port.touch();
        speak(now, line);
    }

    /** New (R11): say the ask line, then listen once it has finished (KTD8). */
    private void askName(long now, CuriosityPort.MatchAnswer a) {
        note("someone new: asking their name");
        stranger = a;
        say(now, a.askLine, State.ASK_NAME);
    }

    private void startListening(long now) {
        stopMotors();
        state = State.LISTEN;
        meetDeadline = now + tuning.listenMs + tuning.listenMarginMs;
        note("listening for a reply");
        port.listen(tuning.listenMs);
    }

    /** LISTEN: nothing heard stores nothing (R12); words go on to the name. */
    private void listenStep(long now) {
        CuriosityPort.Heard h = port.heard();
        if (h == null) {
            if (now >= meetDeadline) {
                note("no answer from listening in time; carrying on");
                finishPick(now);
            }
            return;
        }
        if (h.status == CuriosityPort.Heard.Status.WORDS && h.text != null && !h.text.trim().isEmpty()) {
            state = State.NAME;
            show(EyeState.THINKING, null);
            meetDeadline = now + tuning.meetTimeoutMs;
            note("heard a reply; looking for a name in it");
            port.findName(h.text, tuning.meetTimeoutMs);
        } else if (h.status == CuriosityPort.Heard.Status.FAILED) {
            note("listening failed; carrying on without storing anything");
            finishPick(now);
        } else if (stranger != null && usable(stranger.noReplyLine)) {
            note("no reply: storing nothing");
            speak(now, stranger.noReplyLine);
        } else {
            note("no reply and no line for it: storing nothing");
            finishPick(now);
        }
    }

    /** NAME: a reply was heard, so the face is kept either way, named or not (R12). */
    private void nameStep(long now) {
        CuriosityPort.Named n = port.foundName();
        if (n == null && now < meetDeadline) {
            return;
        }
        String name = n != null && n.status == CuriosityPort.Named.Status.NAME ? n.name : null;
        note(name != null ? "got a name; remembering them" : "no clear name; remembering them unnamed");
        state = State.REMEMBER;
        meetDeadline = now + tuning.meetTimeoutMs;
        port.remember(name, tuning.meetTimeoutMs);
    }

    private void rememberStep(long now) {
        CuriosityPort.Answer a = port.remembered();
        if (a == null && now < meetDeadline) {
            return;
        }
        if (a != null && a.status == CuriosityPort.Answer.Status.PICK && usable(a.line)) {
            speak(now, a.line);
        } else {
            note("no remember line; carrying on");
            finishPick(now);
        }
    }

    /** Both person requests failed (KTD3): the detector's name clip, as before U4, and no asking. */
    private void nameClip(long now) {
        stopMotors();
        state = State.NAME_CLIP;
        if (target != null) {
            stare(target);
        } else {
            stareAt(0f, pick.box.centerY());
        }
        sound.playName(target != null ? target.label : "person");
        phaseUntil = now + tuning.nameMs;
    }

    private static boolean usable(String line) {
        return line != null && !line.trim().isEmpty();
    }

    /** SPEAK: the camera and detector are closed (R6); wait for the finished flag (KTD8). */
    private void speak(long now, String line) {
        say(now, line, State.SPEAK);
    }

    private void say(long now, String line, State s) {
        stopMotors();
        state = s;
        if (target != null) {
            stare(target);
        } else {
            stareAt(0f, pick.box.centerY());
        }
        sayUntil = now + tuning.sayTimeoutMs;
        port.say(line);
    }

    private void finishPick(long now) {
        if (pick.kind.isLiving()) {
            peopleIgnoredUntil = now + tuning.peopleCooldownMs;
        } else {
            seen.add(pick.box.label);
        }
        endCuriosity(now);
    }

    private void cancelAsk() {
        if (asking) {
            asking = false;
            port.cancelAsk();
        }
    }

    private void remember(String label, CuriosityPort.Kind kind, long now) {
        picked.addLast(new Picked(label, kind, now));
        while (picked.size() > tuning.recentPicksMax) {
            picked.pollFirst();
        }
    }

    private List<CuriosityPort.Recent> recent(long now) {
        List<CuriosityPort.Recent> out = new ArrayList<CuriosityPort.Recent>();
        for (Picked p : picked) {
            out.add(new CuriosityPort.Recent(p.label, p.kind, now - p.atMs));
        }
        return out;
    }

    /**
     * The target in a new look: the largest box with its name, or else the box
     * overlapping its last box the most (at least MATCH_IOU), kept under the
     * target's name. The detector often names one thing differently from frame
     * to frame ("tv", "monitor", "computer" on the same box, seen on the robot).
     */
    private static Detection find(List<Detection> detections, Detection last) {
        Detection best = null;
        for (Detection d : detections) {
            if (d.label.equals(last.label) && (best == null || d.area() > best.area())) {
                best = d;
            }
        }
        if (best != null) {
            return best;
        }
        float bestIou = MATCH_IOU;
        for (Detection d : detections) {
            float iou = d.iou(last);
            if (iou >= bestIou) {
                bestIou = iou;
                best = d;
            }
        }
        return best == null ? null : new Detection(last.label, best.score, best.x0, best.y0, best.x1, best.y1);
    }

    private static final float MATCH_IOU = 0.3f;

    /** The image's left is the robot's left: the camera faces forward. */
    private static Direction sideOf(Detection d) {
        return d.centerX() < 0 ? Direction.LEFT : Direction.RIGHT;
    }

    private long turnMsFor(Detection d) {
        return Math.max(100, (long) (Math.abs(d.centerX()) * tuning.turnMsPerUnit));
    }

    // ---- entering states ----

    private void enterEyesOnly(String why) {
        stopMotors();
        cancelAsk();
        hopNext = false;
        target = null;
        pick = null;
        afterOrient = null;
        cues.clear();
        if (state != State.EYES_ONLY || shownState == null) {
            note("eyes only: " + why);
        }
        state = State.EYES_ONLY;
        show(EyeState.EYES_ONLY, null);
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
        clearSince = hazardInView ? -1 : now;
        turnStartedAt = now;
        phaseUntil = now + turnMs;
        moving = true;
        motor.turn(heading);
    }

    private void startHop(long now) {
        hopNext = false;
        show(EyeState.IDLE, null);
        state = State.HOP;
        int ticks = tuning.hopTicksMax > tuning.hopTicks
                ? tuning.hopTicks + random.nextInt(tuning.hopTicksMax - tuning.hopTicks + 1)
                : tuning.hopTicks;
        ticksLeft = ticks - 1;
        nextTickAt = now + tuning.hopTickMs;
        phaseUntil = now + ticks * tuning.hopTickMs;
        hopStartedAt = now;
        lastWheels = null;
        wheelMoves.clear();
        moving = true;
        motor.hopTick();
    }

    /** The escape turn's minimum: longer for each stall in a row (see HOP). */
    private long escapeTurnMs() {
        if (!stalledNow) {
            return tuning.escapeTurnMs;
        }
        return Math.min(tuning.escapeSweepMaxMs, tuning.stallTurnMs + (stallStreak - 1) * tuning.stallTurnStepMs);
    }

    private void startBackOff(long now) {
        int ticks = stalledNow ? Math.max(tuning.backTicks, tuning.stallBackTicks) : tuning.backTicks;
        if (ticks <= 0) {
            enterLook(now, escapeDir, true, escapeTurnMs());
            return;
        }
        state = State.BACK_OFF;
        ticksLeft = ticks - 1;
        nextTickAt = now + tuning.backTickMs;
        phaseUntil = now + ticks * tuning.backTickMs;
        moving = true;
        motor.backTick();
    }

    private void enterCornered(long now) {
        stopMotors();
        note("cornered: " + escapeFailures.size() + " failed escapes, " + hazardTimes.size()
                + " hazards; resting");
        hazardTimes.clear();
        escapeFailures.clear();
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

    private void stare(Detection d) {
        stareAt(d.centerX(), d.centerY());
    }

    private void stareAt(float x, float y) {
        if (shownState == EyeState.STARE && x == shownX && y == shownY) {
            return;
        }
        shownState = EyeState.STARE;
        shownGaze = null;
        shownX = x;
        shownY = y;
        eyes.stare(x, y);
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
