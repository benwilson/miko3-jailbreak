#!/usr/bin/env python3
"""
brom-probe.py — catch the MediaTek preloader/BROM stage and attempt the raw
download-mode handshake ourselves, with millisecond timestamps.

Why this exists:
  mtkclient on this unit detects the preloader *reliably* (one restart logged 135
  detections) but prints `Status: Handshake failed, retrying...` every single
  time. That means the failure is at handshake level, not "missed the tiny boot
  window" — so we need per-step visibility that mtkclient does not give: how long
  the stage actually stays up, whether the interface can be claimed, whether the
  write lands, and how many bytes come back.

What it does:
  1. optionally sends a host-side port reset so the unit re-runs its boot ladder
     while we are already listening (this unit restarts Android on a port reset);
  2. watches enumeration every 20 ms and time-stamps each stage it sees
     (0x2000-family = preloader, 0x0003 = BROM, 0x2008 = Android);
  3. during preloader/BROM stages it detaches the kernel driver, claims the first
     interface, sends the 4-byte download-mode SYNC, and reads the reply;
  4. prints hex for every attempt so a short/partial read is distinguishable from
     a stalled pipe.

Run it via scripts/brom-probe.sh (same venv as aoa-inject).
"""
import argparse
import glob
import time

import usb.core
import usb.util

# MediaTek stages all enumerate under this VID.
MTK_VID = 0x0E8D
PRELOADER_PIDS = {0x2000, 0x2001, 0x2002, 0x2003, 0x200A, 0x200B, 0x0003}
ANDROID_PID = 0x2008

# Download-mode SYNC + expected replies. Measured on this MT8167: the preloader
# answers the SYNC with ASCII "READ" (52 45 41 44) on its CDC-ACM port at 115200,
# which is the DA-ready signal; the inverted echo is what other revisions send.
SYNC = b"\xa0\x0a\x50\x05"
ECHO = b"\x5f\xf5\xaf\xfa"
READY = (ECHO, b"READ")


def log(msg):
    print(f"t+{(time.time() - T0) * 1000:7.0f}ms  {msg}", flush=True)


def find():
    return usb.core.find(idVendor=MTK_VID)


def dump_cfg(dev):
    """Compact endpoint map, so a missing bulk pair is visible in the log."""
    try:
        cfg = dev.get_active_configuration()
    except usb.core.USBError as e:
        log(f"  cfg read failed: {e}")
        return None
    parts = []
    for intf in cfg:
        eps = []
        for e in intf:
            direc = "IN" if usb.util.endpoint_direction(e.bEndpointAddress) \
                == usb.util.ENDPOINT_IN else "OUT"
            eps.append(f"ep{e.bEndpointAddress:02x}-{direc}/{e.wMaxPacketSize}"
                       f"/{usb.util.endpoint_type(e.bmAttributes)}")
        parts.append(f"if{intf.bInterfaceNumber}"
                     f"[{intf.bInterfaceClass:02x}/{intf.bInterfaceSubClass:02x}"
                     f"/{intf.bInterfaceProtocol:02x}]{' '.join(eps) or 'no-eps'}")
    log(f"  cfg: {' | '.join(parts)}")
    return cfg


def bulk_pair(dev, cfg):
    if not cfg:
        return None, None
    intf = cfg[(0, 0)]
    ep_out = usb.util.find_descriptor(
        intf, custom_match=lambda e: usb.util.endpoint_direction(e.bEndpointAddress)
        == usb.util.ENDPOINT_OUT)
    ep_in = usb.util.find_descriptor(
        intf, custom_match=lambda e: usb.util.endpoint_direction(e.bEndpointAddress)
        == usb.util.ENDPOINT_IN)
    return ep_out, ep_in


def bulk_try(dev, cfg, tag, claimed):
    """Write SYNC / read echo on the bulk pair. `claimed` = claim first."""
    ep_out, ep_in = bulk_pair(dev, cfg)
    if ep_out is None or ep_in is None:
        log(f"  {tag}: no bulk IN+OUT pair visible")
        return False
    if claimed:
        try:
            usb.util.claim_interface(dev, 0)
            log(f"  {tag}: claimed if0")
        except usb.core.USBError as e:
            log(f"  {tag}: claim if0 -> {e} (continuing unclaimed)")
    try:
        wrote = ep_out.write(SYNC, timeout=1500)
        got = bytes(ep_in.read(4, timeout=1500))
        log(f"  {tag}: bulk wrote={wrote} read={got.hex()} echo_ok={got == ECHO}")
        return bool(got)
    except usb.core.USBError as e:
        log(f"  {tag}: bulk({'claimed' if claimed else 'bare'}) -> {e}")
        return False


def serial_hunt(tag, baseline, metamode=None, budget=2.5):
    """The MT8167 preloader exposes CDC-ACM, so macOS binds a serial node to it.
    Only nodes that were NOT present with Android up can be the preloader's own
    port, so the baseline list is what makes the pick reliable.

    The whole preloader stage lasts about 2.3 s, so this is a tight retry loop with
    short reads rather than one long-blocking open: the node also flaps in and out,
    and a single 1.5 s timeout would eat the entire window."""
    try:
        import serial  # type: ignore
    except ImportError:
        log(f"  {tag}: pyserial missing; install it into tools/aoa-inject/.venv")
        return False
    end = time.time() + budget
    tries, shown, last_err = 0, None, None
    while time.time() < end:
        nodes = sorted(set(glob.glob("/dev/cu.usb*") + glob.glob("/dev/cu.MTK*")))
        fresh = [n for n in nodes if n not in baseline]
        if fresh != shown:
            log(f"  {tag}: fresh serial nodes -> {fresh or 'none'}")
            shown = fresh
        for node in fresh:
            tries += 1
            try:
                with serial.Serial(node, 115200, timeout=0.3) as s:
                    s.reset_input_buffer()
                    s.write(SYNC)
                    got = bytes(s.read(4))
                    log(f"  {tag}: {node}@115200 try{tries} read={got.hex()} "
                        f"ready={got in READY}")
                    if got:
                        # The reply is ASCII, so this port takes the META text
                        # protocol: read until READY, write a mode name, then see what
                        # the other side says. That is how a locked unit gets moved
                        # into fastboot without any UI interaction.
                        for _ in range(6):
                            if b"READY" in got:
                                break
                            time.sleep(0.05)
                            got += bytes(s.read(64))
                        log(f"  {tag}: stream={got[:40]!r}")
                        if metamode:
                            s.reset_input_buffer()
                            s.write(metamode.encode())
                            time.sleep(0.4)
                            reply = bytes(s.read(512))
                            log(f"  {tag}: after {metamode!r} -> {reply!r}")
                        return True
            except Exception as e:  # noqa: BLE001
                last_err = str(e)
        time.sleep(0.02)
    log(f"  {tag}: serial_hunt {tries} tries, last error: {last_err}")
    return False


def attempt(dev, n, baseline, metamode=None):
    """One handshake try. Serial first — it is the interface the preloader actually
    answers on and it is fast — then the bulk pair, then ep0."""
    tag = f"try{n}"
    if serial_hunt(tag, baseline, metamode):
        return True
    cfg = dump_cfg(dev)
    if bulk_try(dev, cfg, tag, claimed=False):
        return True
    if bulk_try(dev, cfg, tag + "b", claimed=True):
        return True
    # Control-transfer fallback: some MTK stages only answer on ep0.
    try:
        got = bytes(dev.ctrl_transfer(0xC0, 0x05, 0, 0, SYNC, timeout=1500))
        log(f"  {tag}: ep0 read={got.hex()}")
        return bool(got)
    except usb.core.USBError as e:
        log(f"  {tag}: ep0 -> {e}")
    return False


def main():
    global T0
    ap = argparse.ArgumentParser(description="Probe MediaTek download mode with timestamps")
    ap.add_argument("--duration", type=int, default=70, help="seconds to watch (default 70)")
    ap.add_argument("--interval-ms", type=int, default=20, help="poll interval (default 20)")
    ap.add_argument("--meta", default=None, metavar="MODE",
                    help="after READY, switch the preloader to this META mode "
                         "(FASTBOOT, METAMETA, ADVEMETA, FACT, FACTORYM)")
    ap.add_argument("--no-reset", action="store_true",
                    help="do not send the initial port reset (watch a manual power-cycle instead)")
    args = ap.parse_args()

    T0 = time.time()
    baseline = sorted(glob.glob("/dev/cu.usb*") + glob.glob("/dev/tty.usb*")
                      + glob.glob("/dev/cu.MTK*"))
    log(f"baseline serial nodes with Android up: {baseline or 'none'}")
    if not args.no_reset:
        dev = find()
        if dev is None:
            log("no MediaTek device on bus yet — watching anyway")
        else:
            try:
                dev.reset()
                log(f"port reset sent (PID={hex(dev.idProduct)}); boot ladder starting")
            except usb.core.USBError as e:
                log(f"initial reset failed: {e}")

    last = None
    attempts = 0
    hits = 0
    deadline = T0 + args.duration
    while time.time() < deadline:
        dev = find()
        pid = None if dev is None else dev.idProduct
        if pid != last:
            name = {ANDROID_PID: "android", None: "absent", 0x0003: "BROM"}.get(pid, "preloader")
            log(f"stage: {'none' if pid is None else hex(pid)} ({name})")
            last = pid
        if pid is not None and pid != ANDROID_PID:
            attempts += 1
            if attempt(dev, attempts, baseline, args.meta):
                hits += 1
                log("HANDSHAKE OK — download mode is reachable from this host")
                return
        time.sleep(args.interval_ms / 1000)

    log(f"done: {attempts} handshake attempts, {hits} replies")


if __name__ == "__main__":
    main()
