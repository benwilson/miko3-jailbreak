#!/usr/bin/env bash
# Snapshot USB + serial state so before/after power-on can be diffed.
# Usage: scripts/usb-snapshot.sh <label>     e.g. ./scripts/usb-snapshot.sh before-poweron
set -euo pipefail

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
