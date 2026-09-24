#!/usr/bin/env python3
"""
speed-check.py — time stock Piper voices on the robot with sherpa-onnx, and
pick the quality tier to train (robot voice plan U1, R1, R2, AE1).

Pushes sherpa-onnx's command-line TTS and one stock Piper voice per tier to
/data/local/tmp/voicecheck/ over root adb, synthesizes the AE1 sentence and a
~20-word sentence several times at 1, 2, and 4 threads, then repeats the 2- and
4-thread runs while Explore mode has its camera and detector running. Prints
a table (tier, voice, checkpoint, sample rate, threads, idle and loaded times,
RTF, chunk limit) and a recommendation. When no tier gets the AE1 sentence
under 2 s it prints STOP and exits 2 (the plan's stop condition).

The CLI synthesizes a whole sentence before it writes anything, so time to
first audio is the sentence's full synthesis time ("Elapsed seconds"); model
loading is not counted, since the speech service keeps the model loaded.

What runs where:
  - Binary: the sherpa-onnx Android arm64 (termux) build. The official
    linux-aarch64 "static" build is linked against glibc (its interpreter is
    /lib/ld-linux-aarch64.so.1), which Android lacks, so it cannot run here.
    The Android build needs a 64-bit libc++_shared.so (the robot's
    /system/lib64 copy is 32-bit), taken from Termux's libc++ package and
    placed next to the binary, which finds it through its $ORIGIN rpath.
  - Voices: only those whose training checkpoint exists at the same tier in
    rhasspy/piper-checkpoints (checked on Hugging Face at run time).
      low     en_US lessac   (sherpa-onnx tts-models package)
      medium  en_US lessac   (sherpa-onnx tts-models package)
      x_low   es_MX ald      the only x_low checkpoint in piper-checkpoints;
              no English x_low has one and sherpa-onnx has no package for it,
              so the script adds sherpa-onnx's metadata to piper-voices' ONNX.
              It reads English text with Spanish phonemes: a timing proxy for
              the x_low network, not a voice to listen to.
  - Downloads are cached under tools/third_party/voicecheck/ (gitignored).

Explore under load: Explore drives once it has calibration, so the script
keeps the wheels still with the mode's own debug hooks. It starts Explore
with MikoExploreStale on (the brain sees no readings, so it never moves),
checks the raw sensor readings against the calibration for any hazard (a
hazard at start would make it turn away), then lets the readings through
with MikoExploreCurious on, so its first decision is a curiosity scan, which
stands still and opens the camera. The moment the scan starts it sets
MikoExploreFreeze: the brain stops ticking, the stop timer stops the wheels,
and the camera and detector keep running. If the brain logs anything else
first, it freezes and force-stops Explore at once and skips the loaded run.
Explore is always force-stopped and the hooks restored afterwards.

/data/local/tmp/voicecheck/ is removed from the robot at the end, including
after an error or Ctrl-C.

Usage:
  python3 scripts/voice/speed-check.py
  python3 scripts/voice/speed-check.py --runs 5
  python3 scripts/voice/speed-check.py --no-load          # skip the Explore run
  python3 scripts/voice/speed-check.py --serial 10.0.0.5:5555
"""
import argparse
import hashlib
import importlib.util
import io
import json
import re
import shlex
import statistics
import subprocess
import sys
import tarfile
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
CACHE = REPO / "tools" / "third_party" / "voicecheck"
STAGE = CACHE / "stage"

DEFAULT_SERIAL = "192.168.19.74:5555"
REMOTE_DIR = "/data/local/tmp/voicecheck"
TTS_BIN = "sherpa-onnx-offline-tts"

SHERPA_VERSION = "1.13.8"
SHERPA_ANDROID_URL = (f"https://github.com/k2-fsa/sherpa-onnx/releases/download/v{SHERPA_VERSION}/"
                      f"sherpa-onnx-v{SHERPA_VERSION}-android-aarch64-termux-static.tar.bz2")
TTS_MODELS_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-{name}.tar.bz2"
LIBCXX_URL = "https://packages.termux.dev/apt/termux-main/pool/main/libc/libc++/libc++_30_aarch64.deb"
LIBCXX_SHA256 = "53d0b84a7ba7459024257cb94d5b136fe13ef858567f65a8064b35950799f2ca"
PIPER_VOICES_URL = "https://huggingface.co/rhasspy/piper-voices/resolve/main/{path}"
CHECKPOINTS_URL = "https://huggingface.co/datasets/rhasspy/piper-checkpoints/resolve/main/{path}"

AE1 = "Hi there, I don't think we've met!"
LONG = ("When you finish your snack, could you please bring the red ball back "
        "to the play mat near the window?")
BUDGET_S = 2.0          # R1: first sound within 2 seconds
CLAUSE_WORDS = 8        # a comma-length clause
THREADS = (1, 2, 4)     # the MT8167 has 4 cores
LOAD_THREADS = (2, 4)   # 1 thread is over budget even idle
TIER_RANK = {"x_low": 0, "low": 1, "medium": 2, "high": 3}
MIN_FREE_KB = 400 * 1024

EXPLORE_PKG = "com.miko3.mode.explore"
EXPLORE_ACTIVITY = f"{EXPLORE_PKG}/.MainActivity"
EXPLORE_CAL = f"/data/data/{EXPLORE_PKG}/files/explore-calibration.properties"
HOOK_STALE, HOOK_FREEZE, HOOK_CURIOUS, HOOK_RAW = ("MikoExploreStale", "MikoExploreFreeze",
                                                   "MikoExploreCurious", "MikoDmdRaw")
SAFE_NOTES = ("sensors available and lease held", "curiosity stop: scanning")
TOF_FAULT = 16383

ADB_TIMEOUT = 300       # pushing ~160 MB over Wi-Fi
CONNECT_TIMEOUT = 15


class SpeedCheckError(SystemExit):
    """A step failed; the message says what to do."""


@dataclass
class Voice:
    tier: str
    name: str
    checkpoint: str          # path inside rhasspy/piper-checkpoints
    sample_rate: int
    local_dir: Path          # staged directory, pushed as REMOTE_DIR/<its name>
    onnx: str
    note: str = ""

    @property
    def remote_dir(self):
        return f"{REMOTE_DIR}/{self.local_dir.name}"


@dataclass
class Timing:
    elapsed: float
    audio: float
    rtf: float

    @property
    def first_audio(self):
        # The CLI writes nothing until the whole sentence is synthesized.
        return self.elapsed


@dataclass
class Result:
    voice: Voice
    threads: int
    ae1: float               # median time to first audio, AE1 sentence
    long: float              # median time to first audio, ~20-word sentence
    rtf: float               # median RTF on the ~20-word sentence
    limit: int               # longest chunk (words) under BUDGET_S
    loaded_ae1: float = None
    loaded_long: float = None
    loaded_rtf: float = None
    loaded_limit: int = None


# Voices tried, one per tier; checkpoints verified in rhasspy/piper-checkpoints.
VOICE_SPECS = [
    dict(tier="x_low", name="es_MX-ald-x_low", checkpoint="es/es_MX/ald/x_low/epoch=1953-step=244064.ckpt",
         source="piper-voices", piper_path="es/es_MX/ald/x_low/es_MX-ald-x_low",
         note="Spanish voice; English text read with es-419 phonemes; timing proxy only"),
    dict(tier="low", name="en_US-lessac-low", checkpoint="en/en_US/lessac/low/epoch=2307-step=558536.ckpt",
         source="sherpa"),
    dict(tier="medium", name="en_US-lessac-medium",
         checkpoint="en/en_US/lessac/medium/epoch=2164-step=1355540.ckpt", source="sherpa"),
]


# ---------------------------------------------------------------- adb helpers

def adb_cmd(serial, *args):
    return ["adb", "-s", serial] + list(args)


def _run(cmd, timeout):
    print("  $ " + " ".join(cmd)[:200], flush=True)
    try:
        return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, stdin=subprocess.DEVNULL)
    except FileNotFoundError:
        raise SpeedCheckError("!! adb not found on PATH — install Android platform-tools "
                              "(brew install android-platform-tools) and re-run.")
    except subprocess.TimeoutExpired:
        raise SpeedCheckError(f"!! adb timed out after {timeout}s: {' '.join(cmd)[:200]}\n"
                              "   The robot stopped answering. Check it is powered on and on Wi-Fi, then re-run.")


def adb(serial, *args, check=True, timeout=ADB_TIMEOUT):
    r = _run(adb_cmd(serial, *args), timeout)
    if check and r.returncode != 0:
        raise SpeedCheckError(f"!! adb failed ({r.returncode}): {' '.join(args)[:200]}\n"
                              f"{(r.stdout + r.stderr).strip()}")
    return r


def shell(serial, command, check=True, timeout=ADB_TIMEOUT):
    return adb(serial, "shell", command, check=check, timeout=timeout)


def ensure_reachable(serial):
    detail = ""
    if ":" in serial:
        r = _run(["adb", "connect", serial], CONNECT_TIMEOUT)
        detail = (r.stdout + r.stderr).strip()
    r = _run(adb_cmd(serial, "get-state"), CONNECT_TIMEOUT)
    if r.returncode != 0 or r.stdout.strip() != "device":
        detail = "\n".join(s for s in (detail, (r.stdout + r.stderr).strip()) if s)
        raise SpeedCheckError(
            f"!! robot not reachable over adb at {serial}\n"
            + (f"   adb said: {detail}\n" if detail else "")
            + "   Check the robot is powered on and on Wi-Fi, and that root adbd is listening\n"
              "   (python3 scripts/verify-persistent-adb.py), or pass --serial.")


def parse_df_available_kb(text):
    """The Available column of `df -k`'s first data line."""
    lines = [ln for ln in text.splitlines() if ln.strip() and not ln.startswith("Filesystem")]
    if not lines:
        raise SpeedCheckError(f"!! could not read free space from df:\n{text.strip()}")
    return int(lines[0].split()[3])


def cleanup(serial):
    """Remove everything the check pushed. Never raises: it runs on every exit path."""
    try:
        shell(serial, f"rm -rf {REMOTE_DIR}", check=False, timeout=60)
    except BaseException as e:  # noqa: BLE001 — cleanup must not mask the real error
        print(f"!! could not remove {REMOTE_DIR} from the robot ({e}); remove it by hand:\n"
              f"   adb -s {serial} shell rm -rf {REMOTE_DIR}", flush=True)


# ---------------------------------------------------------------- parsing and maths

ELAPSED_RE = re.compile(r"Elapsed seconds:\s*([0-9.]+)")
AUDIO_RE = re.compile(r"Audio duration:\s*([0-9.]+)")
RTF_RE = re.compile(r"Real-time factor \(RTF\):.*=\s*([0-9.]+)")


def parse_timing(output):
    """Synthesis time, audio length, and RTF from sherpa-onnx-offline-tts's output."""
    e, a, r = ELAPSED_RE.search(output), AUDIO_RE.search(output), RTF_RE.search(output)
    if not (e and a and r):
        raise SpeedCheckError("!! sherpa-onnx-offline-tts printed no timing lines. Its output:\n"
                              + output.strip()[-1500:])
    return Timing(float(e.group(1)), float(a.group(1)), float(r.group(1)))


def words(text):
    return len(text.split())


def chunk_limit(points, budget=BUDGET_S):
    """Longest chunk in words synthesized under budget, from a line through (words, seconds) points."""
    (w1, t1), (w2, t2) = sorted(points)[0], sorted(points)[-1]
    per_word = (t2 - t1) / (w2 - w1) if w2 != w1 else t1 / max(w1, 1)
    fixed = t1 - per_word * w1
    if per_word <= 0:
        return 999 if fixed < budget else 0
    return max(0, int((budget - fixed) / per_word + 1e-9))


def recommend(results):
    """Highest-quality tier whose chunk limit covers a clause; None when no tier gets AE1 under budget."""
    passing = [r for r in results if r.ae1 < BUDGET_S]
    if not passing:
        return None
    covering = [r for r in passing if r.limit >= CLAUSE_WORDS]
    if covering:
        return max(covering, key=lambda r: (TIER_RANK.get(r.voice.tier, -1), r.limit))
    return max(passing, key=lambda r: r.limit)


# ---------------------------------------------------------------- downloads

def fetch(url, dest, sha256=None):
    """Download url to dest once; later runs reuse the cached file."""
    if not dest.exists():
        dest.parent.mkdir(parents=True, exist_ok=True)
        print(f"  downloading {url}", flush=True)
        tmp = dest.with_suffix(dest.suffix + ".part")
        try:
            with urllib.request.urlopen(url, timeout=60) as r, open(tmp, "wb") as f:
                while chunk := r.read(1 << 20):
                    f.write(chunk)
        except (urllib.error.URLError, OSError) as e:
            tmp.unlink(missing_ok=True)
            raise SpeedCheckError(f"!! download failed: {url}\n   {e}\n   Check the Mac's internet connection and re-run.")
        tmp.rename(dest)
    if sha256 and hashlib.sha256(dest.read_bytes()).hexdigest() != sha256:
        dest.unlink()
        raise SpeedCheckError(f"!! {dest.name} does not match its pinned SHA-256; deleted it. Re-run to fetch it again.")
    return dest


def checkpoint_exists(path):
    req = urllib.request.Request(CHECKPOINTS_URL.format(path=path), method="HEAD")
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status == 200
    except urllib.error.HTTPError:
        return False
    except urllib.error.URLError as e:
        raise SpeedCheckError(f"!! could not reach Hugging Face to check {path}: {e}")


def extract_libcxx(deb, dest):
    """libc++_shared.so out of Termux's .deb (an ar archive holding data.tar.xz)."""
    d = deb.read_bytes()
    if d[:8] != b"!<arch>\n":
        raise SpeedCheckError(f"!! {deb} is not a .deb; delete it and re-run.")
    off = 8
    while off < len(d):
        name = d[off:off + 16].decode().strip().rstrip("/")
        size = int(d[off + 48:off + 58])
        body = d[off + 60:off + 60 + size]
        off += 60 + size + (size & 1)
        if name.startswith("data.tar"):
            with tarfile.open(fileobj=io.BytesIO(body)) as t:
                member = next(m for m in t.getmembers() if m.name.endswith("/libc++_shared.so"))
                dest.write_bytes(t.extractfile(member).read())
            return dest
    raise SpeedCheckError(f"!! no data.tar in {deb}; delete it and re-run.")


def extract_members(archive, dest_dir, want):
    """Extract members whose path passes want(relative path) -> new relative path or None."""
    with tarfile.open(archive, "r:bz2") as t:
        for m in t:
            rel = m.name.split("/", 1)[1] if "/" in m.name else ""
            new = want(rel) if rel else None
            if new and m.isfile():
                out = dest_dir / new
                out.parent.mkdir(parents=True, exist_ok=True)
                out.write_bytes(t.extractfile(m).read())


def _pb_varint(n):
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        out.append(b | (0x80 if n else 0))
        if not n:
            return bytes(out)


def _pb_field(num, payload):
    return _pb_varint((num << 3) | 2) + _pb_varint(len(payload)) + payload


def add_sherpa_metadata(onnx_path, config):
    """What sherpa-onnx's scripts/piper/add_meta_data.py adds, without the onnx package: metadata_props
    (ModelProto field 14) appended to the protobuf, which is valid for a repeated field."""
    meta = {"model_type": "vits", "comment": "piper", "language": "Spanish",
            "voice": config["espeak"]["voice"], "version": 1, "has_espeak": 1, "has_g2pw": 0,
            "n_speakers": config["num_speakers"], "sample_rate": config["audio"]["sample_rate"]}
    blob = b"".join(_pb_field(14, _pb_field(1, k.encode()) + _pb_field(2, str(v).encode()))
                    for k, v in meta.items())
    with open(onnx_path, "ab") as f:
        f.write(blob)


def write_tokens(config, path):
    with open(path, "w", encoding="utf-8") as f:
        for sym, ids in config["phoneme_id_map"].items():
            if sym != "\n":
                f.write(f"{sym} {ids[0] if isinstance(ids, list) else ids}\n")


def prepare_assets():
    """Download (cached) and stage the binary, libc++, espeak-ng-data, and voices. Returns (stage, voices)."""
    print("== preparing sherpa-onnx and the voices (cached under tools/third_party/voicecheck) ==", flush=True)
    STAGE.mkdir(parents=True, exist_ok=True)
    binary = STAGE / TTS_BIN
    if not binary.exists():
        tarball = fetch(SHERPA_ANDROID_URL, CACHE / Path(SHERPA_ANDROID_URL).name)
        extract_members(tarball, STAGE, lambda rel: TTS_BIN if rel == f"bin/{TTS_BIN}" else None)
    libcxx = STAGE / "libc++_shared.so"
    if not libcxx.exists():
        extract_libcxx(fetch(LIBCXX_URL, CACHE / Path(LIBCXX_URL).name, LIBCXX_SHA256), libcxx)

    voices = []
    for spec in VOICE_SPECS:
        vdir = STAGE / spec["name"]
        onnx = f"{spec['name']}.onnx"
        if not (vdir / "tokens.txt").exists():
            vdir.mkdir(parents=True, exist_ok=True)
            if spec["source"] == "sherpa":
                url = TTS_MODELS_URL.format(name=spec["name"])
                tarball = fetch(url, CACHE / Path(url).name)
                keep_espeak = not (STAGE / "espeak-ng-data").exists()

                def want(rel, name=spec["name"], keep=keep_espeak):
                    if rel.startswith("espeak-ng-data/"):
                        return rel if keep else None
                    if rel in (f"{name}.onnx", f"{name}.onnx.json", "tokens.txt"):
                        return f"{name}/{rel}"
                    return None
                extract_members(tarball, STAGE, want)
            else:
                base = spec["piper_path"]
                src = fetch(PIPER_VOICES_URL.format(path=base + ".onnx"), CACHE / onnx)
                cfg = fetch(PIPER_VOICES_URL.format(path=base + ".onnx.json"), CACHE / (onnx + ".json"))
                (vdir / onnx).write_bytes(src.read_bytes())
                (vdir / (onnx + ".json")).write_bytes(cfg.read_bytes())
                add_sherpa_metadata(vdir / onnx, json.loads(cfg.read_text()))
                write_tokens(json.loads(cfg.read_text()), vdir / "tokens.txt")
        config = json.loads((vdir / (onnx + ".json")).read_text())
        voices.append(Voice(tier=spec["tier"], name=spec["name"], checkpoint=spec["checkpoint"],
                            sample_rate=int(config["audio"]["sample_rate"]), local_dir=vdir, onnx=onnx,
                            note=spec.get("note", "")))
    if not (STAGE / "espeak-ng-data").is_dir():
        raise SpeedCheckError(f"!! no espeak-ng-data staged; delete {STAGE} and re-run.")
    return STAGE, voices


# ---------------------------------------------------------------- the check

def push_assets(serial, stage, voices):
    free_kb = parse_df_available_kb(shell(serial, "df -k /data/local/tmp").stdout)
    if free_kb < MIN_FREE_KB:
        raise SpeedCheckError(f"!! only {free_kb // 1024} MB free in /data/local/tmp; the check needs "
                              f"{MIN_FREE_KB // 1024} MB. Free some space on the robot and re-run.")
    print(f"== pushing to {REMOTE_DIR} ({free_kb // 1024} MB free) ==", flush=True)
    shell(serial, f"rm -rf {REMOTE_DIR} && mkdir -p {REMOTE_DIR}")
    for item in [stage / TTS_BIN, stage / "libc++_shared.so", stage / "espeak-ng-data"] + [v.local_dir for v in voices]:
        adb(serial, "push", str(item), REMOTE_DIR + "/")
    shell(serial, f"chmod 755 {REMOTE_DIR}/{TTS_BIN}")


def synth(serial, voice, threads, text):
    cmd = (f"cd {REMOTE_DIR} && ./{TTS_BIN} --vits-model={voice.remote_dir}/{voice.onnx} "
           f"--vits-tokens={voice.remote_dir}/tokens.txt --vits-data-dir={REMOTE_DIR}/espeak-ng-data "
           f"--num-threads={threads} --output-filename={REMOTE_DIR}/out.wav {shlex.quote(text)}")
    r = shell(serial, cmd, check=False)
    out = r.stdout + r.stderr
    if r.returncode != 0:
        raise SpeedCheckError(f"!! {TTS_BIN} failed ({r.returncode}) on {voice.name}:\n{out.strip()[-1500:]}")
    return parse_timing(out)


def measure(serial, voice, threads, runs):
    """Median (ae1, long, rtf, limit) over runs of each sentence."""
    ae1 = [synth(serial, voice, threads, AE1) for _ in range(runs)]
    long = [synth(serial, voice, threads, LONG) for _ in range(runs)]
    ae1_t = statistics.median(t.first_audio for t in ae1)
    long_t = statistics.median(t.first_audio for t in long)
    rtf = statistics.median(t.rtf for t in long)
    limit = chunk_limit([(words(AE1), ae1_t), (words(LONG), long_t)])
    print(f"   {voice.name} x{threads}: AE1 {ae1_t:.2f}s  20w {long_t:.2f}s  RTF {rtf:.3f}  "
          f"limit {limit} words", flush=True)
    return ae1_t, long_t, rtf, limit


def sensors_module():
    path = REPO / "scripts" / "qa-explore-sensors.py"
    spec = importlib.util.spec_from_file_location("qa_explore_sensors", path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def reading_hazard(reply, cal, sensors):
    """Why this raw POWER reply would be a hazard to Explore's classifier, or None."""
    fields = sensors.tofir_fields(reply)
    if not fields or fields[0] is None or not fields[0].isdigit():
        return "no ToF value"
    vals = [int(f) if f is not None and f.isdigit() else -1 for f in (fields + [None, None])[:3]]
    tof, irs = vals[0], vals[1:]
    if tof == TOF_FAULT:
        return "ToF fault"
    edge_ir, above = int(cal.get("edgeIr", -1)), cal.get("edgeIrAbove", "true") == "true"
    if edge_ir >= 0 and any(ir >= 0 and (ir > edge_ir if above else ir < edge_ir) for ir in irs):
        return f"IR edge flag {irs}"
    if int(cal.get("edgeTofAbove", -1)) >= 0 and tof > int(cal["edgeTofAbove"]):
        return f"ToF {tof} reads as an edge"
    if int(cal.get("obstacleTofBelow", -1)) >= 0 and tof < int(cal["obstacleTofBelow"]):
        return f"ToF {tof} reads as an obstacle"
    if re.search(r"CPL=2", reply):
        return "CPL=2"
    return None


def brain_log(serial):
    return shell(serial, "logcat -d -s ExploreBrain:V ExploreCamera:V", check=False).stdout


def start_explore_still(serial):
    """Start Explore with its camera and detector running and its wheels still. Returns None or a skip reason."""
    cal_text = shell(serial, f"cat {EXPLORE_CAL}", check=False).stdout
    cal = dict(ln.split("=", 1) for ln in cal_text.splitlines() if "=" in ln)
    if not cal:
        return "Explore has no calibration file, so it never leaves eyes-only and never opens the camera"
    for tag, value in ((HOOK_STALE, "DEBUG"), (HOOK_FREEZE, "INFO"), (HOOK_CURIOUS, "DEBUG"), (HOOK_RAW, "DEBUG")):
        shell(serial, f"setprop log.tag.{tag} {value}")
    shell(serial, f"am force-stop {EXPLORE_PKG}")
    shell(serial, "logcat -c")
    shell(serial, f"am start -n {EXPLORE_ACTIVITY}")
    time.sleep(8)  # the mode comes up, takes the lease, and starts its keepalive; readings stay hidden

    sensors = sensors_module()
    shell(serial, "logcat -c")
    time.sleep(3)
    raw = shell(serial, f"logcat -d -s {HOOK_RAW}:D", check=False).stdout
    replies = [r for s, r in sensors.extract_records(raw) if s == "POWER"]
    shell(serial, f"setprop log.tag.{HOOK_RAW} INFO")
    if len(replies) < 3:
        return f"only {len(replies)} sensor readings in 3 s, so a hazard could not be ruled out"
    hazards = [h for h in (reading_hazard(r, cal, sensors) for r in replies) if h]
    if hazards:
        return f"the sensors show a hazard ({hazards[0]}); Explore would turn away from it at start"

    shell(serial, "logcat -c")
    shell(serial, f"setprop log.tag.{HOOK_STALE} INFO")
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        notes = [ln.split("ExploreBrain", 1)[1].lstrip(" :(0123456789)") for ln in brain_log(serial).splitlines()
                 if "ExploreBrain" in ln and ": " in ln]
        if any("curiosity stop: scanning" in n for n in notes):
            shell(serial, f"setprop log.tag.{HOOK_FREEZE} DEBUG")
            break
        moving = [n for n in notes if not any(s in n for s in SAFE_NOTES)]
        if moving:
            shell(serial, f"setprop log.tag.{HOOK_FREEZE} DEBUG")
            shell(serial, f"am force-stop {EXPLORE_PKG}")
            return f"Explore decided to move before its scan ({moving[0]!r}); frozen and stopped at once"
        time.sleep(0.2)
    else:
        shell(serial, f"setprop log.tag.{HOOK_FREEZE} DEBUG")
        return "Explore never started a curiosity scan within 15 s"

    deadline = time.monotonic() + 40
    while time.monotonic() < deadline:
        if "look in " in brain_log(serial):
            return None
        time.sleep(1)
    return "the camera and detector never produced a look within 40 s (camera HAL wedged?)"


def stop_explore(serial, saved):
    # Force-stop first: clearing MikoExploreFreeze while the mode still runs lets the brain
    # tick again and act on its scan (a first run turned right for a moment doing that).
    shell(serial, f"am force-stop {EXPLORE_PKG}", check=False)
    for tag, value in saved.items():
        shell(serial, f"setprop log.tag.{tag} {value or 'INFO'}", check=False)


def measure_under_load(serial, results, runs):
    """Fill loaded_* on the LOAD_THREADS rows with Explore's camera and detector running.
    Returns a note for the report."""
    print("== under load: Explore with its camera and detector running, wheels still ==", flush=True)
    saved = {t: shell(serial, f"getprop log.tag.{t}", check=False).stdout.strip()
             for t in (HOOK_STALE, HOOK_FREEZE, HOOK_CURIOUS, HOOK_RAW)}
    try:
        skip = start_explore_still(serial)
        if skip:
            return f"skipped: {skip}"
        shell(serial, "logcat -c")
        started = time.monotonic()
        for r in results:
            if r.threads in LOAD_THREADS:
                r.loaded_ae1, r.loaded_long, r.loaded_rtf, r.loaded_limit = measure(serial, r.voice, r.threads, runs)
        log = brain_log(serial)
        looks = log.count("look in ")
        moved = [ln for ln in log.splitlines() if "ExploreBrain" in ln and "stop timer" not in ln]
        note = (f"ran with Explore frozen mid-scan (camera open, detector running, brain frozen, wheels "
                f"stopped by its stop timer); the detector made {looks} looks in "
                f"{time.monotonic() - started:.0f} s of loaded runs")
        if moved:
            note += f"; WARNING: the brain logged {moved[0].strip()!r}"
        return note
    finally:
        stop_explore(serial, saved)


def fmt(x, spec=".2f"):
    return "-" if x is None else format(x, spec)


def print_report(results, best_rows, pick, load_note):
    print("\nTime to first audio = the sentence's whole synthesis time: the CLI synthesizes a whole")
    print("sentence before writing any audio (and the speech service's callback also fires once per")
    print(f"sentence). Times are medians; 'AE1' is \"{AE1}\" ({words(AE1)} words), '20w' is a")
    print(f"{words(LONG)}-word sentence; RTF is on the 20-word sentence; the chunk limit is the longest")
    print(f"chunk in words synthesized under {BUDGET_S:.0f} s, from a line through the two sentences.\n")
    head = ("tier", "voice", "checkpoint", "rate", "thr", "AE1 idle", "20w idle", "AE1 load", "20w load",
            "RTF", "limit")
    rows = [(r.voice.tier, r.voice.name, r.voice.checkpoint, str(r.voice.sample_rate), str(r.threads),
             fmt(r.ae1), fmt(r.long), fmt(r.loaded_ae1), fmt(r.loaded_long),
             fmt(r.rtf, ".3f") + ("" if r.loaded_rtf is None else f" / {r.loaded_rtf:.3f}"),
             str(r.limit) + ("" if r.loaded_limit is None else f" / {r.loaded_limit}"))
            for r in results]
    widths = [max(len(h), *(len(row[i]) for row in rows)) for i, h in enumerate(head)]
    for row in [head] + rows:
        print("  ".join(c.ljust(w) for c, w in zip(row, widths)))
    for v in {r.voice.name: r.voice for r in results}.values():
        if v.note:
            print(f"\nnote: {v.name}: {v.note}")
    print(f"\nExplore under load: {load_note}")
    print("\nBest thread count per voice: " + ", ".join(f"{r.voice.name} x{r.threads}" for r in best_rows))
    print("\nRecommendation:")
    if pick is None:
        return
    print(f"  train the {pick.voice.tier} tier: {pick.voice.name} at {pick.threads} threads, "
          f"AE1 first audio {pick.ae1:.2f} s idle, chunks up to {pick.limit} words under {BUDGET_S:.0f} s.")
    print(f"  checkpoint: rhasspy/piper-checkpoints {pick.voice.checkpoint}")
    print(f"  sample rate: {pick.voice.sample_rate} Hz (resample U2/U3 audio to this)")
    if pick.limit < CLAUSE_WORDS:
        print(f"  WARNING: no tier covers a {CLAUSE_WORDS}-word clause; the speech service must chunk shorter.")
    loaded = [r for r in results if r.loaded_ae1 is not None]
    if loaded:
        ok = [r for r in loaded if r.loaded_ae1 < BUDGET_S]
        best = min(loaded, key=lambda r: r.loaded_ae1)
        print("  Under Explore's load (the worst case: the camera and detector run only during its")
        print("  curiosity stops): " + (
            "AE1 under 2 s for " + ", ".join(f"{r.voice.name} x{r.threads} ({r.loaded_ae1:.2f} s, "
                                              f"limit {r.loaded_limit} words)" for r in ok)
            if ok else f"no voice gets AE1 under 2 s; fastest is {best.voice.name} x{best.threads} "
                       f"at {best.loaded_ae1:.2f} s. Speak between curiosity stops, or pause the detector "
                       "while speaking."))


def parse_args(argv=None):
    ap = argparse.ArgumentParser(description="Time stock Piper voices on the robot and pick the tier to train.")
    ap.add_argument("--serial", default=DEFAULT_SERIAL,
                    help=f"adb serial (default: {DEFAULT_SERIAL}; host:port is adb-connected first)")
    ap.add_argument("--runs", type=int, default=3, help="runs per sentence per configuration (default 3)")
    ap.add_argument("--no-load", action="store_true", help="skip the run with Explore's camera and detector busy")
    return ap.parse_args(argv)


def run_check(args):
    stage, voices = prepare_assets()
    for v in voices:
        if not checkpoint_exists(v.checkpoint):
            raise SpeedCheckError(f"!! rhasspy/piper-checkpoints has no {v.checkpoint}; update VOICE_SPECS.")

    print(f"== reaching the robot at {args.serial} ==", flush=True)
    ensure_reachable(args.serial)
    push_assets(args.serial, stage, voices)

    print(f"== timing {len(voices)} voices x threads {THREADS} x {args.runs} runs per sentence ==", flush=True)
    results = []
    for v in voices:
        for n in THREADS:
            results.append(Result(v, n, *measure(args.serial, v, n, args.runs)))
    best_rows = [min((r for r in results if r.voice is v), key=lambda r: (r.long, r.threads)) for v in voices]
    pick = recommend(best_rows)

    load_note = "not run (--no-load)"
    if pick is not None and not args.no_load:
        load_note = measure_under_load(args.serial, results, args.runs)
    elif pick is None:
        load_note = "not run (no tier passed)"

    print_report(results, best_rows, pick, load_note)
    if pick is None:
        print(f"\nSTOP: no Piper tier gets the AE1 sentence to first audio under {BUDGET_S:.0f} s on the robot.")
        print("  Per the plan's stop conditions, do not train; tell the owner these times.")
        return 2
    return 0


def main(argv=None):
    args = parse_args(argv)
    try:
        return run_check(args)
    except KeyboardInterrupt:
        print("\n!! interrupted", flush=True)
        return 130
    finally:
        print(f"== removing {REMOTE_DIR} from the robot ==", flush=True)
        cleanup(args.serial)


if __name__ == "__main__":
    sys.exit(main())
