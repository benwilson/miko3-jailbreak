#!/usr/bin/env python3
"""Offline evaluation of Explore's openness scorer on real robot frames.

Runs the real Java Openness (compiled for the host JVM with the openness
harness fixture's OpennessEval main) over a hand-labelled set of frames and
compares its 16 bins with the labels: per bin, is that direction drivable
floor for the next stretch (1), blocked (0), or unclear (?, not scored).

Labels, by eye on zoomed frame bottoms. The camera tilts up ~16 degrees from
~15 cm, so the frame's bottom row is already ~1 m ahead and the horizon sits
near 0.82 of the height: "drivable for the next ~1 m" is whether floor shows
at the bottom of the column.
  1  floor at the bottom of the column, running up past ~0.96 of the frame
     height (free for ~1.3 m or more);
  0  a standing surface (wall, desk side, chair leg, pot, backpack) reaches
     into the bottom ~4% of the frame: within ~1.2 m, or no floor at all;
  ?  in between, a bin split between the two, or too dark to tell.

Run it with the detector venv (cv2 + numpy):

    tools/detector-export/.venv/bin/python scripts/eval-explore-openness.py eval
    tools/detector-export/.venv/bin/python scripts/eval-explore-openness.py eval --per-frame
    tools/detector-export/.venv/bin/python scripts/eval-explore-openness.py sheet out/sheet.jpg a.jpg b.jpg ...
    tools/detector-export/.venv/bin/python scripts/eval-explore-openness.py import NAME src.jpg [--mask x0,y0,x1,y1]...

The labelled set lives under scripts/tests/fixtures/explore_openness_eval:
labels.json (name -> source, kind, 16-character label string) and frames/,
each frame at 160x120 (the size ExploreCamera decodes for openness) as PNG.
Frames with a person in them are pixelated over the person (--mask, in
640x480 source pixels) before they are stored: nothing identifiable in git.

Teaching conditions, each a fresh Openness per frame unless noted:
  untaught  nothing taught (what he sees before his first clean drive);
  self      taught from the frame's own bottom-centre patch, as if he had just
            driven over it, then scored;
  carried   one Openness through the whole set in label order, each frame
            scored and then taught (a model taught elsewhere);
  walltaught  taught first from two plain walls he faced on the roam, then
            each frame scored (the robot's state on 2026-10-01: the old
            scorer took the wall's colour for floor; the new one refuses).
A bin reads open when it scores above steerBlocked (ExploreTuning, 0.35).
"""
import argparse
import json
import subprocess
import sys
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
TESTS = REPO / "scripts" / "tests"
EVAL_DIR = TESTS / "fixtures" / "explore_openness_eval"
LABELS = EVAL_DIR / "labels.json"
FRAMES = EVAL_DIR / "frames"
HARNESS = TESTS / "fixtures" / "explore_openness_harness" / "src"
EVAL_MAIN = HARNESS / "com" / "miko3" / "mode" / "explore" / "OpennessEval.java"
EXPLORE_SRC = REPO / "mode-explore" / "src"
BINS = 16
OPEN_ABOVE = 0.35  # ExploreTuning steerBlocked
CONDITIONS = ("untaught", "self", "carried", "walltaught")
# Plain walls he faced on the roam: what the robot was taught on 2026-10-01.
WALL_TEACHERS = ("r492092", "r403028")
sys.path.insert(0, str(TESTS))
import jvm_harness  # noqa: E402


def load_labels():
    return json.loads(LABELS.read_text())


def run_java(names, src=EXPLORE_SRC):
    """{condition: {name: (confidence, [16 bins])}} and the mean ms per score, from OpennessEval."""
    jdk = jvm_harness.find_jdk()
    if jdk is None:
        sys.exit("no JDK found")
    with tempfile.TemporaryDirectory(prefix="openness_eval_") as out:
        c = subprocess.run(jvm_harness.javac_cmd(jdk[0], out, [EVAL_MAIN], [HARNESS, src]),
                           capture_output=True, text=True)
        if c.returncode != 0:
            sys.exit(c.stdout + c.stderr)
        args = [str(FRAMES / f"{n}.png") for n in names] + ["--walls"] + [str(FRAMES / f"{n}.png") for n in WALL_TEACHERS]
        r = subprocess.run([jdk[1], "-Djava.awt.headless=true", "-cp", out,
                            "com.miko3.mode.explore.OpennessEval"] + args,
                           capture_output=True, text=True, timeout=300)
        if r.returncode != 0:
            sys.exit(r.stdout + r.stderr)
    results = {c: {} for c in CONDITIONS}
    ms = None
    for line in r.stdout.splitlines():
        parts = line.split()
        if parts and parts[0] == "TIME":
            ms = float(parts[1])
        elif parts and parts[0] == "WALLPATCHES":
            results["wall_patches"] = int(parts[1])
        elif parts and parts[0] in results:
            cond, path, conf = parts[0], parts[1], float(parts[2])
            results[cond][Path(path).stem] = (conf, [float(v) for v in parts[3:3 + BINS]])
    return results, ms


def score(labels, results, names):
    """Counts over the scored bins of these frames: open/blocked labels vs prediction."""
    t = {"tp": 0, "tn": 0, "fp": 0, "fn": 0, "open_sum": 0.0, "blocked_sum": 0.0, "frames_all_zero": 0}
    for n in names:
        lab = labels[n]["open"]
        conf, bins = results[n]
        if max(bins) < 0.01:
            t["frames_all_zero"] += 1
        for b in range(BINS):
            if lab[b] == "?":
                continue
            truth = lab[b] == "1"
            pred = bins[b] > OPEN_ABOVE
            if truth:
                t["open_sum"] += bins[b]
                t["tp" if pred else "fn"] += 1
            else:
                t["blocked_sum"] += bins[b]
                t["fp" if pred else "tn"] += 1
    return t


def fmt(t):
    n = t["tp"] + t["tn"] + t["fp"] + t["fn"]
    opens = t["tp"] + t["fn"]
    blocks = t["tn"] + t["fp"]
    acc = (t["tp"] + t["tn"]) / n if n else 0
    fo = t["fp"] / blocks if blocks else 0
    fb = t["fn"] / opens if opens else 0
    mo = t["open_sum"] / opens if opens else 0
    mb = t["blocked_sum"] / blocks if blocks else 0
    return (f"acc {acc:.2f}  false-open {fo:.2f} ({t['fp']}/{blocks})  false-blocked {fb:.2f} ({t['fn']}/{opens})"
            f"  mean open {mo:.2f} blocked {mb:.2f}  all-zero frames {t['frames_all_zero']}")


def cmd_eval(a):
    labels = load_labels()
    names = list(labels)
    results, ms = run_java(names, Path(a.src) if a.src else EXPLORE_SRC)
    groups = {"all": names}
    for n in names:
        groups.setdefault(labels[n]["kind"], []).append(n)
    groups["doorway o1-o6"] = [n for n in names if n.startswith("o")]
    walls = [n for n in names if labels[n]["kind"] == "wall"]
    print(f"{len(names)} frames, {sum(c != '?' for n in names for c in labels[n]['open'])} scored bins;"
          f" {ms:.2f} ms per score (host, 160x120)" if ms is not None else "")
    print(f"walls taught as floor in the walltaught condition: {results.get('wall_patches')} patches")
    for cond in CONDITIONS:
        print(f"\n[{cond}]")
        for g, ns in groups.items():
            print(f"  {g:14s} ({len(ns):2d}) {fmt(score(labels, results[cond], ns))}")
        if walls:
            t = score(labels, results[cond], walls)
            print(f"  false-open on walls: {t['fp']}/{t['fp'] + t['tn']}")
    if a.per_frame:
        for cond in CONDITIONS:
            print(f"\n[{cond}] per frame: label / bins (x10, '.'=0) / errors (F false-open, b false-blocked)")
            for n in names:
                conf, bins = results[cond][n]
                s = "".join("." if v < 0.05 else str(min(9, int(v * 10))) for v in bins)
                err = "".join("F" if c == "0" and v > OPEN_ABOVE else "b" if c == "1" and v <= OPEN_ABOVE else " "
                              for c, v in zip(labels[n]["open"], bins))
                print(f"  {n:10s} {labels[n]['open']}  {s}  {err}  c{conf:.2f}")


def cmd_sheet(a):
    import cv2
    import numpy as np
    tiles = []
    for p in a.images:
        im = cv2.imread(p)
        if im is None:
            continue
        im = cv2.resize(im, (320, 240), interpolation=cv2.INTER_AREA)
        for b in range(1, BINS):
            x = b * 320 // BINS
            colour = (0, 255, 255) if b % 4 == 0 else (0, 200, 0)
            cv2.line(im, (x, 0), (x, 5), colour, 1)
            cv2.line(im, (x, 234), (x, 239), colour, 1)
        for b in range(BINS):
            cv2.putText(im, format(b, "x"), (b * 20 + 7, 16), cv2.FONT_HERSHEY_PLAIN, 0.8, (0, 255, 255), 1)
        cv2.putText(im, Path(p).stem, (4, 30), cv2.FONT_HERSHEY_PLAIN, 1.0, (0, 0, 255), 1)
        tiles.append(im)
    cols = a.cols
    while len(tiles) % cols:
        tiles.append(np.zeros_like(tiles[0]))
    rows = [np.hstack(tiles[i:i + cols]) for i in range(0, len(tiles), cols)]
    cv2.imwrite(a.out, np.vstack(rows), [cv2.IMWRITE_JPEG_QUALITY, 80])


def cmd_import(a):
    import cv2
    im = cv2.imread(a.src)
    for m in a.mask:
        x0, y0, x1, y1 = (int(v) for v in m.split(","))
        roi = im[y0:y1, x0:x1]
        small = cv2.resize(roi, (max(1, (x1 - x0) // 64), max(1, (y1 - y0) // 64)), interpolation=cv2.INTER_AREA)
        im[y0:y1, x0:x1] = cv2.resize(small, (x1 - x0, y1 - y0), interpolation=cv2.INTER_NEAREST)
    FRAMES.mkdir(parents=True, exist_ok=True)
    cv2.imwrite(str(FRAMES / f"{a.name}.png"), cv2.resize(im, (160, 120), interpolation=cv2.INTER_AREA))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    e = sub.add_parser("eval")
    e.add_argument("--per-frame", action="store_true")
    e.add_argument("--src", help="another mode-explore/src tree to evaluate (e.g. a checkout of the old scorer)")
    s = sub.add_parser("sheet")
    s.add_argument("out")
    s.add_argument("images", nargs="+")
    s.add_argument("--cols", type=int, default=4)
    i = sub.add_parser("import")
    i.add_argument("name")
    i.add_argument("src")
    i.add_argument("--mask", action="append", default=[], help="x0,y0,x1,y1 in source pixels, pixelated")
    a = ap.parse_args()
    {"eval": cmd_eval, "sheet": cmd_sheet, "import": cmd_import}[a.cmd](a)


if __name__ == "__main__":
    main()
