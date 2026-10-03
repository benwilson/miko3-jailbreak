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
libonnxruntime.so that library was built against) and bundles them, with the
vendor's libmiko_drivers.so for the drive lease (VENDOR_LIB_DIR). The voice
itself is staged into the APK's assets/voice/ as model.onnx, tokens.txt and
espeak-ng-data/, plus a stamp.txt the launcher uses to know when to copy it
out again: from launcher/assets/voice/ once the trained voice is committed
there (U4), else from sherpa-onnx's stock vits-piper-en_US-lessac-medium,
downloaded into tools/third_party/ and never committed.

The robot's ears (explore-on-claude plan U3, KTD4): the launcher's
ListenService runs sherpa-onnx's streaming zipformer-en-20M. The build
downloads that model's pinned, checksummed release into tools/third_party/,
and stages its int8 encoder, decoder and joiner plus tokens.txt into the APK's
assets/listen/ under fixed names, with a stamp.txt like the voice's.
The voice identification's speaker-embedding model (3D-Speaker CAM++) is fetched
the same way and staged as assets/voiceid/model.onnx with its own stamp.txt.

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

# The vendor's libmiko_drivers.so, bundled as the launcher's own native
# library: DriveLeaseService's DirectMotorDriver uses SensorModule, which
# System.loadLibrary()s it, and the /system/lib64 copy is out of reach behind
# linker namespace isolation (live test: NoClassDefFoundError SensorModule).
# Same source as build-mode-explore.py and build-mode-remote-control.py.
# libconexant_dsp_lib.so rides the same way (meeting plan U1, KTD4): the
# voice-direction stubs under shared/src/com/example/conexantapi/ load it for
# VoiceDirection, which only the launcher uses (it holds the microphone).
VENDOR_ABI = "arm64-v8a"
VENDOR_LIB_DIR = REPO / "tools" / "serviceexam_jadx" / "resources" / "lib" / VENDOR_ABI
DRIVER_LIB = "libmiko_drivers.so"
DSP_LIB = "libconexant_dsp_lib.so"

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
# The listening model (explore plan U3, KTD4): streaming, English, ~44 MB int8.
LISTEN_MODEL = "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17"
LISTEN_MODEL_URL = ("https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"
                    f"{LISTEN_MODEL}.tar.bz2")
LISTEN_MODEL_SHA256 = "9c559283e8498d3fe95913c79ca1cb454bb26281ac2b102b41306c7d752765d9"
LISTEN_CACHE = REPO / "tools" / "third_party" / LISTEN_MODEL
LISTEN_PARTS = ("encoder", "decoder", "joiner")
# Meeting plan U3, KTD2: hotwords bias nothing without the model's bpe vocabulary,
# and the sherpa tarball ships none. The package's own bpe.model comes from the
# icefall repository its README names (pinned), and the build writes bpe.vocab
# from it beside tokens.txt. The Silero VAD gate's model rides along there too.
BPE_MODEL_URL = ("https://huggingface.co/desh2608/icefall-asr-librispeech-pruned-transducer-stateless7-"
                 "streaming-small/resolve/main/data/lang_bpe_500/bpe.model")
BPE_MODEL_SHA256 = "c53433de083c4a6ad12d034550ef22de68cec62c4f58932a7b6b8b2f1e743fa5"
BPE_VOCAB = "bpe.vocab"
VAD_MODEL_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"
VAD_MODEL_SHA256 = "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6"
VAD_MODEL = "silero_vad.onnx"
# Owner 2026-10-02: voice identification. sherpa-onnx's speaker embedding extractor runs
# 3D-Speaker's CAM++ (English, VoxCeleb, 16 kHz, ~28 MB), staged as assets/voiceid/model.onnx
# with a stamp.txt like the listen model's. The release tag really is spelled "recongition".
VOICEID_MODEL = "3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx"
VOICEID_MODEL_URL = ("https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/"
                     + VOICEID_MODEL)
VOICEID_MODEL_SHA256 = "357a834f702b80161e5b981182c038e18553c1f2ca752ed6cec2052365d4129b"
VOICEID_ASSET = "voiceid/model.onnx"
VOICEID_CACHE = REPO / "tools" / "third_party" / "voiceid"
# The hotwords file lives at the APK asset root, outside the staged model directory.
HOTWORDS = LAUNCHER_ASSETS / "hotwords.txt"
STAMP = "stamp.txt"
LABEL = "label.txt"


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


def extract_listen_model(tarball, out):
    """Unpack the zipformer tarball's int8 encoder, decoder and joiner and its
    tokens.txt into out/listen/ as encoder.onnx, decoder.onnx, joiner.onnx and
    tokens.txt (the names ListenEngine loads). Returns out."""
    out = Path(out)
    listen = out / "listen"
    tmp = out / "listen.part"
    shutil.rmtree(tmp, ignore_errors=True)
    tmp.mkdir(parents=True)
    wanted = {f"{part}-epoch-99-avg-1.int8.onnx": f"{part}.onnx" for part in LISTEN_PARTS}
    wanted["tokens.txt"] = "tokens.txt"
    with tarfile.open(tarball, "r:bz2") as t:
        for m in t.getmembers():
            name = Path(m.name).name
            if m.isfile() and len(Path(m.name).parts) == 2 and name in wanted:
                with t.extractfile(m) as src, open(tmp / wanted[name], "wb") as f:
                    shutil.copyfileobj(src, f)
    missing = sorted(set(wanted.values()) - {p.name for p in tmp.iterdir()})
    if missing:
        raise BuildError(f"!! {Path(tarball).name} lacks {', '.join(missing)}")
    shutil.rmtree(listen, ignore_errors=True)
    tmp.rename(listen)
    return out


def _varint(data, i):
    shift, value = 0, 0
    while True:
        if i >= len(data):
            raise BuildError("!! bpe.model is truncated")
        b = data[i]
        i += 1
        value |= (b & 0x7F) << shift
        shift += 7
        if b < 0x80:
            return value, i


def _protobuf_fields(data):
    """(field number, wire type, value) for each field of a protobuf message."""
    i = 0
    while i < len(data):
        key, i = _varint(data, i)
        field, wire = key >> 3, key & 7
        if wire == 0:
            value, i = _varint(data, i)
        elif wire == 1:
            value, i = data[i:i + 8], i + 8
        elif wire == 2:
            n, i = _varint(data, i)
            value, i = data[i:i + n], i + n
        elif wire == 5:
            value, i = data[i:i + 4], i + 4
        else:
            raise BuildError(f"!! bpe.model has an unexpected protobuf wire type {wire}")
        yield field, wire, value


def bpe_vocab(model_bytes):
    """bpe.vocab text from a sentencepiece bpe.model: one "piece<TAB>score" line
    per piece in id order, what sherpa-onnx's script/export_bpe_vocab.py prints.
    The ModelProto is decoded by hand (field 1 repeated SentencePiece{1: piece,
    2: score}) so the build needs no sentencepiece dependency."""
    import struct
    lines = []
    for field, wire, value in _protobuf_fields(model_bytes):
        if field != 1 or wire != 2:
            continue
        piece, score = None, 0.0
        for f, w, v in _protobuf_fields(value):
            if f == 1 and w == 2:
                piece = v.decode("utf-8")
            elif f == 2 and w == 5:
                score = struct.unpack("<f", v)[0]
        if piece is not None:
            lines.append(f"{piece}\t{score}\n")
    if not lines:
        raise BuildError("!! bpe.model holds no pieces; is it a sentencepiece model?")
    return "".join(lines)


def listen_extras(root, fetch=fetch):
    """Writes listen/bpe.vocab (from the pinned bpe.model) and listen/silero_vad.onnx
    beside tokens.txt under root, fetching each once. Raises BuildError, naming the
    file, when either cannot be produced: hotwords need the vocabulary (KTD2)."""
    root = Path(root)
    listen = root / "listen"
    vocab = listen / BPE_VOCAB
    if not vocab.is_file():
        try:
            model = fetch(BPE_MODEL_URL, root / "bpe.model", BPE_MODEL_SHA256)
        except BuildError as e:
            raise BuildError(f"!! cannot stage {BPE_VOCAB}: the model package's bpe.model is unavailable\n   {e}")
        vocab.write_text(bpe_vocab(Path(model).read_bytes()))
    vad = listen / VAD_MODEL
    if not vad.is_file():
        try:
            src = fetch(VAD_MODEL_URL, root / VAD_MODEL, VAD_MODEL_SHA256)
        except BuildError as e:
            raise BuildError(f"!! cannot stage {VAD_MODEL}: the Silero VAD model is unavailable\n   {e}")
        if Path(src).resolve() != vad.resolve():
            shutil.copy(src, vad)
    return root


def check_hotwords(path=HOTWORDS):
    """The hotwords file is read by the launcher at start; a missing one fails the build."""
    path = Path(path)
    if not path.is_file():
        raise BuildError(f"!! hotwords file missing: {path}\n"
                         "   it lives at the launcher's asset root (meeting plan U3, KTD2)")
    return path


def listen_model(cache=LISTEN_CACHE):
    """Asset root holding listen/ for the pinned zipformer model, its bpe
    vocabulary and the VAD model."""
    root = Path(cache)
    if not all((root / "listen" / f"{p}.onnx").is_file() for p in LISTEN_PARTS):
        tarball = fetch(LISTEN_MODEL_URL, root.parent / Path(LISTEN_MODEL_URL).name, LISTEN_MODEL_SHA256)
        extract_listen_model(tarball, root)
    return listen_extras(root)


def voiceid_model(cache=VOICEID_CACHE, fetch=fetch):
    """Asset root holding voiceid/model.onnx, the pinned speaker-embedding model, fetched
    (checksummed) into cache once. Raises BuildError naming the model when it cannot be had."""
    root = Path(cache)
    staged = root / VOICEID_ASSET
    if not staged.is_file():
        try:
            src = fetch(VOICEID_MODEL_URL, root / VOICEID_MODEL, VOICEID_MODEL_SHA256)
        except BuildError as e:
            raise BuildError(f"!! cannot stage {VOICEID_MODEL}: the voice-id model is unavailable\n   {e}")
        staged.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy(src, staged)
    return root


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


def vendor_native_libs(lib_dir=None):
    """[(abi, so_path)] for the motor-driver and voice-direction libraries, or
    BuildError naming the missing file and the directory searched."""
    lib_dir = Path(lib_dir if lib_dir is not None else VENDOR_LIB_DIR)
    driver = lib_dir / DRIVER_LIB
    if not driver.is_file():
        raise BuildError(
            f"!! vendor motor-driver library missing: {driver}\n"
            "   it comes from ServiceExam's APK (lib/arm64-v8a/); re-extract it into "
            "tools/serviceexam_jadx/resources/ (jadx) before building the launcher.\n"
            "   Without it the drive lease's DirectMotorDriver cannot load SensorModule, "
            "and the launcher crashes when a mode takes the lease.")
    dsp = lib_dir / DSP_LIB
    if not dsp.is_file():
        raise BuildError(
            f"!! vendor voice-direction library missing: {dsp}\n"
            "   it comes from ServiceExam's APK (lib/arm64-v8a/), next to libmiko_drivers.so; "
            "re-extract it into tools/serviceexam_jadx/resources/ (jadx) before building the launcher.\n"
            "   Without it VoiceDirection has no backend and the ears probe reports no angle "
            "(meeting plan U1, KTD4).")
    # Meeting plan U3: the ears session runs the vendor wake-word engine, from the
    # same shared list mode-voice stages.
    return [(VENDOR_ABI, driver), (VENDOR_ABI, dsp)] + bc.wakeword_native_libs(lib_dir, "the launcher")


def main():
    ap = argparse.ArgumentParser(description="Build and sign the Miko 3 custom launcher APK.")
    ap.add_argument("--sdk", help="Android SDK root (default: auto-detect)")
    ap.add_argument("--no-bootstrap", action="store_true",
                    help="fail if the toolchain is missing instead of installing it")
    args = ap.parse_args()

    # Checked first, so a missing library or asset never costs toolchain work.
    vendor_native_libs()
    check_hotwords()
    if not bc.WAKEWORD_MODEL.is_file():
        raise BuildError(f"!! wake-word model missing: {bc.WAKEWORD_MODEL}\n"
                         "   the ears session (meeting plan U3) needs the vendor model mode-voice ships")
    sdk = bc.find_sdk(args.sdk)
    sdk, bt, android_jar, javac, keytool = bc.ensure_toolchain(sdk, not args.no_bootstrap)
    jh = bc.java_home()
    sherpa_jar, sherpa_libs = sherpa_onnx()
    native_libs = vendor_native_libs() + sherpa_libs
    placeholder = voice_root()
    voice_dir = (placeholder or LAUNCHER_ASSETS) / "voice"
    print(f"== voice: {voice_dir.relative_to(REPO)}"
          + (" (stock placeholder until the trained voice is committed)" if placeholder else "") + " ==")
    listen_root = listen_model()
    print(f"== listening model: {LISTEN_MODEL} (int8) ==")
    voiceid_root = voiceid_model()
    print(f"== voice-id model: {VOICEID_MODEL} ==")
    with tempfile.TemporaryDirectory(prefix="launcher-voice-stamp-") as td:
        # The stamp goes in its own asset root, merged into assets/voice/ by
        # stage_assets, so neither voice source is ever written to.
        stamp_root = Path(td)
        (stamp_root / "voice").mkdir()
        (stamp_root / "voice" / STAMP).write_text(voice_stamp(voice_dir) + "\n")
        # What the Settings page shows as the loaded voice (outside the stamp's hash).
        (stamp_root / "voice" / LABEL).write_text(("stock lessac medium" if placeholder else "trained") + "\n")
        (stamp_root / "listen").mkdir()
        (stamp_root / "listen" / STAMP).write_text(voice_stamp(listen_root / "listen") + "\n")
        (stamp_root / "voiceid").mkdir()
        (stamp_root / "voiceid" / STAMP).write_text(voice_stamp(voiceid_root / "voiceid") + "\n")
        # The wake-word model at the asset root, where recognizer.WakeWord looks for it
        # (meeting plan U3): the same file mode-voice ships, never a second copy in git.
        shutil.copy(bc.WAKEWORD_MODEL, stamp_root / bc.WAKEWORD_MODEL.name)
        bc.build_apk(
            src_dirs=[SRC, SHARED_SRC],
            manifest=MANIFEST,
            android_jar=android_jar, javac=javac, bt=bt, keytool=keytool, java_home_dir=jh,
            build_dir=BUILD,
            keystore=KEYSTORE, keystore_alias=KEYSTORE_ALIAS, keystore_pass=KEYSTORE_PASS,
            keystore_cn=KEYSTORE_CN,
            apk_out=APK,
            # stage_assets skips a source that does not exist.
            asset_sources=[LAUNCHER_ASSETS] + ([placeholder] if placeholder else [])
            + [listen_root, voiceid_root, stamp_root, SHARED_ASSETS],
            res_dir=RES,
            native_libs=native_libs,
            jars=[sherpa_jar],
            # One id with build-mode-explore.py (meeting plan U1): the QA scripts compare
            # the two APKs' version names to know they came from the same tree.
            version_name=bc.build_id(),
        )
    print(f"\n== 4/4 BUILT: {APK.relative_to(REPO)} ({APK.stat().st_size} bytes, build {bc.build_id()}) ==")
    return 0


if __name__ == "__main__":
    sys.exit(main())
