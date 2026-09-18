#!/usr/bin/env python3
"""Tests for scripts/build-mode-voice.py (U1: wake-word spike).

Covers the vendor-library precondition (a missing .so fails with a message
naming it, before any toolchain work), the vendored JNI surface (package and
native signatures must match ServiceExam's recognizer.WakeWord exactly, or the
library's JNI symbols will not bind), the copied model asset, and, when the
Android toolchain is installed, a real build whose APK must carry all three
vendor libraries under lib/arm64-v8a/ plus the model under assets/.
"""
import hashlib
import importlib.util
import re
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock

REPO = Path(__file__).resolve().parents[2]
BUILD_PY = REPO / "scripts" / "build-mode-voice.py"
VENDOR_ROOT = REPO / "tools" / "serviceexam_jadx"
VENDOR_LIB_DIR = VENDOR_ROOT / "resources" / "lib" / "arm64-v8a"
VENDOR_WAKEWORD = VENDOR_ROOT / "sources" / "recognizer" / "WakeWord.java"
VENDOR_MODEL = VENDOR_ROOT / "resources" / "assets" / "miko_wakeword_model.tflite"
OUR_WAKEWORD = REPO / "mode-voice" / "src" / "recognizer" / "WakeWord.java"
OUR_MODEL = REPO / "mode-voice" / "assets" / "miko_wakeword_model.tflite"

WAKEWORD_LIBS = (
    "libnative_wakeword_vad_lib.so",
    "libncnn.so",
    "libtensorflowlite_gpu_delegate.so",
)


def load_build_module():
    spec = importlib.util.spec_from_file_location("build_mode_voice", BUILD_PY)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load build module from {BUILD_PY}")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


build = load_build_module()


def _sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


class VendorLibsTest(unittest.TestCase):
    def test_declares_exactly_the_three_wakeword_libs(self):
        self.assertEqual(sorted(build.WAKEWORD_LIBS), sorted(WAKEWORD_LIBS))

    def test_all_present_returns_arm64_entries(self):
        libs = build.vendor_native_libs(VENDOR_LIB_DIR)
        self.assertEqual(sorted(Path(p).name for _, p in libs), sorted(WAKEWORD_LIBS))
        for abi, path in libs:
            self.assertEqual(abi, "arm64-v8a")
            self.assertTrue(Path(path).is_file(), path)

    def test_missing_lib_fails_with_clear_message(self):
        """A vendor .so absent from the extracted resources is named, with where it was expected."""
        with tempfile.TemporaryDirectory() as td:
            td = Path(td)
            for name in WAKEWORD_LIBS[:2]:
                (td / name).write_bytes(b"\x7fELF")
            with self.assertRaises(build.BuildError) as ctx:
                build.vendor_native_libs(td)
        msg = str(ctx.exception)
        self.assertIn("libtensorflowlite_gpu_delegate.so", msg)
        self.assertIn(str(td), msg)
        self.assertNotIn("libncnn.so", msg, "a present library must not be reported missing")

    def test_every_missing_lib_is_listed(self):
        with tempfile.TemporaryDirectory() as td:
            with self.assertRaises(build.BuildError) as ctx:
                build.vendor_native_libs(Path(td))
        for name in WAKEWORD_LIBS:
            self.assertIn(name, str(ctx.exception))

    def test_missing_lib_fails_before_any_toolchain_work(self):
        """The precondition is checked first, so a missing lib never costs a javac run."""
        with tempfile.TemporaryDirectory() as td:
            with mock.patch.object(build.bc, "ensure_toolchain") as tc, \
                    mock.patch.object(build.bc, "build_apk") as ba:
                with self.assertRaises(build.BuildError):
                    build.build(lib_dir=Path(td), bootstrap=False)
        tc.assert_not_called()
        ba.assert_not_called()


class VendoredSourceTest(unittest.TestCase):
    NATIVE_RE = re.compile(r"^\s*public native [^;]+;", re.M)

    def test_wakeword_lives_in_recognizer_package(self):
        src = OUR_WAKEWORD.read_text()
        self.assertRegex(src, r"(?m)^package recognizer;")
        self.assertRegex(src, r"public class WakeWord\b")
        self.assertTrue('System.loadLibrary("native_wakeword_vad_lib")' in src,
                        "WakeWord must load the vendor library in its static initializer")

    def test_native_signatures_match_vendor_verbatim(self):
        ours = sorted(m.strip() for m in self.NATIVE_RE.findall(OUR_WAKEWORD.read_text()))
        vendor = sorted(m.strip() for m in self.NATIVE_RE.findall(VENDOR_WAKEWORD.read_text()))
        self.assertTrue(vendor, "vendor WakeWord.java has no natives -- wrong path?")
        self.assertEqual(ours, vendor)

    def test_model_asset_is_the_vendor_live_model(self):
        """KTD7: the three-class model KeywordTask2 loads, byte-identical."""
        self.assertTrue(OUR_MODEL.is_file(), f"model asset missing at {OUR_MODEL}")
        self.assertEqual(_sha256(OUR_MODEL), _sha256(VENDOR_MODEL))


class ApkContentsTest(unittest.TestCase):
    """Builds into a scratch dir (never touches mode-voice/'s own APK or keystore)."""

    @classmethod
    def setUpClass(cls):
        try:
            build.bc.ensure_toolchain(build.bc.find_sdk(None), bootstrap=False)
        except build.BuildError as exc:
            raise unittest.SkipTest(f"Android toolchain unavailable: {exc}")
        cls._td = tempfile.TemporaryDirectory()
        td = Path(cls._td.name)
        cls.apk = build.build(bootstrap=False, apk_out=td / "voice.apk",
                              build_dir=td / "build", keystore=td / "test.keystore")
        with zipfile.ZipFile(cls.apk) as z:
            cls.names = set(z.namelist())

    @classmethod
    def tearDownClass(cls):
        cls._td.cleanup()

    def test_apk_carries_all_three_vendor_libs(self):
        for name in WAKEWORD_LIBS:
            self.assertIn(f"lib/arm64-v8a/{name}", self.names)

    def test_apk_carries_model_asset(self):
        self.assertIn("assets/miko_wakeword_model.tflite", self.names)

    def test_apk_has_dex(self):
        self.assertIn("classes.dex", self.names)


if __name__ == "__main__":
    unittest.main()
