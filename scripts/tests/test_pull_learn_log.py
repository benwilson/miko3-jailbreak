"""Tests for scripts/pull-learn-log.py: the adb fallbacks (pull, root then pull, su cat), the
split into one out/learn/<date>.log per day, idempotent merging and --clear. adb is faked."""
import contextlib
import importlib.util
import io
import subprocess
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


pl = load("pull_learn_log", HERE.parent / "pull-learn-log.py")

OLD = ("10-02 23:59:58.000 I/ExploreBrain( 4100): leg: id=1 src=steer\n"
       "10-03 00:00:01.000 I/ExploreBrain( 4100): mode: ROAM\n")
NEW = ("10-03 09:00:00.000 I/ExploreModeApp( 5200): learn: start build=abc123def456 tuning=0badf00d diff=-\n"
       "10-03 09:00:05.000 I/ExploreBrain( 5200): trig: kind=bathroom rule=strong label=toilet score=0.60 "
       "box=0.35,0.40,0.65,0.80 looks=1\n"
       "garbage that is not a record\n")


class FakeAdb:
    """Answers adb commands from a dict of remote files; root_needed makes the first pull fail."""

    def __init__(self, files, root_needed=False, pull_works=True):
        self.files, self.root_needed, self.pull_works = files, root_needed, pull_works
        self.calls = []
        self.rooted = False

    def __call__(self, cmd, timeout=60):
        self.calls.append(cmd)
        args = cmd[1:]
        if args[:2] == ["-s", "SER"]:
            args = args[2:]
        if args[0] == "root":
            self.rooted = True
            return subprocess.CompletedProcess(cmd, 0, b"", b"")
        if args[0] == "pull":
            name = args[1].rsplit("/", 1)[1]
            if not self.pull_works or name not in self.files or (self.root_needed and not self.rooted):
                return subprocess.CompletedProcess(cmd, 1, b"", b"denied")
            Path(args[2]).write_text(self.files[name])
            return subprocess.CompletedProcess(cmd, 0, b"", b"")
        if args[0] == "exec-out":
            name = args[-1].rsplit("/", 1)[1]
            if name in self.files:
                return subprocess.CompletedProcess(cmd, 0, self.files[name].encode(), b"")
            return subprocess.CompletedProcess(cmd, 1, b"cat: No such file", b"")
        return subprocess.CompletedProcess(cmd, 0, b"", b"")


def pull(fake, d, clear=False):
    with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
        return pl.pull("SER", d, 2026, clear, runner=fake)


class PullLearnLogTest(unittest.TestCase):
    def test_splits_by_day_drops_non_records_and_keeps_time_order(self):
        with tempfile.TemporaryDirectory() as d:
            fake = FakeAdb({"learn.log.1": OLD, "learn.log": NEW})
            self.assertEqual(pull(fake, d), 0)
            self.assertEqual(sorted(p.name for p in Path(d).iterdir()), ["2026-10-02.log", "2026-10-03.log"])
            day3 = Path(d, "2026-10-03.log").read_text().splitlines()
            self.assertEqual(len(day3), 3)
            self.assertIn("mode: ROAM", day3[0])
            self.assertNotIn("garbage", "".join(day3))
            self.assertTrue(all(c[:3] == ["adb", "-s", "SER"] for c in fake.calls))

    def test_pulling_again_adds_nothing(self):
        with tempfile.TemporaryDirectory() as d:
            pull(FakeAdb({"learn.log": NEW}), d)
            before = Path(d, "2026-10-03.log").read_text()
            pull(FakeAdb({"learn.log": NEW}), d)
            self.assertEqual(Path(d, "2026-10-03.log").read_text(), before)

    def test_root_then_su_cat_fallbacks(self):
        with tempfile.TemporaryDirectory() as d:
            fake = FakeAdb({"learn.log": NEW}, root_needed=True)
            self.assertEqual(pull(fake, d), 0)
            self.assertTrue(fake.rooted)
        with tempfile.TemporaryDirectory() as d:
            fake = FakeAdb({"learn.log": NEW}, pull_works=False)
            self.assertEqual(pull(fake, d), 0)
            self.assertTrue(any(c[3:6] == ["exec-out", "su", "0"] for c in fake.calls))

    def test_nothing_on_the_robot_is_an_error(self):
        with tempfile.TemporaryDirectory() as d:
            self.assertEqual(pull(FakeAdb({}), d), 1)

    def test_clear_truncates_only_the_files_pulled(self):
        with tempfile.TemporaryDirectory() as d:
            fake = FakeAdb({"learn.log": NEW})
            pull(fake, d, clear=True)
            cleared = [c for c in fake.calls if "truncate" in c]
            self.assertEqual(len(cleared), 1)
            self.assertTrue(cleared[0][-1].endswith("/files/learn.log"))


if __name__ == "__main__":
    unittest.main()
