#!/usr/bin/env python3
"""gen-explore-voice.py — render the spoken names explore says when it inspects
something ("ooh, a plant") into mode-explore/assets/name-<slug>.webm (camera
curiosity plan U6, KTD7, R8, R14).

The vocabulary is mode-explore/assets/vocabulary.txt, the same list the detector is
exported with (scripts/export-explore-detector.py). Each name gets a short phrase
with the right article, or none for plural and mass nouns ("ooh, socks"), and
fixed capitals where a name needs them ("a TV"). The phrase is spoken by a built-in macOS `say` voice,
then ffmpeg turns it into a small robot voice to match the idle songs' WALL-E-style
babble in the deeper register: pitched down a little, a light warble (vibrato) and
a light ring-modulated buzz. Finally the clip is trimmed, faded and normalized here
and encoded as Opus in WebM: a few hundred names as WAV would add ~17 MB to the
APK (assets are stored uncompressed), as Opus ~1.5 MB. The robot's MediaPlayer
plays Opus in WebM (checked on the device; Opus in Ogg needs Android 10). Every
name is original speech; nothing comes from the film.

`say` output differs between macOS versions and machines, so the committed clips are
checked for presence and format only (scripts/tests/test_gen_explore_voice.py).
Requires macOS `say` and ffmpeg with libopus.

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
from pathlib import Path

RATE = 22050  # same as scripts/gen-explore-sounds.py; the tests check they agree
VOICE = "Samantha"  # clear on a small speaker; the ffmpeg chain supplies the robot
SAY_RATE = 175       # words per minute; raised automatically if a phrase runs long
MAX_SECONDS = 2.3    # keep every clip under the 2.5 s the brain budgets for a name
PEAK = 0.62          # of full scale, like the startle chirps
PITCH = 0.84         # pitch factor (tempo is restored), a little deeper than the voice

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
            write_clip(path, render(label, voice, workdir))
            paths.append(path)
    return paths


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("out", nargs="?", type=Path,
                        default=Path(__file__).resolve().parents[1] / "mode-explore" / "assets")
    parser.add_argument("--voice", default=os.environ.get("EXPLORE_VOICE", VOICE),
                        help=f"built-in macOS voice (default {VOICE})")
    args = parser.parse_args()
    for stale in args.out.glob("name-*.wav"):  # the earlier WAV clips
        stale.unlink()
    for path in generate(args.out, args.voice):
        print(f"wrote {path.name} ({clip_seconds(path) or 0:.2f}s, {path.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
