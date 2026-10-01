#!/usr/bin/env python3
"""Host-side tests for Explore's visual place memory (owner, 2026-10-01).

"He seems to go to a lot of the same places over and over again... if he's been
somewhere in the last 30 minutes, he should try and find somewhere else to go."
The coverage grid is dead-reckoned and drifts through wedges, shoves and escapes,
so PlaceMemory keeps a small print of each look (a 16x12 grey thumbnail and a
hue/saturation histogram) for 30 minutes and says how much the current view looks
like one seen from roughly the same heading. The brain lowers the steer's novelty
of headings that look familiar.

PlaceMemory is plain Java; the harness under fixtures/explore_place_memory_harness
drives it, including on real frames from the 2026-10-01 roam (frames/, made by
make_frames.py with the reference scores the Java port must reproduce).
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
EXPLORE_SRC = REPO / "mode-explore" / "src"
PKG = EXPLORE_SRC / "com" / "miko3" / "mode" / "explore"
FIXTURE = TESTS / "fixtures" / "explore_place_memory_harness"
HARNESS = FIXTURE / "src"
HARNESS_MAIN = HARNESS / "com" / "miko3" / "mode" / "explore" / "PlaceMemoryHarness.java"
FRAMES = FIXTURE / "frames"


def code_only(text):
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


class PlaceMemoryHarnessTest(unittest.TestCase):
    SCENARIOS = (
        "real_frames_score_like_the_offline_reference",
        "a_plain_wall_is_no_evidence_either_way",
        "a_new_view_gives_full_novelty",
        "repeating_the_same_view_at_the_same_heading_lowers_its_novelty",
        "a_view_from_under_a_minute_ago_does_not_count",
        "a_memory_older_than_30_min_is_forgotten",
        "an_unusable_heading_still_matches_on_appearance",
        "the_same_view_more_than_45_deg_away_does_not_match",
        "keeps_one_print_per_record_interval_and_at_most_the_cap_oldest_first",
        "the_steer_picks_the_unfamiliar_side",
        "grid_and_place_combine_by_the_lower_and_a_blocked_band_never_gains",
        "the_note_reads_seen_before_with_sim_and_minutes",
        "defaults_30_min_300_prints",
    )

    @classmethod
    def setUpClass(cls):
        cls.results = {}
        cls.run_output = ""
        jdk = jvm_harness.find_jdk()
        if jdk is None:
            raise unittest.SkipTest("no JDK (javac + java) found")
        with tempfile.TemporaryDirectory(prefix="explore_place_memory_harness_") as out:
            c = subprocess.run(jvm_harness.javac_cmd(jdk[0], out, [HARNESS_MAIN], [HARNESS, EXPLORE_SRC]),
                               capture_output=True, text=True)
            cls.compiled = c.returncode == 0
            cls.compile_output = (c.stdout + c.stderr)[-3000:]
            if cls.compiled:
                r = subprocess.run([jdk[1], "-cp", out, "com.miko3.mode.explore.PlaceMemoryHarness", str(FRAMES)],
                                   capture_output=True, text=True, timeout=60)
                cls.run_output = (r.stdout + r.stderr)[-6000:]
                cls.results = jvm_harness.parse_verdicts(r.stdout)

    def setUp(self):
        self.assertTrue(self.compiled, f"harness failed to compile:\n{self.compile_output}")

    def _assert_pass(self, name):
        verdict, detail = self.results.get(name, ("MISSING", self.run_output))
        self.assertEqual(verdict, "PASS", detail)

    def test_harness_ran_exactly_the_listed_scenarios(self):
        self.assertEqual(sorted(self.results), sorted(self.SCENARIOS), self.run_output)


jvm_harness.add_scenario_tests(PlaceMemoryHarnessTest)


class PlaceMemoryIsPlainJavaTest(unittest.TestCase):
    def test_no_android_imports(self):
        src = code_only((PKG / "PlaceMemory.java").read_text())
        self.assertNotIn("import android", src)
        self.assertNotIn("com.miko3.shared", src)

    def test_real_frames_are_present(self):
        self.assertEqual(len(list(FRAMES.glob("*.rgb"))), 9)
        self.assertIn("pair ", (FRAMES / "expected.txt").read_text())


if __name__ == "__main__":
    unittest.main()
