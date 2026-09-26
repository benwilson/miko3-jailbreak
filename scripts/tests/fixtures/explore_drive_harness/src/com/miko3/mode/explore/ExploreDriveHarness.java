package com.miko3.mode.explore;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Host-JVM checks for the explore mode's drive wiring (U5): the stop timer, the
 * lease-gated motor adapter, and the brain loop's threads and exit order.
 * Driven by scripts/tests/test_explore_drive.py.
 *
 * The loop scenarios run the real threads against fakes for a few hundred
 * milliseconds, so they check order and outcome, never exact timing.
 *
 * Prints one "PASS <name>" or "FAIL <name>: <detail>" line per scenario.
 */
public final class ExploreDriveHarness {
    private static void check(String name, boolean ok, String detail) {
        System.out.println(ok ? "PASS " + name : "FAIL " + name + ": " + detail);
    }

    /** Records every wheel command, and every call made by anyone, in order. */
    static final class FakeWheels implements ExploreLoop.Wheels {
        final List<String> calls = Collections.synchronizedList(new ArrayList<String>());
        volatile boolean failNext;
        /** How many upcoming stop() calls throw, to exercise the stop timer's retry. */
        volatile int failStops;

        private void record(String c) throws IOException {
            calls.add(c);
            if (failNext && !c.equals("stop")) {
                failNext = false;
                throw new IOException("injected");
            }
        }

        @Override public void forwardTick() throws IOException { record("forward"); }
        @Override public void turn(ExploreBrain.Direction d) throws IOException { record("turn-" + d); }
        @Override public void backTick() throws IOException { record("back"); }
        @Override public void stop() throws IOException {
            calls.add("stop");
            if (failStops > 0) {
                failStops--;
                throw new IOException("injected stop failure");
            }
        }

        int count(String c) {
            synchronized (calls) {
                int n = 0;
                for (String s : calls) {
                    if (s.equals(c)) {
                        n++;
                    }
                }
                return n;
            }
        }

        int motion() {
            return count("forward") + count("back") + count("turn-LEFT") + count("turn-RIGHT");
        }
    }

    static final class FakeLease implements ExploreLoop.Lease {
        volatile boolean held;
        @Override public boolean held() { return held; }
    }

    /** A fresh, clear reading every call, timestamped now. */
    static final class ClearSensors implements ExploreLoop.Sensors {
        final ExploreBrain.Clock clock;
        ClearSensors(ExploreBrain.Clock clock) { this.clock = clock; }
        @Override public SensorReading latest() {
            return new SensorReading(clock.nowMs(), 250, SensorSnapshotAbsent.ABSENT, 0, null, false);
        }
    }

    /** SensorReading's "absent field" value, without pulling in the shared parser. */
    static final class SensorSnapshotAbsent {
        static final int ABSENT = -1;
    }

    static final class Hooks implements ExploreLoop.Hooks {
        volatile boolean stale;
        volatile boolean frozen;
        volatile boolean spin;
        @Override public boolean staleSensors() { return stale; }
        @Override public boolean freezeBrain() { return frozen; }
        @Override public boolean curiousNow() { return false; }
        @Override public boolean spinInPlace() { return spin; }
    }

    /** Records trace notes, for the spin phases the QA script segments by. */
    static final class Notes implements ExploreBrain.Trace {
        final List<String> notes = Collections.synchronizedList(new ArrayList<String>());
        @Override public void note(String message) { notes.add(message); }
    }

    /** A reading stamped {@code t}, for driving ExploreSpin on a made-up clock. */
    static SensorReading readingAt(long t) {
        return new SensorReading(t, 250, SensorSnapshotAbsent.ABSENT, 0, null, false);
    }

    /** Spin timings short enough for a loop run; null calibration = uncalibrated floor sensors. */
    static ExploreTuning spinTuning(ExploreTuning.Calibration calibration) {
        return new ExploreTuning.Builder().calibration(calibration).spinMs(100, 500).staleMs(300).build();
    }

    static final ExploreBrain.Clock REAL = new ExploreBrain.Clock() {
        @Override public long nowMs() { return System.nanoTime() / 1000000L; }
    };

    static final ExploreBrain.Eyes NO_EYES = new ExploreBrain.Eyes() {
        @Override public void show(ExploreBrain.EyeState state, ExploreBrain.Direction gaze) { }
        @Override public void stare(float x, float y) { }
    };

    static final ExploreBrain.Sound NO_SOUND = new ExploreBrain.Sound() {
        @Override public void playStartle() { }
        @Override public void playReaction(String group) { }
        @Override public void playName(String label) { }
    };

    /** Short pauses so a loop run moves within a few hundred milliseconds. */
    static ExploreTuning quickTuning() {
        return new ExploreTuning.Builder()
                .calibration(new ExploreTuning.Calibration(60, -1, 5, true))
                .pauseMs(20, 40).lookLeadMs(20).turnMs(20, 40)
                .hopTickMs(30).recoveryStreak(2)
                // ClearSensors reports a constant tof; keep the frozen rule out of these short runs.
                .frozenTofWindowMs(60000)
                .build();
    }

    static ExploreLoop loop(FakeWheels wheels, FakeLease lease, Hooks hooks, long deadmanMs) {
        return new ExploreLoop(quickTuning(), REAL, wheels, new ClearSensors(REAL), lease,
                NO_EYES, NO_SOUND, hooks, null, 10, deadmanMs);
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static void writeText(File f, String text) throws IOException {
        FileWriter w = new FileWriter(f);
        try {
            w.write(text);
        } finally {
            w.close();
        }
    }

    public static void main(String[] args) {
        // ---- stop timer ----
        StopTimer t = new StopTimer(600);
        check("stop_timer_idle_until_first_feed", !t.expired(10000), "fired with nothing to stop");
        t.feed(1000);
        boolean before = t.expired(1599);
        boolean at = t.expired(1600);
        boolean again = t.expired(5000);
        check("stop_timer_fires_once_after_silence", !before && at && !again,
                "before=" + before + " at=" + at + " again=" + again);

        StopTimer fed = new StopTimer(600);
        boolean everFired = false;
        for (long now = 0; now < 10000; now += 100) {
            fed.feed(now);
            everFired |= fed.expired(now + 50);
        }
        check("stop_timer_never_fires_while_fed", !everFired, "fired despite regular feeds");

        StopTimer rearm = new StopTimer(600);
        rearm.feed(0);
        rearm.expired(700);
        rearm.feed(800);
        check("stop_timer_rearms_after_a_feed", rearm.expired(1400), "did not fire after re-arming");

        StopTimer retry = new StopTimer(600);
        retry.feed(0);
        boolean rFirst = retry.expired(600);
        retry.rearm();
        boolean rAgain = retry.expired(620);
        check("stop_timer_rearm_fires_again_without_a_feed", rFirst && rAgain, "first=" + rFirst + " again=" + rAgain);

        // ---- lease trust ----
        LeaseTrust trust = new LeaseTrust(1750);
        boolean tBefore = trust.trusted(0);
        trust.renewed(1000);
        boolean tFresh = trust.trusted(2700);
        boolean tStale = trust.trusted(2750);
        trust.renewed(2800);
        boolean tRenewed = trust.trusted(4000);
        trust.lost();
        boolean tAfterLoss = trust.trusted(4000);
        check("lease_trust_expires_before_the_launcher_ttl",
                !tBefore && tFresh && !tStale && tRenewed && !tAfterLoss,
                "before=" + tBefore + " fresh=" + tFresh + " stale=" + tStale + " renewed=" + tRenewed
                        + " afterLoss=" + tAfterLoss);

        // ---- lease-gated motor ----
        FakeWheels w = new FakeWheels();
        FakeLease lease = new FakeLease();
        DriveGate gate = new DriveGate(w, lease, null);
        gate.hopTick();
        gate.turn(ExploreBrain.Direction.LEFT);
        gate.backTick();
        check("gate_drops_motion_without_lease", w.motion() == 0, "sent " + w.calls);
        gate.stop();
        check("gate_stop_goes_out_without_lease", w.count("stop") == 1, "sent " + w.calls);

        lease.held = true;
        gate.hopTick();
        gate.turn(ExploreBrain.Direction.RIGHT);
        gate.backTick();
        check("gate_passes_motion_under_lease",
                w.calls.contains("forward") && w.calls.contains("turn-RIGHT") && w.calls.contains("back"),
                "sent " + w.calls);

        FakeWheels failing = new FakeWheels();
        FakeLease held = new FakeLease();
        held.held = true;
        DriveGate failGate = new DriveGate(failing, held, null);
        failing.failNext = true;
        failGate.hopTick();
        check("gate_write_failure_stops_best_effort",
                failing.calls.size() == 2 && failing.calls.get(1).equals("stop"), "sent " + failing.calls);

        // ---- calibration file ----
        try {
            File dir = java.nio.file.Files.createTempDirectory("explore_cal").toFile();
            File f = new File(dir, ExploreCalibration.FILE_NAME);
            check("calibration_missing_file_is_uncalibrated", ExploreCalibration.read(f) == null, "expected null");

            ExploreCalibration.write(f, new ExploreTuning.Calibration(60, 420, -1, true));
            ExploreTuning.Calibration back = ExploreCalibration.read(f);
            check("calibration_round_trips",
                    back != null && back.obstacleTofBelow == 60 && back.edgeTofAbove == 420
                            && back.edgeIr == -1 && back.edgeIrAbove,
                    "got " + back);

            writeText(f, "obstacleTofBelow=sixty\nedgeTofAbove=420\n");
            check("calibration_corrupt_file_is_uncalibrated", ExploreCalibration.read(f) == null, "expected null");

            writeText(f, "obstacleTofBelow=60\n");
            check("calibration_without_an_edge_rule_is_uncalibrated", ExploreCalibration.read(f) == null,
                    "an obstacle rule alone must not be enough to drive");

            writeText(f, "obstacleTofBelow=60\nedgeTofAbove=420\nedgeIrAbove=maybe\n");
            check("calibration_bad_boolean_is_uncalibrated", ExploreCalibration.read(f) == null, "expected null");

            // ---- gyro keys (explore nav plan U1) ----
            writeText(f, "obstacleTofBelow=60\nedgeTofAbove=420\nedgeIr=-1\nedgeIrAbove=true\n");
            check("calibration_without_gyro_keys_reads_gyro_uncalibrated",
                    ExploreCalibration.read(f) != null && ExploreCalibration.readGyro(f) == null,
                    "floor=" + ExploreCalibration.read(f) + " gyro=" + ExploreCalibration.readGyro(f));

            ExploreCalibration.writeGyro(f, new ExploreCalibration.Gyro(2, -1, 1234.5));
            ExploreCalibration.Gyro g = ExploreCalibration.readGyro(f);
            check("calibration_gyro_round_trips",
                    g != null && g.axis == 2 && g.sign == -1 && g.countSecondsPer360 == 1234.5, "got " + g);

            ExploreTuning.Calibration floorAfterGyro = ExploreCalibration.read(f);
            ExploreCalibration.write(f, new ExploreTuning.Calibration(70, 500, 3, false));
            ExploreCalibration.Gyro gyroAfterFloor = ExploreCalibration.readGyro(f);
            ExploreTuning.Calibration floorBack = ExploreCalibration.read(f);
            check("calibration_writes_keep_each_others_keys",
                    floorAfterGyro != null && floorAfterGyro.obstacleTofBelow == 60 && floorAfterGyro.edgeTofAbove == 420
                            && gyroAfterFloor != null && gyroAfterFloor.axis == 2
                            && gyroAfterFloor.countSecondsPer360 == 1234.5
                            && floorBack != null && floorBack.obstacleTofBelow == 70 && !floorBack.edgeIrAbove,
                    "floor after gyro write=" + floorAfterGyro + " gyro after floor write=" + gyroAfterFloor
                            + " floor=" + floorBack);

            String floor = "obstacleTofBelow=60\nedgeTofAbove=420\n";
            boolean badAll = true;
            for (String bad : new String[] {
                    "gyroAxis=w\ngyroSign=1\ngyroCountSecondsPer360=100\n",
                    "gyroAxis=x\ngyroSign=0\ngyroCountSecondsPer360=100\n",
                    "gyroAxis=x\ngyroSign=1\ngyroCountSecondsPer360=-5\n",
                    "gyroAxis=x\ngyroSign=1\ngyroCountSecondsPer360=NaN\n",
                    "gyroAxis=x\ngyroSign=1\ngyroCountSecondsPer360=lots\n",
                    "gyroAxis=x\ngyroCountSecondsPer360=100\n"}) {
                writeText(f, floor + bad);
                badAll &= ExploreCalibration.readGyro(f) == null && ExploreCalibration.read(f) != null;
            }
            check("calibration_bad_gyro_keys_are_gyro_uncalibrated_but_floor_loads", badAll,
                    "a malformed or partial gyro entry must read as uncalibrated, and not cost the floor rules");
        } catch (IOException e) {
            check("calibration_round_trips", false, e.toString());
        }

        // ---- spin hook (explore nav plan U1): ExploreSpin on a made-up clock ----
        ExploreTuning.Calibration floorCal = new ExploreTuning.Calibration(60, -1, 5, true);
        FakeWheels spw = new FakeWheels();
        FakeLease spl = new FakeLease();
        spl.held = true;
        Notes spn = new Notes();
        ExploreSpin spin = new ExploreSpin(new DriveGate(spw, spl, null), spn, spinTuning(floorCal));
        for (long now = 0; now <= 1300; now += 50) {
            spin.onTick(now, readingAt(now), true);
        }
        spin.end();
        List<String> spinCalls = new ArrayList<String>(spw.calls);
        int firstLeft = spinCalls.indexOf("turn-LEFT");
        int firstRight = spinCalls.indexOf("turn-RIGHT");
        check("spin_turns_left_then_right_with_still_spells",
                firstLeft > 0 && firstRight > firstLeft && spinCalls.get(0).equals("stop")
                        && spinCalls.subList(firstLeft, firstRight).contains("stop")
                        && spw.count("forward") == 0 && spw.count("back") == 0
                        && spinCalls.get(spinCalls.size() - 1).equals("stop")
                        && spn.notes.contains("spin still") && spn.notes.contains("spin LEFT")
                        && spn.notes.contains("spin RIGHT") && spn.notes.contains("spin off")
                        && !spin.active(),
                "calls=" + spinCalls + " notes=" + spn.notes);

        FakeWheels hw = new FakeWheels();
        FakeLease hl = new FakeLease();
        hl.held = true;
        Notes hn = new Notes();
        ExploreSpin halting = new ExploreSpin(new DriveGate(hw, hl, null), hn, spinTuning(floorCal));
        long now = 0;
        for (; now <= 200; now += 50) {
            halting.onTick(now, readingAt(now), true);
        }
        int turnsBeforeStale = hw.motion();
        // Readings stop arriving: the newest one goes stale while he is turning.
        for (; now <= 1000; now += 50) {
            halting.onTick(now, readingAt(150), true);
        }
        int turnsWhileStale = hw.motion() - turnsBeforeStale;
        String lastWhileStale = hw.calls.get(hw.calls.size() - 1);
        for (; now <= 2000; now += 50) {
            hl.held = false;
            halting.onTick(now, readingAt(now), false);
        }
        int turnsWithoutLease = hw.motion() - turnsBeforeStale - turnsWhileStale;
        check("spin_halts_without_lease_or_fresh_readings",
                turnsBeforeStale == 1 && turnsWhileStale == 0 && turnsWithoutLease == 0
                        && lastWhileStale.equals("stop")
                        && hn.notes.contains("spin halted: readings stale")
                        && hn.notes.contains("spin halted: no lease"),
                "before=" + turnsBeforeStale + " stale=" + turnsWhileStale + " noLease=" + turnsWithoutLease
                        + " calls=" + hw.calls + " notes=" + hn.notes);

        FakeWheels uw = new FakeWheels();
        FakeLease ul = new FakeLease();
        ul.held = true;
        Notes un = new Notes();
        ExploreSpin uncalibrated = new ExploreSpin(new DriveGate(uw, ul, null), un, spinTuning(null));
        for (long u = 0; u <= 2000; u += 50) {
            uncalibrated.onTick(u, readingAt(u), true);
        }
        check("spin_needs_calibrated_floor_sensors",
                uw.motion() == 0 && un.notes.contains("spin halted: sensors uncalibrated"),
                "calls=" + uw.calls + " notes=" + un.notes);

        // ---- reading carries the gyro ----
        SensorReading withGyro = new SensorReading(5, 250, -1, 0, null, false, 10, 20, 62, -757, 93);
        SensorReading noGyro = new SensorReading(5, 250, -1, 0, null, false, 10, 20);
        check("reading_carries_the_gyro",
                withGyro.hasGyro && withGyro.gyroX == 62 && withGyro.gyroY == -757 && withGyro.gyroZ == 93
                        && withGyro.wheelLeft == 10 && !noGyro.hasGyro,
                "with=" + withGyro + " without=" + noGyro);

        // ---- loop ----
        FakeWheels lw = new FakeWheels();
        FakeLease ll = new FakeLease();
        Hooks hooks = new Hooks();
        ExploreLoop noLease = loop(lw, ll, hooks, 600);
        noLease.start();
        sleep(400);
        int motionWithoutLease = lw.motion();
        ll.held = true;
        sleep(600);
        int motionWithLease = lw.motion();
        noLease.stop();
        check("loop_moves_only_once_the_lease_is_held", motionWithoutLease == 0 && motionWithLease > 0,
                "without=" + motionWithoutLease + " with=" + motionWithLease);

        FakeWheels sw = new FakeWheels();
        FakeLease sl = new FakeLease();
        sl.held = true;
        Hooks staleHooks = new Hooks();
        staleHooks.stale = true;
        ExploreLoop stale = loop(sw, sl, staleHooks, 600);
        stale.start();
        sleep(500);
        stale.stop();
        check("loop_stale_hook_keeps_the_robot_still", sw.motion() == 0, "moved: " + sw.calls);

        FakeWheels ew = new FakeWheels();
        FakeLease el = new FakeLease();
        el.held = true;
        ExploreLoop exiting = loop(ew, el, new Hooks(), 600);
        exiting.start();
        sleep(300);
        exiting.stop();
        int callsAtStop = ew.calls.size();
        String last = callsAtStop == 0 ? "" : ew.calls.get(callsAtStop - 1);
        sleep(200);
        check("loop_exit_ends_in_stop_and_goes_quiet",
                last.equals("stop") && ew.calls.size() == callsAtStop && !exiting.isRunning(),
                "last=" + last + " after=" + ew.calls.subList(callsAtStop, ew.calls.size()));

        FakeWheels fw = new FakeWheels();
        FakeLease fl = new FakeLease();
        fl.held = true;
        Hooks freeze = new Hooks();
        ExploreLoop frozen = loop(fw, fl, freeze, 200);
        frozen.start();
        sleep(300);
        int stopsBefore = fw.count("stop");
        freeze.frozen = true;
        sleep(500);
        int stopsAfter = fw.count("stop");
        freeze.frozen = false;
        frozen.stop();
        check("loop_stop_timer_stops_a_frozen_brain", stopsAfter > stopsBefore,
                "stops before=" + stopsBefore + " after=" + stopsAfter);

        FakeWheels rw = new FakeWheels();
        FakeLease rl = new FakeLease();
        rl.held = true;
        Hooks rfreeze = new Hooks();
        ExploreLoop retrying = loop(rw, rl, rfreeze, 200);
        retrying.start();
        sleep(300);
        rw.failStops = 2;
        int stopsAtFreeze = rw.count("stop");
        rfreeze.frozen = true;
        sleep(700);
        int attempts = rw.count("stop") - stopsAtFreeze;
        boolean succeeded = rw.failStops == 0;
        rfreeze.frozen = false;
        retrying.stop();
        check("loop_stop_timer_retries_a_failed_stop", attempts >= 3 && succeeded,
                "stop attempts after freeze=" + attempts + " failuresLeft=" + rw.failStops);

        FakeWheels pw = new FakeWheels();
        FakeLease pl = new FakeLease();
        pl.held = true;
        Hooks spinHooks = new Hooks();
        spinHooks.spin = true;
        ExploreLoop spinning = new ExploreLoop(spinTuning(new ExploreTuning.Calibration(60, -1, 5, true)), REAL, pw,
                new ClearSensors(REAL), pl, NO_EYES, NO_SOUND, spinHooks, null, 10, 600);
        spinning.start();
        sleep(400);
        int spinForward = pw.count("forward") + pw.count("back");
        int spinLefts = pw.count("turn-LEFT");
        int atSpinOff = pw.calls.size();
        spinHooks.spin = false;
        sleep(100);
        spinning.stop();
        String afterSpinOff = pw.calls.size() > atSpinOff ? pw.calls.get(atSpinOff) : "";
        check("loop_spin_hook_turns_in_place_and_ends_in_stop",
                spinForward == 0 && spinLefts >= 1 && afterSpinOff.equals("stop"),
                "forward/back=" + spinForward + " lefts=" + spinLefts + " first after off=" + afterSpinOff
                        + " calls=" + pw.calls);
    }
}
