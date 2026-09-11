#!/usr/bin/env bash
#
# usb-snapshot.sh — snapshot USB + serial state to a file for before/after diffs.
#
# Usage:   scripts/usb-snapshot.sh [label]
# Example: scripts/usb-snapshot.sh before-poweron
#          scripts/usb-snapshot.sh after-poweron
#          diff recon/captures/usb-before-poweron.txt recon/captures/usb-after-poweron.txt
#
# Output:  recon/captures/usb-<label>.txt   (label defaults to a timestamp)
#
# Dependencies: macOS built-ins (ioreg, ls); adb + fastboot optional (Homebrew
#   `android-platform-tools`) — reported as "(not installed)" if absent.
#
set -euo pipefail

case "${1:-}" in
  -h|--help) sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
esac

command -v ioreg >/dev/null || { echo "error: ioreg not found (are you on macOS?)" >&2; exit 1; }

label="${1:-$(date +%Y%m%d-%H%M%S)}"
outdir="recon/captures"
mkdir -p "$outdir"
out="$outdir/usb-$label.txt"

{
  echo "### snapshot: $label"
  echo "### date: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo
  echo "=== /dev serial nodes ==="
  ls -la /dev/cu.* /dev/tty.* 2>/dev/null || echo "(none)"
  echo
  echo "=== USB devices (VID/PID) ==="
  ioreg -p IOUSB -w0 -l 2>/dev/null \
    | grep -iE '\+-o |"idVendor"|"idProduct"|"USB Vendor Name"|"USB Product Name"|"USB Serial Number"|"bcdDevice"' \
    || echo "(none)"
  echo
  echo "=== adb ==="
  if command -v adb >/dev/null 2>&1; then adb devices -l 2>&1; else echo "(adb not installed)"; fi
  echo
  echo "=== fastboot ==="
  if command -v fastboot >/dev/null 2>&1; then timeout 5 fastboot devices 2>&1 || echo "(no fastboot devices)"; else echo "(fastboot not installed)"; fi
} > "$out"

echo "wrote $out"
