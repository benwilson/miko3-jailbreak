# miko3-jailbreak

Research notes and tooling for gaining root / unrestricted access on a personally
owned **Miko 3** robot (Chidakashi Technologies). The Miko 3 is an Android-based
device running a MediaTek MT8168 SoC with Android 9 (Pie).

## Status: Root Achieved ✅

**We have root.** The device can be rooted via the MediaTek preloader's META
protocol — send `FACTFACT` during the boot window, and the device enters factory
mode with root ADB, no watchdog, and no kiosk.

**To get root:** `scripts/factory-root.sh` (tells you when to power-cycle)

Full documentation: [`docs/method-factory-root.md`](docs/method-factory-root.md)

## Quick Reference

| Task | Command |
|------|---------|
| Get root shell | `scripts/factory-root.sh` |
| Get root + dump firmware | `scripts/factory-root.sh --dump` |
| List firmware backups | `scripts/restore-firmware.sh` |
| Restore one partition | `scripts/restore-firmware.sh boot` |
| Verify backups vs device | `scripts/restore-firmware.sh --check` |
| Switch to fastboot | `scripts/meta-try.sh FASTBOOT` |
| Detect device state | `scripts/miko-detect.sh` |
| AOA HID keystroke injection | `scripts/aoa-inject.sh` |

## Layout

| Path | Contents |
|------|----------|
| `docs/` | Writeups: teardown, boot chain, findings, factory root method |
| `docs/method-factory-root.md` | **The canonical root method** |
| `docs/update-mechanism.md` | Reverse-engineered OTA engine (APPS.zip / `3_files.l` / `.ia`) |
| `docs/mishap-recovery.md` | The "robo mishap" incident: cause + recovery steps |
| `docs/secrets-and-auth.md` | Where the secrets live + how we derived them (redacted) |
| `recon/` | Enumeration results, USB captures, probe logs |
| `recon/captures/` | Timestamped logs from every probe and session |
| `firmware/dump/` | Full firmware backup with SHA-256 manifest (22 images, 8.8 GB) |
| `tools/` | Third-party tooling (mtkclient, etc.) |
| `scripts/` | Repo-local helpers |
| `notes/` | Working scratch |

## Key Scripts

| Script | Purpose |
|--------|---------|
| `factory-root.sh` | **One-command root** — catches preloader, sends FACTFACT, gives ADB shell |
| `restore-firmware.sh` | Restore partitions from backup (ADB or fastboot) |
| `meta-try.sh` | Send arbitrary META mode name to preloader |
| `fastboot-recon.sh` | Exhaustive fastboot probe (getvar + OEM commands) |
| `brom-probe.sh` | Low-level preloader/BROM handshake probe |
| `miko-detect.sh` | One-shot: what mode is the device in? |
| `aoa-inject.sh` | AOAv2 HID keystroke/pointer injection |
| `boot-modes.sh` | Timestamped boot ladder capture |

## Device Profile

- **SoC:** MediaTek MT8168 (quad Cortex-A35)
- **Android:** 9 (SDK 28), `userdebug` build, June 2023
- **Bootloader:** LK, locked (`lks=1`, `unlock_ability=0`)
- **Security:** SELinux disabled, dm-verity enforcing on system/vendor
- **Root:** `/system/bin/su` setuid root, world-executable
- **Serial:** `MIKO3250XXM3Q0636CB`
- **Watchdog:** `com.example.root.serviceexam` (disabled by factory-root.sh)

## Scope

- Hardware is owned outright by the repo author. Everything here is local device
  modification — no attacks against Miko's servers, other people's units, or
  accounts.
- Warranty is forfeit. Assume any step can brick the unit.
- Large binaries are kept out of git. Firmware backups are local only.
