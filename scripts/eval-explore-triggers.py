#!/usr/bin/env python3
"""Replay the bathroom-privacy rule over a log's detector looks.

ExploreCamera logs every look it runs ("look in N ms: [label score [x0,y0,x1,y1], ...]").
This replays ExploreBrain's bathroom rule over those lines, so past logs can be checked for
false bathroom triggers after the rule or its thresholds change:

  * a strong label (toilet) at BATHROOM_STRONG_MIN or more in one look, or
  * two different weak labels (toilet paper, sink, mirror, soap, paper towel, towel, bathtub)
    at BATHROOM_WEAK_MIN or more within the last BATHROOM_WEAK_LOOKS looks and
    BATHROOM_WEAK_WINDOW_MS of the newest;
  * a box smaller than BATHROOM_MIN_AREA of the frame never counts.

The constants below mirror ExploreBrain.java; scripts/tests/test_eval_explore_triggers.py
reads the Java and fails when they drift. --strong-min, --weak-min and --min-area try other
values on the same looks.

Approximations: the look's log time stands in for its frame time; the brain weighs looks
only while roaming with the camera open (not docked), and after a trigger it is private (no
looks weighed) until it is out, which a replay cannot know, so after each trigger the replay
starts afresh. Each trigger is listed with whether the log itself shows a "bathroom: <labels>;
leaving" note within --match-s, so a changed rule's new and lost triggers stand out.

Usage:
  scripts/eval-explore-triggers.py LOG [LOG ...] [--json out.json|-] [--strong-min 0.35]
                                   [--weak-min 0.3] [--min-area 0.006] [--year 2026]
"""
import argparse
import datetime as dt
import importlib.util
import json
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent

# Mirrors ExploreBrain.BATHROOM_* (checked by scripts/tests/test_eval_explore_triggers.py).
BATHROOM_STRONG = ("toilet",)
BATHROOM_STRONG_MIN = 0.35
BATHROOM_WEAK = ("toilet paper", "sink", "mirror", "soap", "paper towel", "towel", "bathtub")
BATHROOM_WEAK_MIN = 0.3
BATHROOM_MIN_AREA = 0.006
BATHROOM_WEAK_LOOKS = 3
BATHROOM_WEAK_WINDOW_MS = 10000

LOOK_RE = re.compile(r"^look in \d+ ms: \[(.*)\]$")
BOX_RE = re.compile(r"\s*([^\[\],][^\[\]]*?) (\d+(?:\.\d+)?) \[([-\d.]+),([-\d.]+),([-\d.]+),([-\d.]+)\]")
LOGGED_RE = re.compile(r"^bathroom: (.+); leaving and beeping \(privacy\)$")


def _load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def parse_look(msg):
    """[(label, score, (x0, y0, x1, y1)), ...] of a "look in" line, or None for another line."""
    m = LOOK_RE.match(msg)
    if not m:
        return None
    return [(b.group(1).strip(), float(b.group(2)), tuple(float(b.group(i)) for i in range(3, 7)))
            for b in BOX_RE.finditer(m.group(1))]


def hits(boxes, strong_min, weak_min, min_area):
    """(strong, weak) qualifying boxes, each label once (its first box), in box order (bathroomHits)."""
    strong, weak = [], []
    for label, score, (x0, y0, x1, y1) in boxes:
        if (x1 - x0) * (y1 - y0) < min_area:
            continue
        if label in BATHROOM_STRONG and score >= strong_min and label not in [b[0] for b in strong]:
            strong.append((label, score, (x0, y0, x1, y1)))
        elif label in BATHROOM_WEAK and score >= weak_min and label not in [b[0] for b in weak]:
            weak.append((label, score, (x0, y0, x1, y1)))
    return strong, weak


def replay(looks, strong_min=BATHROOM_STRONG_MIN, weak_min=BATHROOM_WEAK_MIN, min_area=BATHROOM_MIN_AREA):
    """Triggers over [(time, pid, boxes)] in order: dicts of time, pid, rule, boxes, looks."""
    out, recent, pid = [], [], None
    for t, p, boxes in looks:
        if p != pid:
            recent, pid = [], p
        strong, weak = hits(boxes, strong_min, weak_min, min_area)
        recent.append((t, weak))
        while len(recent) > BATHROOM_WEAK_LOOKS or (t - recent[0][0]).total_seconds() * 1000 > BATHROOM_WEAK_WINDOW_MS:
            recent.pop(0)
        if strong:
            out.append({"time": t, "pid": p, "rule": "strong", "boxes": strong, "looks": 1})
            recent = []
            continue
        seen, looks_n = [], 0
        for _, w in recent:
            added = False
            for b in w:
                if b[0] not in [s[0] for s in seen]:
                    seen.append(b)
                    added = True
            looks_n += added
        if len(seen) >= 2:
            out.append({"time": t, "pid": p, "rule": "weak2", "boxes": seen, "looks": looks_n})
            recent = []
    return out


def evaluate(lines, strong_min=BATHROOM_STRONG_MIN, weak_min=BATHROOM_WEAK_MIN, min_area=BATHROOM_MIN_AREA,
             match_s=3.0):
    looks, logged = [], []
    for p in lines:
        if p.tag == "ExploreCamera":
            boxes = parse_look(p.msg)
            if boxes is not None:
                looks.append((p.ts, p.pid, boxes))
        elif LOGGED_RE.match(p.msg):
            logged.append(p.ts)
    trig = replay(looks, strong_min, weak_min, min_area)
    for x in trig:
        x["logged"] = any(abs((x["time"] - t).total_seconds()) <= match_s for t in logged)
    missed = [t for t in logged if not any(abs((x["time"] - t).total_seconds()) <= match_s for x in trig)]
    return {"looks": len(looks), "triggers": trig, "logged_triggers": len(logged),
            "logged_not_replayed": [t.isoformat(sep=" ") for t in missed],
            "thresholds": {"strong_min": strong_min, "weak_min": weak_min, "min_area": min_area,
                           "weak_looks": BATHROOM_WEAK_LOOKS, "weak_window_ms": BATHROOM_WEAK_WINDOW_MS}}


def render(r):
    out = [f"{r['looks']} looks; {len(r['triggers'])} would trigger; {r['logged_triggers']} triggered in the log "
           f"(thresholds {r['thresholds']})"]
    for x in r["triggers"]:
        boxes = ", ".join(f"{lab} {s:.2f} [{','.join(f'{v:.2f}' for v in b)}]" for lab, s, b in x["boxes"])
        out.append(f"{x['time']:%m-%d %H:%M:%S.%f}"[:-3] + f" pid {x['pid']} {x['rule']} looks={x['looks']}"
                   f" {'logged' if x['logged'] else 'NEW'}: {boxes}")
    for t in r["logged_not_replayed"]:
        out.append(f"{t} logged a bathroom trigger the replay does not make")
    return "\n".join(out) + "\n"


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("logs", nargs="+", help="logcat captures with ExploreCamera look lines")
    ap.add_argument("--json", help="JSON output path, or - for stdout")
    ap.add_argument("--strong-min", type=float, default=BATHROOM_STRONG_MIN)
    ap.add_argument("--weak-min", type=float, default=BATHROOM_WEAK_MIN)
    ap.add_argument("--min-area", type=float, default=BATHROOM_MIN_AREA)
    ap.add_argument("--match-s", type=float, default=3.0, help="a logged trigger this close is the same one")
    ap.add_argument("--year", type=int, default=dt.date.today().year)
    ap.add_argument("--tz", default="America/Los_Angeles")
    args = ap.parse_args(argv)
    nav = _load("nav_report_for_triggers", HERE / "nav-report.py")
    raws = []
    for path in args.logs:
        with open(path, errors="replace") as f:
            raws.extend(f)
    r = evaluate(nav.parse_lines(raws, args.year, args.tz), args.strong_min, args.weak_min, args.min_area, args.match_s)
    if args.json != "-":
        sys.stdout.write(render(r))
    if args.json:
        text = json.dumps(r, indent=1, default=str)
        if args.json == "-":
            sys.stdout.write(text + "\n")
        else:
            Path(args.json).write_text(text + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
