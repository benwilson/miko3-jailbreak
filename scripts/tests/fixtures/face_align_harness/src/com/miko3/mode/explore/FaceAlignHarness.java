package com.miko3.mode.explore;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Random;
import javax.imageio.ImageIO;

/**
 * Host harness for FaceAlign and FaceQuality (on-device face recognition plan
 * U2, KTD2, KTD3). Prints one PASS/FAIL line per scenario.
 *
 * Usage:
 *   --faces DIR                 run the scenarios (DIR holds the public-domain face fixtures)
 *   --fit x0 y0 ... x4 y4       print "FIT m00 m01 m02 m10 m11 m12" (or "FIT null")
 *   --warp IN W H m00..m12 OUT  warp IN (int32 LE ARGB, W x H) to OUT (float32 LE, 112x112x3 RGB)
 *   --quality IN W H            print "LUMA", "LAPVAR" and "TABLE" (256 ints) for IN
 */
public final class FaceAlignHarness {
    /** YuNet 2026may landmarks on scripts/tests/fixtures/faces/obama-2012.jpg (500x624), from the Python reference. */
    private static final float[] OBAMA_LANDMARKS = {
        216.0f, 107.8f, 274.9f, 107.7f, 244.5f, 139.0f, 217.8f, 162.5f, 273.7f, 162.8f,
    };

    private static final FaceQuality.Thresholds T = FaceQuality.Thresholds.DEFAULTS;

    public static void main(String[] args) throws IOException {
        if (args.length == 11 && args[0].equals("--fit")) {
            float[] lm = new float[10];
            for (int i = 0; i < 10; i++) {
                lm[i] = Float.parseFloat(args[i + 1]);
            }
            double[] m = FaceAlign.fit(lm);
            StringBuilder sb = new StringBuilder("FIT");
            if (m == null) {
                sb.append(" null");
            } else {
                for (double v : m) {
                    sb.append(' ').append(v);
                }
            }
            System.out.println(sb);
            return;
        }
        if (args.length == 11 && args[0].equals("--warp")) {
            int w = Integer.parseInt(args[2]);
            int h = Integer.parseInt(args[3]);
            double[] m = new double[6];
            for (int i = 0; i < 6; i++) {
                m[i] = Double.parseDouble(args[4 + i]);
            }
            float[] rgb = FaceAlign.warp(readArgb(args[1], w * h), w, h, m);
            ByteBuffer b = ByteBuffer.allocate(rgb.length * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (float v : rgb) {
                b.putFloat(v);
            }
            Files.write(Paths.get(args[10]), b.array());
            return;
        }
        if (args.length == 4 && args[0].equals("--quality")) {
            int w = Integer.parseInt(args[2]);
            int h = Integer.parseInt(args[3]);
            int[] argb = readArgb(args[1], w * h);
            System.out.println("LUMA " + FaceQuality.meanLuma(argb));
            System.out.println("LAPVAR " + FaceQuality.laplacianVariance(argb, w, h));
            StringBuilder sb = new StringBuilder("TABLE");
            for (int v : FaceQuality.table(argb)) {
                sb.append(' ').append(v);
            }
            System.out.println(sb);
            return;
        }
        String faces = args.length == 2 && args[0].equals("--faces") ? args[1] : "scripts/tests/fixtures/faces";

        check("identity_landmarks_give_identity_and_same_crop", identity());
        check("rotated_and_scaled_landmarks_recovered", rotatedScaled());
        check("mirrored_landmarks_give_no_reflection", mirrored());
        check("degenerate_landmarks_give_no_fit", degenerate());
        check("aligned_crop_is_112_square_rgb", alignedShape(faces));
        check("dark_crop_rejected_too_dark", dark());
        check("dim_crop_marked_dim_and_brightened_into_range", dim());
        check("blurred_face_under_floor_sharp_face_clears_it", blur(faces));
        check("blur_measured_before_brightening", blurBeforeBrighten());
        check("narrow_face_rejected_too_small_before_other_checks", small());
        check("dark_is_checked_before_blurry", darkBeforeBlurry());
        check("bright_crop_unchanged_by_brighten", bright());
        check("stored_loose_crop_gets_the_same_table", looseCropSameTable());
        check("migration_brightens_dim_but_never_rejects", migration());
        check("thresholds_are_the_ones_given", thresholdsGiven());
    }

    // ---- alignment ----

    private static String identity() {
        float[] lm = new float[10];
        for (int i = 0; i < 10; i++) {
            lm[i] = (float) FaceAlign.TEMPLATE[i];
        }
        double[] m = FaceAlign.fit(lm);
        if (m == null) {
            return "no fit";
        }
        double[] id = {1, 0, 0, 0, 1, 0};
        for (int i = 0; i < 6; i++) {
            if (Math.abs(m[i] - id[i]) > 1e-4) {
                return "not identity: " + Arrays.toString(m);
            }
        }
        int[] in = noise(FaceAlign.SIDE, FaceAlign.SIDE, 128, 100, 7, true);
        int[] out = FaceAlign.toArgb(FaceAlign.warp(in, FaceAlign.SIDE, FaceAlign.SIDE, m));
        return Arrays.equals(in, out) ? null : "warped crop differs from the input";
    }

    private static String rotatedScaled() {
        double ang = Math.toRadians(15);
        double s = 2.0;
        float[] lm = new float[10];
        for (int i = 0; i < 5; i++) {
            double x = FaceAlign.TEMPLATE[2 * i];
            double y = FaceAlign.TEMPLATE[2 * i + 1];
            lm[2 * i] = (float) (s * (Math.cos(ang) * x - Math.sin(ang) * y) + 140);
            lm[2 * i + 1] = (float) (s * (Math.sin(ang) * x + Math.cos(ang) * y) + 60);
        }
        double[] m = FaceAlign.fit(lm);
        if (m == null) {
            return "no fit";
        }
        double rot = FaceAlign.rotationDegrees(m);
        double scale = FaceAlign.scale(m);
        if (Math.abs(rot - (-15)) > 0.5) {
            return "rotation " + rot + " not -15";
        }
        if (Math.abs(scale - 0.5) > 0.005) {
            return "scale " + scale + " not 0.5";
        }
        for (int i = 0; i < 5; i++) {
            double[] p = FaceAlign.apply(m, lm[2 * i], lm[2 * i + 1]);
            if (Math.hypot(p[0] - FaceAlign.TEMPLATE[2 * i], p[1] - FaceAlign.TEMPLATE[2 * i + 1]) > 0.01) {
                return "landmark " + i + " maps to " + Arrays.toString(p);
            }
        }
        return null;
    }

    private static String mirrored() {
        float[] lm = new float[10];
        for (int i = 0; i < 5; i++) {
            lm[2 * i] = (float) (200 - FaceAlign.TEMPLATE[2 * i]);
            lm[2 * i + 1] = (float) FaceAlign.TEMPLATE[2 * i + 1];
        }
        double[] m = FaceAlign.fit(lm);
        if (m == null) {
            return "no fit";
        }
        double det = m[0] * m[4] - m[1] * m[3];
        if (!(det > 0)) {
            return "reflection: det " + det;
        }
        if (Math.abs(m[0] - m[4]) > 1e-9 || Math.abs(m[1] + m[3]) > 1e-9) {
            return "not a similarity: " + Arrays.toString(m);
        }
        return null;
    }

    private static String degenerate() {
        float[] same = {50, 50, 50, 50, 50, 50, 50, 50, 50, 50};
        if (FaceAlign.fit(same) != null) {
            return "five equal points fitted";
        }
        if (FaceAlign.fit(new float[4]) != null) {
            return "short landmarks fitted";
        }
        float[] nan = same.clone();
        nan[3] = Float.NaN;
        nan[0] = 10;
        if (FaceAlign.fit(nan) != null) {
            return "NaN landmark fitted";
        }
        return FaceAlign.fit(null) == null ? null : "null fitted";
    }

    private static String alignedShape(String faces) throws IOException {
        int[][] img = new int[1][];
        int[] wh = new int[2];
        img[0] = load(faces + "/obama-2012.jpg", wh);
        int[] a = FaceAlign.align(img[0], wh[0], wh[1], OBAMA_LANDMARKS);
        if (a == null || a.length != FaceAlign.SIDE * FaceAlign.SIDE) {
            return "aligned crop " + (a == null ? "null" : a.length + " px");
        }
        float[] rgb = FaceAlign.warp(img[0], wh[0], wh[1], FaceAlign.fit(OBAMA_LANDMARKS));
        return rgb.length == 112 * 112 * 3 ? null : "rgb length " + rgb.length;
    }

    // ---- quality gate ----

    private static String dark() {
        int[] crop = noise(112, 112, 25, 30, 1, false);
        FaceQuality.Verdict v = FaceQuality.check(100, crop, 112, 112, T, false);
        return v.reason == FaceQuality.Reason.TOO_DARK && !v.ok() ? null : "verdict " + v;
    }

    private static String dim() {
        int[] crop = noise(112, 112, 65, 60, 2, false);
        FaceQuality.Verdict v = FaceQuality.check(100, crop, 112, 112, T, false);
        if (!v.ok() || !v.dim) {
            return "verdict " + v;
        }
        int[] b = v.brighten(crop);
        double before = FaceQuality.meanLuma(crop);
        double after = FaceQuality.meanLuma(b);
        if (!(after >= T.dimLevel && after <= 200)) {
            return "mean luma " + before + " -> " + after + ", not raised into range";
        }
        return null;
    }

    private static String blur(String faces) throws IOException {
        int[] wh = new int[2];
        int[] frame = load(faces + "/obama-2012.jpg", wh);
        int[] sharp = FaceAlign.align(frame, wh[0], wh[1], OBAMA_LANDMARKS);
        int[] soft = gaussian(sharp, 112, 112, 2.0);
        FaceQuality.Verdict vs = FaceQuality.check(59, sharp, 112, 112, T, false);
        FaceQuality.Verdict vb = FaceQuality.check(59, soft, 112, 112, T, false);
        if (!vs.ok()) {
            return "sharp face rejected: " + vs;
        }
        if (vb.reason != FaceQuality.Reason.TOO_BLURRY) {
            return "blurred face not rejected as blurry: " + vb;
        }
        return null;
    }

    private static String blurBeforeBrighten() {
        int[] crop = noise(112, 112, 60, 40, 3, false);
        FaceQuality.Verdict v = FaceQuality.check(100, crop, 112, 112, T, false);
        if (!v.dim) {
            return "fixture not dim: " + v;
        }
        double raw = FaceQuality.laplacianVariance(crop, 112, 112);
        double bright = FaceQuality.laplacianVariance(v.brighten(crop), 112, 112);
        if (v.blur != raw) {
            return "verdict blur " + v.blur + " is not the raw crop's " + raw;
        }
        return bright > raw ? null : "brightening did not change the blur score (" + raw + " vs " + bright + ")";
    }

    private static String small() {
        int[] flatDark = flat(112, 112, 10);
        FaceQuality.Verdict v = FaceQuality.check(T.minWidth - 1, flatDark, 112, 112, T, false);
        if (v.reason != FaceQuality.Reason.TOO_SMALL) {
            return "narrow dark flat face gave " + v;
        }
        FaceQuality.Verdict edge = FaceQuality.check(T.minWidth, noise(112, 112, 130, 80, 4, false), 112, 112, T, false);
        return edge.ok() ? null : "face exactly min width rejected: " + edge;
    }

    private static String darkBeforeBlurry() {
        FaceQuality.Verdict v = FaceQuality.check(100, flat(112, 112, 10), 112, 112, T, false);
        return v.reason == FaceQuality.Reason.TOO_DARK ? null : "dark flat face gave " + v;
    }

    private static String bright() {
        int[] crop = noise(112, 112, 150, 80, 5, true);
        FaceQuality.Verdict v = FaceQuality.check(100, crop, 112, 112, T, false);
        if (!v.ok() || v.dim) {
            return "bright crop verdict " + v;
        }
        int[] copy = crop.clone();
        return Arrays.equals(v.brighten(crop), copy) ? null : "brighten changed a bright crop";
    }

    private static String looseCropSameTable() {
        int[] aligned = noise(112, 112, 60, 50, 6, true);
        int[] loose = noise(224, 224, 55, 70, 8, true);
        FaceQuality.Verdict v = FaceQuality.check(100, aligned, 112, 112, T, false);
        if (!v.dim || v.table == null || v.table.length != 256) {
            return "fixture verdict " + v;
        }
        int[] b = v.brighten(loose);
        if (b.length != loose.length) {
            return "length changed";
        }
        for (int i = 0; i < loose.length; i++) {
            int p = loose[i];
            int q = b[i];
            if ((q >>> 24) != (p >>> 24)
                    || ((q >> 16) & 0xff) != v.table[(p >> 16) & 0xff]
                    || ((q >> 8) & 0xff) != v.table[(p >> 8) & 0xff]
                    || (q & 0xff) != v.table[p & 0xff]) {
                return "pixel " + i + " not mapped by the aligned crop's table";
            }
        }
        return Arrays.equals(v.table, FaceQuality.table(aligned)) ? null : "verdict table is not table(aligned)";
    }

    private static String migration() {
        FaceQuality.Verdict dark = FaceQuality.check(100, flat(112, 112, 20), 112, 112, T, true);
        FaceQuality.Verdict blurry = FaceQuality.check(100, flat(112, 112, 130), 112, 112, T, true);
        FaceQuality.Verdict small = FaceQuality.check(10, noise(112, 112, 130, 80, 9, false), 112, 112, T, true);
        if (dark.reason != null || blurry.reason != null || small.reason != null) {
            return "migration rejected: " + dark + " / " + blurry + " / " + small;
        }
        int[] crop = noise(112, 112, 65, 60, 10, false);
        FaceQuality.Verdict dim = FaceQuality.check(100, crop, 112, 112, T, true);
        if (!dim.dim || !(FaceQuality.meanLuma(dim.brighten(crop)) > FaceQuality.meanLuma(crop))) {
            return "migration did not brighten a dim crop: " + dim;
        }
        FaceQuality.Verdict live = FaceQuality.check(100, flat(112, 112, 130), 112, 112, T, false);
        return live.reason == FaceQuality.Reason.TOO_BLURRY ? null : "live flat crop not blurry: " + live;
    }

    private static String thresholdsGiven() {
        FaceQuality.Thresholds d = FaceQuality.Thresholds.DEFAULTS;
        if (d.minWidth != 48 || d.darkFloor != 40 || d.dimLevel != 90 || d.blurFloor != 30) {
            return "defaults " + d;
        }
        int[] crop = noise(112, 112, 65, 60, 11, false);
        FaceQuality.Thresholds strict = new FaceQuality.Thresholds(48, 70, 90, 30);
        FaceQuality.Verdict v = FaceQuality.check(100, crop, 112, 112, strict, false);
        if (v.reason != FaceQuality.Reason.TOO_DARK) {
            return "raised dark floor ignored: " + v;
        }
        FaceQuality.Thresholds lax = new FaceQuality.Thresholds(48, 40, 50, 1e9);
        v = FaceQuality.check(100, crop, 112, 112, lax, false);
        return v.reason == FaceQuality.Reason.TOO_BLURRY ? null : "raised blur floor ignored: " + v;
    }

    // ---- fixtures ----

    /** Grey (or colour) noise around a mean luma. */
    private static int[] noise(int w, int h, int mean, int amplitude, long seed, boolean colour) {
        Random r = new Random(seed);
        int[] out = new int[w * h];
        for (int i = 0; i < out.length; i++) {
            int g = mean + (int) Math.round((r.nextDouble() - 0.5) * amplitude);
            int rr = clamp(g + (colour ? r.nextInt(21) - 10 : 0));
            int gg = clamp(g);
            int bb = clamp(g + (colour ? r.nextInt(21) - 10 : 0));
            out[i] = 0xff000000 | (rr << 16) | (gg << 8) | bb;
        }
        return out;
    }

    private static int[] flat(int w, int h, int v) {
        int[] out = new int[w * h];
        Arrays.fill(out, 0xff000000 | (v << 16) | (v << 8) | v);
        return out;
    }

    /** A separable Gaussian blur, edges clamped. */
    private static int[] gaussian(int[] argb, int w, int h, double sigma) {
        int r = (int) Math.ceil(3 * sigma);
        double[] k = new double[2 * r + 1];
        double sum = 0;
        for (int i = -r; i <= r; i++) {
            k[i + r] = Math.exp(-i * i / (2 * sigma * sigma));
            sum += k[i + r];
        }
        for (int i = 0; i < k.length; i++) {
            k[i] /= sum;
        }
        double[][] ch = new double[3][w * h];
        for (int i = 0; i < w * h; i++) {
            ch[0][i] = (argb[i] >> 16) & 0xff;
            ch[1][i] = (argb[i] >> 8) & 0xff;
            ch[2][i] = argb[i] & 0xff;
        }
        for (int c = 0; c < 3; c++) {
            double[] tmp = new double[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    double s = 0;
                    for (int i = -r; i <= r; i++) {
                        s += k[i + r] * ch[c][y * w + Math.max(0, Math.min(w - 1, x + i))];
                    }
                    tmp[y * w + x] = s;
                }
            }
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    double s = 0;
                    for (int i = -r; i <= r; i++) {
                        s += k[i + r] * tmp[Math.max(0, Math.min(h - 1, y + i)) * w + x];
                    }
                    ch[c][y * w + x] = s;
                }
            }
        }
        int[] out = new int[w * h];
        for (int i = 0; i < out.length; i++) {
            out[i] = 0xff000000 | (clamp((int) Math.round(ch[0][i])) << 16)
                    | (clamp((int) Math.round(ch[1][i])) << 8) | clamp((int) Math.round(ch[2][i]));
        }
        return out;
    }

    private static int[] load(String path, int[] wh) throws IOException {
        BufferedImage img = ImageIO.read(new File(path));
        wh[0] = img.getWidth();
        wh[1] = img.getHeight();
        return img.getRGB(0, 0, wh[0], wh[1], null, 0, wh[0]);
    }

    private static int[] readArgb(String path, int n) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(Paths.get(path))).order(ByteOrder.LITTLE_ENDIAN);
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            out[i] = b.getInt();
        }
        return out;
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static void check(String name, String failure) {
        System.out.println(failure == null ? "PASS " + name : "FAIL " + name + ": " + failure);
    }
}
