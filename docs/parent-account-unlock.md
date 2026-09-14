# Parent Account Unlock Guide for Miko3

## Problem

Your used Miko3 robot is factory reset but still linked to the previous owner's parent account. This is called "activation lock" and prevents you from linking it to your own account.

## Root Cause

The Miko3 stores a `PARENT_ID` in its database that survives factory resets. The device requires this parent to explicitly unlink it before it can be linked to a new account.

## Your Situation

- ✅ You have root access to the device
- ✅ You can access ADB shell
- ✅ You have local copies of the database files
- ❌ Previous owner is unreachable
- ❌ Miko support is unresponsive

## What We're About To Try

**Approach:** Direct database modification to bypass parent account lock

**Why this should work:**

1. The Miko3 stores the parent account linkage in the `appStore.sqlite` database
2. The `AppConfigResponse` field contains a `parent` field that ties the robot to an account
3. We have root ADB access, so we can directly modify this database
4. Clearing this linkage should allow the robot to be paired with a new parent account

**What we're going to do:**

1. **Backup current state** - Save all databases and configuration to `secrets/unlock-backup-*/`
2. **Modify AppConfigResponse** - Remove the `parent` field and set `LOGIN` to `N`
3. **Clear login tokens** - Remove authentication credentials
4. **Clear app data** - Force the app to reconfigure on next boot
5. **Delete klug directory** - Remove cached configuration files
6. **Power cycle** - Restart robot to apply changes

**Recovery options if this fails:**

```bash
# Restore from latest backup
./scripts/unlock-parent.sh restore

# Or restore from specific backup
./scripts/unlock-parent.sh restore-dir secrets/unlock-backup-20260913-233000/
```

## Solution: Use Your Root Access to Bypass the Lock

### Method 1: Automated Script (Recommended)

Run the new `unlock-parent.sh` script which handles backup and restore automatically:

```bash
cd /Users/bwilson/Documents/miko3-jailbreak
./scripts/unlock-parent.sh unlock
```

**What it does:**

- Creates a backup of the database
- Modifies the `AppConfigResponse` to remove the parent ID field
- Clears login tokens
- Clears app data
- Deletes klug configuration directory

**After running:**

1. Power cycle the robot (turn off, wait 10 seconds, turn on)
2. Wait for the "Linking Code" screen
3. Open Miko Parent App on your phone
4. Create/login to your account
5. Enter the linking code from the robot

### Method 2: Manual Database Modification

If the script doesn't work, you can manually modify the database:

1. **Extract the current database:**

```bash
adb shell 'cp /data/data/com.chidakashi.klug/databases/appStore.sqlite /sdcard/appStore.sqlite'
adb pull /sdcard/appStore.sqlite
```

1. **Modify the database locally:**

```bash
sqlite3 appStore.sqlite "UPDATE SharedPrefMaster SET PrefValue = '{\"parent\":\"\",\"status\":\"success\"}' WHERE PrefName = 'AppConfigResponse';"
sqlite3 appStore.sqlite "UPDATE SharedPrefMaster SET PrefValue = '' WHERE PrefName = 'LoginResponse';"
sqlite3 appStore.sqlite "UPDATE SharedPrefMaster SET PrefValue = '' WHERE PrefName = 'LoginCallTime';"
```

1. **Push the modified database back:**

```bash
adb push appStore.sqlite /sdcard/appStore.sqlite
adb shell 'cp /sdcard/appStore.sqlite /data/data/com.chidakashi.klug/databases/appStore.sqlite'
adb shell 'chown system:system /data/data/com.chidakashi.klug/databases/appStore.sqlite'
```

1. **Clear app data and reboot:**

```bash
adb shell pm clear com.chidakashi.klug
adb shell reboot
```

### Method 3: OTG Keyboard Method (If robot is responsive)

If the robot is showing a screen but not responding to commands:

1. Connect a USB keyboard via OTG adapter
2. Boot the robot
3. Press **Win+N** to open Windows settings (or similar shortcut)
4. Navigate to Settings > Apps > Miko
5. Force stop and clear data
6. Restart the robot

## Important Notes

### Current Parent ID Found in Your Backup

```
Parent ID: mQkU99SDojm8FkFmhqFdPp
```

### Database Locations

- Primary: `/data/data/com.chidakashi.klug/databases/appStore.sqlite`
- Klug config: `/storage/emulated/0/klug/`

### What Gets Cleared

- Parent account linkage
- Login tokens
- App data and settings
- klug configuration files

### What's Preserved

- Firmware version
- Hardware identifiers
- Robot functionality

## Troubleshooting

### If the script says "Device not connected"

- Check USB connection
- Enable ADB debugging (use OTG keyboard to navigate Settings)
- Try: `adb kill-server && adb start-server`

### If the script says "Device not rooted"

- Root the device using: `./scripts/factory-root.sh`
- Or use the OTG keyboard method to enable ADB

### If robot still shows "Linking Code" error after unlock

1. Power cycle (off for 30 seconds, then on)
2. Try Settings > Apps > Miko > Clear Data
3. Force stop the Miko app
4. Restart

### If robot is completely unresponsive

1. Hold power button for 15 seconds to force restart
2. If still stuck, use factory reset procedure
3. Try the OTG keyboard method to access settings

## Alternative: Physical Hardware Access

Since you've already achieved root via the MediaTek preloader method, you also have:

### UART Access

- The device likely has UART test points on the board
- Can access bootloader-level commands
- Can modify partitions directly

### Direct SPI Flash Access

- Can dump and modify the firmware
- Can patch the app to bypass parent check
- Most invasive but most reliable method

## Responsible Use Notice

This guide is for owners of Miko3 robots who need to unlink devices from previous owners. This is not a hacking guide for unauthorized access to other people's robots.

## Success Indicators

You'll know it worked when:

1. Robot shows "Linking Code" screen after reboot
2. You can open Miko Parent App on your phone
3. You can create/login to your parent account
4. You can enter the linking code without errors
5. The robot shows "Connected" and features enable

## Need More Help?

Check these files in your repository:

- `docs/method-factory-root.md` - Root access method
- `docs/ota-update.md` - OTA update mechanism
- `docs/secrets-and-auth.md` - Authentication details
- `recon/APPS.zip_extracted/` - Extracted app files
