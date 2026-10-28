"""Host-side tests for the launcher's cue classifier (meeting plan U3; R2, R3,
R5, KTD3, KTD11).

The plain-Java CueClassifier (tiers for the wake word, name forms, greetings,
shove-plus-sorry timing, the "answers when spoken to" switch, and the side of a
direction angle) runs under a JVM harness; source checks keep it and the shared
CueWords (the name and apology vocabulary and the normalisation) free of
android.* and of any logging at all, since they see every utterance's words.
"""
import re
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
LAUNCHER_SRC = REPO / "launcher" / "src"
SHARED_SRC = REPO / "shared" / "src"
CLASSIFIER = LAUNCHER_SRC / "com" / "miko3" / "launcher" / "CueClassifier.java"
WORDS = SHARED_SRC / "com" / "miko3" / "shared" / "CueWords.java"
HOTWORDS = REPO / "launcher" / "assets" / "hotwords.txt"
EARS_INTERFACE = SHARED_SRC / "com" / "miko3" / "shared" / "RobotEars.java"
MODE_PKG = REPO / "mode-explore" / "src" / "com" / "miko3" / "mode" / "explore"
HARNESS = TESTS / "fixtures" / "cue_classifier_harness" / "src"
HARNESS_MAIN = HARNESS / "com" / "miko3" / "launcher" / "CueClassifierHarness.java"


def _strip_comments(text):
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


class CueClassifierHarnessTest(unittest.TestCase):
    SCENARIOS = (
        "names_and_greetings_are_strong",
        "lone_hey_and_burst_are_weak",
        "sorry_soon_after_shove_is_strong",
        "sorry_late_after_shove_is_weak",
        "switch_off_leaves_only_the_wake_word",
        "conversation_listen_ignores_the_switch",
        "side_follows_the_angle_sign",
        "greeting_inside_a_long_sentence_is_weak",
        "normalisation_ignores_case_and_punctuation",
        "kinds_follow_the_wake_flag_the_words_and_the_tier",
        "excuse_me_and_my_bad_after_a_shove_are_strong_apologies",
        "a_calls_message_is_its_words_besides_the_address",
    )

    @classmethod
    def setUpClass(cls):
        jdk = jvm_harness.find_jdk()
        if jdk is None:
            raise unittest.SkipTest("no JDK (javac + java) found")
        cls._td = tempfile.TemporaryDirectory(prefix="cue_classifier_harness_")
        out = cls._td.name
        c = subprocess.run(jvm_harness.javac_cmd(jdk[0], out, [HARNESS_MAIN],
                                                 [HARNESS, LAUNCHER_SRC, SHARED_SRC]),
                           capture_output=True, text=True)
        cls.compiled = c.returncode == 0
        cls.compile_output = (c.stdout + c.stderr)[-3000:]
        cls.results, cls.run_output = {}, ""
        if cls.compiled:
            r = subprocess.run([jdk[1], "-cp", out, "com.miko3.launcher.CueClassifierHarness"],
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


jvm_harness.add_scenario_tests(CueClassifierHarnessTest)


class ClassifierSourceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.raw = CLASSIFIER.read_text() if CLASSIFIER.exists() else ""
        cls.src = _strip_comments(cls.raw)
        cls.words_raw = WORDS.read_text() if WORDS.exists() else ""

    def test_is_plain_java(self):
        self.assertTrue(self.raw, "CueClassifier.java missing")
        self.assertTrue(self.words_raw, "CueWords.java missing")
        for raw in (self.raw, self.words_raw):
            self.assertEqual([ln for ln in raw.splitlines() if ln.startswith("import android")], [])

    def test_never_logs_anything(self):
        """They see every utterance's words, so they have no log line at all (KTD1: counters, never words)."""
        for src in (self.src, _strip_comments(self.words_raw)):
            for needle in ("Log.", "System.out", "System.err", "printStackTrace"):
                self.assertNotIn(needle, src)

    def test_shove_window_is_the_plans_two_seconds(self):
        self.assertRegex(self.src, r"SORRY_WINDOW_MS\s*=\s*2000")

    def test_reads_the_switch_through_an_interface(self):
        # KTD11: the launcher session reads the switch when it classifies a cue.
        self.assertRegex(self.src, r"interface Switch\s*\{[^}]*boolean answersWhenSpokenTo\(\)")

    @staticmethod
    def _ints(src, prefix):
        return dict(re.findall(r"int (" + prefix + r"\w+)\s*=\s*(-?\d+);", src))

    def test_tier_side_and_kind_ints_mirror_the_ears_binder(self):
        """The classifier cannot import RobotEars (android.os), so its TIER_*,
        SIDE_* and KIND_* ints are copies; the wire carries them as they are."""
        ears = _strip_comments(EARS_INTERFACE.read_text())
        for prefix in ("TIER_", "SIDE_", "KIND_"):
            mine, theirs = self._ints(self.src, prefix), self._ints(ears, prefix)
            self.assertTrue(mine, f"CueClassifier declares no {prefix}* ints")
            for name, value in mine.items():
                self.assertEqual(theirs.get(name), value, f"{name}: classifier {value}, RobotEars {theirs.get(name)}")
        self.assertEqual(self._ints(self.src, "KIND_"),
                         {"KIND_WAKE_WORD": "0", "KIND_NAME": "1", "KIND_GREETING": "2", "KIND_APOLOGY": "3",
                          "KIND_VOICE": "4"})

    def test_kind_is_decided_here_from_the_wake_flag_and_the_tier(self):
        """The owner's ruling (2026-09-26): the launcher names the kind; the
        apology check is the same isSorry the tier uses (SORRY_PHRASES too)."""
        self.assertRegex(self.src, r"static int kind\(String \w+, boolean \w+, int \w+\)")
        body = re.search(r"static int kind\([^)]*\)\s*\{(.*?)\n    \}", self.src, re.S)
        self.assertIsNotNone(body)
        self.assertIn("isSorry(", body.group(1))
        self.assertIn("CueWords.WAKE_PHRASES", body.group(1))
        self.assertRegex(_strip_comments(self.words_raw), r"String\[\] WAKE_PHRASES\s*=")

    def test_the_modes_own_copy_of_the_lexicon_is_gone(self):
        """PR #18 review: CueKinds re-copied CueWords and drifted; the kind now
        arrives over the wire, so the mode keeps no lexicon at all."""
        self.assertFalse((MODE_PKG / "CueKinds.java").exists(), "mode-explore still has CueKinds.java")
        for name in ("NAMES", "SORRY_WORDS", "SORRY_PHRASES", "WAKE_PHRASES"):
            for java in MODE_PKG.glob("*.java"):
                self.assertNotRegex(_strip_comments(java.read_text()), r"String\[\] " + name + r"\s*=", java.name)


class HotwordsFileTest(unittest.TestCase):
    """KTD2: the hotwords file lives at the APK asset root, outside the staged
    model directory, and carries the name forms and greetings the classifier
    treats as strong, one per line, upper case as the model's tokens are."""

    def test_hotwords_file_exists_at_the_asset_root(self):
        self.assertTrue(HOTWORDS.is_file(), f"{HOTWORDS} missing")
        self.assertFalse((REPO / "launcher" / "assets" / "listen" / "hotwords.txt").exists())

    def test_hotwords_cover_the_plans_examples(self):
        lines = [ln.split(":")[0].strip() for ln in HOTWORDS.read_text().splitlines()
                 if ln.strip() and not ln.startswith("#")]
        for phrase in ("MIKO", "MIKA", "MIKEY", "HEY BUDDY", "MORNING"):
            self.assertIn(phrase, lines)
        for ln in lines:
            self.assertEqual(ln, ln.upper(), ln)

    def test_every_hotword_name_form_is_one_the_classifier_knows(self):
        # The classifier matches names against the shared CueWords.NAMES.
        src = _strip_comments(WORDS.read_text()) if WORDS.exists() else ""
        names = re.search(r"NAMES\s*=\s*\{([^}]*)\}", src)
        self.assertIsNotNone(names, "CueWords declares no NAMES")
        self.assertIn("CueWords.NAMES", _strip_comments(CLASSIFIER.read_text()))
        known = set(re.findall(r'"([^"]+)"', names.group(1)))
        for phrase in ("miko", "mika", "mikey"):
            self.assertIn(phrase, known)


if __name__ == "__main__":
    unittest.main()
