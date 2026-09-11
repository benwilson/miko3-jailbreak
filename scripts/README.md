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
   - **Preloader:** plug in USB, press no buttons.
   - **BROM (more reliable on locked units):** hold a volume button (or the head
     button / all HW buttons) while plugging in USB.
4. mtkclient handshakes in the first moment of connection. If it prints
   "Handshake failed, retrying", the preloader window closed too fast — repeat,
   this time plugging into the already-off device so the handshake lands before
   Android boots.

## Boot mode ladder (measured on our unit)

| Stage | Enumeration | What works there |
|-------|-------------|------------------|
| BROM | `0x0e8d:0x0003`, no product string | mtkclient handshake lands here — the ROM waits for the host instead of booting on. Reach it by holding a volume/head button while plugging USB in. |
| Preloader | `0x0e8d:0x2000`-family, very brief | Detected on every boot, handshake usually misses because the window is so short |
| Android | `MIKO3 / alps`, `0x0e8d:0x2008`, one interface 255/255/0 | AOA HID keystrokes (`aoa-inject.sh`); no adb interface is offered |

`scripts/boot-modes.sh 60 50` records those transitions with ms timestamps in
`recon/captures/boot-modes-*.txt`.
