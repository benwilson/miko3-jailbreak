"""Host-side tests for the launcher's people store (explore-on-claude plan U2;
R13-R16, AE6, KTD1, KTD3).

The plain-Java PeopleStore (ids, names, last seen, face files, "recent N",
forget) runs under a JVM harness over a temporary directory. The People
section of the Settings page is covered by the page harness in
test_launcher_settings.py. The Android glue (the Binder service, its client,
the manifest, LauncherApp's wiring) is covered by source-wiring checks, since
host tests can't run it.
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
HARNESS = TESTS / "fixtures" / "people_store_harness" / "src"
HARNESS_MAIN = HARNESS / "com" / "miko3" / "launcher" / "PeopleStoreHarness.java"
STORE = LAUNCHER / "PeopleStore.java"
SERVICE = LAUNCHER / "PeopleService.java"
APP = LAUNCHER / "LauncherApp.java"
PAGE = LAUNCHER / "SettingsPage.java"
INTERFACE = SHARED / "RobotPeople.java"
CLIENT = SHARED / "RobotPeopleClient.java"
NOTES = SHARED / "PersonNotes.java"
PROTOCOL = SHARED / "LauncherProtocol.java"
MANIFEST = REPO / "launcher" / "AndroidManifest.xml"


def _strip_comments(text):
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def _read(path):
    return _strip_comments(path.read_text()) if path.exists() else ""


def _method_body(src, name):
    """Body of the first method called name (brace-matched), or None."""
    m = re.search(r"\b" + re.escape(name) + r"\([^)]*\)[^{;]*\{", src)
    if m is None:
        return None
    depth, i = 1, m.end()
    while depth and i < len(src):
        depth += {"{": 1, "}": -1}.get(src[i], 0)
        i += 1
    return src[m.end():i - 1]


class PeopleStoreHarnessTest(unittest.TestCase):
    SCENARIOS = (
        "add_then_recent_newest_first",
        "recent_is_capped_at_n",
        "touch_moves_person_to_front",
        "face_bytes_round_trip",
        "rename_changes_name_used_next_time",
        "rename_empty_makes_person_unnamed",
        "name_is_trimmed_capped_and_cleaned",
        "unknown_person_has_no_name",
        "forget_deletes_file_and_entry",
        "index_survives_reload",
        "index_write_leaves_no_temp_file",
        "corrupt_index_lines_are_skipped",
        "add_refuses_non_jpeg_empty_and_oversized",
        "ids_are_validated_before_any_file_access",
        "unpinned_caller_is_denied",
        "merge_adds_interests_and_closes_a_thread",
        "merge_is_idempotent",
        "merge_past_caps_trims_in_named_order",
        "byte_cap_never_drops_a_question_before_an_interest",
        "ten_conversations_of_questions_fit_under_the_cap",
        "bad_delta_is_refused_and_document_unchanged",
        "merge_for_unknown_id_is_refused",
        "notes_survive_reload",
        "forget_removes_index_notes_and_face",
        "orphans_after_a_crash_are_deleted_on_load",
        "notes_for_unknown_id_are_empty",
        "malformed_notes_file_loads_as_empty",
        "gallery_excludes_nameless_records_store_still_lists_them",
        "entries_are_cleaned_and_matched_on_normalised_text",
        "index_read_failure_skips_the_orphan_sweep",
    )

    @classmethod
    def setUpClass(cls):
        jdk = jvm_harness.find_jdk()
        if jdk is None:
            raise unittest.SkipTest("no JDK (javac + java) found")
        cls._td = tempfile.TemporaryDirectory(prefix="people_store_harness_")
        out = cls._td.name
        # javac pulls in only what the harness references: PeopleStore and
        # CallerCheck. Never the Android-bound service.
        c = subprocess.run(jvm_harness.javac_cmd(jdk[0], out, [HARNESS_MAIN],
                                                 [HARNESS, LAUNCHER_SRC, SHARED_SRC]),
                           capture_output=True, text=True)
        cls.compiled = c.returncode == 0
        cls.compile_output = (c.stdout + c.stderr)[-3000:]
        cls.results = {}
        cls.run_output = ""
        if c.returncode == 0:
            r = subprocess.run([jdk[1], "-cp", out, "com.miko3.launcher.PeopleStoreHarness"],
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


jvm_harness.add_scenario_tests(PeopleStoreHarnessTest)


class StoreSourceTest(unittest.TestCase):
    def test_store_is_plain_java(self):
        raw = STORE.read_text() if STORE.exists() else ""
        self.assertTrue(raw, "PeopleStore.java missing")
        self.assertEqual([ln for ln in raw.splitlines() if ln.startswith("import android")], [])

    def test_index_is_written_durably(self):
        src = _read(STORE)
        # Temp file, fsync, then an atomic rename over the index.
        for needle in (".getFD().sync()", "renameTo("):
            self.assertIn(needle, src)

    def test_forget_deletes_index_then_notes_then_face(self):
        # KTD10: once the index no longer names them, a crash leaves only orphans.
        body = _method_body(_read(STORE), "synchronized boolean forget")
        self.assertIsNotNone(body)
        index = body.find("saveIndex")
        notes = body.find("notesFile(")
        face = body.find("faceFile(")
        self.assertGreaterEqual(index, 0, "forget never rewrites the index")
        self.assertGreater(notes, index, "notes deleted before the index rewrite")
        self.assertGreater(face, notes, "face deleted before the notes")
        self.assertRegex(body[notes:], r"notesFile\(\s*id\s*\)\.delete\(\)")
        self.assertRegex(body[face:], r"faceFile\(\s*id\s*\)\.delete\(\)")

    def test_load_sweeps_orphans_and_the_gallery_skips_nameless(self):
        src = _read(STORE)
        load = _method_body(src, "private void load")
        self.assertIn("delete()", load or "")
        recent = _method_body(src, "synchronized List<Person> recent")
        self.assertRegex(recent or "", r"name\.isEmpty\(\)")

    def test_a_torn_index_read_skips_the_orphan_sweep(self):
        src = _read(STORE)
        load = _method_body(src, "private void load")
        self.assertIsNotNone(load)
        self.assertIn("boolean complete = !index.isFile() || readIndex(index);", load)
        gate = load.index("if (!complete)")
        self.assertLess(gate, load.index("delete()"), "the sweep runs before the read is known complete")
        self.assertRegex(load[gate:], r"if \(!complete\) \{\s*return;")
        read = _method_body(src, "private boolean readIndex")
        self.assertIsNotNone(read, "readIndex does not report whether it reached the end")
        self.assertRegex(read, r"while \(\(line = in\.readLine\(\)\) != null\)")
        self.assertRegex(read, r"\}\s*return true;\s*\} catch \(IOException e\) \{[^}]*return false;")

    def test_nothing_is_logged(self):
        for path in (STORE, SERVICE, INTERFACE, CLIENT, NOTES):
            src = _read(path)
            with self.subTest(file=path.name):
                self.assertTrue(src, f"{path.name} missing")
                for needle in ("System.out", "System.err", "printStackTrace"):
                    self.assertNotIn(needle, src)
                # Names are personal: no log line carries one.
                for stmt in re.findall(r"Log\.\w\([^;]*;", src, flags=re.S):
                    self.assertNotRegex(stmt, r"(?i)name|jpeg|face", stmt)


class ServiceWiringTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.src = _read(SERVICE)

    def test_every_call_checks_the_caller_before_the_store(self):
        for method in ("public RobotPeople.Face[] recent", "public String add", "public boolean touch",
                       "public String nameOf", "public String notesOf", "public String mergeNotes",
                       "public boolean forget"):
            with self.subTest(method=method):
                body = _method_body(self.src, method)
                self.assertIsNotNone(body, f"PeopleService does not implement {method}")
                check, store = body.find("enforceCaller("), body.find("people()")
                self.assertGreaterEqual(check, 0, f"{method} never checks the caller")
                self.assertGreater(store, check, f"{method} touches the store before the caller check")

    def test_caller_gate_is_reused(self):
        self.assertIn("CallerGate.enforce(", self.src)

    def test_recent_is_capped_by_the_interface_limit(self):
        body = _method_body(self.src, "public RobotPeople.Face[] recent")
        self.assertIn("RobotPeople.MAX_RECENT", body or "")

    def test_uses_the_launcher_store(self):
        self.assertIn("(LauncherApp) getApplication()", self.src)
        self.assertIn("LauncherProtocol.ROBOT_PEOPLE_ACTION", self.src)


class InterfaceAndClientTest(unittest.TestCase):
    def test_hand_written_binder_shape(self):
        src = _read(INTERFACE)
        for needle in ("extends IInterface", "abstract class Stub extends Binder", "class Proxy",
                       '"com.miko3.shared.RobotPeople"', "enforceInterface(", "writeInterfaceToken(",
                       "reply.readException()", "writeNoException()", "writeByteArray(", "createByteArray()"):
            self.assertIn(needle, src)

    def test_interface_declares_the_four_calls(self):
        body = _read(INTERFACE).split("abstract class Stub", 1)[0]
        self.assertRegex(body, r"Face\[\] recent\(int \w+\) throws RemoteException;")
        self.assertRegex(body, r"String add\(byte\[\] \w+, String \w+\) throws RemoteException;")
        self.assertRegex(body, r"boolean touch\(String \w+\) throws RemoteException;")
        self.assertRegex(body, r"String nameOf\(String \w+\) throws RemoteException;")

    def test_interface_declares_the_notes_and_forget_calls(self):
        body = _read(INTERFACE).split("abstract class Stub", 1)[0]
        self.assertRegex(body, r"String notesOf\(String \w+\) throws RemoteException;")
        self.assertRegex(body, r"String mergeNotes\(String \w+, String \w+\) throws RemoteException;")
        self.assertRegex(body, r"boolean forget\(String \w+\) throws RemoteException;")

    def test_transaction_codes_are_appended(self):
        # KTD10: the four existing codes are unchanged; the three new ones follow.
        src = _read(INTERFACE)
        for name, code in (("recent", 1), ("add", 2), ("touch", 3), ("nameOf", 4),
                           ("notesOf", 5), ("mergeNotes", 6), ("forget", 7)):
            self.assertRegex(src, rf"TRANSACTION_{name}\s*=\s*{code}\s*;")
        codes = [int(c) for c in re.findall(r"TRANSACTION_\w+\s*=\s*(\d+)\s*;", src)]
        self.assertEqual(codes, list(range(1, 8)))

    def test_new_proxy_methods_check_the_transaction_result(self):
        src = _read(INTERFACE)
        proxy = src[src.index("class Proxy"):]
        for name in ("notesOf", "mergeNotes", "forget"):
            with self.subTest(method=name):
                body = _method_body(proxy, f"public \\w+ {name}") or _method_body(proxy, f"public String {name}") \
                    or _method_body(proxy, f"public boolean {name}")
                self.assertIsNotNone(body, f"Proxy does not implement {name}")
                self.assertRegex(body, rf"if\s*\(\s*!remote\.transact\(\s*TRANSACTION_{name}")
                self.assertIn("LauncherProtocol.LAUNCHER_TOO_OLD", body)

    def test_client_has_notes_and_forget_and_reports_an_old_launcher(self):
        src = _read(CLIENT)
        for needle in ("PersonNotes notesOf(", "PersonNotes mergeNotes(", "boolean forget(",
                       "catch (UnsupportedOperationException", "LauncherProtocol.LAUNCHER_TOO_OLD"):
            self.assertIn(needle, src)

    def test_person_notes_is_plain_java_with_named_caps(self):
        raw = NOTES.read_text() if NOTES.exists() else ""
        self.assertTrue(raw, "PersonNotes.java missing")
        self.assertEqual([ln for ln in raw.splitlines() if ln.startswith("import android")], [])
        src = _strip_comments(raw)
        for name, value in (("MAX_QUESTIONS_ASKED", 120), ("MAX_OPEN_THREADS", 6), ("MAX_INTERESTS", 8),
                            ("MAX_TOPICS", 12), ("MAX_ENTRY_CHARS", 80)):
            self.assertRegex(src, rf"public static final int {name}\s*=\s*{value}\s*;")
        m = re.search(r"public static final int MAX_DOCUMENT_BYTES\s*=\s*([0-9 *]+);", src)
        self.assertIsNotNone(m, "PersonNotes has no MAX_DOCUMENT_BYTES")
        # The binding limit: smaller than the per-list caps could add up to.
        self.assertLess(eval(m.group(1)), 120 * 80)

    def test_reply_stays_well_under_the_binder_limit(self):
        # KTD3: up to 10 faces; the store caps each face, so 10 of them stay
        # far below Binder's 1 MB transaction buffer.
        self.assertRegex(_read(INTERFACE), r"MAX_RECENT\s*=\s*10\s*;")
        m = re.search(r"MAX_FACE_BYTES\s*=\s*([0-9 *]+);", _read(STORE))
        self.assertIsNotNone(m, "PeopleStore has no MAX_FACE_BYTES")
        self.assertLessEqual(10 * eval(m.group(1)), 512 * 1024)

    def test_client_binds_by_action_and_unbinds(self):
        src = _read(CLIENT)
        for needle in ("new Intent(LauncherProtocol.ROBOT_PEOPLE_ACTION)",
                       "setPackage(LauncherProtocol.LAUNCHER_PACKAGE)", "bindService(",
                       "unbindService(", "RobotPeople.Stub.asInterface(", "Looper.getMainLooper()"):
            self.assertIn(needle, src)


class ProtocolManifestAndAppTest(unittest.TestCase):
    def test_action_and_paths_declared(self):
        src = _read(PROTOCOL)
        self.assertRegex(src, r'ROBOT_PEOPLE_ACTION\s*=\s*"com\.miko3\.launcher\.ROBOT_PEOPLE"')
        for name, path in (("SETTINGS_PEOPLE_RENAME_PATH", "/settings/people/rename"),
                           ("SETTINGS_PEOPLE_FORGET_PATH", "/settings/people/forget"),
                           ("SETTINGS_PEOPLE_FACE_PATH", "/settings/people/face")):
            self.assertRegex(src, rf'public static final String {name} = "{re.escape(path)}";')

    def test_manifest_declares_exported_service_with_action(self):
        manifest = MANIFEST.read_text()
        m = re.search(r'<service android:name="\.PeopleService"([^>]*)>(.*?)</service>', manifest, flags=re.S)
        self.assertIsNotNone(m, "manifest has no PeopleService")
        self.assertIn('android:exported="true"', m.group(1))
        self.assertIn('<action android:name="com.miko3.launcher.ROBOT_PEOPLE"/>', m.group(2))

    def test_launcher_owns_one_store_in_private_files(self):
        app = _read(APP)
        self.assertEqual(app.count("new PeopleStore("), 1)
        self.assertRegex(app, r"new PeopleStore\(\s*new File\(\s*getFilesDir\(\)")
        self.assertIn("PeopleStore people()", app)

    def test_launcher_routes_the_people_paths(self):
        app = _read(APP)
        for name in ("SETTINGS_PEOPLE_RENAME_PATH", "SETTINGS_PEOPLE_FORGET_PATH", "SETTINGS_PEOPLE_FACE_PATH"):
            self.assertRegex(app, rf"server\.route\(\s*LauncherProtocol\.{name}\s*,")

    def test_face_route_is_tls_only(self):
        # Under /settings, so RoutingHttpServer never serves a face in cleartext.
        src = _read(PROTOCOL)
        m = re.search(r'SETTINGS_PEOPLE_FACE_PATH = "([^"]+)"', src)
        self.assertIsNotNone(m)
        self.assertTrue(m.group(1).startswith("/settings/"))


if __name__ == "__main__":
    unittest.main()
