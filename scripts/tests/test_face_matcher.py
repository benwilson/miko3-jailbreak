#!/usr/bin/env python3
"""Host-side tests for the on-device face matcher (on-device face recognition
plan U3; R1, R2, R3, R17, R18; KTD1, KTD5).

FaceMatcher is plain Java: a probe embedding against every stored photo of
every person, each person scored by their best photo, three bands with
inclusive lower edges, R17's near-tie demotion and R18's not-ready rule.
FaceEmbedder runs the SFace model on the robot and cannot run here, so:

  - the JVM harness runs the matcher scenarios;
  - a pure-Python mirror of the band rule is checked against the Java on
    random galleries (always runs with a JDK);
  - the model file's SHA-256 and its Apache-2.0 licence are pinned;
  - with Python onnxruntime, numpy and pillow (tools/face-bench/venv/bin/python)
    the real SFace model embeds the public-domain fixtures from input tensors
    the JAVA FaceAlign + FaceMatcher.input() produced, landmarks from the
    shipped YuNet: the same person scores above 0.5, different people below 0.3.
"""
import hashlib
import math
import random
import struct
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
ASSETS = REPO / "mode-explore" / "assets"
HARNESS = TESTS / "fixtures" / "face_matcher_harness" / "src"
HARNESS_MAIN = HARNESS / "com" / "miko3" / "mode" / "explore" / "FaceMatcherHarness.java"
PLAIN_JAVA = [PKG / "FaceMatcher.java", PKG / "FaceAlign.java"]
FACES = TESTS / "fixtures" / "faces"
MAIN_CLASS = "com.miko3.mode.explore.FaceMatcherHarness"

MODEL = ASSETS / "face_sface.onnx"
MODEL_SHA256 = "0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79"
MODEL_LICENSE = ASSETS / "face_sface-LICENSE.txt"
YUNET = ASSETS / "face_yunet.onnx"


class Harness:
    """Compiles the harness once per process; skips without a JDK."""
    _state = None

    @classmethod
    def get(cls):
        if cls._state is None:
            jdk = jvm_harness.find_jdk()
            if jdk is None:
                cls._state = ("skip", None, None, None)
            else:
                out = tempfile.mkdtemp(prefix="face_matcher_harness_")
                c = subprocess.run(jvm_harness.javac_cmd(jdk[0], out, [HARNESS_MAIN] + PLAIN_JAVA, [HARNESS]),
                                   capture_output=True, text=True)
                cls._state = ("ok" if c.returncode == 0 else "fail", jdk[1], out, (c.stdout + c.stderr)[-3000:])
        state, java, out, log = cls._state
        if state == "skip":
            raise unittest.SkipTest("no JDK (javac + java) found")
        return state == "ok", java, out, log

    @classmethod
    def run(cls, *args):
        ok, java, out, log = cls.get()
        if not ok:
            raise AssertionError(f"harness failed to compile:\n{log}")
        r = subprocess.run([java, "-cp", out, MAIN_CLASS] + [str(a) for a in args],
                           capture_output=True, text=True, timeout=120)
        if r.returncode != 0:
            raise AssertionError(r.stdout + r.stderr)
        return r.stdout


class FaceMatcherScenarioTest(unittest.TestCase):
    SCENARIOS = (
        "probe_equal_to_stored_scores_one_and_is_confident",
        "score_at_close_threshold_is_close_just_below_is_weak",
        "score_at_confident_threshold_is_confident_just_below_is_close",
        "person_scored_by_best_photo_not_average",
        "near_tie_demotes_confident_to_close_naming_the_higher",
        "two_photos_of_one_person_at_the_top_do_not_trigger_margin",
        "gap_of_at_least_the_margin_stays_confident",
        "near_tie_only_touches_a_confident_result",
        "runner_up_is_the_best_other_person",
        "empty_gallery_is_weak_with_no_best_id",
        "not_ready_whatever_the_scores",
        "unusable_entries_are_skipped",
        "gallery_order_does_not_change_the_answer",
        "thresholds_are_the_ones_given",
        "defaults_and_model_id",
        "normalize_gives_unit_length_and_refuses_garbage",
        "input_is_rgb_planes_of_raw_0_255",
        "inputs_are_not_modified",
    )

    @classmethod
    def setUpClass(cls):
        cls.results, cls.run_output = {}, ""
        ok, _, _, cls.compile_output = Harness.get()
        cls.compiled = ok
        if ok:
            stdout = Harness.run()
            cls.run_output = stdout[-6000:]
            cls.results = jvm_harness.parse_verdicts(stdout)

    def setUp(self):
        self.assertTrue(self.compiled, f"harness failed to compile:\n{self.compile_output}")

    def _assert_pass(self, name):
        verdict, detail = self.results.get(name, ("MISSING", self.run_output))
        self.assertEqual(verdict, "PASS", detail)

    def test_harness_ran_exactly_the_listed_scenarios(self):
        self.assertEqual(sorted(self.results), sorted(self.SCENARIOS), self.run_output)


jvm_harness.add_scenario_tests(FaceMatcherScenarioTest)


class SourceTest(unittest.TestCase):
    """What can be held without Android: plain Java, the model id, the runtime pattern."""

    def test_matcher_is_plain_java(self):
        text = (PKG / "FaceMatcher.java").read_text()
        self.assertNotIn("import android", text)
        self.assertNotIn("import ai.onnxruntime", text)

    def test_embedder_loads_the_model_from_a_file_path_not_a_byte_array(self):
        # Model load memory (plan Risks): ~38 MB must never sit on the Java heap.
        text = (PKG / "FaceEmbedder.java").read_text()
        self.assertNotIn("readAssetBytes", text)
        self.assertNotIn("readAllBytes", text)
        self.assertRegex(text, r"createSession\(\s*\w+\.getAbsolutePath\(\)|createSession\(\s*path\b")
        self.assertIn('"face_sface.onnx"', text)
        self.assertIn("FaceMatcher.MODEL_ID", text)

    def test_embedder_follows_the_face_cropper_runtime_pattern(self):
        text = (PKG / "FaceEmbedder.java").read_text()
        self.assertIn("setIntraOpNumThreads(THREADS)", text)
        self.assertRegex(text, r"THREADS\s*=\s*2;")
        self.assertIn("OptLevel.ALL_OPT", text)
        self.assertIn("synchronized void close()", text)
        self.assertIn("FaceMatcher.normalize(", text)
        self.assertIn("FaceMatcher.DIM", text)


# ---- pure-Python mirror of FaceMatcher.match ----

def f32(x):
    return struct.unpack("<f", struct.pack("<f", x))[0]


def mirror(probe, gallery, conf, close, margin, ready):
    """(band, best id, best slot, score, runner-up id, runner-up score, near tie).
    Every value float32, dot products summed in double in index order, as the Java."""
    best = {}  # id -> (score, slot)
    for pid, slot, emb in gallery:
        if pid is None or emb is None or len(emb) != len(probe):
            continue
        s = 0.0
        for a, b in zip(probe, emb):
            s += a * b
        if not math.isfinite(s):
            continue
        s = f32(s)
        cur = best.get(pid)
        if cur is None or s > cur[0] or (s == cur[0] and slot < cur[1]):
            best[pid] = (s, slot)
    ranked = sorted(best.items(), key=lambda kv: (-kv[1][0], kv[0]))
    if not ranked:
        top = (None, -1, float("nan"))
        run = (None, float("nan"))
    else:
        top = (ranked[0][0], ranked[0][1][1], ranked[0][1][0])
        run = (ranked[1][0], ranked[1][1][0]) if len(ranked) > 1 else (None, float("nan"))
    near = False
    if not ready:
        band = "NOT_READY"
    elif top[0] is None:
        band = "WEAK"
    elif top[2] >= conf:
        band = "CONFIDENT"
        if run[0] is not None and f32(top[2] - run[1]) < margin:
            band, near = "CLOSE", True
    elif top[2] >= close:
        band = "CLOSE"
    else:
        band = "WEAK"
    return band, top[0], top[1], top[2], run[0], run[1], near


def rand_unit(rnd, n, levels=None):
    while True:
        v = [rnd.gauss(0, 1) for _ in range(n)]
        if levels:
            v = [round(x * levels) / levels for x in v]
        norm = math.sqrt(sum(x * x for x in v))
        if norm > 0:
            return [f32(x / norm) for x in v]


class MatchMirrorTest(unittest.TestCase):
    """Java's match equals the mirror on random galleries, ties and edges included."""

    def test_random_galleries_match_the_mirror(self):
        rnd = random.Random(7)
        cases = []
        for i in range(150):
            dim = rnd.choice([3, 4, 6])
            levels = 2 if i % 3 == 0 else None  # coarse vectors make exact ties
            probe = rand_unit(rnd, dim, levels)
            gallery = []
            for _ in range(rnd.randint(0, 9)):
                pid = rnd.choice(["p1", "p2", "p3", "p4"])
                slot = rnd.randint(0, 4)
                emb = None if rnd.random() < 0.08 else rand_unit(rnd, dim, levels)
                gallery.append((pid, slot, emb))
            if i % 10 == 0 and gallery:
                gallery.append(("dup", 0, list(probe)))
                gallery.append(("dup2", 1, list(probe)))
            close = f32(rnd.uniform(0, 0.6))
            conf = f32(rnd.uniform(close, 0.9))
            margin = f32(rnd.choice([0.0, 0.05, 0.2, 0.3]))
            cases.append((probe, gallery, conf, close, margin, rnd.random() > 0.1))
        with tempfile.TemporaryDirectory() as d:
            for n, (probe, gallery, conf, close, margin, ready) in enumerate(cases):
                p = Path(d) / f"case{n}.txt"
                lines = [f"{conf!r} {close!r} {margin!r} {'true' if ready else 'false'}",
                         "probe " + " ".join(repr(x) for x in probe)]
                for pid, slot, emb in gallery:
                    lines.append(f"{pid} {slot} " + ("null" if emb is None else " ".join(repr(x) for x in emb)))
                p.write_text("\n".join(lines) + "\n")
                out = Harness.run("--match", p).split()
                self.assertEqual(out[0], "MATCH")
                band, bid, bslot, score, rid, rscore, near = out[1:]
                want = mirror(probe, gallery, conf, close, margin, ready)
                got = (band, None if bid == "-" else bid, int(bslot), f32(float(score)),
                       None if rid == "-" else rid, f32(float(rscore)), near == "true")
                msg = f"case {n}: {got} vs {want}"
                self.assertEqual(got[:3], want[:3], msg)
                self.assertEqual(got[4], want[4], msg)
                self.assertEqual(got[6], want[6], msg)
                for g, w in ((got[3], want[3]), (got[5], want[5])):
                    if math.isnan(w):
                        self.assertTrue(math.isnan(g), msg)
                    else:
                        self.assertEqual(g, w, msg)


class PinnedModelTest(unittest.TestCase):
    def test_the_model_is_the_pinned_sface_2021dec_file(self):
        self.assertEqual(hashlib.sha256(MODEL.read_bytes()).hexdigest(), MODEL_SHA256)

    def test_the_apache_licence_ships_beside_it(self):
        text = MODEL_LICENSE.read_text()
        self.assertIn("Apache License", text)
        self.assertIn("Version 2.0, January 2004", text)


# ---- the real model (needs onnxruntime, numpy and pillow) ----

TEMPLATE = [(38.2946, 51.6963), (73.5318, 51.5014), (56.0252, 71.7366), (41.5493, 92.3655), (70.7299, 92.2041)]


def _session(path):
    import onnxruntime as ort
    so = ort.SessionOptions()
    so.log_severity_level = 3  # SFace lists its initializers as graph inputs: harmless warnings
    return ort.InferenceSession(str(path), so, providers=["CPUExecutionProvider"])


def yunet_landmarks(yunet, rgb):
    """The best face's five landmarks, in image pixels, at the image's own size
    padded to a multiple of 32 (as the Python reference does)."""
    import numpy as np
    h, w = rgb.shape[:2]
    H, W = (h + 31) // 32 * 32, (w + 31) // 32 * 32
    x = np.zeros((H, W, 3), np.float32)
    x[:h, :w] = rgb[:, :, ::-1]
    names = [o.name for o in yunet.get_outputs()]
    out = dict(zip(names, yunet.run(None, {yunet.get_inputs()[0].name: np.ascontiguousarray(x.transpose(2, 0, 1)[None])})))
    best = None
    for s in (8, 16, 32):
        cols = W // s
        sc = np.sqrt(np.clip(out[f"cls_{s}"][0, :, 0], 0, 1) * np.clip(out[f"obj_{s}"][0, :, 0], 0, 1))
        i = int(sc.argmax())
        if best is None or sc[i] > best[0]:
            r, c = divmod(i, cols)
            k = out[f"kps_{s}"][0, i]
            best = (sc[i], np.array([[(k[2 * j] + c) * s, (k[2 * j + 1] + r) * s] for j in range(5)]))
    return best


def numpy_input(rgb, lm):
    """The numpy reference pipeline's model input: Umeyama onto the template,
    bilinear warp, RGB NCHW floats."""
    import numpy as np
    T = np.array(TEMPLATE)
    mu_s, mu_d = lm.mean(0), T.mean(0)
    sc, dc = lm - mu_s, T - mu_d
    cov = dc.T @ sc / len(lm)
    U, D, Vt = np.linalg.svd(cov)
    d = np.ones(2)
    if np.linalg.det(cov) < 0:
        d[1] = -1
    R = U @ np.diag(d) @ Vt
    s = (D * d).sum() / sc.var(0).sum()
    A, t = s * R, mu_d - s * R @ mu_s
    Ai = np.linalg.inv(A)
    ys, xs = np.mgrid[0:112, 0:112]
    q = (np.stack([xs.ravel(), ys.ravel()], 1) - t) @ Ai.T
    x, y = q[:, 0], q[:, 1]
    x0 = np.clip(np.floor(x).astype(int), 0, rgb.shape[1] - 2)
    y0 = np.clip(np.floor(y).astype(int), 0, rgb.shape[0] - 2)
    fx, fy = (x - x0)[:, None], (y - y0)[:, None]
    I = rgb.astype(np.float32)
    v = (I[y0, x0] * (1 - fx) * (1 - fy) + I[y0, x0 + 1] * fx * (1 - fy)
         + I[y0 + 1, x0] * (1 - fx) * fy + I[y0 + 1, x0 + 1] * fx * fy)
    return np.ascontiguousarray(v.reshape(112, 112, 3).transpose(2, 0, 1)[None], dtype=np.float32)


class RealModelTest(unittest.TestCase):
    """The shipped SFace on Java-aligned public-domain crops; skipped without
    Python onnxruntime/numpy/pillow (run with tools/face-bench/venv/bin/python)."""

    @classmethod
    def setUpClass(cls):
        try:
            import numpy  # noqa: F401
            import onnxruntime  # noqa: F401
            from PIL import Image  # noqa: F401
        except ImportError:
            raise unittest.SkipTest("onnxruntime/numpy/pillow not installed (use tools/face-bench/venv/bin/python)")
        import numpy as np
        from PIL import Image
        cls.sface = _session(MODEL)
        yunet = _session(YUNET)
        cls.java, cls.ref = {}, {}
        with tempfile.TemporaryDirectory() as d:
            for name in ("obama-2012.jpg", "obama-2009.jpg", "biden-2021.jpg"):
                rgb = np.asarray(Image.open(FACES / name).convert("RGB"))
                score, lm = yunet_landmarks(yunet, rgb)
                assert score > 0.6, f"YuNet found no face in {name} ({score})"
                out = Path(d) / (name + ".f32")
                Harness.run("--input", FACES / name, *[repr(float(v)) for v in lm.ravel()], out)
                tensor = np.frombuffer(out.read_bytes(), dtype="<f4").reshape(1, 3, 112, 112)
                cls.java[name] = cls.embed(tensor)
                cls.ref[name] = cls.embed(numpy_input(rgb, lm))

    @classmethod
    def embed(cls, tensor):
        import numpy as np
        e = cls.sface.run(None, {"data": tensor})[0][0].astype(np.float64)
        return e / np.linalg.norm(e)

    def test_io_is_data_1x3x112x112_to_fc1_1x128(self):
        (i,), (o,) = self.sface.get_inputs(), self.sface.get_outputs()
        self.assertEqual((i.name, list(i.shape)), ("data", [1, 3, 112, 112]))
        self.assertEqual((o.name, list(o.shape)), ("fc1", [1, 128]))

    def test_same_person_scores_above_one_half(self):
        s = float(self.java["obama-2012.jpg"] @ self.java["obama-2009.jpg"])
        self.assertGreater(s, 0.5)

    def test_different_people_score_below_0_3(self):
        for name in ("obama-2012.jpg", "obama-2009.jpg"):
            s = float(self.java[name] @ self.java["biden-2021.jpg"])
            self.assertLess(s, 0.3, name)

    def test_java_alignment_embeds_like_the_numpy_reference(self):
        for name in self.java:
            self.assertGreater(float(self.java[name] @ self.ref[name]), 0.99, name)


if __name__ == "__main__":
    unittest.main()
