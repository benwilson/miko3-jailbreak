#!/usr/bin/env python3
"""qa-voice-bench.py — how long the launcher's voice identification takes to compute one
speaker embedding on the robot (owner 2026-10-02; launcher VoiceId, VoiceTuning.BENCH_PROP).

Sets debug.miko3.voice_bench=1, restarts the launcher the way an installed launcher
restarts (am force-stop, then am start; the HOME key does not bring it back), waits for
the voice model to load and the bench to run on the voice thread (synthetic audio of
1.5, 3, 5 and 8 s, three runs each, "voice: bench dur M ms: embedding in N ms"), then
prints each length's runs, median and max, and clears the property. The bench audio is
synthetic; nothing is recorded. Real answers log "voice: embedding in N ms (dur M ms)"
as they happen, so `adb logcat -s ListenEngine | grep voice:` shows live timings too.

Usage:
  scripts/qa-voice-bench.py [--serial 192.168.19.74:5555] [--timeout 120]
"""
import argparse
import re
import subprocess
import sys
import time

DEFAULT_SERIAL = "192.168.19.74:5555"
LAUNCHER_PACKAGE = "com.miko3.launcher"
LAUNCHER_ACTIVITY = "com.miko3.launcher/.MainActivity"
TAG = "ListenEngine"
BENCH_PROP = "debug.miko3.voice_bench"
BENCH_RE = re.compile(r"voice: bench dur (\d+) ms: embedding in (\d+) ms \(dim (\d+)\)")
DONE_MARKERS = ("voice: bench done", "voice: bench failed", "voice: bench skipped", "voice: model failed")


def parse_bench(text):
    """{duration ms: [compute ms, ...]} and the embedding size from a logcat dump."""
    runs, dim = {}, None
    for m in BENCH_RE.finditer(text):
        runs.setdefault(int(m.group(1)), []).append(int(m.group(2)))
        dim = int(m.group(3))
    return runs, dim


def median(values):
    s = sorted(values)
    mid = len(s) // 2
    return s[mid] if len(s) % 2 else (s[mid - 1] + s[mid]) / 2


def format_bench(runs, dim):
    lines = [f"embedding size: {dim}", "audio   runs (ms)            median   max"]
    for dur in sorted(runs):
        r = runs[dur]
        lines.append(f"{dur / 1000:>4.1f} s  {', '.join(str(x) for x in r):<20} {median(r):>6}  {max(r):>5}")
    return "\n".join(lines)


def adb(serial, *args):
    return subprocess.run(["adb", "-s", serial, *args], capture_output=True, text=True).stdout


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--serial", default=DEFAULT_SERIAL)
    ap.add_argument("--timeout", type=int, default=120)
    args = ap.parse_args(argv)
    subprocess.run(["adb", "connect", args.serial], capture_output=True)
    try:
        adb(args.serial, "shell", "setprop", BENCH_PROP, "1")
        adb(args.serial, "logcat", "-c")
        adb(args.serial, "shell", "am", "force-stop", LAUNCHER_PACKAGE)
        adb(args.serial, "shell", "am", "start", "-n", LAUNCHER_ACTIVITY)
        deadline = time.monotonic() + args.timeout
        text = ""
        while time.monotonic() < deadline:
            text = adb(args.serial, "logcat", "-d", "-s", f"{TAG}:I", f"{TAG}:E")
            if any(m in text for m in DONE_MARKERS):
                break
            time.sleep(2)
        for line in text.splitlines():
            if "voice:" in line and "bench dur" not in line:
                print("  " + line.strip())
        runs, dim = parse_bench(text)
        if not runs:
            print(f"!! no bench lines in {args.timeout} s (adb logcat -s {TAG} | grep voice:)", file=sys.stderr)
            return 1
        print(format_bench(runs, dim))
        return 0
    finally:
        adb(args.serial, "shell", "setprop", BENCH_PROP, '""')


if __name__ == "__main__":
    sys.exit(main())
