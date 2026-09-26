#!/usr/bin/env python3
"""qa-explore-sensors.py — capture the motor controller's raw replies over adb so
the explore mode's sensor parsing rests on real records (docs/hardware/tof-sensor.md).

DirectMotorDriver logs every raw reply under the MikoDmdRaw tag once
`log.tag.MikoDmdRaw` is DEBUG. Any mode that holds the driver (remote-control or
explore) keeps the 100ms POWER poll running, so a capture needs no driving:

  python3 scripts/qa-explore-sensors.py --non-interactive          # passive baseline
  python3 scripts/qa-explore-sensors.py --save-fixtures DIR        # also save records
  python3 scripts/qa-explore-sensors.py                            # + guided cover/edge steps
  python3 scripts/qa-explore-sensors.py --gyro-circle              # gyro yaw calibration

The guided steps ask the owner to cover the sensor and hold the robot over an
edge, so the summary shows what a hazard looks like in the record.

--gyro-circle (explore nav plan U1, KTD1) turns on Explore's MikoExploreSpin hook:
he stays still, turns left for a while, stays still, turns right, and round
again. The owner presses Enter each time his nose passes a mark on the floor.
The script treats IMUGY as a rate: it takes the bias from the still spells,
integrates each axis over the reading timestamps, picks the axis whose integral
changes sign with the turn direction, and sets the scale as the integrated
counts x seconds per full turn between marks. It then logs a short normal
wander, to check the gyro is not saturated or noisy while the wheels run, and
offers to merge the result into the robot's explore-calibration.properties
(keeping the floor-sensor keys qa-explore-mode.py wrote there). Explore needs its
floor calibration first: without it the spin hook keeps him still.
"""
import argparse
import datetime
import re
import statistics
import subprocess
import sys
import tempfile
import threading
import time
from pathlib import Path

DEFAULT_SERIAL = "192.168.19.74:5555"
RAW_TAG = "MikoDmdRaw"
DEFAULT_ACTIVITY = "com.miko3.mode.remotecontrol/.MainActivity"
LINE_RE = re.compile(r"MikoDmdRaw: sent=(?P<sent>\S*) (?:len=\d+ )?reply=(?P<reply>.*)$")
TOFIR_RE = re.compile(r"TOFIR=(?P<body>[^A-WYZ]*)")

# ---- explore nav plan U1 ----
EXPLORE_PACKAGE = "com.miko3.mode.explore"
EXPLORE_ACTIVITY = f"{EXPLORE_PACKAGE}/.MainActivity"
CAL_NAME = "explore-calibration.properties"
CAL_PATH = f"/data/data/{EXPLORE_PACKAGE}/files/{CAL_NAME}"
SPIN_TAG = "MikoExploreSpin"
MARK_TAG = "MikoExploreSpinMark"
AXES = "xyz"
# IMUGY= up to the next section key; the fields are signed, so '-' is part of the body.
IMUGY_RE = re.compile(r"IMUGY=(?P<body>[^A-WYZ]*)(?=[A-WYZ])")
SIGNED_RE = re.compile(r"^-?\d{1,10}$")
WHEELS_RE = re.compile(r"Left=(?P<left>\d{1,10}),Right=(?P<right>\d{1,10}),")
STAMP_RE = re.compile(r"^(?P<month>\d\d)-(?P<day>\d\d) (?P<h>\d\d):(?P<m>\d\d):(?P<s>\d\d)\.(?P<ms>\d{3})")
# "I ExploreBrain: spin LEFT" (threadtime) or "I/ExploreBrain( 2249): spin LEFT" (time).
SPIN_NOTE_RE = re.compile(r"ExploreBrain(?:\(\s*\d+\))?: spin (?P<phase>still|LEFT|RIGHT|halted|off)\b")
MARK_RE = re.compile(MARK_TAG + r"(?:\(\s*\d+\))?: ")
# Skip the start of each still spell for the bias: he may still be coasting.
SETTLE_S = 1.0
# A yaw axis must turn at least this many times its still-spell noise.
MIN_SIGNAL_TO_NOISE = 10.0
# The largest rate a signed 16-bit gyro register can report.
GYRO_FULL_SCALE = 32767


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


# ---- gyro (explore nav plan U1, KTD1) ----

def gyro_fields(reply):
    """The three signed IMUGY rates, or None unless all three read cleanly and the section
    ends at the next key (as shared/SensorReply reads them)."""
    m = IMUGY_RE.search(reply)
    if not m:
        return None
    fields = m.group("body").split(",")
    if len(fields) != 3 or not all(SIGNED_RE.match(f) for f in fields):
        return None
    return tuple(int(f) for f in fields)


def _stamp_ms(line):
    m = STAMP_RE.match(line)
    if not m:
        return None
    t = datetime.datetime(2000, int(m["month"]), int(m["day"]), int(m["h"]), int(m["m"]), int(m["s"]))
    return int((t - datetime.datetime(2000, 1, 1)).total_seconds()) * 1000 + int(m["ms"])


def parse_circle_log(text):
    """A --gyro-circle capture's logcat text as {records, phases, marks}, times in seconds
    from its first event: records are (t, (x, y, z), (left, right) or None) for each POWER
    reply that carried the gyro; phases are (t, "still"|"LEFT"|"RIGHT"|"halted"|"off") from
    ExploreSpin's notes; marks are the owner's Enter presses."""
    events = []
    for line in text.splitlines():
        ms = _stamp_ms(line)
        if ms is None:
            continue
        raw = LINE_RE.search(line)
        if raw:
            g = gyro_fields(raw.group("reply")) if raw.group("sent") == "POWER" else None
            if g is not None:
                w = WHEELS_RE.search(raw.group("reply"))
                events.append((ms, "record", (g, (int(w["left"]), int(w["right"])) if w else None)))
            continue
        note = SPIN_NOTE_RE.search(line)
        if note:
            events.append((ms, "phase", note.group("phase")))
        elif MARK_RE.search(line):
            events.append((ms, "mark", None))
    log = {"records": [], "phases": [], "marks": []}
    if not events:
        return log
    events.sort(key=lambda e: e[0])
    t0 = events[0][0]
    for ms, kind, payload in events:
        t = (ms - t0) / 1000.0
        if kind == "record":
            log["records"].append((t, payload[0], payload[1]))
        elif kind == "phase":
            log["phases"].append((t, payload))
        else:
            log["marks"].append(t)
    return log


def _segments(log):
    """(start, end, phase) for each spin phase; the last runs past the last record."""
    phases = log["phases"]
    end_of_log = max([t for t, _, _ in log["records"]] + [t for t, _ in phases] + [0.0]) + 1.0
    return [(t, phases[i + 1][0] if i + 1 < len(phases) else end_of_log, p) for i, (t, p) in enumerate(phases)]


def integrate(records, bias, t0, t1):
    """Per axis, the integral of (rate - bias) over [t0, t1] in counts x seconds. Each
    reading holds until the next one (the MCU sends a rate, not a sample of a curve)."""
    total = [0.0, 0.0, 0.0]
    for (ta, g, _), (tb, _, _) in zip(records, records[1:]):
        overlap = min(tb, t1) - max(ta, t0)
        if overlap > 0:
            for a in range(3):
                total[a] += (g[a] - bias[a]) * overlap
    return total


def gyro_circle(log):
    """The yaw axis, sign and scale from a --gyro-circle capture (see the module doc).
    Returns {axis, sign, countSecondsPer360, perTurn, secondsPerTurn, turns, bias, noise};
    raises ValueError when the capture cannot give one, including when no axis turns
    clearly both ways (the plan's stop condition: no usable yaw signal)."""
    records = sorted(log["records"])
    segments = _segments(log)
    still = [g for t, g, _ in records
             if any(p == "still" and s + SETTLE_S <= t < e for s, e, p in segments)]
    if len(still) < 2:
        raise ValueError("no still spell with readings: the bias needs him still first "
                         "(is the floor calibration pushed, and the lease held?)")
    bias = tuple(statistics.mean(g[a] for g in still) for a in range(3))
    noise = tuple(max(statistics.pstdev(g[a] for g in still), 1.0) for a in range(3))

    sums = {d: [0.0, 0.0, 0.0] for d in ("LEFT", "RIGHT")}
    turns = {"LEFT": 0, "RIGHT": 0}
    seconds = {"LEFT": 0.0, "RIGHT": 0.0}
    for s, e, p in segments:
        if p not in sums:
            continue
        marks = [m for m in log["marks"] if s < m < e]
        if len(marks) < 2:
            continue
        for a, v in enumerate(integrate(records, bias, marks[0], marks[-1])):
            sums[p][a] += v
        turns[p] += len(marks) - 1
        seconds[p] += marks[-1] - marks[0]
    for d in ("LEFT", "RIGHT"):
        if turns[d] == 0:
            raise ValueError(f"no full turn marked while turning {d}: press Enter at least twice "
                             f"(a whole turn apart) during a {d} turn")

    per_turn = {d: [v / turns[d] for v in sums[d]] for d in sums}
    rate = {d: [v / seconds[d] for v in sums[d]] for d in sums}
    usable = [a for a in range(3)
              if per_turn["LEFT"][a] * per_turn["RIGHT"][a] < 0
              and min(abs(rate["LEFT"][a]), abs(rate["RIGHT"][a])) >= MIN_SIGNAL_TO_NOISE * noise[a]]
    if not usable:
        raise ValueError("no usable yaw signal: no gyro axis turns clearly one way going left and the "
                         "other going right (per-turn integrals: LEFT "
                         + ", ".join(f"{v:.0f}" for v in per_turn["LEFT"]) + "; RIGHT "
                         + ", ".join(f"{v:.0f}" for v in per_turn["RIGHT"]) + ")")
    axis = max(usable, key=lambda a: min(abs(rate["LEFT"][a]), abs(rate["RIGHT"][a])))
    return {
        "axis": AXES[axis],
        "sign": 1 if per_turn["LEFT"][axis] > 0 else -1,
        "countSecondsPer360": (abs(per_turn["LEFT"][axis]) + abs(per_turn["RIGHT"][axis])) / 2,
        "perTurn": {d: abs(per_turn[d][axis]) for d in per_turn},
        "secondsPerTurn": {d: seconds[d] / turns[d] for d in seconds},
        "turns": turns,
        "bias": bias,
        "noise": noise,
    }


def describe_circle(result):
    left, right = result["perTurn"]["LEFT"], result["perTurn"]["RIGHT"]
    return "\n".join([
        f"yaw axis: {result['axis']}  sign: {result['sign']:+d} (left turns read positive)",
        f"scale: {result['countSecondsPer360']:.1f} counts x s per 360 deg "
        f"(LEFT {left:.1f} over {result['turns']['LEFT']} turns, RIGHT {right:.1f} over "
        f"{result['turns']['RIGHT']}; they differ by {abs(left - right) / max(left, right) * 100:.1f}%)",
        f"a turn takes: LEFT {result['secondsPerTurn']['LEFT']:.2f} s, RIGHT {result['secondsPerTurn']['RIGHT']:.2f} s",
        "still bias: " + ", ".join(f"{AXES[a]}={result['bias'][a]:.1f}" for a in range(3))
        + "  noise: " + ", ".join(f"{AXES[a]}={result['noise'][a]:.1f}" for a in range(3)),
    ])


def straight_drive_summary(log, bias, calibration):
    """What the gyro did while the wheels ran: wheel travel, each axis's offset from the
    still bias and spread, any saturated axis, and (with a calibration) the heading drift."""
    records = sorted(log["records"])
    if len(records) < 2:
        return "no gyro readings in the drive window"
    wheels = [w for _, _, w in records if w is not None]
    lines = []
    if wheels and (wheels[-1][0] != wheels[0][0] or wheels[-1][1] != wheels[0][1]):
        lines.append(f"wheels moved: left={wheels[-1][0] - wheels[0][0]} right={wheels[-1][1] - wheels[0][1]}")
    else:
        lines.append("WARNING: the wheels did not move (floor sensors uncalibrated, or he paused throughout)")
    for a in range(3):
        values = [g[a] for _, g, _ in records]
        line = (f"{AXES[a]}: mean-bias={statistics.mean(values) - bias[a]:+.1f} "
                f"std={statistics.pstdev(values):.1f} range=[{min(values)}, {max(values)}]")
        if any(abs(v) >= GYRO_FULL_SCALE for v in values):
            line += "  SATURATED"
        lines.append(line)
    duration = records[-1][0] - records[0][0]
    if calibration:
        a = AXES.index(calibration["axis"])
        counts = integrate(records, bias, records[0][0], records[-1][0])[a]
        degrees = calibration["sign"] * counts * 360.0 / calibration["countSecondsPer360"]
        lines.append(f"heading drift: {degrees:.1f} deg over {duration:.1f} s (near 0 if he drove straight)")
    return "\n".join(lines)


# ---- the calibration file, shared with qa-explore-mode.py (explore nav plan U1) ----

def parse_properties(text):
    """key -> value for each 'key=value' line of a java.util.Properties file, in order.
    Comments, blank lines, and anything without '=' (such as a failed cat's error) are
    skipped."""
    props = {}
    for line in text.splitlines():
        line = line.strip()
        if not line or line[0] in "#!" or "=" not in line:
            continue
        key, value = line.split("=", 1)
        props[key.strip()] = value.strip()
    return props


def merge_properties(text, updates):
    """The file with the updates set: existing keys keep their place, new ones follow."""
    props = parse_properties(text)
    props.update({k: str(v) for k, v in updates.items()})
    return "".join(f"{k}={v}\n" for k, v in props.items())


def gyro_calibration_keys(result):
    """The keys ExploreCalibration.readGyro reads."""
    return {"gyroAxis": result["axis"], "gyroSign": str(result["sign"]),
            "gyroCountSecondsPer360": f"{result['countSecondsPer360']:.2f}"}


def write_calibration_keys(adb_fn, keys):
    """Read-modify-write the app's calibration file over root adb: pull it, merge in
    the keys, push it back, so the other script's keys survive. adb_fn(*args,
    check=True) runs one adb command and returns its stdout."""
    existing = adb_fn("shell", "cat", CAL_PATH, check=False)
    push_calibration_text(adb_fn, merge_properties(existing, keys))


def push_calibration_text(adb_fn, text):
    files = CAL_PATH.rsplit("/", 1)[0]
    with tempfile.NamedTemporaryFile("w", suffix=".properties", delete=False) as f:
        f.write(text)
        local = f.name
    try:
        adb_fn("push", local, f"/data/local/tmp/{CAL_NAME}")
    finally:
        Path(local).unlink()
    owner = adb_fn("shell", "stat", "-c", "%u:%g", f"/data/data/{EXPLORE_PACKAGE}").strip()
    adb_fn("shell", "mkdir", "-p", files)
    adb_fn("shell", "cp", f"/data/local/tmp/{CAL_NAME}", CAL_PATH)
    adb_fn("shell", "chown", "-R", owner, files)
    adb_fn("shell", "chmod", "600", CAL_PATH)
    adb_fn("shell", "restorecon", "-R", files, check=False)
    adb_fn("shell", "rm", f"/data/local/tmp/{CAL_NAME}", check=False)


class LogcatStream:
    """Streams logcat into memory: a 10 Hz raw-reply log overflows the ring buffer long
    before a spin capture ends, so `logcat -d` afterwards would lose the start."""

    def __init__(self, serial, filters):
        self.lines = []
        self.proc = subprocess.Popen(["adb", "-s", serial, "logcat", "-v", "threadtime", "-s"] + filters,
                                     stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                     stdin=subprocess.DEVNULL, text=True, errors="replace")
        self.reader = threading.Thread(target=self._read, daemon=True)
        self.reader.start()

    def _read(self):
        for line in self.proc.stdout:
            self.lines.append(line.rstrip("\n"))

    def stop(self):
        if self.proc.poll() is None:
            self.proc.terminate()
        self.reader.join(timeout=5)
        return "\n".join(self.lines)


def run_gyro_circle(serial, drive_seconds):
    def adb_fn(*args, check=True):
        return adb(serial, *args, check=check)

    print("\nGyro circle (explore nav plan U1). Put him on open floor with a mark (tape) under his nose.\n"
          "He stays still ~3 s, turns LEFT ~20 s, still, turns RIGHT, and round again. Press Enter\n"
          "each time his nose passes the mark (the first press starts a turn's count), at least 3\n"
          "presses per direction. Type q + Enter when done; he then wanders normally for a short\n"
          "straight-drive check.")
    input(">> Press Enter to start ")
    adb(serial, "shell", "setprop", f"log.tag.{RAW_TAG}", "DEBUG")
    adb(serial, "logcat", "-c")
    stream = LogcatStream(serial, [f"{RAW_TAG}:D", "ExploreBrain:I", f"{MARK_TAG}:I"])
    try:
        adb(serial, "shell", "setprop", f"log.tag.{SPIN_TAG}", "DEBUG")
        adb(serial, "shell", "am", "start", "-n", EXPLORE_ACTIVITY)
        presses = 0
        while input(">> Enter = nose passes the mark, q = done: ").strip().lower() != "q":
            adb(serial, "shell", "log", "-t", MARK_TAG, "mark")
            presses += 1
            print(f"   mark {presses}")
        adb(serial, "shell", "setprop", f"log.tag.{SPIN_TAG}", "INFO")
        print(f"   spin off; logging {drive_seconds:.0f} s of normal wandering")
        time.sleep(drive_seconds)
    finally:
        adb(serial, "shell", "setprop", f"log.tag.{SPIN_TAG}", "INFO", check=False)
        adb(serial, "shell", "setprop", f"log.tag.{RAW_TAG}", "INFO", check=False)
        text = stream.stop()

    log = parse_circle_log(text)
    off = next((t for t, p in log["phases"] if p == "off"), None)
    circle = dict(log, records=[r for r in log["records"] if off is None or r[0] < off])
    drive = dict(log, records=[r for r in log["records"] if off is not None and r[0] >= off])
    print(f"\n== gyro circle ==\nreadings: {len(circle['records'])}  marks: {len(log['marks'])}  "
          f"phases: {' '.join(p for _, p in log['phases'])}")
    try:
        result = gyro_circle(circle)
    except ValueError as e:
        print(f"!! {e}")
        result = None
    if result:
        print(describe_circle(result))
    bias = result["bias"] if result else (0.0, 0.0, 0.0)
    print(f"\n== straight drive ==\n{straight_drive_summary(drive, bias, result)}")
    print("\nExplore is still running; exit it from the launcher when done.")
    if result and input("\n>> merge this gyro calibration into the robot's file? [y/N] ").strip().lower() == "y":
        write_calibration_keys(adb_fn, gyro_calibration_keys(result))
        print(f"   wrote {', '.join(f'{k}={v}' for k, v in gyro_calibration_keys(result).items())}")


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--serial", default=DEFAULT_SERIAL)
    ap.add_argument("--seconds", type=float, default=10.0,
                    help="length of each capture window (and of --gyro-circle's drive check)")
    ap.add_argument("--activity", default=DEFAULT_ACTIVITY,
                    help="a mode activity that holds DirectMotorDriver (keeps the POWER poll running)")
    ap.add_argument("--non-interactive", action="store_true", help="passive baseline only")
    ap.add_argument("--save-fixtures", metavar="DIR", help="write captured records under DIR")
    ap.add_argument("--gyro-circle", action="store_true",
                    help="guided gyro yaw calibration with Explore spinning in place (explore nav plan U1)")
    args = ap.parse_args()

    if ":" in args.serial:
        subprocess.run(["adb", "connect", args.serial], capture_output=True, timeout=30)
    if args.gyro_circle:
        run_gyro_circle(args.serial, args.seconds)
        return
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
