#!/usr/bin/env python3
"""
verify-persistent-adb.py — report whether root adb is up on each transport after a normal boot.

Why this exists (Miko 3 context):
  The decisive proof for persistent adb is on the device, after a normal boot: a root shell
  over USB and over Wi-Fi, with the ServiceExam watchdog defused. `adb devices` alone is not
  enough — it needs authorization and says nothing about the neuter. This script checks the
  machine-readable signals and prints a per-transport verdict.

Requires: root ADB (the agent installed by scripts/install-persistent-adb.py).

What it checks:
  - preconditions: a device is attached, and the agent is registered;
  - boot state: ro.bootmode and sys.boot_completed (and says so when still in factory mode);
  - registration vs execution kept separate: `pm path` resolves AND the boot log was written
    during this boot — "registered but the payload failed" is not "never registered";
  - USB: the adb authorization state, `id -u` is 0, init.svc.adbd is running, sys.usb.config
    contains adb, /system/bin/reboot's first bytes are NOT ELF magic (the shadow is a script,
    so magic means the real binary is back), and the neuterd and watcher processes are alive;
  - Wi-Fi: the wlan0 address, then `adb connect <ip>:5555` and a root re-check over TCP;
  - AE2: the watcher's re-assertion, proved by perturbing sys.usb.config rather than by
    reading a value that cannot show the re-assertion ever happened.

Every device command names its transport (`adb -s <serial>`). With both transports up,
`adb devices` lists two entries and an untargeted `adb shell` fails with "more than one
device" — which reads as Wi-Fi DOWN while it is up, or attributes USB's answer to Wi-Fi.

Usage:
  python3 scripts/verify-persistent-adb.py               # human-readable verdict
  python3 scripts/verify-persistent-adb.py --json        # machine-readable
  python3 scripts/verify-persistent-adb.py --usb-serial <serial> --tcp-serial <ip:5555>

Dependencies: python3, adb.
"""
import argparse
import json
import re
import subprocess
import sys
import time
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
CAPTURE_DIR = REPO / "recon" / "captures"

BOOT_LOG = "/data/local/tmp/miko3-boot.log"
REBOOT = "/system/bin/reboot"
NEUTERD = "/data/local/tmp/neuterd"
WATCHER = "/data/local/tmp/miko3-usb-watch.sh"
AGENT_PKG = "com.miko3.bootagent"

# The documented unit profile (docs/device-intel.md); override with --usb-serial.
DEFAULT_USB_SERIAL = "MIKO3250XXM3Q0636CB"
TCP_PORT = 5555

# /system/bin/reboot's real binary is an ELF; the no-op shadow is a shell script.
ELF_MAGIC_HEX = "7f454c46"


class VerifyError(SystemExit):
    """A verify precondition failed; message is actionable."""


def adb(*args, serial=None, check=False):
    """Run adb, naming the transport explicitly (KTD10)."""
    cmd = ["adb"] + (["-s", serial] if serial else []) + list(args)
    r = subprocess.run(cmd, capture_output=True, text=True)
    if check and r.returncode != 0:
        raise VerifyError(f"!! adb failed: {' '.join(cmd)}\n{r.stderr.strip()}")
    return r


def sh(cmd, serial=None):
    return adb("shell", cmd, serial=serial).stdout.strip()


# --- pure helpers (unit-tested) ---------------------------------------------------------

def classify_boot(bootmode, boot_completed):
    """'factory', 'normal', or 'booting'."""
    if bootmode == "factory":
        return "factory"
    if boot_completed == "1":
        return "normal"
    return "booting"


def parse_adb_devices(raw):
    """{serial: state} from `adb devices`, e.g. {'abc': 'device', 'ip:5555': 'unauthorized'}."""
    out = {}
    for line in (raw or "").splitlines():
        line = line.strip()
        if not line or line.startswith("List of devices"):
            continue
        parts = line.split()
        if len(parts) >= 2:
            out[parts[0]] = parts[1]
    return out


def parse_reboot_magic(raw):
    """True when the first bytes are ELF magic, i.e. the real binary is back.

    A byte-size check cannot tell a shadow from a truncated real binary; the magic can.
    """
    return "".join((raw or "").split()).lower().startswith(ELF_MAGIC_HEX)


def parse_uptime_secs(raw):
    """First field of /proc/uptime as a float, or None."""
    m = re.search(r"[\d.]+", raw or "")
    return float(m.group(0)) if m else None


def parse_epoch(raw):
    """An integer epoch from command output, or None."""
    m = re.search(r"\d+", raw or "")
    return int(m.group(0)) if m else None


def log_is_fresh(log_mtime, now_epoch, uptime_secs):
    """True when the boot log was written during the current boot.

    A log from a previous boot satisfies "the log exists" while proving nothing about this
    boot, which is exactly the collapse U5 has to avoid.
    """
    if log_mtime is None or now_epoch is None or uptime_secs is None:
        return False
    return log_mtime >= (now_epoch - uptime_secs)


def assess_registration(pm_path, log_fresh):
    """(registered, executed) — kept separate so the two failures are distinguishable."""
    registered = bool((pm_path or "").strip())
    return registered, (registered and bool(log_fresh))


def assess_usb(uid, adbd, usb_config, reboot_is_real, neuterd_alive, watcher_alive, auth_state):
    """(up, evidence_lines) for the USB transport."""
    lines = []
    ok = True
    lines.append(f"adb authorization = {auth_state or 'absent'} (want device)")
    ok &= auth_state == "device"
    lines.append(f"id -u = {uid or '?'} (want 0)")
    ok &= uid == "0"
    lines.append(f"init.svc.adbd = {adbd or '?'} (want running)")
    ok &= adbd == "running"
    has_adb = "adb" in (usb_config or "")
    lines.append(f"sys.usb.config = {usb_config or '?'} (want *adb*)")
    ok &= has_adb
    lines.append(f"{REBOOT} is ELF = {reboot_is_real} (want False = shadowed)")
    ok &= not reboot_is_real
    lines.append(f"neuterd running = {neuterd_alive} (want True)")
    ok &= neuterd_alive
    lines.append(f"usb watcher running = {watcher_alive} (want True)")
    ok &= watcher_alive
    return bool(ok), lines


def assess_wifi(ip, tcp_uid, boot):
    """(up, evidence_lines) for the Wi-Fi transport.

    In factory mode the honest reason is that factory mode has no Wi-Fi at all, not that the
    device is off the network.
    """
    lines = []
    if not ip:
        if boot == "factory":
            return False, ["wlan0 address = none (factory mode has no Wi-Fi)"]
        return False, ["wlan0 address = none (device not on Wi-Fi)"]
    lines.append(f"wlan0 address = {ip}")
    ok = tcp_uid == "0"
    lines.append(f"root over tcp {ip}:{TCP_PORT} = {tcp_uid or 'unreachable'} (want 0)")
    return bool(ok), lines


# --- device reads -----------------------------------------------------------------------

def process_alive(fragment, serial):
    """True when a process matching `fragment` is running."""
    got = sh(f"pgrep -f '{fragment}' >/dev/null 2>&1 && echo yes || echo no", serial).strip()
    if got == "yes":
        return True
    # pgrep is absent on some builds; fall back to ps.
    count = sh(f"ps -A 2>/dev/null | grep -v grep | grep -c '{fragment}'", serial).strip()
    return count not in ("", "0")


def read_device_state(serial):
    return {
        "bootmode": sh("getprop ro.bootmode", serial),
        "boot_completed": sh("getprop sys.boot_completed", serial),
        "uid": sh("id -u", serial),
        "adbd": sh("getprop init.svc.adbd", serial),
        "usb_config": sh("getprop sys.usb.config", serial),
        "reboot_hex": sh(f"od -An -tx1 -N4 {REBOOT} 2>/dev/null", serial),
        "neuterd_alive": process_alive(NEUTERD, serial),
        "watcher_alive": process_alive(WATCHER, serial),
        "pm_path": sh(f"pm path {AGENT_PKG} 2>/dev/null", serial),
        "wlan0_ip": sh("ip -4 addr show wlan0 2>/dev/null | grep -o 'inet [0-9.]*' "
                       "| head -1 | cut -d' ' -f2", serial),
        "log_mtime": parse_epoch(sh(f"stat -c %Y {BOOT_LOG} 2>/dev/null", serial)),
        "now_epoch": parse_epoch(sh("date +%s", serial)),
        "uptime": parse_uptime_secs(sh("cat /proc/uptime", serial)),
        "boot_log_tail": sh(f"tail -3 {BOOT_LOG} 2>/dev/null", serial),
    }


def tcp_root(ip):
    """Connect over TCP and read `id -u` on the TCP transport; '' when unreachable."""
    if not ip:
        return ""
    tcp_serial = f"{ip}:{TCP_PORT}"
    adb("connect", tcp_serial)
    return sh("id -u", tcp_serial)


def check_reassert(serial, wait_secs=4):
    """AE2: perturb sys.usb.config and re-read it, proving the watcher restores adb.

    Reading the current value cannot show the re-assertion ever happened.
    """
    sh("setprop sys.usb.config mtp", serial)
    time.sleep(wait_secs)
    return sh("getprop sys.usb.config", serial)


def write_capture(result):
    """Write the verdict under recon/captures/ — the repo's device-touch convention."""
    CAPTURE_DIR.mkdir(parents=True, exist_ok=True)
    ts = time.strftime("%Y%m%dT%H%M%SZ", time.gmtime())
    path = CAPTURE_DIR / f"verify-persistent-adb-{ts}.json"
    path.write_text(json.dumps(result, indent=2) + "\n")
    return path


def require_device(usb_serial):
    """Refuse to report a verdict with no device attached.

    Without this, empty getprop output reads as 'still booting' and a missing unit is
    misreported as a boot in progress.
    """
    devices = parse_adb_devices(adb("devices").stdout)
    if not devices:
        raise VerifyError(
            "!! no device attached (adb devices is empty).\n"
            "   Connect USB, and confirm the agent is installed "
            "(scripts/install-persistent-adb.py) before verifying.")
    return devices


def verify(json_out=False, usb_serial=DEFAULT_USB_SERIAL, tcp_serial=None, reassert=False):
    devices = require_device(usb_serial)
    auth_state = devices.get(usb_serial)

    state = read_device_state(usb_serial)
    boot = classify_boot(state["bootmode"], state["boot_completed"])
    registered, executed = assess_registration(
        state["pm_path"], log_is_fresh(state["log_mtime"], state["now_epoch"], state["uptime"]))
    reboot_is_real = parse_reboot_magic(state["reboot_hex"])

    usb_up, usb_lines = assess_usb(
        state["uid"], state["adbd"], state["usb_config"], reboot_is_real,
        state["neuterd_alive"], state["watcher_alive"], auth_state)

    ip = state["wlan0_ip"]
    tcp_uid = tcp_root(ip) if boot == "normal" else ""
    wifi_up, wifi_lines = assess_wifi(ip, tcp_uid, boot)

    if reassert and boot == "normal":
        after = check_reassert(usb_serial)
        usb_lines.append(f"AE2 re-assert: sys.usb.config back to {after!r} (want *adb*)")
        usb_up = usb_up and "adb" in after

    result = {
        "boot": boot,
        "bootmode": state["bootmode"],
        "usb_serial": usb_serial,
        "authorization": auth_state or "absent",
        "registered": registered,
        "executed": executed,
        "neuter_shadowed": not reboot_is_real,
        "neuterd_alive": state["neuterd_alive"],
        "watcher_alive": state["watcher_alive"],
        "usb": {"up": usb_up, "evidence": usb_lines},
        "wifi": {"up": wifi_up, "evidence": wifi_lines},
        "boot_log_tail": state["boot_log_tail"],
    }

    if json_out:
        print(json.dumps(result, indent=2))
        return 0 if (usb_up and wifi_up and boot == "normal") else 1

    print("=== Miko 3 persistent adb @ %s ===" % time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()))
    if boot == "factory":
        print("boot state: FACTORY MODE — not a normal boot; the agent is not exercised.")
    elif boot == "booting":
        print("boot state: still booting (sys.boot_completed != 1); re-run shortly.")
    else:
        print("boot state: normal")
    print(f"agent:      registered={registered} executed={executed}"
          + ("" if registered else "  (never registered)"))
    print(f"neuter:     {'shadowed ✓' if result['neuter_shadowed'] else 'NOT shadowed ✗'}"
          f"  neuterd={state['neuterd_alive']} watcher={state['watcher_alive']}")
    print(f"USB:        {'UP ✓' if usb_up else 'DOWN ✗'}")
    for line in usb_lines:
        print(f"            {line}")
    print(f"Wi-Fi:      {'UP ✓' if wifi_up else 'DOWN ✗'}")
    for line in wifi_lines:
        print(f"            {line}")
    if state["boot_log_tail"]:
        print("boot log (tail):")
        for line in state["boot_log_tail"].splitlines():
            print(f"            {line}")
    else:
        print("boot log:   MISSING — the agent did not run this boot")
    try:
        print(f"capture:    {write_capture(result)}")
    except OSError as e:
        print(f"capture:    !! not written ({e})")
    return 0 if (usb_up and wifi_up and boot == "normal") else 1


def main():
    ap = argparse.ArgumentParser(description="Verify persistent root adb on the Miko 3.")
    ap.add_argument("--json", action="store_true", help="machine-readable output")
    ap.add_argument("--usb-serial", default=DEFAULT_USB_SERIAL,
                    help=f"USB transport serial (default: {DEFAULT_USB_SERIAL})")
    ap.add_argument("--tcp-serial", default=None, help="TCP transport, e.g. 10.0.0.5:5555")
    ap.add_argument("--reassert", action="store_true",
                    help="prove AE2 by perturbing sys.usb.config and re-reading it")
    args = ap.parse_args()
    return verify(json_out=args.json, usb_serial=args.usb_serial,
                  tcp_serial=args.tcp_serial, reassert=args.reassert)


if __name__ == "__main__":
    sys.exit(main())
