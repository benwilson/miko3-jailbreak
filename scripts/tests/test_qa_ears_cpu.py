"""Tests for scripts/qa-ears-cpu.py's host-side logic (ears CPU work, 2026-09-30):
the properties each setting sets, the tuning the launcher's ready line must name,
the ears stats line it parses (with and without the decode timing), the counter
diff, the per-thread CPU from /proc/<pid>/task/*/stat, the table, and one whole
setting driven through fakes, including the restore that clears every switch.
The adb calls themselves are device-only and faked here."""
import contextlib
import importlib.util
import io
import re
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCRIPT = HERE.parent / "qa-ears-cpu.py"
EARS_TUNING = HERE.parents[1] / "launcher" / "src" / "com" / "miko3" / "launcher" / "EarsTuning.java"
HARNESS = (HERE / "fixtures" / "listen_service_harness" / "src" / "com" / "miko3" / "launcher"
           / "ListenServiceHarness.java")


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


qa = load("qa_ears_cpu", SCRIPT)

READY = ("09-30 14:01:02.345  1234  1250 I ListenEngine: recognizer ready in 2100 ms (files 40 ms, load 1800 ms, "
         "warm-up and VAD 260 ms; decoding=greedy_search paths=2 threads=1 hotwords=off gate=off)")
NEW = ("09-30 14:02:02.345  1234  1260 I ListenEngine: ears: chunks=750 utterances=9 delivered=4 strong=2 weak=2 "
       "partial=0 dropped=12 resets=1 wakes=2 decode_p50=21.5 decode_p95=64.0 decode_max=180.2 decoded=8 "
       "fed=310 gated=0")
OLD = ("09-30 14:02:02.345  1234  1260 I ListenEngine: ears: chunks=750 utterances=9 delivered=4 strong=2 weak=2 "
       "partial=0 dropped=12 resets=1 wakes=2")
RELEASED = ("09-30 14:03:02.345  1234  1260 I ListenEngine: ears: released uid 10077: binder died; chunks=900 "
            "utterances=11 delivered=5 strong=3 weak=2 partial=0 dropped=12 resets=1 wakes=3 decode_p50=- "
            "decode_p95=- decode_max=- decoded=0 fed=320 gated=40")


class SettingsTest(unittest.TestCase):
    def test_props_mirror_ears_tuning(self):
        src = EARS_TUNING.read_text()
        for key, prop in qa.PROPS.items():
            self.assertRegex(src, re.escape(f'"{prop}"'), key)
        self.assertIn('DEFAULT_THREADS = 2', src)
        self.assertIn('DEFAULT_PATHS = 2', src)

    def test_every_setting_sets_all_four_and_clears_the_rest(self):
        greedy = qa.select_settings(["greedy-threads1"])[0]
        self.assertEqual(dict(qa.prop_commands(greedy)), {
            "persist.miko3.ears.decoding": "greedy_search", "persist.miko3.ears.paths": "",
            "persist.miko3.ears.threads": "1", "persist.miko3.ears.gate": ""})
        base = qa.select_settings(["baseline"])[0]
        self.assertEqual({v for _, v in qa.prop_commands(base)}, {""})

    def test_select_keeps_order_and_refuses_unknown(self):
        self.assertEqual([s.name for s in qa.select_settings(["gate", "baseline"])], ["gate", "baseline"])
        self.assertEqual(len(qa.select_settings([])), len(qa.SETTINGS))
        with self.assertRaises(ValueError):
            qa.select_settings(["turbo"])

    def test_expected_tuning_matches_the_launchers_to_string(self):
        """The harness pins EarsTuning.toString(); the script must expect the same text."""
        harness = HARNESS.read_text()
        base = qa.expected_tuning(qa.select_settings(["baseline"])[0])
        self.assertEqual(base, "decoding=modified_beam_search paths=2 threads=2 hotwords=on gate=off")
        self.assertIn(f'"{base}"', harness)
        self.assertEqual(qa.expected_tuning(qa.select_settings(["greedy"])[0]),
                         "decoding=greedy_search paths=2 threads=2 hotwords=off gate=off")
        self.assertEqual(qa.expected_tuning(qa.select_settings(["gate"])[0]),
                         "decoding=modified_beam_search paths=2 threads=2 hotwords=on gate=wake")


class ParseTest(unittest.TestCase):
    def test_ready_line(self):
        self.assertEqual(qa.parse_ready("noise\n" + READY + "\n"),
                         "decoding=greedy_search paths=2 threads=1 hotwords=off gate=off")
        self.assertIsNone(qa.parse_ready("ListenEngine: recognizer ready in 2100 ms (files 1 ms, load 2 ms, "
                                         "warm-up and VAD 3 ms)"))

    def test_new_summary(self):
        s = qa.parse_summary(NEW)
        self.assertEqual((s["wakes"], s["strong"], s["weak"], s["fed"], s["gated"], s["decoded"]), (2, 2, 2, 310, 0, 8))
        self.assertEqual((s["decode_p50"], s["decode_p95"], s["decode_max"]), (21.5, 64.0, 180.2))

    def test_old_summary_and_released_summary(self):
        old = qa.parse_summary(OLD)
        self.assertEqual(old["utterances"], 9)
        self.assertIsNone(old["decode_p95"])
        self.assertIsNone(old["fed"])
        rel = qa.parse_summary(RELEASED)
        self.assertEqual((rel["chunks"], rel["gated"], rel["decode_p95"]), (900, 40, None))
        self.assertIsNone(qa.parse_summary("ListenEngine: ears: capture open"))

    def test_summaries_and_diff(self):
        found = qa.summaries("\n".join([OLD, "ears: capture open", NEW, RELEASED]))
        self.assertEqual(len(found), 3)
        d = qa.counter_diff(found[1], found[2])
        self.assertEqual((d["utterances"], d["strong"], d["wakes"], d["gated"]), (2, 1, 1, 40))
        self.assertIsNone(qa.counter_diff(found[0], found[1])["fed"])

    def test_task_stats_and_thread_cpu(self):
        def stat(tid, name, utime, stime):
            return f"{tid} ({name}) S 1 1 0 0 -1 4194368 10 0 0 0 {utime} {stime} 0 0 20 0 30 0 100 0 0"
        before = qa.parse_task_stats("\n".join([stat(10, "ears", 100, 20), stat(11, "listen) x", 5, 5),
                                                stat(12, "Thread-3", 0, 0), stat(13, "Thread-3", 0, 0)]))
        self.assertEqual(before[11], ("listen) x", 10))
        after = qa.parse_task_stats("\n".join([stat(10, "ears", 400, 70), stat(11, "listen) x", 5, 5),
                                               stat(12, "Thread-3", 100, 0), stat(13, "Thread-3", 50, 0),
                                               stat(14, "new", 999, 0), "garbage"]))
        rows = qa.thread_cpu(before, after, 10.0, 100)
        self.assertEqual(rows, [("ears", 35.0), ("Thread-3", 15.0)])
        self.assertEqual(qa.thread_cpu(before, after, 0, 100), [])


class FakeProbe:
    class ProbeError(SystemExit):
        pass

    percentile = staticmethod(lambda values, p: max(v for v in values if v is not None))

    class CpuSampler:
        rows = []

        def __init__(self, robot):
            self.clk_tck = 100

        def start(self):
            pass

        def stop(self):
            return list(FakeProbe.CpuSampler.rows)


class FakeRobot:
    """Answers adb like the robot would across one setting: the log fills as the run goes."""

    def __init__(self, ready=READY):
        self.calls = []
        self.log = []
        self.ready = ready
        self.stage = 0

    def adb(self, *args, check=True):
        self.calls.append(args)
        if args[:2] == ("shell", "am") and args[2] == "start":
            self.log = [self.ready, "ListenEngine: ears: capture open"]
        if args[0] == "logcat" and args[1] == "-c":
            self.log = []
            self.stage += 1
        if args[0] == "logcat" and args[1] == "-d":
            if not self.log and self.stage in (2, 3):
                self.log = [OLD.replace("utterances=9", "utterances=1") if self.stage == 2 else NEW]
            return "\n".join(self.log)
        if args[:2] == ("shell", "pidof"):
            return "4321\n"
        if args[0] == "shell" and "task" in args[1]:
            return ""
        return ""


class RunTest(unittest.TestCase):
    def setUp(self):
        # The run talks to the owner on stdout and stderr; keep the test output clean.
        stack = contextlib.ExitStack()
        stack.enter_context(contextlib.redirect_stdout(io.StringIO()))
        stack.enter_context(contextlib.redirect_stderr(io.StringIO()))
        self.addCleanup(stack.close)

    def test_one_setting_end_to_end(self):
        FakeProbe.CpuSampler.rows = [{"cpu_launcher_pct": 120.0, "cpu_explore_pct": 150.0},
                                     {"cpu_launcher_pct": 80.0, "cpu_explore_pct": 170.0}]
        robot = FakeRobot()
        prompts = []
        run = qa.Run(robot, FakeProbe, 30, ask=prompts.append, sleep=lambda s: None, clock=iter(range(10000)).__next__)
        r = run.one(qa.select_settings(["greedy-threads1"])[0])
        self.assertEqual(r.tuning, "decoding=greedy_search paths=2 threads=1 hotwords=off gate=off")
        self.assertEqual((r.talk_cpu, r.talk_cpu_p95, r.explore_cpu), (100.0, 120.0, 160.0))
        self.assertEqual(r.counters["utterances"], 8)
        self.assertEqual(r.decode["decode_p95"], 64.0)
        self.assertEqual(len(prompts), 1)
        sets = [c for c in robot.calls if c[:2] == ("shell", "setprop")]
        self.assertEqual(len(sets), 4)
        self.assertIn(("shell", "setprop", "persist.miko3.ears.threads", "1"), sets)
        self.assertIn(("shell", "am", "force-stop", "com.miko3.launcher"), robot.calls)
        table = qa.format_table([r])
        self.assertIn("greedy-threads1", table)
        self.assertIn(" ok", table)

    def test_a_stale_launcher_is_refused(self):
        robot = FakeRobot(ready=READY.replace("threads=1", "threads=2"))
        run = qa.Run(robot, FakeProbe, 30, ask=lambda p: None, sleep=lambda s: None,
                     clock=iter(range(10000)).__next__)
        with self.assertRaises(FakeProbe.ProbeError):
            run.one(qa.select_settings(["greedy-threads1"])[0])

    def test_restore_clears_every_switch_and_restarts(self):
        robot = FakeRobot()
        qa.Run(robot, FakeProbe, 30).restore()
        sets = [c for c in robot.calls if c[:2] == ("shell", "setprop")]
        self.assertEqual(sorted(c[2] for c in sets), sorted(qa.PROPS.values()))
        self.assertTrue(all(c[3] == '""' for c in sets))
        self.assertIn(("shell", "am", "start", "-n", "com.miko3.launcher/.MainActivity"), robot.calls)

    def test_over_budget_is_flagged(self):
        s = qa.select_settings(["baseline"])[0]
        r = qa.Result(s, "", 10.0, 190.0, 210.0, 160.0, {"wakes": 1}, {"decode_p95": 95.0}, [("ears", 60.0)])
        table = qa.format_table([r])
        self.assertIn("OVER", table)
        self.assertIn("baseline threads: ears 60.0%", table)


if __name__ == "__main__":
    unittest.main()
