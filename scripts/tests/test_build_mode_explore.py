#!/usr/bin/env python3
"""Tests for scripts/build-mode-explore.py (U4: mode app scaffold).

Covers the vendor-library precondition (a missing libmiko_drivers.so fails
with a message naming it and where it was looked for, before any toolchain
work, since DirectMotorDriver's SensorModule cannot load without it), asset
staging (the app's own assets/ when present, the shared certificate, never the
remote-control song or the settings stylesheet), and, when the Android
toolchain is installed, a real build whose APK must carry the driver library
under lib/arm64-v8a/, a manifest with INTERNET and CAMERA but no RECORD_AUDIO,
the network security config, and the mode's classes in the dex. The pinned
ONNX Runtime package (camera curiosity, KTD2) must fail loudly when missing or
tampered with, and its library, the detector and its vocabulary must ship.
"""
import hashlib
import importlib.util
import re
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock

REPO = Path(__file__).resolve().parents[2]
BUILD_PY = REPO / "scripts" / "build-mode-explore.py"
LAUNCHER_BUILD_PY = REPO / "scripts" / "build-custom-launcher.py"
VENDOR_LIB_DIR = REPO / "tools" / "serviceexam_jadx" / "resources" / "lib" / "arm64-v8a"


def load_build_module():
    spec = importlib.util.spec_from_file_location("build_mode_explore", BUILD_PY)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load build module from {BUILD_PY}")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


build = load_build_module()


class OnnxRuntimeTest(unittest.TestCase):
    def _fake_aar(self, cache):
        cache.mkdir(parents=True, exist_ok=True)
        aar = cache / build.ORT_AAR
        with zipfile.ZipFile(aar, "w") as z:
            z.writestr("classes.jar", b"jar")
            for name in build.ORT_LIBS:
                z.writestr(f"jni/{build.VENDOR_ABI}/{name}", b"so")
            z.writestr("jni/x86_64/libonnxruntime.so", b"other abi")
        return aar

    def test_missing_package_without_fetch_fails_clearly(self):
        with tempfile.TemporaryDirectory() as td:
            with self.assertRaises(build.BuildError) as ctx:
                build.onnxruntime(Path(td), fetch=False)
        self.assertIn(build.ORT_AAR, str(ctx.exception))

    def test_checksum_mismatch_fails_and_extracts_nothing(self):
        with tempfile.TemporaryDirectory() as td:
            self._fake_aar(Path(td))
            with self.assertRaises(build.BuildError) as ctx:
                build.onnxruntime(Path(td), fetch=False)
            self.assertIn("checksum", str(ctx.exception))
            self.assertFalse((Path(td) / f"onnxruntime-android-{build.ORT_VERSION}").exists())

    def test_verified_package_yields_jar_and_arm64_libs_only(self):
        with tempfile.TemporaryDirectory() as td:
            aar = self._fake_aar(Path(td))
            digest = hashlib.sha256(aar.read_bytes()).hexdigest()
            with mock.patch.object(build, "ORT_SHA256", digest):
                jar, libs = build.onnxruntime(Path(td), fetch=False)
            self.assertEqual(jar.read_bytes(), b"jar")
            self.assertEqual([abi for abi, _ in libs], [build.VENDOR_ABI] * len(build.ORT_LIBS))
            self.assertEqual(sorted(p.name for _, p in libs), sorted(build.ORT_LIBS))
            self.assertTrue(all(p.is_file() for _, p in libs))


class LayoutTest(unittest.TestCase):
    def test_paths_are_mode_explores_own(self):
        self.assertEqual(build.APP_DIR, REPO / "mode-explore")
        self.assertEqual(build.APK, REPO / "mode-explore" / "miko3-mode-explore.apk")
        self.assertEqual(build.KEYSTORE, REPO / "mode-explore" / "miko3-mode-explore.keystore")
        self.assertEqual(build.BUILD, REPO / "mode-explore" / "build")
        self.assertEqual(build.ASSETS, REPO / "mode-explore" / "assets")

    def test_keystore_identity_is_not_another_modes(self):
        self.assertEqual(build.KEYSTORE_ALIAS, "miko3modeexplore")
        self.assertIn("Explore", build.KEYSTORE_CN)


class VendorLibsTest(unittest.TestCase):
    def test_declares_the_motor_driver_lib(self):
        self.assertEqual(build.DRIVER_LIB, "libmiko_drivers.so")

    def test_present_returns_arm64_entry(self):
        libs = build.vendor_native_libs(VENDOR_LIB_DIR)
        self.assertEqual(len(libs), 1)
        abi, path = libs[0]
        self.assertEqual(abi, "arm64-v8a")
        self.assertEqual(Path(path).name, "libmiko_drivers.so")
        self.assertTrue(Path(path).is_file(), path)

    def test_missing_lib_fails_with_clear_message(self):
        """An absent .so is named, with where it was expected — never silently skipped."""
        with tempfile.TemporaryDirectory() as td:
            with self.assertRaises(build.BuildError) as ctx:
                build.vendor_native_libs(Path(td))
        msg = str(ctx.exception)
        self.assertIn("libmiko_drivers.so", msg)
        self.assertIn(str(td), msg)

    def test_missing_lib_fails_before_any_toolchain_work(self):
        """The precondition is checked first, so a missing lib never costs a javac run."""
        with tempfile.TemporaryDirectory() as td:
            with mock.patch.object(build.bc, "ensure_toolchain") as tc, \
                    mock.patch.object(build.bc, "build_apk") as ba:
                with self.assertRaises(build.BuildError):
                    build.build(lib_dir=Path(td), bootstrap=False)
        tc.assert_not_called()
        ba.assert_not_called()


class BuildIdTest(unittest.TestCase):
    """Meeting plan U1, step 3: one build id from build_common into both APKs' version
    names, so scripts/qa-*.py can tell whether launcher and mode-explore came from the
    same tree."""

    @staticmethod
    def _git(cwd, *args):
        subprocess.run(["git", *args], cwd=cwd, check=True, capture_output=True,
                       env={"GIT_AUTHOR_NAME": "t", "GIT_AUTHOR_EMAIL": "t@t", "GIT_COMMITTER_NAME": "t",
                            "GIT_COMMITTER_EMAIL": "t@t", "PATH": "/usr/bin:/bin:/usr/local/bin:/opt/homebrew/bin",
                            "HOME": cwd})

    def test_build_id_is_deterministic_and_shaped(self):
        a, b = build.bc.build_id(), build.bc.build_id()
        self.assertEqual(a, b)
        self.assertRegex(a, r"^(?:[0-9a-f]{12}(?:\+[0-9a-f]{8})?|nogit-[0-9]{8}T[0-9]{6}Z)$")

    def test_build_id_follows_the_commit_and_the_working_tree(self):
        with tempfile.TemporaryDirectory() as td:
            self._git(td, "init", "-q")
            f = Path(td) / "a.txt"
            f.write_text("one\n")
            self._git(td, "add", "a.txt")
            self._git(td, "commit", "-q", "-m", "one")
            clean = build.bc.build_id(td)
            head = subprocess.run(["git", "rev-parse", "--short=12", "HEAD"], cwd=td, capture_output=True,
                                  text=True, check=True).stdout.strip()
            self.assertEqual(clean, head)
            f.write_text("two\n")
            dirty = build.bc.build_id(td)
            self.assertNotEqual(dirty, clean)
            self.assertTrue(dirty.startswith(clean + "+"), dirty)
            self.assertEqual(dirty, build.bc.build_id(td), "the same dirty tree must give the same id")
            f.write_text("three\n")
            self.assertNotEqual(build.bc.build_id(td), dirty, "a different change is a different build")

    def test_build_id_without_git_is_still_an_id(self):
        with tempfile.TemporaryDirectory() as td:
            self.assertRegex(build.bc.build_id(td), r"^nogit-[0-9]{8}T[0-9]{6}Z$")

    def test_build_passes_the_shared_build_id_as_the_version_name(self):
        fake_jar = REPO / "scripts" / "build_common.py"
        with mock.patch.object(build.bc, "find_sdk", return_value=Path("/sdk")), \
                mock.patch.object(build.bc, "ensure_toolchain", return_value=("sdk", "bt", "jar", "javac", "kt")), \
                mock.patch.object(build.bc, "java_home", return_value="/jh"), \
                mock.patch.object(build, "onnxruntime", return_value=(fake_jar, [])), \
                mock.patch.object(build.bc, "build_apk") as ba:
            build.build(bootstrap=False)
        self.assertEqual(ba.call_count, 1)
        self.assertEqual(ba.call_args.kwargs.get("version_name"), build.bc.build_id())

    def test_launcher_build_stamps_the_same_build_id(self):
        src = LAUNCHER_BUILD_PY.read_text()
        self.assertRegex(src, r"version_name\s*=\s*bc\.build_id\(\)")
        self.assertRegex(BUILD_PY.read_text(), r"version_name\s*=\s*bc\.build_id\(\)")

    def test_link_step_overrides_the_manifest_version_name(self):
        with mock.patch.object(build.bc, "run") as run, mock.patch.object(build.bc.shutil, "copy"), \
                tempfile.TemporaryDirectory() as td:
            build.bc.link_and_pack("jar", Path("/bt"), Path("/m.xml"), Path(td), version_name="abc123+00000000")
        link = run.call_args_list[0].args[0]
        self.assertIn("--version-name", link)
        self.assertEqual(link[link.index("--version-name") + 1], "abc123+00000000")


class AssetsTest(unittest.TestCase):
    """build_common.stage_assets with this build's sources and exclude list."""

    def _shared(self, td):
        shared = Path(td) / "shared"
        shared.mkdir()
        for name in ("pico.min.css", "server.p12", "danger-zone.mp3"):
            (shared / name).write_bytes(b"x")
        return shared

    def test_app_assets_staged_when_present(self):
        with tempfile.TemporaryDirectory() as td:
            app = Path(td) / "app"
            app.mkdir()
            (app / "startle-1.wav").write_bytes(b"RIFF")
            out = build.bc.stage_assets([app, self._shared(td)], Path(td) / "build",
                                        exclude=build.SHARED_ASSETS_EXCLUDED)
            self.assertEqual(sorted(p.name for p in out.iterdir()), ["server.p12", "startle-1.wav"])

    def test_absent_app_assets_dir_is_fine(self):
        """U7 adds mode-explore/assets/; until then the build must not need it."""
        with tempfile.TemporaryDirectory() as td:
            out = build.bc.stage_assets([Path(td) / "no-such-dir", self._shared(td)], Path(td) / "build",
                                        exclude=build.SHARED_ASSETS_EXCLUDED)
            self.assertEqual(sorted(p.name for p in out.iterdir()), ["server.p12"])

    def test_real_shared_assets_keep_only_the_https_certificate(self):
        with tempfile.TemporaryDirectory() as td:
            out = build.bc.stage_assets([build.SHARED_ASSETS], Path(td),
                                        exclude=build.SHARED_ASSETS_EXCLUDED)
            names = {p.name for p in out.iterdir()}
        self.assertIn("server.p12", names)
        self.assertNotIn("danger-zone.mp3", names)
        self.assertNotIn("pico.min.css", names)


class ApkContentsTest(unittest.TestCase):
    """Builds into a scratch dir (never touches mode-explore/'s own APK or keystore)."""

    @classmethod
    def setUpClass(cls):
        try:
            build.bc.ensure_toolchain(build.bc.find_sdk(None), bootstrap=False)
        except build.BuildError as exc:
            raise unittest.SkipTest(f"Android toolchain unavailable: {exc}")
        cls._td = tempfile.TemporaryDirectory()
        td = Path(cls._td.name)
        cls.bt = build.bc.ensure_toolchain(build.bc.find_sdk(None), bootstrap=False)[1]
        cls.apk = build.build(bootstrap=False, apk_out=td / "explore.apk",
                              build_dir=td / "build", keystore=td / "test.keystore")
        with zipfile.ZipFile(cls.apk) as z:
            cls.names = set(z.namelist())

    @classmethod
    def tearDownClass(cls):
        cls._td.cleanup()

    def _manifest_tree(self):
        r = subprocess.run([str(self.bt / "aapt2"), "dump", "xmltree", "--file", "AndroidManifest.xml",
                            str(self.apk)], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stderr)
        return r.stdout

    def test_apk_carries_motor_driver_lib(self):
        self.assertIn("lib/arm64-v8a/libmiko_drivers.so", self.names)

    def test_apk_carries_detector_and_its_runtime(self):
        for name in build.ORT_LIBS:
            self.assertIn(f"lib/arm64-v8a/{name}", self.names)
        self.assertIn("assets/detector.onnx", self.names)
        self.assertIn("assets/vocabulary.txt", self.names)
        with zipfile.ZipFile(self.apk) as z:
            dex = z.read("classes.dex")
        self.assertTrue(b"Lai/onnxruntime/OrtSession;" in dex, "ONNX Runtime classes missing from classes.dex")

    def test_manifest_requests_internet_and_camera_only(self):
        tree = self._manifest_tree()
        self.assertIn("android.permission.INTERNET", tree)
        self.assertIn("android.permission.CAMERA", tree)
        self.assertNotIn("RECORD_AUDIO", tree)

    def test_manifest_declares_mode_app_and_main_activity(self):
        tree = self._manifest_tree()
        # aapt2 keeps the manifest's relative names; resolved against package=.
        self.assertIn('package="com.miko3.mode.explore"', tree)
        self.assertIn('".ModeApp"', tree)
        self.assertIn('".MainActivity"', tree)
        self.assertIn("android.intent.action.MAIN", tree)
        self.assertNotIn("android.intent.category.LAUNCHER", tree,
                         "launched by the custom launcher's explicit Intent, never from a stock app drawer")
        with zipfile.ZipFile(self.apk) as z:
            dex = z.read("classes.dex")
        for cls in ("ModeApp", "MainActivity", "ExploreState"):
            # assertTrue, not assertIn: a failing assertIn would print the whole dex.
            self.assertTrue(f"Lcom/miko3/mode/explore/{cls};".encode() in dex, f"{cls} missing from classes.dex")
        self.assertTrue(b"Lcom/miko3/shared/DirectMotorDriver;" in dex, "shared module missing from classes.dex")

    def test_manifest_carries_the_build_id(self):
        # aapt2 dumps attributes as name(0xid)="value".
        self.assertRegex(self._manifest_tree(), rf'versionName\(0x[0-9a-f]+\)="{re.escape(build.bc.build_id())}"')

    def test_apk_carries_network_security_config(self):
        self.assertIn("res/xml/network_security_config.xml", self.names)
        self.assertIn("networkSecurityConfig", self._manifest_tree())

    def test_apk_carries_https_certificate_only_from_shared(self):
        self.assertIn("assets/server.p12", self.names)
        self.assertNotIn("assets/danger-zone.mp3", self.names)
        self.assertNotIn("assets/pico.min.css", self.names)


if __name__ == "__main__":
    unittest.main()
