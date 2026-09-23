package com.miko3.mode.explore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Turns the exported YOLOE detector's raw output into Detections (KTD2).
 *
 * The model (scripts/export-explore-detector.py) outputs one tensor shaped
 * [4 + classes + 32, anchors]: per anchor, the box centre x, centre y, width and
 * height in input pixels, then one already-sigmoided score per vocabulary name,
 * then 32 mask coefficients this mode ignores. Unlike the base YOLO26 it is not
 * de-duplicated, so overlapping boxes of the same name are merged here with
 * standard non-maximum suppression.
 *
 * Plain Java so it runs on the host JVM (scripts/tests/test_explore_sighting.py).
 */
final class YoloeDecoder {
    private final String[] names;
    private final int inputWidth;
    private final int inputHeight;

    YoloeDecoder(String[] names, int inputWidth, int inputHeight) {
        this.names = names;
        this.inputWidth = inputWidth;
        this.inputHeight = inputHeight;
    }

    /**
     * @param out     the output tensor flattened row-major: out[row * anchors + anchor]
     * @param anchors the number of anchors (the tensor's last dimension)
     * @param minScore detections below this confidence are dropped
     * @param maxIou   same-name boxes overlapping more than this are merged
     * @param maxCount at most this many detections, highest scores first
     */
    List<Detection> decode(float[] out, int anchors, float minScore, float maxIou, int maxCount) {
        int classes = names.length;
        if (out.length < (4 + classes) * anchors) {
            throw new IllegalArgumentException("output has " + out.length + " values, need at least "
                    + (4 + classes) * anchors + " for " + classes + " names");
        }
        // Best name per anchor, scanning each score row in order (the tensor is
        // row-major, so walking anchors inside a row reads memory sequentially).
        int[] best = new int[anchors];
        float[] bestScore = new float[anchors];
        Arrays.fill(best, -1);
        Arrays.fill(bestScore, minScore);
        for (int c = 0; c < classes; c++) {
            int row = (4 + c) * anchors;
            for (int a = 0; a < anchors; a++) {
                float s = out[row + a];
                if (s >= bestScore[a]) {
                    bestScore[a] = s;
                    best[a] = c;
                }
            }
        }
        List<Candidate> candidates = new ArrayList<Candidate>();
        for (int a = 0; a < anchors; a++) {
            if (best[a] < 0) {
                continue;
            }
            float cx = out[a];
            float cy = out[anchors + a];
            float w = out[2 * anchors + a];
            float h = out[3 * anchors + a];
            candidates.add(new Candidate(best[a], bestScore[a], cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2));
        }
        Collections.sort(candidates, new Comparator<Candidate>() {
            @Override
            public int compare(Candidate p, Candidate q) {
                return Float.compare(q.score, p.score);
            }
        });
        List<Candidate> kept = new ArrayList<Candidate>();
        for (Candidate c : candidates) {
            boolean overlaps = false;
            for (Candidate k : kept) {
                if (k.cls == c.cls && iou(k, c) > maxIou) {
                    overlaps = true;
                    break;
                }
            }
            if (!overlaps) {
                kept.add(c);
                if (kept.size() >= maxCount) {
                    break;
                }
            }
        }
        List<Detection> result = new ArrayList<Detection>(kept.size());
        for (Candidate c : kept) {
            result.add(new Detection(names[c.cls], c.score, c.x0 / inputWidth, c.y0 / inputHeight,
                    c.x1 / inputWidth, c.y1 / inputHeight));
        }
        return result;
    }

    private static float iou(Candidate a, Candidate b) {
        float iw = Math.max(0f, Math.min(a.x1, b.x1) - Math.max(a.x0, b.x0));
        float ih = Math.max(0f, Math.min(a.y1, b.y1) - Math.max(a.y0, b.y0));
        float inter = iw * ih;
        float union = (a.x1 - a.x0) * (a.y1 - a.y0) + (b.x1 - b.x0) * (b.y1 - b.y0) - inter;
        return union <= 0 ? 0f : inter / union;
    }

    private static final class Candidate {
        final int cls;
        final float score;
        final float x0;
        final float y0;
        final float x1;
        final float y1;

        Candidate(int cls, float score, float x0, float y0, float x1, float y1) {
            this.cls = cls;
            this.score = score;
            this.x0 = x0;
            this.y0 = y0;
            this.x1 = x1;
            this.y1 = y1;
        }
    }
}
