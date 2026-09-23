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
                .lookLeadMs(500)
                .startleMs(400)
                .staleMs(300)
                .frozenTofWindowMs(3000)
                .recoveryStreak(3)
                .cap(3, 20000, 30000)
                .ir1IsLeft(true)
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
    static final class Rig implements ExploreBrain.Clock, ExploreBrain.Motor, ExploreBrain.Eyes, ExploreBrain.Sound {
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

        Rig(ExploreTuning tuning, Feed feed) {
            this.tuning = tuning;
            this.feed = feed;
            this.brain = new ExploreBrain(tuning, this, this, this, this, new Random(1));
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
                if (now % 100 == 0) {
                    SensorReading r = feed.at(now);
                    if (r != null) {
                        lastFed = r;
                        brain.onReading(r);
                    }
                }
                brain.onTick();
            }
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
    }

    // ---- ExploreBrain: acceptance examples ----
    //
    // With the harness tuning and clear readings from t=100 the classifier is
    // available at 300, the first pause runs to 1300, and the first hop starts on
    // the 1300 reading with ticks at 1300, 1550 and 1800 and a stop at 2050.

    private static void acceptanceScenarios() {
        scenario("ae1_edge_mid_hop_startles_backs_off_and_turns_away", n -> {
            Rig rig = new Rig(tuning().build(), t -> t < 1600 ? clear(t) : edgeAhead(t)).started();
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
        scenario("cornered_cap_rests_without_motion_then_turns_wider", n -> {
            // Edge dead ahead from 1600 forever: hazard 1 mid-hop at 1600, then refusals
            // at the ends of the next two pauses (4600, 6900) trip the cap at 6900.
            Rig rig = new Rig(tuning().build(), t -> t < 1600 ? clear(t) : edgeAhead(t)).started();
            rig.runUntil(7000);
            ExploreBrain.State resting = rig.brain.state();
            int rest = rig.first("eyes RESTING", 0);
            rig.runUntil(40000);
            int wider = rig.firstAfter("turn", 36900);
            int widerStop = rig.first("stop", wider);
            long widerMs = rig.timeOf(widerStop) - rig.timeOf(wider);
            check(n, resting == ExploreBrain.State.CORNERED && rig.timeOf(rest) == 6900
                            && rig.motions(6901, 36900) == 0 && rig.count("back") == 1
                            && wider >= 0 && widerMs >= 2400 && widerMs < 2500,
                    "state=" + resting + " rest@" + rig.timeOf(rest) + " motionsDuringRest="
                            + rig.motions(6901, 36900) + " widerMs=" + widerMs + " " + rig.tail());
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
}
