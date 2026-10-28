#!/usr/bin/env python3
"""qa-detector-bench.py — time Explore's object detector on the robot, stage by
stage, for every configured execution provider and model, without ever opening
the camera (detector speed plan).

The camera HAL wedged on 2026-09-30 from repeated camera opens and force-stops,
so this never touches it: with debug.miko3.explore.bench=1, Explore's startup
runs DetectorBench instead of the wander (no camera, no driver, no drive lease)
and logs per-stage p50/p95 under the tag ExploreBench. The run goes:

  1. refuse if Explore's process is already up (a wander may have the camera
     open, and force-stopping that is what wedges it): exit Explore from the
     launcher first, or pass --restart if you know it is idle;
  2. optionally push JPEGs (--frames) and/or unshipped variant models
     (--push-model, a speed-only check: only the bench loads pushed models) to
     Explore's external files dir bench/;
  3. set debug.miko3.explore.bench=1 (and bench_configs / bench_rounds when given),
     start Explore and follow its log until "bench done";
  4. on every exit, Ctrl-C included: clear the bench properties, force-stop
     Explore only if it reported bench mode (so its camera never opened), and
     remove anything pushed; then print the table.

Usage:
  scripts/qa-detector-bench.py [--serial 192.168.19.74:5555] [--rounds 5]
      [--configs cpu/detector.onnx,xnnpack/detector-rgba.onnx]
      [--frames a.jpg b.jpg] [--push-model out/detector-variants/detector-int8.onnx]
      [--timeout 300] [--log out/detector-bench.log] [--restart]
  scripts/qa-detector-bench.py --parse LOGFILE    # re-print the table from a saved log

The default configs are every shipped detector*.onnx on cpu and on xnnpack.
"""
import argparse
import re
import subprocess
import sys
import time
from pathlib import Path

DEFAULT_SERIAL = "192.168.19.74:5555"
PACKAGE = "com.miko3.mode.explore"
ACTIVITY = f"{PACKAGE}/.MainActivity"
# Mirrors DetectorConfig.java and DetectorBench.java.
BENCH_PROPERTY = "debug.miko3.explore.bench"
CONFIGS_PROPERTY = "debug.miko3.explore.bench_configs"
ROUNDS_PROPERTY = "debug.miko3.explore.bench_rounds"
PROPERTIES = (BENCH_PROPERTY, CONFIGS_PROPERTY, ROUNDS_PROPERTY)
REMOTE_DIR = f"/sdcard/Android/data/{PACKAGE}/files/bench"
TAGS = ("ExploreBench", "ExploreDetector", "ExploreModeApp", "AndroidRuntime")
STAGES = ("decode", "prep", "run", "copy", "detect")
BASELINE = ("cpu", "detector.onnx")
BUDGET_MS = 1000.0
DEFAULT_TIMEOUT = 300
ADB_TIMEOUT = 60
# Recognised but harmless; "bench start" is what proves bench mode (no camera).
_EVENT = re.compile(r"\bbench (start|result|detections|error|done|cancelled)\b(.*)$")
_KV = re.compile(r"(\w+)=(\S+)")
_DETECTION = re.compile(r"([^\[\],]+?) (\d+\.\d+) \[(-?[\d.]+),(-?[\d.]+),(-?[\d.]+),(-?[\d.]+)\]")


class BenchError(Exception):
    pass


# ---- parsing (host-tested) ----

def parse_event(line):
    """(kind, fields) for an ExploreBench log line, or None. Fields hold the
    key=value pairs; detections lines add "found" [(label, score)], error lines
    "message", done/cancelled lines "ms"."""
    m = _EVENT.search(line)
    if not m:
        return None
    kind, rest = m.group(1), m.group(2)
    if kind == "detections":
        head, _, found = rest.partition(": ")
        fields = dict(_KV.findall(head))
        fields["found"] = [(d.group(1).strip(), float(d.group(2))) for d in _DETECTION.finditer(found)]
        return kind, fields
    if kind == "error":
        head, _, message = rest.partition(": ")
        fields = dict(_KV.findall(head))
        fields["message"] = message.strip() or head.strip(": ").strip()
        return kind, fields
    if kind in ("done", "cancelled"):
        ms = re.search(r"in (\d+) ms", rest)
        return kind, {"ms": int(ms.group(1)) if ms else None}
    fields = dict(_KV.findall(rest))
    if kind == "result":
        for k in ("total",) + STAGES:
            if k in fields:
                p50, _, p95 = fields[k].partition("/")
                fields[k] = (float(p50), float(p95))
        for k in ("frames", "rounds", "looks", "load_ms", "first_ms"):
            if k in fields:
                fields[k] = int(fields[k])
    return kind, fields


def collect(lines):
    """Everything a run logged: start fields, results and errors per (ep, model),
    detections per (ep, model) then frame, and whether it finished."""
    out = {"start": None, "results": {}, "errors": {}, "detections": {}, "done": None, "cancelled": False}
    for line in lines:
        ev = parse_event(line)
        if ev is None:
            continue
        kind, f = ev
        key = (f.get("ep"), f.get("model"))
        if kind == "start":
            out["start"] = f
        elif kind == "result":
            out["results"][key] = f
        elif kind == "error":
            out["errors"][key] = f["message"]
        elif kind == "detections":
            out["detections"].setdefault(key, {})[f.get("frame")] = f["found"]
        elif kind == "done":
            out["done"] = f["ms"]
        elif kind == "cancelled":
            out["cancelled"] = True
            out["done"] = f["ms"]
    return out


def labels_agree(base, other):
    """Per frame, whether the two configs found the same set of names."""
    frames = sorted(set(base) | set(other))
    return {fr: sorted(n for n, _ in base.get(fr, [])) == sorted(n for n, _ in other.get(fr, []))
            for fr in frames}


def format_table(run, baseline=BASELINE, budget_ms=BUDGET_MS):
    """The results as a markdown table: per config, load and first-look time,
    p50/p95 of the total and of each stage, the speed-up against the baseline's
    total p50, whether p95 meets the budget, and whether the detected names
    match the baseline's on every frame."""
    rows = ["| ep/model | load ms | first ms | total p50/p95 | "
            + " | ".join(STAGES) + " | vs cpu fp32 | p95 <= 1 s | same names |",
            "|---" * (len(STAGES) + 7) + "|"]
    base = run["results"].get(baseline)
    base_dets = run["detections"].get(baseline, {})
    for key, r in sorted(run["results"].items(), key=lambda kv: kv[1]["total"][0]):
        speed = f"{base['total'][0] / r['total'][0]:.2f}x" if base and r["total"][0] > 0 else "-"
        dets = run["detections"].get(key, {})
        if key == baseline or not base_dets:
            same = "-"
        else:
            agree = labels_agree(base_dets, dets)
            same = "yes" if all(agree.values()) else \
                "no: " + ", ".join(fr for fr, ok in agree.items() if not ok)
        stage_cells = " | ".join(f"{r[s][0]:.0f}/{r[s][1]:.0f}" for s in STAGES)
        rows.append(f"| {key[0]}/{key[1]} | {r.get('load_ms', '-')} | {r.get('first_ms', '-')} | "
                    f"{r['total'][0]:.0f}/{r['total'][1]:.0f} | {stage_cells} | {speed} | "
                    f"{'yes' if r['total'][1] <= budget_ms else 'no'} | {same} |")
    for key, msg in sorted(run["errors"].items(), key=lambda kv: str(kv[0])):
        if key not in run["results"]:
            rows.append(f"| {key[0]}/{key[1]} | error: {msg} |")
    return "\n".join(rows)


def summary_text(run):
    lines = []
    s = run["start"]
    if s is None:
        lines.append("!! no 'bench start' line: Explore did not run the bench (is the APK current?)")
        return "\n".join(lines)
    lines.append(f"frames={s.get('frames')} ({s.get('source')}), rounds={s.get('rounds')}, "
                 f"configs={s.get('configs')}")
    lines.append(format_table(run))
    if run["done"] is None:
        lines.append("!! the bench did not finish (timeout or crash); the table has what it logged")
    elif run["cancelled"]:
        lines.append("(bench cancelled before the end)")
    else:
        lines.append(f"bench took {run['done'] / 1000:.0f} s")
    return "\n".join(lines)


# ---- adb ----

class Robot:
    def __init__(self, serial):
        self.serial = serial

    def adb(self, *args, check=True, timeout=ADB_TIMEOUT):
        try:
            r = subprocess.run(["adb", "-s", self.serial] + list(args), capture_output=True, text=True,
                               timeout=timeout, stdin=subprocess.DEVNULL)
        except FileNotFoundError:
            raise BenchError("!! adb not found on PATH")
        except subprocess.TimeoutExpired:
            raise BenchError(f"!! adb timed out: {' '.join(args)}. Is the robot on and on Wi-Fi?")
        if check and r.returncode != 0:
            raise BenchError(f"!! adb {' '.join(args)}: {(r.stderr or r.stdout).strip()}")
        return r.stdout

    def connect(self):
        if ":" in self.serial:
            subprocess.run(["adb", "connect", self.serial], capture_output=True, text=True, timeout=30)
        if self.adb("get-state", check=False).strip() != "device":
            raise BenchError(f"!! robot not reachable over adb at {self.serial}")

    def running(self):
        return bool(self.adb("shell", "pidof", PACKAGE, check=False).strip())

    def setprop(self, key, value):
        # '' clears it: SystemProperties.get then returns "".
        self.adb("shell", f"setprop {key} '{value}'")

    def getprop(self, key):
        return self.adb("shell", "getprop", key, check=False).strip()


def run_bench(robot, args):
    robot.connect()
    leftover = {k: robot.getprop(k) for k in PROPERTIES}
    if any(leftover.values()):
        print(f"note: clearing leftover bench properties {leftover}")
        for k in PROPERTIES:
            robot.setprop(k, "")
    if robot.running():
        if not args.restart:
            raise BenchError(
                "!! Explore's process is up. If he is wandering, his camera is open, and force-stopping\n"
                "   it is what wedged the camera HAL. Exit Explore from the launcher, then re-run with\n"
                "   --restart (the clean exit closes the camera; the bench then stops a camera-less run).")
        print("Explore's process is up; --restart given, so stopping it (you said it is idle).")
        robot.adb("shell", "am", "force-stop", PACKAGE)
        time.sleep(1)
    pushed = []
    lines = []
    in_bench = False
    try:
        if args.frames or args.push_model:
            robot.adb("shell", "mkdir", "-p", REMOTE_DIR)
            for f in list(args.frames or []) + list(args.push_model or []):
                robot.adb("push", str(f), f"{REMOTE_DIR}/{Path(f).name}", timeout=180)
                pushed.append(Path(f).name)
            print(f"pushed {len(pushed)} file(s) to {REMOTE_DIR}")
        robot.setprop(BENCH_PROPERTY, "1")
        if args.configs:
            robot.setprop(CONFIGS_PROPERTY, args.configs)
        if args.rounds:
            robot.setprop(ROUNDS_PROPERTY, str(args.rounds))
        robot.adb("logcat", "-c")
        robot.adb("shell", "am", "start", "-n", ACTIVITY)
        print(f"Explore started in bench mode; following its log (up to {args.timeout} s)...", flush=True)
        proc = subprocess.Popen(["adb", "-s", robot.serial, "logcat", "-v", "brief", "-s"]
                                + [f"{t}:V" for t in TAGS], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                stdin=subprocess.DEVNULL, text=True)
        deadline = time.monotonic() + args.timeout
        try:
            while time.monotonic() < deadline:
                line = proc.stdout.readline()
                if not line:
                    break
                line = line.rstrip("\n")
                lines.append(line)
                ev = parse_event(line)
                if ev is None:
                    if "FATAL" in line or "AndroidRuntime" in line and line.startswith("E/"):
                        print("  " + line)
                    continue
                kind, f = ev
                if kind == "start":
                    in_bench = True
                if kind in ("start", "result", "error", "done", "cancelled"):
                    print("  " + line.split("): ", 1)[-1], flush=True)
                if kind in ("done", "cancelled"):
                    break
        finally:
            proc.terminate()
    finally:
        for k in PROPERTIES:
            robot.setprop(k, "")
        if in_bench:
            # Bench mode never opened the camera, so this cannot wedge it.
            robot.adb("shell", "am", "force-stop", PACKAGE, check=False)
        else:
            print("!! never saw 'bench start': Explore was NOT force-stopped (it may be wandering with the\n"
                  "   camera open); exit it from the launcher.")
        for name in pushed:
            robot.adb("shell", "rm", "-f", f"{REMOTE_DIR}/{name}", check=False)
        left = {k: robot.getprop(k) for k in PROPERTIES}
        print("bench properties cleared" if not any(left.values()) else f"!! properties still set: {left}")
        if args.log:
            Path(args.log).parent.mkdir(parents=True, exist_ok=True)
            Path(args.log).write_text("\n".join(lines) + "\n")
            print(f"log saved to {args.log}")
    return lines


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--serial", default=DEFAULT_SERIAL)
    ap.add_argument("--rounds", type=int, help="timed passes over the frames (app default 5)")
    ap.add_argument("--configs", help="ep/model,... (app default: every shipped model on cpu and xnnpack)")
    ap.add_argument("--frames", nargs="+", help="JPEGs to push instead of the bundled bench frames")
    ap.add_argument("--push-model", nargs="+", help="unshipped detector-*.onnx variants to time (speed only)")
    ap.add_argument("--timeout", type=int, default=DEFAULT_TIMEOUT)
    ap.add_argument("--log", help="save the raw log lines here (e.g. out/detector-bench.log)")
    ap.add_argument("--restart", action="store_true", help="force-stop an idle Explore process first")
    ap.add_argument("--parse", metavar="LOGFILE", help="print the table from a saved log; no robot needed")
    args = ap.parse_args(argv)
    try:
        if args.parse:
            lines = Path(args.parse).read_text().splitlines()
        else:
            for m in args.push_model or []:
                if not re.fullmatch(r"detector(-[a-z0-9]+)*\.onnx", Path(m).name):
                    raise BenchError(f"!! {m}: the app only loads files named detector-<variant>.onnx")
            lines = run_bench(Robot(args.serial), args)
    except BenchError as e:
        print(e, file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        return 130
    print()
    print(summary_text(collect(lines)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
