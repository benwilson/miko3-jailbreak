#!/bin/bash
# Script to unlock Miko3 from previous parent account
# With backup/restore functionality

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."

BACKUP_DIR="secrets/unlock-backup-$(date +%Y%m%d-%H%M%S)"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

show_usage() {
    echo "Usage: $0 [COMMAND]"
    echo ""
    echo "Commands:"
    echo "  unlock    - Unlock parent account (default)"
    echo "  backup    - Create backup only"
    echo "  restore   - Restore from latest backup"
    echo "  restore-dir <dir> - Restore from specific backup directory"
    echo "  status    - Show current parent status"
    echo "  help      - Show this help"
    echo ""
    echo "Examples:"
    echo "  $0 unlock              # Unlock and clear parent linkage"
    echo "  $0 backup              # Create backup without making changes"
    echo "  $0 restore             # Restore from latest backup"
    echo "  $0 restore-dir secrets/unlock-backup-20260913-233000/"
}

check_device() {
    if ! command -v adb &>/dev/null; then
        echo -e "${RED}ERROR: adb not found. Please install Android SDK Platform Tools.${NC}"
        exit 1
    fi

    if [ "$(adb get-state 2>/dev/null)" != "device" ]; then
        echo -e "${RED}ERROR: No device connected or device not in ADB mode.${NC}"
        echo "Please ensure ADB is enabled and device is connected."
        exit 1
    fi

    # Verify root access
    if ! adb shell 'test "$(id -u)" -eq 0' 2>/dev/null; then
        echo -e "${RED}ERROR: Device is not rooted or ADB root is not available.${NC}"
        echo "Please root the device first using scripts/factory-root.sh"
        exit 1
    fi
}

MIKO_PACKAGE="com.miko.mikoplus"

get_db_path() {
    # Try multiple possible database locations
    # The main appStore.sqlite is in /storage/emulated/0/klug/APPS/ for our device
    local db_path
    db_path=$(adb shell 'test -f /storage/emulated/0/klug/APPS/appStore.sqlite && echo /storage/emulated/0/klug/APPS/appStore.sqlite' 2>/dev/null)
    if [ -z "$db_path" ]; then
        db_path=$(adb shell 'find /storage/emulated/0/klug -name "appStore.sqlite" -type f 2>/dev/null' | head -1)
    fi
    if [ -z "$db_path" ]; then
        db_path="/data/data/$MIKO_PACKAGE/databases/appStore.sqlite"
    fi
    echo "$db_path"
}

get_parent_id() {
    local db_path
    db_path=$(get_db_path)

    # Try multiple methods to get parent ID
    local result
    result=$(adb shell "sqlite3 '$db_path' \"SELECT PrefValue FROM SharedPrefMaster WHERE PrefName='AppConfigResponse';\"" 2>/dev/null || echo "")

    if [ -n "$result" ]; then
        # Extract parent ID from JSON
        echo "$result" | grep -o '"parent":"[^"]*"' | sed 's/"parent":"//;s/"$//' || echo "not found"
    else
        echo "not found"
    fi
}

do_backup() {
    local db_path
    db_path=$(get_db_path)

    echo "Database path: $db_path"

    # Check if database exists
    if ! adb shell "test -f '$db_path'" 2>/dev/null; then
        echo -e "${RED}Database not found at expected location.${NC}"
        # Try alternative paths
        echo "Trying alternative paths..."
        adb shell 'find /data/data -name "*.sqlite" -type f 2>/dev/null' || true
        return 1
    fi

    # Create backup directory
    mkdir -p "$BACKUP_DIR"

    # Backup appStore.sqlite
    echo -e "${GREEN}Backing up appStore.sqlite...${NC}"
    adb shell "cp '$db_path' /sdcard/appStore.sqlite.backup"
    adb pull /sdcard/appStore.sqlite.backup "$BACKUP_DIR/appStore.sqlite"

    # Backup miko.properties
    echo -e "${GREEN}Backing up miko.properties...${NC}"
    adb shell 'cp /storage/emulated/0/klug/miko.properties /sdcard/miko.properties.backup' 2>/dev/null || true
    adb pull /sdcard/miko.properties.backup "$BACKUP_DIR/miko.properties" 2>/dev/null || echo "  - miko.properties not found"

    # Backup SSL certificates
    echo -e "${GREEN}Backing up SSL certificates...${NC}"
    adb shell 'cp /storage/emulated/0/klug/ssl/node.p12 /sdcard/node.p12.backup' 2>/dev/null || true
    adb pull /sdcard/node.p12.backup "$BACKUP_DIR/ssl/node.p12" 2>/dev/null || echo "  - SSL certificates not found"

    # Backup AES keys
    echo -e "${GREEN}Backing up AES keys...${NC}"
    adb shell 'cp /storage/emulated/0/klug/OTA_1100/*.bin /sdcard/ 2>/dev/null' || true
    mkdir -p "$BACKUP_DIR/ota-keys"
    adb pull /sdcard/*.bin "$BACKUP_DIR/ota-keys/" 2>/dev/null || echo "  - AES keys not found"

    # Backup database files
    echo -e "${GREEN}Backing up database files...${NC}"
    mkdir -p "$BACKUP_DIR/db"
    adb shell 'find /storage/emulated/0/klug -name "*.sqlite" -type f 2>/dev/null' | while read -r f; do
        adb shell "cp '$f' /sdcard/" 2>/dev/null || true
        adb pull /sdcard/"$(basename "$f")" "$BACKUP_DIR/db/" 2>/dev/null || echo "  - Could not backup $(basename "$f")"
    done

    # Create README in backup directory
    echo "# Miko3 Unlock Backup" >"$BACKUP_DIR/README.md"
    echo "" >>"$BACKUP_DIR/README.md"
    echo "Created: $(date)" >>"$BACKUP_DIR/README.md"
    echo "Device Serial: $(adb getserialno 2>/dev/null || echo 'unknown')" >>"$BACKUP_DIR/README.md"
    echo "" >>"$BACKUP_DIR/README.md"
    echo "Contents:" >>"$BACKUP_DIR/README.md"
    echo "- appStore.sqlite: Main app database" >>"$BACKUP_DIR/README.md"
    echo "- miko.properties: Configuration file" >>"$BACKUP_DIR/README.md"
    echo "- ssl/node.p12: SSL certificates" >>"$BACKUP_DIR/README.md"
    echo "- ota-keys/*.bin: Encryption keys" >>"$BACKUP_DIR/README.md"
    echo "- db/*.sqlite: Additional database files" >>"$BACKUP_DIR/README.md"
    echo "" >>"$BACKUP_DIR/README.md"
    echo "To restore: ./scripts/unlock-parent.sh restore" >>"$BACKUP_DIR/README.md"
    echo "" >>"$BACKUP_DIR/README.md"
    echo "Backup created before attempting to clear parent account linkage." >>"$BACKUP_DIR/README.md"

    echo ""
    echo -e "${GREEN}Backup complete: $BACKUP_DIR/${NC}"
    echo ""
    echo "Contents:"
    ls -la "$BACKUP_DIR/"
    echo ""

    return 0
}

do_restore() {
    local restore_dir="$1"

    if [ -z "$restore_dir" ]; then
        # Find latest backup
        restore_dir=$(ls -td secrets/unlock-backup-* 2>/dev/null | head -1)
        if [ -z "$restore_dir" ]; then
            echo -e "${RED}No backup directory found.${NC}"
            echo "Run this script with 'backup' command first."
            exit 1
        fi
    fi

    if [ ! -d "$restore_dir" ]; then
        echo -e "${RED}Backup directory not found: $restore_dir${NC}"
        exit 1
    fi

    echo -e "${YELLOW}Restoring from: $restore_dir${NC}"
    echo ""

    local db_path
    db_path=$(get_db_path)

    if [ ! -f "$restore_dir/appStore.sqlite" ]; then
        echo -e "${RED}Backup appStore.sqlite not found.${NC}"
        exit 1
    fi

    # Restore appStore.sqlite
    echo -e "${GREEN}Restoring appStore.sqlite...${NC}"
    adb push "$restore_dir/appStore.sqlite" /sdcard/appStore.sqlite
    adb shell "cp /sdcard/appStore.sqlite '$db_path'"
    adb shell "chown system:system '$db_path'"

    # Restore miko.properties
    if [ -f "$restore_dir/miko.properties" ]; then
        echo -e "${GREEN}Restoring miko.properties...${NC}"
        adb push "$restore_dir/miko.properties" /sdcard/miko.properties
        adb shell 'cp /sdcard/miko.properties /storage/emulated/0/klug/miko.properties'
    fi

    # Clear app data
    echo ""
    echo -e "${YELLOW}Clearing app data...${NC}"
    adb shell pm clear "$MIKO_PACKAGE"

    echo ""
    echo -e "${GREEN}Restore complete!${NC}"
    echo "Please power cycle the robot."
}

do_status() {
    echo "=== Miko3 Parent Account Status ==="
    echo ""

    local db_path
    db_path=$(get_db_path)
    echo "Database path: $db_path"
    echo ""

    echo "Current AppConfigResponse:"
    adb shell "sqlite3 '$db_path' \"SELECT PrefValue FROM SharedPrefMaster WHERE PrefName='AppConfigResponse';\"" 2>/dev/null | head -c 500
    echo ""
    echo ""

    local parent_id
    parent_id=$(get_parent_id)
    echo "Current Parent ID: $parent_id"
    echo ""

    echo "Login status:"
    adb shell "sqlite3 '$db_path' \"SELECT PrefValue FROM SharedPrefMaster WHERE PrefName='LoginResponse';\"" 2>/dev/null | head -c 100
    echo ""
    echo ""

    echo "Backup directories:"
    ls -td secrets/unlock-backup-* 2>/dev/null | head -5 || echo "  No backups found"
}

do_unlock() {
    check_device

    echo "=== Miko3 Parent Account Unlocker ==="
    echo ""
    echo "Device: $(adb getserialno 2>/dev/null || echo 'unknown')"
    echo ""

    # Show current status
    echo "=== Current Status ==="
    do_status

    echo ""
    echo "=== WARNING: This will remove parent account linkage ==="
    echo ""

    # Get backup confirmation
    echo "Creating backup before making changes..."
    do_backup

    echo ""
    echo "=== Proceeding with unlock... ==="
    echo ""

    local db_path
    db_path=$(get_db_path)

    # Clear parent from AppConfigResponse
    echo -e "${GREEN}Step 1: Clearing parent ID from AppConfigResponse...${NC}"
    adb shell "sqlite3 '$db_path' \"UPDATE SharedPrefMaster SET PrefValue = '{\"parent\":\"\",\"status\":\"success\",\"LOGIN\":\"N\"}' WHERE PrefName='AppConfigResponse';\""

    # Clear login tokens
    echo -e "${GREEN}Step 2: Clearing authentication tokens...${NC}"
    adb shell "sqlite3 '$db_path' \"UPDATE SharedPrefMaster SET PrefValue = '' WHERE PrefName='LoginResponse';\""
    adb shell "sqlite3 '$db_path' \"UPDATE SharedPrefMaster SET PrefValue = '' WHERE PrefName='LoginCallTime';\""

    # Clear parent-related settings
    echo -e "${GREEN}Step 3: Clearing parent-related settings...${NC}"
    adb shell "sqlite3 '$db_path' \"UPDATE SharedPrefMaster SET PrefValue = '' WHERE PrefName LIKE '%parent%';\""
    adb shell "sqlite3 '$db_path' \"UPDATE SharedPrefMaster SET PrefValue = '' WHERE PrefName LIKE '%Parent%';\""

    # Clear app data
    echo ""
    echo -e "${GREEN}Step 4: Clearing app data...${NC}"
    adb shell pm clear "$MIKO_PACKAGE"

    # Reset the FTUE-success flag. ServiceExam's InternetCheckThreadLib and
    # MikoPlus's MikoActivity both independently gate a "Verification Needed"
    # ownership-reverification blocker (ask for the first 4 letters of the
    # registered email, then a real emailed OTP) on:
    #   isFtueFileSuccess() [ftue/ftueStatus.txt == "success"] AND
    #   getValueForAPI_SK() empty [KeyValueMaster.API_STATUS in
    #     g5nyhkzq7ax7v8sx1g3oqg.sqlite]
    # A DB-only unlock (Steps 1-3) leaves ftueStatus.txt at "success" (this
    # bot previously finished setup), so the blocker fires even though the
    # local parent field is cleared. See
    # docs/parent-unlock-email-verification-blocker.md for the full
    # decompiled trace. Rewriting the flag is enough on its own.
    echo -e "${GREEN}Step 5: Resetting FTUE status flag...${NC}"
    adb shell "echo -n 'pending' > /storage/emulated/0/klug/ftue/ftueStatus.txt" 2>/dev/null ||
        echo "  - ftueStatus.txt not found (device may never have completed FTUE - fine)"

    # NOTE: an earlier version of this script also ran
    #   adb shell rm -rf /storage/emulated/0/klug/
    # as "Step 5". Do NOT do this: /storage/emulated/0/klug/ is not
    # parent-lock config, it's the app's runtime asset library (images,
    # audio, expressions, screen_mapping.json, OTA keys, etc). Deleting it
    # leaves the app unable to start (FileNotFoundException on assets it
    # expects to already exist, and it auto-recreates the missing paths as
    # empty directories instead of files) and the launcher falls back to a
    # generic "robo mishap" error screen. The parent-ID/auth-token fields
    # cleared above (Steps 1-3) plus this app-data clear are sufficient to
    # force re-linking; the asset tree must be left in place.

    echo ""
    echo -e "${GREEN}=== Unlock Complete ===${NC}"
    echo ""
    echo "Backup saved to: $BACKUP_DIR/"
    echo ""
    echo "Next steps:"
    echo "1. Power cycle the Miko3 robot (turn off, wait 10 seconds, turn on)"
    echo "2. Expect a 'Verification Needed' screen asking for the first 4"
    echo "   letters of the PREVIOUS owner's registered email -- this is a"
    echo "   real server-validated check (backend sends an OTP to that real"
    echo "   inbox), not a local flag this script can clear for you. See"
    echo "   docs/parent-unlock-email-verification-blocker.md for the full"
    echo "   traced mechanism, including why it appears even after this"
    echo "   script runs, and what happens further downstream if you skip it."
    echo "3. If you know that email: enter it, receive the real OTP, verify,"
    echo "   and the robot proceeds to the actual 'Linking Code' screen."
    echo "4. If you don't: this script cannot get you further on its own --"
    echo "   the device's own backend enforces this, the same way phone"
    echo "   activation lock does. Contact Miko support with proof of"
    echo "   purchase, or the previous owner."
    echo ""
    echo "If the robot still shows parent lock:"
    echo "  - Try Settings > Apps > Miko > Clear Data (using OTG keyboard)"
    echo "  - Or run this script again"
}

# Parse command
COMMAND="${1:-unlock}"

case "$COMMAND" in
unlock)
    do_unlock
    ;;
backup)
    check_device
    do_backup
    ;;
restore)
    check_device
    do_restore ""
    ;;
restore-dir)
    if [ -z "$2" ]; then
        echo -e "${RED}ERROR: Please specify backup directory${NC}"
        echo "Usage: $0 restore-dir <backup_directory>"
        exit 1
    fi
    check_device
    do_restore "$2"
    ;;
status)
    check_device
    do_status
    ;;
help | --help | -h)
    show_usage
    ;;
*)
    echo -e "${RED}Unknown command: $COMMAND${NC}"
    show_usage
    exit 1
    ;;
esac
