#!/usr/bin/env python3
"""Pull Explore's learning log off the robot into out/learn/, one file per day.

Explore appends its structured records (leg:, trig:, turn:, escape#, seek#, mode:, bathroom,
power, stalls, recover, jams, and a "learn: start build=... tuning=..." line per start) to
app-private files/learn.log, rotated at 5 MB into learn.log.1. Each line is a `logcat -v time`
line, so the day files go straight into scripts/nav-report.py, chat-report.py and
daily-diff.py. The records carry no words, names or pixels; out/ is gitignored all the same.

The files are app-private, so `adb exec-out run-as` would need a debuggable build. The robot's
adbd runs as root (scripts/face-bench.py pulls the frame ring the same way): `adb pull`, after
`adb root` if the first try is refused, and `adb exec-out su 0 cat` as the last resort.

Pulling is idempotent: each day's lines merge into out/learn/<YYYY-MM-DD>.log (duplicates
dropped, time order). With --clear the robot's copies are emptied after a successful pull, so
the next pull starts fresh (Explore keeps appending to the open file).

Usage:
  python3 scripts/pull-learn-log.py [--serial SERIAL] [--out-dir out/learn] [--year 2026] [--clear]
"""
import argparse
import datetime as dt
import re
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent
PACKAGE = "com.miko3.mode.explore"
REMOTE_DIR = f"/data/data/{PACKAGE}/files"
NAMES = ("learn.log.1", "learn.log")  # oldest first
LINE_RE = re.compile(r"^(\d\d)-(\d\d) \d\d:\d\d:\d\d\.\d{3} [VDIWEFA]/")


def adb_cmd(serial, *args):
    return ["adb"] + (["-s", serial] if serial else []) + list(args)


def run(cmd, timeout=60):
    try:
        return subprocess.run(cmd, capture_output=True, timeout=timeout)
    except (OSError, subprocess.TimeoutExpired) as e:
        return subprocess.CompletedProcess(cmd, 1, b"", str(e).encode())


def fetch(serial, name, dest, runner=run):
    """The remote file's text, or None when it is not there (or unreadable)."""
    remote = f"{REMOTE_DIR}/{name}"
    local = Path(dest) / name
    r = runner(adb_cmd(serial, "pull", remote, str(local)))
    if r.returncode != 0:
        runner(adb_cmd(serial, "root"))
        r = runner(adb_cmd(serial, "pull", remote, str(local)))
    if r.returncode == 0 and local.exists():
        return local.read_text(errors="replace")
    r = runner(adb_cmd(serial, "exec-out", "su", "0", "cat", remote))
    if r.returncode == 0 and r.stdout and b"No such file" not in r.stdout:
        return r.stdout.decode(errors="replace")
    return None


def split_days(text, year):
    """{date: [lines]} of the records in a learn.log text; lines that are not records are dropped."""
    out = {}
    for line in text.splitlines():
        m = LINE_RE.match(line)
        if not m:
            continue
        try:
            day = dt.date(year, int(m.group(1)), int(m.group(2)))
        except ValueError:
            continue
        out.setdefault(day, []).append(line)
    return out


def merge_into(path, lines):
    """Adds lines to the day file: duplicates dropped, kept in time order. Returns the lines added."""
    old = path.read_text().splitlines() if path.exists() else []
    seen = set(old)
    new = [x for x in lines if x not in seen and not seen.add(x)]
    if new or not path.exists():
        # The "MM-dd HH:mm:ss.SSS" prefix sorts in time order within a year; sorted() is stable.
        merged = sorted(old + new, key=lambda x: x[:18])
        path.write_text("\n".join(merged) + ("\n" if merged else ""))
    return len(new)


def pull(serial, out_dir, year, clear=False, runner=run):
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    texts = []
    with tempfile.TemporaryDirectory() as td:
        for name in NAMES:
            t = fetch(serial, name, td, runner)
            if t is not None:
                texts.append((name, t))
    if not texts:
        print(f"!! no learn.log on the robot ({REMOTE_DIR}); has an Explore build with the learning log run?",
              file=sys.stderr)
        return 1
    days = {}
    for _, t in texts:
        for day, lines in split_days(t, year).items():
            days.setdefault(day, []).extend(lines)
    for day in sorted(days):
        path = out_dir / f"{day.isoformat()}.log"
        added = merge_into(path, days[day])
        print(f"{path}: {added} new line(s), {len(days[day])} pulled")
    if clear:
        for name, _ in texts:
            runner(adb_cmd(serial, "shell", "truncate", "-s", "0", f"{REMOTE_DIR}/{name}"))
        print("robot copies emptied")
    return 0


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--serial", help="adb serial (default: the only device)")
    ap.add_argument("--out-dir", default=str(REPO / "out" / "learn"), help="where the day files go")
    ap.add_argument("--year", type=int, default=dt.date.today().year, help="the year of the MM-dd lines")
    ap.add_argument("--clear", action="store_true", help="empty the robot's learn.log files after pulling")
    args = ap.parse_args(argv)
    return pull(args.serial, args.out_dir, args.year, args.clear)


if __name__ == "__main__":
    sys.exit(main())
