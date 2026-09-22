#!/usr/bin/env python3
"""gen-explore-sounds.py — render the explore mode's startle chirps into
mode-explore/assets/ (explore plan U7, KTD11).

The sounds are synthesized rather than recorded so there is nothing to license
and their character can be retuned here and regenerated. Each clip is a short
"whoa": a quick upward swoop that falls back down, with a little vibrato so it
reads as a voice rather than a beep. Output is deterministic, so the committed
WAVs can be checked against this script (scripts/tests/test_gen_explore_sounds.py).

  python3 scripts/gen-explore-sounds.py            # write mode-explore/assets/
  python3 scripts/gen-explore-sounds.py OUT_DIR    # write somewhere else
"""
import array
import math
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


def generate(out_dir):
    """Write every variant into out_dir; returns their paths in VARIANTS order."""
    out_dir.mkdir(parents=True, exist_ok=True)
    paths = []
    for name, seconds, start, top, end in VARIANTS:
        path = out_dir / f"{name}.wav"
        with wave.open(str(path), "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(RATE)
            w.writeframes(render(seconds, start, top, end).tobytes())
        paths.append(path)
    return paths


def main():
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_OUT
    for path in generate(out):
        print(f"wrote {path}")


if __name__ == "__main__":
    main()
