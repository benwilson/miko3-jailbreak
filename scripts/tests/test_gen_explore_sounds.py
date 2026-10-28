"""Tests for scripts/gen-explore-sounds.py: the explore mode's startle chirps are
generated, not recorded, so these pin what makes them usable on the robot —
valid short WAVs, audible, and byte-identical across runs (explore plan U7), plus the
curiosity reactions (camera curiosity plan U6)."""
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

    def reacts(self, group=None):
        """The babble reactions; the privacy beep is a react- group too, but not babble."""
        prefix = "react-" if group is None else f"react-{group}-"
        return [p for p in self.paths if p.name.startswith(prefix) and not p.name.startswith("react-privacy-")]

    def privacy(self):
        return [p for p in self.paths if p.name.startswith("react-privacy-")]

    def test_writes_several_startle_variants(self):
        self.assertGreaterEqual(len(self.startles()), 2)
        for p in self.startles():
            self.assertEqual(p.suffix, ".wav", p.name)

    def test_writes_several_idle_songs(self):
        # WALL-E-style babble while he sits still (resting or eyes-only); four, picked at
        # random, so it isn't always the same one.
        self.assertEqual(len(self.songs()), 4)
        self.assertEqual(len(self.paths),
                         len(self.startles()) + len(self.songs()) + len(self.reacts()) + len(self.privacy()))

    def test_each_song_is_a_short_phrase(self):
        for p in self.songs():
            with wave.open(str(p)) as w:
                self.assertEqual((w.getnchannels(), w.getsampwidth(), w.getframerate()), (1, 2, gen.RATE))
                seconds = w.getnframes() / w.getframerate()
            self.assertTrue(2.0 <= seconds <= 6.0, f"{p.name}: {seconds:.2f}s")

    def test_each_song_moves_in_pitch(self):
        # Syllables glide and jump (a voice, not a held tone): the dominant frequency across
        # the clip must cover a wide range, measured by zero crossings in short windows.
        for p in self.songs():
            with wave.open(str(p)) as w:
                samples = array.array("h", w.readframes(w.getnframes()))
            win = gen.RATE // 20
            rates = []
            for start in range(0, len(samples) - win, win):
                chunk = samples[start:start + win]
                if max(abs(x) for x in chunk) < 3000:
                    continue
                crossings = sum(1 for a, b in zip(chunk, chunk[1:]) if (a < 0) != (b < 0))
                rates.append(crossings)
            self.assertGreaterEqual(max(rates) / max(1, min(rates)), 1.8, p.name)

    def test_songs_are_distinct(self):
        bodies = [p.read_bytes() for p in self.songs()]
        self.assertEqual(len(set(bodies)), len(bodies))

    def test_writes_every_reaction_group_with_variants(self):
        # Curiosity reactions (camera plan U6): each group has a few variants so repeats vary.
        for group in ("curious", "thinking", "disappointed", "delighted", "puzzled"):
            clips = self.reacts(group)
            self.assertGreaterEqual(len(clips), 2, group)
            self.assertLessEqual(len(clips), 3, group)
            for i, p in enumerate(sorted(clips), start=1):
                self.assertEqual(p.name, f"react-{group}-{i}.wav")
        self.assertEqual({group for group, _ in gen.REACTIONS}, {"curious", "thinking", "disappointed", "delighted", "puzzled", "acknowledge"})

    def test_each_reaction_is_short_mono_16bit_at_the_expected_rate(self):
        for p in self.reacts():
            with wave.open(str(p)) as w:
                self.assertEqual((w.getnchannels(), w.getsampwidth(), w.getframerate()), (1, 2, gen.RATE))
                seconds = w.getnframes() / w.getframerate()
            self.assertTrue(0.3 <= seconds < 3.0, f"{p.name}: {seconds:.2f}s")

    def test_reactions_are_distinct(self):
        bodies = [p.read_bytes() for p in self.reacts()]
        self.assertEqual(len(set(bodies)), len(bodies))

    def test_startles_are_a_soft_oops_quieter_than_everything_else(self):
        # Owner 2026-10-02: the old rising "whoa" chirp was "pretty loud and annoying" when the
        # floor sensor fires often; the startle is now a soft falling "oops" in the same voice.
        def peak(p):
            with wave.open(str(p)) as w:
                return max(abs(x) for x in array.array("h", w.readframes(w.getnframes())))
        loudest_startle = max(peak(p) for p in self.startles())
        self.assertLessEqual(loudest_startle, int(0.22 * 32767))
        for p in self.songs() + self.reacts():
            self.assertLess(loudest_startle, peak(p), p.name)

    # The bathroom privacy beep (owner 2026-10-02: "he beeps every five seconds and tries
    # to escape the bathroom"): one short, unmistakable two-tone beep, played through the
    # reaction path as the group "privacy" (ClipPlayer finds react-privacy-1.wav).

    def test_writes_one_privacy_beep_as_a_reaction_group(self):
        self.assertEqual([p.name for p in self.privacy()], ["react-privacy-1.wav"])
        self.assertEqual([name for name, _ in gen.BEEPS], ["privacy"])

    def test_privacy_beep_is_short_mono_16bit_at_the_expected_rate(self):
        with wave.open(str(self.privacy()[0])) as w:
            self.assertEqual((w.getnchannels(), w.getsampwidth(), w.getframerate()), (1, 2, gen.RATE))
            seconds = w.getnframes() / w.getframerate()
        self.assertTrue(0.2 <= seconds <= 0.3, f"{seconds:.3f}s")

    def test_privacy_beep_is_two_steady_tones_unlike_the_babble(self):
        # Two tones, each held (a beep, not a glide): the dominant frequency in the first
        # and second halves differs clearly, and each half is steady within itself.
        with wave.open(str(self.privacy()[0])) as w:
            samples = array.array("h", w.readframes(w.getnframes()))
        win = gen.RATE // 50
        rates = []
        for start in range(0, len(samples) - win, win):
            chunk = samples[start:start + win]
            if max(abs(x) for x in chunk) < 6000:
                continue
            rates.append(sum(1 for a, b in zip(chunk, chunk[1:]) if (a < 0) != (b < 0)))
        self.assertGreaterEqual(len(rates), 6, rates)
        first, second = rates[:len(rates) // 2], rates[(len(rates) + 1) // 2:]
        self.assertGreater(abs(sum(first) / len(first) - sum(second) / len(second)), 4, rates)
        for half in (first[1:-1], second[1:-1]):
            if half:
                self.assertLessEqual(max(half) - min(half), 4, rates)

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
            # The startle "oops" is deliberately soft (owner 2026-10-02); still clearly audible.
            floor = 3000 if p.name.startswith("startle-") else 8000
            self.assertGreater(peak, floor, f"{p.name} is too quiet")
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


class StartleGapTest(unittest.TestCase):
    """The startle plays at most once every 20 s (owner 2026-10-02)."""

    def test_clip_player_spaces_startles_20_s_apart(self):
        src = (Path(__file__).resolve().parents[2] / "mode-explore" / "src" / "com" / "miko3" / "mode"
               / "explore" / "ClipPlayer.java").read_text()
        self.assertRegex(src, r"STARTLE_MIN_GAP_MS\s*=\s*20000")
        body = src[src.index("void playStartle()"):]
        body = body[:body.index("handler.post")]
        self.assertIn("now - lastStartleMs < STARTLE_MIN_GAP_MS", body)
        self.assertIn("return;", body)


if __name__ == "__main__":
    unittest.main()
