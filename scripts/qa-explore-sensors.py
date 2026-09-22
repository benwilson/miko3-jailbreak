#!/usr/bin/env python3
"""qa-explore-sensors.py — capture the motor controller's raw replies over adb so
the explore mode's sensor parsing rests on real records (docs/hardware/tof-sensor.md).

DirectMotorDriver logs every raw reply under the MikoDmdRaw tag once
`log.tag.MikoDmdRaw` is DEBUG. Any mode that holds the driver (remote-control or
explore) keeps the 100ms POWER poll running, so a capture needs no driving:

  python3 scripts/qa-explore-sensors.py --non-interactive          # passive baseline
  python3 scripts/qa-explore-sensors.py --save-fixtures DIR        # also save records
  python3 scripts/qa-explore-sensors.py                            # + guided cover/edge steps

The guided steps ask the owner to cover the sensor and hold the robot over an
edge, so the summary shows what a hazard looks like in the record.
"""
import argparse
import re
import statistics
import subprocess
import sys
import time
from pathlib import Path

DEFAULT_SERIAL = "192.168.19.74:5555"
RAW_TAG = "MikoDmdRaw"
DEFAULT_ACTIVITY = "com.miko3.mode.remotecontrol/.MainActivity"
LINE_RE = re.compile(r"MikoDmdRaw: sent=(?P<sent>\S*) (?:len=\d+ )?reply=(?P<reply>.*)$")
TOFIR_RE = re.compile(r"TOFIR=(?P<body>[^A-WYZ]*)")


def adb(serial, *args, check=True):
    r = subprocess.run(["adb", "-s", serial] + list(args), capture_output=True, text=True, timeout=60)
    if check and r.returncode != 0:
        sys.exit(f"!! adb {' '.join(args)} failed: {r.stderr.strip() or r.stdout.strip()}")
    return r.stdout


def extract_records(logcat_text):
    """(sent, reply) for every MikoDmdRaw line, in order; other lines are ignored."""
    records = []
    for line in logcat_text.splitlines():
        m = LINE_RE.search(line)
        if m:
            records.append((m.group("sent"), m.group("reply")))
    return records


def tofir_fields(reply):
    """The comma-separated TOFIR fields, with all-'X' padding fields as None."""
    m = TOFIR_RE.search(reply)
    if not m:
        return None
    fields = []
    for raw in m.group("body").split(","):
        raw = raw.strip()
        if not raw:
            continue
        fields.append(None if set(raw) == {"X"} else raw)
    return fields


def summarize(records):
    """Plain-text summary of a capture window: counts, TOFIR spread, frozen flag."""
    power = [r for s, r in records if s == "POWER"]
    lines = [f"records: {len(records)} ({len(power)} POWER replies)"]
    tofs = []
    for reply in power:
        fields = tofir_fields(reply)
        if fields and fields[0] is not None and fields[0].isdigit():
            tofs.append(int(fields[0]))
    if tofs:
        lines.append(f"tof: min={min(tofs)} max={max(tofs)} mean={statistics.mean(tofs):.1f} "
                     f"spread={max(tofs) - min(tofs)}")
        if len(set(tofs)) == 1:
            lines.append("WARNING: tof values never changed in this window (frozen)")
    else:
        lines.append("WARNING: no TOFIR tof value found in any POWER reply")
    others = sorted({s for s, _ in records if s != "POWER"})
    if others:
        lines.append("other replies: " + ", ".join(others))
    if any("CPL=" in r for _, r in records):
        lines.append("CPL= seen in: " + ", ".join(sorted({s for s, r in records if "CPL=" in r})))
    return "\n".join(lines)


def capture(serial, seconds):
    adb(serial, "logcat", "-c")
    time.sleep(seconds)
    return extract_records(adb(serial, "logcat", "-d", "-s", f"{RAW_TAG}:D"))


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--serial", default=DEFAULT_SERIAL)
    ap.add_argument("--seconds", type=float, default=10.0, help="length of each capture window")
    ap.add_argument("--activity", default=DEFAULT_ACTIVITY,
                    help="a mode activity that holds DirectMotorDriver (keeps the POWER poll running)")
    ap.add_argument("--non-interactive", action="store_true", help="passive baseline only")
    ap.add_argument("--save-fixtures", metavar="DIR", help="write captured records under DIR")
    args = ap.parse_args()

    if ":" in args.serial:
        subprocess.run(["adb", "connect", args.serial], capture_output=True, timeout=30)
    adb(args.serial, "shell", "setprop", f"log.tag.{RAW_TAG}", "DEBUG")
    adb(args.serial, "shell", "am", "start", "-n", args.activity)
    time.sleep(3)

    steps = [("baseline", "leave the robot still, nothing in front of it")]
    if not args.non_interactive:
        steps += [("covered", "hold a hand ~3cm in front of the sensor"),
                  ("edge", "hold the robot with its front just past a table edge")]
    try:
        for name, instruction in steps:
            if name != "baseline":
                input(f"\n>> {instruction}, then press Enter ")
            records = capture(args.serial, args.seconds)
            print(f"\n== {name} ==\n{summarize(records)}")
            if args.save_fixtures:
                out = Path(args.save_fixtures)
                out.mkdir(parents=True, exist_ok=True)
                (out / f"{name}.txt").write_text(
                    "".join(f"{s}\t{r}\n" for s, r in records[:20]))
                print(f"saved {min(len(records), 20)} records to {out / (name + '.txt')}")
    finally:
        adb(args.serial, "shell", "setprop", f"log.tag.{RAW_TAG}", "INFO", check=False)


if __name__ == "__main__":
    main()
