#!/usr/bin/env python3
"""
restore-stock-launcher.py — revert the default HOME app back to the stock
launcher (com.miko.launcher_app), undoing scripts/install-custom-launcher.py.

This does NOT uninstall or modify anything else: com.miko.launcher_app lives
read-only on /system and was never touched, so this is just re-pointing
Android's persisted HOME preference back at it. Safe to run any time,
including if the custom launcher was never installed.

Usage:
  python3 scripts/restore-stock-launcher.py
  python3 scripts/restore-stock-launcher.py --uninstall-custom   # also remove com.miko3.launcher
"""
import argparse
import subprocess
import sys

STOCK_PKG = "com.miko.launcher_app"
STOCK_ACTIVITY = "com.miko.launcher_app.MainActivity"
STOCK_COMPONENT = f"{STOCK_PKG}/{STOCK_ACTIVITY}"
CUSTOM_PKG = "com.miko3.launcher"


def adb(*args, check=True):
    cmd = ["adb"] + list(args)
    print(f"  $ {' '.join(cmd)}")
    return subprocess.run(cmd, capture_output=True, text=True, check=check)


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--uninstall-custom", action="store_true",
                    help="also uninstall com.miko3.launcher after restoring HOME")
    args = ap.parse_args()

    devices = adb("devices").stdout
    if "\tdevice" not in devices:
        print("!! no adb device attached/authorized:\n" + devices, file=sys.stderr)
        return 1

    print(f"== restoring HOME to {STOCK_COMPONENT} ==")
    result = adb("shell", "cmd", "package", "set-home-activity", STOCK_COMPONENT, check=False)
    print(result.stdout.strip())
    if "Success" not in result.stdout:
        print(f"!! set-home-activity did not report success: {result.stdout}{result.stderr}",
              file=sys.stderr)
        return 1

    check = adb("shell", "cmd", "package", "resolve-activity",
                "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME")
    if STOCK_PKG in check.stdout:
        print(f"== confirmed: HOME now resolves to {STOCK_PKG} ==")
    else:
        print(f"!! resolve-activity does not show {STOCK_PKG} as the default:\n{check.stdout}",
              file=sys.stderr)
        return 1

    if args.uninstall_custom:
        print(f"== uninstalling {CUSTOM_PKG} ==")
        adb("uninstall", CUSTOM_PKG, check=False)

    print("\nDone. Press the home button (or reboot) to see the stock launcher again.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
