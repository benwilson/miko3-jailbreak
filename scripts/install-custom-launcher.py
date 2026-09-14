#!/usr/bin/env python3
"""
install-custom-launcher.py — back up the current default-HOME state, then
install and activate the custom launcher (launcher/miko3-launcher.apk).

Backup-first, matching this repo's convention (firmware/agent-backups/):
writes the current HOME component and a couple of sanity facts to
firmware/agent-backups/<timestamp>-custom-launcher-install/, before touching
anything. The stock launcher app itself (com.miko.launcher_app) is never
modified or uninstalled — this only changes which app Android's persisted
HOME preference points at, which scripts/restore-stock-launcher.py reverts
in one command.

Usage:
  python3 scripts/install-custom-launcher.py
  python3 scripts/install-custom-launcher.py --no-build   # skip rebuilding the APK first
"""
import argparse
import datetime
import hashlib
import json
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
APK = REPO / "launcher" / "miko3-launcher.apk"
BACKUP_ROOT = REPO / "firmware" / "agent-backups"

CUSTOM_PKG = "com.miko3.launcher"
CUSTOM_COMPONENT = f"{CUSTOM_PKG}/{CUSTOM_PKG}.MainActivity"
STOCK_PKG = "com.miko.launcher_app"


def adb(*args, check=True):
    cmd = ["adb"] + list(args)
    print(f"  $ {' '.join(cmd)}")
    result = subprocess.run(cmd, capture_output=True, text=True)
    if check and result.returncode != 0:
        print(result.stdout, file=sys.stderr)
        print(result.stderr, file=sys.stderr)
        raise SystemExit(f"!! command failed ({result.returncode}): {' '.join(cmd)}")
    return result


def sha256(path):
    h = hashlib.sha256()
    h.update(path.read_bytes())
    return h.hexdigest()


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--no-build", action="store_true", help="use the existing APK instead of rebuilding")
    args = ap.parse_args()

    if not args.no_build:
        print("== building launcher/miko3-launcher.apk ==")
        subprocess.run([sys.executable, str(REPO / "scripts" / "build-custom-launcher.py")], check=True)

    if not APK.exists():
        raise SystemExit(f"!! {APK} not found — build it first or drop --no-build")

    devices = adb("devices").stdout
    if "\tdevice" not in devices:
        raise SystemExit("!! no adb device attached/authorized:\n" + devices)

    print("== 1/4 recording current HOME state (backup) ==")
    resolve = adb("shell", "cmd", "package", "resolve-activity",
                  "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME").stdout
    current_pkg = None
    for line in resolve.splitlines():
        line = line.strip()
        if line.startswith("packageName="):
            current_pkg = line.split("=", 1)[1]
            break
    if current_pkg == "android":
        # Installing a second HOME-capable app can invalidate Android's persisted
        # HOME default, so resolve-activity now shows the ambiguous FallbackHome
        # (package "android") instead of a real launcher. This is expected and
        # harmless -- com.miko.launcher_app is still installed on /system and
        # still HOME-capable, it's just no longer the unique resolved default.
        print(f"   note: HOME resolution is currently ambiguous (resolves to 'android' / "
              f"FallbackHome) because a second HOME-capable app is installed.")
        print(f"   proceeding with the known-correct restore target: {STOCK_PKG}")
    elif current_pkg != STOCK_PKG:
        print(f"!! current default HOME is '{current_pkg}', not the expected '{STOCK_PKG}'.")
        print("   Refusing to guess a restore target — check firmware/agent-backups/ and")
        print("   scripts/restore-stock-launcher.py's STOCK_COMPONENT before proceeding.")
        return 1

    ts = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    backup_dir = BACKUP_ROOT / f"{ts}-custom-launcher-install"
    backup_dir.mkdir(parents=True, exist_ok=True)
    (backup_dir / "resolve-activity-before.txt").write_text(resolve)
    record = {
        "timestamp_utc": ts,
        "previous_home_package": current_pkg,
        "previous_home_component": f"{STOCK_PKG}/{STOCK_PKG}.MainActivity",
        "restore_command": "python3 scripts/restore-stock-launcher.py",
        "installed_apk": str(APK.relative_to(REPO)),
        "installed_apk_sha256": sha256(APK),
    }
    (backup_dir / "record.json").write_text(json.dumps(record, indent=2))
    print(f"   backup written: {backup_dir.relative_to(REPO)}")

    print(f"== 2/4 installing {APK.name} ==")
    adb("install", "-r", "-t", str(APK))

    print(f"== 3/4 clearing 'stopped' state and setting HOME to {CUSTOM_COMPONENT} ==")
    adb("shell", "am", "start", "-n", CUSTOM_COMPONENT)
    result = adb("shell", "cmd", "package", "set-home-activity", CUSTOM_COMPONENT, check=False)
    print(result.stdout.strip())
    if "Success" not in result.stdout:
        raise SystemExit(f"!! set-home-activity did not report success: {result.stdout}{result.stderr}")

    print("== 4/4 verifying ==")
    check = adb("shell", "cmd", "package", "resolve-activity",
                "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME").stdout
    if CUSTOM_PKG not in check:
        raise SystemExit(f"!! resolve-activity does not show {CUSTOM_PKG} as the default:\n{check}")

    print(f"""
Done. {CUSTOM_PKG} is now the default HOME app.

To go back to the stock launcher at any time:
  python3 scripts/restore-stock-launcher.py

Backup record: {backup_dir.relative_to(REPO)}
""")
    return 0


if __name__ == "__main__":
    sys.exit(main())
