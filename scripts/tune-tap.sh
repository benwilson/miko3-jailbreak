#!/usr/bin/env bash
#
# tune-tap.sh — find which injected burst actually reaches Developer options, with
# no human watching the display.
#
# The trick is that the check does not need eyes. Enabling USB debugging makes
# Android re-compute its USB configuration, so the composite re-enumerates: the
# interface count under the device goes up and `adb devices` stops being empty.
# That is observable from the host alone, so each candidate burst can score itself.
#
# Usage:        scripts/tune-tap.sh [settle_seconds]
# Example:      scripts/tune-tap.sh 4
# Logs to:      recon/captures/tune-tap-<utc>.txt
#
set -uo pipefail
case "${1:-}" in -h|--help) sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;; esac

here="$(cd "$(dirname "$0")/.." && pwd)"
settle="${1:-4}"
log="$here/recon/captures/tune-tap-$(date -u +%Y%m%dT%H%M%SZ).txt"
mkdir -p "$here/recon/captures"

inj=("$here/scripts/aoa-inject.sh")

iface_count() {
  ioreg -p IOUSB -w0 -l -r -n MIKO3 2>/dev/null | grep -c '+-o'
}

state() {
  local adb_ifaces
  adb_ifaces="$(adb devices 2>/dev/null | sed -n '2,$p' | grep -c . )"
  printf 'ifaces=%s adb=%s' "$(iface_count)" "${adb_ifaces:-0}"
}

say() { printf '%s\n' "$*" | tee -a "$log"; }

say "=== tune-tap @ $(date -u +%FT%TZ), settle ${settle}s ==="
base="$(state)"
say "baseline: $base"
say "(success = the state changes from this after a burst)"

# Each entry: label | burst steps. Order is best-bet first.
cands=(
  "bottom-right-corner|meta+n wait:350 move:right move:down click"
  "bottom-right inset 30|meta+n wait:350 move:right move:down nudge:0,-30 click"
  "bottom-right inset 60|meta+n wait:350 move:right move:down nudge:0,-60 click"
  "bottom-right inset diag|meta+n wait:350 move:right move:down nudge:-25,-25 click"
  "right-middle|meta+n wait:350 move:right click"
  "bottom-left|meta+n wait:350 move:left move:down click"
  "kbd tab enter|meta+n wait:350 tab wait:150 enter"
  "kbd pgdn tab enter|meta+n wait:350 pgdn wait:200 tab wait:150 enter"
  "kbd down enter|meta+n wait:350 down wait:150 enter"
)

for entry in "${cands[@]}"; do
  label="${entry%%|*}"
  steps="${entry#*|}"
  # shellcheck disable=SC2086
  "${inj[@]}" --sequence $steps --interval-ms 50 --duration 8 >/dev/null 2>&1
  sleep "$settle"
  cur="$(state)"
  if [ "$cur" != "$base" ]; then
    say "PASS  $label  ->  $cur   [$steps]"
    say "=== winner: $label ==="
    exit 0
  fi
  say "fail  $label  ->  $cur"
done

say "=== no candidate changed the USB state; last state $(state) ==="
exit 1
