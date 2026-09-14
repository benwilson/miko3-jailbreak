#!/usr/bin/env python3
"""
build.py — build, sign, install, and launch the spike-drive-test APK.

Throwaway diagnostic app: binds directly to ServiceExam's MyService and sends a
correctly-formed GameEvent envelope (see MainActivity.java), bypassing
RobotControlClient's malformed envelope to answer one question empirically:
does a well-formed drive command move the wheels.

Mirrors scripts/build-bootagent.py's toolchain (javac -> d8 -> aapt2 link ->
zipalign -> apksigner) but skips the native/bootstrap steps this app doesn't need.
"""
import shutil
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
SRC = HERE / "src"
MANIFEST = HERE / "AndroidManifest.xml"
BUILD = HERE / "build"
KEYSTORE = HERE / "spiketest.keystore"
APK = HERE / "spiketest.apk"

PKG = "com.miko3.spiketest"
BUILD_TOOLS = "35.0.0"
PLATFORM = "android-28"
SDK_CANDIDATES = [
    Path("/opt/homebrew/share/android-commandlinetools"),
    Path.home() / "Library" / "Android" / "sdk",
]
KEYSTORE_PASS = "spiketest"


def run(cmd, **kw):
    printable = " ".join(str(c) for c in cmd)
    print(f"  $ {printable}", flush=True)
    subprocess.run([str(c) for c in cmd], check=True, **kw)


def find_sdk():
    for cand in SDK_CANDIDATES:
        if cand.exists():
            return cand
    sys.exit("no Android SDK found under " + ", ".join(str(c) for c in SDK_CANDIDATES))


def main():
    sdk = find_sdk()
    bt = sdk / "build-tools" / BUILD_TOOLS
    android_jar = sdk / "platforms" / PLATFORM / "android.jar"
    javac = shutil.which("javac")
    keytool = shutil.which("keytool")
    if not javac or not keytool:
        sys.exit("need javac + keytool on PATH (brew install openjdk@17)")

    if BUILD.exists():
        shutil.rmtree(BUILD)
    obj = BUILD / "obj"
    obj.mkdir(parents=True)

    print("== 1/5 javac ==")
    sources = [str(p) for p in sorted(SRC.rglob("*.java"))]
    run([javac, "-source", "8", "-target", "8", "-encoding", "UTF-8",
         "-bootclasspath", str(android_jar), "-classpath", str(android_jar),
         "-d", str(obj)] + sources)

    print("== 2/5 d8 ==")
    classes = [str(p) for p in sorted(obj.rglob("*.class"))]
    run([bt / "d8", "--min-api", "28", "--output", str(BUILD)] + classes)

    print("== 3/5 aapt2 link + add dex ==")
    unsigned = BUILD / "unsigned.apk"
    run([bt / "aapt2", "link", "-I", str(android_jar), "--manifest", str(MANIFEST),
         "--min-sdk-version", "28", "--target-sdk-version", "28", "-o", str(unsigned)])
    withdex = BUILD / "withdex.apk"
    shutil.copy(unsigned, withdex)
    run(["zip", "-jq", str(withdex), str(BUILD / "classes.dex")])

    print("== 4/5 zipalign + sign ==")
    aligned = BUILD / "aligned.apk"
    run([bt / "zipalign", "-f", "-p", "4", str(withdex), str(aligned)])
    if not KEYSTORE.exists():
        run([keytool, "-genkeypair", "-keystore", str(KEYSTORE), "-alias", "spiketest",
             "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
             "-storepass", KEYSTORE_PASS, "-keypass", KEYSTORE_PASS,
             "-dname", "CN=Spike Test"])
    signed = BUILD / "signed.apk"
    run([bt / "apksigner", "sign", "--ks", str(KEYSTORE),
         "--ks-pass", f"pass:{KEYSTORE_PASS}", "--key-pass", f"pass:{KEYSTORE_PASS}",
         "--out", str(signed), str(aligned)])
    shutil.copy(signed, APK)
    print(f"== 5/5 wrote {APK} ==")

    if "--install" in sys.argv:
        print("== installing + launching ==")
        run(["adb", "install", "-r", str(APK)])
        run(["adb", "shell", "am", "start", "-n", f"{PKG}/.MainActivity"])


if __name__ == "__main__":
    main()
