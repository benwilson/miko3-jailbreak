package com.miko3.launcher;

/**
 * The speech engine's tunables (voice plan U5), read once at start from
 * system properties so the owner can try values over adb without a rebuild:
 * synthesis threads, the words per synthesis chunk, and (meeting plan U3,
 * KTD1) the deaf-window tail the ears keep closed after playback goes idle,
 * 500 ms until U2 measures it. Out-of-range values are clamped; unset or
 * unparsable ones fall back to the defaults.
 */
final class SpeechTuning {
    static final int DEFAULT_THREADS = 4;
    static final int DEFAULT_MAX_WORDS = 10;
    static final int DEFAULT_DEAF_TAIL_MS = 500;
    static final String THREADS_PROP = "debug.miko3.speech.threads";
    static final String MAX_WORDS_PROP = "debug.miko3.speech.max_words";
    static final String DEAF_TAIL_PROP = "debug.miko3.speech.deaf_tail_ms";

    static final int MIN_THREADS = 1;
    static final int MAX_THREADS = 4;
    static final int MIN_WORDS = 4;
    static final int MAX_WORDS = 40;
    static final int MIN_DEAF_TAIL_MS = 0;
    static final int MAX_DEAF_TAIL_MS = 3000;

    interface Props {
        String get(String key);
    }

    final int threads;
    final int maxWords;
    final int deafTailMs;

    SpeechTuning(int threads, int maxWords) {
        this(threads, maxWords, DEFAULT_DEAF_TAIL_MS);
    }

    SpeechTuning(int threads, int maxWords, int deafTailMs) {
        this.threads = threads;
        this.maxWords = maxWords;
        this.deafTailMs = deafTailMs;
    }

    static SpeechTuning from(Props props) {
        return new SpeechTuning(
                read(props, THREADS_PROP, DEFAULT_THREADS, MIN_THREADS, MAX_THREADS),
                read(props, MAX_WORDS_PROP, DEFAULT_MAX_WORDS, MIN_WORDS, MAX_WORDS),
                read(props, DEAF_TAIL_PROP, DEFAULT_DEAF_TAIL_MS, MIN_DEAF_TAIL_MS, MAX_DEAF_TAIL_MS));
    }

    private static int read(Props props, String key, int def, int min, int max) {
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

    @Override
    public String toString() {
        return "threads=" + threads + " maxWords=" + maxWords + " deafTailMs=" + deafTailMs;
    }
}
