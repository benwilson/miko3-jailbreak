"""Tests for scripts/gen-explore-sounds.py: the explore mode's startle chirps are
generated, not recorded, so these pin what makes them usable on the robot —
valid short WAVs, audible, and byte-identical across runs (explore plan U7)."""
import array
import importlib.util
import tempfile
import unittest
import wave
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCRIPT = HERE.parent / "gen-explore-sounds.py"
ASSETS = HERE.parents[1] / "mode-explore" / "assets"


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load {path}")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


gen = load("gen_explore_sounds", SCRIPT)


class GeneratedClipsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls._td = tempfile.TemporaryDirectory(prefix="explore_sounds_")
        cls.out = Path(cls._td.name)
        cls.paths = gen.generate(cls.out)

    @classmethod
    def tearDownClass(cls):
        cls._td.cleanup()

    def startles(self):
        return [p for p in self.paths if p.name.startswith("startle-")]

    def songs(self):
        return [p for p in self.paths if p.name.startswith("song-")]

    def test_writes_several_startle_variants(self):
        self.assertGreaterEqual(len(self.startles()), 2)
        for p in self.startles():
            self.assertEqual(p.suffix, ".wav", p.name)

    def test_writes_several_idle_songs(self):
        # Hummed while he sits still (resting or eyes-only); varied so it doesn't loop one tune.
        self.assertGreaterEqual(len(self.songs()), 3)
        self.assertEqual(len(self.paths), len(self.startles()) + len(self.songs()))

    def test_each_song_is_a_short_phrase(self):
        for p in self.songs():
            with wave.open(str(p)) as w:
                self.assertEqual((w.getnchannels(), w.getsampwidth(), w.getframerate()), (1, 2, gen.RATE))
                seconds = w.getnframes() / w.getframerate()
            self.assertTrue(2.0 <= seconds <= 5.0, f"{p.name}: {seconds:.2f}s")

    def test_each_startle_is_short_mono_16bit_at_the_expected_rate(self):
        for p in self.startles():
            with wave.open(str(p)) as w:
                self.assertEqual(w.getnchannels(), 1)
                self.assertEqual(w.getsampwidth(), 2)
                self.assertEqual(w.getframerate(), gen.RATE)
                self.assertLess(w.getnframes() / w.getframerate(), 0.5, p.name)
                self.assertGreater(w.getnframes(), 0)

    def test_each_clip_is_audible_and_does_not_clip(self):
        for p in self.paths:
            with wave.open(str(p)) as w:
                samples = array.array("h", w.readframes(w.getnframes()))
            peak = max(abs(s) for s in samples)
            self.assertGreater(peak, 8000, f"{p.name} is too quiet")
            self.assertLess(peak, 32767, f"{p.name} clips")

    def test_clip_starts_and_ends_near_silence(self):
        # A hard edge is an audible click on the robot's speaker.
        for p in self.paths:
            with wave.open(str(p)) as w:
                samples = array.array("h", w.readframes(w.getnframes()))
            self.assertLess(abs(samples[0]), 500, p.name)
            self.assertLess(abs(samples[-1]), 500, p.name)

    def test_output_is_deterministic(self):
        with tempfile.TemporaryDirectory() as again:
            second = gen.generate(Path(again))
            for a, b in zip(self.paths, second):
                self.assertEqual(a.read_bytes(), b.read_bytes(), a.name)

    def test_committed_assets_match_the_generator(self):
        # The APK ships mode-explore/assets/; regenerate them if this fails.
        for p in self.paths:
            committed = ASSETS / p.name
            self.assertTrue(committed.exists(), f"{committed} missing; run scripts/gen-explore-sounds.py")
            self.assertEqual(committed.read_bytes(), p.read_bytes(), p.name)


if __name__ == "__main__":
    unittest.main()
