#!/usr/bin/env bash
#
# mtk.sh — friendly wrapper around mtkclient (tools/mtkclient).
#
# Activates the mtkclient venv, verifies setup, and forwards all args to mtk.py.
#
# Usage:        scripts/mtk.sh <mtkclient-command> [args...]
# Examples:     scripts/mtk.sh printgpt              # read partition table (read-only)
#               scripts/mtk.sh r boot boot.img       # dump the boot partition
#               scripts/mtk.sh --help
# Dependencies: tools/mtkclient venv + libusb (see tools/README.md).
#
set -euo pipefail
case "${1:-}" in -h|--help) sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'; ;; esac

here="$(cd "$(dirname "$0")/.." && pwd)"
mc="$here/tools/mtkclient"
venv="$mc/.venv/bin/activate"

if [ ! -f "$venv" ]; then
  echo "error: mtkclient venv not found at $mc/.venv" >&2
  echo "  set it up first — see tools/README.md:" >&2
  echo "    cd tools/mtkclient && python3 -m venv .venv && . .venv/bin/activate && pip install -r requirements.txt" >&2
  exit 1
fi

# shellcheck disable=SC1090
. "$venv"
cd "$mc"
exec python mtk.py "$@"
