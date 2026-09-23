package com.miko3.mode.explore;

import java.util.ArrayDeque;
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

    /** One recognized camera frame: when it was captured (brain clock), and what was in it. */
    static final class Look {
        final long frameMs;
        final List<Detection> detections;

        Look(long frameMs, List<Detection> detections) {
            this.frameMs = frameMs;
            this.detections = detections;
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
    enum EyeState { IDLE, LOOK, FLINCH, RESTING, EYES_ONLY, STARE }

    enum State {
        EYES_ONLY, PAUSE, LOOK, TURN, HOP, STARTLE, BACK_OFF, CORNERED, STOPPED,
        SCAN, FACE, APPROACH, INSPECT, REACT_HERE;

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

    ExploreBrain(ExploreTuning tuning, Clock clock, Motor motor, Eyes eyes, Sound sound, Random random) {
        this(tuning, clock, motor, eyes, sound, NO_CAMERA, random);
    }

    ExploreBrain(ExploreTuning tuning, Clock clock, Motor motor, Eyes eyes, Sound sound, Camera camera,
                 Random random) {
        this.tuning = tuning;
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
            case SCAN:
            case FACE:
            case APPROACH:
                curiosityStep(now, fresh, hazard);
                break;
            case INSPECT:
            case REACT_HERE:
                if (now >= phaseUntil) {
                    nextCue(now);
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
        firstLook = true;
        show(EyeState.IDLE, null);
        waitForLook(now);
    }

    /** Stand still until a look taken after now + settle arrives. */
    private void waitForLook(long now) {
        step = Step.WAIT_LOOK;
        lookAfter = now + tuning.lookSettleMs;
        lookDeadline = now + (firstLook ? tuning.firstLookTimeoutMs : tuning.lookTimeoutMs);
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
                    endCuriosity(now);
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
                react(now, State.REACT_HERE, Cue.PUZZLED);
            } else if (!sighting.isPersonOrPet() && seen.contains(target.label)) {
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
                endCuriosity(now);
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
            endCuriosity(now);
        } else if (Math.abs(target.centerX()) > tuning.centreTolerance) {
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
        if (Sighting.isPersonOrPet(target.label)) {
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
        target = null;
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
            }
        }
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
        hopNext = false;
        target = null;
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

    private void stare(Detection d) {
        float x = d.centerX();
        float y = d.centerY();
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
