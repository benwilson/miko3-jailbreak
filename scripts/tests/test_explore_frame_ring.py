#!/usr/bin/env python3
"""Host-side tests for Explore's debug frame ring (camera calibration).

With log.tag.MikoExploreFrames=DEBUG, ExploreCamera saves each decoded look's
JPEG (the bytes it already has, no re-encode) to files/frames/frame-<wall ms>.jpg,
keeping the newest ~400. scripts/calibrate-camera-fov.py pairs those frames
with the brain's measured turns. FrameRing is plain Java; the harness under
fixtures/explore_frame_ring_harness drives it in a temp directory. The
camera's side is checked by reading its source.
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
HARNESS = TESTS / "fixtures" / "explore_frame_ring_harness" / "src"
HARNESS_MAIN = HARNESS / "com" / "miko3" / "mode" / "explore" / "FrameRingHarness.java"


def code_only(text):
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


class FrameRingHarnessTest(unittest.TestCase):
    SCENARIOS = (
        "saves_the_exact_bytes_named_by_wall_time",
        "keeps_only_the_newest_capacity_frames",
        "a_new_ring_adopts_frames_already_on_disk",
        "ignores_other_files_in_the_directory",
        "creates_the_directory",
    )

    @classmethod
    def setUpClass(cls):
        cls.results = {}
        cls.run_output = ""
        jdk = jvm_harness.find_jdk()
        if jdk is None:
            raise unittest.SkipTest("no JDK (javac + java) found")
        with tempfile.TemporaryDirectory(prefix="explore_frame_ring_harness_") as out:
            c = subprocess.run(jvm_harness.javac_cmd(jdk[0], out, [HARNESS_MAIN], [HARNESS, EXPLORE_SRC]),
                               capture_output=True, text=True)
            cls.compiled = c.returncode == 0
            cls.compile_output = (c.stdout + c.stderr)[-3000:]
            if cls.compiled:
                r = subprocess.run([jdk[1], "-cp", out, "com.miko3.mode.explore.FrameRingHarness"],
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


jvm_harness.add_scenario_tests(FrameRingHarnessTest)


class FrameRingIsPlainJavaTest(unittest.TestCase):
    def test_no_android_imports(self):
        ring = code_only((PKG / "FrameRing.java").read_text())
        self.assertNotIn("import android", ring)


class CameraUsesFrameRingTest(unittest.TestCase):
    def setUp(self):
        self.cam = code_only((PKG / "ExploreCamera.java").read_text())

    def test_gated_on_its_own_debug_tag_off_by_default(self):
        self.assertIn('FRAMES_TAG = "MikoExploreFrames"', self.cam)
        self.assertTrue(re.search(r"Log\.isLoggable\(\s*FRAMES_TAG\s*,\s*Log\.DEBUG\s*\)", self.cam))

    def test_wall_time_taken_at_frame_arrival(self):
        listener = self.cam[self.cam.index("onImageAvailable"):self.cam.index("private void recognize(")]
        self.assertIn("System.currentTimeMillis()", listener)

    def test_saves_the_jpeg_it_already_has(self):
        self.assertTrue(re.search(r"\.save\(\s*wallMs\s*,\s*jpeg\s*\)", self.cam))
        self.assertNotIn("compress(", self.cam)

    def test_ring_lives_under_private_files(self):
        self.assertTrue(re.search(r'new File\(\s*context\.getFilesDir\(\)\s*,\s*"frames"\s*\)', self.cam))


if __name__ == "__main__":
    unittest.main()
