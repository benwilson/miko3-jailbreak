#!/usr/bin/env bash
#
# tune-tap.sh — find which injected burst actually reaches Developer options, with
# no human watching the display.
#
# The trick is that the check does not need eyes. Enabling USB debugging changes the
# interface descriptors the composite exposes: the plain kiosk gadget is a single
# FF/FF/00 vendor interface, and adb shows up as FF/42/01. Reading that triplet from
# the host is enough to score each candidate burst without watching the display.
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

iface_state() {
  "$here/scripts/aoa-inject.sh" --ifaces 2>/dev/null | tail -n +2 \
    | sed -E 's/.*: //' | paste -sd, -
}

state() {
  local adb_ifaces
  adb_ifaces="$(adb devices 2>/dev/null | sed -n '2,$p' | grep -c . )"
  printf 'ifaces=%s adb=%s' "$(iface_state)" "${adb_ifaces:-0}"
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
