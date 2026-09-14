#!/usr/bin/env python3
"""
build-custom-launcher.py — build and sign the Miko 3 custom launcher APK.

Proof-of-concept HOME replacement: shows basic device info on-screen and
serves the same info as a read-only web page over Wi-Fi, plus a minimal
Wi-Fi connect/disconnect/forget UI. See launcher/README.md.

Same gradle-free pipeline as scripts/build-bootagent.py (javac -> d8 ->
aapt2 link -> zipalign -> apksigner), minus the native-daemon step this app
doesn't need — factored into scripts/build_common.py and shared with
scripts/build-mode-remote-control.py. Also compiles the shared module
(shared/src) into this APK's own classes.dex (KTD2) and packages its
vendored assets (e.g. pico.min.css) alongside the launcher's own.

Usage:
  python3 scripts/build-custom-launcher.py
  python3 scripts/build-custom-launcher.py --no-bootstrap
  python3 scripts/build-custom-launcher.py --sdk /path/to/sdk

Dependencies: python3, Homebrew (for bootstrap), a JDK (javac/keytool).
"""
import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build_common as bc

REPO = Path(__file__).resolve().parent.parent
APP_DIR = REPO / "launcher"
SHARED_DIR = REPO / "shared"
SRC = APP_DIR / "src"
RES = APP_DIR / "res"
SHARED_SRC = SHARED_DIR / "src"
SHARED_ASSETS = SHARED_DIR / "assets"
MANIFEST = APP_DIR / "AndroidManifest.xml"
KEYSTORE = APP_DIR / "miko3-launcher.keystore"
APK = APP_DIR / "miko3-launcher.apk"
BUILD = APP_DIR / "build"

KEYSTORE_ALIAS = "miko3launcher"
KEYSTORE_PASS = "miko3launcher"
KEYSTORE_CN = "Miko3 Custom Launcher"


def main():
    ap = argparse.ArgumentParser(description="Build and sign the Miko 3 custom launcher APK.")
    ap.add_argument("--sdk", help="Android SDK root (default: auto-detect)")
    ap.add_argument("--no-bootstrap", action="store_true",
                    help="fail if the toolchain is missing instead of installing it")
    args = ap.parse_args()

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
        res_dir=RES,
    )
    print(f"\n== 4/4 BUILT: {APK.relative_to(REPO)} ({APK.stat().st_size} bytes) ==")
    return 0


if __name__ == "__main__":
    sys.exit(main())
