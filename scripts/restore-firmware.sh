#!/usr/bin/env bash
#
# restore-firmware.sh — restore Miko 3 partitions from dump.
#
# Usage:
#   scripts/restore-firmware.sh                    # list available backups
#   scripts/restore-firmware.sh boot               # restore boot partition
#   scripts/restore-firmware.sh recovery           # restore recovery
#   scripts/restore-firmware.sh --all              # restore ALL (DANGER)
#   scripts/restore-firmware.sh --check            # verify dumps vs device
#
# Restore requires root ADB or fastboot.
# Uses firmware/dump/ as source.
#
set -uo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
dumpdir="$here/firmware/dump"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'
say() { printf '%b%s%b\n' "$1" "$2" "$NC"; }
info() { say "$GREEN" "$*"; }
warn() { say "$YELLOW" "$*"; }
err()  { say "$RED" "$*"; }

# ── Partition name → block device mapping ─────────────────────
# These are from the GPT on our unit (MT8168, tb8168p1_64_bsp).
declare -A PART_MAP=(
  [proinfo]="mmcblk0p1"
  [boot_para]="mmcblk0p2"
  [cam_vpu1]="mmcblk0p3"
  [cam_vpu2]="mmcblk0p4"
  [cam_vpu3]="mmcblk0p5"
  [nvram]="mmcblk0p6"
  [protect1]="mmcblk0p7"
  [protect2]="mmcblk0p8"
  [persist]="mmcblk0p9"
  [nvcfg]="mmcblk0p10"
  [seccfg]="mmcblk0p11"
  [lk]="mmcblk0p12"
  [lk2]="mmcblk0p13"
  [boot]="mmcblk0p14"
  [recovery]="mmcblk0p15"
  [para]="mmcblk0p16"
  [logo]="mmcblk0p17"
  [dtbo]="mmcblk0p18"
  [expdb]="mmcblk0p19"
  [frp]="mmcblk0p20"
  [nvdata]="mmcblk0p21"
  [tee1]="mmcblk0p22"
  [tee2]="mmcblk0p23"
  [kb]="mmcblk0p24"
  [dkb]="mmcblk0p25"
  [metadata]="mmcblk0p26"
  [vbmeta]="mmcblk0p27"
  [system]="mmcblk0p28"
  [vendor]="mmcblk0p29"
  [cache]="mmcblk0p30"
  [userdata]="mmcblk0p31"
  [preloader]="mmcblk0boot0"
  [preloader2]="mmcblk0boot1"
)

# ── NEVER write these ──────────────────────────────────────────
NEVER_WRITE=("preloader" "preloader2" "mmcblk0boot0" "mmcblk0boot1")

# ── List backups ───────────────────────────────────────────────
list_backups() {
  echo "Available backups in $dumpdir/:"
  echo ""
  printf "%-16s %8s  %s\n" "PARTITION" "SIZE" "SHA-256"
  printf "%-16s %8s  %s\n" "---------" "----" "------"
  for img in "$dumpdir"/*.img; do
    [ -f "$img" ] || continue
    name=$(basename "$img" .img)
    size=$(ls -lh "$img" | awk '{print $5}')
    sha=$(shasum -a 256 "$img" 2>/dev/null | awk '{print substr($1,1,12)}')
    printf "%-16s %8s  %s...\n" "$name" "$size" "$sha"
  done
  echo ""
  echo "Restore with: scripts/restore-firmware.sh <name>"
}

# ── Check: compare dump hashes against device ──────────────────
check_partition() {
  local name="$1"
  local img="$dumpdir/${name}.img"
  local blk="${PART_MAP[$name]:-}"
  
  if [ -z "$blk" ]; then
    err "  $name: unknown block device"
    return 1
  fi
  if [ ! -f "$img" ]; then
    warn "  $name: no backup file"
    return 1
  fi
  
  local dump_hash
  dump_hash=$(dd if="$img" bs=4096 2>/dev/null | md5sum | awk '{print $1}')
  
  local dev_hash
  dev_hash=$(adb shell "dd if=/dev/block/$blk bs=4096 2>/dev/null | md5sum" 2>/dev/null | awk '{print $1}')
  
  if [ -z "$dev_hash" ]; then
    err "  $name: cannot read device (ADB not available?)"
    return 1
  fi
  
  if [ "$dump_hash" = "$dev_hash" ]; then
    info "  $name: MATCH"
    return 0
  else
    err "  $name: MISMATCH (dump=$dump_hash dev=$dev_hash)"
    return 1
  fi
}

# ── Restore one partition ──────────────────────────────────────
restore_partition() {
  local name="$1"
  local img="$dumpdir/${name}.img"
  local blk="${PART_MAP[$name]:-}"
  
  if [ -z "$blk" ]; then
    err "Unknown partition: $name"
    return 1
  fi
  
  # Safety check: never write preloader
  for n in "${NEVER_WRITE[@]}"; do
    if [ "$name" = "$n" ] || [ "$blk" = "$n" ]; then
      err "REFUSING to write $name ($blk) — hard brick risk!"
      err "This partition must be restored via mtkclient BROM mode only."
      return 1
    fi
  done
  
  if [ ! -f "$img" ]; then
    err "No backup for $name at $img"
    return 1
  fi
  
  local imgsize=$(ls -l "$img" | awk '{print $5}')
  local blksize=$(adb shell "cat /sys/block/${blk%p*}/size 2>/dev/null" 2>/dev/null)
  
  warn "About to restore $name ($blk, ${imgsize} bytes)"
  warn "This will OVERWRITE the partition on the device."
  warn "Proceed? [y/N] "
  read -r answer
  if [ "$answer" != "y" ] && [ "$answer" != "Y" ]; then
    info "Aborted."
    return 0
  fi
  
  info "Pushing $name image to device..."
  if ! adb push "$img" "/data/local/tmp/restore_${name}.img" 2>/dev/null; then
    # Push might fail for large files; try piping
    info "Push failed, trying pipe method..."
    cat "$img" | adb shell "dd of=/dev/block/$blk bs=4096 2>&1"
  else
    adb shell "dd if=/data/local/tmp/restore_${name}.img of=/dev/block/$blk bs=4096 2>&1"
    adb shell "rm /data/local/tmp/restore_${name}.img 2>/dev/null"
  fi
  
  info "Restore of $name complete."
  warn "Verify with: scripts/restore-firmware.sh --check"
}

# ── Main ───────────────────────────────────────────────────────
action="${1:-list}"

case "$action" in
  list|"")
    list_backups
    ;;
  --check)
    info "Verifying dumps against device..."
    adb shell "echo ok" 2>/dev/null || { err "ADB not available."; exit 1; }
    ok=0; fail=0
    for name in "${!PART_MAP[@]}"; do
      [ -f "$dumpdir/${name}.img" ] || continue
      if check_partition "$name"; then
        ((ok++))
      else
        ((fail++))
      fi
    done
    echo ""
    info "$ok match, $fail mismatch"
    ;;
  --all)
    err "DANGER: --all will overwrite every partition."
    err "This should only be done if the device is unbootable."
    err "Type 'YES' to confirm: "
    read -r answer
    if [ "$answer" != "YES" ]; then
      info "Aborted."
      exit 0
    fi
    for name in "${!PART_MAP[@]}"; do
      [ -f "$dumpdir/${name}.img" ] || continue
      restore_partition "$name"
    done
    ;;
  *)
    if [ -f "$dumpdir/${action}.img" ]; then
      restore_partition "$action"
    else
      err "No backup for '$action'. Available:"
      list_backups
      exit 1
    fi
    ;;
esac
