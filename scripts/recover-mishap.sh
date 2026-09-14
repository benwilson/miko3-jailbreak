#!/usr/bin/env bash
# recover-mishap.sh - Recovery script for "robo mishap" incident
#
# Use when:
# - Unit shows "Mishap... contact support" error screen
# - Launcher logs show "showing error animation"
# - Kiosk never appears despite booting
#
# Root cause: MikoPlus update was purged, leaving only system version (v11)
# which lacks com.miko.mikoplus.activity.appui.MikoActivity
#
# Recovery: Restore BOTH MikoPlus v69 AND ServiceExam v92 (matching AIDL)
#
# Precondition: Root ADB in factory mode (scripts/factory-root.sh)
#
# Usage:
#   scripts/recover-mishap.sh                    # Full recovery
#   scripts/recover-mishap.sh --no-reboot        # Restore only, no reboot
#   scripts/recover-mishap.sh --status           # Check current state
#
set -uo pipefail
S="${SERIAL:-MIKO3250XXM3Q0636CB}"
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
OTA_DIR="$REPO/firmware/ota-payload" # Where file1.ia, file2.ia should be

red() { printf '\033[0;31m%s\033[0m\n' "$*"; }
grn() { printf '\033[0;32m%s\033[0m\n' "$*"; }
ylw() { printf '\033[1;33m%s\033[0m\n' "$*"; }
sh_() { adb -s "$S" shell "$@"; }

# --- Precondition checks ---
check_preconditions() {
    if ! adb devices | grep -q "$S.*device"; then
        red "no adb device $S -- enter factory mode first: scripts/factory-root.sh"
        exit 1
    fi
    uid="$(sh_ 'id -u' | tr -d '\r')"
    bootmode="$(sh_ 'getprop ro.bootmode' | tr -d '\r')"
    if [ "$uid" != "0" ]; then
        red "not root (id -u=$uid) -- enter factory mode first"
        exit 1
    fi
    grn "connected: uid=$uid bootmode=$bootmode"
}

# --- Status check ---
status() {
    echo "--- Current state ---"
    echo "MikoPlus:"
    sh_ 'pm list packages | grep miko' 2>/dev/null || echo "  (not installed)"
    sh_ 'dumpsys package com.miko.mikoplus | grep -E "versionCode|appui" 2>/dev/null' || true
    echo ""
    echo "ServiceExam:"
    sh_ 'pm list packages | grep serviceexam' 2>/dev/null || echo "  (not installed)"
    sh_ 'dumpsys package com.example.root.serviceexam | grep versionCode 2>/dev/null' || true
    echo ""
    echo "Kiosk activity check:"
    if sh_ 'dumpsys package com.miko.mikoplus | grep appui.MikoActivity' 2>/dev/null; then
        grn "  OK - kiosk activity present"
    else
        red "  MISSING - kiosk activity not found"
    fi
    echo ""
    echo "Apps.json:"
    sh_ 'cat /storage/emulated/0/klug/APPS/apps.json 2>/dev/null || echo "not found"'
}

# --- Recovery ---
recover() {
    local no_reboot="${1:-}"

    grn "Starting mishap recovery..."
    grn "This will restore MikoPlus v69 and ServiceExam v92"
    grn "These MUST be matching versions (AIDL mismatch = splash loop)"
    echo ""

    # Check for OTA payload
    if [ ! -f "$OTA_DIR/file1.ia" ] || [ ! -f "$OTA_DIR/file2.ia" ]; then
        red "OTA payload not found at $OTA_DIR"
        red "Required: file1.ia (ServiceExam v92) and file2.ia (MikoPlus v69)"
        red "Run scripts/factory-root.sh --dump to get these"
        exit 1
    fi

    # Push payload
    grn "Pushing OTA payload..."
    adb -s "$S" push "$OTA_DIR/file1.ia" /data/local/tmp/file1.ia
    adb -s "$S" push "$OTA_DIR/file2.ia" /data/local/tmp/file2.ia

    # Restore MikoPlus update FIRST (runtime apps)
    grn "Installing MikoPlus v69 update..."
    sh_ 'pm install -r -t /data/local/tmp/file2.ia'

    # Restore ServiceExam update SECOND (installer of record)
    grn "Installing ServiceExam v92 update..."
    sh_ 'pm install -r -t /data/local/tmp/file1.ia'

    # Verify
    grn "Verifying installation..."
    echo ""
    echo "MikoPlus:"
    sh_ 'dumpsys package com.miko.mikoplus | grep -E "versionCode|appui"'
    echo ""
    echo "ServiceExam:"
    sh_ 'dumpsys package com.example.root.serviceexam | grep versionCode'
    echo ""
    echo "Kiosk activity:"
    sh_ 'dumpsys package com.miko.mikoplus | grep appui.MikoActivity' &&
        grn "  OK - kiosk activity present" ||
        red "  MISSING - kiosk activity not found"

    if [ -z "$no_reboot" ]; then
        grn "Rebooting to apply changes..."
        adb -s "$S" reboot
    else
        grn "Recovery complete. Reboot manually when ready."
    fi
}

# --- Main ---
case "${1:-}" in
--status | -s)
    check_preconditions
    status
    ;;
--no-reboot | -n)
    check_preconditions
    recover "yes"
    ;;
--help | -h)
    echo "Usage: $0 [--no-reboot|--status|--help]"
    echo ""
    echo "Recovery script for 'robo mishap' - restores MikoPlus/ServiceExam"
    echo "Requires: Root ADB in factory mode"
    ;;
*)
    check_preconditions
    recover ""
    ;;
esac
