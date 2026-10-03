"""Host-side tests for the launcher's voice identification (owner 2026-10-02).

The plain-Java VoiceStore (per-person embeddings, bands, cap, forget, its file),
VoiceTuning and VoiceId (the capped utterance buffer and its single background
embedding thread) run under a JVM harness with a fake embedder. The ears session's
hooks are proven in the listen-service harness (ears_voice_* scenarios). The
Android/sherpa glue (the extractor, the Binder field, the model staging) is covered
by source-wiring checks and the build script's own functions.
"""
import importlib.util
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
HARNESS = TESTS / "fixtures" / "voice_id_harness" / "src"
HARNESS_MAIN = HARNESS / "com" / "miko3" / "launcher" / "VoiceIdHarness.java"
VOICE_ID = LAUNCHER / "VoiceId.java"
VOICE_STORE = LAUNCHER / "VoiceStore.java"
VOICE_TUNING = LAUNCHER / "VoiceTuning.java"
VOICE_PRINTS = LAUNCHER / "VoicePrints.java"
EMBEDDER = LAUNCHER / "SherpaVoiceEmbedder.java"
EARS = LAUNCHER / "EarsSession.java"
ENGINE = LAUNCHER / "ListenEngine.java"
EARS_INTERFACE = SHARED / "RobotEars.java"
EARS_CLIENT = SHARED / "RobotEarsClient.java"
BUILD_PY = REPO / "scripts" / "build-custom-launcher.py"
BENCH_PY = REPO / "scripts" / "qa-voice-bench.py"
APK = REPO / "launcher" / "miko3-launcher.apk"


def _strip_comments(text):
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def _read(path):
    return _strip_comments(path.read_text())


def _method_body(src, signature):
    i = src.find(signature)
    if i < 0:
        return None
    j = src.find("{", i)
    depth = 0
    for k in range(j, len(src)):
        if src[k] == "{":
            depth += 1
        elif src[k] == "}":
            depth -= 1
            if depth == 0:
                return src[j:k + 1]
    return None


class VoiceIdHarnessTest(unittest.TestCase):
    SCENARIOS = (
        "tuning_defaults_and_bands",
        "tuning_properties_override_and_bad_values_fall_back",
        "store_empty_matches_nobody",
        "store_bands_follow_the_thresholds",
        "store_picks_the_closest_person",
        "store_near_tie_is_only_weak",
        "store_caps_each_person_keeping_the_newest",
        "store_forget_removes_a_person_and_persists",
        "store_persists_and_reloads_embeddings",
        "store_unreadable_file_starts_empty",
        "store_ignores_embeddings_of_another_size",
        "store_file_holds_no_ids_in_its_logs",
        "buffer_caps_at_eight_seconds_keeping_the_start",
        "buffer_trims_the_silence_after_the_last_speech",
        "buffer_short_speech_is_not_embedded_and_is_cleared",
        "buffer_unclean_answer_is_not_embedded_and_is_cleared",
        "buffer_start_and_reset_drop_the_previous_utterance",
        "no_embedder_yet_skips_quietly",
        "embedding_runs_on_one_low_priority_background_thread",
        "a_known_voice_is_reported_with_its_band",
        "enrol_by_utterance_time_then_match_then_forget",
        "score_an_answer_against_a_person_or_another_answer",
        "only_the_most_recent_embeddings_are_kept_for_enrolment",
        "a_failing_embedder_is_logged_by_kind_and_reports_nothing",
    )

    @classmethod
    def setUpClass(cls):
        jdk = jvm_harness.find_jdk()
        if jdk is None:
            raise unittest.SkipTest("no JDK (javac + java) found")
        cls._td = tempfile.TemporaryDirectory(prefix="voice_id_harness_")
        out = cls._td.name
        c = subprocess.run(jvm_harness.javac_cmd(jdk[0], out, [HARNESS_MAIN], [HARNESS, LAUNCHER_SRC, SHARED_SRC]),
                           capture_output=True, text=True)
        cls.compiled = c.returncode == 0
        cls.compile_output = (c.stdout + c.stderr)[-3000:]
        cls.results, cls.run_output = {}, ""
        if cls.compiled:
            r = subprocess.run([jdk[1], "-cp", out, "com.miko3.launcher.VoiceIdHarness"],
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


jvm_harness.add_scenario_tests(VoiceIdHarnessTest)


class PlainJavaTest(unittest.TestCase):
    def test_store_tuning_and_buffer_are_plain_java(self):
        """Proven in the host harness, so none may touch android.* or sherpa."""
        for path in (VOICE_ID, VOICE_STORE, VOICE_TUNING, VOICE_PRINTS):
            src = path.read_text()
            self.assertNotIn("import android.", src, path.name)
            self.assertNotIn("com.k2fsa", src, path.name)


class PrivacyTest(unittest.TestCase):
    def test_no_audio_is_ever_written(self):
        """Embeddings only: the buffer lives in memory, and only VoiceStore writes a file."""
        for path in (VOICE_ID, VOICE_TUNING, VOICE_PRINTS, EMBEDDER):
            src = _read(path)
            for needle in ("FileOutputStream", "RandomAccessFile", "Files.write", "FileWriter", ".wav"):
                self.assertNotIn(needle, src, f"{path.name} writes {needle}")
        store = _read(VOICE_STORE)
        save = _method_body(store, "private void save")
        self.assertIsNotNone(save)
        self.assertIn("writeFloat(", save)
        self.assertNotIn("short", save)
        # The ears pass samples only to VoiceId, never to a file.
        ears = _read(EARS)
        self.assertNotIn("FileOutputStream", ears)

    def test_logs_never_carry_names_ids_or_audio(self):
        """Every log line is built from counts, timings, scores and bands; never an id, a name or samples."""
        offenders = []
        for path in (VOICE_ID, VOICE_STORE, EMBEDDER):
            for stmt in re.findall(r"diag\.log\((.*?)\);", _read(path), flags=re.S):
                bare = re.sub(r'"(?:\\.|[^"\\])*"', "", stmt)
                if re.search(r"\b(id|personId|person|name|text|samples|snapshot|buf|embedding)\b", bare):
                    offenders.append(f"{path.name}: {' '.join(stmt.split())}")
        self.assertEqual(offenders, [])
        engine = _read(ENGINE)
        for stmt in re.findall(r"Log\.\w\(TAG, (.*?)\);", engine, flags=re.S):
            if "voice" in stmt:
                self.assertNotRegex(re.sub(r'"(?:\\.|[^"\\])*"', "", stmt), r"\b(person|personId|id)\b")

    def test_store_file_is_app_private(self):
        engine = _read(ENGINE)
        self.assertRegex(engine, r'new VoiceStore\(new File\(context\.getFilesDir\(\), "voiceprints\.bin"\)')


class WiringTest(unittest.TestCase):
    def test_ears_feed_the_buffer_on_every_decode_and_reset_it_with_the_utterance(self):
        ears = _read(EARS)
        decode = _method_body(ears, "private void decode(")
        self.assertRegex(decode, r"VoiceId v = voice;\s*if \(v != null\)\s*\{\s*v\.append\(buf, n\);")
        self.assertIn("void setVoice(VoiceId v)", ears)
        # The onset starts a fresh buffer before the pre-roll head goes in.
        feed = _method_body(ears, "void feed(")
        onset = feed.split("speechStartMs = now;", 1)[1].split("drainPreroll()", 1)[0]
        self.assertIn("voiceStart();", onset)
        self.assertIn("voiceSpeech();", feed)
        drop = _method_body(ears, "private void dropUtterance(")
        self.assertIn("voiceReset();", drop)

    def test_only_a_clean_listen_answer_is_identified_after_its_delivery(self):
        """A conversation answer with words and a tier, not clipped by the deaf window,
        and only after deliver(): the words never wait for the embedding."""
        end = _method_body(_read(EARS), "private void endUtterance(")
        self.assertRegex(end, r"boolean answer = uttCapAt != Long\.MAX_VALUE;")
        tail = end.split("deliver(new Utterance(text, side, angle, tier, at, partial, kind, wasWake, message));", 1)
        self.assertEqual(len(tail), 2, "the delivery moved")
        self.assertRegex(tail[1], r"voiceAnswered\(at, answer && !partial && !text\.isEmpty\(\)\)")
        self.assertNotIn("voiceAnswered(", tail[0])
        # The dropped path clears the buffer.
        dropped = end.split("if (tier == CueClassifier.TIER_NONE", 1)[1].split("return;", 1)[0]
        self.assertIn("voiceReset();", dropped)

    def test_embedder_is_single_threaded_low_priority_and_loaded_off_the_recogniser(self):
        emb = _read(EMBEDDER)
        self.assertIn(".setNumThreads(1)", emb)
        self.assertIn("Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)", emb)
        for needle in ("createStream()", "acceptWaveform(", "inputFinished()", "isReady(", "compute(", "release()"):
            self.assertIn(needle, emb)
        vid = _read(VOICE_ID)
        self.assertIn('"voice-id"', vid)
        self.assertIn("Thread.MIN_PRIORITY", vid)
        engine = _read(ENGINE)
        self.assertIn('installAssets(context, VOICE_ASSETS)', engine)
        self.assertRegex(engine, r'VOICE_ASSETS = "voiceid";')
        self.assertIn("voiceId.runOnVoiceThread(", engine)
        self.assertIn("ears.setVoice(voiceId);", engine)

    def test_voice_result_rides_a_new_one_way_callback_code(self):
        """Appended code 5, one-way: an older mode's Stub has no case for it (onTransact
        returns false, which a one-way sender never sees); a newer mode under an older
        launcher never receives one."""
        src = _read(EARS_INTERFACE)
        head = src.split("abstract class Stub", 1)[0]
        self.assertRegex(head, r"void voice\(long at, String person, float score, int band\) throws RemoteException;")
        for name, value in (("VOICE_NONE", 0), ("VOICE_WEAK", 1), ("VOICE_STRONG", 2)):
            self.assertRegex(src, rf"int {name}\s*=\s*{value};")
        self.assertIn("static final int TRANSACTION_voice = 5;", src)
        stub = src.split("case TRANSACTION_voice:", 1)[1].split("return true;", 1)[0]
        self.assertRegex(stub, r"long at = data\.readLong\(\);\s*String person = data\.readString\(\);\s*"
                               r"float score = data\.readFloat\(\);\s*voice\(at, person, score, data\.readInt\(\)\);")
        proxy = src.split("private static class Proxy implements Callback", 1)[1]
        self.assertRegex(proxy, r"data\.writeLong\(at\);\s*data\.writeString\(person\);\s*data\.writeFloat\(score\);\s*"
                                r"data\.writeInt\(band\);\s*remote\.transact\(TRANSACTION_voice, data, null, "
                                r"IBinder\.FLAG_ONEWAY\);")
        client = _read(EARS_CLIENT)
        self.assertRegex(client, r"default void onVoice\(long at, String person, float score, int band\)\s*\{\s*\}")
        self.assertIn("listener.onVoice(at, person, score, band);", client)
        # The band constants agree on both sides.
        store = _read(VOICE_STORE)
        for name, value in (("BAND_NONE", 0), ("BAND_WEAK", 1), ("BAND_STRONG", 2)):
            self.assertRegex(store, rf"static final int {name}\s*=\s*{value};")

    def test_engine_relays_the_result_on_the_delivery_thread(self):
        engine = _read(ENGINE)
        self.assertRegex(engine, r"public void voice\(final long at, final String person, final float score, "
                                 r"final int band\)")
        self.assertIn("callback.voice(at, person, score, band);", engine)
        ears = _read(EARS)
        self.assertRegex(ears, r"default void voice\(long at, String person, float score, int band\)\s*\{\s*\}")
        self.assertIn("void voiceHeard(long at, String person, float score, int band)", ears)

    def test_people_layer_interface(self):
        src = _read(VOICE_PRINTS)
        for m in (r"float\[\] lastEmbeddingFor\(long at\);",
                  r"boolean enrolVoice\(String personId, float\[\] embedding\);",
                  r"boolean enrolVoice\(String personId, long at\);",
                  r"boolean forgetVoice\(String personId\);",
                  r"int voiceCount\(String personId\);"):
            self.assertRegex(src, m)
        self.assertIn("implements VoicePrints", _read(VOICE_ID))
        self.assertRegex(_read(ENGINE), r"VoicePrints voicePrints\(\)\s*\{\s*return voiceId;")

    def test_bench_is_a_debug_property_and_has_a_script(self):
        tuning = _read(VOICE_TUNING)
        self.assertIn('BENCH_PROP = "debug.miko3.voice_bench"', tuning)
        engine = _read(ENGINE)
        self.assertIn("VoiceTuning.BENCH_PROP", engine)
        self.assertIn("voiceId.bench(", engine)
        self.assertTrue(BENCH_PY.is_file())
        bench = BENCH_PY.read_text()
        self.assertIn("debug.miko3.voice_bench", bench)
        self.assertIn("voice: bench", bench)


class BenchScriptTest(unittest.TestCase):
    def test_parses_and_formats_the_bench_lines(self):
        spec = importlib.util.spec_from_file_location("qa_voice_bench", BENCH_PY)
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        log = ("I ListenEngine: voice: model ready in 900 ms (dim 512)\n"
               "I ListenEngine: voice: bench dur 1500 ms: embedding in 210 ms (dim 512)\n"
               "I ListenEngine: voice: bench dur 1500 ms: embedding in 190 ms (dim 512)\n"
               "I ListenEngine: voice: bench dur 8000 ms: embedding in 900 ms (dim 512)\n"
               "I ListenEngine: voice: bench done\n")
        runs, dim = mod.parse_bench(log)
        self.assertEqual(runs, {1500: [210, 190], 8000: [900]})
        self.assertEqual(dim, 512)
        table = mod.format_bench(runs, dim)
        self.assertIn("1.5 s", table)
        self.assertIn("200", table)


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

    def test_pins_the_campplus_model(self):
        b = self.build
        self.assertEqual(b.VOICEID_MODEL, "3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx")
        self.assertEqual(b.VOICEID_MODEL_URL, "https://github.com/k2-fsa/sherpa-onnx/releases/download/"
                                              "speaker-recongition-models/" + b.VOICEID_MODEL)
        self.assertRegex(b.VOICEID_MODEL_SHA256, r"^[0-9a-f]{64}$")
        self.assertEqual(b.VOICEID_ASSET, "voiceid/model.onnx")

    def test_voiceid_model_staged_under_its_own_asset_dir_and_fails_when_absent(self):
        b = self.build
        with tempfile.TemporaryDirectory() as td:
            cache = Path(td) / "voiceid"
            fake = Path(td) / "downloaded.onnx"
            fake.write_bytes(b"onnx")
            seen = []

            def fetch(url, dest, sha256):
                seen.append((url, sha256))
                return fake

            root = b.voiceid_model(cache, fetch=fetch)
            self.assertEqual((root / "voiceid" / "model.onnx").read_bytes(), b"onnx")
            self.assertEqual(seen, [(b.VOICEID_MODEL_URL, b.VOICEID_MODEL_SHA256)])
            # Cached: a second call fetches nothing.
            b.voiceid_model(cache, fetch=fetch)
            self.assertEqual(len(seen), 1)

            def broken(url, dest, sha256):
                raise b.BuildError(f"!! cannot fetch {url}")

            with self.assertRaises(b.BuildError) as ctx:
                b.voiceid_model(Path(td) / "other", fetch=broken)
            self.assertIn(b.VOICEID_MODEL, str(ctx.exception))

    def test_main_stamps_and_bundles_the_voiceid_root(self):
        main = self.src.split("def main():", 1)[1]
        self.assertIn("voiceid_root = voiceid_model()", main)
        self.assertIn('(stamp_root / "voiceid" / STAMP).write_text(voice_stamp(voiceid_root / "voiceid")', main)
        self.assertRegex(main, r"\+ \[listen_root, voiceid_root, stamp_root, SHARED_ASSETS\]")

    def test_built_apk_bundles_the_voiceid_model_when_present(self):
        if not APK.exists() or APK.stat().st_mtime < BUILD_PY.stat().st_mtime:
            self.skipTest("launcher not built since the voice-id model was added")
        import zipfile
        names = set(zipfile.ZipFile(APK).namelist())
        for f in ("model.onnx", "stamp.txt"):
            self.assertIn(f"assets/voiceid/{f}", names)


if __name__ == "__main__":
    unittest.main()
