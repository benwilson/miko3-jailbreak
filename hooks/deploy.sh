#!/usr/bin/env bash
# Deploy the Miko 3 root-ADB boot hook.
#
#   hooks/deploy.sh            # Route B (no SD card needed)
#   hooks/deploy.sh --sd       # Route A (SD card must be inserted)
#   hooks/deploy.sh --verify   # just report what is currently in place
#
# Requires factory-mode root adb (scripts/factory-root.sh). Route B rewrites
# apps.json, so this backs it up first.
set -uo pipefail
S="${SERIAL:-MIKO3250XXM3Q0636CB}"
HERE="$(cd "$(dirname "$0")" && pwd)"
MODE="${1:-}"

red()  { printf '\033[0;31m%s\033[0m\n' "$*"; }
grn()  { printf '\033[0;32m%s\033[0m\n' "$*"; }
ylw()  { printf '\033[1;33m%s\033[0m\n' "$*"; }
sh_()  { adb -s "$S" shell "$@"; }

# --- preconditions -------------------------------------------------------
if ! adb devices | grep -q "$S.*device"; then
  red "no adb device $S -- enter factory mode first: scripts/factory-root.sh"; exit 1
fi
uid="$(sh_ 'id -u' | tr -d '\r')"; mode="$(sh_ 'getprop ro.bootmode' | tr -d '\r')"
[ "$uid" = "0" ] || { red "not root (id -u=$uid)"; exit 1; }
grn "connected: uid=$uid bootmode=$mode"

if [ "$MODE" = "--verify" ]; then
  echo "--- hook state ---"
  printf 'APPS.zip        : '; sh_ 'ls -la /sdcard/klug/downloads/APPS.zip 2>/dev/null || echo absent'
  printf 'miko3/ payload  : '; sh_ 'ls -la /sdcard/klug/miko3/ 2>/dev/null || echo absent'
  printf 'apps.json entry : '; sh_ 'grep -o "com.miko.update_app[^\"]*" /sdcard/klug/APPS/apps.json 2>/dev/null | head -1 || echo none'
  printf 'hook log        : '; sh_ 'ls -la /data/local/tmp/miko3-hook.log 2>/dev/null || echo absent'
  printf 'sd card present : '; sh_ 'ls -d /storage/sdcard1 2>/dev/null || echo "no /storage/sdcard1"'
  exit 0
fi

TS="$(date -u +%Y%m%dT%H%M%SZ)"
if [ "$MODE" = "--sd" ]; then
  # ---------- Route A: SD card ----------
  sh_ 'ls -d /storage/sdcard1' >/dev/null 2>&1 || { red "no /storage/sdcard1 -- insert the SD card"; exit 1; }
  ylw "deploying Route A (SD card)"
  D=/storage/sdcard1
  sh_ "mkdir -p $D/UPDATE_APP_INSTALL_DIR $D/miko3"
  adb -s "$S" push "$HERE/sd/1_miko3.l"   "$D/UPDATE_APP_INSTALL_DIR/1_miko3.l"
  adb -s "$S" push "$HERE/sd/payload.sh"  "$D/miko3/payload.sh"
  adb -s "$S" push "$HERE/sd/neuterd"     "$D/miko3/neuterd"
  sh_ "chmod 755 $D/miko3/payload.sh $D/miko3/neuterd; chmod 644 $D/UPDATE_APP_INSTALL_DIR/1_miko3.l"
  sh_ "ls -la $D/UPDATE_APP_INSTALL_DIR/ $D/miko3/"
  grn "Route A deployed. NOTE: exactly ONE .l must be in UPDATE_APP_INSTALL_DIR for the"
  grn "launcher to hand off to com.miko.update_app (the installer)."
else
  # ---------- Route B: no SD card ----------
  ylw "deploying Route B (no SD card)"
  sh_ "mkdir -p /sdcard/klug/miko3 /sdcard/klug/downloads"
  adb -s "$S" push "$HERE/sd-free/APPS.zip"     /sdcard/klug/downloads/APPS.zip
  adb -s "$S" push "$HERE/sd-free/payload.sh"   /sdcard/klug/miko3/payload.sh
  adb -s "$S" push "$HERE/sd-free/neuterd"      /sdcard/klug/miko3/neuterd
  sh_ "chmod 755 /sdcard/klug/miko3/payload.sh /sdcard/klug/miko3/neuterd"
  # back up apps.json, then point it at the updater launched with NO extras,
  # which makes UpdateActivity default LAUNCHED_BY to FTUE -> launchedByLauncher=false
  sh_ "cp -n /sdcard/klug/APPS/apps.json /sdcard/klug/APPS/apps.json.pre-hook-$TS"
  echo "--- CURRENT apps.json (patch this by hand if the shape differs) ---"
  sh_ 'cat /sdcard/klug/APPS/apps.json'
  ylw "apps.json NOT modified automatically -- inspect the above and repoint the"
  ylw "'target\":\"true\"' entry at com.miko.update_app, then re-run with --verify."
  sh_ "ls -la /sdcard/klug/miko3/ /sdcard/klug/downloads/APPS.zip"
fi
grn "done. Reboot, then: adb devices / hooks/deploy.sh --verify"
