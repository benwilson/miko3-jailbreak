#!/usr/bin/env python3
"""Tests for the speaker's buffer sizing (U12, KTD6).

Every reply on the robot logged underruns=2..3 and stuttered: the AudioTrack
was capped at one 80 ms chunk, so the voice-player thread had to wake and
refill inside 80 ms while the wake-word spotter ran inference on the same
quad-core, and the player queue (1.04 s) was smaller than the cushion the
relay now bursts (2.0 s). The sizes are preferences again, but the arithmetic
between them -- what the track starts on, what it holds once playing, what the
queue may hold, and where a hand-written value is clamped -- lives in
SpeakerSizing, plain Java with no android.* imports.

VoicePlayer and VoiceEngine are Android-only and the repo has no Android test
harness, so this compiles SpeakerSizing (and VoiceSettings) for the host JVM
and runs fixtures/voice_sizing_harness, which prints one PASS/FAIL line per
scenario.
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
HARNESS = TESTS / "fixtures" / "voice_sizing_harness" / "src"
# SpeakerSizing reads ConversationClient.SPEAKER_RATE, and the client reaches the
# shared module, so the same android.util.Log stub the client harness uses is on
# the sourcepath; nothing under test calls into it.
LOG_STUBS = TESTS / "fixtures" / "ws_harness" / "stubs"
HARNESS_MAIN = HARNESS / "com" / "miko3" / "mode" / "voice" / "SpeakerSizingHarness.java"
SIZING = VOICE_SRC / "com" / "miko3" / "mode" / "voice" / "SpeakerSizing.java"


class SizingIsPlainJavaTest(unittest.TestCase):
    """It only runs on the host JVM while it stays clear of the Android SDK."""

    def test_no_android_imports(self):
        offenders = [line for line in SIZING.read_text().splitlines()
                     if line.startswith("import ") and ".android." in line or line.startswith("import android")]
        self.assertEqual(offenders, [])


class SpeakerSizingHarnessTest(unittest.TestCase):
    """One harness run, one assertion per scenario."""

    SCENARIOS = (
        "defaults",
        "chunk_is_eighty_milliseconds_of_the_speaker_rate",
        "absent_preferences_fall_back_to_defaults",
        "zero_preferences_fall_back_to_defaults",
        "prebuffer_clamped_at_its_bounds",
        "speaker_buffer_ms_clamped_at_its_bounds",
        "player_queue_chunks_clamped_at_its_bounds",
        "queue_holds_the_relay_burst",
        "queue_bottom_bound_is_the_old_one_second",
        "track_buffer_is_never_below_the_prebuffer",
        "start_threshold_is_the_prebuffer_not_the_cushion",
        "hardware_minimum_raises_both_sizes",
        "capacity_leaves_room_above_the_cushion",
        "start_waits_for_the_prebuffer",
        "short_reply_starts_when_the_stream_goes_quiet",
        "trickling_reply_starts_at_the_wait_cap",
        "nothing_queued_never_starts",
        "no_arrival_times_yet_waits_for_the_prebuffer",
    )

    @classmethod
    def setUpClass(cls):
        jdk = jvm_harness.find_jdk()
        if jdk is None:
            raise unittest.SkipTest("no JDK (javac + java) found")
        cls._td = tempfile.TemporaryDirectory(prefix="voice_sizing_harness_")
        out = cls._td.name
        sourcepath = [HARNESS, VOICE_SRC, SHARED_SRC, LOG_STUBS]
        c = subprocess.run(jvm_harness.javac_cmd(jdk[0], out, [HARNESS_MAIN], sourcepath),
                           capture_output=True, text=True)
        cls.compiled = c.returncode == 0
        cls.compile_output = (c.stdout + c.stderr)[-3000:]
        cls.results = {}
        cls.run_output = ""
        if c.returncode == 0:
            r = subprocess.run([jdk[1], "-cp", out, "com.miko3.mode.voice.SpeakerSizingHarness"],
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


jvm_harness.add_scenario_tests(SpeakerSizingHarnessTest)


if __name__ == "__main__":
    unittest.main()
