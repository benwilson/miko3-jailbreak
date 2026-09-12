#!/usr/bin/env python3
"""Tests for scripts/build-bootagent.py and the agent source it builds.

Covers the pure build logic (toolchain failure message, timestamp normalization,
module loading) and the committed artifacts, plus the source-level ordering
invariant the payload depends on (neuterd before adb). The full build itself is
proved by the Verification Contract's build proof, not here.
"""
import importlib.util
import io
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock

REPO = Path(__file__).resolve().parents[2]
BUILD_PY = REPO / "scripts" / "build-bootagent.py"
ROOTOPS = REPO / "bootagent" / "src" / "com" / "miko3" / "bootagent" / "RootOps.java"
MANIFEST = REPO / "bootagent" / "AndroidManifest.xml"
APK = REPO / "bootagent" / "miko3-bootagent.apk"
NEUTERD = REPO / "bootagent" / "native" / "neuterd"


def load_build_module():
    spec = importlib.util.spec_from_file_location("build_bootagent", BUILD_PY)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load build module from {BUILD_PY}")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


build = load_build_module()


class ToolchainFailureTest(unittest.TestCase):
    def test_missing_toolchain_is_actionable(self):
        """A missing SDK/toolchain raises BuildError with an install hint, not a stack trace."""
        with self.assertRaises(build.BuildError) as ctx:
            build.ensure_toolchain(Path("/nonexistent/sdk"), bootstrap=False)
        msg = str(ctx.exception)
        self.assertIn("toolchain missing", msg)
        self.assertIn("brew install lld", msg)
        self.assertIn("android-commandlinetools", msg)

    def test_missing_jdk_is_reported_with_the_other_preconditions(self):
        """U3: a missing JDK fails up front, not at the first javac the build happens to call."""
        real_which = build.which

        def fake_which(name):
            return None if name in ("javac", "keytool") else real_which(name)

        with mock.patch.object(build, "which", side_effect=fake_which):
            with self.assertRaises(build.BuildError) as ctx:
                build.ensure_toolchain(None, bootstrap=False)
        self.assertIn("JDK", str(ctx.exception))

    def test_no_bootstrap_never_installs(self):
        """--no-bootstrap must not shell out to brew; it fails fast instead."""
        with mock.patch.object(build, "run") as run_mock:
            with self.assertRaises(build.BuildError):
                build.ensure_toolchain(None, bootstrap=False)
        run_mock.assert_not_called()


class NormalizeZipTest(unittest.TestCase):
    def test_normalize_sets_fixed_timestamp(self):
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w") as z:
            info = zipfile.ZipInfo("a.txt", date_time=(2026, 9, 12, 0, 32, 0))
            z.writestr(info, b"hello")
        with tempfile.TemporaryDirectory() as td:
            tmp = Path(td) / "_norm_test.zip"
            tmp.write_bytes(buf.getvalue())
            build.normalize_zip_timestamps(tmp)
            with zipfile.ZipFile(tmp) as z:
                names = z.namelist()
                self.assertEqual(names, ["a.txt"])
                self.assertEqual(z.read("a.txt"), b"hello")
                self.assertEqual(z.infolist()[0].date_time, (2020, 1, 1, 0, 0, 0))


class SourceInvariantTest(unittest.TestCase):
    def test_rootops_has_placeholder(self):
        """The build injects neuterd by replacing this placeholder; it must exist."""
        self.assertIn("@@NEUTERD_B64@@", ROOTOPS.read_text())

    def test_payload_orders_neuterd_before_adb(self):
        """KTD2: neuterd must start (and the shadow land) before adb is touched."""
        text = ROOTOPS.read_text()
        neuterd = text.index("neuterd")
        tcp = text.index("service.adb.tcp.port")
        usb = text.index("sys.usb.config mtp,adb")
        self.assertLess(neuterd, tcp, "neuterd must be materialized before the TCP property")
        self.assertLess(neuterd, usb, "neuterd must be materialized before the USB combo")

    def test_payload_restarts_adbd_unconditionally(self):
        """KTD7: setting the TCP property only moves a *fresh* adbd onto TCP.

        An adbd already running without the property never picks it up, so TCP 5555
        stays off for the whole boot unless the payload restarts adbd itself. The
        watcher's own conditional restart does not cover this, so the assertion is
        that a restart precedes the watcher's creation rather than merely existing.
        """
        text = ROOTOPS.read_text()
        usb = text.index("sys.usb.config mtp,adb")
        restart = text.index("ctl.restart adbd")
        watcher = text.index("<<'WEOF'")
        self.assertLess(usb, restart, "adbd must be restarted after the USB combo is set")
        self.assertLess(restart, watcher,
                        "the payload must restart adbd itself, not only inside the watcher loop")

    def test_su_piped_on_stdin_not_c_argument(self):
        """Miko's su has no -c; the payload must go on stdin."""
        text = ROOTOPS.read_text()
        self.assertIn('exec("/system/bin/su")', text)
        self.assertNotIn('su", "-c"', text)

    def test_manifest_declares_boot_receiver(self):
        text = MANIFEST.read_text()
        self.assertIn("com.miko3.bootagent", text)
        self.assertIn("BOOT_COMPLETED", text)
        self.assertIn(".BootReceiver", text)


class CommittedApkTest(unittest.TestCase):
    def test_apk_present(self):
        self.assertTrue(APK.exists(), f"committed APK missing at {APK}")

    @unittest.skipUnless(shutil.which("apksigner"), "apksigner not on PATH")
    def test_apk_verifies(self):
        r = subprocess.run(["apksigner", "verify", str(APK)],
                           capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)

    @unittest.skipUnless(shutil.which("aapt2"), "aapt2 not on PATH (Android build-tools)")
    def test_apk_declares_package_and_boot_receiver(self):
        """U3: the built APK carries the package id and the BOOT_COMPLETED receiver."""
        badging = subprocess.run(["aapt2", "dump", "badging", str(APK)],
                                 capture_output=True, text=True)
        self.assertEqual(badging.returncode, 0, badging.stdout + badging.stderr)
        self.assertIn("com.miko3.bootagent", badging.stdout)
        tree = subprocess.run(
            ["aapt2", "dump", "xmltree", "--file", "AndroidManifest.xml", str(APK)],
            capture_output=True, text=True)
        self.assertEqual(tree.returncode, 0, tree.stdout + tree.stderr)
        self.assertIn("BOOT_COMPLETED", tree.stdout)
        self.assertIn("BootReceiver", tree.stdout)

    @unittest.skipUnless(shutil.which("aapt2") and shutil.which("apksigner"),
                         "Android build-tools not on PATH; byte-stability is proved by the "
                         "Verification Contract's build proof instead")
    def test_rebuild_is_byte_stable(self):
        """U3: the committed keystore plus normalized zip timestamps reproduce the APK.

        The build runs against a scratch COPY of the tree, so a failed rebuild can never
        overwrite the committed artifact.
        """
        before = APK.read_bytes()
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            (root / "scripts").mkdir()
            shutil.copytree(REPO / "bootagent", root / "bootagent")
            shutil.copy2(BUILD_PY, root / "scripts" / "build-bootagent.py")
            r = subprocess.run([sys.executable, str(root / "scripts" / "build-bootagent.py")],
                               capture_output=True, text=True, cwd=str(root))
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            rebuilt = (root / "bootagent" / "miko3-bootagent.apk").read_bytes()
        self.assertEqual(rebuilt, before, "rebuild produced a different APK")
        self.assertEqual(APK.read_bytes(), before, "the committed APK must not be modified")


class NeuterdBinaryTest(unittest.TestCase):
    def test_committed_neuterd_is_aarch64_elf(self):
        """U2: the daemon is a 64-bit AArch64 ELF — the only shape this unit can exec."""
        data = NEUTERD.read_bytes()
        self.assertEqual(data[:4], b"\x7fELF", "neuterd is not an ELF")
        self.assertEqual(data[4], 2, "neuterd is not a 64-bit ELF")
        machine = int.from_bytes(data[18:20], "little")
        self.assertEqual(machine, 0xB7, f"e_machine={machine:#x} is not AArch64 (0xB7)")


if __name__ == "__main__":
    unittest.main()
