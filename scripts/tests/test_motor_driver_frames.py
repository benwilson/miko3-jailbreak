"""Host-side tests for shared/DirectMotorDriver's ToF-check frames (dark-floor mode):
disableTofCheck() writes exactly "TOFDS" + 'X' padding to 500 bytes, enableTofCheck()
exactly "TOFEN" likewise, both through the (stubbed) JNI UART. The Android and JNI
classes are host stubs in scripts/tests/fixtures/motor_driver_harness/stubs."""
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
SHARED = REPO / "shared" / "src" / "com" / "miko3" / "shared"
FIXTURE = TESTS / "fixtures" / "motor_driver_harness"
HARNESS_MAIN = FIXTURE / "src" / "com" / "miko3" / "shared" / "MotorDriverHarness.java"
SOURCES = [SHARED / n for n in ("DirectMotorDriver.java", "SensorReply.java", "SensorSnapshot.java")]
STUBS = sorted((FIXTURE / "stubs").rglob("*.java"))


class MotorDriverFramesTest(unittest.TestCase):
    SCENARIOS = (
        "tof_check_commands_need_a_connection",
        "disable_sends_exactly_tofds_padded_to_500",
        "disable_sends_no_tofen",
        "enable_sends_exactly_tofen_padded_to_500",
        "enable_sends_no_tofds",
        "enable_tof_is_the_same_tofen_frame",
    )

    @classmethod
    def setUpClass(cls):
        jdk = jvm_harness.find_jdk()
        if jdk is None:
            raise unittest.SkipTest("no JDK (javac + java) found")
        cls._td = tempfile.TemporaryDirectory(prefix="motor_driver_harness_")
        out = cls._td.name
        c = subprocess.run(jvm_harness.javac_cmd(jdk[0], out, [HARNESS_MAIN] + SOURCES + STUBS),
                           capture_output=True, text=True)
        cls.compiled = c.returncode == 0
        cls.compile_output = (c.stdout + c.stderr)[-3000:]
        cls.results = {}
        cls.run_output = ""
        if c.returncode == 0:
            r = subprocess.run([jdk[1], "-cp", out, "com.miko3.shared.MotorDriverHarness"],
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


jvm_harness.add_scenario_tests(MotorDriverFramesTest)


if __name__ == "__main__":
    unittest.main()
