#!/usr/bin/env python3
"""Tests for scripts/install-persistent-adb.py.

Covers the pure registration helpers (stopped-flag clearing, packages.xml entry
build/insert) and the factory-mode precondition, plus the invariant that the
script never removes ServiceExam or MikoPlus. The device install itself is proved
by the plan's device proof, not here.
"""
import importlib.util
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


class PackageEntryTest(unittest.TestCase):
    def test_entry_has_fields(self):
        entry = inst.build_package_entry("com.miko3.bootagent", "/data/app/x", "AABB")
        self.assertIn('name="com.miko3.bootagent"', entry)
        self.assertIn('codePath="/data/app/x"', entry)
        self.assertIn('<cert index="0" key="AABB" />', entry)

    def test_insert_once(self):
        entry = inst.build_package_entry("com.miko3.bootagent", "/data/app/x", "AABB")
        new, n = inst.insert_package_entry(PACKAGES, entry)
        self.assertEqual(n, 1)
        self.assertIn('name="com.miko3.bootagent"', new)
        self.assertTrue(new.index("com.miko3.bootagent") < new.index("</packages>"))

    def test_insert_idempotent(self):
        entry = inst.build_package_entry("com.miko3.bootagent", "/data/app/x", "AABB")
        once, _ = inst.insert_package_entry(PACKAGES, entry)
        twice, n = inst.insert_package_entry(once, entry)
        self.assertEqual(n, 0)
        self.assertEqual(once, twice)


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
