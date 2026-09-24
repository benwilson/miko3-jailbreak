"""Host-side tests for the launcher's settings service for modes (settings plan
U5, KTD1; R13, R14, R16): the plain-Java caller check and the "not set up
yet" answer run under a JVM harness, the pinned certificate digests are
recomputed from the committed keystores, and source-wiring checks cover the
Android glue (service, Binder interface, manifest, client helper), which host
tests can't run."""
import hashlib
import re
import shutil
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
HARNESS = TESTS / "fixtures" / "robot_settings_service_harness" / "src"
HARNESS_MAIN = HARNESS / "com" / "miko3" / "launcher" / "RobotSettingsServiceHarness.java"
CALLER_CHECK = LAUNCHER / "CallerCheck.java"
CALLER_GATE = LAUNCHER / "CallerGate.java"
SERVICE = LAUNCHER / "RobotSettingsService.java"
INTERFACE = SHARED / "RobotSettings.java"
ACCESS = SHARED / "ClaudeAccess.java"
CLIENT = SHARED / "RobotSettingsClient.java"
PROTOCOL = SHARED / "LauncherProtocol.java"
MANIFEST = REPO / "launcher" / "AndroidManifest.xml"

# (package, keystore, alias and store password) — the same values the
# scripts/build-*.py files sign with.
MIKO3_APPS = (
    ("com.miko3.launcher", REPO / "launcher" / "miko3-launcher.keystore", "miko3launcher"),
    ("com.miko3.mode.voice", REPO / "mode-voice" / "miko3-mode-voice.keystore", "miko3modevoice"),
    ("com.miko3.mode.explore", REPO / "mode-explore" / "miko3-mode-explore.keystore", "miko3modeexplore"),
    ("com.miko3.mode.remotecontrol", REPO / "mode-remote-control" / "miko3-mode-remote-control.keystore",
     "miko3moderemotecontrol"),
)


def _strip_comments(text):
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def _read(path):
    return _strip_comments(path.read_text()) if path.exists() else ""


class RobotSettingsServiceHarnessTest(unittest.TestCase):
    SCENARIOS = (
        "each_miko3_app_with_its_pinned_digest_is_allowed",
        "miko3_name_with_wrong_digest_is_denied",
        "miko3_name_with_another_apps_digest_is_denied",
        "extra_unpinned_signer_is_denied",
        "package_with_no_signers_is_denied",
        "unknown_package_is_denied",
        "uid_with_no_packages_is_denied",
        "shared_uid_with_one_pinned_package_is_allowed",
        "digest_compare_ignores_hex_case",
        "pins_cover_exactly_the_four_apps",
        "forget_key_answers_not_set_up",
        "before_setup_answers_not_set_up",
        "set_up_answer_carries_current_values",
        "model_change_shows_on_next_call",
        "access_to_string_never_shows_key",
    )

    @classmethod
    def setUpClass(cls):
        jdk = jvm_harness.find_jdk()
        if jdk is None:
            raise unittest.SkipTest("no JDK (javac + java) found")
        cls._td = tempfile.TemporaryDirectory(prefix="robot_settings_service_harness_")
        out = cls._td.name
        # javac pulls in only what the harness references: CallerCheck,
        # ClaudeSettings and ClaudeAccess, never the Android-bound service.
        c = subprocess.run(jvm_harness.javac_cmd(jdk[0], out, [HARNESS_MAIN],
                                                 [HARNESS, LAUNCHER_SRC, SHARED_SRC]),
                           capture_output=True, text=True)
        cls.compiled = c.returncode == 0
        cls.compile_output = (c.stdout + c.stderr)[-3000:]
        cls.results = {}
        cls.run_output = ""
        if c.returncode == 0:
            r = subprocess.run([jdk[1], "-cp", out, "com.miko3.launcher.RobotSettingsServiceHarness"],
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


jvm_harness.add_scenario_tests(RobotSettingsServiceHarnessTest)


class PinnedDigestTest(unittest.TestCase):
    """The pins in CallerCheck are the SHA-256 of each committed keystore's
    signing certificate (KTD1), so a pin can't silently drift from the key
    the build signs with."""

    @classmethod
    def setUpClass(cls):
        cls.src = _read(CALLER_CHECK)

    def _pin_for(self, package):
        m = re.search(r'"' + re.escape(package) + r'"\s*,\s*"([0-9a-fA-F]{64})"', self.src)
        return m.group(1).lower() if m else None

    def test_pins_match_committed_keystores(self):
        keytool = shutil.which("keytool")
        if keytool is None:
            self.skipTest("no keytool on PATH")
        for package, keystore, alias in MIKO3_APPS:
            with self.subTest(package=package), tempfile.TemporaryDirectory() as td:
                der = Path(td) / "cert.der"
                subprocess.run([keytool, "-exportcert", "-keystore", str(keystore), "-storepass", alias,
                                "-alias", alias, "-file", str(der)],
                               check=True, capture_output=True)
                expected = hashlib.sha256(der.read_bytes()).hexdigest()
                self.assertEqual(self._pin_for(package), expected)

    def test_regeneration_is_documented(self):
        raw = CALLER_CHECK.read_text() if CALLER_CHECK.exists() else ""
        self.assertIn("keytool -exportcert", raw)

    def test_no_android_imports(self):
        raw = CALLER_CHECK.read_text() if CALLER_CHECK.exists() else ""
        self.assertTrue(raw)
        self.assertEqual([ln for ln in raw.splitlines() if ln.startswith("import android")], [])


class ServiceWiringTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.src = _read(SERVICE)
        cls.iface = _read(INTERFACE)

    def _interface_methods(self):
        body = self.iface.split("abstract class Stub", 1)[0]
        return re.findall(r"^\s+\w[\w<>\[\]]*\s+(\w+)\([^)]*\)\s+throws RemoteException;", body, flags=re.M)

    def test_interface_has_methods(self):
        self.assertTrue(self._interface_methods(), "RobotSettings declares no methods")

    def test_every_method_checks_the_caller_before_reading_settings(self):
        methods = self._interface_methods()
        for name in methods:
            with self.subTest(method=name):
                m = re.search(r"public\s+\w+\s+" + name + r"\(\)[^{]*\{(.*?)\n        \}", self.src, flags=re.S)
                self.assertIsNotNone(m, f"service does not implement {name}")
                body = m.group(1)
                check = body.find("enforceCaller(")
                read = body.find("credentialsForRequests(")
                self.assertGreaterEqual(check, 0, f"{name} never checks the caller")
                self.assertGreater(read, check, f"{name} reads the store before the caller check")

    def test_settings_read_only_inside_checked_methods(self):
        # R13: read on every call, no cached copy that a Save would leave stale.
        self.assertEqual(self.src.count("credentialsForRequests("), len(self._interface_methods()))
        self.assertNotRegex(self.src, r"private\s+(final\s+)?ClaudeSettings\.Credentials\s+\w+\s*;")

    def test_caller_identity_comes_from_binder_and_package_manager(self):
        # The Android lookups live in CallerGate, shared with SpeechService.
        self.assertIn("CallerGate.enforce(", self.src)
        gate = _read(CALLER_GATE)
        for needle in ("Binder.getCallingUid()", "getPackagesForUid(", "GET_SIGNATURES",
                       'MessageDigest.getInstance("SHA-256")', "CallerCheck.allows(",
                       "throw new SecurityException("):
            self.assertIn(needle, gate)

    def test_not_set_up_answer_when_store_is_not_set_up(self):
        # The answer rule the harness exercises (AE7) is the one the service returns.
        self.assertIn("CallerCheck.accessFor(", self.src)
        # accessFor defers to ClaudeAccess.setUp, which answers "not set up"
        # when the base URL, key or model is blank.
        self.assertIn("ClaudeAccess.setUp(", _read(CALLER_CHECK))
        access = _read(ACCESS)
        self.assertRegex(access, r"isBlank\(baseUrl\)\s*\|\|\s*isBlank\(apiKey\)\s*\|\|\s*isBlank\(model\)")
        self.assertIn("return NOT_SET_UP;", access)

    def test_no_log_call_touches_the_key(self):
        # R14: no Log line (or anything else printing) mentions the key.
        for path in (SERVICE, INTERFACE, ACCESS, CLIENT, CALLER_CHECK, CALLER_GATE):
            src = _read(path)
            with self.subTest(file=path.name):
                self.assertTrue(src, f"{path.name} missing")
                for stmt in re.findall(r"Log\.\w\([^;]*;", src, flags=re.S):
                    self.assertNotRegex(stmt, r"(?i)apiKey|key\b|credentials|access\b|ClaudeAccess",
                                        f"Log call may touch the key: {stmt}")
                for needle in ("System.out", "System.err", "printStackTrace"):
                    self.assertNotIn(needle, src)

    def test_uses_the_launcher_settings_instance(self):
        self.assertIn("claudeSettings()", self.src)
        self.assertIn("(LauncherApp) getApplication()", self.src)


class ProtocolAndManifestTest(unittest.TestCase):
    def test_action_constant_declared(self):
        self.assertRegex(_read(PROTOCOL),
                         r'ROBOT_SETTINGS_ACTION\s*=\s*"com\.miko3\.launcher\.ROBOT_SETTINGS"')

    def test_manifest_declares_exported_service_with_action(self):
        manifest = MANIFEST.read_text()
        m = re.search(r'<service android:name="\.RobotSettingsService"([^>]*)>(.*?)</service>', manifest, flags=re.S)
        self.assertIsNotNone(m, "manifest has no RobotSettingsService")
        self.assertIn('android:exported="true"', m.group(1))
        self.assertIn('<action android:name="com.miko3.launcher.ROBOT_SETTINGS"/>', m.group(2))

    def test_service_binds_on_the_constant(self):
        self.assertIn("LauncherProtocol.ROBOT_SETTINGS_ACTION", _read(SERVICE))


class InterfaceAndClientTest(unittest.TestCase):
    def test_hand_written_binder_shape(self):
        src = _read(INTERFACE)
        for needle in ("extends IInterface", "abstract class Stub extends Binder", "class Proxy",
                       '"com.miko3.shared.RobotSettings"', "enforceInterface(DESCRIPTOR)",
                       "writeInterfaceToken(DESCRIPTOR)", "reply.readException()"):
            self.assertIn(needle, src)

    def test_client_binds_by_action_with_package_and_unbinds(self):
        src = _read(CLIENT)
        for needle in ("new Intent(LauncherProtocol.ROBOT_SETTINGS_ACTION)",
                       "setPackage(LauncherProtocol.LAUNCHER_PACKAGE)", "bindService(",
                       "unbindService(", ".await(", "RobotSettings.Stub.asInterface("):
            self.assertIn(needle, src)

    def test_client_refuses_the_main_thread(self):
        # onServiceConnected arrives on the main thread; waiting there would deadlock.
        self.assertIn("Looper.getMainLooper()", _read(CLIENT))

    def test_access_is_plain_java(self):
        raw = ACCESS.read_text() if ACCESS.exists() else ""
        self.assertTrue(raw)
        self.assertEqual([ln for ln in raw.splitlines() if ln.startswith("import android")], [])


if __name__ == "__main__":
    unittest.main()
