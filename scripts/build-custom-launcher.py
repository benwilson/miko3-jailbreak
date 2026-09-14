#!/usr/bin/env python3
"""
build-custom-launcher.py — build and sign the Miko 3 custom launcher APK.

Proof-of-concept HOME replacement: shows basic device info on-screen and
serves the same info as a read-only web page over Wi-Fi, plus a minimal
Wi-Fi connect/disconnect/forget UI. See launcher/README.md.

Same gradle-free pipeline as scripts/build-bootagent.py (javac -> d8 ->
aapt2 link -> zipalign -> apksigner), minus the native-daemon step this app
doesn't need.

Usage:
  python3 scripts/build-custom-launcher.py
  python3 scripts/build-custom-launcher.py --no-bootstrap
  python3 scripts/build-custom-launcher.py --sdk /path/to/sdk

Dependencies: python3, Homebrew (for bootstrap), a JDK (javac/keytool).
"""
import argparse
import os
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
APP_DIR = REPO / "launcher"
SRC = APP_DIR / "src"
MANIFEST = APP_DIR / "AndroidManifest.xml"
KEYSTORE = APP_DIR / "miko3-launcher.keystore"
APK = APP_DIR / "miko3-launcher.apk"
BUILD = APP_DIR / "build"

BUILD_TOOLS = "35.0.0"
PLATFORM = "android-28"
SDK_CANDIDATES = [
    Path("/opt/homebrew/share/android-commandlinetools"),
    Path.home() / "Library" / "Android" / "sdk",
]
KEYSTORE_PASS = "miko3launcher"


class BuildError(SystemExit):
    """A build precondition or step failed; message is actionable."""


def run(cmd, **kw):
    printable = " ".join(str(c) for c in cmd)
    print(f"  $ {printable}", flush=True)
    try:
        return subprocess.run([str(c) for c in cmd], check=True, **kw)
    except FileNotFoundError as exc:
        raise BuildError(f"!! required tool not found: {cmd[0]} ({exc})")
    except subprocess.CalledProcessError as exc:
        raise BuildError(f"!! command failed ({exc.returncode}): {printable}")


def which(name):
    return shutil.which(name)


def find_sdk(explicit):
    if explicit:
        p = Path(explicit).expanduser()
        if not (p / "cmdline-tools").exists() and not (p / "build-tools").exists():
            raise BuildError(f"!! --sdk {p} does not look like an Android SDK")
        return p
    env = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if env and Path(env).exists():
        return Path(env)
    for cand in SDK_CANDIDATES:
        if cand.exists():
            return cand
    return None


def ensure_toolchain(sdk, bootstrap):
    """Return (sdk_dir, build_tools_dir, android_jar, javac, keytool)."""
    need_sdk = sdk is None or not (sdk / "build-tools" / BUILD_TOOLS).exists() \
        or not (sdk / "platforms" / PLATFORM / "android.jar").exists()
    need_java = which("javac") is None or which("keytool") is None

    if (need_sdk or need_java) and not bootstrap:
        missing = []
        if need_sdk:
            missing.append(f"Android SDK (build-tools;{BUILD_TOOLS} + platforms;{PLATFORM})")
        if need_java:
            missing.append("a JDK (javac + keytool)")
        raise BuildError(
            "!! toolchain missing: " + ", ".join(missing)
            + "\n   Install with: brew install --cask android-commandlinetools"
            + "\n   plus a JDK (e.g. brew install openjdk@17),"
            + "\n   then re-run, or pass --sdk <path>."
        )

    if need_sdk and bootstrap:
        print("== bootstrapping: Android command-line tools ==")
        if sdk is None:
            if which("sdkmanager") is None:
                run(["brew", "install", "--cask", "android-commandlinetools"])
            sdk = find_sdk(None) or SDK_CANDIDATES[0]
        sdkmanager = sdk / "cmdline-tools" / "latest" / "bin" / "sdkmanager"
        if not sdkmanager.exists():
            sdkmanager = Path(which("sdkmanager") or sdkmanager)
        if not sdkmanager.exists():
            raise BuildError(f"!! sdkmanager not found under {sdk}")
        env = dict(os.environ, JAVA_HOME=java_home())
        subprocess.run([str(sdkmanager), "--licenses"], input=b"y\n" * 100,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        run([sdkmanager, f"platforms;{PLATFORM}", f"build-tools;{BUILD_TOOLS}"], env=env)

    if sdk is None:
        raise BuildError("!! no Android SDK found; pass --sdk or install android-commandlinetools")

    bt = sdk / "build-tools" / BUILD_TOOLS
    android_jar = sdk / "platforms" / PLATFORM / "android.jar"
    if not bt.exists() or not android_jar.exists():
        raise BuildError(f"!! SDK at {sdk} lacks build-tools;{BUILD_TOOLS} or platforms;{PLATFORM}")

    javac = which("javac")
    keytool = which("keytool")
    if not javac:
        raise BuildError("!! javac not found; install a JDK (brew install openjdk@17)")
    if not keytool:
        raise BuildError("!! keytool not found; install a JDK")
    return sdk, bt, android_jar, javac, keytool


def _jdk_major(home):
    release = Path(home) / "release"
    if release.exists():
        for line in release.read_text().splitlines():
            if line.startswith("JAVA_VERSION="):
                ver = line.split("=", 1)[1].strip().strip('"')
                if ver.startswith("1."):
                    return int(ver.split(".")[1])
                return int(ver.split(".")[0].split("-")[0])
    return 0


def java_home():
    env = os.environ.get("JAVA_HOME")
    if env and _jdk_major(env) >= 17:
        return env
    candidates = []
    for base in (Path("/Library/Java/JavaVirtualMachines"), Path("/opt/homebrew/opt")):
        if base.exists():
            candidates.extend(base.glob("*.jdk/Contents/Home"))
    modern = [c for c in candidates if _jdk_major(c) >= 17]
    pool = modern or candidates
    if pool:
        return str(max(pool, key=_jdk_major))
    return ""


def compile_java(android_jar, javac, bt, java_home_dir):
    print("== 1/4 javac + d8 ==")
    if BUILD.exists():
        shutil.rmtree(BUILD)
    obj = BUILD / "obj"
    obj.mkdir(parents=True, exist_ok=True)
    sources = [str(p) for p in sorted(SRC.rglob("*.java"))]
    env = dict(os.environ)
    if java_home_dir:
        env["JAVA_HOME"] = java_home_dir
    run([javac, "-source", "8", "-target", "8", "-encoding", "UTF-8",
         "-bootclasspath", str(android_jar), "-classpath", str(android_jar),
         "-d", str(obj)] + sources, env=env)
    classes = [str(p) for p in sorted(obj.rglob("*.class"))]
    run([bt / "d8", "--min-api", "28", "--output", str(BUILD)] + classes, env=env)
    if not (BUILD / "classes.dex").exists():
        raise BuildError("!! d8 did not produce classes.dex")


def link_and_pack(android_jar, bt):
    print("== 2/4 aapt2 link + add dex ==")
    unsigned = BUILD / "unsigned.apk"
    run([bt / "aapt2", "link", "-I", str(android_jar), "--manifest", str(MANIFEST),
         "--min-sdk-version", "28", "--target-sdk-version", "28", "-o", str(unsigned)])
    withdex = BUILD / "withdex.apk"
    shutil.copy(unsigned, withdex)
    run(["zip", "-jq", str(withdex), str(BUILD / "classes.dex")])
    return withdex


def normalize_zip_timestamps(path, fixed=(2020, 1, 1, 0, 0, 0)):
    tmp = path.with_name(path.name + ".norm")
    with zipfile.ZipFile(path) as zin, zipfile.ZipFile(tmp, "w") as zout:
        for info in zin.infolist():
            data = zin.read(info.filename)
            ni = zipfile.ZipInfo(info.filename, date_time=fixed)
            ni.compress_type = info.compress_type
            ni.external_attr = info.external_attr
            zout.writestr(ni, data)
    tmp.replace(path)


def sign(withdex, bt, keytool, java_home_dir):
    print("== 3/4 zipalign + sign ==")
    aligned = BUILD / "aligned.apk"
    run([bt / "zipalign", "-f", "-p", "4", str(withdex), str(aligned)])
    normalize_zip_timestamps(aligned)
    if not KEYSTORE.exists():
        env = dict(os.environ)
        if java_home_dir:
            env["JAVA_HOME"] = java_home_dir
        run([keytool, "-genkeypair", "-keystore", str(KEYSTORE), "-alias", "miko3launcher",
             "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
             "-storepass", KEYSTORE_PASS, "-keypass", KEYSTORE_PASS,
             "-dname", "CN=Miko3 Custom Launcher"], env=env)
        print(f"   generated {KEYSTORE}")
    signed = BUILD / "signed.apk"
    run([bt / "apksigner", "sign", "--ks", str(KEYSTORE),
         "--ks-pass", f"pass:{KEYSTORE_PASS}", "--key-pass", f"pass:{KEYSTORE_PASS}",
         "--v4-signing-enabled", "false",
         "--min-sdk-version", "28", "--out", str(signed), str(aligned)])
    run([bt / "apksigner", "verify", "--print-certs", str(signed)])
    shutil.move(str(signed), str(APK))


def main():
    ap = argparse.ArgumentParser(description="Build and sign the Miko 3 custom launcher APK.")
    ap.add_argument("--sdk", help="Android SDK root (default: auto-detect)")
    ap.add_argument("--no-bootstrap", action="store_true",
                    help="fail if the toolchain is missing instead of installing it")
    args = ap.parse_args()

    sdk = find_sdk(args.sdk)
    sdk, bt, android_jar, javac, keytool = ensure_toolchain(sdk, not args.no_bootstrap)
    jh = java_home()
    compile_java(android_jar, javac, bt, jh)
    withdex = link_and_pack(android_jar, bt)
    sign(withdex, bt, keytool, jh)
    print(f"\n== 4/4 BUILT: {APK.relative_to(REPO)} ({APK.stat().st_size} bytes) ==")
    return 0


if __name__ == "__main__":
    sys.exit(main())
