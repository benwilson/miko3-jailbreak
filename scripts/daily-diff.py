#!/usr/bin/env python3
"""Compare two robot days: what moved between them, per roaming hour, with sample counts.

Each day is one or more files (logcat captures or pulled learn.log files; a directory means
every *.log and *.txt in it). For each day this runs

  scripts/nav-report.py  --json -   navigation tallies and rates per roaming hour, escapes,
                                    turn accuracy, per-metre metrics from the leg records
  scripts/chat-report.py --json -   conversation latencies, unaddressed and cut-off rates,
                                    end reasons, tools, failures

and summarises the `trig:` records (one per decision a detection set off: kind, rule, label)
per roaming hour. It prints the metrics that moved (by --min-change, relative, and only where
either day has at least --min-n samples behind the number), each with both days' values and
sample counts, then the `git log` between the two days' builds when both start records
(`learn: start build=<sha>`) name a commit this repo knows.

The robot never reads this; it is for the coding agent deciding what the last change did.

Usage:
  scripts/daily-diff.py DAY_A DAY_B [--a-extra FILE ...] [--b-extra FILE ...] [--json out.json|-]
                        [--min-change 0.2] [--min-n 5] [--all] [--year 2026]
"""
import argparse
import datetime as dt
import json
import re
import subprocess
import sys
import tempfile
from collections import Counter
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent
TRIG_RE = re.compile(r"\bI/\S+\(\s*\d+\): trig: (.*)$")
START_RE = re.compile(r"\): learn: start build=(\S+)")
KV_RE = re.compile(r"(\w+)=(\S+)")


def files_of(paths):
    out = []
    for p in paths:
        p = Path(p)
        if p.is_dir():
            out += sorted(x for x in p.iterdir() if x.suffix in (".log", ".txt") and x.is_file())
        else:
            out.append(p)
    return [str(x) for x in out]


def run_json(script, files, year):
    """A report script's --json output for these files."""
    with tempfile.TemporaryDirectory() as empty:
        cmd = [sys.executable, str(HERE / script), *files, "--json", "-", "--year", str(year)]
        if script == "nav-report.py":
            cmd += ["--frames-dir", empty]  # frames add nothing to a diff; skip the scan
        r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        raise SystemExit(f"{script} failed: {r.stderr.strip()[-500:]}")
    return json.loads(r.stdout)


def merged_day(nav):
    """The nav report's days as one (a day's files may straddle midnight)."""
    days = nav.get("days", [])
    if len(days) == 1:
        return days[0]
    if not days:
        return {"time": {"roaming_s": 0.0}, "tally": {}, "escapes": {}, "turns": {}, "distance": {}}
    out = {"time": {"roaming_s": sum(d["time"]["roaming_s"] for d in days)}, "tally": Counter(),
           "escapes": {}, "turns": {}, "distance": {}}
    for d in days:
        out["tally"].update(d["tally"])
    out["tally"] = dict(out["tally"])
    return out


def scan(files):
    """trig: records and build ids from the raw files."""
    trigs, builds = [], []
    for path in files:
        with open(path, errors="replace") as f:
            for raw in f:
                m = TRIG_RE.search(raw)
                if m:
                    trigs.append(dict(KV_RE.findall(m.group(1))))
                    continue
                m = START_RE.search(raw)
                if m and m.group(1) not in builds:
                    builds.append(m.group(1))
    return trigs, builds


def trig_summary(trigs):
    return {
        "total": len(trigs),
        "by_kind": dict(Counter(t.get("kind", "?") for t in trigs)),
        "by_rule": dict(Counter(f"{t.get('kind', '?')}/{t.get('rule', '?')}" for t in trigs)),
        "bathroom_labels": dict(Counter(t.get("label", "?") for t in trigs if t.get("kind") == "bathroom")),
    }


def metrics(nav_day, chat, trig):
    """Flat {name: (value, n, kind)}: kind "rate" (per roaming hour), "ratio" or "ms"."""
    hours = nav_day["time"]["roaming_s"] / 3600
    out = {"roaming_hours": (hours, None, "value")}
    for k, v in sorted(nav_day.get("tally", {}).items()):
        out[f"nav.{k}_per_h"] = (v / hours if hours > 0 else None, v, "rate")
    esc = nav_day.get("escapes") or {}
    if esc:
        out["nav.escape_success"] = (esc.get("success_rate"), esc.get("episodes"), "ratio")
        out["nav.escape_median_free_ms"] = (esc.get("median_free_ms"), esc.get("freed"), "ms")
    tr = nav_day.get("turns") or {}
    if tr:
        out["nav.turn_median_abs_err_deg"] = (tr.get("median_abs_err_deg"), tr.get("clean"), "value")
        out["nav.turn_within_10deg"] = (tr.get("within_10deg"), tr.get("clean"), "ratio")
    dist = nav_day.get("distance") or {}
    if dist and dist.get("metres"):
        out["nav.metres"] = (dist.get("metres"), dist.get("legs"), "value")
        out["nav.hazards_per_m"] = (dist.get("hazards_per_m"), dist.get("hazards"), "value")
        out["nav.stalls_per_m"] = (dist.get("stalls_per_m"), dist.get("stalls"), "value")
    out["chat.conversations_per_h"] = (chat["conversations"] / hours if hours > 0 else None, chat["conversations"], "rate")
    out["chat.unaddressed_rate"] = (chat["unaddressed_rate"], chat["turns"], "ratio")
    out["chat.cut_off_rate"] = (chat["cut_off_rate"], chat["conversations"], "ratio")
    out["chat.replied_rate"] = (chat.get("replied_rate"), chat["conversations"], "ratio")
    for kind, m in chat.get("by_meeting", {}).items():
        n = m["conversations"]
        out[f"chat.meet.{kind}_per_h"] = (n / hours if hours > 0 else None, n, "rate")
        out[f"chat.meet.{kind}.replied_rate"] = (m["replied"] / n if n else None, n, "ratio")
        out[f"chat.meet.{kind}.faceless_rate"] = (m["faceless"] / n if n else None, n, "ratio")
    for group in ("opener", "reply"):
        for stage, st in chat["latency"][group].items():
            for q in ("p50", "p95"):
                out[f"chat.{group}.{stage}_{q}_ms"] = (st[q], st["n"], "ms")
    cr = chat.get("claude_requests", {})
    out["chat.claude_ms_p50"] = (cr.get("ms_p50"), cr.get("n"), "ms")
    out["chat.claude_line_at_p50"] = (cr.get("line_at_p50"), cr.get("n"), "ms")
    for k, v in chat.get("end_reasons", {}).items():
        out[f"chat.end.{k}"] = (v / chat["conversations"] if chat["conversations"] else None, v, "ratio")
    for k, v in trig["by_rule"].items():
        out[f"trig.{k}_per_h"] = (v / hours if hours > 0 else None, v, "rate")
    return out


def compare(ma, mb, min_change=0.2, min_n=5, show_all=False):
    rows = []
    for k in sorted(set(ma) | set(mb)):
        va, na, kind = ma.get(k, (None, 0, None))
        vb, nb, kind_b = mb.get(k, (None, 0, None))
        kind = kind or kind_b
        if kind == "rate":
            # A count missing on one day is a count of zero there, not an unknown.
            va, vb = (0.0 if va is None and k not in ma else va), (0.0 if vb is None and k not in mb else vb)
        if va is None and vb is None:
            continue
        enough = (na is None and nb is None) or max(na or 0, nb or 0) >= min_n
        if va is None or vb is None:
            change, moved = None, True
        elif va == 0:
            change, moved = None, vb != 0
        else:
            change = (vb - va) / abs(va)
            moved = abs(change) >= min_change
        if show_all or (moved and enough):
            rows.append({"metric": k, "a": va, "a_n": na, "b": vb, "b_n": nb, "change": change, "enough": enough})
    return rows


def sha_of(build):
    m = re.match(r"^([0-9a-f]{7,40})(?:\+[0-9a-f]+)?$", build or "")
    return m.group(1) if m else None


def git_log(a_builds, b_builds):
    a = next((sha_of(x) for x in reversed(a_builds) if sha_of(x)), None)
    b = next((sha_of(x) for x in reversed(b_builds) if sha_of(x)), None)
    if not a or not b:
        return {"a": a, "b": b, "log": None, "why": "a build sha is unknown"}
    if a == b:
        return {"a": a, "b": b, "log": [], "why": "same commit (a dirty tree may still differ)"}
    r = subprocess.run(["git", "log", "--oneline", f"{a}..{b}"], cwd=str(REPO), capture_output=True, text=True)
    if r.returncode != 0:
        return {"a": a, "b": b, "log": None, "why": r.stderr.strip()[:200]}
    return {"a": a, "b": b, "log": r.stdout.splitlines(), "why": None}


def fmt(v, kind=None):
    if v is None:
        return "-"
    if kind == "ratio":
        return f"{100 * v:.0f}%"
    if isinstance(v, float):
        return f"{v:.2f}"
    return str(v)


def render(r):
    a, b = r["a"], r["b"]
    out = [f"# Daily diff: {', '.join(a['files'])}  ->  {', '.join(b['files'])}", "",
           f"Roaming hours: {a['metrics']['roaming_hours'][0]:.2f} -> {b['metrics']['roaming_hours'][0]:.2f}; "
           f"builds {a['builds'] or ['?']} -> {b['builds'] or ['?']}", "",
           "| metric | A | n | B | n | change |", "|---|---|---|---|---|---|"]
    kinds = {k: v[2] for k, v in {**a["metrics"], **b["metrics"]}.items()}
    for row in r["moved"]:
        k = kinds.get(row["metric"])
        ch = ("new" if not row["a"] else "-") if row["change"] is None else f"{100 * row['change']:+.0f}%"
        out.append(f"| {row['metric']} | {fmt(row['a'], k)} | {fmt(row['a_n'])} | {fmt(row['b'], k)} "
                   f"| {fmt(row['b_n'])} | {ch}{'' if row['enough'] else ' (few samples)'} |")
    if not r["moved"]:
        out.append("| (nothing moved past the threshold) | | | | | |")
    out += ["", "## Detector triggers", "", f"A: {a['trig']}", f"B: {b['trig']}", "", "## Code between the builds", ""]
    g = r["git"]
    if g["log"] is None:
        out.append(f"Unknown ({g['why']}).")
    elif not g["log"]:
        out.append(g["why"] or "No commits.")
    else:
        out += [f"    {x}" for x in g["log"]]
    return "\n".join(out) + "\n"


def day(files, year):
    nav = run_json("nav-report.py", files, year)
    chat = run_json("chat-report.py", files, year)
    trigs, builds = scan(files)
    trig = trig_summary(trigs)
    nd = merged_day(nav)
    return {"files": files, "builds": builds, "trig": trig, "metrics": metrics(nd, chat, trig), "chat": chat}


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("day_a", help="day A: a log or learn.log file, or a directory of them")
    ap.add_argument("day_b", help="day B: a log or learn.log file, or a directory of them")
    ap.add_argument("--a-extra", nargs="*", default=[], help="more files for day A")
    ap.add_argument("--b-extra", nargs="*", default=[], help="more files for day B")
    ap.add_argument("--json", help="JSON output path, or - for stdout")
    ap.add_argument("--min-change", type=float, default=0.2, help="relative change that counts as moved")
    ap.add_argument("--min-n", type=int, default=5, help="samples either day needs behind a moved metric")
    ap.add_argument("--all", action="store_true", help="list every metric, moved or not")
    ap.add_argument("--year", type=int, default=dt.date.today().year)
    args = ap.parse_args(argv)
    a = day(files_of([args.day_a, *args.a_extra]), args.year)
    b = day(files_of([args.day_b, *args.b_extra]), args.year)
    r = {"a": a, "b": b, "moved": compare(a["metrics"], b["metrics"], args.min_change, args.min_n, args.all),
         "git": git_log(a["builds"], b["builds"])}
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
