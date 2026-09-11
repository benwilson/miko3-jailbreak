# Recon log

## Setup

- Unit: Miko 3, case disassembled, internal micro USB port cabled to the host Mac.
- Host: macOS (Darwin 25.5.0), Apple Silicon.
- Tooling: `adb` / `fastboot` 37.0.1 via `brew install --cask android-platform-tools`.

## 2026-09-11 — baseline, unit powered off

Enumerated USB with the Miko cabled but **not powered on**. No Miko device
present, as expected. Baseline captured to `recon/captures/usb-before-poweron.txt`
(gitignored) for diffing against the powered-on state.

Host devices present at baseline, all accounted for and unrelated to the Miko:

| Device | VID | PID | Notes |
|--------|-----|-----|-------|
| LG Monitor Controls | 0x043e | 0x9439 | Exposes `/dev/cu.usbmodem208NTXR9D2662` — **not** the Miko |
| OWC Thunderbolt 3 Dock SD Reader | 0x1e91 | 0x4002 | |
| OWC Thunderbolt 3 Audio | 0x1e91 | 0x4001 | |
| Blue Yeti X | 0x046d | 0x0aaf | |
| Envoy Pro Elektron | 0x1e91 | 0xde3c | |
| Generic USB2.1 Hub | 0x0bda | 0x5411 | |

Worth noting up front: the LG monitor claims a `usbmodem` CDC-ACM serial node.
Anything that appears after power-on must be confirmed by VID/PID, not by the
`usbmodem` prefix alone.

## Next: power-on enumeration

1. Leave the USB cable connected. Power the unit on.
2. Wait ~60s for a full boot (splash → idle face).
3. `./scripts/usb-snapshot.sh after-poweron`
4. `diff recon/captures/usb-before-poweron.txt recon/captures/usb-after-poweron.txt`

What the diff tells us, in rough order of luck:

| New device | Meaning | Next move |
|------------|---------|-----------|
| `adb devices` lists a device | ADB enabled in the shipped image | Straight to `adb shell`; enumerate with `getprop` |
| ADB listed as `unauthorized` | ADB on, but RSA auth required | Need to accept the host key on-device, or find the UI/settings path |
| `fastboot devices` lists a device | Booted to bootloader | Check `fastboot getvar unlocked` / `all` |
| New `usbmodem` with unknown VID/PID | Likely a UART console | `screen /dev/cu.<node> 115200` and watch the boot log |
| Nothing new appears | Port may be power-only, or ADB is off | Look for a UART on test pads; consider a boot-mode key combo |

The most informative single artifact is a **serial boot log** — it names the SoC,
the bootloader, and usually whether the bootloader is locked. Capture it if a
console shows up.
