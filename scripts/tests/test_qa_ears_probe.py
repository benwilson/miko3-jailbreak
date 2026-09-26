"""Tests for scripts/qa-ears-probe.py's host-side logic (meeting plan U1, step 4):
the per-run nonce it puts in the robot's debug property, the POST it makes to
the launcher's probe route, the rows it parses out of the answer, the CSV it
writes, the drive commands it resends through the remote-control endpoint, and
the cleanup that clears the property on every exit, including an interrupt. The
adb and HTTP calls themselves are device-only and faked here."""
import contextlib
import importlib.util
import io
import json
import re
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCRIPT = HERE.parent / "qa-ears-probe.py"


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load {path}")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


qa = load("qa_ears_probe", SCRIPT)

SAMPLE_DUMP = json.dumps({
    "backend": "conexant",
    "seconds": 3,
    "rows": [
        {"second": 1, "angle": 12.5, "rms": 812, "decode_ms": 240, "decode_max_ms": 31, "chunks": 13,
         "words": 0, "matched": False},
        {"second": 2, "angle": None, "rms": 4210, "decode_ms": 610, "decode_max_ms": 95, "chunks": 12,
         "words": 2, "matched": False},
        {"second": 3, "angle": -40.0, "rms": 3990, "decode_ms": 580, "decode_max_ms": 70, "chunks": 13,
         "words": 4, "matched": True},
    ],
})

PAGE_HTML = ('<html><body><form method="post" action="/settings/claude">'
             '<input type="hidden" name="t" value="feedfacefeedfacefeedfacefeedface"></form></body></html>')


class FakeRobot(qa.Robot):
    """Records adb calls instead of running them; forwards answer a fixed base."""

    def __init__(self):
        super().__init__("fake:5555")
        self.calls = []

    def adb(self, *args, check=True):
        self.calls.append(args)
        return ""

    @contextlib.contextmanager
    def forward(self, port):
        self.calls.append(("forward", port))
        yield f"{'https' if port == qa.LAUNCHER_HTTPS_PORT else 'http'}://127.0.0.1:{10000 + port}"
        self.calls.append(("forward-removed", port))

    def property_writes(self):
        return [c[3] for c in self.calls if c[:3] == ("shell", "setprop", qa.PROPERTY)]


class FakeHttp:
    """Answers the settings page on GET and the given probe answer on POST."""

    def __init__(self, post_status=200, post_body=SAMPLE_DUMP, post_raises=None):
        self.requests = []
        self.post_status = post_status
        self.post_body = post_body
        self.post_raises = post_raises

    def __call__(self, method, url, body=None, headers=None, timeout=None):
        self.requests.append((method, url, body, headers))
        if method == "GET":
            return qa.Response(200, {}, PAGE_HTML.encode())
        if self.post_raises is not None:
            raise self.post_raises
        return qa.Response(self.post_status, {}, self.post_body.encode())


def quiet(fn, *args, **kw):
    with contextlib.redirect_stdout(io.StringIO()):
        return fn(*args, **kw)


class NonceTest(unittest.TestCase):
    def test_nonce_is_fresh_32_hex(self):
        a, b = qa.new_nonce(), qa.new_nonce()
        self.assertRegex(a, r"^[0-9a-f]{32}$")
        self.assertNotEqual(a, b)

    def test_property_and_path_match_the_launcher(self):
        self.assertEqual(qa.PROPERTY, "debug.miko3.ears_probe")
        self.assertEqual(qa.PROBE_PATH, "/settings/ears-probe")


class ParseRowsTest(unittest.TestCase):
    def test_sample_dump_gives_per_second_rows(self):
        answer = qa.parse_answer(SAMPLE_DUMP)
        self.assertEqual(answer.backend, "conexant")
        self.assertEqual(answer.seconds, 3)
        self.assertEqual([r["second"] for r in answer.rows], [1, 2, 3])
        self.assertEqual(answer.rows[0]["angle"], 12.5)
        self.assertIsNone(answer.rows[1]["angle"])
        self.assertEqual(answer.rows[2]["words"], 4)
        self.assertIs(answer.rows[2]["matched"], True)
        self.assertEqual(set(answer.rows[0]), set(qa.ROW_FIELDS))

    def test_rows_never_carry_transcript_text(self):
        """The route must never send words; if one ever did, the script refuses the dump."""
        bad = json.loads(SAMPLE_DUMP)
        bad["rows"][0]["text"] = "hello robot"
        with self.assertRaises(ValueError) as ctx:
            qa.parse_answer(json.dumps(bad))
        self.assertIn("text", str(ctx.exception))

    def test_missing_field_or_bad_json_is_an_error(self):
        bad = json.loads(SAMPLE_DUMP)
        del bad["rows"][1]["rms"]
        with self.assertRaises(ValueError):
            qa.parse_answer(json.dumps(bad))
        with self.assertRaises(ValueError):
            qa.parse_answer("<html>not json")


class FormatAndCsvTest(unittest.TestCase):
    def test_table_has_one_line_per_second_with_a_dash_for_no_angle(self):
        answer = qa.parse_answer(SAMPLE_DUMP)
        text = qa.format_rows(answer.rows)
        lines = [ln for ln in text.splitlines() if re.match(r"\s*\d+\s", ln)]
        self.assertEqual(len(lines), 3)
        self.assertIn("12.5", lines[0])
        self.assertRegex(lines[1], r"^\s*2\s+-\s")
        self.assertIn("yes", lines[2])

    def test_csv_carries_the_row_fields_in_order(self):
        answer = qa.parse_answer(SAMPLE_DUMP)
        with tempfile.TemporaryDirectory() as td:
            path = Path(td) / "probe.csv"
            qa.write_csv(answer.rows, path)
            lines = path.read_text().splitlines()
        self.assertEqual(lines[0], ",".join(qa.ROW_FIELDS))
        self.assertEqual(len(lines), 4)
        self.assertTrue(lines[2].startswith("2,,4210,"), lines[2])


class DriveTest(unittest.TestCase):
    def test_drive_url_targets_the_remote_control_endpoint(self):
        url = qa.drive_url("http://127.0.0.1:18081", 40, -10, "abcd")
        self.assertTrue(url.startswith("http://127.0.0.1:18081/drive?"))
        for needle in ("linear=40", "angular=-10", "ct=abcd"):
            self.assertIn(needle, url)

    def test_driver_resends_while_running_and_stops_the_wheels_on_stop(self):
        sent = []

        def http(method, url, body=None, headers=None, timeout=None):
            sent.append(url)
            return qa.Response(200, {}, b"ok")

        d = qa.Driver("http://127.0.0.1:18081", 40, 0, http=http, period_s=0.01)
        d.start()
        d.join_after(0.08)
        d.stop()
        self.assertGreaterEqual(len([u for u in sent if "linear=40" in u]), 3)
        self.assertIn("linear=0", sent[-1])
        self.assertIn("angular=0", sent[-1])
        self.assertTrue(all(re.search(r"ct=[0-9a-f]{8,}", u) for u in sent), sent[:2])

    def test_parse_drive_option(self):
        self.assertEqual(qa.parse_drive("40,0"), (40, 0))
        self.assertEqual(qa.parse_drive("0,-25"), (0, -25))
        with self.assertRaises(ValueError):
            qa.parse_drive("fast")


class RunTest(unittest.TestCase):
    def test_sets_a_fresh_nonce_posts_it_with_the_page_token_and_clears_it(self):
        robot, http = FakeRobot(), FakeHttp()
        answer = quiet(qa.run, robot, seconds=3, phrase="hello robot", http=http)
        writes = robot.property_writes()
        self.assertEqual(len(writes), 2, robot.calls)
        self.assertRegex(writes[0], r"^[0-9a-f]{32}$")
        self.assertEqual(writes[1], '""')
        posts = [r for r in http.requests if r[0] == "POST"]
        self.assertEqual(len(posts), 1)
        method, url, body, headers = posts[0]
        self.assertTrue(url.endswith(qa.PROBE_PATH), url)
        self.assertTrue(url.startswith("https://"), url)
        fields = dict(p.split("=", 1) for p in body.split("&"))
        self.assertEqual(fields["t"], "feedfacefeedfacefeedfacefeedface")
        self.assertEqual(fields["nonce"], writes[0])
        self.assertEqual(fields["seconds"], "3")
        self.assertEqual(fields["phrase"], "hello+robot")
        self.assertEqual(headers["Content-Type"], "application/x-www-form-urlencoded")
        self.assertEqual(len(answer.rows), 3)
        # The property is cleared after the forward is gone, never left set.
        self.assertLess(robot.calls.index(("forward-removed", qa.LAUNCHER_HTTPS_PORT)),
                        len(robot.calls) - 1)
        self.assertEqual(robot.calls[-1][:3], ("shell", "setprop", qa.PROPERTY))

    def test_interrupt_mid_probe_still_clears_the_property(self):
        robot = FakeRobot()
        http = FakeHttp(post_raises=KeyboardInterrupt())
        with self.assertRaises(KeyboardInterrupt):
            quiet(qa.run, robot, seconds=3, phrase=None, http=http)
        self.assertEqual(robot.property_writes()[-1], '""')
        self.assertEqual(robot.calls[-1][:3], ("shell", "setprop", qa.PROPERTY))

    def test_a_404_names_the_property_and_the_launcher_build(self):
        robot = FakeRobot()
        http = FakeHttp(post_status=404, post_body="not found")
        with self.assertRaises(qa.ProbeError) as ctx:
            quiet(qa.run, robot, seconds=3, phrase=None, http=http)
        self.assertIn(qa.PROPERTY, str(ctx.exception))
        self.assertEqual(robot.property_writes()[-1], '""')

    def test_drive_is_started_before_the_probe_and_stopped_after(self):
        robot = FakeRobot()
        urls = []

        def http(method, url, body=None, headers=None, timeout=None):
            urls.append(url)
            if url.endswith(qa.PROBE_PATH):
                return qa.Response(200, {}, SAMPLE_DUMP.encode())
            if "/drive?" in url:
                return qa.Response(200, {}, b"ok")
            return qa.Response(200, {}, PAGE_HTML.encode())

        quiet(qa.run, robot, seconds=1, phrase=None, http=http, drive=(40, 0))
        probe_at = next(i for i, u in enumerate(urls) if u.endswith(qa.PROBE_PATH))
        self.assertTrue(any("/drive?" in u and "linear=40" in u for u in urls[:probe_at]), urls[:probe_at])
        self.assertTrue(any("/drive?" in u and "linear=0&" in u for u in urls[probe_at:]), urls[probe_at:])
        self.assertIn(("forward", qa.REMOTE_CONTROL_PORT), robot.calls)
        self.assertEqual(robot.property_writes()[-1], '""')


class MainTest(unittest.TestCase):
    def test_parser_defaults(self):
        args = qa.build_parser().parse_args([])
        self.assertEqual(args.seconds, qa.DEFAULT_SECONDS)
        self.assertIsNone(args.phrase)
        self.assertIsNone(args.drive)
        self.assertIsNone(args.csv)


if __name__ == "__main__":
    unittest.main()
