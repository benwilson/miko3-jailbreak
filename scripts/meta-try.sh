#!/usr/bin/env bash
#
# meta-try.sh — catch the preloader window and switch to a META mode.
#
# The script starts listening FIRST, then you power-cycle the robot.
# It catches the preloader during boot and sends the mode name.
#
# Usage:      scripts/meta-try.sh MODE_NAME [window_seconds]
# Example:    scripts/meta-try.sh FACTFACT
#
set -uo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
mode="${1:-}"
window="${2:-90}"

if [ -z "$mode" ]; then
  echo "usage: meta-try.sh MODE_NAME [window_seconds]" >&2
  echo "  MODE_NAME: FASTBOOT | FACTFACT | FACTORYM | METAMETA | ADVEMETA | AT+NBOOT" >&2
  exit 1
fi

ts="$(date -u +%Y%m%dT%H%M%SZ)"
log="$here/recon/captures/meta-try-$mode-$ts.txt"
mkdir -p "$here/recon/captures"

say() { printf '%s\n' "$*" | tee -a "$log"; }

pid_now() {
  ioreg -p IOUSB -w0 -l 2>/dev/null | awk 'BEGIN{RS="\\+-o "} \
    /"idVendor" = 3725|"idVendor" = 6353/ {
      match($0, /"idProduct" = [0-9]+/); pid = substr($0, RSTART, RLENGTH);
      gsub(/.*= /, "", pid); printf "0x%04x", pid + 0; exit }'
}

say "=== meta-try: $mode @ $(date -u +%FT%TZ) ==="
say ""

# Check if device is already connected
cur="$(pid_now)"
if [ -n "$cur" ]; then
  say "Device is currently PID=$cur — POWER CYCLE THE ROBOT NOW (off, then on)"
  say "Waiting for device to disappear..."
  deadline=$((SECONDS + 60))
  while [ "$SECONDS" -lt "$deadline" ]; do
    cur="$(pid_now)"
    if [ -z "$cur" ]; then
      say "Device gone at t+${SECONDS}s"
      break
    fi
    sleep 0.5
  done
  if [ -n "$cur" ]; then
    say "Device still present after 60s — is it turned off?"
  fi
else
  say "No device on USB. POWER CYCLE THE ROBOT NOW (off, then on)"
fi

say "Launching brom-probe --meta $mode (watching ${window}s for preloader)..."
say ""

probe_log="$here/recon/captures/meta-try-$mode-$ts.log"
"$here/scripts/brom-probe.sh" --no-reset --meta "$mode" --duration "$window" \
  > "$probe_log" 2>&1
rc=$?

say ""
say "brom-probe exited rc=$rc"
say ""
say "Key events from probe:"
grep -E "HANDSHAKE|after |stream=|fresh serial|stage:" "$probe_log" | tee -a "$log"

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
say "=== done ==="
say "full log: $probe_log"
