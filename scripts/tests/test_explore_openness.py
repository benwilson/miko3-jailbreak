#!/usr/bin/env python3
"""Host-side tests for Explore's per-look openness profile (explore nav plan U3,
KTD3, KTD9; R2, R6, R15).

Each look carries ~16 column bins scored 0..1 plus a confidence, built on the
detect thread from three cheap signals: the detector boxes' bottom edges, a
floor-colour model of the bottom rows (taught only from frames captured while
the floor was clear and the wheels free, and only once he has driven over the
patch cleanly), and a horizon test. Openness is plain Java, so the harness
under fixtures/explore_openness_harness paints synthetic frames and prints one
PASS/FAIL line per scenario. The camera's side (decode, dump switch, logging)
is Android-only and is checked here by reading its source.
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
HARNESS = TESTS / "fixtures" / "explore_openness_harness" / "src"
HARNESS_MAIN = HARNESS / "com" / "miko3" / "mode" / "explore" / "OpennessHarness.java"
GATE_MAIN = HARNESS / "com" / "miko3" / "mode" / "explore" / "OpennessGate.java"
# The owner's real frames for U3's gate: private, gitignored, often absent.
# Two pairs from the same spots: dim (before the camera ran its own exposure,
# U9) and bright (after). Each: label, open frame, wall frame, doorway bins.
# The bright frame sits about a bin further left, so its red door panel covers
# bin 7 (a standing surface, rightly not open): its doorway is bins 8-10.
NAV_FRAMES = REPO / "voice-work" / "nav-frames"
GATE_PAIRS = (
    ("dim", NAV_FRAMES / "open-floor-doorway.jpg", NAV_FRAMES / "wall-2ft.jpg", (7, 10)),
    ("bright", NAV_FRAMES / "open-floor-doorway-bright.jpg", NAV_FRAMES / "wall-2ft-bright.jpg", (8, 10)),
)
GATE_CHECKS = (
    "carpet_teaches",
    "open_frame_is_trusted_once_taught",
    "wall_reads_blocked_in_every_bin",
    "wall_scores_clearly_below_the_doorway",
    "plant_and_chair_stay_below_the_doorway",
)


def src(name):
    return (PKG / name).read_text()


def code_only(text):
    """Source without // and /* */ comments."""
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


class OpennessHarnessTest(unittest.TestCase):
    SCENARIOS = (
        # Happy paths (KTD3)
        "wall_on_the_left_scores_left_low_and_right_high",
        "floor_up_to_the_horizon_scores_every_bin_open",
        "box_reaching_the_bottom_blocks_floor_coloured_columns",
        "high_box_lowers_its_columns_only_slightly",
        "taught_rug_stays_open_and_untaught_colour_reads_unsure",
        "taught_floor_raises_confidence",
        "profile_has_sixteen_bins_in_range",
        # Edges
        "all_dark_frame_has_low_confidence",
        "no_detections_and_no_taught_floor_is_low_confidence",
        "textured_bottom_rows_are_not_a_floor_sample",
        "floor_patches_are_capped",
        "band_optional_whole_frame_alone_still_scores",
        # Teaching (the pending-sample rule)
        "frame_not_teachable_never_teaches",
        "pending_dropped_by_a_hazard_before_driving_there",
        "pending_taught_once_driven_over_cleanly",
        "late_frame_from_before_a_hazard_is_refused",
        "pending_samples_are_capped_oldest_first",
        # Errors: scoring never throws
        "malformed_input_never_throws",
        # Integration with the brain's types
        "look_carries_the_profile_and_older_constructors_leave_it_null",
        "no_camera_accepts_the_floor_calls",
        # The U3 gate's two real failures, as synthetic scenes (this camera: dim, up-tilted)
        "dim_carpet_sample_teaches_and_reads_open",
        "wall_filling_the_frame_scores_blocked_once_floor_is_taught",
        "untaught_floor_filling_the_ground_under_a_far_wall_stays_unsure",
        "standing_thing_stands_where_the_floor_run_ends",
        # After U9 brightened the camera: a grey wall over a grey carpet
        "grey_wall_the_colour_of_a_bright_taught_carpet_still_reads_blocked",
        "a_stray_floor_row_at_a_chairs_foot_is_not_a_view_past_it",
    )

    @classmethod
    def setUpClass(cls):
        cls.results = {}
        cls.run_output = ""
        jdk = jvm_harness.find_jdk()
        if jdk is None:
            raise unittest.SkipTest("no JDK (javac + java) found")
        with tempfile.TemporaryDirectory(prefix="explore_openness_harness_") as out:
            c = subprocess.run(jvm_harness.javac_cmd(jdk[0], out, [HARNESS_MAIN], [HARNESS, EXPLORE_SRC]),
                               capture_output=True, text=True)
            cls.compiled = c.returncode == 0
            cls.compile_output = (c.stdout + c.stderr)[-3000:]
            if cls.compiled:
                r = subprocess.run([jdk[1], "-cp", out, "com.miko3.mode.explore.OpennessHarness"],
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


jvm_harness.add_scenario_tests(OpennessHarnessTest)


class OpennessRealFrameGateTest(unittest.TestCase):
    """U3's gate on the owner's real robot frames (open floor with a doorway; a
    plain wall 2-3 ft away), dim and bright pairs, sampled as ExploreCamera
    samples them. Each pair is taught from its own open frame; with both pairs,
    each taught model also scores the other pair's wall. Optional: the frames
    are private and not in git, so a pair without its files is skipped."""

    SCENARIOS = tuple(f"{label}_{check}" for label, *_ in GATE_PAIRS for check in GATE_CHECKS) + tuple(
        f"{a}_taught_{b}_wall_never_reads_open"
        for a, *_ in GATE_PAIRS for b, *_ in GATE_PAIRS if a != b)

    @classmethod
    def setUpClass(cls):
        cls.present = [p for p in GATE_PAIRS if p[1].is_file() and p[2].is_file()]
        if not cls.present:
            raise unittest.SkipTest("private gate frames absent")
        jdk = jvm_harness.find_jdk()
        if jdk is None:
            raise unittest.SkipTest("no JDK (javac + java) found")
        cls.results = {}
        cls.run_output = ""
        args = []
        for label, open_frame, wall_frame, (door_from, door_to) in cls.present:
            args += [label, str(open_frame), str(wall_frame), str(door_from), str(door_to)]
        with tempfile.TemporaryDirectory(prefix="explore_openness_gate_") as out:
            c = subprocess.run(jvm_harness.javac_cmd(jdk[0], out, [GATE_MAIN], [HARNESS, EXPLORE_SRC]),
                               capture_output=True, text=True)
            cls.compiled = c.returncode == 0
            cls.compile_output = (c.stdout + c.stderr)[-3000:]
            if cls.compiled:
                r = subprocess.run([jdk[1], "-Djava.awt.headless=true", "-cp", out,
                                    "com.miko3.mode.explore.OpennessGate"] + args,
                                   capture_output=True, text=True, timeout=120)
                cls.run_output = (r.stdout + r.stderr)[-6000:]
                cls.results = jvm_harness.parse_verdicts(r.stdout)

    @classmethod
    def expected(cls):
        labels = [p[0] for p in cls.present]
        return sorted(n for n in cls.SCENARIOS
                      if n.split("_", 1)[0] in labels
                      and ("_taught_" not in n or n.split("_taught_")[1].split("_", 1)[0] in labels))

    def setUp(self):
        self.assertTrue(self.compiled, f"gate failed to compile:\n{self.compile_output}")

    def _assert_pass(self, name):
        if name not in self.expected():
            self.skipTest(f"private frames for {name} absent")
        verdict, detail = self.results.get(name, ("MISSING", self.run_output))
        self.assertEqual(verdict, "PASS", detail)

    def test_gate_ran_exactly_the_checks_for_the_present_pairs(self):
        self.assertEqual(sorted(self.results), self.expected(), self.run_output)


jvm_harness.add_scenario_tests(OpennessRealFrameGateTest)


class OpennessIsPrivateTest(unittest.TestCase):
    """R15: no pixels, crops or profiles in the log, and nothing stored outside the
    owner's gate-check switch (private files directory only)."""

    def test_openness_never_logs(self):
        o = code_only(src("Openness.java"))
        for word in ("Log.", "System.out", "System.err", "printStackTrace", "java.io"):
            self.assertNotIn(word, o, word)

    def test_camera_logs_only_the_scoring_duration(self):
        cam = code_only(src("ExploreCamera.java"))
        for call in re.findall(r"\bLog\.\w\((.*?)\);", cam, re.S):
            bare = re.sub(r'"(?:\\.|[^"\\])*"', "", call)
            self.assertNotRegex(bare, r"\b(profile|openness|pixels|band|whole)\b", " ".join(call.split()))
        self.assertIn('"openness in "', cam)

    def test_the_gate_check_dump_is_behind_its_switch_in_private_files(self):
        cam = code_only(src("ExploreCamera.java"))
        self.assertIn('NAV_DEBUG_TAG = "MikoExploreNavDebug"', cam)
        self.assertIn("Log.isLoggable(NAV_DEBUG_TAG, Log.DEBUG)", cam)
        self.assertIn("getFilesDir()", cam)
        for shared in ("getExternal", "Environment.", "MediaStore", "Base64", "/sdcard"):
            self.assertNotIn(shared, cam, shared)
        self.assertLessEqual(len("MikoExploreNavDebug"), 23)

    def test_the_camera_scores_each_look_and_attaches_it(self):
        cam = code_only(src("ExploreCamera.java"))
        self.assertIn("openness.score(", cam)
        self.assertIn("new ExploreBrain.Look(frameMs, found, jpeg, profile)", cam)
        # The flag rides with each captured frame, read on the camera thread.
        self.assertRegex(cam, r"recognize\(jpeg, clock\.nowMs\(\), generation, floorClear\)")


if __name__ == "__main__":
    unittest.main()
