#!/usr/bin/env python3
"""gen-explore-voice.py — render the spoken names explore says when it inspects
something ("ooh, a plant") into mode-explore/assets/name-<slug>.webm (camera
curiosity plan U6, KTD7, R8, R14).

The vocabulary is mode-explore/assets/vocabulary.txt, the same list the detector is
exported with (scripts/export-explore-detector.py). Each name gets a short phrase
with the right article, or none for plural and mass nouns ("ooh, socks"), and
fixed capitals where a name needs them ("a TV"). The phrase is spoken by the
robot's own trained Piper voice (launcher/assets/voice/, robot voice plan U4)
through sherpa-onnx, the same engine the launcher speaks with, with no robot
effect: the clips sound like the rest of his speech (robot voice plan U6, KTD7,
R14). Finally the clip is trimmed, faded and normalized here and encoded as
Opus in WebM: a few hundred names as WAV would add ~17 MB to the APK (assets
are stored uncompressed), as Opus ~1.5 MB. The robot's MediaPlayer plays Opus
in WebM (checked on the device; Opus in Ogg needs Android 10).

sherpa-onnx output can differ between machines and versions, so the committed
clips are checked for presence and format only (scripts/tests/test_gen_explore_voice.py).
Requires the sherpa-onnx Python package (voice-work/.venv-piper has it) and
ffmpeg with libopus.

  voice-work/.venv-piper/bin/python scripts/gen-explore-voice.py          # mode-explore/assets/
  voice-work/.venv-piper/bin/python scripts/gen-explore-voice.py OUT_DIR  # somewhere else
"""
import argparse
import array
import shutil
import subprocess
import sys
from pathlib import Path

RATE = 22050  # same as scripts/gen-explore-sounds.py and the voice; the tests check
VOICE_DIR = Path(__file__).resolve().parents[1] / "launcher" / "assets" / "voice"
SPEED = 1.0          # sherpa-onnx speed; raised automatically if a phrase runs long
MAX_SPEED = 1.5
SPEED_STEP = 0.1
MAX_SECONDS = 2.3    # keep every clip under the 2.5 s the brain budgets for a name
PEAK = 0.62          # of full scale, like the startle chirps

VOCABULARY_FILE = Path(__file__).resolve().parents[1] / "mode-explore" / "assets" / "vocabulary.txt"
OPUS_BITRATE = "24k"  # speech; transparent enough on the robot's small speaker


def read_vocabulary(path=VOCABULARY_FILE):
    """The names in model order: non-blank lines that are not '#' comments (the
    same rule as export-explore-detector.py and OnnxRecognizer)."""
    names = []
    for line in Path(path).read_text().splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            names.append(line)
    return tuple(names)


VOCABULARY = read_vocabulary()

# How a name is written for the voice, where the vocabulary's lowercase spelling
# would be read wrong ("tv" as "tuh-vee") or a synonym is how people say it.
FRIENDLY = {
    "tv": "TV",
    "cell phone": "phone",
    "t-shirt": "T-shirt",
    "usb stick": "USB stick",
    "lego": "Lego",
    "rubik's cube": "Rubik's cube",
}

# Plural and mass nouns: "ooh, scissors", not "ooh, a scissors".
NO_ARTICLE = {
    "flowers", "blinds", "stairs", "shoes", "socks", "pants", "jeans", "shorts", "pajamas",
    "glasses", "sunglasses", "earrings", "keys", "headphones", "earbuds", "scissors",
    "building blocks", "playing cards", "dice", "lego", "play dough", "sticky notes",
    "grapes", "broccoli", "bread", "cheese", "candy", "chocolate", "ice cream", "popcorn",
    "toothpaste", "soap", "toilet paper", "paper towel", "paper", "tape", "glue", "pliers",
}

# Words starting with a vowel letter but a consonant sound, and the reverse.
A_NOT_AN = {"ukulele", "usb stick"}


def slug(label):
    """Vocabulary name -> asset slug: lowercase, every run of other characters
    becomes one dash ("guinea pig" -> "guinea-pig", "rubik's cube" -> "rubik-s-cube").
    Must match Detection.nameClip on the robot."""
    out, dash = [], False
    for ch in label.lower():
        if ch.isascii() and ch.isalnum():
            if dash and out:
                out.append("-")
            out.append(ch)
            dash = False
        else:
            dash = True
    return "".join(out)


def clip_name(label):
    return f"name-{slug(label)}.webm"


def phrase(label):
    """The words he says for a name, with the right article: "ooh, an apple"."""
    word = FRIENDLY.get(label, label)
    if label in NO_ARTICLE:
        return f"ooh, {word}"
    vowel = word[0].lower() in "aeiou" and label not in A_NOT_AN
    return f"ooh, {'an' if vowel else 'a'} {word}"


def load_voice(voice_dir=VOICE_DIR):
    """The trained voice as a sherpa_onnx.OfflineTts (imported here so the tests
    run without sherpa-onnx)."""
    import sherpa_onnx
    d = Path(voice_dir)
    config = sherpa_onnx.OfflineTtsConfig(
        model=sherpa_onnx.OfflineTtsModelConfig(
            vits=sherpa_onnx.OfflineTtsVitsModelConfig(
                model=str(d / "model.onnx"), tokens=str(d / "tokens.txt"),
                data_dir=str(d / "espeak-ng-data")),
            num_threads=2, provider="cpu"))
    return sherpa_onnx.OfflineTts(config)


def speak(text, tts, speed):
    """Speak one phrase; returns raw 16-bit mono samples at RATE."""
    audio = tts.generate(text, sid=0, speed=speed)
    if audio.sample_rate != RATE:
        raise RuntimeError(f"voice speaks at {audio.sample_rate} Hz, clips need {RATE}")
    return array.array("h", (int(round(max(-1.0, min(1.0, s)) * 32767)) for s in audio.samples))


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


def write_clip(path, samples):
    """Encode finished samples as Opus in WebM at path."""
    pcm = samples.tobytes() if sys.byteorder == "little" else _swapped(samples)
    subprocess.run(
        ["ffmpeg", "-v", "error", "-y", "-f", "s16le", "-ar", str(RATE), "-ac", "1", "-i", "-",
         "-c:a", "libopus", "-b:a", OPUS_BITRATE, "-application", "voip",
         "-map_metadata", "-1", "-fflags", "+bitexact", "-f", "webm", str(path)],
        input=pcm, check=True)


def _swapped(samples):
    copy = array.array("h", samples)
    copy.byteswap()
    return copy.tobytes()


def clip_seconds(path):
    """A clip's length from ffprobe, or None when ffprobe is not installed."""
    if shutil.which("ffprobe") is None:
        return None
    out = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration",
                          "-of", "csv=p=0", str(path)], check=True, capture_output=True, text=True)
    return float(out.stdout.strip())


def render(label, tts):
    """One label's finished clip, speeding the voice up if it would run too long."""
    speed = SPEED
    while True:
        samples = finish(speak(phrase(label), tts, round(speed, 2)))
        if len(samples) / RATE <= MAX_SECONDS or speed >= MAX_SPEED - 1e-9:
            return samples
        speed += SPEED_STEP


def generate(out_dir, voice_dir=VOICE_DIR):
    """Write a name clip for every vocabulary label into out_dir; returns their paths."""
    if shutil.which("ffmpeg") is None:
        raise SystemExit("ffmpeg not found; this script needs ffmpeg with libopus")
    out_dir.mkdir(parents=True, exist_ok=True)
    tts = load_voice(voice_dir)
    paths = []
    for label in VOCABULARY:
        path = out_dir / clip_name(label)
        write_clip(path, render(label, tts))
        paths.append(path)
    return paths


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("out", nargs="?", type=Path,
                        default=Path(__file__).resolve().parents[1] / "mode-explore" / "assets")
    parser.add_argument("--voice-dir", type=Path, default=VOICE_DIR,
                        help="sherpa-onnx Piper voice (default launcher/assets/voice/)")
    args = parser.parse_args()
    for stale in args.out.glob("name-*.wav"):  # the earlier WAV clips
        stale.unlink()
    for path in generate(args.out, args.voice_dir):
        print(f"wrote {path.name} ({clip_seconds(path) or 0:.2f}s, {path.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
