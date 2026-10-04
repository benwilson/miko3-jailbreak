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

With the NC direction chip (hey-miko plan U1, U2) each row also carries the
chip's raw 0..255 reading ("raw", or null), and the answer carries the chip's
raw reply bytes as hex ("raw_reply"). The table and the CSVs print the raw value
next to the angle, which is what the owner calibrates zero, sign and scale from
(KTD12; scripts/qa-direction-chip.py --calibrate). An older launcher omits both
and the output keeps its old shape.

Rows carry counts and flags only. The route never sends transcript text, and
this script refuses a dump that carries any (parse_answer), so nothing said
near the robot lands in a terminal or a CSV.

The measurement session (U2, --session) runs the plan's named steps in order,
each printing an instruction to the owner and writing one CSV under out/
(gitignored): the ears steps (left, right, front, behind, name, talk-3m,
talk-5m) run the probe above and add per-chunk decode milliseconds with the
step's p50 and p95 (KTD2's 80 ms fallback line), and per-second CPU of the
launcher and Explore processes read from /proc/<pid>/stat over adb; the shove
step records the accelerometer (IMUAC=) from the raw sensor log while the owner
pushes him (KTD5); the face steps (frontal, 45 degrees, profile at 1.5 m) sample
Explore's /state page while the owner stands there. The state page does not yet
expose the face box, so the face-box shape KTD4 needs is a TODO in docs/TODO.md,
not a column here. Pass --drive to repeat the ears steps with the wheels on.

Usage:
  scripts/qa-ears-probe.py [--serial 192.168.19.74:5555] [--seconds 10]
      [--phrase "hey miko"] [--drive 40,0] [--csv out/ears.csv]
  scripts/qa-ears-probe.py --session [left right ...] [--out out/ears] [--drive 40,0]
"""
import argparse
import contextlib
import csv
import datetime
import importlib.util
import json
import math
import re
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
# Optional, from a launcher with the NC chip backend (U2): the chip's raw 0..255
# direction reading that second (null when none), and the top-level raw reply hex.
RAW_FIELD = "raw"
NC_REPLY_FIELD = "raw_reply"

# --- the measurement session (U2) ---
# Explore's plain listener; /state answers {"state":..,"lookX":..,"lookY":..} (ModeApp.PORT).
EXPLORE_PORT = 8083
STATE_PATH = "/state"
STATE_FIELDS = ("t_s", "state", "lookX", "lookY")
STATE_SAMPLE_S = 0.5
# The processes whose CPU the session reads from /proc/<pid>/stat (AndroidManifest package names).
LAUNCHER_PACKAGE = "com.miko3.launcher"
EXPLORE_PACKAGE = "com.miko3.mode.explore"
CPU_SAMPLE_S = 1.0
DEFAULT_CLK_TCK = 100
PAGE_BYTES = 4096
# KTD2: decode above this per 80 ms chunk at p95 with the detector roaming selects the keyword-spotter fallback.
DECODE_CHUNK_BUDGET_MS = 80
STEP_EXTRA_FIELDS = ("decode_chunk_ms", "decode_p50_ms", "decode_p95_ms", "decode_max_p95_ms",
                     "cpu_launcher_pct", "cpu_explore_pct", "rss_launcher_mb")
# The raw sensor log qa-explore-sensors.py reads; the shove step takes IMUAC= from it (KTD5).
RAW_TAG = "MikoDmdRaw"
ACCEL_FIELDS = ("t_ms", "ax", "ay", "az", "delta")
IMUAC_RE = re.compile(r"IMUAC=(-?\d{1,10}),(-?\d{1,10}),(-?\d{1,10})")
STAMP_RE = re.compile(r"^(?P<month>\d\d)-(?P<day>\d\d) (?P<h>\d\d):(?P<m>\d\d):(?P<s>\d\d)\.(?P<ms>\d{3})")
FACE_TODO = ("TODO: Explore's /state exposes state and look only, not the face box; the width-to-height "
             "ratio and size KTD4 needs are recorded as a follow-up in docs/TODO.md")

Response = namedtuple("Response", "status headers body")
# nc_reply: the NC chip's raw reply as hex (a string or a list of them), or None.
Answer = namedtuple("Answer", "backend seconds rows nc_reply", defaults=(None,))
Step = namedtuple("Step", "name kind instruction phrase")
DecodeStats = namedtuple("DecodeStats", "per_chunk_ms p50_ms p95_ms max_p95_ms over_budget")
ProcSample = namedtuple("ProcSample", "ticks rss_pages")

STEPS = (
    Step("left", "ears", "stand 1.5 m to his LEFT and say 'hey miko' twice, a few seconds apart", "hey miko"),
    Step("right", "ears", "stand 1.5 m to his RIGHT and say 'hey miko' twice, a few seconds apart", "hey miko"),
    Step("front", "ears", "stand 1.5 m in FRONT of him and say 'hey miko' twice, a few seconds apart", "hey miko"),
    Step("behind", "ears", "stand 1.5 m BEHIND him and say 'hey miko' twice, a few seconds apart", "hey miko"),
    Step("name", "ears", "stand 1.5 m to one side and say his name ('miko') twice, plainly", "miko"),
    Step("shove", "shove", "wait two seconds, then push him once, firmly, from the side", None),
    Step("face-frontal", "face", "stand 1.5 m in front of him, facing him squarely, while Explore looks at you",
         None),
    Step("face-45", "face", "stand 1.5 m in front of him, turned 45 degrees away, while Explore looks at you", None),
    Step("face-profile", "face", "stand 1.5 m in front of him in full profile (side on), while Explore looks at you",
         None),
    Step("talk-3m", "ears", "two people hold a normal conversation 3 m away, not addressing him", None),
    Step("talk-5m", "ears", "two people hold a normal conversation 5 m away, not addressing him", None),
)


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
        extra = set(row) - set(ROW_FIELDS) - {RAW_FIELD}
        if extra:
            raise ValueError(f"a row carries unexpected fields: {', '.join(sorted(extra))}")
        missing = set(ROW_FIELDS) - set(row)
        if missing:
            raise ValueError(f"a row lacks: {', '.join(sorted(missing))}")
        raw = row.get(RAW_FIELD)
        if raw is not None and (type(raw) is not int or not 0 <= raw <= 255):
            raise ValueError(f"a row's {RAW_FIELD} is not a byte: {raw!r}")
        rows.append({k: row[k] for k in row_fields([row])})
    reply = data.get(NC_REPLY_FIELD)
    if not (reply is None or isinstance(reply, str)
            or (isinstance(reply, list) and all(isinstance(r, str) for r in reply))):
        raise ValueError(f"the answer's {NC_REPLY_FIELD} is not hex text: {reply!r}")
    return Answer(str(data.get("backend", "?")), int(data.get("seconds", len(rows))), rows, reply)


def row_fields(rows):
    """ROW_FIELDS, with the raw chip value right after the angle when any row carries it."""
    if not any(RAW_FIELD in r for r in rows):
        return ROW_FIELDS
    at = ROW_FIELDS.index("angle") + 1
    return ROW_FIELDS[:at] + (RAW_FIELD,) + ROW_FIELDS[at:]


def format_rows(rows):
    with_raw = RAW_FIELD in row_fields(rows)
    raw_head = f" {'raw':>4}" if with_raw else ""
    head = f"{'sec':>4} {'angle':>7}{raw_head} {'rms':>6} {'decode':>7} {'max':>5} {'chunks':>6} {'words':>5} match"
    lines = [head]
    for r in rows:
        angle = "-" if r["angle"] is None else f"{r['angle']:.1f}"
        raw = ""
        if with_raw:
            raw = f" {'-' if r.get(RAW_FIELD) is None else r[RAW_FIELD]:>4}"
        lines.append(f"{r['second']:>4} {angle:>7}{raw} {r['rms']:>6} {r['decode_ms']:>6}ms {r['decode_max_ms']:>5} "
                     f"{r['chunks']:>6} {r['words']:>5} {'yes' if r['matched'] else 'no'}")
    return "\n".join(lines)


def write_csv(rows, path):
    write_dict_csv(rows, row_fields(rows), path)


def write_dict_csv(rows, fields, path):
    """Rows of plain dicts to a CSV with exactly these columns; None is an empty cell."""
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fields)
        w.writeheader()
        for r in rows:
            w.writerow({k: ("" if r.get(k) is None else r[k]) for k in fields})


# --- the measurement session's statistics (U2) ---

def percentile(values, p):
    """Nearest-rank percentile of a non-empty list, else None."""
    values = sorted(v for v in values if v is not None)
    if not values:
        return None
    rank = max(1, math.ceil(p / 100.0 * len(values)))
    return values[rank - 1]


def decode_stats(rows):
    """Decode milliseconds per chunk: the route reports each second's total and worst chunk,
    so the per-chunk value is that second's mean (total / chunks) and the p50/p95 run over the
    seconds; max_p95_ms is the p95 of the worst chunks, an upper bound on the true per-chunk p95."""
    per_chunk = [(r["decode_ms"] / r["chunks"]) if r["chunks"] else None for r in rows]
    p95 = percentile(per_chunk, 95)
    return DecodeStats(per_chunk, percentile(per_chunk, 50), p95,
                       percentile([r["decode_max_ms"] for r in rows], 95),
                       p95 is not None and p95 > DECODE_CHUNK_BUDGET_MS)


def parse_proc_stat(text):
    """utime+stime ticks and the resident pages from one /proc/<pid>/stat line, or None.
    The command name sits in parentheses and may hold spaces, so fields are counted after it."""
    text = (text or "").strip()
    close = text.rfind(")")
    if close < 0:
        return None
    fields = text[close + 1:].split()
    # fields[0] is state (field 3 of the man page); utime is field 14, stime 15, rss 24.
    try:
        return ProcSample(int(fields[11]) + int(fields[12]), int(fields[21]))
    except (IndexError, ValueError):
        return None


def cpu_percent(before, after, elapsed_s, clk_tck):
    """Percent of one core a process used between two samples, or None without both."""
    if before is None or after is None or elapsed_s <= 0 or clk_tck <= 0:
        return None
    return round(100.0 * (after.ticks - before.ticks) / (elapsed_s * clk_tck), 1)


def cpu_rows(snapshots, clk_tck):
    """One dict per interval between consecutive (t, {package: ProcSample}) snapshots."""
    rows = []
    for (t0, a), (t1, b) in zip(snapshots, snapshots[1:]):
        launcher = b.get(LAUNCHER_PACKAGE)
        rows.append({"cpu_launcher_pct": cpu_percent(a.get(LAUNCHER_PACKAGE), launcher, t1 - t0, clk_tck),
                     "cpu_explore_pct": cpu_percent(a.get(EXPLORE_PACKAGE), b.get(EXPLORE_PACKAGE), t1 - t0, clk_tck),
                     "rss_launcher_mb": None if launcher is None
                     else round(launcher.rss_pages * PAGE_BYTES / 1048576.0, 1)})
    return rows


class CpuSampler:
    """Reads /proc/<pid>/stat for the launcher and Explore over adb; samples every period
    in a thread between start() and stop(), so a probe that blocks on one POST still gets
    per-second rows. pidof is re-read each sample: a mode may restart mid-session."""

    PACKAGES = (LAUNCHER_PACKAGE, EXPLORE_PACKAGE)
    # One adb round trip per sample (Wi-Fi adb costs 100-300 ms a spawn): for each package
    # a "<package> <pid>" line, then that pid's stat line when it has one.
    STAT_SCRIPT = (f"for p in {' '.join(PACKAGES)}; do set -- $(pidof $p); echo \"$p $1\"; "
                   '[ -n "$1" ] && cat /proc/$1/stat; done')

    def __init__(self, robot, period_s=CPU_SAMPLE_S, clock=time.monotonic):
        self.robot, self.period_s, self.clock = robot, period_s, clock
        self.snapshots = []
        self._stop = threading.Event()
        self._thread = None
        self._clk_tck = None

    @property
    def clk_tck(self):
        if self._clk_tck is None:
            out = self.robot.adb("shell", "getconf", "CLK_TCK", check=False).strip()
            self._clk_tck = int(out) if out.isdigit() else DEFAULT_CLK_TCK
        return self._clk_tck

    def snapshot(self):
        """{package: ProcSample or None} from one run of STAT_SCRIPT."""
        sample = {pkg: None for pkg in self.PACKAGES}
        awaiting_stat = None
        for line in self.robot.adb("shell", self.STAT_SCRIPT, check=False).splitlines():
            head = line.split(None, 1)
            if head and head[0] in self.PACKAGES:
                # A "<package> <pid>" line; a stat line never starts with a package name.
                pid = head[1].strip() if len(head) > 1 else ""
                awaiting_stat = head[0] if pid.isdigit() else None
            elif awaiting_stat is not None:
                sample[awaiting_stat] = parse_proc_stat(line)
                awaiting_stat = None
        return sample

    def _run(self):
        while not self._stop.is_set():
            try:
                self.snapshots.append((self.clock(), self.snapshot()))
            except ProbeError:
                pass  # a dropped adb mid-sample loses one row, not the step
            self._stop.wait(self.period_s)

    def start(self):
        self.snapshots = []
        self._stop.clear()
        self._thread = threading.Thread(target=self._run, name="cpu-sampler", daemon=True)
        self._thread.start()

    def stop(self):
        """Stops sampling and returns the per-interval CPU rows."""
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout=ADB_TIMEOUT)
        return cpu_rows(self.snapshots, self.clk_tck)


def step_rows(rows, cpu):
    """The probe rows joined with per-chunk decode, the step's percentiles and the CPU
    interval sampled alongside that second (by index; both run over the same window)."""
    stats = decode_stats(rows)
    out = []
    for i, r in enumerate(rows):
        row = dict(r)
        row["decode_chunk_ms"] = None if stats.per_chunk_ms[i] is None else round(stats.per_chunk_ms[i], 1)
        row["decode_p50_ms"] = None if stats.p50_ms is None else round(stats.p50_ms, 1)
        row["decode_p95_ms"] = None if stats.p95_ms is None else round(stats.p95_ms, 1)
        row["decode_max_p95_ms"] = stats.max_p95_ms
        c = cpu[i] if i < len(cpu) else {}
        for k in ("cpu_launcher_pct", "cpu_explore_pct", "rss_launcher_mb"):
            row[k] = c.get(k)
        out.append(row)
    return out


def write_step_csv(rows, cpu, path):
    write_dict_csv(step_rows(rows, cpu), row_fields(rows) + STEP_EXTRA_FIELDS, path)


def format_step_summary(rows, cpu):
    stats = decode_stats(rows)
    fmt = lambda v: "-" if v is None else f"{v:.1f}"  # noqa: E731
    angles = [r["angle"] for r in rows if r["angle"] is not None]
    lines = [f"decode per chunk: p50 {fmt(stats.p50_ms)} ms, p95 {fmt(stats.p95_ms)} ms "
             f"(worst-chunk p95 {fmt(stats.max_p95_ms)} ms; KTD2 line {DECODE_CHUNK_BUDGET_MS} ms"
             f"{', OVER: keyword-spotter fallback' if stats.over_budget else ''})",
             f"angle: {len(angles)} of {len(rows)} seconds reported one"
             + (f", median {percentile(angles, 50):.1f}, min {min(angles):.1f}, max {max(angles):.1f}" if angles else ""),
             f"matched: {'yes' if any(r['matched'] for r in rows) else 'no'}"]
    if RAW_FIELD in row_fields(rows):
        raws = [r[RAW_FIELD] for r in rows if r.get(RAW_FIELD) is not None]
        lines.append(f"raw: {len(raws)} of {len(rows)} seconds reported one"
                     + (f", median {percentile(raws, 50)}, min {min(raws)}, max {max(raws)}" if raws else ""))
    launcher = [c["cpu_launcher_pct"] for c in cpu if c.get("cpu_launcher_pct") is not None]
    explore = [c["cpu_explore_pct"] for c in cpu if c.get("cpu_explore_pct") is not None]
    if launcher or explore:
        lines.append(f"cpu: launcher mean {fmt(sum(launcher) / len(launcher) if launcher else None)} % "
                     f"max {fmt(max(launcher) if launcher else None)} %, explore mean "
                     f"{fmt(sum(explore) / len(explore) if explore else None)} % max {fmt(max(explore) if explore else None)} %")
    return "\n".join(lines)


# --- the shove step: the accelerometer from the raw sensor log (KTD5) ---

def _stamp_ms(line):
    m = STAMP_RE.match(line)
    if not m:
        return None
    t = datetime.datetime(2000, int(m["month"]), int(m["day"]), int(m["h"]), int(m["m"]), int(m["s"]))
    return int((t - datetime.datetime(2000, 1, 1)).total_seconds()) * 1000 + int(m["ms"])


def parse_accel_log(text):
    """(t_ms, ax, ay, az, delta) per raw-log line with a readable IMUAC= triple; delta is the
    vector change from the previous reading, the shove signature KTD5 looks for."""
    records, prev = [], None
    for line in text.splitlines():
        if RAW_TAG not in line:
            continue
        m = IMUAC_RE.search(line)
        t = _stamp_ms(line)
        if not m or t is None:
            continue
        ax, ay, az = (int(g) for g in m.groups())
        delta = 0 if prev is None else round(math.sqrt((ax - prev[0]) ** 2 + (ay - prev[1]) ** 2 + (az - prev[2]) ** 2))
        records.append({"t_ms": t, "ax": ax, "ay": ay, "az": az, "delta": delta})
        prev = (ax, ay, az)
    return records


# --- the face steps: Explore's state page while the owner stands there ---

def parse_state_sample(t_s, text):
    try:
        data = json.loads(text)
    except ValueError:
        return None
    if not isinstance(data, dict):
        return None
    return {"t_s": t_s, "state": data.get("state"), "lookX": data.get("lookX"), "lookY": data.get("lookY")}


def sample_state(base, seconds, http, period_s=STATE_SAMPLE_S, clock=time.monotonic, sleep=time.sleep):
    rows, started = [], clock()
    while True:
        t = clock() - started
        try:
            r = http("GET", base + STATE_PATH, timeout=5)
            row = parse_state_sample(round(t, 2), r.body.decode("utf-8", "replace")) if r.status == 200 else None
        except OSError:
            row = None
        if row is not None:
            rows.append(row)
        if clock() - started >= seconds:
            return rows
        sleep(period_s)


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


# --- the measurement session (U2) ---

def select_steps(names):
    """The plan's steps in plan order, or the named ones in the order given; unknown names raise."""
    if not names:
        return list(STEPS)
    by_name = {s.name: s for s in STEPS}
    unknown = [n for n in names if n not in by_name]
    if unknown:
        raise ValueError(f"unknown step(s) {', '.join(unknown)}; choose from {', '.join(by_name)}")
    return [by_name[n] for n in names]


def run_ears_step(robot, step, seconds, drive, http, cpu_sampler, out_dir):
    if cpu_sampler is not None:
        cpu_sampler.start()
    try:
        answer = run(robot, seconds, step.phrase, drive=drive, http=http)
    finally:
        cpu = cpu_sampler.stop() if cpu_sampler is not None else []
    path = Path(out_dir) / f"{step.name}.csv"
    write_step_csv(answer.rows, cpu, path)
    print(format_step_summary(answer.rows, cpu))
    return path


def run_shove_step(robot, step, seconds, out_dir, sleep=time.sleep):
    robot.adb("shell", "setprop", f"log.tag.{RAW_TAG}", "DEBUG")
    try:
        robot.adb("logcat", "-c")
        sleep(seconds)
        records = parse_accel_log(robot.adb("logcat", "-d", "-s", f"{RAW_TAG}:D"))
    finally:
        robot.adb("shell", "setprop", f"log.tag.{RAW_TAG}", "INFO", check=False)
    path = Path(out_dir) / f"{step.name}.csv"
    write_dict_csv(records, ACCEL_FIELDS, path)
    if records:
        peak = max(records, key=lambda r: r["delta"])
        print(f"{len(records)} accelerometer readings; largest step {peak['delta']} counts at "
              f"+{(peak['t_ms'] - records[0]['t_ms']) / 1000.0:.1f} s")
    else:
        print("!! no IMUAC= readings in the raw log: is a mode holding the drive (Explore or remote-control)?")
    return path


def run_face_step(robot, step, seconds, http, out_dir, sample_period_s=STATE_SAMPLE_S):
    with robot.forward(EXPLORE_PORT) as base:
        rows = sample_state(base, seconds, http, period_s=sample_period_s)
    path = Path(out_dir) / f"{step.name}.csv"
    write_dict_csv(rows, STATE_FIELDS, path)
    states = sorted({r["state"] for r in rows if r["state"]})
    print(f"{len(rows)} state samples; states seen: {', '.join(states) if states else 'none'}")
    print(FACE_TODO)
    return path


def run_session(robot, names, seconds, out_dir, drive=None, http=default_http, ask=None, cpu_sampler=None,
                sample_period_s=STATE_SAMPLE_S):
    """The owner's measurement session: each step prints its instruction, waits for Enter
    (ask), captures, and writes out/<step>.csv. cpu_sampler=None skips the CPU columns."""
    steps = select_steps(names)
    if ask is None:
        ask = lambda text: input(text)  # noqa: E731
    written = []
    for step in steps:
        print(f"\n== {step.name} ==")
        ask(f">> {step.name}: {step.instruction}, then press Enter ")
        if step.kind == "ears":
            path = run_ears_step(robot, step, seconds, drive, http, cpu_sampler, out_dir)
        elif step.kind == "shove":
            path = run_shove_step(robot, step, seconds, out_dir)
        else:
            path = run_face_step(robot, step, seconds, http, out_dir, sample_period_s=sample_period_s)
        print(f"wrote {path}")
        written.append(path)
    return written


def build_parser():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--serial", default=DEFAULT_SERIAL)
    ap.add_argument("--seconds", type=int, default=DEFAULT_SECONDS, help=f"capture length, 1..{MAX_SECONDS}")
    ap.add_argument("--phrase", help="words the rows report as matched once all of them were heard")
    ap.add_argument("--drive", type=parse_drive,
                    help="linear,angular to resend through remote-control's /drive during the capture")
    ap.add_argument("--csv", help="also write the rows to this CSV")
    ap.add_argument("--session", nargs="*", metavar="STEP",
                    help="run the U2 measurement steps (all, or the named ones): "
                         + ", ".join(s.name for s in STEPS))
    ap.add_argument("--out", default="out/ears", help="directory for the session's per-step CSVs")
    return ap


def main(argv=None):
    args = build_parser().parse_args(argv)
    robot = Robot(args.serial)
    robot.ensure_reachable()
    # A SIGTERM (a closed terminal, a kill) must reach the finally blocks like Ctrl-C does.
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(143))
    try:
        if args.session is not None:
            try:
                select_steps(args.session)
            except ValueError as exc:
                raise ProbeError(f"!! {exc}")
            run_session(robot, args.session, args.seconds, args.out, drive=args.drive, cpu_sampler=CpuSampler(robot))
        else:
            run(robot, args.seconds, args.phrase, drive=args.drive, csv_path=args.csv)
    except KeyboardInterrupt:
        print("\ninterrupted; the property is cleared", file=sys.stderr)
        return 130
    return 0


if __name__ == "__main__":
    sys.exit(main())
