#!/usr/bin/env bash
#
# fastboot-once.sh — drive one Miko 3 boot into fastboot and read it, in a single
# bounded run, so the whole cycle fits in one terminal session.
#
# Why this exists:
#   This unit's buttons do not hold download mode, and the kiosk UI has no path to
#   Developer options. What does work is the MediaTek preloader's META port: during
#   the boot window the preloader exposes CDC-ACM, answers our SYNC with ASCII
#   "READY", and accepts a mode name — "FASTBOOT" hands the unit to fastboot.
#   See scripts/brom-probe.py for the handshake itself.
#
# One catch worth knowing: the first fastboot command after the mode switch answers
# promptly, later ones can stall. So this script waits for the fastboot PID and then
# issues a single combined read.
#
# Usage:      scripts/fastboot-once.sh [window_seconds]     (default 90)
# Logs to:    recon/captures/fastboot-once-<utc>.txt
# Deps:       scripts/brom-probe.sh, ioreg, fastboot
#
set -uo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
window="${1:-90}"
log="$here/recon/captures/fastboot-once-$(date -u +%Y%m%dT%H%M%SZ).txt"
mkdir -p "$here/recon/captures"

say() { printf '%s\n' "$*" | tee -a "$log"; }

pid_now() {
  ioreg -p IOUSB -w0 -l 2>/dev/null | awk 'BEGIN{RS="\\+-o "} \
    /"idVendor" = 3725|"idVendor" = 6353/ {
      match($0, /"idProduct" = [0-9]+/); pid = substr($0, RSTART, RLENGTH);
      gsub(/.*= /, "", pid); printf "0x%04x", pid + 0; exit }'
}

say "=== fastboot-once @ $(date -u +%FT%TZ), window ${window}s ==="

"$here/scripts/brom-probe.sh" --no-reset --meta FASTBOOT --duration "$window" \
  > "$log.probe" 2>&1 &
probe=$!

deadline=$((SECONDS + window))
seen=""
while [ "$SECONDS" -lt "$deadline" ]; do
  cur="$(pid_now)"
  [ "$cur" != "$seen" ] && say "t+${SECONDS}s  pid=$cur" && seen="$cur"
  # 0x201c = fastboot gadget (interface ff/42/03)
  if [ "$cur" = "0x201c" ]; then break; fi
  sleep 0.2
done

if [ "$seen" = "0x201c" ]; then
  say "fastboot interface up after ${SECONDS}s — reading immediately"
  timeout 30 fastboot devices 2>&1 | tee -a "$log"
  timeout 30 fastboot getvar all 2>&1 | tee -a "$log"
  rc=$?
else
  say "no fastboot PID (0x201c) seen within ${window}s — last pid=$seen"
  rc=1
fi

kill "$probe" 2>/dev/null
say "probe output: $(wc -l < "$log.probe" 2>/dev/null) lines in $log.probe"
say "=== done rc=$rc ==="
exit "$rc"
