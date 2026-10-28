#!/usr/bin/env python3
"""Host-side tests for AnswerParser and NameResolver (face plan U7; KTD6, KTD9,
KTD10): the reply to "Is that you, <name>?" and to "And your last name?", and
the rule that decides whether a spoken name joins a stored person, asks their
last name or stores someone new. Both are plain Java in mode-explore; the
parser runs here with the robot's own NameExtractor, as the adapter wires it.
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
SHARED_SRC = REPO / "shared" / "src"
HARNESS = TESTS / "fixtures" / "answer_parser_harness" / "src"
HARNESS_MAIN = HARNESS / "com" / "miko3" / "mode" / "explore" / "AnswerParserHarness.java"
PLAIN_JAVA = ("AnswerParser.java", "NameResolver.java")


class AnswerParserHarnessTest(unittest.TestCase):
    SCENARIOS = (
        "yes_is_yes",
        "yeah_thats_me_is_yes",
        "its_me_is_yes",
        "recognizer_its_me_is_yes",
        "yep_is_yes",
        "correct_is_yes",
        "name_asked_is_yes",
        "im_name_asked_is_yes",
        "full_name_asked_full_is_yes",
        "first_name_when_asked_full_is_unclear",
        "no_im_sarah_is_no_with_name",
        "no_comma_im_sarah_is_no_with_name",
        "im_sarah_is_no_with_name",
        "bare_other_name_is_no_with_name",
        "nope_its_priya_is_no_with_name",
        "other_full_name_is_no_with_name",
        "nope_is_no",
        "no_is_no",
        "not_me_is_no",
        "thats_not_me_is_no",
        "nah_is_no",
        "wrong_is_no",
        "whos_ben_is_unclear",
        "recognizer_whos_ben_is_unclear",
        "empty_is_unclear",
        "null_is_unclear",
        "maybe_is_unclear",
        "sentence_is_unclear",
        "last_one_word_is_the_last_name",
        "last_its_wilson",
        "last_full_name_starting_with_first",
        "last_full_name_other_first_is_none",
        "last_first_name_again_is_none",
        "last_nothing_is_none",
        "last_no_name_is_none",
        "unclear_counts_as_no",
        "resolve_no_stored_match_is_new",
        "resolve_close_to_any_photo_joins",
        "resolve_weak_asks_the_last_name",
        "resolve_close_edge_is_inclusive",
        "resolve_takes_the_best_matching_id",
        "resolve_full_name_weak_joins_that_full_name",
        "resolve_without_a_probe_asks_the_last_name",
        "last_name_ae4_smith_is_a_new_full_name",
        "last_name_ae4_wilson_joins_ben_wilson",
        "last_name_stored_without_a_last_name_is_new",
        "asked_name_first_word_when_unique",
        "asked_name_full_when_another_shares_the_first_name",
        "asked_name_one_word_stored",
        "asked_name_blank_is_none",
        "decision_to_string_carries_no_name",
        "resolve_joins_a_name_only_record_with_the_exact_name",
        "resolve_a_close_face_still_wins_over_a_name_only_record",
        "by_name_the_full_name_given_wins",
        "by_name_a_first_name_several_share_is_ambiguous",
        "by_name_a_unique_first_name_finds_them",
        "by_name_nobody_is_new",
        "by_name_a_full_name_matching_nobody_is_new",
    )

    @classmethod
    def setUpClass(cls):
        jdk = jvm_harness.find_jdk()
        if jdk is None:
            raise unittest.SkipTest("no JDK (javac + java) found")
        cls._td = tempfile.TemporaryDirectory(prefix="answer_parser_harness_")
        out = cls._td.name
        c = subprocess.run(jvm_harness.javac_cmd(jdk[0], out, [HARNESS_MAIN], [HARNESS, EXPLORE_SRC, SHARED_SRC]),
                           capture_output=True, text=True)
        cls.compiled = c.returncode == 0
        cls.compile_output = (c.stdout + c.stderr)[-3000:]
        cls.results, cls.run_output = {}, ""
        if cls.compiled:
            r = subprocess.run([jdk[1], "-cp", out, "com.miko3.mode.explore.AnswerParserHarness"],
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


jvm_harness.add_scenario_tests(AnswerParserHarnessTest)


class PlainJavaTest(unittest.TestCase):
    """Both run in the brain's host harness, which has no Android SDK and no shared sources."""

    def test_no_android_or_shared_imports(self):
        offenders = []
        for name in PLAIN_JAVA:
            path = EXPLORE_PKG / name
            self.assertTrue(path.exists(), f"{name} missing")
            for line in path.read_text().splitlines():
                if line.startswith("import android") or line.startswith("import com.miko3.shared"):
                    offenders.append(f"{name}: {line}")
        self.assertEqual(offenders, [])

    def test_no_logging(self):
        """Names and transcripts never reach a log: neither class logs at all."""
        for name in PLAIN_JAVA:
            path = EXPLORE_PKG / name
            if path.exists():
                self.assertNotIn("Log.", path.read_text(), name)


if __name__ == "__main__":
    unittest.main()
