# Plan / current state

## Where we are (2026-09-11)

Confirmed about the unit:
- SoC: **MediaTek** (VID 0x0E8D). Normal Android enumerates as `MIKO3` / vendor
  `alps`, PID 0x2008, serial `MIKO3250XXM3Q0636CB`.
- **USB debugging is OFF** — the only Android USB interface is vendor-specific
  (255/255/0), no adb. So no adb/root shell via the shipped image.
- **Bootloader route via mtkclient is the target**, but blocked at the handshake.

### The mtkclient blocker (fully characterized)
- mtkclient **reliably detects the MediaTek preloader** on every boot, but the
  low-level **handshake fails every time** on macOS.
- Not driver contention: preloader is raw bulk USB, no `/dev/cu.*` node appears;
  `--serialport DETECT` and `--crash` fail identically.
- Root cause: the preloader's USB-download window is **too short** — the chip
  boots on to Android before macOS/libusb completes the handshake.
- **The accessible buttons do NOT hold download mode open.** Tested head
  volume-up and volume-down, each with USB connected + tap power + hold ~10s:
  both just boot normally (normal boot logo, no USB-download device). The robot's
  buttons are not wired to the SoC download-mode key (KPCOL0) — consistent with
  the mgdproductions note that the head volume-up didn't trigger fastboot unlock.

## Decision point — two real paths left (macOS-compatible)

1. **eMMC / BROM test point (hardware).** Short the correct pad to ground while
   powering on to force **BROM mode (PID 0x0003)**, which waits for the host
   indefinitely — no timing window, no buttons needed. This is the natural fit
   since the case is already open and the buttons can't do it. Needs: clear,
   well-lit photos of both sides of the mainboard (around the eMMC/SoC) to
   identify the pad; a bit of careful shorting (tweezers/wire) during power-on.
   mtkclient keeps polling and grabs the held-open BROM.

2. **Linux host (no hardware).** mtkclient's USB latency is much lower on Linux
   and it can detach the kernel driver cleanly, so it often completes the
   handshake within the same short window macOS misses. Needs a real Linux box or
   SBC (a VM's BROM USB passthrough is itself unreliable). No board poking.

Both end at the same place: a BROM/preloader session in mtkclient → dump
partitions → assess bootloader unlock / root persistence.

### Recommendation
Given the unit is already open, the **test point** is the most reliable and is
macOS-native. If opening the board further / shorting a pad isn't appealing, a
**Linux host** is the clean no-hardware alternative with genuinely better odds
than continuing on macOS. Continuing macOS-software-only is unlikely to progress
now that the buttons are ruled out.
