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
CLIENT_HARNESS = TESTS / "fixtures" / "speech_client_harness"
CLIENT_HARNESS_MAIN = CLIENT_HARNESS / "src" / "com" / "miko3" / "shared" / "SpeechClientHarness.java"
QUEUE = LAUNCHER / "SpeechQueue.java"
TUNING = LAUNCHER / "SpeechTuning.java"
SERVICE = LAUNCHER / "SpeechService.java"
ENGINE = LAUNCHER / "SpeechEngine.java"
PLAYER = LAUNCHER / "SpeechPlayer.java"
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
        # Synthesis runs ahead of playback: cancel still stops at the end of
        # the sentence playing, not after everything already made.
        "uncancelled_line_plays_every_sentence_made_ahead",
        "cancel_while_draining_stops_after_the_playing_sentence",
        "cancel_during_synthesis_stops_after_the_playing_sentence",
        "other_callers_cancel_does_not_cut_a_draining_line",
        "stuck_head_ends_the_line_at_the_deadline",
        # An underrun between sentences (short first sentence, slow second)
        # must not leave the track waiting to refill a 20 s buffer.
        "short_first_sentence_underrun_still_plays_the_whole_line",
        "line_without_underrun_plays_through_untouched",
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
        "voice_failure_fails_that_line_only",
        "playback_failure_fails_that_line_only",
        # Tuning (KTD8).
        "tuning_defaults", "default_splits_a_13_word_line_at_its_comma",
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


class SpeechClientHarnessTest(unittest.TestCase):
    """RobotSpeechClient itself, compiled against android.* stubs, with a fake
    Context and a fake launcher service."""
    SCENARIOS = (
        "speak_before_connect_is_sent_on_connect",
        "bind_failure_fails_the_line_once",
        "disconnect_fails_sent_lines_but_leaves_unsent_alone",
        "cancel_before_connect_cancels_unsent_lines_once",
        "cancel_while_connecting_skips_lines_not_yet_sent",
        "last_callback_unbinds_and_next_speak_rebinds",
        "duplicate_callback_fires_the_listener_once",
        "failed_callback_reaches_on_failed_with_its_reason",
    )

    @classmethod
    def setUpClass(cls):
        jdk = jvm_harness.find_jdk()
        if jdk is None:
            raise unittest.SkipTest("no JDK (javac + java) found")
        cls._td = tempfile.TemporaryDirectory(prefix="speech_client_harness_")
        out = cls._td.name
        c = subprocess.run(jvm_harness.javac_cmd(jdk[0], out, [CLIENT_HARNESS_MAIN],
                                                 [CLIENT_HARNESS / "src", CLIENT_HARNESS / "stubs", SHARED_SRC]),
                           capture_output=True, text=True)
        cls.compiled = c.returncode == 0
        cls.compile_output = (c.stdout + c.stderr)[-3000:]
        cls.results = {}
        cls.run_output = ""
        if c.returncode == 0:
            r = subprocess.run([jdk[1], "-cp", out, "com.miko3.shared.SpeechClientHarness"],
                               capture_output=True, text=True, timeout=60)
            cls.run_output = (r.stdout + r.stderr)[-6000:]
            cls.results = jvm_harness.parse_verdicts(r.stdout)

    @classmethod
    def tearDownClass(cls):
        cls._td.cleanup()

    def setUp(self):
        self.assertTrue(self.compiled, f"client harness failed to compile:\n{self.compile_output}")

    def _assert_pass(self, name):
        self.assertIn(name, self.results, f"scenario {name} never reported:\n{self.run_output}")
        verdict, detail = self.results[name]
        self.assertEqual(verdict, "PASS", f"{name}: {detail}")

    def test_harness_reports_exactly_the_expected_scenarios(self):
        self.assertEqual(sorted(self.results), sorted(self.SCENARIOS), self.run_output)


jvm_harness.add_scenario_tests(SpeechClientHarnessTest)


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

    def test_playback_goes_through_speech_player(self):
        # The harness covers SpeechPlayer and LineDrain; this pins the engine to them.
        speak = _method_body(self.src, "public void speak") or ""
        self.assertIn("player.write(", speak)
        self.assertIn("player.chunkEnded()", speak)
        end = _method_body(self.src, "public void endLine") or ""
        self.assertIn("player.endLine(", end)
        self.assertIn("queue.cancelRequested(", end)
        player = _read(PLAYER)
        self.assertIn("drain.await(", _method_body(player, "boolean endLine") or "")
        self.assertIn("speaker.pause()", player)
        self.assertIn("speaker.flush()", player)
        self.assertEqual([ln for ln in PLAYER.read_text().splitlines() if ln.startswith("import android")], [])

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

    def test_no_logging_of_spoken_text(self):
        # What the robot says can be a child's words played back: logs carry
        # ids, character counts and timings, never the text itself.
        for path in (SERVICE, ENGINE, PLAYER, QUEUE, CLIENT, APP):
            src = _read(path)
            with self.subTest(file=path.name):
                self.assertTrue(src, f"{path.name} missing")
                for stmt in re.findall(r"Log\.\w\([^;]*;", src, flags=re.S):
                    code = re.sub(r'"(?:\\.|[^"\\])*"', '""', stmt)
                    self.assertNotRegex(code, r"\b(text|chunk|transcript|line\.text)\b(?!\.length\(\))",
                                        f"Log call names spoken text: {stmt}")

    def test_no_logging_of_secrets(self):
        # The settings key is never logged.
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

    def test_callback_has_one_way_failed_transaction(self):
        src = _read(INTERFACE)
        body = src.split("abstract class Stub", 1)[0]
        self.assertRegex(body, r"void failed\(String \w+\) throws RemoteException;")
        self.assertRegex(src, r"TRANSACTION_failed\s*=\s*3;")
        self.assertRegex(src, r"case TRANSACTION_failed:[^}]*readString\(\)")
        send = _method_body(src, "private void send")
        self.assertIsNotNone(send)
        self.assertIn("FLAG_ONEWAY", send)

    def test_service_forwards_failed(self):
        body = _method_body(_read(SERVICE), "public void failed")
        self.assertIsNotNone(body, "SpeechService.LineCallback has no failed()")
        self.assertIn("callback.failed(", body)
        self.assertIn("unlink()", body)

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



class LauncherDriverLibTest(unittest.TestCase):
    """Live test: DriveLeaseService crashed with NoClassDefFoundError
    SensorModule, because System.loadLibrary("miko_drivers") fell through to
    /system/lib64, which the linker namespace hides. The launcher bundles the
    vendor library as its own, as build-mode-explore.py does."""

    VENDOR_DIR = REPO / "tools" / "serviceexam_jadx" / "resources" / "lib" / "arm64-v8a"

    @classmethod
    def setUpClass(cls):
        cls.build = _load_build()

    def test_names_the_vendor_lib_and_its_home(self):
        self.assertEqual(self.build.DRIVER_LIB, "libmiko_drivers.so")
        self.assertEqual(self.build.VENDOR_LIB_DIR, self.VENDOR_DIR)

    def test_present_lib_is_an_arm64_entry(self):
        if not (self.VENDOR_DIR / "libmiko_drivers.so").is_file():
            self.skipTest("vendor library not extracted")
        self.assertEqual(self.build.vendor_native_libs(self.VENDOR_DIR),
                         [("arm64-v8a", self.VENDOR_DIR / "libmiko_drivers.so")])

    def test_missing_lib_fails_clearly_before_any_toolchain_work(self):
        with tempfile.TemporaryDirectory() as td:
            with self.assertRaises(self.build.BuildError) as ctx:
                self.build.vendor_native_libs(Path(td))
            self.assertIn("libmiko_drivers.so", str(ctx.exception))
            self.assertIn(td, str(ctx.exception))
            from unittest import mock
            with mock.patch.object(self.build, "VENDOR_LIB_DIR", Path(td)), \
                    mock.patch.object(self.build.bc, "find_sdk") as sdk, \
                    mock.patch.object(self.build.bc, "ensure_toolchain") as tc, \
                    mock.patch.object(self.build.bc, "build_apk") as ba, \
                    mock.patch.object(sys, "argv", ["build-custom-launcher.py"]):
                with self.assertRaises(self.build.BuildError):
                    self.build.main()
        sdk.assert_not_called()
        tc.assert_not_called()
        ba.assert_not_called()

    def test_main_bundles_it_with_the_sherpa_libs(self):
        body = BUILD_PY.read_text()
        self.assertRegex(body, r"native_libs\s*=\s*vendor_native_libs\(\)\s*\+\s*sherpa_libs")

    def test_sensor_module_loads_the_bundled_copy_by_name(self):
        sm = (SHARED_SRC / "emotix" / "com" / "drivers" / "SensorModule.java").read_text()
        self.assertIn('System.loadLibrary("miko_drivers")', sm)
        self.assertNotIn('System.load("/system', sm)

    def test_built_apk_carries_it(self):
        apk = REPO / "launcher" / "miko3-launcher.apk"
        if not apk.exists() or apk.stat().st_mtime < BUILD_PY.stat().st_mtime:
            self.skipTest("launcher not built since the build script changed")
        import zipfile
        self.assertIn("lib/arm64-v8a/libmiko_drivers.so", zipfile.ZipFile(apk).namelist())


# Plan rule: never log names, transcripts, or spoken text; ids, timings,
# lengths (x.length()), and outcomes are fine. A Log call's arguments, with string literals
# dropped, must not name a variable that holds words.
_WORDS_VAR = re.compile(r"\b(text|chunk|transcript|said|name|label|words)\b|\.text\b|\bline\b(?!\s*\.\s*id\b)")


def _log_word_offenders(paths):
    offenders = []
    for path in paths:
        for stmt in re.findall(r"Log\.\w\((.*?)\);", _read(path), flags=re.S):
            bare = re.sub(r'"(?:\\.|[^"\\])*"', "", stmt)
            # A length or a null check says how much, not what: fine.
            bare = re.sub(r"[\w.]+\.length\(\)|[\w.]+\s*==\s*null", "", bare)
            if _WORDS_VAR.search(bare):
                offenders.append(f"{path.name}: Log({' '.join(stmt.split())})")
    return offenders


class SpeechPrivacyLogTest(unittest.TestCase):
    def test_log_calls_never_carry_spoken_or_heard_words(self):
        self.assertEqual(_log_word_offenders((SERVICE, ENGINE, QUEUE, LAUNCHER / "LauncherApp.java")), [])


if __name__ == "__main__":
    unittest.main()
