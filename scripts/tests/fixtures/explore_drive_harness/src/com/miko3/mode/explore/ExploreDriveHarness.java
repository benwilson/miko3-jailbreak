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
        @Override public void disableTofCheck() throws IOException { calls.add("tofds"); }
        @Override public void enableTofCheck() throws IOException { calls.add("tofen"); }
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
        volatile boolean probe;
        @Override public boolean fwdProbe() { return probe; }
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

        // The fwd probe (dark floor, 2026-10-02): TOFDS as it starts, forward legs whatever
        // the floor reads, and TOFEN when the hook goes off.
        FakeWheels pqw = new FakeWheels();
        FakeLease pql = new FakeLease();
        pql.held = true;
        Hooks probeHooks = new Hooks();
        probeHooks.probe = true;
        ExploreLoop probing = new ExploreLoop(spinTuning(null), REAL, pqw, new ClearSensors(REAL), pql,
                NO_EYES, NO_SOUND, probeHooks, null, 10, 600);
        probing.start();
        sleep(100);
        boolean dsFirst = pqw.calls.indexOf("tofds") >= 0 && pqw.count("tofen") == 0;
        probeHooks.probe = false;
        sleep(100);
        int enAfterOff = pqw.count("tofen");
        probing.stop();
        check("loop_fwd_probe_sends_tofds_at_start_and_tofen_when_it_ends", dsFirst && enAfterOff == 1,
                "tofds before off=" + dsFirst + " tofen after off=" + enAfterOff + " calls=" + pqw.calls);

        FakeWheels pqw2 = new FakeWheels();
        FakeLease pql2 = new FakeLease();
        pql2.held = true;
        Hooks probeHooks2 = new Hooks();
        probeHooks2.probe = true;
        ExploreLoop probing2 = new ExploreLoop(spinTuning(null), REAL, pqw2, new ClearSensors(REAL), pql2,
                NO_EYES, NO_SOUND, probeHooks2, null, 10, 600);
        probing2.start();
        sleep(100);
        probing2.stop();
        check("loop_stop_during_fwd_probe_sends_tofen", pqw2.count("tofds") >= 1 && pqw2.count("tofen") == 1,
                "calls=" + pqw2.calls);

        darkFloorScenarios();
    }

    /** A settable dark-floor switch, as the system property would be. */
    static final class Switch implements DarkFloor.Source {
        volatile boolean on;
        @Override public boolean darkFloor() { return on; }
    }

    static SensorReading flagged(long t, int ir2, Integer cpl) {
        return new SensorReading(t, 16383, SensorSnapshotAbsent.ABSENT, ir2, cpl, false);
    }

    /** A reading every 100 ms from..to (inclusive), each passed to df while held. */
    static void feed(DarkFloor df, long from, long to, int ir2, Integer cpl) {
        for (long t = from; t <= to; t += 100) {
            df.onPass(t, true, flagged(t, ir2, cpl));
        }
    }

    static boolean noted(Notes n, String part) {
        synchronized (n.notes) {
            for (String x : n.notes) {
                if (x.contains(part)) {
                    return true;
                }
            }
        }
        return false;
    }

    static void darkFloorScenarios() {
        // The loop: the switch read at start and every pollMs, TOFDS while held, TOFEN on off and stop.
        FakeWheels dw = new FakeWheels();
        FakeLease dl = new FakeLease();
        dl.held = true;
        Switch sw = new Switch();
        sw.on = true;
        Notes dn = new Notes();
        ExploreLoop dark = new ExploreLoop(quickTuning(), REAL, dw, new ClearSensors(REAL), dl,
                NO_EYES, NO_SOUND, new Hooks(), dn, 10, 600);
        dark.setDarkFloor(sw, 50);
        dark.start();
        sleep(300);
        int dsHeld = dw.count("tofds");
        check("loop_dark_floor_on_sends_tofds_once_while_held",
                dsHeld == 1 && dw.count("tofen") == 0 && noted(dn, "dark floor: on")
                        && noted(dn, "dark floor: TOFDS sent (drive session start)"),
                "tofds=" + dsHeld + " calls=" + dw.calls + " notes=" + dn.notes);
        dl.held = false;
        sleep(100);
        dl.held = true;
        sleep(200);
        check("loop_dark_floor_lease_regained_sends_tofds_again", dw.count("tofds") == 2
                        && noted(dn, "dark floor: TOFDS sent (lease re-acquired)"),
                "tofds=" + dw.count("tofds") + " notes=" + dn.notes);
        sw.on = false;
        sleep(200);
        int enOff = dw.count("tofen");
        check("loop_dark_floor_switched_off_sends_tofen", enOff == 1 && noted(dn, "dark floor: off")
                        && noted(dn, "dark floor: TOFEN sent (dark floor switched off)"),
                "tofen=" + enOff + " notes=" + dn.notes);
        sw.on = true;
        sleep(200);
        dark.stop();
        check("loop_dark_floor_stop_sends_tofen", dw.count("tofds") == 3 && dw.count("tofen") == 2
                        && dw.calls.get(dw.calls.size() - 1).equals("tofen")
                        && noted(dn, "dark floor: TOFEN sent (explore stopped)"),
                "calls tail=" + dw.calls.subList(Math.max(0, dw.calls.size() - 4), dw.calls.size()));

        FakeWheels ow = new FakeWheels();
        FakeLease ol = new FakeLease();
        ol.held = true;
        ExploreLoop off = new ExploreLoop(quickTuning(), REAL, ow, new ClearSensors(REAL), ol,
                NO_EYES, NO_SOUND, new Hooks(), null, 10, 600);
        off.start();
        sleep(200);
        off.stop();
        check("loop_dark_floor_off_by_default_sends_neither", ow.count("tofds") == 0 && ow.count("tofen") == 0,
                "calls=" + ow.calls);

        // DarkFloor itself on a made-up clock: re-sending when the MCU may have reset.
        FakeWheels gw = new FakeWheels();
        FakeLease gl = new FakeLease();
        gl.held = true;
        Notes gn = new Notes();
        Switch gs = new Switch();
        gs.on = true;
        DarkFloor df = new DarkFloor(gs, new DriveGate(gw, gl, gn), gn, 5000);
        df.poll(0);
        feed(df, 0, 1900, 1, null);
        int early = gw.count("tofds");
        feed(df, 2000, 5100, 1, null);
        check("dark_floor_resends_tofds_when_ir2_comes_back_at_most_every_5s",
                early == 1 && gw.count("tofds") == 2 && noted(gn, "the MCU flag is back (ir2=1)"),
                "early=" + early + " tofds=" + gw.count("tofds") + " notes=" + gn.notes);
        feed(df, 6800, 6800, 0, null);
        check("dark_floor_resends_tofds_when_readings_resume_after_a_gap",
                gw.count("tofds") == 3 && noted(gn, "readings resumed after 1700 ms"),
                "tofds=" + gw.count("tofds") + " notes=" + gn.notes);
        feed(df, 6900, 11800, 0, Integer.valueOf(2));
        check("dark_floor_resends_tofds_when_cpl2_comes_back",
                gw.count("tofds") == 4 && noted(gn, "the MCU flag is back (cpl=2)"),
                "tofds=" + gw.count("tofds") + " notes=" + gn.notes);
        gl.held = false;
        df.onPass(11900, false, flagged(11900, 0, null));
        gs.on = false;
        df.poll(20000);
        check("dark_floor_lease_loss_leaves_the_tofen_to_the_drive",
                gw.count("tofen") == 0 && gw.count("tofds") == 4, "calls=" + gw.calls);
        check("dark_floor_property_values",
                DarkFloor.parse("1") && DarkFloor.parse(" true ") && DarkFloor.parse("ON") && !DarkFloor.parse("0")
                        && !DarkFloor.parse("") && !DarkFloor.parse(null) && !DarkFloor.parse("2"),
                "parse");
    }
}
