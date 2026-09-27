#!/usr/bin/env python3
"""face-bench.py — fit Explore's face-check thresholds and YuNet input size from
real robot captures, on the Mac (on-device face recognition plan, U9).

  python3 scripts/face-bench.py pull           # the robot's rolling meeting frames
  python3 scripts/face-bench.py run [DIR]      # score them and suggest values
  python3 scripts/face-bench.py clean          # delete the captures (do this after)

Capturing: `adb shell setprop log.tag.MikoExploreFaceDebug DEBUG`, restart
Explore, and let him meet people. He keeps the last 50 meeting source frames in
his private files dir (face-frames/). `pull` copies them over root adb into
tools/face-bench/captures/_unsorted/. Sort them into one folder per person
under tools/face-bench/captures/ (initials, not names: the labels appear in the
summary) and delete frames with no face or the wrong person.

`run` mirrors the robot's face pipeline (KTD2, KTD3) in numpy + onnxruntime
with the same constants: YuNet on the frame scaled to fit each candidate input
size and padded, a five-point similarity alignment, the size/dark/blur gate,
the brighten, then SFace. It reports detection counts per input size, the gate
metrics, genuine and impostor score ranges, and a leave-one-person-out pass
(each person scored as a stranger against everyone else's full photo sets). It
suggests confident/close/margin that keep every impostor, held-out ones
included, out of the confident band while at least 80% of genuine pairs reach
it; if no such value exists it prints a STOP CONDITION 2 warning instead. The
summary is numbers and folder labels only. `--confident/--close/--margin`
evaluate given values instead of suggesting them.

It runs in its own venv, tools/face-bench/venv (onnxruntime 1.30, numpy,
pillow), created on first use and re-entered automatically.
"""
import argparse
import math
import os
import shutil
import struct
import subprocess
import sys
import time
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
BENCH = REPO / "tools" / "face-bench"
VENV = BENCH / "venv"
VENV_PYTHON = VENV / "bin" / "python"
VENV_PACKAGES = ["onnxruntime==1.30.*", "numpy", "pillow"]
CAPTURES = BENCH / "captures"
SUMMARY = BENCH / "summary.md"
ASSETS = REPO / "mode-explore" / "assets"
YUNET = ASSETS / "face_yunet.onnx"
SFACE = ASSETS / "face_sface.onnx"

DEFAULT_SERIAL = "192.168.19.74:5555"
EXPLORE_PACKAGE = "com.miko3.mode.explore"
FRAMES_DIR = "face-frames"
REMOTE_FRAMES = f"/data/data/{EXPLORE_PACKAGE}/files/{FRAMES_DIR}"
DEBUG_PROP = "log.tag.MikoExploreFaceDebug"
IMAGE_EXTS = {".jpg", ".jpeg", ".png"}

# YuNetDecoder / FaceCropper
CANDIDATE_SIZES = [(320, 256), (480, 384), (640, 480)]
STRIDES = (8, 16, 32)
MIN_SCORE = 0.6
NMS_IOU = 0.3
MAX_FACES = 10
FULL_FRAME = (640, 480)

# FaceAlign
SIDE = 112
TEMPLATE = (38.2946, 51.6963, 73.5318, 51.5014, 56.0252, 71.7366, 41.5493, 92.3655, 70.7299, 92.2041)
MIN_SPREAD = 1e-6

# FaceQuality
GAMMA_TARGET = 110.0
GAMMA_MIN = 0.4
GAMMA_MAX = 1.0
LOW_PERCENTILE = 0.01
HIGH_PERCENTILE = 0.99
DEFAULT_GATES = {"min_width": 48, "dark_floor": 40, "dim_level": 90, "blur_floor": 30}
GATE_PERCENTILE = 0.05

# FaceMatcher / KTD5
DEFAULT_BANDS = {"confident": 0.50, "close": 0.363, "margin": 0.05}
CONFIDENT_FLOOR = 0.363      # SFace's published 1:1 threshold: never suggest confident below it
                             # (close is never suggested above it, so a close band always remains)
CONFIDENT_GAP = 0.02         # confident sits at least this far above the highest impostor
GREET_SHARE = 0.80           # genuine pairs that must reach confident
MARGIN_STEPS = (0.05, 0.04, 0.03, 0.02, 0.01, 0.0)
MAX_DEMOTED_SHARE = 0.10     # genuine confident probes the near-tie margin may demote


class BenchError(Exception):
    pass


# --- people folders ---

def list_people(root):
    """{label: [image paths]} for each subfolder; '_' and '.' folders are skipped."""
    root = Path(root)
    if not root.is_dir():
        raise BenchError(f"!! {root} is not a folder")
    people = {}
    for d in sorted(root.iterdir()):
        if not d.is_dir() or d.name.startswith(("_", ".")):
            continue
        people[d.name] = sorted(p for p in d.iterdir() if p.is_file() and p.suffix.lower() in IMAGE_EXTS)
    return people


def require_people(people):
    kept = {k: v for k, v in people.items() if v}
    if len(kept) < 2:
        raise BenchError(
            f"!! the bench needs at least two people (one folder each, with photos); found {len(kept)}.\n"
            "   Impostor scores come from comparing different people, so one person gives nothing to fit.\n"
            "   Sort the pulled frames into tools/face-bench/captures/<initials>/ folders.")
    return kept


# --- score analysis (plain Python, so it runs and tests without numpy) ---

def f32(x):
    return struct.unpack("f", struct.pack("f", x))[0]


def floor2(x):
    return math.floor(x * 100 + 1e-9) / 100


def percentile(values, q):
    """The value at sorted index floor(q*(n-1)), FaceQuality's percentile rule."""
    s = sorted(values)
    return s[int(q * (len(s) - 1))] if s else float("nan")


def band(top, runner, t):
    """FaceMatcher.match's band for a best score and a runner-up score (or None)."""
    top = f32(top)
    if top >= f32(t["confident"]):
        if runner is not None and f32(top - f32(runner)) < f32(t["margin"]):
            return "close"
        return "confident"
    return "close" if top >= f32(t["close"]) else "weak"


def _similarity(vectors):
    try:
        import numpy as np
        e = np.asarray(vectors, dtype=np.float32).astype(np.float64)
        return (e @ e.T).astype(np.float32).astype(float).tolist()
    except ImportError:
        return [[f32(sum(a * b for a, b in zip(u, v))) for v in vectors] for u in vectors]


def analyse_scores(people, given=None):
    """Genuine/impostor scores, the held-out pass and suggested (or given) bands.

    people is {label: [unit embeddings]}. Each probe is scored as FaceMatcher does:
    the best photo per person, then the top and runner-up people.
    """
    labels, vectors = [], []
    for label in sorted(people):
        for v in people[label]:
            labels.append(label)
            vectors.append(list(v))
    S = _similarity(vectors)
    n = len(labels)
    genuine, impostor = [], []
    for i in range(n):
        for j in range(i + 1, n):
            (genuine if labels[i] == labels[j] else impostor).append(S[i][j])

    probes = []  # (label, own best or None, [(score, other person)] best first)
    for i in range(n):
        best = {}
        for j in range(n):
            if j != i and (labels[j] not in best or S[i][j] > best[labels[j]]):
                best[labels[j]] = S[i][j]
        own = best.pop(labels[i], None)
        others = sorted(((s, p) for p, s in best.items()), key=lambda x: (-x[0], x[1]))
        probes.append((labels[i], own, others))

    a = {"people": {k: len(v) for k, v in sorted(people.items())}, "genuine": genuine, "impostor": impostor,
         "genuine_min": min(genuine) if genuine else None, "genuine_max": max(genuine) if genuine else None,
         "impostor_min": min(impostor) if impostor else None,
         "impostor_max": max(impostor) if impostor else None, "given": given is not None}

    reasons = []
    if not genuine:
        reasons.append("no person has two usable captures, so there are no genuine scores to fit")
    if not impostor:
        reasons.append("no impostor scores (needs two people with usable captures)")
    if given is not None:
        t = dict(given)
    elif genuine and impostor:
        conf = max(CONFIDENT_FLOOR, round(math.floor((a["impostor_max"] + CONFIDENT_GAP) * 100 + 1) / 100, 2))
        t = {"confident": conf, "close": max(0.0, min(round(floor2(a["genuine_min"]), 2), DEFAULT_BANDS["close"])),
             "margin": DEFAULT_BANDS["margin"]}
        gaps = [own - others[0][0] for _, own, others in probes
                if own is not None and others and own >= t["confident"]]
        if gaps:
            t["margin"] = next(m for m in MARGIN_STEPS
                               if sum(1 for g in gaps if g < m) <= MAX_DEMOTED_SHARE * len(gaps))
    else:
        t = dict(DEFAULT_BANDS)
    a["evaluated"] = t

    held = {}
    for label, _, others in probes:
        h = held.setdefault(label, {"probes": 0, "confident": 0, "close": 0, "reached_confident": 0, "best": None})
        if not others:
            continue
        top = others[0][0]
        runner = others[1][0] if len(others) > 1 else None
        h["probes"] += 1
        b = band(top, runner, t)
        if b in ("confident", "close"):
            h[b] += 1
        if f32(top) >= f32(t["confident"]):
            h["reached_confident"] += 1
        h["best"] = top if h["best"] is None else max(h["best"], top)
    a["held_out"] = held

    gen = {"probes": 0, "confident": 0, "close": 0, "weak": 0, "wrong_person": 0}
    for _, own, others in probes:
        if own is None:
            continue
        gen["probes"] += 1
        if others and others[0][0] > own:
            gen["wrong_person"] += 1
            continue
        gen[band(own, others[0][0] if others else None, t)] += 1
    a["genuine_probes"] = gen
    share = sum(1 for g in genuine if f32(g) >= f32(t["confident"])) / len(genuine) if genuine else 0.0
    a["greet_share"] = share

    if genuine and impostor:
        if t["confident"] > 1:
            reasons.append(f"the highest impostor score ({a['impostor_max']:.3f}) leaves no confident value "
                           "at or below 1")
        over = sum(1 for s in impostor if f32(s) >= f32(t["confident"]))
        if over:
            reasons.append(f"{over} impostor pair(s) score at or above confident {t['confident']}")
        reached = sum(h["reached_confident"] for h in held.values())
        if reached:
            who = ", ".join(k for k, h in sorted(held.items()) if h["reached_confident"])
            reasons.append(f"{reached} held-out capture(s) ({who}) reach confident {t['confident']} "
                           "against everyone else's photos")
        if share < GREET_SHARE:
            reasons.append(f"only {share:.0%} of genuine pairs reach confident {t['confident']} "
                           f"(needs {GREET_SHARE:.0%})")
        if not 0 <= t["close"] <= t["confident"]:
            reasons.append("close must sit between 0 and confident")
    a["stop_reasons"] = reasons
    a["stop"] = bool(reasons)
    a["suggested"] = None if a["stop"] else t
    return a


def suggest_gates(faces):
    """Gate values from the captures' metrics: each is the 5th percentile, floored,
    but never stricter than the KTD5 default, so the owner's own captures pass."""
    g = dict(DEFAULT_GATES)
    widths = [f["width"] for f in faces if f.get("full_frame")]
    if widths:
        g["min_width"] = max(16, min(g["min_width"], int(math.floor(percentile(widths, GATE_PERCENTILE)))))
    if faces:
        g["dark_floor"] = max(0, min(g["dark_floor"],
                                     int(math.floor(percentile([f["luma"] for f in faces], GATE_PERCENTILE)))))
        g["blur_floor"] = max(0, min(g["blur_floor"],
                                     int(math.floor(percentile([f["blur"] for f in faces], GATE_PERCENTILE)))))
    g["dark_floor"] = min(g["dark_floor"], g["dim_level"] - 1)
    return g


def thresholds_command(bands, gates):
    """The robot-faces.py (U8) command that applies the values."""
    return ("python3 scripts/robot-faces.py thresholds"
            f" --confident {bands['confident']:g} --close {bands['close']:g} --margin {bands['margin']:g}"
            f" --min-width {gates['min_width']} --dark-floor {gates['dark_floor']:g}"
            f" --dim-level {gates['dim_level']:g} --blur-floor {gates['blur_floor']:g}")


# --- summary ---

def _spread(values, fmt="{:.3f}"):
    if not values:
        return "none"
    qs = [(k, percentile(values, q)) for k, q in (("min", 0), ("p5", .05), ("p50", .5), ("p95", .95))]
    return ", ".join(f"{k} {fmt.format(v)}" for k, v in qs) + f", max {fmt.format(max(values))} (n={len(values)})"


def _histogram(values, lo=-0.2, hi=1.0, step=0.05):
    counts = {}
    for v in values:
        k = min(max(math.floor((v - lo) / step), 0), int(round((hi - lo) / step)) - 1)
        counts[k] = counts.get(k, 0) + 1
    return " ".join(f"[{lo + k * step:.2f},{lo + (k + 1) * step:.2f}):{c}" for k, c in sorted(counts.items()))


def render_summary(a, gates, detection, faces=None, rejected=None):
    out = ["# Face bench summary", "",
           f"Run {time.strftime('%Y-%m-%d %H:%M')}. Models: YuNet 2026may, SFace 2021dec. Numbers and folder "
           "labels only.", ""]
    out.append("People (usable captures): " + ", ".join(f"{k} {v}" for k, v in a["people"].items()))
    out.append("")
    if detection:
        out += ["## Detection per YuNet input size", "", "| input | images with a face | faces found |",
                "|---|---|---|"]
        for size, d in detection["per_size"].items():
            out.append(f"| {size[0]}x{size[1]} | {d['images_with_face']}/{detection['images']} | {d['faces']} |")
        out.append("")
        chosen = detection["chosen"]
        out.append(f"Smallest size that keeps every face: **{chosen[0]}x{chosen[1]}**"
                   + ("" if chosen == CANDIDATE_SIZES[0] else " (the robot default is 320x256; change "
                      "YuNetDecoder.INPUT_W/H)") + ".")
        if detection["no_face"]:
            out.append(f"No face at any size in {detection['no_face']} image(s); they are left out.")
        out.append("")
    if faces:
        sharp = [f for f in faces if f["blur"] >= DEFAULT_GATES["blur_floor"]]
        bright = [f for f in faces if f["luma"] >= DEFAULT_GATES["dim_level"]]
        out += ["## Gate metrics (aligned 112 px crop; width in frame px, full frames only)", ""]
        for name, fs in (("all", faces), ("sharp (blur >= 30)", sharp), ("bright (luma >= 90)", bright)):
            out.append(f"- {name}: width {_spread([f['width'] for f in fs if f['full_frame']], '{:.0f}')}; "
                       f"luma {_spread([f['luma'] for f in fs], '{:.0f}')}; "
                       f"blur {_spread([f['blur'] for f in fs], '{:.0f}')}")
        out.append("")
    if gates:
        out.append(f"Suggested gates (5th percentile, never stricter than the defaults 48/40/90/30): "
                   f"min width {gates['min_width']}, dark floor {gates['dark_floor']}, dim level "
                   f"{gates['dim_level']}, blur floor {gates['blur_floor']}.")
        if rejected is not None:
            out.append("Rejected by those gates, left out of the scores: "
                       + (", ".join(f"{k} {v}" for k, v in rejected.items() if v) or "none") + ".")
        out.append("")
    out += ["## Scores (cosine, SFace)", "",
            f"- genuine pairs: {_spread(a['genuine'])}",
            f"- impostor pairs: {_spread(a['impostor'])}",
            f"- highest impostor: {a['impostor_max']:.3f}" if a["impostor_max"] is not None else "- highest impostor: none",
            f"- genuine histogram: {_histogram(a['genuine'])}",
            f"- impostor histogram: {_histogram(a['impostor'])}", ""]
    t = a["evaluated"]
    out.append(f"Bands {'given' if a['given'] else 'suggested'}: confident {t['confident']:g}, close "
               f"{t['close']:g}, margin {t['margin']:g}. Genuine pairs at or above confident: "
               f"{a['greet_share']:.0%}.")
    g = a["genuine_probes"]
    out.append(f"Each genuine capture against the full gallery (own other photos plus everyone else): "
               f"{g['confident']} confident, {g['close']} close, {g['weak']} weak, {g['wrong_person']} best "
               f"matched someone else (of {g['probes']}).")
    out += ["", "## Held out: each person as a stranger against everyone else", "",
            "| person | captures | best score | close | confident | raw score >= confident |", "|---|---|---|---|---|---|"]
    for label, h in a["held_out"].items():
        best = f"{h['best']:.3f}" if h["best"] is not None else "-"
        out.append(f"| {label} | {h['probes']} | {best} | {h['close']} | {h['confident']} | "
                   f"{h['reached_confident']} |")
    out.append("")
    if a["stop"]:
        lead = ("The given values fail the checks below" if a["given"] else
                "No confident threshold keeps every impostor (held-out people included) out of the confident "
                "band while at least 80% of genuine pairs reach it")
        out += ["## STOP CONDITION 2", "",
                f"{lead}. Do not ship the bands; paste this summary into the pull request and report the "
                "distributions above.", ""]
        out += [f"- {r}" for r in a["stop_reasons"]]
        out.append("")
    else:
        rule = ("confident = the next 0.01 step above (highest impostor + 0.02), at least 0.363; close = the "
                "lower of the lowest genuine pair score and 0.363 (SFace's published threshold); margin = the largest of 0.05..0 that demotes "
                "at most 10% of confident genuine captures")
        out += ["## Values", "", f"Rule: {rule}." if not a["given"] else "Given values pass every check.", "",
                "```", thresholds_command(t, gates or DEFAULT_GATES), "```", ""]
    out.append("Delete the captures once these values are recorded: `python3 scripts/face-bench.py clean` "
               "(add `--robot` to clear the frames on the robot too).")
    return "\n".join(out) + "\n"


# --- numpy mirror of the robot pipeline ---

def fit(lm):
    """FaceAlign.fit: the closed-form similarity onto TEMPLATE, [a, -b, tx, b, a, ty] or None."""
    pts = [float(v) for v in lm]
    if len(pts) != 10 or any(math.isnan(v) or math.isinf(v) for v in pts):
        return None
    sx = sy = dx = dy = 0.0
    for i in range(5):
        sx += pts[2 * i]
        sy += pts[2 * i + 1]
        dx += TEMPLATE[2 * i]
        dy += TEMPLATE[2 * i + 1]
    sx /= 5
    sy /= 5
    dx /= 5
    dy /= 5
    spread = re = im = 0.0
    for i in range(5):
        px, py = pts[2 * i] - sx, pts[2 * i + 1] - sy
        qx, qy = TEMPLATE[2 * i] - dx, TEMPLATE[2 * i + 1] - dy
        spread += px * px + py * py
        re += px * qx + py * qy
        im += px * qy - py * qx
    if not spread > MIN_SPREAD:
        return None
    a, b = re / spread, im / spread
    import numpy as np
    return np.array([a, -b, dx - (a * sx - b * sy), b, a, dy - (b * sx + a * sy)])


def warp(rgb, m):
    """FaceAlign.warp: 112x112x3 float32, bilinear, corner index clamped, fraction not."""
    import numpy as np
    h, w = rgb.shape[:2]
    det = m[0] * m[4] - m[1] * m[3]
    i00, i01, i10, i11 = m[4] / det, -m[1] / det, -m[3] / det, m[0] / det
    yy, xx = np.mgrid[0:SIDE, 0:SIDE].astype(np.float64)
    px, py = xx - m[2], yy - m[5]
    x = i00 * px + i01 * py
    y = i10 * px + i11 * py
    x0 = np.clip(np.floor(x), 0, w - 2).astype(np.int64)
    y0 = np.clip(np.floor(y), 0, h - 2).astype(np.int64)
    fx = (x - x0)[..., None]
    fy = (y - y0)[..., None]
    I = rgb.astype(np.float64)
    v = (I[y0, x0] * ((1 - fx) * (1 - fy)) + I[y0, x0 + 1] * (fx * (1 - fy))
         + I[y0 + 1, x0] * ((1 - fx) * fy) + I[y0 + 1, x0 + 1] * (fx * fy))
    return v.astype(np.float32)


def to_rgb8(v):
    """FaceAlign.toArgb's rounding: floor(v + 0.5f) in float, clamped."""
    import numpy as np
    return np.clip(np.floor(v.astype(np.float32) + np.float32(0.5)), 0, 255).astype(np.uint8)


def _luma256(rgb):
    import numpy as np
    c = rgb.astype(np.int64)
    return 77 * c[..., 0] + 150 * c[..., 1] + 29 * c[..., 2]


def mean_luma(rgb):
    l = _luma256(rgb)
    return int(l.sum()) / 256.0 / l.size


def laplacian_variance(rgb):
    h, w = rgb.shape[:2]
    if w < 3 or h < 3:
        return 0.0
    g = _luma256(rgb) / 256.0
    lap = g[:-2, 1:-1] + g[2:, 1:-1] + g[1:-1, :-2] + g[1:-1, 2:] - 4 * g[1:-1, 1:-1]
    mean = float(lap.sum()) / lap.size
    return float(((lap - mean) ** 2).sum()) / lap.size


def brighten_table(rgb):
    """FaceQuality.table: gamma toward luma 110, then a 1st-99th percentile stretch."""
    mean = mean_luma(rgb)
    if not mean > 0:
        gamma = GAMMA_MIN
    elif mean >= 255:
        gamma = GAMMA_MAX
    else:
        gamma = max(GAMMA_MIN, min(GAMMA_MAX, math.log(GAMMA_TARGET / 255) / math.log(mean / 255)))
    hist = [0] * 256
    for v in (_luma256(rgb) >> 8).ravel().tolist():
        hist[v] += 1
    n = sum(hist)

    def pct(index):
        seen = 0
        for v in range(256):
            seen += hist[v]
            if seen > index:
                return v
        return 255

    def curve(v):
        return 255.0 * math.pow(v / 255.0, gamma)

    glo, ghi = curve(pct(int(LOW_PERCENTILE * (n - 1)))), curve(pct(int(HIGH_PERCENTILE * (n - 1))))
    table = []
    for v in range(256):
        x = (curve(v) - glo) * 255.0 / (ghi - glo) if ghi > glo else curve(v)
        table.append(max(0, min(255, int(math.floor(x + 0.5)))))
    return table


def apply_table(rgb, table):
    import numpy as np
    return np.asarray(table, dtype=np.uint8)[rgb]


def fit_scale(w, h, in_w, in_h):
    import numpy as np
    return float(min(np.float32(1), min(np.float32(in_w) / np.float32(w), np.float32(in_h) / np.float32(h))))


def decode(out, in_w, in_h, content_w, content_h):
    """YuNetDecoder.decode: faces as (x0, y0, x1, y1, score, landmarks[10]), best first, after NMS."""
    import numpy as np
    found = []
    for s in STRIDES:
        cols, rows = in_w // s, in_h // s
        cls, obj = out[f"cls_{s}"].reshape(-1), out[f"obj_{s}"].reshape(-1)
        bbox, kps = out[f"bbox_{s}"].reshape(-1, 4), out[f"kps_{s}"].reshape(-1, 10)
        if cls.size != cols * rows:
            continue
        score = np.sqrt((np.clip(cls, 0, 1) * np.clip(obj, 0, 1)).astype(np.float64)).astype(np.float32)
        for i in np.nonzero(score >= np.float32(MIN_SCORE))[0].tolist():
            r, c = divmod(i, cols)
            cx, cy = (c + bbox[i, 0]) * s, (r + bbox[i, 1]) * s
            bw, bh = math.exp(bbox[i, 2]) * s, math.exp(bbox[i, 3]) * s
            if cx >= content_w or cy >= content_h:
                continue
            lm = [float((c + kps[i, j]) * s) if j % 2 == 0 else float((r + kps[i, j]) * s) for j in range(10)]
            found.append((float(cx - bw / 2), float(cy - bh / 2), float(cx + bw / 2), float(cy + bh / 2),
                          float(score[i]), lm))
    found.sort(key=lambda f: -f[4])
    kept = []
    for f in found:
        if len(kept) >= MAX_FACES:
            break
        if all(_iou(f, k) <= NMS_IOU for k in kept):
            kept.append(f)
    return kept


def _iou(a, b):
    iw = min(a[2], b[2]) - max(a[0], b[0])
    ih = min(a[3], b[3]) - max(a[1], b[1])
    if iw <= 0 or ih <= 0:
        return 0.0
    area = lambda f: max(0.0, f[2] - f[0]) * max(0.0, f[3] - f[1])  # noqa: E731
    inter = iw * ih
    return inter / (area(a) + area(b) - inter)


class Models:
    def __init__(self):
        import onnxruntime as ort
        so = ort.SessionOptions()
        so.log_severity_level = 3
        self.yunet = ort.InferenceSession(str(YUNET), so, providers=["CPUExecutionProvider"])
        self.sface = ort.InferenceSession(str(SFACE), so, providers=["CPUExecutionProvider"])
        self.yunet_input = self.yunet.get_inputs()[0].name
        self.sface_input = self.sface.get_inputs()[0].name

    def detect(self, image, size):
        """FaceCropper.findFace without the person box: faces in frame coordinates."""
        import numpy as np
        from PIL import Image
        in_w, in_h = size
        w, h = image.size
        scale = fit_scale(w, h, in_w, in_h)
        cw, ch = int(math.floor(w * scale + 0.5)), int(math.floor(h * scale + 0.5))
        canvas = np.zeros((in_h, in_w, 3), np.float32)
        canvas[:ch, :cw] = np.asarray(image.resize((cw, ch), Image.BILINEAR), dtype=np.float32)
        x = np.ascontiguousarray(canvas[..., ::-1].transpose(2, 0, 1)[None])  # BGR planes
        names = [o.name for o in self.yunet.get_outputs()]
        out = dict(zip(names, self.yunet.run(None, {self.yunet_input: x})))
        faces = decode(out, in_w, in_h, cw, ch)
        return [(f[0] / scale, f[1] / scale, f[2] / scale, f[3] / scale, f[4], [v / scale for v in f[5]])
                for f in faces]

    def embed(self, rgb112):
        """FaceMatcher.input + normalize: RGB planes 0-255 in, unit float32 vector out."""
        import numpy as np
        x = np.ascontiguousarray(rgb112.astype(np.float32).transpose(2, 0, 1)[None])
        v = self.sface.run(None, {self.sface_input: x})[0][0].astype(np.float32)
        sq = float((v.astype(np.float64) ** 2).sum())
        return (v.astype(np.float64) * (1.0 / math.sqrt(sq))).astype(np.float32)


def largest(faces):
    best = None
    for f in faces:
        if best is None or (f[2] - f[0]) * (f[3] - f[1]) > (best[2] - best[0]) * (best[3] - best[1]):
            best = f
    return best


def bench(root, given=None):
    """The whole bench on a people folder: detection, gates, embeddings and scores."""
    import numpy as np
    from PIL import Image
    people = require_people(list_people(root))
    models = Models()
    images = []  # (label, rgb array, pil image, {size: faces})
    for label, paths in people.items():
        for p in paths:
            with Image.open(p) as im:
                pil = im.convert("RGB")
            images.append((label, pil, {size: models.detect(pil, size) for size in CANDIDATE_SIZES}))
    per_size = {size: {"images_with_face": sum(1 for _, _, d in images if d[size]),
                       "faces": sum(len(d[size]) for _, _, d in images)} for size in CANDIDATE_SIZES}
    any_face = [i for i, (_, _, d) in enumerate(images) if any(d.values())]
    chosen = next((s for s in CANDIDATE_SIZES if all(images[i][2][s] for i in any_face)), CANDIDATE_SIZES[-1])
    detection = {"images": len(images), "per_size": per_size, "chosen": chosen,
                 "no_face": len(images) - len(any_face)}

    faces = []
    for label, pil, dets in images:
        f = largest(dets[chosen])
        if f is None:
            continue
        m = fit(np.asarray(f[5], dtype=np.float32))
        if m is None:
            continue
        rgb = np.asarray(pil, dtype=np.uint8)
        aligned = to_rgb8(warp(rgb, m))
        faces.append({"label": label, "width": float(np.float32(f[2]) - np.float32(f[0])),
                      "luma": mean_luma(aligned), "blur": laplacian_variance(aligned),
                      "full_frame": pil.size == FULL_FRAME, "aligned": aligned})
    gates = suggest_gates(faces)

    rejected = {"too small": 0, "too dark": 0, "too blurry": 0}
    embeddings = {}
    for f in faces:
        if not f["width"] >= gates["min_width"]:
            rejected["too small"] += 1
        elif not f["luma"] >= gates["dark_floor"]:
            rejected["too dark"] += 1
        elif not f["blur"] >= gates["blur_floor"]:
            rejected["too blurry"] += 1
        else:
            crop = f["aligned"]
            if f["luma"] < gates["dim_level"]:
                crop = apply_table(crop, brighten_table(crop))
            embeddings.setdefault(f["label"], []).append(models.embed(crop))
    if len(embeddings) < 2:
        raise BenchError(f"!! only {len(embeddings)} person(s) had a usable face after detection and the "
                         "gates; the bench needs two.")
    analysis = analyse_scores(embeddings, given=given)
    for f in faces:
        del f["aligned"]
    return {"detection": detection, "faces": faces, "gates": gates, "rejected": rejected, "analysis": analysis}


# --- venv ---

def ensure_env(argv):
    """Re-run under tools/face-bench/venv (creating it) unless numpy/onnxruntime/pillow import here."""
    try:
        import numpy  # noqa: F401
        import onnxruntime  # noqa: F401
        import PIL  # noqa: F401
        return
    except ImportError:
        pass
    if os.environ.get("FACE_BENCH_IN_VENV"):
        raise BenchError(f"!! {VENV} is missing numpy/onnxruntime/pillow; delete it and re-run to rebuild it")
    if not VENV_PYTHON.exists():
        print(f"Creating {VENV.relative_to(REPO)} (onnxruntime 1.30, numpy, pillow); first run only.", flush=True)
        subprocess.run([sys.executable, "-m", "venv", str(VENV)], check=True)
        subprocess.run([str(VENV_PYTHON), "-m", "pip", "install", "--quiet"] + VENV_PACKAGES, check=True)
    os.environ["FACE_BENCH_IN_VENV"] = "1"
    os.execv(str(VENV_PYTHON), [str(VENV_PYTHON), str(Path(__file__).resolve())] + list(argv))


# --- adb ---

def adb(serial, *args, check=True, timeout=120):
    try:
        r = subprocess.run(["adb", "-s", serial] + list(args), capture_output=True, text=True, timeout=timeout)
    except FileNotFoundError:
        raise BenchError("!! adb not found on PATH (brew install android-platform-tools)")
    except subprocess.TimeoutExpired:
        raise BenchError(f"!! adb timed out: {' '.join(args)}. Is the robot on and on Wi-Fi?")
    if check and r.returncode != 0:
        raise BenchError(f"!! adb {' '.join(args)} failed: {(r.stderr or r.stdout).strip()}")
    return r


def connect(serial):
    if ":" in serial:
        subprocess.run(["adb", "connect", serial], capture_output=True, text=True, timeout=30)
    r = adb(serial, "get-state", check=False, timeout=30)
    if r.stdout.strip() != "device":
        raise BenchError(f"!! robot not reachable over adb at {serial}; check it is on, or pass --serial")


def pull(serial):
    connect(serial)
    r = adb(serial, "shell", "ls", REMOTE_FRAMES, check=False)
    if r.returncode != 0 or not r.stdout.strip():
        raise BenchError(
            f"!! no meeting frames on the robot ({REMOTE_FRAMES} is missing or empty).\n"
            f"   Turn capture on with `adb shell setprop {DEBUG_PROP} DEBUG`, restart Explore, and let him\n"
            "   meet people first. He keeps the last 50 meeting frames.")
    dest = CAPTURES / "_unsorted" / time.strftime("%Y%m%d-%H%M%S")
    dest.mkdir(parents=True, exist_ok=True)
    r = adb(serial, "pull", REMOTE_FRAMES, str(dest), check=False)
    if r.returncode != 0:  # adbd not running as root yet
        adb(serial, "root", check=False)
        connect(serial)
        adb(serial, "pull", REMOTE_FRAMES, str(dest))
    n = sum(1 for p in dest.rglob("*") if p.suffix.lower() in IMAGE_EXTS)
    print(f"Pulled {n} frame(s) into {dest.relative_to(REPO)}.\n"
          f"Sort them into {CAPTURES.relative_to(REPO)}/<initials>/ (one folder per person, initials only),\n"
          "drop frames with no face, then run: python3 scripts/face-bench.py run\n"
          f"Capture stays on until `adb shell setprop {DEBUG_PROP} INFO`.")
    return 0


def clean(serial=None):
    if CAPTURES.exists():
        shutil.rmtree(CAPTURES)
        print(f"Deleted {CAPTURES}.")
    else:
        print(f"Nothing to delete at {CAPTURES}.")
    if SUMMARY.exists():
        print(f"(The numbers-only summary stays at {SUMMARY}.)")
    if serial:
        connect(serial)
        adb(serial, "shell", "rm", "-rf", REMOTE_FRAMES)
        adb(serial, "shell", "setprop", DEBUG_PROP, "INFO", check=False)
        print(f"Deleted the robot's {FRAMES_DIR}/ and turned capture off.")
    return 0


# --- main ---

def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("pull", help="copy the robot's meeting frames into tools/face-bench/captures/_unsorted/")
    p.add_argument("--serial", default=DEFAULT_SERIAL)
    r = sub.add_parser("run", help="score a folder of per-person captures and suggest values")
    r.add_argument("dir", nargs="?", default=str(CAPTURES))
    r.add_argument("--out", default=str(SUMMARY), help=f"summary markdown (default {SUMMARY.relative_to(REPO)})")
    for flag in ("confident", "close", "margin"):
        r.add_argument(f"--{flag}", type=float, help=f"evaluate this {flag} value instead of suggesting one")
    c = sub.add_parser("clean", help="delete tools/face-bench/captures/")
    c.add_argument("--robot", action="store_true", help="also delete the frames on the robot and stop capture")
    c.add_argument("--serial", default=DEFAULT_SERIAL)
    args = ap.parse_args(argv)
    try:
        if args.cmd == "pull":
            return pull(args.serial)
        if args.cmd == "clean":
            return clean(args.serial if args.robot else None)
        given = None
        if any(v is not None for v in (args.confident, args.close, args.margin)):
            given = {k: (getattr(args, k) if getattr(args, k) is not None else DEFAULT_BANDS[k])
                     for k in ("confident", "close", "margin")}
        require_people(list_people(Path(args.dir)))
        ensure_env(argv)
        report = bench(Path(args.dir), given=given)
        text = render_summary(report["analysis"], report["gates"], report["detection"],
                              faces=report["faces"], rejected=report["rejected"])
        print(text)
        Path(args.out).parent.mkdir(parents=True, exist_ok=True)
        Path(args.out).write_text(text)
        print(f"Summary written to {args.out}. Folder labels appear in it: use initials.")
        if report["analysis"]["stop"]:
            print("!! STOP CONDITION 2: do not apply these bands; report the distributions.", file=sys.stderr)
            return 3
        return 0
    except BenchError as e:
        print(str(e), file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
