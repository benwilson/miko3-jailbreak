#!/usr/bin/env bash
#
# fastboot-recon.sh — exhaustive safe (read-only) fastboot probe.
#
# Drives one boot cycle: catches the preloader META window, switches to FASTBOOT,
# then runs every informational getvar + OEM command this bootloader might answer.
# Nothing is flashed, nothing is erased — entirely read-only.
#
# Usage:      scripts/fastboot-recon.sh [window_seconds]     (default 120)
# Output:     recon/captures/fastboot-recon-<utc>.txt
# Deps:       scripts/brom-probe.sh, fastboot, ioreg
#
set -uo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
window="${1:-120}"
ts="$(date -u +%Y%m%dT%H%M%SZ)"
log="$here/recon/captures/fastboot-recon-$ts.txt"
mkdir -p "$here/recon/captures"

say() { printf '%s\n' "$*" | tee -a "$log"; }
banner() { say ""; say "=== $* ==="; say ""; }

# ── USB PID watcher ──────────────────────────────────────────────
pid_now() {
  ioreg -p IOUSB -w0 -l 2>/dev/null | awk 'BEGIN{RS="\\+-o "} \
    /"idVendor" = 3725|"idVendor" = 6353/ {
      match($0, /"idProduct" = [0-9]+/); pid = substr($0, RSTART, RLENGTH);
      gsub(/.*= /, "", pid); printf "0x%04x", pid + 0; exit }'
}

# ── Run a fastboot command with timeout ─────────────────────────
fb() {
  local desc="$1"; shift
  say "  CMD: fastboot $*"
  local out
  if out="$(timeout 15 fastboot "$@" 2>&1)"; then
    say "  OK:  $out"
  else
    local rc=$?
    if [ $rc -eq 124 ]; then
      say "  TIMEOUT (15s)"
    else
      say "  ERR (rc=$rc): $out"
    fi
  fi
  say ""
}

# ── Header ──────────────────────────────────────────────────────
banner "fastboot-recon @ $(date -u +%FT%TZ) — window=${window}s"

say "fastboot version: $(fastboot --version 2>&1 | head -1)"
say ""

# ── Phase A: launch the preloader probe ─────────────────────────
say "--- launching brom-probe (META -> FASTBOOT) ---"
"$here/scripts/brom-probe.sh" --no-reset --meta FASTBOOT --duration "$window" \
  > "$log.probe" 2>&1 &
probe=$!
say "brom-probe pid=$probe"

# ── Phase B: wait for fastboot PID (0x201c) ─────────────────────
deadline=$((SECONDS + window))
seen=""
fb_up=0
while [ "$SECONDS" -lt "$deadline" ]; do
  cur="$(pid_now)"
  if [ "$cur" != "$seen" ]; then
    say "t+${SECONDS}s  pid=$cur"
    seen="$cur"
  fi
  if [ "$cur" = "0x201c" ]; then
    fb_up=1
    break
  fi
  sleep 0.3
done

if [ "$fb_up" -eq 0 ]; then
  say ""
  say "FATAL: fastboot PID (0x201c) never appeared within ${window}s"
  say "last PID seen: $seen"
  kill "$probe" 2>/dev/null
  exit 1
fi

say "fastboot interface up at t+${SECONDS}s — starting recon"
# Small settle
sleep 0.5

# ── Phase C: fastboot devices ───────────────────────────────────
banner "fastboot devices"
fb "list devices" devices

# ── Phase D: getvar — individual keys ───────────────────────────
banner "getvar — standard keys"

for key in \
  unlocked \
  secure \
  product \
  version \
  version-bootloader \
  version-baseband \
  serialno \
  variant \
  hw-revision \
  cpu \
  battery-voltage \
  battery-soc-ok \
  downloadsize \
  max-download-size \
  partition-size:boot \
  partition-size:system \
  partition-size:vendor \
  partition-size:userdata \
  partition-size:recovery \
  partition-type:boot \
  partition-type:system \
  partition-type:vendor \
  partition-type:userdata \
  current-slot \
  has-slot:boot \
  has-slot:system \
  slot-count \
  slot-successful:boot \
  slot-unbootable:boot \
  is-userspace \
  is-logical:boot \
  off-mode-charge \
; do
  fb "getvar $key" getvar "$key"
done

# ── Phase E: OEM informational commands ─────────────────────────
banner "OEM — informational (MTK-specific)"

for cmd in \
  "get-bootinfo" \
  "get-build-version" \
  "get-product-model" \
  "get-lock-state" \
  "get-psn" \
  "lks" \
  "device-info" \
  "get-variant" \
  "get-secure-state" \
  "get-root-state" \
  "get-sku" \
  "get-imei" \
  "get-wifi-mac" \
  "get-bt-mac" \
  "get-dram-info" \
  "get-emmc-info" \
  "get-battery-info" \
  "get-hw-id" \
  "get-sw-id" \
  "get-project-code" \
  "get-platform" \
  "dump-hardware-info" \
; do
  fb "oem $cmd" oem "$cmd"
done

# ── Phase F: fastboot reboot-bootloader ─────────────────────────
banner "fastboot reboot-bootloader"
fb "reboot-bootloader" reboot-bootloader

# ── Cleanup ─────────────────────────────────────────────────────
kill "$probe" 2>/dev/null
say ""
say "=== recon complete ==="
say "full log:     $log"
say "probe log:    $log.probe"
exit 0
