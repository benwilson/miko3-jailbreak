#!/usr/bin/env python3
"""Host-side tests for Explore's self-adjusting camera brightness (explore nav
plan U9, KTD10; R17).

Brightness is a plain-Java controller: each scored frame's mean luma, the
"moving" flag and the camera's exposure/sensitivity ranges go in, and the next
manual exposure time, sensitivity and frame duration (or "no change") come
out. The harness under fixtures/explore_brightness_harness drives it with
synthetic luma series and a simple simulated scene and prints one PASS/FAIL
line per scenario. The camera's side (manual requests, the fixed frame-rate
range, logging) is Android-only and is checked here by reading its source.
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
HARNESS = TESTS / "fixtures" / "explore_brightness_harness" / "src"
HARNESS_MAIN = HARNESS / "com" / "miko3" / "mode" / "explore" / "BrightnessHarness.java"


def src(name):
    return (PKG / name).read_text()


def code_only(text):
    """Source without // and /* */ comments."""
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


class BrightnessHarnessTest(unittest.TestCase):
    SCENARIOS = (
        # Happy paths
        "dark_series_raises_exposure_to_the_cap_then_sensitivity",
        "bright_series_lowers_sensitivity_then_exposure",
        "inside_the_dead_band_changes_nothing",
        "hysteresis_settles_into_the_inner_band_then_holds",
        "steps_are_small_and_multiplicative",
        "simulated_rooms_converge_without_oscillating",
        # Moving versus still
        "moving_pulls_a_long_exposure_to_the_moving_cap_at_once",
        "still_allows_a_longer_exposure_than_moving",
        # Ranges
        "settings_stay_within_the_reported_ranges",
        "missing_ranges_use_safe_defaults",
        "frame_duration_follows_the_exposure",
        # Edges
        "covered_lens_stops_at_the_limits_and_never_oscillates",
        "frames_just_after_a_change_are_ignored",
        "bad_luma_changes_nothing",
        "next_open_starts_from_the_last_settings_that_worked",
        "mean_luma_of_pixels",
    )

    @classmethod
    def setUpClass(cls):
        cls.results = {}
        cls.run_output = ""
        jdk = jvm_harness.find_jdk()
        if jdk is None:
            raise unittest.SkipTest("no JDK (javac + java) found")
        with tempfile.TemporaryDirectory(prefix="explore_brightness_harness_") as out:
            c = subprocess.run(jvm_harness.javac_cmd(jdk[0], out, [HARNESS_MAIN], [HARNESS, EXPLORE_SRC]),
                               capture_output=True, text=True)
            cls.compiled = c.returncode == 0
            cls.compile_output = (c.stdout + c.stderr)[-3000:]
            if cls.compiled:
                r = subprocess.run([jdk[1], "-cp", out, "com.miko3.mode.explore.BrightnessHarness"],
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


jvm_harness.add_scenario_tests(BrightnessHarnessTest)


class BrightnessIsPlainJavaTest(unittest.TestCase):
    def test_no_android_or_shared_imports_and_no_logging(self):
        b = code_only(src("Brightness.java"))
        for word in ("import android", "com.miko3.shared", "Log.", "System.out", "System.err"):
            self.assertFalse(word in b, word)


class CameraUsesBrightnessTest(unittest.TestCase):
    """ExploreCamera's side, read from source: manual exposure only where the
    camera offers it, the frame-rate range stays the fixed one, and the log
    carries only the settings' numbers."""

    def setUp(self):
        self.cam = code_only(src("ExploreCamera.java"))

    def test_frame_rate_range_is_only_the_fixed_one(self):
        sets = re.findall(r"\.set\(\s*CaptureRequest\.CONTROL_AE_TARGET_FPS_RANGE\s*,\s*(\w+)\s*\)", self.cam)
        self.assertEqual(len(sets), 1, "one place sets the frame-rate range")
        self.assertEqual(self.cam.count("CONTROL_AE_TARGET_FPS_RANGE"), 1)
        # Its value comes only from fixedFpsRange(), which prefers lower == upper.
        self.assertTrue("= fixedFpsRange(c)" in self.cam, "= fixedFpsRange(c)")
        self.assertTrue("r.getLower().equals(r.getUpper())" in self.cam, "r.getLower().equals(r.getUpper())")
        self.assertFalse(re.search(r"new\s+Range\s*<|Range\.create\(", self.cam), "a range built by hand")

    def test_manual_exposure_only_when_the_camera_lists_ae_off(self):
        self.assertTrue("CONTROL_AE_AVAILABLE_MODES" in self.cam, "CONTROL_AE_AVAILABLE_MODES")
        self.assertTrue("CaptureRequest.CONTROL_AE_MODE_OFF" in self.cam, "CaptureRequest.CONTROL_AE_MODE_OFF")
        for key in ("SENSOR_EXPOSURE_TIME", "SENSOR_SENSITIVITY", "SENSOR_FRAME_DURATION",
                    "SENSOR_INFO_EXPOSURE_TIME_RANGE", "SENSOR_INFO_SENSITIVITY_RANGE"):
            self.assertTrue(key in self.cam, key)
        # The fallback is today's auto exposure with maximum compensation.
        self.assertTrue("CaptureRequest.CONTROL_AE_MODE_ON" in self.cam, "CaptureRequest.CONTROL_AE_MODE_ON")
        self.assertTrue("CONTROL_AE_EXPOSURE_COMPENSATION" in self.cam, "CONTROL_AE_EXPOSURE_COMPENSATION")

    def test_brightness_is_fed_from_the_openness_decode_and_applied_on_the_camera_thread(self):
        self.assertTrue("Brightness.meanLuma(smallPixels" in self.cam, "Brightness.meanLuma(smallPixels")
        self.assertTrue("brightness.onFrame(" in self.cam, "brightness.onFrame(")
        self.assertTrue("brightness.start(" in self.cam, "brightness.start(")
        self.assertTrue(re.search(r"cameraHandler\.post\(\s*applyExposure\s*\)", self.cam),
                        "posted to the camera thread")

    def test_the_brain_can_say_when_he_moves(self):
        brain = code_only(src("ExploreBrain.java"))
        self.assertTrue(re.search(r"default void setMoving\(boolean moving\)\s*\{\s*\}", brain), "brain default")
        self.assertTrue(re.search(r"public void setMoving\(boolean moving\)", self.cam), "camera override")

    def test_logs_carry_no_pixel_data(self):
        for call in re.findall(r"\bLog\.\w\((.*?)\);", self.cam, re.S):
            bare = re.sub(r'"(?:\\.|[^"\\])*"', "", call)
            self.assertNotRegex(bare, r"(?i)\b(jpeg|smallPixels|px|luma|meanLuma|bmp|frame)\b",
                                " ".join(call.split()))

    def test_exposure_log_is_numbers_only(self):
        calls = [c for c in re.findall(r"\bLog\.\w\((.*?)\);", self.cam, re.S) if '" ms, ISO "' in c]
        self.assertTrue(calls, "the settings are logged when they change")
        for call in calls:
            bare = re.sub(r'"(?:\\.|[^"\\])*"', "", call)
            names = set(re.findall(r"[A-Za-z_]\w*", bare)) - {"TAG", "s", "exposureMs", "sensitivity", "fps", "null"}
            self.assertEqual(names, set(), " ".join(call.split()))


if __name__ == "__main__":
    unittest.main()
