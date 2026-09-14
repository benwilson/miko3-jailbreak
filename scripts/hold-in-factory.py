#!/usr/bin/env python3
"""
hold-in-factory.py — TEMPORARY tool for the 2026-09-14 unattended session.

autonomous-recovery.py's normal loop immediately patches persist.sys.usb.config
and reboots to normal the instant it reaches factory mode — right for steady-
state repair, wrong when we want to stop and think between attempts. Factory
mode itself never auto-reboots or times out on its own, so simply not issuing
the "reboot to normal" step is enough to hold there indefinitely: real root,
no watchdog, no kiosk apps, nothing racing us.

This script watches the bus, forces the next preloader sighting into factory
mode (same FACTFACT handshake autonomous-recovery.py uses), confirms root,
opportunistically patches persist.sys.usb.config and re-stages neuterd (both
harmless, idempotent, no reboot involved), then STOPS and reports device is
parked in factory mode, ready to investigate.

Reuses autonomous-recovery.py's already-working detection/handshake code
in-process instead of duplicating it (that module guards its own entry point
with `if __name__ == "__main__"`, so importing it here does not start its
daemon loop).

Usage: tools/aoa-inject/.venv/bin/python scripts/hold-in-factory.py
Delete this file once the session's investigation is done — it's a stopgap,
not a permanent part of the toolchain.
"""
from __future__ import annotations

import importlib.util
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))


def _load(name: str):
    spec = importlib.util.spec_from_file_location(name, os.path.join(HERE, f"{name}.py"))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


AR = _load("autonomous-recovery")

if not AR.HAVE_PYUSB:
    AR.log(f"pyusb/libusb NOT available ({AR._PYUSB_IMPORT_ERROR}) — cannot force factory mode")
    sys.exit(1)

AR.log("hold-in-factory: watching for the next preloader sighting, will force "
       "factory mode and then STOP there (no patch-and-reboot)")

android_pid = AR.BROM.ANDROID_PID
attempt_n = 0
work_dir = os.path.join(AR.REPO, "firmware", "agent-backups", "hold-in-factory")
os.makedirs(work_dir, exist_ok=True)

while True:
    raw_pid = AR.get_raw_mtk_pid()
    online = AR.list_online_devices()

    if raw_pid is None and not online:
        time.sleep(AR.FAST_POLL)
        continue

    if raw_pid is not None and raw_pid != android_pid and not online:
        attempt_n += 1
        try:
            AR.BROM.T0 = time.time()
            dev = AR.BROM.find()
            if dev is not None:
                ok = AR.BROM.attempt(dev, attempt_n, [], AR.META_MODE)
                if ok:
                    AR.log("FACTFACT handshake accepted — expecting factory-mode boot")
        except Exception as e:  # noqa: BLE001
            AR.log(f"handshake attempt error: {e}")
        time.sleep(AR.FAST_POLL)
        continue

    if online:
        serial = online[0]
        bootmode = AR.get_bootmode(serial)
        if bootmode == "factory":
            if not AR.has_root(serial):
                AR.log("in factory mode but no root yet — waiting")
                time.sleep(1)
                continue
            AR.log(f"device {serial} in FACTORY mode, root confirmed")
            AR.ensure_usb_config(serial, work_dir)
            AR.ensure_staged_files(serial)
            AR.log("=== HOLDING IN FACTORY MODE — not patching further, not rebooting ===")
            AR.log(f"root shell: adb -s {serial} shell")
            AR.log("investigate/try things by hand now; nothing here will reboot the device")
            AR.log("when ready to go to normal boot, run auto-persistent-adb.py's race path "
                   "or relaunch autonomous-recovery.py for full auto-repair")
            sys.exit(0)

    time.sleep(AR.FAST_POLL)
