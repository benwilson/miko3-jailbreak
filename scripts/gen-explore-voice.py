#!/usr/bin/env python3
"""gen-explore-voice.py — render the spoken names explore says when it inspects
something ("ooh, a plant") into mode-explore/assets/name-<slug>.wav (camera
curiosity plan U6, KTD7, R8, R14).

The vocabulary is the vendor detector's 80 COCO labels (Box.java in the gitignored
tools/serviceexam_jadx sources; copied here so this script runs without them). Each
label gets a short phrase with friendlier wording where the COCO name is stilted
("potted plant" -> "a plant"). The phrase is spoken by a built-in macOS `say` voice,
then ffmpeg turns it into a small robot voice to match the idle songs' WALL-E-style
babble in the deeper register: pitched down a little, a light warble (vibrato) and
a light ring-modulated buzz. Finally the clip is trimmed, faded and normalized here
and written mono 16-bit at gen-explore-sounds.py's RATE. Every name is original
speech; nothing comes from the film.

`say` output differs between macOS versions and machines, so the committed clips are
checked for presence and format only (scripts/tests/test_gen_explore_voice.py).
Requires macOS `say` and ffmpeg.

  python3 scripts/gen-explore-voice.py                  # write mode-explore/assets/
  python3 scripts/gen-explore-voice.py OUT_DIR          # write somewhere else
  python3 scripts/gen-explore-voice.py --voice Fred     # a different built-in voice
"""
import argparse
import array
import os
import shutil
import subprocess
import sys
import tempfile
import wave
from pathlib import Path

RATE = 22050  # same as scripts/gen-explore-sounds.py; the tests check they agree
VOICE = "Samantha"  # clear on a small speaker; the ffmpeg chain supplies the robot
SAY_RATE = 175       # words per minute; raised automatically if a phrase runs long
MAX_SECONDS = 2.3    # keep every clip under the 2.5 s the brain budgets for a name
PEAK = 0.62          # of full scale, like the startle chirps
PITCH = 0.84         # pitch factor (tempo is restored), a little deeper than the voice

# The vendor detector's labels, in its class-index order (Box.java). The decompiled file
# references library constants for "mouse" and "clock"; these are their literal values.
VOCABULARY = (
    "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
    "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
    "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack",
    "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball",
    "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket",
    "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
    "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair",
    "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse",
    "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
    "refrigerator", "book", "clock", "vase", "scissors", "teddy bear", "hair drier",
    "toothbrush",
)

# What he calls a thing when the COCO name is stilted or not how people say it.
FRIENDLY = {
    "potted plant": "plant",
    "cell phone": "phone",
    "dining table": "table",
    "tv": "TV",
    "sports ball": "ball",
    "hair drier": "hair dryer",
    "refrigerator": "fridge",
    "motorcycle": "motorbike",
}

# Plural and mass nouns: "ooh, scissors", not "ooh, a scissors".
NO_ARTICLE = {"skis", "scissors", "broccoli"}


def slug(label):
    """COCO label -> asset slug: spaces become dashes ("potted plant" -> "potted-plant")."""
    return label.replace(" ", "-")


def clip_name(label):
    return f"name-{slug(label)}.wav"


def phrase(label):
    """The words he says for a label, with the right article: "ooh, an apple"."""
    word = FRIENDLY.get(label, label)
    if label in NO_ARTICLE:
        return f"ooh, {word}"
    article = "an" if word[0].lower() in "aeiou" else "a"
    return f"ooh, {article} {word}"


# ffmpeg chain, after `say` writes 16-bit mono at RATE:
#   highpass       drop rumble the small speaker can't reproduce anyway
#   asetrate/atempo/aresample  pitch down by PITCH, restore the tempo, back to RATE
#   vibrato        light warble, like the songs' syllable wobble
#   aeval          light ring modulation: a 90 Hz carrier mixed in at 25% for a buzz
FILTERS = ",".join((
    "highpass=f=90",
    f"asetrate={RATE}*{PITCH}",
    f"atempo={1 / PITCH:.4f}",
    f"aresample={RATE}",
    "vibrato=f=6.5:d=0.18",
    "aeval='val(0)*(0.75+0.25*sin(2*PI*90*t))'",
))


def speak(text, voice, words_per_minute, workdir):
    """Speak and process one phrase; returns raw mono samples at RATE."""
    spoken = Path(workdir) / "say.wav"
    subprocess.run(
        ["say", "-v", voice, "-r", str(words_per_minute), "-o", str(spoken),
         f"--data-format=LEI16@{RATE}", text],
        check=True)
    out = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", str(spoken), "-af", FILTERS,
         "-ac", "1", "-ar", str(RATE), "-f", "s16le", "-acodec", "pcm_s16le", "-"],
        check=True, stdout=subprocess.PIPE).stdout
    samples = array.array("h")
    samples.frombytes(out)
    if sys.byteorder != "little":
        samples.byteswap()
    return samples


def finish(samples):
    """Trim silence at both ends, fade the edges, normalize to PEAK and pad a little
    silence, so every clip is equally loud and starts and ends without a click."""
    threshold = 300
    loud = [i for i, s in enumerate(samples) if abs(s) > threshold]
    if not loud:
        raise RuntimeError("rendered clip is silent")
    body = [float(s) for s in samples[max(0, loud[0] - 200):loud[-1] + 200]]
    peak = max(abs(s) for s in body)
    gain = PEAK * 32767 / peak
    fade_in, fade_out = int(0.01 * RATE), int(0.04 * RATE)
    n = len(body)
    out = array.array("h", [0] * int(0.02 * RATE))
    for i, s in enumerate(body):
        edge = min(1.0, i / fade_in, (n - 1 - i) / fade_out)
        out.append(int(round(s * gain * edge)))
    out.extend([0] * int(0.03 * RATE))
    return out


def write_wav(path, samples):
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(samples.tobytes())


def render(label, voice, workdir):
    """One label's finished clip, speeding the voice up if it would run too long."""
    wpm = SAY_RATE
    while True:
        samples = finish(speak(phrase(label), voice, wpm, workdir))
        if len(samples) / RATE <= MAX_SECONDS or wpm >= 280:
            return samples
        wpm += 20


def generate(out_dir, voice=VOICE):
    """Write a name clip for every vocabulary label into out_dir; returns their paths."""
    for tool in ("say", "ffmpeg"):
        if shutil.which(tool) is None:
            raise SystemExit(f"{tool} not found; this script needs macOS `say` and ffmpeg")
    out_dir.mkdir(parents=True, exist_ok=True)
    paths = []
    with tempfile.TemporaryDirectory(prefix="explore_voice_") as workdir:
        for label in VOCABULARY:
            path = out_dir / clip_name(label)
            write_wav(path, render(label, voice, workdir))
            paths.append(path)
    return paths


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("out", nargs="?", type=Path,
                        default=Path(__file__).resolve().parents[1] / "mode-explore" / "assets")
    parser.add_argument("--voice", default=os.environ.get("EXPLORE_VOICE", VOICE),
                        help=f"built-in macOS voice (default {VOICE})")
    args = parser.parse_args()
    for path in generate(args.out, args.voice):
        with wave.open(str(path)) as w:
            seconds = w.getnframes() / w.getframerate()
        print(f"wrote {path.name} ({seconds:.2f}s)")


if __name__ == "__main__":
    main()
