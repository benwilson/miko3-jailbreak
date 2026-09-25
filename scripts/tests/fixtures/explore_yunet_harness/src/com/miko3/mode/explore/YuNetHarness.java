package com.miko3.mode.explore;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/**
 * Host harness for YuNetDecoder (explore on Claude KTD5). Builds synthetic
 * outputs in the 2023mar layout -- per stride 8/16/32 a cls and obj score and
 * four bbox values (grid offsets, log width and height) per grid cell, row-major
 * -- and prints one PASS/FAIL line per scenario.
 *
 * With "--decode dir inW inH" it instead reads real model outputs from
 * dir/{cls,obj,bbox}_{8,16,32}.bin (float32 little-endian) and prints one
 * "FACE x0 y0 x1 y1 score" line per face at the production threshold.
 */
public final class YuNetHarness {
    private static final int IN = 640;
    private static final float MIN = 0.6f;

    public static void main(String[] args) throws IOException {
        if (args.length == 4 && args[0].equals("--decode")) {
            decodeFiles(args[1], Integer.parseInt(args[2]), Integer.parseInt(args[3]));
            return;
        }
        check("decodes_an_anchor_to_a_box_in_input_pixels", decodesAnAnchor());
        check("decodes_the_coarsest_stride_too", coarsestStride());
        check("score_is_the_root_of_cls_times_obj_clamped", scoreClamped());
        check("below_threshold_dropped", belowThreshold());
        check("overlapping_faces_merged_keeping_the_best", overlapsMerged());
        check("far_apart_faces_both_kept_best_first_and_capped", farApartKept());
        check("short_output_rejected", shortRejected());
        check("largest_face_inside_the_person_box_is_picked", largestInside());
        check("no_face_inside_the_person_box_is_null", noneInside());
        check("frame_fits_the_input_without_upscaling", fitScale());
    }

    /** Empty outputs for a 640x640 input: cls, obj, bbox per stride. */
    private static final class Outputs {
        final float[][] cls = new float[3][];
        final float[][] obj = new float[3][];
        final float[][] bbox = new float[3][];

        Outputs() {
            for (int i = 0; i < 3; i++) {
                int cells = (IN / YuNetDecoder.STRIDES[i]) * (IN / YuNetDecoder.STRIDES[i]);
                cls[i] = new float[cells];
                obj[i] = new float[cells];
                bbox[i] = new float[cells * 4];
            }
        }

        /** A face at grid cell (row, col) of stride index s: offsets dx, dy, and w/h in strides. */
        Outputs put(int s, int row, int col, float cl, float ob, float dx, float dy, float w, float h) {
            int idx = row * (IN / YuNetDecoder.STRIDES[s]) + col;
            cls[s][idx] = cl;
            obj[s][idx] = ob;
            bbox[s][idx * 4] = dx;
            bbox[s][idx * 4 + 1] = dy;
            bbox[s][idx * 4 + 2] = (float) Math.log(w);
            bbox[s][idx * 4 + 3] = (float) Math.log(h);
            return this;
        }

        List<YuNetDecoder.Face> decode(int max) {
            return new YuNetDecoder(IN, IN).decode(cls, obj, bbox, MIN, 0.3f, max);
        }
    }

    private static String decodesAnAnchor() {
        // Stride 8, cell (30, 40), offsets 0.5: centre (324, 244); 12 x 15 strides = 96 x 120 px.
        List<YuNetDecoder.Face> d = new Outputs().put(0, 30, 40, 0.81f, 1f, 0.5f, 0.5f, 12f, 15f).decode(10);
        if (d.size() != 1) return "want 1 face, got " + d;
        YuNetDecoder.Face f = d.get(0);
        if (!near(f.x0, 276) || !near(f.y0, 184) || !near(f.x1, 372) || !near(f.y1, 304)) return "box " + f;
        return near(f.score, 0.9f) ? null : "score " + f;
    }

    private static String coarsestStride() {
        // Stride 32, cell (5, 10): centre (336, 176); 4 x 5 strides = 128 x 160 px.
        List<YuNetDecoder.Face> d = new Outputs().put(2, 5, 10, 1f, 1f, 0.5f, 0.5f, 4f, 5f).decode(10);
        if (d.size() != 1) return "want 1 face, got " + d;
        YuNetDecoder.Face f = d.get(0);
        return near(f.x0, 272) && near(f.y0, 96) && near(f.x1, 400) && near(f.y1, 256) ? null : "box " + f;
    }

    private static String scoreClamped() {
        // cls 1.5 reads as 1, obj 0.64: sqrt(0.64) = 0.8. A negative obj reads as 0 and drops out.
        List<YuNetDecoder.Face> d = new Outputs()
                .put(1, 10, 10, 1.5f, 0.64f, 0.5f, 0.5f, 3f, 3f)
                .put(1, 30, 30, 1f, -2f, 0.5f, 0.5f, 3f, 3f).decode(10);
        return d.size() == 1 && near(d.get(0).score, 0.8f) ? null : "got " + d;
    }

    private static String belowThreshold() {
        // sqrt(0.5 * 0.7) = 0.59 < 0.6; sqrt(0.6 * 0.61) = 0.605 stays.
        List<YuNetDecoder.Face> d = new Outputs()
                .put(1, 5, 5, 0.5f, 0.7f, 0.5f, 0.5f, 3f, 3f)
                .put(1, 30, 30, 0.6f, 0.61f, 0.5f, 0.5f, 3f, 3f).decode(10);
        return d.size() == 1 && near(d.get(0).score, 0.605f) ? null : "got " + d;
    }

    private static String overlapsMerged() {
        // The same face seen by neighbouring cells of two strides: one box survives, the stronger.
        List<YuNetDecoder.Face> d = new Outputs()
                .put(0, 30, 40, 0.7f, 1f, 0.5f, 0.5f, 12f, 15f)
                .put(0, 30, 41, 0.9f, 1f, 0.0f, 0.5f, 12f, 15f)
                .put(1, 15, 20, 0.8f, 1f, 0.25f, 0.25f, 6f, 7.5f).decode(10);
        return d.size() == 1 && near(d.get(0).score, (float) Math.sqrt(0.9)) ? null : "got " + d;
    }

    private static String farApartKept() {
        Outputs o = new Outputs()
                .put(1, 5, 5, 0.25f, 1f, 0.5f, 0.5f, 3f, 3f)
                .put(1, 5, 30, 0.81f, 1f, 0.5f, 0.5f, 3f, 3f)
                .put(1, 30, 5, 0.64f, 1f, 0.5f, 0.5f, 3f, 3f);
        List<YuNetDecoder.Face> all = o.decode(10);
        List<YuNetDecoder.Face> two = o.decode(2);
        if (all.size() != 2) return "want the two above 0.6, got " + all;
        if (!near(all.get(0).score, 0.9f) || !near(all.get(1).score, 0.8f)) return "order " + all;
        List<YuNetDecoder.Face> one = o.decode(1);
        return two.size() == 2 && one.size() == 1 && near(one.get(0).score, 0.9f) ? null : "cap " + one;
    }

    private static String shortRejected() {
        Outputs o = new Outputs().put(0, 30, 40, 1f, 1f, 0.5f, 0.5f, 12f, 15f);
        o.bbox[0] = new float[7];
        List<YuNetDecoder.Face> d = o.decode(10);
        return d.isEmpty() ? null : "decoded a short output: " + d;
    }

    private static String largestInside() {
        java.util.List<YuNetDecoder.Face> faces = new java.util.ArrayList<YuNetDecoder.Face>();
        faces.add(new YuNetDecoder.Face(10, 10, 210, 210, 0.99f));   // biggest, but outside the box
        faces.add(new YuNetDecoder.Face(300, 100, 340, 150, 0.95f)); // inside, small
        faces.add(new YuNetDecoder.Face(330, 90, 450, 240, 0.7f));   // inside, largest there
        faces.add(new YuNetDecoder.Face(560, 100, 700, 240, 0.9f));  // centre (630) right of the box
        YuNetDecoder.Face f = YuNetDecoder.largestInside(faces, 250, 50, 600, 480);
        return f == faces.get(2) ? null : "picked " + f;
    }

    private static String noneInside() {
        java.util.List<YuNetDecoder.Face> faces = new java.util.ArrayList<YuNetDecoder.Face>();
        faces.add(new YuNetDecoder.Face(10, 10, 110, 110, 0.99f));
        YuNetDecoder.Face f = YuNetDecoder.largestInside(faces, 250, 50, 600, 480);
        YuNetDecoder.Face none = YuNetDecoder.largestInside(new java.util.ArrayList<YuNetDecoder.Face>(), 0, 0, 9, 9);
        return f == null && none == null ? null : "picked " + f + " / " + none;
    }

    private static String fitScale() {
        // The camera's 640x480 goes in 1:1 (padded below); bigger frames shrink to fit; small ones never grow.
        float a = YuNetDecoder.fitScale(640, 480, 640, 640);
        float b = YuNetDecoder.fitScale(1280, 960, 640, 640);
        float c = YuNetDecoder.fitScale(320, 240, 640, 640);
        float d = YuNetDecoder.fitScale(640, 1280, 640, 640);
        return near(a, 1f) && near(b, 0.5f) && near(c, 1f) && near(d, 0.5f) ? null : a + " " + b + " " + c + " " + d;
    }

    private static void decodeFiles(String dir, int inW, int inH) throws IOException {
        String[] kinds = {"cls", "obj", "bbox"};
        float[][][] out = new float[3][3][];
        for (int k = 0; k < 3; k++) {
            for (int s = 0; s < 3; s++) {
                byte[] raw = Files.readAllBytes(Paths.get(dir, kinds[k] + "_" + YuNetDecoder.STRIDES[s] + ".bin"));
                float[] v = new float[raw.length / 4];
                ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(v);
                out[k][s] = v;
            }
        }
        for (YuNetDecoder.Face f : new YuNetDecoder(inW, inH).decode(out[0], out[1], out[2], MIN, 0.3f, 20)) {
            System.out.printf(java.util.Locale.US, "FACE %.1f %.1f %.1f %.1f %.3f%n", f.x0, f.y0, f.x1, f.y1, f.score);
        }
    }

    private static boolean near(float a, float b) {
        return Math.abs(a - b) < 1e-3f;
    }

    private static void check(String name, String failure) {
        System.out.println(failure == null ? "PASS " + name : "FAIL " + name + ": " + failure);
    }
}
