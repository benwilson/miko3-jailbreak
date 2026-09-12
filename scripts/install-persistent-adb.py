#!/usr/bin/env python3
"""
install-persistent-adb.py — install the Miko 3 boot agent from a factory-mode root session.

Why this exists (Miko 3 context):
  Factory mode has root adb but NO package manager (no system_server, no `pm`), so the
  boot agent APK cannot be installed with `adb install`. Instead we hand-place it under
  /data/app and let PackageManagerService register it on the next normal boot — the same
  shape the kiosk recovery used for MikoPlus/ServiceExam (docs/kiosk-recovery-fix.md).

What it does:
  1. Refuses unless the session is root and in factory mode.
  2. Backs up packages.xml and package-restrictions.xml (host + on device).
  3. Places the committed APK at /data/app/com.miko3.bootagent-<suffix>==/base.apk
     (system:system, dir 0755, file 0644).
  4. Pre-authorizes the host adb key in /data/misc/adb/adb_keys (system:system, 0640).
  5. Reboots to normal boot (unless --no-reboot).

It never touches com.example.root.serviceexam or com.miko.mikoplus: ServiceExam is the
installer of record for every app, and removing it breaks the kiosk.

Usage:
  python3 scripts/install-persistent-adb.py            # full install + reboot
  python3 scripts/install-persistent-adb.py --no-reboot  # everything except the reboot
  python3 scripts/install-persistent-adb.py --status     # report what is already placed

Dependencies: python3, adb (a device in factory mode with root adb).
"""
import argparse
import base64
import re
import secrets
import subprocess
import sys
import tempfile
import time
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
APK = REPO / "bootagent" / "miko3-bootagent.apk"
BACKUP_ROOT = REPO / "firmware" / "agent-backups"

PKG = "com.miko3.bootagent"
PACKAGES_XML = "/data/system/packages.xml"
RESTRICTIONS_XML = "/data/system/users/0/package-restrictions.xml"
ADB_KEYS = "/data/misc/adb/adb_keys"
REMOTE_STAGE = "/data/local/tmp/miko3-bootagent.apk"
REMOTE_BACKUP = "/data/local/tmp/miko3-agent-backup"
BOOT_LOG = "/data/local/tmp/miko3-boot.log"

# packages this script must never remove or edit
PROTECTED = ("com.example.root.serviceexam", "com.miko.mikoplus")

# The documented unit profile (docs/device-intel.md); override with --usb-serial.
DEFAULT_USB_SERIAL = "MIKO3250XXM3Q0636CB"

# A device that stops answering must fail, not hang.
ADB_TIMEOUT = 120


class InstallError(SystemExit):
    """An install precondition or step failed; message is actionable."""


# Set from --usb-serial. Every device command names its transport (KTD10): once the unit is
# on normal boot adbd may be reachable over TCP as well, and an untargeted `adb shell` then
# fails with "more than one device".
SERIAL = None


def adb(*args, check=True):
    cmd = ["adb"] + (["-s", SERIAL] if SERIAL else []) + list(args)
    print("  $ " + " ".join(cmd), flush=True)
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=ADB_TIMEOUT)
    except subprocess.TimeoutExpired:
        raise InstallError(
            f"!! adb timed out after {ADB_TIMEOUT}s: {' '.join(cmd)}\n"
            "   The unit stopped answering. Check the USB connection and re-run.")
    if check and r.returncode != 0:
        raise InstallError(f"!! adb failed ({r.returncode}): {' '.join(cmd)}\n{r.stderr.strip()}")
    return r


def sh(cmd, check=True):
    return adb("shell", cmd, check=check)


def require_root_factory():
    """Refuse unless uid=0 and ro.bootmode=factory."""
    uid = sh("id -u").stdout.strip()
    bootmode = sh("getprop ro.bootmode").stdout.strip()
    if uid != "0":
        raise InstallError(f"!! not a root session (id -u={uid!r}). Enter factory mode with "
                           "scripts/factory-root.sh first.")
    if bootmode != "factory":
        raise InstallError(f"!! not in factory mode (ro.bootmode={bootmode!r}). The manual "
                           "/data/app install only works from a factory-mode root session.")
    return uid, bootmode


def make_suffix():
    """A /data/app directory suffix in the platform's shape: 20 base64url chars + '=='."""
    raw = secrets.token_bytes(15)
    b64 = base64.b64encode(raw).decode("ascii").replace("+", "_").replace("/", "-")
    return b64[:20] + "=="


def pkg_dir(suffix):
    return f"/data/app/{PKG}-{suffix}"


def existing_suffixes():
    """Suffixes already placed for this package (idempotency)."""
    r = sh(f"ls -d /data/app/{PKG}-*== 2>/dev/null", check=False)
    out = []
    for line in r.stdout.splitlines():
        m = re.search(re.escape(PKG) + r"-(\S+==)/?$", line.strip())
        if m:
            out.append(m.group(1))
    return out


def backup_system_state(ts):
    """Back up every system-state file the install writes or replaces (R9).

    `adb_keys` is included, and its *absence* is recorded explicitly when it does not
    exist pre-install: revert has to delete it in that case, or the pre-install state is
    not restored and the host key survives the uninstall (R14).
    """
    host_dir = BACKUP_ROOT / ts
    host_dir.mkdir(parents=True, exist_ok=True)
    dev_dir = f"{REMOTE_BACKUP}-{ts}"
    sh(f"mkdir -p {dev_dir}")
    for path in (PACKAGES_XML, RESTRICTIONS_XML):
        name = Path(path).name
        adb("pull", path, str(host_dir / name))
        sh(f"cp {path} {dev_dir}/{name}")
    present = sh(f"[ -e {ADB_KEYS} ] && echo yes || echo no").stdout.strip() == "yes"
    if present:
        adb("pull", ADB_KEYS, str(host_dir / "adb_keys"))
        sh(f"cp {ADB_KEYS} {dev_dir}/adb_keys")
        print(f"   backed up to {host_dir} and {dev_dir}")
    else:
        (host_dir / "adb_keys.absent").write_text(
            "adb_keys did not exist before install; revert must delete it.\n")
        sh(f"rm -f {dev_dir}/adb_keys")
        print(f"   backed up to {host_dir} and {dev_dir} (adb_keys absent pre-install, recorded)")
    return host_dir


def place_apk(suffix):
    """Push and place the APK under /data/app with the expected owner and mode."""
    if not APK.exists():
        raise InstallError(f"!! committed APK missing at {APK}; run scripts/build-bootagent.py")
    adb("push", str(APK), REMOTE_STAGE)
    d = pkg_dir(suffix)
    sh(f"mkdir -p {d}/lib")
    sh(f"cp {REMOTE_STAGE} {d}/base.apk")
    sh(f"chmod 755 {d} {d}/lib; chmod 644 {d}/base.apk")
    sh(f"chown -R system:system {d}")
    return d


def authorize_host_key():
    """Pre-authorize the host adb key so USB/TCP need no on-device confirmation (KTD5).

    The key is pushed as a file rather than interpolated into a shell command, and it is
    APPENDED only when absent — replacing the file would silently revoke every other host
    already authorized on the unit. The write is read back, so a key that did not land is
    reported instead of being announced as pre-authorized.
    """
    key = Path.home() / ".android" / "adbkey.pub"
    if not key.exists() or not key.read_text().strip():
        print(f"   !! host key {key} missing or empty; skipping pre-authorization")
        return False
    staged = "/data/local/tmp/miko3-adbkey.pub"
    adb("push", str(key), staged)
    sh("mkdir -p /data/misc/adb")
    sh(f"if ! grep -qxF \"$(cat {staged})\" {ADB_KEYS} 2>/dev/null; then "
       f"cat {staged} >> {ADB_KEYS}; fi")
    sh(f"rm -f {staged}")
    sh(f"chown system:system {ADB_KEYS}; chmod 640 {ADB_KEYS}")
    written = sh(f"wc -l < {ADB_KEYS} 2>/dev/null", check=False).stdout.strip()
    if not written or written == "0":
        raise InstallError(f"!! the host key did not land in {ADB_KEYS}; aborting rather than "
                           "leaving the unit without the authorization the install promised.")
    return True


# --- pure helpers (unit-tested) ---------------------------------------------------------

def clear_stopped(xml, pkg=PKG):
    """Remove stopped="true" from the package's entry in package-restrictions.xml.

    Returns (new_xml, n_changes). Android refuses BOOT_COMPLETED to a stopped package,
    and factory mode has no ActivityManager to clear it via `am start`.
    """
    pattern = re.compile(r'(<pkg\s+name="' + re.escape(pkg) + r'"[^>]*?)\s+stopped="true"')
    return pattern.subn(r"\1", xml)


def arm_decision(registered):
    """What the KTD4 post-boot arm path should do.

    'clear-stopped' when the receiver registered but never ran: Android refuses
    BOOT_COMPLETED to a package still marked stopped, and factory mode has no activity
    manager to clear it with `am start` (R7). 'recover' when the package never registered
    at all — there, no entry exists to correct and the documented recovery is to re-enter
    factory mode and re-place (KTD4).
    """
    return "clear-stopped" if registered else "recover"


def status():
    suffixes = existing_suffixes()
    print(f"package: {PKG}")
    print(f"placed:  {'yes, ' + ', '.join(suffixes) if suffixes else 'no'}")
    log = sh(f"cat {BOOT_LOG} 2>/dev/null | tail -5", check=False).stdout.strip()
    print("boot log (tail):")
    print(f"  {log}" if log else "  (none — the agent has not run on this unit)")
    return 0


def agent_registered():
    """True when packages.xml carries the agent's <package> block.

    A file probe, not `pm path`: the only session that can reach an un-armed unit is factory
    mode, which has no package manager, so a package-manager probe would always answer "no"
    and the arm path could never do the job it exists for (KTD4).
    """
    out = sh(f"grep -o 'name=\"{PKG}\"' {PACKAGES_XML} 2>/dev/null | head -1", check=False)
    return bool(out.stdout.strip())


def arm():
    """The KTD4 post-boot arm path: run after a normal boot that produced no adb.

    Registration is asserted first, so "registered but refused the broadcast" and "never
    registered" get different answers instead of one silent failure.
    """
    decision = arm_decision(agent_registered())
    if decision == "recover":
        raise InstallError(
            "!! the agent never registered, so there is no entry to correct — the "
            "install-time backup predates the APK and cannot hold its package block.\n"
            "   Recover by re-entering factory mode (scripts/factory-root.sh) and re-running "
            "install.")
    with tempfile.TemporaryDirectory() as td:
        tmp = Path(td) / "package-restrictions.xml"
        adb("pull", RESTRICTIONS_XML, str(tmp))
        edited, n = clear_stopped(tmp.read_text())
        if n == 0:
            print("   the agent is registered and not marked stopped; nothing to arm")
            return 0
        mode = sh(f"stat -c '%a' {RESTRICTIONS_XML}", check=False).stdout.strip() or "660"
        owner = sh(f"stat -c '%U:%G' {RESTRICTIONS_XML}",
                   check=False).stdout.strip() or "system:system"
        tmp.write_text(edited)
        adb("push", str(tmp), RESTRICTIONS_XML)
        sh(f"chown {owner} {RESTRICTIONS_XML}; chmod {mode} {RESTRICTIONS_XML}")
    print(f"   cleared the stopped flag for {PKG}; reboot to let BOOT_COMPLETED through")
    return 0


def main():
    global SERIAL
    ap = argparse.ArgumentParser(description="Install the Miko 3 boot agent from factory mode.")
    ap.add_argument("--no-reboot", action="store_true", help="do everything except the reboot")
    ap.add_argument("--status", action="store_true", help="report placement and exit")
    ap.add_argument("--arm", action="store_true",
                    help="post-boot: clear a stopped flag when the receiver registered but never ran")
    ap.add_argument("--usb-serial", default=DEFAULT_USB_SERIAL,
                    help=f"USB transport serial (default: {DEFAULT_USB_SERIAL})")
    args = ap.parse_args()
    SERIAL = args.usb_serial

    if args.arm:
        return arm()

    if args.status:
        return status()

    require_root_factory()
    ts = time.strftime("%Y%m%dT%H%M%SZ", time.gmtime())
    backup_system_state(ts)

    suffixes = existing_suffixes()
    if suffixes:
        suffix = suffixes[0]
        print(f"== already placed ({suffix}); refreshing the APK ==")
    else:
        suffix = make_suffix()
        print(f"== placing APK as {PKG}-{suffix} ==")
    place_apk(suffix)

    if authorize_host_key():
        print("== host adb key pre-authorized ==")

    print(f"\n== install complete: {PKG}-{suffix} ==")
    if args.no_reboot:
        print("   --no-reboot: reboot to normal boot yourself to arm the agent.")
    else:
        print("   rebooting to normal boot ...")
        adb("reboot")
    return 0


if __name__ == "__main__":
    sys.exit(main())
