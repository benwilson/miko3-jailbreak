#!/usr/bin/env python3
"""gen-explore-sounds.py — render the explore mode's startle chirps and idle songs
into mode-explore/assets/ (explore plan U7, KTD11).

The sounds are synthesized rather than recorded so there is nothing to license
and their character can be retuned here and regenerated. Each clip is a short
"whoa": a quick upward swoop that falls back down, with a little vibrato so it
reads as a voice rather than a beep. The idle songs are short original runs of
voiced syllables (pitch glides, vowel tones, warble and rasp) that he babbles while
sitting still, in the style of WALL-E's processed-voice babble; they copy none of
his lines or tunes. Output is deterministic, so the committed
WAVs can be checked against this script (scripts/tests/test_gen_explore_sounds.py).

  python3 scripts/gen-explore-sounds.py            # write mode-explore/assets/
  python3 scripts/gen-explore-sounds.py OUT_DIR    # write somewhere else
"""
import array
import math
import random
import sys
import wave
from pathlib import Path

RATE = 22050
PEAK = 0.62  # of full scale; loud enough for the robot's small speaker without clipping

# (name, seconds, start Hz, top Hz, end Hz): three voicings so repeated startles vary.
VARIANTS = (
    ("startle-1", 0.34, 520.0, 1180.0, 640.0),
    ("startle-2", 0.28, 600.0, 1320.0, 760.0),
    ("startle-3", 0.40, 460.0, 1040.0, 520.0),
)

# Idle songs: WALL-E-style babble rather than tunes. Each song is a run of voiced
# syllables, like the processed-voice warbles, "ooh?" rises, "bip-bip" chirps and
# wobbly hums the character is known for. All original; none copies his lines or the
# music he hums in the film. A syllable is
#   (vowel, seconds, start Hz, end Hz, shape, warble, rasp, gap after in seconds)
# shape: "glide" (straight), "up"/"down" (eased), "arc" (out to the end pitch and back).
# warble: vibrato depth as a fraction of pitch; rasp: 0-1 buzzy amplitude flutter.
SONG_PEAK = 0.55
VOWELS = {  # harmonic weights: more upper harmonics reads as a brighter vowel
    "mm": (1.0, 0.9, 0.75, 0.6, 0.45, 0.3, 0.2),   # closed-mouth hum, buzzy
    "oo": (1.0, 0.35, 0.12, 0.05),
    "oh": (1.0, 0.6, 0.3, 0.12, 0.05),
    "ah": (1.0, 0.8, 0.6, 0.4, 0.25, 0.12),
    "ee": (1.0, 0.5, 0.7, 0.6, 0.45, 0.3, 0.2),
}
SONGS = (
    ("song-1", [  # curious: a questioning "ooh?", a little hummed line, then a happy bip-bip
        ("oo", 0.45, 220, 330, "up", 0.012, 0.0, 0.10),
        ("mm", 0.28, 262, 262, "glide", 0.01, 0.1, 0.02),
        ("mm", 0.28, 294, 294, "glide", 0.01, 0.1, 0.02),
        ("mm", 0.50, 330, 262, "down", 0.015, 0.1, 0.12),
        ("oh", 0.30, 247, 330, "arc", 0.01, 0.0, 0.10),
        ("ee", 0.09, 440, 523, "up", 0.0, 0.0, 0.06),
        ("ee", 0.11, 440, 587, "up", 0.0, 0.0, 0.0),
    ]),
    ("song-2", [  # sing-song: a stuttery, warbly da-da-dee-daa
        ("ah", 0.18, 262, 262, "glide", 0.01, 0.15, 0.04),
        ("ah", 0.18, 262, 262, "glide", 0.01, 0.15, 0.04),
        ("ee", 0.22, 330, 349, "up", 0.01, 0.1, 0.05),
        ("ah", 0.50, 294, 262, "down", 0.025, 0.15, 0.12),
        ("oh", 0.09, 392, 392, "glide", 0.0, 0.2, 0.03),
        ("oh", 0.09, 440, 440, "glide", 0.0, 0.2, 0.03),
        ("oh", 0.09, 494, 494, "glide", 0.0, 0.2, 0.06),
        ("oo", 0.65, 330, 247, "down", 0.03, 0.1, 0.0),
    ]),
    ("song-3", [  # dreamy: slow downward hums, a sleepy yawn, one tiny bleep
        ("mm", 0.90, 294, 220, "down", 0.015, 0.05, 0.15),
        ("ah", 0.75, 196, 330, "arc", 0.012, 0.05, 0.15),
        ("oo", 0.85, 262, 196, "down", 0.02, 0.0, 0.25),
        ("ee", 0.07, 587, 698, "up", 0.0, 0.0, 0.0),
    ]),
    ("song-4", [  # excited: quick chattery blips, a rising wheee, a satisfied mm-hm
        ("ee", 0.06, 440, 523, "up", 0.0, 0.1, 0.04),
        ("ee", 0.06, 494, 587, "up", 0.0, 0.1, 0.04),
        ("ee", 0.06, 392, 494, "up", 0.0, 0.1, 0.10),
        ("ee", 0.70, 247, 587, "up", 0.02, 0.25, 0.18),
        ("mm", 0.20, 262, 294, "up", 0.01, 0.1, 0.08),
        ("mm", 0.32, 330, 262, "down", 0.012, 0.1, 0.10),
        ("oo", 0.40, 294, 247, "down", 0.018, 0.0, 0.0),
    ]),
)

DEFAULT_OUT = Path(__file__).resolve().parents[1] / "mode-explore" / "assets"


def pitch_at(t, seconds, start, top, end):
    """Rises to the top in the first 30% of the clip, then falls to the end pitch."""
    rise = 0.3 * seconds
    if t < rise:
        x = t / rise
        return start + (top - start) * math.sin(x * math.pi / 2)
    x = (t - rise) / (seconds - rise)
    return top + (end - top) * (1 - math.cos(x * math.pi / 2))


def envelope(t, seconds):
    """Short fade in and a longer fade out, so the clip has no click at either end."""
    attack, release = 0.015, 0.12 * seconds
    if t < attack:
        return t / attack
    if t > seconds - release:
        return max(0.0, (seconds - t) / release)
    return 1.0


def render(seconds, start, top, end):
    n = int(seconds * RATE)
    samples = array.array("h")
    phase = 0.0
    for i in range(n):
        t = i / RATE
        freq = pitch_at(t, seconds, start, top, end) * (1 + 0.025 * math.sin(2 * math.pi * 11 * t))
        phase += 2 * math.pi * freq / RATE
        # A touch of second harmonic keeps it from sounding like a pure test tone.
        value = math.sin(phase) + 0.25 * math.sin(2 * phase)
        samples.append(int(round(value / 1.25 * PEAK * envelope(t, seconds) * 32767)))
    return samples


def syllable_pitch(x, f0, f1, shape):
    """Pitch at fraction x (0-1) through a syllable."""
    if shape == "up":
        return f0 + (f1 - f0) * math.sin(x * math.pi / 2)
    if shape == "down":
        return f0 + (f1 - f0) * (1 - math.cos(x * math.pi / 2))
    if shape == "arc":
        return f0 + (f1 - f0) * math.sin(x * math.pi)
    return f0 + (f1 - f0) * x


def render_song(syllables, seed):
    """One song: each voiced syllable glides in pitch, wobbles, and is shaped by its own
    short envelope; gaps are silence. Deterministic for a given seed."""
    rng = random.Random(seed)
    samples = array.array("h")
    phase = 0.0
    t_song = 0.0
    for vowel, dur, f0, f1, shape, warble, rasp, gap in syllables:
        weights = VOWELS[vowel]
        norm = sum(weights)
        n = int(dur * RATE)
        wobble_hz = 7.0 + 4.0 * rng.random()   # each syllable wobbles a little differently
        rasp_hz = 32.0 + 12.0 * rng.random()
        for i in range(n):
            x = i / n
            t = i / RATE
            f = syllable_pitch(x, f0, f1, shape) * (1 + warble * math.sin(2 * math.pi * wobble_hz * (t_song + t)))
            phase += 2 * math.pi * f / RATE
            value = sum(w * math.sin((k + 1) * phase) for k, w in enumerate(weights)) / norm
            buzz = 1 - rasp * (0.5 + 0.5 * math.sin(2 * math.pi * rasp_hz * t))
            edge = min(1.0, t / 0.015, (dur - t) / 0.03)
            samples.append(int(round(value * buzz * edge * SONG_PEAK * 32767)))
        t_song += dur
        samples.extend([0] * int(gap * RATE))
        t_song += gap
    samples.extend([0] * int(0.05 * RATE))
    return samples


def write_wav(path, samples):
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(samples.tobytes())


def generate(out_dir):
    """Write every startle then every song into out_dir; returns their paths in that order."""
    out_dir.mkdir(parents=True, exist_ok=True)
    paths = []
    for name, seconds, start, top, end in VARIANTS:
        path = out_dir / f"{name}.wav"
        write_wav(path, render(seconds, start, top, end))
        paths.append(path)
    for seed, (name, syllables) in enumerate(SONGS):
        path = out_dir / f"{name}.wav"
        write_wav(path, render_song(syllables, seed))
        paths.append(path)
    return paths


def main():
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_OUT
    for path in generate(out):
        print(f"wrote {path}")


if __name__ == "__main__":
    main()
