package com.miko3.launcher;

/**
 * The recogniser's CPU switches (ears CPU work, 2026-09-30), read once at start
 * from system properties like SpeechTuning, so the owner can compare settings on
 * the robot over adb without a rebuild (scripts/qa-ears-cpu.py). Every switch is
 * off by default: unset, unparsable or out-of-range values give exactly the
 * meeting plan's KTD2 configuration (modified_beam_search with the hotwords file,
 * 2 active paths, 2 threads, continuous recognition).
 *
 * decoding: "modified_beam_search" (the default) or "greedy_search". sherpa-onnx
 * refuses a hotwords file with greedy_search (its config check fails), so greedy
 * decodes without the hotwords; the host bench found that costs some name hits.
 *
 * gate "wake": continuous recognition only while its words can matter. Outside a
 * conversation listen, with the Settings page's "answers when spoken to" switch
 * off, the classifier ignores every word unless the wake engine fired, so the
 * recogniser is then fed only after the engine fires in an utterance (with the
 * utterance's audio so far, held meanwhile). With that switch on, as by default,
 * the name, greeting and apology cues need every utterance's words, so the gate
 * stays open and nothing changes. The one difference while it is shut: the
 * recogniser's endpoint cannot split the utterance, so only the VAD's hangover
 * ends it (EarsSession).
 *
 * The one recogniser serves the one-shot listen too, so decoding, paths and
 * threads apply to both; the gate applies only to the ears session.
 */
final class EarsTuning {
    static final String BEAM = "modified_beam_search";
    static final String GREEDY = "greedy_search";
    static final String GATE_OFF = "off";
    static final String GATE_WAKE = "wake";

    /** KTD2: the defaults are the configuration the meeting plan chose. */
    static final String DEFAULT_DECODING = BEAM;
    static final int DEFAULT_PATHS = 2;
    static final int DEFAULT_THREADS = 2;

    static final String DECODING_PROP = "persist.miko3.ears.decoding";
    static final String PATHS_PROP = "persist.miko3.ears.paths";
    static final String THREADS_PROP = "persist.miko3.ears.threads";
    static final String GATE_PROP = "persist.miko3.ears.gate";

    static final int MIN_PATHS = 1;
    static final int MAX_PATHS = 8;
    static final int MIN_THREADS = 1;
    static final int MAX_THREADS = 4;

    final String decoding;
    final int paths;
    final int threads;
    final boolean gateWake;

    EarsTuning(String decoding, int paths, int threads, boolean gateWake) {
        this.decoding = decoding;
        this.paths = paths;
        this.threads = threads;
        this.gateWake = gateWake;
    }

    static EarsTuning defaults() {
        return new EarsTuning(DEFAULT_DECODING, DEFAULT_PATHS, DEFAULT_THREADS, false);
    }

    static EarsTuning from(SpeechTuning.Props props) {
        String d = trimmed(props.get(DECODING_PROP));
        String decoding = GREEDY.equals(d) ? GREEDY : DEFAULT_DECODING;
        return new EarsTuning(decoding,
                read(props, PATHS_PROP, DEFAULT_PATHS, MIN_PATHS, MAX_PATHS),
                read(props, THREADS_PROP, DEFAULT_THREADS, MIN_THREADS, MAX_THREADS),
                GATE_WAKE.equals(trimmed(props.get(GATE_PROP))));
    }

    /** The hotwords file goes to the recogniser only with beam search (sherpa-onnx's rule). */
    boolean hotwords() {
        return BEAM.equals(decoding);
    }

    private static String trimmed(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static int read(SpeechTuning.Props props, String key, int def, int min, int max) {
        String raw = props.get(key);
        if (raw == null || raw.trim().isEmpty()) {
            return def;
        }
        try {
            int v = Integer.parseInt(raw.trim());
            return Math.max(min, Math.min(max, v));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** The log's one line of it; the QA script reads it back (scripts/qa-ears-cpu.py). */
    @Override
    public String toString() {
        return "decoding=" + decoding + " paths=" + paths + " threads=" + threads + " hotwords="
                + (hotwords() ? "on" : "off") + " gate=" + (gateWake ? GATE_WAKE : GATE_OFF);
    }
}
