#!/usr/bin/env python3
"""The voice mode's QA report (plan U10, KTD11), computed offline from the relay's
per-conversation JSONL logs (relay.logging), as fetched by scripts/qa-voice-mode.py.

    python -m relay.report relay/out/conversations            # Markdown
    python -m relay.report a.jsonl b.jsonl --json --calibration-ms 940

Per-turn latency is the KTD11 sum of durations, each measured on one clock:
user_end_to_first_chunk_ms (relay) + robot_first_chunk_to_play_ms (robot) +
half_ping_rtt_ms (link). A turn missing any of the three is listed as incomplete and left
out of the percentiles, never counted as zero. The perceived figure (from the person's last
word) adds the model's own endpointing delay before user_end, which the logs do not carry:
it is "not measured" unless every complete turn has an endpointing_ms field. The acoustic
calibration (a phone recording of the room, measured by hand) is the only real measure of
it; --calibration-ms puts that gap beside the software figure.

Also reported: model `flush` events with no user text within a second after them
(self-interruption, the signal for choosing strict turn-taking), underruns, sustained model
input backlog (a model-host capacity alarm), close reasons, and pass/fail per success
criterion. Standard library only, so the QA script can import it with the system Python.
"""
import argparse
import json
import sys
from pathlib import Path

LATENCY_FIELDS = ("user_end_to_first_chunk_ms", "robot_first_chunk_to_play_ms", "half_ping_rtt_ms")
LATENCY_BAR_MS = 1000.0  # Success Criteria: reply audio within about one second of user_end
FIVE_MINUTES_MS = 5 * 60 * 1000
SELF_INTERRUPT_WINDOW_MS = 1000.0  # a flush with no user text this soon after it
BACKLOG_FRAMES = 5.0  # sustained input backlog above this many frames ...
BACKLOG_TURNS = 3  # ... for this many consecutive turns is a capacity alarm
FRAME_MS = 80.0  # one uplink chunk; the model's stats report input_backlog in seconds
# A conversation ending any other way (link_lost, model_error, no close) dropped out.
CLEAN_CLOSE_REASONS = frozenset({"sleep_word", "silence", "farewell_timeout", "robot_request"})
NO_CLOSE = "(no close)"
USER_TEXT_TYPES = ("user_text", "user_text_delta")


def percentile(values, p):
    """The p-th percentile (0-100) by linear interpolation between closest ranks (numpy's
    default); None for no values."""
    if not values:
        return None
    ordered = sorted(values)
    rank = (len(ordered) - 1) * p / 100.0
    low = int(rank)
    high = min(low + 1, len(ordered) - 1)
    return ordered[low] + (ordered[high] - ordered[low]) * (rank - low)


def _number(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def _log_files(paths):
    """Every .jsonl file the paths name (a directory means its *.jsonl), each once, in
    the order given; a directory's files in name order, which is conversation-id order."""
    files, seen = [], set()
    for p in map(Path, paths):
        if p.is_dir():
            found = sorted(p.glob("*.jsonl"))
        elif p.is_file():
            found = [p]
        else:
            raise FileNotFoundError(f"no such log file or directory: {p}")
        for f in found:
            key = f.resolve()
            if key not in seen:
                seen.add(key)
                files.append(f)
    return files


def _read_records(path):
    records, bad = [], 0
    with open(path, encoding="utf-8") as f:
        for line in f:
            if not line.strip():
                continue
            try:
                record = json.loads(line)
            except ValueError:
                bad += 1
                continue
            if not isinstance(record, dict):
                bad += 1
                continue
            records.append(record)
    return records, bad


def _time_ms(record):
    """The record's time on the relay's clock: rt_ms, else wall-clock ts."""
    if _number(record.get("rt_ms")):
        return float(record["rt_ms"])
    if _number(record.get("ts")):
        return float(record["ts"]) * 1000.0
    return None


def _backlog_frames(stats, frame_ms):
    """Input backlog in frames from a model stats frame, or None: an explicit
    input_backlog_frames is used as is, else input_backlog (seconds) / frame length."""
    if not isinstance(stats, dict):
        return None
    if _number(stats.get("input_backlog_frames")):
        return float(stats["input_backlog_frames"])
    if _number(stats.get("input_backlog")):
        return round(stats["input_backlog"] * 1000.0 / frame_ms, 2)
    return None


def _count_self_interruptions(records, window_ms):
    """(flushes, flushes with no non-blank user text within window_ms after them)."""
    flushes, texts = [], []
    for r in records:
        msg = r.get("msg")
        if r.get("ev") != "model" or not isinstance(msg, dict):
            continue
        t = _time_ms(r)
        if msg.get("type") == "flush":
            flushes.append(t)
        elif msg.get("type") in USER_TEXT_TYPES and str(msg.get("text") or "").strip():
            texts.append(t)
    lonely = 0
    for f in flushes:
        if f is None or not any(t is not None and f <= t <= f + window_ms for t in texts):
            lonely += 1
    return len(flushes), lonely


def _analyze_conversation(path, latency_turns, frame_ms, backlog_frames, backlog_turns):
    records, bad = _read_records(path)
    conv = {"id": path.stem, "file": str(path), "robot_conv": None, "turn_taking": None,
            "close_reason": None, "duration_ms": None, "turns": 0, "complete_turns": 0,
            "underruns": 0, "flushes": 0, "self_interruptions": 0, "max_backlog_frames": None,
            "backlog_alarm": False, "bad_lines": bad}
    run = 0
    for r in records:
        ev = r.get("ev")
        if ev == "open":
            conv["robot_conv"] = r.get("conv")
            conv["turn_taking"] = r.get("turn_taking")
        elif ev == "close":
            conv["close_reason"] = r.get("reason")
            conv["duration_ms"] = r.get("duration_ms") if _number(r.get("duration_ms")) else None
        elif ev == "underrun":
            conv["underruns"] += 1
        elif ev == "turn":
            conv["turns"] += 1
            figures = {k: r.get(k) for k in LATENCY_FIELDS}
            missing = [k for k in LATENCY_FIELDS if not _number(figures[k])]
            total = None if missing else round(sum(figures.values()), 1)
            endpointing = r.get("endpointing_ms") if _number(r.get("endpointing_ms")) else None
            latency_turns.append({"conversation": conv["id"], "n": r.get("n"), **figures,
                                  "total_ms": total, "complete": not missing,
                                  "missing": missing, "endpointing_ms": endpointing,
                                  "flushed": bool(r.get("flushed"))})
            if not missing:
                conv["complete_turns"] += 1
            frames = _backlog_frames(r.get("stats"), frame_ms)
            if frames is not None:
                conv["max_backlog_frames"] = max(frames, conv["max_backlog_frames"] or 0)
            run = run + 1 if frames is not None and frames > backlog_frames else 0
            if run >= backlog_turns:
                conv["backlog_alarm"] = True
    conv["flushes"], conv["self_interruptions"] = _count_self_interruptions(
        records, SELF_INTERRUPT_WINDOW_MS)
    return conv


def _criteria(conversations, latency, latency_bar_ms):
    crit = {}
    p50 = latency["p50_ms"]
    if p50 is None:
        crit["latency_p50"] = {"status": "no data", "detail": "no complete turn"}
    else:
        crit["latency_p50"] = {
            "status": "pass" if p50 <= latency_bar_ms else "fail",
            "detail": f"p50 {p50:.0f} ms from user_end over {latency['complete_turns']} "
                      f"turns (bar {latency_bar_ms:.0f} ms)"}

    long_runs = [c for c in conversations
                 if c["duration_ms"] is not None and c["duration_ms"] >= FIVE_MINUTES_MS]
    clean = [c for c in long_runs if c["underruns"] == 0 and c["self_interruptions"] == 0
             and c["close_reason"] in CLEAN_CLOSE_REASONS]
    if clean:
        c = clean[0]
        crit["five_minute_run"] = {
            "status": "pass",
            "detail": f"{c['id']}: {c['duration_ms'] / 60000:.1f} min, {c['turn_taking']}, "
                      f"closed {c['close_reason']}, no underrun or self-interruption"}
    elif long_runs:
        why = "; ".join(f"{c['id']}: {c['underruns']} underruns, {c['self_interruptions']} "
                        f"self-interruptions, closed {c['close_reason'] or NO_CLOSE}"
                        for c in long_runs)
        crit["five_minute_run"] = {"status": "fail", "detail": why}
    else:
        crit["five_minute_run"] = {"status": "no data",
                                   "detail": "no conversation lasted five minutes"}

    if not conversations:
        crit["underruns_zero"] = {"status": "no data", "detail": "no conversation"}
    else:
        total = sum(c["underruns"] for c in conversations)
        crit["underruns_zero"] = {"status": "pass" if total == 0 else "fail",
                                  "detail": f"{total} underruns in {len(conversations)} "
                                            f"conversations"}
    return crit


def build_report(paths, *, calibration_ms=None, latency_bar_ms=LATENCY_BAR_MS,
                 backlog_frames=BACKLOG_FRAMES, backlog_turns=BACKLOG_TURNS, frame_ms=FRAME_MS):
    """The report, as a JSON-ready dict, from log files and/or directories of them.
    calibration_ms: the operator's acoustic gaps (last word to first robot sound), if any.
    Raises FileNotFoundError for a path that does not exist."""
    turns = []
    conversations = [_analyze_conversation(f, turns, frame_ms, backlog_frames, backlog_turns)
                     for f in _log_files(paths)]
    totals = [t["total_ms"] for t in turns if t["complete"]]
    latency = {"complete_turns": len(totals), "incomplete_turns": len(turns) - len(totals),
               "p50_ms": percentile(totals, 50), "p95_ms": percentile(totals, 95),
               "max_ms": max(totals) if totals else None, "turns": turns}

    complete = [t for t in turns if t["complete"]]
    if complete and all(t["endpointing_ms"] is not None for t in complete):
        perceived_values = [t["total_ms"] + t["endpointing_ms"] for t in complete]
        perceived = {"status": "measured", "p50_ms": percentile(perceived_values, 50),
                     "p95_ms": percentile(perceived_values, 95), "max_ms": max(perceived_values)}
    else:
        perceived = {"status": "not measured", "p50_ms": None, "p95_ms": None, "max_ms": None,
                     "note": "adds the model's endpointing delay before user_end, which the "
                             "logs do not carry; see the acoustic calibration"}

    samples = [float(v) for v in (calibration_ms or [])]
    gap = sum(samples) / len(samples) if samples else None
    software = latency["p50_ms"]
    calibration = {"acoustic_gap_ms": gap, "samples_ms": samples, "software_p50_ms": software,
                   "difference_ms": gap - software if gap is not None and software is not None
                   else None,
                   "note": "acoustic gap (person's last word to the robot's first sound) minus "
                           "the software p50: the endpointing delay, the uplink stages before "
                           "user_end, and the speaker's own output latency"}

    close_reasons = {}
    for c in conversations:
        reason = c["close_reason"] or NO_CLOSE
        close_reasons[reason] = close_reasons.get(reason, 0) + 1

    alarmed = [c["id"] for c in conversations if c["backlog_alarm"]]
    backlogs = [c["max_backlog_frames"] for c in conversations if c["max_backlog_frames"] is not None]
    return {
        "conversations": conversations,
        "latency": latency,
        "perceived": perceived,
        "self_interruptions": {"flushes": sum(c["flushes"] for c in conversations),
                               "without_user_text": sum(c["self_interruptions"]
                                                        for c in conversations),
                               "window_ms": SELF_INTERRUPT_WINDOW_MS},
        "underruns": sum(c["underruns"] for c in conversations),
        "backlog": {"alarm": bool(alarmed), "conversations": alarmed,
                    "max_frames": max(backlogs) if backlogs else None,
                    "threshold_frames": backlog_frames, "consecutive_turns": backlog_turns,
                    "frame_ms": frame_ms},
        "close_reasons": close_reasons,
        "calibration": calibration,
        "criteria": _criteria(conversations, latency, latency_bar_ms),
    }


def _fmt(value):
    if value is None:
        return "-"
    if _number(value):
        return str(int(value)) if float(value).is_integer() else f"{value:.1f}"
    return str(value)


def render_markdown(r):
    """The report as Markdown, for the plan's follow-up notes."""
    lat, per, cal = r["latency"], r["perceived"], r["calibration"]
    out = ["# Voice mode QA report", "", "## Success criteria", "",
           "| Criterion | Result | Detail |", "|---|---|---|"]
    for name, c in r["criteria"].items():
        out.append(f"| {name} | {c['status']} | {c['detail']} |")
    out += ["", "## Turn latency (from user_end, KTD11 sum of durations)", "",
            f"- Complete turns: {lat['complete_turns']}; incomplete: {lat['incomplete_turns']}",
            f"- p50 {_fmt(lat['p50_ms'])} ms, p95 {_fmt(lat['p95_ms'])} ms, "
            f"max {_fmt(lat['max_ms'])} ms",
            f"- Perceived (from the last word): {per['status']}"
            + (f", p50 {_fmt(per['p50_ms'])} ms, p95 {_fmt(per['p95_ms'])} ms"
               if per["status"] == "measured" else f" ({per['note']})"),
            "", "## Acoustic calibration", ""]
    if cal["acoustic_gap_ms"] is None:
        out.append("- Not recorded (pass --calibration-ms with the measured gaps).")
    else:
        out += [f"- Acoustic gap: {_fmt(cal['acoustic_gap_ms'])} ms "
                f"(from {len(cal['samples_ms'])} samples)",
                f"- Software p50: {_fmt(cal['software_p50_ms'])} ms; "
                f"difference {_fmt(cal['difference_ms'])} ms ({cal['note']})"]
    si, bl = r["self_interruptions"], r["backlog"]
    out += ["", "## Signals", "",
            f"- Flushes: {si['flushes']}; with no user text within "
            f"{_fmt(si['window_ms'])} ms (self-interruptions): {si['without_user_text']}",
            f"- Underruns: {r['underruns']}",
            f"- Model input backlog alarm (> {_fmt(bl['threshold_frames'])} frames for "
            f"{bl['consecutive_turns']} turns): "
            + (f"YES in {', '.join(bl['conversations'])}" if bl["alarm"] else "no")
            + f"; max {_fmt(bl['max_frames'])} frames",
            "- Close reasons: " + (", ".join(f"{k} {v}" for k, v in sorted(r["close_reasons"].items()))
                                   or "none"),
            "", "## Conversations", ""]
    if r["conversations"]:
        out += ["| Log | Robot conv | Turn-taking | Closed | Minutes | Turns | Underruns | "
                "Self-interruptions | Max backlog |", "|---|---|---|---|---|---|---|---|---|"]
        for c in r["conversations"]:
            minutes = None if c["duration_ms"] is None else round(c["duration_ms"] / 60000, 1)
            out.append(f"| {c['id']} | {_fmt(c['robot_conv'])} | {_fmt(c['turn_taking'])} | "
                       f"{c['close_reason'] or NO_CLOSE} | {_fmt(minutes)} | {c['turns']} | "
                       f"{c['underruns']} | {c['self_interruptions']} | "
                       f"{_fmt(c['max_backlog_frames'])} |")
    else:
        out.append("No conversation logs: no data.")
    out += ["", "## Turns", ""]
    if lat["turns"]:
        out += ["| Log | Turn | user_end to first chunk | Robot first chunk to play | "
                "Half ping RTT | Total ms |", "|---|---|---|---|---|---|"]
        for t in lat["turns"]:
            total = _fmt(t["total_ms"]) if t["complete"] else \
                "incomplete (no " + ", ".join(t["missing"]) + ")"
            out.append(f"| {t['conversation']} | {_fmt(t['n'])} | "
                       f"{_fmt(t['user_end_to_first_chunk_ms'])} | "
                       f"{_fmt(t['robot_first_chunk_to_play_ms'])} | "
                       f"{_fmt(t['half_ping_rtt_ms'])} | {total} |")
    else:
        out.append("No turns.")
    return "\n".join(out) + "\n"


def parse_args(argv=None):
    p = argparse.ArgumentParser(prog="python -m relay.report",
                                description="Latency and QA report from the relay's "
                                            "per-conversation JSONL logs.")
    p.add_argument("paths", nargs="+", help="log files and/or directories of *.jsonl")
    p.add_argument("--json", action="store_true", help="print JSON instead of Markdown")
    p.add_argument("--calibration-ms", type=float, action="append", default=[],
                   metavar="MS", help="an acoustically measured gap from the person's last "
                                      "word to the robot's first sound; repeatable")
    p.add_argument("--latency-bar-ms", type=float, default=LATENCY_BAR_MS,
                   help="p50 pass bar (default %(default)s)")
    p.add_argument("--backlog-frames", type=float, default=BACKLOG_FRAMES,
                   help="capacity alarm threshold in frames (default %(default)s)")
    p.add_argument("--backlog-turns", type=int, default=BACKLOG_TURNS,
                   help="consecutive turns above it that raise the alarm (default %(default)s)")
    return p.parse_args(argv)


def main(argv=None):
    args = parse_args(argv)
    try:
        r = build_report(args.paths, calibration_ms=args.calibration_ms,
                         latency_bar_ms=args.latency_bar_ms,
                         backlog_frames=args.backlog_frames, backlog_turns=args.backlog_turns)
    except (FileNotFoundError, OSError) as exc:
        print(f"relay.report: {exc}", file=sys.stderr)
        return 2
    print(json.dumps(r, indent=2) if args.json else render_markdown(r), end="\n" if args.json else "")
    return 0


if __name__ == "__main__":
    sys.exit(main())
