#!/usr/bin/env python3
"""
build-mode-voice.py — build and sign the voice-conversation mode APK
(mode-voice/).

Same gradle-free pipeline as scripts/build-mode-remote-control.py, via
scripts/build_common.py: compiles this app's own src plus the shared module
(shared/src) into one classes.dex, and packages the shared module's vendored
assets alongside the app's own (the wake-word model, KTD7).

The vendor's wake-word engine ships inside this APK (KTD7): recognizer.WakeWord
binds its JNI symbols by class name, and its three native libraries are bundled
as this app's own lib/arm64-v8a/ entries from tools/serviceexam_jadx's extracted
resources. A missing library fails the build up front rather than producing an
APK whose System.loadLibrary() dies on the robot.

Usage:
  python3 scripts/build-mode-voice.py
  python3 scripts/build-mode-voice.py --no-bootstrap
  python3 scripts/build-mode-voice.py --sdk /path/to/sdk

Dependencies: python3, Homebrew (for bootstrap), a JDK (javac/keytool).
"""
import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build_common as bc

BuildError = bc.BuildError

REPO = Path(__file__).resolve().parent.parent
APP_DIR = REPO / "mode-voice"
SHARED_DIR = REPO / "shared"
SRC = APP_DIR / "src"
RES = APP_DIR / "res"
ASSETS = APP_DIR / "assets"
SHARED_SRC = SHARED_DIR / "src"
SHARED_ASSETS = SHARED_DIR / "assets"
MANIFEST = APP_DIR / "AndroidManifest.xml"
KEYSTORE = APP_DIR / "miko3-mode-voice.keystore"
APK = APP_DIR / "miko3-mode-voice.apk"
BUILD = APP_DIR / "build"

KEYSTORE_ALIAS = "miko3modevoice"
KEYSTORE_PASS = "miko3modevoice"
KEYSTORE_CN = "Miko3 Voice Mode"

VENDOR_ABI = "arm64-v8a"
VENDOR_LIB_DIR = REPO / "tools" / "serviceexam_jadx" / "resources" / "lib" / VENDOR_ABI

# libnative_wakeword_vad_lib.so is what recognizer.WakeWord loads; it links
# libncnn.so directly (readelf NEEDED) and the TFLite GPU delegate is dlopen'd
# by the engine at init, so all three ship together.
WAKEWORD_LIBS = (
    "libnative_wakeword_vad_lib.so",
    "libncnn.so",
    "libtensorflowlite_gpu_delegate.so",
)


def vendor_native_libs(lib_dir=VENDOR_LIB_DIR):
    """Return [(abi, so_path), ...] for build_common's native_libs, or raise
    BuildError naming every missing library and the directory searched."""
    lib_dir = Path(lib_dir)
    missing = [name for name in WAKEWORD_LIBS if not (lib_dir / name).is_file()]
    if missing:
        raise BuildError(
            f"!! vendor wake-word librar{'y' if len(missing) == 1 else 'ies'} missing from "
            f"{lib_dir}: {', '.join(missing)}\n"
            "   these come from ServiceExam's APK (lib/arm64-v8a/); re-extract it into "
            "tools/serviceexam_jadx/resources/ (jadx) before building mode-voice")
    return [(VENDOR_ABI, lib_dir / name) for name in WAKEWORD_LIBS]


def build(sdk=None, bootstrap=True, apk_out=APK, build_dir=BUILD, keystore=KEYSTORE,
          lib_dir=VENDOR_LIB_DIR):
    """Check preconditions, then run the shared pipeline. Returns the APK path."""
    native_libs = vendor_native_libs(lib_dir)
    if not MANIFEST.exists():
        raise BuildError(f"!! {MANIFEST} not found — build mode-voice/ scaffold first")

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
        asset_sources=[ASSETS, SHARED_ASSETS],
        res_dir=RES,
        native_libs=native_libs,
    )
    return Path(apk_out)


def main():
    ap = argparse.ArgumentParser(description="Build and sign the voice-conversation mode APK.")
    ap.add_argument("--sdk", help="Android SDK root (default: auto-detect)")
    ap.add_argument("--no-bootstrap", action="store_true",
                    help="fail if the toolchain is missing instead of installing it")
    args = ap.parse_args()

    apk = build(sdk=args.sdk, bootstrap=not args.no_bootstrap)
    print(f"\n== 4/4 BUILT: {apk.relative_to(REPO)} ({apk.stat().st_size} bytes) ==")
    return 0


if __name__ == "__main__":
    sys.exit(main())
