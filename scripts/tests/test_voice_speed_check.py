#!/usr/bin/env python3
"""Tests for scripts/voice/speed-check.py (robot voice plan U1).

Covers what can be checked off the robot: the CLI's timing lines are parsed
into synthesis time and real-time factor, the chunk limit and the pick of a
tier follow from the times, the STOP message and non-zero exit when no tier
gets the AE1 sentence under 2 s, and the robot's scratch directory being
removed after a success, a failure, and Ctrl-C. The timings themselves are a
runtime measurement on the robot, not this file.
"""
import importlib.util
import io
import types
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest import mock

REPO = Path(__file__).resolve().parents[2]
SPEED_PY = REPO / "scripts" / "voice" / "speed-check.py"


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load {path}")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


sc = load("voice_speed_check", SPEED_PY)

SERIAL = "192.168.19.74:5555"

CLI_OUTPUT = """\
/k2-fsa/sherpa-onnx/sherpa-onnx/csrc/parse-options.cc:Read:374 ./sherpa-onnx-offline-tts ...

Number of threads: 2
Elapsed seconds: 1.251 s
Audio duration: 2.179 s
Real-time factor (RTF): 1.251/2.179 = 0.574
The text is: Hi there, I don't think we've met!. Speaker ID: 0
Saved to /data/local/tmp/voicecheck/o.wav successfully!
"""

DF_OUTPUT = """\
Filesystem            1K-blocks     Used Available Use% Mounted on
/dev/block/mmcblk0p31  20000000 11000000   9000000  56% /data
"""


def done(stdout="", returncode=0, stderr=""):
    return types.SimpleNamespace(stdout=stdout, stderr=stderr, returncode=returncode)


def timing(elapsed, audio):
    return done(f"Elapsed seconds: {elapsed:.3f} s\nAudio duration: {audio:.3f} s\n"
                f"Real-time factor (RTF): {elapsed:.3f}/{audio:.3f} = {elapsed / audio:.3f}\n")


class FakeAdb:
    """Records every command. Synthesis runs answer from synth(cmd); others from a prefix table."""

    def __init__(self, synth=None, answers=None):
        self.calls = []
        self.synth = synth or (lambda cmd: timing(0.8, 2.0))
        self.answers = {"get-state": done("device\n"), "df -k": done(DF_OUTPUT)}
        self.answers.update(answers or {})

    def __call__(self, cmd, **kw):
        self.calls.append(cmd)
        joined = " ".join(cmd)
        if "sherpa-onnx-offline-tts" in joined:
            return self.synth(cmd)
        for prefix, result in self.answers.items():
            if prefix in joined:
                return result
        return done()

    def joined(self):
        return [" ".join(c) for c in self.calls]


def fake_voices():
    return [sc.Voice(tier="low", name="en_US-lessac-low", checkpoint="en/en_US/lessac/low/x.ckpt",
                     sample_rate=16000, local_dir=Path("/nonexistent/low"), onnx="en_US-lessac-low.onnx")]


class ParseTest(unittest.TestCase):
    def test_timing_lines_become_synthesis_time_and_rtf(self):
        t = sc.parse_timing(CLI_OUTPUT)
        self.assertAlmostEqual(t.elapsed, 1.251)
        self.assertAlmostEqual(t.audio, 2.179)
        self.assertAlmostEqual(t.rtf, 0.574)

    def test_time_to_first_audio_is_the_whole_synthesis_time(self):
        # The CLI writes nothing until the sentence is synthesized.
        self.assertAlmostEqual(sc.parse_timing(CLI_OUTPUT).first_audio, 1.251)

    def test_output_without_timing_is_a_clear_error(self):
        with self.assertRaises(sc.SpeedCheckError) as cm:
            sc.parse_timing("CANNOT LINK EXECUTABLE ...")
        self.assertIn("CANNOT LINK", str(cm.exception))

    def test_free_space_is_read_from_df(self):
        self.assertEqual(sc.parse_df_available_kb(DF_OUTPUT), 9000000)


class ChunkLimitTest(unittest.TestCase):
    def test_linear_fit_through_two_sentence_lengths(self):
        # 0.5 s fixed + 0.1 s per word: 2 s buys 15 words.
        self.assertEqual(sc.chunk_limit([(7, 1.2), (20, 2.5)], budget=2.0), 15)

    def test_nothing_fits_when_even_the_fixed_cost_is_over_budget(self):
        self.assertEqual(sc.chunk_limit([(7, 2.9), (20, 4.2)], budget=2.0), 0)


class RecommendTest(unittest.TestCase):
    def row(self, tier, ae1, limit):
        return sc.Result(voice=types.SimpleNamespace(tier=tier), threads=4, ae1=ae1, long=None,
                         rtf=0.5, limit=limit)

    def test_picks_highest_quality_tier_covering_a_clause(self):
        best = sc.recommend([self.row("x_low", 0.5, 30), self.row("low", 1.0, 12), self.row("medium", 1.8, 6)])
        self.assertEqual(best.voice.tier, "low")

    def test_no_tier_under_two_seconds_means_stop(self):
        self.assertIsNone(sc.recommend([self.row("low", 2.1, 0), self.row("medium", 3.0, 0)]))


class MainTest(unittest.TestCase):
    def run_main(self, fake, argv=("--no-load", "--runs", "1")):
        out = io.StringIO()
        with mock.patch.object(sc.subprocess, "run", fake), \
                mock.patch.object(sc, "prepare_assets", return_value=(Path("/nonexistent"), fake_voices())), \
                mock.patch.object(sc, "checkpoint_exists", return_value=True), \
                redirect_stdout(out):
            try:
                code = sc.main(list(argv))
            except SystemExit as e:
                code = e.code if isinstance(e.code, int) else 1
        return code, out.getvalue()

    def removed(self, fake):
        return any("rm -rf " + sc.REMOTE_DIR in c for c in fake.joined())

    def test_success_prints_table_and_recommendation_then_cleans_up(self):
        fake = FakeAdb()
        code, out = self.run_main(fake)
        self.assertEqual(code, 0)
        self.assertIn("en/en_US/lessac/low/x.ckpt", out)
        self.assertIn("Recommendation", out)
        self.assertIn("time to first audio", out.lower())
        self.assertTrue(self.removed(fake))
        self.assertIn("rm -rf " + sc.REMOTE_DIR, fake.joined()[-1])

    def test_stop_when_no_tier_gets_ae1_under_two_seconds(self):
        fake = FakeAdb(synth=lambda cmd: timing(2.6, 2.2))
        code, out = self.run_main(fake)
        self.assertNotEqual(code, 0)
        self.assertIn("STOP", out)
        self.assertTrue(self.removed(fake))

    def test_failure_still_cleans_up(self):
        fake = FakeAdb(synth=lambda cmd: done("CANNOT LINK EXECUTABLE", returncode=1))
        code, _ = self.run_main(fake)
        self.assertNotEqual(code, 0)
        self.assertTrue(self.removed(fake))

    def test_ctrl_c_still_cleans_up(self):
        def interrupt(cmd):
            raise KeyboardInterrupt
        fake = FakeAdb(synth=interrupt)
        code, _ = self.run_main(fake)
        self.assertEqual(code, 130)
        self.assertTrue(self.removed(fake))

    def test_too_little_space_stops_before_pushing(self):
        tight = DF_OUTPUT.replace("9000000", "100000")
        fake = FakeAdb(answers={"df -k": done(tight)})
        code, _ = self.run_main(fake)
        self.assertNotEqual(code, 0)
        self.assertFalse(any(" push " in c for c in fake.joined()))

    def test_explore_is_force_stopped_before_its_freeze_hook_is_cleared(self):
        # Unfreezing a running Explore lets its brain act on the scan and turn.
        fake = FakeAdb()
        with mock.patch.object(sc.subprocess, "run", fake), redirect_stdout(io.StringIO()):
            sc.stop_explore(SERIAL, {sc.HOOK_FREEZE: "", sc.HOOK_STALE: ""})
        joined = fake.joined()
        self.assertIn("force-stop " + sc.EXPLORE_PKG, joined[0])
        self.assertTrue(all("setprop" in c for c in joined[1:]))

    def test_every_adb_call_names_the_serial(self):
        fake = FakeAdb()
        self.run_main(fake)
        for cmd in fake.calls:
            if cmd[:2] == ["adb", "connect"]:
                continue
            self.assertEqual(cmd[:3], ["adb", "-s", SERIAL], cmd)


if __name__ == "__main__":
    unittest.main()
