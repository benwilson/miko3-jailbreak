"""
build_common.py — shared no-Gradle Android build pipeline for this repo's
installable APKs (launcher, mode apps). Factored out of the pipeline that
was duplicated between scripts/build-custom-launcher.py and
scripts/build-bootagent.py.

javac -> d8 -> aapt2 link (+assets) -> zipalign -> apksigner, with no
Gradle, no AndroidX, no Maven-resolved dependencies. Every source
directory passed in (an app's own src/ plus, per KTD2, the shared
module's src/) compiles into one javac invocation and one classes.dex —
Android has no true cross-APK class sharing outside split-APK/Bundletool
machinery, so every APK dexes its own private copy of the shared classes.

scripts/build-bootagent.py is intentionally NOT ported onto this module —
its native-neuterd-compile and base64-injection steps are unique to that
app and out of this refactor's scope.
"""
import os
import shutil
import subprocess
import zipfile
from pathlib import Path

BUILD_TOOLS = "35.0.0"
PLATFORM = "android-28"
SDK_CANDIDATES = [
    Path("/opt/homebrew/share/android-commandlinetools"),
    Path.home() / "Library" / "Android" / "sdk",
]


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


def compile_java(src_dirs, android_jar, javac, bt, java_home_dir, build_dir):
    """Compile every .java under each of src_dirs into one classes.dex.

    Per KTD2, src_dirs is normally [app_src, shared_src] — every APK dexes
    its own private copy of the shared module rather than sharing a
    library, since this build has no split-APK/Bundletool machinery.
    """
    print("== 1/4 javac + d8 ==")
    if build_dir.exists():
        shutil.rmtree(build_dir)
    obj = build_dir / "obj"
    obj.mkdir(parents=True, exist_ok=True)
    sources = []
    for src_dir in src_dirs:
        sources.extend(str(p) for p in sorted(Path(src_dir).rglob("*.java")))
    if not sources:
        raise BuildError(f"!! no .java sources found under {src_dirs}")
    env = dict(os.environ)
    if java_home_dir:
        env["JAVA_HOME"] = java_home_dir
    run([javac, "-source", "8", "-target", "8", "-encoding", "UTF-8",
         "-bootclasspath", str(android_jar), "-classpath", str(android_jar),
         "-d", str(obj)] + sources, env=env)
    classes = [str(p) for p in sorted(obj.rglob("*.class"))]
    run([bt / "d8", "--min-api", "28", "--output", str(build_dir)] + classes, env=env)
    if not (build_dir / "classes.dex").exists():
        raise BuildError("!! d8 did not produce classes.dex")


def stage_assets(asset_sources, build_dir):
    """Merge one or more assets directories into build_dir/assets.

    asset_sources: list of directories whose contents are copied into one
    merged assets/ tree (e.g. the shared module's vendored pico.min.css
    plus an app's own assets), since aapt2 link's -A takes a single dir.
    Returns the merged dir, or None if every source is empty/absent.
    """
    merged = build_dir / "assets"
    found_any = False
    for src in asset_sources:
        src = Path(src)
        if not src.exists():
            continue
        for item in src.iterdir():
            found_any = True
            dest = merged / item.name
            merged.mkdir(parents=True, exist_ok=True)
            if item.is_dir():
                shutil.copytree(item, dest, dirs_exist_ok=True)
            else:
                shutil.copy(item, dest)
    return merged if found_any else None


def compile_resources(bt, res_dir, build_dir):
    """Compile an app's res/ directory (e.g. res/xml/network_security_config.xml)
    into a flat archive aapt2 link can consume via -R. Returns None if res_dir
    is absent or empty — most of this repo's apps have no res/ at all yet."""
    res_dir = Path(res_dir)
    if not res_dir.exists() or not any(res_dir.rglob("*.xml")):
        return None
    compiled = build_dir / "compiled_res.zip"
    run([bt / "aapt2", "compile", "--dir", str(res_dir), "-o", str(compiled)])
    return compiled


def link_and_pack(android_jar, bt, manifest, build_dir, assets_dir=None, res_zip=None):
    print("== 2/4 aapt2 link + add dex ==")
    unsigned = build_dir / "unsigned.apk"
    cmd = [bt / "aapt2", "link", "-I", str(android_jar), "--manifest", str(manifest),
           "--min-sdk-version", "28", "--target-sdk-version", "28", "-o", str(unsigned)]
    if assets_dir is not None:
        cmd += ["-A", str(assets_dir)]
    if res_zip is not None:
        cmd += ["-R", str(res_zip)]
    run(cmd)
    withdex = build_dir / "withdex.apk"
    shutil.copy(unsigned, withdex)
    run(["zip", "-jq", str(withdex), str(build_dir / "classes.dex")])
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


def sign(withdex, bt, keytool, java_home_dir, keystore, keystore_alias, keystore_pass,
         keystore_cn, build_dir, apk_out):
    print("== 3/4 zipalign + sign ==")
    aligned = build_dir / "aligned.apk"
    run([bt / "zipalign", "-f", "-p", "4", str(withdex), str(aligned)])
    normalize_zip_timestamps(aligned)
    if not keystore.exists():
        env = dict(os.environ)
        if java_home_dir:
            env["JAVA_HOME"] = java_home_dir
        run([keytool, "-genkeypair", "-keystore", str(keystore), "-alias", keystore_alias,
             "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
             "-storepass", keystore_pass, "-keypass", keystore_pass,
             "-dname", f"CN={keystore_cn}"], env=env)
        print(f"   generated {keystore}")
    signed = build_dir / "signed.apk"
    run([bt / "apksigner", "sign", "--ks", str(keystore),
         "--ks-pass", f"pass:{keystore_pass}", "--key-pass", f"pass:{keystore_pass}",
         "--v4-signing-enabled", "false",
         "--min-sdk-version", "28", "--out", str(signed), str(aligned)])
    run([bt / "apksigner", "verify", "--print-certs", str(signed)])
    shutil.move(str(signed), str(apk_out))


def build_apk(src_dirs, manifest, android_jar, javac, bt, keytool, java_home_dir,
              build_dir, keystore, keystore_alias, keystore_pass, keystore_cn,
              apk_out, asset_sources=None, res_dir=None):
    """Full pipeline: compile_java -> stage_assets -> compile_resources -> link_and_pack -> sign."""
    compile_java(src_dirs, android_jar, javac, bt, java_home_dir, build_dir)
    assets_dir = stage_assets(asset_sources, build_dir) if asset_sources else None
    res_zip = compile_resources(bt, res_dir, build_dir) if res_dir else None
    withdex = link_and_pack(android_jar, bt, manifest, build_dir, assets_dir, res_zip)
    sign(withdex, bt, keytool, java_home_dir, keystore, keystore_alias, keystore_pass,
         keystore_cn, build_dir, apk_out)
