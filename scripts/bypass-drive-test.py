#!/usr/bin/env python3
"""bypass-drive-test.py — drive the wheels directly over /dev/ttyS2, bypassing
ModeApp/DriveController/the WebSocket/the JS control page entirely, so a
sustained-forward test can be attributed to the motor/firmware layer alone.

Replicates shared/src/com/miko3/shared/DirectMotorDriver.java's exact wire
format (POWER keepalive frame + VEL1 "kick+sustain" frame, see that class's
own field comments) via raw adb shell writes to /dev/ttyS2, with the
mode-remote-control app force-stopped first so nothing else holds the fd.
"""
import argparse
import struct
import subprocess
import sys

FRAME_SIZE = 100
POWER_FRAME_SIZE = 500
PAD = b"X"


def tagged_frame(tag: bytes, size: int) -> bytes:
    return tag + PAD * (size - len(tag))


def u3(value: int) -> bytes:
    return struct.pack(">I", value)[1:]


def s3(value: int) -> bytes:
    magnitude = abs(value)
    sign = 1 if value > 0 else (2 if value < 0 else 0)
    return struct.pack(">H", magnitude) + bytes([sign])


def build_sustained_frame(linear: int, angular: int, frame_type: int = 24,
                           t1: int = 5, t2: int = 10) -> bytes:
    """Default t1/t2/frame_type match DirectMotorDriver.buildSustainedFrame()
    (type=24, 50ms+100ms). Pass frame_type=25, t1=40, t2=2 to replicate the
    on-device Explore/Linear.txt idle-mode expression exactly."""
    body = b"VEL1="
    body += u3(4)   # MotionMsg type
    body += u3(39)  # datasize: 15-byte header + 2x12-byte frames
    body += u3(4)   # motion_type
    body += u3(1)   # loop
    body += u3(2)   # seqCount
    body += s3(linear) + s3(angular) + u3(t1) + u3(frame_type)
    body += s3(linear) + s3(angular) + u3(t2) + u3(frame_type)
    body += PAD * (FRAME_SIZE - len(body))
    return body


def adb(serial: str, *args: str, check=True) -> subprocess.CompletedProcess:
    return subprocess.run(["adb", "-s", serial, *args], check=check,
                           capture_output=True, text=False)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default="192.168.19.74:5555")
    ap.add_argument("--seconds", type=int, default=25)
    ap.add_argument("--linear", type=int, default=20)
    ap.add_argument("--angular", type=int, default=0)
    ap.add_argument("--frame-type", type=int, default=24)
    ap.add_argument("--t1", type=int, default=5)
    ap.add_argument("--t2", type=int, default=10)
    ap.add_argument("--interval", type=float, default=0.5)
    ap.add_argument("--warmup", type=float, default=0,
                     help="seconds of POWER-only keepalive before the first drive frame")
    ap.add_argument("--with-reader", action="store_true",
                     help="also run a background reader draining /dev/ttyS2, matching ServiceExam")
    args = ap.parse_args()

    power = tagged_frame(b"POWER", POWER_FRAME_SIZE)
    stop = tagged_frame(b"MTSTP", POWER_FRAME_SIZE)
    drive = build_sustained_frame(args.linear, args.angular, args.frame_type, args.t1, args.t2)

    tmp = "/data/local/tmp"
    for name, data in (("power.bin", power), ("stop.bin", stop), ("drive.bin", drive)):
        local = f"/tmp/{name}"
        with open(local, "wb") as f:
            f.write(data)
        subprocess.run(["adb", "-s", args.serial, "push", local, f"{tmp}/{name}"],
                        check=True, capture_output=True)

    print("Force-stopping com.miko3.mode.remotecontrol and ServiceExam so nothing else holds /dev/ttyS2...")
    adb(args.serial, "shell", "am", "force-stop", "com.miko3.mode.remotecontrol")
    adb(args.serial, "shell", "am", "force-stop", "com.example.root.serviceexam")

    drive_iters = max(1, round(args.seconds / args.interval))
    total_power_seconds = args.seconds + args.warmup
    power_iters = max(1, round(total_power_seconds * 1000 / 100)) + 20

    reader_start = 'cat <&3 > /dev/null &\nREADERPID=$!\n' if args.with_reader else ''
    reader_kill = 'kill $READERPID 2>/dev/null\n' if args.with_reader else ''
    script = f"""
exec 3<>/dev/ttyS2
{reader_start}( for i in $(seq 1 {power_iters}); do cat {tmp}/power.bin >&3; sleep 0.1; done ) &
POWERPID=$!
echo "warming up POWER-only keepalive for {args.warmup}s..."
sleep {args.warmup}
for i in $(seq 1 {drive_iters}); do
  cat {tmp}/drive.bin >&3
  echo "sent drive frame $i at $(date +%s.%N)"
  sleep {args.interval}
done
cat {tmp}/stop.bin >&3
kill $POWERPID 2>/dev/null
{reader_kill}exec 3>&-
echo DONE
"""
    print(f"Driving linear={args.linear} angular={args.angular} for ~{args.seconds}s, "
          f"bypassing the app entirely...")
    proc = subprocess.run(["adb", "-s", args.serial, "shell", script],
                           capture_output=True, text=True)
    print(proc.stdout)
    if proc.returncode != 0:
        print(proc.stderr, file=sys.stderr)
        return proc.returncode
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
