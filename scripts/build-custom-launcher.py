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

The robot's voice (voice plan U5, KTD3/KTD4): the launcher's SpeechService
runs a Piper voice through sherpa-onnx. The build downloads sherpa-onnx's
pinned, checksummed Android release into the gitignored tools/third_party/
(its Java API jar, and its arm64 libsherpa-onnx-jni.so plus the
libonnxruntime.so that library was built against) and bundles them. The voice
itself is staged into the APK's assets/voice/ as model.onnx, tokens.txt and
espeak-ng-data/, plus a stamp.txt the launcher uses to know when to copy it
out again: from launcher/assets/voice/ once the trained voice is committed
there (U4), else from sherpa-onnx's stock vits-piper-en_US-lessac-medium,
downloaded into tools/third_party/ and never committed.

Usage:
  python3 scripts/build-custom-launcher.py
  python3 scripts/build-custom-launcher.py --no-bootstrap
  python3 scripts/build-custom-launcher.py --sdk /path/to/sdk

Dependencies: python3, Homebrew (for bootstrap), a JDK (javac/keytool).
"""
import argparse
import hashlib
import shutil
import sys
import tarfile
import tempfile
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build_common as bc

BuildError = bc.BuildError

REPO = Path(__file__).resolve().parent.parent
APP_DIR = REPO / "launcher"
SHARED_DIR = REPO / "shared"
SRC = APP_DIR / "src"
RES = APP_DIR / "res"
SHARED_SRC = SHARED_DIR / "src"
SHARED_ASSETS = SHARED_DIR / "assets"
LAUNCHER_ASSETS = APP_DIR / "assets"
MANIFEST = APP_DIR / "AndroidManifest.xml"
KEYSTORE = APP_DIR / "miko3-launcher.keystore"
APK = APP_DIR / "miko3-launcher.apk"
BUILD = APP_DIR / "build"

KEYSTORE_ALIAS = "miko3launcher"
KEYSTORE_PASS = "miko3launcher"
KEYSTORE_CN = "Miko3 Custom Launcher"

# sherpa-onnx, the same release U1's speed check measured on the robot.
SHERPA_VERSION = "1.13.8"
_RELEASE = f"https://github.com/k2-fsa/sherpa-onnx/releases/download/v{SHERPA_VERSION}/"
SHERPA_ANDROID_URL = _RELEASE + f"sherpa-onnx-v{SHERPA_VERSION}-android.tar.bz2"
SHERPA_ANDROID_SHA256 = "2ff63469a71cb6009aa2e3ed5f4a670f8abdcbe4bb9ffd23776afc792a6b4f44"
SHERPA_JAR_URL = _RELEASE + f"sherpa-onnx-jvm-{SHERPA_VERSION}.jar"
SHERPA_JAR_SHA256 = "77b7b047fade4eadada96b568eb92615049aaf1dc317c7244e46c1ea38b9a63b"
ABI = "arm64-v8a"
# libsherpa-onnx-jni.so needs only libonnxruntime.so beyond the system's own
# libraries (its c-api and cxx-api siblings are for other bindings).
SHERPA_LIBS = ("libsherpa-onnx-jni.so", "libonnxruntime.so")
SHERPA_CACHE = REPO / "tools" / "third_party" / f"sherpa-onnx-{SHERPA_VERSION}"
# Placeholder voice until the trained one is committed under launcher/assets/voice/.
PLACEHOLDER_VOICE = "vits-piper-en_US-lessac-medium"
PLACEHOLDER_VOICE_URL = ("https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/"
                         f"{PLACEHOLDER_VOICE}.tar.bz2")
PLACEHOLDER_VOICE_SHA256 = "9e3febfacf0abf4270172d2958bcec246032b7e88efc2720840cc80c93de334e"
STAMP = "stamp.txt"


def verified(path, sha256):
    """path, once its SHA-256 matches; otherwise deletes it and raises."""
    path = Path(path)
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while chunk := f.read(1 << 20):
            h.update(chunk)
    if h.hexdigest() != sha256:
        path.unlink()
        raise BuildError(f"!! {path.name} checksum mismatch: {h.hexdigest()} (expected {sha256}); "
                         "deleted it, rebuild to fetch it again")
    return path


def fetch(url, dest, sha256):
    """Download url to dest once (cached), and verify it."""
    dest = Path(dest)
    if not dest.is_file():
        dest.parent.mkdir(parents=True, exist_ok=True)
        print(f"== fetching {url} ==")
        tmp = dest.with_suffix(dest.suffix + ".part")
        urllib.request.urlretrieve(url, tmp)
        tmp.rename(dest)
    return verified(dest, sha256)


def sherpa_onnx(cache=SHERPA_CACHE):
    """(java_api_jar, [(abi, so), ...]) from the pinned sherpa-onnx release."""
    cache = Path(cache)
    jar = fetch(SHERPA_JAR_URL, cache / Path(SHERPA_JAR_URL).name, SHERPA_JAR_SHA256)
    lib_dir = cache / "jniLibs" / ABI
    if not all((lib_dir / n).is_file() for n in SHERPA_LIBS):
        tarball = fetch(SHERPA_ANDROID_URL, cache / Path(SHERPA_ANDROID_URL).name, SHERPA_ANDROID_SHA256)
        lib_dir.mkdir(parents=True, exist_ok=True)
        with tarfile.open(tarball, "r:bz2") as t:
            for name in SHERPA_LIBS:
                member = t.getmember(f"./jniLibs/{ABI}/{name}")
                with t.extractfile(member) as src, open(lib_dir / name, "wb") as out:
                    shutil.copyfileobj(src, out)
    return jar, [(ABI, lib_dir / n) for n in SHERPA_LIBS]


def extract_placeholder(tarball, out):
    """Unpack a sherpa-onnx Piper voice tarball into out/voice/ under the
    fixed names the launcher loads: model.onnx, tokens.txt, espeak-ng-data/.
    Returns out."""
    out = Path(out)
    voice = out / "voice"
    tmp = out / "voice.part"
    shutil.rmtree(tmp, ignore_errors=True)
    tmp.mkdir(parents=True)
    with tarfile.open(tarball, "r:bz2") as t:
        for m in t.getmembers():
            if not m.isfile():
                continue
            parts = Path(m.name).parts
            rel = Path(*parts[1:]) if len(parts) > 1 else None
            if rel is None:
                continue
            if rel.suffix == ".onnx" and len(rel.parts) == 1:
                dest = tmp / "model.onnx"
            elif rel == Path("tokens.txt") or rel.parts[0] == "espeak-ng-data":
                dest = tmp / rel
            else:
                continue
            dest.parent.mkdir(parents=True, exist_ok=True)
            with t.extractfile(m) as src, open(dest, "wb") as f:
                shutil.copyfileobj(src, f)
    if not (tmp / "model.onnx").is_file() or not (tmp / "tokens.txt").is_file():
        raise BuildError(f"!! {Path(tarball).name} holds no model.onnx and tokens.txt")
    shutil.rmtree(voice, ignore_errors=True)
    tmp.rename(voice)
    return out


def placeholder_voice(cache=SHERPA_CACHE):
    """Asset root holding voice/ for the stock placeholder voice."""
    root = Path(cache) / PLACEHOLDER_VOICE
    if not (root / "voice" / "model.onnx").is_file():
        tarball = fetch(PLACEHOLDER_VOICE_URL, Path(cache) / Path(PLACEHOLDER_VOICE_URL).name,
                        PLACEHOLDER_VOICE_SHA256)
        extract_placeholder(tarball, root)
    return root


def voice_root(launcher_assets=LAUNCHER_ASSETS, placeholder=placeholder_voice):
    """None when the committed voice (launcher_assets/voice/) exists, since
    launcher_assets is already an asset source; else the placeholder's root."""
    if (Path(launcher_assets) / "voice").is_dir():
        return None
    return Path(placeholder())


def voice_stamp(voice_dir):
    """Short content hash of a voice directory (stamp.txt itself excluded)."""
    voice_dir = Path(voice_dir)
    h = hashlib.sha256()
    for f in sorted(p for p in voice_dir.rglob("*") if p.is_file() and p.name != STAMP):
        h.update(str(f.relative_to(voice_dir)).encode() + b"\0")
        with open(f, "rb") as fh:
            while chunk := fh.read(1 << 20):
                h.update(chunk)
    return h.hexdigest()[:16]


def main():
    ap = argparse.ArgumentParser(description="Build and sign the Miko 3 custom launcher APK.")
    ap.add_argument("--sdk", help="Android SDK root (default: auto-detect)")
    ap.add_argument("--no-bootstrap", action="store_true",
                    help="fail if the toolchain is missing instead of installing it")
    args = ap.parse_args()

    sdk = bc.find_sdk(args.sdk)
    sdk, bt, android_jar, javac, keytool = bc.ensure_toolchain(sdk, not args.no_bootstrap)
    jh = bc.java_home()
    sherpa_jar, sherpa_libs = sherpa_onnx()
    placeholder = voice_root()
    voice_dir = (placeholder or LAUNCHER_ASSETS) / "voice"
    print(f"== voice: {voice_dir.relative_to(REPO)}"
          + (" (stock placeholder until the trained voice is committed)" if placeholder else "") + " ==")
    with tempfile.TemporaryDirectory(prefix="launcher-voice-stamp-") as td:
        # The stamp goes in its own asset root, merged into assets/voice/ by
        # stage_assets, so neither voice source is ever written to.
        stamp_root = Path(td)
        (stamp_root / "voice").mkdir()
        (stamp_root / "voice" / STAMP).write_text(voice_stamp(voice_dir) + "\n")
        bc.build_apk(
            src_dirs=[SRC, SHARED_SRC],
            manifest=MANIFEST,
            android_jar=android_jar, javac=javac, bt=bt, keytool=keytool, java_home_dir=jh,
            build_dir=BUILD,
            keystore=KEYSTORE, keystore_alias=KEYSTORE_ALIAS, keystore_pass=KEYSTORE_PASS,
            keystore_cn=KEYSTORE_CN,
            apk_out=APK,
            # stage_assets skips a source that does not exist.
            asset_sources=[LAUNCHER_ASSETS] + ([placeholder] if placeholder else []) + [stamp_root, SHARED_ASSETS],
            res_dir=RES,
            native_libs=sherpa_libs,
            jars=[sherpa_jar],
        )
    print(f"\n== 4/4 BUILT: {APK.relative_to(REPO)} ({APK.stat().st_size} bytes) ==")
    return 0


if __name__ == "__main__":
    sys.exit(main())
