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
FACE_CHECK = SHARED / "FaceCheck.java"
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
        "add_refuses_a_missing_name_and_writes_nothing",
        "legacy_nameless_index_row_still_loads_and_stays_out_of_the_gallery",
        "entries_are_cleaned_and_matched_on_normalised_text",
        "index_read_failure_skips_the_orphan_sweep",
        # Face plan U4: several photos, embeddings, and calls by id.
        "legacy_person_loads_with_one_pending_photo",
        "add_person_stores_photo_zero_with_its_embedding",
        "add_person_refusals_write_nothing",
        "sixth_photo_replaces_the_oldest_slot",
        "recent_and_face_answer_the_newest_photo",
        "add_photo_to_a_forgotten_id_is_refused_and_writes_nothing",
        "add_photo_refuses_a_bad_photo_or_embedding",
        "forget_removes_every_photo_and_the_faces_file",
        "load_keeps_slot_photos_and_faces_and_sweeps_the_rest",
        "ids_named_matches_full_name_or_first_word",
        "gallery_skips_nameless_records_and_carries_no_names",
        "deleting_the_only_photo_is_refused",
        "set_embedding_is_refused_for_a_replaced_slot_or_a_forgotten_id",
        "mark_unusable_clears_pending_and_is_refused_once_replaced",
        "embeddings_survive_reload_exactly",
        "a_corrupt_faces_file_reads_as_pending",
        "photo_by_slot_validates_id_and_slot",
        "forget_racing_photo_writes_leaves_nothing_behind",
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

    def test_forget_deletes_index_then_notes_then_faces_then_photos(self):
        # KTD10, face plan KTD4: once the index no longer names them, a crash
        # leaves only orphans; then notes, the .faces file, every photo slot.
        body = _method_body(_read(STORE), "synchronized boolean forget")
        self.assertIsNotNone(body)
        index = body.find("saveIndex")
        notes = body.find("notesFile(")
        faces = body.find("facesFile(")
        face = body.find("faceFile(")
        slots = body.find("photoFile(")
        self.assertGreaterEqual(index, 0, "forget never rewrites the index")
        self.assertGreater(notes, index, "notes deleted before the index rewrite")
        self.assertGreater(faces, notes, ".faces deleted before the notes")
        self.assertGreater(face, faces, "face deleted before the .faces file")
        self.assertGreater(slots, face, "slot photos never deleted, or deleted before slot 0")
        self.assertRegex(body[notes:], r"notesFile\(\s*id\s*\)\.delete\(\)")
        self.assertRegex(body[faces:], r"facesFile\(\s*id\s*\)\.delete\(\)")
        self.assertRegex(body[face:], r"faceFile\(\s*id\s*\)\.delete\(\)")
        self.assertRegex(body[face:], r"for \(int slot = 1; slot < MAX_PHOTOS; slot\+\+\)[^}]*photoFile\(\s*id,\s*slot\s*\)\.delete\(\)")

    def test_owned_file_pattern_covers_slots_and_faces(self):
        # KTD4: the orphan sweep must recognise every per-person file name.
        m = re.search(r'OWNED_FILE = Pattern\.compile\("((?:[^"\\]|\\.)*)"\)', _read(STORE))
        self.assertIsNotNone(m, "PeopleStore has no OWNED_FILE")
        owned = re.compile(m.group(1).encode().decode("unicode_escape"))
        pid = "0123456789abcdef"
        for name in (f"{pid}.jpg", f"{pid}-1.jpg", f"{pid}-4.jpg", f"{pid}.json", f"{pid}.faces"):
            self.assertTrue(owned.fullmatch(name), name)
            self.assertEqual(owned.fullmatch(name).group(1), pid)
        for name in (f"{pid}-0.jpg", f"{pid}-5.jpg", f"{pid}.faces.tmp", "people.index", "x.jpg"):
            self.assertIsNone(owned.fullmatch(name), name)

    def test_load_sweeps_orphans_and_the_gallery_skips_nameless(self):
        src = _read(STORE)
        load = _method_body(src, "private void load")
        self.assertIn("delete()", load or "")
        recent = _method_body(src, "synchronized List<Person> recent")
        self.assertRegex(recent or "", r"name\.isEmpty\(\)")

    def test_add_refuses_a_missing_name_before_any_write(self):
        """R19: a person is only stored once he has a name; the refusal comes
        before the face file or the index row is written, and the loader still
        keeps legacy nameless rows (plan line 322)."""
        src = _read(STORE)
        self.assertRegex(src, r'static final String REFUSE_NO_NAME = "[^"]+";')
        add = _method_body(src, "synchronized String add") or ""
        self.assertIn("REFUSE_NO_NAME", add)
        self.assertLess(add.index("REFUSE_NO_NAME"), add.index("writeDurably"))
        read = _method_body(src, "private boolean readIndex") or ""
        self.assertNotIn("REFUSE_NO_NAME", read)
        self.assertNotIn("isEmpty()", read)

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
                       "public boolean forget", "public RobotPeople.GalleryPhoto[] gallery",
                       "public byte[] photo", "public String[] idsNamed", "public int addPhoto",
                       "public String addPerson", "public boolean setEmbedding", "public boolean markUnusable"):
            with self.subTest(method=method):
                body = _method_body(self.src, method)
                self.assertIsNotNone(body, f"PeopleService does not implement {method}")
                check, store = body.find("enforceCaller("), body.find("people()")
                self.assertGreaterEqual(check, 0, f"{method} never checks the caller")
                self.assertGreater(store, check, f"{method} touches the store before the caller check")

    def test_check_calls_check_the_caller_before_the_ring(self):
        # Face plan U5 (KTD8): the face-check ring is reached only after the caller check.
        for method in ("public long recordCheck", "public boolean updateCheck"):
            with self.subTest(method=method):
                body = _method_body(self.src, method)
                self.assertIsNotNone(body, f"PeopleService does not implement {method}")
                check, ring = body.find("enforceCaller("), body.find("checks()")
                self.assertGreaterEqual(check, 0, f"{method} never checks the caller")
                self.assertGreater(ring, check, f"{method} touches the ring before the caller check")

    def test_update_check_closes_as_ended_only_through_the_ring_rule(self):
        body = _method_body(self.src, "public boolean updateCheck") or ""
        self.assertIn("FaceCheck.ENDED_WITHOUT_ANSWER", body)
        self.assertIn(".closeAsEnded(", body)
        self.assertIn(".updateOutcome(", body)

    def test_forget_purges_the_face_checks(self):
        # R19: forgetting a person removes every check that matched or joined them.
        body = _method_body(self.src, "public boolean forget") or ""
        self.assertIn("FaceChecks.forget(people(), checks(), id)", body)
        shared = _method_body((LAUNCHER / "FaceChecks.java").read_text(), "static boolean forget") or ""
        self.assertLess(shared.find("people.forget("), shared.find(".purgePerson("))
        self.assertGreaterEqual(shared.find("people.forget("), 0)

    def test_caller_gate_is_reused(self):
        self.assertIn("CallerGate.enforce(", self.src)

    def test_gallery_and_ids_are_capped_by_the_interface_limits(self):
        gallery = _method_body(self.src, "public RobotPeople.GalleryPhoto[] gallery") or ""
        self.assertIn("RobotPeople.MAX_GALLERY", gallery)
        named = _method_body(self.src, "public String[] idsNamed") or ""
        self.assertIn("RobotPeople.MAX_IDS_NAMED", named)

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

    # Face plan U4 (KTD10-KTD12) appends 8-14; later units append after them.
    FACE_TRANSACTIONS = (("gallery", 8), ("photo", 9), ("idsNamed", 10), ("addPhoto", 11),
                         ("addPerson", 12), ("setEmbedding", 13), ("markUnusable", 14),
                         # Face plan U5 (KTD8): the face-check ring.
                         ("recordCheck", 15), ("updateCheck", 16))

    def test_transaction_codes_are_appended(self):
        # KTD10: the existing codes are unchanged; new ones only ever follow.
        src = _read(INTERFACE)
        for name, code in (("recent", 1), ("add", 2), ("touch", 3), ("nameOf", 4),
                           ("notesOf", 5), ("mergeNotes", 6), ("forget", 7)) + self.FACE_TRANSACTIONS:
            self.assertRegex(src, rf"TRANSACTION_{name}\s*=\s*{code}\s*;")
        codes = [int(c) for c in re.findall(r"TRANSACTION_\w+\s*=\s*(\d+)\s*;", src)]
        self.assertEqual(codes, list(range(1, len(codes) + 1)))

    def test_interface_declares_the_face_calls(self):
        body = _read(INTERFACE).split("abstract class Stub", 1)[0]
        for pattern in (r"GalleryPhoto\[\] gallery\(\) throws RemoteException;",
                        r"byte\[\] photo\(String \w+, int \w+\) throws RemoteException;",
                        r"String\[\] idsNamed\(String \w+\) throws RemoteException;",
                        r"int addPhoto\(String \w+, byte\[\] \w+, String \w+, float\[\] \w+\) throws RemoteException;",
                        r"String addPerson\(byte\[\] \w+, String \w+, String \w+, float\[\] \w+\) throws RemoteException;",
                        r"boolean setEmbedding\(String \w+, int \w+, long \w+, String \w+, float\[\] \w+\)\s+throws RemoteException;",
                        r"boolean markUnusable\(String \w+, int \w+, long \w+\) throws RemoteException;",
                        r"long recordCheck\(FaceCheck \w+\) throws RemoteException;",
                        r"boolean updateCheck\(long \w+, int \w+, String \w+\) throws RemoteException;"):
            self.assertRegex(body, pattern)
        # KTD10: names leave only through nameOf; the gallery record has no name.
        gallery = body[body.index("class GalleryPhoto"):]
        gallery = gallery[:gallery.index("\n    }\n")]
        self.assertNotRegex(gallery, r"(?i)\bString\s+\w*name")

    def test_every_new_stub_case_reads_its_interface_token(self):
        src = _read(INTERFACE)
        stub = src[src.index("public boolean onTransact"):src.index("class Proxy")]
        for name, _ in self.FACE_TRANSACTIONS:
            with self.subTest(transaction=name):
                m = re.search(rf"case TRANSACTION_{name}: \{{\s*data\.enforceInterface\(DESCRIPTOR\);", stub)
                self.assertIsNotNone(m, f"Stub has no guarded case for {name}")

    def test_gallery_reply_stays_well_under_the_binder_limit(self):
        src = _read(INTERFACE)
        cap = re.search(r"MAX_GALLERY\s*=\s*([0-9 *]+);", src)
        floats = re.search(r"MAX_EMBEDDING_FLOATS\s*=\s*([0-9 *]+);", _read(STORE))
        self.assertIsNotNone(cap)
        self.assertIsNotNone(floats)
        # Each entry: floats, a 16-char id and a short model id as UTF-16, a few ints.
        self.assertLessEqual(eval(cap.group(1)) * (4 * eval(floats.group(1)) + 2 * (16 + 64) + 64), 512 * 1024)
        self.assertRegex(src, r"MAX_IDS_NAMED\s*=\s*\d+\s*;")
        proxy = src[src.index("class Proxy"):]
        self.assertIn("MAX_GALLERY", _method_body(proxy, "public GalleryPhoto[] gallery") or "")
        self.assertIn("MAX_IDS_NAMED", _method_body(proxy, "public String[] idsNamed") or "")

    def test_new_proxy_methods_check_the_transaction_result(self):
        src = _read(INTERFACE)
        proxy = src[src.index("class Proxy"):]
        for name in ("notesOf", "mergeNotes", "forget") + tuple(n for n, _ in self.FACE_TRANSACTIONS):
            with self.subTest(method=name):
                decl = re.search(rf"public [\w\[\]]+ {name}\(", proxy)
                body = _method_body(proxy[decl.start():], decl.group(0)[:-1]) if decl else None
                self.assertIsNotNone(body, f"Proxy does not implement {name}")
                self.assertRegex(body, rf"if\s*\(\s*!remote\.transact\(\s*TRANSACTION_{name}")
                self.assertIn("LauncherProtocol.LAUNCHER_TOO_OLD", body)

    def test_client_has_notes_and_forget_and_reports_an_old_launcher(self):
        src = _read(CLIENT)
        for needle in ("PersonNotes notesOf(", "PersonNotes mergeNotes(", "boolean forget(",
                       "catch (UnsupportedOperationException", "LauncherProtocol.LAUNCHER_TOO_OLD"):
            self.assertIn(needle, src)

    def test_client_has_the_face_calls(self):
        src = _read(CLIENT)
        for needle in ("RobotPeople.GalleryPhoto[] gallery(Context", "byte[] photo(Context", "String[] idsNamed(Context",
                       "int addPhoto(Context", "String addPerson(Context", "boolean setEmbedding(Context",
                       "boolean markUnusable(Context", "long recordCheck(Context", "boolean updateCheck(Context"):
            self.assertIn(needle, src)

    def test_face_check_is_plain_java_and_carries_ids_not_names(self):
        raw = FACE_CHECK.read_text() if FACE_CHECK.exists() else ""
        self.assertTrue(raw, "FaceCheck.java missing")
        self.assertEqual([ln for ln in raw.splitlines() if ln.startswith("import android")], [])
        self.assertNotRegex(_strip_comments(raw), r"(?i)\bString\s+\w*name")
        # KTD8: the crop's cap is the stored photo's cap.
        self.assertRegex(_strip_comments(raw), r"MAX_CROP_BYTES\s*=\s*40\s*\*\s*1024\s*;")

    def test_record_check_reads_the_crop_bytes(self):
        src = _read(INTERFACE)
        stub = src[src.index("case TRANSACTION_recordCheck"):]
        stub = stub[:stub.index("return true;")]
        self.assertIn("createByteArray()", stub)

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
