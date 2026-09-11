#!/usr/bin/env bash
#
# meta-sweep.sh — systematically probe every known META mode name.
# One mode per boot cycle. Safe: read-only handshake, nothing flashed.
#
# Usage:      scripts/meta-sweep.sh [window_seconds]     (default 90)
# Output:     recon/captures/meta-sweep-<utc>.txt
#
set -uo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
window="${1:-90}"
ts="$(date -u +%Y%m%dT%H%M%SZ)"
log="$here/recon/captures/meta-sweep-$ts.txt"
mkdir -p "$here/recon/captures"

say() { printf '%s\n' "$*" | tee -a "$log"; }

# All known META mode names from mtkclient Library/meta.py Mode enum
MODES=(
  "FACTFACT"
  "FACTORYM"
  "METAMETA"
  "ADVEMETA"
  "AT+NBOOT"
)

pid_now() {
  ioreg -p IOUSB -w0 -l 2>/dev/null | awk 'BEGIN{RS="\\+-o "} \
    /"idVendor" = 3725|"idVendor" = 6353/ {
      match($0, /"idProduct" = [0-9]+/); pid = substr($0, RSTART, RLENGTH);
      gsub(/.*= /, "", pid); printf "0x%04x", pid + 0; exit }'
}

say "=== meta-sweep @ $(date -u +%FT%TZ) ==="
say "Modes to probe: ${MODES[*]}"
say ""

for mode in "${MODES[@]}"; do
  say "──────────────────────────────────────────"
  say ">>> Probing META mode: $mode"
  say "──────────────────────────────────────────"
  
  # Check current state
  cur="$(pid_now)"
  say "current PID: $cur"
  
  # Launch probe
  probe_log="$here/recon/captures/meta-sweep-$mode-$ts.log"
  "$here/scripts/brom-probe.sh" --no-reset --meta "$mode" --duration "$window" \
    > "$probe_log" 2>&1
  rc=$?
  
  # Log the result
  say "brom-probe rc=$rc"
  say "probe output:"
  grep -E "HANDSHAKE|after |stream=|serial_hunt|stage:" "$probe_log" | head -20 | tee -a "$log"
  
  # Check what mode we landed in
  sleep 1
  cur="$(pid_now)"
  case "$cur" in
    0x201c) say "→ landed in FASTBOOT" ;;
    0x2000) say "→ landed in PRELOADER" ;;
    0x0003) say "→ landed in BROM" ;;
    0x2008) say "→ landed in ANDROID" ;;
    "")     say "→ device absent" ;;
    *)      say "→ landed in PID=$cur" ;;
  esac
  say ""
  
  # If we landed in fastboot or Android, tell user to power cycle
  if [ "$cur" = "0x201c" ] || [ "$cur" = "0x2008" ]; then
    say ">>> POWER CYCLE THE ROBOT NOW (off, then on) <<<"
    say "Waiting for device to go away and preloader to appear..."
    
    # Wait for device to disappear (user turning it off)
    deadline=$((SECONDS + 60))
    while [ "$SECONDS" -lt "$deadline" ]; do
      cur="$(pid_now)"
      if [ -z "$cur" ]; then
        say "device absent — waiting for preloader..."
        break
      fi
      sleep 0.5
    done
    
    # Wait for preloader to appear and pass (we'll catch it on next iteration)
    deadline=$((SECONDS + 90))
    while [ "$SECONDS" -lt "$deadline" ]; do
      cur="$(pid_now)"
      if [ "$cur" = "0x2008" ] || [ "$cur" = "0x201c" ]; then
        say "device back in $cur — ready for next mode"
        sleep 2
        break
      fi
      sleep 0.5
    done
  fi
done

say "=== sweep complete ==="
say "logs: $here/recon/captures/meta-sweep-*"
