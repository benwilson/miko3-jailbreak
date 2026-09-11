#!/usr/bin/env python3
"""
aoa-inject.py — inject a keystroke into an Android device via the AOAv2 HID
protocol, with fast polling to catch a brief input window (e.g. during boot).

Why this exists (Miko 3 context):
  The Miko 3's hidden data port makes the robot a USB *device* and the Mac the
  USB *host*. A Mac cannot be a raw USB HID keyboard, but AOAv2 lets the HOST
  register a HID device with the Android target and push HID reports over the
  control endpoint (ep0) — no OTG adapter, no adb, and NO re-enumeration into
  accessory mode. On the latest Miko firmware there is only a brief window
  during the boot "slot machine" eye animation where injected input reaches the
  UI before the kiosk locks it, so we poll hard and fire repeatedly.

What it does each poll tick (default every 50 ms):
  1. find the target (default VID 0x0e8d = MediaTek);
  2. AOA GET_PROTOCOL (req 51) — needs >= 2 for HID;
  3. REGISTER_HID (54) + SET_HID_REPORT_DESC (56) with a boot-keyboard descriptor;
  4. SEND_HID_EVENT (57): the chord (default Meta+N), then key-release.
  It re-registers automatically after the USB resets that happen during boot.

Dependencies: python3, pyusb, libusb (see tools/aoa-inject/README or
  scripts/aoa-inject.sh which runs this inside the right venv).

SAFETY: --probe only negotiates AOA and prints the protocol version; it sends no
  keystrokes. Use it first to learn whether this firmware supports AOA at all.
"""
import argparse
import sys
import time

import usb.core
import usb.util

# AOA (Android Open Accessory) control requests
AOA_GET_PROTOCOL      = 51
AOA_SEND_STRING       = 52
AOA_START             = 53
AOA_REGISTER_HID      = 54
AOA_UNREGISTER_HID    = 55
AOA_SET_HID_REPORT_DESC = 56
AOA_SEND_HID_EVENT    = 57

HID_ID = 1

# Minimal USB HID boot-keyboard report descriptor (8-byte input reports:
# [modifiers, reserved, key1..key6]).
KBD_REPORT_DESC = bytes([
    0x05, 0x01,  # Usage Page (Generic Desktop)
    0x09, 0x06,  # Usage (Keyboard)
    0xA1, 0x01,  # Collection (Application)
    0x05, 0x07,  #   Usage Page (Key Codes)
    0x19, 0xE0,  #   Usage Min (224, Left Ctrl)
    0x29, 0xE7,  #   Usage Max (231, Right GUI)
    0x15, 0x00,  #   Logical Min (0)
    0x25, 0x01,  #   Logical Max (1)
    0x75, 0x01,  #   Report Size (1)
    0x95, 0x08,  #   Report Count (8)
    0x81, 0x02,  #   Input (Data,Var,Abs)  -> 8 modifier bits
    0x95, 0x01,  #   Report Count (1)
    0x75, 0x08,  #   Report Size (8)
    0x81, 0x01,  #   Input (Const)         -> reserved byte
    0x95, 0x06,  #   Report Count (6)
    0x75, 0x08,  #   Report Size (8)
    0x15, 0x00,  #   Logical Min (0)
    0x25, 0x65,  #   Logical Max (101)
    0x05, 0x07,  #   Usage Page (Key Codes)
    0x19, 0x00,  #   Usage Min (0)
    0x29, 0x65,  #   Usage Max (101)
    0x81, 0x00,  #   Input (Data,Array)    -> 6 key slots
    0xC0,        # End Collection
])

# HID modifier bits
MOD = {"ctrl": 0x01, "shift": 0x02, "alt": 0x04, "meta": 0x08,
       "gui": 0x08, "win": 0x08, "cmd": 0x08}
# A few HID usage codes (US keyboard)
KEY = {**{chr(ord('a') + i): 0x04 + i for i in range(26)},
       "enter": 0x28, "esc": 0x29, "tab": 0x2B, "space": 0x2C,
       "home": 0x4A, "n": 0x11}


def parse_chord(s):
    """'meta+n' -> (modifier_byte, keycode)."""
    mods, key = 0, 0
    for part in s.lower().split("+"):
        part = part.strip()
        if part in MOD:
            mods |= MOD[part]
        elif part in KEY:
            key = KEY[part]
        else:
            sys.exit(f"unknown key token: {part!r} (known: {sorted(KEY)} / mods {sorted(MOD)})")
    return mods, key


def ctrl_out(dev, req, value=0, index=0, data=b""):
    # bmRequestType 0x40 = host->device, vendor, device
    return dev.ctrl_transfer(0x40, req, value, index, data, timeout=500)


def ctrl_in(dev, req, value=0, index=0, length=2):
    # bmRequestType 0xC0 = device->host, vendor, device
    return dev.ctrl_transfer(0xC0, req, value, index, length, timeout=500)


def aoa_protocol(dev):
    try:
        resp = ctrl_in(dev, AOA_GET_PROTOCOL, length=2)
        if len(resp) >= 2:
            return resp[0] | (resp[1] << 8)
    except usb.core.USBError:
        return None
    return None


def register_hid(dev):
    ctrl_out(dev, AOA_REGISTER_HID, value=HID_ID, index=len(KBD_REPORT_DESC))
    # descriptor may need chunking; it is small so one shot is fine
    ctrl_out(dev, AOA_SET_HID_REPORT_DESC, value=HID_ID, index=0, data=KBD_REPORT_DESC)


def send_report(dev, report8):
    ctrl_out(dev, AOA_SEND_HID_EVENT, value=HID_ID, index=0, data=report8)


def main():
    ap = argparse.ArgumentParser(description="AOA HID keystroke injector with boot-window polling")
    ap.add_argument("--vid", default="0x0e8d", help="target USB VID (default 0x0e8d MediaTek)")
    ap.add_argument("--pid", default=None, help="target USB PID (default: any PID under the VID)")
    ap.add_argument("--chord", default="meta+n", help="keys to send, e.g. 'meta+n' (default)")
    ap.add_argument("--interval-ms", type=int, default=50, help="poll/resend interval (default 50)")
    ap.add_argument("--duration", type=int, default=180, help="seconds to keep polling (default 180)")
    ap.add_argument("--probe", action="store_true", help="only negotiate AOA + print protocol; send NO keys")
    ap.add_argument("--verbose", action="store_true", help="log every tick")
    args = ap.parse_args()

    vid = int(args.vid, 0)
    pid = int(args.pid, 0) if args.pid else None
    mods, key = parse_chord(args.chord)
    press = bytes([mods, 0, key, 0, 0, 0, 0, 0])
    release = bytes(8)

    print(f"[aoa] target VID={hex(vid)} PID={hex(pid) if pid else 'any'} chord={args.chord!r} "
          f"({'PROBE only' if args.probe else 'INJECT'}) interval={args.interval_ms}ms duration={args.duration}s")
    print("[aoa] polling — power-cycle / boot the device now; Ctrl-C to stop")

    t_end = time.time() + args.duration
    last_state = None
    registered_for = None   # id() of the dev we last registered HID on
    sends = 0

    def log(state, extra=""):
        nonlocal last_state
        if state != last_state or args.verbose:
            print(f"[aoa {time.strftime('%H:%M:%S')}] {state} {extra}".rstrip())
            last_state = state

    while time.time() < t_end:
        try:
            kw = {"idVendor": vid}
            if pid is not None:
                kw["idProduct"] = pid
            dev = usb.core.find(**kw)
            if dev is None:
                log("waiting: device not on bus")
                time.sleep(args.interval_ms / 1000)
                continue

            proto = aoa_protocol(dev)
            if not proto:
                log("device present but AOA GET_PROTOCOL failed",
                    f"(PID={hex(dev.idProduct)}) — firmware may not support AOA, or ep0 busy")
                registered_for = None
                time.sleep(args.interval_ms / 1000)
                continue

            if proto < 2:
                log("AOA present but protocol < 2 (no HID support)", f"proto={proto}")
                time.sleep(args.interval_ms / 1000)
                continue

            log("AOA OK", f"proto={proto} PID={hex(dev.idProduct)}")
            if args.probe:
                # Success criterion for a probe: AOA v2+ answered. Keep confirming.
                time.sleep(args.interval_ms / 1000)
                continue

            # (Re)register HID if this is a freshly seen device handle.
            if registered_for != id(dev):
                try:
                    register_hid(dev)
                    registered_for = id(dev)
                    log("HID registered", f"proto={proto}")
                except usb.core.USBError as e:
                    log("HID register failed", str(e))
                    registered_for = None
                    time.sleep(args.interval_ms / 1000)
                    continue

            # Fire the chord, then release.
            try:
                send_report(dev, press)
                send_report(dev, release)
                sends += 1
                if sends % 20 == 1 or args.verbose:
                    log("SENT chord", f"x{sends}")
            except usb.core.USBError as e:
                log("send failed (will re-register)", str(e))
                registered_for = None

        except usb.core.USBError as e:
            log("USBError", str(e))
            registered_for = None
        except KeyboardInterrupt:
            print("\n[aoa] stopped by user")
            break
        time.sleep(args.interval_ms / 1000)

    print(f"[aoa] done. chords sent: {sends}")


if __name__ == "__main__":
    main()
