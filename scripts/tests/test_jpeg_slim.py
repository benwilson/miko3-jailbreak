#!/usr/bin/env python3
"""Host-side tests for JpegSlim, the lossless JPEG slimmer on Claude uploads.

The robot's hardware JPEG is ~445 KB, of which ~300 KB is the camera vendor's
APPn blocks (EXIF, MakerNote, MediaTek tuning) and ~128-byte zero padding after
EOI; the image itself is ~140 KB. JpegSlim walks the marker segments, drops
APP1-APP15 and COM, keeps APP0 and every other segment byte-for-byte, and stops
at EOI. No re-encode, so the pixels are identical. Anything it can't walk
(not a JPEG, a bad length, a truncated scan) comes back unchanged.

ClaudeApi.jpegBlock slims every image it encodes, so every Claude request
(look, doorway, way-out, face) uploads the slim bytes while the frames kept for
face matching and the debug frame ring stay as captured.

The harness under fixtures/jpeg_slim_harness compiles JpegSlim and ClaudeApi
from shared/src and decodes with javax.imageio. It runs on the checked-in
robot frame plus up to 50 frames from out/camera-frames-run/ when present.
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
SHARED_SRC = REPO / "shared" / "src"
SHARED_PKG = SHARED_SRC / "com" / "miko3" / "shared"
FIXTURE = TESTS / "fixtures" / "jpeg_slim_harness"
HARNESS = FIXTURE / "src"
HARNESS_MAIN = HARNESS / "com" / "miko3" / "shared" / "JpegSlimHarness.java"
SAMPLE = FIXTURE / "robot-frame.jpg"
RUN_FRAMES = REPO / "out" / "camera-frames-run"
# A real truncated hardware frame (scan cut short, no EOI), when the local copy exists.
TRUNCATED = Path("/tmp/compound-engineering-501/lfg/frames/f34.jpg")


def sample_frames(limit=50):
    """The checked-in frame plus up to `limit` evenly spaced frames from a local run."""
    frames = [SAMPLE]
    run = sorted(RUN_FRAMES.glob("frame-*.jpg")) if RUN_FRAMES.is_dir() else []
    if run:
        step = max(1, len(run) // limit)
        frames += run[::step][:limit]
    return frames


def code_only(text):
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


class JpegSlimHarnessTest(unittest.TestCase):
    SCENARIOS = (
        "a_real_frame_slims_smaller_and_decodes_to_identical_pixels",
        "drops_app1_to_app15_and_com_keeps_app0",
        "keeps_tables_frame_scan_and_eoi_byte_for_byte",
        "drops_the_hardware_zero_padding_after_eoi",
        "malformed_or_truncated_input_is_returned_unchanged",
        "the_sample_frames_carry_orientation_1_so_app1_goes",
        "a_non_1_orientation_survives_as_a_minimal_app1",
        "an_already_slim_jpeg_comes_back_as_is",
        "a_request_image_block_carries_the_slimmed_bytes",
    )

    @classmethod
    def setUpClass(cls):
        cls.results = {}
        cls.run_output = ""
        jdk = jvm_harness.find_jdk()
        if jdk is None:
            raise unittest.SkipTest("no JDK (javac + java) found")
        with tempfile.TemporaryDirectory(prefix="jpeg_slim_harness_") as out:
            c = subprocess.run(jvm_harness.javac_cmd(jdk[0], out, [HARNESS_MAIN], [HARNESS, SHARED_SRC]),
                               capture_output=True, text=True)
            cls.compiled = c.returncode == 0
            cls.compile_output = (c.stdout + c.stderr)[-3000:]
            if cls.compiled:
                args = []
                if TRUNCATED.is_file():
                    args += ["--truncated", str(TRUNCATED)]
                args += [str(f) for f in sample_frames()]
                r = subprocess.run([jdk[1], "-Djava.awt.headless=true", "-cp", out,
                                    "com.miko3.shared.JpegSlimHarness"] + args,
                                   capture_output=True, text=True, timeout=120)
                cls.run_output = (r.stdout + r.stderr)[-6000:]
                cls.results = jvm_harness.parse_verdicts(r.stdout)

    def setUp(self):
        self.assertTrue(self.compiled, f"harness failed to compile:\n{self.compile_output}")

    def _assert_pass(self, name):
        verdict, detail = self.results.get(name, ("MISSING", self.run_output))
        self.assertEqual(verdict, "PASS", detail)

    def test_harness_ran_exactly_the_listed_scenarios(self):
        self.assertEqual(sorted(self.results), sorted(self.SCENARIOS), self.run_output)


jvm_harness.add_scenario_tests(JpegSlimHarnessTest)


class JpegSlimWiringTest(unittest.TestCase):
    def test_slim_is_plain_java(self):
        slim = code_only((SHARED_PKG / "JpegSlim.java").read_text())
        self.assertNotIn("import android", slim)
        # Lossless: it never decodes or re-encodes.
        for codec in ("Bitmap", "ImageIO", "compress("):
            self.assertNotIn(codec, slim)

    def test_the_request_image_block_slims_its_bytes(self):
        api = code_only((SHARED_PKG / "ClaudeApi.java").read_text())
        block = api[api.index("Map<String, Object> jpegBlock("):]
        block = block[:block.index("\n    }\n")]
        self.assertIn("JpegSlim.slim(", block)


if __name__ == "__main__":
    unittest.main()
