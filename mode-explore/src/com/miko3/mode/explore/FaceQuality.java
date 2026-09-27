package com.miko3.mode.explore;

/**
 * The face quality gate and the brighten (on-device face recognition plan
 * KTD3, R11, R12). Plain Java; the bench (U9) mirrors it in numpy with the
 * same constants.
 *
 * check() runs on the aligned crop, in this order, and gives one reason:
 *  1. too small: the face box is narrower than the minimum width, in frame pixels;
 *  2. too dark: the aligned crop's mean luma is below the dark floor;
 *  3. too blurry: the variance of the Laplacian of the aligned grey crop,
 *     measured before any brightening, is below the blur floor.
 * A crop that passes with mean luma below the dim level is dim, and its verdict
 * carries the brighten table. A migration call (KTD11: a stored photo, already
 * accepted once) never rejects; it only marks a crop dim.
 *
 * Luma is BT.601 in Brightness's integer weights, (77 R + 150 G + 29 B) / 256.
 * The grey crop for the blur score is that luma unrounded; the Laplacian is
 * the 4-neighbour kernel over interior pixels only, its variance the population
 * variance.
 *
 * The brighten is one lookup table from the crop, applied to R, G and B alike:
 * a gamma of log(110/255) / log(mean/255), clamped to 0.4..1.0, then a linear
 * stretch that takes the gamma'd 1st and 99th luma percentiles to 0 and 255,
 * rounded half up and clamped. The percentiles are of the integer luma
 * ((77 R + 150 G + 29 B) >> 8), sorted, at index floor(q * (n - 1)). The same
 * table brightens the stored loose crop, so the stored photo is never
 * brightened twice.
 */
final class FaceQuality {
    /** The mean luma the gamma aims for. */
    static final double GAMMA_TARGET = 110;
    static final double GAMMA_MIN = 0.4;
    static final double GAMMA_MAX = 1.0;
    static final double LOW_PERCENTILE = 0.01;
    static final double HIGH_PERCENTILE = 0.99;

    /** Why a crop is neither matched nor stored. */
    enum Reason {
        TOO_SMALL, TOO_DARK, TOO_BLURRY
    }

    /** The gate's thresholds, from the launcher settings (KTD5). */
    static final class Thresholds {
        static final Thresholds DEFAULTS = new Thresholds(48, 40, 90, 30);

        /** Minimum face box width, frame pixels. */
        final int minWidth;
        /** Mean luma below which a crop is too dark. */
        final double darkFloor;
        /** Mean luma below which a usable crop is brightened. */
        final double dimLevel;
        /** Laplacian variance below which a crop is too blurry. */
        final double blurFloor;

        Thresholds(int minWidth, double darkFloor, double dimLevel, double blurFloor) {
            this.minWidth = minWidth;
            this.darkFloor = darkFloor;
            this.dimLevel = dimLevel;
            this.blurFloor = blurFloor;
        }

        @Override
        public String toString() {
            return "Thresholds{minWidth=" + minWidth + ", darkFloor=" + darkFloor + ", dimLevel=" + dimLevel
                    + ", blurFloor=" + blurFloor + "}";
        }
    }

    /** What check() made of one crop. */
    static final class Verdict {
        /** The one rejection reason, or null when the crop is usable. */
        final Reason reason;
        /** Usable but dim: brighten() applies table. */
        final boolean dim;
        /** The brighten table (256 entries) when dim, else null. */
        final int[] table;
        final float faceWidth;
        final double meanLuma;
        /** Laplacian variance of the crop as given, before any brightening. */
        final double blur;

        Verdict(Reason reason, boolean dim, int[] table, float faceWidth, double meanLuma, double blur) {
            this.reason = reason;
            this.dim = dim;
            this.table = table;
            this.faceWidth = faceWidth;
            this.meanLuma = meanLuma;
            this.blur = blur;
        }

        boolean ok() {
            return reason == null;
        }

        /**
         * The pixels (the aligned crop, or the stored loose crop) brightened by
         * this verdict's table; the same array, untouched, when not dim.
         */
        int[] brighten(int[] argb) {
            return table == null ? argb : apply(argb, table);
        }

        @Override
        public String toString() {
            return "Verdict{reason=" + reason + ", dim=" + dim + ", width=" + faceWidth + ", luma=" + meanLuma
                    + ", blur=" + blur + "}";
        }
    }

    private FaceQuality() {
    }

    /**
     * The gate for one face: its box width in frame pixels and its aligned w x h
     * ARGB crop. With migration true nothing is rejected (KTD11).
     */
    static Verdict check(float faceWidthPx, int[] alignedArgb, int w, int h, Thresholds t, boolean migration) {
        double luma = meanLuma(alignedArgb);
        double blur = laplacianVariance(alignedArgb, w, h);
        Reason reason = null;
        if (!migration) {
            if (!(faceWidthPx >= t.minWidth)) {
                reason = Reason.TOO_SMALL;
            } else if (!(luma >= t.darkFloor)) {
                reason = Reason.TOO_DARK;
            } else if (!(blur >= t.blurFloor)) {
                reason = Reason.TOO_BLURRY;
            }
        }
        boolean dim = reason == null && luma < t.dimLevel;
        return new Verdict(reason, dim, dim ? table(alignedArgb) : null, faceWidthPx, luma, blur);
    }

    /** Mean luma (0..255) of the pixels; NaN when there are none. */
    static double meanLuma(int[] argb) {
        return Brightness.meanLuma(argb, argb == null ? 0 : argb.length);
    }

    /** Variance of the 4-neighbour Laplacian of the grey w x h crop, interior pixels; 0 under 3x3. */
    static double laplacianVariance(int[] argb, int w, int h) {
        if (argb == null || w < 3 || h < 3 || argb.length < w * h) {
            return 0;
        }
        double[] g = new double[w * h];
        for (int i = 0; i < g.length; i++) {
            g[i] = luma256(argb[i]) / 256.0;
        }
        int n = (w - 2) * (h - 2);
        double[] lap = new double[n];
        double sum = 0;
        int k = 0;
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                double v = g[i - w] + g[i + w] + g[i - 1] + g[i + 1] - 4 * g[i];
                lap[k++] = v;
                sum += v;
            }
        }
        double mean = sum / n;
        double var = 0;
        for (double v : lap) {
            var += (v - mean) * (v - mean);
        }
        return var / n;
    }

    /** The brighten lookup table (256 entries, 0..255) for these pixels. */
    static int[] table(int[] argb) {
        double mean = meanLuma(argb);
        double gamma;
        if (!(mean > 0)) {
            gamma = GAMMA_MIN;
        } else if (mean >= 255) {
            gamma = GAMMA_MAX;
        } else {
            gamma = Math.log(GAMMA_TARGET / 255) / Math.log(mean / 255);
            gamma = Math.max(GAMMA_MIN, Math.min(GAMMA_MAX, gamma));
        }
        int[] hist = new int[256];
        for (int p : argb) {
            hist[luma256(p) >> 8]++;
        }
        int n = argb.length;
        int lo = percentile(hist, (int) (LOW_PERCENTILE * (n - 1)));
        int hi = percentile(hist, (int) (HIGH_PERCENTILE * (n - 1)));
        double glo = curve(lo, gamma);
        double ghi = curve(hi, gamma);
        int[] out = new int[256];
        for (int v = 0; v < 256; v++) {
            double x = ghi > glo ? (curve(v, gamma) - glo) * 255.0 / (ghi - glo) : curve(v, gamma);
            int r = (int) Math.floor(x + 0.5);
            out[v] = r < 0 ? 0 : (r > 255 ? 255 : r);
        }
        return out;
    }

    /** The pixels with table applied to R, G and B; alpha kept. A new array. */
    static int[] apply(int[] argb, int[] table) {
        int[] out = new int[argb.length];
        for (int i = 0; i < argb.length; i++) {
            int p = argb[i];
            out[i] = (p & 0xff000000) | (table[(p >> 16) & 0xff] << 16) | (table[(p >> 8) & 0xff] << 8)
                    | table[p & 0xff];
        }
        return out;
    }

    /** 256 times the BT.601 luma, Brightness's integer weights. */
    private static int luma256(int p) {
        return 77 * ((p >> 16) & 0xff) + 150 * ((p >> 8) & 0xff) + 29 * (p & 0xff);
    }

    private static double curve(int v, double gamma) {
        return 255.0 * Math.pow(v / 255.0, gamma);
    }

    /** The value at a sorted index of the histogram's samples. */
    private static int percentile(int[] hist, int index) {
        int seen = 0;
        for (int v = 0; v < 256; v++) {
            seen += hist[v];
            if (seen > index) {
                return v;
            }
        }
        return 255;
    }
}
