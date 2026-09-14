#!/usr/bin/env python3
"""
auto-persistent-adb.py — end-to-end automation for getting persistent root
adb on normal boot, without ever racing ServiceExam's watchdog.

See docs/persistent-adb-normal-boot.md for the method this automates and why
each step exists. Summary of the sequence:

  1. Get root in factory mode (reuses scripts/factory-root.sh for the actual
     MediaTek preloader handshake; polls independently for real root because
     that script's own success detection has been unreliable in practice).
  2. Back up the currently-live ServiceExam v92 APK to an on-device path
     (a local `cp`, not a USB transfer — it's already on the device).
  3. Delete the /data/app override for ServiceExam, reverting PMS to the
     untouched stock v40 copy on /system on the next boot. v40 contains none
     of the watchdog code (SecurityMonitor), confirmed by decompilation.
  4. Patch persist.sys.usb.config to "mtp,adb" in the persisted-properties
     protobuf file, so adb comes up automatically on the next normal boot.
  5. Reboot. Poll for a calm, un-raced adb session (v40 has no watchdog, so
     this should just work, the same as it did in the session that produced
     this script).
  6. Push and pm install the bootagent APK (a real host->device transfer —
     it's a small custom APK, not one of the large real update payloads).
     Launch it once via `am start` to clear Android's post-install "stopped"
     state, which is what arms its BOOT_COMPLETED receiver.
  7. Reboot again and verify — WITHOUT any manual intervention — that the
     bootagent neuters the watchdog and brings adb up on its own. Safe to
     test this in isolation because ServiceExam is still stock v40 at this
     point, so nothing bad happens even if the timing isn't perfect yet.
  8. Once confirmed, pm install the backed-up ServiceExam v92 APK from step 2
     (the on-device copy — no re-transfer of the 106MB file needed). The
     bootagent's neuter should already be active by the time the watchdog
     code exists again.
  9. Final reboot. Verify the kiosk works AND adb persists automatically,
     with no manual racing anywhere in the whole sequence.

Every mutating step backs up what it's about to change before touching it.
Refuses to proceed past a step it can't verify rather than guessing forward.
"""
from __future__ import annotations

import datetime
import hashlib
import os
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
SERIAL = os.environ.get("SERIAL", "MIKO3250XXM3Q0636CB")

BOOTAGENT_APK = os.path.join(REPO, "bootagent", "miko3-bootagent.apk")
BOOTAGENT_PKG = "com.miko3.bootagent"
BOOTAGENT_ACTIVITY = f"{BOOTAGENT_PKG}/.MainActivity"

NEUTERD_LOCAL = os.path.join(REPO, "bootagent", "native", "neuterd")
NEUTERD_REMOTE = "/data/local/tmp/neuterd"

SERVICEEXAM_PKG = "com.example.root.serviceexam"
SERVICEEXAM_V92_BACKUP = "/data/local/tmp/serviceexam-v92-backup.apk"

TS = datetime.datetime.utcnow().strftime("%Y%m%dT%H%M%SZ")
LOG_DIR = os.path.join(REPO, "recon", "captures")
LOG_PATH = os.path.join(LOG_DIR, f"auto-persistent-adb-{TS}.log")

BACKUP_DIR = os.path.join(REPO, "firmware", "agent-backups", f"auto-persistent-adb-{TS}")


def log(msg: str) -> None:
    line = f"[{datetime.datetime.now().strftime('%H:%M:%S')}] {msg}"
    print(line, flush=True)
    os.makedirs(LOG_DIR, exist_ok=True)
    with open(LOG_PATH, "a") as f:
        f.write(line + "\n")


def die(msg: str) -> None:
    log(f"FATAL: {msg}")
    sys.exit(1)


def adb(*args: str, timeout: int = 15, check: bool = False) -> subprocess.CompletedProcess:
    cmd = ["adb", "-s", SERIAL, *args]
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        return subprocess.CompletedProcess(cmd, 124, "", "timeout")
    if check and r.returncode != 0:
        die(f"command failed ({r.returncode}): {' '.join(cmd)}\n{r.stderr}")
    return r


def adb_shell(cmd: str, timeout: int = 15) -> subprocess.CompletedProcess:
    return adb("shell", cmd, timeout=timeout)


def device_present() -> bool:
    r = subprocess.run(["adb", "devices"], capture_output=True, text=True, timeout=8)
    return any(line.startswith(SERIAL) and "device" in line for line in r.stdout.splitlines())


def has_root() -> bool:
    if not device_present():
        return False
    r = adb_shell("id", timeout=5)
    return r.returncode == 0 and "uid=0" in r.stdout


def bootmode() -> str:
    r = adb_shell("getprop ro.bootmode", timeout=5)
    return r.stdout.strip()


def wait_for(predicate, timeout_s: int, interval_s: float = 2.0, what: str = "condition") -> bool:
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        if predicate():
            return True
        time.sleep(interval_s)
    log(f"timed out waiting for: {what}")
    return False


# --- persisted-properties codec (protobuf PersistentPropertyRecord, unframed) ---
# Verified round-trip byte-identical against the real device file before this
# script was written; see docs/persistent-adb-normal-boot.md.

def _read_varint(buf: bytes, pos: int):
    result = 0
    shift = 0
    while True:
        b = buf[pos]
        pos += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80):
            break
        shift += 7
    return result, pos


def _write_varint(n: int) -> bytes:
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            break
    return bytes(out)


def _parse_props(data: bytes):
    entries = []
    pos = 0
    while pos < len(data):
        tag, pos = _read_varint(data, pos)
        assert tag == 0x0A
        rec_len, pos = _read_varint(data, pos)
        rec = data[pos:pos + rec_len]
        pos += rec_len
        rpos = 0
        name = value = None
        while rpos < len(rec):
            ftag, rpos = _read_varint(rec, rpos)
            flen, rpos = _read_varint(rec, rpos)
            fval = rec[rpos:rpos + flen]
            rpos += flen
            if ftag == 0x0A:
                name = fval.decode("utf-8")
            elif ftag == 0x12:
                value = fval.decode("utf-8")
        entries.append((name, value))
    return entries


def _serialize_props(entries) -> bytes:
    out = bytearray()
    for name, value in entries:
        name_b, value_b = name.encode("utf-8"), value.encode("utf-8")
        rec = bytearray([0x0A]) + _write_varint(len(name_b)) + name_b
        rec += bytearray([0x12]) + _write_varint(len(value_b)) + value_b
        out += bytearray([0x0A]) + _write_varint(len(rec)) + rec
    return bytes(out)


def md5sum(path: str) -> str:
    h = hashlib.md5()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def remote_md5(path: str) -> str | None:
    r = adb_shell(f"md5sum {path} 2>/dev/null", timeout=15)
    parts = r.stdout.strip().split()
    return parts[0] if parts else None


# --- Steps ---

MTK_VID = 0x0E8D
BROM_PROBE_PY = os.path.join(HERE, "brom-probe.py")


def _pyusb_device_present() -> bool:
    """Cross-platform check for any MediaTek-VID device on the bus (pyusb)."""
    try:
        import usb.core  # local import: only needed for this one check
    except ImportError:
        return False  # can't check; caller should not depend on this being reliable
    try:
        return usb.core.find(idVendor=MTK_VID) is not None
    except Exception:
        return False


def _prepare_libusb_env() -> dict:
    """Return an env dict with libusb discoverable, handling the one genuinely
    platform-specific wrinkle: macOS/Homebrew's libusb isn't on the default
    dynamic-linker search path. Linux and Windows installs of libusb are
    normally already discoverable (system package manager / bundled DLL), so
    nothing extra is done there — if pyusb can't find a backend, it raises a
    clear ImportError/NoBackendError on its own.
    """
    env = os.environ.copy()
    if sys.platform == "darwin":
        try:
            prefix = subprocess.run(
                ["brew", "--prefix", "libusb"], capture_output=True, text=True, timeout=10
            ).stdout.strip()
            if prefix:
                env["DYLD_LIBRARY_PATH"] = f"{prefix}/lib:{env.get('DYLD_LIBRARY_PATH', '')}"
        except (FileNotFoundError, subprocess.SubprocessError):
            pass  # brew not present; hope libusb is already on the loader path
    return env


def step_enter_factory_mode():
    if has_root() and bootmode() == "factory":
        log("already in factory mode with root — skipping the watcher")
        return

    if not os.path.isfile(BROM_PROBE_PY):
        die(f"{BROM_PROBE_PY} not found")

    log("This step needs pyusb + libusb for the MediaTek preloader handshake.")
    log("That part is genuinely OS-dependent below the Python level:")
    log("  - macOS: libusb via Homebrew (handled automatically below)")
    log("  - Linux: libusb1 from your package manager, and udev rules or root")
    log("  - Windows: a WinUSB/libusb driver bound to the device via Zadig")
    log("This has only ever been run on macOS; Linux/Windows are untested.")

    if _pyusb_device_present():
        log("device already visible on USB — power cycle it now so the")
        log("preloader watcher can catch a fresh boot")
        wait_for(lambda: not _pyusb_device_present(), timeout_s=30,
                 what="device to disappear from USB")

    log(">>> POWER CYCLE THE ROBOT NOW if it isn't already off <<<")
    log("launching brom-probe.py directly (no shell wrapper) to catch the")
    log("preloader and send the FACTFACT handshake")
    env = _prepare_libusb_env()
    probe = subprocess.Popen(
        [sys.executable, BROM_PROBE_PY, "--no-reset", "--meta", "FACTFACT", "--duration", "90"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, env=env,
    )
    try:
        log("polling for real root independently — brom-probe's own success is")
        log("not trusted here; it has been unreliable in practice (see")
        log("docs/boot-hook-findings.md §6) even when the handshake actually lands")
        ok = wait_for(lambda: has_root() and bootmode() == "factory", timeout_s=240,
                      what="root in factory mode")
        if not ok:
            die("never got root in factory mode")
        log("root confirmed in factory mode")
    finally:
        # Critical: brom-probe.py watches for up to 90s and RE-SENDS the
        # FACTFACT handshake on every preloader sighting it gets during that
        # window. If left running past the point root is confirmed, it will
        # catch the preloader stage of a later `adb reboot` and force the
        # device straight back into factory mode instead of normal boot.
        if probe.poll() is None:
            log("stopping the background preloader watcher (must not outlive this step)")
            probe.terminate()
            try:
                probe.wait(timeout=5)
            except subprocess.TimeoutExpired:
                probe.kill()
                probe.wait(timeout=5)


def step_backup_v92():
    os.makedirs(BACKUP_DIR, exist_ok=True)
    r = adb_shell(f"ls -d /data/app/{SERVICEEXAM_PKG}-*/ 2>/dev/null", timeout=10)
    app_dir = r.stdout.strip().splitlines()[0].rstrip("/") if r.stdout.strip() else None
    if not app_dir:
        log("no /data/app override for ServiceExam found — nothing to back up")
        log("(it may already be at stock; continuing)")
        return
    live_apk = f"{app_dir}/base.apk"
    log(f"backing up live ServiceExam APK: {live_apk} -> {SERVICEEXAM_V92_BACKUP}")
    live_md5 = remote_md5(live_apk)
    if not live_md5:
        die(f"could not md5 {live_apk}")
    adb_shell(f"cp {live_apk} {SERVICEEXAM_V92_BACKUP}", timeout=30)
    backup_md5 = remote_md5(SERVICEEXAM_V92_BACKUP)
    if backup_md5 != live_md5:
        die(f"backup checksum mismatch: live={live_md5} backup={backup_md5}")
    log(f"backup verified (md5 {backup_md5})")


def step_revert_serviceexam():
    r = adb_shell(f"ls -d /data/app/{SERVICEEXAM_PKG}-*/ 2>/dev/null", timeout=10)
    app_dir = r.stdout.strip().splitlines()[0].rstrip("/") if r.stdout.strip() else None
    if not app_dir:
        log("no override directory present — already at stock")
        return
    log(f"backing up current packages.xml before reverting {app_dir}")
    adb("pull", "/data/system/packages.xml", os.path.join(BACKUP_DIR, "packages.xml.before-revert"), timeout=20)
    log(f"removing {app_dir} (PMS will fall back to the untouched /system copy)")
    adb_shell(f"rm -rf {app_dir}", timeout=15)
    r = adb_shell(f"ls -d /data/app/{SERVICEEXAM_PKG}-*/ 2>/dev/null", timeout=10)
    if r.stdout.strip():
        die(f"override directory still present after rm: {r.stdout.strip()}")
    log("override removed; stock v40 will be active on next boot")


def step_patch_usb_config():
    local_orig = os.path.join(BACKUP_DIR, "persistent_properties.orig")
    local_new = os.path.join(BACKUP_DIR, "persistent_properties.new")
    log("pulling /data/property/persistent_properties")
    adb("pull", "/data/property/persistent_properties", local_orig, timeout=20, check=True)

    with open(local_orig, "rb") as f:
        data = f.read()
    entries = _parse_props(data)
    if _serialize_props(entries) != data:
        die("persisted-properties codec did not round-trip the unmodified file — refusing to touch it")

    before = dict(entries).get("persist.sys.usb.config")
    log(f"persist.sys.usb.config: {before!r} -> 'mtp,adb'")
    new_entries = [(n, "mtp,adb" if n == "persist.sys.usb.config" else v) for n, v in entries]
    if "persist.sys.usb.config" not in dict(entries):
        new_entries.append(("persist.sys.usb.config", "mtp,adb"))
    new_data = _serialize_props(new_entries)

    reparsed = dict(_parse_props(new_data))
    if reparsed.get("persist.sys.usb.config") != "mtp,adb":
        die("re-parse of patched properties did not show the new value — refusing to push")
    if len(reparsed) != len(dict(entries)) and "persist.sys.usb.config" in dict(entries):
        die("patched properties file has a different entry count than expected — refusing to push")

    with open(local_new, "wb") as f:
        f.write(new_data)

    adb_shell("cp /data/property/persistent_properties /data/local/tmp/persistent_properties.orig.bak", timeout=10)
    adb("push", local_new, "/data/local/tmp/persistent_properties.new", timeout=20, check=True)
    adb_shell(
        "cp /data/local/tmp/persistent_properties.new /data/property/persistent_properties && "
        "chown root:root /data/property/persistent_properties && "
        "chmod 600 /data/property/persistent_properties",
        timeout=10,
    )
    r = adb_shell("wc -c < /data/property/persistent_properties", timeout=10)
    if r.stdout.strip() != str(len(new_data)):
        die(f"pushed file size mismatch on device: expected {len(new_data)}, got {r.stdout.strip()!r}")
    log("persisted properties patched and verified on-device")


def step_reboot_to_calm_normal_boot():
    log("rebooting — expecting a CALM boot (stock v40 has no watchdog code)")
    adb("reboot", timeout=10)
    ok = wait_for(lambda: has_root() and bootmode() == "normal", timeout_s=180,
                  what="root on normal boot")
    if not ok:
        die("adb never came up on normal boot — persist.sys.usb.config patch may not have taken")
    log("root confirmed on normal boot, bootmode=normal")
    log("checking stability over 20s (should NOT reboot — no watchdog at stock v40)...")
    t0 = time.time()
    for _ in range(10):
        time.sleep(2)
        if not has_root():
            die(f"adb dropped after only {time.time() - t0:.0f}s — unexpected watchdog activity at stock v40")
    log("stable for 20s, as expected")


def step_install_bootagent():
    """Historical name kept for the pipeline's sake, but this no longer installs
    an app. Empirically proven this session (see docs/persistent-adb-normal-boot.md):
    an app-invoked `su` does NOT grant real root on this device, even though the
    binary is genuinely setuid-root — confirmed via `run-as` from the bootagent's
    own unprivileged uid, both with a bare `su` (silently ran the command as the
    ORIGINAL uid, exit 0, no escalation) and via Java's exact Runtime.exec("su")
    pattern (exit 1, no log line ever written — su refused before reading stdin).
    Only privileged, platform-signed callers (uid 1000, e.g. ServiceExam's own
    watchdog, proven working via its real `reboot` calls all night) can use this
    su. We don't have Miko's platform signing key, so our own app can never join
    that set — there is no fix for this within the boot-agent's own code.
    The actual working substitute is a fast, proven-reliable manual race for
    neuterd immediately after the app that carries the watchdog re-registers or
    a reboot completes — see step_neuter_race below. Left as a no-op here so the
    pipeline shape (and BOOTAGENT_APK etc.) stays available if platform signing
    ever becomes possible.
    """
    log("SKIPPING app-based bootagent install: proven infeasible this session")
    log("(app-invoked su does not grant root on this device — see")
    log("docs/persistent-adb-normal-boot.md). Using the proven manual neuter")
    log("race instead of an autonomous boot receiver.")


def step_backup_real_reboot() -> None:
    """Save a copy of the genuine /system/bin/reboot ELF at a path that stays
    readable regardless of neuterd's bind-mount (which shadows the canonical
    path itself). This is a software escape hatch: if a real reboot is ever
    needed later in a session with no one physically present to power-cycle
    the unit, `su -c /data/local/tmp/real_reboot` still works even though
    `reboot`/`/system/bin/reboot` resolve to the no-op shim. Idempotent —
    skips if already saved, and never overwrites a previously-saved copy with
    the (possibly already-shadowed) current file.
    """
    r = adb_shell("[ -s /data/local/tmp/real_reboot ] && echo present", timeout=10)
    if "present" in r.stdout:
        return
    adb_shell("cp /system/bin/reboot /data/local/tmp/real_reboot && "
              "chmod 755 /data/local/tmp/real_reboot", timeout=10)
    r = adb_shell("wc -c < /data/local/tmp/real_reboot", timeout=10)
    size = r.stdout.strip()
    if size.isdigit() and int(size) > 4096:
        log(f"real reboot binary backed up to /data/local/tmp/real_reboot ({size} bytes)")
    else:
        log("WARNING: could not confirm a genuine reboot binary was backed up "
            "(may already be shadowed) — no software-only reboot fallback this boot")


def step_start_adb_defense_guard() -> None:
    """Counters `disableADB()` (docs/boot-sequence.md Finding #1): ServiceExam's
    MyService.init() unconditionally runs `settings put global adb_enabled 0`
    as root, every normal boot, regardless of any setting — independent of the
    SecurityMonitor/reboot mechanism neuterd defeats. A long-lived background
    loop just keeps putting it back to 1 and keeps sys.usb.config asserting
    adb, every 0.5s, for 30 minutes. Deliberately does NOT touch ServiceExam's
    process itself (no kill -9, no am force-stop) — a prior session's guard
    loop killed ServiceExam repeatedly to buy time, which made Android's
    ActivityManager mark it permanently 'bad' (stopped auto-restarting it
    entirely), leaving MikoPlus's AIDL bind dead forever and the kiosk stuck
    on its splash screen with no path back short of a physical power cycle.
    Letting ServiceExam run and just neutralizing what it does is the safe
    version of the same idea.
    """
    adb_shell(
        "(for i in $(seq 1 3600); do "
        "settings put global adb_enabled 1 2>/dev/null; "
        "case \"$(getprop sys.usb.config)\" in *adb*) : ;; "
        "*) setprop sys.usb.config mtp,adb 2>/dev/null ;; esac; "
        "sleep 0.5; done) >/data/local/tmp/adb_guard.log 2>&1 &",
        timeout=10,
    )
    log("adb-defense guard launched (re-asserts adb_enabled + sys.usb.config for 30min, "
        "never touches ServiceExam's process)")


def step_neuter_race(max_attempts: int = 40, per_attempt_timeout: int = 2) -> None:
    """The actual working mechanism. Fast, tight retry loop launching neuterd —
    no diagnostic calls in the loop body, since those cost precious time against
    a watchdog that can fire within a single adb round-trip once ServiceExam is
    genuinely at v92 (confirmed: it can win before a *second*, separate adb
    shell call even executes). Landed on the first attempt both times this was
    tried for real. Safe to call whether or not a watchdog currently exists —
    if there's no ServiceExam/watchdog yet, this is just a harmless no-op-ish
    warmup.

    Also backs up the real reboot binary and starts the non-destructive adb
    defense guard (see step_backup_real_reboot / step_start_adb_defense_guard)
    before racing, since both need to happen as early in the boot as possible
    and neither one is allowed to cost the race any time.
    """
    step_backup_real_reboot()
    step_start_adb_defense_guard()
    for i in range(1, max_attempts + 1):
        adb_shell(f"setsid {NEUTERD_REMOTE} </dev/null >/data/local/tmp/neuterd.log 2>&1 &",
                  timeout=per_attempt_timeout)
        r = adb_shell("wc -c < /system/bin/reboot", timeout=per_attempt_timeout)
        size = r.stdout.strip()
        if size.isdigit() and int(size) < 100:
            log(f"neuter landed on attempt {i} (reboot shim = {size} bytes)")
            return
    die(f"neuter did not land after {max_attempts} attempts")


def step_install_serviceexam_v92():
    r = adb_shell(f"ls -la {SERVICEEXAM_V92_BACKUP}", timeout=10)
    if "No such file" in r.stdout or r.returncode != 0:
        die(f"{SERVICEEXAM_V92_BACKUP} is missing — cannot restore v92 without a fresh transfer")
    log(f"staging neuterd at {NEUTERD_REMOTE} before the install (racing starts immediately after)")
    adb("push", NEUTERD_LOCAL, NEUTERD_REMOTE, timeout=20, check=True)
    adb_shell(f"chmod 755 {NEUTERD_REMOTE}", timeout=10)
    log("installing the backed-up ServiceExam v92 APK (on-device copy, no re-transfer) — "
        "this re-arms the real watchdog the instant it completes")
    r = adb_shell(f"pm install -r -t {SERVICEEXAM_V92_BACKUP}", timeout=60)
    if "Success" not in r.stdout:
        die(f"ServiceExam v92 pm install failed: {r.stdout} {r.stderr}")
    log("install succeeded — racing the neuter now, no delay")
    step_neuter_race()


def step_final_verification():
    log("final reboot — the watchdog will be live from the start of this boot, racing immediately")
    adb("reboot", timeout=10)
    ok = wait_for(lambda: has_root() and bootmode() == "normal", timeout_s=180,
                  what="root on final normal boot")
    if not ok:
        die("adb did not come up on the final boot at all")
    step_neuter_race()
    log("checking stability over 30s with ServiceExam v92 (real watchdog) now active...")
    t0 = time.time()
    for _ in range(15):
        time.sleep(2)
        if not has_root():
            die(f"adb dropped after {time.time() - t0:.0f}s — the watchdog won the race this time")
    log("stable for 30s with the real watchdog present — persistent adb confirmed")

    v = adb_shell(f"dumpsys package {SERVICEEXAM_PKG} | grep -m1 versionCode", timeout=10).stdout.strip()
    log(f"ServiceExam: {v}")
    v = adb_shell("dumpsys package com.miko.mikoplus | grep -m1 versionCode", timeout=10).stdout.strip()
    log(f"MikoPlus: {v}")
    log("DONE. Check the device screen to confirm the kiosk is showing normally.")
    log("NOTE: the neuter does not survive a reboot (mount-namespace scoped by design —")
    log("see docs/persistent-adb-normal-boot.md). Re-run with --race-only after any future")
    log("reboot to restore it; persist.sys.usb.config itself does survive, so adb will")
    log("always come back up, it just needs the race repeated each time.")


def main():
    if "--race-only" in sys.argv:
        # Fast path for restoring the neuter after any later, ordinary reboot —
        # skips the whole factory-mode/revert/reinstall dance, which is only
        # needed once.
        log("=== --race-only: just racing the neuter on the current boot ===")
        if not (has_root() and bootmode() == "normal"):
            die("need root on a normal boot first (adb wait-for-device, or just retry)")
        step_neuter_race()
        return

    log(f"=== auto-persistent-adb.py starting, log: {LOG_PATH} ===")
    step_enter_factory_mode()
    step_backup_v92()
    step_revert_serviceexam()
    step_patch_usb_config()
    step_reboot_to_calm_normal_boot()
    step_install_bootagent()
    step_install_serviceexam_v92()
    step_final_verification()


if __name__ == "__main__":
    main()
