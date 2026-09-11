# tools/

Third-party tooling. Clones live here but are **gitignored** (see `.gitignore`);
this file tracks provenance and any local patches so the setup is reproducible.

## mtkclient (bkerler)

- Source: <https://github.com/bkerler/mtkclient> (`git clone --depth 1`), V2.1.4.
- Purpose: talk to the MediaTek BROM / preloader over USB to dump/flash and (on
  exploitable SoCs) bypass bootloader lock — independent of Android/ADB. This is
  our primary route since USB debugging is OFF on the unit (single 255/255/0
  interface, no adb).
- Setup:
  ```
  cd tools/mtkclient
  python3 -m venv .venv && . .venv/bin/activate
  pip install -U pip && pip install -r requirements.txt
  ```
- Local patch (Python 3.14 / no libfuse on this Mac): `mfusepy` is only needed
  for the optional `mtk fs` mount feature and raises `OSError` when libfuse is
  absent. Broadened two `except ImportError:` guards to `except (ImportError,
  OSError):` so the CLI runs without macFUSE:
  - `mtkclient/Library/Filesystem/mtkdafs.py` (~line 81)
  - `mtkclient/Library/DA/mtk_da_handler.py` (~line 18)
  Re-apply after any `git pull` of mtkclient.
- Verify: `python mtk.py --help` (prints "fuse library not installed", then usage).

### Entering MediaTek download mode (needed before mtkclient can talk)

The unit in normal Android enumerates as VID 0x0E8D / PID 0x2008 (a vendor
interface, not a mode mtkclient handshakes). To use mtkclient we must reach:
- **Preloader** (PID 0x2000/0x2001/0x2003): appears briefly on every boot while
  USB is connected — mtkclient can catch it if it is already polling.
- **BROM** (PID 0x0003): earlier/lower stage; most reliable on locked devices via
  the "test point" (kamakiri) short-to-ground while plugging in, or `--crash`
  from preloader.

## aoa-inject (this repo)

- `scripts/aoa-inject.py` + `scripts/aoa-inject.sh`: AOAv2 HID keystroke injector.
- Purpose: inject `Meta+N` (etc.) into the Miko over USB with the Mac as host,
  using Android Open Accessory HID over the control endpoint — no OTG adapter, no
  adb. Polls fast (default 50 ms) to catch the brief boot-time input window.
- Setup:
  ```
  python3 -m venv tools/aoa-inject/.venv
  tools/aoa-inject/.venv/bin/pip install pyusb        # + Homebrew libusb
  ```
- Probe support first (sends nothing): `scripts/aoa-inject.sh --probe`
- Inject: `scripts/aoa-inject.sh --chord meta+n --interval-ms 50 --duration 240`
