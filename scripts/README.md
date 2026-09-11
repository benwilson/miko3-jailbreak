# scripts/

Repo-local helpers. All are macOS/zsh-friendly and support `-h` / `--help`.

| Script | What it does | Depends on |
|--------|--------------|-----------|
| `usb-snapshot.sh [label]` | Dump USB + serial + adb/fastboot state to `recon/captures/usb-<label>.txt` for before/after diffing | `ioreg` (built-in); `adb`/`fastboot` optional |
| `watch-poweron.sh [secs]` | Poll until the device appears (new serial node / adb / fastboot), then log it. Default 180s | `ioreg`, `adb`, `fastboot` |
| `boot-modes.sh [secs] [interval_ms]` | Sample every 50 ms across a boot and log each distinct USB mode with a ms timestamp, so the width of each window is on record | `ioreg` (built-in) |
| `aoa-inject.sh [flags]` | AOAv2 HID injector: `--probe`, `--step`, `--sequence`, `--ladder`, `--timeline`, `--chord` | `tools/aoa-inject` venv (pyusb) |
| `miko-detect.sh` | One-shot: is the Miko visible and in what mode (Android / ADB / fastboot / MediaTek preloader / BROM), and is anything (Chrome WebUSB) holding it | `ioreg` (built-in); `adb`/`fastboot` optional |
| `mtk.sh [args...]` | Friendly wrapper around mtkclient: activates its venv, checks setup, forwards args (e.g. `scripts/mtk.sh printgpt`) | `tools/mtkclient` venv (see `tools/README.md`) |
| `tune-tap.sh [settle_s]` | Try each candidate burst in turn and score it automatically — enabling USB debugging makes the unit re-enumerate, so the winner is detected without watching the screen | `ioreg`, `adb` |
| `brom-probe.sh [flags]` | Watch the boot for the preloader stage, diff its fresh `/dev/cu.*` node against Android's, send the META handshake and a mode name (`--meta FASTBOOT`). Serial first, short reads, several tries per window | `tools/aoa-inject` venv (pyusb + pyserial) |
| `fastboot-once.sh [window_s]` | One bounded pass: probe in the background, wait for fastboot (`0x201c`), then read `devices` + `getvar all` immediately, because later commands in the same session stall | `brom-probe.sh`, `ioreg`, `fastboot` |

## Install the optional dependencies

```bash
brew install --cask android-platform-tools   # adb + fastboot
brew install libusb                           # for mtkclient
# mtkclient itself: see tools/README.md
```

## Entering MediaTek download mode (for mtk.sh)

mtkclient needs the device in **preloader** or **BROM** mode, not normal Android:

1. Power the Miko **OFF** completely and **unplug** the USB.
2. Start the tool polling:  `scripts/mtk.sh printgpt`
3. Then connect USB to the powered-off unit:
   - **Preloader:** plug in USB, press no buttons. This is the stage that answers on
     macOS.
   - **BROM (more reliable on locked units):** hold a volume button (or the head
     button / all HW buttons) while plugging in USB. Tested here as unreliable — the
     buttons usually just boot normally; a test-point short is the dependable way.
4. mtkclient handshakes in the first moment of connection. If it prints
   "Handshake failed, retrying", pass a fixed port instead of letting it detect
   (`--serialport /dev/cu.usbmodem2100`) — the ACM node that is present most of the time
   belongs to the LG monitor, and detection can claim that one. Then repeat, plugging
   into the already-off device so the handshake lands before Android boots.

## Boot mode ladder (measured on our unit)

| Stage | Enumeration | What works there |
|-------|-------------|------------------|
| BROM | `0x0e8d:0x0003`, no product string | mtkclient handshake lands here — the ROM waits for the host instead of booting on. Reach it by holding a volume/head button while plugging USB in. |
| Preloader | `MT65xx Preloader`, `0x0e8d:0x2000` | Detected on every boot. Measured width: about **2.6 s per appearance**, with its CDC-ACM node live for roughly **1.1 s** of that, and it comes back several times in the first minute (one run: t+26.4s, t+38.2s, t+54.3s) — not one sub-second blip, so start the poller before powering on and it gets several attempts per boot. It exposes its own CDC-ACM node (diff `/dev/cu.*` against the baseline taken with Android up; the always-present node is the LG monitor's) which speaks MediaTek's META text handshake — that is the console; see `scripts/brom-probe.sh` |
| Fastboot | `Android`, `0x0e8d:0x201c`, one interface 255/66/3 | Reached by writing `FASTBOOT` to the preloader's META console. `fastboot devices` lists the serial; issue the first read immediately (`scripts/fastboot-once.sh`) |
| Android | `MIKO3 / alps`, `0x0e8d:0x2008`, one interface 255/255/0 | AOA HID keystrokes (`aoa-inject.sh`); no adb interface is offered |
| Accessory | `MIKO3`, `0x18d1:0x2d00` | Reached by the AOAv2 strings handshake (`--ads AdsDebug`). Accessory-only config, so macOS may leave it unclaimed — replug to get a usable handle |

`scripts/boot-modes.sh 60 50` records those transitions with ms timestamps in
`recon/captures/boot-modes-*.txt`. Both it and `miko-detect.sh` match VID `0x0e8d`
**and** `0x18d1`, since the unit changes VID between these stages.
