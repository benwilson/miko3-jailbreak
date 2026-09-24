#!/usr/bin/env python3
"""Tests for scripts/voice/synthesize-dataset.py (robot voice plan U3, R4, R6, R12).

Covers the pure logic of the --samples mode: sample-line slugs and file
names, the guard that keeps every output inside the gitignored voice-work/
directory, resampling to 22050 Hz, the audio checks (silence, clipping,
non-finite values), the written WAV format, and the speed figures (real-time
factor and the projected dataset time). The cloning model is replaced by a
fake backend; the owner's real clips are never read here.
"""
import importlib.util
import math
import tempfile
import unittest
from pathlib import Path

try:
    import numpy as np
    import soundfile as sf
except ImportError:  # the host python has neither; run these in voice-work/.venv-clone
    raise unittest.SkipTest("numpy/soundfile not installed; run with voice-work/.venv-clone/bin/python")

REPO = Path(__file__).resolve().parents[2]
SCRIPT = REPO / "scripts" / "voice" / "synthesize-dataset.py"


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load {path}")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


syn = load("voice_synthesize_dataset", SCRIPT)


def tone(seconds, rate, amp=0.3, freq=220.0):
    t = np.arange(int(seconds * rate)) / rate
    return (amp * np.sin(2 * math.pi * freq * t)).astype(np.float32)


class FakeBackend:
    """Stands in for Chatterbox: returns a tone whose length tracks the text."""
    name = "fake"

    def __init__(self, rate=24000, amp=0.3):
        self.rate = rate
        self.amp = amp
        self.calls = []

    def generate(self, text):
        self.calls.append(text)
        return tone(0.05 * len(text.split()) + 0.2, self.rate, self.amp), self.rate


class SlugTest(unittest.TestCase):
    def test_apostrophes_and_punctuation_become_hyphens(self):
        self.assertEqual(syn.slugify("Hi there, I don't think we've met!"),
                         "hi-there-i-don-t-think-we-ve-met")

    def test_ellipsis_and_question_mark(self):
        self.assertEqual(syn.slugify("Wait... did you just move that chair?"),
                         "wait-did-you-just-move-that-chair")

    def test_long_line_cut_at_a_word_boundary(self):
        slug = syn.slugify(" ".join(["wonderful"] * 20), max_len=40)
        self.assertLessEqual(len(slug), 40)
        self.assertFalse(slug.endswith("-"))
        self.assertTrue(all(part == "wonderful" for part in slug.split("-")))

    def test_empty_text_still_gets_a_name(self):
        self.assertEqual(syn.slugify("?!..."), "line")

    def test_sample_file_name_is_numbered_from_one(self):
        self.assertEqual(syn.sample_filename(0, "One, two, three, four, five."),
                         "01-one-two-three-four-five.wav")
        self.assertEqual(syn.sample_filename(9, "Hi!"), "10-hi.wav")

    def test_sample_lines_are_about_ten_unique_with_one_long_sentence(self):
        lines = syn.SAMPLE_LINES
        self.assertTrue(8 <= len(lines) <= 12)
        self.assertEqual(len(set(lines)), len(lines))
        names = [syn.sample_filename(i, t) for i, t in enumerate(lines)]
        self.assertEqual(len(set(names)), len(names))
        self.assertTrue(any(len(t.split()) == 25 for t in lines))
        self.assertIn("One, two, three, four, five.", lines)


class GuardTest(unittest.TestCase):
    def test_inside_work_root_allowed(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d) / "voice-work"
            root.mkdir()
            p = syn.guard_output(root / "samples" / "01-hi.wav", root)
            self.assertEqual(p, (root / "samples" / "01-hi.wav").resolve())

    def test_outside_work_root_refused(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d) / "voice-work"
            root.mkdir()
            with self.assertRaises(syn.OutsideWorkDir):
                syn.guard_output(Path(d) / "elsewhere.wav", root)
            with self.assertRaises(syn.OutsideWorkDir):
                syn.guard_output(root / ".." / "escape.wav", root)

    def test_sibling_with_shared_prefix_refused(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d) / "voice-work"
            root.mkdir()
            with self.assertRaises(syn.OutsideWorkDir):
                syn.guard_output(Path(d) / "voice-work-evil" / "x.wav", root)

    def test_symlink_out_of_work_root_refused(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d) / "voice-work"
            root.mkdir()
            outside = Path(d) / "outside"
            outside.mkdir()
            (root / "samples").symlink_to(outside)
            with self.assertRaises(syn.OutsideWorkDir):
                syn.guard_output(root / "samples" / "01-hi.wav", root)

    def test_default_root_is_repo_voice_work(self):
        self.assertEqual(syn.WORK_ROOT, REPO / "voice-work")
        with self.assertRaises(syn.OutsideWorkDir):
            syn.guard_output(REPO / "scripts" / "voice" / "x.wav")


class ResampleTest(unittest.TestCase):
    def test_24k_to_22050_keeps_duration(self):
        out = syn.resample(tone(2.0, 24000), 24000, syn.OUT_RATE)
        self.assertEqual(len(out), 2 * syn.OUT_RATE)
        self.assertEqual(out.dtype, np.float32)

    def test_same_rate_is_unchanged(self):
        x = tone(0.5, 22050)
        np.testing.assert_array_equal(syn.resample(x, 22050, 22050), x)

    def test_stereo_or_batched_input_flattened_to_mono(self):
        x = tone(0.5, 24000)[None, :]  # Chatterbox returns shape (1, n)
        self.assertEqual(syn.resample(x, 24000, 22050).ndim, 1)


class AudioCheckTest(unittest.TestCase):
    def test_clean_tone_has_no_problems(self):
        self.assertEqual(syn.audio_problems(tone(1.0, 22050), 22050), [])

    def test_silence_flagged(self):
        problems = syn.audio_problems(np.zeros(22050, dtype=np.float32), 22050)
        self.assertTrue(any("silent" in p for p in problems))

    def test_clipping_flagged(self):
        x = np.clip(tone(1.0, 22050, amp=3.0), -1.0, 1.0)
        self.assertTrue(any("clipp" in p for p in syn.audio_problems(x, 22050)))

    def test_too_short_flagged(self):
        self.assertTrue(any("short" in p
                            for p in syn.audio_problems(tone(0.1, 22050), 22050)))

    def test_nan_flagged(self):
        x = tone(1.0, 22050)
        x[10] = np.nan
        self.assertTrue(any("finite" in p for p in syn.audio_problems(x, 22050)))

    def test_long_internal_silence_flagged(self):
        x = np.concatenate([tone(0.5, 22050), np.zeros(3 * 22050, np.float32),
                            tone(0.5, 22050)])
        self.assertTrue(any("gap" in p for p in syn.audio_problems(x, 22050)))


class PeakCeilingTest(unittest.TestCase):
    def test_hot_audio_scaled_under_minus_one_dbfs(self):
        out = syn.limit_peak(tone(1.0, 22050, amp=1.05))
        self.assertLessEqual(float(np.max(np.abs(out))), 10 ** (-1 / 20) + 1e-6)

    def test_quiet_audio_left_alone(self):
        x = tone(1.0, 22050, amp=0.3)
        np.testing.assert_array_equal(syn.limit_peak(x), x)

    def test_render_line_output_never_clips(self):
        audio, _ = syn.render_line(FakeBackend(amp=1.2), "Loud line.")
        self.assertLessEqual(float(np.max(np.abs(audio))), 10 ** (-1 / 20) + 1e-6)


class WavFormatTest(unittest.TestCase):
    def test_write_then_check_22050_mono_pcm16(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            p = root / "samples" / "01-hi.wav"
            syn.write_wav(p, tone(1.0, 22050), 22050, root)
            info = sf.info(str(p))
            self.assertEqual((info.samplerate, info.channels, info.subtype),
                             (22050, 1, "PCM_16"))
            self.assertAlmostEqual(syn.check_wav(p), 1.0, places=2)

    def test_write_refuses_wrong_rate(self):
        with tempfile.TemporaryDirectory() as d:
            with self.assertRaises(ValueError):
                syn.write_wav(Path(d) / "x.wav", tone(1.0, 24000), 24000, Path(d))

    def test_write_refuses_outside_root(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d) / "voice-work"
            root.mkdir()
            with self.assertRaises(syn.OutsideWorkDir):
                syn.write_wav(Path(d) / "x.wav", tone(1.0, 22050), 22050, root)

    def test_check_rejects_wrong_rate_and_stereo(self):
        with tempfile.TemporaryDirectory() as d:
            a = Path(d) / "a.wav"
            sf.write(str(a), tone(1.0, 24000), 24000, subtype="PCM_16")
            with self.assertRaises(syn.BadAudio):
                syn.check_wav(a)
            b = Path(d) / "b.wav"
            sf.write(str(b), np.stack([tone(1.0, 22050)] * 2, axis=1), 22050,
                     subtype="PCM_16")
            with self.assertRaises(syn.BadAudio):
                syn.check_wav(b)


class WordMatchTest(unittest.TestCase):
    def test_case_punctuation_and_curly_apostrophe_ignored(self):
        self.assertEqual(syn.word_match("Hi there, I don't know!", "hi there i don\u2019t know"), 1.0)

    def test_dropped_word_lowers_the_score(self):
        self.assertLess(syn.word_match("one two three four", "one two four"), 1.0)


class SpeedTest(unittest.TestCase):
    def test_real_time_factor(self):
        self.assertAlmostEqual(syn.real_time_factor(30.0, 10.0), 3.0)

    def test_real_time_factor_needs_audio(self):
        with self.assertRaises(ValueError):
            syn.real_time_factor(1.0, 0.0)

    def test_projected_hours(self):
        # 1.5 h of audio at RTF 2 takes 3 h, plus the rejected-clip overhead.
        self.assertAlmostEqual(syn.projected_hours(2.0, 1.5), 3.0)
        self.assertAlmostEqual(syn.projected_hours(2.0, 1.5, keep_rate=0.75), 4.0)


class RenderSamplesTest(unittest.TestCase):
    def test_renders_every_line_to_numbered_22050_wavs(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d) / "voice-work"
            out = root / "samples"
            backend = FakeBackend()
            lines = ["Hi there!", "One, two, three."]
            report = syn.render_samples(backend, lines, out, root)
            self.assertEqual(backend.calls, lines)
            names = sorted(p.name for p in out.glob("*.wav"))
            self.assertEqual(names, ["01-hi-there.wav", "02-one-two-three.wav"])
            for p in out.glob("*.wav"):
                self.assertEqual(sf.info(str(p)).samplerate, 22050)
            self.assertEqual(len(report["samples"]), 2)
            self.assertGreater(report["audio_seconds"], 0)
            self.assertGreater(report["rtf"], 0)
            self.assertTrue((out / "report.json").exists())

    def test_problems_are_reported_not_fatal(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d) / "voice-work"
            report = syn.render_samples(FakeBackend(amp=0.0), ["Hello there."],
                                        root / "samples", root)
            self.assertTrue(report["samples"][0]["problems"])

    def test_out_dir_outside_root_refused_before_generating(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d) / "voice-work"
            root.mkdir()
            backend = FakeBackend()
            with self.assertRaises(syn.OutsideWorkDir):
                syn.render_samples(backend, ["Hi."], Path(d) / "samples", root)
            self.assertEqual(backend.calls, [])


class DatasetStubTest(unittest.TestCase):
    def test_dataset_mode_is_not_built_yet(self):
        with self.assertRaises(SystemExit) as cm:
            syn.main(["--dataset"])
        self.assertNotEqual(cm.exception.code, 0)


if __name__ == "__main__":
    unittest.main()
