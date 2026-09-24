#!/usr/bin/env python3
"""Tests for scripts/voice/prepare-clips.py (robot voice plan U2, R3, R12).

Covers the pure logic: file name to hint text, the transcript-versus-hint
check, the guard that keeps every output inside the gitignored voice-work/
directory, SNR and silence trimming from frame levels, the set-aside rules,
and the pick of 10-15 s of reference audio. The heavy tools (ffmpeg,
DeepFilterNet, mlx-whisper) are mocked; the end-to-end test runs main() on a
temporary work directory. The owner's real clips are never read here.
"""
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock

REPO = Path(__file__).resolve().parents[2]
SCRIPT = REPO / "scripts" / "voice" / "prepare-clips.py"


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load {path}")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


prep = load("voice_prepare_clips", SCRIPT)


class HintTest(unittest.TestCase):
    def test_contraction_and_capital_i(self):
        hint, placeholder = prep.hint_from_filename(
            "i-ll-scan-my-database-to-find-out-how-to-do-it.mp3")
        self.assertEqual(hint, "I'll scan my database to find out how to do it")
        self.assertFalse(placeholder)

    def test_other_contractions(self):
        self.assertEqual(prep.hint_from_filename("i-m-your-friend.mp3")[0], "I'm your friend")
        self.assertEqual(prep.hint_from_filename("don-t-go.mp3")[0], "Don't go")
        self.assertEqual(prep.hint_from_filename("we-re-here.mp3")[0], "We're here")

    def test_first_word_capitalised_and_numbers_kept(self):
        self.assertEqual(prep.hint_from_filename("hi-absalom.mp3")[0], "Hi absalom")
        self.assertEqual(prep.hint_from_filename("at-232-degrees.mp3")[0], "At 232 degrees")

    def test_placeholder_left_as_is_and_flagged(self):
        hint, placeholder = prep.hint_from_filename("hi-insert-registered-name.mp3")
        self.assertEqual(hint, "Hi insert-registered-name")
        self.assertTrue(placeholder)

    def test_placeholder_possessive(self):
        hint, placeholder = prep.hint_from_filename(
            "i-am-insert-registered-name-s-best-friend.mp3")
        self.assertEqual(hint, "I am insert-registered-name's best friend")
        self.assertTrue(placeholder)


class AgreementTest(unittest.TestCase):
    def test_same_words_different_punctuation_agree(self):
        self.assertIsNone(prep.disagreement(
            "I'll scan my database to find out how to do it",
            " I'll scan my database to find out how to do it."))

    def test_different_words_disagree(self):
        self.assertIsNotNone(prep.disagreement(
            "Am I now your best friend out of the box",
            "The weather is lovely today in the park"))

    def test_empty_transcript_disagrees(self):
        self.assertIsNotNone(prep.disagreement("Hi absalom", ""))

    def test_placeholder_always_flagged(self):
        reason = prep.flag_reason("Hi insert-registered-name", True, "Hi there.")
        self.assertIn("placeholder", reason)

    def test_retried_transcript_stays_flagged(self):
        reason = prep.flag_reason("I know it", False, "About you. I know it.", retried=True)
        self.assertIn("unprompted", reason)

    def test_agreeing_clip_not_flagged(self):
        self.assertIsNone(prep.flag_reason("Hi absalom", False, "Hi, Absalom."))


class TranscribeFallbackTest(unittest.TestCase):
    def test_prompted_transcript_kept_when_it_agrees(self):
        with mock.patch.object(prep, "_whisper", return_value="Hi Absalom.") as w:
            self.assertEqual(prep.transcribe(Path("a.wav"), "Hi absalom"), ("Hi Absalom.", False))
        self.assertEqual(w.call_count, 1)

    def test_unprompted_retry_when_prompted_disagrees(self):
        # The prompt once made Whisper skip most of a clip with extra speech.
        with mock.patch.object(prep, "_whisper",
                               side_effect=["17 degrees.", "About you. I know it, 17."]) as w:
            got = prep.transcribe(Path("a.wav"), "I know it")
        self.assertEqual(got, ("About you. I know it, 17.", True))
        self.assertIsNone(w.call_args_list[1].args[1])

    def test_prompt_drops_placeholder(self):
        self.assertEqual(prep.whisper_prompt("Hi insert-registered-name"), "Hi")


class PathGuardTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.work = Path(self.tmp.name) / "voice-work"
        self.work.mkdir()

    def tearDown(self):
        self.tmp.cleanup()

    def test_inside_work_is_allowed(self):
        p = self.work / "clips" / "a.wav"
        self.assertEqual(prep.guard_output(p, self.work), p.resolve())

    def test_outside_work_is_refused(self):
        for bad in (Path(self.tmp.name) / "a.wav",
                    self.work / ".." / "a.wav",
                    REPO / "scripts" / "a.wav",
                    Path("/tmp/a.wav")):
            with self.assertRaises(prep.OutsideWorkDir, msg=str(bad)):
                prep.guard_output(bad, self.work)

    def test_symlink_escape_is_refused(self):
        outside = Path(self.tmp.name) / "outside"
        outside.mkdir()
        (self.work / "link").symlink_to(outside)
        with self.assertRaises(prep.OutsideWorkDir):
            prep.guard_output(self.work / "link" / "a.wav", self.work)

    def test_default_work_dir_is_repo_voice_work(self):
        self.assertEqual(prep.WORK_ROOT, REPO / "voice-work")

    def test_voice_work_is_gitignored(self):
        lines = (REPO / ".gitignore").read_text().splitlines()
        self.assertIn("voice-work/", [l.strip() for l in lines])


class LevelsTest(unittest.TestCase):
    def test_snr_is_loud_frames_over_quiet_frames(self):
        dbs = [-60.0] * 50 + [-20.0] * 50
        self.assertAlmostEqual(prep.snr_from_frame_db(dbs), 40.0, places=1)

    def test_active_span_trims_silence_with_padding(self):
        dbs = [-80.0] * 10 + [-20.0] * 5 + [-80.0] * 10
        start, end = prep.active_span(dbs, threshold_db=-40.0, pad_frames=2)
        self.assertEqual((start, end), (8, 17))

    def test_active_span_of_silence_is_empty(self):
        self.assertIsNone(prep.active_span([-90.0] * 10, threshold_db=-40.0, pad_frames=2))


class SetAsideTest(unittest.TestCase):
    def test_good_clip_is_kept(self):
        self.assertIsNone(prep.set_aside_reason(3.0, 25.0))

    def test_too_short(self):
        reason = prep.set_aside_reason(prep.MIN_SECONDS - 0.1, 25.0)
        self.assertIn("too short", reason)

    def test_too_noisy(self):
        reason = prep.set_aside_reason(3.0, prep.MIN_SNR_DB - 1)
        self.assertIn("too noisy", reason)

    def test_both_reasons_listed(self):
        reason = prep.set_aside_reason(0.2, 1.0)
        self.assertIn("too short", reason)
        self.assertIn("too noisy", reason)


class ReferencePickTest(unittest.TestCase):
    def clip(self, name, dur, snr, set_aside=None, flagged=False):
        return {"file": name, "duration": dur, "snr": snr,
                "set_aside": set_aside, "flagged": flagged}

    def test_picks_best_snr_until_ten_to_fifteen_seconds(self):
        clips = [self.clip("a", 4.0, 30), self.clip("b", 4.0, 20),
                 self.clip("c", 4.0, 25), self.clip("d", 4.0, 10)]
        picked = prep.pick_reference(clips)
        self.assertEqual([c["file"] for c in picked], ["a", "c", "b"])
        self.assertTrue(10 <= sum(c["duration"] for c in picked) <= 15)

    def test_skips_set_aside_and_clips_that_overshoot(self):
        clips = [self.clip("x", 5.0, 40, set_aside="too noisy"),
                 self.clip("a", 6.0, 30), self.clip("big", 10.0, 28),
                 self.clip("b", 5.0, 20)]
        picked = [c["file"] for c in prep.pick_reference(clips)]
        self.assertEqual(picked, ["a", "b"])

    def test_length_breaks_snr_ties(self):
        clips = [self.clip("short", 2.0, 30), self.clip("long", 5.0, 30)]
        self.assertEqual(prep.pick_reference(clips)[0]["file"], "long")

    def test_takes_what_there_is_when_under_ten_seconds(self):
        clips = [self.clip("a", 3.0, 30), self.clip("b", 2.0, 20)]
        self.assertEqual(len(prep.pick_reference(clips)), 2)


class MainTest(unittest.TestCase):
    """main() with ffmpeg, DeepFilterNet and Whisper replaced by fakes."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        base = Path(self.tmp.name)
        self.src = base / "src"
        self.src.mkdir()
        for n in ("hi-absalom.mp3", "i-ll-scan-it.mp3", "hi-insert-registered-name.mp3"):
            (self.src / n).write_bytes(b"not audio")
        self.work = base / "voice-work"

    def tearDown(self):
        self.tmp.cleanup()

    def fake_process(self, src, out_wav):
        out_wav.write_bytes(b"RIFF")
        return {"hi-absalom": (0.5, 30.0),
                "i-ll-scan-it": (6.0, 25.0),
                "hi-insert-registered-name": (5.0, 20.0)}[src.stem]

    def run_main(self, *extra):
        transcripts = {"hi-absalom": "Hi Absalom.", "i-ll-scan-it": "I'll scan it.",
                       "hi-insert-registered-name": "Hi Sam."}
        with mock.patch.object(prep, "WORK_ROOT", self.work), \
             mock.patch.object(prep, "process_clip", side_effect=self.fake_process), \
             mock.patch.object(prep, "transcribe",
                               side_effect=lambda p, hint: (transcripts[p.stem], False)), \
             mock.patch.object(prep, "write_reference",
                               side_effect=lambda picked, d: [d / "reference.wav"]), \
             mock.patch("builtins.print"):
            return prep.main(["--src", str(self.src), *extra])

    def test_writes_manifest_under_work_dir(self):
        self.assertEqual(self.run_main(), 0)
        manifest = json.loads((self.work / "clips" / "manifest.json").read_text())
        by = {c["source"]: c for c in manifest["clips"]}
        self.assertEqual(set(by), {"hi-absalom.mp3", "i-ll-scan-it.mp3",
                                   "hi-insert-registered-name.mp3"})
        for c in manifest["clips"]:
            for key in ("file", "duration", "snr", "hint", "transcript",
                        "flagged", "set_aside"):
                self.assertIn(key, c)
        self.assertIn("too short", by["hi-absalom.mp3"]["set_aside"])
        self.assertFalse(by["i-ll-scan-it.mp3"]["flagged"])
        self.assertTrue(by["hi-insert-registered-name.mp3"]["flagged"])
        self.assertEqual(manifest["reference"]["clips"],
                         ["i-ll-scan-it.wav", "hi-insert-registered-name.wav"])
        self.assertAlmostEqual(manifest["reference"]["seconds"], 11.0)

    def test_refuses_out_dir_outside_work(self):
        with self.assertRaises(SystemExit):
            self.run_main("--out", str(Path(self.tmp.name) / "elsewhere"))
        self.assertFalse((Path(self.tmp.name) / "elsewhere").exists())


if __name__ == "__main__":
    unittest.main()
