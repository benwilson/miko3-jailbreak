#!/usr/bin/env python3
"""Tests for scripts/qa-voice-mode.py (U10).

Covers what can be checked without a robot: argument parsing and its defaults, the
setup failures that must stop the run before any scenario (no relay address, robot not
reachable over adb or HTTP, relay not answering), the settings page's token parse and
the form POST built from it, the checklist's pass/fail/manual-skipped bookkeeping and exit
code, and fetching the run's conversation logs into the report. The full scripted run on
the robot is the plan's on-device scenario, not this file.
"""
import importlib.util
import io
import json
import subprocess
import tempfile
import types
import unittest
from pathlib import Path
from unittest import mock
from urllib.parse import parse_qs

REPO = Path(__file__).resolve().parents[2]
QA_PY = REPO / "scripts" / "qa-voice-mode.py"


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load {path}")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


qa = load("qa_voice_mode", QA_PY)

SERIAL = "192.168.19.74:5555"


def done(stdout="", returncode=0, stderr=""):
    return types.SimpleNamespace(stdout=stdout, stderr=stderr, returncode=returncode)


class FakeAdb:
    """Records every command; answers from a {substring: result} table."""

    def __init__(self, answers=None):
        self.calls = []
        self.answers = answers or {}

    def __call__(self, cmd, **kw):
        self.calls.append(cmd)
        joined = " ".join(cmd)
        for key, result in self.answers.items():
            if key in joined:
                if isinstance(result, BaseException):
                    raise result
                return result
        return done()


class FakeHttp:
    """Records every request; answers from a list of (method, url substring, response)."""

    def __init__(self, routes=()):
        self.calls = []
        self.routes = list(routes)

    def __call__(self, method, url, body=None, headers=None, timeout=None):
        self.calls.append((method, url, body))
        for m, part, answer in self.routes:
            if m == method and part in url:
                if isinstance(answer, BaseException):
                    raise answer
                return answer
        raise ConnectionRefusedError(f"no route for {method} {url}")


def response(status=200, body=b"", headers=None):
    return qa.Response(status, headers or {}, body)


SETTINGS_HTML = (
    '<!DOCTYPE html><html><body><p id="voice-state">State: <strong>Listening</strong></p>'
    '<form method="post" action="/">'
    '<input type="hidden" name="t" value="0f1e2d3c4b5a69788796a5b4c3d2e1f0">'
    '<label for="relay">Relay address<input type="text" id="relay" name="relay" '
    'value="192.168.19.20:8790" placeholder="192.168.1.50:8790" autocomplete="off"></label>'
    '<label for="turn_taking"><input type="checkbox" role="switch" id="turn_taking" '
    'name="turn_taking"> Strict turn-taking</label>'
    '<button type="submit">Save</button></form>'
    '<form method="post" action="/exit"><input type="hidden" name="t" '
    'value="0f1e2d3c4b5a69788796a5b4c3d2e1f0"><button>Exit</button></form></body></html>')


class ArgsTest(unittest.TestCase):
    def test_defaults(self):
        with mock.patch.dict(qa.os.environ, {}, clear=True):
            a = qa.parse_args([])
        self.assertEqual(a.serial, SERIAL)
        self.assertIsNone(a.relay_host)
        self.assertEqual(a.relay_http_port, 8791)
        self.assertFalse(a.non_interactive)
        self.assertFalse(a.install)
        self.assertEqual(a.calibration_ms, [])

    def test_relay_host_from_the_environment_and_flags(self):
        with mock.patch.dict(qa.os.environ, {"RELAY_HOST": "10.0.0.9"}, clear=True):
            self.assertEqual(qa.parse_args([]).relay_host, "10.0.0.9")
            a = qa.parse_args(["--relay-host", "10.0.0.2", "--relay-http-port", "9000",
                               "--serial", "10.0.0.5:5555", "--non-interactive",
                               "--calibration-ms", "900", "--calibration-ms", "950",
                               "--silence-timeout", "15", "--only", "ae3,ae4"])
        self.assertEqual(a.relay_host, "10.0.0.2")
        self.assertEqual(a.relay_http_port, 9000)
        self.assertTrue(a.non_interactive)
        self.assertEqual(a.calibration_ms, [900.0, 950.0])
        self.assertEqual(a.silence_timeout, 15.0)
        self.assertEqual(a.only, ["ae3", "ae4"])

    def test_robot_host_comes_from_the_serial(self):
        with mock.patch.dict(qa.os.environ, {}, clear=True):
            self.assertEqual(qa.robot_host(qa.parse_args(["--serial", "10.0.0.5:5555"])), "10.0.0.5")
            self.assertEqual(qa.robot_host(qa.parse_args(["--robot-host", "10.0.0.7"])), "10.0.0.7")
            self.assertIsNone(qa.robot_host(qa.parse_args(["--serial", "ABC123"])))


class SetupFailureTest(unittest.TestCase):
    """Every setup failure stops main() before a single scenario runs."""

    def run_main(self, argv, adb, http):
        scenario = mock.Mock()
        with mock.patch.object(qa.subprocess, "run", adb), \
                mock.patch.object(qa, "http_request", http), \
                mock.patch.object(qa, "run_checklist", scenario), \
                mock.patch.dict(qa.os.environ, {}, clear=True), \
                mock.patch("sys.stdout", io.StringIO()):
            with self.assertRaises(qa.SetupError) as cm:
                qa.main(argv)
        scenario.assert_not_called()
        return str(cm.exception.code)

    def test_missing_relay_address(self):
        adb = FakeAdb()
        msg = self.run_main(["--non-interactive"], adb, FakeHttp())
        self.assertIn("relay address", msg)
        self.assertEqual(adb.calls, [])  # checked before touching the robot

    def test_robot_unreachable_over_adb_fails_fast(self):
        adb = FakeAdb({"get-state": done("", 1, "error: device offline")})
        http = FakeHttp()
        msg = self.run_main(["--relay-host", "10.0.0.2", "--non-interactive"], adb, http)
        self.assertIn("not reachable over adb", msg)
        self.assertIn(SERIAL, msg)
        self.assertIn("device offline", msg)
        self.assertEqual(http.calls, [])
        self.assertEqual(adb.calls[0], ["adb", "connect", SERIAL])

    def test_adb_hang_is_a_setup_failure_not_a_hang(self):
        adb = FakeAdb({"connect": subprocess.TimeoutExpired("adb", 15)})
        msg = self.run_main(["--relay-host", "10.0.0.2"], adb, FakeHttp())
        self.assertIn("timed out", msg)

    def test_no_adb_on_path(self):
        adb = FakeAdb({"connect": FileNotFoundError("adb")})
        msg = self.run_main(["--relay-host", "10.0.0.2"], adb, FakeHttp())
        self.assertIn("adb not found", msg)

    def test_robot_http_unreachable(self):
        adb = FakeAdb({"get-state": done("device\n")})
        msg = self.run_main(["--relay-host", "10.0.0.2"], adb, FakeHttp())
        self.assertIn("192.168.19.74:8080", msg)

    def test_relay_unreachable(self):
        adb = FakeAdb({"get-state": done("device\n")})
        http = FakeHttp([("GET", ":8080/", response(302, headers={"location": "https://x:8443/"}))])
        msg = self.run_main(["--relay-host", "10.0.0.2"], adb, http)
        self.assertIn("relay", msg)
        self.assertIn("10.0.0.2:8791", msg)

    def test_relay_answering_something_else(self):
        adb = FakeAdb({"get-state": done("device\n")})
        http = FakeHttp([("GET", ":8080/", response(200)),
                         ("GET", "10.0.0.2:8791/conversations", response(404, b"not found"))])
        msg = self.run_main(["--relay-host", "10.0.0.2"], adb, http)
        self.assertIn("404", msg)


class SettingsFormTest(unittest.TestCase):
    def test_parse_takes_the_save_forms_fields(self):
        form = qa.parse_settings_form(SETTINGS_HTML)
        self.assertEqual(form, {"t": "0f1e2d3c4b5a69788796a5b4c3d2e1f0",
                                "relay": "192.168.19.20:8790", "turn_taking": False})

    def test_parse_reads_a_checked_switch_and_unescapes_the_relay(self):
        html = SETTINGS_HTML.replace('name="turn_taking">', 'name="turn_taking" checked>') \
                            .replace("192.168.19.20:8790", "a&amp;b")
        form = qa.parse_settings_form(html)
        self.assertTrue(form["turn_taking"])
        self.assertEqual(form["relay"], "a&b")

    def test_parse_without_a_token_fails(self):
        with self.assertRaises(qa.CheckFailed):
            qa.parse_settings_form("<html><form action='/'></form></html>")

    def test_post_body_carries_token_relay_and_switch(self):
        form = {"t": "tok", "relay": "192.168.19.20:8790", "turn_taking": False}
        self.assertEqual(parse_qs(qa.settings_post_body(form, strict=True)),
                         {"t": ["tok"], "relay": ["192.168.19.20:8790"], "turn_taking": ["on"]})
        # Interruptible: the checkbox is simply absent, as a browser sends it.
        self.assertEqual(parse_qs(qa.settings_post_body(form, strict=False)),
                         {"t": ["tok"], "relay": ["192.168.19.20:8790"]})

    def test_set_turn_taking_gets_the_form_then_posts_it_over_https(self):
        http = FakeHttp([
            ("GET", "https://192.168.19.74:8445/", response(200, SETTINGS_HTML.encode())),
            ("POST", "https://192.168.19.74:8445/",
             response(303, headers={"location": "/?status=Saved."})),
        ])
        robot = qa.Robot(SERIAL, "192.168.19.74", http=http, run=FakeAdb())
        before = robot.set_turn_taking(strict=True)
        self.assertFalse(before)  # the previous value, for restoring it afterwards
        (m1, u1, _), (m2, u2, body) = http.calls
        self.assertEqual((m1, m2), ("GET", "POST"))
        self.assertEqual(u2, "https://192.168.19.74:8445/")
        fields = parse_qs(body.decode())
        self.assertEqual(fields["t"], ["0f1e2d3c4b5a69788796a5b4c3d2e1f0"])
        self.assertEqual(fields["turn_taking"], ["on"])
        self.assertEqual(fields["relay"], ["192.168.19.20:8790"])

    def test_a_refused_save_fails_the_check(self):
        http = FakeHttp([
            ("GET", ":8445/", response(200, SETTINGS_HTML.encode())),
            ("POST", ":8445/", response(303, headers={
                "location": "/?status=Not+saved%3A+this+page+had+expired."})),
        ])
        robot = qa.Robot(SERIAL, "192.168.19.74", http=http, run=FakeAdb())
        with self.assertRaises(qa.CheckFailed) as cm:
            robot.set_turn_taking(strict=False)
        self.assertIn("Not saved", str(cm.exception))


class ChecklistTest(unittest.TestCase):
    def make(self, interactive=True, answers=()):
        out = io.StringIO()
        answers = list(answers)
        ask = mock.Mock(side_effect=lambda prompt="": answers.pop(0))
        return qa.Checklist(interactive=interactive, input_fn=ask, out=out), ask, out

    def test_pass_fail_and_error_are_recorded(self):
        cl, _, _ = self.make()
        cl.check("a", lambda: "fine")

        def fails():
            raise qa.CheckFailed("nope")

        def breaks():
            raise RuntimeError("boom")

        cl.check("b", fails)
        cl.check("c", breaks)
        self.assertEqual([(r.name, r.status) for r in cl.results],
                         [("a", "pass"), ("b", "fail"), ("c", "fail")])
        self.assertEqual(cl.results[0].detail, "fine")
        self.assertIn("nope", cl.results[1].detail)
        self.assertIn("RuntimeError: boom", cl.results[2].detail)
        self.assertEqual(cl.exit_code(), 1)
        self.assertEqual(cl.counts(), {"pass": 1, "fail": 2})

    def test_non_interactive_marks_human_steps_manual_skipped_without_running_them(self):
        cl, ask, _ = self.make(interactive=False)
        body = mock.Mock(return_value="ran")
        cl.check("wake", body, human=True)
        body.assert_not_called()
        ask.assert_not_called()
        (r,) = cl.results
        self.assertEqual((r.status, r.human), ("manual-skipped", True))
        self.assertEqual(cl.exit_code(), 0)  # a skipped step is not a failure

    def test_interactive_human_step_prompts_and_confirms(self):
        cl, ask, _ = self.make(answers=["", "y"])

        def step():
            cl.prompt("Say 'Hey Miko'")
            if not cl.confirm("Did the eyes change?"):
                raise qa.CheckFailed("operator said no")
            return "operator confirmed"

        cl.check("ae11", step, human=True)
        self.assertEqual(cl.results[0].status, "pass")
        self.assertEqual(ask.call_count, 2)

    def test_operator_no_is_a_fail(self):
        cl, _, _ = self.make(answers=["n"])
        cl.check("ae7", lambda: None if cl.confirm("Forgot the name?") else
                 (_ for _ in ()).throw(qa.CheckFailed("remembered")), human=True)
        self.assertEqual(cl.results[0].status, "fail")

    def test_only_filter_skips_other_checks(self):
        cl, _, _ = self.make()
        cl.only = ["ae3"]
        body = mock.Mock(return_value="x")
        cl.check("ae4.silence", body)
        cl.check("ae3.sleep_word", body)
        self.assertEqual([r.status for r in cl.results], ["skipped", "pass"])
        self.assertEqual(body.call_count, 1)

    def test_markdown_table_lists_every_check(self):
        cl, _, _ = self.make(interactive=False)
        cl.check("launch", lambda: "ok")
        cl.check("wake", lambda: "x", human=True)
        md = cl.markdown()
        self.assertIn("| launch | pass | auto | ok |", md)
        self.assertIn("| wake | manual-skipped | manual |", md)


class RobotTest(unittest.TestCase):
    def test_presence_parses_the_mode_answer(self):
        http = FakeHttp([
            ("GET", ":8082/presence", response(200, b'{"mode":"voice","active":true}')),
            ("GET", ":8081/presence", response(200, b'{"mode":"remote-control","active":false}')),
        ])
        robot = qa.Robot(SERIAL, "h", http=http, run=FakeAdb())
        self.assertIs(robot.presence("voice"), True)
        self.assertIs(robot.presence("remote-control"), False)

    def test_presence_of_a_mode_that_is_not_running_is_none(self):
        robot = qa.Robot(SERIAL, "h", http=FakeHttp(), run=FakeAdb())
        self.assertIsNone(robot.presence("voice"))

    def test_voice_state_is_read_over_https(self):
        http = FakeHttp([("GET", "https://h:8445/voice-state", response(200, b"listening"))])
        self.assertEqual(qa.Robot(SERIAL, "h", http=http, run=FakeAdb()).voice_state(), "listening")

    def test_launch_follows_the_plain_launchers_https_redirect(self):
        http = FakeHttp([
            ("GET", "http://h:8080/launch-mode?mode=voice",
             response(302, headers={"location": "https://h:8443/launch-mode?mode=voice"})),
            ("GET", "https://h:8443/launch-mode?mode=voice",
             response(302, headers={"location": "http://h:8082/"})),
        ])
        qa.Robot(SERIAL, "h", http=http, run=FakeAdb()).launch_mode("voice")
        self.assertEqual(len(http.calls), 2)

    def test_launch_of_an_unknown_mode_fails(self):
        http = FakeHttp([("GET", "8080/launch-mode", response(
            302, headers={"location": "/?status=Unknown+mode%3A+x"}))])
        with self.assertRaises(qa.CheckFailed):
            qa.Robot(SERIAL, "h", http=http, run=FakeAdb()).launch_mode("x")

    def test_serviceexam_disabled_check_reads_the_disabled_list(self):
        adb = FakeAdb({"list packages -d": done("package:com.example.root.serviceexam\n")})
        robot = qa.Robot(SERIAL, "h", http=FakeHttp(), run=adb)
        self.assertIn("disabled", robot.check_serviceexam_disabled())
        self.assertEqual(adb.calls[0][:3], ["adb", "-s", SERIAL])
        robot = qa.Robot(SERIAL, "h", http=FakeHttp(), run=FakeAdb({"list packages -d": done("")}))
        with self.assertRaises(qa.CheckFailed):
            robot.check_serviceexam_disabled()

    def test_force_exit_sends_the_launchers_intent(self):
        adb = FakeAdb()
        qa.Robot(SERIAL, "h", http=FakeHttp(), run=adb).force_exit_voice()
        cmd = " ".join(adb.calls[0])
        self.assertIn("am start -n com.miko3.mode.voice/.MainActivity", cmd)
        self.assertIn("--ez com.miko3.launcher.EXTRA_FORCE_EXIT true", cmd)


class RelayLogsTest(unittest.TestCase):
    def test_new_conversations_are_fetched_and_reported(self):
        line = json.dumps({"ev": "turn", "n": 1, "user_end_to_first_chunk_ms": 500,
                           "robot_first_chunk_to_play_ms": 60, "half_ping_rtt_ms": 10})
        close = json.dumps({"ev": "close", "reason": "sleep_word", "duration_ms": 1000})
        listing = {"conversations": [{"id": "old-1", "close_reason": "silence"},
                                     {"id": "new-1", "close_reason": "sleep_word"}]}
        http = FakeHttp([
            ("GET", "/conversations/new-1", response(200, f"{line}\n{close}\n".encode())),
            ("GET", "/conversations", response(200, json.dumps(listing).encode())),
        ])
        relay = qa.Relay("r", 8791, http=http)
        with tempfile.TemporaryDirectory() as d:
            files = relay.fetch_new({"old-1"}, Path(d))
            self.assertEqual([f.name for f in files], ["new-1.jsonl"])
            report = qa.build_report(Path(d), calibration_ms=[900.0])
        self.assertEqual(report["latency"]["p50_ms"], 570)
        self.assertEqual(report["calibration"]["acoustic_gap_ms"], 900)
        self.assertNotIn(("GET", "http://r:8791/conversations/old-1", None), http.calls)

    def test_a_bad_id_from_the_relay_is_not_used_as_a_path(self):
        listing = {"conversations": [{"id": "../../etc/passwd"}]}
        http = FakeHttp([("GET", "/conversations", response(200, json.dumps(listing).encode()))])
        with tempfile.TemporaryDirectory() as d:
            self.assertEqual(qa.Relay("r", 8791, http=http).fetch_new(set(), Path(d)), [])


if __name__ == "__main__":
    unittest.main()
