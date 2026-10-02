package com.miko3.mode.explore;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Host-JVM checks for HazardClassifier and ExploreBrain (U3), driven by
 * scripts/tests/test_explore_brain.py. Lives in the mode's own package so it
 * can reach the package-private classes; neither touches android.* or the
 * shared driver, so the whole wander loop runs under plain javac here.
 *
 * A Rig stands in for everything the brain is handed: the clock, the motors,
 * the eyes and the sound. Time moves in 10 ms steps with a brain tick on every
 * step and a reading every 100 ms (the keepalive poll's cadence), taken from a
 * scripted Feed that may return null for "no reply". Every call the brain makes
 * lands in one timestamped log, so a scenario asserts on order and timing.
 *
 * Prints one "PASS <name>" or "FAIL <name>: <detail>" line per scenario; the
 * Python side asserts on each by name.
 */
public final class ExploreBrainHarness {
    private static int failures;
    /** The seed of the next Rig's brain Random (the coverage scenarios run several). */
    private static long rigSeed = 1;

    /** Tuning for the scripted runs: fixed pause and turn lengths, so timelines are exact. */
    private static ExploreTuning.Builder tuning() {
        return new ExploreTuning.Builder()
                .hopTicks(3).hopTickMs(250)
                .backTicks(1).backTickMs(250)
                .pauseMs(1000, 1000)
                .turnChance(0.0)
                .turnMs(500, 500)
                .escapeTurnMs(800)
                .corneredTurnMs(2400)
                .escape(300, 3000, 3, 60000)
                .lookLeadMs(500)
                .startleMs(400)
                .staleMs(300)
                .frozenTofWindowMs(3000)
                .recoveryStreak(3)
                .cap(3, 20000, 30000)
                .ir1IsLeft(true)
                .reopenGapMs(0)
                // Scripted timelines count exact leg lengths and bends: going somewhere new
                // (U10) is off here and switched on by the coverage scenarios; so is the
                // mid-leg re-aim, switched on by the reaim_ scenarios.
                .coverageOff()
                .reaimOff()
                // Meeting plan U6: the cue, facing-face and conversation numbers from its Assumptions,
                // pinned here so the U7 and U8 timelines are exact.
                .cues(10000, 4000, 45f, 10f, 3, 2)
                .facingFace(0.65f, 0.12f)
                .chat(4000, 5000, 5000, 3000, 2, 30, 500)
                // Owner 2026-10-02: a call opens the conversation at once and he looks for the
                // caller during it (callChatFirst, on by default). The scenarios written before
                // it pin the search-first call so their timelines stay exact; the callchat_
                // scenarios switch it on.
                .callChatFirst(false)
                .calibration(calibration());
    }

    /** tof below 100 is an obstacle; ir above 500 is an edge. Units are the harness's own. */
    private static ExploreTuning.Calibration calibration() {
        return new ExploreTuning.Calibration(100, -1, 500, true);
    }

    // ---- readings ----

    /** tof wobbles by a few counts, as a live sensor does, so it never looks frozen. */
    private static int jitter(long t) {
        return (int) ((t / 100) % 7);
    }

    static SensorReading clear(long t) {
        return new SensorReading(t, 300 + jitter(t), 100, 100, null, false);
    }

    static SensorReading edgeAhead(long t) {
        return new SensorReading(t, 300 + jitter(t), 900, 900, null, false);
    }

    static SensorReading edgeLeft(long t) {
        return new SensorReading(t, 300 + jitter(t), 900, 100, null, false);
    }

    static SensorReading edgeRight(long t) {
        return new SensorReading(t, 300 + jitter(t), 100, 900, null, false);
    }

    static SensorReading obstacle(long t) {
        return new SensorReading(t, 50 + jitter(t), 100, 100, null, false);
    }

    /** Clear floor with wheel counts that track movedUntil (10 counts per 100 ms per wheel). */
    static SensorReading wheels(long t, long movedUntil) {
        long counts = movedUntil / 10;
        return new SensorReading(t, 300 + jitter(t), 100, 100, null, false, 50000 + counts, 40000 + counts);
    }

    static SensorReading cpl2(long t) {
        return new SensorReading(t, 300 + jitter(t), 100, 100, 2, false);
    }

    /** Clear floor with the charger latch set (meeting plan U1, KTD6). */
    static SensorReading charger(long t) {
        return new SensorReading(t, 300 + jitter(t), 100, 100, null, false, false, 0, 0, false, 0, 0, 0,
                true, false, 0, 0, 0);
    }

    interface Feed {
        SensorReading at(long t);
    }

    /** The robot's measured gyro calibration (explore nav plan U1, 2026-09-25): z, +1, 23199.53. */
    static ExploreCalibration.Gyro robotGyro() {
        return new ExploreCalibration.Gyro(2, 1, 23199.53);
    }

    /**
     * A simulated yaw (explore nav plan U2): the true heading turns at rateDegS while
     * the motor turns, then coasts coastDeg further after a stop (the overshoot). Each
     * reading carries the mean rate since the previous one as raw counts on the
     * calibration's axis, plus biasCounts (or biasAt(t)). Left is positive.
     */
    static class YawSim {
        final ExploreCalibration.Gyro gyro;
        double rateDegS = 60;
        /** The wheels turn but he doesn't (wedged under a desk, explore nav plan U5): the yaw stays flat. */
        boolean stuck;
        /** Only turns this way are stuck (+1 left, -1 right, 0 neither): live 2026-09-25, left
         * was blocked while right and reversing were free. */
        int stuckDir;
        /** The first turn's way becomes stuckDir. */
        boolean blockFirstTurnsSide;
        double biasCounts = 92;
        double coastDeg;
        /** Unwrapped, left positive, starting at 0. */
        double trueDeg;
        int dir;
        int coastDir;
        double coastLeft;
        double lastReadDeg;
        long lastReadT = -1;
        double turnStartDeg;
        boolean resultPending;
        /** Each turn's true signed change, coast included, in order. */
        final List<Double> turnResults = new ArrayList<Double>();

        YawSim(ExploreCalibration.Gyro gyro) {
            this.gyro = gyro;
        }

        double biasAt(long t) {
            return biasCounts;
        }

        void turn(int d) {
            finish();
            if (blockFirstTurnsSide && stuckDir == 0) {
                stuckDir = d;
            }
            dir = d;
            coastLeft = 0;
            turnStartDeg = trueDeg;
            resultPending = true;
        }

        void stop() {
            if (dir != 0) {
                coastDir = dir;
                coastLeft = coastDeg;
            }
            dir = 0;
            if (coastLeft <= 0) {
                finish();
            }
        }

        /** Moves the true heading on by ms of motion at rateDegS. */
        void advance(long ms) {
            double s = rateDegS * ms / 1000.0;
            if (dir != 0) {
                if (!stuck && dir != stuckDir) {
                    trueDeg += dir * s;
                }
            } else if (coastLeft > 0) {
                double m = Math.min(coastLeft, s);
                trueDeg += coastDir * m;
                coastLeft -= m;
                if (coastLeft <= 0) {
                    finish();
                }
            }
        }

        void finish() {
            if (resultPending) {
                turnResults.add(trueDeg - turnStartDeg);
                resultPending = false;
            }
        }

        /** The raw yaw rate a reading at t carries. */
        int rawAt(long t) {
            double rate = lastReadT < 0 || t <= lastReadT ? 0 : (trueDeg - lastReadDeg) * 1000.0 / (t - lastReadT);
            lastReadDeg = trueDeg;
            lastReadT = t;
            return (int) Math.round(biasAt(t) + gyro.sign * rate * gyro.countSecondsPer360 / 360.0);
        }

        /** r with this sim's gyro (and the given wheel counts, if it has any). */
        SensorReading wrap(SensorReading r, boolean hasWheels, long wl, long wr) {
            int raw = rawAt(r.timestampMs);
            return new SensorReading(r.timestampMs, r.tof, r.ir1, r.ir2, r.cpl, r.fault, hasWheels, wl, wr,
                    true, gyro.axis == 0 ? raw : 0, gyro.axis == 1 ? raw : 0, gyro.axis == 2 ? raw : 0,
                    r.charger, r.hasAccel, r.accelX, r.accelY, r.accelZ);
        }

        double wrapped() {
            return Heading.wrap(trueDeg);
        }
    }

    /**
     * A walled room for the coverage scenarios (explore nav plan U10): he starts in
     * the middle facing heading 0 (along x); each wheel count moves him 1/countsPerMetre
     * along the true yaw, never closer than 0.15 m to a wall. The floor sensor reads an
     * obstacle when the spot 0.25 m ahead is within 0.1 m of a wall; the camera's
     * openness per column is its distance to the wall. Every 0.5 m cell his true
     * position passes through is counted.
     */
    static final class Room {
        final double width;
        final double depth;
        final double countsPerMetre;
        double x;
        double y;
        final java.util.Set<Long> cells = new java.util.HashSet<Long>();

        Room(double width, double depth, double countsPerMetre) {
            this.width = width;
            this.depth = depth;
            this.countsPerMetre = countsPerMetre;
            x = width / 2;
            y = depth / 2;
            visit();
        }

        /** One count forward (+1) or back (-1) along trueDeg; false (and no move) at a wall. */
        boolean move(int counts, double trueDeg) {
            double r = Math.toRadians(trueDeg);
            double nx = x + counts / countsPerMetre * Math.cos(r);
            double ny = y + counts / countsPerMetre * Math.sin(r);
            if (nx < 0.15 || ny < 0.15 || nx > width - 0.15 || ny > depth - 0.15) {
                return false;
            }
            x = nx;
            y = ny;
            visit();
            return true;
        }

        private void visit() {
            cells.add(((long) Math.floor(x / 0.5) << 32) ^ ((long) Math.floor(y / 0.5) & 0xffffffffL));
        }

        /** Metres to the wall along heading deg. */
        double wallDistance(double deg) {
            double r = Math.toRadians(deg);
            double cx = Math.cos(r);
            double cy = Math.sin(r);
            double d = Double.MAX_VALUE;
            if (cx > 1e-9) d = Math.min(d, (width - x) / cx);
            if (cx < -1e-9) d = Math.min(d, -x / cx);
            if (cy > 1e-9) d = Math.min(d, (depth - y) / cy);
            if (cy < -1e-9) d = Math.min(d, -y / cy);
            return d;
        }

        SensorReading reading(long t, double trueDeg) {
            return wallDistance(trueDeg) < 0.35 ? obstacle(t) : clear(t);
        }

        /** The camera's view at trueDeg: each column open by its distance to the wall. */
        Openness.Profile view(double trueDeg, double halfFovDeg) {
            float[] b = new float[Openness.BINS];
            for (int i = 0; i < b.length; i++) {
                double offset = (i + 0.5) / b.length * 2 - 1;
                double d = wallDistance(trueDeg - offset * halfFovDeg);
                b[i] = (float) Math.max(0.1, Math.min(0.9, (d - 0.3) / 1.5));
            }
            return new Openness.Profile(b, 0.9f);
        }
    }

    /** Something to do to the brain at a scheduled moment (lease changes, shutdown). */
    static final class Action {
        final long at;
        final Runnable run;

        Action(long at, Runnable run) {
            this.at = at;
            this.run = run;
        }
    }

    /** One call the brain made, with the mock time it made it at. */
    static final class Event {
        final long t;
        final String what;

        Event(long t, String what) {
            this.t = t;
            this.what = what;
        }

        @Override
        public String toString() {
            return t + ":" + what;
        }
    }

    /** The brain's whole world. */
    /** What the camera sees at a capture time, given what the robot has done so far; null = no frame. */
    interface Vision {
        List<Detection> see(Rig rig, long t);
    }

    /** The openness profile of a frame captured at t (explore nav plan U4); null = not scored. */
    interface OpenView {
        Openness.Profile at(Rig rig, long t);
    }

    /** The place print of a frame captured at t (visual place memory); null = none. */
    interface PlaceView {
        PlaceMemory.Print at(Rig rig, long t);
    }

    /**
     * The scripted Claude (explore on Claude U4), shaped like Vision: the answer to
     * the rig's nth ask() (1-based, counted over the whole run), or null for a
     * request that never answers (the brain's own deadline ends it).
     */
    interface Claude {
        CuriosityPort.Answer answer(Rig rig, CuriosityPort.LookRequest request, int nth);
    }

    /** The scripted way-out answers (explore nav plan U5): the nth wayOut()'s answer, or null for none ever. */
    interface WayOutScript {
        CuriosityPort.WayOut answer(Rig rig, CuriosityPort.WayOutRequest request, int nth);
    }

    /** The scripted doorway answers (explore nav plan U6): the nth doorway()'s answer, or null for none ever. */
    interface DoorwayScript {
        CuriosityPort.Doorway answer(Rig rig, int nth);
    }

    /** The scripted seek answers (seeking the unfamiliar): the nth seek()'s answer, or null for none ever. */
    interface SeekScript {
        CuriosityPort.WayOut answer(Rig rig, CuriosityPort.SeekRequest request, int nth);
    }

    /** One doorway ask as the fake Claude saw it: when, in which brain state, and the frame it carried. */
    static final class DoorAsk {
        final long t;
        final String state;
        final byte[] jpeg;
        final long timeoutMs;

        DoorAsk(long t, String state, byte[] jpeg, long timeoutMs) {
            this.t = t;
            this.state = state;
            this.jpeg = jpeg;
            this.timeoutMs = timeoutMs;
        }

        @Override
        public String toString() {
            return "ask@" + t + " " + state;
        }
    }

    /** One straight drive: when it started, the true heading then, and the counts it moved (back: negative). */
    static final class Drive {
        final long t;
        final double heading;
        final String kind;
        final String state;
        long end = -1;
        long counts;

        Drive(long t, double heading, String kind, String state) {
            this.t = t;
            this.heading = heading;
            this.kind = kind;
            this.state = state;
        }

        @Override
        public String toString() {
            return kind + "@" + t + "-" + end + " " + state + " " + Math.round(heading) + "deg " + counts;
        }
    }

    interface MatchScript {
        CuriosityPort.MatchAnswer answer(Rig rig, int nth);
    }

    interface NameScript {
        CuriosityPort.Named find(String transcript);
    }

    /** The scripted recently-met checks (explore nav plan U7): the nth check's answer, or null for none ever. */
    interface MetScript {
        CuriosityPort.Recently answer(Rig rig, CuriosityPort.RecentlyMetRequest request, int nth);
    }

    /** One recently-met check as the fake Claude saw it: when, in which brain state, and what it carried. */
    static final class MetCheck {
        final long t;
        final String state;
        final CuriosityPort.RecentlyMetRequest request;
        final long timeoutMs;

        MetCheck(long t, String state, CuriosityPort.RecentlyMetRequest request, long timeoutMs) {
            this.t = t;
            this.state = state;
            this.request = request;
            this.timeoutMs = timeoutMs;
        }

        @Override
        public String toString() {
            return "check@" + t + " " + state + " " + request.met;
        }
    }

    /** One listen's scripted outcome (meeting plan U6): what is heard, and after how long (-1: People.replyMs). */
    static final class Hearing {
        final CuriosityPort.Heard heard;
        final long afterMs;
        /** When the launcher's "answering" arrives, this long after the listen starts; -1: never (an older launcher). */
        final long answeringAfterMs;

        Hearing(CuriosityPort.Heard heard, long afterMs) {
            this(heard, afterMs, -1);
        }

        Hearing(CuriosityPort.Heard heard, long afterMs, long answeringAfterMs) {
            this.heard = heard;
            this.afterMs = afterMs;
            this.answeringAfterMs = answeringAfterMs;
        }

        /** The same hearing, arriving this long after the listen starts. */
        Hearing after(long ms) {
            return new Hearing(heard, ms, answeringAfterMs);
        }

        /** The same hearing, with the answer starting (the port's answering()) this long after the listen starts. */
        Hearing answeringAfter(long ms) {
            return new Hearing(heard, afterMs, ms);
        }
    }

    /** An answer that starts this long into the listen and whose words never come. */
    static Hearing hearAnsweringOnly(long answeringAfterMs) {
        return new Hearing(null, -1, answeringAfterMs);
    }

    static Hearing hearWords(String text) {
        return new Hearing(new CuriosityPort.Heard(CuriosityPort.Heard.Status.WORDS, text), -1);
    }

    static Hearing hearSilence() {
        return new Hearing(CuriosityPort.Heard.NOTHING, -1);
    }

    static Hearing hearFailed() {
        return new Hearing(new CuriosityPort.Heard(CuriosityPort.Heard.Status.FAILED, null), -1);
    }

    /**
     * The scripted listens (meeting plan U6), shaped like Claude: the rig's nth
     * listen() (1-based, counted over the whole run) hears this, or null for a
     * listen that never answers (the brain's own timer ends it).
     */
    interface ListenScript {
        Hearing hear(Rig rig, int nth);

        /** Never answers: the mic stays open for its maxMs and heard() stays null. */
        ListenScript NEVER = (rig, nth) -> null;

        /** Every listen hears the same thing (the old single replay). */
        static ListenScript always(Hearing h) {
            return (rig, nth) -> h;
        }

        /** Turn 1 hears the first, turn 2 the second, ...; every listen past the script hears silence. */
        static ListenScript turns(Hearing... perTurn) {
            return (rig, nth) -> nth <= perTurn.length ? perTurn[nth - 1] : hearSilence();
        }
    }

    /** The scripted conversation turns (meeting plan U6): the nth turn()'s answer, or null for none ever. */
    interface TurnScript {
        CuriosityPort.Turn answer(Rig rig, CuriosityPort.TurnRequest request, int nth);
    }

    /** One turn request as the fake Claude saw it: when, in which brain state, and what it carried. */
    static final class TurnAsk {
        final long t;
        final String state;
        final CuriosityPort.TurnRequest request;
        final long timeoutMs;

        TurnAsk(long t, String state, CuriosityPort.TurnRequest request, long timeoutMs) {
            this.t = t;
            this.state = state;
            this.request = request;
            this.timeoutMs = timeoutMs;
        }

        @Override
        public String toString() {
            return "turn@" + t + " " + state + (request.heard == null ? " opener" : "");
        }
    }

    /** A person standing there until leavesAt: frames captured from then on carry no box (walked off). */
    static Vision personUntil(Detection person, long leavesAt) {
        return (rig, t) -> t < leavesAt ? list(person) : list();
    }

    /** A facing person who turns to profile at turnsAt: frames from then on carry a profile-shaped box. */
    static Vision profileFrom(Detection facing, long turnsAt) {
        return (rig, t) -> list(t < turnsAt ? facing : profileOf(facing));
    }

    /** The same box seen in profile: about half as wide as it is tall, same centre and height. */
    static Detection profileOf(Detection d) {
        float cx = (d.x0 + d.x1) / 2;
        float w = d.height() * 0.5f;
        return new Detection(d.label, d.score, cx - w / 2, d.y0, cx + w / 2, d.y1);
    }

    /** "A face turned toward him" (KTD4) as the fake camera reports it: the tuning's ratio and size thresholds. */
    static boolean facing(Detection d, ExploreTuning t) {
        return d.height() > 0 && d.width() / d.height() >= t.facingFaceMinRatio && d.height() >= t.facingFaceMinHeight;
    }

    /** What the fake launcher and Claude answer in the meet flow; a null answer never comes. */
    static final class People {
        MatchScript match = (rig, nth) -> CuriosityPort.MatchAnswer.FAILED;
        CuriosityPort.MatchAnswer lines = CuriosityPort.MatchAnswer.FAILED;
        /** What each listen hears (meeting plan U6): silence unless the scenario scripts it. */
        ListenScript listen = ListenScript.always(hearSilence());
        long replyMs = 2000;
        /** Like the adapter: the robot's patterns first ("my name is X", "I'm X"), then Claude. */
        NameScript name = t -> {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(?i)^(?:my name is|i'm|i am|call me) (\\w+)$").matcher(t.trim());
            return m.matches() ? CuriosityPort.Named.of(m.group(1)) : CuriosityPort.Named.NONE;
        };
        boolean nameAsksClaude;
        CuriosityPort.Answer remembered = CuriosityPort.Answer.line("Nice to meet you! I'll remember you!");
        /** The faceless hello's line (no promise to remember). */
        CuriosityPort.Answer welcomed = CuriosityPort.Answer.line("So nice to meet you!");
        /** The conversation's persona box (meeting plan U8, KTD11), read when a match answers; null: no conversation path. */
        String persona;
        /** The fake People store: each id's notes as data and the questions asked them, normalised. */
        final java.util.Map<String, String> notes = new java.util.HashMap<String, String>();
        final java.util.Map<String, List<String>> asked = new java.util.HashMap<String, List<String>>();
        /** What keep() answers (null: never), and the ids it hands out. */
        boolean keepFails;
        boolean keepNeverAnswers;
        /**
         * The fake store's named people (face plan U7): id -> stored name, in the
         * store's order. keep() adds its new records here too.
         */
        final java.util.Map<String, String> named = new java.util.LinkedHashMap<String, String>();
        /** How close this meeting's face is to each stored person (unset: 0.1, weak), and the close threshold. */
        final java.util.Map<String, Float> faceScore = new java.util.HashMap<String, Float>();
        float close = 0.363f;
        /** Ids whose addPhoto the store refuses: forgotten meanwhile (KTD12). */
        final java.util.Set<String> refusePhoto = new java.util.HashSet<String>();
        /** The store's answer time for a resolve or an added photo. */
        long storeDelayMs = 300;
    }

    static final class Rig implements ExploreBrain.Clock, ExploreBrain.Motor, ExploreBrain.Eyes, ExploreBrain.Sound,
            ExploreBrain.Camera, CuriosityPort, Ears, ExploreBrain.Gauges {
        final List<Event> log = new ArrayList<Event>();
        final List<Action> actions = new ArrayList<Action>();
        final List<String> violations = new ArrayList<String>();
        final ExploreTuning tuning;
        final ExploreBrain brain;
        final Feed feed;
        long now;
        SensorReading lastFed;
        boolean moving;
        int hopTicksThisHop;
        int maxHopTicks;
        int minHopTicks = Integer.MAX_VALUE;
        /** Distinct leg lengths seen (ticks per completed-or-current leg). */
        final java.util.Set<Integer> legLengths = new java.util.TreeSet<Integer>();
        /** Set by a scenario: the next hopTick reports the lease lost from inside the call. */
        boolean loseLeaseInsideHop;
        /** The yaw model (explore nav plan U2): on whenever the tuning carries a gyro calibration;
         * a scenario may add one to an uncalibrated brain. Null: readings carry no gyro. */
        YawSim yaw;
        /** Simulated encoders: 1 count per wheel per 10 ms driving, creepPer100 per 100 ms once blocked. */
        boolean simWheels;
        long blockedFrom = Long.MAX_VALUE;
        int creepPer100 = 2;
        long wheelLeft = 50000;
        long wheelRight = 40000;
        String motion;
        /** Wheel counts (per wheel) driven forward before blockedFrom. */
        long countsBeforeBlocked;

        /** The fake camera: a look every 500 ms while open, captured 200 ms before it arrives. */
        final Vision vision;
        /** Each look's openness, scripted per heading (explore nav plan U4); null: looks carry none. */
        OpenView openView;
        /** Each look's place print; null: looks carry none (as the camera gave before place memory). */
        PlaceView placeView;
        /** How often a look arrives (captured 200 ms before it does); the live camera gives one every 1-2 s. */
        long lookEveryMs = 500;
        /** How long before it arrives each look was captured: the detector's time (the robot: 1.8-4.7 s). */
        long detectMs = 200;
        /** Looks arrive when (now - lookPhaseMs) is a multiple of lookEveryMs (0: the old phase). */
        long lookPhaseMs;
        /** His true heading every 10 ms of the last 20 s, {t, yaw}: where a look was captured from. */
        private final java.util.ArrayDeque<double[]> yawLog = new java.util.ArrayDeque<double[]>();
        final boolean cameraAvailable;
        boolean cameraOpen;
        long openedAt;
        ExploreBrain.Look latestLook;
        /** Every look was captured before the camera opened: arriving, never new enough. */
        boolean staleLooks;
        /** Like ExploreCamera: after a close, the camera yields nothing until this gap has passed. */
        long reopenGapMs;
        long closedAt = Long.MIN_VALUE / 4;
        /** Like ExploreCamera: a detector run still in flight this long after close(). */
        long detectorTailMs;
        long looksFrom;

        /** The fake Claude and speech: null claude means the port can't ask (today's behavior). */
        final Claude claude;
        /** Claude unavailable on a rig wired with the port and the ears (hey-miko plan KTD1: the answer only). */
        boolean askRefused;
        /** ClaudeCuriosity's rate-limit pause (robot 2026-10-01), on the rig's clock: canAsk() is false until then. */
        long claudePausedUntil;
        /** An UNREACHABLE turn answer starts a pause this long (a 429 behind it), when above 0. */
        long pauseOnUnreachableMs;
        long claudeDelayMs = 1000;
        final List<CuriosityPort.LookRequest> asks = new ArrayList<CuriosityPort.LookRequest>();
        final List<Long> askTimeouts = new ArrayList<Long>();
        CuriosityPort.Answer pending;
        long pendingAt;
        long speechMs = 1500;
        /** Set by the scenario where the camera never goes quiet: the backstop speaks anyway. */
        boolean sayWhileBusyAllowed;
        boolean sayNeverFinishes;
        long sayingUntil = Long.MIN_VALUE;
        /** The fake people flow (U5): scripted answers, each after claudeDelayMs (a reply after replyMs). */
        final People people = new People();
        final List<String> meets = new ArrayList<String>();
        /** The person box each match() carried, in order. */
        final List<Detection> matchBoxes = new ArrayList<Detection>();
        final List<Long> matchTimeouts = new ArrayList<Long>();
        final List<String> stored = new ArrayList<String>();
        int touches;
        long listenMaxMs;
        CuriosityPort.MatchAnswer pendingMatch;
        long pendingMatchAt;
        CuriosityPort.MatchAnswer pendingLines;
        long pendingLinesAt;
        CuriosityPort.Heard pendingHeard;
        long pendingHeardAt;
        /** When the listen's answer started (the port's answering() is true from then until heard()); MAX_VALUE: none. */
        long pendingAnsweringAt = Long.MAX_VALUE;
        CuriosityPort.Named pendingName;
        long pendingNameAt;
        CuriosityPort.Answer pendingRemembered;
        long pendingRememberedAt;
        CuriosityPort.Answer pendingWelcomed;
        long pendingWelcomedAt;
        /** The fake way-out request (explore nav plan U5): null script means every request fails. */
        WayOutScript wayOuts;
        long wayOutDelayMs = 1000;
        final List<CuriosityPort.WayOutRequest> wayOutRequests = new ArrayList<CuriosityPort.WayOutRequest>();
        final List<Long> wayOutTimeouts = new ArrayList<Long>();
        CuriosityPort.WayOut pendingWayOut;
        long pendingWayOutAt;
        /** The fake doorway request (explore nav plan U6): null script means every request fails. */
        DoorwayScript doorways;
        long doorwayDelayMs = 1000;
        final List<DoorAsk> doorwayAsks = new ArrayList<DoorAsk>();
        /** The brain state each doorway answer was handed over in. */
        final List<String> doorwayAnswerStates = new ArrayList<String>();
        CuriosityPort.Doorway pendingDoorway;
        long pendingDoorwayAt;
        int doorwayCancels;
        /** The fake seek request (seeking the unfamiliar): null script means every request fails. */
        SeekScript seeks;
        long seekDelayMs = 1000;
        final List<CuriosityPort.SeekRequest> seekRequests = new ArrayList<CuriosityPort.SeekRequest>();
        CuriosityPort.WayOut pendingSeek;
        long pendingSeekAt;
        int seekCancels;
        /** The fake recently-met check (explore nav plan U7): null script means every check fails. */
        MetScript metChecks;
        long metCheckDelayMs = 1000;
        final List<MetCheck> metCheckLog = new ArrayList<MetCheck>();
        CuriosityPort.Recently pendingMet;
        long pendingMetAt;
        int metCancels;
        /** The fake conversation (meeting plan U6): null script means every turn never answers. */
        TurnScript turns;
        long turnDelayMs = 1000;
        final List<TurnAsk> turnAsks = new ArrayList<TurnAsk>();
        CuriosityPort.Turn pendingTurn;
        long pendingTurnAt;
        int turnCancels;
        /** Every notes delta and forget the brain asked for ("id: delta"; id), and what the store answers. */
        final List<String> notesDeltas = new ArrayList<String>();
        CuriosityPort.Done notesResult = CuriosityPort.Done.OK;
        CuriosityPort.Done pendingNotes;
        long pendingNotesAt;
        final List<String> forgotten = new ArrayList<String>();
        CuriosityPort.Done forgetResult = CuriosityPort.Done.OK;
        CuriosityPort.Done pendingForget;
        long pendingForgetAt;
        /** Every keep() the brain asked for (the name), and the ids handed out. */
        final List<String> kept = new ArrayList<String>();
        CuriosityPort.Kept pendingKept;
        long pendingKeptAt;
        int keepCancels;
        /** The fake resolver (face plan U7): every name resolved, photo added, outcome and meeting end. */
        final List<String> resolves = new ArrayList<String>();
        final List<String> photos = new ArrayList<String>();
        final List<String> outcomes = new ArrayList<String>();
        int meetingOvers;
        String pendingFirst;
        CuriosityPort.Resolved pendingResolved;
        long pendingResolvedAt;
        CuriosityPort.MatchAnswer pendingPhoto;
        long pendingPhotoAt;
        /** The conversation listens (chatListen) and the newcomer angle each carried. */
        int chatListens;
        final List<Float> chatListenAngles = new ArrayList<Float>();
        /** The detector is parked (meeting plan U8, KTD7): no looks come while it is. */
        boolean parked;
        /** The start-up face migration (face plan U6, KTD11) settles at this time (0: at once). */
        long migratedAt;
        /** Whether the brain last allowed face work (migration), and every change it made. */
        boolean faceWorkAllowed;
        final List<Event> faceWorkLog = new ArrayList<Event>();
        /** The brain's trace, when a scenario asked for it (traced). */
        List<String> traceNotes;
        int parks;
        int unparks;
        /** Every clip window the brain declared, and until when the last one keeps the recogniser deaf. */
        final List<Long> clipWindows = new ArrayList<Long>();
        long clipUntil = Long.MIN_VALUE;
        /** Turns commanded in a CHAT state (owner 2026-10-02: the search during the conversation). */
        int chatTurns;
        /** The call conversation's opening (owner 2026-10-02): the persona, no Claude; null when none comes. */
        CuriosityPort.MatchAnswer pendingCallChat;
        long pendingCallChatAt;
        int callChats;
        /** The one-shot mic (KTD1): open from listen() for its maxMs, or until heard() drains the answer. */
        long micOpenUntil = Long.MIN_VALUE;
        int listens;
        /** The fake ears (meeting plan U6): cues, the angle trend and shove spikes as step input. */
        boolean earsPresent = true;
        boolean earsListening;
        /** An earsOpen() before this time binds and fails at once: listening stays false (the reopen backoff). */
        long earsOpenRefusedUntil = Long.MIN_VALUE;
        /** When >= 0, every session opened is lost again this long after the open (a flapping launcher). */
        long earsHoldMs = -1;
        int earsOpens;
        int earsCloses;
        private final List<Ears.Cue> cues = new ArrayList<Ears.Cue>();
        Ears.Trend pendingTrend;
        float lastTrendDeg = Float.NaN;
        Ears.Shove pendingShove;
        /** metId() hands out "met-1", "met-2", ... (null while facelessMeetings: no face to compare). */
        boolean facelessMeetings;
        final List<String> metIdsGiven = new ArrayList<String>();
        /** The true heading at each look's capture time (explore nav plan U5), for aiming checks. */
        final java.util.Map<Long, Double> lookHeadings = new java.util.HashMap<Long, Double>();
        /** Every straight drive, in order; the current one while it runs. */
        final List<Drive> drives = new ArrayList<Drive>();
        Drive drive;
        long driveStartWheel;
        /** When the current (or last) forward leg started. */
        long legStartT;
        /** Reversing moves nothing (a stall during a back-out). */
        boolean backBlocked;
        /** Turning moves no wheel counts (every wheel wedged); else turnWheelsPer100 >= 0: that
         * many counts per wheel per 100 ms (a wedged wheel working slowly); else 1 per 10 ms. */
        boolean turnWheelsBlocked;
        int turnWheelsPer100 = -1;
        /** A wall at this heading (NaN: none) from wallFrom: driving forward while facing within
         * wallHalfDeg of it moves nothing (nose to the wall, live 2026-09-25). */
        double wallAt = Double.NaN;
        long wallFrom = Long.MAX_VALUE;
        double wallHalfDeg = 45;
        /** The yaw unsticks as the nth back move starts (0: never): a little reversing frees the turn. */
        int unstickAfterBacks;
        /** The true heading when the brain first entered CIRCLE (NaN before). */
        double circleFrom = Double.NaN;
        /** A room (explore nav plan U10): walls, a true position the simulated wheels move; null: none. */
        Room room;
        /** Every brain state seen, and every state seen with the camera open. */
        final java.util.Set<ExploreBrain.State> statesSeen = new java.util.TreeSet<ExploreBrain.State>();
        final java.util.Set<ExploreBrain.State> openStates = new java.util.TreeSet<ExploreBrain.State>();
        /** Every state change, in order, kept apart from the call log so its indices stay put. */
        final List<Event> stateLog = new ArrayList<Event>();
        /** His true heading (yaw frame, left positive) at each entry to CUE_LOOK. */
        final List<Double> cueLookYaws = new ArrayList<Double>();
        ExploreBrain.State lastState;

        Rig(ExploreTuning tuning, Feed feed) {
            this(tuning, feed, null, false);
        }

        Rig(ExploreTuning tuning, Feed feed, Vision vision, boolean cameraAvailable) {
            this(tuning, feed, vision, cameraAvailable, null);
        }

        Rig(ExploreTuning tuning, Feed feed, Vision vision, boolean cameraAvailable, Claude claude) {
            this.tuning = tuning;
            this.feed = feed;
            this.vision = vision;
            this.cameraAvailable = cameraAvailable;
            this.claude = claude;
            // The ears are wired with the port (meeting plan U7): a rig without Claude has no
            // conversation to open, and the U6 fake-only scenarios drain the rig themselves.
            this.brain = claude == null
                    ? new ExploreBrain(tuning, this, this, this, this, this, new Random(rigSeed))
                    : new ExploreBrain(tuning, this, this, this, this, this, this, this, new Random(rigSeed));
            this.brain.setGauges(this);
            this.yaw = tuning.gyro == null ? null : new YawSim(tuning.gyro);
        }

        /** One 10 ms step of the simulated wheels. */
        private void advanceWheels() {
            if ("hop".equals(motion)) {
                boolean wall = !Double.isNaN(wallAt) && now >= wallFrom && yaw != null
                        && Math.abs(Heading.delta(yaw.wrapped(), wallAt)) <= wallHalfDeg;
                if (wall) {
                    // Nose to the wall: nothing moves.
                } else if (room != null && !room.move(1, yaw.trueDeg)) {
                    // Nose to a room wall: nothing moves.
                } else if (now <= blockedFrom) {
                    wheelLeft++;
                    wheelRight++;
                    countsBeforeBlocked++;
                } else if (now % 100 == 0) {
                    wheelLeft += creepPer100;
                    wheelRight += creepPer100;
                }
            } else if ("back".equals(motion)) {
                if (!backBlocked && (room == null || room.move(-1, yaw.trueDeg))) {
                    wheelLeft--;
                    wheelRight--;
                }
            } else if ("turn".equals(motion)) {
                if (turnWheelsBlocked) {
                    // Every wheel wedged: the motors push, the encoders stay put.
                } else if (turnWheelsPer100 >= 0) {
                    if (now % 100 == 0) {
                        wheelLeft -= turnWheelsPer100;
                        wheelRight += turnWheelsPer100;
                    }
                } else {
                    wheelLeft--;
                    wheelRight++;
                }
            }
        }

        /** The usual start: brain up, lease granted at once. */
        Rig started() {
            brain.start();
            brain.onLeaseChanged(true);
            return this;
        }

        Rig at(long t, Runnable r) {
            actions.add(new Action(t, r));
            return this;
        }

        void runUntil(long until) {
            while (now < until) {
                now += 10;
                if (yaw != null) {
                    yaw.advance(10);
                }
                if (simWheels) {
                    advanceWheels();
                }
                for (Action a : actions) {
                    if (a.at == now) {
                        a.run.run();
                    }
                }
                if (yaw != null) {
                    yawLog.addLast(new double[]{now, yaw.wrapped()});
                    while (now - (long) yawLog.peekFirst()[0] > 20000) {
                        yawLog.pollFirst();
                    }
                }
                if (cameraOpen && !parked && vision != null && Math.floorMod(now - lookPhaseMs, lookEveryMs) == 0
                        && now >= looksFrom) {
                    long shot = now - detectMs;
                    List<Detection> seen = vision.see(this, shot);
                    if (seen != null) {
                        if (yaw != null) {
                            lookHeadings.put(shot, yaw.wrapped());
                        }
                        latestLook = new ExploreBrain.Look(staleLooks ? openedAt - 1000 : shot, seen,
                                ("jpeg@" + shot).getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                                openView == null ? null : openView.at(this, shot),
                                placeView == null ? null : placeView.at(this, shot));
                    }
                }
                if (now % 100 == 0) {
                    SensorReading r = feed.at(now);
                    if (r != null && (yaw != null || simWheels)) {
                        long wl = simWheels ? wheelLeft : r.wheelLeft;
                        long wr = simWheels ? wheelRight : r.wheelRight;
                        boolean has = simWheels || r.hasWheels();
                        r = yaw != null ? yaw.wrap(r, has, wl, wr)
                                : new SensorReading(r.timestampMs, r.tof, r.ir1, r.ir2, r.cpl, r.fault, has, wl, wr,
                                false, 0, 0, 0);
                    }
                    if (r != null) {
                        lastFed = r;
                        brain.onReading(r);
                        statesSeen.add(brain.state());
                    }
                }
                brain.onTick();
                String broken = cameraRuleBreak(brain.state(), cameraOpen, cameraAvailable, tuning.navigation,
                        earsListening,
                        brain.cameraBackedOff(), moving);
                if (broken != null) {
                    violations.add(now + ":" + broken);
                }
                statesSeen.add(brain.state());
                if (brain.state() != lastState) {
                    lastState = brain.state();
                    if (lastState == ExploreBrain.State.CIRCLE && Double.isNaN(circleFrom) && yaw != null) {
                        circleFrom = yaw.wrapped();
                    }
                    stateLog.add(new Event(now, lastState.name()));
                    if (lastState == ExploreBrain.State.CUE_LOOK && yaw != null) {
                        cueLookYaws.add(yaw.wrapped());
                    }
                }
                if (cameraOpen) {
                    openStates.add(brain.state());
                }
            }
        }

        // ---- the fake ears (meeting plan U6, KTD3, KTD4): step input the scenarios inject ----

        /** A cue the session classified at t, of this kind (its tier follows), from this side and angle. */
        Rig cue(long t, Ears.Kind kind, Ears.Side side, float angleDeg) {
            return at(t, () -> {
                cues.add(Ears.Cue.of(kind, side, angleDeg, t));
                log.add(new Event(t, "cue " + kind + " " + kind.tier + " " + side));
            });
        }

        /**
         * The end-of-utterance delivery of a call spotted early (hey-miko plan KTD4): delivered
         * at t, keyed by the utterance's start at, and marked already called.
         */
        Rig calledCue(long t, long at, Ears.Kind kind, Ears.Side side, float angleDeg) {
            return calledCue(t, at, kind, side, angleDeg, "");
        }

        /** As calledCue, carrying the caller's words besides the address (owner 2026-10-02; null: an older launcher). */
        Rig calledCue(long t, long at, Ears.Kind kind, Ears.Side side, float angleDeg, String message) {
            return at(t, () -> {
                cues.add(new Ears.Cue(kind, kind.tier, side, angleDeg, at, true, message));
                log.add(new Event(t, "cue " + kind + " " + kind.tier + " " + side + " already called"
                        + (message == null || message.isEmpty() ? "" : " with words")));
            });
        }

        /**
         * A call delivered at t whose utterance began at `at` (hey-miko plan KTD7): the
         * spotter fires some way into the words, so he may have turned since the angle's sample.
         */
        Rig lateCue(long t, long at, Ears.Kind kind, Ears.Side side, float angleDeg) {
            return at(t, () -> {
                cues.add(Ears.Cue.of(kind, side, angleDeg, at));
                log.add(new Event(t, "cue " + kind + " " + kind.tier + " " + side + " from " + at));
            });
        }

        /** A cue of this tier: a greeting when strong, a voice burst when weak. */
        Rig cue(long t, Ears.Tier tier, Ears.Side side, float angleDeg) {
            return cue(t, tier == Ears.Tier.STRONG ? Ears.Kind.GREETING : Ears.Kind.VOICE, side, angleDeg);
        }

        /** A latched angle sample at t; the trend carries the previous sample with it. */
        Rig trendAt(long t, float angleDeg) {
            return at(t, () -> {
                pendingTrend = new Ears.Trend(angleDeg, lastTrendDeg, t);
                lastTrendDeg = angleDeg;
            });
        }

        /** An accelerometer spike at t, in the controller's counts above rest. */
        Rig shoveAt(long t, int counts) {
            return at(t, () -> pendingShove = new Ears.Shove(counts, t));
        }

        @Override
        public boolean present() {
            return earsPresent;
        }

        @Override
        public boolean listening() {
            return earsListening;
        }

        @Override
        public List<Ears.Cue> drain() {
            List<Ears.Cue> out = new ArrayList<Ears.Cue>(cues);
            cues.clear();
            return out;
        }

        @Override
        public Ears.Trend trend() {
            Ears.Trend t = pendingTrend;
            pendingTrend = null;
            return t;
        }

        @Override
        public Ears.Shove shove() {
            Ears.Shove s = pendingShove;
            pendingShove = null;
            return s;
        }

        /** True while a listen's one-shot mic is open: a line said now would be heard as a reply. */
        boolean micOpen() {
            return now < micOpenUntil;
        }

        /** The facing face the fake camera would report in a frame captured at t (null: none, or no vision). */
        Detection facingFace(long t) {
            List<Detection> seen = vision == null ? null : vision.see(this, t);
            if (seen == null) {
                return null;
            }
            for (Detection d : seen) {
                if (facing(d, tuning)) {
                    return d;
                }
            }
            return null;
        }

        @Override
        public boolean available() {
            return cameraAvailable;
        }

        /** Floor teaching and brightness calls (explore nav plan U3, U9), kept out of the call log. */
        int floorClearCalls;
        int floorClearFalse;
        long floorClearFalseAt = -1;
        /** {when, through frameMs} per floorDrivenOver. */
        final List<long[]> floorTaught = new ArrayList<long[]>();
        final List<Event> movingCalls = new ArrayList<Event>();

        @Override
        public void setFloorClear(long nowMs, boolean clearAndFree) {
            floorClearCalls++;
            if (!clearAndFree) {
                floorClearFalse++;
                floorClearFalseAt = now;
            }
        }

        @Override
        public void floorDrivenOver(long throughFrameMs) {
            floorTaught.add(new long[]{now, throughFrameMs});
        }

        @Override
        public void setMoving(boolean m) {
            movingCalls.add(new Event(now, m ? "moving" : "still"));
        }

        @Override
        public void open() {
            if (faceWorkAllowed) {
                violations.add(now + ":camera opened with face work allowed in " + brain.state());
            }
            cameraOpen = true;
            openedAt = now;
            looksFrom = Math.max(now, closedAt + reopenGapMs) + 500;
            latestLook = null;
            log.add(new Event(now, "camera open"));
        }

        @Override
        public void close() {
            cameraOpen = false;
            closedAt = now;
            log.add(new Event(now, "camera close"));
        }

        @Override
        public ExploreBrain.Look latest() {
            return latestLook;
        }

        @Override
        public boolean quiet() {
            return !cameraOpen && now >= closedAt + detectorTailMs;
        }

        @Override
        public void park(boolean p) {
            if (p == parked) {
                violations.add(now + ":park(" + p + ") twice in " + brain.state());
            }
            if (!p && cameraOpen && faceWorkAllowed) {
                violations.add(now + ":detector unparked with face work allowed in " + brain.state());
            }
            parked = p;
            if (p) {
                parks++;
            } else {
                unparks++;
            }
            log.add(new Event(now, p ? "park" : "unpark"));
        }

        @Override
        public long nowMs() {
            return now;
        }

        /** A motion may only start on a fresh reading the rig itself would call clear. */
        private void checkStart(String what, boolean needClear) {
            if (lastFed == null || now - lastFed.timestampMs >= tuning.staleMs) {
                violations.add(now + ":" + what + " without a fresh reading");
            } else if (needClear && !isClear(lastFed)) {
                violations.add(now + ":" + what + " on a hazard reading");
            }
        }

        @Override
        public void hopTick() {
            if (!moving) {
                checkStart("hop", true);
                legStartT = now;
                startDrive("hop");
                if (hopTicksThisHop > 0) {
                    legLengths.add(hopTicksThisHop);
                    minHopTicks = Math.min(minHopTicks, hopTicksThisHop);
                }
                hopTicksThisHop = 0;
            }
            moving = true;
            motion = "hop";
            hopTicksThisHop++;
            maxHopTicks = Math.max(maxHopTicks, hopTicksThisHop);
            log.add(new Event(now, "hop"));
            if (loseLeaseInsideHop) {
                loseLeaseInsideHop = false;
                brain.onLeaseChanged(false);
            }
        }

        @Override
        public void turn(ExploreBrain.Direction d) {
            if (moving) {
                violations.add(now + ":turn while still moving");
            }
            if (brain.state().chats()) {
                // Owner 2026-10-02: in a conversation he never turns while he speaks, while a clip
                // plays, or while an answer is in progress (the motors would swallow the words).
                if (now < sayingUntil) {
                    violations.add(now + ":turn while speaking in " + brain.state());
                }
                if (now < clipUntil) {
                    violations.add(now + ":turn while a clip plays in " + brain.state());
                }
                if (answering()) {
                    violations.add(now + ":turn while an answer is in progress in " + brain.state());
                }
                chatTurns++;
            }
            checkStart("turn", false);
            moving = true;
            motion = "turn";
            if (yaw != null) {
                yaw.turn(d == ExploreBrain.Direction.LEFT ? 1 : -1);
            }
            log.add(new Event(now, "turn " + d));
        }

        @Override
        public void backTick() {
            if (!moving || !"back".equals(motion)) {
                startDrive("back");
                if (unstickAfterBacks > 0 && yaw != null) {
                    int backs = 0;
                    for (Drive d : drives) {
                        backs += d.kind.equals("back") ? 1 : 0;
                    }
                    if (backs >= unstickAfterBacks) {
                        yaw.stuck = false;
                    }
                }
            }
            moving = true;
            motion = "back";
            log.add(new Event(now, "back"));
        }

        @Override
        public void stop() {
            if (drive != null) {
                drive.end = now;
                drive.counts = wheelLeft - driveStartWheel;
                drive = null;
            }
            moving = false;
            motion = null;
            if (yaw != null) {
                yaw.stop();
            }
            log.add(new Event(now, "stop"));
        }

        private void startDrive(String kind) {
            drive = new Drive(now, yaw == null ? Double.NaN : yaw.wrapped(), kind, brain.state().name());
            driveStartWheel = wheelLeft;
            drives.add(drive);
        }

        @Override
        public void show(ExploreBrain.EyeState state, ExploreBrain.Direction gaze) {
            log.add(new Event(now, "eyes " + state + (gaze == null ? "" : " " + gaze)));
        }

        @Override
        public void playStartle() {
            log.add(new Event(now, "startle"));
        }

        @Override
        public void stare(float x, float y) {
            log.add(new Event(now, String.format(java.util.Locale.US, "eyes STARE %.2f %.2f", x, y)));
        }

        @Override
        public void playReaction(String group) {
            log.add(new Event(now, "react " + group));
        }

        @Override
        public void playName(String label) {
            log.add(new Event(now, "name " + label));
        }

        // ---- the curiosity port: scripted Claude and speech ----

        @Override
        public boolean canAsk() {
            return claude != null && !askRefused && now >= claudePausedUntil;
        }

        @Override
        public long claudePausedMs() {
            return Math.max(0, claudePausedUntil - now);
        }

        @Override
        public void ask(CuriosityPort.LookRequest request, long timeoutMs) {
            asks.add(request);
            askTimeouts.add(timeoutMs);
            pending = claude.answer(this, request, asks.size());
            pendingAt = now + claudeDelayMs;
            log.add(new Event(now, "ask " + request.frames.size() + " frames"));
        }

        @Override
        public CuriosityPort.Answer answer() {
            if (pending == null || now < pendingAt) {
                return null;
            }
            CuriosityPort.Answer a = pending;
            pending = null;
            log.add(new Event(now, "answer " + a.status));
            return a;
        }

        @Override
        public void cancelAsk() {
            pending = null;
            log.add(new Event(now, "cancel ask"));
        }

        @Override
        public void say(String line) {
            // Speech starts only once the camera and detector are closed (R6, KTD6), except in
            // the conversation, where the camera stays open with the detector parked (KTD7).
            // The close match's question (face plan U7) runs the same way on the conversation path.
            boolean chatting = brain.state().chats() || (brain.state().confirms() && cameraOpen && parked);
            if (cameraOpen && !chatting) {
                violations.add(now + ":say with the camera open in " + brain.state());
            }
            if (chatting && cameraOpen && !parked) {
                violations.add(now + ":say with the detector unparked in " + brain.state());
            }
            if (!quiet() && !sayWhileBusyAllowed && !chatting) {
                violations.add(now + ":say while a detector run is in flight in " + brain.state());
            }
            if (moving && brain.state().chats()) {
                violations.add(now + ":say while turning in " + brain.state());
            }
            // The mic is a one-shot listen (KTD1): a line said while it is open is heard as the reply.
            if (micOpen()) {
                violations.add(now + ":say while the mic is open in " + brain.state());
            }
            sayingUntil = sayNeverFinishes ? Long.MAX_VALUE : now + speechMs;
            log.add(new Event(now, "say " + line));
        }

        @Override
        public boolean sayFinished() {
            return now >= sayingUntil;
        }

        @Override
        public void match(byte[] frameJpeg, Detection personBox, long timeoutMs) {
            meets.add(new String(frameJpeg, java.nio.charset.StandardCharsets.US_ASCII) + " " + personBox.label);
            matchBoxes.add(personBox);
            matchTimeouts.add(timeoutMs);
            pendingMatch = people.match == null ? null : people.match.answer(this, meets.size());
            pendingMatchAt = now + claudeDelayMs;
            log.add(new Event(now, "match"));
        }

        @Override
        public CuriosityPort.MatchAnswer matchAnswer() {
            if (pendingMatch == null || now < pendingMatchAt) {
                return null;
            }
            CuriosityPort.MatchAnswer a = pendingMatch;
            pendingMatch = null;
            log.add(new Event(now, "match " + a.status));
            return a;
        }

        @Override
        public void callChat(long timeoutMs) {
            callChats++;
            pendingCallChat = people.persona == null ? CuriosityPort.MatchAnswer.FAILED
                    : CuriosityPort.MatchAnswer.faceless().withMatch(null, null, Float.NaN, -1L)
                            .withConversation(people.persona, null, null, null);
            pendingCallChatAt = now + 50;
            log.add(new Event(now, "call chat"));
        }

        @Override
        public CuriosityPort.MatchAnswer callChatAnswer() {
            if (pendingCallChat == null || now < pendingCallChatAt) {
                return null;
            }
            CuriosityPort.MatchAnswer a = pendingCallChat;
            pendingCallChat = null;
            log.add(new Event(now, "call chat " + a.status));
            return a;
        }

        @Override
        public void lines(long timeoutMs) {
            pendingLines = people.lines;
            pendingLinesAt = now + claudeDelayMs;
            log.add(new Event(now, "lines"));
        }

        @Override
        public CuriosityPort.MatchAnswer linesAnswer() {
            if (pendingLines == null || now < pendingLinesAt) {
                return null;
            }
            CuriosityPort.MatchAnswer a = pendingLines;
            pendingLines = null;
            log.add(new Event(now, "lines " + a.status));
            return a;
        }

        @Override
        public void listen(long maxMs) {
            if (!sayFinished()) {
                violations.add(now + ":listen while still speaking");
            }
            if (now < clipUntil) {
                violations.add(now + ":listen while a clip plays");
            }
            listenMaxMs = maxMs;
            listens++;
            Hearing h = people.listen == null ? null : people.listen.hear(this, listens);
            pendingHeard = h == null ? null : h.heard;
            pendingHeardAt = now + (h == null || h.afterMs < 0 ? people.replyMs : h.afterMs);
            pendingAnsweringAt = h == null || h.answeringAfterMs < 0 ? Long.MAX_VALUE : now + h.answeringAfterMs;
            micOpenUntil = now + maxMs;
            log.add(new Event(now, "listen"));
        }

        @Override
        public boolean answering() {
            return now >= pendingAnsweringAt;
        }

        @Override
        public CuriosityPort.Heard heard() {
            if (pendingHeard == null || now < pendingHeardAt) {
                return null;
            }
            CuriosityPort.Heard h = pendingHeard;
            pendingHeard = null;
            pendingAnsweringAt = Long.MAX_VALUE;
            micOpenUntil = Long.MIN_VALUE;
            log.add(new Event(now, "heard " + h.status));
            return h;
        }

        @Override
        public void findName(String transcript, long timeoutMs) {
            pendingName = people.name == null ? null : people.name.find(transcript);
            pendingNameAt = now + (people.nameAsksClaude ? claudeDelayMs : 0);
            log.add(new Event(now, "find name"));
        }

        @Override
        public CuriosityPort.Named foundName() {
            if (pendingName == null || now < pendingNameAt) {
                return null;
            }
            CuriosityPort.Named n = pendingName;
            pendingName = null;
            return n;
        }

        @Override
        public void remember(String name, long timeoutMs) {
            stored.add(name == null ? "(unnamed)" : name);
            pendingRemembered = people.remembered;
            pendingRememberedAt = now + claudeDelayMs;
            log.add(new Event(now, "remember " + (name == null ? "(unnamed)" : name)));
        }

        @Override
        public CuriosityPort.Answer remembered() {
            if (pendingRemembered == null || now < pendingRememberedAt) {
                return null;
            }
            CuriosityPort.Answer a = pendingRemembered;
            pendingRemembered = null;
            log.add(new Event(now, "remembered " + a.status));
            return a;
        }

        // No @Override: these compile against the port with or without welcome().
        public void welcome(String name, long timeoutMs) {
            pendingWelcomed = people.welcomed;
            pendingWelcomedAt = now + claudeDelayMs;
            log.add(new Event(now, "welcome " + (name == null ? "(unnamed)" : name)));
        }

        public CuriosityPort.Answer welcomed() {
            if (pendingWelcomed == null || now < pendingWelcomedAt) {
                return null;
            }
            CuriosityPort.Answer a = pendingWelcomed;
            pendingWelcomed = null;
            log.add(new Event(now, "welcomed " + a.status));
            return a;
        }

        @Override
        public void wayOut(CuriosityPort.WayOutRequest request, long timeoutMs) {
            wayOutRequests.add(request);
            wayOutTimeouts.add(timeoutMs);
            pendingWayOut = wayOuts == null ? CuriosityPort.WayOut.failed()
                    : wayOuts.answer(this, request, wayOutRequests.size());
            pendingWayOutAt = now + wayOutDelayMs;
            log.add(new Event(now, "way-out " + request.frames.size() + " frames"));
        }

        @Override
        public CuriosityPort.WayOut wayOutAnswer() {
            if (pendingWayOut == null || now < pendingWayOutAt) {
                return null;
            }
            CuriosityPort.WayOut a = pendingWayOut;
            pendingWayOut = null;
            log.add(new Event(now, "way-out answer " + a.status));
            return a;
        }

        @Override
        public void doorway(byte[] jpeg, long timeoutMs) {
            doorwayAsks.add(new DoorAsk(now, brain.state().name(), jpeg, timeoutMs));
            pendingDoorway = doorways == null ? CuriosityPort.Doorway.failed()
                    : doorways.answer(this, doorwayAsks.size());
            pendingDoorwayAt = now + doorwayDelayMs;
        }

        @Override
        public CuriosityPort.Doorway doorwayAnswer() {
            if (pendingDoorway == null || now < pendingDoorwayAt) {
                return null;
            }
            CuriosityPort.Doorway a = pendingDoorway;
            pendingDoorway = null;
            doorwayAnswerStates.add(brain.state().name());
            return a;
        }

        @Override
        public void cancelDoorway() {
            pendingDoorway = null;
            doorwayCancels++;
        }

        @Override
        public void seek(CuriosityPort.SeekRequest request, long timeoutMs) {
            seekRequests.add(request);
            pendingSeek = seeks == null ? CuriosityPort.WayOut.failed() : seeks.answer(this, request, seekRequests.size());
            pendingSeekAt = now + seekDelayMs;
            log.add(new Event(now, "seek " + request.frames.size() + " frames"));
        }

        @Override
        public CuriosityPort.WayOut seekAnswer() {
            if (pendingSeek == null || now < pendingSeekAt) {
                return null;
            }
            CuriosityPort.WayOut a = pendingSeek;
            pendingSeek = null;
            log.add(new Event(now, "seek answer " + a.status));
            return a;
        }

        @Override
        public void cancelSeek() {
            pendingSeek = null;
            seekCancels++;
        }

        @Override
        public void cancelWayOut() {
            pendingWayOut = null;
            log.add(new Event(now, "cancel way-out"));
        }

        @Override
        public void touch() {
            touches++;
            log.add(new Event(now, "touch"));
        }

        @Override
        public void recentlyMet(CuriosityPort.RecentlyMetRequest request, long timeoutMs) {
            metCheckLog.add(new MetCheck(now, brain.state().name(), request, timeoutMs));
            pendingMet = metChecks == null ? CuriosityPort.Recently.failed()
                    : metChecks.answer(this, request, metCheckLog.size());
            pendingMetAt = now + metCheckDelayMs;
            log.add(new Event(now, "met-check " + request.met.size()));
        }

        @Override
        public CuriosityPort.Recently recentlyMetAnswer() {
            if (pendingMet == null || now < pendingMetAt) {
                return null;
            }
            CuriosityPort.Recently a = pendingMet;
            pendingMet = null;
            log.add(new Event(now, "met-check answer " + a.status));
            return a;
        }

        @Override
        public void cancelRecentlyMet() {
            pendingMet = null;
            metCancels++;
        }

        @Override
        public String metId() {
            String id = facelessMeetings ? null : "met-" + (metIdsGiven.size() + 1);
            metIdsGiven.add(id);
            return id;
        }

        // ---- the fake conversation (meeting plan U6): scripted turns, the store and the ears session ----

        @Override
        public void turn(CuriosityPort.TurnRequest request, long timeoutMs) {
            turnAsks.add(new TurnAsk(now, brain.state().name(), request, timeoutMs));
            pendingTurn = turns == null ? null : turns.answer(this, request, turnAsks.size());
            pendingTurnAt = now + turnDelayMs;
            log.add(new Event(now, "turn"));
        }

        @Override
        public CuriosityPort.Turn turnAnswer() {
            if (pendingTurn == null || now < pendingTurnAt) {
                return null;
            }
            CuriosityPort.Turn t = pendingTurn;
            pendingTurn = null;
            if (t.status == CuriosityPort.Turn.Status.UNREACHABLE && pauseOnUnreachableMs > 0) {
                claudePausedUntil = now + pauseOnUnreachableMs;
            }
            log.add(new Event(now, "turn " + t.status));
            return t;
        }

        @Override
        public void cancelTurn() {
            pendingTurn = null;
            turnCancels++;
            log.add(new Event(now, "cancel turn"));
        }

        @Override
        public void notesDelta(String personId, String notesUpdate, long timeoutMs) {
            notesDeltas.add(personId + ": " + notesUpdate);
            pendingNotes = notesResult;
            pendingNotesAt = now + claudeDelayMs;
            log.add(new Event(now, "notes " + personId));
        }

        @Override
        public CuriosityPort.Done notesDeltaAnswer() {
            if (pendingNotes == null || now < pendingNotesAt) {
                return null;
            }
            CuriosityPort.Done d = pendingNotes;
            pendingNotes = null;
            log.add(new Event(now, "notes " + d.status));
            return d;
        }

        @Override
        public void cancelNotesDelta() {
            pendingNotes = null;
            log.add(new Event(now, "cancel notes"));
        }

        @Override
        public void forget(String personId, long timeoutMs) {
            forgotten.add(personId);
            pendingForget = forgetResult;
            pendingForgetAt = now + claudeDelayMs;
            log.add(new Event(now, "forget " + personId));
        }

        @Override
        public CuriosityPort.Done forgetAnswer() {
            if (pendingForget == null || now < pendingForgetAt) {
                return null;
            }
            CuriosityPort.Done d = pendingForget;
            pendingForget = null;
            log.add(new Event(now, "forgot " + d.status));
            return d;
        }

        @Override
        public void cancelForget() {
            pendingForget = null;
            log.add(new Event(now, "cancel forget"));
        }

        @Override
        public void chatListen(long maxMs, float newcomerAngleDeg) {
            chatListens++;
            chatListenAngles.add(newcomerAngleDeg);
            listen(maxMs);
        }

        @Override
        public void keep(String name, long timeoutMs) {
            kept.add(name);
            pendingKept = people.keepNeverAnswers ? null
                    : people.keepFails ? CuriosityPort.Kept.FAILED : CuriosityPort.Kept.done("kept-" + kept.size());
            if (pendingKept != null && pendingKept.ok()) {
                people.named.put(pendingKept.personId, name);
            }
            pendingKeptAt = now + claudeDelayMs;
            log.add(new Event(now, "keep"));
        }

        @Override
        public boolean migrated() {
            return now >= migratedAt;
        }

        @Override
        public void faceWork(boolean allowed) {
            if (allowed == faceWorkAllowed && !faceWorkLog.isEmpty()) {
                violations.add(now + ":faceWork(" + allowed + ") twice in " + brain.state());
            }
            if (allowed && (cameraOpen ? !parked : !quiet())) {
                violations.add(now + ":face work allowed while the detector may run in " + brain.state());
            }
            faceWorkAllowed = allowed;
            faceWorkLog.add(new Event(now, allowed ? "face work on" : "face work off"));
        }

        @Override
        public CuriosityPort.Kept keptAnswer() {
            if (pendingKept == null || now < pendingKeptAt) {
                return null;
            }
            CuriosityPort.Kept k = pendingKept;
            pendingKept = null;
            log.add(new Event(now, "kept " + k.status));
            return k;
        }

        @Override
        public void cancelKeep() {
            pendingKept = null;
            keepCancels++;
            log.add(new Event(now, "cancel keep"));
        }

        // ---- the fake resolver and photo store (face plan U7): the real NameResolver over a fake store ----

        @Override
        public String nameIn(String transcript) {
            return fakeNameIn(transcript);
        }

        @Override
        public void resolveName(String name, long timeoutMs) {
            resolves.add(name);
            List<String> ids = idsNamed(people, name);
            NameResolver.Decision d = NameResolver.resolve(name, PROBE, ids, galleryOf(people, ids), people.close);
            if (d.kind == NameResolver.Kind.ASK_LAST_NAME) {
                pendingFirst = d.name;
            }
            pendingResolved = resolvedOf(people, d);
            pendingResolvedAt = now + people.storeDelayMs;
            log.add(new Event(now, "resolve " + name));
        }

        @Override
        public void resolveLastName(String lastName, long timeoutMs) {
            resolves.add("last " + lastName);
            String full = pendingFirst + " " + lastName;
            java.util.Map<String, String> stored = new java.util.LinkedHashMap<String, String>();
            for (String id : idsNamed(people, full)) {
                stored.put(id, people.named.get(id));
            }
            pendingResolved = pendingFirst == null ? CuriosityPort.Resolved.FAILED
                    : resolvedOf(people, NameResolver.afterLastName(pendingFirst, lastName, stored));
            pendingResolvedAt = now + people.storeDelayMs;
            log.add(new Event(now, "resolve last " + lastName));
        }

        @Override
        public CuriosityPort.Resolved resolved() {
            if (pendingResolved == null || now < pendingResolvedAt) {
                return null;
            }
            CuriosityPort.Resolved r = pendingResolved;
            pendingResolved = null;
            log.add(new Event(now, "resolved " + r.status + (r.personId == null ? "" : " " + r.personId)));
            return r;
        }

        @Override
        public void cancelResolve() {
            pendingResolved = null;
            log.add(new Event(now, "cancel resolve"));
        }

        @Override
        public void addPhoto(String personId, long timeoutMs) {
            photos.add(personId);
            boolean refused = people.refusePhoto.contains(personId) || !people.named.containsKey(personId);
            CuriosityPort.MatchAnswer a = CuriosityPort.MatchAnswer.known(people.named.get(personId));
            pendingPhoto = refused ? CuriosityPort.MatchAnswer.FAILED : people.persona == null ? a
                    : a.withConversation(people.persona, personId, people.notes.get(personId),
                            people.asked.get(personId));
            pendingPhotoAt = now + people.storeDelayMs;
            log.add(new Event(now, "add photo " + personId));
        }

        @Override
        public CuriosityPort.MatchAnswer photoAdded() {
            if (pendingPhoto == null || now < pendingPhotoAt) {
                return null;
            }
            CuriosityPort.MatchAnswer a = pendingPhoto;
            pendingPhoto = null;
            log.add(new Event(now, "photo " + a.status));
            return a;
        }

        @Override
        public void cancelAddPhoto() {
            pendingPhoto = null;
            log.add(new Event(now, "cancel photo"));
        }

        @Override
        public void checkOutcome(CuriosityPort.Outcome outcome, String joinedId) {
            outcomes.add(outcome + (joinedId == null ? "" : " " + joinedId));
            log.add(new Event(now, "outcome " + outcome));
        }

        @Override
        public void meetingOver() {
            meetingOvers++;
            log.add(new Event(now, "meeting over"));
        }

        @Override
        public void earsOpen() {
            earsOpens++;
            earsListening = earsPresent && now >= earsOpenRefusedUntil;
            if (earsListening && earsHoldMs >= 0) {
                at(now + earsHoldMs, () -> earsListening = false);
            }
            log.add(new Event(now, "ears open"));
        }

        @Override
        public void earsClose() {
            earsCloses++;
            earsListening = false;
            log.add(new Event(now, "ears close"));
        }

        @Override
        public void clipWindow(long ms) {
            clipWindows.add(ms);
            clipUntil = now + ms + tuning.deafTailMs;
            log.add(new Event(now, "clip " + ms));
        }
        /** Every shove or bump stamp the brain forwarded to the session (meeting plan U7, KTD5). */
        final List<Long> shovedStamps = new ArrayList<Long>();
        @Override
        public void earsShoved(long atMs) {
            shovedStamps.add(atMs);
            log.add(new Event(now, "shoved"));
        }
        // ---- the gauges (meeting plan U7, KTD14): counters and stage stamps as the state page would show them ----
        final java.util.EnumMap<ExploreBrain.Gauges.Counter, Integer> counters =
                new java.util.EnumMap<ExploreBrain.Gauges.Counter, Integer>(ExploreBrain.Gauges.Counter.class);
        final java.util.EnumMap<ExploreBrain.Gauges.Stage, Long> stamps =
                new java.util.EnumMap<ExploreBrain.Gauges.Stage, Long>(ExploreBrain.Gauges.Stage.class);
        @Override
        public void count(ExploreBrain.Gauges.Counter counter) {
            Integer n = counters.get(counter);
            counters.put(counter, n == null ? 1 : n + 1);
        }
        @Override
        public void stamp(ExploreBrain.Gauges.Stage stage, long atMs) {
            stamps.put(stage, atMs);
        }
        int counted(ExploreBrain.Gauges.Counter c) {
            Integer n = counters.get(c);
            return n == null ? 0 : n;
        }
        long stamped(ExploreBrain.Gauges.Stage st) {
            Long t = stamps.get(st);
            return t == null ? -1 : t;
        }

        // ---- log queries ----

        int count(String what) {
            int n = 0;
            for (Event e : log) {
                if (e.what.equals(what)) {
                    n++;
                }
            }
            return n;
        }

        int countPrefix(String prefix, long from, long to) {
            int n = 0;
            for (Event e : log) {
                if (e.what.startsWith(prefix) && e.t >= from && e.t < to) {
                    n++;
                }
            }
            return n;
        }

        /** Hops, turns and back-offs issued in [from, to). */
        int motions(long from, long to) {
            return countPrefix("hop", from, to) + countPrefix("turn", from, to) + countPrefix("back", from, to);
        }

        /** Index of the first event at or after `from` whose text starts with prefix, else -1. */
        int first(String prefix, int from) {
            for (int i = Math.max(0, from); i < log.size(); i++) {
                if (log.get(i).what.startsWith(prefix)) {
                    return i;
                }
            }
            return -1;
        }

        int firstAfter(String prefix, long t) {
            for (int i = 0; i < log.size(); i++) {
                if (log.get(i).t >= t && log.get(i).what.startsWith(prefix)) {
                    return i;
                }
            }
            return -1;
        }

        /** Index of the first hop, turn or back-off at or after t, else -1. */
        int firstMotionAfter(long t) {
            for (int i = 0; i < log.size(); i++) {
                String w = log.get(i).what;
                if (log.get(i).t >= t && (w.equals("hop") || w.startsWith("turn") || w.equals("back"))) {
                    return i;
                }
            }
            return -1;
        }

        long timeOf(int i) {
            return i < 0 ? -1 : log.get(i).t;
        }

        String what(int i) {
            return i < 0 ? "none" : log.get(i).what;
        }

        /** His true heading (yaw frame, left positive) at t, from the last 20 s; the current one if t is older or later. */
        double yawAt(long t) {
            double at = yaw.wrapped();
            for (java.util.Iterator<double[]> it = yawLog.descendingIterator(); it.hasNext(); ) {
                double[] e = it.next();
                at = e[1];
                if ((long) e[0] <= t) {
                    break;
                }
            }
            return at;
        }

        String tail() {
            int from = Math.max(0, log.size() - 14);
            return "log=" + log.subList(from, log.size()) + " violations=" + violations;
        }
    }

    /**
     * The camera rule (explore nav plan U4, KTD2), independent of the brain's own:
     * null when it holds, else what broke. Never open while he meets, asks or talks,
     * at rest, without the lease or sensors (EYES_ONLY), after shutdown, without a
     * camera, or in a failure's back-off; always open in a curiosity stop's looking
     * states; open in every roaming and escaping state while healthy (continuous),
     * or, look-then-go (KTD7), never while he moves or anywhere but a PAUSE.
     */
    static String cameraRuleBreak(ExploreBrain.State s, boolean open, boolean available,
                                  ExploreTuning.Navigation nav, boolean backedOff, boolean moving) {
        return cameraRuleBreak(s, open, available, nav, false, backedOff, moving);
    }

    /** With the ears listening, MEET keeps the camera open (parked) for the conversation it becomes (KTD7). */
    static String cameraRuleBreak(ExploreBrain.State s, boolean open, boolean available,
                                  ExploreTuning.Navigation nav, boolean earsListening, boolean backedOff,
                                  boolean moving) {
        if (s == ExploreBrain.State.MEET && earsListening) {
            return null;
        }
        switch (s) {
            case MEET: case SPEAK: case ASK_NAME: case LISTEN: case NAME: case REMEMBER: case NAME_CLIP:
            case ASK: case ORIENT: case EYES_ONLY: case CORNERED: case STOPPED:
            // Waiting out a motor cutout (robot 2026-10-01): still and resting, like CORNERED.
            case RECOVER:
                return open ? "camera open in " + s : null;
            case CHAT_THINK: case CHAT_SPEAK: case CHAT_LISTEN: case CHAT_NOTES:
                // Open with the detector parked (KTD7), unless the lease was lost mid-conversation.
                return null;
            case DOCKED:
                // Docked (owner 2026-10-02): open with the detector parked between looks, never toggled per look.
                if (open && !available) {
                    return "camera open without a camera in " + s;
                }
                return open && backedOff ? "camera open during its back-off in " + s : null;
            default:
                break;
        }
        if (open && !available) {
            return "camera open without a camera in " + s;
        }
        if (open && backedOff) {
            return "camera open during its back-off in " + s;
        }
        if (s.curious() || s.cueSearch()) {
            return open ? null : "camera closed in " + s;
        }
        if (nav == ExploreTuning.Navigation.LOOK_THEN_GO) {
            if (open && (moving || s != ExploreBrain.State.PAUSE)) {
                return "camera open while " + (moving ? "moving" : "roaming") + " in look-then-go in " + s;
            }
            return null;
        }
        if (!open && available && !backedOff) {
            return "camera closed while roaming in " + s;
        }
        return null;
    }

    /** What the rig calls clear, independent of the classifier under test. */
    static boolean isClear(SensorReading r) {
        return r.tof >= 100 && r.ir1 <= 500 && r.ir2 <= 500 && (r.cpl == null || r.cpl != 2);
    }

    /** Feeds classifier readings every 100 ms from `from` to `to` inclusive. */
    private static void feedClassifier(HazardClassifier c, Feed f, long from, long to) {
        for (long t = from; t <= to; t += 100) {
            SensorReading r = f.at(t);
            if (r != null) {
                c.offer(r);
            }
        }
    }

    private static final Feed CLEAR = new Feed() {
        public SensorReading at(long t) {
            return clear(t);
        }
    };

    // ---- on-device matching in Explore (face plan U6; KTD7, KTD11, R11, R18) ----

    /** The degraded ladder's lines (KTD7): the named greeting with {name}, and the stranger's two lines. */
    private static final CuriosityPort.MatchAnswer LINES = new CuriosityPort.MatchAnswer(
            CuriosityPort.MatchAnswer.Status.NEW, null, "Great to see you, {name}!", null, "Hello! What's your name?",
            "No worries, shy friend!");

    private static String localGreeting(String name) {
        return "say " + ChatSession.LOCAL_GREETING.replace("{name}", name);
    }

    private static void faceMatchScenarios() {
        scenario("face_confident_with_a_conversation_enters_chat_known_without_a_lines_request", n -> {
            Rig rig = chatRig(personAt(bearingOf(-90f), 25), true);
            rig.people.match = (r, k) -> CuriosityPort.MatchAnswer.known("Sarah")
                    .withMatch(FaceMatcher.Band.CONFIDENT, SARAH_ID, 0.71f, 11L)
                    .withConversation(r.people.persona, SARAH_ID, r.people.notes.get(SARAH_ID),
                            r.people.asked.get(SARAH_ID));
            rig.started();
            long open = openChat(rig);
            long say = runUntilEvent(rig, "say Line 1.", open, open + 20000);
            TurnAsk first = rig.turnAsks.isEmpty() ? null : rig.turnAsks.get(0);
            check(n, open > 0 && say > 0 && first != null && "Sarah".equals(first.request.name)
                            && SARAH_NOTES.equals(first.request.notes) && rig.count("lines") == 0
                            && rig.touches == 1 && rig.violations.isEmpty(),
                    "open@" + open + " say@" + say + " first=" + first + " " + rig.tail());
        });
        scenario("face_rejected_crop_takes_the_faceless_path_and_keeps_nothing", n -> {
            // Degraded ladder: the lines come from lines(), the name is welcomed, never remembered.
            Rig rig = meetRig();
            rig.people.match = (r, k) -> CuriosityPort.MatchAnswer.faceless().withMatch(null, null, Float.NaN, 5L);
            rig.people.lines = LINES;
            rig.people.listen = ListenScript.always(hearWords("my name is Sam"));
            rig.people.welcomed = CuriosityPort.Answer.line("So nice to meet you, Sam!");
            rig.started();
            rig.runUntil(18000);
            int match = rig.first("match NEW", 0);
            int lines = rig.first("lines", match);
            int ask = rig.first("say Hello! What's your name?", lines);
            int welcome = rig.first("welcome Sam", ask);
            // The conversation path: a name given on turn 1 keeps nothing either.
            Rig chat = chatRig(personAt(bearingOf(-90f), 25), false);
            chat.people.match = (r, k) -> CuriosityPort.MatchAnswer.faceless().withMatch(null, null, Float.NaN, 6L)
                    .withConversation(r.people.persona, null, null, null);
            chat.turns = turnsOf(CuriosityPort.Turn.line("Hi Sam!", null, "Sam", false, false, null));
            chat.people.listen = ListenScript.turns(hearWords("bye"));
            chat.started();
            long open = openChat(chat);
            long over = chatOver(chat, open);
            check(n, match >= 0 && lines > match && rig.timeOf(lines) == rig.timeOf(match) && ask > lines
                            && welcome > ask && rig.stored.isEmpty() && rig.countPrefix("remember", 0, 18001) == 0
                            && rig.violations.isEmpty()
                            && open > 0 && over > 0 && chat.kept.isEmpty() && chat.count("keep") == 0
                            && chat.violations.isEmpty(),
                    "lines=" + lines + " ask=" + ask + " welcome=" + welcome + " " + rig.tail() + " chat: "
                            + chat.tail());
        });
        scenario("face_not_ready_is_faceless_and_a_later_name_is_not_stored", n -> {
            Rig rig = meetRig();
            rig.people.match = (r, k) -> CuriosityPort.MatchAnswer.faceless()
                    .withMatch(FaceMatcher.Band.NOT_READY, null, Float.NaN, 7L);
            rig.people.lines = LINES;
            rig.people.listen = ListenScript.always(hearWords("i'm Priya"));
            rig.started();
            rig.runUntil(18000);
            Rig chat = chatRig(personAt(bearingOf(-90f), 25), false);
            chat.people.match = (r, k) -> CuriosityPort.MatchAnswer.faceless()
                    .withMatch(FaceMatcher.Band.NOT_READY, null, Float.NaN, 8L)
                    .withConversation(r.people.persona, null, null, null);
            chat.people.listen = ListenScript.turns(hearWords("I'm Priya"), hearWords("bye"));
            chat.turns = turnsOf(turnLine(1), CuriosityPort.Turn.line("Nice, Priya!", null, "Priya", false, false,
                    null));
            chat.started();
            long open = openChat(chat);
            long over = chatOver(chat, open);
            check(n, rig.count("welcome Priya") == 1 && rig.stored.isEmpty() && rig.violations.isEmpty()
                            && open > 0 && over > 0 && chat.turnAsks.size() >= 2 && chat.kept.isEmpty()
                            && chat.violations.isEmpty(),
                    rig.tail() + " chat: " + chat.tail());
        });
        scenario("face_close_or_weak_band_meets_a_stranger_who_is_stored_under_their_name", n -> {
            Rig rig = meetRig();
            rig.people.match = (r, k) -> CuriosityPort.MatchAnswer.stranger()
                    .withMatch(FaceMatcher.Band.WEAK, "p-other", 0.21f, 9L);
            rig.people.lines = LINES;
            rig.people.listen = ListenScript.always(hearWords("my name is Priya"));
            rig.started();
            rig.runUntil(18000);
            int lines = rig.first("lines", 0);
            int ask = rig.first("say Hello! What's your name?", lines);
            check(n, lines >= 0 && ask > lines && rig.stored.equals(java.util.Arrays.asList("Priya"))
                            && rig.touches == 0 && rig.violations.isEmpty(),
                    rig.tail());
        });
        scenario("face_degraded_known_greeting_comes_from_the_lines_request", n -> {
            Rig rig = meetRig();
            rig.people.match = (r, k) -> CuriosityPort.MatchAnswer.known("Sarah")
                    .withMatch(FaceMatcher.Band.CONFIDENT, SARAH_ID, 0.7f, 2L);
            rig.people.lines = LINES;
            rig.started();
            rig.runUntil(14000);
            int match = rig.first("match KNOWN", 0);
            int lines = rig.first("lines", match);
            int say = rig.first("say ", lines);
            check(n, match >= 0 && lines > match && rig.what(say).equals("say Great to see you, Sarah!")
                            && rig.touches == 1 && rig.count("listen") == 0 && rig.stored.isEmpty()
                            && rig.violations.isEmpty(),
                    "say=" + rig.what(say) + " " + rig.tail());
        });
        scenario("face_degraded_known_with_failing_lines_plays_the_local_greeting", n -> {
            Rig rig = meetRig();
            rig.people.match = (r, k) -> CuriosityPort.MatchAnswer.known("Sarah")
                    .withMatch(FaceMatcher.Band.CONFIDENT, SARAH_ID, 0.7f, 2L);
            rig.people.lines = CuriosityPort.MatchAnswer.FAILED;
            rig.started();
            rig.runUntil(14000);
            int failed = rig.first("lines FAILED", 0);
            int say = rig.first("say ", failed);
            check(n, failed >= 0 && rig.what(say).equals(localGreeting("Sarah"))
                            && rig.timeOf(say) >= rig.timeOf(failed) && rig.count("name person") == 0
                            && rig.touches == 1 && rig.count("listen") == 0 && rig.violations.isEmpty(),
                    "say=" + rig.what(say) + " " + rig.tail());
        });
        scenario("face_chat_known_turn_one_failure_plays_the_local_greeting_before_the_sign_off", n -> {
            Rig rig = sarahRig(true);
            rig.turns = turnsOf(CuriosityPort.Turn.failed());
            long open = openChat(rig);
            long over = chatOver(rig, open);
            int greet = rig.firstAfter(localGreeting("Sarah"), open);
            int signOff = rig.firstAfter("react sign-off", open);
            // A stranger's turn-1 failure has no name to greet: the sign-off alone, as before.
            Rig stranger = sarahRig(false);
            stranger.turns = turnsOf(CuriosityPort.Turn.failed());
            long open2 = openChat(stranger);
            long over2 = chatOver(stranger, open2);
            check(n, open > 0 && over > 0 && greet >= 0 && signOff > greet && rig.count(localGreeting("Sarah")) == 1
                            && rig.violations.isEmpty()
                            && open2 > 0 && over2 > 0 && stranger.countPrefix("say", open2, over2 + 1) == 0
                            && stranger.count("react sign-off") == 1 && stranger.violations.isEmpty(),
                    "greet=" + greet + " signOff=" + signOff + " " + rig.tail() + " stranger: " + stranger.tail());
        });
        scenario("face_chat_known_turn_one_unreachable_twice_also_greets_before_the_sign_off", n -> {
            Rig rig = sarahRig(true);
            rig.turns = turnsOf(CuriosityPort.Turn.unreachable(), CuriosityPort.Turn.unreachable());
            long open = openChat(rig);
            long over = chatOver(rig, open);
            int greet = rig.firstAfter(localGreeting("Sarah"), open);
            int signOff = rig.firstAfter("react sign-off", open);
            check(n, open > 0 && over > 0 && rig.turnAsks.size() == 2 && greet >= 0 && signOff > greet
                            && rig.violations.isEmpty(),
                    "greet=" + greet + " signOff=" + signOff + " " + rig.tail());
        });
        scenario("face_first_roam_waits_for_migration_or_30_s", n -> {
            Rig waits = meetRig();
            waits.migratedAt = 5000;
            waits.started();
            waits.runUntil(8000);
            long pause = entered(waits, ExploreBrain.State.PAUSE, 0);
            Rig capped = meetRig();
            capped.migratedAt = Long.MAX_VALUE;
            capped.started();
            capped.runUntil(33000);
            long pause2 = entered(capped, ExploreBrain.State.PAUSE, 0);
            Rig done = meetRig();
            done.started();
            done.runUntil(1000);
            long pause3 = entered(done, ExploreBrain.State.PAUSE, 0);
            check(n, pause >= 5000 && pause <= 5200 && waits.motions(0, 5000) == 0
                            && waits.countPrefix("camera open", 0, 5000) == 0
                            && pause2 >= 30000 && pause2 <= 30200 && capped.motions(0, 30000) == 0
                            && pause3 >= 0 && pause3 <= 400
                            && waits.violations.isEmpty() && capped.violations.isEmpty(),
                    "pause@" + pause + " capped@" + pause2 + " done@" + pause3);
        });
        scenario("face_work_is_allowed_only_while_the_detector_is_quiet_or_parked", n -> {
            // The rig flags any allow while the detector could run, and any camera open or
            // unpark while allowed; the brain must also actually allow it at a stop.
            Rig roam = meetRig();
            roam.people.match = (r, k) -> CuriosityPort.MatchAnswer.known("Sarah")
                    .withMatch(FaceMatcher.Band.CONFIDENT, SARAH_ID, 0.7f, 2L);
            roam.people.lines = LINES;
            roam.started();
            roam.runUntil(20000);
            long firstOn = -1;
            long offBeforeOpen = -1;
            long onInMeet = -1;
            for (Event e : roam.faceWorkLog) {
                if (firstOn < 0 && e.what.equals("face work on")) {
                    firstOn = e.t;
                }
                if (offBeforeOpen < 0 && e.what.equals("face work off")) {
                    offBeforeOpen = e.t;
                }
                if (e.what.equals("face work on") && e.t >= 5000) {
                    onInMeet = onInMeet < 0 ? e.t : onInMeet;
                }
            }
            Rig chat = sarahRig(true);
            long open = openChat(chat);
            long over = chatOver(chat, open);
            boolean onInChat = false;
            for (Event e : chat.faceWorkLog) {
                onInChat |= e.what.equals("face work on") && e.t >= open && e.t <= over;
            }
            check(n, firstOn >= 0 && offBeforeOpen >= 0 && offBeforeOpen <= roam.timeOf(roam.first("camera open", 0))
                            && onInMeet >= 0 && roam.violations.isEmpty()
                            && open > 0 && onInChat && chat.violations.isEmpty(),
                    "on@" + firstOn + " off@" + offBeforeOpen + " meet@" + onInMeet + " log=" + roam.faceWorkLog
                            + " " + roam.tail() + " chat: " + chat.faceWorkLog + " " + chat.tail());
        });
    }

    // ---- confirming a close match and resolving names (face plan U7; KTD6, KTD9, KTD10, KTD12) ----

    private static final String BEN_ID = "p-ben";
    private static final String BEN2_ID = "p-ben2";
    private static final String BEN_NOTES = "{\"topics\":[\"cycling\"]}";
    /** This meeting's face in the fake resolver's 2-D space: each stored person sits at its scripted score. */
    private static final float[] PROBE = {1f, 0f};

    /** The fake store's idsNamed (KTD10): by full name when two words are given, else by first word. */
    static List<String> idsNamed(People p, String name) {
        List<String> out = new ArrayList<String>();
        if (name == null || name.trim().isEmpty()) {
            return out;
        }
        String n = name.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
        boolean full = n.contains(" ");
        for (java.util.Map.Entry<String, String> e : p.named.entrySet()) {
            String s = e.getValue() == null ? "" : e.getValue().trim().toLowerCase(java.util.Locale.ROOT)
                    .replaceAll("\\s+", " ");
            if (!s.isEmpty() && (full ? s : s.split(" ")[0]).equals(n)) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    /** One unit embedding per candidate whose score against PROBE is the scripted face score (0.1: weak). */
    static List<FaceMatcher.Entry> galleryOf(People p, List<String> ids) {
        List<FaceMatcher.Entry> out = new ArrayList<FaceMatcher.Entry>();
        for (String id : ids) {
            float s = p.faceScore.containsKey(id) ? p.faceScore.get(id) : 0.1f;
            out.add(new FaceMatcher.Entry(id, 0, new float[] {s, (float) Math.sqrt(Math.max(0f, 1f - s * s))}));
        }
        return out;
    }

    static CuriosityPort.Resolved resolvedOf(People p, NameResolver.Decision d) {
        switch (d.kind) {
            case JOIN:
                return CuriosityPort.Resolved.join(d.personId, p.named.get(d.personId));
            case ASK_LAST_NAME:
                return CuriosityPort.Resolved.askLastName(d.name);
            default:
                return CuriosityPort.Resolved.newPerson(d.name);
        }
    }

    private static final java.util.Set<String> FAKE_NOT_NAMES = new java.util.HashSet<String>(java.util.Arrays.asList(
            "me", "no", "nope", "nah", "not", "yes", "yeah", "yep", "maybe", "who's", "whos", "what", "why", "the",
            "bye", "ok", "okay", "sure", "thanks", "fine", "a", "b", "c", "d", "tell", "joke", "hi", "hello"));

    /** Like NameExtractor for the replies the scenarios use: "I'm X", "it's X", "X", "X Y". */
    static String fakeNameIn(String t) {
        if (t == null) {
            return null;
        }
        String s = t.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z' -]", " ").trim().replaceAll("\\s+", " ");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "^(?:(?:my name is|i'm|im|i am|call me|it's|its|this is) )?([a-z][a-z'-]*(?: [a-z][a-z'-]*)?)$")
                .matcher(s);
        if (!m.matches()) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (String w : m.group(1).split(" ")) {
            if (FAKE_NOT_NAMES.contains(w)) {
                return null;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return out.toString();
    }

    /** A close match on this stored person, with the question name the adapter would pick (KTD6). */
    static CuriosityPort.MatchAnswer closeMatch(Rig r, String id, float score, long handle) {
        String stored = r.people.named.get(id);
        String asked = NameResolver.askedName(stored, idsNamed(r.people, NameResolver.firstWord(stored)).size());
        CuriosityPort.MatchAnswer a = CuriosityPort.MatchAnswer.stranger()
                .withMatch(FaceMatcher.Band.CLOSE, id, score, handle).withConfirm(asked);
        return r.people.persona == null ? a : a.withConversation(r.people.persona, null, null, null);
    }

    /** Ben Wilson is stored with notes; this face scores 0.42 against him (close). */
    private static void storeBen(Rig rig) {
        rig.people.named.put(BEN_ID, "Ben Wilson");
        rig.people.notes.put(BEN_ID, BEN_NOTES);
        rig.people.asked.put(BEN_ID, new ArrayList<String>());
        rig.people.faceScore.put(BEN_ID, 0.42f);
    }

    /** Ben Smith is stored beside Ben Wilson, and this face is weak for both: the question uses the full name. */
    private static void twoWeakBens(Rig rig) {
        rig.people.named.put(BEN2_ID, "Ben Smith");
        rig.people.faceScore.put(BEN_ID, 0.1f);
        rig.people.faceScore.put(BEN2_ID, 0.1f);
        rig.people.match = (r, k) -> closeMatch(r, BEN_ID, 0.4f, 32L);
    }

    /** Sarah is stored; this face scores the given value against her. */
    private static void storeSarah(Rig rig, float score) {
        rig.people.named.put(SARAH_ID, "Sarah");
        rig.people.faceScore.put(SARAH_ID, score);
    }

    /** The conversation path with a close match on Ben Wilson, each listen hearing the next reply. */
    private static Rig closeChatRig(Hearing... listens) {
        Rig rig = chatRig(personAt(bearingOf(-90f), 25), false);
        storeBen(rig);
        rig.people.match = (r, k) -> closeMatch(r, BEN_ID, 0.42f, 21L);
        rig.people.listen = ListenScript.turns(listens);
        return rig;
    }

    /** The degraded ladder (no conversation possible) with a close match on Ben Wilson. */
    private static Rig closeLadderRig(Hearing... listens) {
        Rig rig = meetRig();
        storeBen(rig);
        rig.people.match = (r, k) -> closeMatch(r, BEN_ID, 0.42f, 31L);
        rig.people.lines = LINES;
        rig.people.listen = ListenScript.turns(listens);
        return rig;
    }

    /** A stranger conversation (weak match) whose turns are scripted. */
    private static Rig strangerChatRig(TurnScript turns, Hearing... listens) {
        Rig rig = chatRig(personAt(bearingOf(-90f), 25), false);
        rig.people.match = (r, k) -> CuriosityPort.MatchAnswer.stranger()
                .withMatch(FaceMatcher.Band.WEAK, null, 0.1f, 41L).withConversation(r.people.persona, null, null, null);
        rig.turns = turns;
        rig.people.listen = ListenScript.turns(listens);
        return rig;
    }

    private static CuriosityPort.Turn named(int nth, String nameGiven) {
        return CuriosityPort.Turn.line("Line " + nth + ".", null, nameGiven, false, false,
                "{\"topics\":[\"t" + nth + "\"]}");
    }

    private static boolean allStartWith(List<String> deltas, String prefix) {
        for (String d : deltas) {
            if (!d.startsWith(prefix)) {
                return false;
            }
        }
        return true;
    }

    private static final String ASK_BEN = "say Is that you, Ben?";

    private static void faceConfirmScenarios() {
        scenario("confirm_chat_ae2_yes_adds_the_photo_and_starts_known_with_their_notes", n -> {
            Rig rig = closeChatRig(hearWords("yeah that's me"), hearWords("bye")).started();
            long open = openChat(rig);
            long over = chatOver(rig, open);
            int ask = rig.first(ASK_BEN, 0);
            int photo = rig.first("add photo " + BEN_ID, ask);
            TurnAsk first = rig.turnAsks.isEmpty() ? null : rig.turnAsks.get(0);
            check(n, ask >= 0 && photo > ask && open >= rig.timeOf(photo) && over > 0 && first != null
                            && "Ben Wilson".equals(first.request.name) && BEN_NOTES.equals(first.request.notes)
                            && rig.outcomes.equals(java.util.Arrays.asList("YES " + BEN_ID)) && rig.kept.isEmpty()
                            && allStartWith(rig.notesDeltas, BEN_ID + ": ") && !rig.notesDeltas.isEmpty()
                            && rig.violations.isEmpty(),
                    "ask=" + ask + " photo=" + photo + " open@" + open + " first=" + first + " outcomes="
                            + rig.outcomes + " " + rig.tail());
        });
        scenario("confirm_chat_ae3_no_im_sarah_close_to_sarah_joins_her_and_starts_known_as_sarah", n -> {
            Rig rig = closeChatRig(hearWords("No, I'm Sarah"), hearWords("bye"));
            storeSarah(rig, 0.45f);
            rig.people.notes.put(SARAH_ID, SARAH_NOTES);
            rig.started();
            long open = openChat(rig);
            long over = chatOver(rig, open);
            TurnAsk first = rig.turnAsks.isEmpty() ? null : rig.turnAsks.get(0);
            check(n, open > 0 && over > 0 && rig.resolves.equals(java.util.Arrays.asList("Sarah"))
                            && rig.photos.equals(java.util.Arrays.asList(SARAH_ID)) && first != null
                            && "Sarah".equals(first.request.name) && SARAH_NOTES.equals(first.request.notes)
                            && rig.outcomes.equals(java.util.Arrays.asList("JOINED " + SARAH_ID)) && rig.kept.isEmpty()
                            && rig.violations.isEmpty(),
                    "resolves=" + rig.resolves + " photos=" + rig.photos + " first=" + first + " outcomes="
                            + rig.outcomes + " " + rig.tail());
        });
        scenario("confirm_chat_ae7_silence_starts_the_stranger_opener_with_no_photo_and_outcome_no_reply", n -> {
            Rig rig = closeChatRig(hearSilence(), hearWords("bye")).started();
            long open = openChat(rig);
            long over = chatOver(rig, open);
            TurnAsk first = rig.turnAsks.isEmpty() ? null : rig.turnAsks.get(0);
            check(n, rig.first(ASK_BEN, 0) >= 0 && open > 0 && over > 0 && first != null && first.request.name == null
                            && first.request.heard == null && rig.photos.isEmpty() && rig.kept.isEmpty()
                            && rig.outcomes.equals(java.util.Arrays.asList("NO_REPLY")) && rig.violations.isEmpty(),
                    "first=" + first + " outcomes=" + rig.outcomes + " " + rig.tail());
        });
        scenario("confirm_chat_ae8_near_tie_asks_the_full_name_and_a_bare_first_name_is_a_no", n -> {
            Rig rig = closeChatRig(hearWords("Ben"), hearWords("bye"));
            rig.people.named.put(BEN2_ID, "Ben Smith");
            rig.people.faceScore.put(BEN2_ID, 0.6f);
            rig.people.faceScore.put(BEN_ID, 0.62f);
            rig.people.match = (r, k) -> closeMatch(r, BEN_ID, 0.62f, 22L);
            rig.started();
            long open = openChat(rig);
            long over = chatOver(rig, open);
            TurnAsk first = rig.turnAsks.isEmpty() ? null : rig.turnAsks.get(0);
            check(n, rig.count("say Is that you, Ben Wilson?") == 1 && rig.count(ASK_BEN) == 0 && open > 0 && over > 0
                            && first != null && first.request.name == null && rig.photos.isEmpty()
                            && rig.outcomes.equals(java.util.Arrays.asList("NO")) && rig.violations.isEmpty(),
                    "first=" + first + " outcomes=" + rig.outcomes + " " + rig.tail());
        });
        scenario("confirm_chat_no_im_priya_unstored_stores_priya_and_starts_known_without_asking_again", n -> {
            Rig rig = closeChatRig(hearWords("no I'm Priya"), hearWords("fine"), hearWords("bye"));
            rig.turns = turnsOf(turnLine(1), named(2, "Priya"));
            rig.started();
            long open = openChat(rig);
            long over = chatOver(rig, open);
            TurnAsk first = rig.turnAsks.isEmpty() ? null : rig.turnAsks.get(0);
            check(n, open > 0 && over > 0 && rig.kept.equals(java.util.Arrays.asList("Priya")) && first != null
                            && "Priya".equals(first.request.name) && first.request.notes == null
                            && rig.resolves.equals(java.util.Arrays.asList("Priya")) && rig.photos.isEmpty()
                            && rig.timeOf(rig.first("keep", 0)) <= open && allStartWith(rig.notesDeltas, "kept-1: ")
                            && rig.notesDeltas.size() == 2 && rig.violations.isEmpty(),
                    "kept=" + rig.kept + " first=" + first + " deltas=" + rig.notesDeltas + " " + rig.tail());
        });
        scenario("confirm_chat_yes_with_the_photo_refused_starts_as_a_stranger_and_recreates_nobody", n -> {
            Rig rig = closeChatRig(hearWords("yes"), hearWords("bye"));
            rig.people.refusePhoto.add(BEN_ID);
            rig.started();
            long open = openChat(rig);
            long over = chatOver(rig, open);
            TurnAsk first = rig.turnAsks.isEmpty() ? null : rig.turnAsks.get(0);
            check(n, open > 0 && over > 0 && rig.photos.equals(java.util.Arrays.asList(BEN_ID)) && first != null
                            && first.request.name == null && rig.kept.isEmpty() && rig.notesDeltas.isEmpty()
                            && rig.violations.isEmpty(),
                    "first=" + first + " " + rig.tail());
        });
        scenario("confirm_meeting_ending_mid_confirm_closes_the_check_without_an_answer", n -> {
            Rig rig = closeLadderRig(hearWords("yes").after(5000)).started();
            long confirm = runUntilState(rig, ExploreBrain.State.CONFIRM, 0, 30000);
            long listen = runUntilEvent(rig, "listen", confirm, confirm + 10000);
            rig.brain.onLeaseChanged(false);
            rig.runUntil(rig.now + 8000);
            check(n, confirm > 0 && listen > 0 && rig.meetingOvers >= 1 && rig.outcomes.isEmpty() && rig.photos.isEmpty()
                            && !rig.brain.state().confirms() && rig.violations.isEmpty(),
                    "confirm@" + confirm + " overs=" + rig.meetingOvers + " outcomes=" + rig.outcomes + " "
                            + rig.tail());
        });
        scenario("confirm_ladder_answer_started_in_time_is_heard_past_the_listen_deadline", n -> {
            Rig rig = closeLadderRig(hearWords("yes").after(12000).answeringAfter(3000));
            List<String> notes = traced(rig);
            rig.started();
            rig.runUntil(45000);
            check(n, rig.outcomes.equals(java.util.Arrays.asList("YES " + BEN_ID))
                            && noteAt(notes, "an answer has started") >= 0
                            && noteAt(notes, "no answer to the question") < 0 && rig.violations.isEmpty(),
                    "outcomes=" + rig.outcomes + " notes=" + notes + " " + rig.tail());
        });
        scenario("confirm_ladder_runs_the_confirm_and_last_name_branches_without_a_conversation", n -> {
            // Yes: the photo, then the degraded greeting from the lines request with the stored name.
            Rig yes = closeLadderRig(hearWords("yes")).started();
            yes.runUntil(25000);
            int ask = yes.first(ASK_BEN, 0);
            int photo = yes.first("add photo " + BEN_ID, ask);
            int lines = yes.first("lines", photo);
            int greet = yes.first("say Great to see you, Ben Wilson!", lines);
            // Two Bens: "No, I'm Ben" is weak for both, so the last name; a new full name goes through remember().
            Rig last = closeLadderRig(hearWords("no, I'm Ben"), hearWords("Jones"));
            twoWeakBens(last);
            last.started();
            last.runUntil(30000);
            int ask2 = last.first("say Is that you, Ben Wilson?", 0);
            int lastQ = last.first("say " + ChatSession.LAST_NAME_QUESTION, ask2);
            // A weak stranger's name on the ladder goes through the resolver too.
            Rig weak = meetRig();
            storeBen(weak);
            weak.people.faceScore.put(BEN_ID, 0.1f);
            weak.people.match = (r, k) -> CuriosityPort.MatchAnswer.stranger()
                    .withMatch(FaceMatcher.Band.WEAK, BEN_ID, 0.1f, 33L);
            weak.people.lines = LINES;
            weak.people.listen = ListenScript.turns(hearWords("I'm Ben"), hearWords("Wilson"));
            weak.started();
            weak.runUntil(30000);
            int lastQ3 = weak.first("say " + ChatSession.LAST_NAME_QUESTION, 0);
            check(n, ask >= 0 && photo > ask && lines > photo && greet > lines
                            && yes.outcomes.equals(java.util.Arrays.asList("YES " + BEN_ID)) && yes.stored.isEmpty()
                            && yes.turnAsks.isEmpty() && yes.violations.isEmpty()
                            && ask2 >= 0 && lastQ > ask2 && last.stored.equals(java.util.Arrays.asList("Ben Jones"))
                            && last.photos.isEmpty() && last.turnAsks.isEmpty() && last.violations.isEmpty()
                            && weak.first(ASK_BEN, 0) < 0 && lastQ3 >= 0 && weak.photos.equals(java.util.Arrays.asList(BEN_ID))
                            && weak.stored.isEmpty() && weak.outcomes.equals(java.util.Arrays.asList("JOINED " + BEN_ID))
                            && weak.violations.isEmpty(),
                    "yes: " + yes.tail() + " last: stored=" + last.stored + " " + last.tail() + " weak: photos="
                            + weak.photos + " outcomes=" + weak.outcomes + " " + weak.tail());
        });
        scenario("confirm_ladder_last_name_unanswered_welcomes_them_and_stores_nobody", n -> {
            Rig rig = closeLadderRig(hearWords("no I'm Ben"), hearSilence());
            twoWeakBens(rig);
            rig.started();
            rig.runUntil(30000);
            int lastQ = rig.first("say " + ChatSession.LAST_NAME_QUESTION, 0);
            check(n, lastQ >= 0 && rig.stored.isEmpty() && rig.photos.isEmpty() && rig.first("welcome", lastQ) > lastQ
                            && rig.outcomes.equals(java.util.Arrays.asList("NAME_GIVEN")) && rig.violations.isEmpty(),
                    "outcomes=" + rig.outcomes + " " + rig.tail());
        });
        scenario("resolve_chat_ae4_weak_ben_asks_the_last_name_smith_stores_ben_smith_and_wilson_joins_ben_wilson", n -> {
            Rig smith = strangerChatRig(turnsOf(turnLine(1), named(2, "Ben")), hearWords("I'm Ben"),
                    hearWords("Smith"), hearWords("bye"));
            storeBen(smith);
            smith.people.faceScore.put(BEN_ID, 0.1f);
            smith.started();
            long open = openChat(smith);
            long over = chatOver(smith, open);
            Rig wilson = strangerChatRig(turnsOf(turnLine(1), named(2, "Ben")), hearWords("I'm Ben"),
                    hearWords("Wilson"), hearWords("bye"));
            storeBen(wilson);
            wilson.people.faceScore.put(BEN_ID, 0.1f);
            wilson.started();
            long open2 = openChat(wilson);
            long over2 = chatOver(wilson, open2);
            check(n, open > 0 && over > 0 && smith.count("say " + ChatSession.LAST_NAME_QUESTION) == 1
                            && smith.count("say Line 2.") == 0 && smith.kept.equals(java.util.Arrays.asList("Ben Smith"))
                            && smith.photos.isEmpty() && allStartWith(smith.notesDeltas, "kept-1: ")
                            && smith.violations.isEmpty()
                            && open2 > 0 && over2 > 0 && wilson.kept.isEmpty()
                            && wilson.photos.equals(java.util.Arrays.asList(BEN_ID))
                            && wilson.outcomes.equals(java.util.Arrays.asList("JOINED " + BEN_ID))
                            && !wilson.notesDeltas.isEmpty() && allStartWith(wilson.notesDeltas, BEN_ID + ": ")
                            && wilson.violations.isEmpty(),
                    "smith: kept=" + smith.kept + " " + smith.tail() + " wilson: photos=" + wilson.photos + " deltas="
                            + wilson.notesDeltas + " " + wilson.tail());
        });
        scenario("resolve_chat_after_the_last_name_the_next_turn_carries_both_replies_and_an_equal_name_given_stores_nothing", n -> {
            Rig rig = strangerChatRig(turnsOf(turnLine(1), named(2, "Ben"), named(3, "Ben Smith"), named(4, "Ben")),
                    hearWords("I'm Ben"), hearWords("Smith"), hearWords("sure"), hearWords("bye"));
            storeBen(rig);
            rig.people.faceScore.put(BEN_ID, 0.1f);
            rig.started();
            long open = openChat(rig);
            long over = chatOver(rig, open);
            TurnAsk third = rig.turnAsks.size() >= 3 ? rig.turnAsks.get(2) : null;
            CuriosityPort.Exchange lastEx = third == null || third.request.transcript.isEmpty() ? null
                    : third.request.transcript.get(third.request.transcript.size() - 1);
            TurnAsk fourth = rig.turnAsks.size() >= 4 ? rig.turnAsks.get(3) : null;
            check(n, open > 0 && over > 0 && third != null && "Smith".equals(third.request.heard) && lastEx != null
                            && "I'm Ben".equals(lastEx.heard) && ChatSession.LAST_NAME_QUESTION.equals(lastEx.said)
                            && "Ben Smith".equals(third.request.name) && fourth != null
                            && "Ben Smith".equals(fourth.request.name)
                            && rig.kept.equals(java.util.Arrays.asList("Ben Smith"))
                            && rig.resolves.equals(java.util.Arrays.asList("Ben", "last Smith"))
                            && rig.violations.isEmpty(),
                    "third=" + (third == null ? null : third.request.heard) + " last=" + (lastEx == null ? null
                            : lastEx.heard + "/" + lastEx.said) + " kept=" + rig.kept + " resolves=" + rig.resolves
                            + " " + rig.tail());
        });
        scenario("resolve_chat_last_name_unanswered_stores_nobody_and_the_conversation_runs_unnamed", n -> {
            Rig rig = strangerChatRig(turnsOf(turnLine(1), named(2, "Ben")), hearWords("I'm Ben"), hearSilence(),
                    hearWords("tell me a joke"), hearWords("bye"));
            storeBen(rig);
            rig.people.faceScore.put(BEN_ID, 0.1f);
            rig.started();
            long open = openChat(rig);
            long over = chatOver(rig, open);
            TurnAsk third = rig.turnAsks.size() >= 3 ? rig.turnAsks.get(2) : null;
            check(n, open > 0 && over > 0 && rig.count("say " + ChatSession.LAST_NAME_QUESTION) == 1
                            && rig.kept.isEmpty() && rig.photos.isEmpty() && rig.notesDeltas.isEmpty() && third != null
                            && "tell me a joke".equals(third.request.heard) && third.request.name == null
                            && rig.outcomes.equals(java.util.Arrays.asList("NAME_GIVEN")) && rig.violations.isEmpty(),
                    "third=" + (third == null ? null : third.request.heard) + " outcomes=" + rig.outcomes + " "
                            + rig.tail());
        });
        scenario("resolve_chat_stored_ben_without_a_last_name_and_smith_stores_a_new_ben_smith", n -> {
            Rig rig = strangerChatRig(turnsOf(turnLine(1), named(2, "Ben")), hearWords("Ben"), hearWords("Smith"),
                    hearWords("bye"));
            rig.people.named.put("p-ben1", "Ben");
            rig.started();
            long open = openChat(rig);
            long over = chatOver(rig, open);
            check(n, open > 0 && over > 0 && rig.count("say " + ChatSession.LAST_NAME_QUESTION) == 1
                            && rig.kept.equals(java.util.Arrays.asList("Ben Smith")) && rig.photos.isEmpty()
                            && rig.violations.isEmpty(),
                    "kept=" + rig.kept + " " + rig.tail());
        });
        scenario("resolve_chat_ae9_name_on_turn_three_close_to_sarah_joins_her_and_moves_the_notes", n -> {
            Rig rig = strangerChatRig(turnsOf(turnLine(1), turnLine(2), named(3, "Sarah")), hearWords("hi"),
                    hearWords("fine"), hearWords("I'm Sarah"), hearWords("bye"));
            storeSarah(rig, 0.45f);
            rig.started();
            long open = openChat(rig);
            long over = chatOver(rig, open);
            TurnAsk fourth = rig.turnAsks.size() >= 4 ? rig.turnAsks.get(3) : null;
            check(n, open > 0 && over > 0 && rig.kept.isEmpty() && rig.photos.equals(java.util.Arrays.asList(SARAH_ID))
                            && rig.notesDeltas.size() == 4 && allStartWith(rig.notesDeltas, SARAH_ID + ": ")
                            && rig.outcomes.equals(java.util.Arrays.asList("JOINED " + SARAH_ID)) && fourth != null
                            && "Sarah".equals(fourth.request.name) && rig.count("say Line 3.") == 1
                            && rig.violations.isEmpty(),
                    "photos=" + rig.photos + " deltas=" + rig.notesDeltas + " outcomes=" + rig.outcomes + " "
                            + rig.tail());
        });
        scenario("resolve_chat_a_known_conversation_whose_name_given_is_close_to_another_stored_person_joins_them", n -> {
            // Replaces the meeting plan's KTD10 mismatch rule: a stored name close to the face joins, no new record.
            Rig rig = chatRig(personAt(bearingOf(-90f), 25), true);
            rig.people.named.put("p-priya", "Priya");
            rig.people.faceScore.put("p-priya", 0.4f);
            rig.turns = turnsOf(turnLine(1), named(2, "Priya"));
            rig.people.listen = ListenScript.turns(hearWords("i'm priya"), hearWords("bye"));
            rig.started();
            long open = openChat(rig);
            long over = chatOver(rig, open);
            check(n, open > 0 && over > 0 && rig.kept.isEmpty() && rig.photos.equals(java.util.Arrays.asList("p-priya"))
                            && rig.notesDeltas.size() == 2 && allStartWith(rig.notesDeltas, "p-priya: ")
                            && rig.countPrefix("notes " + SARAH_ID, 0, over) == 0 && rig.violations.isEmpty(),
                    "kept=" + rig.kept + " photos=" + rig.photos + " deltas=" + rig.notesDeltas + " " + rig.tail());
        });
        scenario("resolve_chat_a_join_whose_photo_is_refused_continues_and_recreates_nobody", n -> {
            Rig rig = strangerChatRig(turnsOf(turnLine(1), named(2, "Sarah")), hearWords("hi"), hearWords("I'm Sarah"),
                    hearWords("bye"));
            storeSarah(rig, 0.45f);
            rig.people.refusePhoto.add(SARAH_ID);
            rig.started();
            long open = openChat(rig);
            long over = chatOver(rig, open);
            check(n, open > 0 && over > 0 && rig.photos.equals(java.util.Arrays.asList(SARAH_ID)) && rig.kept.isEmpty()
                            && rig.notesDeltas.isEmpty() && rig.count("say Line 2.") == 1 && rig.turnAsks.size() == 3
                            && rig.violations.isEmpty(),
                    "photos=" + rig.photos + " kept=" + rig.kept + " " + rig.tail());
        });
    }

    // ---- the start-up migration's decisions (face plan U6, KTD11) ----

    /** A fake people store: its photos, their JPEGs by "id/slot", and every call made on it. */
    static final class FakeFaceStore implements FaceMigration.Store {
        final List<FaceMigration.Photo> photos = new ArrayList<FaceMigration.Photo>();
        final java.util.Map<String, byte[]> jpegs = new java.util.HashMap<String, byte[]>();
        final List<String> calls = new ArrayList<String>();
        boolean tooOld;

        FakeFaceStore photo(String id, int slot, boolean pending, String content) {
            photos.add(new FaceMigration.Photo(id, slot, 1000L + slot, pending));
            jpegs.put(id + "/" + slot, content.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            return this;
        }

        @Override
        public List<FaceMigration.Photo> gallery() throws java.io.IOException {
            calls.add("gallery");
            if (tooOld) {
                throw new java.io.IOException("launcher too old: install the current launcher");
            }
            return new ArrayList<FaceMigration.Photo>(photos);
        }

        @Override
        public byte[] photo(String id, int slot) {
            calls.add("photo " + id + "/" + slot);
            return jpegs.get(id + "/" + slot);
        }

        @Override
        public boolean setEmbedding(String id, int slot, long addedAtMillis, float[] embedding) {
            calls.add("embed " + id + "/" + slot + " " + embedding.length);
            return settle(id, slot);
        }

        @Override
        public boolean markUnusable(String id, int slot, long addedAtMillis) {
            calls.add("unusable " + id + "/" + slot);
            return settle(id, slot);
        }

        private boolean settle(String id, int slot) {
            for (int i = 0; i < photos.size(); i++) {
                FaceMigration.Photo p = photos.get(i);
                if (p.id.equals(id) && p.slot == slot) {
                    photos.set(i, new FaceMigration.Photo(id, slot, p.addedAtMillis, false));
                    return true;
                }
            }
            return false;
        }
    }

    /** "face" embeds, "blank" has no face, anything else fails the models (try again later). */
    private static final FaceMigration.Faces FAKE_FACES = jpeg -> {
        String s = new String(jpeg, java.nio.charset.StandardCharsets.US_ASCII);
        return s.equals("face") ? new float[128] : s.equals("blank") ? FaceMigration.NO_FACE : null;
    };

    private static void faceMigrationScenarios() {
        scenario("face_migration_marks_a_faceless_photo_unusable_and_becomes_ready", n -> {
            FakeFaceStore store = new FakeFaceStore().photo("a", 0, true, "face").photo("b", 0, true, "blank")
                    .photo("c", 0, false, "face");
            FaceMigration m = new FaceMigration(store, FAKE_FACES, () -> true);
            boolean readyBefore = FaceMigration.ready(store.photos) || m.ready() || m.settled();
            FaceMigration.Outcome o = m.pass();
            check(n, !readyBefore && o == FaceMigration.Outcome.DONE && m.settled() && m.ready()
                            && store.calls.equals(java.util.Arrays.asList("gallery", "photo a/0", "embed a/0 128",
                            "photo b/0", "unusable b/0"))
                            && FaceMigration.ready(store.photos) && m.embedded() == 1 && m.unusable() == 1,
                    "outcome=" + o + " calls=" + store.calls);
        });
        scenario("face_migration_runs_only_while_the_gate_is_open_and_resumes", n -> {
            FakeFaceStore store = new FakeFaceStore().photo("a", 0, true, "face").photo("a", 1, true, "face");
            final boolean[] open = {true};
            final int[] asks = {0};
            FaceMigration m = new FaceMigration(store, FAKE_FACES, () -> {
                asks[0]++;
                return open[0] && asks[0] <= 1;
            });
            FaceMigration.Outcome first = m.pass();
            List<String> afterFirst = new ArrayList<String>(store.calls);
            boolean settledFirst = m.settled();
            FaceMigration shut = new FaceMigration(new FakeFaceStore().photo("z", 0, true, "face"), FAKE_FACES,
                    () -> false);
            FaceMigration.Outcome closed = shut.pass();
            FaceMigration again = new FaceMigration(store, FAKE_FACES, () -> true);
            FaceMigration.Outcome second = again.pass();
            check(n, first == FaceMigration.Outcome.INTERRUPTED && !settledFirst && !m.ready()
                            && afterFirst.equals(java.util.Arrays.asList("gallery", "photo a/0", "embed a/0 128"))
                            && closed == FaceMigration.Outcome.INTERRUPTED && !shut.settled()
                            && second == FaceMigration.Outcome.DONE && again.ready()
                            && store.calls.contains("embed a/1 128")
                            && java.util.Collections.frequency(store.calls, "photo a/0") == 1,
                    "first=" + first + " closed=" + closed + " second=" + second + " calls=" + store.calls);
        });
        scenario("face_migration_leaves_a_photo_waiting_when_the_models_fail", n -> {
            FakeFaceStore store = new FakeFaceStore().photo("a", 0, true, "broken");
            FaceMigration m = new FaceMigration(store, FAKE_FACES, () -> true);
            FaceMigration.Outcome o = m.pass();
            check(n, o == FaceMigration.Outcome.DONE && m.settled() && !m.ready() && m.waiting() == 1
                            && !store.calls.contains("unusable a/0") && !FaceMigration.ready(store.photos),
                    "outcome=" + o + " calls=" + store.calls);
        });
        scenario("face_migration_retries_a_waiting_photo_after_a_backoff", n -> {
            FakeFaceStore store = new FakeFaceStore().photo("a", 0, true, "broken");
            FaceMigration m = new FaceMigration(store, FAKE_FACES, () -> true);
            boolean dueFresh = m.due(0);
            m.pass();
            boolean dueAtOnce = m.due(1000);
            boolean dueEarly = m.due(60999);
            boolean dueLater = m.due(61000);
            m.pass();
            boolean dueAfterSecond = m.due(62000) || m.due(62000 + 119999);
            boolean dueDoubled = m.due(62000 + 120000);
            store.jpegs.put("a/0", "face".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            FaceMigration.Outcome third = m.pass();
            boolean dueWhenReady = m.due(10_000_000);
            FakeFaceStore gone = new FakeFaceStore().photo("b", 0, true, "face");
            gone.tooOld = true;
            FaceMigration failed = new FaceMigration(gone, FAKE_FACES, () -> true);
            failed.pass();
            boolean failedEarly = failed.due(0);
            boolean failedLater = failed.due(60000);
            check(n, dueFresh && !dueAtOnce && !dueEarly && dueLater && !dueAfterSecond && dueDoubled
                            && third == FaceMigration.Outcome.DONE && m.ready() && m.settled() && !dueWhenReady
                            && store.calls.contains("embed a/0 128") && !failedEarly && failedLater,
                    "fresh=" + dueFresh + " once=" + dueAtOnce + " early=" + dueEarly + " later=" + dueLater
                            + " second=" + dueAfterSecond + " doubled=" + dueDoubled + " third=" + third
                            + " ready=" + dueWhenReady + " failed=" + failedEarly + "/" + failedLater
                            + " calls=" + store.calls);
        });
        scenario("face_migration_settles_without_readiness_when_the_launcher_is_too_old", n -> {
            FakeFaceStore store = new FakeFaceStore().photo("a", 0, true, "face");
            store.tooOld = true;
            FaceMigration m = new FaceMigration(store, FAKE_FACES, () -> true);
            FaceMigration.Outcome o = m.pass();
            FakeFaceStore empty = new FakeFaceStore().photo("a", 0, false, "face");
            FaceMigration none = new FaceMigration(empty, FAKE_FACES, () -> true);
            FaceMigration.Outcome o2 = none.pass();
            check(n, o == FaceMigration.Outcome.FAILED && m.settled() && !m.ready()
                            && o2 == FaceMigration.Outcome.DONE && none.ready()
                            && empty.calls.equals(java.util.Arrays.asList("gallery")),
                    "outcome=" + o + " none=" + o2 + " calls=" + empty.calls);
        });
    }

    // ---- review fixes (review 2026-10-01) ----

    private static void reviewFixScenarios() {
        scenario("review_lease_lost_in_the_back_up_wait_the_next_short_back_up_still_stops_on_a_stall_and_waits", n -> {
            // Review P2-1: the lease drops during the 3 s wait after a short back-up. With
            // retryWaiting left set, the next blocked turn's back-up ran blind for its full
            // time (no stall check) and turned at once (no wait): the motor board's latch.
            Rig rig = new Rig(escTuning().turnChance(1.0).build(), CLEAR, NOTHING, true);
            List<String> notes = traced(rig);
            rig.simWheels = true;
            rig.yaw.blockFirstTurnsSide = true;
            rig.started();
            runUntil(rig, 30000, r -> notedAt(notes, "backed up: waiting") >= 0);
            long waited = notedAt(notes, "backed up: waiting");
            rig.runUntil(rig.now + 1000);
            rig.brain.onLeaseChanged(false);
            rig.runUntil(rig.now + 500);
            long regained = rig.now;
            // Every turn is blocked from here on, and reversing goes nowhere.
            rig.yaw.stuck = true;
            rig.backBlocked = true;
            rig.brain.onLeaseChanged(true);
            runUntil(rig, regained + 30000, r -> notedAfter(notes, "backing up a little", regained) >= 0
                    && r.brain.state() != ExploreBrain.State.BACK_OFF);
            long second = notedAfter(notes, "backing up a little", regained);
            long stalled = notedAfter(notes, "wheels stalled backing up", regained);
            check(n, waited > 0 && second > regained && stalled > second && rig.violations.isEmpty(),
                    "waited@" + waited + " second@" + second + " stalled@" + stalled + " notes="
                            + notesAfter(notes, regained));
        });
    }

    private static void reviewAnswerOverScenarios() {
        scenario("review_chat_an_answer_that_ends_without_words_is_unanswered_as_it_ends_not_at_the_fallback", n -> {
            // Review P2-2: a cough at 3.5 s says answering; the launcher's "answer over" at 5 s ends
            // the port's listen as silence (NOTHING). The conversation must not hold to 23 s.
            Rig rig = sarahRig(true);
            List<String> notes = traced(rig);
            rig.people.listen = ListenScript.turns(hearWords("hi").after(300),
                    hearSilence().after(5000).answeringAfter(3500));
            long open = openChat(rig);
            long over = chatOver(rig, open);
            long second = nthListenAt(rig, open, 2);
            long held = noteAt(notes, "an answer has started");
            long first = noteAt(notes, "first unanswered listen");
            check(n, open > 0 && over > 0 && second > 0 && held - second >= 4000 && held - second <= 4150
                            && first - second >= 5000 && first - second <= 5150 && rig.violations.isEmpty(),
                    "second@" + second + " held@" + held + " first unanswered@" + first + " notes=" + notes);
        });
    }

    private static void reviewMeetAnswerOverScenarios() {
        scenario("review_meet_a_wordless_answer_ends_the_listen_when_the_port_says_silence_not_at_29_s", n -> {
            // Review P2-2, the brain's side: answering at 1 s, then the launcher's "answer over"
            // (the port's NOTHING) at 12 s, past the listen's deadline: "no reply" then, not at 29 s.
            Rig rig = meetRig();
            List<String> notes = traced(rig);
            rig.people.match = (r, k) -> STRANGER;
            rig.people.listen = ListenScript.always(hearSilence().after(12000).answeringAfter(1000));
            rig.started();
            rig.runUntil(60000);
            int listen = rig.first("listen", 0);
            long noReply = noteAt(notes, "no reply");
            check(n, listen >= 0 && noteAt(notes, "an answer has started") > rig.timeOf(listen)
                            && noReply - rig.timeOf(listen) >= 12000 && noReply - rig.timeOf(listen) <= 12200
                            && rig.violations.isEmpty(),
                    "listen@" + rig.timeOf(listen) + " noReply@" + noReply + " notes=" + notes);
        });
    }

    private static void reviewCplEpisodeScenarios() {
        scenario("review_one_cpl_hiccup_episode_counts_once_toward_boxed_in_and_its_retry_still_runs", n -> {
            // Review P2-3: on plain floor, a hiccup, CPL again on its retry (one episode), then a
            // second hiccup on a later leg: two episodes, not three refusals. He is not boxed in,
            // and the second hiccup's retry leg is driven.
            Rig[] h = new Rig[1];
            long[] cpls = {0};
            long[] lastAt = {-1};
            Rig rig = escRig(escTuning().hopTicks(8), h, t -> {
                Rig r = h[0];
                if (r == null || !"hop".equals(r.motion) || r.brain.state() != ExploreBrain.State.HOP) {
                    return clear(t);
                }
                long into = t - r.legStartT;
                boolean due = cpls[0] == 0 ? into >= 300
                        : cpls[0] == 1 ? into >= 100 && t - lastAt[0] >= 300
                        : cpls[0] == 2 ? t - lastAt[0] >= 8000 && into >= 300 : false;
                if (!due) {
                    return clear(t);
                }
                cpls[0]++;
                lastAt[0] = t;
                return cpl2(t);
            }, null);
            List<String> notes = traced(rig);
            rig.started();
            rig.runUntil(40000);
            List<Long> hiccups = noteTimes(notes, "controller refused forward (CPL) on plain floor");
            long second = hiccups.size() >= 2 ? hiccups.get(1) : -1;
            int retry = second < 0 ? -1 : rig.firstAfter("hop", second + 1);
            check(n, cpls[0] == 3 && hiccups.size() == 2 && notedAt(notes, "hazard while HOP: CPL") > 0
                            && notedAt(notes, "boxed in:") < 0 && entered(rig, ExploreBrain.State.RETRACE, 0) < 0
                            && retry >= 0 && rig.timeOf(retry) - second <= 1000 && rig.violations.isEmpty(),
                    "cpls=" + cpls[0] + " hiccups=" + hiccups + " retry@" + (retry < 0 ? -1 : rig.timeOf(retry))
                            + " notes=" + lastNotes(notes, 30));
        });
        scenario("review_a_hiccup_that_would_be_the_third_refusal_still_gets_its_retry_first", n -> {
            // Review P2-3: two hiccup episodes (each a hiccup and CPL again on its retry), then a
            // third hiccup: three refusals, so boxed in may follow, but only after that hiccup's
            // own retry leg has been driven (boxedIn used to pre-empt hopNext).
            Rig[] h = new Rig[1];
            long[] cpls = {0};
            long[] lastAt = {-1};
            Rig rig = escRig(escTuning().hopTicks(8).cap(10, 20000, 30000).wedge(10, 2, 1), h, t -> {
                Rig r = h[0];
                if (r == null || !"hop".equals(r.motion) || r.brain.state() != ExploreBrain.State.HOP || cpls[0] >= 5) {
                    return clear(t);
                }
                long into = t - r.legStartT;
                boolean hiccup = cpls[0] % 2 == 0;
                boolean due = hiccup ? into >= 300 && (lastAt[0] < 0 || t - lastAt[0] >= 3000)
                        : into >= 100 && t - lastAt[0] >= 300;
                if (!due) {
                    return clear(t);
                }
                cpls[0]++;
                lastAt[0] = t;
                return cpl2(t);
            }, null);
            List<String> notes = traced(rig);
            rig.started();
            rig.runUntil(50000);
            List<Long> hiccups = noteTimes(notes, "controller refused forward (CPL) on plain floor");
            long third = hiccups.size() >= 3 ? hiccups.get(2) : -1;
            int retry = third < 0 ? -1 : rig.firstAfter("hop", third + 1);
            long boxed = notedAt(notes, "boxed in:");
            check(n, cpls[0] == 5 && third > 0 && retry >= 0 && rig.timeOf(retry) - third <= 1000
                            && boxed > rig.timeOf(retry) && rig.violations.isEmpty(),
                    "cpls=" + cpls[0] + " hiccups=" + hiccups + " retry@" + (retry < 0 ? -1 : rig.timeOf(retry))
                            + " boxed@" + boxed + " notes=" + lastNotes(notes, 30));
        });
    }

    private static void reviewSeekDoorwayScenarios() {
        scenario("review_a_doorway_forgotten_after_a_hazard_is_not_the_next_seeks_trusted_target", n -> {
            // Review P2-4: Claude reports a doorway; the trusted short leg toward it hits an
            // obstacle ("forgotten"). A seek that starts within seekDoorwayMs must not head for it.
            List<String> notes = new ArrayList<String>();
            long[] obstAt = {Long.MAX_VALUE};
            Rig[] h = new Rig[1];
            Rig rig = doorRig(doorTuning().curiosityMs(15000, 15000).scan(3, 500).scanTurnDeg(40).ask(1, 4000)
                            .placeMemory(1800000, 300, 500, 5000).seekEvery(15000).seekAskTimeoutMs(5000),
                    t -> {
                        if (obstAt[0] == Long.MAX_VALUE && h[0] != null) {
                            long tr = notedAt(notes, "reads blocked");
                            if (tr >= 0 && h[0].firstAfter("hop", tr) >= 0) {
                                obstAt[0] = t + 200;
                            }
                        }
                        return t >= obstAt[0] && t < obstAt[0] + 300 ? obstacle(t) : clear(t);
                    },
                    (r, t) -> r.doorwayAnswerStates.isEmpty() ? prof(0.9f, 0.9f, 0.9f, 0.9f)
                            : prof(0.9f, 0.9f, 0.1f, 0.9f),
                    (r, k) -> k == 1 ? CuriosityPort.Doorway.door(0f) : CuriosityPort.Doorway.none());
            h[0] = rig;
            rig.seeks = (r, req, k) -> CuriosityPort.WayOut.none();
            rig.brain.setTrace(x -> notes.add(h[0].now + " " + x));
            rig.started();
            runUntil(rig, 120000, r -> notedAt(notes, "seeking:") >= 0);
            rig.runUntil(rig.now + 2000);
            long forgotten = notedAt(notes, "a hazard on the leg toward it: forgotten");
            long seek = notedAt(notes, "seeking:");
            check(n, forgotten > 0 && seek > forgotten && seek - forgotten < 180000
                            && notedAt(notes, "seeking: heading for the doorway") < 0 && rig.violations.isEmpty(),
                    "forgotten@" + forgotten + " seek@" + seek + " notes=" + lastNotes(notes, 25));
        });
    }

    private static void reviewLookAroundScenarios() {
        scenario("review_clutter_reading_0_25_everywhere_looks_around_at_most_three_times_in_3_min_and_still_drives", n -> {
            // Review P2-5: every heading reads 0.25 (over boxedInOpen, under steerBlocked): each
            // look-around ends facing a "best" for one short leg, and the next decision started
            // another 6-look spin. A cooldown (lookAroundCooldownMs, unless he has driven
            // lookAroundCooldownCounts since) leaves him driving between look-arounds: 11 look-arounds
            // and 10 legs in 3 min before, 3 and 20 or more with 60 s.
            Rig rig = doorRig(doorTuning(), CLEAR, worldView(hd -> 0.25), (r, k) -> CuriosityPort.Doorway.none());
            List<String> notes = traced(rig);
            rig.started();
            rig.runUntil(180000);
            int arounds = notedTimes(notes, "everything ahead closed: looking around").size();
            int legs = rig.count("hop") > 0 ? drivesIn(rig, "HOP", "hop", 0, Long.MAX_VALUE).size() : 0;
            ExploreTuning d = new ExploreTuning.Builder().build();
            check(n, arounds >= 1 && arounds <= 3 && legs >= 20 && d.lookAroundCooldownMs == 60000
                            && d.lookAroundCooldownCounts == 1500 && rig.violations.isEmpty(),
                    "arounds=" + arounds + " legs=" + legs + " notes=" + lastNotes(notes, 20));
        });
    }

    private static void reviewJamMemoryScenarios() {
        scenario("review_jammed_a_lease_drop_and_regain_rests_on_and_never_drives_into_the_jam", n -> {
            // Review P2-6: fully jammed, the lease drops (someone picks him up) and comes back. He
            // went to PAUSE and drove a full forward leg into the jam he had just declared.
            List<String> notes = new ArrayList<String>();
            Rig rig = pinnedRig(notes);
            rig.started();
            runUntil(rig, 120000, r -> notedAt(notes, "fully jammed") >= 0);
            long jamAt = rig.now;
            rig.runUntil(jamAt + 5000);
            rig.brain.onLeaseChanged(false);
            rig.runUntil(rig.now + 1000);
            long back = rig.now;
            rig.brain.onLeaseChanged(true);
            rig.runUntil(jamAt + 29000);
            int pushes = rig.countPrefix("hop", back, Long.MAX_VALUE) + rig.countPrefix("turn", back, Long.MAX_VALUE);
            long rest = entered(rig, ExploreBrain.State.CORNERED, back);
            List<Long> probes = backDrives(rig, back, Long.MAX_VALUE);
            rig.runUntil(jamAt + 32000);
            List<Long> probesAfter = backDrives(rig, back, Long.MAX_VALUE);
            check(n, jamAt > 0 && pushes == 0 && rest >= back && rest - back < 300 && probes.isEmpty()
                            && probesAfter.size() == 1 && withinTick(probesAfter.get(0), jamAt + 30000)
                            && rig.violations.isEmpty(),
                    "jam@" + jamAt + " back@" + back + " pushes=" + pushes + " rest@" + rest + " probes=" + probesAfter
                            + " notes=" + notesAfter(notes, back));
        });
        scenario("review_jammed_a_call_met_in_place_goes_back_to_the_jammed_rest_not_roaming", n -> {
            // Review P2-6: a call while jammed is met where he is (IN_PLACE); when the meeting ends
            // he must not roam straight into the jam.
            List<String> notes = new ArrayList<String>();
            Rig[] h = new Rig[1];
            Rig rig = escRig(escTuning().turnChance(1.0), h, CLEAR, (r, req, nth) -> CuriosityPort.WayOut.way(0, 0f));
            rig.creepPer100 = 0;
            rig.brain.setTrace(x -> notes.add(h[0].now + " " + x));
            pin(rig, true);
            rig.blockedFrom = 0;
            rig.started();
            runUntil(rig, 120000, r -> notedAt(notes, "fully jammed") >= 0);
            long jamAt = rig.now;
            rig.cue(jamAt + 2000, Ears.Kind.WAKE_WORD, Ears.Side.LEFT, -40f);
            rig.runUntil(jamAt + 25000);
            long met = notedAt(notes, "the call's stop is over");
            int pushes = rig.countPrefix("hop", jamAt, Long.MAX_VALUE) + rig.countPrefix("turn", jamAt, Long.MAX_VALUE);
            long rest = met < 0 ? -1 : entered(rig, ExploreBrain.State.CORNERED, met);
            long over = met;
            check(n, jamAt > 0 && notedAt(notes, "answering the call") > jamAt && met > 0 && pushes == 0
                            && rest >= met && rest - met < 300 && rig.violations.isEmpty(),
                    "jam@" + jamAt + " over@" + over + " pushes=" + pushes + " notes=" + notesAfter(notes, jamAt));
        });
    }

    private static void reviewNowhereLegScenarios() {
        scenario("review_a_leg_that_went_nowhere_keeps_the_stuck_spells_recovery_count", n -> {
            // Review P2-6: a short roaming leg whose wheels never moved (too short for the stall
            // watch) ran "driven away cleanly": the RECOVER count went back to 0, so the next
            // zero turn started a fresh 3-spell cycle and pushed the latched board again.
            Rig[] h = new Rig[1];
            List<String> notes = new ArrayList<String>();
            int[] phase = {0};
            long[] hopFrom = {Long.MAX_VALUE};
            Rig rig = escRig(escTuning().turnChance(1.0), h, t -> {
                Rig r = h[0];
                if (r == null) {
                    return clear(t);
                }
                if (phase[0] == 0 && r.brain.state() == ExploreBrain.State.TURN && r.now > 2000) {
                    phase[0] = 1;
                    pin(r, true);
                } else if (phase[0] == 1 && notedAt(notes, "stall: waiting for the motor board") >= 0
                        && r.now >= notedAt(notes, "stall: waiting for the motor board") + 3000) {
                    // The board is back for turns and reversing; forward still goes nowhere.
                    phase[0] = 2;
                    pin(r, false);
                    r.blockedFrom = r.now;
                    hopFrom[0] = r.now;
                } else if (phase[0] == 2 && r.countPrefix("hop", hopFrom[0], Long.MAX_VALUE) > 0
                        && r.brain.state() == ExploreBrain.State.TURN) {
                    phase[0] = 3;
                    r.yaw.stuck = true;
                    r.turnWheelsBlocked = true;
                    r.backBlocked = true;
                }
                return clear(t);
            }, (r, req, nth) -> CuriosityPort.WayOut.way(0, 0f));
            rig.creepPer100 = 0;
            rig.brain.setTrace(x -> notes.add(h[0].now + " " + x));
            rig.started();
            runUntil(rig, 120000, r -> phase[0] == 3 && notedTimes(notes, "stall: waiting for the motor board").size() >= 2);
            List<String> waits = new ArrayList<String>();
            for (String x : notes) {
                if (x.contains("stall: waiting for the motor board")) {
                    waits.add(x);
                }
            }
            check(n, phase[0] == 3 && waits.size() >= 2 && waits.get(1).contains("(recovery 2 of ")
                            && rig.violations.isEmpty(),
                    "phase=" + phase[0] + " waits=" + waits + " notes=" + lastNotes(notes, 30));
        });
    }

    private static void reviewTrustLegScenarios() {
        scenario("review_a_trusted_leg_cut_off_by_a_cue_or_a_lease_drop_leaves_no_trust_behind", n -> {
            // Review P3: the trusted short leg toward Claude's doorway starts with a turn; a voice
            // mid-turn takes over (leaveForCue), or the lease drops (enterEyesOnly). The trust flag
            // stayed set, so a later leg reached without a leg decision (the turn away after a
            // conversation, a hiccup's retry) ran full length with the camera's blocked-ahead
            // stop and re-aim off. Neither interruption may leave it set.
            boolean[] left = new boolean[2];
            String[] detail = new String[2];
            for (int k = 0; k < 2; k++) {
                List<String> notes = new ArrayList<String>();
                Rig[] h = new Rig[1];
                Rig rig = doorRig(doorTuning(), CLEAR,
                        (r, t) -> r.doorwayAnswerStates.isEmpty() ? prof(0.9f, 0.9f, 0.9f, 0.9f)
                                : worldView(hd -> Math.abs(Heading.delta(hd, 341)) < 12 ? 0.1 : 0.9).at(r, t),
                        (r, q) -> q == 1 ? CuriosityPort.Doorway.door(0.6f) : CuriosityPort.Doorway.none());
                h[0] = rig;
                rig.brain.setTrace(x -> notes.add(h[0].now + " " + x));
                rig.started();
                runUntil(rig, 30000, r -> notedAt(notes, "to face the doorway") >= 0
                        && r.brain.state() == ExploreBrain.State.TURN);
                boolean armed = rig.brain.trustLegPending();
                if (k == 0) {
                    rig.cue(rig.now + 10, Ears.Tier.WEAK, Ears.Side.LEFT, Float.NaN);
                    rig.runUntil(rig.now + 300);
                } else {
                    rig.brain.onLeaseChanged(false);
                    rig.runUntil(rig.now + 300);
                }
                left[k] = armed && !rig.brain.trustLegPending();
                detail[k] = "armed=" + armed + " after=" + rig.brain.trustLegPending() + " state=" + rig.brain.state()
                        + " notes=" + lastNotes(notes, 12);
            }
            check(n, left[0] && left[1], "cue: " + detail[0] + " | lease: " + detail[1]);
        });
    }

    private static void reviewRecoverStampScenarios() {
        scenario("review_a_zero_turn_long_after_a_bump_waits_the_full_recovery_from_the_turn", n -> {
            // Review P3: a bump stamps the stall clock; a turn reading 0 deg and 0 counts some
            // seconds later entered RECOVER timed from the bump, so the early probes were already
            // past and the board's wait was mostly skipped. The zero turn is itself fresh
            // evidence: the wait runs from it, probes at 2, 5, 10 and 20 s.
            Rig[] h = new Rig[1];
            long[] bumpAt = {-1};
            List<String> notes = new ArrayList<String>();
            Rig rig = escRig(escTuning().turnChance(1.0), h, t -> {
                Rig r = h[0];
                if (r == null) {
                    return clear(t);
                }
                if (bumpAt[0] < 0 && r.brain.state() == ExploreBrain.State.HOP && r.moving && t - r.legStartT >= 700) {
                    bumpAt[0] = t;
                    return obstacle(t);
                }
                if (bumpAt[0] >= 0 && t >= bumpAt[0] + 2500 && !r.yaw.stuck) {
                    // The board latches 2.5 s after the bump, while he is still escaping.
                    pin(r, true);
                }
                return clear(t);
            }, (r, req, nth) -> CuriosityPort.WayOut.way(0, 0f));
            rig.creepPer100 = 0;
            rig.brain.setTrace(x -> notes.add(h[0].now + " " + x));
            rig.started();
            runUntil(rig, 60000, r -> notedAt(notes, "stall: waiting for the motor board") >= 0);
            long wait = notedAt(notes, "stall: waiting for the motor board");
            String w = firstNote(notes, "stall: waiting for the motor board");
            rig.runUntil(wait + 1900);
            int early = rig.countPrefix("back", wait + 1, wait + 1900);
            check(n, bumpAt[0] > 0 && wait - bumpAt[0] > 2000 && w != null && w.contains("(probes at 2, 5, 10, 20 s)")
                            && early == 0 && rig.violations.isEmpty(),
                    "bump@" + bumpAt[0] + " wait@" + wait + " note=" + w + " early backs=" + early
                            + " notes=" + notesAfter(notes, bumpAt[0]) + " states=" + rig.stateLog.subList(Math.max(0, rig.stateLog.size() - 12), rig.stateLog.size()) + " tail=" + rig.tail());
        });
    }

    private static void reviewLeanInScenarios() {
        scenario("review_a_lean_in_cut_off_by_a_lease_drop_does_not_start_a_cooldown_at_a_later_stop", n -> {
            // Review P3: a lean-in interrupted by a lease drop left leanInOpen set; the next
            // unrelated curiosity stop's end, a minute later, started the 60 s cooldown, and a
            // voice just after it was ignored.
            Rig rig = cueRig(cueTuning().curiosityMs(70000, 70000), CLEAR, EMPTY_ROOM);
            List<String> notes = traced(rig);
            rig.started();
            rig.cue(400, Ears.Tier.WEAK, Ears.Side.LEFT, -90f);
            rig.at(700, () -> rig.brain.onLeaseChanged(false));
            rig.at(1700, () -> rig.brain.onLeaseChanged(true));
            runUntil(rig, 150000, r -> notedAt(notes, "curiosity stop") >= 0);
            long stop = notedAt(notes, "curiosity stop");
            long ended = runUntilState(rig, ExploreBrain.State.PAUSE, rig.now, rig.now + 40000);
            long cueT = rig.now + 2000;
            rig.cue(cueT, Ears.Tier.WEAK, Ears.Side.LEFT, -90f);
            rig.runUntil(cueT + 500);
            List<Long> leanIns = noteTimes(notes, "a lean-in from");
            long ignored = notedAfter(notes, "cue ignored: lean-in cooldown", cueT);
            check(n, stop > 60000 && ended > stop && leanIns.size() == 2 && leanIns.get(1) >= cueT && ignored < 0
                            && rig.violations.isEmpty(),
                    "stop@" + stop + " ended@" + ended + " leanIns=" + leanIns + " ignored@" + ignored
                            + " notes=" + lastNotes(notes, 15));
        });
    }

    /** When the brain first noted part at or after from, else -1. */
    private static long notedAfter(List<String> notes, String part, long from) {
        for (String x : notes) {
            long t = Long.parseLong(x.substring(0, x.indexOf(' ')));
            if (t >= from && x.contains(part)) {
                return t;
            }
        }
        return -1;
    }

    /** The notes at or after from, for a failure's detail. */
    private static List<String> notesAfter(List<String> notes, long from) {
        List<String> out = new ArrayList<String>();
        for (String x : notes) {
            if (Long.parseLong(x.substring(0, x.indexOf(' '))) >= from) {
                out.add(x);
            }
        }
        return out.size() > 30 ? out.subList(0, 30) : out;
    }

    // ---- harness plumbing ----

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            System.out.println("PASS " + name);
        } else {
            failures++;
            System.out.println("FAIL " + name + ": " + detail);
        }
    }

    interface Scenario {
        void run(String name) throws Exception;
    }

    private static void scenario(String name, Scenario s) {
        try {
            s.run(name);
        } catch (Throwable t) {
            failures++;
            System.out.println("FAIL " + name + ": threw " + t);
        }
    }

    public static void main(String[] args) {
        classifierScenarios();
        acceptanceScenarios();
        edgeScenarios();
        integrationScenarios();
        curiosityScenarios();
        claudeScenarios();
        meetScenarios();
        faceCropScenarios();
        liveFixScenarios();
        hazardDuringPickScenarios();
        headingScenarios();
        navScenarios();
        coverageScenarios();
        placeScenarios();
        escapeScenarios();
        pinnedScenarios();
        jamScenarios();
        wriggleScenarios();
        recoverScenarios();
        budgetScenarios();
        forwardFirstScenarios();
        sideScenarios();
        backUpFirstScenarios();
        doorwayScenarios();
        seekScenarios();
        peopleScenarios();
        steerWaitScenarios();
        cplHiccupScenarios();
        reaimScenarios();
        earsAndChatScenarios();
        cueScenarios();
        leanInCooldownScenarios();
        boxedInScenarios();
        lookAroundScenarios();
        robotSeekScenarios();
        chatScenarios();
        dockScenarios();
        callScenarios();
        callFindScenarios();
        callSideScenarios();
        faceMatchScenarios();
        faceMigrationScenarios();
        faceConfirmScenarios();
        remarkRateScenarios();
        reviewFixScenarios();
        reviewAnswerOverScenarios();
        reviewMeetAnswerOverScenarios();
        reviewCplEpisodeScenarios();
        reviewSeekDoorwayScenarios();
        reviewLookAroundScenarios();
        reviewJamMemoryScenarios();
        reviewNowhereLegScenarios();
        reviewTrustLegScenarios();
        reviewRecoverStampScenarios();
        reviewLeanInScenarios();
        System.out.println(failures == 0 ? "ALL OK" : ("FAILURES " + failures));
        System.exit(failures == 0 ? 0 : 1);
    }

    // ---- HazardClassifier ----

    private static void classifierScenarios() {
        scenario("classifier_uncalibrated_is_unavailable", n -> {
            HazardClassifier c = new HazardClassifier(tuning().calibration(null).build());
            feedClassifier(c, CLEAR, 100, 2000);
            HazardClassifier.Status s = c.status(2000);
            check(n, s == HazardClassifier.Status.UNAVAILABLE, "status=" + s);
        });
        scenario("classifier_incomplete_calibration_is_unavailable", n -> {
            // No obstacle rule and no edge rule: nothing to stop on, so no driving.
            HazardClassifier c = new HazardClassifier(tuning()
                    .calibration(new ExploreTuning.Calibration(-1, -1, -1, true)).build());
            feedClassifier(c, CLEAR, 100, 2000);
            HazardClassifier.Status s = c.status(2000);
            check(n, s == HazardClassifier.Status.UNAVAILABLE, "status=" + s);
        });
        scenario("classifier_needs_the_recovery_streak_at_start", n -> {
            HazardClassifier c = new HazardClassifier(tuning().build());
            HazardClassifier.Status none = c.status(50);
            c.offer(clear(100));
            HazardClassifier.Status one = c.status(100);
            c.offer(clear(200));
            HazardClassifier.Status two = c.status(200);
            c.offer(clear(300));
            HazardClassifier.Status three = c.status(300);
            check(n, none == HazardClassifier.Status.UNAVAILABLE && one == HazardClassifier.Status.UNAVAILABLE
                            && two == HazardClassifier.Status.UNAVAILABLE && three == HazardClassifier.Status.CLEAR,
                    "none=" + none + " one=" + one + " two=" + two + " three=" + three);
        });
        scenario("classifier_goes_unavailable_after_300ms_without_a_reading", n -> {
            HazardClassifier c = new HazardClassifier(tuning().build());
            feedClassifier(c, CLEAR, 100, 500);
            HazardClassifier.Status fresh = c.status(790);
            HazardClassifier.Status stale = c.status(800);
            check(n, fresh == HazardClassifier.Status.CLEAR && stale == HazardClassifier.Status.UNAVAILABLE,
                    "at790=" + fresh + " at800=" + stale);
        });
        scenario("classifier_tof_fault_value_is_unavailable", n -> {
            HazardClassifier c = new HazardClassifier(tuning().build());
            feedClassifier(c, CLEAR, 100, 500);
            c.offer(new SensorReading(600, 16383, 100, 100, null, false));
            HazardClassifier.Status s = c.status(600);
            check(n, s == HazardClassifier.Status.UNAVAILABLE, "status=" + s);
        });
        scenario("classifier_uart_fault_is_unavailable", n -> {
            HazardClassifier c = new HazardClassifier(tuning().build());
            feedClassifier(c, CLEAR, 100, 500);
            c.offer(new SensorReading(600, 305, 100, 100, null, true));
            HazardClassifier.Status s = c.status(600);
            c.offer(clear(700));
            HazardClassifier.Status after = c.status(700);
            check(n, s == HazardClassifier.Status.UNAVAILABLE && after == HazardClassifier.Status.UNAVAILABLE,
                    "fault=" + s + " oneCleanAfter=" + after);
        });
        scenario("classifier_frozen_tof_is_unavailable", n -> {
            // A leftover TOFDS leaves a plausible tof that never moves (KTD3).
            HazardClassifier c = new HazardClassifier(tuning().build());
            Feed frozen = t -> new SensorReading(t, 300, 100 + jitter(t), 100 + jitter(t), null, false);
            feedClassifier(c, frozen, 100, 3000);
            HazardClassifier.Status before = c.status(3000);
            c.offer(frozen.at(3100));
            HazardClassifier.Status after = c.status(3100);
            check(n, before == HazardClassifier.Status.CLEAR && after == HazardClassifier.Status.UNAVAILABLE,
                    "at3000=" + before + " at3100=" + after);
        });
        scenario("classifier_constant_ir_is_not_frozen", n -> {
            // The frozen rule is tof-only: ir1 and ir2 sitting still is normal on a flat desk.
            HazardClassifier c = new HazardClassifier(tuning().build());
            Feed steadyIr = t -> new SensorReading(t, 300 + jitter(t), 100, 100, null, false);
            feedClassifier(c, steadyIr, 100, 10000);
            HazardClassifier.Status s = c.status(10000);
            check(n, s == HazardClassifier.Status.CLEAR, "status=" + s);
        });
        scenario("classifier_flapping_needs_a_new_streak", n -> {
            HazardClassifier c = new HazardClassifier(tuning().build());
            feedClassifier(c, CLEAR, 100, 300);
            HazardClassifier.Status up = c.status(300);
            HazardClassifier.Status stale = c.status(600);
            c.offer(clear(700));
            HazardClassifier.Status oneBack = c.status(700);
            c.offer(clear(800));
            HazardClassifier.Status twoBack = c.status(800);
            c.offer(clear(900));
            HazardClassifier.Status threeBack = c.status(900);
            check(n, up == HazardClassifier.Status.CLEAR && stale == HazardClassifier.Status.UNAVAILABLE
                            && oneBack == HazardClassifier.Status.UNAVAILABLE
                            && twoBack == HazardClassifier.Status.UNAVAILABLE
                            && threeBack == HazardClassifier.Status.CLEAR,
                    "up=" + up + " stale=" + stale + " 1=" + oneBack + " 2=" + twoBack + " 3=" + threeBack);
        });
        scenario("classifier_thresholds_report_edge_and_obstacle_sides", n -> {
            HazardClassifier c = new HazardClassifier(tuning().build());
            feedClassifier(c, CLEAR, 100, 300);
            StringBuilder got = new StringBuilder();
            SensorReading[] rs = {edgeLeft(400), edgeRight(500), edgeAhead(600), obstacle(700)};
            for (SensorReading r : rs) {
                c.offer(r);
                HazardClassifier.Status s = c.status(r.timestampMs);
                HazardClassifier.Hazard h = c.hazard();
                got.append(s).append('/').append(h == null ? "null" : h.kind + "/" + h.side).append(' ');
            }
            String want = "HAZARD/EDGE/LEFT HAZARD/EDGE/RIGHT HAZARD/EDGE/null HAZARD/OBSTACLE/null ";
            check(n, got.toString().equals(want), "got=" + got);
        });
        scenario("classifier_cpl2_is_a_hazard", n -> {
            HazardClassifier c = new HazardClassifier(tuning().build());
            feedClassifier(c, CLEAR, 100, 300);
            c.offer(cpl2(400));
            HazardClassifier.Status s = c.status(400);
            HazardClassifier.Hazard h = c.hazard();
            c.offer(new SensorReading(500, 303, 100, 100, 1, false));
            HazardClassifier.Status cpl1 = c.status(500);
            check(n, s == HazardClassifier.Status.HAZARD && h != null && h.kind == HazardClassifier.Kind.CPL
                            && cpl1 == HazardClassifier.Status.CLEAR,
                    "cpl2=" + s + " hazard=" + (h == null ? null : h.kind) + " cpl1=" + cpl1);
        });
        scenario("classifier_charger_flag_is_motion_refused", n -> {
            // Docked (meeting plan U1, KTD6): the latched CPL=3 flag arrives on an
            // otherwise clear reading and reads as motion refused, like CPL=2.
            HazardClassifier c = new HazardClassifier(tuning().build());
            feedClassifier(c, CLEAR, 100, 300);
            c.offer(charger(400));
            HazardClassifier.Status s = c.status(400);
            HazardClassifier.Hazard h = c.hazard();
            boolean docked = c.charger();
            c.offer(clear(500));
            boolean undocked = c.charger();
            HazardClassifier c2 = ownerClassifier();
            c2.offer(new SensorReading(100, 166, -1, 0, null, false, false, 0, 0, false, 0, 0, 0, true, false, 0, 0, 0));
            HazardClassifier.ApproachVerdict v = c2.approach(100);
            check(n, s == HazardClassifier.Status.HAZARD && h != null && h.kind == HazardClassifier.Kind.CPL
                            && docked && !undocked && v == HazardClassifier.ApproachVerdict.CLOSE_REFUSED,
                    "status=" + s + " hazard=" + (h == null ? null : h.kind) + " docked=" + docked
                            + " undocked=" + undocked + " approach=" + v);
        });
        scenario("classifier_cpl2_is_forward_refused_never_charging", n -> {
            HazardClassifier c = new HazardClassifier(tuning().build());
            feedClassifier(c, CLEAR, 100, 300);
            c.offer(cpl2(400));
            HazardClassifier.Hazard h = c.hazard();
            check(n, h != null && h.kind == HazardClassifier.Kind.CPL && !c.charger(),
                    "hazard=" + (h == null ? null : h.kind) + " charger=" + c.charger());
        });
        scenario("classifier_absent_ir_is_never_an_edge", n -> {
            // Live records carry ir1 as all-'X' padding (absent, -1); with a "below"
            // IR rule that must not read as an edge on every reading.
            HazardClassifier c = new HazardClassifier(new ExploreTuning.Builder()
                    .calibration(new ExploreTuning.Calibration(100, -1, 1, false)).recoveryStreak(1).build());
            c.offer(new SensorReading(100, 300, -1, 1, null, false));
            HazardClassifier.Status s = c.status(100);
            check(n, s == HazardClassifier.Status.CLEAR, "status=" + s + " hazard=" + c.hazard());
        });
        scenario("classifier_edge_held_past_frozen_window_stays_a_hazard", n -> {
            // Facing past an edge, tof sits at its out-of-range value with the IR flag on
            // and never changes. That is an edge, not a stuck sensor; calling it frozen put
            // the robot in eyes-only at the edge, where it never turned away (seen on device).
            HazardClassifier c = new HazardClassifier(new ExploreTuning.Builder()
                    .calibration(new ExploreTuning.Calibration(100, -1, 0, true)).recoveryStreak(1)
                    .frozenTofWindowMs(3000).build());
            for (long t = 100; t <= 6000; t += 100) {
                c.offer(new SensorReading(t, 16383, -1, 1, null, false));
            }
            HazardClassifier.Status st = c.status(6000);
            check(n, st == HazardClassifier.Status.HAZARD, "status=" + st + " reason=" + c.reason());
        });
        scenario("classifier_one_ir_channel_gives_no_side", n -> {
            // ir1 is always absent on this robot; with only ir2 flagging there is nothing
            // to tell left from right, so the edge must not be pinned to a side.
            HazardClassifier c = new HazardClassifier(new ExploreTuning.Builder()
                    .calibration(new ExploreTuning.Calibration(100, -1, 0, true)).recoveryStreak(1).build());
            c.offer(new SensorReading(100, 300, -1, 1, null, false));
            HazardClassifier.Status s2 = c.status(100);
            HazardClassifier.Hazard h = c.hazard();
            check(n, s2 == HazardClassifier.Status.HAZARD && h != null && h.kind == HazardClassifier.Kind.EDGE
                            && h.side == null,
                    "status=" + s2 + " hazard=" + h + " side=" + (h == null ? null : h.side));
        });
        scenario("classifier_fault_tof_with_ir_edge_flag_is_an_edge", n -> {
            // Over an edge the ToF may read its out-of-range value; when the IR edge flag
            // agrees, that is an edge to back away from, not a dead sensor to freeze on.
            HazardClassifier c = new HazardClassifier(new ExploreTuning.Builder()
                    .calibration(new ExploreTuning.Calibration(100, -1, 0, true)).recoveryStreak(1).build());
            c.offer(new SensorReading(100, 300, -1, 0, null, false));
            HazardClassifier.Status before = c.status(100);
            c.offer(new SensorReading(200, 16383, -1, 1, null, false));
            HazardClassifier.Status edge = c.status(200);
            HazardClassifier.Hazard h = c.hazard();
            c.offer(new SensorReading(300, 16383, -1, 0, null, false));
            HazardClassifier.Status noFlag = c.status(300);
            check(n, before == HazardClassifier.Status.CLEAR && edge == HazardClassifier.Status.HAZARD
                            && h != null && h.kind == HazardClassifier.Kind.EDGE
                            && noFlag == HazardClassifier.Status.UNAVAILABLE,
                    "before=" + before + " edge=" + edge + " hazard=" + h + " noFlag=" + noFlag);
        });
        approachScenarios();
    }

    // ---- HazardClassifier: approach mode (U4, KTD4) ----

    /** The owner's real calibration: obstacle below 157, no tof edge rule, ir2 above 0 is an edge. */
    private static HazardClassifier ownerClassifier() {
        return new HazardClassifier(new ExploreTuning.Builder()
                .calibration(new ExploreTuning.Calibration(157, -1, 0, true)).recoveryStreak(1).build());
    }

    private static HazardClassifier.ApproachVerdict approachAt(int tof, int ir2, Integer cpl) {
        HazardClassifier c = ownerClassifier();
        c.offer(new SensorReading(100, tof, -1, ir2, cpl, false));
        return c.approach(100);
    }

    private static void approachScenarios() {
        scenario("approach_low_tof_with_ir2_is_close", n -> {
            // A hand in front: tof far below the band with ir2 set is arrival, not a hazard.
            HazardClassifier.ApproachVerdict v = approachAt(45, 1, null);
            check(n, v == HazardClassifier.ApproachVerdict.CLOSE, "verdict=" + v);
        });
        scenario("approach_fault_tof_with_ir2_is_edge", n -> {
            HazardClassifier.ApproachVerdict v = approachAt(16383, 1, null);
            check(n, v == HazardClassifier.ApproachVerdict.EDGE, "verdict=" + v);
        });
        scenario("approach_cpl2_below_band_is_close_by_refusal", n -> {
            // tof 166 is above obstacleTofBelow (157) but below the band (170): the
            // controller refused forward because something is close.
            HazardClassifier.ApproachVerdict v = approachAt(166, 0, 2);
            check(n, v == HazardClassifier.ApproachVerdict.CLOSE_REFUSED && v.isClose(), "verdict=" + v);
        });
        scenario("approach_cpl2_with_ir2_above_band_is_edge", n -> {
            // Rolling toward a drop-off: tof rising past the band's top (280).
            HazardClassifier.ApproachVerdict v = approachAt(289, 1, 2);
            check(n, v == HazardClassifier.ApproachVerdict.EDGE, "verdict=" + v);
        });
        scenario("approach_ir2_inside_band_is_edge", n -> {
            // A flag with tof inside the band says nothing about direction: take the safe reading.
            HazardClassifier.ApproachVerdict v = approachAt(230, 1, null);
            check(n, v == HazardClassifier.ApproachVerdict.EDGE, "verdict=" + v);
        });
        scenario("approach_no_flag_inside_band_is_clear", n -> {
            HazardClassifier.ApproachVerdict v = approachAt(220, 0, null);
            check(n, v == HazardClassifier.ApproachVerdict.CLEAR, "verdict=" + v);
        });
        scenario("approach_tof_above_edge_rule_is_edge", n -> {
            // A calibrated tof edge rule applies with or without a flag.
            HazardClassifier c = new HazardClassifier(new ExploreTuning.Builder()
                    .calibration(new ExploreTuning.Calibration(157, 400, 0, true)).recoveryStreak(1).build());
            c.offer(new SensorReading(100, 450, -1, 0, null, false));
            HazardClassifier.ApproachVerdict v = c.approach(100);
            check(n, v == HazardClassifier.ApproachVerdict.EDGE, "verdict=" + v);
        });
        scenario("approach_unavailable_like_wander_mode", n -> {
            HazardClassifier unc = new HazardClassifier(tuning().calibration(null).build());
            unc.offer(new SensorReading(100, 45, -1, 1, null, false));
            HazardClassifier.ApproachVerdict uncalibrated = unc.approach(100);
            HazardClassifier c = ownerClassifier();
            c.offer(new SensorReading(100, 45, -1, 1, null, false));
            HazardClassifier.ApproachVerdict stale = c.approach(400);
            check(n, uncalibrated == HazardClassifier.ApproachVerdict.UNAVAILABLE
                            && stale == HazardClassifier.ApproachVerdict.UNAVAILABLE,
                    "uncalibrated=" + uncalibrated + " stale=" + stale);
        });
        scenario("wander_low_tof_with_ir2_is_still_a_hazard", n -> {
            HazardClassifier c = ownerClassifier();
            c.offer(new SensorReading(100, 45, -1, 1, null, false));
            HazardClassifier.Status s = c.status(100);
            HazardClassifier.Hazard h = c.hazard();
            check(n, s == HazardClassifier.Status.HAZARD && h != null && h.kind == HazardClassifier.Kind.EDGE,
                    "status=" + s + " hazard=" + h);
        });
    }

    // ---- ExploreBrain: acceptance examples ----
    //
    // With the harness tuning and clear readings from t=100 the classifier is
    // available at 300, the first pause runs to 1300, and the first hop starts on
    // the 1300 reading with ticks at 1300, 1550 and 1800 and a stop at 2050.

    private static void acceptanceScenarios() {
        scenario("ae1_edge_mid_hop_startles_backs_off_and_turns_away", n -> {
            // The edge is in view until the escape turn has begun (turning away clears it).
            Rig rig = new Rig(tuning().build(), t -> t < 1600 || t >= 2600 ? clear(t) : edgeAhead(t)).started();
            rig.runUntil(3700);
            int hop = rig.first("hop", 0);
            int stop = rig.first("stop", hop);
            int startle = rig.first("startle", 0);
            int flinch = rig.first("eyes FLINCH", 0);
            int back = rig.first("back", 0);
            int backStop = rig.first("stop", back);
            int look = rig.first("eyes LOOK", backStop);
            int turn = rig.first("turn", look);
            int turnStop = rig.first("stop", turn);
            int idle = rig.first("eyes IDLE", turnStop);
            String lookDir = rig.what(look).replace("eyes LOOK ", "");
            String turnDir = rig.what(turn).replace("turn ", "");
            // Ticks at 1300 and 1550, then the 1600 edge reading stops the hop in that same event.
            boolean ordered = hop >= 0 && rig.countPrefix("hop", 0, 1600) == 2
                    && startle > stop && flinch > stop && back > Math.max(startle, flinch)
                    && backStop > back && look > backStop && turn > look && turnStop > turn && idle > turnStop;
            check(n, ordered && rig.timeOf(stop) == 1600 && rig.countPrefix("hop", 1600, 3700) == 0
                            && rig.count("back") == 1 && rig.count("startle") == 1
                            && lookDir.equals(turnDir) && rig.timeOf(turn) - rig.timeOf(look) >= 500
                            && rig.brain.state() == ExploreBrain.State.PAUSE && rig.violations.isEmpty(),
                    "stop@" + rig.timeOf(stop) + " state=" + rig.brain.state() + " " + rig.tail());
        });
        scenario("ae2_eyes_lead_the_turn_then_idle_on_the_hop", n -> {
            Rig rig = new Rig(tuning().turnChance(1.0).build(), CLEAR).started();
            rig.runUntil(2600);
            int look = rig.first("eyes LOOK", 0);
            int turn = rig.first("turn", 0);
            int turnStop = rig.first("stop", turn);
            int hop = rig.first("hop", 0);
            int idle = rig.first("eyes IDLE", turnStop);
            String lookDir = rig.what(look).replace("eyes LOOK ", "");
            String turnDir = rig.what(turn).replace("turn ", "");
            check(n, look >= 0 && turn > look && lookDir.equals(turnDir)
                            && rig.timeOf(turn) - rig.timeOf(look) >= 500
                            && rig.first("eyes", look + 1) > turn // eyes still leading during the turn
                            && hop > turnStop && idle > turnStop && rig.timeOf(idle) == rig.timeOf(hop)
                            && rig.violations.isEmpty(),
                    rig.tail());
        });
        scenario("ae3_no_readings_never_moves", n -> {
            Rig rig = new Rig(tuning().build(), t -> null).started();
            rig.runUntil(10000);
            check(n, rig.motions(0, 10001) == 0 && rig.count("eyes EYES_ONLY") >= 1
                            && rig.brain.state() == ExploreBrain.State.EYES_ONLY,
                    "state=" + rig.brain.state() + " " + rig.tail());
        });
        scenario("ae4_readings_stop_mid_hop", n -> {
            Rig rig = new Rig(tuning().build(), t -> (t <= 1400 || t >= 3000) ? clear(t) : null).started();
            rig.runUntil(2000);
            int stop = rig.first("stop", rig.first("hop", 0));
            ExploreBrain.State mid = rig.brain.state();
            rig.runUntil(5000);
            int still = rig.firstAfter("eyes EYES_ONLY", 1300);
            int idleAgain = rig.firstAfter("eyes IDLE", 1700);
            int hopAgain = rig.firstAfter("hop", 1700);
            // Readings back at 3000, 3100, 3200: available at 3200, then a full pause.
            check(n, rig.timeOf(stop) > 1400 && rig.timeOf(stop) <= 1700 && mid == ExploreBrain.State.EYES_ONLY
                            && rig.timeOf(still) == rig.timeOf(stop)
                            && rig.motions(1701, 4200) == 0 && rig.timeOf(idleAgain) == 3200
                            && rig.timeOf(hopAgain) == 4200 && rig.violations.isEmpty(),
                    "stop@" + rig.timeOf(stop) + " mid=" + mid + " idle@" + rig.timeOf(idleAgain)
                            + " hop@" + rig.timeOf(hopAgain) + " " + rig.tail());
        });
        scenario("ae5_lease_loss_mid_back_off", n -> {
            // Edge at 1600 mid-hop; back-off tick at 2000; lease lost at 2100, back at 3000.
            final Rig[] box = new Rig[1];
            Rig rig = new Rig(tuning().build(), t -> (t >= 1600 && t < 2500) ? edgeAhead(t) : clear(t));
            box[0] = rig;
            rig.at(2100, () -> box[0].brain.onLeaseChanged(false));
            rig.at(3000, () -> box[0].brain.onLeaseChanged(true));
            rig.started();
            rig.runUntil(2200);
            int back = rig.first("back", 0);
            int stop = rig.first("stop", back);
            ExploreBrain.State lost = rig.brain.state();
            rig.runUntil(4200);
            int idle = rig.firstAfter("eyes IDLE", 3000);
            int next = rig.firstMotionAfter(3001);
            check(n, rig.timeOf(back) == 2000 && rig.timeOf(stop) == 2100 && lost == ExploreBrain.State.EYES_ONLY
                            && rig.firstAfter("eyes EYES_ONLY", 2100) >= 0 && rig.motions(2101, 4000) == 0
                            && rig.timeOf(idle) == 3000 && rig.what(next).equals("hop")
                            && rig.timeOf(next) == 4000 && rig.violations.isEmpty(),
                    "back@" + rig.timeOf(back) + " stop@" + rig.timeOf(stop) + " lost=" + lost
                            + " next=" + rig.what(next) + "@" + rig.timeOf(next) + " " + rig.tail());
        });
        scenario("ae6_cpl2_never_retries_forward", n -> {
            // The controller refuses forward (CPL=2) while the mode's own thresholds see nothing.
            // Plain floor: the first refusal is a hiccup, retried once (owner-approved
            // 2026-09-25), but the retry's start sees CPL=2 still and turns away instead: no
            // forward command is ever sent against it.
            Rig rig = new Rig(tuning().build(), t -> t < 1600 ? clear(t) : cpl2(t));
            List<String> notes = traced(rig);
            rig.started();
            rig.runUntil(30000);
            int stop = rig.firstAfter("stop", 1600);
            check(n, rig.timeOf(stop) == 1600 && rig.countPrefix("hop", 1600, 30001) == 0
                            && noteAt(notes, "controller refused forward (CPL) on plain floor", 0) == 1600
                            && noteAt(notes, "hazard at start: CPL", 1601) == 2000
                            && rig.firstAfter("turn", 2000) > 0 && rig.violations.isEmpty(),
                    "stop@" + rig.timeOf(stop) + " hopsAfter=" + rig.countPrefix("hop", 1600, 30001) + " " + rig.tail());
        });
    }

    // ---- ExploreBrain: edges and errors ----

    private static void edgeScenarios() {
        scenario("hazard_during_a_turn_stops_the_turn", n -> {
            // turnChance 1: look at 1300, turn at 1800 for 500 ms; obstacle from 2000.
            Rig rig = new Rig(tuning().turnChance(1.0).build(), t -> t < 2000 ? clear(t) : obstacle(t)).started();
            rig.runUntil(2600);
            int turn = rig.first("turn", 0);
            int stop = rig.first("stop", turn);
            int startle = rig.first("startle", 0);
            int back = rig.first("back", 0);
            check(n, rig.timeOf(turn) == 1800 && rig.timeOf(stop) == 2000 && startle > stop
                            && rig.first("eyes FLINCH", 0) > stop && back > startle && rig.violations.isEmpty(),
                    "turn@" + rig.timeOf(turn) + " stop@" + rig.timeOf(stop) + " " + rig.tail());
        });
        scenario("hazard_at_hop_start_turns_away_instead", n -> {
            // Edge on the left appears while pausing; the hop would start at 1300.
            Rig rig = new Rig(tuning().build(), t -> t < 1200 ? clear(t) : edgeLeft(t)).started();
            rig.runUntil(2800);
            int look = rig.first("eyes LOOK", 0);
            int turn = rig.first("turn", 0);
            check(n, rig.count("hop") == 0 && rig.count("back") == 0 && rig.count("startle") == 0
                            && rig.what(look).equals("eyes LOOK RIGHT") && rig.timeOf(look) == 1300
                            && rig.what(turn).equals("turn RIGHT") && rig.timeOf(turn) == 1800,
                    rig.tail());
        });
        scenario("escape_turn_steers_away_from_the_hazard_side", n -> {
            Rig rig = new Rig(tuning().build(), t -> t < 1600 ? clear(t) : edgeRight(t)).started();
            rig.runUntil(3700);
            int look = rig.first("eyes LOOK", 0);
            int turn = rig.first("turn", 0);
            check(n, rig.timeOf(rig.firstAfter("stop", 1600)) == 1600 && rig.what(look).equals("eyes LOOK LEFT")
                            && rig.what(turn).equals("turn LEFT") && rig.violations.isEmpty(),
                    rig.tail());
        });
        scenario("cornered_only_after_three_failed_escapes", n -> {
            // Edge in view from 1600 whichever way he turns: one startle, then escape
            // sweeps (3 s each here) that never find a clear way, alternating direction;
            // the third failure makes him rest, with no motion, then try a wider turn.
            Rig rig = new Rig(tuning().build(), t -> t < 1600 ? clear(t) : edgeAhead(t)).started();
            rig.runUntil(20000);
            ExploreBrain.State resting = rig.brain.state();
            int rest = rig.first("eyes RESTING", 0);
            List<String> turns = new ArrayList<String>();
            for (Event e : rig.log) {
                if (e.what.startsWith("turn") && e.t < rig.timeOf(rest)) {
                    turns.add(e.what);
                }
            }
            long restAt = rig.timeOf(rest);
            rig.runUntil(restAt + 30000 + 2000);
            int wider = rig.firstAfter("turn", restAt + 30000);
            check(n, resting == ExploreBrain.State.CORNERED && turns.size() == 3
                            && !turns.get(0).equals(turns.get(1)) && !turns.get(1).equals(turns.get(2))
                            && rig.count("startle") == 1 && rig.count("back") == 1
                            && rig.motions(restAt + 1, restAt + 30000) == 0 && wider >= 0,
                    "state=" + resting + " turns=" + turns + " rest@" + restAt + " " + rig.tail());
        });
        scenario("wall_clearing_mid_escape_ends_it_without_resting", n -> {
            // An obstacle ahead until the escape turn has swept a while: he keeps turning
            // (no second startle) until it reads clear, then pauses and drives on.
            Rig rig = new Rig(tuning().build(), t -> t < 1600 || t >= 4000 ? clear(t) : obstacle(t)).started();
            rig.runUntil(8000);
            int turn = rig.firstAfter("turn", 1600);
            int stop = rig.first("stop", turn);
            check(n, rig.count("startle") == 1 && rig.timeOf(stop) >= 4300 && rig.timeOf(stop) < 4500
                            && rig.firstAfter("hop", rig.timeOf(stop)) >= 0 && rig.count("eyes RESTING") == 0
                            && rig.violations.isEmpty(),
                    "turn@" + rig.timeOf(turn) + " stop@" + rig.timeOf(stop) + " " + rig.tail());
        });
        scenario("escapes_keep_one_direction_until_he_drives_off", n -> {
            // Edge on the right (escape LEFT); then, before a leg completes, an edge on the
            // left, which alone would say turn RIGHT: he keeps escaping LEFT.
            Rig rig = new Rig(tuning().build(), t -> {
                if (t >= 1600 && t < 2600) {
                    return edgeRight(t);
                }
                if (t >= 4800 && t < 5800) {
                    return edgeLeft(t);
                }
                return clear(t);
            }).started();
            rig.runUntil(8000);
            List<String> turns = new ArrayList<String>();
            for (Event e : rig.log) {
                if (e.what.startsWith("turn")) {
                    turns.add(e.what);
                }
            }
            check(n, rig.count("startle") == 2 && turns.size() >= 2
                            && turns.get(0).equals("turn LEFT") && turns.get(1).equals("turn LEFT"),
                    "turns=" + turns + " " + rig.tail());
        });
        scenario("stalled_wheels_mid_hop_stop_startle_and_escape", n -> {
            // A 5 s leg from 1300; the wheels turn until 2000, then stand still though
            // forward keeps going out: after a second with no progress he stops.
            // The feed's wheels never move again, so the post-stall wait would find no recovery (the
            // recover_ scenarios): this is the escape as it runs once the board is back.
            Rig rig = new Rig(tuning().hopTicks(20).stallRecoverOff().build(), t -> wheels(t, Math.min(t, 2000))).started();
            rig.runUntil(5500);
            int stop = rig.firstAfter("stop", 1301);
            check(n, rig.timeOf(stop) >= 2900 && rig.timeOf(stop) <= 3100 && rig.count("startle") == 1
                            && rig.count("back") == 3 && rig.firstAfter("turn", rig.timeOf(stop)) >= 0,
                    "stop@" + rig.timeOf(stop) + " " + rig.tail());
        });
        scenario("repeated_stalls_back_off_further_and_turn_more_each_time", n -> {
            // The wheels never turn: every leg stalls. Each stall backs off 3 ticks and
            // turns longer than the last (2 s, 3 s, 4 s here), one way, one "whoa".
            // Recovery off: the feed's wheels never move, so the wait would call a real jam.
            Rig rig = new Rig(tuning().hopTicks(20).stallRecoverOff().stallEscape(3, 2000, 1000).cap(8, 20000, 30000)
                    .escape(300, 6000, 3, 60000).build(),
                    t -> wheels(t, 0)).started();
            rig.runUntil(30000);
            List<Long> turnMs = new ArrayList<Long>();
            List<String> dirs = new ArrayList<String>();
            for (int i = 0; i < rig.log.size(); i++) {
                if (rig.log.get(i).what.startsWith("turn")) {
                    dirs.add(rig.log.get(i).what);
                    turnMs.add(rig.timeOf(rig.first("stop", i)) - rig.log.get(i).t);
                }
            }
            check(n, turnMs.size() >= 3 && turnMs.get(0) >= 2000 && turnMs.get(1) >= 3000 && turnMs.get(2) >= 4000
                            && turnMs.get(1) > turnMs.get(0) && turnMs.get(2) > turnMs.get(1)
                            && dirs.get(0).equals(dirs.get(1)) && dirs.get(1).equals(dirs.get(2))
                            && rig.count("startle") == 1 && rig.count("back") >= 9,
                    "turnMs=" + turnMs + " dirs=" + dirs + " startles=" + rig.count("startle")
                            + " backs=" + rig.count("back") + " " + rig.tail());
        });
        scenario("turning_wheels_never_read_as_stalled", n -> {
            Rig rig = new Rig(tuning().hopTicks(20).build(), t -> wheels(t, t)).started();
            rig.runUntil(6500);
            check(n, rig.count("startle") == 0 && rig.countPrefix("hop", 1300, 6300) == 20
                            && rig.timeOf(rig.firstAfter("stop", 1301)) == 6300,
                    rig.tail());
        });
        scenario("no_wheel_data_never_reads_as_stalled", n -> {
            Rig rig = new Rig(tuning().hopTicks(20).build(), CLEAR).started();
            rig.runUntil(6500);
            check(n, rig.count("startle") == 0 && rig.timeOf(rig.firstAfter("stop", 1301)) == 6300, rig.tail());
        });
        scenario("flapping_readings_do_not_resume_driving", n -> {
            // Solid until 500, then two readings every 500 ms (never three in a row), solid from 10000.
            Rig rig = new Rig(tuning().build(), t -> {
                if (t <= 500 || t >= 10000) {
                    return clear(t);
                }
                return (t % 500 == 0 || t % 500 == 100) ? clear(t) : null;
            }).started();
            rig.runUntil(10000);
            ExploreBrain.State flapping = rig.brain.state();
            int motionsWhileFlapping = rig.motions(0, 10001);
            rig.runUntil(11500);
            int hop = rig.firstAfter("hop", 10000);
            check(n, flapping == ExploreBrain.State.EYES_ONLY && motionsWhileFlapping == 0
                            && rig.timeOf(hop) == 11200 && rig.violations.isEmpty(),
                    "state=" + flapping + " motions=" + motionsWhileFlapping + " hop@" + rig.timeOf(hop)
                            + " " + rig.tail());
        });
        scenario("uncalibrated_brain_never_moves", n -> {
            Rig rig = new Rig(tuning().calibration(null).build(), CLEAR).started();
            rig.runUntil(10000);
            check(n, rig.motions(0, 10001) == 0 && rig.count("eyes EYES_ONLY") >= 1
                            && rig.brain.state() == ExploreBrain.State.EYES_ONLY,
                    "state=" + rig.brain.state() + " " + rig.tail());
        });
        scenario("frozen_tof_mid_run_stops_the_hop", n -> {
            // tof pinned at 300 from the first reading (t=100) with a 1500 ms window: frozen
            // at 1600, which lands inside the first hop (1300..2050).
            Rig rig = new Rig(tuning().frozenTofWindowMs(1500).build(),
                    t -> new SensorReading(t, 300, 100, 100, null, false)).started();
            rig.runUntil(5000);
            int stop = rig.firstAfter("stop", 1300);
            check(n, rig.countPrefix("hop", 1300, 1600) == 2 && rig.timeOf(stop) == 1600
                            && rig.motions(1601, 5001) == 0 && rig.brain.state() == ExploreBrain.State.EYES_ONLY,
                    "stop@" + rig.timeOf(stop) + " " + rig.tail());
        });
        scenario("no_lease_never_moves", n -> {
            Rig rig = new Rig(tuning().build(), CLEAR);
            rig.brain.start();
            rig.runUntil(10000);
            check(n, rig.motions(0, 10001) == 0 && rig.count("eyes EYES_ONLY") >= 1
                            && rig.brain.state() == ExploreBrain.State.EYES_ONLY,
                    "state=" + rig.brain.state() + " " + rig.tail());
        });
    }

    // ---- ExploreBrain: happy path and integration ----

    private static void integrationScenarios() {
        scenario("continuous_legs_vary_in_length_within_range", n -> {
            // Legs of continuous driving: every tick of a leg is resent without a stop in
            // between, and each leg's length is drawn from hopTicks..hopTicksMax.
            Rig rig = new Rig(tuning().hopTicks(4, 12).turnChance(0.7).pauseMs(300, 700).build(), CLEAR)
                    .started();
            rig.runUntil(180000);
            check(n, rig.legLengths.size() >= 3 && rig.minHopTicks >= 4 && rig.maxHopTicks <= 12
                            && rig.count("startle") == 0 && rig.violations.isEmpty(),
                    "legs=" + rig.legLengths + " min=" + rig.minHopTicks + " max=" + rig.maxHopTicks
                            + " " + rig.tail());
        });
        scenario("wander_cycle_hops_and_turns_only_on_fresh_clear_readings", n -> {
            Rig rig = new Rig(tuning().turnChance(0.4).pauseMs(800, 2500).turnMs(300, 900).build(), CLEAR)
                    .started();
            rig.runUntil(60000);
            // Every turn is led by a LOOK in the same direction at least lookLeadMs earlier.
            boolean led = true;
            for (int i = rig.first("turn", 0); i >= 0; i = rig.first("turn", i + 1)) {
                int look = -1;
                for (int j = i - 1; j >= 0; j--) {
                    if (rig.what(j).startsWith("eyes LOOK")) {
                        look = j;
                        break;
                    }
                }
                led &= look >= 0 && rig.what(look).endsWith(rig.what(i).replace("turn ", ""))
                        && rig.timeOf(i) - rig.timeOf(look) >= 500;
            }
            check(n, rig.count("hop") >= 10 && rig.countPrefix("turn", 0, 60001) >= 2 && rig.count("back") == 0
                            && rig.count("startle") == 0 && rig.maxHopTicks == 3 && led
                            && rig.violations.isEmpty(),
                    "hops=" + rig.count("hop") + " turns=" + rig.countPrefix("turn", 0, 60001) + " maxTicks=" + rig.maxHopTicks
                            + " led=" + led + " " + rig.tail());
        });
        scenario("lease_loss_reported_inside_a_motor_call", n -> {
            // The drive adapter drops a command it has no lease for and tells the brain
            // from inside that same call (U5).
            Rig rig = new Rig(tuning().build(), CLEAR).started();
            rig.loseLeaseInsideHop = true;
            rig.runUntil(3000);
            int hop = rig.first("hop", 0);
            int stop = rig.first("stop", hop);
            check(n, rig.count("hop") == 1 && rig.timeOf(stop) == rig.timeOf(hop)
                            && rig.brain.state() == ExploreBrain.State.EYES_ONLY && !rig.moving
                            && rig.firstAfter("eyes EYES_ONLY", 1300) >= 0,
                    "state=" + rig.brain.state() + " " + rig.tail());
        });
        scenario("shutdown_stops_and_goes_inert", n -> {
            final Rig[] box = new Rig[1];
            Rig rig = new Rig(tuning().build(), CLEAR);
            box[0] = rig;
            rig.at(1400, () -> box[0].brain.shutdown());
            rig.started();
            rig.runUntil(1400);
            int stop = rig.firstAfter("stop", 1400);
            int size = rig.log.size();
            rig.brain.onLeaseChanged(true);
            rig.runUntil(8000);
            check(n, rig.timeOf(stop) == 1400 && rig.brain.state() == ExploreBrain.State.STOPPED
                            && rig.log.size() == size && !rig.moving,
                    "stop@" + rig.timeOf(stop) + " state=" + rig.brain.state() + " " + rig.tail());
        });
    }

    // ---- ExploreBrain: camera curiosity (camera curiosity plan U5) ----
    //
    // With curiosity due at once, the first pause ends at 1300 and the scan opens
    // the camera there. Looks arrive every 500 ms, captured 200 ms earlier, so the
    // first one that counts (captured at or after 1300 + 200 settle) is the one
    // captured at 1800 and delivered at 2000.

    private static ExploreTuning.Builder curious() {
        return tuning()
                .curiosityMs(0, 0)
                .scan(2, 500)
                .lookTiming(200, 3000, 2000)
                .cameraBackoffMs(10000)
                .facing(0.25f, 800, 3)
                .approach(2, 4, 500, 2)
                .reactionMs(500)
                .peopleCooldownMs(20000)
                .recognition(0.35f, 0.2f)
                .fill(0.7f, 0.4f);
    }

    /** A box by centre and size, as frame fractions (cx, cy in 0..1). */
    static Detection box(String label, float score, float cx, float cy, float w, float h) {
        return new Detection(label, score, cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
    }

    static List<Detection> list(Detection... ds) {
        return new ArrayList<Detection>(java.util.Arrays.asList(ds));
    }

    /** A plant off to the right until he turns right once, growing 0.15 of the frame's height per forward tick. */
    private static final Vision PLANT = (rig, t) -> {
        float cx = rig.count("turn RIGHT") > 0 ? 0.5f : 0.8f;
        return list(box("plant", 0.8f, cx, 0.6f, 0.2f, Math.min(1f, 0.3f + 0.15f * rig.count("hop"))));
    };

    /** When the brain first entered s at or after from (the state log), else -1. */
    static long entered(Rig rig, ExploreBrain.State s, long from) {
        for (Event e : rig.stateLog) {
            if (e.t >= from && e.what.equals(s.name())) {
                return e.t;
            }
        }
        return -1;
    }

    /** Index of the first event starting with prefix at or after index from whose time is at least t. */
    private static int firstFrom(Rig rig, String prefix, int from, long t) {
        for (int i = Math.max(0, from); i < rig.log.size(); i++) {
            if (rig.log.get(i).t >= t && rig.log.get(i).what.startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    private static SensorReading closeLowTof(long t) {
        return new SensorReading(t, 50 + jitter(t), 100, 100, null, false);
    }

    private static SensorReading refusedLowTof(long t) {
        return new SensorReading(t, 150 + jitter(t), 100, 100, 2, false);
    }

    private static void curiosityScenarios() {
        scenario("ae1_new_thing_faced_approached_inspected_then_disappointed", n -> {
            Rig rig = new Rig(curious().build(), CLEAR, PLANT, true).started();
            rig.runUntil(30000);
            int open = rig.first("camera open", 0);
            int stare = rig.first("eyes STARE", open);
            int turn = rig.first("turn RIGHT", stare);
            int hop = rig.first("hop", turn);
            int curiousAt = rig.first("react curious", hop);
            int thinking = rig.first("react thinking", curiousAt);
            int name = rig.first("name plant", thinking);
            // The camera stays open while he roams between the stops (explore nav plan U4).
            long scan2 = entered(rig, ExploreBrain.State.SCAN, rig.timeOf(name));
            int sad = rig.first("react disappointed", name);
            long end2 = entered(rig, ExploreBrain.State.PAUSE, rig.timeOf(sad));
            boolean ordered = open >= 0 && stare > open && turn > stare && hop > turn && curiousAt > hop
                    && thinking > curiousAt && name > thinking && scan2 > rig.timeOf(name)
                    && sad > name && rig.timeOf(sad) >= scan2 && end2 > rig.timeOf(sad);
            // Eyes lead the turn toward it (R5), and no motion toward it the second time (R10).
            boolean led = rig.timeOf(turn) - rig.timeOf(stare) >= 500;
            boolean stayed = ordered && rig.motions(scan2, end2) == 0;
            check(n, ordered && led && stayed && rig.brain.seen().contains("plant") && rig.count("camera close") == 0
                            && rig.count("name plant") == 1 && rig.count("startle") == 0 && rig.violations.isEmpty(),
                    "open=" + open + " stare=" + stare + " turn=" + turn + " hop=" + hop + " curious=" + curiousAt
                            + " name=" + name + " scan2=" + scan2 + " sad=" + sad + " " + rig.tail());
        });
        scenario("ae2_frame_fill_arrives_without_the_sensor", n -> {
            Vision ahead = (rig, t) -> list(box("cup", 0.8f, 0.5f, 0.6f, 0.2f,
                    Math.min(1f, 0.3f + 0.15f * rig.count("hop"))));
            Rig rig = new Rig(curious().build(), CLEAR, ahead, true).started();
            rig.runUntil(9000);
            int hop = rig.first("hop", 0);
            int arrive = rig.first("react curious", hop);
            check(n, hop >= 0 && arrive > hop && rig.count("startle") == 0 && rig.count("back") == 0
                            && rig.count("name cup") == 1 && rig.violations.isEmpty(),
                    rig.tail());
        });
        scenario("ae3_edge_during_approach_stops_startles_and_abandons", n -> {
            Vision ahead = (rig, t) -> list(box("cup", 0.8f, 0.5f, 0.6f, 0.2f, 0.3f));
            Rig rig = new Rig(curious().build(), t -> t <= 2000 ? clear(t) : t <= 2600 ? edgeAhead(t) : clear(t),
                    ahead, true).started();
            rig.runUntil(6000);
            int hop = rig.first("hop", 0);
            int stop = rig.first("stop", hop);
            int startle = rig.first("startle", stop);
            int back = rig.first("back", startle);
            check(n, hop >= 0 && rig.timeOf(hop) == 2000 && rig.timeOf(stop) == 2100 && startle > stop
                            && back > startle && rig.count("react curious") == 0 && rig.count("name cup") == 0
                            // The stop is over at the hazard; escaping, the camera stays open (U4).
                            && entered(rig, ExploreBrain.State.STARTLE, 2100) == 2100
                            && rig.count("camera close") == 0 && rig.violations.isEmpty(),
                    "hop@" + rig.timeOf(hop) + " stop@" + rig.timeOf(stop) + " " + rig.tail());
        });
        scenario("ae4_person_greeted_then_ignored_during_cooldown", n -> {
            Vision room = (rig, t) -> list(
                    box("person", 0.9f, 0.5f, 0.5f, 0.5f, 0.8f),
                    box("cup", 0.6f, 0.5f, 0.8f, 0.1f, Math.min(1f, 0.1f + 0.2f * rig.count("hop"))));
            Rig rig = new Rig(curious().build(), CLEAR, room, true).started();
            rig.runUntil(30000);
            int hello = rig.first("react delighted", 0);
            int name = rig.first("name person", hello);
            int again = rig.first("react delighted", name);
            int cup = rig.first("name cup", again);
            check(n, hello >= 0 && name > hello && again > name && cup > again
                            // Once within the 20 s cool-down; greeting again after it is fine.
                            && rig.countPrefix("name person", 0, rig.timeOf(name) + 20000) == 1
                            && rig.motions(0, rig.timeOf(again)) == 0
                            && !rig.brain.seen().contains("person") && rig.violations.isEmpty(),
                    "hello=" + hello + " name=" + name + " cup=" + cup + " " + rig.tail());
        });
        scenario("ae5_camera_unavailable_wanders_as_before", n -> {
            Rig rig = new Rig(curious().build(), CLEAR, PLANT, false).started();
            rig.runUntil(10000);
            check(n, rig.count("camera open") == 0 && rig.count("hop") >= 6 && rig.countPrefix("react", 0, 10001) == 0
                            && rig.violations.isEmpty(),
                    rig.tail());
        });
        scenario("ae6_camera_follows_the_camera_rule_through_stops_and_lease_loss", n -> {
            Rig rig = new Rig(curious().build(), CLEAR, PLANT, true).started();
            rig.at(12000, () -> rig.brain.onLeaseChanged(false));
            rig.at(15000, () -> rig.brain.onLeaseChanged(true));
            rig.runUntil(30000);
            // The rig records a violation whenever the camera breaks the camera rule
            // (cameraRuleBreak): open roaming and curious, closed without the lease.
            check(n, rig.count("camera open") == 2 && rig.count("camera close") == 1
                            && rig.timeOf(rig.first("camera close", 0)) == 12000
                            && rig.statesSeen.contains(ExploreBrain.State.INSPECT) && rig.violations.isEmpty(),
                    rig.tail());
        });
        scenario("target_lost_during_approach_gives_up", n -> {
            Vision fleeting = (rig, t) -> rig.count("hop") == 0 ? list(box("cat", 0.8f, 0.5f, 0.6f, 0.2f, 0.3f)) : list();
            Rig rig = new Rig(curious().build(), CLEAR, fleeting, true).started();
            rig.runUntil(8000);
            int hop = rig.first("hop", 0);
            long end = entered(rig, ExploreBrain.State.PAUSE, rig.timeOf(hop));
            check(n, hop >= 0 && end > rig.timeOf(hop) && rig.countPrefix("react", 0, 8001) == 0
                            && rig.countPrefix("hop", rig.timeOf(hop), end) == 2 && rig.violations.isEmpty(),
                    rig.tail());
        });
        scenario("renamed_target_is_kept_by_overlap", n -> {
            // The same box is called "tv" first, then "monitor": still the tv he is approaching.
            Vision flicker = (rig, t) -> {
                float h = Math.min(1f, 0.3f + 0.15f * rig.count("hop"));
                return list(box(rig.count("hop") == 0 ? "tv" : "monitor", 0.8f, 0.5f, 0.6f, 0.3f, h));
            };
            Rig rig = new Rig(curious().build(), CLEAR, flicker, true).started();
            rig.runUntil(6000); // the first curiosity stop only
            check(n, rig.count("name tv") == 1 && rig.count("name monitor") == 0 && rig.brain.seen().contains("tv")
                            && rig.violations.isEmpty(),
                    rig.tail());
        });
        scenario("different_thing_elsewhere_is_not_the_target", n -> {
            Vision swap = (rig, t) -> rig.count("hop") == 0
                    ? list(box("tv", 0.8f, 0.5f, 0.6f, 0.2f, 0.3f))
                    : list(box("monitor", 0.8f, 0.1f, 0.2f, 0.1f, 0.1f));
            Rig rig = new Rig(curious().build(), CLEAR, swap, true).started();
            rig.runUntil(8000);
            check(n, rig.countPrefix("name", 0, 8001) == 0 && rig.countPrefix("react", 0, 8001) == 0
                            && rig.violations.isEmpty(),
                    rig.tail());
        });
        scenario("unsure_sighting_is_puzzled_and_stays", n -> {
            Vision blurry = (rig, t) -> list(box("cup", 0.25f, 0.5f, 0.6f, 0.2f, 0.3f));
            Rig rig = new Rig(curious().build(), CLEAR, blurry, true).started();
            rig.runUntil(4000);
            long scan = entered(rig, ExploreBrain.State.SCAN, 0);
            int puzzled = rig.first("react puzzled", 0);
            long end = entered(rig, ExploreBrain.State.PAUSE, rig.timeOf(puzzled));
            check(n, scan >= 0 && rig.timeOf(puzzled) >= scan && end > rig.timeOf(puzzled)
                            && rig.motions(scan, end) == 0
                            && rig.count("name cup") == 0 && rig.violations.isEmpty(),
                    rig.tail());
        });
        scenario("sensor_arrival_ignored_in_leg_grace", n -> {
            Vision ahead = (rig, t) -> list(box("cup", 0.8f, 0.5f, 0.6f, 0.2f, 0.3f));
            Rig rig = new Rig(curious().build(), t -> t > 2000 && t <= 2400 ? closeLowTof(t) : clear(t),
                    ahead, true).started();
            rig.runUntil(2600);
            check(n, rig.countPrefix("hop", 2000, 2500) == 2 && rig.countPrefix("react", 0, 2600) == 0
                            && rig.count("startle") == 0 && rig.brain.state() == ExploreBrain.State.APPROACH,
                    "state=" + rig.brain.state() + " " + rig.tail());
        });
        scenario("sensor_arrival_after_grace_arrives", n -> {
            // A 3-tick leg (2000-2750) outlasts the 500 ms grace: the close reading at 2500
            // arrives mid-leg, before the leg would have ended on its own.
            Vision ahead = (rig, t) -> list(box("cup", 0.8f, 0.5f, 0.6f, 0.2f, 0.3f));
            Rig rig = new Rig(curious().approach(3, 4, 500, 2).build(), t -> t >= 2500 ? closeLowTof(t) : clear(t),
                    ahead, true).started();
            rig.runUntil(4000);
            int stop = rig.firstAfter("stop", 2001);
            int react = rig.first("react curious", 0);
            check(n, rig.timeOf(stop) == 2500 && rig.timeOf(react) == 2500 && rig.countPrefix("hop", 2500, 4001) == 0
                            && rig.count("startle") == 0 && rig.count("back") == 0,
                    "stop@" + rig.timeOf(stop) + " react@" + rig.timeOf(react) + " " + rig.tail());
        });
        scenario("edge_at_leg_start_refuses_without_driving", n -> {
            Vision ahead = (rig, t) -> list(box("cup", 0.8f, 0.5f, 0.6f, 0.2f, 0.3f));
            Rig rig = new Rig(curious().build(), t -> t >= 1900 && t <= 2000 ? edgeAhead(t) : clear(t),
                    ahead, true).started();
            rig.runUntil(3200);
            int turn = rig.first("turn", 0);
            check(n, rig.countPrefix("hop", 0, 3201) == 0 && turn >= 0 && rig.countPrefix("react", 0, 3201) == 0
                            && rig.violations.isEmpty(),
                    rig.tail());
        });
        scenario("close_at_leg_start_arrives_without_driving", n -> {
            Vision ahead = (rig, t) -> list(box("cup", 0.8f, 0.5f, 0.6f, 0.2f, 0.3f));
            Rig rig = new Rig(curious().build(), t -> t >= 1900 && t <= 2100 ? closeLowTof(t) : clear(t),
                    ahead, true).started();
            rig.runUntil(3000);
            check(n, rig.countPrefix("hop", 0, 3001) == 0 && rig.timeOf(rig.first("react curious", 0)) == 2000
                            && rig.count("startle") == 0 && rig.violations.isEmpty(),
                    rig.tail());
        });
        scenario("hazard_at_curiosity_turn_start_refuses", n -> {
            // The plant is off to the right: eyes lead from 2000, the turn is due at 2500,
            // but an obstacle is ahead then, so he turns away (after a new look lead) instead.
            Rig rig = new Rig(curious().build(), t -> t >= 2400 && t <= 2500 ? obstacle(t) : clear(t),
                    PLANT, true).started();
            rig.runUntil(3200);
            int turn = rig.first("turn", 0);
            int look = rig.firstAfter("eyes LOOK", 2500);
            check(n, rig.timeOf(turn) >= 3000 && look >= 0 && rig.timeOf(look) == 2500
                            && rig.countPrefix("react", 0, 3201) == 0 && rig.violations.isEmpty(),
                    "turn@" + rig.timeOf(turn) + " " + rig.tail());
        });
        scenario("close_but_off_centre_arrives_instead_of_turning", n -> {
            // After the first leg the cup has drifted right and he is touching it: arrive,
            // rather than turn toward it and read the ir flag as a hazard.
            Vision drift = (rig, t) -> list(box("cup", 0.8f, rig.count("hop") > 0 ? 0.9f : 0.5f, 0.6f, 0.2f, 0.3f));
            Rig rig = new Rig(curious().build(), t -> t >= 2600 ? closeLowTof(t) : clear(t), drift, true).started();
            rig.runUntil(5000);
            check(n, rig.count("react curious") == 1 && rig.countPrefix("turn", 0, 5001) == 0
                            && rig.count("startle") == 0 && rig.violations.isEmpty(),
                    rig.tail());
        });
        scenario("stale_looks_end_the_stop_without_turning_curiosity_off", n -> {
            // Looks keep arriving but all were captured before the stop settled: a slow
            // detector, not a dead camera. The stop ends, and the next one still comes.
            Rig rig = new Rig(curious().build(), CLEAR, (r, t) -> list(), true);
            rig.staleLooks = true;
            rig.started();
            rig.runUntil(9000);
            long end = entered(rig, ExploreBrain.State.PAUSE, entered(rig, ExploreBrain.State.SCAN, 0));
            long next = entered(rig, ExploreBrain.State.SCAN, end);
            check(n, entered(rig, ExploreBrain.State.SCAN, 0) == 1300 && end == 4300 && next > end
                            && !rig.brain.cameraBackedOff() && rig.count("camera close") == 0 && rig.violations.isEmpty(),
                    "end@" + end + " next@" + next + " " + rig.tail());
        });
        scenario("curiosity_requested_now_starts_at_the_next_pause_end", n -> {
            Rig rig = new Rig(curious().curiosityMs(100000, 100000).build(), CLEAR, PLANT, true).started();
            rig.at(1200, () -> rig.brain.requestCuriosity());
            rig.runUntil(1400);
            check(n, entered(rig, ExploreBrain.State.SCAN, 0) == 1300 && rig.count("hop") == 0,
                    rig.tail());
        });
        scenario("cpl2_in_leg_grace_stops_the_leg_and_looks_again", n -> {
            Vision ahead = (rig, t) -> list(box("cup", 0.8f, 0.5f, 0.6f, 0.2f, 0.3f));
            Rig rig = new Rig(curious().build(), t -> t == 2100 ? refusedLowTof(t) : clear(t), ahead, true).started();
            rig.runUntil(2600);
            int stop = rig.firstAfter("stop", 2000);
            // No forward resent after the refusal; the next leg waits for a new look (captured 2300, at 2500).
            check(n, rig.timeOf(stop) == 2100 && rig.countPrefix("hop", 2101, 2500) == 0
                            && rig.countPrefix("react", 0, 2600) == 0 && rig.count("startle") == 0
                            && rig.brain.state() == ExploreBrain.State.APPROACH,
                    "stop@" + rig.timeOf(stop) + " state=" + rig.brain.state() + " " + rig.tail());
        });
        scenario("lease_loss_mid_approach_stops_and_closes_the_camera", n -> {
            Vision ahead = (rig, t) -> list(box("cup", 0.8f, 0.5f, 0.6f, 0.2f, 0.3f));
            Rig rig = new Rig(curious().build(), CLEAR, ahead, true).started();
            rig.at(2100, () -> rig.brain.onLeaseChanged(false));
            rig.runUntil(3000);
            int stop = rig.firstAfter("stop", 2100);
            int close = rig.firstAfter("camera close", 2100);
            check(n, rig.timeOf(stop) == 2100 && rig.timeOf(close) == 2100
                            && rig.brain.state() == ExploreBrain.State.EYES_ONLY && rig.violations.isEmpty(),
                    rig.tail());
        });
        scenario("camera_without_looks_turns_curiosity_off", n -> {
            Vision dark = (rig, t) -> null;
            Rig rig = new Rig(curious().build(), CLEAR, dark, true);
            List<String> notes = traced(rig);
            rig.started();
            rig.runUntil(12000);
            int close = rig.first("camera close", 0);
            // The stop's look is due by 1300 + 3000. That first miss keeps the camera open and
            // retries the stop 3 s later, but roaming's own deadline (2 s with no look) comes
            // first: the camera closes at 6300 for roaming's back-off, with no stop within it.
            check(n, notes.contains("4300 camera gave no look in time") && rig.timeOf(close) == 6300
                            && notes.contains("6300 camera gave no look in time while roaming; camera off for 10000 ms")
                            && rig.count("camera open") == 1 && rig.countPrefix("hop", 6300, 12001) >= 3
                            && entries(rig, ExploreBrain.State.SCAN).size() == 1 && rig.violations.isEmpty(),
                    notes + " " + rig.tail());
        });
    }

    // ---- ExploreBrain: Claude picks and speaks (explore on Claude plan U4) ----
    //
    // Three scan looks: the camera opens at 1300, looks arrive at 2000, 3000 and
    // 4000 (captured 1800, 2800, 3800) with a 500 ms scan turn after each of the
    // first two, so the look request goes out at 4000 and the fake Claude answers
    // claudeDelayMs later. The look a frame was captured in is the number of turns
    // before its capture time.

    private static ExploreTuning.Builder claudeTuning() {
        return curious()
                .scan(3, 500)
                .ask(2, 4000)
                .meet(4000, 6000, 2000)
                .sayTimeoutMs(6000);
    }

    /** Which scan look a capture at t belongs to: 0, 1, 2 during the scan; 3+ once he turns after it. */
    private static int lookAt(Rig rig, long t) {
        return rig.countPrefix("turn", 0, t);
    }

    private static CuriosityPort.Answer pick(int frame, String label, CuriosityPort.Kind kind, String line,
                                             float cx, float cy, float w, float h) {
        return CuriosityPort.Answer.pick(frame, box(label, 1f, cx, cy, w, h), kind, line);
    }

    /** A cup straight ahead in the given look (and in every look after the scan), growing as he drives. */
    private static Vision cupIn(int look) {
        return (rig, t) -> {
            int at = lookAt(rig, t);
            if (at == look || at >= 3) {
                return list(box("cup", 0.8f, 0.5f, 0.6f, 0.2f, Math.min(1f, 0.3f + 0.15f * rig.count("hop"))));
            }
            return list();
        };
    }

    private static final Claude CAT_RIGHT_IN_FRAME_3 = (rig, req, nth) ->
            pick(2, "cat", CuriosityPort.Kind.ANIMAL, "Hello kitty, what a fluffy tail!", 0.8f, 0.5f, 0.2f, 0.3f);

    private static final Claude MUG_ON_THE_CUP = (rig, req, nth) ->
            pick(2, "mug", CuriosityPort.Kind.OTHER, "Ooh, a mug! Is that hot chocolate?", 0.52f, 0.6f, 0.22f, 0.32f);

    private static void claudeScenarios() {
        scenario("claude_ae1_turns_to_the_picked_frame_and_offset_and_speaks_without_driving", n -> {
            // A chair on the left in look 1; the detector sees nothing in look 3, where Claude sees a cat on the right.
            Vision room = (rig, t) -> lookAt(rig, t) == 0 ? list(box("chair", 0.8f, 0.2f, 0.6f, 0.2f, 0.4f)) : list();
            Rig rig = new Rig(claudeTuning().build(), CLEAR, room, true, CAT_RIGHT_IN_FRAME_3).started();
            rig.runUntil(9000);
            int ask = rig.first("ask", 0);
            int thinking = rig.first("eyes THINKING", 0);
            int lead = rig.first("eyes LOOK RIGHT", ask);
            int turn = rig.first("turn RIGHT", lead);
            int stop = rig.first("stop", turn);
            int say = rig.first("say Hello kitty", stop);
            long turnMs = rig.timeOf(stop) - rig.timeOf(turn);
            boolean ordered = ask >= 0 && thinking >= 0 && rig.timeOf(thinking) == rig.timeOf(ask)
                    && lead > ask && turn > lead && stop > turn && say > stop;
            check(n, ordered && rig.timeOf(ask) == 4000 && rig.asks.get(0).frames.size() == 3
                            // Frame 3's own heading (no heading turn) plus 0.6 x 800 ms for the right offset.
                            && turnMs >= 470 && turnMs <= 500
                            && rig.countPrefix("hop", 0, rig.timeOf(say) + 3000) == 0
                            && rig.countPrefix("camera open", rig.timeOf(ask), rig.timeOf(say) + 1) == 0
                            && rig.countPrefix("name", 0, 9001) == 0 && rig.countPrefix("react", 0, 9001) == 0
                            && rig.violations.isEmpty(),
                    "ask=" + ask + " lead=" + lead + " turn=" + turn + " turnMs=" + turnMs + " say=" + say + " "
                            + rig.tail());
        });
        scenario("claude_pick_on_a_detector_box_of_the_same_kind_is_approached_before_speaking", n -> {
            Rig rig = new Rig(claudeTuning().build(), CLEAR, cupIn(2), true, MUG_ON_THE_CUP).started();
            rig.runUntil(12000);
            int ask = rig.first("ask", 0);
            int open = rig.first("camera open", ask);
            int hop = rig.first("hop", open);
            int say = rig.first("say Ooh, a mug", hop);
            check(n, ask >= 0 && open > ask && hop > open && say > hop
                            && rig.timeOf(rig.firstAfter("camera close", rig.timeOf(hop))) == rig.timeOf(say)
                            && rig.countPrefix("hop", rig.timeOf(say), rig.timeOf(say) + 1500) == 0
                            && rig.count("name cup") == 0 && rig.countPrefix("react", 0, 12001) == 0
                            && rig.count("startle") == 0 && rig.violations.isEmpty(),
                    "ask=" + ask + " open=" + open + " hop=" + hop + " say=" + say + " " + rig.tail());
        });
        scenario("claude_nothing_interesting_resumes_without_speaking", n -> {
            Rig rig = new Rig(claudeTuning().build(), CLEAR, cupIn(0), true,
                    (r, req, nth) -> CuriosityPort.Answer.nothing()).started();
            rig.runUntil(6000);
            int answer = rig.first("answer NOTHING", 0);
            int idle = rig.firstAfter("eyes IDLE", rig.timeOf(answer));
            check(n, answer >= 0 && rig.timeOf(idle) == rig.timeOf(answer)
                            && rig.brain.state() != ExploreBrain.State.ASK
                            && rig.countPrefix("say", 0, 6001) == 0 && rig.countPrefix("name", 0, 6001) == 0
                            && rig.countPrefix("react", 0, 6001) == 0
                            // The next stop is due after the 1000 ms pause.
                            && !(entered(rig, ExploreBrain.State.SCAN, rig.timeOf(answer)) >= 0
                                && entered(rig, ExploreBrain.State.SCAN, rig.timeOf(answer)) < rig.timeOf(answer) + 1000)
                            && rig.motions(rig.timeOf(answer), rig.timeOf(answer) + 1000) == 0
                            && rig.violations.isEmpty(),
                    "answer=" + answer + " idle=" + idle + " " + rig.tail());
        });
        scenario("claude_ae4_unreachable_thinks_tries_twice_then_turns_back_to_the_detector_pick", n -> {
            // The detector saw a cup in look 1; Claude never answers.
            Rig rig = new Rig(claudeTuning().build(), CLEAR, cupIn(0), true, (r, req, nth) -> null).started();
            rig.runUntil(30000);
            int ask = rig.first("ask", 0);
            int thinking = rig.first("eyes THINKING", 0);
            int ask2 = rig.first("ask", ask + 1);
            int cancel = rig.first("cancel ask", ask);
            int cancel2 = rig.first("cancel ask", cancel + 1);
            int scanTurn = rig.first("turn", 0);
            String scanDir = rig.what(scanTurn).substring(5);
            String back = scanDir.equals("LEFT") ? "RIGHT" : "LEFT";
            int lead = rig.first("eyes LOOK " + back, cancel2);
            int turn = rig.first("turn " + back, lead);
            int stop = rig.first("stop", turn);
            int open = rig.first("camera open", stop);
            int name = rig.first("name cup", open);
            long askAt = rig.timeOf(ask);
            check(n, ask >= 0 && rig.timeOf(thinking) == askAt && rig.countPrefix("ask", 0, askAt + 8001) == 2
                            && rig.askTimeouts.get(0) == 4000 && rig.timeOf(ask2) == askAt + 4000
                            && rig.timeOf(cancel) == askAt + 4000 && rig.timeOf(cancel2) == askAt + 8000
                            // Back to look 1's heading: both scan turns undone.
                            && rig.timeOf(lead) == askAt + 8000 && rig.timeOf(stop) - rig.timeOf(turn) == 1000
                            && name > open && rig.timeOf(name) - askAt < 8000 + 6000
                            && rig.countPrefix("say", 0, 30001) == 0 && rig.violations.isEmpty(),
                    "ask=" + ask + " ask2=" + ask2 + " cancel2=" + cancel2 + " lead=" + lead + " turn=" + turn
                            + " name=" + name + " " + rig.tail());
        });
        scenario("claude_camera_closed_while_asking_and_speaking_reopened_only_for_face_and_approach", n -> {
            Rig rig = new Rig(claudeTuning().build(), CLEAR, cupIn(2), true, MUG_ON_THE_CUP).started();
            rig.runUntil(12000);
            int ask = rig.first("ask", 0);
            int say = rig.first("say", 0);
            // Roaming keeps it open too (explore nav plan U4); never while asking or speaking.
            java.util.Set<ExploreBrain.State> allowed = java.util.EnumSet.of(
                    ExploreBrain.State.SCAN, ExploreBrain.State.FACE, ExploreBrain.State.APPROACH,
                    ExploreBrain.State.PAUSE, ExploreBrain.State.LOOK, ExploreBrain.State.TURN, ExploreBrain.State.HOP);
            check(n, rig.timeOf(rig.firstAfter("camera close", 0)) == rig.timeOf(ask)
                            && rig.timeOf(rig.firstAfter("camera close", rig.timeOf(ask) + 1)) == rig.timeOf(say)
                            && rig.statesSeen.contains(ExploreBrain.State.ASK)
                            && rig.statesSeen.contains(ExploreBrain.State.SPEAK)
                            && allowed.containsAll(rig.openStates) && rig.violations.isEmpty(),
                    "open in " + rig.openStates + " seen " + rig.statesSeen + " " + rig.tail());
        });
        scenario("claude_look_request_carries_recent_picks_and_the_people_cool_down_holds", n -> {
            Rig rig = new Rig(claudeTuning().build(), CLEAR, (r, t) -> list(), true,
                    (r, req, nth) -> pick(2, "person", CuriosityPort.Kind.PERSON, "Hi!",
                            0.5f, 0.5f, 0.4f, 0.8f));
            // U5: a person is greeted through the match; here he knows them but has no name for them.
            rig.people.match = (r, k) -> CuriosityPort.MatchAnswer.known(null, "Hi {name}!", "Hi there, friend!");
            rig.started();
            rig.runUntil(16000);
            int say = rig.first("say Hi there, friend!", 0);
            CuriosityPort.LookRequest first = rig.asks.get(0);
            CuriosityPort.LookRequest second = rig.asks.size() > 1 ? rig.asks.get(1) : null;
            boolean remembered = second != null && second.recent.size() == 1
                    && second.recent.get(0).kind == CuriosityPort.Kind.PERSON
                    && second.recent.get(0).label.equals("person") && second.livingCoolingDown;
            check(n, say >= 0 && first.recent.isEmpty() && !first.livingCoolingDown && remembered
                            && rig.count("say Hi there, friend!") == 1
                            && rig.statesSeen.contains(ExploreBrain.State.MEET) && rig.violations.isEmpty(),
                    "asks=" + rig.asks.size() + " say=" + say + " says=" + rig.count("say Hi there, friend!")
                            + " seen=" + rig.statesSeen + (second == null ? "" : " recent=" + second.recent
                            + " cooling=" + second.livingCoolingDown) + " " + rig.tail());
        });
        scenario("claude_say_that_never_finishes_ends_at_the_backstop", n -> {
            Rig rig = new Rig(claudeTuning().build(), CLEAR, (r, t) -> list(), true,
                    (r, req, nth) -> pick(2, "lamp", CuriosityPort.Kind.OTHER, "What a shiny lamp!",
                            0.5f, 0.5f, 0.2f, 0.3f));
            rig.sayNeverFinishes = true;
            rig.started();
            rig.runUntil(14000);
            int say = rig.first("say What a shiny lamp!", 0);
            int idle = rig.firstAfter("eyes IDLE", rig.timeOf(say));
            check(n, say >= 0 && rig.timeOf(idle) == rig.timeOf(say) + 6000
                            && rig.brain.state() != ExploreBrain.State.SPEAK && rig.violations.isEmpty(),
                    "say=" + rig.timeOf(say) + " idle=" + rig.timeOf(idle) + " " + rig.tail());
        });
        scenario("claude_scan_keeps_every_look_even_after_a_sighting", n -> {
            Rig rig = new Rig(claudeTuning().build(), CLEAR, cupIn(0), true,
                    (r, req, nth) -> CuriosityPort.Answer.nothing()).started();
            rig.runUntil(5000);
            CuriosityPort.LookRequest req = rig.asks.isEmpty() ? null : rig.asks.get(0);
            boolean frames = req != null && req.frames.size() == 3;
            for (int i = 0; frames && i < 3; i++) {
                frames = req.frames.get(i).look == i && req.frames.get(i).jpeg != null
                        && new String(req.frames.get(i).jpeg, java.nio.charset.StandardCharsets.US_ASCII)
                        .equals("jpeg@" + (1800 + 1000 * i));
            }
            check(n, frames && rig.countPrefix("turn", 0, 4001) == 2 && rig.violations.isEmpty(),
                    "asks=" + rig.asks.size() + " " + rig.tail());
        });
        scenario("claude_failed_first_try_is_retried_then_spoken", n -> {
            Rig rig = new Rig(claudeTuning().build(), CLEAR, (r, t) -> list(), true,
                    (r, req, nth) -> nth == 1 ? CuriosityPort.Answer.failed()
                            : pick(2, "robot", CuriosityPort.Kind.TECHNOLOGY, "Another robot! Hello, cousin!",
                            0.5f, 0.5f, 0.2f, 0.3f)).started();
            rig.runUntil(9000);
            int failed = rig.first("answer FAILED", 0);
            int ask2 = rig.first("ask", failed);
            int say = rig.first("say Another robot!", ask2);
            check(n, failed >= 0 && rig.timeOf(ask2) == rig.timeOf(failed) && say > ask2
                            && rig.countPrefix("name", 0, 9001) == 0 && rig.violations.isEmpty(),
                    rig.tail());
        });
        // ---- rate limits (robot 2026-10-01: a 429, retried 0.6 s later) ----
        scenario("claude_rate_limited_ask_is_not_retried_and_stops_skip_claude_until_the_pause_ends", n -> {
            // ClaudeCuriosity's pause starts as the 429 comes back: canAsk() is false for 20 s.
            Rig rig = new Rig(claudeTuning().build(), CLEAR, (r, t) -> list(), true,
                    (r, req, nth) -> {
                        if (nth == 1) {
                            r.claudePausedUntil = r.now + 1000 + 20000;
                            return CuriosityPort.Answer.failed();
                        }
                        return CuriosityPort.Answer.nothing();
                    });
            List<String> notes = traced(rig);
            rig.started();
            rig.runUntil(45000);
            int failed = rig.first("answer FAILED", 0);
            long at = rig.timeOf(failed);
            int next = rig.first("ask", failed);
            int scans = 0;
            for (String x : notes) {
                long t = Long.parseLong(x.substring(0, x.indexOf(' ')));
                if (t > at && t < at + 20000 && x.contains("curiosity stop")) {
                    scans++;
                }
            }
            check(n, failed >= 0 && next > failed && rig.timeOf(next) >= at + 20000
                            && noted(notes, "Claude is paused: no second try") && noted(notes, "no answer from Claude")
                            && rig.violations.isEmpty(),
                    "failed=" + at + " next=" + (next < 0 ? -1 : rig.timeOf(next)) + " stopsInPause=" + scans + " "
                            + rig.tail());
        });
        scenario("claude_look_budget_caps_asks_per_minute_and_the_excess_stops_use_the_detector", n -> {
            Rig rig = new Rig(claudeTuning().claudeLooksPerMinute(2).build(), CLEAR, (r, t) -> list(), true,
                    (r, req, nth) -> CuriosityPort.Answer.nothing());
            List<String> notes = traced(rig);
            rig.started();
            rig.runUntil(130000);
            List<Long> times = new ArrayList<Long>();
            for (int i = rig.first("ask", 0); i >= 0; i = rig.first("ask", i + 1)) {
                times.add(rig.timeOf(i));
            }
            boolean capped = true;
            for (int i = 2; i < times.size(); i++) {
                capped &= times.get(i) - times.get(i - 2) >= 60000;
            }
            ExploreTuning d = tuning().build();
            check(n, times.size() >= 3 && capped && noted(notes, "Claude look budget spent")
                            && d.claudeLooksPerMinute == 0 && d.chatPauseWaitMs == 10000 && rig.violations.isEmpty(),
                    "asks at " + times + " defaults " + d.claudeLooksPerMinute + "/" + d.chatPauseWaitMs);
        });
        scenario("chat_turn_in_a_short_claude_pause_says_one_sec_waits_and_carries_on", n -> {
            Rig rig = sarahRig(false);
            rig.turns = turnsOf(turnLine(1), CuriosityPort.Turn.unreachable(), turnLine(2));
            rig.pauseOnUnreachableMs = 6000;
            rig.people.listen = ListenScript.turns(hearWords("I like trains"), hearWords("bye"));
            long open = openChat(rig);
            List<String> notes = traced(rig);
            long over = chatOver(rig, open);
            int unreachable = rig.first("turn UNREACHABLE", 0);
            long at = rig.timeOf(unreachable);
            int sec = rig.firstAfter("react one-sec", at);
            int retry = rig.first("turn", unreachable + 1);
            int line2 = rig.first("say Line 2.", unreachable);
            int signOff = rig.firstAfter("react sign-off", at);
            check(n, open > 0 && unreachable >= 0 && sec >= 0 && rig.timeOf(sec) < at + 6000
                            && retry > unreachable && rig.timeOf(retry) >= at + 6000 && line2 > retry
                            && (signOff < 0 || signOff > line2) && rig.turnAsks.size() >= 3
                            && noted(notes, "Claude is paused for 6000 ms") && rig.violations.isEmpty(),
                    "unreachable=" + at + " sec=" + (sec < 0 ? -1 : rig.timeOf(sec)) + " retry="
                            + (retry < 0 ? -1 : rig.timeOf(retry)) + " over=" + over + " " + rig.tail());
        });
        scenario("chat_turn_in_a_long_claude_pause_signs_off_without_another_request", n -> {
            Rig rig = sarahRig(false);
            rig.turns = turnsOf(turnLine(1), CuriosityPort.Turn.unreachable(), turnLine(2));
            rig.pauseOnUnreachableMs = 30000;
            rig.people.listen = ListenScript.turns(hearWords("I like trains"), hearWords("bye"));
            long open = openChat(rig);
            List<String> notes = traced(rig);
            long over = chatOver(rig, open);
            int unreachable = rig.first("turn UNREACHABLE", 0);
            int signOff = rig.firstAfter("react sign-off", rig.timeOf(unreachable));
            check(n, open > 0 && over > 0 && unreachable >= 0 && signOff >= 0 && rig.turnAsks.size() == 2
                            && noted(notes, "Claude is paused for 30000 ms") && rig.violations.isEmpty(),
                    "asks=" + rig.turnAsks.size() + " " + rig.tail());
        });
        scenario("claude_reopen_gap_counts_in_the_first_look_budget", n -> {
            Rig rig = new Rig(claudeTuning().reopenGapMs(3000).build(), CLEAR, cupIn(2), true, MUG_ON_THE_CUP);
            rig.reopenGapMs = 3000;
            rig.claudeDelayMs = 100;
            rig.started();
            rig.runUntil(16000);
            int ask = rig.first("ask", 0);
            int open = rig.first("camera open", ask);
            int hop = rig.first("hop", open);
            int say = rig.first("say Ooh, a mug", hop);
            check(n, open > ask && hop > open && rig.timeOf(hop) >= rig.timeOf(ask) + 3000 && say > hop
                            && rig.violations.isEmpty(),
                    "open=" + rig.timeOf(open) + " hop=" + rig.timeOf(hop) + " " + rig.tail());
        });
    }

    // ---- ExploreBrain: meeting and remembering people (explore on Claude plan U5, AE2, AE3, AE5) ----
    //
    // Claude picks a person straight ahead in look 3, so there is no orient turn:
    // MEET starts at 5000 with the match request, which answers at 6000. A line
    // takes speechMs (1500) to say, and a reply comes replyMs (2000) after listen().

    private static final Claude PERSON_AHEAD = (rig, req, nth) ->
            pick(2, "person", CuriosityPort.Kind.PERSON, "LOOK-LINE", 0.5f, 0.5f, 0.4f, 0.8f);

    private static final CuriosityPort.MatchAnswer STRANGER =
            CuriosityPort.MatchAnswer.stranger("Hello! What's your name?", "No worries, shy friend!");

    private static Rig meetRig() {
        return meetRig(claudeTuning());
    }

    private static Rig meetRig(ExploreTuning.Builder b) {
        return new Rig(b.build(), CLEAR, (r, t) -> list(), true, PERSON_AHEAD);
    }

    /** Back to wandering after the stop: eyes idle at t, and not stuck in any meet state. */
    private static boolean resumedBy(Rig rig, long from, long by) {
        int idle = rig.firstAfter("eyes IDLE", from);
        return idle >= 0 && rig.timeOf(idle) <= by;
    }

    // ---- the fixes from Explore on Claude's first live test ----

    /** A curiosity stop, from SCAN until he is back to wandering. */
    private static boolean isStop(ExploreBrain.State s) {
        switch (s) {
            case SCAN: case FACE: case APPROACH: case INSPECT: case REACT_HERE: case ASK: case ORIENT: case MEET_LOOK:
            case MEET:
            case SPEAK: case ASK_NAME: case LISTEN: case NAME: case REMEMBER: case NAME_CLIP:
            case CUE_TURN: case CUE_LOOK:
                return true;
            default:
                return false;
        }
    }

    /** A detector box with this label in the given look (and in every look after the scan), growing as he drives. */
    private static Vision thingIn(int look, String label, float cx, float cy, float w, float h) {
        return (rig, t) -> {
            int at = lookAt(rig, t);
            if (at == look || at >= 3) {
                return list(box(label, 0.8f, cx, cy, w, Math.min(1f, h + 0.15f * rig.count("hop"))));
            }
            return list();
        };
    }

    /** Whether he drove up to Claude's pick: a hop between the answer and the line. */
    private static boolean drove(Rig rig, String sayPrefix) {
        int answer = rig.first("answer PICK", 0);
        int say = rig.first(sayPrefix, answer);
        return say > 0 && rig.countPrefix("hop", rig.timeOf(answer), rig.timeOf(say) + 1) > 0;
    }

    // ---- a hazard while going to Claude's pick (owner report: a neon sign's line lost to an obstacle) ----
    //
    // Claude picks on the first stop only; the stop after it (curiosity gap 0 here)
    // is how a test sees that the first one ended. The feed reads a hazard only while the brain is in `during` and moving (for an
    // approach, once a leg has started), until the first startle; clear after that.

    private static Feed hazardWhile(Rig[] rig, ExploreBrain.State during, Feed hazard) {
        return t -> {
            Rig r = rig[0];
            boolean on = r != null && r.brain.state() == during && r.moving && r.count("startle") == 0
                    && (during != ExploreBrain.State.APPROACH || r.count("hop") > 0);
            return on ? hazard.at(t) : clear(t);
        };
    }

    /** The line was said once, after the startle, the back-off and the escape turn had all stopped. */
    private static String saidAfterEscape(Rig rig, String sayPrefix) {
        int startle = rig.first("startle", 0);
        int back = rig.first("back", startle);
        int say = rig.first(sayPrefix, 0);
        if (startle < 0 || back < 0 || say < 0) {
            return "startle=" + startle + " back=" + back + " say=" + say;
        }
        int lastMotion = -1;
        for (int i = 0; i < say; i++) {
            String w = rig.what(i);
            if (w.equals("hop") || w.startsWith("turn") || w.equals("back")) {
                lastMotion = i;
            }
        }
        int stop = rig.first("stop", lastMotion);
        if (!(say > back && lastMotion > back && stop > lastMotion && stop < say)) {
            return "not after a finished escape: back=" + back + " lastMotion=" + lastMotion + " stop=" + stop
                    + " say=" + say;
        }
        if (rig.countPrefix(sayPrefix, 0, Long.MAX_VALUE) != 1) {
            return "said " + rig.countPrefix(sayPrefix, 0, Long.MAX_VALUE) + " times";
        }
        int early = rig.first("say", startle);
        if (early < stop) {
            return "spoke during the hazard handling";
        }
        return null;
    }

    /** Claude picks on the first stop only (NOTHING after), so a later stop can't say the line instead. */
    private static Claude onlyFirst(Claude c) {
        return (rig, req, nth) -> nth == 1 ? c.answer(rig, req, nth) : CuriosityPort.Answer.nothing();
    }

    private static void hazardDuringPickScenarios() {
        scenario("claude_obstacle_during_orient_says_the_line_after_the_escape", n -> {
            Rig[] h = new Rig[1];
            Rig rig = new Rig(claudeTuning().build(), hazardWhile(h, ExploreBrain.State.ORIENT, t -> obstacle(t)),
                    (r, t) -> list(), true, onlyFirst(CAT_RIGHT_IN_FRAME_3));
            h[0] = rig;
            rig.started();
            rig.runUntil(15000);
            String bad = saidAfterEscape(rig, "say Hello kitty");
            int turn = rig.firstAfter("turn RIGHT", 5000);
            check(n, bad == null && rig.first("startle", 0) > turn && turn >= 0 && rig.violations.isEmpty(),
                    bad + " " + rig.tail());
        });
        scenario("claude_edge_during_approach_says_the_line_after_the_escape", n -> {
            Rig[] h = new Rig[1];
            Rig rig = new Rig(claudeTuning().build(), hazardWhile(h, ExploreBrain.State.APPROACH, t -> edgeAhead(t)),
                    cupIn(2), true, onlyFirst(MUG_ON_THE_CUP));
            h[0] = rig;
            rig.started();
            rig.runUntil(18000);
            String bad = saidAfterEscape(rig, "say Ooh, a mug");
            int hop = rig.first("hop", 0);
            check(n, bad == null && hop >= 0 && rig.first("startle", 0) > hop && rig.violations.isEmpty(),
                    bad + " " + rig.tail());
        });
        scenario("meet_hazard_on_the_way_to_a_person_drops_the_meet", n -> {
            Rig[] h = new Rig[1];
            // A person off to the right in look 3: he turns toward them (ORIENT), then would MEET.
            Rig rig = new Rig(claudeTuning().build(), hazardWhile(h, ExploreBrain.State.ORIENT, t -> obstacle(t)),
                    (r, t) -> list(), true,
                    onlyFirst((r, req, nth) -> pick(2, "person", CuriosityPort.Kind.PERSON, "LOOK-LINE",
                            0.8f, 0.5f, 0.3f, 0.8f)));
            rig.people.match = (r, k) -> STRANGER;
            h[0] = rig;
            rig.started();
            rig.runUntil(15000);
            int startle = rig.first("startle", 0);
            check(n, startle >= 0 && rig.count("match") == 0 && rig.countPrefix("say", 0, 15001) == 0
                            && rig.count("listen") == 0 && rig.stored.isEmpty() && rig.touches == 0
                            && !rig.statesSeen.contains(ExploreBrain.State.MEET)
                            && rig.firstAfter("ask", rig.timeOf(startle)) >= 0 && rig.violations.isEmpty(),
                    "startle=" + startle + " " + rig.tail());
        });
        scenario("claude_line_older_than_the_freshness_window_is_not_spoken", n -> {
            Rig[] h = new Rig[1];
            // The escape takes ~2 s; the line keeps for 1 s.
            Rig rig = new Rig(claudeTuning().heldLineFreshMs(1000).build(),
                    hazardWhile(h, ExploreBrain.State.ORIENT, t -> obstacle(t)), (r, t) -> list(), true,
                    onlyFirst(CAT_RIGHT_IN_FRAME_3));
            h[0] = rig;
            rig.started();
            rig.runUntil(15000);
            int startle = rig.first("startle", 0);
            check(n, startle >= 0 && rig.countPrefix("say", 0, 15001) == 0
                            && rig.firstAfter("ask", rig.timeOf(startle)) >= 0 && rig.violations.isEmpty(),
                    "startle=" + startle + " " + rig.tail());
        });
        scenario("claude_hazard_mid_speak_neither_cuts_nor_repeats_the_line", n -> {
            // Standing still while he speaks: an obstacle then is not reacted to, and the line is said once.
            Rig[] h = new Rig[1];
            Rig rig = new Rig(claudeTuning().build(),
                    t -> h[0] != null && h[0].brain.state() == ExploreBrain.State.SPEAK ? obstacle(t) : clear(t),
                    (r, t) -> list(), true, onlyFirst(CAT_RIGHT_IN_FRAME_3));
            h[0] = rig;
            rig.started();
            rig.runUntil(15000);
            int say = rig.first("say Hello kitty", 0);
            check(n, say >= 0 && rig.count("startle") == 0
                            && rig.countPrefix("say", 0, 15001) == 1
                            && rig.motions(rig.timeOf(say), rig.timeOf(say) + 1500) == 0 && rig.violations.isEmpty(),
                    "say@" + rig.timeOf(say) + " " + rig.tail());
        });
    }

    private static void liveFixScenarios() {
        scenario("tuning_defaults_roam_25_to_40_s_and_two_10_s_claude_tries", n -> {
            ExploreTuning t = new ExploreTuning.Builder().calibration(calibration()).build();
            Rig rig = new Rig(curious().scan(3, 500).sayTimeoutMs(6000).build(), CLEAR, (r, tt) -> list(), true,
                    (r, req, nth) -> null).started();
            rig.runUntil(9000);
            check(n, t.curiosityMinMs == 18000 && t.curiosityMaxMs == 28000 && t.curiosityRetryMs == 3000
                            && t.curiosityBackoffMs == 30000 && t.cameraBackoffMs == 120000 && t.askAttempts == 2
                            && t.askTimeoutMs == 10000 && t.pickMatchIou == 0.3f
                            && !rig.askTimeouts.isEmpty() && rig.askTimeouts.get(0) == 10000,
                    t.curiosityMinMs + ".." + t.curiosityMaxMs + " " + t.askAttempts + "x" + t.askTimeoutMs
                            + " iou " + t.pickMatchIou + " rig " + rig.askTimeouts);
        });
        scenario("claude_other_pick_on_a_differently_named_detector_box_faces_without_driving", n -> {
            // Live: Claude picked a potted plant and he drove at the detector's "dresser".
            Rig rig = new Rig(claudeTuning().build(), CLEAR, thingIn(2, "dresser", 0.5f, 0.6f, 0.3f, 0.4f), true,
                    (r, req, nth) -> pick(2, "potted plant", CuriosityPort.Kind.OTHER, "What a lovely plant!",
                            0.5f, 0.6f, 0.3f, 0.4f)).started();
            rig.runUntil(12000);
            int ask = rig.first("ask", 0);
            int say = rig.first("say What a lovely plant!", ask);
            check(n, say > ask && !drove(rig, "say What a lovely plant!")
                            && rig.countPrefix("camera open", rig.timeOf(ask), rig.timeOf(say) + 1) == 0
                            && rig.violations.isEmpty(),
                    "ask=" + ask + " say=" + say + " " + rig.tail());
        });
        scenario("claude_other_pick_with_a_synonym_or_shared_word_is_approached", n -> {
            Rig a = new Rig(claudeTuning().build(), CLEAR, thingIn(2, "potted plant", 0.5f, 0.6f, 0.3f, 0.4f), true,
                    (r, req, nth) -> pick(2, "succulent", CuriosityPort.Kind.OTHER, "A tiny succulent!",
                            0.5f, 0.6f, 0.3f, 0.4f)).started();
            a.runUntil(12000);
            Rig b = new Rig(claudeTuning().build(), CLEAR, thingIn(2, "plant", 0.5f, 0.6f, 0.3f, 0.4f), true,
                    (r, req, nth) -> pick(2, "green potted plant", CuriosityPort.Kind.OTHER, "So green!",
                            0.5f, 0.6f, 0.3f, 0.4f)).started();
            b.runUntil(12000);
            check(n, drove(a, "say A tiny succulent!") && drove(b, "say So green!")
                            && a.violations.isEmpty() && b.violations.isEmpty(),
                    "a: " + a.tail() + " b: " + b.tail());
        });
        scenario("claude_pick_needs_iou_0_3_not_a_centre_inside_its_box", n -> {
            // A small "tv" box inside Claude's big television box overlaps it by ~0.05.
            Rig rig = new Rig(claudeTuning().build(), CLEAR, thingIn(2, "tv", 0.5f, 0.5f, 0.2f, 0.2f), true,
                    (r, req, nth) -> pick(2, "big television", CuriosityPort.Kind.TECHNOLOGY, "What a big screen!",
                            0.5f, 0.5f, 0.9f, 0.9f)).started();
            rig.runUntil(12000);
            check(n, rig.first("say What a big screen!", 0) > 0 && !drove(rig, "say What a big screen!")
                            && rig.violations.isEmpty(),
                    rig.tail());
        });
        scenario("claude_repeat_of_a_recent_thing_is_nothing", n -> {
            // Live: the same potted plant twice in a row. With no fresh line it is as good as nothing
            // (a fresh line is said: claude_familiar_pick_with_a_fresh_line_is_said).
            Rig rig = new Rig(claudeTuning().build(), CLEAR, (r, t) -> list(), true,
                    (r, req, nth) -> nth == 1
                            ? pick(2, "potted plant", CuriosityPort.Kind.OTHER, "Look at that plant!",
                                    0.5f, 0.5f, 0.2f, 0.3f)
                            : pick(1, "plant", CuriosityPort.Kind.OTHER, "", 0.5f, 0.5f, 0.2f, 0.3f))
                    .started();
            rig.runUntil(20000);
            int second = rig.first("answer PICK", rig.first("answer PICK", 0) + 1);
            check(n, rig.count("say Look at that plant!") == 1 && rig.countPrefix("say ", 0, 20001) == 1
                            && second > 0 && rig.firstMotionAfter(rig.timeOf(second)) > 0
                            && rig.violations.isEmpty(),
                    "second=" + second + " " + rig.tail());
        });
        scenario("claude_living_pick_during_cool_down_is_nothing", n -> {
            Rig rig = new Rig(claudeTuning().build(), CLEAR, (r, t) -> list(), true,
                    (r, req, nth) -> nth == 1
                            ? pick(2, "person", CuriosityPort.Kind.PERSON, "Hi!", 0.5f, 0.5f, 0.4f, 0.8f)
                            : pick(1, "dog", CuriosityPort.Kind.ANIMAL, "A doggy!", 0.5f, 0.5f, 0.4f, 0.4f));
            rig.people.match = (r, k) -> CuriosityPort.MatchAnswer.known(null, "Hi {name}!", "Hi there, friend!");
            rig.started();
            rig.runUntil(16000);
            int second = rig.first("answer PICK", rig.first("answer PICK", 0) + 1);
            check(n, rig.count("say Hi there, friend!") == 1 && rig.countPrefix("say A doggy!", 0, 16001) == 0
                            && rig.asks.size() >= 2 && rig.asks.get(1).livingCoolingDown
                            && second > 0 && rig.firstMotionAfter(rig.timeOf(second)) > 0
                            && rig.violations.isEmpty(),
                    "second=" + second + " " + rig.tail());
        });
        scenario("prompt_cool_down_firmly_rules_out_people_and_animals", n -> {
            String p = ExplorePrompts.lookAsk(new CuriosityPort.LookRequest(
                    new ArrayList<CuriosityPort.Frame>(), new ArrayList<CuriosityPort.Recent>(), true));
            String q = ExplorePrompts.lookAsk(new CuriosityPort.LookRequest(
                    new ArrayList<CuriosityPort.Frame>(), new ArrayList<CuriosityPort.Recent>(), false));
            check(n, p.contains("Do not pick a person or an animal") && p.contains("most interesting other thing")
                            && !q.contains("Do not pick a person or an animal"),
                    p);
        });
        scenario("prompt_recent_picks_are_ruled_out", n -> {
            List<CuriosityPort.Recent> recent = new ArrayList<CuriosityPort.Recent>();
            recent.add(new CuriosityPort.Recent("potted plant", CuriosityPort.Kind.OTHER, 30000));
            String p = ExplorePrompts.lookAsk(new CuriosityPort.LookRequest(
                    new ArrayList<CuriosityPort.Frame>(), recent, false));
            // Robot 2026-10-01: a hard "do not pick" plus "or answer interesting false" left a familiar
            // office silent; familiar things are now preferred against, with a fresh line if picked.
            check(n, p.contains("potted plant") && p.contains("Prefer something new over anything on this list")
                            && !p.contains("Do not pick anything on this list"), p);
        });
        scenario("claude_camera_closed_at_speak_entry_on_every_path", n -> {
            Rig approach = new Rig(claudeTuning().build(), CLEAR, cupIn(2), true, MUG_ON_THE_CUP).started();
            approach.runUntil(12000);
            Rig face = new Rig(claudeTuning().build(), CLEAR, (r, t) -> list(), true, CAT_RIGHT_IN_FRAME_3).started();
            face.runUntil(9000);
            Rig person = new Rig(claudeTuning().build(), CLEAR,
                    thingIn(2, "person", 0.5f, 0.5f, 0.4f, 0.8f), true,
                    (r, req, nth) -> pick(2, "person", CuriosityPort.Kind.PERSON, "Hi!", 0.5f, 0.5f, 0.4f, 0.8f));
            person.people.match = (r, k) -> CuriosityPort.MatchAnswer.known(null, "Hi {name}!", "Hi there, friend!");
            person.started();
            person.runUntil(16000);
            boolean ok = true;
            StringBuilder why = new StringBuilder();
            for (Rig rig : new Rig[] {approach, face, person}) {
                int say = rig.first("say", 0);
                int lastClose = -1;
                for (int i = 0; i < say; i++) {
                    if (rig.what(i).equals("camera close")) {
                        lastClose = i;
                    }
                }
                boolean closedFirst = say > 0 && lastClose >= 0
                        && rig.first("camera open", lastClose) == -1 | rig.first("camera open", lastClose) > say;
                ok &= closedFirst && rig.violations.isEmpty();
                why.append(" [").append(rig.tail()).append("]");
            }
            check(n, ok && approach.statesSeen.contains(ExploreBrain.State.APPROACH), why.toString());
        });
        scenario("claude_speech_waits_for_an_in_flight_detector_run", n -> {
            Rig rig = new Rig(claudeTuning().build(), CLEAR, cupIn(2), true, MUG_ON_THE_CUP);
            rig.detectorTailMs = 700;
            rig.started();
            rig.runUntil(14000);
            int say = rig.first("say Ooh, a mug", 0);
            long close = rig.timeOf(rig.firstAfter("camera close", rig.timeOf(rig.first("hop", 0))));
            check(n, say > 0 && rig.timeOf(say) == close + 700
                            && rig.brain.state() != ExploreBrain.State.SPEAK && rig.violations.isEmpty(),
                    "close=" + close + " say=" + rig.timeOf(say) + " " + rig.tail());
        });
        scenario("claude_speech_goes_ahead_when_the_camera_never_goes_quiet", n -> {
            Rig rig = new Rig(claudeTuning().quietWaitMs(1500).build(), CLEAR, cupIn(2), true, MUG_ON_THE_CUP);
            rig.detectorTailMs = 1000000;
            rig.sayWhileBusyAllowed = true;
            rig.started();
            rig.runUntil(16000);
            int say = rig.first("say Ooh, a mug", 0);
            long close = rig.timeOf(rig.firstAfter("camera close", rig.timeOf(rig.first("hop", 0))));
            int idle = rig.firstAfter("eyes IDLE", rig.timeOf(say));
            check(n, say > 0 && rig.timeOf(say) == close + 1500 && rig.timeOf(idle) == rig.timeOf(say) + 1500
                            && rig.violations.isEmpty(),
                    "close=" + close + " say=" + rig.timeOf(say) + " " + rig.tail());
        });
        scenario("claude_stops_are_45_to_90_s_apart_and_he_roams_between", n -> {
            // Live: a new stop began ~0.5 s after every SPEAK, so he never roamed.
            // Every way a stop ends: a spoken line, NOTHING, two failures and the
            // fallback, a cool-down skip, a repeat, and a person's name clip.
            Rig rig = new Rig(claudeTuning().curiosityMs(45000, 90000).peopleCooldownMs(10000000).build(), CLEAR,
                    (r, t) -> list(), true,
                    (r, req, nth) -> {
                        switch (nth) {
                            case 1: return pick(2, "lamp", CuriosityPort.Kind.OTHER, "What a shiny lamp!",
                                    0.5f, 0.5f, 0.2f, 0.3f);
                            case 2: return CuriosityPort.Answer.nothing();
                            case 3: case 4: return CuriosityPort.Answer.failed();
                            case 5: return pick(2, "person", CuriosityPort.Kind.PERSON, "Hi!", 0.5f, 0.5f, 0.4f, 0.8f);
                            case 6: return pick(1, "cat", CuriosityPort.Kind.ANIMAL, "A kitty!", 0.5f, 0.5f, 0.3f, 0.3f);
                            // A repeat with the line he has said already: as good as nothing.
                            default: return pick(0, "lamp", CuriosityPort.Kind.OTHER, "What a shiny lamp!",
                                    0.5f, 0.5f, 0.2f, 0.3f);
                        }
                    }).started();
            rig.runUntil(800000);
            List<Long> scans = new ArrayList<Long>();
            List<Long> ends = new ArrayList<Long>();
            ExploreBrain.State prev = null;
            for (Event e : rig.stateLog) {
                ExploreBrain.State st = ExploreBrain.State.valueOf(e.what);
                if (st == ExploreBrain.State.SCAN && (prev == null || !isStop(prev))) {
                    scans.add(e.t);
                }
                if (prev != null && isStop(prev) && !isStop(st)) {
                    ends.add(e.t);
                }
                prev = st;
            }
            boolean ok = scans.size() >= 7 && ends.size() >= scans.size() - 1;
            StringBuilder gaps = new StringBuilder();
            for (int i = 1; ok && i < scans.size(); i++) {
                long end = ends.get(i - 1);
                long gap = scans.get(i) - end;
                gaps.append(gap).append(' ');
                boolean roamed = rig.countPrefix("hop", end, scans.get(i)) > 0;
                boolean paused = false;
                for (Event e : rig.stateLog) {
                    paused |= e.t >= end && e.t < scans.get(i) && e.what.equals("PAUSE");
                }
                ok = gap >= 45000 && gap <= 90000 + 3000 && roamed && paused;
            }
            check(n, ok && rig.count("say What a shiny lamp!") == 1 && rig.countPrefix("say A kitty!", 0, 800001) == 0
                            && rig.countPrefix("say The lamp again!", 0, 800001) == 0 && rig.violations.isEmpty(),
                    "scans=" + scans + " ends=" + ends + " gaps=" + gaps + " " + rig.tail());
        });
    }

    private static void meetScenarios() {
        scenario("meet_ae3_known_person_is_greeted_by_name_and_touched", n -> {
            Rig rig = meetRig();
            rig.people.match = (r, k) -> CuriosityPort.MatchAnswer.known("Sarah", "Sarah! Great to see you, {name}!",
                    "Hello again, friend!");
            rig.started();
            rig.runUntil(12000);
            int match = rig.first("match", 0);
            int say = rig.first("say ", match);
            check(n, match >= 0 && rig.what(say).equals("say Sarah! Great to see you, Sarah!")
                            && rig.touches == 1 && rig.countPrefix("say", 0, 12001) == 1
                            && rig.count("listen") == 0 && rig.stored.isEmpty()
                            && resumedBy(rig, rig.timeOf(say), rig.timeOf(say) + 1500)
                            && rig.violations.isEmpty(),
                    "say=" + rig.what(say) + " touches=" + rig.touches + " " + rig.tail());
        });
        scenario("meet_ae2_new_person_who_gives_a_name_is_stored_and_remembered", n -> {
            Rig rig = meetRig();
            rig.people.match = (r, k) -> STRANGER;
            rig.people.listen = ListenScript.always(hearWords("my name is Sarah"));
            rig.people.remembered = CuriosityPort.Answer.line("Sarah, what a lovely smile! I'll remember you!");
            rig.started();
            rig.runUntil(16000);
            int ask = rig.first("say Hello! What's your name?", 0);
            int listen = rig.first("listen", ask);
            int remember = rig.first("remember Sarah", listen);
            int say = rig.first("say Sarah, what a lovely smile!", remember);
            check(n, ask >= 0 && listen > ask && rig.timeOf(listen) == rig.timeOf(ask) + 1500
                            && rig.listenMaxMs == 6000 && remember > listen && say > remember
                            && rig.stored.equals(java.util.Arrays.asList("Sarah"))
                            && rig.count("say LOOK-LINE") == 0 && rig.touches == 0
                            && resumedBy(rig, rig.timeOf(say), rig.timeOf(say) + 1500)
                            && rig.violations.isEmpty(),
                    "ask=" + ask + " listen=" + listen + " remember=" + remember + " say=" + say + " " + rig.tail());
        });
        scenario("meet_ae5_no_reply_says_the_friendly_line_and_stores_nothing", n -> {
            Rig rig = meetRig();
            rig.people.match = (r, k) -> STRANGER;
            rig.people.listen = ListenScript.always(hearSilence());
            rig.started();
            rig.runUntil(14000);
            int listen = rig.first("listen", 0);
            int say = rig.first("say No worries, shy friend!", listen);
            check(n, listen >= 0 && say > listen && rig.stored.isEmpty() && rig.count("find name") == 0
                            && resumedBy(rig, rig.timeOf(say), rig.timeOf(say) + 1500)
                            && rig.violations.isEmpty(),
                    "say=" + say + " stored=" + rig.stored + " " + rig.tail());
        });
        // R19 (meeting plan line 309): a reply without a name gets the hello and nothing is kept.
        scenario("meet_ae5_reply_without_a_name_is_welcomed_and_nothing_is_stored", n -> {
            Rig rig = meetRig();
            rig.people.match = (r, k) -> STRANGER;
            rig.people.listen = ListenScript.always(hearWords("hmm what"));
            rig.people.remembered = CuriosityPort.Answer.line("Nice to meet you! I'll remember that smile!");
            rig.people.welcomed = CuriosityPort.Answer.line("So nice to meet you!");
            rig.started();
            rig.runUntil(16000);
            int heard = rig.first("heard WORDS", 0);
            int welcome = rig.first("welcome (unnamed)", heard);
            int say = rig.first("say So nice to meet you!", welcome);
            check(n, heard >= 0 && welcome > heard && say > welcome && rig.stored.isEmpty()
                            && rig.count("welcome (unnamed)") == 1 && rig.countPrefix("remember", 0, 16001) == 0
                            && rig.count("say Nice to meet you! I'll remember that smile!") == 0
                            && resumedBy(rig, rig.timeOf(say), 16000) && rig.violations.isEmpty(),
                    "stored=" + rig.stored + " welcome=" + welcome + " say=" + say + " " + rig.tail());
        });
        // A nameless reply falls back to the friendly no-reply line when the hello has no usable line.
        scenario("meet_ae5_reply_without_a_name_and_no_hello_line_says_the_friendly_line", n -> {
            Rig rig = meetRig();
            rig.people.match = (r, k) -> STRANGER;
            rig.people.listen = ListenScript.always(hearWords("hmm what"));
            rig.people.welcomed = CuriosityPort.Answer.failed();
            rig.started();
            rig.runUntil(16000);
            int welcome = rig.first("welcome (unnamed)", 0);
            int say = rig.first("say " + STRANGER.noReplyLine, welcome);
            check(n, welcome >= 0 && say > welcome && rig.stored.isEmpty()
                            && rig.count("say " + STRANGER.noReplyLine) == 1
                            && rig.countPrefix("remember", 0, 16001) == 0
                            && resumedBy(rig, rig.timeOf(say), rig.timeOf(say) + 1500)
                            && rig.violations.isEmpty(),
                    "welcome=" + welcome + " say=" + say + " " + rig.tail());
        });
        // The third way into nameStep's nameless branch: the name request never answers, so the
        // meet deadline passes. He still just says hello and keeps nothing (R19); before the fix
        // this was the path most likely to store an unnamed face on the robot.
        scenario("meet_ae5_name_request_that_never_answers_is_welcomed_at_the_deadline", n -> {
            Rig rig = meetRig();
            rig.people.match = (r, k) -> STRANGER;
            rig.people.listen = ListenScript.always(hearWords("hmm what"));
            rig.people.name = null;
            rig.people.nameAsksClaude = true;
            rig.started();
            rig.runUntil(18000);
            int heard = rig.first("heard WORDS", 0);
            int find = rig.first("find name", heard);
            int welcome = rig.first("welcome (unnamed)", find);
            int say = rig.first("say So nice to meet you!", welcome);
            // 4000 ms is the meet rig's meetTimeoutMs: the name request's deadline.
            check(n, heard >= 0 && find > heard && welcome > find && say > welcome
                            && rig.timeOf(welcome) == rig.timeOf(heard) + 4000
                            && rig.stored.isEmpty() && rig.countPrefix("remember", 0, 18001) == 0
                            && rig.count("welcome (unnamed)") == 1
                            && resumedBy(rig, rig.timeOf(say), rig.timeOf(say) + 1500)
                            && rig.violations.isEmpty(),
                    "heard=" + heard + " welcome=" + welcome + " say=" + say + " stored=" + rig.stored
                            + " " + rig.tail());
        });
        scenario("meet_reply_without_a_pattern_waits_for_claude_to_find_the_name", n -> {
            Rig rig = meetRig();
            rig.people.match = (r, k) -> STRANGER;
            rig.people.listen = ListenScript.always(hearWords("oh hi it is priya"));
            rig.people.name = t -> CuriosityPort.Named.of("Priya");
            rig.people.nameAsksClaude = true;
            rig.started();
            rig.runUntil(16000);
            int heard = rig.first("heard WORDS", 0);
            int find = rig.first("find name", heard);
            // Face plan U7: the name found goes through the resolver before it is stored.
            int resolve = rig.first("resolve Priya", find);
            int remember = rig.first("remember Priya", resolve);
            check(n, find > heard && rig.timeOf(resolve) == rig.timeOf(find) + 1000 && remember > resolve
                            && rig.stored.equals(java.util.Arrays.asList("Priya")) && rig.violations.isEmpty(),
                    "find=" + find + " remember=" + remember + " " + rig.tail());
        });
        scenario("meet_refused_match_asks_text_only_lines_then_the_name", n -> {
            Rig rig = meetRig();
            rig.people.match = (r, k) -> CuriosityPort.MatchAnswer.FAILED;
            rig.people.lines = STRANGER;
            rig.started();
            rig.runUntil(14000);
            int failed = rig.first("match FAILED", 0);
            int lines = rig.first("lines", failed);
            int ask = rig.first("say Hello! What's your name?", lines);
            check(n, failed >= 0 && rig.timeOf(lines) == rig.timeOf(failed) && ask > lines
                            && rig.count("lines") == 1 && rig.first("listen", ask) > ask
                            && rig.countPrefix("name", 0, 14001) == 0 && rig.violations.isEmpty(),
                    "lines=" + lines + " ask=" + ask + " " + rig.tail());
        });
        scenario("meet_refused_match_and_failed_lines_play_the_name_clip_without_asking", n -> {
            Rig rig = meetRig();
            rig.people.match = (r, k) -> CuriosityPort.MatchAnswer.FAILED;
            rig.people.lines = CuriosityPort.MatchAnswer.FAILED;
            rig.started();
            rig.runUntil(12000);
            int linesFailed = rig.first("lines FAILED", 0);
            int name = rig.first("name person", linesFailed);
            check(n, linesFailed >= 0 && rig.timeOf(name) == rig.timeOf(linesFailed)
                            && rig.countPrefix("say", 0, 12001) == 0 && rig.count("listen") == 0
                            && rig.count("lines") == 1 && rig.stored.isEmpty()
                            && resumedBy(rig, rig.timeOf(name), rig.timeOf(name) + 1000)
                            && rig.violations.isEmpty(),
                    "name=" + name + " " + rig.tail());
        });
        // Legacy defence: the gallery no longer answers nameless records (R19, KTD10), but a
        // matched record with no name still gets the unnamed line rather than a crash.
        scenario("meet_known_person_without_a_name_is_greeted_with_the_unnamed_line", n -> {
            Rig rig = meetRig();
            rig.people.match = (r, k) -> CuriosityPort.MatchAnswer.known(null, "Hi {name}!", "Hey, I remember you!");
            rig.started();
            rig.runUntil(12000);
            check(n, rig.count("say Hey, I remember you!") == 1 && rig.touches == 1 && rig.count("listen") == 0
                            && rig.violations.isEmpty(),
                    rig.tail());
        });
        // Face plan U6 (KTD7): the lines request carries the named greeting with {name} for the robot to fill.
        scenario("replies_lines_carry_the_named_greeting_with_its_placeholder", n -> {
            java.util.Map<String, Object> json = new java.util.LinkedHashMap<String, Object>();
            json.put("named_line", "Great to see you, {name}!");
            json.put("ask_line", "Hello! What's your name?");
            json.put("no_reply_line", "No worries!");
            CuriosityPort.MatchAnswer all = ClaudeReplies.lines(json);
            json.put("named_line", "Great to see you, Sarah!");
            CuriosityPort.MatchAnswer noPlaceholder = ClaudeReplies.lines(json);
            json.remove("ask_line");
            CuriosityPort.MatchAnswer noAsk = ClaudeReplies.lines(json);
            json.put("named_line", "Hi {name}!");
            CuriosityPort.MatchAnswer namedOnly = ClaudeReplies.lines(json);
            json.remove("named_line");
            CuriosityPort.MatchAnswer neither = ClaudeReplies.lines(json);
            check(n, all.status == CuriosityPort.MatchAnswer.Status.NEW
                            && "Great to see you, {name}!".equals(all.namedLine)
                            && "Hello! What's your name?".equals(all.askLine) && "No worries!".equals(all.noReplyLine)
                            && noPlaceholder.namedLine == null && noPlaceholder.askLine != null
                            && noAsk.status == CuriosityPort.MatchAnswer.Status.FAILED
                            && namedOnly.status == CuriosityPort.MatchAnswer.Status.NEW && namedOnly.askLine == null
                            && neither.status == CuriosityPort.MatchAnswer.Status.FAILED,
                    "all=" + all.namedLine + " noPlaceholder=" + noPlaceholder.namedLine + " noAsk=" + noAsk.status
                            + " namedOnly=" + namedOnly.status + " neither=" + neither.status);
        });
        scenario("meet_listen_failure_resumes_within_the_budget", n -> {
            Rig rig = meetRig();
            rig.people.match = (r, k) -> STRANGER;
            rig.people.listen = ListenScript.always(hearFailed());
            rig.started();
            rig.runUntil(14000);
            int failed = rig.first("heard FAILED", 0);
            check(n, failed >= 0 && rig.stored.isEmpty() && resumedBy(rig, rig.timeOf(failed), rig.timeOf(failed))
                            && rig.violations.isEmpty(),
                    rig.tail());
        });
        scenario("meet_listen_that_never_answers_ends_at_its_deadline", n -> {
            Rig rig = meetRig();
            rig.people.match = (r, k) -> STRANGER;
            rig.people.listen = ListenScript.NEVER;
            rig.started();
            rig.runUntil(20000);
            int listen = rig.first("listen", 0);
            // 6000 ms of listening plus the 2000 ms margin.
            check(n, listen >= 0 && resumedBy(rig, rig.timeOf(listen), rig.timeOf(listen) + 8000)
                            && !resumedBy(rig, rig.timeOf(listen), rig.timeOf(listen) + 7990)
                            && rig.stored.isEmpty() && rig.violations.isEmpty(),
                    rig.tail());
        });
        scenario("meet_a_name_answer_started_in_time_is_heard_past_the_listen_deadline", n -> {
            Rig rig = meetRig();
            List<String> notes = traced(rig);
            rig.people.match = (r, k) -> STRANGER;
            rig.people.listen = ListenScript.always(hearWords("my name is Sarah").after(10000).answeringAfter(3000));
            rig.started();
            rig.runUntil(40000);
            int listen = rig.first("listen", 0);
            int remember = rig.first("remember Sarah", listen);
            check(n, listen >= 0 && remember > listen && rig.timeOf(remember) - rig.timeOf(listen) >= 10000
                            && noteAt(notes, "an answer has started") > rig.timeOf(listen)
                            && noteAt(notes, "no answer from listening in time") < 0 && rig.violations.isEmpty(),
                    "listen=" + listen + " remember=" + remember + " notes=" + notes + " " + rig.tail());
        });
        scenario("meet_remember_failure_resumes_within_the_budget", n -> {
            Rig rig = meetRig();
            rig.people.match = (r, k) -> STRANGER;
            rig.people.listen = ListenScript.always(hearWords("i'm Sam"));
            rig.people.remembered = null;
            rig.started();
            rig.runUntil(22000);
            int remember = rig.first("remember Sam", 0);
            // Nothing said before he is back to wandering (the next stop's person pick is a
            // remark about someone just met, explore nav plan U7).
            long resumed = rig.timeOf(rig.firstAfter("eyes IDLE", rig.timeOf(remember)));
            check(n, remember >= 0 && resumedBy(rig, rig.timeOf(remember), rig.timeOf(remember) + 4000)
                            && rig.countPrefix("say", rig.timeOf(remember), resumed + 1) == 0
                            && rig.violations.isEmpty(),
                    rig.tail());
        });
        scenario("meet_match_that_never_answers_resumes_within_the_budget", n -> {
            Rig rig = meetRig();
            rig.people.match = null;
            rig.people.lines = null;
            rig.started();
            rig.runUntil(20000);
            int match = rig.first("match", 0);
            int lines = rig.first("lines", match);
            int name = rig.first("name person", lines);
            check(n, match >= 0 && rig.matchTimeouts.get(0) == 4000
                            && rig.timeOf(lines) == rig.timeOf(match) + 4000
                            && rig.timeOf(name) == rig.timeOf(lines) + 4000
                            && resumedBy(rig, rig.timeOf(name), rig.timeOf(name) + 1000)
                            && rig.violations.isEmpty(),
                    rig.tail());
        });
        scenario("meet_match_request_never_carries_names", n -> {
            Rig rig = meetRig(claudeTuning().peopleCooldownMs(0));
            final List<String> notes = new ArrayList<String>();
            rig.brain.setTrace(notes::add);
            rig.people.match = (r, k) -> k == 1 ? STRANGER
                    : CuriosityPort.MatchAnswer.known("Sarah", "Welcome back, {name}!", "Hi again!");
            rig.people.listen = ListenScript.always(hearWords("my name is Sarah"));
            // Each recently-met check says someone new, so she is met again (explore nav plan U7).
            rig.metChecks = (r, req, k) -> CuriosityPort.Recently.different();
            rig.started();
            rig.runUntil(40000);
            boolean clean = rig.meets.size() >= 2 && !rig.metCheckLog.isEmpty();
            for (String m : rig.meets) {
                clean &= m.startsWith("jpeg@") && m.endsWith(" person") && !m.contains("Sarah");
            }
            for (MetCheck c : rig.metCheckLog) {
                clean &= !c.request.met.toString().contains("Sarah");
            }
            boolean quiet = true;
            for (String note : notes) {
                quiet &= !note.contains("Sarah") && !note.contains("What's your name") && !note.contains("Welcome")
                        && !note.contains("LOOK-LINE");
            }
            check(n, clean && quiet && rig.count("say Welcome back, Sarah!") >= 1 && rig.violations.isEmpty(),
                    "meets=" + rig.meets + " quiet=" + quiet + " " + rig.tail());
        });
        scenario("meet_camera_stays_closed_and_eyes_think_while_matching", n -> {
            // The meeting-as-today path (no ears session): with the ears listening MEET keeps
            // the camera open and parked for the conversation it becomes (U8, KTD7).
            Rig rig = meetRig();
            rig.earsPresent = false;
            rig.people.match = (r, k) -> STRANGER;
            rig.people.listen = ListenScript.always(hearWords("i'm Sam"));
            rig.started();
            rig.runUntil(16000);
            int match = rig.first("match", 0);
            // The eyes last shown when the match goes out, up to its answer, are the thinking eyes.
            String eyes = "none";
            for (int i = 0; i < rig.log.size() && rig.log.get(i).t <= rig.timeOf(match) + 990; i++) {
                if (rig.log.get(i).what.startsWith("eyes")) {
                    eyes = rig.log.get(i).what;
                }
            }
            // MEET_LOOK opens it for one fresh look at the person before the match (the face crop).
            // Roaming keeps it open too (explore nav plan U4), from the end of the meet.
            java.util.Set<ExploreBrain.State> allowed = java.util.EnumSet.of(
                    ExploreBrain.State.SCAN, ExploreBrain.State.FACE, ExploreBrain.State.APPROACH,
                    ExploreBrain.State.MEET_LOOK, ExploreBrain.State.PAUSE, ExploreBrain.State.LOOK,
                    ExploreBrain.State.TURN, ExploreBrain.State.HOP);
            long over = entered(rig, ExploreBrain.State.PAUSE, rig.timeOf(match));
            check(n, eyes.equals("eyes THINKING") && allowed.containsAll(rig.openStates) && over > rig.timeOf(match)
                            && rig.countPrefix("camera open", rig.timeOf(match), over) == 0
                            && rig.violations.isEmpty(),
                    "open in " + rig.openStates + " " + rig.tail());
        });
        // ---- the face crop (owner report: a stored face showed the wall) ----
        scenario("meet_face_is_cut_from_a_fresh_look_after_turning_with_the_detectors_box", n -> {
            // Claude picks someone off to the right in the first scan frame, which the
            // detector missed; ORIENT turns toward them, and only then is the face cut,
            // from a new look and the detector's own person box in it.
            Vision afterTurn = (r, t) -> lookAt(r, t) >= 3 ? list(box("person", 0.9f, 0.5f, 0.55f, 0.3f, 0.8f))
                    : list();
            Rig rig = new Rig(claudeTuning().build(), CLEAR, afterTurn, true,
                    (r, req, k) -> pick(0, "woman in a red top", CuriosityPort.Kind.PERSON, "LOOK-LINE",
                            0.8f, 0.5f, 0.25f, 0.7f));
            rig.people.match = (r, k) -> STRANGER;
            rig.started();
            rig.runUntil(16000);
            int match = rig.first("match", 0);
            long lastTurn = -1;
            for (int i = 0; i < match && i >= 0; i++) {
                if (rig.log.get(i).what.startsWith("turn")) {
                    lastTurn = rig.log.get(i).t;
                }
            }
            String meet = rig.meets.isEmpty() ? "" : rig.meets.get(0);
            long shot = meet.startsWith("jpeg@") ? Long.parseLong(meet.substring(5, meet.indexOf(' '))) : -1;
            check(n, match >= 0 && meet.endsWith(" person") && lastTurn > 0 && shot > lastTurn
                            && rig.violations.isEmpty(),
                    "meet=" + meet + " lastTurn=" + lastTurn + " " + rig.tail());
        });
        scenario("meet_without_a_person_box_in_the_fresh_look_uses_claudes_box_in_its_own_frame", n -> {
            Rig rig = meetRig();
            rig.people.match = (r, k) -> STRANGER;
            rig.started();
            rig.runUntil(12000);
            int answer = rig.first("answer PICK", 0);
            int open = rig.first("camera open", answer);
            int match = rig.first("match", answer);
            // Frame 3 of the scan was captured at 3800: Claude's box is in that frame's coordinates.
            check(n, open > answer && match > open && rig.meets.size() == 1
                            && rig.meets.get(0).equals("jpeg@3800 person") && rig.violations.isEmpty(),
                    "meets=" + rig.meets + " " + rig.tail());
        });
        scenario("meet_the_person_box_is_the_one_matching_the_pick", n -> {
            // Claude's pick at the right edge; ORIENT's turn puts it in the middle.
            Detection expect = ExploreBrain.recentred(new Detection("woman", 1f, 0.7f, 0.2f, 0.9f, 0.9f), true);
            Detection them = box("person", 0.9f, 0.52f, 0.55f, 0.22f, 0.7f);
            Detection p = ExploreBrain.personIn(list(box("person", 0.9f, 0.12f, 0.5f, 0.2f, 0.7f), them,
                    box("chair", 0.9f, 0.5f, 0.5f, 0.3f, 0.7f)), expect, 0.3f);
            Detection none = ExploreBrain.personIn(list(box("person", 0.9f, 0.1f, 0.5f, 0.15f, 0.7f)), expect, 0.3f);
            Detection same = ExploreBrain.recentred(expect, false);
            check(n, p == them && none == null && same == expect && Math.abs(expect.x0 - 0.4f) < 1e-6,
                    p + " " + none + " " + expect);
        });
        scenario("meet_faceless_new_person_is_asked_but_never_stored_or_promised", n -> {
            Rig rig = meetRig();
            rig.people.match = (r, k) -> CuriosityPort.MatchAnswer.faceless("Hello! What's your name?",
                    "No worries, shy friend!");
            rig.people.listen = ListenScript.always(hearWords("my name is Sam"));
            rig.people.welcomed = CuriosityPort.Answer.line("So nice to meet you, Sam!");
            rig.started();
            rig.runUntil(16000);
            int ask = rig.first("say Hello! What's your name?", 0);
            int welcome = rig.first("welcome Sam", ask);
            int say = rig.first("say So nice to meet you, Sam!", welcome);
            check(n, ask >= 0 && welcome > ask && say > welcome && rig.stored.isEmpty()
                            && rig.countPrefix("remember", 0, 16001) == 0
                            && resumedBy(rig, rig.timeOf(say), rig.timeOf(say) + 1500) && rig.violations.isEmpty(),
                    "stored=" + rig.stored + " " + rig.tail());
        });
        scenario("meet_faceless_without_a_hello_line_says_the_friendly_line", n -> {
            Rig rig = meetRig();
            rig.people.match = (r, k) -> CuriosityPort.MatchAnswer.faceless("Hello! What's your name?",
                    "No worries, shy friend!");
            rig.people.listen = ListenScript.always(hearWords("i'm Sam"));
            rig.people.welcomed = CuriosityPort.Answer.failed();
            rig.started();
            rig.runUntil(16000);
            int welcome = rig.first("welcome Sam", 0);
            int say = rig.first("say No worries, shy friend!", welcome);
            check(n, welcome >= 0 && say > welcome && rig.stored.isEmpty() && rig.violations.isEmpty(),
                    "stored=" + rig.stored + " " + rig.tail());
        });
    }

    // ---- FaceCrop geometry (explore on Claude plan KTD5) ----

    private static void faceCropScenarios() {
        scenario("face_crop_expands_the_face_hit_1_6x", n -> {
            // A 100 px face box centred at (320, 200) in a 640x480 frame: a 160 px square on the same centre.
            int[] sq = FaceCrop.Square.aroundFace(270f, 150f, 370f, 250f, 640, 480);
            // A tall 80x120 box: the longer side sets the square (192 px), centred at (340, 160).
            int[] tall = FaceCrop.Square.aroundFace(300f, 100f, 380f, 220f, 640, 480);
            int[] edge = FaceCrop.Square.aroundFace(0f, 0f, 100f, 100f, 640, 480);
            check(n, sq[2] == 160 && sq[0] == 240 && sq[1] == 120 && tall[2] == 192 && tall[0] == 244
                            && tall[1] == 64 && edge[0] == 0 && edge[1] == 0 && edge[2] == 160,
                    java.util.Arrays.toString(sq) + " " + java.util.Arrays.toString(tall) + " "
                            + java.util.Arrays.toString(edge));
        });
        scenario("face_crop_has_no_top_of_person_fallback", n -> {
            // Owner report: a stored face showed the wall. The top quarter of a loose or
            // small person box is not a face, and nothing without a detected face is stored.
            java.util.List<String> names = new java.util.ArrayList<String>();
            for (java.lang.reflect.Method m : FaceCrop.Square.class.getDeclaredMethods()) {
                names.add(m.getName());
            }
            boolean noTopShare = true;
            for (java.lang.reflect.Field f : FaceCrop.class.getDeclaredFields()) {
                noTopShare &= !f.getName().equals("TOP_SHARE");
            }
            check(n, !names.contains("topOfPerson") && noTopShare, names.toString());
        });
        scenario("face_crop_square_shrinks_and_stays_inside_a_small_frame", n -> {
            // A 100 px face wants a 160 px square; a 120x90 frame holds at most 90, pushed inside.
            int[] sq = FaceCrop.Square.aroundFace(60f, 30f, 160f, 130f, 120, 90);
            check(n, sq[2] == 90 && sq[0] == 30 && sq[1] == 0, java.util.Arrays.toString(sq));
        });
        scenario("face_crop_region_is_the_person_box_in_pixels_clamped_with_an_even_width", n -> {
            // A live box, [0.66,0.58,0.88,0.79] of 640x480: x 422..564 (142 wide), y 278..380.
            int[] r = FaceCrop.Square.region(new Detection("person", 1f, 0.66f, 0.58f, 0.88f, 0.79f), 640, 480);
            // Odd widths drop a pixel; the right edge stays inside the frame.
            int[] edge = FaceCrop.Square.region(new Detection("person", 1f, 0.9f, 0.1f, 1.2f, 0.9f), 641, 480);
            int[] tiny = FaceCrop.Square.region(new Detection("person", 1f, 0.9f, 0.0f, 1.0f, 0.02f), 640, 480);
            check(n, r != null && r[0] == 422 && r[1] == 278 && r[2] == 142 && r[3] == 102
                            && edge != null && edge[0] + edge[2] <= 641 && edge[2] % 2 == 0 && tiny == null,
                    java.util.Arrays.toString(r) + " " + java.util.Arrays.toString(edge) + " "
                            + java.util.Arrays.toString(tiny));
        });
        scenario("face_crop_reads_pixel_and_normalized_boxes_alike", n -> {
            float[] px = FaceCrop.Square.fractions(new double[] {422, 278, 563, 379}, 640, 480);
            float[] norm = FaceCrop.Square.fractions(new double[] {0.66, 0.58, 0.88, 0.79}, 640, 480);
            float[] whole = FaceCrop.Square.fractions(new double[] {0, 0, 640, 480}, 640, 480);
            float[] bad = FaceCrop.Square.fractions(new double[] {0, Double.NaN, 1, 1}, 640, 480);
            boolean same = true;
            for (int i = 0; i < 4; i++) {
                same &= Math.abs(px[i] - norm[i]) < 0.005f;
            }
            check(n, same && Math.abs(norm[0] - 0.66f) < 1e-6 && whole[2] == 1f && whole[3] == 1f && bad == null,
                    java.util.Arrays.toString(px) + " " + java.util.Arrays.toString(norm));
        });
        scenario("replies_look_accepts_a_normalized_box", n -> {
            // Divided by 640x480 again, [0.66,...] would become a sub-pixel box in the top-left corner.
            java.util.Map<String, Object> json = new java.util.LinkedHashMap<String, Object>();
            json.put("interesting", Boolean.TRUE);
            json.put("frame", 1L);
            json.put("box", java.util.Arrays.<Object>asList(0.66, 0.58, 0.88, 0.79));
            json.put("kind", "person");
            json.put("label", "person");
            json.put("line", "Hi there!");
            CuriosityPort.Answer a = ClaudeReplies.look(json, new int[] {640}, new int[] {480});
            check(n, a.status == CuriosityPort.Answer.Status.PICK && Math.abs(a.box.x0 - 0.66f) < 1e-4
                            && Math.abs(a.box.y1 - 0.79f) < 1e-4,
                    a + " " + a.box);
        });
        scenario("replies_look_reads_the_frame_box_kind_and_line", n -> {
            java.util.Map<String, Object> json = new java.util.LinkedHashMap<String, Object>();
            json.put("interesting", Boolean.TRUE);
            json.put("frame", 3L);
            json.put("box", java.util.Arrays.<Object>asList(320L, 120L, 640L, 360L));
            json.put("kind", "animal");
            json.put("label", "cat");
            json.put("line", "What a fluffy cat!");
            int[] w = {640, 640, 640};
            int[] h = {480, 480, 480};
            CuriosityPort.Answer a = ClaudeReplies.look(json, w, h);
            json.put("frame", 4L);
            CuriosityPort.Answer bad = ClaudeReplies.look(json, w, h);
            json.put("interesting", Boolean.FALSE);
            CuriosityPort.Answer nothing = ClaudeReplies.look(json, w, h);
            check(n, a.status == CuriosityPort.Answer.Status.PICK && a.frame == 2 && a.kind == CuriosityPort.Kind.ANIMAL
                            && Math.abs(a.box.x0 - 0.5f) < 1e-4 && Math.abs(a.box.y1 - 0.75f) < 1e-4
                            && a.line.equals("What a fluffy cat!")
                            && bad.status == CuriosityPort.Answer.Status.FAILED
                            && nothing.status == CuriosityPort.Answer.Status.NOTHING,
                    a + " " + bad + " " + nothing);
        });
        scenario("replies_name_keeps_one_or_two_words_and_fills_the_placeholder", n -> {
            java.util.Map<String, Object> json = new java.util.LinkedHashMap<String, Object>();
            json.put("name", "Priya");
            CuriosityPort.Named ok = ClaudeReplies.name(json);
            json.put("name", "");
            CuriosityPort.Named none = ClaudeReplies.name(json);
            json.put("name", "the person said hello there");
            CuriosityPort.Named sentence = ClaudeReplies.name(json);
            json.remove("name");
            CuriosityPort.Named missing = ClaudeReplies.name(json);
            check(n, ok.status == CuriosityPort.Named.Status.NAME && ok.name.equals("Priya")
                            && none.status == CuriosityPort.Named.Status.NO_NAME
                            && sentence.status == CuriosityPort.Named.Status.NO_NAME
                            && missing.status == CuriosityPort.Named.Status.FAILED
                            && ClaudeReplies.fill("Hi {name}!", "Sam").equals("Hi Sam!"),
                    "ok=" + ok.status + " none=" + none.status + " sentence=" + sentence.status);
        });
    }

    // ---- heading, measured turns and the leg log (explore nav plan U2) ----

    /** The scripted tuning with the robot's gyro calibration: measured turns. */
    private static ExploreTuning.Builder gyroTuning() {
        return tuning().gyro(robotGyro());
    }

    /** Drives a Heading on its own: 10 ms steps, a reading every 100 ms, like the Rig. */
    static final class Bench {
        final Heading h;
        final YawSim yaw;
        long now;
        boolean commanded;
        boolean wheelsTurning;
        long wl = 1000;
        long wr = 1000;

        Bench(ExploreTuning t, YawSim yaw) {
            this.h = new Heading(t.gyro, t);
            this.yaw = yaw;
        }

        private void tick() {
            now += 10;
            yaw.advance(10);
            if (wheelsTurning) {
                wl++;
                wr++;
            }
            if (now % 100 == 0) {
                h.offer(yaw.wrap(clear(now), true, wl, wr), commanded);
            }
        }

        void run(long ms) {
            long until = now + ms;
            while (now < until) {
                tick();
            }
        }

        void still(long ms) {
            commanded = false;
            wheelsTurning = false;
            run(ms);
        }

        void drive(long ms) {
            commanded = true;
            wheelsTurning = true;
            run(ms);
            commanded = false;
            wheelsTurning = false;
        }

        /** Something turns him while nothing is commanded: the wheels move and the gyro sees it. */
        void pushed(long ms, int dir, double rateDegS) {
            double keep = yaw.rateDegS;
            yaw.rateDegS = rateDegS;
            yaw.turn(dir);
            commanded = false;
            wheelsTurning = true;
            run(ms);
            yaw.stop();
            yaw.rateDegS = keep;
            wheelsTurning = false;
        }

        /** A measured turn as the brain runs one: start on a reading, stop on the reading that reaches it. */
        void turnBy(int dir, double amount) {
            h.startTurn(dir, amount);
            yaw.turn(dir);
            commanded = true;
            long start = now;
            while (!h.turnReached() && now - start < 20000) {
                tick();
            }
            yaw.stop();
            commanded = false;
            h.stopped(now);
        }

        void turnTo(double target) {
            turnBy(h.directionTo(target), Math.abs(Heading.delta(h.degrees(), target)));
        }

        /** Tracked minus true heading, in (-180, 180]. */
        double error() {
            return Heading.delta(yaw.wrapped(), h.degrees());
        }
    }

    private static String f1(double v) {
        return String.format(java.util.Locale.US, "%.1f", v);
    }

    /** Each turn's start time and length in the log, in order. */
    private static List<long[]> turnTimes(Rig rig) {
        List<long[]> out = new ArrayList<long[]>();
        for (int i = 0; i < rig.log.size(); i++) {
            if (rig.log.get(i).what.startsWith("turn")) {
                long start = rig.log.get(i).t;
                out.add(new long[]{start, rig.timeOf(rig.first("stop", i)) - start});
            }
        }
        return out;
    }

    private static void headingScenarios() {
        scenario("heading_turn_takes_the_shorter_way_across_0_360", n -> {
            Bench b = new Bench(gyroTuning().build(), new YawSim(robotGyro()));
            b.still(1000);
            boolean math = Heading.delta(10, 340) == -30 && Heading.delta(350, 20) == 30
                    && Math.abs(Heading.delta(0, 180)) == 180 && Heading.wrap(-10) == 350;
            b.turnTo(20);
            b.still(1000);
            // From about 20 to 300: 80 right across 0, not 280 left.
            int dir = b.h.directionTo(300);
            b.turnTo(300);
            b.still(1000);
            double at300 = b.h.degrees();
            double true300 = b.yaw.trueDeg;
            double target = Heading.wrap(at300 + 180);
            double before = b.yaw.trueDeg;
            b.turnTo(target);
            b.still(1000);
            double turned180 = Math.abs(b.yaw.trueDeg - before);
            check(n, math && dir == Heading.RIGHT && Math.abs(Heading.delta(at300, 300)) <= 5
                            && at300 >= 0 && at300 < 360 && Math.abs(true300 - (-60)) <= 5
                            && Math.abs(Heading.delta(b.h.degrees(), target)) <= 5 && Math.abs(turned180 - 180) <= 5
                            && Math.abs(b.error()) <= 2,
                    "math=" + math + " dir=" + dir + " at300=" + f1(at300) + " true=" + f1(true300)
                            + " turned180=" + f1(turned180) + " err=" + f1(b.error()));
        });
        scenario("heading_bias_drift_is_re_estimated_at_each_stop", n -> {
            // The bias moves by 0.1 deg/s (6.4 counts) after the first stop: a bias fixed
            // there would be ~5 deg out after the minute; re-estimated at each stop, it isn't.
            final double drift = 0.1 * robotGyro().countSecondsPer360 / 360.0;
            YawSim yaw = new YawSim(robotGyro()) {
                @Override
                double biasAt(long t) {
                    return biasCounts + (t >= 2000 ? drift : 0);
                }
            };
            Bench b = new Bench(gyroTuning().build(), yaw);
            b.still(2000);
            double firstBias = b.h.biasCounts();
            while (b.now < 62000) {
                b.turnBy(Heading.LEFT, 90);
                b.still(2000);
                b.drive(5000);
                b.still(2000);
                b.turnBy(Heading.RIGHT, 45);
                b.still(2000);
            }
            check(n, Math.abs(firstBias - 92) < 0.5 && Math.abs(b.h.biasCounts() - (92 + drift)) < 1
                            && Math.abs(b.error()) < 1.0,
                    "firstBias=" + f1(firstBias) + " bias=" + f1(b.h.biasCounts()) + " err=" + f1(b.error()));
        });
        scenario("heading_motion_during_a_stop_leaves_the_bias_alone", n -> {
            // Nothing commanded, but the wheels move and he turns (pushed, or coasting):
            // those rates are not bias samples, and the heading still follows the turn.
            Bench b = new Bench(gyroTuning().build(), new YawSim(robotGyro()));
            b.still(2000);
            double before = b.h.biasCounts();
            b.pushed(1500, Heading.LEFT, 30);
            b.still(300);
            double after = b.h.biasCounts();
            double tracked = b.h.degrees();
            b.still(2000);
            check(n, Math.abs(before - 92) < 0.5 && Math.abs(after - 92) < 0.5
                            && Math.abs(b.h.biasCounts() - 92) < 0.5 && Math.abs(Heading.delta(tracked, 45)) <= 2
                            && Math.abs(b.error()) <= 2,
                    "before=" + f1(before) + " after=" + f1(after) + " end=" + f1(b.h.biasCounts())
                            + " tracked=" + f1(tracked) + " err=" + f1(b.error()));
        });
        scenario("measured_90_degree_turn_without_bias_error_ends_within_tolerance", n -> {
            Rig rig = new Rig(gyroTuning().turnChance(1.0).turnDeg(90, 90).build(), CLEAR);
            final List<String> notes = new ArrayList<String>();
            rig.brain.setTrace(notes::add);
            rig.started();
            rig.runUntil(5000);
            List<long[]> turns = turnTimes(rig);
            double r = rig.yaw.turnResults.isEmpty() ? 0 : Math.abs(rig.yaw.turnResults.get(0));
            boolean noted = false;
            for (String note : notes) {
                noted |= note.matches("measured turn: asked 90 deg, turned \\d+ deg, overshoot \\d+ deg");
            }
            check(n, !turns.isEmpty() && Math.abs(r - 90) <= rig.tuning.turnToleranceDeg
                            && turns.get(0)[1] >= 1300 && noted && rig.violations.isEmpty(),
                    "result=" + f1(r) + " turnMs=" + (turns.isEmpty() ? -1 : turns.get(0)[1]) + " notes=" + notes
                            + " " + rig.tail());
        });
        scenario("measured_turn_overshoot_is_learned_and_the_next_turn_is_closer", n -> {
            Rig rig = new Rig(gyroTuning().turnChance(1.0).turnDeg(90, 90).build(), CLEAR);
            rig.yaw.coastDeg = 15;
            rig.started();
            rig.runUntil(20000);
            List<Double> r = rig.yaw.turnResults;
            double e1 = r.size() > 0 ? Math.abs(Math.abs(r.get(0)) - 90) : -1;
            double e2 = r.size() > 1 ? Math.abs(Math.abs(r.get(1)) - 90) : -1;
            double e3 = r.size() > 2 ? Math.abs(Math.abs(r.get(2)) - 90) : -1;
            check(n, r.size() >= 3 && e1 >= 10 && e2 < e1 - 3 && e3 < e2 + 1 && e3 <= rig.tuning.turnToleranceDeg
                            && rig.brain.heading().overshootDeg() > 10 && rig.violations.isEmpty(),
                    "results=" + r + " overshoot=" + f1(rig.brain.heading().overshootDeg()) + " " + rig.tail());
        });
        scenario("stalled_leg_logs_no_distance_for_the_stalled_time", n -> {
            // A 5 s leg from 1300; the wheels drive until 2500, then only creep (2 counts
            // per 100 ms): stalled at ~3600. The leg keeps the distance up to 2500 only.
            Rig rig = new Rig(gyroTuning().hopTicks(20).stall(1000, 1000, 60).build(), CLEAR);
            rig.simWheels = true;
            rig.blockedFrom = 2500;
            rig.started();
            rig.runUntil(3700);
            long stop = rig.timeOf(rig.firstAfter("stop", 1301));
            List<Heading.Leg> legs = rig.brain.heading().legs();
            long counts = legs.size() == 1 ? legs.get(0).counts : -1;
            check(n, stop >= 3300 && stop <= 3700 && rig.count("startle") == 1 && legs.size() == 1
                            && Math.abs(counts - rig.countsBeforeBlocked) <= 3,
                    "stop@" + stop + " legs=" + legs + " driven=" + rig.countsBeforeBlocked + " " + rig.tail());
        });
        scenario("clean_drive_off_restarts_the_leg_log", n -> {
            // A leg cut short by an obstacle, the back-off, the escape turn, then a clean
            // leg: before it ends the log holds the way in; after, only the clean leg.
            Rig rig = new Rig(gyroTuning().build(), t -> t >= 1600 && t < 1700 ? obstacle(t) : clear(t));
            rig.simWheels = true;
            rig.started();
            while (rig.now < 10000 && rig.firstAfter("hop", 3000) < 0) {
                rig.runUntil(rig.now + 10);
            }
            rig.runUntil(rig.now + 300);
            List<Heading.Leg> during = rig.brain.heading().legs();
            int hop = rig.firstAfter("hop", 3000);
            long cleanStop = rig.timeOf(rig.first("stop", hop));
            while (rig.now < 12000 && cleanStop < 0) {
                rig.runUntil(rig.now + 10);
                cleanStop = rig.timeOf(rig.first("stop", hop));
            }
            rig.runUntil(rig.now + 100);
            List<Heading.Leg> after = rig.brain.heading().legs();
            // The cut-short leg (1300-1600: 30 counts) and the back-off (250 ms: 25), facing the other way.
            boolean reverse = during.size() == 2 && Math.abs(during.get(0).counts - 30) <= 3
                    && Math.abs(during.get(1).counts - 25) <= 3
                    && Math.abs(Heading.delta(during.get(0).heading + 180, during.get(1).heading)) <= 2;
            check(n, rig.count("startle") == 1 && reverse && after.size() == 1
                            && Math.abs(after.get(0).counts - 75) <= 3 && rig.violations.isEmpty(),
                    "during=" + during + " after=" + after + " " + rig.tail());
        });
        scenario("uncalibrated_turns_stay_timed_even_with_gyro_readings", n -> {
            Rig rig = new Rig(tuning().turnChance(1.0).build(), CLEAR);
            rig.yaw = new YawSim(robotGyro());
            rig.simWheels = true;
            rig.started();
            rig.runUntil(8000);
            List<long[]> turns = turnTimes(rig);
            check(n, turns.size() >= 2 && turns.get(0)[1] == 500 && turns.get(1)[1] == 500
                            && !rig.brain.heading().usable(rig.now) && rig.brain.heading().legs().isEmpty()
                            && rig.violations.isEmpty(),
                    "turns=" + turns.size() + " " + rig.tail());
        });
        scenario("calibrated_without_gyro_in_the_readings_turns_stay_timed", n -> {
            Rig rig = new Rig(gyroTuning().turnChance(1.0).build(), CLEAR);
            rig.yaw = null;
            rig.started();
            rig.runUntil(5000);
            List<long[]> turns = turnTimes(rig);
            check(n, !turns.isEmpty() && turns.get(0)[1] == 500 && !rig.brain.heading().usable(rig.now)
                            && rig.violations.isEmpty(),
                    rig.tail());
        });
        scenario("measured_escape_turn_turns_its_angle_not_its_time", n -> {
            // Edge on the right mid-hop: the escape turns left at least 90 deg (2 s at
            // 45 deg/s), not the timed 800 ms, then drives on.
            Rig rig = new Rig(gyroTuning().escapeDeg(90, 145, 360).build(),
                    t -> t >= 1600 && t < 1700 ? edgeRight(t) : clear(t));
            rig.yaw.rateDegS = 45;
            rig.started();
            rig.runUntil(7000);
            List<long[]> turns = turnTimes(rig);
            double r = rig.yaw.turnResults.isEmpty() ? 0 : rig.yaw.turnResults.get(0);
            int turn = rig.firstAfter("turn", 1600);
            check(n, rig.what(turn).equals("turn LEFT") && Math.abs(r - 90) <= rig.tuning.turnToleranceDeg
                            && turns.get(0)[1] >= 1800 && rig.firstAfter("hop", turns.get(0)[0] + turns.get(0)[1]) >= 0
                            && rig.violations.isEmpty(),
                    "result=" + f1(r) + " turnMs=" + (turns.isEmpty() ? -1 : turns.get(0)[1]) + " " + rig.tail());
        });
        scenario("measured_orient_turns_to_the_picked_looks_heading_plus_its_offset", n -> {
            // Claude picks a cat right of centre (cx 0.6) in the FIRST look: after a
            // two-step scan he turns back to that look's heading and 18 deg (0.6 x 30)
            // right of it, wherever the scan went.
            Vision room = (r, t) -> list();
            Claude catInFirst = (r, req, nth) ->
                    pick(0, "cat", CuriosityPort.Kind.ANIMAL, "Hello kitty, what a fluffy tail!", 0.8f, 0.5f, 0.2f, 0.3f);
            Rig rig = new Rig(claudeTuning().gyro(robotGyro()).scanTurnDeg(40).cameraHalfFovDeg(30).build(),
                    CLEAR, room, true, catInFirst);
            rig.started();
            while (rig.now < 15000 && rig.first("say Hello kitty", 0) < 0) {
                rig.runUntil(rig.now + 10);
            }
            List<Double> r = rig.yaw.turnResults;
            double facing = rig.yaw.trueDeg;
            check(n, rig.first("say Hello kitty", 0) >= 0 && r.size() == 3
                            && Math.abs(Math.abs(r.get(0)) - 40) <= 5 && Math.abs(Math.abs(r.get(1)) - 40) <= 5
                            && Math.abs(Heading.delta(Heading.wrap(facing), Heading.wrap(-18))) <= 6
                            && rig.countPrefix("hop", 0, rig.now) == 0 && rig.violations.isEmpty(),
                    "turns=" + r + " facing=" + f1(facing) + " " + rig.tail());
        });
    }

    // ---- the camera while roaming, steering by openness, look-then-go (explore nav plan U4) ----
    //
    // No curiosity stops: the camera opens with roaming at 300, looks arrive every
    // 500 ms from 1000 (captured 200 ms earlier), and the first pause ends at 1300.

    private static ExploreTuning.Builder navTuning() {
        return tuning()
                .hopTicks(8)
                .curiosityMs(1000000, 1000000)
                .lookTiming(200, 3000, 2000)
                .cameraBackoffMs(10000)
                .steerBlockedTurn(60, 2, 2);
    }

    /** A profile: bins 0-5 (left), 6-9 (ahead) and 10-15 (right) at these openness values. */
    static Openness.Profile prof(float confidence, float left, float ahead, float right) {
        float[] b = new float[Openness.BINS];
        for (int i = 0; i < b.length; i++) {
            b[i] = i < 6 ? left : i < 10 ? ahead : right;
        }
        return new Openness.Profile(b, confidence);
    }

    private static final Vision NOTHING = (rig, t) -> list();

    private static Rig navRig(ExploreTuning.Builder b, Feed feed, OpenView view) {
        Rig rig = new Rig(b.build(), feed, NOTHING, true);
        rig.openView = view;
        return rig;
    }

    /** Hop ticks in each leg, in order. */
    private static List<Integer> legs(Rig rig) {
        List<Integer> out = new ArrayList<Integer>();
        int ticks = 0;
        for (Event e : rig.log) {
            if (e.what.equals("hop")) {
                ticks++;
            } else if (e.what.equals("stop") && ticks > 0) {
                out.add(ticks);
                ticks = 0;
            }
        }
        if (ticks > 0) {
            out.add(ticks);
        }
        return out;
    }

    // ---- the leg decision waits for a look to steer by (explore nav plan KTD9, live 2026-09-25) ----
    //
    // Live, looks come every ~1-2 s: the decision right after a turn found no look taken
    // since it settled, and every decision after a clean leg threw the leg's own looks
    // away (a leg's stop counted as a turn's), so no leg was ever steered.

    private static void steerWaitScenarios() {
        scenario("steer_waits_for_a_look_after_a_turn_and_uses_the_legs_own_looks", n -> {
            // Right open, left blocked; an obstacle at 4000 mid-leg: startle, back-off, the
            // escape turn LEFT stops at 6000 and the pause ends at 6300, before the next look.
            Rig rig = navRig(navTuning().pauseMs(300, 300), t -> t >= 3950 && t < 4050 ? obstacle(t) : clear(t),
                    (r, t) -> prof(0.9f, 0.1f, 0.4f, 0.9f));
            rig.lookEveryMs = 1500;
            List<String> notes = traced(rig);
            rig.started();
            rig.runUntil(12000);
            int esc = rig.firstAfter("turn LEFT", 4000);
            int escStop = rig.firstAfter("stop", rig.timeOf(esc));
            long stopAt = rig.timeOf(escStop);
            long waited = noteAt(notes, "waiting up to 2000 ms for a look to steer by", stopAt);
            long steered = noteAt(notes, "steer: right", stopAt);
            int next = rig.firstMotionAfter(stopAt);
            // The clean leg after that bend ends at 10200: its own look (captured 9700) steers.
            int legStop = rig.firstAfter("stop", rig.timeOf(rig.firstAfter("hop", rig.timeOf(next))));
            long legEnd = rig.timeOf(legStop);
            long legSteer = noteAt(notes, "steer: right", legEnd);
            long legWait = noteAt(notes, "waiting", legEnd);
            check(n, esc > 0 && waited == stopAt + 300 && steered == 7500 && rig.what(next).equals("turn RIGHT")
                            && rig.countPrefix("hop", stopAt, rig.timeOf(next)) == 0
                            && legEnd > 0 && legSteer == legEnd + 300 && (legWait < 0 || legWait > legSteer)
                            && noteAt(notes, "no look to steer by in time", 0) < 0 && rig.violations.isEmpty(),
                    "esc stop@" + stopAt + " waited@" + waited + " steered@" + steered + " next=" + rig.what(next)
                            + " legEnd=" + legEnd + " legSteer=" + legSteer + " " + notes + " " + rig.tail());
        });
        scenario("steer_wait_times_out_to_todays_leg_and_never_waits_with_the_camera_backed_off", n -> {
            // A camera that gives no looks: the first decision (at 1300) waits steerWaitMs and
            // then hops as before; once the camera is backed off, decisions do not wait at all.
            Rig rig = new Rig(navTuning().steerWaitMs(1500).build(), CLEAR, (r, t) -> null, true);
            rig.openView = (r, t) -> prof(0.9f, 0.1f, 0.4f, 0.9f);
            List<String> notes = traced(rig);
            rig.started();
            rig.runUntil(20000);
            long timedOut = noteAt(notes, "no look to steer by in time", 0);
            long off = noteAt(notes, "camera gave no look in time", 0);
            int hop = rig.firstMotionAfter(0);
            check(n, noteAt(notes, "waiting", 0) == 1300 && timedOut == 2800 && rig.what(hop).equals("hop")
                            && rig.timeOf(hop) == 2800 && off > 0
                            && (noteAt(notes, "waiting", off) < 0 || noteAt(notes, "waiting", off) >= off + 10000)
                            && rig.countPrefix("hop", off, off + 10000) > 0 && notesStarting(notes, "steer:") == 0
                            && rig.violations.isEmpty(),
                    "timedOut@" + timedOut + " off@" + off + " hop=" + rig.what(hop) + "@" + rig.timeOf(hop) + " "
                            + notes + " " + rig.tail());
        });
    }

    // ---- CPL hiccups on plain floor (owner-approved 2026-09-25) ----
    //
    // Live on carpet, 11 of 12 hazards were the controller refusing forward (CPL=2) as the
    // nose dipped at a start or stop, with our own tof reading ordinary floor.

    private static SensorReading cpl2Edge(long t) {
        return new SensorReading(t, 300 + jitter(t), 900, 900, 2, false);
    }

    private static void cplHiccupScenarios() {
        scenario("cpl_on_plain_floor_is_retried_once_and_the_leg_drives_on", n -> {
            // An 8-tick leg from 1300 (to 3300); one CPL=2 reading at 1600.
            Rig rig = new Rig(tuning().hopTicks(8).build(), t -> t == 1600 ? cpl2(t) : clear(t));
            List<String> notes = traced(rig);
            rig.started();
            rig.runUntil(4000);
            int stop = rig.firstAfter("stop", 1600);
            int retry = rig.firstAfter("hop", 1601);
            check(n, rig.timeOf(stop) == 1600 && rig.timeOf(retry) == 2000 && rig.countPrefix("hop", 1601, 4000) == 7
                            && rig.count("startle") == 0 && noteAt(notes, "hazard", 0) < 0
                            && noteAt(notes, "controller refused forward (CPL) on plain floor", 0) == 1600
                            && rig.violations.isEmpty(),
                    "stop@" + rig.timeOf(stop) + " retry@" + rig.timeOf(retry) + " hops after="
                            + rig.countPrefix("hop", 1601, 4000) + " " + notes + " " + rig.tail());
        });
        scenario("cpl_again_at_the_same_spot_after_the_retry_is_a_hazard", n -> {
            // The retry starts at 2000; CPL=2 again at 2100 is within cplRetryWindowMs.
            Rig rig = new Rig(tuning().hopTicks(8).build(), t -> t == 1600 || t == 2100 ? cpl2(t) : clear(t));
            List<String> notes = traced(rig);
            rig.started();
            rig.runUntil(4000);
            int startle = rig.first("startle", 0);
            check(n, rig.timeOf(startle) == 2100 && noteAt(notes, "hazard while HOP: CPL", 0) == 2100
                            && notesStarting(notes, "controller refused forward") == 1 && rig.violations.isEmpty(),
                    "startle@" + rig.timeOf(startle) + " " + notes + " " + rig.tail());
        });
        scenario("cpl_with_our_sensor_at_an_edge_is_a_hazard_at_once", n -> {
            Rig rig = new Rig(tuning().hopTicks(8).build(), t -> t == 1600 ? cpl2Edge(t) : clear(t));
            List<String> notes = traced(rig);
            rig.started();
            rig.runUntil(3000);
            int startle = rig.first("startle", 0);
            check(n, rig.timeOf(startle) == 1600 && noteAt(notes, "controller refused forward", 0) < 0
                            && rig.countPrefix("hop", 1601, 2400) == 0 && rig.violations.isEmpty(),
                    "startle@" + rig.timeOf(startle) + " " + notes + " " + rig.tail());
        });
        scenario("cpl_hiccups_spread_over_a_leg_do_not_make_him_wedged", n -> {
            // A 24-tick leg from 1300; three CPL=2 hiccups, each well after the last retry.
            // Counted as hazards (cap 3), today they would corner him.
            Rig rig = new Rig(tuning().hopTicks(24).build(),
                    t -> t == 1600 || t == 3700 || t == 5800 ? cpl2(t) : clear(t));
            List<String> notes = traced(rig);
            rig.started();
            rig.runUntil(9000);
            check(n, notesStarting(notes, "controller refused forward (CPL) on plain floor") == 3
                            && rig.count("startle") == 0 && noteAt(notes, "wedged", 0) < 0
                            && noteAt(notes, "cornered", 0) < 0 && noteAt(notes, "hazard", 0) < 0
                            && rig.countPrefix("hop", 5801, 9000) > 0
                            && !rig.statesSeen.contains(ExploreBrain.State.CORNERED) && !rig.statesSeen.contains(ExploreBrain.State.STARTLE) && rig.violations.isEmpty(),
                    notes + " " + rig.tail());
        });
    }

    // ---- mid-leg re-aim (owner-approved 2026-09-25, KTD9) ----
    //
    // The drive can't curve: forward is straight and turns are in place. A look during a
    // leg that finds the open space off to one side stops, turns a little toward it, and
    // drives the rest of the leg.

    private static ExploreTuning.Builder reaimTuning() {
        return navTuning().gyro(robotGyro()).hopTicks(16).reaim(15, 30, 2000, 2);
    }

    private static final Openness.Profile ALL_OPEN_PROF = prof(0.9f, 0.9f, 0.9f, 0.9f);

    private static void reaimScenarios() {
        scenario("reaim_open_space_drifting_right_mid_leg_turns_a_little_toward_it_and_drives_on", n -> {
            // Straight ahead is open until 2500; then the open space lies right until he turns.
            Rig rig = navRig(reaimTuning(), CLEAR, (r, t) -> t >= 2500 && r.count("turn RIGHT") == 0
                    ? prof(0.9f, 0.2f, 0.5f, 0.9f) : ALL_OPEN_PROF);
            List<String> notes = traced(rig);
            rig.started();
            rig.runUntil(8000);
            long reaim = noteAt(notes, "re-aim: ", 0);
            int turn = rig.firstMotionAfter(reaim);
            int after = rig.firstMotionAfter(rig.timeOf(rig.firstAfter("stop", rig.timeOf(turn))));
            double r = rig.yaw.turnResults.isEmpty() ? 0 : rig.yaw.turnResults.get(0);
            int before = rig.countPrefix("hop", 0, reaim);
            int rest = rig.countPrefix("hop", reaim, rig.timeOf(rig.firstAfter("stop", rig.timeOf(after))) + 1);
            check(n, reaim > 2500 && notesStarting(notes, "re-aim") == 1 && rig.what(turn).equals("turn RIGHT")
                            && -r >= 15 - rig.tuning.turnToleranceDeg && -r <= 30 + rig.tuning.turnToleranceDeg && rig.what(after).equals("hop")
                            && before + rest >= 16 && before + rest <= 17 && rig.count("startle") == 0
                            && rig.violations.isEmpty(),
                    "reaim@" + reaim + " turn=" + rig.what(turn) + " r=" + f1(r) + " hops " + before + "+" + rest
                            + " " + notes + " " + rig.tail());
        });
        scenario("reaim_never_with_the_open_space_straight_ahead", n -> {
            Rig rig = navRig(reaimTuning(), CLEAR, (r, t) -> ALL_OPEN_PROF);
            List<String> notes = traced(rig);
            rig.started();
            rig.runUntil(12000);
            List<Integer> legs = legs(rig);
            check(n, notesStarting(notes, "re-aim") == 0 && !legs.isEmpty() && legs.get(0) == 16
                            && rig.count("turn") == 0 && rig.violations.isEmpty(),
                    "legs=" + legs + " " + notes + " " + rig.tail());
        });
        scenario("reaim_is_rate_limited", n -> {
            // The open space always reads right, however he turns: at most one re-aim per 2 s.
            Rig rig = navRig(reaimTuning(), CLEAR, (r, t) -> prof(0.9f, 0.2f, 0.6f, 0.9f));
            List<String> notes = traced(rig);
            rig.started();
            rig.runUntil(15000);
            List<Long> at = new ArrayList<Long>();
            for (String x : notes) {
                if (x.startsWith("re-aim: ", x.indexOf(' ') + 1)) {
                    at.add(Long.parseLong(x.substring(0, x.indexOf(' '))));
                }
            }
            boolean spaced = true;
            for (int i = 1; i < at.size(); i++) {
                spaced &= at.get(i) - at.get(i - 1) >= 2000;
            }
            check(n, at.size() >= 2 && spaced && rig.violations.isEmpty(), "re-aims at " + at + " " + rig.tail());
        });
        scenario("reaim_never_toward_a_blocked_side", n -> {
            // The first roaming turn's way will not turn (blocked); he backs up and goes the
            // other way. On the leg after it, the open space reads toward the blocked side.
            Rig rig = new Rig(escTuning().turnChance(1.0).hopTicks(16).reaim(15, 30, 2000, 2).build(), CLEAR,
                    NOTHING, true);
            rig.simWheels = true;
            rig.yaw.blockFirstTurnsSide = true;
            rig.openView = (r, t) -> {
                int f = r.first("turn", 0);
                if (f < 0 || r.countPrefix("hop", r.timeOf(f), Long.MAX_VALUE) == 0) {
                    return null;
                }
                // Open toward the side of the first (blocked) turn.
                return r.what(f).equals("turn LEFT") ? prof(0.9f, 0.9f, 0.5f, 0.2f) : prof(0.9f, 0.2f, 0.5f, 0.9f);
            };
            List<String> notes = traced(rig);
            rig.started();
            rig.runUntil(15000);
            int f = rig.first("turn", 0);
            int hop = rig.firstAfter("hop", rig.timeOf(f));
            int legStop = rig.firstAfter("stop", rig.timeOf(hop));
            String blocked = rig.what(f);
            long skipped = noteAt(notes, "re-aim " + (blocked.equals("turn LEFT") ? "LEFT" : "RIGHT")
                    + " skipped: that side is blocked", rig.timeOf(hop));
            check(n, f >= 0 && hop > 0 && skipped > 0 && skipped < rig.timeOf(legStop)
                            && rig.countPrefix(blocked, rig.timeOf(hop), rig.timeOf(legStop) + 1) == 0
                            && rig.violations.isEmpty(),
                    "first=" + blocked + " hop@" + rig.timeOf(hop) + " legStop@" + rig.timeOf(legStop) + " skipped@"
                            + skipped + " " + notes + " " + rig.tail());
        });
    }

    // ---- going somewhere new (explore nav plan U10, R18) ----

    /** U10 on with the shipped weights; the harness's 100 counts/s is 0.25 m/s (the guessed real speed) at 400 counts/m. */
    private static ExploreTuning.Builder coverageTuning(ExploreTuning.Builder b) {
        return b.coverageGrid(400, 0.5, 180000, 1.5).coverageSteer(0.3f, 0.5f, 0.2f, 60).coverageTurns(0.7f, 0.3);
    }

    /** An 8 x 6 m room (192 cells), camera on, gyro and wheels simulated, roaming with today's leg lengths and turns. */
    private static Rig roomRig(boolean novelty) {
        return roomRig(novelty, 1);
    }

    private static Rig roomRig(boolean novelty, long seed) {
        ExploreTuning.Builder b = navTuning().gyro(robotGyro()).hopTicks(16, 40).turnChance(0.7).turnDeg(20, 55)
                .cap(8, 20000, 30000)
                // The room's walls stall him with a working board; the post-stall wait would only
                // take time from both runs. Coverage is measured without it.
                .stallRecoverOff();
        b = novelty ? coverageTuning(b) : coverageTuning(b).coverageOff();
        Rig[] h = new Rig[1];
        rigSeed = seed;
        Rig rig = new Rig(b.build(), t -> h[0].room.reading(t, h[0].yaw.trueDeg), NOTHING, true);
        rigSeed = 1;
        h[0] = rig;
        rig.room = new Room(8, 6, 400);
        rig.simWheels = true;
        rig.openView = (r, t) -> r.room.view(r.yaw.trueDeg, r.tuning.cameraHalfFovDeg);
        return rig;
    }

    /** A Coverage fed straight: drives metres along deg from t (1 s per metre, readings every 100 ms). */
    static final class CoverageBench {
        final Coverage cov;
        long t;
        long wheels;

        CoverageBench(ExploreTuning tuning) {
            cov = new Coverage(tuning);
            offer(0);
        }

        void drive(double metres, double deg) {
            int steps = (int) Math.round(Math.abs(metres) * 10);
            for (int i = 0; i < steps; i++) {
                wheels += Math.round(Math.signum(metres) * 100);
                t += 100;
                offer(deg);
            }
        }

        void offer(double deg) {
            cov.offer(new SensorReading(t, 300, 100, 100, null, false, true, wheels, wheels, true, 0, 0, 0), deg, true);
        }
    }

    private static ExploreTuning benchTuning() {
        // 1000 counts/m: 100 counts per reading is 0.1 m.
        return tuning().coverageGrid(1000, 0.5, 180000, 1.5).coverageSteer(0.3f, 0.5f, 0.2f, 60).build();
    }

    private static void coverageScenarios() {
        scenario("coverage_open_room_covers_more_cells_than_with_novelty_off", n -> {
            // Five runs each (brain seeds 1-5): one run's count swings with where the walls turn him.
            int a = 0;
            int b = 0;
            boolean clean = true;
            StringBuilder each = new StringBuilder();
            for (long seed = 1; seed <= 5; seed++) {
                Rig on = roomRig(true, seed);
                on.started();
                on.runUntil(600000);
                Rig off = roomRig(false, seed);
                off.started();
                off.runUntil(600000);
                a += on.room.cells.size();
                b += off.room.cells.size();
                clean &= on.violations.isEmpty() && off.violations.isEmpty();
                each.append(' ').append(on.room.cells.size()).append('/').append(off.room.cells.size());
            }
            check(n, a >= b * 1.3 && clean, "cells on/off per seed" + each);
        });
        scenario("coverage_two_equally_open_ways_picks_the_unvisited_one", n -> {
            ExploreTuning tu = benchTuning();
            CoverageBench b = new CoverageBench(tu);
            // Out 1.5 m at 20 deg left and back: the ground ahead-left is covered, ahead-right is not.
            b.drive(1.5, 20);
            b.drive(-1.5, 20);
            long now = b.t;
            Openness.Profile p = prof(0.9f, 0.9f, 0.1f, 0.9f);
            RoamSteer.Plan without = new RoamSteer(tu).plan(p, Double.NaN, null);
            RoamSteer.Plan with = new RoamSteer(tu).plan(p, Double.NaN, x -> b.cov.novelty(Heading.wrap(x), now));
            check(n, without.side == RoamSteer.LEFT && with.side == RoamSteer.RIGHT && !with.turnOnly
                            && with.novelty >= 0.9 && b.cov.novelty(15, now) < 0.5,
                    "without=" + without + " with=" + with + " left=" + f1(b.cov.novelty(15, now)));
        });
        scenario("coverage_open_floor_gives_a_longer_leg_and_a_blocked_view_still_shortens", n -> {
            int[] first = new int[5];
            OpenView[] views = {ALL_OPEN, ALL_OPEN, ALL_OPEN, (r, t) -> prof(0.9f, 0.5f, 0.5f, 0.5f),
                    (r, t) -> prof(0.9f, 0.1f, 0.1f, 0.1f)};
            for (int i = 0; i < 5; i++) {
                ExploreTuning.Builder b = navTuning();
                if (i != 2) {
                    b = coverageTuning(b.gyro(robotGyro()));
                    if (i == 1) {
                        b = b.coverageOff();
                    }
                } else {
                    b = coverageTuning(b); // no gyro: uncalibrated, today's legs
                }
                Rig rig = navRig(b, CLEAR, views[i]);
                rig.simWheels = true;
                rig.started();
                rig.runUntil(i == 4 ? 15000 : 25000);
                List<Integer> legs = legs(rig);
                int max = 0;
                for (int l : legs) {
                    max = Math.max(max, l);
                }
                first[i] = i == 4 ? max : legs.isEmpty() ? -1 : legs.get(0);
            }
            // 8 ticks drawn; open and all new: 8 + 1.0 x (60 - 8) = 60.
            check(n, first[0] == 60 && first[1] == 8 && first[2] == 8 && first[3] > 0 && first[3] < 8
                            && first[4] <= 2,
                    "open=" + first[0] + " off=" + first[1] + " uncalibrated=" + first[2] + " middling=" + first[3]
                            + " blocked max=" + first[4]);
        });
        scenario("coverage_floor_sensor_still_ends_a_long_leg", n -> {
            // A 60-tick leg on open new ground from 1300; an obstacle at 5000 (tick 15).
            Rig rig = navRig(coverageTuning(navTuning().gyro(robotGyro())), t -> t >= 5000 && t < 5500 ? obstacle(t)
                    : clear(t), ALL_OPEN);
            rig.simWheels = true;
            rig.started();
            rig.runUntil(6000);
            int stop = rig.firstAfter("stop", 1300);
            check(n, rig.timeOf(stop) == 5000 && rig.count("startle") == 1
                            && rig.countPrefix("hop", 5000, 6000) == 0 && rig.violations.isEmpty(),
                    "stop@" + rig.timeOf(stop) + " " + rig.tail());
        });
        scenario("coverage_visited_cells_fade_so_an_old_area_is_eligible_again", n -> {
            ExploreTuning tu = benchTuning();
            CoverageBench b = new CoverageBench(tu);
            b.drive(1.5, 0);
            b.drive(-1.5, 0);
            long t0 = b.t;
            double fresh = b.cov.novelty(0, t0);
            int cells = b.cov.cells(t0);
            double half = b.cov.novelty(0, t0 + 90000);
            double faded = b.cov.novelty(0, t0 + 180000);
            int left = b.cov.cells(t0 + 180000);
            check(n, fresh < 0.1 && cells >= 3 && half > 0.3 && half < 0.7 && faded == 1.0 && left == 0
                            && b.cov.novelty(180, t0) == 1.0,
                    "fresh=" + f1(fresh) + " cells=" + cells + " half=" + f1(half) + " faded=" + f1(faded)
                            + " left=" + left);
        });
        scenario("coverage_no_look_turns_less_while_the_way_ahead_is_new", n -> {
            int[] turns = new int[2];
            for (int i = 0; i < 2; i++) {
                ExploreTuning.Builder b = coverageTuning(tuning().gyro(robotGyro()).hopTicks(16, 40).turnChance(0.7)
                        .turnDeg(20, 55));
                Rig rig = new Rig((i == 0 ? b : b.coverageOff()).build(), CLEAR);
                rig.simWheels = true;
                rig.started();
                rig.runUntil(180000);
                turns[i] = rig.countPrefix("turn", 0, rig.now + 1);
            }
            check(n, turns[0] > 0 && turns[0] * 2 <= turns[1], "turns on=" + turns[0] + " off=" + turns[1]);
        });
        scenario("coverage_uncalibrated_roams_exactly_as_before", n -> {
            List<String> logs = new ArrayList<String>();
            for (int i = 0; i < 2; i++) {
                ExploreTuning.Builder b = coverageTuning(navTuning().hopTicks(16, 40).turnChance(0.7));
                Rig rig = navRig(i == 0 ? b : b.coverageOff(), t -> t % 7000 >= 5000 && t % 7000 < 5200
                        ? obstacle(t) : clear(t), (r, t) -> r.now % 3000 < 1500 ? null : prof(0.9f, 0.3f, 0.9f, 0.6f));
                rig.simWheels = true;
                rig.started();
                rig.runUntil(60000);
                logs.add(rig.log.toString());
            }
            check(n, logs.get(0).equals(logs.get(1)), "differ");
        });
        scenario("coverage_trace_notes_carry_counts_only_and_are_forgotten_at_shutdown", n -> {
            Rig rig = roomRig(true);
            List<String> notes = traced(rig);
            rig.at(120000, () -> rig.brain.shutdown());
            rig.started();
            rig.runUntil(119990);
            int before = rig.brain.coverage().cells();
            rig.runUntil(121000);
            int counted = 0;
            boolean numbersOnly = true;
            for (String note : notes) {
                String text = note.substring(note.indexOf(' ') + 1);
                if (text.startsWith("coverage")) {
                    counted++;
                    numbersOnly &= text.matches("coverage: \\d+ cells");
                }
                if (text.contains(", new ")) {
                    numbersOnly &= text.matches(".*, new \\d\\.\\d\\d$");
                }
            }
            check(n, before > 3 && counted > 0 && numbersOnly && rig.brain.coverage().cells() == 0
                            && !rig.brain.coverage().tracking(),
                    "before=" + before + " counted=" + counted + " numbersOnly=" + numbersOnly + " " + notes);
        });
    }

    // ---- a visual place memory (owner 2026-10-01: somewhere else than the last 30 minutes) ----

    /** A textured 80x60 scene: random 5x5 colour blocks from the seed (PlaceMemoryHarness's scenes). */
    static PlaceMemory.Print scene(long seed) {
        java.util.Random r = new java.util.Random(seed);
        int[] rgb = new int[80 * 60];
        int[] cells = new int[16 * 12];
        for (int i = 0; i < cells.length; i++) {
            cells[i] = (r.nextInt(256) << 16) | (r.nextInt(256) << 8) | r.nextInt(256);
        }
        for (int y = 0; y < 60; y++) {
            for (int x = 0; x < 80; x++) {
                rgb[y * 80 + x] = cells[(y / 5) * 16 + x / 5];
            }
        }
        return PlaceMemory.Print.of(rgb, 80, 60);
    }

    /** A plain grey frame: no texture, so no evidence. */
    static PlaceMemory.Print plainScene() {
        int[] rgb = new int[80 * 60];
        java.util.Arrays.fill(rgb, 0x606060);
        return PlaceMemory.Print.of(rgb, 80, 60);
    }

    /** Whether a heading (0..360) is in the half of the room that always looks the same. */
    private static boolean familiarHalf(double deg) {
        double d = Heading.wrap(deg);
        return d >= 180;
    }

    private static void placeScenarios() {
        scenario("place_a_familiar_view_is_noted_and_its_novelty_lowered", n -> {
            // The same scene wherever he looks: after a minute every look is seen before, and
            // a leg decision on open ground facing it turns him (to ground the grid calls new).
            Rig rig = navRig(coverageTuning(navTuning().gyro(robotGyro())), CLEAR, ALL_OPEN);
            rig.simWheels = true;
            rig.placeView = (r, t) -> scene(1);
            List<String> notes = traced(rig);
            rig.started();
            rig.runUntil(150000);
            long first = -1;
            int seen = 0;
            boolean format = true;
            int turnsAfterSeen = 0;
            int straightAfterSeen = 0;
            int earlyTurns = 0;
            boolean afterSeen = false;
            for (String note : notes) {
                long at = Long.parseLong(note.substring(0, note.indexOf(' ')));
                String text = note.substring(note.indexOf(' ') + 1);
                if (text.startsWith("place:")) {
                    seen++;
                    afterSeen = true;
                    if (first < 0) {
                        first = at;
                    }
                    format &= text.matches("place: seen before \\(sim \\d\\.\\d\\d, \\d+ min ago\\)");
                } else if (text.startsWith("steer:")) {
                    if (at < 60000 && text.contains("turn only")) {
                        earlyTurns++;
                    }
                    if (afterSeen) {
                        if (text.contains("turn only")) {
                            turnsAfterSeen++;
                        } else {
                            straightAfterSeen++;
                        }
                        afterSeen = false;
                    }
                }
            }
            check(n, first >= 60000 && seen >= 2 && seen <= 20 && format && turnsAfterSeen == seen
                            && straightAfterSeen == 0 && earlyTurns == 0 && rig.violations.isEmpty(),
                    "first=" + first + " seen=" + seen + " format=" + format + " turnsAfterSeen=" + turnsAfterSeen
                            + " straightAfterSeen=" + straightAfterSeen + " earlyTurns=" + earlyTurns + " " + notes);
        });
        scenario("place_the_steer_spends_more_time_facing_the_unfamiliar_half", n -> {
            // An 8 x 6 m room: one half of the compass always shows the same scene; the other a new one each look.
            int[] fresh = new int[2];
            int[] all = new int[2];
            StringBuilder each = new StringBuilder();
            for (int i = 0; i < 2; i++) {
                for (long seed = 1; seed <= 5; seed++) {
                    Rig rig = roomRig(true, seed);
                    if (i == 0) {
                        rig.placeView = (r, t) -> familiarHalf(r.yaw.trueDeg) ? scene(1) : scene(1000 + t);
                    }
                    int[] counts = new int[2];
                    rig.started();
                    for (long t = 1000; t <= 600000; t += 1000) {
                        rig.runUntil(t);
                        if (t >= 120000) {
                            counts[familiarHalf(rig.yaw.trueDeg) ? 0 : 1]++;
                        }
                    }
                    fresh[i] += counts[1];
                    all[i] += counts[0] + counts[1];
                    each.append(i == 0 ? " on " : " off ").append(counts[1]).append('/').append(counts[0] + counts[1]);
                }
            }
            double on = (double) fresh[0] / all[0];
            double off = (double) fresh[1] / all[1];
            check(n, on >= off + 0.1, "fresh share on=" + f1(on) + " off=" + f1(off) + each);
        });
        scenario("place_an_unusable_heading_still_lowers_the_view_it_has_seen", n -> {
            // No gyro: no grid, but the same scene for a minute still reads seen before.
            Rig rig = navRig(coverageTuning(navTuning()), CLEAR, ALL_OPEN);
            rig.simWheels = true;
            rig.placeView = (r, t) -> scene(1);
            List<String> notes = traced(rig);
            rig.started();
            rig.runUntil(150000);
            boolean seen = false;
            boolean lowAfter = false;
            for (String note : notes) {
                long at = Long.parseLong(note.substring(0, note.indexOf(' ')));
                String text = note.substring(note.indexOf(' ') + 1);
                seen |= text.startsWith("place: seen before");
                lowAfter |= at >= 60000 && text.startsWith("steer:") && text.matches(".*, new 0\\.[0-4]\\d$");
            }
            check(n, seen && lowAfter && rig.violations.isEmpty(),
                    "seen=" + seen + " lowAfter=" + lowAfter + " " + notes.subList(Math.max(0, notes.size() - 8),
                            notes.size()));
        });
        scenario("place_plain_frames_roam_exactly_as_with_no_prints", n -> {
            List<String> logs = new ArrayList<String>();
            for (int i = 0; i < 2; i++) {
                Rig rig = navRig(coverageTuning(navTuning().gyro(robotGyro()).hopTicks(16, 40).turnChance(0.7)), CLEAR,
                        (r, t) -> r.now % 3000 < 1500 ? null : prof(0.9f, 0.3f, 0.9f, 0.6f));
                rig.simWheels = true;
                if (i == 0) {
                    rig.placeView = (r, t) -> plainScene();
                }
                rig.started();
                rig.runUntil(120000);
                logs.add(rig.log.toString());
            }
            check(n, logs.get(0).equals(logs.get(1)), "differ");
        });
        scenario("place_memory_is_forgotten_at_shutdown", n -> {
            Rig rig = navRig(coverageTuning(navTuning().gyro(robotGyro())), CLEAR, ALL_OPEN);
            rig.simWheels = true;
            rig.placeView = (r, t) -> scene(r.now / 7000);
            rig.at(60000, () -> rig.brain.shutdown());
            rig.started();
            rig.runUntil(59990);
            int before = rig.brain.places().size();
            rig.runUntil(61000);
            check(n, before > 3 && rig.brain.places().size() == 0, "before=" + before + " after="
                    + rig.brain.places().size());
        });
    }

    private static void navScenarios() {
        scenario("roam_camera_open_in_every_roaming_state", n -> {
            // Obstacles now and then: pauses, turns, legs, startles, back-offs and escapes.
            Rig rig = navRig(navTuning().turnChance(0.5).cap(8, 20000, 30000),
                    t -> t % 5000 >= 3500 && t % 5000 < 3700 ? obstacle(t) : clear(t), (r, t) -> null);
            rig.started();
            rig.runUntil(40000);
            java.util.Set<ExploreBrain.State> roaming = java.util.EnumSet.of(ExploreBrain.State.PAUSE,
                    ExploreBrain.State.LOOK, ExploreBrain.State.TURN, ExploreBrain.State.HOP,
                    ExploreBrain.State.STARTLE, ExploreBrain.State.BACK_OFF);
            check(n, rig.openStates.containsAll(roaming) && rig.count("camera open") == 1
                            && rig.count("camera close") == 0 && rig.violations.isEmpty(),
                    "open in " + rig.openStates + " " + rig.tail());
        });
        scenario("roam_speak_stops_closes_the_camera_then_reopens_after_the_gap_and_roams", n -> {
            // One stop (requested for the first pause's end, the next 100 s away), then roaming.
            ExploreTuning.Builder b = claudeTuning().reopenGapMs(3000).hopTicks(8).curiosityMs(100000, 100000);
            Vision room = (rig, t) -> list();
            Rig rig = new Rig(b.build(), CLEAR, room, true, CAT_RIGHT_IN_FRAME_3);
            rig.reopenGapMs = 3000;
            rig.at(1200, () -> rig.brain.requestCuriosity());
            rig.openView = (r, t) -> prof(0.9f, 0.9f, 0.9f, 0.9f);
            rig.started();
            rig.runUntil(20000);
            int say = rig.first("say", 0);
            long speak = entered(rig, ExploreBrain.State.SPEAK, 0);
            // Closed as the scan ended (ASK), and not reopened before the line.
            int lastClose = -1;
            for (int i = 0; i < say; i++) {
                if (rig.log.get(i).what.equals("camera close")) {
                    lastClose = i;
                }
            }
            long closedAt = rig.timeOf(lastClose);
            long lineDone = rig.timeOf(say) + rig.speechMs;
            int reopen = rig.firstAfter("camera open", rig.timeOf(say));
            int hop = rig.firstAfter("hop", lineDone);
            // Stopped (the last motion call before the line is a stop), camera closed at SPEAK.
            String before = "none";
            for (int i = 0; i < say; i++) {
                String w = rig.log.get(i).what;
                if (w.equals("stop") || w.equals("hop") || w.startsWith("turn") || w.equals("back")) {
                    before = w;
                }
            }
            check(n, say >= 0 && speak >= 0 && lastClose >= 0 && closedAt <= speak
                            && rig.countPrefix("camera open", closedAt, rig.timeOf(say) + 1) == 0 && before.equals("stop")
                            && rig.timeOf(reopen) >= lineDone && hop >= 0
                            // The first look after the reopen was captured after the close plus the gap.
                            && rig.looksFrom >= closedAt + 3000
                            && !rig.brain.cameraBackedOff() && rig.violations.isEmpty(),
                    "speak@" + speak + " close@" + closedAt + " say@" + rig.timeOf(say) + " reopen@"
                            + rig.timeOf(reopen) + " hop@" + rig.timeOf(hop) + " " + rig.tail());
        });
        scenario("roam_camera_closed_through_meet_ask_name_listen_name_remember_and_name_clip", n -> {
            // The meeting-as-today path (no ears session), as above.
            Rig named = meetRig();
            named.earsPresent = false;
            named.people.match = (r, k) -> STRANGER;
            named.people.listen = ListenScript.always(hearWords("i'm Sam"));
            named.started();
            named.runUntil(16000);
            Rig clip = meetRig();
            clip.earsPresent = false;
            clip.people.match = (r, k) -> CuriosityPort.MatchAnswer.FAILED;
            clip.people.lines = CuriosityPort.MatchAnswer.FAILED;
            clip.started();
            clip.runUntil(12000);
            java.util.Set<ExploreBrain.State> talking = java.util.EnumSet.of(ExploreBrain.State.MEET,
                    ExploreBrain.State.ASK_NAME, ExploreBrain.State.LISTEN, ExploreBrain.State.NAME,
                    ExploreBrain.State.REMEMBER, ExploreBrain.State.SPEAK);
            boolean seen = named.statesSeen.containsAll(talking)
                    && clip.statesSeen.contains(ExploreBrain.State.NAME_CLIP);
            boolean closed = true;
            for (ExploreBrain.State st : named.openStates) {
                closed &= !talking.contains(st) && st != ExploreBrain.State.NAME_CLIP;
            }
            for (ExploreBrain.State st : clip.openStates) {
                closed &= !talking.contains(st) && st != ExploreBrain.State.NAME_CLIP;
            }
            check(n, seen && closed && named.openStates.contains(ExploreBrain.State.PAUSE)
                            && named.violations.isEmpty() && clip.violations.isEmpty(),
                    "seen " + named.statesSeen + " / " + clip.statesSeen + " open " + named.openStates + " / "
                            + clip.openStates + " " + named.violations + clip.violations);
        });
        scenario("roam_steer_blocked_left_open_right_bends_right", n -> {
            Rig rig = navRig(navTuning(), CLEAR, (r, t) -> r.count("turn RIGHT") == 0
                    ? prof(0.9f, 0.1f, 0.4f, 0.9f) : prof(0.9f, 0.9f, 0.9f, 0.9f));
            rig.started();
            rig.runUntil(4500);
            int first = rig.firstMotionAfter(1300);
            int stop = rig.first("stop", first);
            int hop = rig.first("hop", stop);
            List<Integer> legs = legs(rig);
            check(n, rig.what(first).equals("turn RIGHT") && rig.timeOf(rig.first("eyes LOOK RIGHT", 0)) == 1300
                            && hop > stop && !legs.isEmpty() && legs.get(0) == 8 && rig.violations.isEmpty(),
                    "first=" + rig.what(first) + " legs=" + legs + " " + rig.tail());
        });
        scenario("roam_steer_bend_is_a_measured_turn_when_the_heading_is_usable", n -> {
            // Only the right quarter is open: 0.75 x 30 = 22.5 deg right, by the gyro (a
            // timed bend would be 22.5 x 3000 / 360 = 188 ms, 11 deg at 60 deg/s).
            Rig rig = navRig(navTuning().gyro(robotGyro()), CLEAR, (r, t) -> {
                if (r.count("turn RIGHT") > 0) {
                    return prof(0.9f, 0.9f, 0.9f, 0.9f);
                }
                Openness.Profile p = prof(0.9f, 0.1f, 0.1f, 0.1f);
                for (int i = 12; i < 16; i++) {
                    p.bins[i] = 0.9f;
                }
                return p;
            });
            rig.started();
            rig.runUntil(4000);
            double r = rig.yaw.turnResults.isEmpty() ? 0 : rig.yaw.turnResults.get(0);
            check(n, rig.what(rig.firstMotionAfter(1300)).equals("turn RIGHT")
                            && Math.abs(-r - 22.5) <= rig.tuning.turnToleranceDeg && rig.violations.isEmpty(),
                    "result=" + f1(r) + " " + rig.tail());
        });
        scenario("roam_steer_all_blocked_gives_a_short_leg_or_a_turn_never_a_full_leg", n -> {
            Rig rig = navRig(navTuning(), CLEAR, (r, t) -> prof(0.9f, 0.1f, 0.1f, 0.1f));
            rig.started();
            rig.runUntil(15000);
            List<Integer> legs = legs(rig);
            int max = 0;
            for (int l : legs) {
                max = Math.max(max, l);
            }
            check(n, rig.what(rig.firstMotionAfter(1300)).startsWith("turn") && !legs.isEmpty() && max <= 2
                            && rig.count("startle") == 0 && rig.violations.isEmpty(),
                    "legs=" + legs + " " + rig.tail());
        });
        scenario("roam_floor_hazard_mid_leg_aborts_even_when_the_camera_reads_open", n -> {
            Rig rig = navRig(navTuning(), t -> t >= 1600 && t < 1800 ? edgeAhead(t) : clear(t),
                    (r, t) -> prof(0.9f, 0.9f, 0.9f, 0.9f));
            rig.started();
            rig.runUntil(4000);
            int hop = rig.first("hop", 0);
            int stop = rig.first("stop", hop);
            int startle = rig.first("startle", stop);
            check(n, rig.timeOf(hop) == 1300 && rig.timeOf(stop) == 1600 && startle > stop
                            && rig.floorClearFalseAt >= 1600 && rig.violations.isEmpty(),
                    "hop@" + rig.timeOf(hop) + " stop@" + rig.timeOf(stop) + " " + rig.tail());
        });
        scenario("roam_no_looks_backs_off_roams_on_the_floor_sensor_and_retries", n -> {
            Vision dark = (rig, t) -> null;
            Rig rig = new Rig(navTuning().build(), CLEAR, dark, true).started();
            rig.runUntil(17000);
            int close = rig.first("camera close", 0);
            int reopen = rig.first("camera open", close);
            // Opened at 300; no look by 300 + 3000: off for 10 s, roaming on, then tried again.
            check(n, rig.timeOf(close) == 3300 && rig.timeOf(reopen) >= 13300 && rig.timeOf(reopen) <= 13400
                            && rig.countPrefix("hop", 3300, 13300) >= 8 && rig.count("camera close") == 2
                            && rig.violations.isEmpty(),
                    rig.tail());
        });
        scenario("roam_look_then_go_opens_the_camera_only_at_leg_decisions", n -> {
            ExploreTuning.Builder b = navTuning().navigation(ExploreTuning.Navigation.LOOK_THEN_GO);
            Rig rig = navRig(b, CLEAR, (r, t) -> prof(0.9f, 0.9f, 0.9f, 0.9f));
            rig.started();
            rig.runUntil(15000);
            int open = rig.first("camera open", 0);
            int close = rig.first("camera close", open);
            int hop = rig.first("hop", 0);
            java.util.Set<ExploreBrain.State> onlyPause = java.util.EnumSet.of(ExploreBrain.State.PAUSE);
            check(n, new ExploreTuning.Builder().build().navigation == ExploreTuning.Navigation.CONTINUOUS
                            && rig.timeOf(open) == 1300 && rig.timeOf(close) == 2000 && rig.timeOf(hop) == 2000
                            && close < hop && rig.count("camera open") >= 3
                            && rig.count("camera open") - rig.count("camera close") <= 1
                            && onlyPause.containsAll(rig.openStates) && rig.violations.isEmpty(),
                    "open in " + rig.openStates + " " + rig.tail());
        });
        scenario("roam_lease_loss_closes_the_camera_and_goes_eyes_only", n -> {
            Rig rig = navRig(navTuning(), CLEAR, (r, t) -> null);
            rig.at(1500, () -> rig.brain.onLeaseChanged(false));
            rig.started();
            rig.runUntil(3000);
            check(n, rig.timeOf(rig.firstAfter("stop", 1500)) == 1500
                            && rig.timeOf(rig.firstAfter("camera close", 1500)) == 1500
                            && rig.brain.state() == ExploreBrain.State.EYES_ONLY && rig.violations.isEmpty(),
                    rig.tail());
        });
        scenario("roam_invariant_flags_a_camera_open_while_talking_or_without_the_lease", n -> {
            ExploreTuning.Navigation c = ExploreTuning.Navigation.CONTINUOUS;
            boolean flags = true;
            for (ExploreBrain.State st : new ExploreBrain.State[]{ExploreBrain.State.SPEAK, ExploreBrain.State.MEET,
                    ExploreBrain.State.ASK_NAME, ExploreBrain.State.LISTEN, ExploreBrain.State.NAME,
                    ExploreBrain.State.REMEMBER, ExploreBrain.State.NAME_CLIP, ExploreBrain.State.EYES_ONLY,
                    ExploreBrain.State.CORNERED}) {
                flags &= cameraRuleBreak(st, true, true, c, false, false) != null;
            }
            flags &= cameraRuleBreak(ExploreBrain.State.HOP, true, true,
                    ExploreTuning.Navigation.LOOK_THEN_GO, false, true) != null;
            flags &= cameraRuleBreak(ExploreBrain.State.HOP, false, true, c, false, true) != null;
            flags &= cameraRuleBreak(ExploreBrain.State.HOP, true, true, c, true, true) != null;
            boolean allows = cameraRuleBreak(ExploreBrain.State.HOP, true, true, c, false, true) == null
                    && cameraRuleBreak(ExploreBrain.State.SPEAK, false, true, c, false, false) == null
                    && cameraRuleBreak(ExploreBrain.State.HOP, false, true, c, true, true) == null;
            check(n, flags && allows, "flags=" + flags + " allows=" + allows);
        });
        scenario("roam_steer_low_confidence_keeps_todays_legs", n -> {
            Rig plain = navRig(navTuning().turnChance(0.5), CLEAR, (r, t) -> null);
            Rig dim = navRig(navTuning().turnChance(0.5), CLEAR, (r, t) -> prof(0.3f, 0.1f, 0.1f, 0.9f));
            plain.started();
            dim.started();
            plain.runUntil(20000);
            dim.runUntil(20000);
            List<String> a = new ArrayList<String>();
            List<String> b = new ArrayList<String>();
            for (Event e : plain.log) {
                if (!e.what.startsWith("camera")) {
                    a.add(e.toString());
                }
            }
            for (Event e : dim.log) {
                if (!e.what.startsWith("camera")) {
                    b.add(e.toString());
                }
            }
            check(n, a.equals(b) && plain.count("hop") > 0 && dim.violations.isEmpty(),
                    "plain=" + plain.tail() + " dim=" + dim.tail());
        });
        scenario("roam_blocked_look_mid_leg_ends_the_leg_at_the_next_tick", n -> {
            List<String> notes = new ArrayList<String>();
            Rig rig = navRig(navTuning(), CLEAR, (r, t) -> r.count("hop") == 0
                    ? prof(0.9f, 0.9f, 0.9f, 0.9f) : prof(0.9f, 0.9f, 0.1f, 0.9f));
            rig.brain.setTrace(notes::add);
            rig.started();
            rig.runUntil(4000);
            int hop = rig.first("hop", 0);
            int stop = rig.first("stop", hop);
            int next = rig.firstMotionAfter(rig.timeOf(stop) + 1);
            boolean numbersOnly = true;
            for (String note : notes) {
                numbersOnly &= !note.contains("jpeg") && !note.contains("bins");
            }
            // Leg from 1300 (8 ticks, to 3300); the look captured 1800 arrives at 2000.
            check(n, rig.timeOf(hop) == 1300 && rig.timeOf(stop) == 2000 && rig.count("startle") == 0
                            && rig.what(next).startsWith("turn") && numbersOnly && rig.violations.isEmpty(),
                    "stop@" + rig.timeOf(stop) + " next=" + rig.what(next) + " " + rig.tail());
        });
        scenario("roam_floor_is_taught_after_driving_over_it_and_motion_is_reported", n -> {
            Rig rig = navRig(navTuning().floorTeachCounts(100), CLEAR, (r, t) -> prof(0.9f, 0.9f, 0.9f, 0.9f));
            rig.simWheels = true;
            rig.started();
            rig.runUntil(3200);
            // 100 counts per wheel is 1 s of driving: from the 1300 leg start, taught from ~2300.
            long[] first = rig.floorTaught.isEmpty() ? null : rig.floorTaught.get(0);
            boolean moving = false;
            boolean still = false;
            for (Event e : rig.movingCalls) {
                moving |= e.what.equals("moving") && e.t == 1300;
                still |= e.what.equals("still") && e.t == 3300;
            }
            rig.runUntil(3400);
            for (Event e : rig.movingCalls) {
                still |= e.what.equals("still") && e.t == 3300;
            }
            check(n, first != null && first[0] >= 2300 && first[0] <= 2500 && first[1] <= 1300
                            // Not clear only while the sensors came up, before the lease was used.
                            && rig.floorClearCalls >= 30 && rig.floorClearFalseAt < 300
                            && rig.movingCalls.size() >= 3 && rig.movingCalls.get(0).what.equals("still")
                            && moving && still && rig.violations.isEmpty(),
                    "taught=" + (first == null ? "none" : first[0] + "/" + first[1]) + " moving=" + rig.movingCalls
                            + " " + rig.tail());
        });
    }

    // ---- wedged escapes: retrace, measured circle, Claude's way out, rest (explore nav plan U5) ----
    //
    // Legs of 4 ticks (1 s: 100 counts at the rig's 100 counts/s), curiosity stops off,
    // the camera open with nothing in view. bumps() puts an obstacle in front of him 700
    // ms into each of his first few roaming legs: three in a row wedge him at the third
    // startle's end, with the leg log holding the three legs (~70 counts each) and the
    // two back-offs (25) between them. The circle's budget is generous here (20 s) so its
    // six looks always fit; escape_full_budgets_... runs the shipped budgets.

    private static final java.util.Set<ExploreBrain.State> ESCAPING = java.util.EnumSet.of(
            ExploreBrain.State.RETRACE, ExploreBrain.State.CIRCLE, ExploreBrain.State.WAY_OUT,
            ExploreBrain.State.DRIVE_OFF);

    private static boolean escaping(Rig r) {
        return ESCAPING.contains(r.brain.state());
    }

    interface RigTest {
        boolean test(Rig r);
    }

    private static ExploreTuning.Builder escTuning() {
        return gyroTuning()
                .hopTicks(4)
                .curiosityMs(1000000, 1000000)
                .lookTiming(200, 3000, 2000)
                .cameraBackoffMs(10000)
                .cap(8, 20000, 30000)
                .wedge(3, 2, 1)
                .escapeRetrace(1500, 10)
                .escapeBudgets(6000, 20000, 6000, 3000, 6000)
                .escapeCircle(6, 60, 500)
                .escapeDrive(4, 2);
    }

    /** An obstacle 700 ms into each roaming leg while there have been fewer than `times` startles. */
    private static Feed bumps(Rig[] h, int times) {
        return t -> {
            Rig r = h[0];
            boolean on = r != null && r.brain.state() == ExploreBrain.State.HOP && r.moving
                    && r.count("startle") < times && t - r.legStartT >= 700;
            return on ? obstacle(t) : clear(t);
        };
    }

    /** bumps(), and an obstacle 200 ms into any forward drive of the escape while `block` holds. */
    private static Feed bumpsThen(Rig[] h, int times, RigTest block) {
        Feed b = bumps(h, times);
        return t -> {
            Rig r = h[0];
            if (r != null && escaping(r) && "hop".equals(r.motion) && t - r.legStartT >= 200 && block.test(r)) {
                return obstacle(t);
            }
            return b.at(t);
        };
    }

    /** A rig for the escape scenarios: camera, simulated wheels and yaw; claude null is no Claude at all. */
    private static Rig escRig(ExploreTuning.Builder b, Rig[] h, Feed feed, WayOutScript claude) {
        Rig rig = claude == null ? new Rig(b.build(), feed, NOTHING, true)
                : new Rig(b.build(), feed, NOTHING, true, (r, req, nth) -> CuriosityPort.Answer.nothing());
        rig.wayOuts = claude;
        rig.simWheels = true;
        h[0] = rig;
        return rig;
    }

    private static void runUntil(Rig rig, long limit, RigTest done) {
        while (rig.now < limit && !done.test(rig)) {
            rig.runUntil(rig.now + 10);
        }
    }

    /** The first drive of this kind started in this brain state at or after from, else null. */
    private static Drive firstDrive(Rig rig, String state, String kind, long from) {
        for (Drive d : rig.drives) {
            if (d.t >= from && d.state.equals(state) && d.kind.equals(kind)) {
                return d;
            }
        }
        return null;
    }

    private static List<Drive> drivesIn(Rig rig, String state, String kind, long from, long to) {
        List<Drive> out = new ArrayList<Drive>();
        for (Drive d : rig.drives) {
            if (d.t >= from && d.t < to && d.state.equals(state) && d.kind.equals(kind)) {
                out.add(d);
            }
        }
        return out;
    }

    /** The first state entered after t (the state log), and when. */
    private static Event nextState(Rig rig, long t) {
        for (Event e : rig.stateLog) {
            if (e.t > t) {
                return e;
            }
        }
        return null;
    }

    /** Open straight ahead (0.9) within 25 deg of these offsets from where the circle began, else
     * blocked (0.1); confident. Nothing while roaming, so the steer keeps today's legs. */
    private static OpenView openAt(double... offsets) {
        return (r, t) -> {
            if (!escaping(r) || Double.isNaN(r.circleFrom)) {
                return null;
            }
            for (double o : offsets) {
                if (Math.abs(Heading.delta(r.yaw.wrapped(), Heading.wrap(r.circleFrom + o))) <= 25) {
                    return prof(0.9f, 0.9f, 0.9f, 0.9f);
                }
            }
            return prof(0.9f, 0.1f, 0.1f, 0.1f);
        };
    }

    private static long captured(CuriosityPort.Frame f) {
        String s = new String(f.jpeg, java.nio.charset.StandardCharsets.US_ASCII);
        return Long.parseLong(s.substring(s.indexOf('@') + 1));
    }

    private static boolean near(double a, double b, double tol) {
        return !Double.isNaN(a) && !Double.isNaN(b) && Math.abs(Heading.delta(a, b)) <= tol;
    }

    /** Roaming on a desk-like spot: one clean straight leg (100 counts), then the steer bends right
     * (ahead middling, so no look ends the first leg early). */
    private static OpenView deskView() {
        return (r, t) -> r.count("hop") == 0 ? prof(0.9f, 0.9f, 0.9f, 0.9f) : prof(0.9f, 0.1f, 0.5f, 0.9f);
    }

    // ---- pinned (live: every turn blocked, the wheels going nowhere either way) ----

    /** Pins or frees the rig: turns, reversing and driving forward all go nowhere (0 counts). */
    private static void pin(Rig r, boolean on) {
        r.yaw.stuck = on;
        r.backBlocked = on;
        r.turnWheelsBlocked = on;
        r.blockedFrom = on ? Math.min(r.blockedFrom, r.now) : Long.MAX_VALUE;
    }

    /** A pinned rig whose Claude always points straight ahead in the first frame (no turn needed). */
    private static Rig pinnedRig(List<String> notes) {
        return pinnedRig(notes, escTuning());
    }

    /**
     * pinnedRig with jam detection off (ExploreTuning.jamOff): the escape ladder, its rests
     * and its forward tries as they still run when the encoders can't rule a back-up (since
     * 2026-10-01 a fully pinned rig with encoders is jammed: the jam scenarios).
     */
    private static Rig pinnedLadderRig(List<String> notes) {
        return pinnedRig(notes, escTuning().jamOff());
    }

    private static Rig pinnedRig(List<String> notes, ExploreTuning.Builder b) {
        Rig[] h = new Rig[1];
        Rig rig = escRig(b.turnChance(1.0), h, CLEAR, (r, req, nth) -> CuriosityPort.WayOut.way(0, 0f));
        rig.creepPer100 = 0;
        rig.brain.setTrace(x -> notes.add(h[0].now + " " + x));
        pin(rig, true);
        rig.blockedFrom = 0;
        return rig;
    }

    /** Each entry into state s, in order. */
    private static List<Long> entries(Rig rig, ExploreBrain.State s) {
        List<Long> out = new ArrayList<Long>();
        for (Event e : rig.stateLog) {
            if (e.what.equals(s.name())) {
                out.add(e.t);
            }
        }
        return out;
    }

    /** How long the rest entered at t lasted (until the next state), else -1. */
    private static long restLength(Rig rig, long t) {
        Event next = nextState(rig, t);
        return next == null ? -1 : next.t - t;
    }

    /** A time within one 10 ms tick of the expected one (and not the -1 of a missing event). */
    private static boolean withinTick(long t, long expected) {
        return t >= 0 && Math.abs(t - expected) <= 10;
    }

    private static int notesWith(List<String> notes, String part) {
        int n = 0;
        for (String x : notes) {
            n += x.contains(part) ? 1 : 0;
        }
        return n;
    }

    private static void pinnedScenarios() {
        scenario("pinned_runs_the_ladder_once_with_two_asks_then_rests", n -> {
            List<String> notes = new ArrayList<String>();
            Rig rig = pinnedLadderRig(notes);
            rig.started();
            runUntil(rig, 60000, r -> r.brain.state() == ExploreBrain.State.CORNERED);
            long rest = entered(rig, ExploreBrain.State.CORNERED, 0);
            long retrace = entered(rig, ExploreBrain.State.RETRACE, 0);
            long circle = entered(rig, ExploreBrain.State.CIRCLE, retrace);
            long ask = entered(rig, ExploreBrain.State.WAY_OUT, circle);
            long off = entered(rig, ExploreBrain.State.DRIVE_OFF, ask);
            long second = entered(rig, ExploreBrain.State.WAY_OUT, off);
            long off2 = entered(rig, ExploreBrain.State.DRIVE_OFF, second);
            check(n, retrace > 0 && circle > retrace && ask > circle && off > ask && second > off && off2 > second
                            && rest > off2 && entries(rig, ExploreBrain.State.RETRACE).size() == 1
                            && rig.wayOutRequests.size() == 2 && !rig.wayOutRequests.get(0).second
                            && rig.wayOutRequests.get(1).second && notesWith(notes, "wedged:") == 1
                            && notesWith(notes, "free after") == 0 && notesWith(notes, "cornered: 1 failed escape") == 1
                            && rig.violations.isEmpty(),
                    "retrace@" + retrace + " circle@" + circle + " ask@" + ask + " off@" + off + " second@" + second
                            + " off2@" + off2 + " rest@" + rest + " asks=" + rig.wayOutRequests.size() + " notes=" + notes);
        });
        scenario("pinned_after_the_rest_waits_longer_before_the_next_ladder", n -> {
            // Rests: 30 s after each failed ladder; a still-pinned first move after it rests
            // again 30 s x 2^k (k failed ladders in a row, capped at 240 s) before the next
            // ladder. Ladders start near 0 s, ~103 s, ~264 s here: at most 3 in 5 minutes,
            // so at most 6 way-out asks (two per ladder) where 30 s rests alone gave ~7 ladders.
            List<String> notes = new ArrayList<String>();
            Rig rig = pinnedLadderRig(notes);
            rig.started();
            rig.runUntil(300000);
            List<Long> ladders = entries(rig, ExploreBrain.State.RETRACE);
            List<Long> rests = entries(rig, ExploreBrain.State.CORNERED);
            long firstRest = rests.isEmpty() ? -1 : rests.get(0);
            long longRest = -1;
            for (long t : rests) {
                if (longRest < 0 && t > firstRest && ladders.size() > 1 && t < ladders.get(1)) {
                    longRest = restLength(rig, t);
                }
            }
            check(n, ladders.size() >= 2 && ladders.size() <= 3 && rig.wayOutRequests.size() <= 6
                            && rig.wayOutRequests.size() == 2 * ladders.size()
                            && restLength(rig, firstRest) == 30000 && longRest == 60000
                            && ladders.get(1) - firstRest >= 30000 + 60000
                            && notesWith(notes, "free after") == 0 && rig.violations.isEmpty(),
                    "ladders=" + ladders + " rests=" + rests + " longRest=" + longRest + " asks="
                            + rig.wayOutRequests.size() + " " + rig.tail());
        });
        scenario("pinned_backoff_resets_after_a_clean_drive_off", n -> {
            List<String> notes = new ArrayList<String>();
            // Robot 2026-10-02: a first zero turn now waits for the board (RECOVER) before any
            // ladder; this scenario is about the ladder's rests, so the wait is switched off.
            Rig rig = pinnedRig(notes, escTuning().jamOff().stallRecoverOff());
            rig.started();
            // Pinned: ladder 1 fails, the rest, still pinned, the longer rest.
            runUntil(rig, 120000, r -> entries(r, ExploreBrain.State.CORNERED).size() >= 2);
            long longRestAt = rig.now;
            pin(rig, false);
            // Freed by hand during the longer rest: the forward try after it drives off cleanly
            // (forward first; before it, ladder 2 did).
            runUntil(rig, 240000, r -> notesWith(notes, "free after") > 0);
            long freeAt = rig.now;
            pin(rig, true);
            // Pinned again: the next wedge runs its ladder at once, and the rest after it and
            // the still-pinned one are the first ones again (30 s, then 60 s).
            runUntil(rig, 400000, r -> entries(r, ExploreBrain.State.CORNERED).size() >= 4);
            rig.runUntil(rig.now + 61000);
            List<Long> ladders = entries(rig, ExploreBrain.State.RETRACE);
            List<Long> rests = entries(rig, ExploreBrain.State.CORNERED);
            long ladder3 = -1;
            for (long t : ladders) {
                if (ladder3 < 0 && t > freeAt) {
                    ladder3 = t;
                }
            }
            long rest3 = rests.size() >= 3 ? rests.get(2) : -1;
            long rest4 = rests.size() >= 4 ? rests.get(3) : -1;
            check(n, freeAt > longRestAt && ladder3 > freeAt && ladder3 - freeAt <= 10000 && rest3 > ladder3
                            && restLength(rig, rest3) == 30000 && rest4 > rest3 && restLength(rig, rest4) == 60000
                            && rig.violations.isEmpty(),
                    "free@" + freeAt + " ladders=" + ladders + " rests=" + rests + " " + rig.tail());
        });
    }

    // ---- fully jammed (robot 2026-10-01: "constantly getting stuck under this chair") ----

    /** The help line's say events at or after from. */
    private static List<Long> helpLines(Rig rig, long from) {
        List<Long> out = new ArrayList<Long>();
        for (Event e : rig.log) {
            if (e.t >= from && e.what.startsWith("say ") && e.what.contains("stuck")) {
                out.add(e.t);
            }
        }
        return out;
    }

    /** Back drives (each a fresh reverse) started in [from, to). */
    private static List<Long> backDrives(Rig rig, long from, long to) {
        List<Long> out = new ArrayList<Long>();
        for (Drive d : rig.drives) {
            if (d.kind.equals("back") && d.t >= from && d.t < to) {
                out.add(d.t);
            }
        }
        return out;
    }

    private static void jamScenarios() {
        scenario("jammed_nothing_moves_one_help_line_one_probe_per_rest_no_ladder_loop", n -> {
            // Forward, reverse and both turns go nowhere (under the seat, every wheel wedged):
            // the escape stops the moment the back-up and both turns have gone nowhere, he asks
            // for help once, then one short back-up at 30, 60 and 120 s after the jam (robot
            // 15:19), then one every jammedRestMs (120 s). No more ladders, way-out asks, turns
            // or forward pushes; no 30 s ladder-rest loop. Since robot 16:42 each zero attempt
            // waits for the board first, three times a spell, so the jam comes after ~70 s.
            List<String> notes = new ArrayList<String>();
            Rig rig = pinnedRig(notes);
            rig.started();
            runUntil(rig, 120000, r -> notedAt(notes, "fully jammed") >= 0);
            rig.runUntil(Math.max(rig.now, notedAt(notes, "fully jammed")) + 250000);
            long jamAt = notedAt(notes, "fully jammed");
            List<Long> rests = entries(rig, ExploreBrain.State.CORNERED);
            List<Long> helps = helpLines(rig, 0);
            List<Long> probes = backDrives(rig, jamAt + 1, Long.MAX_VALUE);
            int pushes = rig.countPrefix("hop", jamAt + 1, Long.MAX_VALUE)
                    + rig.countPrefix("turn", jamAt + 1, Long.MAX_VALUE);
            int laddersAfter = 0;
            for (long t : entries(rig, ExploreBrain.State.RETRACE)) {
                laddersAfter += t > jamAt ? 1 : 0;
            }
            // Probes at 30, 60 and 120 s, then 120 s apart; a rest after a probe stays in CORNERED.
            boolean probesSpaced = probes.size() == 4 && withinTick(probes.get(0), jamAt + 30000)
                    && withinTick(probes.get(1), jamAt + 60000) && withinTick(probes.get(2), jamAt + 120000)
                    && probes.get(3) - probes.get(2) >= 120000;
            check(n, jamAt > 0 && jamAt < 80000 && notesWith(notes, "(recovery 3 of 3)") == 1
                            && helps.size() == 1 && helps.get(0) >= jamAt
                            && helps.get(0) - jamAt < 2000 && !rests.isEmpty() && rests.get(0) >= jamAt
                            && rests.get(0) - jamAt < 100 && rests.size() == 1
                            && probesSpaced && pushes == 0 && laddersAfter == 0 && rig.wayOutRequests.size() <= 1
                            && notesWith(notes, "cornered: ") == 0 && notesWith(notes, "free after") == 0
                            && rig.violations.isEmpty(),
                    "jam@" + jamAt + " rests=" + rests + " helps=" + helps + " probes=" + probes + " pushes=" + pushes
                            + " ladders after=" + laddersAfter + " asks=" + rig.wayOutRequests.size() + " notes="
                            + notes.subList(Math.max(0, notes.size() - 25), notes.size()));
        });
        scenario("jammed_help_line_at_most_every_five_minutes", n -> {
            List<String> notes = new ArrayList<String>();
            Rig rig = pinnedRig(notes);
            rig.started();
            rig.runUntil(800000);
            List<Long> helps = helpLines(rig, 0);
            boolean spaced = true;
            for (int i = 1; i < helps.size(); i++) {
                spaced &= helps.get(i) - helps.get(i - 1) >= 300000;
            }
            check(n, helps.size() >= 2 && helps.size() <= 3 && spaced && notesWith(notes, "free") == 0
                            && rig.violations.isEmpty(),
                    "helps=" + helps + " " + rig.tail());
        });
        scenario("jammed_probe_that_moves_resumes_roaming", n -> {
            // Pulled out during the rest (nothing told him): the probe at its end (30 s after the
            // jam since robot 15:19) moves, so he is free and roams again.
            List<String> notes = new ArrayList<String>();
            Rig rig = pinnedRig(notes);
            rig.started();
            runUntil(rig, 60000, r -> notedAt(notes, "fully jammed") >= 0);
            long jamAt = rig.now;
            rig.runUntil(jamAt + 30000);
            pin(rig, false);
            rig.runUntil(jamAt + 140000);
            List<Long> probes = backDrives(rig, jamAt + 1, Long.MAX_VALUE);
            long freeAt = notedAt(notes, "jam probe moved");
            long hop = entered(rig, ExploreBrain.State.HOP, Math.max(freeAt, 0));
            check(n, jamAt > 0 && !probes.isEmpty() && probes.get(0) >= jamAt + 29900 && freeAt > probes.get(0)
                            && hop > freeAt && helpLines(rig, 0).size() == 1 && rig.violations.isEmpty(),
                    "jam@" + jamAt + " probes=" + probes + " free@" + freeAt + " hop@" + hop + " " + rig.tail());
        });
        scenario("jammed_moved_from_outside_probes_at_once", n -> {
            // Pulled out by hand 20 s into the rest: the encoders count it, and he probes at
            // once instead of sitting out the 120 s.
            List<String> notes = new ArrayList<String>();
            Rig rig = pinnedRig(notes);
            rig.started();
            runUntil(rig, 60000, r -> notedAt(notes, "fully jammed") >= 0);
            long jamAt = rig.now;
            rig.runUntil(jamAt + 20000);
            pin(rig, false);
            long movedAt = rig.now;
            rig.wheelLeft -= 200;
            rig.wheelRight -= 200;
            rig.runUntil(movedAt + 5000);
            List<Long> probes = backDrives(rig, jamAt + 1, Long.MAX_VALUE);
            long freeAt = notedAt(notes, "jam probe moved");
            check(n, notedAt(notes, "moved from outside") >= movedAt && !probes.isEmpty()
                            && probes.get(0) - movedAt <= 1000 && freeAt > probes.get(0) && rig.violations.isEmpty(),
                    "jam@" + jamAt + " moved@" + movedAt + " probes=" + probes + " free@" + freeAt + " " + rig.tail());
        });
        scenario("jammed_partial_block_one_way_free_runs_the_escape", n -> {
            // As side_left_blocked_escape_ladder_commands_no_left_turn_until_free: reversing and
            // LEFT blocked, RIGHT free. Not fully jammed: the escape handles it, no help line.
            Rig[] h = new Rig[1];
            Rig rig = escRig(escTuning(), h, bumps(h, 3), null);
            rig.yaw.stuckDir = 1;
            rig.backBlocked = true;
            rig.openView = openAt(90);
            List<String> notes = traced(rig);
            rig.started();
            runUntil(rig, 120000, r -> notedAt(notes, "free after") >= 0 || r.brain.state() == ExploreBrain.State.CORNERED);
            check(n, entered(rig, ExploreBrain.State.RETRACE, 0) > 0 && notedAt(notes, "free after") > 0
                            && notedAt(notes, "fully jammed") < 0 && helpLines(rig, 0).isEmpty()
                            && rig.violations.isEmpty(),
                    "notes=" + notes.subList(Math.max(0, notes.size() - 20), notes.size()));
        });
    }

    // ---- the long wriggle (robot 2026-10-01: an 11 s spin freed him where 1.5 s turns gave up) ----

    /** Roams one clean leg, then is pinned (live 14:08: the steer's turn was the first move that would not turn). */
    private static Rig chairRig(List<String> notes, ExploreTuning.Builder b) {
        Rig[] h = new Rig[1];
        Rig rig = escRig(b.turnChance(1.0), h, t -> {
            Rig r = h[0];
            if (r != null && !r.yaw.stuck && r.blockedFrom == Long.MAX_VALUE && !r.drives.isEmpty()
                    && r.drives.get(0).end > 0) {
                pin(r, true);
            }
            return clear(t);
        }, (r, req, nth) -> CuriosityPort.WayOut.way(0, 0f));
        rig.creepPer100 = 0;
        rig.brain.setTrace(x -> notes.add(h[0].now + " " + x));
        return rig;
    }

    private static void wriggleScenarios() {
        scenario("wriggle_slow_wheels_then_the_heading_turns_after_6s_frees_him_where_the_short_turns_gave_up", n -> {
            // Under the chair the wheels work slowly at first (60 counts/s here), then he comes
            // loose 6 s into the wriggle: free, and he roams. The same rig without the wriggle
            // stops at the jam with only the ~1.5 s measured turns, and is still resting later.
            List<String> notes = new ArrayList<String>();
            Rig rig = pinnedRig(notes);
            rig.turnWheelsBlocked = false;
            rig.turnWheelsPer100 = 3;
            rig.started();
            runUntil(rig, 60000, r -> notedAt(notes, "wriggle LEFT: up to 10000 ms") >= 0);
            long start = notedAt(notes, "wriggle LEFT: up to 10000 ms");
            rig.runUntil(rig.now + 6000);
            pin(rig, false);
            rig.turnWheelsPer100 = -1;
            rig.runUntil(rig.now + 20000);
            long free = notedAt(notes, "wriggle LEFT: free after");
            long hop = entered(rig, ExploreBrain.State.HOP, Math.max(free, 0));

            List<String> old = new ArrayList<String>();
            Rig was = pinnedRig(old, escTuning().wriggleOff());
            was.turnWheelsBlocked = false;
            was.turnWheelsPer100 = 3;
            was.started();
            runUntil(was, 60000, r -> notedAt(old, "fully jammed") >= 0);
            long jamAt = was.now;
            was.runUntil(jamAt + 6000);
            pin(was, false);
            was.turnWheelsPer100 = -1;
            was.runUntil(jamAt + 26000);
            check(n, start > 0 && free - start >= 6000 && free - start <= 7500 && hop > free
                            && notedAt(notes, "fully jammed") < 0 && helpLines(rig, 0).isEmpty()
                            && notesWith(notes, "wriggle RIGHT") == 0 && rig.violations.isEmpty()
                            && jamAt > 0 && notesWith(old, "free") == 0 && notesWith(old, "wriggle") == 0
                            && was.brain.state() == ExploreBrain.State.CORNERED && was.violations.isEmpty(),
                    "start@" + start + " free@" + free + " hop@" + hop + " notes="
                            + notes.subList(Math.max(0, notes.size() - 20), notes.size()) + " old jam@" + jamAt
                            + " old=" + old.subList(Math.max(0, old.size() - 8), old.size()));
        });
        scenario("wriggle_nothing_moves_each_way_stops_within_1500ms_then_the_help_line", n -> {
            // Every wheel wedged: no counts at all. Each way stops after one 1.5 s window, never
            // 10 s of grinding against stalled wheels, then the existing jam path (after three
            // waits for the board since robot 16:42).
            List<String> notes = new ArrayList<String>();
            Rig rig = pinnedRig(notes);
            rig.started();
            runUntil(rig, 120000, r -> notedAt(notes, "fully jammed") >= 0);
            rig.runUntil(rig.now + 5000);
            long left = notedAt(notes, "wriggle LEFT: up to 10000 ms");
            long leftStop = notedAt(notes, "wriggle LEFT: wheels not moving; stopping");
            long right = notedAt(notes, "wriggle RIGHT: up to 10000 ms");
            long rightStop = notedAt(notes, "wriggle RIGHT: wheels not moving; stopping");
            long failed = notedAt(notes, "wriggle failed both ways: asking for help");
            long jam = notedAt(notes, "fully jammed");
            List<Long> helps = helpLines(rig, 0);
            check(n, left > 0 && leftStop - left >= 1400 && leftStop - left <= 1700 && right >= leftStop
                            && right - leftStop <= 200 && rightStop - right >= 1400 && rightStop - right <= 1700
                            && failed >= rightStop && jam >= failed && helps.size() == 1 && helps.get(0) >= failed
                            && helps.get(0) - failed < 2000 && turnCommands(rig, left, failed + 1).size() == 2
                            && turnCommands(rig, failed + 1, Long.MAX_VALUE).isEmpty()
                            && rig.brain.state() == ExploreBrain.State.CORNERED && rig.violations.isEmpty(),
                    "left@" + left + "/" + leftStop + " right@" + right + "/" + rightStop + " failed@" + failed
                            + " jam@" + jam + " helps=" + helps + " notes="
                            + notes.subList(Math.max(0, notes.size() - 20), notes.size()));
        });
        scenario("wriggle_wheels_spin_heading_never_moves_full_time_both_ways_then_help_one_per_two_minutes", n -> {
            // The wheels spin (200 counts/s) but he never turns and backing up goes nowhere:
            // 10 s each way, then the help line. Pulled out and wedged again within 2 minutes:
            // straight to the jam path, no second wriggle.
            List<String> notes = new ArrayList<String>();
            Rig rig = pinnedRig(notes);
            rig.turnWheelsBlocked = false;
            rig.started();
            runUntil(rig, 90000, r -> notedAt(notes, "fully jammed") >= 0);
            long left = notedAt(notes, "wriggle LEFT: up to 10000 ms");
            long leftEnd = notedAt(notes, "wriggle LEFT: not free after 10000 ms");
            long right = notedAt(notes, "wriggle RIGHT: up to 10000 ms");
            long rightEnd = notedAt(notes, "wriggle RIGHT: not free after 10000 ms");
            long failed = notedAt(notes, "wriggle failed both ways: asking for help");
            long jam = rig.now;
            int backsTried = notesWith(notes, "backing up to see");
            rig.runUntil(jam + 5000);
            // Pulled out by hand: the jam probe goes at once and frees him; then wedged again.
            pin(rig, false);
            rig.wheelLeft -= 200;
            rig.wheelRight -= 200;
            runUntil(rig, jam + 20000, r -> notedAt(notes, "jam probe moved") >= 0);
            long freed = notedAt(notes, "jam probe moved");
            pin(rig, true);
            rig.turnWheelsBlocked = false;
            runUntil(rig, left + 119000, r -> notesWith(notes, "fully jammed") >= 2);
            List<Long> jams = notedTimes(notes, "fully jammed");
            check(n, left > 0 && leftEnd - left >= 10000 && leftEnd - left <= 10200 && right >= leftEnd
                            && rightEnd - right >= 10000 && rightEnd - right <= 10200 && failed >= rightEnd
                            && backsTried == 2 && helpLines(rig, 0).size() == 1 && freed > jam
                            && jams.size() == 2 && jams.get(1) - left < 120000
                            && notesWith(notes, "wriggle LEFT: up to") == 1 && notesWith(notes, "wriggle RIGHT: up to") == 1
                            && notedAt(notes, "no wriggle: the last was") > freed && rig.violations.isEmpty(),
                    "left@" + left + "-" + leftEnd + " right@" + right + "-" + rightEnd + " failed@" + failed
                            + " backs=" + backsTried + " freed@" + freed + " jams=" + jams + " notes="
                            + notes.subList(Math.max(0, notes.size() - 25), notes.size()));
        });
        scenario("wriggle_two_blocked_escape_turns_in_a_row_wriggles_before_the_ladder_grinds", n -> {
            // Turns go nowhere both ways but the back-up moves, so the jam rule can't fire: the
            // second blocked escape turn in a row starts the wriggle, which frees him 3 s in.
            List<String> notes = new ArrayList<String>();
            Rig rig = pinnedRig(notes);
            rig.backBlocked = false;
            rig.turnWheelsBlocked = false;
            rig.started();
            runUntil(rig, 60000, r -> notesWith(notes, "wriggle ") > 0);
            long start = rig.now;
            List<Long> blocked = notedTimes(notes, "measured turn blocked");
            int escBlocked = 0;
            for (String x : notes) {
                escBlocked += x.contains("measured turn blocked") && x.endsWith(")") ? 1 : 0;
            }
            rig.runUntil(start + 3000);
            pin(rig, false);
            rig.runUntil(start + 15000);
            long free = notedTimes(notes, ": free after").isEmpty() ? -1 : notedAt(notes, "wriggle LEFT: free after");
            check(n, start > 0 && notedAt(notes, "turns blocked twice in a row") > 0 && escBlocked == 2
                            && free - start >= 3000 && free - start <= 4500
                            && entered(rig, ExploreBrain.State.WAY_OUT, 0) < 0 && rig.wayOutRequests.isEmpty()
                            && notesWith(notes, "cornered: ") == 0
                            && notedAt(notes, "fully jammed") < 0 && helpLines(rig, 0).isEmpty()
                            && rig.violations.isEmpty(),
                    "start@" + start + " blocked=" + blocked + " esc=" + escBlocked + " free@" + free + " notes="
                            + notes.subList(Math.max(0, notes.size() - 25), notes.size()));
        });
        scenario("wriggle_the_1408_chair_episode_wedge_turn_stalled_back_up_blocked_retrace_is_caught", n -> {
            // Live 14:08:22-14:09:53: a roaming turn would not turn, backing up first went
            // nowhere, the RETRACE turn would not turn, then CORNERED. The spin at 14:09:41 moved
            // fine, so that was the motor board's cutout: now the back-up that went nowhere waits
            // for the board (no retrace inside the window), and only with no recovery by the last
            // probe does the wriggle run, then the jam path. Since robot 16:42 a wait with no
            // recovery tries the escape again, and its next zero attempt waits again, three
            // waits a spell before the wriggle.
            List<String> notes = new ArrayList<String>();
            Rig rig = chairRig(notes, escTuning());
            rig.started();
            runUntil(rig, 150000, r -> notedAt(notes, "fully jammed") >= 0);
            rig.runUntil(rig.now + 3000);
            // Robot 2026-10-02: the first zero turn is itself the stall, so the first wait comes at
            // it, not after the wedge's back-up went nowhere.
            long blocked = notedAt(notes, "measured turn blocked");
            long wedged = notedAt(notes, "wedged: a turn that would not turn");
            long wait = notedAt(notes, "stall: waiting for the motor board to recover");
            long real = notedAt(notes, "no recovery after 20 s: a real jam");
            long wriggle = notedAt(notes, "wriggle LEFT: up to 10000 ms");
            long failed = notedAt(notes, "wriggle failed both ways");
            long jam = notedAt(notes, "fully jammed");
            long rest = entered(rig, ExploreBrain.State.CORNERED, 0);
            check(n, blocked > 0 && wait == blocked && (wedged < 0 || wedged > wait) && real > wait && wriggle >= real
                            && failed > wriggle && jam >= failed && rest >= jam && helpLines(rig, 0).size() == 1
                            && notesWith(notes, "stall: waiting for the motor board to recover") == 3
                            && notesWith(notes, "(recovery 3 of 3)") == 1
                            && (entered(rig, ExploreBrain.State.RETRACE, wait + 1) < 0
                                || entered(rig, ExploreBrain.State.RETRACE, wait + 1)
                                    >= notedAt(notes, "(recovery 1 of 3): trying the escape again"))
                            && notesWith(notes, "cornered: ") == 0 && rig.violations.isEmpty(),
                    "blocked@" + blocked + " wedged@" + wedged + " wait@" + wait + " real@" + real + " wriggle@" + wriggle
                            + " failed@" + failed + " jam@" + jam + " rest@" + rest + " notes="
                            + notes.subList(Math.max(0, notes.size() - 25), notes.size()));
        });
    }

    // ---- the post-stall recovery wait (robot 2026-10-01: the motor board refused all motion for 9-29 s) ----

    /**
     * Roams one leg into something low (forward goes nowhere 700 ms in, so the stall watch
     * fires); at the stall's STARTLE, cutoutMs > 0 pins every motion for cutoutMs, < 0 pins it
     * for good, 0 leaves the board working (only forward was blocked, and the low thing is
     * behind him once he moves).
     */
    private static Rig cutoutRig(List<String> notes, ExploreTuning.Builder b, long cutoutMs) {
        Rig[] h = new Rig[1];
        long[] stallAt = {-1};
        Rig rig = escRig(b, h, t -> {
            Rig r = h[0];
            if (r == null) {
                return clear(t);
            }
            if (stallAt[0] < 0 && r.brain.state() == ExploreBrain.State.HOP && r.moving && t - r.legStartT >= 700
                    && r.blockedFrom == Long.MAX_VALUE) {
                r.blockedFrom = t;
            }
            if (stallAt[0] < 0 && r.brain.state() == ExploreBrain.State.STARTLE) {
                stallAt[0] = t;
                if (cutoutMs != 0) {
                    pin(r, true);
                } else {
                    r.blockedFrom = Long.MAX_VALUE;
                }
            }
            if (stallAt[0] >= 0 && cutoutMs > 0 && t >= stallAt[0] + cutoutMs && r.yaw.stuck) {
                pin(r, false);
            }
            return clear(t);
        }, (r, req, nth) -> CuriosityPort.WayOut.way(0, 0f));
        rig.creepPer100 = 0;
        rig.brain.setTrace(x -> notes.add(h[0].now + " " + x));
        return rig;
    }

    private static List<String> lastNotes(List<String> notes, int n) {
        return notes.subList(Math.max(0, notes.size() - n), notes.size());
    }

    private static void recoverScenarios() {
        scenario("recover_a_first_zero_turn_with_no_stall_before_it_waits_for_the_board_and_backs_out", n -> {
            // Robot 2026-10-02 09:21:58: a roaming turn read 0 of 60 deg with its wheels still and
            // no stall before it; it went straight to the escape ladder, whose turns and back-ups
            // all read zero inside the board's cutout, and he rested "fully jammed". The zero turn
            // is itself the stall: it stamps the stall clock and he waits in RECOVER, then backs out.
            Rig[] h = new Rig[1];
            long[] pinnedAt = {-1};
            List<String> notes = new ArrayList<String>();
            Rig rig = escRig(escTuning().turnChance(1.0), h, t -> {
                Rig r = h[0];
                if (r == null) {
                    return clear(t);
                }
                if (pinnedAt[0] < 0 && r.brain.state() == ExploreBrain.State.LOOK) {
                    // The board latched as the turn was about to start: it reads 0 deg and 0 counts.
                    pinnedAt[0] = t;
                    pin(r, true);
                }
                if (pinnedAt[0] >= 0 && t >= pinnedAt[0] + 6000 && r.yaw.stuck) {
                    pin(r, false);
                }
                return clear(t);
            }, (r, req, nth) -> CuriosityPort.WayOut.way(0, 0f));
            rig.creepPer100 = 0;
            rig.brain.setTrace(x -> notes.add(h[0].now + " " + x));
            rig.started();
            runUntil(rig, 60000, r -> pinnedAt[0] >= 0 && notedAt(notes, "the board is back") >= 0);
            long back = notedAt(notes, "the board is back");
            rig.runUntil(Math.max(back, rig.now) + 15000);
            long blocked = notedAt(notes, "measured turn blocked");
            long wait = notedAt(notes, "stall: waiting for the motor board");
            long wedged = notedAt(notes, "wedged");
            long jammed = notedAt(notes, "fully jammed");
            List<Long> backs = backDrives(rig, back, Long.MAX_VALUE);
            check(n, pinnedAt[0] > 0 && blocked > pinnedAt[0] && wait >= blocked && wait <= blocked + 100
                            && (wedged < 0 || wedged > back) && jammed < 0 && back > wait && !backs.isEmpty()
                            && rig.violations.isEmpty(),
                    "pinned@" + pinnedAt[0] + " blocked@" + blocked + " wait@" + wait + " wedged@" + wedged
                            + " jammed@" + jammed + " back@" + back + " backs=" + backs + " notes="
                            + notesAfter(notes, Math.max(0, pinnedAt[0] - 200)));
        });
        scenario("recover_a_12s_cutout_probes_find_nothing_until_20s_then_the_normal_escape", n -> {
            // Live 14:33: the board refused everything after the stall, then came back on its
            // own. He waits still; the 2, 5 and 10 s probes give nothing, the 20 s one moves,
            // and only then the escape backs up and turns away. No jam, no wriggle, no help line.
            List<String> notes = new ArrayList<String>();
            Rig rig = cutoutRig(notes, escTuning().hopTicks(20), 12000);
            rig.started();
            runUntil(rig, 60000, r -> notedAt(notes, "the board is back") >= 0);
            long back = rig.now;
            rig.runUntil(back + 15000);
            long stall = notedAt(notes, "wheels stalled while driving");
            long wait = notedAt(notes, "stall: waiting for the motor board to recover (probes at 2, 5, 10, 20 s)");
            long p2 = notedAt(notes, "recover probe at 2 s: nothing");
            long p5 = notedAt(notes, "recover probe at 5 s: nothing");
            long p10 = notedAt(notes, "recover probe at 10 s: nothing");
            long p20 = notedAt(notes, "recover probe at 20 s: moved");
            // Since robot 15:19 the probes back up first: two back-ups that move nothing, then turns.
            List<Long> backs = backDrives(rig, stall + 1, p20);
            List<long[]> turns = turnCommands(rig, stall + 1, p20);
            List<long[]> probes = new ArrayList<long[]>();
            for (long b : backs) {
                probes.add(new long[]{b, 0});
            }
            probes.addAll(turns);
            probes.sort((x, y) -> Long.compare(x[0], y[0]));
            boolean onTime = probes.size() == 4 && backs.size() == 2 && turns.size() == 2;
            long[] want = {2000, 5000, 10000, 20000};
            for (int i = 0; onTime && i < 4; i++) {
                onTime = probes.get(i)[0] - stall >= want[i] && probes.get(i)[0] - stall <= want[i] + 200
                        && (probes.get(i)[1] == 0) == (i < 2);
            }
            // Still between the probes: no leg; each probe one short back-up or turn.
            int pushes = rig.countPrefix("hop", stall + 1, p20);
            List<Long> escBacks = backDrives(rig, p20, Long.MAX_VALUE);
            long turn = escBacks.isEmpty() ? -1 : rig.timeOf(rig.firstAfter("turn", escBacks.get(0)));
            long hop = entered(rig, ExploreBrain.State.HOP, Math.max(p20, 0));
            check(n, stall > 0 && wait >= stall && wait - stall <= 600 && p2 > 0 && p5 > p2 && p10 > p5 && p20 > p10
                            && onTime && pushes == 0 && p2 - probes.get(0)[0] <= 700 && !escBacks.isEmpty() && turn > escBacks.get(0) && hop > turn
                            && notesWith(notes, "fully jammed") == 0 && notesWith(notes, "wriggle") == 0
                            && notesWith(notes, "no recovery") == 0 && helpLines(rig, 0).isEmpty()
                            && rig.violations.isEmpty(),
                    "stall@" + stall + " wait@" + wait + " probes=" + probes.size() + " pushes=" + pushes + " escBacks="
                            + escBacks + " turn@" + turn + " hop@" + hop + " notes=" + lastNotes(notes, 25));
        });
        scenario("recover_motors_that_never_come_back_three_recoveries_then_wriggle_then_the_help_line", n -> {
            // Every wheel wedged for good (robot 16:42: a zero attempt re-enters RECOVER, up to
            // recoverMaxPerSpell = 3 a spell). The stall waits; its probes give nothing; the next
            // attempt reads zero and waits again; after the third wait's probes give nothing, the
            // jam path: the wriggle, then fully jammed and its help line. No fourth wait.
            List<String> notes = new ArrayList<String>();
            Rig rig = cutoutRig(notes, escTuning().hopTicks(20), -1);
            rig.started();
            runUntil(rig, 200000, r -> notedAt(notes, "fully jammed") >= 0);
            rig.runUntil(rig.now + 3000);
            long stall = notedAt(notes, "wheels stalled while driving");
            List<Long> waits = notedTimes(notes, "stall: waiting for the motor board to recover");
            int nothing = notesWith(notes, ": nothing");
            long real = notedAt(notes, "a real jam");
            long wriggle = notedAt(notes, "wriggle LEFT: up to");
            long failed = notedAt(notes, "wriggle failed both ways");
            long jam = notedAt(notes, "fully jammed");
            List<Long> helps = helpLines(rig, 0);
            // Between waits: one zero attempt each (the escape's back-off or turn reading nothing).
            boolean attemptsBetween = waits.size() == 3;
            for (int i = 0; attemptsBetween && i < 2; i++) {
                attemptsBetween = rig.motions(waits.get(i), waits.get(i + 1)) > 4;
            }
            check(n, stall > 0 && waits.size() == 3 && waits.get(0) >= stall && attemptsBetween && nothing == 12
                            && notesWith(notes, "(recovery 3 of 3)") == 1 && real > waits.get(2)
                            && wriggle >= real && wriggle - real <= 200 && failed > wriggle && jam >= failed
                            && helps.size() == 1 && helps.get(0) >= jam
                            && notesWith(notes, "the board is back") == 0 && rig.violations.isEmpty(),
                    "stall@" + stall + " waits=" + waits + " nothing=" + nothing + " real@" + real + " wriggle@" + wriggle
                            + " failed@" + failed + " jam@" + jam + " helps=" + helps + " notes=" + lastNotes(notes, 30));
        });
        scenario("recover_robot_16_42_a_blocked_turn_after_the_back_out_recovers_again_and_he_gets_free", n -> {
            // Robot 16:42: the stall's cutout ends, the probe moves, he backs straight out, and
            // the next turn reads 0 deg with no wheel counts: a fresh 6 s cutout. He waits again
            // (not the escape ladder inside the cutout), backs straight out once more, and gets
            // free: no jam, no wriggle, no help line.
            List<String> notes = new ArrayList<String>();
            Rig[] h = new Rig[1];
            long[] stallAt = {-1};
            long[] secondFrom = {-1};
            long[] secondUntil = {-1};
            String[] lastMotion = {null};
            Rig rig = escRig(escTuning().hopTicks(20), h, t -> {
                Rig r = h[0];
                if (r == null) {
                    return clear(t);
                }
                if (stallAt[0] < 0 && r.brain.state() == ExploreBrain.State.HOP && r.moving && t - r.legStartT >= 700
                        && r.blockedFrom == Long.MAX_VALUE) {
                    r.blockedFrom = t;
                }
                if (stallAt[0] < 0 && r.brain.state() == ExploreBrain.State.STARTLE) {
                    stallAt[0] = t;
                    pin(r, true);
                }
                if (stallAt[0] >= 0 && secondFrom[0] < 0 && t >= stallAt[0] + 4000 && r.yaw.stuck) {
                    // The first cutout ends: the 5 s probe moves.
                    pin(r, false);
                }
                long back = notedAt(notes, "backing straight out");
                if (back >= 0 && secondFrom[0] < 0 && "back".equals(lastMotion[0]) && !"back".equals(r.motion)) {
                    // The back-out ends; the turn after it re-arms the cutout for 6 s.
                    secondFrom[0] = t;
                    secondUntil[0] = t + 6000;
                    pin(r, true);
                }
                lastMotion[0] = r.motion;
                if (secondFrom[0] >= 0 && t >= secondUntil[0] && r.yaw.stuck) {
                    pin(r, false);
                }
                return clear(t);
            }, (r, req, nth) -> CuriosityPort.WayOut.way(0, 0f));
            rig.creepPer100 = 0;
            rig.brain.setTrace(x -> notes.add(h[0].now + " " + x));
            rig.started();
            runUntil(rig, 120000, r -> {
                List<Long> backs = notedTimes(notes, ": the board is back");
                return backs.size() >= 2 && entered(r, ExploreBrain.State.HOP, backs.get(1)) > 0
                        || notedAt(notes, "fully jammed") >= 0;
            });
            rig.runUntil(rig.now + 5000);
            List<Long> waits = notedTimes(notes, "stall: waiting for the motor board to recover");
            List<Long> boards = notedTimes(notes, ": the board is back");
            List<Long> outs = notedTimes(notes, "backing straight out");
            long blocked = secondFrom[0] < 0 ? -1 : noteAt(notes, "measured turn blocked", secondFrom[0]);
            long hop = boards.size() < 2 ? -1 : entered(rig, ExploreBrain.State.HOP, boards.get(1));
            check(n, waits.size() == 2 && boards.size() == 2 && outs.size() >= 2 && secondFrom[0] > boards.get(0)
                            && blocked >= secondFrom[0] && waits.get(1) >= blocked && waits.get(1) - blocked <= 100
                            && boards.get(1) >= secondUntil[0] && outs.get(1) >= boards.get(1) && hop > boards.get(1)
                            && notesWith(notes, "wedged") == 0 && notesWith(notes, "fully jammed") == 0
                            && notesWith(notes, "wriggle") == 0 && helpLines(rig, 0).isEmpty() && rig.violations.isEmpty(),
                    "stall@" + stallAt[0] + " waits=" + waits + " boards=" + boards + " outs=" + outs + " second@"
                            + secondFrom[0] + " blocked@" + blocked + " hop@" + hop + " notes=" + lastNotes(notes, 30));
        });
        scenario("recover_no_cutout_the_first_probe_moves_and_the_escape_goes_on", n -> {
            // Only something low ahead; the board works. The 2 s probe moves at once and the
            // escape runs: about 2.5 s later than before the wait.
            List<String> notes = new ArrayList<String>();
            Rig rig = cutoutRig(notes, escTuning().hopTicks(20), 0);
            rig.started();
            runUntil(rig, 30000, r -> notedAt(notes, "the board is back") >= 0);
            rig.runUntil(rig.now + 10000);
            long stall = notedAt(notes, "wheels stalled while driving");
            long moved = notedAt(notes, "recover probe at 2 s: moved");
            List<Long> escBacks = backDrives(rig, moved, Long.MAX_VALUE);
            long escape = escBacks.isEmpty() ? -1 : escBacks.get(0);
            long turn = escape < 0 ? -1 : rig.timeOf(rig.firstAfter("turn", escape));
            long hop = entered(rig, ExploreBrain.State.HOP, Math.max(turn, 0));
            System.out.println("REPORT recovery wait with no cutout: the escape's back-up began " + (escape - stall)
                    + " ms after the stall (before the wait: " + rig.tuning.startleMs + " ms)");
            check(n, stall > 0 && moved - stall >= 2000 && moved - stall <= 3000 && escape >= moved
                            && escape - stall <= 3200 && turn > escape && hop > turn
                            && notesWith(notes, ": nothing") == 0 && notesWith(notes, "wriggle") == 0
                            && notesWith(notes, "fully jammed") == 0 && rig.violations.isEmpty(),
                    "stall@" + stall + " moved@" + moved + " escape@" + escape + " turn@" + turn + " hop@" + hop
                            + " notes=" + lastNotes(notes, 20));
        });
        scenario("recover_a_call_while_waiting_is_answered_where_he_stands", n -> {
            List<String> notes = new ArrayList<String>();
            Rig rig = cutoutRig(notes, escTuning().hopTicks(20), -1);
            rig.started();
            runUntil(rig, 30000, r -> notedAt(notes, "stall: waiting for the motor board") >= 0);
            long wait = rig.now;
            long call = wait + 1000;
            rig.cue(call, Ears.Kind.WAKE_WORD, Ears.Side.LEFT, Float.NaN);
            rig.runUntil(call + 6000);
            int answers = rig.countPrefix("react answer", call, call + 200);
            long met = notedAt(notes, "met someone");
            int turns = turnCommands(rig, call, met).size();
            check(n, answers == 1 && notedAt(notes, "meeting without a look") >= call && met > call && turns == 0
                            && rig.countPrefix("hop", call, met) == 0
                            && notesWith(notes, "looking for the caller") == 0 && rig.violations.isEmpty(),
                    "answers=" + answers + " turns=" + turns + " met@" + met + " violations=" + rig.violations
                            + " notes=" + lastNotes(notes, 20));
        });
        scenario("recover_a_shove_while_waiting_probes_at_once", n -> {
            // Freed by hand 1.2 s after the stall: the shove probes then, not at the 2 s mark.
            List<String> notes = new ArrayList<String>();
            Rig rig = cutoutRig(notes, escTuning().hopTicks(20), -1);
            rig.started();
            runUntil(rig, 30000, r -> notedAt(notes, "stall: waiting for the motor board") >= 0);
            long stall = notedAt(notes, "wheels stalled while driving");
            long shove = stall + 1200 - (stall + 1200) % 10;
            rig.at(shove - 10, () -> pin(rig, false));
            rig.shoveAt(shove, 900);
            rig.runUntil(shove + 8000);
            // Since robot 15:19 a probe backs up first (the way he came).
            List<Long> probes = backDrives(rig, stall + 1, Long.MAX_VALUE);
            long back = notedAt(notes, "recover probe at 1 s: moved");
            long probe = probes.isEmpty() ? -1 : probes.get(0);
            check(n, probe >= shove && probe - shove <= 200 && back > shove
                            && notesWith(notes, ": nothing") == 0 && notesWith(notes, "fully jammed") == 0
                            && rig.violations.isEmpty(),
                    "stall@" + stall + " shove@" + shove + " probe@" + probe + " notes=" + lastNotes(notes, 20));
        });
        scenario("recover_a_turn_that_reads_nothing_after_a_bump_waits_and_its_attempts_never_count", n -> {
            // A collision stop (the front sensor) and a 12 s cutout from it: the escape's blind
            // back-off and its first turn land in the cutout. That turn reading nothing waits
            // for the board; the escape then runs as if from the bump. Nothing made inside the
            // window counts: no jam rule, no wriggle, no "turns blocked twice", no wedge.
            Rig[] h = new Rig[1];
            long[] bumpAt = {-1};
            List<String> notes = new ArrayList<String>();
            Rig rig = escRig(escTuning(), h, t -> {
                Rig r = h[0];
                if (r == null) {
                    return clear(t);
                }
                if (bumpAt[0] < 0 && r.brain.state() == ExploreBrain.State.HOP && r.moving && t - r.legStartT >= 700) {
                    bumpAt[0] = t;
                    pin(r, true);
                    return obstacle(t);
                }
                if (bumpAt[0] >= 0 && t >= bumpAt[0] + 12000 && r.yaw.stuck) {
                    pin(r, false);
                }
                return clear(t);
            }, (r, req, nth) -> CuriosityPort.WayOut.way(0, 0f));
            rig.creepPer100 = 0;
            rig.brain.setTrace(x -> notes.add(h[0].now + " " + x));
            rig.started();
            runUntil(rig, 60000, r -> notedAt(notes, "the board is back") >= 0);
            long back = rig.now;
            rig.runUntil(back + 15000);
            long bump = notedAt(notes, "collision stop: a bump");
            long blocked = notedAt(notes, "measured turn blocked");
            long wait = notedAt(notes, "stall: waiting for the motor board to recover");
            long hop = entered(rig, ExploreBrain.State.HOP, back);
            check(n, bump > 0 && blocked > bump && wait >= blocked && wait - blocked <= 100
                            && notedAt(notes, "the board is back") - bump >= 12000
                            && notesWith(notes, "measured turn blocked") == 1 && notesWith(notes, "wedged") == 0
                            && notesWith(notes, "fully jammed") == 0 && notesWith(notes, "wriggle") == 0
                            && notesWith(notes, "blocked twice") == 0 && hop > back
                            && helpLines(rig, 0).isEmpty() && rig.violations.isEmpty(),
                    "bump@" + bump + " blocked@" + blocked + " wait@" + wait + " back@" + back + " hop@" + hop
                            + " notes=" + lastNotes(notes, 25));
        });
        scenario("recover_desk_rig_backs_out_the_way_he_came_within_15_s_no_jam_no_help_line", n -> {
            // Robot 15:19, owner: "all he has to do is back up". Under a desk: forward is
            // blocked, both turns are blocked by legs (the wheels slip, he does not rotate, and
            // each attempt starts a 6 s cutout), and behind is clear. 150 counts back and he is
            // out from under it. The probes back up first, so he backs out at once.
            List<String> notes = new ArrayList<String>();
            Rig[] h = new Rig[1];
            long[] stallAt = {-1};
            long[] sum0 = {0};
            long[] cutoutUntil = {-1};
            long[] clearAt = {-1};
            String[] lastMotion = {null};
            Rig rig = escRig(escTuning().hopTicks(20), h, t -> {
                Rig r = h[0];
                if (r == null) {
                    return clear(t);
                }
                if (stallAt[0] < 0 && r.brain.state() == ExploreBrain.State.HOP && r.moving && t - r.legStartT >= 700
                        && r.blockedFrom == Long.MAX_VALUE) {
                    r.blockedFrom = t;
                }
                if (stallAt[0] < 0 && r.brain.state() == ExploreBrain.State.STARTLE) {
                    stallAt[0] = t;
                    sum0[0] = r.wheelLeft + r.wheelRight;
                }
                if (stallAt[0] >= 0 && clearAt[0] < 0) {
                    boolean turnEnded = "turn".equals(lastMotion[0]) && !"turn".equals(r.motion);
                    if (turnEnded && t >= cutoutUntil[0]) {
                        cutoutUntil[0] = t + 6000;
                    }
                    boolean cut = t < cutoutUntil[0];
                    r.yaw.stuck = true;
                    r.turnWheelsBlocked = cut;
                    r.backBlocked = cut;
                    if ((sum0[0] - (r.wheelLeft + r.wheelRight)) / 2 >= 150) {
                        clearAt[0] = t;
                        r.yaw.stuck = false;
                        r.turnWheelsBlocked = false;
                        r.backBlocked = false;
                        r.blockedFrom = Long.MAX_VALUE;
                    }
                }
                lastMotion[0] = r.motion;
                return clear(t);
            }, (r, req, nth) -> CuriosityPort.WayOut.way(0, 0f));
            rig.creepPer100 = 0;
            rig.brain.setTrace(x -> notes.add(h[0].now + " " + x));
            rig.started();
            runUntil(rig, 60000, r -> clearAt[0] >= 0 || notedAt(notes, "fully jammed") >= 0);
            long out = clearAt[0];
            rig.runUntil(Math.max(rig.now, 40000));
            long stall = notedAt(notes, "wheels stalled while driving");
            long hop = out < 0 ? -1 : entered(rig, ExploreBrain.State.HOP, out);
            check(n, stall > 0 && out > stall && out - stall <= 15000 && hop > out
                            && notesWith(notes, "fully jammed") == 0 && helpLines(rig, 0).isEmpty()
                            && rig.violations.isEmpty(),
                    "stall@" + stall + " out@" + out + " hop@" + hop + " notes=" + lastNotes(notes, 25));
        });
        scenario("recover_behind_blocked_probes_turn_after_two_still_back_ups_and_a_slipping_turn_is_that_way_blocked", n -> {
            // Pinned behind and ahead; the board works, so a turn probe's wheels slip ~100 counts
            // without turning him (robot 15:19: 109 counts, 0 deg). Two back-up probes go nowhere,
            // so the third probe turns; its slip is "that way is blocked", not "the board is
            // back", and the remaining probe backs up again. None moved: the jam path (the wriggle).
            List<String> notes = new ArrayList<String>();
            Rig[] h = new Rig[1];
            long[] stallAt = {-1};
            Rig rig = escRig(escTuning().hopTicks(20), h, t -> {
                Rig r = h[0];
                if (r == null) {
                    return clear(t);
                }
                if (stallAt[0] < 0 && r.brain.state() == ExploreBrain.State.HOP && r.moving && t - r.legStartT >= 700
                        && r.blockedFrom == Long.MAX_VALUE) {
                    r.blockedFrom = t;
                }
                if (stallAt[0] < 0 && r.brain.state() == ExploreBrain.State.STARTLE) {
                    stallAt[0] = t;
                    pin(r, true);
                    r.turnWheelsBlocked = false;
                }
                return clear(t);
            }, (r, req, nth) -> CuriosityPort.WayOut.way(0, 0f));
            rig.creepPer100 = 0;
            rig.brain.setTrace(x -> notes.add(h[0].now + " " + x));
            rig.started();
            runUntil(rig, 40000, r -> notedAt(notes, "wriggle LEFT: up to") >= 0 || notedAt(notes, "wedged") >= 0);
            rig.runUntil(rig.now + 500);
            long stall = notedAt(notes, "wheels stalled while driving");
            long p2 = notedAt(notes, "recover probe at 2 s: nothing");
            long p5 = notedAt(notes, "recover probe at 5 s: nothing");
            long p10 = notedAt(notes, "recover probe at 10 s: wheels moved");
            boolean said = notesWith(notes, "but he did not turn: that way is blocked") == 1;
            long p20 = notedAt(notes, "recover probe at 20 s: nothing");
            long wriggle = notedAt(notes, "wriggle LEFT: up to");
            List<Long> backs = backDrives(rig, stall + 1, p20);
            List<long[]> turns = turnCommands(rig, stall + 1, p20);
            check(n, stall > 0 && p2 > stall && p5 > p2 && p10 > p5 && said && p20 > p10 && wriggle >= p20
                            && backs.size() == 3 && turns.size() == 1 && turns.get(0)[0] > p5 && turns.get(0)[0] < p10
                            && notesWith(notes, "the board is back") == 0 && notesWith(notes, "wedged") == 0
                            && entered(rig, ExploreBrain.State.RETRACE, 0) < 0 && rig.violations.isEmpty(),
                    "stall@" + stall + " p2@" + p2 + " p5@" + p5 + " p10@" + p10 + " p20@" + p20 + " wriggle@" + wriggle
                            + " backs=" + backs + " turns=" + turns.size() + " notes=" + lastNotes(notes, 20));
        });
    }

    // ---- a step's budget covers its turns (live 2026-09-25: ~40 deg/s on carpet, the 3 s drive-off ran out mid-turn) ----

    /** Claude's way out: the frame and x that aim `deg` left of where he faces when asked. */
    private static WayOutScript leftOfHere(double deg) {
        return (r, req, nth) -> {
            double want = Heading.wrap(r.brain.heading().degrees() + deg);
            for (int k = 0; k < req.frames.size(); k++) {
                Double at = r.lookHeadings.get(captured(req.frames.get(k)));
                if (at == null) {
                    continue;
                }
                double off = Heading.delta(at, want);
                if (Math.abs(off) <= 0.9 * r.tuning.cameraHalfFovDeg) {
                    return CuriosityPort.WayOut.way(k, (float) (-off / r.tuning.cameraHalfFovDeg));
                }
            }
            return CuriosityPort.WayOut.failed();
        };
    }

    /** The first turn command at or after t, and the stop that ended it: {turn index, stop index}. */
    private static int[] turnFrom(Rig rig, long t) {
        int turn = rig.firstAfter("turn", t);
        return new int[]{turn, turn < 0 ? -1 : rig.first("stop", turn)};
    }

    private static void budgetScenarios() {
        scenario("budget_drive_off_turn_at_40_deg_s_completes_and_drives_off", n -> {
            // The live log: turning at 40 deg/s, the way out 130 deg round. The fixed 3 s
            // ran out 125 deg in; now the drive-off's budget adds 130 deg at 35 deg/s.
            Rig[] h = new Rig[1];
            ExploreTuning d = new ExploreTuning.Builder().build();
            Rig rig = escRig(escTuning().escapeRetrace(0, 10).escapeBudgets(6000, 20000, 6000, d.escapeDriveOffMs, 6000),
                    h, bumps(h, 3), leftOfHere(130));
            List<String> notes = traced(rig);
            rig.yaw.rateDegS = 40;
            rig.started();
            runUntil(rig, 90000, r -> notedAt(notes, "free after") >= 0);
            long off = entered(rig, ExploreBrain.State.DRIVE_OFF, 0);
            int[] turn = turnFrom(rig, off);
            long took = rig.timeOf(turn[1]) - rig.timeOf(turn[0]);
            Drive drive = firstDrive(rig, "DRIVE_OFF", "hop", off);
            double result = rig.yaw.turnResults.isEmpty() ? 0 : rig.yaw.turnResults.get(rig.yaw.turnResults.size() - 1);
            check(n, off > 0 && d.escapeDriveOffMs == 3000 && d.escapeTurnRateDegS == 35 && took > 3000
                            && Math.abs(Math.abs(result) - 130) <= 15 && drive != null && drive.t >= rig.timeOf(turn[1])
                            && notesWith(notes, "out of time") == 0 && notesWith(notes, "free after") == 1
                            && notedAt(notes, "drove off") >= 0 && rig.violations.isEmpty(),
                    "off@" + off + " took=" + took + " result=" + f1(result) + " drive=" + drive + " notes="
                            + notes.subList(Math.max(0, notes.size() - 8), notes.size()));
        });
        scenario("budget_slow_but_progressing_turn_is_never_cut_by_the_step_budget", n -> {
            // 10 deg/s in the drive-off only (it learned ~60 before, so its budget is 3 s +
            // 130 deg at 35 deg/s, ~6.7 s): the 13 s turn outlives it and still finishes.
            Rig[] h = new Rig[1];
            Feed b = bumps(h, 3);
            Rig rig = escRig(escTuning().escapeRetrace(0, 10), h, t -> {
                Rig r = h[0];
                if (r != null) {
                    r.yaw.rateDegS = r.brain.state() == ExploreBrain.State.DRIVE_OFF ? 10 : 60;
                }
                return b.at(t);
            }, leftOfHere(130));
            List<String> notes = traced(rig);
            rig.started();
            runUntil(rig, 120000, r -> notedAt(notes, "free after") >= 0);
            long off = entered(rig, ExploreBrain.State.DRIVE_OFF, 0);
            int[] turn = turnFrom(rig, off);
            long took = rig.timeOf(turn[1]) - rig.timeOf(turn[0]);
            double result = rig.yaw.turnResults.isEmpty() ? 0 : rig.yaw.turnResults.get(rig.yaw.turnResults.size() - 1);
            Drive drive = firstDrive(rig, "DRIVE_OFF", "hop", off);
            check(n, off > 0 && took >= 11000 && took < rig.tuning.turnBackstopMs && Math.abs(Math.abs(result) - 130) <= 15
                            && drive != null && drive.t >= rig.timeOf(turn[1]) && notesWith(notes, "out of time") == 0
                            && notesWith(notes, "measured turn blocked") == 0 && notedAt(notes, "drove off") >= 0
                            && rig.violations.isEmpty(),
                    "off@" + off + " took=" + took + " result=" + f1(result) + " drive=" + drive + " notes="
                            + notes.subList(Math.max(0, notes.size() - 8), notes.size()));
        });
        scenario("budget_blocked_drive_off_turn_still_fails_the_step_within_1_5_s", n -> {
            // The way out needs a turn, and the drive-off's turns go nowhere: blocked at ~1.5 s,
            // one back-up and the turn the other way, blocked again, and the step has failed.
            // (The wriggle off: with it, the second blocked turn in a row starts one instead.)
            Rig[] h = new Rig[1];
            Feed b = bumps(h, 3);
            Rig rig = escRig(escTuning().escapeRetrace(0, 10).wriggleOff(), h, t -> {
                Rig r = h[0];
                if (r != null && r.brain.state() == ExploreBrain.State.DRIVE_OFF) {
                    r.yaw.stuck = true;
                }
                return b.at(t);
            }, null);
            rig.openView = openAt(180);
            List<String> notes = traced(rig);
            rig.started();
            runUntil(rig, 90000, r -> notedAt(notes, "escape's DRIVE_OFF failed") >= 0);
            long off = entered(rig, ExploreBrain.State.DRIVE_OFF, 0);
            int[] first = turnFrom(rig, off);
            int[] retry = turnFrom(rig, rig.timeOf(first[1]) + 1);
            long took = rig.timeOf(first[1]) - rig.timeOf(first[0]);
            long took2 = rig.timeOf(retry[1]) - rig.timeOf(retry[0]);
            long failed = notedAt(notes, "escape's DRIVE_OFF failed: a turn that would not turn");
            check(n, off > 0 && took >= 1400 && took <= 1700 && took2 >= 1400 && took2 <= 1700
                            && !rig.what(first[0]).equals(rig.what(retry[0])) && failed >= rig.timeOf(retry[1])
                            && failed - rig.timeOf(retry[1]) <= 300 && notesWith(notes, "out of time") == 0
                            && rig.violations.isEmpty(),
                    "took=" + took + " took2=" + took2 + " failed@" + failed + " " + rig.tail());
        });
        scenario("budget_turn_rate_defaults_35_deg_s_floor_15_and_the_rate_is_learned", n -> {
            ExploreTuning d = new ExploreTuning.Builder().build();
            EscapePlanner p = new EscapePlanner(d);
            Rig rig = new Rig(gyroTuning().turnChance(1.0).build(), CLEAR, NOTHING, true);
            rig.simWheels = true;
            rig.yaw.rateDegS = 40;
            rig.started();
            rig.runUntil(20000);
            double learned = rig.brain.heading().turnRateDegS();
            check(n, d.escapeTurnRateDegS == 35 && d.escapeTurnRateFloorDegS == 15 && d.escapeTurnAllowanceMaxMs == 20000
                            && p.turnRate(Double.NaN) == 35 && p.turnRate(60) == 35 && p.turnRate(25) == 25
                            && p.turnRate(5) == 15 && Math.abs(learned - 40) <= 6,
                    "learned=" + f1(learned) + " " + rig.tail());
        });
    }

    // ---- forward first (live 2026-09-25: facing open floor after a turn, he rested, then turned away) ----

    private static void forwardFirstScenarios() {
        scenario("forward_first_after_a_rest_facing_open_floor_drives_forward_instead_of_turning", n -> {
            List<String> notes = new ArrayList<String>();
            Rig rig = pinnedLadderRig(notes);
            rig.started();
            runUntil(rig, 60000, r -> r.brain.state() == ExploreBrain.State.CORNERED);
            long rest = rig.now;
            pin(rig, false);
            rig.runUntil(rest + 30000 + 8000);
            int firstMove = rig.firstMotionAfter(rest + 1);
            Drive fwd = firstDrive(rig, "DRIVE_OFF", "hop", rest + 1);
            long free = notedAt(notes, "a short leg forward drove cleanly");
            check(n, rig.what(firstMove).equals("hop") && rig.timeOf(firstMove) >= rest + 30000
                            && rig.timeOf(firstMove) - (rest + 30000) <= 300 && fwd != null && fwd.t == rig.timeOf(firstMove)
                            && free > fwd.t && rig.countPrefix("turn", rest + 1, free + 1) == 0
                            && firstDrive(rig, "HOP", "hop", free) != null && rig.violations.isEmpty(),
                    "rest@" + rest + " first=" + rig.what(firstMove) + "@" + rig.timeOf(firstMove) + " free@" + free
                            + " " + rig.tail());
        });
        scenario("forward_first_after_a_rest_facing_a_blocked_way_still_turns_and_rests_longer", n -> {
            // Pinned: the forward try goes nowhere, then the wider turn and the still-pinned
            // rest as before; with an obstacle in front, no forward try at all, only the turn.
            List<String> notes = new ArrayList<String>();
            Rig rig = pinnedLadderRig(notes);
            rig.started();
            runUntil(rig, 60000, r -> r.brain.state() == ExploreBrain.State.CORNERED);
            long rest = rig.now;
            rig.runUntil(rest + 30000 + 15000 + 61000);
            int firstMove = rig.firstMotionAfter(rest + 1);
            long blocked = -1;
            for (long t : notedTimes(notes, "short leg forward blocked")) {
                if (blocked < 0 && t >= rest + 30000) {
                    blocked = t;
                }
            }
            int turn = rig.firstAfter("turn", rest + 30000);
            List<Long> rests = entries(rig, ExploreBrain.State.CORNERED);

            List<String> notes2 = new ArrayList<String>();
            Rig[] h = new Rig[1];
            Rig rig2 = escRig(escTuning().jamOff().turnChance(1.0), h, t -> {
                Rig r = h[0];
                return r != null && r.statesSeen.contains(ExploreBrain.State.CORNERED) ? obstacle(t) : clear(t);
            }, (r, req, nth) -> CuriosityPort.WayOut.way(0, 0f));
            rig2.creepPer100 = 0;
            rig2.brain.setTrace(x -> notes2.add(h[0].now + " " + x));
            pin(rig2, true);
            rig2.blockedFrom = 0;
            rig2.started();
            runUntil(rig2, 60000, r -> r.brain.state() == ExploreBrain.State.CORNERED);
            long rest2 = rig2.now;
            rig2.runUntil(rest2 + 30000 + 3000);
            int firstMove2 = rig2.firstMotionAfter(rest2 + 1);
            check(n, rig.what(firstMove).equals("hop") && blocked > rest + 30000 && turn > 0 && rig.timeOf(turn) > blocked
                            && rests.size() >= 2 && restLength(rig, rests.get(1)) == 60000
                            && notesWith(notes, "free after") == 0 && rig2.what(firstMove2).startsWith("turn")
                            && notesWith(notes2, "trying a short leg forward first: after the rest") == 0
                            && rig.violations.isEmpty() && rig2.violations.isEmpty(),
                    "first=" + rig.what(firstMove) + " blocked@" + blocked + " turn@" + rig.timeOf(turn) + " rests=" + rests
                            + " first2=" + rig2.what(firstMove2) + " " + rig2.tail());
        });
        scenario("forward_first_when_an_escape_step_runs_out_of_time_facing_clear_floor", n -> {
            // The circle's third look never comes (the frames go stale), so the circle runs
            // out of time two turns round, facing floor nothing has blocked: forward first.
            Rig[] h = new Rig[1];
            Feed b = bumps(h, 3);
            Rig rig = escRig(escTuning().escapeRetrace(0, 10).escapeBudgets(6000, 4000, 6000, 3000, 6000), h, t -> {
                Rig r = h[0];
                if (r != null) {
                    r.staleLooks = r.brain.state() == ExploreBrain.State.CIRCLE && !Double.isNaN(r.circleFrom)
                            && Math.abs(Heading.delta(r.yaw.wrapped(), r.circleFrom)) >= 100;
                }
                return b.at(t);
            }, (r, req, nth) -> CuriosityPort.WayOut.way(0, 0f));
            List<String> notes = traced(rig);
            rig.started();
            runUntil(rig, 90000, r -> notedAt(notes, "free after") >= 0);
            long late = notedAt(notes, "escape's CIRCLE out of time");
            int hop = rig.firstAfter("hop", late);
            Drive fwd = firstDrive(rig, "DRIVE_OFF", "hop", late);
            check(n, late > 0 && hop >= 0 && rig.timeOf(hop) - late <= 300 && fwd != null && fwd.t == rig.timeOf(hop)
                            && notedAt(notes, "a short leg forward drove cleanly") > late
                            && entered(rig, ExploreBrain.State.WAY_OUT, 0) < 0 && rig.wayOutRequests.isEmpty()
                            && rig.violations.isEmpty(),
                    "late@" + late + " hop@" + rig.timeOf(hop) + " notes=" + notes.subList(Math.max(0, notes.size() - 8),
                            notes.size()) + " " + rig.tail());
        });
    }

    // ---- the avoided side, at the motor (live 2026-09-25: "he only tries turning left") ----

    /** Each turn command's direction, in order, with its time: {t, +1 LEFT / -1 RIGHT}. */
    private static List<long[]> turnCommands(Rig rig, long from, long to) {
        List<long[]> out = new ArrayList<long[]>();
        for (Event e : rig.log) {
            if (e.t >= from && e.t < to && e.what.startsWith("turn ")) {
                out.add(new long[]{e.t, e.what.equals("turn LEFT") ? 1 : -1});
            }
        }
        return out;
    }

    /** Times of each note containing part. */
    private static List<Long> notedTimes(List<String> notes, String part) {
        List<Long> out = new ArrayList<Long>();
        for (String x : notes) {
            if (x.contains(part)) {
                out.add(Long.parseLong(x.substring(0, x.indexOf(' '))));
            }
        }
        return out;
    }

    /** After each blocked LEFT turn, the next turn command is RIGHT; returns the offending times. */
    private static List<Long> leftAfterBlockedLeft(Rig rig, List<String> notes) {
        List<Long> bad = new ArrayList<Long>();
        List<long[]> turns = turnCommands(rig, 0, Long.MAX_VALUE);
        for (long bt : notedTimes(notes, "measured turn blocked")) {
            long[] last = null;
            long[] next = null;
            for (long[] c : turns) {
                if (c[0] <= bt) {
                    last = c;
                } else if (next == null) {
                    next = c;
                }
            }
            if (last != null && last[1] == 1 && next != null && next[1] == 1) {
                bad.add(bt);
            }
        }
        return bad;
    }

    private static void sideScenarios() {
        scenario("side_left_blocked_roaming_retry_commands_right_every_time", n -> {
            // Only LEFT is blocked; RIGHT, reversing and driving are free.
            Rig rig = new Rig(escTuning().turnChance(1.0).build(), CLEAR, NOTHING, true);
            rig.simWheels = true;
            rig.yaw.stuckDir = 1;
            List<String> notes = traced(rig);
            rig.started();
            rig.runUntil(90000);
            List<Long> blocked = notedTimes(notes, "measured turn blocked");
            List<Long> bad = leftAfterBlockedLeft(rig, notes);
            boolean escaped = false;
            for (ExploreBrain.State st : rig.statesSeen) {
                escaped |= ESCAPING.contains(st);
            }
            // A blocked turn with a leg behind him to back out along still wedges him (U5);
            // only the directions matter here.
            check(n, blocked.size() >= 3 && bad.isEmpty() && rig.violations.isEmpty(),
                    "blocked=" + blocked + " bad=" + bad + " escaped=" + escaped + " " + rig.tail());
        });
        scenario("side_left_blocked_escape_ladder_commands_no_left_turn_until_free", n -> {
            // Wedged by bumps with LEFT blocked for good and nothing behind him giving: once a
            // LEFT turn has been blocked, every turn the ladder commands (retrace, circle,
            // drive-off, retries) is RIGHT, the long way round where it must be, until free.
            // (A retrace move whose long way round is over retraceLongWayMaxDeg is skipped now,
            // so one RIGHT turn can be all he needs before a forward try frees him.)
            Rig[] h = new Rig[1];
            // The ladder against a physical block with the board working: its back-ups read 0 like a
            // cutout's, so the post-stall wait would restart the ladder. The wait: the recover_ scenarios.
            Rig rig = escRig(escTuning().stallRecoverOff(), h, bumps(h, 3), null);
            rig.yaw.stuckDir = 1;
            rig.backBlocked = true;
            rig.openView = openAt(90);
            List<String> notes = traced(rig);
            rig.started();
            runUntil(rig, 120000, r -> notedAt(notes, "free after") >= 0 || r.brain.state() == ExploreBrain.State.CORNERED);
            long wedged = entered(rig, ExploreBrain.State.RETRACE, 0);
            long firstBlocked = -1;
            for (long t : notedTimes(notes, "measured turn blocked")) {
                if (firstBlocked < 0 && t >= wedged) {
                    firstBlocked = t;
                }
            }
            long end = notedAt(notes, "free after") >= 0 ? notedAt(notes, "free after") : rig.now;
            int lefts = 0;
            int rights = 0;
            for (long[] c : turnCommands(rig, firstBlocked + 1, end + 1)) {
                lefts += c[1] == 1 ? 1 : 0;
                rights += c[1] == -1 ? 1 : 0;
            }
            check(n, wedged > 0 && firstBlocked > 0 && lefts == 0 && rights >= 1 && rig.violations.isEmpty(),
                    "wedged@" + wedged + " blocked@" + firstBlocked + " lefts=" + lefts + " rights=" + rights + " "
                            + rig.tail());
        });
        scenario("side_left_blocked_the_turn_after_a_rest_and_the_next_ladder_go_right", n -> {
            // Pinned for driving (forward and back go nowhere), turns blocked LEFT only: the
            // ladder fails, and after the rest (its forward try blocked) the wider turn, the
            // still-pinned rest and the next ladder never command LEFT.
            // Legs long enough (3 s) for the stall watch to rule, so stalls wedge him.
            List<String> notes = new ArrayList<String>();
            Rig[] h = new Rig[1];
            Rig rig = escRig(escTuning().turnChance(1.0).hopTicks(12), h, CLEAR,
                    (r, req, nth) -> CuriosityPort.WayOut.way(0, 0f));
            rig.creepPer100 = 0;
            rig.brain.setTrace(x -> notes.add(h[0].now + " " + x));
            pin(rig, true);
            rig.blockedFrom = 0;
            rig.yaw.stuck = false;
            rig.yaw.stuckDir = 1;
            rig.started();
            runUntil(rig, 120000, r -> r.brain.state() == ExploreBrain.State.CORNERED);
            long rest = entered(rig, ExploreBrain.State.CORNERED, 0);
            rig.runUntil(Math.max(rest, 0) + 30000 + 120000);
            long firstBlocked = notedTimes(notes, "measured turn blocked").isEmpty() ? -1
                    : notedTimes(notes, "measured turn blocked").get(0);
            int turn = rig.firstAfter("turn", rest + 30000);
            int lefts = 0;
            for (long[] c : turnCommands(rig, firstBlocked + 1, Long.MAX_VALUE)) {
                lefts += c[1] == 1 ? 1 : 0;
            }
            check(n, rest > 0 && firstBlocked > 0 && firstBlocked < rest && rig.what(turn).equals("turn RIGHT") && lefts == 0
                            && entries(rig, ExploreBrain.State.RETRACE).size() >= 2
                            && notesWith(notes, "free after") == 0 && rig.violations.isEmpty(),
                    "rest@" + rest + " blocked@" + firstBlocked + " turn=" + rig.what(turn) + " lefts=" + lefts + " "
                            + rig.tail() + " notes=" + notes);
        });
        scenario("side_both_blocked_alternates_instead_of_one_side_for_ever", n -> {
            // Every turn blocked: the retries and ladders go back and forth, not LEFT every time.
            List<String> notes = new ArrayList<String>();
            Rig rig = pinnedLadderRig(notes);
            rig.started();
            rig.runUntil(120000);
            List<long[]> turns = turnCommands(rig, 0, Long.MAX_VALUE);
            int lefts = 0;
            int rights = 0;
            for (long[] c : turns) {
                lefts += c[1] == 1 ? 1 : 0;
                rights += c[1] == -1 ? 1 : 0;
            }
            check(n, turns.size() >= 4 && lefts >= 2 && rights >= 2 && rig.violations.isEmpty(),
                    "lefts=" + lefts + " rights=" + rights + " " + rig.tail());
        });
    }

    // ---- wedged: back up first (live 2026-09-25: nose to a wall, "he could just back up") ----

    /** Nose to a wall at 0 once the first leg is done: forward toward it goes nowhere; the first
     * turn's way pivots into the wall (blocked for good), the other way is free. */
    private static Rig noseToWallRig(List<String> notes, boolean backBlocked) {
        Rig[] h = new Rig[1];
        Rig rig = escRig(escTuning().turnChance(1.0), h, t -> {
            Rig r = h[0];
            if (r != null && r.wallFrom == Long.MAX_VALUE && !r.drives.isEmpty() && r.drives.get(0).end > 0) {
                r.wallAt = r.yaw.wrapped();
                r.wallFrom = t;
            }
            return clear(t);
        }, null);
        rig.yaw.blockFirstTurnsSide = true;
        rig.backBlocked = backBlocked;
        rig.creepPer100 = 0;
        rig.brain.setTrace(x -> notes.add(h[0].now + " " + x));
        return rig;
    }

    /** Ends a measured turn the way a blocked or cut one ends: moving for movingMs, then stuck. */
    private static void cutTurn(Bench b, int dir, double amount, long movingMs, long stuckMs) {
        b.h.startTurn(dir, amount);
        b.yaw.turn(dir);
        b.commanded = true;
        b.run(movingMs);
        b.yaw.stuck = true;
        b.run(stuckMs);
        b.yaw.stop();
        b.yaw.stuck = false;
        b.commanded = false;
        b.h.stopped(b.now);
    }

    private static void backUpFirstScenarios() {
        scenario("wedged_nose_to_wall_backs_up_first_then_turns_the_free_way_and_drives_off", n -> {
            List<String> notes = new ArrayList<String>();
            Rig rig = noseToWallRig(notes, false);
            rig.started();
            runUntil(rig, 60000, r -> notedAt(notes, "free after") >= 0 && !r.moving);
            long wedged = notedAt(notes, "wedged: a turn that would not turn");
            int first = rig.first("turn", 0);
            String blocked = rig.what(first);
            Drive back = wedged < 0 ? null : firstDrive(rig, "RETRACE", "back", wedged);
            Drive anyFirst = null;
            for (Drive d : rig.drives) {
                if (anyFirst == null && d.t >= wedged) {
                    anyFirst = d;
                }
            }
            int turn = back == null || back.end < 0 ? -1 : rig.firstAfter("turn", back.end);
            Drive off = turn < 0 ? null : firstDrive(rig, "RETRACE", "hop", rig.timeOf(turn));
            long free = notedAt(notes, "free after");
            check(n, wedged > 0 && back != null && anyFirst == back && back.end - back.t >= 1900 && Math.abs(back.counts) > 50
                            && notedAt(notes, "backing up first") >= wedged
                            && turn > 0 && !rig.what(turn).equals(blocked) && off != null && off.counts > 0
                            && free > 0 && free - wedged <= 10000 && notesWith(notes, "long way") == 0
                            && notesWith(notes, "retrace: facing") == 0 && entered(rig, ExploreBrain.State.CIRCLE, 0) < 0
                            && Math.abs(Heading.delta(off.heading, rig.wallAt)) > rig.wallHalfDeg
                            && rig.violations.isEmpty(),
                    "wedged@" + wedged + " blocked=" + blocked + " back=" + back + " turn=" + rig.what(turn) + " off=" + off
                            + " wall=" + f1(rig.wallAt) + " notes=" + notes.subList(Math.max(0, notes.size() - 14), notes.size()));
        });
        scenario("wedged_back_up_blocked_behind_goes_on_with_the_ladder", n -> {
            // Something behind him too: the back-up stalls, no back-out along the leg log
            // follows, and the ladder runs as before (retrace, then on) until he is free.
            List<String> notes = new ArrayList<String>();
            Rig rig = noseToWallRig(notes, true);
            rig.started();
            runUntil(rig, 150000, r -> notedAt(notes, "free after") >= 0 && !r.moving);
            long wedged = notedAt(notes, "wedged: a turn that would not turn");
            List<Drive> backs = drivesIn(rig, "RETRACE", "back", Math.max(0, wedged), Long.MAX_VALUE);
            long nowhere = notedAt(notes, "backing up first went nowhere");
            long retrace = notedAt(notes, "retrace: facing");
            Drive hop = retrace < 0 ? null : firstDrive(rig, "RETRACE", "hop", retrace);
            Drive anyFirst = null;
            for (Drive d : rig.drives) {
                if (anyFirst == null && d.t >= wedged) {
                    anyFirst = d;
                }
            }
            check(n, wedged > 0 && !backs.isEmpty() && anyFirst == backs.get(0) && Math.abs(backs.get(0).counts) <= 5
                            && nowhere >= backs.get(0).t && retrace > nowhere && hop != null
                            && notedAt(notes, "free after") > hop.t && notesWith(notes, "backing out straight") == 0
                            && notesWith(notes, "backed up: turning") == 0 && rig.violations.isEmpty(),
                    "wedged@" + wedged + " backs=" + backs + " hop=" + hop + " notes="
                            + notes.subList(Math.max(0, notes.size() - 14), notes.size()));
        });
        scenario("wedged_retrace_long_way_round_past_a_blocked_side_is_skipped", n -> {
            // Live 2026-09-25: "the LEFT side is blocked: turning the long way round", then 20 s
            // on a 327 deg turn. LEFT blocked for good, nothing behind him gives: a retrace move
            // that would go over retraceLongWayMaxDeg the long way is skipped for the next step.
            Rig[] h = new Rig[1];
            // The ladder against a physical block with the board working: its back-ups read 0 like a
            // cutout's, so the post-stall wait would restart the ladder. The wait: the recover_ scenarios.
            Rig rig = escRig(escTuning().stallRecoverOff(), h, bumps(h, 3), null);
            rig.yaw.stuckDir = 1;
            rig.backBlocked = true;
            rig.openView = openAt(90);
            List<String> notes = traced(rig);
            rig.started();
            runUntil(rig, 120000, r -> notedAt(notes, "free after") >= 0 || r.brain.state() == ExploreBrain.State.CORNERED);
            long skipped = notedAt(notes, "escape's RETRACE failed: the LEFT side is blocked and the long way round is");
            int longTurns = 0;
            for (String x : notes) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("RETRACE step gets \\d+ ms more for its (\\d+) deg")
                        .matcher(x);
                if (m.find() && Long.parseLong(m.group(1)) > rig.tuning.retraceLongWayMaxDeg) {
                    longTurns++;
                }
            }
            check(n, rig.tuning.retraceLongWayMaxDeg == 200 && skipped > 0 && longTurns == 0
                            && notedAt(notes, "free after") > skipped && rig.violations.isEmpty(),
                    "skipped@" + skipped + " longTurns=" + longTurns + " notes="
                            + notes.subList(Math.max(0, notes.size() - 12), notes.size()));
        });
        scenario("turn_rate_learns_only_from_completed_turns", n -> {
            // Live 2026-09-25: blocked and cut turns (degrees over 1.5 s of nothing) dragged the
            // learned rate to the 15 deg/s floor; real turns on that carpet ran ~40 deg/s.
            Bench b = new Bench(gyroTuning().build(), new YawSim(robotGyro()));
            b.still(1000);
            b.yaw.rateDegS = 20;
            b.turnBy(Heading.LEFT, 90);
            b.still(1000);
            double slow = b.h.turnRateDegS();
            b.yaw.rateDegS = 40;
            cutTurn(b, Heading.RIGHT, 40, 0, 1500);
            b.still(1000);
            cutTurn(b, Heading.LEFT, 90, 750, 1500);
            b.still(1000);
            cutTurn(b, Heading.RIGHT, 120, 1000, 0);
            b.still(1000);
            double afterCut = b.h.turnRateDegS();
            b.turnBy(Heading.LEFT, 90);
            b.still(1000);
            double afterReal = b.h.turnRateDegS();
            b.turnBy(Heading.RIGHT, 10);
            b.still(1000);
            double afterSmall = b.h.turnRateDegS();
            check(n, Math.abs(slow - 20) <= 3 && Math.abs(afterCut - slow) < 0.01 && afterReal > slow + 7
                            && afterReal <= 40 && Math.abs(afterSmall - afterReal) < 0.01,
                    "slow=" + f1(slow) + " afterCut=" + f1(afterCut) + " afterReal=" + f1(afterReal) + " afterSmall="
                            + f1(afterSmall));
        });
    }

    private static void escapeScenarios() {
        scenario("escape_ae1_wall_and_plant_retraces_the_way_in_and_roams_again", n -> {
            Rig[] h = new Rig[1];
            Rig rig = escRig(escTuning().escapeRetrace(60, 10), h, bumps(h, 3), null);
            rig.started();
            runUntil(rig, 40000, r -> entered(r, ExploreBrain.State.RETRACE, 0) >= 0);
            List<Heading.Leg> legs = rig.brain.heading().legs();
            long wedged = entered(rig, ExploreBrain.State.RETRACE, 0);
            rig.runUntil(wedged + 30000);
            Drive back = firstDrive(rig, "RETRACE", "hop", wedged);
            Drive roam = firstDrive(rig, "HOP", "hop", wedged);
            Heading.Leg newest = legs.isEmpty() ? null : legs.get(legs.size() - 1);
            check(n, wedged > 0 && newest != null && back != null
                            && near(back.heading, Heading.wrap(newest.heading + 180), 8)
                            && Math.abs(back.counts - 60) <= 12 && roam != null && roam.t - wedged <= 15000
                            && entered(rig, ExploreBrain.State.CIRCLE, 0) < 0 && rig.count("eyes RESTING") == 0
                            && rig.violations.isEmpty(),
                    "wedged@" + wedged + " legs=" + legs + " drives=" + rig.drives + " " + rig.tail());
        });
        scenario("escape_retrace_hazard_partway_moves_on_to_the_circle", n -> {
            Rig[] h = new Rig[1];
            Rig rig = escRig(escTuning(), h,
                    bumpsThen(h, 3, r -> r.brain.state() == ExploreBrain.State.RETRACE), null);
            rig.started();
            runUntil(rig, 60000, r -> entered(r, ExploreBrain.State.CIRCLE, 0) >= 0);
            long wedged = entered(rig, ExploreBrain.State.RETRACE, 0);
            long circle = entered(rig, ExploreBrain.State.CIRCLE, 0);
            Drive cut = firstDrive(rig, "RETRACE", "hop", wedged);
            Drive back = cut == null ? null : firstDrive(rig, "RETRACE", "back", cut.t);
            check(n, wedged > 0 && cut != null && cut.end > 0 && cut.counts <= 40 && back != null
                            && back.t >= cut.end && circle >= back.end && circle - cut.end <= 1500
                            && rig.count("eyes RESTING") == 0 && rig.violations.isEmpty(),
                    "wedged@" + wedged + " circle@" + circle + " drives=" + rig.drives + " " + rig.tail());
        });
        scenario("escape_three_short_legs_retrace_newest_first_up_to_the_retrace_distance", n -> {
            Rig[] h = new Rig[1];
            Rig rig = escRig(escTuning().escapeRetrace(150, 10).escapeBudgets(20000, 20000, 6000, 3000, 6000),
                    h, bumps(h, 3), null);
            rig.started();
            runUntil(rig, 40000, r -> entered(r, ExploreBrain.State.RETRACE, 0) >= 0);
            List<Heading.Leg> legs = rig.brain.heading().legs();
            long wedged = entered(rig, ExploreBrain.State.RETRACE, 0);
            rig.runUntil(wedged + 25000);
            List<Drive> back = drivesIn(rig, "RETRACE", "hop", wedged, Long.MAX_VALUE);
            boolean ok = legs.size() == 5 && back.size() == 3;
            long total = 0;
            if (ok) {
                // Newest first: L3 whole (~70), L2 less its back-off (~45), L1 less its back-off, cut at 150.
                long l3 = legs.get(4).counts;
                long l2 = legs.get(2).counts - legs.get(3).counts;
                long l1 = Math.min(legs.get(0).counts - legs.get(1).counts, 150 - l3 - l2);
                ok = near(back.get(0).heading, Heading.wrap(legs.get(4).heading + 180), 8)
                        && near(back.get(1).heading, Heading.wrap(legs.get(2).heading + 180), 8)
                        && near(back.get(2).heading, Heading.wrap(legs.get(0).heading + 180), 8)
                        && Math.abs(back.get(0).counts - l3) <= 12 && Math.abs(back.get(1).counts - l2) <= 12
                        && Math.abs(back.get(2).counts - l1) <= 12;
                for (Drive d : back) {
                    total += d.counts;
                }
            }
            check(n, ok && total <= 165 && firstDrive(rig, "HOP", "hop", wedged) != null
                            && entered(rig, ExploreBrain.State.CIRCLE, 0) < 0 && rig.violations.isEmpty(),
                    "legs=" + legs + " retrace=" + back + " " + rig.tail());
        });
        scenario("escape_claude_frame_5_centre_turns_to_that_frames_heading_and_drives_off", n -> {
            Rig[] h = new Rig[1];
            Rig rig = escRig(escTuning().escapeRetrace(0, 10), h, bumps(h, 3),
                    (r, req, nth) -> CuriosityPort.WayOut.way(4, 0f));
            rig.started();
            runUntil(rig, 60000, r -> firstDrive(r, "DRIVE_OFF", "hop", 0) != null && !r.moving);
            rig.runUntil(rig.now + 5000);
            CuriosityPort.WayOutRequest req = rig.wayOutRequests.isEmpty() ? null : rig.wayOutRequests.get(0);
            Double aim = req == null || req.frames.size() < 5 ? null : rig.lookHeadings.get(captured(req.frames.get(4)));
            Drive off = firstDrive(rig, "DRIVE_OFF", "hop", 0);
            check(n, req != null && req.frames.size() == 6 && !req.second && aim != null && off != null
                            && near(off.heading, aim, 8) && rig.wayOutRequests.size() == 1
                            && firstDrive(rig, "HOP", "hop", off.t) != null && rig.violations.isEmpty(),
                    "aim=" + aim + " off=" + off + " " + rig.tail());
        });
        scenario("escape_ae7_offline_uses_the_most_open_heading_on_the_robot", n -> {
            Rig[] h = new Rig[1];
            Rig rig = escRig(escTuning().escapeRetrace(0, 10), h, bumps(h, 3), null);
            rig.openView = openAt(180);
            rig.started();
            runUntil(rig, 60000, r -> firstDrive(r, "DRIVE_OFF", "hop", 0) != null && !r.moving);
            rig.runUntil(rig.now + 5000);
            Drive off = firstDrive(rig, "DRIVE_OFF", "hop", 0);
            check(n, off != null && near(off.heading, Heading.wrap(rig.circleFrom + 180), 10)
                            && rig.wayOutRequests.isEmpty() && rig.count("eyes RESTING") == 0
                            && firstDrive(rig, "HOP", "hop", off.t) != null && rig.violations.isEmpty(),
                    "from=" + f1(rig.circleFrom) + " off=" + off + " " + rig.tail());
        });
        scenario("escape_on_robot_heading_penalises_headings_already_tried", n -> {
            // The way he faced when wedged and its opposite read equally open: he takes the opposite.
            Rig[] h = new Rig[1];
            Rig rig = escRig(escTuning().escapeRetrace(0, 10), h, bumps(h, 3), null);
            rig.openView = openAt(0, 180);
            rig.started();
            runUntil(rig, 60000, r -> firstDrive(r, "DRIVE_OFF", "hop", 0) != null);
            Drive off = firstDrive(rig, "DRIVE_OFF", "hop", 0);
            check(n, off != null && near(off.heading, Heading.wrap(rig.circleFrom + 180), 10)
                            && rig.violations.isEmpty(),
                    "from=" + f1(rig.circleFrom) + " off=" + off + " " + rig.tail());
        });
        scenario("escape_late_claude_answer_is_dropped_and_the_robot_heading_used", n -> {
            Rig[] h = new Rig[1];
            Rig rig = escRig(escTuning().escapeRetrace(0, 10), h, bumps(h, 3),
                    (r, req, nth) -> CuriosityPort.WayOut.way(0, 0f));
            rig.wayOutDelayMs = 7000;
            rig.openView = openAt(180);
            rig.started();
            runUntil(rig, 60000, r -> firstDrive(r, "DRIVE_OFF", "hop", 0) != null);
            rig.runUntil(rig.now + 3000);
            int ask = rig.first("way-out", 0);
            int cancel = rig.first("cancel way-out", ask);
            Drive off = firstDrive(rig, "DRIVE_OFF", "hop", 0);
            check(n, ask >= 0 && cancel > ask && Math.abs(rig.timeOf(cancel) - rig.timeOf(ask) - 6000) <= 100
                            && rig.first("way-out answer", 0) < 0 && off != null
                            && near(off.heading, Heading.wrap(rig.circleFrom + 180), 10) && rig.violations.isEmpty(),
                    "off=" + off + " " + rig.tail());
        });
        scenario("escape_frame_7_of_6_is_rejected_and_the_robot_heading_used", n -> {
            Rig[] h = new Rig[1];
            Rig rig = escRig(escTuning().escapeRetrace(0, 10), h, bumps(h, 3),
                    (r, req, nth) -> CuriosityPort.WayOut.way(6, 0f));
            rig.openView = openAt(180);
            rig.started();
            runUntil(rig, 60000, r -> firstDrive(r, "DRIVE_OFF", "hop", 0) != null);
            Drive off = firstDrive(rig, "DRIVE_OFF", "hop", 0);
            check(n, rig.first("way-out answer WAY", 0) >= 0 && off != null
                            && near(off.heading, Heading.wrap(rig.circleFrom + 180), 10) && rig.violations.isEmpty(),
                    "off=" + off + " " + rig.tail());
        });
        scenario("replies_way_out_reads_frame_and_x_and_rejects_bad_answers", n -> {
            int[] w = {640, 640, 640, 640, 640, 640};
            java.util.Map<String, Object> json = new java.util.LinkedHashMap<String, Object>();
            json.put("way_out", Boolean.TRUE);
            json.put("frame", 5L);
            json.put("x", 480L);
            CuriosityPort.WayOut ok = ClaudeReplies.wayOut(json, w);
            json.put("x", 0.25);
            CuriosityPort.WayOut fraction = ClaudeReplies.wayOut(json, w);
            json.put("x", 320L);
            json.put("frame", 7L);
            CuriosityPort.WayOut seventh = ClaudeReplies.wayOut(json, w);
            json.put("frame", 0L);
            CuriosityPort.WayOut zeroth = ClaudeReplies.wayOut(json, w);
            json.put("frame", "5");
            CuriosityPort.WayOut text = ClaudeReplies.wayOut(json, w);
            json.put("frame", 5L);
            json.put("x", 900L);
            CuriosityPort.WayOut wide = ClaudeReplies.wayOut(json, w);
            json.remove("x");
            CuriosityPort.WayOut noX = ClaudeReplies.wayOut(json, w);
            json.put("way_out", Boolean.FALSE);
            CuriosityPort.WayOut none = ClaudeReplies.wayOut(json, w);
            json.put("way_out", "yes");
            CuriosityPort.WayOut odd = ClaudeReplies.wayOut(json, w);
            CuriosityPort.WayOut.Status F = CuriosityPort.WayOut.Status.FAILED;
            check(n, ok.status == CuriosityPort.WayOut.Status.WAY && ok.frame == 4 && Math.abs(ok.x - 0.5f) < 1e-4
                            && fraction.status == CuriosityPort.WayOut.Status.WAY && Math.abs(fraction.x + 0.5f) < 1e-4
                            && seventh.status == F && zeroth.status == F && text.status == F && wide.status == F
                            && noX.status == F && none.status == CuriosityPort.WayOut.Status.NONE && odd.status == F,
                    ok + " " + fraction + " " + seventh + " " + zeroth + " " + text + " " + wide + " " + noX + " "
                            + none + " " + odd);
        });
        scenario("escape_full_budgets_drive_off_within_30_s_of_wedged", n -> {
            // The shipped budgets and turn rates, turning at 25 deg/s. Before steps covered
            // their turns, the retrace's turn and the circle ran out of time here (their 6 s
            // and 12 s only, drive-off by 30 s). Now each step's budget adds its turns at the
            // learned 25 deg/s, and a turn still turning is never cut: the retrace turns round
            // (its drive meets a hazard), the circle takes all six looks past its fixed 12 s,
            // and the drive-off comes within the target scaled the same way: the 27 s of fixed
            // allowances (the ~30 s target at the default turn rate) plus the ladder's turns
            // (retrace 180, circle 300, drive-off up to 180 deg) at 25 deg/s.
            ExploreTuning d = new ExploreTuning.Builder().build();
            Rig[] h = new Rig[1];
            ExploreTuning.Builder b = escTuning()
                    .escapeBudgets(d.escapeRetraceMs, d.escapeCircleMs, d.escapeAskMs, d.escapeDriveOffMs,
                            d.escapeSecondAskMs)
                    .escapeCircle(d.escapeCircleSteps, d.escapeCircleStepDeg, d.escapeSettleMs)
                    .escapeTurnRate(d.escapeTurnRateDegS, d.escapeTurnRateFloorDegS, d.escapeTurnAllowanceMaxMs);
            List<String> notes = new ArrayList<String>();
            Rig rig = escRig(b, h, bumpsThen(h, 3, r -> r.brain.state() == ExploreBrain.State.RETRACE),
                    (r, req, nth) -> CuriosityPort.WayOut.way(3, 0f));
            rig.brain.setTrace(x -> notes.add(h[0].now + " " + x));
            rig.wayOutDelayMs = 5900;
            rig.yaw.rateDegS = 25;
            rig.started();
            runUntil(rig, 150000, r -> firstDrive(r, "DRIVE_OFF", "hop", 0) != null);
            long wedged = entered(rig, ExploreBrain.State.RETRACE, 0);
            long circle = entered(rig, ExploreBrain.State.CIRCLE, 0);
            long ask = entered(rig, ExploreBrain.State.WAY_OUT, 0);
            long answer = rig.timeOf(rig.first("way-out answer", 0));
            Drive off = firstDrive(rig, "DRIVE_OFF", "hop", 0);
            long sum = d.escapeRetraceMs + d.escapeCircleMs + d.escapeAskMs + d.escapeDriveOffMs;
            long target = sum + Math.round((180 + 300 + 180) * 1000.0 / 25);
            check(n, wedged > 0 && circle > wedged && off != null && off.t - wedged <= target
                            && ask - circle > d.escapeCircleMs && answer - ask >= 5800 && notesWith(notes, "out of time") == 0
                            && notesWith(notes, "circle look 6 of 6") == 1 && sum >= 25000 && sum <= 30000
                            && d.escapeCircleSteps == 6 && d.escapeCircleStepDeg == 60 && rig.violations.isEmpty(),
                    "wedged@" + wedged + " circle@" + circle + " ask@" + ask + " answer@" + answer + " off=" + off
                            + " target=" + target + " " + rig.tail());
        });
        scenario("escape_second_ask_is_sent_once_per_escape", n -> {
            Rig[] h = new Rig[1];
            Rig rig = escRig(escTuning().escapeRetrace(0, 10), h,
                    bumpsThen(h, 3, r -> r.brain.state() == ExploreBrain.State.DRIVE_OFF),
                    (r, req, nth) -> CuriosityPort.WayOut.way(nth == 1 ? 3 : 0, 0f));
            rig.started();
            runUntil(rig, 90000, r -> r.brain.state() == ExploreBrain.State.CORNERED);
            rig.runUntil(rig.now + 5000);
            List<CuriosityPort.WayOutRequest> asks = rig.wayOutRequests;
            check(n, asks.size() == 2 && asks.get(0).frames.size() == 6 && !asks.get(0).second
                            && asks.get(1).frames.size() == 1 && asks.get(1).second
                            && drivesIn(rig, "DRIVE_OFF", "hop", 0, Long.MAX_VALUE).size() == 2
                            && rig.brain.state() == ExploreBrain.State.CORNERED && rig.violations.isEmpty(),
                    "asks=" + asks.size() + " drives=" + rig.drives + " " + rig.tail());
        });
        scenario("escape_all_steps_fail_rests_cornered_then_roams", n -> {
            Rig[] h = new Rig[1];
            Rig rig = escRig(escTuning(), h,
                    bumpsThen(h, 3, r -> !r.statesSeen.contains(ExploreBrain.State.CORNERED)),
                    (r, req, nth) -> CuriosityPort.WayOut.failed());
            rig.started();
            runUntil(rig, 120000, r -> r.brain.state() == ExploreBrain.State.CORNERED);
            long rest = entered(rig, ExploreBrain.State.CORNERED, 0);
            rig.runUntil(rest + 30000 + 8000);
            long retrace = entered(rig, ExploreBrain.State.RETRACE, 0);
            long circle = entered(rig, ExploreBrain.State.CIRCLE, retrace);
            long ask = entered(rig, ExploreBrain.State.WAY_OUT, circle);
            long off = entered(rig, ExploreBrain.State.DRIVE_OFF, ask);
            long second = entered(rig, ExploreBrain.State.WAY_OUT, off);
            Drive roam = firstDrive(rig, "HOP", "hop", rest);
            check(n, retrace > 0 && circle > retrace && ask > circle && off > ask && second > off && rest > second
                            && rig.wayOutRequests.size() == 2 && rig.motions(rest + 1, rest + 30000) == 0
                            && roam != null && roam.t > rest + 30000 && rig.violations.isEmpty(),
                    "retrace@" + retrace + " circle@" + circle + " ask@" + ask + " off@" + off + " second@" + second
                            + " rest@" + rest + " " + rig.tail());
        });
        scenario("escape_lease_loss_during_the_circle_goes_eyes_only_stopped", n -> {
            Rig[] h = new Rig[1];
            Rig rig = escRig(escTuning().escapeRetrace(0, 10), h, bumps(h, 3), null);
            rig.started();
            runUntil(rig, 60000, r -> r.brain.state() == ExploreBrain.State.CIRCLE && r.moving);
            long at = rig.now;
            boolean turning = rig.moving;
            rig.brain.onLeaseChanged(false);
            rig.runUntil(at + 3000);
            check(n, turning && rig.timeOf(rig.firstAfter("stop", at)) == at
                            && rig.timeOf(rig.firstAfter("camera close", at)) == at
                            && rig.brain.state() == ExploreBrain.State.EYES_ONLY && rig.motions(at + 1, at + 3000) == 0
                            && rig.violations.isEmpty(),
                    "at=" + at + " " + rig.tail());
        });
        scenario("escape_way_out_carries_only_the_circles_frames_and_notes_carry_counts", n -> {
            Rig[] h = new Rig[1];
            List<String> notes = new ArrayList<String>();
            Rig rig = escRig(escTuning().escapeRetrace(0, 10), h, bumps(h, 3),
                    (r, req, nth) -> CuriosityPort.WayOut.way(3, 0f));
            rig.openView = openAt(180);
            rig.brain.setTrace(notes::add);
            rig.started();
            runUntil(rig, 60000, r -> firstDrive(r, "DRIVE_OFF", "hop", 0) != null);
            long circle = entered(rig, ExploreBrain.State.CIRCLE, 0);
            CuriosityPort.WayOutRequest req = rig.wayOutRequests.isEmpty() ? null : rig.wayOutRequests.get(0);
            boolean framesOk = req != null && req.frames.size() == 6;
            java.util.Set<Long> times = new java.util.HashSet<Long>();
            if (framesOk) {
                for (CuriosityPort.Frame f : req.frames) {
                    long t = captured(f);
                    framesOk &= t >= circle && rig.lookHeadings.containsKey(t) && times.add(t);
                }
            }
            boolean numbersOnly = true;
            boolean spoke = false;
            for (String note : notes) {
                numbersOnly &= !note.contains("jpeg") && !note.contains("bins") && !note.contains("confidence");
                spoke |= note.contains("way out");
            }
            check(n, framesOk && numbersOnly && spoke && rig.violations.isEmpty(),
                    "frames=" + (req == null ? 0 : req.frames.size()) + " notes=" + notes);
        });
        scenario("escape_uncalibrated_keeps_todays_escape", n -> {
            Rig[] h = new Rig[1];
            Rig rig = new Rig(tuning().hopTicks(4).cap(3, 20000, 30000).wedge(3, 2, 1).build(), bumps(h, 3));
            rig.simWheels = true;
            h[0] = rig;
            rig.started();
            rig.runUntil(20000);
            boolean escaped = false;
            for (ExploreBrain.State st : rig.statesSeen) {
                escaped |= ESCAPING.contains(st);
            }
            check(n, rig.statesSeen.contains(ExploreBrain.State.CORNERED) && !escaped && rig.violations.isEmpty(),
                    "seen=" + rig.statesSeen + " " + rig.tail());
        });
        // ---- a turn the gyro says isn't turning (live: wedged under a desk, "asked 120 deg, turned 0") ----
        scenario("turn_flat_yaw_in_the_circle_is_blocked_within_1_5_s_and_the_escape_advances", n -> {
            // The wriggle off: with it, the second blocked turn in a row starts one instead.
            Rig[] h = new Rig[1];
            Feed b = bumps(h, 3);
            Rig rig = escRig(escTuning().escapeRetrace(0, 10).wriggleOff(), h, t -> {
                Rig r = h[0];
                if (r != null && r.brain.state() == ExploreBrain.State.CIRCLE) {
                    r.yaw.stuck = true;
                }
                return b.at(t);
            }, null);
            rig.started();
            runUntil(rig, 60000, r -> entered(r, ExploreBrain.State.DRIVE_OFF, 0) >= 0);
            long circle = entered(rig, ExploreBrain.State.CIRCLE, 0);
            int turn = rig.firstAfter("turn", circle);
            int stop = rig.first("stop", turn);
            long took = rig.timeOf(stop) - rig.timeOf(turn);
            // One short back-up, then the same turn once more; blocked again, the step fails.
            int back = rig.first("back", stop);
            int retry = rig.first("turn", stop);
            int stop2 = rig.first("stop", retry);
            long took2 = rig.timeOf(stop2) - rig.timeOf(retry);
            Event next = nextState(rig, rig.timeOf(stop) - 1);
            check(n, circle > 0 && turn > 0 && took >= 1400 && took <= 1700 && back > stop && retry > back
                            && took2 >= 1400 && took2 <= 1700 && next != null && next.t >= rig.timeOf(stop2)
                            && next.t - rig.timeOf(stop2) <= 300 && !next.what.equals("CIRCLE")
                            && rig.violations.isEmpty(),
                    "took=" + took + " took2=" + took2 + " next=" + next + " " + rig.tail());
        });
        scenario("turn_flat_yaw_while_roaming_is_blocked_and_counts_as_wedged", n -> {
            Rig rig = new Rig(escTuning().turnChance(1.0).build(), CLEAR, NOTHING, true);
            rig.simWheels = true;
            rig.yaw.stuck = true;
            rig.started();
            runUntil(rig, 20000, r -> escaping(r));
            int turn = rig.first("turn", 0);
            int stop = rig.first("stop", turn);
            long took = rig.timeOf(stop) - rig.timeOf(turn);
            // Nothing logged to back out along: a short back-up, the same turn again, then wedged.
            int back = rig.first("back", stop);
            int retry = rig.first("turn", stop);
            int stop2 = rig.first("stop", retry);
            long escape = -1;
            for (Event e : rig.stateLog) {
                if (escape < 0 && e.t >= rig.timeOf(stop) && ESCAPING.contains(ExploreBrain.State.valueOf(e.what))) {
                    escape = e.t;
                }
            }
            check(n, turn >= 0 && took >= 1400 && took <= 1700 && back > stop && retry > back
                            && escape >= rig.timeOf(stop2) && escape - rig.timeOf(stop2) <= 300
                            && rig.violations.isEmpty(),
                    "took=" + took + " escape@" + escape + " " + rig.tail());
        });
        scenario("turn_slow_but_moving_is_not_blocked", n -> {
            Rig rig = new Rig(escTuning().turnChance(1.0).turnDeg(40, 40).build(), CLEAR, NOTHING, true);
            rig.simWheels = true;
            rig.yaw.rateDegS = 8;
            rig.started();
            rig.runUntil(12000);
            double r = rig.yaw.turnResults.isEmpty() ? 0 : rig.yaw.turnResults.get(0);
            boolean escaped = false;
            for (ExploreBrain.State st : rig.statesSeen) {
                escaped |= ESCAPING.contains(st);
            }
            check(n, Math.abs(Math.abs(r) - 40) <= 6 && !escaped && rig.firstAfter("hop", 5000) >= 0
                            && rig.violations.isEmpty(),
                    "result=" + f1(r) + " " + rig.tail());
        });
        // ---- turns blocked: back out straight along the last leg first (live: under a desk) ----
        scenario("escape_blocked_turns_back_out_along_the_last_leg_then_turn_and_drive_off", n -> {
            Rig[] h = new Rig[1];
            // The leg back-out itself: no blind back-up first (wedged_nose_to_wall_* covers that).
            Rig rig = escRig(escTuning().blockedTurnBackTicks(0), h, t -> {
                Rig r = h[0];
                if (r != null) {
                    boolean backedOut = false;
                    for (Drive d : r.drives) {
                        backedOut |= d.kind.equals("back") && d.end > 0;
                    }
                    r.yaw.stuck = r.count("hop") > 0 && !backedOut;
                }
                return clear(t);
            }, null);
            rig.openView = deskView();
            rig.started();
            rig.runUntil(20000);
            Drive back = null;
            for (Drive d : rig.drives) {
                if (back == null && d.kind.equals("back")) {
                    back = d;
                }
            }
            int turn = back == null ? -1 : rig.firstAfter("turn", back.end);
            long turnAt = rig.timeOf(turn);
            boolean turned = false;
            for (long[] tt : turnTimes(rig)) {
                turned |= tt[0] == turnAt;
            }
            double last = rig.yaw.turnResults.isEmpty() ? 0 : rig.yaw.turnResults.get(rig.yaw.turnResults.size() - 1);
            Drive roam = back == null ? null : firstDrive(rig, "HOP", "hop", back.end);
            check(n, back != null && back.state.equals("RETRACE") && Math.abs(back.counts + 100) <= 12
                            && turn > 0 && turned && Math.abs(last) >= 10 && roam != null && roam.t > turnAt
                            && entered(rig, ExploreBrain.State.CIRCLE, 0) < 0 && rig.violations.isEmpty(),
                    "back=" + back + " last=" + f1(last) + " drives=" + rig.drives + " " + rig.tail());
        });
        scenario("escape_back_out_stops_at_the_logged_distance_and_the_retrace_distance", n -> {
            long[] got = new long[2];
            long[] cap = {1500, 60};
            String detail = "";
            for (int i = 0; i < 2; i++) {
                Rig[] h = new Rig[1];
                Rig rig = escRig(escTuning().escapeRetrace(cap[i], 10).blockedTurnBackTicks(0), h, t -> {
                    Rig r = h[0];
                    if (r != null && r.count("hop") > 0) {
                        r.yaw.stuck = true;
                    }
                    return clear(t);
                }, null);
                rig.openView = deskView();
                rig.started();
                rig.runUntil(12000);
                Drive back = null;
                for (Drive d : rig.drives) {
                    if (back == null && d.kind.equals("back")) {
                        back = d;
                    }
                }
                got[i] = back == null ? 0 : -back.counts;
                detail += " cap " + cap[i] + ": " + back + " " + rig.violations;
            }
            check(n, Math.abs(got[0] - 100) <= 12 && Math.abs(got[1] - 60) <= 12, detail);
        });
        scenario("escape_back_out_needs_a_logged_leg", n -> {
            Rig rig = new Rig(escTuning().turnChance(1.0).build(), CLEAR, NOTHING, true);
            rig.simWheels = true;
            rig.yaw.stuck = true;
            rig.started();
            runUntil(rig, 30000, r -> entered(r, ExploreBrain.State.CIRCLE, 0) >= 0);
            // No back-out along a leg: only the short blind back-ups (blockedTurnBackTicks), before
            // retried turns and, one per ladder, the ladder's first move.
            long shortMs = rig.tuning.blockedTurnBackTicks * 250 + 100;
            boolean shortOnly = true;
            for (Drive d : rig.drives) {
                shortOnly &= !d.kind.equals("back") || (d.end > 0 && d.end - d.t <= shortMs);
            }
            int ladderBacks = drivesIn(rig, "RETRACE", "back", 0, Long.MAX_VALUE).size();
            check(n, entered(rig, ExploreBrain.State.CIRCLE, 0) > 0
                            && ladderBacks <= entries(rig, ExploreBrain.State.RETRACE).size() && shortOnly
                            && rig.violations.isEmpty(),
                    rig.drives + " " + rig.tail());
        });
        scenario("escape_stall_during_back_out_stops_it", n -> {
            Rig[] h = new Rig[1];
            Rig rig = escRig(escTuning(), h, t -> {
                Rig r = h[0];
                if (r != null && r.count("hop") > 0) {
                    r.yaw.stuck = true;
                }
                return clear(t);
            }, null);
            rig.backBlocked = true;
            rig.openView = deskView();
            rig.started();
            // Driving forward is free here, so the first ladder ends in a forward try once a
            // step fails (forward first), not always in the rest: only its back moves count.
            // (Since robot 15:19 the recovery wait backs up first and turns only after two
            // still back-ups, so with behind blocked the wait runs to its end: longer.)
            runUntil(rig, 90000, r -> r.brain.state() == ExploreBrain.State.CORNERED
                    || entries(r, ExploreBrain.State.RETRACE).size() >= 2);
            List<Long> ladders = entries(rig, ExploreBrain.State.RETRACE);
            long ladderEnd = ladders.size() >= 2 ? ladders.get(1) : Long.MAX_VALUE;
            List<Drive> backs = new ArrayList<Drive>();
            for (Drive d : rig.drives) {
                if (d.kind.equals("back") && d.t < ladderEnd) {
                    backs.add(d);
                }
            }
            Drive first = backs.isEmpty() ? null : backs.get(0);
            long took = first == null ? -1 : first.end - first.t;
            // After it, only the short back-ups before retried turns (blockedTurnBackTicks, or
            // less when the stall watch stops one), one per blocked turn.
            boolean restShort = true;
            for (int i = 1; i < backs.size(); i++) {
                restShort &= backs.get(i).end - backs.get(i).t <= rig.tuning.blockedTurnBackTicks * 250 + 100;
            }
            check(n, first != null && first.state.equals("RETRACE") && took >= 900 && took <= 1500
                            && Math.abs(first.counts) <= 2 && restShort && (ladders.size() >= 2
                            || rig.brain.state() == ExploreBrain.State.CORNERED) && rig.violations.isEmpty(),
                    "backs=" + backs + " " + rig.tail());
        });
        // ---- a blocked side: the retry and later turns go the other way (live 2026-09-25) ----
        scenario("blocked_side_backs_up_then_turns_the_other_way_and_drives_off", n -> {
            // The first turn's way is blocked, the other way and reversing are free.
            Rig rig = new Rig(escTuning().turnChance(1.0).build(), CLEAR, NOTHING, true);
            rig.simWheels = true;
            rig.yaw.blockFirstTurnsSide = true;
            rig.started();
            rig.runUntil(15000);
            int first = rig.first("turn", 0);
            List<Drive> backs = drivesIn(rig, "BACK_OFF", "back", 0, Long.MAX_VALUE);
            Drive back = backs.isEmpty() ? null : backs.get(0);
            int retry = back == null || back.end < 0 ? -1 : rig.firstAfter("turn", back.end);
            String other = rig.what(first).equals("turn LEFT") ? "turn RIGHT" : "turn LEFT";
            boolean turned = false;
            for (double r : rig.yaw.turnResults) {
                turned |= Math.abs(r) >= 15;
            }
            Drive hop = retry < 0 ? null : firstDrive(rig, "HOP", "hop", rig.timeOf(retry));
            boolean escaped = false;
            for (ExploreBrain.State st : rig.statesSeen) {
                escaped |= ESCAPING.contains(st);
            }
            check(n, first >= 0 && back != null && backs.size() == 1 && back.end - back.t >= 1900
                            && rig.what(retry).equals(other) && turned && hop != null && !escaped
                            && rig.violations.isEmpty(),
                    "first=" + rig.what(first) + " backs=" + backs + " retry=" + rig.what(retry) + " results="
                            + rig.yaw.turnResults + " " + rig.tail());
        });
        scenario("blocked_side_waits_3_s_after_the_back_up_before_trying_the_other_way", n -> {
            // Robot 15:19: a blocked turn is a fresh stall and re-arms the board's cutout, so the
            // other way straight after it read nothing. He backs up, waits blockedTurnWaitMs
            // (3 s) still, then turns the other way.
            Rig rig = new Rig(escTuning().turnChance(1.0).build(), CLEAR, NOTHING, true);
            List<String> notes = traced(rig);
            rig.simWheels = true;
            rig.yaw.blockFirstTurnsSide = true;
            rig.started();
            rig.runUntil(20000);
            List<Drive> backs = drivesIn(rig, "BACK_OFF", "back", 0, Long.MAX_VALUE);
            Drive back = backs.isEmpty() ? null : backs.get(0);
            int retry = back == null || back.end < 0 ? -1 : rig.firstAfter("turn", back.end);
            long waited = notedAt(notes, "backed up: waiting 3000 ms before turning");
            int moves = back == null ? -1 : rig.countPrefix("turn", back.end, back.end + 3000)
                    + rig.countPrefix("hop", back.end, back.end + 3000) + rig.countPrefix("back", back.end, back.end + 3000);
            check(n, back != null && waited >= back.end && waited - back.end <= 20 && retry >= 0
                            && rig.timeOf(retry) - back.end >= 3000 && rig.timeOf(retry) - back.end <= 3700
                            && moves == 0 && rig.violations.isEmpty(),
                    "backs=" + backs + " waited@" + waited + " retry@" + rig.timeOf(retry) + " " + rig.tail());
        });
        scenario("blocked_side_escape_turns_go_the_unblocked_way_even_the_long_way_round", n -> {
            // Bumped into a wedge; the first turn's way is blocked for good, and nothing behind
            // him gives (reversing goes nowhere), so the ladder's turns must go the other way:
            // the retrace's, the circle's and the drive-off's. Every turn after the first does.
            Rig[] h = new Rig[1];
            // The ladder against a physical block with the board working: its back-ups read 0 like a
            // cutout's, so the post-stall wait would restart the ladder. The wait: the recover_ scenarios.
            Rig rig = escRig(escTuning().stallRecoverOff(), h, bumps(h, 3), null);
            rig.yaw.blockFirstTurnsSide = true;
            rig.backBlocked = true;
            rig.openView = deskView();
            List<String> notes = traced(rig);
            rig.started();
            runUntil(rig, 60000, r -> notedAt(notes, "free after") >= 0);
            int first = rig.first("turn", 0);
            String blocked = rig.what(first);
            int second = rig.first("turn", first + 1);
            boolean otherWay = second > 0;
            for (int i = second; otherWay && i < rig.log.size(); i++) {
                otherWay &= !rig.log.get(i).what.equals(blocked);
            }
            List<String> key = new ArrayList<String>();
            for (String x : notes) {
                if (x.contains("blocked") || x.contains("other way") || x.contains("wedged") || x.contains("free after")) {
                    key.add(x);
                }
            }
            check(n, first >= 0 && otherWay && notedAt(notes, "measured turn blocked") >= 0
                            && notedAt(notes, "the long way round") >= 0 && notedAt(notes, "free after") >= 0
                            && enteredAny(rig, 0, ExploreBrain.State.RETRACE, ExploreBrain.State.CIRCLE) >= 0
                            && entered(rig, ExploreBrain.State.CORNERED, 0) < 0 && rig.violations.isEmpty(),
                    "blocked=" + blocked + " notes=" + key + " " + rig.tail());
        });
        scenario("blocked_turn_back_up_default_is_about_2_s", n -> {
            ExploreTuning t = new ExploreTuning.Builder().build();
            check(n, t.blockedTurnBackTicks * t.backTickMs >= 1500 && t.blockedTurnBackTicks * t.backTickMs <= 2000,
                    t.blockedTurnBackTicks + " x " + t.backTickMs);
        });
        // ---- signed wheel counters (live 2026-09-25: reverse counts down, from 0 at power-up) ----
        scenario("wheels_negative_counts_back_out_reports_the_real_distance", n -> {
            // As escape_back_out_stops_at_the_logged_distance...: the counters start below
            // zero, so the leg in crosses zero and the back-out crosses it again.
            Rig[] h = new Rig[1];
            Rig rig = escRig(escTuning().blockedTurnBackTicks(0), h, t -> {
                Rig r = h[0];
                if (r != null && r.count("hop") > 0) {
                    r.yaw.stuck = true;
                }
                return clear(t);
            }, null);
            rig.wheelLeft = -60;
            rig.wheelRight = -40;
            rig.openView = deskView();
            List<String> notes = traced(rig);
            rig.started();
            rig.runUntil(12000);
            Drive back = null;
            for (Drive d : rig.drives) {
                if (back == null && d.kind.equals("back")) {
                    back = d;
                }
            }
            long noted = -1;
            for (String x : notes) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("backed out (\\d+) counts").matcher(x);
                if (noted < 0 && m.find()) {
                    noted = Long.parseLong(m.group(1));
                }
            }
            check(n, back != null && Math.abs(-back.counts - 100) <= 12 && Math.abs(noted - 100) <= 12
                            && rig.violations.isEmpty(),
                    "back=" + back + " noted=" + noted + " " + rig.tail());
        });
        scenario("wheels_stall_is_detected_below_and_across_zero", n -> {
            // Below zero: the wheels turn until 2000, then stand still at negative counts.
            Rig below = new Rig(tuning().hopTicks(20).build(), t -> new SensorReading(t, 300 + jitter(t), 100, 100,
                    null, false, -900 + Math.min(t, 2000) / 10, -700 + Math.min(t, 2000) / 10)).started();
            below.runUntil(5500);
            int stop = below.firstAfter("stop", 1301);
            // Across zero: counts climb through 0 mid-leg and never stall: the full leg runs.
            Rig across = new Rig(tuning().hopTicks(20).build(), t -> new SensorReading(t, 300 + jitter(t), 100, 100,
                    null, false, -300 + t / 10, -250 + t / 10)).started();
            across.runUntil(6500);
            check(n, below.timeOf(stop) >= 2900 && below.timeOf(stop) <= 3100 && below.count("startle") == 1
                            && across.count("startle") == 0 && across.maxHopTicks == 20,
                    "stop@" + below.timeOf(stop) + " " + below.tail() + " / across " + across.tail());
        });
        // ---- a blocked turn backs up a little first, then tries again once (live: pinned after a CPL stop) ----
        scenario("blocked_turn_backs_up_a_little_then_the_retried_turn_succeeds", n -> {
            // Nothing logged to back out along: the first move is a turn, and it will not turn.
            Rig rig = new Rig(escTuning().turnChance(1.0).build(), CLEAR, NOTHING, true);
            rig.simWheels = true;
            rig.yaw.stuck = true;
            rig.unstickAfterBacks = 1;
            rig.started();
            rig.runUntil(15000);
            int turn = rig.first("turn", 0);
            List<Drive> backs = new ArrayList<Drive>();
            for (Drive d : rig.drives) {
                if (d.kind.equals("back")) {
                    backs.add(d);
                }
            }
            Drive back = backs.isEmpty() ? null : backs.get(0);
            int retry = back == null || back.end < 0 ? -1 : rig.firstAfter("turn", back.end);
            boolean turned = false;
            for (double r : rig.yaw.turnResults) {
                turned |= Math.abs(r) >= 15;
            }
            Drive hop = retry < 0 ? null : firstDrive(rig, "HOP", "hop", rig.timeOf(retry));
            boolean escaped = false;
            for (ExploreBrain.State st : rig.statesSeen) {
                escaped |= ESCAPING.contains(st);
            }
            check(n, turn >= 0 && back != null && back.state.equals("BACK_OFF") && backs.size() == 1
                            && back.t - rig.timeOf(turn) >= 1400 && back.counts < 0
                            && back.end - back.t <= rig.tuning.blockedTurnBackTicks * 250 + 100 && retry > 0 && turned
                            && hop != null && !escaped
                            && rig.violations.isEmpty(),
                    "backs=" + backs + " results=" + rig.yaw.turnResults + " " + rig.tail());
        });
        scenario("blocked_turn_after_a_cpl_stop_still_backs_up_a_little_before_the_retry", n -> {
            // The controller refuses forward mid-leg (CPL): startle, the usual back-off, then the
            // escape turn will not turn; a short back-up (the second back move) frees it. The
            // one retry of a CPL on plain floor (owner-approved 2026-09-25) is off here: this is
            // about the CPL that is a hazard (cpl_* scenarios cover the retry).
            Rig rig = new Rig(escTuning().cplRetry(-1, 0).build(), t -> t >= 1600 && t < 1800 ? cpl2(t) : clear(t),
                    NOTHING, true);
            rig.simWheels = true;
            rig.yaw.stuck = true;
            rig.unstickAfterBacks = 2;
            rig.started();
            rig.runUntil(15000);
            List<Drive> backs = new ArrayList<Drive>();
            for (Drive d : rig.drives) {
                if (d.kind.equals("back")) {
                    backs.add(d);
                }
            }
            Drive second = backs.size() < 2 ? null : backs.get(1);
            int blocked = second == null ? -1 : rig.firstAfter("turn", backs.get(0).end);
            int retry = second == null || second.end < 0 ? -1 : rig.firstAfter("turn", second.end);
            boolean turned = false;
            for (double r : rig.yaw.turnResults) {
                turned |= Math.abs(r) >= 30;
            }
            boolean escaped = false;
            for (ExploreBrain.State st : rig.statesSeen) {
                escaped |= ESCAPING.contains(st);
            }
            check(n, rig.count("startle") == 1 && second != null && blocked > 0
                            && second.t - rig.timeOf(blocked) >= 1400 && second.state.equals("BACK_OFF")
                            && retry > 0 && turned && !escaped && rig.violations.isEmpty(),
                    "backs=" + backs + " results=" + rig.yaw.turnResults + " " + rig.tail());
        });
        scenario("blocked_turn_backs_up_at_most_once_per_turn_and_stops_on_a_stall", n -> {
            // Pinned: turns and reversing go nowhere. A long back-up (12 ticks, 3 s) stops on the
            // stall watch (~1 s here); the retried turn is still blocked, so he is wedged, and each
            // blocked turn of the escape's circle backs up once too.
            // Jam detection off: with it, the circle's first blocked turn would end the escape (jammed).
            // Recovery off too: the stalled back-up would wait for the board instead of retrying.
            Rig rig = new Rig(escTuning().turnChance(1.0).blockedTurnBackTicks(12).jamOff().stallRecoverOff().build(),
                    CLEAR, NOTHING,
                    true);
            rig.simWheels = true;
            rig.yaw.stuck = true;
            rig.backBlocked = true;
            rig.started();
            runUntil(rig, 60000, r -> entered(r, ExploreBrain.State.CIRCLE, 0) >= 0
                    && r.brain.state() != ExploreBrain.State.CIRCLE);
            long retrace = entered(rig, ExploreBrain.State.RETRACE, 0);
            long circle = entered(rig, ExploreBrain.State.CIRCLE, 0);
            List<Drive> roam = drivesIn(rig, "BACK_OFF", "back", 0, retrace < 0 ? Long.MAX_VALUE : retrace);
            List<Drive> inCircle = drivesIn(rig, "CIRCLE", "back", 0, Long.MAX_VALUE);
            boolean stalled = true;
            for (Drive d : roam) {
                stalled &= d.end > 0 && d.end - d.t >= 900 && d.end - d.t < 12 * 250 && Math.abs(d.counts) <= 2;
            }
            for (Drive d : inCircle) {
                stalled &= d.end > 0 && d.end - d.t >= 900 && d.end - d.t < 12 * 250 && Math.abs(d.counts) <= 2;
            }
            check(n, retrace > 0 && circle > retrace && roam.size() == 1 && inCircle.size() == 1 && stalled
                            && rig.violations.isEmpty(),
                    "roam=" + roam + " circle=" + inCircle + " " + rig.tail());
        });
    }

    // ---- open doorways through Claude (explore nav plan U6, AE2, AE3, AE7) ----
    //
    // Continuous roaming with the gyro: the camera opens at 300, looks arrive every
    // 500 ms from 1000, the first pause ends at 1300 and legs are 8 ticks (2 s). The
    // first doorway ask goes with the first fresh look (~1000) and answers 1 s later.

    private static ExploreTuning.Builder doorTuning() {
        return navTuning().gyro(robotGyro());
    }

    private static Rig doorRig(ExploreTuning.Builder b, Feed feed, OpenView view, DoorwayScript script) {
        Rig rig = new Rig(b.build(), feed, NOTHING, true, (r, req, nth) -> CuriosityPort.Answer.nothing());
        rig.openView = view;
        rig.doorways = script;
        rig.simWheels = true;
        return rig;
    }

    private static final OpenView ALL_OPEN = (r, t) -> prof(0.9f, 0.9f, 0.9f, 0.9f);

    /** When the brain first noted something containing part (notes are "t message"), else -1. */
    private static long notedAt(List<String> notes, String part) {
        for (String x : notes) {
            if (x.contains(part)) {
                return Long.parseLong(x.substring(0, x.indexOf(' ')));
            }
        }
        return -1;
    }

    /** When the first traced note starting with prefix came, at or after from; -1 if none. */
    private static long noteAt(List<String> notes, String prefix, long from) {
        for (String x : notes) {
            int sp = x.indexOf(' ');
            long t = Long.parseLong(x.substring(0, sp));
            if (t >= from && x.startsWith(prefix, sp + 1)) {
                return t;
            }
        }
        return -1;
    }

    /** How many traced notes start with prefix. */
    private static int notesStarting(List<String> notes, String prefix) {
        int c = 0;
        for (String x : notes) {
            if (x.startsWith(prefix, x.indexOf(' ') + 1)) {
                c++;
            }
        }
        return c;
    }

    /** The time of the first traced note containing what, or -1. */
    private static long noteAt(List<String> notes, String what) {
        for (String line : notes) {
            if (line.contains(what)) {
                return Long.parseLong(line.substring(0, line.indexOf(' ')));
            }
        }
        return -1;
    }

    /** The time of the kth (1-based) listen at or after from, or -1. */
    private static long nthListenAt(Rig rig, long from, int k) {
        long t = from;
        long at = -1;
        for (int i = 0; i < k; i++) {
            int j = rig.firstAfter("listen", t);
            if (j < 0) {
                return -1;
            }
            at = rig.timeOf(j);
            t = at + 1;
        }
        return at;
    }

    private static List<String> traced(Rig rig) {
        List<String> notes = new ArrayList<String>();
        rig.brain.setTrace(x -> notes.add(rig.now + " " + x));
        return notes;
    }

    /** Only roaming states, never a stop, a meeting or an escape. */
    private static boolean roamingOnly(List<String> states) {
        for (String st : states) {
            if (!st.equals("PAUSE") && !st.equals("HOP") && !st.equals("LOOK") && !st.equals("TURN")
                    && !st.equals("STARTLE") && !st.equals("BACK_OFF")) {
                return false;
            }
        }
        return true;
    }

    // ---- seeking the unfamiliar (owner 2026-10-01: "find something that's unfamiliar and drive towards it") ----
    //
    // Continuous roaming with the gyro, curiosity stops every 15 s (three looks 40 deg
    // apart, Claude finds nothing), prints kept every 0.5 s and counting after 5 s, so a
    // room whose every view is the same reads familiar from the first stop.

    private static ExploreTuning.Builder seekTuning() {
        // A stop every 15 s plus doorway asks would spend the look budget; these pin the seek itself.
        return navTuning().gyro(robotGyro())
                .claudeLooksPerMinute(0)
                .curiosityMs(15000, 15000)
                .scan(3, 500).scanTurnDeg(40)
                .ask(1, 4000)
                .placeMemory(1800000, 300, 500, 5000)
                .seekTrigger(2, 0.3, 2, 60000)
                .seekDrive(4, 100000, 20, 0.7, 0.6f)
                .seekAskTimeoutMs(5000);
    }

    private static Rig seekRig(ExploreTuning.Builder b, PlaceView place, SeekScript seeks) {
        Rig rig = new Rig(b.build(), CLEAR, NOTHING, true, (r, req, nth) -> CuriosityPort.Answer.nothing());
        rig.openView = ALL_OPEN;
        rig.placeView = place;
        rig.seeks = seeks;
        rig.simWheels = true;
        return rig;
    }

    /** The exact bearing (left positive) of a frame position x (-1 left .. 1 right): 31.3 deg half view, 18 deg up. */
    private static double exactBearing(double x) {
        return -Math.toDegrees(Math.atan2(x * Math.tan(Math.toRadians(31.3)), Math.cos(Math.toRadians(18))));
    }

    /** scene(1) with its first k colour blocks changed: a view a little unlike the familiar one. */
    static PlaceMemory.Print blend(int k) {
        return blend(k, 0);
    }

    /** As above, the changed blocks' colours drawn from seed: blends of different seeds differ as much. */
    static PlaceMemory.Print blend(int k, long seed) {
        java.util.Random r = new java.util.Random(1);
        java.util.Random o = new java.util.Random(99 + seed);
        int[] cells = new int[16 * 12];
        for (int i = 0; i < cells.length; i++) {
            cells[i] = (r.nextInt(256) << 16) | (r.nextInt(256) << 8) | r.nextInt(256);
            if (i < k) {
                cells[i] = (o.nextInt(256) << 16) | (o.nextInt(256) << 8) | o.nextInt(256);
            }
        }
        int[] rgb = new int[80 * 60];
        for (int y = 0; y < 60; y++) {
            for (int x = 0; x < 80; x++) {
                rgb[y * 80 + x] = cells[(y / 5) * 16 + x / 5];
            }
        }
        return PlaceMemory.Print.of(rgb, 80, 60);
    }

    /** The first traced note starting with prefix, or null. */
    private static String firstNote(List<String> notes, String prefix) {
        for (String x : notes) {
            if (x.startsWith(prefix, x.indexOf(' ') + 1)) {
                return x;
            }
        }
        return null;
    }

    /** The number after "re-centred by " in a note. */
    private static double recentredBy(String note) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("re-centred by (-?\\d+) deg").matcher(note);
        return m.find() ? Double.parseDouble(m.group(1)) : Double.NaN;
    }

    // ---- seeking somewhere new by time and area (owner 2026-10-01: "if he's been somewhere in the
    // last 30 minutes, he should try and find somewhere else to go") ----
    //
    // Live, 30+ minutes gave 9 curiosity stops and no seek: Claude had a fresh remark at almost
    // every stop, and the place memory read most views as new. These rigs are that room: a
    // remark at every stop, and every view new to the place memory, so only time or the
    // ground he has covered can start a seek.

    private static Rig remarkRig(ExploreTuning.Builder b, SeekScript seeks) {
        Rig rig = new Rig(b.build(), CLEAR, NOTHING, true, (r, req, nth) -> pick(1, "plant " + nth,
                CuriosityPort.Kind.OTHER, "Remark number " + nth + ", what a thing!", 0.5f, 0.5f, 0.2f, 0.3f));
        rig.openView = ALL_OPEN;
        rig.placeView = (r, t) -> scene(100000 + t);
        rig.seeks = seeks;
        rig.simWheels = true;
        return rig;
    }

    /** The time of the last event starting with prefix before t, else -1. */
    private static long lastEventBefore(Rig rig, String prefix, long t) {
        long at = -1;
        for (Event e : rig.log) {
            if (e.t < t && e.what.startsWith(prefix)) {
                at = e.t;
            }
        }
        return at;
    }

    private static void timedSeekScenarios() {
        scenario("seek_tuning_defaults_every_five_minutes_or_a_small_area_over_three", n -> {
            ExploreTuning t = new ExploreTuning.Builder().build();
            ExploreTuning off = new ExploreTuning.Builder().seekOff().build();
            check(n, t.seekEveryMs == 300000 && t.seekAreaWindowMs == 180000 && t.seekAreaSpanM == 1.5
                            && t.seekAreaCells == 3 && off.seekEveryMs == 0 && off.seekAreaWindowMs == 0
                            && off.seekFamiliarScans == 0,
                    "every=" + t.seekEveryMs + " window=" + t.seekAreaWindowMs + " span=" + t.seekAreaSpanM
                            + " cells=" + t.seekAreaCells);
        });
        scenario("seek_claude_always_has_a_remark_still_seeks_within_five_minutes", n -> {
            Rig rig = remarkRig(seekTuning(), (r, req, k) -> CuriosityPort.WayOut.way(0, 0f));
            List<String> notes = traced(rig);
            rig.started();
            runUntil(rig, 420000, r -> notedAt(notes, "seeking: Claude picked") >= 0);
            long why = notedAt(notes, "seeking: no seek for 5 min");
            long asked = rig.timeOf(rig.firstAfter("seek ", 0));
            int said = rig.countPrefix("say Remark number", 0, why < 0 ? rig.now : why);
            // The stop's remark is said first, then its frames go to the seek ask.
            long saidAt = lastEventBefore(rig, "say Remark number", asked < 0 ? rig.now : asked);
            CuriosityPort.SeekRequest req = rig.seekRequests.isEmpty() ? null : rig.seekRequests.get(0);
            check(n, why >= 300000 && why <= 360000 && said >= 10 && asked >= why && saidAt >= 0
                            && asked - saidAt <= 15000 && req != null && req.frames.size() == 3
                            && notedAt(notes, "seeking: surroundings familiar") < 0
                            && notedAt(notes, "seeking: stayed within a small area") < 0
                            && rig.violations.isEmpty(),
                    "why=" + why + " asked=" + asked + " saidAt=" + saidAt + " said=" + said + " "
                            + notes.subList(Math.max(0, notes.size() - 8), notes.size()));
        });
        scenario("seek_circling_in_a_one_metre_area_seeks_early", n -> {
            // Wheel counts read as a fiftieth of the distance: all his roaming stays within about a metre.
            Rig rig = remarkRig(seekTuning().coverageGrid(150000, 0.5, 1800000, 1.5),
                    (r, req, k) -> CuriosityPort.WayOut.way(0, 0f));
            List<String> notes = traced(rig);
            rig.started();
            runUntil(rig, 420000, r -> notedAt(notes, "seeking: Claude picked") >= 0);
            String why = firstNote(notes, "seeking: stayed within a small area (");
            long at = notedAt(notes, "seeking: stayed within a small area (");
            check(n, why != null && why.matches(".* \\(\\d+ cells in 3 min\\)") && at >= 180000 && at < 300000
                            && notedAt(notes, "seeking: no seek for") < 0 && notedAt(notes, "seeking: Claude picked") > at
                            && rig.violations.isEmpty(),
                    "why=" + why + " at=" + at + " " + notes.subList(Math.max(0, notes.size() - 8), notes.size()));
        });
        scenario("seek_just_sought_waits_seek_gap_before_seeking_again", n -> {
            Rig rig = remarkRig(seekTuning().seekTrigger(2, 0.3, 2, 120000).seekEvery(20000).seekArea(0, 1.5, 3),
                    (r, req, k) -> CuriosityPort.WayOut.way(0, 0f));
            List<String> notes = traced(rig);
            rig.started();
            rig.runUntil(600000);
            List<Long> starts = notedTimes(notes, "seeking: no seek for");
            List<Long> ends = new ArrayList<Long>(notedTimes(notes, "seeking: gave up"));
            ends.addAll(notedTimes(notes, "seeking: arrived"));
            java.util.Collections.sort(ends);
            boolean floor = starts.size() >= 3 && starts.get(0) >= 20000;
            for (int i = 1; floor && i < starts.size(); i++) {
                long prevEnd = -1;
                for (long e : ends) {
                    if (e < starts.get(i)) {
                        prevEnd = e;
                    }
                }
                floor = prevEnd >= starts.get(i - 1) && starts.get(i) - prevEnd >= 120000
                        && starts.get(i) - prevEnd <= 160000;
            }
            check(n, floor && rig.violations.isEmpty(), "starts=" + starts + " ends=" + ends);
        });
        scenario("seek_a_call_during_a_timed_seek_is_answered", n -> {
            Rig rig = remarkRig(seekTuning().seekEvery(60000).cueTurn(45, 90, 500, 2000, 600),
                    (r, req, k) -> CuriosityPort.WayOut.way(0, 0f));
            List<String> notes = traced(rig);
            rig.started();
            runUntil(rig, 300000, r -> notedAt(notes, "seeking: Claude picked") >= 0);
            long picked = rig.now;
            runUntil(rig, picked + 30000, r -> r.firstAfter("hop", picked) >= 0);
            long cueT = rig.now + 200;
            rig.cue(cueT, Ears.Kind.WAKE_WORD, Ears.Side.RIGHT, 70f);
            rig.runUntil(cueT + 4000);
            String gaveUp = firstNote(notes, "seeking: gave up");
            long answered = answerAt(rig, cueT);
            check(n, notedAt(notes, "seeking: no seek for 1 min") >= 0 && answered >= cueT && answered <= cueT + 500
                            && entered(rig, ExploreBrain.State.CUE_TURN, cueT) >= cueT
                            && gaveUp != null && gaveUp.contains("a call") && !rig.brain.seeking() && rig.violations.isEmpty(),
                    "answered=" + answered + " gaveUp=" + gaveUp + " " + rig.tail());
        });
    }

    private static void seekScenarios() {
        scenario("seek_bearing_is_exact_at_the_centre_the_edge_and_a_quarter_of_the_width", n -> {
            ExploreTuning t = new ExploreTuning.Builder().build();
            double c = RoamSteer.bearingOf(0, t.cameraHalfFovDeg, t.cameraPitchDeg);
            double right = RoamSteer.bearingOf(1, t.cameraHalfFovDeg, t.cameraPitchDeg);
            double left = RoamSteer.bearingOf(-1, t.cameraHalfFovDeg, t.cameraPitchDeg);
            // Pixel 160 of 640 (a quarter of the width) is x -0.5; pixel 480 is x 0.5. The linear
            // x * halfFov is "within about 2 deg" (2.1 at a quarter of the width).
            double quarter = RoamSteer.bearingOf(-0.5, t.cameraHalfFovDeg, t.cameraPitchDeg);
            double threeQ = RoamSteer.bearingOf(0.5, t.cameraHalfFovDeg, t.cameraPitchDeg);
            check(n, t.cameraHalfFovDeg == 31.3 && t.cameraPitchDeg == 18 && Math.abs(c) < 1e-9
                            && Math.abs(right - (-32.59)) < 0.05 && Math.abs(left - 32.59) < 0.05
                            && Math.abs(quarter - 17.73) < 0.05 && Math.abs(threeQ + 17.73) < 0.05
                            && Math.abs(quarter - exactBearing(-0.5)) < 1e-9
                            && Math.abs(quarter - 0.5 * 31.3) <= 2.2 && Math.abs(right + 31.3) <= 2.2,
                    "centre=" + c + " right=" + right + " left=" + left + " quarter=" + quarter + " 3q=" + threeQ);
        });
        scenario("seek_tuning_defaults_familiar_two_scans_every_three_minutes", n -> {
            ExploreTuning t = new ExploreTuning.Builder().build();
            check(n, t.seekFamiliarScans == 2 && t.seekFamiliarNovelty == 0.3 && t.seekMinLooks == 2
                            && t.seekGapMs == 180000 && t.seekAskTimeoutMs == 15000 && t.seekMaxLegs == 6
                            && t.seekMaxCounts == 9000 && t.seekRecentreDeg == 20 && t.seekArriveNovelty == 0.7
                            && t.seekWeight == 0.6f && new ExploreTuning.Builder().seekOff().build().seekFamiliarScans == 0,
                    "scans=" + t.seekFamiliarScans + " novelty=" + t.seekFamiliarNovelty + " gap=" + t.seekGapMs);
        });
        scenario("seek_familiar_room_triggers_and_claudes_frame_and_x_give_the_heading", n -> {
            Rig rig = seekRig(seekTuning(), (r, t) -> scene(1), (r, req, k) -> CuriosityPort.WayOut.way(1, 0.5f));
            List<String> notes = traced(rig);
            rig.started();
            runUntil(rig, 300000, r -> !Double.isNaN(r.brain.seekHeading()));
            double heading = rig.brain.seekHeading();
            CuriosityPort.SeekRequest req = rig.seekRequests.isEmpty() ? null : rig.seekRequests.get(0);
            Double at = req == null ? null : rig.lookHeadings.get(captured(req.frames.get(1).frame));
            boolean familiarFrames = req != null && req.frames.size() == 3;
            for (int i = 0; familiarFrames && i < 3; i++) {
                CuriosityPort.SeekFrame f = req.frames.get(i);
                familiarFrames = f.novelty <= 0.3 && f.seenAgoMs >= 0 && !f.wentThere && f.labels.isEmpty();
            }
            boolean bearings = req != null && Math.abs(req.frames.get(0).bearingDeg) < 1
                    && Math.abs(Math.abs(req.frames.get(1).bearingDeg) - 40) < 8;
            String why = firstNote(notes, "seeking: surroundings familiar");
            String picked = firstNote(notes, "seeking: Claude picked frame 2 x 0.50: heading ");
            long pickedAt = notedAt(notes, "seeking: Claude picked");
            // He turns to it (measured), then looks again before any leg.
            rig.runUntil(rig.now + 6000);
            int turn = rig.firstAfter("turn", pickedAt);
            int hop = rig.firstAfter("hop", pickedAt);
            double after = rig.yaw.wrapped();
            check(n, at != null && near(heading, Heading.wrap(at + exactBearing(0.5)), 3) && familiarFrames && bearings
                            && why != null && picked != null && picked.endsWith("(bearing -18)")
                            && turn >= 0 && hop > turn && near(after, heading, 8) && rig.violations.isEmpty(),
                    "at=" + at + " heading=" + f1(heading) + " after=" + f1(after) + " why=" + why + " picked=" + picked
                            + " frames=" + familiarFrames + " bearings=" + bearings + " " + rig.tail());
        });
        scenario("seek_legs_follow_the_heading_and_a_relook_recentres_it", n -> {
            double[] w = {Double.NaN};
            Rig rig = seekRig(seekTuning(), (r, t) -> scene(1), (r, req, k) -> CuriosityPort.WayOut.way(1, 0f));
            // Once he seeks: only the floor 10 deg left of the target reads fully open.
            rig.openView = (r, t) -> {
                double sh = r.brain.seekHeading();
                if (Double.isNaN(sh)) {
                    return Double.isNaN(w[0]) ? prof(0.9f, 0.9f, 0.9f, 0.9f) : prof(0.9f, 0.5f, 0.5f, 0.5f);
                }
                if (Double.isNaN(w[0])) {
                    w[0] = Heading.wrap(sh + 10);
                }
                float[] b = new float[Openness.BINS];
                for (int i = 0; i < b.length; i++) {
                    double offset = (i + 0.5) / b.length * 2 - 1;
                    double world = r.yaw.wrapped() + exactBearing(offset);
                    b[i] = Math.abs(Heading.delta(world, w[0])) <= 9 ? 0.95f : 0.5f;
                }
                return new Openness.Profile(b, 0.9f);
            };
            List<String> notes = traced(rig);
            rig.started();
            runUntil(rig, 400000, r -> notedAt(notes, "seeking: gave up") >= 0 || notedAt(notes, "seeking: arrived") >= 0);
            String recentred = firstNote(notes, "seeking: leg ");
            double by = recentred == null ? Double.NaN : recentredBy(recentred);
            long start = notedAt(notes, "seeking: Claude picked");
            long end = notedAt(notes, "seeking: gave up");
            String gaveUp = firstNote(notes, "seeking: gave up");
            // Every forward leg during the seek drives within 15 deg of the open floor he was sent to.
            int legs = 0;
            boolean onCourse = true;
            for (Drive d : rig.drives) {
                if (d.t >= start && d.t < end && "hop".equals(d.kind)) {
                    legs++;
                    onCourse &= near(d.heading, w[0], 15);
                }
            }
            check(n, start > 0 && end > start && Math.abs(by - 10) <= 5 && legs >= 3 && onCourse
                            && gaveUp != null && gaveUp.contains("4 legs") && notesStarting(notes, "seeking: leg ") >= 3
                            && rig.violations.isEmpty(),
                    "by=" + f1(by) + " legs=" + legs + " onCourse=" + onCourse + " w=" + f1(w[0]) + " gaveUp=" + gaveUp
                            + " drives=" + rig.drives + " " + notes.subList(Math.max(0, notes.size() - 12), notes.size()));
        });
        scenario("seek_without_claude_falls_back_to_the_least_familiar_frame", n -> {
            // A view somewhat unlike the familiar one (84 of its 192 blocks changed, a fresh set of
            // colours each stop: novelty about 0.05-0.7), counted familiar here (0.8 or less), but
            // always the least familiar of its stop.
            ExploreTuning.Builder b = seekTuning().seekTrigger(1, 0.8, 2, 60000);
            PlaceMemory pm = new PlaceMemory(b.build());
            int blendK = 84;
            List<Long> blendShots = new ArrayList<Long>();
            Rig rig = new Rig(b.build(), CLEAR, NOTHING, true);
            rig.openView = ALL_OPEN;
            rig.simWheels = true;
            List<String> notes = traced(rig);
            // Each scan's second look (after its first turn) shows a fresh blend; every other view is the same.
            rig.placeView = (r, t) -> {
                long scanAt = -1;
                for (String x : notes) {
                    if (x.contains("curiosity stop: scanning")) {
                        scanAt = Long.parseLong(x.substring(0, x.indexOf(' ')));
                    }
                }
                if (scanAt >= 0 && r.brain.state() == ExploreBrain.State.SCAN && r.countPrefix("turn", scanAt, t) == 1) {
                    blendShots.add(t);
                    return blend(blendK, scanAt);
                }
                return scene(1);
            };
            rig.started();
            runUntil(rig, 400000, r -> !Double.isNaN(r.brain.seekHeading()));
            double heading = rig.brain.seekHeading();
            Double at = blendShots.isEmpty() ? null : rig.lookHeadings.get(blendShots.get(blendShots.size() - 1));
            String fell = firstNote(notes, "seeking: no Claude: least familiar frame 2 ");
            double nov = pm.novelty(PlaceMemory.similarity(scene(1), blend(blendK)));
            check(n, at != null && near(heading, at, 3) && fell != null && rig.seekRequests.isEmpty()
                            && rig.violations.isEmpty(),
                    "k=" + blendK + " nov=" + f1(nov) + " at=" + at + " heading=" + f1(heading) + " fell=" + fell + " "
                            + notes.subList(Math.max(0, notes.size() - 10), notes.size()));
        });
        scenario("seek_a_blocked_leg_ends_the_seek_cleanly", n -> {
            // Robot 2026-10-01: a hazard toward Claude's pick no longer ends the seek at once; he
            // tries 45 deg off the target (by the hazard, not the camera) first, and a hazard on
            // that leg too ends it.
            long[] blockAt = {Long.MAX_VALUE};
            long[] block2At = {Long.MAX_VALUE};
            Rig[] h = new Rig[1];
            Rig rig = new Rig(seekTuning().build(), t -> (t >= blockAt[0] && t < blockAt[0] + 300)
                    || (t >= block2At[0] && t < block2At[0] + 300) ? obstacle(t) : clear(t),
                    NOTHING, true, (r, req, nth) -> CuriosityPort.Answer.nothing());
            h[0] = rig;
            rig.openView = ALL_OPEN;
            rig.placeView = (r, t) -> scene(1);
            rig.seeks = (r, req, k) -> CuriosityPort.WayOut.way(0, 0f);
            rig.simWheels = true;
            List<String> notes = traced(rig);
            rig.started();
            runUntil(rig, 300000, r -> notedAt(notes, "seeking: Claude picked") >= 0);
            long picked = rig.now;
            runUntil(rig, picked + 30000, r -> r.firstAfter("hop", picked) >= 0);
            blockAt[0] = rig.now + 300;
            runUntil(rig, blockAt[0] + 30000, r -> notedAt(notes, "seeking: blocked toward the target") >= 0);
            long detour = notedAt(notes, "seeking: blocked toward the target");
            runUntil(rig, rig.now + 30000, r -> r.firstAfter("hop", detour + 1) >= 0);
            block2At[0] = rig.now + 300;
            rig.runUntil(rig.now + 30000);
            String gaveUp = firstNote(notes, "seeking: gave up");
            long endAt = notedAt(notes, "seeking: gave up");
            check(n, detour >= blockAt[0] && detour < blockAt[0] + 300 && gaveUp != null && gaveUp.contains("blocked")
                            && endAt >= block2At[0] && endAt < block2At[0] + 300
                            && !rig.brain.seeking() && Double.isNaN(rig.brain.seekHeading())
                            && rig.firstAfter("hop", block2At[0] + 3000) >= 0 && notedAt(notes, "seeking: arrived") < 0
                            && rig.violations.isEmpty(),
                    "blockAt=" + blockAt[0] + " detour@" + detour + " block2At=" + block2At[0] + " gaveUp=" + gaveUp
                            + "@" + endAt + " notes=" + lastNotes(notes, 25));
        });
        scenario("seek_after_arrival_the_next_seek_chooses_a_different_place", n -> {
            // Three looks 120 deg apart: each faces its own third of the room, and each third
            // always looks the same, until a seek's first leg reaches somewhere new.
            List<String> holder = new ArrayList<String>();
            ExploreTuning.Builder b = seekTuning().scanTurnDeg(120).seekTrigger(1, 0.3, 2, 20000);
            // Claude goes back to where he went if it can: the brain must not.
            Rig rig = seekRig(b, null, (r, req, k) -> {
                for (int i = 0; i < req.frames.size(); i++) {
                    if (req.frames.get(i).wentThere) {
                        return CuriosityPort.WayOut.way(i, 0f);
                    }
                }
                return CuriosityPort.WayOut.way(0, 0f);
            });
            List<String> notes = traced(rig);
            rig.placeView = (r, t) -> {
                long pickedAt = notedAt(notes, "seeking: Claude picked");
                if (r.brain.seeking() && pickedAt >= 0 && r.countPrefix("hop", pickedAt, t) > 0
                        && notesStarting(notes, "seeking: arrived") == 0) {
                    return scene(5000 + t);
                }
                return scene(1 + (int) (Heading.wrap(r.yaw.wrapped()) / 120));
            };
            rig.started();
            runUntil(rig, 200000, r -> notesStarting(notes, "seeking: arrived") >= 1);
            boolean visited = rig.seekRequests.size() == 1;
            CuriosityPort.SeekRequest first = visited ? rig.seekRequests.get(0) : null;
            Double firstAt = first == null ? null : rig.lookHeadings.get(captured(first.frames.get(0).frame));
            runUntil(rig, rig.now + 200000, r -> r.seekRequests.size() >= 2 && !Double.isNaN(r.brain.seekHeading()));
            CuriosityPort.SeekRequest second = rig.seekRequests.size() >= 2 ? rig.seekRequests.get(1) : null;
            int went = 0;
            int wentFrame = -1;
            for (int i = 0; second != null && i < second.frames.size(); i++) {
                if (second.frames.get(i).wentThere) {
                    went++;
                    wentFrame = i;
                }
            }
            Double wentAt = wentFrame < 0 ? null : rig.lookHeadings.get(captured(second.frames.get(wentFrame).frame));
            double target = rig.brain.seekHeading();
            int third = (int) (Heading.wrap(target) / 120);
            boolean sameThirdAsWent = wentAt != null && third == (int) (Heading.wrap(wentAt) / 120);
            boolean sameThirdAsFirst = firstAt != null && third == (int) (Heading.wrap(firstAt) / 120);
            PlaceMemory.Print firstView = firstAt == null ? null : scene(1 + (int) (Heading.wrap(firstAt) / 120));
            boolean marked = firstView != null && rig.brain.places().soughtBefore(firstView, rig.now);
            rig.brain.shutdown();
            boolean forgotten = firstView != null && !rig.brain.places().soughtBefore(firstView, rig.now);
            check(n, visited && marked && forgotten && firstAt != null && second != null && went == 1 && wentAt != null
                            && (int) (Heading.wrap(wentAt) / 120) == (int) (Heading.wrap(firstAt) / 120)
                            && !Double.isNaN(target) && !sameThirdAsWent && !sameThirdAsFirst
                            && notedAt(notes, "seeking: arrived (the place looks new") >= 0
                            && notedAt(notes, "seeking: Claude picked where he went last time: least familiar frame") >= 0
                            && rig.brain.places().size() == 0 && rig.violations.isEmpty(),
                    "firstAt=" + firstAt + " went=" + went + " wentAt=" + wentAt + " target=" + f1(target) + " "
                            + notes.subList(Math.max(0, notes.size() - 12), notes.size()));
        });
        scenario("seek_never_in_a_room_that_looks_new", n -> {
            Rig rig = seekRig(seekTuning(), (r, t) -> scene(100000 + t), (r, req, k) -> CuriosityPort.WayOut.way(0, 0f));
            List<String> notes = traced(rig);
            rig.started();
            rig.runUntil(200000);
            check(n, notesStarting(notes, "curiosity stop") >= 6 && notesStarting(notes, "seeking:") == 0
                            && rig.seekRequests.isEmpty() && rig.violations.isEmpty(),
                    "stops=" + notesStarting(notes, "curiosity stop") + " seeks=" + notesStarting(notes, "seeking:"));
        });
        scenario("seek_a_person_box_mid_seek_is_passed_by_and_the_seek_reaches_its_target", n -> {
            // Robot 16:46:30: a roaming person pick ended a seek 0.7 s in ("interrupted:
            // approach"). While seeking, roaming person picks are ignored (noted once).
            List<String> notes = new ArrayList<String>();
            Rig[] h = new Rig[1];
            Rig rig = new Rig(seekTuning().build(), CLEAR,
                    (r, t) -> !Double.isNaN(r.brain.seekHeading()) ? list(box("person", 0.9f, 0.5f, 0.5f, 0.3f, 0.6f))
                            : list(),
                    true, (r, req, nth) -> CuriosityPort.Answer.nothing());
            h[0] = rig;
            rig.openView = ALL_OPEN;
            rig.seeks = (r, req, k) -> CuriosityPort.WayOut.way(0, 0f);
            rig.simWheels = true;
            rig.placeView = (r, t) -> {
                long picked = notedAt(notes, "seeking: Claude picked");
                return picked >= 0 && r.countPrefix("stop", firstHopT(r, picked), t) >= 3 ? scene(9) : scene(1);
            };
            rig.brain.setTrace(x -> notes.add(h[0].now + " " + x));
            rig.started();
            runUntil(rig, 400000, r -> notedAt(notes, "seeking: arrived") >= 0 || notedAt(notes, "seeking: gave up") >= 0);
            long picked = notedAt(notes, "seeking: Claude picked");
            long arrived = notedAt(notes, "seeking: arrived");
            int carried = notesWith(notes, "a person while seeking: carrying on to the target");
            long approach = picked < 0 ? -1 : entered(rig, ExploreBrain.State.APPROACH, picked);
            check(n, picked > 0 && arrived > picked && carried == 1 && (approach < 0 || approach > arrived)
                            && notedAt(notes, "a person while roaming") < 0
                            && notedAt(notes, "seeking: gave up") < 0 && rig.violations.isEmpty(),
                    "picked@" + picked + " arrived@" + arrived + " carried=" + carried + " approach@" + approach
                            + " notes=" + lastNotes(notes, 25));
        });
        scenario("seek_a_call_during_a_seek_is_answered", n -> {
            Rig rig = seekRig(seekTuning().cueTurn(45, 90, 500, 2000, 600), (r, t) -> scene(1),
                    (r, req, k) -> CuriosityPort.WayOut.way(0, 0f));
            List<String> notes = traced(rig);
            rig.started();
            runUntil(rig, 300000, r -> notedAt(notes, "seeking: Claude picked") >= 0);
            long picked = rig.now;
            runUntil(rig, picked + 30000, r -> r.firstAfter("hop", picked) >= 0);
            long cueT = rig.now + 200;
            rig.cue(cueT, Ears.Kind.WAKE_WORD, Ears.Side.RIGHT, 70f);
            rig.runUntil(cueT + 4000);
            String gaveUp = firstNote(notes, "seeking: gave up");
            long answered = answerAt(rig, cueT);
            check(n, answered >= cueT && answered <= cueT + 500 && entered(rig, ExploreBrain.State.CUE_TURN, cueT) >= cueT
                            && gaveUp != null && gaveUp.contains("a call") && !rig.brain.seeking() && rig.violations.isEmpty(),
                    "answered=" + answered + " gaveUp=" + gaveUp + " " + rig.tail());
        });
        timedSeekScenarios();
        scenario("replies_seek_reads_frame_and_x_or_none_and_the_prompt_carries_numbers_and_labels_only", n -> {
            int[] w = {640, 640, 640};
            java.util.Map<String, Object> json = new java.util.LinkedHashMap<String, Object>();
            json.put("unexplored", Boolean.TRUE);
            json.put("frame", 2L);
            json.put("x", 160L);
            CuriosityPort.WayOut ok = ClaudeReplies.seek(json, w);
            json.put("frame", 4L);
            CuriosityPort.WayOut fourth = ClaudeReplies.seek(json, w);
            json.put("unexplored", Boolean.FALSE);
            CuriosityPort.WayOut none = ClaudeReplies.seek(json, w);
            json.remove("unexplored");
            CuriosityPort.WayOut missing = ClaudeReplies.seek(json, w);
            CuriosityPort.SeekFrame f = new CuriosityPort.SeekFrame(new CuriosityPort.Frame(1, new byte[]{1}), -40, 0.1,
                    720000, true, java.util.Arrays.asList("chair", "desk"));
            String label = ExplorePrompts.seekFrame(f, 1);
            String ask = ExplorePrompts.seekAsk(3);
            check(n, ok.status == CuriosityPort.WayOut.Status.WAY && ok.frame == 1 && Math.abs(ok.x + 0.5f) < 0.01
                            && fourth.status == CuriosityPort.WayOut.Status.FAILED
                            && none.status == CuriosityPort.WayOut.Status.NONE
                            && missing.status == CuriosityPort.WayOut.Status.FAILED
                            && label.startsWith("Frame 2") && label.contains("40 deg right of Frame 1")
                            && label.contains("12 minutes ago") && label.contains("went there") && label.contains("chair, desk")
                            && ask.contains("1 to 3") && ask.contains("open doorway") && ask.contains("corridor"),
                    "ok=" + ok + " label=" + label + " ask=" + ask);
        });
    }

    private static void doorwayScenarios() {
        scenario("doorway_right_third_sets_a_heading_20_deg_right_and_legs_bend_that_way", n -> {
            Rig rig = doorRig(doorTuning(), CLEAR, ALL_OPEN, (r, k) -> CuriosityPort.Doorway.door(0.667f));
            List<String> notes = traced(rig);
            rig.started();
            rig.runUntil(2100);
            DoorAsk ask = rig.doorwayAsks.isEmpty() ? null : rig.doorwayAsks.get(0);
            Double at = ask == null ? null : rig.lookHeadings.get(captured(new CuriosityPort.Frame(0, ask.jpeg)));
            double door = rig.brain.doorwayHeading();
            rig.runUntil(8000);
            int turn = rig.firstAfter("turn", 2100);
            double r = rig.yaw.turnResults.isEmpty() ? 0 : rig.yaw.turnResults.get(0);
            check(n, ask != null && at != null && near(door, Heading.wrap(at - 20), 3)
                            && rig.what(turn).equals("turn RIGHT") && Math.abs(-r - 18.75) <= 6
                            && notedAt(notes, "open doorway") >= 0 && rig.violations.isEmpty(),
                    "ask=" + ask + " at=" + at + " door=" + f1(door) + " turn=" + f1(r) + " " + rig.tail());
        });
        scenario("doorway_none_leaves_the_steering_unchanged", n -> {
            Rig rig = doorRig(doorTuning(), CLEAR, ALL_OPEN, (r, k) -> CuriosityPort.Doorway.none());
            rig.started();
            rig.runUntil(12000);
            check(n, rig.doorwayAsks.size() == 1 && rig.doorwayAnswerStates.size() == 1
                            && Double.isNaN(rig.brain.doorwayHeading()) && rig.count("turn LEFT") == 0
                            && rig.count("turn RIGHT") == 0 && legs(rig).size() >= 3 && rig.violations.isEmpty(),
                    "asks=" + rig.doorwayAsks + " legs=" + legs(rig) + " " + rig.tail());
        });
        scenario("doorway_second_ask_waits_the_60_s_interval", n -> {
            Rig rig = doorRig(doorTuning(), CLEAR, ALL_OPEN, (r, k) -> CuriosityPort.Doorway.none());
            rig.started();
            rig.runUntil(130000);
            List<DoorAsk> asks = rig.doorwayAsks;
            boolean spaced = asks.size() == 3;
            for (int i = 1; spaced && i < asks.size(); i++) {
                long gap = asks.get(i).t - asks.get(i - 1).t;
                // The interval restarts when the answer comes (1 s after the ask).
                spaced = gap >= 61000 && gap <= 64000;
            }
            check(n, spaced && rig.violations.isEmpty(), "asks=" + asks);
        });
        scenario("doorway_never_asked_during_a_stop_an_approach_a_meeting_or_an_escape", n -> {
            // Asks due every 2 s: none goes out, or is answered, outside roaming.
            Rig meet = meetRig(claudeTuning().gyro(robotGyro()).doorwayAsk(2000, 15000));
            meet.people.match = (r, k) -> STRANGER;
            meet.people.listen = ListenScript.always(hearWords("i'm Sam"));
            meet.doorways = (r, k) -> CuriosityPort.Doorway.none();
            meet.doorwayDelayMs = 1500;
            meet.started();
            meet.runUntil(30000);
            // A plant Claude can't be asked about: the detector's pick is approached.
            Rig near = new Rig(curious().gyro(robotGyro()).doorwayAsk(2000, 15000).build(), CLEAR, PLANT, true,
                    (r, req, k) -> CuriosityPort.Answer.failed());
            near.doorways = (r, k) -> CuriosityPort.Doorway.none();
            near.doorwayDelayMs = 1500;
            near.started();
            near.runUntil(30000);
            Rig[] h = new Rig[1];
            Rig esc = escRig(escTuning().escapeRetrace(0, 10).doorwayAsk(2000, 15000), h, bumps(h, 3),
                    (r, req, k) -> CuriosityPort.WayOut.way(3, 0f));
            esc.doorways = (r, k) -> CuriosityPort.Doorway.none();
            esc.doorwayDelayMs = 1500;
            esc.started();
            runUntil(esc, 60000, r -> firstDrive(r, "DRIVE_OFF", "hop", 0) != null);
            esc.runUntil(esc.now + 5000);
            List<String> sent = new ArrayList<String>();
            for (DoorAsk a : meet.doorwayAsks) {
                sent.add(a.state);
            }
            for (DoorAsk a : esc.doorwayAsks) {
                sent.add(a.state);
            }
            for (DoorAsk a : near.doorwayAsks) {
                sent.add(a.state);
            }
            boolean onlyPauseOrHop = true;
            for (String st : sent) {
                onlyPauseOrHop &= st.equals("PAUSE") || st.equals("HOP");
            }
            check(n, meet.statesSeen.contains(ExploreBrain.State.MEET) && near.statesSeen.contains(ExploreBrain.State.APPROACH)
                            && near.doorwayAsks.size() >= 2 && roamingOnly(near.doorwayAnswerStates)
                            && near.violations.isEmpty()
                            && esc.statesSeen.contains(ExploreBrain.State.CIRCLE) && meet.doorwayAsks.size() >= 2
                            && esc.doorwayAsks.size() >= 1 && onlyPauseOrHop
                            && roamingOnly(meet.doorwayAnswerStates) && roamingOnly(esc.doorwayAnswerStates)
                            && meet.violations.isEmpty() && esc.violations.isEmpty(),
                    "sent=" + sent + " answered=" + meet.doorwayAnswerStates + esc.doorwayAnswerStates + " meet="
                            + meet.statesSeen + " esc=" + esc.statesSeen + " near=" + near.statesSeen + " "
                            + near.doorwayAsks.size() + " " + meet.violations + esc.violations);
        });
        scenario("doorway_ae7_offline_ask_fails_quietly_and_roaming_continues", n -> {
            // Set up but unreachable: every ask fails; and not set up at all: none is sent.
            Rig rig = doorRig(doorTuning(), CLEAR, ALL_OPEN, null);
            rig.started();
            rig.runUntil(70000);
            Rig none = new Rig(doorTuning().build(), CLEAR, NOTHING, true);
            none.openView = ALL_OPEN;
            none.started();
            none.runUntil(20000);
            check(n, rig.doorwayAsks.size() == 2 && rig.doorwayAsks.get(1).t - rig.doorwayAsks.get(0).t >= 61000
                            && Double.isNaN(rig.brain.doorwayHeading()) && rig.count("eyes THINKING") == 0
                            && rig.countPrefix("hop", 60000, 70000) > 0 && none.doorwayAsks.isEmpty()
                            && none.countPrefix("hop", 10000, 20000) > 0 && rig.violations.isEmpty(),
                    "asks=" + rig.doorwayAsks + " " + rig.tail());
        });
        scenario("doorway_ae3_floor_edge_at_the_doorway_stops_and_escapes_as_today", n -> {
            Rig[] h = new Rig[1];
            Rig rig = doorRig(doorTuning(), t -> {
                Rig r = h[0];
                boolean at = r != null && !Double.isNaN(r.brain.doorwayHeading())
                        && r.brain.state() == ExploreBrain.State.HOP && r.moving && r.count("startle") == 0
                        && t - r.legStartT >= 500;
                return at ? edgeAhead(t) : clear(t);
            }, ALL_OPEN, (r, k) -> CuriosityPort.Doorway.door(0f));
            h[0] = rig;
            rig.started();
            rig.runUntil(12000);
            int startle = rig.first("startle", 0);
            long t = rig.timeOf(startle);
            int stop = rig.firstAfter("stop", t - 1);
            int back = rig.firstAfter("back", t);
            int turn = rig.firstAfter("turn", t);
            check(n, startle >= 0 && rig.timeOf(stop) == t && rig.floorClearFalseAt >= t && back > startle
                            && turn > back && rig.violations.isEmpty(),
                    "startle@" + t + " " + rig.tail());
        });
        scenario("doorway_heading_expires_by_time_or_distance_and_steering_returns_to_openness", n -> {
            // Middling everywhere (never open enough to go through): one bend toward the
            // doorway, then straight legs; after it expires, no bend toward where it was.
            OpenView middling = (r, t) -> prof(0.9f, 0.5f, 0.5f, 0.5f);
            Rig timed = doorRig(doorTuning().doorwayExpire(8000, 1000000), CLEAR, middling,
                    (r, k) -> CuriosityPort.Doorway.door(0.667f));
            List<String> tNotes = traced(timed);
            timed.started();
            timed.runUntil(20000);
            Rig driven = doorRig(doorTuning().doorwayExpire(1000000, 200), CLEAR, middling,
                    (r, k) -> CuriosityPort.Doorway.door(0.667f));
            List<String> dNotes = traced(driven);
            driven.started();
            driven.runUntil(20000);
            long te = notedAt(tNotes, "doorway heading expired");
            long de = notedAt(dNotes, "doorway heading expired");
            check(n, te >= 10000 && te <= 10600 && Double.isNaN(timed.brain.doorwayHeading())
                            && timed.countPrefix("turn", 0, te) == 1 && timed.countPrefix("turn", te, 20001) == 0
                            && de > 2000 && de < 12000 && Double.isNaN(driven.brain.doorwayHeading())
                            && driven.countPrefix("turn", de, 20001) == 0
                            && timed.violations.isEmpty() && driven.violations.isEmpty(),
                    "timed@" + te + " driven@" + de + " " + tNotes + " " + timed.tail());
        });
        scenario("doorway_reading_blocked_when_faced_takes_a_short_leg_toward_it_and_an_obstacle_there_drops_it", n -> {
            // Robot 2026-10-01: openness read open hallway carpet 0.00. Answered straight ahead; by
            // the next decision the band there reads blocked: he trusts Claude with a short leg
            // toward it (not a turn away). An obstacle on that leg is real: the doorway is dropped.
            List<String> notes = new ArrayList<String>();
            long[] obstAt = {Long.MAX_VALUE};
            Rig[] h = new Rig[1];
            Rig rig = doorRig(doorTuning(), t -> {
                        if (obstAt[0] == Long.MAX_VALUE && h[0] != null) {
                            long tr = notedAt(notes, "reads blocked");
                            if (tr >= 0 && h[0].firstAfter("hop", tr) >= 0) {
                                obstAt[0] = t + 200;
                            }
                        }
                        return t >= obstAt[0] && t < obstAt[0] + 300 ? obstacle(t) : clear(t);
                    },
                    (r, t) -> r.doorwayAnswerStates.isEmpty() ? prof(0.9f, 0.9f, 0.9f, 0.9f)
                            : prof(0.9f, 0.9f, 0.1f, 0.9f),
                    (r, k) -> k == 1 ? CuriosityPort.Doorway.door(0f) : CuriosityPort.Doorway.none());
            h[0] = rig;
            rig.brain.setTrace(x -> notes.add(h[0].now + " " + x));
            rig.started();
            rig.runUntil(12000);
            long set = notedAt(notes, "open doorway");
            String trusted = firstNote(notes, "the doorway at 0 deg reads blocked (open ");
            long trustAt = notedAt(notes, "reads blocked");
            Drive hop = trustAt < 0 ? null : firstHop(rig, trustAt);
            long dropped = notedAt(notes, "a hazard on the leg toward it: forgotten");
            check(n, set > 0 && trustAt > set && trustAt <= set + 3500 && trusted != null && trusted.contains("open 0.10")
                            && trusted.contains("a short leg toward it") && hop != null && hop.end > 0
                            && hop.end - hop.t <= rig.tuning.steerShortTicks * rig.tuning.hopTickMs + 100
                            && dropped >= obstAt[0] && dropped < obstAt[0] + 300 && Double.isNaN(rig.brain.doorwayHeading())
                            && notedAt(notes, "expired") < 0 && rig.violations.isEmpty(),
                    "set@" + set + " trusted=" + trusted + " hop=" + hop + " obst@" + obstAt[0] + " dropped@" + dropped
                            + " notes=" + lastNotes(notes, 25));
        });
        scenario("doorway_passed_through_after_a_leg_toward_it_is_forgotten", n -> {
            Rig rig = doorRig(doorTuning(), CLEAR, ALL_OPEN, (r, k) -> CuriosityPort.Doorway.door(0f));
            List<String> notes = traced(rig);
            rig.started();
            rig.runUntil(9000);
            long set = notedAt(notes, "open doorway");
            long through = notedAt(notes, "through the doorway");
            // Set during the first leg (1300-3300); the next leg (4300-6300) goes through.
            check(n, set > 0 && through >= 6300 && through <= 6400 && Double.isNaN(rig.brain.doorwayHeading())
                            && rig.violations.isEmpty(),
                    "set@" + set + " through@" + through + " " + notes);
        });
        scenario("doorway_ask_carries_one_roaming_frame_and_notes_carry_numbers_only", n -> {
            Rig rig = doorRig(doorTuning(), CLEAR, ALL_OPEN, (r, k) -> CuriosityPort.Doorway.door(0.667f));
            List<String> notes = traced(rig);
            rig.started();
            rig.runUntil(8000);
            DoorAsk ask = rig.doorwayAsks.isEmpty() ? null : rig.doorwayAsks.get(0);
            long cap = ask == null ? -1 : captured(new CuriosityPort.Frame(0, ask.jpeg));
            boolean numbersOnly = true;
            for (String x : notes) {
                numbersOnly &= !x.contains("jpeg") && !x.contains("bins") && !x.contains("confidence");
            }
            check(n, ask != null && cap >= rig.openedAt && rig.lookHeadings.containsKey(cap) && ask.t - cap <= 3000
                            && ask.timeoutMs == rig.tuning.doorwayAskTimeoutMs && numbersOnly
                            && notedAt(notes, "asking Claude for an open doorway") >= 0 && rig.violations.isEmpty(),
                    "ask=" + ask + " cap=" + cap + " notes=" + notes);
        });
        scenario("roam_steer_doorway_weights_open_bands_and_turns_to_face_one_out_of_view", n -> {
            ExploreTuning doorT = doorTuning().build();
            RoamSteer steer = new RoamSteer(doorT);
            Openness.Profile open = prof(0.9f, 0.9f, 0.9f, 0.9f);
            RoamSteer.Plan plain = steer.plan(open);
            RoamSteer.Plan right = steer.plan(open, -20);
            RoamSteer.Plan behind = steer.plan(open, 90);
            // The doorway's band reads blocked: the steer never bends into it.
            Openness.Profile closed = prof(0.9f, 0.9f, 0.9f, 0.1f);
            RoamSteer.Plan intoClosed = steer.plan(closed, -20);
            boolean blockedFaced = steer.doorwayReadsBlocked(prof(0.9f, 0.9f, 0.1f, 0.9f), 5);
            boolean openFaced = steer.doorwayReadsBlocked(open, 5);
            boolean notFaced = steer.doorwayReadsBlocked(prof(0.9f, 0.9f, 0.1f, 0.9f), 25);
            check(n, plain.side == RoamSteer.STRAIGHT && !plain.towardDoorway
                            && right.side == RoamSteer.RIGHT && Math.abs(right.bendDeg - 0.625 * doorT.cameraHalfFovDeg) < 0.01 && right.towardDoorway
                            && !right.turnOnly && behind.side == RoamSteer.LEFT && behind.turnOnly
                            && Math.abs(behind.bendDeg - 90) < 0.01
                            && intoClosed.side != RoamSteer.RIGHT && !intoClosed.towardDoorway
                            && blockedFaced && !openFaced && !notFaced,
                    "plain=" + plain + " right=" + right + " behind=" + behind + " intoClosed=" + intoClosed);
        });
        scenario("replies_doorway_reads_x_and_rejects_bad_answers", n -> {
            java.util.Map<String, Object> json = new java.util.LinkedHashMap<String, Object>();
            json.put("open_doorway", Boolean.TRUE);
            json.put("x", 533L);
            CuriosityPort.Doorway ok = ClaudeReplies.doorway(json, 640);
            json.put("x", 0.5);
            CuriosityPort.Doorway fraction = ClaudeReplies.doorway(json, 640);
            json.put("x", 700L);
            CuriosityPort.Doorway wide = ClaudeReplies.doorway(json, 640);
            json.put("x", -3L);
            CuriosityPort.Doorway negative = ClaudeReplies.doorway(json, 640);
            json.put("x", "533");
            CuriosityPort.Doorway text = ClaudeReplies.doorway(json, 640);
            json.remove("x");
            CuriosityPort.Doorway noX = ClaudeReplies.doorway(json, 640);
            json.put("open_doorway", Boolean.FALSE);
            CuriosityPort.Doorway none = ClaudeReplies.doorway(json, 640);
            json.put("open_doorway", "yes");
            CuriosityPort.Doorway odd = ClaudeReplies.doorway(json, 640);
            check(n, ok.status == CuriosityPort.Doorway.Status.DOOR && Math.abs(ok.x - 0.666f) < 0.01f
                            && fraction.status == CuriosityPort.Doorway.Status.DOOR && Math.abs(fraction.x) < 0.01f
                            && wide.status == CuriosityPort.Doorway.Status.FAILED
                            && negative.status == CuriosityPort.Doorway.Status.FAILED
                            && text.status == CuriosityPort.Doorway.Status.FAILED
                            && noX.status == CuriosityPort.Doorway.Status.FAILED
                            && none.status == CuriosityPort.Doorway.Status.NONE
                            && odd.status == CuriosityPort.Doorway.Status.FAILED,
                    ok + " " + fraction + " " + wide + " " + negative + " " + text + " " + noX + " " + none + " " + odd);
        });
    }
    // ---- people while roaming, with a per-person leave-alone (explore nav plan U7, R9, R10, AE4) ----
    //
    // Roaming with Claude set up and no curiosity stops, so only a person brings him
    // to a stop. A person box is straight ahead and grows 0.15 of the frame's height
    // per approach leg from 0.3: the polite distance (0.6) is reached after 2 legs,
    // short of filling the frame (0.7). A meeting ends about 10 s in.

    private static ExploreTuning.Builder peopleTuning() {
        return claudeTuning().curiosityMs(100000000L, 100000000L);
    }

    /** Approach legs driven since he last went from roaming to facing or approaching someone. */
    private static int approachLegs(Rig rig) {
        long from = -1;
        String prev = "";
        for (Event e : rig.stateLog) {
            boolean going = e.what.equals("FACE") || e.what.equals("APPROACH");
            if (going && !prev.equals("FACE") && !prev.equals("APPROACH")) {
                from = e.t;
            }
            prev = e.what;
        }
        if (from < 0) {
            return 0;
        }
        return drivesIn(rig, "APPROACH", "hop", from, Long.MAX_VALUE).size();
    }

    /** A person straight ahead whenever visible(t), closer after each approach leg. */
    private static Vision personWhen(java.util.function.LongPredicate visible) {
        return (rig, t) -> visible.test(t)
                ? list(box("person", 0.9f, 0.5f, 0.5f, 0.3f, Math.min(1f, 0.3f + 0.15f * approachLegs(rig))))
                : list();
    }

    private static Rig peopleRig(ExploreTuning.Builder b, Vision v) {
        Rig rig = new Rig(b.build(), CLEAR, v, true, (r, req, nth) -> CuriosityPort.Answer.nothing());
        rig.people.match = (r, k) -> CuriosityPort.MatchAnswer.known("Sarah", "Hi {name}!", "Hello again!");
        return rig;
    }

    private static List<MetCheck> checksIn(Rig rig, long from, long to) {
        List<MetCheck> out = new ArrayList<MetCheck>();
        for (MetCheck c : rig.metCheckLog) {
            if (c.t >= from && c.t < to) {
                out.add(c);
            }
        }
        return out;
    }

    private static String ascii(byte[] b) {
        return b == null ? "null" : new String(b, java.nio.charset.StandardCharsets.US_ASCII);
    }

    /** When the brain entered any of these states at or after from (the state log), else -1. */
    private static long enteredAny(Rig rig, long from, ExploreBrain.State... states) {
        long first = -1;
        for (ExploreBrain.State s : states) {
            long t = entered(rig, s, from);
            if (t >= 0 && (first < 0 || t < first)) {
                first = t;
            }
        }
        return first;
    }

    private static void peopleScenarios() {
        scenario("people_roaming_person_is_approached_to_the_polite_distance_and_greeted_by_name", n -> {
            Rig rig = peopleRig(peopleTuning(), personWhen(t -> true));
            rig.started();
            rig.runUntil(15000);
            int match = rig.first("match", 0);
            int say = rig.first("say ", match);
            long matchT = rig.timeOf(match);
            int legsBefore = drivesIn(rig, "APPROACH", "hop", 0, matchT < 0 ? 15001 : matchT).size();
            check(n, match >= 0 && rig.what(say).equals("say Hi Sarah!") && rig.touches == 1
                            && legsBefore == 2 && checksIn(rig, 0, matchT).isEmpty()
                            && rig.count("match") == 1 && rig.metIdsGiven.size() == 1
                            && rig.statesSeen.contains(ExploreBrain.State.MEET) && rig.violations.isEmpty(),
                    "match=" + match + " say=" + rig.what(say) + " legs=" + legsBefore + " checks="
                            + rig.metCheckLog + " " + rig.tail());
        });
        scenario("people_ae4_same_person_5_min_later_is_checked_and_left_alone", n -> {
            Rig rig = peopleRig(peopleTuning(), personWhen(t -> t < 15000 || t >= 310000));
            rig.metChecks = (r, req, k) -> CuriosityPort.Recently.same(0);
            rig.started();
            rig.runUntil(340000);
            List<MetCheck> late = checksIn(rig, 300000, 340000);
            MetCheck c = late.isEmpty() ? null : late.get(0);
            String jpeg = c == null ? "" : ascii(c.request.frameJpeg);
            boolean roamingFrame = jpeg.startsWith("jpeg@")
                    && c.t - Long.parseLong(jpeg.substring(5)) <= 3000;
            boolean carried = c != null && c.request.met.equals(java.util.Arrays.asList("met-1")) && roamingFrame
                    && c.request.personBox != null && "person".equals(c.request.personBox.label)
                    && c.state.equals("PAUSE");
            check(n, rig.count("match") == 1 && carried && rig.countPrefix("hop", c.t, 340001) > 0
                            && enteredAny(rig, 300000, ExploreBrain.State.FACE, ExploreBrain.State.APPROACH,
                            ExploreBrain.State.MEET_LOOK, ExploreBrain.State.MEET) < 0
                            && rig.violations.isEmpty(),
                    "late=" + late + " jpeg=" + jpeg + " " + rig.tail());
        });
        scenario("people_different_person_5_min_later_is_approached", n -> {
            Rig rig = peopleRig(peopleTuning(), personWhen(t -> t < 15000 || t >= 310000));
            rig.metChecks = (r, req, k) -> r.now >= 300000 ? CuriosityPort.Recently.different()
                    : CuriosityPort.Recently.same(0);
            rig.people.match = (r, k) -> k == 1
                    ? CuriosityPort.MatchAnswer.known("Sarah", "Hi {name}!", "Hello again!")
                    : CuriosityPort.MatchAnswer.known("Bob", "Hey {name}!", "Hello again!");
            rig.started();
            rig.runUntil(340000);
            List<MetCheck> late = checksIn(rig, 300000, 340000);
            int answer = rig.firstAfter("met-check answer DIFFERENT", 300000);
            int second = rig.firstAfter("match", 300000);
            int say = rig.first("say Hey Bob!", second);
            check(n, rig.count("match") == 2 && !late.isEmpty() && answer >= 0 && second > answer && say > second
                            && rig.violations.isEmpty(),
                    "late=" + late + " answer=" + answer + " second=" + second + " " + rig.tail());
        });
        scenario("people_check_timeout_or_offline_never_approaches", n -> {
            Rig never = peopleRig(peopleTuning(), personWhen(t -> t < 15000 || t >= 310000));
            never.metChecks = (r, req, k) -> null;
            never.started();
            never.runUntil(340000);
            Rig offline = peopleRig(peopleTuning(), personWhen(t -> t < 15000 || t >= 310000));
            offline.started();
            offline.runUntil(340000);
            // A curiosity stop's person pick with the check never answering: a remark, no approach.
            Rig stop = new Rig(peopleTuning().build(), CLEAR, (r, t) -> list(), true, PERSON_AHEAD);
            stop.people.match = (r, k) -> CuriosityPort.MatchAnswer.known("Olive", "Hi {name}!", "Hello again!");
            stop.metChecks = (r, req, k) -> null;
            stop.at(1000, () -> stop.brain.requestCuriosity());
            stop.at(300000, () -> stop.brain.requestCuriosity());
            stop.started();
            stop.runUntil(330000);
            long check2 = checksIn(stop, 300000, 330000).isEmpty() ? -1 : checksIn(stop, 300000, 330000).get(0).t;
            int remark = stop.firstAfter("say LOOK-LINE", 300000);
            check(n, never.count("match") == 1 && !checksIn(never, 300000, 340000).isEmpty() && never.metCancels >= 1
                            && offline.count("match") == 1 && !checksIn(offline, 300000, 340000).isEmpty()
                            && stop.count("match") == 1 && check2 > 0 && remark >= 0 && stop.timeOf(remark) > check2
                            && enteredAny(stop, 300000, ExploreBrain.State.APPROACH, ExploreBrain.State.MEET_LOOK,
                            ExploreBrain.State.MEET) < 0
                            && never.violations.isEmpty() && offline.violations.isEmpty() && stop.violations.isEmpty(),
                    "never=" + never.count("match") + "/" + never.metCancels + " offline=" + offline.count("match")
                            + " stop=" + stop.count("match") + " check2=" + check2 + " remark=" + remark + " "
                            + stop.tail());
        });
        scenario("people_after_10_min_no_check_and_approached", n -> {
            Rig rig = peopleRig(peopleTuning(), personWhen(t -> t < 15000 || t >= 620000));
            rig.metChecks = (r, req, k) -> CuriosityPort.Recently.same(0);
            rig.started();
            rig.runUntil(650000);
            int second = rig.firstAfter("match", 600000);
            // No check before the second meeting (the one after it is about the person just met).
            check(n, rig.count("match") == 2 && second >= 0 && checksIn(rig, 600000, rig.timeOf(second)).isEmpty()
                            && rig.violations.isEmpty(),
                    "second=" + rig.timeOf(second) + " checks=" + rig.metCheckLog + " " + rig.tail());
        });
        scenario("people_a_then_b_then_a_check_covers_both_and_a_is_left_alone", n -> {
            Rig rig = peopleRig(peopleTuning(), personWhen(t -> t < 15000 || (t >= 100000 && t < 115000)
                    || (t >= 200000 && t < 215000)));
            rig.metChecks = (r, req, k) -> r.now >= 100000 && r.now < 110000 ? CuriosityPort.Recently.different()
                    : CuriosityPort.Recently.same(0);
            rig.people.match = (r, k) -> k == 1
                    ? CuriosityPort.MatchAnswer.known("Ann", "Hi {name}!", "Hello again!")
                    : CuriosityPort.MatchAnswer.known("Bob", "Hey {name}!", "Hello again!");
            rig.started();
            rig.runUntil(240000);
            List<MetCheck> third = checksIn(rig, 200000, 240000);
            boolean both = !third.isEmpty()
                    && third.get(0).request.met.equals(java.util.Arrays.asList("met-1", "met-2"));
            check(n, rig.count("match") == 2 && rig.count("say Hi Ann!") == 1 && rig.count("say Hey Bob!") == 1
                            && both && rig.firstAfter("met-check answer SAME", 200000) >= 0
                            && enteredAny(rig, 200000, ExploreBrain.State.FACE, ExploreBrain.State.APPROACH,
                            ExploreBrain.State.MEET) < 0 && rig.violations.isEmpty(),
                    "checks=" + rig.metCheckLog + " " + rig.tail());
        });
        scenario("people_curiosity_pick_of_the_owner_5_min_later_is_a_remark", n -> {
            Rig rig = new Rig(peopleTuning().build(), CLEAR, (r, t) -> list(), true, PERSON_AHEAD);
            rig.people.match = (r, k) -> CuriosityPort.MatchAnswer.known("Olive", "Hi {name}!", "Hello again!");
            rig.metChecks = (r, req, k) -> CuriosityPort.Recently.same(0);
            rig.at(1000, () -> rig.brain.requestCuriosity());
            rig.at(300000, () -> rig.brain.requestCuriosity());
            rig.started();
            rig.runUntil(330000);
            List<MetCheck> late = checksIn(rig, 300000, 330000);
            MetCheck c = late.isEmpty() ? null : late.get(0);
            CuriosityPort.LookRequest ask2 = rig.asks.size() < 2 ? null : rig.asks.get(1);
            boolean scanFrame = c != null && ask2 != null
                    && ascii(c.request.frameJpeg).equals(ascii(ask2.frames.get(2).jpeg));
            int remark = rig.firstAfter("say LOOK-LINE", 300000);
            check(n, rig.count("say Hi Olive!") == 1 && rig.count("match") == 1 && c != null && c.state.equals("ASK")
                            && c.request.met.equals(java.util.Arrays.asList("met-1")) && scanFrame
                            && remark >= 0 && rig.timeOf(remark) > c.t
                            && rig.countPrefix("hop", c.t, rig.timeOf(remark) + 1) == 0
                            && enteredAny(rig, 300000, ExploreBrain.State.APPROACH, ExploreBrain.State.MEET_LOOK,
                            ExploreBrain.State.MEET) < 0 && rig.violations.isEmpty(),
                    "late=" + late + " remark=" + remark + " " + rig.tail());
        });
        scenario("people_owner_in_view_3_min_gets_at_most_3_checks", n -> {
            Rig rig = peopleRig(peopleTuning(), personWhen(t -> true));
            rig.metChecks = (r, req, k) -> CuriosityPort.Recently.same(0);
            rig.started();
            rig.runUntil(200000);
            int say = rig.first("say Hi Sarah!", 0);
            long ended = rig.timeOf(say) + rig.speechMs;
            List<MetCheck> checks = checksIn(rig, ended, ended + 180000);
            check(n, say >= 0 && checks.size() >= 2 && checks.size() <= 3 && rig.count("match") == 1
                            && rig.violations.isEmpty(),
                    "ended=" + ended + " checks=" + checks);
        });
        scenario("people_hazard_during_a_roaming_approach_drops_the_meeting", n -> {
            Rig[] h = new Rig[1];
            Rig rig = new Rig(peopleTuning().build(), hazardWhile(h, ExploreBrain.State.APPROACH, t -> edgeAhead(t)),
                    personWhen(t -> t < 2500), true, (r, req, nth) -> CuriosityPort.Answer.nothing());
            rig.people.match = (r, k) -> STRANGER;
            h[0] = rig;
            rig.started();
            rig.runUntil(20000);
            long approach = entered(rig, ExploreBrain.State.APPROACH, 0);
            int startle = rig.first("startle", 0);
            check(n, approach >= 0 && startle >= 0 && rig.timeOf(startle) > approach && rig.count("match") == 0
                            && !rig.statesSeen.contains(ExploreBrain.State.MEET_LOOK)
                            && !rig.statesSeen.contains(ExploreBrain.State.MEET) && rig.metIdsGiven.isEmpty()
                            && rig.countPrefix("say", 0, 20001) == 0 && rig.violations.isEmpty(),
                    "approach=" + approach + " startle=" + startle + " " + rig.tail());
        });
        scenario("people_camera_closed_through_a_roaming_meetings_talking_states", n -> {
            // The meeting-as-today path (no ears session), as above.
            Rig rig = peopleRig(peopleTuning(), personWhen(t -> t < 15000));
            rig.earsPresent = false;
            rig.people.match = (r, k) -> STRANGER;
            rig.people.listen = ListenScript.always(hearWords("my name is Priya"));
            rig.started();
            rig.runUntil(30000);
            boolean talked = rig.statesSeen.containsAll(java.util.Arrays.asList(ExploreBrain.State.MEET,
                    ExploreBrain.State.ASK_NAME, ExploreBrain.State.LISTEN, ExploreBrain.State.NAME,
                    ExploreBrain.State.REMEMBER, ExploreBrain.State.SPEAK));
            boolean closed = true;
            for (ExploreBrain.State s : new ExploreBrain.State[]{ExploreBrain.State.MEET, ExploreBrain.State.ASK_NAME,
                    ExploreBrain.State.LISTEN, ExploreBrain.State.NAME, ExploreBrain.State.REMEMBER,
                    ExploreBrain.State.SPEAK, ExploreBrain.State.NAME_CLIP}) {
                closed &= !rig.openStates.contains(s);
            }
            check(n, talked && closed && rig.openStates.contains(ExploreBrain.State.APPROACH)
                            && rig.stored.equals(java.util.Arrays.asList("Priya")) && rig.violations.isEmpty(),
                    "seen=" + rig.statesSeen + " open=" + rig.openStates + " " + rig.tail());
        });
        scenario("people_trace_notes_never_carry_a_name", n -> {
            Rig rig = peopleRig(peopleTuning(), personWhen(t -> t < 15000 || (t >= 100000 && t < 115000)));
            rig.people.match = (r, k) -> k == 1 ? STRANGER
                    : CuriosityPort.MatchAnswer.known("Sarah", "Hi {name}!", "Hello again!");
            rig.people.listen = ListenScript.always(hearWords("my name is Priya"));
            rig.metChecks = (r, req, k) -> r.now >= 100000 ? CuriosityPort.Recently.different()
                    : CuriosityPort.Recently.same(0);
            List<String> notes = traced(rig);
            rig.started();
            rig.runUntil(130000);
            List<String> leaks = new ArrayList<String>();
            for (String x : notes) {
                if (x.contains("Priya") || x.contains("Sarah") || x.contains("What's your name")
                        || x.contains("shy friend") || x.contains("remember you")) {
                    leaks.add(x);
                }
            }
            check(n, leaks.isEmpty() && rig.count("match") == 2 && rig.count("say Hi Sarah!") == 1
                            && notedAt(notes, "just met") >= 0 && rig.violations.isEmpty(),
                    "leaks=" + leaks + " " + rig.tail());
        });
        scenario("replies_recently_met_reads_same_none_unsure_and_rejects_bad_answers", n -> {
            java.util.Map<String, Object> json = new java.util.LinkedHashMap<String, Object>();
            json.put("same_as", "2");
            CuriosityPort.Recently two = ClaudeReplies.recentlyMet(json, 2);
            json.put("same_as", "Person 1");
            CuriosityPort.Recently labelled = ClaudeReplies.recentlyMet(json, 2);
            json.put("same_as", 1L);
            CuriosityPort.Recently number = ClaudeReplies.recentlyMet(json, 2);
            json.put("same_as", "none");
            CuriosityPort.Recently none = ClaudeReplies.recentlyMet(json, 2);
            json.put("same_as", "Unsure");
            CuriosityPort.Recently unsure = ClaudeReplies.recentlyMet(json, 2);
            json.put("same_as", "3");
            CuriosityPort.Recently beyond = ClaudeReplies.recentlyMet(json, 2);
            json.put("same_as", "0");
            CuriosityPort.Recently zero = ClaudeReplies.recentlyMet(json, 2);
            json.put("same_as", "maybe the first");
            CuriosityPort.Recently odd = ClaudeReplies.recentlyMet(json, 2);
            json.remove("same_as");
            CuriosityPort.Recently missing = ClaudeReplies.recentlyMet(json, 2);
            check(n, two.status == CuriosityPort.Recently.Status.SAME && two.index == 1
                            && labelled.status == CuriosityPort.Recently.Status.SAME && labelled.index == 0
                            && number.status == CuriosityPort.Recently.Status.SAME && number.index == 0
                            && none.status == CuriosityPort.Recently.Status.DIFFERENT
                            && unsure.status == CuriosityPort.Recently.Status.UNSURE
                            && beyond.status == CuriosityPort.Recently.Status.FAILED
                            && zero.status == CuriosityPort.Recently.Status.FAILED
                            && odd.status == CuriosityPort.Recently.Status.FAILED
                            && missing.status == CuriosityPort.Recently.Status.FAILED,
                    two + " " + labelled + " " + number + " " + none + " " + unsure + " " + beyond + " " + zero
                            + " " + odd + " " + missing);
        });
        scenario("people_tuning_defaults_10_min_leave_alone_one_check_a_minute", n -> {
            ExploreTuning t = new ExploreTuning.Builder().build();
            check(n, t.metLeaveAloneMs == 600000 && t.metCheckIntervalMs == 60000 && t.metCheckTimeoutMs == 10000
                            && t.metClearedMs == 15000 && Math.abs(t.politeHeight - 0.6f) < 1e-6
                            && t.peopleCooldownMs == 120000,
                    t.metLeaveAloneMs + " " + t.metCheckIntervalMs + " " + t.metCheckTimeoutMs + " " + t.metClearedMs
                            + " " + t.politeHeight);
        });
    }

    // ---- the harness surface for cues and conversations (meeting plan U6, KTD3, KTD4, KTD7, KTD8) ----

    /**
     * The brain does not consume these fakes yet (U7 and U8 do), so each scenario
     * asserts the fake's own behaviour: what U7 and U8 will build on.
     */
    private static void earsAndChatScenarios() {
        scenario("ears_cue_at_t_is_drained_on_the_next_step_with_its_kind_tier_side_and_angle", n -> {
            Rig rig = new Rig(tuning().build(), CLEAR);
            rig.cue(3000, Ears.Kind.NAME, Ears.Side.LEFT, -60f);
            rig.cue(3000, Ears.Tier.WEAK, Ears.Side.RIGHT, Float.NaN);
            rig.started();
            rig.runUntil(2990);
            List<Ears.Cue> before = rig.drain();
            rig.runUntil(3000);
            List<Ears.Cue> at = rig.drain();
            List<Ears.Cue> again = rig.drain();
            rig.runUntil(4000);
            List<Ears.Cue> after = rig.drain();
            Ears.Cue name = at.size() == 2 ? at.get(0) : null;
            Ears.Cue burst = at.size() == 2 ? at.get(1) : null;
            check(n, before.isEmpty() && at.size() == 2 && again.isEmpty() && after.isEmpty()
                            && name.kind == Ears.Kind.NAME && name.tier == Ears.Tier.STRONG && name.strong()
                            && name.side == Ears.Side.LEFT && name.hasAngle() && name.angleDeg == -60f && name.at == 3000
                            && burst.kind == Ears.Kind.VOICE && burst.tier == Ears.Tier.WEAK && !burst.strong()
                            && burst.side == Ears.Side.RIGHT && !burst.hasAngle() && burst.at == 3000
                            && rig.present() && !rig.listening()
                            && rig.count("cue NAME STRONG LEFT") == 1 && rig.count("cue VOICE WEAK RIGHT") == 1
                            && rig.violations.isEmpty(),
                    "before=" + before + " at=" + at + " after=" + after + " " + rig.tail());
        });
        scenario("ears_trend_and_shove_spikes_are_step_input", n -> {
            Rig rig = new Rig(tuning().build(), CLEAR);
            rig.trendAt(1000, -40f).trendAt(1100, -25f).trendAt(1200, -30f);
            rig.shoveAt(2000, 900);
            rig.started();
            rig.runUntil(990);
            Ears.Trend none = rig.trend();
            rig.runUntil(1000);
            Ears.Trend first = rig.trend();
            rig.runUntil(1100);
            Ears.Trend closing = rig.trend();
            rig.runUntil(1200);
            Ears.Trend growing = rig.trend();
            Ears.Trend consumed = rig.trend();
            rig.runUntil(1990);
            Ears.Shove early = rig.shove();
            rig.runUntil(2000);
            Ears.Shove shove = rig.shove();
            Ears.Shove gone = rig.shove();
            check(n, none == null && first != null && first.angleDeg == -40f && Float.isNaN(first.previousDeg)
                            && !first.growing() && first.at == 1000
                            && closing != null && closing.angleDeg == -25f && closing.previousDeg == -40f
                            && !closing.growing()
                            && growing != null && growing.angleDeg == -30f && growing.previousDeg == -25f
                            && growing.growing() && consumed == null
                            && early == null && shove != null && shove.counts == 900 && shove.at == 2000 && gone == null
                            && rig.violations.isEmpty(),
                    "first=" + first + " closing=" + closing + " growing=" + growing + " shove=" + shove + " "
                            + rig.tail());
        });
        scenario("listen_script_answers_turns_1_to_3_then_silence_on_turn_4_each_after_its_own_delay", n -> {
            Rig rig = new Rig(tuning().build(), CLEAR);
            rig.people.listen = ListenScript.turns(hearWords("hi").after(300), hearWords("good thanks").after(1200),
                    hearWords("catch you later"), hearSilence());
            rig.listen(6000);
            rig.runUntil(290);
            CuriosityPort.Heard early = rig.heard();
            boolean openEarly = rig.micOpen();
            rig.runUntil(300);
            CuriosityPort.Heard one = rig.heard();
            boolean openAfter = rig.micOpen();
            rig.listen(6000);
            rig.runUntil(1490);
            CuriosityPort.Heard early2 = rig.heard();
            rig.runUntil(1500);
            CuriosityPort.Heard two = rig.heard();
            rig.listen(6000);
            rig.runUntil(3500);
            CuriosityPort.Heard three = rig.heard();
            rig.listen(6000);
            rig.runUntil(5500);
            CuriosityPort.Heard four = rig.heard();
            rig.listen(6000);
            rig.runUntil(7500);
            CuriosityPort.Heard five = rig.heard();
            check(n, early == null && openEarly && one != null && one.status == CuriosityPort.Heard.Status.WORDS
                            && one.text.equals("hi") && !openAfter
                            && early2 == null && two != null && two.text.equals("good thanks")
                            && three != null && three.text.equals("catch you later")
                            && four == CuriosityPort.Heard.NOTHING && five == CuriosityPort.Heard.NOTHING
                            && rig.listens == 5 && rig.count("listen") == 5 && rig.count("heard WORDS") == 3
                            && rig.count("heard SILENCE") == 2 && rig.violations.isEmpty(),
                    "one=" + one + " two=" + two + " three=" + three + " four=" + four + " five=" + five + " "
                            + rig.tail());
        });
        scenario("listen_script_never_answers_and_a_line_meanwhile_is_a_say_while_the_mic_is_open", n -> {
            Rig rig = new Rig(tuning().build(), CLEAR);
            rig.people.listen = ListenScript.NEVER;
            rig.listen(4000);
            rig.runUntil(3000);
            CuriosityPort.Heard none = rig.heard();
            boolean open = rig.micOpen();
            rig.say("hello?");
            int flagged = rig.violations.size();
            rig.runUntil(6000);
            CuriosityPort.Heard still = rig.heard();
            boolean closed = !rig.micOpen();
            rig.say("still there?");
            check(n, none == null && open && flagged == 1 && rig.violations.get(0).endsWith(":say while the mic is open in EYES_ONLY")
                            && still == null && closed && rig.violations.size() == 1,
                    "open=" + open + " closed=" + closed + " " + rig.tail());
        });
        scenario("listen_while_a_line_or_a_clip_plays_is_a_violation", n -> {
            Rig rig = new Rig(tuning().build(), CLEAR);
            rig.say("one sec");
            rig.listen(4000);
            int whileSpeaking = rig.violations.size();
            rig.runUntil(2000);
            CuriosityPort.Heard one = rig.heard();
            rig.clipWindow(800);
            rig.listen(4000);
            int whileClip = rig.violations.size();
            rig.runUntil(4000);
            CuriosityPort.Heard two = rig.heard();
            rig.listen(4000);
            check(n, whileSpeaking == 1 && rig.violations.get(0).endsWith(":listen while still speaking")
                            && one == CuriosityPort.Heard.NOTHING
                            && whileClip == 2 && rig.violations.get(1).endsWith(":listen while a clip plays")
                            && two == CuriosityPort.Heard.NOTHING && rig.violations.size() == 2
                            && rig.clipWindows.equals(java.util.Arrays.asList(800L)) && rig.clipUntil == 2000 + 800 + 500
                            && rig.count("clip 800") == 1,
                    rig.tail());
        });
        scenario("turn_script_returns_the_per_turn_fields_and_cancel_drops_a_late_answer", n -> {
            Rig rig = new Rig(tuning().build(), CLEAR);
            rig.turns = (r, req, k) -> k == 1
                    ? CuriosityPort.Turn.line("Hey. How was the weekend?", "how was the weekend", null, false, false,
                            "weekend: asked")
                    : k == 2 ? CuriosityPort.Turn.line("Ha. I don't do timers.", null, "Sarah", false, true, null)
                    : k == 3 ? CuriosityPort.Turn.line("Later then.", null, null, true, false, "left at three")
                    : k == 4 ? CuriosityPort.Turn.refused()
                    : k == 5 ? CuriosityPort.Turn.line("never returned")
                    : CuriosityPort.Turn.unreachable();
            CuriosityPort.TurnRequest opener = CuriosityPort.TurnRequest.opener("persona", "Sarah", "notes");
            List<CuriosityPort.Exchange> window = new ArrayList<CuriosityPort.Exchange>();
            window.add(new CuriosityPort.Exchange("fine", "Hey. How was the weekend?"));
            CuriosityPort.TurnRequest reply = new CuriosityPort.TurnRequest("persona", "Sarah", "notes", window,
                    "set a timer");
            rig.turn(opener, 5000);
            rig.runUntil(990);
            CuriosityPort.Turn early = rig.turnAnswer();
            rig.runUntil(1000);
            CuriosityPort.Turn one = rig.turnAnswer();
            rig.turn(reply, 5000);
            rig.runUntil(2000);
            CuriosityPort.Turn two = rig.turnAnswer();
            rig.turn(reply, 5000);
            rig.runUntil(3000);
            CuriosityPort.Turn three = rig.turnAnswer();
            rig.turn(reply, 3000);
            rig.runUntil(4000);
            CuriosityPort.Turn four = rig.turnAnswer();
            rig.turn(reply, 5000);
            rig.runUntil(4500);
            rig.cancelTurn();
            rig.runUntil(6000);
            CuriosityPort.Turn cancelled = rig.turnAnswer();
            rig.turn(reply, 5000);
            rig.runUntil(7000);
            CuriosityPort.Turn six = rig.turnAnswer();
            rig.turns = null;
            rig.turn(reply, 5000);
            rig.runUntil(9000);
            CuriosityPort.Turn never = rig.turnAnswer();
            boolean opened = rig.turnAsks.size() == 7 && rig.turnAsks.get(0).request == opener
                    && rig.turnAsks.get(0).request.heard == null && rig.turnAsks.get(0).request.transcript.isEmpty()
                    && rig.turnAsks.get(1).request.heard.equals("set a timer")
                    && rig.turnAsks.get(1).request.transcript.get(0).heard.equals("fine")
                    && rig.turnAsks.get(3).timeoutMs == 3000 && rig.turnAsks.get(0).timeoutMs == 5000;
            check(n, opened && early == null && one != null && one.status == CuriosityPort.Turn.Status.LINE
                            && one.line.equals("Hey. How was the weekend?") && one.questionAsked.equals("how was the weekend")
                            && one.nameGiven == null && !one.endsConversation && !one.deflected
                            && one.notesUpdate.equals("weekend: asked")
                            && two != null && two.nameGiven.equals("Sarah") && two.deflected && two.questionAsked == null
                            && two.notesUpdate == null
                            && three != null && three.endsConversation && three.notesUpdate.equals("left at three")
                            && four != null && four.status == CuriosityPort.Turn.Status.REFUSED && four.line == null
                            && cancelled == null && rig.turnCancels == 1
                            && six != null && six.status == CuriosityPort.Turn.Status.UNREACHABLE
                            && never == null && rig.count("turn LINE") == 3 && rig.count("cancel turn") == 1
                            && rig.violations.isEmpty(),
                    "asks=" + rig.turnAsks + " one=" + one + " cancelled=" + cancelled + " " + rig.tail());
        });
        scenario("notes_delta_forget_ears_and_clip_window_are_recorded_by_the_fake", n -> {
            Rig rig = new Rig(tuning().build(), CLEAR);
            rig.notesDelta("p-1", "likes tea; asked about the weekend", 5000);
            rig.runUntil(990);
            CuriosityPort.Done early = rig.notesDeltaAnswer();
            rig.runUntil(1000);
            CuriosityPort.Done merged = rig.notesDeltaAnswer();
            rig.notesResult = CuriosityPort.Done.FAILED;
            rig.notesDelta("p-1", "again", 5000);
            rig.runUntil(2000);
            CuriosityPort.Done failed = rig.notesDeltaAnswer();
            rig.notesDelta("p-1", "dropped", 5000);
            rig.cancelNotesDelta();
            rig.runUntil(3000);
            CuriosityPort.Done cancelled = rig.notesDeltaAnswer();
            rig.forget("p-1", 5000);
            rig.runUntil(4000);
            CuriosityPort.Done forgot = rig.forgetAnswer();
            rig.forget("p-2", 5000);
            rig.cancelForget();
            rig.runUntil(5000);
            CuriosityPort.Done forgetCancelled = rig.forgetAnswer();
            boolean closedAtStart = !rig.listening();
            rig.earsOpen();
            boolean openNow = rig.listening();
            rig.earsClose();
            boolean closedAgain = !rig.listening();
            rig.earsPresent = false;
            rig.earsOpen();
            boolean absentStaysClosed = !rig.listening() && !rig.present();
            rig.clipWindow(700);
            check(n, early == null && merged == CuriosityPort.Done.OK && merged.ok()
                            && failed == CuriosityPort.Done.FAILED && !failed.ok() && cancelled == null
                            && rig.notesDeltas.equals(java.util.Arrays.asList("p-1: likes tea; asked about the weekend",
                                    "p-1: again", "p-1: dropped"))
                            && forgot == CuriosityPort.Done.OK && forgetCancelled == null
                            && rig.forgotten.equals(java.util.Arrays.asList("p-1", "p-2"))
                            && closedAtStart && openNow && closedAgain && absentStaysClosed
                            && rig.earsOpens == 2 && rig.earsCloses == 1
                            && rig.clipWindows.equals(java.util.Arrays.asList(700L))
                            && rig.count("notes p-1") == 3 && rig.count("notes DONE") == 1 && rig.count("notes FAILED") == 1
                            && rig.count("cancel notes") == 1 && rig.count("forget p-1") == 1 && rig.count("forgot DONE") == 1
                            && rig.count("cancel forget") == 1 && rig.count("ears open") == 2 && rig.count("ears close") == 1
                            && rig.count("clip 700") == 1 && rig.violations.isEmpty(),
                    rig.tail());
        });
        scenario("vision_person_box_dropped_at_t_reports_no_facing_face_afterwards", n -> {
            Detection them = box("person", 0.9f, 0.5f, 0.55f, 0.6f, 0.8f);
            Vision v = personUntil(them, 4000);
            Rig rig = new Rig(tuning().build(), CLEAR, v, true);
            Detection before = rig.facingFace(3990);
            Detection gone = rig.facingFace(4000);
            Detection later = rig.facingFace(9000);
            check(n, before == them && v.see(rig, 3990).size() == 1 && gone == null && v.see(rig, 4000).isEmpty()
                            && later == null && v.see(rig, 9000).isEmpty(),
                    "before=" + before + " gone=" + gone + " later=" + later);
        });
        scenario("vision_profile_shaped_box_from_t_reports_no_facing_face_afterwards", n -> {
            Detection them = box("person", 0.9f, 0.5f, 0.55f, 0.6f, 0.8f);
            Vision v = profileFrom(them, 4000);
            Rig rig = new Rig(tuning().build(), CLEAR, v, true);
            Detection before = rig.facingFace(3990);
            List<Detection> turned = v.see(rig, 4000);
            Detection profile = turned.size() == 1 ? turned.get(0) : null;
            Detection none = rig.facingFace(4000);
            // A facing shape too small to be 1.5 m away is not a facing face either.
            Detection far = box("person", 0.9f, 0.5f, 0.55f, 0.08f, 0.1f);
            boolean farRejected = !facing(far, rig.tuning);
            check(n, before == them && Math.abs(them.width() / them.height() - 0.75f) < 1e-5
                            && profile != null && profile.label.equals("person")
                            && Math.abs(profile.width() / profile.height() - 0.5f) < 1e-5
                            && Math.abs(profile.height() - them.height()) < 1e-6
                            && Math.abs((profile.x0 + profile.x1) - (them.x0 + them.x1)) < 1e-6
                            && none == null && rig.facingFace(9000) == null && farRejected,
                    "before=" + before + " profile=" + profile + " none=" + none);
        });
        scenario("chat_tuning_defaults_follow_the_plans_assumptions", n -> {
            ExploreTuning t = new ExploreTuning.Builder().build();
            check(n, t.cueHoldMs == 10000 && t.leanInMs == 4000 && t.newcomerAngleDeg == 45f && t.cueStopBandDeg == 10f
                            && t.strongCueLooks == 3 && t.weakCueLooks == 2
                            && t.facingFaceMinRatio == 0.65f && t.facingFaceMinHeight == 0.12f
                            && t.unansweredListenMs == 4000 && t.chatStallGraceMs == 5000
                            && t.turnBudgetMs == 5000 && t.turnRetryMs == 3000 && t.sentenceCap == 2
                            && t.transcriptWindow == 30 && t.deafTailMs == 500 && t.answerClipMs == 600
                            // Hey-miko plan U6: the placeholders and the heading history.
                            // Robot QA 2026-09-30: looks took 1.4-2.4 s, later 1.8-4.7 s (detection); a
                            // floor-level caller scored 0.27-0.32; a stale caller counts within 30 deg.
                            && t.callLookMs == 5000 && t.callStaleLookDeg == 30 && t.callPersonMinHeight == 0.12f && t.callNearHeight == 0.35f
                            && t.callPersonMinScore == 0.25f && t.confidenceFloor == 0.35f
                            && t.callListenMs == 4000 && t.whereClipMs == 900 && t.headingHistoryMs == 30000
                            && t.headingSampleMs == 100,
                    t.cueHoldMs + " " + t.leanInMs + " " + t.newcomerAngleDeg + " " + t.cueStopBandDeg + " "
                            + t.strongCueLooks + " " + t.weakCueLooks + " " + t.facingFaceMinRatio + " "
                            + t.facingFaceMinHeight + " " + t.unansweredListenMs + " " + t.chatStallGraceMs + " "
                            + t.turnBudgetMs + " " + t.turnRetryMs + " " + t.sentenceCap + " " + t.transcriptWindow
                            + " " + t.deafTailMs + " " + t.answerClipMs + " " + t.callLookMs + " "
                            + t.callPersonMinScore + " " + t.confidenceFloor + " " + t.callPersonMinHeight + " " + t.callNearHeight + " " + t.callListenMs + " " + t.whereClipMs
                            + " " + t.headingHistoryMs + " " + t.headingSampleMs);
        });
        scenario("chat_session_starts_thinking_with_the_four_chat_states_and_no_behaviour", n -> {
            ExploreTuning t = tuning().build();
            ChatSession s = new ChatSession(t, CuriosityPort.NONE, null);
            ChatSession.State[] states = ChatSession.State.values();
            boolean named = states.length == 4 && states[0] == ChatSession.State.CHAT_THINK
                    && states[1] == ChatSession.State.CHAT_SPEAK && states[2] == ChatSession.State.CHAT_LISTEN
                    && states[3] == ChatSession.State.CHAT_NOTES;
            check(n, named && s.state() == ChatSession.State.CHAT_THINK && !s.finished() && !s.signedOff()
                            && !Ears.NONE.present() && !Ears.NONE.listening()
                            && Ears.NONE.drain().isEmpty() && Ears.NONE.trend() == null && Ears.NONE.shove() == null
                            && CuriosityPort.NONE.turnAnswer().status == CuriosityPort.Turn.Status.FAILED
                            && CuriosityPort.NONE.notesDeltaAnswer() == CuriosityPort.Done.FAILED
                            && CuriosityPort.NONE.forgetAnswer() == CuriosityPort.Done.FAILED,
                    java.util.Arrays.toString(states) + " state=" + s.state());
        });
    }

    // ---- cues, the lean-in and the turn to the voice (meeting plan U7; R1-R3, R6-R9, R15; KTD3-KTD6, KTD8) ----
    //
    // The cue rig: Claude answers "nothing" at stops and the stops are far apart, so
    // he roams; the yaw model makes every turn measured, so a person placed at a
    // bearing (personAt) is seen only when the camera really faces them. Sarah is
    // known, so a facing face ends in "Hi Sarah!" through the MEET path.

    private static ExploreTuning.Builder cueTuning() {
        return claudeTuning().curiosityMs(100000000L, 100000000L).gyro(robotGyro())
                .cueTurn(45, 90, 500, 2000, 600);
    }

    private static Rig cueRig(Vision v) {
        return cueRig(cueTuning(), CLEAR, v);
    }

    private static Rig cueRig(ExploreTuning.Builder b, Feed feed, Vision v) {
        Rig rig = new Rig(b.build(), feed, v, true, (r, req, nth) -> CuriosityPort.Answer.nothing());
        rig.people.match = (r, k) -> CuriosityPort.MatchAnswer.known("Sarah", "Hi {name}!", "Hello again!");
        rig.people.lines = STRANGER;
        return rig;
    }

    /** Nobody in view, ever. */
    private static final Vision EMPTY_ROOM = (r, t) -> list();

    /** A facing person box: about as wide as it is tall, 0.4 of the frame high (1.5 m or nearer). */
    private static Detection facingPerson() {
        return box("person", 0.9f, 0.5f, 0.5f, 0.3f, 0.4f);
    }

    /**
     * A person standing at this bearing in the yaw model's frame (left positive):
     * their facing box is in view while the true heading is within halfViewDeg of it.
     */
    private static Vision personAt(double bearingLeftDeg, double halfViewDeg) {
        return (r, t) -> r.yaw != null && Math.abs(Heading.delta(r.yaw.wrapped(), Heading.wrap(bearingLeftDeg)))
                <= halfViewDeg ? list(facingPerson()) : list();
    }

    /** The bearing (yaw frame, left positive) a cue's Ears angle (negative left) points at. */
    private static double bearingOf(float earsAngleDeg) {
        return -earsAngleDeg;
    }

    /** The state before the first entry to `s` at or after from, else null. */
    private static String stateBefore(Rig rig, ExploreBrain.State s, long from) {
        String prev = null;
        for (Event e : rig.stateLog) {
            if (e.t >= from && e.what.equals(s.name())) {
                return prev;
            }
            prev = e.what;
        }
        return null;
    }

    /** Runs until the first event with this prefix at or after from appears, or until limit; its time or -1. */
    private static long runUntilEvent(Rig rig, String prefix, long from, long limit) {
        while (rig.now < limit && rig.firstAfter(prefix, from) < 0) {
            rig.runUntil(rig.now + 10);
        }
        int i = rig.firstAfter(prefix, from);
        return i < 0 ? -1 : rig.timeOf(i);
    }

    /** Runs until the brain enters this state at or after from, or until limit; the entry time or -1. */
    private static long runUntilState(Rig rig, ExploreBrain.State s, long from, long limit) {
        while (rig.now < limit && entered(rig, s, from) < 0) {
            rig.runUntil(rig.now + 10);
        }
        return entered(rig, s, from);
    }

    private static String gauges(Rig rig) {
        return "counters=" + rig.counters + " stamps=" + rig.stamps;
    }

    // ---- the lean-in cooldown (robot 2026-10-01 15:12-15:17: 15 lean-ins in 5 min under a desk) ----

    /** Times of each note starting with prefix. */
    private static List<Long> noteTimes(List<String> notes, String prefix) {
        List<Long> out = new ArrayList<Long>();
        for (String x : notes) {
            int sp = x.indexOf(' ');
            if (x.startsWith(prefix, sp + 1)) {
                out.add(Long.parseLong(x.substring(0, sp)));
            }
        }
        return out;
    }

    private static void leanInCooldownScenarios() {
        scenario("leanin_weak_cues_every_20_s_for_5_min_nobody_found_at_most_5_lean_ins_and_roams_between", n -> {
            // Talking near him: a voice every 20 s for 5 minutes, nobody ever found. Each
            // lean-in that finds nobody is followed by leanInCooldownMs of ignoring voices
            // (one note per cooldown), so he roams between them.
            Rig rig = cueRig(EMPTY_ROOM);
            List<String> notes = traced(rig);
            rig.started();
            for (long t = 400; t < 300000; t += 20000) {
                rig.cue(t, Ears.Tier.WEAK, (t / 20000) % 2 == 0 ? Ears.Side.LEFT : Ears.Side.RIGHT, Float.NaN);
            }
            rig.runUntil(300000);
            List<Long> leanIns = noteTimes(notes, "a lean-in from");
            boolean roamed = !leanIns.isEmpty();
            for (int i = 0; roamed && i < leanIns.size(); i++) {
                long to = i + 1 < leanIns.size() ? leanIns.get(i + 1) : 300000;
                roamed = rig.countPrefix("hop", leanIns.get(i), to) > 0;
            }
            int ignored = noteTimes(notes, "cue ignored: lean-in cooldown (").size();
            check(n, leanIns.size() >= 2 && leanIns.size() <= 5 && roamed && ignored >= 1 && ignored <= leanIns.size()
                            && rig.counted(ExploreBrain.Gauges.Counter.LEAN_INS) == leanIns.size()
                            && rig.violations.isEmpty(),
                    "leanIns=" + leanIns + " ignored=" + noteTimes(notes, "cue ignored: lean-in cooldown (") + " "
                            + gauges(rig));
        });
        scenario("leanin_a_call_during_the_cooldown_is_answered_at_once", n -> {
            Rig rig = cueRig(EMPTY_ROOM);
            List<String> notes = traced(rig);
            rig.started();
            rig.cue(400, Ears.Tier.WEAK, Ears.Side.RIGHT, Float.NaN);
            rig.runUntil(400);
            long pause = runUntilState(rig, ExploreBrain.State.PAUSE, 401, 30000);
            long weak = pause + 3000;
            long callT = pause + 6000;
            rig.cue(weak, Ears.Tier.WEAK, Ears.Side.LEFT, Float.NaN);
            rig.cue(callT, Ears.Kind.NAME, Ears.Side.LEFT, -70f);
            rig.runUntil(callT + 3000);
            long ignored = notedAt(notes, "cue ignored: lean-in cooldown (");
            check(n, pause > 0 && ignored >= weak && ignored < callT && answerAt(rig, callT) == callT
                            && entered(rig, ExploreBrain.State.CUE_TURN, callT) == callT
                            && noteTimes(notes, "a lean-in from").size() == 1 && rig.violations.isEmpty(),
                    "pause@" + pause + " ignored@" + ignored + " answer@" + answerAt(rig, callT) + " " + rig.tail());
        });
        scenario("leanin_at_most_one_per_30_s_even_after_a_meeting", n -> {
            // A lean-in that meets Sarah starts no cooldown, but the next voice inside
            // leanInMinGapMs of it is ignored; one after the gap leans in again.
            Rig rig = cueRig(personAt(90, 25));
            List<String> notes = traced(rig);
            rig.started();
            rig.cue(400, Ears.Tier.WEAK, Ears.Side.LEFT, -90f);
            rig.runUntil(400);
            long met = runUntilState(rig, ExploreBrain.State.PAUSE, 401, 30000);
            long early = 400 + 25000;
            long late = 400 + 35000;
            rig.cue(early, Ears.Tier.WEAK, Ears.Side.LEFT, -90f);
            rig.cue(late, Ears.Tier.WEAK, Ears.Side.LEFT, -90f);
            rig.runUntil(late + 2000);
            List<Long> leanIns = noteTimes(notes, "a lean-in from");
            long ignored = notedAt(notes, "cue ignored: lean-in cooldown (");
            check(n, met > 0 && met < early && rig.count("match") >= 1 && leanIns.size() == 2
                            && leanIns.get(1) >= late && leanIns.get(1) - late <= 20 && ignored >= early
                            && ignored < late && rig.violations.isEmpty(),
                    "met@" + met + " leanIns=" + leanIns + " ignored@" + ignored + " " + rig.tail());
        });
        scenario("leanin_never_interrupts_a_seek", n -> {
            Rig rig = seekRig(seekTuning().cueTurn(45, 90, 500, 2000, 600), (r, t) -> scene(1),
                    (r, req, k) -> CuriosityPort.WayOut.way(0, 0f));
            List<String> notes = traced(rig);
            rig.started();
            runUntil(rig, 300000, r -> notedAt(notes, "seeking: Claude picked") >= 0);
            long picked = rig.now;
            runUntil(rig, picked + 30000, r -> r.firstAfter("hop", picked) >= 0);
            long cueT = rig.now + 200;
            rig.cue(cueT, Ears.Tier.WEAK, Ears.Side.RIGHT, 70f);
            rig.runUntil(cueT + 4000);
            String gaveUp = firstNote(notes, "seeking: gave up");
            long ended = notedAt(notes, "seeking: ", cueT);
            long turn = entered(rig, ExploreBrain.State.CUE_TURN, cueT);
            check(n, (gaveUp == null || !gaveUp.contains("a voice")) && (turn < 0 || (ended >= 0 && turn >= ended))
                            && rig.violations.isEmpty(),
                    "gaveUp=" + gaveUp + " ended@" + ended + " cueTurn@" + turn + " seeking=" + rig.brain.seeking()
                            + " " + rig.tail());
        });
        scenario("leanin_never_interrupts_the_recover_wait", n -> {
            List<String> notes = new ArrayList<String>();
            Rig rig = cutoutRig(notes, escTuning().hopTicks(20), 12000);
            rig.started();
            runUntil(rig, 30000, r -> notedAt(notes, "stall: waiting for the motor board") >= 0);
            long wait = rig.now;
            for (long t = wait + 500; t < wait + 20000; t += 1500) {
                rig.cue(t, Ears.Tier.WEAK, Ears.Side.LEFT, Float.NaN);
            }
            runUntil(rig, wait + 40000, r -> notedAt(notes, "the board is back") >= 0);
            long back = notedAt(notes, "the board is back");
            long lean = notedAt(notes, "a lean-in from");
            check(n, back > wait && (lean < 0 || lean > back) && rig.violations.isEmpty(),
                    "wait@" + wait + " back@" + back + " lean@" + lean + " notes=" + lastNotes(notes, 20));
        });
        scenario("leanin_never_interrupts_an_escape_or_the_ladder", n -> {
            // Pinned with jam detection off: escapes, the ladder and its rests over and over,
            // with a voice every 2 s. No lean-in starts from an escape state, a startle, a
            // back-off or a cornered rest.
            List<String> notes = new ArrayList<String>();
            Rig rig = pinnedLadderRig(notes);
            rig.started();
            for (long t = 2000; t < 120000; t += 2000) {
                rig.cue(t, Ears.Tier.WEAK, Ears.Side.LEFT, Float.NaN);
            }
            rig.runUntil(120000);
            List<String> from = new ArrayList<String>();
            for (long t : noteTimes(notes, "a lean-in from")) {
                from.add(stateBefore(rig, ExploreBrain.State.CUE_TURN, t));
            }
            boolean ok = true;
            for (String st : from) {
                ok &= st != null && !(st.equals("RETRACE") || st.equals("CIRCLE") || st.equals("WAY_OUT")
                        || st.equals("DRIVE_OFF") || st.equals("STARTLE") || st.equals("BACK_OFF")
                        || st.equals("RECOVER") || st.equals("CORNERED"));
            }
            check(n, entries(rig, ExploreBrain.State.RETRACE).size() >= 1 && ok && rig.violations.isEmpty(),
                    "leanIns from " + from + " notes=" + lastNotes(notes, 20));
        });
    }

    // ---- boxed in: leave the way he came (robot 2026-10-01: minutes under a desk, turning in place) ----

    /**
     * The first leg runs straight at 0 deg (the way in). From then on every heading more than
     * openHalfDeg from 180 is under the desk: forward there goes nowhere and the controller
     * refuses it (CPL=2, our own sensor reading plain floor). openHalfDeg 0: refused everywhere.
     */
    private static Rig boxedRig(List<String> notes, double openHalfDeg) {
        Rig[] h = new Rig[1];
        long[] entered = {-1};
        Rig rig = escRig(escTuning().hopTicks(8).cap(10, 20000, 30000).wedge(10, 2, 1).escapeRetrace(1500, 40), h,
                t -> {
                    Rig r = h[0];
                    if (r == null) {
                        return clear(t);
                    }
                    if (entered[0] < 0 && r.count("hop") > 0 && r.brain.state() == ExploreBrain.State.PAUSE) {
                        entered[0] = t;
                        r.wallAt = 0;
                        r.wallHalfDeg = 180 - openHalfDeg;
                        r.wallFrom = t;
                    }
                    boolean under = entered[0] >= 0 && Math.abs(Heading.delta(r.yaw.wrapped(), 180)) > openHalfDeg;
                    return under && "hop".equals(r.motion) ? cpl2(t) : clear(t);
                }, (r, req, nth) -> CuriosityPort.WayOut.way(0, 0f));
        rig.brain.setTrace(x -> notes.add(h[0].now + " " + x));
        return rig;
    }

    /** Times of the controller's forward refusals: hiccups and CPL hazards. */
    private static List<Long> refusals(List<String> notes) {
        List<Long> out = new ArrayList<Long>(noteTimes(notes, "controller refused forward (CPL)"));
        for (String x : notes) {
            if (x.contains("hazard while") && x.contains(": CPL")) {
                out.add(Long.parseLong(x.substring(0, x.indexOf(' '))));
            }
        }
        java.util.Collections.sort(out);
        return out;
    }

    private static void boxedInScenarios() {
        scenario("boxed_in_three_cpl_refusals_in_60_s_leaves_the_way_he_came", n -> {
            List<String> notes = new ArrayList<String>();
            Rig rig = boxedRig(notes, 30);
            rig.started();
            runUntil(rig, 90000, r -> notedAt(notes, "free after") >= 0);
            rig.runUntil(rig.now + 3000);
            List<Long> cpl = refusals(notes);
            // Review P2-3: a hiccup and CPL again on its retry are one refusal: the third episode counts.
            List<Long> episodes = noteTimes(notes, "controller refused forward (CPL)");
            long third = episodes.size() >= 3 ? episodes.get(2) : -1;
            long boxed = notedAt(notes, "boxed in: 3 forward refusals in ");
            String boxedNote = firstNote(notes, "boxed in: 3 forward refusals in ");
            long retrace = notedAt(notes, "retrace: facing 180 deg for ");
            long free = notedAt(notes, "free after");
            int spins = third < 0 || boxed < 0 ? -1 : turnCommands(rig, third, retrace < 0 ? Long.MAX_VALUE : retrace).size();
            check(n, third > 0 && boxed >= third && boxed - third <= 5000 && boxedNote != null
                            && boxedNote.contains("leaving the way he came (facing 180 deg)") && retrace >= boxed
                            && free > retrace && notedAt(notes, "boxed in: out") >= free
                            && spins <= 1 && entered(rig, ExploreBrain.State.CIRCLE, 0) < 0 && rig.violations.isEmpty(),
                    "cpl=" + cpl + " boxed@" + boxed + " retrace@" + retrace + " free@" + free + " spins=" + spins
                            + " notes=" + lastNotes(notes, 25));
        });
        scenario("boxed_in_a_refused_retrace_falls_back_to_the_escape_ladder", n -> {
            List<String> notes = new ArrayList<String>();
            Rig rig = boxedRig(notes, 0);
            rig.started();
            runUntil(rig, 90000, r -> r.statesSeen.contains(ExploreBrain.State.CIRCLE));
            long boxed = notedAt(notes, "boxed in: ");
            long retrace = notedAt(notes, "retrace: facing 180 deg for ");
            long circle = entered(rig, ExploreBrain.State.CIRCLE, 0);
            check(n, boxed > 0 && retrace >= boxed && circle > retrace && notedAt(notes, "free after") < 0
                            && rig.violations.isEmpty(),
                    "boxed@" + boxed + " retrace@" + retrace + " circle@" + circle + " notes=" + lastNotes(notes, 25));
        });
        scenario("boxed_in_a_full_look_around_finding_nothing_open_leaves_the_way_he_came", n -> {
            // Nothing refuses him, but every look after the way in reads closed all round
            // ("open 0.00" under the desk). Three closed decisions are no longer enough
            // (robot 2026-10-01 15:55: those only covered the half in front of him): he
            // looks all the way round first, and only a full look-around with no heading
            // open over boxedInOpen is boxed in.
            List<String> notes = new ArrayList<String>();
            Rig[] h = new Rig[1];
            Rig rig = escRig(escTuning().hopTicks(8).escapeRetrace(1500, 40), h, CLEAR,
                    (r, req, nth) -> CuriosityPort.WayOut.way(0, 0f));
            long[] entered = {-1};
            rig.openView = (r, t) -> {
                if (entered[0] < 0 && r.count("hop") > 0 && r.brain.state() == ExploreBrain.State.PAUSE) {
                    entered[0] = t;
                }
                return entered[0] < 0 ? prof(0.9f, 0.9f, 0.9f, 0.9f) : prof(0.9f, 0.02f, 0.02f, 0.02f);
            };
            rig.brain.setTrace(x -> notes.add(h[0].now + " " + x));
            rig.started();
            runUntil(rig, 90000, r -> notedAt(notes, "free after") >= 0);
            long around = notedAt(notes, "everything ahead closed: looking around (6 looks)");
            List<Long> looks = notedTimes(notes, "look-around look ");
            long boxed = notedAt(notes, "boxed in: a full look-around found no heading with open over 0.1");
            long retrace = notedAt(notes, "retrace: facing 180 deg for ");
            int turns = around < 0 || boxed < 0 ? -1 : turnCommands(rig, around, boxed).size();
            check(n, around > 0 && looks.size() >= 6 && boxed >= looks.get(5) && turns == 5
                            && notedAt(notes, "boxed in: the steer read") < 0
                            && retrace >= boxed && notedAt(notes, "free after") > retrace && rig.violations.isEmpty(),
                    "around@" + around + " looks=" + looks + " turns=" + turns + " boxed@" + boxed + " retrace@" + retrace
                            + " notes=" + lastNotes(notes, 25));
        });
    }

    // ---- look around when everything in view reads closed (robot 2026-10-01 15:55) ----

    /**
     * A view fixed to the room: each bin reads open(h) for the world heading it looks along
     * (his true yaw plus the bin's bearing, left positive); confident.
     */
    private static OpenView worldView(java.util.function.DoubleUnaryOperator open) {
        return (r, t) -> {
            float[] b = new float[Openness.BINS];
            for (int i = 0; i < b.length; i++) {
                double x = (i + 0.5) / b.length * 2 - 1;
                b[i] = (float) open.applyAsDouble(Heading.wrap(r.yaw.trueDeg - x * r.tuning.cameraHalfFovDeg));
            }
            return new Openness.Profile(b, 0.9f);
        };
    }

    /** The heading in a "looked around: most open at H deg" note, NaN for none. */
    private static double lookedAroundAt(List<String> notes) {
        String x = firstNote(notes, "looked around: most open at ");
        if (x == null) {
            return Double.NaN;
        }
        String rest = x.substring(x.indexOf("most open at ") + 13);
        return Double.parseDouble(rest.substring(0, rest.indexOf(' ')));
    }

    private static void lookAroundScenarios() {
        scenario("look_around_walls_across_the_front_half_turns_to_face_the_open_side_and_drives_there", n -> {
            // Robot 15:55: a wall fills the view and the front 180 deg are closed; behind him
            // the floor is open, with a doorway Claude reports when he faces it.
            Rig rig = doorRig(doorTuning(), CLEAR,
                    worldView(hd -> Math.abs(Heading.delta(hd, 0)) < 90 ? 0.02 : 0.9),
                    (r, k) -> Math.abs(Heading.delta(r.yaw.wrapped(), 180)) <= 60 ? CuriosityPort.Doorway.door(0f)
                            : CuriosityPort.Doorway.none());
            List<String> notes = traced(rig);
            rig.started();
            double[] hopYaw = {Double.NaN};
            long[] hopAt = {-1};
            while (rig.now < 60000 && hopAt[0] < 0) {
                rig.runUntil(rig.now + 10);
                long looked = notedAt(notes, "looked around: most open at ");
                if (looked >= 0) {
                    int hop = rig.firstAfter("hop", looked);
                    if (hop >= 0) {
                        hopAt[0] = rig.timeOf(hop);
                        hopYaw[0] = rig.yaw.wrapped();
                    }
                }
            }
            long around = notedAt(notes, "everything ahead closed: looking around (6 looks)");
            double most = lookedAroundAt(notes);
            check(n, around > 0 && !Double.isNaN(most) && Math.abs(Heading.delta(most, 0)) >= 60
                            && hopAt[0] > around && Math.abs(Heading.delta(hopYaw[0], 0)) > 90
                            && notedAt(notes, "boxed in") < 0 && rig.violations.isEmpty(),
                    "around@" + around + " most=" + f1(most) + " hop@" + hopAt[0] + " yaw=" + f1(hopYaw[0])
                            + " notes=" + lastNotes(notes, 25));
        });
        scenario("look_around_stops_early_on_the_first_step_that_reads_open", n -> {
            // Closed within 40 deg of where he faces; open beyond: the first 60 deg step sees it.
            Rig rig = doorRig(doorTuning(), CLEAR,
                    worldView(hd -> Math.abs(Heading.delta(hd, 0)) < 40 ? 0.02 : 0.9),
                    (r, k) -> CuriosityPort.Doorway.none());
            List<String> notes = traced(rig);
            rig.started();
            runUntil(rig, 30000, r -> notedAt(notes, "looked around: most open at ") >= 0);
            long around = notedAt(notes, "everything ahead closed: looking around (6 looks)");
            long looked = notedAt(notes, "looked around: most open at ");
            String note = firstNote(notes, "looked around: most open at ");
            int turns = around < 0 || looked < 0 ? -1 : turnCommands(rig, around, looked + 1).size();
            check(n, around > 0 && looked > around && note != null && note.contains("2 of 6 looks") && turns == 1
                            && notedTimes(notes, "look-around look ").size() == 2
                            && notedAt(notes, "boxed in") < 0 && rig.violations.isEmpty(),
                    "around@" + around + " looked@" + looked + " turns=" + turns + " notes=" + lastNotes(notes, 20));
        });
        scenario("look_around_nothing_open_with_a_doorway_reported_faces_it_and_takes_a_short_leg", n -> {
            // Robot 2026-10-01 16:35: every look-around look read 0.38 or less, but Claude had
            // reported a doorway (at 90 deg, since expired from the steer) a minute before: he
            // faces it and takes a short leg, not boxed in.
            List<String> notes = new ArrayList<String>();
            Rig[] h = new Rig[1];
            Rig rig = doorRig(doorTuning().doorwayExpire(2000, 100000), CLEAR,
                    (r, t) -> notedAt(notes, "doorway heading expired") < 0 ? prof(0.9f, 0.9f, 0.9f, 0.9f)
                            : prof(0.9f, 0.02f, 0.02f, 0.02f),
                    (r, k) -> k == 1 ? CuriosityPort.Doorway.door(-0.9f) : CuriosityPort.Doorway.none());
            h[0] = rig;
            rig.brain.setTrace(x -> notes.add(h[0].now + " " + x));
            rig.started();
            runUntil(rig, 90000, r -> {
                long f = notedAt(notes, "looked around: nothing open");
                return f >= 0 && firstHop(r, f) != null && firstHop(r, f).end > 0 || notedAt(notes, "boxed in") >= 0;
            });
            double remembered = degAfter(notes, "remembered at ");
            long around = notedAt(notes, "everything ahead closed: looking around (6 looks)");
            String faced = firstNote(notes, "looked around: nothing open");
            long facedAt = notedAt(notes, "looked around: nothing open");
            Drive hop = facedAt < 0 ? null : firstHop(rig, facedAt);
            check(n, around > 0 && faced != null && faced.contains("Claude reported a doorway") && hop != null
                            && near(hop.heading, remembered, 15) && hop.end > 0
                            && hop.end - hop.t <= rig.tuning.steerShortTicks * rig.tuning.hopTickMs + 100
                            && notedAt(notes, "boxed in") < 0 && rig.violations.isEmpty(),
                    "remembered=" + f1(remembered) + " around@" + around + " faced=" + faced + " hop=" + hop
                            + " notes=" + lastNotes(notes, 25));
        });
        scenario("look_around_a_floor_hazard_mid_turn_drops_it_and_the_hazard_rules", n -> {
            // A floor edge reads during the look-around's first turn: the wheels stop on that
            // step as always, and the look-around is dropped (the hazard's escape takes over).
            long[] edgeFrom = {Long.MAX_VALUE};
            Rig[] h = new Rig[1];
            Rig rig = doorRig(doorTuning(), t -> t >= edgeFrom[0] && t < edgeFrom[0] + 300 ? edgeAhead(t) : clear(t),
                    worldView(hd -> 0.02), (r, k) -> CuriosityPort.Doorway.none());
            h[0] = rig;
            List<String> notes = traced(rig);
            rig.started();
            runUntil(rig, 30000, r -> {
                long a = notedAt(notes, "everything ahead closed: looking around");
                if (a >= 0 && edgeFrom[0] == Long.MAX_VALUE && r.brain.state() == ExploreBrain.State.TURN) {
                    edgeFrom[0] = r.now + 10;
                }
                return edgeFrom[0] != Long.MAX_VALUE && r.now > edgeFrom[0] + 3000;
            });
            int stop = rig.firstAfter("stop", edgeFrom[0]);
            long dropped = notedAt(notes, "look-around dropped");
            check(n, edgeFrom[0] != Long.MAX_VALUE && stop >= 0 && rig.timeOf(stop) <= edgeFrom[0] + 100
                            && dropped >= edgeFrom[0] && notedAt(notes, "looked around: most open at ") < 0
                            && rig.violations.isEmpty(),
                    "edge@" + edgeFrom[0] + " stop@" + (stop < 0 ? -1 : rig.timeOf(stop)) + " dropped@" + dropped
                            + " notes=" + lastNotes(notes, 20));
        });
    }

    // ---- the first real seek on the robot (2026-10-01 15:59-16:00) ----

    /** The first forward drive at or after from, else null. */
    private static Drive firstHop(Rig rig, long from) {
        for (Drive d : rig.drives) {
            if (d.t >= from && "hop".equals(d.kind)) {
                return d;
            }
        }
        return null;
    }

    /** The whole degrees in a note after part ("... at 54 deg"), NaN for none. */
    private static double degAfter(List<String> notes, String part) {
        String x = null;
        for (String y : notes) {
            if (y.contains(part)) {
                x = y;
                break;
            }
        }
        if (x == null) {
            return Double.NaN;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(part) + "(-?\\d+) deg")
                .matcher(x);
        return m.find() ? Double.parseDouble(m.group(1)) : Double.NaN;
    }

    private static void robotSeekScenarios() {
        scenario("seek_a_doorway_reported_during_the_seek_becomes_its_target_and_he_drives_toward_it", n -> {
            // Robot 16:00:23-24: Claude picked a frame, then reported an open doorway; he
            // should head for the doorway, not argue between the two.
            Rig rig = seekRig(seekTuning().doorwayAsk(5000, 3000), (r, t) -> scene(1),
                    (r, req, k) -> CuriosityPort.WayOut.way(2, 0f));
            rig.doorways = (r, k) -> r.brain.seeking() ? CuriosityPort.Doorway.door(0.77f) : CuriosityPort.Doorway.none();
            List<String> notes = traced(rig);
            rig.started();
            runUntil(rig, 400000, r -> {
                long at = notedAt(notes, "seeking: heading for the doorway at ");
                return at >= 0 && firstHop(r, at) != null || notedAt(notes, "seeking: gave up") >= 0;
            });
            double remembered = degAfter(notes, "remembered at ");
            double target = degAfter(notes, "seeking: heading for the doorway at ");
            long at = notedAt(notes, "seeking: heading for the doorway at ");
            Drive hop = at < 0 ? null : firstHop(rig, at);
            long gaveUp = notedAt(notes, "seeking: gave up");
            check(n, !Double.isNaN(target) && near(target, remembered, 1.5) && hop != null
                            && near(hop.heading, target, 20) && (gaveUp < 0 || gaveUp > hop.t) && rig.violations.isEmpty(),
                    "remembered=" + f1(remembered) + " target=" + f1(target) + " hop=" + (hop == null ? "none"
                            : hop.t + "@" + f1(hop.heading)) + " gaveUp@" + gaveUp + " notes=" + lastNotes(notes, 25));
        });
        scenario("seek_a_none_answer_logs_the_fallback_and_no_second_seek_within_seek_gap_ms", n -> {
            // Robot 15:59:40: Claude answered NONE, no fallback was logged and no gap applied,
            // so he asked again 42 s later. Every stop's looks read closed: the fallback finds
            // nowhere, says so, and the gap (60 s here) holds.
            Rig rig = seekRig(seekTuning(), (r, t) -> scene(1), (r, req, k) -> CuriosityPort.WayOut.none());
            rig.openView = (r, t) -> r.brain.state() == ExploreBrain.State.SCAN ? prof(0.9f, 0.1f, 0.1f, 0.1f)
                    : prof(0.9f, 0.9f, 0.9f, 0.9f);
            List<String> notes = traced(rig);
            rig.started();
            runUntil(rig, 400000, r -> notedAt(notes, "seeking: asking Claude where to go") >= 0);
            long asked = notedAt(notes, "seeking: asking Claude where to go");
            rig.runUntil(asked + 59000);
            long fell = notedAt(notes, "seeking: Claude sees nowhere new");
            int asks = notedTimes(notes, "seeking: asking Claude where to go").size();
            check(n, asked > 0 && fell >= asked && asks == 1 && rig.violations.isEmpty(),
                    "asked@" + asked + " fell@" + fell + " asks=" + asks + " notes=" + lastNotes(notes, 20));
        });
        scenario("seek_claudes_target_blocked_with_an_open_band_30_deg_left_still_drives_the_target", n -> {
            // Robot 2026-10-01 16:35: openness is not trusted over Claude's pick. The target heading
            // reads blocked when he faces it, open floor lies 30 deg left of it: he drives short
            // legs at the target itself, not the open band, and does not give up.
            double[] w = {Double.NaN};
            double[] target = {Double.NaN};
            Rig rig = seekRig(seekTuning(), (r, t) -> scene(1), (r, req, k) -> CuriosityPort.WayOut.way(1, 0f));
            rig.openView = (r, t) -> {
                double sh = r.brain.seekHeading();
                if (Double.isNaN(sh) && Double.isNaN(w[0])) {
                    return prof(0.9f, 0.9f, 0.9f, 0.9f);
                }
                if (Double.isNaN(w[0])) {
                    target[0] = sh;
                    w[0] = Heading.wrap(sh + 30);
                }
                float[] b = new float[Openness.BINS];
                for (int i = 0; i < b.length; i++) {
                    double offset = (i + 0.5) / b.length * 2 - 1;
                    double world = r.yaw.wrapped() + exactBearing(offset);
                    b[i] = Math.abs(Heading.delta(world, w[0])) <= 6 ? 0.9f : 0.1f;
                }
                return new Openness.Profile(b, 0.9f);
            };
            List<String> notes = traced(rig);
            rig.started();
            runUntil(rig, 400000, r -> {
                long at = notedAt(notes, "trusting Claude's pick and driving");
                return at >= 0 && firstHop(r, at) != null && firstHop(r, at).end > 0
                        || notedAt(notes, "seeking: gave up") >= 0;
            });
            String trusted = firstNote(notes, "seeking: openness reads the target blocked (open ");
            long at = notedAt(notes, "trusting Claude's pick and driving");
            Drive hop = at < 0 ? null : firstHop(rig, at);
            long gaveUp = notedAt(notes, "seeking: gave up");
            check(n, at > 0 && trusted != null && trusted.contains("(open 0.10); trusting Claude's pick and driving")
                            && hop != null && near(hop.heading, target[0], 12) && !near(hop.heading, w[0], 15)
                            && hop.end - hop.t <= rig.tuning.steerShortTicks * rig.tuning.hopTickMs + 100
                            && notedAt(notes, "aiming at the open band") < 0 && gaveUp < 0 && rig.violations.isEmpty(),
                    "target=" + f1(target[0]) + " open=" + f1(w[0]) + " at@" + at + " hop=" + hop
                            + " gaveUp@" + gaveUp + " notes=" + lastNotes(notes, 25));
        });
        scenario("seek_claudes_doorway_reading_open_zero_with_clear_floor_drives_to_it_and_arrives", n -> {
            // Robot 2026-10-01 16:35: Claude saw the doorway at x 0.77; every bin read 0.00 on the
            // open hallway carpet. The floor is clear: he drives short legs toward it and arrives.
            long[] trustAt = {-1};
            Rig rig = seekRig(seekTuning().doorwayAsk(5000, 3000), null, (r, req, k) -> CuriosityPort.WayOut.way(2, 0f));
            List<String> notes = traced(rig);
            rig.placeView = (r, t) -> {
                long door = notedAt(notes, "seeking: heading for the doorway at ");
                long tr = door < 0 ? -1 : noteAt(notes, "seeking: openness reads the target blocked", door + 1);
                return tr >= 0 && r.countPrefix("stop", firstHopT(r, tr), t) >= 3 ? scene(9) : scene(1);
            };
            rig.doorways = (r, k) -> r.brain.seeking() ? CuriosityPort.Doorway.door(0.77f) : CuriosityPort.Doorway.none();
            rig.openView = (r, t) -> r.brain.seeking() ? prof(0.9f, 0f, 0f, 0f) : prof(0.9f, 0.9f, 0.9f, 0.9f);
            rig.started();
            runUntil(rig, 400000, r -> notedAt(notes, "seeking: arrived") >= 0 || notedAt(notes, "seeking: gave up") >= 0);
            double target = degAfter(notes, "seeking: heading for the doorway at ");
            long door = notedAt(notes, "seeking: heading for the doorway at ");
            long arrived = notedAt(notes, "seeking: arrived");
            String trusted = null;
            for (String x : notes) {
                if (x.contains("seeking: openness reads the target blocked") && Long.parseLong(x.substring(0, x.indexOf(' '))) > door) {
                    trusted = x;
                    break;
                }
            }
            int legs = 0;
            boolean onCourse = true;
            boolean short_ = true;
            for (Drive d : rig.drives) {
                if (door >= 0 && d.t > door && d.t < arrived && "hop".equals(d.kind)) {
                    legs++;
                    onCourse &= near(d.heading, target, 20);
                    short_ &= d.end > 0 && d.end - d.t <= rig.tuning.steerShortTicks * rig.tuning.hopTickMs + 100;
                }
            }
            check(n, door > 0 && trusted != null && trusted.contains("(open 0.00); trusting Claude's pick and driving")
                            && arrived > door && legs >= 2 && onCourse && short_
                            && notedAt(notes, "seeking: gave up") < 0 && rig.violations.isEmpty(),
                    "target=" + f1(target) + " door@" + door + " arrived@" + arrived + " legs=" + legs + " onCourse="
                            + onCourse + " short=" + short_ + " trusted=" + trusted + " notes=" + lastNotes(notes, 25));
        });
        scenario("seek_claudes_doorway_reading_blocked_with_a_tof_obstacle_at_1_m_stops_on_the_hazard_then_the_seek_blocked_handling_runs", n -> {
            // As above, but a real obstacle stands toward the doorway (and 45 deg either side of it):
            // the floor sensor stops him as on any leg, he tries 45 deg off the target, is stopped
            // again, and the seek gives up.
            List<String> notes = new ArrayList<String>();
            long[] first = {-1};
            Rig[] h = new Rig[1];
            double[] target = {Double.NaN};
            Rig rig = new Rig(seekTuning().doorwayAsk(5000, 3000).build(), t -> {
                Rig r = h[0];
                boolean toward = r != null && !Double.isNaN(target[0]) && r.brain.seeking()
                        && Math.abs(Heading.delta(r.yaw.wrapped(), target[0])) <= 60
                        && notedAt(notes, "trusting Claude's pick and driving") >= 0;
                if (toward && first[0] < 0) {
                    first[0] = t;
                }
                return toward ? obstacle(t) : clear(t);
            }, NOTHING, true, (r, req, nth) -> CuriosityPort.Answer.nothing());
            h[0] = rig;
            rig.placeView = (r, t) -> scene(1);
            rig.seeks = (r, req, k) -> CuriosityPort.WayOut.way(2, 0f);
            rig.simWheels = true;
            rig.doorways = (r, k) -> r.brain.seeking() ? CuriosityPort.Doorway.door(0.77f) : CuriosityPort.Doorway.none();
            rig.openView = (r, t) -> r.brain.seeking() ? prof(0.9f, 0f, 0f, 0f) : prof(0.9f, 0.9f, 0.9f, 0.9f);
            rig.brain.setTrace(x -> {
                notes.add(h[0].now + " " + x);
                if (x.startsWith("seeking: heading for the doorway at ")) {
                    target[0] = h[0].brain.seekHeading();
                }
            });
            rig.started();
            runUntil(rig, 400000, r -> notedAt(notes, "seeking: gave up") >= 0 || notedAt(notes, "seeking: arrived") >= 0);
            int stop = first[0] < 0 ? -1 : rig.firstAfter("stop", first[0]);
            long hazard = first[0] < 0 ? -1 : noteAt(notes, "hazard", first[0]);
            long detour = notedAt(notes, "seeking: blocked toward the target");
            String gaveUp = firstNote(notes, "seeking: gave up");
            long gaveAt = notedAt(notes, "seeking: gave up");
            boolean towardAfter = false;
            for (Drive d : rig.drives) {
                towardAfter |= "hop".equals(d.kind) && d.t > detour && detour > 0 && d.t < gaveAt
                        && near(d.heading, target[0], 20);
            }
            check(n, first[0] > 0 && hazard >= first[0] && hazard <= first[0] + 1500
                            && detour >= hazard && gaveUp != null && gaveUp.contains("blocked") && gaveAt > detour
                            && !towardAfter && notedAt(notes, "seeking: arrived") < 0 && rig.violations.isEmpty(),
                    "target=" + f1(target[0]) + " first@" + first[0] + " stop@" + (stop < 0 ? -1 : rig.timeOf(stop))
                            + " hazard@" + hazard + " detour@" + detour + " gaveUp=" + gaveUp + " towardAfter=" + towardAfter
                            + " notes=" + lastNotes(notes, 30));
        });
        scenario("seek_the_least_familiar_fallback_target_reading_blocked_still_gives_up", n -> {
            // Claude sees nowhere new: the fallback (the least familiar frame) is not Claude's pick,
            // so its reading blocked when faced ends the seek as before.
            Rig rig = seekRig(seekTuning(), (r, t) -> scene(1), (r, req, k) -> CuriosityPort.WayOut.none());
            rig.openView = (r, t) -> Double.isNaN(r.brain.seekHeading()) ? prof(0.9f, 0.9f, 0.9f, 0.9f)
                    : prof(0.9f, 0.02f, 0.02f, 0.02f);
            List<String> notes = traced(rig);
            rig.started();
            runUntil(rig, 400000, r -> notedAt(notes, "seeking: gave up") >= 0);
            long fell = notedAt(notes, "seeking: Claude sees nowhere new: least familiar frame");
            String gaveUp = firstNote(notes, "seeking: gave up");
            check(n, fell > 0 && gaveUp != null && gaveUp.contains("the target reads blocked, nothing open within 45 deg of it")
                            && notedAt(notes, "trusting Claude") < 0 && rig.violations.isEmpty(),
                    "fell@" + fell + " gaveUp=" + gaveUp + " notes=" + lastNotes(notes, 20));
        });
    }

    /** The time of the first forward drive at or after from, or from itself when none yet. */
    private static long firstHopT(Rig rig, long from) {
        Drive d = from < 0 ? null : firstHop(rig, from);
        return d == null ? Long.MAX_VALUE : d.t;
    }

    private static void cueScenarios() {
        scenario("cue_strong_from_the_left_mid_hop_stops_within_one_step_and_turns_left", n -> {
            Rig rig = cueRig(EMPTY_ROOM).started();
            long hop = runUntilEvent(rig, "hop", 0, 20000);
            long cueT = hop + 200;
            rig.cue(cueT, Ears.Kind.NAME, Ears.Side.LEFT, -70f);
            rig.runUntil(cueT + 3000);
            int stop = rig.firstAfter("stop", cueT);
            int glance = rig.firstAfter("eyes GLANCE LEFT", cueT);
            int turn = rig.firstAfter("turn", cueT);
            // A name is a call: the wheels stop and the answer clip plays in the step that takes it (KTD3).
            check(n, hop > 0 && rig.timeOf(stop) == cueT && entered(rig, ExploreBrain.State.CUE_TURN, cueT) == cueT
                            && answerAt(rig, cueT) == cueT && rig.timeOf(rig.firstAfter("clip 600", cueT)) == cueT
                            && rig.timeOf(glance) == cueT && turn >= 0 && rig.what(turn).equals("turn LEFT")
                            && rig.timeOf(turn) <= cueT + 700 && rig.countPrefix("hop", cueT + 1, cueT + 3001) == 0
                            && rig.stamped(ExploreBrain.Gauges.Stage.CUE_AT) == cueT
                            && rig.counted(ExploreBrain.Gauges.Counter.CUES) == 1
                            && rig.counted(ExploreBrain.Gauges.Counter.STRONG_CUES) == 1
                            && rig.counted(ExploreBrain.Gauges.Counter.SEARCHES) == 1 && rig.violations.isEmpty(),
                    "hop@" + hop + " stop@" + rig.timeOf(stop) + " turn=" + rig.what(turn) + "@" + rig.timeOf(turn)
                            + " " + gauges(rig) + " " + rig.tail());
        });
        scenario("cue_weak_with_no_face_after_the_look_and_its_opposite_resumes_quietly_within_the_budget", n -> {
            Rig rig = cueRig(EMPTY_ROOM).started();
            rig.cue(400, Ears.Tier.WEAK, Ears.Side.RIGHT, Float.NaN);
            rig.runUntil(400);
            long search = entered(rig, ExploreBrain.State.CUE_TURN, 400);
            long pause = runUntilState(rig, ExploreBrain.State.PAUSE, 401, 30000);
            List<Long> looks = entries(rig, ExploreBrain.State.CUE_LOOK);
            int firstTurn = rig.firstAfter("turn", 400);
            // Two looks (that side, the opposite), each up to leanInMs after the camera is ready,
            // two measured turns of 90 and 180 deg at 60 deg/s, and the glance lead: under 16 s.
            check(n, search == 400 && looks.size() == 2 && pause > 0 && pause <= 400 + 16000
                            && rig.what(firstTurn).equals("turn RIGHT") && rig.count("ask") == 0
                            && rig.count("match") == 0 && rig.count("lines") == 0 && rig.countPrefix("say", 0, pause + 1) == 0
                            && rig.countPrefix("clip", 0, pause + 1) == 0
                            && rig.counted(ExploreBrain.Gauges.Counter.LEAN_INS) == 1
                            && rig.counted(ExploreBrain.Gauges.Counter.WEAK_CUES) == 1
                            && rig.counted(ExploreBrain.Gauges.Counter.QUIET_RESUMES) == 1
                            && rig.counted(ExploreBrain.Gauges.Counter.FACES_FOUND) == 0 && rig.violations.isEmpty(),
                    "search@" + search + " looks=" + looks + " pause@" + pause + " " + gauges(rig) + " " + rig.tail());
        });
        scenario("cue_weak_that_finds_two_profile_faces_resumes_quietly", n -> {
            Detection a = profileOf(box("person", 0.9f, 0.35f, 0.5f, 0.3f, 0.4f));
            Detection b = profileOf(box("person", 0.9f, 0.7f, 0.5f, 0.3f, 0.5f));
            Rig rig = cueRig((r, t) -> list(a, b)).started();
            rig.cue(400, Ears.Tier.WEAK, Ears.Side.LEFT, -80f);
            rig.runUntil(400);
            long pause = runUntilState(rig, ExploreBrain.State.PAUSE, 401, 30000);
            boolean sawLooks = false;
            for (Event e : rig.log) {
                sawLooks |= e.t > 400 && e.t < pause && e.what.equals("camera open");
            }
            check(n, pause > 0 && entries(rig, ExploreBrain.State.CUE_LOOK).size() == 2
                            && rig.count("match") == 0 && rig.countPrefix("clip", 0, pause + 1) == 0
                            && rig.countPrefix("say", 0, pause + 1) == 0
                            && rig.counted(ExploreBrain.Gauges.Counter.FACES_FOUND) == 0
                            && rig.counted(ExploreBrain.Gauges.Counter.QUIET_RESUMES) == 1
                            && rig.stamped(ExploreBrain.Gauges.Stage.FACE_FOUND) < 0 && rig.violations.isEmpty(),
                    "pause@" + pause + " looks=" + entries(rig, ExploreBrain.State.CUE_LOOK) + " sawLooks=" + sawLooks
                            + " " + gauges(rig) + " " + rig.tail());
        });
        scenario("cue_strong_from_behind_is_found_on_the_third_look", n -> {
            // The mics said left; the voice was over his right shoulder (100 deg right). The
            // side look (90 left) and the rear look (180) miss; the other side (90 right) sees them.
            // A greeting: a call looks round a circle instead (hey-miko plan KTD6, call_ae4_...).
            Rig rig = cueRig(personAt(-100, 25)).started();
            rig.cue(400, Ears.Kind.GREETING, Ears.Side.LEFT, Float.NaN);
            rig.runUntil(400);
            long match = runUntilEvent(rig, "match", 400, 40000);
            List<Long> looks = entries(rig, ExploreBrain.State.CUE_LOOK);
            double facing = rig.yaw.trueDeg;
            check(n, match > 0 && looks.size() == 3 && rig.count("match") == 1
                            && Math.abs(Heading.delta(rig.yaw.wrapped(), Heading.wrap(-100))) <= 30
                            && rig.stamped(ExploreBrain.Gauges.Stage.FACE_FOUND) > looks.get(2)
                            && rig.counted(ExploreBrain.Gauges.Counter.FACES_FOUND) == 1
                            && rig.counted(ExploreBrain.Gauges.Counter.QUIET_RESUMES) == 0 && rig.violations.isEmpty(),
                    "match@" + match + " looks=" + looks + " facing=" + f1(facing) + " " + gauges(rig) + " " + rig.tail());
        });
        scenario("cue_miko_miko_800_ms_apart_is_one_search_and_the_second_is_the_caller_talking", n -> {
            Rig rig = cueRig(EMPTY_ROOM).started();
            rig.cue(400, Ears.Kind.NAME, Ears.Side.LEFT, -60f);
            rig.cue(1200, Ears.Kind.NAME, Ears.Side.LEFT, -60f);
            rig.runUntil(6000);
            // One search, one answer: a name is a call, and a second call from the same side during
            // the turn toward it is the caller talking (owner 2026-09-30): the turn finishes, then the
            // call's meeting opens, where it used to re-aim the search (KTD6).
            long meet = entered(rig, ExploreBrain.State.MEET, 1200);
            check(n, rig.counted(ExploreBrain.Gauges.Counter.SEARCHES) == 1 && rig.count("react answer") == 1
                            && rig.counted(ExploreBrain.Gauges.Counter.CUES) == 2
                            && rig.counted(ExploreBrain.Gauges.Counter.CUES_DROPPED) == 0
                            && rig.counted(ExploreBrain.Gauges.Counter.RETARGETS) == 0
                            && meet > 1200 && entered(rig, ExploreBrain.State.CUE_LOOK, 400) < 0
                            && rig.countPrefix("turn LEFT", 400, 3500) > 0 && rig.countPrefix("turn RIGHT", 400, 3500) == 0
                            && rig.count("eyes GLANCE RIGHT") == 0 && rig.violations.isEmpty(),
                    "turns=" + entries(rig, ExploreBrain.State.CUE_TURN) + " " + gauges(rig) + " " + rig.tail());
        });
        scenario("cue_strong_from_the_opposite_side_during_the_turn_retargets_once_not_twice", n -> {
            // Greetings: a call with an angle during a call's search retargets every time (hey-miko
            // plan KTD6, call_during_its_search_...).
            Rig rig = cueRig(EMPTY_ROOM).started();
            rig.cue(400, Ears.Kind.GREETING, Ears.Side.LEFT, -90f);
            rig.cue(1400, Ears.Kind.GREETING, Ears.Side.RIGHT, 90f);
            rig.cue(2400, Ears.Kind.GREETING, Ears.Side.LEFT, -90f);
            rig.runUntil(6000);
            int afterFirst = rig.firstAfter("turn", 1400);
            int afterSecond = rig.firstAfter("turn", 2400);
            check(n, rig.count("eyes GLANCE LEFT") == 1 && rig.count("eyes GLANCE RIGHT") == 1
                            && rig.what(afterFirst).equals("turn RIGHT") && rig.timeOf(afterFirst) <= 2400
                            && (afterSecond < 0 || rig.what(afterSecond).equals("turn RIGHT"))
                            && rig.counted(ExploreBrain.Gauges.Counter.RETARGETS) == 1
                            && rig.counted(ExploreBrain.Gauges.Counter.CUES_DROPPED) == 1 && rig.violations.isEmpty(),
                    "afterFirst=" + rig.what(afterFirst) + "@" + rig.timeOf(afterFirst) + " afterSecond="
                            + rig.what(afterSecond) + " " + gauges(rig) + " " + rig.tail());
        });
        scenario("cue_during_ask_cancels_the_ask", n -> {
            // Claude never answers the look request, so ASK lasts its 4 s try.
            Rig rig = new Rig(claudeTuning().gyro(robotGyro()).cueTurn(45, 90, 500, 2000, 600).build(), CLEAR,
                    EMPTY_ROOM, true, (r, req, nth) -> null);
            rig.started();
            long ask = runUntilEvent(rig, "ask", 0, 20000);
            long cueT = ask + 500;
            rig.cue(cueT, Ears.Kind.GREETING, Ears.Side.RIGHT, 60f);
            rig.runUntil(cueT + 2000);
            int cancel = rig.firstAfter("cancel ask", cueT);
            check(n, ask > 0 && rig.timeOf(cancel) == cueT && entered(rig, ExploreBrain.State.CUE_TURN, cueT) == cueT
                            && "ASK".equals(stateBefore(rig, ExploreBrain.State.CUE_TURN, cueT)) && rig.violations.isEmpty(),
                    "ask@" + ask + " cancel@" + rig.timeOf(cancel) + " " + rig.tail());
        });
        scenario("cue_during_a_playing_line_is_held_and_taken_when_it_ends", n -> {
            Rig rig = new Rig(claudeTuning().gyro(robotGyro()).cueTurn(45, 90, 500, 2000, 600).build(), CLEAR,
                    EMPTY_ROOM, true, (r, req, nth) -> pick(0, "lamp", CuriosityPort.Kind.OTHER, "What a shiny lamp!",
                            0.5f, 0.5f, 0.2f, 0.3f));
            rig.started();
            long say = runUntilEvent(rig, "say What a shiny lamp!", 0, 30000);
            long cueT = say + 300;
            rig.cue(cueT, Ears.Kind.NAME, Ears.Side.LEFT, -60f);
            rig.runUntil(say + 3000);
            long search = entered(rig, ExploreBrain.State.CUE_TURN, cueT);
            check(n, say > 0 && search >= say + rig.speechMs && search <= say + rig.speechMs + 120
                            && answerAt(rig, cueT) == search && rig.count("say What a shiny lamp!") == 1
                            && rig.counted(ExploreBrain.Gauges.Counter.CUES_HELD) == 1
                            && rig.counted(ExploreBrain.Gauges.Counter.SEARCHES) == 1 && rig.violations.isEmpty(),
                    "say@" + say + " search@" + search + " " + gauges(rig) + " " + rig.tail());
        });
        scenario("cue_before_the_line_starts_drops_the_remark_and_turns", n -> {
            Rig rig = new Rig(claudeTuning().gyro(robotGyro()).cueTurn(45, 90, 500, 2000, 600).build(), CLEAR,
                    EMPTY_ROOM, true, (r, req, nth) -> pick(0, "lamp", CuriosityPort.Kind.OTHER, "What a shiny lamp!",
                            0.5f, 0.5f, 0.2f, 0.3f));
            // The camera closed at ASK; a detector run still in flight long after it means the
            // line waits at SPEAK (quietWaitMs 1500), and the cue lands in that wait.
            rig.detectorTailMs = 6000;
            rig.started();
            long speak = runUntilState(rig, ExploreBrain.State.SPEAK, 0, 30000);
            long cueT = speak + 300;
            rig.cue(cueT, Ears.Kind.NAME, Ears.Side.LEFT, -60f);
            rig.runUntil(speak + 4000);
            check(n, speak > 0 && rig.count("say What a shiny lamp!") == 0
                            && entered(rig, ExploreBrain.State.CUE_TURN, cueT) == cueT && answerAt(rig, cueT) == cueT
                            && rig.counted(ExploreBrain.Gauges.Counter.CUES_HELD) == 0 && rig.violations.isEmpty(),
                    "speak@" + speak + " " + gauges(rig) + " " + rig.tail());
        });
        scenario("cue_during_orient_is_held_until_the_remark_is_said", n -> {
            // A voice that is not a call no longer cancels a stop with a pick: the remark comes
            // first, then the held cue is taken (the remark rate, owner 2026-10-01).
            Rig rig = new Rig(claudeTuning().gyro(robotGyro()).scanTurnDeg(40).cueTurn(45, 90, 500, 2000, 600).build(),
                    CLEAR, EMPTY_ROOM, true, (r, req, nth) -> pick(0, "lamp", CuriosityPort.Kind.OTHER,
                            "What a shiny lamp!", 0.5f, 0.5f, 0.2f, 0.3f));
            rig.started();
            long orient = runUntilState(rig, ExploreBrain.State.ORIENT, 0, 30000);
            long cueT = orient + 300;
            rig.cue(cueT, Ears.Kind.GREETING, Ears.Side.RIGHT, 70f);
            rig.runUntil(orient + 8000);
            int say = rig.first("say What a shiny lamp!", 0);
            long search = entered(rig, ExploreBrain.State.CUE_TURN, cueT);
            check(n, orient > 0 && say >= 0 && search > rig.timeOf(say)
                            && rig.counted(ExploreBrain.Gauges.Counter.CUES_HELD) == 1
                            && rig.count("react answer") == 0 && rig.violations.isEmpty(),
                    "orient@" + orient + " say@" + (say < 0 ? -1 : rig.timeOf(say)) + " search@" + search + " "
                            + gauges(rig) + " " + rig.tail());
        });
        scenario("weak_cue_before_the_line_starts_still_says_the_remark", n -> {
            Rig rig = new Rig(claudeTuning().gyro(robotGyro()).cueTurn(45, 90, 500, 2000, 600).build(), CLEAR,
                    EMPTY_ROOM, true, (r, req, nth) -> pick(0, "lamp", CuriosityPort.Kind.OTHER, "What a shiny lamp!",
                            0.5f, 0.5f, 0.2f, 0.3f));
            // As in cue_before_the_line_starts_...: the line waits at SPEAK, and a weak voice lands in that wait.
            rig.detectorTailMs = 6000;
            rig.started();
            long speak = runUntilState(rig, ExploreBrain.State.SPEAK, 0, 30000);
            // The detector goes quiet after the cue, inside the quiet wait: the line starts then.
            rig.detectorTailMs = speak + 800 - rig.closedAt;
            long cueT = speak + 300;
            rig.cue(cueT, Ears.Tier.WEAK, Ears.Side.LEFT, -60f);
            rig.runUntil(speak + 6000);
            int say = rig.first("say What a shiny lamp!", 0);
            long search = entered(rig, ExploreBrain.State.CUE_TURN, cueT);
            check(n, speak > 0 && say >= 0 && rig.timeOf(say) > cueT && (search < 0 || search > rig.timeOf(say))
                            && rig.counted(ExploreBrain.Gauges.Counter.CUES_HELD) == 1
                            && rig.counted(ExploreBrain.Gauges.Counter.REMARKS) == 1
                            && rig.count("react answer") == 0 && rig.violations.isEmpty(),
                    "speak@" + speak + " say@" + (say < 0 ? -1 : rig.timeOf(say)) + " search@" + search + " "
                            + gauges(rig) + " " + rig.tail());
        });
        scenario("call_during_orient_drops_the_pick_and_is_answered", n -> {
            Rig rig = new Rig(claudeTuning().gyro(robotGyro()).scanTurnDeg(40).cueTurn(45, 90, 500, 2000, 600).build(),
                    CLEAR, EMPTY_ROOM, true, (r, req, nth) -> pick(0, "lamp", CuriosityPort.Kind.OTHER,
                            "What a shiny lamp!", 0.5f, 0.5f, 0.2f, 0.3f));
            rig.started();
            long orient = runUntilState(rig, ExploreBrain.State.ORIENT, 0, 30000);
            long cueT = orient + 300;
            rig.cue(cueT, Ears.Kind.WAKE_WORD, Ears.Side.RIGHT, 70f);
            rig.runUntil(orient + 8000);
            check(n, orient > 0 && answerAt(rig, cueT) == cueT && entered(rig, ExploreBrain.State.CUE_TURN, cueT) == cueT
                            && "ORIENT".equals(stateBefore(rig, ExploreBrain.State.CUE_TURN, cueT))
                            && rig.count("say What a shiny lamp!") == 0 && rig.violations.isEmpty(),
                    "orient@" + orient + " answer@" + answerAt(rig, cueT) + " " + rig.tail());
        });
        scenario("cue_during_startle_is_held_until_pause", n -> {
            // A weak voice keeps the held-cue rule; a call waits only for the back-off (call_ae2_...).
            Rig rig = cueRig(cueTuning(), t -> t >= 1500 && t < 1700 ? edgeAhead(t) : clear(t), EMPTY_ROOM).started();
            long startle = runUntilState(rig, ExploreBrain.State.STARTLE, 0, 20000);
            long cueT = startle + 100;
            rig.cue(cueT, Ears.Tier.WEAK, Ears.Side.LEFT, -60f);
            long search = runUntilState(rig, ExploreBrain.State.CUE_TURN, cueT, 30000);
            // The escape (startle, back-off, the turn away) runs to its end first; the cue is
            // taken in the very tick the escape's PAUSE begins, at the escape turn's stop.
            int escapeTurn = rig.firstAfter("turn", startle);
            int escapeStop = rig.firstAfter("stop", rig.timeOf(escapeTurn));
            check(n, startle > 0 && search > 0 && escapeTurn >= 0 && rig.timeOf(escapeTurn) < search
                            && rig.timeOf(escapeStop) == search && "TURN".equals(stateBefore(rig, ExploreBrain.State.CUE_TURN, cueT))
                            && rig.counted(ExploreBrain.Gauges.Counter.CUES_HELD) == 1 && rig.count("react answer") == 0
                            && rig.violations.isEmpty(),
                    "startle@" + startle + " escapeTurn@" + rig.timeOf(escapeTurn) + " search@" + search + " "
                            + gauges(rig) + " " + rig.tail());
        });
        scenario("cue_during_cornered_rest_is_taken", n -> {
            // An edge whichever way he turns until he rests cornered; clear floor from then on.
            final Rig[] h = new Rig[1];
            Rig rig = cueRig(cueTuning(), t -> t >= 1600 && (h[0] == null
                    || !h[0].statesSeen.contains(ExploreBrain.State.CORNERED)) ? edgeAhead(t) : clear(t), EMPTY_ROOM);
            h[0] = rig;
            rig.started();
            long rest = runUntilState(rig, ExploreBrain.State.CORNERED, 0, 60000);
            long cueT = rest + 500;
            rig.cue(cueT, Ears.Kind.NAME, Ears.Side.LEFT, -60f);
            rig.runUntil(cueT + 2000);
            check(n, rest > 0 && entered(rig, ExploreBrain.State.CUE_TURN, cueT) == cueT
                            && "CORNERED".equals(stateBefore(rig, ExploreBrain.State.CUE_TURN, cueT))
                            && rig.firstAfter("turn", cueT) >= 0 && rig.violations.isEmpty(),
                    "rest@" + rest + " " + rig.tail());
        });
        scenario("cue_same_side_during_approach_continues_it", n -> {
            Rig rig = cueRig(cueTuning(), CLEAR, personWhen(t -> true)).started();
            long approach = runUntilState(rig, ExploreBrain.State.APPROACH, 0, 20000);
            long cueT = approach + 300;
            rig.cue(cueT, Ears.Kind.NAME, Ears.Side.LEFT, -15f);
            rig.runUntil(cueT + 12000);
            int match = rig.firstAfter("match", cueT);
            check(n, approach > 0 && entered(rig, ExploreBrain.State.CUE_TURN, cueT) < 0 && match > 0
                            && answerAt(rig, cueT) == cueT
                            && rig.what(rig.firstAfter("say ", cueT)).equals("say Hi Sarah!")
                            && rig.counted(ExploreBrain.Gauges.Counter.CUES) == 1
                            && rig.counted(ExploreBrain.Gauges.Counter.SEARCHES) == 0 && rig.violations.isEmpty(),
                    "approach@" + approach + " match@" + rig.timeOf(match) + " " + gauges(rig) + " " + rig.tail());
        });
        scenario("cue_opposite_side_during_approach_abandons_it_and_turns", n -> {
            Rig rig = cueRig(cueTuning(), CLEAR, personWhen(t -> true)).started();
            long approach = runUntilState(rig, ExploreBrain.State.APPROACH, 0, 20000);
            long cueT = approach + 300;
            rig.cue(cueT, Ears.Kind.NAME, Ears.Side.RIGHT, 120f);
            rig.runUntil(cueT + 3000);
            int turn = rig.firstAfter("turn", cueT);
            check(n, approach > 0 && entered(rig, ExploreBrain.State.CUE_TURN, cueT) == cueT
                            && "APPROACH".equals(stateBefore(rig, ExploreBrain.State.CUE_TURN, cueT))
                            && rig.what(turn).equals("turn RIGHT") && rig.countPrefix("match", 0, cueT + 1) == 0
                            && rig.counted(ExploreBrain.Gauges.Counter.SEARCHES) == 1 && rig.violations.isEmpty(),
                    "approach@" + approach + " turn=" + rig.what(turn) + " " + gauges(rig) + " " + rig.tail());
        });
        scenario("cue_shove_while_stopped_arms_a_weak_cue_and_looks_ahead_then_behind", n -> {
            Rig rig = cueRig(EMPTY_ROOM).started();
            long hop = runUntilEvent(rig, "hop", 0, 20000);
            long stop = runUntilEvent(rig, "stop", hop, 20000);
            long shove = stop + 600;
            rig.shoveAt(shove, 900);
            rig.runUntil(shove + 200);
            long look = entered(rig, ExploreBrain.State.CUE_LOOK, shove);
            long pause = runUntilState(rig, ExploreBrain.State.PAUSE, shove + 1, 30000);
            List<Long> looks = entries(rig, ExploreBrain.State.CUE_LOOK);
            // No side to turn to: the look starts in the shove's own tick (the state log never sees CUE_TURN).
            check(n, stop > 0 && look == shove && rig.countPrefix("turn", shove, look + 1) == 0 && looks.size() == 2
                            && rig.firstAfter("turn", look) >= 0 && pause > 0
                            && rig.shovedStamps.equals(java.util.Arrays.asList(shove))
                            && rig.counted(ExploreBrain.Gauges.Counter.SHOVES) == 1
                            && rig.counted(ExploreBrain.Gauges.Counter.LEAN_INS) == 1
                            && rig.counted(ExploreBrain.Gauges.Counter.WEAK_CUES) == 1 && rig.violations.isEmpty(),
                    "stop@" + stop + " look@" + look + " looks=" + looks + " pause@" + pause + " " + gauges(rig) + " "
                            + rig.tail());
        });
        scenario("cue_shove_300_ms_after_a_motor_command_does_not_arm", n -> {
            Rig rig = cueRig(EMPTY_ROOM).started();
            long hop = runUntilEvent(rig, "hop", 0, 20000);
            long stop = runUntilEvent(rig, "stop", hop, 20000);
            rig.shoveAt(stop + 300, 900);
            rig.runUntil(stop + 1500);
            check(n, stop > 0 && entered(rig, ExploreBrain.State.CUE_TURN, stop) < 0
                            && entered(rig, ExploreBrain.State.CUE_LOOK, stop) < 0 && rig.shovedStamps.isEmpty()
                            && rig.counted(ExploreBrain.Gauges.Counter.SHOVES) == 0
                            && rig.counted(ExploreBrain.Gauges.Counter.LEAN_INS) == 0 && rig.violations.isEmpty(),
                    "stop@" + stop + " " + gauges(rig) + " " + rig.tail());
        });
        scenario("cue_forward_stall_stamps_a_bump_and_sorry_within_2_s_is_strong_held_until_the_escape_ends", n -> {
            // A 5 s leg; the wheels turn until 2000, then stand still: the stall stops him at ~3000.
            // Recovery off: the feed's wheels never move again (the wait would call a real jam).
            Rig rig = cueRig(cueTuning().hopTicks(20).stallRecoverOff(), t -> wheels(t, Math.min(t, 2000)), EMPTY_ROOM)
                    .started();
            long startle = runUntilState(rig, ExploreBrain.State.STARTLE, 0, 20000);
            long sorry = startle + 1500;
            rig.cue(sorry, Ears.Kind.APOLOGY, Ears.Side.LEFT, -40f);
            long search = runUntilState(rig, ExploreBrain.State.CUE_TURN, sorry, 30000);
            int escapeTurn = rig.firstAfter("turn", startle);
            int escapeStop = rig.firstAfter("stop", rig.timeOf(escapeTurn));
            check(n, startle > 0 && rig.shovedStamps.equals(java.util.Arrays.asList(startle))
                            && search > 0 && escapeTurn >= 0 && rig.timeOf(escapeStop) == search
                            && "TURN".equals(stateBefore(rig, ExploreBrain.State.CUE_TURN, sorry))
                            && rig.counted(ExploreBrain.Gauges.Counter.STRONG_CUES) == 1
                            && rig.counted(ExploreBrain.Gauges.Counter.LEAN_INS) == 0
                            && rig.counted(ExploreBrain.Gauges.Counter.CUES_HELD) == 1 && rig.violations.isEmpty(),
                    "startle@" + startle + " search@" + search + " stamps=" + rig.shovedStamps + " "
                            + gauges(rig) + " " + rig.tail());
        });
        scenario("cue_sorry_4_s_after_the_bump_is_weak", n -> {
            // Recovery off: the feed's wheels never move again (the wait would call a real jam).
            Rig rig = cueRig(cueTuning().hopTicks(20).stallRecoverOff(), t -> wheels(t, Math.min(t, 2000)), EMPTY_ROOM)
                    .started();
            long startle = runUntilState(rig, ExploreBrain.State.STARTLE, 0, 20000);
            long sorry = startle + 4000;
            rig.cue(sorry, Ears.Kind.APOLOGY, Ears.Side.LEFT, -40f);
            rig.runUntil(sorry);
            long search = runUntilState(rig, ExploreBrain.State.CUE_TURN, sorry, 40000);
            check(n, startle > 0 && search > 0 && rig.counted(ExploreBrain.Gauges.Counter.LEAN_INS) == 1
                            && rig.counted(ExploreBrain.Gauges.Counter.WEAK_CUES) == 1
                            && rig.counted(ExploreBrain.Gauges.Counter.STRONG_CUES) == 0 && rig.violations.isEmpty(),
                    "startle@" + startle + " search@" + search + " " + gauges(rig) + " " + rig.tail());
        });
        scenario("cue_charger_keeps_the_ears_open_and_a_call_there_is_answered_in_place", n -> {
            // Hey-miko plan KTD5 replaces the meeting plan's KTD6 dock rule: the ears stay open on
            // the charger, and a call there is answered and met where he sits.
            Rig rig = cueRig(cueTuning(), t -> t >= 3000 && t < 6000 ? charger(t) : clear(t), EMPTY_ROOM).started();
            rig.cue(4000, Ears.Kind.NAME, Ears.Side.LEFT, -60f);
            rig.runUntil(4000);
            ExploreBrain.State after = rig.brain.state();
            rig.runUntil(20000);
            long over = stopOver(rig, 4000);
            check(n, rig.timeOf(rig.first("ears open", 0)) <= 100 && rig.earsOpens == 1 && rig.earsCloses == 0
                            && after == ExploreBrain.State.MEET && answerAt(rig, 4000) == 4000 && rig.count("lines") == 1
                            && over > 4000 && wheelMoves(rig, 4000, over) == 0
                            && entered(rig, ExploreBrain.State.CUE_TURN, 0) < 0 && rig.violations.isEmpty(),
                    "after=" + after + " over@" + over + " opens=" + rig.earsOpens + " closes=" + rig.earsCloses + " "
                            + gauges(rig) + " " + rig.tail());
        });
        scenario("ears_lost_reopens_with_backoff", n -> {
            // Lost at 5 s; the launcher refuses every bind until 20 s, so the retries at +2, +4 and +8 s
            // fail and the one at +16 s (35 s) holds. A second loss at 80 s starts over at 2 s.
            List<String> notes = new ArrayList<String>();
            Rig rig = cueRig(EMPTY_ROOM);
            rig.brain.setTrace(x -> notes.add(rig.now + " " + x));
            rig.started();
            rig.earsOpenRefusedUntil = 20000;
            rig.at(5000, () -> rig.earsListening = false);
            rig.at(80000, () -> rig.earsListening = false);
            rig.runUntil(90000);
            long open0 = rig.timeOf(rig.first("ears open", 0));
            long r1 = rig.timeOf(rig.firstAfter("ears open", 5000));
            long r2 = rig.timeOf(rig.firstAfter("ears open", r1 + 10));
            long r3 = rig.timeOf(rig.firstAfter("ears open", r2 + 10));
            long r4 = rig.timeOf(rig.firstAfter("ears open", r3 + 10));
            long r5 = rig.timeOf(rig.firstAfter("ears open", r4 + 10));
            int between = rig.countPrefix("ears open", r4 + 10, 80000);
            int after = rig.countPrefix("ears open", r5 + 10, 90001);
            check(n, open0 <= 100 && withinTick(r1, 7000) && withinTick(r2, 11000) && withinTick(r3, 19000)
                            && withinTick(r4, 35000) && between == 0 && withinTick(r5, 82000) && after == 0
                            && rig.earsOpens == 6 && rig.earsCloses == 0 && rig.listening()
                            && notesWith(notes, "ears lost: reopening in 2000ms (attempt 1)") == 2
                            && notesWith(notes, "ears back after 4 attempt(s)") == 1
                            && notesWith(notes, "ears back") == 2 && rig.violations.isEmpty(),
                    "open0@" + open0 + " retries@" + r1 + "," + r2 + "," + r3 + "," + r4 + " between=" + between
                            + " second@" + r5 + " after=" + after + " opens=" + rig.earsOpens + " closes=" + rig.earsCloses
                            + " listening=" + rig.listening() + " lost=" + notesWith(notes, "ears lost")
                            + " back=" + notesWith(notes, "ears back") + " " + rig.tail());
        });
        scenario("ears_flapping_session_keeps_backing_off", n -> {
            // Every reopen binds and is lost 500 ms later, before the next check: the interval keeps
            // doubling to the 30 s cap and never collapses back to 2 s, one bind per interval.
            List<String> notes = new ArrayList<String>();
            Rig rig = cueRig(EMPTY_ROOM);
            rig.brain.setTrace(x -> notes.add(rig.now + " " + x));
            rig.started();
            rig.earsHoldMs = 500;
            rig.at(5000, () -> rig.earsListening = false);
            rig.runUntil(130000);
            long[] r = new long[7];
            long from = 5000;
            for (int i = 0; i < r.length; i++) {
                r[i] = rig.timeOf(rig.firstAfter("ears open", from));
                from = r[i] + 10;
            }
            int perInterval = rig.countPrefix("ears open", 95010, 125000);
            check(n, withinTick(r[0], 7000) && withinTick(r[1], 11000) && withinTick(r[2], 19000)
                            && withinTick(r[3], 35000) && withinTick(r[4], 65000) && withinTick(r[5], 95000)
                            && withinTick(r[6], 125000) && perInterval == 0 && rig.earsOpens == 8 && rig.earsCloses == 0
                            && notesWith(notes, "ears lost") == 1 && notesWith(notes, "ears back") == 0
                            && rig.violations.isEmpty(),
                    "retries@" + java.util.Arrays.toString(r) + " perInterval=" + perInterval + " opens=" + rig.earsOpens
                            + " closes=" + rig.earsCloses + " lost=" + notesWith(notes, "ears lost")
                            + " back=" + notesWith(notes, "ears back") + " " + rig.tail());
        });
        scenario("ears_lost_on_the_charger_reopens_on_the_same_backoff", n -> {
            // The ears stay open on the charger (hey-miko plan KTD5), so the latch at 5-8 s closes
            // nothing and the reopen ladder runs through it: lost at 2.5 s, the retry at 4.5 s is
            // refused (the launcher refuses binds until 6 s), the one at 8.5 s binds, and it is
            // found holding at 16.5 s.
            List<String> notes = new ArrayList<String>();
            Rig rig = cueRig(cueTuning(), t -> t >= 5000 && t < 8000 ? charger(t) : clear(t), EMPTY_ROOM);
            rig.brain.setTrace(x -> notes.add(rig.now + " " + x));
            rig.started();
            rig.earsOpenRefusedUntil = 6000;
            rig.at(2500, () -> rig.earsListening = false);
            rig.runUntil(17000);
            long r1 = rig.timeOf(rig.firstAfter("ears open", 2500));
            long r2 = rig.timeOf(rig.firstAfter("ears open", r1 + 10));
            int after = rig.countPrefix("ears open", r2 + 10, 17001);
            check(n, rig.earsCloses == 0 && withinTick(r1, 4500) && withinTick(r2, 8500) && after == 0
                            && rig.earsOpens == 3 && rig.listening()
                            && notesWith(notes, "ears lost: reopening in 2000ms (attempt 1)") == 1
                            && notesWith(notes, "ears back after 2 attempt(s)") == 1 && rig.violations.isEmpty(),
                    "retries@" + r1 + "," + r2 + " after=" + after + " opens=" + rig.earsOpens + " closes="
                            + rig.earsCloses + " listening=" + rig.listening() + " " + rig.tail());
        });
        scenario("cue_eyes_only_a_name_call_is_answered_meets_without_moving_and_a_second_call_waits_for_the_meeting", n -> {
            // No lease ever: eyes only. A name is a call (hey-miko plan KTD1): answered at once and
            // met without a turn. The wake word a second later, during that meeting, is kept (R1,
            // review P1 on PR #29) and answered with its own still meeting once the first ends.
            // Nobody can be seen, so each meeting takes the stranger path.
            Rig rig = cueRig(EMPTY_ROOM);
            rig.brain.start();
            rig.cue(1000, Ears.Kind.NAME, Ears.Side.LEFT, -60f);
            rig.cue(2000, Ears.Kind.WAKE_WORD, Ears.Side.RIGHT, 60f);
            rig.runUntil(1000);
            ExploreBrain.State afterName = rig.brain.state();
            long ask = runUntilEvent(rig, "say Hello! What's your name?", 1000, 20000);
            // The first meeting ends straight into the second, in the same step.
            long first = runUntilState(rig, ExploreBrain.State.MEET, ask, ask + 20000);
            long second = answerAt(rig, 1001);
            long ask2 = runUntilEvent(rig, "say Hello! What's your name?", ask + 1, first + 20000);
            rig.runUntil(ask2 + 12000);
            check(n, afterName == ExploreBrain.State.MEET && answerAt(rig, 1000) == 1000
                            && ExploreBrain.State.valueOf(stateAt(rig, 2000)).converses()
                            && rig.countPrefix("react answer", 1001, first) == 0 && first > ask
                            && second == first && rig.count("meeting over") == 2
                            && rig.count("react answer") == 2 && ask2 > second
                            && rig.clipWindows.equals(java.util.Arrays.asList(600L, 600L))
                            && wheelMoves(rig, 0, ask2 + 12001) == 0 && rig.count("lines") == 2 && rig.count("listen") == 2
                            && rig.counted(ExploreBrain.Gauges.Counter.CUES_DROPPED) == 0
                            && rig.count("camera open") == 0 && rig.brain.state() == ExploreBrain.State.EYES_ONLY
                            && rig.violations.isEmpty(),
                    "overs=" + rig.count("meeting over") + " lines=" + rig.count("lines") + " listens=" + rig.count("listen")
                            + " clips=" + rig.clipWindows + " early=" + rig.countPrefix("react answer", 1001, first)
                            + " afterName=" + afterName + " ask@" + ask + " first@" + first + " second@" + second + " ask2@" + ask2
                            + " end=" + rig.brain.state() + " " + gauges(rig) + " states=" + rig.stateLog + " " + rig.tail());
        });
        scenario("cue_held_strong_older_than_10_s_becomes_a_lean_in", n -> {
            // A 12 s line: the cue lands 300 ms in and waits 11.7 s for the deaf window to close.
            Rig rig = new Rig(claudeTuning().gyro(robotGyro()).sayTimeoutMs(15000).cueTurn(45, 90, 500, 2000, 600).build(),
                    CLEAR, EMPTY_ROOM, true, (r, req, nth) -> pick(0, "lamp", CuriosityPort.Kind.OTHER,
                            "What a shiny lamp!", 0.5f, 0.5f, 0.2f, 0.3f));
            rig.speechMs = 12000;
            rig.started();
            long say = runUntilEvent(rig, "say What a shiny lamp!", 0, 30000);
            // A greeting is strong but not a call: it keeps the hold and the lean-in downgrade.
            // A call never degrades (call_held_60_s_behind_a_long_conversation_...).
            rig.cue(say + 300, Ears.Kind.GREETING, Ears.Side.LEFT, -60f);
            long search = runUntilState(rig, ExploreBrain.State.CUE_TURN, say, say + 20000);
            long pause = runUntilState(rig, ExploreBrain.State.PAUSE, search + 1, search + 40000);
            check(n, say > 0 && search >= say + 12000 && entries(rig, ExploreBrain.State.CUE_LOOK).size() == 2 && pause > 0
                            && rig.counted(ExploreBrain.Gauges.Counter.LEAN_INS) == 1
                            && rig.counted(ExploreBrain.Gauges.Counter.STRONG_CUES) == 1
                            && rig.counted(ExploreBrain.Gauges.Counter.CUES_HELD) == 1 && rig.violations.isEmpty(),
                    "say@" + say + " search@" + search + " looks=" + entries(rig, ExploreBrain.State.CUE_LOOK) + " "
                            + gauges(rig) + " " + rig.tail());
        });
        scenario("cue_facing_face_plays_the_acknowledgement_in_a_clip_window_meets_and_stamps_the_stages", n -> {
            // A greeting, not a call: a call's search plays no acknowledgement after its
            // answer (hey-miko plan U6), so the acknowledgement is pinned on a strong cue.
            Rig rig = cueRig(personAt(bearingOf(-90f), 25)).started();
            rig.cue(400, Ears.Kind.GREETING, Ears.Side.LEFT, -90f);
            rig.runUntil(400);
            long say = runUntilEvent(rig, "say Hi Sarah!", 400, 30000);
            int clip = rig.firstAfter("clip 600", 400);
            int ack = rig.firstAfter("react acknowledge", 400);
            int match = rig.firstAfter("match", 400);
            long cueAt = rig.stamped(ExploreBrain.Gauges.Stage.CUE_AT);
            long turnDone = rig.stamped(ExploreBrain.Gauges.Stage.TURN_DONE);
            long faceFound = rig.stamped(ExploreBrain.Gauges.Stage.FACE_FOUND);
            long matchAnswered = rig.stamped(ExploreBrain.Gauges.Stage.MATCH_ANSWERED);
            check(n, say > 0 && clip >= 0 && ack > clip && match > ack && rig.timeOf(clip) == faceFound
                            && rig.clipWindows.equals(java.util.Arrays.asList(600L)) && rig.count("react answer") == 0
                            && cueAt == 400 && turnDone > cueAt && faceFound > turnDone && matchAnswered > faceFound
                            && matchAnswered == rig.timeOf(rig.firstAfter("match KNOWN", 400))
                            && rig.stamped(ExploreBrain.Gauges.Stage.LINE_REQUESTED) < 0
                            && rig.counted(ExploreBrain.Gauges.Counter.FACES_FOUND) == 1
                            && rig.counted(ExploreBrain.Gauges.Counter.QUIET_RESUMES) == 0
                            && entries(rig, ExploreBrain.State.CUE_LOOK).size() == 1 && rig.touches == 1
                            && rig.violations.isEmpty(),
                    "say@" + say + " clip@" + rig.timeOf(clip) + " ack@" + rig.timeOf(ack) + " match@" + rig.timeOf(match)
                            + " " + gauges(rig) + " " + rig.tail());
        });
        scenario("cue_trend_under_the_stop_band_ends_the_turn_early", n -> {
            // The angle estimate says 120 deg left (2 s at 60 deg/s); the latched trend closes
            // on the voice 400 ms into the first step and he stops there to look.
            Rig rig = cueRig(EMPTY_ROOM).started();
            rig.cue(400, Ears.Kind.NAME, Ears.Side.LEFT, -120f);
            rig.runUntil(400);
            long turn = runUntilEvent(rig, "turn", 400, 5000);
            rig.trendAt(turn + 200, -60f).trendAt(turn + 300, -30f).trendAt(turn + 400, -6f);
            rig.runUntil(turn + 2500);
            int stop = rig.firstAfter("stop", turn);
            long look = entered(rig, ExploreBrain.State.CUE_LOOK, turn);
            check(n, turn > 0 && rig.timeOf(stop) == turn + 400 && look == turn + 400
                            && rig.stamped(ExploreBrain.Gauges.Stage.TURN_DONE) == turn + 400 && rig.violations.isEmpty(),
                    "turn@" + turn + " stop@" + rig.timeOf(stop) + " look@" + look + " " + rig.tail());
        });
        scenario("cue_trend_growing_means_the_voice_is_behind_and_ends_the_turn", n -> {
            Rig rig = cueRig(EMPTY_ROOM).started();
            rig.cue(400, Ears.Kind.NAME, Ears.Side.LEFT, -120f);
            rig.runUntil(400);
            long turn = runUntilEvent(rig, "turn", 400, 5000);
            rig.trendAt(turn + 300, -125f).trendAt(turn + 500, -135f);
            rig.runUntil(turn + 2500);
            int stop = rig.firstAfter("stop", turn);
            long look = entered(rig, ExploreBrain.State.CUE_LOOK, turn);
            check(n, turn > 0 && rig.timeOf(stop) == turn + 500 && look == turn + 500 && rig.violations.isEmpty(),
                    "turn@" + turn + " stop@" + rig.timeOf(stop) + " look@" + look + " " + rig.tail());
        });
    }

    // ---- the conversation (meeting plan U8; R4, R10-R21; KTD7-KTD12, KTD14) ----
    //
    // The chat rig is the cue rig with a persona attached to the match answer (the
    // adapter's sign that the conversation path is open): Sarah is known with notes
    // and one question on record, a stranger has neither. Sarah stands to the left
    // (a cue from -90 deg), so the resume leg turns right, away from her.

    private static final String PERSONA = "dry office small talk";
    private static final String PERSONA_EDITED = "warmer office small talk";
    private static final String SARAH_ID = "p-sarah";
    private static final String SARAH_NOTES =
            "{\"open_threads\":[{\"text\":\"camping trip\",\"since\":1}],\"questions_asked\":[\"How was the weekend?\"]}";
    private static final String SARAH_ASKED = "how was the weekend";

    /** A plain turn line with a notes delta naming its turn, so the deltas can be told apart. */
    private static CuriosityPort.Turn turnLine(int nth) {
        return CuriosityPort.Turn.line("Line " + nth + ".", null, null, false, false, "{\"topics\":[\"t" + nth + "\"]}");
    }

    /** Turn 1 answers the first, turn 2 the second, ...; every turn past the script answers a plain line. */
    private static TurnScript turnsOf(CuriosityPort.Turn... perTurn) {
        return (rig, req, nth) -> nth <= perTurn.length ? perTurn[nth - 1] : turnLine(nth);
    }

    // ---- the call: slot, verdict and answer (hey-miko plan U5; R1-R4, R11; KTD1-KTD5; AE1-AE3, AE6) ----
    //
    // A strong WAKE_WORD or NAME cue is a call: answered with the "answer" clip in the
    // step it is taken, never dropped, never a lean-in. These pin KTD2's order (a line
    // playing, a conversation, the charger, the back-off, no wheels or camera, the person
    // he was going to, everything else), the hand-back, and the charger's still meeting.

    /** When the stop that began before `from` ended: the first state change after it into a non-stop state, or -1. */
    private static long stopOver(Rig rig, long from) {
        for (Event e : rig.stateLog) {
            if (e.t > from && !ExploreBrain.State.valueOf(e.what).inStop()) {
                return e.t;
            }
        }
        return -1;
    }

    /** When the state first changed after `from`, or -1. */
    private static long stateChangeAfter(Rig rig, long from) {
        for (Event e : rig.stateLog) {
            if (e.t > from) {
                return e.t;
            }
        }
        return -1;
    }

    /** Wheel commands in [from, to): hops, back-offs and turns (not the conversation's "turn" requests). */
    private static int wheelMoves(Rig rig, long from, long to) {
        return rig.countPrefix("hop", from, to) + rig.countPrefix("back", from, to)
                + rig.countPrefix("turn LEFT", from, to) + rig.countPrefix("turn RIGHT", from, to);
    }

    /** When the turn to a voice began at or after from (CUE_TURN, or CUE_LOOK when there was nothing to turn), or -1. */
    private static long searchFrom(Rig rig, long from) {
        long turn = entered(rig, ExploreBrain.State.CUE_TURN, from);
        long look = entered(rig, ExploreBrain.State.CUE_LOOK, from);
        return turn < 0 ? look : look < 0 ? turn : Math.min(turn, look);
    }

    /** The state he was in at t (the last state change at or before it), else null. */
    private static String stateAt(Rig rig, long t) {
        String s = null;
        for (Event e : rig.stateLog) {
            if (e.t > t) {
                break;
            }
            s = e.what;
        }
        return s;
    }

    /** The time of the first answer clip at or after t, or -1. */
    private static long answerAt(Rig rig, long t) {
        return rig.timeOf(rig.firstAfter("react answer", t));
    }

    private static void callScenarios() {
        scenario("call_ae1_met_two_minutes_ago_a_wake_word_is_still_answered_and_searched", n -> {
            Rig rig = sarahRig(true);
            List<String> notes = traced(rig);
            rig.people.listen = ListenScript.turns(hearWords("hi"), hearWords("catch you later"));
            long open = openChat(rig);
            long over = chatOver(rig, open);
            long cueT = over + 120000;
            rig.cue(cueT, Ears.Kind.WAKE_WORD, Ears.Side.LEFT, -90f);
            rig.runUntil(cueT + 100);
            check(n, open > 0 && over > 0 && anyContains(notes, "conversation with someone named over")
                            && cueT - over < rig.tuning.metLeaveAloneMs && answerAt(rig, cueT) == cueT
                            && entered(rig, ExploreBrain.State.CUE_TURN, cueT) == cueT
                            && rig.count("react answer") == 2 && rig.violations.isEmpty(),
                    "over@" + over + " answer@" + answerAt(rig, cueT) + " " + gauges(rig) + " " + rig.tail());
        });
        scenario("call_ae2_a_wake_word_during_the_back_off_waits_for_it_then_answers_and_searches", n -> {
            Rig rig = cueRig(cueTuning(), t -> t >= 1500 && t < 1700 ? edgeAhead(t) : clear(t), EMPTY_ROOM).started();
            long back = runUntilState(rig, ExploreBrain.State.BACK_OFF, 0, 20000);
            long cueT = back + 50;
            rig.cue(cueT, Ears.Kind.WAKE_WORD, Ears.Side.UNKNOWN, Float.NaN);
            rig.runUntil(back + 6000);
            long search = searchFrom(rig, cueT);
            long backEnd = stateChangeAfter(rig, back);
            check(n, back > 0 && search > 0 && backEnd > cueT && search >= backEnd && search <= backEnd + 20
                            && answerAt(rig, cueT) == search && rig.countPrefix("react answer", 0, search) == 0
                            && rig.countPrefix("turn", cueT, search) == 0
                            && rig.timeOf(rig.firstAfter("stop", cueT)) == backEnd
                            && rig.counted(ExploreBrain.Gauges.Counter.CUES_HELD) == 1
                            && rig.counted(ExploreBrain.Gauges.Counter.LEAN_INS) == 0 && rig.violations.isEmpty(),
                    "back@" + back + " backEnd@" + backEnd + " search@" + search + " answer@" + answerAt(rig, cueT) + " "
                            + gauges(rig) + " " + rig.tail());
        });
        scenario("call_two_calls_before_the_answer_merge_into_one_and_the_newer_angle_wins", n -> {
            Rig rig = cueRig(cueTuning(), t -> t >= 1500 && t < 1700 ? edgeAhead(t) : clear(t), EMPTY_ROOM).started();
            long back = runUntilState(rig, ExploreBrain.State.BACK_OFF, 0, 20000);
            rig.cue(back + 50, Ears.Kind.WAKE_WORD, Ears.Side.LEFT, -60f);
            rig.cue(back + 100, Ears.Kind.NAME, Ears.Side.RIGHT, 60f);
            long search = runUntilState(rig, ExploreBrain.State.CUE_TURN, back + 50, 30000);
            rig.runUntil(search + 1500);
            int turn = rig.firstAfter("turn", search);
            check(n, back > 0 && search > 0 && rig.count("react answer") == 1 && answerAt(rig, back) == search
                            && rig.count("eyes GLANCE RIGHT") == 1 && rig.count("eyes GLANCE LEFT") == 0
                            && rig.what(turn).equals("turn RIGHT") && rig.counted(ExploreBrain.Gauges.Counter.SEARCHES) == 1
                            && rig.counted(ExploreBrain.Gauges.Counter.CUES_DROPPED) == 0 && rig.violations.isEmpty(),
                    "search@" + search + " turn=" + rig.what(turn) + " " + gauges(rig) + " " + rig.tail());
        });
        scenario("call_docked_cycling_through_startle_and_back_off_answers_in_place_without_moving", n -> {
            // An edge at 1.5 s: the startle, then the back-off from 1.9 s; the charger latch comes on
            // 50 ms into it (the latch reads as motion refused), and the call lands 50 ms later.
            Rig rig = cueRig(cueTuning(), t -> t >= 1500 && t < 1700 ? edgeAhead(t) : t >= 1950 ? charger(t) : clear(t),
                    EMPTY_ROOM).started();
            long back = runUntilState(rig, ExploreBrain.State.BACK_OFF, 0, 30000);
            long cueT = back + 100;
            rig.cue(cueT, Ears.Kind.WAKE_WORD, Ears.Side.LEFT, -60f);
            rig.runUntil(cueT);
            ExploreBrain.State after = rig.brain.state();
            long over = stopOver(rig, cueT);
            long end = over > 0 ? over : rig.now;
            rig.runUntil(cueT + 20000);
            over = stopOver(rig, cueT);
            end = over > 0 ? over : rig.now;
            check(n, back > 0 && answerAt(rig, cueT) == cueT && after == ExploreBrain.State.MEET && rig.count("lines") == 1
                            && wheelMoves(rig, cueT, end) == 0 && rig.count("ears close") == 0
                            && rig.counted(ExploreBrain.Gauges.Counter.CUES_HELD) == 0 && rig.violations.isEmpty(),
                    "back@" + back + " after=" + after + " over@" + over + " motions=" + wheelMoves(rig, cueT, end) + " "
                            + gauges(rig) + " states=" + rig.stateLog + " " + rig.tail());
        });
        scenario("call_ae3_no_angle_in_chat_listen_is_the_partner_speaking_and_makes_no_call", n -> {
            // Owner 2026-09-30: with no angle nobody can be told apart from the partner, so a wake
            // word in the conversation is them speaking: no call waits, none is answered after.
            Rig rig = sarahRig(true);
            rig.people.listen = ListenScript.turns(hearWords("yes").after(2500), hearWords("catch you later"));
            long open = openChat(rig);
            long listen = runUntilEvent(rig, "listen", open, open + 20000);
            long cueT = listen + 300;
            rig.cue(cueT, Ears.Kind.WAKE_WORD, Ears.Side.UNKNOWN, Float.NaN);
            long over = chatOver(rig, listen);
            rig.runUntil(over + 20000);
            check(n, open > 0 && over > cueT && searchFrom(rig, over) < 0 && rig.count("react answer") == 1
                            && rig.turnAsks.size() == 2 && rig.count("react sign-off") == 1
                            && rig.count("react one-sec") == 0 && rig.counted(ExploreBrain.Gauges.Counter.CUES_HELD) == 0
                            && rig.counted(ExploreBrain.Gauges.Counter.CUES_DROPPED) == 0 && rig.violations.isEmpty(),
                    "listen@" + listen + " over@" + over + " search@" + searchFrom(rig, over) + " " + gauges(rig) + " "
                            + rig.tail());
        });
        scenario("call_a_second_caller_during_the_first_calls_meeting_waits_for_the_conversation_then_is_answered", n -> {
            // Review P1 (PR #29): Sarah's call is met; Sam calls from 150 deg before the chat opens.
            Rig rig = sarahRig(true);
            rig.people.listen = ListenScript.turns(hearWords("hi"), hearWords("catch you later"));
            rig.cue(400, Ears.Kind.NAME, Ears.Side.LEFT, -90f);
            long meet = runUntilState(rig, ExploreBrain.State.MEET, 400, 30000);
            long cueT = rig.now + 10;
            rig.cue(cueT, Ears.Kind.WAKE_WORD, Ears.Side.RIGHT, 150f);
            long open = runUntilState(rig, ExploreBrain.State.CHAT_THINK, meet, meet + 30000);
            long over = chatOver(rig, open);
            rig.runUntil(over + 100);
            long search = searchFrom(rig, over);
            check(n, meet > 0 && "MEET".equals(stateAt(rig, cueT)) && open > cueT && over > open
                            && search >= over && search <= over + 20
                            && rig.countPrefix("react answer", cueT, over) == 0 && answerAt(rig, cueT) == search
                            && rig.count("react answer") == 2 && rig.turnAsks.size() == 2
                            && rig.counted(ExploreBrain.Gauges.Counter.CUES_DROPPED) == 0 && rig.violations.isEmpty(),
                    "meet@" + meet + " cue@" + cueT + " in " + stateAt(rig, cueT) + " open@" + open + " over@" + over
                            + " search@" + search + " answer@" + answerAt(rig, cueT) + " " + gauges(rig) + " " + rig.tail());
        });
        scenario("call_a_second_caller_during_the_first_calls_chat_listen_waits_for_the_conversation_then_is_answered", n -> {
            Rig rig = sarahRig(true);
            rig.people.listen = ListenScript.turns(hearWords("yes").after(2500), hearWords("catch you later"));
            long open = openChat(rig);
            long listen = runUntilEvent(rig, "listen", open, open + 20000);
            long cueT = listen + 300;
            rig.cue(cueT, Ears.Kind.WAKE_WORD, Ears.Side.RIGHT, 150f);
            long over = chatOver(rig, listen);
            rig.runUntil(over + 100);
            long search = searchFrom(rig, over);
            check(n, open > 0 && "CHAT_LISTEN".equals(stateAt(rig, cueT)) && over > cueT
                            && search >= over && search <= over + 20
                            && rig.countPrefix("react answer", cueT, over) == 0 && answerAt(rig, cueT) == search
                            && rig.count("react answer") == 2 && rig.turnAsks.size() == 2
                            && rig.counted(ExploreBrain.Gauges.Counter.CUES_DROPPED) == 0 && rig.violations.isEmpty(),
                    "listen@" + listen + " cue in " + stateAt(rig, cueT) + " over@" + over + " search@" + search
                            + " answer@" + answerAt(rig, cueT) + " " + gauges(rig) + " " + rig.tail());
        });
        scenario("call_a_second_call_with_no_angle_in_a_charger_meeting_is_answered_after_it_without_moving", n -> {
            Rig rig = chatRig(cueTuning(), t -> t >= 1000 ? charger(t) : clear(t), EMPTY_ROOM, false);
            rig.people.lines = STRANGER.withConversation(PERSONA, null, null, null);
            rig.turns = turnsOf(turnLine(1), turnLine(2));
            rig.people.listen = ListenScript.turns(hearWords("i'm ben"), hearWords("bye"));
            rig.started();
            long cueT = 4000;
            rig.cue(cueT, Ears.Kind.WAKE_WORD, Ears.Side.LEFT, -60f);
            rig.runUntil(cueT);
            long cue2 = cueT + 20;
            rig.cue(cue2, Ears.Kind.WAKE_WORD, Ears.Side.UNKNOWN, Float.NaN);
            long open = runUntilState(rig, ExploreBrain.State.CHAT_THINK, cueT, cueT + 20000);
            long over = chatOver(rig, open);
            long meet2 = runUntilState(rig, ExploreBrain.State.MEET, over, over + 5000);
            long open2 = runUntilState(rig, ExploreBrain.State.CHAT_THINK, meet2, meet2 + 20000);
            long over2 = chatOver(rig, open2);
            long end = over2 > 0 ? over2 : rig.now;
            check(n, "MEET".equals(stateAt(rig, cue2)) && open > cue2 && over > open && meet2 >= over
                            && meet2 <= over + 20 && answerAt(rig, cue2) == meet2
                            && rig.countPrefix("react answer", cue2, over) == 0 && rig.count("react answer") == 2
                            && open2 > meet2 && over2 > open2
                            && wheelMoves(rig, cueT, end) == 0 && entered(rig, ExploreBrain.State.CUE_TURN, cueT) < 0
                            && rig.count("ears close") == 0 && rig.violations.isEmpty(),
                    "cue2 in " + stateAt(rig, cue2) + " open@" + open + " over@" + over + " meet2@" + meet2
                            + " open2@" + open2 + " over2@" + over2 + " answer@" + answerAt(rig, cue2)
                            + " motions=" + wheelMoves(rig, cueT, end) + " " + gauges(rig) + " " + rig.tail());
        });
        scenario("call_from_the_partners_angle_during_the_calls_meeting_or_conversation_makes_no_extra_call", n -> {
            Rig rig = sarahRig(true);
            rig.people.listen = ListenScript.turns(hearWords("yes").after(2500), hearWords("catch you later"));
            rig.cue(400, Ears.Kind.NAME, Ears.Side.LEFT, -90f);
            long meet = runUntilState(rig, ExploreBrain.State.MEET, 400, 30000);
            long cue1 = rig.now + 10;
            float inside = (float) rig.tuning.newcomerAngleDeg - 5f;
            rig.cue(cue1, Ears.Kind.WAKE_WORD, Ears.Side.RIGHT, inside);
            long open = runUntilState(rig, ExploreBrain.State.CHAT_THINK, meet, meet + 30000);
            long listen = runUntilEvent(rig, "listen", open, open + 20000);
            long cue2 = listen + 300;
            rig.cue(cue2, Ears.Kind.WAKE_WORD, Ears.Side.LEFT, -inside);
            long over = chatOver(rig, listen);
            rig.runUntil(over + 20000);
            check(n, meet > 0 && "MEET".equals(stateAt(rig, cue1)) && "CHAT_LISTEN".equals(stateAt(rig, cue2))
                            && over > cue2 && rig.count("react answer") == 1 && searchFrom(rig, over) < 0
                            && rig.count("react one-sec") == 0 && rig.violations.isEmpty(),
                    "cue1 in " + stateAt(rig, cue1) + " cue2 in " + stateAt(rig, cue2) + " over@" + over + " "
                            + gauges(rig) + " " + rig.tail());
        });
        scenario("call_docked_in_a_chat_waits_until_it_ends_then_answers_and_meets_without_moving", n -> {
            long[] dock = {Long.MAX_VALUE};
            Rig rig = chatRig(cueTuning(), t -> t >= dock[0] ? charger(t) : clear(t), personAt(bearingOf(-90f), 25), true);
            rig.started();
            rig.people.listen = ListenScript.turns(hearWords("hi"), hearWords("more"), hearWords("more again"));
            long open = openChat(rig);
            long say2 = runUntilEvent(rig, "say Line 2.", open, open + 30000);
            dock[0] = say2 + 100;
            long cueT = say2 + 300;
            rig.cue(cueT, Ears.Kind.WAKE_WORD, Ears.Side.RIGHT, 60f);
            long over = chatOver(rig, say2);
            long meet = runUntilState(rig, ExploreBrain.State.MEET, over, over + 5000);
            rig.runUntil(meet + 20000);
            long done = stopOver(rig, meet);
            long end = done > 0 ? done : rig.now;
            check(n, open > 0 && over > cueT && meet >= over && meet <= over + 20 && answerAt(rig, cueT) == meet
                            && rig.countPrefix("react answer", cueT, over) == 0 && wheelMoves(rig, cueT, end) == 0
                            && entered(rig, ExploreBrain.State.CUE_TURN, cueT) < 0 && rig.count("ears close") == 0
                            && rig.violations.isEmpty(),
                    "over@" + over + " meet@" + meet + " done@" + done + " motions=" + wheelMoves(rig, cueT, end) + " "
                            + gauges(rig) + " " + rig.tail());
        });
        scenario("call_with_claude_unavailable_plays_the_answer_once_and_clears_the_slot", n -> {
            Rig rig = cueRig(EMPTY_ROOM);
            rig.askRefused = true;
            List<String> notes = traced(rig);
            rig.started();
            long hop = runUntilEvent(rig, "hop", 0, 20000);
            long cueT = hop + 200;
            rig.cue(cueT, Ears.Kind.WAKE_WORD, Ears.Side.LEFT, -60f);
            rig.runUntil(cueT + 20000);
            check(n, hop > 0 && answerAt(rig, cueT) == cueT && rig.count("react answer") == 1
                            && rig.timeOf(rig.firstAfter("stop", cueT)) == cueT
                            && entered(rig, ExploreBrain.State.CUE_TURN, cueT) < 0 && rig.count("lines") == 0
                            && rig.count("match") == 0 && wheelMoves(rig, cueT + 1, cueT + 20000) > 0
                            && notesWith(notes, "the answer is the whole response") == 1 && rig.violations.isEmpty(),
                    "answer@" + answerAt(rig, cueT) + " " + gauges(rig) + " " + rig.tail());
        });
        scenario("call_in_approach_with_claude_unavailable_still_stops_for_the_answer", n -> {
            // R4/KTD3: he stops for the answer even when no conversation can open (review #3).
            Rig rig = cueRig(cueTuning(), CLEAR, personWhen(t -> true)).started();
            long approach = runUntilState(rig, ExploreBrain.State.APPROACH, 0, 20000);
            long leg = runUntilEvent(rig, "hop", approach, approach + 5000);
            long cueT = leg + 100;
            rig.askRefused = true;
            rig.cue(cueT, Ears.Kind.WAKE_WORD, Ears.Side.UNKNOWN, Float.NaN);
            rig.runUntil(cueT + rig.tuning.answerClipMs);
            check(n, approach > 0 && leg > 0 && answerAt(rig, cueT) == cueT
                            && rig.timeOf(rig.firstAfter("stop", cueT)) == cueT
                            && rig.countPrefix("hop", cueT + 1, cueT + rig.tuning.answerClipMs) == 0
                            && rig.violations.isEmpty(),
                    "approach@" + approach + " leg@" + leg + " " + gauges(rig) + " " + rig.tail());
        });
        scenario("call_lease_lost_during_its_search_is_retaken_in_eyes_only_without_a_second_answer", n -> {
            Rig rig = cueRig(EMPTY_ROOM);
            List<String> notes = traced(rig);
            rig.started();
            rig.cue(400, Ears.Kind.WAKE_WORD, Ears.Side.LEFT, -90f);
            rig.at(900, () -> rig.brain.onLeaseChanged(false));
            rig.runUntil(900);
            long meet = runUntilState(rig, ExploreBrain.State.MEET, 900, 5000);
            rig.runUntil(meet + 10000);
            check(n, entered(rig, ExploreBrain.State.CUE_TURN, 400) == 400 && answerAt(rig, 400) == 400
                            && meet >= 900 && meet <= 920 && rig.count("react answer") == 1 && rig.count("lines") == 1
                            && notesWith(notes, "during the call's search or approach: the call waits") == 1
                            && wheelMoves(rig, meet, rig.now) == 0 && rig.violations.isEmpty(),
                    "meet@" + meet + " " + gauges(rig) + " " + rig.tail());
        });
        scenario("call_ae6_on_the_charger_answers_and_holds_the_conversation_without_leaving_the_dock", n -> {
            Rig rig = chatRig(cueTuning(), t -> t >= 1000 ? charger(t) : clear(t), EMPTY_ROOM, false);
            rig.people.lines = STRANGER.withConversation(PERSONA, null, null, null);
            rig.turns = turnsOf(turnLine(1), turnLine(2));
            rig.people.listen = ListenScript.turns(hearWords("i'm ben"), hearWords("bye"));
            rig.started();
            long cueT = 4000;
            rig.cue(cueT, Ears.Kind.WAKE_WORD, Ears.Side.LEFT, -60f);
            rig.runUntil(cueT);
            ExploreBrain.State after = rig.brain.state();
            long open = runUntilState(rig, ExploreBrain.State.CHAT_THINK, cueT, cueT + 20000);
            long over = chatOver(rig, open);
            check(n, after == ExploreBrain.State.MEET && answerAt(rig, cueT) == cueT && rig.count("react answer") == 1
                            && open > 0 && over > 0 && wheelMoves(rig, cueT, over) == 0 && rig.count("camera open") >= 0
                            && rig.turnAsks.size() == 2 && rig.count("ears close") == 0 && rig.earsOpens == 1
                            && rig.violations.isEmpty(),
                    "after=" + after + " open@" + open + " over@" + over + " motions=" + wheelMoves(rig, cueT, Math.max(cueT, over))
                            + " " + gauges(rig) + " " + rig.tail());
        });
        scenario("call_held_60_s_behind_a_long_conversation_is_still_answered_as_a_call_not_a_lean_in", n -> {
            Rig rig = sarahRig(true);
            Hearing[] replies = new Hearing[19];
            for (int i = 0; i < 18; i++) {
                replies[i] = hearWords("more");
            }
            replies[18] = hearWords("catch you later");
            rig.people.listen = ListenScript.turns(replies);
            long open = openChat(rig);
            long cueT = open + 1000;
            // Clearly someone else's (behind him, Sarah in view): a no-angle one is Sarah speaking now.
            rig.cue(cueT, Ears.Kind.WAKE_WORD, Ears.Side.RIGHT, 150f);
            long over = chatOver(rig, open);
            rig.runUntil(over + 100);
            long search = searchFrom(rig, over);
            check(n, open > 0 && over - cueT >= 60000 && search >= over && search <= over + 20
                            && answerAt(rig, cueT) == search && rig.counted(ExploreBrain.Gauges.Counter.LEAN_INS) == 0
                            && rig.violations.isEmpty(),
                    "open@" + open + " over@" + over + " search@" + search + " " + gauges(rig) + " " + rig.tail());
        });
        scenario("call_during_an_escape_is_answered_at_once", n -> {
            List<String> notes = new ArrayList<String>();
            // Recovery off: pinned, the post-stall wait goes from the back-up to the wriggle, no RETRACE.
            Rig rig = pinnedRig(notes, escTuning().stallRecoverOff());
            rig.started();
            long retrace = runUntilState(rig, ExploreBrain.State.RETRACE, 0, 60000);
            long cueT = retrace + 10;
            rig.cue(cueT, Ears.Kind.WAKE_WORD, Ears.Side.LEFT, -60f);
            rig.runUntil(cueT + 100);
            check(n, retrace > 0 && answerAt(rig, cueT) == cueT && entered(rig, ExploreBrain.State.CUE_TURN, cueT) == cueT
                            && "RETRACE".equals(stateBefore(rig, ExploreBrain.State.CUE_TURN, cueT)),
                    "retrace@" + retrace + " answer@" + answerAt(rig, cueT) + " " + rig.tail());
        });
        scenario("call_in_approach_with_no_angle_answers_and_carries_on_the_same_approach", n -> {
            // R4 wins over KTD2 step 6's wording: he stops for the answer, mid-leg here, then
            // carries on toward the same person (the next leg after the clip) and meets them.
            Rig rig = cueRig(cueTuning(), CLEAR, personWhen(t -> true)).started();
            long approach = runUntilState(rig, ExploreBrain.State.APPROACH, 0, 20000);
            long leg = runUntilEvent(rig, "hop", approach, approach + 5000);
            long cueT = leg + 100;
            rig.cue(cueT, Ears.Kind.WAKE_WORD, Ears.Side.UNKNOWN, Float.NaN);
            rig.runUntil(cueT + 12000);
            int match = rig.firstAfter("match", cueT);
            long resumed = rig.timeOf(rig.firstAfter("hop", cueT));
            check(n, approach > 0 && leg > 0 && answerAt(rig, cueT) == cueT
                            && rig.timeOf(rig.firstAfter("stop", cueT)) == cueT
                            && resumed >= cueT + rig.tuning.answerClipMs && match > 0 && resumed < rig.timeOf(match)
                            && "APPROACH".equals(stateAt(rig, resumed))
                            && entered(rig, ExploreBrain.State.CUE_TURN, cueT) < 0
                            && entered(rig, ExploreBrain.State.SCAN, cueT) < 0
                            && rig.what(rig.firstAfter("say ", cueT)).equals("say Hi Sarah!")
                            && rig.counted(ExploreBrain.Gauges.Counter.SEARCHES) == 0 && rig.violations.isEmpty(),
                    "approach@" + approach + " leg@" + leg + " resumed@" + resumed + " match@" + rig.timeOf(match) + " "
                            + gauges(rig) + " " + rig.tail());
        });
        scenario("call_in_approach_to_a_thing_answers_and_searches_instead_of_carrying_on", n -> {
            // KTD2 step 6 carries on only toward a person, the likeliest caller; heading for a
            // plant, a call must still find somebody (R1), so it answers and searches.
            Vision cup = (r, t) -> list(box("cup", 0.8f, 0.5f, 0.6f, 0.2f, 0.3f));
            Rig rig = new Rig(claudeTuning().gyro(robotGyro()).build(), CLEAR, cup, true,
                    (r, req, nth) -> pick(2, "cup", CuriosityPort.Kind.OTHER, "Ooh, a cup!",
                            0.5f, 0.6f, 0.2f, 0.3f)).started();
            long approach = runUntilState(rig, ExploreBrain.State.APPROACH, 0, 20000);
            long cueT = approach + 100;
            rig.cue(cueT, Ears.Kind.WAKE_WORD, Ears.Side.UNKNOWN, Float.NaN);
            rig.runUntil(cueT + 3000);
            check(n, approach > 0 && answerAt(rig, cueT) == cueT
                            && rig.counted(ExploreBrain.Gauges.Counter.SEARCHES) == 1 && rig.violations.isEmpty(),
                    "approach@" + approach + " " + gauges(rig) + " " + rig.tail());
        });
        scenario("call_an_already_called_end_of_utterance_makes_no_second_call", n -> {
            Rig rig = cueRig(EMPTY_ROOM);
            List<String> notes = traced(rig);
            rig.started();
            rig.cue(400, Ears.Kind.WAKE_WORD, Ears.Side.LEFT, -60f);
            rig.calledCue(1400, 400, Ears.Kind.WAKE_WORD, Ears.Side.RIGHT, 90f);
            rig.runUntil(6000);
            check(n, answerAt(rig, 400) == 400 && rig.count("react answer") == 1
                            && rig.counted(ExploreBrain.Gauges.Counter.SEARCHES) == 1
                            && rig.counted(ExploreBrain.Gauges.Counter.RETARGETS) == 0
                            && rig.count("eyes GLANCE RIGHT") == 0 && rig.counted(ExploreBrain.Gauges.Counter.CUES) == 2
                            && notesWith(notes, "already called") == 1 && rig.violations.isEmpty(),
                    gauges(rig) + " " + rig.tail());
        });
    }

    // ---- finding and reaching the caller (hey-miko plan U6; R5-R7, R9, R10; KTD6-KTD9; AE4, AE5) ----
    //
    // After the answer he looks for the caller: with an angle, at the bearing corrected
    // for his own turning since the call was heard, then its two 45 deg neighbours; with
    // none, eight 45 deg looks over one circle from straight ahead. Any person box tall
    // enough is found (no facing-face gate). A far one is approached to politeHeight and
    // met; a near one is met where it stands. Nobody: "Where'd you go?", then a listen.

    /** A person box of this shape at a bearing (yaw frame, left positive), in view within halfViewDeg. */
    private static Vision boxAt(double bearingLeftDeg, double halfViewDeg, Detection box) {
        return (r, t) -> r.yaw != null && Math.abs(Heading.delta(r.yaw.wrapped(), Heading.wrap(bearingLeftDeg)))
                <= halfViewDeg ? list(box) : list();
    }

    /**
     * A far person at a bearing: 0.3 of the frame tall, 0.15 taller per approach leg driven
     * since his last turn to a voice began (polite after 2); each search finds them far again.
     */
    private static Vision farPersonAt(double bearingLeftDeg, double halfViewDeg) {
        return (r, t) -> r.yaw != null && Math.abs(Heading.delta(r.yaw.wrapped(), Heading.wrap(bearingLeftDeg)))
                <= halfViewDeg ? list(box("person", 0.9f, 0.5f, 0.5f, 0.2f,
                Math.min(1f, 0.3f + 0.15f * legsSinceSearch(r)))) : list();
    }

    /** APPROACH legs since the last entry to CUE_TURN or CUE_LOOK. */
    private static int legsSinceSearch(Rig rig) {
        long from = -1;
        for (Event e : rig.stateLog) {
            if (e.what.equals("CUE_TURN") || e.what.equals("CUE_LOOK")) {
                from = e.t;
            }
        }
        return from < 0 ? 0 : drivesIn(rig, "APPROACH", "hop", from, Long.MAX_VALUE).size();
    }

    /** A call at t whose angle points at this bearing (yaw frame, left positive) from wherever he faces then. */
    private static void callToward(Rig rig, long t, double bearingLeftDeg) {
        rig.at(t, () -> {
            float angle = (float) -Heading.delta(rig.yaw.wrapped(), Heading.wrap(bearingLeftDeg));
            rig.cues.add(Ears.Cue.of(Ears.Kind.WAKE_WORD, angle < 0 ? Ears.Side.LEFT : Ears.Side.RIGHT, angle, t));
            rig.log.add(new Event(t, "cue WAKE_WORD toward " + Math.round(bearingLeftDeg)));
        });
    }

    /** The true signed turns (left positive) he made from the nth on. */
    private static List<Long> turnsFrom(Rig rig, int first) {
        List<Long> out = new ArrayList<Long>();
        for (int i = first; i < rig.yaw.turnResults.size(); i++) {
            out.add(Math.round(rig.yaw.turnResults.get(i)));
        }
        return out;
    }

    /** From the call at 400 ms to a person found in front (CALL_FACING), for a person behind; -1 if never. */
    private static long behindSearchMs(long lookEveryMs, long settleMs, long callLookMs) {
        ExploreTuning d = new ExploreTuning.Builder().build();
        Rig rig = cueRig(cueTuning().lookTiming(settleMs, 3000, 2000)
                .call(callLookMs, d.callPersonMinHeight, d.callNearHeight, d.callListenMs), CLEAR, personAt(180, 25));
        rig.lookEveryMs = lookEveryMs;
        rig.started();
        rig.cue(400, Ears.Kind.WAKE_WORD, Ears.Side.UNKNOWN, Float.NaN);
        rig.runUntil(40000);
        long facing = rig.stamped(ExploreBrain.Gauges.Stage.CALL_FACING);
        return facing < 0 ? -1 : facing - 400;
    }

    private static void callFindScenarios() {
        scenario("call_ae4_no_angle_a_person_directly_behind_is_found_on_the_fifth_look_and_faced", n -> {
            Rig rig = cueRig(personAt(180, 25)).started();
            rig.cue(400, Ears.Kind.WAKE_WORD, Ears.Side.UNKNOWN, Float.NaN);
            rig.runUntil(400);
            long match = runUntilEvent(rig, "match", 400, 40000);
            List<Long> looks = entries(rig, ExploreBrain.State.CUE_LOOK);
            long facing = rig.stamped(ExploreBrain.Gauges.Stage.CALL_FACING);
            check(n, match > 0 && looks.size() == 5 && Math.abs(Heading.delta(rig.yaw.wrapped(), 180)) <= 25
                            && rig.count("react answer") == 1 && rig.count("react where") == 0
                            && rig.stamped(ExploreBrain.Gauges.Stage.CALL_HEARD) == 400
                            && rig.stamped(ExploreBrain.Gauges.Stage.CALL_ANSWERED) == 400
                            && facing >= looks.get(4) && facing <= match
                            && rig.counted(ExploreBrain.Gauges.Counter.FACES_FOUND) == 1
                            && rig.counted(ExploreBrain.Gauges.Counter.SEARCHES) == 1 && rig.violations.isEmpty(),
                    "match@" + match + " looks=" + looks + " yaw=" + f1(rig.yaw.wrapped()) + " " + gauges(rig) + " "
                            + rig.tail());
        });
        scenario("call_no_angle_and_nobody_makes_eight_45_deg_looks_one_full_circle_then_where", n -> {
            Rig rig = cueRig(EMPTY_ROOM).started();
            int before = rig.yaw.turnResults.size();
            double start = rig.yaw.trueDeg;
            rig.cue(400, Ears.Kind.WAKE_WORD, Ears.Side.UNKNOWN, Float.NaN);
            rig.runUntil(400);
            long where = runUntilEvent(rig, "react where", 400, 60000);
            List<Long> looks = entries(rig, ExploreBrain.State.CUE_LOOK);
            List<Long> turns = turnsFrom(rig, before);
            boolean steps = turns.size() == 7;
            for (long t : turns) {
                steps &= Math.abs(Math.abs(t) - 45) <= 8 && Math.signum(t) == Math.signum(turns.get(0));
            }
            System.out.println("REPORT call search, a full empty circle: " + (where - 400) + " ms to the where clip");
            check(n, where > 0 && looks.size() == 8 && steps && Math.abs(Math.abs(rig.yaw.trueDeg - start) - 315) <= 20
                            && rig.timeOf(rig.firstAfter("clip " + rig.tuning.whereClipMs, 400)) == where
                            && entered(rig, ExploreBrain.State.CUE_WHERE, 400) == where
                            && rig.count("react answer") == 1 && rig.count("match") == 0 && rig.violations.isEmpty(),
                    "where@" + where + " looks=" + looks + " turns=" + turns + " " + gauges(rig) + " " + rig.tail());
        });
        scenario("call_ae5_after_where_a_new_wake_word_restarts_the_search_every_time_without_a_second_answer", n -> {
            Rig rig = cueRig(EMPTY_ROOM).started();
            rig.cue(400, Ears.Kind.WAKE_WORD, Ears.Side.UNKNOWN, Float.NaN);
            rig.runUntil(400);
            long where1 = runUntilEvent(rig, "react where", 400, 60000);
            long t2 = where1 + 2000;
            rig.cue(t2, Ears.Kind.WAKE_WORD, Ears.Side.UNKNOWN, Float.NaN);
            rig.runUntil(t2);
            long search2 = searchFrom(rig, t2);
            long where2 = runUntilEvent(rig, "react where", t2, t2 + 60000);
            long t3 = where2 + 2000;
            rig.cue(t3, Ears.Kind.NAME, Ears.Side.UNKNOWN, Float.NaN);
            rig.runUntil(t3);
            long search3 = searchFrom(rig, t3);
            check(n, where1 > 0 && search2 == t2 && where2 > t2 && search3 == t3 && rig.count("react answer") == 1
                            && rig.count("react where") == 2 && rig.counted(ExploreBrain.Gauges.Counter.SEARCHES) == 3
                            && rig.violations.isEmpty(),
                    "where1@" + where1 + " search2@" + search2 + " where2@" + where2 + " search3@" + search3 + " "
                            + gauges(rig) + " " + rig.tail());
        });
        scenario("call_after_where_a_reply_opens_the_meeting_and_silence_resumes_roaming", n -> {
            // Owner 2026-09-30: a reply after "Where'd you go?" is the caller answering, so the call's
            // meeting opens (here with no conversation to become: the stranger's lines), not a search.
            Rig rig = cueRig(EMPTY_ROOM).started();
            rig.cue(400, Ears.Kind.WAKE_WORD, Ears.Side.UNKNOWN, Float.NaN);
            rig.runUntil(400);
            long where1 = runUntilEvent(rig, "react where", 400, 60000);
            long r1 = where1 + 2000;
            rig.cue(r1, Ears.Tier.WEAK, Ears.Side.UNKNOWN, Float.NaN);
            rig.runUntil(r1);
            String inReply = rig.brain.state().name();
            Rig quiet = cueRig(EMPTY_ROOM).started();
            quiet.cue(400, Ears.Kind.WAKE_WORD, Ears.Side.UNKNOWN, Float.NaN);
            quiet.runUntil(400);
            long where2 = runUntilEvent(quiet, "react where", 400, 60000);
            long pause = runUntilState(quiet, ExploreBrain.State.PAUSE, where2, where2 + 20000);
            long listenEnd = where2 + quiet.tuning.whereClipMs + quiet.tuning.callListenMs;
            check(n, where1 > 0 && "MEET".equals(inReply) && rig.count("lines") == 1 && searchFrom(rig, r1) < 0
                            && rig.count("react answer") == 1 && rig.count("react where") == 1
                            && rig.counted(ExploreBrain.Gauges.Counter.SEARCHES) == 1
                            && pause >= listenEnd && pause <= listenEnd + 20 && quiet.count("react answer") == 1
                            && rig.counted(ExploreBrain.Gauges.Counter.LEAN_INS) == 0 && rig.violations.isEmpty()
                            && quiet.violations.isEmpty(),
                    "where1@" + where1 + " in " + inReply + " where2@" + where2 + " pause@" + pause + " "
                            + gauges(rig) + " " + rig.tail());
        });
        scenario("call_angle_plus_90_with_no_turn_since_the_sample_turns_90_right", n -> {
            Rig rig = cueRig(EMPTY_ROOM).started();
            rig.runUntil(400);
            double start = rig.yaw.trueDeg;
            rig.cue(410, Ears.Kind.WAKE_WORD, Ears.Side.RIGHT, 90f);
            long look = runUntilState(rig, ExploreBrain.State.CUE_LOOK, 410, 10000);
            double turned = rig.yaw.trueDeg - start;
            check(n, look > 0 && Math.abs(turned + 90) <= 8 && rig.what(rig.firstAfter("turn", 410)).equals("turn RIGHT")
                            && rig.violations.isEmpty(),
                    "look@" + look + " turned=" + f1(turned) + " " + rig.tail());
        });
        scenario("call_angle_plus_90_after_40_deg_of_right_turning_since_it_was_heard_turns_50", n -> {
            // A weak voice from the right starts a 90 deg right turn; the call was heard as it began
            // and is delivered when he has turned about 40 deg of it.
            Rig rig = cueRig(EMPTY_ROOM).started();
            rig.cue(400, Ears.Tier.WEAK, Ears.Side.RIGHT, Float.NaN);
            long turnAt = runUntilEvent(rig, "turn RIGHT", 400, 10000);
            double atHeading = rig.yaw.trueDeg;
            long t = turnAt + 670;
            rig.lateCue(t, turnAt, Ears.Kind.WAKE_WORD, Ears.Side.RIGHT, 90f);
            rig.runUntil(t);
            double turnedBefore = atHeading - rig.yaw.trueDeg;
            long look = runUntilState(rig, ExploreBrain.State.CUE_LOOK, t, t + 10000);
            double fromHeard = rig.yaw.trueDeg - atHeading;
            check(n, turnAt > 0 && turnedBefore >= 35 && turnedBefore <= 45 && look > 0 && Math.abs(fromHeard + 90) <= 10
                            && answerAt(rig, t) == t && rig.violations.isEmpty(),
                    "turnAt@" + turnAt + " before=" + f1(turnedBefore) + " look@" + look + " fromHeard=" + f1(fromHeard)
                            + " " + rig.tail());
        });
        scenario("call_angle_plus_90_after_40_deg_of_right_turning_without_a_usable_gyro_turns_50", n -> {
            // As above with no gyro calibration: the heading history falls back to the commanded
            // turn at the nominal rate (a circle per escapeSweepMaxMs), and the timed turns run at
            // that rate too. The sim's true yaw turns at the same 60 deg/s, so only the history's
            // own sampling (a reading per 100 ms) and the coast separate it from the truth.
            Rig rig = cueRig(cueTuning().gyro(null).escape(300, 6000, 3, 60000), CLEAR, EMPTY_ROOM);
            rig.yaw = new YawSim(robotGyro());
            rig.started();
            rig.cue(400, Ears.Tier.WEAK, Ears.Side.RIGHT, Float.NaN);
            long turnAt = runUntilEvent(rig, "turn RIGHT", 400, 10000);
            double atHeading = rig.yaw.trueDeg;
            long t = turnAt + 670;
            rig.lateCue(t, turnAt, Ears.Kind.WAKE_WORD, Ears.Side.RIGHT, 90f);
            rig.runUntil(t);
            double atCall = rig.yaw.trueDeg;
            double turnedBefore = atHeading - atCall;
            long look = runUntilState(rig, ExploreBrain.State.CUE_LOOK, t, t + 10000);
            double callTurn = atCall - rig.yaw.trueDeg;
            check(n, turnAt > 0 && !rig.brain.heading().usable(rig.now) && turnedBefore >= 35 && turnedBefore <= 45
                            && look > 0 && Math.abs(callTurn - 50) <= 12
                            && rig.what(rig.firstAfter("turn", t)).equals("turn RIGHT")
                            && answerAt(rig, t) == t && rig.violations.isEmpty(),
                    "turnAt@" + turnAt + " before=" + f1(turnedBefore) + " look@" + look + " callTurn=" + f1(callTurn)
                            + " " + rig.tail());
        });
        scenario("heading_history_without_the_gyro_counts_a_commanded_right_turn_at_the_nominal_rate", n -> {
            // Heading's convention: degrees positive LEFT, so RIGHT (-1) for 400 ms is -400 x the rate.
            double rate = 360.0 / 6000;
            Heading.History h = new Heading.History(3000, 50);
            long t0 = 1000;
            for (long t = t0; t <= t0 + 400; t += 100) {
                h.offer(t, Double.NaN, Heading.RIGHT, rate);
            }
            double turned = h.turnedSince(t0);
            double mid = h.turnedSince(t0 + 200);
            Heading.History still = new Heading.History(3000, 50);
            for (long t = t0; t <= t0 + 400; t += 100) {
                still.offer(t, Double.NaN, 0, rate);
            }
            check(n, Heading.RIGHT == -1 && Math.abs(turned - (-400 * rate)) < 1e-6
                            && Math.abs(mid - (-200 * rate)) < 1e-6 && still.turnedSince(t0) == 0,
                    "turned=" + turned + " mid=" + mid + " still=" + still.turnedSince(t0));
        });
        scenario("call_angle_with_nobody_at_the_bearing_looks_at_both_45_deg_neighbours_then_where", n -> {
            Rig rig = cueRig(EMPTY_ROOM).started();
            int before = rig.yaw.turnResults.size();
            rig.cue(400, Ears.Kind.WAKE_WORD, Ears.Side.RIGHT, 90f);
            long where = runUntilEvent(rig, "react where", 400, 30000);
            List<Long> turns = turnsFrom(rig, before);
            // Right 90 to the bearing (in 45 deg steps), then the neighbours 45 either side of it.
            int k = turns.size();
            long first = 0;
            for (int i = 0; i < k - 2; i++) {
                first += turns.get(i);
            }
            boolean shape = k >= 3 && Math.abs(first + 90) <= 10 && Math.abs(Math.abs(turns.get(k - 2)) - 45) <= 10
                    && Math.abs(Math.abs(turns.get(k - 1)) - 90) <= 12
                    && Math.signum(turns.get(k - 2)) == -Math.signum(turns.get(k - 1));
            check(n, where > 0 && entries(rig, ExploreBrain.State.CUE_LOOK).size() == 3 && shape
                            && rig.count("react answer") == 1 && rig.violations.isEmpty(),
                    "where@" + where + " turns=" + turns + " looks=" + entries(rig, ExploreBrain.State.CUE_LOOK) + " "
                            + rig.tail());
        });
        scenario("call_two_person_boxes_the_one_nearest_the_bearing_is_chosen", n -> {
            Detection nearBearing = box("person", 0.9f, 0.45f, 0.5f, 0.2f, 0.5f);
            Detection bigger = box("person", 0.9f, 0.92f, 0.5f, 0.15f, 0.8f);
            Rig rig = cueRig((r, t) -> r.yaw != null && Math.abs(Heading.delta(r.yaw.wrapped(), Heading.wrap(-90))) <= 25
                    ? list(nearBearing, bigger) : list()).started();
            rig.cue(400, Ears.Kind.WAKE_WORD, Ears.Side.RIGHT, 90f);
            long match = runUntilEvent(rig, "match", 400, 20000);
            check(n, match > 0 && rig.matchBoxes.size() == 1 && rig.matchBoxes.get(0) == nearBearing
                            && rig.violations.isEmpty(),
                    "match@" + match + " boxes=" + rig.matchBoxes + " " + rig.tail());
        });
        scenario("call_far_box_faces_and_approaches_to_polite_then_meets_without_the_leave_alone_checks", n -> {
            Rig rig = chatRig(cueTuning(), CLEAR, farPersonAt(90, 25), true);
            rig.started();
            rig.people.listen = ListenScript.turns(hearWords("hi"), hearWords("catch you later"), hearWords("hi again"));
            List<String> notes = traced(rig);
            long open = openChat(rig);
            long over = chatOver(rig, open);
            long cueT = over + 120000;
            rig.runUntil(cueT - 10);
            callToward(rig, cueT, 90);
            long meet = runUntilState(rig, ExploreBrain.State.MEET, cueT, cueT + 40000);
            long approach = entered(rig, ExploreBrain.State.APPROACH, cueT);
            long chat = runUntilState(rig, ExploreBrain.State.CHAT_THINK, cueT, cueT + 40000);
            Detection met = rig.matchBoxes.isEmpty() ? null : rig.matchBoxes.get(rig.matchBoxes.size() - 1);
            check(n, open > 0 && over > 0 && anyContains(notes, "conversation with someone named over")
                            && approach > cueT && meet > approach
                            && rig.countPrefix("hop", approach, meet) > 0 && met != null
                            && met.height() >= rig.tuning.politeHeight && rig.matchBoxes.size() == 2
                            && checksIn(rig, cueT, rig.now).isEmpty() && chat > meet
                            && rig.stamped(ExploreBrain.Gauges.Stage.CALL_ARRIVED) == chat
                            && rig.stamped(ExploreBrain.Gauges.Stage.CALL_FACING) >= cueT
                            && rig.stamped(ExploreBrain.Gauges.Stage.CALL_FACING) <= approach && rig.violations.isEmpty(),
                    "open@" + open + " over@" + over + " approach@" + approach + " meet@" + meet
                            + " chat@" + chat + " met=" + met + " " + gauges(rig) + " " + rig.tail());
        });
        scenario("call_near_box_goes_straight_to_the_meeting_with_no_approach_legs", n -> {
            Rig rig = cueRig(personAt(90, 25)).started();
            rig.cue(400, Ears.Kind.WAKE_WORD, Ears.Side.LEFT, -90f);
            long meet = runUntilState(rig, ExploreBrain.State.MEET, 400, 20000);
            check(n, meet > 0 && entered(rig, ExploreBrain.State.FACE, 400) < 0
                            && entered(rig, ExploreBrain.State.APPROACH, 400) < 0
                            && entered(rig, ExploreBrain.State.MEET_LOOK, 400) < 0
                            && rig.countPrefix("hop", 400, meet + 1) == 0 && rig.count("match") == 1
                            && rig.count("react acknowledge") == 0 && rig.violations.isEmpty(),
                    "meet@" + meet + " " + rig.tail());
        });
        scenario("call_a_tall_narrow_standing_person_counts_as_found", n -> {
            Detection standing = box("person", 0.9f, 0.5f, 0.5f, 0.15f, 0.5f);
            Rig rig = cueRig(boxAt(90, 25, standing)).started();
            rig.cue(400, Ears.Kind.WAKE_WORD, Ears.Side.LEFT, -90f);
            long match = runUntilEvent(rig, "match", 400, 20000);
            check(n, !facing(standing, rig.tuning) && Math.abs(standing.width() / standing.height() - 0.3f) < 1e-5
                            && match > 0 && rig.matchBoxes.size() == 1 && rig.matchBoxes.get(0) == standing
                            && rig.count("react where") == 0 && rig.violations.isEmpty(),
                    "match@" + match + " " + rig.tail());
        });
        scenario("call_hazard_during_its_approach_hands_the_call_back_and_it_is_retaken_after_the_escape", n -> {
            long[] edge = {Long.MAX_VALUE};
            Rig rig = cueRig(cueTuning(), t -> t >= edge[0] && t < edge[0] + 200 ? edgeAhead(t) : clear(t),
                    farPersonAt(90, 25));
            List<String> notes = traced(rig);
            rig.started();
            rig.cue(400, Ears.Kind.WAKE_WORD, Ears.Side.LEFT, -90f);
            long leg = runUntilEvent(rig, "hop", 400, 30000);
            edge[0] = (leg / 100 + 1) * 100;
            long back = runUntilState(rig, ExploreBrain.State.BACK_OFF, leg, leg + 5000);
            long retaken = runUntilState(rig, ExploreBrain.State.CUE_TURN, back, back + 20000);
            long look = searchFrom(rig, back);
            long meet = runUntilState(rig, ExploreBrain.State.MEET, back, back + 60000);
            check(n, leg > 0 && back > leg && look > back && meet > look
                            && notesWith(notes, "during the call's search or approach: the call waits") == 1
                            && rig.count("react answer") == 1 && rig.count("match") == 1 && rig.violations.isEmpty(),
                    "leg@" + leg + " back@" + back + " retaken@" + retaken + " look@" + look + " meet@" + meet + " "
                            + gauges(rig) + " " + rig.tail());
        });
        scenario("call_during_its_search_a_call_from_the_other_side_retargets_and_one_with_no_angle_opens_the_meeting", n -> {
            Rig re = cueRig(EMPTY_ROOM).started();
            re.cue(400, Ears.Kind.NAME, Ears.Side.LEFT, -90f);
            re.cue(1400, Ears.Kind.WAKE_WORD, Ears.Side.RIGHT, 60f);
            re.runUntil(4000);
            int after = re.firstAfter("turn", 1400);
            Rig merged = cueRig(EMPTY_ROOM).started();
            merged.cue(400, Ears.Kind.NAME, Ears.Side.LEFT, -90f);
            merged.cue(1400, Ears.Kind.WAKE_WORD, Ears.Side.UNKNOWN, Float.NaN);
            merged.runUntil(4000);
            check(n, re.what(after).equals("turn RIGHT") && re.timeOf(after) <= 1400 + re.tuning.lookLeadMs + 20
                            && re.counted(ExploreBrain.Gauges.Counter.RETARGETS) == 1 && re.count("react answer") == 1
                            && merged.counted(ExploreBrain.Gauges.Counter.RETARGETS) == 0
                            && merged.counted(ExploreBrain.Gauges.Counter.SEARCHES) == 1
                            && merged.count("react answer") == 1 && merged.count("eyes GLANCE LEFT") == 1
                            && merged.countPrefix("turn RIGHT", 1400, 4000) == 0 && merged.count("lines") == 1
                            && entered(merged, ExploreBrain.State.MEET, 1400) > 1400 && re.violations.isEmpty()
                            && merged.violations.isEmpty(),
                    "re: " + gauges(re) + " " + re.tail() + " | merged: " + gauges(merged) + " " + merged.tail());
        });
        scenario("call_during_its_approach_a_far_side_angle_retargets_and_no_angle_merges", n -> {
            Rig re = cueRig(farPersonAt(90, 25)).started();
            re.cue(400, Ears.Kind.WAKE_WORD, Ears.Side.LEFT, -90f);
            long approach = runUntilState(re, ExploreBrain.State.APPROACH, 400, 30000);
            long t = approach + 50;
            re.cue(t, Ears.Kind.WAKE_WORD, Ears.Side.RIGHT, 120f);
            re.runUntil(t + 3000);
            Rig merged = cueRig(farPersonAt(90, 25)).started();
            merged.cue(400, Ears.Kind.WAKE_WORD, Ears.Side.LEFT, -90f);
            long approach2 = runUntilState(merged, ExploreBrain.State.APPROACH, 400, 30000);
            long t2 = approach2 + 50;
            merged.cue(t2, Ears.Kind.WAKE_WORD, Ears.Side.UNKNOWN, Float.NaN);
            long meet = runUntilState(merged, ExploreBrain.State.MEET, t2, t2 + 30000);
            check(n, approach > 0 && searchFrom(re, t) == t && re.counted(ExploreBrain.Gauges.Counter.RETARGETS) == 1
                            && re.count("react answer") == 1 && re.what(re.firstAfter("turn", t)).equals("turn RIGHT")
                            && approach2 > 0 && meet > t2 && searchFrom(merged, t2) < 0
                            && merged.counted(ExploreBrain.Gauges.Counter.RETARGETS) == 0
                            && merged.count("react answer") == 1 && re.violations.isEmpty() && merged.violations.isEmpty(),
                    "re: approach@" + approach + " " + gauges(re) + " " + re.tail() + " | merged: meet@" + meet + " "
                            + gauges(merged) + " " + merged.tail());
        });
        scenario("call_one_s_after_a_camera_close_turns_at_once_and_the_first_look_waits_for_the_reopen_gap", n -> {
            // Claude never answers the look request, so ASK (camera closed) lasts its 4 s try. The
            // caller stands 45 deg to his right as the call lands (the scan has turned him by then).
            double[] caller = {Double.NaN};
            Rig rig = new Rig(claudeTuning().gyro(robotGyro()).cueTurn(45, 90, 500, 2000, 600).reopenGapMs(3000).build(),
                    CLEAR, (r, t) -> !Double.isNaN(caller[0]) && r.yaw != null
                            && Math.abs(Heading.delta(r.yaw.wrapped(), Heading.wrap(caller[0]))) <= 25
                            ? list(facingPerson()) : list(), true, (r, req, nth) -> null);
            rig.people.match = (r, k) -> CuriosityPort.MatchAnswer.known("Sarah", "Hi {name}!", "Hello again!");
            rig.people.lines = STRANGER;
            rig.reopenGapMs = 3000;
            List<String> notes = traced(rig);
            rig.started();
            long ask = runUntilEvent(rig, "ask", 0, 20000);
            long close = rig.timeOf(rig.firstAfter("camera close", ask - 1));
            long cueT = close + 1000;
            rig.at(cueT, () -> caller[0] = rig.yaw.wrapped() - 45);
            rig.cue(cueT, Ears.Kind.WAKE_WORD, Ears.Side.RIGHT, 45f);
            long match = runUntilEvent(rig, "match", cueT, cueT + 20000);
            int turn = rig.firstAfter("turn", cueT);
            long frame = match < 0 ? -1 : Long.parseLong(rig.meets.get(0).substring(5, rig.meets.get(0).indexOf(' ')));
            check(n, ask > 0 && close >= ask && answerAt(rig, cueT) == cueT && rig.what(turn).equals("turn RIGHT")
                            && rig.timeOf(turn) <= cueT + rig.tuning.lookLeadMs + 20
                            && rig.timeOf(turn) < close + 3000 && match > 0 && frame >= close + 3000
                            && !rig.brain.cameraBackedOff() && rig.count("react where") == 0 && rig.violations.isEmpty(),
                    "ask@" + ask + " close@" + close + " turn@" + rig.timeOf(turn) + " match@" + match + " frame@" + frame
                            + " notes=" + notes + " " + rig.tail());
        });
        scenario("call_search_time_for_a_person_directly_behind_is_reported_and_found_at_the_robots_look_rate", n -> {
            // Robot QA (2026-09-30) measured live looks of 1.4-2.4 s, so the looks-every-2000 ms
            // figure is the robot's and callLookMs is now 2500; the old 700 ms placeholder is
            // reported alongside (it never found them at that rate). An empty look ends at its first
            // fresh frame, so the Success Criteria's "facing within about 12 s" holds at the robot's rate.
            long budget = new ExploreTuning.Builder().build().callLookMs;
            long fast = behindSearchMs(500, 200, budget);
            long settled = behindSearchMs(500, 400, budget);
            long slowCamera = behindSearchMs(1000, 400, budget);
            long robotCamera = behindSearchMs(2000, 400, budget);
            long robotCameraOld = behindSearchMs(2000, 400, 700);
            System.out.println("REPORT call search, a person directly behind: " + fast + " ms (looks every 500 ms, settle"
                    + " 200 ms, callLookMs " + budget + "), " + settled + " ms (settle 400 ms), " + slowCamera
                    + " ms (looks every 1000 ms), " + robotCamera + " ms (looks every 2000 ms), " + robotCameraOld
                    + " ms (looks every 2000 ms, callLookMs 700; -1 is never found)");
            check(n, fast > 0 && settled > 0 && slowCamera > 0 && robotCamera > 0 && robotCamera <= 12000,
                    "fast=" + fast + " settled=" + settled + " slowCamera=" + slowCamera + " robot=" + robotCamera
                            + " old=" + robotCameraOld);
        });
    }

    // ---- a call with a side but no angle (hey-miko plan R7, robot QA 2026-09-30) ----
    //
    // The direction chip gives LEFT or RIGHT with no angle: he looks to that side first,
    // at 90 deg, then its two 45 deg neighbours (45 and 135), then the rest of the circle
    // (ahead, the other side, behind). The robot's live looks took 1.4-2.4 s each and the
    // detector scored a floor-level caller 0.27-0.32, so the call has its own longer look
    // budget and its own lower person floor.

    /** A call of this side and no angle at 410 ms; the heading (yaw frame, left positive) it was heard at. */
    private static double sideCall(Rig rig, Ears.Side side) {
        rig.runUntil(400);
        double start = rig.yaw.wrapped();
        rig.cue(410, Ears.Kind.WAKE_WORD, side, Float.NaN);
        return start;
    }

    /** Where each look of the call's search faced, relative to the heading at the call (left positive). */
    private static List<Long> lookBearings(Rig rig, double start) {
        List<Long> out = new ArrayList<Long>();
        for (double y : rig.cueLookYaws) {
            out.add(Math.round(Heading.delta(start, y)));
        }
        return out;
    }

    /** Whether each bearing is within tol of the expected one (yaw frame; 180 and -180 are the same). */
    private static boolean bearingsNear(List<Long> got, int tol, int... expected) {
        if (got.size() != expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (Math.abs(Heading.delta(expected[i], got.get(i))) > tol) {
                return false;
            }
        }
        return true;
    }

    /** A side call with no angle and a person at this bearing: cue to CALL_FACING in ms, or -1 if never found. */
    private static long sideSearchMs(Ears.Side side, double bearingLeftDeg, long lookEveryMs, ExploreTuning.Builder b) {
        Rig rig = cueRig(b, CLEAR, personAt(bearingLeftDeg, 25));
        rig.lookEveryMs = lookEveryMs;
        rig.started();
        sideCall(rig, side);
        rig.runUntil(40000);
        long facing = rig.stamped(ExploreBrain.Gauges.Stage.CALL_FACING);
        return facing < 0 ? -1 : facing - 410;
    }

    private static void callSideScenarios() {
        scenario("call_left_side_no_angle_turns_about_90_left_first_and_finds_a_person_there_on_look_1", n -> {
            Rig rig = cueRig(personAt(90, 25)).started();
            double start = sideCall(rig, Ears.Side.LEFT);
            long match = runUntilEvent(rig, "match", 410, 30000);
            List<Long> looks = lookBearings(rig, start);
            check(n, match > 0 && rig.what(rig.firstAfter("turn", 410)).equals("turn LEFT")
                            && bearingsNear(looks, 12, 90) && rig.count("react answer") == 1
                            && rig.count("react where") == 0 && rig.violations.isEmpty(),
                    "match@" + match + " looks=" + looks + " " + rig.tail());
        });
        scenario("call_left_side_no_angle_finds_a_person_at_135_left_on_look_2_or_3", n -> {
            Rig rig = cueRig(personAt(135, 25)).started();
            double start = sideCall(rig, Ears.Side.LEFT);
            long match = runUntilEvent(rig, "match", 410, 30000);
            List<Long> looks = lookBearings(rig, start);
            check(n, match > 0 && looks.size() >= 2 && looks.size() <= 3
                            && Math.abs(Heading.delta(135, looks.get(looks.size() - 1))) <= 12
                            && rig.count("react where") == 0 && rig.violations.isEmpty(),
                    "match@" + match + " looks=" + looks + " " + rig.tail());
        });
        scenario("call_right_side_no_angle_mirrors_the_left_side_first_search", n -> {
            Rig at90 = cueRig(personAt(-90, 25)).started();
            double s90 = sideCall(at90, Ears.Side.RIGHT);
            long m90 = runUntilEvent(at90, "match", 410, 30000);
            List<Long> l90 = lookBearings(at90, s90);
            Rig at135 = cueRig(personAt(-135, 25)).started();
            double s135 = sideCall(at135, Ears.Side.RIGHT);
            long m135 = runUntilEvent(at135, "match", 410, 30000);
            List<Long> l135 = lookBearings(at135, s135);
            check(n, m90 > 0 && at90.what(at90.firstAfter("turn", 410)).equals("turn RIGHT")
                            && bearingsNear(l90, 12, -90) && m135 > 0 && l135.size() >= 2 && l135.size() <= 3
                            && Math.abs(Heading.delta(-135, l135.get(l135.size() - 1))) <= 12
                            && at90.violations.isEmpty() && at135.violations.isEmpty(),
                    "90: match@" + m90 + " looks=" + l90 + " 135: match@" + m135 + " looks=" + l135 + " "
                            + at135.tail());
        });
        scenario("call_side_no_angle_and_nobody_looks_side_neighbours_then_the_rest_of_one_circle", n -> {
            Rig left = cueRig(EMPTY_ROOM).started();
            double sl = sideCall(left, Ears.Side.LEFT);
            long whereL = runUntilEvent(left, "react where", 410, 60000);
            List<Long> ll = lookBearings(left, sl);
            Rig right = cueRig(EMPTY_ROOM).started();
            double sr = sideCall(right, Ears.Side.RIGHT);
            long whereR = runUntilEvent(right, "react where", 410, 60000);
            List<Long> lr = lookBearings(right, sr);
            System.out.println("REPORT call search order, LEFT side no angle: " + ll + "; RIGHT: " + lr
                    + " (deg, left positive)");
            check(n, whereL > 0 && bearingsNear(ll, 15, 90, 135, 45, 0, -45, -90, -135, 180)
                            && whereR > 0 && bearingsNear(lr, 15, -90, -135, -45, 0, 45, 90, 135, 180)
                            && left.count("react answer") == 1 && right.count("react answer") == 1
                            && left.violations.isEmpty() && right.violations.isEmpty(),
                    "left where@" + whereL + " looks=" + ll + " right where@" + whereR + " looks=" + lr);
        });
        scenario("call_with_no_side_and_no_angle_still_starts_straight_ahead", n -> {
            Rig rig = cueRig(EMPTY_ROOM).started();
            double start = sideCall(rig, Ears.Side.UNKNOWN);
            long where = runUntilEvent(rig, "react where", 410, 60000);
            List<Long> looks = lookBearings(rig, start);
            Rig ahead = cueRig(personAt(0, 25)).started();
            double s0 = sideCall(ahead, Ears.Side.UNKNOWN);
            long match = runUntilEvent(ahead, "match", 410, 30000);
            check(n, where > 0 && looks.size() == 8 && Math.abs(looks.get(0)) <= 8
                            && Math.abs(Math.abs(looks.get(1)) - 45) <= 12 && match > 0
                            && bearingsNear(lookBearings(ahead, s0), 8, 0) && ahead.firstAfter("turn", 410) < 0
                            && rig.violations.isEmpty() && ahead.violations.isEmpty(),
                    "where@" + where + " looks=" + looks + " match@" + match + " " + ahead.tail());
        });
        scenario("call_a_person_box_scoring_0_28_counts_during_a_call_search", n -> {
            Detection dim = box("person", 0.28f, 0.5f, 0.5f, 0.3f, 0.4f);
            Rig rig = cueRig(boxAt(90, 25, dim)).started();
            rig.cue(400, Ears.Kind.WAKE_WORD, Ears.Side.LEFT, -90f);
            long match = runUntilEvent(rig, "match", 400, 20000);
            check(n, rig.tuning.callPersonMinScore == 0.25f && dim.score < rig.tuning.confidenceFloor
                            && match > 0 && rig.matchBoxes.size() == 1 && rig.matchBoxes.get(0) == dim
                            && rig.count("react where") == 0 && rig.violations.isEmpty(),
                    "match@" + match + " floor=" + rig.tuning.confidenceFloor + " " + rig.tail());
        });
        scenario("call_score_floor_does_not_loosen_a_roaming_person_pick", n -> {
            // The same 0.28 person box ahead while roaming: under confidenceFloor, so no meeting.
            Rig dim = peopleRig(peopleTuning(), (r, t) -> list(box("person", 0.28f, 0.5f, 0.5f, 0.3f, 0.4f)));
            dim.started();
            dim.runUntil(15000);
            Rig clear = peopleRig(peopleTuning(), (r, t) -> list(box("person", 0.9f, 0.5f, 0.5f, 0.3f, 0.4f)));
            clear.started();
            clear.runUntil(15000);
            check(n, dim.tuning.confidenceFloor > 0.28f && dim.count("match") == 0
                            && entered(dim, ExploreBrain.State.FACE, 0) < 0
                            && entered(dim, ExploreBrain.State.APPROACH, 0) < 0
                            && clear.count("match") == 1 && dim.violations.isEmpty(),
                    "dim=" + dim.tail() + " clear matches=" + clear.count("match"));
        });
        scenario("call_full_height_box_on_the_first_look_goes_straight_to_the_meeting", n -> {
            // The robot's own sighting: a seated person's jeans filling the frame, person 0.32 [0,0,0.38,1].
            Detection jeans = new Detection("person", 0.32f, 0f, 0f, 0.38f, 1f);
            Rig rig = cueRig(boxAt(90, 25, jeans)).started();
            double start = sideCall(rig, Ears.Side.LEFT);
            long meet = runUntilState(rig, ExploreBrain.State.MEET, 410, 30000);
            check(n, meet > 0 && bearingsNear(lookBearings(rig, start), 12, 90)
                            && entered(rig, ExploreBrain.State.FACE, 410) < 0
                            && entered(rig, ExploreBrain.State.APPROACH, 410) < 0
                            && rig.countPrefix("hop", 410, meet + 1) == 0 && rig.matchBoxes.size() == 1
                            && rig.matchBoxes.get(0) == jeans && rig.violations.isEmpty(),
                    "meet@" + meet + " looks=" + lookBearings(rig, start) + " " + rig.tail());
        });
        scenario("call_looks_every_2000_ms_a_person_at_90_left_is_found", n -> {
            // The robot's looks took 1.4-2.4 s; at the old 700 ms budget most stops ended with no frame.
            ExploreTuning d = new ExploreTuning.Builder().build();
            long slow = sideSearchMs(Ears.Side.LEFT, 90, 2000, cueTuning());
            long fast = sideSearchMs(Ears.Side.LEFT, 90, 500, cueTuning());
            long old = sideSearchMs(Ears.Side.LEFT, 90, 2000, cueTuning()
                    .call(700, d.callPersonMinHeight, d.callNearHeight, d.callListenMs));
            long behind = sideSearchMs(Ears.Side.LEFT, 180, 2000, cueTuning());
            System.out.println("REPORT call search, a LEFT call with no angle and a person directly behind: " + behind
                    + " ms (looks every 2000 ms; the eighth look)");
            System.out.println("REPORT call search, a LEFT call with no angle and a person at 90 deg left: " + slow
                    + " ms (looks every 2000 ms, callLookMs " + d.callLookMs + "), " + fast + " ms (looks every 500 ms), "
                    + old + " ms (looks every 2000 ms, callLookMs 700; -1 is never found)");
            check(n, d.callLookMs == 5000 && slow > 0 && fast > 0 && slow <= 12000,
                    "slow=" + slow + " fast=" + fast + " old=" + old);
        });
        callEmptyLookScenarios();
    }

    /** How long each CUE_LOOK stop lasted (until the next state). */
    private static List<Long> cueLookLengths(Rig rig) {
        List<Long> out = new ArrayList<Long>();
        for (long t : entries(rig, ExploreBrain.State.CUE_LOOK)) {
            out.add(restLength(rig, t));
        }
        return out;
    }

    // An empty call look ends at its first fresh frame (a frame captured after the settle
    // with no caller in it); callLookMs is only the cap for a stop that gets no fresh frame.
    private static void callEmptyLookScenarios() {
        scenario("call_empty_circle_with_looks_every_2000_ms_ends_each_empty_stop_at_its_first_fresh_frame", n -> {
            Rig rig = cueRig(EMPTY_ROOM);
            rig.lookEveryMs = 2000;
            rig.started();
            rig.cue(400, Ears.Kind.WAKE_WORD, Ears.Side.UNKNOWN, Float.NaN);
            rig.runUntil(400);
            long where = runUntilEvent(rig, "react where", 400, 60000);
            List<Long> stops = cueLookLengths(rig);
            boolean short_ = stops.size() == 8;
            for (long s : stops) {
                // The settle, then at most one camera interval for the next frame (plus a tick).
                short_ &= s > 0 && s <= rig.tuning.lookSettleMs + 2000 + 20;
            }
            System.out.println("REPORT call search, a full empty circle with looks every 2000 ms: " + (where - 400)
                    + " ms to the where clip (stops " + stops + " ms)");
            check(n, where > 0 && where - 400 <= 20000 && short_ && rig.count("react answer") == 1
                            && rig.count("match") == 0 && rig.violations.isEmpty(),
                    "where@" + where + " stops=" + stops + " " + rig.tail());
        });
        scenario("call_a_stop_with_no_fresh_frame_still_waits_out_call_look_ms", n -> {
            // The camera delivers looks until 1000 ms, then nothing: every stop waits callLookMs (a call's
            // stop takes frames captured once the turn stopped, so there is no settle wait).
            Rig rig = cueRig((r, t) -> t < 1000 ? list() : null);
            rig.started();
            rig.cue(1500, Ears.Kind.WAKE_WORD, Ears.Side.UNKNOWN, Float.NaN);
            rig.runUntil(1500);
            runUntilEvent(rig, "react where", 1500, 60000);
            List<Long> stops = cueLookLengths(rig);
            long full = rig.tuning.callLookMs;
            boolean capped = stops.size() >= 2;
            for (int i = 1; i < stops.size(); i++) {
                capped &= Math.abs(stops.get(i) - full) <= 20;
            }
            check(n, capped && rig.violations.isEmpty(), "stops=" + stops + " full=" + full + " " + rig.tail());
        });
        roamingPhantomScenarios();
    }

    // ---- a roaming person pick with no face in its box (robot 2026-09-30) ----
    //
    // While roaming, motion-blurred frames scored "person" about 0.5; the face check then
    // found no face and the meeting still opened a conversation with nobody in view, four
    // times in four minutes. A roaming pick whose match finds no face is now dropped quietly
    // and roaming person picks are ignored for phantomPersonCooldownMs. A call's meeting
    // (someone asked for him) keeps the faceless conversation.

    /** No face in the person box, as the adapter answers it: faceless, no band, a conversation attached. */
    private static CuriosityPort.MatchAnswer noFace(Rig r) {
        return CuriosityPort.MatchAnswer.faceless().withMatch(null, null, Float.NaN, 9L)
                .withConversation(r.people.persona, null, null, null);
    }

    /** Roaming with Claude and a conversation possible; a person straight ahead whenever visible(t). */
    private static Rig roamRig(java.util.function.LongPredicate visible, boolean face) {
        return roamRig(personWhen(visible), face);
    }

    private static Rig roamRig(Vision v, boolean face) {
        Rig rig = cueRig(cueTuning(), CLEAR, v);
        rig.people.persona = PERSONA;
        rig.people.match = face
                ? (r, k) -> STRANGER.withMatch(FaceMatcher.Band.WEAK, null, 0.2f, 3L)
                        .withConversation(r.people.persona, null, null, null)
                : (r, k) -> noFace(r);
        return rig;
    }

    /** Events spoken or played after from: say, react or clip lines. */
    private static int spokenAfter(Rig rig, long from) {
        return rig.countPrefix("say ", from, Long.MAX_VALUE) + rig.countPrefix("react ", from, Long.MAX_VALUE)
                + rig.countPrefix("clip ", from, Long.MAX_VALUE);
    }

    private static void roamingPhantomScenarios() {
        scenario("roaming_person_pick_with_no_face_drops_the_meeting_quietly_and_roams_on", n -> {
            Rig rig = roamRig(oncePaused(box("person", 0.9f, 0.5f, 0.5f, 0.3f, 0.3f)), false).started();
            long match = runUntilEvent(rig, "match", 0, 12000);
            rig.runUntil(match + 12000);
            long chat = entered(rig, ExploreBrain.State.CHAT_THINK, 0);
            long pause = entered(rig, ExploreBrain.State.PAUSE, match);
            check(n, match > 0 && chat < 0 && rig.count("match") == 1 && spokenAfter(rig, match) == 0
                            && pause > 0 && pause <= match + 1000 && rig.countPrefix("hop", pause, Long.MAX_VALUE) > 0
                            && rig.violations.isEmpty(),
                    "match@" + match + " chat@" + chat + " pause@" + pause + " " + rig.tail());
        });
        scenario("roaming_blur_seen_again_within_the_phantom_cooldown_is_ignored", n -> {
            // A one-frame phantom at each leg decision, alternating sides, so none persists.
            Rig rig = roamRig(eachPauseAlternating(), false).started();
            long first = runUntilEvent(rig, "match", 0, 12000);
            long cool = rig.tuning.phantomPersonCooldownMs;
            rig.runUntil(first + cool + 20000);
            List<Long> matches = new ArrayList<Long>();
            for (Event e : rig.log) {
                if (e.what.equals("match")) {
                    matches.add(e.t);
                }
            }
            // The next pick waits out the cooldown from the dropped meeting (about a second after the request).
            check(n, first > 0 && cool == 20000 && matches.size() >= 2 && matches.get(1) > first + cool
                            && entered(rig, ExploreBrain.State.CHAT_THINK, 0) < 0 && rig.violations.isEmpty(),
                    "first@" + first + " matches=" + matches + " " + rig.tail());
        });
        scenario("roaming_person_pick_with_a_face_still_meets_and_converses", n -> {
            Rig rig = roamRig(t -> true, true).started();
            long match = runUntilEvent(rig, "match", 0, 12000);
            long chat = runUntilState(rig, ExploreBrain.State.CHAT_THINK, match, match + 10000);
            check(n, match > 0 && chat > 0 && rig.violations.isEmpty(), "match@" + match + " chat@" + chat + " "
                    + rig.tail());
        });
        scenario("call_started_faceless_meeting_still_converses", n -> {
            Rig rig = cueRig(personAt(90, 25));
            rig.people.persona = PERSONA;
            rig.people.match = (r, k) -> noFace(r);
            rig.started();
            rig.cue(400, Ears.Kind.WAKE_WORD, Ears.Side.LEFT, -90f);
            rig.runUntil(400);
            long chat = runUntilState(rig, ExploreBrain.State.CHAT_THINK, 400, 30000);
            check(n, chat > 0 && rig.count("match") == 1 && rig.violations.isEmpty(), "chat@" + chat + " "
                    + rig.tail());
        });
        // ---- Robot 2026-10-01: only a call may open a faceless meeting; a cue's needs a usable face ----
        scenario("cue_weak_then_a_person_box_with_no_face_is_not_met_and_nothing_is_said", n -> {
            String[] detail = {""};
            boolean ok = true;
            for (Ears.Kind kind : new Ears.Kind[] {null, Ears.Kind.GREETING}) {
                Rig rig = cueRig(personAt(90, 25));
                rig.people.persona = PERSONA;
                rig.people.match = (r, k) -> noFace(r);
                List<String> notes = traced(rig);
                rig.started();
                if (kind == null) {
                    rig.cue(400, Ears.Tier.WEAK, Ears.Side.LEFT, -90f);
                } else {
                    rig.cue(400, kind, Ears.Side.LEFT, -90f);
                }
                rig.runUntil(400);
                long match = runUntilEvent(rig, "match", 400, 30000);
                rig.runUntil(Math.max(match, 400) + 12000);
                long chat = entered(rig, ExploreBrain.State.CHAT_THINK, 0);
                long pause = entered(rig, ExploreBrain.State.PAUSE, match);
                boolean one = match > 0 && chat < 0 && rig.count("match") == 1 && rig.countPrefix("say ", match, Long.MAX_VALUE) == 0
                        && rig.turnAsks.isEmpty() && pause > 0 && pause <= match + 1000
                        && noteAt(notes, "a person toward him after the cue") >= 0
                        && noteAt(notes, "a face turned toward him") < 0
                        && noteAt(notes, "no usable face in the cue's person box") >= 0 && rig.violations.isEmpty();
                ok &= one;
                detail[0] += (kind == null ? "weak" : kind) + ": match@" + match + " chat@" + chat + " pause@" + pause
                        + " notes=" + notes + " " + rig.tail() + " | ";
            }
            check(n, ok, detail[0]);
        });
        scenario("cue_weak_then_a_usable_face_meets_and_converses", n -> {
            Rig rig = cueRig(personAt(90, 25));
            rig.people.persona = PERSONA;
            rig.people.match = (r, k) -> STRANGER.withMatch(FaceMatcher.Band.WEAK, null, 0.2f, 3L)
                    .withConversation(r.people.persona, null, null, null);
            rig.started();
            rig.cue(400, Ears.Tier.WEAK, Ears.Side.LEFT, -90f);
            rig.runUntil(400);
            long chat = runUntilState(rig, ExploreBrain.State.CHAT_THINK, 400, 30000);
            check(n, chat > 0 && rig.count("match") == 1 && rig.violations.isEmpty(), "chat@" + chat + " "
                    + rig.tail());
        });
        scenario("call_wake_word_with_no_face_still_opens_with_the_crouch_opener", n -> {
            Rig rig = cueRig(personAt(90, 25));
            rig.people.persona = PERSONA;
            rig.people.match = (r, k) -> noFace(r);
            rig.started();
            rig.cue(400, Ears.Kind.WAKE_WORD, Ears.Side.LEFT, -90f);
            rig.runUntil(400);
            long chat = runUntilState(rig, ExploreBrain.State.CHAT_THINK, 400, 30000);
            runUntil(rig, chat + 20000, r -> !r.turnAsks.isEmpty());
            TurnAsk first = rig.turnAsks.isEmpty() ? null : rig.turnAsks.get(0);
            check(n, chat > 0 && first != null && first.request.faceless && first.request.heard == null
                            && rig.violations.isEmpty(),
                    "chat@" + chat + " asks=" + rig.turnAsks + " " + rig.tail());
        });
        callerTalksScenarios();
    }

    // ---- the caller talks while he looks for them (owner, 2026-09-30) ----
    //
    // "He needs to start talking to people even when he's looking for them." On the robot a
    // caller at his left said "Hey Miko" and kept talking; every word during the search was
    // ignored or merged, he did the whole circle and said "Where'd you go?", a reply to that
    // started another silent search, and when a conversation did open the caller's own
    // "Hey Miko ..." was queued as a new call and the listen ended as "walked off". Now the
    // caller's voice during the search opens the conversation at once (the call's meeting
    // with nobody in view), a reply after "Where'd you go?" does too, and in a conversation a
    // call that cannot be told apart from the partner is the partner speaking.

    /** A rig where a meeting with nobody in view can become a conversation (the lines carry one). */
    private static Rig talkRig(Vision v) {
        Rig rig = chatRig(cueTuning(), CLEAR, v, false);
        rig.people.lines = STRANGER.withConversation(PERSONA, null, null, null);
        return rig;
    }

    /** Whether any trace note contains this text. */
    private static boolean noted(List<String> notes, String text) {
        for (String x : notes) {
            if (x.contains(text)) {
                return true;
            }
        }
        return false;
    }

    private static void callerTalksScenarios() {
        scenario("call_caller_talking_during_the_search_opens_the_conversation_before_the_search_ends", n -> {
            Rig rig = talkRig(EMPTY_ROOM);
            List<String> notes = traced(rig);
            rig.people.listen = ListenScript.turns(hearWords("over here"), hearWords("bye"));
            rig.started();
            sideCall(rig, Ears.Side.LEFT);
            long look1 = runUntilState(rig, ExploreBrain.State.CUE_LOOK, 410, 20000);
            // The caller keeps talking, weak voice from the left, through looks 1 and 2.
            for (long t = look1 + 100; t < look1 + 3000; t += 700) {
                rig.cue(t, Ears.Tier.WEAK, Ears.Side.LEFT, Float.NaN);
            }
            long chat = runUntilState(rig, ExploreBrain.State.CHAT_THINK, look1, look1 + 20000);
            rig.runUntil(Math.max(chat, look1) + 15000);
            check(n, look1 > 0 && chat > 0 && chat <= look1 + 100 + 1500 && rig.count("react where") == 0
                            && rig.cueLookYaws.size() == 1 && rig.count("react answer") == 1 && rig.count("lines") == 1
                            && rig.turnAsks.size() >= 1 && searchFrom(rig, chat) < 0
                            // No match opens it; the conversation's face retries come later (robot 2026-10-01).
                            && (rig.first("match", 0) < 0 || rig.timeOf(rig.first("match", 0)) > chat)
                            && noted(notes, "the caller is talking to him") && rig.violations.isEmpty(),
                    "look1@" + look1 + " chat@" + chat + " looks=" + rig.cueLookYaws.size() + " turns=" + rig.turnAsks
                            + " " + gauges(rig) + " " + rig.tail());
        });
        scenario("call_a_repeated_wake_word_during_the_search_opens_the_conversation", n -> {
            Rig rig = talkRig(EMPTY_ROOM);
            rig.people.listen = ListenScript.turns(hearWords("over here"), hearWords("bye"));
            rig.started();
            sideCall(rig, Ears.Side.LEFT);
            long look1 = runUntilState(rig, ExploreBrain.State.CUE_LOOK, 410, 20000);
            long again = look1 + 200;
            rig.cue(again, Ears.Kind.WAKE_WORD, Ears.Side.UNKNOWN, Float.NaN);
            // Its end-of-utterance delivery, already called: absorbed, nothing more.
            rig.calledCue(again + 900, again, Ears.Kind.WAKE_WORD, Ears.Side.UNKNOWN, Float.NaN);
            long chat = runUntilState(rig, ExploreBrain.State.CHAT_THINK, look1, look1 + 20000);
            rig.runUntil(Math.max(chat, look1) + 15000);
            check(n, look1 > 0 && chat > again && chat <= again + 1500 && rig.count("react where") == 0
                            && rig.count("react answer") == 1 && rig.counted(ExploreBrain.Gauges.Counter.SEARCHES) == 1
                            && searchFrom(rig, chat) < 0 && rig.violations.isEmpty(),
                    "look1@" + look1 + " chat@" + chat + " " + gauges(rig) + " " + rig.tail());
        });
        scenario("call_a_reply_after_where_opens_the_conversation_not_another_search", n -> {
            Rig rig = talkRig(EMPTY_ROOM);
            rig.people.listen = ListenScript.turns(hearWords("over here"), hearWords("bye"));
            rig.started();
            rig.cue(400, Ears.Kind.WAKE_WORD, Ears.Side.UNKNOWN, Float.NaN);
            rig.runUntil(400);
            long where = runUntilEvent(rig, "react where", 400, 60000);
            long reply = where + 1500;
            rig.cue(reply, Ears.Tier.WEAK, Ears.Side.UNKNOWN, Float.NaN);
            long chat = runUntilState(rig, ExploreBrain.State.CHAT_THINK, where, where + 20000);
            rig.runUntil(Math.max(chat, reply) + 15000);
            check(n, where > 0 && chat > reply && chat <= reply + 1500 && searchFrom(rig, reply) < 0
                            && rig.count("react where") == 1 && rig.count("react answer") == 1
                            && rig.counted(ExploreBrain.Gauges.Counter.SEARCHES) == 1 && rig.violations.isEmpty(),
                    "where@" + where + " chat@" + chat + " " + gauges(rig) + " " + rig.tail());
        });
        scenario("call_wake_word_in_chat_listen_of_a_call_opened_conversation_keeps_it_going", n -> {
            Rig rig = talkRig(EMPTY_ROOM);
            List<String> notes = traced(rig);
            // The first conversation listen hears no words (the launcher took the "Hey Miko ..."
            // as a wake word); the next one hears them.
            rig.people.listen = (r, nth) -> nth == 1 ? hearSilence() : nth == 2 ? hearWords("still here")
                    : hearWords("bye");
            rig.started();
            sideCall(rig, Ears.Side.LEFT);
            long look1 = runUntilState(rig, ExploreBrain.State.CUE_LOOK, 410, 20000);
            rig.cue(look1 + 100, Ears.Tier.WEAK, Ears.Side.LEFT, Float.NaN);
            long chat = runUntilState(rig, ExploreBrain.State.CHAT_THINK, look1, look1 + 20000);
            long listen = runUntilEvent(rig, "listen", Math.max(chat, 0), Math.max(chat, 0) + 20000);
            long cueT = listen + 300;
            rig.cue(cueT, Ears.Kind.WAKE_WORD, Ears.Side.LEFT, Float.NaN);
            long over = chatOver(rig, listen);
            rig.runUntil(Math.max(over, rig.now) + 20000);
            check(n, chat > 0 && listen > 0 && "CHAT_LISTEN".equals(stateAt(rig, cueT)) && over > cueT
                            && !noted(notes, "walked off") && !noted(notes, "a call waited for the conversation")
                            && rig.turnAsks.size() >= 2 && searchFrom(rig, cueT) < 0
                            && rig.count("react answer") == 1 && rig.violations.isEmpty(),
                    "chat@" + chat + " listen@" + listen + " cue in " + stateAt(rig, cueT) + " over@" + over
                            + " turns=" + rig.turnAsks + " " + gauges(rig) + " " + rig.tail());
        });
        scenario("call_a_silent_caller_still_gets_the_full_search_and_where", n -> {
            Rig rig = talkRig(EMPTY_ROOM).started();
            double start = sideCall(rig, Ears.Side.LEFT);
            // The call's own end-of-utterance delivery during the first turn opens nothing.
            rig.calledCue(900, 410, Ears.Kind.WAKE_WORD, Ears.Side.LEFT, Float.NaN);
            long where = runUntilEvent(rig, "react where", 410, 90000);
            check(n, where > 0 && bearingsNear(lookBearings(rig, start), 15, 90, 135, 45, 0, -45, -90, -135, 180)
                            && entered(rig, ExploreBrain.State.MEET, 410) < 0
                            && entered(rig, ExploreBrain.State.CHAT_THINK, 410) < 0 && rig.violations.isEmpty(),
                    "where@" + where + " looks=" + lookBearings(rig, start) + " " + rig.tail());
        });
        slowDetectorScenarios();
    }

    // ---- a slow detector during the call's search (robot 2026-09-30) ----
    //
    // Detection took 1.8-4.7 s a frame and the camera skips frames while one is detected, so
    // the first frame taken after a stop was often published 2-5 s later; frames taken during
    // the turn or before lookSettleMs were thrown away even with the caller in them, and he
    // turned past someone he was looking straight at. A frame taken once the turn stopped is
    // fresh now, callLookMs is 5000, a stale look with the caller in it counts when it was
    // taken within callStaleLookDeg of where he faces, and a stale look arriving during the
    // stop means the frame taken since is on its way.

    /** A cue rig whose detector takes detectMs a frame, one frame at a time, arriving on this phase. */
    private static Rig slowRig(Vision v, long detectMs, long phaseMs) {
        // The robot's own settle (400 ms), not the test rigs' 200 ms.
        Rig rig = cueRig(cueTuning().lookTiming(ROBOT_SETTLE_MS, 3000, 2000), CLEAR, v);
        rig.lookEveryMs = detectMs;
        rig.detectMs = detectMs;
        rig.lookPhaseMs = phaseMs;
        return rig;
    }

    private static final long ROBOT_SETTLE_MS = new ExploreTuning.Builder().build().lookSettleMs;

    /** A person at this bearing (yaw frame, left positive), seen from where the frame was taken. */
    private static Vision personAtShot(double bearingLeftDeg, double halfViewDeg) {
        return (r, t) -> r.yaw != null && Math.abs(Heading.delta(r.yawAt(t), Heading.wrap(bearingLeftDeg)))
                <= halfViewDeg ? list(facingPerson()) : list();
    }

    private static void slowDetectorScenarios() {
        scenario("call_detect_2500_ms_a_frame_taken_200_ms_after_the_turn_ends_is_fresh_and_found_with_no_more_looks", n -> {
            Rig probe = slowRig(EMPTY_ROOM, 2500, 0).started();
            sideCall(probe, Ears.Side.LEFT);
            long stop = runUntilState(probe, ExploreBrain.State.CUE_LOOK, 410, 20000);
            long shot = stop + 200;
            long phase = Math.floorMod(shot + 2500, 2500);
            Rig rig = slowRig((r, t) -> Math.abs(t - shot) <= 50 ? list(facingPerson()) : list(), 2500, phase);
            rig.started();
            sideCall(rig, Ears.Side.LEFT);
            long stop2 = runUntilState(rig, ExploreBrain.State.CUE_LOOK, 410, 20000);
            long meet = runUntilState(rig, ExploreBrain.State.MEET, stop2, stop2 + 20000);
            rig.runUntil(Math.max(meet, stop2) + 5000);
            check(n, stop > 0 && stop2 == stop && meet > 0 && meet <= shot + 2500 + 200 && rig.cueLookYaws.size() == 1
                            && rig.matchBoxes.size() == 1 && rig.count("react where") == 0 && rig.violations.isEmpty(),
                    "stop@" + stop + " stop2@" + stop2 + " shot@" + shot + " meet@" + meet + " looks="
                            + rig.cueLookYaws.size() + " " + rig.tail());
        });
        scenario("call_a_person_in_a_stale_look_20_deg_from_the_stop_is_found", n -> {
            Rig probe = slowRig(EMPTY_ROOM, 2500, 0).started();
            double start = sideCall(probe, Ears.Side.LEFT);
            long stop = runUntilState(probe, ExploreBrain.State.CUE_LOOK, 410, 20000);
            // When, in the turn into the first stop, he faced 70 deg left (20 deg short of it).
            long at70 = -1;
            for (double[] e : probe.yawLog) {
                if (e[0] > 410 && e[0] <= stop && Heading.delta(start, e[1]) >= 70) {
                    at70 = (long) e[0];
                    break;
                }
            }
            long shot = at70;
            Rig rig = slowRig(personAtShot(start + 70, 10), 2500, Math.floorMod(shot + 2500, 2500));
            rig.started();
            sideCall(rig, Ears.Side.LEFT);
            long stop2 = runUntilState(rig, ExploreBrain.State.CUE_LOOK, 410, 20000);
            long meet = runUntilState(rig, ExploreBrain.State.MEET, stop2, stop2 + 30000);
            rig.runUntil(Math.max(meet, stop2) + 5000);
            System.out.println("REPORT call search, a person seen 20 deg short of the first stop in a frame taken"
                    + " during the turn (detection 2500 ms): stop at " + stop2 + " ms, met at " + meet + " ms");
            check(n, at70 > 0 && stop2 == stop && shot + 2500 >= stop2 && meet > 0 && meet <= shot + 2500 + 200
                            && rig.cueLookYaws.size() == 1 && rig.count("react where") == 0 && rig.violations.isEmpty(),
                    "at70@" + at70 + " stop@" + stop + " stop2@" + stop2 + " meet@" + meet + " looks="
                            + rig.cueLookYaws.size() + " " + rig.tail());
        });
        scenario("call_detect_4500_ms_a_caller_at_90_left_is_found_on_look_1", n -> {
            StringBuilder got = new StringBuilder();
            boolean all = true;
            for (long phase : new long[]{0, 1500, 3000}) {
                // The heading at the call, from a probe run (the same up to the call).
                Rig probe = slowRig(EMPTY_ROOM, 4500, phase).started();
                probe.runUntil(400);
                double start = probe.yaw.wrapped();
                Rig real = slowRig(personAtShot(start + 90, 25), 4500, phase).started();
                sideCall(real, Ears.Side.LEFT);
                long stop = runUntilState(real, ExploreBrain.State.CUE_LOOK, 410, 20000);
                long meet = runUntilState(real, ExploreBrain.State.MEET, 410, 40000);
                got.append(" phase ").append(phase).append(": stop@").append(stop).append(" met@").append(meet)
                        .append(" looks=").append(real.cueLookYaws.size());
                all &= meet > 0 && real.cueLookYaws.size() == 1 && real.count("react where") == 0
                        && real.violations.isEmpty();
            }
            System.out.println("REPORT call search, detection 4500 ms, a caller 90 deg left:" + got);
            check(n, all, got.toString());
        });
        scenario("call_detect_4500_ms_an_empty_circle_still_reaches_where", n -> {
            Rig rig = slowRig(EMPTY_ROOM, 4500, 0).started();
            double start = sideCall(rig, Ears.Side.LEFT);
            long where = runUntilEvent(rig, "react where", 410, 200000);
            System.out.println("REPORT call search, an empty circle with detection 4500 ms: " + (where - 410)
                    + " ms from the call to the where clip (stops " + cueLookLengths(rig) + " ms)");
            check(n, where > 0 && bearingsNear(lookBearings(rig, start), 15, 90, 135, 45, 0, -45, -90, -135, 180)
                            && rig.count("react answer") == 1 && rig.count("match") == 0 && rig.violations.isEmpty(),
                    "where@" + where + " looks=" + lookBearings(rig, start) + " " + rig.tail());
        });
        seenCallerScenarios();
        persistedPersonScenarios();
        blockedSearchScenarios();
    }

    // ---- a caller seen in a stale look is a target (robot 2026-10-01) ----
    //
    // The owner said "Hey Miko" from his right; the search's looks caught him twice, 37 and
    // 61 deg from where he faced, and threw both away as "not where he faces now", then the
    // conversation opened with nobody in view. Now a person in a look whose capture heading is
    // known is a bearing: he turns to face it and takes one confirming look; nobody there
    // resumes the planned looks. At most callSeenRetargetsMax such turns per call.

    /** A person box whose centre is centreX (-1 left .. 1 right), 0.4 of the frame tall (near). */
    private static Detection personBoxAt(float centreX) {
        return box("person", 0.9f, 0.5f + centreX / 2, 0.5f, 0.2f, 0.4f);
    }

    /**
     * A person standing at this bearing (yaw frame, left positive), seen from where each frame
     * was taken: the box sits where they are in a 60 deg wide frame, while capture time t passes.
     */
    private static Vision personSeenAt(double bearingLeftDeg, java.util.function.LongPredicate there) {
        return (r, t) -> {
            if (r.yaw == null || !there.test(t)) {
                return list();
            }
            double left = Heading.delta(r.yawAt(t), Heading.wrap(bearingLeftDeg));
            return Math.abs(left) > 27 ? list() : list(personBoxAt((float) (-left / 30)));
        };
    }

    /** The first time in the probe's yaw log after from at which he faced at least deg left of start. */
    private static long whenFacing(Rig probe, double start, double deg, long from) {
        for (double[] e : probe.yawLog) {
            if (e[0] > from && Heading.delta(start, e[1]) >= deg) {
                return (long) e[0];
            }
        }
        return -1;
    }

    /** The trace notes containing text. */
    private static int notedCount(List<String> notes, String text) {
        int k = 0;
        for (String x : notes) {
            if (x.contains(text)) {
                k++;
            }
        }
        return k;
    }

    /** The time of the first note at or after from containing text, else -1. */
    private static long notedAt(List<String> notes, String text, long from) {
        for (String x : notes) {
            long t = Long.parseLong(x.substring(0, x.indexOf(' ')));
            if (t >= from && x.contains(text)) {
                return t;
            }
        }
        return -1;
    }

    /** Runs in 10 ms steps until a note containing text appears, or until limit; its time or -1. */
    private static long runUntilNoted(Rig rig, List<String> notes, String text, long limit) {
        while (rig.now < limit && notedAt(notes, text, 0) < 0) {
            rig.runUntil(rig.now + 10);
        }
        return notedAt(notes, text, 0);
    }

    private static final String RETARGET_NOTE = "turning to face them";

    /** A LEFT call with detection 2500 ms; the frame taken while he faced 53 deg left on the way to 90 arrives in look 1. */
    private static long[] look1Shot53() {
        Rig probe = slowRig(EMPTY_ROOM, 2500, 0).started();
        double start = sideCall(probe, Ears.Side.LEFT);
        long stop = runUntilState(probe, ExploreBrain.State.CUE_LOOK, 410, 20000);
        long at53 = whenFacing(probe, start, 53, 410);
        return new long[]{at53, stop, Math.round(start * 1000)};
    }

    private static void seenCallerScenarios() {
        scenario("call_a_person_in_look_1_captured_37_deg_right_is_faced_and_met_with_no_circle", n -> {
            long[] p = look1Shot53();
            long at53 = p[0];
            double start = p[2] / 1000.0;
            Rig rig = slowRig(personSeenAt(start + 53, t -> true), 2500, Math.floorMod(at53 + 2500, 2500));
            List<String> notes = traced(rig);
            rig.started();
            sideCall(rig, Ears.Side.LEFT);
            long meet = runUntilState(rig, ExploreBrain.State.MEET, 410, 40000);
            rig.runUntil(Math.max(meet, 410) + 3000);
            List<Long> looks = lookBearings(rig, start);
            System.out.println("REPORT call search, a person seen 37 deg right of look 1: looks " + looks
                    + ", met at " + meet + " ms");
            check(n, at53 > 0 && p[1] < at53 + 2500 && meet > 0 && looks.size() == 2 && Math.abs(looks.get(1) - 53) <= 8
                            && notedCount(notes, RETARGET_NOTE) == 1 && rig.count("react where") == 0
                            && rig.matchBoxes.size() == 1 && rig.violations.isEmpty(),
                    "at53@" + at53 + " stop@" + p[1] + " meet@" + meet + " looks=" + looks + " " + rig.tail());
        });
        scenario("call_a_person_seen_61_deg_away_during_look_4_is_retargeted_faced_and_met", n -> {
            // Frames every 100 ms, each taken 2500 ms before it arrives: the probe's run is the
            // real one's until the person steps in, as look 3 ends, 16 deg left of it.
            Rig probe = slowRig(EMPTY_ROOM, 2500, 0);
            probe.lookEveryMs = 100;
            probe.started();
            probe.runUntil(400);
            double start = probe.yaw.wrapped();
            probe.cue(410, Ears.Kind.WAKE_WORD, Ears.Side.UNKNOWN, Float.NaN);
            long look4 = -1;
            while (probe.now < 60000 && probe.cueLookYaws.size() < 4) {
                probe.runUntil(probe.now + 10);
            }
            look4 = probe.now;
            long turn4 = -1;
            int looks = 0;
            for (Event e : probe.stateLog) {
                if (e.what.equals("CUE_LOOK")) {
                    looks++;
                } else if (looks == 3 && e.what.equals("CUE_TURN")) {
                    turn4 = e.t;
                    break;
                }
            }
            double y3 = probe.cueLookYaws.get(2);
            double y4 = probe.cueLookYaws.get(3);
            double b = y4 + 61;
            long in = turn4;
            Rig rig = slowRig(personSeenAt(b, t -> t >= in), 2500, 0);
            rig.lookEveryMs = 100;
            List<String> notes = traced(rig);
            rig.started();
            rig.runUntil(400);
            rig.cue(410, Ears.Kind.WAKE_WORD, Ears.Side.UNKNOWN, Float.NaN);
            long meet = runUntilState(rig, ExploreBrain.State.MEET, 410, 60000);
            rig.runUntil(Math.max(meet, 410) + 3000);
            List<Long> got = lookBearings(rig, start);
            String note61 = "";
            for (String x : notes) {
                if (x.contains(RETARGET_NOTE)) {
                    note61 = x;
                }
            }
            System.out.println("REPORT call search, a person stepping in 61 deg from look 4: looks " + got
                    + ", met at " + meet + " ms; " + note61);
            check(n, turn4 > 0 && Math.abs(Heading.delta(y3, y4) + 45) <= 5 && meet > 0 && got.size() == 5
                            && Math.abs(Heading.delta(rig.cueLookYaws.get(4), b)) <= 8
                            && notedCount(notes, RETARGET_NOTE) == 1 && rig.count("react where") == 0
                            && rig.matchBoxes.size() == 1 && rig.violations.isEmpty(),
                    "turn4@" + turn4 + " look4@" + look4 + " y3=" + y3 + " y4=" + y4 + " meet@" + meet + " looks=" + got
                            + " " + note61 + " " + rig.tail());
        });
        scenario("call_a_seen_caller_gone_from_the_retarget_bearing_resumes_the_planned_looks_and_asks_where", n -> {
            long[] p = look1Shot53();
            long at53 = p[0];
            double start = p[2] / 1000.0;
            Rig rig = slowRig(personSeenAt(start + 53, t -> t <= at53 + 50), 2500, Math.floorMod(at53 + 2500, 2500));
            List<String> notes = traced(rig);
            rig.started();
            sideCall(rig, Ears.Side.LEFT);
            long where = runUntilEvent(rig, "react where", 410, 200000);
            List<Long> looks = lookBearings(rig, start);
            check(n, where > 0 && bearingsNear(looks, 15, 90, 53, 135, 45, 0, -45, -90, -135, 180)
                            && notedCount(notes, RETARGET_NOTE) == 1 && notedCount(notes, "nobody where the caller was seen") == 1
                            && rig.count("react where") == 1 && rig.count("match") == 0 && rig.violations.isEmpty(),
                    "where@" + where + " looks=" + looks + " " + rig.tail());
        });
        scenario("call_the_seen_caller_retarget_cap_holds_against_phantom_boxes", n -> {
            // A blurred "person" at the frame's trailing edge in the frames taken as each of three
            // turns starts: each one puts a "caller" well behind the turn, a different one every turn.
            int[] shown = {0};
            Vision phantoms = (r, t) -> {
                if (!turningAt(r, t) || turningAt(r, t - 100) || shown[0] >= 3) {
                    return list();
                }
                shown[0]++;
                boolean left = Heading.delta(r.yawAt(t - 100), r.yawAt(t)) > 0;
                return list(personBoxAt(left ? 0.8f : -0.8f));
            };
            Rig rig = slowRig(phantoms, 2500, 0);
            rig.lookEveryMs = 100;
            List<String> notes = traced(rig);
            rig.started();
            rig.runUntil(400);
            rig.cue(410, Ears.Kind.WAKE_WORD, Ears.Side.UNKNOWN, Float.NaN);
            long where = runUntilEvent(rig, "react where", 410, 300000);
            int retargets = notedCount(notes, RETARGET_NOTE);
            check(n, where > 0 && retargets == 2 && notedCount(notes, "left to the plan") >= 1
                            && rig.cueLookYaws.size() == 8 + 2 && rig.count("match") == 0 && rig.violations.isEmpty(),
                    "where@" + where + " retargets=" + retargets + " looks=" + rig.cueLookYaws.size() + " " + rig.tail());
        });
        scenario("call_caller_talking_while_he_turns_to_a_seen_caller_meets_them_facing", n -> {
            long[] p = look1Shot53();
            long at53 = p[0];
            double start = p[2] / 1000.0;
            Rig rig = slowRig(personSeenAt(start + 53, t -> true), 2500, Math.floorMod(at53 + 2500, 2500));
            rig.people.persona = PERSONA;
            rig.people.match = (r, k) -> STRANGER.withConversation(r.people.persona, null, null, null);
            rig.people.lines = STRANGER.withConversation(PERSONA, null, null, null);
            List<String> notes = traced(rig);
            rig.started();
            sideCall(rig, Ears.Side.LEFT);
            long turn = runUntilNoted(rig, notes, RETARGET_NOTE, 40000);
            rig.cue(rig.now + 50, Ears.Tier.WEAK, Ears.Side.LEFT, Float.NaN);
            long meet = runUntilState(rig, ExploreBrain.State.MEET, Math.max(turn, 0), 40000);
            rig.runUntil(Math.max(meet, 410) + 3000);
            List<Long> looks = lookBearings(rig, start);
            check(n, turn > 0 && meet > 0 && looks.size() == 2 && Math.abs(looks.get(1) - 53) <= 8
                            && rig.matchBoxes.size() == 1 && !noted(notes, "nobody in view")
                            && rig.count("react where") == 0 && rig.violations.isEmpty(),
                    "turn@" + turn + " meet@" + meet + " looks=" + looks + " " + rig.tail());
        });
    }

    // ---- a faceless roaming person who stays (robot 2026-10-01) ----
    //
    // From the floor a standing person nearby is legs and a body, no face. A rule that met a
    // faceless roaming pick when its box stayed in the still looks also met static furniture
    // (a chair's edge, the shadow under a desk), so it is gone: a faceless roaming pick is
    // never a meeting, however long its box stays (usableFaceScenarios).

    /** A person straight ahead in only the first still frame taken in a leg-decision pause. */
    private static Vision oncePaused(Detection box) {
        boolean[] shown = {false};
        return (r, t) -> {
            long p = pauseEntry(r);
            if (!shown[0] && p >= 0 && t >= p && !turningAt(r, t)) {
                shown[0] = true;
                return list(box);
            }
            return list();
        };
    }

    /** When the current leg-decision PAUSE began (not the short one after a turn), or -1 when he is not in one. */
    private static long pauseEntry(Rig r) {
        if (r.brain.state() != ExploreBrain.State.PAUSE) {
            return -1;
        }
        for (int i = r.stateLog.size() - 1; i >= 0; i--) {
            if (r.stateLog.get(i).what.equals("PAUSE")) {
                return i > 0 && r.stateLog.get(i - 1).what.equals("TURN") ? -1 : r.stateLog.get(i).t;
            }
        }
        return -1;
    }

    /** Whether he was turning when the frame at t was taken (his true heading moved over the 100 ms before). */
    private static boolean turningAt(Rig r, long t) {
        return r.yaw != null && Math.abs(Heading.delta(r.yawAt(t - 100), r.yawAt(t))) > 1;
    }

    /** A person box in the first still frame taken in each leg-decision pause only, on alternating sides. */
    private static Vision eachPauseAlternating() {
        int[] shown = {0};
        long[] lastPause = {-1};
        return (r, t) -> {
            long p = pauseEntry(r);
            if (p < 0 || p == lastPause[0] || t < p || turningAt(r, t)) {
                return list();
            }
            lastPause[0] = p;
            shown[0]++;
            return list(box("person", 0.9f, shown[0] % 2 == 0 ? 0.25f : 0.75f, 0.5f, 0.2f, 0.3f));
        };
    }

    private static void persistedPersonScenarios() {
        scenario("roaming_faceless_person_box_in_3_stopped_looks_is_still_not_met", n -> {
            // Owner 2026-10-01: a box that stays is no proof (furniture stays too); only a usable face is.
            Rig rig = roamRig(t -> true, false);
            List<String> notes = traced(rig);
            rig.started();
            long match = runUntilEvent(rig, "match", 0, 12000);
            rig.runUntil(match + 8000);
            check(n, match > 0 && entered(rig, ExploreBrain.State.CHAT_THINK, 0) < 0 && spokenAfter(rig, match) == 0
                            && noted(notes, "no usable face in the roaming person pick's box")
                            && rig.countPrefix("remember ", 0, Long.MAX_VALUE) == 0 && rig.violations.isEmpty(),
                    "match@" + match + " " + rig.tail());
        });
        usableFaceScenarios();
        facelessCallScenarios();
        callChatFirstScenarios();
    }

    // ---- a roaming person is met only with a usable face (owner 2026-10-01) ----
    //
    // "If he's not positive that it's a person, why is he acting like it is a person?" The
    // roaming detector scored a chair's edge and the shadow under a desk "person" 0.53-0.54,
    // and he asked a wastebasket its name. A roaming pick now opens a meeting only when the
    // face check gave a usable face (a match, or a new face past the quality gate); no face,
    // a rejected one, the face models not ready or a failed check drop it quietly.

    /** A roaming pick whose match answers a; whether a meeting opened, and anything was said. */
    private static void roamingDropped(String n, CuriosityPort.MatchAnswer a) {
        Rig rig = roamRig(t -> true, false);
        rig.people.match = (r, k) -> a == CuriosityPort.MatchAnswer.FAILED ? a
                : a.withConversation(r.people.persona, null, null, null);
        List<String> notes = traced(rig);
        rig.started();
        long match = runUntilEvent(rig, "match", 0, 12000);
        rig.runUntil(match + 8000);
        long pause = entered(rig, ExploreBrain.State.PAUSE, match);
        check(n, match > 0 && entered(rig, ExploreBrain.State.CHAT_THINK, 0) < 0 && spokenAfter(rig, match) == 0
                        && rig.count("lines") == 0 && pause > 0 && pause <= match + 12000
                        && noted(notes, "no usable face in the roaming person pick's box") && rig.violations.isEmpty(),
                "match@" + match + " pause@" + pause + " " + rig.tail());
    }

    private static void usableFaceScenarios() {
        scenario("roaming_person_with_no_face_is_not_met_and_nothing_is_said",
                n -> roamingDropped(n, CuriosityPort.MatchAnswer.faceless().withMatch(null, null, Float.NaN, 11L)));
        scenario("roaming_person_with_a_too_small_face_is_not_met_and_nothing_is_said",
                // The adapter answers a rejected crop (TOO_SMALL, TOO_DARK, TOO_BLURRY) faceless with no band.
                n -> roamingDropped(n, CuriosityPort.MatchAnswer.faceless().withMatch(null, null, Float.NaN, 12L)));
        scenario("roaming_person_with_the_face_models_not_ready_is_not_met",
                n -> roamingDropped(n, CuriosityPort.MatchAnswer.faceless()
                        .withMatch(FaceMatcher.Band.NOT_READY, null, Float.NaN, 13L)));
        scenario("roaming_person_whose_face_check_failed_is_not_met",
                n -> roamingDropped(n, CuriosityPort.MatchAnswer.FAILED));
        scenario("roaming_person_with_a_usable_new_face_is_met_and_the_opener_asks_the_name", n -> {
            Rig rig = roamRig(t -> true, true).started();
            long match = runUntilEvent(rig, "match", 0, 12000);
            long chat = runUntilState(rig, ExploreBrain.State.CHAT_THINK, match, match + 10000);
            TurnAsk first = rig.turnAsks.isEmpty() ? null : rig.turnAsks.get(0);
            check(n, match > 0 && chat > 0 && first != null && first.request.heard == null && first.request.name == null
                            && !first.request.faceless && rig.violations.isEmpty(),
                    "match@" + match + " chat@" + chat + " first=" + first + " " + rig.tail());
        });
    }

    // ---- a call with no usable face: the crouch opener, face retries, the name only with a face ----
    //
    // Robot 2026-10-01: from the floor the face was out of frame or under 48 px, so he asked
    // three office regulars their names and remembered none of them. A call (someone asked for
    // him) still converses facelessly, but the opener invites them down to his level instead
    // of asking the name; he retries the face on a fresh look up to chatFaceTries times; the
    // name is asked (or one already given is used) only once a usable face is in hand, and
    // then it is stored with the face. With no usable face he chats unnamed, as before.

    /** A call from Priya on his left whose face is usable from the usableFrom-th match on (0: never). */
    private static Rig facelessCallRig(int usableFrom, TurnScript turns, Hearing... listens) {
        Rig rig = chatRig(personAt(bearingOf(-90f), 25), false);
        rig.people.match = (r, k) -> usableFrom > 0 && k >= usableFrom
                ? CuriosityPort.MatchAnswer.stranger().withMatch(FaceMatcher.Band.WEAK, null, 0.1f, 40L + k)
                        .withConversation(r.people.persona, null, null, null)
                : noFace(r);
        rig.turns = turns;
        rig.people.listen = ListenScript.turns(listens);
        return rig;
    }

    private static Hearing[] replies(int ok) {
        Hearing[] h = new Hearing[ok + 1];
        for (int i = 0; i < ok; i++) {
            h[i] = hearWords("sure");
        }
        h[ok] = hearWords("bye");
        return h;
    }

    /** The index of the first turn request carrying faceSeen, or -1. */
    private static int firstFaceSeen(Rig rig) {
        for (int i = 0; i < rig.turnAsks.size(); i++) {
            if (rig.turnAsks.get(i).request.faceSeen) {
                return i;
            }
        }
        return -1;
    }


    // ---- conversation first, the caller found during it (owner 2026-10-02) ----
    //
    // Robot 09:08:57: a call from his right, eight search looks, the caller's voice opened the
    // conversation with nobody in view, one wordless answer, "one look for them", over after one
    // turn. Owner: "he needs to be able to carry on a conversation even before he's managed to
    // turn and recognize the person." Now a call opens the conversation at once (the words said
    // with the wake word are the first message), he looks for the caller between utterances, and
    // not seeing them never ends it: three unanswered listens do, and a wordless answer first
    // gets "Sorry, I didn't catch that?".

    private static ExploreTuning.Builder chatFirstTuning() {
        return cueTuning().callChatFirst(true);
    }

    /** A conversation-first rig: nobody's face usable unless the scenario says so, these listens. */
    private static Rig callChatRig(ExploreTuning.Builder b, Feed feed, Vision v, Hearing... listens) {
        Rig rig = chatRig(b, feed, v, false);
        rig.people.match = (r, k) -> noFace(r);
        rig.people.listen = ListenScript.turns(listens);
        return rig;
    }

    private static Rig callChatRig(Vision v, Hearing... listens) {
        return callChatRig(chatFirstTuning(), CLEAR, v, listens);
    }

    /** "Hey Miko" at t from side (no angle), its utterance's end delivered endMs later with message (endMs < 0: none). */
    private static void heyMiko(Rig rig, long t, Ears.Side side, long endMs, String message) {
        rig.cue(t, Ears.Kind.WAKE_WORD, side, Float.NaN);
        if (endMs >= 0) {
            rig.calledCue(t + endMs, t, Ears.Kind.WAKE_WORD, side, Float.NaN, message);
        }
    }

    /** The first turn request at or after from, or null. */
    private static TurnAsk firstAsk(Rig rig, long from) {
        for (TurnAsk a : rig.turnAsks) {
            if (a.t >= from) {
                return a;
            }
        }
        return null;
    }

    /** The wheel turns commanded in [from, to): {t, 1 for LEFT, -1 for RIGHT} (not the port's "turn LINE" events). */
    private static List<long[]> wheelTurns(Rig rig, long from, long to) {
        List<long[]> out = new ArrayList<long[]>();
        for (Event e : rig.log) {
            if (e.t >= from && e.t < to && (e.what.equals("turn LEFT") || e.what.equals("turn RIGHT"))) {
                out.add(new long[]{e.t, e.what.equals("turn LEFT") ? 1 : -1});
            }
        }
        return out;
    }

    private static boolean searchedBeforeTheConversation(Rig rig, long from) {
        return entered(rig, ExploreBrain.State.CUE_TURN, from) >= 0 || entered(rig, ExploreBrain.State.CUE_LOOK, from) >= 0
                || entered(rig, ExploreBrain.State.CUE_WHERE, from) >= 0;
    }

    private static void callChatFirstScenarios() {
        scenario("callchat_wake_with_words_opens_with_their_words_as_the_first_message_within_2_s", n -> {
            Rig rig = callChatRig(EMPTY_ROOM, hearWords("not bad"), hearWords("bye"));
            List<String> notes = traced(rig);
            rig.started();
            heyMiko(rig, 400, Ears.Side.RIGHT, 1200, "how's it going");
            rig.runUntil(400);
            long open = runUntilState(rig, ExploreBrain.State.CHAT_THINK, 400, 20000);
            long over = chatOver(rig, open);
            TurnAsk first = firstAsk(rig, 400);
            long answer = answerAt(rig, 400);
            System.out.println("REPORT worded Hey Miko: " + notesAfter(notes, 400));
            check(n, open > 0 && over > 0 && first != null && "how's it going".equals(first.request.heard)
                            && first.request.called && first.t - 400 <= 2000 && answer >= 1600 && answer <= first.t
                            && rig.count("react answer") == 1 && !searchedBeforeTheConversation(rig, 400)
                            && noted(notes, "they said goodbye") && rig.violations.isEmpty(),
                    "open@" + open + " answer@" + answer + " first=" + (first == null ? null : first.t + " heard="
                            + first.request.heard + " called=" + first.request.called) + " " + rig.tail());
        });
        scenario("callchat_bare_wake_opens_at_once_with_a_greeting_question_and_searches_during_it", n -> {
            Rig rig = callChatRig(EMPTY_ROOM, hearWords("not much"), hearWords("sure"), hearWords("ok"),
                    hearWords("bye"));
            List<String> notes = traced(rig);
            rig.started();
            heyMiko(rig, 400, Ears.Side.RIGHT, 800, "");
            rig.runUntil(400);
            long open = runUntilState(rig, ExploreBrain.State.CHAT_THINK, 400, 20000);
            long over = chatOver(rig, open);
            TurnAsk first = firstAsk(rig, 400);
            List<long[]> turns = wheelTurns(rig, open, over);
            System.out.println("REPORT bare Hey Miko: " + notesAfter(notes, 400));
            check(n, open > 0 && over > 0 && first != null && first.request.heard == null && first.request.called
                            && !first.request.cantSee && first.t - 400 <= 1500 && !searchedBeforeTheConversation(rig, 400)
                            && !turns.isEmpty() && turns.get(0)[1] == -1 && rig.chatTurns == turns.size()
                            && noted(notes, "looking for the caller during the conversation")
                            && noted(notes, "search look") && rig.violations.isEmpty(),
                    "open@" + open + " over@" + over + " first=" + first + " cantSee=" + (first == null ? null : first.request.cantSee)
                            + " turns=" + turns.size() + " chatTurns=" + rig.chatTurns + " first turn=" + (turns.isEmpty() ? null : turns.get(0)[1])
                            + " searchedBefore=" + searchedBeforeTheConversation(rig, 400) + " v=" + rig.violations);
        });
        scenario("callchat_the_caller_found_at_look_3_gets_the_face_path_and_the_conversation_goes_on", n -> {
            // The side-first plan to his right: 90, 135, then 45 deg, where Priya stands.
            Rig rig = callChatRig(personAt(bearingOf(45f), 20), replies(7));
            rig.people.match = (r, k) -> CuriosityPort.MatchAnswer.stranger()
                    .withMatch(FaceMatcher.Band.WEAK, null, 0.1f, 40L + k).withConversation(r.people.persona, null, null, null);
            rig.turns = (r, req, k) -> req.faceSeen && req.name == null ? named(k, "Priya") : turnLine(k);
            List<String> notes = traced(rig);
            rig.started();
            heyMiko(rig, 400, Ears.Side.RIGHT, 800, "");
            rig.runUntil(400);
            long open = runUntilState(rig, ExploreBrain.State.CHAT_THINK, 400, 20000);
            long over = chatOver(rig, open);
            long usable = notedAt(notes, "a usable face");
            int seen = firstFaceSeen(rig);
            List<Long> looks = notedTimes(notes, "search look");
            int turnsAfter = wheelTurns(rig, usable, over).size();
            check(n, open > 0 && over > 0 && usable > open && seen > 0 && rig.turnAsks.get(seen).t > usable
                            && looks.size() >= 3 && rig.count("match") >= 1 && turnsAfter <= 1
                            && rig.kept.equals(java.util.Arrays.asList("Priya")) && noted(notes, "they said goodbye")
                            && !searchedBeforeTheConversation(rig, 400) && rig.violations.isEmpty(),
                    "usable@" + usable + " seen=" + seen + " looks=" + looks + " turnsAfter=" + turnsAfter + " at="
                            + wheelTurns(rig, usable, over).stream().map(x -> x[0]).collect(java.util.stream.Collectors.toList())
                            + " over@" + over + " v=" + rig.violations + " kept="
                            + rig.kept + " notes=" + notes + " " + rig.tail());
        });
        scenario("callchat_never_found_goes_on_while_they_answer_and_ends_after_3_unanswered", n -> {
            Rig rig = callChatRig(EMPTY_ROOM, hearWords("one"), hearWords("two"), hearWords("three"),
                    hearWords("four"), hearWords("five"));
            List<String> notes = traced(rig);
            rig.started();
            heyMiko(rig, 400, Ears.Side.LEFT, 800, "");
            rig.runUntil(400);
            long open = runUntilState(rig, ExploreBrain.State.CHAT_THINK, 400, 20000);
            long over = chatOver(rig, open);
            boolean cantSee = false;
            for (TurnAsk a : rig.turnAsks) {
                cantSee |= a.request.cantSee;
            }
            check(n, open > 0 && over > 0 && rig.tuning.callChatUnansweredMax == 3 && rig.turnAsks.size() == 6
                            && noted(notes, "3 unanswered listens in a row") && !noted(notes, "walked off")
                            && !noted(notes, "one look for them") && noted(notes, "nobody found in the search")
                            && cantSee && rig.violations.isEmpty(),
                    "asks=" + rig.turnAsks.size() + " cantSee=" + cantSee + " notes=" + notes + " " + rig.tail());
        });
        scenario("callchat_an_answer_with_no_words_gets_one_didnt_catch_that_and_does_not_count", n -> {
            // Wordless, then two silences: had the wordless one counted, the third would end it.
            Rig rig = callChatRig(EMPTY_ROOM, hearSilence().after(5000).answeringAfter(1000), hearSilence(),
                    hearSilence(), hearWords("hello again"), hearWords("bye"));
            List<String> notes = traced(rig);
            rig.started();
            heyMiko(rig, 400, Ears.Side.LEFT, 800, "");
            rig.runUntil(400);
            long open = runUntilState(rig, ExploreBrain.State.CHAT_THINK, 400, 20000);
            long over = chatOver(rig, open);
            boolean heardAgain = false;
            for (TurnAsk a : rig.turnAsks) {
                heardAgain |= "hello again".equals(a.request.heard);
            }
            check(n, open > 0 && over > 0 && rig.count("say " + ChatSession.DIDNT_CATCH) == 1 && heardAgain
                            && noted(notes, "they said goodbye") && !noted(notes, "unanswered listens in a row")
                            && rig.violations.isEmpty(),
                    "reasks=" + rig.count("say " + ChatSession.DIDNT_CATCH) + " notes=" + notes + " " + rig.tail());
        });
        scenario("callchat_no_turn_is_commanded_while_he_speaks_or_an_answer_is_in_progress", n -> {
            Rig rig = callChatRig(EMPTY_ROOM, hearWords("well").after(2600).answeringAfter(200),
                    hearWords("and then").after(3000).answeringAfter(100), hearWords("so").after(2000).answeringAfter(50),
                    hearWords("bye").after(1500).answeringAfter(100));
            rig.speechMs = 2500;
            rig.started();
            heyMiko(rig, 400, Ears.Side.RIGHT, 800, "");
            rig.runUntil(400);
            long open = runUntilState(rig, ExploreBrain.State.CHAT_THINK, 400, 20000);
            long over = chatOver(rig, open);
            check(n, open > 0 && over > 0 && rig.chatTurns > 0 && rig.violations.isEmpty(),
                    "turns=" + rig.chatTurns + " violations=" + rig.violations + " " + rig.tail());
        });
        scenario("callchat_an_older_launcher_with_no_words_gives_the_bare_wake_opener", n -> {
            // An older launcher: the end delivery carries no message (null); or, older still, no end
            // is marked at all, and the answer waits out callUtteranceWaitMs.
            Rig rig = callChatRig(EMPTY_ROOM, hearWords("bye"));
            rig.started();
            heyMiko(rig, 400, Ears.Side.RIGHT, 900, null);
            rig.runUntil(400);
            long open = runUntilState(rig, ExploreBrain.State.CHAT_THINK, 400, 20000);
            TurnAsk first = firstAsk(rig, 400);
            Rig old = callChatRig(EMPTY_ROOM, hearWords("bye"));
            old.started();
            heyMiko(old, 400, Ears.Side.RIGHT, -1, null);
            old.runUntil(400);
            long openOld = runUntilState(old, ExploreBrain.State.CHAT_THINK, 400, 20000);
            TurnAsk firstOld = firstAsk(old, 400);
            long wait = old.tuning.callUtteranceWaitMs;
            check(n, open > 0 && first != null && first.request.heard == null && first.request.called
                            && answerAt(rig, 400) >= 1300 && openOld > 0 && firstOld != null && firstOld.request.heard == null
                            && answerAt(old, 400) == 400 + wait && wait == 2500 && rig.violations.isEmpty()
                            && old.violations.isEmpty(),
                    "first=" + first + " answer@" + answerAt(rig, 400) + " old first=" + firstOld + " old answer@"
                            + answerAt(old, 400) + " " + old.tail());
        });
        scenario("callchat_a_call_that_waits_out_a_back_off_keeps_its_words_and_answers_as_the_back_off_ends", n -> {
            // The utterance's end (with its words) arrives while the call waits for the back-off:
            // the answer must not then wait callUtteranceWaitMs for an end that already came.
            Rig rig = chatRig(chatFirstTuning(), t -> t >= 1500 && t < 1700 ? edgeAhead(t) : clear(t), EMPTY_ROOM, false);
            rig.people.match = (r, k) -> noFace(r);
            rig.people.listen = ListenScript.turns(hearWords("bye"));
            rig.started();
            long back = runUntilState(rig, ExploreBrain.State.BACK_OFF, 0, 20000);
            long cueT = back + 50;
            heyMiko(rig, cueT, Ears.Side.LEFT, 100, "are you ok");
            rig.runUntil(cueT);
            long open = runUntilState(rig, ExploreBrain.State.CHAT_THINK, cueT, cueT + 20000);
            long backEnd = stateChangeAfter(rig, back);
            TurnAsk first = firstAsk(rig, cueT);
            long answer = answerAt(rig, cueT);
            check(n, back > 0 && backEnd > cueT + 100 && open > 0 && first != null && "are you ok".equals(first.request.heard)
                            && answer >= backEnd && answer <= backEnd + 100 && rig.violations.isEmpty(),
                    "back@" + back + " backEnd@" + backEnd + " answer@" + answer + " open@" + open + " first=" + first
                            + " " + rig.tail());
        });
        scenario("callchat_on_the_charger_talks_without_turning", n -> {
            Rig rig = callChatRig(chatFirstTuning(), t -> t >= 1000 ? charger(t) : clear(t), EMPTY_ROOM,
                    hearWords("hi"), hearWords("bye"));
            rig.started();
            heyMiko(rig, 4000, Ears.Side.LEFT, 800, "anyone home");
            rig.runUntil(4000);
            long open = runUntilState(rig, ExploreBrain.State.CHAT_THINK, 4000, 24000);
            long over = chatOver(rig, open);
            TurnAsk first = firstAsk(rig, 4000);
            check(n, open > 0 && over > 0 && first != null && "anyone home".equals(first.request.heard)
                            && wheelMoves(rig, 4000, over) == 0 && rig.chatTurns == 0 && rig.violations.isEmpty(),
                    "open@" + open + " over@" + over + " first=" + first + " moves=" + wheelMoves(rig, 4000, Math.max(4000, over))
                            + " " + rig.tail());
        });
    }

    private static void facelessCallScenarios() {
        scenario("call_faceless_meeting_opener_invites_them_down_and_does_not_ask_the_name", n -> {
            Rig rig = facelessCallRig(0, (r, req, k) -> turnLine(k), replies(1));
            rig.started();
            long open = openChat(rig);
            long over = chatOver(rig, open);
            TurnAsk first = rig.turnAsks.isEmpty() ? null : rig.turnAsks.get(0);
            boolean allFaceless = !rig.turnAsks.isEmpty();
            for (TurnAsk t : rig.turnAsks) {
                allFaceless &= t.request.faceless && !t.request.faceSeen;
            }
            check(n, open > 0 && over > 0 && first != null && first.request.heard == null && first.request.name == null
                            && allFaceless && rig.kept.isEmpty() && rig.violations.isEmpty(),
                    "open@" + open + " asks=" + rig.turnAsks + " " + rig.tail());
        });
        scenario("call_faceless_usable_face_on_a_retry_asks_the_name_then_stores_name_and_face", n -> {
            // The face is usable on the third match (the meeting's, then two retries).
            Rig rig = facelessCallRig(3, (r, req, k) -> req.faceSeen && req.name == null ? named(k, "Priya")
                    : turnLine(k), replies(6));
            List<String> notes = traced(rig);
            rig.started();
            long open = openChat(rig);
            long over = chatOver(rig, open);
            int seen = firstFaceSeen(rig);
            int third = rig.first("match NEW", rig.first("match NEW", rig.first("match NEW", 0) + 1) + 1);
            check(n, open > 0 && over > 0 && rig.count("match") == 3 && seen > 0
                            && rig.turnAsks.get(seen).t > rig.timeOf(third)
                            && rig.kept.equals(java.util.Arrays.asList("Priya"))
                            && rig.resolves.equals(java.util.Arrays.asList("Priya")) && !rig.notesDeltas.isEmpty()
                            && allStartWith(rig.notesDeltas, "kept-1: ") && !noted(notes, "discarded (R19)")
                            && noted(notes, "a usable face on try 2 of 3") && rig.violations.isEmpty(),
                    "seen=" + seen + " kept=" + rig.kept + " deltas=" + rig.notesDeltas + " asks=" + rig.turnAsks + " "
                            + rig.tail());
        });
        scenario("call_faceless_name_given_is_held_and_stored_when_a_face_arrives_on_a_retry", n -> {
            Rig rig = facelessCallRig(3, turnsOf(turnLine(1), named(2, "Priya")), replies(6));
            List<String> notes = traced(rig);
            rig.started();
            long open = openChat(rig);
            long over = chatOver(rig, open);
            check(n, open > 0 && over > 0 && rig.count("match") == 3 && noted(notes, "the name is held")
                            && rig.kept.equals(java.util.Arrays.asList("Priya"))
                            && rig.resolves.equals(java.util.Arrays.asList("Priya")) && firstFaceSeen(rig) < 0
                            && allStartWith(rig.notesDeltas, "kept-1: ") && rig.notesDeltas.size() >= 2
                            && !noted(notes, "discarded (R19)") && rig.violations.isEmpty(),
                    "kept=" + rig.kept + " deltas=" + rig.notesDeltas + " " + rig.tail());
        });
        scenario("call_faceless_with_no_usable_face_on_any_retry_chats_unnamed_and_discards_the_notes", n -> {
            Rig rig = facelessCallRig(0, turnsOf(turnLine(1), named(2, "Priya")), replies(8));
            List<String> notes = traced(rig);
            rig.started();
            long open = openChat(rig);
            long over = chatOver(rig, open);
            check(n, open > 0 && over > 0 && rig.count("match") == 1 + rig.tuning.chatFaceTries
                            && rig.tuning.chatFaceTries == 3 && rig.kept.isEmpty() && rig.resolves.isEmpty()
                            && noted(notes, "no usable face after 3 tries") && noted(notes, "discarded (R19)")
                            && firstFaceSeen(rig) < 0 && rig.violations.isEmpty(),
                    "matches=" + rig.count("match") + " " + rig.tail());
        });
    }

    // ---- a call while his turns do nothing (robot 2026-10-01: the motor board latched) ----
    //
    // Every search turn was blocked, the call went back to its slot, and the search restarted
    // 40 ms into the wedge escape, every 2-4 s for 40 s. Now the waiting call lets the escape
    // run first, and a call whose search turns were blocked twice meets the caller where he is.

    private static Rig stuckCallRig(List<String> notes) {
        Rig rig = talkRig(EMPTY_ROOM);
        rig.people.listen = ListenScript.turns(hearWords("over here"), hearWords("bye"));
        rig.brain.setTrace(x -> notes.add(rig.now + " " + x));
        rig.started();
        rig.runUntil(400);
        rig.yaw.stuck = true;
        rig.cue(410, Ears.Kind.WAKE_WORD, Ears.Side.LEFT, Float.NaN);
        return rig;
    }

    private static void blockedSearchScenarios() {
        scenario("call_with_turns_that_never_turn_searches_at_most_twice_then_meets_where_he_is", n -> {
            List<String> notes = new ArrayList<String>();
            Rig rig = stuckCallRig(notes);
            long meet = runUntilNoted(rig, notes, "meeting without a look", 120000);
            rig.runUntil(Math.max(meet, 410) + 5000);
            rig.runUntil(rig.now + 30000);
            int searches = notedCount(notes, "looking for the caller with no angle");
            System.out.println("REPORT a call with turns that never turn: " + searches + " search starts, met where he is at "
                    + meet + " ms, " + notedCount(notes, "failed escapes in a row") + " escape notes; "
                    + notes.stream().filter(x -> x.contains("search turn would not turn") || x.contains("gives way")
                    || x.contains("backing up first") || x.contains("looking for the caller with")).collect(java.util.stream.Collectors.toList()));
            check(n, meet > 0 && searches >= 1 && searches <= 2 && entered(rig, ExploreBrain.State.MEET, 410) > 0
                            && notedCount(notes, "meeting without a look") == 1,
                    "meet@" + meet + " searches=" + searches + " " + rig.tail());
        });
        scenario("call_search_waits_for_the_escape_back_up_before_it_restarts", n -> {
            List<String> notes = new ArrayList<String>();
            Rig rig = stuckCallRig(notes);
            runUntilNoted(rig, notes, "meeting without a look", 120000);
            long blocked = notedAt(notes, "measured turn blocked", 410);
            long backUp = notedAt(notes, "backing up first", Math.max(blocked, 0));
            long again = blocked < 0 ? -1 : notedAt(notes, "looking for the caller with no angle", blocked);
            boolean backed = false;
            for (Drive d : rig.drives) {
                backed |= d.kind.equals("back") && d.t > blocked && (again < 0 || d.t < again);
            }
            check(n, blocked > 0 && backUp > 0 && backed && (again < 0 || again > backUp + 500),
                    "blocked@" + blocked + " backUp@" + backUp + " again@" + again + " " + rig.tail());
        });
    }

    private static Rig chatRig(Vision v, boolean known) {
        return chatRig(cueTuning(), CLEAR, v, known);
    }

    private static Rig chatRig(ExploreTuning.Builder b, Feed feed, Vision v, boolean known) {
        Rig rig = cueRig(b, feed, v);
        rig.people.persona = PERSONA;
        rig.people.notes.put(SARAH_ID, SARAH_NOTES);
        rig.people.asked.put(SARAH_ID, new ArrayList<String>(java.util.Arrays.asList(SARAH_ASKED)));
        rig.people.match = (r, k) -> known
                ? CuriosityPort.MatchAnswer.known("Sarah", "Hi {name}!", "Hello again!").withConversation(
                        r.people.persona, SARAH_ID, r.people.notes.get(SARAH_ID), r.people.asked.get(SARAH_ID))
                : STRANGER.withConversation(r.people.persona, null, null, null);
        rig.turns = (r, req, nth) -> turnLine(nth);
        return rig;
    }

    /** Sarah facing him from the left; a cue at 400 ms opens the conversation. */
    private static Rig sarahRig(boolean known) {
        return chatRig(personAt(bearingOf(-90f), 25), known).started();
    }

    /** A person who stands there until leaves[0] (mutable, so a scenario can send them off mid-conversation). */
    private static Vision personAtUntil(double bearingLeftDeg, double halfViewDeg, long[] leaves) {
        Vision there = personAt(bearingLeftDeg, halfViewDeg);
        return (r, t) -> t < leaves[0] ? there.see(r, t) : list();
    }

    /** Cues at 400 ms and runs until the conversation opens; the time CHAT_THINK was entered, or -1. */
    private static long openChat(Rig rig) {
        rig.cue(400, Ears.Kind.NAME, Ears.Side.LEFT, -90f);
        rig.runUntil(400);
        return runUntilState(rig, ExploreBrain.State.CHAT_THINK, 400, 30000);
    }

    /**
     * Runs until a conversation seen after from is over (he left the CHAT states, for
     * PAUSE or EYES_ONLY); the time he left them, or -1. CHAT_NOTES itself can come and
     * go inside one reading step, so the state log is not relied on.
     */
    private static long chatOver(Rig rig, long from) {
        long limit = from + 120000;
        boolean inChat = rig.brain.state().chats();
        while (rig.now < limit) {
            rig.runUntil(rig.now + 10);
            if (rig.brain.state().chats()) {
                inChat = true;
            } else if (inChat) {
                return rig.now;
            }
        }
        return -1;
    }

    private static int match(Rig rig) {
        return rig.firstAfter("match", 0);
    }

    private static String questionsIn(String delta) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"questions_asked\":\\[([^\\]]*)\\]").matcher(delta);
        return m.find() ? m.group(1) : "";
    }

    /** The state log as one string, for a failing scenario's detail. */
    private static String states(Rig rig) {
        StringBuilder b = new StringBuilder("states=");
        for (Event e : rig.stateLog) {
            b.append(e.t).append(':').append(e.what).append(' ');
        }
        return b.toString();
    }

    private static boolean anyContains(List<String> lines, String... words) {
        for (String l : lines) {
            for (String w : words) {
                if (l.contains(w)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void chatScenarios() {
        scenario("chat_known_person_opener_carries_the_notes_and_persona_after_the_acknowledgement", n -> {
            // A greeting, not a call: the acknowledgement plays when the face is found (a call's does not).
            Rig rig = sarahRig(true);
            rig.cue(400, Ears.Kind.GREETING, Ears.Side.LEFT, -90f);
            rig.runUntil(400);
            long open = runUntilState(rig, ExploreBrain.State.CHAT_THINK, 400, 30000);
            long say = runUntilEvent(rig, "say Line 1.", open, open + 20000);
            TurnAsk first = rig.turnAsks.isEmpty() ? null : rig.turnAsks.get(0);
            int ack = rig.firstAfter("react acknowledge", 400);
            int turn = ack < 0 ? -1 : rig.firstAfter("turn", rig.timeOf(ack));
            int park = rig.firstAfter("park", 400);
            check(n, open > 0 && say > 0 && first != null && first.request.heard == null
                            && PERSONA.equals(first.request.persona) && SARAH_NOTES.equals(first.request.notes)
                            && "Sarah".equals(first.request.name) && first.request.transcript.isEmpty()
                            && first.timeoutMs == 5000 && ack >= 0 && turn > ack && park >= 0 && park <= turn
                            && rig.timeOf(turn) == rig.stamped(ExploreBrain.Gauges.Stage.MATCH_ANSWERED)
                            && rig.stamped(ExploreBrain.Gauges.Stage.LINE_REQUESTED) == rig.timeOf(turn)
                            && rig.stamped(ExploreBrain.Gauges.Stage.FIRST_SOUND) == say
                            && rig.openStates.contains(ExploreBrain.State.CHAT_THINK)
                            && rig.openStates.contains(ExploreBrain.State.CHAT_SPEAK)
                            && rig.countPrefix("camera close", rig.stamped(ExploreBrain.Gauges.Stage.FACE_FOUND), say + 1) == 0
                            && rig.openStates.contains(ExploreBrain.State.MEET) && rig.timeOf(park) <= rig.timeOf(match(rig))
                            && rig.count("say Hi Sarah!") == 0 && rig.touches == 1 && rig.violations.isEmpty(),
                    "open@" + open + " say@" + say + " first=" + first + " " + gauges(rig) + " " + rig.tail());
        });
        scenario("chat_three_turns_then_catch_you_later_signs_off_persists_once_and_resumes_away_from_them", n -> {
            Rig rig = sarahRig(true);
            rig.people.listen = ListenScript.turns(hearWords("hi").after(300), hearWords("good thanks"),
                    hearWords("catch you later"));
            long open = openChat(rig);
            long over = chatOver(rig, open);
            long pause = entered(rig, ExploreBrain.State.PAUSE, over - 1);
            long turnAway = runUntilEvent(rig, "turn RIGHT", over, over + 20000);
            int signOff = rig.firstAfter("react sign-off", open);
            TurnAsk third = rig.turnAsks.size() >= 3 ? rig.turnAsks.get(2) : null;
            check(n, open > 0 && over > 0 && pause > 0 && signOff >= 0 && rig.count("react sign-off") == 1
                            && rig.turnAsks.size() == 3 && third != null && "good thanks".equals(third.request.heard)
                            && third.request.transcript.size() == 2 && "hi".equals(third.request.transcript.get(1).heard)
                            && "Line 2.".equals(third.request.transcript.get(1).said)
                            && rig.notesDeltas.size() == 3 && rig.notesDeltas.get(0).startsWith(SARAH_ID + ": ")
                            && rig.count("notes DONE") == 3 && rig.metIdsGiven.size() == 1
                            && turnAway > 0 && rig.countPrefix("turn LEFT", over, turnAway) == 0
                            && rig.countPrefix("hop", over, turnAway) == 0
                            && rig.clipWindows.contains(rig.tuning.chatClipMs) && rig.violations.isEmpty(),
                    "open@" + open + " over@" + over + " pause@" + pause + " away@" + turnAway + " asks="
                            + rig.turnAsks + " deltas=" + rig.notesDeltas + " " + rig.tail());
        });
        scenario("chat_silence_twice_with_the_face_gone_at_the_first_look_ends_without_a_sign_off", n -> {
            long[] leaves = {Long.MAX_VALUE};
            Rig rig = chatRig(personAtUntil(bearingOf(-90f), 25, leaves), true).started();
            rig.people.listen = ListenScript.turns(hearWords("hi"), hearSilence(), hearSilence());
            long open = openChat(rig);
            long say2 = runUntilEvent(rig, "say Line 2.", open, open + 30000);
            leaves[0] = say2;
            long over = chatOver(rig, say2);
            int unpark = rig.firstAfter("unpark", say2);
            int repark = unpark < 0 ? -1 : rig.firstAfter("park", rig.timeOf(unpark));
            check(n, open > 0 && say2 > 0 && over > 0 && rig.count("react sign-off") == 0 && unpark >= 0 && repark > unpark
                            && rig.countPrefix("listen", open, over) == 2 && rig.notesDeltas.size() == 2
                            && rig.count("notes DONE") == 2 && rig.brain.state() == ExploreBrain.State.PAUSE
                            && rig.violations.isEmpty(),
                    "open@" + open + " say2@" + say2 + " over@" + over + " unpark=" + unpark + " " + rig.tail());
        });
        scenario("chat_silence_twice_with_the_face_still_there_signs_off_once", n -> {
            Rig rig = sarahRig(true);
            rig.people.listen = ListenScript.turns(hearWords("hi"), hearSilence(), hearSilence());
            long open = openChat(rig);
            long say2 = runUntilEvent(rig, "say Line 2.", open, open + 30000);
            long over = chatOver(rig, say2);
            int unpark = rig.firstAfter("unpark", say2);
            long secondListen = rig.timeOf(rig.firstAfter("listen", rig.timeOf(unpark)));
            check(n, open > 0 && say2 > 0 && over > 0 && rig.count("react sign-off") == 1 && unpark >= 0
                            && rig.countPrefix("unpark", open, over) == 1 && rig.countPrefix("listen", open, over) == 3
                            && secondListen > rig.timeOf(unpark) && rig.turnAsks.size() == 2
                            && rig.brain.state() == ExploreBrain.State.PAUSE && rig.violations.isEmpty(),
                    "open@" + open + " over@" + over + " unparks=" + rig.countPrefix("unpark", open, over) + " " + rig.tail());
        });
        // ---- Robot 2026-10-01: an answer that has started holds the listen past its 4 s ----
        scenario("chat_an_answer_started_at_2_5_s_and_ended_at_7_3_s_is_heard_with_no_unanswered_listen", n -> {
            String late = "we went to the beach with my sister";
            Rig rig = sarahRig(true);
            List<String> notes = traced(rig);
            rig.people.listen = ListenScript.turns(hearWords("hi").after(300),
                    hearWords(late).after(7300).answeringAfter(2500), hearWords("catch you later"));
            long open = openChat(rig);
            long over = chatOver(rig, open);
            long second = nthListenAt(rig, open, 2);
            long held = noteAt(notes, "an answer has started");
            int heard = rig.firstAfter("heard WORDS", second);
            TurnAsk third = rig.turnAsks.size() >= 3 ? rig.turnAsks.get(2) : null;
            check(n, open > 0 && over > 0 && second > 0 && third != null && late.equals(third.request.heard)
                            && held - second >= 4000 && held - second <= 4150
                            && rig.timeOf(heard) - second >= 7300 && rig.timeOf(heard) - second <= 7450
                            && noteAt(notes, "unanswered listen") < 0 && rig.count("react sign-off") == 1
                            && rig.violations.isEmpty(),
                    "open@" + open + " second@" + second + " held@" + held + " heard@" + rig.timeOf(heard)
                            + " asks=" + rig.turnAsks + " notes=" + notes);
        });
        scenario("chat_a_silent_listen_is_still_the_first_unanswered_listen_at_4_s", n -> {
            Rig rig = sarahRig(true);
            List<String> notes = traced(rig);
            rig.people.listen = ListenScript.turns(hearWords("hi").after(300), hearSilence(), hearSilence());
            long open = openChat(rig);
            long over = chatOver(rig, open);
            long second = nthListenAt(rig, open, 2);
            long first = noteAt(notes, "first unanswered listen");
            check(n, open > 0 && over > 0 && second > 0 && first - second >= 4000 && first - second <= 4150
                            && noteAt(notes, "an answer has started") < 0 && rig.violations.isEmpty(),
                    "second@" + second + " first unanswered@" + first + " notes=" + notes);
        });
        scenario("chat_an_answer_whose_words_never_come_ends_as_unanswered_at_the_fallback", n -> {
            Rig rig = sarahRig(true);
            List<String> notes = traced(rig);
            rig.people.listen = ListenScript.turns(hearWords("hi").after(300), hearAnsweringOnly(2500));
            long open = openChat(rig);
            long over = chatOver(rig, open);
            long second = nthListenAt(rig, open, 2);
            long held = noteAt(notes, "an answer has started");
            long gaveUp = noteAt(notes, "the answer's words never came");
            long first = noteAt(notes, "first unanswered listen");
            long hold = rig.tuning.answerHoldMs;
            check(n, open > 0 && over > 0 && second > 0 && hold == 63000
                            && held - second >= 4000 && held - second <= 4150
                            && gaveUp - second >= hold && gaveUp - second <= hold + 150 && first == gaveUp
                            && rig.violations.isEmpty(),
                    "second@" + second + " held@" + held + " gaveUp@" + gaveUp + " first@" + first + " notes=" + notes);
        });
        scenario("chat_an_older_launcher_that_never_says_answering_ends_the_listen_at_4_s_as_today", n -> {
            Rig rig = sarahRig(true);
            List<String> notes = traced(rig);
            rig.people.listen = ListenScript.turns(hearWords("hi").after(300),
                    hearWords("we went to the beach").after(7300), hearSilence());
            long open = openChat(rig);
            long over = chatOver(rig, open);
            long second = nthListenAt(rig, open, 2);
            long first = noteAt(notes, "first unanswered listen");
            check(n, open > 0 && over > 0 && second > 0 && first - second >= 4000 && first - second <= 4150
                            && noteAt(notes, "an answer has started") < 0 && rig.violations.isEmpty(),
                    "second@" + second + " first unanswered@" + first + " notes=" + notes);
        });
        scenario("chat_a_three_sentence_line_is_cut_to_two_before_speaking", n -> {
            Rig rig = sarahRig(true);
            rig.turns = turnsOf(CuriosityPort.Turn.line("One here. Two here! Three here?"));
            long open = openChat(rig);
            long say = runUntilEvent(rig, "say ", open, open + 20000);
            check(n, open > 0 && say > 0 && rig.count("say One here. Two here!") == 1
                            && rig.countPrefix("say One here. Two here! Three", 0, say + 1) == 0 && rig.violations.isEmpty(),
                    "say@" + say + " " + rig.tail());
        });
        scenario("chat_a_repeated_question_is_re_requested_once_and_a_second_repeat_is_stripped_and_counted", n -> {
            Rig rig = sarahRig(true);
            rig.turns = turnsOf(
                    CuriosityPort.Turn.line("Hi Sarah. How was the weekend?", "How was the weekend?", null, false, false, null),
                    CuriosityPort.Turn.line("Still here. How was the weekend?", "how was the weekend", null, false, false, null));
            long open = openChat(rig);
            long say = runUntilEvent(rig, "say ", open, open + 20000);
            TurnAsk second = rig.turnAsks.size() >= 2 ? rig.turnAsks.get(1) : null;
            check(n, open > 0 && say > 0 && rig.turnAsks.size() == 2 && second != null
                            && "How was the weekend?".equals(second.request.avoidQuestion) && second.request.heard == null
                            && second.timeoutMs > 0 && second.timeoutMs < 5000
                            && rig.count("say Still here.") == 1 && rig.counted(ExploreBrain.Gauges.Counter.REPEATS) == 1
                            && rig.violations.isEmpty(),
                    "say@" + say + " asks=" + rig.turnAsks + " " + gauges(rig) + " " + rig.tail());
        });
        scenario("chat_ends_conversation_true_is_spoken_as_a_normal_line_and_the_conversation_goes_on", n -> {
            Rig rig = sarahRig(true);
            rig.turns = turnsOf(CuriosityPort.Turn.line("Bye then.", null, null, true, false, null));
            rig.people.listen = ListenScript.turns(hearWords("no wait"), hearWords("catch you later"));
            long open = openChat(rig);
            long over = chatOver(rig, open);
            check(n, open > 0 && over > 0 && rig.count("say Bye then.") == 1 && rig.count("say Line 2.") == 1
                            && rig.turnAsks.size() == 2 && rig.count("react sign-off") == 1 && rig.violations.isEmpty(),
                    "open@" + open + " over@" + over + " asks=" + rig.turnAsks.size() + " " + rig.tail());
        });
        scenario("chat_ten_conversations_accumulate_notes_and_the_tenth_never_repeats_a_recorded_question", n -> {
            // AE4: the fake store carries the questions and threads from one conversation to the next.
            List<String> recorded = new ArrayList<String>();
            List<String> threads = new ArrayList<String>();
            List<String> spoken = new ArrayList<String>();
            int repeatsCounted = 0;
            TurnAsk opener10 = null;
            boolean fine = true;
            for (int c = 1; c <= 10 && fine; c++) {
                final int conv = c;
                Rig rig = sarahRig(true);
                rig.people.asked.put(SARAH_ID, new ArrayList<String>(recorded));
                StringBuilder notes = new StringBuilder("{\"open_threads\":[");
                for (int i = 0; i < threads.size(); i++) {
                    notes.append(i == 0 ? "" : ",").append("{\"text\":\"").append(threads.get(i)).append("\",\"since\":1}");
                }
                notes.append("],\"questions_asked\":[");
                for (int i = 0; i < recorded.size(); i++) {
                    notes.append(i == 0 ? "" : ",").append('"').append(recorded.get(i)).append('"');
                }
                rig.people.notes.put(SARAH_ID, notes.append("]}").toString());
                rig.people.listen = ListenScript.turns(hearWords("fine"), hearWords("bye"));
                rig.turns = (r, req, nth) -> {
                    // The tenth conversation's fake Claude first repeats the very first question ever asked.
                    if (conv == 10 && nth == 1) {
                        return CuriosityPort.Turn.line("Hi. Q1a?", "Q1a?", null, false, false, null);
                    }
                    String q = "Q" + conv + (req.heard == null ? "a" : "b") + "?";
                    return CuriosityPort.Turn.line("Hi. " + q, q, null, false, false,
                            "{\"questions_asked\":[\"" + q + "\"],\"open_threads\":[\"thread-" + conv + "\"]}");
                };
                long open = openChat(rig);
                long over = chatOver(rig, open);
                fine = open > 0 && over > 0 && rig.violations.isEmpty();
                for (String d : rig.notesDeltas) {
                    for (String q : questionsIn(d).split(",")) {
                        String bare = q.replace("\"", "").trim();
                        if (!bare.isEmpty() && !recorded.contains(bare)) {
                            recorded.add(bare);
                        }
                    }
                }
                threads.add("thread-" + c);
                if (c == 10) {
                    opener10 = rig.turnAsks.get(0);
                    repeatsCounted = rig.counted(ExploreBrain.Gauges.Counter.REPEATS);
                    for (Event e : rig.log) {
                        if (e.what.startsWith("say ")) {
                            spoken.add(e.what.substring(4));
                        }
                    }
                }
            }
            boolean noRepeat = true;
            for (String line : spoken) {
                for (String q : recorded) {
                    if (!q.startsWith("Q10") && line.contains(q)) {
                        noRepeat = false;
                    }
                }
            }
            check(n, fine && recorded.size() >= 18 && opener10 != null && opener10.request.notes.contains("thread-9")
                            && opener10.request.notes.contains("Q9b?") && spoken.size() == 2 && noRepeat
                            && spoken.get(0).equals("Hi. Q10a?") && repeatsCounted == 0,
                    "fine=" + fine + " recorded=" + recorded.size() + " spoken=" + spoken + " repeats=" + repeatsCounted
                            + " notes10=" + (opener10 == null ? null : opener10.request.notes));
        });
        scenario("chat_a_newcomer_mid_reply_gets_the_glance_and_one_sec_only_after_the_listen_ends", n -> {
            Rig rig = sarahRig(true);
            rig.people.listen = ListenScript.turns(hearWords("hi there").after(1500), hearWords("catch you later"));
            long open = openChat(rig);
            long listen = runUntilEvent(rig, "listen", open, open + 20000);
            rig.cue(listen + 500, Ears.Kind.WAKE_WORD, Ears.Side.RIGHT, 80f);
            long over = chatOver(rig, listen);
            int heard = rig.firstAfter("heard WORDS", listen);
            int glance = rig.firstAfter("eyes GLANCE RIGHT", listen);
            int oneSec = rig.firstAfter("react one-sec", listen);
            int turn2 = rig.firstAfter("turn", rig.timeOf(heard));
            TurnAsk second = rig.turnAsks.size() >= 2 ? rig.turnAsks.get(1) : null;
            long search = runUntilState(rig, ExploreBrain.State.CUE_TURN, over, over + 20000);
            check(n, open > 0 && listen > 0 && over > 0 && heard >= 0 && rig.timeOf(heard) == listen + 1500
                            && glance > heard && oneSec > glance && turn2 > oneSec
                            && second != null && "hi there".equals(second.request.heard)
                            && rig.counted(ExploreBrain.Gauges.Counter.CUES_HELD) == 1 && rig.count("react sign-off") == 1
                            && search >= over && rig.violations.isEmpty(),
                    "listen@" + listen + " heard@" + rig.timeOf(heard) + " glance@" + rig.timeOf(glance) + " oneSec@"
                            + rig.timeOf(oneSec) + " search@" + search + " " + gauges(rig) + " " + rig.tail());
        });
        scenario("chat_forget_me_from_a_named_person_confirms_by_name_and_yes_forgets_by_id_and_clears_the_notes", n -> {
            Rig rig = sarahRig(true);
            rig.people.listen = ListenScript.turns(hearWords("hi"), hearWords("forget me"), hearWords("yes"),
                    hearWords("catch you later"));
            long open = openChat(rig);
            long over = chatOver(rig, open);
            int confirm = rig.firstAfter("say Forget you, Sarah?", open);
            int forgot = rig.firstAfter("forget " + SARAH_ID, open);
            int done = rig.firstAfter("say Done. I've forgotten you.", open);
            check(n, open > 0 && over > 0 && confirm >= 0 && forgot > confirm && done > forgot
                            && rig.forgotten.equals(java.util.Arrays.asList(SARAH_ID)) && rig.notesDeltas.isEmpty()
                            && rig.turnAsks.size() == 2 && rig.count("react sign-off") == 1 && rig.metIdsGiven.isEmpty()
                            && rig.violations.isEmpty(),
                    "over@" + over + " confirm@" + rig.timeOf(confirm) + " forgot=" + rig.forgotten + " deltas="
                            + rig.notesDeltas + " asks=" + rig.turnAsks.size() + " " + states(rig) + " " + rig.tail());
        });
        scenario("chat_forget_me_not_confirmed_keeps_them_and_dont_forget_me_is_just_a_reply", n -> {
            Rig rig = sarahRig(true);
            rig.people.listen = ListenScript.turns(hearWords("forget me"), hearWords("no"), hearWords("forget me"),
                    hearWords("yes, no wait"), hearWords("don't forget me"), hearWords("catch you later"));
            long open = openChat(rig);
            long over = chatOver(rig, open);
            TurnAsk last = rig.turnAsks.isEmpty() ? null : rig.turnAsks.get(rig.turnAsks.size() - 1);
            check(n, open > 0 && over > 0 && rig.count("say Forget you, Sarah?") == 2 && rig.count("say Okay, keeping you.") == 2
                            && rig.forgotten.isEmpty() && last != null && "don't forget me".equals(last.request.heard)
                            && rig.turnAsks.size() == 2 && rig.notesDeltas.size() == 2 && rig.count("react sign-off") == 1
                            && rig.violations.isEmpty(),
                    "asks=" + rig.turnAsks + " forgot=" + rig.forgotten + " " + rig.tail());
        });
        scenario("chat_forget_me_from_an_unnamed_person_plays_nothing_kept_and_calls_no_store", n -> {
            Rig rig = sarahRig(false);
            rig.people.listen = ListenScript.turns(hearWords("forget me"), hearWords("bye"));
            long open = openChat(rig);
            long over = chatOver(rig, open);
            check(n, open > 0 && over > 0 && rig.count("react nothing-kept") == 1 && rig.forgotten.isEmpty()
                            && rig.countPrefix("say Forget you", 0, over) == 0 && rig.notesDeltas.isEmpty()
                            && rig.turnAsks.size() == 1 && rig.violations.isEmpty(),
                    "over@" + over + " " + rig.tail());
        });
        scenario("chat_a_name_given_on_turn_four_keeps_the_crop_at_once_and_x9_lol_is_no_name", n -> {
            Rig rig = sarahRig(false);
            rig.turns = turnsOf(turnLine(1),
                    CuriosityPort.Turn.line("Line 2.", null, "x9 lol", false, false, "{\"topics\":[\"t2\"]}"),
                    turnLine(3),
                    CuriosityPort.Turn.line("Line 4.", null, "Sarah", false, false, "{\"topics\":[\"t4\"]}"));
            rig.people.listen = ListenScript.turns(hearWords("a"), hearWords("b"), hearWords("c"), hearWords("d"),
                    hearWords("bye"));
            long open = openChat(rig);
            long over = chatOver(rig, open);
            int line4 = rig.firstAfter("turn LINE", rig.timeOf(rig.firstAfter("say Line 3.", open)));
            int keep = rig.firstAfter("keep", open);
            int say4 = rig.firstAfter("say Line 4.", open);
            TurnAsk fifth = rig.turnAsks.size() >= 5 ? rig.turnAsks.get(4) : null;
            boolean allKept = !rig.notesDeltas.isEmpty();
            for (String d : rig.notesDeltas) {
                allKept &= d.startsWith("kept-1: ");
            }
            check(n, open > 0 && over > 0 && rig.kept.equals(java.util.Arrays.asList("Sarah")) && keep >= 0 && keep >= line4
                            && keep < say4 && fifth != null && "Sarah".equals(fifth.request.name)
                            && rig.turnAsks.get(3).request.name == null && allKept && rig.notesDeltas.size() == 5
                            && rig.metIdsGiven.size() == 1 && rig.violations.isEmpty(),
                    "kept=" + rig.kept + " keep@" + rig.timeOf(keep) + " say4@" + rig.timeOf(say4) + " deltas="
                            + rig.notesDeltas + " " + rig.tail());
        });
        scenario("chat_a_known_conversation_whose_name_given_differs_makes_a_new_record_and_never_writes_the_old_id", n -> {
            Rig rig = sarahRig(true);
            rig.turns = turnsOf(turnLine(1),
                    CuriosityPort.Turn.line("Line 2.", null, "Priya", false, false, "{\"topics\":[\"t2\"]}"));
            rig.people.listen = ListenScript.turns(hearWords("i'm priya"), hearWords("bye"));
            long open = openChat(rig);
            long over = chatOver(rig, open);
            boolean allNew = rig.notesDeltas.size() == 2;
            for (String d : rig.notesDeltas) {
                allNew &= d.startsWith("kept-1: ");
            }
            check(n, open > 0 && over > 0 && rig.kept.equals(java.util.Arrays.asList("Priya")) && allNew
                            && rig.countPrefix("notes " + SARAH_ID, 0, over) == 0 && rig.violations.isEmpty(),
                    "kept=" + rig.kept + " deltas=" + rig.notesDeltas + " " + rig.tail());
        });
        scenario("chat_newcomer_wake_word_above_the_angle_is_held_and_greeted_after_and_inside_the_angle_is_a_reply", n -> {
            Rig held = sarahRig(true);
            held.people.listen = ListenScript.turns(hearWords("yes").after(2500), hearWords("catch you later"));
            long open = openChat(held);
            long listen = runUntilEvent(held, "listen", open, open + 20000);
            held.cue(listen + 300, Ears.Kind.WAKE_WORD, Ears.Side.RIGHT, 70f);
            long over = chatOver(held, listen);
            long search = runUntilState(held, ExploreBrain.State.CUE_TURN, over, over + 20000);
            Rig inside = sarahRig(true);
            inside.people.listen = ListenScript.turns(hearWords("yes").after(2500), hearWords("catch you later"));
            long open2 = openChat(inside);
            long listen2 = runUntilEvent(inside, "listen", open2, open2 + 20000);
            inside.cue(listen2 + 300, Ears.Kind.WAKE_WORD, Ears.Side.LEFT, -10f);
            long over2 = chatOver(inside, listen2);
            check(n, open > 0 && over > 0 && held.counted(ExploreBrain.Gauges.Counter.CUES_HELD) == 1
                            && held.count("react one-sec") == 1 && held.countPrefix("eyes GLANCE RIGHT", listen, over) == 1
                            && held.turnAsks.size() == 2 && held.count("react sign-off") == 1 && search >= over
                            && held.count("react answer") == 2 && answerAt(held, over) == search
                            && inside.count("react answer") == 1
                            && open2 > 0 && over2 > 0 && inside.counted(ExploreBrain.Gauges.Counter.CUES_HELD) == 0
                            && inside.counted(ExploreBrain.Gauges.Counter.CUES_DROPPED) == 1
                            && inside.count("react one-sec") == 0 && inside.turnAsks.size() == 2
                            && entered(inside, ExploreBrain.State.CUE_TURN, over2) < 0
                            && held.violations.isEmpty() && inside.violations.isEmpty(),
                    "held: " + gauges(held) + " search@" + search + " inside: " + gauges(inside) + " " + inside.tail());
        });
        scenario("chat_a_retried_turn_whose_first_reply_arrives_late_does_not_merge_its_delta_twice", n -> {
            Rig rig = sarahRig(true);
            rig.turnDelayMs = 6000;
            rig.people.listen = ListenScript.turns(hearWords("bye"));
            long open = openChat(rig);
            rig.at(open + 5000, () -> rig.turnDelayMs = 1000);
            long over = chatOver(rig, open);
            int cancel = rig.firstAfter("cancel turn", open);
            check(n, open > 0 && over > 0 && rig.countPrefix("cancel turn", open, open + 5001) == 1 && cancel >= 0
                            && rig.timeOf(cancel) == open + 5000
                            && rig.turnAsks.size() == 2 && rig.turnAsks.get(1).timeoutMs == 3000
                            && rig.turnAsks.get(1).request.heard == null && rig.count("say Line 2.") == 1
                            && rig.notesDeltas.size() == 1 && rig.violations.isEmpty(),
                    "cancel@" + rig.timeOf(cancel) + " asks=" + rig.turnAsks + " deltas=" + rig.notesDeltas + " "
                            + rig.tail());
        });
        scenario("chat_unreachable_twice_ends_with_the_local_sign_off_within_the_budget_and_merges_the_notes_once", n -> {
            Rig rig = sarahRig(true);
            rig.turns = turnsOf(turnLine(1), CuriosityPort.Turn.unreachable(), CuriosityPort.Turn.unreachable());
            rig.people.listen = ListenScript.turns(hearWords("ok"));
            long open = openChat(rig);
            long heard = runUntilEvent(rig, "heard WORDS", open, open + 20000);
            long over = chatOver(rig, heard);
            int signOff = rig.firstAfter("react sign-off", heard);
            check(n, open > 0 && heard > 0 && over > 0 && rig.turnAsks.size() == 3 && rig.turnAsks.get(2).timeoutMs == 3000
                            && signOff >= 0 && rig.timeOf(signOff) <= heard + 5000 + 3000
                            && rig.notesDeltas.size() == 1 && rig.count("notes DONE") == 1
                            && rig.brain.state() == ExploreBrain.State.PAUSE && rig.violations.isEmpty(),
                    "heard@" + heard + " signOff@" + rig.timeOf(signOff) + " asks=" + rig.turnAsks + " " + rig.tail());
        });
        scenario("chat_a_refusal_plays_the_deflection_and_the_conversation_continues", n -> {
            Rig rig = sarahRig(true);
            rig.turns = turnsOf(turnLine(1), CuriosityPort.Turn.refused());
            rig.people.listen = ListenScript.turns(hearWords("set a timer"), hearWords("ok then"), hearWords("bye"));
            long open = openChat(rig);
            long over = chatOver(rig, open);
            TurnAsk third = rig.turnAsks.size() >= 3 ? rig.turnAsks.get(2) : null;
            check(n, open > 0 && over > 0 && rig.count("react deflect") == 1 && rig.turnAsks.size() == 3 && third != null
                            && third.request.transcript.size() == 2
                            && ChatSession.DEFLECT_SAID.equals(third.request.transcript.get(1).said)
                            && rig.count("say Line 3.") == 1 && rig.count("react sign-off") == 1 && rig.violations.isEmpty(),
                    "asks=" + rig.turnAsks + " " + rig.tail());
        });
        scenario("chat_a_persona_edit_between_turns_is_heard_only_in_the_next_conversation", n -> {
            Rig rig = chatRig((r, t) -> list(facingPerson()), true).started();
            rig.people.listen = ListenScript.turns(hearWords("hi"), hearWords("more"), hearWords("bye"),
                    hearWords("hi again"), hearWords("bye"));
            long open = openChat(rig);
            rig.at(open + 3000, () -> rig.people.persona = PERSONA_EDITED);
            long over = chatOver(rig, open);
            int first = rig.turnAsks.size();
            rig.cue(over + 3000, Ears.Kind.NAME, Ears.Side.LEFT, -90f);
            long open2 = runUntilState(rig, ExploreBrain.State.CHAT_THINK, over + 3000, over + 40000);
            long over2 = chatOver(rig, open2);
            boolean oldAll = first == 3;
            for (int i = 0; i < first && i < rig.turnAsks.size(); i++) {
                oldAll &= PERSONA.equals(rig.turnAsks.get(i).request.persona);
            }
            boolean newAll = rig.turnAsks.size() > first;
            for (int i = first; i < rig.turnAsks.size(); i++) {
                newAll &= PERSONA_EDITED.equals(rig.turnAsks.get(i).request.persona);
            }
            check(n, open > 0 && over > 0 && open2 > 0 && over2 > 0 && oldAll && newAll && rig.violations.isEmpty(),
                    "first=" + first + " asks=" + rig.turnAsks.size() + " open2@" + open2 + " " + gauges(rig) + " "
                            + states(rig));
        });
        scenario("chat_the_charger_mid_conversation_lets_it_finish_and_drives_no_resume_leg", n -> {
            long[] dock = {Long.MAX_VALUE};
            Rig rig = chatRig(cueTuning(), t -> t >= dock[0] ? charger(t) : clear(t), personAt(bearingOf(-90f), 25), true);
            rig.traceNotes = traced(rig);
            rig.started();
            rig.people.listen = ListenScript.turns(hearWords("hi"), hearWords("more"), hearWords("more again"));
            long open = openChat(rig);
            long say2 = runUntilEvent(rig, "say Line 2.", open, open + 30000);
            dock[0] = say2 + 100;
            long over = chatOver(rig, say2);
            int closed = rig.firstAfter("ears close", say2);
            rig.runUntil(over + 15000);
            List<String> notes = rig.traceNotes;
            check(n, open > 0 && say2 > 0 && over > 0 && rig.count("react sign-off") == 1 && rig.turnAsks.size() == 2
                            && closed < 0 && rig.count("ears close") == 0 && rig.notesDeltas.size() == 2
                            && rig.countPrefix("turn RIGHT", over, over + 6000) == 0
                            && rig.countPrefix("hop", over, over + 6000) == 0
                            && anyContains(notes, "on the charger: no resume leg") && rig.violations.isEmpty(),
                    "say2@" + say2 + " over@" + over + " closed@" + rig.timeOf(closed) + " " + rig.tail());
        });
        scenario("chat_lease_lost_mid_conversation_continues_without_the_look_and_a_6_s_sensor_stall_ends_it", n -> {
            Rig lost = sarahRig(true);
            lost.people.listen = ListenScript.turns(hearWords("hi"), hearSilence(), hearSilence());
            long open = openChat(lost);
            long say2 = runUntilEvent(lost, "say Line 2.", open, open + 30000);
            lost.at(say2 + 100, () -> lost.brain.onLeaseChanged(false));
            long over = chatOver(lost, say2);
            long eyesOnly = runUntilState(lost, ExploreBrain.State.EYES_ONLY, over - 1, over + 5000);
            long[] stall = {Long.MAX_VALUE};
            Rig stalled = chatRig(cueTuning(), t -> t >= stall[0] ? null : clear(t), personAt(bearingOf(-90f), 25), true)
                    .started();
            stalled.people.listen = ListenScript.NEVER;
            long open2 = openChat(stalled);
            long say1 = runUntilEvent(stalled, "say Line 1.", open2, open2 + 30000);
            stall[0] = say1 + 100;
            long over2 = chatOver(stalled, say1);
            int signOff2 = stalled.firstAfter("react sign-off", say1);
            long eyesOnly2 = runUntilState(stalled, ExploreBrain.State.EYES_ONLY, over2 - 1, over2 + 2000);
            check(n, open > 0 && say2 > 0 && over > 0 && lost.countPrefix("unpark", say2, over) == 0
                            && lost.count("react sign-off") == 1
                            && lost.countPrefix("listen", say2, over) == 2 && lost.notesDeltas.size() == 2 && eyesOnly > 0
                            && open2 > 0 && say1 > 0 && over2 > 0 && signOff2 >= 0
                            && stalled.timeOf(signOff2) >= stall[0] + 5000 && stalled.timeOf(signOff2) < stall[0] + 7000
                            && eyesOnly2 > 0
                            && lost.violations.isEmpty() && stalled.violations.isEmpty(),
                    "lost: over@" + over + " eyesOnly@" + eyesOnly + " unparks=" + lost.count("unpark") + " stalled: signOff@"
                            + (signOff2 < 0 ? -1 : stalled.timeOf(signOff2)) + " end=" + stalled.brain.state() + " "
                            + stalled.tail());
        });
        scenario("chat_eyes_only_wake_word_opens_a_stranger_conversation_without_a_turn_or_a_match_and_stores_nothing", n -> {
            Rig rig = chatRig(EMPTY_ROOM, false);
            rig.people.lines = STRANGER.withConversation(PERSONA, null, null, null);
            rig.turns = turnsOf(turnLine(1), CuriosityPort.Turn.line("Line 2.", null, "Sam", false, false, null));
            rig.people.listen = ListenScript.turns(hearWords("i'm sam"), hearWords("bye"));
            rig.brain.start();
            rig.cue(1000, Ears.Kind.WAKE_WORD, Ears.Side.RIGHT, 60f);
            long open = runUntilState(rig, ExploreBrain.State.CHAT_THINK, 1000, 20000);
            long over = chatOver(rig, open);
            check(n, open > 0 && over > 0 && rig.count("match") == 0 && rig.countPrefix("turn LEFT", 0, over) == 0
                            && rig.countPrefix("turn RIGHT", 0, over) == 0 && rig.count("camera open") == 0
                            && rig.turnAsks.size() == 2 && rig.turnAsks.get(0).request.name == null
                            && rig.kept.isEmpty() && rig.notesDeltas.isEmpty() && rig.count("react sign-off") == 1
                            && rig.brain.state() == ExploreBrain.State.EYES_ONLY && rig.violations.isEmpty(),
                    "open@" + open + " over@" + over + " end=" + rig.brain.state() + " " + rig.tail());
        });
        scenario("chat_the_transcript_never_appears_in_the_trace", n -> {
            Rig rig = sarahRig(true);
            List<String> notes = traced(rig);
            rig.turns = turnsOf(CuriosityPort.Turn.line("Cheese is great.", "Like cheese?", "Sarah", false, false,
                    "{\"topics\":[\"cheese\"]}"));
            rig.people.listen = ListenScript.turns(hearWords("i love gouda"), hearWords("forget me"), hearWords("yes"),
                    hearWords("catch you later"));
            long open = openChat(rig);
            long over = chatOver(rig, open);
            check(n, open > 0 && over > 0 && !notes.isEmpty()
                            && !anyContains(notes, "Cheese", "cheese", "gouda", "Like", "Sarah", "catch you", "forget me",
                                    "Line 2", "Forget you"),
                    "over@" + over + " notes=" + notes);
        });
    }

    // ---- the remark rate (an observation about every 30 s with nobody about) ----

    /** Things no two of which match loosely, so every pick is new to him. */
    private static final String[] NEW_THINGS = {"lamp", "plant", "chair", "mug", "poster", "clock", "book", "window",
            "shoe", "bag", "desk", "cable", "kettle", "bin", "coat", "phone", "radiator", "door", "printer", "sofa",
            "whiteboard", "umbrella", "bottle", "keyboard", "monitor", "rug", "fan", "guitar", "basket", "pillow"};

    /** Claude picks something new in the first look every time he is asked, with a line to say. */
    private static final Claude PICKS_SOMETHING = (r, req, nth) -> {
        String thing = NEW_THINGS[(nth - 1) % NEW_THINGS.length];
        return pick(0, thing, CuriosityPort.Kind.OTHER, "Look at that " + thing + "!", 0.5f, 0.5f, 0.2f, 0.3f);
    };

    /** Ten simulated minutes in an empty room with the robot's own tuning: the spoken remarks, logged as RATE. */
    private static Rig emptyRoomTenMinutes(ExploreTuning tuning, List<String> notes) {
        Rig rig = new Rig(tuning, CLEAR, EMPTY_ROOM, true, PICKS_SOMETHING);
        rig.brain.setTrace(x -> notes.add(rig.now + " " + x));
        rig.started();
        rig.runUntil(600000);
        return rig;
    }

    /** A familiar office (robot 2026-10-01): Claude keeps picking the same few things, each time with a new line. */
    private static final String[] FAMILIAR_THINGS = {"plant", "refrigerator", "gaming console"};

    private static final Claude PICKS_THE_SAME_FEW = (r, req, nth) -> {
        String thing = FAMILIAR_THINGS[(nth - 1) % FAMILIAR_THINGS.length];
        return pick(0, thing, CuriosityPort.Kind.OTHER, "Thought " + nth + " about that " + thing + ".",
                0.5f, 0.5f, 0.2f, 0.3f);
    };

    /** The spoken lines in the log, in order. */
    private static List<String> spoken(Rig rig) {
        List<String> out = new ArrayList<String>();
        for (Event e : rig.log) {
            if (e.what.startsWith("say ")) {
                out.add(e.what.substring(4));
            }
        }
        return out;
    }

    private static void remarkRateScenarios() {
        scenario("one_slow_look_retries_the_stop_soon_and_curiosity_stays_on", n -> {
            // The robot's own look budgets and back-off, with the camera dark in the first stop only.
            // Look-then-go keeps it closed while roaming, so the stop opens it and has no look at all.
            boolean[] dark = {true};
            Vision v = (r, t) -> dark[0] && r.brain.state() == ExploreBrain.State.SCAN ? null : list();
            Rig rig = new Rig(claudeTuning().curiosityMs(25000, 40000).lookTiming(400, 10000, 8000)
                    .cameraBackoffMs(120000).navigation(ExploreTuning.Navigation.LOOK_THEN_GO).build(), CLEAR, v,
                    true, PICKS_SOMETHING);
            long[] miss = {-1};
            rig.brain.setTrace(x -> {
                if (miss[0] < 0 && x.contains("no look in time") && rig.brain.state() == ExploreBrain.State.SCAN) {
                    miss[0] = rig.now;
                    dark[0] = false;
                }
            });
            rig.started();
            rig.runUntil(90000);
            long retry = miss[0] < 0 ? -1 : entered(rig, ExploreBrain.State.SCAN, miss[0] + 1);
            int say = miss[0] < 0 ? -1 : rig.firstAfter("say ", miss[0]);
            check(n, miss[0] > 0 && retry >= miss[0] + 3000 && retry <= miss[0] + 6000 && say >= 0
                            && rig.timeOf(say) < miss[0] + 30000 && rig.violations.isEmpty(),
                    "miss@" + miss[0] + " retry@" + retry + " say@" + (say < 0 ? -1 : rig.timeOf(say)) + " "
                            + rig.tail());
        });
        scenario("two_slow_looks_in_a_row_turn_curiosity_off_for_30_s", n -> {
            // The camera is dark in every stop: the stop misses, retries and misses again.
            // Look-then-go keeps it closed while roaming, so each stop opens it and has no look at all.
            Vision dark = (r, t) -> r.brain.state() == ExploreBrain.State.SCAN ? null : list();
            Rig rig = new Rig(curious().lookTiming(400, 3000, 8000).cameraBackoffMs(120000)
                    .navigation(ExploreTuning.Navigation.LOOK_THEN_GO).build(), CLEAR, dark, true);
            List<Long> misses = new ArrayList<Long>();
            rig.brain.setTrace(x -> {
                if (x.contains("no look in time") && rig.brain.state() == ExploreBrain.State.SCAN) {
                    misses.add(rig.now);
                }
            });
            rig.started();
            rig.runUntil(60000);
            long second = misses.size() < 2 ? -1 : misses.get(1);
            List<Long> scans = entries(rig, ExploreBrain.State.SCAN);
            long afterOff = -1;
            for (long t : scans) {
                if (second > 0 && t > second) {
                    afterOff = t;
                    break;
                }
            }
            check(n, misses.size() >= 2 && scans.size() >= 2 && scans.get(1) > misses.get(0)
                            && second > 0 && afterOff >= second + 30000 && afterOff <= second + 33000
                            && rig.violations.isEmpty(),
                    "misses=" + misses + " scans=" + scans + " " + rig.tail());
        });
        scenario("remark_rate_default_tuning_empty_room_at_least_12_remarks_in_10_min", n -> {
            List<String> notes = new ArrayList<String>();
            Rig rig = emptyRoomTenMinutes(new ExploreTuning.Builder().calibration(calibration()).build(), notes);
            int said = rig.countPrefix("say ", 0, 600001);
            List<Long> scans = entries(rig, ExploreBrain.State.SCAN);
            List<Long> says = new ArrayList<Long>();
            for (Event e : rig.log) {
                if (e.what.startsWith("say ")) {
                    says.add(e.t);
                }
            }
            String lastRate = null;
            for (String x : notes) {
                if (x.contains("remarks in the last 10 min: ")) {
                    lastRate = x.substring(x.indexOf("remarks in"));
                }
            }
            System.out.println("REPORT remark rate, default tuning, an empty room, 10 simulated min: " + said
                    + " remarks, " + scans.size() + " stops; stops at " + scans + "; remarks at " + says);
            check(n, said >= 12 && rig.counted(ExploreBrain.Gauges.Counter.REMARKS) == said
                            && ("remarks in the last 10 min: " + said).equals(lastRate) && rig.violations.isEmpty(),
                    "said=" + said + " counted=" + rig.counted(ExploreBrain.Gauges.Counter.REMARKS) + " lastRate=" + lastRate + " " + rig.tail());
        });
        scenario("remark_rate_familiar_room_at_least_12_remarks_in_10_min_none_repeated", n -> {
            // Robot 2026-10-01: in a familiar office every pick was something he had reacted to, and the stop
            // ended silently (2 remarks in 30 min). A familiar pick with a fresh line is now said.
            List<String> notes = new ArrayList<String>();
            Rig rig = new Rig(new ExploreTuning.Builder().calibration(calibration()).build(), CLEAR, EMPTY_ROOM, true,
                    PICKS_THE_SAME_FEW);
            rig.brain.setTrace(x -> notes.add(rig.now + " " + x));
            rig.started();
            rig.runUntil(600000);
            List<String> lines = spoken(rig);
            int said = rig.countPrefix("say ", 0, 600001);
            boolean unique = new java.util.HashSet<String>(lines).size() == lines.size();
            System.out.println("REPORT remark rate, default tuning, a familiar room, 10 simulated min: " + said
                    + " remarks, " + entries(rig, ExploreBrain.State.SCAN).size() + " stops, unique=" + unique);
            check(n, said >= 12 && unique && rig.violations.isEmpty(),
                    "said=" + said + " unique=" + unique + " lines=" + lines + " " + rig.tail());
        });
        scenario("claude_familiar_pick_with_a_fresh_line_is_said", n -> {
            Rig rig = new Rig(claudeTuning().build(), CLEAR, EMPTY_ROOM, true,
                    (r, req, nth) -> pick(0, nth == 1 ? "potted plant" : "plant", CuriosityPort.Kind.OTHER,
                            nth == 1 ? "What a lovely plant!" : "That plant has grown since this morning, take " + nth + ".",
                            0.5f, 0.5f, 0.2f, 0.3f));
            List<String> notes = new ArrayList<String>();
            rig.brain.setTrace(notes::add);
            rig.started();
            rig.runUntil(120000);
            boolean silent = false;
            for (String x : notes) {
                silent |= x.contains("as good as nothing");
            }
            check(n, rig.count("say What a lovely plant!") == 1
                            && rig.countPrefix("say That plant has grown since this morning, take ", 0, 120001) >= 1
                            && !silent
                            && rig.violations.isEmpty(),
                    "asks=" + rig.asks.size() + " lines=" + spoken(rig) + " " + rig.tail());
        });
        scenario("claude_familiar_pick_with_no_line_or_a_repeated_line_is_as_good_as_nothing", n -> {
            // No fresh line: the old ending, no remark. A line he has already said counts as no line.
            Rig rig = new Rig(claudeTuning().build(), CLEAR, EMPTY_ROOM, true,
                    (r, req, nth) -> pick(0, "plant", CuriosityPort.Kind.OTHER,
                            nth == 1 ? "What a lovely plant!" : nth % 2 == 0 ? "" : "What a lovely plant!",
                            0.5f, 0.5f, 0.2f, 0.3f));
            List<String> notes = new ArrayList<String>();
            rig.brain.setTrace(notes::add);
            rig.started();
            rig.runUntil(150000);
            int already = 0;
            for (String x : notes) {
                already += x.contains("reacted to that already") ? 1 : 0;
            }
            check(n, rig.asks.size() >= 3 && rig.countPrefix("say ", 0, 150001) == 1 && already >= 2
                            && rig.violations.isEmpty(),
                    "asks=" + rig.asks.size() + " already=" + already + " lines=" + spoken(rig) + " " + rig.tail());
        });
        scenario("claude_look_request_carries_what_he_reacted_to_and_said_but_no_person_or_name", n -> {
            // A named person is met first, then things: the request lists things only, most recent first.
            String[] things = {"lamp", "kettle", "radiator"};
            Rig rig = new Rig(claudeTuning().build(), CLEAR, (r, t) -> list(), true,
                    (r, req, nth) -> nth == 1
                            ? pick(2, "man in a blue shirt", CuriosityPort.Kind.PERSON, "Hi!", 0.5f, 0.5f, 0.4f, 0.8f)
                            : pick(0, things[(nth - 2) % things.length], CuriosityPort.Kind.OTHER,
                            "Remark " + nth + " on the " + things[(nth - 2) % things.length] + ".",
                            0.5f, 0.5f, 0.2f, 0.3f));
            rig.people.match = (r, k) -> CuriosityPort.MatchAnswer.known("Sarah", "Hi {name}!", "Hi there, friend!");
            rig.started();
            rig.runUntil(240000);
            CuriosityPort.LookRequest last = rig.asks.get(rig.asks.size() - 1);
            boolean clean = true;
            for (CuriosityPort.LookRequest q : rig.asks) {
                String text = ExplorePrompts.lookAsk(q) + q.reacted + q.said;
                clean &= !text.contains("Sarah") && !q.reacted.contains("man in a blue shirt")
                        && !q.reacted.contains("person") && !q.said.contains("Hi Sarah!") && !q.said.contains("Hi!");
            }
            List<String> lines = spoken(rig);
            // Most recent first, capped: the newest remark is about the newest thing reacted to.
            boolean listed = rig.asks.size() >= 14 && last.reacted.size() == 3 && last.said.size() == 10
                    && lines.contains(last.said.get(0)) && last.said.get(0).contains(last.reacted.get(0))
                    && !last.said.get(0).equals(last.said.get(1));
            check(n, rig.count("say Hi Sarah!") + rig.count("say Hi there, friend!") >= 1 && listed && clean
                            && rig.violations.isEmpty(),
                    "asks=" + rig.asks.size() + " reacted=" + last.reacted + " said=" + last.said + " lines=" + lines
                            + " " + rig.tail());
        });
        scenario("prompt_lists_reacted_things_and_said_lines_and_asks_for_a_fresh_line", n -> {
            List<String> reacted = new ArrayList<String>();
            reacted.add("refrigerator");
            reacted.add("potted plant");
            List<String> said = new ArrayList<String>();
            said.add("That fridge hums like it knows a secret.");
            String p = ExplorePrompts.lookAsk(new CuriosityPort.LookRequest(new ArrayList<CuriosityPort.Frame>(),
                    new ArrayList<CuriosityPort.Recent>(), false, reacted, said));
            String q = ExplorePrompts.lookAsk(new CuriosityPort.LookRequest(new ArrayList<CuriosityPort.Frame>(),
                    new ArrayList<CuriosityPort.Recent>(), false));
            check(n, p.contains("refrigerator; potted plant") && p.contains("Prefer something not on this list")
                            && p.contains("That fridge hums like it knows a secret.") && p.contains("never repeat")
                            && p.contains("say something new about it") && !q.contains("Prefer something not on")
                            && !q.contains("already said"),
                    p);
        });
    }
    // ---- docked: quiet on the charger, a remark only for something new (owner 2026-10-02) ----
    //
    // On the charger he used to roam on: every leg refused, startles, escape turns that
    // whirred, and the eyes-only song every few seconds. Docked he now sits still in
    // DOCKED with the eyes DOCKED (ModeApp sings only for RESTING and EYES_ONLY), takes
    // one camera look every dockLookMs, and speaks only for something he has not
    // reacted to this session with a line he has not said.

    /** Docked from the first reading: Claude as given, the cue rig's people. */
    private static Rig dockRig(Claude claude, Vision v) {
        Rig rig = new Rig(cueTuning().build(), t -> charger(t), v, true, claude);
        rig.people.match = (r, k) -> CuriosityPort.MatchAnswer.known("Sarah", "Hi {name}!", "Hello again!");
        rig.people.lines = STRANGER;
        return rig;
    }

    /** A kettle in view from 100 s on. */
    private static final Vision KETTLE_FROM_100S = (r, t) -> t >= 100000
            ? list(box("kettle", 0.8f, 0.1f, 0.5f, 0.2f, 0.3f)) : list();

    /** Claude: nothing until the kettle comes, then the kettle with a fresh line every time. */
    private static final Claude KETTLE_PICKS = (r, req, nth) -> r.now >= 100000
            ? pick(0, "kettle", CuriosityPort.Kind.OTHER, "Ooh, a kettle, take " + nth + "!", 0.1f, 0.5f, 0.2f, 0.3f)
            : CuriosityPort.Answer.nothing();

    /** Eye states that make ModeApp sing (RESTING, EYES_ONLY) shown in [from, to). */
    private static int singingEyes(Rig rig, long from, long to) {
        return rig.countPrefix("eyes RESTING", from, to) + rig.countPrefix("eyes EYES_ONLY", from, to);
    }

    private static int sounds(Rig rig, long from, long to) {
        return rig.countPrefix("startle", from, to) + rig.countPrefix("react ", from, to)
                + rig.countPrefix("name ", from, to) + rig.countPrefix("say ", from, to);
    }

    private static void dockScenarios() {
        scenario("dock_five_minutes_docked_no_songs_no_startles_no_wheels", n -> {
            Rig rig = dockRig((r, req, nth) -> CuriosityPort.Answer.nothing(), EMPTY_ROOM);
            List<String> notes = traced(rig);
            rig.started();
            rig.shoveAt(90000, 900);
            rig.cue(150000, Ears.Kind.VOICE, Ears.Side.LEFT, -60f);
            rig.runUntil(300000);
            int asks = rig.asks.size();
            check(n, rig.brain.state() == ExploreBrain.State.DOCKED && rig.count("eyes DOCKED") >= 1
                            && singingEyes(rig, 500, 300001) == 0 && sounds(rig, 0, 300001) == 0
                            && rig.motions(0, 300001) == 0 && asks >= 4 && asks <= 5
                            && rig.count("camera open") == 1 && rig.count("camera close") == 0
                            && notesWith(notes, "docked: on the charger") == 1
                            && notesWith(notes, "docked look: nothing new") == asks && rig.violations.isEmpty(),
                    "state=" + rig.brain.state() + " singing=" + singingEyes(rig, 500, 300001) + " sounds="
                            + sounds(rig, 0, 300001) + " motions=" + rig.motions(0, 300001) + " asks=" + asks
                            + " opens=" + rig.count("camera open") + " closes=" + rig.count("camera close")
                            + " docked=" + notesWith(notes, "docked: on the charger") + " " + rig.tail());
        });
        scenario("dock_a_new_thing_appearing_is_remarked_once", n -> {
            Rig rig = dockRig(KETTLE_PICKS, KETTLE_FROM_100S);
            List<String> notes = traced(rig);
            rig.started();
            rig.runUntil(200000);
            int said = rig.firstAfter("say ", 0);
            check(n, rig.countPrefix("say ", 0, 200001) == 1 && rig.timeOf(said) > 100000
                            && rig.timeOf(said) <= 100000 + 60000 + 5000
                            && rig.what(said).startsWith("say Ooh, a kettle") && rig.motions(0, 200001) == 0
                            && rig.count("startle") == 0 && singingEyes(rig, 500, 200001) == 0
                            && notesWith(notes, "docked look: something new") == 1
                            && rig.brain.state() == ExploreBrain.State.DOCKED && rig.violations.isEmpty(),
                    "lines=" + spoken(rig) + " at " + rig.timeOf(said) + " motions=" + rig.motions(0, 200001)
                            + " state=" + rig.brain.state() + " " + rig.tail());
        });
        scenario("dock_the_same_thing_still_there_is_not_remarked_again", n -> {
            Rig rig = dockRig(KETTLE_PICKS, KETTLE_FROM_100S);
            List<String> notes = traced(rig);
            rig.started();
            rig.runUntil(450000);
            long said = rig.timeOf(rig.firstAfter("say ", 0));
            int asksAfter = rig.countPrefix("ask ", said + 1, 450001);
            check(n, rig.countPrefix("say ", 0, 450001) == 1 && asksAfter >= 4
                            && notesWith(notes, "docked look: Claude picked something he reacted to already") == asksAfter
                            && sounds(rig, said + 1, 450001) == 0 && rig.motions(0, 450001) == 0
                            && rig.violations.isEmpty(),
                    "lines=" + spoken(rig) + " asksAfter=" + asksAfter + " already="
                            + notesWith(notes, "docked look: Claude picked something he reacted to already") + " "
                            + rig.tail());
        });
        scenario("dock_detector_only_a_new_thing_says_its_name_once", n -> {
            Rig rig = dockRig((r, req, nth) -> CuriosityPort.Answer.nothing(), KETTLE_FROM_100S);
            rig.askRefused = true;
            List<String> notes = traced(rig);
            rig.started();
            rig.runUntil(400000);
            int named = rig.firstAfter("name ", 0);
            check(n, rig.countPrefix("name ", 0, 400001) == 1 && rig.what(named).equals("name kettle")
                            && rig.timeOf(named) > 100000 && rig.timeOf(named) <= 165000 && rig.asks.isEmpty()
                            && rig.countPrefix("say ", 0, 400001) == 0 && rig.count("startle") == 0
                            && rig.motions(0, 400001) == 0 && notesWith(notes, "docked look: nothing new") >= 3
                            && rig.violations.isEmpty(),
                    "named@" + rig.timeOf(named) + " " + rig.what(named) + " asks=" + rig.asks.size() + " " + rig.tail());
        });
        scenario("dock_a_call_is_answered_and_the_conversation_runs_without_turning", n -> {
            Rig rig = callChatRig(chatFirstTuning(), t -> charger(t), EMPTY_ROOM, hearWords("hi"), hearWords("bye"));
            rig.started();
            rig.runUntil(70000);
            ExploreBrain.State before = rig.brain.state();
            heyMiko(rig, 75000, Ears.Side.LEFT, 800, "anyone home");
            rig.runUntil(75000);
            long open = runUntilState(rig, ExploreBrain.State.CHAT_THINK, 75000, 95000);
            long over = chatOver(rig, open);
            TurnAsk first = firstAsk(rig, 75000);
            rig.runUntil(Math.max(rig.now, over) + 5000);
            check(n, before == ExploreBrain.State.DOCKED && open > 0 && over > 0 && first != null
                            && "anyone home".equals(first.request.heard) && wheelMoves(rig, 0, rig.now + 1) == 0
                            && rig.chatTurns == 0 && rig.count("startle") == 0
                            && rig.brain.state() == ExploreBrain.State.DOCKED && rig.violations.isEmpty(),
                    "before=" + before + " open@" + open + " over@" + over + " first=" + first + " after="
                            + rig.brain.state() + " wheels=" + wheelMoves(rig, 0, rig.now + 1) + " " + rig.tail());
        });
        scenario("dock_driving_onto_the_charger_is_no_startle_and_he_settles", n -> {
            Rig rig = cueRig(cueTuning(), t -> t >= 20000 ? charger(t) : clear(t), EMPTY_ROOM);
            rig.started();
            rig.runUntil(120000);
            check(n, rig.motions(0, 20000) > 0 && rig.countPrefix("startle", 20000, 120001) == 0
                            && rig.motions(20500, 120001) == 0 && singingEyes(rig, 20000, 120001) == 0
                            && rig.brain.state() == ExploreBrain.State.DOCKED && rig.violations.isEmpty(),
                    "before=" + rig.motions(0, 20000) + " startles=" + rig.countPrefix("startle", 20000, 120001)
                            + " after=" + rig.motions(20500, 120001) + " state=" + rig.brain.state() + " " + rig.tail());
        });
        scenario("dock_taken_off_the_charger_he_roams_again", n -> {
            Rig rig = cueRig(cueTuning(), t -> t < 60000 ? charger(t) : clear(t), EMPTY_ROOM);
            List<String> notes = traced(rig);
            rig.started();
            rig.runUntil(59000);
            ExploreBrain.State docked = rig.brain.state();
            rig.runUntil(90000);
            check(n, docked == ExploreBrain.State.DOCKED && rig.brain.state() != ExploreBrain.State.DOCKED
                            && rig.motions(0, 60000) == 0 && rig.motions(60000, 90001) > 0
                            && notesWith(notes, "docked: off the charger") == 1 && rig.violations.isEmpty(),
                    "docked=" + docked + " now=" + rig.brain.state() + " motions after=" + rig.motions(60000, 90001)
                            + " " + rig.tail());
        });
    }

}
