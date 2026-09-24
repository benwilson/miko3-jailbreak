"""Host-side tests for the launcher's speech service (voice plan U5; R8-R11,
AE2, AE3, KTD3-KTD5, KTD8).

The plain-Java SpeechQueue (queueing, per-caller cancel, dead callers,
sentence and comma splitting, refusals) and SpeechTuning run under a JVM
harness with a fake voice. The Android glue (the Binder service, the audio
thread, the client helper, the manifest) and the launcher build's sherpa-onnx
bundling are covered by source-wiring checks and by build-script unit tests,
since host tests can't run them.
"""
import importlib.util
import re
import subprocess
import sys
import tarfile
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
HARNESS = TESTS / "fixtures" / "speech_service_harness" / "src"
HARNESS_MAIN = HARNESS / "com" / "miko3" / "launcher" / "SpeechServiceHarness.java"
QUEUE = LAUNCHER / "SpeechQueue.java"
TUNING = LAUNCHER / "SpeechTuning.java"
SERVICE = LAUNCHER / "SpeechService.java"
ENGINE = LAUNCHER / "SpeechEngine.java"
GATE = LAUNCHER / "CallerGate.java"
APP = LAUNCHER / "LauncherApp.java"
INTERFACE = SHARED / "RobotSpeech.java"
CLIENT = SHARED / "RobotSpeechClient.java"
PROTOCOL = SHARED / "LauncherProtocol.java"
MANIFEST = REPO / "launcher" / "AndroidManifest.xml"
BUILD_PY = REPO / "scripts" / "build-custom-launcher.py"


def _strip_comments(text):
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def _read(path):
    return _strip_comments(path.read_text()) if path.exists() else ""


def _method_body(src, name):
    """Body of the first method called name (brace-matched), or None."""
    m = re.search(r"\b" + name + r"\([^)]*\)[^{;]*\{", src)
    if m is None:
        return None
    depth, i = 1, m.end()
    while depth and i < len(src):
        depth += {"{": 1, "}": -1}.get(src[i], 0)
        i += 1
    return src[m.end():i - 1]


class SpeechServiceHarnessTest(unittest.TestCase):
    SCENARIOS = (
        # AE3 / KTD5: queue, never cut off.
        "second_line_queues_behind_first",
        "first_callback_fires_before_second_starts",
        "finished_fires_once_per_line",
        # Cancel only touches the caller's own lines.
        "cancel_drops_only_callers_queued_lines",
        "cancel_leaves_other_callers_playing_line",
        "cancel_stops_own_playing_line_at_sentence_boundary",
        "cancel_with_nothing_queued_is_harmless",
        # linkToDeath path.
        "dead_caller_lines_are_dropped",
        # Splitting (R10, U1's chunk limit).
        "multi_sentence_line_one_chunk_per_sentence",
        "thirty_word_sentence_split_at_commas_within_limit",
        "chunk_limit_is_configurable",
        "clause_without_commas_split_at_word_limit",
        "abbreviation_dr_does_not_end_sentence",
        "decimal_number_does_not_end_sentence",
        "line_without_punctuation_is_one_chunk",
        "whitespace_is_collapsed",
        # Refusals.
        "empty_line_refused_with_fixed_reason",
        "blank_line_refused_with_fixed_reason",
        "overlong_line_refused_with_fixed_reason",
        "refused_line_queues_nothing",
        "unpinned_caller_is_denied",
        "shutdown_cancels_queued_and_refuses_new",
        "voice_failure_cancels_that_line_only",
        # Tuning (KTD8).
        "tuning_defaults",
        "tuning_overrides_and_clamps",
    )

    @classmethod
    def setUpClass(cls):
        jdk = jvm_harness.find_jdk()
        if jdk is None:
            raise unittest.SkipTest("no JDK (javac + java) found")
        cls._td = tempfile.TemporaryDirectory(prefix="speech_service_harness_")
        out = cls._td.name
        # javac pulls in only what the harness references: SpeechQueue,
        # SpeechTuning, CallerCheck. Never the Android-bound service or engine.
        c = subprocess.run(jvm_harness.javac_cmd(jdk[0], out, [HARNESS_MAIN],
                                                 [HARNESS, LAUNCHER_SRC, SHARED_SRC]),
                           capture_output=True, text=True)
        cls.compiled = c.returncode == 0
        cls.compile_output = (c.stdout + c.stderr)[-3000:]
        cls.results = {}
        cls.run_output = ""
        if c.returncode == 0:
            r = subprocess.run([jdk[1], "-cp", out, "com.miko3.launcher.SpeechServiceHarness"],
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


jvm_harness.add_scenario_tests(SpeechServiceHarnessTest)


class PlainJavaTest(unittest.TestCase):
    def test_queue_and_tuning_are_plain_java(self):
        for path in (QUEUE, TUNING):
            with self.subTest(file=path.name):
                raw = path.read_text() if path.exists() else ""
                self.assertTrue(raw, f"{path.name} missing")
                self.assertEqual([ln for ln in raw.splitlines() if ln.startswith("import android")], [])


class ServiceWiringTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.src = _read(SERVICE)

    def test_speak_checks_the_caller_before_queueing(self):
        body = _method_body(self.src, "public void speak")
        self.assertIsNotNone(body, "SpeechService does not implement speak")
        check, queue = body.find("enforceCaller("), body.find(".speak(")
        self.assertGreaterEqual(check, 0, "speak never checks the caller")
        self.assertGreater(queue, check, "speak queues before the caller check")

    def test_cancel_checks_the_caller_before_cancelling(self):
        body = _method_body(self.src, "public void cancel")
        self.assertIsNotNone(body, "SpeechService does not implement cancel")
        check, cancel = body.find("enforceCaller("), body.find(".cancel(")
        self.assertGreaterEqual(check, 0, "cancel never checks the caller")
        self.assertGreater(cancel, check, "cancel acts before the caller check")

    def test_caller_is_identified_by_binder_uid(self):
        self.assertIn("Binder.getCallingUid()", self.src)

    def test_dead_caller_is_cancelled_through_link_to_death(self):
        self.assertIn("linkToDeath(", self.src)
        self.assertIn("unlinkToDeath(", self.src)
        m = re.search(r"void binderDied\(\)\s*\{(.*?)\}", self.src, flags=re.S)
        self.assertIsNotNone(m, "no DeathRecipient.binderDied")
        self.assertIn("cancelLine(", m.group(1))

    def test_caller_gate_shared_with_settings_service(self):
        gate = _read(GATE)
        for needle in ("Binder.getCallingUid()", "getPackagesForUid(", "GET_SIGNATURES",
                       'MessageDigest.getInstance("SHA-256")', "CallerCheck.allows(",
                       "throw new SecurityException("):
            self.assertIn(needle, gate)
        self.assertIn("CallerGate.enforce(", self.src)
        self.assertIn("CallerGate.enforce(", _read(LAUNCHER / "RobotSettingsService.java"))

    def test_binds_on_the_constant(self):
        self.assertIn("LauncherProtocol.ROBOT_SPEECH_ACTION", self.src)

    def test_uses_the_launcher_engine(self):
        self.assertIn("(LauncherApp) getApplication()", self.src)
        self.assertIn("speech()", self.src)


class EngineWiringTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.src = _read(ENGINE)

    def test_audio_thread_runs_at_urgent_audio(self):
        self.assertIn("Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)", self.src)

    def test_line_end_judged_by_playback_head(self):
        self.assertIn("getPlaybackHeadPosition()", self.src)

    def test_plays_on_the_music_stream(self):
        self.assertIn("AudioAttributes.USAGE_MEDIA", self.src)
        self.assertIn("AudioTrack.MODE_STREAM", self.src)

    def test_sherpa_onnx_vits_one_sentence_per_callback(self):
        for needle in ("new OfflineTts(", "OfflineTtsVitsModelConfig", "setMaxNumSentences(1)",
                       "generateWithCallback(", "setNumThreads(tuning.threads)"):
            self.assertIn(needle, self.src)

    def test_voice_copied_out_of_assets_keyed_by_stamp(self):
        for needle in ('"voice/stamp.txt"', "getFilesDir()", "getAssets()"):
            self.assertIn(needle, self.src)

    def test_voice_loaded_once_off_the_main_thread(self):
        app = _read(APP)
        self.assertIn("new SpeechEngine(", app)
        self.assertEqual(app.count("new SpeechEngine("), 1)
        self.assertIn("new Thread(", self.src)
        self.assertIn(".start()", _method_body(self.src, "void start") or "")

    def test_no_logging_of_secrets(self):
        # Logging the spoken text is fine; the settings key never is.
        for path in (SERVICE, ENGINE, QUEUE, INTERFACE, CLIENT, GATE):
            src = _read(path)
            with self.subTest(file=path.name):
                self.assertTrue(src, f"{path.name} missing")
                for stmt in re.findall(r"Log\.\w\([^;]*;", src, flags=re.S):
                    self.assertNotRegex(stmt, r"(?i)apiKey|claudeSettings|ClaudeAccess|credentials",
                                        f"Log call may touch the settings key: {stmt}")
                for needle in ("System.out", "System.err", "printStackTrace"):
                    self.assertNotIn(needle, src)


class ProtocolAndManifestTest(unittest.TestCase):
    def test_action_constant_declared(self):
        self.assertRegex(_read(PROTOCOL), r'ROBOT_SPEECH_ACTION\s*=\s*"com\.miko3\.launcher\.ROBOT_SPEECH"')

    def test_manifest_declares_exported_service_with_action(self):
        manifest = MANIFEST.read_text()
        m = re.search(r'<service android:name="\.SpeechService"([^>]*)>(.*?)</service>', manifest, flags=re.S)
        self.assertIsNotNone(m, "manifest has no SpeechService")
        self.assertIn('android:exported="true"', m.group(1))
        self.assertIn('<action android:name="com.miko3.launcher.ROBOT_SPEECH"/>', m.group(2))


class InterfaceAndClientTest(unittest.TestCase):
    def test_hand_written_binder_shape(self):
        src = _read(INTERFACE)
        for needle in ("extends IInterface", "abstract class Stub extends Binder", "class Proxy",
                       '"com.miko3.shared.RobotSpeech"', '"com.miko3.shared.RobotSpeech.Callback"',
                       "enforceInterface(", "writeInterfaceToken(", "reply.readException()",
                       "writeStrongBinder(", "readStrongBinder()", "FLAG_ONEWAY"):
            self.assertIn(needle, src)

    def test_interface_declares_speak_and_cancel(self):
        body = _read(INTERFACE).split("abstract class Stub", 1)[0]
        self.assertRegex(body, r"void speak\(String \w+, Callback \w+\) throws RemoteException;")
        self.assertRegex(body, r"void cancel\(\) throws RemoteException;")

    def test_client_binds_by_action_and_keeps_binding_until_callback(self):
        src = _read(CLIENT)
        for needle in ("new Intent(LauncherProtocol.ROBOT_SPEECH_ACTION)",
                       "setPackage(LauncherProtocol.LAUNCHER_PACKAGE)", "bindService(",
                       "unbindService(", "RobotSpeech.Stub.asInterface("):
            self.assertIn(needle, src)
        # Unbinds only once no line is outstanding.
        release = _method_body(src, "private void lineDone")
        self.assertIsNotNone(release, "client has no lineDone()")
        self.assertIn("unbindService(", release)

    def test_client_documents_speaking_after_heavy_work(self):
        raw = CLIENT.read_text() if CLIENT.exists() else ""
        self.assertRegex(raw, r"(?is)heavy work.*pause")


def _load_build():
    spec = importlib.util.spec_from_file_location("build_custom_launcher", BUILD_PY)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


class BuildScriptTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.build = _load_build()
        cls.src = BUILD_PY.read_text()

    def test_pins_sherpa_onnx_version_and_checksums(self):
        b = self.build
        self.assertRegex(b.SHERPA_VERSION, r"^\d+\.\d+\.\d+$")
        for digest in (b.SHERPA_ANDROID_SHA256, b.SHERPA_JAR_SHA256, b.PLACEHOLDER_VOICE_SHA256):
            self.assertRegex(digest, r"^[0-9a-f]{64}$")
        self.assertIn(b.SHERPA_VERSION, b.SHERPA_ANDROID_URL)
        self.assertIn(b.SHERPA_VERSION, b.SHERPA_JAR_URL)

    def test_bundles_sherpas_own_onnx_runtime_and_jni(self):
        self.assertEqual(set(self.build.SHERPA_LIBS), {"libonnxruntime.so", "libsherpa-onnx-jni.so"})
        self.assertIn("native_libs=", self.src)
        self.assertIn("jars=", self.src)

    def test_downloads_into_gitignored_third_party(self):
        cache = self.build.SHERPA_CACHE.resolve()
        self.assertEqual(cache.parent, (REPO / "tools" / "third_party").resolve())
        self.assertIn("tools/third_party/", (REPO / ".gitignore").read_text())

    def test_checksum_mismatch_fails(self):
        with tempfile.TemporaryDirectory() as td:
            f = Path(td) / "x.bin"
            f.write_bytes(b"tampered")
            with self.assertRaises(self.build.BuildError):
                self.build.verified(f, "0" * 64)
            self.assertFalse(f.exists(), "a file failing its checksum must be deleted")

    def test_committed_voice_wins_over_placeholder(self):
        with tempfile.TemporaryDirectory() as td:
            td = Path(td)
            committed, placeholder = td / "assets", td / "placeholder"
            (placeholder / "voice").mkdir(parents=True)
            self.assertEqual(self.build.voice_root(committed, lambda: placeholder), placeholder)
            (committed / "voice").mkdir(parents=True)
            called = []
            self.assertEqual(self.build.voice_root(committed, lambda: called.append(1)), None)
            self.assertEqual(called, [], "placeholder fetched although the committed voice exists")

    def test_placeholder_is_normalized_to_fixed_names(self):
        with tempfile.TemporaryDirectory() as td:
            td = Path(td)
            src = td / "vits-piper-en_US-lessac-medium"
            (src / "espeak-ng-data" / "lang").mkdir(parents=True)
            (src / "en_US-lessac-medium.onnx").write_bytes(b"onnx")
            (src / "tokens.txt").write_text("a 1\n")
            (src / "espeak-ng-data" / "phontab").write_bytes(b"p")
            tarball = td / "voice.tar.bz2"
            with tarfile.open(tarball, "w:bz2") as t:
                t.add(src, arcname=src.name)
            out = self.build.extract_placeholder(tarball, td / "out")
            voice = out / "voice"
            self.assertEqual((voice / "model.onnx").read_bytes(), b"onnx")
            self.assertTrue((voice / "tokens.txt").is_file())
            self.assertTrue((voice / "espeak-ng-data" / "phontab").is_file())

    def test_voice_stamp_tracks_content(self):
        with tempfile.TemporaryDirectory() as td:
            voice = Path(td) / "voice"
            voice.mkdir()
            (voice / "model.onnx").write_bytes(b"one")
            a = self.build.voice_stamp(voice)
            self.assertEqual(a, self.build.voice_stamp(voice))
            (voice / "model.onnx").write_bytes(b"two")
            self.assertNotEqual(a, self.build.voice_stamp(voice))

    def test_placeholder_never_lands_in_launcher_assets(self):
        # The stock voice is staged from tools/third_party/, never copied into
        # launcher/assets/voice/, which is reserved for the committed trained voice.
        self.assertNotRegex(self.src, r"copytree\([^)]*LAUNCHER_ASSETS")

    def test_built_apk_bundles_sherpa_libs_when_present(self):
        apk = REPO / "launcher" / "miko3-launcher.apk"
        if not apk.exists():
            self.skipTest("launcher not built")
        import zipfile
        names = set(zipfile.ZipFile(apk).namelist())
        if "lib/arm64-v8a/libsherpa-onnx-jni.so" not in names and \
                apk.stat().st_mtime < BUILD_PY.stat().st_mtime:
            self.skipTest("launcher APK predates the speech build")
        for lib in ("lib/arm64-v8a/libonnxruntime.so", "lib/arm64-v8a/libsherpa-onnx-jni.so",
                    "assets/voice/model.onnx", "assets/voice/tokens.txt", "assets/voice/stamp.txt"):
            self.assertIn(lib, names)


if __name__ == "__main__":
    unittest.main()
