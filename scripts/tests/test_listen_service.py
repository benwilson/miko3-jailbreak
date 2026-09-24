"""Host-side tests for the launcher's listening (explore-on-claude plan U3;
R11, R12, KTD1, KTD4, KTD8).

The plain-Java ListenSession (the endpoint, the cap, "no speech", waiting for
the speech queue to go idle, one listen at a time) runs under a JVM harness
with a fake microphone and recognizer, next to the real SpeechQueue. The
Android glue (the Binder service, the AudioRecord and sherpa-onnx engine, the
client, the manifest) and the build's model staging are covered by
source-wiring checks and build-script unit tests, since host tests can't run
them.
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
HARNESS = TESTS / "fixtures" / "listen_service_harness" / "src"
HARNESS_MAIN = HARNESS / "com" / "miko3" / "launcher" / "ListenServiceHarness.java"
SESSION = LAUNCHER / "ListenSession.java"
SERVICE = LAUNCHER / "ListenService.java"
ENGINE = LAUNCHER / "ListenEngine.java"
APP = LAUNCHER / "LauncherApp.java"
INTERFACE = SHARED / "RobotListen.java"
CLIENT = SHARED / "RobotListenClient.java"
PROTOCOL = SHARED / "LauncherProtocol.java"
MANIFEST = REPO / "launcher" / "AndroidManifest.xml"
BUILD_PY = REPO / "scripts" / "build-custom-launcher.py"
INSTALL_PY = REPO / "scripts" / "install-custom-launcher.py"


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


class ListenServiceHarnessTest(unittest.TestCase):
    SCENARIOS = (
        # ListenSession stops at the endpoint, at the cap, and hears silence as "no speech".
        "stops_at_endpoint",
        "stops_at_cap",
        "silence_reports_no_speech",
        "silence_endpoint_reports_no_speech",
        "whitespace_transcript_is_no_speech",
        "microphone_failure_reports_failed",
        "cap_is_clamped",
        "run_opens_mic_and_closes_everything",
        "mic_that_will_not_open_reports_failed",
        # KTD8: listen only once the speech queue is idle.
        "listen_waits_while_speech_plays_then_starts",
        "listen_waits_for_queued_lines_too",
        "listen_at_once_when_queue_idle",
        "speech_busy_past_timeout_fails_without_opening_mic",
        # One listen at a time; callers are checked.
        "second_concurrent_listen_refused",
        "refused_when_recognizer_not_ready",
        "unpinned_caller_is_denied",
    )

    @classmethod
    def setUpClass(cls):
        jdk = jvm_harness.find_jdk()
        if jdk is None:
            raise unittest.SkipTest("no JDK (javac + java) found")
        cls._td = tempfile.TemporaryDirectory(prefix="listen_service_harness_")
        out = cls._td.name
        # javac pulls in only what the harness references: ListenSession,
        # SpeechQueue, CallerCheck. Never the Android-bound service or engine.
        c = subprocess.run(jvm_harness.javac_cmd(jdk[0], out, [HARNESS_MAIN],
                                                 [HARNESS, LAUNCHER_SRC, SHARED_SRC]),
                           capture_output=True, text=True)
        cls.compiled = c.returncode == 0
        cls.compile_output = (c.stdout + c.stderr)[-3000:]
        cls.results, cls.run_output = {}, ""
        if cls.compiled:
            r = subprocess.run([jdk[1], "-cp", out, "com.miko3.launcher.ListenServiceHarness"],
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


jvm_harness.add_scenario_tests(ListenServiceHarnessTest)


class PlainJavaTest(unittest.TestCase):
    def test_session_is_plain_java(self):
        raw = SESSION.read_text() if SESSION.exists() else ""
        self.assertTrue(raw, "ListenSession.java missing")
        self.assertEqual([ln for ln in raw.splitlines() if ln.startswith("import android")], [])


class ServiceWiringTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.src = _read(SERVICE)

    def test_listen_checks_the_caller_before_claiming(self):
        body = _method_body(self.src, "public void listen")
        self.assertIsNotNone(body, "ListenService does not implement listen")
        check, claim = body.find("enforceCaller("), body.find(".claim(")
        self.assertGreaterEqual(check, 0, "listen never checks the caller")
        self.assertGreater(claim, check, "listen claims the microphone before the caller check")

    def test_caller_gate_and_uid(self):
        self.assertIn("CallerGate.enforce(", self.src)
        self.assertIn("Binder.getCallingUid()", self.src)

    def test_listens_off_the_binder_thread(self):
        # listen() returns at once; the session runs on the engine's own thread.
        body = _method_body(self.src, "public void listen") or ""
        self.assertRegex(body, r"\.(execute|submit|listen)\(")

    def test_binds_on_the_constant_and_uses_the_launcher_engine(self):
        self.assertIn("LauncherProtocol.ROBOT_LISTEN_ACTION", self.src)
        self.assertIn("(LauncherApp) getApplication()", self.src)
        self.assertIn("listen()", self.src)


class EngineWiringTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.src = _read(ENGINE)

    def test_records_16k_mono_on_voice_modes_source(self):
        for needle in ("new AudioRecord(", "AudioFormat.CHANNEL_IN_MONO", "AudioFormat.ENCODING_PCM_16BIT",
                       "MediaRecorder.AudioSource.VOICE_COMMUNICATION", "AudioRecord.getMinBufferSize("):
            self.assertIn(needle, self.src)
        self.assertRegex(_read(SESSION), r"SAMPLE_RATE\s*=\s*16000")

    def test_streaming_zipformer_with_endpointing(self):
        for needle in ("new OnlineRecognizer(", "OnlineTransducerModelConfig", "setEnableEndpoint(true)",
                       "EndpointConfig", "acceptWaveform(", "isEndpoint(", "inputFinished()"):
            self.assertIn(needle, self.src)

    def test_model_copied_out_of_assets_keyed_by_stamp(self):
        self.assertIn('"listen"', self.src)
        self.assertIn("installAssets(", self.src)

    def test_waits_on_the_speech_queue(self):
        self.assertIn("awaitIdle(", self.src + _read(APP))

    def test_checks_the_record_permission(self):
        self.assertIn("Manifest.permission.RECORD_AUDIO", self.src)

    def test_engine_made_once_by_the_app(self):
        app = _read(APP)
        self.assertEqual(app.count("new ListenEngine("), 1)
        self.assertIn("ListenEngine listen()", app)

    def test_no_logging_of_secrets(self):
        for path in (SERVICE, ENGINE, SESSION, INTERFACE, CLIENT):
            src = _read(path)
            with self.subTest(file=path.name):
                self.assertTrue(src, f"{path.name} missing")
                for stmt in re.findall(r"Log\.\w\([^;]*;", src, flags=re.S):
                    self.assertNotRegex(stmt, r"(?i)apiKey|claudeSettings|ClaudeAccess|credentials")
                for needle in ("System.out", "System.err", "printStackTrace"):
                    self.assertNotIn(needle, src)


class ProtocolAndManifestTest(unittest.TestCase):
    def test_action_constant_declared(self):
        self.assertRegex(_read(PROTOCOL), r'ROBOT_LISTEN_ACTION\s*=\s*"com\.miko3\.launcher\.ROBOT_LISTEN"')

    def test_manifest_declares_exported_service_with_action(self):
        manifest = MANIFEST.read_text()
        m = re.search(r'<service android:name="\.ListenService"([^>]*)>(.*?)</service>', manifest, flags=re.S)
        self.assertIsNotNone(m, "manifest has no ListenService")
        self.assertIn('android:exported="true"', m.group(1))
        self.assertIn('<action android:name="com.miko3.launcher.ROBOT_LISTEN"/>', m.group(2))

    def test_manifest_asks_for_the_microphone(self):
        self.assertIn('<uses-permission android:name="android.permission.RECORD_AUDIO"/>',
                      MANIFEST.read_text())

    def test_installer_grants_the_microphone(self):
        src = INSTALL_PY.read_text()
        self.assertIn('"android.permission.RECORD_AUDIO"', src)
        self.assertRegex(src, r'adb\("shell", "pm", "grant", CUSTOM_PKG, PERMISSION\)')


class InterfaceAndClientTest(unittest.TestCase):
    def test_hand_written_binder_shape(self):
        src = _read(INTERFACE)
        for needle in ("extends IInterface", "abstract class Stub extends Binder", "class Proxy",
                       '"com.miko3.shared.RobotListen"', '"com.miko3.shared.RobotListen.Callback"',
                       "enforceInterface(", "writeInterfaceToken(", "reply.readException()",
                       "writeStrongBinder(", "readStrongBinder()", "FLAG_ONEWAY"):
            self.assertIn(needle, src)

    def test_interface_declares_listen_and_callback(self):
        head = _read(INTERFACE).split("abstract class Stub", 1)[0]
        self.assertRegex(head, r"void listen\(long \w+, Callback \w+\) throws RemoteException;")
        for m in (r"void heard\(String \w+\)", r"void noSpeech\(\)", r"void failed\(String \w+\)"):
            self.assertRegex(head, m)

    def test_client_binds_by_action_and_unbinds_after_the_callback(self):
        src = _read(CLIENT)
        for needle in ("new Intent(LauncherProtocol.ROBOT_LISTEN_ACTION)",
                       "setPackage(LauncherProtocol.LAUNCHER_PACKAGE)", "bindService(",
                       "unbindService(", "RobotListen.Stub.asInterface("):
            self.assertIn(needle, src)
        release = _method_body(src, "private void done")
        self.assertIsNotNone(release, "client has no done()")
        self.assertIn("unbindService(", release)

    def test_client_has_a_timeout_backstop(self):
        # A launcher that never answers can't leave Explore waiting forever.
        src = _read(CLIENT)
        self.assertIn("TIMEOUT_MARGIN_MS", src)
        self.assertIn("schedule(", src)


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

    def test_pins_the_listen_model(self):
        b = self.build
        self.assertEqual(b.LISTEN_MODEL, "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17")
        self.assertRegex(b.LISTEN_MODEL_SHA256, r"^[0-9a-f]{64}$")
        self.assertIn(b.LISTEN_MODEL, b.LISTEN_MODEL_URL)
        self.assertTrue(b.LISTEN_MODEL_URL.startswith("https://github.com/k2-fsa/sherpa-onnx/releases/"))

    def test_downloads_into_gitignored_third_party(self):
        cache = self.build.LISTEN_CACHE.resolve()
        self.assertEqual(cache.parent, (REPO / "tools" / "third_party").resolve())

    def test_model_is_normalized_to_fixed_int8_names(self):
        with tempfile.TemporaryDirectory() as td:
            td = Path(td)
            src = td / "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17"
            (src / "test_wavs").mkdir(parents=True)
            for name in ("encoder-epoch-99-avg-1.onnx", "decoder-epoch-99-avg-1.onnx",
                         "joiner-epoch-99-avg-1.onnx"):
                (src / name).write_bytes(b"fp32")
                (src / name.replace(".onnx", ".int8.onnx")).write_bytes(b"int8")
            (src / "tokens.txt").write_text("<blk> 0\n")
            (src / "test_wavs" / "0.wav").write_bytes(b"wav")
            tarball = td / "m.tar.bz2"
            with tarfile.open(tarball, "w:bz2") as t:
                t.add(src, arcname=src.name)
            out = self.build.extract_listen_model(tarball, td / "out")
            listen = out / "listen"
            self.assertEqual(sorted(p.name for p in listen.iterdir()),
                             ["decoder.onnx", "encoder.onnx", "joiner.onnx", "tokens.txt"])
            for name in ("encoder.onnx", "decoder.onnx", "joiner.onnx"):
                self.assertEqual((listen / name).read_bytes(), b"int8", name)

    def test_model_is_stamped_and_staged(self):
        self.assertIn('"listen"', self.src)
        self.assertRegex(self.src, r"listen_model\(\)")

    def test_built_apk_bundles_the_listen_model_when_present(self):
        apk = REPO / "launcher" / "miko3-launcher.apk"
        if not apk.exists() or apk.stat().st_mtime < BUILD_PY.stat().st_mtime:
            self.skipTest("launcher not built since the listen model was added")
        import zipfile
        names = set(zipfile.ZipFile(apk).namelist())
        for f in ("encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt", "stamp.txt"):
            self.assertIn(f"assets/listen/{f}", names)



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


class ListenPrivacyLogTest(unittest.TestCase):
    def test_log_calls_never_carry_spoken_or_heard_words(self):
        self.assertEqual(_log_word_offenders((SERVICE, ENGINE, SESSION, APP, LAUNCHER / "PeopleService.java", LAUNCHER / "PeopleStore.java")), [])


if __name__ == "__main__":
    unittest.main()
