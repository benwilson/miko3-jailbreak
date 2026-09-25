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
                // (U10) is off here and switched on by the coverage scenarios.
                .coverageOff()
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
                    true, gyro.axis == 0 ? raw : 0, gyro.axis == 1 ? raw : 0, gyro.axis == 2 ? raw : 0);
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

    /** What the fake launcher and Claude answer in the meet flow; a null answer never comes. */
    static final class People {
        MatchScript match = (rig, nth) -> CuriosityPort.MatchAnswer.FAILED;
        CuriosityPort.MatchAnswer lines = CuriosityPort.MatchAnswer.FAILED;
        CuriosityPort.Heard heard = CuriosityPort.Heard.NOTHING;
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
    }

    static final class Rig implements ExploreBrain.Clock, ExploreBrain.Motor, ExploreBrain.Eyes, ExploreBrain.Sound,
            ExploreBrain.Camera, CuriosityPort {
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
        /** The fake recently-met check (explore nav plan U7): null script means every check fails. */
        MetScript metChecks;
        long metCheckDelayMs = 1000;
        final List<MetCheck> metCheckLog = new ArrayList<MetCheck>();
        CuriosityPort.Recently pendingMet;
        long pendingMetAt;
        int metCancels;
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
            this.brain = claude == null
                    ? new ExploreBrain(tuning, this, this, this, this, this, new Random(rigSeed))
                    : new ExploreBrain(tuning, this, this, this, this, this, this, new Random(rigSeed));
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
                wheelLeft--;
                wheelRight++;
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
                if (cameraOpen && vision != null && now % 500 == 0 && now >= looksFrom) {
                    List<Detection> seen = vision.see(this, now - 200);
                    if (seen != null) {
                        if (yaw != null) {
                            lookHeadings.put(now - 200, yaw.wrapped());
                        }
                        latestLook = new ExploreBrain.Look(staleLooks ? openedAt - 1000 : now - 200, seen,
                                ("jpeg@" + (now - 200)).getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                                openView == null ? null : openView.at(this, now - 200));
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
                }
                if (cameraOpen) {
                    openStates.add(brain.state());
                }
            }
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
            return claude != null;
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
            // Speech starts only once the camera and detector are closed (R6, KTD6).
            if (cameraOpen) {
                violations.add(now + ":say with the camera open in " + brain.state());
            }
            if (!quiet() && !sayWhileBusyAllowed) {
                violations.add(now + ":say while a detector run is in flight in " + brain.state());
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
            listenMaxMs = maxMs;
            pendingHeard = people.heard;
            pendingHeardAt = now + people.replyMs;
            log.add(new Event(now, "listen"));
        }

        @Override
        public CuriosityPort.Heard heard() {
            if (pendingHeard == null || now < pendingHeardAt) {
                return null;
            }
            CuriosityPort.Heard h = pendingHeard;
            pendingHeard = null;
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
        switch (s) {
            case MEET: case SPEAK: case ASK_NAME: case LISTEN: case NAME: case REMEMBER: case NAME_CLIP:
            case ASK: case ORIENT: case EYES_ONLY: case CORNERED: case STOPPED:
                return open ? "camera open in " + s : null;
            default:
                break;
        }
        if (open && !available) {
            return "camera open without a camera in " + s;
        }
        if (open && backedOff) {
            return "camera open during its back-off in " + s;
        }
        if (s.curious()) {
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
        escapeScenarios();
        pinnedScenarios();
        budgetScenarios();
        forwardFirstScenarios();
        sideScenarios();
        backUpFirstScenarios();
        doorwayScenarios();
        peopleScenarios();
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
            Rig rig = new Rig(tuning().build(), t -> t < 1600 ? clear(t) : cpl2(t)).started();
            rig.runUntil(30000);
            int stop = rig.firstAfter("stop", 1600);
            check(n, rig.timeOf(stop) == 1600 && rig.countPrefix("hop", 1600, 30001) == 0
                            && rig.count("startle") >= 1 && rig.violations.isEmpty(),
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
            Rig rig = new Rig(tuning().hopTicks(20).build(), t -> wheels(t, Math.min(t, 2000))).started();
            rig.runUntil(5500);
            int stop = rig.firstAfter("stop", 1301);
            check(n, rig.timeOf(stop) >= 2900 && rig.timeOf(stop) <= 3100 && rig.count("startle") == 1
                            && rig.count("back") == 3 && rig.firstAfter("turn", rig.timeOf(stop)) >= 0,
                    "stop@" + rig.timeOf(stop) + " " + rig.tail());
        });
        scenario("repeated_stalls_back_off_further_and_turn_more_each_time", n -> {
            // The wheels never turn: every leg stalls. Each stall backs off 3 ticks and
            // turns longer than the last (2 s, 3 s, 4 s here), one way, one "whoa".
            Rig rig = new Rig(tuning().hopTicks(20).stallEscape(3, 2000, 1000).cap(8, 20000, 30000)
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
            Rig rig = new Rig(curious().build(), CLEAR, dark, true).started();
            rig.runUntil(12000);
            int close = rig.first("camera close", 0);
            // Opened at 1300; no look by 1300 + 3000; back to wandering, and no retry within the back-off.
            check(n, rig.timeOf(close) == 4300 && rig.count("camera open") == 1 && rig.countPrefix("hop", 4300, 12001) >= 3
                            && rig.violations.isEmpty(),
                    rig.tail());
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
        scenario("tuning_defaults_roam_45_to_90_s_and_two_10_s_claude_tries", n -> {
            ExploreTuning t = new ExploreTuning.Builder().calibration(calibration()).build();
            Rig rig = new Rig(curious().scan(3, 500).sayTimeoutMs(6000).build(), CLEAR, (r, tt) -> list(), true,
                    (r, req, nth) -> null).started();
            rig.runUntil(9000);
            check(n, t.curiosityMinMs == 45000 && t.curiosityMaxMs == 90000 && t.askAttempts == 2
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
            // Live: the same potted plant twice in a row.
            Rig rig = new Rig(claudeTuning().build(), CLEAR, (r, t) -> list(), true,
                    (r, req, nth) -> nth == 1
                            ? pick(2, "potted plant", CuriosityPort.Kind.OTHER, "Look at that plant!",
                                    0.5f, 0.5f, 0.2f, 0.3f)
                            : pick(1, "plant", CuriosityPort.Kind.OTHER, "A plant again!", 0.5f, 0.5f, 0.2f, 0.3f))
                    .started();
            rig.runUntil(20000);
            int second = rig.first("answer PICK", rig.first("answer PICK", 0) + 1);
            check(n, rig.count("say Look at that plant!") == 1 && rig.countPrefix("say A plant again!", 0, 20001) == 0
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
            check(n, p.contains("potted plant") && p.contains("Do not pick anything on this list"), p);
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
                            default: return pick(0, "lamp", CuriosityPort.Kind.OTHER, "The lamp again!",
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
            rig.people.heard = new CuriosityPort.Heard(CuriosityPort.Heard.Status.WORDS, "my name is Sarah");
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
            rig.people.heard = CuriosityPort.Heard.NOTHING;
            rig.started();
            rig.runUntil(14000);
            int listen = rig.first("listen", 0);
            int say = rig.first("say No worries, shy friend!", listen);
            check(n, listen >= 0 && say > listen && rig.stored.isEmpty() && rig.count("find name") == 0
                            && resumedBy(rig, rig.timeOf(say), rig.timeOf(say) + 1500)
                            && rig.violations.isEmpty(),
                    "say=" + say + " stored=" + rig.stored + " " + rig.tail());
        });
        scenario("meet_ae5_reply_without_a_name_is_stored_unnamed", n -> {
            Rig rig = meetRig();
            rig.people.match = (r, k) -> STRANGER;
            rig.people.heard = new CuriosityPort.Heard(CuriosityPort.Heard.Status.WORDS, "hmm what");
            rig.people.remembered = CuriosityPort.Answer.line("Nice to meet you! I'll remember that smile!");
            rig.started();
            rig.runUntil(16000);
            int remember = rig.first("remember", 0);
            int say = rig.first("say Nice to meet you!", remember);
            check(n, rig.stored.equals(java.util.Arrays.asList("(unnamed)")) && say > remember
                            && rig.violations.isEmpty(),
                    "stored=" + rig.stored + " " + rig.tail());
        });
        scenario("meet_reply_without_a_pattern_waits_for_claude_to_find_the_name", n -> {
            Rig rig = meetRig();
            rig.people.match = (r, k) -> STRANGER;
            rig.people.heard = new CuriosityPort.Heard(CuriosityPort.Heard.Status.WORDS, "oh hi it is priya");
            rig.people.name = t -> CuriosityPort.Named.of("Priya");
            rig.people.nameAsksClaude = true;
            rig.started();
            rig.runUntil(16000);
            int heard = rig.first("heard WORDS", 0);
            int find = rig.first("find name", heard);
            int remember = rig.first("remember Priya", find);
            check(n, find > heard && rig.timeOf(remember) == rig.timeOf(find) + 1000
                            && rig.stored.equals(java.util.Arrays.asList("Priya")) && rig.violations.isEmpty(),
                    "find=" + find + " remember=" + remember + " " + rig.tail());
        });
        scenario("meet_unsure_match_runs_the_new_person_flow", n -> {
            java.util.Map<String, Object> json = new java.util.LinkedHashMap<String, Object>();
            json.put("match", "unsure");
            json.put("named_line", "Hi {name}!");
            json.put("unnamed_line", "Hi again!");
            json.put("ask_line", "Hello! What's your name?");
            json.put("no_reply_line", "No worries, shy friend!");
            ClaudeReplies.Match m = ClaudeReplies.match(json, 3);
            Rig rig = meetRig();
            rig.people.match = (r, k) -> m.reference < 0
                    ? CuriosityPort.MatchAnswer.stranger(m.askLine, m.noReplyLine)
                    : CuriosityPort.MatchAnswer.known("X", m.namedLine, m.unnamedLine);
            rig.started();
            rig.runUntil(12000);
            check(n, m.reference == -1 && rig.count("say Hello! What's your name?") == 1
                            && rig.count("listen") == 1 && rig.touches == 0 && rig.violations.isEmpty(),
                    "ref=" + m.reference + " " + rig.tail());
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
        scenario("meet_known_person_without_a_name_is_greeted_with_the_unnamed_line", n -> {
            Rig rig = meetRig();
            rig.people.match = (r, k) -> CuriosityPort.MatchAnswer.known(null, "Hi {name}!", "Hey, I remember you!");
            rig.started();
            rig.runUntil(12000);
            check(n, rig.count("say Hey, I remember you!") == 1 && rig.touches == 1 && rig.count("listen") == 0
                            && rig.violations.isEmpty(),
                    rig.tail());
        });
        scenario("meet_match_reference_beyond_the_gallery_is_a_new_person", n -> {
            java.util.Map<String, Object> json = new java.util.LinkedHashMap<String, Object>();
            json.put("match", "4");
            json.put("named_line", "Hi {name}!");
            json.put("unnamed_line", "Hi again!");
            json.put("ask_line", "Hello! What's your name?");
            json.put("no_reply_line", "No worries!");
            ClaudeReplies.Match beyond = ClaudeReplies.match(json, 3);
            json.put("match", 2L);
            ClaudeReplies.Match second = ClaudeReplies.match(json, 3);
            json.put("match", "0");
            ClaudeReplies.Match zero = ClaudeReplies.match(json, 3);
            json.put("match", "none");
            json.remove("ask_line");
            ClaudeReplies.Match noAsk = ClaudeReplies.match(json, 3);
            check(n, beyond.reference == -1 && beyond.askLine != null && second.reference == 1
                            && zero.reference == -1 && noAsk == null,
                    "beyond=" + beyond.reference + " second=" + second.reference + " zero=" + zero.reference
                            + " noAsk=" + noAsk);
        });
        scenario("meet_listen_failure_resumes_within_the_budget", n -> {
            Rig rig = meetRig();
            rig.people.match = (r, k) -> STRANGER;
            rig.people.heard = new CuriosityPort.Heard(CuriosityPort.Heard.Status.FAILED, null);
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
            rig.people.heard = null;
            rig.started();
            rig.runUntil(20000);
            int listen = rig.first("listen", 0);
            // 6000 ms of listening plus the 2000 ms margin.
            check(n, listen >= 0 && resumedBy(rig, rig.timeOf(listen), rig.timeOf(listen) + 8000)
                            && !resumedBy(rig, rig.timeOf(listen), rig.timeOf(listen) + 7990)
                            && rig.stored.isEmpty() && rig.violations.isEmpty(),
                    rig.tail());
        });
        scenario("meet_remember_failure_resumes_within_the_budget", n -> {
            Rig rig = meetRig();
            rig.people.match = (r, k) -> STRANGER;
            rig.people.heard = new CuriosityPort.Heard(CuriosityPort.Heard.Status.WORDS, "i'm Sam");
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
            rig.people.heard = new CuriosityPort.Heard(CuriosityPort.Heard.Status.WORDS, "my name is Sarah");
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
            Rig rig = meetRig();
            rig.people.match = (r, k) -> STRANGER;
            rig.people.heard = new CuriosityPort.Heard(CuriosityPort.Heard.Status.WORDS, "i'm Sam");
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
            rig.people.heard = new CuriosityPort.Heard(CuriosityPort.Heard.Status.WORDS, "my name is Sam");
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
            rig.people.heard = new CuriosityPort.Heard(CuriosityPort.Heard.Status.WORDS, "i'm Sam");
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
                .cap(8, 20000, 30000);
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
            Rig named = meetRig();
            named.people.match = (r, k) -> STRANGER;
            named.people.heard = new CuriosityPort.Heard(CuriosityPort.Heard.Status.WORDS, "i'm Sam");
            named.started();
            named.runUntil(16000);
            Rig clip = meetRig();
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
        r.blockedFrom = on ? Math.min(r.blockedFrom, r.now) : Long.MAX_VALUE;
    }

    /** A pinned rig whose Claude always points straight ahead in the first frame (no turn needed). */
    private static Rig pinnedRig(List<String> notes) {
        Rig[] h = new Rig[1];
        Rig rig = escRig(escTuning().turnChance(1.0), h, CLEAR, (r, req, nth) -> CuriosityPort.WayOut.way(0, 0f));
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
            Rig rig = pinnedRig(notes);
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
            Rig rig = pinnedRig(notes);
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
            Rig rig = pinnedRig(notes);
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
            Rig[] h = new Rig[1];
            Feed b = bumps(h, 3);
            Rig rig = escRig(escTuning().escapeRetrace(0, 10), h, t -> {
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
            Rig rig = pinnedRig(notes);
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
            Rig rig = pinnedRig(notes);
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
            Rig rig2 = escRig(escTuning().turnChance(1.0), h, t -> {
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
            Rig rig = escRig(escTuning(), h, bumps(h, 3), null);
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
                            + rig.tail());
        });
        scenario("side_both_blocked_alternates_instead_of_one_side_for_ever", n -> {
            // Every turn blocked: the retries and ladders go back and forth, not LEFT every time.
            List<String> notes = new ArrayList<String>();
            Rig rig = pinnedRig(notes);
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
            runUntil(rig, 60000, r -> notedAt(notes, "free after") >= 0 && !r.moving);
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
            Rig rig = escRig(escTuning(), h, bumps(h, 3), null);
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
            Rig[] h = new Rig[1];
            Feed b = bumps(h, 3);
            Rig rig = escRig(escTuning().escapeRetrace(0, 10), h, t -> {
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
            runUntil(rig, 30000, r -> r.brain.state() == ExploreBrain.State.CORNERED
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
        scenario("blocked_side_escape_turns_go_the_unblocked_way_even_the_long_way_round", n -> {
            // Bumped into a wedge; the first turn's way is blocked for good, and nothing behind
            // him gives (reversing goes nowhere), so the ladder's turns must go the other way:
            // the retrace's, the circle's and the drive-off's. Every turn after the first does.
            Rig[] h = new Rig[1];
            Rig rig = escRig(escTuning(), h, bumps(h, 3), null);
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
            // escape turn will not turn; a short back-up (the second back move) frees it.
            Rig rig = new Rig(escTuning().build(), t -> t >= 1600 && t < 1800 ? cpl2(t) : clear(t), NOTHING, true);
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
            Rig rig = new Rig(escTuning().turnChance(1.0).blockedTurnBackTicks(12).build(), CLEAR, NOTHING, true);
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
            meet.people.heard = new CuriosityPort.Heard(CuriosityPort.Heard.Status.WORDS, "i'm Sam");
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
        scenario("doorway_closed_since_reads_blocked_when_faced_and_is_dropped", n -> {
            // Answered straight ahead; by the next decision the way ahead reads blocked.
            Rig rig = doorRig(doorTuning(), CLEAR,
                    (r, t) -> r.doorwayAnswerStates.isEmpty() ? prof(0.9f, 0.9f, 0.9f, 0.9f)
                            : prof(0.9f, 0.9f, 0.1f, 0.9f),
                    (r, k) -> CuriosityPort.Doorway.door(0f));
            List<String> notes = traced(rig);
            rig.started();
            rig.runUntil(6000);
            long set = notedAt(notes, "open doorway");
            long dropped = notedAt(notes, "reads blocked");
            check(n, set > 0 && dropped > set && dropped <= set + 3500 && Double.isNaN(rig.brain.doorwayHeading())
                            && notedAt(notes, "expired") < 0 && rig.violations.isEmpty(),
                    "set@" + set + " dropped@" + dropped + " " + notes);
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
            RoamSteer steer = new RoamSteer(doorTuning().build());
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
                            && right.side == RoamSteer.RIGHT && Math.abs(right.bendDeg - 18.75) < 0.01 && right.towardDoorway
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
            Rig rig = peopleRig(peopleTuning(), personWhen(t -> t < 15000));
            rig.people.match = (r, k) -> STRANGER;
            rig.people.heard = new CuriosityPort.Heard(CuriosityPort.Heard.Status.WORDS, "my name is Priya");
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
            rig.people.heard = new CuriosityPort.Heard(CuriosityPort.Heard.Status.WORDS, "my name is Priya");
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
}
