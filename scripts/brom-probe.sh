#!/usr/bin/env bash
#
# brom-probe.sh — run scripts/brom-probe.py inside the aoa-inject venv (pyusb +
# libusb), with the same libusb lookup the other scripts use.
#
# Usage:    scripts/brom-probe.sh                      # reset + watch 70s
#           scripts/brom-probe.sh --no-reset           # watch a manual power-cycle
#           scripts/brom-probe.sh --duration 120
# Deps:     tools/aoa-inject/.venv (pyusb), Homebrew libusb. See tools/README.md.
#
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
py="$here/tools/aoa-inject/.venv/bin/python"
[ -x "$py" ] || { echo "error: venv missing; see scripts/README.md" >&2; exit 1; }
export PYTHONUNBUFFERED=1
export DYLD_LIBRARY_PATH="$(brew --prefix libusb)/lib:${DYLD_LIBRARY_PATH:-}"
exec "$py" "$here/scripts/brom-probe.py" "$@"
