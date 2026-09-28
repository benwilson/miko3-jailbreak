#!/usr/bin/env python3
"""qa-direction-chip.py — identify the NC direction chip on /dev/ttyS1 without
ever writing to it, and once the port is confirmed, set and calibrate the
properties the launcher opens it from (hey-miko plan U1; R8, KTD10, KTD12, KTD13).

Identification (the default run) is read-only. Over adb it gathers:
  holder      which process, if any, holds /dev/ttyS1 open;
  driver      /proc/tty/driver/* and the port's tx/rx counters;
  dmesg       kernel lines about the UART, ttyS1 and the DSP;
  dtree       the device-tree serial1 alias and the gpio_dsp nodes;
  settings    the port's termios (stty -a, reading through a read-only redirect);
  vendor_log  any vendor nc_dsp log lines, in logcat or in files;
  passive     five seconds of whatever the chip sends, read through `cat` under
              `timeout` and parsed for XXUB frames with a valid CRC32.
The passive read runs only when the settings show echo off: a tty with echo on
sends every byte it receives back out, which would be a write to the chip.
Toybox has no non-blocking open, so `timeout` bounds the open and the read.

The verdict follows KTD13's fixed rule. The port is CONFIRMED only when
  (frame)       the passive read holds an XXUB frame with a valid CRC,
  (vendor log)  a vendor nc_dsp log line names ttyS1, or
  (owner)       the owner passes --owner-confirms after reading the evidence.
Otherwise it is UNCONFIRMED and nothing is written anywhere. The first two are
expected to find nothing on this robot (the chip only answers requests, and the
vendor app that talks to it has always been disabled here), so the owner's
confirmation is the expected route.

On CONFIRMED it sets persist.miko3.voice_dir.port to /dev/ttyS1, restarts the
launcher so it opens the chip afresh, and runs the launcher's ears probe once
(scripts/qa-ears-probe.py's route). Unless that probe shows the NC backend with
an XXUB reply (probe_shows_nc), it unsets the property again, on every exit
including Ctrl-C.

--calibrate walks the owner through front, left, right and behind with the same
probe, takes the chip's raw 0..255 reading at each, fits zero, sign and degrees
per step, and sets persist.miko3.voice_dir.zero, .sign and .scale. The angle the
launcher derives is sign * ((raw - zero) mod 256) * scale, wrapped to +-180,
with his right at +90. --readings front=10 left=202 ... skips the probe and fits
readings taken earlier (for example from qa-ears-probe.py --session's CSVs).

The script never writes to the node. Its only property writes are the
persist.miko3.voice_dir.* properties above, plus the ears probe's own debug
nonce (debug.miko3.ears_probe, set and cleared by qa-ears-probe.py's run). The
launcher's NC backend does talk to the chip once the port property is set; that
is the point of confirming first.

Usage:
  scripts/qa-direction-chip.py [--serial 192.168.19.74:5555] [--owner-confirms]
  scripts/qa-direction-chip.py --calibrate [--seconds 5]
  scripts/qa-direction-chip.py --calibrate --readings front=10 left=202 right=74 behind=138
"""
import argparse
import importlib.util
import re
import signal
import sys
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


ears = _load_ears()

NODE = "/dev/ttyS1"
PORT_PROPERTY = "persist.miko3.voice_dir.port"
ZERO_PROPERTY = "persist.miko3.voice_dir.zero"
SIGN_PROPERTY = "persist.miko3.voice_dir.sign"
SCALE_PROPERTY = "persist.miko3.voice_dir.scale"
LAUNCHER_PACKAGE = ears.LAUNCHER_PACKAGE
LAUNCHER_ACTIVITY = f"{LAUNCHER_PACKAGE}/.MainActivity"
LAUNCHER_SETTLE_S = 8
PROBE_SECONDS = 3
PROBE_ATTEMPTS = 3
PROBE_RETRY_S = 5
CALIBRATION_SECONDS = 5
CALIBRATION_PHRASE = "hey miko"
PASSIVE_SECONDS = 5

FRAME_MAGIC = b"XXUB"
MIN_FRAME = 14  # a request: XXUB, module, op, param, three zeros, CRC32 LE over the first 10
MAX_FRAME = 42  # a 38-byte reply plus a trailing CRC, if that is where its CRC sits

# The positions the owner stands at, and the angle each should read (his right is +90).
POSITIONS = (("front", 0), ("left", 270), ("right", 90), ("behind", 180))
MAX_FIT_ERROR_DEG = 45  # beyond this a reading sits nearer another quadrant than its own

# Read-only evidence (KTD13). None of these may write to the node; the tests check.
EVIDENCE_COMMANDS = {
    "holder": ("for p in /proc/[0-9]*; do for f in $p/fd/*; do "
               "[ \"$(readlink $f 2>/dev/null)\" = " + NODE + " ] && "
               "echo \"${p#/proc/} $(tr '\\0' ' ' < $p/cmdline 2>/dev/null)\"; "
               "done; done 2>/dev/null; true"),
    "driver": "for f in /proc/tty/driver/*; do echo \"== $f\"; cat \"$f\" 2>&1; done 2>&1 | head -60",
    "dmesg": "dmesg 2>&1 | grep -iE 'ttyS1|uart|nc_?dsp|gpio_dsp|mid_dsp' | tail -40",
    "dtree": ("tr '\\0' '\\n' < /proc/device-tree/aliases/serial1 2>&1; "
              "ls /sys/devices/platform/odm/odm:gpio_dsp 2>&1; "
              "find /proc/device-tree -maxdepth 3 -iname '*dsp*' 2>/dev/null | head -20"),
    "settings": f"timeout 3 sh -c 'stty -a < {NODE}' 2>&1",
    "vendor_log": ("logcat -d -b all 2>/dev/null | grep -iE 'nc_?dsp|ncdsp' | tail -50; "
                   "for f in $(find /sdcard /data/local/tmp /data/vendor /data/misc -maxdepth 4 "
                   "-iname '*nc*dsp*' 2>/dev/null | head -10); do echo \"== $f\"; "
                   "grep -iE 'ttyS1' \"$f\" 2>/dev/null | tail -20; done"),
}
PASSIVE_READ = f"timeout {PASSIVE_SECONDS} cat {NODE} 2>/dev/null | od -An -tx1 -v"

Evidence = namedtuple("Evidence", "sections passive passive_note")
Frame = namedtuple("Frame", "offset length hex")
Verdict = namedtuple("Verdict", "confirmed reason detail")
Fit = namedtuple("Fit", "zero sign scale max_error_deg")


class CalibrationError(ValueError):
    """The readings cannot give a trustworthy zero, sign and scale."""


# --- frames ---

def request_frame(module, op, param):
    """A 14-byte request: XXUB, module, op, param, three zeros, CRC32 LE over the first 10."""
    head = FRAME_MAGIC + bytes([module, op, param, 0, 0, 0])
    return head + zlib.crc32(head).to_bytes(4, "little")


def parse_od(text):
    """The bytes `od -An -tx1 -v` printed; lines that are not all hex pairs are skipped."""
    out = bytearray()
    for line in text.splitlines():
        tokens = line.split()
        if tokens and all(re.fullmatch(r"[0-9a-fA-F]{2}", t) for t in tokens):
            out.extend(int(t, 16) for t in tokens)
    return bytes(out)


def find_frames(data):
    """XXUB frames with a valid CRC32 (LE) over every byte before it. The request
    CRC sits at bytes 10..13; where a reply's sits is unverified (KTD11), so any
    length up to MAX_FRAME with a trailing CRC counts. The shortest match wins."""
    frames = []
    at = data.find(FRAME_MAGIC)
    while at >= 0:
        for length in range(MIN_FRAME, MAX_FRAME + 1):
            if at + length > len(data):
                break
            body, crc = data[at:at + length - 4], data[at + length - 4:at + length]
            if zlib.crc32(body).to_bytes(4, "little") == crc:
                frames.append(Frame(at, length, data[at:at + length].hex()))
                break
        at = data.find(FRAME_MAGIC, at + 1)
    return frames


# --- evidence ---

def echo_on(settings):
    """True or False from `stty -a` output, None when it is not stty output."""
    tokens = set(re.split(r"[\s;]+", settings))
    if "echo" in tokens:
        return True
    if "-echo" in tokens:
        return False
    return None


def port_counters(driver_text):
    """ttyS1's tx/rx counters from /proc/tty/driver (the line for port 1), or None."""
    m = re.search(r"^\s*1:\s.*?\btx:(\d+)\s+rx:(\d+)", driver_text, re.MULTILINE)
    return {"tx": int(m.group(1)), "rx": int(m.group(2))} if m else None


NC_NAME = re.compile(r"nc_?dsp", re.IGNORECASE)
TTYS1 = re.compile(r"ttyS1\b")


def vendor_log_lines(text):
    """Lines of an nc_dsp log (a logcat line naming nc_dsp, or a line of a file
    whose name does) that name ttyS1."""
    out, in_nc_file = [], False
    for line in text.splitlines():
        if line.startswith("== "):
            in_nc_file = bool(NC_NAME.search(line))
            continue
        if TTYS1.search(line) and (in_nc_file or NC_NAME.search(line)):
            out.append(line)
    return out


def gather(robot):
    """Runs the read-only evidence commands; the passive read only with echo off."""
    sections = {}
    for name, script in EVIDENCE_COMMANDS.items():
        print(f".. {name}")
        sections[name] = robot.adb("shell", script, check=False)
    echo = echo_on(sections["settings"])
    if echo is None:
        return Evidence(sections, None, "skipped: the port settings could not be read, so echo may be on")
    if echo:
        return Evidence(sections, None, "skipped: the port has echo on, so reading would send bytes back out")
    print(f".. passive read, {PASSIVE_SECONDS} s")
    data = parse_od(robot.adb("shell", PASSIVE_READ, check=False))
    return Evidence(sections, data, f"{len(data)} bytes in {PASSIVE_SECONDS} s")


def decide(evidence, owner_confirms):
    """KTD13's rule: a valid frame, then a vendor log naming ttyS1, then the owner."""
    frames = find_frames(evidence.passive) if evidence.passive else []
    if frames:
        return Verdict(True, "frame", [f"frame at byte {f.offset}, {f.length} bytes: {f.hex}" for f in frames])
    lines = vendor_log_lines(evidence.sections.get("vendor_log", ""))
    if lines:
        return Verdict(True, "vendor log", lines)
    if owner_confirms:
        return Verdict(True, "owner", ["the owner confirmed from the evidence shown (--owner-confirms)"])
    return Verdict(False, None, ["no XXUB frame with a valid CRC and no nc_dsp log naming ttyS1"])


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
             "== port settings", block(s.get("settings", "")),
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


def restart_launcher(robot, sleep=None):
    """So the launcher reads the voice_dir properties afresh. HOME alone does not restart it."""
    robot.adb("shell", "am", "force-stop", LAUNCHER_PACKAGE, check=False)
    robot.adb("shell", "am", "start", "-n", LAUNCHER_ACTIVITY, check=False)
    if sleep is not None:
        sleep(LAUNCHER_SETTLE_S)


def run_probe(robot, phrase=None, seconds=PROBE_SECONDS, http=None, sleep=time.sleep):
    """One launcher ears probe (qa-ears-probe.py's run), retried while the launcher comes up."""
    for attempt in range(1, PROBE_ATTEMPTS + 1):
        try:
            return ears.run(robot, seconds, phrase, http=http or ears.default_http)
        except ears.ProbeError as exc:
            if attempt == PROBE_ATTEMPTS:
                raise
            print(f"{exc}\n.. probe attempt {attempt} failed; retrying in {PROBE_RETRY_S} s")
            sleep(PROBE_RETRY_S)


# The launcher's probe answer, as U2 is expected to shape it. This function is the
# one place that decides whether it shows the chip; align it here after integration.
#   {"backend": "NC",                  VoiceDirection.Backend.NC's name
#    "raw_reply": "58585542...",       a raw chip reply as hex (spaces allowed), or a list of them
#    "seconds": 3,
#    "rows": [{..., "raw": 138, ...}]} the chip's raw 0..255 reading that second, or null
# qa-ears-probe.py's parse_answer reads raw_reply (NC_REPLY_FIELD) and raw (RAW_FIELD).
PROBE_NC_BACKEND = "NC"
PROBE_REPLY_PREFIX = FRAME_MAGIC.hex()  # "58585542"


def probe_shows_nc(answer):
    """(ok, why): the probe reports the NC backend and at least one XXUB reply."""
    backend = str(answer.backend).strip().upper()
    if backend != PROBE_NC_BACKEND:
        return False, f"the probe reports backend {answer.backend!r}, not {PROBE_NC_BACKEND}"
    replies = answer.nc_reply
    if isinstance(replies, str):
        replies = [replies]
    for reply in replies or []:
        h = re.sub(r"[\s:]", "", reply).lower()
        if re.fullmatch(r"(?:[0-9a-f]{2})+", h) and h.startswith(PROBE_REPLY_PREFIX):
            return True, f"backend NC with an XXUB reply {h[:48]}{'...' if len(h) > 48 else ''}"
    return False, f"the probe has no XXUB reply (nc_reply {answer.nc_reply!r})"


# --- identification ---

def identify(robot, owner_confirms, probe=run_probe, sleep=time.sleep):
    """Gathers the evidence, prints it and the verdict; on CONFIRMED sets the port,
    restarts the launcher and probes once, unsetting the port unless it shows NC."""
    evidence = gather(robot)
    print(format_evidence(evidence))
    verdict = decide(evidence, owner_confirms)
    print(format_verdict(verdict))
    if not verdict.confirmed:
        print(f"Nothing was written. Record this evidence in docs/robot-return.md. If it convinces you that "
              f"{NODE} is the NC chip, run again with --owner-confirms.")
        return 1
    set_prop(robot, PORT_PROPERTY, NODE)
    print(f"set {PORT_PROPERTY}={NODE}; restarting the launcher and probing once")
    kept = False
    try:
        restart_launcher(robot, sleep)
        try:
            answer = probe(robot)
        except ears.ProbeError as exc:
            print(f"{exc}\n!! the probe failed, so the port is not kept")
            return 3
        kept, why = probe_shows_nc(answer)
        print(("OK: " if kept else "!! ") + why)
    finally:
        if not kept:
            unset_prop(robot, PORT_PROPERTY)
            restart_launcher(robot)
    print(f"{PORT_PROPERTY} kept. Next: {Path(__file__).name} --calibrate")
    return 0 if kept else 3


# --- calibration ---

def _circular(a, b, period):
    d = (a - b) % period
    return min(d, period - d)


def _wrap180(deg):
    return (deg + 180.0) % 360.0 - 180.0


def position_reading(raws):
    """The raw reading that best stands for one position: the circular medoid of the
    readings (the one nearest all the others round the 0..255 circle), or None."""
    values = sorted(r for r in raws if r is not None)
    if not values:
        return None
    return min(values, key=lambda v: sum(_circular(v, w, 256) for w in values))


def fit_calibration(readings):
    """zero is the front reading. For each sign, the degrees per step is the least-
    squares fit through the origin of the steps from zero against the angles each
    position should read; the sign with the smaller worst error wins."""
    if "front" not in readings:
        raise CalibrationError("no front reading: zero is where front reads")
    if len(set(readings.values())) < 3:
        raise CalibrationError(f"only {len(set(readings.values()))} distinct readings; at least three are needed")
    zero = readings["front"]
    targets = dict(POSITIONS)
    pairs = [((raw - zero) % 256, targets[pos]) for pos, raw in readings.items() if pos != "front"]
    best = None
    for sign in (1, -1):
        wanted = [(d, t if sign == 1 else (360 - t) % 360) for d, t in pairs]
        denom = sum(d * d for d, _ in wanted)
        if denom == 0:
            continue
        scale = sum(d * t for d, t in wanted) / denom
        if scale <= 0:
            continue
        err = max(abs(_wrap180(d * scale - t)) for d, t in wanted)
        if best is None or err < best.max_error_deg:
            best = Fit(zero, sign, scale, err)
    if best is None:
        raise CalibrationError("the readings give no rotation at all")
    if best.max_error_deg > MAX_FIT_ERROR_DEG:
        raise CalibrationError(f"the best fit's worst error is {best.max_error_deg:.0f} deg (over "
                               f"{MAX_FIT_ERROR_DEG}); a reading sits nearer another position than its own")
    return best


def calibration_props(fit):
    scale = f"{fit.scale:.5f}".rstrip("0").rstrip(".")
    return [(ZERO_PROPERTY, str(fit.zero)), (SIGN_PROPERTY, str(fit.sign)), (SCALE_PROPERTY, scale)]


def parse_readings(items):
    """--readings front=10 left=202 ... as {position: raw}."""
    names = dict(POSITIONS)
    out = {}
    for item in items:
        pos, sep, value = item.partition("=")
        if not sep or pos not in names:
            raise ValueError(f"bad reading {item!r}; use position=raw with position one of {', '.join(names)}")
        try:
            raw = int(value)
        except ValueError:
            raise ValueError(f"bad reading {item!r}: {value!r} is not a number")
        if not 0 <= raw <= 255:
            raise ValueError(f"bad reading {item!r}: raw readings run 0..255")
        out[pos] = raw
    return out


def calibrate(robot, probe=run_probe, ask=None, readings=None, seconds=CALIBRATION_SECONDS):
    """Walks the owner through the positions (or takes given readings), fits and sets
    zero, sign and scale. Refuses, writing nothing, without a confirmed port."""
    port = robot.adb("shell", "getprop", PORT_PROPERTY, check=False).strip()
    if not port:
        print(f"!! {PORT_PROPERTY} is not set: identify and confirm the port first "
              f"({Path(__file__).name} [--owner-confirms]). Nothing was written.")
        return 1
    if readings is None:
        if ask is None:
            ask = lambda text: input(text)  # noqa: E731
        readings = {}
        for pos, angle in POSITIONS:
            ask(f">> {pos.upper()}: stand 1.5 m to his {pos} ({angle} deg) and say '{CALIBRATION_PHRASE}' "
                f"a few times over {seconds} s; press Enter to start ")
            answer = probe(robot, phrase=CALIBRATION_PHRASE, seconds=seconds)
            ok, why = probe_shows_nc(answer)
            if not ok:
                print(f"!! {why}. Nothing was written.")
                return 1
            raws = [r.get(ears.RAW_FIELD) for r in answer.rows]
            reading = position_reading(raws)
            got = len([r for r in raws if r is not None])
            print(f"{pos}: {got} raw readings" + ("" if reading is None else f", taking {reading}"))
            if reading is not None:
                readings[pos] = reading
    print("readings: " + ", ".join(f"{p}={r}" for p, r in readings.items()))
    try:
        fit = fit_calibration(readings)
    except CalibrationError as exc:
        print(f"!! {exc}. Nothing was written.")
        return 1
    print(f"fit: zero {fit.zero}, sign {fit.sign:+d}, scale {fit.scale:.5f} deg/step, "
          f"worst error {fit.max_error_deg:.1f} deg")
    for key, value in calibration_props(fit):
        set_prop(robot, key, value)
        print(f"set {key}={value}")
    restart_launcher(robot)
    return 0


# --- main ---

def build_parser():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--serial", default=ears.DEFAULT_SERIAL)
    ap.add_argument("--owner-confirms", action="store_true",
                    help=f"the owner confirms from the evidence that {NODE} is the NC chip")
    ap.add_argument("--calibrate", action="store_true",
                    help="fit and set zero, sign and scale from front, left, right and behind")
    ap.add_argument("--readings", nargs="+", metavar="POS=RAW",
                    help="with --calibrate: fit these raw readings instead of probing")
    ap.add_argument("--seconds", type=int, default=CALIBRATION_SECONDS,
                    help="with --calibrate: probe length per position")
    return ap


def main(argv=None):
    args = build_parser().parse_args(argv)
    if args.readings and not args.calibrate:
        raise ears.ProbeError("!! --readings needs --calibrate")
    readings = None
    if args.readings:
        try:
            readings = parse_readings(args.readings)
        except ValueError as exc:
            raise ears.ProbeError(f"!! {exc}")
    robot = ears.Robot(args.serial)
    robot.ensure_reachable()
    # A SIGTERM (a closed terminal, a kill) must reach the finally blocks like Ctrl-C does.
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(143))
    try:
        if args.calibrate:
            return calibrate(robot, readings=readings, seconds=args.seconds)
        return identify(robot, args.owner_confirms)
    except KeyboardInterrupt:
        print("\ninterrupted", file=sys.stderr)
        return 130


if __name__ == "__main__":
    sys.exit(main())
