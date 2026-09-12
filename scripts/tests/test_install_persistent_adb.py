#!/usr/bin/env python3
"""Tests for scripts/install-persistent-adb.py.

Covers the pure registration helpers (stopped-flag clearing, packages.xml entry
build/insert) and the factory-mode precondition, plus the invariant that the
script never removes ServiceExam or MikoPlus. The device install itself is proved
by the plan's device proof, not here.
"""
import importlib.util
import tempfile
import types
import unittest
from pathlib import Path
from unittest import mock

REPO = Path(__file__).resolve().parents[2]
INSTALL_PY = REPO / "scripts" / "install-persistent-adb.py"


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load {path}")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


inst = load("install_persistent_adb", INSTALL_PY)

RESTRICTIONS = """<?xml version="1.0" encoding="utf-8"?>
<package-restrictions>
  <pkg name="com.example.root.serviceexam" />
  <pkg name="com.miko3.bootagent" ceDataInode="123" stopped="true" />
  <pkg name="com.miko.launcher_app" stopped="true" />
</package-restrictions>
"""

PACKAGES = """<?xml version="1.0" encoding="utf-8"?>
<packages>
    <package name="com.example.root.serviceexam" codePath="/data/app/x" version="92">
    </package>
</packages>
"""


class ClearStoppedTest(unittest.TestCase):
    def test_removes_stopped_from_target(self):
        new, n = inst.clear_stopped(RESTRICTIONS)
        self.assertEqual(n, 1)
        self.assertNotIn('name="com.miko3.bootagent" ceDataInode="123" stopped="true"', new)
        self.assertIn('name="com.miko3.bootagent" ceDataInode="123"', new)

    def test_leaves_other_packages_stopped(self):
        new, _ = inst.clear_stopped(RESTRICTIONS)
        self.assertIn('name="com.miko.launcher_app" stopped="true"', new)

    def test_noop_when_absent(self):
        xml = '<package-restrictions><pkg name="com.miko3.bootagent" /></package-restrictions>'
        new, n = inst.clear_stopped(xml)
        self.assertEqual(n, 0)
        self.assertEqual(new, xml)

    def test_only_target_package(self):
        xml = ('<r><pkg name="com.miko3.bootagent" stopped="true" />'
               '<pkg name="com.other.app" stopped="true" /></r>')
        new, n = inst.clear_stopped(xml)
        self.assertEqual(n, 1)
        self.assertIn('name="com.other.app" stopped="true"', new)


class ArmDecisionTest(unittest.TestCase):
    """KTD4: the two post-boot failures get different answers, not one silent no-op."""

    def test_registered_but_suppressed_clears_the_stopped_flag(self):
        self.assertEqual(inst.arm_decision(True), "clear-stopped")

    def test_never_registered_routes_to_factory_mode_recovery(self):
        self.assertEqual(inst.arm_decision(False), "recover")

    def test_synthesizing_a_package_entry_is_gone(self):
        """KTD4 forbids synthesizing: a <cert key=...> holds signature bytes, not a digest."""
        self.assertFalse(hasattr(inst, "build_package_entry"))
        self.assertFalse(hasattr(inst, "insert_package_entry"))


class NamingTest(unittest.TestCase):
    def test_suffix_shape(self):
        s = inst.make_suffix()
        self.assertTrue(s.endswith("=="))
        self.assertEqual(len(s), 22)

    def test_pkg_dir(self):
        self.assertEqual(inst.pkg_dir("abc=="), "/data/app/com.miko3.bootagent-abc==")


class PreconditionTest(unittest.TestCase):
    def _fake_sh(self, uid, bootmode):
        def fake(cmd, check=True):
            out = uid if cmd.startswith("id -u") else bootmode
            return types.SimpleNamespace(stdout=out + "\n", stderr="", returncode=0)
        return fake

    def test_rejects_non_root(self):
        with mock.patch.object(inst, "sh", self._fake_sh("2000", "factory")):
            with self.assertRaises(inst.InstallError) as ctx:
                inst.require_root_factory()
        self.assertIn("not a root session", str(ctx.exception))

    def test_rejects_non_factory(self):
        with mock.patch.object(inst, "sh", self._fake_sh("0", "normal")):
            with self.assertRaises(inst.InstallError) as ctx:
                inst.require_root_factory()
        self.assertIn("not in factory mode", str(ctx.exception))

    def test_accepts_root_factory(self):
        with mock.patch.object(inst, "sh", self._fake_sh("0", "factory")):
            self.assertEqual(inst.require_root_factory(), ("0", "factory"))


class BackupSystemStateTest(unittest.TestCase):
    """R9: install backs up every system-state file it modifies, adb_keys included."""

    def _run_backup(self, adb_keys_present, tmpdir):
        calls = []

        def fake_sh(cmd, check=True):
            calls.append(cmd)
            if cmd.startswith("["):  # the adb_keys presence probe
                return types.SimpleNamespace(
                    stdout=("yes" if adb_keys_present else "no") + "\n", stderr="", returncode=0)
            return types.SimpleNamespace(stdout="", stderr="", returncode=0)

        def fake_adb(*args, check=True):
            calls.append("adb " + " ".join(args))
            return types.SimpleNamespace(stdout="", stderr="", returncode=0)

        with mock.patch.object(inst, "sh", fake_sh), \
                mock.patch.object(inst, "adb", fake_adb), \
                mock.patch.object(inst, "BACKUP_ROOT", Path(tmpdir)):
            return inst.backup_system_state("20260912T000000Z"), calls

    def test_backs_up_adb_keys_when_present(self):
        with tempfile.TemporaryDirectory() as td:
            host_dir, calls = self._run_backup(True, td)
            self.assertFalse((host_dir / "adb_keys.absent").exists())
            self.assertTrue(any(c.startswith("adb pull " + inst.ADB_KEYS) for c in calls),
                            f"adb_keys was not pulled: {calls}")

    def test_records_adb_keys_absence(self):
        """R14: with no pre-install adb_keys, revert must delete it — so record absence."""
        with tempfile.TemporaryDirectory() as td:
            host_dir, calls = self._run_backup(False, td)
            self.assertTrue((host_dir / "adb_keys.absent").exists())
            self.assertFalse(any(c.startswith("adb pull " + inst.ADB_KEYS) for c in calls))


class TransportTargetingTest(unittest.TestCase):
    """KTD10: every device command names its transport."""

    def test_adb_names_the_usb_transport(self):
        with mock.patch.object(inst, "SERIAL", "MIKO3250XXM3Q0636CB"), \
                mock.patch.object(inst.subprocess, "run") as run_mock:
            run_mock.return_value = types.SimpleNamespace(stdout="", stderr="", returncode=0)
            inst.adb("shell", "id -u")
        cmd = run_mock.call_args[0][0]
        self.assertEqual(cmd[:3], ["adb", "-s", "MIKO3250XXM3Q0636CB"], cmd)

    def test_default_serial_is_the_documented_unit(self):
        self.assertEqual(inst.DEFAULT_USB_SERIAL, "MIKO3250XXM3Q0636CB")


class ProtectedPackagesTest(unittest.TestCase):
    def test_never_removes_protected(self):
        """R8: the script must not remove or rename ServiceExam or MikoPlus."""
        src = INSTALL_PY.read_text()
        self.assertIn("PROTECTED", src)
        for name in inst.PROTECTED:
            self.assertNotIn(f"rm -rf /data/app/{name}", src)
            self.assertNotIn(f"mv /data/app/{name}", src)


if __name__ == "__main__":
    unittest.main()
