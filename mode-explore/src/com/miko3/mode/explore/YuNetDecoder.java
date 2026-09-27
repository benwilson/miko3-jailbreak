package com.miko3.mode.explore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Decodes the YuNet face detector's outputs (OpenCV Zoo,
 * face_detection_yunet_2026may.onnx, MIT; assets/face_yunet.onnx) into face
 * boxes and landmarks, the way OpenCV's FaceDetectorYN does (face_detect.cpp).
 * Plain Java, so the host harness tests it without android.* or ONNX Runtime.
 *
 * The model has one output set per stride (8, 16, 32), each a grid of
 * (inW / stride) x (inH / stride) cells, row-major:
 *  - cls_S and obj_S, one value per cell; the face score is
 *    sqrt(clamp(cls) * clamp(obj)), each clamped to 0..1;
 *  - bbox_S, four per cell: the centre as grid offsets (dx, dy) and the size as
 *    log strides (lw, lh). Centre = ((col + dx) * S, (row + dy) * S), size =
 *    (exp(lw) * S, exp(lh) * S), in the input's pixels;
 *  - kps_S, ten per cell: five landmarks as grid offsets (kx, ky), each at
 *    ((col + kx) * S, (row + ky) * S), in OpenCV's order: right eye, left eye,
 *    nose tip, right and left mouth corner (the subject's right, so the
 *    image-left eye comes first). Used to straighten the face (KTD2).
 * Faces under the score floor go, faces centred in the input's black padding go
 * (see the content size), then non-maximum suppression keeps the best of each
 * overlapping group.
 *
 * The 2026may model takes any input size whose sides are multiples of 32 (it
 * reports -1 for height and width, and fails on 320x240), so the size is a
 * fixed constant, not read from the model (on-device face recognition KTD2).
 */
final class YuNetDecoder {
    static final int[] STRIDES = {8, 16, 32};
    /**
     * The model input FaceCropper feeds (KTD2): the 640x480 camera frame halved
     * to 320x240 and padded with 16 black rows below, so both sides are
     * multiples of 32 and every stride's grid is whole (40x32, 20x16, 10x8).
     */
    static final int INPUT_W = 320;
    static final int INPUT_H = 256;
    /** Five landmarks, x and y each. */
    static final int LANDMARK_VALUES = 10;

    /** One face, a box and five landmarks in the input's (or, after scaling, the frame's) pixels. */
    static final class Face {
        final float x0;
        final float y0;
        final float x1;
        final float y1;
        final float score;
        /** x0, y0, ... x4, y4 in OpenCV's order (see the class comment); null when not decoded. */
        final float[] landmarks;

        Face(float x0, float y0, float x1, float y1, float score) {
            this(x0, y0, x1, y1, score, null);
        }

        Face(float x0, float y0, float x1, float y1, float score, float[] landmarks) {
            this.x0 = x0;
            this.y0 = y0;
            this.x1 = x1;
            this.y1 = y1;
            this.score = score;
            this.landmarks = landmarks;
        }

        float area() {
            return Math.max(0f, x1 - x0) * Math.max(0f, y1 - y0);
        }

        /** This face, box and landmarks, scaled by 1 / scale: from the input's pixels back to the frame's. */
        Face unscaled(float scale) {
            float[] points = null;
            if (landmarks != null) {
                points = new float[landmarks.length];
                for (int i = 0; i < points.length; i++) {
                    points[i] = landmarks[i] / scale;
                }
            }
            return new Face(x0 / scale, y0 / scale, x1 / scale, y1 / scale, score, points);
        }

        @Override
        public String toString() {
            return String.format(java.util.Locale.US, "[%.1f,%.1f,%.1f,%.1f %.3f]", x0, y0, x1, y1, score);
        }
    }

    private final int inW;
    private final int inH;
    /** The picture's part of the input, from the top-left; the rest is black padding. */
    private final int contentW;
    private final int contentH;

    /** A decoder for an inW x inH input filled edge to edge. */
    YuNetDecoder(int inW, int inH) {
        this(inW, inH, inW, inH);
    }

    /**
     * A decoder for an inW x inH input whose picture fills only contentW x
     * contentH at the top-left, the rest padded black: a face centred in the
     * padding is noise and never reported.
     */
    YuNetDecoder(int inW, int inH, int contentW, int contentH) {
        this.inW = inW;
        this.inH = inH;
        this.contentW = contentW;
        this.contentH = contentH;
    }

    /** The grid's columns at a stride: inW / stride. */
    int gridCols(int stride) {
        return inW / stride;
    }

    /** The grid's rows at a stride: inH / stride. */
    int gridRows(int stride) {
        return inH / stride;
    }

    /**
     * The faces scoring at least minScore, after NMS at nmsIou, best first, at
     * most max, each with its five landmarks. cls, obj, bbox and kps hold one
     * array per stride in STRIDES order; a stride whose arrays are missing or
     * not the grid's size is skipped (a wrong model), so no kps means no face.
     */
    List<Face> decode(float[][] cls, float[][] obj, float[][] bbox, float[][] kps,
                      float minScore, float nmsIou, int max) {
        List<Face> found = new ArrayList<Face>();
        if (cls == null || obj == null || bbox == null || kps == null) {
            return found;
        }
        for (int s = 0; s < STRIDES.length && s < cls.length && s < obj.length && s < bbox.length
                && s < kps.length; s++) {
            int stride = STRIDES[s];
            int cols = gridCols(stride);
            int rows = gridRows(stride);
            int cells = cols * rows;
            if (cls[s] == null || obj[s] == null || bbox[s] == null || kps[s] == null
                    || cls[s].length != cells || obj[s].length != cells || bbox[s].length != cells * 4
                    || kps[s].length != cells * LANDMARK_VALUES) {
                continue;
            }
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    int i = r * cols + c;
                    float score = (float) Math.sqrt(clamp01(cls[s][i]) * clamp01(obj[s][i]));
                    if (score < minScore) {
                        continue;
                    }
                    float cx = (c + bbox[s][i * 4]) * stride;
                    float cy = (r + bbox[s][i * 4 + 1]) * stride;
                    float w = (float) Math.exp(bbox[s][i * 4 + 2]) * stride;
                    float h = (float) Math.exp(bbox[s][i * 4 + 3]) * stride;
                    if (cx >= contentW || cy >= contentH) {
                        continue;
                    }
                    float[] points = new float[LANDMARK_VALUES];
                    for (int j = 0; j < LANDMARK_VALUES; j += 2) {
                        points[j] = (c + kps[s][i * LANDMARK_VALUES + j]) * stride;
                        points[j + 1] = (r + kps[s][i * LANDMARK_VALUES + j + 1]) * stride;
                    }
                    found.add(new Face(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f, score, points));
                }
            }
        }
        Collections.sort(found, new Comparator<Face>() {
            @Override
            public int compare(Face a, Face b) {
                return Float.compare(b.score, a.score);
            }
        });
        List<Face> kept = new ArrayList<Face>();
        for (Face f : found) {
            if (kept.size() >= max) {
                break;
            }
            boolean overlaps = false;
            for (Face k : kept) {
                if (iou(f, k) > nmsIou) {
                    overlaps = true;
                    break;
                }
            }
            if (!overlaps) {
                kept.add(f);
            }
        }
        return kept;
    }

    /** The largest face whose centre lies inside the region, or null. */
    static Face largestInside(List<Face> faces, float left, float top, float right, float bottom) {
        Face best = null;
        for (Face f : faces) {
            float cx = (f.x0 + f.x1) / 2f;
            float cy = (f.y0 + f.y1) / 2f;
            if (cx >= left && cx <= right && cy >= top && cy <= bottom
                    && (best == null || f.area() > best.area())) {
                best = f;
            }
        }
        return best;
    }

    /**
     * How much a frameW x frameH frame is scaled to fit an inW x inH input
     * (drawn at the top-left, the rest left black): at most 1, so a small frame
     * is never blown up. The camera's 640x480 goes into the 320x256 input at 0.5.
     */
    static float fitScale(int frameW, int frameH, int inW, int inH) {
        return Math.min(1f, Math.min(inW / (float) frameW, inH / (float) frameH));
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static float iou(Face a, Face b) {
        float iw = Math.min(a.x1, b.x1) - Math.max(a.x0, b.x0);
        float ih = Math.min(a.y1, b.y1) - Math.max(a.y0, b.y0);
        if (iw <= 0f || ih <= 0f) {
            return 0f;
        }
        float inter = iw * ih;
        return inter / (a.area() + b.area() - inter);
    }
}
