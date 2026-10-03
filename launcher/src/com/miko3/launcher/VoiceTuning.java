package com.miko3.launcher;

import java.util.Locale;

/**
 * Owner 2026-10-02: the voice identification's numbers. The two match thresholds are
 * cosine scores against a person's stored voice and are first guesses, to be tuned from
 * the "voice: match" log lines; each can be set without a rebuild by its property, read
 * when the launcher starts. A strong match is that person; a weak one is "maybe them" (ask);
 * below weak is someone unknown. Plain Java, proven in the host harness.
 */
final class VoiceTuning {
    static final String STRONG_PROP = "persist.miko3.voice.strong";
    static final String WEAK_PROP = "persist.miko3.voice.weak";
    /** Debug only: "1" when the launcher starts runs the compute() timing bench (qa-voice-bench.py). */
    static final String BENCH_PROP = "debug.miko3.voice_bench";

    static final float DEFAULT_STRONG = 0.65f;
    static final float DEFAULT_WEAK = 0.45f;
    /** A strong best match whose runner-up is this close is a close call: only weak. */
    static final float TIE_MARGIN = 0.05f;

    static final int SAMPLE_RATE = 16000;
    /** Less speech than this says too little about a voice to be worth an embedding. */
    static final long MIN_SPEECH_MS = 1500;
    /** The utterance buffer's cap; an answer's first 8 s are plenty for an embedding. */
    static final long MAX_BUFFER_MS = 8000;
    /** Embeddings kept per person, the oldest dropped first. */
    static final int MAX_PER_PERSON = 10;
    /** Recent utterances whose embeddings stay in memory for the people layer to enrol. */
    static final int RECENT = 8;

    interface Props {
        String get(String key);
    }

    final float strong;
    final float weak;

    VoiceTuning(float strong, float weak) {
        this.strong = strong;
        this.weak = weak;
    }

    /** Each threshold from its property when it is a number in (0, 1] and weak <= strong; else both defaults. */
    static VoiceTuning from(Props props) {
        float s = parse(props.get(STRONG_PROP), DEFAULT_STRONG);
        float w = parse(props.get(WEAK_PROP), DEFAULT_WEAK);
        if (w > s) {
            return new VoiceTuning(DEFAULT_STRONG, DEFAULT_WEAK);
        }
        return new VoiceTuning(s, w);
    }

    private static float parse(String raw, float fallback) {
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }
        try {
            float v = Float.parseFloat(raw.trim());
            return v > 0f && v <= 1f ? v : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** VoiceStore.BAND_* for a best score. */
    int band(float score) {
        if (score >= strong) {
            return VoiceStore.BAND_STRONG;
        }
        return score >= weak ? VoiceStore.BAND_WEAK : VoiceStore.BAND_NONE;
    }

    static String bandName(int band) {
        return band == VoiceStore.BAND_STRONG ? "strong" : band == VoiceStore.BAND_WEAK ? "weak" : "none";
    }

    @Override
    public String toString() {
        return String.format(Locale.US, "voice strong=%.2f weak=%.2f", strong, weak);
    }
}
