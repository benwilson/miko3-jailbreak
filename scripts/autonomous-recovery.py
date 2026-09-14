#!/usr/bin/env python3
"""
autonomous-recovery.py — single standalone script for the whole
persistent-root cycle, including forcing the MediaTek preloader into factory
mode itself. Run it once, leave it running, then just power-cycle the robot —
nothing else needs to happen by hand.

It reuses scripts/brom-probe.py's handshake code in-process (imported, not
shelled out to) so the same tight polling loop can watch for the preloader
stage AND drive the adb-based recovery/race logic without any separate
process to launch, babysit, or forget to kill.

State machine, continuously re-evaluated:

  nothing on the bus at all      -> wait (covers reboots, the ~30-33s hardware
                                     watchdog reset, USB re-enumeration, etc.)

  MediaTek preloader/BROM stage visible on raw USB (before Android boots)
                                     -> if `repair_needed` is armed, send the
                                     FACTFACT meta-mode handshake immediately
                                     (20ms polling, matching brom-probe.py's
                                     own cadence — the preloader stage only
                                     lasts ~2.3s). If not armed, do nothing
                                     and let it boot through normally, so the
                                     race below gets its chance.

  Android up, ro.bootmode=factory   -> confirm root, patch
                                     persist.sys.usb.config to "adb" if
                                     needed, verify the Route A race payload
                                     is staged at /data/local/tmp/*, then
                                     disarm repair_needed and reboot to
                                     normal.

  Android up, adb reachable, bootmode=normal (or unset early in boot)
                                     -> immediately fire the nsinject race: a
                                     single remote-shell command that
                                     busy-polls `pidof com.miko.launcher_app`
                                     (no ActivityManager dependency) and
                                     injects the moment the launcher's first
                                     natural pid appears, then checks for
                                     evidence (/system/bin/reboot size,
                                     miko3-hook.log).

  Android up, adb NOT reachable     -> this is the failure mode found on
                                     2026-09-13: kiosk boots fully but
                                     ServiceExam's lockdown (or something
                                     else) keeps sys.usb.config from ever
                                     including "adb", so the persisted
                                     property patch alone isn't enough this
                                     boot. Arms repair_needed so the *next*
                                     preloader sighting is forced into
                                     factory mode for a fresh repair attempt.

Requires pyusb + libusb for the preloader-forcing half (same dependency as
brom-probe.py). If unavailable, that half is skipped with a clear one-time
warning and the script falls back to pure adb-based race/factory handling —
getting into factory mode from a cold boot then needs a manual
scripts/brom-probe.py run, same as before this consolidation.

Usage: python3 scripts/autonomous-recovery.py [--once] [--no-force-factory]
  --once               exit after the first full cycle instead of looping
  --no-force-factory   never send the FACTFACT handshake even if armed
                        (pure observe-and-race mode)
"""
from __future__ import annotations

import datetime
import glob
import importlib.util
import json
import os
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)

RUN_TS = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
LOG_DIR = os.path.join(REPO, "recon", "captures")
os.makedirs(LOG_DIR, exist_ok=True)
TEXT_LOG_PATH = os.path.join(LOG_DIR, f"autonomous-recovery-{RUN_TS}.log")
EVENT_LOG_PATH = os.path.join(LOG_DIR, f"autonomous-recovery-{RUN_TS}.jsonl")

# Running counters for the end-of-run / periodic analytics summary.
COUNTERS = {
    "cycles_absent": 0,
    "preloader_sightings": 0,
    "handshake_attempts": 0,
    "handshake_successes": 0,
    "factory_entries": 0,
    "factory_repairs_completed": 0,
    "android_no_adb_events": 0,
    "race_attempts": 0,
    "race_successes": 0,
    "race_no_pid_found": 0,
}

# Timestamp of the last stage transition, for stage-duration logging.
_STAGE_STARTED_AT = {"t": time.time(), "stage": "init"}

NSINJECT_LOCAL = os.path.join(REPO, "bootagent", "native", "nsinject")
NEUTERD_LOCAL = os.path.join(REPO, "bootagent", "native", "neuterd")
STAGED_CACHE_DIR = os.path.join(REPO, "scripts", "route-a-cache")
STAGED_FILES = {
    "/data/local/tmp/nsinject": NSINJECT_LOCAL,
    "/data/local/tmp/neuterd": NEUTERD_LOCAL,
    "/data/local/tmp/1_miko3.l": os.path.join(STAGED_CACHE_DIR, "1_miko3.l"),
    "/data/local/tmp/route_a_payload.sh": os.path.join(STAGED_CACHE_DIR, "route_a_payload.sh"),
    "/data/local/tmp/route_a_neuterd": os.path.join(STAGED_CACHE_DIR, "route_a_neuterd"),
}
MIN_SIZES = {
    "/data/local/tmp/nsinject": 1000,
    "/data/local/tmp/neuterd": 500,
    "/data/local/tmp/1_miko3.l": 10,
    "/data/local/tmp/route_a_payload.sh": 100,
    "/data/local/tmp/route_a_neuterd": 500,
}

# 2026-09-14 finding (docs/boot-sequence.md): the ~30s "crash" is NOT a
# hardware watchdog. ServiceExam v92's SecurityMonitor polls `ps -ef` for
# adbd every 2s starting immediately and shells out `reboot` via su the
# instant it sees it; separately, SocialInteraction_SpeechChat.init()
# unconditionally calls disableADB() every boot regardless of any setting.
# Both are counterable by re-asserting settings/props, without needing to
# touch ServiceExam's own process — so keep re-asserting adb for the WHOLE
# window instead of racing a deadline. This removes the time pressure on the
# app-mediated Route A chain (launcher -> update_app -> payload.sh as root)
# entirely, rather than just shaving milliseconds off the injection race.
#
# 2026-09-14 correction: this guard previously also did `kill -9 $P` on
# ServiceExam's pid every time it (re)appeared, on the theory that a dead
# watchdog can't reboot anything. It can't — but Android's ActivityManager
# treats a handful of near-immediate deaths as a crash loop and marks the
# process permanently "bad", after which it stops auto-restarting the
# service at all. MikoPlus's AIDL bind to ServiceExam's MyService then never
# succeeds, ever, and the kiosk sits on its splash screen forever with no
# path back except a physical power cycle. Confirmed live this session
# (dumpsys activity services showed the connection stuck DEAD with no further
# restart attempts scheduled). Neutering `reboot` and re-asserting
# adb_enabled/sys.usb.config is sufficient on its own — ServiceExam is left
# completely alone here now.
#
# 2026-09-14 second correction: this used to wait for com.miko.launcher_app's
# pid and then run `nsinject` — the Route A trick of writing files into the
# launcher's own private mount namespace so its privileged su-capable
# processInstall() would (indirectly) start a neuterd for us. That's dead
# weight now: docs/persistent-adb-normal-boot.md's later discovery is that
# OUR OWN adb shell already has genuine root (via the factory-mode +
# persist.sys.usb.config path this daemon itself sets up), so neuterd can be
# launched directly, immediately, with no need to wait for any app process
# to appear at all. Waiting on the launcher pid was pure lost time against a
# watchdog with a zero-initial-delay first tick — confirmed live this
# session: nsinject kept reporting rc=0 but REBOOT_NEUTERED never appeared
# before the watchdog rebooted the device. Launching neuterd directly is the
# same mechanism scripts/auto-persistent-adb.py's step_neuter_race already
# proved reliable (landed on the first attempt, twice).
#
# 2026-09-14 third correction: re-asserting `settings put global adb_enabled
# 1` in a loop to fight disableADB() was NOT reliable in practice — confirmed
# live, twice, with an operator physically present to verify: adb stayed
# down after disableADB() fired regardless of the guard loop running. Best
# guess is the USB gadget's re-enumeration after toggling that setting takes
# longer than our re-assertion interval, so the loop kept interrupting its
# own recovery. Rather than keep tuning that race with no way to verify
# results overnight (no one present to power-cycle on a bad guess), this
# guard now does something guaranteed to make forward progress instead:
# the moment it sees adb_enabled get forced back to 0 (disableADB() firing),
# it deliberately reboots the device itself, via a backed-up copy of the
# genuine reboot binary saved before neuterd shadows the canonical path
# (`/data/local/tmp/real_reboot` — same escape-hatch idea as
# auto-persistent-adb.py's step_backup_real_reboot, now also wired in here).
# That reboot is caught by this same daemon's own preloader-watching loop
# (repair_needed re-arms the instant normal boot is confirmed — see below),
# forcing factory mode again with no human involved. Net effect: instead of
# one attempt per human-triggered power cycle, this runs an unattended
# fresh-attempt loop roughly every 30-40s, forever, until something actually
# keeps adb up past this point.
RACE_SCRIPT = r'''
[ -s /data/local/tmp/real_reboot ] || cp /system/bin/reboot /data/local/tmp/real_reboot
chmod 755 /data/local/tmp/real_reboot 2>/dev/null

setsid /data/local/tmp/neuterd </dev/null >/data/local/tmp/neuterd.log 2>&1 &
echo "RACE neuterd_launch_rc=$? uptime=$(cat /proc/uptime)"

setsid sh -c '
  sleep 3
  for i in $(seq 1 300); do
    cur=$(settings get global adb_enabled 2>/dev/null)
    if [ "$cur" = "0" ]; then
      echo "$(date +%s) adb_enabled=$cur -- disableADB fired, self-rebooting via real_reboot" >> /data/local/tmp/adb_guard.log
      /data/local/tmp/real_reboot
      exit 0
    fi
    sleep 1
  done
' </dev/null >/data/local/tmp/adb_guard_stdout.log 2>&1 &
echo "RACE guard_launch_rc=$? uptime=$(cat /proc/uptime)"

for i in $(seq 1 25); do
  sleep 1
  SZ=$(wc -c < /system/bin/reboot 2>/dev/null)
  if [ -n "$SZ" ] && [ "$SZ" -lt 4096 ] 2>/dev/null; then
    echo "REBOOT_NEUTERED size=$SZ at t=$(cat /proc/uptime)"
    break
  fi
done

echo "RACE_DONE uptime=$(cat /proc/uptime)"
'''

EVIDENCE_SCRIPT = r'''
echo "---hooklog---"
cat /data/local/tmp/miko3-hook.log 2>&1
echo "---rebootsize---"
wc -c < /system/bin/reboot 2>&1
'''

FAST_POLL = 0.02       # raw-USB polling cadence; matches brom-probe.py
ADB_POLL_INTERVAL = 0.3  # `adb devices` is a subprocess spawn — don't do it every 20ms
META_MODE = "FACTFACT"


def log(msg: str) -> None:
    line = f"[{datetime.datetime.now().strftime('%H:%M:%S')}] {msg}"
    print(line, flush=True)
    with open(TEXT_LOG_PATH, "a") as f:
        f.write(line + "\n")


def log_event(kind: str, **fields) -> None:
    """Structured JSONL event, one per line, for later analytics (success
    rates, stage durations, handshake attempt counts, etc.) without having to
    scrape the human-readable log."""
    rec = {"ts": time.time(), "iso": datetime.datetime.now(datetime.timezone.utc).isoformat(), "kind": kind, **fields}
    with open(EVENT_LOG_PATH, "a") as f:
        f.write(json.dumps(rec) + "\n")


def enter_stage(stage: str, **fields) -> None:
    """Log a stage transition with how long the previous stage lasted."""
    prev = _STAGE_STARTED_AT["stage"]
    dur = time.time() - _STAGE_STARTED_AT["t"]
    log_event("stage_transition", from_stage=prev, to_stage=stage, prev_duration_s=round(dur, 2), **fields)
    _STAGE_STARTED_AT["stage"] = stage
    _STAGE_STARTED_AT["t"] = time.time()


def log_summary() -> None:
    log(f"SUMMARY: {COUNTERS}")
    log_event("summary", counters=dict(COUNTERS))


def run(args, timeout=10):
    try:
        r = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
        return r.returncode, r.stdout, r.stderr
    except subprocess.TimeoutExpired:
        return -1, "", "timeout"


# --- in-process libusb/brom-probe setup -------------------------------------

def _prepare_libusb_env_inprocess() -> None:
    """macOS/Homebrew's libusb isn't on the default dynamic-linker search
    path; dyld reads DYLD_LIBRARY_PATH on each dlopen, so setting it here
    (before usb.core's backend is first touched) is enough — no subprocess
    re-exec needed. Linux/Windows libusb installs are normally already
    discoverable and are left alone."""
    if sys.platform == "darwin":
        try:
            r = subprocess.run(["brew", "--prefix", "libusb"], capture_output=True, text=True, timeout=10)
            prefix = r.stdout.strip()
            if prefix:
                os.environ["DYLD_LIBRARY_PATH"] = f"{prefix}/lib:" + os.environ.get("DYLD_LIBRARY_PATH", "")
        except (FileNotFoundError, subprocess.SubprocessError):
            pass


def _load_brom_probe():
    """Import brom-probe.py as a module in-process (it's a hyphenated
    filename, so importlib by path rather than a normal `import`) to reuse
    its handshake logic without shelling out to a separate process — that
    was the source of the earlier "leftover probe subprocess re-triggers
    FACTFACT on our own reboot" bug, which is structurally impossible now
    since there's no child process to forget to kill."""
    spec = importlib.util.spec_from_file_location("brom_probe", os.path.join(HERE, "brom-probe.py"))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


_prepare_libusb_env_inprocess()
try:
    BROM = _load_brom_probe()
    HAVE_PYUSB = True
except Exception as e:  # noqa: BLE001 — genuinely want to degrade gracefully on any import failure
    BROM = None
    HAVE_PYUSB = False
    _PYUSB_IMPORT_ERROR = str(e)


def get_raw_mtk_pid():
    if not HAVE_PYUSB:
        return None
    try:
        dev = BROM.find()
        return None if dev is None else dev.idProduct
    except Exception:
        return None


def refresh_serial_baseline():
    return sorted(glob.glob("/dev/cu.usb*") + glob.glob("/dev/tty.usb*") + glob.glob("/dev/cu.MTK*"))


# --- adb helpers --------------------------------------------------------

def list_online_devices():
    rc, out, err = run(["adb", "devices"], timeout=5)
    devs = []
    for line in out.splitlines()[1:]:
        line = line.strip()
        if not line:
            continue
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            devs.append(parts[0])
    return devs


def adb_shell(serial, cmd, timeout=10):
    return run(["adb", "-s", serial, "shell", cmd], timeout=timeout)


def get_bootmode(serial):
    rc, out, err = adb_shell(serial, "getprop ro.bootmode", timeout=5)
    return out.strip()


def has_root(serial):
    rc, out, err = adb_shell(serial, "id", timeout=5)
    return rc == 0 and "uid=0" in out


# --- persisted-properties codec (verified round-trip byte-identical; see
# docs/persistent-adb-normal-boot.md) ---

def _read_varint(buf, pos):
    result, shift = 0, 0
    while True:
        b = buf[pos]
        pos += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80):
            break
        shift += 7
    return result, pos


def _write_varint(n):
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


def _parse_props(data):
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


def _serialize_props(entries):
    out = bytearray()
    for name, value in entries:
        name_b, value_b = name.encode("utf-8"), value.encode("utf-8")
        rec = bytearray([0x0A]) + _write_varint(len(name_b)) + name_b
        rec += bytearray([0x12]) + _write_varint(len(value_b)) + value_b
        out += bytearray([0x0A]) + _write_varint(len(rec)) + rec
    return bytes(out)


def ensure_usb_config(serial, work_dir):
    rc, out, err = adb_shell(serial, "getprop persist.sys.usb.config", timeout=5)
    current = out.strip()
    if current == "adb":
        log("persist.sys.usb.config already 'adb' — no patch needed")
        return True

    log(f"persist.sys.usb.config is {current!r} — patching to 'adb'")
    local_orig = os.path.join(work_dir, "persistent_properties.orig")
    local_new = os.path.join(work_dir, "persistent_properties.new")
    rc, out, err = run(["adb", "-s", serial, "pull", "/data/property/persistent_properties", local_orig], timeout=20)
    if rc != 0:
        log(f"FAILED to pull persistent_properties: {err.strip()}")
        return False

    with open(local_orig, "rb") as f:
        data = f.read()
    entries = _parse_props(data)
    if _serialize_props(entries) != data:
        log("codec did not round-trip the unmodified file — refusing to touch it")
        return False

    new_entries = [(n, "adb" if n == "persist.sys.usb.config" else v) for n, v in entries]
    if "persist.sys.usb.config" not in dict(entries):
        new_entries.append(("persist.sys.usb.config", "adb"))
    new_data = _serialize_props(new_entries)

    reparsed = dict(_parse_props(new_data))
    if reparsed.get("persist.sys.usb.config") != "adb":
        log("re-parse of patched properties did not show the new value — refusing to push")
        return False

    with open(local_new, "wb") as f:
        f.write(new_data)

    run(["adb", "-s", serial, "push", local_new, "/data/local/tmp/persistent_properties.new"], timeout=20)
    adb_shell(
        serial,
        "cp /data/local/tmp/persistent_properties.new /data/property/persistent_properties && "
        "chown root:root /data/property/persistent_properties && "
        "chmod 600 /data/property/persistent_properties",
        timeout=10,
    )
    rc, out, err = adb_shell(serial, "wc -c < /data/property/persistent_properties", timeout=10)
    if out.strip() != str(len(new_data)):
        log(f"pushed file size mismatch on device: expected {len(new_data)}, got {out.strip()!r}")
        return False
    log("persist.sys.usb.config patched and verified on-device")
    return True


def ensure_staged_files(serial):
    os.makedirs(STAGED_CACHE_DIR, exist_ok=True)
    all_ok = True
    for remote, local in STAGED_FILES.items():
        min_size = MIN_SIZES[remote]
        rc, out, err = adb_shell(serial, f"wc -c < {remote} 2>/dev/null", timeout=5)
        size = int(out.strip()) if out.strip().isdigit() else 0
        if size >= min_size:
            if local and local.startswith(STAGED_CACHE_DIR) and not os.path.isfile(local):
                log(f"caching {remote} -> {local} (no local backup existed yet)")
                run(["adb", "-s", serial, "pull", remote, local], timeout=20)
            continue
        log(f"{remote} missing/short (size={size}, want >={min_size})")
        if local and os.path.isfile(local):
            log(f"  re-pushing from {local}")
            run(["adb", "-s", serial, "push", local, remote], timeout=20)
            adb_shell(serial, f"chmod 755 {remote}", timeout=5)
            rc, out, err = adb_shell(serial, f"wc -c < {remote} 2>/dev/null", timeout=5)
            size = int(out.strip()) if out.strip().isdigit() else 0
            if size < min_size:
                log(f"  still short after push ({size}) — giving up on this file")
                all_ok = False
        else:
            log(f"  no local source known for {remote} — cannot auto-repair; race may fail")
            all_ok = False
    return all_ok


def handle_factory_mode(serial, work_dir):
    if not has_root(serial):
        log("in factory mode but no root yet — waiting")
        log_event("factory_prep", serial=serial, root=False)
        return False
    usb_ok = ensure_usb_config(serial, work_dir)
    files_ok = ensure_staged_files(serial)
    log_event("factory_prep", serial=serial, root=True, usb_ok=usb_ok, files_ok=files_ok)
    if not (usb_ok and files_ok):
        log("factory-mode prep incomplete — NOT rebooting yet, will retry")
        return False
    log("factory-mode prep complete (usb config + staged files) — rebooting to normal")
    COUNTERS["factory_repairs_completed"] += 1
    run(["adb", "-s", serial, "reboot"], timeout=10)
    return True


def _parse_race_output(out: str) -> dict:
    """Pull neuterd_launch_rc/uptime out of RACE_SCRIPT's stdout for
    structured logging (whether the direct `setsid neuterd &` launch itself
    reported success, and at what device uptime)."""
    fields = {}
    for line in out.splitlines():
        line = line.strip()
        if line.startswith("RACE neuterd_launch_rc="):
            for tok in line.split():
                if "=" in tok:
                    k, v = tok.split("=", 1)
                    fields[k] = v
    return fields


def do_race(serial):
    log(f"NORMAL BOOT detected on {serial} -- firing race now")
    COUNTERS["race_attempts"] += 1
    t0 = time.time()
    rc, out, err = run(["adb", "-s", serial, "shell", RACE_SCRIPT], timeout=60)
    race_wall_s = round(time.time() - t0, 2)
    for line in out.strip().splitlines():
        log(f"  race: {line}")
    if err.strip():
        log(f"  race stderr: {err.strip()}")

    race_fields = _parse_race_output(out)
    if not race_fields.get("neuterd_launch_rc"):
        COUNTERS["race_no_pid_found"] += 1
    log_event("race_attempt", serial=serial, race_wall_s=race_wall_s,
               adb_rc=rc, stderr=err.strip()[:500], **race_fields)

    rc2, out2, err2 = run(["adb", "-s", serial, "shell", EVIDENCE_SCRIPT], timeout=5)
    for line in out2.strip().splitlines():
        log(f"  evidence: {line}")

    lines = out2.strip().splitlines()
    reboot_size = None
    if lines:
        tail = lines[-1].strip()
        if tail.isdigit():
            reboot_size = int(tail)

    success = reboot_size is not None and 0 < reboot_size < 4096
    log_event("race_evidence", serial=serial, reboot_size=reboot_size, success=success,
               hook_log_present="miko3" in out2.lower())
    if success:
        COUNTERS["race_successes"] += 1
        log(f"!!! POSSIBLE SUCCESS: /system/bin/reboot is only {reboot_size} bytes (looks neutered) !!!")
    log_summary()
    return success


def main():
    once = "--once" in sys.argv
    force_factory_enabled = "--no-force-factory" not in sys.argv

    ts = datetime.datetime.utcnow().strftime("%Y%m%dT%H%M%SZ")
    work_dir = os.path.join(REPO, "firmware", "agent-backups", f"autonomous-recovery-{ts}")
    os.makedirs(work_dir, exist_ok=True)

    log("autonomous-recovery daemon starting — fully self-driving, no state narration needed")
    log(f"text log: {TEXT_LOG_PATH}")
    log(f"event log (jsonl): {EVENT_LOG_PATH}")
    log_event("daemon_start", have_pyusb=HAVE_PYUSB, once=once, force_factory_enabled=force_factory_enabled)
    if HAVE_PYUSB:
        log("pyusb/libusb available — can force factory mode itself via the preloader handshake")
    else:
        log(f"pyusb/libusb NOT available ({_PYUSB_IMPORT_ERROR}) — cannot force factory mode; "
            "run scripts/brom-probe.py manually to get root the first time")

    # Start armed: the last known state (2026-09-13) was a kiosk boot with no
    # adb reachable at all, so the next preloader sighting should be forced.
    repair_needed = True
    stage = "init"
    serial_baseline: list[str] = []
    attempt_n = 0
    raced_this_cycle = False
    online: list[str] = []
    last_adb_poll = 0.0
    last_heartbeat = 0.0
    android_pid = BROM.ANDROID_PID if HAVE_PYUSB else None

    while True:
        now = time.time()
        raw_pid = get_raw_mtk_pid()

        if now - last_heartbeat >= 30:
            log_event("heartbeat", stage=stage, repair_needed=repair_needed, counters=dict(COUNTERS))
            last_heartbeat = now

        if now - last_adb_poll >= ADB_POLL_INTERVAL:
            online = list_online_devices()
            last_adb_poll = now

        # Nothing on the bus at all.
        if raw_pid is None and not online:
            if stage != "absent":
                log("device absent (offline/rebooting/watchdog reset)")
                COUNTERS["cycles_absent"] += 1
                enter_stage("absent")
                stage = "absent"
                raced_this_cycle = False
            time.sleep(FAST_POLL)
            continue

        # Preloader/BROM stage — before Android has booted. Only meaningful
        # if we can actually see it via pyusb; adb never sees this stage.
        if HAVE_PYUSB and raw_pid is not None and raw_pid != android_pid and not online:
            if stage != "preloader":
                log(f"preloader/BROM stage detected (pid={hex(raw_pid)}), "
                    f"repair_needed={repair_needed}")
                COUNTERS["preloader_sightings"] += 1
                enter_stage("preloader", raw_pid=hex(raw_pid), repair_needed=repair_needed)
                stage = "preloader"
                attempt_n = 0
                BROM.T0 = time.time()
            if repair_needed and force_factory_enabled:
                attempt_n += 1
                COUNTERS["handshake_attempts"] += 1
                try:
                    dev = BROM.find()
                    if dev is not None:
                        ok = BROM.attempt(dev, attempt_n, serial_baseline, META_MODE)
                        log_event("handshake_attempt", attempt_n=attempt_n, ok=ok)
                        if ok:
                            COUNTERS["handshake_successes"] += 1
                            log("FACTFACT handshake accepted — expecting factory-mode boot")
                except Exception as e:  # noqa: BLE001
                    log(f"handshake attempt error: {e}")
                    log_event("handshake_attempt", attempt_n=attempt_n, error=str(e))
            time.sleep(FAST_POLL)
            continue

        # Android is up (adb reachable, or at least enumerating as the
        # Android-stage USB PID even if adb isn't).
        if online:
            serial = online[0]
            serial_baseline = refresh_serial_baseline()
            bootmode = get_bootmode(serial)

            if bootmode == "factory":
                if stage != "factory":
                    log(f"device {serial} in FACTORY mode")
                    COUNTERS["factory_entries"] += 1
                    enter_stage("factory", serial=serial)
                    stage = "factory"
                rebooted = handle_factory_mode(serial, work_dir)
                if rebooted:
                    # 2026-09-14: disarm for exactly this one transition. The
                    # reboot we just triggered ourselves has to be allowed to
                    # pass through the preloader stage uncontested or it can
                    # never reach normal boot at all — re-forcing FACTFACT on
                    # our own deliberate reboot would loop forever in factory
                    # mode. re-armed the instant normal boot is confirmed
                    # below, so this window covers only this one pass-through.
                    repair_needed = False
                    if once:
                        log("--once specified, exiting after factory-mode reboot")
                        log_summary()
                        return
                    stage = "absent"
                    time.sleep(3)
                else:
                    time.sleep(1)
                continue

            if stage != "normal":
                log(f"device {serial} present, bootmode='{bootmode}'")
                enter_stage("normal", serial=serial, bootmode=bootmode)
                stage = "normal"
                # Re-arm immediately: the one deliberate pass-through is used
                # up the moment we confirm we're actually on a normal boot.
                # Any reboot from here on — watchdog, crash, anything — gets
                # caught at the next preloader sighting and forced back to
                # factory mode, with no one present to power-cycle instead.
                repair_needed = True

            if not raced_this_cycle:
                raced_this_cycle = True
                success = do_race(serial)
                if success:
                    # 2026-09-14: do NOT stop here. A neutered `reboot` only
                    # defeats SecurityMonitor; it does nothing about
                    # disableADB() (unconditional, fires later in
                    # ServiceExam's init() once it reaches that point) or the
                    # unconfirmed usb1/authorized deauth line. Both can drop
                    # adb — or the whole USB link — with no reboot at all,
                    # invisibly to this check. With no one present to
                    # power-cycle on a bad outcome, this daemon must keep
                    # watching for the rest of the session so it can force
                    # factory mode again the moment it sees anything go
                    # wrong, rather than declaring victory and exiting.
                    log("neuter confirmed — continuing to watch (not exiting; "
                        "disableADB()/usb-deauth can still drop adb later this boot)")
                if once:
                    log("--once specified, exiting after first race")
                    log_summary()
                    return
            time.sleep(FAST_POLL)
            continue

        # raw_pid == android_pid but adb not (yet/ever) reachable this boot.
        if stage != "android_no_adb":
            log("Android userspace up but adb NOT reachable this boot — "
                "arming repair_needed for the next preloader sighting")
            COUNTERS["android_no_adb_events"] += 1
            enter_stage("android_no_adb", raw_pid=hex(raw_pid) if raw_pid else None)
            stage = "android_no_adb"
            repair_needed = True
            serial_baseline = refresh_serial_baseline()
        time.sleep(FAST_POLL)


if __name__ == "__main__":
    main()
