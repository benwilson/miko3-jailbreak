#!/usr/bin/env python3
"""
Fully autonomous nsinject race daemon.

Watches `adb devices` in a tight loop with no user input required. The moment
a device shows up in normal boot mode (ro.bootmode=normal, i.e. not the
FACTFACT-forced factory mode), it immediately fires a single remote-shell
command that polls for com.miko.launcher_app's first natural pid via `pidof`
(no ActivityManagerService dependency) and injects via nsinject as early as
possible, racing the ~30-33s MediaTek hardware watchdog reset.

Requires (already staged in earlier sessions):
  /data/local/tmp/nsinject
  /data/local/tmp/1_miko3.l
  /data/local/tmp/route_a_payload.sh
  /data/local/tmp/route_a_neuterd

Usage: python3 scripts/race-daemon.py [--once]
  --once   exit after the first race attempt (for testing), instead of looping forever
"""
import subprocess
import sys
import time
import datetime

RACE_SCRIPT = r'''
P=""
for i in $(seq 1 4000); do
  P=$(pidof com.miko.launcher_app 2>/dev/null)
  [ -n "$P" ] && break
done
echo "RACE pid=$P iter=$i uptime=$(cat /proc/uptime)"
if [ -n "$P" ]; then
  echo "$P" > /data/local/tmp/nsinject_pid
  /data/local/tmp/nsinject
  echo "RACE nsinject_rc=$? uptime=$(cat /proc/uptime)"
fi
'''

EVIDENCE_SCRIPT = r'''
echo "---hooklog---"
cat /data/local/tmp/miko3-hook.log 2>&1
echo "---rebootsize---"
wc -c < /system/bin/reboot 2>&1
'''


def log(msg):
    print(f"[{datetime.datetime.now().strftime('%H:%M:%S')}] {msg}", flush=True)


def run(args, timeout=10):
    try:
        r = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
        return r.returncode, r.stdout, r.stderr
    except subprocess.TimeoutExpired:
        return -1, "", "timeout"


def list_online_devices():
    rc, out, err = run(["adb", "devices"], timeout=5)
    devs = []
    for line in out.splitlines()[1:]:
        line = line.strip()
        if not line:
            continue
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            devs.append(parts[0])
    return devs


def get_bootmode(serial):
    rc, out, err = run(["adb", "-s", serial, "shell", "getprop", "ro.bootmode"], timeout=5)
    return out.strip()


def do_race(serial):
    log(f"NORMAL BOOT detected on {serial} -- firing race now")
    rc, out, err = run(["adb", "-s", serial, "shell", RACE_SCRIPT], timeout=20)
    for line in out.strip().splitlines():
        log(f"  race: {line}")
    if err.strip():
        log(f"  race stderr: {err.strip()}")

    rc2, out2, err2 = run(["adb", "-s", serial, "shell", EVIDENCE_SCRIPT], timeout=5)
    for line in out2.strip().splitlines():
        log(f"  evidence: {line}")

    reboot_size = None
    lines = out2.strip().splitlines()
    if lines:
        tail = lines[-1].strip()
        if tail.isdigit():
            reboot_size = int(tail)

    success = reboot_size is not None and reboot_size != 0 and reboot_size < 100 and reboot_size != 0
    # /system/bin/reboot on stock devices is typically a large toybox multicall
    # binary (tens of KB); the neuter payload replaces it with a tiny shim.
    # We flag anything suspiciously small (<4096 bytes) as a strong signal.
    if reboot_size is not None and reboot_size < 4096:
        log(f"!!! POSSIBLE SUCCESS: /system/bin/reboot is only {reboot_size} bytes (looks neutered) !!!")
        return True
    return False


def main():
    once = "--once" in sys.argv
    log("race daemon starting -- fully autonomous, watching device state")
    state = "unknown"
    raced_this_cycle = False

    while True:
        online = list_online_devices()
        if not online:
            if state != "absent":
                log("device absent (offline/rebooting)")
                state = "absent"
                raced_this_cycle = False
            time.sleep(0.4)
            continue

        serial = online[0]
        bootmode = get_bootmode(serial)

        if bootmode == "factory":
            if state != "factory":
                log(f"device {serial} in FACTORY mode -- no auto action (staging/patching handled separately)")
                state = "factory"
            time.sleep(1)
            continue

        # normal boot (bootmode == "normal" or empty before the prop is set yet)
        if state != "normal":
            log(f"device {serial} present, bootmode='{bootmode}'")
            state = "normal"

        if not raced_this_cycle:
            raced_this_cycle = True
            success = do_race(serial)
            if success:
                log("SUCCESS CONDITION MET -- stopping daemon")
                return
            if once:
                log("--once specified, exiting after first race")
                return
        time.sleep(0.3)


if __name__ == "__main__":
    main()
