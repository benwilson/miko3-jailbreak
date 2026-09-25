package com.miko3.mode.explore;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
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
 *   TURN       turning in place for a fixed duration (R3), or to a measured angle
 *              once the gyro is calibrated (explore nav plan U2, KTD1)
 *   HOP        one leg of continuous driving: a random hopTicks..hopTicksMax forward ticks,
 *              hopTickMs apart (each resend keeps it rolling), then stop (R1)
 *   STARTLE    stopped, startle clip and flinch (R11)
 *   BACK_OFF   the only reversing: backTicks, time-bounded and blind (R4, R12)
 *   CORNERED   too many hazards too fast: resting eyes, no motion (KTD8)
 *   STOPPED    after shutdown(); inert
 *
 * Camera curiosity (camera curiosity plan KTD5), entered from PAUSE when a
 * curiosity stop is due; the camera is always open in these states:
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
 *   MEET_LOOK  a person pick, facing them and stopped: thinking eyes while the
 *              camera takes a FRESH look; the face is cropped from the detector's
 *              person box in it that matches the pick (by IoU, from where the turn
 *              should have put the pick), else from Claude's box in the picked
 *              scan frame (no person box, or no look in time)
 *   MEET       a person pick (U5, KTD3): thinking eyes; the match request with
 *              that face, one try of meetTimeoutMs; on a
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
 * Measured turns (explore nav plan U2, KTD1): with the gyro calibrated and its
 * bias known (Heading.usable), every turn above -- discretionary, escape (its
 * minimum and its full-circle failure), cornered, scan step, face, re-centre and
 * ORIENT -- goes by degrees from the Heading tracker instead of milliseconds, and
 * ORIENT turns to the heading the picked look was taken at. Uncalibrated, or with
 * no gyro in the readings, every turn is timed exactly as before. The safety rules
 * are unchanged: a measured turn starts and stops only on the same events a timed
 * one does. A gyro that goes quiet mid-turn ends it at its timed length, and
 * turnBackstopMs ends one that never gets there. Heading also keeps the leg log,
 * restarted at each clean drive-off (where escape state resets). Trace notes carry
 * numbers only.
 *
 * The camera while roaming (explore nav plan U4, KTD2, KTD7, KTD9): syncCamera()
 * is the one place it opens and closes, by rule (cameraWanted): open in every
 * roaming, escaping and curiosity state; closed while he meets, asks or talks, at
 * rest, without the lease or sensors, and for cameraBackoffMs after a camera that
 * gave no look in time (he roams on the floor sensor meanwhile, R5). Each leg is
 * chosen from the newest fresh look's openness by RoamSteer (a bend toward the
 * most open columns, a length cut by blocked ones; a low-confidence profile
 * chooses as before), and a fresh look reading the way ahead blocked ends a leg
 * at the next tick. The floor sensor, stall sensing and CPL=2 still decide every
 * stop: motion starts only on fresh readings, and the camera never starts one.
 * Look-then-go (ExploreTuning.Navigation.LOOK_THEN_GO) opens the camera at each
 * leg decision, waits for one look, and closes it before the leg. Every reading
 * tells the camera whether the floor is clear with the wheels free (floor
 * teaching, U3), a look's floor is taught once he has driven floorTeachCounts
 * past it, and the camera hears when he starts and stops moving (U9).
 *
 * Wedged escapes (explore nav plan U5, R11-R14, KTD4-KTD6): with the heading
 * usable, wedgeHazards hazards with no clean leg between, wedgeStalls stalls in a
 * row, a failed escape sweep, or a measured turn the gyro says isn't turning
 * (turnStallDeg in turnStallMs: every measured turn is watched) make him wedged,
 * and EscapePlanner's steps run instead of today's escape turns and rest:
 *
 *   RETRACE    back along the leg log, newest first: face each leg's reverse and
 *              drive it forward, up to the retrace distance; when he can't turn, he
 *              first reverses straight along the most recent forward leg(s), capped
 *              at their logged distance (ground he just drove over), then turns
 *   CIRCLE     escapeCircleSteps measured stops, one fresh stationary look at each
 *   WAY_OUT    Claude picks the way out from the circle's frames (THINKING eyes, the
 *              camera stays open); offline or late, the best on-robot openness
 *              heading; the second ask, with one frame of now, happens here too
 *   DRIVE_OFF  turn to the chosen heading and drive off
 *
 * Each step has a time budget, plus its measured turns' time at the learned turn
 * rate (ExploreTuning.escapeTurnRateDegS); out of time or a hazard, it has failed
 * and the next one starts, but a turn still making progress, or a drive-off whose
 * turn is done, is never cut off for time. Turns in an escape ignore hazards
 * (turning in place is how he gets out); a hazard or stall while driving forward
 * stops at once, backs off backTicks, and moves on. All failing ends in CORNERED
 * as before. Forward first: a step that ran out of time or failed, a ladder about
 * to rest, and the end of a cornered rest first try escapeProbeTicks forward when
 * the way ahead looks clear; driven cleanly, he is free. Uncalibrated, every wedge
 * is today's: the cornered cap and the failed-sweep count. A blocked measured turn
 * with no leg to back out along first backs up blockedTurnBackTicks (stopped by a
 * stall, not logged as a leg) and tries the turn once more, the other way. Every
 * ladder starts with one such back-up, whatever the leg log holds (no leg back-out
 * after it); if it moved him, the turn that would not turn goes the free way and he
 * drives off, else (or failing) the ladder goes on. A retrace turn the long way
 * round past a blocked side over retraceLongWayMaxDeg is skipped. A way a
 * turn would not turn is avoided by every later turn (unblocked()) until he drives
 * off forward cleanly or a turn that way gets there; with both blocked, the one
 * blocked longer ago is tried first.
 *
 * Open doorways (explore nav plan U6, R7, R8, KTD4): while he roams (PAUSE or HOP,
 * camera open, heading usable, Claude set up) one fresh roaming frame goes to
 * Claude in the background at most every doorwayAskMs, never in a stop, a meeting
 * or an escape (an ask still running when one starts is dropped). An open doorway's
 * position plus the frame's heading is remembered; RoamSteer weights legs toward it
 * until he has driven a full leg toward it with the way reading open, it expires
 * (doorwayExpireMs, doorwayExpireCounts), a look facing it reads blocked (closed
 * since), or he is wedged. Floor hazards, stalls and CPL stop him as always (AE3);
 * offline the ask fails quietly (AE7). Notes carry numbers only (R15).
 *
 * People while roaming (explore nav plan U7, R9, R10, KTD4, KTD8): a detector person
 * box in a leg decision's look, with Claude set up, becomes a synthetic PERSON pick
 * (the look is its frame 0): FACE, APPROACH until the box is politeHeight of the
 * frame, then MEET_LOOK and the meeting exactly as at a curiosity stop. Everyone met
 * is left alone for metLeaveAloneMs from the end of their meeting. While anyone is
 * on that list, every PERSON pick, roaming or at a curiosity stop, first needs
 * Claude's recently-met check (the person's face against theirs) to answer "none of
 * them": at most one check per metCheckIntervalMs, and a pending, failed or unsure
 * check, or none allowed yet, counts as just met. Roaming, he keeps roaming (a check
 * runs in the background; a "none of them" clears a person seen within
 * metClearedMs); at a curiosity stop, ASK waits for the check and a just-met pick
 * ends as its remark, said without approaching. This replaces peopleCooldownMs for
 * approaching people only; the look request's cooling-down flag still follows it.
 * Notes carry counts only, never a name (R15).
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

        /**
         * Closed and idle: close() has taken effect and no detector run is in
         * flight. close() is asynchronous, so speech waits for this (R6).
         */
        boolean quiet();

        /**
         * Floor teaching (explore nav plan U3, KTD3): whether the floor sensor reads
         * floor and the wheels turn freely, given on every reading. The camera stamps
         * each captured frame with it; a frame's bottom-row patch becomes pending only
         * while it is true, and every pending patch seen up to a turn to false (a
         * hazard or stall) is dropped. No-op by default: nothing is taught.
         */
        default void setFloorClear(long nowMs, boolean clearAndFree) {
        }

        /**
         * He has driven at least the distance to the patches seen in frames captured
         * up to this time (Look.frameMs), with no hazard or stall: teach them as floor.
         */
        default void floorDrivenOver(long throughFrameMs) {
        }

        /**
         * Brightness (explore nav plan U9, KTD10): whether he is driving, which caps
         * the camera's exposure short against motion blur. No-op by default.
         */
        default void setMoving(boolean moving) {
        }
    }

    /**
     * One recognized camera frame: when it was captured (brain clock), what was in
     * it, and the frame's JPEG (null when the camera didn't keep it), which the
     * look request sends to Claude (explore on Claude U4), and its openness profile
     * (explore nav plan U3; null when the camera didn't score it).
     */
    static final class Look {
        final long frameMs;
        final List<Detection> detections;
        final byte[] jpeg;
        final Openness.Profile openness;

        Look(long frameMs, List<Detection> detections) {
            this(frameMs, detections, null);
        }

        Look(long frameMs, List<Detection> detections, byte[] jpeg) {
            this(frameMs, detections, jpeg, null);
        }

        Look(long frameMs, List<Detection> detections, byte[] jpeg, Openness.Profile openness) {
            this.frameMs = frameMs;
            this.detections = detections;
            this.jpeg = jpeg;
            this.openness = openness;
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

        public boolean quiet() {
            return true;
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
        ASK, ORIENT, MEET_LOOK, MEET, SPEAK,
        ASK_NAME, LISTEN, NAME, REMEMBER, NAME_CLIP,
        RETRACE, CIRCLE, WAY_OUT, DRIVE_OFF;

        /** A curiosity stop's looking states: the camera is always open in these (R2, AE6). */
        boolean curious() {
            return this == SCAN || this == FACE || this == APPROACH || this == INSPECT || this == REACT_HERE
                    || this == MEET_LOOK;
        }

        /**
         * Roaming and escaping (explore nav plan U4, KTD2): the camera is open in these
         * too, while it is healthy (continuous navigation), or only at a leg decision
         * in PAUSE (look-then-go, KTD7).
         */
        boolean roams() {
            return this == PAUSE || this == LOOK || this == TURN || this == HOP || this == STARTLE
                    || this == BACK_OFF || escapes();
        }

        /** A wedged escape's steps (explore nav plan U5): the camera stays open through them. */
        boolean escapes() {
            return this == RETRACE || this == CIRCLE || this == WAY_OUT || this == DRIVE_OFF;
        }

        /** Part of a curiosity stop, from the scan until he is back to wandering. */
        boolean inStop() {
            return curious() || this == ASK || this == ORIENT || this == MEET || this == SPEAK
                    || this == ASK_NAME || this == LISTEN || this == NAME || this == REMEMBER || this == NAME_CLIP;
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
    /** Heading, measured turns and the leg log (explore nav plan U2). */
    private final Heading compass;
    /** Bends and shortens roaming legs from the looks' openness (explore nav plan U4). */
    private final RoamSteer steer;
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
    /** The same turn in degrees (0: timed only), and whether it is running measured. */
    private double turnDeg;
    private boolean measured;
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
    /** MEET_LOOK: where the picked person should be in the fresh look. */
    private Detection meetExpect;
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
    /** The heading each scan look was taken at (NaN: not measured), for ORIENT (explore nav plan U2, R4). */
    private final List<Double> scanHeadings = new ArrayList<Double>();
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
    /** A line waiting for the camera and detector to go quiet, and how long it waits at most. */
    private String pendingLine;
    private long quietUntil;
    /** Whether ORIENT added the pick's offset, so the pick should now sit at the frame's centre. */
    private boolean pickRecentred;
    /** When Claude's pick arrived, and a pick whose turn or approach a hazard cut short (said after the escape). */
    private long pickAt;
    private CuriosityPort.Answer heldPick;
    private long heldPickAt;
    // ---- meeting a person (explore on Claude U5) ----
    /** MEET waits for the match request, then (if that failed) the lines-only request. */
    private boolean meetLines;
    /** The deadline of the person request, listen, name or remember being waited on. */
    private long meetDeadline;
    /** A new person's lines: the ask line was said, the no-reply line may be. */
    private CuriosityPort.MatchAnswer stranger;
    /** When the camera last closed, for its reopen gap (tuning.reopenGapMs). */
    private long cameraClosedAt = Long.MIN_VALUE / 4;

    // ---- the camera while roaming (explore nav plan U4) ----
    /** The last look seen (by identity: a real frame arrived), and when the next must come by. */
    private Look lastLook;
    private long roamLookDeadline;
    /** The last look checked during the current leg for a blocked way ahead. */
    private Look legLook;
    /** When the last turn stopped: a look captured before that faced another way. */
    private long headingSettledAt = Long.MIN_VALUE / 4;
    /** The next leg's forward ticks as the steer chose them (0: its bend is the whole move; -1: none). */
    private int plannedTicks = -1;
    /** Look-then-go (KTD7): in PAUSE, waiting for the leg decision's look, captured at or after legLookAfter. */
    private boolean lookForLeg;
    private long legLookAfter;
    private long legLookDeadline;
    /** What the camera was last told about driving (null: nothing yet). */
    private Boolean movingShown;
    /** Forward encoder counts driven (mean of the wheels), the reading they were last taken from,
     * and {frameMs, counts} at each look's arrival, oldest first, for floor teaching. */
    private long forwardCounts;
    private SensorReading countsFrom;
    private final ArrayDeque<long[]> teachQueue = new ArrayDeque<long[]>();

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

    // ---- wedged escapes (explore nav plan U5) ----
    /** Where a measured turn last made progress (turnStallDeg), for the blocked-turn check. */
    private double turnProgressDeg;
    private long turnProgressAt;
    /** The steps, budgets and headings of the escape; the brain runs them. */
    private final EscapePlanner planner;
    /** Within an escape step: what he is doing right now. */
    private enum Esc { TURN_READY, TURNING, DRIVE_READY, DRIVING, BACK_READY, BACKING, READY, LOOKING, ASKING }

    /** What follows the current turn, back-out or wait. */
    private enum EscThen { DRIVE, LOOK, RETRACE_NEXT, RETRY_TURN, PHASE, FIRST_BACK }

    private Esc esc;
    private EscThen escThen;
    /** A short forward try (ExploreTuning.escapeProbeTicks) is under way, and what follows it if blocked. */
    private boolean probing;
    private enum ProbeThen { STEP, REST, AFTER_REST }
    private ProbeThen probeThen;
    private long probeSince;
    /** Where a forward drive last hit a hazard or stalled (NaN: none since a clean drive). */
    private double blockedAheadAt = Double.NaN;
    /**
     * Escape ladders that ended in rest, in a row (reset by a clean drive-off or a
     * clean leg); they set how long he rests when still pinned (pinnedRestMs()).
     */
    private int failedLadders;
    /** When the last cornered rest ended (its first move after is the pinned check). */
    private long restEndedAt = Long.MIN_VALUE / 4;
    /** This rest is the still-pinned one: when it ends, the ladder, not the wider turn. */
    private boolean ladderAfterRest;
    private boolean ladderTurnBlocked;
    private Direction escDir;
    private double escTurnAmount;
    /** This turn has had its back-out already. */
    private boolean escBackedOut;
    /** Encoder counts (both wheels) moved in the current roaming leg. */
    private long hopMoved;
    /** This ladder has driven forward cleanly (a retrace move), not only backed out. */
    private boolean escDroveForward;
    /** Wedged by a blocked turn: back out before the retrace's first turn. */
    private boolean escBackOutFirst;
    /**
     * The ladder's first move is a straight blind back-up (blockedTurnBackTicks), whatever
     * the leg log holds (live 2026-09-25: nose to a wall, the turn into it blocked, "he
     * could just back up to get out of there"; the retrace turned 327 deg instead).
     */
    private boolean escBackUpFirst;
    /** After that back-up moved him: the turn the free way and a drive off are under way. */
    private boolean escFirstRetry;
    /** The measured turn that would not turn when he was wedged (null: another trigger), and its amount. */
    private Direction wedgeTurnDir;
    private double wedgeTurnDeg;
    /** This ladder's: the blocked turn to try the free way after the first back-up (null: none). */
    private Direction escRetryDir;
    private double escRetryDeg;
    /** Counts to drive or back out (0: a drive-off's escapeDriveTicks, a hazard's backTicks). */
    private long escGoal;
    /** Counts moved in this drive or back-out, and the reading they were last counted from. */
    private long escMoved;
    private SensorReading escFrom;
    private int escTicks;
    private long escUntil;
    private long escWaitSince;
    private long escLookAfter;
    private Look escLookBefore;
    private Direction circleDir;
    /** The frames asked about, and the heading each was taken at. */
    private final List<CuriosityPort.Frame> escAsked = new ArrayList<CuriosityPort.Frame>();
    private final List<Double> escAskedHeadings = new ArrayList<Double>();
    private boolean wayOutAsking;

    // ---- open doorways (explore nav plan U6) ----
    /** An ask is out: sent at doorwayAskAt, for a frame taken facing doorwayAskFacing. */
    private boolean doorwayAsking;
    private long doorwayAskAt;
    private double doorwayAskFacing;
    /** The next ask may go at this time (the interval restarts when an ask ends). */
    private long doorwayNextAskAt;
    /** The remembered doorway's heading (NaN: none), when it was set and the forward counts then. */
    private double doorway = Double.NaN;
    private long doorwaySetAt;
    private long doorwaySetCounts;
    /** The leg under way heads for the doorway with the way reading open: driven in full, he is through. */
    private boolean doorwayLeg;

    // ---- people while roaming (explore nav plan U7) ----
    /** Someone met: the port's handle for them (null: no face to compare) and when the meeting ended. */
    private static final class Met {
        final String id;
        final long endedAt;

        Met(String id, long endedAt) {
            this.id = id;
            this.endedAt = endedAt;
        }
    }

    /** Everyone met in the last metLeaveAloneMs, oldest first. */
    private final ArrayDeque<Met> met = new ArrayDeque<Met>();
    /** This pick reached MEET: its end starts that person's leave-alone. */
    private boolean meetingHeld;
    /** This person pick ends as its remark: no approach and no meeting (KTD8). */
    private boolean remarkOnly;
    /** A recently-met check is out (sent at metCheckAt); gatedPick waits on it in ASK. */
    private boolean metChecking;
    private long metCheckAt;
    private CuriosityPort.Answer gatedPick;
    /** The next check may go at this time; a roaming person is cleared until metClearedUntil. */
    private long metNextCheckAt = Long.MIN_VALUE / 4;
    private long metClearedUntil = Long.MIN_VALUE / 4;

    /** The newest reading, where a drive's counts start from. */
    private SensorReading lastReading;

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
        this.compass = new Heading(tuning.gyro, tuning);
        this.steer = new RoamSteer(tuning);
        this.planner = new EscapePlanner(tuning);
    }

    /** The heading tracker, for the navigation that builds on it (explore nav plan U2, U5). */
    Heading heading() {
        return compass;
    }

    /** The remembered open doorway's heading, NaN for none (explore nav plan U6), for tests and logs. */
    double doorwayHeading() {
        return doorway;
    }

    void setTrace(Trace trace) {
        this.trace = trace;
    }

    State state() {
        return state;
    }

    /** The camera is off after a failure (cameraBackoffMs), for tests and logs. */
    boolean cameraBackedOff() {
        return clock.nowMs() < curiosityOffUntil;
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
        countEscapeWheels(reading);
        lastReading = reading;
        compass.offer(reading, moving);
        teachFloor(reading);
        double[] turn = compass.takeTurnResult();
        if (turn != null) {
            note("measured turn: asked " + Math.round(turn[0]) + " deg, turned " + Math.round(turn[1])
                    + " deg, overshoot " + Math.round(turn[2]) + " deg");
        }
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
        cancelWayOut();
        cancelDoorway(clock.nowMs(), null);
        cancelMetCheck();
        planner.reset();
        probing = false;
        state = State.STOPPED;
        syncCamera();
        syncMoving();
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
        boolean wasInStop = state.inStop();
        try {
            boolean f = fresh;
            do {
                again = false;
                stepOnce(f);
                f = false;
            } while (again && state != State.STOPPED);
            syncCamera();
            syncMoving();
            // However a stop ends (a line, NOTHING, a skip, the fallback, a
            // failure, a hazard, the lease), the next one is a full gap of
            // wandering away. Live, stops began ~0.5 s apart and he never roamed.
            if (wasInStop && !state.inStop() && state != State.STOPPED) {
                scheduleCuriosity(clock.nowMs());
            }
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
        watchLooks(now);
        doorwayStep(now);
        metCheckStep(now);
        switch (state) {
            case PAUSE:
                if (lookForLeg) {
                    legLookStep(now, fresh, hazard);
                } else if (fresh && now >= phaseUntil) {
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
                if (turnBlocked(now)) {
                    turnWouldNotTurn(now);
                    break;
                }
                if (escape) {
                    escapeStep(now, hazard);
                    break;
                }
                sawClearDuringTurn |= !hazard;
                if (hazard) {
                    hazardInMotion(now);
                } else if (turnDone(now)) {
                    stopMotors();
                    if (escape) {
                        enterPause(now, pauseMs(), false);
                    } else if (plannedTicks == 0) {
                        // The steer's turn away from a view with nothing open: look again from here.
                        plannedTicks = -1;
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
                    compass.legStalled(now - tuning.stallWindowMs);
                    hazardInMotion(now, null);
                } else if (now >= phaseUntil) {
                    if (doorwayLeg) {
                        note("through the doorway at " + Math.round(doorway) + " deg");
                        forgetDoorway();
                    }
                    legDriven(now);
                } else if (blockedAheadInLeg()) {
                    // He never stops for the camera alone (KTD9): the leg just ends here,
                    // like a short one, and the next decision bends away.
                    note("camera reads the way ahead blocked: ending the leg early");
                    doorwayLeg = false;
                    legDriven(now);
                } else if (ticksLeft > 0 && now >= nextTickAt) {
                    ticksLeft--;
                    nextTickAt += tuning.hopTickMs;
                    motor.hopTick();
                }
                break;
            case STARTLE:
                if (now >= phaseUntil) {
                    if (corneredAfterStartle) {
                        wedged(now, "hazards in a row", false);
                    } else {
                        startBackOff(now);
                    }
                }
                break;
            case BACK_OFF:
                // Blind (nothing watches behind him): bounded by time, hazards ignored; the
                // short back-up before a retried turn also stops on a stall.
                if (backForTurn && now < phaseUntil && wheelsStalled(now)) {
                    note("wheels stalled backing up");
                    phaseUntil = now;
                }
                if (now >= phaseUntil && backForTurn) {
                    stopMotors();
                    backForTurn = false;
                    enterLook(now, retryDir, retryEscape, retryMs, retryDeg);
                    turnRetrying = true;
                } else if (now >= phaseUntil) {
                    stopMotors();
                    enterLook(now, escapeDir, true, escapeTurnMs(), escapeTurnDeg());
                } else if (ticksLeft > 0 && now >= nextTickAt) {
                    ticksLeft--;
                    nextTickAt += tuning.backTickMs;
                    motor.backTick();
                }
                break;
            case SCAN:
            case FACE:
            case APPROACH:
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
            case MEET_LOOK:
                meetLookStep(now);
                break;
            case MEET:
                meetStep(now);
                break;
            case ASK_NAME:
                if (!lineStarted(now)) {
                    break;
                }
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
                if (!lineStarted(now)) {
                    break;
                }
                if (port.sayFinished()) {
                    finishPick(now);
                } else if (now >= sayUntil) {
                    note("speech never reported finished; moving on");
                    finishPick(now);
                }
                break;
            case RETRACE:
            case CIRCLE:
            case WAY_OUT:
            case DRIVE_OFF:
                escapeTick(now, fresh, hazard);
                break;
            case CORNERED:
                if (now >= phaseUntil) {
                    if (!ladderAfterRest) {
                        restEndedAt = now;
                        hazardTimes.clear();
                    }
                    // Facing open floor after the rest, a turn would only face him away from it.
                    if (!tryForwardFirst(now, "after the rest", true, ProbeThen.AFTER_REST)) {
                        afterRest(now);
                    }
                }
                break;
            default:
                break;
        }
    }

    /** After a cornered rest (and its forward try, if any): the next ladder when still pinned, else the wider turn. */
    private void afterRest(long now) {
        if (ladderAfterRest) {
            ladderAfterRest = false;
            startLadder(now, "still pinned after the longer rest", ladderTurnBlocked);
            return;
        }
        restEndedAt = now;
        Direction d = unblocked(lastHazardSide != null ? lastHazardSide.opposite() : randomDirection());
        note("cool-down over, trying a wider turn " + d);
        escapeSide = d;
        enterLook(now, d, true, tuning.corneredTurnMs, tuning.corneredTurnDeg);
    }

    /** A roaming leg ended without a hazard or stall (its time, or the camera saw the way blocked). */
    private void legDriven(long now) {
        stopMotors();
        if (legWentNowhere(now)) {
            // Too short for the stall watch to rule, but the encoders say he never moved:
            // not a drive-off, so the blocked ways stay avoided.
            aheadBlocked();
        } else {
            blockedSides.clear();
            blockedAheadAt = Double.NaN;
        }
        // Driven away cleanly: whatever cornered him is behind him.
        compass.droveOffCleanly();
        hazardTimes.clear();
        stallStreak = 0;
        failedLadders = 0;
        escapeFailures.clear();
        escapeSide = null;
        enterPause(now, pauseMs(), false);
    }

    /** End of a pause, on a fresh reading: hop, turn, look around, or turn away from what is ahead. */
    private void decide(long now, boolean hazard) {
        if (hazard) {
            refuse(now);
        } else if (!hopNext && camera.available() && now >= curiosityAt && now >= curiosityOffUntil) {
            enterScan(now);
        } else if (hopNext) {
            startHop(now);
        } else if (tuning.navigation == ExploreTuning.Navigation.LOOK_THEN_GO && camera.available()
                && now >= curiosityOffUntil) {
            // Look-then-go (KTD7): the camera opens for this decision (at the end of
            // this step) and closes again before the leg.
            lookForLeg = true;
            long ready = Math.max(now, cameraClosedAt + tuning.reopenGapMs);
            legLookAfter = ready + tuning.lookSettleMs;
            legLookDeadline = ready + tuning.firstLookTimeoutMs;
        } else {
            Look look = cameraOpen ? roamLook(now) : null;
            if (!seePerson(now, look)) {
                chooseLeg(now, look);
            }
        }
    }

    /**
     * Look-then-go, in PAUSE: the leg decision's look arrived (acted on with a fresh
     * reading in hand), or none came in time: the camera closes before any motion
     * and the leg is chosen, from the look if there is one.
     */
    private void legLookStep(long now, boolean fresh, boolean hazard) {
        Look look = camera.latest();
        boolean arrived = look != null && look.frameMs >= legLookAfter;
        if (arrived ? !fresh : now < legLookDeadline) {
            return;
        }
        lookForLeg = false;
        if (!arrived) {
            note("camera gave no look in time; camera off for " + tuning.cameraBackoffMs + " ms");
            curiosityOffUntil = now + tuning.cameraBackoffMs;
        }
        syncCamera();
        if (!fresh) {
            // The deadline passed on a tick: decide on the next reading, without the camera.
            phaseUntil = now;
            return;
        }
        if (hazard) {
            refuse(now);
        } else if (!seePerson(now, arrived ? look : null)) {
            chooseLeg(now, arrived ? look : null);
        }
    }

    /**
     * The next roaming leg: steered by the look's openness (RoamSteer, KTD9) when it
     * is confident, else as before the camera roamed: a random turn or a hop.
     */
    private void chooseLeg(long now, Look look) {
        double door = doorwayBearing(now);
        if (look != null && steer.doorwayReadsBlocked(look.openness, door)) {
            note("the doorway at " + Math.round(doorway) + " deg reads blocked now: forgotten");
            forgetDoorway();
            door = Double.NaN;
        }
        RoamSteer.Plan plan = look == null ? null : steer.plan(look.openness, door);
        doorwayLeg = false;
        if (plan != null) {
            note("steer: " + plan);
            if (plan.towardDoorway && !plan.turnOnly && !plan.shortLeg && plan.open >= tuning.steerOpen) {
                double after = compass.degrees() + (plan.side == RoamSteer.LEFT ? plan.bendDeg
                        : plan.side == RoamSteer.RIGHT ? -plan.bendDeg : 0);
                doorwayLeg = Math.abs(Heading.delta(Heading.wrap(after), doorway)) <= tuning.doorwayFacingDeg;
            }
            lastHazardSide = null;
            plannedTicks = steer.legTicks(plan, drawTicks());
            if (plan.side == RoamSteer.STRAIGHT) {
                startHop(now);
                return;
            }
            Direction d = plan.side == RoamSteer.LEFT ? Direction.LEFT : Direction.RIGHT;
            double bend = plan.bendDeg;
            if (unblocked(d) != d) {
                note("the " + d + " side is blocked: bending the long way round");
                d = d.opposite();
                bend = 360 - bend;
            }
            enterLook(now, d, false, timedMs(bend), compass.usable(now) ? bend : 0);
        } else if (random.nextDouble() < tuning.turnChance) {
            Direction d;
            if (lastHazardSide != null) {
                d = lastHazardSide.opposite();
                lastHazardSide = null;
            } else {
                d = randomDirection();
            }
            d = unblocked(d);
            if (compass.usable(now)) {
                double deg = tuning.turnMinDeg + random.nextDouble() * (tuning.turnMaxDeg - tuning.turnMinDeg);
                enterLook(now, d, false, timedMs(deg), deg);
            } else {
                enterLook(now, d, false, between(tuning.turnMinMs, tuning.turnMaxMs), 0);
            }
        } else {
            startHop(now);
        }
    }

    /** A hazard in view when a move would start: no move, turn away (or rest, if cornered). */
    private void refuse(long now) {
        HazardClassifier.Hazard h = classifier.hazard();
        note("hazard at start: " + h);
        aheadBlocked();
        leaveStopForHazard();
        hopNext = false;
        doorwayLeg = false;
        plannedTicks = -1;
        lastHazardSide = h == null ? null : h.side;
        boolean capped = recordHazard(now);
        if (capped || wedgedNow(now)) {
            wedged(now, "hazards in a row", false);
            return;
        }
        enterLook(now, escapeSide(h), true, tuning.escapeTurnMs, tuning.escapeTurnDeg);
    }

    /** A hazard while moving: stop in this same event, then startle (R11). */
    private void hazardInMotion(long now) {
        HazardClassifier.Hazard h = classifier.hazard();
        note("hazard while " + state + ": " + h);
        hazardInMotion(now, h);
    }

    /** As above, for a hazard the classifier did not see (h null: side unknown). */
    private void hazardInMotion(long now, HazardClassifier.Hazard h) {
        if (state == State.HOP) {
            aheadBlocked();
        }
        stopMotors();
        leaveStopForHazard();
        doorwayLeg = false;
        stalledNow = h == null && state == State.HOP && stallStreak > 0;
        hopNext = false;
        plannedTicks = -1;
        lastHazardSide = h == null ? null : h.side;
        escapeDir = escapeSide(h);
        corneredAfterStartle = recordHazard(now);
        corneredAfterStartle |= wedgedNow(now);
        // One "whoa" per stall streak: repeat stalls flinch quietly.
        if (!stalledNow || stallStreak == 1) {
            sound.playStartle();
        }
        show(EyeState.FLINCH, null);
        state = State.STARTLE;
        phaseUntil = now + tuning.startleMs;
    }

    /**
     * A hazard ends any curiosity stop he was moving in: the escape comes first and
     * nothing is said during it. Claude's line for a thing he was turning to or
     * driving up to is kept, to be said from wherever the escape leaves him (like
     * an approach that loses sight); a person's meeting is dropped, since the
     * person is no longer in front of him.
     */
    private void leaveStopForHazard() {
        if (!state.inStop()) {
            return;
        }
        boolean going = state == State.ORIENT || state == State.FACE || state == State.APPROACH;
        if (going && pick != null && pick.kind != CuriosityPort.Kind.PERSON && usable(pick.line)) {
            note("keeping Claude's line for after the escape");
            heldPick = pick;
            heldPickAt = pickAt;
        } else if (pick != null) {
            note("dropping this stop's pick");
        }
        clearStop();
    }

    /** After an escape, standing still: Claude's kept line, if it is still fresh. True if he is saying it. */
    private boolean sayHeldLine(long now) {
        CuriosityPort.Answer p = heldPick;
        heldPick = null;
        if (p == null) {
            return false;
        }
        if (now - heldPickAt > tuning.heldLineFreshMs) {
            note("Claude's kept line is " + (now - heldPickAt) + " ms old: dropping it");
            return false;
        }
        note("escape over: saying Claude's line from here");
        pick = p;
        target = null;
        speak(now, p.line);
        return true;
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
        escapeSide = unblocked(escapeSide);
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
        if (clearSince >= 0 && now - clearSince >= tuning.escapeClearMs && turnDone(now)) {
            stopMotors();
            if (!sayHeldLine(now)) {
                enterPause(now, pauseMs(), false);
            }
        } else if (sweptFullCircle(now)) {
            stopMotors();
            escapeFailures.addLast(now);
            while (!escapeFailures.isEmpty() && now - escapeFailures.peekFirst() > tuning.escapeFailWindowMs) {
                escapeFailures.pollFirst();
            }
            note("no clear way turning " + heading + " (" + escapeFailures.size() + " failed escapes)");
            if (escapeFailures.size() >= tuning.escapeFailuresMax
                    || (compass.usable(now) && escapeFailures.size() >= tuning.wedgeFailedEscapes)) {
                wedged(now, "no clear way all round", false);
                return;
            }
            escapeSide = heading.opposite();
            enterLook(now, escapeSide, true, tuning.escapeTurnMs, tuning.escapeTurnDeg);
        }
    }

    /**
     * Whether the current turn has gone far enough: its timed length, or, measured,
     * its angle (turnBackstopMs if it never gets there; the timed length if the gyro
     * went quiet mid-turn). Measured progress moves only on readings.
     */
    private boolean turnDone(long now) {
        if (!measured) {
            return now >= phaseUntil;
        }
        if (!compass.usable(now)) {
            return now >= phaseUntil;
        }
        return compass.turnReached() || now - turnStartedAt >= tuning.turnBackstopMs;
    }

    /** An escape turn with no clear way: escapeSweepMaxMs timed, or measured, escapeSweepDeg. */
    private boolean sweptFullCircle(long now) {
        if (!measured || !compass.usable(now)) {
            return now - turnStartedAt >= tuning.escapeSweepMaxMs;
        }
        return compass.turned() >= tuning.escapeSweepDeg || now - turnStartedAt >= tuning.turnBackstopMs;
    }

    /** The timed stand-in for a measured angle, at escapeSweepMaxMs per full circle. */
    private long timedMs(double deg) {
        return Math.max(1, Math.round(deg * tuning.escapeSweepMaxMs / 360.0));
    }

    /** Degrees to turn so a box at d's centre is ahead: its offset x half the camera's view. */
    private double turnDegFor(Detection d) {
        return Math.abs(d.centerX()) * tuning.cameraHalfFovDeg;
    }

    /** Starts a measured turn now if the gyro can measure it (motor.turn(heading) just went out). */
    private void measureTurn(long now, double deg) {
        measured = deg > 0 && compass.usable(now);
        turnProgressDeg = 0;
        turnProgressAt = now;
        if (measured) {
            compass.startTurn(heading == Direction.LEFT ? Heading.LEFT : Heading.RIGHT,
                    escape && state == State.TURN ? Math.min(deg, tuning.escapeSweepDeg) : deg, now);
        }
    }

    /**
     * Wheel progress during a leg, from the encoder counts in each reading. Only
     * while hopping: turns and back-offs move the wheels differently.
     */
    private void trackWheels(SensorReading r) {
        if (!(state == State.HOP || escapeDriving() || (state == State.BACK_OFF && backForTurn)) || !r.hasWheels()) {
            return;
        }
        if (lastWheels != null) {
            long moved = Math.abs(r.wheelLeft - lastWheels.wheelLeft) + Math.abs(r.wheelRight - lastWheels.wheelRight);
            if (state == State.HOP) {
                hopMoved += moved;
            }
            wheelMoves.addLast(new long[]{r.timestampMs, moved});
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
        scanDir = unblocked(randomDirection());
        target = null;
        claudeStop = port.canAsk();
        heldPick = null;
        scanned.clear();
        scanHeadings.clear();
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
        long ready = Math.max(now, cameraClosedAt + tuning.reopenGapMs);
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
                if (turnBlocked(now)) {
                    turnWouldNotTurn(now);
                } else if (hazard) {
                    hazardInMotion(now);
                } else if (turnDone(now)) {
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
        // Someone met in the last 10 minutes: the detector's fallback never approaches a person (KTD8).
        boolean ignorePeople = now < peopleIgnoredUntil || anyoneMet(now);
        if (state == State.SCAN && claudeStop) {
            scanLook(now, look, ignorePeople);
            return;
        }
        if (state == State.SCAN) {
            Sighting sighting = Sighting.choose(look.detections, tuning, ignorePeople);
            if (sighting.kind == Sighting.Kind.NOTHING) {
                if (--scanLooksLeft > 0) {
                    startCuriosityTurn(now, scanDir, tuning.scanTurnMs, tuning.scanTurnDeg, false);
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
        } else if (polite(target)) {
            note("arrived: a polite distance from the person");
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
            startCuriosityTurn(now, sideOf(target), turnMsFor(target), turnDegFor(target), false);
        } else {
            step = Step.READY_LEG;
            startLeg(now);
        }
    }

    /** FACE: turn toward the target until it is ahead (or enough tries), then approach. */
    private void face(long now) {
        stare(target);
        if (Sighting.fillsFrame(target, tuning) || polite(target)) {
            note("arrived: the " + target.label + " already fills the frame or is a polite distance away");
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
        startCuriosityTurn(now, sideOf(target), turnMsFor(target), turnDegFor(target), true);
    }

    private void startCuriosityTurn(long now, Direction d, long ms, double deg, boolean lead) {
        if (deg > 0 && unblocked(d) != d) {
            // Measured toward something, and that side is blocked: the long way round.
            d = d.opposite();
            deg = 360 - deg;
            ms = timedMs(deg);
        }
        heading = d;
        turnMs = ms;
        turnDeg = deg;
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
        turnStartedAt = now;
        phaseUntil = now + turnMs;
        measureTurn(now, turnDeg);
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
        compass.startLeg(false, now);
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
        clearStop();
        enterPause(now, pauseMs(), false);
    }

    /** Forgets everything about the current stop (the camera closes as the state leaves curiosity). */
    private void clearStop() {
        cancelAsk();
        // A check the stop was waiting on runs on; its answer then only clears a roaming person.
        gatedPick = null;
        remarkOnly = false;
        meetingHeld = false;
        target = null;
        meetExpect = null;
        pick = null;
        stranger = null;
        pendingLine = null;
        afterOrient = null;
        scanned.clear();
        scanHeadings.clear();
        askedFrames.clear();
        cues.clear();
    }

    /**
     * The one place the camera opens and closes (explore nav plan U4, KTD2): it is
     * wanted in every roaming, escaping and curiosity state, and never while he
     * meets, asks or talks (MEET, SPEAK, ASK_NAME, LISTEN, NAME, REMEMBER,
     * NAME_CLIP, and ASK and ORIENT, which lead into a line: speech is fast only
     * with the camera and detector idle, R3), nor at rest (CORNERED), without the
     * lease or the sensors (EYES_ONLY), after shutdown, or during a camera
     * failure's back-off (R5). Look-then-go (KTD7) keeps it closed while roaming
     * except at a leg decision.
     */
    private boolean cameraWanted(long now) {
        if (!camera.available() || !leaseHeld || now < curiosityOffUntil) {
            return false;
        }
        if (state.curious()) {
            return true;
        }
        if (!state.roams()) {
            return false;
        }
        if (tuning.navigation == ExploreTuning.Navigation.LOOK_THEN_GO) {
            return state == State.PAUSE && lookForLeg;
        }
        return true;
    }

    private void syncCamera() {
        long now = clock.nowMs();
        boolean want = state != State.STOPPED && cameraWanted(now);
        if (want != cameraOpen) {
            cameraOpen = want;
            if (want) {
                camera.open();
                // Nothing comes until the reopen gap has passed, then the camera starts.
                roamLookDeadline = Math.max(now, cameraClosedAt + tuning.reopenGapMs) + tuning.firstLookTimeoutMs;
                lastLook = null;
                legLook = null;
            } else {
                camera.close();
                cameraClosedAt = now;
                teachQueue.clear();
            }
        }
    }

    /** Tells the camera whether he is driving (U9: exposure is capped short while he is). */
    private void syncMoving() {
        if (movingShown == null || movingShown != moving) {
            movingShown = moving;
            camera.setMoving(moving);
        }
    }

    /**
     * Each new look (a real frame arrived, R5): noted for floor teaching, and while
     * roaming it keeps the camera's health deadline moving. No new look by then, in
     * a roaming state, is a camera failure: it closes for cameraBackoffMs and he
     * roams on the floor sensor, then it is tried again (AE6). A curiosity stop
     * watches its own looks (waitForLook), so the deadline only runs while roaming.
     */
    private void watchLooks(long now) {
        if (!cameraOpen) {
            return;
        }
        Look look = camera.latest();
        if (look != null && look != lastLook) {
            lastLook = look;
            roamLookDeadline = now + tuning.lookTimeoutMs;
            teachQueue.addLast(new long[]{look.frameMs, forwardCounts});
            while (teachQueue.size() > TEACH_QUEUE_MAX) {
                teachQueue.pollFirst();
            }
        } else if (!state.roams() || lookForLeg) {
            roamLookDeadline = Math.max(roamLookDeadline, now + tuning.lookTimeoutMs);
        } else if (now >= roamLookDeadline) {
            note("camera gave no look in time while roaming; camera off for " + tuning.cameraBackoffMs + " ms");
            curiosityOffUntil = now + tuning.cameraBackoffMs;
        }
    }

    private static final int TEACH_QUEUE_MAX = 8;

    /** The newest look to steer the next leg by: fresh, and taken since he last turned; else null. */
    private Look roamLook(long now) {
        Look look = camera.latest();
        if (look == null || look.frameMs < now - tuning.steerLookFreshMs
                || look.frameMs < headingSettledAt + tuning.lookSettleMs) {
            return null;
        }
        return look;
    }

    /** During a leg: a look captured since it started that reads the way ahead blocked. */
    private boolean blockedAheadInLeg() {
        if (!cameraOpen) {
            return false;
        }
        Look look = camera.latest();
        if (look == null || look == legLook || look.frameMs <= hopStartedAt) {
            return false;
        }
        legLook = look;
        return steer.blockedAhead(look.openness);
    }

    /**
     * Floor teaching (explore nav plan U3, KTD3): on every reading the camera hears
     * whether the floor sensor reads clear floor with the wheels free (and he is not
     * turning or backing, which would carry a frame's floor patch off his path); a
     * look's patch is taught once he has driven floorTeachCounts forward since it
     * arrived. A turn to false drops everything pending (the camera does that).
     */
    private void teachFloor(SensorReading r) {
        long now = clock.nowMs();
        boolean forward = drivingForward();
        if (forward && r.hasWheels()) {
            if (countsFrom != null) {
                forwardCounts += (Math.abs(r.wheelLeft - countsFrom.wheelLeft)
                        + Math.abs(r.wheelRight - countsFrom.wheelRight)) / 2;
            }
            countsFrom = r;
        } else {
            countsFrom = null;
        }
        boolean clear = classifier.status(now) == HazardClassifier.Status.CLEAR
                && !((state == State.HOP || escapeDriving()) && wheelsStalled(now))
                && (!moving || forward);
        camera.setFloorClear(now, clear);
        if (!clear) {
            teachQueue.clear();
            return;
        }
        long through = Long.MIN_VALUE;
        while (!teachQueue.isEmpty() && forwardCounts - teachQueue.peekFirst()[1] >= tuning.floorTeachCounts) {
            through = teachQueue.pollFirst()[0];
        }
        if (through != Long.MIN_VALUE) {
            camera.floorDrivenOver(through);
        }
    }

    // ---- asking Claude (explore on Claude U4) ----

    /** A Claude stop's scan look: keep it (and the detector's first sighting), and take them all (R1). */
    private void scanLook(long now, Look look, boolean ignorePeople) {
        scanned.add(look);
        scanHeadings.add(compass.usable(now) ? compass.degrees() : Double.NaN);
        if (detectorPick == null) {
            Sighting s = Sighting.choose(look.detections, tuning, ignorePeople);
            if (s.kind != Sighting.Kind.NOTHING) {
                detectorPick = s;
                detectorPickLook = scanned.size() - 1;
                note("detector saw " + s + " in look " + scanned.size());
            }
        }
        if (--scanLooksLeft > 0) {
            startCuriosityTurn(now, scanDir, tuning.scanTurnMs, tuning.scanTurnDeg, false);
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
        if (gatedPick != null) {
            // The pick is in; the recently-met check decides it (metCheckStep).
            return;
        }
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
            nothing(now, "Claude: nothing interesting here");
        } else if (a.status == CuriosityPort.Answer.Status.PICK && validPick(a)) {
            onPick(now, a);
        } else {
            note("Claude's answer failed or was unusable: " + a);
            retryOrFallback(now);
        }
    }

    private boolean validPick(CuriosityPort.Answer a) {
        return a.frame >= 0 && a.frame < askedFrames.size() && a.box != null && a.kind != null
                && usable(a.line);
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
        // People: the recently-met gate before any approach (explore nav plan U7, KTD8).
        if (a.kind == CuriosityPort.Kind.PERSON && anyoneMet(now)) {
            if (startMetCheck(now, askedFrames.get(a.frame).jpeg, a.box)) {
                gatedPick = a;
                return;
            }
            note("no recently-met check can go now: just met, a remark only");
            takePick(now, a, true);
            return;
        }
        // The prompt rules these out; a pick that ignores it wastes no more of the stop.
        // People are left alone per person instead (above), not by this cool-down.
        if (a.kind == CuriosityPort.Kind.ANIMAL && now < peopleIgnoredUntil) {
            nothing(now, "greeted people and animals recently: as good as nothing, carrying on");
            return;
        }
        if (!a.kind.isLiving() && seenLoosely(a.box.label)) {
            nothing(now, "reacted to that already: as good as nothing, carrying on");
            return;
        }
        takePick(now, a, false);
    }

    /**
     * Go with Claude's pick: face it, approaching only if the detector boxed it too
     * (KTD7); remark: a person just met, whose line is said without approaching or
     * meeting them (KTD8).
     */
    private void takePick(long now, CuriosityPort.Answer a, boolean remark) {
        int look = askedFrames.get(a.frame).look;
        pick = a;
        pickAt = now;
        remarkOnly = remark;
        remember(a.box.label, a.kind, now);
        float cx = a.box.centerX();
        pickRecentred = Math.abs(cx) > tuning.centreTolerance;
        Detection agree = remark ? null : detectorAgrees(scanned.get(look).detections, a);
        if (agree != null) {
            note("the detector sees it too, as a " + agree.label + ": approaching");
            target = agree;
            orient(now, look, cx, Then.FACE);
        } else {
            target = null;
            orient(now, look, cx, Then.SPEAK);
        }
    }

    /**
     * The detector's box in that look that is the same thing as Claude's (KTD7),
     * or null: the same broad kind, overlapping by at least pickMatchIou, and for
     * an OTHER pick, labels that agree loosely too. Live, a "potted plant" pick
     * drove him at a "dresser" box when the broad kind was the only rule.
     */
    private Detection detectorAgrees(List<Detection> detections, CuriosityPort.Answer a) {
        Detection best = null;
        float bestIou = -1f;
        for (Detection d : detections) {
            if (d.score < tuning.confidenceFloor || Sighting.BACKGROUND.contains(d.label)
                    || !CuriosityPort.Kind.of(d.label).sameBroadKind(a.kind)) {
                continue;
            }
            if (a.kind == CuriosityPort.Kind.OTHER && !labelsAgree(d.label, a.box.label)) {
                continue;
            }
            float iou = d.iou(a.box);
            if (iou >= tuning.pickMatchIou && iou > bestIou) {
                best = d;
                bestIou = iou;
            }
        }
        return best;
    }

    /** A stop that ends with nothing to react to: no line, back to wandering (R4). */
    private void nothing(long now, String why) {
        note(why);
        endCuriosity(now);
    }

    /** Whether a thing he reacted to this session (seen) loosely matches this label. */
    private boolean seenLoosely(String label) {
        for (String s : seen) {
            if (labelsAgree(s, label)) {
                return true;
            }
        }
        return false;
    }

    /** Words that describe rather than name a thing, so sharing one is no match. */
    private static final Set<String> DESCRIBING = new HashSet<String>(java.util.Arrays.asList(
            "a", "an", "the", "of", "and", "with", "on", "in", "some", "small", "big", "little", "large", "tiny",
            "huge", "old", "new", "green", "red", "blue", "yellow", "white", "black", "brown", "grey", "gray",
            "pink", "purple", "orange", "wooden", "wood", "metal", "plastic", "shiny", "round", "square", "tall",
            "short", "pair", "cute", "fluffy", "colorful", "colourful", "empty", "full"));

    /** Simple synonyms: one thing the detector and Claude may name differently. */
    private static final String[][] SYNONYMS = {
        {"plant", "potted plant", "pot plant", "houseplant", "succulent", "cactus", "fern", "flower", "bonsai"},
        {"cup", "mug", "glass", "teacup"},
        {"couch", "sofa", "settee"},
        {"tv", "television", "monitor", "screen", "tv monitor"},
        {"phone", "cell phone", "mobile phone", "smartphone", "cellphone"},
        {"dresser", "chest of drawers", "drawers", "cabinet", "cupboard", "sideboard"},
        {"table", "desk"},
        {"teddy bear", "teddy", "stuffed animal", "plush", "plushie", "soft toy"},
        {"lamp", "light"},
        {"laptop", "computer"},
    };

    /**
     * Whether two labels loosely name the same thing: a shared naming word
     * (plurals folded), or both in one synonym group. So "succulent" matches
     * "potted plant" and "green potted plant" matches "plant", but "potted plant"
     * never matches "dresser".
     */
    static boolean labelsAgree(String a, String b) {
        List<String> wa = labelWords(a);
        List<String> wb = labelWords(b);
        for (String w : wa) {
            if (!DESCRIBING.contains(w) && wb.contains(w)) {
                return true;
            }
        }
        for (String[] group : SYNONYMS) {
            if (mentions(wa, group) && mentions(wb, group)) {
                return true;
            }
        }
        return false;
    }

    private static boolean mentions(List<String> words, String[] group) {
        for (String term : group) {
            List<String> t = labelWords(term);
            for (int i = 0; i + t.size() <= words.size(); i++) {
                if (words.subList(i, i + t.size()).equals(t)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Lower-case words with a plural "s" folded ("plants" is "plant"). */
    private static List<String> labelWords(String label) {
        List<String> out = new ArrayList<String>();
        if (label == null) {
            return out;
        }
        for (String w : label.toLowerCase(java.util.Locale.ROOT).split("[^a-z]+")) {
            if (w.length() > 3 && w.endsWith("s") && !w.endsWith("ss")) {
                w = w.substring(0, w.length() - 1);
            }
            if (!w.isEmpty()) {
                out.add(w);
            }
        }
        return out;
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
        orient(now, detectorPickLook, 0f, Then.SIGHTING);
    }

    /**
     * Turn from the last scan look's heading to the given look's, plus the pick's
     * offset in the frame (cx, -1..1, positive = right; ignored within
     * centreTolerance), then carry on with `then`. Measured (explore nav plan U2,
     * R4): to the heading that look was taken at, less cx x cameraHalfFovDeg.
     * Timed: the scan stepped scanTurnMs toward scanDir between looks, and the
     * offset is cx x turnMsPerUnit.
     */
    private void orient(long now, int look, float cx, Then then) {
        stopMotors();
        state = State.ORIENT;
        afterOrient = then;
        boolean offCentre = Math.abs(cx) > tuning.centreTolerance;
        Double at = look < scanHeadings.size() ? scanHeadings.get(look) : null;
        if (compass.usable(now) && at != null && !at.isNaN()) {
            double delta = Heading.delta(compass.degrees(),
                    at - (offCentre ? cx * tuning.cameraHalfFovDeg : 0));
            if (Math.abs(delta) < tuning.turnToleranceDeg) {
                oriented(now);
                return;
            }
            Direction d = delta > 0 ? Direction.LEFT : Direction.RIGHT;
            note("turning " + d + " " + Math.round(Math.abs(delta)) + " deg toward it");
            show(EyeState.LOOK, d);
            startCuriosityTurn(now, d, timedMs(Math.abs(delta)), Math.abs(delta), true);
            return;
        }
        long offsetMs = offCentre
                ? (cx < 0 ? -1 : 1) * Math.max(100, (long) (Math.abs(cx) * tuning.turnMsPerUnit)) : 0;
        long scanSign = scanDir == Direction.RIGHT ? 1 : -1;
        long ms = (look - (scanned.size() - 1)) * tuning.scanTurnMs * scanSign + offsetMs;
        if (Math.abs(ms) < MIN_ORIENT_MS) {
            oriented(now);
            return;
        }
        Direction d = ms > 0 ? Direction.RIGHT : Direction.LEFT;
        note("turning " + d + " " + Math.abs(ms) + " ms toward it");
        show(EyeState.LOOK, d);
        startCuriosityTurn(now, d, Math.abs(ms), 0, true);
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
        if (pick.kind == CuriosityPort.Kind.PERSON && !remarkOnly) {
            enterMeetLook(now);
            return;
        }
        speak(now, pick.line);
    }

    // ---- meeting a person (explore on Claude U5; R9-R14, KTD3, KTD4) ----

    /**
     * MEET_LOOK: turned toward the person and stopped. The scan frame is from
     * before every turn and leg since, so the face comes from a fresh look
     * taken now (settled, the camera reopened if it had closed).
     */
    private void enterMeetLook(long now) {
        stopMotors();
        state = State.MEET_LOOK;
        show(EyeState.THINKING, null);
        // The detector's own box for it (tracked through FACE and APPROACH), else
        // Claude's box moved to where ORIENT's turn should have put it.
        meetExpect = target != null ? target : recentred(pick.box, pickRecentred);
        firstLook = !cameraOpen;
        waitForLook(now);
        note("a person: taking a fresh look at them");
    }

    private void meetLookStep(long now) {
        Look look = camera.latest();
        if (look != null && look.frameMs >= lookAfter && look.jpeg != null) {
            Detection box = personIn(look.detections, meetExpect, tuning.pickMatchIou);
            if (box != null) {
                note("the detector boxes the person in the fresh look");
                enterMeet(now, look.jpeg, box);
                return;
            }
            note("no person box in the fresh look; using Claude's box in the picked frame");
        } else if (now < lookDeadline) {
            return;
        } else {
            note("no fresh look in time; using Claude's box in the picked frame");
        }
        enterMeet(now, askedFrames.get(pick.frame).jpeg, pick.box);
    }

    /** Where the pick should be after ORIENT: its box shifted to the frame's centre when the turn included its offset. */
    static Detection recentred(Detection box, boolean recentred) {
        if (!recentred) {
            return box;
        }
        float dx = 0.5f - (box.x0 + box.x1) / 2f;
        return new Detection(box.label, box.score, box.x0 + dx, box.y0, box.x1 + dx, box.y1);
    }

    /**
     * The detector's person box in a fresh look that is the picked person: the
     * best IoU with where they should be, at least minIou; else the largest
     * person box holding that spot's centre; else null.
     */
    static Detection personIn(List<Detection> found, Detection expect, float minIou) {
        if (found == null || expect == null) {
            return null;
        }
        Detection best = null;
        float bestIou = 0f;
        Detection holding = null;
        float cx = (expect.x0 + expect.x1) / 2f;
        float cy = (expect.y0 + expect.y1) / 2f;
        for (Detection d : found) {
            if (CuriosityPort.Kind.of(d.label) != CuriosityPort.Kind.PERSON) {
                continue;
            }
            float iou = d.iou(expect);
            if (iou >= minIou && iou > bestIou) {
                best = d;
                bestIou = iou;
            }
            if (cx >= d.x0 && cx <= d.x1 && cy >= d.y0 && cy <= d.y1
                    && (holding == null || d.area() > holding.area())) {
                holding = d;
            }
        }
        return best != null ? best : holding;
    }

    /** MEET: thinking eyes while Claude compares the face in this frame's person box with the stored ones. */
    private void enterMeet(long now, byte[] frameJpeg, Detection personBox) {
        stopMotors();
        state = State.MEET;
        meetingHeld = true;
        stranger = null;
        meetLines = false;
        show(EyeState.THINKING, null);
        meetDeadline = now + tuning.meetTimeoutMs;
        note("a person: checking whether we've met");
        port.match(frameJpeg, personBox, tuning.meetTimeoutMs);
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
            line = ClaudeReplies.fill(a.namedLine, a.name.trim());
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

    /**
     * NAME: a reply was heard, so the face is kept either way, named or not (R12)
     * -- unless no face was found, when nothing is stored and the line he says
     * next makes no promise to remember them.
     */
    private void nameStep(long now) {
        CuriosityPort.Named n = port.foundName();
        if (n == null && now < meetDeadline) {
            return;
        }
        String name = n != null && n.status == CuriosityPort.Named.Status.NAME ? n.name : null;
        state = State.REMEMBER;
        meetDeadline = now + tuning.meetTimeoutMs;
        if (faceless()) {
            note("no face to remember them by; just saying hello");
            port.welcome(name, tuning.meetTimeoutMs);
            return;
        }
        note(name != null ? "got a name; remembering them" : "no clear name; remembering them unnamed");
        port.remember(name, tuning.meetTimeoutMs);
    }

    private boolean faceless() {
        return stranger != null && stranger.faceless;
    }

    private void rememberStep(long now) {
        boolean faceless = faceless();
        CuriosityPort.Answer a = faceless ? port.welcomed() : port.remembered();
        if (a == null && now < meetDeadline) {
            return;
        }
        if (a != null && a.status == CuriosityPort.Answer.Status.PICK && usable(a.line)) {
            speak(now, a.line);
        } else if (faceless && usable(stranger.noReplyLine)) {
            note("no hello line; saying the friendly line instead");
            speak(now, stranger.noReplyLine);
        } else {
            note("no remember line; carrying on");
            finishPick(now);
        }
    }

    /** Both person requests failed (KTD3): the detector's name clip, as before U4, and no asking. */
    private void nameClip(long now) {
        stopMotors();
        state = State.NAME_CLIP;
        stareAtPick();
        sound.playName(target != null ? target.label : "person");
        phaseUntil = now + tuning.nameMs;
    }

    /** The detector's target when it has one, else the height of Claude's box. */
    private void stareAtPick() {
        if (target != null) {
            stare(target);
        } else {
            stareAt(0f, pick.box.centerY());
        }
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
        // Close the camera and detector before speech begins, not at the end of
        // this step: synthesis competes with them for the CPU (R6, KTD6). Live,
        // a line after APPROACH took 2.8 s to first audio.
        syncCamera();
        stareAtPick();
        pendingLine = line;
        quietUntil = now + tuning.quietWaitMs;
        lineStarted(now);
    }

    /**
     * Hands the waiting line to the speech service once the camera and detector
     * are quiet (or after quietWaitMs, as a backstop). True once it is speaking.
     */
    private boolean lineStarted(long now) {
        if (pendingLine == null) {
            return true;
        }
        if (!camera.quiet()) {
            if (now < quietUntil) {
                return false;
            }
            note("camera or detector still busy after " + tuning.quietWaitMs + " ms; speaking anyway");
        }
        sayUntil = now + tuning.sayTimeoutMs;
        String line = pendingLine;
        pendingLine = null;
        port.say(line);
        return false;
    }

    private void finishPick(long now) {
        if (pick.kind.isLiving()) {
            peopleIgnoredUntil = now + tuning.peopleCooldownMs;
            if (meetingHeld) {
                // Their leave-alone starts now (R10): the port's handle, never a name.
                met.addLast(new Met(port.metId(), now));
                note("met someone: left alone for " + (tuning.metLeaveAloneMs / 1000) + " s ("
                        + met.size() + " met recently)");
            }
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

    // ---- wedged escapes (explore nav plan U5) ----

    /**
     * A measured turn the gyro says isn't turning (live, under a desk: "asked 120 deg,
     * turned 0"): less than turnStallDeg of progress for turnStallMs since the turn
     * started or last moved on. Only measured turns can tell; timed ones run as before.
     */
    private boolean turnBlocked(long now) {
        if (!measured || !compass.usable(now)) {
            return false;
        }
        double t = compass.turned();
        if (t >= turnProgressDeg + tuning.turnStallDeg) {
            turnProgressDeg = t;
            turnProgressAt = now;
        }
        return now - turnProgressAt >= tuning.turnStallMs;
    }

    /** A roaming, escape or curiosity turn that would not turn: stop at once, and he is wedged. */
    private void turnWouldNotTurn(long now) {
        stopMotors();
        note("measured turn blocked: turned " + Math.round(compass.turned()) + " of " + Math.round(turnDeg)
                + " deg in " + (now - turnStartedAt) + " ms");
        blockSide(heading);
        if (state == State.TURN && !turnRetrying && tuning.blockedTurnBackTicks > 0
                && !planner.hasLegToBackAlong(compass.legs(), compass.degrees())) {
            // Live, pinned after a CPL stop with no leg to back out along (the ladder backs
            // out along one when there is): a little room behind him is often all a turn needs.
            // The retry goes the other way (live 2026-09-25: left blocked, right free); a
            // roaming or escape turn's way doesn't matter, only its amount.
            retryDir = heading.opposite();
            retryEscape = escape;
            retryMs = turnMs;
            retryDeg = turnDeg;
            startShortBack(now);
            return;
        }
        turnRetrying = false;
        leaveStopForHazard();
        wedgeTurnDir = heading;
        wedgeTurnDeg = turnDeg;
        wedged(now, "a turn that would not turn", true);
    }

    /** The short blind back-up before a blocked roaming turn is tried again (BACK_OFF, backForTurn). */
    private void startShortBack(long now) {
        note("backing up a little, then turning the other way");
        int ticks = tuning.blockedTurnBackTicks;
        state = State.BACK_OFF;
        backForTurn = true;
        ticksLeft = ticks - 1;
        nextTickAt = now + tuning.backTickMs;
        phaseUntil = now + ticks * tuning.backTickMs;
        hopStartedAt = now;
        lastWheels = null;
        wheelMoves.clear();
        moving = true;
        motor.backTick();
        // Not logged as a leg: a retrace of it would only drive him back into the spot
        // it freed him from (and call that a clean escape).
    }

    /** Today's triggers, counted lower once the heading can steer an escape (wedgeHazards, wedgeStalls). */
    private boolean wedgedNow(long now) {
        return compass.usable(now) && (hazardTimes.size() >= tuning.wedgeHazards || stallStreak >= tuning.wedgeStalls);
    }

    /**
     * Wedged: with the heading usable, the escape planner's steps (retrace, circle,
     * way out, drive off); without it, today's rest.
     */
    private void wedged(long now, String why, boolean turnBlocked) {
        stopMotors();
        if (!Double.isNaN(doorway)) {
            note("wedged: the doorway at " + Math.round(doorway) + " deg is forgotten");
            forgetDoorway();
        }
        hopNext = false;
        plannedTicks = -1;
        lookForLeg = false;
        if (!compass.usable(now)) {
            enterCornered(now);
            return;
        }
        if (failedLadders > 0 && now - restEndedAt <= tuning.pinnedWindowMs) {
            // The first move after the rest is blocked too: still pinned. The ladder (and
            // its two Claude asks) waits out a longer rest instead of running again now.
            restEndedAt = Long.MIN_VALUE / 4;
            long rest = pinnedRestMs();
            note("still pinned after the rest: " + why + " (" + failedLadders + " failed escapes in a row); resting "
                    + rest + " ms before the next escape");
            rest(now, rest);
            ladderAfterRest = true;
            ladderTurnBlocked = turnBlocked;
            return;
        }
        startLadder(now, why, turnBlocked);
    }

    /**
     * The rest before the next ladder when still pinned: cooldownMs doubled for each
     * failed ladder in a row, capped at pinnedMaxRestMs.
     */
    private long pinnedRestMs() {
        long rest = tuning.cooldownMs;
        for (int i = 0; i < failedLadders && rest < tuning.pinnedMaxRestMs; i++) {
            rest *= 2;
        }
        return Math.min(rest, Math.max(tuning.cooldownMs, tuning.pinnedMaxRestMs));
    }

    /** The escape ladder from its first step (retrace, circle, way out, drive off). */
    private void startLadder(long now, String why, boolean turnBlocked) {
        note("wedged: " + why + " (" + hazardTimes.size() + " hazards, " + stallStreak + " stalls, "
                + failedLadders + " failed escapes in a row); escaping");
        hazardTimes.clear();
        escapeFailures.clear();
        planner.begin(now, compass.degrees());
        escDroveForward = false;
        escBackOutFirst = turnBlocked;
        escBackUpFirst = tuning.blockedTurnBackTicks > 0;
        escFirstRetry = false;
        escRetryDir = turnBlocked ? wedgeTurnDir : null;
        escRetryDeg = wedgeTurnDeg;
        escShortBack = false;
        circleDir = unblocked(escapeSide != null ? escapeSide : Direction.LEFT);
        escapePhase(now);
    }

    /** Enters the planner's current step. */
    private void escapePhase(long now) {
        switch (planner.phase()) {
            case RETRACE:
                state = State.RETRACE;
                show(EyeState.IDLE, null);
                if (escBackUpFirst) {
                    // Straight back first, blind and bounded like a blocked turn's back-up
                    // (its time, the stall watch), not logged as a leg.
                    escBackUpFirst = false;
                    note("backing up first: " + tuning.blockedTurnBackTicks + " back ticks");
                    escGoal = 0;
                    escGoalAfterRetry = 0;
                    escShortBack = true;
                    escThen = EscThen.FIRST_BACK;
                    esc = Esc.BACK_READY;
                    return;
                }
                if (escBackOutFirst) {
                    escBackOutFirst = false;
                    if (startBackOut(EscThen.RETRACE_NEXT)) {
                        return;
                    }
                }
                escWait(now, EscThen.RETRACE_NEXT);
                break;
            case CIRCLE:
                state = State.CIRCLE;
                if (!cameraWanted(now)) {
                    note("no camera for the circle");
                    planner.next(now);
                    escapePhase(now);
                    return;
                }
                show(EyeState.IDLE, null);
                escLook(now);
                break;
            case WAY_OUT:
                state = State.WAY_OUT;
                escAsked.clear();
                escAskedHeadings.clear();
                for (CuriosityPort.Frame f : planner.frames()) {
                    escAsked.add(f);
                    escAskedHeadings.add(planner.lookHeading(f.look));
                }
                askWayOut(now);
                break;
            case SECOND_ASK:
                state = State.WAY_OUT;
                if (!port.canAsk() || !cameraWanted(now)) {
                    note("no second way-out ask: " + (port.canAsk() ? "no camera" : "Claude unreachable"));
                    planner.next(now);
                    escapePhase(now);
                    return;
                }
                show(EyeState.THINKING, null);
                escLook(now);
                break;
            case DRIVE_OFF:
            case SECOND_DRIVE_OFF:
                state = State.DRIVE_OFF;
                escGoal = 0;
                escTurnTo(now, planner.driveHeading(), EscThen.DRIVE);
                break;
            case REST:
                if (tryForwardFirst(now, "before resting", false, ProbeThen.REST)) {
                    return;
                }
                ladderRest(now);
                break;
            default:
                break;
        }
    }

    /** The whole ladder failed: one failed escape, counted for the pinned rests. */
    private void ladderRest(long now) {
        planner.reset();
        esc = null;
        failedLadders++;
        stopMotors();
        note("cornered: " + failedLadders + " failed escapes in a row, " + hazardTimes.size()
                + " hazards; resting " + tuning.cooldownMs + " ms");
        rest(now, tuning.cooldownMs);
    }

    /** Each tick of an escape step: its budget, then what it is doing. */
    private void escapeTick(long now, boolean fresh, boolean hazard) {
        if (probing) {
            probeStep(now, fresh, hazard);
            return;
        }
        if (!compass.usable(now)) {
            stopMotors();
            cancelWayOut();
            planner.reset();
            esc = null;
            note("heading lost mid-escape: turning away as before");
            enterLook(now, escapeSide != null ? escapeSide : randomDirection(), true, tuning.escapeTurnMs,
                    tuning.escapeTurnDeg);
            return;
        }
        if (now >= planner.stepUntil() && !budgetWaits(now)) {
            escapeOutOfTime(now);
            return;
        }
        // A move made ready by this step starts on the same fresh reading.
        for (int i = 0; i < 4 && planner.active() && state.escapes(); i++) {
            Esc was = esc;
            escapeOnce(now, fresh, hazard);
            if (esc == was || !(esc == Esc.TURN_READY || esc == Esc.DRIVE_READY || esc == Esc.BACK_READY)) {
                break;
            }
        }
    }

    private void escapeOnce(long now, boolean fresh, boolean hazard) {
        if (esc == null) {
            return;
        }
        switch (esc) {
            case READY:
                // A fresh reading after the wait began: the last leg has closed in the log.
                if (fresh && now > escWaitSince) {
                    if (escThen == EscThen.RETRACE_NEXT) {
                        retraceNext(now);
                    } else {
                        escapePhase(now);
                    }
                }
                break;
            case TURN_READY:
                // Turning in place is how he gets out: a hazard in view doesn't stop it.
                if (fresh) {
                    heading = escDir;
                    turnDeg = escTurnAmount;
                    moving = true;
                    motor.turn(escDir);
                    turnStartedAt = now;
                    phaseUntil = now + timedMs(escTurnAmount);
                    measureTurn(now, escTurnAmount);
                    esc = Esc.TURNING;
                    if (measured) {
                        double rate = planner.turnRate(compass.turnRateDegS());
                        long more = planner.allowTurn(escTurnAmount, rate);
                        if (more > 0) {
                            note("the " + planner.phase() + " step gets " + more + " ms more for its "
                                    + Math.round(escTurnAmount) + " deg turn at " + Math.round(rate) + " deg/s");
                        }
                    }
                }
                break;
            case TURNING:
                if (turnBlocked(now)) {
                    stopMotors();
                    escTurnBlocked(now);
                } else if (turnDone(now)) {
                    stopMotors();
                    escAfterTurn(now);
                }
                break;
            case DRIVE_READY:
                if (!fresh) {
                    break;
                }
                if (hazard) {
                    escFailed(now, "the way ahead reads blocked");
                    break;
                }
                show(EyeState.IDLE, null);
                startEscapeMotion(now);
                escTicks = 1;
                nextTickAt = now + tuning.hopTickMs;
                esc = Esc.DRIVING;
                motor.hopTick();
                compass.startLeg(false, now);
                break;
            case DRIVING:
                escDriveStep(now, hazard);
                break;
            case BACK_READY:
                if (fresh) {
                    startEscapeMotion(now);
                    escUntil = now + (escShortBack ? tuning.blockedTurnBackTicks * tuning.backTickMs
                            : escGoal > 0 ? tuning.backOutMaxMs : tuning.backTicks * tuning.backTickMs);
                    nextTickAt = now + tuning.backTickMs;
                    esc = Esc.BACKING;
                    motor.backTick();
                    if (!escShortBack) {
                        // The short back-up is not a leg (see startShortBack).
                        compass.startLeg(true, now);
                    }
                }
                break;
            case BACKING:
                escBackStep(now);
                break;
            case LOOKING:
                escLookStep(now);
                break;
            case ASKING:
                escAskStep(now);
                break;
            default:
                break;
        }
    }

    /** A drive or back-out starts: its counts and stall watch from here. */
    private void startEscapeMotion(long now) {
        moving = true;
        escMoved = 0;
        escFrom = lastReading != null && lastReading.hasWheels() ? lastReading : null;
        hopStartedAt = now;
        lastWheels = null;
        wheelMoves.clear();
    }

    /** Driving or backing out in an escape: the counts it has moved, from each reading's encoders. */
    private boolean escapeDriving() {
        return state.escapes() && moving && (esc == Esc.DRIVING || esc == Esc.BACKING);
    }

    private void countEscapeWheels(SensorReading r) {
        if (!escapeDriving() || !r.hasWheels()) {
            return;
        }
        if (escFrom != null) {
            escMoved += (Math.abs(r.wheelLeft - escFrom.wheelLeft) + Math.abs(r.wheelRight - escFrom.wheelRight)) / 2;
        }
        escFrom = r;
    }

    private void escDriveStep(long now, boolean hazard) {
        boolean stalled = wheelsStalled(now);
        if (hazard || stalled) {
            stopMotors();
            aheadBlocked();
            note((hazard ? "hazard" : "wheels stalled") + " driving in the escape's " + planner.phase() + " after "
                    + escMoved + " counts");
            show(EyeState.FLINCH, null);
            if (escFirstRetry) {
                // The drive off after the first back-up: the retrace still follows, after the back-off.
                escFirstRetry = false;
                planner.restartStep(now);
            } else {
                if (planner.phase() == EscapePlanner.Phase.RETRACE) {
                    planner.addRetraced(escMoved);
                }
                planner.next(now);
            }
            if (tuning.backTicks > 0) {
                escGoal = 0;
                escThen = EscThen.PHASE;
                esc = Esc.BACK_READY;
            } else {
                escapePhase(now);
            }
            return;
        }
        boolean done = escGoal > 0 ? escMoved >= escGoal
                : escTicks >= tuning.escapeDriveTicks && now >= nextTickAt;
        if (done && escGoal <= 0 && escDroveNowhere(now)) {
            // Live, pinned: the drive-off's ticks ran out before the stall watch could
            // rule (grace + window), and "free" wiped the ladder; the next move was
            // blocked and a whole new ladder began. Its encoders decide instead.
            stopMotors();
            aheadBlocked();
            note("wheels stalled driving in the escape's " + planner.phase() + ": " + escMoved + " counts in "
                    + (now - hopStartedAt) + " ms");
            show(EyeState.FLINCH, null);
            if (escFirstRetry) {
                escFirstRetry = false;
                planner.restartStep(now);
            } else {
                planner.next(now);
            }
            if (tuning.backTicks > 0) {
                escGoal = 0;
                escThen = EscThen.PHASE;
                esc = Esc.BACK_READY;
            } else {
                escapePhase(now);
            }
            return;
        }
        if (done) {
            stopMotors();
            escDroveForward = true;
            if (escFirstRetry) {
                escFirstRetry = false;
                escapeFreed(now, "backed up, turned and drove off");
            } else if (planner.phase() == EscapePlanner.Phase.RETRACE) {
                planner.addRetraced(escMoved);
                escWait(now, EscThen.RETRACE_NEXT);
            } else {
                escapeFreed(now, "drove off");
            }
        } else if (now >= nextTickAt) {
            escTicks++;
            nextTickAt += tuning.hopTickMs;
            motor.hopTick();
        }
    }

    /**
     * The escape drive so far moved less than the stall rate (stallMinCounts, both
     * wheels, per stallWindowMs): the wheels went nowhere. False without encoders.
     */
    private boolean escDroveNowhere(long now) {
        long ms = Math.max(1, now - hopStartedAt);
        return escFrom != null && 2 * escMoved * tuning.stallWindowMs < tuning.stallMinCounts * ms;
    }

    /** Backing out (goal counts, bounded by backOutMaxMs) or a hazard's timed back-off (blind either way). */
    private void escBackStep(long now) {
        boolean reached = escGoal > 0 && escMoved >= escGoal;
        boolean stalled = (escGoal > 0 || escShortBack) && wheelsStalled(now);
        if (reached || stalled || now >= escUntil) {
            stopMotors();
            if (escShortBack) {
                escShortBack = false;
                escGoal = escGoalAfterRetry;
                note((stalled ? "back-up stalled after " : "backed up ") + escMoved + " counts");
            } else if (escGoal > 0) {
                planner.addRetraced(escMoved);
                if (stalled && !reached) {
                    planner.backOutStalled();
                    note("back-out stalled after " + escMoved + " counts");
                } else {
                    note("backed out " + escMoved + " counts");
                }
            }
            if (escThen == EscThen.FIRST_BACK) {
                afterFirstBack(now, escMoved >= tuning.stallMinCounts);
            } else if (escThen == EscThen.RETRY_TURN) {
                escThen = escThenAfterRetry;
                flipEscTurn(now);
                if (planner.phase() == EscapePlanner.Phase.RETRACE && !Double.isNaN(escTarget)
                        && escTurnAmount > tuning.retraceLongWayMaxDeg) {
                    escFailed(now, "the other way round is " + Math.round(escTurnAmount) + " deg");
                } else {
                    esc = Esc.TURN_READY;
                }
            } else if (escThen == EscThen.RETRACE_NEXT) {
                escWait(now, EscThen.RETRACE_NEXT);
            } else {
                escapePhase(now);
            }
        } else if (now >= nextTickAt) {
            nextTickAt += tuning.backTickMs;
            motor.backTick();
        }
    }

    /**
     * The ladder's first back-up is over; no back-out along the leg log follows it this
     * escape. It went nowhere (something behind him): the ladder goes on as before. It moved him: now
     * with room to pivot, the turn that would not turn is tried the free way and he
     * drives off; wedged some other way, a short leg forward if the way ahead looks clear.
     * Either failing, the ladder goes on from its retrace with the step's whole budget.
     */
    private void afterFirstBack(long now, boolean moved) {
        planner.restartStep(now);
        // One back-up per ladder, plus the per-blocked-turn short back-ups (reversing stays bounded).
        planner.backedUpFirst();
        escBackOutFirst = false;
        if (!moved) {
            note("backing up first went nowhere: on with the escape");
            escapePhase(now);
            return;
        }
        if (escRetryDir != null) {
            Direction d = unblocked(escRetryDir.opposite());
            double deg = Math.max(escRetryDeg, tuning.escapeCircleStepDeg);
            note("backed up: turning " + d + " " + Math.round(deg) + " deg with room to pivot, then driving off");
            escFirstRetry = true;
            escGoal = 0;
            escTurnBy(d, deg, EscThen.DRIVE);
            return;
        }
        if (tryForwardFirst(now, "after backing up", false, ProbeThen.STEP)) {
            return;
        }
        escapePhase(now);
    }

    /** The turn and drive after the first back-up failed: the ladder goes on from its retrace. */
    private void firstRetryFailed(long now, String why) {
        escFirstRetry = false;
        stopMotors();
        note("the free way after backing up failed: " + why + "; on with the escape");
        planner.restartStep(now);
        escapePhase(now);
    }

    /**
     * Straight back along the most recent forward leg(s) he is facing along, capped at
     * their logged distance and the retrace distance; false when the log has none.
     */
    private boolean startBackOut(EscThen then) {
        long counts = planner.backOutCounts(compass.legs(), compass.degrees());
        if (counts <= 0) {
            return false;
        }
        note("backing out straight along the way in: " + counts + " counts");
        escGoal = counts;
        escThen = then;
        esc = Esc.BACK_READY;
        return true;
    }

    /** Wait for the next fresh reading (the last leg closes on it), then go on. */
    private void escWait(long now, EscThen then) {
        escThen = then;
        escWaitSince = now;
        esc = Esc.READY;
    }

    /** The retrace's next move from the leg log as it is now, or its end. */
    private void retraceNext(long now) {
        EscapePlanner.Move m = planner.nextRetraceMove(compass.legs());
        if (m == null) {
            if (planner.retraced() > 0) {
                escapeFreed(now, "retraced " + planner.retraced() + " counts", escDroveForward);
            } else {
                note("nothing logged to retrace");
                planner.next(now);
                escapePhase(now);
            }
            return;
        }
        note("retrace: facing " + m);
        planner.tried(m.heading);
        escGoal = m.counts;
        escTurnTo(now, m.heading, EscThen.DRIVE);
    }

    private void escTurnTo(long now, double target, EscThen then) {
        double delta = Heading.delta(compass.degrees(), target);
        if (Math.abs(delta) < tuning.turnToleranceDeg) {
            escThen = then;
            escAfterTurn(now);
            return;
        }
        Direction d = delta > 0 ? Direction.LEFT : Direction.RIGHT;
        double deg = Math.abs(delta);
        if (unblocked(d) != d) {
            if (planner.phase() == EscapePlanner.Phase.RETRACE && 360 - deg > tuning.retraceLongWayMaxDeg) {
                // Not 20 s of turning the long way round (live 2026-09-25): the circle instead.
                escFailed(now, "the " + d + " side is blocked and the long way round is " + Math.round(360 - deg)
                        + " deg");
                return;
            }
            note("the " + d + " side is blocked: turning the long way round");
            d = d.opposite();
            deg = 360 - deg;
        }
        escTurnBy(d, deg, then);
        escTarget = target;
    }

    private void escTurnBy(Direction d, double deg, EscThen then) {
        escTarget = Double.NaN;
        escDir = d;
        escTurnAmount = deg;
        escThen = then;
        escBackedOut = false;
        esc = Esc.TURN_READY;
        show(EyeState.LOOK, d);
    }

    private void escAfterTurn(long now) {
        if (escThen == EscThen.LOOK) {
            escLook(now);
        } else {
            esc = Esc.DRIVE_READY;
        }
    }

    /** A turn in the escape that would not turn: back out once if the log allows, else the step failed. */
    private void escTurnBlocked(long now) {
        note("measured turn blocked: turned " + Math.round(compass.turned()) + " of " + Math.round(escTurnAmount)
                + " deg in " + (now - turnStartedAt) + " ms (" + escDir + ")");
        blockSide(escDir);
        if (escFirstRetry) {
            firstRetryFailed(now, "a turn that would not turn");
            return;
        }
        if (!escBackedOut) {
            escBackedOut = true;
            EscThen after = planner.phase() == EscapePlanner.Phase.RETRACE ? EscThen.RETRACE_NEXT : EscThen.RETRY_TURN;
            EscThen keep = escThen;
            if (startBackOut(after)) {
                if (after == EscThen.RETRY_TURN) {
                    // The retried turn keeps what follows it.
                    escThenAfterRetry = keep;
                }
                return;
            }
            if (tuning.blockedTurnBackTicks > 0) {
                // No leg to back out along: a short blind back-up, then the turn once more, the other way.
                note("backing up a little, then trying the turn the other way");
                escThenAfterRetry = keep;
                escGoalAfterRetry = escGoal;
                escGoal = 0;
                escShortBack = true;
                escThen = EscThen.RETRY_TURN;
                esc = Esc.BACK_READY;
                return;
            }
        }
        escFailed(now, "a turn that would not turn");
    }

    /** What follows a turn retried after a back-out. */
    private EscThen escThenAfterRetry;
    /** The escape's back-out is the short blind back-up before a retried turn (blockedTurnBackTicks). */
    private boolean escShortBack;
    /** The retried turn's drive goal (a retrace leg's counts), kept across the short back-up. */
    private long escGoalAfterRetry;
    /**
     * A roaming turn that would not turn: BACK_OFF is the short back-up before it is
     * tried again (backForTurn), with the turn to retry; turnRetrying marks the retry,
     * which, blocked too, is wedged as before.
     */
    private boolean backForTurn;
    private boolean turnRetrying;
    /**
     * The ways a measured turn would not turn since he last drove off cleanly (live
     * 2026-09-25: left was blocked while right and reversing were free, and every retry
     * and ladder turn went left again). A blocked turn is retried the other way, and
     * later turns go the unblocked way (unblocked()), even the long way round.
     */
    private final EnumMap<Direction, Long> blockedSides = new EnumMap<Direction, Long>(Direction.class);
    /** Each block is numbered, so with both ways blocked the older one is tried again first. */
    private long blockSeq;
    /** The escape turn's target heading (NaN: a circle step, whose way doesn't matter). */
    private double escTarget = Double.NaN;
    private Direction retryDir;
    private boolean retryEscape;
    private long retryMs;
    private double retryDeg;

    /**
     * d, unless d has been blocked since the last clean drive-off: then the other way.
     * With both ways blocked, the one blocked longer ago (it has had longest to come
     * free), so he alternates rather than trying one side for ever (live 2026-09-25:
     * "he only tries turning left").
     */
    private Direction unblocked(Direction d) {
        if (d == null || !blockedSides.containsKey(d)) {
            return d;
        }
        Long other = blockedSides.get(d.opposite());
        return other == null || other < blockedSides.get(d) ? d.opposite() : d;
    }

    private void blockSide(Direction d) {
        if (d != null) {
            blockedSides.put(d, ++blockSeq);
        }
    }

    /**
     * The escape turn to retry after its back-up, the other way round from the one
     * that was blocked: to the same target the long way (or the short way, if the
     * blocked turn was the long one), or, for a circle step, the same step the other
     * way, and the rest of the circle turns that way too.
     */
    private void flipEscTurn(long now) {
        Direction d = escDir.opposite();
        if (!Double.isNaN(escTarget)) {
            double delta = Heading.delta(compass.degrees(), escTarget);
            Direction shortWay = delta > 0 ? Direction.LEFT : Direction.RIGHT;
            escTurnAmount = d == shortWay ? Math.abs(delta) : 360 - Math.abs(delta);
        } else if (planner.phase() == EscapePlanner.Phase.CIRCLE) {
            circleDir = d;
        }
        escDir = d;
        note("trying the turn the other way: " + d + " " + Math.round(escTurnAmount) + " deg");
        show(EyeState.LOOK, d);
    }

    private void escFailed(long now, String why) {
        if (escFirstRetry) {
            firstRetryFailed(now, why);
            return;
        }
        stopMotors();
        EscapePlanner.Phase p = planner.phase();
        note("escape's " + p + " failed: " + why);
        planner.next(now);
        if (planner.phase() != EscapePlanner.Phase.REST
                && tryForwardFirst(now, "the " + p + " step failed", false, ProbeThen.STEP)) {
            return;
        }
        escapePhase(now);
    }

    /** One fresh stationary look, captured escapeSettleMs after he stopped (the bias re-estimated meanwhile). */
    private void escLook(long now) {
        esc = Esc.LOOKING;
        escLookAfter = now + tuning.escapeSettleMs;
        escLookBefore = camera.latest();
    }

    private void escLookStep(long now) {
        Look look = camera.latest();
        if (look == null || look == escLookBefore || look.frameMs < escLookAfter) {
            return;
        }
        double at = compass.degrees();
        if (planner.phase() == EscapePlanner.Phase.CIRCLE) {
            planner.addLook(at, look.openness, look.jpeg);
            note("circle look " + planner.looks() + " of " + tuning.escapeCircleSteps + " at " + Math.round(at) + " deg");
            if (planner.circleDone()) {
                planner.next(now);
                escapePhase(now);
            } else {
                circleDir = unblocked(circleDir);
                escTurnBy(circleDir, tuning.escapeCircleStepDeg, EscThen.LOOK);
            }
            return;
        }
        escAsked.clear();
        escAskedHeadings.clear();
        if (look.jpeg != null) {
            escAsked.add(new CuriosityPort.Frame(0, look.jpeg));
            escAskedHeadings.add(at);
        }
        askWayOut(now);
    }

    /** Claude's way out from escAsked (the circle's frames, or the second ask's one), within the step's budget. */
    private void askWayOut(long now) {
        boolean second = planner.phase() == EscapePlanner.Phase.SECOND_ASK;
        if (escAsked.isEmpty() || !port.canAsk()) {
            note("no way-out ask (" + escAsked.size() + " frames, Claude " + (port.canAsk() ? "set up" : "unreachable")
                    + ")");
            wayOutFailed(now);
            return;
        }
        show(EyeState.THINKING, null);
        note("asking Claude the way out (" + escAsked.size() + " frames" + (second ? ", second ask" : "") + ")");
        port.wayOut(new CuriosityPort.WayOutRequest(new ArrayList<CuriosityPort.Frame>(escAsked), second),
                Math.max(1, planner.stepUntil() - now));
        wayOutAsking = true;
        esc = Esc.ASKING;
    }

    private void escAskStep(long now) {
        CuriosityPort.WayOut a = port.wayOutAnswer();
        if (a == null) {
            return;
        }
        wayOutAsking = false;
        if (a.status == CuriosityPort.WayOut.Status.WAY && a.frame >= 0 && a.frame < escAsked.size()
                && a.x >= -1f && a.x <= 1f) {
            // Frame coordinates become a gyro heading the moment the answer arrives (KTD4).
            double h = EscapePlanner.aim(escAskedHeadings.get(a.frame), a.x, tuning.cameraHalfFovDeg);
            note("Claude's " + a + ": the way out is at " + Math.round(h) + " deg");
            planner.driveOff(now, h);
            escapePhase(now);
            return;
        }
        note("Claude's way-out answer is unusable: " + a);
        wayOutFailed(now);
    }

    /** No way out from Claude: the first ask falls back on the robot's own; the second ends in rest. */
    private void wayOutFailed(long now) {
        if (planner.phase() == EscapePlanner.Phase.SECOND_ASK) {
            planner.next(now);
            escapePhase(now);
        } else {
            onRobotWayOut(now);
        }
    }

    private void onRobotWayOut(long now) {
        double h = planner.bestOpenHeading(compass.degrees());
        note("way out on the robot: " + Math.round(h) + " deg (from " + planner.looks() + " looks)");
        planner.driveOff(now, h);
        escapePhase(now);
    }

    /** A step out of time has failed (U5's budgets); driving clear when it ran out has freed him. */
    private void escapeOutOfTime(long now) {
        EscapePlanner.Phase p = planner.phase();
        boolean clear = esc == Esc.DRIVING && moving && escTicks >= tuning.escapeFreeTicks
                && (escGoal > 0 || !escDroveNowhere(now));
        stopMotors();
        cancelWayOut();
        escShortBack = false;
        escFirstRetry = false;
        if (clear && (p == EscapePlanner.Phase.RETRACE || p == EscapePlanner.Phase.DRIVE_OFF
                || p == EscapePlanner.Phase.SECOND_DRIVE_OFF)) {
            if (p == EscapePlanner.Phase.RETRACE) {
                planner.addRetraced(escMoved);
            }
            escapeFreed(now, "driving clear when the step's time ran out");
            return;
        }
        note("escape's " + p + " out of time after " + (planner.budget(p) + planner.stepTurnMs()) + " ms ("
                + planner.budget(p) + " fixed, " + planner.stepTurnMs() + " for its turns)");
        if (p == EscapePlanner.Phase.WAY_OUT) {
            onRobotWayOut(now);
            return;
        }
        planner.next(now);
        if (planner.phase() != EscapePlanner.Phase.REST
                && tryForwardFirst(now, "the " + p + " step ran out of time", false, ProbeThen.STEP)) {
            return;
        }
        escapePhase(now);
    }

    /**
     * The step's budget has run out, but what he is doing now is not cut off for it:
     * a measured turn still making progress (the blocked-turn rule and turnBackstopMs
     * end one that isn't; live 2026-09-25, the drive-off's 3 s ran out 125 deg into a
     * turn that would have freed him), or a drive-off whose turn is done (he faces the
     * way out: its drive's ticks, hazards and stall watch decide).
     */
    private boolean budgetWaits(long now) {
        if (esc == Esc.TURNING && measured && compass.usable(now)) {
            return true;
        }
        EscapePlanner.Phase p = planner.phase();
        return (p == EscapePlanner.Phase.DRIVE_OFF || p == EscapePlanner.Phase.SECOND_DRIVE_OFF)
                && (esc == Esc.DRIVE_READY || esc == Esc.DRIVING);
    }

    // ---- forward first (live 2026-09-25: facing open floor, he rested, then turned away) ----

    /** A forward drive hit a hazard or stalled facing this way. */
    private void aheadBlocked() {
        blockedAheadAt = compass.usable(clock.nowMs()) ? compass.degrees() : Double.NaN;
    }

    /**
     * Before the ladder's next step, its rest, or the turn after a rest: a short leg
     * forward if the way ahead looks clear (the floor sensor, the camera if it has a
     * look since he last turned, and, except after a rest, no hazard or stall driving
     * this way just now). Returns whether it started; `then` follows if it is blocked.
     */
    private boolean tryForwardFirst(long now, String why, boolean afterRest, ProbeThen then) {
        if (tuning.escapeProbeTicks <= 0 || !compass.usable(now)
                || classifier.status(now) == HazardClassifier.Status.HAZARD) {
            return false;
        }
        if (!afterRest && !Double.isNaN(blockedAheadAt)
                && Math.abs(Heading.delta(compass.degrees(), blockedAheadAt)) <= tuning.escapeProbeClearDeg) {
            return false;
        }
        Look look = camera.latest();
        if (look != null && look.openness != null && look.frameMs >= headingSettledAt
                && steer.blockedAhead(look.openness)) {
            return false;
        }
        stopMotors();
        cancelWayOut();
        note("trying a short leg forward first: " + why);
        probing = true;
        probeThen = then;
        probeSince = now;
        state = State.DRIVE_OFF;
        show(EyeState.IDLE, null);
        escGoal = 0;
        esc = Esc.DRIVE_READY;
        return true;
    }

    private void probeStep(long now, boolean fresh, boolean hazard) {
        switch (esc == null ? Esc.READY : esc) {
            case DRIVE_READY:
                if (!fresh) {
                    break;
                }
                if (hazard) {
                    probeBlocked(now, "the way ahead reads blocked", false);
                    break;
                }
                startEscapeMotion(now);
                escTicks = 1;
                nextTickAt = now + tuning.hopTickMs;
                esc = Esc.DRIVING;
                motor.hopTick();
                compass.startLeg(false, now);
                break;
            case DRIVING: {
                boolean stalled = wheelsStalled(now);
                if (hazard || stalled) {
                    stopMotors();
                    probeBlocked(now, (hazard ? "hazard" : "wheels stalled") + " after " + escMoved + " counts", hazard);
                    break;
                }
                boolean done = escTicks >= tuning.escapeProbeTicks && now >= nextTickAt;
                if (done && escDroveNowhere(now)) {
                    stopMotors();
                    probeBlocked(now, "the wheels went nowhere (" + escMoved + " counts in " + (now - hopStartedAt)
                            + " ms)", false);
                } else if (done) {
                    stopMotors();
                    probing = false;
                    probeThen = null;
                    escapeFreed(now, "a short leg forward drove cleanly");
                } else if (now >= nextTickAt) {
                    escTicks++;
                    nextTickAt += tuning.hopTickMs;
                    motor.hopTick();
                }
                break;
            }
            case BACK_READY:
                if (fresh) {
                    startEscapeMotion(now);
                    escUntil = now + tuning.backTicks * tuning.backTickMs;
                    nextTickAt = now + tuning.backTickMs;
                    esc = Esc.BACKING;
                    motor.backTick();
                    compass.startLeg(true, now);
                }
                break;
            case BACKING:
                if (now >= escUntil) {
                    stopMotors();
                    probeDone(now);
                } else if (now >= nextTickAt) {
                    nextTickAt += tuning.backTickMs;
                    motor.backTick();
                }
                break;
            default:
                probeDone(now);
                break;
        }
    }

    /** The forward try is blocked: remembered for this heading; after a hazard, the usual back-off first. */
    private void probeBlocked(long now, String why, boolean backOff) {
        note("short leg forward blocked: " + why);
        aheadBlocked();
        show(EyeState.FLINCH, null);
        if (backOff && tuning.backTicks > 0) {
            esc = Esc.BACK_READY;
        } else {
            probeDone(now);
        }
    }

    /** What the forward try stood in front of: the ladder's step, its rest, or the turn after a rest. */
    private void probeDone(long now) {
        ProbeThen then = probeThen;
        probing = false;
        probeThen = null;
        esc = null;
        if (then == ProbeThen.STEP) {
            planner.restartStep(now);
            escapePhase(now);
        } else if (then == ProbeThen.REST) {
            ladderRest(now);
        } else {
            afterRest(now);
        }
    }

    /** Out: whatever wedged him is behind him, as after a clean leg. */
    private void escapeFreed(long now, String how) {
        escapeFreed(now, how, true);
    }

    /**
     * droveForward false: freed by backing out alone. The ways a turn would not turn
     * stay avoided then (live 2026-09-25, "he only tries turning left": a back-out that
     * freed him wiped the blocked side, and the next turn went left into it again).
     */
    private void escapeFreed(long now, String how, boolean droveForward) {
        stopMotors();
        note("free after " + (now - (planner.active() ? planner.startedAt() : probeSince)) + " ms: " + how);
        if (droveForward) {
            blockedSides.clear();
        }
        blockedAheadAt = Double.NaN;
        ladderAfterRest = false;
        planner.reset();
        esc = null;
        compass.droveOffCleanly();
        hazardTimes.clear();
        stallStreak = 0;
        failedLadders = 0;
        escapeFailures.clear();
        escapeSide = null;
        lastHazardSide = null;
        if (!sayHeldLine(now)) {
            enterPause(now, pauseMs(), false);
        }
    }

    private void cancelWayOut() {
        if (wayOutAsking) {
            wayOutAsking = false;
            port.cancelWayOut();
        }
    }

    // ---- open doorways (explore nav plan U6, R7, R8, KTD4) ----

    /**
     * Each step: an answer to take, an ask to drop (he left roaming) or give up on,
     * a remembered doorway expiring, and a new ask when one is due.
     */
    private void doorwayStep(long now) {
        if (doorwayAsking) {
            if (!state.roams() || state.escapes() || state == State.CORNERED) {
                cancelDoorway(now, "not roaming");
            } else if (now - doorwayAskAt >= tuning.doorwayAskTimeoutMs) {
                cancelDoorway(now, "no answer in " + tuning.doorwayAskTimeoutMs + " ms");
            } else {
                CuriosityPort.Doorway a = port.doorwayAnswer();
                if (a != null) {
                    doorwayAsking = false;
                    doorwayNextAskAt = now + tuning.doorwayAskMs;
                    doorwayAnswered(now, a);
                }
            }
        }
        if (!Double.isNaN(doorway) && (now - doorwaySetAt >= tuning.doorwayExpireMs
                || forwardCounts - doorwaySetCounts >= tuning.doorwayExpireCounts)) {
            note("doorway heading expired after " + (now - doorwaySetAt) + " ms, "
                    + (forwardCounts - doorwaySetCounts) + " counts");
            forgetDoorway();
        }
        if (!doorwayAsking && now >= doorwayNextAskAt) {
            askDoorway(now);
        }
    }

    /**
     * Only while roaming straight or paused (never a turn, a hazard reaction, a stop,
     * a meeting or an escape), with a fresh look taken since he last turned: the
     * heading he faces now is the frame's.
     */
    private void askDoorway(long now) {
        if (!(state == State.PAUSE || state == State.HOP) || lookForLeg || escape || !cameraOpen
                || !compass.usable(now) || !port.canAsk()) {
            return;
        }
        Look look = roamLook(now);
        if (look == null || look.jpeg == null) {
            return;
        }
        doorwayAskFacing = compass.degrees();
        doorwayAskAt = now;
        doorwayAsking = true;
        note("asking Claude for an open doorway (facing " + Math.round(doorwayAskFacing) + " deg)");
        port.doorway(look.jpeg, tuning.doorwayAskTimeoutMs);
    }

    /** The answer becomes a heading from the frame's (KTD4); none leaves the steering as it was. */
    private void doorwayAnswered(long now, CuriosityPort.Doorway a) {
        if (a.status == CuriosityPort.Doorway.Status.DOOR && a.x >= -1f && a.x <= 1f) {
            doorway = EscapePlanner.aim(doorwayAskFacing, a.x, tuning.cameraHalfFovDeg);
            doorwaySetAt = now;
            doorwaySetCounts = forwardCounts;
            doorwayLeg = false;
            note("Claude sees an " + a + ": remembered at " + Math.round(doorway) + " deg");
        } else if (a.status == CuriosityPort.Doorway.Status.NONE) {
            note("Claude sees no open doorway");
        } else {
            note("doorway ask failed: roaming on");
        }
    }

    /** Drop the running ask (why null: quietly); the interval restarts from now. */
    private void cancelDoorway(long now, String why) {
        if (!doorwayAsking) {
            return;
        }
        doorwayAsking = false;
        port.cancelDoorway();
        doorwayNextAskAt = now + tuning.doorwayAskMs;
        if (why != null) {
            note("doorway ask dropped: " + why);
        }
    }

    /** The remembered doorway's bearing from his facing (left positive), NaN for none or no usable heading. */
    private double doorwayBearing(long now) {
        if (Double.isNaN(doorway) || !compass.usable(now)) {
            return Double.NaN;
        }
        return Heading.delta(compass.degrees(), doorway);
    }

    private void forgetDoorway() {
        doorway = Double.NaN;
        doorwayLeg = false;
    }

    // ---- people while roaming (explore nav plan U7, R9, R10, KTD4, KTD8) ----

    /**
     * A person in the leg decision's look (Claude set up, so he can meet them): true
     * if he goes over to meet them. While someone met in the last metLeaveAloneMs is
     * on the list, the person counts as just met unless a recently-met check cleared
     * a person within metClearedMs; a check goes out in the background when one is
     * allowed, and he keeps roaming meanwhile.
     */
    private boolean seePerson(long now, Look look) {
        if (look == null || look.jpeg == null) {
            return false;
        }
        Detection p = personBox(look.detections);
        if (p == null || !port.canAsk()) {
            return false;
        }
        if (anyoneMet(now) && now >= metClearedUntil) {
            startMetCheck(now, look.jpeg, p);
            return false;
        }
        metClearedUntil = Long.MIN_VALUE / 4;
        approachPerson(now, look, p);
        return true;
    }

    /** The largest person box at or above the confidence floor, else null. */
    private Detection personBox(List<Detection> found) {
        Detection best = null;
        for (Detection d : found) {
            if (d.score >= tuning.confidenceFloor && CuriosityPort.Kind.of(d.label) == CuriosityPort.Kind.PERSON
                    && (best == null || d.area() > best.area())) {
                best = d;
            }
        }
        return best;
    }

    /**
     * A synthetic PERSON pick (KTD8): the roaming look is its frame 0 and the box its
     * pick, so FACE, APPROACH (to the polite distance), MEET_LOOK and the meeting run
     * as for Claude's person pick at a stop. Started on a fresh reading (decide).
     */
    private void approachPerson(long now, Look look, Detection p) {
        note("a person while roaming: going over to meet them");
        lookForLeg = false;
        hopNext = false;
        plannedTicks = -1;
        doorwayLeg = false;
        claudeStop = true;
        heldPick = null;
        scanned.clear();
        scanHeadings.clear();
        askedFrames.clear();
        scanned.add(look);
        scanHeadings.add(compass.usable(now) ? compass.degrees() : Double.NaN);
        askedFrames.add(new CuriosityPort.Frame(0, look.jpeg));
        pick = CuriosityPort.Answer.pick(0, p, CuriosityPort.Kind.PERSON, null);
        pickAt = now;
        pickRecentred = false;
        remarkOnly = false;
        remember(p.label, CuriosityPort.Kind.PERSON, now);
        target = p;
        state = State.FACE;
        faceTurns = 0;
        lostLooks = 0;
        firstLook = false;
        face(now);
    }

    /** A person box close enough to meet them: politeHeight of the frame's height (R9). */
    private boolean polite(Detection d) {
        return CuriosityPort.Kind.of(d.label) == CuriosityPort.Kind.PERSON && d.height() >= tuning.politeHeight;
    }

    /** Whether anyone was met in the last metLeaveAloneMs (older meetings are dropped here). */
    private boolean anyoneMet(long now) {
        while (!met.isEmpty() && now - met.peekFirst().endedAt >= tuning.metLeaveAloneMs) {
            met.pollFirst();
        }
        return !met.isEmpty();
    }

    /**
     * Send a recently-met check for this person, if one may go now: none out, the
     * interval since the last one passed, and everyone on the list has a face to
     * compare against. False: none went, so the person counts as just met.
     */
    private boolean startMetCheck(long now, byte[] jpeg, Detection box) {
        if (metChecking || now < metNextCheckAt || jpeg == null || box == null || !port.canAsk()) {
            return false;
        }
        List<String> ids = new ArrayList<String>();
        for (Met m : met) {
            if (m.id == null) {
                // Someone met without a face to compare: nobody can be told apart from them.
                return false;
            }
            ids.add(m.id);
        }
        metChecking = true;
        metCheckAt = now;
        metNextCheckAt = now + tuning.metCheckIntervalMs;
        note("asking Claude whether this person was just met (" + ids.size() + " met recently)");
        port.recentlyMet(new CuriosityPort.RecentlyMetRequest(jpeg, box, ids), tuning.metCheckTimeoutMs);
        return true;
    }

    /**
     * Each step: the check's answer, or its deadline. Only "none of them" lets him
     * approach: the stop waiting on it goes on with the pick, or a roaming person is
     * cleared for metClearedMs. Anything else is just met: the stop's pick becomes a
     * remark, and roaming carries on.
     */
    private void metCheckStep(long now) {
        if (!metChecking) {
            return;
        }
        CuriosityPort.Recently a;
        if (now - metCheckAt >= tuning.metCheckTimeoutMs) {
            cancelMetCheck();
            note("no recently-met answer in " + tuning.metCheckTimeoutMs + " ms");
            a = CuriosityPort.Recently.failed();
        } else {
            a = port.recentlyMetAnswer();
            if (a == null) {
                return;
            }
            metChecking = false;
        }
        boolean cleared = a.status == CuriosityPort.Recently.Status.DIFFERENT;
        note("recently-met check: " + a + (cleared ? ": someone new" : ": just met, leaving them alone"));
        if (gatedPick != null) {
            CuriosityPort.Answer g = gatedPick;
            gatedPick = null;
            if (state == State.ASK) {
                takePick(now, g, !cleared);
            }
        } else if (cleared) {
            metClearedUntil = now + tuning.metClearedMs;
        }
    }

    private void cancelMetCheck() {
        if (metChecking) {
            metChecking = false;
            port.cancelRecentlyMet();
        }
    }

    // ---- entering states ----

    private void enterEyesOnly(String why) {
        stopMotors();
        cancelAsk();
        cancelWayOut();
        planner.reset();
        esc = null;
        probing = false;
        probeThen = null;
        escShortBack = false;
        backForTurn = false;
        turnRetrying = false;
        cancelDoorway(clock.nowMs(), "lease or sensors lost");
        cancelMetCheck();
        gatedPick = null;
        remarkOnly = false;
        meetingHeld = false;
        hopNext = false;
        plannedTicks = -1;
        lookForLeg = false;
        target = null;
        pick = null;
        heldPick = null;
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
        lookForLeg = false;
        phaseUntil = now + ms;
        if (!keepEyes) {
            show(EyeState.IDLE, null);
        }
    }

    /** Eyes toward d, then a turn of ms, or deg once measured (0: timed only). */
    private void enterLook(long now, Direction d, boolean escapeTurn, long ms, double deg) {
        // Every turn through here is unaimed (its way doesn't matter, only its amount):
        // it goes the unblocked way. Aimed ones (the steer's bend) come round already.
        d = unblocked(d);
        state = State.LOOK;
        turnRetrying = false;
        heading = d;
        escape = escapeTurn;
        turnMs = ms;
        turnDeg = deg;
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
        measureTurn(now, turnDeg);
    }

    private void startHop(long now) {
        hopNext = false;
        show(EyeState.IDLE, null);
        state = State.HOP;
        int ticks = plannedTicks > 0 ? plannedTicks : drawTicks();
        plannedTicks = -1;
        legLook = camera.latest();
        ticksLeft = ticks - 1;
        nextTickAt = now + tuning.hopTickMs;
        phaseUntil = now + ticks * tuning.hopTickMs;
        hopStartedAt = now;
        lastWheels = null;
        wheelMoves.clear();
        hopMoved = 0;
        moving = true;
        motor.hopTick();
        compass.startLeg(false, now);
    }

    /** This roaming leg's encoders (both wheels) moved less than the stall rate: he went nowhere. */
    private boolean legWentNowhere(long now) {
        long ms = Math.max(1, now - hopStartedAt);
        return lastWheels != null && hopMoved * tuning.stallWindowMs < tuning.stallMinCounts * ms;
    }

    /** A leg's length as before the steer: random in hopTicks..hopTicksMax. */
    private int drawTicks() {
        return tuning.hopTicksMax > tuning.hopTicks
                ? tuning.hopTicks + random.nextInt(tuning.hopTicksMax - tuning.hopTicks + 1)
                : tuning.hopTicks;
    }

    /** The escape turn's minimum: longer for each stall in a row (see HOP). */
    private long escapeTurnMs() {
        if (!stalledNow) {
            return tuning.escapeTurnMs;
        }
        return Math.min(tuning.escapeSweepMaxMs, tuning.stallTurnMs + (stallStreak - 1) * tuning.stallTurnStepMs);
    }

    /** The same minimum in degrees, for a measured escape turn. */
    private double escapeTurnDeg() {
        if (!stalledNow) {
            return tuning.escapeTurnDeg;
        }
        return Math.min(tuning.escapeSweepDeg, tuning.stallTurnDeg + (stallStreak - 1) * tuning.stallTurnStepDeg);
    }

    private void startBackOff(long now) {
        int ticks = stalledNow ? Math.max(tuning.backTicks, tuning.stallBackTicks) : tuning.backTicks;
        if (ticks <= 0) {
            enterLook(now, escapeDir, true, escapeTurnMs(), escapeTurnDeg());
            return;
        }
        state = State.BACK_OFF;
        ticksLeft = ticks - 1;
        nextTickAt = now + tuning.backTickMs;
        phaseUntil = now + ticks * tuning.backTickMs;
        moving = true;
        motor.backTick();
        compass.startLeg(true, now);
    }

    private void enterCornered(long now) {
        stopMotors();
        note("cornered: " + escapeFailures.size() + " failed escapes, " + hazardTimes.size()
                + " hazards; resting " + tuning.cooldownMs + " ms");
        rest(now, tuning.cooldownMs);
    }

    /** The cornered rest: eyes resting, no motion, for restMs; then the wider turn. */
    private void rest(long now, long restMs) {
        stopMotors();
        hazardTimes.clear();
        escapeFailures.clear();
        ladderAfterRest = false;
        state = State.CORNERED;
        phaseUntil = now + restMs;
        show(EyeState.RESTING, null);
    }

    // ---- helpers ----

    private void stopMotors() {
        if (moving) {
            if (measured && heading != null && compass.turnReached()) {
                // A turn that got there: that way turns again.
                blockedSides.remove(heading);
            }
            moving = false;
            motor.stop();
            long now = clock.nowMs();
            compass.stopped(now);
            if (!drivingForward()) {
                // A turn (or back-off) ended: looks from before it faced elsewhere.
                headingSettledAt = now;
            }
        }
    }

    /** A forward leg is under way: a roaming hop, or an approach leg. */
    private boolean drivingForward() {
        return moving && (state == State.HOP || (state == State.APPROACH && step == Step.LEG)
                || (state.escapes() && esc == Esc.DRIVING));
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
