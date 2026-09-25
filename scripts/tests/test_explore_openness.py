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
NAV_FRAMES = REPO / "voice-work" / "nav-frames"
GATE_FRAMES = (NAV_FRAMES / "open-floor-doorway.jpg", NAV_FRAMES / "wall-2ft.jpg")


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
        "all_floor_frame_scores_every_bin_open",
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
    """U3's gate on the owner's two real robot frames (open floor with a doorway;
    a plain wall 2-3 ft away), sampled as ExploreCamera samples them. Optional:
    the frames are private and not in git, so this skips without them."""

    SCENARIOS = (
        "the_dim_carpet_teaches",
        "the_open_frame_is_trusted_once_taught",
        "the_wall_scores_clearly_below_the_doorway",
        "the_plant_and_chair_stay_below_the_doorway",
    )

    @classmethod
    def setUpClass(cls):
        missing = [f.name for f in GATE_FRAMES if not f.is_file()]
        if missing:
            raise unittest.SkipTest(f"private gate frames absent: {', '.join(missing)}")
        jdk = jvm_harness.find_jdk()
        if jdk is None:
            raise unittest.SkipTest("no JDK (javac + java) found")
        cls.results = {}
        cls.run_output = ""
        with tempfile.TemporaryDirectory(prefix="explore_openness_gate_") as out:
            c = subprocess.run(jvm_harness.javac_cmd(jdk[0], out, [GATE_MAIN], [HARNESS, EXPLORE_SRC]),
                               capture_output=True, text=True)
            cls.compiled = c.returncode == 0
            cls.compile_output = (c.stdout + c.stderr)[-3000:]
            if cls.compiled:
                r = subprocess.run([jdk[1], "-Djava.awt.headless=true", "-cp", out,
                                    "com.miko3.mode.explore.OpennessGate"] + [str(f) for f in GATE_FRAMES],
                                   capture_output=True, text=True, timeout=60)
                cls.run_output = (r.stdout + r.stderr)[-6000:]
                cls.results = jvm_harness.parse_verdicts(r.stdout)

    def setUp(self):
        self.assertTrue(self.compiled, f"gate failed to compile:\n{self.compile_output}")

    def _assert_pass(self, name):
        verdict, detail = self.results.get(name, ("MISSING", self.run_output))
        self.assertEqual(verdict, "PASS", detail)

    def test_gate_ran_exactly_the_listed_checks(self):
        self.assertEqual(sorted(self.results), sorted(self.SCENARIOS), self.run_output)


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
