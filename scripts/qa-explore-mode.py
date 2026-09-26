#!/usr/bin/env python3
"""qa-explore-mode.py — calibrate the explore mode's sensors on the owner's desk,
then walk the acceptance examples (explore plan U8).

The mode never drives until a calibration file exists (KTD9). This script
builds that file from live readings: the owner holds the robot still, then puts
a hand in front, then holds its front over the desk edge, and the script
suggests thresholds between those clusters with the margin on the safe side,
writes the file into the app over root adb, and restarts the mode.

  python3 scripts/qa-explore-mode.py                    # calibrate, then every check
  python3 scripts/qa-explore-mode.py --only calibrate   # just the calibration
  python3 scripts/qa-explore-mode.py --only ae3,ae5     # just those checks

Steps: calibrate, ae1..ae6, stoptimer, wander, curiosity. AE3/AE4/AE5 and the stop
timer are staged with the mode's debug hooks (setprop log.tag.MikoExplore* DEBUG)
and checked in logcat; the rest need the owner's eyes and a y/n answer.

The curiosity step (camera curiosity plan U8) turns on MikoExploreCurious, so a
curiosity stop comes at every pause instead of every 20-40 s, and reports what he
saw and chose at each stop; the owner then answers for what they saw and heard.

  python3 scripts/qa-explore-mode.py --only curiosity --curious-seconds 90

The nav step (explore nav plan U8) first resets any leftover MikoExplore* debug
prop, then guides the owner through: the gyro circle (qa-explore-sensors.py's
--gyro-circle helper), the wall-and-plant wedge 5 times (each escape timed from
the brain's "wedged" line to its "free after" line, against 30 s; at least 4 of
5 pass), an open-versus-closed doorway, a person approach and the 10-minute
leave-alone (a 10-minute roam that also samples CPU temperature and battery at
its start and end, the camera-while-roaming fallback triggers), and a spoken
line's time to first audio. It prints counts and timings only.

  python3 scripts/qa-explore-mode.py --only nav
  python3 scripts/qa-explore-mode.py --only nav --nav-steps wedge,speech

roam-summary needs no robot step: it summarises a logcat capture (steers,
re-aims, CPL hiccups, wedges and their free-after times, coverage cells, blocked
turns, ...). With no --log it reads the robot's current logcat buffer.

  python3 scripts/qa-explore-mode.py --only roam-summary --log roam.txt
  adb logcat -v threadtime -s ExploreBrain ExploreCamera SpeechEngine | tee roam.txt
"""
import argparse
import importlib.util
import re
import ssl
import statistics
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

PACKAGE = "com.miko3.mode.explore"
ACTIVITY = f"{PACKAGE}/.MainActivity"
CAL_NAME = "explore-calibration.properties"
TOF_FAULT = 16383
ALL_STEPS = ("calibrate", "ae1", "ae2", "ae3", "ae4", "ae5", "ae6", "stoptimer", "wander", "curiosity", "nav")
# Accepted by --only but never part of the default run: it reads a log, not the robot.
LOG_STEPS = ("roam-summary",)

# explore nav plan U8: the nav step's sub-steps, in the order the owner runs them.
NAV_STEPS = ("gyro", "wedge", "doorway", "person", "speech")
# What the nav step's log captures listen to: the brain's notes, the camera's
# timings and warnings, and the launcher's speech engine (first audio).
NAV_TAGS = ("ExploreBrain", "ExploreCamera", "SpeechEngine")
WEDGE_TARGET_S = 30.0     # Success Criteria: free within about 30 s
WEDGE_PASSES_NEEDED = 4   # Definition of Done: at least 4 of 5 tries
IDLE_FIRST_AUDIO_S = 1.1  # SpeechTuning: medium voice to first sound when idle
FIRST_AUDIO_NEAR_S = 1.6  # "near the idle figure": within half a second of it
# ExploreCamera's warnings that mean the camera driver misbehaved.
CAMERA_ERRORS = ("camera disconnected", "no camera", "camera close not confirmed", "openness decode failed")
# Debug hooks an interrupted script can leave on; MikoDmdRaw logs every raw reply at 10 Hz.
HOOK_PREFIXES = ("MikoExplore", "MikoDmdRaw")

# What the brain logs at each curiosity decision (ExploreBrain.note), in the
# order a stop goes: the scan, what it saw, and how it ended.
CURIOSITY_EVENTS = ("curiosity stop", "saw ", "turning ", "re-centring", "approaching", "arrived",
                    "seen the ", "unsure what", "nothing interesting", "lost sight", "never got close",
                    "no new look", "camera gave no look", "hazard")

_sensors = None


def sensors_module():
    """scripts/qa-explore-sensors.py, for its logcat capture and TOFIR parsing."""
    global _sensors
    if _sensors is None:
        path = Path(__file__).resolve().parent / "qa-explore-sensors.py"
        spec = importlib.util.spec_from_file_location("qa_explore_sensors", path)
        _sensors = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(_sensors)
    return _sensors


def parse_only(value):
    """The --only list, validated; empty means every step in order. roam-summary
    reads a log rather than driving the robot, so it runs alone."""
    if not value:
        return list(ALL_STEPS)
    steps = [s.strip().lower() for s in value.split(",") if s.strip()]
    known = ALL_STEPS + LOG_STEPS
    unknown = [s for s in steps if s not in known]
    if unknown:
        raise ValueError(f"unknown step(s): {', '.join(unknown)} (known: {', '.join(known)})")
    if any(s in LOG_STEPS for s in steps) and len(steps) > 1:
        raise ValueError(f"{', '.join(LOG_STEPS)} runs alone")
    return steps


def parse_nav_steps(value):
    """The --nav-steps list, validated and put in NAV_STEPS order; empty means all."""
    if not value:
        return list(NAV_STEPS)
    steps = {s.strip().lower() for s in value.split(",") if s.strip()}
    unknown = sorted(steps - set(NAV_STEPS))
    if unknown:
        raise ValueError(f"unknown nav step(s): {', '.join(unknown)} (known: {', '.join(NAV_STEPS)})")
    return [s for s in NAV_STEPS if s in steps]


def readings(replies):
    """(tof, ir1, ir2) per reply, absent fields as -1, replies without TOFIR skipped."""
    out = []
    for reply in replies:
        fields = sensors_module().tofir_fields(reply)
        if not fields or fields[0] is None or not fields[0].isdigit():
            continue
        vals = [int(f) if f is not None and f.isdigit() else -1 for f in (fields + [None, None])[:3]]
        out.append(tuple(vals))
    return out


def suggest_calibration(clear, hand, edge, margin=0.3):
    """Thresholds from three captures of (tof, ir1, ir2) readings.

    Returns (calibration dict, notes). The obstacle threshold sits between the hand
    and clear clusters, nearer the clear side, so the robot stops early rather
    than late. The edge rule prefers an IR flag that separates edge from clear
    readings (the ToF may just read out of range over an edge); otherwise a ToF
    threshold above the clear cluster, nearer the clear side. A rule that cannot
    be separated is left off (-1) and noted; the mode will not drive without both.
    """
    notes = []
    cal = {"obstacleTofBelow": -1, "edgeTofAbove": -1, "edgeIr": -1, "edgeIrAbove": True}
    clear_tof = [r[0] for r in clear if r[0] != TOF_FAULT]
    hand_tof = [r[0] for r in hand if r[0] != TOF_FAULT]
    if not clear_tof:
        notes.append("no usable clear readings (tof at 16383 or missing): check the sensor first")
        return cal, notes
    if hand_tof and max(hand_tof) < min(clear_tof):
        gap = min(clear_tof) - max(hand_tof)
        cal["obstacleTofBelow"] = int(round(min(clear_tof) - margin * gap))
    else:
        notes.append("hand readings overlap the clear ones: no obstacle threshold")

    for idx, name in ((1, "ir1"), (2, "ir2")):
        clear_ir = {r[idx] for r in clear if r[idx] >= 0}
        edge_ir = {r[idx] for r in edge if r[idx] >= 0}
        if clear_ir and edge_ir and not clear_ir & edge_ir:
            above = min(edge_ir) > max(clear_ir)
            cal["edgeIr"] = max(clear_ir) if above else min(clear_ir)
            cal["edgeIrAbove"] = above
            notes.append(f"edge detected by the {name} flag ({sorted(clear_ir)} clear, {sorted(edge_ir)} edge)")
            break
    else:
        edge_tof = [r[0] for r in edge]
        if edge_tof and min(edge_tof) > max(clear_tof):
            gap = min(edge_tof) - max(clear_tof)
            cal["edgeTofAbove"] = int(round(max(clear_tof) + margin * gap))
        else:
            notes.append("edge readings overlap the clear ones and no IR flag separates them: no edge rule")
    return cal, notes


def calibration_complete(cal):
    return cal["obstacleTofBelow"] >= 0 and (cal["edgeTofAbove"] >= 0 or cal["edgeIr"] >= 0)


def calibration_values(cal):
    """The floor-sensor keys as ExploreCalibration reads them (java.util.Properties)."""
    return {k: str(cal[k]).lower() if isinstance(cal[k], bool) else str(cal[k])
            for k in ("obstacleTofBelow", "edgeTofAbove", "edgeIr", "edgeIrAbove")}


def calibration_text(cal):
    """The floor-sensor part of the app's calibration file."""
    return "".join(f"{k}={v}\n" for k, v in calibration_values(cal).items())


class Robot:
    def __init__(self, serial):
        self.serial = serial

    def adb(self, *args, check=True):
        # stdin=DEVNULL: adb would otherwise read the owner's y/n answers.
        r = subprocess.run(["adb", "-s", self.serial] + list(args), capture_output=True, text=True, timeout=60,
                           stdin=subprocess.DEVNULL)
        if check and r.returncode != 0:
            raise RuntimeError(f"adb {' '.join(args)}: {r.stderr.strip() or r.stdout.strip()}")
        return r.stdout

    def hook(self, tag, on):
        self.adb("shell", "setprop", f"log.tag.{tag}", "DEBUG" if on else "INFO")

    def start_mode(self):
        """Bring Explore up if it is not already (am start leaves a running one be)."""
        self.adb("shell", "am", "start", "-n", ACTIVITY)
        time.sleep(3)

    def restart_mode(self):
        self.adb("shell", "am", "force-stop", PACKAGE)
        self.adb("shell", "am", "start", "-n", ACTIVITY)
        time.sleep(3)

    def restart_and_log(self, tags):
        """Restart the mode and return what the given tags logged while it came up."""
        self.adb("logcat", "-c")
        self.restart_mode()
        return self.adb("logcat", "-d", "-s", *[f"{t}:V" for t in tags])

    def state(self):
        """GET /state through an adb forward (the robot has no curl); HTTP redirects
        to the mode's HTTPS port, whose certificate is self-signed."""
        self.adb("forward", "tcp:18446", "tcp:8446")
        ctx = ssl.create_default_context()
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        try:
            with urllib.request.urlopen("https://127.0.0.1:18446/state", context=ctx, timeout=5) as r:
                return r.read().decode()
        except OSError as e:
            return f"unreachable: {e}"

    def logcat_after(self, seconds, tags):
        self.adb("logcat", "-c")
        time.sleep(seconds)
        return self.adb("logcat", "-d", "-s", *[f"{t}:V" for t in tags])

    def capture(self, seconds):
        self.hook("MikoDmdRaw", True)
        try:
            return readings([r for s, r in sensors_module().capture(self.serial, seconds) if s == "POWER"])
        finally:
            self.hook("MikoDmdRaw", False)

    def stream(self, tags=NAV_TAGS):
        """A live logcat capture of the given tags from now on (qa-explore-sensors.py's
        LogcatStream: a 10-minute roam would overflow the ring buffer before `-d`)."""
        self.adb("logcat", "-c")
        return sensors_module().LogcatStream(self.serial, [f"{t}:I" for t in tags])

    def reset_hooks(self):
        """Every leftover debug hook back to INFO; returns what was set."""
        left = leftover_hooks(self.adb("shell", "getprop"))
        for tag in left:
            self.hook(tag, False)
        return left

    def thermals(self):
        return self.adb("shell", "for z in /sys/class/thermal/thermal_zone*; do "
                        "echo \"$(cat $z/type 2>/dev/null) $(cat $z/temp 2>/dev/null)\"; done", check=False)

    def battery(self):
        return battery_level(self.adb("shell", "dumpsys", "battery", check=False))

    def push_calibration(self, cal):
        """Read-modify-write the app's calibration file (explore nav plan U1): the floor
        keys replace their old values and any gyro keys from qa-explore-sensors.py stay."""
        sensors_module().write_calibration_keys(self.adb, calibration_values(cal))


def curiosity_summary(log):
    """The brain's curiosity decisions from a logcat dump, one line each, and
    whether at least one stop got as far as a look (a sighting or nothing)."""
    lines = []
    for line in log.splitlines():
        _, sep, msg = line.partition("ExploreBrain")
        if not sep:
            continue
        msg = msg.split(":", 1)[-1].strip()
        if msg.startswith(CURIOSITY_EVENTS):
            lines.append(msg)
    looked = any(m.startswith(("saw ", "nothing interesting")) for m in lines)
    return lines, looked


# ---- explore nav plan U8: logcat parsing for the nav step and roam-summary ----

# "09-25 16:01:02.345  1234  1250 I ExploreBrain: msg" (threadtime) or
# "09-25 16:01:02.345 I/ExploreBrain( 1234): msg" (time/brief).
_LOG_HEAD = re.compile(r"(?:^|\s)[VDIWEF][/ ]\s*([\w.$-]+)\s*(?:\(\s*\d+\))?\s*:\s?(.*)$")
_STAMP = re.compile(r"^(\d\d)-(\d\d)\s+(\d\d):(\d\d):(\d\d)\.(\d{3})")
_MS = re.compile(r"(\d+) ms")


def parse_logcat(text):
    """(seconds or None, tag, message) per logcat line; lines with no tag are skipped.
    The seconds count from the start of the month's day 0, enough to subtract two
    stamps from one capture."""
    out = []
    for line in text.splitlines():
        m = _LOG_HEAD.search(line)
        if not m:
            continue
        s = _STAMP.match(line.strip())
        t = None
        if s:
            mo, d, h, mi, se, ms = (int(g) for g in s.groups())
            t = (((mo * 31 + d) * 24 + h) * 60 + mi) * 60 + se + ms / 1000.0
        out.append((t, m.group(1), m.group(2).strip()))
    return out


def _first_ms(msg):
    m = _MS.search(msg)
    return int(m.group(1)) if m else None


def leftover_hooks(getprop_text):
    """Debug hooks left set, as {tag: value}, from `getprop` output
    ("[log.tag.MikoExploreCurious]: [DEBUG]"). INFO or empty counts as off."""
    out = {}
    for m in re.finditer(r"\[log\.tag\.([\w.$-]+)\]:\s*\[([^\]]*)\]", getprop_text):
        tag, value = m.group(1), m.group(2).strip()
        if tag.startswith(HOOK_PREFIXES) and value.upper() not in ("", "INFO"):
            out[tag] = value
    return out


def thermal_temps(text):
    """{zone type: degrees C} from "type milli-degrees" lines (one per thermal zone);
    readings a driver reports in whole degrees are kept as they are."""
    out = {}
    for line in text.splitlines():
        parts = line.split()
        if len(parts) < 2 or not parts[-1].lstrip("-").isdigit():
            continue
        v = int(parts[-1])
        name = " ".join(parts[:-1])
        if name in out:
            name = f"{name}#{len([k for k in out if k.split('#')[0] == name])}"
        out[name] = v / 1000.0 if abs(v) > 1000 else float(v)
    return out


def cpu_temp(temps):
    """The CPU's temperature from thermal_temps(): the hottest zone named for the
    CPU (MediaTek calls it mtktscpu), else the hottest zone of all."""
    cpu = [v for k, v in temps.items() if "cpu" in k.lower()]
    pool = cpu or list(temps.values())
    return max(pool) if pool else None


def battery_level(dumpsys_text):
    """The percentage from `dumpsys battery`, or None."""
    m = re.search(r"^\s*level:\s*(\d+)", dumpsys_text, re.M)
    return int(m.group(1)) if m else None


def roam_summary(text):
    """Counts and timings from a logcat capture of Explore roaming. Numbers only:
    no labels, names or lines are kept."""
    s = {"seconds": None, "steers": 0, "steers_doorway": 0, "reaims": 0, "reaims_skipped": 0,
         "cpl_hiccups": 0, "hazards": {}, "wedges": 0, "free_after_ms": [], "not_freed": 0,
         "cornered": 0, "retraces": 0, "backouts": 0, "circle_looks": 0, "wayout_asks": 0,
         "wayout_answers": 0, "wayout_robot": 0, "wayout_unusable": 0, "blocked_turns": 0,
         "coverage_cells": 0, "doorway_asks": 0, "doorway_seen": 0, "doorway_none": 0,
         "doorway_through": 0, "person_approaches": 0, "met": 0, "left_alone": 0,
         "look_ms": [], "openness_ms": [], "no_look": 0, "camera_errors": 0, "first_audio_ms": []}
    stamps = []
    wedged = False
    for t, tag, msg in parse_logcat(text):
        if t is not None:
            stamps.append(t)
        if tag == "SpeechEngine":
            if ": first audio " in msg:
                ms = _first_ms(msg.split("first audio", 1)[1])
                if ms is not None:
                    s["first_audio_ms"].append(ms)
            continue
        if tag == "ExploreCamera":
            if msg.startswith("look in "):
                s["look_ms"].append(_first_ms(msg))
            elif msg.startswith("openness in "):
                s["openness_ms"].append(_first_ms(msg))
            elif msg.startswith(CAMERA_ERRORS):
                s["camera_errors"] += 1
            continue
        if tag != "ExploreBrain":
            continue
        if msg.startswith("steer: "):
            s["steers"] += 1
            s["steers_doorway"] += "toward the doorway" in msg
        elif msg.startswith("re-aim: "):
            s["reaims"] += 1
        elif msg.startswith("re-aim ") and "skipped" in msg:
            s["reaims_skipped"] += 1
        elif msg.startswith("controller refused forward (CPL) on plain floor"):
            s["cpl_hiccups"] += 1
        elif msg.startswith(("hazard while ", "hazard at start: ")):
            kind = msg.rsplit(": ", 1)[-1].split("/")[0].strip()
            s["hazards"][kind] = s["hazards"].get(kind, 0) + 1
        elif msg.startswith("wedged: "):
            s["wedges"] += 1
            wedged = True
        elif msg.startswith("free after "):
            s["free_after_ms"].append(_first_ms(msg))
            wedged = False
        elif msg.startswith(("cornered: ", "still pinned after the rest")):
            s["cornered"] += 1
            if wedged:
                s["not_freed"] += 1
                wedged = False
        elif msg.startswith("retrace: facing"):
            s["retraces"] += 1
        elif msg.startswith("backing out straight along the way in"):
            s["backouts"] += 1
        elif msg.startswith("circle look "):
            s["circle_looks"] += 1
        elif msg.startswith("asking Claude the way out"):
            s["wayout_asks"] += 1
        elif msg.startswith("Claude's ") and ": the way out is at " in msg:
            s["wayout_answers"] += 1
        elif msg.startswith("way out on the robot"):
            s["wayout_robot"] += 1
        elif msg.startswith("Claude's way-out answer is unusable"):
            s["wayout_unusable"] += 1
        elif msg.startswith("measured turn blocked"):
            s["blocked_turns"] += 1
        elif msg.startswith("coverage: "):
            n = re.match(r"coverage: (\d+) cells", msg)
            if n:
                s["coverage_cells"] = max(s["coverage_cells"], int(n.group(1)))
        elif msg.startswith("asking Claude for an open doorway"):
            s["doorway_asks"] += 1
        elif msg.startswith("Claude sees an "):
            s["doorway_seen"] += 1
        elif msg.startswith("Claude sees no open doorway"):
            s["doorway_none"] += 1
        elif msg.startswith("through the doorway"):
            s["doorway_through"] += 1
        elif msg.startswith("a person while roaming: going over to meet them"):
            s["person_approaches"] += 1
        elif msg.startswith("met someone: left alone"):
            s["met"] += 1
        elif msg.startswith("recently-met check: ") and msg.endswith("just met, leaving them alone"):
            s["left_alone"] += 1
        elif msg.startswith("camera gave no look in time"):
            s["no_look"] += 1
    s["look_ms"] = [v for v in s["look_ms"] if v is not None]
    s["openness_ms"] = [v for v in s["openness_ms"] if v is not None]
    s["free_after_ms"] = [v for v in s["free_after_ms"] if v is not None]
    if len(stamps) > 1:
        s["seconds"] = max(stamps) - min(stamps)
    return s


def _secs(ms_list):
    return ", ".join(f"{v / 1000:.1f} s" for v in ms_list) or "-"


def _median_ms(values):
    return f"median {statistics.median(values):.0f} ms" if values else "none"


def format_roam_summary(s):
    """roam_summary() as the lines the owner reads."""
    span = f"{s['seconds'] / 60:.1f} min of log" if s["seconds"] is not None else "no timestamps"
    hazards = ", ".join(f"{k} {v}" for k, v in sorted(s["hazards"].items())) or "none"
    over = sum(1 for v in s["free_after_ms"] if v > WEDGE_TARGET_S * 1000)
    fa = s["first_audio_ms"]
    audio = (f"{len(fa)} lines, median {statistics.median(fa) / 1000:.2f} s, max {max(fa) / 1000:.2f} s"
             f" (idle {IDLE_FIRST_AUDIO_S:.1f} s)" if fa else "no lines")
    return [
        f"roam summary ({span})",
        f"  steers {s['steers']} (toward a doorway {s['steers_doorway']}); "
        f"re-aims {s['reaims']} (skipped, side blocked {s['reaims_skipped']})",
        f"  CPL hiccups {s['cpl_hiccups']} (retried once); hazards {sum(s['hazards'].values())}: {hazards}",
        f"  wedges {s['wedges']}: free after {_secs(s['free_after_ms'])}"
        f" ({over} over {WEDGE_TARGET_S:.0f} s); not freed {s['not_freed']}; cornered rests {s['cornered']}",
        f"  escape steps: retraces {s['retraces']}, back-outs {s['backouts']}, circle looks {s['circle_looks']},"
        f" way-out asks {s['wayout_asks']} (Claude {s['wayout_answers']}, robot's own {s['wayout_robot']},"
        f" unusable {s['wayout_unusable']})",
        f"  blocked turns {s['blocked_turns']}; coverage {s['coverage_cells']} cells",
        f"  doorway: asks {s['doorway_asks']}, seen {s['doorway_seen']}, none {s['doorway_none']},"
        f" through {s['doorway_through']}",
        f"  people: approaches {s['person_approaches']}, met {s['met']}, left alone {s['left_alone']}",
        f"  camera: looks {len(s['look_ms'])} ({_median_ms(s['look_ms'])}), openness {_median_ms(s['openness_ms'])},"
        f" no look in time {s['no_look']}, driver errors {s['camera_errors']}",
        f"  speech first audio: {audio}",
    ]


def wedge_trial(text):
    """One wedge trial's outcome from its log: (outcome, escape ms, ms since the first
    hazard). outcome is "free" (a "free after" line following "wedged"), "not freed"
    (a cornered rest after "wedged"), or None while neither has happened yet."""
    first_hazard = None
    wedged = False
    for t, tag, msg in parse_logcat(text):
        if tag != "ExploreBrain":
            continue
        if msg.startswith(("hazard while ", "hazard at start: ", "measured turn blocked")) and first_hazard is None:
            first_hazard = t
        if msg.startswith("wedged: "):
            wedged = True
        elif wedged and msg.startswith("free after "):
            since = round((t - first_hazard) * 1000) if t is not None and first_hazard is not None else None
            return "free", _first_ms(msg), since
        elif wedged and msg.startswith(("cornered: ", "still pinned after the rest")):
            return "not freed", None, None
    return None, None, None


def needed_passes(trials):
    """4 of 5 (Definition of Done), scaled to another number of tries."""
    return -(-WEDGE_PASSES_NEEDED * trials // 5)


def wedge_verdict(trials, target_s=WEDGE_TARGET_S):
    """(passes, ok) for trials of (outcome, escape ms): a pass is freed within target_s,
    or out on his own with no escape needed ("out", None)."""
    needed = needed_passes(len(trials))
    passes = sum(1 for outcome, ms in trials
                 if outcome == "out" or (outcome == "free" and ms is not None and ms <= target_s * 1000))
    return passes, passes >= needed


def ask(question):
    return input(f"   {question} [y/n] ").strip().lower().startswith("y")


def step_calibrate(robot, seconds):
    robot.adb("shell", "am", "start", "-n", ACTIVITY)
    time.sleep(3)
    input("\n>> Put the robot on the desk, facing open desk, and keep it still. Enter ")
    clear = robot.capture(seconds)
    input(">> Hold a hand about 5cm in front of the sensor. Enter ")
    hand = robot.capture(seconds)
    input(">> Hold the robot with its front just past the desk edge. Enter ")
    edge = robot.capture(seconds)
    cal, notes = suggest_calibration(clear, hand, edge)
    for label, rs in (("clear", clear), ("hand", hand), ("edge", edge)):
        print(f"   {label}: {len(rs)} readings, tof {min((r[0] for r in rs), default='-')}"
              f"..{max((r[0] for r in rs), default='-')}, ir2 {sorted({r[2] for r in rs})}")
    for n in notes:
        print(f"   note: {n}")
    print("   suggested:\n" + "".join(f"     {line}\n" for line in calibration_text(cal).splitlines()))
    if not calibration_complete(cal):
        print("   calibration incomplete: the mode would stay eyes-only; not writing it")
        return False
    if not ask("write this calibration and restart the mode?"):
        return False
    robot.push_calibration(cal)
    ok = "sensor calibration:" in robot.restart_and_log(["ExploreModeApp"])
    print("   the mode loaded the calibration" if ok else "   the mode did not report loading it")
    return ok


def hook_check(robot, tag, want, seconds=4):
    robot.hook(tag, True)
    try:
        log = robot.logcat_after(seconds, ["ExploreBrain", "ExploreDrive"])
    finally:
        robot.hook(tag, False)
    ok = want in log
    print(f"   looked for '{want}' in logcat: {'found' if ok else 'not found'}")
    return ok


def first_audio_verdict(ms_list, near_s=FIRST_AUDIO_NEAR_S):
    """(median seconds, ok): speech's first audio stays near the idle figure while he roams."""
    if not ms_list:
        return None, False
    med = statistics.median(ms_list) / 1000.0
    return med, med <= near_s


def wait(seconds):
    """Sleep, or stop early on Ctrl-C (the owner has seen enough)."""
    try:
        time.sleep(seconds)
    except KeyboardInterrupt:
        print("\n   (ended early)")


def nav_gyro(robot, opts, logs):
    print("   Gyro circle (nav U1): qa-explore-sensors.py --gyro-circle's guided capture.")
    if ask("run the gyro circle now? (n = skip it, already captured today)"):
        sensors_module().run_gyro_circle(robot.serial, 10.0)
    return ask("did the circle show one axis that flips sign with the turn direction, and a scale"
               " within a few percent of the previous capture?")


def nav_wedge(robot, opts, logs):
    print(f"   Wall-and-plant wedge, {opts.wedge_trials} tries: each escape is timed from the brain's"
          f" 'wedged' line to its 'free after' line, against {WEDGE_TARGET_S:.0f} s. Don't help him.")
    trials = []
    for i in range(opts.wedge_trials):
        input(f">> Try {i + 1}/{opts.wedge_trials}: put him facing into the nook while he roams, then Enter ")
        stream = robot.stream()
        start = time.monotonic()
        outcome = (None, None, None)
        try:
            while time.monotonic() - start < opts.wedge_timeout:
                time.sleep(1)
                outcome = wedge_trial("\n".join(stream.lines))
                if outcome[0]:
                    break
        except KeyboardInterrupt:
            print("\n   (try ended early)")
        finally:
            logs.append(stream.stop())
        kind, ms, since = outcome
        if kind is None:
            kind = "out" if ask(f"no escape logged in {opts.wedge_timeout:.0f} s: is he out on his own?") else "stuck"
        took = f"{ms / 1000:.1f} s" if ms is not None else "-"
        bumped = f", {since / 1000:.1f} s since the first bump" if since is not None else ""
        print(f"   try {i + 1}: {kind}, escape {took}{bumped}")
        trials.append((kind, ms))
    passes, ok = wedge_verdict(trials)
    print(f"   wedge: {passes} of {len(trials)} free within {WEDGE_TARGET_S:.0f} s"
          f" (need {needed_passes(len(trials))})")
    return ok


def nav_doorway(robot, opts, logs):
    input(f">> One open doorway and one closed door in view, him ~2 m back facing between them."
          f" Enter, then watch for {opts.doorway_seconds:.0f} s (Ctrl-C ends early) ")
    stream = robot.stream()
    try:
        wait(opts.doorway_seconds)
    finally:
        text = stream.stop()
    logs.append(text)
    s = roam_summary(text)
    print(f"   doorway: asks {s['doorway_asks']}, seen {s['doorway_seen']}, none {s['doorway_none']},"
          f" through {s['doorway_through']}; steers toward it {s['steers_doorway']} of {s['steers']}")
    return ask("did he head for the open doorway, not the closed door?")


def nav_person(robot, opts, logs):
    print(f"   Person, then a {opts.roam_minutes:.0f}-minute roam: step into his view so he comes to meet you,"
          " then stay in the room and let him roam. CPU temperature and battery are sampled at the"
          " start and end (the camera-while-roaming fallback triggers).")
    input(">> Enter to start ")
    t0, b0 = cpu_temp(thermal_temps(robot.thermals())), robot.battery()
    stream = robot.stream()
    try:
        wait(opts.roam_minutes * 60)
    finally:
        text = stream.stop()
    t1, b1 = cpu_temp(thermal_temps(robot.thermals())), robot.battery()
    logs.append(text)
    s = roam_summary(text)
    for line in format_roam_summary(s):
        print(f"   {line}")
    print(f"   fallback triggers: CPU {_num(t0, 'C')} -> {_num(t1, 'C')}, battery {_num(b0, '%')} -> {_num(b1, '%')},"
          f" camera driver errors {s['camera_errors']}, no look in time {s['no_look']}")
    if s["met"] > 1:
        print(f"   met {s['met']} times: more than once (only one person in the room?)")
    return s["met"] >= 1 and ask("did he come over and greet you once, then leave you alone for the rest of it?")


def _num(v, unit):
    return "?" if v is None else f"{v:.1f} {unit}" if isinstance(v, float) else f"{v} {unit}"


def nav_speech(robot, opts, logs):
    fa = [ms for text in logs for ms in roam_summary(text)["first_audio_ms"]]
    if not fa:
        print(f"   no spoken line logged yet: a curiosity stop at every pause for {opts.curious_seconds:.0f} s;"
              " put something new in front of him")
        robot.hook("MikoExploreCurious", True)
        stream = robot.stream()
        try:
            wait(opts.curious_seconds)
        finally:
            robot.hook("MikoExploreCurious", False)
            text = stream.stop()
        logs.append(text)
        fa = roam_summary(text)["first_audio_ms"]
    med, ok = first_audio_verdict(fa)
    if med is None:
        print("   no first-audio line from SpeechEngine")
        return False
    print(f"   first audio: {len(fa)} lines, median {med:.2f} s, max {max(fa) / 1000:.2f} s"
          f" (idle {IDLE_FIRST_AUDIO_S:.1f} s, near = {FIRST_AUDIO_NEAR_S:.1f} s or less)")
    return ok


NAV_RUNNERS = {"gyro": nav_gyro, "wedge": nav_wedge, "doorway": nav_doorway, "person": nav_person,
               "speech": nav_speech}


def step_nav(robot, opts, runners=None):
    """explore nav plan U8: reset leftover hooks, then each chosen sub-step in order,
    and every hook back off at the end whatever happened."""
    runners = runners or NAV_RUNNERS
    left = robot.reset_hooks()
    print("   debug hooks: " + (", ".join(f"{t} was {v}, now INFO" for t, v in left.items()) or "none left set"))
    robot.start_mode()
    logs, results = [], []
    try:
        for name in opts.nav_steps:
            print(f"\n-- nav: {name} --")
            results.append((name, runners[name](robot, opts, logs)))
    finally:
        still = robot.reset_hooks()
        if still:
            print(f"   reset hooks left on: {', '.join(still)}")
    print("\n-- nav report --")
    for name, ok in results:
        print(f"   {'PASS' if ok else 'FAIL'} {name}")
    return all(ok for _, ok in results)


def run_step(robot, name, seconds, curious_seconds=60.0, opts=None):
    print(f"\n== {name} ==")
    if name == "calibrate":
        return step_calibrate(robot, seconds)
    if name == "ae1":
        print("   AE1: let him wander toward the desk edge.")
        return ask("did he stop before the edge, 'whoa' with an eye flinch, back off briefly, look aside, turn, pause?")
    if name == "ae2":
        return ask("AE2: on a turn, do the eyes move toward the new direction before he starts turning?")
    if name == "ae3":
        print("   AE3: hiding every reading, then restarting the mode.")
        robot.hook("MikoExploreStale", True)
        try:
            robot.restart_mode()
            ok = '"eyes-only"' in robot.state()
            print(f"   /state shows eyes-only: {ok}")
            return ok and ask("did he stay put with the half-closed eyes?")
        finally:
            robot.hook("MikoExploreStale", False)
    if name == "ae4":
        print("   AE4: hiding readings while he wanders (wait for a hop).")
        input("   Enter once he is moving ")
        return hook_check(robot, "MikoExploreStale", "eyes only") and ask("did he stop and stay stopped?")
    if name == "ae5":
        print("   AE5: stopping lease renewals so the launcher's TTL takes the lease back.")
        return hook_check(robot, "MikoExploreNoRenew", "drive lease lost", seconds=6) \
            and ask("did he stop, with the eyes still running?")
    if name == "ae6":
        print("   AE6: the motor controller's own refusal, if his check misses an edge.")
        return ask("with the sensor covered at the edge, did he stay put (no push on)? (skip = n)")
    if name == "stoptimer":
        print("   Stop timer: freezing the brain.")
        return hook_check(robot, "MikoExploreFreeze", "stop timer fired")
    if name == "wander":
        return ask("Watch him for several minutes: no edge falls, no pushing into things, and slow and curious?")
    if name == "curiosity":
        print(f"   Curiosity: a stop at every pause for {curious_seconds:.0f} s. Put something new, a person or a"
              " pet in front of him; leave something he already inspected in view too.")
        robot.hook("MikoExploreCurious", True)
        try:
            log = robot.logcat_after(curious_seconds, ["ExploreBrain", "ExploreCamera"])
        finally:
            robot.hook("MikoExploreCurious", False)
        events, looked = curiosity_summary(log)
        for e in events:
            print(f"     {e}")
        if not looked:
            print("   no curiosity stop got as far as a look (camera or recognizer?)")
            return False
        return ask("did he face and roll up to new things and say their names, greet people/pets, "
                   "sigh at things seen before, and never go off an edge?")
    if name == "nav":
        return step_nav(robot, opts)
    raise ValueError(name)


def build_parser():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--serial", default=sensors_module().DEFAULT_SERIAL)
    ap.add_argument("--only", help="comma-separated steps: " + ",".join(ALL_STEPS))
    ap.add_argument("--seconds", type=float, default=5.0, help="length of each calibration capture")
    ap.add_argument("--curious-seconds", type=float, default=60.0, help="length of the curiosity step")
    ap.add_argument("--nav-steps", help="nav sub-steps to run, in order: " + ",".join(NAV_STEPS))
    ap.add_argument("--wedge-trials", type=int, default=5, help="wedge tries in the nav step")
    ap.add_argument("--wedge-timeout", type=float, default=90.0, help="seconds to wait for each wedge try")
    ap.add_argument("--doorway-seconds", type=float, default=120.0, help="length of the doorway watch")
    ap.add_argument("--roam-minutes", type=float, default=10.0, help="length of the person/roam watch")
    ap.add_argument("--log", help="roam-summary: a logcat capture to read ('-' = stdin; default: the robot's buffer)")
    return ap


def main(argv=None):
    args = build_parser().parse_args(argv)
    try:
        steps = parse_only(args.only)
        args.nav_steps = parse_nav_steps(args.nav_steps)
        if args.wedge_trials < 1:
            raise ValueError("--wedge-trials must be at least 1")
    except ValueError as e:
        sys.exit(f"!! {e}")
    if ":" in args.serial and args.log is None:
        subprocess.run(["adb", "connect", args.serial], capture_output=True, timeout=30)
    robot = Robot(args.serial)
    if steps == ["roam-summary"]:
        if args.log == "-":
            text = sys.stdin.read()
        elif args.log:
            text = Path(args.log).read_text(errors="replace")
        else:
            text = robot.adb("logcat", "-d", "-v", "threadtime", "-s", *NAV_TAGS)
        print("\n".join(format_roam_summary(roam_summary(text))))
        return
    results = [(s, run_step(robot, s, args.seconds, args.curious_seconds, args)) for s in steps]
    print("\n== report ==")
    for s, ok in results:
        print(f"   {'PASS' if ok else 'FAIL'} {s}")
    sys.exit(0 if all(ok for _, ok in results) else 1)


if __name__ == "__main__":
    main()
