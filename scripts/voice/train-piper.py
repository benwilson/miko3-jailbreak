#!/usr/bin/env python3
"""
train-piper.py — fine-tune the robot's small Piper voice from the lessac
medium checkpoint on the synthetic dataset (robot voice plan U4, R5, R7,
KTD2, KTD8).

Stack (KTD2): OHF-Voice/piper1-gpl, cloned into voice-work/piper1-gpl and
installed editable into voice-work/.venv-piper with its [train] extras. The
old rhasspy checkpoint (Lightning 1.9) loads there once its hyperparameters
are cleaned: Lightning 2's CLI reads them with torch.load(weights_only=True),
which refuses the PosixPath values, and piper1-gpl's model rejects the old
trainer options such as sample_bytes. --convert-checkpoint writes that cleaned
copy; the weights, optimizer states and epoch counter are kept, so training
resumes at epoch 2165.

Modes:
  --convert-checkpoint  voice-work/checkpoints/epoch=2164-step=1355540.ckpt ->
                        voice-work/checkpoints/lessac-medium-piper1.ckpt
  --preprocess          voice-work/dataset/metadata.csv (`id|text`, still
                        growing while U3 runs) -> voice-work/piper/metadata.csv
                        (`id.wav|text`) for every row whose wav exists. The
                        audio stays in voice-work/dataset/wavs/ (already
                        22050 Hz mono 16-bit).
  --time-trial          train a few steps past the checkpoint on Apple's GPU
                        (MPS), falling back to the CPU, and project the hours
                        for EXTRA_EPOCHS epochs on the full dataset. Over
                        about a day means a rented GPU (R7). Leaves no
                        checkpoint behind.
  --train               the real fine-tune (Mac, or the rented box).
  --rent-manifest       what to upload to a rented NVIDIA box (the synthetic
                        dataset and the converted checkpoint only, never the
                        owner's clips) and the command lines to run there.
  --export CKPT         ONNX + sherpa-onnx's layout (model.onnx, tokens.txt,
                        espeak-ng-data/, README.md) in launcher/assets/voice/.

  voice-work/.venv-piper/bin/python scripts/voice/train-piper.py --convert-checkpoint
  voice-work/.venv-piper/bin/python scripts/voice/train-piper.py --time-trial --limit 384
  python3 scripts/voice/train-piper.py --rent-manifest
  voice-work/.venv-piper/bin/python scripts/voice/train-piper.py --export path/to/last.ckpt
"""
import argparse
import inspect
import json
import os
import shlex
import shutil
import statistics
import subprocess
import sys
import tempfile
import time
from pathlib import Path, PurePath

REPO = Path(__file__).resolve().parents[2]
WORK_ROOT = REPO / "voice-work"
DATASET_DIR = WORK_ROOT / "dataset"
PIPER_DIR = WORK_ROOT / "piper"
PIPER_SRC = WORK_ROOT / "piper1-gpl" / "src"
TRIAL_DIR = WORK_ROOT / "piper-trial"
CHECKPOINTS = WORK_ROOT / "checkpoints"
BASE_CKPT = CHECKPOINTS / "epoch=2164-step=1355540.ckpt"
CKPT = CHECKPOINTS / "lessac-medium-piper1.ckpt"
BASE_CKPT_URL = ("https://huggingface.co/datasets/rhasspy/piper-checkpoints/resolve/main/"
                 "en/en_US/lessac/medium/epoch%3D2164-step%3D1355540.ckpt")
SCRIPT_LINES = REPO / "scripts" / "voice" / "script-lines.txt"
EXPORT_DIR = REPO / "launcher" / "assets" / "voice"
PIPER_REPO = "https://github.com/OHF-Voice/piper1-gpl.git"

SAMPLE_RATE = 22050          # KTD8: medium tier
ESPEAK_VOICE = "en-us"
BATCH_SIZE = 32
EXTRA_EPOCHS = 1000          # past the checkpoint's epoch (external notes: ~1000)
VALIDATION_SPLIT = 0.05
NUM_TEST = 0
VAL_EVERY = 50               # epochs between validation passes
RENT_THRESHOLD_H = 24.0      # R7: "more than about a day"
SHERPA_FILES = ("model.onnx", "tokens.txt", "espeak-ng-data/")


def fail(msg):
    raise SystemExit(f"!! {msg}")


def guard_inside(path, root):
    """Resolve path; refuse it unless it is inside root (symlinks followed)."""
    p, r = Path(path).resolve(), Path(root).resolve()
    if p != r and not p.is_relative_to(r):
        fail(f"{p} is outside {r}")
    return p


# ---------------------------------------------------------------- dataset

def read_metadata(path):
    """(id, text) rows of an LJSpeech `id|text` file. A malformed line, or a
    last line with no newline yet (U3 may still be writing it), is skipped."""
    raw = Path(path).read_text(encoding="utf-8")
    lines = raw.split("\n")
    if not raw.endswith("\n"):
        lines = lines[:-1]
    rows = []
    for line in lines:
        utt, bar, text = line.partition("|")
        if bar and utt.strip() and text.strip():
            rows.append((utt.strip(), text.strip()))
    return rows


def piper_row(utt, text):
    """Piper's single-speaker CSV row, or None when the text would break it."""
    text = text.strip()
    if "|" in text or not text:
        return None
    return f"{utt}.wav|{text}"


def preprocess(dataset_dir, out_dir, limit=None, work_root=None):
    """Write out_dir/metadata.csv for rows whose wav exists; returns the count."""
    out = guard_inside(out_dir, WORK_ROOT if work_root is None else work_root)
    wavs = Path(dataset_dir) / "wavs"
    rows = []
    for utt, text in read_metadata(Path(dataset_dir) / "metadata.csv"):
        row = piper_row(utt, text)
        if row and (wavs / f"{utt}.wav").is_file():
            rows.append(row)
        if limit and len(rows) >= limit:
            break
    out.mkdir(parents=True, exist_ok=True)
    (out / "metadata.csv").write_text("".join(r + "\n" for r in rows), encoding="utf-8")
    return len(rows)


def expected_rows(path=SCRIPT_LINES):
    """Upper bound on the finished dataset: one clip per script line."""
    return sum(1 for l in Path(path).read_text(encoding="utf-8").splitlines() if l.strip())


# ------------------------------------------------------------- checkpoint

def clean_hparams(hparams, accepted):
    """The old checkpoint's hyperparameters that piper1-gpl's model accepts,
    with paths turned into strings (weights_only loading refuses PosixPath)."""
    return {k: (str(v) if isinstance(v, PurePath) else v)
            for k, v in hparams.items() if k in accepted}


def convert_checkpoint(src=BASE_CKPT, dst=CKPT):
    import torch
    from piper.train.vits.lightning import VitsModel
    if not Path(src).is_file():
        fail(f"{src} missing; download it: curl -L -o '{src}' '{BASE_CKPT_URL}'")
    ckpt = torch.load(src, weights_only=False, map_location="cpu")  # trusted: rhasspy's own
    accepted = set(inspect.signature(VitsModel.__init__).parameters) - {"self", "kwargs", "dataset"}
    ckpt["hyper_parameters"] = clean_hparams(ckpt["hyper_parameters"], accepted)
    torch.save(ckpt, dst)
    torch.load(dst, weights_only=True, map_location="cpu")  # what Lightning's CLI does
    print(f"== wrote {dst} (epoch {ckpt['epoch']}, step {ckpt['global_step']}) ==")
    return dst


def ckpt_epoch(path):
    import torch
    return torch.load(path, weights_only=True, map_location="cpu", mmap=True)["epoch"]


# --------------------------------------------------------------- training


def drop_val_mos_callback(main=None):
    """piper1-gpl's second ModelCheckpoint monitors val_mos (UTMOS), which this setup
    never logs; newer Lightning raises instead of skipping, killing the run at the
    first validation. Remove that callback from the local clone (idempotent)."""
    main = Path(main) if main else PIPER_SRC / "piper" / "train" / "__main__.py"
    text = main.read_text()
    start = text.find("    ModelCheckpoint(\n        monitor=\"val_mos\"")
    if start < 0:
        return False
    end = text.index("    ),\n", start) + len("    ),\n")
    main.write_text(text[:start] + text[end:])
    return True

def fit_args(csv, audio_dir, cache_dir, config_path, ckpt, max_epochs, accelerator,
             batch_size=BATCH_SIZE, root_dir=None, validation_split=VALIDATION_SPLIT,
             num_test=NUM_TEST, extra=()):
    """Arguments for `python -m piper.train fit`."""
    args = ["--data.voice_name", "miko", "--data.csv_path", str(csv),
            "--data.audio_dir", str(audio_dir), "--model.sample_rate", str(SAMPLE_RATE),
            "--data.espeak_voice", ESPEAK_VOICE, "--data.cache_dir", str(cache_dir),
            "--data.config_path", str(config_path), "--data.batch_size", str(batch_size),
            "--data.validation_split", str(validation_split),
            "--data.num_test_examples", str(num_test),
            "--model.mos_metric", "none",
            "--trainer.accelerator", accelerator, "--trainer.devices", "1",
            "--trainer.precision", "32", "--trainer.max_epochs", str(max_epochs)]
    if root_dir:
        args += ["--trainer.default_root_dir", str(root_dir)]
    return args + list(extra) + ["--ckpt_path", str(ckpt)]


def steps_per_epoch(rows, batch_size, validation_split=VALIDATION_SPLIT, num_test=NUM_TEST):
    """Training batches per epoch, as piper1-gpl splits and batches the rows."""
    valid = int(rows * validation_split)
    test = min(num_test, max(0, rows - valid - 1))
    train = rows - valid - test
    return train // batch_size if train > batch_size else 1


def step_seconds(times, warmup=1):
    """Median seconds per step, after the warm-up steps (graph build, caches)."""
    steady = list(times)[warmup:]
    if not steady:
        raise ValueError(f"need more than {warmup} timed steps, got {len(times)}")
    return statistics.median(steady)


def project(step_s, full_rows, batch_size=BATCH_SIZE, epochs=EXTRA_EPOCHS,
            validation_split=VALIDATION_SPLIT, num_test=NUM_TEST):
    steps = steps_per_epoch(full_rows, batch_size, validation_split, num_test)
    epoch_s = steps * step_s
    return {"steps_per_epoch": steps, "epoch_s": epoch_s, "total_h": epoch_s * epochs / 3600}


def needs_rent(total_h, threshold_h=RENT_THRESHOLD_H):
    return total_h > threshold_h


def time_trial(runner, full_rows, batch_size=BATCH_SIZE, steps=12, mps_available=True,
               epochs=EXTRA_EPOCHS, **runner_kw):
    """Time `steps` training steps with runner(accelerator, ...) -> step times,
    on MPS first and on the CPU if MPS fails; print and return the projection."""
    result = {"mps_error": None}
    times = None
    if mps_available:
        try:
            times = runner("mps", batch_size=batch_size, steps=steps, **runner_kw)
            result["accelerator"] = "mps"
        except Exception as e:  # any MPS gap: unsupported op, OOM, driver error
            result["mps_error"] = f"{type(e).__name__}: {e}"
            print(f"-- MPS failed ({result['mps_error']}); retrying on the CPU")
    if times is None:
        times = runner("cpu", batch_size=batch_size, steps=steps, **runner_kw)
        result["accelerator"] = "cpu"
    result["times"] = list(times)
    result["step_s"] = step_seconds(times)
    result.update(project(result["step_s"], full_rows, batch_size, epochs))
    result["rent"] = needs_rent(result["total_h"])
    print(f"== time trial on {result['accelerator']}: "
          f"{result['step_s']:.2f} s/step at batch {batch_size} "
          f"(steps: {', '.join(f'{t:.1f}' for t in times)}) ==")
    print(f"   {full_rows} rows -> {result['steps_per_epoch']} steps/epoch -> "
          f"{result['epoch_s'] / 60:.1f} min/epoch -> {result['total_h']:.1f} h "
          f"for {epochs} epochs (validation every {VAL_EVERY} epochs not included)")
    if result["rent"]:
        print(f"   over {RENT_THRESHOLD_H:.0f} h: training needs a rented GPU, with the "
              "owner's go-ahead for the cost (R7). See --rent-manifest.")
    else:
        print(f"   under {RENT_THRESHOLD_H:.0f} h: train on this Mac (--train).")
    return result


def lightning_runner(accelerator, batch_size, steps, csv, audio_dir, cache_dir, ckpt):
    """Run piper1-gpl's own Lightning CLI in-process for `steps` steps of one
    epoch past the checkpoint and return each step's wall time."""
    import torch
    from lightning.pytorch.callbacks import Callback
    from piper.train.__main__ import VitsLightningCLI, _DEFAULT_CALLBACKS
    from piper.train.vits.dataset import VitsDataModule
    from piper.train.vits.lightning import VitsModel

    def sync():
        if accelerator == "mps":
            torch.mps.synchronize()
        elif accelerator == "gpu":
            torch.cuda.synchronize()

    class StepTimer(Callback):
        def __init__(self):
            self.times, self._t0 = [], None

        def on_train_batch_start(self, *a):
            sync()
            self._t0 = time.perf_counter()

        def on_train_batch_end(self, *a):
            sync()
            self.times.append(time.perf_counter() - self._t0)

    timer = StepTimer()
    root = Path(tempfile.mkdtemp(prefix="trial-", dir=guard_inside(TRIAL_DIR, WORK_ROOT)))
    args = fit_args(csv, audio_dir, cache_dir, Path(cache_dir).parent / "config.json", ckpt,
                    max_epochs=ckpt_epoch(ckpt) + 2, accelerator=accelerator,
                    batch_size=batch_size, root_dir=root, validation_split=0.0,
                    extra=["--trainer.limit_train_batches", str(steps),
                           "--trainer.limit_val_batches", "0",
                           "--trainer.enable_progress_bar", "false"])
    try:
        VitsLightningCLI(VitsModel, VitsDataModule, args=["fit"] + args,
                         trainer_defaults={"max_epochs": -1,
                                           "callbacks": _DEFAULT_CALLBACKS + [timer]})
    finally:
        shutil.rmtree(root, ignore_errors=True)  # last.ckpt is ~800 MB
    if len(timer.times) < steps:
        raise RuntimeError(f"only {len(timer.times)} of {steps} steps ran "
                           "(too few rows for this batch size?)")
    return timer.times


# ------------------------------------------------------------ rented GPU

def rent_manifest(dataset_dir=DATASET_DIR, ckpt=CKPT, work_root=None):
    """Files to upload (paths on this Mac) and the command lines for the box.
    Only rows of voice-work/dataset/ and the converted checkpoint go up."""
    work = Path(WORK_ROOT if work_root is None else work_root).resolve()
    dataset = guard_inside(dataset_dir, work / "dataset")
    ckpt = guard_inside(ckpt, work / "checkpoints")
    uploads = [dataset / "metadata.csv"]
    uploads += [guard_inside(dataset / "wavs" / f"{u}.wav", dataset)
                for u, _ in read_metadata(dataset / "metadata.csv")
                if (dataset / "wavs" / f"{u}.wav").is_file()]
    uploads.append(ckpt)
    repo = work.parent
    rel = [str(p.relative_to(repo)) for p in uploads]
    script = str(Path(__file__).resolve().relative_to(REPO))
    py = "voice-work/.venv-piper/bin/python"
    commands = [
        "# on this Mac, from the repo root: pack the upload (repo-relative paths)",
        "tar -czf voice-work/rent-upload.tgz -T voice-work/rent-upload.txt",
        "# on the rented box (Ubuntu + CUDA, Python 3.11), in an empty directory ~/miko",
        "mkdir -p ~/miko && tar -xzf rent-upload.tgz -C ~/miko && cd ~/miko",
        f"git clone --depth 1 {PIPER_REPO} voice-work/piper1-gpl",
        "sudo apt-get install -y build-essential cmake ninja-build",
        "python3 -m venv voice-work/.venv-piper",
        f"{py} -m pip install -e 'voice-work/piper1-gpl[train]' scikit-build",
        f"(cd voice-work/piper1-gpl && PATH=$PWD/../.venv-piper/bin:$PATH "
        "./build_monotonic_align.sh && ../.venv-piper/bin/python setup.py build_ext --inplace)",
        f"{py} {script} --preprocess",
        f"{py} {script} --time-trial --accelerator gpu   # sanity check, a few minutes",
        f"nohup {py} {script} --train --accelerator gpu > voice-work/train.log 2>&1 &",
        "# equivalent raw command:",
        f"{py} -m piper.train fit " + " ".join(shlex.quote(a) for a in fit_args(
            "voice-work/piper/metadata.csv", "voice-work/dataset/wavs", "voice-work/piper/cache",
            "voice-work/piper/config.json", "voice-work/checkpoints/lessac-medium-piper1.ckpt",
            max_epochs=f"<epoch+1+{EXTRA_EPOCHS}>", accelerator="gpu",
            root_dir="voice-work/piper/train",
            extra=["--trainer.check_val_every_n_epoch", str(VAL_EVERY)])),
        "# bring back: voice-work/piper/config.json and the chosen "
        "voice-work/piper/train/lightning_logs/version_*/checkpoints/*.ckpt",
        "# then delete the box (it holds the synthetic dataset)",
    ]
    return {"upload": [str(p) for p in uploads], "upload_rel": rel, "tools": [script],
            "commands": commands}


# ----------------------------------------------------------------- export

def missing_sherpa_files(out_dir):
    """The sherpa-onnx voice files absent from out_dir (a trailing / is a directory)."""
    out = Path(out_dir)
    return [f for f in SHERPA_FILES
            if not ((out / f[:-1]).is_dir() if f.endswith("/") else (out / f).is_file())]


def tokens_text(config):
    """sherpa-onnx's tokens.txt: one `symbol id` line per single-codepoint
    phoneme. piper1-gpl's map also lists diphthongs ("aɪ"), which sherpa-onnx
    cannot read; training only uses them when the config sets vowel_clusters."""
    if config.get("vowel_clusters"):
        fail("config has vowel_clusters; sherpa-onnx phonemizes one codepoint per token")
    return "".join(f"{sym} {ids[0]}\n" for sym, ids in config["phoneme_id_map"].items()
                   if len(sym) == 1)


def sherpa_metadata(config):
    return {"model_type": "vits", "comment": "piper",
            "language": config.get("language", {}).get("name_english", "English"),
            "voice": config["espeak"]["voice"], "has_espeak": 1,
            "n_speakers": config["num_speakers"],
            "sample_rate": config["audio"]["sample_rate"]}


def onnx_add_metadata(path, data):
    import onnx
    model = onnx.load(path)
    del model.metadata_props[:]
    for k, v in data.items():
        m = model.metadata_props.add()
        m.key, m.value = k, str(v)
    onnx.save(model, path)


def default_espeak_data():
    """sherpa-onnx's own espeak-ng-data, from the placeholder voice the
    launcher build already caches (downloaded and checksummed if absent)."""
    import importlib.util
    sys.path.insert(0, str(REPO / "scripts"))  # for its build_common import
    spec = importlib.util.spec_from_file_location(
        "build_custom_launcher", REPO / "scripts" / "build-custom-launcher.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return Path(mod.placeholder_voice()) / "voice" / "espeak-ng-data"


README = """# Robot voice (Piper, medium, 22050 Hz)

Made by scripts/voice/train-piper.py (voice plan U4): the rhasspy lessac medium
checkpoint (epoch 2164) fine-tuned with OHF-Voice/piper1-gpl on synthetic
speech from scripts/voice/synthesize-dataset.py, then exported to ONNX with
sherpa-onnx's Piper metadata. No training audio or transcripts live here; the
dataset stays in the gitignored voice-work/.

- model.onnx: from {ckpt}
- tokens.txt: the voice config's phoneme_id_map
- espeak-ng-data/: sherpa-onnx's bundle
"""


# piper1-gpl's export_onnx calls torch.onnx.export without dynamo=False; torch
# 2.9+ then takes the torch.export path, which fails on VITS's data-dependent
# shapes. The legacy TorchScript exporter works, so force it.
EXPORT_SHIM = ("import functools, runpy, sys, torch\n"
               "torch.onnx.export = functools.partial(torch.onnx.export, dynamo=False)\n"
               "sys.argv[0] = 'export_onnx'\n"
               "runpy.run_module('piper.train.export_onnx', run_name='__main__')\n")


def export(ckpt, config_path, out_dir, espeak_src, runner=None, add_metadata=onnx_add_metadata):
    out = Path(out_dir)
    config = json.loads(Path(config_path).read_text(encoding="utf-8"))
    run = runner or (lambda cmd: subprocess.run(cmd, check=True))
    tmp = out.with_name(out.name + ".tmp")
    shutil.rmtree(tmp, ignore_errors=True)
    tmp.mkdir(parents=True)
    model = tmp / "model.onnx"
    run([sys.executable, "-c", EXPORT_SHIM, "--checkpoint", str(ckpt), "--output-file", str(model)])
    if not model.is_file():
        shutil.rmtree(tmp, ignore_errors=True)
        fail(f"export wrote no {model}")
    add_metadata(str(model), sherpa_metadata(config))
    (tmp / "tokens.txt").write_text(tokens_text(config), encoding="utf-8")
    shutil.copytree(espeak_src, tmp / "espeak-ng-data")
    (tmp / "README.md").write_text(README.format(ckpt=Path(ckpt).name), encoding="utf-8")
    missing = missing_sherpa_files(tmp)
    if missing:
        fail(f"export is missing {missing}")
    shutil.rmtree(out, ignore_errors=True)
    tmp.rename(out)
    print(f"== voice exported to {out} ==")
    return out


# ------------------------------------------------------------------- main

def mps_ok():
    import torch
    return torch.backends.mps.is_available()


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    mode = ap.add_mutually_exclusive_group(required=True)
    mode.add_argument("--convert-checkpoint", action="store_true")
    mode.add_argument("--preprocess", action="store_true")
    mode.add_argument("--time-trial", action="store_true")
    mode.add_argument("--train", action="store_true")
    mode.add_argument("--rent-manifest", action="store_true")
    mode.add_argument("--export", metavar="CKPT")
    ap.add_argument("--dataset", default=str(DATASET_DIR))
    ap.add_argument("--limit", type=int, help="rows for the time trial (default steps x batch)")
    ap.add_argument("--steps", type=int, default=12, help="timed steps (the first is warm-up)")
    ap.add_argument("--batch-size", type=int, default=BATCH_SIZE)
    ap.add_argument("--full-rows", type=int, help="rows to project for (default: script lines)")
    ap.add_argument("--resume", type=Path, help="checkpoint to continue --train from (default: the converted base)")
    ap.add_argument("--extra-epochs", type=int, default=EXTRA_EPOCHS,
                    help=f"epochs to train past the checkpoint for --train (default {EXTRA_EPOCHS})")
    ap.add_argument("--accelerator", choices=("auto", "mps", "cpu", "gpu"), default="auto")
    ap.add_argument("--config", default=str(PIPER_DIR / "config.json"))
    ap.add_argument("--out", default=str(EXPORT_DIR))
    ap.add_argument("--espeak-data")
    a = ap.parse_args(argv)
    os.environ.setdefault("PYTORCH_ENABLE_MPS_FALLBACK", "1")

    if a.convert_checkpoint:
        convert_checkpoint()
    elif a.preprocess:
        n = preprocess(a.dataset, PIPER_DIR)
        print(f"== {n} rows -> {PIPER_DIR / 'metadata.csv'} ==")
    elif a.time_trial:
        if not CKPT.is_file():
            convert_checkpoint()
        limit = a.limit or a.steps * a.batch_size
        n = preprocess(a.dataset, TRIAL_DIR, limit=limit)
        if n < a.steps * a.batch_size:
            fail(f"only {n} finished rows; {a.steps} steps at batch {a.batch_size} "
                 f"need {a.steps * a.batch_size}")
        kw = dict(csv=TRIAL_DIR / "metadata.csv", audio_dir=Path(a.dataset) / "wavs",
                  cache_dir=TRIAL_DIR / "cache", ckpt=CKPT)
        full = a.full_rows or expected_rows()
        if a.accelerator in ("auto", "mps"):
            r = time_trial(lightning_runner, full, a.batch_size, a.steps, mps_ok(), **kw)
        else:
            r = time_trial(lambda acc, **k: lightning_runner(a.accelerator, **k), full,
                           a.batch_size, a.steps, mps_available=False, **kw)
        (TRIAL_DIR / "report.json").write_text(json.dumps(r, indent=2) + "\n")
    elif a.train:
        if not CKPT.is_file():
            convert_checkpoint()
        acc = a.accelerator if a.accelerator != "auto" else ("mps" if mps_ok() else "cpu")
        drop_val_mos_callback()
        args = fit_args(PIPER_DIR / "metadata.csv", Path(a.dataset) / "wavs", PIPER_DIR / "cache",
                        PIPER_DIR / "config.json", a.resume or CKPT, ckpt_epoch(CKPT) + 1 + a.extra_epochs,
                        acc, a.batch_size, root_dir=PIPER_DIR / "train",
                        extra=["--trainer.check_val_every_n_epoch", str(VAL_EVERY)])
        os.execv(sys.executable, [sys.executable, "-m", "piper.train", "fit"] + args)
    elif a.rent_manifest:
        m = rent_manifest(a.dataset)
        listing = WORK_ROOT / "rent-upload.txt"
        listing.write_text("".join(p + "\n" for p in m["upload_rel"] + m["tools"]))
        size = sum(Path(p).stat().st_size for p in m["upload"]) / 1e6
        print(f"== upload {len(m['upload'])} files, {size:.0f} MB (listed in {listing}) ==")
        print("   voice-work/dataset/metadata.csv, voice-work/dataset/wavs/*.wav, "
              f"{CKPT.relative_to(REPO)}; tool: {m['tools'][0]}")
        print("   never uploaded: voice-work/clips, reference, samples*")
        print("\n".join(m["commands"]))
    elif a.export:
        espeak = Path(a.espeak_data) if a.espeak_data else default_espeak_data()
        export(a.export, a.config, a.out, espeak)


if __name__ == "__main__":
    main()
