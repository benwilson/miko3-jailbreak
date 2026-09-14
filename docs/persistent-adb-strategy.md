# Persistent ADB Strategy - Miko 3

## Overview

This document describes the current strategy for achieving persistent ADB access
in kiosk mode on the Miko 3 device. It covers multiple approaches, their tradeoffs,
and when to use each.

## Current Status

- **Route A (SD card boot hook)**: Mechanism confirmed by decompilation; **not yet tested on device** - requires SD card
- **Route B (no SD card)**: **Broken** - zip4j extracts nothing; cloud-gated; multiple blockers
- **Manual "disable-restore"**: Theoretical - never fully tested; high risk of "robo mishap"
- **Boot agent APK**: Built but cannot be installed in factory mode (no package manager)

## Route A: SD Card Boot Hook ⭐ RECOMMENDED (when SD card available)

### Mechanism

Hooks the kiosk's own boot process via `com.miko.launcher_app` (HOME app).
The launcher's `processInstall()` runs a root shell via `AppUtils.runCommands1()`.

### Files

- `hooks/sd/1_miko3.l` - driver file
- `hooks/sd/payload.sh` - boot hook payload
- `hooks/sd/neuterd` - ARM64 daemon that shadows `/system/bin/reboot`

### How It Works

1. On boot, launcher runs `processInstall()`
2. Engine reads `1_miko3.l` and executes `payload.sh`
3. Payload:
   - Materializes `neuterd` from SD card
   - Neuter shadows `/system/bin/reboot` (watchdog can't reboot)
   - Only THEN starts adbd
   - Re-arms itself by rewriting `1_miko3.l`
4. If neutering fails, adbd never starts = no watchdog trigger = no reboot

### Advantages

- **Atomic safety**: If neuterd fails, adbd never starts
- **Self-healing**: Re-arms every boot
- **OTA survival**: SD card is physically separate
- **No mishap risk**: Never touches ServiceExam
- **No timing issues**: Runs in legitimate boot path
- **No manual intervention**: Fully automated

### Limitations

- **Requires SD card**: Not available currently
- **Hardware dependency**: Must have microSD card inserted

### When to Use

- **Primary choice** when SD card is available
- **Always preferred** over manual approaches
- **Test immediately** when SD card is obtained

## Route B: No SD Card (Broken) ⚠️ CURRENT BLOCKER

### Mechanism

Uses the update app's zip4j extraction of `/sdcard/klug/downloads/APPS.zip`.

### Current Blockers

1. **zip4j extracts nothing**: Our archive produces no files in `ENC/`
2. **Cloud-gated**: App fetches bot details from Miko's servers
3. **version.txt issue**: Version 42 = "already up to date" → stalls at 0%
4. **Network dependency**: May overwrite our archive

### Status

- **Not working** - undiagnosed zip4j issue
- **Test archive created**: `hooks/sd-free-test/APPS-test.zip`
- **Host-side test**: `scripts/test-zip4j.py`
- **Next step**: Test minimal archive on device

### When to Use

- **Only if Route A is impossible**
- **After zip4j issue is diagnosed and fixed**
- **As fallback when SD card unavailable**

## Manual "Disable-Restore" ⛔ HIGH RISK - LAST RESORT

### Mechanism

1. Disable/stop ServiceExam (watchdog)
2. Install boot agent
3. Restore ServiceExam
4. Restore kiosk if mishap occurs

### Risks

- **Could cause "robo mishap"**: If ServiceExam isn't restored correctly
- **Requires manual intervention**: Not self-healing
- **Could brick unit**: If packages.xml is corrupted
- **No fallback**: If recovery fails, unit may be unusable
- **Timing issues**: Race conditions during transition

### When to Use

- **Last resort only** - when all other options exhausted
- **Only with recovery script ready**: `scripts/recover-mishap.sh`
- **Only with backup**: `firmware/agent-backups/` preserved
- **Only by experienced operator**: Understands risk fully

## Recovery Procedures

### Mishap Recovery

If "robo mishap" occurs (error screen, splash loop):

```bash
scripts/recover-mishap.sh
```

- Restores MikoPlus v69 AND ServiceExam v92
- Must be matching versions (AIDL mismatch = splash loop)
- Reference: `docs/mishap-recovery.md`

### Factory Mode Recovery

To re-enter factory mode:

```bash
scripts/factory-root.sh
```

- Uses MediaTek preloader's META protocol
- Sends `FACTFACT` during boot window
- Gives root ADB, no watchdog, no kiosk

## Decision Matrix

| Scenario | Recommended Approach |
| ---------- | --------------------- |
| SD card available | **Route A** - SD card boot hook |
| SD card unavailable, zip4j diagnosed | **Route B** - minimal archive |
| SD card unavailable, zip4j undiagnosed | **Manual recovery script ready**, wait |
| "robo mishap" occurs | **recover-mishap.sh** - restore both apps |
| Unit bricked | **factory-root.sh** - re-enter factory mode |
| Emergency, all else failed | **Manual disable-restore** - last resort |

## Next Steps (Current)

1. **Test minimal Route B archive**: `hooks/sd-free-test/APPS-test.zip`
2. **Run host-side zip4j test**: `scripts/test-zip4j.py`
3. **Verify recovery script**: `scripts/recover-mishap.sh --status`
4. **Document limitation**: This file - why Route A isn't used
5. **Wait for SD card**: Then test Route A immediately

## References

- `docs/boot-hook-findings.md` - Full decompilation analysis
- `docs/mishap-recovery.md` - Mishap cause and recovery
- `docs/kiosk-recovery-fix.md` - Actual kiosk fix (MikoPlus/ServiceExam versions)
- `hooks/README.md` - Boot hook deployment instructions
- `hooks/deploy.sh` - Deployment script
- `hooks/sd/` - Route A artifacts
- `hooks/sd-free/` - Route B artifacts
- `hooks/sd-free-test/` - Minimal test artifacts
- `scripts/install-persistent-adb.py` - Manual boot agent install
- `scripts/recover-mishap.sh` - Mishap recovery script
