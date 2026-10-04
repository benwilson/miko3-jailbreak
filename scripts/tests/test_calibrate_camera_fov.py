#!/usr/bin/env python3
"""Tests for scripts/calibrate-camera-fov.py: log parsing, turn/frame pairing,
the phase-correlation shift and the focal-length fit.

The image tests need cv2 and numpy; run them with the detector venv:
    tools/detector-export/.venv/bin/python scripts/tests/test_calibrate_camera_fov.py
Without cv2 they are skipped; the log and pairing tests run anywhere.
"""
import importlib.util
import math
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "calibrate-camera-fov.py"


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


cal = load("calibrate_camera_fov", SCRIPT)

try:
    import cv2  # noqa: F401
    import numpy as np
    HAVE_CV = True
except ImportError:
    HAVE_CV = False


def epoch_line(t, tag, msg, pid=100, tid=101):
    return f"     {t:.3f}  {pid}  {tid} I {tag}: {msg}"


class ParseLogTest(unittest.TestCase):
    def test_epoch_and_threadtime_lines(self):
        e = cal.parse_line(epoch_line(1790886847.586, "ExploreBrain", "hello"))
        self.assertEqual(e, (1790886847586, "ExploreBrain", "hello"))
        t = cal.parse_line("10-01 14:02:03.250  1 2 I ExploreBrain: hi", year=2026)
        self.assertEqual(t[1:], ("ExploreBrain", "hi"))
        self.assertIsNone(cal.parse_line("--------- beginning of main"))

    def test_turns_pair_start_with_measured_result(self):
        lines = [
            epoch_line(1000.000, "ExploreBrain", "turn start: heading 10 deg, asking 20 deg LEFT"),
            epoch_line(1001.500, "ExploreBrain", "measured turn: asked 20 deg, turned 18 deg, overshoot 3 deg"),
            epoch_line(1005.000, "ExploreBrain", "turn start: heading 28 deg, asking 120 deg RIGHT"),
            epoch_line(1006.509, "ExploreBrain", "measured turn blocked: turned 0 of 120 deg in 1509 ms (RIGHT)"),
            epoch_line(1006.600, "ExploreBrain", "measured turn: asked 120 deg, turned 0 deg, overshoot 2 deg"),
        ]
        turns = cal.parse_turns(lines)
        self.assertEqual(len(turns), 2)
        a, b = turns
        self.assertEqual((a.start_ms, a.end_ms, a.direction, a.asked, a.turned, a.blocked),
                         (1000000, 1001500, "LEFT", 20, 18, False))
        self.assertEqual(a.signed_deg, 18)  # heading positive is LEFT
        self.assertTrue(b.blocked)
        self.assertEqual(b.signed_deg, 0)

    def test_a_turn_overlapped_by_the_next_start_is_dropped(self):
        lines = [
            epoch_line(1000.0, "ExploreBrain", "turn start: heading 0 deg, asking 20 deg LEFT"),
            epoch_line(1001.0, "ExploreBrain", "turn start: heading 20 deg, asking 15 deg RIGHT"),
            epoch_line(1001.1, "ExploreBrain", "measured turn: asked 20 deg, turned 20 deg, overshoot 3 deg"),
            epoch_line(1003.0, "ExploreBrain", "measured turn: asked 15 deg, turned 14 deg, overshoot 3 deg"),
            epoch_line(1010.0, "ExploreBrain", "turn start: heading 6 deg, asking 10 deg LEFT"),
            epoch_line(1011.0, "ExploreBrain", "measured turn: asked 10 deg, turned 9 deg, overshoot 3 deg"),
        ]
        turns = cal.parse_turns(lines)
        self.assertEqual([(t.asked, t.direction) for t in turns], [(10, "LEFT")])


    def test_a_start_that_never_gets_a_result_does_not_block_later_turns(self):
        lines = [
            epoch_line(1000.0, "ExploreBrain", "turn start: heading 0 deg, asking 180 deg RIGHT"),
            epoch_line(1010.0, "ExploreBrain", "turn start: heading 0 deg, asking 60 deg LEFT"),
            epoch_line(1011.5, "ExploreBrain", "measured turn blocked: turned 0 of 60 deg in 1503 ms (LEFT)"),
            epoch_line(1011.6, "ExploreBrain", "measured turn: asked 60 deg, turned 0 deg, overshoot 0 deg"),
            epoch_line(1020.0, "ExploreBrain", "turn start: heading 0 deg, asking 23 deg LEFT"),
            epoch_line(1021.0, "ExploreBrain", "measured turn: asked 23 deg, turned 24 deg, overshoot 9 deg"),
        ]
        turns = cal.parse_turns(lines)
        self.assertEqual([(t.asked, t.turned, t.blocked) for t in turns], [(60, 0, True), (23, 24, False)])

class PairingTest(unittest.TestCase):
    def turn(self, start, end, turned, blocked=False, direction="LEFT"):
        return cal.Turn(start_ms=start, end_ms=end, direction=direction, asked=turned,
                        turned=turned, start_heading=0, blocked=blocked)

    def test_last_frame_before_start_and_first_after_end(self):
        frames = [900, 950, 1100, 1600, 1700]
        pairs = cal.pair_frames([self.turn(1000, 1500, 20)], frames)
        self.assertEqual(len(pairs), 1)
        self.assertEqual((pairs[0].before_ms, pairs[0].after_ms), (950, 1600))
        late = cal.pair_frames([self.turn(1000, 1500, 20)], frames, min_after_ms=150)
        self.assertEqual(late[0].after_ms, 1700)

    def test_filters_by_size_blocked_and_gap(self):
        frames = list(range(0, 100000, 500))
        turns = [
            self.turn(10000, 11000, 3),        # too small
            self.turn(20000, 21000, 45),       # too big
            self.turn(30000, 31000, 20, True), # blocked
            self.turn(40000, 41000, -12),      # ok, either sign
        ]
        pairs = cal.pair_frames(turns, frames, min_deg=5, max_deg=30)
        self.assertEqual([p.turn.start_ms for p in pairs], [40000])
        # No frame near enough: dropped.
        self.assertEqual(cal.pair_frames([self.turn(1000, 2000, 20)], [0, 9000], max_gap_ms=1500), [])


@unittest.skipUnless(HAVE_CV, "needs cv2 + numpy")
class ShiftAndFitTest(unittest.TestCase):
    W, H = 640, 480

    def scene(self, seed=1):
        rng = np.random.default_rng(seed)
        big = rng.random((self.H // 8, 2000 // 8)).astype(np.float32)
        big = cv2.resize(big, (2000, self.H), interpolation=cv2.INTER_CUBIC)
        return (np.clip(big, 0, 1) * 255).astype(np.uint8)

    def view(self, scene, x0):
        return scene[:, x0:x0 + self.W].copy()

    def test_measured_shift_and_its_sign(self):
        s = self.scene()
        a = self.view(s, 600)
        b = self.view(s, 560)  # camera panned left: content moves right (+x)
        du, resp = cal.measure_shift(a, b)
        self.assertAlmostEqual(du, 40, delta=0.5)
        self.assertGreater(resp, 0.3)

    def test_fit_recovers_the_field_of_view(self):
        hfov = 66.0
        fx = (self.W / 2) / math.tan(math.radians(hfov / 2))
        s = self.scene(2)
        samples = []
        for i, deg in enumerate([6, -9, 12, -15, 18, 21, -24, 27, 10, -8]):
            du_true = fx * math.tan(math.radians(deg))
            a = self.view(s, 700)
            b = self.view(s, int(round(700 - du_true)))
            du, resp = cal.measure_shift(a, b)
            samples.append(cal.Sample(deg=deg, du=du, response=resp, label=str(i)))
        # One parallax-wrecked outlier is rejected.
        samples.append(cal.Sample(deg=15, du=fx * math.tan(math.radians(15)) * 1.6, response=0.5, label="bad"))
        fit = cal.fit_focal(samples, width=self.W)
        self.assertAlmostEqual(fit["hfov_deg"], hfov, delta=0.6)
        self.assertEqual(fit["sign"], 1)  # LEFT (+deg) turn moves the image +x
        self.assertIn("bad", fit["rejected"])
        self.assertAlmostEqual(fit["px_per_deg"], fx * math.tan(math.radians(1)), delta=0.2)

    def test_fit_needs_enough_samples(self):
        with self.assertRaises(ValueError):
            cal.fit_focal([cal.Sample(deg=10, du=50, response=0.5, label="x")], width=self.W)


@unittest.skipUnless(HAVE_CV, "needs cv2 + numpy")
class RotationHomographyTest(unittest.TestCase):
    """The gyro-free estimate: a turn in place maps one frame onto the other by
    K R K^-1. The focal length is the f that makes K^-1 H K a rotation; that
    rotation's angle and axis give the true turn and the camera's pitch."""
    W, H = 640, 480

    def texture(self, seed=3):
        rng = np.random.default_rng(seed)
        small = (rng.random((60, 80)) * 255).astype(np.uint8)
        big = cv2.resize(small, (1600, 1200), interpolation=cv2.INTER_NEAREST)
        return cv2.GaussianBlur(big, (5, 5), 1.0)

    def render(self, tex, f, R):
        """The view of a far textured sphere-like backdrop: a big plane far away
        seen through K R (pure rotation, so any scene depth works)."""
        K = np.array([[f, 0, self.W / 2], [0, f, self.H / 2], [0, 0, 1.0]])
        # Plane texture coordinates: a virtual wide camera with focal 500 looking at it.
        Kt = np.array([[500.0, 0, 800], [0, 500.0, 600], [0, 0, 1]])
        Hmap = Kt @ R.T @ np.linalg.inv(K)
        return cv2.warpPerspective(tex, Hmap, (self.W, self.H), flags=cv2.WARP_INVERSE_MAP | cv2.INTER_LINEAR)

    def test_recovers_focal_turn_and_pitch(self):
        f, pitch, yaw = 520.0, 18.0, 20.0
        tex = self.texture()
        r0 = cal.yaw_about_tilted_axis(0.0, pitch)
        r1 = cal.yaw_about_tilted_axis(yaw, pitch)
        a = self.render(tex, f, r0)
        b = self.render(tex, f, r1)
        m = cal.measure_rotation(a, b)
        self.assertIsNotNone(m)
        self.assertAlmostEqual(m["f"], f, delta=f * 0.03)
        self.assertAlmostEqual(abs(m["angle_deg"]), yaw, delta=0.7)
        self.assertAlmostEqual(abs(m["pitch_deg"]), pitch, delta=2.0)
        self.assertLess(m["reproj_px"], 1.5)

    def test_turn_direction_sign(self):
        tex = self.texture(4)
        a = self.render(tex, 520.0, cal.yaw_about_tilted_axis(0.0, 10.0))
        b = self.render(tex, 520.0, cal.yaw_about_tilted_axis(15.0, 10.0))  # +yaw is LEFT
        m = cal.measure_rotation(a, b)
        self.assertGreater(m["angle_deg"], 0)
        self.assertGreater(m["du_centre"], 0)  # LEFT moves content right

    def test_edge_yaw_of_a_pitched_camera(self):
        # Level camera: the edge of the centre row is exactly half the HFOV.
        f = 320 / math.tan(math.radians(30))
        self.assertAlmostEqual(cal.edge_yaw_deg(f, 0.0, self.W), 30.0, places=3)
        # Pitched up, the same edge pixel is a wider yaw (the ray leans back toward the axis).
        self.assertGreater(cal.edge_yaw_deg(f, 18.0, self.W), 30.0)

class BiasTest(unittest.TestCase):
    def test_turn_bias(self):
        turns = [cal.Turn(0, 1, "LEFT", 20, 17, 0, False), cal.Turn(0, 1, "RIGHT", 30, 26, 0, False),
                 cal.Turn(0, 1, "LEFT", 40, 0, 0, True)]
        b = cal.turn_bias(turns)
        self.assertEqual(b["n"], 2)
        self.assertAlmostEqual(b["median_error_deg"], -3.5)


if __name__ == "__main__":
    unittest.main()
