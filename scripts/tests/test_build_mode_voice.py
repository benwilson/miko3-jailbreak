#!/usr/bin/env python3
"""Tests for scripts/build-mode-voice.py (U1: wake-word engine; U6: mode app; U8: engine).

Covers the vendor-library precondition (a missing .so fails with a message
naming it, before any toolchain work), the vendored JNI surface (package and
native signatures must match ServiceExam's recognizer.WakeWord exactly, or the
library's JNI symbols will not bind), the copied model asset, and, when the
Android toolchain is installed, a real build whose APK must carry all three
vendor libraries under lib/arm64-v8a/ plus the model under assets/, and (U6)
a manifest with RECORD_AUDIO but no CAMERA, the network security config, and
the settings page's assets. U8 replaced U1's spike Activity with the mode's
own listening path: the spike's source and manifest entry must be gone and
the voice engine and conversation client must be in the dex.

SettingsHarnessTest (U6, KTD10) compiles the mode's settings helpers for the
host JVM (no Android harness exists; none of those classes touch android.*)
and runs fixtures/voice_settings_harness, which prints one PASS/FAIL line per
scenario: address validation, the page-token check, and form handling.
"""
import hashlib
import importlib.util
import re
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock

TESTS = Path(__file__).resolve().parent
if str(TESTS) not in sys.path:
    sys.path.insert(0, str(TESTS))
import jvm_harness  # noqa: E402

REPO = Path(__file__).resolve().parents[2]
BUILD_PY = REPO / "scripts" / "build-mode-voice.py"
VENDOR_ROOT = REPO / "tools" / "serviceexam_jadx"
VENDOR_LIB_DIR = VENDOR_ROOT / "resources" / "lib" / "arm64-v8a"
VENDOR_WAKEWORD = VENDOR_ROOT / "sources" / "recognizer" / "WakeWord.java"
VENDOR_MODEL = VENDOR_ROOT / "resources" / "assets" / "miko_wakeword_model.tflite"
OUR_WAKEWORD = REPO / "mode-voice" / "src" / "recognizer" / "WakeWord.java"
OUR_MODEL = REPO / "mode-voice" / "assets" / "miko_wakeword_model.tflite"
VOICE_SRC = REPO / "mode-voice" / "src"
SHARED_SRC = REPO / "shared" / "src"
HARNESS = REPO / "scripts" / "tests" / "fixtures" / "voice_settings_harness" / "src"
HARNESS_MAIN = HARNESS / "com" / "miko3" / "mode" / "voice" / "VoiceSettingsHarness.java"

WAKEWORD_LIBS = (
    "libnative_wakeword_vad_lib.so",
    "libncnn.so",
    "libtensorflowlite_gpu_delegate.so",
)


def load_build_module():
    spec = importlib.util.spec_from_file_location("build_mode_voice", BUILD_PY)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load build module from {BUILD_PY}")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


build = load_build_module()


def _sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


class VendorLibsTest(unittest.TestCase):
    def test_declares_exactly_the_three_wakeword_libs(self):
        self.assertEqual(sorted(build.WAKEWORD_LIBS), sorted(WAKEWORD_LIBS))

    def test_all_present_returns_arm64_entries(self):
        libs = build.vendor_native_libs(VENDOR_LIB_DIR)
        self.assertEqual(sorted(Path(p).name for _, p in libs), sorted(WAKEWORD_LIBS))
        for abi, path in libs:
            self.assertEqual(abi, "arm64-v8a")
            self.assertTrue(Path(path).is_file(), path)

    def test_missing_lib_fails_with_clear_message(self):
        """A vendor .so absent from the extracted resources is named, with where it was expected."""
        with tempfile.TemporaryDirectory() as td:
            td = Path(td)
            for name in WAKEWORD_LIBS[:2]:
                (td / name).write_bytes(b"\x7fELF")
            with self.assertRaises(build.BuildError) as ctx:
                build.vendor_native_libs(td)
        msg = str(ctx.exception)
        self.assertIn("libtensorflowlite_gpu_delegate.so", msg)
        self.assertIn(str(td), msg)
        self.assertNotIn("libncnn.so", msg, "a present library must not be reported missing")

    def test_every_missing_lib_is_listed(self):
        with tempfile.TemporaryDirectory() as td:
            with self.assertRaises(build.BuildError) as ctx:
                build.vendor_native_libs(Path(td))
        for name in WAKEWORD_LIBS:
            self.assertIn(name, str(ctx.exception))

    def test_missing_lib_fails_before_any_toolchain_work(self):
        """The precondition is checked first, so a missing lib never costs a javac run."""
        with tempfile.TemporaryDirectory() as td:
            with mock.patch.object(build.bc, "ensure_toolchain") as tc, \
                    mock.patch.object(build.bc, "build_apk") as ba:
                with self.assertRaises(build.BuildError):
                    build.build(lib_dir=Path(td), bootstrap=False)
        tc.assert_not_called()
        ba.assert_not_called()


class SharedAssetsTest(unittest.TestCase):
    """build_common.stage_assets's exclude, which this build passes SHARED_ASSETS_EXCLUDED."""

    def _sources(self, td):
        app, shared = Path(td) / "app", Path(td) / "shared"
        app.mkdir()
        shared.mkdir()
        (app / "miko_wakeword_model.tflite").write_bytes(b"x")
        for name in ("pico.min.css", "server.p12", "danger-zone.mp3"):
            (shared / name).write_bytes(b"x")
        return [app, shared]

    def test_stages_everything_but_the_excluded_song(self):
        with tempfile.TemporaryDirectory() as td:
            out = build.bc.stage_assets(self._sources(td), Path(td) / "build",
                                        exclude=build.SHARED_ASSETS_EXCLUDED)
            self.assertEqual(sorted(p.name for p in out.iterdir()),
                             ["miko_wakeword_model.tflite", "pico.min.css", "server.p12"])

    def test_default_exclude_stages_everything(self):
        """The other apps' builds pass no exclude and keep every asset."""
        with tempfile.TemporaryDirectory() as td:
            out = build.bc.stage_assets(self._sources(td), Path(td) / "build")
            self.assertIn("danger-zone.mp3", {p.name for p in out.iterdir()})

    def test_only_excluded_assets_is_no_assets_dir(self):
        with tempfile.TemporaryDirectory() as td:
            src = Path(td) / "src"
            src.mkdir()
            (src / "danger-zone.mp3").write_bytes(b"x")
            self.assertIsNone(build.bc.stage_assets([src], Path(td) / "build",
                                                    exclude=build.SHARED_ASSETS_EXCLUDED))

    def test_real_shared_assets_keep_settings_page_and_https_files(self):
        with tempfile.TemporaryDirectory() as td:
            out = build.bc.stage_assets([build.SHARED_ASSETS], Path(td),
                                        exclude=build.SHARED_ASSETS_EXCLUDED)
            names = {p.name for p in out.iterdir()}
        self.assertIn("pico.min.css", names)
        self.assertIn("server.p12", names)
        self.assertNotIn("danger-zone.mp3", names)


class VendoredSourceTest(unittest.TestCase):
    NATIVE_RE = re.compile(r"^\s*public native [^;]+;", re.M)

    def test_wakeword_lives_in_recognizer_package(self):
        src = OUR_WAKEWORD.read_text()
        self.assertRegex(src, r"(?m)^package recognizer;")
        self.assertRegex(src, r"public class WakeWord\b")
        self.assertTrue('System.loadLibrary("native_wakeword_vad_lib")' in src,
                        "WakeWord must load the vendor library in its static initializer")

    def test_native_signatures_match_vendor_verbatim(self):
        ours = sorted(m.strip() for m in self.NATIVE_RE.findall(OUR_WAKEWORD.read_text()))
        vendor = sorted(m.strip() for m in self.NATIVE_RE.findall(VENDOR_WAKEWORD.read_text()))
        self.assertTrue(vendor, "vendor WakeWord.java has no natives -- wrong path?")
        self.assertEqual(ours, vendor)

    def test_spike_activity_removed(self):
        """Definition of Done: U1's spike Activity is dead code once U8's engine lands."""
        voice = VOICE_SRC / "com" / "miko3" / "mode" / "voice"
        self.assertFalse((voice / "WakeWordSpikeActivity.java").exists())
        self.assertNotIn("WakeWordSpikeActivity", (REPO / "mode-voice" / "AndroidManifest.xml").read_text())
        for name in ("VoiceEngine.java", "ConversationClient.java"):
            self.assertTrue((voice / name).is_file(), f"{name} missing")

    def test_model_asset_is_the_vendor_live_model(self):
        """KTD7: the three-class model KeywordTask2 loads, byte-identical."""
        self.assertTrue(OUR_MODEL.is_file(), f"model asset missing at {OUR_MODEL}")
        self.assertEqual(_sha256(OUR_MODEL), _sha256(VENDOR_MODEL))


class ApkContentsTest(unittest.TestCase):
    """Builds into a scratch dir (never touches mode-voice/'s own APK or keystore)."""

    @classmethod
    def setUpClass(cls):
        try:
            build.bc.ensure_toolchain(build.bc.find_sdk(None), bootstrap=False)
        except build.BuildError as exc:
            raise unittest.SkipTest(f"Android toolchain unavailable: {exc}")
        cls._td = tempfile.TemporaryDirectory()
        td = Path(cls._td.name)
        cls.bt = build.bc.ensure_toolchain(build.bc.find_sdk(None), bootstrap=False)[1]
        cls.apk = build.build(bootstrap=False, apk_out=td / "voice.apk",
                              build_dir=td / "build", keystore=td / "test.keystore")
        with zipfile.ZipFile(cls.apk) as z:
            cls.names = set(z.namelist())

    @classmethod
    def tearDownClass(cls):
        cls._td.cleanup()

    def test_apk_carries_all_three_vendor_libs(self):
        for name in WAKEWORD_LIBS:
            self.assertIn(f"lib/arm64-v8a/{name}", self.names)

    def test_apk_carries_model_asset(self):
        self.assertIn("assets/miko_wakeword_model.tflite", self.names)

    def test_apk_has_dex(self):
        self.assertIn("classes.dex", self.names)

    def _manifest_tree(self):
        r = subprocess.run([str(self.bt / "aapt2"), "dump", "xmltree", "--file", "AndroidManifest.xml",
                            str(self.apk)], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stderr)
        return r.stdout

    def test_manifest_requests_record_audio_not_camera(self):
        tree = self._manifest_tree()
        self.assertIn("android.permission.RECORD_AUDIO", tree)
        self.assertIn("android.permission.INTERNET", tree)
        self.assertNotIn("CAMERA", tree, "R18/Implementation Constraints: the voice mode never opens the camera")

    def test_manifest_declares_mode_app_and_main_activity(self):
        tree = self._manifest_tree()
        # aapt2 keeps the manifest's relative names; resolved against package=.
        self.assertIn('package="com.miko3.mode.voice"', tree)
        self.assertIn('".ModeApp"', tree)
        self.assertIn('".MainActivity"', tree)
        with zipfile.ZipFile(self.apk) as z:
            dex = z.read("classes.dex")
        for cls in ("ModeApp", "MainActivity", "VoiceEngine", "ConversationClient"):
            # assertTrue, not assertIn: a failing assertIn would print the whole dex.
            self.assertTrue(f"Lcom/miko3/mode/voice/{cls};".encode() in dex, f"{cls} missing from classes.dex")
        self.assertFalse(b"WakeWordSpikeActivity" in dex, "U1's spike Activity is removed in U8")
        self.assertNotIn("WakeWordSpikeActivity", tree)
        self.assertIn("android.intent.action.MAIN", tree)
        self.assertNotIn("android.intent.category.LAUNCHER", tree,
                         "launched by the custom launcher's explicit Intent, never from a stock app drawer")

    def test_apk_carries_network_security_config(self):
        self.assertIn("res/xml/network_security_config.xml", self.names)
        self.assertIn("networkSecurityConfig", self._manifest_tree())

    def test_apk_carries_settings_page_and_https_assets(self):
        self.assertIn("assets/pico.min.css", self.names)
        self.assertIn("assets/server.p12", self.names)

    def test_apk_omits_remote_control_song(self):
        self.assertNotIn("assets/danger-zone.mp3", self.names)


class SettingsHarnessTest(unittest.TestCase):
    """KTD10 settings page on the host JVM; one harness run, one assertion per scenario."""

    SCENARIOS = (
        "valid_private_address_saves",
        "saved_address_is_trimmed_and_normalized",
        "missing_port_refused",
        "public_ip_refused",
        "invalid_value_does_not_change_turn_taking",
        "post_without_token_refused",
        "post_with_token_in_query_only_refused",
        "token_from_other_page_load_refused",
        "forged_token_refused",
        "listener_fires_once_on_address_change",
        "removed_listener_not_called",
        "blank_address_clears_and_notifies",
        "oversized_form_refused",
        "get_renders_form_with_token_and_state",
        "get_marks_turn_taking_checked",
        "other_methods_refused",
        "exit_with_issued_token_goes_to_launcher",
        "exit_without_token_refused",
        "exit_with_stale_token_refused",
        "defaults",
        "tuning_keys_read_from_store",
        "wake_trim_zero_is_kept_and_nonsense_is_not",
        "address_parser_accepts_private_and_link_local",
        "address_parser_refuses_bad_input",
        "address_parser_fields",
        "connect_time_check_on_resolved_ip",
    )

    @classmethod
    def setUpClass(cls):
        jdk = jvm_harness.find_jdk()
        if jdk is None:
            raise unittest.SkipTest("no JDK (javac + java) found")
        cls._td = tempfile.TemporaryDirectory(prefix="voice_settings_harness_")
        out = cls._td.name
        c = subprocess.run(jvm_harness.javac_cmd(jdk[0], out, [HARNESS_MAIN], [HARNESS, VOICE_SRC, SHARED_SRC]),
                           capture_output=True, text=True)
        cls.compiled = c.returncode == 0
        cls.compile_output = (c.stdout + c.stderr)[-3000:]
        cls.results = {}
        cls.run_output = ""
        if c.returncode == 0:
            r = subprocess.run([jdk[1], "-cp", out, "com.miko3.mode.voice.VoiceSettingsHarness"],
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


jvm_harness.add_scenario_tests(SettingsHarnessTest)


if __name__ == "__main__":
    unittest.main()
