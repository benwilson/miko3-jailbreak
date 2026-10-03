"""Host-side tests for the feedback log (owner 2026-10-02: when people give the
robot feedback about himself in a conversation, keep the feedback, never the
conversation).

The plain-Java pieces run under a JVM harness: the shared Feedback value that
reads a turn's "feedback" field, the launcher's FeedbackStore (append-only,
capped, newest first, persisted), and PeopleStore's side (who an entry names;
forget deletes that person's entries). The Binder plumbing (RobotPeople's
recordFeedback, PeopleService, RobotPeopleClient) is covered by source-wiring
checks, since host tests can't run it.
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
LAUNCHER = LAUNCHER_SRC / "com" / "miko3" / "launcher"
SHARED_SRC = REPO / "shared" / "src"
SHARED = SHARED_SRC / "com" / "miko3" / "shared"
HARNESS = TESTS / "fixtures" / "feedback_log_harness" / "src"
HARNESS_MAIN = HARNESS / "com" / "miko3" / "launcher" / "FeedbackLogHarness.java"


def _code(path):
    text = path.read_text() if path.exists() else ""
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


class FeedbackLogHarnessTest(unittest.TestCase):
    SCENARIOS = (
        "parse_accepts_each_kind_and_cleans_the_text",
        "parse_ignores_a_malformed_or_empty_field",
        "the_quote_and_summary_are_capped",
        "record_then_newest_first_with_every_field",
        "the_cap_drops_the_oldest",
        "the_log_survives_a_reload_and_skips_corrupt_lines",
        "clear_empties_the_log_for_good",
        "a_refused_entry_writes_nothing",
        "context_is_cleaned_and_capped",
        "an_unknown_or_unnamed_person_is_someone",
        "a_known_named_person_gives_their_first_name_only",
        "forget_deletes_that_persons_entries_and_keeps_the_rest",
        "the_people_stores_orphan_sweep_keeps_the_log",
    )

    @classmethod
    def setUpClass(cls):
        jdk = jvm_harness.find_jdk()
        if jdk is None:
            raise unittest.SkipTest("no JDK (javac + java) found")
        cls._td = tempfile.TemporaryDirectory(prefix="feedback_log_harness_")
        out = cls._td.name
        c = subprocess.run(jvm_harness.javac_cmd(jdk[0], out, [HARNESS_MAIN], [HARNESS, LAUNCHER_SRC, SHARED_SRC]),
                           capture_output=True, text=True)
        cls.compiled = c.returncode == 0
        cls.compile_output = (c.stdout + c.stderr)[-3000:]
        cls.results = {}
        cls.run_output = ""
        if c.returncode == 0:
            r = subprocess.run([jdk[1], "-cp", out, "com.miko3.launcher.FeedbackLogHarness"],
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


jvm_harness.add_scenario_tests(FeedbackLogHarnessTest)


class FeedbackWiringTest(unittest.TestCase):
    """The Android glue, read from source."""

    def test_the_log_and_the_value_are_plain_java_and_never_log(self):
        for path in (LAUNCHER / "FeedbackStore.java", SHARED / "Feedback.java"):
            src = _code(path)
            self.assertTrue(src, f"{path.name} missing")
            self.assertNotIn("import android", src, path.name)
            for needle in ("Log.", "System.out", "System.err", "printStackTrace"):
                self.assertNotIn(needle, src, f"{path.name}: {needle}")

    def test_the_log_lives_in_the_people_store_so_forget_reaches_it(self):
        store = _code(LAUNCHER / "PeopleStore.java")
        self.assertIn("new FeedbackStore(new File(dir, FeedbackStore.FILE), clock)", store)
        forget = re.search(r"synchronized boolean forget\(String id\)\s*\{(.*?)\n    \}", store, re.S)
        self.assertIsNotNone(forget)
        self.assertIn("feedback.purgePerson(id)", forget.group(1))

    def test_the_binder_carries_record_feedback_as_an_appended_transaction(self):
        iface = _code(SHARED / "RobotPeople.java")
        self.assertIn("static final int TRANSACTION_recordFeedback = 17;", iface)
        self.assertIn("boolean recordFeedback(String id, String kind, String summary, String quote, String context)",
                      iface)
        proxy = iface.split("class Proxy", 1)[1]
        body = re.search(r"public boolean recordFeedback\((.*?)\n            \}", proxy, re.S).group(1)
        self.assertIn("LauncherProtocol.LAUNCHER_TOO_OLD", body)
        service = _code(LAUNCHER / "PeopleService.java")
        rec = re.search(r"public boolean recordFeedback\((.*?)\n        \}", service, re.S)
        self.assertIsNotNone(rec)
        self.assertLess(rec.group(1).index("enforceCaller()"), rec.group(1).index("people().recordFeedback("))
        self.assertIn("Feedback.of(kind, summary, quote)", rec.group(1))
        client = _code(SHARED / "RobotPeopleClient.java")
        self.assertIn("public static boolean recordFeedback(Context context, final String id, final Feedback feedback,",
                      client)


if __name__ == "__main__":
    unittest.main()
