#!/usr/bin/env python3
"""
qa-voice-mode.py — the voice mode's end-to-end QA run (plan U10): a named
checklist of the acceptance examples (AE1-AE12), the mode-switch and safety
checks, and the latency report from the relay's conversation logs.

Every step is a named check recorded as pass / fail / manual-skipped /
skipped. Automatic steps drive the robot over root adb and HTTP (launcher
/launch-mode on 8080, presence on 8081/8082, /voice-state and the settings
form on the mode's HTTPS port 8445) and read the relay's logs (GET
/conversations on 8791). Steps that need a person in the room ("say Hey
Miko") print what to do, then poll for the result; the ones that need human
judgement ask y/n. --non-interactive runs only the automatic steps and marks
the rest manual-skipped.

Setup is checked first and fails fast, before any scenario: a relay address
must be given, the robot must answer adb and HTTP, and the relay must answer
its log endpoint.

Relay control (AE8-AE10). Three scenarios need the relay stopped and
started. With --relay-cmd "<command>" the script starts the relay itself (on
this host) and stops/starts it when needed; otherwise it asks the operator
to. AE8 needs the lane stub instead of the relay, which the operator runs:
    relay/.venv/bin/python relay/tests/relay_stub.py --unknown-cmd dance
It sends cmd{action: dance} after conv.ready; the robot must answer
cmd.result "unsupported" (the stub prints it), not move, and keep the
conversation going.

For AE4, run the relay with a short silence timeout for the QA run
(--silence-timeout 15 on the relay, or RELAY_SILENCE_TIMEOUT=15) and pass the
same value here with --silence-timeout so the script waits the right time.

Output (in --out-dir, default relay/out/qa/<time>/, gitignored): the run's
conversation logs, logcat, report.md (latency report plus this checklist, to
paste into the plan's follow-up notes) and report.json. The acoustic
calibration gaps (a phone recording of the room, measured by hand from the
person's last word to the robot's first sound) go in with --calibration-ms,
or are asked for at the end.

Exit status: 0 when nothing failed, 1 when a check failed or setup did.

Usage:
  python3 scripts/qa-voice-mode.py --relay-host 192.168.19.20
  python3 scripts/qa-voice-mode.py --relay-host 192.168.19.20 --non-interactive
  python3 scripts/qa-voice-mode.py --relay-host 127.0.0.1 --install \\
      --relay-cmd "relay/.venv/bin/python relay/relay/main.py --model-host 192.168.19.30 --silence-timeout 15" \\
      --silence-timeout 15
  python3 scripts/qa-voice-mode.py --relay-host 192.168.19.20 --only ae3,ae4,report
"""
import argparse
import http.client
import json
import os
import re
import shlex
import ssl
import subprocess
import sys
import time
from collections import namedtuple
from dataclasses import dataclass
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import parse_qs, urlencode, urlsplit

REPO = Path(__file__).resolve().parent.parent
RELAY_ROOT = REPO / "relay"
INSTALL_PY = REPO / "scripts" / "install-mode-voice.py"

DEFAULT_SERIAL = "192.168.19.74:5555"
VOICE_PKG = "com.miko3.mode.voice"
VOICE_ACTIVITY = f"{VOICE_PKG}/.MainActivity"
LAUNCHER_PKG = "com.miko3.launcher"
FORCE_EXIT_EXTRA = "com.miko3.launcher.EXTRA_FORCE_EXIT"  # LauncherProtocol.EXTRA_FORCE_EXIT
EXIT_INTENT_FLAGS = "0x30000000"  # FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_SINGLE_TOP, as the launcher sends
SERVICEEXAM_PKG = "com.example.root.serviceexam"
LAUNCHER_PORT = 8080
MODE_PORTS = {"remote-control": 8081, "voice": 8082}  # ModeRegistry: presence, plain HTTP
VOICE_HTTPS_PORT = 8445  # plain 8082 redirects everything but /presence here
RELAY_HTTP_PORT = 8791

ADB_TIMEOUT = 30
CONNECT_TIMEOUT = 15
HTTP_TIMEOUT = 5
INSTALL_TIMEOUT = 900

PASS, FAIL, MANUAL_SKIPPED, SKIPPED = "pass", "fail", "manual-skipped", "skipped"
CRITERION_STATUS = {"pass": PASS, "fail": FAIL, "no data": SKIPPED}

Response = namedtuple("Response", "status headers body")


class SetupError(SystemExit):
    """The run can't start; the message says what to fix."""


class CheckFailed(Exception):
    """A check's expectation did not hold; the message is the recorded detail."""


class AdbError(Exception):
    pass


# --- HTTP ---

def _insecure_context():
    # The robot's HTTPS ports use a self-signed certificate (HttpsSupport).
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    return ctx


def http_request(method, url, body=None, headers=None, timeout=HTTP_TIMEOUT):
    """One request, redirects not followed. Connection trouble of any kind is OSError."""
    parts = urlsplit(url)
    if parts.scheme == "https":
        conn = http.client.HTTPSConnection(parts.hostname, parts.port or 443, timeout=timeout,
                                           context=_insecure_context())
    else:
        conn = http.client.HTTPConnection(parts.hostname, parts.port or 80, timeout=timeout)
    path = (parts.path or "/") + (f"?{parts.query}" if parts.query else "")
    try:
        conn.request(method, path, body=body, headers=headers or {})
        r = conn.getresponse()
        data = r.read()
    except http.client.HTTPException as exc:
        raise OSError(f"{url}: {type(exc).__name__}: {exc}") from exc
    finally:
        conn.close()
    return Response(r.status, {k.lower(): v for k, v in r.getheaders()}, data)


def _status_param(location):
    """The ?status= message of a redirect Location, or ""."""
    values = parse_qs(urlsplit(location or "").query).get("status")
    return values[0] if values else ""


# --- the settings form ---

class _FormParser(HTMLParser):
    """The inputs of the settings page's save form (action "/"), not the Exit form's."""

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.in_form = False
        self.fields = {}
        self.checked = set()

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if tag == "form":
            self.in_form = a.get("action") == "/"
        elif tag == "input" and self.in_form and a.get("name"):
            if a.get("type") == "checkbox":
                if "checked" in a:
                    self.checked.add(a["name"])
                self.fields.setdefault(a["name"], None)
            else:
                self.fields[a["name"]] = a.get("value") or ""

    def handle_endtag(self, tag):
        if tag == "form":
            self.in_form = False


def parse_settings_form(html):
    """{t, relay, turn_taking} from the settings page. The page token (t) is minted by
    every GET, and only the latest one is honored, so post straight after fetching."""
    p = _FormParser()
    p.feed(html)
    token = p.fields.get("t")
    if not token:
        raise CheckFailed("the settings page has no page token (name=\"t\") in its save form")
    return {"t": token, "relay": p.fields.get("relay") or "",
            "turn_taking": "turn_taking" in p.checked}


def settings_post_body(form, strict):
    """The save form's body: the token, the relay address unchanged (an empty one would
    clear it), and turn_taking=on only for strict, the way a browser sends a checkbox."""
    fields = [("t", form["t"]), ("relay", form["relay"])]
    if strict:
        fields.append(("turn_taking", "on"))
    return urlencode(fields)


# --- the robot and the relay ---

def robot_host(args):
    if args.robot_host:
        return args.robot_host
    return args.serial.split(":")[0] if ":" in args.serial else None


class Robot:
    def __init__(self, serial, host, http=None, run=None):
        self.serial = serial
        self.host = host
        self._http = http
        self._run = run
        self.states_seen = []  # every distinct /voice-state answer, in order (AE11)

    def http(self, method, url, body=None, headers=None):
        return (self._http or http_request)(method, url, body=body, headers=headers,
                                            timeout=HTTP_TIMEOUT)

    def _exec(self, cmd, timeout):
        try:
            return (self._run or subprocess.run)(cmd, capture_output=True, text=True,
                                                  timeout=timeout)
        except FileNotFoundError:
            raise AdbError("adb not found on PATH — install Android platform-tools "
                           "(brew install android-platform-tools)") from None
        except subprocess.TimeoutExpired:
            raise AdbError(f"adb timed out after {timeout}s: {' '.join(cmd)}") from None

    def adb(self, *args, timeout=ADB_TIMEOUT, check=True):
        cmd = ["adb", "-s", self.serial, *args]
        r = self._exec(cmd, timeout)
        if check and r.returncode != 0:
            raise AdbError(f"adb failed ({r.returncode}): {' '.join(cmd)}\n"
                           f"{(r.stdout + r.stderr).strip()}")
        return r

    def ensure_adb(self):
        """Connect a TCP serial, then require get-state to say "device"."""
        detail = ""
        if ":" in self.serial:
            r = self._exec(["adb", "connect", self.serial], CONNECT_TIMEOUT)
            detail = (r.stdout + r.stderr).strip()
        r = self.adb("get-state", timeout=CONNECT_TIMEOUT, check=False)
        if r.returncode != 0 or r.stdout.strip() != "device":
            detail = "\n".join(s for s in (detail, (r.stdout + r.stderr).strip()) if s)
            raise AdbError(f"robot not reachable over adb at {self.serial}"
                           + (f"\n   adb said: {detail}" if detail else ""))

    def launcher_get(self, path):
        """GET a launcher route; its plain port redirects to HTTPS 8443 once that is up."""
        r = self.http("GET", f"http://{self.host}:{LAUNCHER_PORT}{path}")
        location = r.headers.get("location", "")
        if 300 <= r.status < 400 and location.startswith("https://"):
            r = self.http("GET", location)
        return r

    def launch_mode(self, mode):
        r = self.launcher_get(f"/launch-mode?mode={mode}")
        status = _status_param(r.headers.get("location"))
        if not 300 <= r.status < 400 or status.startswith("Unknown mode"):
            raise CheckFailed(f"launcher refused /launch-mode?mode={mode}: HTTP {r.status} {status}")
        return r.headers.get("location", "")

    def presence(self, mode):
        """True/False from the mode's presence route; None when it doesn't answer (the
        mode's process isn't running) or answers for another mode."""
        try:
            r = self.http("GET", f"http://{self.host}:{MODE_PORTS[mode]}/presence")
            data = json.loads(r.body)
        except (OSError, ValueError):
            return None
        if r.status != 200 or not isinstance(data, dict) or data.get("mode") != mode:
            return None
        return data.get("active") is True

    def voice_state(self):
        try:
            r = self.http("GET", f"https://{self.host}:{VOICE_HTTPS_PORT}/voice-state")
        except OSError:
            return None
        if r.status != 200:
            return None
        state = r.body.decode("utf-8", "replace").strip()
        if state and (not self.states_seen or self.states_seen[-1] != state):
            self.states_seen.append(state)
        return state

    def settings_form(self):
        r = self.http("GET", f"https://{self.host}:{VOICE_HTTPS_PORT}/")
        if r.status != 200:
            raise CheckFailed(f"settings page answered HTTP {r.status}")
        return parse_settings_form(r.body.decode("utf-8", "replace"))

    def set_turn_taking(self, strict):
        """Save the strict turn-taking switch through the settings form (token and all);
        returns the value it had before."""
        form = self.settings_form()
        body = settings_post_body(form, strict).encode()
        r = self.http("POST", f"https://{self.host}:{VOICE_HTTPS_PORT}/", body=body,
                      headers={"Content-Type": "application/x-www-form-urlencoded"})
        status = _status_param(r.headers.get("location"))
        if not status.startswith("Saved"):
            raise CheckFailed(f"settings not saved (HTTP {r.status}): {status or r.body[:200]!r}")
        return form["turn_taking"]

    def force_exit_voice(self):
        """The launcher's exit request to the mode (LauncherApp.launchModeGracefully)."""
        self.adb("shell", "am", "start", "-n", VOICE_ACTIVITY, "--ez", FORCE_EXIT_EXTRA, "true",
                 "-f", EXIT_INTENT_FLAGS)

    def resumed_activity(self):
        out = self.adb("shell", "dumpsys", "activity", "activities").stdout
        for line in out.splitlines():
            if "ResumedActivity" in line:
                return line.strip()
        return ""

    def check_serviceexam_disabled(self):
        out = self.adb("shell", "pm", "list", "packages", "-d").stdout
        if f"package:{SERVICEEXAM_PKG}" not in out.split():
            raise CheckFailed(f"{SERVICEEXAM_PKG} is not in `pm list packages -d`: it is enabled "
                              "(or gone). Disable it again before using the robot.")
        return f"{SERVICEEXAM_PKG} still disabled"


class Relay:
    def __init__(self, host, port, http=None):
        self.base = f"http://{host}:{port}"
        self._http = http

    def get(self, path):
        return (self._http or http_request)("GET", self.base + path, timeout=HTTP_TIMEOUT)

    def conversations(self):
        """The relay's conversation list. OSError when it doesn't answer; CheckFailed
        when something else does."""
        r = self.get("/conversations")
        if r.status != 200:
            raise CheckFailed(f"{self.base}/conversations answered HTTP {r.status}")
        try:
            items = json.loads(r.body)["conversations"]
        except (ValueError, KeyError, TypeError):
            raise CheckFailed(f"{self.base}/conversations is not the relay's log listing") from None
        _use_relay_package()
        from relay.logging import CONVERSATION_ID
        return [c for c in items if isinstance(c, dict)
                and isinstance(c.get("id"), str) and CONVERSATION_ID.fullmatch(c["id"])]

    def ids(self):
        return {c["id"] for c in self.conversations()}

    def up(self):
        try:
            self.conversations()
            return True
        except (OSError, CheckFailed):
            return False

    def close_reason(self, conv_id):
        for c in self.conversations():
            if c["id"] == conv_id:
                return c.get("close_reason")
        return None

    def records(self, conv_id):
        r = self.get(f"/conversations/{conv_id}")
        if r.status != 200:
            raise CheckFailed(f"relay log {conv_id} answered HTTP {r.status}")
        out = []
        for line in r.body.decode("utf-8", "replace").splitlines():
            try:
                out.append(json.loads(line))
            except ValueError:
                pass
        return out

    def fetch_new(self, known, out_dir):
        """Save every conversation log not in known to out_dir/<id>.jsonl."""
        out_dir.mkdir(parents=True, exist_ok=True)
        files = []
        for c in self.conversations():
            if c["id"] in known:
                continue
            r = self.get(f"/conversations/{c['id']}")
            if r.status == 200:
                path = out_dir / f"{c['id']}.jsonl"
                path.write_bytes(r.body)
                files.append(path)
        return files


class RelayProcess:
    """The relay run by this script (--relay-cmd), so AE9/AE10 can stop and start it."""

    def __init__(self, cmd, relay, log_path):
        self.cmd = shlex.split(cmd)
        self.relay = relay
        self.log_path = log_path
        self.proc = None

    def start(self, timeout=20):
        self.log_path.parent.mkdir(parents=True, exist_ok=True)
        with open(self.log_path, "ab") as log:
            self.proc = subprocess.Popen(self.cmd, cwd=REPO, stdout=log, stderr=subprocess.STDOUT)
        wait_for("the relay to answer", self.relay.up, timeout)

    def stop(self):
        if self.proc is None or self.proc.poll() is not None:
            return
        self.proc.terminate()
        try:
            self.proc.wait(10)
        except subprocess.TimeoutExpired:
            self.proc.kill()
            self.proc.wait()


def wait_for(what, fn, timeout, interval=0.5):
    """fn()'s first truthy value within timeout seconds, else CheckFailed."""
    deadline = time.monotonic() + timeout
    while True:
        value = fn()
        if value:
            return value
        if time.monotonic() >= deadline:
            raise CheckFailed(f"timed out after {timeout:.0f}s waiting for {what}")
        time.sleep(interval)


def _use_relay_package():
    """Makes the relay package importable (its logging and report modules are stdlib only)."""
    if str(RELAY_ROOT) not in sys.path:
        sys.path.insert(0, str(RELAY_ROOT))


def build_report(log_dir, calibration_ms=None):
    """relay.report's report over log_dir (imported from the relay package, stdlib only)."""
    _use_relay_package()
    from relay import report
    return report.build_report([log_dir], calibration_ms=calibration_ms)


def render_report(r):
    from relay import report
    return report.render_markdown(r)


# --- the checklist ---

@dataclass
class Result:
    name: str
    status: str
    detail: str = ""
    human: bool = False


class Checklist:
    """Runs named checks and keeps their results. A human check (needs someone in the
    room) is manual-skipped, not run, when the run is non-interactive."""

    def __init__(self, interactive=True, input_fn=input, out=sys.stdout, only=None):
        self.interactive = interactive
        self.input_fn = input_fn
        self.out = out
        self.only = only or []
        self.results = []

    def say(self, text):
        print(text, file=self.out, flush=True)

    def record(self, name, status, detail="", human=False):
        result = Result(name, status, detail, human)
        self.results.append(result)
        self.say(f"   -> {status}" + (f": {detail}" if detail else ""))
        return result

    def selected(self, name):
        return not self.only or any(name.startswith(p) for p in self.only)

    def check(self, name, fn, human=False):
        if not self.selected(name):
            return self.record(name, SKIPPED, "not selected (--only)", human)
        self.say(f"== {name} ==")
        if human and not self.interactive:
            return self.record(name, MANUAL_SKIPPED, "needs a person at the robot "
                                                     "(--non-interactive)", human)
        try:
            detail = fn()
        except (CheckFailed, AdbError, OSError) as exc:
            return self.record(name, FAIL, str(exc), human)
        except Exception as exc:  # noqa: BLE001 - a broken step fails, the run goes on
            return self.record(name, FAIL, f"{type(exc).__name__}: {exc}", human)
        return self.record(name, PASS, "" if detail is None else str(detail), human)

    def prompt(self, text):
        """Show an instruction and wait for Enter."""
        self.say(f"   >> {text}")
        self.input_fn("   press Enter to continue ")

    def instruct(self, text):
        """Show an instruction; the check then polls for its effect."""
        self.say(f"   >> {text}")

    def confirm(self, question):
        while True:
            answer = self.input_fn(f"   ?? {question} [y/n] ").strip().lower()
            if answer in ("y", "yes"):
                return True
            if answer in ("n", "no"):
                return False

    def ask_text(self, question):
        return self.input_fn(f"   ?? {question} ").strip()

    def counts(self):
        out = {}
        for r in self.results:
            out[r.status] = out.get(r.status, 0) + 1
        return out

    def exit_code(self):
        return 1 if any(r.status == FAIL for r in self.results) else 0

    def markdown(self):
        rows = ["## Checklist", "", "| Check | Result | Kind | Detail |", "|---|---|---|---|"]
        for r in self.results:
            detail = r.detail.replace("|", "\\|").replace("\n", " ")
            rows.append(f"| {r.name} | {r.status} | {'manual' if r.human else 'auto'} | {detail} |")
        return "\n".join(rows) + "\n"


class Run:
    """The scenarios, in checklist order, sharing the robot, the relay and the ids of
    the conversations they open."""

    def __init__(self, args, cl, robot, relay, relay_proc, baseline, out_dir):
        self.args, self.cl, self.robot, self.relay = args, cl, robot, relay
        self.relay_proc = relay_proc
        self.baseline = baseline
        self.out_dir = out_dir
        self.open_conv = None  # the conversation the wake check opened, for AE3
        self.original_turn_taking = None
        self.report_data = None  # relay.report's report, once the report step has run

    # helpers

    def wait_state(self, states, timeout):
        seen = []

        def poll():
            s = self.robot.voice_state()
            seen.append(s)
            return s if s in states else None
        try:
            return wait_for(f"voice state {'/'.join(sorted(states))}", poll, timeout)
        except CheckFailed as exc:
            raise CheckFailed(f"{exc} (last state: {seen[-1] if seen else None})") from None

    def wait_new_conversation(self, known, timeout=30):
        def poll():
            new = sorted(self.relay.ids() - known)
            return new[-1] if new else None
        return wait_for("a new conversation on the relay", poll, timeout)

    def wait_closed(self, conv, timeout):
        return wait_for(f"conversation {conv} to close", lambda: self.relay.close_reason(conv), timeout)

    def expect_closed(self, conv, reasons, timeout):
        reason = self.wait_closed(conv, timeout)
        if reason not in reasons:
            raise CheckFailed(f"conversation {conv} closed with {reason}, expected "
                              + " or ".join(reasons))
        return reason

    def relay_down(self):
        if self.relay_proc is not None:
            self.relay_proc.stop()
        else:
            self.cl.prompt("Stop the relay now (Ctrl-C in its terminal).")
        wait_for("the relay to stop answering", lambda: not self.relay.up(), 15)

    def relay_up(self):
        if self.relay_proc is not None:
            self.relay_proc.start()
        else:
            self.cl.prompt("Start the relay again.")
            wait_for("the relay to answer", self.relay.up, 30)
        # The robot's reconnect backoff caps at 30 s (Implementation Constraints).
        return self.wait_state({"listening"}, 60)

    def must_confirm(self, question):
        if not self.cl.confirm(question):
            raise CheckFailed(f"operator: no — {question}")

    # checks

    def install(self):
        cmd = [sys.executable, str(INSTALL_PY), "--serial", self.args.serial]
        if self.args.no_build:
            cmd.append("--no-build")
        r = subprocess.run(cmd, timeout=INSTALL_TIMEOUT)
        if r.returncode != 0:
            raise CheckFailed(f"install-mode-voice.py exited {r.returncode} (see its output above)")
        return "installed, RECORD_AUDIO granted"

    def launch(self):
        location = self.robot.launch_mode("voice")
        return f"launcher redirected to {location}"

    def presence_active(self):
        wait_for("voice presence active", lambda: self.robot.presence("voice"), 20)
        return "8082/presence says active"

    def listening(self):
        try:
            self.wait_state({"listening"}, 30)
        except CheckFailed as exc:
            raise CheckFailed(f"{exc}; if it says unreachable, check the relay address on "
                              f"https://{self.robot.host}:{VOICE_HTTPS_PORT}/") from None
        return "voice-state listening"

    def ae1_quiet(self):
        known = self.relay.ids()
        time.sleep(self.args.quiet_seconds)
        new = self.relay.ids() - known
        if new:
            raise CheckFailed(f"conversations opened with no wake: {sorted(new)}")
        state = self.robot.voice_state()
        if state != "listening":
            raise CheckFailed(f"voice state {state} after {self.args.quiet_seconds:.0f}s idle")
        return f"no conversation in {self.args.quiet_seconds:.0f}s of listening"

    def ae2_room_talk(self):
        secs = self.args.ae2_seconds
        known = self.relay.ids()
        self.cl.prompt(f"Talk normally near the robot for {secs:.0f}s WITHOUT saying "
                       "'Hey Miko'. The timer starts when you press Enter.")
        time.sleep(secs)
        new = self.relay.ids() - known
        if new:
            raise CheckFailed(f"a conversation opened without the wake word: {sorted(new)}")
        return f"no conversation in {secs:.0f}s of room talk (the plan's full check is an hour)"

    def wake(self):
        known = self.relay.ids()
        self.cl.instruct("Say 'Hey Miko', then ask a short question. Leave the conversation open.")
        conv = self.wait_new_conversation(known)
        state = self.wait_state({"conversing", "speaking"}, 15)
        self.open_conv = conv
        return f"conversation {conv} opened; eyes state {state}"

    def ae3_sleep_word(self):
        conv = self.open_conv
        if conv is None:
            raise CheckFailed("needs the conversation the wake check opens")
        self.cl.instruct("Say 'Goodbye Miko'.")
        self.expect_closed(conv, ["sleep_word"], 30)
        self.open_conv = None
        self.wait_state({"listening"}, 20)
        self.must_confirm("Did the farewell play to its end before the eyes went back to listening?")
        return f"{conv} closed sleep_word; listening again"

    def ae4_silence(self):
        known = self.relay.ids()
        self.cl.instruct(f"Say 'Hey Miko', then stay silent. The relay closes the conversation "
                         f"after its silence timeout ({self.args.silence_timeout:.0f}s).")
        conv = self.wait_new_conversation(known)
        self.expect_closed(conv, ["silence"], self.args.silence_timeout + 30)
        self.wait_state({"listening"}, 20)
        self.must_confirm("Did it go back to listening with no farewell?")
        return f"{conv} closed silence"

    def turn_taking(self, strict):
        previous = self.robot.set_turn_taking(strict)
        if self.original_turn_taking is None:
            self.original_turn_taking = previous
        known = self.relay.ids()
        self.cl.instruct("Say 'Hey Miko' and ask for something long ('tell me a story').")
        conv = self.wait_new_conversation(known)
        self.wait_state({"speaking"}, 60)
        if strict:
            self.cl.instruct("While it is speaking, say 'Goodbye Miko' and then a question.")
            asked = ("Did the reply play to its end, with nothing you said during it "
                     "(not even 'Goodbye Miko') answered?")
        else:
            self.cl.instruct("While it is speaking, interrupt: 'wait, what is two plus two?'")
            asked = "Did the robot stop its reply at once and answer what you said?"
        confirmed = self.cl.confirm(asked)
        self.cl.instruct("Now end the conversation: say 'Goodbye Miko' after the robot finishes.")
        self.wait_closed(conv, 60)
        records = self.relay.records(conv)
        opened = next((r for r in records if r.get("ev") == "open"), {})
        flushed = any(r.get("ev") == "relay" and (r.get("msg") or {}).get("type") == "audio.flush"
                      for r in records)
        gated = any(r.get("ev") == "gate.close" for r in records)
        expected = "strict" if strict else "interruptible"
        problems = []
        if opened.get("turn_taking") != expected:
            problems.append(f"relay log says turn_taking {opened.get('turn_taking')}")
        if strict and (flushed or not gated):
            problems.append(f"log: audio.flush {flushed}, gate.close {gated}")
        if not strict and not flushed:
            problems.append("log has no audio.flush: the reply was not interrupted")
        if not confirmed:
            problems.append(f"operator: no — {asked}")
        if problems:
            raise CheckFailed(f"{conv}: " + "; ".join(problems))
        return f"{conv}: {expected}, " + ("gate closed during replies" if strict else "reply flushed")

    def restore_turn_taking(self):
        if self.original_turn_taking is None:
            return "unchanged"
        self.robot.set_turn_taking(self.original_turn_taking)
        return "strict" if self.original_turn_taking else "interruptible"

    def ae7_no_memory(self):
        self.cl.prompt("Say 'Hey Miko', tell it your name, then say 'Goodbye Miko' and wait "
                       "for the eyes to go back to listening.")
        self.cl.prompt("Say 'Hey Miko' and ask 'what is my name?', then 'Goodbye Miko'.")
        self.must_confirm("Did it NOT know your name in the second conversation?")
        return "operator confirmed: no memory across conversations"

    def ae8_unknown_command(self):
        self.relay_down()
        self.cl.prompt("On the relay host, start the lane stub in place of the relay:\n"
                       "      relay/.venv/bin/python relay/tests/relay_stub.py --unknown-cmd dance\n"
                       "   then say 'Hey Miko' (the stub plays a tone and sends cmd{action: dance}).")
        self.must_confirm("Did the stub print '<- cmd.result' with 'unsupported', the robot not "
                          "move, and the conversation carry on (the tone kept playing)?")
        self.cl.prompt("Stop the stub (Ctrl-C).")
        self.relay_up()
        return "robot answered unsupported; relay restored"

    def ae9_relay_down(self):
        self.relay_down()
        self.wait_state({"unreachable"}, 30)
        self.cl.instruct("Say 'Hey Miko'.")
        self.must_confirm("Did the eyes stay in the unreachable look, with no sound?")
        self.relay_up()
        known = self.relay.ids()
        self.cl.instruct("Say 'Hey Miko' again, then 'Goodbye Miko'.")
        conv = self.wait_new_conversation(known, 60)
        self.wait_closed(conv, 60)
        return f"unreachable while down; {conv} opened once the relay was back"

    def ae10_link_drop(self):
        known = self.relay.ids()
        self.cl.instruct("Say 'Hey Miko' and ask for a long story.")
        self.wait_new_conversation(known)
        self.wait_state({"speaking", "conversing"}, 30)
        self.relay_down()
        self.wait_state({"unreachable"}, 30)
        self.must_confirm("Did the sound stop and the eyes change to unreachable?")
        self.relay_up()
        return "conversation ended on link loss; unreachable shown; listening after"

    def ae11_state_looks(self):
        seen = ", ".join(self.robot.states_seen) or "none"
        self.must_confirm("Over this run, did 'conversation open', 'robot speaking' and "
                          "'relay unreachable' each show as a distinct change in the eyes?")
        return f"states polled: {seen}"

    def ae12_exit_mid_reply(self):
        known = self.relay.ids()
        self.cl.instruct("Say 'Hey Miko' and ask for a long story.")
        conv = self.wait_new_conversation(known)
        self.wait_state({"speaking"}, 60)
        self.robot.force_exit_voice()
        wait_for("voice presence inactive", lambda: self.robot.presence("voice") is not True, 10)
        resumed = self.robot.resumed_activity()
        reason = self.expect_closed(conv, ["robot_request", "link_lost"], 20)
        self.must_confirm("Did the speaker go silent at once and the launcher home screen appear?")
        problem = None if LAUNCHER_PKG in resumed else f"resumed activity is not the launcher: {resumed}"
        self.robot.launch_mode("voice")
        wait_for("voice presence active", lambda: self.robot.presence("voice"), 20)
        self.wait_state({"listening"}, 30)
        if problem:
            raise CheckFailed(problem)
        return f"{conv} closed {reason}; launcher resumed; relaunched"

    def mode_switch(self):
        other = "remote-control"
        for i in range(1, 4):
            self.robot.launch_mode(other)
            wait_for(f"round {i}: remote-control active, voice not",
                     lambda: self.robot.presence(other) and self.robot.presence("voice") is not True, 30)
            self.robot.launch_mode("voice")
            wait_for(f"round {i}: voice active, remote-control not",
                     lambda: self.robot.presence("voice") and self.robot.presence(other) is not True, 30)
            self.wait_state({"listening"}, 30)
        return "3 round trips, presence correct each way, listening after each"

    def five_minutes(self):
        known = self.relay.ids()
        self.cl.instruct("Say 'Hey Miko' and hold a conversation for at least five minutes, "
                         "then say 'Goodbye Miko'.")
        conv = self.wait_new_conversation(known)
        reason = self.wait_closed(conv, self.args.five_minute_timeout)
        return f"{conv} closed {reason}; judged by the report's five_minute_run"

    def serviceexam(self):
        return self.robot.check_serviceexam_disabled()

    # the report

    def report(self):
        try:
            logcat = self.robot.adb("logcat", "-d", timeout=60).stdout
            (self.out_dir / "logcat.txt").write_text(logcat)
        except AdbError as exc:
            self.cl.say(f"   (logcat not saved: {exc})")
        files = self.relay.fetch_new(self.baseline, self.out_dir / "conversations")
        calibration = list(self.args.calibration_ms)
        if not calibration and self.cl.interactive:
            text = self.cl.ask_text("Acoustic calibration gaps in ms, comma-separated "
                                    "(blank to skip):")
            calibration = [float(v) for v in re.split(r"[,\s]+", text) if v]
        r = build_report(self.out_dir / "conversations", calibration_ms=calibration)
        self.report_data = r
        for name, c in r["criteria"].items():
            self.cl.record(f"criteria.{name}", CRITERION_STATUS.get(c["status"], FAIL), c["detail"])
        return f"{len(files)} conversation logs fetched"


def run_checklist(args, cl, robot, relay, relay_proc, baseline, out_dir):
    run = Run(args, cl, robot, relay, relay_proc, baseline, out_dir)
    if args.install:
        cl.check("install", run.install)
    else:
        cl.say("== install ==")
        cl.record("install", SKIPPED, "not asked for (--install)")
    cl.check("launch", run.launch)
    cl.check("presence.active", run.presence_active)
    cl.check("voice_state.listening", run.listening)
    cl.check("ae1.no_conversation_before_wake", run.ae1_quiet)
    cl.check("ae2.room_talk_no_wake", run.ae2_room_talk, human=True)
    cl.check("wake.conversing", run.wake, human=True)
    cl.check("ae3.sleep_word", run.ae3_sleep_word, human=True)
    cl.check("ae4.silence", run.ae4_silence, human=True)
    try:
        cl.check("ae5.interrupt_interruptible", lambda: run.turn_taking(False), human=True)
        cl.check("ae6.interrupt_strict", lambda: run.turn_taking(True), human=True)
    finally:
        if run.original_turn_taking is not None:
            cl.check("settings.restore_turn_taking", run.restore_turn_taking)
    cl.check("ae7.no_memory", run.ae7_no_memory, human=True)
    cl.check("ae8.unknown_command", run.ae8_unknown_command, human=True)
    cl.check("ae9.relay_down", run.ae9_relay_down, human=True)
    cl.check("ae10.link_drop", run.ae10_link_drop, human=True)
    cl.check("ae11.state_looks", run.ae11_state_looks, human=True)
    cl.check("ae12.exit_mid_reply", run.ae12_exit_mid_reply, human=True)
    cl.check("mode_switch.x3", run.mode_switch)
    if args.five_minutes:
        cl.check("five_minute_conversation", run.five_minutes, human=True)
    cl.check("safety.serviceexam_disabled", run.serviceexam)
    cl.check("report", run.report)
    return run


def preflight(robot, relay, relay_proc):
    """Everything the scenarios need, before any of them runs; SetupError otherwise."""
    print(f"== setup: robot adb at {robot.serial} ==", flush=True)
    try:
        robot.ensure_adb()
    except AdbError as exc:
        raise SetupError(f"!! {exc}\n   Check the robot is powered on and on Wi-Fi, and that root "
                         "adbd is listening (python3 scripts/verify-persistent-adb.py), "
                         "or pass --serial.") from None
    url = f"{robot.host}:{LAUNCHER_PORT}"
    print(f"== setup: robot HTTP (launcher) at {url} ==", flush=True)
    try:
        robot.http("GET", f"http://{url}/")
    except OSError as exc:
        raise SetupError(f"!! the launcher at {url} is not answering HTTP ({exc}).\n"
                         "   Is the custom launcher installed and running?") from None
    print(f"== setup: relay logs at {relay.base}/conversations ==", flush=True)
    try:
        if relay_proc is not None:
            relay_proc.start()
        relay.conversations()
    except (OSError, CheckFailed) as exc:
        where = relay.base.split("//", 1)[1]
        raise SetupError(f"!! the relay at {where} is not serving its conversation logs "
                         f"({exc}).\n   Start it (relay/README.md) or fix --relay-host / "
                         "--relay-http-port.") from None


def parse_args(argv=None):
    ap = argparse.ArgumentParser(description="End-to-end QA run of the voice mode (plan U10).")
    ap.add_argument("--serial", default=DEFAULT_SERIAL,
                    help=f"adb serial (default: {DEFAULT_SERIAL}; host:port is adb-connected first)")
    ap.add_argument("--robot-host", help="robot address for HTTP (default: the serial's host)")
    ap.add_argument("--relay-host", default=os.environ.get("RELAY_HOST"),
                    help="relay address (default: $RELAY_HOST); required")
    ap.add_argument("--relay-http-port", type=int, default=RELAY_HTTP_PORT,
                    help=f"relay conversation-log port (default {RELAY_HTTP_PORT})")
    ap.add_argument("--relay-cmd", help="start the relay with this command (on this host) so "
                                        "the script can stop and start it for AE8-AE10")
    ap.add_argument("--install", action="store_true",
                    help="install the mode first (scripts/install-mode-voice.py)")
    ap.add_argument("--no-build", action="store_true", help="with --install: use the existing APK")
    ap.add_argument("--non-interactive", action="store_true",
                    help="run only the automatic checks; mark human ones manual-skipped")
    ap.add_argument("--only", type=lambda s: [p for p in s.split(",") if p],
                    help="comma-separated check-name prefixes to run (e.g. ae3,ae4,report)")
    ap.add_argument("--silence-timeout", type=float, default=90.0,
                    help="the relay's --silence-timeout, for AE4's wait (default 90; run the "
                         "relay with 15 for QA)")
    ap.add_argument("--quiet-seconds", type=float, default=10.0,
                    help="AE1: idle listening time with no conversation expected (default 10)")
    ap.add_argument("--ae2-seconds", type=float, default=60.0,
                    help="AE2: room talk without the wake word (default 60)")
    ap.add_argument("--five-minutes", action="store_true",
                    help="include the five-minute conversation (success criterion)")
    ap.add_argument("--five-minute-timeout", type=float, default=900.0,
                    help="longest wait for the five-minute conversation to close (default 900)")
    ap.add_argument("--calibration-ms", type=float, action="append", default=[], metavar="MS",
                    help="acoustic gap, last word to first robot sound; repeatable")
    ap.add_argument("--out-dir", type=Path,
                    help="where logs and the report go (default relay/out/qa/<time>)")
    return ap.parse_args(argv)


def main(argv=None, input_fn=input):
    args = parse_args(argv)
    if not args.relay_host:
        raise SetupError("!! no relay address: pass --relay-host (or set RELAY_HOST). Every "
                         "scenario is checked against the relay's conversation logs.")
    host = robot_host(args)
    if host is None:
        raise SetupError(f"!! can't tell the robot's address from serial {args.serial!r}: "
                         "pass --robot-host.")
    out_dir = args.out_dir or RELAY_ROOT / "out" / "qa" / time.strftime("%Y%m%dT%H%M%S")
    robot = Robot(args.serial, host)
    relay = Relay(args.relay_host, args.relay_http_port)
    relay_proc = RelayProcess(args.relay_cmd, relay, out_dir / "relay.log") if args.relay_cmd else None
    try:
        preflight(robot, relay, relay_proc)
        out_dir.mkdir(parents=True, exist_ok=True)
        baseline = relay.ids()
        try:
            robot.adb("logcat", "-c")
        except AdbError as exc:
            print(f"   (logcat not cleared: {exc})", flush=True)
        cl = Checklist(interactive=not args.non_interactive, input_fn=input_fn, only=args.only)
        run = run_checklist(args, cl, robot, relay, relay_proc, baseline, out_dir)
    finally:
        if relay_proc is not None:
            relay_proc.stop()

    report = run.report_data
    md = (render_report(report) + "\n" if report else "") + cl.markdown()
    (out_dir / "report.md").write_text(md)
    (out_dir / "report.json").write_text(json.dumps(
        {"report": report, "checks": [r.__dict__ for r in cl.results]}, indent=2))
    counts = ", ".join(f"{v} {k}" for k, v in sorted(cl.counts().items()))
    print(f"\n{md}\nDone: {counts}. Report: {out_dir / 'report.md'}")
    return cl.exit_code()


if __name__ == "__main__":
    sys.exit(main())
