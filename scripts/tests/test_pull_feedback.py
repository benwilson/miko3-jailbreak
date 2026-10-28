#!/usr/bin/env python3
"""Tests for scripts/pull-feedback.py (owner 2026-10-02: the feedback log, read
over adb into a markdown file to act on).

The robot is faked at the same two seams as test_robot_faces.py: subprocess.run
(adb) and http_request (the launcher behind the adb forward), both on the
robot-settings.py module the script borrows. Every test checks that the page
token never reaches an adb argv, stdout, stderr or the markdown file.
"""
import importlib.util
import io
import json
import tempfile
import types
import unittest
from datetime import datetime
from pathlib import Path
from unittest import mock
from urllib.parse import parse_qs, urlsplit

REPO = Path(__file__).resolve().parents[2]
SCRIPT = REPO / "scripts" / "pull-feedback.py"


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load {path}")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


pf = load("pull_feedback", SCRIPT)
rs = pf.rs

SERIAL = "192.168.19.74:5555"
PORT = "53118"
TOKEN = "tokSECRET123456"
T0 = 1790000000000


def entry(at, kind, summary, quote="", who="someone", context="in a conversation"):
    return {"at": at, "kind": kind, "summary": summary, "quote": quote, "who": who, "context": context}


def sample():
    return {"max": 200, "entries": [
        entry(T0 + 120000, "complaint", "He keeps bumping into chairs.", "you keep bumping into my chair", "Sarah",
              "in a call's conversation"),
        entry(T0 + 60000, "suggestion", "He could learn to dance.", "you should learn to dance"),
        entry(T0, "praise", "Likes his voice.", "", "Tom", "while docked"),
    ]}


def done(stdout="", returncode=0, stderr=""):
    return types.SimpleNamespace(stdout=stdout, stderr=stderr, returncode=returncode)


class FakeAdb:
    def __init__(self):
        self.calls = []

    def __call__(self, cmd, **kw):
        self.calls.append(list(cmd))
        joined = " ".join(cmd)
        if "get-state" in joined:
            return done("device\n")
        if "forward tcp:0" in joined:
            return done(PORT + "\n")
        return done()

    def forwards_removed(self):
        return [c for c in self.calls if "forward" in c and "--remove" in c]


class FakeLauncher:
    def __init__(self, state=None, missing=False):
        self.state = sample() if state is None else state
        self.missing = missing
        self.requests = []

    def __call__(self, method, url, body=None, headers=None, timeout=None):
        parts = urlsplit(url)
        assert parts.hostname == "127.0.0.1" and str(parts.port) == PORT, url
        self.requests.append((method, parts.path, body))
        if method == "GET" and parts.path == "/settings":
            html = ('<html><body><form method="post" action="/settings/claude">'
                    f'<input type="hidden" name="t" value="{TOKEN}"></form></body></html>')
            return rs.Response(200, {}, html.encode())
        assert method == "POST" and parts.path == "/settings/feedback/state", (method, parts.path)
        if self.missing:
            return rs.Response(404, {}, b"Not Found")
        form = {k: v[0] for k, v in parse_qs(body or "").items()}
        if form.get("t") != TOKEN:
            return rs.Response(403, {}, b"page token missing or expired\n")
        return rs.Response(200, {}, json.dumps(self.state).encode())


class Run:
    def __init__(self, argv, launcher=None):
        self.adb = FakeAdb()
        self.launcher = launcher or FakeLauncher()
        self.out = io.StringIO()
        self.err = io.StringIO()
        with mock.patch.object(rs.subprocess, "run", self.adb), \
                mock.patch.object(rs, "http_request", self.launcher), \
                mock.patch("sys.stdout", self.out), mock.patch("sys.stderr", self.err):
            try:
                self.code = pf.main(argv)
            except SystemExit as e:
                self.code = e.code


class PullFeedbackTest(unittest.TestCase):
    def setUp(self):
        self._td = tempfile.TemporaryDirectory()
        self.path = Path(self._td.name) / "feedback.md"

    def tearDown(self):
        self._td.cleanup()

    def run_ok(self, launcher=None):
        run = Run(["--serial", SERIAL, "--out", str(self.path)], launcher)
        self.assertEqual(run.code, 0, run.err.getvalue())
        self.assertEqual(run.adb.forwards_removed(), [["adb", "-s", SERIAL, "forward", "--remove", "tcp:" + PORT]])
        for argv in run.adb.calls:
            self.assertNotIn(TOKEN, " ".join(argv))
        self.assertNotIn(TOKEN, run.out.getvalue() + run.err.getvalue())
        return run

    def test_writes_markdown_newest_first_with_every_field(self):
        run = self.run_ok()
        md = self.path.read_text()
        self.assertNotIn(TOKEN, md)
        self.assertTrue(md.startswith("# Feedback from conversations"), md[:80])
        first = md.index("He keeps bumping into chairs.")
        second = md.index("He could learn to dance.")
        third = md.index("Likes his voice.")
        self.assertLess(first, second)
        self.assertLess(second, third)
        self.assertIn("> you keep bumping into my chair", md)
        self.assertIn("Sarah", md)
        self.assertIn("in a call's conversation", md)
        stamp = datetime.fromtimestamp((T0 + 120000) / 1000).strftime("%Y-%m-%d %H:%M")
        self.assertIn(stamp, md)
        self.assertIn("- [ ] **complaint**", md)
        self.assertIn("1 complaint, 1 suggestion, 1 praise, 0 bug", md)
        self.assertIn("3 entries", run.out.getvalue())
        self.assertIn(str(self.path), run.out.getvalue())

    def test_an_entry_without_a_quote_has_no_quote_line(self):
        self.run_ok()
        md = self.path.read_text()
        praise = md[md.index("Likes his voice."):]
        self.assertNotIn(">", praise.split("\n- [ ]")[0])

    def test_entries_out_of_order_are_sorted_newest_first(self):
        state = sample()
        state["entries"].reverse()
        self.run_ok(FakeLauncher(state))
        md = self.path.read_text()
        self.assertLess(md.index("He keeps bumping"), md.index("Likes his voice."))

    def test_an_empty_log_says_so(self):
        run = self.run_ok(FakeLauncher({"max": 200, "entries": []}))
        self.assertIn("No feedback yet.", self.path.read_text())
        self.assertIn("0 entries", run.out.getvalue())

    def test_a_launcher_without_the_log_is_a_clear_error_and_no_file(self):
        run = Run(["--serial", SERIAL, "--out", str(self.path)], FakeLauncher(missing=True))
        self.assertEqual(run.code, 1)
        self.assertIn("install the current launcher", run.err.getvalue())
        self.assertFalse(self.path.exists())
        self.assertEqual(len(run.adb.forwards_removed()), 1)

    def test_the_default_output_is_gitignored(self):
        self.assertEqual(pf.DEFAULT_OUT.relative_to(REPO).parts[0], "out")
        self.assertIn("out/", (REPO / ".gitignore").read_text().splitlines())


if __name__ == "__main__":
    unittest.main()
