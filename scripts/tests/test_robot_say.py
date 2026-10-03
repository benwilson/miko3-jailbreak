#!/usr/bin/env python3
"""Tests for scripts/robot-say.py: the debug "heard text" injector (2026-10-02).

Parsing of the step scripts and the log, the broadcast's quoting, the wait for the
robot to listen before each answer, and the debug property set at start and always
cleared on exit, against a fake adb and a scripted log stream.
"""
import importlib.util
import shlex
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SCRIPT = REPO / "scripts" / "robot-say.py"
SCRIPTS_DIR = REPO / "scripts" / "robot-say-scripts"

_spec = importlib.util.spec_from_file_location("robot_say", SCRIPT)
rs = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(rs)

PROP_ON = f"setprop {rs.PROPERTY} 1"
PROP_OFF = f'setprop {rs.PROPERTY} ""'


class FakeAdb:
    """Records each `adb shell` command; answers like a rooted robot with the launcher installed."""

    def __init__(self, fail_on=None, raise_on=None):
        self.shells = []
        self.fail_on = fail_on
        self.raise_on = raise_on

    def __call__(self, cmd, timeout=30):
        assert cmd[0] == "adb" and cmd[1] == "shell", cmd
        command = cmd[2]
        self.shells.append(command)
        if self.raise_on and self.raise_on in command:
            raise KeyboardInterrupt
        if self.fail_on and self.fail_on in command:
            return subprocess.CompletedProcess(cmd, 1, "", "boom")
        out = ""
        if command == "id -u":
            out = "0\n"
        elif command.startswith("am broadcast"):
            out = "Broadcasting: Intent { ... }\nBroadcast completed: result=0\n"
        return subprocess.CompletedProcess(cmd, 0, out, "")

    def broadcasts(self):
        return [c for c in self.shells if c.startswith("am broadcast") or c.startswith("su 0 am broadcast")]


class FakeClock:
    def __init__(self):
        self.t = 0.0

    def __call__(self):
        return self.t


class FakeStream:
    """Hands out scripted (tag, message) lines. Each entry is released once its trigger
    count of broadcasts has been sent; get() with nothing ready advances the fake clock."""

    def __init__(self, adb, clock, script):
        self.adb = adb
        self.clock = clock
        self.script = list(script)  # (after_n_broadcasts, tag, message)
        self.closed = False

    def get(self, timeout):
        sent = len(self.adb.broadcasts())
        if self.script and self.script[0][0] <= sent:
            _, tag, msg = self.script.pop(0)
            return tag, msg
        self.clock.t += timeout
        return None

    def close(self):
        self.closed = True


def acked(n):
    return (n, "EarsInject", "queued an utterance")


class ParseScriptTest(unittest.TestCase):
    def test_steps_comments_and_directives(self):
        steps = rs.parse_script("# hi\n\n@wake\n@wake  turn around \n@pause 1.5\nyes, go find the printer\n")
        self.assertEqual(steps, [rs.Step("wake"), rs.Step("wake", "turn around"), rs.Step("pause", seconds=1.5),
                                 rs.Step("say", "yes, go find the printer")])

    def test_bad_lines_are_named(self):
        for text, needle in (("@pause soon\n", "line 1"), ("hi\n@shout x\n", "line 2: unknown directive"),
                             ("@pause -1\n", "negative"), ("# only\n@pause 1\n", "nothing to say")):
            with self.subTest(text=text):
                with self.assertRaises(rs.SayError) as cm:
                    rs.parse_script(text)
                self.assertIn(needle, str(cm.exception))

    def test_bundled_scripts_parse(self):
        want = {"commands.txt": ["wake", "say", "say"], "errand.txt": ["wake", "say"],
                "chat.txt": ["wake", "say", "say", "say", "say"]}
        for name, kinds in want.items():
            with self.subTest(name=name):
                steps = rs.parse_script((SCRIPTS_DIR / name).read_text())
                self.assertEqual([s.kind for s in steps], kinds)
        self.assertEqual(rs.parse_script((SCRIPTS_DIR / "chat.txt").read_text())[-1].text, "bye")


class ParseLogTest(unittest.TestCase):
    def test_time_format_lines(self):
        self.assertEqual(rs.parse_log("10-02 12:00:01.234 I/ListenEngine( 1234): ears: conversation listen open: "
                                      "6000 ms to start answering\n"),
                         ("ListenEngine", "ears: conversation listen open: 6000 ms to start answering"))
        self.assertEqual(rs.parse_log("10-02 12:00:01.234 I/ExploreBrain(  99): turn: c=3 t=1 open=y"),
                         ("ExploreBrain", "turn: c=3 t=1 open=y"))
        self.assertIsNone(rs.parse_log("--------- beginning of main"))

    def test_relevant_lines(self):
        for msg in ("turn: c=1", "act: start turn", "task: start n=1", "intent: find_person (target given)",
                    "conversation over after 3 turn(s), 0 note delta(s) kept", "ears: conversation listen answering"):
            self.assertTrue(rs.relevant(msg), msg)
        for msg in ("ears: chunks=12 utterances=1", "someone new: asking their name"):
            self.assertFalse(rs.relevant(msg), msg)


class BroadcastTest(unittest.TestCase):
    def test_words_survive_the_remote_shell(self):
        cmd = rs.broadcast_command("go see if anyone's there; rm -rf /", True)
        args = shlex.split(cmd)
        self.assertEqual(args[:2], ["am", "broadcast"])
        self.assertEqual(args[args.index("-n") + 1], rs.COMPONENT)
        self.assertEqual(args[args.index("-a") + 1], rs.ACTION)
        self.assertEqual(args[args.index("--es") + 2], "go see if anyone's there; rm -rf /")
        self.assertEqual(args[args.index("--ez") + 1:args.index("--ez") + 3], ["wake", "true"])

    def test_bare_wake_and_non_root(self):
        cmd = rs.broadcast_command("", True, root=False)
        self.assertTrue(cmd.startswith("su 0 am broadcast"))
        self.assertNotIn("--es", cmd)
        self.assertNotIn("--ez", rs.broadcast_command("yes", False))

    def test_constants_match_the_launcher(self):
        src = (REPO / "launcher" / "src" / "com" / "miko3" / "launcher" / "EarsInject.java").read_text()
        self.assertIn(f'PROPERTY = "{rs.PROPERTY}"', src)
        self.assertIn(f'ACTION = "{rs.ACTION}"', src)
        self.assertIn('EXTRA_TEXT = "text"', src)
        self.assertIn('EXTRA_WAKE = "wake"', src)
        engine = (REPO / "launcher" / "src" / "com" / "miko3" / "launcher" / "ListenEngine.java").read_text()
        self.assertIn('TAG = "ListenEngine"', engine)
        self.assertIn('"conversation listen open: "', (REPO / "launcher" / "src" / "com" / "miko3" / "launcher"
                                                      / "EarsSession.java").read_text())
        self.assertIn('"inject spent: ', src)


class RunnerTest(unittest.TestCase):
    def make(self, script, timeout=10, tail=5):
        self.adb = FakeAdb()
        robot = rs.Robot(runner=self.adb)
        self.clock = FakeClock()
        self.stream = FakeStream(self.adb, self.clock, script)
        self.out = []
        return rs.Runner(robot, self.stream, out=self.out.append, clock=self.clock, timeout=timeout, tail=tail)

    def test_waits_for_a_listen_after_the_words_were_taken(self):
        steps = rs.parse_script("@wake\ncan you turn around\nthanks\n")
        runner = self.make([
            acked(1),
            (1, "ListenEngine", "ears: conversation listen open: 6000 ms to start answering"),  # took the call's words
            (1, "ListenEngine", "ears: inject spent: the session took the words"),
            (1, "ExploreBrain", "turn: c=1 t=0"),
            (1, "ListenEngine", "ears: conversation listen open: 6000 ms to start answering"),
            acked(2),
            (2, "ListenEngine", "ears: inject spent: the session took the words"),
            (2, "ExploreBrain", "act: start turn"),
            (2, "ListenEngine", "ears: conversation listen open: 6000 ms to start answering"),
            acked(3),
            (3, "ListenEngine", "ears: inject spent: the session took the words"),
            (3, "ExploreBrain", "conversation over after 2 turn(s), 0 note delta(s) kept"),
        ])
        self.assertTrue(runner.run(steps))
        sent = self.adb.broadcasts()
        self.assertEqual(len(sent), 3)
        self.assertIn("--ez wake true", sent[0])
        self.assertIn("'can you turn around'", sent[1])
        self.assertTrue(any("act: start turn" in line for line in self.out))
        self.assertTrue(any("conversation over" in line for line in self.out))

    def test_listen_that_opened_while_the_words_played_does_not_release_the_next_line(self):
        steps = rs.parse_script("@wake turn around\nthanks\n")
        runner = self.make([acked(1), (1, "ListenEngine", "ears: conversation listen open: 6000 ms"),
                            (1, "ListenEngine", "ears: inject spent: the session took the words")], timeout=3)
        self.assertFalse(runner.run(steps))
        self.assertEqual(len(self.adb.broadcasts()), 1)
        self.assertTrue(any("did not listen within 3 s" in line for line in self.out))

    def test_refusal_from_the_launcher_stops_the_run(self):
        runner = self.make([(1, "EarsInject", "ignored: debug.miko3.ears_inject is not 1")])
        with self.assertRaises(rs.SayError):
            runner.run([rs.Step("say", "hello")])


class MainCleanupTest(unittest.TestCase):
    def run_main(self, argv, adb, script=()):
        robot = rs.Robot(runner=adb)
        clock = FakeClock()
        made = []

        def factory(r):
            s = FakeStream(adb, clock, script)
            made.append(s)
            return s

        orig = rs.Runner.__init__

        def init(self, *a, **kw):
            kw["clock"] = clock
            orig(self, *a, **kw)

        rs.Runner.__init__ = init
        try:
            code = rs.main(argv + ["--tail", "1"], robot=robot, stream_factory=factory)
        finally:
            rs.Runner.__init__ = orig
        return code, made

    def test_sets_then_clears_the_property(self):
        adb = FakeAdb()
        code, made = self.run_main(["--wake", "turn around"], adb, [acked(1)])
        self.assertEqual(code, 0)
        self.assertIn(PROP_ON, adb.shells)
        self.assertEqual(adb.shells[-1], PROP_OFF)
        self.assertLess(adb.shells.index(PROP_ON), adb.shells.index(adb.broadcasts()[0]))
        self.assertTrue(made[0].closed)

    def test_clears_the_property_when_the_broadcast_fails(self):
        adb = FakeAdb(fail_on="am broadcast")
        code, _ = self.run_main(["hello"], adb)
        self.assertEqual(code, 1)
        self.assertEqual(adb.shells[-1], PROP_OFF)

    def test_clears_the_property_on_ctrl_c(self):
        adb = FakeAdb(raise_on="am broadcast")
        code, _ = self.run_main(["hello"], adb)
        self.assertEqual(code, 130)
        self.assertEqual(adb.shells[-1], PROP_OFF)

    def test_clears_the_property_when_the_robot_never_listens(self):
        adb = FakeAdb()
        with tempfile.NamedTemporaryFile("w", suffix=".txt") as f:
            f.write("@wake\nhello\n")
            f.flush()
            code, _ = self.run_main(["--script", f.name, "--timeout", "2"], adb, [acked(1)])
        self.assertEqual(code, 1)
        self.assertEqual(adb.shells[-1], PROP_OFF)

    def test_bad_arguments_never_touch_the_robot(self):
        for argv in ([], ["--script", "/nonexistent/steps.txt"]):
            adb = FakeAdb()
            with self.subTest(argv=argv):
                self.assertEqual(rs.main(argv, robot=rs.Robot(runner=adb)), 2)
                self.assertEqual(adb.shells, [])


if __name__ == "__main__":
    unittest.main()
