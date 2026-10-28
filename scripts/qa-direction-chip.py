#!/usr/bin/env python3
"""qa-direction-chip.py — identify the NC direction chip on /dev/ttyS1 without
ever writing to it, confirm the launcher opens it, and set the side thresholds
the launcher turns its readings into left or right with (hey-miko plan U1; R8,
KTD10, KTD12, KTD13).

What the chip does (robot, 2026-09-29): it STREAMS 19-byte frames by itself,
about one or two a second, and nothing ever needs to write to it:
  XXUB, a3, 03, a sequence byte, 01 05 00, CRC32 LE over bytes 0..9 at 10..13,
  then a 5-byte payload whose byte 0 is the raw reading.
It tells left from right only, not front from back: with the owner about 1 m
away, right read 30-45, front about 80, behind 85-100, left 95-110. So no
full-circle zero/sign/scale fits; the launcher's side mode uses two thresholds
instead: persist.miko3.voice_dir.left (raw at or above is left) and
persist.miko3.voice_dir.right (raw at or below is right), with zero, sign and
scale unset.

Identification (the default run) never opens /dev/ttyS1. It reads /proc, /sys
and logs over adb:
  holder      which process, if any, holds /dev/ttyS1 open;
  driver      /proc/tty/driver/* and the port's tx/rx counters;
  dmesg       kernel lines about the UART, ttyS1 and the DSP;
  dtree       the device-tree serial1 alias and the gpio_dsp nodes;
  vendor_log  any vendor nc_dsp log lines (by logcat tag), or nc_dsp files.
The port is CONFIRMED only when a vendor nc_dsp log line names ttyS1, or the
owner passes --owner-confirms after reading the evidence. Otherwise nothing is
written anywhere.

On CONFIRMED it sets persist.miko3.voice_dir.port to /dev/ttyS1, restarts the
launcher and asks the owner to say something (the NC backend opens on the first
speech). It keeps the property only if, within about 15 s of the restart, the
restarted launcher logs `voice direction: backend NC` and nothing logs a
`has died` or `SIG_DFL` for it. Otherwise it unsets the property and restarts
the launcher again, on every exit including Ctrl-C. (The launcher's ears probe
crashes the launcher every time, on main too, so nothing here uses it.)

--calibrate reads the chip's stream directly. First, once, it checks with a
read-only `stty -F /dev/ttyS1 -g` that the launcher left the port raw: input
flags 0 and no ECHO in the local flags. It refuses otherwise, because opening a
tty that echoes writes back to it. Then for front, left, right and behind it
asks the owner to stand there, press Enter and count aloud, and captures 11 s:
  exec 3</dev/ttyS1; timeout 11 cat <&3 > /data/local/tmp/cal.bin; exec 3<&-
which opens the node read-only; the file is pulled and parsed here. It prints
each position's median and range and suggests
  right = midpoint of the right median and the lowest front/behind median,
  left  = midpoint of the left median and the highest front/behind median,
each rounded to a multiple of 5. It refuses unless the medians order as
right < front/behind < left with a gap of at least 10 on each side. With
--apply it sets .left and .right, unsets .zero, .sign and .scale and restarts
the launcher; without it, it only prints the suggestion. --readings
front=80 left=95,100,110 ... skips the capture and uses given readings (one
value, or several giving their median).

--watch captures the stream the same way (after the same check) for --seconds
(default 10) and prints one line per frame: its sequence byte, the raw reading
and the side under the thresholds set now.

The script never writes to the node, and opens it only in the two read-only
forms above. Its only property writes are persist.miko3.voice_dir.*.

Usage:
  scripts/qa-direction-chip.py [--serial 192.168.19.74:5555] [--owner-confirms]
  scripts/qa-direction-chip.py --calibrate [--apply] [--seconds 11]
  scripts/qa-direction-chip.py --calibrate --readings front=80 left=95,100,110 right=35 behind=90 [--apply]
  scripts/qa-direction-chip.py --watch [--seconds 10]
"""
import argparse
import importlib.util
import math
import re
import signal
import statistics
import sys
import tempfile
import time
import zlib
from collections import namedtuple
from pathlib import Path

HERE = Path(__file__).resolve().parent


def _load_ears():
    spec = importlib.util.spec_from_file_location("qa_ears_probe", HERE / "qa-ears-probe.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


ears = _load_ears()  # for Robot (adb), ProbeError and the defaults; never its probe

NODE = "/dev/ttyS1"
PORT_PROPERTY = "persist.miko3.voice_dir.port"
ZERO_PROPERTY = "persist.miko3.voice_dir.zero"
SIGN_PROPERTY = "persist.miko3.voice_dir.sign"
SCALE_PROPERTY = "persist.miko3.voice_dir.scale"
LEFT_PROPERTY = "persist.miko3.voice_dir.left"
RIGHT_PROPERTY = "persist.miko3.voice_dir.right"
LAUNCHER_PACKAGE = ears.LAUNCHER_PACKAGE
LAUNCHER_ACTIVITY = f"{LAUNCHER_PACKAGE}/.MainActivity"

# The stream check after confirmation: how long after the restart to look.
STREAM_CHECK_S = 15
BACKEND_NC = re.compile(r"voice direction: backend NC\b")
LAUNCHER_DEATH = re.compile(r"has died|SIG_DFL")
THREADTIME = re.compile(r"^\d\d-\d\d \d\d:\d\d:\d\d\.\d+\s+(\d+)\s+\d+\s+[VDIWEFA]\s")
START_PROC = (re.compile(r"Start proc (\d+):" + re.escape(LAUNCHER_PACKAGE) + r"(?:/|\s|$)"),
              re.compile(r"Start proc " + re.escape(LAUNCHER_PACKAGE) + r"\b.*?\bpid=(\d+)"))
ROBOT_TIME = re.compile(r"^\d\d-\d\d \d\d:\d\d:\d\d\.\d{3}$")

# Reading the stream: one read-only termios check, then a read-only capture.
TERMIOS_COMMAND = f"stty -F {NODE} -g"
ECHO = 0o10  # termios lflag ECHO
CAPTURE_REMOTE = "/data/local/tmp/cal.bin"
CAPTURE_SECONDS = 11
WATCH_SECONDS = 10
MAX_CAPTURE_SECONDS = 25  # adb's own timeout is ears.ADB_TIMEOUT (30 s)

FRAME_MAGIC = b"XXUB"
STREAM_FRAME = 19
STREAM_MODULE, STREAM_OP = 0xA3, 0x03
STREAM_FIXED = b"\x01\x05\x00"  # bytes 7..9

POSITIONS = ("front", "left", "right", "behind")
MIDDLE = ("front", "behind")
MIN_GAP = 10
THRESHOLD_STEP = 5

# Read-only evidence (KTD13). None of these may open the node, let alone write to it; the tests check.
EVIDENCE_COMMANDS = {
    # One ls per process: a shell loop per open file timed out over 300+ processes on the robot.
    "holder": ("for p in /proc/[0-9]*; do ls -l $p/fd 2>/dev/null | grep -q ' " + NODE + "$' && "
               "echo \"${p#/proc/} $(tr '\\0' ' ' < $p/cmdline 2>/dev/null)\"; "
               "done; true"),
    "driver": "for f in /proc/tty/driver/*; do echo \"== $f\"; cat \"$f\" 2>&1; done 2>&1 | head -60",
    "dmesg": "dmesg 2>&1 | grep -iE 'ttyS1|uart|nc_?dsp|gpio_dsp|mid_dsp' | tail -40",
    "dtree": ("tr '\\0' '\\n' < /proc/device-tree/aliases/serial1 2>&1; "
              "ls /sys/devices/platform/odm/odm:gpio_dsp 2>&1; "
              "find /proc/device-tree -maxdepth 3 -iname '*dsp*' 2>/dev/null | head -20"),
    "vendor_log": ("logcat -d -b all 2>/dev/null | grep -iE 'nc_?dsp|ncdsp' | tail -50; "
                   "for f in $(find /sdcard /data/local/tmp /data/vendor /data/misc -maxdepth 4 "
                   "-iname '*nc*dsp*' 2>/dev/null | head -10); do echo \"== $f\"; "
                   "grep -iE 'ttyS1' \"$f\" 2>/dev/null | tail -20; done"),
}

Evidence = namedtuple("Evidence", "sections passive passive_note")
StreamFrame = namedtuple("StreamFrame", "offset seq raw hex")
Verdict = namedtuple("Verdict", "confirmed reason detail")
Sides = namedtuple("Sides", "left right")


class CalibrationError(ValueError):
    """The readings cannot give trustworthy side thresholds."""


# --- the chip's stream ---

def stream_frames(data):
    """The chip's streamed direction frames in these bytes: XXUB a3 03 seq 01 05 00,
    a CRC32 LE over bytes 0..9 at 10..13, then five payload bytes (byte 0 the raw).
    Junk, torn frames and frames of any other shape are skipped."""
    frames = []
    at = data.find(FRAME_MAGIC)
    while at >= 0 and at + STREAM_FRAME <= len(data):
        f = data[at:at + STREAM_FRAME]
        if (f[4] == STREAM_MODULE and f[5] == STREAM_OP and f[7:10] == STREAM_FIXED
                and zlib.crc32(f[:10]).to_bytes(4, "little") == f[10:14]):
            frames.append(StreamFrame(at, f[6], f[14], f.hex()))
            at = data.find(FRAME_MAGIC, at + STREAM_FRAME)
        else:
            at = data.find(FRAME_MAGIC, at + 1)
    return frames


def termios_raw(g):
    """(ok, why) for `stty -g` output: input flags 0 and no ECHO in the local flags."""
    fields = g.strip().split(":")
    if len(fields) < 4 or not all(re.fullmatch(r"[0-9a-fA-F]+", f) for f in fields[:4]):
        return False, f"stty -g printed {g.strip()[:60]!r}, not a termios string"
    iflag, lflag = int(fields[0], 16), int(fields[3], 16)
    if iflag != 0:
        return False, f"input flags are {fields[0]}, not 0: the launcher has not left {NODE} raw"
    if lflag & ECHO:
        return False, f"local flags {fields[3]} have ECHO set: opening {NODE} could echo back to the chip"
    return True, f"input flags 0, echo off (local flags {fields[3]})"


def port_is_raw(robot):
    """The single pre-check before any capture: a read-only stty -g."""
    return termios_raw(robot.adb("shell", TERMIOS_COMMAND, check=False))


def capture_command(seconds):
    """Reads the node read-only for this long into CAPTURE_REMOTE. Never writes to it."""
    return f"exec 3<{NODE}; timeout {int(seconds)} cat <&3 > {CAPTURE_REMOTE}; exec 3<&-"


def capture(robot, seconds):
    """The stream's bytes over this many seconds (the port must already be checked raw)."""
    robot.adb("shell", capture_command(seconds), check=False)  # timeout exits 124
    with tempfile.TemporaryDirectory() as tmp:
        local = Path(tmp) / "cal.bin"
        robot.adb("pull", CAPTURE_REMOTE, str(local), check=False)
        data = local.read_bytes() if local.exists() else b""
    robot.adb("shell", f"rm -f {CAPTURE_REMOTE}", check=False)
    return data


# --- evidence ---

def port_counters(driver_text):
    """ttyS1's tx/rx counters from /proc/tty/driver (the line for port 1), or None."""
    m = re.search(r"^\s*1:\s.*?\btx:(\d+)\s+rx:(\d+)", driver_text, re.MULTILINE)
    return {"tx": int(m.group(1)), "rx": int(m.group(2))} if m else None


NC_NAME = re.compile(r"nc_?dsp", re.IGNORECASE)
TTYS1 = re.compile(r"ttyS1\b")


# A logcat threadtime line whose tag is the vendor's nc_dsp: date, time, pid, tid, level, tag.
NC_LOGCAT_LINE = re.compile(r"^\S+\s+\S+\s+\d+\s+\d+\s+[VDIWEF]\s+nc_?dsp\s*:", re.IGNORECASE)


def vendor_log_lines(text):
    """Lines of an nc_dsp log that name ttyS1: a logcat line whose tag is nc_dsp, or a
    line of a file whose name says nc_dsp. Matching the tag, not free text, keeps the
    evidence command's own logged echo (ADB_SERVICES) from confirming anything."""
    out, in_nc_file = [], False
    for line in text.splitlines():
        if line.startswith("== "):
            in_nc_file = bool(NC_NAME.search(line))
            continue
        if TTYS1.search(line) and (in_nc_file or NC_LOGCAT_LINE.search(line)):
            out.append(line)
    return out


def gather(robot):
    """Runs the evidence commands, none of which opens the node."""
    sections = {}
    for name, script in EVIDENCE_COMMANDS.items():
        print(f".. {name}")
        try:
            sections[name] = robot.adb("shell", script, check=False)
        except ears.ProbeError as exc:
            # One slow or failed step is missing evidence, not a reason to drop the rest.
            sections[name] = f"(unavailable: {exc})"
    return Evidence(sections, None, "not run: the port is never opened, since opening a tty can write to it")


def decide(evidence, owner_confirms):
    """KTD13's rule without the passive frame: a vendor log naming ttyS1, then the owner."""
    lines = vendor_log_lines(evidence.sections.get("vendor_log", ""))
    if lines:
        return Verdict(True, "vendor log", lines)
    if owner_confirms:
        return Verdict(True, "owner", ["the owner confirmed from the evidence shown (--owner-confirms)"])
    return Verdict(False, None, ["no nc_dsp log naming ttyS1"])


def format_verdict(verdict):
    head = f"CONFIRMED ({verdict.reason})" if verdict.confirmed else "UNCONFIRMED"
    return "\n".join([head] + [f"  {line}" for line in verdict.detail])


def format_evidence(evidence):
    def block(text):
        body = text.strip()
        return "\n".join(f"  {line}" for line in body.splitlines()) if body else "  (nothing)"

    s = evidence.sections
    counters = port_counters(s.get("driver", ""))
    parts = [f"== holder of {NODE}", block(s.get("holder", "")),
             "== driver", block(s.get("driver", "")),
             f"  ttyS1 counters: {'unreadable' if counters is None else 'tx %(tx)d rx %(rx)d' % counters}",
             "== dmesg", block(s.get("dmesg", "")),
             "== device tree", block(s.get("dtree", "")),
             "== vendor nc_dsp log", block(s.get("vendor_log", "")),
             f"== passive read: {evidence.passive_note}"]
    if evidence.passive:
        parts.append(block(evidence.passive.hex(" ")))
    return "\n".join(parts)


# --- properties and the launcher ---

def set_prop(robot, key, value):
    """setprop, then getprop to prove it took (persist.* needs adbd as root)."""
    robot.adb("shell", "setprop", key, value if value else '""')
    got = robot.adb("shell", "getprop", key, check=False).strip()
    if got != value:
        raise ears.ProbeError(f"!! setprop {key} did not take (reads {got!r}); persist.* properties need "
                              f"root: run `adb -s {robot.serial} root` and try again.")


def unset_prop(robot, key):
    """Best effort, never raises: this runs from finally blocks."""
    try:
        set_prop(robot, key, "")
        print(f"unset {key}")
    except Exception as exc:  # noqa: BLE001 - must not mask the original failure
        print(f"{exc}\n!! unset it by hand: adb -s {robot.serial} shell setprop {key} '\"\"'", file=sys.stderr)


def get_prop(robot, key):
    return robot.adb("shell", "getprop", key, check=False).strip()


def restart_launcher(robot):
    """So the launcher reads the voice_dir properties afresh. HOME alone does not restart it."""
    robot.adb("shell", "am", "force-stop", LAUNCHER_PACKAGE, check=False)
    robot.adb("shell", "am", "start", "-n", LAUNCHER_ACTIVITY, check=False)


# --- the stream check after confirmation ---

def launcher_pids(log, pidof=""):
    """The launcher's pids: from ActivityManager's Start proc lines, and pidof."""
    pids = {int(p) for p in pidof.split() if p.isdigit()}
    for line in log.splitlines():
        for pattern in START_PROC:
            m = pattern.search(line)
            if m:
                pids.add(int(m.group(1)))
    return pids


def _line_pid(line):
    m = THREADTIME.match(line)
    return int(m.group(1)) if m else None


def stream_check_verdict(log, pidof=""):
    """(ok, why): the restarted launcher logged the NC backend and did not die."""
    pids = launcher_pids(log, pidof)
    lines = log.splitlines()
    deaths = [l for l in lines if LAUNCHER_DEATH.search(l) and (LAUNCHER_PACKAGE in l or _line_pid(l) in pids)]
    if deaths:
        return False, f"the launcher died: {deaths[0].strip()}"
    if not pids:
        return False, "could not tell the restarted launcher's pid (no Start proc line, no pidof)"
    nc = [l for l in lines if BACKEND_NC.search(l) and _line_pid(l) in pids]
    if not nc:
        other = [l for l in lines if "voice direction: backend" in l and _line_pid(l) in pids]
        seen = f"; it logged: {other[-1].strip()}" if other else ""
        return False, f"the launcher did not log 'voice direction: backend NC'{seen}"
    return True, f"the launcher opened the chip: {nc[0].strip()}"


def stream_check(robot, sleep=time.sleep):
    """Restarts the launcher, asks the owner to speak, and reads the log since just
    before the restart for the NC backend line and any launcher death."""
    since = robot.adb("shell", "date '+%m-%d %H:%M:%S.000'", check=False).strip()
    restart_launcher(robot)
    print(f">> The launcher is restarting. SAY SOMETHING to the robot now (for example 'hey miko', "
          f"then a few words): the chip opens on the first speech. Watching the log for {STREAM_CHECK_S} s.")
    sleep(STREAM_CHECK_S)
    if not ROBOT_TIME.match(since):
        return False, f"could not read the robot's clock ({since!r}), so the log cannot be checked"
    log = robot.adb("shell", f"logcat -d -v threadtime -b main,system,crash -T '{since}'", check=False)
    pidof = robot.adb("shell", f"pidof {LAUNCHER_PACKAGE}", check=False)
    return stream_check_verdict(log, pidof)


# --- identification ---

def identify(robot, owner_confirms, sleep=time.sleep):
    """Gathers the evidence, prints it and the verdict; on CONFIRMED sets the port,
    restarts the launcher and keeps the port only if the launcher opens the chip."""
    evidence = gather(robot)
    print(format_evidence(evidence))
    verdict = decide(evidence, owner_confirms)
    print(format_verdict(verdict))
    if not verdict.confirmed:
        print(f"Nothing was written. Record this evidence in docs/robot-return.md. If it convinces you that "
              f"{NODE} is the NC chip, run again with --owner-confirms.")
        return 1
    set_prop(robot, PORT_PROPERTY, NODE)
    print(f"set {PORT_PROPERTY}={NODE}")
    kept = False
    try:
        kept, why = stream_check(robot, sleep)
        print(("OK: " if kept else "!! ") + why)
    finally:
        if not kept:
            unset_prop(robot, PORT_PROPERTY)
            restart_launcher(robot)
    if not kept:
        return 3
    print(f"{PORT_PROPERTY} kept. Next: {Path(__file__).name} --calibrate")
    return 0


# --- side calibration ---

def summarize(raws):
    """(median, low, high) of the readings, or None when there are none."""
    if not raws:
        return None
    med = statistics.median(raws)
    return (int(med) if med == int(med) else med), min(raws), max(raws)


def round_to_step(x):
    """To the nearest multiple of THRESHOLD_STEP, halves up."""
    return int(math.floor(x / THRESHOLD_STEP + 0.5)) * THRESHOLD_STEP


def suggest_sides(medians):
    """Side thresholds from each position's median: right is the midpoint of the right
    median and the lowest front/behind median, left the midpoint of the left median and
    the highest, each rounded to a multiple of 5. Refuses unless the medians order as
    right < front/behind < left with at least MIN_GAP on each side."""
    missing = [p for p in ("right", "left") if p not in medians]
    middle = [medians[p] for p in MIDDLE if p in medians]
    if missing or not middle:
        need = missing + ([] if middle else ["front or behind"])
        raise CalibrationError(f"no reading for {', '.join(need)}")
    right, left, low, high = medians["right"], medians["left"], min(middle), max(middle)
    shown = ", ".join(f"{p} {medians[p]}" for p in POSITIONS if p in medians)
    if right + MIN_GAP > low or high + MIN_GAP > left:
        raise CalibrationError(f"the medians ({shown}) do not order as right < front/behind < left with a "
                               f"gap of at least {MIN_GAP} on each side, so no thresholds separate the sides")
    sides = Sides(left=round_to_step((left + high) / 2), right=round_to_step((right + low) / 2))
    if not 0 <= sides.right < sides.left <= 255:
        raise CalibrationError(f"the thresholds {sides} are out of order or range")
    return sides


def parse_readings(items):
    """--readings front=80 left=95,100,110 ... as {position: [raw, ...]}."""
    out = {}
    for item in items:
        pos, sep, value = item.partition("=")
        if not sep or pos not in POSITIONS:
            raise ValueError(f"bad reading {item!r}; use position=raw[,raw...] with position one of "
                             f"{', '.join(POSITIONS)}")
        raws = []
        for part in value.split(","):
            try:
                raw = int(part)
            except ValueError:
                raise ValueError(f"bad reading {item!r}: {part!r} is not a number")
            if not 0 <= raw <= 255:
                raise ValueError(f"bad reading {item!r}: raw readings run 0..255")
            raws.append(raw)
        out[pos] = raws
    return out


def _fmt(x):
    return f"{x:g}" if isinstance(x, float) else str(x)


def calibrate(robot, ask=None, readings=None, apply=False, seconds=CAPTURE_SECONDS):
    """Captures (or takes given) readings at each position, prints the median and range
    of each, and suggests side thresholds; with apply, sets them and unsets the full
    calibration. Writes nothing unless apply and every check passes."""
    needs_port = apply or readings is None
    if needs_port and not get_prop(robot, PORT_PROPERTY):
        print(f"!! {PORT_PROPERTY} is not set: identify and confirm the port first "
              f"({Path(__file__).name} [--owner-confirms]). Nothing was written.")
        return 1
    if readings is None:
        ok, why = port_is_raw(robot)
        if not ok:
            print(f"!! refusing to read {NODE}: {why}. Let the launcher open the chip first (say something "
                  f"to the robot), then try again. Nothing was read or written.")
            return 1
        print(f"{NODE}: {why}")
        if ask is None:
            ask = lambda text: input(text)  # noqa: E731
        readings = {}
        for pos in POSITIONS:
            ask(f">> {pos.upper()}: stand about 1 m to the robot's {pos}, press Enter, then COUNT ALOUD "
                f"steadily for {seconds} s ")
            print(f".. capturing {seconds} s")
            readings[pos] = [f.raw for f in stream_frames(capture(robot, seconds))]
    medians = {}
    for pos in POSITIONS:
        if pos not in readings:
            continue
        summary = summarize(readings[pos])
        if summary is None:
            print(f"{pos}: no frames")
            continue
        med, lo, hi = summary
        medians[pos] = med
        print(f"{pos}: median {_fmt(med)} ({lo}..{hi}), {len(readings[pos])} readings")
    try:
        sides = suggest_sides(medians)
    except CalibrationError as exc:
        print(f"!! {exc}. Nothing was written.")
        return 1
    print(f"suggest: {RIGHT_PROPERTY}={sides.right} {LEFT_PROPERTY}={sides.left} "
          f"(raw <= {sides.right} is right, raw >= {sides.left} is left)")
    if not apply:
        print("Nothing was written. Run again with --apply to set them (and unset zero, sign and scale).")
        return 0
    for key in (ZERO_PROPERTY, SIGN_PROPERTY, SCALE_PROPERTY):
        set_prop(robot, key, "")
        print(f"unset {key}")
    for key, value in ((LEFT_PROPERTY, sides.left), (RIGHT_PROPERTY, sides.right)):
        set_prop(robot, key, str(value))
        print(f"set {key}={value}")
    restart_launcher(robot)
    print("restarted the launcher so it reads them")
    return 0


# --- watch ---

def current_sides(robot):
    """The side thresholds as the launcher would parse them now, or None."""
    try:
        left, right = int(get_prop(robot, LEFT_PROPERTY)), int(get_prop(robot, RIGHT_PROPERTY))
    except ValueError:
        return None
    return Sides(left, right) if 0 <= right < left <= 255 else None


def side_of(raw, sides):
    if sides is None:
        return "(no thresholds)"
    if raw >= sides.left:
        return "left"
    if raw <= sides.right:
        return "right"
    return "ahead/behind"


def watch(robot, seconds=WATCH_SECONDS):
    """Captures the stream and prints one line per frame with its side."""
    ok, why = port_is_raw(robot)
    if not ok:
        print(f"!! refusing to read {NODE}: {why}. Let the launcher open the chip first (say something "
              f"to the robot), then try again.")
        return 1
    sides = current_sides(robot)
    print(f"{NODE}: {why}; thresholds: " +
          ("none set" if sides is None else f"left >= {sides.left}, right <= {sides.right}"))
    if sides is not None and get_prop(robot, ZERO_PROPERTY):
        print(f"note: {ZERO_PROPERTY} is set, so the launcher uses the full calibration, not these sides")
    print(f".. capturing {seconds} s: speak from where you stand")
    frames = stream_frames(capture(robot, seconds))
    if not frames:
        print(f"!! no frames in {seconds} s")
        return 1
    counts = {}
    for f in frames:
        side = side_of(f.raw, sides)
        counts[side] = counts.get(side, 0) + 1
        print(f"seq {f.seq:3d}  raw {f.raw:3d}  {side}")
    print(f"{len(frames)} frames in {seconds} s: " + ", ".join(f"{n} {s}" for s, n in counts.items()))
    return 0


# --- main ---

def build_parser():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--serial", default=ears.DEFAULT_SERIAL)
    ap.add_argument("--owner-confirms", action="store_true",
                    help=f"the owner confirms from the evidence that {NODE} is the NC chip")
    ap.add_argument("--calibrate", action="store_true",
                    help="capture front, left, right and behind and suggest the side thresholds")
    ap.add_argument("--apply", action="store_true",
                    help="with --calibrate: set the suggested thresholds and unset zero, sign and scale")
    ap.add_argument("--readings", nargs="+", metavar="POS=RAW[,RAW...]",
                    help="with --calibrate: use these raw readings instead of capturing")
    ap.add_argument("--watch", action="store_true",
                    help="capture the stream and print each frame's raw reading and side")
    ap.add_argument("--seconds", type=int, default=None,
                    help=f"capture length (--calibrate: {CAPTURE_SECONDS} per position, --watch: "
                         f"{WATCH_SECONDS}; at most {MAX_CAPTURE_SECONDS})")
    return ap


def main(argv=None):
    ap = build_parser()
    args = ap.parse_args(argv)
    if (args.readings or args.apply) and not args.calibrate:
        ap.error("--readings and --apply need --calibrate")
    if args.watch and (args.calibrate or args.owner_confirms):
        ap.error("--watch runs on its own")
    if args.seconds is not None and not 1 <= args.seconds <= MAX_CAPTURE_SECONDS:
        ap.error(f"--seconds runs 1..{MAX_CAPTURE_SECONDS}")
    readings = None
    if args.readings:
        try:
            readings = parse_readings(args.readings)
        except ValueError as exc:
            ap.error(str(exc))
    robot = ears.Robot(args.serial)
    if not (readings and not args.apply):  # given readings, only suggested: no robot needed
        robot.ensure_reachable()
    # A SIGTERM (a closed terminal, a kill) must reach the finally blocks like Ctrl-C does.
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(143))
    try:
        if args.watch:
            return watch(robot, args.seconds or WATCH_SECONDS)
        if args.calibrate:
            return calibrate(robot, readings=readings, apply=args.apply,
                             seconds=args.seconds or CAPTURE_SECONDS)
        return identify(robot, args.owner_confirms)
    except KeyboardInterrupt:
        print("\ninterrupted", file=sys.stderr)
        return 130


if __name__ == "__main__":
    sys.exit(main())
