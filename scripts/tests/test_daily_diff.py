"""Tests for scripts/daily-diff.py: two synthetic learn.log days (no words, no names) through
nav-report and chat-report --json, the trig: summary, rates per roaming hour with sample counts,
what counts as moved, and the git log between the two start records' builds."""
import contextlib
import datetime as dt
import importlib.util
import io
import json
import subprocess
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


dd = load("daily_diff", HERE.parent / "daily-diff.py")


def L(base, sec, msg, pid=4100, tag="ExploreBrain"):
    t = base + dt.timedelta(seconds=sec)
    return f"{t:%m-%d %H:%M:%S}.{t.microsecond // 1000:03d} I/{tag}({pid:>5}): {msg}\n"


def learn_day(day, build, hazards, bathroom, reply_line_ms, replied=6):
    """One hour roaming as the learn.log writes it: the start record, mode, alive lines, the records."""
    base = dt.datetime(2026, 10, day, 9, 0, 0)
    out = [L(base, 0, f"learn: start build={build} tuning=0badf00d diff=-", tag="ExploreModeApp"),
           L(base, 0.5, "mode: ROAM")]
    out += [L(base, 60 * i, "learn: alive", tag="ExploreLearn") for i in range(1, 61)]
    out += [L(base, 100 + 30 * i, "hazard while HOP: OBSTACLE (front)") for i in range(hazards)]
    out += [L(base, 900 + i, "trig: kind=bathroom rule=strong label=toilet score=0.60 box=0.35,0.40,0.65,0.80 "
                             "looks=1") for i in range(bathroom)]
    for c in range(1, 7):
        s = 1200 + 300 * c
        out.append(L(base, s, f"turn: c={c} t=1 open=y addr=y req=0 line=2500 sound=2510 done=5000 spec=n tools=- "
                               "tries=1 retry=- fail=- unans=0 nowords=0 reask=n end=- meet=call faceless=y faceseen=n replies=0"))
        out.append(L(base, s + 10, f"turn: c={c} t=2 open=n addr=y req=0 line={reply_line_ms} sound=1 done=2 "
                                    "spec=n tools=- tries=1 retry=- fail=- unans=0 nowords=0 reask=n end=goodbye meet=call "
                                    f"faceless=y faceseen=n replies={1 if c <= replied else 0}"))
    out.append(L(base, 3605, "mode: DOCKED"))
    return "".join(sorted(out))


def git(*args):
    return subprocess.run(["git", *args], cwd=str(REPO), capture_output=True, text=True).stdout.strip()


class DailyDiffTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.td = tempfile.TemporaryDirectory()
        d = Path(cls.td.name)
        cls.old, cls.new = git("rev-parse", "--short=12", "HEAD~2"), git("rev-parse", "--short=12", "HEAD")
        cls.a, cls.b = d / "a.log", d / "b.log"
        cls.a.write_text(learn_day(2, cls.old, hazards=2, bathroom=6, reply_line_ms=1500))
        cls.b.write_text(learn_day(3, cls.new + "+deadbeef", hazards=8, bathroom=0, reply_line_ms=1500, replied=2))
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            dd.main([str(cls.a), str(cls.b), "--year", "2026", "--json", "-"])
        cls.r = json.loads(out.getvalue())

    @classmethod
    def tearDownClass(cls):
        cls.td.cleanup()

    def moved(self):
        return {row["metric"]: row for row in self.r["moved"]}

    def test_roaming_hours_come_from_the_learn_log_alone(self):
        self.assertAlmostEqual(self.r["a"]["metrics"]["roaming_hours"][0], 1.0, places=2)
        self.assertAlmostEqual(self.r["b"]["metrics"]["roaming_hours"][0], 1.0, places=2)

    def test_hazards_per_roaming_hour_moved_with_their_counts(self):
        row = self.moved()["nav.hazards_per_h"]
        self.assertEqual((row["a_n"], row["b_n"]), (2, 8))
        self.assertAlmostEqual(row["change"], 3.0, places=1)

    def test_trig_summary_and_its_rate(self):
        self.assertEqual(self.r["a"]["trig"]["by_rule"], {"bathroom/strong": 6})
        self.assertEqual(self.r["b"]["trig"]["total"], 0)
        row = self.moved()["trig.bathroom/strong_per_h"]
        self.assertEqual((row["a_n"], row["b"]), (6, 0.0))

    def test_replied_rate_by_meeting_kind(self):
        row = self.moved()["chat.meet.call.replied_rate"]
        self.assertAlmostEqual(row["a"], 1.0)
        self.assertAlmostEqual(row["b"], 2 / 6)
        self.assertEqual(row["b_n"], 6)

    def test_what_did_not_move_or_has_few_samples_is_left_out(self):
        moved = self.moved()
        self.assertNotIn("chat.reply.line_p50_ms", moved)  # 1500 both days
        self.assertNotIn("roaming_hours", moved)

    def test_git_log_between_the_builds(self):
        g = self.r["git"]
        self.assertEqual((g["a"], g["b"]), (self.old, self.new))
        self.assertEqual(len(g["log"]), 2)

    def test_compare_rules(self):
        ma = {"x": (10.0, 10, "rate"), "y": (1.0, 2, "rate"), "z": (0, 0, "rate"), "w": (5.0, 20, "ms")}
        mb = {"x": (13.0, 13, "rate"), "y": (4.0, 3, "rate"), "z": (2.0, 6, "rate"), "w": (5.5, 20, "ms")}
        rows = {r["metric"]: r for r in dd.compare(ma, mb, min_change=0.2, min_n=5)}
        self.assertEqual(sorted(rows), ["x", "z"])
        self.assertIsNone(rows["z"]["change"])
        self.assertEqual(len(dd.compare(ma, mb, show_all=True)), 4)

    def test_unknown_builds_give_no_log(self):
        self.assertIsNone(dd.git_log([], ["abc1234"])["log"])
        self.assertIsNone(dd.sha_of("nogit-20261003T000000Z"))
        self.assertEqual(dd.sha_of("abc1234def56+0011aabb"), "abc1234def56")

    def test_markdown(self):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            dd.main([str(self.a), str(self.b), "--year", "2026"])
        md = out.getvalue()
        self.assertIn("| nav.hazards_per_h | 2.00 | 2 | 7.99 | 8 | +300% |", md)
        self.assertIn("## Code between the builds", md)


if __name__ == "__main__":
    unittest.main()
