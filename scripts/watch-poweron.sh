#!/usr/bin/env bash
# Watch for the Miko appearing on USB after power-on. Exits as soon as something
# new shows up (a new /dev/cu serial node, an adb device, or a fastboot device),
# or after the timeout. Logs everything with timestamps.
set -uo pipefail
log="recon/captures/poweron-watch.log"
timeout_s="${1:-180}"

ts(){ date -u +%H:%M:%S; }
snap_nodes(){ ls /dev/cu.* 2>/dev/null | sort; }

adb start-server >/dev/null 2>&1 || true
base="$(snap_nodes)"
echo "=== watch start $(date -u +%FT%TZ), timeout ${timeout_s}s ===" | tee "$log"
echo "baseline serial nodes:" | tee -a "$log"; echo "$base" | tee -a "$log"
echo "--- power on the unit now ---" | tee -a "$log"

start=$(date +%s)
while :; do
  now=$(date +%s); (( now-start > timeout_s )) && { echo "[$(ts)] TIMEOUT, nothing new" | tee -a "$log"; exit 2; }

  cur="$(snap_nodes)"
  newnodes="$(comm -13 <(echo "$base") <(echo "$cur"))"
  if [ -n "$newnodes" ]; then
    echo "[$(ts)] NEW SERIAL NODE(S):" | tee -a "$log"; echo "$newnodes" | tee -a "$log"
    # capture the matching USB descriptor
    ioreg -p IOUSB -w0 -l 2>/dev/null | grep -iE '\+-o |idVendor|idProduct|USB Product Name|USB Vendor Name|USB Serial|bcdDevice' | tee -a "$log"
    echo "RESULT=serial" | tee -a "$log"; exit 0
  fi

  adbout="$(adb devices -l 2>/dev/null | sed '1d;/^$/d')"
  if [ -n "$adbout" ]; then
    echo "[$(ts)] ADB DEVICE:" | tee -a "$log"; echo "$adbout" | tee -a "$log"
    echo "RESULT=adb" | tee -a "$log"; exit 0
  fi

  fbout="$(fastboot devices 2>/dev/null)"
  if [ -n "$fbout" ]; then
    echo "[$(ts)] FASTBOOT DEVICE:" | tee -a "$log"; echo "$fbout" | tee -a "$log"
    echo "RESULT=fastboot" | tee -a "$log"; exit 0
  fi
  sleep 1
done
