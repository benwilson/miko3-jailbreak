# Method: Factory Root via META → FACTFACT

## Summary

One-command root shell on the Miko 3. Uses the MediaTek preloader's META
serial protocol to switch the device into **factory mode**, which boots Android
with root ADB and no kiosk/watchdog running.

**Reproducible. No hardware modification. No flashing. Works on latest firmware (June 2023).**

## Prerequisites

- USB cable (micro-USB, data-capable — test against a known Android phone first)
- macOS with Homebrew `adb` and `libusb` installed
- The Miko 3's hidden data port (remove bottom screws, lift top, micro-USB port inside)

## Quick Start

```bash
# One command — tells you when to power-cycle:
scripts/factory-root.sh

# Auto-dump all firmware after root:
scripts/factory-root.sh --dump

# Just give me a shell:
scripts/factory-root.sh --shell
```

## What It Does

```
Power on → BROM → Preloader (PID 0x2000, ~2.3s window)
                        ↓
              CDC-ACM serial port appears (/dev/cu.usbmodem*)
                        ↓
              We send: SYNC (a0 0a 50 05) → device answers READY
                        ↓
              We send: FACTFACT → device enters factory mode
                        ↓
              Android boots with:
                - Root ADB (uid=0, ro.secure=0)
                - No kiosk app (com.miko.* not started)
                - No watchdog (ServiceExam not started)
                - SELinux permissive
                - dm-verity enforcing (system/vendor read-only)
```

## Manual Steps (if script fails)

1. Start the preloader watcher:
   ```bash
   scripts/brom-probe.sh --no-reset --meta FACTFACT --duration 90
   ```

2. Power-cycle the robot (off, then on).

3. The probe catches the preloader window, sends the META handshake, and
   the device enters factory mode.

4. Once ADB appears:
   ```bash
   adb shell          # you are root (uid=0)
   ```

5. Disable the watchdog (first time only):
   ```bash
   adb shell "mv /data/app/com.example.root.serviceexam-* /data/app/com.example.root.serviceexam-disabled"
   ```

## Device Intel

| Property | Value |
|---|---|
| SoC | MediaTek MT8168 (quad Cortex-A35) |
| Android | 9 (Pie), SDK 28 |
| Build | `full_tb8168p1_64_bsp-userdebug` |
| Build date | 2023-06-10 |
| Security patch | 2020-01-05 |
| Bootloader | LK, locked (`lks=1`, `unlock_ability=0`) |
| SELinux | Disabled (`ro.boot.selinux=disable`) |
| dm-verity | Enforcing on system/vendor |
| Root su | `/system/bin/su` setuid root, world-executable |
| ADB over TCP | Port 5555 (configured, but Wi-Fi off in factory mode) |
| Watchdog | `com.example.root.serviceexam` — greps for adbd, reboots if found |
| Serial | `MIKO3250XXM3Q0636CB` |

## Partition Layout

| # | Name | Size | Device | Purpose |
|---|---|---|---|---|
| 1 | proinfo | 3 MB | mmcblk0p1 | Device info |
| 2 | boot_para | 1 MB | mmcblk0p2 | Boot parameters |
| 6 | nvram | 5 MB | mmcblk0p6 | Wi-Fi/BT calibration |
| 9 | persist | 48 MB | mmcblk0p9 | Persistent settings |
| 10 | nvcfg | 8 MB | mmcblk0p10 | NVRAM config |
| 11 | seccfg | 256 KB | mmcblk0p11 | Security/lock state |
| 12 | lk | 1 MB | mmcblk0p12 | Little Kernel (fastboot) |
| 13 | lk2 | 1 MB | mmcblk0p13 | Backup LK |
| 14 | boot | 16 MB | mmcblk0p14 | Kernel + ramdisk |
| 15 | recovery | 16 MB | mmcblk0p15 | Recovery image |
| 16 | para | 512 KB | mmcblk0p16 | Parameters |
| 17 | logo | 8 MB | mmcblk0p17 | Boot logo |
| 18 | dtbo | 8 MB | mmcblk0p18 | Device tree overlay |
| 20 | frp | 1 MB | mmcblk0p20 | Factory reset protection |
| 21 | nvdata | 32 MB | mmcblk0p21 | NVRAM data |
| 22-23 | tee1/tee2 | 5 MB | mmcblk0p22-23 | TrustZone |
| 27 | vbmeta | 11.3 MB | mmcblk0p27 | Verified boot metadata |
| 28 | system | 8.3 GB | mmcblk0p28 | System (dm-verity) |
| 29 | vendor | 400 MB | mmcblk0p29 | Vendor (dm-verity) |
| 30 | cache | 112 MB | mmcblk0p30 | Cache |
| 31 | userdata | 19.9 GB | mmcblk0p31 | User data |
| — | preloader | 4 MB | mmcblk0boot0 | Preloader (hidden boot0) |
| — | preloader2 | 4 MB | mmcblk0boot1 | Preloader backup |

## META Mode Names (from mtkclient)

| Mode | String | Effect |
|---|---|---|
| FASTBOOT | `FASTBOOT` | Bootloader fastboot mode |
| FACTFACT | `FACTFACT` | **Factory test mode → root ADB** |
| FACTORYM | `FACTORYM` | ATE Signaling Test |
| METAMETA | `METAMETA` | MAUI META engineering mode |
| ADVEMETA | `ADVEMETA` | Advanced META mode |
| AT+NBOOT | `AT+NBOOT` | AT command boot |

## Watchdog Details

- Package: `com.example.root.serviceexam`
- APK location: `/data/app/com.example.root.serviceexam-GkwqFZdithqFCIi_l8n9kw==/base.apk` (106 MB)
- Behavior: greps `ps` for `adbd` every ~2s, calls `su -c reboot` if found
- Disable: rename the APK directory (add `.disabled` suffix)
- Note: ServiceExam is also the `installer` for all child apps (packages.xml shows `installer="com.example.root.serviceexam"` for every app)
- Factory mode: ServiceExam does NOT start — this is the safe state

## Recovery Procedure

If the device won't boot or ADB is lost:

1. **Re-enter factory mode:** Power-cycle and run `scripts/factory-root.sh`
2. **Re-enter fastboot:** `scripts/meta-try.sh FASTBOOT`
3. **Restore firmware via fastboot:** `fastboot flash <partition> <image>`
4. **Restore firmware via root ADB:** `dd if=<image> of=/dev/block/mmcblk0p<N>`

Firmware backups with SHA-256 hashes are in `firmware/dump/`.
See `firmware/dump/` for a complete manifest.

**Never write to mmcblk0boot0 or mmcblk0boot1 (preloader) — hard brick risk.**

## Gotchas

1. **Charge-only cable** looks identical to a dead port. Test the cable against a known phone first.
2. **Chrome WebUSB** steals the device. Close Chrome before running any tools.
3. **Preloader window is ~2.3s** — the probe must be running BEFORE power-on.
4. **Fastboot is flaky** — first command answers, later ones may stall. Issue commands quickly.
5. **Factory mode has no Wi-Fi** — ADB is USB-only in factory mode.
6. **Recovery partition is fragile** — always verify writes against backups.
7. **PID 0x2006** = factory mode, **PID 0x2008** = normal Android, **PID 0x201c** = fastboot.
