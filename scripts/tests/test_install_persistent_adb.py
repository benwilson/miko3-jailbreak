#!/usr/bin/env python3
"""Tests for scripts/install-persistent-adb.py.

Covers the pure helpers (stopped-flag clearing, the arm decision), the
factory-mode precondition, transport targeting, the device commands the install
emits (APK placement and the adb key write), and the invariant that the script
never removes ServiceExam or MikoPlus. The device install itself is proved by the
plan's device proof, not here.
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


class DeviceCommandTest(unittest.TestCase):
    """The owner/mode the install writes are part of the contract, not incidental."""

    def _capture(self, fn, *args):
        cmds = []

        def fake_sh(cmd, check=True):
            cmds.append(cmd)
            # the adb_keys write is read back; answer that probe as a landed write
            out = "1\n" if cmd.startswith("wc -l") else ""
            return types.SimpleNamespace(stdout=out, stderr="", returncode=0)

        def fake_adb(*a, **k):
            cmds.append("adb " + " ".join(str(x) for x in a))
            return types.SimpleNamespace(stdout="", stderr="", returncode=0)

        with mock.patch.object(inst, "sh", fake_sh), mock.patch.object(inst, "adb", fake_adb):
            fn(*args)
        return cmds

    def test_place_apk_sets_expected_owner_and_modes(self):
        cmds = self._capture(inst.place_apk, "abc==")
        joined = "\n".join(cmds)
        self.assertIn("chmod 755", joined)
        self.assertIn("chmod 644", joined)
        self.assertIn("chown -R system:system", joined)

    def test_authorize_host_key_appends_rather_than_replaces(self):
        """Replacing the file would revoke every other host already authorized."""
        key = Path.home() / ".android" / "adbkey.pub"
        if not key.exists() or not key.read_text().strip():
            self.skipTest("no host adbkey.pub on this machine")
        cmds = self._capture(inst.authorize_host_key)
        joined = "\n".join(cmds)
        self.assertIn("grep -qxF", joined, "the key must be appended only when absent")
        self.assertNotIn("> " + inst.ADB_KEYS + ".tmp", joined,
                         "the previous implementation replaced the whole file")
        self.assertIn(f"chmod 640 {inst.ADB_KEYS}", joined)

    def test_agent_registered_probes_packages_xml_not_the_package_manager(self):
        """The arm path must work in factory mode, which has no package manager (KTD4)."""
        src = INSTALL_PY.read_text()
        self.assertIn("def agent_registered()", src)
        self.assertNotIn('sh(f"pm path {PKG}', src,
                         "a package-manager probe is unreachable from factory mode")


class ArmTest(unittest.TestCase):
    def test_registered_but_suppressed_clears_the_stopped_flag(self):
        pushed = []

        def fake_sh(cmd, check=True):
            if cmd.startswith("grep -o"):
                return types.SimpleNamespace(stdout='name="com.miko3.bootagent"\n',
                                             stderr="", returncode=0)
            if cmd.startswith("stat -c"):
                return types.SimpleNamespace(stdout="660\n", stderr="", returncode=0)
            return types.SimpleNamespace(stdout="", stderr="", returncode=0)

        def fake_adb(*args, **kwargs):
            if args and args[0] == "pull":
                Path(args[2]).write_text(RESTRICTIONS)
            if args and args[0] == "push":
                pushed.append(Path(args[1]).read_text())
            return types.SimpleNamespace(stdout="", stderr="", returncode=0)

        with mock.patch.object(inst, "sh", fake_sh), mock.patch.object(inst, "adb", fake_adb):
            rc = inst.arm()

        self.assertEqual(rc, 0)
        self.assertEqual(len(pushed), 1, "expected exactly one write-back")
        self.assertNotIn('name="com.miko3.bootagent" ceDataInode="123" stopped="true"', pushed[0])
        self.assertIn("com.example.root.serviceexam", pushed[0])

    def test_never_registered_routes_to_factory_mode_recovery(self):
        def fake_sh(cmd, check=True):
            return types.SimpleNamespace(stdout="", stderr="", returncode=0)

        with mock.patch.object(inst, "sh", fake_sh):
            with self.assertRaises(inst.InstallError) as ctx:
                inst.arm()
        self.assertIn("never registered", str(ctx.exception))


if __name__ == "__main__":
    unittest.main()
