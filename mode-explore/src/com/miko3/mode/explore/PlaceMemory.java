package com.miko3.mode.explore;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Where he has looked lately, by what it looked like (owner, 2026-10-01: "if he's
 * been somewhere in the last 30 minutes, he should try and find somewhere else to
 * go"): the complement to Coverage, whose dead reckoning drifts through wedges,
 * shoves and escapes. Each look's Print is kept for placeFadeMs (one per
 * placeRecordMs, placeMax at most, oldest dropped first) with its heading at
 * capture (NaN: not usable), the detector's labels and the time.
 *
 * A look's similarity is its best match among prints at least placeMinAgeMs old
 * taken within placeHeadingDeg of its heading, or at any heading when either
 * heading is unusable; its novelty runs from 1 (placeSimLow or less) to 0
 * (placeSimHigh or more). A plain or dark view (a wall up close, carpet, the
 * underside of a desk) is no evidence either way: NaN, and not kept.
 *
 * The print and threshold were chosen on 624 real frames from the 2026-10-01 roam
 * (out/camera-frames-run): the grey layout alone confused rooms with the same
 * shapes, the colour histogram alone rooms with the same light; their mean
 * separated hand-labelled same-place and different-place pairs best (AUC 0.90).
 * The detector's labels alone did worse (AUC 0.70) and added nothing on top, so
 * they are kept with the print but not scored.
 *
 * Each look's novelty also stands for its heading for placeGlanceMs, so the looks
 * of a curiosity scan score the headings it turned through; steer() hands RoamSteer
 * the lower of that and the grid's novelty (either says he has been there; the
 * grid often reads all-new after a drift). Only novelty is lowered: a blocked band
 * stays blocked, and the floor sensor, stalls and CPL rule as always.
 *
 * Plain Java (no Android or shared-driver imports), so it runs on the host JVM.
 * Prints are built on the camera's detect thread (Print.of); the memory itself is
 * not thread-safe, and the brain calls it from its one thread. Nothing is saved.
 */
final class PlaceMemory {
    /** The grey layout: 16 x 12 cell means. */
    static final int COLS = 16;
    static final int ROWS = 12;
    /** Hue (8 x 45 deg) by saturation (4) histogram. */
    static final int HUES = 8;
    static final int SATS = 4;
    /** The layout is compared shifted up to this many columns either way (~8 deg of heading). */
    static final int SHIFT = 2;
    /**
     * Below this mean step between neighbouring cells (grey levels), a view is plain:
     * on the real frames, walls up close and carpet read 2-13, rooms 20-60.
     */
    static final float MIN_TEXTURE = 16f;

    /**
     * What a look looked like: a 16 x 12 grey thumbnail and a hue/saturation
     * histogram (square-rooted, for the Bhattacharyya coefficient). About 1 KB.
     */
    static final class Print {
        final float[] grey;
        final float[] hist;
        final float texture;

        private Print(float[] grey, float[] hist, float texture) {
            this.grey = grey;
            this.hist = hist;
            this.texture = texture;
        }

        /** Too plain to tell one place from another. */
        boolean plain() {
            return texture < MIN_TEXTURE;
        }

        /**
         * From a decoded frame (0xRRGGBB, row-major), e.g. the camera's 80 x 60 openness
         * frame: one pass over its pixels, well under a millisecond at that size.
         */
        static Print of(int[] rgb, int width, int height) {
            float[] sum = new float[COLS * ROWS];
            int[] count = new int[COLS * ROWS];
            float[] hist = new float[HUES * SATS];
            for (int y = 0; y < height; y++) {
                int cy = Math.min(ROWS - 1, y * ROWS / height);
                for (int x = 0; x < width; x++) {
                    int p = rgb[y * width + x];
                    int r = (p >> 16) & 0xff;
                    int g = (p >> 8) & 0xff;
                    int b = p & 0xff;
                    int c = cy * COLS + Math.min(COLS - 1, x * COLS / width);
                    sum[c] += 0.299f * r + 0.587f * g + 0.114f * b;
                    count[c]++;
                    hist[hueBin(r, g, b) * SATS + satBin(r, g, b)]++;
                }
            }
            float[] grey = new float[COLS * ROWS];
            for (int i = 0; i < grey.length; i++) {
                grey[i] = count[i] == 0 ? 0f : sum[i] / count[i];
            }
            float total = Math.max(1, width * height);
            for (int i = 0; i < hist.length; i++) {
                hist[i] = (float) Math.sqrt(hist[i] / total);
            }
            return new Print(grey, hist, texture(grey));
        }

        private static int hueBin(int r, int g, int b) {
            int max = Math.max(r, Math.max(g, b));
            int min = Math.min(r, Math.min(g, b));
            float d = max - min;
            if (d <= 0) {
                return 0;
            }
            float h;
            if (max == r) {
                h = 60f * (g - b) / d;
            } else if (max == g) {
                h = 120f + 60f * (b - r) / d;
            } else {
                h = 240f + 60f * (r - g) / d;
            }
            if (h < 0) {
                h += 360f;
            }
            return Math.min(HUES - 1, (int) (h / (360f / HUES)));
        }

        private static int satBin(int r, int g, int b) {
            int max = Math.max(r, Math.max(g, b));
            if (max == 0) {
                return 0;
            }
            int min = Math.min(r, Math.min(g, b));
            return Math.min(SATS - 1, (int) (255f * (max - min) / max / (256f / SATS)));
        }

        /** The mean step between horizontal and vertical neighbours, summed. */
        private static float texture(float[] grey) {
            float dx = 0;
            float dy = 0;
            for (int y = 0; y < ROWS; y++) {
                for (int x = 0; x < COLS; x++) {
                    float v = grey[y * COLS + x];
                    if (x + 1 < COLS) {
                        dx += Math.abs(grey[y * COLS + x + 1] - v);
                    }
                    if (y + 1 < ROWS) {
                        dy += Math.abs(grey[(y + 1) * COLS + x] - v);
                    }
                }
            }
            return dx / (ROWS * (COLS - 1)) + dy / ((ROWS - 1) * COLS);
        }
    }

    /**
     * How alike two views are, about -1..1: the mean of the grey layouts' best
     * normalised correlation (shifted up to SHIFT columns) and the histograms'
     * Bhattacharyya coefficient (as 2 BC - 1). NaN when either is plain.
     */
    static double similarity(Print a, Print b) {
        if (a == null || b == null || a.plain() || b.plain()) {
            return Double.NaN;
        }
        double ncc = -1;
        for (int s = -SHIFT; s <= SHIFT; s++) {
            ncc = Math.max(ncc, shifted(a.grey, b.grey, s));
        }
        double bc = 0;
        for (int i = 0; i < a.hist.length; i++) {
            bc += a.hist[i] * b.hist[i];
        }
        return 0.5 * ncc + 0.5 * (2 * bc - 1);
    }

    /** Correlation of a's columns from max(0, s) with b's from max(0, -s), the overlap only. */
    private static double shifted(float[] a, float[] b, int s) {
        int w = COLS - Math.abs(s);
        int ax = Math.max(0, s);
        int bx = Math.max(0, -s);
        int n = w * ROWS;
        double ma = 0;
        double mb = 0;
        for (int y = 0; y < ROWS; y++) {
            for (int x = 0; x < w; x++) {
                ma += a[y * COLS + ax + x];
                mb += b[y * COLS + bx + x];
            }
        }
        ma /= n;
        mb /= n;
        double ab = 0;
        double aa = 0;
        double bb = 0;
        for (int y = 0; y < ROWS; y++) {
            for (int x = 0; x < w; x++) {
                double u = a[y * COLS + ax + x] - ma;
                double v = b[y * COLS + bx + x] - mb;
                ab += u * v;
                aa += u * u;
                bb += v * v;
            }
        }
        return aa <= 1e-9 || bb <= 1e-9 ? 0 : ab / Math.sqrt(aa * bb);
    }

    /** One look's verdict: its best similarity, how long ago that print was taken, and its novelty. */
    static final class Match {
        /** Best similarity among the prints that count; NaN when the view is plain or none count. */
        final double sim;
        /** The matched print's age, ms; -1 for none. */
        final long ageMs;
        /** 0 (familiar) .. 1 (new); NaN for a plain view. */
        final double novelty;
        /** The matched print, for noting each memory once. */
        final Object print;
        private final boolean seen;

        Match(double sim, long ageMs, double novelty, Object print, boolean seen) {
            this.sim = sim;
            this.ageMs = ageMs;
            this.novelty = novelty;
            this.print = print;
            this.seen = seen;
        }

        /** Similar enough (placeSeenSim) to a print that counts to say so. */
        boolean seen() {
            return seen;
        }

        /** The trace line: numbers only. */
        String note() {
            return String.format(Locale.US, "place: seen before (sim %.2f, %d min ago)", sim,
                    Math.round(ageMs / 60000.0));
        }
    }

    private static final class Kept {
        final Print print;
        final double headingDeg;
        final long atMs;
        final List<String> labels;

        Kept(Print print, double headingDeg, long atMs, List<String> labels) {
            this.print = print;
            this.headingDeg = headingDeg;
            this.atMs = atMs;
            this.labels = labels;
        }
    }

    /** A recent look's novelty at its heading. */
    private static final class Glance {
        final double headingDeg;
        final double novelty;
        final long atMs;

        Glance(double headingDeg, double novelty, long atMs) {
            this.headingDeg = headingDeg;
            this.novelty = novelty;
            this.atMs = atMs;
        }
    }

    private final ExploreTuning tuning;
    /** Oldest first. */
    private final ArrayDeque<Kept> kept = new ArrayDeque<Kept>();
    /** Oldest first. */
    private final ArrayDeque<Glance> glances = new ArrayDeque<Glance>();
    private long lastKeptMs = Long.MIN_VALUE / 4;
    /** Where his seeks went (seeking the unfamiliar): {print, when}, oldest first, kept for placeFadeMs. */
    private final ArrayDeque<Kept> sought = new ArrayDeque<Kept>();

    PlaceMemory(ExploreTuning tuning) {
        this.tuning = tuning;
    }

    /**
     * A look captured at atMs facing headingDeg (NaN: not usable), with the detector's
     * labels above the floor (may be null): scored against the prints that count,
     * then kept (one per placeRecordMs). Off (placeMax 0) or plain: novelty NaN.
     */
    Match look(Print p, Collection<String> labels, double headingDeg, long atMs) {
        if (p == null || p.plain() || tuning.placeMax <= 0) {
            return new Match(Double.NaN, -1, Double.NaN, null, false);
        }
        forget(atMs);
        double best = Double.NaN;
        Kept match = null;
        for (Kept k : kept) {
            if (atMs - k.atMs < tuning.placeMinAgeMs || !sameWay(headingDeg, k.headingDeg)) {
                continue;
            }
            double s = similarity(p, k.print);
            if (!Double.isNaN(s) && (Double.isNaN(best) || s > best)) {
                best = s;
                match = k;
            }
        }
        double novelty = Double.isNaN(best) ? 1.0 : novelty(best);
        if (atMs - lastKeptMs >= tuning.placeRecordMs) {
            kept.addLast(new Kept(p, headingDeg, atMs, labels == null ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(new ArrayList<String>(labels))));
            lastKeptMs = atMs;
            while (kept.size() > tuning.placeMax) {
                kept.pollFirst();
            }
        }
        if (!Double.isNaN(headingDeg)) {
            glances.addLast(new Glance(Heading.wrap(headingDeg), novelty, atMs));
        }
        return new Match(best, match == null ? -1 : atMs - match.atMs, novelty, match,
                match != null && best >= tuning.placeSeenSim);
    }

    /** A similarity as novelty: 1 at placeSimLow or less, 0 at placeSimHigh or more. */
    double novelty(double sim) {
        double f = (sim - tuning.placeSimLow) / (tuning.placeSimHigh - tuning.placeSimLow);
        return 1 - Math.max(0, Math.min(1, f));
    }

    /** The newest look's novelty within cameraHalfFovDeg of headingDeg in the last placeGlanceMs; NaN: none. */
    double noveltyAt(double headingDeg, long nowMs) {
        for (Iterator<Glance> it = glances.descendingIterator(); it.hasNext(); ) {
            Glance g = it.next();
            if (nowMs - g.atMs > tuning.placeGlanceMs) {
                break;
            }
            if (Math.abs(Heading.delta(g.headingDeg, Heading.wrap(headingDeg))) <= tuning.cameraHalfFovDeg) {
                return g.novelty;
            }
        }
        return Double.NaN;
    }

    /**
     * The steer's novelty: per bearing off his facing (left positive), the lower of
     * the grid's (null: none) and the place memory's, where either is known. In view
     * (within cameraHalfFovDeg) the place memory's is inView, the novelty of the look
     * the steer reads; out of view, with the heading usable, the newest look at that
     * heading. Null when neither has anything to say (the steer plans as before).
     */
    RoamSteer.Novelty steer(final RoamSteer.Novelty grid, final double facingDeg, final boolean usable,
                            final double inView, final long nowMs) {
        boolean placeKnows = !Double.isNaN(inView) || (usable && hasGlances(nowMs));
        if (!placeKnows) {
            return grid;
        }
        // An anonymous class, not a lambda: the Android build's bootclasspath has no LambdaMetafactory.
        return new RoamSteer.Novelty() {
            @Override
            public double at(double bearing) {
                double place = Math.abs(bearing) <= tuning.cameraHalfFovDeg ? inView
                        : usable ? noveltyAt(facingDeg + bearing, nowMs) : Double.NaN;
                if (grid == null) {
                    return place;
                }
                double g = grid.at(bearing);
                return Double.isNaN(g) ? place : Double.isNaN(place) ? g : Math.min(g, place);
            }
        };
    }

    private boolean hasGlances(long nowMs) {
        return !glances.isEmpty() && nowMs - glances.peekLast().atMs <= tuning.placeGlanceMs;
    }

    /**
     * A seek went to the place in this view (its target frame, or the view on arrival):
     * the next seek's frames that look like it (placeSeenSim or more, whatever their
     * age or heading) are where he went, and he picks somewhere else. Plain or null: nothing.
     */
    void markSought(Print p, long atMs) {
        if (p == null || p.plain() || tuning.placeMax <= 0) {
            return;
        }
        forget(atMs);
        sought.addLast(new Kept(p, Double.NaN, atMs, Collections.<String>emptyList()));
        while (sought.size() > tuning.placeMax) {
            sought.pollFirst();
        }
    }

    /** Whether this view looks like where a seek in the last placeFadeMs went. */
    boolean soughtBefore(Print p, long nowMs) {
        if (p == null || p.plain()) {
            return false;
        }
        forget(nowMs);
        for (Kept k : sought) {
            double s = similarity(p, k.print);
            if (!Double.isNaN(s) && s >= tuning.placeSeenSim) {
                return true;
            }
        }
        return false;
    }

    /**
     * Forget every print, glance and seek mark taken at or after atMs (bathroom privacy,
     * owner 2026-10-02: the looks that decided he was in a bathroom were taken inside).
     */
    void forgetSince(long atMs) {
        for (Iterator<Kept> it = kept.iterator(); it.hasNext(); ) {
            if (it.next().atMs >= atMs) {
                it.remove();
            }
        }
        for (Iterator<Kept> it = sought.iterator(); it.hasNext(); ) {
            if (it.next().atMs >= atMs) {
                it.remove();
            }
        }
        for (Iterator<Glance> it = glances.iterator(); it.hasNext(); ) {
            if (it.next().atMs >= atMs) {
                it.remove();
            }
        }
        lastKeptMs = kept.isEmpty() ? Long.MIN_VALUE / 4 : kept.peekLast().atMs;
    }

    /** Prints kept now. */
    int size() {
        return kept.size();
    }

    /** Forget everything (Explore stopping). */
    void clear() {
        kept.clear();
        glances.clear();
        sought.clear();
        lastKeptMs = Long.MIN_VALUE / 4;
    }

    private void forget(long nowMs) {
        while (!kept.isEmpty() && nowMs - kept.peekFirst().atMs >= tuning.placeFadeMs) {
            kept.pollFirst();
        }
        while (!sought.isEmpty() && nowMs - sought.peekFirst().atMs >= tuning.placeFadeMs) {
            sought.pollFirst();
        }
        while (!glances.isEmpty() && nowMs - glances.peekFirst().atMs > tuning.placeGlanceMs) {
            glances.pollFirst();
        }
    }

    private boolean sameWay(double a, double b) {
        return Double.isNaN(a) || Double.isNaN(b) || Math.abs(Heading.delta(a, b)) <= tuning.placeHeadingDeg;
    }
}
