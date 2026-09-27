#!/usr/bin/env python3
"""Host-side tests for explore's face detector (YuNet, explore on Claude KTD5;
on-device face recognition U1, KTD2, R10, R16).

Android's FaceDetector missed an obvious, well-lit frontal face (the owner, in
glasses, ~115 px wide in a 640x480 frame), so Explore never stored a face. YuNet
(OpenCV Zoo) replaced it, run on the ONNX Runtime the mode already bundles. The
2026may release takes any input size (a multiple of 32), so the camera frame is
scaled by half to 320x240 and padded to a fixed 320x256, and its five landmarks
are decoded so the face can be straightened before matching.

The harness feeds YuNetDecoder synthetic outputs in the 2026may layout -- per
stride 8/16/32 a cls, obj, bbox and kps tensor, one row per grid cell -- and
checks the anchor and landmark decode, the score (sqrt(cls * obj), each clamped
to 0..1), the threshold, NMS, the padded rows, the pick of the largest face
inside the person box, and the frame-to-input fit. The pinned model's checksum
and licence are checked too, and when Python's onnxruntime is present the real
model runs on a blank frame and on a public-domain portrait, and its outputs go
through the same Java decoder.
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
# opencv_zoo models/face_detection_yunet/face_detection_yunet_2026may.onnx (MIT).
MODEL_SHA256 = "ebafce4e3c118d6554634be5c27ab333b4c047a9a8c3faf1d7cf93101c22f0f0"
STRIDES = (8, 16, 32)
# The production input (YuNetDecoder.INPUT_W/H, KTD2) and the camera frame.
IN_W, IN_H = 320, 256
FRAME_W, FRAME_H = 640, 480
FACES = TESTS / "fixtures" / "faces"


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
        "decodes_five_landmarks_from_kps_with_the_cell_offset_rule",
        "landmarks_scale_back_to_frame_pixels_with_the_box",
        "input_is_320x256_with_whole_grids_at_every_stride",
        "a_face_in_the_padded_bottom_rows_is_never_reported",
        "short_landmark_output_rejected",
        "empty_outputs_find_no_face",
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
    def test_the_model_is_the_pinned_2026may_file(self):
        self.assertEqual(hashlib.sha256(MODEL.read_bytes()).hexdigest(), MODEL_SHA256)

    def test_the_mit_licence_ships_beside_it(self):
        text = MODEL_LICENSE.read_text()
        self.assertIn("MIT License", text)
        self.assertIn("Shiqi Yu", text)


def portrait_frame(name):
    """A 640x480 RGB camera-like frame with the fixture portrait scaled to the
    frame's height and centred on grey: numpy uint8 HxWx3."""
    import numpy as np
    from PIL import Image
    img = Image.open(FACES / name).convert("RGB")
    w = round(img.width * FRAME_H / img.height)
    frame = Image.new("RGB", (FRAME_W, FRAME_H), (128, 128, 128))
    frame.paste(img.resize((w, FRAME_H), Image.BILINEAR), ((FRAME_W - w) // 2, 0))
    return np.asarray(frame)


def model_input(frame_rgb, in_w, in_h):
    """The frame as FaceCropper feeds it: scaled by fitScale, drawn top-left on
    black, as 1x3xHxW BGR 0..255 floats. Returns (input, scale, content w, h)."""
    import numpy as np
    from PIL import Image
    h, w = frame_rgb.shape[:2]
    scale = min(1.0, in_w / w, in_h / h)
    cw, ch = round(w * scale), round(h * scale)
    scaled = np.asarray(Image.fromarray(frame_rgb).resize((cw, ch), Image.BILINEAR))
    x = np.zeros((in_h, in_w, 3), np.float32)
    x[:ch, :cw] = scaled[:, :, ::-1]
    return np.ascontiguousarray(x.transpose(2, 0, 1)[None]), scale, cw, ch


def numpy_best_face(outputs, in_w):
    """The best-scoring cell over all strides, decoded as OpenCV's
    face_detect.cpp does: (score, [x0, y0, x1, y1], 5x2 landmarks) in input pixels."""
    import numpy as np
    best = None
    for s in STRIDES:
        cols = in_w // s
        sc = np.sqrt(np.clip(outputs[f"cls_{s}"][0, :, 0], 0, 1) * np.clip(outputs[f"obj_{s}"][0, :, 0], 0, 1))
        i = int(sc.argmax())
        if best is None or sc[i] > best[0]:
            r, c = divmod(i, cols)
            b = outputs[f"bbox_{s}"][0, i]
            k = outputs[f"kps_{s}"][0, i]
            cx, cy, bw, bh = (c + b[0]) * s, (r + b[1]) * s, np.exp(b[2]) * s, np.exp(b[3]) * s
            best = (float(sc[i]), [cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2],
                    np.array([[(k[2 * j] + c) * s, (k[2 * j + 1] + r) * s] for j in range(5)]))
    return best


def run_model(image_chw_bgr):
    """The real model's outputs for one 1x3xHxW BGR 0..255 image: {name: array}."""
    import onnxruntime as ort
    s = ort.InferenceSession(str(MODEL), providers=["CPUExecutionProvider"])
    names = [o.name for o in s.get_outputs()]
    return dict(zip(names, s.run(None, {s.get_inputs()[0].name: image_chw_bgr}))), s


def decode_with_java(outputs, in_w, in_h, out_dir, java_cp, java, content=None, scale=1.0):
    """Dumps the cls/obj/bbox/kps outputs as raw float32 and decodes them with
    the harness's --decode mode, as FaceCropper does: faces outside the content
    (w, h) are dropped and the rest scaled back by 1 / scale. Returns
    [(x0, y0, x1, y1, score, l0x, l0y, ..., l4x, l4y)] in frame pixels."""
    import numpy as np
    for kind in ("cls", "obj", "bbox", "kps"):
        for stride in STRIDES:
            np.asarray(outputs[f"{kind}_{stride}"], dtype="<f4").tofile(Path(out_dir) / f"{kind}_{stride}.bin")
    cw, ch = content if content else (in_w, in_h)
    r = subprocess.run([java, "-cp", java_cp, "com.miko3.mode.explore.YuNetHarness", "--decode", str(out_dir),
                        str(in_w), str(in_h), str(cw), str(ch), repr(float(scale))],
                       capture_output=True, text=True, timeout=60)
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
        # The 2026may model is dynamic (height and width read as names, not
        # numbers), so FaceCropper uses a fixed size rather than the model's.
        import numpy as np
        outputs, session = run_model(np.zeros((1, 3, IN_H, IN_W), dtype=np.float32))
        shape = session.get_inputs()[0].shape
        self.assertEqual(shape[:2], [1, 3])
        self.assertFalse(isinstance(shape[2], int) and shape[2] > 0, shape)
        for stride in STRIDES:
            cells = (IN_W // stride) * (IN_H // stride)
            self.assertEqual(outputs[f"cls_{stride}"].shape, (1, cells, 1))
            self.assertEqual(outputs[f"obj_{stride}"].shape, (1, cells, 1))
            self.assertEqual(outputs[f"bbox_{stride}"].shape, (1, cells, 4))
            self.assertEqual(outputs[f"kps_{stride}"].shape, (1, cells, 10))

    def test_a_blank_grey_frame_has_no_face(self):
        # R10: no detected face means no crop, so nothing is matched or stored.
        import numpy as np
        frame = np.full((FRAME_H, FRAME_W, 3), 128, dtype=np.uint8)
        image, scale, cw, ch = model_input(frame, IN_W, IN_H)
        outputs, _ = run_model(image)
        with tempfile.TemporaryDirectory(prefix="explore_yunet_real_") as out:
            java, ok, log = compile_harness(out)
            self.assertTrue(ok, log)
            faces = decode_with_java(outputs, IN_W, IN_H, out, out, java, (cw, ch), scale)
        self.assertEqual(faces, [])

    def _one_face_with_landmarks_inside(self, in_w, in_h):
        frame = portrait_frame("obama-2012.jpg")
        image, scale, cw, ch = model_input(frame, in_w, in_h)
        outputs, _ = run_model(image)
        with tempfile.TemporaryDirectory(prefix="explore_yunet_real_") as out:
            java, ok, log = compile_harness(out)
            self.assertTrue(ok, log)
            faces = decode_with_java(outputs, in_w, in_h, out, out, java, (cw, ch), scale)
        self.assertEqual(len(faces), 1, faces)
        x0, y0, x1, y1, score = faces[0][:5]
        points = [(faces[0][5 + 2 * j], faces[0][6 + 2 * j]) for j in range(5)]
        self.assertGreaterEqual(score, 0.6)
        for x, y in points:
            self.assertTrue(x0 <= x <= x1 and y0 <= y <= y1, (faces[0], points))
        # OpenCV's order: right eye, left eye, nose, right and left mouth corner --
        # the subject's right eye is the image-left one; the nose sits below the eyes.
        (rex, rey), (lex, ley), (nx, ny), (rmx, rmy), (lmx, lmy) = points
        self.assertLess(rex, lex)
        self.assertLess(rmx, lmx)
        self.assertGreater(ny, max(rey, ley))
        self.assertGreater(min(rmy, lmy), ny)
        # The Java decode agrees with a numpy mirror of OpenCV's face_detect.cpp.
        ref_score, ref_box, ref_points = numpy_best_face(outputs, in_w)
        self.assertAlmostEqual(score, ref_score, places=2)
        for got, want in zip((x0, y0, x1, y1), ref_box):
            self.assertAlmostEqual(got, want / scale, delta=0.2 / scale)
        for (gx, gy), (wx, wy) in zip(points, ref_points):
            self.assertAlmostEqual(gx, wx / scale, delta=0.2 / scale)
            self.assertAlmostEqual(gy, wy / scale, delta=0.2 / scale)
        return faces[0]

    def test_a_portrait_gives_one_face_with_landmarks_inside_at_320x256(self):
        self._one_face_with_landmarks_inside(IN_W, IN_H)

    def test_a_portrait_gives_one_face_with_landmarks_inside_at_640x480(self):
        self._one_face_with_landmarks_inside(640, 480)

    def test_320x240_is_not_a_usable_input_so_the_frame_is_padded(self):
        # KTD2: the model needs both sides a multiple of 32; 240 is not.
        import numpy as np
        with self.assertRaises(Exception):
            run_model(np.zeros((1, 3, 240, 320), dtype=np.float32))


if __name__ == "__main__":
    unittest.main()
