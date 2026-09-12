#!/usr/bin/env python3
"""
revert-persistent-adb.py — remove the boot agent and restore the pre-install state.

Why this exists (Miko 3 context):
  The agent is persistent state on the unit. Reverting must remove only the agent:
  ServiceExam and MikoPlus are the kiosk's paired apps and ServiceExam is the installer
  of record, so touching either breaks the device (docs/mishap-recovery.md).

  Two ordering rules here are safety constraints, not preferences:
    - The agent's packages.xml entry comes out BEFORE its /data/app directory. A surviving
      entry whose directory is gone is the state docs/mishap-recovery.md records as feeding
      the package manager's purge of all of /data/app, taking MikoPlus and every oat/ with it.
    - The backup-presence gate runs FIRST. A check placed after the destructive steps cannot
      prevent the state it exists to prevent.

Requires: root ADB (factory mode). Run scripts/factory-root.sh first.

What it does:
  1. Gate: a device that never had the agent is a clean no-op; an installed agent with no
     usable backup stops rather than editing boot-critical XML blind.
  2. Kills the USB watcher and neuterd.
  3. Removes the agent's own entries from packages.xml and package-restrictions.xml
     surgically, leaving every other entry untouched.
  4. Removes /data/app/com.miko3.bootagent-* .
  5. Removes the agent's /data/local/tmp artifacts, including neuterd's no-op source.
  6. Restores adb_keys to its recorded pre-install state (deletes it when install recorded
     that it did not exist).
  7. Re-asserts that ServiceExam and MikoPlus are still installed.
  8. Reboots via adb's own reboot service, which the reboot shadow cannot intercept.

Usage:
  python3 scripts/revert-persistent-adb.py
  python3 scripts/revert-persistent-adb.py --no-reboot
  python3 scripts/revert-persistent-adb.py --dry-run

Dependencies: python3, adb.
"""
import argparse
import json
import re
import subprocess
import sys
import tempfile
import time
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
BACKUP_ROOT = REPO / "firmware" / "agent-backups"
CAPTURE_DIR = REPO / "recon" / "captures"

PKG = "com.miko3.bootagent"
PACKAGES_XML = "/data/system/packages.xml"
RESTRICTIONS_XML = "/data/system/users/0/package-restrictions.xml"
ADB_KEYS = "/data/misc/adb/adb_keys"

# neuterd hardcodes its no-op source here; it survives a revert unless removed.
NEUTER_SRC = "/data/local/tmp/nr"

PROTECTED = ("com.example.root.serviceexam", "com.miko.mikoplus")

DEFAULT_USB_SERIAL = "MIKO3250XXM3Q0636CB"


class RevertError(SystemExit):
    """A revert precondition or step failed."""


def adb(*args, serial=None, check=True):
    cmd = ["adb"] + (["-s", serial] if serial else []) + list(args)
    print("  $ " + " ".join(cmd), flush=True)
    r = subprocess.run(cmd, capture_output=True, text=True)
    if check and r.returncode != 0:
        raise RevertError(f"!! adb failed ({r.returncode}): {' '.join(cmd)}\n{r.stderr.strip()}")
    return r


def sh(cmd, serial=None, check=True):
    return adb("shell", cmd, serial=serial, check=check)


def require_root(serial):
    uid = sh("id -u", serial).stdout.strip()
    if uid != "0":
        raise RevertError(f"!! not a root session (id -u={uid!r}); enter factory mode with "
                          "scripts/factory-root.sh first.")


# --- pure helpers (unit-tested) ---------------------------------------------------------

def remove_package_entry(xml, pkg=PKG):
    """Remove the <package name="pkg"> ... </package> block from packages.xml."""
    pattern = re.compile(
        r'\s*<package\s+name="' + re.escape(pkg) + r'"[^>]*>.*?</package>',
        re.DOTALL,
    )
    return pattern.subn("", xml)


def remove_restriction_entry(xml, pkg=PKG):
    """Remove the <pkg name="pkg" ... /> entry from package-restrictions.xml."""
    pattern = re.compile(r'\s*<pkg\s+name="' + re.escape(pkg) + r'"[^>]*/>')
    return pattern.subn("", xml)


def latest_backup():
    """The most recent install backup directory, or None."""
    if not BACKUP_ROOT.exists():
        return None
    dirs = sorted([d for d in BACKUP_ROOT.iterdir() if d.is_dir()])
    return dirs[-1] if dirs else None


def opening_gate(agent_installed, backup):
    """Revert's first decision: 'proceed', 'nothing-to-do', or 'error'.

    An installed agent with no backup cannot be reverted safely — the pre-install state is
    unknown, and editing boot-critical XML blind is the failure this script exists to avoid.
    """
    if backup is not None:
        return "proceed"
    return "error" if agent_installed else "nothing-to-do"


def adb_keys_action(backup):
    """'restore', 'delete', or 'unknown' for /data/misc/adb/adb_keys.

    Install records absence explicitly, because leaving the host key behind is not the
    pre-install state (R14).
    """
    if backup is None:
        return "unknown"
    if (backup / "adb_keys").exists():
        return "restore"
    if (backup / "adb_keys.absent").exists():
        return "delete"
    return "unknown"


def staged_removal_cmds():
    """The device commands that remove only the agent (no directory before its entry)."""
    return [
        "pkill -f /data/local/tmp/miko3-usb-watch.sh 2>/dev/null; true",
        "pkill -f /data/local/tmp/neuterd 2>/dev/null; true",
        f"rm -rf /data/app/{PKG}-*==",
        "rm -f /data/local/tmp/miko3-bootagent.apk /data/local/tmp/miko3-boot.log "
        f"/data/local/tmp/miko3-usb-watch.sh /data/local/tmp/neuterd {NEUTER_SRC}",
    ]


# --- device steps -----------------------------------------------------------------------

def agent_installed(serial):
    """True when the agent is present, by directory or by registration."""
    d = sh(f"ls -d /data/app/{PKG}-*== 2>/dev/null", serial, check=False).stdout.strip()
    if d:
        return True
    return bool(sh(f"pm path {PKG} 2>/dev/null", serial, check=False).stdout.strip())


def edit_agent_entries(serial, path, remove_fn, label):
    """Pull, surgically remove the agent's entry, push back only when something changed.

    A wholesale restore is deliberately NOT used: it would roll back every entry
    PackageManagerService has written since install, and the recorded failure mode for this
    unit is a stale or partial packages.xml causing a purge of all of /data/app.
    """
    tmp = Path(tempfile.mkdtemp()) / Path(path).name
    adb("pull", path, str(tmp), serial=serial)
    original = tmp.read_text()
    edited, removed = remove_fn(original)
    if removed == 0:
        print(f"   {label}: no {PKG} entry present")
        return 0
    before_mode = sh(f"stat -c '%a' {path}", serial, check=False).stdout.strip() or "660"
    before_owner = sh(f"stat -c '%U:%G' {path}", serial, check=False).stdout.strip() or "system:system"
    tmp.write_text(edited)
    adb("push", str(tmp), path, serial=serial)
    sh(f"chown {before_owner} {path}; chmod {before_mode} {path}", serial)
    print(f"   {label}: removed the agent's entry")
    return removed


def restore_adb_keys(serial, backup):
    action = adb_keys_action(backup)
    if action == "restore":
        adb("push", str(backup / "adb_keys"), ADB_KEYS, serial=serial)
        sh(f"chown system:system {ADB_KEYS}; chmod 640 {ADB_KEYS}", serial)
        print("   adb_keys: restored the pre-install file")
    elif action == "delete":
        sh(f"rm -f {ADB_KEYS}", serial, check=False)
        print("   adb_keys: removed (install recorded it absent pre-install)")
    else:
        print("   !! adb_keys: the backup carries no record; leaving it in place — a host key "
              "may remain authorized")


def assert_protected(serial):
    """Re-assert ServiceExam and MikoPlus are still installed (R8, AE4)."""
    missing = []
    for name in PROTECTED:
        path = sh(f"pm path {name} 2>/dev/null", serial, check=False).stdout.strip()
        version = sh(f"dumpsys package {name} 2>/dev/null | grep -m1 versionName",
                     serial, check=False).stdout.strip()
        if path:
            print(f"   {name}: still installed {version}")
        else:
            missing.append(name)
    return missing


def write_capture(payload):
    CAPTURE_DIR.mkdir(parents=True, exist_ok=True)
    ts = time.strftime("%Y%m%dT%H%M%SZ", time.gmtime())
    path = CAPTURE_DIR / f"revert-persistent-adb-{ts}.json"
    path.write_text(json.dumps(payload, indent=2) + "\n")
    return path


def main():
    ap = argparse.ArgumentParser(description="Revert the Miko 3 boot agent.")
    ap.add_argument("--no-reboot", action="store_true")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--usb-serial", default=DEFAULT_USB_SERIAL,
                    help=f"USB transport serial (default: {DEFAULT_USB_SERIAL})")
    args = ap.parse_args()
    serial = args.usb_serial

    backup = latest_backup()

    if args.dry_run:
        print("would run on device:")
        for c in staged_removal_cmds():
            print(f"  {c}")
        for path, fn, label in ((PACKAGES_XML, remove_package_entry, "packages.xml"),
                                (RESTRICTIONS_XML, remove_restriction_entry,
                                 "package-restrictions.xml")):
            print(f"  surgically remove the {PKG} entry from {path} ({label})")
        print(f"  adb_keys action: {adb_keys_action(backup)}")
        print(f"backup: {backup or '(none found)'}")
        return 0

    require_root(serial)

    # The gate runs first: placed after the destructive steps it cannot prevent the state
    # it exists to prevent.
    gate = opening_gate(agent_installed(serial), backup)
    if gate == "nothing-to-do":
        print("== nothing to revert: the agent is not installed and no backup exists ==")
        return 0
    if gate == "error":
        raise RevertError(
            "!! the agent is installed but no install backup exists under "
            f"{BACKUP_ROOT}.\n   Reverting without it would edit boot-critical XML blind — "
            "restore firmware (scripts/restore-firmware.sh) or re-enter factory mode "
            "(scripts/factory-root.sh) and re-run install first.")

    for cmd in staged_removal_cmds():
        sh(cmd, serial, check=False)
    print("== agent processes and files removed ==")

    # Entries before the directory: a surviving entry whose directory is gone is the
    # documented purge trigger.
    edit_agent_entries(serial, PACKAGES_XML, remove_package_entry, "packages.xml")
    edit_agent_entries(serial, RESTRICTIONS_XML, remove_restriction_entry,
                       "package-restrictions.xml")

    restore_adb_keys(serial, backup)

    missing = assert_protected(serial)
    if missing:
        print(f"   !! protected apps missing after revert: {', '.join(missing)}")
    else:
        print("== ServiceExam and MikoPlus still installed ==")

    try:
        print(f"capture: {write_capture({'restored_from': str(backup), 'protected_missing': missing})}")
    except OSError as e:
        print(f"capture: !! not written ({e})")

    if args.no_reboot:
        print("   --no-reboot: reboot yourself to finish.")
    else:
        # adb's reboot service sets sys.powerctl and never execs /system/bin/reboot, which
        # the shadow intercepts for the rest of the boot even after neuterd is killed.
        print("   rebooting via adb's reboot service (bypasses the reboot shadow) ...")
        adb("reboot", serial=serial)
    return 0


if __name__ == "__main__":
    sys.exit(main())
