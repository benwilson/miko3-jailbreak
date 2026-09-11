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
