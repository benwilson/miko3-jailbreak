package com.miko3.mode.explore;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Decides who a face is from its embedding (on-device face recognition plan
 * U3; R1, R2, R3, R17, R18; KTD1, KTD5). Plain Java, no network, no Android,
 * so the host harness proves it.
 *
 * The probe (a unit-length SFace embedding) is compared by dot product with
 * every stored photo of every named person (R1). A person scores their best
 * photo, not the mean of their photos, so one good photo is enough and a
 * stale one never drags the others down. The best person's score gives the
 * band, each band inclusive at its lower edge (KTD5): score >= confident is
 * CONFIDENT, else score >= close is CLOSE, else WEAK. R17: a CONFIDENT result
 * whose best OTHER person scores within the margin (best - runner-up <
 * margin; a gap of exactly the margin stays confident, and margin 0 turns the
 * rule off) is demoted to CLOSE and flagged nearTie; it still names the
 * higher person. Two photos of the same person never make a near tie. R18:
 * with the ready flag false the band is NOT_READY whatever the scores; the
 * best and runner-up are still filled in for the face-check log, but a caller
 * acts on the band alone.
 *
 * Scores are floats, the precision the embeddings carry: each dot product is
 * summed in double and rounded to float once, and thresholds are floats, so a
 * score equal to the threshold is exactly on the edge. Entries without a
 * usable embedding (null, another length, not finite, or no id) are skipped:
 * a photo still waiting for its embedding, or marked unusable, never matches.
 * Equal scores break by the smaller person id, then the smaller slot, so the
 * answer does not depend on gallery order.
 */
final class FaceMatcher {
    /** Tags every embedding the mode writes; a stored embedding with another tag is recomputed. */
    static final String MODEL_ID = "sface-2021dec";

    /** The embedding length SFace gives (checked when the model loads). */
    static final int DIM = 128;

    /** The aligned crop's side, the model's input height and width. */
    static final int INPUT_SIDE = FaceAlign.SIDE;

    enum Band { CONFIDENT, CLOSE, WEAK, NOT_READY }

    /** The band thresholds (KTD5), launcher settings read at each meeting. */
    static final class Thresholds {
        /** Starting values until the bench (U9) fits them: SFace's published 1:1 threshold for close. */
        static final Thresholds DEFAULTS = new Thresholds(0.50f, 0.363f, 0.05f);

        final float confident;
        final float close;
        final float margin;

        Thresholds(float confident, float close, float margin) {
            this.confident = confident;
            this.close = close;
            this.margin = margin;
        }

        @Override
        public String toString() {
            return "confident " + confident + ", close " + close + ", margin " + margin;
        }
    }

    /** One stored photo's embedding: the person's id and the photo slot it came from. */
    static final class Entry {
        final String personId;
        final int slot;
        final float[] embedding;

        Entry(String personId, int slot, float[] embedding) {
            this.personId = personId;
            this.slot = slot;
            this.embedding = embedding;
        }
    }

    /** The decision. With no usable entry: no best id, slot -1, score NaN. */
    static final class Result {
        final Band band;
        final String bestId;
        final int bestSlot;
        final float score;
        /** The best-scoring person other than bestId, or null (runnerUpScore NaN). */
        final String runnerUpId;
        final float runnerUpScore;
        /** True when R17 demoted a confident result to close. */
        final boolean nearTie;

        Result(Band band, String bestId, int bestSlot, float score, String runnerUpId, float runnerUpScore,
               boolean nearTie) {
            this.band = band;
            this.bestId = bestId;
            this.bestSlot = bestSlot;
            this.score = score;
            this.runnerUpId = runnerUpId;
            this.runnerUpScore = runnerUpScore;
            this.nearTie = nearTie;
        }

        boolean hasBest() {
            return bestId != null;
        }

        /** Ids and scores only: no names, no pixels. */
        @Override
        public String toString() {
            return String.format(Locale.ROOT, "%s best=%s/%d %.4f runner-up=%s %.4f%s", band, bestId, bestSlot,
                    score, runnerUpId, runnerUpScore, nearTie ? " near-tie" : "");
        }
    }

    private FaceMatcher() {
    }

    /**
     * The band for a probe against the gallery. A null probe or gallery counts
     * as nothing to match (WEAK, or NOT_READY when not ready); null thresholds
     * are the defaults. Neither the probe nor the gallery is modified.
     */
    static Result match(float[] probe, List<Entry> gallery, Thresholds thresholds, boolean ready) {
        Thresholds t = thresholds != null ? thresholds : Thresholds.DEFAULTS;
        Map<String, Entry> bestPhoto = new HashMap<>();
        Map<String, Float> bestScore = new HashMap<>();
        if (probe != null && gallery != null) {
            for (Entry e : gallery) {
                if (e == null || e.personId == null || e.embedding == null || e.embedding.length != probe.length) {
                    continue;
                }
                double sum = 0;
                for (int i = 0; i < probe.length; i++) {
                    sum += probe[i] * (double) e.embedding[i];
                }
                if (Double.isNaN(sum) || Double.isInfinite(sum)) {
                    continue;
                }
                float s = (float) sum;
                Float cur = bestScore.get(e.personId);
                if (cur == null || s > cur || (s == cur && e.slot < bestPhoto.get(e.personId).slot)) {
                    bestScore.put(e.personId, s);
                    bestPhoto.put(e.personId, e);
                }
            }
        }
        String topId = null;
        String runId = null;
        for (String id : bestScore.keySet()) {
            if (ranksAbove(id, topId, bestScore)) {
                runId = topId;
                topId = id;
            } else if (ranksAbove(id, runId, bestScore)) {
                runId = id;
            }
        }
        float score = topId == null ? Float.NaN : bestScore.get(topId);
        int slot = topId == null ? -1 : bestPhoto.get(topId).slot;
        float runScore = runId == null ? Float.NaN : bestScore.get(runId);
        boolean nearTie = false;
        Band band;
        if (!ready) {
            band = Band.NOT_READY;
        } else if (topId == null) {
            band = Band.WEAK;
        } else if (score >= t.confident) {
            band = Band.CONFIDENT;
            if (runId != null && score - runScore < t.margin) {
                band = Band.CLOSE;
                nearTie = true;
            }
        } else if (score >= t.close) {
            band = Band.CLOSE;
        } else {
            band = Band.WEAK;
        }
        return new Result(band, topId, slot, score, runId, runScore, nearTie);
    }

    /** True when person a outranks person b (null b is outranked by anyone): higher score, then smaller id. */
    private static boolean ranksAbove(String a, String b, Map<String, Float> scores) {
        if (b == null) {
            return true;
        }
        float sa = scores.get(a);
        float sb = scores.get(b);
        return sa > sb || (sa == sb && a.compareTo(b) < 0);
    }

    /** v scaled to unit length, as a new array; null when v is null, empty, all zero or not finite. */
    static float[] normalize(float[] v) {
        if (v == null || v.length == 0) {
            return null;
        }
        double sq = 0;
        for (float x : v) {
            sq += (double) x * x;
        }
        if (!(sq > 0) || Double.isInfinite(sq)) {
            return null;
        }
        double inv = 1.0 / Math.sqrt(sq);
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = (float) (v[i] * inv);
        }
        return out;
    }

    /**
     * The model input for an aligned INPUT_SIDE x INPUT_SIDE ARGB crop (KTD1):
     * three planes R, G, B of raw 0..255 floats (NCHW, batch 1), no
     * normalisation, since SFace normalises internally. Alpha is dropped.
     */
    static void input(int[] alignedArgb, float[] out) {
        int plane = INPUT_SIDE * INPUT_SIDE;
        if (alignedArgb == null || alignedArgb.length != plane) {
            throw new IllegalArgumentException("aligned crop must be " + INPUT_SIDE + "x" + INPUT_SIDE);
        }
        if (out == null || out.length < 3 * plane) {
            throw new IllegalArgumentException("output must hold 3 planes");
        }
        for (int i = 0; i < plane; i++) {
            int p = alignedArgb[i];
            out[i] = (p >> 16) & 0xff;
            out[plane + i] = (p >> 8) & 0xff;
            out[2 * plane + i] = p & 0xff;
        }
    }
}
