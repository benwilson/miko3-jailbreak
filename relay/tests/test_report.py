"""Tests for relay/relay/report.py, the offline latency and QA report (plan U10, KTD11).

Covers: the percentile math; per-turn latency as the KTD11 sum of durations, with a turn
missing any of the three figures reported as incomplete (never as zero latency); flushes
with no following user text counted as self-interruptions; the sustained-backlog capacity
alarm; the close-reason tally; the pass/fail boundaries of each success criterion; the
calibration gap; empty and malformed input; the CLI's Markdown and JSON output.
Every conversation here is a synthetic JSONL file shaped like relay.logging writes them.
"""
import io
import json
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path

RELAY_ROOT = Path(__file__).resolve().parents[1]
if str(RELAY_ROOT) not in sys.path:
    sys.path.insert(0, str(RELAY_ROOT))

from relay import report  # noqa: E402

MINUTE_MS = 60_000


def turn(n, user_end=None, robot=None, half_ping=None, stats=None, **extra):
    return {"ev": "turn", "n": n, "user_end_to_first_chunk_ms": user_end,
            "robot_first_chunk_to_play_ms": robot, "half_ping_rtt_ms": half_ping,
            "reply_chunks": 5, "flushed": False, "underruns": 0, "stats": stats, **extra}


def model(rt_ms, type_, **fields):
    return {"ev": "model", "rt_ms": rt_ms, "msg": {"type": type_, **fields}}


def conversation(*records, reason="sleep_word", duration_ms=30_000, turn_taking="interruptible",
                 conv="c1"):
    """Records framed by an open and (unless reason is None) a close record."""
    out = [{"ev": "open", "rt_ms": 0.0, "conv": conv, "robot": "miko-1",
            "turn_taking": turn_taking}]
    out.extend(records)
    if reason is not None:
        out.append({"ev": "close", "rt_ms": duration_ms, "reason": reason,
                    "duration_ms": duration_ms, "turns": 1})
    return out


class TempLogs:
    """A temporary log directory; write(name, records) makes <name>.jsonl."""

    def __init__(self):
        self._tmp = tempfile.TemporaryDirectory(prefix="relay-report-")
        self.dir = Path(self._tmp.name)

    def write(self, name, records, extra_lines=()):
        path = self.dir / f"{name}.jsonl"
        lines = [json.dumps({"ts": 1_700_000_000.0 + i, **r}) for i, r in enumerate(records)]
        path.write_text("\n".join([*lines, *extra_lines]) + "\n")
        return path

    def cleanup(self):
        self._tmp.cleanup()


class ReportTestCase(unittest.TestCase):
    def setUp(self):
        self.logs = TempLogs()
        self.addCleanup(self.logs.cleanup)

    def build(self, *paths, **kw):
        return report.build_report(paths or [self.logs.dir], **kw)


class PercentileTests(unittest.TestCase):
    def test_linear_interpolation_between_ranks(self):
        values = [100, 200, 300, 400]
        self.assertEqual(report.percentile(values, 50), 250)
        self.assertEqual(report.percentile(values, 0), 100)
        self.assertEqual(report.percentile(values, 100), 400)
        self.assertAlmostEqual(report.percentile(values, 95), 385)

    def test_order_does_not_matter(self):
        self.assertEqual(report.percentile([900, 100, 500], 50), 500)

    def test_one_value_is_every_percentile(self):
        self.assertEqual(report.percentile([640], 95), 640)

    def test_no_values_is_none(self):
        self.assertIsNone(report.percentile([], 50))


class LatencyTests(ReportTestCase):
    def test_turn_latency_is_the_sum_of_the_three_durations(self):
        self.logs.write("a", conversation(turn(1, 500, 60, 10)))
        r = self.build()
        (t,) = r["latency"]["turns"]
        self.assertEqual(t["total_ms"], 570)
        self.assertTrue(t["complete"])

    def test_known_durations_give_the_expected_p50_p95_and_max(self):
        # Totals 600, 700, ... 1500 over two files.
        totals = [600 + 100 * i for i in range(10)]
        first = [turn(i + 1, t - 100, 80, 20) for i, t in enumerate(totals[:5])]
        second = [turn(i + 1, t - 100, 80, 20) for i, t in enumerate(totals[5:])]
        self.logs.write("a", conversation(*first))
        self.logs.write("b", conversation(*second, conv="c2"))
        lat = self.build()["latency"]
        self.assertEqual(lat["complete_turns"], 10)
        self.assertEqual(lat["p50_ms"], 1050)
        self.assertAlmostEqual(lat["p95_ms"], 1455)
        self.assertEqual(lat["max_ms"], 1500)

    def test_missing_robot_playback_figure_is_incomplete_not_zero(self):
        self.logs.write("a", conversation(turn(1, 500, 60, 10), turn(2, 480, None, 10)))
        lat = self.build()["latency"]
        self.assertEqual(lat["complete_turns"], 1)
        self.assertEqual(lat["incomplete_turns"], 1)
        bad = lat["turns"][1]
        self.assertFalse(bad["complete"])
        self.assertIsNone(bad["total_ms"])
        self.assertEqual(bad["missing"], ["robot_first_chunk_to_play_ms"])
        self.assertEqual(lat["p50_ms"], 570)  # the incomplete turn is not counted as 0

    def test_a_turn_with_no_figures_at_all_is_incomplete(self):
        # e.g. a reply with no user_end before it, and no stats or ping yet.
        self.logs.write("a", conversation({"ev": "turn", "n": 1}))
        lat = self.build()["latency"]
        self.assertEqual(lat["incomplete_turns"], 1)
        self.assertEqual(len(lat["turns"][0]["missing"]), 3)
        self.assertIsNone(lat["p50_ms"])

    def test_non_numeric_figure_is_incomplete(self):
        self.logs.write("a", conversation(turn(1, "fast", 60, 10)))
        self.assertEqual(self.build()["latency"]["incomplete_turns"], 1)

    def test_perceived_figure_is_not_measured_without_an_endpointing_field(self):
        self.logs.write("a", conversation(turn(1, 500, 60, 10)))
        perceived = self.build()["perceived"]
        self.assertEqual(perceived["status"], "not measured")
        self.assertIsNone(perceived["p50_ms"])

    def test_perceived_figure_adds_endpointing_when_the_field_exists(self):
        self.logs.write("a", conversation(turn(1, 500, 60, 10, endpointing_ms=300)))
        perceived = self.build()["perceived"]
        self.assertEqual(perceived["status"], "measured")
        self.assertEqual(perceived["p50_ms"], 870)


class SelfInterruptionTests(ReportTestCase):
    def test_flush_with_no_user_text_within_a_second_is_a_self_interruption(self):
        self.logs.write("a", conversation(model(1000, "flush"), model(2500, "user_text", text="hi")))
        r = self.build()
        self.assertEqual(r["self_interruptions"]["flushes"], 1)
        self.assertEqual(r["self_interruptions"]["without_user_text"], 1)

    def test_flush_followed_by_user_text_is_a_real_barge_in(self):
        self.logs.write("a", conversation(model(1000, "flush"),
                                          model(1400, "user_text_delta", text="wait")))
        self.assertEqual(self.build()["self_interruptions"]["without_user_text"], 0)

    def test_the_final_user_text_counts_too(self):
        self.logs.write("a", conversation(model(1000, "flush"), model(2000, "user_text", text="stop")))
        self.assertEqual(self.build()["self_interruptions"]["without_user_text"], 0)

    def test_blank_user_text_does_not_count(self):
        self.logs.write("a", conversation(model(1000, "flush"), model(1200, "user_text_delta", text=" ")))
        self.assertEqual(self.build()["self_interruptions"]["without_user_text"], 1)

    def test_flush_at_the_end_of_the_log_is_a_self_interruption(self):
        self.logs.write("a", conversation(model(1000, "flush"), reason="link_lost"))
        self.assertEqual(self.build()["self_interruptions"]["without_user_text"], 1)

    def test_user_text_before_the_flush_does_not_count(self):
        self.logs.write("a", conversation(model(900, "user_text", text="hm"), model(1000, "flush")))
        self.assertEqual(self.build()["self_interruptions"]["without_user_text"], 1)


class UnderrunTests(ReportTestCase):
    def test_underrun_records_are_counted_per_conversation_and_in_total(self):
        self.logs.write("a", conversation({"ev": "underrun", "rt_ms": 5, "reply": 1},
                                          {"ev": "underrun", "rt_ms": 9, "reply": 1}))
        self.logs.write("b", conversation(conv="c2"))
        r = self.build()
        self.assertEqual(r["underruns"], 2)
        self.assertEqual([c["underruns"] for c in r["conversations"]], [2, 0])


class BacklogTests(ReportTestCase):
    @staticmethod
    def backlog(seconds):
        return {"type": "stats", "input_backlog": seconds, "speech_queue": 0}

    def turns(self, *backlogs_s):
        return [turn(i + 1, 500, 60, 10, stats=None if s is None else self.backlog(s))
                for i, s in enumerate(backlogs_s)]

    def test_three_consecutive_turns_above_five_frames_raise_the_alarm(self):
        # 80 ms frames: 0.48 s is 6 frames.
        self.logs.write("a", conversation(*self.turns(0.0, 0.48, 0.48, 0.48)))
        b = self.build()["backlog"]
        self.assertTrue(b["alarm"])
        self.assertEqual(b["conversations"], ["a"])
        self.assertEqual(b["max_frames"], 6)

    def test_two_consecutive_turns_do_not(self):
        self.logs.write("a", conversation(*self.turns(0.48, 0.48, 0.0, 0.48, 0.48)))
        self.assertFalse(self.build()["backlog"]["alarm"])

    def test_exactly_five_frames_is_not_above_the_threshold(self):
        self.logs.write("a", conversation(*self.turns(0.40, 0.40, 0.40)))
        self.assertFalse(self.build()["backlog"]["alarm"])

    def test_a_turn_without_stats_breaks_the_run(self):
        self.logs.write("a", conversation(*self.turns(0.48, None, 0.48, 0.48)))
        self.assertFalse(self.build()["backlog"]["alarm"])

    def test_an_explicit_frame_count_is_used_as_is(self):
        stats = {"type": "stats", "input_backlog_frames": 9}
        self.logs.write("a", conversation(*[turn(i, 500, 60, 10, stats=stats) for i in (1, 2, 3)]))
        b = self.build()["backlog"]
        self.assertTrue(b["alarm"])
        self.assertEqual(b["max_frames"], 9)

    def test_threshold_and_run_length_are_configurable(self):
        self.logs.write("a", conversation(*self.turns(0.48, 0.48)))
        self.assertTrue(self.build(backlog_turns=2)["backlog"]["alarm"])
        self.assertFalse(self.build(backlog_turns=2, backlog_frames=6)["backlog"]["alarm"])


class CloseReasonTests(ReportTestCase):
    def test_close_reasons_are_tallied_and_a_missing_close_is_its_own_bucket(self):
        self.logs.write("a", conversation(reason="sleep_word"))
        self.logs.write("b", conversation(reason="silence", conv="c2"))
        self.logs.write("c", conversation(reason="sleep_word", conv="c3"))
        self.logs.write("d", conversation(reason=None, conv="c4"))
        r = self.build()
        self.assertEqual(r["close_reasons"], {"sleep_word": 2, "silence": 1, "(no close)": 1})
        self.assertEqual([c["robot_conv"] for c in r["conversations"]], ["c1", "c2", "c3", "c4"])


class CriteriaTests(ReportTestCase):
    def status(self, name, **kw):
        return self.build(**kw)["criteria"][name]["status"]

    def test_latency_p50_at_the_bar_passes(self):
        self.logs.write("a", conversation(turn(1, 900, 80, 20)))  # exactly 1000
        self.assertEqual(self.status("latency_p50"), "pass")

    def test_latency_p50_over_the_bar_fails(self):
        self.logs.write("a", conversation(turn(1, 901, 80, 20)))
        self.assertEqual(self.status("latency_p50"), "fail")

    def test_latency_bar_is_configurable(self):
        self.logs.write("a", conversation(turn(1, 901, 80, 20)))
        self.assertEqual(self.status("latency_p50", latency_bar_ms=1100), "pass")

    def test_latency_with_only_incomplete_turns_is_no_data(self):
        self.logs.write("a", conversation(turn(1, 500, None, 10)))
        self.assertEqual(self.status("latency_p50"), "no data")

    def test_five_minute_clean_run_passes(self):
        self.logs.write("a", conversation(reason="silence", duration_ms=5 * MINUTE_MS))
        crit = self.build()["criteria"]["five_minute_run"]
        self.assertEqual(crit["status"], "pass")
        self.assertIn("interruptible", crit["detail"])

    def test_just_under_five_minutes_is_no_data(self):
        self.logs.write("a", conversation(reason="silence", duration_ms=5 * MINUTE_MS - 1))
        self.assertEqual(self.status("five_minute_run"), "no data")

    def test_five_minutes_with_an_underrun_fails(self):
        self.logs.write("a", conversation({"ev": "underrun", "rt_ms": 5}, reason="silence",
                                          duration_ms=6 * MINUTE_MS))
        self.assertEqual(self.status("five_minute_run"), "fail")

    def test_five_minutes_with_a_self_interruption_fails(self):
        self.logs.write("a", conversation(model(1000, "flush"), reason="sleep_word",
                                          duration_ms=6 * MINUTE_MS))
        self.assertEqual(self.status("five_minute_run"), "fail")

    def test_five_minutes_ending_in_a_dropout_fails(self):
        self.logs.write("a", conversation(reason="link_lost", duration_ms=6 * MINUTE_MS))
        self.assertEqual(self.status("five_minute_run"), "fail")

    def test_a_clean_strict_run_passes_after_a_dirty_interruptible_one(self):
        self.logs.write("a", conversation(model(1000, "flush"), duration_ms=6 * MINUTE_MS))
        self.logs.write("b", conversation(duration_ms=6 * MINUTE_MS, turn_taking="strict", conv="c2"))
        crit = self.build()["criteria"]["five_minute_run"]
        self.assertEqual(crit["status"], "pass")
        self.assertIn("strict", crit["detail"])

    def test_underruns_zero(self):
        self.logs.write("a", conversation())
        self.assertEqual(self.status("underruns_zero"), "pass")
        self.logs.write("b", conversation({"ev": "underrun", "rt_ms": 5}, conv="c2"))
        self.assertEqual(self.status("underruns_zero"), "fail")


class CalibrationTests(ReportTestCase):
    def test_no_calibration_leaves_the_gap_empty(self):
        self.logs.write("a", conversation(turn(1, 500, 60, 10)))
        cal = self.build()["calibration"]
        self.assertIsNone(cal["acoustic_gap_ms"])
        self.assertIsNone(cal["difference_ms"])

    def test_calibration_gap_is_set_beside_the_software_figure(self):
        self.logs.write("a", conversation(turn(1, 500, 60, 10)))
        cal = self.build(calibration_ms=[900, 1000])["calibration"]
        self.assertEqual(cal["acoustic_gap_ms"], 950)
        self.assertEqual(cal["samples_ms"], [900, 1000])
        self.assertEqual(cal["software_p50_ms"], 570)
        self.assertEqual(cal["difference_ms"], 380)


class InputTests(ReportTestCase):
    def test_empty_input_is_a_report_with_no_data(self):
        r = self.build()
        self.assertEqual(r["conversations"], [])
        self.assertEqual(r["latency"]["complete_turns"], 0)
        self.assertEqual(r["close_reasons"], {})
        for crit in r["criteria"].values():
            self.assertEqual(crit["status"], "no data")
        self.assertIn("no data", report.render_markdown(r))

    def test_malformed_lines_are_skipped_and_counted(self):
        self.logs.write("a", conversation(turn(1, 500, 60, 10)), extra_lines=["{not json", "[1]"])
        r = self.build()
        self.assertEqual(r["latency"]["complete_turns"], 1)
        self.assertEqual(r["conversations"][0]["bad_lines"], 2)

    def test_files_and_directories_mix_and_duplicates_are_read_once(self):
        a = self.logs.write("a", conversation())
        self.logs.write("b", conversation(conv="c2"))
        r = self.build(a, self.logs.dir)
        self.assertEqual([c["id"] for c in r["conversations"]], ["a", "b"])

    def test_a_missing_path_is_an_error(self):
        with self.assertRaises(FileNotFoundError):
            self.build(self.logs.dir / "nope.jsonl")


class CliTests(ReportTestCase):
    def run_cli(self, *argv):
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = report.main([str(a) for a in argv])
        return code, out.getvalue(), err.getvalue()

    def test_markdown_lists_turns_and_criteria(self):
        self.logs.write("a", conversation(turn(1, 500, 60, 10), turn(2, 480, None, 10)))
        code, out, _ = self.run_cli(self.logs.dir)
        self.assertEqual(code, 0)
        self.assertTrue(out.startswith("# Voice mode QA report"))
        self.assertIn("| a | 1 | 500 | 60 | 10 | 570 |", out)
        self.assertIn("incomplete", out)
        self.assertIn("Perceived", out)
        self.assertIn("not measured", out)
        self.assertIn("latency_p50", out)

    def test_json_output_round_trips(self):
        self.logs.write("a", conversation(turn(1, 500, 60, 10)))
        code, out, _ = self.run_cli(self.logs.dir, "--json", "--calibration-ms", "900")
        self.assertEqual(code, 0)
        data = json.loads(out)
        self.assertEqual(data["latency"]["p50_ms"], 570)
        self.assertEqual(data["calibration"]["acoustic_gap_ms"], 900)

    def test_missing_path_exits_2_with_a_message(self):
        code, _, err = self.run_cli(self.logs.dir / "nope")
        self.assertEqual(code, 2)
        self.assertIn("nope", err)


if __name__ == "__main__":
    unittest.main()
