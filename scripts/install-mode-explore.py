#!/usr/bin/env python3
"""
install-mode-explore.py — build, install, and launch the explore mode
(mode-explore/miko3-mode-explore.apk) over root adb, hands-off.

Then starts the mode's Activity directly, the same explicit Intent the
launcher sends. No runtime permission to grant: the mode uses neither the
camera nor the microphone. The launcher only lists Explore once it is
rebuilt with the updated mode registry (scripts/install-custom-launcher.py).

The robot is reached over TCP adb by default (root adbd on 5555, as the
other scripts assume); a serial with a colon is `adb connect`ed first. When
the device can't be reached the script stops before touching anything and
says so.

Usage:
  python3 scripts/install-mode-explore.py
  python3 scripts/install-mode-explore.py --no-build             # use the existing APK
  python3 scripts/install-mode-explore.py --serial 10.0.0.5:5555
"""
import argparse
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
APK = REPO / "mode-explore" / "miko3-mode-explore.apk"
BUILD_PY = REPO / "scripts" / "build-mode-explore.py"

PKG = "com.miko3.mode.explore"
COMPONENT = f"{PKG}/.MainActivity"
DEFAULT_SERIAL = "192.168.19.74:5555"

# A device that stops answering must fail, not hang.
ADB_TIMEOUT = 120
CONNECT_TIMEOUT = 15


class InstallError(SystemExit):
    """An install precondition or step failed; the message says what to do."""


def adb_cmd(serial, *args):
    return ["adb", "-s", serial] + list(args)


def _run(cmd, timeout):
    print("  $ " + " ".join(cmd), flush=True)
    try:
        return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    except FileNotFoundError:
        raise InstallError("!! adb not found on PATH — install Android platform-tools "
                           "(brew install android-platform-tools) and re-run.")
    except subprocess.TimeoutExpired:
        raise InstallError(f"!! adb timed out after {timeout}s: {' '.join(cmd)}\n"
                           "   The robot stopped answering. Check it is powered on and on Wi-Fi, then re-run.")


def run_step(cmd, check=True, timeout=ADB_TIMEOUT):
    """Runs a full adb command line; with check, a non-zero exit is an InstallError."""
    r = _run(cmd, timeout)
    if check and r.returncode != 0:
        raise InstallError(f"!! adb failed ({r.returncode}): {' '.join(cmd)}\n"
                           f"{(r.stdout + r.stderr).strip()}")
    return r


def install_commands(serial, apk):
    """The two device steps, in order: install, launch."""
    return [
        adb_cmd(serial, "install", "-r", "-t", str(apk)),
        adb_cmd(serial, "shell", "am", "start", "-n", COMPONENT),
    ]


def ensure_reachable(serial):
    """Connect a TCP serial, then require `get-state` to say "device"."""
    detail = ""
    if ":" in serial:
        r = _run(["adb", "connect", serial], CONNECT_TIMEOUT)
        detail = (r.stdout + r.stderr).strip()
    r = _run(adb_cmd(serial, "get-state"), CONNECT_TIMEOUT)
    if r.returncode != 0 or r.stdout.strip() != "device":
        detail = "\n".join(s for s in (detail, (r.stdout + r.stderr).strip()) if s)
        raise InstallError(
            f"!! robot not reachable over adb at {serial}\n"
            + (f"   adb said: {detail}\n" if detail else "")
            + "   Check the robot is powered on and on Wi-Fi, and that root adbd is listening\n"
              "   (python3 scripts/verify-persistent-adb.py), or pass --serial.")


def parse_args(argv=None):
    ap = argparse.ArgumentParser(description="Build, install, and launch the explore mode over root adb.")
    ap.add_argument("--serial", default=DEFAULT_SERIAL,
                    help=f"adb serial (default: {DEFAULT_SERIAL}; host:port is adb-connected first)")
    ap.add_argument("--no-build", action="store_true", help="use the existing APK instead of rebuilding")
    return ap.parse_args(argv)


def main(argv=None):
    args = parse_args(argv)

    if not args.no_build:
        print("== building mode-explore/miko3-mode-explore.apk ==", flush=True)
        r = subprocess.run([sys.executable, str(BUILD_PY)])
        if r.returncode != 0:
            raise InstallError(f"!! build failed ({r.returncode}) — see the output above")

    if not APK.exists():
        raise InstallError(f"!! {APK} not found — build it first or drop --no-build")

    print(f"== 1/3 reaching the robot at {args.serial} ==", flush=True)
    ensure_reachable(args.serial)

    install, start = install_commands(args.serial, APK)
    print(f"== 2/3 installing {APK.name} ==", flush=True)
    run_step(install)

    print(f"== 3/3 launching {COMPONENT} ==", flush=True)
    out = run_step(start).stdout
    if "Error" in out:
        raise InstallError(f"!! am start failed:\n{out.strip()}")

    host = args.serial.split(":")[0] if ":" in args.serial else "<robot-ip>"
    print(f"""
Done. {PKG} is installed and running.

Eyes page and state: http://{host}:8083/ and http://{host}:8083/state
Exit through the launcher (http://{host}:8080/).
""")
    return 0


if __name__ == "__main__":
    sys.exit(main())
