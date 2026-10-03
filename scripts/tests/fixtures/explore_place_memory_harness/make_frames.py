#!/usr/bin/env python3
"""Regenerates frames/: real roam frames as the robot's place-print input, plus reference scores.

Run with tools/detector-export/.venv/bin/python (cv2, numpy) against out/camera-frames-run/
(the 2026-10-01 14-minute roam). Each frame is decoded at a quarter (160x120, like
ExploreCamera's inSampleSize 4) and halved by 2x2 integer means (ExploreCamera.halve) to
the 80x60 RGB the camera already has; it is saved raw (R, G, B bytes). expected.txt
holds this file's own reference similarity for each pair, which PlaceMemory must match.
"""
import sys
from pathlib import Path

import cv2
import numpy as np

HERE = Path(__file__).resolve().parent
RUN = HERE.parents[3] / "out" / "camera-frames-run"
# (a, b, label): same place minutes apart, different places, and a plain wall.
PAIRS = [
    ("1790887169643", "1790887336426", "same"),
    ("1790887865916", "1790887951408", "same"),
    ("1790887155108", "1790887578495", "different"),
    ("1790887191224", "1790887309246", "different"),
]
PLAIN = "1790887822808"


def eighty(name):
    im = cv2.imread(str(RUN / f"frame-{name}.jpg"), cv2.IMREAD_REDUCED_COLOR_4)[:, :, ::-1].astype(np.int32)
    h, w = im.shape[0] // 2, im.shape[1] // 2
    return (im[0:2 * h:2, 0:2 * w:2] + im[1:2 * h:2, 0:2 * w:2] + im[0:2 * h:2, 1:2 * w:2]
            + im[1:2 * h:2, 1:2 * w:2]) // 4


def gray16(rgb):
    g = 0.299 * rgb[..., 0] + 0.587 * rgb[..., 1] + 0.114 * rgb[..., 2]
    h, w = g.shape
    out = np.zeros((12, 16))
    for cy in range(12):
        for cx in range(16):
            out[cy, cx] = g[cy * h // 12:(cy + 1) * h // 12, cx * w // 16:(cx + 1) * w // 16].mean()
    return out


def hist(rgb):
    r, g, b = (rgb[..., k].astype(np.float64) for k in range(3))
    mx = np.maximum(np.maximum(r, g), b)
    mn = np.minimum(np.minimum(r, g), b)
    d = mx - mn
    safe = np.where(d > 0, d, 1)
    hue = np.where(mx == r, 60 * (g - b) / safe, np.where(mx == g, 120 + 60 * (b - r) / safe, 240 + 60 * (r - g) / safe))
    hue = np.where(d > 0, np.mod(hue, 360), 0)
    sat = np.where(mx > 0, 255 * d / np.where(mx > 0, mx, 1), 0)
    hb = np.minimum(7, (hue / 45).astype(int))
    sb = np.minimum(3, (sat / 64).astype(int))
    hh = np.bincount((hb * 4 + sb).ravel(), minlength=32).astype(np.float64)
    return np.sqrt(hh / hh.sum())


def z(v):
    v = v - v.mean()
    n = np.sqrt((v * v).sum())
    return v / n if n > 1e-9 else v * 0


def sim(a, b):
    ga, gb = gray16(a), gray16(b)
    ncc = max(float(z(ga[:, max(0, s):16 + min(0, s)].ravel()) @ z(gb[:, max(0, -s):16 + min(0, -s)].ravel()))
              for s in range(-2, 3))
    return 0.5 * ncc + 0.5 * (2 * float(hist(a) @ hist(b)) - 1)


def texture(rgb):
    g = gray16(rgb)
    return np.abs(np.diff(g, axis=1)).mean() + np.abs(np.diff(g, axis=0)).mean()


def main():
    out = HERE / "frames"
    out.mkdir(exist_ok=True)
    lines = []
    names = {n for a, b, _ in PAIRS for n in (a, b)} | {PLAIN}
    for n in sorted(names):
        rgb = eighty(n)
        (out / f"{n}.rgb").write_bytes(rgb.astype(np.uint8).tobytes())
        lines.append(f"texture {n} {texture(rgb):.4f}")
    for a, b, label in PAIRS:
        lines.append(f"pair {a} {b} {label} {sim(eighty(a), eighty(b)):.4f}")
    (out / "expected.txt").write_text("\n".join(lines) + "\n")
    print("\n".join(lines))


if __name__ == "__main__":
    sys.exit(main())
