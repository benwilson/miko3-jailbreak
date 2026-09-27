#!/usr/bin/env python3
"""Host-side tests for face alignment and the quality gate (on-device face
recognition plan U2, KTD2, KTD3; R11, R12, R16).

FaceAlign fits a five-point similarity transform (least squares, uniform scale,
no reflection) onto the ArcFace 112x112 template and warps the frame with
bilinear sampling. FaceQuality rejects a face as too small, too dark or too
blurry, in that order, and brightens a dim one with one luma lookup table.

The JVM harness runs the behaviour scenarios. Two further checks hold the Java
to the Python the bench (U9) mirrors:
  - the luma, Laplacian variance and brighten table, against a pure-Python
    mirror (always runs), the table compared exactly;
  - the fit and the warp, against the numpy Umeyama and bilinear-warp
    reference (runs when numpy is importable, e.g. tools/face-bench/venv).
"""
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
HARNESS = TESTS / "fixtures" / "face_align_harness" / "src"
HARNESS_MAIN = HARNESS / "com" / "miko3" / "mode" / "explore" / "FaceAlignHarness.java"
PLAIN_JAVA = [PKG / "FaceAlign.java", PKG / "FaceQuality.java", PKG / "Brightness.java"]
FACES = TESTS / "fixtures" / "faces"
MAIN_CLASS = "com.miko3.mode.explore.FaceAlignHarness"

TEMPLATE = [(38.2946, 51.6963), (73.5318, 51.5014), (56.0252, 71.7366), (41.5493, 92.3655), (70.7299, 92.2041)]
GAMMA_TARGET = 110.0


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
                out = tempfile.mkdtemp(prefix="face_align_harness_")
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


class FaceAlignScenarioTest(unittest.TestCase):
    SCENARIOS = (
        "identity_landmarks_give_identity_and_same_crop",
        "rotated_and_scaled_landmarks_recovered",
        "mirrored_landmarks_give_no_reflection",
        "degenerate_landmarks_give_no_fit",
        "aligned_crop_is_112_square_rgb",
        "dark_crop_rejected_too_dark",
        "dim_crop_marked_dim_and_brightened_into_range",
        "blurred_face_under_floor_sharp_face_clears_it",
        "blur_measured_before_brightening",
        "narrow_face_rejected_too_small_before_other_checks",
        "dark_is_checked_before_blurry",
        "bright_crop_unchanged_by_brighten",
        "stored_loose_crop_gets_the_same_table",
        "migration_brightens_dim_but_never_rejects",
        "thresholds_are_the_ones_given",
    )

    @classmethod
    def setUpClass(cls):
        cls.results, cls.run_output = {}, ""
        ok, _, _, cls.compile_output = Harness.get()
        cls.compiled = ok
        if ok:
            stdout = Harness.run("--faces", FACES)
            cls.run_output = stdout[-6000:]
            cls.results = jvm_harness.parse_verdicts(stdout)

    def setUp(self):
        self.assertTrue(self.compiled, f"harness failed to compile:\n{self.compile_output}")

    def _assert_pass(self, name):
        verdict, detail = self.results.get(name, ("MISSING", self.run_output))
        self.assertEqual(verdict, "PASS", detail)

    def test_harness_ran_exactly_the_listed_scenarios(self):
        self.assertEqual(sorted(self.results), sorted(self.SCENARIOS), self.run_output)


jvm_harness.add_scenario_tests(FaceAlignScenarioTest)


class PlainJavaTest(unittest.TestCase):
    def test_no_android_imports(self):
        for name in ("FaceAlign.java", "FaceQuality.java"):
            text = (PKG / name).read_text()
            self.assertNotIn("import android", text, name)


# ---- pure-Python mirror of FaceQuality (what the bench's mirror must match) ----

def luma_weights(p):
    return 77 * ((p >> 16) & 0xFF) + 150 * ((p >> 8) & 0xFF) + 29 * (p & 0xFF)


def mean_luma(argb):
    return sum(luma_weights(p) for p in argb) / 256.0 / len(argb)


def laplacian_variance(argb, w, h):
    g = [luma_weights(p) / 256.0 for p in argb]
    vals = []
    for y in range(1, h - 1):
        for x in range(1, w - 1):
            i = y * w + x
            vals.append(g[i - w] + g[i + w] + g[i - 1] + g[i + 1] - 4 * g[i])
    m = sum(vals) / len(vals)
    return sum((v - m) ** 2 for v in vals) / len(vals)


def brighten_table(argb):
    mean = mean_luma(argb)
    if mean <= 0:
        gamma = 0.4
    elif mean >= 255:
        gamma = 1.0
    else:
        gamma = min(1.0, max(0.4, math.log(GAMMA_TARGET / 255) / math.log(mean / 255)))
    lum = sorted(luma_weights(p) >> 8 for p in argb)
    n = len(lum)
    lo, hi = lum[int(0.01 * (n - 1))], lum[int(0.99 * (n - 1))]

    def g(v):
        return 255.0 * math.pow(v / 255.0, gamma)

    glo, ghi = g(lo), g(hi)
    table = []
    for v in range(256):
        x = (g(v) - glo) * 255.0 / (ghi - glo) if ghi > glo else g(v)
        table.append(max(0, min(255, int(math.floor(x + 0.5)))))
    return table


def write_argb(path, argb):
    Path(path).write_bytes(struct.pack(f"<{len(argb)}i", *[p - (1 << 32) if p >= 1 << 31 else p for p in argb]))


def dim_image(w, h, seed, mean=62, amp=70):
    rnd = random.Random(seed)
    out = []
    for _ in range(w * h):
        base = mean + int(round((rnd.random() - 0.5) * amp))
        r, g, b = (max(0, min(255, base + rnd.randint(-12, 12))) for _ in range(3))
        out.append(0xFF000000 | (r << 16) | (g << 8) | b)
    return out


class QualityMirrorTest(unittest.TestCase):
    """Java's luma, Laplacian variance and table equal the pure-Python mirror."""

    def _quality(self, argb, w, h):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "in.raw"
            write_argb(p, argb)
            out = Harness.run("--quality", p, w, h)
        lines = {line.split(" ", 1)[0]: line.split(" ", 1)[1] for line in out.splitlines()}
        return float(lines["LUMA"]), float(lines["LAPVAR"]), [int(v) for v in lines["TABLE"].split()]

    def test_dim_crops_match_the_mirror_exactly(self):
        for seed, (w, h) in enumerate([(112, 112), (224, 224), (40, 30)]):
            argb = dim_image(w, h, seed, mean=40 + 15 * seed)
            luma, lap, table = self._quality(argb, w, h)
            self.assertAlmostEqual(luma, mean_luma(argb), places=9)
            self.assertAlmostEqual(lap, laplacian_variance(argb, w, h), delta=1e-9 * max(1, lap))
            self.assertEqual(table, brighten_table(argb), f"seed {seed}")

    def test_table_raises_a_dim_crop_and_is_monotonic(self):
        argb = dim_image(112, 112, 42)
        _, _, table = self._quality(argb, 112, 112)
        self.assertEqual(table, sorted(table))
        self.assertGreater(table[62], 62)

    def test_flat_crop_has_zero_blur_and_no_stretch(self):
        argb = [0xFF3C3C3C] * (50 * 50)
        luma, lap, table = self._quality(argb, 50, 50)
        self.assertEqual(lap, 0.0)
        self.assertEqual(table, brighten_table(argb))


class NumpyParityTest(unittest.TestCase):
    """Java's fit and warp against the numpy Umeyama/bilinear reference."""

    @classmethod
    def setUpClass(cls):
        try:
            import numpy  # noqa: F401
        except ImportError:
            raise unittest.SkipTest("numpy not installed (run with tools/face-bench/venv/bin/python)")

    @staticmethod
    def umeyama(src, dst):
        import numpy as np
        mu_s, mu_d = src.mean(0), dst.mean(0)
        sc, dc = src - mu_s, dst - mu_d
        cov = dc.T @ sc / len(src)
        U, D, Vt = np.linalg.svd(cov)
        d = np.ones(2)
        if np.linalg.det(cov) < 0:
            d[1] = -1
        R = U @ np.diag(d) @ Vt
        s = (D * d).sum() / sc.var(0).sum()
        return s * R, mu_d - s * R @ mu_s

    @staticmethod
    def warp(rgb, A, t):
        import numpy as np
        Ai = np.linalg.inv(A)
        ys, xs = np.mgrid[0:112, 0:112]
        q = (np.stack([xs.ravel(), ys.ravel()], 1) - t) @ Ai.T
        x, y = q[:, 0], q[:, 1]
        x0 = np.clip(np.floor(x).astype(int), 0, rgb.shape[1] - 2)
        y0 = np.clip(np.floor(y).astype(int), 0, rgb.shape[0] - 2)
        fx, fy = (x - x0)[:, None], (y - y0)[:, None]
        I = rgb.astype(np.float64)
        v = (I[y0, x0] * (1 - fx) * (1 - fy) + I[y0, x0 + 1] * fx * (1 - fy)
             + I[y0 + 1, x0] * (1 - fx) * fy + I[y0 + 1, x0 + 1] * fx * fy)
        return v.reshape(112, 112, 3)

    def _fit(self, lm):
        import numpy as np
        out = Harness.run("--fit", *[repr(float(v)) for v in np.asarray(lm, dtype=np.float32).ravel()])
        vals = out.split()[1:]
        return None if vals == ["null"] else np.array([float(v) for v in vals]).reshape(2, 3)

    def test_fit_matches_umeyama(self):
        import numpy as np
        T = np.array(TEMPLATE)
        rng = np.random.default_rng(3)
        cases = []
        for ang, s in ((0, 1), (15, 2), (-40, 0.7), (170, 3.1)):
            a = math.radians(ang)
            R = np.array([[math.cos(a), -math.sin(a)], [math.sin(a), math.cos(a)]])
            cases.append((T @ R.T) * s + np.array([120, 80]) + rng.normal(0, 1.5, (5, 2)))
        cases.append(np.array([[200 - x, y] for x, y in TEMPLATE]))  # mirrored: guard must hold
        cases.append(np.array([[216.0, 107.8], [274.9, 107.7], [244.5, 139.0], [217.8, 162.5], [273.7, 162.8]]))
        for lm in cases:
            lm32 = lm.astype(np.float32).astype(np.float64)
            A, t = self.umeyama(lm32, T)
            m = self._fit(lm32)
            self.assertIsNotNone(m)
            np.testing.assert_allclose(m[:, :2], A, atol=1e-6)
            np.testing.assert_allclose(m[:, 2], t, atol=1e-4)
            self.assertGreater(np.linalg.det(m[:, :2]), 0)

    def test_warp_matches_the_numpy_bilinear_warp(self):
        import numpy as np
        rng = np.random.default_rng(5)
        h, w = 150, 130
        rgb = rng.integers(0, 256, (h, w, 3))
        argb = [int(0xFF000000 | (r << 16) | (g << 8) | b) for r, g, b in rgb.reshape(-1, 3).tolist()]
        lm = np.array([[50.0, 60.0], [85.0, 58.0], [68.0, 80.0], [53.0, 100.0], [84.0, 99.0]])
        m = self._fit(lm)
        # plus a transform that samples beyond the frame edge
        for mat in (m, np.array([[0.4, -0.3, 30.0], [0.3, 0.4, -10.0]])):
            with tempfile.TemporaryDirectory() as d:
                src, dst = Path(d) / "in.raw", Path(d) / "out.raw"
                write_argb(src, argb)
                Harness.run("--warp", src, w, h, *[repr(float(v)) for v in mat.ravel()], dst)
                got = np.frombuffer(dst.read_bytes(), dtype="<f4").reshape(112, 112, 3)
            want = self.warp(rgb, mat[:, :2], mat[:, 2])
            np.testing.assert_allclose(got, want, atol=1e-2)


if __name__ == "__main__":
    unittest.main()
