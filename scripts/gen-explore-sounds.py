#!/usr/bin/env python3
"""gen-explore-sounds.py — render the explore mode's startle chirps and idle songs
into mode-explore/assets/ (explore plan U7, KTD11).

The sounds are synthesized rather than recorded so there is nothing to license
and their character can be retuned here and regenerated. Each clip is a short
"whoa": a quick upward swoop that falls back down, with a little vibrato so it
reads as a voice rather than a beep. The idle songs are short original hummed
phrases (pentatonic, gliding between notes, with vibrato) that he sings while
sitting still; they are not any existing tune. Output is deterministic, so the committed
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

# Idle songs: (name, seconds per beat, [(semitones above SONG_ROOT_HZ, beats), ...]).
# Original phrases on a major-pentatonic scale, so any note sits well after any other.
SONG_ROOT_HZ = 261.63  # C4: a low croon; the harmonics below carry it on the small speaker
SONG_PEAK = 0.5
SONGS = (
    ("song-1", 0.25, [(0, 1), (4, 1), (7, 2), (9, 1), (7, 1), (4, 2), (2, 1), (0, 3)]),
    ("song-2", 0.24, [(7, 1), (9, 1), (12, 2), (9, 1), (7, 1), (4, 1), (7, 1), (4, 1), (2, 1), (0, 3)]),
    ("song-3", 0.23, [(0, 1), (2, 1), (4, 1), (7, 1), (12, 2), (9, 1), (7, 1), (9, 1), (12, 3), (9, 1),
                      (7, 1), (4, 2), (0, 3)]),
)
GLIDE_S = 0.045  # portamento into each note, so the line sounds sung rather than keyed

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


def render_song(beat_s, notes):
    """One hummed phrase: notes glide into each other, with a gentle vibrato and a soft
    per-note swell, and the whole phrase fades in and out so it never clicks."""
    starts, t = [], 0.0
    for _, beats in notes:
        starts.append(t)
        t += beats * beat_s
    seconds = t + 0.25  # a short tail so the last note can ring out
    n = int(seconds * RATE)
    freqs = [SONG_ROOT_HZ * 2 ** (semi / 12.0) for semi, _ in notes]
    samples = array.array("h")
    phase = 0.0
    k = 0
    for i in range(n):
        t = i / RATE
        while k + 1 < len(notes) and t >= starts[k + 1]:
            k += 1
        target = freqs[k]
        into = t - starts[k]
        if k > 0 and into < GLIDE_S:
            prev = freqs[k - 1]
            target = prev + (target - prev) * (into / GLIDE_S)
        freq = target * (1 + 0.014 * math.sin(2 * math.pi * 4.5 * t))
        phase += 2 * math.pi * freq / RATE
        note_len = notes[k][1] * beat_s
        swell = min(1.0, into / 0.03) * (0.75 + 0.25 * math.cos(math.pi * min(into / note_len, 1.0)))
        # Strong low harmonics: a tiny speaker barely moves at the fundamental, but the
        # ear rebuilds the low pitch from its overtones, and they make it a warm hum.
        value = (math.sin(phase) + 0.7 * math.sin(2 * phase) + 0.45 * math.sin(3 * phase)
                 + 0.2 * math.sin(4 * phase)) / 2.35
        samples.append(int(round(value * SONG_PEAK * swell * envelope(t, seconds) * 32767)))
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
    for name, beat_s, notes in SONGS:
        path = out_dir / f"{name}.wav"
        write_wav(path, render_song(beat_s, notes))
        paths.append(path)
    return paths


def main():
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_OUT
    for path in generate(out):
        print(f"wrote {path}")


if __name__ == "__main__":
    main()
