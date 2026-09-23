"""Host-side tests for the explore mode's drive wiring (explore plan U5): the
stop timer that halts the motors when the brain stops ticking (KTD6), the
lease-gated motor adapter, and the brain loop's start/exit order."""
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
HARNESS = TESTS / "fixtures" / "explore_drive_harness" / "src"
HARNESS_MAIN = HARNESS / "com" / "miko3" / "mode" / "explore" / "ExploreDriveHarness.java"
PLAIN_JAVA = [EXPLORE_SRC / "com" / "miko3" / "mode" / "explore" / n
              for n in ("StopTimer.java", "DriveGate.java", "ExploreLoop.java", "ExploreCalibration.java",
                        "LeaseTrust.java")]


class DriveWiringIsPlainJavaTest(unittest.TestCase):
    def test_no_android_imports(self):
        for path in PLAIN_JAVA:
            offenders = [line for line in path.read_text().splitlines() if line.startswith("import android")]
            self.assertEqual(offenders, [], path.name)


class ExploreDriveHarnessTest(unittest.TestCase):
    """One harness run, one assertion per scenario."""

    SCENARIOS = (
        "stop_timer_idle_until_first_feed",
        "stop_timer_fires_once_after_silence",
        "stop_timer_never_fires_while_fed",
        "stop_timer_rearms_after_a_feed",
        "stop_timer_rearm_fires_again_without_a_feed",
        "lease_trust_expires_before_the_launcher_ttl",
        "gate_drops_motion_without_lease",
        "gate_stop_goes_out_without_lease",
        "gate_passes_motion_under_lease",
        "gate_write_failure_stops_best_effort",
        "calibration_missing_file_is_uncalibrated",
        "calibration_round_trips",
        "calibration_corrupt_file_is_uncalibrated",
        "calibration_without_an_edge_rule_is_uncalibrated",
        "calibration_bad_boolean_is_uncalibrated",
        "loop_moves_only_once_the_lease_is_held",
        "loop_stale_hook_keeps_the_robot_still",
        "loop_exit_ends_in_stop_and_goes_quiet",
        "loop_stop_timer_stops_a_frozen_brain",
        "loop_stop_timer_retries_a_failed_stop",
    )

    @classmethod
    def setUpClass(cls):
        jdk = jvm_harness.find_jdk()
        if jdk is None:
            raise unittest.SkipTest("no JDK (javac + java) found")
        cls._td = tempfile.TemporaryDirectory(prefix="explore_drive_harness_")
        out = cls._td.name
        # Only mode-explore's own plain-Java sources: the brain, the classifier,
        # and the wiring under test. ModeApp/ExploreDrive need the Android SDK.
        c = subprocess.run(jvm_harness.javac_cmd(jdk[0], out, [HARNESS_MAIN] + PLAIN_JAVA, [HARNESS, EXPLORE_SRC]),
                           capture_output=True, text=True)
        cls.compiled = c.returncode == 0
        cls.compile_output = (c.stdout + c.stderr)[-3000:]
        cls.results = {}
        cls.run_output = ""
        if c.returncode == 0:
            r = subprocess.run([jdk[1], "-cp", out, "com.miko3.mode.explore.ExploreDriveHarness"],
                               capture_output=True, text=True, timeout=120)
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


jvm_harness.add_scenario_tests(ExploreDriveHarnessTest)


if __name__ == "__main__":
    unittest.main()
