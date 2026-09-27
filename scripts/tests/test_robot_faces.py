#!/usr/bin/env python3
"""Tests for scripts/robot-faces.py (face plan U8, R15).

The robot is faked at the same two seams as test_robot_settings.py:
subprocess.run (adb) and http_request (the launcher behind the adb forward).
robot-faces.py borrows those helpers from robot-settings.py, so the patches
go on that module. The fake launcher keeps the face routes' contract: the
token-protected JSON state, the loopback-only thresholds save that redirects
with a fixed status, and the check crops by handle. Every test checks that
the page token never reaches an adb argv, stdout or stderr.
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
from urllib.parse import parse_qs, quote, urlsplit

REPO = Path(__file__).resolve().parents[2]
SCRIPT = REPO / "scripts" / "robot-faces.py"


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load {path}")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


rf = load("robot_faces", SCRIPT)
rs = rf.rs  # the robot-settings.py module robot-faces.py uses for adb and HTTP

SERIAL = "192.168.19.74:5555"
PORT = "53117"
TOKEN = "tokSECRET987654"

SAVED = "Face thresholds saved; they apply from the next meeting."
REFUSE_BANDS = "Not saved: The bands need 0 <= close <= confident <= 1."
REFUSE_WIDTH = "Not saved: The minimum face width must be between 16 and 640 pixels."
EXPIRED = "Nothing changed: this page had expired. Try again."

T0 = 1790000000000  # ms


def check(handle, at_ms, decision, reason="", has_crop=True, best_id="", best_name="", score=0.0,
          runner_up_id="", runner_up_name="", runner_up_score=None, near_tie=False, outcome="pending",
          joined_id="", joined_name=""):
    return {"handle": handle, "at_ms": at_ms, "decision": decision, "reason": reason, "has_crop": has_crop,
            "best_id": best_id, "best_name": best_name, "best_slot": 0 if best_id else -1,
            "best_photo": "current" if best_id else "none", "score": score,
            "runner_up_id": runner_up_id, "runner_up_name": runner_up_name, "runner_up_score": runner_up_score,
            "near_tie": near_tie, "outcome": outcome, "joined_id": joined_id, "joined_name": joined_name}


def sample_state():
    # Deliberately not in newest-first order: the CLI sorts.
    return {
        "thresholds": {"confident": 0.5, "close": 0.363, "margin": 0.05, "min_width": 48,
                       "dark_floor": 40.0, "dim_level": 90.0, "blur_floor": 30.0},
        "checks": [
            check(1, T0, "confident", best_id="p1", best_name="Ada", score=0.7123, outcome="yes"),
            check(3, T0 + 120000, "rejected", reason="too dark", outcome="pending"),
            check(2, T0 + 60000, "close", best_id="p2", best_name="Grace", score=0.4051,
                  runner_up_id="p1", runner_up_name="Ada", runner_up_score=0.3822, near_tie=True,
                  outcome="name given"),
            check(4, T0 + 180000, "no face", has_crop=False, outcome="ended without an answer"),
        ],
        "people": [
            {"id": "p1", "name": "Ada", "last_seen_ms": T0, "photos": 3, "with_embedding": 2, "unusable": 1},
            {"id": "p2", "name": "", "last_seen_ms": 0, "photos": 1, "with_embedding": 1, "unusable": 0},
        ],
    }


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
    """GET /settings (token), POST /settings/face/state (JSON), POST
    /settings/face/thresholds (302 ?status=), GET /settings/face/check-crop."""

    def __init__(self, state=None, fail_on=None, status_override=None):
        self.state = state or sample_state()
        self.fail_on = fail_on
        self.status_override = status_override
        self.requests = []

    def __call__(self, method, url, body=None, headers=None, timeout=None):
        parts = urlsplit(url)
        assert parts.hostname == "127.0.0.1" and str(parts.port) == PORT, url
        self.requests.append((method, parts.path, parts.query, body))
        if self.fail_on == parts.path:
            raise OSError("connection reset by peer")
        if self.fail_on == "interrupt:" + parts.path:
            raise KeyboardInterrupt()
        if method == "GET" and parts.path == "/settings":
            html = ('<html><body><form method="post" action="/settings/claude">'
                    f'<input type="hidden" name="t" value="{TOKEN}"></form></body></html>')
            return rs.Response(200, {}, html.encode())
        if method == "GET" and parts.path == "/settings/face/check-crop":
            h = int(parse_qs(parts.query)["id"][0])
            c = [c for c in self.state["checks"] if c["handle"] == h]
            if not c or not c[0]["has_crop"]:
                return rs.Response(404, {}, b"No such face check.")
            return rs.Response(200, {"content-type": "image/jpeg"}, b"\xff\xd8JPEG%d\xff\xd9" % h)
        assert method == "POST", (method, parts.path)
        form = {k: v[0] for k, v in parse_qs(body or "", keep_blank_values=True).items()}
        if parts.path == "/settings/face/state":
            if form.get("t") != TOKEN:
                return rs.Response(403, {}, b"page token missing or expired\n")
            return rs.Response(200, {}, json.dumps(self.state).encode())
        if parts.path == "/settings/face/thresholds":
            if form.get("t") != TOKEN:
                status = EXPIRED
            else:
                status = SAVED
                for k, v in form.items():
                    if k != "t":
                        self.state["thresholds"][k] = int(v) if k == "min_width" else float(v)
            if self.status_override:
                status = self.status_override
            return rs.Response(302, {"location": "/settings?status=" + quote(status)}, b"")
        raise AssertionError(parts.path)

    def posts(self, path):
        return [b for m, p, q, b in self.requests if m == "POST" and p == path]


class Run:
    def __init__(self, argv, launcher=None, adb=None):
        self.adb = adb or FakeAdb()
        self.launcher = launcher or FakeLauncher()
        self.out = io.StringIO()
        self.err = io.StringIO()
        with mock.patch.object(rs.subprocess, "run", self.adb), \
                mock.patch.object(rs, "http_request", self.launcher), \
                mock.patch("sys.stdout", self.out), mock.patch("sys.stderr", self.err):
            try:
                self.code = rf.main(argv)
            except SystemExit as e:
                self.code = e.code

    @property
    def stdout(self):
        return self.out.getvalue()

    @property
    def stderr(self):
        return self.err.getvalue()


class Base(unittest.TestCase):
    def assertTokenNeverPrinted(self, run):
        for argv in run.adb.calls:
            self.assertNotIn(TOKEN, " ".join(argv))
        self.assertNotIn(TOKEN, run.stdout)
        self.assertNotIn(TOKEN, run.stderr)

    def assertForwardClosed(self, run):
        self.assertEqual(run.adb.forwards_removed(),
                         [["adb", "-s", SERIAL, "forward", "--remove", "tcp:" + PORT]])


class ChecksTest(Base):
    def rows(self, run):
        return [l for l in run.stdout.splitlines() if l.startswith("|")][2:]  # after header + rule

    def test_table_is_newest_first_with_the_rejected_reason(self):
        run = Run(["checks"])
        self.assertEqual(run.code, 0, run.stderr)
        rows = self.rows(run)
        self.assertEqual(len(rows), 4, run.stdout)
        self.assertIn("no face", rows[0])
        self.assertIn("ended without an answer", rows[0])
        self.assertIn("rejected (too dark)", rows[1])
        self.assertIn("close", rows[2])
        self.assertIn("Grace", rows[2])
        self.assertIn("0.41", rows[2])
        self.assertIn("near tie: Ada 0.38", rows[2])
        self.assertIn("name given", rows[2])
        self.assertIn("confident", rows[3])
        self.assertIn("Ada", rows[3])
        self.assertIn("0.71", rows[3])
        self.assertIn("yes", rows[3])
        self.assertIn(rf.format_time(T0 + 180000), rows[0])
        self.assertTokenNeverPrinted(run)
        self.assertForwardClosed(run)

    def test_state_is_posted_with_the_page_token(self):
        run = Run(["checks"])
        body = parse_qs(run.launcher.posts("/settings/face/state")[0])
        self.assertEqual(body["t"], [TOKEN])

    def test_render_checks_is_pure_and_fixed_format(self):
        text = rf.render_checks(sample_state()["checks"])
        self.assertIn("rejected (too dark)", text)
        self.assertIn("joined", rf.render_checks([check(9, T0, "weak", best_id="p1", best_name="Ada", score=0.2,
                                                       outcome="joined", joined_id="p1", joined_name="Ada")]))

    def test_no_checks_says_so(self):
        state = sample_state()
        state["checks"] = []
        run = Run(["checks"], launcher=FakeLauncher(state=state))
        self.assertEqual(run.code, 0, run.stderr)
        self.assertIn("No face checks", run.stdout)

    def test_save_writes_one_jpeg_per_check_with_a_crop(self):
        with tempfile.TemporaryDirectory() as d:
            run = Run(["checks", "--save", d])
            self.assertEqual(run.code, 0, run.stderr)
            names = sorted(p.name for p in Path(d).iterdir())
            expect = sorted(rf.crop_filename(c) for c in sample_state()["checks"] if c["has_crop"])
            self.assertEqual(names, expect)
            self.assertEqual(len(names), 3)
            for c in sample_state()["checks"]:
                if c["has_crop"]:
                    data = (Path(d) / rf.crop_filename(c)).read_bytes()
                    self.assertEqual(data, b"\xff\xd8JPEG%d\xff\xd9" % c["handle"])
            self.assertTokenNeverPrinted(run)
            self.assertForwardClosed(run)

    def test_crop_filename_is_time_and_outcome(self):
        c = check(2, T0 + 60000, "close", outcome="name given")
        stamp = datetime.fromtimestamp((T0 + 60000) / 1000).strftime("%Y%m%d-%H%M%S")
        self.assertEqual(rf.crop_filename(c), f"{stamp}-name-given-2.jpg")

    def test_crop_rolled_off_is_skipped_not_fatal(self):
        state = sample_state()
        launcher = FakeLauncher(state=state)
        orig = launcher.__call__

        def gone(method, url, **kw):
            if "check-crop" in url and url.endswith("id=1"):
                return rs.Response(404, {}, b"No such face check.")
            return orig(method, url, **kw)

        with tempfile.TemporaryDirectory() as d:
            run = Run(["checks", "--save", d], launcher=gone, adb=FakeAdb())
            self.assertEqual(run.code, 0, run.stderr)
            self.assertEqual(len(list(Path(d).iterdir())), 2)
            self.assertIn("rolled off", run.stderr)

    def test_default_save_dir_is_under_the_gitignored_bench(self):
        self.assertEqual(rf.DEFAULT_SAVE_DIR, REPO / "tools" / "face-bench" / "captures" / "_checks")


class PeopleTest(Base):
    def test_people_table(self):
        run = Run(["people"])
        self.assertEqual(run.code, 0, run.stderr)
        rows = [l for l in run.stdout.splitlines() if l.startswith("|")][2:]
        self.assertEqual(len(rows), 2)
        cells = [c.strip() for c in rows[0].strip("|").split("|")]
        self.assertEqual(cells[:4], ["Ada", "3", "2", "1"])
        self.assertEqual(cells[4], rf.format_time(T0))
        cells = [c.strip() for c in rows[1].strip("|").split("|")]
        self.assertEqual(cells, ["(unnamed)", "1", "1", "0", "never"])
        self.assertTokenNeverPrinted(run)
        self.assertForwardClosed(run)


class ThresholdsTest(Base):
    def test_read_prints_current_values(self):
        run = Run(["thresholds"])
        self.assertEqual(run.code, 0, run.stderr)
        self.assertIn("confident", run.stdout)
        self.assertIn("0.363", run.stdout)
        self.assertIn("48", run.stdout)
        self.assertEqual(run.launcher.posts("/settings/face/thresholds"), [])
        self.assertForwardClosed(run)

    def test_close_above_confident_is_refused_before_any_post(self):
        run = Run(["thresholds", "--close", "0.6", "--confident", "0.5"])
        self.assertEqual(run.code, 1)
        self.assertIn("The bands need 0 <= close <= confident <= 1.", run.stderr)
        self.assertEqual(run.launcher.requests, [])
        self.assertEqual(run.adb.calls, [])

    def test_partial_set_is_checked_against_current_values_before_the_save(self):
        # confident is 0.5 on the robot; close 0.6 alone breaks the bands.
        run = Run(["thresholds", "--close", "0.6"])
        self.assertEqual(run.code, 1)
        self.assertIn("The bands need", run.stderr)
        self.assertEqual(run.launcher.posts("/settings/face/thresholds"), [])
        self.assertForwardClosed(run)

    def test_each_rule_refuses_locally(self):
        for argv, text in [(["--margin", "0.31"], "near-tie margin"),
                           (["--dark-floor", "90", "--dim-level", "90"], "dark floor"),
                           (["--dim-level", "256"], "dark floor"),
                           (["--blur-floor", "-1"], "blur floor"),
                           (["--min-width", "15"], "minimum face width"),
                           (["--min-width", "641"], "minimum face width"),
                           (["--confident", "1.01"], "The bands"),
                           (["--confident", "nan"], "not a plain decimal"),
                           (["--confident", "nan", "--close", "0.3"], "The bands")]:
            with self.subTest(argv=argv):
                run = Run(["thresholds"] + argv)
                self.assertEqual(run.code, 1, run.stdout)
                self.assertIn(text, run.stderr)
                self.assertEqual(run.launcher.posts("/settings/face/thresholds"), [])

    def test_face_bench_command_is_accepted_and_saved(self):
        argv = ("thresholds --confident 0.47 --close 0.34 --margin 0.04 --min-width 40 "
                "--dark-floor 35 --dim-level 80 --blur-floor 25").split()
        run = Run(argv)
        self.assertEqual(run.code, 0, run.stderr)
        body = parse_qs(run.launcher.posts("/settings/face/thresholds")[0])
        self.assertEqual(body["t"], [TOKEN])
        self.assertEqual({k: v[0] for k, v in body.items() if k != "t"},
                         {"confident": "0.47", "close": "0.34", "margin": "0.04", "min_width": "40",
                          "dark_floor": "35", "dim_level": "80", "blur_floor": "25"})
        self.assertIn(SAVED, run.stdout)
        self.assertIn("0.47", run.stdout)  # read back after the save
        self.assertTokenNeverPrinted(run)
        self.assertForwardClosed(run)

    def test_only_given_fields_are_sent(self):
        run = Run(["thresholds", "--margin", "0.1"])
        self.assertEqual(run.code, 0, run.stderr)
        body = parse_qs(run.launcher.posts("/settings/face/thresholds")[0])
        self.assertEqual(sorted(body), ["margin", "t"])

    def test_values_are_sent_as_plain_decimals(self):
        self.assertEqual(rf.plain_decimal(0.1 + 0.2), "0.3")
        self.assertEqual(rf.plain_decimal(1e-7), "0")
        self.assertEqual(rf.plain_decimal(35.0), "35")
        self.assertEqual(rf.plain_decimal(0.363), "0.363")

    def test_too_large_to_send_is_refused_locally(self):
        run = Run(["thresholds", "--blur-floor", "12345678"])
        self.assertEqual(run.code, 1)
        self.assertIn("plain decimal", run.stderr)
        self.assertEqual(run.launcher.posts("/settings/face/thresholds"), [])

    def test_refused_save_on_the_robot_is_reported_with_the_page_status(self):
        run = Run(["thresholds", "--min-width", "40"], launcher=FakeLauncher(status_override=REFUSE_WIDTH))
        self.assertEqual(run.code, 1)
        self.assertIn(REFUSE_WIDTH, run.stderr)
        self.assertNotIn(SAVED, run.stdout)
        self.assertTokenNeverPrinted(run)
        self.assertForwardClosed(run)

    def test_expired_token_is_reported_as_failed(self):
        run = Run(["thresholds", "--margin", "0.1"], launcher=FakeLauncher(status_override=EXPIRED))
        self.assertEqual(run.code, 1)
        self.assertIn(EXPIRED, run.stderr)

    def test_non_loopback_403_is_readable(self):
        launcher = FakeLauncher()
        orig = launcher.__call__

        def forbid(method, url, **kw):
            if url.endswith("/settings/face/thresholds"):
                launcher.requests.append((method, "/settings/face/thresholds", "", kw.get("body")))
                return rs.Response(403, {}, b"Face thresholds can only be set from the robot itself\n")
            return orig(method, url, **kw)

        run = Run(["thresholds", "--margin", "0.1"], launcher=forbid)
        self.assertEqual(run.code, 1)
        self.assertIn("403", run.stderr)
        self.assertForwardClosed(run)


class ForwardTest(Base):
    def test_forward_removed_after_failure(self):
        run = Run(["checks"], launcher=FakeLauncher(fail_on="/settings/face/state"))
        self.assertEqual(run.code, 1)
        self.assertIn("connection reset", run.stderr)
        self.assertForwardClosed(run)
        self.assertTokenNeverPrinted(run)

    def test_forward_removed_after_interrupt(self):
        run = Run(["thresholds", "--margin", "0.1"],
                  launcher=FakeLauncher(fail_on="interrupt:/settings/face/thresholds"))
        self.assertEqual(run.code, 130)
        self.assertForwardClosed(run)
        self.assertTokenNeverPrinted(run)

    def test_interrupt_during_save_of_crops_closes_the_forward(self):
        with tempfile.TemporaryDirectory() as d:
            run = Run(["checks", "--save", d], launcher=FakeLauncher(fail_on="interrupt:/settings/face/check-crop"))
        self.assertEqual(run.code, 130)
        self.assertForwardClosed(run)

    def test_state_403_is_readable(self):
        launcher = FakeLauncher()
        orig = launcher.__call__

        def forbid(method, url, **kw):
            if url.endswith("/settings/face/state"):
                return rs.Response(403, {}, b"page token missing or expired\n")
            return orig(method, url, **kw)

        run = Run(["people"], launcher=forbid)
        self.assertEqual(run.code, 1)
        self.assertIn("403", run.stderr)
        self.assertForwardClosed(run)

    def test_serial_flag_is_used(self):
        run = Run(["people", "--serial", "10.0.0.5:5555"])
        self.assertEqual(run.code, 0, run.stderr)
        self.assertTrue(all("10.0.0.5:5555" in c for c in run.adb.calls))
        self.assertEqual(rf.DEFAULT_SERIAL, SERIAL)


if __name__ == "__main__":
    unittest.main()
