#!/usr/bin/env python3
"""
build-mode-remote-control.py — build and sign the remote-control/telepresence
mode APK (mode-remote-control/).

Same gradle-free pipeline as scripts/build-custom-launcher.py, via
scripts/build_common.py: compiles this app's own src plus the shared
module (shared/src) into one classes.dex per APK (KTD2), and packages the
shared module's vendored assets (e.g. pico.min.css) alongside the app's
own. See mode-remote-control/README.md.

Usage:
  python3 scripts/build-mode-remote-control.py
  python3 scripts/build-mode-remote-control.py --no-bootstrap
  python3 scripts/build-mode-remote-control.py --sdk /path/to/sdk

Dependencies: python3, Homebrew (for bootstrap), a JDK (javac/keytool).
"""
import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build_common as bc

REPO = Path(__file__).resolve().parent.parent
APP_DIR = REPO / "mode-remote-control"
SHARED_DIR = REPO / "shared"
SRC = APP_DIR / "src"
SHARED_SRC = SHARED_DIR / "src"
SHARED_ASSETS = SHARED_DIR / "assets"
MANIFEST = APP_DIR / "AndroidManifest.xml"
KEYSTORE = APP_DIR / "miko3-mode-remote-control.keystore"
APK = APP_DIR / "miko3-mode-remote-control.apk"
BUILD = APP_DIR / "build"

KEYSTORE_ALIAS = "miko3moderemotecontrol"
KEYSTORE_PASS = "miko3moderemotecontrol"
KEYSTORE_CN = "Miko3 Remote Control Mode"


def main():
    ap = argparse.ArgumentParser(description="Build and sign the remote-control/telepresence mode APK.")
    ap.add_argument("--sdk", help="Android SDK root (default: auto-detect)")
    ap.add_argument("--no-bootstrap", action="store_true",
                    help="fail if the toolchain is missing instead of installing it")
    args = ap.parse_args()

    if not MANIFEST.exists():
        raise bc.BuildError(f"!! {MANIFEST} not found — build mode-remote-control/ scaffold first")

    sdk = bc.find_sdk(args.sdk)
    sdk, bt, android_jar, javac, keytool = bc.ensure_toolchain(sdk, not args.no_bootstrap)
    jh = bc.java_home()
    bc.build_apk(
        src_dirs=[SRC, SHARED_SRC],
        manifest=MANIFEST,
        android_jar=android_jar, javac=javac, bt=bt, keytool=keytool, java_home_dir=jh,
        build_dir=BUILD,
        keystore=KEYSTORE, keystore_alias=KEYSTORE_ALIAS, keystore_pass=KEYSTORE_PASS,
        keystore_cn=KEYSTORE_CN,
        apk_out=APK,
        asset_sources=[SHARED_ASSETS],
    )
    print(f"\n== 4/4 BUILT: {APK.relative_to(REPO)} ({APK.stat().st_size} bytes) ==")
    return 0


if __name__ == "__main__":
    sys.exit(main())
