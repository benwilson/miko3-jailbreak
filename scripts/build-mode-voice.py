#!/usr/bin/env python3
"""
build-mode-voice.py — build and sign the voice-conversation mode APK
(mode-voice/).

Same gradle-free pipeline as scripts/build-mode-remote-control.py, via
scripts/build_common.py: compiles this app's own src plus the shared module
(shared/src) into one classes.dex, and packages the shared module's vendored
assets alongside the app's own (the wake-word model, KTD7), minus the ones the
voice mode has no use for (SHARED_ASSETS_EXCLUDED).

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

VENDOR_ABI = bc.VENDOR_ABI
VENDOR_LIB_DIR = bc.VENDOR_LIB_DIR

# The wake-word library list and its missing-library check live in build_common
# (meeting plan U3): the launcher's ears session stages the same three files, and
# recognizer.WakeWord itself now lives in shared/src.
WAKEWORD_LIBS = bc.WAKEWORD_LIBS

# The remote-control mode's 3.6 MB song has no use in this APK. Everything else
# in shared/assets is needed: pico.min.css styles the settings page and
# server.p12 is the HTTPS listener's certificate (HttpsSupport).
SHARED_ASSETS_EXCLUDED = ("danger-zone.mp3",)


def vendor_native_libs(lib_dir=VENDOR_LIB_DIR):
    """Return [(abi, so_path), ...] for build_common's native_libs, or raise
    BuildError naming every missing library and the directory searched."""
    return bc.wakeword_native_libs(lib_dir, "mode-voice")


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
        asset_exclude=SHARED_ASSETS_EXCLUDED,
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
