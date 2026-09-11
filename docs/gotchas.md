# Gotchas / dead ends

Running list of things that cost time, so we don't repeat them.

## 1. Charge-only cable looks identical to "port does no data"
The external Miko USB port is charge-only (per mgdproductions). But a **charge-only
cable** on the *hidden data port* also produces zero host USB events — same
symptom. Distinguish by testing the same cable against a known Android phone
before blaming the device. A known-good data cable enumerated ours immediately
as `MIKO3 / alps / VID 0x0E8D / PID 0x2008`.

## 2. Chrome's WebUSB steals the device from adb/mtkclient
macOS showing a "connect to a MIKO3 / alps device" prompt can be **Chrome's
WebUSB picker**, not the macOS accessory prompt. If you allow it, Chrome opens an
exclusive handle and holds the device — `ioreg -l -r -n MIKO3` then shows
`Google Chrome  AppleUSBHostDeviceUserClient` children and an `!matched`
composite, and `adb devices` / `fastboot devices` / mtkclient all come up empty.
Fix: close the Chrome tab that requested USB (or quit Chrome) so the native
tools can claim the interfaces. Only one host process can own the USB device at a
time — browser WebUSB and native libusb/adb are mutually exclusive.

## 3. mtkclient on macOS: "Preloader ... Handshake failed, retrying"
Symptom: on every USB (re)connect of the powered-off Miko, mtkclient prints
`Preloader` (it sees the port) then `Status: Handshake failed, retrying`. The
device then continues booting to Android.

What it means: mtkclient reliably detects the MediaTek preloader, but cannot
complete the low-level handshake. On macOS this is typically **driver
contention** — when the preloader/BROM enumerates its CDC-ACM (usbmodem) port,
the Apple USB serial driver attaches to it before mtkclient's libusb can claim
the raw interface, so the timing-critical handshake byte exchange fails. It is
**not** a "missed the window" problem and is not fixed by button-press timing.

Confirmed non-causes on our setup:
- Not a USB hub: the Miko is directly on `AppleT8142USBXHCI` (no intervening Hub).
- Not a missed window: the preloader is caught on every single plug-in. Detection
  succeeding does not mean the handshake will, so pair this with a fixed port
  (`--serialport /dev/cu.usbmodem2100`) rather than `DETECT` — the ACM node that is
  present most of the time belongs to the LG monitor, and `DETECT` can claim that one.

Options (see chosen path in docs/plan.md):
1. **BROM via test point** — short the BROM/EMMC test point to ground while
   connecting USB. Forces BROM (PID 0x0003) and holds it stable, so there is no
   timing window at all. Most reliable on macOS. Needs identifying the pad on the
   board.
2. **Linux host** — mtkclient's handshake is far more reliable on Linux (it can
   detach the kernel driver cleanly). A real Linux box or SBC beats a VM (BROM
   USB passthrough into a VM is itself flaky).
3. **Keep retrying on macOS** with `--crash` (crash preloader into BROM) — low
   effort, may remain flaky.

## 3b. Update: it's a short preloader window, NOT serial-driver contention
Follow-up testing corrected the theory in #3 twice over:
- A fresh `/dev/cu.*` node **does** appear while the preloader is on the bus
  (`/dev/cu.usbmodem2100`), separate from the node that is always present on this Mac
  (`/dev/cu.usbmodem208NTXR9D2662`, the LG monitor's — see `docs/recon.md`). The booted
  Android gadget itself exposes no serial node, just one vendor interface. Pick the
  preloader's node by diffing `/dev/cu.*` against the baseline taken while Android was
  up, and confirm by VID/PID rather than by the `usbmodem` prefix alone.
- The real reason the handshake fails on a responsive link is the comparison,
  not the clock. `tools/mtkclient/mtkclient/Library/Port.py`, in `run_handshake()`, writes the SYNC
  byte-by-byte and requires each byte echoed inverted
  (`echo[0] != (~byte & 0xFF)` → `Echo mismatch`). This chip answers the whole
  SYNC at once with ASCII `READ` then repeated `READY`, which is MediaTek's META
  shape and is what `tools/mtkclient/mtkclient/Library/meta.py` expects. So the per-byte check
  never matches and mtkclient gives up after its retries while the port answers.
  Speak the META shape directly (`scripts/brom-probe.py`) or use the `meta`
  path.
- `--crash` also fails at the same initial-handshake step (crash needs a
  successful handshake first, so it never engages).
- The short window is a real but secondary constraint: the stage is on the bus about
  **2.6 s per appearance** and its serial node is live for roughly **1.1 s** of that,
  with several appearances in the first minute, so a miss is retried in the same power
  cycle. Order the attempts so the serial port is tried first — bulk/ep0 attempts burn
  the whole window on `Errno 60` and `Errno 5` before the node is reached.

Conclusion: we must hold the chip in download mode. The BROM/USBDL key makes the
preloader WAIT for the host indefinitely instead of booting on. Easiest no-plug-
timing method (USB stays connected the whole time):
  1. USB already plugged in; unit fully OFF.
  2. Press and HOLD the head volume-up button.
  3. While still holding it, briefly tap POWER to start boot.
  4. Keep holding volume-up for ~10s. mtkclient (already polling) catches the
     held-open download mode.
If vol-up doesn't hold it, try vol-down, then both. Measured on this unit all three just
boot normally — the accessible buttons are not wired to the download-mode key, so plan on
the plug-in ladder above rather than a held-open window. If a held window is genuinely
needed, the remaining options are the eMMC/BROM test point or a Linux host.

## 4. Only two HID slots over AOA — register the mouse BEFORE the keyboard
With AOAv2, a second `REGISTER_HID` on the free slot makes later `SEND_HID_EVENT`
calls on the first slot return `[Errno 32] Pipe error` until you re-register.
Order that works: mouse/touch first, keyboard last, then confirm the keyboard with
one empty report and fall back to keyboard-only if that confirm fails. Getting
this wrong looks exactly like "the device ignores keystrokes".

## 5. `adb devices` is the wrong success check here
It stays empty until an RSA key is accepted, so a successful "USB debugging on"
flip can look like a failure. Read the descriptor triplet instead
(`aoa-inject.sh --ifaces`): `ff/ff/00` = debugging off, `ff/42/01` = adb present,
`ff/42/03` = the fastboot gadget (fastboot answers, adb does not).
`ioreg` also keeps listing the unit after libusb stops seeing it in accessory
mode, so use ioreg for presence and libusb for I/O.

## 6. AdsDebug/Ace accessory strings engage accessory mode but do not enable adb
Expected from AOSP notes, measured here on Android 9: PID flips to `0x2d00`, adb
still absent. Do not spend more cycles on it; the preloader's META console (item 3b) or
BROM is the way.
