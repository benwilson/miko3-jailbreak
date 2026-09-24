"""Host-side tests for shared/PageToken (settings plan U1, KTD6): the settings
pages' anti-forgery token, now shared, with a capacity of recent tokens it
still accepts (voice keeps 1, the launcher uses 4)."""
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
HARNESS = TESTS / "fixtures" / "shared_page_token_harness" / "src"
HARNESS_MAIN = HARNESS / "com" / "miko3" / "shared" / "PageTokenHarness.java"
PLAIN_JAVA = [SHARED / "PageToken.java", SHARED / "Json.java"]


class SharedClassesArePlainJavaTest(unittest.TestCase):
    """They only run on the host JVM while they stay clear of the Android SDK."""

    def test_no_android_imports(self):
        for path in PLAIN_JAVA:
            offenders = [line for line in path.read_text().splitlines() if line.startswith("import android")]
            self.assertEqual(offenders, [], path.name)

    def test_voice_copies_are_gone(self):
        voice = REPO / "mode-voice" / "src" / "com" / "miko3" / "mode" / "voice"
        self.assertFalse((voice / "PageToken.java").exists())
        self.assertFalse((voice / "LaneJson.java").exists())


class PageTokenHarnessTest(unittest.TestCase):
    """One harness run, one assertion per scenario."""

    SCENARIOS = (
        "capacity_one_accepts_only_latest",
        "capacity_four_accepts_last_four",
        "capacity_four_rejects_fifth_oldest",
        "nothing_issued_rejects_everything",
        "empty_token_rejected",
        "null_token_rejected",
        "wrong_token_rejected",
        "different_length_rejected",
        "tokens_are_32_hex_and_distinct",
        "capacity_below_one_rejected",
    )

    @classmethod
    def setUpClass(cls):
        jdk = jvm_harness.find_jdk()
        if jdk is None:
            raise unittest.SkipTest("no JDK (javac + java) found")
        cls._td = tempfile.TemporaryDirectory(prefix="shared_page_token_harness_")
        out = cls._td.name
        # Only the plain-Java class is compiled; the rest of shared/ needs the Android SDK.
        c = subprocess.run(jvm_harness.javac_cmd(jdk[0], out, [HARNESS_MAIN, SHARED / "PageToken.java"], [HARNESS]),
                           capture_output=True, text=True)
        cls.compiled = c.returncode == 0
        cls.compile_output = (c.stdout + c.stderr)[-3000:]
        cls.results = {}
        cls.run_output = ""
        if c.returncode == 0:
            r = subprocess.run([jdk[1], "-cp", out, "com.miko3.shared.PageTokenHarness"],
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


jvm_harness.add_scenario_tests(PageTokenHarnessTest)


if __name__ == "__main__":
    unittest.main()
