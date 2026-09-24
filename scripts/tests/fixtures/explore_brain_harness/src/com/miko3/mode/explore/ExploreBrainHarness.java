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

    /**
     * The scripted Claude (explore on Claude U4), shaped like Vision: the answer to
     * the rig's nth ask() (1-based, counted over the whole run), or null for a
     * request that never answers (the brain's own deadline ends it).
     */
    interface Claude {
        CuriosityPort.Answer answer(Rig rig, CuriosityPort.LookRequest request, int nth);
    }

    interface MatchScript {
        CuriosityPort.MatchAnswer answer(Rig rig, int nth);
    }

    interface NameScript {
        CuriosityPort.Named find(String transcript);
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

        /** The fake camera: a look every 500 ms while open, captured 200 ms before it arrives. */
        final Vision vision;
        final boolean cameraAvailable;
        boolean cameraOpen;
        long openedAt;
        ExploreBrain.Look latestLook;
        /** Every look was captured before the camera opened: arriving, never new enough. */
        boolean staleLooks;
        /** Like ExploreCamera: after a close, the camera yields nothing until this gap has passed. */
        long reopenGapMs;
        long closedAt = Long.MIN_VALUE / 4;
        long looksFrom;

        /** The fake Claude and speech: null claude means the port can't ask (today's behavior). */
        final Claude claude;
        long claudeDelayMs = 1000;
        final List<CuriosityPort.LookRequest> asks = new ArrayList<CuriosityPort.LookRequest>();
        final List<Long> askTimeouts = new ArrayList<Long>();
        CuriosityPort.Answer pending;
        long pendingAt;
        long speechMs = 1500;
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
        /** Every brain state seen, and every state seen with the camera open. */
        final java.util.Set<ExploreBrain.State> statesSeen = new java.util.TreeSet<ExploreBrain.State>();
        final java.util.Set<ExploreBrain.State> openStates = new java.util.TreeSet<ExploreBrain.State>();

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
                    ? new ExploreBrain(tuning, this, this, this, this, this, new Random(1))
                    : new ExploreBrain(tuning, this, this, this, this, this, this, new Random(1));
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
                for (Action a : actions) {
                    if (a.at == now) {
                        a.run.run();
                    }
                }
                if (cameraOpen && vision != null && now % 500 == 0 && now >= looksFrom) {
                    List<Detection> seen = vision.see(this, now - 200);
                    if (seen != null) {
                        latestLook = new ExploreBrain.Look(staleLooks ? openedAt - 1000 : now - 200, seen,
                                ("jpeg@" + (now - 200)).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    }
                }
                if (now % 100 == 0) {
                    SensorReading r = feed.at(now);
                    if (r != null) {
                        lastFed = r;
                        brain.onReading(r);
                        statesSeen.add(brain.state());
                    }
                }
                brain.onTick();
                if (cameraOpen != brain.state().curious()) {
                    violations.add(now + ":camera " + (cameraOpen ? "open" : "closed") + " in " + brain.state());
                }
                statesSeen.add(brain.state());
                if (cameraOpen) {
                    openStates.add(brain.state());
                }
            }
        }

        @Override
        public boolean available() {
            return cameraAvailable;
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
                if (hopTicksThisHop > 0) {
                    legLengths.add(hopTicksThisHop);
                    minHopTicks = Math.min(minHopTicks, hopTicksThisHop);
                }
                hopTicksThisHop = 0;
            }
            moving = true;
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
            log.add(new Event(now, "turn " + d));
        }

        @Override
        public void backTick() {
            moving = true;
            log.add(new Event(now, "back"));
        }

        @Override
        public void stop() {
            moving = false;
            log.add(new Event(now, "stop"));
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

        @Override
        public void touch() {
            touches++;
            log.add(new Event(now, "touch"));
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
            int close = rig.first("camera close", name);
            int open2 = rig.first("camera open", close);
            int sad = rig.first("react disappointed", open2);
            int close2 = rig.first("camera close", sad);
            boolean ordered = open >= 0 && stare > open && turn > stare && hop > turn && curiousAt > hop
                    && thinking > curiousAt && name > thinking && close > name
                    && open2 > close && sad > open2 && close2 > sad;
            // Eyes lead the turn toward it (R5), and no motion toward it the second time (R10).
            boolean led = rig.timeOf(turn) - rig.timeOf(stare) >= 500;
            boolean stayed = ordered && rig.motions(rig.timeOf(open2), rig.timeOf(close2)) == 0;
            check(n, ordered && led && stayed && rig.brain.seen().contains("plant")
                            && rig.count("name plant") == 1 && rig.count("startle") == 0 && rig.violations.isEmpty(),
                    "open=" + open + " stare=" + stare + " turn=" + turn + " hop=" + hop + " curious=" + curiousAt
                            + " name=" + name + " open2=" + open2 + " sad=" + sad + " " + rig.tail());
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
                            && rig.firstAfter("camera close", 2100) >= 0
                            && rig.timeOf(rig.firstAfter("camera close", 2100)) == 2100 && rig.violations.isEmpty(),
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
        scenario("ae6_camera_open_only_while_curious", n -> {
            Rig rig = new Rig(curious().build(), CLEAR, PLANT, true).started();
            rig.at(12000, () -> rig.brain.onLeaseChanged(false));
            rig.runUntil(30000);
            // The rig records a violation whenever the camera's state and the brain's disagree.
            check(n, rig.count("camera open") >= 2 && rig.count("camera open") == rig.count("camera close")
                            && rig.violations.isEmpty(),
                    rig.tail());
        });
        scenario("target_lost_during_approach_gives_up", n -> {
            Vision fleeting = (rig, t) -> rig.count("hop") == 0 ? list(box("cat", 0.8f, 0.5f, 0.6f, 0.2f, 0.3f)) : list();
            Rig rig = new Rig(curious().build(), CLEAR, fleeting, true).started();
            rig.runUntil(8000);
            int hop = rig.first("hop", 0);
            int close = rig.first("camera close", hop);
            check(n, hop >= 0 && close > hop && rig.countPrefix("react", 0, 8001) == 0
                            && rig.countPrefix("hop", rig.timeOf(hop), rig.timeOf(close)) == 2 && rig.violations.isEmpty(),
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
            int open = rig.first("camera open", 0);
            int puzzled = rig.first("react puzzled", open);
            int close = rig.first("camera close", puzzled);
            check(n, puzzled > open && close > puzzled && rig.motions(rig.timeOf(open), rig.timeOf(close)) == 0
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
            int close = rig.first("camera close", 0);
            check(n, rig.timeOf(close) == 4300 && rig.count("camera open") >= 2 && rig.violations.isEmpty(),
                    "close@" + rig.timeOf(close) + " " + rig.tail());
        });
        scenario("curiosity_requested_now_starts_at_the_next_pause_end", n -> {
            Rig rig = new Rig(curious().curiosityMs(100000, 100000).build(), CLEAR, PLANT, true).started();
            rig.at(1200, () -> rig.brain.requestCuriosity());
            rig.runUntil(1400);
            check(n, rig.timeOf(rig.first("camera open", 0)) == 1300 && rig.count("hop") == 0,
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
                            && rig.countPrefix("camera open", rig.timeOf(answer), rig.timeOf(answer) + 1000) == 0
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
            java.util.Set<ExploreBrain.State> allowed = java.util.EnumSet.of(
                    ExploreBrain.State.SCAN, ExploreBrain.State.FACE, ExploreBrain.State.APPROACH);
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
            check(n, remember >= 0 && resumedBy(rig, rig.timeOf(remember), rig.timeOf(remember) + 4000)
                            && rig.countPrefix("say", rig.timeOf(remember), 22001) == 0
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
            rig.started();
            rig.runUntil(40000);
            boolean clean = rig.meets.size() >= 2;
            for (String m : rig.meets) {
                clean &= m.startsWith("jpeg@") && m.endsWith(" person") && !m.contains("Sarah");
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
            java.util.Set<ExploreBrain.State> allowed = java.util.EnumSet.of(
                    ExploreBrain.State.SCAN, ExploreBrain.State.FACE, ExploreBrain.State.APPROACH);
            check(n, eyes.equals("eyes THINKING") && allowed.containsAll(rig.openStates)
                            && rig.countPrefix("camera open", rig.timeOf(match), rig.timeOf(match) + 8000) == 0
                            && rig.violations.isEmpty(),
                    "open in " + rig.openStates + " " + rig.tail());
        });
    }

    // ---- FaceCrop geometry (explore on Claude plan KTD5) ----

    private static void faceCropScenarios() {
        scenario("face_crop_expands_the_face_hit_1_6x", n -> {
            // Eyes 40 px apart at (320, 200) in a 640x480 frame: a 100 px face, 160 px after 1.6x, 10 px lower.
            int[] sq = FaceCrop.Square.aroundFace(320f, 200f, 40f, 640, 480);
            int[] edge = FaceCrop.Square.aroundFace(10f, 10f, 40f, 640, 480);
            check(n, sq[2] == 160 && sq[0] == 240 && sq[1] == 130 && edge[0] == 0 && edge[1] == 0 && edge[2] == 160,
                    java.util.Arrays.toString(sq) + " " + java.util.Arrays.toString(edge));
        });
        scenario("face_crop_without_a_face_uses_the_top_quarter_of_the_person", n -> {
            // A person box from (0.25, 0.1) to (0.75, 0.9): 384 px tall, so a 96 px square at its top centre.
            int[] sq = FaceCrop.Square.topOfPerson(new Detection("person", 1f, 0.25f, 0.1f, 0.75f, 0.9f), 640, 480);
            check(n, sq[2] == 96 && sq[0] == 272 && sq[1] == 48, java.util.Arrays.toString(sq));
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
}
