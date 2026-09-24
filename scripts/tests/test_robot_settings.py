#!/usr/bin/env python3
"""Tests for scripts/robot-settings.py (U6).

The robot is faked at two seams: subprocess.run (adb) and the script's
http_request (the launcher's Settings page behind the adb forward). The fake
launcher keeps its own state, so a test can check what was stored as well as
what was sent. Every test that handles a key also checks that the key never
reaches an adb argv, stdout, or stderr (R14). The real run on the robot is U7.
"""
import importlib.util
import io
import types
import unittest
from pathlib import Path
from unittest import mock
from urllib.parse import parse_qs, quote, urlsplit

REPO = Path(__file__).resolve().parents[2]
SCRIPT = REPO / "scripts" / "robot-settings.py"


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load {path}")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


rs = load("robot_settings", SCRIPT)

SERIAL = "192.168.19.74:5555"
KEY = "sk-ant-api03-SECRETSECRETSECRET-wxyz"
URL = "https://teamclaude.example.com"
MODEL = "claude-opus-5-5"
PORT = "53117"


def done(stdout="", returncode=0, stderr=""):
    return types.SimpleNamespace(stdout=stdout, stderr=stderr, returncode=returncode)


class FakeAdb:
    """Records every adb argv. get-state says "device"; forward tcp:0 allocates PORT."""

    def __init__(self, missing=False, state="device"):
        self.calls = []
        self.missing = missing
        self.state = state

    def __call__(self, cmd, **kw):
        self.calls.append(list(cmd))
        if self.missing:
            raise FileNotFoundError("adb")
        joined = " ".join(cmd)
        if "get-state" in joined:
            if self.state != "device":
                return done(stderr="error: device '%s' not found" % SERIAL, returncode=1)
            return done("device\n")
        if "forward tcp:0" in joined:
            return done(PORT + "\n")
        return done()

    def forwards_opened(self):
        return [c for c in self.calls if "forward" in c and "tcp:0" in c]

    def forwards_removed(self):
        return [c for c in self.calls if "forward" in c and "--remove" in c]


class FakeLauncher:
    """The Settings page's contract: GET /settings renders, each POST 302s to ?status=."""

    def __init__(self, model="", key_set=False, models=("claude-opus-5-5", "claude-haiku-5"),
                 fail_on=None, status_override=None):
        self.base_url = URL if key_set else ""
        self.model = model
        self.last_four = "abcd" if key_set else ""
        self.models = []
        self.available = list(models)
        self.requests = []
        self.fail_on = fail_on
        self.status_override = status_override

    def page(self):
        opts = "".join('<option value="%s">' % m for m in self.models)
        key_status = "Key set, ends in …" + self.last_four if self.last_four else "No key set"
        return ("<html><body><main><section id=\"claude\">"
                '<form method="post" action="/settings/claude">'
                '<input type="hidden" name="t" value="tok123">'
                '<input type="url" id="base_url" name="base_url" value="%s">'
                '<input type="password" id="key" name="key" autocomplete="off">'
                '<input type="text" id="model" name="model" list="claude-models" value="%s">'
                '<datalist id="claude-models">%s</datalist></form>'
                '<p id="claude-key-status">%s</p>'
                "</section></main></body></html>") % (self.base_url, self.model, opts, key_status)

    def __call__(self, method, url, body=None, headers=None, timeout=None):
        parts = urlsplit(url)
        assert parts.hostname == "127.0.0.1" and str(parts.port) == PORT, url
        self.requests.append((method, parts.path, body))
        if self.fail_on == parts.path:
            raise OSError("connection reset by peer")
        if self.fail_on == "interrupt:" + parts.path:
            raise KeyboardInterrupt()
        if method == "GET":
            return rs.Response(200, {}, self.page().encode("utf-8"))
        form = {k: v[0] for k, v in parse_qs(body or "", keep_blank_values=True).items()}
        assert form.get("t") == "tok123", form
        if parts.path == "/settings/claude":
            if form.get("key"):
                self.last_four = form["key"][-4:] if len(form["key"]) >= 12 else ""
            self.base_url = form.get("base_url", "")
            self.model = form.get("model", "")
            status = "Saved." if self.model else "Saved. Press Refresh models, pick a model, and save again."
        elif parts.path == "/settings/claude/models":
            self.models = list(self.available)
            status = "Found %d models." % len(self.models)
        elif parts.path == "/settings/claude/test":
            status = "Connection works: %s answered." % self.model
        else:
            status = "Nothing changed: unknown action."
        if self.status_override:
            status = self.status_override
        return rs.Response(302, {"location": "/settings?status=" + quote(status)}, b"")

    def posts(self, path=None):
        return [(p, b) for m, p, b in self.requests if m == "POST" and (path is None or p == path)]

    def post_form(self, path):
        bodies = [b for p, b in self.posts(path)]
        return {k: v[0] for k, v in parse_qs(bodies[-1], keep_blank_values=True).items()}


class Run:
    """Runs main() with the fakes; captures stdout, stderr, the exit code, and getpass."""

    def __init__(self, argv, env, launcher=None, adb=None, prompt=KEY):
        self.adb = adb or FakeAdb()
        self.launcher = launcher or FakeLauncher()
        self.out = io.StringIO()
        self.err = io.StringIO()
        self.getpass = mock.Mock(return_value=prompt)
        with mock.patch.object(rs.subprocess, "run", self.adb), \
                mock.patch.object(rs, "http_request", self.launcher), \
                mock.patch.object(rs.getpass, "getpass", self.getpass), \
                mock.patch.dict(rs.os.environ, env, clear=True), \
                mock.patch("sys.stdout", self.out), mock.patch("sys.stderr", self.err):
            try:
                self.code = rs.main(argv)
            except SystemExit as e:
                self.code = e.code

    @property
    def stdout(self):
        return self.out.getvalue()

    @property
    def stderr(self):
        return self.err.getvalue()


ALL_ENV = {"ANTHROPIC_API_KEY": KEY, "ANTHROPIC_BASE_URL": URL, "ANTHROPIC_MODEL": MODEL}


class SecrecyMixin:
    def assertKeyNeverLeaked(self, run, key=KEY):
        for argv in run.adb.calls:
            self.assertNotIn(key, " ".join(argv))
        self.assertNotIn(key, run.stdout)
        self.assertNotIn(key, run.stderr)


class PushTest(SecrecyMixin, unittest.TestCase):
    def test_ae4_push_with_all_three_env_vars_stores_them_and_tests(self):
        run = Run([], ALL_ENV)
        self.assertEqual(run.code, 0, run.stderr)
        form = run.launcher.post_form("/settings/claude")
        self.assertEqual(form["base_url"], URL)
        self.assertEqual(form["key"], KEY)
        self.assertEqual(form["model"], MODEL)
        paths = [p for p, _ in run.launcher.posts()]
        self.assertEqual(paths, ["/settings/claude", "/settings/claude/test"])
        self.assertIn("Connection works: %s answered." % MODEL, run.stdout)
        self.assertIn("…" + KEY[-4:], run.stdout)
        self.assertKeyNeverLeaked(run)

    def test_push_is_the_default_command(self):
        self.assertEqual(rs.parse_args([]).command, "push")
        self.assertEqual(rs.parse_args(["push"]).command, "push")

    def test_key_never_travels_in_a_url(self):
        run = Run([], ALL_ENV)
        # The fake records only the path; the query string would be dropped, so check
        # the real contract instead: the key is in a POST body and nowhere else.
        for method, path, body in run.launcher.requests:
            self.assertNotIn(KEY, path)
            if method == "GET":
                self.assertIsNone(body)

    def test_no_api_key_flag_exists(self):
        with mock.patch("sys.stderr", io.StringIO()):
            with self.assertRaises(SystemExit):
                rs.parse_args(["push", "--api-key", KEY])

    def test_prompts_through_getpass_when_env_has_no_key(self):
        env = {"ANTHROPIC_BASE_URL": URL, "ANTHROPIC_MODEL": MODEL}
        run = Run([], env)
        self.assertEqual(run.code, 0, run.stderr)
        run.getpass.assert_called_once()
        self.assertEqual(run.launcher.post_form("/settings/claude")["key"], KEY)
        self.assertKeyNeverLeaked(run)

    def test_empty_prompted_key_stops_before_adb(self):
        env = {"ANTHROPIC_BASE_URL": URL}
        run = Run([], env, prompt="")
        self.assertNotEqual(run.code, 0)
        self.assertEqual(run.adb.calls, [])

    def test_env_key_is_used_without_prompting(self):
        run = Run([], ALL_ENV)
        run.getpass.assert_not_called()

    def test_http_base_url_fails_before_any_adb_command(self):
        env = dict(ALL_ENV, ANTHROPIC_BASE_URL="http://teamclaude.example.com")
        run = Run([], env)
        self.assertNotEqual(run.code, 0)
        self.assertEqual(run.adb.calls, [])
        self.assertIn("https://", run.stderr)
        self.assertKeyNeverLeaked(run)

    def test_http_base_url_flag_fails_before_any_adb_command(self):
        run = Run(["--base-url", "http://x.example.com"], ALL_ENV)
        self.assertNotEqual(run.code, 0)
        self.assertEqual(run.adb.calls, [])

    def test_userinfo_or_bad_port_base_url_fails_before_any_adb_command(self):
        for bad in ("https://h.test@evil.test", "https://user:pw@h.test", "https://h.test:abc",
                    "https://h.test:123456", "https://h.test:65536", "https://h.test:0"):
            with self.subTest(url=bad):
                run = Run(["--base-url", bad], ALL_ENV)
                self.assertNotEqual(run.code, 0)
                self.assertEqual(run.adb.calls, [])
                self.assertNotIn("pw", run.stderr)
                self.assertKeyNeverLeaked(run)

    def test_explicit_valid_port_is_accepted(self):
        rs.validate_base_url("https://h.test:8443")

    def test_key_without_base_url_exits_readably_before_any_adb_command(self):
        run = Run([], {"ANTHROPIC_API_KEY": KEY, "ANTHROPIC_MODEL": MODEL})
        self.assertNotEqual(run.code, 0)
        self.assertEqual(run.adb.calls, [])
        self.assertEqual(run.launcher.requests, [])
        self.assertIn("ANTHROPIC_BASE_URL", run.stderr)
        self.assertIn("--base-url", run.stderr)
        self.assertKeyNeverLeaked(run)

    def test_base_url_flag_wins_over_env(self):
        other = "https://other.example.com"
        run = Run(["--base-url", other], ALL_ENV)
        self.assertEqual(run.code, 0, run.stderr)
        self.assertEqual(run.launcher.post_form("/settings/claude")["base_url"], other)

    def test_model_flag_wins_over_env(self):
        run = Run(["--model", "claude-haiku-5"], ALL_ENV)
        self.assertEqual(run.launcher.post_form("/settings/claude")["model"], "claude-haiku-5")

    def test_stored_model_is_resent_and_unchanged(self):
        env = {"ANTHROPIC_API_KEY": KEY, "ANTHROPIC_BASE_URL": URL}
        launcher = FakeLauncher(model="claude-stored-1", key_set=True)
        run = Run([], env, launcher=launcher)
        self.assertEqual(run.code, 0, run.stderr)
        self.assertEqual(launcher.post_form("/settings/claude")["model"], "claude-stored-1")
        self.assertEqual(launcher.model, "claude-stored-1")
        self.assertIn("/settings/claude/test", [p for p, _ in launcher.posts()])
        self.assertKeyNeverLeaked(run)

    def test_no_model_anywhere_saves_then_lists_models(self):
        env = {"ANTHROPIC_API_KEY": KEY, "ANTHROPIC_BASE_URL": URL}
        run = Run([], env)
        self.assertEqual(run.code, 0, run.stderr)
        paths = [p for p, _ in run.launcher.posts()]
        self.assertEqual(paths, ["/settings/claude", "/settings/claude/models"])
        self.assertEqual(run.launcher.post_form("/settings/claude")["model"], "")
        self.assertIn("claude-opus-5-5", run.stdout)
        self.assertIn("claude-haiku-5", run.stdout)
        self.assertIn("--model", run.stdout)
        self.assertKeyNeverLeaked(run)

    def test_short_key_shows_no_last_four(self):
        short = "sk-short-99"
        env = dict(ALL_ENV, ANTHROPIC_API_KEY=short)
        run = Run([], env)
        self.assertEqual(run.code, 0, run.stderr)
        self.assertNotIn(short[-4:], run.stdout)
        self.assertKeyNeverLeaked(run, key=short)

    def test_key_echoed_by_the_robot_is_redacted(self):
        launcher = FakeLauncher(status_override="Not saved: bad key " + KEY)
        run = Run([], ALL_ENV, launcher=launcher)
        self.assertNotEqual(run.code, 0)
        self.assertIn("Not saved", run.stdout + run.stderr)
        self.assertKeyNeverLeaked(run)

    def test_key_in_an_http_error_is_redacted(self):
        launcher = FakeLauncher()

        def http(method, url, body=None, headers=None, timeout=None):
            if method == "POST":
                raise OSError("failed sending " + (body or ""))
            return launcher(method, url, body, headers, timeout)

        run = Run([], ALL_ENV, launcher=http)
        self.assertNotEqual(run.code, 0)
        self.assertIn("failed sending", run.stderr)
        self.assertKeyNeverLeaked(run)

    def test_every_adb_call_names_the_serial(self):
        run = Run(["--serial", "10.0.0.5:5555"], ALL_ENV)
        for argv in run.adb.calls:
            if argv[:2] == ["adb", "connect"]:
                continue
            self.assertEqual(argv[:3], ["adb", "-s", "10.0.0.5:5555"])


class ForwardTest(SecrecyMixin, unittest.TestCase):
    def assertForwardClosed(self, run):
        self.assertEqual(len(run.adb.forwards_opened()), 1)
        opened = run.adb.forwards_opened()[0]
        self.assertEqual(opened[-2:], ["tcp:0", "tcp:8443"])
        self.assertEqual(run.adb.forwards_removed(),
                         [["adb", "-s", SERIAL, "forward", "--remove", "tcp:" + PORT]])

    def test_forward_removed_after_success(self):
        run = Run([], ALL_ENV)
        self.assertEqual(run.code, 0, run.stderr)
        self.assertForwardClosed(run)

    def test_forward_removed_after_http_error(self):
        run = Run([], ALL_ENV, launcher=FakeLauncher(fail_on="/settings/claude/test"))
        self.assertNotEqual(run.code, 0)
        self.assertIn("connection reset", run.stderr)
        self.assertForwardClosed(run)
        self.assertKeyNeverLeaked(run)

    def test_forward_removed_after_ctrl_c(self):
        run = Run([], ALL_ENV, launcher=FakeLauncher(fail_on="interrupt:/settings/claude"))
        self.assertEqual(run.code, 130)
        self.assertForwardClosed(run)
        self.assertKeyNeverLeaked(run)

    def test_non_redirect_answer_is_an_error_and_closes_the_forward(self):
        launcher = FakeLauncher()
        inner = launcher.__call__

        def http(method, url, body=None, headers=None, timeout=None):
            if method == "POST":
                launcher.requests.append((method, urlsplit(url).path, body))
                return rs.Response(500, {}, b"oops")
            return inner(method, url, body, headers, timeout)

        run = Run([], ALL_ENV, launcher=http)
        run.launcher = launcher
        self.assertNotEqual(run.code, 0)
        self.assertIn("500", run.stderr)
        self.assertForwardClosed(run)

    def test_unparseable_forward_port_is_an_error(self):
        adb = FakeAdb()
        original = adb.__call__

        def patched(cmd, **kw):
            if "forward" in cmd and "tcp:0" in cmd:
                adb.calls.append(list(cmd))
                return done("")
            return original(cmd, **kw)

        run = Run([], ALL_ENV, adb=patched)
        self.assertNotEqual(run.code, 0)
        self.assertIn("forward", run.stderr)


class DeviceErrorTest(SecrecyMixin, unittest.TestCase):
    def test_adb_missing_is_readable_and_nonzero(self):
        run = Run([], ALL_ENV, adb=FakeAdb(missing=True))
        self.assertNotEqual(run.code, 0)
        self.assertIn("adb not found", run.stderr)
        self.assertEqual(run.launcher.requests, [])
        self.assertKeyNeverLeaked(run)

    def test_no_device_is_readable_and_nonzero(self):
        run = Run([], ALL_ENV, adb=FakeAdb(state="offline"))
        self.assertNotEqual(run.code, 0)
        self.assertIn("not reachable", run.stderr)
        self.assertIn("--serial", run.stderr)
        self.assertEqual(run.launcher.requests, [])
        self.assertEqual(run.adb.forwards_opened(), [])
        self.assertKeyNeverLeaked(run)

    def test_tcp_serial_is_adb_connected_first(self):
        run = Run([], ALL_ENV)
        self.assertEqual(run.adb.calls[0], ["adb", "connect", SERIAL])

    def test_usb_serial_is_not_connected(self):
        run = Run(["--serial", "ABC123"], ALL_ENV)
        self.assertNotIn("connect", run.adb.calls[0])


class TestAndModelsCommandTest(SecrecyMixin, unittest.TestCase):
    def test_test_command_needs_no_key_and_reports_status(self):
        launcher = FakeLauncher(model=MODEL, key_set=True)
        run = Run(["test"], {}, launcher=launcher)
        self.assertEqual(run.code, 0, run.stderr)
        run.getpass.assert_not_called()
        self.assertEqual([p for p, _ in launcher.posts()], ["/settings/claude/test"])
        self.assertIn("Connection works", run.stdout)
        self.assertEqual(len(run.adb.forwards_removed()), 1)

    def test_test_command_failure_is_nonzero(self):
        launcher = FakeLauncher(model=MODEL, key_set=True,
                                status_override="Test failed: the key was rejected.")
        run = Run(["test"], {}, launcher=launcher)
        self.assertNotEqual(run.code, 0)
        self.assertIn("Test failed: the key was rejected.", run.stdout + run.stderr)

    def test_models_command_prints_the_list(self):
        launcher = FakeLauncher(key_set=True)
        run = Run(["models"], {}, launcher=launcher)
        self.assertEqual(run.code, 0, run.stderr)
        run.getpass.assert_not_called()
        self.assertEqual([p for p, _ in launcher.posts()], ["/settings/claude/models"])
        self.assertIn("claude-opus-5-5", run.stdout)
        self.assertIn("claude-haiku-5", run.stdout)

    def test_models_command_failure_is_nonzero(self):
        launcher = FakeLauncher(key_set=True,
                                status_override="Models not refreshed: the key was rejected.")
        run = Run(["models"], {}, launcher=launcher)
        self.assertNotEqual(run.code, 0)
        self.assertIn("Models not refreshed", run.stdout + run.stderr)


class HelperTest(unittest.TestCase):
    def test_mask_key_shows_last_four_only_for_long_keys(self):
        self.assertEqual(rs.mask_key(KEY), "…" + KEY[-4:])
        self.assertNotIn("99", rs.mask_key("sk-short-99"))

    def test_redact_replaces_the_key(self):
        self.assertNotIn(KEY, rs.redact("x " + KEY + " y", KEY))
        self.assertEqual(rs.redact("plain", ""), "plain")

    def test_status_from_location(self):
        self.assertEqual(rs.status_from_location("/settings?status=Saved.%20OK"), "Saved. OK")
        self.assertEqual(rs.status_from_location(None), "")

    def test_parse_forward_port(self):
        self.assertEqual(rs.parse_forward_port("53117\n"), 53117)
        self.assertIsNone(rs.parse_forward_port("error: something"))


if __name__ == "__main__":
    unittest.main()
