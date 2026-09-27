#!/usr/bin/env python3
"""Tests for scripts/face-bench.py (on-device face recognition plan, U9).

The threshold logic, the refusal of a one-person folder and the printed
`robot-faces.py thresholds` command run under plain python3. The parity checks
compare the bench's numpy mirror with the Java it mirrors (FaceAlign,
FaceQuality): they need a JDK and numpy, so run them under the bench's venv:

  tools/face-bench/venv/bin/python scripts/tests/test_face_bench.py

The real-model check also needs onnxruntime and the pinned models. None of
this needs the robot.
"""
import importlib.util
import io
import math
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest import mock

TESTS = Path(__file__).resolve().parent
sys.path.insert(0, str(TESTS))
import jvm_harness  # noqa: E402

REPO = TESTS.parents[1]
SCRIPT = REPO / "scripts" / "face-bench.py"
PKG = REPO / "mode-explore" / "src" / "com" / "miko3" / "mode" / "explore"
HARNESS = TESTS / "fixtures" / "face_bench_harness" / "src"
HARNESS_MAIN = HARNESS / "com" / "miko3" / "mode" / "explore" / "FaceBenchHarness.java"
PLAIN_JAVA = [PKG / "FaceAlign.java", PKG / "FaceQuality.java", PKG / "Brightness.java"]
MAIN_CLASS = "com.miko3.mode.explore.FaceBenchHarness"
FACES = TESTS / "fixtures" / "faces"


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load {path}")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


fb = load("face_bench", SCRIPT)


def unit(angle_deg):
    """A 2-D unit vector; two of them score cos(angle between)."""
    a = math.radians(angle_deg)
    return [math.cos(a), math.sin(a)]


def people_at(**angles):
    """{label: [unit vectors]} from {label: [angles]}."""
    return {label: [unit(a) for a in list_] for label, list_ in angles.items()}


class ThresholdSuggestionTest(unittest.TestCase):
    def test_confident_above_highest_impostor_and_close_at_or_below_lowest_genuine(self):
        # AB: genuine 10 deg apart (0.985); AB vs CD: 70 deg or more (at most 0.342).
        a = fb.analyse_scores(people_at(AB=[0, 10, 5], CD=[80, 90, 85]))
        self.assertFalse(a["stop"], a["stop_reasons"])
        s = a["suggested"]
        self.assertGreater(s["confident"], a["impostor_max"])
        self.assertLessEqual(s["close"], a["genuine_min"])
        self.assertLessEqual(s["close"], s["confident"])
        self.assertTrue(0 <= s["margin"] <= 0.3)
        self.assertAlmostEqual(a["impostor_max"], math.cos(math.radians(70)), places=5)
        self.assertAlmostEqual(a["genuine_min"], math.cos(math.radians(10)), places=5)
        for person in a["held_out"].values():
            self.assertEqual(person["confident"], 0)

    def test_overlapping_genuine_and_impostor_scores_stop_instead_of_suggesting(self):
        # Genuine pairs 60 deg apart (0.5); impostors as near as 20 deg (0.94).
        a = fb.analyse_scores(people_at(AB=[0, 60], CD=[20, 80]))
        self.assertTrue(a["stop"])
        self.assertIsNone(a["suggested"])
        text = fb.render_summary(a, gates=None, detection=None)
        self.assertIn("STOP CONDITION 2", text)
        self.assertNotIn("robot-faces.py thresholds", text)

    def test_held_out_person_reaching_the_given_confident_value_stops(self):
        # Evaluating the default 0.50: EF's captures score cos(50 deg) = 0.64 against AB.
        a = fb.analyse_scores(people_at(AB=[0, 5], CD=[120, 125], EF=[50, 52]),
                              given={"confident": 0.50, "close": 0.363, "margin": 0.05})
        self.assertTrue(a["stop"])
        self.assertGreater(a["held_out"]["EF"]["reached_confident"], 0)
        self.assertTrue(any("held-out" in r for r in a["stop_reasons"]), a["stop_reasons"])

    def test_held_out_person_at_a_near_identical_score_stops_the_suggestion(self):
        # An impostor at 0.995: confident cannot sit above it and stay at most 1.
        a = fb.analyse_scores(people_at(AB=[0, 1], CD=[5.7, 6]))
        self.assertTrue(a["stop"])
        self.assertIsNone(a["suggested"])

    def test_most_genuine_captures_must_reach_confident(self):
        # Two tight people plus one whose own captures are 75 deg apart (0.26 < impostor max).
        a = fb.analyse_scores(people_at(AB=[0, 2], CD=[120, 195]))
        self.assertTrue(a["stop"])
        self.assertTrue(any("genuine" in r for r in a["stop_reasons"]), a["stop_reasons"])

    def test_no_person_with_two_captures_stops(self):
        a = fb.analyse_scores(people_at(AB=[0], CD=[90]))
        self.assertTrue(a["stop"])

    def test_band_mirrors_face_matcher_inclusive_edges_and_strict_margin(self):
        t = {"confident": 0.5, "close": 0.4, "margin": 0.05}
        self.assertEqual(fb.band(0.5, None, t), "confident")
        self.assertEqual(fb.band(0.4, None, t), "close")
        self.assertEqual(fb.band(0.39, None, t), "weak")
        self.assertEqual(fb.band(0.6, 0.56, t), "close")        # 0.04 < margin: demoted
        self.assertEqual(fb.band(0.625, 0.5625, t), "confident")  # 0.0625 >= margin: kept


class GateSuggestionTest(unittest.TestCase):
    def test_gates_only_loosen_from_the_defaults(self):
        faces = [{"width": 120.0, "luma": 130.0, "blur": 400.0, "full_frame": True}] * 40
        g = fb.suggest_gates(faces)
        self.assertEqual((g["min_width"], g["dark_floor"], g["dim_level"], g["blur_floor"]), (48, 40, 90, 30))

    def test_gates_follow_low_captures_down(self):
        faces = [{"width": 30.0 + i, "luma": 20.0 + i, "blur": 5.0 + i, "full_frame": True} for i in range(100)]
        g = fb.suggest_gates(faces)
        self.assertEqual(g["min_width"], 34)
        self.assertEqual(g["dark_floor"], 24)
        self.assertLess(g["blur_floor"], 30)
        self.assertLess(g["dark_floor"], g["dim_level"])
        self.assertGreaterEqual(g["min_width"], 16)


class CommandTest(unittest.TestCase):
    def test_printed_command_uses_the_u8_flag_names(self):
        cmd = fb.thresholds_command({"confident": 0.61, "close": 0.4, "margin": 0.05},
                                    {"min_width": 44, "dark_floor": 35, "dim_level": 90, "blur_floor": 22})
        self.assertTrue(cmd.startswith("python3 scripts/robot-faces.py thresholds "), cmd)
        argv = cmd.split()[3:]
        flags = argv[0::2]
        self.assertEqual(flags, ["--confident", "--close", "--margin", "--min-width", "--dark-floor",
                                 "--dim-level", "--blur-floor"])
        self.assertEqual(argv[1::2], ["0.61", "0.4", "0.05", "44", "35", "90", "22"])

    def test_summary_has_the_command_labels_and_no_file_names(self):
        a = fb.analyse_scores(people_at(AB=[0, 10, 5], CD=[80, 90, 85]))
        gates = {"min_width": 48, "dark_floor": 40, "dim_level": 90, "blur_floor": 30}
        text = fb.render_summary(a, gates=gates, detection=None)
        self.assertIn("robot-faces.py thresholds --confident", text)
        self.assertIn("AB", text)
        self.assertIn("face-bench.py clean", text)


class PeopleFolderTest(unittest.TestCase):
    def _tree(self, d, spec):
        for person, files in spec.items():
            (Path(d) / person).mkdir()
            for f in files:
                (Path(d) / person / f).write_bytes(b"")

    def test_fewer_than_two_people_is_refused_with_a_clear_message(self):
        with tempfile.TemporaryDirectory() as d:
            self._tree(d, {"AB": ["1.jpg", "2.jpg"], "CD": [], "_unsorted": ["3.jpg"]})
            with self.assertRaises(fb.BenchError) as e:
                fb.require_people(fb.list_people(Path(d)))
            self.assertIn("two people", str(e.exception))
            err = io.StringIO()
            with mock.patch("sys.stderr", err), redirect_stdout(io.StringIO()):
                code = fb.main(["run", d])
            self.assertNotEqual(code, 0)
            self.assertIn("two people", err.getvalue())

    def test_two_people_accepted_and_underscore_folders_skipped(self):
        with tempfile.TemporaryDirectory() as d:
            self._tree(d, {"AB": ["1.jpg"], "CD": ["2.png", "notes.txt"], "_unsorted": ["3.jpg"]})
            people = fb.require_people(fb.list_people(Path(d)))
            self.assertEqual(sorted(people), ["AB", "CD"])
            self.assertEqual([p.name for p in people["CD"]], ["2.png"])

    def test_clean_deletes_the_captures(self):
        with tempfile.TemporaryDirectory() as d:
            captures = Path(d) / "captures"
            (captures / "AB").mkdir(parents=True)
            (captures / "AB" / "1.jpg").write_bytes(b"x")
            with mock.patch.object(fb, "CAPTURES", captures), redirect_stdout(io.StringIO()):
                self.assertEqual(fb.main(["clean"]), 0)
            self.assertFalse(captures.exists())


class Harness:
    """Compiles the bench harness once per process; skips without a JDK."""
    _state = None

    @classmethod
    def run(cls, *args):
        if cls._state is None:
            jdk = jvm_harness.find_jdk()
            if jdk is None:
                cls._state = ("skip", None, None, None)
            else:
                out = tempfile.mkdtemp(prefix="face_bench_harness_")
                c = subprocess.run(jvm_harness.javac_cmd(jdk[0], out, [HARNESS_MAIN] + PLAIN_JAVA, [HARNESS]),
                                   capture_output=True, text=True)
                cls._state = ("ok" if c.returncode == 0 else "fail", jdk[1], out, (c.stdout + c.stderr)[-3000:])
        state, java, out, log = cls._state
        if state == "skip":
            raise unittest.SkipTest("no JDK (javac + java) found")
        if state != "ok":
            raise AssertionError(f"harness failed to compile:\n{log}")
        r = subprocess.run([java, "-cp", out, MAIN_CLASS] + [str(a) for a in args],
                           capture_output=True, text=True, timeout=120)
        if r.returncode != 0:
            raise AssertionError(r.stdout + r.stderr)
        return r.stdout


def need_numpy():
    try:
        import numpy  # noqa: F401
    except ImportError:
        raise unittest.SkipTest("numpy not installed (run with tools/face-bench/venv/bin/python)")


def write_argb(path, rgb):
    import numpy as np
    r, g, b = (rgb[..., i].astype(np.uint32) for i in range(3))
    argb = (np.uint32(0xFF000000) | (r << 16) | (g << 8) | b).astype("<u4")
    Path(path).write_bytes(argb.tobytes())


class JavaParityTest(unittest.TestCase):
    """The numpy mirror against FaceAlign and FaceQuality on the same inputs."""

    def setUp(self):
        need_numpy()

    def _java_align(self, rgb, lm):
        import numpy as np
        h, w = rgb.shape[:2]
        with tempfile.TemporaryDirectory() as d:
            src, fl, ar = Path(d) / "in.raw", Path(d) / "warp.raw", Path(d) / "argb.raw"
            write_argb(src, rgb)
            out = Harness.run("--align", src, w, h, *[repr(float(v)) for v in lm], fl, ar)
            vals = out.split()[1:]
            m = None if vals == ["null"] else np.array([float(v) for v in vals])
            warp = np.frombuffer(fl.read_bytes(), dtype="<f4").reshape(112, 112, 3) if m is not None else None
            argb = np.frombuffer(ar.read_bytes(), dtype="<u4").reshape(112, 112) if m is not None else None
        return m, warp, argb

    def test_alignment_matches_java_within_1e_3(self):
        import numpy as np
        rng = np.random.default_rng(11)
        rgb = rng.integers(0, 256, (480, 640, 3)).astype(np.uint8)
        cases = [
            [216.0, 107.8, 274.9, 107.7, 244.5, 139.0, 217.8, 162.5, 273.7, 162.8],
            [300.3, 200.1, 352.7, 214.9, 322.0, 240.5, 296.2, 262.3, 340.8, 275.0],   # tilted
            [2.0, 5.0, 40.0, 4.0, 20.0, 25.0, 5.0, 45.0, 38.0, 44.0],                  # near the corner
            [600.0, 440.0, 630.0, 441.0, 615.0, 458.0, 603.0, 472.0, 628.0, 473.0],    # beyond the edge
        ]
        for lm in cases:
            lm32 = np.asarray(lm, dtype=np.float32)
            m_java, warp_java, argb_java = self._java_align(rgb, lm32)
            m = fb.fit(lm32)
            np.testing.assert_allclose(m, m_java, atol=1e-9)
            warp = fb.warp(rgb, m)
            np.testing.assert_allclose(warp, warp_java, atol=1e-3)
            got = fb.to_rgb8(warp)
            want = np.stack([(argb_java >> s) & 0xFF for s in (16, 8, 0)], -1).astype(np.uint8)
            np.testing.assert_array_equal(got, want)

    def test_degenerate_landmarks_give_no_fit_in_both(self):
        import numpy as np
        rgb = np.zeros((20, 20, 3), np.uint8)
        lm = np.full(10, 7.0, np.float32)
        self.assertIsNone(self._java_align(rgb, lm)[0])
        self.assertIsNone(fb.fit(lm))

    def _java_quality(self, rgb):
        h, w = rgb.shape[:2]
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "in.raw"
            write_argb(p, rgb)
            out = Harness.run("--quality", p, w, h)
        lines = {line.split(" ", 1)[0]: line.split(" ", 1)[1] for line in out.splitlines()}
        return float(lines["LUMA"]), float(lines["LAPVAR"]), [int(v) for v in lines["TABLE"].split()]

    def test_luma_blur_and_brighten_table_match_face_quality(self):
        import numpy as np
        rng = np.random.default_rng(7)
        crops = []
        for mean, amp in ((35, 30), (62, 70), (85, 120), (140, 60)):
            base = mean + (rng.random((112, 112)) - 0.5) * amp
            crops.append(np.clip(base[..., None] + rng.integers(-12, 13, (112, 112, 3)), 0, 255).astype(np.uint8))
        crops.append(np.full((112, 112, 3), 60, np.uint8))  # flat: zero blur, no stretch
        crops.append(rng.integers(0, 256, (224, 224, 3)).astype(np.uint8))  # a loose crop
        for i, rgb in enumerate(crops):
            luma, lap, table = self._java_quality(rgb)
            self.assertAlmostEqual(fb.mean_luma(rgb), luma, places=9, msg=f"crop {i}")
            self.assertLessEqual(abs(fb.laplacian_variance(rgb) - lap), 0.005 * max(lap, 1e-12), f"crop {i}")
            self.assertEqual(fb.brighten_table(rgb), table, f"crop {i}")
            applied = fb.apply_table(rgb, table)
            self.assertEqual(applied.dtype, np.uint8)
            self.assertEqual(int(applied[0, 0, 0]), table[int(rgb[0, 0, 0])])


class RealModelTest(unittest.TestCase):
    """The whole bench pipeline on the public-domain fixtures (obama x2, biden x1)."""

    def setUp(self):
        need_numpy()
        try:
            import onnxruntime  # noqa: F401
            import PIL  # noqa: F401
        except ImportError:
            raise unittest.SkipTest("onnxruntime/pillow not installed (run with tools/face-bench/venv/bin/python)")

    def test_fixtures_detect_align_and_score_as_expected(self):
        with tempfile.TemporaryDirectory() as d:
            for person, files in {"BO": ["obama-2009.jpg", "obama-2012.jpg"], "JB": ["biden-2021.jpg"]}.items():
                (Path(d) / person).mkdir()
                for f in files:
                    (Path(d) / person / f).symlink_to(FACES / f)
            report = fb.bench(Path(d))
        det = report["detection"]
        for size in fb.CANDIDATE_SIZES:
            self.assertEqual(det["per_size"][size]["images_with_face"], 3, size)
        self.assertEqual(det["chosen"], fb.CANDIDATE_SIZES[0])
        a = report["analysis"]
        self.assertEqual(len(a["genuine"]), 1)
        self.assertEqual(len(a["impostor"]), 2)
        self.assertGreater(a["genuine"][0], 0.363)
        self.assertLess(a["impostor_max"], a["genuine"][0])


if __name__ == "__main__":
    unittest.main()
