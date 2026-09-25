#!/usr/bin/env python3
"""Host-side tests for explore's face detector (YuNet, explore on Claude KTD5).

Android's FaceDetector missed an obvious, well-lit frontal face (the owner, in
glasses, ~115 px wide in a 640x480 frame), so Explore never stored a face. YuNet
(OpenCV Zoo, face_detection_yunet_2023mar.onnx) replaced it, run on the ONNX
Runtime the mode already bundles.

The harness feeds YuNetDecoder synthetic outputs in the 2023mar layout -- per
stride 8/16/32 a cls, obj and bbox tensor, one row per grid cell -- and checks
the anchor decode, the score (sqrt(cls * obj), each clamped to 0..1), the
threshold, NMS, the pick of the largest face inside the person box, and the
frame-to-input fit. The pinned model's checksum and licence are checked too, and
when Python's onnxruntime is present the real model runs on a synthetic image
and its outputs go through the same Java decoder.
"""
import hashlib
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
PKG = REPO / "mode-explore" / "src" / "com" / "miko3" / "mode" / "explore"
HARNESS = TESTS / "fixtures" / "explore_yunet_harness" / "src"
HARNESS_MAIN = HARNESS / "com" / "miko3" / "mode" / "explore" / "YuNetHarness.java"
PLAIN_JAVA = [PKG / "YuNetDecoder.java"]
MODEL = REPO / "mode-explore" / "assets" / "face_yunet.onnx"
MODEL_LICENSE = REPO / "mode-explore" / "assets" / "face_yunet-LICENSE.txt"
# opencv_zoo models/face_detection_yunet/face_detection_yunet_2023mar.onnx (MIT).
MODEL_SHA256 = "8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4"
STRIDES = (8, 16, 32)


def compile_harness(out):
    jdk = jvm_harness.find_jdk()
    if jdk is None:
        raise unittest.SkipTest("no JDK (javac + java) found")
    c = subprocess.run(jvm_harness.javac_cmd(jdk[0], out, [HARNESS_MAIN] + PLAIN_JAVA, [HARNESS]),
                       capture_output=True, text=True)
    return jdk[1], c.returncode == 0, (c.stdout + c.stderr)[-3000:]


class YuNetDecodeTest(unittest.TestCase):
    SCENARIOS = (
        "decodes_an_anchor_to_a_box_in_input_pixels",
        "decodes_the_coarsest_stride_too",
        "score_is_the_root_of_cls_times_obj_clamped",
        "below_threshold_dropped",
        "overlapping_faces_merged_keeping_the_best",
        "far_apart_faces_both_kept_best_first_and_capped",
        "short_output_rejected",
        "largest_face_inside_the_person_box_is_picked",
        "no_face_inside_the_person_box_is_null",
        "frame_fits_the_input_without_upscaling",
    )

    @classmethod
    def setUpClass(cls):
        cls.results = {}
        cls.run_output = ""
        with tempfile.TemporaryDirectory(prefix="explore_yunet_harness_") as out:
            java, cls.compiled, cls.compile_output = compile_harness(out)
            if cls.compiled:
                r = subprocess.run([java, "-cp", out, "com.miko3.mode.explore.YuNetHarness"],
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


jvm_harness.add_scenario_tests(YuNetDecodeTest)


class PinnedModelTest(unittest.TestCase):
    def test_the_model_is_the_pinned_2023mar_file(self):
        self.assertEqual(hashlib.sha256(MODEL.read_bytes()).hexdigest(), MODEL_SHA256)

    def test_the_mit_licence_ships_beside_it(self):
        text = MODEL_LICENSE.read_text()
        self.assertIn("MIT License", text)
        self.assertIn("Shiqi Yu", text)


def run_model(image_chw_bgr):
    """The real model's outputs for one 1x3xHxW BGR 0..255 image: {name: array}."""
    import onnxruntime as ort
    s = ort.InferenceSession(str(MODEL), providers=["CPUExecutionProvider"])
    names = [o.name for o in s.get_outputs()]
    return dict(zip(names, s.run(None, {s.get_inputs()[0].name: image_chw_bgr}))), s


def decode_with_java(outputs, in_w, in_h, out_dir, java_cp, java):
    """Dumps the cls/obj/bbox outputs as raw float32 and decodes them with the
    harness's --decode mode: [(x0, y0, x1, y1, score)] in input pixels."""
    import numpy as np
    for kind in ("cls", "obj", "bbox"):
        for stride in STRIDES:
            np.asarray(outputs[f"{kind}_{stride}"], dtype="<f4").tofile(Path(out_dir) / f"{kind}_{stride}.bin")
    r = subprocess.run([java, "-cp", java_cp, "com.miko3.mode.explore.YuNetHarness", "--decode", str(out_dir),
                        str(in_w), str(in_h)], capture_output=True, text=True, timeout=60)
    if r.returncode != 0:
        raise AssertionError(r.stdout + r.stderr)
    return [tuple(float(v) for v in line.split()[1:]) for line in r.stdout.splitlines() if line.startswith("FACE ")]


class RealModelTest(unittest.TestCase):
    """The real ONNX, when Python's onnxruntime is installed; skipped otherwise."""

    @classmethod
    def setUpClass(cls):
        try:
            import numpy  # noqa: F401
            import onnxruntime  # noqa: F401
        except ImportError:
            raise unittest.SkipTest("onnxruntime for Python not installed")

    def test_outputs_are_the_layout_the_decoder_reads(self):
        import numpy as np
        outputs, session = run_model(np.zeros((1, 3, 640, 640), dtype=np.float32))
        shape = session.get_inputs()[0].shape
        self.assertEqual(shape, [1, 3, 640, 640])
        for stride in STRIDES:
            cells = (640 // stride) ** 2
            self.assertEqual(outputs[f"cls_{stride}"].shape, (1, cells, 1))
            self.assertEqual(outputs[f"obj_{stride}"].shape, (1, cells, 1))
            self.assertEqual(outputs[f"bbox_{stride}"].shape, (1, cells, 4))

    def test_a_blank_grey_frame_has_no_face(self):
        import numpy as np
        image = np.full((1, 3, 640, 640), 128, dtype=np.float32)
        outputs, _ = run_model(image)
        with tempfile.TemporaryDirectory(prefix="explore_yunet_real_") as out:
            java, ok, log = compile_harness(out)
            self.assertTrue(ok, log)
            faces = decode_with_java(outputs, 640, 640, out, out, java)
        self.assertEqual(faces, [])


if __name__ == "__main__":
    unittest.main()
