"""Tests for scripts/gen-explore-voice.py: the spoken names explore says when it
inspects something (camera curiosity plan U6, KTD7; robot voice plan U6, KTD7,
R14). The clips are rendered with the robot's trained Piper voice through
sherpa-onnx, whose output can differ between machines and library versions, so
the committed clips are checked for presence and format (Opus in WebM, a sane
length and size), never for byte equality."""
import importlib.util
import shutil
import subprocess
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
REPO = Path(__file__).resolve().parents[2]
SCRIPT = HERE.parent / "gen-explore-voice.py"
SOUNDS = HERE.parent / "gen-explore-sounds.py"
ASSETS = ROOT / "mode-explore" / "assets"
VOCABULARY = ASSETS / "vocabulary.txt"


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load {path}")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


voice = load("gen_explore_voice", SCRIPT)
sounds = load("gen_explore_sounds_for_voice", SOUNDS)


class VocabularyTest(unittest.TestCase):
    def test_vocabulary_is_the_shipped_vocabulary_file(self):
        self.assertEqual(voice.VOCABULARY_FILE, VOCABULARY)
        self.assertEqual(voice.VOCABULARY, voice.read_vocabulary(VOCABULARY))
        self.assertGreaterEqual(len(voice.VOCABULARY), 300)
        self.assertEqual(len(set(voice.VOCABULARY)), len(voice.VOCABULARY))
        for label in ("person", "cat", "dog", "plant", "succulent", "mouse", "teddy bear"):
            self.assertIn(label, voice.VOCABULARY)

    def test_every_special_case_is_a_real_name(self):
        # A typo in FRIENDLY or NO_ARTICLE would silently do nothing.
        names = set(voice.VOCABULARY)
        for table in (voice.FRIENDLY, voice.NO_ARTICLE, voice.A_NOT_AN):
            self.assertEqual(set(table) - names, set())


class SlugAndPhraseTest(unittest.TestCase):
    def test_slug_is_lowercase_with_single_dashes(self):
        self.assertEqual(voice.slug("guinea pig"), "guinea-pig")
        self.assertEqual(voice.slug("cup"), "cup")
        self.assertEqual(voice.slug("t-shirt"), "t-shirt")
        self.assertEqual(voice.slug("rubik's cube"), "rubik-s-cube")
        self.assertEqual(voice.slug("TV"), "tv")

    def test_slugs_are_unique(self):
        slugs = [voice.slug(n) for n in voice.VOCABULARY]
        self.assertEqual(len(set(slugs)), len(slugs))

    def test_clip_name(self):
        self.assertEqual(voice.clip_name("cell phone"), "name-cell-phone.webm")

    def test_friendly_wording(self):
        self.assertEqual(voice.phrase("cell phone"), "ooh, a phone")
        self.assertEqual(voice.phrase("tv"), "ooh, a TV")
        self.assertEqual(voice.phrase("rubik's cube"), "ooh, a Rubik's cube")

    def test_article_follows_the_first_sound(self):
        self.assertEqual(voice.phrase("apple"), "ooh, an apple")
        self.assertEqual(voice.phrase("umbrella"), "ooh, an umbrella")
        self.assertEqual(voice.phrase("ukulele"), "ooh, a ukulele")
        self.assertEqual(voice.phrase("usb stick"), "ooh, a USB stick")
        self.assertEqual(voice.phrase("plant"), "ooh, a plant")

    def test_plural_and_mass_nouns_take_no_article(self):
        self.assertEqual(voice.phrase("scissors"), "ooh, scissors")
        self.assertEqual(voice.phrase("socks"), "ooh, socks")
        self.assertEqual(voice.phrase("lego"), "ooh, Lego")

    def test_every_label_has_a_phrase(self):
        for label in voice.VOCABULARY:
            self.assertTrue(voice.phrase(label).startswith("ooh, "), label)


class FakeAudio:
    def __init__(self, seconds, rate):
        self.sample_rate = rate
        n = int(seconds * rate)
        self.samples = [0.5 if i % 2 else -0.5 for i in range(n)]


class FakeTts:
    """Stands in for sherpa_onnx.OfflineTts: speech 3 s long at speed 1, shorter
    when spoken faster."""
    def __init__(self, seconds=3.0, rate=None):
        self.seconds, self.rate, self.calls = seconds, rate or voice.RATE, []

    def generate(self, text, sid=0, speed=1.0):
        self.calls.append((text, sid, speed))
        return FakeAudio(self.seconds / speed, self.rate)


class TrainedVoiceTest(unittest.TestCase):
    def test_the_voice_is_the_launchers_trained_model(self):
        self.assertEqual(voice.VOICE_DIR, ROOT / "launcher" / "assets" / "voice")

    def test_no_say_and_no_robot_effect(self):
        # R14/KTD7: the trained voice as it is, no macOS `say`, no ring-mod buzz.
        src = SCRIPT.read_text(encoding="utf-8")
        for gone in ('"say"', "aeval", "vibrato", "asetrate", "sin(2*PI"):
            self.assertNotIn(gone, src)
        self.assertFalse(hasattr(voice, "FILTERS"))
        self.assertFalse(hasattr(voice, "PITCH"))

    def test_render_speaks_the_phrase(self):
        tts = FakeTts(seconds=1.0)
        samples = voice.render("apple", tts)
        self.assertEqual(tts.calls, [("ooh, an apple", 0, 1.0)])
        self.assertLessEqual(len(samples) / voice.RATE, voice.MAX_SECONDS)
        self.assertEqual(max(abs(s) for s in samples), round(voice.PEAK * 32767))

    def test_render_speeds_up_a_long_phrase(self):
        tts = FakeTts(seconds=3.0)
        samples = voice.render("plant", tts)
        self.assertGreater(len(tts.calls), 1)
        self.assertGreater(tts.calls[-1][2], 1.0)
        self.assertLessEqual(len(samples) / voice.RATE, voice.MAX_SECONDS)

    def test_render_rejects_a_voice_at_another_rate(self):
        with self.assertRaises(RuntimeError):
            voice.render("plant", FakeTts(seconds=1.0, rate=16000))


class CommittedNameClipsTest(unittest.TestCase):
    def test_voice_renders_at_the_sound_generator_rate(self):
        self.assertEqual(voice.RATE, sounds.RATE)

    def test_every_label_has_a_webm_clip(self):
        for label in voice.VOCABULARY:
            path = ASSETS / voice.clip_name(label)
            self.assertTrue(path.exists(), f"{path} missing; run scripts/gen-explore-voice.py")
            data = path.read_bytes()
            self.assertEqual(data[:4], b"\x1a\x45\xdf\xa3", f"{path.name} is not WebM/Matroska")
            self.assertIn(b"webm", data[:64], path.name)
            self.assertIn(b"A_OPUS", data[:512], path.name)
            self.assertTrue(1000 < len(data) < 16000, f"{path.name}: {len(data)} bytes")

    @unittest.skipUnless(shutil.which("ffprobe"), "ffprobe not installed")
    def test_clips_fit_the_brains_name_budget(self):
        for label in voice.VOCABULARY:
            path = ASSETS / voice.clip_name(label)
            if not path.exists():
                continue  # reported by test_every_label_has_a_webm_clip
            seconds = voice.clip_seconds(path)
            self.assertTrue(0.3 < seconds < 2.5, f"{path.name}: {seconds:.2f}s")

    @unittest.skipUnless(shutil.which("ffmpeg"), "ffmpeg not installed")
    def test_a_clip_is_loud_and_unclipped(self):
        path = ASSETS / voice.clip_name("plant")
        out = subprocess.run(["ffmpeg", "-v", "error", "-i", str(path), "-af", "volumedetect",
                              "-f", "null", "-"], capture_output=True, text=True).stderr
        out += subprocess.run(["ffmpeg", "-i", str(path), "-af", "volumedetect", "-f", "null", "-"],
                              capture_output=True, text=True).stderr
        peak = float(out.split("max_volume:")[1].split("dB")[0])
        self.assertTrue(-8.0 < peak < 0.0, f"peak {peak} dB")

    def test_no_stray_name_clips(self):
        expected = {voice.clip_name(label) for label in voice.VOCABULARY}
        present = {p.name for p in ASSETS.glob("name-*")}
        self.assertEqual(present, expected)


class LineClipsTest(unittest.TestCase):
    """The conversation's line clips (meeting plan U8, KTD12): every group the brain
    plays has phrasings, each renders as react-<group>-<n>.webm the way ClipPlayer
    indexes reactions, and the binary assets come from the same voice. The voice
    model needs sherpa-onnx, so a missing asset is reported as pending generation
    rather than invented."""

    GROUPS = ("sign-off", "one-sec", "deflect", "nothing-kept", "answer", "where")
    SESSION_GROUPS = ("sign-off", "one-sec", "deflect", "nothing-kept")

    def test_every_group_the_brain_plays_has_phrasings(self):
        self.assertEqual(tuple(voice.LINE_CLIPS), self.GROUPS)
        for group, phrases in voice.LINE_CLIPS.items():
            self.assertTrue(phrases, group)
            for phrase in phrases:
                self.assertTrue(phrase.strip(), group)
                self.assertLess(len(phrase.split()), 10, phrase)

    def test_the_brain_and_the_session_play_these_groups(self):
        brain = (REPO / "mode-explore" / "src" / "com" / "miko3" / "mode" / "explore" / "ExploreBrain.java").read_text()
        session = (REPO / "mode-explore" / "src" / "com" / "miko3" / "mode" / "explore" / "ChatSession.java").read_text()
        self.assertIn('playReaction("acknowledge")', brain)
        self.assertIn('playReaction("answer")', brain)
        self.assertIn('playReaction("where")', brain)
        for group in self.SESSION_GROUPS:
            self.assertIn(f'"{group}"', session, group)

    def test_the_call_lines_are_the_owners_short_answers(self):
        # A call is answered from a small set of short on-robot lines (R4), and a
        # search that finds nobody asks where the caller went (R10).
        self.assertEqual(voice.LINE_CLIPS["answer"], ["oh hi?", "what?", "yes?"])
        self.assertEqual(voice.LINE_CLIPS["where"], ["where'd you go?"])

    def test_the_call_clips_are_generated(self):
        # Unlike older groups, the call clips must ship: the answer never waits for TTS.
        for name, _ in voice.line_clips():
            if name.startswith(("react-answer-", "react-where-")):
                self.assertTrue((ASSETS / name).exists(), f"{name} missing: run scripts/gen-explore-voice.py")

    def test_clip_names_follow_the_reaction_index(self):
        names = [n for n, _ in voice.line_clips()]
        self.assertEqual(names[:2], ["react-sign-off-1.webm", "react-sign-off-2.webm"])
        self.assertEqual(len(names), len(set(names)))
        for n in names:
            self.assertRegex(n, r"^react-[a-z-]+-\d+\.webm$")

    def test_clip_player_indexes_webm_reactions(self):
        player = (REPO / "mode-explore" / "src" / "com" / "miko3" / "mode" / "explore" / "ClipPlayer.java").read_text()
        self.assertIn('name.endsWith(".webm")', player)
        self.assertIn('name.startsWith("react-")', player)

    def test_clip_player_keeps_an_answer_prepared(self):
        # The answer must start within the brain step that takes the call (KTD3), so
        # ClipPlayer holds one answer variant prepared and prepares the next after it plays.
        player = (REPO / "mode-explore" / "src" / "com" / "miko3" / "mode" / "explore" / "ClipPlayer.java").read_text()
        self.assertIn('ANSWER_GROUP = "answer"', player)
        self.assertIn("readyAnswer", player)
        self.assertIn("prepareNextAnswer()", player)

    def test_generate_writes_the_line_clips_after_the_names(self):
        src = SCRIPT.read_text()
        self.assertIn("for name, text in line_clips():", src)
        self.assertIn("render_phrase(text, tts)", src)

    def test_line_clip_assets_are_webm_opus_when_present(self):
        pending = []
        for name, _ in voice.line_clips():
            path = ASSETS / name
            if not path.exists():
                pending.append(name)
                continue
            data = path.read_bytes()
            self.assertEqual(data[:4], b"\x1a\x45\xdf\xa3", f"{name} is not WebM/Matroska")
            self.assertIn(b"A_OPUS", data[:512], name)
            self.assertTrue(500 < len(data) < 40000, f"{name}: {len(data)} bytes")
        if pending:
            self.skipTest("line clips pending generation (needs sherpa-onnx and the voice model): "
                          "run scripts/gen-explore-voice.py; missing " + ", ".join(pending))


if __name__ == "__main__":
    unittest.main()
