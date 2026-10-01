package com.miko3.mode.explore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Where a look's time goes (detector speed plan): decode the camera JPEG,
 * preprocess it into the model's input, run the model, copy the output into
 * Java, and decode the detections. One instance per look; Summary collects many
 * for the bench mode's p50/p95.
 *
 * Plain Java so it runs on the host JVM (scripts/tests/test_explore_detector.py).
 */
final class DetectorStages {
    static final int DECODE = 0;
    static final int PREP = 1;
    static final int RUN = 2;
    static final int COPY = 3;
    static final int DETECT = 4;
    /** Log names, in stage order; scripts/qa-detector-bench.py parses them. */
    static final String[] STAGES = {"decode", "prep", "run", "copy", "detect"};

    private final long[] nanos = new long[STAGES.length];

    void set(int stage, long ns) {
        nanos[stage] = ns;
    }

    long nanos(int stage) {
        return nanos[stage];
    }

    /** A snapshot: the recognizer reuses its own instance look to look. */
    DetectorStages copy() {
        DetectorStages c = new DetectorStages();
        System.arraycopy(nanos, 0, c.nanos, 0, nanos.length);
        return c;
    }

    long totalNanos() {
        long t = 0;
        for (long n : nanos) {
            t += n;
        }
        return t;
    }

    /** "decode 31.2, prep 12.0, run 905.1, copy 21.3, detect 16.0 ms". */
    String line() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < STAGES.length; i++) {
            if (i > 0) {
                b.append(", ");
            }
            b.append(STAGES[i]).append(' ').append(ms(nanos[i]));
        }
        return b.append(" ms").toString();
    }

    static String ms(long ns) {
        return String.format(Locale.US, "%.1f", ns / 1e6);
    }

    /**
     * Nearest-rank percentile of the values (the same rule as
     * scripts/bench-explore-detector.py's percentile); 0 for none.
     */
    static long percentile(long[] values, int q) {
        if (values.length == 0) {
            return 0;
        }
        long[] s = values.clone();
        Arrays.sort(s);
        int rank = (int) Math.ceil(q * s.length / 100.0);
        return s[Math.max(0, Math.min(s.length - 1, rank - 1))];
    }

    /** Many looks' stages, summarised as p50/p95 per stage and of the total. */
    static final class Summary {
        private final List<DetectorStages> looks = new ArrayList<DetectorStages>();

        void add(DetectorStages s) {
            looks.add(s);
        }

        int size() {
            return looks.size();
        }

        /** "total=950.2/1100.3 decode=31.0/35.2 ... " in ms, p50/p95. */
        String line() {
            StringBuilder b = new StringBuilder();
            b.append("total=").append(pair(column(-1)));
            for (int i = 0; i < STAGES.length; i++) {
                b.append(' ').append(STAGES[i]).append('=').append(pair(column(i)));
            }
            return b.toString();
        }

        private long[] column(int stage) {
            long[] v = new long[looks.size()];
            for (int i = 0; i < v.length; i++) {
                v[i] = stage < 0 ? looks.get(i).totalNanos() : looks.get(i).nanos(stage);
            }
            return v;
        }

        private static String pair(long[] v) {
            return ms(percentile(v, 50)) + "/" + ms(percentile(v, 95));
        }
    }
}
