#!/usr/bin/env python3
"""
prepare-clips.py — clean and transcribe the owner's voice clips, and pick the
cloner's reference audio (robot voice plan U2, R3, R12).

For each MP3 in the VoiceChat folder (read in place, never copied into the
tracked repository):
  1. decode to 48 kHz mono with ffmpeg and estimate its signal-to-noise ratio
     from frame levels (loud frames over quiet frames);
  2. denoise with DeepFilterNet3 (a masking denoiser, not a generative
     enhancer, so the timbre is kept);
  3. trim leading and trailing silence, even out the level (-20 dBFS RMS over
     the speech, peaks held under -1 dBFS), resample to 22050 Hz (the Piper
     medium tier, KTD8) and write 16-bit mono WAV;
  4. transcribe with mlx-whisper large-v3-turbo, prompted with the file name
     (which is close to a transcript), and flag clips whose transcript
     disagrees with the file name or whose name holds a placeholder;
  5. set aside clips too short or too noisy to help, rank the rest by SNR
     then length, and take 10-15 s of the best as Chatterbox's reference.

Outputs, all under voice-work/ (gitignored; every write is checked):
  voice-work/clips/<name>.wav, voice-work/clips/manifest.json,
  voice-work/reference/NN-<name>.wav and voice-work/reference/reference.wav
  (the picked clips joined with short gaps, for the cloner).

Run with the voice tooling environment:
  voice-work/.venv/bin/python scripts/voice/prepare-clips.py
"""
import argparse
import difflib
import json
import re
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
WORK_ROOT = REPO / "voice-work"
DEFAULT_SRC = Path.home() / "Documents" / "NVIDIA-NemotronLabs-VoiceChat-11B" / "voice"

DECODE_RATE = 48000          # DeepFilterNet3's native rate
OUT_RATE = 22050             # Piper medium tier (KTD8)
FRAME_MS = 10
TRIM_BELOW_PEAK_DB = 40.0    # frames this far under the loudest frame are silence
TRIM_PAD_MS = 100
TARGET_RMS_DBFS = -20.0
PEAK_CEILING_DBFS = -1.0

MIN_SECONDS = 1.0            # shorter clips carry too little voice to help
MIN_SNR_DB = 15.0            # noisier clips would teach the cloner the noise
REF_MIN_SECONDS = 10.0
REF_MAX_SECONDS = 15.0
REF_GAP_SECONDS = 0.25

AGREE_RATIO = 0.8            # word-level match needed between hint and transcript
WHISPER_REPO = "mlx-community/whisper-large-v3-turbo"
PLACEHOLDER = ("insert", "registered", "name")
CONTRACTIONS = {"ll", "m", "s", "re", "ve", "d", "t"}


class OutsideWorkDir(Exception):
    pass


# ---------------------------------------------------------------- pure logic

def hint_from_filename(name):
    """File name -> (hint text, holds placeholder). 'i-ll-scan' -> "I'll scan"."""
    tokens = Path(name).stem.lower().split("-")
    merged, placeholder, i = [], False, 0
    while i < len(tokens):
        if tuple(tokens[i:i + len(PLACEHOLDER)]) == PLACEHOLDER:
            merged.append("-".join(PLACEHOLDER))
            placeholder = True
            i += len(PLACEHOLDER)
        else:
            merged.append(tokens[i])
            i += 1
    words = []
    for tok in merged:
        if not tok:
            continue
        if words and tok in CONTRACTIONS and (tok != "t" or words[-1].endswith("n")):
            words[-1] += "'" + tok
        else:
            words.append(tok)
    words = ["I" + w[1:] if w.split("'")[0] == "i" else w for w in words]
    if words:
        words[0] = words[0][0].upper() + words[0][1:]
    return " ".join(words), placeholder


def _words(text):
    text = text.lower().replace("-", " ").replace("'", "").replace("’", "")
    return re.sub(r"[^a-z0-9 ]+", " ", text).split()


def disagreement(hint, transcript):
    """None when the transcript says what the file name says, else a reason."""
    want, got = _words(hint), _words(transcript)
    if not got:
        return "empty transcript"
    ratio = difflib.SequenceMatcher(None, want, got).ratio()
    if ratio < AGREE_RATIO:
        return f"transcript differs from file name (word match {ratio:.0%})"
    return None


def flag_reason(hint, placeholder, transcript, retried=False):
    reasons = []
    if retried:
        reasons.append("Whisper prompted with the file name disagreed; transcript is unprompted")
    if placeholder:
        reasons.append("file name holds a placeholder (insert-registered-name); check by ear")
    d = disagreement(hint, transcript)
    if d:
        reasons.append(d)
    return "; ".join(reasons) or None


def whisper_prompt(hint):
    """The hint as Whisper's prompt, without the placeholder (not real speech)."""
    return " ".join(hint.replace("-".join(PLACEHOLDER), " ").split())


def guard_output(path, work_root=None):
    """Resolve path; refuse it unless it is inside work_root (symlinks followed)."""
    root = Path(WORK_ROOT if work_root is None else work_root).resolve()
    p = Path(path).resolve()
    if p != root and root not in p.parents:
        raise OutsideWorkDir(f"refusing to write {p}: outside {root}")
    return p


def _percentile(sorted_vals, q):
    return sorted_vals[int(round(q * (len(sorted_vals) - 1)))]


def snr_from_frame_db(dbs):
    """Speech level (90th percentile frame) over noise floor (10th), in dB."""
    s = sorted(dbs)
    return _percentile(s, 0.9) - _percentile(s, 0.1)


def active_span(dbs, threshold_db, pad_frames):
    """(start, end) frame span above threshold, padded; end exclusive. None if silent."""
    loud = [i for i, d in enumerate(dbs) if d >= threshold_db]
    if not loud:
        return None
    return max(0, loud[0] - pad_frames), min(len(dbs), loud[-1] + 1 + pad_frames)


def set_aside_reason(duration, snr):
    reasons = []
    if duration < MIN_SECONDS:
        reasons.append(f"too short ({duration:.2f} s < {MIN_SECONDS:g} s)")
    if snr < MIN_SNR_DB:
        reasons.append(f"too noisy (SNR {snr:.1f} dB < {MIN_SNR_DB:g} dB)")
    return "; ".join(reasons) or None


def pick_reference(clips, lo=REF_MIN_SECONDS, hi=REF_MAX_SECONDS):
    """Best clips by SNR, then length, until lo-hi seconds; skips set-aside ones."""
    ranked = sorted((c for c in clips if not c["set_aside"]),
                    key=lambda c: (-c["snr"], -c["duration"]))
    picked, total = [], 0.0
    for c in ranked:
        if total + c["duration"] > hi:
            continue
        picked.append(c)
        total += c["duration"]
        if total >= lo:
            break
    return picked


# ------------------------------------------------------------- heavy tools

def _np():
    try:
        import numpy
        return numpy
    except ImportError:
        sys.exit("numpy missing: run with voice-work/.venv/bin/python")


def decode(src, rate=DECODE_RATE):
    np = _np()
    raw = subprocess.run(
        ["ffmpeg", "-nostdin", "-v", "error", "-i", str(src), "-ac", "1",
         "-ar", str(rate), "-f", "f32le", "-"],
        check=True, capture_output=True).stdout
    return np.frombuffer(raw, dtype=np.float32).copy()


def frame_db(x, rate):
    np = _np()
    n = rate * FRAME_MS // 1000
    count = len(x) // n
    frames = x[:count * n].reshape(count, n).astype(np.float64)
    rms = np.sqrt((frames ** 2).mean(axis=1))
    return list(20 * np.log10(rms + 1e-10))


_DF = None


def denoise(x):
    global _DF
    import torch
    from df.enhance import enhance, init_df
    if _DF is None:
        model, state, _ = init_df(log_file=None, log_level="WARNING")
        if state.sr() != DECODE_RATE:
            raise RuntimeError(f"DeepFilterNet rate {state.sr()} != {DECODE_RATE}")
        _DF = (model, state)
    out = enhance(_DF[0], _DF[1], torch.from_numpy(x)[None])
    return out[0].numpy()


def level(x, dbs):
    """Scale to TARGET_RMS_DBFS over speech frames, peaks under the ceiling."""
    np = _np()
    top = max(dbs)
    speech = [d for d in dbs if d >= top - TRIM_BELOW_PEAK_DB]
    speech_rms = np.sqrt(np.mean([10 ** (d / 10) for d in speech]))
    gain = 10 ** (TARGET_RMS_DBFS / 20) / max(speech_rms, 1e-9)
    peak = float(np.abs(x).max()) * gain
    ceiling = 10 ** (PEAK_CEILING_DBFS / 20)
    if peak > ceiling:
        gain *= ceiling / peak
    return (x * gain).astype(np.float32)


def process_clip(src, out_wav):
    """Decode, denoise, trim, level, resample, write. Returns (seconds, snr_db)."""
    np = _np()
    import soundfile
    from scipy.signal import resample_poly
    out_wav = guard_output(out_wav)
    raw = decode(src)
    snr = snr_from_frame_db(frame_db(raw, DECODE_RATE))
    clean = denoise(raw)
    # Pad both ends with silence so speech that starts or ends right at the
    # edge still gets its lead-in; Whisper drops words with none.
    edge = np.zeros(DECODE_RATE * TRIM_PAD_MS // 1000, dtype=np.float32)
    clean = np.concatenate([edge, clean.astype(np.float32), edge])
    dbs = frame_db(clean, DECODE_RATE)
    span = active_span(dbs, max(dbs) - TRIM_BELOW_PEAK_DB, TRIM_PAD_MS // FRAME_MS)
    if span is None:
        return 0.0, snr
    n = DECODE_RATE * FRAME_MS // 1000
    trimmed = clean[span[0] * n:span[1] * n]
    leveled = level(trimmed, dbs[span[0]:span[1]])
    out = resample_poly(leveled, 147, 320).astype(np.float32)  # 48000 -> 22050
    out = np.clip(out, -1.0, 1.0)
    soundfile.write(str(out_wav), out, OUT_RATE, subtype="PCM_16")
    return len(out) / OUT_RATE, snr


def _whisper(path, prompt):
    import mlx_whisper
    result = mlx_whisper.transcribe(
        str(path), path_or_hf_repo=WHISPER_REPO, language="en",
        initial_prompt=prompt, condition_on_previous_text=False)
    return result["text"].strip()


def transcribe(path, hint):
    """Whisper prompted with the hint. If that disagrees with the hint, the
    prompt may have made Whisper skip words (seen on a clip with extra speech
    around the hinted sentence), so the unprompted transcript is kept instead;
    the clip stays flagged because it still differs from its file name.
    Returns (transcript, retried)."""
    prompted = _whisper(path, whisper_prompt(hint))
    if disagreement(hint, prompted) is None:
        return prompted, False
    return _whisper(path, None), True


def write_reference(picked, ref_dir):
    np = _np()
    import soundfile
    ref_dir = guard_output(ref_dir)
    for old in ref_dir.glob("*.wav"):
        guard_output(old).unlink()
    written, parts = [], []
    gap = np.zeros(int(REF_GAP_SECONDS * OUT_RATE), dtype=np.float32)
    for i, c in enumerate(picked, 1):
        data, rate = soundfile.read(str(guard_output(c["path"])), dtype="float32")
        assert rate == OUT_RATE
        dest = guard_output(ref_dir / f"{i:02d}-{c['file']}")
        soundfile.write(str(dest), data, OUT_RATE, subtype="PCM_16")
        written.append(dest)
        parts += [data, gap] if i < len(picked) else [data]
    if parts:
        joined = guard_output(ref_dir / "reference.wav")
        soundfile.write(str(joined), np.concatenate(parts), OUT_RATE, subtype="PCM_16")
        written.append(joined)
    return written


def check_gitignored(work):
    """When the work dir is inside the repo, git must ignore it."""
    try:
        work.resolve().relative_to(REPO)
    except ValueError:
        return
    probe = work / "probe"
    if subprocess.run(["git", "-C", str(REPO), "check-ignore", "-q", str(probe)]).returncode:
        sys.exit(f"{work} is not gitignored; refusing to write private audio there")


# --------------------------------------------------------------------- main

def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--src", type=Path, default=DEFAULT_SRC, help="folder of MP3 clips")
    ap.add_argument("--out", type=Path, default=None, help="work dir (inside voice-work/)")
    args = ap.parse_args(argv)
    try:
        work = guard_output(args.out or WORK_ROOT)
        clips_dir = guard_output(work / "clips")
        ref_dir = guard_output(work / "reference")
    except OutsideWorkDir as e:
        sys.exit(str(e))
    check_gitignored(work)
    sources = sorted(args.src.glob("*.mp3"))
    if not sources:
        sys.exit(f"no MP3 clips in {args.src}")
    clips_dir.mkdir(parents=True, exist_ok=True)
    ref_dir.mkdir(parents=True, exist_ok=True)

    entries = []
    for src in sources:
        hint, placeholder = hint_from_filename(src.name)
        out_wav = guard_output(clips_dir / f"{src.stem}.wav")
        duration, snr = process_clip(src, out_wav)
        transcript, retried = transcribe(out_wav, hint) if duration > 0 else ("", False)
        reason = flag_reason(hint, placeholder, transcript, retried)
        entries.append({
            "file": out_wav.name, "source": src.name, "path": out_wav,
            "duration": round(duration, 2), "snr": round(snr, 1),
            "hint": hint, "transcript": transcript,
            "flagged": reason is not None, "flag_reason": reason,
            "set_aside": set_aside_reason(duration, snr),
        })

    picked = pick_reference(entries)
    write_reference(picked, ref_dir)
    chosen = {c["file"] for c in picked}
    for e in entries:
        e["in_reference"] = e["file"] in chosen
    manifest = {
        "sample_rate": OUT_RATE,
        "clips": [{k: v for k, v in e.items() if k != "path"} for e in entries],
        "reference": {"clips": [c["file"] for c in picked],
                      "seconds": round(sum(c["duration"] for c in picked), 2),
                      "joined": "reference.wav"},
    }
    guard_output(clips_dir / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")

    for e in entries:
        mark = "REF " if e["in_reference"] else ("ASIDE" if e["set_aside"] else "    ")
        print(f"{mark} {e['duration']:5.2f}s {e['snr']:5.1f}dB  {e['file']}")
        print(f"      hint: {e['hint']}\n      said: {e['transcript']}")
        for label in ("flag_reason", "set_aside"):
            if e[label]:
                print(f"      {label}: {e[label]}")
    print(f"reference: {len(picked)} clips, {manifest['reference']['seconds']} s -> {ref_dir}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
