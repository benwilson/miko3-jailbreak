"""Tests for scripts/gen-explore-voice.py: the spoken names explore says when it
inspects something (camera curiosity plan U6, KTD7). The clips are rendered with
macOS `say`, whose output differs between machines, so the committed clips are
checked for presence and format, never for byte equality."""
import array
import importlib.util
import re
import unittest
import wave
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
SCRIPT = HERE.parent / "gen-explore-voice.py"
SOUNDS = HERE.parent / "gen-explore-sounds.py"
ASSETS = ROOT / "mode-explore" / "assets"
BOX = ROOT / "tools" / "serviceexam_jadx" / "sources" / "com" / "miko" / "objectDetection" / "Box.java"


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
    def test_vocabulary_is_the_80_coco_labels(self):
        self.assertEqual(len(voice.VOCABULARY), 80)
        self.assertEqual(len(set(voice.VOCABULARY)), 80)
        for label in ("person", "cat", "dog", "bird", "potted plant", "mouse", "clock", "teddy bear"):
            self.assertIn(label, voice.VOCABULARY)

    @unittest.skipUnless(BOX.exists(), "decompiled vendor sources not present (gitignored)")
    def test_vocabulary_matches_the_vendor_box_labels(self):
        # Box.java references constants for "mouse" and "clock" instead of literals.
        text = BOX.read_text()
        body = re.search(r"labels\s*=\s*\{(.*?)\};", text, re.S).group(1)
        labels = []
        for item in body.split(","):
            item = item.strip()
            if item.startswith('"'):
                labels.append(item.strip('"'))
            elif "MOUSE" in item:
                labels.append("mouse")
            elif "CLOCK" in item:
                labels.append("clock")
            else:
                self.fail(f"unexpected label entry {item}")
        self.assertEqual(list(voice.VOCABULARY), labels)


class SlugAndPhraseTest(unittest.TestCase):
    def test_slug_replaces_spaces_with_dashes(self):
        self.assertEqual(voice.slug("potted plant"), "potted-plant")
        self.assertEqual(voice.slug("cup"), "cup")
        self.assertEqual(voice.slug("traffic light"), "traffic-light")

    def test_clip_name(self):
        self.assertEqual(voice.clip_name("cell phone"), "name-cell-phone.wav")

    def test_friendly_wording(self):
        self.assertEqual(voice.phrase("potted plant"), "ooh, a plant")
        self.assertEqual(voice.phrase("cell phone"), "ooh, a phone")
        self.assertEqual(voice.phrase("dining table"), "ooh, a table")
        self.assertEqual(voice.phrase("tv"), "ooh, a TV")

    def test_article_follows_the_first_sound(self):
        self.assertEqual(voice.phrase("apple"), "ooh, an apple")
        self.assertEqual(voice.phrase("orange"), "ooh, an orange")
        self.assertEqual(voice.phrase("umbrella"), "ooh, an umbrella")
        self.assertEqual(voice.phrase("cup"), "ooh, a cup")

    def test_plural_and_mass_nouns_take_no_article(self):
        self.assertEqual(voice.phrase("scissors"), "ooh, scissors")
        self.assertEqual(voice.phrase("skis"), "ooh, skis")
        self.assertEqual(voice.phrase("broccoli"), "ooh, broccoli")

    def test_every_label_has_a_phrase(self):
        for label in voice.VOCABULARY:
            self.assertTrue(voice.phrase(label).startswith("ooh, "), label)


class CommittedNameClipsTest(unittest.TestCase):
    def test_voice_renders_at_the_sound_generator_rate(self):
        self.assertEqual(voice.RATE, sounds.RATE)

    def test_every_label_has_a_usable_clip(self):
        for label in voice.VOCABULARY:
            path = ASSETS / voice.clip_name(label)
            self.assertTrue(path.exists(), f"{path} missing; run scripts/gen-explore-voice.py")
            with wave.open(str(path)) as w:
                self.assertEqual((w.getnchannels(), w.getsampwidth(), w.getframerate()),
                                 (1, 2, sounds.RATE), path.name)
                seconds = w.getnframes() / w.getframerate()
                samples = array.array("h", w.readframes(w.getnframes()))
            self.assertTrue(0.3 < seconds < 2.5, f"{path.name}: {seconds:.2f}s")
            peak = max(abs(s) for s in samples)
            self.assertGreater(peak, 8000, f"{path.name} is too quiet")
            self.assertLess(peak, 32767, f"{path.name} clips")

    def test_no_stray_name_clips(self):
        expected = {voice.clip_name(label) for label in voice.VOCABULARY}
        present = {p.name for p in ASSETS.glob("name-*.wav")}
        self.assertEqual(present, expected)


if __name__ == "__main__":
    unittest.main()
