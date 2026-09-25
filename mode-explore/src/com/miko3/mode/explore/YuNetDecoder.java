package com.miko3.mode.explore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Decodes the YuNet face detector's outputs (OpenCV Zoo,
 * face_detection_yunet_2023mar.onnx, MIT; assets/face_yunet.onnx) into face
 * boxes, the way OpenCV's FaceDetectorYN does. Plain Java, so the host harness
 * tests it without android.* or ONNX Runtime.
 *
 * The model has one output set per stride (8, 16, 32), each a grid of
 * (inW / stride) x (inH / stride) cells, row-major:
 *  - cls_S and obj_S, one value per cell; the face score is
 *    sqrt(clamp(cls) * clamp(obj)), each clamped to 0..1;
 *  - bbox_S, four per cell: the centre as grid offsets (dx, dy) and the size as
 *    log strides (lw, lh). Centre = ((col + dx) * S, (row + dy) * S), size =
 *    (exp(lw) * S, exp(lh) * S), in the input's pixels.
 * (The kps_S landmarks are not needed.) Faces under the score floor go, then
 * non-maximum suppression keeps the best of each overlapping group.
 */
final class YuNetDecoder {
    static final int[] STRIDES = {8, 16, 32};

    /** One face, a box in the input's (or, after scaling, the frame's) pixels. */
    static final class Face {
        final float x0;
        final float y0;
        final float x1;
        final float y1;
        final float score;

        Face(float x0, float y0, float x1, float y1, float score) {
            this.x0 = x0;
            this.y0 = y0;
            this.x1 = x1;
            this.y1 = y1;
            this.score = score;
        }

        float area() {
            return Math.max(0f, x1 - x0) * Math.max(0f, y1 - y0);
        }

        /** This face scaled by 1 / scale: from the input's pixels back to the frame's. */
        Face unscaled(float scale) {
            return new Face(x0 / scale, y0 / scale, x1 / scale, y1 / scale, score);
        }

        @Override
        public String toString() {
            return String.format(java.util.Locale.US, "[%.1f,%.1f,%.1f,%.1f %.3f]", x0, y0, x1, y1, score);
        }
    }

    private final int inW;
    private final int inH;

    YuNetDecoder(int inW, int inH) {
        this.inW = inW;
        this.inH = inH;
    }

    /**
     * The faces scoring at least minScore, after NMS at nmsIou, best first, at
     * most max. cls, obj and bbox hold one array per stride in STRIDES order; a
     * stride whose arrays are not the grid's size is skipped (a wrong model).
     */
    List<Face> decode(float[][] cls, float[][] obj, float[][] bbox, float minScore, float nmsIou, int max) {
        List<Face> found = new ArrayList<Face>();
        for (int s = 0; s < STRIDES.length && s < cls.length && s < obj.length && s < bbox.length; s++) {
            int stride = STRIDES[s];
            int cols = inW / stride;
            int rows = inH / stride;
            int cells = cols * rows;
            if (cls[s] == null || obj[s] == null || bbox[s] == null
                    || cls[s].length != cells || obj[s].length != cells || bbox[s].length != cells * 4) {
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
                    found.add(new Face(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f, score));
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
     * (drawn at the top-left, the rest left black): at most 1, so the camera's
     * 640x480 goes into the 640x640 model as it is, and small faces stay sharp.
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
