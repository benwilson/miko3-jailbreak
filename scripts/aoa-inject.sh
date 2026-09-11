#!/usr/bin/env bash
#
# aoa-inject.sh — run scripts/aoa-inject.py inside its venv (pyusb + libusb).
#
# Usage:    scripts/aoa-inject.sh --probe            # test AOA support, send nothing
#           scripts/aoa-inject.sh                    # inject meta+n, poll 50ms, 180s
#           scripts/aoa-inject.sh --chord meta+n --interval-ms 50 --duration 240
# Deps:     tools/aoa-inject/.venv (pyusb), Homebrew libusb. See tools/README.md.
#
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
py="$here/tools/aoa-inject/.venv/bin/python"
[ -x "$py" ] || { echo "error: venv missing; run: python3 -m venv tools/aoa-inject/.venv && tools/aoa-inject/.venv/bin/pip install pyusb" >&2; exit 1; }
export PYTHONUNBUFFERED=1
export DYLD_LIBRARY_PATH="$(brew --prefix libusb)/lib:${DYLD_LIBRARY_PATH:-}"
exec "$py" "$here/scripts/aoa-inject.py" "$@"
