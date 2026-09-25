"""Host-side tests for NameExtractor (explore-on-claude plan U3; R11, R12,
KTD4): pulling a name out of a short spoken reply on the robot, before any
Claude fallback. Runs the plain-Java extractor under a JVM harness.
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
SHARED_SRC = REPO / "shared" / "src"
EXTRACTOR = SHARED_SRC / "com" / "miko3" / "shared" / "NameExtractor.java"
HARNESS = TESTS / "fixtures" / "name_extractor_harness" / "src"
HARNESS_MAIN = HARNESS / "com" / "miko3" / "shared" / "NameExtractorHarness.java"


class NameExtractorHarnessTest(unittest.TestCase):
    SCENARIOS = (
        "my_name_is", "i_m", "call_me", "bare_name", "its_name_thanks", "i_am_full_name",
        "what_is_null", "no_is_null", "dont_know_is_null",
        "recognizer_upper_case", "recognizer_i_m_upper", "greeting_then_name", "filler_then_name",
        "this_is", "my_names", "name_then_trailer", "bare_full_name", "bare_name_please", "hyphenated",
        "im_fine_is_null", "im_not_telling_is_null", "sentence_is_null", "empty_is_null",
        "null_is_null", "blank_is_null", "hello_alone_is_null", "yes_is_null", "digits_is_null",
        "clipped_s_tail", "clipped_s_no_apostrophe", "clipped_m_tail", "clipped_re_tail",
        "names_ben", "its_ben", "im_ben", "stray_letter_before_name", "stray_letter_inside_name",
        "lone_letter_is_null",
    )

    @classmethod
    def setUpClass(cls):
        jdk = jvm_harness.find_jdk()
        if jdk is None:
            raise unittest.SkipTest("no JDK (javac + java) found")
        cls._td = tempfile.TemporaryDirectory(prefix="name_extractor_harness_")
        out = cls._td.name
        c = subprocess.run(jvm_harness.javac_cmd(jdk[0], out, [HARNESS_MAIN], [HARNESS, SHARED_SRC]),
                           capture_output=True, text=True)
        cls.compiled = c.returncode == 0
        cls.compile_output = (c.stdout + c.stderr)[-3000:]
        cls.results, cls.run_output = {}, ""
        if cls.compiled:
            r = subprocess.run([jdk[1], "-cp", out, "com.miko3.shared.NameExtractorHarness"],
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


jvm_harness.add_scenario_tests(NameExtractorHarnessTest)


class PlainJavaTest(unittest.TestCase):
    def test_extractor_is_plain_java(self):
        raw = EXTRACTOR.read_text() if EXTRACTOR.exists() else ""
        self.assertTrue(raw, "NameExtractor.java missing")
        self.assertEqual([ln for ln in raw.splitlines() if ln.startswith("import android")], [])


if __name__ == "__main__":
    unittest.main()
