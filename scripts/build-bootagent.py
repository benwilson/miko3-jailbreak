#!/usr/bin/env python3
"""
build-bootagent.py — build and sign the Miko 3 boot agent APK, reproducibly.

Why this exists (Miko 3 context):
  The boot agent must be installed by hand under /data/app (factory mode has no
  package manager), so the APK is committed to the repo and normal use never runs
  this script. This script exists so the APK is reproducible from source: it
  bootstraps the Android command-line tools and lld when they are missing, compiles
  the freestanding arm64 neuterd daemon, embeds it into RootOps.java as base64, and
  drives javac -> d8 -> aapt2 link -> zipalign -> apksigner without gradle.

Pipeline:
  0. ensure toolchain (Android SDK cmdline-tools + platform + build-tools, lld)
  1. compile native/neuterd with clang + ld.lld
  2. copy src to a build dir and inject the base64 neuterd into RootOps.java
  3. javac -> d8 -> classes.dex
  4. aapt2 link manifest -> unsigned.apk, add classes.dex
  5. zipalign -> apksigner sign (generating a debug keystore on first run)
  6. write bootagent/miko3-bootagent.apk

Usage:
  python3 scripts/build-bootagent.py            # build (bootstraps toolchain if needed)
  python3 scripts/build-bootagent.py --no-bootstrap   # fail instead of installing anything
  python3 scripts/build-bootagent.py --sdk /path/to/sdk

Dependencies: python3, Homebrew (for bootstrap), Java (javac/keytool), clang.
"""
import argparse
import base64
import os
import shutil
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
BOOTAGENT = REPO / "bootagent"
NATIVE = BOOTAGENT / "native"
SRC = BOOTAGENT / "src"
MANIFEST = BOOTAGENT / "AndroidManifest.xml"
KEYSTORE = BOOTAGENT / "miko3-bootagent.keystore"
APK = BOOTAGENT / "miko3-bootagent.apk"
BUILD = BOOTAGENT / "build"

PKG = "com.miko3.bootagent"
BUILD_TOOLS = "35.0.0"
PLATFORM = "android-28"
SDK_CANDIDATES = [
    Path("/opt/homebrew/share/android-commandlinetools"),
    Path.home() / "Library" / "Android" / "sdk",
]
KEYSTORE_PASS = "miko3bootagent"


class BuildError(SystemExit):
    """A build precondition or step failed; message is actionable."""


def run(cmd, **kw):
    """Run a command, echoing it, raising BuildError on failure."""
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
    need_lld = which("ld.lld") is None and not Path("/opt/homebrew/opt/lld/bin/ld.lld").exists()
    # `zip` is an external binary the packaging step shells out to, and javac/keytool need a JDK;
    # both are reported up front so a host missing them gets one actionable message rather than
    # a failure at the first tool the build happens to invoke.
    need_zip = which("zip") is None
    need_java = which("javac") is None or which("keytool") is None

    if (need_sdk or need_lld or need_zip or need_java) and not bootstrap:
        missing = []
        if need_sdk:
            missing.append(f"Android SDK (build-tools;{BUILD_TOOLS} + platforms;{PLATFORM})")
        if need_lld:
            missing.append("lld (ld.lld)")
        if need_zip:
            missing.append("zip")
        if need_java:
            missing.append("a JDK (javac + keytool)")
        raise BuildError(
            "!! toolchain missing: " + ", ".join(missing)
            + "\n   Install with: brew install lld && brew install --cask android-commandlinetools"
            + "\n   plus a JDK (e.g. brew install openjdk@17) and zip,"
            + "\n   then re-run, or pass --sdk <path>. (Normal use needs no build: the APK is committed.)"
        )

    if need_lld and bootstrap:
        print("== bootstrapping: brew install lld ==")
        run(["brew", "install", "lld"])
        os.environ["PATH"] = "/opt/homebrew/opt/lld/bin:" + os.environ.get("PATH", "")

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
        # accept licenses non-interactively, without a shell
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
    """Major version of a JDK home, from its release file (0 if unknown)."""
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
    """A JDK home for javac/keytool/sdkmanager. Prefer the highest JDK >= 17."""
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


def compile_neuterd():
    print("== 1/6 compile neuterd ==")
    script = NATIVE / "build-neuterd.sh"
    if not script.exists():
        raise BuildError(f"!! missing {script}")
    env = dict(os.environ)
    lld = which("ld.lld") or ("/opt/homebrew/opt/lld/bin/ld.lld"
                              if Path("/opt/homebrew/opt/lld/bin/ld.lld").exists() else "")
    if lld:
        env["LLD"] = lld
    run(["bash", script], env=env)
    binary = NATIVE / "neuterd"
    if not binary.exists():
        raise BuildError("!! neuterd did not build")
    return binary


def stage_sources(neuterd_binary):
    print("== 2/6 stage sources + inject neuterd ==")
    if BUILD.exists():
        shutil.rmtree(BUILD)
    src_out = BUILD / "src"
    shutil.copytree(SRC, src_out)
    b64 = base64.b64encode(neuterd_binary.read_bytes()).decode("ascii")
    rootops = src_out / "com" / "miko3" / "bootagent" / "RootOps.java"
    text = rootops.read_text()
    if "@@NEUTERD_B64@@" not in text:
        raise BuildError("!! RootOps.java placeholder @@NEUTERD_B64@@ not found")
    rootops.write_text(text.replace("@@NEUTERD_B64@@", b64))
    print(f"   embedded neuterd ({neuterd_binary.stat().st_size} bytes -> {len(b64)} b64 chars)")
    return src_out


def compile_java(src_out, android_jar, javac, bt, java_home_dir):
    print("== 3/6 javac + d8 ==")
    obj = BUILD / "obj"
    obj.mkdir(parents=True, exist_ok=True)
    sources = [str(p) for p in sorted(src_out.rglob("*.java"))]
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
    print("== 4/6 aapt2 link + add dex ==")
    unsigned = BUILD / "unsigned.apk"
    run([bt / "aapt2", "link", "-I", str(android_jar), "--manifest", str(MANIFEST),
         "--min-sdk-version", "28", "--target-sdk-version", "28", "-o", str(unsigned)])
    withdex = BUILD / "withdex.apk"
    shutil.copy(unsigned, withdex)
    run(["zip", "-jq", str(withdex), str(BUILD / "classes.dex")])
    return withdex


def normalize_zip_timestamps(path, fixed=(2020, 1, 1, 0, 0, 0)):
    """Rewrite a zip's entries with a fixed timestamp so the build is byte-reproducible."""
    import zipfile
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
    print("== 5/6 zipalign + sign ==")
    aligned = BUILD / "aligned.apk"
    run([bt / "zipalign", "-f", "-p", "4", str(withdex), str(aligned)])
    normalize_zip_timestamps(aligned)
    if not KEYSTORE.exists():
        env = dict(os.environ)
        if java_home_dir:
            env["JAVA_HOME"] = java_home_dir
        run([keytool, "-genkeypair", "-keystore", str(KEYSTORE), "-alias", "miko3",
             "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
             "-storepass", KEYSTORE_PASS, "-keypass", KEYSTORE_PASS,
             "-dname", "CN=Miko3 Boot Agent"], env=env)
        print(f"   generated {KEYSTORE}")
    run([bt / "apksigner", "sign", "--ks", str(KEYSTORE),
         "--ks-pass", f"pass:{KEYSTORE_PASS}", "--key-pass", f"pass:{KEYSTORE_PASS}",
         "--v4-signing-enabled", "false",
         "--min-sdk-version", "28", "--out", str(APK), str(aligned)])
    run([bt / "apksigner", "verify", "--print-certs", str(APK)])


def main():
    ap = argparse.ArgumentParser(description="Build and sign the Miko 3 boot agent APK.")
    ap.add_argument("--sdk", help="Android SDK root (default: auto-detect)")
    ap.add_argument("--no-bootstrap", action="store_true",
                    help="fail if the toolchain is missing instead of installing it")
    args = ap.parse_args()

    sdk = find_sdk(args.sdk)
    sdk, bt, android_jar, javac, keytool = ensure_toolchain(sdk, not args.no_bootstrap)
    jh = java_home()
    neuterd = compile_neuterd()
    src_out = stage_sources(neuterd)
    compile_java(src_out, android_jar, javac, bt, jh)
    withdex = link_and_pack(android_jar, bt)
    sign(withdex, bt, keytool, jh)
    print(f"\n== 6/6 BUILT: {APK.relative_to(REPO)} ({APK.stat().st_size} bytes) ==")
    return 0


if __name__ == "__main__":
    sys.exit(main())
