#!/usr/bin/env python3
"""
build-mode-explore.py — build and sign the explore mode APK (mode-explore/).

Same gradle-free pipeline as scripts/build-mode-voice.py, via
scripts/build_common.py: compiles this app's own src plus the shared module
(shared/src) into one classes.dex, and packages the app's own assets
(mode-explore/assets/, the startle sounds, when present) alongside the shared
module's vendored ones, minus the ones the explore mode has no use for
(SHARED_ASSETS_EXCLUDED).

The vendor's libmiko_drivers.so is bundled as this app's own
lib/arm64-v8a/ entry, as the remote-control build does: DirectMotorDriver's
SensorModule-based UART I/O System.loadLibrary()s it (the /system/lib64 copy
is out of reach behind linker namespace isolation). Unlike the remote-control
build, a missing library fails the build up front: without it the mode can
neither drive nor read the sensors it needs to drive safely.

Usage:
  python3 scripts/build-mode-explore.py
  python3 scripts/build-mode-explore.py --no-bootstrap
  python3 scripts/build-mode-explore.py --sdk /path/to/sdk

Dependencies: python3, Homebrew (for bootstrap), a JDK (javac/keytool).
"""
import zipfile
import urllib.request
import hashlib
import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build_common as bc

BuildError = bc.BuildError

REPO = Path(__file__).resolve().parent.parent
APP_DIR = REPO / "mode-explore"
SHARED_DIR = REPO / "shared"
SRC = APP_DIR / "src"
RES = APP_DIR / "res"
ASSETS = APP_DIR / "assets"
SHARED_SRC = SHARED_DIR / "src"
SHARED_ASSETS = SHARED_DIR / "assets"
MANIFEST = APP_DIR / "AndroidManifest.xml"
KEYSTORE = APP_DIR / "miko3-mode-explore.keystore"
APK = APP_DIR / "miko3-mode-explore.apk"
BUILD = APP_DIR / "build"

KEYSTORE_ALIAS = "miko3modeexplore"
KEYSTORE_PASS = "miko3modeexplore"
KEYSTORE_CN = "Miko3 Explore Mode"

VENDOR_ABI = "arm64-v8a"
VENDOR_LIB_DIR = REPO / "tools" / "serviceexam_jadx" / "resources" / "lib" / VENDOR_ABI
DRIVER_LIB = "libmiko_drivers.so"
# ONNX Runtime for the YOLOE detector (mode-explore/assets/detector.onnx): the
# prebuilt Android package from Maven Central, pinned and checksummed, cached in
# the gitignored tools/third_party/ (too large for git: ~33 MB of arm64 .so).
ORT_VERSION = "1.30.0"
ORT_AAR = f"onnxruntime-android-{ORT_VERSION}.aar"
ORT_URL = ("https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/"
           f"{ORT_VERSION}/{ORT_AAR}")
ORT_SHA256 = "e7fb945e402205f6db858d65bb78d2bdb0812317b383976c9e3bceb4862c73f1"
ORT_CACHE = REPO / "tools" / "third_party"
ORT_LIBS = ("libonnxruntime.so", "libonnxruntime4j_jni.so")

# The remote-control mode's 3.6 MB song and the settings-page stylesheet have
# no use in this APK (it serves no settings page). server.p12 is kept: it is
# the HTTPS listener's certificate (HttpsSupport).
SHARED_ASSETS_EXCLUDED = ("danger-zone.mp3", "pico.min.css")


def vendor_native_libs(lib_dir=VENDOR_LIB_DIR):
    """Return [(abi, so_path)] for build_common's native_libs, or raise
    BuildError naming the missing library and the directory searched."""
    lib = Path(lib_dir) / DRIVER_LIB
    if not lib.is_file():
        raise BuildError(
            f"!! vendor motor-driver library missing: {lib}\n"
            "   it comes from ServiceExam's APK (lib/arm64-v8a/); re-extract it into "
            "tools/serviceexam_jadx/resources/ (jadx) before building mode-explore.\n"
            "   Without it DirectMotorDriver cannot open the UART, so the mode could "
            "neither drive nor read its sensors.")
    return [(VENDOR_ABI, lib)]


def onnxruntime(cache=ORT_CACHE, fetch=True):
    """Return (classes_jar, [(abi, so), ...]) from the pinned ONNX Runtime AAR,
    downloading it into cache on first use and verifying its SHA-256."""
    cache = Path(cache)
    aar = cache / ORT_AAR
    if not aar.is_file():
        if not fetch:
            raise BuildError(f"!! {aar} missing and fetching is off")
        cache.mkdir(parents=True, exist_ok=True)
        print(f"== fetching {ORT_URL} ==")
        tmp = aar.with_suffix(".part")
        urllib.request.urlretrieve(ORT_URL, tmp)
        tmp.rename(aar)
    digest = hashlib.sha256(aar.read_bytes()).hexdigest()
    if digest != ORT_SHA256:
        raise BuildError(f"!! {aar} checksum mismatch: {digest} (expected {ORT_SHA256}); "
                         "delete it and rebuild")
    out = cache / f"onnxruntime-android-{ORT_VERSION}"
    if not (out / "classes.jar").is_file():
        with zipfile.ZipFile(aar) as z:
            z.extract("classes.jar", out)
            for name in ORT_LIBS:
                z.extract(f"jni/{VENDOR_ABI}/{name}", out)
    return out / "classes.jar", [(VENDOR_ABI, out / "jni" / VENDOR_ABI / n) for n in ORT_LIBS]


def build(sdk=None, bootstrap=True, apk_out=APK, build_dir=BUILD, keystore=KEYSTORE,
          lib_dir=VENDOR_LIB_DIR):
    """Check preconditions, then run the shared pipeline. Returns the APK path."""
    native_libs = vendor_native_libs(lib_dir)
    ort_jar, ort_libs = onnxruntime()
    native_libs = native_libs + ort_libs
    if not MANIFEST.exists():
        raise BuildError(f"!! {MANIFEST} not found — build mode-explore/ scaffold first")

    sdk = bc.find_sdk(sdk)
    sdk, bt, android_jar, javac, keytool = bc.ensure_toolchain(sdk, bootstrap)
    jh = bc.java_home()
    bc.build_apk(
        src_dirs=[SRC, SHARED_SRC],
        manifest=MANIFEST,
        android_jar=android_jar, javac=javac, bt=bt, keytool=keytool, java_home_dir=jh,
        build_dir=Path(build_dir),
        keystore=Path(keystore), keystore_alias=KEYSTORE_ALIAS, keystore_pass=KEYSTORE_PASS,
        keystore_cn=KEYSTORE_CN,
        apk_out=Path(apk_out),
        # stage_assets skips a source that does not exist, so ASSETS is optional.
        asset_sources=[ASSETS, SHARED_ASSETS],
        asset_exclude=SHARED_ASSETS_EXCLUDED,
        res_dir=RES,
        native_libs=native_libs,
        jars=[ort_jar],
    )
    return Path(apk_out)


def main():
    ap = argparse.ArgumentParser(description="Build and sign the explore mode APK.")
    ap.add_argument("--sdk", help="Android SDK root (default: auto-detect)")
    ap.add_argument("--no-bootstrap", action="store_true",
                    help="fail if the toolchain is missing instead of installing it")
    args = ap.parse_args()

    apk = build(sdk=args.sdk, bootstrap=not args.no_bootstrap)
    print(f"\n== 4/4 BUILT: {apk.relative_to(REPO)} ({apk.stat().st_size} bytes) ==")
    return 0


if __name__ == "__main__":
    sys.exit(main())
