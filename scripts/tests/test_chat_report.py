"""Tests for scripts/chat-report.py: conversation stats from `turn:` records (learn.log or
logcat) plus the logcat-only no-words and Claude request lines. Synthetic lines, no words."""
import contextlib
import datetime as dt
import importlib.util
import io
import json
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


chat = load("chat_report", HERE.parent / "chat-report.py")
BASE = dt.datetime(2026, 10, 3, 9, 0, 0)


def L(sec, msg, pid=4100, tag="ExploreBrain"):
    t = BASE + dt.timedelta(seconds=sec)
    return f"{t:%m-%d %H:%M:%S}.{t.microsecond // 1000:03d} I/{tag}({pid:>5}): {msg}\n"


def T(c, t, addr="y", req=0, line=1800, sound=1810, done=4000, open_="n", spec="n", tools="-", tries=1,
      retry="-", fail="-", nowords=0, reask="n", end="-", meet="claude-pick", faceless="y", replies=0):
    return (f"turn: c={c} t={t} open={open_} addr={addr} req={req} line={line} sound={sound} done={done} "
            f"spec={spec} tools={tools} tries={tries} retry={retry} fail={fail} unans=0 nowords={nowords} "
            f"reask={reask} end={end} meet={meet} faceless={faceless} faceseen=n replies={replies}")


LOG = [
    L(0, T(1, 1, open_="y", line=2500, sound=2510, done=5000)),
    L(10, T(1, 2, line=1500, sound=1510, done=3000, spec="y", req=-900, tools="look+places")),
    L(20, T(1, 3, addr="n", line=1700, sound="-", done="-")),
    L(30, T(1, 4, line=2100, sound=2110, done=4100, nowords=1, reask="y", end="goodbye", replies=2)),
    L(100, T(2, 1, open_="y", addr="?", line="-", sound="-", done="-", tries=2, retry="timeout",
             fail="timeout", end="failure", meet="call")),
    L(200, T(3, 1, open_="y", line=2000, sound=2010, done=4000, end="noreply")),
    L(201, "ears: conversation listen answer over: no words (answer 1980 ms long, 25 chunks fed, deaf-clipped, "
           "began 2861 ms after the deaf window, pre-roll 0 ms, ended 1357 ms after the listen's end)",
      pid=2055, tag="ListenEngine"),
    L(202, "ears: conversation listen answer over: no words", pid=2055, tag="ListenEngine"),
    L(203, "turn request with 3 message(s): LINE in 2851 ms (settings 0 ms, line at 2100 ms; system 900 chars)",
      tag="ExploreClaude"),
    L(204, "speculative turn request with 3 message(s): LINE in 1000 ms (settings 0 ms, line at 800 ms; x)",
      tag="ExploreClaude"),
]


def report(lines):
    return chat.analyze(chat.nav.parse_lines(lines, 2026))


class ChatReportTest(unittest.TestCase):
    def test_conversations_turns_and_addressed(self):
        r = report(LOG)
        self.assertEqual(r["conversations"], 3)
        self.assertEqual(r["turns"], 6)
        self.assertEqual(r["addressed"], {"y": 4, "n": 1, "?": 1})
        self.assertAlmostEqual(r["unaddressed_rate"], 1 / 5)

    def test_latency_percentiles_per_stage_and_group(self):
        r = report(LOG)
        reply = r["latency"]["reply"]
        self.assertEqual(reply["line"], {"n": 2, "p50": 1500, "p95": 2100})
        self.assertEqual(reply["req"]["p50"], -900)
        opener = r["latency"]["opener"]
        self.assertEqual(opener["line"]["n"], 2)
        self.assertEqual(opener["done"]["p95"], 5000)
        self.assertEqual(chat.pct([1, 2, 3, 4], 50), 2)
        self.assertEqual(chat.pct([1, 2, 3, 4], 95), 4)
        self.assertIsNone(chat.pct([], 50))

    def test_ends_cut_off_and_early_ends(self):
        r = report(LOG)
        self.assertEqual(r["end_reasons"], {"goodbye": 1, "failure": 1, "noreply": 1})
        self.assertEqual(r["cut_off"], 1)
        self.assertAlmostEqual(r["cut_off_rate"], 1 / 3)
        self.assertEqual(r["early_end_reasons"], {"failure": 1, "noreply": 1})

    def test_tools_retries_failures_and_no_words(self):
        r = report(LOG)
        self.assertEqual(r["tools"], {"look": 1, "places": 1})
        self.assertEqual(r["turns_with_tools"], 1)
        self.assertEqual(r["speculative_used"], 1)
        self.assertEqual(r["retried"], 1)
        self.assertEqual(r["retry_reasons"], {"timeout": 1})
        self.assertEqual(r["failures"], {"timeout": 1})
        self.assertEqual((r["wordless_answers"], r["reasks"]), (1, 1))
        self.assertEqual(r["no_words_lines"], 2)
        self.assertEqual(r["no_words_detail"]["n"], 1)
        self.assertEqual(r["no_words_detail"]["deaf_clipped"], 1)
        self.assertEqual(r["claude_requests"]["n"], 2)
        self.assertEqual(r["claude_requests"]["speculative"], 1)
        self.assertEqual(r["claude_requests"]["ms_p50"], 2851)

    def test_meetings_by_kind_faceless_and_replied(self):
        r = report(LOG)
        self.assertEqual(r["by_meeting"]["claude-pick"], {"conversations": 2, "faceless": 2, "replied": 1,
                                                          "faceless_replied": 1, "replies_p50": 0})
        self.assertEqual(r["by_meeting"]["call"]["replied"], 0)
        self.assertAlmostEqual(r["replied_rate"], 1 / 3)

    def test_an_unclosed_conversation_counts_as_unclosed_not_cut(self):
        r = report([L(0, T(7, 1, open_="y"))])
        self.assertEqual(r["end_reasons"], {"unclosed": 1})
        self.assertIsNone(r["cut_off_rate"])

    def test_old_logs_without_turn_records_count_conversations_from_the_notes(self):
        r = report([L(0, "conversation over after 3 turn(s), 0 note delta(s) kept"),
                    L(9, "conversation over after 1 turn(s), 0 note delta(s) kept")])
        self.assertEqual((r["conversations"], r["conversations_from"]), (2, "conversation over notes"))

    def test_cli_json_and_markdown(self):
        with tempfile.TemporaryDirectory() as d:
            log = Path(d, "learn.log")
            log.write_text("".join(LOG))
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                self.assertEqual(chat.main([str(log), "--year", "2026", "--json", "-"]), 0)
            self.assertEqual(json.loads(out.getvalue())["conversations"], 3)
            md = Path(d, "r.md")
            chat.main([str(log), "--year", "2026", "-o", str(md)])
            self.assertIn("| reply | line | 2 | 1500 | 2100 |", md.read_text())


if __name__ == "__main__":
    unittest.main()
