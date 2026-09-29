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
PROBE = LAUNCHER / "EarsProbe.java"
DIRECTION = SHARED / "VoiceDirection.java"
JNI_STUBS = REPO / "shared" / "src" / "com" / "example" / "conexantapi"
VENDOR_JNI = REPO / "tools" / "serviceexam_jadx" / "sources" / "com" / "example" / "conexantapi"
VENDOR_LIB_DIR = REPO / "tools" / "serviceexam_jadx" / "resources" / "lib" / "arm64-v8a"
INTERFACE = SHARED / "RobotListen.java"
CLIENT = SHARED / "RobotListenClient.java"
PROTOCOL = SHARED / "LauncherProtocol.java"
MANIFEST = REPO / "launcher" / "AndroidManifest.xml"
BUILD_PY = REPO / "scripts" / "build-custom-launcher.py"
INSTALL_PY = REPO / "scripts" / "install-custom-launcher.py"
# Meeting plan U3: the continuous ears session.
EARS = LAUNCHER / "EarsSession.java"
CLASSIFIER = LAUNCHER / "CueClassifier.java"
KEEPER = LAUNCHER / "LeaseKeeper.java"
DRIVE_LEASE = LAUNCHER / "DriveLeaseService.java"
QUEUE = LAUNCHER / "SpeechQueue.java"
TUNING = LAUNCHER / "SpeechTuning.java"
EARS_INTERFACE = SHARED / "RobotEars.java"
EARS_CLIENT = SHARED / "RobotEarsClient.java"
HOTWORDS = REPO / "launcher" / "assets" / "hotwords.txt"
WAKEWORD_LIBS = ("libnative_wakeword_vad_lib.so", "libncnn.so", "libtensorflowlite_gpu_delegate.so")


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
        # Meeting plan U3: the continuous ears session (KTD1, KTD2, KTD6) and the
        # keeper extracted from the drive lease service.
        "ears_deaf_window_drops_utterance_inside",
        "ears_utterance_after_window_delivered",
        "ears_straddling_utterance_is_partial",
        "ears_line_start_mid_utterance_delivers_partial",
        "ears_clip_window_matches_a_spoken_line",
        "ears_second_open_refused",
        "ears_one_shot_refused_while_open",
        "ears_renew_and_close_from_other_uid_refused",
        "ears_three_missed_renews_release_capture",
        "ears_client_death_releases_capture",
        "ears_close_releases_capture",
        "ears_capture_that_will_not_open_retries_on_tick",
        # Hey Miko plan U3 (KTD5): the ears stay open on the charger; these replace
        # the meeting plan's charger-closes-capture scenarios.
        "ears_charger_keeps_capturing_and_delivers_the_wake_word",
        "ears_charger_latch_changes_never_close_the_capture",
        "ears_wake_word_is_strong_with_the_switch_off",
        # Hey Miko plan U3 (KTD4): the wake word is delivered as soon as it is spotted.
        "ears_wake_mid_speech_delivers_an_early_cue_at_once",
        "ears_end_of_a_called_utterance_is_marked_already_called",
        "ears_two_hits_in_one_utterance_send_one_early_cue",
        "ears_wake_inside_the_deaf_window_delivers_nothing",
        "ears_early_cue_keeps_the_conversation_listen_for_the_words",
        "ears_bare_wake_with_the_gate_closed_is_unchanged",
        "ears_direction_sampled_only_while_speech",
        "ears_burst_without_words_or_side_is_dropped",
        "ears_shove_then_sorry_is_strong",
        "ears_listen_expires_at_its_cap",
        "ears_logs_counters_not_words",
        "keeper_acquire_renew_release",
        "keeper_ttl_expiry",
        "keeper_death_and_stale_death_ignored",
        "keeper_dead_token_never_acquires",
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

    def test_ears_session_classifier_and_keeper_are_plain_java(self):
        """Meeting plan U3: the session state machine, the classifier and the
        keeper are proven in the host harness, so none may touch android.*."""
        for path in (EARS, CLASSIFIER, KEEPER):
            with self.subTest(file=path.name):
                raw = path.read_text() if path.exists() else ""
                self.assertTrue(raw, f"{path.name} missing")
                self.assertEqual([ln for ln in raw.splitlines() if ln.startswith("import android")], [])

    def test_ears_session_pins_the_plans_timings(self):
        src = _read(EARS)
        self.assertRegex(src, r"RENEW_PERIOD_MS\s*=\s*1000")
        self.assertRegex(src, r"MISSED_RENEWS\s*=\s*3")
        self.assertRegex(src, r"DIRECTION_PERIOD_MS\s*=\s*100")
        # KTD4: the angle is the median over the utterance, from the shared library.
        self.assertIn("VoiceDirection.median(", src)

    def test_deaf_tail_is_a_speech_tuning_with_the_plans_default(self):
        src = _read(TUNING)
        self.assertRegex(src, r"DEFAULT_DEAF_TAIL_MS\s*=\s*500")
        self.assertIn("deafTailMs", src)
        self.assertRegex(src, r'DEAF_TAIL_PROP\s*=\s*"debug\.miko3\.speech\.deaf_tail_ms"')


class SpeechQueueHookTest(unittest.TestCase):
    """KTD1: the deaf window runs from a line's start to playback idle, so the
    queue tells the ears both, from the thread that plays the line."""

    def test_queue_signals_line_start_and_idle(self):
        src = _read(QUEUE)
        self.assertRegex(src, r"interface Speaking\s*\{[^}]*void started\(\);[^}]*void idle\(\);")
        self.assertIn("setSpeaking(", src)
        body = _method_body(src, "boolean playNext")
        self.assertIsNotNone(body)
        self.assertLess(body.find(".started()"), body.find("voice.startLine("))
        self.assertIn(".idle()", body)


class DriveLeaseKeeperTest(unittest.TestCase):
    """The renew, death and TTL bookkeeping moved into LeaseKeeper; the drive
    lease service keeps its stop-motors behaviour on every release path."""

    def test_drive_lease_delegates_to_the_keeper(self):
        src = _read(DRIVE_LEASE)
        self.assertIn("new LeaseKeeper(", src)
        self.assertNotIn("holderDeathRecipient", src)
        for reason in ("ttl_expired", "binder_died", "clean_release"):
            self.assertIn(reason, _read(KEEPER), reason)
        self.assertIn("issueStop(", _method_body(src, "released") or "")


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

    def test_one_shot_listen_refused_while_ears_are_open(self):
        """Meeting plan U3, KTD1: the one-shot stays only for callers with no
        session open; the check runs after the caller gate and before the claim."""
        body = _method_body(self.src, "public void listen") or ""
        check, refuse, claim = body.find("enforceCaller("), body.find("refuseOneShot("), body.find(".claim(")
        self.assertGreater(refuse, check, "the ears check runs before the caller gate")
        self.assertGreater(claim, refuse, "the microphone is claimed before the ears check")

    EARS_METHODS = (r"public void open\(", r"public boolean renew\(", r"public void close\(",
                    r"public void listen\(long \w+\)", r"public void clipWindow\(", r"public void shoved\(")

    def test_every_ears_transaction_passes_the_gate_and_reads_the_uid(self):
        """Every ears transaction passes the caller gate, and renew, close, listen,
        clip and shove are bound to the uid that opened the session."""
        ears = self.src.split("RobotEars.Stub", 1)
        self.assertEqual(len(ears), 2, "ListenService serves no RobotEars.Stub")
        for pattern in self.EARS_METHODS:
            with self.subTest(method=pattern):
                m = re.search(pattern + r"[^{;]*\{", ears[1])
                self.assertIsNotNone(m, f"no {pattern}")
                depth, i = 1, m.end()
                while depth and i < len(ears[1]):
                    depth += {"{": 1, "}": -1}.get(ears[1][i], 0)
                    i += 1
                body = ears[1][m.end():i - 1]
                self.assertIn("enforceCaller(", body)
                self.assertIn("Binder.getCallingUid()", body)

    def test_binds_ears_by_their_own_action(self):
        self.assertIn("LauncherProtocol.ROBOT_EARS_ACTION", self.src)
        body = _method_body(self.src, "public IBinder onBind") or ""
        self.assertIn("getAction()", body)


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

    def test_probe_runs_on_the_listen_thread_with_the_session_claimed(self):
        """Meeting plan U1: the ears probe borrows the one microphone through the
        same claim a listen takes, so it can never record over a mode's listen."""
        body = _method_body(self.src, "probe")
        self.assertIsNotNone(body, "ListenEngine has no probe()")
        claim, run = body.find("session.claim()"), body.find("thread.submit(")
        self.assertGreaterEqual(claim, 0, "probe never claims the session")
        self.assertGreater(run, claim, "probe runs before the claim")
        self.assertIn("session.run(", body)

    def test_engine_made_once_by_the_app(self):
        app = _read(APP)
        self.assertEqual(app.count("new ListenEngine("), 1)
        self.assertIn("ListenEngine listen()", app)

    def test_one_recogniser_configured_per_ktd2(self):
        """Meeting plan KTD2: modified_beam_search with the hotwords file at the
        asset root, bpe modelling unit with the vocabulary beside tokens.txt, 2
        threads, 2 active paths, endpoints 0.8 s after words and 2 s of nothing."""
        for needle in ('"modified_beam_search"', "setMaxActivePaths(MAX_ACTIVE_PATHS)", "setHotwordsFile(",
                       'setModelingUnit("bpe")', "setBpeVocab(", '"bpe.vocab"', '"hotwords.txt"',
                       "setMinTrailingSilence(0.8f)", "setMinTrailingSilence(2.0f)", "setNumThreads(THREADS)"):
            self.assertIn(needle, self.src)
        self.assertRegex(self.src, r"\bTHREADS\s*=\s*2;")
        self.assertRegex(self.src, r"MAX_ACTIVE_PATHS\s*=\s*2;")
        self.assertEqual(self.src.count("new OnlineRecognizer("), 1, "one recogniser serves both listens")

    def test_ears_feed_the_wake_word_engine_a_silero_gate_and_the_direction_sampler(self):
        for needle in ("new WakeWord(", "processChunk(", "SileroVadModelConfig", "new Vad(", "isSpeechDetected()",
                       "VoiceDirection.sampleLazily(EarsSession.DIRECTION_PERIOD_MS)", "setSpeaking(",
                       "new EarsSession("):
            self.assertIn(needle, self.src)
        # The switch (KTD11) is read at classify time through ClaudeSettings, the
        # launcher's one parser of the stored value.
        self.assertIn("conversation().answersWhenSpokenTo", self.src)

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

    def test_ears_action_declared_and_served_by_the_listen_service(self):
        self.assertRegex(_read(PROTOCOL), r'ROBOT_EARS_ACTION\s*=\s*"com\.miko3\.launcher\.ROBOT_EARS"')
        m = re.search(r'<service android:name="\.ListenService"([^>]*)>(.*?)</service>', MANIFEST.read_text(), flags=re.S)
        self.assertIsNotNone(m)
        self.assertIn('<action android:name="com.miko3.launcher.ROBOT_EARS"/>', m.group(2))

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

    def test_client_close_shuts_down_its_worker_and_is_idempotent(self):
        # ClaudeCuriosity is rebuilt on every Explore start; close() is how its
        # release() gets this client's executor thread back (no leak per start).
        src = _read(CLIENT)
        body = _method_body(src, "public void close")
        self.assertIsNotNone(body, "client has no close()")
        self.assertIn("shutdownNow()", body)
        self.assertIn("unbindService(", body)
        # Idempotent: a second close() returns before doing anything.
        self.assertRegex(body, r"if \(closed\)\s*\{?\s*return;")
        self.assertIn("closed = true", body)
        # Late launcher callbacks are dropped: every open call is marked ended.
        self.assertIn("ended.set(true)", body)

    def test_client_drops_calls_and_callbacks_after_close(self):
        src = _read(CLIENT)
        for name in ("public void listen", "public void onServiceConnected", "public void onServiceDisconnected"):
            body = _method_body(src, name)
            self.assertIsNotNone(body, name)
            self.assertIn("closed", body, name)

    def test_client_has_a_timeout_backstop(self):
        # A launcher that never answers can't leave Explore waiting forever.
        src = _read(CLIENT)
        self.assertIn("TIMEOUT_MARGIN_MS", src)
        self.assertIn("schedule(", src)

    # ---- meeting plan U3: the ears Binder ----

    def test_ears_binder_shape(self):
        src = _read(EARS_INTERFACE)
        for needle in ("extends IInterface", "abstract class Stub extends Binder", "class Proxy",
                       '"com.miko3.shared.RobotEars"', '"com.miko3.shared.RobotEars.Callback"',
                       "enforceInterface(", "writeInterfaceToken(", "reply.readException()",
                       "writeStrongBinder(", "readStrongBinder()", "FLAG_ONEWAY"):
            self.assertIn(needle, src)
        head = src.split("abstract class Stub", 1)[0]
        for m in (r"void open\(Callback \w+, boolean \w+\) throws RemoteException;",
                  r"boolean renew\(boolean \w+\) throws RemoteException;",
                  r"void close\(\) throws RemoteException;",
                  r"void listen\(long \w+\) throws RemoteException;",
                  r"void clipWindow\(long \w+\) throws RemoteException;",
                  r"void shoved\(long \w+\) throws RemoteException;",
                  r"void heard\(String \w+, int \w+, float \w+, int \w+, long \w+, boolean \w+, int \w+,\s+"
                  r"boolean \w+\)"):
            self.assertRegex(head, m)

    def test_ears_callback_kind_is_appended_last_and_an_older_launcher_ends_the_session(self):
        """Owner-approved 2026-09-26: the cue kind rides the KTD1 callback as
        its last field. An older mode ignores the trailing int; a newer mode
        under an older launcher finds none and ends the session once, never
        guessing the kind from the text."""
        src = _read(EARS_INTERFACE)
        for name, value in (("KIND_WAKE_WORD", 0), ("KIND_NAME", 1), ("KIND_GREETING", 2), ("KIND_APOLOGY", 3),
                            ("KIND_VOICE", 4), ("KIND_MISSING", -1)):
            self.assertRegex(src, rf"int {name}\s*=\s*{value};")
        stub = _method_body(src, "public boolean onTransact")
        self.assertIsNotNone(stub)
        self.assertRegex(stub, r"boolean partial = data\.readInt\(\) != 0;\s*"
                               r"int kind = data\.dataAvail\(\) > 0 \? data\.readInt\(\) : KIND_MISSING;\s*"
                               r"boolean called = data\.dataAvail\(\) > 0 && data\.readInt\(\) != 0;\s*"
                               r"heard\(text, side, angle, tier, at, partial, kind, called\);")
        proxy = src.split("private static class Proxy implements Callback", 1)[1]
        self.assertRegex(proxy, r"data\.writeInt\(partial \? 1 : 0\);\s*data\.writeInt\(kind\);\s*"
                                r"data\.writeInt\(called \? 1 : 0\);\s*"
                                r"remote\.transact\(TRANSACTION_heard")
        client = _read(EARS_CLIENT)
        self.assertRegex(client, r"void onHeard\(String \w+, int \w+, float \w+, int \w+, long \w+, "
                                 r"boolean \w+, int \w+,\s+boolean \w+\);")
        self.assertRegex(client, r'NO_KIND\s*=\s*"the launcher\'s ears session sends no cue kind '
                                 r'\(install both APKs together\)"')
        heard = _method_body(client, "public void heard")
        self.assertIsNotNone(heard)
        self.assertRegex(heard, r"if \(kind == RobotEars\.KIND_MISSING\)\s*\{\s*lost\(NO_KIND\);\s*return;")
        self.assertIn("listener.onHeard(text, side, angle, tier, at, partial, kind, called)", heard)

    def test_ears_callback_already_called_flag_is_appended_after_the_kind(self):
        """Hey Miko plan U3 (KTD4): the end-of-utterance delivery of a call the
        early cue already made carries a flag, appended after the kind (never
        reordered). An older launcher's parcel ends before it: read as false."""
        src = _read(EARS_INTERFACE)
        head = src.split("abstract class Stub", 1)[0]
        self.assertRegex(head, r"void heard\(String text, int side, float angle, int tier, long at, boolean partial, "
                               r"int kind,\s*boolean called\)")
        self.assertEqual(src.count("TRANSACTION_heard = 1;"), 1)
        ears = _read(EARS)
        self.assertIn("final boolean called;", ears)
        self.assertRegex(ears, r"Utterance\(String \w+, int \w+, Float \w+, int \w+, long \w+, boolean \w+, "
                               r"int \w+, boolean called\)")
        engine = _read(LAUNCHER / "ListenEngine.java")
        self.assertRegex(engine, r"callback\.heard\(u\.text, u\.side, [^;]*?u\.partial,\s*u\.kind, u\.called\);")

    def test_ears_session_capture_ignores_the_charger_latch(self):
        """Hey Miko plan U3 (KTD5): the capture rule no longer closes on the
        charger latch, and a conversation listen is no longer refused docked."""
        body = _method_body(_read(EARS), "private void reconcile")
        self.assertIsNotNone(body)
        self.assertRegex(body, r"boolean want = keeper\.holder\(\) != null;")
        self.assertNotIn("charger", body)
        listen = _method_body(_read(EARS), "synchronized boolean listen")
        self.assertIsNotNone(listen)
        self.assertNotIn("charger", listen)

    def test_ears_session_names_the_kind_and_the_engine_relays_it_last(self):
        ears = _read(EARS)
        self.assertRegex(ears, r"Utterance\(String \w+, int \w+, Float \w+, int \w+, long \w+, boolean \w+, "
                               r"int kind\)")
        self.assertIn("final int kind;", ears)
        self.assertIn("int kind = CueClassifier.kind(text, wasWake, tier);", ears)
        self.assertIn("new Utterance(text, side, angle, tier, at, partial, kind, wasWake)", ears)
        self.assertRegex(ears, r"new Utterance\(\"\", CueClassifier\.SIDE_NONE, null, CueClassifier\.TIER_STRONG, "
                               r"now, false,\s*CueClassifier\.KIND_WAKE_WORD\)")
        engine = _read(LAUNCHER / "ListenEngine.java")
        self.assertRegex(engine, r"callback\.heard\(u\.text, u\.side, [^;]*?u\.partial,\s*u\.kind, u\.called\);")

    def test_ears_proxy_detects_an_older_launcher(self):
        """KTD11: every new proxy method checks the transaction result."""
        proxy = _read(EARS_INTERFACE).split("abstract class Stub extends Binder", 1)[1]
        proxy = proxy.split("class Proxy implements RobotEars", 1)[1]
        # Every transact() in the proxy is checked: none is a bare call.
        self.assertGreaterEqual(proxy.count("remote.transact("), 4)
        self.assertEqual(proxy.count("remote.transact("), proxy.count("if (!remote.transact("))
        self.assertIn("throw new RemoteException(", proxy)

    def test_listen_transaction_codes_unchanged_and_ears_codes_appended(self):
        """Existing codes in the listen Binder stay; the ears Binder's own codes
        count up from 1 in declaration order, so a later method is appended."""
        listen = _read(INTERFACE)
        self.assertRegex(listen, r"TRANSACTION_listen\s*=\s*1;")
        for name, code in (("heard", 1), ("noSpeech", 2), ("failed", 3)):
            self.assertRegex(listen, rf"TRANSACTION_{name}\s*=\s*{code};")
        callback, ears = _read(EARS_INTERFACE).split("implements RobotEars {", 1)
        codes = [(n, int(c)) for n, c in re.findall(r"TRANSACTION_(\w+)\s*=\s*(\d+);", ears)]
        self.assertEqual([n for n, _ in codes], ["open", "renew", "close", "listen", "clipWindow", "shoved"])
        self.assertEqual([c for _, c in codes], list(range(1, 7)))
        self.assertRegex(callback, r"TRANSACTION_heard\s*=\s*1;")

    def test_ears_client_binds_renews_and_closes(self):
        src = _read(EARS_CLIENT)
        for needle in ("new Intent(LauncherProtocol.ROBOT_EARS_ACTION)", "setPackage(LauncherProtocol.LAUNCHER_PACKAGE)",
                       "bindService(", "unbindService(", "RobotEars.Stub.asInterface(", "RENEW_PERIOD_MS",
                       "scheduleAtFixedRate(", "shutdownNow()", "extends RobotEars.Callback.Stub"):
            self.assertIn(needle, src)
        body = _method_body(src, "public void close")
        self.assertIsNotNone(body, "client has no close()")
        self.assertIn("unbindService(", body)
        self.assertRegex(body, r"if \(closed\)\s*\{?\s*return;")


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

    def test_stages_the_dsp_direction_library_and_fails_without_it(self):
        """Meeting plan U1, KTD4: libconexant_dsp_lib.so rides with libmiko_drivers.so;
        a missing copy fails the build naming the file and the directory searched."""
        b = self.build
        self.assertEqual(b.DSP_LIB, "libconexant_dsp_lib.so")
        libs = b.vendor_native_libs(VENDOR_LIB_DIR)
        self.assertEqual(sorted(Path(p).name for _, p in libs),
                         sorted(["libconexant_dsp_lib.so", "libmiko_drivers.so", *WAKEWORD_LIBS]))
        self.assertTrue(all(abi == "arm64-v8a" and Path(p).is_file() for abi, p in libs), libs)
        with tempfile.TemporaryDirectory() as td:
            (Path(td) / "libmiko_drivers.so").write_bytes(b"so")
            with self.assertRaises(b.BuildError) as ctx:
                b.vendor_native_libs(Path(td))
        msg = str(ctx.exception)
        self.assertIn("libconexant_dsp_lib.so", msg)
        self.assertIn(td, msg)

    def test_wake_word_libraries_come_from_the_shared_list(self):
        """Meeting plan U3: the launcher and mode-voice stage the same three
        wake-word libraries from build_common; a missing one names them all."""
        b = self.build
        self.assertEqual(b.bc.WAKEWORD_LIBS, WAKEWORD_LIBS)
        self.assertNotIn("WAKEWORD_LIBS = (", self.src, "the launcher build keeps its own list")
        with tempfile.TemporaryDirectory() as td:
            (Path(td) / "libmiko_drivers.so").write_bytes(b"so")
            (Path(td) / "libconexant_dsp_lib.so").write_bytes(b"so")
            with self.assertRaises(b.BuildError) as ctx:
                b.vendor_native_libs(Path(td))
        msg = str(ctx.exception)
        for name in WAKEWORD_LIBS:
            self.assertIn(name, msg)
        self.assertIn(td, msg)
        self.assertTrue(b.bc.WAKEWORD_MODEL.is_file(), b.bc.WAKEWORD_MODEL)
        self.assertIn("WAKEWORD_MODEL", self.src)

    def test_bpe_vocab_is_derived_from_the_models_bpe_model(self):
        """KTD2: hotwords bias nothing without the bpe vocabulary. The sherpa
        tarball ships none, so the build fetches the package's own bpe.model
        (the source its README names, pinned) and writes bpe.vocab beside
        tokens.txt the way sherpa's export_bpe_vocab.py does: piece, tab, score."""
        b = self.build
        self.assertTrue(b.BPE_MODEL_URL.startswith("https://huggingface.co/desh2608/"), b.BPE_MODEL_URL)
        self.assertRegex(b.BPE_MODEL_SHA256, r"^[0-9a-f]{64}$")
        # A two-piece sentencepiece ModelProto: pieces {piece="<blk>", score=0} and {piece="▁HEY", score=-1.5}.
        piece1 = b"\x0a\x05<blk>\x15\x00\x00\x00\x00\x18\x04"
        piece2 = b"\x0a\x06\xe2\x96\x81HEY\x15\x00\x00\xc0\xbf"
        model = b"\x0a" + bytes([len(piece1)]) + piece1 + b"\x0a" + bytes([len(piece2)]) + piece2
        # An unrelated top-level field (trainer_spec, wire type 2) is skipped.
        model += b"\x12\x02\x08\x01"
        self.assertEqual(b.bpe_vocab(model), "<blk>\t0.0\n▁HEY\t-1.5\n")
        with self.assertRaises(b.BuildError):
            b.bpe_vocab(b"")

    def test_listen_extras_land_beside_tokens_and_fail_when_absent(self):
        b = self.build
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            (root / "listen").mkdir()
            (root / "listen" / "tokens.txt").write_text("<blk> 0\n")
            fake_model = root / "bpe.model"
            fake_model.write_bytes(b"\x0a\x0c\x0a\x05<blk>\x15\x00\x00\x00\x00")
            fake_vad = root / "silero_vad.onnx"
            fake_vad.write_bytes(b"onnx")

            def fetch(url, dest, sha256):
                return fake_model if url == b.BPE_MODEL_URL else fake_vad

            b.listen_extras(root, fetch=fetch)
            self.assertEqual((root / "listen" / "bpe.vocab").read_text(), "<blk>\t0.0\n")
            self.assertEqual((root / "listen" / "silero_vad.onnx").read_bytes(), b"onnx")
            self.assertEqual(sorted(p.name for p in (root / "listen").iterdir()),
                             ["bpe.vocab", "silero_vad.onnx", "tokens.txt"])

            def broken(url, dest, sha256):
                raise b.BuildError(f"!! cannot fetch {url}")

            (root / "listen" / "bpe.vocab").unlink()
            with self.assertRaises(b.BuildError) as ctx:
                b.listen_extras(root, fetch=broken)
            self.assertIn("bpe.model", str(ctx.exception))
        self.assertIn("listen_extras(", self.src)

    def test_hotwords_file_is_a_build_precondition_at_the_asset_root(self):
        b = self.build
        self.assertEqual(b.HOTWORDS, REPO / "launcher" / "assets" / "hotwords.txt")
        self.assertTrue(HOTWORDS.is_file())
        with tempfile.TemporaryDirectory() as td:
            with self.assertRaises(b.BuildError) as ctx:
                b.check_hotwords(Path(td) / "hotwords.txt")
        self.assertIn("hotwords.txt", str(ctx.exception))

    def test_jni_stubs_match_the_vendor_symbols(self):
        """The library resolves natives by package, class and method name, so the
        stubs must carry the vendor's declarations exactly (docs/hardware/voice-mic.md section 3)."""
        for name in ("ConexantDSP.java", "NCDsp.java"):
            with self.subTest(file=name):
                ours = _read(JNI_STUBS / name)
                theirs = _read(VENDOR_JNI / name)
                self.assertTrue(ours, f"{name} stub missing")
                self.assertIn("package com.example.conexantapi;", ours)
                self.assertIn('System.loadLibrary("conexant_dsp_lib")', ours)
                natives = re.findall(r"public native [^;]+;", theirs)
                self.assertTrue(natives, f"vendor {name} declares no natives")
                for decl in natives:
                    self.assertIn(" ".join(decl.split()), " ".join(ours.split()), decl)

    def test_voice_direction_is_plain_java_and_names_its_backend(self):
        raw = DIRECTION.read_text() if DIRECTION.exists() else ""
        self.assertTrue(raw, "VoiceDirection.java missing")
        self.assertEqual([ln for ln in raw.splitlines() if ln.startswith("import android")], [])
        src = _strip_comments(raw)
        for needle in ("enum Backend", "NONE", "CONEXANT", "NC", "getDSPRawDOA(", "NcFrames.doaQuery(",
                       "initDSPComm(", "stty"):
            self.assertIn(needle, src)
        # KTD4: the angle is sampled on its own thread at a caller-set cadence.
        self.assertRegex(src, r"sample\(\s*(final\s+)?long\s+\w+")

    def test_the_ears_never_open_the_direction_chip_on_the_capture_thread(self):
        # Review P1 (2026-09-29): EarsSession.feed starts direction sampling while holding
        # feedLock, so the first chip open must happen on VoiceDirection's own thread.
        engine = _strip_comments(ENGINE.read_text())
        self.assertIn("VoiceDirection.sampleLazily(", engine)
        self.assertNotIn("VoiceDirection.open().sample(", engine)

    def test_voice_direction_tries_nc_only_on_the_confirmed_port_property_and_node(self):
        # Seen live 2026-09-28: with no /dev/ttyMT2, createUART still answers a handle and
        # initNCUART answers 1, so the next native call (getCurrentDOAStatus) segfaulted the
        # whole launcher on the first speech the ears heard. Explore plan U2 (KTD10): the
        # port now comes only from the owner-confirmed property, its node must exist before
        # any NC native call, only 0 from initNCUART is success, and the vendor's blocking
        # native reads are never made. The behaviour runs in scripts/tests/test_nc_frames.py.
        src = _strip_comments(DIRECTION.read_text())
        self.assertNotIn("/dev/ttyMT2", src)
        self.assertIn('"persist.miko3.voice_dir.port"', src)
        body = _method_body(src, "private static VoiceDirection openNc") or ""
        port, node, setup = body.find("isEmpty()"), body.find(".exists(node)"), body.find("configure(node)")
        self.assertGreaterEqual(port, 0, "the NC path never checks the port property")
        self.assertGreater(node, port, "the NC path does not check the node after the property")
        self.assertGreater(setup, node, "the port is configured before the node check")
        self.assertRegex(body, r"status\s*!=\s*0")
        # 2026-09-29: with the vendor's createUART/initNCUART in the process the launcher
        # segfaulted seconds after every chip open; the NC path now runs no vendor code.
        for vendor_call in ("NCDsp", "createUART(", "initNCUART(", "getCurrentDOAStatus(", "toggleDOA(",
                            "getFWVersion("):
            self.assertNotIn(vendor_call, src)
        self.assertIn("new File(path).exists()", src)
        # The launcher reads the properties and hands them over before the first open().
        engine = _read(ENGINE)
        for prop in ("PORT_PROPERTY", "ZERO_PROPERTY", "SIGN_PROPERTY", "SCALE_PROPERTY"):
            self.assertIn(f"SpeechEngine.systemProperty(VoiceDirection.{prop})", engine)
        self.assertIn("VoiceDirection.configure(", engine)

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
        self.assertEqual(_log_word_offenders((SERVICE, ENGINE, SESSION, APP, PROBE, LAUNCHER / "PeopleService.java",
                                              LAUNCHER / "PeopleStore.java", EARS, CLASSIFIER, KEEPER,
                                              EARS_INTERFACE, EARS_CLIENT)), [])

    def test_ears_session_and_classifier_never_print(self):
        for path in (EARS, CLASSIFIER, KEEPER):
            src = _read(path)
            with self.subTest(file=path.name):
                for needle in ("System.out", "System.err", "printStackTrace"):
                    self.assertNotIn(needle, src)


if __name__ == "__main__":
    unittest.main()
