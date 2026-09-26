#!/usr/bin/env python3
"""qa-ears-probe.py — dump what the robot hears for a timed window: the voice
direction angle, the capture level, the recogniser's decode time and whether it
heard a phrase (meeting plan U1, step 4; the owner's measurement session, U2).

The launcher's probe route (/settings/ears-probe, TLS) answers only while the
debug property debug.miko3.ears_probe holds the nonce this script generated, so
the run goes:
  1. set the property to a fresh nonce over adb;
  2. forward the launcher's HTTPS port, read the Settings page for its token;
  3. optionally drive the wheels through the remote-control mode's /drive
     endpoint for the length of the capture (--drive linear,angular; the mode
     must be running: the launcher's /launch-mode?mode=remote-control);
  4. POST the token, the nonce, the length and the phrase, and print the rows;
  5. clear the property, stop the wheels and remove the forwards, on every exit
     including Ctrl-C (an interrupted run must never leave the route armed:
     see the debug-props note in docs/solutions and the memory about hooks
     left on).

Rows carry counts and flags only. The route never sends transcript text, and
this script refuses a dump that carries any (parse_answer), so nothing said
near the robot lands in a terminal or a CSV.

Usage:
  scripts/qa-ears-probe.py [--serial 192.168.19.74:5555] [--seconds 10]
      [--phrase "hey miko"] [--drive 40,0] [--csv out/ears.csv]
"""
import argparse
import contextlib
import csv
import importlib.util
import json
import secrets
import signal
import subprocess
import sys
import threading
import time
from collections import namedtuple
from pathlib import Path
from urllib.parse import urlencode

HERE = Path(__file__).resolve().parent
DEFAULT_SERIAL = "192.168.19.74:5555"
# Mirrors launcher/src/com/miko3/launcher/EarsProbe.java and LauncherProtocol.java.
PROPERTY = "debug.miko3.ears_probe"
PROBE_PATH = "/settings/ears-probe"
SETTINGS_PATH = "/settings"
LAUNCHER_HTTPS_PORT = 8443
# The remote-control mode's plain listener; /drive?linear=&angular=&ct= resent
# while a control is held (its watchdog stops the wheels 1.2 s after the last one).
REMOTE_CONTROL_PORT = 8081
DRIVE_RESEND_S = 0.25
DEFAULT_SECONDS = 10
MAX_SECONDS = 15
ADB_TIMEOUT = 30
HTTP_TIMEOUT = 60
ROW_FIELDS = ("second", "angle", "rms", "decode_ms", "decode_max_ms", "chunks", "words", "matched")

Response = namedtuple("Response", "status headers body")
Answer = namedtuple("Answer", "backend seconds rows")


class ProbeError(SystemExit):
    """A precondition or the probe itself failed; the message says what to do."""


def new_nonce():
    return secrets.token_hex(16)


def _settings_module():
    """scripts/robot-settings.py, for its adb reachability check and HTTPS client."""
    spec = importlib.util.spec_from_file_location("robot_settings", HERE / "robot-settings.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


class Robot:
    def __init__(self, serial):
        self.serial = serial

    def adb(self, *args, check=True):
        # stdin=DEVNULL: adb would otherwise eat the owner's keystrokes.
        try:
            r = subprocess.run(["adb", "-s", self.serial] + list(args), capture_output=True, text=True,
                               timeout=ADB_TIMEOUT, stdin=subprocess.DEVNULL)
        except FileNotFoundError:
            raise ProbeError("!! adb not found on PATH (brew install android-platform-tools)")
        except subprocess.TimeoutExpired:
            raise ProbeError(f"!! adb timed out: adb {' '.join(args)}")
        if check and r.returncode != 0:
            raise ProbeError(f"!! adb {' '.join(args)}: {r.stderr.strip() or r.stdout.strip()}")
        return r.stdout

    def ensure_reachable(self):
        _settings_module().ensure_reachable(self.serial)

    def set_property(self, value):
        self.adb("shell", "setprop", PROPERTY, value)

    def clear_property(self):
        """Best effort, never raises: this runs from finally blocks."""
        try:
            self.adb("shell", "setprop", PROPERTY, '""', check=False)
        except ProbeError as exc:
            print(f"{exc}\n!! clear it by hand: adb -s {self.serial} shell setprop {PROPERTY} '\"\"'",
                  file=sys.stderr)

    @contextlib.contextmanager
    def forward(self, port):
        """Yields a base URL for the robot's port through an adb forward; always removed."""
        out = self.adb("forward", "tcp:0", f"tcp:{port}").strip()
        if not out.isdigit():
            raise ProbeError(f"!! adb forward to tcp:{port} failed: {out or 'no port printed'}")
        local = int(out)
        scheme = "https" if port == LAUNCHER_HTTPS_PORT else "http"
        try:
            yield f"{scheme}://127.0.0.1:{local}"
        finally:
            self.adb("forward", "--remove", f"tcp:{local}", check=False)


# --- HTTP ---

def default_http(method, url, body=None, headers=None, timeout=HTTP_TIMEOUT):
    """robot-settings.py's client for HTTPS (self-signed), urllib for the mode's plain port."""
    if url.startswith("https://"):
        return _settings_module().http_request(method, url, body=body, headers=headers, timeout=timeout)
    import urllib.error
    import urllib.request
    req = urllib.request.Request(url, data=body, headers=headers or {}, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return Response(r.status, dict(r.headers), r.read())
    except urllib.error.HTTPError as e:
        return Response(e.code, dict(e.headers), e.read())


def page_token(html):
    """The Settings page's form token (name="t"), or None."""
    import re
    m = re.search(r'name="t"\s+value="([0-9a-f]{32})"', html)
    return m.group(1) if m else None


def fetch_token(base, http):
    try:
        r = http("GET", base + SETTINGS_PATH)
    except OSError as exc:
        raise ProbeError(f"!! could not reach the launcher's Settings page: {exc}\n"
                         "   Check the custom launcher is running on the robot.")
    if r.status != 200:
        raise ProbeError(f"!! GET {SETTINGS_PATH} answered {r.status}; install the current launcher.")
    token = page_token(r.body.decode("utf-8", "replace"))
    if not token:
        raise ProbeError("!! the Settings page has no form token; install the current launcher.")
    return token


def parse_answer(text):
    """The route's JSON as an Answer of plain-dict rows. Refuses anything but the
    documented fields, so a transcript could never slip through unnoticed."""
    try:
        data = json.loads(text)
    except ValueError as exc:
        raise ValueError(f"probe answer is not JSON: {exc}")
    if not isinstance(data, dict) or not isinstance(data.get("rows"), list):
        raise ValueError("probe answer has no rows")
    rows = []
    for row in data["rows"]:
        if not isinstance(row, dict):
            raise ValueError("a row is not an object")
        extra = set(row) - set(ROW_FIELDS)
        if extra:
            raise ValueError(f"a row carries unexpected fields: {', '.join(sorted(extra))}")
        missing = set(ROW_FIELDS) - set(row)
        if missing:
            raise ValueError(f"a row lacks: {', '.join(sorted(missing))}")
        rows.append({k: row[k] for k in ROW_FIELDS})
    return Answer(str(data.get("backend", "?")), int(data.get("seconds", len(rows))), rows)


def format_rows(rows):
    head = f"{'sec':>4} {'angle':>7} {'rms':>6} {'decode':>7} {'max':>5} {'chunks':>6} {'words':>5} match"
    lines = [head]
    for r in rows:
        angle = "-" if r["angle"] is None else f"{r['angle']:.1f}"
        lines.append(f"{r['second']:>4} {angle:>7} {r['rms']:>6} {r['decode_ms']:>6}ms {r['decode_max_ms']:>5} "
                     f"{r['chunks']:>6} {r['words']:>5} {'yes' if r['matched'] else 'no'}")
    return "\n".join(lines)


def write_csv(rows, path):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=ROW_FIELDS)
        w.writeheader()
        for r in rows:
            w.writerow({k: ("" if r[k] is None else r[k]) for k in ROW_FIELDS})


# --- driving through the remote-control mode ---

def parse_drive(value):
    """'linear,angular' as two ints, e.g. '40,0' forward or '0,-25' turning."""
    try:
        linear, angular = (int(v.strip()) for v in value.split(","))
    except ValueError:
        raise ValueError(f"--drive wants linear,angular (two integers), got {value!r}")
    return linear, angular


def drive_url(base, linear, angular, ct):
    return f"{base}/drive?" + urlencode([("linear", linear), ("angular", angular), ("ct", ct)])


class Driver:
    """Resends one drive command every period_s until stop(), then stops the wheels."""

    def __init__(self, base, linear, angular, http=default_http, period_s=DRIVE_RESEND_S):
        self.base, self.linear, self.angular, self.http, self.period_s = base, linear, angular, http, period_s
        self.ct = secrets.token_hex(8)
        self._stop = threading.Event()
        self._thread = threading.Thread(target=self._run, name="drive", daemon=True)
        self.errors = 0

    def _send(self, linear, angular):
        try:
            r = self.http("GET", drive_url(self.base, linear, angular, self.ct), timeout=5)
            if r.status != 200:
                self.errors += 1
        except OSError:
            self.errors += 1

    def _run(self):
        while not self._stop.is_set():
            self._send(self.linear, self.angular)
            self._stop.wait(self.period_s)

    def start(self):
        self._thread.start()

    def join_after(self, seconds):
        self._stop.wait(seconds)

    def stop(self):
        self._stop.set()
        self._thread.join(timeout=5)
        self._send(0, 0)


# --- the run ---

def run(robot, seconds, phrase, drive=None, http=default_http, csv_path=None):
    """One probe: arms the property, captures, prints the rows; the property is
    cleared and the wheels stopped whatever happens."""
    seconds = max(1, min(MAX_SECONDS, int(seconds)))
    nonce = new_nonce()
    robot.set_property(nonce)
    print(f"armed {PROPERTY} for {seconds} s" + (f", phrase {phrase!r}" if phrase else ""))
    try:
        with contextlib.ExitStack() as stack:
            driver = None
            if drive is not None:
                rc_base = stack.enter_context(robot.forward(REMOTE_CONTROL_PORT))
                driver = Driver(rc_base, drive[0], drive[1], http=http)
                stack.callback(driver.stop)
                driver.start()
                print(f"driving linear={drive[0]} angular={drive[1]} through {rc_base}/drive")
            base = stack.enter_context(robot.forward(LAUNCHER_HTTPS_PORT))
            token = fetch_token(base, http)
            fields = [("t", token), ("nonce", nonce), ("seconds", str(seconds))]
            if phrase:
                fields.append(("phrase", phrase))
            try:
                r = http("POST", base + PROBE_PATH, body=urlencode(fields),
                         headers={"Content-Type": "application/x-www-form-urlencoded"},
                         timeout=seconds + HTTP_TIMEOUT)
            except OSError as exc:
                raise ProbeError(f"!! POST {PROBE_PATH} failed: {exc}")
            if r.status == 404:
                raise ProbeError(f"!! the probe route answered 404: the launcher did not see {PROPERTY} "
                                 "(is this launcher built from this tree? was the property cleared "
                                 "by another run?), or the page token was refused.")
            if r.status != 200:
                raise ProbeError(f"!! the probe answered {r.status}: {r.body.decode('utf-8', 'replace').strip()}")
            try:
                answer = parse_answer(r.body.decode("utf-8", "replace"))
            except ValueError as exc:
                raise ProbeError(f"!! {exc}")
            if driver is not None and driver.errors:
                print(f"!! {driver.errors} drive commands failed (is remote-control mode running?)")
    finally:
        robot.clear_property()
    print(f"direction backend: {answer.backend}")
    print(format_rows(answer.rows))
    if csv_path:
        write_csv(answer.rows, csv_path)
        print(f"wrote {csv_path}")
    return answer


def build_parser():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--serial", default=DEFAULT_SERIAL)
    ap.add_argument("--seconds", type=int, default=DEFAULT_SECONDS, help=f"capture length, 1..{MAX_SECONDS}")
    ap.add_argument("--phrase", help="words the rows report as matched once all of them were heard")
    ap.add_argument("--drive", type=parse_drive,
                    help="linear,angular to resend through remote-control's /drive during the capture")
    ap.add_argument("--csv", help="also write the rows to this CSV")
    return ap


def main(argv=None):
    args = build_parser().parse_args(argv)
    robot = Robot(args.serial)
    robot.ensure_reachable()
    # A SIGTERM (a closed terminal, a kill) must reach the finally blocks like Ctrl-C does.
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(143))
    try:
        run(robot, args.seconds, args.phrase, drive=args.drive, csv_path=args.csv)
    except KeyboardInterrupt:
        print("\ninterrupted; the property is cleared", file=sys.stderr)
        return 130
    return 0


if __name__ == "__main__":
    sys.exit(main())
