#!/usr/bin/env bash
#
# dump-all.sh — full "parental locked" (working kiosk) dump of the Miko 3.
#
# Streams EVERY GPT partition plus the hidden preloader regions straight to the
# host (no device storage used), then writes a SHA-256 manifest.
#
# Requires: root ADB (factory mode). Run scripts/factory-root.sh first.
#
# Usage: scripts/dump-all.sh [outdir]
#        default outdir: firmware/dump-parental-locked
#
set -uo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
outdir="${1:-$here/firmware/dump-parental-locked}"
mkdir -p "$outdir"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
say() { printf '%b%s%b\n' "$1" "$2" "$NC"; }
info() { say "$GREEN" "$*"; }
warn() { say "$YELLOW" "$*"; }
err()  { say "$RED" "$*"; }

adb shell "id" 2>/dev/null | grep -q 'uid=0' || { err "Need root ADB (run scripts/factory-root.sh first)"; exit 1; }
info "Root ADB confirmed."

# Make sure busybox is available for a dd that supports large bs
adb shell '[ -x /data/local/tmp/busybox ] || cp /storage/emulated/0/klug/busybox /data/local/tmp/busybox && chmod 755 /data/local/tmp/busybox' 2>/dev/null
BB=/data/local/tmp/busybox

# name:block mapping (partition name -> /dev/block node)
PARTITIONS=(
  "proinfo:mmcblk0p1"       "boot_para:mmcblk0p2"
  "cam_vpu1:mmcblk0p3"      "cam_vpu2:mmcblk0p4"      "cam_vpu3:mmcblk0p5"
  "nvram:mmcblk0p6"         "protect1:mmcblk0p7"      "protect2:mmcblk0p8"
  "persist:mmcblk0p9"       "nvcfg:mmcblk0p10"        "seccfg:mmcblk0p11"
  "lk:mmcblk0p12"           "lk2:mmcblk0p13"          "boot:mmcblk0p14"
  "recovery:mmcblk0p15"     "para:mmcblk0p16"         "logo:mmcblk0p17"
  "dtbo:mmcblk0p18"         "expdb:mmcblk0p19"        "frp:mmcblk0p20"
  "nvdata:mmcblk0p21"       "tee1:mmcblk0p22"         "tee2:mmcblk0p23"
  "kb:mmcblk0p24"           "dkb:mmcblk0p25"          "metadata:mmcblk0p26"
  "vbmeta:mmcblk0p27"       "system:mmcblk0p28"       "vendor:mmcblk0p29"
  "cache:mmcblk0p30"        "userdata:mmcblk0p31"
  "preloader:mmcblk0boot0"  "preloader2:mmcblk0boot1"
)

log="$outdir/DUMP-LOG.txt"
: > "$log"
say2() { printf '%s\n' "$*" | tee -a "$log"; }

say2 "=== Miko 3 full dump (parental-locked state) ==="
say2 "date: $(date -u +%FT%TZ)"
say2 "outdir: $outdir"
say2 ""

# GPT header (first 34 sectors) for a complete partition-table record
info "dumping gpt_header ..."
adb exec-out "$BB dd if=/dev/block/mmcblk0 bs=512 count=34 2>/dev/null" > "$outdir/gpt_header.img"
say2 "gpt_header.img $(stat -f%z "$outdir/gpt_header.img" 2>/dev/null || stat -c%s "$outdir/gpt_header.img")"

total=${#PARTITIONS[@]}
i=0
for entry in "${PARTITIONS[@]}"; do
  name="${entry%%:*}"; blk="${entry##*:}"
  i=$((i+1))
  info "[$i/$total] $name ($blk) ..."
  # Stream straight to host. busybox dd handles bs=1M.
  if adb exec-out "$BB dd if=/dev/block/$blk bs=1M 2>/dev/null" > "$outdir/$name.img"; then
    sz=$(stat -f%z "$outdir/$name.img" 2>/dev/null || stat -c%s "$outdir/$name.img")
    say2 "$name.img  bytes=$sz  src=/dev/block/$blk"
  else
    err "  FAILED: $name ($blk)"
    say2 "$name.img  FAILED  src=/dev/block/$blk"
    rm -f "$outdir/$name.img"
  fi
done

# ── Manifest ────────────────────────────────────────────────────
info "computing SHA-256 manifest ..."
manifest="$outdir/SHA256SUMS"
: > "$manifest"
for f in "$outdir"/*.img; do
  [ -f "$f" ] || continue
  shasum -a 256 "$f" >> "$manifest"
done

say2 ""
say2 "=== manifest ==="
cat "$manifest" | tee -a "$log"
say2 ""
info "Dump complete: $outdir"
info "Manifest:      $manifest"
