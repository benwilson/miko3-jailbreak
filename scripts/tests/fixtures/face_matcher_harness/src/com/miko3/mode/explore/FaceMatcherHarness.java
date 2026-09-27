package com.miko3.mode.explore;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import javax.imageio.ImageIO;

/**
 * Host harness for FaceMatcher (on-device face recognition plan U3, R1, R2,
 * R17, R18, KTD1, KTD5). Prints one PASS/FAIL line per scenario.
 *
 * Usage:
 *   (no args)                       run the scenarios
 *   --match FILE                    match one case and print
 *                                   "MATCH band bestId bestSlot score runnerUpId runnerUpScore nearTie"
 *                                   ("-" for a missing id). FILE's lines:
 *                                   "confident close margin ready", "probe v0 v1 ...",
 *                                   then one "id slot v0 v1 ..." (or "id slot null") per gallery entry.
 *   --input IMAGE x0 y0 .. x4 y4 OUT  align IMAGE on the five landmarks with FaceAlign and write
 *                                   FaceMatcher.input() of the crop to OUT (float32 LE, 1x3x112x112)
 */
public final class FaceMatcherHarness {
    private static final FaceMatcher.Thresholds T = FaceMatcher.Thresholds.DEFAULTS;
    private static final int DIM = 128;

    public static void main(String[] args) throws IOException {
        if (args.length == 2 && args[0].equals("--match")) {
            printMatch(args[1]);
            return;
        }
        if (args.length == 13 && args[0].equals("--input")) {
            writeInput(args);
            return;
        }
        check("probe_equal_to_stored_scores_one_and_is_confident", probeEqual());
        check("score_at_close_threshold_is_close_just_below_is_weak", closeEdge());
        check("score_at_confident_threshold_is_confident_just_below_is_close", confidentEdge());
        check("person_scored_by_best_photo_not_average", bestPhotoNotAverage());
        check("near_tie_demotes_confident_to_close_naming_the_higher", nearTie());
        check("two_photos_of_one_person_at_the_top_do_not_trigger_margin", samePersonTop());
        check("gap_of_at_least_the_margin_stays_confident", gapAtMargin());
        check("near_tie_only_touches_a_confident_result", nearTieOnlyConfident());
        check("runner_up_is_the_best_other_person", runnerUp());
        check("empty_gallery_is_weak_with_no_best_id", emptyGallery());
        check("not_ready_whatever_the_scores", notReady());
        check("unusable_entries_are_skipped", unusable());
        check("gallery_order_does_not_change_the_answer", orderIndependent());
        check("thresholds_are_the_ones_given", thresholdsGiven());
        check("defaults_and_model_id", defaults());
        check("normalize_gives_unit_length_and_refuses_garbage", normalize());
        check("input_is_rgb_planes_of_raw_0_255", inputLayout());
        check("inputs_are_not_modified", notModified());
    }

    // ---- scenarios ----

    private static String probeEqual() {
        float[] e = unit(DIM, 1);
        List<FaceMatcher.Entry> g = new ArrayList<>();
        g.add(new FaceMatcher.Entry("aaaa", 0, unit(DIM, 2)));
        g.add(new FaceMatcher.Entry("bbbb", 3, e.clone()));
        FaceMatcher.Result r = FaceMatcher.match(e, g, T, true);
        if (r.band != FaceMatcher.Band.CONFIDENT || !"bbbb".equals(r.bestId) || r.bestSlot != 3) {
            return "result " + r;
        }
        return Math.abs(r.score - 1.0f) < 1e-5 ? null : "score " + r.score;
    }

    private static String closeEdge() {
        float close = T.close;
        FaceMatcher.Result at = one(close, T);
        FaceMatcher.Result below = one(Math.nextDown(close), T);
        if (at.score != close) {
            return "fixture score " + at.score + " is not " + close;
        }
        if (at.band != FaceMatcher.Band.CLOSE) {
            return "at close: " + at;
        }
        return below.band == FaceMatcher.Band.WEAK ? null : "just below close: " + below;
    }

    private static String confidentEdge() {
        float conf = T.confident;
        FaceMatcher.Result at = one(conf, T);
        FaceMatcher.Result below = one(Math.nextDown(conf), T);
        if (at.band != FaceMatcher.Band.CONFIDENT) {
            return "at confident: " + at;
        }
        return below.band == FaceMatcher.Band.CLOSE ? null : "just below confident: " + below;
    }

    private static String bestPhotoNotAverage() {
        float[] probe = basis(DIM, 0);
        List<FaceMatcher.Entry> g = new ArrayList<>();
        // "many": one photo at 0.70 and four at 0.10 -> best 0.70, mean 0.22.
        g.add(new FaceMatcher.Entry("many", 0, withScore(0.10f, 1)));
        g.add(new FaceMatcher.Entry("many", 1, withScore(0.10f, 2)));
        g.add(new FaceMatcher.Entry("many", 2, withScore(0.70f, 3)));
        g.add(new FaceMatcher.Entry("many", 3, withScore(0.10f, 4)));
        g.add(new FaceMatcher.Entry("many", 4, withScore(0.10f, 5)));
        // "steady": one photo at 0.40, above the other person's mean, below their best.
        g.add(new FaceMatcher.Entry("steady", 0, withScore(0.40f, 6)));
        FaceMatcher.Result r = FaceMatcher.match(probe, g, T, true);
        if (!"many".equals(r.bestId) || r.bestSlot != 2) {
            return "best " + r;
        }
        if (Math.abs(r.score - 0.70f) > 1e-6 || r.band != FaceMatcher.Band.CONFIDENT) {
            return "score/band " + r;
        }
        return "steady".equals(r.runnerUpId) && Math.abs(r.runnerUpScore - 0.40f) < 1e-6 ? null : "runner-up " + r;
    }

    private static String nearTie() {
        float[] probe = basis(DIM, 0);
        List<FaceMatcher.Entry> g = new ArrayList<>();
        g.add(new FaceMatcher.Entry("low", 0, withScore(0.60f, 1)));
        g.add(new FaceMatcher.Entry("high", 1, withScore(0.62f, 2)));
        FaceMatcher.Result r = FaceMatcher.match(probe, g, new FaceMatcher.Thresholds(0.50f, 0.363f, 0.05f), true);
        if (r.band != FaceMatcher.Band.CLOSE || !r.nearTie) {
            return "not demoted: " + r;
        }
        if (!"high".equals(r.bestId) || r.bestSlot != 1 || Math.abs(r.score - 0.62f) > 1e-6) {
            return "does not name the higher: " + r;
        }
        return "low".equals(r.runnerUpId) && Math.abs(r.runnerUpScore - 0.60f) < 1e-6 ? null : "runner-up " + r;
    }

    private static String samePersonTop() {
        float[] probe = basis(DIM, 0);
        List<FaceMatcher.Entry> g = new ArrayList<>();
        g.add(new FaceMatcher.Entry("ben", 0, withScore(0.62f, 1)));
        g.add(new FaceMatcher.Entry("ben", 1, withScore(0.61f, 2)));
        g.add(new FaceMatcher.Entry("amy", 0, withScore(0.20f, 3)));
        FaceMatcher.Result r = FaceMatcher.match(probe, g, T, true);
        if (r.band != FaceMatcher.Band.CONFIDENT || r.nearTie || !"ben".equals(r.bestId) || r.bestSlot != 0) {
            return "result " + r;
        }
        FaceMatcher.Result alone = FaceMatcher.match(probe, g.subList(0, 2), T, true);
        if (alone.band != FaceMatcher.Band.CONFIDENT || alone.nearTie || alone.runnerUpId != null
                || !Float.isNaN(alone.runnerUpScore)) {
            return "one person only: " + alone;
        }
        return "amy".equals(r.runnerUpId) ? null : "runner-up " + r;
    }

    private static String gapAtMargin() {
        // 0.75 and 0.5 are exact in float, so the gap is exactly the 0.25 margin.
        float[] probe = basis(DIM, 0);
        FaceMatcher.Thresholds t = new FaceMatcher.Thresholds(0.5f, 0.375f, 0.25f);
        List<FaceMatcher.Entry> g = new ArrayList<>();
        g.add(new FaceMatcher.Entry("a", 0, withScore(0.75f, 1)));
        g.add(new FaceMatcher.Entry("b", 0, withScore(0.5f, 2)));
        FaceMatcher.Result r = FaceMatcher.match(probe, g, t, true);
        if (r.band != FaceMatcher.Band.CONFIDENT || r.nearTie) {
            return "gap == margin demoted: " + r;
        }
        g.set(1, new FaceMatcher.Entry("b", 0, withScore(Math.nextUp(0.5f), 2)));
        r = FaceMatcher.match(probe, g, t, true);
        if (r.band != FaceMatcher.Band.CLOSE || !r.nearTie) {
            return "gap just under margin not demoted: " + r;
        }
        FaceMatcher.Thresholds noMargin = new FaceMatcher.Thresholds(0.5f, 0.375f, 0f);
        g.set(1, new FaceMatcher.Entry("b", 0, withScore(0.75f, 2)));
        r = FaceMatcher.match(probe, g, noMargin, true);
        return r.band == FaceMatcher.Band.CONFIDENT && !r.nearTie ? null : "margin 0 demoted an exact tie: " + r;
    }

    private static String nearTieOnlyConfident() {
        float[] probe = basis(DIM, 0);
        List<FaceMatcher.Entry> g = new ArrayList<>();
        g.add(new FaceMatcher.Entry("a", 0, withScore(0.45f, 1)));
        g.add(new FaceMatcher.Entry("b", 0, withScore(0.44f, 2)));
        FaceMatcher.Result r = FaceMatcher.match(probe, g, T, true);
        if (r.band != FaceMatcher.Band.CLOSE || r.nearTie) {
            return "close pair: " + r;
        }
        g.set(0, new FaceMatcher.Entry("a", 0, withScore(0.20f, 1)));
        g.set(1, new FaceMatcher.Entry("b", 0, withScore(0.19f, 2)));
        r = FaceMatcher.match(probe, g, T, true);
        return r.band == FaceMatcher.Band.WEAK && !r.nearTie ? null : "weak pair: " + r;
    }

    private static String runnerUp() {
        float[] probe = basis(DIM, 0);
        List<FaceMatcher.Entry> g = new ArrayList<>();
        g.add(new FaceMatcher.Entry("a", 0, withScore(0.90f, 1)));
        g.add(new FaceMatcher.Entry("a", 1, withScore(0.80f, 2)));
        g.add(new FaceMatcher.Entry("b", 0, withScore(0.30f, 3)));
        g.add(new FaceMatcher.Entry("c", 0, withScore(0.10f, 4)));
        g.add(new FaceMatcher.Entry("c", 2, withScore(0.55f, 5)));
        FaceMatcher.Result r = FaceMatcher.match(probe, g, T, true);
        if (!"a".equals(r.bestId) || r.bestSlot != 0 || r.band != FaceMatcher.Band.CONFIDENT || r.nearTie) {
            return "best " + r;
        }
        return "c".equals(r.runnerUpId) && Math.abs(r.runnerUpScore - 0.55f) < 1e-6 ? null : "runner-up " + r;
    }

    private static String emptyGallery() {
        FaceMatcher.Result r = FaceMatcher.match(unit(DIM, 1), Collections.<FaceMatcher.Entry>emptyList(), T, true);
        if (r.band != FaceMatcher.Band.WEAK || r.bestId != null || r.bestSlot != -1 || !Float.isNaN(r.score)
                || r.runnerUpId != null || r.nearTie || r.hasBest()) {
            return "empty: " + r;
        }
        FaceMatcher.Result nul = FaceMatcher.match(unit(DIM, 1), null, T, true);
        return nul.band == FaceMatcher.Band.WEAK && nul.bestId == null ? null : "null gallery: " + nul;
    }

    private static String notReady() {
        float[] e = unit(DIM, 1);
        List<FaceMatcher.Entry> g = new ArrayList<>();
        g.add(new FaceMatcher.Entry("a", 0, e.clone()));
        FaceMatcher.Result r = FaceMatcher.match(e, g, T, false);
        if (r.band != FaceMatcher.Band.NOT_READY || r.nearTie) {
            return "perfect match while not ready: " + r;
        }
        FaceMatcher.Result empty = FaceMatcher.match(e, Collections.<FaceMatcher.Entry>emptyList(), T, false);
        if (empty.band != FaceMatcher.Band.NOT_READY) {
            return "empty gallery while not ready: " + empty;
        }
        g.add(new FaceMatcher.Entry("b", 0, e.clone()));
        FaceMatcher.Result tie = FaceMatcher.match(e, g, T, false);
        return tie.band == FaceMatcher.Band.NOT_READY && !tie.nearTie ? null : "tie while not ready: " + tie;
    }

    private static String unusable() {
        float[] probe = basis(DIM, 0);
        float[] nan = withScore(0.9f, 2);
        nan[5] = Float.NaN;
        List<FaceMatcher.Entry> g = new ArrayList<>();
        g.add(new FaceMatcher.Entry("waiting", 0, null));
        g.add(new FaceMatcher.Entry("short", 0, new float[]{1f, 0f}));
        g.add(new FaceMatcher.Entry("broken", 0, nan));
        g.add(new FaceMatcher.Entry(null, 0, withScore(0.95f, 3)));
        g.add(null);
        g.add(new FaceMatcher.Entry("ok", 1, withScore(0.4f, 4)));
        FaceMatcher.Result r = FaceMatcher.match(probe, g, T, true);
        if (!"ok".equals(r.bestId) || r.bestSlot != 1 || r.band != FaceMatcher.Band.CLOSE || r.runnerUpId != null) {
            return "result " + r;
        }
        FaceMatcher.Result bad = FaceMatcher.match(null, g, T, true);
        if (bad.band != FaceMatcher.Band.WEAK || bad.bestId != null) {
            return "null probe: " + bad;
        }
        return null;
    }

    private static String orderIndependent() {
        Random rnd = new Random(11);
        float[] probe = unit(16, 99);
        List<FaceMatcher.Entry> g = new ArrayList<>();
        String[] ids = {"p1", "p2", "p3", "p4"};
        for (int i = 0; i < 14; i++) {
            g.add(new FaceMatcher.Entry(ids[rnd.nextInt(ids.length)], i % 5, unit(16, 200 + i)));
        }
        // An exact tie between two people: broken by the smaller id, whatever the order.
        float[] twin = unit(16, 500);
        g.add(new FaceMatcher.Entry("zz", 0, twin.clone()));
        g.add(new FaceMatcher.Entry("yy", 0, twin.clone()));
        FaceMatcher.Result first = FaceMatcher.match(twin, g, T, true);
        if (!"yy".equals(first.bestId) || !"zz".equals(first.runnerUpId) || !first.nearTie) {
            return "tie break " + first;
        }
        for (int k = 0; k < 20; k++) {
            List<FaceMatcher.Entry> s = new ArrayList<>(g);
            Collections.shuffle(s, new Random(k));
            FaceMatcher.Result r = FaceMatcher.match(twin, s, T, true);
            if (!r.toString().equals(first.toString())) {
                return "shuffle " + k + ": " + r + " vs " + first;
            }
            r = FaceMatcher.match(probe, s, T, true);
            FaceMatcher.Result base = FaceMatcher.match(probe, g, T, true);
            if (!r.toString().equals(base.toString())) {
                return "shuffle " + k + ": " + r + " vs " + base;
            }
        }
        return null;
    }

    private static String thresholdsGiven() {
        FaceMatcher.Result strict = one(0.62f, new FaceMatcher.Thresholds(0.7f, 0.65f, 0.05f));
        if (strict.band != FaceMatcher.Band.WEAK) {
            return "raised thresholds ignored: " + strict;
        }
        FaceMatcher.Result lax = one(0.2f, new FaceMatcher.Thresholds(0.2f, 0.1f, 0.05f));
        if (lax.band != FaceMatcher.Band.CONFIDENT) {
            return "lowered thresholds ignored: " + lax;
        }
        FaceMatcher.Result dflt = one(0.45f, null);
        return dflt.band == FaceMatcher.Band.CLOSE ? null : "null thresholds are not the defaults: " + dflt;
    }

    private static String defaults() {
        if (T.confident != 0.50f || T.close != 0.363f || T.margin != 0.05f) {
            return "defaults " + T;
        }
        if (!"sface-2021dec".equals(FaceMatcher.MODEL_ID)) {
            return "model id " + FaceMatcher.MODEL_ID;
        }
        return FaceMatcher.DIM == 128 && FaceMatcher.INPUT_SIDE == 112 ? null
                : "dims " + FaceMatcher.DIM + " / " + FaceMatcher.INPUT_SIDE;
    }

    private static String normalize() {
        float[] v = {3f, 4f, 0f};
        float[] n = FaceMatcher.normalize(v);
        if (n == null || Math.abs(n[0] - 0.6f) > 1e-6 || Math.abs(n[1] - 0.8f) > 1e-6 || n[2] != 0f) {
            return "3,4,0 -> " + Arrays.toString(n);
        }
        if (v[0] != 3f) {
            return "input modified";
        }
        float[] big = unit(DIM, 3);
        for (int i = 0; i < big.length; i++) {
            big[i] *= 37.5f;
        }
        double len = 0;
        for (float x : FaceMatcher.normalize(big)) {
            len += x * x;
        }
        if (Math.abs(len - 1) > 1e-5) {
            return "length^2 " + len;
        }
        float[] inf = {1f, Float.POSITIVE_INFINITY};
        float[] nanv = {Float.NaN, 1f};
        if (FaceMatcher.normalize(new float[3]) != null || FaceMatcher.normalize(null) != null
                || FaceMatcher.normalize(new float[0]) != null || FaceMatcher.normalize(inf) != null
                || FaceMatcher.normalize(nanv) != null) {
            return "garbage normalised";
        }
        return null;
    }

    private static String inputLayout() {
        int side = FaceMatcher.INPUT_SIDE;
        int plane = side * side;
        int[] argb = new int[plane];
        for (int i = 0; i < plane; i++) {
            argb[i] = 0xff000000 | ((i % 251) << 16) | (((i * 7) % 256) << 8) | ((i * 13) % 256);
        }
        argb[5 * side + 9] = 0x80fe0102;
        float[] out = new float[3 * plane];
        FaceMatcher.input(argb, out);
        for (int i = 0; i < plane; i++) {
            int p = argb[i];
            if (out[i] != ((p >> 16) & 0xff) || out[plane + i] != ((p >> 8) & 0xff) || out[2 * plane + i] != (p & 0xff)) {
                return "pixel " + i + ": " + out[i] + "," + out[plane + i] + "," + out[2 * plane + i];
            }
        }
        int k = 5 * side + 9;
        if (out[k] != 254f || out[plane + k] != 1f || out[2 * plane + k] != 2f) {
            return "alpha leaked into the planes";
        }
        try {
            FaceMatcher.input(new int[10], out);
            return "wrong-size crop accepted";
        } catch (IllegalArgumentException expected) {
            // fine
        }
        try {
            FaceMatcher.input(argb, new float[10]);
            return "short output accepted";
        } catch (IllegalArgumentException expected) {
            return null;
        }
    }

    private static String notModified() {
        float[] probe = unit(DIM, 1);
        float[] p0 = probe.clone();
        float[] e = unit(DIM, 2);
        float[] e0 = e.clone();
        List<FaceMatcher.Entry> g = new ArrayList<>();
        g.add(new FaceMatcher.Entry("a", 0, e));
        FaceMatcher.match(probe, g, T, true);
        return Arrays.equals(probe, p0) && Arrays.equals(e, e0) && g.size() == 1 ? null : "inputs changed";
    }

    // ---- fixtures ----

    /** A one-person, one-photo match against the first basis vector at exactly score s. */
    private static FaceMatcher.Result one(float s, FaceMatcher.Thresholds t) {
        List<FaceMatcher.Entry> g = new ArrayList<>();
        g.add(new FaceMatcher.Entry("only", 0, withScore(s, 1)));
        return FaceMatcher.match(basis(DIM, 0), g, t, true);
    }

    /** A unit vector whose dot product with basis(0) is exactly s: {s, sqrt(1 - s^2) on axis k, 0...}. */
    private static float[] withScore(float s, int k) {
        float[] v = new float[DIM];
        v[0] = s;
        v[k] = (float) Math.sqrt(1 - (double) s * s);
        return v;
    }

    private static float[] basis(int n, int k) {
        float[] v = new float[n];
        v[k] = 1f;
        return v;
    }

    private static float[] unit(int n, long seed) {
        Random r = new Random(seed);
        float[] v = new float[n];
        for (int i = 0; i < n; i++) {
            v[i] = (float) r.nextGaussian();
        }
        return FaceMatcher.normalize(v);
    }

    // ---- CLI modes ----

    private static void printMatch(String path) throws IOException {
        List<String> lines = Files.readAllLines(Paths.get(path), StandardCharsets.UTF_8);
        String[] head = lines.get(0).trim().split(" ");
        FaceMatcher.Thresholds t = new FaceMatcher.Thresholds(
                Float.parseFloat(head[0]), Float.parseFloat(head[1]), Float.parseFloat(head[2]));
        boolean ready = Boolean.parseBoolean(head[3]);
        float[] probe = floats(lines.get(1).trim().split(" "), 1);
        List<FaceMatcher.Entry> g = new ArrayList<>();
        for (int i = 2; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] f = line.split(" ");
            g.add(new FaceMatcher.Entry(f[0], Integer.parseInt(f[1]), f[2].equals("null") ? null : floats(f, 2)));
        }
        FaceMatcher.Result r = FaceMatcher.match(probe, g, t, ready);
        System.out.println("MATCH " + r.band + " " + (r.bestId == null ? "-" : r.bestId) + " " + r.bestSlot + " "
                + r.score + " " + (r.runnerUpId == null ? "-" : r.runnerUpId) + " " + r.runnerUpScore + " " + r.nearTie);
    }

    private static float[] floats(String[] f, int from) {
        float[] v = new float[f.length - from];
        for (int i = from; i < f.length; i++) {
            v[i - from] = Float.parseFloat(f[i]);
        }
        return v;
    }

    private static void writeInput(String[] args) throws IOException {
        BufferedImage img = ImageIO.read(new File(args[1]));
        int w = img.getWidth();
        int h = img.getHeight();
        int[] argb = img.getRGB(0, 0, w, h, null, 0, w);
        float[] lm = new float[10];
        for (int i = 0; i < 10; i++) {
            lm[i] = Float.parseFloat(args[2 + i]);
        }
        int[] crop = FaceAlign.align(argb, w, h, lm);
        if (crop == null) {
            throw new IllegalStateException("no fit for the landmarks");
        }
        float[] chw = new float[3 * FaceMatcher.INPUT_SIDE * FaceMatcher.INPUT_SIDE];
        FaceMatcher.input(crop, chw);
        ByteBuffer b = ByteBuffer.allocate(chw.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : chw) {
            b.putFloat(v);
        }
        Files.write(Paths.get(args[12]), b.array());
    }

    private static void check(String name, String failure) {
        System.out.println(failure == null ? "PASS " + name : "FAIL " + name + ": " + failure);
    }
}
