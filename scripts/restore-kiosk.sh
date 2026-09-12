#!/usr/bin/env bash
#
# restore-kiosk.sh — restore the Miko 3 kiosk after the "robo mishap" state.
#
# Root cause (see docs/kiosk-recovery-fix.md): the kiosk needs BOTH
#   - com.miko.mikoplus v69  (has activity.appui.MikoActivity)
#   - com.example.root.serviceexam v92 (matching UIEventAIDL binder interface)
# Both come from the same APPS.zip. Restoring only one loops on the splash.
#
# Requires: root ADB (factory mode). Run scripts/factory-root.sh first.
#
# Usage: scripts/restore-kiosk.sh
#
set -uo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
APPS_ZIP="/storage/emulated/0/klug/downloads/APPS.zip"
ENC="/data/local/tmp/restore-enc"
MIKOPLUS_DIR="/data/app/com.miko.mikoplus-jejrc6689SElcf6hABYUJw=="

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
say() { printf '%b%s%b\n' "$1" "$2" "$NC"; }
info() { say "$GREEN" "$*"; }
warn() { say "$YELLOW" "$*"; }
err()  { say "$RED" "$*"; }

adb shell "id" 2>/dev/null | grep -q 'uid=0' || { err "Need root ADB (run scripts/factory-root.sh first)"; exit 1; }
info "Root ADB confirmed."

# ── 1. Stage the update payloads ────────────────────────────────
info "Staging update payloads from APPS.zip ..."
adb shell "
  mkdir -p $ENC
  [ -f $ENC/file1.ia ] || /data/local/tmp/busybox unzip -o $APPS_ZIP file1.ia -d $ENC
  [ -f $ENC/file2.ia ] || /data/local/tmp/busybox unzip -o $APPS_ZIP file2.ia -d $ENC
  ls -la $ENC
" 2>&1

# ── 2. Restore MikoPlus v69 into its original /data/app path ────
info "Restoring MikoPlus v69 to $MIKOPLUS_DIR ..."
adb shell "
  mkdir -p $MIKOPLUS_DIR/lib
  cp $ENC/file2.ia $MIKOPLUS_DIR/base.apk
  chmod 644 $MIKOPLUS_DIR/base.apk
  chmod 755 $MIKOPLUS_DIR $MIKOPLUS_DIR/lib
  chown -R system:system $MIKOPLUS_DIR
" 2>&1

# ── 3. Install the matching ServiceExam update (v92) ────────────
info "Installing ServiceExam update (v92) ..."
adb shell "pm install -r -t $ENC/file1.ia" 2>&1

# ── 4. Verify ───────────────────────────────────────────────────
info "Verifying ..."
adb shell "
  echo -n 'MikoPlus version: '; dumpsys package com.miko.mikoplus 2>/dev/null | grep -m1 versionCode
  echo -n 'appui activity:   '; dumpsys package com.miko.mikoplus 2>/dev/null | grep -c appui.MikoActivity
  echo -n 'ServiceExam ver:  '; dumpsys package com.example.root.serviceexam 2>/dev/null | grep -m1 versionCode
" 2>&1

warn ""
warn "If MikoPlus shows versionCode=69 and appui.MikoActivity count=1,"
warn "and ServiceExam shows versionCode=92, reboot to finish:"
warn "    adb reboot"
warn ""
warn "The AIDL interface check that proves it will work:"
warn "    logcat -d | grep -A15 'onServiceConnected in callback'"
warn "No 'Binder invocation to an incorrect interface' = good."
