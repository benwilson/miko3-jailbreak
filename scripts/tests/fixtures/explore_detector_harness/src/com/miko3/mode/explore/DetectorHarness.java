package com.miko3.mode.explore;

import java.util.List;

/**
 * Host harness for YoloeDecoder and Detection (camera curiosity, KTD2). Builds
 * small synthetic output tensors in the exported model's layout -- rows of box
 * centre x, centre y, width, height, one score row per name, then mask rows --
 * and prints one PASS/FAIL line per scenario.
 */
public final class DetectorHarness {
    private static final String[] NAMES = {"person", "cat", "plant"};
    private static final int W = 640;
    private static final int H = 480;
    private static final int MASK_ROWS = 32;

    public static void main(String[] args) {
        check("decodes_best_name_and_frame_fractions", decodesBestName());
        check("below_threshold_dropped", belowThresholdDropped());
        check("same_name_overlaps_merged", sameNameOverlapsMerged());
        check("different_names_overlapping_both_kept", differentNamesKept());
        check("far_apart_same_name_both_kept", farApartKept());
        check("highest_score_first_and_capped", sortedAndCapped());
        check("short_output_rejected", shortOutputRejected());
        check("detection_clamps_to_frame", detectionClamps());
        check("center_x_spans_minus_one_to_one", centerX());
        if (args.length > 0) {
            // NAMECLIP lines for the Python side to compare against its own slug().
            for (String name : args) {
                System.out.println("NAMECLIP " + name + "\t" + Detection.nameClip(name));
            }
        }
    }

    /** One anchor per row of boxes: {cx, cy, w, h, score per name...}. */
    private static float[] tensor(float[][] anchors) {
        int n = anchors.length;
        int rows = 4 + NAMES.length + MASK_ROWS;
        float[] out = new float[rows * n];
        for (int a = 0; a < n; a++) {
            for (int r = 0; r < anchors[a].length; r++) {
                out[r * n + a] = anchors[a][r];
            }
            for (int r = 4 + NAMES.length; r < rows; r++) {
                out[r * n + a] = 99f; // mask coefficients must never read as scores
            }
        }
        return out;
    }

    private static List<Detection> decode(float[][] anchors, float minScore, int max) {
        return new YoloeDecoder(NAMES, W, H).decode(tensor(anchors), anchors.length, minScore, 0.5f, max);
    }

    private static String decodesBestName() {
        List<Detection> d = decode(new float[][]{{320, 240, 64, 48, 0.1f, 0.2f, 0.9f}}, 0.25f, 20);
        if (d.size() != 1) return "want 1 detection, got " + d;
        Detection p = d.get(0);
        if (!p.label.equals("plant") || Math.abs(p.score - 0.9f) > 1e-6) return "want plant 0.9, got " + p;
        if (!near(p.x0, 0.45f) || !near(p.x1, 0.55f) || !near(p.y0, 0.45f) || !near(p.y1, 0.55f)) {
            return "box fractions wrong: " + p;
        }
        return null;
    }

    private static String belowThresholdDropped() {
        List<Detection> d = decode(new float[][]{{320, 240, 64, 48, 0.2f, 0.24f, 0.1f}}, 0.25f, 20);
        return d.isEmpty() ? null : "want none, got " + d;
    }

    private static String sameNameOverlapsMerged() {
        List<Detection> d = decode(new float[][]{
                {320, 240, 100, 100, 0f, 0.6f, 0f},
                {325, 245, 100, 100, 0f, 0.8f, 0f}}, 0.25f, 20);
        if (d.size() != 1) return "want 1 merged cat, got " + d;
        return near(d.get(0).score, 0.8f) ? null : "kept the weaker box: " + d;
    }

    private static String differentNamesKept() {
        List<Detection> d = decode(new float[][]{
                {320, 240, 100, 100, 0.7f, 0f, 0f},
                {320, 240, 100, 100, 0f, 0.6f, 0f}}, 0.25f, 20);
        return d.size() == 2 ? null : "want person and cat, got " + d;
    }

    private static String farApartKept() {
        List<Detection> d = decode(new float[][]{
                {100, 100, 50, 50, 0f, 0f, 0.5f},
                {500, 400, 50, 50, 0f, 0f, 0.4f}}, 0.25f, 20);
        return d.size() == 2 ? null : "want two plants, got " + d;
    }

    private static String sortedAndCapped() {
        List<Detection> d = decode(new float[][]{
                {50, 50, 20, 20, 0.3f, 0f, 0f},
                {200, 50, 20, 20, 0.9f, 0f, 0f},
                {350, 50, 20, 20, 0.5f, 0f, 0f}}, 0.25f, 2);
        if (d.size() != 2) return "want 2 (capped), got " + d;
        return near(d.get(0).score, 0.9f) && near(d.get(1).score, 0.5f) ? null : "wrong order: " + d;
    }

    private static String shortOutputRejected() {
        try {
            new YoloeDecoder(NAMES, W, H).decode(new float[10], 5, 0.25f, 0.5f, 20);
            return "want IllegalArgumentException";
        } catch (IllegalArgumentException expected) {
            return null;
        }
    }

    private static String detectionClamps() {
        Detection d = new Detection("cat", 0.5f, 1.2f, -0.1f, 0.8f, Float.NaN);
        if (!near(d.x0, 0.8f) || !near(d.x1, 1f) || !near(d.y0, 0f) || !near(d.y1, 0f)) return "not clamped: " + d;
        return d.area() >= 0 ? null : "negative area";
    }

    private static String centerX() {
        Detection left = new Detection("cat", 0.5f, 0f, 0f, 0f, 1f);
        Detection mid = new Detection("cat", 0.5f, 0.25f, 0f, 0.75f, 1f);
        Detection right = new Detection("cat", 0.5f, 1f, 0f, 1f, 1f);
        if (!near(left.centerX(), -1f) || !near(mid.centerX(), 0f) || !near(right.centerX(), 1f)) {
            return "centerX " + left.centerX() + " " + mid.centerX() + " " + right.centerX();
        }
        return null;
    }

    private static boolean near(float a, float b) {
        return Math.abs(a - b) < 1e-4f;
    }

    private static void check(String name, String failure) {
        System.out.println(failure == null ? "PASS " + name : "FAIL " + name + ": " + failure);
    }
}
