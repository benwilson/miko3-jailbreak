#!/usr/bin/env python3
"""Tests for scripts/install-mode-explore.py (U4).

Covers the device commands the install emits and their order (install, then
launch; the explore mode has no runtime permission to grant), the default
serial and package, transport targeting (every adb call names the serial),
and the clean failures: no reachable device, no adb on PATH, a hung adb, a
missing APK. The install on the robot itself is the plan's on-device
scenario, not this file.
"""
import importlib.util
import subprocess
import types
import unittest
from pathlib import Path
from unittest import mock

REPO = Path(__file__).resolve().parents[2]
INSTALL_PY = REPO / "scripts" / "install-mode-explore.py"


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load {path}")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


inst = load("install_mode_explore", INSTALL_PY)

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

    def test_package_matches_the_mode_registry(self):
        self.assertEqual(inst.PKG, "com.miko3.mode.explore")

    def test_component_is_the_mode_activity(self):
        self.assertEqual(inst.COMPONENT, "com.miko3.mode.explore/.MainActivity")

    def test_apk_and_build_script_are_explores(self):
        self.assertEqual(inst.APK, REPO / "mode-explore" / "miko3-mode-explore.apk")
        self.assertEqual(inst.BUILD_PY, REPO / "scripts" / "build-mode-explore.py")

    def test_install_then_launch_no_grant(self):
        cmds = inst.install_commands(SERIAL, Path("/x/explore.apk"))
        self.assertEqual(cmds, [
            ["adb", "-s", SERIAL, "install", "-r", "-t", "/x/explore.apk"],
            ["adb", "-s", SERIAL, "shell", "am", "start", "-n", "com.miko3.mode.explore/.MainActivity"],
        ])

    def test_every_adb_call_names_the_serial(self):
        for cmd in inst.install_commands("10.0.0.9:5555", Path("/x/explore.apk")):
            self.assertEqual(cmd[:3], ["adb", "-s", "10.0.0.9:5555"])


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

    def test_happy_path_installs_then_launches(self):
        fake = FakeAdb({
            "get-state": done("device\n"),
            "install": done("Success\n"),
            "am start": done("Starting: Intent { cmp=com.miko3.mode.explore/.MainActivity }\n"),
        })
        self.assertEqual(self._run_main(fake, ["--no-build"]), 0)
        joined = [" ".join(c) for c in fake.calls]
        install = next(i for i, c in enumerate(joined) if " install " in c)
        start = next(i for i, c in enumerate(joined) if "am start" in c)
        self.assertLess(install, start)
        self.assertFalse(any("pm grant" in c for c in joined), "the explore mode needs no runtime permission")
        self.assertFalse(any("build-mode-explore.py" in c for c in joined), "--no-build must not rebuild")

    def test_builds_first_by_default(self):
        fake = FakeAdb({"get-state": done("device\n")})
        self.assertEqual(self._run_main(fake, []), 0)
        self.assertIn("build-mode-explore.py", " ".join(fake.calls[0]))

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

    def test_launch_error_fails(self):
        fake = FakeAdb({
            "get-state": done("device\n"),
            "am start": done("Error: Activity class {com.miko3.mode.explore/.MainActivity} does not exist.\n"),
        })
        with self.assertRaises(inst.InstallError):
            self._run_main(fake, ["--no-build"])


if __name__ == "__main__":
    unittest.main()
