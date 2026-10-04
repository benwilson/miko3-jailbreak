#!/usr/bin/env python3
"""qa-ears-cpu.py — what the launcher's continuous ears cost in CPU under each of
the recogniser switches (ears CPU work, 2026-09-30; launcher EarsTuning).

For each setting, in order:
  1. set the four persist.miko3.ears.* properties (unset ones cleared, so a
     setting is exactly what it names) and restart the launcher the way an
     installed launcher restarts (am force-stop, then am start; the HOME key does
     not bring it back); the switches are read once, at its start;
  2. wait for the recogniser's ready line, check it names the setting, and wait
     for Explore to reopen the ears ("capture open"); Explore must be running;
  3. sample the launcher's and Explore's CPU (/proc/<pid>/stat, what top reads)
     while the robot sits quiet until the ears' next stats line (they log one a
     minute), so the talk window starts on a fresh line;
  4. prompt the owner to talk near the robot for --seconds (30 by default),
     including "Hey Miko" twice and "Miko" once, sampling CPU per second and the
     launcher's threads at both ends;
  5. wait (quiet) for the next stats line and diff its counters against the one
     before the window: the decode p50/p95 (ms per 80 ms chunk) cover the
     utterances between the two lines, i.e. the talk window.
Then it prints one table row per setting: idle and talking launcher CPU, the
Explore CPU while talking, decode p50/p95/max against the 80 ms budget, and the
wake, strong and weak cue counts, then the busiest launcher threads per setting.

Every exit, Ctrl-C included, clears the properties and restarts the launcher:
persist.* properties survive a reboot, and a forgotten switch would quietly
change the robot. The gate setting saves CPU only while the Settings page's
"answers when spoken to" switch is off (EarsTuning); with it on the gate stays
open and the row should match the baseline.

Logs carry counters only, never words; nothing here prints what was said.

Usage:
  scripts/qa-ears-cpu.py [--serial 192.168.19.74:5555] [--seconds 30]
      [--settings baseline threads1 greedy greedy-threads1 gate]
"""
import argparse
import importlib.util
import re
import signal
import sys
import time
from collections import namedtuple
from pathlib import Path

HERE = Path(__file__).resolve().parent
DEFAULT_SERIAL = "192.168.19.74:5555"
DEFAULT_SECONDS = 30
LAUNCHER_PACKAGE = "com.miko3.launcher"
LAUNCHER_ACTIVITY = "com.miko3.launcher/.MainActivity"
EXPLORE_PACKAGE = "com.miko3.mode.explore"
TAG = "ListenEngine"
# Mirrors launcher/src/com/miko3/launcher/EarsTuning.java.
PROPS = {
    "decoding": "persist.miko3.ears.decoding",
    "paths": "persist.miko3.ears.paths",
    "threads": "persist.miko3.ears.threads",
    "gate": "persist.miko3.ears.gate",
}
DEFAULTS = {"decoding": "modified_beam_search", "paths": "2", "threads": "2", "gate": "off"}
# KTD2: the plan's budget for decode per 80 ms chunk at p95.
DECODE_CHUNK_BUDGET_MS = 80
READY_TIMEOUT_S = 90
SUMMARY_TIMEOUT_S = 90  # EarsSession logs a stats line every 60 s while held
POLL_S = 2.0

Setting = namedtuple("Setting", "name values note")
SETTINGS = (
    Setting("baseline", {}, "KTD2 as shipped: beam search, 2 paths, 2 threads, hotwords"),
    Setting("threads1", {"threads": "1"}, "one recogniser thread"),
    Setting("greedy", {"decoding": "greedy_search"}, "greedy search (no hotwords: sherpa-onnx's rule)"),
    Setting("greedy-threads1", {"decoding": "greedy_search", "threads": "1"}, "greedy, one thread"),
    Setting("gate", {"gate": "wake"}, "wake gate; saves only with 'answers when spoken to' off"),
)

SUMMARY_KEYS_INT = ("chunks", "utterances", "delivered", "strong", "weak", "partial", "dropped", "resets",
                    "wakes", "decoded", "fed", "gated")
SUMMARY_KEYS_MS = ("decode_p50", "decode_p95", "decode_max")
SUMMARY_RE = re.compile(r"ears: (?:released uid \S+: .*?; )?(chunks=\d+ .*)$")
PAIR_RE = re.compile(r"(\w+)=(\S+)")
READY_RE = re.compile(r"recognizer ready in \d+ ms .*?; (decoding=\S+ paths=\d+ threads=\d+ hotwords=\S+ "
                      r"gate=\w+)\)")
COUNTER_DIFF_KEYS = ("utterances", "delivered", "strong", "weak", "wakes", "fed", "gated", "dropped")

Result = namedtuple("Result", "setting tuning idle_cpu talk_cpu talk_cpu_p95 explore_cpu counters decode threads")


def probe_module():
    """scripts/qa-ears-probe.py, for its adb Robot, CPU sampler and percentile."""
    spec = importlib.util.spec_from_file_location("qa_ears_probe", HERE / "qa-ears-probe.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


# ---- parsing (host-tested) ----

def select_settings(names):
    """The named settings in the order given, or all; ValueError names an unknown one."""
    if not names:
        return list(SETTINGS)
    by_name = {s.name: s for s in SETTINGS}
    unknown = [n for n in names if n not in by_name]
    if unknown:
        raise ValueError(f"unknown setting(s) {', '.join(unknown)}; known: {', '.join(by_name)}")
    return [by_name[n] for n in names]


def prop_commands(setting):
    """(property, value) for every switch: the setting's value, else "" (cleared)."""
    return [(PROPS[k], setting.values.get(k, "")) for k in PROPS]


def expected_tuning(setting):
    """The EarsTuning.toString() the launcher's ready line should carry for this setting."""
    v = dict(DEFAULTS, **setting.values)
    hotwords = "on" if v["decoding"] == "modified_beam_search" else "off"
    return (f"decoding={v['decoding']} paths={v['paths']} threads={v['threads']} hotwords={hotwords} "
            f"gate={v['gate']}")


def parse_ready(text):
    """The tuning named by the last recogniser-ready line in a logcat dump, or None."""
    found = None
    for line in (text or "").splitlines():
        m = READY_RE.search(line)
        if m:
            found = m.group(1)
    return found


def parse_summary(line):
    """The counters of one ears stats line as a dict, or None. Decode values are floats or
    None ('-' when nothing was decoded); a launcher older than the decode timing omits them."""
    m = SUMMARY_RE.search(line or "")
    if not m:
        return None
    pairs = dict(PAIR_RE.findall(m.group(1)))
    out = {}
    for k in SUMMARY_KEYS_INT:
        try:
            out[k] = int(pairs[k])
        except (KeyError, ValueError):
            out[k] = None
    for k in SUMMARY_KEYS_MS:
        try:
            out[k] = float(pairs[k])
        except (KeyError, ValueError):
            out[k] = None
    return out


def summaries(text):
    """Every ears stats line in a logcat dump, parsed, oldest first."""
    return [s for s in (parse_summary(line) for line in (text or "").splitlines()) if s is not None]


def counter_diff(before, after):
    """after - before for the cumulative counters; None where either lacks one."""
    out = {}
    for k in COUNTER_DIFF_KEYS:
        a, b = before.get(k), after.get(k)
        out[k] = None if a is None or b is None else b - a
    return out


def parse_task_stats(text):
    """{tid: (thread name, utime+stime ticks)} from concatenated /proc/<pid>/task/*/stat lines."""
    out = {}
    for line in (text or "").splitlines():
        line = line.strip()
        open_, close = line.find("("), line.rfind(")")
        if open_ < 0 or close < open_:
            continue
        tid = line[:open_].strip()
        fields = line[close + 1:].split()
        try:
            out[int(tid)] = (line[open_ + 1:close], int(fields[11]) + int(fields[12]))
        except (IndexError, ValueError):
            continue
    return out


def thread_cpu(before, after, elapsed_s, clk_tck, top=6):
    """[(thread name, percent of one core)] summed over threads of a name, busiest first.
    Threads that exist only at one end are skipped (they started or died in the window)."""
    if elapsed_s <= 0 or clk_tck <= 0:
        return []
    by_name = {}
    for tid, (name, ticks) in after.items():
        if tid in before:
            by_name[name] = by_name.get(name, 0) + ticks - before[tid][1]
    rows = [(name, round(100.0 * t / (elapsed_s * clk_tck), 1)) for name, t in by_name.items() if t > 0]
    return sorted(rows, key=lambda r: -r[1])[:top]


def mean(values):
    values = [v for v in values if v is not None]
    return round(sum(values) / len(values), 1) if values else None


def _cell(v, width):
    return ("-" if v is None else str(v)).rjust(width)


def format_table(results):
    head = ("setting", "idle%", "talk%", "talk%p95", "explore%", "dec p50", "dec p95", "dec max", "budget",
            "wakes", "strong", "weak", "utts", "gated")
    widths = (16, 6, 6, 8, 8, 7, 7, 7, 6, 5, 6, 5, 5, 6)
    lines = ["  ".join(h.rjust(w) if i else h.ljust(w) for i, (h, w) in enumerate(zip(head, widths)))]
    for r in results:
        p95 = r.decode.get("decode_p95")
        budget = "-" if p95 is None else ("OVER" if p95 > DECODE_CHUNK_BUDGET_MS else "ok")
        c = r.counters
        cells = (r.setting.name.ljust(widths[0]), _cell(r.idle_cpu, 6), _cell(r.talk_cpu, 6),
                 _cell(r.talk_cpu_p95, 8), _cell(r.explore_cpu, 8), _cell(r.decode.get("decode_p50"), 7),
                 _cell(p95, 7), _cell(r.decode.get("decode_max"), 7), budget.rjust(6), _cell(c.get("wakes"), 5),
                 _cell(c.get("strong"), 6), _cell(c.get("weak"), 5), _cell(c.get("utterances"), 5),
                 _cell(c.get("gated"), 6))
        lines.append("  ".join(cells))
    lines.append("CPU: percent of one core (4 cores = 400%). decode: ms per 80 ms chunk fed, per utterance, "
                 f"p50/p95 over the window's utterances; budget {DECODE_CHUNK_BUDGET_MS} ms at p95.")
    for r in results:
        if r.threads:
            lines.append(f"{r.setting.name} threads: " + ", ".join(f"{n} {p}%" for n, p in r.threads))
    return "\n".join(lines)


# ---- the robot (device-only) ----

class Run:
    def __init__(self, robot, probe, seconds, ask=input, sleep=time.sleep, clock=time.monotonic):
        self.robot, self.probe, self.seconds = robot, probe, seconds
        self.ask, self.sleep, self.clock = ask, sleep, clock

    def adb(self, *args):
        return self.robot.adb(*args, check=False)

    def set_props(self, setting):
        for prop, value in prop_commands(setting):
            self.adb("shell", "setprop", prop, value if value else '""')

    def restart_launcher(self):
        self.adb("shell", "am", "force-stop", LAUNCHER_PACKAGE)
        self.adb("shell", "am", "start", "-n", LAUNCHER_ACTIVITY)

    def logcat(self):
        return self.adb("logcat", "-d", "-s", f"{TAG}:I")

    def wait_for(self, what, test, timeout_s):
        """Polls the launcher's log until test(text) is truthy; returns its value or raises."""
        deadline = self.clock() + timeout_s
        while self.clock() < deadline:
            got = test(self.logcat())
            if got:
                return got
            self.sleep(POLL_S)
        raise self.probe.ProbeError(f"!! no {what} in {timeout_s} s (adb logcat -s {TAG})")

    def pid(self, package):
        out = self.adb("shell", "pidof", package).split()
        return out[0] if out and out[0].isdigit() else None

    def tasks(self, pid):
        return parse_task_stats(self.adb("shell", f"cat /proc/{pid}/task/*/stat 2>/dev/null"))

    def one(self, setting):
        print(f"\n== {setting.name}: {setting.note} ==")
        self.set_props(setting)
        self.adb("logcat", "-c")
        self.restart_launcher()
        tuning = self.wait_for("recogniser ready line", parse_ready, READY_TIMEOUT_S)
        want = expected_tuning(setting)
        if tuning != want:
            raise self.probe.ProbeError(f"!! the launcher came up with '{tuning}', not '{want}': is this build "
                                        "older than the switches? (scripts/build-custom-launcher.py, then "
                                        "adb install -r -t launcher/miko3-launcher.apk)")
        print(f"   launcher up: {tuning}")
        if self.pid(EXPLORE_PACKAGE) is None:
            raise self.probe.ProbeError("!! Explore is not running: start it from the launcher, then rerun")
        self.wait_for("'capture open' (Explore reopening the ears)", lambda t: "ears: capture open" in t,
                      READY_TIMEOUT_S)
        print("   ears open; keep quiet until the next stats line (up to a minute) ...")
        idle = self.probe.CpuSampler(self.robot)
        idle.start()
        self.adb("logcat", "-c")
        before = self.wait_for("ears stats line", lambda t: (summaries(t) or [None])[-1], SUMMARY_TIMEOUT_S)
        idle_rows = idle.stop()
        self.ask(f"   Press Enter, then talk near the robot for {self.seconds} s: say 'Hey Miko' twice and "
                 "'Miko' once, and chat normally in between. ")
        self.adb("logcat", "-c")
        pid = self.pid(LAUNCHER_PACKAGE)
        sampler = self.probe.CpuSampler(self.robot)
        t0 = self.clock()
        tasks0 = self.tasks(pid) if pid else {}
        sampler.start()
        self.sleep(self.seconds)
        talk_rows = sampler.stop()
        tasks1 = self.tasks(pid) if pid else {}
        threads = thread_cpu(tasks0, tasks1, self.clock() - t0, sampler.clk_tck)
        print("   stop talking; waiting quietly for the next stats line ...")
        after = self.wait_for("ears stats line", lambda t: (summaries(t) or [None])[-1], SUMMARY_TIMEOUT_S)
        talk = [r["cpu_launcher_pct"] for r in talk_rows]
        return Result(setting, tuning, mean(r["cpu_launcher_pct"] for r in idle_rows), mean(talk),
                      self.probe.percentile(talk, 95), mean(r["cpu_explore_pct"] for r in talk_rows),
                      counter_diff(before, after), after, threads)

    def restore(self):
        """Best effort, never raises: clears every switch and restarts the launcher on its defaults."""
        try:
            self.set_props(Setting("defaults", {}, ""))
            self.restart_launcher()
            print("switches cleared; launcher restarted on its defaults", file=sys.stderr)
        except BaseException as exc:  # noqa: BLE001 - this runs from finally
            print(f"!! {exc}\n!! clear them by hand: "
                  + "; ".join(f"adb shell setprop {p} '\"\"'" for p in PROPS.values())
                  + f"; adb shell am force-stop {LAUNCHER_PACKAGE}; adb shell am start -n {LAUNCHER_ACTIVITY}",
                  file=sys.stderr)


def build_parser():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--serial", default=DEFAULT_SERIAL)
    ap.add_argument("--seconds", type=int, default=DEFAULT_SECONDS, help="talk window per setting")
    ap.add_argument("--settings", nargs="*", metavar="NAME",
                    help="which settings, in order (default all): " + ", ".join(s.name for s in SETTINGS))
    return ap


def main(argv=None):
    args = build_parser().parse_args(argv)
    probe = probe_module()
    try:
        chosen = select_settings(args.settings)
    except ValueError as exc:
        raise probe.ProbeError(f"!! {exc}")
    robot = probe.Robot(args.serial)
    robot.ensure_reachable()
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(143))
    run = Run(robot, probe, max(5, args.seconds))
    results = []
    try:
        for s in chosen:
            results.append(run.one(s))
    except KeyboardInterrupt:
        print("\ninterrupted", file=sys.stderr)
        return 130
    finally:
        run.restore()
        if results:
            print("\n" + format_table(results))
    return 0


if __name__ == "__main__":
    sys.exit(main())
