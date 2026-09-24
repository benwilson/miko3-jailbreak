#!/usr/bin/env python3
"""
synthesize-dataset.py — read text in the owner's voice with a voice cloner,
to build the synthetic training set for the small Piper voice (robot voice
plan U3, R4, R6, R12; KTD1, KTD2).

Modes:
  --samples   render about 10 sample lines for the owner's listening gate
              (R6) and time the cloner. Writes voice-work/samples/NN-<slug>.wav
              (22050 Hz, mono, 16-bit) and voice-work/samples/report.json.
  --dataset   NOT BUILT YET. Will read scripts/voice/script-lines.txt, render
              every line, re-transcribe each clip with mlx-whisper, drop clips
              whose transcript drifts from the prompt (KTD2), and write an
              LJSpeech-style voice-work/dataset/metadata.csv (`wav|text`).
              render_line(), resample(), audio_problems(), write_wav() and
              word_match() are the pieces it will reuse.

Backends (KTD1):
  chatterbox  Chatterbox (MIT), the primary. Needs no reference transcript.
              PyTorch on Apple's GPU (MPS). Runs in voice-work/.venv-clone
              (torch 2.6; voice-work/.venv keeps torch 2.0.1 for DeepFilterNet).
  f5          F5-TTS on MLX, the fallback. Needs the exact reference
              transcript, taken from voice-work/clips/manifest.json. Runs in
              voice-work/.venv-f5.

Every output is checked to stay inside the gitignored voice-work/ directory.
The owner's clips are read in place and never copied elsewhere.

  voice-work/.venv-clone/bin/python scripts/voice/synthesize-dataset.py --samples
  voice-work/.venv-f5/bin/python scripts/voice/synthesize-dataset.py --samples \\
      --backend f5 --out voice-work/samples-f5 --limit 3
"""
import argparse
import difflib
import json
import math
import os
import re
import sys
import time
from fractions import Fraction
from pathlib import Path

import numpy as np

REPO = Path(__file__).resolve().parents[2]
WORK_ROOT = REPO / "voice-work"
REFERENCE = WORK_ROOT / "reference" / "reference.wav"
MANIFEST = WORK_ROOT / "clips" / "manifest.json"
SAMPLES_DIR = WORK_ROOT / "samples"

OUT_RATE = 22050             # Piper medium tier (KTD8)
MIN_SECONDS = 0.3
SILENT_PEAK = 1e-3           # peak under this (about -60 dBFS) is silence
CLIP_LEVEL = 0.999
MAX_CLIPPED_FRACTION = 1e-3
GAP_SECONDS = 1.5            # a pause this long inside a line is suspicious
GAP_BELOW_PEAK_DB = 40.0
PEAK_CEILING_DBFS = -1.0     # Chatterbox peaks near 0 dBFS; resampling can overshoot
FRAME_MS = 10
SLUG_MAX = 48

SAMPLE_LINES = [
    "Hi there, I don't think we've met!",
    "Ooh, is that a cat? You look very comfortable up there.",
    "I'll scan my database to find out how to do it.",
    "That's a nice laptop. Is it faster than me?",
    "One, two, three, four, five.",
    "Wait... did you just move that chair?",
    "I found a mug, two books, and a very suspicious sock under the table.",
    "Good morning! Did you sleep well?",
    "Hmm, I'm not sure what that is. Can you show me again?",
    # 25 words:
    "When the sun goes down and the house gets quiet, I like to roll around "
    "the kitchen and look for crumbs that nobody has noticed.",
]


class OutsideWorkDir(Exception):
    pass


class BadAudio(Exception):
    pass


# ---------------------------------------------------------------- names, paths

def slugify(text, max_len=SLUG_MAX):
    """Lower-case words joined by hyphens; apostrophes split words like U2 names."""
    words = re.findall(r"[a-z0-9]+", text.lower())
    slug = ""
    for w in words:
        nxt = w if not slug else f"{slug}-{w}"
        if len(nxt) > max_len:
            break
        slug = nxt
    if not slug and words:
        slug = words[0][:max_len]
    return slug or "line"


def sample_filename(index, text):
    return f"{index + 1:02d}-{slugify(text)}.wav"


def guard_output(path, work_root=None):
    """Resolve path; refuse it unless it is inside work_root (symlinks followed)."""
    root = Path(WORK_ROOT if work_root is None else work_root).resolve()
    p = Path(path).resolve()
    if p != root and root not in p.parents:
        raise OutsideWorkDir(f"refusing to write {p}: outside {root}")
    return p


# ---------------------------------------------------------------------- audio

def resample(wav, src_rate, dst_rate=OUT_RATE):
    """Flatten to mono float32 and resample with a polyphase filter."""
    x = np.asarray(wav, dtype=np.float32)
    if x.ndim > 1:
        x = x.reshape(-1) if 1 in x.shape else x.mean(axis=0 if x.shape[0] < x.shape[1] else 1)
    if src_rate == dst_rate:
        return x
    from scipy.signal import resample_poly
    ratio = Fraction(dst_rate, src_rate)
    return resample_poly(x, ratio.numerator, ratio.denominator).astype(np.float32)


def limit_peak(wav, ceiling_dbfs=PEAK_CEILING_DBFS):
    """Scale the whole clip down if its peak is over the ceiling (no hard clipping)."""
    x = np.asarray(wav, dtype=np.float32)
    ceiling = 10 ** (ceiling_dbfs / 20)
    peak = float(np.max(np.abs(x))) if len(x) else 0.0
    return x * (ceiling / peak) if peak > ceiling else x


def audio_problems(wav, rate):
    """Plain-language problems with a rendered clip; empty when it looks usable."""
    x = np.asarray(wav, dtype=np.float32)
    if not np.all(np.isfinite(x)):
        return ["non-finite samples"]
    problems = []
    seconds = len(x) / rate
    if seconds < MIN_SECONDS:
        problems.append(f"too short ({seconds:.2f} s)")
    peak = float(np.max(np.abs(x))) if len(x) else 0.0
    if peak < SILENT_PEAK:
        problems.append("silent")
        return problems
    clipped = float(np.mean(np.abs(x) >= CLIP_LEVEL))
    if clipped > MAX_CLIPPED_FRACTION:
        problems.append(f"clipping ({clipped:.2%} of samples)")
    frame = max(1, rate * FRAME_MS // 1000)
    n = len(x) // frame
    if n:
        rms = np.sqrt(np.mean(x[: n * frame].reshape(n, frame) ** 2, axis=1) + 1e-12)
        db = 20 * np.log10(rms)
        quiet = db < db.max() - GAP_BELOW_PEAK_DB
        loud = np.flatnonzero(~quiet)
        run = longest = 0
        for q in quiet[loud[0]: loud[-1] + 1]:
            run = run + 1 if q else 0
            longest = max(longest, run)
        if longest * FRAME_MS / 1000 >= GAP_SECONDS:
            problems.append(f"{longest * FRAME_MS / 1000:.1f} s gap inside the line")
    return problems


def write_wav(path, wav, rate, work_root=None):
    import soundfile as sf
    if rate != OUT_RATE:
        raise ValueError(f"refusing to write {rate} Hz; the dataset is {OUT_RATE} Hz")
    p = guard_output(path, work_root)
    p.parent.mkdir(parents=True, exist_ok=True)
    sf.write(str(p), np.clip(np.asarray(wav, dtype=np.float32), -1.0, 1.0), rate,
             subtype="PCM_16")
    return p


def check_wav(path):
    """Confirm a file is 22050 Hz mono 16-bit PCM WAV; return its length in seconds."""
    import soundfile as sf
    info = sf.info(str(path))
    got = (info.format, info.samplerate, info.channels, info.subtype)
    if got != ("WAV", OUT_RATE, 1, "PCM_16"):
        raise BadAudio(f"{path}: {got}, want ('WAV', {OUT_RATE}, 1, 'PCM_16')")
    return info.frames / info.samplerate


def word_match(prompt, transcript):
    """Word-level similarity (0-1) of a prompt and its re-transcription."""
    def words(s):
        return re.findall(r"[a-z0-9']+", s.lower().replace("’", "'"))
    return difflib.SequenceMatcher(None, words(prompt), words(transcript)).ratio()


# ---------------------------------------------------------------------- speed

def real_time_factor(gen_seconds, audio_seconds):
    """Seconds of compute per second of audio (above 1 is slower than real time)."""
    if audio_seconds <= 0:
        raise ValueError("no audio was generated")
    return gen_seconds / audio_seconds


def projected_hours(rtf, audio_hours, keep_rate=1.0):
    """Wall-clock hours to end up with audio_hours of kept audio."""
    return rtf * audio_hours / keep_rate


# ------------------------------------------------------------------- backends

class ChatterboxBackend:
    name = "chatterbox"

    def __init__(self, reference, device=None, exaggeration=0.5, cfg_weight=0.5):
        os.environ.setdefault("PYTORCH_ENABLE_MPS_FALLBACK", "1")
        import torch
        from chatterbox.tts import ChatterboxTTS
        if device is None:
            device = "mps" if torch.backends.mps.is_available() else "cpu"
        self.device = device
        self.model = ChatterboxTTS.from_pretrained(device=device)
        self.model.prepare_conditionals(str(reference), exaggeration=exaggeration)
        self.exaggeration = exaggeration
        self.cfg_weight = cfg_weight

    def generate(self, text):
        wav = self.model.generate(text, exaggeration=self.exaggeration,
                                  cfg_weight=self.cfg_weight)
        return wav.detach().cpu().numpy(), self.model.sr


def reference_transcript(manifest_path=MANIFEST):
    """The exact words of reference.wav, in order, from the U2 manifest (for F5)."""
    m = json.loads(Path(manifest_path).read_text())
    by_file = {c["file"]: c["transcript"] for c in m["clips"]}
    return " ".join(by_file[f].strip() for f in m["reference"]["clips"])


class F5Backend:
    name = "f5-mlx"
    RATE = 24000

    def __init__(self, reference, ref_text, work_root=None, quantization_bits=None):
        import mlx.core as mx
        import soundfile as sf
        from f5_tts_mlx.cfm import F5TTS
        from f5_tts_mlx.generate import FRAMES_PER_SEC, estimated_duration
        from f5_tts_mlx.utils import convert_char_to_pinyin
        self._estimate, self._fps = estimated_duration, FRAMES_PER_SEC
        self.mx, self._pinyin = mx, convert_char_to_pinyin
        # f5-tts-mlx 0.2.x passes mx.array scalars as a shape to
        # mx.random.normal, which mlx 0.32 rejects; coerce them to ints.
        normal = mx.random.normal
        if not getattr(normal, "_int_shape_shim", False):
            def _normal(shape=(), *a, **k):
                return normal(tuple(int(d) for d in shape), *a, **k)
            _normal._int_shape_shim = True
            mx.random.normal = _normal
        self.device = "mlx"
        audio, sr = sf.read(str(reference), dtype="float32")
        audio = resample(audio, sr, self.RATE)
        rms = float(np.sqrt(np.mean(audio ** 2)))
        if rms < 0.1:  # F5's TARGET_RMS
            audio = audio * 0.1 / rms
        self.ref = mx.array(audio)
        self.ref_text = ref_text
        self.model = F5TTS.from_pretrained("lucasnewman/f5-tts-mlx",
                                           quantization_bits=quantization_bits)

    def generate(self, text):
        mx = self.mx
        wave, _ = self.model.sample(
            mx.expand_dims(self.ref, axis=0),
            text=self._pinyin([self.ref_text + " " + text]),
            # total frames (reference + new speech), from the library's own
            # text-length heuristic; its duration predictor undershoots on a
            # 12.6 s reference and returns almost nothing past it.
            duration=int(self._estimate(self.ref, self.ref_text, text) * self._fps), steps=8, method="rk4", cfg_strength=2.0,
            sway_sampling_coef=-1.0)
        wave = wave[self.ref.shape[0]:]
        mx.eval(wave)
        return np.array(wave), self.RATE


def make_backend(name, reference, exaggeration=0.5, cfg_weight=0.5):
    if name == "chatterbox":
        # exaggeration: emotional intensity (0.5 neutral); lower cfg_weight: livelier pacing.
        return ChatterboxBackend(reference, exaggeration=exaggeration, cfg_weight=cfg_weight)
    if name == "f5":
        return F5Backend(reference, reference_transcript())
    raise SystemExit(f"unknown backend {name}")


# ------------------------------------------------------------------ rendering

def render_line(backend, text):
    """Render one line; return (22050 Hz mono audio, compute seconds)."""
    t0 = time.perf_counter()
    wav, rate = backend.generate(text)
    took = time.perf_counter() - t0
    return limit_peak(resample(wav, rate, OUT_RATE)), took


def render_samples(backend, lines, out_dir, work_root=None):
    out = guard_output(out_dir, work_root)
    guard_output(out / "report.json", work_root)
    samples, gen_total, audio_total = [], 0.0, 0.0
    for i, text in enumerate(lines):
        audio, took = render_line(backend, text)
        path = write_wav(out / sample_filename(i, text), audio, OUT_RATE, work_root)
        seconds = check_wav(path)
        problems = audio_problems(audio, OUT_RATE)
        gen_total += took
        audio_total += seconds
        samples.append({"file": str(path), "text": text, "seconds": round(seconds, 2),
                        "gen_seconds": round(took, 2),
                        "rtf": round(took / seconds, 3) if seconds else None,
                        "peak_dbfs": round(20 * math.log10(max(float(np.max(np.abs(audio))), 1e-9)), 1),
                        "problems": problems})
        print(f"{path.name}: {seconds:.2f} s audio in {took:.2f} s"
              + (f"  PROBLEMS: {problems}" if problems else ""), flush=True)
    report = {"backend": getattr(backend, "name", "?"),
              "device": getattr(backend, "device", None),
              "sample_rate": OUT_RATE, "gen_seconds": round(gen_total, 2),
              "audio_seconds": round(audio_total, 2),
              "rtf": real_time_factor(gen_total, audio_total),
              "samples": samples}
    report["projected_hours_for_1.5h"] = round(projected_hours(report["rtf"], 1.5), 3)
    (out / "report.json").write_text(json.dumps(report, indent=1) + "\n")
    return report


def transcribe_samples(report, out_dir, work_root=None):
    """Re-transcribe each sample with mlx-whisper and note how well it matches."""
    import mlx_whisper
    for s in report["samples"]:
        r = mlx_whisper.transcribe(s["file"], path_or_hf_repo="mlx-community/whisper-large-v3-turbo",
                                   language="en")
        s["transcript"] = r["text"].strip()
        s["word_match"] = round(word_match(s["text"], s["transcript"]), 3)
        print(f"{Path(s['file']).name}: {s['word_match']:.2f}  {s['transcript']}", flush=True)
    path = guard_output(Path(out_dir) / "report.json", work_root)
    path.write_text(json.dumps(report, indent=1) + "\n")


def run_dataset(args):
    # TODO(U3 second half): script-lines.txt -> render_line -> write_wav ->
    # mlx-whisper round trip (word_match threshold, drop and count) ->
    # voice-work/dataset/wavs/*.wav + metadata.csv (`id|text`, no pipes in text).
    raise SystemExit("--dataset is not built yet (plan U3, second half)")


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    mode = ap.add_mutually_exclusive_group(required=True)
    mode.add_argument("--samples", action="store_true", help="render the sample lines")
    mode.add_argument("--dataset", action="store_true", help="(not built yet)")
    ap.add_argument("--backend", choices=["chatterbox", "f5"], default="chatterbox")
    ap.add_argument("--reference", type=Path, default=REFERENCE)
    ap.add_argument("--exaggeration", type=float, default=0.5,
                    help="Chatterbox emotional intensity (0.5 neutral, higher = more excitable)")
    ap.add_argument("--cfg-weight", type=float, default=0.5,
                    help="Chatterbox pacing guidance (lower = faster, livelier delivery)")
    ap.add_argument("--out", type=Path, default=SAMPLES_DIR)
    ap.add_argument("--limit", type=int, default=None, help="render only the first N lines")
    ap.add_argument("--transcribe", action="store_true",
                    help="re-transcribe the samples with mlx-whisper afterwards")
    args = ap.parse_args(argv)
    if args.dataset:
        run_dataset(args)
    out = guard_output(args.out)
    if not args.reference.exists():
        raise SystemExit(f"missing reference {args.reference}; run prepare-clips.py (U2)")
    t0 = time.perf_counter()
    backend = make_backend(args.backend, args.reference, args.exaggeration, args.cfg_weight)
    print(f"{backend.name} loaded on {backend.device} in {time.perf_counter() - t0:.1f} s",
          flush=True)
    lines = SAMPLE_LINES[: args.limit] if args.limit else SAMPLE_LINES
    report = render_samples(backend, lines, out)
    print(f"RTF {report['rtf']:.3f} ({report['gen_seconds']} s for {report['audio_seconds']} s); "
          f"1.5 h of audio would take about {report['projected_hours_for_1.5h']} h")
    if args.transcribe:
        transcribe_samples(report, out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
