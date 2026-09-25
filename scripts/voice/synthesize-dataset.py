#!/usr/bin/env python3
"""
synthesize-dataset.py — read text in the owner's voice with a voice cloner,
to build the synthetic training set for the small Piper voice (robot voice
plan U3, R4, R6, R12; KTD1, KTD2).

Modes:
  --samples   render about 10 sample lines for the owner's listening gate
              (R6) and time the cloner. Writes voice-work/samples/NN-<slug>.wav
              (22050 Hz, mono, 16-bit) and voice-work/samples/report.json.
  --dataset   read scripts/voice/script-lines.txt, render every line, and
              re-transcribe each clip with mlx-whisper. A clip is dropped when
              it is silent, clipped, implausibly long or short for its text,
              or its transcript's word error rate is over 0.15 after both
              sides are normalized (KTD2); a dropped line is retried twice
              with new seeds. Writes voice-work/dataset/wavs/NNNN.wav,
              metadata.csv (LJSpeech `id|text`), summary.json and
              progress.log. Resumable: lines already finished are skipped.

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
  voice-work/.venv-clone/bin/python scripts/voice/synthesize-dataset.py --dataset
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
DATASET_DIR = WORK_ROOT / "dataset"
SCRIPT_LINES = REPO / "scripts" / "voice" / "script-lines.txt"
WHISPER_MODEL = "mlx-community/whisper-large-v3-turbo"

# Owner's pick at listening gate 1 (KTD1): the "excited" delivery.
EXAGGERATION = 0.9
CFG_WEIGHT = 0.3

MIN_WORDS, MAX_WORDS, MAX_CHARS = 3, 25, 180
MAX_WER = 0.15
MAX_RETRIES = 2              # a dropped line is rendered at most 1 + 2 times
MAX_WORDS_PER_SECOND = 6.5   # faster than this is truncated or garbled
MIN_WORDS_PER_SECOND = 1.0   # slower (after 1 s of slack) is babbling or stalling
SLACK_SECONDS = 1.0

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


# ---------------------------------------------------------- script lines

def line_key(text):
    """What makes two lines duplicates: the same words, ignoring case and punctuation."""
    return " ".join(re.findall(r"[a-z0-9']+", text.lower().replace("\u2019", "'")))


def dedupe(lines):
    seen, out = set(), []
    for line in lines:
        k = line_key(line)
        if k not in seen:
            seen.add(k)
            out.append(line)
    return out


def line_problems(text):
    problems = []
    n = len(text.split())
    if not MIN_WORDS <= n <= MAX_WORDS:
        problems.append(f"{n} words (want {MIN_WORDS}-{MAX_WORDS})")
    if len(text) >= MAX_CHARS:
        problems.append(f"{len(text)} characters (want under {MAX_CHARS})")
    if "|" in text:
        problems.append("pipe character")
    return problems


def _text_lines(path):
    return [l.strip() for l in Path(path).read_text().splitlines()
            if l.strip() and not l.lstrip().startswith("#")]


def load_script_lines(path=SCRIPT_LINES):
    """Script lines in order, de-duplicated; a malformed line is an error."""
    lines = dedupe(_text_lines(path))
    bad = [(l, line_problems(l)) for l in lines if line_problems(l)]
    if bad:
        raise ValueError(f"{path}: bad lines: {bad[:5]}")
    return lines


def vocabulary_names(path):
    return _text_lines(path)


def missing_names(names, lines):
    """Names that no line uses as whole words (case-insensitive)."""
    text = "\n".join(lines).lower()
    return [n for n in names
            if not re.search(r"(?<![a-z0-9'-])" + re.escape(n.lower()) + r"(?![a-z0-9-])", text)]


# ------------------------------------------------------- round-trip filter

_ONES = ("zero one two three four five six seven eight nine ten eleven twelve thirteen "
         "fourteen fifteen sixteen seventeen eighteen nineteen").split()
_TENS = "_ _ twenty thirty forty fifty sixty seventy eighty ninety".split()
_SCALES = [(10 ** 12, "trillion"), (10 ** 9, "billion"), (10 ** 6, "million"), (1000, "thousand")]
_ORDINAL = {"one": "first", "two": "second", "three": "third", "five": "fifth",
            "eight": "eighth", "nine": "ninth", "twelve": "twelfth"}


def number_words(n):
    """Cardinal words for a non-negative integer, without 'and' (1250 -> one thousand two hundred fifty)."""
    if n < 20:
        return [_ONES[n]]
    if n < 100:
        return [_TENS[n // 10]] + ([_ONES[n % 10]] if n % 10 else [])
    if n < 1000:
        return [_ONES[n // 100], "hundred"] + (number_words(n % 100) if n % 100 else [])
    for value, name in _SCALES:
        if n >= value:
            return number_words(n // value) + [name] + (number_words(n % value) if n % value else [])
    raise AssertionError(n)


def year_words(n):
    """How a year is read aloud: 1999 -> nineteen ninety nine, 2024 -> twenty twenty four."""
    hi, lo = divmod(n, 100)
    if lo == 0:
        return number_words(hi) + ["hundred"]
    return number_words(hi) + (["oh"] + number_words(lo) if lo < 10 else number_words(lo))


def ordinal_words(n):
    words = number_words(n)
    last = words[-1]
    if last in _ORDINAL:
        words[-1] = _ORDINAL[last]
    elif last.endswith("y"):
        words[-1] = last[:-1] + "ieth"
    else:
        words[-1] = last + "th"
    return words


def _is_year(n):
    return 1100 <= n <= 1999 or 2010 <= n <= 2099


def normalize_words(text):
    """Lower-case words with punctuation gone and numbers written out, for comparing
    a prompt with its transcript ("1, 2, 3" and "One, two, three." agree)."""
    t = text.lower().replace("\u2019", "'").replace("\u2018", "'")

    def say(m):
        money, num, frac, suffix = m.group(1), m.group(2), m.group(3), m.group(4) or ""
        grouped = "," in num                              # 1,250 is never a year
        n = int(num.replace(",", ""))
        if suffix in ("st", "nd", "rd", "th"):
            words = ordinal_words(n)
        elif frac is None and not money and not grouped and len(num) == 4 and _is_year(n):
            words = year_words(n)
        else:
            words = number_words(n)
            if frac is not None:
                words += ["point"] + [_ONES[int(d)] for d in frac]
        if suffix == "%":
            words.append("percent")
        if money:
            words.append("dollars" if n != 1 or frac else "dollar")
        return " " + " ".join(words) + " "

    t = re.sub(r"\b(\d{1,2}):(\d{2})\b",
               lambda m: " " + " ".join(number_words(int(m.group(1))) + (
                   [] if m.group(2) == "00" else
                   ["oh"] + number_words(int(m.group(2))) if m.group(2)[0] == "0" else
                   number_words(int(m.group(2))))) + " ", t)
    t = re.sub(r"(\$)?(\d{1,3}(?:,\d{3})+|\d+)(?:\.(\d+))?(st|nd|rd|th|%)?", say, t)
    return re.findall(r"[a-z]+", t.replace("'", ""))


def word_error_rate(prompt, transcript):
    """Word edit distance over the prompt's word count, after normalize_words()."""
    ref, hyp = normalize_words(prompt), normalize_words(transcript)
    if not ref:
        return 0.0 if not hyp else 1.0
    prev = list(range(len(hyp) + 1))
    for i, r in enumerate(ref, 1):
        cur = [i] + [0] * len(hyp)
        for j, h in enumerate(hyp, 1):
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (r != h))
        prev = cur
    return prev[-1] / len(ref)


def duration_plausible(text, seconds):
    """Is this clip length believable for this many spoken words?"""
    n = max(1, len(normalize_words(text)))
    return n / MAX_WORDS_PER_SECOND <= seconds <= n / MIN_WORDS_PER_SECOND + SLACK_SECONDS


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

    def __init__(self, reference, device=None, exaggeration=EXAGGERATION, cfg_weight=CFG_WEIGHT):
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

    def generate(self, text, seed=None):
        if seed is not None:
            import torch
            torch.manual_seed(seed)
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


def make_backend(name, reference, exaggeration=EXAGGERATION, cfg_weight=CFG_WEIGHT):
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


def transcribe_file(path, prompt=None):
    """What mlx-whisper hears in a clip (the prompt is unused; it keeps whisper unbiased)."""
    import mlx_whisper
    r = mlx_whisper.transcribe(str(path), path_or_hf_repo=WHISPER_MODEL, language="en")
    return r["text"].strip()


def transcribe_samples(report, out_dir, work_root=None):
    """Re-transcribe each sample with mlx-whisper and note how well it matches."""
    for s in report["samples"]:
        s["transcript"] = transcribe_file(s["file"])
        s["word_match"] = round(word_match(s["text"], s["transcript"]), 3)
        print(f"{Path(s['file']).name}: {s['word_match']:.2f}  {s['transcript']}", flush=True)
    path = guard_output(Path(out_dir) / "report.json", work_root)
    path.write_text(json.dumps(report, indent=1) + "\n")


# -------------------------------------------------------------------- dataset

def clip_id(index):
    return f"{index + 1:04d}"


def classify_clip(text, raw, raw_rate, audio):
    """Drop reason for a rendered clip before transcription, or None if it may pass.
    Clipping is judged on the cloner's own output, before the peak ceiling hides it."""
    x = np.asarray(raw, dtype=np.float32).reshape(-1)
    if len(x) == 0 or not np.all(np.isfinite(x)):
        return "bad-audio"
    if float(np.max(np.abs(x))) < SILENT_PEAK:
        return "silent"
    if float(np.mean(np.abs(x) >= CLIP_LEVEL)) > MAX_CLIPPED_FRACTION:
        return "clipped"
    if not duration_plausible(text, len(audio) / OUT_RATE):
        return "duration"
    problems = audio_problems(audio, OUT_RATE)
    if any("silent" in p for p in problems):
        return "silent"
    if problems:
        return "gap" if any("gap" in p for p in problems) else "bad-audio"
    return None


def write_metadata(out_dir, rows, work_root=None, wav_dir=None):
    """LJSpeech metadata.csv (`id|text`), listing only rows whose WAV exists."""
    out = guard_output(out_dir, work_root)
    wav_dir = Path(wav_dir) if wav_dir else out / "wavs"
    path = guard_output(out / "metadata.csv", work_root)
    path.parent.mkdir(parents=True, exist_ok=True)
    keep = [f"{i}|{t}\n" for i, t in rows if "|" not in t and (wav_dir / f"{i}.wav").exists()]
    tmp = path.with_suffix(".csv.tmp")
    tmp.write_text("".join(keep))
    tmp.replace(path)
    return len(keep)


def _load_results(path):
    results = {}
    if path.exists():
        for raw in path.read_text().splitlines():
            try:
                r = json.loads(raw)
            except ValueError:
                continue  # a line cut short by a crash
            results[r["id"]] = r
    return results


def build_dataset(backend, transcribe, lines, out_dir, work_root=None,
                  max_retries=MAX_RETRIES, max_wer=MAX_WER, log=print):
    """Render, round-trip filter and write the dataset; resumable. Returns the summary."""
    out = guard_output(out_dir, work_root)
    wavs = guard_output(out / "wavs", work_root)
    tmp_dir = guard_output(out / "tmp", work_root)
    results_path = guard_output(out / "results.jsonl", work_root)
    log_path = guard_output(out / "progress.log", work_root)
    for d in (wavs, tmp_dir):
        d.mkdir(parents=True, exist_ok=True)
    results = _load_results(results_path)
    logf = open(log_path, "a")

    def note(msg):
        line = f"{time.strftime('%Y-%m-%d %H:%M:%S')} {msg}"
        logf.write(line + "\n")
        logf.flush()
        if log:
            log(line)

    def done(i, text):
        r = results.get(clip_id(i))
        if not r or r["text"] != text:
            return False
        return r["status"] == "dropped" or (wavs / f"{clip_id(i)}.wav").exists()

    todo = [i for i, t in enumerate(lines) if not done(i, t)]
    note(f"start: {len(lines)} lines, {len(lines) - len(todo)} already finished, {len(todo)} to render")
    t_start = time.perf_counter()
    audio_done = 0.0
    with open(results_path, "a") as resf:
        for n_done, i in enumerate(todo, 1):
            text, cid = lines[i], clip_id(i)
            final = wav_path = None
            attempts = []
            for attempt in range(max_retries + 1):
                seed = (i + 1) * 1000 + attempt
                t0 = time.perf_counter()
                raw, rate = backend.generate(text, seed=seed)
                audio = limit_peak(resample(raw, rate, OUT_RATE))
                took = time.perf_counter() - t0
                seconds = len(audio) / OUT_RATE
                rec = {"attempt": attempt, "seed": seed, "seconds": round(seconds, 2),
                       "gen_seconds": round(took, 2)}
                reason = classify_clip(text, raw, rate, audio)
                if reason is None:
                    tmp = write_wav(tmp_dir / f"{cid}.wav", audio, OUT_RATE, work_root)
                    heard = transcribe(tmp, text)
                    wer = word_error_rate(text, heard)
                    rec.update(transcript=heard, wer=round(wer, 3))
                    if wer > max_wer:
                        reason = "transcript"
                        tmp.unlink()
                    else:
                        wav_path = wavs / f"{cid}.wav"
                        tmp.replace(wav_path)
                rec["reason"] = reason
                attempts.append(rec)
                audio_done += seconds
                note(f"{cid} try {attempt + 1}: {'kept' if reason is None else 'drop ' + reason}"
                     f" {seconds:.1f}s gen {took:.1f}s"
                     + (f" wer {rec['wer']:.2f} heard {rec['transcript']!r}" if "wer" in rec else "")
                     + f" | {text}")
                if reason is None:
                    break
            final = {"id": cid, "text": text, "status": "kept" if wav_path else "dropped",
                     "reason": attempts[-1]["reason"], "seconds": attempts[-1]["seconds"],
                     "attempts": attempts}
            results[cid] = final
            resf.write(json.dumps(final) + "\n")
            resf.flush()
            if n_done % 10 == 0 or n_done == len(todo):
                elapsed = time.perf_counter() - t_start
                kept = sum(r["status"] == "kept" for r in results.values())
                eta = elapsed / n_done * (len(todo) - n_done)
                note(f"progress {n_done}/{len(todo)} this run; kept {kept} total; "
                     f"elapsed {elapsed / 60:.1f} min; eta {eta / 60:.1f} min")
                write_metadata(out, _kept_rows(lines, results), work_root)
    summary = summarize(lines, results, out, work_root)
    note(f"done: {json.dumps(summary)}")
    logf.close()
    return summary


def _kept_rows(lines, results):
    rows = []
    for i, text in enumerate(lines):
        r = results.get(clip_id(i))
        if r and r["text"] == text and r["status"] == "kept":
            rows.append((clip_id(i), text))
    return rows


def summarize(lines, results, out, work_root=None):
    current = [results[clip_id(i)] for i, t in enumerate(lines)
               if clip_id(i) in results and results[clip_id(i)]["text"] == t]
    kept = [r for r in current if r["status"] == "kept"]
    dropped = {}
    for r in current:
        if r["status"] == "dropped":
            dropped[r["reason"]] = dropped.get(r["reason"], 0) + 1
    n_rows = write_metadata(out, _kept_rows(lines, results), work_root)
    summary = {"lines": len(lines), "finished": len(current), "kept": n_rows,
               "dropped": dropped,
               "retries": sum(len(r["attempts"]) - 1 for r in current),
               "hours": round(sum(r["seconds"] for r in kept) / 3600, 3),
               "sample_rate": OUT_RATE, "max_wer": MAX_WER,
               "exaggeration": EXAGGERATION, "cfg_weight": CFG_WEIGHT}
    guard_output(Path(out) / "summary.json", work_root).write_text(json.dumps(summary, indent=1) + "\n")
    return summary


def run_dataset(args):
    out = guard_output(args.out or DATASET_DIR)
    lines = load_script_lines(args.lines)
    if args.limit:
        lines = lines[: args.limit]
    if not args.reference.exists():
        raise SystemExit(f"missing reference {args.reference}; run prepare-clips.py (U2)")
    t0 = time.perf_counter()
    backend = make_backend(args.backend, args.reference, args.exaggeration, args.cfg_weight)
    print(f"{backend.name} loaded on {backend.device} in {time.perf_counter() - t0:.1f} s "
          f"(exaggeration {args.exaggeration}, cfg_weight {args.cfg_weight})", flush=True)
    summary = build_dataset(backend, transcribe_file, lines, out)
    print(json.dumps(summary, indent=1))
    return 0


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    mode = ap.add_mutually_exclusive_group(required=True)
    mode.add_argument("--samples", action="store_true", help="render the sample lines")
    mode.add_argument("--dataset", action="store_true", help="build voice-work/dataset (resumable)")
    ap.add_argument("--backend", choices=["chatterbox", "f5"], default="chatterbox")
    ap.add_argument("--reference", type=Path, default=REFERENCE)
    ap.add_argument("--exaggeration", type=float, default=EXAGGERATION,
                    help="Chatterbox emotional intensity (0.5 neutral, higher = more excitable)")
    ap.add_argument("--cfg-weight", type=float, default=CFG_WEIGHT,
                    help="Chatterbox pacing guidance (lower = faster, livelier delivery)")
    ap.add_argument("--out", type=Path, default=None,
                    help="output directory (default voice-work/samples or voice-work/dataset)")
    ap.add_argument("--lines", type=Path, default=SCRIPT_LINES, help="script lines for --dataset")
    ap.add_argument("--limit", type=int, default=None, help="render only the first N lines")
    ap.add_argument("--transcribe", action="store_true",
                    help="re-transcribe the samples with mlx-whisper afterwards")
    args = ap.parse_args(argv)
    if args.dataset:
        return run_dataset(args)
    out = guard_output(args.out or SAMPLES_DIR)
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
