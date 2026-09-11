# Plan / current state

## Where we are (2026-09-11)

Goal is narrow: **get adb up**, then everything else is ordinary adb work. Two
entries exist, one per boot stage:

- **Fully booted (`0x0e8d:0x2008`)** — Android's own UI, driven by injected HID
  keystrokes over the micro USB. Enable USB debugging here. See
  `docs/method-otg-keyboard-adb.md` for the ladder and what this unit's shade
  actually contains.
- **Boot ROM (`0x0e8d:0x0003`)** — reachable by holding a head/volume button while
  plugging USB in. The ROM waits for the host, so `scripts/mtk.sh printgpt`
  handshakes there rather than racing the preloader window.

`scripts/boot-modes.sh 60 50` logs which stage is enumerated when, at 50 ms
resolution, into `recon/captures/boot-modes-*.txt`.

### Route order (current)

1. `scripts/aoa-inject.sh --ads AdsDebug` — AOAv2 strings handshake. No UI
   navigation, works in the booted stage, and the accessory switch was observed to
   take effect immediately. Needs a replug afterwards for the host to claim the new
   configuration.
2. **BROM (`0x0e8d:0x0003`)** by holding a head/volume button while plugging USB in,
   with `scripts/mtk.sh` already polling. The ROM waits for the host, so there is no
   race.
3. Keystroke ladder in the booted stage — the fallback if the first two stay stuck.

The preloader window is wider than first assumed: about **2.6 s per appearance**,
recurring several times during boot (see `scripts/README.md`), which is why
"poll before powering on" is worth one honest attempt before reaching for hardware.

Original baseline notes:

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
