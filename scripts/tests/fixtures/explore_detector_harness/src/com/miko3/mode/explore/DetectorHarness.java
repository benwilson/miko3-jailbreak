package com.miko3.mode.explore;

import java.util.Arrays;
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
        check("topk_decodes_fractions_and_names", topkDecodes());
        check("topk_matches_raw_decode", topkMatchesRaw());
        check("topk_floor_bad_index_and_short_rejected", topkFloorAndBadIndex());
        check("stages_percentile_nearest_rank", stagesPercentile());
        check("stages_line_and_summary", stagesLine());
        check("config_defaults_and_parsing", configParsing());
        check("config_fallback_attempts", configAttempts());
        check("config_bench_switches", configBench());
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

    /** The top-k layout: rows cx, cy, w, h (fractions), score, name index; one column per candidate. */
    private static float[] topk(float[][] candidates) {
        int k = candidates.length;
        float[] out = new float[6 * k];
        for (int i = 0; i < k; i++) {
            for (int r = 0; r < 6; r++) {
                out[r * k + i] = candidates[i][r];
            }
        }
        return out;
    }

    private static String topkDecodes() {
        // Best first, as the graph's TopK sorts them; the second overlaps the first (same name).
        float[] det = topk(new float[][]{{0.5f, 0.5f, 0.1f, 0.1f, 0.9f, 2}, {0.5f, 0.5f, 0.1f, 0.1f, 0.8f, 2},
                {0.2f, 0.2f, 0.1f, 0.2f, 0.7f, 0}});
        List<Detection> d = new YoloeDecoder(NAMES, W, H).decodeTopK(det, 3, 0.25f, 0.5f, 20);
        if (d.size() != 2) return "want 2 detections, got " + d;
        Detection p = d.get(0);
        if (!p.label.equals("plant") || !near(p.score, 0.9f)) return "want plant 0.9 first, got " + d;
        if (!near(p.x0, 0.45f) || !near(p.x1, 0.55f) || !near(p.y0, 0.45f) || !near(p.y1, 0.55f)) {
            return "box not read as frame fractions: " + p;
        }
        Detection q = d.get(1);
        if (!q.label.equals("person") || !near(q.y0, 0.1f) || !near(q.y1, 0.3f)) return "second wrong: " + q;
        return null;
    }

    private static String topkMatchesRaw() {
        // The same anchors through both layouts decode to the same detections.
        float[][] anchors = {{320, 240, 64, 48, 0.1f, 0.2f, 0.9f}, {330, 245, 64, 48, 0.1f, 0.2f, 0.6f},
                {100, 100, 50, 80, 0.7f, 0.1f, 0.1f}, {500, 400, 40, 40, 0.3f, 0.35f, 0.1f}};
        List<Detection> raw = decode(anchors, 0.25f, 20);
        float[][] cands = new float[anchors.length][];
        for (int a = 0; a < anchors.length; a++) {
            int best = 0;
            for (int c = 1; c < NAMES.length; c++) {
                if (anchors[a][4 + c] >= anchors[a][4 + best]) best = c;
            }
            cands[a] = new float[]{anchors[a][0] / W, anchors[a][1] / H, anchors[a][2] / W, anchors[a][3] / H,
                    anchors[a][4 + best], best};
        }
        Arrays.sort(cands, (x, y) -> Float.compare(y[4], x[4]));
        List<Detection> top = new YoloeDecoder(NAMES, W, H).decodeTopK(topk(cands), cands.length, 0.25f, 0.5f, 20);
        if (raw.size() != top.size() || raw.size() != 3) return "raw " + raw + " vs topk " + top;
        for (int i = 0; i < raw.size(); i++) {
            Detection r = raw.get(i);
            Detection t = top.get(i);
            // Same names and scores; boxes equal up to float rounding (pixels/size vs fractions).
            if (!r.label.equals(t.label) || r.score != t.score || !near(r.x0, t.x0) || !near(r.y0, t.y0)
                    || !near(r.x1, t.x1) || !near(r.y1, t.y1)) {
                return "raw " + raw + " vs topk " + top;
            }
        }
        return null;
    }

    private static String topkFloorAndBadIndex() {
        float[] det = topk(new float[][]{{0.5f, 0.5f, 0.1f, 0.1f, 0.9f, 7}, {0.5f, 0.5f, 0.1f, 0.1f, 0.1f, 1},
                {0.2f, 0.2f, 0.1f, 0.1f, 0.5f, -1}});
        List<Detection> d = new YoloeDecoder(NAMES, W, H).decodeTopK(det, 3, 0.25f, 0.5f, 20);
        if (!d.isEmpty()) return "out-of-range names and sub-floor scores must drop, got " + d;
        try {
            new YoloeDecoder(NAMES, W, H).decodeTopK(new float[5], 1, 0.25f, 0.5f, 20);
            return "a short top-k output must be rejected";
        } catch (IllegalArgumentException expected) {
            return null;
        }
    }

    private static String stagesPercentile() {
        long[] v = {50, 10, 40, 20, 30};
        if (DetectorStages.percentile(v, 50) != 30) return "p50 of 10..50 should be 30";
        if (DetectorStages.percentile(v, 95) != 50) return "p95 of 5 values should be the max";
        if (DetectorStages.percentile(new long[]{7}, 95) != 7) return "one value";
        if (DetectorStages.percentile(new long[0], 50) != 0) return "no values";
        if (v[0] != 50) return "percentile must not reorder its input";
        long[] twenty = new long[20];
        for (int i = 0; i < 20; i++) twenty[i] = i + 1;
        if (DetectorStages.percentile(twenty, 95) != 19) return "p95 of 1..20 should be 19 (nearest rank)";
        return null;
    }

    private static String stagesLine() {
        DetectorStages s = new DetectorStages();
        s.set(DetectorStages.DECODE, 31_200_000L);
        s.set(DetectorStages.PREP, 12_000_000L);
        s.set(DetectorStages.RUN, 905_100_000L);
        s.set(DetectorStages.COPY, 21_300_000L);
        s.set(DetectorStages.DETECT, 16_000_000L);
        String want = "decode 31.2, prep 12.0, run 905.1, copy 21.3, detect 16.0 ms";
        if (!want.equals(s.line())) return "line: " + s.line();
        DetectorStages c = s.copy();
        s.set(DetectorStages.RUN, 0);
        if (c.nanos(DetectorStages.RUN) != 905_100_000L) return "copy must be a snapshot";
        DetectorStages.Summary sum = new DetectorStages.Summary();
        sum.add(c);
        DetectorStages slow = c.copy();
        slow.set(DetectorStages.RUN, 1_905_100_000L);
        sum.add(slow);
        String line = sum.line();
        if (!line.startsWith("total=985.6/1985.6 decode=31.2/31.2 prep=12.0/12.0 run=905.1/1905.1 ")) {
            return "summary: " + line;
        }
        if (!line.endsWith(" copy=21.3/21.3 detect=16.0/16.0")) return "summary tail: " + line;
        return null;
    }

    private static String configParsing() {
        DetectorConfig d = DetectorConfig.parse("", "");
        if (!d.isDefault() || !"ep=cpu model=detector.onnx".equals(d.toString())) return "blank: " + d;
        if (!DetectorConfig.fromSystem().isDefault()) return "no properties off the robot must mean the default";
        if (!"xnnpack".equals(DetectorConfig.parse(" XNNPACK ", "").ep)) return "xnnpack, any case";
        if (!"nnapi".equals(DetectorConfig.parse("nnapi", "").ep)) return "nnapi";
        if (!"cpu".equals(DetectorConfig.parse("gpu", "").ep)) return "unknown provider -> cpu";
        if (!"detector-rgba.onnx".equals(DetectorConfig.parse("", "detector-rgba.onnx").model)) return "variant";
        for (String bad : new String[]{"../detector.onnx", "/sdcard/detector-x.onnx", "face_sface.onnx",
                "detector-RGBA.onnx", "detector.onnx.bak", "detector-.onnx"}) {
            if (DetectorConfig.isModelName(bad)) return "must refuse " + bad;
            if (!DetectorConfig.parse("", bad).model.equals("detector.onnx")) return "must fall back for " + bad;
        }
        return null;
    }

    private static String configAttempts() {
        if (!DetectorConfig.defaults().attempts().toString().equals("[ep=cpu model=detector.onnx]")) {
            return "default must be a single attempt";
        }
        String a = new DetectorConfig("xnnpack", "detector-rgba.onnx").attempts().toString();
        if (!a.equals("[ep=xnnpack model=detector-rgba.onnx, ep=cpu model=detector-rgba.onnx, "
                + "ep=cpu model=detector.onnx]")) return "attempts: " + a;
        String b = new DetectorConfig("xnnpack", "detector.onnx").attempts().toString();
        if (!b.equals("[ep=xnnpack model=detector.onnx, ep=cpu model=detector.onnx]")) return "attempts: " + b;
        return null;
    }

    private static String configBench() {
        for (String on : new String[]{"1", "true", " TRUE "}) {
            if (!DetectorConfig.benchRequested(on)) return "bench should be on for '" + on + "'";
        }
        for (String off : new String[]{"", "0", "false", "yes", null}) {
            if (DetectorConfig.benchRequested(off)) return "bench should be off for '" + off + "'";
        }
        if (DetectorConfig.benchRounds("") != 5 || DetectorConfig.benchRounds("x") != 5
                || DetectorConfig.benchRounds("0") != 5) return "rounds default";
        if (DetectorConfig.benchRounds("3") != 3 || DetectorConfig.benchRounds("500") != 50) return "rounds";
        List<String> models = Arrays.asList("detector.onnx", "detector-rgba.onnx");
        String all = DetectorConfig.benchConfigs("", models).toString();
        if (!all.equals("[ep=cpu model=detector.onnx, ep=cpu model=detector-rgba.onnx, "
                + "ep=xnnpack model=detector.onnx, ep=xnnpack model=detector-rgba.onnx]")) return "default: " + all;
        String some = DetectorConfig.benchConfigs(
                " xnnpack/detector-rgba.onnx, gpu/detector.onnx,cpu/detector-missing.onnx,junk, nnapi/detector.onnx",
                models).toString();
        if (!some.equals("[ep=xnnpack model=detector-rgba.onnx, ep=nnapi model=detector.onnx]")) return "spec: " + some;
        return null;
    }

    private static boolean near(float a, float b) {
        return Math.abs(a - b) < 1e-4f;
    }

    private static void check(String name, String failure) {
        System.out.println(failure == null ? "PASS " + name : "FAIL " + name + ": " + failure);
    }
}
