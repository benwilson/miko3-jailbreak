#!/usr/bin/env python3
"""Tests for the voice mode's wake trim and pre-ready buffer (U11, KTD6).

The robot must never hand the model the wake word that opened the
conversation: the audio it forwards starts after the detection callback, minus
a configurable trim (wake_trim_ms) that swallows the tail of "Hey Miko" and
the detector's own latency, and the question spoken in the same breath must
still arrive whole.

VoiceEngine is Android-only and the repo has no Android test harness, so that
decision lives in UplinkGate, plain Java with no android.* imports. This
compiles it for the host JVM and runs fixtures/voice_uplink_harness, which
prints one PASS/FAIL line per scenario.
"""
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
VOICE_SRC = REPO / "mode-voice" / "src"
SHARED_SRC = REPO / "shared" / "src"
HARNESS = TESTS / "fixtures" / "voice_uplink_harness" / "src"
HARNESS_MAIN = HARNESS / "com" / "miko3" / "mode" / "voice" / "UplinkGateHarness.java"
GATE = VOICE_SRC / "com" / "miko3" / "mode" / "voice" / "UplinkGate.java"


class GateIsPlainJavaTest(unittest.TestCase):
    """It only runs on the host JVM while it stays clear of the Android SDK."""

    def test_no_android_imports(self):
        offenders = [line for line in GATE.read_text().splitlines()
                     if line.startswith("import ") and ".android." in line or line.startswith("import android")]
        self.assertEqual(offenders, [])


class UplinkGateHarnessTest(unittest.TestCase):
    """One harness run, one assertion per scenario."""

    SCENARIOS = (
        "nothing_before_the_wake_is_forwarded",
        "trim_drops_exactly_the_configured_milliseconds",
        "trim_can_end_inside_a_chunk",
        "speech_after_the_trim_is_forwarded_in_order",
        "zero_trim_forwards_everything_from_the_wake",
        "pre_ready_bound_drops_oldest",
        "trim_applies_before_the_bound",
        "live_audio_goes_straight_out_after_ready",
        "drop_stops_and_clears_the_buffer",
        "summary_reports_pre_roll_and_forwarded",
    )

    @classmethod
    def setUpClass(cls):
        jdk = jvm_harness.find_jdk()
        if jdk is None:
            raise unittest.SkipTest("no JDK (javac + java) found")
        cls._td = tempfile.TemporaryDirectory(prefix="voice_uplink_harness_")
        out = cls._td.name
        c = subprocess.run(jvm_harness.javac_cmd(jdk[0], out, [HARNESS_MAIN], [HARNESS, VOICE_SRC, SHARED_SRC]),
                           capture_output=True, text=True)
        cls.compiled = c.returncode == 0
        cls.compile_output = (c.stdout + c.stderr)[-3000:]
        cls.results = {}
        cls.run_output = ""
        if c.returncode == 0:
            r = subprocess.run([jdk[1], "-cp", out, "com.miko3.mode.voice.UplinkGateHarness"],
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


jvm_harness.add_scenario_tests(UplinkGateHarnessTest)


if __name__ == "__main__":
    unittest.main()
