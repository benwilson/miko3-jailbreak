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
 *   CORNERED   too many hazards too fast: resting eyes, no motion (KTD8); also the
 *              fully jammed rest (robot 2026-10-01): the help line, jammedRestMs, one short back-up
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
 *   REMEMBER   with a name, the face is stored under it and Claude writes the
 *              "I'll remember you" line; without one (or without a face)
 *              nothing is stored and he just says hello (R19), then SPEAK
 *   NAME_CLIP  both person requests failed: the detector's name clip, no asking
 *   CONFIRM    a close match (face plan U7, KTD6): the local "Is that you, {name}?",
 *              one listen, AnswerParser; yes adds the photo and starts known, a
 *              name goes to the port's resolver, anything else starts a stranger
 *   LAST_NAME  the resolver found the name but the face is weak for them: the
 *              local "And your last name?", one listen; no reply stores nobody
 *
 *   MEET -> known: SPEAK the named line ({name} filled in here) or the unnamed
 *                  line, and touch them
 *        -> new:   ASK_NAME -> LISTEN -> nothing heard: SPEAK the no-reply line,
 *                  store nothing (R12)
 *                            -> words: NAME -> REMEMBER -> SPEAK (a name: stored;
 *                               no name: nothing kept, R19)
 *        -> failed: lines request -> ASK_NAME ..., or NAME_CLIP
 *   Every failure or missed deadline ends the stop and he carries on exploring.
 *   A roaming person pick (the detector's, while roaming) meets only with a usable
 *   face; anything else drops it quietly (owner 2026-10-01). A call's meeting never does.
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
 *  - CPL=2 (the controller refusing forward, R9) is a hazard like any other,
 *    with one exception (owner-approved 2026-09-25): mid-leg, while our own
 *    floor sensor reads plain floor (no edge, no obstacle, no fault), it is
 *    retried once after a short stop (cplRetryPauseMs), on a fresh clear
 *    reading, and not counted toward hazards in a row. Never at an edge or
 *    obstacle, never twice in a row: CPL again at or soon after the retry is a
 *    hazard, and the cornered cap stops a stuck CPL=2 from looping.
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
 * gave no look in time (he roams on the floor sensor meanwhile, R5); a curiosity
 * stop's miss retries once and only a second in a row closes it, for
 * curiosityBackoffMs. Each leg is
 * chosen from the newest fresh look's openness by RoamSteer (a bend toward the
 * most open columns, a length cut by blocked ones; a low-confidence profile
 * chooses as before), and a fresh look reading the way ahead blocked ends a leg
 * at the next tick. With no such look yet at a decision (none taken since the last
 * turn settled), it waits in PAUSE up to steerWaitMs for one while the camera is
 * open and not backed off, then chooses as before. Mid-leg, a fresh look whose best
 * open band lies reaimMinDeg or more off centre stops the leg, turns a little toward
 * it by the gyro and drives the rest (owner-approved 2026-09-25; the drive can't
 * curve). The floor sensor, stall sensing and CPL=2 still decide every
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
 * Going somewhere new (explore nav plan U10, R18): Coverage dead-reckons where he
 * has been this session from the heading and the signed wheel counts, in fading
 * ~0.5 m cells, and is forgotten at shutdown. With the heading usable, each leg
 * decision hands RoamSteer the novelty of every bearing (open new ground wins over
 * equally open visited ground, a fully open new leg runs long, a view of only
 * covered ground turns him toward newer ground), and without a camera plan a new
 * way ahead cuts the random turn chance and a turn goes the newest way it may.
 * The hazard's side, a blocked side, the floor sensor, stalls, CPL and escapes rule
 * as before; with no usable heading nothing changes. Notes carry counts only.
 *
 * Somewhere he has not looked lately (owner 2026-10-01; PlaceMemory): a look whose
 * camera made a place print is scored against the prints of the last 30 minutes
 * from roughly the same heading (any, with the heading unusable) and kept. Its
 * novelty stands for the bands in view, and for its heading for a while (a scan's
 * looks score the headings it turned through); the steer takes the lower of that
 * and the grid's, and a scan turns toward the less familiar side. A look that
 * matches notes "place: seen before (sim X.XX, N min ago)", each memory once in a
 * row. It only lowers novelty: blocked bands and every safety stop rule as before;
 * looks without a print (or plain ones) change nothing.
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
 * Spoken to (meeting plan U7; R1-R3, R6-R9, R15; KTD3-KTD6, KTD8): the launcher's
 * ears arrive as Ears step input, drained once per tick and handled per state
 * (drainEars, cueVerdict). A taken cue stops the wheels in that step and runs:
 *
 *   CUE_TURN   eyes glance to the voice's side, then bounded measured steps toward
 *              it, re-read from the angle's trend; between looks, the turn to the
 *              next look of the plan
 *   CUE_LOOK   attentive eyes; the camera decides within leanInMs of being ready:
 *              a face turned toward him (the facing-face box test) plays the
 *              acknowledgement and enters MEET; nothing, after the plan's looks
 *              (strong: side, behind, other side; weak: side, opposite), is a quiet
 *              PAUSE with nothing sent
 *
 * Held cues wait for the escape or the line to end, expire after cueHoldMs (a
 * strong one becoming a lean-in); a shove arms a weak cue only while stopped. A
 * call (a strong wake word or name, hey-miko plan U5) has its own slot instead:
 * never dropped, answered with a local clip the moment KTD2's order allows, and,
 * on the charger or in EYES_ONLY, met without a turn. The ears stay open on the
 * charger. The call's own search (hey-miko plan U6) runs in CUE_TURN and CUE_LOOK
 * with its own plan (its bearing and the two neighbours, or eight looks round one
 * circle), counts any person box tall enough as found, goes over to a far one
 * through FACE and APPROACH, and when nobody is found says "Where'd you go?" and
 * listens in CUE_WHERE. The conversation the meeting becomes is U8's (CHAT states).
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

        /**
         * Park the detector (meeting plan U8, KTD7): the stream stays open but no frame
         * runs through the detector, so speech synthesis has the CPU during a
         * conversation; false runs it again for a look.
         */
        default void park(boolean parked) {
        }
    }

    /**
     * One recognized camera frame: when it was captured (brain clock), what was in
     * it, and the frame's JPEG (null when the camera didn't keep it), which the
     * look request sends to Claude (explore on Claude U4), and its openness profile
     * (explore nav plan U3; null when the camera didn't score it), and its place
     * print (PlaceMemory; null when the camera didn't make one).
     */
    static final class Look {
        final long frameMs;
        final List<Detection> detections;
        final byte[] jpeg;
        final Openness.Profile openness;
        final PlaceMemory.Print place;

        Look(long frameMs, List<Detection> detections) {
            this(frameMs, detections, null);
        }

        Look(long frameMs, List<Detection> detections, byte[] jpeg) {
            this(frameMs, detections, jpeg, null);
        }

        Look(long frameMs, List<Detection> detections, byte[] jpeg, Openness.Profile openness) {
            this(frameMs, detections, jpeg, openness, null);
        }

        Look(long frameMs, List<Detection> detections, byte[] jpeg, Openness.Profile openness,
             PlaceMemory.Print place) {
            this.frameMs = frameMs;
            this.detections = detections;
            this.jpeg = jpeg;
            this.openness = openness;
            this.place = place;
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
    /** ...and GLANCE: eyes sliding toward a voice (gaze is its side; meeting plan R3, KTD12),
     * and LISTENING: attentive, looking for a face turned toward him or hearing a reply. */
    enum EyeState { IDLE, LOOK, FLINCH, RESTING, EYES_ONLY, STARE, THINKING, GLANCE, LISTENING }

    enum State {
        EYES_ONLY, PAUSE, LOOK, TURN, HOP, STARTLE, BACK_OFF, CORNERED, STOPPED,
        SCAN, FACE, APPROACH, INSPECT, REACT_HERE,
        ASK, ORIENT, MEET_LOOK, MEET, SPEAK,
        ASK_NAME, LISTEN, NAME, REMEMBER, NAME_CLIP,
        CONFIRM, LAST_NAME,
        RETRACE, CIRCLE, WAY_OUT, DRIVE_OFF,
        CUE_TURN, CUE_LOOK, CUE_WHERE,
        CHAT_THINK, CHAT_SPEAK, CHAT_LISTEN, CHAT_NOTES;

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

        /**
         * The turn to a voice and the look that decides (meeting plan U7, KTD4, KTD7):
         * the camera is open in these, as in a curiosity stop's looking states.
         */
        boolean cueSearch() {
            return this == CUE_TURN || this == CUE_LOOK || this == CUE_WHERE;
        }

        /**
         * The conversation (meeting plan U8, KTD7): the camera stays open with the
         * detector parked, looks run only in CHAT_LISTEN, and a lost lease does not
         * end it.
         */
        boolean chats() {
            return this == CHAT_THINK || this == CHAT_SPEAK || this == CHAT_LISTEN || this == CHAT_NOTES;
        }

        /**
         * Who someone is, decided on the robot before either conversation start
         * (face plan U7, KTD6): "Is that you, {name}?" and "And your last name?",
         * each spoken locally and listened to once.
         */
        boolean confirms() {
            return this == CONFIRM || this == LAST_NAME;
        }

        /** Heading for, or meeting, a person he found: FACE, APPROACH or MEET_LOOK. */
        boolean approachesPerson() {
            return this == FACE || this == APPROACH || this == MEET_LOOK;
        }

        /**
         * A conversation with someone (hey-miko plan KTD2 step 2): the meeting and
         * everything after it, up to the end of the CHAT states. A call waits these out.
         */
        boolean converses() {
            return chats() || confirms() || this == MEET || this == ASK_NAME || this == LISTEN || this == NAME
                    || this == REMEMBER || this == NAME_CLIP;
        }

        /** Part of a curiosity stop, from the scan until he is back to wandering. */
        boolean inStop() {
            return curious() || cueSearch() || chats() || this == ASK || this == ORIENT || this == MEET
                    || this == SPEAK || this == ASK_NAME || this == LISTEN || this == NAME || this == REMEMBER
                    || this == NAME_CLIP || confirms();
        }
    }

    /**
     * The state page's counters and stage stamps (meeting plan U7, KTD14): what
     * the cues did and when each stage of a meeting landed. ModeApp publishes
     * them beside the eye state; the harness rig counts them. Keys are the
     * /state field names. LINE_REQUESTED and FIRST_SOUND are stamped by the
     * conversation (U8).
     */
    interface Gauges {
        enum Counter {
            CUES("cues"), STRONG_CUES("strongCues"), WEAK_CUES("weakCues"), LEAN_INS("leanIns"),
            SEARCHES("searches"), FACES_FOUND("facesFound"), QUIET_RESUMES("quietResumes"),
            CUES_HELD("cuesHeld"), CUES_DROPPED("cuesDropped"), RETARGETS("retargets"), SHOVES("shoves"),
            /** A question the conversation model repeated after the re-request (U8, KTD9). */
            REPEATS("repeats"),
            /** A curiosity stop's remark handed to the speech service (the remark rate, owner 2026-10-01). */
            REMARKS("remarks");

            final String key;

            Counter(String key) {
                this.key = key;
            }
        }

        enum Stage {
            CUE_AT("cueAt"), TURN_DONE("turnDone"), FACE_FOUND("faceFound"), MATCH_ANSWERED("matchAnswered"),
            LINE_REQUESTED("lineRequested"), FIRST_SOUND("firstSound"),
            /** A call's cue (hey-miko plan U6): heard (its at), answered, a person found in front, the conversation open. */
            CALL_HEARD("callHeard"), CALL_ANSWERED("callAnswered"), CALL_FACING("callFacing"),
            CALL_ARRIVED("callArrived");

            final String key;

            Stage(String key) {
                this.key = key;
            }
        }

        void count(Counter counter);

        void stamp(Stage stage, long atMs);

        Gauges NONE = new Gauges() {
            public void count(Counter counter) {
            }

            public void stamp(Stage stage, long atMs) {
            }
        };
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
    private final Ears ears;
    private Gauges gauges = Gauges.NONE;
    private final HazardClassifier classifier;
    /** Heading, measured turns and the leg log (explore nav plan U2). */
    private final Heading compass;
    /** Bends and shortens roaming legs from the looks' openness (explore nav plan U4). */
    private final RoamSteer steer;
    /** Where he has been this session (explore nav plan U10), forgotten at shutdown. */
    private final Coverage coverage;
    private int coverageNoted = -1;
    /** What he has looked at in the last 30 minutes (visual place memory). */
    private final PlaceMemory places;
    /** The newest look scored against it, and its novelty (NaN: plain, or no print). */
    private Look placeLook;
    private double placeLookNovelty = Double.NaN;
    /** The print the last "seen before" note was about: each memory is noted once in a row. */
    private Object placeNoted;
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
    /** The last stop's look never came: the next miss in a row turns curiosity off (curiosityBackoffMs). */
    private boolean stopLookMissed;
    /** A stop retried after its first missed look: when the retry is due, instead of a full gap (or -1). */
    private long curiosityRetryAt = -1;
    /** The line queued at SPEAK is a stop's remark (counted when it is handed to the speech service). */
    private boolean remarkQueued;
    /** When each remark of the last ten minutes was spoken, for the running rate in the log. */
    private final ArrayDeque<Long> remarkTimes = new ArrayDeque<Long>();
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
    /** The things in seen, most recent first, capped at reactedLabelsMax: labels only, never a person. */
    private final ArrayDeque<String> reacted = new ArrayDeque<String>();
    /** His remarks about things this session, most recent first, capped at saidLinesMax (for the look request). */
    private final ArrayDeque<String> saidLines = new ArrayDeque<String>();
    /** Every remark about a thing this session, normalised, so none is said twice (capped at SAID_EVER_MAX). */
    private final Set<String> saidEver = new java.util.LinkedHashSet<String>();
    private static final int SAID_EVER_MAX = 500;
    private long peopleIgnoredUntil;
    /**
     * This stop's person came from a roaming pick (approachPerson), not a call, a cue or
     * Claude's pick at a stop: a match that finds no face in its box drops it (a phantom).
     */
    private boolean roamingPick;
    /**
     * This stop's person came from a voice cue that is not a call (a lean-in, or a strong cue
     * without the wake word or his name), found by the person-box shape test (robot
     * 2026-10-01): like a roaming pick, its meeting needs a usable face.
     */
    private boolean cuePick;
    /** Roaming person picks are ignored until then after a phantom (phantomPersonCooldownMs). */
    private long phantomsIgnoredUntil;
    /** Robot 2026-10-01: when the meeting's listen (LISTEN, or the confirm ladder's) started. */
    private long meetListenAt;
    /** That listen's deadline was moved to the answer hold once already. */
    private boolean answerHeld;

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
    /**
     * The match answer the degraded ladder fetched lines for (face plan U6, KTD7):
     * a match carries no lines, so they come from port.lines() where they are
     * spoken. Null when the match failed (or there was no look): the lines alone.
     */
    private CuriosityPort.MatchAnswer matched;
    /** The first roam waits for the face migration until this time (KTD11); released once it goes. */
    private long faceHoldUntil;
    private boolean faceHoldReleased;
    /** What the port was last told about face work (null: nothing yet): the two-thread rule (KTD11). */
    private Boolean faceWorkSent;
    /** REMEMBER asked for the hello (nothing stored: no face, or no name, R19) rather than the store. */
    private boolean helloOnly;
    /** A clock field's "never": far enough below any tick that now - NEVER cannot overflow. */
    static final long NEVER = Long.MIN_VALUE / 4;
    /** A lost ears session is reopened on the drive lease's backoff (ExploreDrive.LEASE_RETRY_*): 2 s doubling to 30 s. */
    private static final long EARS_REOPEN_BASE_MS = 2000;
    private static final long EARS_REOPEN_MAX_MS = 30000;
    /** When the camera last closed, for its reopen gap (tuning.reopenGapMs). */
    private long cameraClosedAt = NEVER;

    // ---- the camera while roaming (explore nav plan U4) ----
    /** The last look seen (by identity: a real frame arrived), and when the next must come by. */
    private Look lastLook;
    private long roamLookDeadline;
    /** The last look checked during the current leg for a blocked way ahead. */
    private Look legLook;
    /** When the last turn stopped: a look captured before that faced another way. */
    private long headingSettledAt = NEVER;
    /** The next leg's forward ticks as the steer chose them (0: its bend is the whole move; -1: none). */
    private int plannedTicks = -1;
    /** Look-then-go (KTD7): in PAUSE, waiting for the leg decision's look, captured at or after legLookAfter. */
    private boolean lookForLeg;
    /** Continuous mode, in PAUSE: a leg decision waiting for a look to steer by, until then (steerWaitMs). */
    private long steerWaitUntil = NO_WAIT;
    private static final long NO_WAIT = Long.MIN_VALUE;
    /** The running remark count in the log covers this long. */
    private static final long REMARK_WINDOW_MS = 600000;
    /** Frames awaiting the floor-teach distance (teachQueue); the oldest is dropped first. */
    private static final int TEACH_QUEUE_MAX = 8;
    /** A timed orient shorter than this is already facing it. */
    private static final long MIN_ORIENT_MS = 50;
    /** A CPL hiccup's retry (cplRetryPauseMs): CPL again before this is a hazard. */
    private long cplRetryUntil = NEVER;
    /** The last mid-leg re-aim, and the last look it weighed (one decision per look). */
    private long reaimedAt = NEVER;
    private Look reaimLook;
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
    private long restEndedAt = NEVER;
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
    /** How far the measured turn that wedged him had turned when it was ruled blocked. */
    private double wedgeTurnTurned = Double.NaN;

    // ---- fully jammed (robot 2026-10-01: "he's constantly getting stuck under this chair") ----
    /** The fixed help line, said on the launcher's speech service (no Claude call). */
    static final String HELP_LINE = "I'm stuck under here. Could someone pull me out?";
    /** This escape: a back-up went nowhere (under stallMinCounts). */
    private boolean jamBackStalled;
    /** This escape: the ways a measured turn turned under jamTurnDeg. */
    private final EnumSet<Direction> jamBlockedWays = EnumSet.noneOf(Direction.class);
    /** Fully jammed: no escapes, only the long rests and one short back-up after each. */
    private boolean jammed;
    /** How many times he has been fully jammed since start, for the log. */
    private int jams;
    /** The one short back-up after a jammed rest is under way, until jamProbeUntil. */
    private boolean jamProbing;
    private long jamProbeUntil;
    /** The probe's ticks are done and its motors stopped at this time; it is judged on a later reading. */
    private long jamJudgeAfter;
    /** Wheel counts moved (average of both wheels) since the rest or the probe began, and from where. */
    private long jamMoved;
    private SensorReading jamFrom;
    /** The heading when the rest began (NaN: unusable), to see him turned from outside. */
    private double jamHeading = Double.NaN;
    /** Why the rest ends early (moved from outside, shoved), or null. */
    private String jamPoke;
    /** The help line waits for the camera to close; when it was last said. */
    private boolean jamHelpPending;
    private long jamHelpAt = NEVER;

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
    private long metNextCheckAt = NEVER;
    private long metClearedUntil = NEVER;

    /** The newest reading, where a drive's counts start from. */
    private SensorReading lastReading;
    // ---- cues, the turn to the voice and the look that decides (meeting plan U7) ----
    /** When the last motor command went out (TimedMotor): the shove cue's blanking window. */
    private long lastMotorCommandAt = NEVER;
    /**
     * Whether the brain wants the launcher's ears session open (always, until shutdown; hey-miko plan KTD5).
     * The session itself can die underneath (ears.listening() false while this is true): that
     * is a lost session, reopened on the drive lease's backoff below.
     */
    private boolean earsOpen;
    /** Reopens fired since the session was lost; 0 while it holds. */
    private int earsReopenAttempt;
    /** When the lost session's next reopen (or the check that the last one held) is due; NEVER while it holds. */
    private long earsReopenDueAt = NEVER;
    /** The cue waiting for a state that can take it (KTD3's replacement rule), or null. */
    private Ears.Cue cueHeld;
    /**
     * The call (hey-miko plan KTD1): a strong WAKE_WORD or NAME cue, in its own slot apart
     * from cueHeld. It never expires or degrades, repeated calls merge into it (the newest
     * angle wins), and it is kept until the conversation it leads to opens.
     */
    private Ears.Cue call;
    /** The call is being acted on (its search, the approach it carries on, its meeting). */
    private boolean callTaken;
    /**
     * A second caller's call (KTD4), heard during the taken call's own meeting or
     * conversation and not the partner's: it waits here, never merged into the taken
     * call, and becomes the slot's fresh, unanswered call when that one clears.
     */
    private Ears.Cue nextCall;
    /** The answer clip has played for this call: a call handed back is retaken without a second one. */
    private boolean callAnswered;
    /** The call's wait was counted (CUES_HELD once per call). */
    private boolean callWaitCounted;
    /** The utterance keys (Cue.at) of recent calls: an already-called delivery with one of them is absorbed (KTD4). */
    private final ArrayDeque<Long> callAts = new ArrayDeque<Long>();
    private static final int CALL_ATS_KEPT = 8;
    /** The call's search (hey-miko plan KTD6): 45 deg steps, eight looks for one circle with no angle. */
    private static final double CALL_STEP_DEG = 45;
    private static final int CALL_CIRCLE_LOOKS = 8;
    /** The bearing the call's search was planned round (searchRel's frame, right positive); NaN with no angle. */
    private double callBearing = Double.NaN;
    /**
     * The caller spoke during the call's search (owner 2026-09-30): the call's meeting
     * opened on their voice with nobody in view, so more of their voice in it is theirs.
     */
    private boolean callerHeard;
    /**
     * The caller spoke during the first turn of the call's search, or while he turned to or
     * looked at a caller seen in a look: the conversation opens when that turn or look ends.
     */
    private Ears.Cue callerInTurn;
    /** Turns this call toward a caller seen in a look (capped at callSeenRetargetsMax). */
    private int seenRetargets;
    /** This CUE_TURN or CUE_LOOK is the turn toward, or the confirming look at, a caller seen in a look. */
    private boolean seenLook;
    /** The planned look the search resumes after when nobody is where the caller was seen. */
    private int seenResumeLook;
    /** The capture time of the look that turned him to a seen caller: it and older looks cannot confirm them. */
    private long seenFrame = NEVER;
    /** The call's search turns that would not turn (robot 2026-10-01: the motor board latched). */
    private int callBlockedTurns;
    /** The frame time of the newest look when the call's stop began (it arrived before the stop). */
    private long callStopFrame = NEVER;
    /** The frame time of the last look checked during the call's stop, fresh or stale. */
    private long callLookChecked = NEVER;
    /** CUE_WHERE: when the listen after "Where'd you go?" ends. */
    private long whereUntil;
    /** The turn the wheels were last told to make (Heading.LEFT or RIGHT), 0 once stopped (KTD7's fallback). */
    private int turnSign;
    /** KTD7: his heading lately, to correct a call's angle for his own turning since it was heard. */
    private final Heading.History headingHistory;
    /** The cue being searched for in CUE_TURN and CUE_LOOK, or null. */
    private Ears.Cue searchCue;
    /** A strong cue from the other side already retargeted this search (once only, KTD3). */
    private boolean searchRetargeted;
    /** The look headings of this search, relative to where it began (negative left), and the one he is at. */
    private double[] searchPlan;
    private int searchLook;
    /** Degrees turned since the search began, relative and signed like searchPlan. */
    private double searchRel;
    /** The first turn, toward the voice: stepped and re-read from the angle's trend (KTD4). */
    private boolean searchFirstTurn;
    private double searchRemainingDeg;
    private boolean searchTurnLeft;
    private boolean searchTurnDoneStamped;
    /** The newest angle trend since the last step (Ears.trend), and whether one landed in this turn step. */
    private Ears.Trend latestTrend;
    private boolean trendSeenInStep;
    /** The last shove while stopped and the last collision stop while driving (KTD5): "sorry" within 2 s is strong. */
    private long lastShoveAt = NEVER;
    private long bumpAt = NEVER;
    /** A meeting entered without a turn on a call (KTD8; EYES_ONLY, no lease or camera, the charger): the lease and sensor guards let it finish. */
    private boolean wheellessMeeting;
    /** The conversation has nobody in view (a faceless match or a meeting without a look). */
    private boolean chatFaceless;
    /** When the conversation partner last spoke outside a listen's words (a wake word, a call, a cue). */
    private long partnerSpokeAt = NEVER;
    /** The port's stand-in box for a person he cannot see (the wheelless meeting): straight ahead. */
    private static final Detection UNSEEN_PERSON = new Detection("person", 1f, 0.35f, 0.2f, 0.65f, 0.8f);
    // ---- the conversation (meeting plan U8; KTD7, KTD8, KTD10, R16) ----
    /** The conversation running in the CHAT states, or null. */
    private ChatSession chat;
    // ---- confirming a close match and resolving names (face plan U7; KTD6, KTD9, KTD10, KTD12) ----
    private enum IdStep { NONE, ASKING, LISTENING, RESOLVING, ADDING, KEEPING }
    private IdStep idStep = IdStep.NONE;
    /** The close match being confirmed: a stranger answer with the conversation's fields (null from the ladder's NAME step). */
    private CuriosityPort.MatchAnswer confirming;
    /** The resolve decides a conversation start (true) or the degraded ladder's next line. */
    private boolean idChat;
    /** A yes to the question (its photo's outcome is YES, not JOINED). */
    private boolean idYes;
    /** The name given (waiting for its last name), the name being kept, and the id a photo is added to. */
    private String idFirst;
    private String idName;
    private String idJoinId;
    private long idDeadline;
    /** The conversation this meeting becomes: whether its face check still waits for an outcome, and a settled first name. */
    private boolean chatCheckOpen = true;
    private String chatSettled;
    /** The acknowledgement clip's window (KTD14): the local question waits for it to end. */
    private long ackUntil = NEVER;
    /** The side the voice that started this stop came from, or null: the resume leg turns away from it. */
    private Direction chatCueSide;
    private Direction chatSide;
    /** The lease was lost during the conversation (KTD7): no wheels and no look until it ends. */
    private boolean chatNoWheels;
    /** When the sensors went unavailable during the conversation, or MIN while they are fine. */
    private long chatStallSince = NEVER;
    /** The detector is parked (CHAT states), and whether the conversation wants one look. */
    private boolean parked;
    private boolean chatLookWanted;
    /** The first leg after a conversation goes this way, away from the person (R16), or null. */
    private Direction awayLeg;
    /** An unnamed conversation's side-and-time leave-alone (KTD10). */
    private Direction leaveAloneSide;
    private long leaveAloneUntil = NEVER;

    ExploreBrain(ExploreTuning tuning, Clock clock, Motor motor, Eyes eyes, Sound sound, Random random) {
        this(tuning, clock, motor, eyes, sound, NO_CAMERA, random);
    }

    ExploreBrain(ExploreTuning tuning, Clock clock, Motor motor, Eyes eyes, Sound sound, Camera camera,
                 Random random) {
        this(tuning, clock, motor, eyes, sound, camera, CuriosityPort.NONE, random);
    }

    ExploreBrain(ExploreTuning tuning, Clock clock, Motor motor, Eyes eyes, Sound sound, Camera camera,
                 CuriosityPort port, Random random) {
        this(tuning, clock, motor, eyes, sound, camera, port, Ears.NONE, random);
    }

    ExploreBrain(ExploreTuning tuning, Clock clock, Motor motor, Eyes eyes, Sound sound, Camera camera,
                 CuriosityPort port, Ears ears, Random random) {
        this.tuning = tuning;
        this.port = port;
        this.ears = ears;
        this.clock = clock;
        this.motor = new TimedMotor(motor);
        this.eyes = eyes;
        this.sound = sound;
        this.camera = camera;
        this.random = random;
        this.classifier = new HazardClassifier(tuning);
        this.compass = new Heading(tuning.gyro, tuning);
        this.headingHistory = new Heading.History(tuning.headingHistoryMs, tuning.headingSampleMs);
        this.steer = new RoamSteer(tuning);
        this.coverage = new Coverage(tuning);
        this.places = new PlaceMemory(tuning);
        this.planner = new EscapePlanner(tuning);
    }

    /** Where he has been this session (explore nav plan U10), for tests. */
    Coverage coverage() {
        return coverage;
    }

    /** What he has looked at lately (visual place memory), for tests. */
    PlaceMemory places() {
        return places;
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

    /** Where cue counters and stage stamps go (KTD14); none by default. */
    void setGauges(Gauges gauges) {
        this.gauges = gauges == null ? Gauges.NONE : gauges;
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
        faceHoldUntil = clock.nowMs() + tuning.faceHoldMs;
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
        countJamWheels(reading);
        lastReading = reading;
        compass.offer(reading, moving);
        headingHistory.offer(reading.timestampMs,
                compass.usable(reading.timestampMs) ? compass.degrees() : Double.NaN,
                moving ? turnSign : 0, 360.0 / Math.max(1, tuning.escapeSweepMaxMs));
        coverage.offer(reading, compass.degrees(), compass.usable(reading.timestampMs));
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
        coverage.clear();
        places.clear();
        placeLook = null;
        placeNoted = null;
        probing = false;
        state = State.STOPPED;
        syncCamera();
        syncMoving();
        syncEars(clock.nowMs());
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
        // The one place cues are consumed (KTD1, KTD3): every state, EYES_ONLY included.
        drainEars(now);
        // The call (hey-miko plan KTD1, KTD2): in every state, EYES_ONLY included, before
        // the state's own step, so a take and its answer clip land in this very step.
        callStep(now);
        if (state == State.EYES_ONLY) {
            if (leaseHeld && s != HazardClassifier.Status.UNAVAILABLE) {
                if (!faceHoldReleased) {
                    // The first roam waits for the start-up face migration, with the
                    // detector not yet loaded, for at most faceHoldMs (KTD11).
                    boolean migrated = port.migrated();
                    if (!migrated && now < faceHoldUntil) {
                        return;
                    }
                    faceHoldReleased = true;
                    note(migrated ? "faces ready: the first roam" : "face migration still running after "
                            + tuning.faceHoldMs + " ms: the first roam, and it goes on at stops");
                }
                note("sensors available and lease held");
                if (curiosityAt == Long.MAX_VALUE) {
                    scheduleCuriosity(now);
                }
                enterPause(now, pauseMs(), false);
            }
            return;
        }
        if (!leaseHeld && !wheellessMeeting) {
            if (!state.chats()) {
                enterEyesOnly("lease lost");
                return;
            }
            // KTD7's exception: the conversation goes on without wheels or looks.
            if (!chatNoWheels) {
                chatNoWheels = true;
                stopMotors();
                note("lease lost during the conversation: no wheels and no look until it ends");
            }
        }
        if (s == HazardClassifier.Status.UNAVAILABLE && !wheellessMeeting) {
            if (!state.chats()) {
                enterEyesOnly("sensors unavailable: " + classifier.reason());
                return;
            }
            if (chatStallSince == NEVER) {
                chatStallSince = now;
                note("sensors unavailable during the conversation: " + tuning.chatStallGraceMs + " ms grace");
            } else if (now - chatStallSince >= tuning.chatStallGraceMs && chat != null) {
                chat.endWithSignOff(now, "sensors unavailable for " + (now - chatStallSince) + " ms");
            }
        } else if (state.chats()) {
            chatStallSince = NEVER;
        }
        boolean hazard = s == HazardClassifier.Status.HAZARD;
        watchLooks(now);
        doorwayStep(now);
        metCheckStep(now);
        takeHeldCue(now);
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
                    if (!cplHiccup(now)) {
                        hazardInMotion(now);
                    }
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
                } else if (reaimInLeg(now)) {
                    break;
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
            case CUE_TURN:
            case CUE_LOOK:
                curiosityStep(now, fresh, hazard);
                break;
            case CUE_WHERE:
                whereStep(now);
                break;
            case CHAT_THINK:
            case CHAT_SPEAK:
            case CHAT_LISTEN:
            case CHAT_NOTES:
                chatStep(now);
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
            case CONFIRM:
            case LAST_NAME:
                identityStep(now);
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
                if (jammed) {
                    jamStep(now, fresh);
                    break;
                }
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
        jammed = false;
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
        } else if (awayLeg != null) {
            // The first leg after a conversation turns away from the person (R16).
            Direction d = awayLeg;
            awayLeg = null;
            note("first leg after the conversation: turning " + d + ", away from them");
            enterLook(now, d, false, timedMs(tuning.chatAwayDeg), tuning.chatAwayDeg);
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
            if (look == null && cameraOpen && now >= curiosityOffUntil && tuning.steerWaitMs > 0) {
                // No look since the last turn settled yet (they come every 1-2 s live):
                // wait for one, on every fresh reading, so the steer gets its say (KTD9).
                if (steerWaitUntil == NO_WAIT) {
                    steerWaitUntil = now + tuning.steerWaitMs;
                    note("waiting up to " + tuning.steerWaitMs + " ms for a look to steer by");
                    return;
                }
                if (now < steerWaitUntil) {
                    return;
                }
                note("no look to steer by in time: choosing without one");
            }
            steerWaitUntil = NO_WAIT;
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
        RoamSteer.Novelty novelty = novelty(now, look);
        if (novelty != null && coverage.tracking() && coverage.cells(now) != coverageNoted) {
            coverageNoted = coverage.cells(now);
            note("coverage: " + coverageNoted + " cells");
        }
        RoamSteer.Plan plan = look == null ? null : steer.plan(look.openness, door, novelty);
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
        } else if (random.nextDouble() < turnChance(novelty)) {
            Direction d;
            boolean forced = lastHazardSide != null;
            if (lastHazardSide != null) {
                d = lastHazardSide.opposite();
                lastHazardSide = null;
            } else {
                d = randomDirection();
            }
            d = unblocked(d);
            if (compass.usable(now)) {
                double deg = tuning.turnMinDeg + random.nextDouble() * (tuning.turnMaxDeg - tuning.turnMinDeg);
                if (novelty != null) {
                    // Toward the newest ground among the turns he may make (U10); the hazard's
                    // side and a blocked side still rule out the other way.
                    double bestNew = novelty.at(d == Direction.LEFT ? deg : -deg);
                    Direction bestDir = d;
                    double bestDeg = deg;
                    Direction other = d.opposite();
                    boolean otherOk = !forced && unblocked(other) == other;
                    for (Direction c : new Direction[]{d, other}) {
                        if (c != d && !otherOk) {
                            continue;
                        }
                        for (double a : new double[]{tuning.turnMinDeg, (tuning.turnMinDeg + tuning.turnMaxDeg) / 2,
                                tuning.turnMaxDeg}) {
                            double v = novelty.at(c == Direction.LEFT ? a : -a);
                            if (!Double.isNaN(v) && (Double.isNaN(bestNew) || v > bestNew + 0.05)) {
                                bestNew = v;
                                bestDir = c;
                                bestDeg = a;
                            }
                        }
                    }
                    d = bestDir;
                    deg = bestDeg;
                }
                enterLook(now, d, false, timedMs(deg), deg);
            } else {
                enterLook(now, d, false, between(tuning.turnMinMs, tuning.turnMaxMs), 0);
            }
        } else {
            startHop(now);
        }
    }

    /**
     * How new each bearing off his facing is: the lower of the grid's novelty
     * (explore nav plan U10; none with the heading not usable or no position kept
     * yet) and the place memory's (in view, look's; out of view, the newest look at
     * that heading). Null (as before U10) with coverageWeight 0, or when neither
     * knows anything.
     */
    private RoamSteer.Novelty novelty(final long now, Look look) {
        if (tuning.coverageWeight <= 0) {
            return null;
        }
        boolean usable = compass.usable(now);
        final double facing = compass.degrees();
        RoamSteer.Novelty grid = null;
        if (usable && coverage.tracking()) {
            // An anonymous class, not a lambda: the Android build's bootclasspath has no LambdaMetafactory.
            grid = new RoamSteer.Novelty() {
                @Override
                public double at(double bearing) {
                    return coverage.novelty(Heading.wrap(facing + bearing), now);
                }
            };
        }
        return places.steer(grid, facing, usable, placeNovelty(now, look), now);
    }

    /**
     * Scores a look against the place memory once, and keeps its print (visual place
     * memory): its heading at capture is the compass less what he has turned since
     * (NaN when not usable). Notes a match placeSeenSim or better, each memory once
     * in a row. The look's novelty, NaN for none (no look, no print, a plain view).
     */
    private double placeNovelty(long now, Look look) {
        if (look == null || look.place == null) {
            return Double.NaN;
        }
        if (look != placeLook) {
            double heading = Double.NaN;
            if (compass.usable(now)) {
                double turned = headingHistory.turnedSince(look.frameMs);
                heading = Double.isNaN(turned) ? Double.NaN : Heading.wrap(compass.degrees() - turned);
            }
            PlaceMemory.Match m = places.look(look.place, labels(look), heading, look.frameMs);
            placeLook = look;
            placeLookNovelty = m.novelty;
            if (m.seen() && m.print != placeNoted) {
                placeNoted = m.print;
                note(m.note());
            }
        }
        return placeLookNovelty;
    }

    /** The detector's labels in a look (its boxes are already above the detector's floor). */
    private static List<String> labels(Look look) {
        List<String> out = new ArrayList<String>();
        if (look.detections != null) {
            for (Detection d : look.detections) {
                if (!out.contains(d.label)) {
                    out.add(d.label);
                }
            }
        }
        return out;
    }

    /** turnChance, cut by coverageTurnScale while the way ahead is new ground (U10). */
    private double turnChance(RoamSteer.Novelty novelty) {
        double ahead = novelty == null ? Double.NaN : novelty.at(0);
        return !Double.isNaN(ahead) && ahead >= tuning.coverageNovelAhead
                ? tuning.turnChance * tuning.coverageTurnScale : tuning.turnChance;
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

    /**
     * A roaming leg's CPL=2 while our own floor sensor reads plain floor (owner-approved
     * 2026-09-25): live, the controller refuses forward on open carpet as the nose dips
     * at a start or stop. Stop in this same event, pause cplRetryPauseMs, and drive the
     * leg's remaining ticks once more (the start waits for a fresh, clear reading as
     * always); not counted toward hazards in a row. CPL again within cplRetryWindowMs,
     * or at the retry's start, or with our sensor at an edge or obstacle, is a hazard.
     */
    private boolean cplHiccup(long now) {
        HazardClassifier.Hazard h = classifier.hazard();
        if (tuning.cplRetryPauseMs < 0 || h == null || h.kind != HazardClassifier.Kind.CPL
                || !classifier.plainFloor() || now < cplRetryUntil) {
            return false;
        }
        int left = ticksRemaining(now);
        note("controller refused forward (CPL) on plain floor: a hiccup; " + left
                + " ticks to go, trying once more in " + tuning.cplRetryPauseMs + " ms");
        stopMotors();
        cplRetryUntil = now + tuning.cplRetryPauseMs + tuning.cplRetryWindowMs;
        plannedTicks = left;
        hopNext = true;
        enterPause(now, tuning.cplRetryPauseMs, true);
        return true;
    }

    /** The current leg's ticks still to drive (at least one). */
    private int ticksRemaining(long now) {
        return (int) Math.max(1, (phaseUntil - now + tuning.hopTickMs - 1) / tuning.hopTickMs);
    }

    /**
     * Mid-leg re-aim (owner-approved 2026-09-25, KTD9): the drive can't curve, so a
     * fresh look taken during this leg whose best open band lies reaimMinDeg or more
     * off centre stops the leg, turns (by the gyro) toward it by at most reaimMaxDeg,
     * and the leg goes on for its remaining ticks. Continuous mode only, at most once
     * per reaimGapMs, never toward a blocked side, never with the heading unusable.
     * The turn starts on a fresh clear reading, from LOOK, like any other.
     */
    private boolean reaimInLeg(long now) {
        if (tuning.reaimMinDeg <= 0 || tuning.navigation != ExploreTuning.Navigation.CONTINUOUS || !cameraOpen
                || !compass.usable(now) || now - reaimedAt < tuning.reaimGapMs) {
            return false;
        }
        Look look = camera.latest();
        if (look == null || look == reaimLook || look.frameMs <= hopStartedAt
                || look.frameMs < headingSettledAt + tuning.lookSettleMs) {
            return false;
        }
        reaimLook = look;
        int left = ticksRemaining(now);
        if (left < tuning.reaimMinTicks) {
            return false;
        }
        double deg = steer.reaimDeg(look.openness, doorwayBearing(now), novelty(now, look));
        if (Math.abs(deg) < tuning.reaimMinDeg) {
            return false;
        }
        Direction d = deg > 0 ? Direction.LEFT : Direction.RIGHT;
        if (unblocked(d) != d) {
            note("re-aim " + d + " skipped: that side is blocked");
            return false;
        }
        double bend = Math.min(Math.abs(deg), tuning.reaimMaxDeg);
        note("re-aim: open space " + Math.round(Math.abs(deg)) + " deg " + d + ", turning " + Math.round(bend)
                + " deg, then " + left + " ticks more");
        reaimedAt = now;
        stopMotors();
        plannedTicks = left;
        enterLook(now, d, false, timedMs(bend), bend);
        return true;
    }

    /** A hazard while moving: stop in this same event, then startle (R11). */
    private void hazardInMotion(long now) {
        HazardClassifier.Hazard h = classifier.hazard();
        note("hazard while " + state + ": " + h);
        hazardInMotion(now, h);
    }

    /** As above, for a hazard the classifier did not see (h null: side unknown). */
    private void hazardInMotion(long now, HazardClassifier.Hazard h) {
        if (drivingForward() && (h == null || h.kind == HazardClassifier.Kind.OBSTACLE)) {
            stampBump(now);
        }
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
        if (callsOwn() && (state.cueSearch() || state.approachesPerson())) {
            // KTD1: the escape comes first; the call is retaken from where it leaves him.
            handBackCall("a hazard");
            clearStop();
            return;
        }
        if (state.cueSearch() && searchCue != null) {
            // R9: the escape comes first; the voice is looked for again from where it leaves him.
            note("hazard during the turn to a voice: the cue waits for the escape");
            holdCue(clock.nowMs(), searchCue);
            clearStop();
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
        speakRemark(now, p.line);
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
        if (!measured || !compass.usable(now)) {
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
            note("turn start: heading " + Math.round(compass.degrees()) + " deg, asking " + Math.round(deg) + " deg " + heading);
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
        if (curiosityRetryAt >= 0) {
            curiosityAt = curiosityRetryAt;
            curiosityRetryAt = -1;
            return;
        }
        curiosityAt = now + between(tuning.curiosityMinMs, tuning.curiosityMaxMs);
    }

    private void enterScan(long now) {
        note("curiosity stop: scanning");
        // The next stop is scheduled now, so one cut short by a hazard is not retried at once.
        scheduleCuriosity(now);
        state = State.SCAN;
        scanLooksLeft = tuning.scanLooks;
        scanDir = unblocked(scanSide(now, randomDirection()));
        target = null;
        roamingPick = false;
        cuePick = false;
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

    /**
     * The way to scan: toward the side whose recent looks were less familiar (visual
     * place memory), by at least coverageMinGain over the headings the scan would
     * face; else the given (random) way.
     */
    private Direction scanSide(long now, Direction random) {
        if (tuning.coverageWeight <= 0 || !compass.usable(now)) {
            return random;
        }
        double left = 0;
        double right = 0;
        int n = 0;
        for (int i = 1; i < tuning.scanLooks; i++) {
            double l = places.noveltyAt(compass.degrees() + i * tuning.scanTurnDeg, now);
            double r = places.noveltyAt(compass.degrees() - i * tuning.scanTurnDeg, now);
            if (Double.isNaN(l) || Double.isNaN(r)) {
                continue;
            }
            left += l;
            right += r;
            n++;
        }
        if (n == 0 || Math.abs(left - right) / n < tuning.coverageMinGain) {
            return random;
        }
        return left > right ? Direction.LEFT : Direction.RIGHT;
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

    /**
     * A stop's look did not come in time (the remark rate, owner 2026-10-01). The
     * stop ends (saying its pick, if it has one). The first miss is retried
     * curiosityRetryMs later; a second in a row with no look at all turns curiosity
     * off for curiosityBackoffMs, and one where looks came, just none new enough (a
     * slow detector, not a broken camera), only ends the stop.
     */
    private void stopLookMissed(long now, boolean noLookAtAll) {
        boolean again = stopLookMissed;
        stopLookMissed = !again;
        if (!noLookAtAll) {
            note("no new look in time; ending this curiosity stop");
        } else if (again) {
            note("camera gave no look in time again; curiosity off for " + tuning.curiosityBackoffMs + " ms");
            curiosityOffUntil = now + tuning.curiosityBackoffMs;
        } else {
            note("camera gave no look in time");
        }
        if (!again && pick == null) {
            // Taken by scheduleCuriosity as the stop ends, in place of a full gap of wandering.
            note("trying the curiosity stop again in " + tuning.curiosityRetryMs + " ms");
            curiosityRetryAt = now + tuning.curiosityRetryMs;
        }
        giveUp(now);
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
                } else if (fresh && state == State.CUE_LOOK && callsOwn() && look != null
                        && look.frameMs != callLookChecked && staleCallLook(now, look)) {
                    break;
                } else if (now >= lookDeadline) {
                    if (state == State.CUE_LOOK) {
                        cueLookOver(now, look == null);
                        break;
                    }
                    stopLookMissed(now, look == null);
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
                } else if (state == State.CUE_TURN && fresh && callsOwn() && seenInTurn(now)) {
                    // Turning to face a caller seen in a look taken on the way (turnToSeenCaller).
                    break;
                } else if (state == State.CUE_TURN && trendSaysStop(now)) {
                    double turned = turnedSoFar(now);
                    stopMotors();
                    searchRel += searchTurnLeft ? -turned : turned;
                    enterCueLook(now);
                } else if (turnDone(now)) {
                    double turned = turnedSoFar(now);
                    stopMotors();
                    if (state == State.APPROACH) {
                        step = Step.READY_LEG;
                    } else if (state == State.ORIENT) {
                        oriented(now);
                    } else if (state == State.CUE_TURN) {
                        cueTurnStepDone(now, turned);
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
        if (state == State.CUE_LOOK) {
            cueLook(now, look);
            return;
        }
        stopLookMissed = false;
        // Someone met in the last 10 minutes: the detector's fallback never approaches a person (KTD8).
        // Only SCAN asks: a call's FACE and APPROACH never pass the leave-alone checks (hey-miko KTD8).
        boolean ignorePeople = state == State.SCAN
                && (now < peopleIgnoredUntil || anyoneMet(now) || leftAlone(now, look));
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
        if (callsOwn() && CuriosityPort.Kind.of(target.label) == CuriosityPort.Kind.PERSON
                && (polite(target) || Sighting.fillsFrame(target, tuning))) {
            callArrive(now, look);
            return;
        }
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
        turnWheels(heading);
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
        if (callsOwn() && target != null && CuriosityPort.Kind.of(target.label) == CuriosityPort.Kind.PERSON) {
            callArrive(now, null);
            return;
        }
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
                    markSeen(target.label);
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
        closeMeeting();
        cancelAsk();
        // A check the stop was waiting on runs on; its answer then only clears a roaming person.
        gatedPick = null;
        remarkOnly = false;
        meetingHeld = false;
        target = null;
        meetExpect = null;
        pick = null;
        stranger = null;
        helloOnly = false;
        pendingLine = null;
        remarkQueued = false;
        afterOrient = null;
        scanned.clear();
        scanHeadings.clear();
        askedFrames.clear();
        cues.clear();
        searchCue = null;
        searchPlan = null;
        searchFirstTurn = false;
        callerInTurn = null;
        latestTrend = null;
        wheellessMeeting = false;
        roamingPick = false;
        cuePick = false;
        chat = null;
        chatCueSide = null;
        chatNoWheels = false;
        chatStallSince = NEVER;
        chatLookWanted = false;
        syncPark();
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
        if (state.curious() || state.cueSearch() || state.chats()) {
            return true;
        }
        if ((state == State.MEET || state.confirms()) && chatLikely()) {
            // The meeting the conversation grows out of (KTD7), and its question (U7): open, with the detector parked.
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
        syncPark();
        boolean want = state != State.STOPPED && cameraWanted(now);
        if (want != cameraOpen) {
            cameraOpen = want;
            if (want) {
                // The face models stop before the detector can start (the two-thread rule).
                faceWork(false);
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
        // Face work (the migration) runs only while the detector is closed and quiet, or parked (KTD11).
        faceWork(state != State.STOPPED && (cameraOpen ? parked : camera.quiet()));
    }

    /** Tells the port whether the face models may run, on every change (KTD11). */
    private void faceWork(boolean allowed) {
        if (faceWorkSent == null || faceWorkSent != allowed) {
            faceWorkSent = allowed;
            port.faceWork(allowed);
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
            placeNovelty(now, look);
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
        port.ask(new CuriosityPort.LookRequest(new ArrayList<CuriosityPort.Frame>(askedFrames), recent(now), cooling,
                new ArrayList<String>(reacted), new ArrayList<String>(saidLines)), tuning.askTimeoutMs);
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

    /** A line is needed, except for a familiar thing: that one is as good as nothing without a fresh line (onPick). */
    private boolean validPick(CuriosityPort.Answer a) {
        return a.frame >= 0 && a.frame < askedFrames.size() && a.box != null && a.kind != null
                && (usable(a.line) || (!a.kind.isLiving() && seenLoosely(a.box.label)));
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
        if (!a.kind.isLiving()) {
            // Robot 2026-10-01: in a familiar office nearly every pick was something he had reacted
            // to, and those stops ended silently. A fresh line about it is said; no line, or one he
            // has said before, is as good as nothing.
            boolean fresh = usable(a.line) && !saidBefore(a.line);
            if (seenLoosely(a.box.label)) {
                if (!fresh) {
                    nothing(now, "reacted to that already: as good as nothing, carrying on");
                    return;
                }
                note("reacted to that already, but with a fresh line: saying it");
            } else if (!fresh) {
                nothing(now, "a line he has said already: as good as nothing, carrying on");
                return;
            }
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

    /** A thing he reacted to: into seen, and to the front of the request's reacted list. */
    private void markSeen(String label) {
        seen.add(label);
        reacted.remove(label);
        reacted.addFirst(label);
        while (reacted.size() > tuning.reactedLabelsMax) {
            reacted.pollLast();
        }
    }

    /** A remark about a thing: to the front of the request's said list, and never to be said again. */
    private void rememberSaid(String line) {
        saidLines.remove(line);
        saidLines.addFirst(line);
        while (saidLines.size() > tuning.saidLinesMax) {
            saidLines.pollLast();
        }
        saidEver.add(normalisedLine(line));
        if (saidEver.size() > SAID_EVER_MAX) {
            java.util.Iterator<String> it = saidEver.iterator();
            it.next();
            it.remove();
        }
    }

    private boolean saidBefore(String line) {
        return saidEver.contains(normalisedLine(line));
    }

    /** Lower case, letters and digits only, single spaces: "What a plant!" and "what a plant" are one line. */
    private static String normalisedLine(String line) {
        return line.toLowerCase(java.util.Locale.US).replaceAll("[^a-z0-9]+", " ").trim();
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
        speakRemark(now, pick.line);
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
        matched = null;
        helloOnly = false;
        meetLines = false;
        syncPark();
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
        } else if (!meetLines) {
            gauges.stamp(Gauges.Stage.MATCH_ANSWERED, now);
        }
        if (!meetLines) {
            matchAnswered(now, a);
        } else if (matched == null) {
            linesAlone(now, a);
        } else {
            linesForMatch(now, matched, a);
        }
    }

    /**
     * The match answered (face plan U6): the conversation when it is possible,
     * which needs no meeting lines (KTD7); else the degraded ladder, which fetches
     * the lines it speaks with port.lines().
     */
    private void matchAnswered(long now, CuriosityPort.MatchAnswer a) {
        if ((roamingPick || cuePick) && !callsOwn() && !usableFace(a)) {
            phantomPerson(now, a);
            return;
        }
        if (confirmable(a)) {
            enterConfirm(now, a);
            return;
        }
        startAs(now, a);
    }

    /**
     * The face check gave a usable face (owner 2026-10-01): a match, or a new face that passed
     * the quality gate. Not one: no face in the person box, a crop rejected as too small, dark
     * or blurry, one that could not be straightened, the face models not ready (NOT_READY), the
     * face settings or people store unavailable, or a failed or timed-out check.
     */
    private static boolean usableFace(CuriosityPort.MatchAnswer a) {
        return a.status == CuriosityPort.MatchAnswer.Status.KNOWN
                || a.status == CuriosityPort.MatchAnswer.Status.NEW && !a.faceless;
    }

    /**
     * A roaming person pick with no usable face (robot 2026-09-30: motion blur scored person
     * about 0.5 while roaming; 2026-10-01: a chair's edge and the shadow under a desk scored
     * 0.53-0.54, and a box that stays is no proof, since furniture stays too). Owner: "If he's
     * not positive that it's a person, why is he acting like it is a person?" The meeting is
     * dropped with nothing said, roaming person picks are ignored for phantomPersonCooldownMs,
     * and he roams on. A call's meeting is never dropped: someone asked for him.
     */
    private void phantomPerson(long now, CuriosityPort.MatchAnswer a) {
        phantomsIgnoredUntil = now + tuning.phantomPersonCooldownMs;
        String why = a.status == CuriosityPort.MatchAnswer.Status.FAILED ? "the face check failed"
                : a.band == FaceMatcher.Band.NOT_READY ? "the face models are not ready" : "no face, or one rejected";
        note("no usable face in the " + (cuePick ? "cue's person box" : "roaming person pick's box") + " (" + why
                + "): not meeting them; roaming on, roaming people ignored for " + tuning.phantomPersonCooldownMs
                + " ms");
        endCuriosity(now);
    }

    /**
     * Start the meeting as the answer says (KTD6's "Start"): the conversation when
     * it is possible, else the degraded ladder, which fetches its lines.
     */
    private void startAs(long now, CuriosityPort.MatchAnswer a) {
        idStep = IdStep.NONE;
        boolean answered = a.status == CuriosityPort.MatchAnswer.Status.KNOWN
                || a.status == CuriosityPort.MatchAnswer.Status.NEW;
        if (answered && chatPossible(a)) {
            // The conversation path (U8, KTD8): known and unknown alike go to CHAT_THINK,
            // where the opener asks a stranger's name; the ladder below is the degraded path.
            enterChat(now, a);
            return;
        }
        if (a.status == CuriosityPort.MatchAnswer.Status.KNOWN && (usable(a.namedLine) || usable(a.unnamedLine))) {
            // An answer that already carries its lines needs no request.
            greet(now, a, a);
            return;
        }
        if (a.status == CuriosityPort.MatchAnswer.Status.NEW && usable(a.askLine)) {
            askName(now, a);
            return;
        }
        matched = answered ? a : null;
        note(answered ? "matched on the robot; asking for the lines to say"
                : "the person request failed; asking for the lines alone");
        meetLines = true;
        // From CONFIRM (U7) the lines request is waited on in MEET, as after a match.
        state = State.MEET;
        meetDeadline = now + tuning.meetTimeoutMs;
        port.lines(tuning.meetTimeoutMs);
    }

    /** The lines with no match behind them: a failed match, or a meeting without a look. */
    private void linesAlone(long now, CuriosityPort.MatchAnswer a) {
        if (chatPossible(a) && a.status == CuriosityPort.MatchAnswer.Status.NEW) {
            enterChat(now, a);
        } else if (a.status == CuriosityPort.MatchAnswer.Status.NEW && usable(a.askLine)) {
            // No look means no face of this person: an earlier meeting's must never be stored under the name.
            askName(now, wheellessMeeting ? CuriosityPort.MatchAnswer.faceless(a.askLine, a.noReplyLine) : a);
        } else {
            note("no lines for the person either; just the name clip");
            nameClip(now);
        }
    }

    /** The degraded ladder's lines for a match (KTD7): the greeting with the name filled in, or the ask. */
    private void linesForMatch(long now, CuriosityPort.MatchAnswer m, CuriosityPort.MatchAnswer lines) {
        matched = null;
        if (m.status == CuriosityPort.MatchAnswer.Status.KNOWN) {
            greet(now, m, lines.status == CuriosityPort.MatchAnswer.Status.FAILED ? null : lines);
        } else if (lines.status == CuriosityPort.MatchAnswer.Status.NEW && usable(lines.askLine)) {
            // The match decides what may be stored (faceless: nothing); the lines are what he says.
            askName(now, m.faceless ? CuriosityPort.MatchAnswer.faceless(lines.askLine, lines.noReplyLine)
                    : CuriosityPort.MatchAnswer.stranger(lines.askLine, lines.noReplyLine));
        } else {
            note("no lines for the person; just the name clip");
            nameClip(now);
        }
    }

    /**
     * Known (R10): the named line with the stored name filled in, or the unnamed
     * line; with no line at all, a named person still hears the local greeting
     * through the on-device voice (KTD7).
     */
    private void greet(long now, CuriosityPort.MatchAnswer known, CuriosityPort.MatchAnswer lines) {
        String name = known.name == null || known.name.trim().isEmpty() ? null : known.name.trim();
        String line = null;
        if (name != null && lines != null && usable(lines.namedLine)) {
            line = ClaudeReplies.fill(lines.namedLine, name);
            note("someone we've met, with a name");
        } else if (lines != null && usable(lines.unnamedLine)) {
            line = lines.unnamedLine;
            note("someone we've met, without a name");
        } else if (name != null) {
            line = ClaudeReplies.fill(ChatSession.LOCAL_GREETING, name);
            note("someone we've met, with a name, and no line: the local greeting");
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
        meetListenAt = now;
        answerHeld = false;
        note("listening for a reply");
        port.listen(tuning.listenMs);
    }

    /**
     * A meeting listen's deadline passed with nothing heard (robot 2026-10-01): true, once
     * per listen, when the answer has started (the launcher's "answering"), so the caller
     * moves the deadline to tuning.answerHoldMs from the listen's start; the words come at
     * the answer's end. False for a silent listen and under an older launcher: as before.
     */
    private boolean holdForAnswer() {
        if (answerHeld || !port.answering()) {
            return false;
        }
        answerHeld = true;
        note("an answer has started: the listen holds for it up to " + tuning.answerHoldMs + " ms from its start");
        return true;
    }

    /** LISTEN: nothing heard stores nothing (R12); words go on to the name. */
    private void listenStep(long now) {
        CuriosityPort.Heard h = port.heard();
        if (h == null) {
            if (now >= meetDeadline && holdForAnswer()) {
                meetDeadline = meetListenAt + tuning.answerHoldMs + tuning.listenMarginMs;
                return;
            }
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
     * NAME: a reply was heard. With a name the face is kept under it; without one,
     * or without a face, nothing is stored and the line he says next makes no
     * promise to remember them (R19: nobody is saved until he has a name).
     */
    private void nameStep(long now) {
        if (idStep != IdStep.NONE) {
            identityStep(now);
            return;
        }
        CuriosityPort.Named n = port.foundName();
        if (n == null && now < meetDeadline) {
            return;
        }
        String name = n != null && n.status == CuriosityPort.Named.Status.NAME ? n.name : null;
        state = State.REMEMBER;
        meetDeadline = now + tuning.meetTimeoutMs;
        helloOnly = true;
        if (faceless()) {
            note("no face to remember them by; just saying hello");
            port.welcome(name, tuning.meetTimeoutMs);
            return;
        }
        if (name == null) {
            note("no clear name; nothing is kept, just saying hello");
            port.welcome(null, tuning.meetTimeoutMs);
            return;
        }
        helloOnly = false;
        // Every spoken name goes through the resolver (KTD6, KTD10): join, the last name, or someone new.
        note("got a name; checking it against the people stored");
        confirming = null;
        idChat = false;
        state = State.NAME;
        resolve(now, name);
    }

    private boolean faceless() {
        return stranger != null && stranger.faceless;
    }

    private void rememberStep(long now) {
        CuriosityPort.Answer a = helloOnly ? port.welcomed() : port.remembered();
        if (a == null && now < meetDeadline) {
            return;
        }
        if (a != null && a.status == CuriosityPort.Answer.Status.PICK && usable(a.line)) {
            speak(now, a.line);
        } else if (helloOnly && stranger != null && usable(stranger.noReplyLine)) {
            note("no hello line; saying the friendly line instead");
            speak(now, stranger.noReplyLine);
        } else {
            note("no remember line; carrying on");
            finishPick(now);
        }
    }

    // ---- CONFIRM and LAST_NAME (face plan U7; KTD6, KTD9, KTD10, KTD12; R5-R7, R20, R21) ----

    /** A close match with a stored name and a face to store is confirmed aloud first (R2). */
    private boolean confirmable(CuriosityPort.MatchAnswer a) {
        return a.status == CuriosityPort.MatchAnswer.Status.NEW && !a.faceless && !wheellessMeeting
                && a.band == FaceMatcher.Band.CLOSE && a.candidateId != null && usable(a.confirmName);
    }

    /** CONFIRM: "Is that you, {name}?" through the on-device voice, then one listen (KTD6). */
    private void enterConfirm(long now, CuriosityPort.MatchAnswer a) {
        confirming = a;
        idChat = chatPossible(a);
        idYes = false;
        note("a close match: asking whether it is them");
        sayLocal(now, ClaudeReplies.fill(ChatSession.CONFIRM_QUESTION, a.confirmName.trim()), State.CONFIRM);
    }

    /**
     * A fixed local line (KTD6): with the camera open and the detector parked (the
     * conversation path) it is said at once, like the conversation's; otherwise the
     * camera closes first, as for any line. It waits for the acknowledgement clip.
     */
    private void sayLocal(long now, String line, State s) {
        queueLine(now, line, s);
        idStep = IdStep.ASKING;
    }

    private void identityStep(long now) {
        switch (idStep) {
            case ASKING:
                askingStep(now);
                break;
            case LISTENING:
                answerStep(now);
                break;
            case RESOLVING:
                resolveStep(now);
                break;
            case ADDING:
                photoStep(now);
                break;
            case KEEPING:
                keepStep(now);
                break;
            default:
                break;
        }
    }

    private void askingStep(long now) {
        if (pendingLine != null) {
            if (now < ackUntil) {
                return;
            }
            boolean ready = cameraOpen ? parked : camera.quiet();
            if (!ready && now < quietUntil) {
                return;
            }
            handOffLine(now);
            return;
        }
        if (port.sayFinished() || now >= sayUntil) {
            idStep = IdStep.LISTENING;
            idDeadline = now + tuning.listenMs + tuning.listenMarginMs;
            meetListenAt = now;
            answerHeld = false;
            show(EyeState.LISTENING, null);
            port.listen(tuning.listenMs);
        }
    }

    /** The one listen: silence counts as no (R21); words go to AnswerParser (KTD9). */
    private void answerStep(long now) {
        CuriosityPort.Heard h = port.heard();
        if (h == null && now >= idDeadline && holdForAnswer()) {
            idDeadline = meetListenAt + tuning.answerHoldMs + tuning.listenMarginMs;
        }
        if (h == null && now < idDeadline) {
            return;
        }
        String text = h != null && h.status == CuriosityPort.Heard.Status.WORDS && h.text != null
                && !h.text.trim().isEmpty() ? h.text : null;
        if (state == State.LAST_NAME) {
            lastNameAnswer(now, text);
        } else {
            confirmAnswer(now, text);
        }
    }

    private void confirmAnswer(long now, String text) {
        if (text == null) {
            note("no answer to the question: meeting them as someone new");
            outcome(CuriosityPort.Outcome.NO_REPLY, null);
            startAs(now, confirming);
            return;
        }
        AnswerParser.Reply r = AnswerParser.parse(text, confirming.confirmName, port);
        // The kind only: never the words or a name.
        note("the answer to the question: " + r.kind);
        switch (r.kind) {
            case YES:
                idYes = true;
                addPhotoTo(now, confirming.candidateId);
                break;
            case NO_WITH_NAME:
                resolve(now, r.name);
                break;
            default:
                // No, or unclear, which counts as no (R21).
                outcome(CuriosityPort.Outcome.NO, null);
                startAs(now, confirming);
                break;
        }
    }

    private void lastNameAnswer(long now, String text) {
        String last = text == null ? null : AnswerParser.lastName(text, idFirst, port);
        if (last == null) {
            note(text == null ? "no last name came: nobody is stored" : "no last name in the reply: nobody is stored");
            settleUnnamed(now);
            return;
        }
        idStep = IdStep.RESOLVING;
        idDeadline = now + tuning.meetTimeoutMs;
        show(EyeState.THINKING, null);
        port.resolveLastName(last, tuning.meetTimeoutMs);
    }

    /** Every spoken name goes to one resolver on the port (KTD6, KTD10). */
    private void resolve(long now, String name) {
        idFirst = name;
        idStep = IdStep.RESOLVING;
        idDeadline = now + tuning.meetTimeoutMs;
        show(EyeState.THINKING, null);
        port.resolveName(name, tuning.meetTimeoutMs);
    }

    private void resolveStep(long now) {
        CuriosityPort.Resolved r = port.resolved();
        if (r == null) {
            if (now < idDeadline) {
                return;
            }
            port.cancelResolve();
            note("the store did not answer about the name in time");
            r = CuriosityPort.Resolved.FAILED;
        }
        switch (r.status) {
            case JOIN:
                note("the name belongs to someone stored whose face is close enough: adding the photo to them");
                addPhotoTo(now, r.personId);
                return;
            case NEW:
                note("nobody stored has that name: storing them");
                storeAs(now, r.name);
                return;
            case ASK_LAST_NAME:
                if (state != State.LAST_NAME) {
                    note("the name matches someone stored but the face is weak for them: asking the last name");
                    sayLocal(now, ChatSession.LAST_NAME_QUESTION, State.LAST_NAME);
                    return;
                }
                break;
            default:
                break;
        }
        note("the name could not be settled: nobody is stored");
        settleUnnamed(now);
    }

    private void addPhotoTo(long now, String id) {
        idJoinId = id;
        idStep = IdStep.ADDING;
        idDeadline = now + tuning.meetTimeoutMs;
        show(EyeState.THINKING, null);
        port.addPhoto(id, tuning.meetTimeoutMs);
    }

    /** The photo joined them (R5, R6): they are met as known, with their notes; a refusal re-creates nobody (KTD12). */
    private void photoStep(long now) {
        CuriosityPort.MatchAnswer k = port.photoAdded();
        if (k == null) {
            if (now < idDeadline) {
                return;
            }
            port.cancelAddPhoto();
            k = CuriosityPort.MatchAnswer.FAILED;
        }
        if (k.status == CuriosityPort.MatchAnswer.Status.KNOWN) {
            note(idYes ? "a yes: the photo joined them; meeting them as known" : "the photo joined them; meeting them as known");
            outcome(idYes ? CuriosityPort.Outcome.YES : CuriosityPort.Outcome.JOINED, idJoinId);
            startAs(now, k);
            return;
        }
        note("the store refused the photo (forgotten meanwhile?): nobody is re-created");
        outcome(idYes ? CuriosityPort.Outcome.YES : CuriosityPort.Outcome.NAME_GIVEN, null);
        // No close match being confirmed: the resolve came from the ladder's NAME step.
        if (confirming == null) {
            welcomeOnly(now, idFirst);
        } else {
            startAs(now, confirming);
        }
    }

    /**
     * Someone new under this name (R6, R7): on the conversation path kept at once
     * and met as known with empty notes, never asked again; on the ladder stored
     * by remember(), whose line promises to remember them. The store records the
     * check's outcome itself.
     */
    private void storeAs(long now, String name) {
        chatCheckOpen = false;
        if (idChat) {
            idName = name;
            idStep = IdStep.KEEPING;
            idDeadline = now + tuning.meetTimeoutMs;
            port.keep(name, tuning.meetTimeoutMs);
            return;
        }
        idStep = IdStep.NONE;
        state = State.REMEMBER;
        helloOnly = false;
        meetDeadline = now + tuning.meetTimeoutMs;
        show(EyeState.THINKING, null);
        port.remember(name, tuning.meetTimeoutMs);
    }

    private void keepStep(long now) {
        CuriosityPort.Kept k = port.keptAnswer();
        if (k == null) {
            if (now < idDeadline) {
                return;
            }
            port.cancelKeep();
            k = CuriosityPort.Kept.FAILED;
        }
        note(k.ok() ? "kept under a new record; meeting them as known" : "the store refused the keep; meeting them by name, unstored");
        startAs(now, CuriosityPort.MatchAnswer.known(idName)
                .withConversation(confirming.persona, k.ok() ? k.personId : null, null, null));
    }

    /**
     * No last name came, or the name could not be settled (R21): nobody is stored.
     * The conversation starts as with a stranger, the first name settled so it is
     * not asked about again; the ladder says hello without a promise.
     */
    private void settleUnnamed(long now) {
        outcome(CuriosityPort.Outcome.NAME_GIVEN, null);
        // idChat is only ever set with confirming (enterConfirm).
        if (idChat) {
            chatSettled = idFirst;
            startAs(now, confirming);
        } else {
            welcomeOnly(now, idFirst);
        }
    }

    /** The ladder's hello with no promise to remember (R19), as for a reply without a name. */
    private void welcomeOnly(long now, String name) {
        idStep = IdStep.NONE;
        state = State.REMEMBER;
        helloOnly = true;
        meetDeadline = now + tuning.meetTimeoutMs;
        show(EyeState.THINKING, null);
        port.welcome(name, tuning.meetTimeoutMs);
    }

    /** The meeting's face check gets its outcome (KTD8); the conversation then records none of its own. */
    private void outcome(CuriosityPort.Outcome o, String joinedId) {
        chatCheckOpen = false;
        port.checkOutcome(o, joinedId);
    }

    /** A meeting ends: its face check, if still open, ends without an answer (KTD8); the identity state is dropped. */
    private void closeMeeting() {
        if (meetingHeld) {
            port.meetingOver();
        }
        idStep = IdStep.NONE;
        confirming = null;
        idChat = false;
        idYes = false;
        idFirst = null;
        idName = null;
        idJoinId = null;
        chatCheckOpen = true;
        chatSettled = null;
        ackUntil = NEVER;
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

    /** SPEAK, for a stop's remark: counted once it is handed to the speech service (countRemark). */
    private void speakRemark(long now, String line) {
        if (pick != null && pick.kind != null && !pick.kind.isLiving()) {
            rememberSaid(line);
        }
        queueLine(now, line, State.SPEAK);
        remarkQueued = true;
        lineStarted(now);
    }

    private void say(long now, String line, State s) {
        queueLine(now, line, s);
        lineStarted(now);
    }

    /** Stops, enters s and queues the line for the speech service. */
    private void queueLine(long now, String line, State s) {
        stopMotors();
        state = s;
        // Close the camera and detector before speech begins, not at the end of
        // this step: synthesis competes with them for the CPU (R6, KTD6). Live,
        // a line after APPROACH took 2.8 s to first audio.
        syncCamera();
        stareAtPick();
        pendingLine = line;
        remarkQueued = false;
        quietUntil = now + tuning.quietWaitMs;
    }

    /** Hands the waiting line to the speech service now. */
    private void handOffLine(long now) {
        sayUntil = now + tuning.sayTimeoutMs;
        String line = pendingLine;
        pendingLine = null;
        port.say(line);
        if (remarkQueued) {
            remarkQueued = false;
            countRemark(now);
        }
    }

    /** A stop's remark handed to the speech service: counted, with the running rate logged. */
    private void countRemark(long now) {
        gauges.count(Gauges.Counter.REMARKS);
        remarkTimes.addLast(now);
        while (now - remarkTimes.peekFirst() > REMARK_WINDOW_MS) {
            remarkTimes.pollFirst();
        }
        note("remarks in the last 10 min: " + remarkTimes.size());
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
        handOffLine(now);
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
            markSeen(pick.box.label);
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
        if (state == State.CUE_TURN && callsOwn() && Math.abs(compass.turned()) < tuning.callBlockedTurnDeg) {
            callBlockedTurns++;
            note("the call's search turn would not turn (" + callBlockedTurns + " of " + tuning.callBlockedTurnsMax
                    + (callBlockedTurns >= tuning.callBlockedTurnsMax ? "): meeting the caller where he is" : ")"));
        }
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
        wedgeTurnTurned = measured ? Math.abs(compass.turned()) : Double.NaN;
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
        if (jammed) {
            note("still jammed: " + why + "; no escape, resting");
            jamRest(now);
            return;
        }
        if (failedLadders > 0 && now - restEndedAt <= tuning.pinnedWindowMs) {
            // The first move after the rest is blocked too: still pinned. The ladder (and
            // its two Claude asks) waits out a longer rest instead of running again now.
            restEndedAt = NEVER;
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
        jamBackStalled = false;
        jamBlockedWays.clear();
        if (turnBlocked && wedgeTurnDir != null && wedgeTurnTurned < tuning.jamTurnDeg) {
            jamBlockedWays.add(wedgeTurnDir);
        }
        wedgeTurnTurned = Double.NaN;
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
                    turnWheels(escDir);
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
            if (!reached && escFrom != null && escMoved < tuning.stallMinCounts) {
                jamBackStalled = true;
            }
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
            if (jamCheck(now)) {
                return;
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
        if (measured && Math.abs(compass.turned()) < tuning.jamTurnDeg) {
            jamBlockedWays.add(escDir);
        }
        if (jamCheck(now)) {
            return;
        }
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
        jammed = false;
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
        if (p == null || !port.canAsk() || now < phantomsIgnoredUntil) {
            return false;
        }
        if (anyoneMet(now) && now >= metClearedUntil) {
            startMetCheck(now, look.jpeg, p);
            return false;
        }
        metClearedUntil = NEVER;
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
    /**
     * Opens a Claude stop on one person: the held pick and the scan cleared, this
     * look (when there is one) the only one scanned, and the person the pick.
     */
    private void beginPersonStop(long now, Look lookOrNull, Detection person) {
        claudeStop = true;
        roamingPick = false;
        cuePick = false;
        heldPick = null;
        scanned.clear();
        scanHeadings.clear();
        askedFrames.clear();
        if (lookOrNull != null) {
            scanned.add(lookOrNull);
            scanHeadings.add(compass.usable(now) ? compass.degrees() : Double.NaN);
            askedFrames.add(new CuriosityPort.Frame(0, lookOrNull.jpeg));
        }
        pick = CuriosityPort.Answer.pick(0, person, CuriosityPort.Kind.PERSON, null);
        pickAt = now;
        pickRecentred = false;
        remarkOnly = false;
    }

    private void approachPerson(long now, Look look, Detection p) {
        note("a person while roaming: going over to meet them");
        lookForLeg = false;
        hopNext = false;
        plannedTicks = -1;
        doorwayLeg = false;
        beginPersonStop(now, look, p);
        roamingPick = true;
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


    // ---- cues, the lean-in and the turn to the voice (meeting plan U7; R1-R3, R6-R9, R15; KTD3-KTD6, KTD8) ----
    //
    // The adapter only enqueues (KTD1); this is the one place cues are consumed.
    // Each tick drains the queue, arms a shove (KTD5), makes an apology strong
    // within bumpApologyMs of a shove or a bump (KTD3), and hands each cue to the
    // per-state table (KTD8): taken, held (KTD3's replacement rule, expiring after
    // cueHoldMs, a strong one degrading to a lean-in), dropped, or, on the way to a
    // person, a confirmation. A taken cue stops the wheels in this same step and
    // enters CUE_TURN: the eyes glance to the side, then measured steps of at most
    // cueTurnStepDeg toward the voice, each re-read from the angle's trend (KTD4),
    // until the angle is under the stop band or grows (the voice was behind), or
    // the estimate is spent. CUE_LOOK then waits up to leanInMs for a look with a
    // face turned toward him (the facing-face box test); nothing found turns to the
    // next look of the plan (strong: that side, behind, the other side; weak: that
    // side, the opposite) and, after the last, resumes quietly with nothing sent. A
    // facing face plays the acknowledgement in a clip window (KTD14) and enters the
    // MEET path. In EYES_ONLY, or with no camera to decide, only a call opens a
    // meeting: no turn, the stranger's lines, the lease guard held off until it
    // ends (KTD8). Calls (strong wake words and names) skip this table: see the
    // call section below. The ears stay open on the charger (hey-miko plan KTD5).

    private enum CueVerdict { TAKE, HOLD, DROP, CONFIRM }

    /**
     * Opens the launcher's ears and closes them at shutdown (they stay open on the charger,
     * hey-miko plan KTD5), and
     * reopens a session that died while wanted (a launcher restart is a lease loss and an
     * ears-session loss at once, meeting plan: "re-opens the ears with the same backoff the
     * drive lease uses"). The backoff mirrors ExploreDrive.scheduleLeaseRetry: the loss is
     * noticed, the first reopen fires 2 s later, then 4, 8, 16 and 30 s apart until one holds.
     */
    private void syncEars(long now) {
        // The ears stay open on the charger (hey-miko plan KTD5): a call there opens a meeting that does not move.
        boolean want = ears.present() && state != State.STOPPED;
        if (want != earsOpen) {
            earsOpen = want;
            earsReopenAttempt = 0;
            earsReopenDueAt = NEVER;
            if (want) {
                note("ears open");
                port.earsOpen();
            } else {
                note("ears closed: stopping");
                port.earsClose();
            }
            return;
        }
        if (!want) {
            return;
        }
        // Wanted and believed open: listening() false means the launcher dropped the session.
        // A reopen reads as listening the moment it is called, before its bind completes, and
        // a failing bind (an older launcher, a restart still coming up) reports lost again
        // shortly after; so the attempt count is not reset on that first true. It resets only
        // when the next due time arrives and the session is still alive, which is what keeps
        // a flapping session backing off to the cap (one bind per interval) instead of
        // binding every 2 s forever.
        if (earsReopenDueAt == NEVER) {
            if (!ears.listening()) {
                long delay = scheduleEarsReopen(now);
                note("ears lost: reopening in " + delay + "ms (attempt 1)");
            }
            return;
        }
        if (now < earsReopenDueAt) {
            return;
        }
        if (ears.listening()) {
            note("ears back after " + earsReopenAttempt + " attempt(s)");
            earsReopenAttempt = 0;
            earsReopenDueAt = NEVER;
            return;
        }
        earsReopenAttempt++;
        port.earsOpen();
        long delay = scheduleEarsReopen(now);
        note("ears reopen attempt " + earsReopenAttempt + ": next look in " + delay + "ms");
    }

    /**
     * Sets when the lost session is next looked at, on the doubling delay of
     * ExploreDrive.scheduleLeaseRetry (2, 4, 8, 16, then 30 s), and returns that delay.
     */
    private long scheduleEarsReopen(long now) {
        long delay = Math.min(EARS_REOPEN_BASE_MS << Math.min(earsReopenAttempt, 4), EARS_REOPEN_MAX_MS);
        earsReopenDueAt = now + delay;
        return delay;
    }

    /** Once per step: the queue, the angle trend, a shove, then the held cue's clock. */
    private void drainEars(long now) {
        syncEars(now);
        List<Ears.Cue> cues = ears.drain();
        Ears.Trend t = ears.trend();
        if (t != null) {
            latestTrend = t;
            trendSeenInStep = true;
        }
        Ears.Shove shove = ears.shove();
        if (!earsOpen || !ears.listening()) {
            if (!cues.isEmpty()) {
                note(cues.size() + " cue(s) ignored: the ears are closed");
            }
            return;
        }
        if (shove != null) {
            offerShove(now, shove);
        }
        for (Ears.Cue c : cues) {
            offerCue(now, apologyUpgraded(c));
        }
        expireHeldCue(now);
    }

    /** A shove counts only while the wheels are commanded stopped and past the blanking window (KTD5). */
    private void offerShove(long now, Ears.Shove shove) {
        long sinceCommand = now - lastMotorCommandAt;
        if (moving || sinceCommand < tuning.shoveBlankingMs) {
            note("shove of " + shove.counts + " ignored: wheels " + (moving ? "moving" : "commanded " + sinceCommand
                    + " ms ago"));
            return;
        }
        note("shoved: " + shove.counts + " counts while stopped");
        gauges.count(Gauges.Counter.SHOVES);
        if (jammed && state == State.CORNERED && !jamProbing && jamPoke == null) {
            jamPoke = "shoved (" + shove.counts + " counts)";
        }
        lastShoveAt = shove.at;
        port.earsShoved(shove.at);
        offerCue(now, new Ears.Cue(Ears.Kind.VOICE, Ears.Tier.WEAK, Ears.Side.UNKNOWN, Float.NaN, shove.at));
    }

    /** A collision stop while driving (a stall, an obstacle): "sorry" within bumpApologyMs is strong (KTD5). */
    private void stampBump(long now) {
        bumpAt = now;
        note("collision stop: a bump");
        port.earsShoved(now);
    }

    /** An apology within bumpApologyMs of a shove or a bump is strong (KTD3); the tier the session gave stands otherwise. */
    private Ears.Cue apologyUpgraded(Ears.Cue c) {
        if (c.kind != Ears.Kind.APOLOGY || c.strong()) {
            return c;
        }
        long last = Math.max(lastShoveAt, bumpAt);
        if (c.at >= last && c.at - last <= tuning.bumpApologyMs) {
            note("an apology " + (c.at - last) + " ms after the bump: strong");
            return new Ears.Cue(c.kind, Ears.Tier.STRONG, c.side, c.angleDeg, c.at);
        }
        return c;
    }

    /** One cue from the session, through the per-state table (KTD8). */
    private void offerCue(long now, Ears.Cue c) {
        gauges.count(Gauges.Counter.CUES);
        gauges.count(c.strong() ? Gauges.Counter.STRONG_CUES : Gauges.Counter.WEAK_CUES);
        if (isCall(c)) {
            offerCall(now, c);
            return;
        }
        if (state == State.CUE_WHERE && callsOwn()) {
            whereReply(now, c);
            return;
        }
        if (callsOwn() && state.approachesPerson()) {
            // The call's own approach (KTD8): only another call can turn him from it.
            dropCue("cue " + c.tier + " " + c.side + " during the call's approach: ignored");
            return;
        }
        if (state.cueSearch()) {
            offerCueDuringSearch(now, c);
            return;
        }
        if (callsOwn() && callerHeard && state == State.MEET && callersVoice(c)) {
            note("cue " + c.tier + " " + c.side + " from the caller during their meeting: theirs");
            return;
        }
        CueVerdict verdict = cueVerdict(now, c);
        if (state.chats() && chat != null && verdict == CueVerdict.DROP) {
            // Not a newcomer: the conversation partner's voice (their words reach the listen).
            partnerSpokeAt = now;
        }
        switch (verdict) {
            case TAKE:
                takeCue(now, c);
                break;
            case HOLD:
                holdCue(now, c);
                break;
            case CONFIRM:
                note("a voice from the person's side: carrying on toward them");
                break;
            default:
                dropCue("cue " + c.tier + " " + c.side + " dropped in " + state);
                break;
        }
    }

    // ---- the call (hey-miko plan U5; R1-R4, R11; KTD1-KTD5) ----
    //
    // A strong WAKE_WORD or NAME cue is a call. It goes to its own slot, never expires
    // and is never dropped: callVerdict (KTD2's order) says whether it waits for a
    // moment to finish or is taken now. A take stops the wheels and plays the answer
    // clip in the same step (KTD3), then searches (the hand-off U6 replaces), carries
    // on toward the person he was already going to, or, on the charger, in EYES_ONLY,
    // without the lease or without a camera, meets without moving. A hazard or a lost
    // lease during the call's search or approach hands it back to the slot, answered,
    // to be retaken without a second clip. The slot clears when the conversation
    // opens, when the call's stop ends without one, or at once when no conversation
    // can open (no Claude): then the answer clip is the whole response. Logs carry
    // sides and counts only.

    private enum CallVerdict { WAIT, IN_PLACE, CARRY_ON, SEARCH }

    /** A call (KTD1): a strong wake word or name cue. Other cues keep the held-cue rules. */
    private static boolean isCall(Ears.Cue c) {
        return c.strong() && (c.kind == Ears.Kind.WAKE_WORD || c.kind == Ears.Kind.NAME);
    }

    /**
     * A call from the session: absorbed when its utterance already called (KTD4), no call
     * when it is the conversation partner's own (inside newcomerAngleDeg), else it fills
     * the slot or merges into the call there, the newest angle winning.
     */
    private void offerCall(long now, Ears.Cue c) {
        if (c.alreadyCalled() && callAts.contains(c.at)) {
            note("the end of a call's utterance: already called");
            return;
        }
        callAts.remove(c.at);
        callAts.addLast(c.at);
        while (callAts.size() > CALL_ATS_KEPT) {
            callAts.removeFirst();
        }
        if (state.chats() && chat != null && !newcomersCall(c)) {
            // Owner 2026-09-30: in a conversation a call that is not clearly someone else's is the
            // partner speaking ("Hey Miko, ..."): no new call, and they are still here.
            partnerSpokeAt = now;
            if (partnersCall(c)) {
                dropCue("a call from the partner's side during the conversation: theirs, no call");
            } else {
                note("a call in " + state + " from " + c.side
                        + ": the conversation partner speaking, the conversation goes on");
            }
            return;
        }
        if (partnersCall(c)) {
            dropCue("a call from the partner's side during the conversation: theirs, no call");
            return;
        }
        if (callsOwn() && callerHeard && state == State.MEET && callersVoice(c)) {
            note("a call from the caller during their meeting: theirs, no call");
            return;
        }
        if (call == null) {
            clearCall();
            call = c;
            gauges.stamp(Gauges.Stage.CALL_HEARD, c.at);
            note("a call from " + c.side + " in " + state);
            if (state.chats() && chat != null && c.hasAngle()) {
                // Outside the partner's angle: the newcomer's glance and "one sec" (R15).
                chat.newcomer(sideOf(c));
            }
            return;
        }
        if (callTaken && state != State.CUE_WHERE && !state.cueSearch() && !state.approachesPerson()) {
            // KTD4: the taken call's own meeting or conversation. Someone else's call waits
            // for it to end; merged into the taken call, it would clear with it (R1).
            if (nextCall == null) {
                nextCall = c;
                note("a call from " + c.side + " during the call's own meeting: it waits for the next turn");
            } else {
                nextCall = mergeCalls(c.kind, nextCall, c);
                note("a repeat of the waiting call merged into it, now from " + nextCall.side);
            }
            return;
        }
        Ears.Cue merged = mergeCalls(c.kind, call, c);
        call = merged;
        if (callTaken && state == State.CUE_WHERE) {
            // KTD9: a new call after "Where'd you go?" looks again, every time, with no second answer.
            note("a new call after nobody was found: looking again");
            startCallSearch(now, merged, false);
            return;
        }
        if (callTaken && state.cueSearch()) {
            // Owner 2026-09-30: the caller calling again from their side (or from nowhere to be
            // told) is the caller talking: the conversation opens now. KTD6: a call with an
            // angle from the other side retargets the search.
            if (!searchCallersVoice(c)) {
                note("a new call from the other side during its search: retargeting");
                stopMotors();
                startCallSearch(now, merged, true);
            } else {
                callerVoiceInSearch(now, merged, "a new call from " + c.side + " during its search");
            }
            return;
        }
        if (callTaken && state.approachesPerson()) {
            // KTD8: merged into the call's own approach unless its angle is away from that person.
            if (c.hasAngle() && !sameSideAsPerson(c)) {
                note("a new call from away from the person during the call's approach: retargeting");
                stopMotors();
                clearStop();
                startCallSearch(now, merged, true);
            } else {
                note("a new call from the person's side during the call's approach: merged into it");
            }
            return;
        }
        note("a second call merged into the first, now from " + merged.side);
    }

    /** Two calls are one (KTD1), of the given kind: the newer one's angle, or side, wins when it has one. */
    private static Ears.Cue mergeCalls(Ears.Kind kind, Ears.Cue older, Ears.Cue newer) {
        boolean newerPlaces = newer.hasAngle() || (!older.hasAngle() && newer.side != Ears.Side.UNKNOWN);
        Ears.Cue where = newerPlaces ? newer : older;
        // The angle keeps the time it was sampled at: KTD7 corrects it for his turning since then.
        return new Ears.Cue(kind, Ears.Tier.STRONG, where.side, where.angleDeg, where.at);
    }

    /**
     * In a conversation, a call whose angle is within newcomerAngleDeg of the partner is
     * the partner's own and makes no call (KTD4). With no angle nobody can be told apart,
     * so it waits as a call. A meeting without a look has nobody placed in front of him.
     */
    private boolean partnersCall(Ears.Cue c) {
        if (!state.converses() || !c.hasAngle() || (wheellessMeeting && !state.chats())) {
            return false;
        }
        return Math.abs(c.angleDeg) <= tuning.newcomerAngleDeg;
    }

    /**
     * In a conversation (owner 2026-09-30), a call is someone else's only when the partner
     * is in view and its angle is clearly away from them (beyond newcomerAngleDeg); with
     * nobody in view, or no angle (the direction chip's side alone), it is the partner's.
     */
    private boolean newcomersCall(Ears.Cue c) {
        return !chatFaceless && c.hasAngle() && Math.abs(c.angleDeg) > tuning.newcomerAngleDeg;
    }

    /** Each step: the call in the slot, waited out or taken by KTD2's order. */
    private void callStep(long now) {
        if (call == null || state == State.STOPPED) {
            return;
        }
        if (callTaken) {
            if (state.inStop()) {
                return;
            }
            // Its search found nobody, or its meeting ended before a conversation opened.
            note("the call's stop is over without a conversation: the call is done");
            clearCall();
            if (call == null) {
                return;
            }
            // A caller who waited through that meeting (KTD4) is taken in this same step.
        }
        CallVerdict v = callVerdict(now, call);
        if (v == CallVerdict.WAIT) {
            if (!callWaitCounted) {
                callWaitCounted = true;
                gauges.count(Gauges.Counter.CUES_HELD);
                note("the call waits for " + state + " to finish");
            }
            return;
        }
        takeCall(now, v);
    }

    /**
     * KTD2's order: a line playing, then a conversation, then the charger (checked before
     * the back-off wait, since a docked robot keeps cycling through STARTLE and BACK_OFF),
     * then this back-off, then no wheels or no camera, then the person he was going to.
     */
    private CallVerdict callVerdict(long now, Ears.Cue c) {
        if (state == State.SPEAK && pendingLine == null) {
            return CallVerdict.WAIT;
        }
        if (state.converses()) {
            return CallVerdict.WAIT;
        }
        if (classifier.charger()) {
            return CallVerdict.IN_PLACE;
        }
        if (state == State.STARTLE || state == State.BACK_OFF) {
            return CallVerdict.WAIT;
        }
        if (jammed) {
            // Fully jammed (robot 2026-10-01): a search turn would only push against the jam.
            return CallVerdict.IN_PLACE;
        }
        if (callBlockedTurns >= tuning.callBlockedTurnsMax) {
            // Robot 2026-10-01 (the motor board latched): his turns do nothing, so no search.
            return CallVerdict.IN_PLACE;
        }
        if (state.escapes() && callAnswered) {
            // A call handed back by a hazard waits out the escape it caused (robot 2026-10-01: its
            // search restarted 40 ms into each wedge escape, so none ever ran). A new call is answered at once.
            return CallVerdict.WAIT;
        }
        if (state == State.EYES_ONLY || !leaseHeld || !camera.available() || now < curiosityOffUntil) {
            return CallVerdict.IN_PLACE;
        }
        if (state.approachesPerson()
                && headingForAPerson() && (!c.hasAngle() || sameSideAsPerson(c))) {
            return CallVerdict.CARRY_ON;
        }
        return CallVerdict.SEARCH;
    }

    /** Takes the call: the wheels stop and the answer plays in this step (KTD3), then what the verdict says. */
    private void takeCall(long now, CallVerdict v) {
        Ears.Cue c = call;
        if (cueHeld != null) {
            dropCue("the held cue gives way to the call");
            cueHeld = null;
        }
        if (v != CallVerdict.CARRY_ON) {
            if (state.escapes()) {
                // An escape cut short is a failed one (robot 2026-10-01: the count stayed at 0 through 11).
                failedLadders++;
                note("the escape gives way to the call (" + failedLadders + " failed escapes in a row)");
            }
            leaveForCue();
            backForTurn = false;
            turnRetrying = false;
        }
        if (!callAnswered) {
            answerCall(now);
        }
        if (!port.canAsk()) {
            // KTD1: no conversation can open, so the answer is the whole response.
            note("no Claude to talk with: the answer is the whole response");
            clearCall();
            if (v == CallVerdict.CARRY_ON) {
                // R4: he still stops for the answer, then carries on the same approach.
                carryOnAfterAnswer(now);
            } else if (state != State.EYES_ONLY) {
                enterPause(now, pauseMs(), false);
            }
            return;
        }
        callTaken = true;
        switch (v) {
            case IN_PLACE:
                // On the charger (KTD5), in EYES_ONLY, without the lease or a camera, or with turns that
                // do nothing (callBlockedTurns): no turn, no drive.
                meetWithoutLooking(now, c, "a call while he cannot turn to it: meeting without a look");
                break;
            case CARRY_ON:
                note("a call from the person's side: answering and carrying on toward them");
                carryOnAfterAnswer(now);
                break;
            default:
                startCallSearch(now, c, false);
                break;
        }
    }

    /** The answer (KTD3): the prepared clip from the answer group, inside a clip window. */
    private void answerCall(long now) {
        callAnswered = true;
        gauges.stamp(Gauges.Stage.CALL_ANSWERED, now);
        note("answering the call");
        port.clipWindow(tuning.answerClipMs);
        sound.playReaction("answer");
        ackUntil = now + tuning.answerClipMs + tuning.deafTailMs;
    }


    /**
     * R4 wins over KTD2 step 6's wording: a call from the person he was going to stops
     * the wheels for the answer, then the same approach goes on from a look taken after
     * the clip (re-facing them if the stop left them off-centre).
     */
    private void carryOnAfterAnswer(long now) {
        if (state != State.FACE && state != State.APPROACH) {
            return;
        }
        stopMotors();
        waitForLook(now);
        lookAfter = Math.max(lookAfter, now + tuning.answerClipMs);
        lookDeadline = Math.max(lookDeadline, lookAfter + tuning.lookTimeoutMs);
    }

    // ---- finding and reaching the caller (hey-miko plan U6; R5-R7, R9, R10; KTD6-KTD9) ----
    //
    // The call's search runs in CUE_TURN and CUE_LOOK with its own plan. With an angle:
    // the bearing, corrected for his own turning since the voice was sampled (KTD7), then
    // its two 45 deg neighbours. With a side and no angle (R7): 90 deg to that side, then 45
    // and 135 on it, then ahead, the other side and behind. With neither: eight 45 deg looks
    // over one circle from straight ahead. Each look takes frames captured once the turn stopped
    // (no settle) and has callLookMs; the first fresh frame with nobody in it ends that look at
    // once, and a stale one with the caller in it counts within callStaleLookDeg (staleCallLook). Any person
    // box at least callPersonMinHeight tall and scoring callPersonMinScore is the caller (no
    // facing-face gate), the one nearest the bearing when there is one. A far one (under callNearHeight) is faced and approached
    // to politeHeight through FACE and APPROACH, a near one is met where it stands; either
    // way the meeting opens with no leave-alone check. Nobody: "Where'd you go?" and a
    // listen (CUE_WHERE); a new call looks again every time, a reply opens the meeting, silence
    // roams. The caller's voice during the search opens the meeting at once (callerSpoke).

    /** Whether this search or stop is the taken call's own. */
    private boolean callsOwn() {
        return callTaken && call != null;
    }

    /** The call's search from here (KTD6): the plan, then the first turn or look. A retarget counts as one. */
    private void startCallSearch(long now, Ears.Cue c, boolean retarget) {
        callerInTurn = null;
        seenLook = false;
        if (!retarget) {
            seenRetargets = 0;
        }
        state = State.CUE_TURN;
        searchCue = c;
        searchRetargeted = retarget;
        if (retarget) {
            gauges.count(Gauges.Counter.RETARGETS);
        } else {
            gauges.count(Gauges.Counter.SEARCHES);
            gauges.stamp(Gauges.Stage.CUE_AT, c.at);
        }
        double since = c.hasAngle() ? headingHistory.turnedSince(c.at) : Double.NaN;
        double[] plan;
        if (!Double.isNaN(since)) {
            // KTD7: the angle is right positive and a turn left since the sample moves the voice right.
            double bearing = Heading.delta(0, c.angleDeg + since);
            plan = new double[]{bearing, bearing - CALL_STEP_DEG, bearing + CALL_STEP_DEG};
            callBearing = bearing;
        } else if (c.side == Ears.Side.LEFT || c.side == Ears.Side.RIGHT) {
            // R7: a side and no angle (the direction chip's usual cue): that side first, at 90 deg,
            // then its 45 deg neighbours (45 and 135), then the rest of one circle in one sweep:
            // ahead, the other side, behind. Right positive, as the plan's bearings are.
            double s = c.side == Ears.Side.LEFT ? -1 : 1;
            plan = new double[]{s * 2 * CALL_STEP_DEG, s * 3 * CALL_STEP_DEG, s * CALL_STEP_DEG, 0,
                    -s * CALL_STEP_DEG, -s * 2 * CALL_STEP_DEG, -s * 3 * CALL_STEP_DEG, -s * 4 * CALL_STEP_DEG};
            callBearing = Double.NaN;
        } else {
            // R9: no side and no usable angle (none, or one older than the heading history): one
            // circle of looks from straight ahead.
            plan = new double[CALL_CIRCLE_LOOKS];
            for (int i = 0; i < plan.length; i++) {
                plan[i] = CALL_STEP_DEG * i;
            }
            callBearing = Double.NaN;
        }
        searchPlan = plan;
        searchLook = 0;
        searchRel = 0;
        searchTurnDoneStamped = false;
        searchFirstTurn = true;
        searchRemainingDeg = Math.abs(plan[0]);
        searchTurnLeft = plan[0] < 0;
        latestTrend = null;
        trendSeenInStep = false;
        show(EyeState.GLANCE, sideOf(c));
        note("looking for the caller" + (!Double.isNaN(callBearing) ? " to the " + (callBearing < 0 ? "left" : "right")
                : c.side == Ears.Side.UNKNOWN ? " with no angle"
                : c.side == Ears.Side.LEFT ? " with no angle, left side first" : " with no angle, right side first")
                + ": " + plan.length + " looks");
        if (searchRemainingDeg < 1) {
            enterCueLook(now);
            return;
        }
        // The camera may still be in its reopen gap: the turn starts now, the look waits for it.
        startCueTurnStep(now, tuning.lookLeadMs);
    }

    /**
     * A fresh look (captured once the turn stopped) during the call's search: a person box ends it
     * (callFound); no caller in it ends this stop at once and the plan moves on, so an empty stop
     * costs one camera interval rather than all of callLookMs (which stays the cap when no fresh
     * frame comes). A person box with no picture waits for the next look.
     */
    private void callLook(long now, Look look) {
        Detection p = callPerson(look.detections);
        if (p == null) {
            callLookOver(now, false);
            return;
        }
        if (look.jpeg == null) {
            lookAfter = look.frameMs + 1;
            return;
        }
        callFound(now, look, p);
    }

    /**
     * A look captured before the call's stop began, seen during it (robot 2026-09-30: he
     * turned past a caller who was in the frames he threw away). A caller in it counts
     * when the heading it was captured at (the heading history) is within
     * callStaleLookDeg of where he faces now: found, and the meeting or the approach
     * follows as for a fresh look. Farther off (robot 2026-10-01: the owner seen 37 and 61 deg
     * away and the search went on blind) it is a bearing to turn to (turnToSeenCaller). A stale look that arrived
     * during the stop means the detector has been working since on a frame captured after
     * the stop began (it takes one frame at a time), due about one detection time later,
     * so the stop waits for it past callLookMs if need be. True when the caller was found.
     */
    private boolean staleCallLook(long now, Look look) {
        callLookChecked = look.frameMs;
        // The confirming look needs a look captured after the one that turned him there.
        Detection p = look.jpeg == null || (seenLook && look.frameMs <= seenFrame) ? null : callPerson(look.detections);
        if (p != null) {
            double turned = headingHistory.turnedSince(look.frameMs);
            if (!Double.isNaN(turned) && Math.abs(turned) <= tuning.callStaleLookDeg) {
                note("a caller in a look captured " + Math.round(Math.abs(turned))
                        + " deg from here before the stop: found");
                callFound(now, look, p);
                return true;
            }
            if (Double.isNaN(turned)) {
                note("a caller in a look captured at an unknown heading: not where he faces now");
            } else if (turnToSeenCaller(now, look, p, turned, false)) {
                return true;
            }
        }
        if (look.frameMs != callStopFrame) {
            long detecting = Math.min(tuning.callLookMs, Math.max(0, now - look.frameMs));
            long due = now + detecting + STALE_LOOK_SLACK_MS;
            if (due > lookDeadline) {
                lookDeadline = due;
                note("a look captured before the stop arrived: the frame captured since is due in about "
                        + detecting + " ms, waiting for it");
            }
        }
        return false;
    }

    /** Slack on a frame's due time (the readings that notice looks come every 100 ms). */
    private static final long STALE_LOOK_SLACK_MS = 200;

    /**
     * A new look during one of the call search's turns: a caller in it whose bearing the turn
     * will not bring into view (passed already, or well beyond where the turn stops) is turned
     * to now (turnToSeenCaller). One the turn's stop will face is left to that stop's look.
     */
    private boolean seenInTurn(long now) {
        Look look = camera.latest();
        if (look == null || look.jpeg == null || look.frameMs == callLookChecked) {
            return false;
        }
        callLookChecked = look.frameMs;
        Detection p = callPerson(look.detections);
        double turned = p == null ? Double.NaN : headingHistory.turnedSince(look.frameMs);
        if (Double.isNaN(turned)) {
            return false;
        }
        // In the turn's own direction: where the caller is, and how far the turn still goes.
        double way = searchTurnLeft ? -1 : 1;
        double at = way * seenBearing(p, turned);
        double ahead = Math.max(0, searchRemainingDeg - turnedSoFar(now));
        if (at >= -tuning.callStaleLookDeg && at <= ahead + tuning.callStaleLookDeg) {
            return false;
        }
        return turnToSeenCaller(now, look, p, turned, true);
    }

    /**
     * Where a caller in a look is from here (right positive): the heading the look was
     * captured at (turned is how far he has turned left since) plus where the box sits in
     * the frame, half the frame's width being cameraHalfFovDeg.
     */
    private double seenBearing(Detection p, double turned) {
        return Heading.delta(0, p.centerX() * tuning.cameraHalfFovDeg + turned);
    }

    /**
     * A caller in a look captured at a known heading away from here (robot 2026-10-01: the
     * owner was seen 37 and 61 deg off and the search went on blind): the planned looks wait
     * while he turns to face them and takes one confirming look, by the call's fresh-frame
     * rules. Someone there is found (callFound); nobody resumes the planned looks
     * (callLookOver). At most callSeenRetargetsMax a call, so blurred boxes cannot swing him
     * to and fro. True when he turned (or looks at once, the bearing being ahead).
     */
    private boolean turnToSeenCaller(long now, Look look, Detection p, double turned, boolean inTurn) {
        double rel = seenBearing(p, turned);
        String where = "a caller in a look captured " + Math.round(Math.abs(turned)) + " deg from here, "
                + Math.round(Math.abs(rel)) + " deg to the " + (rel < 0 ? "left" : "right");
        if (seenRetargets >= tuning.callSeenRetargetsMax) {
            note(where + ": already turned to a seen caller " + seenRetargets + " times this call, left to the plan");
            return false;
        }
        seenRetargets++;
        if (inTurn) {
            double t = turnedSoFar(now);
            stopMotors();
            searchRel += searchTurnLeft ? -t : t;
        }
        if (!seenLook) {
            // The look the search was turning to is still to come; the one it stopped at is done.
            seenResumeLook = inTurn ? searchLook - 1 : searchLook;
        }
        seenLook = true;
        seenFrame = look.frameMs;
        callBearing = searchRel + rel;
        note(where + ": turning to face them (" + seenRetargets + " of " + tuning.callSeenRetargetsMax + ")");
        state = State.CUE_TURN;
        searchFirstTurn = false;
        searchRemainingDeg = Math.abs(rel);
        searchTurnLeft = rel < 0;
        if (searchRemainingDeg < 1) {
            enterCueLook(now);
            return true;
        }
        startCueTurn(now, searchRemainingDeg, searchTurnLeft, 0);
        return true;
    }

    /** KTD6's "found": a person box tall enough, with no aspect-ratio gate; nearest the bearing, else the tallest. */
    private Detection callPerson(List<Detection> found) {
        Detection best = null;
        double bestOff = Double.MAX_VALUE;
        for (Detection d : found) {
            // The call's own floor (robot QA: a floor-level caller scored 0.27-0.32); curiosity keeps confidenceFloor.
            if (d.score < tuning.callPersonMinScore || CuriosityPort.Kind.of(d.label) != CuriosityPort.Kind.PERSON
                    || d.height() <= 0 || d.height() < tuning.callPersonMinHeight) {
                continue;
            }
            double off = Double.isNaN(callBearing) ? -d.height()
                    : Math.abs(Heading.delta(callBearing, searchRel + d.centerX() * tuning.cameraHalfFovDeg));
            if (best == null || off < bestOff) {
                best = d;
                bestOff = off;
            }
        }
        return best;
    }

    /**
     * The caller in front of him (KTD8): far, the call's FACE and APPROACH to politeHeight;
     * near, the meeting here. The answer already played, so no acknowledgement.
     */
    private void callFound(long now, Look look, Detection p) {
        seenLook = false;
        if (callerInTurn != null) {
            // The caller spoke while he turned to them: the meeting opens facing them, and is theirs.
            note("the caller is talking and in view: the meeting opens facing them");
            call = callerInTurn;
            callerInTurn = null;
            callerHeard = true;
        }
        gauges.stamp(Gauges.Stage.FACE_FOUND, now);
        gauges.stamp(Gauges.Stage.CALL_FACING, now);
        gauges.count(Gauges.Counter.FACES_FOUND);
        Ears.Cue c = searchCue;
        chatCueSide = c == null ? null : sideOf(c);
        searchCue = null;
        searchPlan = null;
        beginPersonStop(now, look, p);
        target = p;
        remember(p.label, CuriosityPort.Kind.PERSON, now);
        if (p.height() < tuning.callNearHeight) {
            note("the caller is far: going over to them");
            state = State.FACE;
            faceTurns = 0;
            lostLooks = 0;
            firstLook = false;
            face(now);
            return;
        }
        note("the caller is near: meeting them here");
        if (!port.canAsk()) {
            nameClip(now);
            return;
        }
        enterMeet(now, look.jpeg, p);
    }

    /** The call's look budget passed: the next look of its plan, or "Where'd you go?" once the plan is done. */
    private void callLookOver(long now, boolean noLookAtAll) {
        if (noLookAtAll) {
            // No camera to find them with: the call is met where he is (KTD2), with no second answer.
            note("camera gave no look in time during the call's search; curiosity off for "
                    + tuning.cameraBackoffMs + " ms");
            curiosityOffUntil = now + tuning.cameraBackoffMs;
            callTaken = false;
            takeCall(now, CallVerdict.IN_PLACE);
            return;
        }
        if (seenLook) {
            seenLook = false;
            if (callerInTurn != null) {
                callerSpoke(now, callerInTurn, "nobody where the caller was seen, and the caller is talking");
                return;
            }
            note("nobody where the caller was seen: back to the planned looks");
            searchLook = seenResumeLook;
        }
        if (searchLook + 1 >= searchPlan.length) {
            enterWhere(now);
            return;
        }
        nextSearchLook(now);
    }

    /** KTD9: nobody found. "Where'd you go?" in a clip window, then a listen of callListenMs after it. */
    private void enterWhere(long now) {
        stopMotors();
        seenLook = false;
        state = State.CUE_WHERE;
        searchPlan = null;
        searchCue = null;
        show(EyeState.LISTENING, null);
        note("nobody found for the call: asking where they went");
        port.clipWindow(tuning.whereClipMs);
        sound.playReaction("where");
        ackUntil = now + tuning.whereClipMs + tuning.deafTailMs;
        whereUntil = now + tuning.whereClipMs + tuning.callListenMs;
    }

    /** CUE_WHERE's step: silence for the whole listen resumes roaming, and the call is over. */
    private void whereStep(long now) {
        if (now < whereUntil) {
            return;
        }
        note("no answer after asking where they went: carrying on");
        clearCall();
        quietResume(now);
    }

    /** A voice that is not a call after "Where'd you go?" (owner 2026-09-30): the caller answering, so the conversation opens. */
    private void whereReply(long now, Ears.Cue c) {
        callerSpoke(now, mergeCalls(call.kind, call, c), "a reply after asking where they went");
    }

    /**
     * Whether a voice during the call's search is the caller's (owner 2026-09-30): from the
     * call's side, or from a side that cannot be told (no side, or a call with none).
     */
    private boolean callersVoice(Ears.Cue c) {
        Direction side = sideOf(c);
        Direction callSide = call == null ? null : sideOf(call);
        return side == null || callSide == null || side == callSide;
    }

    /** callersVoice, against the side the search is looking for (the call's, once retargeted). */
    private boolean searchCallersVoice(Ears.Cue c) {
        Direction side = sideOf(c);
        Direction searching = searchCue != null ? sideOf(searchCue) : call == null ? null : sideOf(call);
        return side == null || searching == null || side == searching;
    }

    /**
     * The caller spoke during the call's search or after "Where'd you go?" (owner
     * 2026-09-30: "he needs to start talking to people even when he's looking for them"):
     * the search stops and the call's meeting opens here with nobody in view, the
     * meeting-without-a-look path, which becomes the conversation: Claude's opener, then
     * a listen. No turn: the side-first search already faces the call's side on its first
     * looks. The cue carries no words (Ears passes none), so the opener starts it.
     */
    /**
     * The caller's voice during the search: the conversation opens now, or, during the first
     * turn (the one toward the call's side), once that turn is done, so he faces their side.
     */
    private void callerVoiceInSearch(long now, Ears.Cue c, String why) {
        if (seenLook) {
            // Facing the person he saw beats opening with nobody in view: the confirming look decides.
            if (callerInTurn == null) {
                note(why + ": the caller is talking; the conversation opens once he has looked where they were seen");
            }
            callerInTurn = c;
            return;
        }
        if (state == State.CUE_TURN && searchFirstTurn) {
            if (callerInTurn == null) {
                note(why + ": the caller is talking; the conversation opens once he faces their side");
            }
            callerInTurn = c;
            return;
        }
        callerSpoke(now, c, why);
    }

    private void callerSpoke(long now, Ears.Cue c, String why) {
        callerInTurn = null;
        seenLook = false;
        note(why + ": the caller is talking to him, the conversation opens now");
        call = c;
        meetWithoutLooking(now, c, "the call's meeting opens on the caller's voice, nobody in view");
        callerHeard = true;
    }

    /** The call's approach arrived (KTD8): the meeting opens on this look, not the curiosity line. */
    private void callArrive(long now, Look look) {
        stopMotors();
        hazardTimes.clear();
        note("arrived: a polite distance from the caller");
        if (look != null && look.jpeg != null) {
            enterMeet(now, look.jpeg, target);
        } else {
            enterMeetLook(now);
        }
    }

    /**
     * A hazard or a lost lease interrupts the call's stop (KTD1): during its search or its
     * approach the call goes back to the slot, answered, to be retaken when the escape (or
     * EYES_ONLY) allows; anywhere else (its meeting) the call is over.
     */
    private void handBackCall(String why) {
        if (!callsOwn()) {
            return;
        }
        if (state.cueSearch() || state.approachesPerson()) {
            callTaken = false;
            callWaitCounted = false;
            note(why + " during the call's search or approach: the call waits in its slot");
        } else {
            note(why + " during the call's meeting: the call is done");
            clearCall();
        }
    }

    private void clearCall() {
        call = null;
        callBlockedTurns = 0;
        callTaken = false;
        callAnswered = false;
        callWaitCounted = false;
        callerHeard = false;
        callBearing = Double.NaN;
        if (nextCall != null) {
            // The waiting caller's turn: a fresh, untaken, unanswered call that callStep's verdict takes.
            call = nextCall;
            nextCall = null;
            gauges.stamp(Gauges.Stage.CALL_HEARD, call.at);
            note("the waiting call from " + call.side + " takes the slot");
        }
    }

    /** During a search (KTD3): a strong cue from the other side retargets once; everything else is ignored. */
    private void offerCueDuringSearch(long now, Ears.Cue c) {
        if (callsOwn() && state != State.CUE_WHERE && searchCallersVoice(c)) {
            // Owner 2026-09-30: the caller's voice during the call's search opens the conversation.
            callerVoiceInSearch(now, c.strong() ? c : mergeCalls(call.kind, call, c),
                    "cue " + c.tier + " " + c.side + " during the call's search");
            return;
        }
        Direction side = sideOf(c);
        Direction searching = searchCue == null ? null : sideOf(searchCue);
        if (c.strong() && side != null && searching != null && side != searching && !searchRetargeted) {
            note("strong cue from the other side during the search: retargeting once");
            stopMotors();
            if (callsOwn()) {
                // The call's own search stays the call's, looking where this voice came from.
                call = new Ears.Cue(call.kind, Ears.Tier.STRONG, c.side, c.angleDeg, c.at);
                startCallSearch(now, call, true);
                return;
            }
            gauges.count(Gauges.Counter.RETARGETS);
            enterCueSearch(now, c, true);
            return;
        }
        dropCue("cue " + c.tier + " " + c.side + " during the search: ignored");
    }

    /** The per-state table (KTD8) for a cue arriving now, or a held one whose turn may have come. */
    private CueVerdict cueVerdict(long now, Ears.Cue c) {
        if (state.chats()) {
            return chatVerdict(c);
        }
        if (state == State.EYES_ONLY || !camera.available() || now < curiosityOffUntil) {
            // He cannot move, or has no camera to decide with: only a call opens a meeting (callVerdict).
            return CueVerdict.DROP;
        }
        if (remarkUnderway()) {
            // The remark rate (owner 2026-10-01): a voice that is not a call waits for the remark.
            return CueVerdict.HOLD;
        }
        if (jammed && state == State.CORNERED) {
            // A turn to a voice would only push against the jam; a call is met where he is.
            return CueVerdict.DROP;
        }
        switch (state) {
            case PAUSE: case HOP: case SCAN: case INSPECT: case REACT_HERE: case CORNERED: case ASK: case ORIENT:
                return CueVerdict.TAKE;
            case LOOK: case TURN:
                return escape ? CueVerdict.HOLD : CueVerdict.TAKE;
            case FACE: case APPROACH: case MEET_LOOK: case MEET:
                return sameSideAsPerson(c) ? CueVerdict.CONFIRM : CueVerdict.TAKE;
            case STARTLE: case BACK_OFF: case RETRACE: case CIRCLE: case WAY_OUT: case DRIVE_OFF:
            case ASK_NAME: case LISTEN: case NAME: case REMEMBER: case NAME_CLIP: case CONFIRM: case LAST_NAME:
                return CueVerdict.HOLD;
            default:
                return CueVerdict.DROP;
        }
    }

    /**
     * A stop with a remark to make (the remark rate, owner 2026-10-01): a line at
     * SPEAK, or a pick with a line that is not a person to meet, on its way to it.
     * Office voices that are not calls used to drop nearly every one.
     */
    private boolean remarkUnderway() {
        if (state == State.SPEAK) {
            return true;
        }
        if (state != State.ORIENT && state != State.FACE && state != State.APPROACH && state != State.INSPECT
                && state != State.REACT_HERE) {
            return false;
        }
        return pick != null && (pick.kind != CuriosityPort.Kind.PERSON || remarkOnly) && usable(pick.line);
    }

    /**
     * The CHAT states (KTD8), for cues that are not calls (a call waits the
     * conversation out, callVerdict): a strong utterance whose latched angle magnitude
     * exceeds newcomerAngleDeg is held as a newcomer cue (kept until the conversation
     * ends, however long it runs); one inside it is the speaker's reply (the listen
     * hears it) or dropped, since the listening look tells them when he hears; weak
     * cues are ignored. Without an angle nobody can be told apart, so the voice is the
     * speaker's.
     */
    private CueVerdict chatVerdict(Ears.Cue c) {
        if (!c.strong() || chatFaceless) {
            // With nobody in view nobody can be told apart from the partner.
            return CueVerdict.DROP;
        }
        return c.hasAngle() && Math.abs(c.angleDeg) > tuning.newcomerAngleDeg ? CueVerdict.HOLD : CueVerdict.DROP;
    }

    /** The box the stop is heading for: the target, else the meeting's expected box, else the pick's. */
    private Detection headedBox() {
        return target != null ? target : meetExpect != null ? meetExpect : pick != null ? pick.box : null;
    }

    /** Whether the stop is heading for a person (KTD2 step 6: only a person can be the caller
     * he carries on toward; a call made while he approaches a thing still searches, R1). */
    private boolean headingForAPerson() {
        Detection box = headedBox();
        return box != null && CuriosityPort.Kind.of(box.label) == CuriosityPort.Kind.PERSON;
    }

    /**
     * On the way to a person (R15): a voice from their side confirms the approach;
     * one from elsewhere wins. With an angle, "their side" is within newcomerAngleDeg
     * of where their box puts them; with only a side, a box near the centre counts as
     * either side and a voice with no side as theirs.
     */
    private boolean sameSideAsPerson(Ears.Cue c) {
        Detection box = headedBox();
        if (box == null) {
            return false;
        }
        float cx = box.centerX();
        if (c.hasAngle()) {
            return Math.abs(c.angleDeg - cx * tuning.cameraHalfFovDeg) <= tuning.newcomerAngleDeg;
        }
        if (c.side == Ears.Side.UNKNOWN || Math.abs(cx) <= tuning.centreTolerance) {
            return true;
        }
        return (cx < 0) == (c.side == Ears.Side.LEFT);
    }

    /** Holds a cue for a later state, by KTD3's rule: stronger replaces, or the same tier and side, newer. */
    private void holdCue(long now, Ears.Cue c) {
        if (cueHeld != null) {
            boolean stronger = c.strong() && !cueHeld.strong();
            boolean sameSideNewer = c.tier == cueHeld.tier && c.side == cueHeld.side && c.at >= cueHeld.at;
            if (!stronger && !sameSideNewer) {
                dropCue("cue " + c.tier + " " + c.side + " dropped: a " + cueHeld.tier + " one is held");
                return;
            }
            note("held cue replaced by a " + c.tier + " one from " + c.side);
        } else {
            note("cue " + c.tier + " " + c.side + " held in " + state);
        }
        cueHeld = c;
        gauges.count(Gauges.Counter.CUES_HELD);
        if (state.chats() && chat != null) {
            chat.newcomer(sideOf(c));
        }
    }

    private void dropCue(String why) {
        note(why);
        gauges.count(Gauges.Counter.CUES_DROPPED);
    }

    /** A held cue lasts cueHoldMs; past that a strong one becomes a lean-in and a weak one is dropped (KTD3). */
    private void expireHeldCue(long now) {
        // A newcomer held during a conversation keeps however long it runs (KTD3, R15).
        if (cueHeld == null || state.chats() || now - cueHeld.at <= tuning.cueHoldMs) {
            return;
        }
        if (cueHeld.strong()) {
            note("held strong cue " + (now - cueHeld.at) + " ms old: now a lean-in");
            cueHeld = new Ears.Cue(cueHeld.kind, Ears.Tier.WEAK, cueHeld.side, cueHeld.angleDeg, now);
        } else {
            dropCue("held cue " + (now - cueHeld.at) + " ms old: expired");
            cueHeld = null;
        }
    }

    /** Each step: the held cue, if the state can take it now (a hold state keeps it, a drop state loses it). */
    private void takeHeldCue(long now) {
        if (cueHeld == null || state.cueSearch()) {
            return;
        }
        Ears.Cue c = cueHeld;
        switch (cueVerdict(now, c)) {
            case TAKE:
                cueHeld = null;
                note("taking the held cue");
                takeCue(now, c);
                break;
            case CONFIRM:
                cueHeld = null;
                break;
            case DROP:
                cueHeld = null;
                dropCue("held cue " + c.tier + " " + c.side + " dropped in " + state);
                break;
            default:
                break;
        }
    }

    /** Takes a cue that is not a call: the search (cueVerdict never takes one he cannot turn to). */
    private void takeCue(long now, Ears.Cue c) {
        leaveForCue();
        enterCueSearch(now, c, false);
    }

    /** Stops whatever the state was doing so the search can start: the wheels, a stop's pick or line, a leg plan. */
    private void leaveForCue() {
        stopMotors();
        if (state.inStop()) {
            if (pendingLine != null) {
                note("a voice before the line started: dropping the remark");
            } else if (pick != null) {
                note("a voice: dropping this stop's pick");
            }
            clearStop();
        }
        hopNext = false;
        plannedTicks = -1;
        doorwayLeg = false;
        lookForLeg = false;
        steerWaitUntil = NO_WAIT;
        escape = false;
    }

    /** CUE_TURN: the eyes glance to the side, then the first turn toward the voice (KTD4). */
    private void enterCueSearch(long now, Ears.Cue c, boolean retarget) {
        seenLook = false;
        state = State.CUE_TURN;
        searchCue = c;
        searchRetargeted = retarget;
        if (!retarget) {
            gauges.count(Gauges.Counter.SEARCHES);
            gauges.stamp(Gauges.Stage.CUE_AT, c.at);
            if (!c.strong()) {
                gauges.count(Gauges.Counter.LEAN_INS);
            }
        }
        double first = firstTurnDeg(c);
        searchPlan = lookPlan(first, c.strong());
        searchLook = 0;
        searchRel = 0;
        searchTurnDoneStamped = false;
        searchFirstTurn = true;
        searchRemainingDeg = Math.abs(first);
        searchTurnLeft = first < 0;
        latestTrend = null;
        trendSeenInStep = false;
        show(EyeState.GLANCE, sideOf(c));
        note((c.strong() ? "a strong cue" : "a lean-in") + " from " + c.side
                + (c.hasAngle() ? " at " + Math.round(c.angleDeg) + " deg" : "") + ": " + searchPlan.length + " looks");
        if (searchRemainingDeg < 1) {
            enterCueLook(now);
            return;
        }
        startCueTurnStep(now, tuning.lookLeadMs);
    }

    /** Signed degrees of the first turn (negative left): the angle when there is one, else a set amount to the side. */
    private double firstTurnDeg(Ears.Cue c) {
        if (c.hasAngle()) {
            return Math.max(-180, Math.min(180, c.angleDeg));
        }
        switch (c.side) {
            case LEFT:
                return -tuning.cueTurnDefaultDeg;
            case RIGHT:
                return tuning.cueTurnDefaultDeg;
            default:
                return 0;
        }
    }

    /**
     * The look headings (KTD4): a strong cue cycles that side, behind, the other side
     * for strongCueLooks looks; a weak cue that side and the opposite for weakCueLooks.
     */
    private double[] lookPlan(double first, boolean strong) {
        double side = first;
        double[] cycle;
        if (Math.abs(side) < 1) {
            // No side to go by: ahead, behind, then a quarter turn.
            cycle = strong ? new double[]{0, 180, 90} : new double[]{0, 180};
        } else {
            double behind = side < 0 ? -180 : 180;
            cycle = strong ? new double[]{side, behind, -side} : new double[]{side, -side};
        }
        int looks = strong ? tuning.strongCueLooks : tuning.weakCueLooks;
        double[] plan = new double[looks];
        for (int i = 0; i < looks; i++) {
            plan[i] = cycle[i % cycle.length];
        }
        return plan;
    }

    /** One bounded step of the first turn: at most cueTurnStepDeg, after leadMs, on a fresh reading (LEAD). */
    private void startCueTurnStep(long now, long leadMs) {
        startCueTurn(now, Math.min(searchRemainingDeg, tuning.cueTurnStepDeg), searchTurnLeft, leadMs);
    }

    /** One turn of the search: deg to the left or the right, after leadMs, on a fresh reading (LEAD). */
    private void startCueTurn(long now, double deg, boolean left, long leadMs) {
        heading = left ? Direction.LEFT : Direction.RIGHT;
        turnDeg = deg;
        turnMs = timedMs(deg);
        trendSeenInStep = false;
        step = Step.LEAD;
        phaseUntil = now + leadMs;
        show(EyeState.LOOK, heading);
    }

    /** Degrees the current turn has gone: measured from the gyro, else the amount asked. */
    private double turnedSoFar(long now) {
        return measured && compass.usable(now) ? compass.turned() : turnDeg;
    }

    /**
     * While the first turn runs: the newest trend ends it when the angle is under the
     * stop band (the voice is ahead) or grew (it was behind him); otherwise it re-aims
     * the rest of the turn.
     */
    private boolean trendSaysStop(long now) {
        Ears.Trend t = latestTrend;
        if (t == null || !searchFirstTurn) {
            return false;
        }
        latestTrend = null;
        float mag = Math.abs(t.angleDeg);
        if (mag <= tuning.cueStopBandDeg) {
            note("the voice is ahead: " + Math.round(t.angleDeg) + " deg");
            return true;
        }
        if (t.growing()) {
            note("the voice is behind: " + Math.round(t.angleDeg) + " deg from " + Math.round(t.previousDeg));
            return true;
        }
        searchRemainingDeg = mag;
        searchTurnLeft = t.angleDeg < 0;
        return false;
    }

    /** A step of the first turn ended: what the trend says is left of it, else the estimate less this step. */
    private void cueTurnStepDone(long now, double turned) {
        searchRel += searchTurnLeft ? -turned : turned;
        if (!searchFirstTurn) {
            enterCueLook(now);
            return;
        }
        if (!trendSeenInStep) {
            searchRemainingDeg = Math.max(0, searchRemainingDeg - turnDeg);
        }
        if (searchRemainingDeg >= 1) {
            startCueTurnStep(now, 0);
            return;
        }
        enterCueLook(now);
    }

    /** CUE_LOOK: attentive eyes, the camera deciding within leanInMs of being ready (KTD4). */
    private void enterCueLook(long now) {
        if (callerInTurn != null && callsOwn() && !seenLook) {
            callerSpoke(now, callerInTurn, "the turn toward the caller's side is done");
            return;
        }
        searchFirstTurn = false;
        if (!searchTurnDoneStamped) {
            searchTurnDoneStamped = true;
            gauges.stamp(Gauges.Stage.TURN_DONE, now);
        }
        state = State.CUE_LOOK;
        show(EyeState.LISTENING, null);
        step = Step.WAIT_LOOK;
        lookAfter = now + tuning.lookSettleMs;
        long ready = Math.max(now, cameraClosedAt + tuning.reopenGapMs);
        if (callsOwn()) {
            // Robot 2026-09-30: detection takes 1.8-4.7 s a frame, so no settle wait. The wheels
            // were stopped in this same step (the turn's end, or no turn at all), so any frame
            // captured from now on is fresh; stale ones are still checked (staleCallLook).
            lookAfter = now;
            Look before = camera.latest();
            callStopFrame = before == null ? NEVER : before.frameMs;
            callLookChecked = NEVER;
            // KTD6: a budget; a camera just (re)opened has no look yet and gets the first-look time.
            long budget = camera.latest() == null ? tuning.firstLookTimeoutMs : tuning.callLookMs;
            lookDeadline = Math.max(lookAfter, ready) + budget;
            note(seenLook ? "looking where the caller was seen"
                    : "looking for the caller (look " + (searchLook + 1) + " of " + searchPlan.length + ")");
            return;
        }
        lookDeadline = ready + tuning.leanInMs;
        note("looking for a person toward him (look " + (searchLook + 1) + " of " + searchPlan.length + ")");
    }

    /** A look during CUE_LOOK: a facing face ends the search in the meeting; anything else waits for the next look. */
    private void cueLook(long now, Look look) {
        if (callsOwn()) {
            callLook(now, look);
            return;
        }
        Detection face = facingFace(look.detections);
        if (face == null || look.jpeg == null) {
            lookAfter = look.frameMs + 1;
            return;
        }
        foundFace(now, look, face);
    }

    /** "A face turned toward him" (KTD4): the largest person box that is wide enough for its height and big enough. */
    private Detection facingFace(List<Detection> found) {
        Detection best = null;
        for (Detection d : found) {
            if (d.score < tuning.confidenceFloor || CuriosityPort.Kind.of(d.label) != CuriosityPort.Kind.PERSON) {
                continue;
            }
            float h = d.height();
            if (h <= 0 || h < tuning.facingFaceMinHeight || d.width() / h < tuning.facingFaceMinRatio) {
                continue;
            }
            if (best == null || d.area() > best.area()) {
                best = d;
            }
        }
        return best;
    }

    /** A facing face: the acknowledgement plays in a clip window (KTD14) and the MEET path takes over. */
    private void foundFace(long now, Look look, Detection face) {
        gauges.stamp(Gauges.Stage.FACE_FOUND, now);
        gauges.count(Gauges.Counter.FACES_FOUND);
        // The shape test sees a person box only: the face check in the meeting decides (robot 2026-10-01).
        note("a person toward him after the cue (a person box; the face check decides)");
        port.clipWindow(tuning.ackClipMs);
        sound.playReaction("acknowledge");
        ackUntil = now + tuning.ackClipMs + tuning.deafTailMs;
        Ears.Cue c = searchCue;
        chatCueSide = c == null ? null : sideOf(c);
        searchCue = null;
        searchPlan = null;
        beginPersonStop(now, look, face);
        cuePick = true;
        target = face;
        remember(face.label, CuriosityPort.Kind.PERSON, now);
        if (!port.canAsk()) {
            note("no Claude to meet them with: the name clip");
            nameClip(now);
            return;
        }
        enterMeet(now, look.jpeg, face);
    }

    /** The look's budget passed with no facing face: the next look of the plan, or a quiet resume. */
    private void cueLookOver(long now, boolean noLookAtAll) {
        if (callsOwn()) {
            callLookOver(now, noLookAtAll);
            return;
        }
        if (noLookAtAll) {
            note("camera gave no look in time; curiosity off for " + tuning.cameraBackoffMs + " ms");
            curiosityOffUntil = now + tuning.cameraBackoffMs;
            quietResume(now);
            return;
        }
        if (searchLook + 1 >= searchPlan.length) {
            quietResume(now);
            return;
        }
        nextSearchLook(now);
    }

    /** Turns to the plan's next look (or looks at once when it is where he already faces). */
    private void nextSearchLook(long now) {
        searchLook++;
        double delta = Heading.wrap(searchPlan[searchLook] - searchRel + 180) - 180;
        if (Math.abs(delta) < 1) {
            enterCueLook(now);
            return;
        }
        state = State.CUE_TURN;
        searchFirstTurn = false;
        searchRemainingDeg = Math.abs(delta);
        searchTurnLeft = delta < 0;
        note("nobody facing him here: turning " + (searchTurnLeft ? "left" : "right") + " " + Math.round(Math.abs(delta))
                + " deg for the next look");
        startCueTurn(now, Math.abs(delta), searchTurnLeft, 0);
    }

    /** Nothing found (R3): back to wandering, nothing remembered, nothing sent. */
    private void quietResume(long now) {
        note("nobody facing him: carrying on");
        gauges.count(Gauges.Counter.QUIET_RESUMES);
        endCuriosity(now);
    }

    /**
     * A call while he cannot move or cannot look (KTD8; hey-miko plan KTD5: on the
     * charger too): no turn and no face, so the meeting takes the stranger's text-only
     * lines and the guards that would send him to EYES_ONLY hold off until it ends.
     * takeCall has already checked that Claude is there to meet with.
     */
    private void meetWithoutLooking(long now, Ears.Cue c, String why) {
        note(why);
        gauges.stamp(Gauges.Stage.CUE_AT, c.at);
        stopMotors();
        if (state.inStop()) {
            clearStop();
        }
        chatCueSide = sideOf(c);
        wheellessMeeting = true;
        beginPersonStop(now, null, UNSEEN_PERSON);
        target = null;
        state = State.MEET;
        meetingHeld = true;
        stranger = null;
        matched = null;
        helloOnly = false;
        meetLines = true;
        show(EyeState.THINKING, null);
        meetDeadline = now + tuning.meetTimeoutMs;
        port.lines(tuning.meetTimeoutMs);
    }

    // ---- the conversation (meeting plan U8; KTD7-KTD10, KTD14, R16) ----

    /**
     * The conversation path is open when the ears session is listening and the
     * adapter attached a persona snapshot to the answer (an older launcher, or no
     * session, leaves it null: the meeting runs as it did before).
     */
    private boolean chatPossible(CuriosityPort.MatchAnswer a) {
        return a.persona != null && chatLikely();
    }

    /** The ears session is listening and Claude is set up: a meeting will most likely become a conversation. */
    private boolean chatLikely() {
        return earsOpen && ears.listening() && port.canAsk();
    }

    /** MEET becomes the conversation (KTD8): turn 1 goes out the moment the match answered. */
    private void enterChat(long now, CuriosityPort.MatchAnswer a) {
        stopMotors();
        meetingHeld = true;
        boolean faceless = wheellessMeeting || a.faceless;
        chatFaceless = faceless;
        partnerSpokeAt = NEVER;
        chatSide = chatCueSide != null ? chatCueSide : sideOfPick();
        chatNoWheels = !leaseHeld;
        chatStallSince = NEVER;
        if (a.status == CuriosityPort.MatchAnswer.Status.KNOWN) {
            port.touch();
        }
        note("the meeting becomes a conversation" + (faceless ? " with nobody in view" : "") + " (" + a.status + ")");
        if (callTaken) {
            // KTD1: the call is kept until the conversation opens.
            gauges.stamp(Gauges.Stage.CALL_ARRIVED, now);
            clearCall();
        }
        chat = new ChatSession(tuning, port, chatHost);
        state = State.CHAT_THINK;
        syncPark();
        chat.start(now, a, faceless, chatCheckOpen, chatSettled);
        state = chatState(chat.state());
    }

    private void chatStep(long now) {
        if (chat == null) {
            endCuriosity(now);
            return;
        }
        chat.step(now);
        state = chatState(chat.state());
        syncPark();
        if (chat.finished()) {
            finishChat(now);
        }
    }

    private static State chatState(ChatSession.State s) {
        return State.valueOf(s.name());
    }

    /**
     * The conversation is over: a named person joins the met list through the
     * port's handle; an unnamed one leaves a side-and-time leave-alone (KTD10);
     * a newcomer's held cue is taken now (R15); else the first leg turns away
     * from them (R16), unless he is on the charger.
     */
    private void finishChat(long now) {
        boolean named = chat.named();
        boolean docked = classifier.charger();
        peopleIgnoredUntil = now + tuning.peopleCooldownMs;
        if (named) {
            met.addLast(new Met(port.metId(), now));
            note("conversation with someone named over: left alone for " + (tuning.metLeaveAloneMs / 1000) + " s ("
                    + met.size() + " met recently)");
        } else {
            leaveAloneSide = chatSide;
            leaveAloneUntil = now + tuning.unnamedLeaveAloneMs;
            note("conversation with someone unnamed over: their side (" + chatSide + ") left alone for "
                    + (tuning.unnamedLeaveAloneMs / 1000) + " s");
        }
        if (call != null) {
            note("a call waited for the conversation: it is answered now");
            awayLeg = null;
        } else if (cueHeld != null) {
            note("the newcomer's held cue is taken now");
            cueHeld = new Ears.Cue(cueHeld.kind, cueHeld.tier, cueHeld.side, cueHeld.angleDeg, now);
            awayLeg = null;
        } else if (docked) {
            note("on the charger: no resume leg");
            awayLeg = null;
        } else {
            awayLeg = chatSide == null ? null : chatSide.opposite();
        }
        endCuriosity(now);
        // The guards the conversation held off (KTD7) apply again at once.
        if (!leaseHeld) {
            enterEyesOnly("lease lost");
        } else if (classifier.status(now) == HazardClassifier.Status.UNAVAILABLE) {
            enterEyesOnly("sensors unavailable: " + classifier.reason());
        }
    }

    /** The side the person is on from their box; when centred (he faces them), a random way. */
    private Direction sideOfPick() {
        Detection box = target != null ? target : pick != null ? pick.box : null;
        if (box != null && Math.abs(box.centerX()) > tuning.centreTolerance) {
            return box.centerX() < 0 ? Direction.LEFT : Direction.RIGHT;
        }
        return randomDirection();
    }

    /** The detector is parked through the CHAT states except for the one look the conversation asks for (KTD7). */
    private void syncPark() {
        boolean want = (state.chats() || (state == State.MEET || state.confirms()) && chatLikely()) && !chatLookWanted;
        if (want != parked) {
            parked = want;
            if (!parked && cameraOpen) {
                // The detector is about to run again: face work stops first (KTD11).
                faceWork(false);
            }
            camera.park(parked);
        }
    }

    /** An unnamed conversation's leave-alone (KTD10): a person on that side, within the time, is not approached. */
    private boolean leftAlone(long now, Look look) {
        if (leaveAloneSide == null || now >= leaveAloneUntil) {
            leaveAloneSide = null;
            return false;
        }
        Detection best = null;
        for (Detection d : look.detections) {
            if (CuriosityPort.Kind.of(d.label) == CuriosityPort.Kind.PERSON && (best == null || d.area() > best.area())) {
                best = d;
            }
        }
        if (best == null) {
            return false;
        }
        float cx = best.centerX();
        return Math.abs(cx) <= tuning.centreTolerance || (cx < 0) == (leaveAloneSide == Direction.LEFT);
    }

    /** What the conversation needs from the brain: the eyes, the clips, the gauges, the trace and one look. */
    private final ChatSession.Host chatHost = new ChatSession.Host() {
        @Override
        public void eyes(EyeState s, Direction gaze) {
            if (s == EyeState.STARE) {
                stareAtPick();
            } else {
                show(s, gaze);
            }
        }

        @Override
        public void playClip(String group) {
            sound.playReaction(group);
        }

        @Override
        public void stamp(Gauges.Stage stage, long atMs) {
            gauges.stamp(stage, atMs);
        }

        @Override
        public void count(Gauges.Counter counter) {
            gauges.count(counter);
        }

        @Override
        public void note(String message) {
            ExploreBrain.this.note(message);
        }

        @Override
        public boolean looksAllowed() {
            long now = clock.nowMs();
            if (partnerSpokeAt != NEVER && now - partnerSpokeAt <= tuning.unansweredListenMs) {
                // Owner 2026-09-30: they spoke during that listen (a wake word the listen did not
                // get as words): still here, so no walked-off look; the chat listens again.
                note("the partner spoke during the listen: still here, no walked-off look");
                return false;
            }
            // The camera rule keeps its lease requirement (KTD7): without it the look is skipped.
            return leaseHeld && !chatNoWheels && cameraOpen && camera.available() && clock.nowMs() >= curiosityOffUntil;
        }

        @Override
        public boolean faceLooksAllowed() {
            return leaseHeld && !chatNoWheels && cameraOpen && camera.available() && clock.nowMs() >= curiosityOffUntil;
        }

        @Override
        public void wantLook(boolean want) {
            chatLookWanted = want;
            syncPark();
        }

        @Override
        public Look look() {
            return camera.latest();
        }

        @Override
        public boolean facing(Look look) {
            return facingFace(look.detections) != null;
        }

        @Override
        public boolean charger() {
            return classifier.charger();
        }
    };

    /** A cue's side as a turn direction; null when the mics tied. */
    private static Direction sideOf(Ears.Cue c) {
        if (c.hasAngle() && Math.abs(c.angleDeg) >= 1) {
            return c.angleDeg < 0 ? Direction.LEFT : Direction.RIGHT;
        }
        switch (c.side) {
            case LEFT:
                return Direction.LEFT;
            case RIGHT:
                return Direction.RIGHT;
            default:
                return null;
        }
    }

    // ---- entering states ----

    private void enterEyesOnly(String why) {
        handBackCall("the lease or the sensors lost");
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
        closeMeeting();
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
        searchCue = null;
        searchPlan = null;
        searchFirstTurn = false;
        callerInTurn = null;
        wheellessMeeting = false;
        roamingPick = false;
        cuePick = false;
        chat = null;
        chatCueSide = null;
        chatNoWheels = false;
        chatStallSince = NEVER;
        chatLookWanted = false;
        awayLeg = null;
        if (state != State.EYES_ONLY || shownState == null) {
            note("eyes only: " + why);
        }
        state = State.EYES_ONLY;
        syncPark();
        show(EyeState.EYES_ONLY, null);
    }

    private void enterPause(long now, long ms, boolean keepEyes) {
        state = State.PAUSE;
        lookForLeg = false;
        steerWaitUntil = NO_WAIT;
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
        turnWheels(heading);
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

    // ---- fully jammed (robot 2026-10-01) ----

    /**
     * Fully jammed: in this escape a back-up went nowhere and measured turns both ways
     * turned under jamTurnDeg. Live under a chair, forward, reverse and both turns all
     * moved nothing at once; the ladder and its 30 s rests kept pushing, which risks the
     * motor board's stall latch. Stops the escape now and rests; true when it did.
     */
    private boolean jamCheck(long now) {
        if (tuning.jamTurnDeg <= 0 || !jamBackStalled || jamBlockedWays.size() < 2) {
            return false;
        }
        stopMotors();
        cancelWayOut();
        planner.reset();
        esc = null;
        probing = false;
        escFirstRetry = false;
        escShortBack = false;
        jamBackStalled = false;
        jamBlockedWays.clear();
        jams++;
        jammed = true;
        note("fully jammed: the back-up went nowhere and turns both ways turned under "
                + Math.round(tuning.jamTurnDeg) + " deg; no more pushing (jam " + jams + " since start)");
        jamRest(now);
        return true;
    }

    /** The jammed rest: no motion for jammedRestMs, the help line when due, then one short back-up. */
    private void jamRest(long now) {
        rest(now, tuning.jammedRestMs);
        jamProbing = false;
        jamPoke = null;
        jamMoved = 0;
        jamFrom = lastReading != null && lastReading.hasWheels() ? lastReading : null;
        jamHeading = compass.usable(now) ? compass.degrees() : Double.NaN;
        if (jamHelpAt == NEVER || now - jamHelpAt >= tuning.jamHelpEveryMs) {
            jamHelpPending = true;
        }
        note("jammed: resting " + tuning.jammedRestMs + " ms, then one short back-up");
    }

    /** Wheel counts while jammed: someone pulling him out during the rest, or the probe moving. */
    private void countJamWheels(SensorReading r) {
        if (!jammed || state != State.CORNERED || !r.hasWheels()) {
            return;
        }
        if (jamFrom != null) {
            jamMoved += (Math.abs(r.wheelLeft - jamFrom.wheelLeft) + Math.abs(r.wheelRight - jamFrom.wheelRight)) / 2;
        }
        jamFrom = r;
    }

    /** CORNERED while jammed: the help line, the rest, the probe and its verdict. */
    private void jamStep(long now, boolean fresh) {
        if (jamHelpPending && !jamProbing
                && (camera.quiet() || now - (phaseUntil - tuning.jammedRestMs) >= tuning.quietWaitMs)) {
            // Said once the camera and detector are closed (R6), with no motion under way.
            jamHelpPending = false;
            jamHelpAt = now;
            note("asking for help: \"" + HELP_LINE + "\"");
            port.say(HELP_LINE);
        }
        if (jamProbing) {
            if (now < jamProbeUntil) {
                if (now >= nextTickAt) {
                    nextTickAt += tuning.backTickMs;
                    motor.backTick();
                }
                return;
            }
            if (moving) {
                stopMotors();
                jamJudgeAfter = now;
            }
            if (!fresh || now <= jamJudgeAfter) {
                return;
            }
            jamProbing = false;
            if (jamFrom != null && jamMoved >= tuning.stallMinCounts) {
                jamFreed(now);
            } else {
                note("jam probe went nowhere (" + (jamFrom == null ? "no encoders" : jamMoved + " counts")
                        + "): resting again");
                jamRest(now);
            }
            return;
        }
        if (jamPoke == null && jamFrom != null && jamMoved >= tuning.jamMovedCounts) {
            jamPoke = "moved from outside (" + jamMoved + " counts)";
        }
        if (jamPoke == null && !Double.isNaN(jamHeading) && compass.usable(now)
                && Math.abs(Heading.delta(jamHeading, compass.degrees())) >= tuning.jamMovedDeg) {
            jamPoke = "moved from outside (turned " + Math.round(Heading.delta(jamHeading, compass.degrees()))
                    + " deg)";
        }
        if (jamPoke != null) {
            startJamProbe(now, jamPoke);
        } else if (now >= phaseUntil) {
            startJamProbe(now, "the rest is over");
        }
    }

    /** The one gentle probe: jamProbeTicks back ticks, blind and bounded by their time. */
    private void startJamProbe(long now, String why) {
        note("jam probe: " + why + "; backing up " + tuning.jamProbeTicks + " ticks");
        jamPoke = null;
        jamProbing = true;
        jamMoved = 0;
        jamFrom = lastReading != null && lastReading.hasWheels() ? lastReading : null;
        jamProbeUntil = now + tuning.jamProbeTicks * tuning.backTickMs;
        nextTickAt = now + tuning.backTickMs;
        moving = true;
        motor.backTick();
    }

    /** The probe moved: he is free, and roams again with today's escape state cleared. */
    private void jamFreed(long now) {
        note("jam probe moved " + jamMoved + " counts: free, roaming again");
        jammed = false;
        jamHelpPending = false;
        blockedSides.clear();
        blockedAheadAt = Double.NaN;
        ladderAfterRest = false;
        restEndedAt = NEVER;
        hazardTimes.clear();
        stallStreak = 0;
        failedLadders = 0;
        escapeFailures.clear();
        escapeSide = null;
        lastHazardSide = null;
        enterPause(now, pauseMs(), false);
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

    /**
     * The drive adapter's commands, each stamped with the brain's clock: the
     * shove cue's blanking window runs from the last one (meeting plan KTD5).
     */
    private final class TimedMotor implements Motor {
        private final Motor inner;

        TimedMotor(Motor inner) {
            this.inner = inner;
        }

        public void hopTick() {
            lastMotorCommandAt = clock.nowMs();
            inner.hopTick();
        }

        public void turn(Direction direction) {
            lastMotorCommandAt = clock.nowMs();
            inner.turn(direction);
        }

        public void backTick() {
            lastMotorCommandAt = clock.nowMs();
            inner.backTick();
        }

        public void stop() {
            lastMotorCommandAt = clock.nowMs();
            inner.stop();
        }
    }

    /** Starts the wheels turning d, noting the sign for KTD7's heading history. */
    private void turnWheels(Direction d) {
        turnSign = d == Direction.LEFT ? Heading.LEFT : Heading.RIGHT;
        motor.turn(d);
    }

    private void stopMotors() {
        turnSign = 0;
        if (moving) {
            if (measured && heading != null && compass.turnReached()) {
                // A turn that got there: that way turns again.
                blockedSides.remove(heading);
            }
            // Asked before moving goes false, which drivingForward() needs to see.
            boolean forward = drivingForward();
            moving = false;
            motor.stop();
            long now = clock.nowMs();
            compass.stopped(now);
            if (!forward) {
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
