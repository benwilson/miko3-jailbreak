#!/bin/bash
# Codified permanent adb-over-wifi for OpenMiko — the "specific boot op", as a purpose-built APK (NO Termux, NO Nova).
#
# WHAT IT IS: com.openmiko.bootagent carries a manifest BOOT_COMPLETED receiver (the same mechanism a launcher uses to
# auto-start). At every boot it execs /system/bin/su and (1) neuters the anti-tamper reboot watchdog by bind-mounting a
# no-op over /system/bin/reboot, then (2) brings adb up on tcp 5555. Source + no-gradle build in ./bootagent/.
#
# WHY an APK and not a property/init.d: verified on-device (Android 9 / mt8167) — verity is ENFORCING, /system+/vendor
# init are read-only, adbd is `disabled`+USB-config-gated (off at boot), and NO init trigger consumes persist.adb.tcp.port.
# So the only way to run a root command at boot is an app boot-receiver.
#
# RUN THIS once, from your pc, over the FIRST adb session (the keyboard-bootstrap gets you there one time). After it,
# every reboot brings adb back automatically — you can uninstall Nova, Termux, w/e else.
set -euo pipefail
DEVICE="${1:-192.168.4.34:5555}"
DIR="$(cd "$(dirname "$0")/bootagent" && pwd)"
APK="$DIR/openmiko-bootagent.apk"
ADB="${ADB:-/opt/homebrew/bin/adb}"

[ -f "$APK" ] || { echo "== APK missing, building =="; bash "$DIR/build.sh"; }

$ADB connect "$DEVICE" >/dev/null 2>&1 || true
echo "== installing com.openmiko.bootagent =="
$ADB -s "$DEVICE" install -r "$APK"

echo "== arming (am start clears Android's post-install 'stopped' state so BOOT_COMPLETED will fire) =="
# The arm runs the payload, which restarts adbd -> this adb link blips. Fire-and-forget, then reconnect to verify.
$ADB -s "$DEVICE" shell am start -n com.openmiko.bootagent/.MainActivity >/dev/null 2>&1 || true

echo "== reconnecting to verify =="
$ADB disconnect "$DEVICE" >/dev/null 2>&1 || true
ok=""
for _ in 1 2 3 4 5 6 7 8; do
  $ADB connect "$DEVICE" >/dev/null 2>&1 || true
  if $ADB -s "$DEVICE" shell true >/dev/null 2>&1; then ok=1; break; fi
done
[ -n "$ok" ] || { echo "!! adb did not come back — reconnect manually: $ADB connect $DEVICE"; exit 1; }

echo "== boot-agent log =="
$ADB -s "$DEVICE" shell "su 0 sh -c 'cat /data/local/tmp/openmiko-boot.log 2>/dev/null | tail -6; echo ---; getprop service.adb.tcp.port; getprop init.svc.adbd'"
echo
echo "DONE. Permanent adb is armed. FINAL confirmation = reboot the unit and re-run:"
echo "   $ADB connect $DEVICE   (should return within ~40s)"
echo "   $ADB -s $DEVICE shell su 0 cat /data/local/tmp/openmiko-boot.log   (expect a fresh 'reboot NEUTERED' + 'adb tcp 5555')"
