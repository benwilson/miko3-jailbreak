"""Tests for scripts/gen-explore-voice.py: the spoken names explore says when it
inspects something (camera curiosity plan U6, KTD7). The clips are rendered with
macOS `say`, whose output differs between machines, so the committed clips are
checked for presence and format (Opus in WebM, a sane length and size), never
for byte equality."""
import importlib.util
import shutil
import subprocess
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
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


if __name__ == "__main__":
    unittest.main()
