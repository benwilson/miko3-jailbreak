"""Host-side tests for the owner's notes about people by name (owner 2026-10-03).

The owner writes a short note per person, by name, on the Settings page; Explore
asks for the note of the person it is talking to and follows it for how to
approach them. The plain-Java pieces run under a JVM harness: the shared
OwnerNotes value (caps, JSON round trip, add/edit/delete, the name match) and the
launcher's OwnerNotesStore (app-private file, atomic write, reload). The Binder
plumbing (RobotPeople's ownerNoteFor, PeopleService, RobotPeopleClient) is
covered by source-wiring checks, since host tests can't run it.
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
HARNESS = TESTS / "fixtures" / "owner_notes_harness" / "src"
HARNESS_MAIN = HARNESS / "com" / "miko3" / "launcher" / "OwnerNotesHarness.java"


def _code(path):
    text = path.read_text() if path.exists() else ""
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


class OwnerNotesHarnessTest(unittest.TestCase):
    SCENARIOS = (
        "add_keeps_name_and_note_trimmed_with_a_stable_id",
        "a_blank_name_or_note_is_refused",
        "a_note_over_600_chars_or_a_name_over_80_is_refused",
        "edit_changes_one_entry_and_unknown_id_is_false",
        "delete_removes_one_entry_and_unknown_id_is_false",
        "json_round_trip_keeps_entries_and_ids",
        "bad_json_reads_as_no_notes_and_bad_entries_are_skipped",
        "match_is_case_insensitive_and_whitespace_collapsed",
        "full_name_match_wins_over_first_name",
        "a_first_name_alone_never_matches_a_full_name_entry",
        "first_name_shared_by_two_entries_matches_nothing",
        "a_full_name_asked_never_matches_a_first_name_entry",
        "different_last_names_never_match",
        "unknown_or_blank_name_is_null",
        "store_persists_atomically_and_reloads",
        "store_refusal_writes_nothing",
        "store_lives_beside_the_people_store",
    )

    @classmethod
    def setUpClass(cls):
        jdk = jvm_harness.find_jdk()
        if jdk is None:
            raise unittest.SkipTest("no JDK (javac + java) found")
        cls._td = tempfile.TemporaryDirectory(prefix="owner_notes_harness_")
        out = cls._td.name
        c = subprocess.run(jvm_harness.javac_cmd(jdk[0], out, [HARNESS_MAIN], [HARNESS, LAUNCHER_SRC, SHARED_SRC]),
                           capture_output=True, text=True)
        cls.compiled = c.returncode == 0
        cls.compile_output = (c.stdout + c.stderr)[-3000:]
        cls.results = {}
        cls.run_output = ""
        if c.returncode == 0:
            r = subprocess.run([jdk[1], "-cp", out, "com.miko3.launcher.OwnerNotesHarness"],
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


jvm_harness.add_scenario_tests(OwnerNotesHarnessTest)


class OwnerNotesWiringTest(unittest.TestCase):
    """The Android glue, read from source."""

    def test_the_value_and_the_store_are_plain_java_and_never_log(self):
        for path in (LAUNCHER / "OwnerNotesStore.java", SHARED / "OwnerNotes.java"):
            src = _code(path)
            self.assertTrue(src, f"{path.name} missing")
            self.assertNotIn("import android", src, path.name)
            for needle in ("Log.", "System.out", "System.err", "printStackTrace"):
                self.assertNotIn(needle, src, f"{path.name}: {needle}")
        self.assertIn("MAX_NOTE_CHARS = 600", _code(SHARED / "OwnerNotes.java"))

    def test_the_store_hangs_off_the_people_store_in_its_private_dir(self):
        store = _code(LAUNCHER / "PeopleStore.java")
        self.assertIn("new OwnerNotesStore(new File(dir, OwnerNotesStore.FILE))", store)
        self.assertRegex(store, r"OwnerNotesStore ownerNotes\(\)")

    def test_the_binder_carries_owner_note_for_as_an_appended_transaction(self):
        iface = _code(SHARED / "RobotPeople.java")
        self.assertIn("static final int TRANSACTION_ownerNoteFor = 18;", iface)
        self.assertIn("String ownerNoteFor(String name) throws RemoteException;", iface)
        self.assertLess(iface.index("TRANSACTION_recordFeedback = 17;"), iface.index("TRANSACTION_ownerNoteFor = 18;"))
        proxy = iface.split("class Proxy", 1)[1]
        body = re.search(r"public String ownerNoteFor\((.*?)\n            \}", proxy, re.S).group(1)
        self.assertIn("LauncherProtocol.LAUNCHER_TOO_OLD", body)
        service = _code(LAUNCHER / "PeopleService.java")
        rec = re.search(r"public String ownerNoteFor\((.*?)\n        \}", service, re.S)
        self.assertIsNotNone(rec)
        self.assertLess(rec.group(1).index("enforceCaller()"), rec.group(1).index("people().ownerNotes().noteFor("))
        client = _code(SHARED / "RobotPeopleClient.java")
        self.assertIn("public static String ownerNoteFor(Context context, final String name) throws IOException",
                      client)

    def test_nothing_in_git_holds_owner_notes(self):
        tracked = subprocess.run(["git", "-C", str(REPO), "ls-files"], capture_output=True, text=True).stdout
        self.assertNotIn("owner-notes.json", tracked)


if __name__ == "__main__":
    unittest.main()
