#!/usr/bin/env python3
"""recover-camera.py — detect and recover from a wedged camera HAL on the
robot, escalating through progressively more disruptive fixes instead of
requiring a human to manually diagnose the same failure each time.

Root cause this targets (confirmed live, 2026-09-15): repeatedly force-stopping
mode-remote-control while a CameraCapture session is open (as happens
routinely during iterative on-device testing) can leave the camera HAL in a
state where CameraDevice.createCaptureSession() fails with
CAMERA_ERROR/"Error configuring streams: Broken pipe (-32)" -- confirmed via
`dumpsys media.camera` showing the camera provider itself flag the device as
removed ("Device status changed from 1 to 0") right after a failed session.
This is a HAL/driver-level wedge, not anything wrong in this repo's own
camera code -- CameraCapture.java hasn't changed since the camera was last
confirmed working.

Escalation, each step re-checked before trying the next:
  1. Check health: does /stream.mjpeg actually deliver JPEG bytes, not just a
     200 response? (A 200 with a still-open connection but zero bytes is
     exactly this failure mode -- ModeApp's cameraError flag hasn't yet been
     set at request time, but the async capture-session callback fails moments
     later, so a plain status-code check is not sufficient.)
  2. Restart cameraserver + camerahalserver (kill -9; init restarts them) and
     relaunch mode-remote-control. Confirmed live: this sometimes reconnects
     the HAL's camera provider but does NOT reliably clear the wedge -- kept
     as the fast, low-disruption first attempt since it costs a few seconds
     when it works.
  3. Reboot the whole device. Confirmed as the reliable fix when step 2 alone
     doesn't clear it. Far more disruptive (~1-2 minutes, interrupts anything
     else running on the robot), so only tried after step 2 fails.

Usage:
  python3 scripts/recover-camera.py                 # check + recover if needed
  python3 scripts/recover-camera.py --check-only     # just report health, exit
  python3 scripts/recover-camera.py --force-reboot   # skip straight to reboot
"""
import argparse
import socket
import ssl
import subprocess
import sys
import time

APP_PACKAGE = "com.miko3.mode.remotecontrol"
APP_ACTIVITY = f"{APP_PACKAGE}/.MainActivity"


def adb(serial: str, *args: str, check=True, capture=True):
    return subprocess.run(["adb", "-s", serial, *args], check=check,
                           capture_output=capture, text=True)


def camera_streaming(host: str, port: int, timeout: float = 4.0) -> bool:
    """True if /stream.mjpeg actually delivers frame bytes within `timeout`,
    not just a 200 status line -- see module docstring for why a status-code
    check alone is misleading here."""
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    try:
        with socket.create_connection((host, port), timeout=timeout) as raw:
            raw.settimeout(timeout)
            with ctx.wrap_socket(raw, server_hostname=host) as sock:
                sock.sendall(b"GET /stream.mjpeg HTTP/1.1\r\nHost: "
                             + host.encode() + b"\r\nConnection: close\r\n\r\n")
                deadline = time.time() + timeout
                header_done = False
                buf = b""
                while time.time() < deadline:
                    try:
                        chunk = sock.recv(4096)
                    except socket.timeout:
                        break
                    if not chunk:
                        break
                    buf += chunk
                    if not header_done:
                        if b"\r\n\r\n" not in buf:
                            continue
                        header, buf = buf.split(b"\r\n\r\n", 1)
                        if b" 200 " not in header.split(b"\r\n", 1)[0]:
                            return False
                        header_done = True
                    if len(buf) > 0:
                        return True
                return False
    except (OSError, ssl.SSLError):
        return False


def restart_camera_services(serial: str):
    print("  restarting cameraserver + camerahalserver...")
    pids = adb(serial, "shell", "pidof", "cameraserver", "camerahalserver",
               check=False).stdout.strip()
    if pids:
        adb(serial, "shell", "kill", "-9", *pids.split(), check=False)
    time.sleep(2)


def relaunch_app(serial: str):
    print("  relaunching mode-remote-control...")
    adb(serial, "shell", "am", "force-stop", APP_PACKAGE, check=False)
    time.sleep(1)
    adb(serial, "shell", "am", "start", "-n", APP_ACTIVITY, check=False)
    time.sleep(3)


def reboot_and_wait(serial: str):
    print("  rebooting device (this takes roughly 1-2 minutes)...")
    adb(serial, "reboot", check=False)
    time.sleep(5)
    adb(serial, "wait-for-device", check=False, capture=False)
    # wait-for-device only means the USB/TCP transport is back, not that
    # Android has finished booting -- poll sys.boot_completed too.
    for _ in range(60):
        result = adb(serial, "shell", "getprop", "sys.boot_completed", check=False)
        if result.stdout.strip() == "1":
            break
        time.sleep(2)
    time.sleep(5)
    relaunch_app(serial)


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                  formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--serial", default="192.168.19.74:5555")
    ap.add_argument("--host", default="192.168.19.74")
    ap.add_argument("--port", type=int, default=8444)
    ap.add_argument("--check-only", action="store_true",
                     help="just report whether the camera is healthy, don't fix anything")
    ap.add_argument("--force-reboot", action="store_true",
                     help="skip the cameraserver-restart step and reboot immediately")
    args = ap.parse_args()

    print("Checking camera health...")
    if camera_streaming(args.host, args.port):
        print("OK: camera is streaming frames.")
        return 0
    print("BROKEN: /stream.mjpeg is not delivering frame data.")

    if args.check_only:
        return 1

    if not args.force_reboot:
        print("Step 1/2: restart camera services + relaunch app")
        restart_camera_services(args.serial)
        relaunch_app(args.serial)
        if camera_streaming(args.host, args.port):
            print("OK: camera recovered after service restart.")
            return 0
        print("Still broken after service restart.")

    print("Step 2/2: full device reboot")
    reboot_and_wait(args.serial)
    if camera_streaming(args.host, args.port):
        print("OK: camera recovered after reboot.")
        return 0
    print("STILL BROKEN after reboot -- this needs manual investigation, "
          "not just a retry (check dumpsys media.camera, physical camera "
          "hardware, or a possible ribbon-cable/connector fault).")
    return 1


if __name__ == "__main__":
    sys.exit(main())
