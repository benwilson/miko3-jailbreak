package com.miko3.launcher;

/**
 * The speech service's two knobs (voice plan U5, KTD8), read once when the
 * voice loads. Plain Java so host tests can check the defaults and clamping.
 *
 * Defaults come from U1's measurements on the robot: 4 threads gave the
 * medium voice 1.10 s to first sound when idle, and 16 words is the longest
 * chunk it synthesizes in under 2 s. Explore's first live test still saw
 * 2.2-2.8 s to first audio for 13-word lines, so the default is 10 words: a
 * comma splits a long line sooner and the first chunk is ready faster. Under Explore's load 2 threads may do
 * better (untested), so both can be changed without a rebuild through debug
 * system properties, which the shell user may set, then a launcher restart:
 *
 *   adb shell setprop debug.miko3.speech.threads 2
 *   adb shell setprop debug.miko3.speech.max_words 12
 *   adb shell am force-stop com.miko3.launcher
 */
final class SpeechTuning {
    static final int DEFAULT_THREADS = 4;
    static final int DEFAULT_MAX_WORDS = 10;
    static final String THREADS_PROP = "debug.miko3.speech.threads";
    static final String MAX_WORDS_PROP = "debug.miko3.speech.max_words";

    /** The robot has four cores; more threads than that only contend. */
    static final int MIN_THREADS = 1;
    static final int MAX_THREADS = 4;
    static final int MIN_WORDS = 4;
    static final int MAX_WORDS = 40;

    /** Reads a system property; "" or null when unset. */
    interface Props {
        String get(String key);
    }

    final int threads;
    final int maxWords;

    SpeechTuning(int threads, int maxWords) {
        this.threads = threads;
        this.maxWords = maxWords;
    }

    static SpeechTuning from(Props props) {
        return new SpeechTuning(
                read(props, THREADS_PROP, DEFAULT_THREADS, MIN_THREADS, MAX_THREADS),
                read(props, MAX_WORDS_PROP, DEFAULT_MAX_WORDS, MIN_WORDS, MAX_WORDS));
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
        return "threads=" + threads + " maxWords=" + maxWords;
    }
}
