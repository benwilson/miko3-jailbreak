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

    def test_nothing_is_logged(self):
        for path in (STORE, SERVICE, INTERFACE, CLIENT):
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
                       "public String nameOf"):
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
