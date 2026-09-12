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
import unittest
import zipfile
from pathlib import Path
from unittest import mock

REPO = Path(__file__).resolve().parents[2]
BUILD_PY = REPO / "scripts" / "build-bootagent.py"
ROOTOPS = REPO / "bootagent" / "src" / "com" / "miko3" / "bootagent" / "RootOps.java"
MANIFEST = REPO / "bootagent" / "AndroidManifest.xml"
APK = REPO / "bootagent" / "miko3-bootagent.apk"


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
        tmp = Path("/tmp/_norm_test.zip")
        tmp.write_bytes(buf.getvalue())
        try:
            build.normalize_zip_timestamps(tmp)
            with zipfile.ZipFile(tmp) as z:
                names = z.namelist()
                self.assertEqual(names, ["a.txt"])
                self.assertEqual(z.read("a.txt"), b"hello")
                self.assertEqual(z.infolist()[0].date_time, (2020, 1, 1, 0, 0, 0))
        finally:
            tmp.unlink(missing_ok=True)


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


if __name__ == "__main__":
    unittest.main()
