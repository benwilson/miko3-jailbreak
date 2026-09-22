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

Steps: calibrate, ae1..ae6, stoptimer, wander. AE3/AE4/AE5 and the stop timer are
staged with the mode's debug hooks (setprop log.tag.MikoExplore* DEBUG) and
checked in logcat; the rest need the owner's eyes and a y/n answer.
"""
import argparse
import importlib.util
import ssl
import subprocess
import sys
import tempfile
import time
import urllib.request
from pathlib import Path

PACKAGE = "com.miko3.mode.explore"
ACTIVITY = f"{PACKAGE}/.MainActivity"
CAL_NAME = "explore-calibration.properties"
TOF_FAULT = 16383
ALL_STEPS = ("calibrate", "ae1", "ae2", "ae3", "ae4", "ae5", "ae6", "stoptimer", "wander")

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
    """The --only list, validated; empty means every step in order."""
    if not value:
        return list(ALL_STEPS)
    steps = [s.strip().lower() for s in value.split(",") if s.strip()]
    unknown = [s for s in steps if s not in ALL_STEPS]
    if unknown:
        raise ValueError(f"unknown step(s): {', '.join(unknown)} (known: {', '.join(ALL_STEPS)})")
    return steps


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


def calibration_text(cal):
    """The app's calibration file (ExploreCalibration reads java.util.Properties)."""
    return "".join(f"{k}={str(cal[k]).lower() if isinstance(cal[k], bool) else cal[k]}\n"
                   for k in ("obstacleTofBelow", "edgeTofAbove", "edgeIr", "edgeIrAbove"))


class Robot:
    def __init__(self, serial):
        self.serial = serial

    def adb(self, *args, check=True):
        r = subprocess.run(["adb", "-s", self.serial] + list(args), capture_output=True, text=True, timeout=60)
        if check and r.returncode != 0:
            raise RuntimeError(f"adb {' '.join(args)}: {r.stderr.strip() or r.stdout.strip()}")
        return r.stdout

    def hook(self, tag, on):
        self.adb("shell", "setprop", f"log.tag.{tag}", "DEBUG" if on else "INFO")

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

    def push_calibration(self, text):
        files = f"/data/data/{PACKAGE}/files"
        with tempfile.NamedTemporaryFile("w", suffix=".properties", delete=False) as f:
            f.write(text)
            local = f.name
        try:
            self.adb("push", local, f"/data/local/tmp/{CAL_NAME}")
        finally:
            Path(local).unlink()
        owner = self.adb("shell", "stat", "-c", "%u:%g", f"/data/data/{PACKAGE}").strip()
        self.adb("shell", "mkdir", "-p", files)
        self.adb("shell", "cp", f"/data/local/tmp/{CAL_NAME}", f"{files}/{CAL_NAME}")
        self.adb("shell", "chown", "-R", owner, files)
        self.adb("shell", "chmod", "600", f"{files}/{CAL_NAME}")
        self.adb("shell", "restorecon", "-R", files, check=False)
        self.adb("shell", "rm", f"/data/local/tmp/{CAL_NAME}", check=False)


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
    robot.push_calibration(calibration_text(cal))
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


def run_step(robot, name, seconds):
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
    raise ValueError(name)


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--serial", default=sensors_module().DEFAULT_SERIAL)
    ap.add_argument("--only", help="comma-separated steps: " + ",".join(ALL_STEPS))
    ap.add_argument("--seconds", type=float, default=5.0, help="length of each calibration capture")
    args = ap.parse_args()
    try:
        steps = parse_only(args.only)
    except ValueError as e:
        sys.exit(f"!! {e}")
    if ":" in args.serial:
        subprocess.run(["adb", "connect", args.serial], capture_output=True, timeout=30)
    robot = Robot(args.serial)
    results = [(s, run_step(robot, s, args.seconds)) for s in steps]
    print("\n== report ==")
    for s, ok in results:
        print(f"   {'PASS' if ok else 'FAIL'} {s}")
    sys.exit(0 if all(ok for _, ok in results) else 1)


if __name__ == "__main__":
    main()
