#!/usr/bin/env python3
"""Self-calibrate the Miko 3 camera's horizontal field of view from turns he
makes anyway.

Explore, with log.tag.MikoExploreFrames=DEBUG, saves each look's JPEG to
files/frames/frame-<wall ms>.jpg (a ring of ~400). The brain logs each measured
turn's start ("turn start: heading H deg, asking X deg DIR") and its result
after the coast ("measured turn: asked A deg, turned T deg, overshoot O deg").
For each unblocked turn of 5-30 deg, the last frame before its start and the
first after its end are phase-correlated (Hanning window, a band above the
floor to limit parallax); the horizontal shift du over tan(turned) is the focal
length fx in pixels, fitted robustly over all pairs:

    HFOV = 2 * atan(W / 2 / fx)      cameraHalfFovDeg = HFOV / 2

Run on the host with the detector venv (cv2 + numpy):

    tools/detector-export/.venv/bin/python scripts/calibrate-camera-fov.py \
        --serial 192.168.19.74:5555 --log out/explore-logcat.txt

Without --log it reads `adb logcat -d -v epoch` (the robot's buffer is small:
stream `adb logcat -v epoch > file` during the run instead). --frames DIR uses
already-pulled frames instead of pulling. The result goes to
out/camera-calibration.json.
"""
import argparse
import datetime as _dt
import json
import math
import re
import subprocess
import sys
import tarfile
from bisect import bisect_left
from dataclasses import dataclass, asdict
from pathlib import Path
from statistics import median

REPO = Path(__file__).resolve().parents[1]
PACKAGE = "com.miko3.mode.explore"
DEVICE_FRAMES = f"/data/data/{PACKAGE}/files/frames"
ASSUMED_HALF_FOV = 30.0

# "     1790886847.586 30891 30974 I ExploreBrain: message"
EPOCH_RE = re.compile(r"^\s*(\d+\.\d{3})\s+\d+\s+\d+\s+[VDIWEF]\s+([^:]+?)\s*:\s?(.*)$")
# "10-01 14:02:03.250  1 2 I ExploreBrain: message" (local time)
THREADTIME_RE = re.compile(
    r"^(\d\d)-(\d\d)\s+(\d\d):(\d\d):(\d\d)\.(\d{3})\s+\d+\s+\d+\s+[VDIWEF]\s+([^:]+?)\s*:\s?(.*)$")
START_RE = re.compile(r"^turn start: heading (-?\d+) deg, asking (\d+) deg (LEFT|RIGHT)")
MEASURED_RE = re.compile(r"^measured turn: asked (\d+) deg, turned (-?\d+) deg, overshoot (-?\d+) deg")
FRAME_RE = re.compile(r"^frame-(\d+)\.jpg$")


def parse_line(line, year=None):
    """(wall ms, tag, message) from a logcat line in -v epoch or threadtime form, else None."""
    m = EPOCH_RE.match(line)
    if m:
        return int(round(float(m.group(1)) * 1000)), m.group(2), m.group(3)
    m = THREADTIME_RE.match(line)
    if m:
        mo, d, hh, mi, ss, ms = (int(g) for g in m.groups()[:6])
        when = _dt.datetime(year or _dt.date.today().year, mo, d, hh, mi, ss, ms * 1000)
        return int(when.timestamp() * 1000), m.group(7), m.group(8)
    return None


@dataclass
class Turn:
    start_ms: int
    end_ms: int
    direction: str
    asked: float
    turned: float
    start_heading: float
    blocked: bool

    @property
    def signed_deg(self):
        """Heading change, positive LEFT (Heading.delta's convention)."""
        return self.turned if self.direction == "LEFT" else -self.turned


def parse_turns(lines, year=None):
    """Turns with a logged start and result, in order. The result comes after
    the coast. A turn that starts during another's coast ends that one, whose
    result is then logged after the new start: both are dropped (the first's
    end and the second's start are mid-motion). A start that never gets a
    result (cut off before it stopped) is dropped. Results match their start
    by the amount asked, else the latest start."""
    turns = []
    starts = []  # every start so far: dict with clean / done
    for line in lines:
        e = parse_line(line, year)
        if e is None or not e[1].startswith("ExploreBrain"):
            continue
        t, _, msg = e
        m = START_RE.match(msg)
        if m:
            starts.append(dict(start_ms=t, start_heading=float(m.group(1)), asking=int(m.group(2)),
                               direction=m.group(3), blocked=False, clean=True, done=False))
            continue
        open_ = [p for p in starts[-3:] if not p["done"]]
        if not open_:
            continue
        if msg.startswith("measured turn blocked"):
            open_[-1]["blocked"] = True
            continue
        m = MEASURED_RE.match(msg)
        if not m:
            continue
        asked = int(m.group(1))
        same = [p for p in open_ if p["asking"] == asked]
        p = same[0] if same else open_[-1]
        p["done"] = True
        if p is not starts[-1]:
            # Its coast ran into a later start: that one began mid-motion.
            p["clean"] = False
            for later in starts[starts.index(p) + 1:]:
                later["clean"] = False
        if p["clean"]:
            turns.append(Turn(start_ms=p["start_ms"], end_ms=t, direction=p["direction"], asked=float(asked),
                              turned=float(m.group(2)), start_heading=p["start_heading"], blocked=p["blocked"]))
    return turns


@dataclass
class Pair:
    turn: Turn
    before_ms: int
    after_ms: int


def pair_frames(turns, frame_times, min_deg=5.0, max_deg=30.0, max_gap_ms=1500, min_after_ms=0):
    """For each unblocked turn of min..max deg: the last frame before its start
    and the first at least min_after_ms after its end, each within max_gap_ms.
    min_after_ms covers the camera's latency: a frame's arrival time is later
    than its exposure (long exposures in a dim room, several frames in flight),
    so a frame arriving just after the turn can still show it mid-way."""
    times = sorted(frame_times)
    pairs = []
    for t in turns:
        if t.blocked or not (min_deg <= abs(t.turned) <= max_deg):
            continue
        i = bisect_left(times, t.start_ms) - 1
        j = bisect_left(times, t.end_ms + min_after_ms)
        if i < 0 or j >= len(times):
            continue
        before, after = times[i], times[j]
        if t.start_ms - before > max_gap_ms or after - t.end_ms > max_gap_ms:
            continue
        pairs.append(Pair(t, before, after))
    return pairs


# ---- images ----

def _band(img, top, bottom):
    h = img.shape[0]
    return img[int(top * h):int(bottom * h), :]


def measure_shift(a, b, top=0.0, bottom=1.0):
    """(du, response): how far b's content sits to the right of a's, in pixels,
    from windowed phase correlation of rows top..bottom (fractions of height)."""
    import cv2
    import numpy as np
    if a.ndim == 3:
        a = cv2.cvtColor(a, cv2.COLOR_BGR2GRAY)
    if b.ndim == 3:
        b = cv2.cvtColor(b, cv2.COLOR_BGR2GRAY)
    fa = _band(a, top, bottom).astype(np.float64)
    fb = _band(b, top, bottom).astype(np.float64)
    fa -= fa.mean()
    fb -= fb.mean()
    win = cv2.createHanningWindow((fa.shape[1], fa.shape[0]), cv2.CV_64F)
    (dx, _dy), response = cv2.phaseCorrelate(fa, fb, win)
    return float(dx), float(response)


# ---- the gyro-free estimate: the rotation homography ----

def yaw_about_tilted_axis(yaw_deg, pitch_deg):
    """World-to-camera rotation of a camera pitched up by pitch_deg that has
    turned yaw_deg about the world vertical (positive LEFT). Camera axes: x
    right, y down, z forward."""
    import numpy as np
    p, y = math.radians(pitch_deg), math.radians(yaw_deg)
    rx = np.array([[1, 0, 0], [0, math.cos(p), math.sin(p)], [0, -math.sin(p), math.cos(p)]])
    ry = np.array([[math.cos(y), 0, math.sin(y)], [0, 1, 0], [-math.sin(y), 0, math.cos(y)]])
    return rx @ ry


def _camera(f, w, h):
    import numpy as np
    return np.array([[f, 0, w / 2], [0, f, h / 2], [0, 0, 1.0]])


def rotation_from_homography(H, w, h, f_lo=200.0, f_hi=1600.0):
    """(f, err, R): the focal length that makes K^-1 H K closest to a rotation
    (principal point at the centre, square pixels), its orthogonality error,
    and the nearest rotation."""
    import numpy as np

    def err(f):
        K = _camera(f, w, h)
        M = np.linalg.inv(K) @ H @ K
        d = np.linalg.det(M)
        if d <= 0:
            return 1e9, None
        M = M / np.cbrt(d)
        return float(np.linalg.norm(M.T @ M - np.eye(3))), M

    grid = np.exp(np.linspace(math.log(f_lo), math.log(f_hi), 300))
    errs = [err(f)[0] for f in grid]
    i = int(np.argmin(errs))
    lo, hi = grid[max(0, i - 1)], grid[min(len(grid) - 1, i + 1)]
    for _ in range(60):  # golden-section refine
        m1, m2 = lo + (hi - lo) * 0.382, lo + (hi - lo) * 0.618
        if err(m1)[0] < err(m2)[0]:
            hi = m2
        else:
            lo = m1
    f = (lo + hi) / 2
    e, M = err(f)
    U, _, Vt = np.linalg.svd(M)
    R = U @ Vt
    if np.linalg.det(R) < 0:
        R = -R
    return f, e, R


def measure_rotation(a, b, min_inliers=12):
    """Feature matches (SIFT, ratio test) and a RANSAC homography between two
    frames of a turn in place, read as a rotation: f, the signed turn (positive
    LEFT), the camera's pitch from the rotation axis, inlier count, orthogonality
    error, reprojection RMS with the fitted K R K^-1, and the centre pixel's
    horizontal shift. None when the frames don't match."""
    import cv2
    import numpy as np
    if a.ndim == 3:
        a = cv2.cvtColor(a, cv2.COLOR_BGR2GRAY)
    if b.ndim == 3:
        b = cv2.cvtColor(b, cv2.COLOR_BGR2GRAY)
    sift = cv2.SIFT_create(2000)
    ka, da = sift.detectAndCompute(a, None)
    kb, db = sift.detectAndCompute(b, None)
    if da is None or db is None or len(ka) < min_inliers or len(kb) < min_inliers:
        return None
    knn = cv2.BFMatcher().knnMatch(da, db, k=2)
    good = [m[0] for m in knn if len(m) == 2 and m[0].distance < 0.75 * m[1].distance]
    if len(good) < min_inliers:
        return None
    A = np.float64([ka[g.queryIdx].pt for g in good])
    B = np.float64([kb[g.trainIdx].pt for g in good])
    H, mask = cv2.findHomography(A, B, cv2.RANSAC, 3.0)
    if H is None or int(mask.sum()) < min_inliers:
        return None
    keep = mask.ravel() > 0
    h, w = a.shape[:2]
    f, e, R = rotation_from_homography(H, w, h)
    K = _camera(f, w, h)
    Hf = K @ R @ np.linalg.inv(K)
    pa = np.c_[A[keep], np.ones(keep.sum())] @ Hf.T
    proj = pa[:, :2] / pa[:, 2:3]
    reproj = float(np.sqrt(np.mean(np.sum((proj - B[keep]) ** 2, axis=1))))
    angle = math.degrees(math.acos(max(-1.0, min(1.0, (np.trace(R) - 1) / 2))))
    axis = np.array([R[2, 1] - R[1, 2], R[0, 2] - R[2, 0], R[1, 0] - R[0, 1]])
    n = np.linalg.norm(axis)
    axis = axis / n if n > 1e-12 else np.array([0.0, 1.0, 0.0])
    if axis[1] < 0:  # orient along the camera's +y (down): then +angle is LEFT
        axis, angle = -axis, -angle
    pitch = math.degrees(math.atan2(-axis[2], axis[1]))
    c = Hf @ np.array([w / 2, h / 2, 1.0])
    return {"f": f, "orth_err": e, "angle_deg": angle, "pitch_deg": pitch, "axis": axis.round(4).tolist(),
            "inliers": int(keep.sum()), "matches": len(good), "reproj_px": reproj,
            "du_centre": float(c[0] / c[2] - w / 2)}


def edge_yaw_deg(f, pitch_deg, width):
    """The turn that brings the centre row's edge pixel straight ahead, for a
    camera pitched up by pitch_deg turning about the world vertical."""
    x = (width / 2) / f
    return math.degrees(math.atan2(x, math.cos(math.radians(pitch_deg))))


def pixel_yaw_deg(f, pitch_deg, width, u):
    """As edge_yaw_deg, for pixel column u on the centre row (signed, right positive)."""
    x = (u - width / 2) / f
    return math.degrees(math.atan2(x, math.cos(math.radians(pitch_deg))))


@dataclass
class Sample:
    deg: float        # heading change, positive LEFT
    du: float         # image shift, positive: content moved right
    response: float
    label: str


def fit_focal(samples, width, reject=0.15):
    """Robust fit of du = sign * fx * tan(deg). Per-pair fx = |du| / tan|deg|; pairs
    whose fx is more than `reject` from the median, or whose shift has the
    minority sign, are rejected; fx is the least-squares slope over the rest."""
    usable = [s for s in samples if abs(s.deg) > 0.5 and abs(s.du) > 0.5]
    if len(usable) < 3:
        raise ValueError(f"need at least 3 usable pairs, have {len(usable)}")
    signs = [1 if (s.du > 0) == (s.deg > 0) else -1 for s in usable]
    sign = 1 if signs.count(1) >= signs.count(-1) else -1
    per = [(s, abs(s.du) / math.tan(math.radians(abs(s.deg)))) for s in usable]
    med = median(fx for s, fx in per if (1 if (s.du > 0) == (s.deg > 0) else -1) == sign)
    inliers, rejected = [], []
    for s, fx in per:
        same = (1 if (s.du > 0) == (s.deg > 0) else -1) == sign
        (inliers if same and abs(fx - med) <= reject * med else rejected).append((s, fx))
    if len(inliers) < 3:
        raise ValueError(f"only {len(inliers)} pairs agree")
    num = sum(sign * s.du * math.tan(math.radians(s.deg)) for s, _ in inliers)
    den = sum(math.tan(math.radians(s.deg)) ** 2 for s, _ in inliers)
    fx = num / den
    fxs = sorted(f for _, f in inliers)
    q = lambda p: fxs[min(len(fxs) - 1, max(0, int(round(p * (len(fxs) - 1)))))]
    hfov = lambda f: 2 * math.degrees(math.atan(width / 2 / f))
    residuals = [{"label": s.label, "deg": s.deg, "du": round(s.du, 2),
                  "predicted_du": round(sign * fx * math.tan(math.radians(s.deg)), 2),
                  "residual_px": round(s.du - sign * fx * math.tan(math.radians(s.deg)), 2),
                  "residual_deg": round(math.degrees(math.atan(sign * s.du / fx)) - s.deg, 2),
                  "fx": round(f, 1), "response": round(s.response, 3)} for s, f in inliers]
    rms_deg = math.sqrt(sum(r["residual_deg"] ** 2 for r in residuals) / len(residuals))
    return {
        "fx_px": fx,
        "hfov_deg": hfov(fx),
        "half_fov_deg": hfov(fx) / 2,
        "hfov_iqr_deg": [hfov(q(0.75)), hfov(q(0.25))],
        "hfov_median_pair_deg": hfov(median(fxs)),
        "px_per_deg": fx * math.tan(math.radians(1)),
        "sign": sign,
        "n_used": len(inliers),
        "rms_residual_deg": rms_deg,
        "residuals": residuals,
        "rejected": [s.label for s, _ in rejected],
    }


def turn_bias(turns):
    """turned - asked over unblocked turns: a consistent under- or overshoot."""
    errs = [(t.turned - t.asked, t) for t in turns if not t.blocked and t.turned > 0]
    if not errs:
        return {"n": 0}
    by_dir = {}
    for d in ("LEFT", "RIGHT"):
        e = [x for x, t in errs if t.direction == d]
        if e:
            by_dir[d] = {"n": len(e), "median_error_deg": median(e)}
    ratios = [t.turned / t.asked for _, t in errs if t.asked > 0]
    return {"n": len(errs), "median_error_deg": median(x for x, _ in errs),
            "mean_error_deg": sum(x for x, _ in errs) / len(errs),
            "median_ratio": median(ratios) if ratios else None, "by_direction": by_dir}


# ---- device ----

def adb(serial, *args, **kw):
    cmd = ["adb"] + (["-s", serial] if serial else []) + list(args)
    return subprocess.run(cmd, capture_output=True, check=True, **kw).stdout


def pull_frames(serial, dest):
    """Snapshot the ring on the robot into one tar, then adb pull it: streaming
    tar over exec-out while Explore keeps writing frames came back damaged.
    Each frame is ~440 KB (the MTK HAL adds ~300 KB of APP5-APP8 data), so a
    full ring is ~170 MB: ~30 s over Wi-Fi adb."""
    dest.mkdir(parents=True, exist_ok=True)
    snap = "/data/local/tmp/explore-frames.tar"
    parent, name = DEVICE_FRAMES.rsplit("/", 1)
    adb(serial, "shell", f"tar -cf {snap} -C {parent} {name}", timeout=300)
    local = dest / "frames.tar"
    try:
        adb(serial, "pull", snap, str(local), timeout=600)
    finally:
        adb(serial, "shell", f"rm -f {snap}", timeout=60)
    with tarfile.open(local) as tar:
        for m in tar.getmembers():
            fname = Path(m.name).name
            if m.isfile() and FRAME_RE.match(fname):
                (dest / fname).write_bytes(tar.extractfile(m).read())
    local.unlink()
    return dest


def frame_times(frames_dir):
    out = {}
    for p in Path(frames_dir).iterdir():
        m = FRAME_RE.match(p.name)
        if m:
            out[int(m.group(1))] = p
    return out


def good_rotation(m, min_inliers=30, max_orth=0.05, max_reproj=6.0, min_angle=5.0):
    """A rotation-homography measurement clean enough to trust."""
    return (m is not None and m["inliers"] >= min_inliers and m["orth_err"] <= max_orth
            and m["reproj_px"] <= max_reproj and abs(m["angle_deg"]) >= min_angle)


def summarize_rotations(ms, width):
    """Median focal, pitch and their spread over trusted measurements."""
    fs = sorted(m["f"] for m in ms)
    pitches = sorted(m["pitch_deg"] for m in ms)
    q = lambda xs, p: xs[min(len(xs) - 1, max(0, int(round(p * (len(xs) - 1)))))]
    f = median(fs)
    pitch = median(pitches)
    hfov = lambda f_: 2 * math.degrees(math.atan(width / 2 / f_))
    return {"n": len(ms), "fx_px": f, "fx_iqr": [q(fs, 0.25), q(fs, 0.75)],
            "hfov_deg": hfov(f), "hfov_iqr_deg": [hfov(q(fs, 0.75)), hfov(q(fs, 0.25))],
            "pitch_deg": pitch, "pitch_iqr_deg": [q(pitches, 0.25), q(pitches, 0.75)],
            "edge_yaw_deg": edge_yaw_deg(f, pitch, width),
            "px_per_deg_centre": f * math.tan(math.radians(1)) * math.cos(math.radians(pitch))}


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--serial", default=None)
    ap.add_argument("--log", type=Path, help="logcat dump (-v epoch or threadtime); default: adb logcat -d")
    ap.add_argument("--frames", type=Path, help="frames already pulled; default: pull from the robot")
    ap.add_argument("--out", type=Path, default=REPO / "out" / "camera-calibration.json")
    ap.add_argument("--min-deg", type=float, default=5)
    ap.add_argument("--max-deg", type=float, default=30, help="phase-correlation pairs (need overlap)")
    ap.add_argument("--max-deg-homography", type=float, default=60,
                    help="feature-matched pairs tolerate bigger turns")
    ap.add_argument("--max-gap-ms", type=int, default=2500)
    ap.add_argument("--min-after-ms", type=int, default=0,
                    help="skip frames arriving sooner than this after the turn's end (camera latency)")
    ap.add_argument("--min-response", type=float, default=0.08)
    ap.add_argument("--band", type=float, nargs=2, default=(0.05, 0.65),
                    help="rows to phase-correlate, fractions of height (default above the near floor)")
    ap.add_argument("--year", type=int, default=None, help="year for threadtime logs")
    args = ap.parse_args(argv)

    import cv2

    if args.log:
        # A capture restarted with >> repeats the buffer: keep each line once, in order.
        lines = list(dict.fromkeys(args.log.read_text(errors="replace").splitlines()))
    else:
        lines = adb(args.serial, "logcat", "-d", "-v", "epoch").decode(errors="replace").splitlines()
    frames_dir = args.frames or pull_frames(
        args.serial, REPO / "out" / f"camera-frames-{_dt.datetime.now():%Y%m%d-%H%M%S}")
    frames = frame_times(frames_dir)
    turns = parse_turns(lines, args.year)
    print(f"frames {len(frames)} ({frames_dir}), turns logged {len(turns)} "
          f"(blocked {sum(t.blocked for t in turns)}, zero {sum(t.turned == 0 for t in turns)})")
    width = 640
    read = lambda ms: cv2.imread(str(frames[ms]), cv2.IMREAD_GRAYSCALE)

    # 1. Rotation homography over turn pairs: f and the true turn, no gyro needed.
    hpairs = pair_frames(turns, frames.keys(), args.min_deg, args.max_deg_homography, args.max_gap_ms,
                         args.min_after_ms)
    print(f"\n[rotation homography] turn pairs {args.min_deg:g}-{args.max_deg_homography:g} deg: {len(hpairs)}")
    trusted, gyro_rows = [], []
    for p in hpairs:
        a, b = read(p.before_ms), read(p.after_ms)
        if a is None or b is None:
            continue
        width = a.shape[1]
        m = measure_rotation(a, b)
        label = f"{p.turn.start_ms}:{p.turn.direction}{p.turn.turned:g}:+{p.after_ms - p.turn.end_ms}"
        if m is None:
            print(f"  {label:>30}  no match")
            continue
        ok = good_rotation(m)
        print(f"  {label:>30}  inliers {m['inliers']:3d}  f {m['f']:6.1f}  turn {m['angle_deg']:+6.1f} "
              f"(gyro {p.turn.signed_deg:+4.0f})  pitch {m['pitch_deg']:5.1f}  orth {m['orth_err']:.3f}  "
              f"reproj {m['reproj_px']:.2f}px" + ("" if ok else "  (rejected)"))
        if ok:
            m["label"] = label
            m["gyro_deg"] = p.turn.signed_deg
            m["asked_deg"] = p.turn.asked
            trusted.append(m)
            gyro_rows.append((m["angle_deg"], p.turn.signed_deg, p.turn.asked))

    # 2. Phase correlation (centre band) against the gyro: the classic fx = du / tan(turn).
    pairs = [p for p in hpairs if abs(p.turn.turned) <= args.max_deg]
    samples = []
    for p in pairs:
        a, b = read(p.before_ms), read(p.after_ms)
        if a is None or b is None:
            continue
        du, resp = measure_shift(a, b, *args.band)
        if resp >= args.min_response:
            samples.append(Sample(deg=p.turn.signed_deg, du=du, response=resp,
                                  label=f"{p.turn.start_ms}:{p.turn.direction}{p.turn.turned:g}"))

    result = {"frames": len(frames), "turns": len(turns), "width": width,
              "assumed_half_fov_deg": ASSUMED_HALF_FOV, "turn_bias": turn_bias(turns),
              "homography_pairs": len(hpairs), "homography": None, "phase_correlation": None}
    if len(trusted) >= 3:
        summary = summarize_rotations(trusted, width)
        ratios = [abs(i) / abs(g) for i, g, _ in gyro_rows if abs(g) > 0]
        diffs = [abs(g) - abs(i) for i, g, _ in gyro_rows]
        same_sign = sum(1 for i, g, _ in gyro_rows if (i > 0) == (g > 0))
        summary.update({
            "gyro_vs_image": {"median_ratio_image_over_gyro": median(ratios),
                              "median_gyro_minus_image_deg": median(diffs),
                              "median_asked_minus_image_deg": median(a_ - abs(i) for i, _, a_ in gyro_rows),
                              "sign_agrees": f"{same_sign}/{len(gyro_rows)}"},
            "pairs": trusted})
        result["homography"] = summary
        lo, hi = summary["hfov_iqr_deg"]
        print(f"\n  {summary['n']} trusted pairs: fx {summary['fx_px']:.1f} px "
              f"(IQR {summary['fx_iqr'][0]:.0f}-{summary['fx_iqr'][1]:.0f})")
        print(f"  HFOV {summary['hfov_deg']:.1f} deg (IQR {lo:.1f}-{hi:.1f}); assumed {2 * ASSUMED_HALF_FOV:.0f}")
        print(f"  camera pitched up {summary['pitch_deg']:.1f} deg; a box at the centre row's edge is "
              f"{summary['edge_yaw_deg']:.1f} deg of turn; {summary['px_per_deg_centre']:.2f} px per deg at the centre")
        g = summary["gyro_vs_image"]
        print(f"  gyro vs image: image/gyro {g['median_ratio_image_over_gyro']:.2f}, gyro - image "
              f"{g['median_gyro_minus_image_deg']:+.1f} deg, asked - image {g['median_asked_minus_image_deg']:+.1f} deg, "
              f"sign agrees {g['sign_agrees']}")
    else:
        print(f"  only {len(trusted)} trusted pairs: no homography estimate")

    print(f"\n[phase correlation vs gyro] pairs <= {args.max_deg:g} deg with response >= {args.min_response}: "
          f"{len(samples)}")
    try:
        fit = fit_focal(samples, width)
    except ValueError as e:
        print(f"  no fit: {e}")
    else:
        result["phase_correlation"] = fit
        print(f"  fx {fit['fx_px']:.1f} px over {fit['n_used']} pairs (rejected {len(fit['rejected'])}), "
              f"HFOV {fit['hfov_deg']:.1f} deg, rms residual {fit['rms_residual_deg']:.2f} deg")
        left = "right (+x)" if fit["sign"] > 0 else "left (-x)"
        print(f"  sign: a LEFT turn (heading +) moves the image content {left}")

    b = result["turn_bias"]
    if b.get("n"):
        print(f"\nturn bias over {b['n']} unblocked turns (gyro turned - asked): median {b['median_error_deg']:+.1f} deg, "
              f"ratio {b['median_ratio']:.2f}, {b['by_direction']}")
    h = result["homography"]
    if h:
        result["recommended_camera_half_fov_deg"] = round(h["edge_yaw_deg"], 1)
        print(f"\nrecommended cameraHalfFovDeg = {h['edge_yaw_deg']:.1f} "
              f"(plain HFOV/2 {h['hfov_deg'] / 2:.1f}; the pitch widens the centre row's yaw)")
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(result, indent=2, default=lambda o: asdict(o)))
    print(f"wrote {args.out}")
    return 0 if h else 1


if __name__ == "__main__":
    sys.exit(main())
