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


NC_DUMP = json.dumps({
    "backend": "NC",
    "nc_reply": "58585542030103000000a620d0e70100",
    "seconds": 2,
    "rows": [
        {"second": 1, "angle": None, "raw": 138, "rms": 900, "decode_ms": 200, "decode_max_ms": 20, "chunks": 13,
         "words": 0, "matched": False},
        {"second": 2, "angle": None, "raw": None, "rms": 850, "decode_ms": 210, "decode_max_ms": 22, "chunks": 13,
         "words": 0, "matched": False},
    ],
})


class RawChipValueTest(unittest.TestCase):
    """The NC chip's raw 0..255 reading rides along each row (U2) so the owner can
    calibrate zero, sign and scale (KTD12); an older launcher simply omits it."""

    def test_raw_and_the_nc_reply_are_parsed_when_present(self):
        answer = qa.parse_answer(NC_DUMP)
        self.assertEqual(answer.backend, "NC")
        self.assertEqual(answer.nc_reply, "58585542030103000000a620d0e70100")
        self.assertEqual(answer.rows[0][qa.RAW_FIELD], 138)
        self.assertIsNone(answer.rows[1][qa.RAW_FIELD])

    def test_a_dump_without_raw_keeps_the_old_shape(self):
        answer = qa.parse_answer(SAMPLE_DUMP)
        self.assertIsNone(answer.nc_reply)
        self.assertNotIn(qa.RAW_FIELD, answer.rows[0])

    def test_raw_outside_a_byte_or_not_a_number_is_refused(self):
        for bad_value in (256, -1, "138", 12.5, True):
            bad = json.loads(NC_DUMP)
            bad["rows"][0]["raw"] = bad_value
            with self.assertRaises(ValueError, msg=repr(bad_value)):
                qa.parse_answer(json.dumps(bad))

    def test_the_nc_reply_must_be_a_string_or_list_of_strings(self):
        bad = json.loads(NC_DUMP)
        bad["nc_reply"] = 42
        with self.assertRaises(ValueError):
            qa.parse_answer(json.dumps(bad))
        ok = json.loads(NC_DUMP)
        ok["nc_reply"] = ["58585542aa", "58585542bb"]
        self.assertEqual(qa.parse_answer(json.dumps(ok)).nc_reply, ["58585542aa", "58585542bb"])

    def test_table_prints_the_raw_value_next_to_the_angle(self):
        text = qa.format_rows(qa.parse_answer(NC_DUMP).rows)
        header, first, second = text.splitlines()[:3]
        self.assertRegex(header, r"angle\s+raw")
        self.assertRegex(first, r"^\s*1\s+-\s+138\s")
        self.assertRegex(second, r"^\s*2\s+-\s+-\s")
        self.assertNotIn("raw", qa.format_rows(qa.parse_answer(SAMPLE_DUMP).rows))

    def test_csvs_carry_the_raw_column_after_the_angle_when_present(self):
        rows = qa.parse_answer(NC_DUMP).rows
        with tempfile.TemporaryDirectory() as td:
            plain = Path(td) / "probe.csv"
            qa.write_csv(rows, plain)
            step = Path(td) / "front.csv"
            qa.write_step_csv(rows, [], step)
            plain_lines = plain.read_text().splitlines()
            step_header = step.read_text().splitlines()[0].split(",")
        header = plain_lines[0].split(",")
        self.assertEqual(header[header.index("angle") + 1], "raw")
        self.assertEqual(dict(zip(header, plain_lines[1].split(",")))["raw"], "138")
        self.assertEqual(dict(zip(header, plain_lines[2].split(",")))["raw"], "")
        self.assertEqual(step_header[step_header.index("angle") + 1], "raw")

    def test_step_summary_reports_the_raw_readings(self):
        text = qa.format_step_summary(qa.parse_answer(NC_DUMP).rows, [])
        self.assertIn("raw: 1 of 2 seconds", text)
        self.assertIn("138", text)
        self.assertNotIn("raw:", qa.format_step_summary(qa.parse_answer(SAMPLE_DUMP).rows, []))

    def test_run_prints_the_raw_column(self):
        robot, http = FakeRobot(), FakeHttp(post_body=NC_DUMP)
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            answer = qa.run(robot, seconds=2, phrase=None, http=http)
        self.assertEqual(answer.nc_reply, "58585542030103000000a620d0e70100")
        self.assertIn("direction backend: NC", out.getvalue())
        self.assertIn("138", out.getvalue())


class MainTest(unittest.TestCase):
    def test_parser_defaults(self):
        args = qa.build_parser().parse_args([])
        self.assertEqual(args.seconds, qa.DEFAULT_SECONDS)
        self.assertIsNone(args.phrase)
        self.assertIsNone(args.drive)
        self.assertIsNone(args.csv)


# ---- the measurement session (meeting plan U2) ----

PROC_STAT_A = ("2249 (com.miko3.launcher) S 1 1 0 0 -1 4194560 12345 0 0 0 1200 300 0 0 20 0 45 0 5000 "
               "900000000 24576 18446744073709551615 1 1 0 0 0 0 0 0 0 0 0 0 17 3 0 0 0 0 0 0 0 0 0 0 0 0 0")
PROC_STAT_B = ("2249 (com.miko3.launcher) S 1 1 0 0 -1 4194560 12345 0 0 0 1260 320 0 0 20 0 45 0 5000 "
               "900000000 25600 18446744073709551615 1 1 0 0 0 0 0 0 0 0 0 0 17 3 0 0 0 0 0 0 0 0 0 0 0 0 0")

SHOVE_LOGCAT = "\n".join([
    "09-25 16:20:01.100  1234  1234 D MikoDmdRaw: sent=POWER reply=POWER=0,0,07884FLBTN=0,0,0,0,00000,00000"
    "IMUAC=-000002110,0000000295,0000023297XIMUGY=0000000062,-000000757,0000000093IMUMG=0,0,0",
    "09-25 16:20:01.300  1234  1234 D MikoDmdRaw: sent=POWER reply=POWER=0,0,07884FLBTN=0,0,0,0,00000,00000"
    "IMUAC=-000006400,0000001200,0000021000XIMUGY=0000000062,-000000757,0000000093IMUMG=0,0,0",
    "09-25 16:20:01.400  1234  1234 I ExploreBrain: nothing to do with sensors",
    "09-25 16:20:01.500  1234  1234 D MikoDmdRaw: sent=POWER reply=POWER=0,0,07884FLBTN=0,0,0,0,00000,00000"
    "IMUAC=XXXXXXXXXX,0000000295,0000023297XIMUGY=0000000062,-000000757,0000000093IMUMG=0,0,0",
])


class PercentileTest(unittest.TestCase):
    def test_nearest_rank_percentiles(self):
        values = [10, 20, 30, 40, 50, 60, 70, 80, 90, 100]
        self.assertEqual(qa.percentile(values, 50), 50)
        self.assertEqual(qa.percentile(values, 95), 100)
        self.assertEqual(qa.percentile([7], 95), 7)
        self.assertIsNone(qa.percentile([], 50))


class DecodeStatsTest(unittest.TestCase):
    def test_per_chunk_decode_and_step_percentiles_from_the_dump(self):
        rows = qa.parse_answer(SAMPLE_DUMP).rows
        stats = qa.decode_stats(rows)
        # 240/13, 610/12, 580/13 ms per chunk that second.
        self.assertAlmostEqual(stats.per_chunk_ms[0], 240 / 13, places=2)
        self.assertAlmostEqual(stats.per_chunk_ms[1], 610 / 12, places=2)
        self.assertAlmostEqual(stats.p50_ms, 580 / 13, places=2)
        self.assertAlmostEqual(stats.p95_ms, 610 / 12, places=2)
        self.assertEqual(stats.max_p95_ms, 95)

    def test_a_second_with_no_chunks_has_no_per_chunk_value(self):
        rows = qa.parse_answer(SAMPLE_DUMP).rows
        rows[0]["chunks"] = 0
        stats = qa.decode_stats(rows)
        self.assertIsNone(stats.per_chunk_ms[0])
        self.assertEqual(len([v for v in stats.per_chunk_ms if v is not None]), 2)

    def test_fallback_flag_follows_ktd2_threshold(self):
        self.assertEqual(qa.DECODE_CHUNK_BUDGET_MS, 80)
        rows = qa.parse_answer(SAMPLE_DUMP).rows
        self.assertFalse(qa.decode_stats(rows).over_budget)
        rows[1]["decode_ms"] = 12 * 90
        self.assertTrue(qa.decode_stats(rows).over_budget)


class ProcStatTest(unittest.TestCase):
    def test_parse_proc_stat_reads_ticks_and_rss_past_the_bracketed_name(self):
        sample = qa.parse_proc_stat(PROC_STAT_A)
        self.assertEqual(sample.ticks, 1500)
        self.assertEqual(sample.rss_pages, 24576)

    def test_cpu_percent_between_two_samples(self):
        a, b = qa.parse_proc_stat(PROC_STAT_A), qa.parse_proc_stat(PROC_STAT_B)
        # 80 ticks in 1 s at 100 Hz = 80 % of one core.
        self.assertAlmostEqual(qa.cpu_percent(a, b, elapsed_s=1.0, clk_tck=100), 80.0)
        self.assertAlmostEqual(qa.cpu_percent(a, b, elapsed_s=2.0, clk_tck=100), 40.0)
        self.assertIsNone(qa.cpu_percent(None, b, elapsed_s=1.0, clk_tck=100))

    def test_garbage_is_none_not_a_crash(self):
        self.assertIsNone(qa.parse_proc_stat(""))
        self.assertIsNone(qa.parse_proc_stat("No such file or directory"))

    def test_cpu_sampler_reads_pids_and_stats_over_adb(self):
        robot = FakeRobot()
        # One shell round trip per sample: "<package> <pid>" then its stat line, per package.
        answers = {("shell", qa.CpuSampler.STAT_SCRIPT):
                   f"{qa.LAUNCHER_PACKAGE} 2249\n{PROC_STAT_A}\n{qa.EXPLORE_PACKAGE} \n",
                   ("shell", "getconf", "CLK_TCK"): "100\n"}

        def adb(*args, check=True):
            robot.calls.append(args)
            return answers.get(args, "")

        robot.adb = adb
        sampler = qa.CpuSampler(robot)
        snap = sampler.snapshot()
        self.assertEqual(snap[qa.LAUNCHER_PACKAGE].ticks, 1500)
        self.assertIsNone(snap[qa.EXPLORE_PACKAGE])
        self.assertEqual(len(robot.calls), 1, "one adb round trip per sample")
        self.assertEqual(sampler.clk_tck, 100)
        self.assertIn("pidof", qa.CpuSampler.STAT_SCRIPT)
        for pkg in qa.CpuSampler.PACKAGES:
            self.assertIn(pkg, qa.CpuSampler.STAT_SCRIPT)

    def test_cpu_sampler_reads_a_restarted_or_missing_process(self):
        robot = FakeRobot()
        # Explore has come back under a new pid; the launcher's stat is garbage this second.
        robot.adb = lambda *args, check=True: (
            f"{qa.LAUNCHER_PACKAGE} 2249\nNo such file or directory\n{qa.EXPLORE_PACKAGE} 3000\n{PROC_STAT_B}\n"
            if args[0] == "shell" and args[1] == qa.CpuSampler.STAT_SCRIPT else "")
        snap = qa.CpuSampler(robot).snapshot()
        self.assertIsNone(snap[qa.LAUNCHER_PACKAGE])
        self.assertEqual(snap[qa.EXPLORE_PACKAGE].ticks, qa.parse_proc_stat(PROC_STAT_B).ticks)


class CpuRowsTest(unittest.TestCase):
    def test_per_second_cpu_rows_from_consecutive_snapshots(self):
        a, b = qa.parse_proc_stat(PROC_STAT_A), qa.parse_proc_stat(PROC_STAT_B)
        snaps = [(0.0, {qa.LAUNCHER_PACKAGE: a, qa.EXPLORE_PACKAGE: None}),
                 (1.0, {qa.LAUNCHER_PACKAGE: b, qa.EXPLORE_PACKAGE: None})]
        rows = qa.cpu_rows(snaps, clk_tck=100)
        self.assertEqual(len(rows), 1)
        self.assertAlmostEqual(rows[0]["cpu_launcher_pct"], 80.0)
        self.assertIsNone(rows[0]["cpu_explore_pct"])
        self.assertAlmostEqual(rows[0]["rss_launcher_mb"], 25600 * 4096 / 1048576, places=1)


class StepCsvTest(unittest.TestCase):
    def test_step_csv_has_decode_percentiles_and_cpu_columns(self):
        rows = qa.parse_answer(SAMPLE_DUMP).rows
        cpu = [{"cpu_launcher_pct": 80.0, "cpu_explore_pct": None, "rss_launcher_mb": 100.0}]
        with tempfile.TemporaryDirectory() as td:
            path = Path(td) / "left.csv"
            qa.write_step_csv(rows, cpu, path)
            lines = path.read_text().splitlines()
        header = lines[0].split(",")
        for col in ("decode_chunk_ms", "decode_p50_ms", "decode_p95_ms", "decode_max_p95_ms",
                    "cpu_launcher_pct", "cpu_explore_pct", "rss_launcher_mb"):
            self.assertIn(col, header)
        self.assertEqual(header[:len(qa.ROW_FIELDS)], list(qa.ROW_FIELDS))
        self.assertEqual(len(lines), 4)
        first = dict(zip(header, lines[1].split(",")))
        self.assertEqual(first["cpu_launcher_pct"], "80.0")
        self.assertEqual(first["cpu_explore_pct"], "")
        # The percentiles are step-level and repeat on every row.
        second = dict(zip(header, lines[2].split(",")))
        self.assertEqual(first["decode_p95_ms"], second["decode_p95_ms"])
        self.assertEqual(second["cpu_launcher_pct"], "")


class ShoveParseTest(unittest.TestCase):
    def test_accel_records_from_logcat_skip_unreadable_sections(self):
        recs = qa.parse_accel_log(SHOVE_LOGCAT)
        self.assertEqual(len(recs), 2)
        self.assertEqual(recs[0]["ax"], -2110)
        self.assertEqual(recs[1]["az"], 21000)
        self.assertEqual(recs[1]["t_ms"] - recs[0]["t_ms"], 200)
        self.assertGreater(recs[1]["delta"], recs[0]["delta"])

    def test_shove_csv_columns(self):
        recs = qa.parse_accel_log(SHOVE_LOGCAT)
        with tempfile.TemporaryDirectory() as td:
            path = Path(td) / "shove.csv"
            qa.write_dict_csv(recs, qa.ACCEL_FIELDS, path)
            lines = path.read_text().splitlines()
        self.assertEqual(lines[0], ",".join(qa.ACCEL_FIELDS))
        self.assertEqual(len(lines), 3)


class StateSampleTest(unittest.TestCase):
    def test_state_samples_keep_only_the_documented_keys(self):
        row = qa.parse_state_sample(1.5, '{"state":"meet_look","lookX":0.2,"lookY":-0.1}')
        self.assertEqual(row, {"t_s": 1.5, "state": "meet_look", "lookX": 0.2, "lookY": -0.1})
        self.assertIsNone(qa.parse_state_sample(0.0, "<html>"))


class SessionStepsTest(unittest.TestCase):
    def test_the_named_steps_cover_the_plan(self):
        names = [s.name for s in qa.STEPS]
        for wanted in ("left", "right", "front", "behind", "name", "shove", "face-frontal", "face-45",
                       "face-profile", "talk-3m", "talk-5m"):
            self.assertIn(wanted, names)
        self.assertEqual(len(names), len(set(names)))
        for step in qa.STEPS:
            self.assertTrue(step.instruction, step.name)
            self.assertIn(step.kind, ("ears", "shove", "face"))
        by_name = {s.name: s for s in qa.STEPS}
        self.assertEqual(by_name["left"].phrase, "hey miko")
        self.assertEqual(by_name["name"].phrase, "miko")
        self.assertIsNone(by_name["talk-3m"].phrase)
        self.assertEqual(by_name["shove"].kind, "shove")
        self.assertEqual(by_name["face-45"].kind, "face")

    def test_select_steps_by_name_and_reject_unknown(self):
        self.assertEqual([s.name for s in qa.select_steps(["front", "left"])], ["front", "left"])
        self.assertEqual(len(qa.select_steps([])), len(qa.STEPS))
        with self.assertRaises(ValueError):
            qa.select_steps(["sideways"])

    def test_ears_step_writes_its_csv_and_prints_the_instruction(self):
        robot, http = FakeRobot(), FakeHttp()
        prompts = []
        with tempfile.TemporaryDirectory() as td:
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                qa.run_session(robot, ["left"], seconds=3, out_dir=td, http=http,
                               ask=lambda text: prompts.append(text), cpu_sampler=None)
            csv_path = Path(td) / "left.csv"
            self.assertTrue(csv_path.exists())
            header = csv_path.read_text().splitlines()[0]
        self.assertIn("decode_p95_ms", header)
        self.assertEqual(len(prompts), 1)
        self.assertIn("left", prompts[0])
        self.assertEqual(robot.property_writes()[-1], '""')
        text = out.getvalue()
        self.assertIn("== left ==", text)
        self.assertIn("p95", text)

    def test_face_step_samples_the_state_page_and_names_the_todo(self):
        robot = FakeRobot()

        def http(method, url, body=None, headers=None, timeout=None):
            return qa.Response(200, {}, b'{"state":"meet_look","lookX":0.1,"lookY":0.0}')

        with tempfile.TemporaryDirectory() as td:
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                qa.run_session(robot, ["face-frontal"], seconds=1, out_dir=td, http=http,
                               ask=lambda text: None, cpu_sampler=None, sample_period_s=0.2)
            lines = (Path(td) / "face-frontal.csv").read_text().splitlines()
        self.assertEqual(lines[0], ",".join(qa.STATE_FIELDS))
        self.assertGreaterEqual(len(lines), 3)
        self.assertIn(("forward", qa.EXPLORE_PORT), robot.calls)
        self.assertIn("TODO", out.getvalue())
        # No probe property is armed for a camera step.
        self.assertEqual(robot.property_writes(), [])

    def test_shove_step_turns_the_raw_log_on_and_back_off(self):
        robot = FakeRobot()
        answers = {("logcat", "-d", "-s", f"{qa.RAW_TAG}:D"): SHOVE_LOGCAT}

        def adb(*args, check=True):
            robot.calls.append(args)
            return answers.get(args, "")

        robot.adb = adb
        with tempfile.TemporaryDirectory() as td:
            quiet(qa.run_session, robot, ["shove"], seconds=0, out_dir=td, http=FakeHttp(),
                  ask=lambda text: None, cpu_sampler=None)
            lines = (Path(td) / "shove.csv").read_text().splitlines()
        self.assertEqual(len(lines), 3)
        props = [c for c in robot.calls if c[:2] == ("shell", "setprop") and c[2] == f"log.tag.{qa.RAW_TAG}"]
        self.assertEqual([c[3] for c in props], ["DEBUG", "INFO"])

    def test_session_parser_options(self):
        args = qa.build_parser().parse_args(["--session"])
        self.assertEqual(args.session, [])
        args = qa.build_parser().parse_args(["--session", "left", "right", "--out", "out/ears"])
        self.assertEqual(args.session, ["left", "right"])
        self.assertEqual(args.out, "out/ears")
        self.assertIsNone(qa.build_parser().parse_args([]).session)


if __name__ == "__main__":
    unittest.main()
