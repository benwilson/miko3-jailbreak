#!/usr/bin/env bash
#
# fastboot-recon2.sh — second pass: OEM commands + boot test.
# Runs the highest-value commands first, before the flaky fastboot session dies.
#
# Usage:      scripts/fastboot-recon2.sh [window_seconds]     (default 120)
# Output:     recon/captures/fastboot-recon2-<utc>.txt
#
set -uo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
window="${1:-120}"
ts="$(date -u +%Y%m%dT%H%M%SZ)"
log="$here/recon/captures/fastboot-recon2-$ts.txt"
mkdir -p "$here/recon/captures"

say() { printf '%s\n' "$*" | tee -a "$log"; }
banner() { say ""; say "=== $* ==="; say ""; }

pid_now() {
  ioreg -p IOUSB -w0 -l 2>/dev/null | awk 'BEGIN{RS="\\+-o "} \
    /"idVendor" = 3725|"idVendor" = 6353/ {
      match($0, /"idProduct" = [0-9]+/); pid = substr($0, RSTART, RLENGTH);
      gsub(/.*= /, "", pid); printf "0x%04x", pid + 0; exit }'
}

# Run a fastboot command with short timeout — 8s max
fb() {
  local desc="$1"; shift
  say "  CMD: fastboot $*"
  local out
  if out="$(timeout 8 fastboot "$@" 2>&1)"; then
    say "  OK:  $out"
    return 0
  else
    local rc=$?
    if [ $rc -eq 124 ]; then
      say "  TIMEOUT"
    else
      say "  ERR (rc=$rc): $out"
    fi
    return 1
  fi
}

banner "fastboot-recon2 @ $(date -u +%FT%TZ)"

# ── Launch probe ───────────────────────────────────────────────
say "launching brom-probe (META -> FASTBOOT) ..."
"$here/scripts/brom-probe.sh" --no-reset --meta FASTBOOT --duration "$window" \
  > "$log.probe" 2>&1 &
probe=$!

# ── Wait for fastboot ──────────────────────────────────────────
deadline=$((SECONDS + window))
fb_up=0
while [ "$SECONDS" -lt "$deadline" ]; do
  cur="$(pid_now)"
  if [ "$cur" = "0x201c" ]; then
    fb_up=1
    say "fastboot up at t+${SECONDS}s"
    break
  fi
  sleep 0.3
done

if [ "$fb_up" -eq 0 ]; then
  say "FATAL: fastboot never appeared"
  kill "$probe" 2>/dev/null
  exit 1
fi
sleep 0.3

# ── Phase 1: devices + critical getvars ────────────────────────
banner "PHASE 1 — critical info"
fb "devices" devices
fb "secure" getvar secure
fb "product" getvar product
fb "max-download-size" getvar max-download-size
fb "partition-size:boot" getvar partition-size:boot
fb "partition-size:system" getvar partition-size:system
fb "partition-size:userdata" getvar partition-size:userdata

# ── Phase 2: OEM commands (most valuable first) ────────────────
banner "PHASE 2 — OEM informational"

fb "oem get-bootinfo" oem get-bootinfo
fb "oem get-lock-state" oem get-lock-state
fb "oem lks" oem lks
fb "oem device-info" oem device-info
fb "oem get-product-model" oem get-product-model
fb "oem get-build-version" oem get-build-version
fb "oem get-psn" oem get-psn
fb "oem get-variant" oem get-variant
fb "oem get-secure-state" oem get-secure-state
fb "oem get-root-state" oem get-root-state
fb "oem get-platform" oem get-platform
fb "oem get-project-code" oem get-project-code
fb "oem get-dram-info" oem get-dram-info
fb "oem get-emmc-info" oem get-emmc-info
fb "oem get-battery-info" oem get-battery-info
fb "oem get-hw-id" oem get-hw-id
fb "oem dump-hardware-info" oem dump-hardware-info

# ── Phase 3: unlock-capable commands (read-only probes) ────────
banner "PHASE 3 — unlock probes (read-only)"
fb "flashing get_unlock_ability" flashing get_unlock_ability
fb "oem unlock" oem unlock
fb "flashing unlock" flashing unlock

# ── Phase 4: boot test (RAM only, safe) ────────────────────────
banner "PHASE 4 — fastboot boot test"
say "NOTE: 'fastboot boot' loads an image to RAM without flashing."
say "If the bootloader rejects it, the device just continues to fastboot."
say "If it accepts but the image is wrong, the device reboots normally."
say ""
say "We don't have a custom image yet, but we can test whether the command"
say "is accepted or rejected:"
fb "boot (expect 'command not allowed' or 'no such file')" boot /dev/null

# ── Phase 5: any remaining getvars ─────────────────────────────
banner "PHASE 5 — remaining getvars"
fb "getvar version-bootloader" getvar version-bootloader
fb "getvar serialno" getvar serialno
fb "getvar hw-revision" getvar hw-revision
fb "getvar battery-voltage" getvar battery-voltage
fb "getvar variant" getvar variant
fb "getvar slot-count" getvar slot-count
fb "getvar current-slot" getvar current-slot
fb "getvar has-slot:boot" getvar has-slot:boot

# ── Cleanup ────────────────────────────────────────────────────
kill "$probe" 2>/dev/null
say ""
say "=== recon2 complete ==="
say "log: $log"
exit 0
