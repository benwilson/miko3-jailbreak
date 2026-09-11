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
- Not a missed window: the preloader is caught on every single plug-in.

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
Follow-up testing corrected the theory in #3:
- With `--serialport DETECT`, mtkclient still fails identically, and **no
  `/dev/cu.*` node ever appears** when the preloader shows. So the preloader is a
  raw MediaTek bulk-USB device, not a CDC-ACM serial port — there is nothing for
  the Apple serial driver to grab. Driver contention ruled out.
- `--crash` also fails at the same initial-handshake step (crash needs a
  successful handshake first, so it never engages).
- The log shows `Preloader` then `Handshake failed after retries` repeating
  within a single boot = the device enters preloader and boots on to Android
  faster than the handshake completes. **The USB-download window is just too
  short.**

Conclusion: we must hold the chip in download mode. The BROM/USBDL key makes the
preloader WAIT for the host indefinitely instead of booting on. Easiest no-plug-
timing method (USB stays connected the whole time):
  1. USB already plugged in; unit fully OFF.
  2. Press and HOLD the head volume-up button.
  3. While still holding it, briefly tap POWER to start boot.
  4. Keep holding volume-up for ~10s. mtkclient (already polling) catches the
     held-open download mode.
If vol-up doesn't hold it, try vol-down, then both. If none hold on macOS, the
remaining reliable options are the eMMC/BROM test point or a Linux host.
