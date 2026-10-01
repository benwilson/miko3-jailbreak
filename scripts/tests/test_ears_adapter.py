#!/usr/bin/env python3
"""Behaviour tests for EarsAdapter.onHeard (PR #29 review).

EarsAdapter maps the launcher's heard utterances onto the brain's cue queue,
and decides which go to an armed conversation reply, which partials are held
and joined, and what the bounded queue drops. It needs the Android SDK, so
test_explore_claude_wiring only reads its source. This compiles the real
EarsAdapter.java, Ears.java and SensorReading.java against small stubs
(fixtures/ears_adapter_harness/stubs plus the ws_harness Android stubs) and
runs fixtures/ears_adapter_harness, which drives onHeard with scripted
deliveries and prints one PASS/FAIL line per scenario.
"""
import re
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
EXPLORE_PKG = REPO / "mode-explore" / "src" / "com" / "miko3" / "mode" / "explore"
REAL_ROBOT_EARS = REPO / "shared" / "src" / "com" / "miko3" / "shared" / "RobotEars.java"
FIXTURE = TESTS / "fixtures" / "ears_adapter_harness"
STUBS = FIXTURE / "stubs"
STUB_ROBOT_EARS = STUBS / "com" / "miko3" / "shared" / "RobotEars.java"
ANDROID_STUBS = TESTS / "fixtures" / "ws_harness" / "stubs"
HARNESS_MAIN = FIXTURE / "src" / "com" / "miko3" / "mode" / "explore" / "EarsAdapterHarness.java"
# The real sources under test; everything else EarsAdapter touches is stubbed.
REAL_SOURCES = [EXPLORE_PKG / n for n in ("EarsAdapter.java", "Ears.java", "SensorReading.java")]

CONSTANT = re.compile(r"^\s*int\s+((?:TIER|SIDE|KIND)_[A-Z_]+)\s*=\s*(-?\d+)\s*;", re.M)


class StubConstantsMatchTest(unittest.TestCase):
    """The stub's wire constants must be the launcher's, or the harness tests a different mapping."""

    def test_robot_ears_constants_match(self):
        real = dict(CONSTANT.findall(REAL_ROBOT_EARS.read_text()))
        stub = dict(CONSTANT.findall(STUB_ROBOT_EARS.read_text()))
        self.assertTrue(real, "no constants parsed from the real RobotEars")
        self.assertEqual(stub, real)


class EarsAdapterHarnessTest(unittest.TestCase):
    """One harness run, one assertion per scenario."""

    SCENARIOS = (
        "early_wake_cue_is_queued_not_given_to_armed_reply",
        "called_end_delivery_goes_to_armed_reply",
        "called_end_delivery_without_reply_is_queued_called",
        "called_partial_keeps_mark_and_is_not_joined",
        "uncalled_strong_partial_joins_later_weak_cue",
        "queue_overflow_drops_oldest",
        "worded_utterance_queued_with_side_and_angle",
        # Robot 2026-10-01: the launcher's "answering" for a conversation listen.
        "answering_goes_to_the_armed_reply_and_its_words_still_answer_it",
        "answering_with_no_armed_reply_is_ignored_and_queues_nothing",
    )

    @classmethod
    def setUpClass(cls):
        jdk = jvm_harness.find_jdk()
        if jdk is None:
            raise unittest.SkipTest("no JDK (javac + java) found")
        cls._td = tempfile.TemporaryDirectory(prefix="ears_adapter_harness_")
        out = cls._td.name
        # Explicit sources, no mode-explore sourcepath: the stub ExploreDrive stands in for the real one.
        stubs = sorted(STUBS.rglob("*.java")) + sorted(ANDROID_STUBS.rglob("*.java"))
        c = subprocess.run(jvm_harness.javac_cmd(jdk[0], out, [HARNESS_MAIN] + REAL_SOURCES + stubs),
                           capture_output=True, text=True)
        cls.compiled = c.returncode == 0
        cls.compile_output = (c.stdout + c.stderr)[-3000:]
        cls.results = {}
        cls.run_output = ""
        if c.returncode == 0:
            r = subprocess.run([jdk[1], "-cp", out, "com.miko3.mode.explore.EarsAdapterHarness"],
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

    def test_no_unlisted_scenarios(self):
        self.assertEqual(sorted(self.results), sorted(self.SCENARIOS), self.run_output)


jvm_harness.add_scenario_tests(EarsAdapterHarnessTest)


if __name__ == "__main__":
    unittest.main()
