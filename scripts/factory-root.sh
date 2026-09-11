#!/usr/bin/env bash
#
# factory-root.sh — one-command root shell on the Miko 3.
#
# What it does:
#   1. Watches for the MediaTek preloader during boot
#   2. Sends the META FACTFACT handshake to enter factory mode
#   3. Factory mode boots with root ADB, no watchdog, no kiosk
#   4. Drops you into an ADB root shell
#
# Usage:
#   scripts/factory-root.sh              # guided: tells you when to power-cycle
#   scripts/factory-root.sh --shell      # auto: waits for ADB then gives shell
#   scripts/factory-root.sh --dump       # auto: dumps all partitions to firmware/dump/
#
# You need: a USB cable, the robot, and one power-cycle per run.
#
set -uo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
ts="$(date -u +%Y%m%dT%H%M%SZ)"
log="$here/recon/captures/factory-root-$ts.log"
mkdir -p "$here/recon/captures"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

say() { printf '%b%s%b\n' "$1" "$2" "$NC"; }
info() { say "$GREEN" "$*"; }
warn() { say "$YELLOW" "$*"; }
err()  { say "$RED" "$*"; }

pid_now() {
  ioreg -p IOUSB -w0 -l 2>/dev/null | awk 'BEGIN{RS="\\+-o "} \
    /"idVendor" = 3725|"idVendor" = 6353/ {
      match($0, /"idProduct" = [0-9]+/); pid = substr($0, RSTART, RLENGTH);
      gsub(/.*= /, "", pid); printf "0x%04x", pid + 0; exit }'
}

# ── Parse args ─────────────────────────────────────────────────
mode="guided"
dump=0
while [ $# -gt 0 ]; do
  case "$1" in
    --shell) mode="shell" ;;
    --dump)  mode="shell"; dump=1 ;;
    --guided) mode="guided" ;;
    *) err "unknown flag: $1"; exit 1 ;;
  esac
  shift
done

# ── Guided mode: tell user what to do ──────────────────────────
if [ "$mode" = "guided" ]; then
  echo ""
  info "=== Miko 3 Factory Root ==="
  echo ""
  warn "Plug the USB cable into the Miko's hidden data port."
  warn "(Bottom screws → lift top → micro-USB port inside)"
  echo ""
  
  cur="$(pid_now)"
  if [ -n "$cur" ]; then
    warn "Device is currently PID=$cur."
    warn "POWER CYCLE THE ROBOT NOW: turn it off, then back on."
    warn "Waiting for device to disappear..."
    for i in $(seq 1 60); do
      cur="$(pid_now)"
      [ -z "$cur" ] && break
      sleep 1
    done
    if [ -n "$cur" ]; then
      err "Device still present after 60s. Is it turned off?"
      err "Unplug USB, turn off robot, plug USB back in, then re-run."
      exit 1
    fi
  else
    warn "No device detected. POWER CYCLE THE ROBOT NOW (off, then on)."
  fi
  
  info "Starting preloader watcher..."
  info "The robot should boot into factory mode within ~30 seconds."
  echo ""
fi

# ── Core: catch preloader, send FACTFACT ───────────────────────
info "Watching for preloader (90s window)..."

"$here/scripts/brom-probe.sh" --no-reset --meta FACTFACT --duration 90 \
  > "$log" 2>&1
probe_rc=$?

if [ "$probe_rc" -ne 0 ]; then
  # Check if it landed in fastboot instead
  cur="$(pid_now)"
  if [ "$cur" = "0x201c" ]; then
    warn "Landed in fastboot instead of factory mode."
    warn "Power cycle and re-run. If this repeats, try: scripts/meta-try.sh FASTBOOT"
  else
    err "Probe failed (rc=$probe_rc). See: $log"
    err "Power cycle and try again."
  fi
  exit 1
fi

# ── Wait for ADB ───────────────────────────────────────────────
info "Preloader handshake sent. Waiting for ADB..."

deadline=$((SECONDS + 120))
adb_up=0
while [ "$SECONDS" -lt "$deadline" ]; do
  if adb devices 2>/dev/null | grep -q 'MIKO.*device'; then
    adb_up=1
    break
  fi
  sleep 1
done

if [ "$adb_up" -eq 0 ]; then
  # Maybe the device booted to Android instead of factory mode
  cur="$(pid_now)"
  err "ADB did not appear within 120s. Current PID: $cur"
  err "Try power-cycling and re-running."
  exit 1
fi

info "ADB is up!"

# ── Verify root ─────────────────────────────────────────────────
root_check=$(timeout 10 adb shell "id -u" 2>/dev/null)
if [ "$root_check" != "0" ]; then
  err "ADB is up but not root (uid=$root_check). Something changed."
  exit 1
fi
info "Root confirmed (uid=0)."

# ── Disable watchdog (first time only) ──────────────────────────
warn "Disabling watchdog..."
adb shell "mv /data/app/com.example.root.serviceexam-* /data/app/com.example.root.serviceexam-disabled 2>/dev/null" 2>/dev/null
info "Watchdog disabled."

# ── Optional: dump firmware ────────────────────────────────────
if [ "$dump" -eq 1 ]; then
  info "Dumping firmware to firmware/dump/ ..."
  dumpdir="$here/firmware/dump"
  mkdir -p "$dumpdir"
  
  # Small critical partitions
  for part in seccfg lk lk2 boot recovery vbmeta preloader proinfo \
              nvram nvdata nvcfg persist frp para logo dtbo tee1 tee2; do
    blk="$(adb shell "readlink /dev/block/platform/*/by-name/$part 2>/dev/null" 2>/dev/null | head -1)"
    if [ -n "$blk" ]; then
      info "  pulling $part..."
      adb pull "$blk" "$dumpdir/${part}.img" 2>/dev/null
    fi
  done
  
  # Preloader from mmcblk0boot0
  adb shell "dd if=/dev/block/mmcblk0boot0 of=/data/local/tmp/preloader.img bs=4096 2>/dev/null" 2>/dev/null
  adb pull /data/local/tmp/preloader.img "$dumpdir/preloader.img" 2>/dev/null
  
  info "Firmware dumped to $dumpdir/"
fi

# ── Done ────────────────────────────────────────────────────────
echo ""
info "=== Factory root active ==="
info "You have a root ADB shell. The watchdog is disabled."
info ""
info "To connect:  adb shell"
info "To dump:     scripts/factory-root.sh --dump"
info "To re-enter: scripts/factory-root.sh  (after power-cycle)"
info ""
info "IMPORTANT: This factory mode session will survive until"
info "the next power-cycle. To make ADB permanent across normal"
info "boots, the watchdog must stay disabled (done above)."
echo ""

# Drop to shell if requested
if [ "$mode" = "shell" ]; then
  exec adb shell
fi
