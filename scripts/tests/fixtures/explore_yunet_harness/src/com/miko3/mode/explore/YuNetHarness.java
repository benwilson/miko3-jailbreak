package com.miko3.mode.explore;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/**
 * Host harness for YuNetDecoder (explore on Claude KTD5; on-device face
 * recognition U1, KTD2). Builds synthetic outputs in the 2026may layout -- per
 * stride 8/16/32 a cls and obj score, four bbox values (grid offsets, log width
 * and height) and ten kps values (five landmarks as grid offsets) per grid cell,
 * row-major -- and prints one PASS/FAIL line per scenario.
 *
 * With "--decode dir inW inH contentW contentH scale" it instead reads real
 * model outputs from dir/{cls,obj,bbox,kps}_{8,16,32}.bin (float32
 * little-endian), decodes them as FaceCropper does, and prints one
 * "FACE x0 y0 x1 y1 score l0x l0y ... l4x l4y" line per face, in frame pixels,
 * at the production threshold.
 */
public final class YuNetHarness {
    private static final int IN = 640;
    private static final float MIN = 0.6f;

    public static void main(String[] args) throws IOException {
        if (args.length == 7 && args[0].equals("--decode")) {
            decodeFiles(args[1], Integer.parseInt(args[2]), Integer.parseInt(args[3]),
                    Integer.parseInt(args[4]), Integer.parseInt(args[5]), Float.parseFloat(args[6]));
            return;
        }
        check("decodes_an_anchor_to_a_box_in_input_pixels", decodesAnAnchor());
        check("decodes_the_coarsest_stride_too", coarsestStride());
        check("decodes_five_landmarks_from_kps_with_the_cell_offset_rule", landmarksDecoded());
        check("landmarks_scale_back_to_frame_pixels_with_the_box", landmarksScaledBack());
        check("input_is_320x256_with_whole_grids_at_every_stride", inputGrids());
        check("a_face_in_the_padded_bottom_rows_is_never_reported", paddedRowsIgnored());
        check("short_landmark_output_rejected", shortKpsRejected());
        check("empty_outputs_find_no_face", emptyFindsNone());
        check("score_is_the_root_of_cls_times_obj_clamped", scoreClamped());
        check("below_threshold_dropped", belowThreshold());
        check("overlapping_faces_merged_keeping_the_best", overlapsMerged());
        check("far_apart_faces_both_kept_best_first_and_capped", farApartKept());
        check("short_output_rejected", shortRejected());
        check("largest_face_inside_the_person_box_is_picked", largestInside());
        check("no_face_inside_the_person_box_is_null", noneInside());
        check("frame_fits_the_input_without_upscaling", fitScale());
    }

    /** Empty outputs for an inW x inH input: cls, obj, bbox and kps per stride. */
    private static final class Outputs {
        final int inW;
        final int inH;
        final float[][] cls = new float[3][];
        final float[][] obj = new float[3][];
        final float[][] bbox = new float[3][];
        final float[][] kps = new float[3][];

        Outputs() {
            this(IN, IN);
        }

        Outputs(int inW, int inH) {
            this.inW = inW;
            this.inH = inH;
            for (int i = 0; i < 3; i++) {
                int cells = (inW / YuNetDecoder.STRIDES[i]) * (inH / YuNetDecoder.STRIDES[i]);
                cls[i] = new float[cells];
                obj[i] = new float[cells];
                bbox[i] = new float[cells * 4];
                kps[i] = new float[cells * 10];
            }
        }

        /** A face at grid cell (row, col) of stride index s: offsets dx, dy, and w/h in strides. */
        Outputs put(int s, int row, int col, float cl, float ob, float dx, float dy, float w, float h) {
            int idx = row * (inW / YuNetDecoder.STRIDES[s]) + col;
            cls[s][idx] = cl;
            obj[s][idx] = ob;
            bbox[s][idx * 4] = dx;
            bbox[s][idx * 4 + 1] = dy;
            bbox[s][idx * 4 + 2] = (float) Math.log(w);
            bbox[s][idx * 4 + 3] = (float) Math.log(h);
            return this;
        }

        /** The five landmarks of cell (row, col) of stride index s, as grid offsets x0, y0, ... x4, y4. */
        Outputs kps(int s, int row, int col, float... offsets) {
            int idx = row * (inW / YuNetDecoder.STRIDES[s]) + col;
            System.arraycopy(offsets, 0, kps[s], idx * 10, 10);
            return this;
        }

        List<YuNetDecoder.Face> decode(int max) {
            return new YuNetDecoder(inW, inH).decode(cls, obj, bbox, kps, MIN, 0.3f, max);
        }
    }

    /** Stride-16 landmark offsets for a face at cell (row 5, col 7): eyes, nose, mouth corners. */
    private static final float[] KPS_16 = {-0.5f, -0.25f, 0.5f, -0.25f, 0f, 0.25f, -0.25f, 0.75f, 0.25f, 0.75f};

    private static String landmarksDecoded() {
        // Stride 16 at a 320x256 input, cell (5, 7): landmark j = ((7 + kx) * 16, (5 + ky) * 16).
        List<YuNetDecoder.Face> d = new Outputs(320, 256).put(1, 5, 7, 1f, 1f, 0.5f, 0.5f, 3f, 4f)
                .kps(1, 5, 7, KPS_16).decode(10);
        if (d.size() != 1) return "want 1 face, got " + d;
        float[] want = {104, 76, 120, 76, 112, 84, 108, 92, 116, 92};
        float[] got = d.get(0).landmarks;
        if (got == null || got.length != 10) return "landmarks " + java.util.Arrays.toString(got);
        for (int i = 0; i < 10; i++) {
            if (!near(got[i], want[i])) return "landmarks " + java.util.Arrays.toString(got);
        }
        return null;
    }

    private static String landmarksScaledBack() {
        // FaceCropper halves a 640x480 frame into the 320x256 input; unscaled(0.5)
        // doubles the box and the landmarks back into frame pixels.
        List<YuNetDecoder.Face> d = new Outputs(320, 256).put(1, 5, 7, 1f, 1f, 0.5f, 0.5f, 3f, 4f)
                .kps(1, 5, 7, KPS_16).decode(10);
        if (d.size() != 1) return "want 1 face, got " + d;
        float scale = YuNetDecoder.fitScale(640, 480, YuNetDecoder.INPUT_W, YuNetDecoder.INPUT_H);
        YuNetDecoder.Face in = d.get(0);
        YuNetDecoder.Face f = in.unscaled(scale);
        if (!near(scale, 0.5f)) return "scale " + scale;
        // Box centre (120, 88), 48 x 64 in the input: (192, 112)-(288, 240) in the frame.
        if (!near(f.x0, 192) || !near(f.y0, 112) || !near(f.x1, 288) || !near(f.y1, 240)) return "box " + f;
        float[] want = {208, 152, 240, 152, 224, 168, 216, 184, 232, 184};
        for (int i = 0; i < 10; i++) {
            if (!near(f.landmarks[i], want[i])) return "landmarks " + java.util.Arrays.toString(f.landmarks);
        }
        return near(in.landmarks[0], 104) && near(f.score, in.score) ? null : "input face changed " + in;
    }

    private static String inputGrids() {
        // KTD2: 320x256 (both multiples of 32), grids 40x32, 20x16 and 10x8.
        if (YuNetDecoder.INPUT_W != 320 || YuNetDecoder.INPUT_H != 256) {
            return "input " + YuNetDecoder.INPUT_W + "x" + YuNetDecoder.INPUT_H;
        }
        YuNetDecoder dec = new YuNetDecoder(YuNetDecoder.INPUT_W, YuNetDecoder.INPUT_H);
        int[][] want = {{40, 32}, {20, 16}, {10, 8}};
        for (int s = 0; s < 3; s++) {
            int stride = YuNetDecoder.STRIDES[s];
            if (dec.gridCols(stride) != want[s][0] || dec.gridRows(stride) != want[s][1]) {
                return "stride " + stride + ": " + dec.gridCols(stride) + "x" + dec.gridRows(stride);
            }
        }
        // A face in the last cell of every stride decodes, so each grid is read whole.
        Outputs o = new Outputs(320, 256);
        for (int s = 0; s < 3; s++) {
            o.put(s, want[s][1] - 1, want[s][0] - 1, 0.81f + 0.05f * s, 1f, 0f, 0f, 1f, 1f);
        }
        List<YuNetDecoder.Face> d = o.decode(10);
        return d.size() == 3 ? null : "want the three corner faces, got " + d;
    }

    private static String paddedRowsIgnored() {
        // The 640x480 frame halves to 320x240 in a 320x256 input: rows 240..255 are
        // black padding, and a face centred there is noise, never reported.
        Outputs o = new Outputs(320, 256)
                .put(0, 30, 10, 1f, 1f, 0.5f, 0.5f, 2f, 2f)   // centre (84, 244): padding
                .put(2, 7, 3, 1f, 1f, 0.5f, 0.9f, 1f, 1f)     // centre (112, 252.8): padding
                .put(1, 14, 5, 0.81f, 1f, 0.5f, 0.5f, 2f, 2f); // centre (88, 232): picture
        List<YuNetDecoder.Face> d = new YuNetDecoder(320, 256, 320, 240)
                .decode(o.cls, o.obj, o.bbox, o.kps, MIN, 0.3f, 10);
        if (d.size() != 1 || !near(d.get(0).score, 0.9f)) return "got " + d;
        List<YuNetDecoder.Face> all = o.decode(10);
        return all.size() == 3 ? null : "without a content size all three decode, got " + all;
    }

    private static String shortKpsRejected() {
        Outputs o = new Outputs(320, 256).put(0, 10, 10, 1f, 1f, 0.5f, 0.5f, 4f, 4f);
        o.kps[0] = new float[9];
        List<YuNetDecoder.Face> d = o.decode(10);
        Outputs none = new Outputs(320, 256).put(0, 10, 10, 1f, 1f, 0.5f, 0.5f, 4f, 4f);
        List<YuNetDecoder.Face> e = new YuNetDecoder(320, 256).decode(none.cls, none.obj, none.bbox, null, MIN, 0.3f, 10);
        return d.isEmpty() && e.isEmpty() ? null : "decoded without landmarks: " + d + " / " + e;
    }

    private static String emptyFindsNone() {
        // R10: all-zero outputs (a blank frame) are no face, so FaceCropper returns Result.NONE.
        List<YuNetDecoder.Face> d = new Outputs(320, 256).decode(10);
        return d.isEmpty() ? null : "got " + d;
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
        // The camera's 640x480 halves into 320x256 (padded below); a 640x480 input takes it
        // 1:1; bigger frames shrink to fit; small ones never grow.
        float a = YuNetDecoder.fitScale(640, 480, 320, 256);
        float b = YuNetDecoder.fitScale(640, 480, 640, 480);
        float c = YuNetDecoder.fitScale(1280, 960, 640, 640);
        float d = YuNetDecoder.fitScale(160, 120, 320, 256);
        float e = YuNetDecoder.fitScale(640, 1280, 320, 256);
        return near(a, 0.5f) && near(b, 1f) && near(c, 0.5f) && near(d, 1f) && near(e, 0.2f)
                ? null : a + " " + b + " " + c + " " + d + " " + e;
    }

    private static void decodeFiles(String dir, int inW, int inH, int contentW, int contentH, float scale)
            throws IOException {
        String[] kinds = {"cls", "obj", "bbox", "kps"};
        float[][][] out = new float[4][3][];
        for (int k = 0; k < 4; k++) {
            for (int s = 0; s < 3; s++) {
                byte[] raw = Files.readAllBytes(Paths.get(dir, kinds[k] + "_" + YuNetDecoder.STRIDES[s] + ".bin"));
                float[] v = new float[raw.length / 4];
                ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(v);
                out[k][s] = v;
            }
        }
        YuNetDecoder decoder = new YuNetDecoder(inW, inH, contentW, contentH);
        for (YuNetDecoder.Face in : decoder.decode(out[0], out[1], out[2], out[3], MIN, 0.3f, 20)) {
            YuNetDecoder.Face f = in.unscaled(scale);
            StringBuilder line = new StringBuilder(String.format(java.util.Locale.US, "FACE %.3f %.3f %.3f %.3f %.4f",
                    f.x0, f.y0, f.x1, f.y1, f.score));
            for (float v : f.landmarks) {
                line.append(String.format(java.util.Locale.US, " %.3f", v));
            }
            System.out.println(line);
        }
    }

    private static boolean near(float a, float b) {
        return Math.abs(a - b) < 1e-3f;
    }

    private static void check(String name, String failure) {
        System.out.println(failure == null ? "PASS " + name : "FAIL " + name + ": " + failure);
    }
}
