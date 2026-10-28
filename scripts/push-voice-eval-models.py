#!/usr/bin/env python3
"""Push the voice evaluation's extra speaker models to the robot and turn the evaluation on.

Owner 2026-10-03: CAM++ (the launcher's bundled speaker model) took the owner's wife for him
through the robot's mic chain. The launcher's opt-in evaluation (VoiceEval, property
debug.miko3.voice_eval=1) embeds every clean conversation answer with each extra model it
finds in its files/voiceeval/models/ as well, and logs "voice eval:" lines (scores and
timings only: no audio, no names, no ids) that scripts/voice-eval-report.py compares.

The models are not in the APK (66 MB). This script fetches the pinned ones (sha256-checked,
cached in tools/third_party/voiceeval/ by build-custom-launcher.py's voice_eval_models()),
pushes them through /data/local/tmp into the launcher's app-private files dir with the root
shell (chowned to the launcher's uid, SELinux labels restored), and sets the property. The
launcher reads the property when it starts: pass --restart, or restart it yourself.

Off: --off sets the property to 0; the launcher deletes the evaluation's prints when it next
starts. --remove also deletes the models and prints from the robot now.

Usage:
  python3 scripts/push-voice-eval-models.py [--serial SERIAL] [--restart]
  python3 scripts/push-voice-eval-models.py --off [--restart]
  python3 scripts/push-voice-eval-models.py --remove [--restart]
"""
import argparse
import importlib.util
import shlex
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
PACKAGE = "com.miko3.launcher"
APP_DIR = f"/data/data/{PACKAGE}"
EVAL_DIR = f"{APP_DIR}/files/voiceeval"
MODELS_DIR = f"{EVAL_DIR}/models"
STAGING = "/data/local/tmp/miko3-voiceeval"
PROP = "debug.miko3.voice_eval"


def load_build():
    spec = importlib.util.spec_from_file_location("build_custom_launcher", HERE / "build-custom-launcher.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def adb_cmd(serial, *args):
    return ["adb"] + (["-s", serial] if serial else []) + list(args)


def run(cmd, timeout=600):
    try:
        return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    except (OSError, subprocess.TimeoutExpired) as e:
        return subprocess.CompletedProcess(cmd, 1, "", str(e))


def install_script(names):
    """The root shell script that moves the staged models into the launcher's files dir."""
    lines = [
        "set -e",
        f"uid=$(stat -c %u {APP_DIR})",
        f"mkdir -p {MODELS_DIR}",
    ]
    for name in names:
        lines.append(f"cp {STAGING}/{shlex.quote(name)} {MODELS_DIR}/{shlex.quote(name)}")
    lines += [
        f'chown -R "$uid:$uid" {EVAL_DIR}',
        f"chmod 700 {EVAL_DIR} {MODELS_DIR}",
        f"chmod 600 {MODELS_DIR}/*.onnx",
        f"restorecon -R {EVAL_DIR} 2>/dev/null || true",
        f"rm -rf {STAGING}",
        f"ls -l {MODELS_DIR}",
    ]
    return "; ".join(lines)


def remove_script():
    return f"rm -rf {EVAL_DIR} {STAGING}; echo removed"


def restart_cmds(serial):
    return [adb_cmd(serial, "shell", "am", "force-stop", PACKAGE),
            adb_cmd(serial, "shell", "am", "start", "-n", f"{PACKAGE}/.MainActivity")]


def root_shell(serial, script, runner=run):
    """Runs script as root: adbd itself when it is root, else through su 0."""
    who = runner(adb_cmd(serial, "shell", "id", "-u"))
    if who.returncode == 0 and who.stdout.strip() == "0":
        return runner(adb_cmd(serial, "shell", script))
    return runner(adb_cmd(serial, "shell", "su", "0", "sh", "-c", shlex.quote(script)))


def push(serial, paths, runner=run):
    r = runner(adb_cmd(serial, "shell", "mkdir", "-p", STAGING))
    if r.returncode != 0:
        return f"!! cannot make {STAGING}: {r.stderr.strip()}"
    for p in paths:
        print(f"== pushing {p.name} ({p.stat().st_size // (1 << 20)} MB) ==")
        r = runner(adb_cmd(serial, "push", str(p), f"{STAGING}/{p.name}"))
        if r.returncode != 0:
            return f"!! push of {p.name} failed: {(r.stderr or r.stdout).strip()}"
    r = root_shell(serial, install_script([p.name for p in paths]), runner)
    if r.returncode != 0:
        return f"!! installing the models failed: {(r.stderr or r.stdout).strip()}"
    print(r.stdout.strip())
    return None


def set_prop(serial, value, runner=run):
    return runner(adb_cmd(serial, "shell", "setprop", PROP, value))


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--serial")
    ap.add_argument("--restart", action="store_true", help="restart the launcher so it reads the property")
    g = ap.add_mutually_exclusive_group()
    g.add_argument("--off", action="store_true", help="turn the evaluation off (its prints go at the next start)")
    g.add_argument("--remove", action="store_true", help="turn it off and delete the models and prints now")
    args = ap.parse_args(argv)

    if args.off or args.remove:
        set_prop(args.serial, "0")
        if args.remove:
            r = root_shell(args.serial, remove_script())
            print(r.stdout.strip() or r.stderr.strip())
    else:
        paths = load_build().voice_eval_models()
        err = push(args.serial, paths)
        if err:
            print(err, file=sys.stderr)
            return 1
        set_prop(args.serial, "1")
        print(f"== {PROP}=1 ==")
    if args.restart:
        for cmd in restart_cmds(args.serial):
            run(cmd)
        print("== launcher restarted ==")
    else:
        print("Restart the launcher (or pass --restart) so it reads the property.")
    print("Check: adb logcat -s ListenEngine | grep 'voice eval'")
    return 0


if __name__ == "__main__":
    sys.exit(main())
