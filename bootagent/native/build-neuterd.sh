#!/usr/bin/env bash
# Rebuild the neuterd arm64 reboot-neuter daemon (freestanding, no libc, no NDK).
#
# Vendored from ne3d-4-steve/miko3-adb-boot-agent. Requires clang + ld.lld
# (on macOS: `brew install lld`). Produces ./neuterd next to this script; that
# binary is what build-bootagent.py embeds into the APK.
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
LLD="${LLD:-}"
if [ -z "$LLD" ]; then
  LLD="$(command -v ld.lld 2>/dev/null || true)"
  [ -z "$LLD" ] && [ -x /opt/homebrew/opt/lld/bin/ld.lld ] && LLD=/opt/homebrew/opt/lld/bin/ld.lld
fi
[ -n "$LLD" ] || {
  echo "!! ld.lld not found — install it (brew install lld) or set LLD=/path/to/ld.lld" >&2
  exit 1
}

clang --target=aarch64-linux-gnu -O2 -nostdlib -static -ffreestanding \
  -fuse-ld="$LLD" -Wl,-e,_start -o "$DIR/neuterd" "$DIR/neuterd.c"
file "$DIR/neuterd"
echo "built $DIR/neuterd ($(wc -c <"$DIR/neuterd") bytes)"
