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
    /**
     * Owner 2026-10-03: renamed from persist.miko3.voice.strong/weak. On the robot those were set
     * to 0.95/0.90 as a stop-gap (effectively no voice adoption) after CAM++ took the owner's wife
     * for him at 0.67-0.82; the margin rule below replaces that stop-gap, so the old names are
     * ignored (LEGACY_*: logged when set, to be cleared with setprop NAME '""').
     */
    static final String STRONG_PROP = "persist.miko3.voiceid.strong";
    static final String WEAK_PROP = "persist.miko3.voiceid.weak";
    static final String LEGACY_STRONG_PROP = "persist.miko3.voice.strong";
    static final String LEGACY_WEAK_PROP = "persist.miko3.voice.weak";
    /** Debug only: "1" when the launcher starts runs the compute() timing bench (qa-voice-bench.py). */
    static final String BENCH_PROP = "debug.miko3.voice_bench";
    /**
     * Debug only (owner 2026-10-03): "1" when the launcher starts turns on the multi-model
     * evaluation (VoiceEval) over whichever extra models scripts/push-voice-eval-models.py put
     * in files/voiceeval/models/. Off, the evaluation's prints are deleted.
     */
    static final String EVAL_PROP = "debug.miko3.voice_eval";

    static final float DEFAULT_STRONG = 0.65f;
    static final float DEFAULT_WEAK = 0.45f;
    /**
     * Owner 2026-10-03 (CAM++ through the robot's mic chain scored the owner's wife 0.67-0.82
     * against his prints): a strong match must lead the second-best stored person by this much,
     * else it is only weak.
     */
    static final float MARGIN = 0.10f;
    /** With only one person stored there is no runner-up: the score must clear strong by this much. */
    static final float SOLO_EXTRA = 0.08f;
    /** A person with fewer prints than this is never a strong match: a thin centroid flatters anyone. */
    static final int MIN_PRINTS = 4;

    static final int SAMPLE_RATE = 16000;
    /** Less speech than this says too little about a voice to be worth an embedding. */
    static final long MIN_SPEECH_MS = 1500;
    /**
     * Robot 2026-10-03: a call ("Hey Miko ...") this long is embedded too, as the conversation's
     * first voice (the voice gate's reference is then the caller, not whatever answers first).
     */
    static final long MIN_CALL_SPEECH_MS = 1200;
    /** The utterance buffer's cap: the span the embedded clip is picked from. */
    static final long MAX_BUFFER_MS = 8000;
    /**
     * Robot 2026-10-03: CAM++ took 1.8-8 s for 3-8 s of speech under Explore's load, slower than
     * real time, so the answer's voice came a turn late. Only the EMBED_MS with the most speech
     * energy is embedded; CAM++ is reliable at 2-3 s, and the strong/weak thresholds stay as they
     * are (enrolled prints are made from the same 3 s clips, so scores stay comparable).
     */
    static final long EMBED_MS = 3000;
    /** Embeddings kept per person, the oldest dropped first (owner 2026-10-03: 20, was 10, so prints build up). */
    static final int MAX_PER_PERSON = 20;
    /** Recent utterances whose embeddings stay in memory for the people layer to enrol. */
    static final int RECENT = 8;

    interface Props {
        String get(String key);
    }

    final float strong;
    final float weak;
    /** The old stop-gap properties (LEGACY_*) are set: they are ignored, and the launcher says so once. */
    final boolean legacySet;

    VoiceTuning(float strong, float weak) {
        this(strong, weak, false);
    }

    VoiceTuning(float strong, float weak, boolean legacySet) {
        this.strong = strong;
        this.weak = weak;
        this.legacySet = legacySet;
    }

    /** Each threshold from its property when it is a number in (0, 1] and weak <= strong; else both defaults. */
    static VoiceTuning from(Props props) {
        boolean legacy = isSet(props.get(LEGACY_STRONG_PROP)) || isSet(props.get(LEGACY_WEAK_PROP));
        float s = parse(props.get(STRONG_PROP), DEFAULT_STRONG);
        float w = parse(props.get(WEAK_PROP), DEFAULT_WEAK);
        if (w > s) {
            return new VoiceTuning(DEFAULT_STRONG, DEFAULT_WEAK, legacy);
        }
        return new VoiceTuning(s, w, legacy);
    }

    private static boolean isSet(String raw) {
        return raw != null && !raw.trim().isEmpty() && !raw.trim().equals("\"\"");
    }

    /**
     * Owner 2026-10-03: a best score that may stand as that person (the STRONG band): at least
     * strong, from someone with MIN_PRINTS prints, and either MARGIN ahead of the second-best
     * person or, with nobody else stored (margin NaN), SOLO_EXTRA above strong.
     */
    boolean confident(float score, float margin, int prints) {
        if (score < strong || prints < MIN_PRINTS) {
            return false;
        }
        return Float.isNaN(margin) ? score >= strong + SOLO_EXTRA : margin >= MARGIN;
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
        return String.format(Locale.US, "voice strong=%.2f weak=%.2f margin=%.2f solo=+%.2f prints>=%d", strong, weak,
                MARGIN, SOLO_EXTRA, MIN_PRINTS);
    }
}
