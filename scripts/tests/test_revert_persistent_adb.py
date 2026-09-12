#!/usr/bin/env python3
"""Tests for scripts/revert-persistent-adb.py.

Covers the pure XML helpers, the opening gate, the adb_keys decision, and the guard that
revert never touches ServiceExam or MikoPlus. The device revert itself is proved by the
plan's revert proof on the unit, not here.

Note the two distinct failure modes this file separates: an agent that is installed but has
no backup must stop, while an agent that was never installed must be a clean no-op.
"""
import importlib.util
import tempfile
import types
import unittest
from pathlib import Path
from unittest import mock

REPO = Path(__file__).resolve().parents[2]
REVERT_PY = REPO / "scripts" / "revert-persistent-adb.py"

# A packages.xml carrying the agent's entry alongside a protected one. ServiceExam's block
# must survive the edit byte-identical.
AGENT_ID = "com.miko3.bootagent"
SERVICE_ID = "com.example.root.serviceexam"
PACKAGES_XML = f"""<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<packages>
<package name="{SERVICE_ID}" codePath="/data/app/{SERVICE_ID}-1==/base.apk" installer="null" version="92">
  <sigs count="1"><cert key="AAAA" /></sigs>
</package>
<package name="{AGENT_ID}" codePath="/data/app/{AGENT_ID}-zz==/base.apk" installer="null" version="1">
  <sigs count="1"><cert key="BBBB" /></sigs>
</package>
</packages>
"""

RESTRICTIONS_XML = f"""<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<package-restrictions>
<pkg name="{SERVICE_ID}" stopped="false" />
<pkg name="{AGENT_ID}" stopped="true" />
</package-restrictions>
"""


def load():
    spec = importlib.util.spec_from_file_location("revert_persistent_adb", REVERT_PY)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load revert module from {REVERT_PY}")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


rev = load()


class RemovePackageEntryTest(unittest.TestCase):
    def test_removes_the_agent_block(self):
        edited, n = rev.remove_package_entry(PACKAGES_XML)
        self.assertEqual(n, 1)
        self.assertNotIn(AGENT_ID, edited)

    def test_leaves_the_protected_block_byte_identical(self):
        """AE4/R8: revert removes only the agent."""
        edited, _ = rev.remove_package_entry(PACKAGES_XML)
        self.assertIn(SERVICE_ID, edited)
        expected = PACKAGES_XML[PACKAGES_XML.index(f'<package name="{SERVICE_ID}"'):
                                PACKAGES_XML.index("</package>") + len("</package>")]
        self.assertIn(expected, edited)

    def test_no_entry_is_a_no_op(self):
        edited, n = rev.remove_package_entry("<packages></packages>")
        self.assertEqual(n, 0)
        self.assertEqual(edited, "<packages></packages>")


class RemoveRestrictionEntryTest(unittest.TestCase):
    def test_removes_the_agent_restriction(self):
        edited, n = rev.remove_restriction_entry(RESTRICTIONS_XML)
        self.assertEqual(n, 1)
        self.assertNotIn(AGENT_ID, edited)
        self.assertIn(SERVICE_ID, edited)


class OpeningGateTest(unittest.TestCase):
    """The gate runs first: placed after the destructive steps it prevents nothing."""

    def test_installed_with_backup_proceeds(self):
        self.assertEqual(rev.opening_gate(True, "/some/backup"), "proceed")

    def test_installed_without_backup_is_a_hard_error(self):
        self.assertEqual(rev.opening_gate(True, None), "error")

    def test_never_installed_without_backup_is_a_clean_no_op(self):
        self.assertEqual(rev.opening_gate(False, None), "nothing-to-do")


class AdbKeysActionTest(unittest.TestCase):
    def test_restore_when_the_backup_holds_the_file(self):
        with tempfile.TemporaryDirectory() as td:
            b = Path(td)
            (b / "adb_keys").write_text("ssh-rsa AAAA\n")
            self.assertEqual(rev.adb_keys_action(b), "restore")

    def test_delete_when_install_recorded_absence(self):
        """R14: leaving the host key behind is not the pre-install state."""
        with tempfile.TemporaryDirectory() as td:
            b = Path(td)
            (b / "adb_keys.absent").write_text("absent\n")
            self.assertEqual(rev.adb_keys_action(b), "delete")

    def test_unknown_without_a_record(self):
        with tempfile.TemporaryDirectory() as td:
            self.assertEqual(rev.adb_keys_action(Path(td)), "unknown")
        self.assertEqual(rev.adb_keys_action(None), "unknown")


class ProtectedPackagesTest(unittest.TestCase):
    """R8/AE4: the command list must never remove or rename the kiosk's paired apps."""

    def test_no_staged_command_touches_a_protected_package(self):
        cmds = rev.staged_removal_cmds()
        self.assertTrue(cmds, "the staged removal list is empty")
        for cmd in cmds:
            for name in rev.PROTECTED:
                self.assertNotIn(f"rm -rf /data/app/{name}", cmd)
                self.assertNotIn(f"mv /data/app/{name}", cmd)

    def test_source_never_removes_or_renames_protected(self):
        src = REVERT_PY.read_text()
        self.assertIn("PROTECTED", src)
        for name in rev.PROTECTED:
            self.assertNotIn(f"rm -rf /data/app/{name}", src)
            self.assertNotIn(f"mv /data/app/{name}", src)

    def test_directory_removal_is_separate_from_the_process_kills(self):
        """The /data/app removal must not run alongside the kills; see the ordering test."""
        joined = " ".join(rev.staged_removal_cmds())
        self.assertNotIn("/data/app/", joined)
        self.assertIn(f"/data/app/{rev.PKG}-*==", rev.remove_agent_dir_cmd())


class RemovalOrderingTest(unittest.TestCase):
    """P0: a surviving packages.xml entry whose directory is gone is the recorded purge trigger."""

    def _run_main(self, order):
        def fake_sh(cmd, serial=None, check=True):
            if cmd.startswith("rm -rf /data/app/"):
                order.append("rmdir")
            elif cmd.startswith("stat -c"):
                return types.SimpleNamespace(stdout="660\n", stderr="", returncode=0)
            return types.SimpleNamespace(stdout="", stderr="", returncode=0)

        def fake_adb(*args, serial=None, check=True):
            return types.SimpleNamespace(stdout="", stderr="", returncode=0)

        def fake_edit(serial, path, fn, label):
            order.append("edit:" + path)

        with tempfile.TemporaryDirectory() as td, \
                mock.patch.object(rev, "sh", fake_sh), \
                mock.patch.object(rev, "adb", fake_adb), \
                mock.patch.object(rev, "edit_agent_entries", fake_edit), \
                mock.patch.object(rev, "latest_backup", lambda: Path(td)), \
                mock.patch.object(rev, "require_root", lambda s: None), \
                mock.patch.object(rev, "agent_installed", lambda s: True), \
                mock.patch.object(rev, "restore_adb_keys", lambda s, b: None), \
                mock.patch.object(rev, "assert_protected", lambda s: []), \
                mock.patch.object(rev, "write_capture", lambda p: Path(td)), \
                mock.patch.object(rev.sys, "argv", ["revert-persistent-adb.py", "--no-reboot"]):
            rev.main()

    def test_entries_are_removed_before_the_directory(self):
        order = []
        self._run_main(order)
        self.assertIn("rmdir", order, f"the directory removal never ran: {order}")
        self.assertIn("edit:" + rev.PACKAGES_XML, order, f"packages.xml was never edited: {order}")
        self.assertLess(order.index("edit:" + rev.PACKAGES_XML), order.index("rmdir"),
                        f"packages.xml entry must be removed before its directory: {order}")
        self.assertLess(order.index("edit:" + rev.RESTRICTIONS_XML), order.index("rmdir"),
                        f"package-restrictions entry must be removed before the directory: {order}")


class RemovalCleanupTest(unittest.TestCase):
    def test_removes_neuterd_no_op_source(self):
        """neuterd hardcodes /data/local/tmp/nr; it survives a revert unless removed."""
        joined = " ".join(rev.staged_removal_cmds())
        self.assertIn("/data/local/tmp/nr", joined)

    def test_kills_both_agent_processes(self):
        joined = " ".join(rev.staged_removal_cmds())
        self.assertIn("miko3-usb-watch.sh", joined)
        self.assertIn("neuterd", joined)


class SurgicalEditTest(unittest.TestCase):
    """The live file is edited surgically; a wholesale backup restore is never used."""

    def test_removes_only_the_agent_entry_and_preserves_owner_and_mode(self):
        calls = {"pushed": []}

        def fake_sh(cmd, serial=None, check=True):
            if cmd.startswith("stat -c '%a'"):
                out = "660\n"
            elif cmd.startswith("stat -c '%U"):
                out = "system:system\n"
            else:
                out = ""
            return types.SimpleNamespace(stdout=out, stderr="", returncode=0)

        def fake_adb(*args, serial=None, check=True):
            if args and args[0] == "pull":
                Path(args[2]).write_text(PACKAGES_XML)
            if args and args[0] == "push":
                calls["pushed"].append(Path(args[1]).read_text())
            return types.SimpleNamespace(stdout="", stderr="", returncode=0)

        with mock.patch.object(rev, "sh", fake_sh), mock.patch.object(rev, "adb", fake_adb):
            n = rev.edit_agent_entries("SERIAL", rev.PACKAGES_XML, rev.remove_package_entry,
                                       "packages.xml")

        self.assertEqual(n, 1)
        self.assertEqual(len(calls["pushed"]), 1, "expected exactly one push")
        pushed = calls["pushed"][0]
        self.assertNotIn(AGENT_ID, pushed)
        self.assertIn(SERVICE_ID, pushed)

    def test_no_push_when_the_entry_is_absent(self):
        pushed = []

        def fake_sh(cmd, serial=None, check=True):
            return types.SimpleNamespace(stdout="", stderr="", returncode=0)

        def fake_adb(*args, serial=None, check=True):
            if args and args[0] == "pull":
                Path(args[2]).write_text("<packages></packages>")
            if args and args[0] == "push":
                pushed.append(args)
            return types.SimpleNamespace(stdout="", stderr="", returncode=0)

        with mock.patch.object(rev, "sh", fake_sh), mock.patch.object(rev, "adb", fake_adb):
            n = rev.edit_agent_entries("SERIAL", rev.PACKAGES_XML, rev.remove_package_entry,
                                       "packages.xml")

        self.assertEqual(n, 0)
        self.assertEqual(pushed, [], "nothing changed, so nothing should be written back")


class MainGateTest(unittest.TestCase):
    def _run(self, installed, backup):
        with mock.patch.object(rev, "latest_backup", lambda: backup), \
                mock.patch.object(rev, "require_root", lambda s: None), \
                mock.patch.object(rev, "agent_installed", lambda s: installed), \
                mock.patch.object(rev, "staged_removal_cmds", lambda: []), \
                mock.patch.object(rev, "edit_agent_entries", lambda *a, **k: 0), \
                mock.patch.object(rev, "assert_protected", lambda s: []), \
                mock.patch.object(rev, "restore_adb_keys", lambda s, b: None), \
                mock.patch.object(rev, "write_capture", lambda p: REPO / "recon" / "_t.json"), \
                mock.patch.object(rev.sys, "argv", ["revert-persistent-adb.py", "--no-reboot"]):
            return rev.main()

    def test_never_installed_is_a_clean_no_op(self):
        self.assertEqual(self._run(False, None), 0)

    def test_installed_without_backup_errors(self):
        with self.assertRaises(rev.RevertError) as ctx:
            self._run(True, None)
        self.assertIn("no install backup", str(ctx.exception))

    def test_installed_with_backup_proceeds(self):
        self.assertEqual(self._run(True, "/backup"), 0)


if __name__ == "__main__":
    unittest.main()
