#!/usr/bin/env bash
#
# miko-detect.sh — one-shot: is the Miko visible, and in what mode?
#
# Checks every transport at once and prints a plain-English verdict:
#   normal Android USB gadget / ADB / fastboot / MediaTek preloader / BROM / nothing.
#
# Usage:        scripts/miko-detect.sh
# Dependencies: macOS ioreg (built-in); adb + fastboot optional.
#
set -uo pipefail
case "${1:-}" in -h|--help) sed -n '2,11p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;; esac

MTK_VID="0x0e8d"   # MediaTek

hex(){ printf '0x%04x' "$1"; }
usb_line(){ ioreg -p IOUSB -w0 -l 2>/dev/null | grep -iE '"idVendor"|"idProduct"|"USB Product Name"' | paste - - - ; }

echo "=== MIKO detect @ $(date -u +%FT%TZ) ==="

# MediaTek device present?
# One ioreg record per USB device: split the tree on "+-o " and keep the record
# that carries MediaTek's VID. Using -r -n MIKO3 alone is not enough, because in
# preloader/BROM mode the product name is not "MIKO3".
mtk="$(ioreg -p IOUSB -w0 -l 2>/dev/null | awk 'BEGIN{RS="\\+-o "} /"idVendor" = 3725/ {
  match($0, /"idProduct" = [0-9]+/);  pid = substr($0, RSTART, RLENGTH);
  match($0, /"USB Product Name" = "[^"]*"/); nm = substr($0, RSTART, RLENGTH);
  split($0, a, "\n"); print a[1]; print pid; print nm }')"
if [ -n "$mtk" ]; then
  pid_dec="$(printf '%s' "$mtk" | grep '"idProduct"' | head -1 | grep -oE '[0-9]+')"
  pid="$(hex "${pid_dec:-0}")"
  name="$(printf '%s' "$mtk" | grep '"USB Product Name"' | head -1 | sed -E 's/.*= "?([^"]*)"?/\1/')"
  echo "MediaTek device present: name='${name:-?}' VID=0x0e8d PID=${pid}"
  case "$pid" in
    0x0003) echo "  -> MODE: BROM (boot ROM). mtkclient can talk directly. BEST for locked units." ;;
    0x2000|0x2001|0x2003) echo "  -> MODE: PRELOADER. mtkclient can talk (may need matching DA)." ;;
    0x2008) echo "  -> MODE: normal Android gadget (vendor iface 255/255/0, no adb iface)."
            echo "     Inject the Settings ladder over AOA HID: ./scripts/aoa-inject.sh --ladder" ;;
    *)      echo "  -> MODE: unknown MediaTek PID ${pid}; try mtkclient anyway." ;;
  esac
else
  echo "MediaTek device: not present"
fi

# ADB
if command -v adb >/dev/null 2>&1; then
  a="$(adb devices -l 2>/dev/null | sed '1d;/^$/d')"
  [ -n "$a" ] && echo "ADB: $a" || echo "ADB: none (USB debugging off, or device not in Android/adb mode)"
else echo "ADB: adb not installed"; fi

# fastboot
if command -v fastboot >/dev/null 2>&1; then
  f="$(timeout 4 fastboot devices 2>/dev/null)"
  [ -n "$f" ] && echo "fastboot: $f" || echo "fastboot: none"
else echo "fastboot: not installed"; fi

# Is something (e.g. Chrome WebUSB) holding the device?
holder="$(ioreg -w0 -l -r -n MIKO3 2>/dev/null | grep -i 'AppleUSBHostDeviceUserClient' | grep -oiE '"[^"]+"  <class' | head -1)"
[ -n "$holder" ] && echo "NOTE: a userspace process holds the USB device (e.g. Chrome WebUSB) — this blocks adb/mtkclient. Close it."

echo "--- full USB VID/PID/name table ---"; usb_line
