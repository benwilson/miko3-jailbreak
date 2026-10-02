#!/usr/bin/env python3
"""Explore navigation scorecard from logcat captures.

Reads `adb logcat -v time` (or `-v epoch`) captures of the explore mode and
scores how he navigates: time roaming, docked and eyes-only; hazards, CPL
refusals, stalls, RECOVER waits, wedges, boxed-in, jams, help calls and
wriggles per roaming hour; escapes and seeks; turn accuracy; coverage and
place memory; remarks and conversations. The core is the openness-versus-
outcome table: each steer decision that led to a forward leg is labelled by
what that leg met next (clean, camera cut, CPL, obstacle, stall...), and the
legs are bucketed by the open score the steer chose them at.

The robot never reads this. It is for the coding agent improving the
navigation code, and only parses what the brain already notes.

Usage:
  scripts/nav-report.py LOG [LOG ...] [-o report.md] [--json report.json|-]
                        [--labels labels.csv] [--frames-dir DIR ...]
                        [--year 2026] [--tz America/Los_Angeles] [--gap-s 120]

Time and date handling:
  * `-v time` lines carry no year and no zone: they are the robot's local
    wall clock. The year comes from --year (default: this year); a capture
    spanning New Year is not handled.
  * `-v epoch` lines and frame-ring names (out/camera-frames-*/frame-<ms>.jpg,
    wall-clock epoch ms) are converted to local time in --tz, default
    America/Los_Angeles. Checked on 2026-10-01: epoch 1790887142.327 in
    out/explore-calib-logcat.txt is the same line day2.log shows at
    10-01 13:39:02.327, so the robot's clock is Pacific time (PDT, -07:00).
    Within the DST fall-back hour, local times are ambiguous; not handled.
  * Exact duplicate lines (overlapping captures) are dropped; lines are then
    stably sorted by time.
  * A capture gap is any stretch longer than --gap-s with no line from any
    process. Time inside a gap counts toward no mode (it is reported as gap),
    and a leg whose outcome window crosses a gap is labelled unknown.
  * Runs are split by the explore process's pid (any Explore* tag); the same
    pid silent for over an hour starts a new run (pid reuse).
"""
import argparse
import bisect
import csv
import datetime as dt
import json
import re
import statistics
import sys
from collections import Counter
from pathlib import Path
from zoneinfo import ZoneInfo

REPO = Path(__file__).resolve().parent.parent
DEFAULT_TZ = "America/Los_Angeles"
RUN_SPLIT_S = 3600.0
LEG_WINDOW_S = 30.0
FRAME_BEFORE_S = 3.0
FRAME_AFTER_S = 0.5
OPEN_EDGES = [0.0, 0.10, 0.20, 0.35, 0.50, 0.70, 1.0]  # steerBlocked 0.35, steerOpen 0.70
BAD = ("cpl", "obstacle", "stall", "blocked_at_start")
TURN_FAIL = ("turn_hazard", "turn_blocked")
OUTCOMES = ("clean", "camera_cut", "cpl_hiccup", "cpl", "obstacle", "stall", "blocked_at_start",
            "turn_hazard", "turn_blocked", "sensor_drop")

TIME_RE = re.compile(r"^(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)\.(\d{3}) ([VDIWEFA])/(.+?)\(\s*(\d+)\): ?(.*)$")
EPOCH_RE = re.compile(r"^\s*(\d{9,11})\.(\d{3})\s+(\d+)\s+\d+\s+([VDIWEFA])\s+(.+?)\s*: ?(.*)$")
NUM_RE = re.compile(r"-?\d+(?:\.\d+)?")

STEER_RE = re.compile(r"^steer: (left|right|straight) (\d+) deg, open ([\d.]+), "
                      r"(turn only|short leg|leg x[\d.]+)(?:, toward the (doorway|seek target))?(?:, new ([\d.]+))?")
TRUSTED_RE = re.compile(r"^the doorway at \d+ deg reads blocked \(open ([\d.]+)\); trusting Claude: a short leg")
HAZARD_RE = re.compile(r"^hazard (?:while ([A-Z_]+)|at start): ([A-Z_]+)")
MEASURED_RE = re.compile(r"^measured turn: asked (-?\d+) deg, turned (-?\d+) deg")
PROBE_RE = re.compile(r"^recover probe (?:at (\d+) s|now): (.*)$")
FREE_RE = re.compile(r"^free after (\d+) ms: (.*)$")
TURNS_RE = re.compile(r"^conversation over after (\d+) turn")

BOUNDARY_PREFIXES = (
    "waiting up to", "no look to steer by", "curiosity stop:", "everything ahead closed", "look-around look",
    "a lean-in", "a person while roaming", "first leg after the conversation", "answering the call",
    "power: on the charger", "docked:", "stall: waiting",
)

# Notes only a robot with live sensors and the lease makes (he drives or turns).
MOTION_PREFIXES = ("steer:", "turn start:", "measured turn", "first leg after the conversation", "hazard while",
                   "waiting up to", "curiosity stop:", "wheels stalled")


def norm(text):
    """Numbers replaced by N, so reasons group."""
    return NUM_RE.sub("N", text).strip()


class Line:
    __slots__ = ("ts", "pid", "tag", "level", "msg")

    def __init__(self, ts, pid, tag, level, msg):
        self.ts, self.pid, self.tag, self.level, self.msg = ts, pid, tag, level, msg

    def key(self):
        return (self.ts, self.pid, self.tag, self.level, self.msg)


def parse_line(raw, year, tz=DEFAULT_TZ):
    """One logcat line (`-v time` or `-v epoch`) as a Line in local time, or None."""
    raw = raw.rstrip("\r\n")
    m = TIME_RE.match(raw)
    if m:
        mo, d, h, mi, s, ms, level, tag, pid, msg = m.groups()
        try:
            ts = dt.datetime(year, int(mo), int(d), int(h), int(mi), int(s), int(ms) * 1000)
        except ValueError:
            return None
        return Line(ts, int(pid), tag.strip(), level, msg)
    m = EPOCH_RE.match(raw)
    if m:
        sec, ms, pid, level, tag, msg = m.groups()
        ts = dt.datetime.fromtimestamp(int(sec) + int(ms) / 1000, ZoneInfo(tz)).replace(tzinfo=None)
        return Line(ts, int(pid), tag.strip(), level, msg)
    return None


def parse_lines(raws, year, tz=DEFAULT_TZ):
    """Parsed lines, exact duplicates dropped, stably sorted by time."""
    seen, out = set(), []
    for raw in raws:
        p = parse_line(raw, year, tz)
        if p is None:
            continue
        k = p.key()
        if k in seen:
            continue
        seen.add(k)
        out.append(p)
    out.sort(key=lambda p: p.ts)
    return out


class Run:
    def __init__(self, pid):
        self.pid = pid
        self.lines = []

    @property
    def start(self):
        return self.lines[0].ts

    @property
    def end(self):
        return self.lines[-1].ts

    @property
    def name(self):
        return f"{self.start:%Y-%m-%d %H:%M} pid {self.pid}"


def split_runs(lines):
    """The explore process's lines (Explore* tags), one Run per pid (pid reuse after an hour: a new run)."""
    current, runs = {}, []
    for p in lines:
        if not p.tag.startswith("Explore"):
            continue
        r = current.get(p.pid)
        if r is None or (p.ts - r.end).total_seconds() > RUN_SPLIT_S:
            r = Run(p.pid)
            current[p.pid] = r
            runs.append(r)
        r.lines.append(p)
    runs.sort(key=lambda r: r.start)
    return runs


def capture_gaps(lines, gap_s):
    """(start, end) of every stretch over gap_s with no line from any process."""
    gaps = []
    for a, b in zip(lines, lines[1:]):
        if (b.ts - a.ts).total_seconds() > gap_s:
            gaps.append((a.ts, b.ts))
    return gaps


def gap_overlap(a, b, gaps):
    total = 0.0
    for g0, g1 in gaps:
        lo, hi = max(a, g0), min(b, g1)
        if hi > lo:
            total += (hi - lo).total_seconds()
    return total


def load_frames(dirs, tz=DEFAULT_TZ):
    """Frame-ring files as sorted (local time, path); the name is wall-clock epoch ms."""
    zone, out = ZoneInfo(tz), []
    for d in dirs:
        for f in Path(d).glob("frame-*.jpg"):
            m = re.fullmatch(r"frame-(\d{12,14})\.jpg", f.name)
            if m:
                t = dt.datetime.fromtimestamp(int(m.group(1)) / 1000, zone).replace(tzinfo=None)
                out.append((t, str(f)))
    out.sort()
    return out


def nearest_frame(frames, at, before_s=FRAME_BEFORE_S, after_s=FRAME_AFTER_S):
    """The latest frame at most after_s after `at` and at most before_s before it (the look the steer used)."""
    if not frames:
        return None
    i = bisect.bisect_right(frames, (at + dt.timedelta(seconds=after_s), "￿")) - 1
    if i < 0 or (at - frames[i][0]).total_seconds() > before_s:
        return None
    return frames[i][1]


def bucket_of(open_score):
    for lo, hi in zip(OPEN_EDGES, OPEN_EDGES[1:]):
        if open_score < hi or hi == OPEN_EDGES[-1]:
            if open_score >= lo:
                return f"{lo:.2f}-{hi:.2f}"
    return f"{OPEN_EDGES[-2]:.2f}-{OPEN_EDGES[-1]:.2f}"


def openness_table(legs):
    rows = {}
    for lo, hi in zip(OPEN_EDGES, OPEN_EDGES[1:]):
        rows[f"{lo:.2f}-{hi:.2f}"] = {"bucket": f"{lo:.2f}-{hi:.2f}", "legs": 0, "unknown": 0, "outcomes": Counter()}
    for g in legs:
        row = rows[bucket_of(g["open"])]
        if g["outcome"] == "unknown":
            row["unknown"] += 1
        else:
            row["legs"] += 1
            row["outcomes"][g["outcome"]] += 1
    out = []
    for row in rows.values():
        o = row["outcomes"]
        row["bad"] = sum(o[k] for k in BAD)
        row["turn_fail"] = sum(o[k] for k in TURN_FAIL)
        row["bad_rate"] = row["bad"] / row["legs"] if row["legs"] else None
        row["outcomes"] = dict(o)
        out.append(row)
    return out


def turn_stats(t):
    errs = t["errors"]
    small = [e for a, e in zip(t["asked"], errs) if a < 30]
    large = [e for a, e in zip(t["asked"], errs) if a >= 30]
    return {
        "measured": t["measured"], "clean": len(errs), "blocked": t["blocked"], "interrupted": t["interrupted"],
        "wrong_way": t["wrong_way"],
        "bias_deg": statistics.mean(errs) if errs else None,
        "spread_deg": statistics.stdev(errs) if len(errs) > 1 else None,
        "median_abs_err_deg": statistics.median(abs(e) for e in errs) if errs else None,
        "within_10deg": sum(abs(e) <= 10 for e in errs) / len(errs) if errs else None,
        "bias_small_deg": statistics.mean(small) if small else None,
        "bias_large_deg": statistics.mean(large) if large else None,
        "blocked_rate": t["blocked"] / t["measured"] if t["measured"] else None,
    }


def median(xs):
    return statistics.median(xs) if xs else None


class Analyzer:
    """One run's pass over its lines."""

    def __init__(self, run, gaps, frames=None, window_s=LEG_WINDOW_S):
        self.run, self.gaps, self.frames, self.window_s = run, gaps, frames or [], window_s
        self.time = {"roaming_s": 0.0, "docked_s": 0.0, "eyes_only_s": 0.0, "gap_s": 0.0}
        self.sensing, self.docked, self.docked_weak = False, False, False
        self.legs, self.leg = [], None
        self.decisions = Counter()
        self.last_turn_only = None
        self.turn = {"measured": 0, "blocked": 0, "interrupted": 0, "wrong_way": 0, "errors": [], "asked": []}
        self.turn_blocked_since = self.turn_hazard_since = False
        self.hazards, self.hazard_states, self.stalls = Counter(), Counter(), Counter()
        self.c = Counter()
        self.recover, self.recover_open = Counter(), False
        self.wedges, self.boxed = Counter(), Counter()
        self.wriggle = {"episodes": 0, "ways": 0, "freed": 0, "failed": 0}
        self.wriggle_open = False
        self.esc, self.esc_list = None, []
        self.seek, self.seeks = None, []
        self.coverage, self.place_hits, self.looks, self.camera = 0, 0, 0, False
        self.social = Counter()

    # ---- time per mode ----
    def mode(self):
        if self.docked:
            return "docked_s"
        return "roaming_s" if self.sensing else "eyes_only_s"

    def account(self, a, b):
        span = (b - a).total_seconds()
        g = gap_overlap(a, b, self.gaps)
        self.time["gap_s"] += g
        self.time[self.mode()] += max(0.0, span - g)

    def update_mode(self, m):
        if m.startswith(("power: on the charger", "docked: on the charger", "docked: sitting still again")):
            self.docked, self.docked_weak = True, False
        elif m.startswith("on the charger: no resume leg"):
            if not self.docked:
                self.docked, self.docked_weak = True, True
        elif m.startswith("docked: off the charger"):
            self.docked, self.sensing = False, True  # "roaming again": he only roams with sensors live
        elif m.startswith("power: off the charger"):
            self.docked = False
        elif m.startswith("eyes only:"):
            self.sensing = False
        elif m.startswith("sensors available and lease held") or m.startswith(MOTION_PREFIXES):
            # He can leave EYES_ONLY without the note (through a conversation): motion means sensors.
            self.sensing = True
            if self.docked_weak:
                self.docked = self.docked_weak = False

    # ---- legs ----
    def close_leg(self, outcome):
        if self.leg is None:
            return
        if outcome == "clean" and self.leg.get("hiccup"):
            outcome = "cpl_hiccup"
        self.leg["outcome"] = outcome
        self.leg.pop("hiccup", None)
        self.legs.append(self.leg)
        self.leg = None

    def open_leg(self, p, band, kind, open_score, novelty, toward):
        self.close_leg("clean")
        self.leg = {"time": p.ts, "run": self.run.name, "open": open_score, "band": band, "kind": kind,
                    "novelty": novelty, "toward": toward, "outcome": None,
                    "frame": nearest_frame(self.frames, p.ts)}

    def leg_line(self, p):
        """Returns True when the line was a decision (handled here)."""
        m = p.msg
        if self.leg is not None:
            age = (p.ts - self.leg["time"]).total_seconds()
            if age > self.window_s or gap_overlap(self.leg["time"], p.ts, self.gaps) > 0:
                self.close_leg("unknown")
        s = STEER_RE.match(m)
        if s:
            side, deg, op, kind, toward, new = s.groups()
            self.decisions["steer"] += 1
            if kind == "turn only":
                self.close_leg("clean")
                self.decisions["turn_only"] += 1
                self.last_turn_only = (p.ts, float(op), new)
            else:
                self.open_leg(p, f"{side} {deg}", "short" if kind == "short leg" else "leg", float(op),
                              float(new) if new else None, toward or "")
            return True
        if m.startswith("facing the look-around's best: a short leg") and self.last_turn_only:
            t0, op, new = self.last_turn_only
            if (p.ts - t0).total_seconds() <= 1.0:
                self.decisions["turn_only"] -= 1
                self.open_leg(p, "straight 0", "short", op, float(new) if new else None, "")
                self.leg["time"] = t0
            return True
        t = TRUSTED_RE.match(m)
        if t:
            self.decisions["trusted_doorway"] += 1
            self.open_leg(p, "doorway", "short", float(t.group(1)), None, "doorway")
            return True
        if m.startswith("no look to steer by"):
            self.decisions["blind"] += 1
        if self.leg is None:
            return False
        h = HAZARD_RE.match(m)
        if h:
            state, kind = h.groups()
            if state is None:
                self.close_leg("blocked_at_start")
            elif state in ("HOP", "APPROACH"):
                self.close_leg("cpl" if kind == "CPL" else "obstacle")
            elif state in ("TURN", "LOOK"):
                self.close_leg("turn_hazard")
            else:
                self.close_leg("clean")
        elif m.startswith("controller refused forward (CPL)"):
            self.leg["hiccup"] = True
        elif m.startswith("wheels stalled while driving"):
            self.close_leg("stall")
        elif m.startswith("camera reads the way ahead blocked"):
            self.close_leg("camera_cut")
        elif m.startswith("measured turn blocked"):
            self.close_leg("turn_blocked")
        elif m.startswith("eyes only:"):
            self.close_leg("sensor_drop")
        elif m.startswith(BOUNDARY_PREFIXES):
            self.close_leg("clean")
        return False

    # ---- turns ----
    def turn_line(self, m):
        if m.startswith("turn start:"):
            self.turn_blocked_since = self.turn_hazard_since = False
        elif m.startswith("measured turn blocked"):
            self.turn["blocked"] += 1
            self.turn_blocked_since = True
        elif m.startswith("hazard while"):
            self.turn_hazard_since = True
        else:
            mt = MEASURED_RE.match(m)
            if mt:
                asked, turned = int(mt.group(1)), int(mt.group(2))
                self.turn["measured"] += 1
                if self.turn_blocked_since:
                    pass
                elif self.turn_hazard_since:
                    self.turn["interrupted"] += 1
                else:
                    self.turn["errors"].append(float(turned - asked))
                    self.turn["asked"].append(asked)
                    if turned < 0 < asked:
                        self.turn["wrong_way"] += 1
                self.turn_blocked_since = self.turn_hazard_since = False

    # ---- escapes ----
    def esc_start(self, p, trigger):
        if self.esc is None:
            self.esc = {"start": p.ts, "trigger": trigger, "help": False}

    def esc_end(self, p, outcome, free_ms=None, how=None):
        if self.esc is None:
            if outcome != "freed":
                return
            self.esc = {"start": p.ts - dt.timedelta(milliseconds=free_ms or 0), "trigger": "unlogged", "help": False}
        e = self.esc
        e.update(outcome=outcome, how=how)
        if outcome == "freed":
            e["free_ms"] = free_ms if free_ms is not None else int((p.ts - e["start"]).total_seconds() * 1000)
        self.esc_list.append(e)
        self.esc = None

    # ---- seeks ----
    def seek_line(self, p):
        m = p.msg
        if not m.startswith("seeking:"):
            return
        body = m[len("seeking:"):].strip()
        for word, key in (("arrived (", "arrived"), ("gave up (", "gave_up")):
            if body.startswith(word):
                reason = norm(body[len(word):].rstrip(")"))
                start = self.seek if self.seek is not None else p.ts
                self.seeks.append({"outcome": key, "reason": reason, "s": (p.ts - start).total_seconds()})
                self.seek = None
                return
        if re.fullmatch(r"no seek for .*", body) or body.startswith("stayed within"):
            if self.seek is not None:
                self.seeks.append({"outcome": "gave_up", "reason": "unlogged (a new seek began)",
                                   "s": (p.ts - self.seek).total_seconds()})
                self.seek = None
            return
        if "; no seek for" in body:
            if self.seek is not None:
                self.seeks.append({"outcome": "gave_up", "reason": "no target",
                                   "s": (p.ts - self.seek).total_seconds()})
                self.seek = None
            return
        if self.seek is None:
            self.seek = p.ts

    # ---- the pass ----
    def line(self, p, prev):
        if prev is not None:
            self.account(prev.ts, p.ts)
        m = p.msg
        if p.tag == "ExploreCamera":
            self.camera = True
            if m.startswith("look in"):
                self.looks += 1
            return
        self.leg_line(p)
        self.turn_line(m)
        self.seek_line(p)
        self.events(p)
        self.update_mode(m)

    def events(self, p):
        m, c = p.msg, self.c
        h = HAZARD_RE.match(m)
        if h:
            self.hazards[h.group(2)] += 1
            self.hazard_states[h.group(1) or "START"] += 1
        elif m.startswith("controller refused forward (CPL)"):
            c["cpl_refusals"] += 1
        elif m.startswith("wheels stalled while driving"):
            self.stalls["driving"] += 1
        elif m.startswith(("wheels stalled backing up", "back-up stalled after", "back-out stalled after")):
            self.stalls["backing"] += 1
        elif m.startswith("wheels stalled driving in the escape"):
            self.stalls["escape"] += 1
        elif m.startswith("camera reads the way ahead blocked"):
            c["camera_cuts"] += 1
        elif m.startswith("collision stop"):
            c["bumps"] += 1
        elif m.startswith("stall: waiting for the motor board"):
            if self.recover_open:
                self.recover["unclosed"] += 1
            self.recover_open = True
            c["recover_waits"] += 1
            self.esc_start(p, "stall")
        elif PROBE_RE.match(m):
            at, rest = PROBE_RE.match(m).groups()
            if self.recover_open and "the board is back" in rest:
                kind = "backing up" if "backing up" in rest else "turn" if "turned" in rest else "moved"
                self.recover[f"{at + 's' if at else 'now'} {kind}"] += 1
                self.recover_open = False
            elif self.recover_open and "no encoders" in rest:
                self.recover["no encoders"] += 1
                self.recover_open = False
        elif m.startswith("no recovery after"):
            if self.recover_open:
                self.recover["none"] += 1
                self.recover_open = False
        elif m.startswith("wedged: ") and not m.startswith("wedged: the doorway"):
            self.wedges[m[len("wedged: "):].split(" (")[0].strip()] += 1
            self.esc_start(p, "wedged")
        elif m.startswith("boxed in: ") and not m.startswith("boxed in: out"):
            self.boxed[norm(m[len("boxed in: "):].split(";")[0])] += 1
            self.esc_start(p, "boxed in")
        elif m.startswith("cornered:"):
            c["cornered"] += 1
            self.esc_start(p, "cornered")
        elif m.startswith("fully jammed"):
            c["jams"] += 1
            self.esc_start(p, "jammed")
        elif m.startswith("asking for help:"):
            c["help_calls"] += 1
            if self.esc is not None:
                self.esc["help"] = True
        elif m.startswith("wriggle failed both ways"):
            if self.esc is not None:
                self.esc["help"] = True
            if self.wriggle_open:
                self.wriggle["failed"] += 1
                self.wriggle_open = False
        elif re.match(r"^wriggle (LEFT|RIGHT): up to", m):
            self.wriggle["ways"] += 1
            if not self.wriggle_open:
                self.wriggle["episodes"] += 1
                self.wriggle_open = True
            self.esc_start(p, "wriggle")
        elif re.match(r"^wriggle (LEFT|RIGHT): free after", m) or m.startswith("wriggled free"):
            if self.wriggle_open:
                self.wriggle["freed"] += 1
                self.wriggle_open = False
            self.esc_end(p, "freed", how="wriggle")
        elif FREE_RE.match(m):
            ms, how = FREE_RE.match(m).groups()
            self.esc_end(p, "freed", int(ms), norm(how))
        elif m.startswith("jam probe moved") and "free" in m:
            self.esc_end(p, "freed", how="jam probe")
        elif m.startswith("coverage: "):
            n = NUM_RE.search(m)
            self.coverage = max(self.coverage, int(n.group())) if n else self.coverage
        elif m.startswith("place: seen before"):
            self.place_hits += 1
        elif m.startswith("curiosity stop:"):
            self.social["curiosity_stops"] += 1
        elif m.startswith("remarks in the last"):
            self.social["remarks"] += 1
        elif m.startswith("a call from"):
            self.social["calls"] += 1
        elif m.startswith("answering the call"):
            self.social["calls_answered"] += 1
        elif m.startswith("a conversation with someone"):
            self.social["conversations"] += 1
        elif TURNS_RE.match(m):
            self.social["conversation_turns"] += int(TURNS_RE.match(m).group(1))
        elif m.startswith("docked look: something new"):
            self.social["docked_remarks"] += 1
        if (self.esc is not None and STEER_RE.match(m)
                and (p.ts - self.esc["start"]).total_seconds() > 1.0):
            self.esc_end(p, "resumed")

    def finish(self):
        self.close_leg("unknown")
        if self.recover_open:
            self.recover["unclosed"] += 1
        if self.esc is not None:
            self.esc_end(self.run.lines[-1], "unresolved")
        if self.seek is not None:
            self.seeks.append({"outcome": "open", "reason": "run ended",
                               "s": (self.run.end - self.seek).total_seconds()})

    def raw(self):
        return {
            "time": dict(self.time),
            "counts": {
                "hazards": dict(self.hazards), "hazards_by_state": dict(self.hazard_states),
                "cpl_refusals": self.c["cpl_refusals"], "stalls": dict(self.stalls),
                "camera_cuts": self.c["camera_cuts"], "bumps": self.c["bumps"],
                "recover": {"waits": self.c["recover_waits"], "outcomes": dict(self.recover)},
                "wedges": dict(self.wedges), "boxed_in": dict(self.boxed), "cornered": self.c["cornered"],
                "jams": self.c["jams"], "help_calls": self.c["help_calls"], "wriggles": dict(self.wriggle),
                "decisions": dict(self.decisions),
            },
            "legs": self.legs, "turn_raw": self.turn, "escape_raw": self.esc_list, "seek_raw": self.seeks,
            "novelty_raw": {"coverage_cells": self.coverage, "place_hits": self.place_hits,
                            "camera_looks": self.looks},
            "camera": self.camera, "social": dict(self.social),
        }


def analyze_run(run, gaps, frames=None, window_s=LEG_WINDOW_S):
    a = Analyzer(run, gaps, frames, window_s)
    prev = None
    for p in run.lines:
        a.line(p, prev)
        prev = p
    a.finish()
    out = a.raw()
    out.update(run=run.name, pid=run.pid, start=run.start.isoformat(sep=" "), end=run.end.isoformat(sep=" "))
    return summarize(out)


def summarize(r):
    """Derived metrics from a run's (or a day's merged) raw tallies."""
    c, soc = r["counts"], r["social"]
    hours = r["time"]["roaming_s"] / 3600
    legs = r["legs"]
    esc = r["escape_raw"]
    freed = [e for e in esc if e["outcome"] == "freed"]
    ended = [e for e in esc if e["outcome"] != "unresolved"]
    r["escapes"] = {
        "episodes": len(esc), "freed": len(freed), "resumed_unlogged": sum(e["outcome"] == "resumed" for e in esc),
        "unresolved": sum(e["outcome"] == "unresolved" for e in esc), "needed_help": sum(e["help"] for e in esc),
        "success_rate": len(freed) / len(ended) if ended else None,
        "free_ms": [e["free_ms"] for e in freed], "median_free_ms": median([e["free_ms"] for e in freed]),
        "how": dict(Counter(e["how"] for e in freed)),
        "by_trigger": dict(Counter(e["trigger"] for e in esc)),
    }
    sk = [s for s in r["seek_raw"] if s["outcome"] in ("arrived", "gave_up")]
    r["seeks"] = {
        "count": len(sk) + sum(s["outcome"] == "open" for s in r["seek_raw"]),
        "arrived": dict(Counter(s["reason"] for s in sk if s["outcome"] == "arrived")),
        "gave_up": dict(Counter(s["reason"] for s in sk if s["outcome"] == "gave_up")),
        "durations_s": [s["s"] for s in sk], "median_s": median([s["s"] for s in sk]),
    }
    r["turns"] = turn_stats(r["turn_raw"])
    nr = r["novelty_raw"]
    r["novelty"] = dict(nr, place_hit_rate=nr["place_hits"] / nr["camera_looks"] if nr["camera_looks"] else None)
    r["openness"] = openness_table(legs)
    forward = [g for g in legs]
    tally = {
        "legs": len(forward), "decisions": c["decisions"].get("steer", 0) + c["decisions"].get("trusted_doorway", 0),
        "hazards": sum(c["hazards"].values()), "cpl_refusals": c["cpl_refusals"],
        "stalls": sum(c["stalls"].values()), "recover_waits": c["recover"]["waits"],
        "wedges": sum(c["wedges"].values()), "boxed_in": sum(c["boxed_in"].values()), "jams": c["jams"],
        "help_calls": c["help_calls"], "wriggles": c["wriggles"]["episodes"], "camera_cuts": c["camera_cuts"],
        "remarks": soc.get("remarks", 0), "curiosity_stops": soc.get("curiosity_stops", 0),
        "conversations": soc.get("conversations", 0), "calls": soc.get("calls", 0),
    }
    for k, v in c["hazards"].items():
        tally[f"hazards_{k}"] = v
    r["tally"] = tally
    r["rates"] = {k: (v / hours if hours > 0 else None) for k, v in tally.items()}
    return r


def merge_counts(a, b):
    out = dict(a)
    for k, v in b.items():
        if isinstance(v, dict):
            out[k] = merge_counts(out.get(k, {}), v)
        elif isinstance(v, (int, float)) and not isinstance(v, bool):
            out[k] = out.get(k, 0) + v
    return out


def merge_runs(runs, label):
    turn = {"measured": 0, "blocked": 0, "interrupted": 0, "wrong_way": 0, "errors": [], "asked": []}
    for r in runs:
        for k in ("measured", "blocked", "interrupted", "wrong_way"):
            turn[k] += r["turn_raw"][k]
        turn["errors"] += r["turn_raw"]["errors"]
        turn["asked"] += r["turn_raw"]["asked"]
    counts, social, time, nov = {}, {}, {}, {"coverage_cells": 0, "place_hits": 0, "camera_looks": 0}
    for r in runs:
        counts = merge_counts(counts, r["counts"])
        social = merge_counts(social, r["social"])
        time = merge_counts(time, r["time"])
        nov["coverage_cells"] = max(nov["coverage_cells"], r["novelty_raw"]["coverage_cells"])
        nov["place_hits"] += r["novelty_raw"]["place_hits"]
        nov["camera_looks"] += r["novelty_raw"]["camera_looks"]
    for k in ("hazards", "hazards_by_state", "stalls", "wedges", "boxed_in", "decisions", "wriggles"):
        counts.setdefault(k, {})
    counts.setdefault("recover", {"waits": 0, "outcomes": {}})
    for k in ("cpl_refusals", "camera_cuts", "bumps", "cornered", "jams", "help_calls"):
        counts.setdefault(k, 0)
    counts["wriggles"] = {k: counts["wriggles"].get(k, 0) for k in ("episodes", "ways", "freed", "failed")}
    day = {
        "date": label, "runs": [r["run"] for r in runs], "time": time, "counts": counts, "social": social,
        "legs": [g for r in runs for g in r["legs"]], "turn_raw": turn,
        "escape_raw": [e for r in runs for e in r["escape_raw"]], "seek_raw": [s for r in runs for s in r["seek_raw"]],
        "novelty_raw": nov, "camera": any(r["camera"] for r in runs),
    }
    for k in ("roaming_s", "docked_s", "eyes_only_s", "gap_s"):
        day["time"].setdefault(k, 0.0)
    out = summarize(day)
    out["novelty"]["coverage_note"] = "largest single-run coverage (cells reset each run)"
    return out


def build_report(lines, gap_s=120.0, frames=None, window_s=LEG_WINDOW_S):
    runs = split_runs(lines)
    gaps = capture_gaps(lines, gap_s)
    results = [analyze_run(r, gaps, frames, window_s) for r in runs]
    by_day = {}
    for r in results:
        by_day.setdefault(r["start"][:10], []).append(r)
    days = [merge_runs(rs, d) for d, rs in sorted(by_day.items())]
    return {"runs": results, "days": days,
            "capture_gaps": [{"from": a.isoformat(sep=" "), "to": b.isoformat(sep=" "),
                              "s": (b - a).total_seconds()} for a, b in gaps]}


# ---- output ----

def fmt(v, nd=1, pct=False):
    if v is None:
        return "-"
    if pct:
        return f"{100 * v:.0f}%"
    if isinstance(v, float):
        return f"{v:.{nd}f}"
    return str(v)


def hm(s):
    s = int(round(s))
    return f"{s // 3600}h{s % 3600 // 60:02d}m" if s >= 3600 else f"{s // 60}m{s % 60:02d}s"


def kv(d):
    return ", ".join(f"{k} {v}" for k, v in sorted(d.items(), key=lambda kv: -kv[1] if isinstance(kv[1], int) else 0)) or "-"


def table(head, rows):
    out = ["| " + " | ".join(head) + " |", "|" + "|".join("---" for _ in head) + "|"]
    out += ["| " + " | ".join(str(x) for x in row) + " |" for row in rows]
    return out


def rate(r, key, nd=1):
    v = r["rates"].get(key)
    return f"{r['tally'].get(key, 0)} ({fmt(v, nd)}/h)"


def scorecard(r):
    t, c, e, s, tu, n, soc = r["time"], r["counts"], r["escapes"], r["seeks"], r["turns"], r["novelty"], r["social"]
    rows = [
        ("Time", f"roaming {hm(t['roaming_s'])}, docked {hm(t['docked_s'])}, eyes-only {hm(t['eyes_only_s'])}, "
                 f"capture gaps {hm(t['gap_s'])}"),
        ("Legs (forward, after a steer)", rate(r, "legs")),
        ("Hazards", f"{rate(r, 'hazards')}: {kv(c['hazards'])}; by state {kv(c['hazards_by_state'])}"),
        ("CPL refusals (hiccups)", rate(r, "cpl_refusals")),
        ("Stalls", f"{rate(r, 'stalls')}: {kv(c['stalls'])}"),
        ("RECOVER waits", f"{rate(r, 'recover_waits')}; outcome {kv(c['recover']['outcomes'])}"),
        ("Wedges", f"{rate(r, 'wedges')}: {kv(c['wedges'])}"),
        ("Boxed in", f"{rate(r, 'boxed_in')}: {kv(c['boxed_in'])}"),
        ("Cornered rests", str(c["cornered"])),
        ("Fully jammed", rate(r, "jams")),
        ("Help calls", rate(r, "help_calls")),
        ("Wriggles", f"{rate(r, 'wriggles')}: {c['wriggles'].get('ways', 0)} ways, "
                     f"{c['wriggles'].get('freed', 0)} freed, {c['wriggles'].get('failed', 0)} failed both ways"),
        ("Camera cut a leg short", rate(r, "camera_cuts")),
        ("Escapes", f"{e['episodes']} episodes ({kv(e['by_trigger'])}); freed {e['freed']}, "
                    f"resumed without a 'free' note {e['resumed_unlogged']}, unresolved at run end {e['unresolved']}, "
                    f"needed help {e['needed_help']}; success {fmt(e['success_rate'], pct=True)}, "
                    f"median time to free {fmt(e['median_free_ms'] and e['median_free_ms'] / 1000)} s; "
                    f"how: {kv(e['how'])}"),
        ("Seeks", f"{s['count']}: arrived {kv(s['arrived'])}; gave up {kv(s['gave_up'])}; "
                  f"median {fmt(s['median_s'])} s"),
        ("Turn accuracy", f"{tu['measured']} measured, {tu['clean']} clean: bias {fmt(tu['bias_deg'])} deg "
                          f"(small <30: {fmt(tu['bias_small_deg'])}, large: {fmt(tu['bias_large_deg'])}), "
                          f"spread {fmt(tu['spread_deg'])} deg, median |err| {fmt(tu['median_abs_err_deg'])} deg, "
                          f"within 10 deg {fmt(tu['within_10deg'], pct=True)}, wrong way {tu['wrong_way']}; "
                          f"blocked {tu['blocked']} ({fmt(tu['blocked_rate'], pct=True)}), "
                          f"cut by a hazard {tu['interrupted']}"),
        ("Coverage / novelty", f"{n['coverage_cells']} cells; place seen-before {n['place_hits']} of "
                               f"{n['camera_looks']} camera looks ({fmt(n['place_hit_rate'], pct=True)})"
                               + ("" if r["camera"] else " (no camera)")),
        ("Curiosity stops", rate(r, "curiosity_stops")),
        ("Remarks", rate(r, "remarks")),
        ("Calls / conversations", f"{soc.get('calls', 0)} calls ({soc.get('calls_answered', 0)} answered); "
                                  f"{soc.get('conversations', 0)} conversations, "
                                  f"{soc.get('conversation_turns', 0)} turns"),
    ]
    return table(["Metric", "Value (per roaming hour)"], rows)


def openness_md(r):
    cols = ["clean", "camera_cut", "cpl_hiccup", "cpl", "obstacle", "stall", "blocked_at_start",
            "turn_hazard", "turn_blocked", "sensor_drop"]
    rows = []
    for row in r["openness"]:
        o = row["outcomes"]
        rows.append([row["bucket"], row["legs"]] + [o.get(k, 0) for k in cols]
                    + [row["unknown"], fmt(row["bad_rate"], pct=True)])
    return table(["open", "legs"] + cols + ["unknown", "hazard/stall rate"], rows)


def runs_md(runs):
    rows = []
    for r in runs:
        t, e, tu = r["time"], r["escapes"], r["turns"]
        bad = sum(row["bad"] for row in r["openness"])
        known = sum(row["legs"] for row in r["openness"])
        rows.append([r["run"][11:], hm(t["roaming_s"]), hm(t["docked_s"]), hm(t["eyes_only_s"]), hm(t["gap_s"]),
                     "yes" if r["camera"] else "no", r["tally"]["legs"], f"{bad}/{known}",
                     fmt(r["rates"]["hazards"]), fmt(r["rates"]["cpl_refusals"]), fmt(r["rates"]["stalls"]),
                     r["tally"]["recover_waits"], r["tally"]["wedges"], r["tally"]["jams"], r["tally"]["help_calls"],
                     f"{e['freed']}/{e['episodes']}", fmt(tu["bias_deg"]), fmt(tu["blocked_rate"], pct=True),
                     r["novelty"]["coverage_cells"], r["tally"]["remarks"], r["tally"]["conversations"]])
    return table(["run", "roam", "docked", "eyes", "gap", "cam", "legs", "bad legs", "haz/h", "CPL ref/h",
                  "stall/h", "recov", "wedge", "jam", "help", "freed", "turn bias", "blocked turns", "cells",
                  "remarks", "convos"], rows)


def render_markdown(report, sources):
    out = ["# Explore navigation report", "",
           "Sources: " + ", ".join(f"`{s}`" for s in sources), "",
           "Built by `scripts/nav-report.py` from the brain's own notes. Rates are per hour of roaming time "
           "(sensors live, not docked, not eyes-only). A leg is a steer decision with a forward part "
           "(`leg xN`, `short leg`, a look-around's best, or Claude's trusted doorway); its outcome is the first "
           "of these before the next decision: hazard (CPL / obstacle / at start), stall, a blocked or hazarded "
           "bend turn, the camera cutting it short, else clean. `cpl_hiccup` = refused once, then the retry drove. "
           "`unknown` = nothing decided it within 30 s, a capture gap, or the run ended. The logs carry no "
           "per-leg encoder counts, so clean legs have no length.", ""]
    a = report.get("assumptions")
    if a:
        out += [f"Times are the robot's local wall clock ({a['tz']}; `-v time` lines get year {a['year']}, "
                f"epoch lines and frame-ring names are converted to that zone). Exact duplicate lines are dropped. "
                f"Frames matched: the latest frame-ring JPEG from {FRAME_BEFORE_S:.0f} s before to "
                f"{FRAME_AFTER_S} s after each decision ({a['frames']} frames under "
                + (", ".join(f"`{Path(d).name}`" for d in a["frames_dirs"]) or "no dirs") + ").", ""]
    if report["capture_gaps"]:
        out += ["Capture gaps (no line from any process): " + "; ".join(
            f"{g['from'][11:19]} to {g['to'][11:19]} ({g['s']:.0f} s)" for g in report["capture_gaps"]), ""]
    for day in report["days"]:
        runs = [r for r in report["runs"] if r["start"][:10] == day["date"]]
        out += [f"## {day['date']} ({len(runs)} runs)", "", "### Scorecard", ""] + scorecard(day)
        out += ["", "### Openness versus outcome", "",
                "Legs by the open score of the band the steer chose; hazard/stall rate = "
                "(cpl + obstacle + stall + blocked_at_start) / legs with a known outcome.", ""] + openness_md(day)
        out += ["", "### Runs", ""] + runs_md(runs) + [""]
    return "\n".join(out) + "\n"


def strip_raw(r):
    return {k: v for k, v in r.items() if k not in ("legs", "turn_raw", "escape_raw", "seek_raw", "novelty_raw")}


def write_labels(report, path):
    with open(path, "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["time", "run", "open", "band", "kind", "novelty", "outcome", "frame"])
        for r in report["runs"]:
            for g in r["legs"]:
                w.writerow([g["time"].isoformat(sep=" ", timespec="milliseconds"), g["run"], f"{g['open']:.2f}",
                            g["band"], g["kind"], "" if g["novelty"] is None else f"{g['novelty']:.2f}",
                            g["outcome"], g["frame"] or ""])


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("logs", nargs="+", help="logcat captures (-v time or -v epoch)")
    ap.add_argument("-o", "--out", help="markdown report path (default: stdout)")
    ap.add_argument("--json", help="JSON report path, or - for stdout")
    ap.add_argument("--labels", help="CSV of auto-labelled decision -> leg pairs")
    ap.add_argument("--frames-dir", action="append",
                    help="frame-ring dir(s) of frame-<wallms>.jpg (default: out/camera-frames-*)")
    ap.add_argument("--year", type=int, default=dt.date.today().year, help="year for -v time lines")
    ap.add_argument("--tz", default=DEFAULT_TZ, help="the robot's local zone (epoch lines and frame names)")
    ap.add_argument("--gap-s", type=float, default=120.0, help="silence that counts as a capture gap")
    args = ap.parse_args(argv)

    raws = []
    for path in args.logs:
        with open(path, errors="replace") as f:
            raws.extend(f)
    lines = parse_lines(raws, args.year, args.tz)
    dirs = args.frames_dir if args.frames_dir is not None else sorted(
        str(d) for d in (REPO / "out").glob("camera-frames-*") if d.is_dir())
    frames = load_frames(dirs, args.tz)
    report = build_report(lines, args.gap_s, frames)
    report["assumptions"] = {"year": args.year, "tz": args.tz, "gap_s": args.gap_s, "frames_dirs": dirs,
                             "frames": len(frames), "leg_window_s": LEG_WINDOW_S}

    md = render_markdown(report, args.logs)
    if args.out:
        Path(args.out).write_text(md)
    elif args.json != "-":
        sys.stdout.write(md)
    if args.json:
        data = {"assumptions": report["assumptions"], "capture_gaps": report["capture_gaps"],
                "runs": [strip_raw(r) for r in report["runs"]], "days": [strip_raw(d) for d in report["days"]]}
        text = json.dumps(data, indent=1, default=str)
        if args.json == "-":
            sys.stdout.write(text + "\n")
        else:
            Path(args.json).write_text(text + "\n")
    if args.labels:
        write_labels(report, args.labels)
    return 0


if __name__ == "__main__":
    sys.exit(main())
