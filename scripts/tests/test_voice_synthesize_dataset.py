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


LINES_FILE = REPO / "scripts" / "voice" / "script-lines.txt"
VOCAB_FILE = REPO / "mode-explore" / "assets" / "vocabulary.txt"


class ScriptLinesTest(unittest.TestCase):
    """The committed script: 1,400-1,600 unique, valid lines naming every vocabulary item."""

    @classmethod
    def setUpClass(cls):
        cls.lines = syn.load_script_lines(LINES_FILE)

    def test_count_in_range(self):
        self.assertGreaterEqual(len(self.lines), 1400)
        self.assertLessEqual(len(self.lines), 1600)

    def test_committed_file_has_no_duplicates(self):
        raw = [l.strip() for l in LINES_FILE.read_text().splitlines()
               if l.strip() and not l.startswith("#")]
        keys = [syn.line_key(l) for l in raw]
        self.assertEqual(len(keys), len(set(keys)))

    def test_every_line_valid(self):
        for line in self.lines:
            self.assertEqual(syn.line_problems(line), [], line)

    def test_every_vocabulary_name_appears(self):
        names = syn.vocabulary_names(VOCAB_FILE)
        self.assertGreater(len(names), 300)
        self.assertEqual(syn.missing_names(names, self.lines), [])

    def test_mix_has_questions_exclamations_and_numbers(self):
        self.assertGreater(sum(l.endswith("?") for l in self.lines), 150)
        self.assertGreater(sum(l.endswith("!") for l in self.lines), 150)
        self.assertGreater(sum(any(c.isdigit() for c in l) for l in self.lines), 60)


class LineRulesTest(unittest.TestCase):
    def test_dedupe_ignores_case_punctuation_and_spacing(self):
        got = syn.dedupe(["Hello there, friend!", "hello  there friend", "Another line here."])
        self.assertEqual(got, ["Hello there, friend!", "Another line here."])

    def test_load_skips_comments_and_blanks_and_dedupes(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "lines.txt"
            p.write_text("# note\n\nOoh, a plant!\nOoh, a plant!\nIs that a TV?\n")
            self.assertEqual(syn.load_script_lines(p), ["Ooh, a plant!", "Is that a TV?"])

    def test_bad_lines_rejected(self):
        self.assertTrue(syn.line_problems("Too short."))
        self.assertTrue(syn.line_problems("a b c | d"))
        self.assertTrue(syn.line_problems(" ".join(["word"] * 26)))
        self.assertTrue(syn.line_problems("x" * 60 + " y " + "z" * 130))
        self.assertEqual(syn.line_problems("Ooh, a plant!"), [])

    def test_load_refuses_a_bad_line(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "lines.txt"
            p.write_text("Ooh, a plant!\nbad | line here\n")
            with self.assertRaises(ValueError):
                syn.load_script_lines(p)

    def test_vocabulary_names_skip_comments(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "v.txt"
            p.write_text("# header\nperson\n\n# pets\nguinea pig\nrubik's cube\n")
            self.assertEqual(syn.vocabulary_names(p), ["person", "guinea pig", "rubik's cube"])

    def test_missing_names_uses_whole_words(self):
        lines = ["Is that a cattle farm?", "Ooh, a t-shirt!", "A guinea pig!"]
        self.assertEqual(syn.missing_names(["cat", "t-shirt", "guinea pig"], lines), ["cat"])


class NormalizeTest(unittest.TestCase):
    def test_digits_match_spelled_numbers(self):
        self.assertEqual(syn.normalize_words("1, 2, 3"), syn.normalize_words("One, two, three."))
        self.assertEqual(syn.normalize_words("1, 2, 3"), ["one", "two", "three"])

    def test_larger_numbers(self):
        self.assertEqual(syn.normalize_words("42"), ["forty", "two"])
        self.assertEqual(syn.normalize_words("1,250 steps"),
                         ["one", "thousand", "two", "hundred", "fifty", "steps"])
        self.assertEqual(syn.normalize_words("3.5"), ["three", "point", "five"])

    def test_ordinals_percent_money_and_years(self):
        self.assertEqual(syn.normalize_words("the 3rd"), ["the", "third"])
        self.assertEqual(syn.normalize_words("21st"), ["twenty", "first"])
        self.assertEqual(syn.normalize_words("50%"), ["fifty", "percent"])
        self.assertEqual(syn.normalize_words("$5"), ["five", "dollars"])
        self.assertEqual(syn.normalize_words("in 1999"), syn.normalize_words("in nineteen ninety-nine"))
        self.assertEqual(syn.normalize_words("2024"), syn.normalize_words("twenty twenty-four"))

    def test_times(self):
        self.assertEqual(syn.normalize_words("7:30"), ["seven", "thirty"])
        self.assertEqual(syn.normalize_words("7:05"), ["seven", "oh", "five"])

    def test_case_punctuation_hyphens_apostrophes(self):
        self.assertEqual(syn.normalize_words("Don\u2019t go--now! Yo-yo."),
                         ["dont", "go", "now", "yo", "yo"])


class WerTest(unittest.TestCase):
    def test_exact_after_normalizing_is_zero(self):
        self.assertEqual(syn.word_error_rate("I have 3 cats.", "I have three cats"), 0.0)

    def test_substitution_insertion_deletion(self):
        self.assertAlmostEqual(syn.word_error_rate("a b c d", "a x c d"), 0.25)
        self.assertAlmostEqual(syn.word_error_rate("a b c d", "a b c d e"), 0.25)
        self.assertAlmostEqual(syn.word_error_rate("a b c d", "a b d"), 0.25)

    def test_threshold(self):
        self.assertEqual(syn.MAX_WER, 0.15)
        # 1 wrong word in 7 (0.143) is kept; 1 in 6 (0.167) is dropped
        self.assertLessEqual(syn.word_error_rate("a b c d e f g", "a b c d e f x"), syn.MAX_WER)
        self.assertGreater(syn.word_error_rate("a b c d e f", "a b c d e x"), syn.MAX_WER)


class DurationTest(unittest.TestCase):
    def test_plausible(self):
        self.assertTrue(syn.duration_plausible("Hi there, I don't think we've met!", 2.0))

    def test_too_fast_or_too_slow(self):
        self.assertFalse(syn.duration_plausible("Hi there, I don't think we've met!", 0.4))
        self.assertFalse(syn.duration_plausible("Ooh, a plant!", 12.0))

    def test_numbers_count_as_spoken_words(self):
        self.assertTrue(syn.duration_plausible("1,234,567", 3.5))


class ScriptedBackend(FakeBackend):
    """A fake cloner that accepts a seed and can return bad audio for chosen lines."""

    def __init__(self, bad=None):
        super().__init__()
        self.bad = bad or {}
        self.seeds = []

    def generate(self, text, seed=None):
        self.calls.append(text)
        self.seeds.append(seed)
        kind = self.bad.get(text)
        n = len(syn.normalize_words(text))
        if kind == "silent":
            return np.zeros(int(0.35 * n * self.rate), np.float32), self.rate
        if kind == "clipped":
            return np.clip(tone(0.35 * n, self.rate, amp=3.0), -1, 1), self.rate
        if kind == "long":
            return tone(5.0 * n + 5, self.rate), self.rate
        return tone(0.35 * n, self.rate), self.rate


class FakeTranscriber:
    """Maps each rendered file back to its prompt, or to a scripted wrong answer."""

    def __init__(self, texts_by_call=None):
        self.answers = texts_by_call or {}
        self.files = []

    def __call__(self, path, prompt):
        self.files.append(Path(path))
        queue = self.answers.get(prompt)
        if queue:
            return queue.pop(0)
        return prompt


class RunDatasetTest(unittest.TestCase):
    LINES = ["Ooh, a plant!", "Is that a TV?", "I count 1, 2, 3 socks.",
             "Hello there, little puppy dog!"]

    def run_it(self, root, backend, transcriber, lines=None, **kw):
        return syn.build_dataset(backend, transcriber, lines or self.LINES,
                                 root / "dataset", work_root=root, **kw)

    def test_all_pass_writes_wavs_and_metadata(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d) / "voice-work"
            summary = self.run_it(root, ScriptedBackend(), FakeTranscriber())
            out = root / "dataset"
            self.assertEqual(summary["lines"], 4)
            self.assertEqual(summary["kept"], 4)
            self.assertEqual(summary["dropped"], {})
            self.assertGreater(summary["hours"], 0)
            rows = (out / "metadata.csv").read_text().splitlines()
            self.assertEqual(rows[0], "0001|Ooh, a plant!")
            for row in rows:
                wav_id, text = row.split("|")
                self.assertNotIn("|", text)
                self.assertEqual(syn.check_wav(out / "wavs" / f"{wav_id}.wav") > 0, True)
            self.assertTrue((out / "progress.log").read_text().strip())
            self.assertTrue((out / "summary.json").exists())

    def test_number_prompt_matches_spelled_transcript(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d) / "voice-work"
            tr = FakeTranscriber({"I count 1, 2, 3 socks.": ["I count one, two, three socks."]})
            summary = self.run_it(root, ScriptedBackend(), tr)
            self.assertEqual(summary["kept"], 4)

    def test_wrong_transcript_retried_with_new_seed_then_dropped(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d) / "voice-work"
            wrong = "Hello there, little muppet frog!"
            tr = FakeTranscriber({self.LINES[3]: [wrong, wrong, wrong]})
            backend = ScriptedBackend()
            summary = self.run_it(root, backend, tr)
            self.assertEqual(summary["kept"], 3)
            self.assertEqual(summary["dropped"], {"transcript": 1})
            self.assertEqual(backend.calls.count(self.LINES[3]), 3)  # first try + 2 retries
            seeds = [s for t, s in zip(backend.calls, backend.seeds) if t == self.LINES[3]]
            self.assertEqual(len(set(seeds)), 3)
            self.assertFalse((root / "dataset" / "wavs" / "0004.wav").exists())
            ids = [r.split("|")[0] for r in (root / "dataset" / "metadata.csv").read_text().splitlines()]
            self.assertEqual(ids, ["0001", "0002", "0003"])

    def test_retry_that_passes_is_kept(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d) / "voice-work"
            tr = FakeTranscriber({self.LINES[0]: ["Ooh, a planet!"]})
            summary = self.run_it(root, ScriptedBackend(), tr)
            self.assertEqual(summary["kept"], 4)
            self.assertEqual(summary["retries"], 1)

    def test_bad_audio_dropped_by_reason_without_transcribing(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d) / "voice-work"
            backend = ScriptedBackend({self.LINES[0]: "silent", self.LINES[1]: "clipped",
                                       self.LINES[2]: "long"})
            tr = FakeTranscriber()
            summary = self.run_it(root, backend, tr)
            self.assertEqual(summary["kept"], 1)
            self.assertEqual(summary["dropped"], {"silent": 1, "clipped": 1, "duration": 1})
            self.assertEqual(len(tr.files), 1)

    def test_resume_skips_finished_lines(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d) / "voice-work"
            wrong = "Hello there, little muppet frog!"
            self.run_it(root, ScriptedBackend(), FakeTranscriber({self.LINES[3]: [wrong] * 3}),
                        lines=self.LINES)
            backend = ScriptedBackend()
            more = self.LINES + ["A brand new line appears!"]
            summary = self.run_it(root, backend, FakeTranscriber(), lines=more)
            self.assertEqual(backend.calls, ["A brand new line appears!"])
            self.assertEqual(summary["kept"], 4)
            self.assertEqual(summary["dropped"], {"transcript": 1})
            self.assertEqual(summary["lines"], 5)

    def test_resume_redoes_a_kept_line_whose_wav_is_missing(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d) / "voice-work"
            self.run_it(root, ScriptedBackend(), FakeTranscriber())
            (root / "dataset" / "wavs" / "0002.wav").unlink()
            backend = ScriptedBackend()
            summary = self.run_it(root, backend, FakeTranscriber())
            self.assertEqual(backend.calls, [self.LINES[1]])
            self.assertEqual(summary["kept"], 4)

    def test_resume_redoes_a_line_whose_text_changed(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d) / "voice-work"
            self.run_it(root, ScriptedBackend(), FakeTranscriber())
            edited = list(self.LINES)
            edited[1] = "Is that a big TV?"
            backend = ScriptedBackend()
            self.run_it(root, backend, FakeTranscriber(), lines=edited)
            self.assertEqual(backend.calls, ["Is that a big TV?"])
            rows = (root / "dataset" / "metadata.csv").read_text().splitlines()
            self.assertIn("0002|Is that a big TV?", rows)

    def test_out_dir_outside_root_refused_before_generating(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d) / "voice-work"
            root.mkdir()
            backend = ScriptedBackend()
            with self.assertRaises(syn.OutsideWorkDir):
                syn.build_dataset(backend, FakeTranscriber(), self.LINES, Path(d) / "dataset",
                                  work_root=root)
            self.assertEqual(backend.calls, [])

    def test_metadata_never_lists_a_missing_file(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d) / "voice-work"
            out = root / "dataset"
            syn.write_metadata(out, [("0001", "Hi there, friend!"), ("0002", "Nope, not here.")],
                               work_root=root, wav_dir=out / "wavs")
            self.assertEqual((out / "metadata.csv").read_text(), "")
            (out / "wavs").mkdir(parents=True)
            syn.write_wav(out / "wavs" / "0001.wav", tone(1.0, 22050), 22050, root)
            syn.write_metadata(out, [("0001", "Hi there, friend!"), ("0002", "Nope, not here.")],
                               work_root=root, wav_dir=out / "wavs")
            self.assertEqual((out / "metadata.csv").read_text(), "0001|Hi there, friend!\n")


if __name__ == "__main__":
    unittest.main()
