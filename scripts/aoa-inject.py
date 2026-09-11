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

Modes:
  --probe            negotiate AOA, print protocol, send nothing
  --chord META+N     single chord, resent every tick (default)
  --once             one clean press+release, then exit
  --sequence ...     ordered burst (chords + waits + mouse swipe/wheel/click + text)
  --ladder           the built-in DEFAULT_LADDER burst
  --timeline         monitor-only, ms-stamped boot-window phase capture to recon/captures
  --mouse-longpress  one clean stationary long-press

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
import os
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

HID_ID = 1        # keyboard
HID_ID_MOUSE = 2  # mouse

# Minimal USB HID relative-mouse report descriptor.
# Report = [buttons, dx, dy, wheel] (4 bytes). Button bit0 = left.
MOUSE_REPORT_DESC = bytes([
    0x05, 0x01,  # Usage Page (Generic Desktop)
    0x09, 0x02,  # Usage (Mouse)
    0xA1, 0x01,  # Collection (Application)
    0x09, 0x01,  #   Usage (Pointer)
    0xA1, 0x00,  #   Collection (Physical)
    0x05, 0x09,  #     Usage Page (Buttons)
    0x19, 0x01,  #     Usage Min (1)
    0x29, 0x03,  #     Usage Max (3)
    0x15, 0x00,  #     Logical Min (0)
    0x25, 0x01,  #     Logical Max (1)
    0x95, 0x03,  #     Report Count (3)
    0x75, 0x01,  #     Report Size (1)
    0x81, 0x02,  #     Input (Data,Var,Abs) -> 3 button bits
    0x95, 0x01,  #     Report Count (1)
    0x75, 0x05,  #     Report Size (5)
    0x81, 0x01,  #     Input (Const)        -> padding
    0x05, 0x01,  #     Usage Page (Generic Desktop)
    0x09, 0x30,  #     Usage (X)
    0x09, 0x31,  #     Usage (Y)
    0x09, 0x38,  #     Usage (Wheel)
    0x15, 0x81,  #     Logical Min (-127)
    0x25, 0x7F,  #     Logical Max (127)
    0x75, 0x08,  #     Report Size (8)
    0x95, 0x03,  #     Report Count (3)
    0x81, 0x06,  #     Input (Data,Var,Rel) -> dx, dy, wheel
    0xC0,        #   End Collection
    0xC0,        # End Collection
])

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

# Minimal USB HID touchscreen (digitizer) report descriptor with ABSOLUTE
# coordinates: report = [tip-switch, x, y] on a 0..255 grid that Android scales to
# the panel. Absolute positioning is what makes a blind tap land on a specific
# glyph of a 320-ish px display; a relative mouse cannot be homed reliably.
#   report = [tip-switch, x, y] on a 0..255 grid that Android scales to the panel.
TOUCH_REPORT_DESC = bytes([
    0x05, 0x0D,  # Usage Page (Digitizer)
    0x09, 0x04,  # Usage (Touch Screen)
    0xA1, 0x01,  # Collection (Application)
    0x05, 0x0D,  #   Usage Page (Digitizer)
    0x09, 0x42,  #   Usage (Tip Switch)
    0x15, 0x00,  #   Logical Min (0)
    0x25, 0x01,  #   Logical Max (1)
    0x75, 0x01,  #   Report Size (1)
    0x95, 0x01,  #   Report Count (1)
    0x81, 0x02,  #   Input (Data,Var,Abs)   -> tip switch bit
    0x95, 0x07,  #   Report Count (7)
    0x75, 0x01,  #   Report Size (1)
    0x81, 0x03,  #   Input (Const)          -> pad to a whole byte
    0x05, 0x01,  #   Usage Page (Generic Desktop)
    0x09, 0x30,  #   Usage (X)
    0x09, 0x31,  #   Usage (Y)
    0x15, 0x00,  #   Logical Min (0)
    0x25, 0xFF,  #   Logical Max (255)
    0x75, 0x08,  #   Report Size (8)
    0x95, 0x02,  #   Report Count (2)
    0x81, 0x02,  #   Input (Data,Var,Abs)   -> absolute X, Y
    0xC0,        # End Collection
])

# HID modifier bits
MOD = {"ctrl": 0x01, "shift": 0x02, "alt": 0x04, "meta": 0x08,
       "gui": 0x08, "win": 0x08, "cmd": 0x08}
HID_ID_TOUCH = HID_ID_MOUSE  # AOA v2 gives us exactly two slots: keyboard + one pointer/touch
# HID boot-keyboard usage codes (Usage Page 0x07, US layout). Letters, digits,
# and the nav keys needed to walk the Settings app blind.
KEY = {**{chr(ord('a') + i): 0x04 + i for i in range(26)},
       **{str(i): 0x1E + i - 1 for i in range(1, 10)}, "0": 0x27,
       "enter": 0x28, "esc": 0x29, "backspace": 0x2A, "tab": 0x2B, "space": 0x2C,
       "-": 0x2D, "=": 0x2E, "[": 0x2F, "]": 0x30, ";": 0x33,
       "'": 0x34, ",": 0x36, ".": 0x37, "/": 0x38,
       "ins": 0x49, "home": 0x4A, "pgup": 0x4B, "del": 0x4C, "end": 0x4D,
       "pgdn": 0x4E, "right": 0x4F, "left": 0x50, "down": 0x51, "up": 0x52,
       "f1": 0x3A, "f2": 0x3B, "f3": 0x3C, "f4": 0x3D,
       "n": 0x11}
# Characters that need the Shift bit with the same usage code.
SHIFTED = {chr(ord('A') + i): 0x04 + i for i in range(26)}
SHIFTED.update({":": 0x33, "?": 0x38, "_": 0x2D, "+": 0x2E})

# Default ladder: what to fire inside the boot window to get from a cold boot to
# Developer options -> USB debugging, on a shade that shows NO settings gear.
# Each step is one HID transaction; the gaps are short on purpose.
DEFAULT_LADDER = ["meta+n", "wait:350", "move:right", "move:down",
                  "nudge:0,-40", "wait:150", "click"]


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


def register_hid_mouse(dev):
    ctrl_out(dev, AOA_REGISTER_HID, value=HID_ID_MOUSE, index=len(MOUSE_REPORT_DESC))
    ctrl_out(dev, AOA_SET_HID_REPORT_DESC, value=HID_ID_MOUSE, index=0, data=MOUSE_REPORT_DESC)


def send_mouse(dev, buttons=0, dx=0, dy=0, wheel=0):
    # signed -> unsigned byte
    b = lambda v: v & 0xFF
    ctrl_out(dev, AOA_SEND_HID_EVENT, value=HID_ID_MOUSE, index=0,
             data=bytes([buttons & 0x07, b(dx), b(dy), b(wheel)]))


def encode_char(ch):
    """'A' -> (shift, usage). Returns None for characters we cannot encode."""
    if ch == " ":
        return 0, KEY["space"]
    low = ch.lower()
    if low in SHIFTED and ch.isupper():
        return MOD["shift"], SHIFTED[low]
    if low in KEY:
        return 0, KEY[low]
    return None


def type_text(dev, text):
    """Type a literal string (Settings search box, etc.)."""
    for ch in text:
        enc = encode_char(ch)
        if enc is None:
            continue
        mods, key = enc
        send_report(dev, bytes([mods, 0, key, 0, 0, 0, 0, 0]))
        time.sleep(0.02)
        send_report(dev, bytes(8))
        time.sleep(0.02)


def do_wheel(dev, clicks):
    for _ in range(abs(clicks) or 1):
        send_mouse(dev, wheel=1 if clicks >= 0 else -1)
        time.sleep(0.03)
        send_mouse(dev, wheel=0)


def do_click(dev):
    """Left click wherever the pointer currently is."""
    send_mouse(dev, buttons=0x01)
    time.sleep(0.04)
    send_mouse(dev, buttons=0x00)


def do_move(dev, direction, reps=8):
    """Walk the pointer to an edge. Android clamps the pointer to the screen, so a
    few max-size relative moves home it onto that edge deterministically — which is
    what makes a blind corner-tap possible on a tiny display."""
    for _ in range(reps):
        if direction == "right":
            send_mouse(dev, dx=127)
        elif direction == "left":
            send_mouse(dev, dx=-127)
        elif direction == "up":
            send_mouse(dev, dy=-127)
        else:
            send_mouse(dev, dy=127)
        time.sleep(0.02)
    send_mouse(dev)


def do_swipe(dev, direction):
    """Drag with the left button held — this is what EXPANDS a collapsed shade on a
    small panel. Distance matters: Android only counts a drag past ~16 px, and a
    single 127 px hop can be read as a fling, so the travel is spread over several
    smaller held steps."""
    step = 50 if direction == "down" else -50
    send_mouse(dev, buttons=0x01)
    time.sleep(0.03)
    for _ in range(4):
        send_mouse(dev, buttons=0x01, dy=step)
        time.sleep(0.03)
    send_mouse(dev, buttons=0x00)


def parse_step(step):
    """'wheel:down' / 'click' / 'meta+n' / 'text:developer' -> (kind, payload)."""
    if ":" in step:
        kind, _, val = step.partition(":")
        return kind, val
    if step in ("click", "re-reg"):
        return step, ""
    if step.startswith("hold:"):
        return "hold", step[len("hold:") :]
    if step.startswith("move:"):
        return "move", step[len("move:"):]
    if step.startswith("nudge:"):
        return "nudge", step[len("nudge:"):]
    return "chord", step


def run_step(dev, step, mouse_ok=True):
    kind, val = parse_step(step)
    if kind == "wait":
        time.sleep(int(val) / 1000)
    elif kind == "wheel":
        clicks = {"down": -3, "up": 3}.get(val, 3)
        if mouse_ok:
            do_wheel(dev, clicks)
    elif kind == "swipe":
        if mouse_ok:
            do_swipe(dev, val)
    elif kind == "hold":
        if mouse_ok:
            mouse_longpress(dev, float(val) if val else 1.5)
    elif kind == "click":
        if mouse_ok:
            do_click(dev)
    elif kind == "move":
        direction, _, reps = val.partition(":")
        do_move(dev, direction, int(reps) if reps else 8)
    elif kind == "nudge":
        xs, _, ys = val.partition(",")
        if not ys:
            raise SystemExit(f"nudge needs dx,dy — got {val!r}")
        nudge(dev, int(xs), int(ys))
    elif kind == "tap":
        xs, _, ys = val.partition(",")
        if not ys:
            raise SystemExit(f"tap needs x,y — got {val!r}")
        tap(dev, int(xs), int(ys))
    elif kind == "text":
        type_text(dev, val)
    elif kind == "re-reg":
        return register_both(dev)
    elif kind == "chord":
        mods, key = parse_chord(val)
        send_report(dev, bytes([mods, 0, key, 0, 0, 0, 0, 0]))
        time.sleep(0.03)
        send_report(dev, bytes(8))
    else:
        raise SystemExit(f"unknown step kind: {kind!r} (in step {step!r})")
    return mouse_ok


def find_target(vid, pid):
    """Look up the unit. The firmware uses VID 0x0e8d normally and 0x18d1 once AOA
    accessory mode is active, so fall through to the other VID before giving up."""
    def _one(v):
        kw = {"idVendor": v}
        if pid is not None:
            kw["idProduct"] = pid
        return usb.core.find(**kw)

    dev = _one(vid)
    if dev is None and vid == 0x0E8D:
        dev = _one(0x18D1)
    return dev


def aoa_ready(dev):
    """Protocol version if this dev is AOA v2+ capable, else None."""
    proto = aoa_protocol(dev)
    if not proto or proto < 2:
        return None
    return proto


def confirm_kbd(dev):
    """Touch the keyboard HID with an empty report; raises USBError when the
    firmware stopped routing reports for it."""
    send_report(dev, bytes(8))
    time.sleep(0.02)


def register_both(dev, second="mouse"):
    """Register the pointer/touch HID FIRST and the keyboard LAST; return whether
    the second device is usable.

    AOA v2 allows exactly TWO HID devices (keyboard=1, pointer/touch=2), but plenty
    of firmware keeps only the LAST registered one alive. So register the pointer
    first, confirm the keyboard still answers, and fall back to keyboard-only.
    Registering once per burst also matters: re-registering mid-burst resets the
    pointer and drops focus.
    """
    def _second(d):
        register_touch(d) if second == "touch" else register_hid_mouse(d)

    try:
        _second(dev)
        register_hid(dev)
        confirm_kbd(dev)
        return True
    except usb.core.USBError:
        pass
    try:
        ctrl_out(dev, AOA_UNREGISTER_HID, value=HID_ID_TOUCH)
    except usb.core.USBError:
        pass
    try:
        register_hid(dev)
        confirm_kbd(dev)
    except usb.core.USBError:
        return False
    return False


def register_touch(dev):
    ctrl_out(dev, AOA_REGISTER_HID, value=HID_ID_TOUCH, index=len(TOUCH_REPORT_DESC))
    ctrl_out(dev, AOA_SET_HID_REPORT_DESC, value=HID_ID_TOUCH, index=0, data=TOUCH_REPORT_DESC)


def send_touch(dev, x=255, y=255, tip=1):
    """Absolute tap on a 0..255 grid — 255,255 is the bottom-right corner."""
    ctrl_out(dev, AOA_SEND_HID_EVENT, value=HID_ID_TOUCH, index=0,
             data=bytes([tip & 0x01, x & 0xFF, y & 0xFF]))


def nudge(dev, dx, dy):
    """One small relative move. move:* clamps to an edge; nudge:* is for insetting a
    known number of pixels from that edge, so a click can land inside the shade
    rather than on the scrim that dismisses it."""
    send_mouse(dev, dx=dx, dy=dy)
    time.sleep(0.02)
    send_mouse(dev)


def wiggle(dev, seconds=2.0):
    """Move a relative pointer in a small loop. Android draws a cursor for a pointer
    device, so this is the quickest way to tell whether pointer input reaches this
    unit at all — and therefore whether tap coordinates are worth chasing."""
    register_hid_mouse(dev)
    time.sleep(0.05)
    end = time.time() + seconds
    while time.time() < end:
        for dx, dy in ((40, 0), (0, 30), (-40, 0), (0, -30)):
            send_mouse(dev, dx=dx, dy=dy)
            time.sleep(0.04)
    send_mouse(dev)


def tap(dev, x, y, hold_s=0.05):
    send_touch(dev, x, y, 1)
    time.sleep(hold_s)
    send_touch(dev, x, y, 0)


def aoa_strings(dev, model):
    """Full AOA v2 string handshake. AOSP's UsbDeviceManager special-cases the
    Model string: "AdsDebug" starts adbd straight away, "Ace" starts it with the
    RSA confirmation dialog. Manufacturer must be exactly "Android" for the
    special-case to apply, and START is what commits the strings."""
    strings = ["Android", model, "miko3 adb bridge", "1.0", "about:blank", "0"]
    for i, s in enumerate(strings):
        ctrl_out(dev, AOA_SEND_STRING, value=0, index=i,
                 data=(s + "\0").encode("utf-8"))
    ctrl_out(dev, AOA_START)


def ioreg_state():
    """Present-at-USB state from ioreg. More reliable than pyusb after accessory
    mode: once the config is accessory-only macOS may leave it unmatched, and then
    pyusb stops listing it while ioreg still shows it."""
    out = shell(["sh", "-c",
                 "ioreg -p IOUSB -w0 -l | awk 'BEGIN{RS=\"\\\\+-o \"} "
                 "/\"idVendor\" = 3725|\"idVendor\" = 6353/ { "
                 "match($0, /\"idProduct\" = [0-9]+/); pid = substr($0, RSTART, RLENGTH); "
                 "gsub(/.*= /, \"\", pid); printf \"%s\", pid }'"])
    try:
        return f"PID=0x{int(out):04x}" if out else "gone"
    except ValueError:
        return out or "gone"


def shell(args, timeout=6):
    import subprocess
    try:
        return subprocess.run(args, capture_output=True, text=True, timeout=timeout).stdout.strip()
    except Exception as e:  # noqa: BLE001
        return f"<{e}>"


def mouse_longpress(dev, hold_s):
    """Clean long-press at the CURRENT cursor position: button-down, hold, up.
    Sends zero movement so Android reads it as a stationary long-press."""
    register_hid_mouse(dev)
    time.sleep(0.05)
    send_mouse(dev, buttons=0x01, dx=0, dy=0)   # left button DOWN
    time.sleep(hold_s)
    send_mouse(dev, buttons=0x00, dx=0, dy=0)   # release


def main():
    ap = argparse.ArgumentParser(description="AOA HID keystroke injector with boot-window polling")
    ap.add_argument("--vid", default="0x0e8d", help="target USB VID (default 0x0e8d MediaTek)")
    ap.add_argument("--pid", default=None, help="target USB PID (default: any PID under the VID)")
    ap.add_argument("--chord", default="meta+n", help="keys to send, e.g. 'meta+n' (default)")
    ap.add_argument("--interval-ms", type=int, default=50, help="poll/resend interval (default 50)")
    ap.add_argument("--duration", type=int, default=180, help="seconds to keep polling (default 180)")
    ap.add_argument("--probe", action="store_true", help="only negotiate AOA + print protocol; send NO keys")
    ap.add_argument("--reset", action="store_true",
                    help="force a fresh enumeration from the host side (this unit restarts "
                         "Android on a port reset, which is exactly what is needed to pick up "
                         "a just-enabled adb interface without walking to the cable)")
    ap.add_argument("--ifaces", action="store_true",
                    help="print each interface's class/subclass/protocol and exit — this is the "
                         "machine-readable read-out of whether USB debugging is on "
                         "(adb shows up as FF/42/01)")
    ap.add_argument("--once", action="store_true", help="send the chord exactly ONCE (clean press+release), then exit")
    ap.add_argument("--mouse-longpress", type=float, default=None, metavar="SEC",
                    help="do ONE clean left-button long-press (this many seconds) at the current cursor position, then exit")
    ap.add_argument("--verbose", action="store_true", help="log every tick")
    ap.add_argument("--sequence", nargs="+", metavar="STEP", default=None,
                    help="fire an ordered burst of steps in one pass, e.g. "
                         "--sequence meta+n wait:250 swipe:down tab enter "
                         "(kinds: chord | wait:MS | wheel:down|up | swipe:down|up | "
                         "click | text:str | re-reg)")
    ap.add_argument("--repeat", type=int, default=1,
                    help="how many times to run the --sequence burst (default 1)")
    ap.add_argument("--cycle-ms", type=int, default=1500,
                    help="gap between --sequence bursts (default 1500)")
    ap.add_argument("--ads", default=None, metavar="MODEL",
                    help="run the full AOA string handshake with MODEL as the Model string "
                         "(try 'AdsDebug' or 'Ace') — AOSP starts adbd for these",
                    )
    ap.add_argument("--wiggle", action="store_true",
                    help="move a relative pointer in a loop so you can see whether pointer "
                         "input reaches the display at all; sends no keys")
    ap.add_argument("--step", default=None, metavar="STEP",
                    help="fire exactly ONE step, then exit — use this to walk the ladder "
                         "one press at a time and watch the display between presses")
    ap.add_argument("--ladder", action="store_true",
                    help="run the built-in DEFAULT_LADDER burst")
    ap.add_argument("--list-chords", action="store_true",
                    help="print the chord/step ladder with what each is for; sends nothing")
    ap.add_argument("--timeline", action="store_true",
                    help="monitor-only: log ms-stamped boot-window state transitions, send no keys")
    ap.add_argument("--out-dir", default="recon/captures",
                    help="where --timeline writes its capture (default recon/captures)")
    args = ap.parse_args()

    if args.list_chords:
        print("Chord / step ladder (fire inside the boot window, most reliable first):")
        for step in DEFAULT_LADDER:
            print(f"  {step}")
        print("\nOther chords worth a slot, by what they are supposed to reach:")
        for line in [
            "meta+n        notification shade (confirmed on this unit)",
            "meta+shift+n  second shade pass — some builds only reveal the gear here",
            "swipe:down    expand a collapsed shade; the gear is often below the fold",
            "move:right / move:down   home a RELATIVE pointer to an edge, then click",
            "nudge:dx,dy   one small relative move, to inset from a homed edge",
            "tap:x,y       absolute touch tap on a 0..255 grid (255,255 = bottom-right);",
            "              does not depend on where a relative pointer was parked",
            "tab / enter   walk QS focus to the gear, then open Settings",
            "down / enter  same idea with arrow focus instead of Tab",
            "esc           dismiss a blocking dialog; also collapses the shade",
            "home          fall back to the launcher if a dialog ate the input",
            "text:set        only lands if some view already holds an edit-field focus — on this",
            "                unit's home page typed letters are dropped, so never rely on it",
        ]:
            print("  " + line)
        return

    vid = int(args.vid, 0)
    pid = int(args.pid, 0) if args.pid else None
    mods, key = parse_chord(args.chord)
    press = bytes([mods, 0, key, 0, 0, 0, 0, 0])
    release = bytes(8)

    # AOA string handshake: the non-keystroke route to a live adbd.
    # Descriptor read-out: the adb interface is FF/42/01, the plain kiosk gadget is
    # FF/FF/00. Cheaper and more reliable than `adb devices`, because it does not
    # depend on an RSA authorization already having happened.
    if args.reset:
        dev = find_target(vid, pid)
        if dev is None:
            print("no device on bus")
            return
        try:
            dev.reset()
            print(f"port reset sent ({ioreg_state()})")
        except usb.core.USBError as e:
            print(f"reset failed: {e}")
        return

    if args.ifaces:
        dev = find_target(vid, pid)
        if dev is None:
            print("no device on bus")
            return
        try:
            cfg = dev.get_active_configuration()
        except usb.core.USBError:
            try:
                dev.set_configuration()
                cfg = dev.get_active_configuration()
            except usb.core.USBError as e:
                print(f"could not read configuration: {e}")
                return
        print(f"PID={hex(dev.idProduct)} configs={dev.bNumConfigurations}")
        for intf in cfg:
            print(f"  iface {intf.bInterfaceNumber}: "
                  f"{intf.bInterfaceClass:02x}/{intf.bInterfaceSubClass:02x}/{intf.bInterfaceProtocol:02x}")
        return

    if args.ads:
        print(f"[aoa] AOA strings handshake with Model={args.ads!r}")
        deadline = time.time() + 20
        while time.time() < deadline:
            dev = find_target(vid, pid)
            if dev is None:
                print("[aoa] waiting for device...")
                time.sleep(args.interval_ms / 1000)
                continue
            proto = aoa_ready(dev)
            if proto is None:
                print(f"[aoa] no AOA yet (PID={hex(dev.idProduct)})")
                time.sleep(args.interval_ms / 1000)
                continue
            try:
                aoa_strings(dev, args.ads)
                print(f"[aoa] strings + START sent (proto={proto})")
                # Give the framework a moment to act on the strings on its own before
                # forcing anything — an immediate reset can cut the ADB-enable path short.
                for attempt in range(1, 6):
                    time.sleep(2 * attempt)
                    out = shell(["adb", "devices"])
                    line = " | ".join(x for x in out.splitlines()
                                       if x and "List of" not in x) or "empty"
                    print(f"[aoa] adb devices: {line}; on bus: {ioreg_state()}")
                    if line != "empty":
                        print("[aoa] adb is up")
                        return
                try:
                    dev.reset()
                    print("[aoa] host-side USB reset sent so the new config takes effect")
                except usb.core.USBError as e:
                    print(f"[aoa] dev.reset() unavailable: {e}")
            except usb.core.USBError as e:
                print(f"[aoa] USBError during handshake: {e}")
                time.sleep(0.3)
                continue
            for _ in range(10):
                time.sleep(2)
                out = shell(["adb", "devices"])
                line = " | ".join(x for x in out.splitlines() if x and "List of" not in x) or "empty"
                print(f"[aoa] adb devices: {line}; on bus: {ioreg_state()}")
                if line != "empty":
                    print("[aoa] adb is up")
                    return
            print("[aoa] adb still not listed after 20s — the accessory config is often left "
                  "unclaimed by the host; replug the micro USB and rerun --ads, or check "
                  "scripts/miko-detect.sh for the PID it settled on")
            return
        print("[aoa] gave up waiting for device")
        sys.exit(1)

    if args.wiggle:
        print("[aoa] wiggle — watching for 2s of pointer movement once AOA is up")
        deadline = time.time() + 15
        while time.time() < deadline:
            dev = find_target(vid, pid)
            if dev is None:
                print("[aoa] waiting for device...")
                time.sleep(args.interval_ms / 1000)
                continue
            if aoa_ready(dev) is None:
                time.sleep(args.interval_ms / 1000)
                continue
            try:
                wiggle(dev)
                print("[aoa] wiggle done — did a cursor dot travel in a rectangle?")
                return
            except usb.core.USBError as e:
                print(f"[aoa] USBError during wiggle: {e}")
                time.sleep(0.2)
        print("[aoa] gave up waiting for device")
        sys.exit(1)

    # Burst mode: the whole path in one pass, so a short boot window is enough.
    if args.sequence or args.ladder or args.step:
        steps = args.sequence or ([args.step] if args.step else DEFAULT_LADDER)
        print(f"[aoa] burst of {len(steps)} steps x{args.repeat}, "
              f"interval={args.interval_ms}ms, cycle gap={args.cycle_ms}ms")
        print("[aoa] polling — power-cycle / boot the device now; Ctrl-C to stop")
        deadline = time.time() + args.duration
        registered_for = None
        mouse_ok = False
        cycles = 0
        while time.time() < deadline and cycles < args.repeat:
            dev = find_target(vid, pid)
            if dev is None:
                print("[aoa] waiting for device...")
                time.sleep(args.interval_ms / 1000)
                continue
            proto = aoa_ready(dev)
            if proto is None:
                print("[aoa] device present, AOA not ready yet")
                time.sleep(args.interval_ms / 1000)
                continue
            try:
                if registered_for != id(dev):
                    second = "touch" if any(s.startswith("tap:") for s in steps) else "mouse"
                    mouse_ok = register_both(dev, second)
                    registered_for = id(dev)
                    print(f"[aoa] HID registered (proto={proto}, mouse={'yes' if mouse_ok else 'no'})")
                t0 = time.time()
                for step in steps:
                    mouse_ok = run_step(dev, step, mouse_ok)
                cycles += 1
                print(f"[aoa] burst {cycles} done in {(time.time() - t0) * 1000:.0f}ms: "
                      + " ".join(steps))
            except usb.core.USBError as e:
                print(f"[aoa] USBError mid-burst (re-registering): {e}")
                registered_for = None
            time.sleep(args.cycle_ms / 1000)
        if not cycles:
            print("[aoa] gave up: never saw an AOA-ready device in the window")
            sys.exit(1)
        return

    # Monitor-only mode: document how wide the input window actually is.
    if args.timeline:
        os.makedirs(args.out_dir, exist_ok=True)
        out = os.path.join(args.out_dir,
                           f"boot-window-{time.strftime('%Y%m%dT%H%M%SZ', time.gmtime())}.txt")
        print(f"[aoa] timeline monitor, {args.interval_ms}ms polling -> {out}")
        print("[aoa] power-cycle the unit now")
        fh = open(out, "w")
        phases = {}
        t_start = time.time()
        deadline = t_start + args.duration
        last = None
        sends = 0
        try:
            while time.time() < deadline:
                now = time.time()
                t_ms = int((now - t_start) * 1000)
                dev = find_target(vid, pid)
                if dev is None:
                    state = "absent"
                    extra = f"PID={'-' if pid is None else hex(pid)}"
                else:
                    proto = aoa_ready(dev)
                    if proto is None:
                        state = "present-no-aoa"
                        extra = f"PID={hex(dev.idProduct)}"
                    else:
                        state = "aoa-ready"
                        extra = f"PID={hex(dev.idProduct)} proto={proto}"
                        try:
                            register_both(dev)
                            send_report(dev, bytes(8))
                            sends += 1
                            state = "hid-accepted"
                        except usb.core.USBError as e:
                            state = "hid-rejected"
                            extra += f" err={e}"
                if state != last:
                    phases.setdefault(state, t_ms)
                    line = f"t+{t_ms:>6}ms  {state}  {extra}"
                    print(line)
                    fh.write(line + "\n")
                    fh.flush()
                    last = state
                time.sleep(args.interval_ms / 1000)
        finally:
            first = min(phases.values()) if phases else 0
            widths = {k: v - first for k, v in phases.items()}
            summary = (f"[aoa] window summary: first AOA-ready at t+{phases.get('aoa-ready', -1)}ms, "
                       f"reports accepted={sends}, phase offsets={widths}")
            print(summary)
            fh.write(summary + "\n")
            fh.close()
        return

    # One-shot mouse long-press mode (positions come from wherever the cursor already is).
    if args.mouse_longpress is not None:
        print(f"[aoa] mouse long-press {args.mouse_longpress}s at current cursor position")
        deadline = time.time() + 15
        while time.time() < deadline:
            kw = {"idVendor": vid}
            if pid is not None:
                kw["idProduct"] = pid
            dev = usb.core.find(**kw)
            if dev is None:
                print("[aoa] waiting for device..."); time.sleep(0.2); continue
            proto = aoa_protocol(dev)
            if not proto or proto < 2:
                print(f"[aoa] AOA not ready (proto={proto})"); time.sleep(0.2); continue
            try:
                mouse_longpress(dev, args.mouse_longpress)
                print("[aoa] long-press sent (down -> hold -> up)")
                return
            except usb.core.USBError as e:
                print(f"[aoa] USBError during long-press: {e}"); time.sleep(0.2)
        print("[aoa] gave up waiting for device"); sys.exit(1)

    # One-shot single keystroke.
    if args.once:
        print(f"[aoa] one-shot chord {args.chord!r}")
        deadline = time.time() + 15
        while time.time() < deadline:
            kw = {"idVendor": vid}
            if pid is not None:
                kw["idProduct"] = pid
            dev = usb.core.find(**kw)
            if dev is None:
                time.sleep(0.2); continue
            proto = aoa_protocol(dev)
            if not proto or proto < 2:
                time.sleep(0.2); continue
            try:
                register_hid(dev)
                time.sleep(0.05)
                send_report(dev, press)
                time.sleep(0.03)
                send_report(dev, release)
                print("[aoa] chord sent once")
                return
            except usb.core.USBError as e:
                print(f"[aoa] USBError: {e}"); time.sleep(0.2)
        print("[aoa] gave up waiting for device"); sys.exit(1)

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
