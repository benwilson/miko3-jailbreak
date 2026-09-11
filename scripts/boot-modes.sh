#!/usr/bin/env bash
#
# boot-modes.sh — watch the boot ladder. The unit exposes a DIFFERENT USB mode at
# each stage of boot, and each stage has different abilities:
#
#   PID 0x0003  BROM        - boot ROM. Waits for the host, so mtkclient can always
#                             handshake here. Best entry point on a locked unit.
#   PID 0x2000  preloader   - brief window, boots on fast; mtkclient often misses it.
#   PID 0x2008  Android     - vendor interface 255/255/0 only. No adb iface. AOA HID
#                             input works here (see aoa-inject.sh).
#
# This script samples every 50 ms and logs each distinct mode with a ms timestamp,
# so the width of every window is on record instead of guessed.
#
# Usage:        scripts/boot-modes.sh [seconds] [interval_ms]
#               then power-cycle the unit while it runs.
# Example:      scripts/boot-modes.sh 60 50
#
set -uo pipefail
case "${1:-}" in -h|--help) sed -n '2,17p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;; esac

dur="${1:-60}"
iv_ms="${2:-50}"
here="$(cd "$(dirname "$0")/.." && pwd)"
log="$here/recon/captures/boot-modes-$(date -u +%Y%m%dT%H%M%SZ).txt"
mkdir -p "$here/recon/captures"

now_ms() {
  if command -v python3 >/dev/null 2>&1; then
    python3 -c 'import time;print(int(time.time()*1000))'
  else
    echo "$(( $(date +%s) * 1000 ))"
  fi
}

# One ioreg record per USB device -> "ProductName/pid" for the MediaTek VID.
probe() {
  ioreg -p IOUSB -w0 -l 2>/dev/null | awk 'BEGIN{RS="\\+-o "} /"idVendor" = 3725/ {
    match($0, /"idProduct" = [0-9]+/);       pid = substr($0, RSTART, RLENGTH);
    match($0, /"USB Product Name" = "[^"]*"/); nm = substr($0, RSTART, RLENGTH);
    gsub(/.*= /, "", pid); gsub(/.*= "/, "", nm); gsub(/".*/, "", nm);
    printf "%s/%s(0x%04x)", (nm == "" ? "unnamed" : nm), pid, pid+0 }'
}

t0="$(now_ms)"
echo "=== boot-mode ladder @ $(date -u +%FT%TZ), ${dur}s @ ${iv_ms}ms -> $log ===" | tee "$log"
echo "--- power-cycle the unit now ---" | tee -a "$log"

prev=""
end=$(( $(date +%s) + dur ))
while :; do
  cur="$(probe)"
  cur="${cur:-absent/0}"
  if [ "$cur" != "$prev" ]; then
    t="$(now_ms)"
    line="t+$(( t - t0 ))ms  $cur"
    adbout="$(adb devices 2>/dev/null | sed -n '2p')"
    [ -n "$adbout" ] && line="$line   adb:[$adbout]"
    echo "$line" | tee -a "$log"
    prev="$cur"
  fi
  : >/dev/null
  # millisecond-grained sleep
  if command -v python3 >/dev/null 2>&1; then
    python3 -c "import time;time.sleep($iv_ms/1000)"
  else
    sleep 1
  fi
  (( $(date +%s) > end )) && break
done
echo "=== done: $(grep -c '^t+' "$log") distinct modes, see $log ==="
