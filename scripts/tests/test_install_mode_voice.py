#!/usr/bin/env python3
"""Tests for scripts/install-mode-voice.py (U6).

Covers the device commands the install emits and their order (install, then
grant RECORD_AUDIO, then launch, so the first launch shows no permission
dialog), transport targeting (every adb call names the serial), and the
clean failures: no reachable device, no adb on PATH, a hung adb, a missing
APK. The install on the robot itself is the plan's on-device scenario, not
this file.
"""
import importlib.util
import subprocess
import types
import unittest
from pathlib import Path
from unittest import mock

REPO = Path(__file__).resolve().parents[2]
INSTALL_PY = REPO / "scripts" / "install-mode-voice.py"


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load {path}")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


inst = load("install_mode_voice", INSTALL_PY)

SERIAL = "192.168.19.74:5555"


def done(stdout="", returncode=0, stderr=""):
    return types.SimpleNamespace(stdout=stdout, stderr=stderr, returncode=returncode)


class FakeAdb:
    """Records every command; answers from a {subcommand-prefix: result} table."""

    def __init__(self, answers=None):
        self.calls = []
        self.answers = answers or {}

    def __call__(self, cmd, **kw):
        self.calls.append(cmd)
        joined = " ".join(cmd)
        for prefix, result in self.answers.items():
            if prefix in joined:
                return result
        return done()


class CommandTest(unittest.TestCase):
    def test_default_serial_is_the_robot_over_tcp(self):
        self.assertEqual(inst.DEFAULT_SERIAL, SERIAL)

    def test_component_is_the_mode_activity(self):
        self.assertEqual(inst.COMPONENT, "com.miko3.mode.voice/.MainActivity")

    def test_install_grant_launch_in_order(self):
        cmds = inst.install_commands(SERIAL, Path("/x/voice.apk"))
        self.assertEqual(cmds, [
            ["adb", "-s", SERIAL, "install", "-r", "-t", "/x/voice.apk"],
            ["adb", "-s", SERIAL, "shell", "pm", "grant", "com.miko3.mode.voice",
             "android.permission.RECORD_AUDIO"],
            ["adb", "-s", SERIAL, "shell", "am", "start", "-n", "com.miko3.mode.voice/.MainActivity"],
        ])

    def test_every_adb_call_names_the_serial(self):
        with mock.patch.object(inst.subprocess, "run", return_value=done()) as run:
            inst.adb("10.0.0.9:5555", "shell", "true")
        self.assertEqual(run.call_args[0][0][:3], ["adb", "-s", "10.0.0.9:5555"])


class ArgsTest(unittest.TestCase):
    def test_defaults(self):
        args = inst.parse_args([])
        self.assertEqual(args.serial, SERIAL)
        self.assertFalse(args.no_build)

    def test_overrides(self):
        args = inst.parse_args(["--serial", "emulator-5554", "--no-build"])
        self.assertEqual(args.serial, "emulator-5554")
        self.assertTrue(args.no_build)


class ReachabilityTest(unittest.TestCase):
    def test_tcp_serial_is_connected_then_checked(self):
        fake = FakeAdb({"get-state": done("device\n")})
        with mock.patch.object(inst.subprocess, "run", fake):
            inst.ensure_reachable(SERIAL)
        self.assertEqual(fake.calls[0], ["adb", "connect", SERIAL])
        self.assertEqual(fake.calls[1], ["adb", "-s", SERIAL, "get-state"])

    def test_usb_serial_skips_connect(self):
        fake = FakeAdb({"get-state": done("device\n")})
        with mock.patch.object(inst.subprocess, "run", fake):
            inst.ensure_reachable("MIKO3250XXM3Q0636CB")
        self.assertEqual(fake.calls, [["adb", "-s", "MIKO3250XXM3Q0636CB", "get-state"]])

    def test_unreachable_device_fails_with_clear_message(self):
        fake = FakeAdb({
            "connect": done("failed to connect to '192.168.19.74:5555': No route to host\n", 1),
            "get-state": done("", 1, "error: device '192.168.19.74:5555' not found"),
        })
        with mock.patch.object(inst.subprocess, "run", fake):
            with self.assertRaises(inst.InstallError) as ctx:
                inst.ensure_reachable(SERIAL)
        msg = str(ctx.exception)
        self.assertIn(SERIAL, msg)
        self.assertIn("not reachable", msg)
        self.assertIn("No route to host", msg)

    def test_offline_device_is_not_reachable(self):
        fake = FakeAdb({"get-state": done("offline\n")})
        with mock.patch.object(inst.subprocess, "run", fake):
            with self.assertRaises(inst.InstallError):
                inst.ensure_reachable(SERIAL)

    def test_missing_adb_fails_cleanly(self):
        with mock.patch.object(inst.subprocess, "run", side_effect=FileNotFoundError("adb")):
            with self.assertRaises(inst.InstallError) as ctx:
                inst.ensure_reachable(SERIAL)
        self.assertIn("adb not found", str(ctx.exception))

    def test_hung_adb_fails_cleanly(self):
        with mock.patch.object(inst.subprocess, "run",
                               side_effect=subprocess.TimeoutExpired(["adb"], inst.CONNECT_TIMEOUT)):
            with self.assertRaises(inst.InstallError) as ctx:
                inst.ensure_reachable(SERIAL)
        self.assertIn("timed out", str(ctx.exception))


class MainTest(unittest.TestCase):
    def _run_main(self, fake, argv, apk_exists=True):
        with mock.patch.object(inst.subprocess, "run", fake), \
                mock.patch.object(inst.Path, "exists", return_value=apk_exists):
            return inst.main(argv)

    def test_happy_path_installs_grants_launches_and_verifies(self):
        fake = FakeAdb({
            "get-state": done("device\n"),
            "install": done("Success\n"),
            "dumpsys package": done("      android.permission.RECORD_AUDIO: granted=true\n"),
            "am start": done("Starting: Intent { cmp=com.miko3.mode.voice/.MainActivity }\n"),
        })
        self.assertEqual(self._run_main(fake, ["--no-build"]), 0)
        joined = [" ".join(c) for c in fake.calls]
        install = next(i for i, c in enumerate(joined) if " install " in c)
        grant = next(i for i, c in enumerate(joined) if "pm grant" in c)
        start = next(i for i, c in enumerate(joined) if "am start" in c)
        self.assertLess(install, grant)
        self.assertLess(grant, start)
        self.assertFalse(any("build-mode-voice.py" in c for c in joined), "--no-build must not rebuild")

    def test_builds_first_by_default(self):
        fake = FakeAdb({
            "get-state": done("device\n"),
            "dumpsys package": done("android.permission.RECORD_AUDIO: granted=true\n"),
        })
        self.assertEqual(self._run_main(fake, []), 0)
        self.assertIn("build-mode-voice.py", " ".join(fake.calls[0]))

    def test_unreachable_device_never_installs(self):
        fake = FakeAdb({"get-state": done("", 1, "error: device not found")})
        with self.assertRaises(inst.InstallError):
            self._run_main(fake, ["--no-build"])
        self.assertFalse(any("install" in c for c in fake.calls))

    def test_missing_apk_fails_before_touching_the_device(self):
        fake = FakeAdb()
        with self.assertRaises(inst.InstallError) as ctx:
            self._run_main(fake, ["--no-build"], apk_exists=False)
        self.assertIn("not found", str(ctx.exception))
        self.assertEqual(fake.calls, [])

    def test_failed_install_stops_before_launch(self):
        fake = FakeAdb({
            "get-state": done("device\n"),
            "install": done("Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]\n", 1),
        })
        with self.assertRaises(inst.InstallError):
            self._run_main(fake, ["--no-build"])
        self.assertFalse(any("am" in c and "start" in c for c in fake.calls))

    def test_grant_not_reflected_fails(self):
        fake = FakeAdb({
            "get-state": done("device\n"),
            "dumpsys package": done("android.permission.RECORD_AUDIO: granted=false\n"),
        })
        with self.assertRaises(inst.InstallError) as ctx:
            self._run_main(fake, ["--no-build"])
        self.assertIn("RECORD_AUDIO", str(ctx.exception))

    def test_launch_error_fails(self):
        fake = FakeAdb({
            "get-state": done("device\n"),
            "dumpsys package": done("android.permission.RECORD_AUDIO: granted=true\n"),
            "am start": done("Error: Activity class {com.miko3.mode.voice/.MainActivity} does not exist.\n"),
        })
        with self.assertRaises(inst.InstallError):
            self._run_main(fake, ["--no-build"])


if __name__ == "__main__":
    unittest.main()
