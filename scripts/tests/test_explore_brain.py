#!/usr/bin/env python3
"""Tests for the explore mode's wander brain and hazard classifier (U3).

The brain is the safety-critical core of the mode: it decides when the robot
may hop, turn or back off, and it must stop on an edge, an obstacle, a lost
sensor feed or a lost drive lease (KTD3, KTD4, KTD5, KTD8, KTD9, KTD10).

MainActivity and the drive adapter are Android-only and the repo has no
Android test harness, so every decision lives in HazardClassifier and
ExploreBrain, plain Java with no android.* imports and everything injected.
This compiles them for the host JVM and runs fixtures/explore_brain_harness,
which scripts sensor readings against a mock clock and prints one PASS/FAIL
line per scenario.
"""
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

TESTS = Path(__file__).resolve().parent
if str(TESTS) not in sys.path:
    sys.path.insert(0, str(TESTS))
import jvm_harness  # noqa: E402

REPO = Path(__file__).resolve().parents[2]
EXPLORE_SRC = REPO / "mode-explore" / "src"
EXPLORE_PKG = EXPLORE_SRC / "com" / "miko3" / "mode" / "explore"
HARNESS = TESTS / "fixtures" / "explore_brain_harness" / "src"
HARNESS_MAIN = HARNESS / "com" / "miko3" / "mode" / "explore" / "ExploreBrainHarness.java"
PLAIN_JAVA = ("SensorReading.java", "HazardClassifier.java", "ExploreBrain.java", "ExploreTuning.java")


class BrainIsPlainJavaTest(unittest.TestCase):
    """It only runs on the host JVM while it stays clear of the Android SDK."""

    def test_no_android_imports(self):
        offenders = []
        for name in PLAIN_JAVA:
            for line in (EXPLORE_PKG / name).read_text().splitlines():
                if line.startswith("import android") or (line.startswith("import ") and ".android." in line):
                    offenders.append(f"{name}: {line}")
        self.assertEqual(offenders, [])

    def test_no_shared_driver_dependency(self):
        """The brain takes its own SensorReading, not the shared driver's snapshot."""
        offenders = [name for name in PLAIN_JAVA
                     if "com.miko3.shared" in (EXPLORE_PKG / name).read_text()]
        self.assertEqual(offenders, [])


class ExploreBrainHarnessTest(unittest.TestCase):
    """One harness run, one assertion per scenario."""

    SCENARIOS = (
        # HazardClassifier (KTD3, KTD9)
        "classifier_uncalibrated_is_unavailable",
        "classifier_incomplete_calibration_is_unavailable",
        "classifier_needs_the_recovery_streak_at_start",
        "classifier_goes_unavailable_after_300ms_without_a_reading",
        "classifier_tof_fault_value_is_unavailable",
        "classifier_uart_fault_is_unavailable",
        "classifier_frozen_tof_is_unavailable",
        "classifier_constant_ir_is_not_frozen",
        "classifier_flapping_needs_a_new_streak",
        "classifier_thresholds_report_edge_and_obstacle_sides",
        "classifier_cpl2_is_a_hazard",
        "classifier_absent_ir_is_never_an_edge",
        "classifier_one_ir_channel_gives_no_side",
        "classifier_fault_tof_with_ir_edge_flag_is_an_edge",
        # ExploreBrain: acceptance examples
        "ae1_edge_mid_hop_startles_backs_off_and_turns_away",
        "ae2_eyes_lead_the_turn_then_idle_on_the_hop",
        "ae3_no_readings_never_moves",
        "ae4_readings_stop_mid_hop",
        "ae5_lease_loss_mid_back_off",
        "ae6_cpl2_never_retries_forward",
        # ExploreBrain: edges and errors
        "hazard_during_a_turn_stops_the_turn",
        "hazard_at_hop_start_turns_away_instead",
        "escape_turn_steers_away_from_the_hazard_side",
        "cornered_cap_rests_without_motion_then_turns_wider",
        "flapping_readings_do_not_resume_driving",
        "uncalibrated_brain_never_moves",
        "frozen_tof_mid_run_stops_the_hop",
        "no_lease_never_moves",
        # ExploreBrain: happy path and integration with the drive adapter
        "continuous_legs_vary_in_length_within_range",
        "wander_cycle_hops_and_turns_only_on_fresh_clear_readings",
        "lease_loss_reported_inside_a_motor_call",
        "shutdown_stops_and_goes_inert",
    )

    @classmethod
    def setUpClass(cls):
        jdk = jvm_harness.find_jdk()
        if jdk is None:
            raise unittest.SkipTest("no JDK (javac + java) found")
        cls._td = tempfile.TemporaryDirectory(prefix="explore_brain_harness_")
        out = cls._td.name
        # No shared/src on the sourcepath: the brain must compile on its own.
        c = subprocess.run(jvm_harness.javac_cmd(jdk[0], out, [HARNESS_MAIN], [HARNESS, EXPLORE_SRC]),
                           capture_output=True, text=True)
        cls.compiled = c.returncode == 0
        cls.compile_output = (c.stdout + c.stderr)[-3000:]
        cls.results = {}
        cls.run_output = ""
        if c.returncode == 0:
            r = subprocess.run([jdk[1], "-cp", out, "com.miko3.mode.explore.ExploreBrainHarness"],
                               capture_output=True, text=True, timeout=60)
            cls.run_output = (r.stdout + r.stderr)[-6000:]
            cls.results = jvm_harness.parse_verdicts(r.stdout)

    @classmethod
    def tearDownClass(cls):
        cls._td.cleanup()

    def setUp(self):
        self.assertTrue(self.compiled, f"harness failed to compile:\n{self.compile_output}")

    def _assert_pass(self, name):
        self.assertIn(name, self.results, f"scenario {name} never reported:\n{self.run_output}")
        verdict, detail = self.results[name]
        self.assertEqual(verdict, "PASS", f"{name}: {detail}")

    def test_harness_reports_exactly_the_expected_scenarios(self):
        self.assertEqual(sorted(self.results), sorted(self.SCENARIOS), self.run_output)


jvm_harness.add_scenario_tests(ExploreBrainHarnessTest)


if __name__ == "__main__":
    unittest.main()
