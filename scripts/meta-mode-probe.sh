#!/usr/bin/env bash
#
# meta-mode-probe.sh — try a specific META mode name and log what happens.
#
# Usage:      scripts/meta-mode-probe.sh MODE_NAME [window_seconds]
# Example:    scripts/meta-mode-probe.sh DOWNLOAD
#             scripts/meta-mode-probe.sh FACTORYM
#
# Output:     recon/captures/meta-mode-<MODE>-<utc>.txt
#
set -uo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
mode="${1:-}"
window="${2:-90}"

if [ -z "$mode" ]; then
  echo "usage: meta-mode-probe.sh MODE_NAME [window_seconds]" >&2
  exit 1
fi

ts="$(date -u +%Y%m%dT%H%M%SZ)"
log="$here/recon/captures/meta-mode-$mode-$ts.txt"
mkdir -p "$here/recon/captures"

say() { printf '%s\n' "$*" | tee -a "$log"; }

# ── USB watcher: log every PID change with timestamp ───────────
watch_pids() {
  local deadline=$1
  local last=""
  while [ "$SECONDS" -lt "$deadline" ]; do
    local cur
    cur=$(ioreg -p IOUSB -w0 -l 2>/dev/null | awk 'BEGIN{RS="\\+-o "} \
      /"idVendor" = 3725|"idVendor" = 6353/ {
        match($0, /"idProduct" = [0-9]+/); pid = substr($0, RSTART, RLENGTH);
        gsub(/.*= /, "", pid); printf "0x%04x", pid + 0; exit }')
    if [ "$cur" != "$last" ]; then
      local name=""
      case "$cur" in
        0x2008) name="Android" ;;
        0x2000) name="preloader" ;;
        0x0003) name="BROM" ;;
        0x201c) name="fastboot" ;;
        0x2001) name="download?" ;;
        "")     name="absent" ;;
      esac
      say "t+${SECONDS}s  PID=$cur  ($name)"
      last="$cur"
    fi
    sleep 0.15
  done
}

say "=== meta-mode-probe: $mode @ $(date -u +%FT%TZ) ==="
say ""

# Start PID watcher in background
deadline=$((SECONDS + window + 15))
watch_pids "$deadline" &
watcher=$!

# Give watcher a moment to start
sleep 0.2

# Launch brom-probe with the requested mode
say "--- launching brom-probe --meta $mode ---"
"$here/scripts/brom-probe.sh" --no-reset --meta "$mode" --duration "$window" \
  > "$log.probe" 2>&1
probe_rc=$?
say "brom-probe exited rc=$probe_rc"

# If mode was DOWNLOAD, try mtkclient
if [ "$mode" = "DOWNLOAD" ] && [ "$probe_rc" -eq 0 ]; then
  say ""
  say "--- DOWNLOAD mode requested; waiting for device to settle ---"
  sleep 2
  
  # Check what's on the bus
  say "current USB state:"
  ioreg -p IOUSB -w0 -l 2>/dev/null | grep -E '"idVendor"|"idProduct"|"USB Product Name"' | head -20 | tee -a "$log"
  
  say ""
  say "--- attempting mtkclient printgpt ---"
  "$here/scripts/mtk.sh" printgpt 2>&1 | tee -a "$log" || say "mtkclient failed (expected on macOS)"
fi

# Wait for watcher to finish
wait "$watcher" 2>/dev/null

say ""
say "=== probe complete ==="
say "log: $log"
say "probe log: $log.probe"
