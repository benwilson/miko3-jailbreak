"""Host-side tests for shared/SensorReply (explore plan U2): parsing the MCU's
POWER-poll reply into a SensorSnapshot (and its gyro, explore nav plan U1), run against records captured on the
robot (scripts/tests/fixtures/explore_sensor_records/)."""
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
SHARED_SRC = REPO / "shared" / "src"
HARNESS = TESTS / "fixtures" / "sensor_reply_harness" / "src"
HARNESS_MAIN = HARNESS / "com" / "miko3" / "shared" / "SensorReplyHarness.java"
BASELINE = TESTS / "fixtures" / "explore_sensor_records" / "baseline.txt"
PLAIN_JAVA = [SHARED_SRC / "com" / "miko3" / "shared" / n for n in ("SensorReply.java", "SensorSnapshot.java")]


class ParserIsPlainJavaTest(unittest.TestCase):
    """It only runs on the host JVM while it stays clear of the Android SDK."""

    def test_no_android_imports(self):
        for path in PLAIN_JAVA:
            offenders = [line for line in path.read_text().splitlines() if line.startswith("import android")]
            self.assertEqual(offenders, [], path.name)


class SensorReplyHarnessTest(unittest.TestCase):
    """One harness run, one assertion per scenario."""

    SCENARIOS = (
        "captured_record_parses",
        "every_captured_record_parses",
        "tofir_found_by_token_at_any_offset",
        "record_without_tofir_gives_no_snapshot",
        "truncated_tof_gives_no_snapshot",
        "garbage_gives_no_snapshot",
        "dead_sensor_value_is_flagged_fault",
        "error_uart_is_not_sensor_data",
        "cpl_two_is_read_from_a_drive_reply",
        "missing_cpl_is_unknown_not_zero",
        "malformed_cpl_is_unknown",
        "wheel_counts_are_read",
        "missing_or_cut_off_wheel_counts_are_absent",
        "captured_record_carries_the_gyro",
        "every_captured_record_carries_the_gyro",
        "gyro_sign_and_leading_zeros_are_read",
        "absent_gyro_leaves_the_rest_of_the_record",
        "cut_off_or_malformed_gyro_is_absent",
    )

    @classmethod
    def setUpClass(cls):
        jdk = jvm_harness.find_jdk()
        if jdk is None:
            raise unittest.SkipTest("no JDK (javac + java) found")
        cls._td = tempfile.TemporaryDirectory(prefix="sensor_reply_harness_")
        out = cls._td.name
        # Only the plain-Java parser classes are on the sourcepath; the rest of
        # shared/ needs the Android SDK.
        c = subprocess.run(jvm_harness.javac_cmd(jdk[0], out, [HARNESS_MAIN] + PLAIN_JAVA, [HARNESS]),
                           capture_output=True, text=True)
        cls.compiled = c.returncode == 0
        cls.compile_output = (c.stdout + c.stderr)[-3000:]
        cls.results = {}
        cls.run_output = ""
        if c.returncode == 0:
            r = subprocess.run([jdk[1], "-cp", out, "com.miko3.shared.SensorReplyHarness", str(BASELINE)],
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


jvm_harness.add_scenario_tests(SensorReplyHarnessTest)


if __name__ == "__main__":
    unittest.main()
