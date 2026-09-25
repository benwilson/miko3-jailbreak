package com.miko3.mode.explore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * How open the floor ahead looks in one camera frame (explore nav plan U3,
 * KTD3): BINS column bins across the frame, each 0 (blocked) to 1 (open), plus
 * a confidence. Three cheap signals, never a depth model:
 * <ol>
 * <li>the detector's boxes: a box whose bottom edge reaches low in the frame is
 * near and blocks its columns; one ending above the horizon only dents them;</li>
 * <li>a floor-colour model of the bottom rows, a few patches so a rug still
 * reads as floor. A colour never taught reads unsure (UNSURE), not blocked:
 * the floor sensor, not the camera, stops him near his nose (KTD9);</li>
 * <li>a horizon test: floor never shows above the horizon, so whatever sits just
 * above it and carries on down into the lower frame is standing in the way,
 * and the further down it reaches the nearer it is. With the floor taught, a
 * surface that comes well down with no floor between it and the floor run at
 * the bottom stands where that run ends (a wall 2 ft off leaves a sliver).</li>
 * </ol>
 *
 * Teaching (R2, "floor sensor and camera together"): a frame captured while the
 * floor was clear and the wheels free leaves its bottom-centre patch pending.
 * It is taught once the brain reports he drove over it (floorDrivenOver), and
 * dropped if a hazard or stall comes first (floorHazard); a late frame from
 * before that hazard is refused. Low light or no taught floor gives low
 * confidence, and the brain then leans on the boxes and the floor sensor.
 *
 * Plain Java (no Android or shared-driver imports) so it runs on the host JVM.
 * It never logs and keeps no pixels, only colour means (R15). Methods are
 * synchronized: ExploreCamera scores on its detect thread and forwards the
 * brain's teach and hazard calls. Thresholds were tuned on U3's gate frames
 * (this camera is dim and tilted up; see HORIZON and the luma constants), and
 * colour distances are scaled for dim light (apart()).
 */
final class Openness {
    static final int BINS = 16;
    /**
     * Where the horizon falls, as a fraction of frame height (top 0). The camera
     * sits ~10 cm up and tilts upward, so its horizon is well below the frame's
     * middle: in the U3 gate frames the far floor through a doorway ends at ~0.85
     * and a wall 2-3 ft away meets the floor at ~0.98, both putting it near 0.82.
     * Floor never shows above it; everything standing on the floor crosses it.
     */
    static final float HORIZON = 0.8f;
    /** Floor colours kept; the least recently taught is forgotten first. */
    static final int MAX_PATCHES = 4;
    /** Samples awaiting the brain's word; the oldest is dropped first. */
    static final int MAX_PENDING = 8;
    /** What a colour the model never saw scores: unsure, not blocked. */
    static final float UNSURE = 0.5f;

    /** A box whose bottom edge is at or below this (the nearer half of the ground below the horizon) blocks its columns. */
    private static final float NEAR_BOX_BOTTOM = 0.9f;
    /** What a box ending at or above the horizon (far away) takes off its columns. */
    private static final float FAR_BOX_PENALTY = 0.15f;
    /** A box counts for a bin when it covers at least this share of the bin's width. */
    private static final float MIN_BIN_OVERLAP = 0.25f;
    /** The band just above the horizon that the horizon test follows down. */
    private static final float REFERENCE_ROWS = 0.04f;
    /** A reach this short (share of the lower frame) is a far wall's base, not in the way. */
    private static final float REACH_MARGIN = 0.1f;
    /**
     * A surface coming down this far (share of the lower frame) from the horizon
     * is standing in front of him, not a far wall's base (those end within the
     * ground band's top fifth or so, the true horizon sitting just below HORIZON).
     */
    private static final float STANDING_REACH = 0.25f;
    /**
     * Colour distance (RGB, 0..441, scaled for dim light by apart()) within which
     * neighbouring pixels are one surface, and how far a surface may drift from
     * its colour at the horizon (shading down a plain wall) and still count as it.
     */
    private static final float SAME_SURFACE = 32f;
    private static final float SURFACE_DRIFT = 64f;
    /** The least step (same units) at which a surface coming down meets the floor. */
    private static final float FLOOR_EDGE = 12f;
    /**
     * Shading and sensor noise scale with brightness, so below this luma colour
     * distances are scaled up by LIGHT_REFERENCE / luma (this camera's frames are
     * dim: luma 20-35 in office light), never by more than LIGHT_REFERENCE / MIN_LIGHT.
     */
    private static final float LIGHT_REFERENCE = 64f;
    private static final float MIN_LIGHT = 16f;
    /** A floor patch matches within this distance plus twice its spread, up to MAX_FLOOR_TOLERANCE. */
    private static final float FLOOR_TOLERANCE = 30f;
    private static final float MAX_FLOOR_TOLERANCE = 60f;
    /** A bin's row is floor when at least this share of its pixels match a patch. */
    private static final float FLOOR_ROW_SHARE = 0.6f;
    /** The teaching sample: the bottom rows (the nearer half of the ground), centre columns (what he drives onto next). */
    private static final float SAMPLE_TOP = 0.9f;
    private static final float SAMPLE_LEFT = 0.3f;
    private static final float SAMPLE_RIGHT = 0.7f;
    /** A sample more varied than this is not one floor (a toy, a seam, an edge). */
    private static final float MAX_SAMPLE_SPREAD = 18f;
    /**
     * Mean frame luma below DARK: no confidence; above DIM: full. This camera's
     * office-light frames average luma ~30, so DIM sits there, not at a bright
     * room's 60. A sample needs SAMPLE_LUMA: darker than that is sensor noise.
     */
    private static final float DARK_LUMA = 8f;
    private static final float DIM_LUMA = 30f;
    private static final float SAMPLE_LUMA = 12f;
    private static final float TAUGHT_CONFIDENCE = 0.9f;
    private static final float UNTAUGHT_CONFIDENCE = 0.3f;
    /** Merging stops weighting history beyond this many samples, so a patch follows the light. */
    private static final int MERGE_WEIGHT_CAP = 20;

    /** Part of a frame, full width: 0xRRGGBB pixels row-major, covering rows top..bottom (fractions). */
    static final class Frame {
        final int[] rgb;
        final int width;
        final int height;
        final float top;
        final float bottom;

        Frame(int[] rgb, int width, int height, float top, float bottom) {
            this.rgb = rgb;
            this.width = width;
            this.height = height;
            this.top = top;
            this.bottom = bottom;
        }

        boolean usable() {
            return rgb != null && width > 0 && height > 0 && (long) width * height <= rgb.length
                    && top >= 0f && bottom <= 1f && top < bottom;
        }

        float rowCentre(int row) {
            return top + (row + 0.5f) * (bottom - top) / height;
        }

        float rowBottom(int row) {
            return top + (row + 1f) * (bottom - top) / height;
        }

        int at(int x, int y) {
            return rgb[y * width + x];
        }
    }

    /** One look's openness: bins left to right, and how far to trust them. */
    static final class Profile {
        final float[] bins;
        final float confidence;

        Profile(float[] bins, float confidence) {
            this.bins = bins;
            this.confidence = confidence;
        }

        /** Numbers only, for the owner's gate-check file (never the log, R15). */
        @Override
        public String toString() {
            StringBuilder s = new StringBuilder(String.format(Locale.US, "confidence %.2f bins", confidence));
            for (float b : bins) {
                s.append(String.format(Locale.US, " %.2f", b));
            }
            return s.toString();
        }
    }

    private static final class Patch {
        float r;
        float g;
        float b;
        float spread;
        int count;
        long taughtSeq;

        float tolerance() {
            return Math.min(MAX_FLOOR_TOLERANCE, FLOOR_TOLERANCE + 2f * spread);
        }
    }

    private static final class Sample {
        final long frameMs;
        final float r;
        final float g;
        final float b;
        final float spread;

        Sample(long frameMs, float r, float g, float b, float spread) {
            this.frameMs = frameMs;
            this.r = r;
            this.g = g;
            this.b = b;
            this.spread = spread;
        }
    }

    private final List<Patch> patches = new ArrayList<>();
    private final List<Sample> pending = new ArrayList<>();
    private long hazardMs = Long.MIN_VALUE;
    private long seq;

    /**
     * Scores one frame. whole covers the full frame at low resolution; band, when
     * given, the rows below the horizon at a higher one (else whole's lower rows
     * serve). teachable: captured while the floor was clear and the wheels free,
     * so its bottom patch may become a pending sample. Never throws.
     */
    synchronized Profile score(Frame whole, Frame band, List<Detection> detections, long frameMs, boolean teachable) {
        float[] boxes = new float[BINS];
        Arrays.fill(boxes, 1f);
        try {
            boxes = boxOpenness(detections);
            if (whole == null || !whole.usable()) {
                return blind(boxes);
            }
            Frame lower = band != null && band.usable() ? band : whole;
            if (teachable && frameMs > hazardMs) {
                sample(lower, frameMs);
            }
            float light = clamp01((meanLuma(whole) - DARK_LUMA) / (DIM_LUMA - DARK_LUMA));
            float[] bins = judge(whole, lower, boxes);
            return new Profile(bins, light * (patches.isEmpty() ? UNTAUGHT_CONFIDENCE : TAUGHT_CONFIDENCE));
        } catch (RuntimeException e) {
            return blind(boxes);
        }
    }

    /** He drove over every patch seen up to this frame time with no hazard or stall: teach them. */
    synchronized void floorDrivenOver(long throughFrameMs) {
        for (int i = 0; i < pending.size(); ) {
            Sample s = pending.get(i);
            if (s.frameMs <= throughFrameMs) {
                learn(s);
                pending.remove(i);
            } else {
                i++;
            }
        }
    }

    /** A hazard or stall at this time: patches seen up to it were not driven over cleanly. */
    synchronized void floorHazard(long atMs) {
        hazardMs = Math.max(hazardMs, atMs);
        for (int i = 0; i < pending.size(); ) {
            if (pending.get(i).frameMs <= atMs) {
                pending.remove(i);
            } else {
                i++;
            }
        }
    }

    synchronized int patches() {
        return patches.size();
    }

    synchronized int pending() {
        return pending.size();
    }

    // ---- scoring ----

    /** No usable image: the boxes, capped at unsure, and no confidence. */
    private static Profile blind(float[] boxes) {
        float[] bins = new float[BINS];
        for (int i = 0; i < BINS; i++) {
            bins[i] = Math.min(UNSURE, boxes[i]);
        }
        return new Profile(bins, 0f);
    }

    private static float[] boxOpenness(List<Detection> detections) {
        float[] open = new float[BINS];
        Arrays.fill(open, 1f);
        if (detections == null) {
            return open;
        }
        for (Detection d : detections) {
            if (d == null) {
                continue;
            }
            float penalty = d.y1 >= NEAR_BOX_BOTTOM ? 1f
                    : d.y1 <= HORIZON ? FAR_BOX_PENALTY
                    : FAR_BOX_PENALTY + (1f - FAR_BOX_PENALTY) * (d.y1 - HORIZON) / (NEAR_BOX_BOTTOM - HORIZON);
            for (int b = 0; b < BINS; b++) {
                float overlap = Math.min(d.x1, (b + 1f) / BINS) - Math.max(d.x0, (float) b / BINS);
                if (overlap >= MIN_BIN_OVERLAP / BINS) {
                    open[b] = Math.min(open[b], 1f - penalty);
                }
            }
        }
        return open;
    }

    private float[] judge(Frame whole, Frame lower, float[] boxes) {
        int first = 0;
        while (first < lower.height && lower.rowCentre(first) < HORIZON) {
            first++;
        }
        int rows = lower.height - first;
        int w = lower.width;
        float[] bins = new float[BINS];
        if (rows <= 0) {
            return blind(boxes).bins;
        }
        boolean[] floor = new boolean[rows * w];
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < w; x++) {
                floor[y * w + x] = isFloor(lower.at(x, first + y));
            }
        }
        float[] reference = referenceAboveHorizon(whole, w);
        float[] reachSum = new float[BINS];
        int[] columns = new int[BINS];
        for (int x = 0; x < w; x++) {
            int bin = Math.min(BINS - 1, x * BINS / w);
            columns[bin]++;
            reachSum[bin] += reach(lower, first, rows, floor, x, reference);
        }
        for (int b = 0; b < BINS; b++) {
            int x0 = (b * w + BINS - 1) / BINS;
            int x1 = Math.min(w, ((b + 1) * w + BINS - 1) / BINS);
            float colour = UNSURE;
            int free = 0;
            boolean floorBeyond = false;
            if (!patches.isEmpty() && x1 > x0) {
                boolean run = true;
                for (int y = rows - 1; y >= 0; y--) {
                    int hits = 0;
                    for (int x = x0; x < x1; x++) {
                        if (floor[y * w + x]) {
                            hits++;
                        }
                    }
                    boolean isFloor = hits >= FLOOR_ROW_SHARE * (x1 - x0);
                    run &= isFloor;
                    if (run) {
                        free++;
                    } else if (isFloor) {
                        floorBeyond = true;
                    }
                }
                colour = UNSURE + (1f - UNSURE) * free / rows;
            }
            float reach = columns[b] == 0 ? 0f : reachSum[b] / columns[b];
            if (!patches.isEmpty() && reach > STANDING_REACH && !floorBeyond) {
                // A surface comes well down from the horizon and no taught floor shows
                // between it and the floor run at the bottom (if any): it stands where
                // that run ends, even where its colour broke first (a chair's seat
                // above its base). Floor seen beyond a gap keeps the gap unsure (KTD9).
                reach = Math.max(reach, 1f - (float) free / rows);
            }
            float horizon = 1f - clamp01((reach - REACH_MARGIN) / (1f - REACH_MARGIN));
            bins[b] = clamp01(Math.min(boxes[b], Math.min(horizon, colour)));
        }
        return bins;
    }

    /**
     * Per lower-frame column, the mean colour of whole's rows just above the
     * horizon, packed r,g,b; NaN where that surface is floor (it crosses nothing).
     */
    private float[] referenceAboveHorizon(Frame whole, int w) {
        int from = -1;
        int to = -1;
        for (int y = 0; y < whole.height; y++) {
            float c = whole.rowCentre(y);
            if (c < HORIZON) {
                to = y;
                if (from < 0 && c >= HORIZON - REFERENCE_ROWS) {
                    from = y;
                }
            }
        }
        float[] ref = new float[w * 3];
        if (to < 0) {
            Arrays.fill(ref, Float.NaN);
            return ref;
        }
        if (from < 0) {
            from = to;
        }
        for (int x = 0; x < w; x++) {
            int wx = Math.min(whole.width - 1, (int) ((x + 0.5f) * whole.width / w));
            float r = 0f;
            float g = 0f;
            float b = 0f;
            for (int y = from; y <= to; y++) {
                int p = whole.at(wx, y);
                r += (p >> 16) & 0xff;
                g += (p >> 8) & 0xff;
                b += p & 0xff;
            }
            int n = to - from + 1;
            r /= n;
            g /= n;
            b /= n;
            boolean isFloor = matchesFloor(r, g, b);
            ref[x * 3] = isFloor ? Float.NaN : r;
            ref[x * 3 + 1] = g;
            ref[x * 3 + 2] = b;
        }
        return ref;
    }

    /**
     * How far (share of the lower frame) the surface above the horizon carries on
     * down this column: pixel to pixel, so the shading down a plain wall is
     * followed, but never drifting far from its colour at the horizon. It ends at
     * floor only across an edge (a wall's base, its shadow line): a dim wall can
     * shade smoothly into a floor-like grey without ever reaching the floor.
     */
    private static float reach(Frame lower, int first, int rows, boolean[] floor, int x, float[] ref) {
        float r = ref[x * 3];
        if (Float.isNaN(r)) {
            return 0f;
        }
        float g = ref[x * 3 + 1];
        float b = ref[x * 3 + 2];
        float pr = r;
        float pg = g;
        float pb = b;
        int last = -1;
        for (int y = 0; y < rows; y++) {
            int p = lower.at(x, first + y);
            float cr = (p >> 16) & 0xff;
            float cg = (p >> 8) & 0xff;
            float cb = p & 0xff;
            float step = apart(cr, cg, cb, pr, pg, pb);
            if (step > SAME_SURFACE || apart(cr, cg, cb, r, g, b) > SURFACE_DRIFT
                    || (floor[y * lower.width + x] && step > FLOOR_EDGE)) {
                break;
            }
            pr = cr;
            pg = cg;
            pb = cb;
            last = y;
        }
        if (last < 0) {
            return 0f;
        }
        return clamp01((lower.rowBottom(first + last) - HORIZON) / (1f - HORIZON));
    }

    private boolean isFloor(int p) {
        return matchesFloor((p >> 16) & 0xff, (p >> 8) & 0xff, p & 0xff);
    }

    private boolean matchesFloor(float r, float g, float b) {
        for (int i = 0; i < patches.size(); i++) {
            Patch f = patches.get(i);
            if (apart(r, g, b, f.r, f.g, f.b) <= f.tolerance()) {
                return true;
            }
        }
        return false;
    }

    // ---- teaching ----

    /** The bottom-centre patch as a pending sample, if it is one bright, even surface. */
    private void sample(Frame lower, long frameMs) {
        int x0 = (int) (SAMPLE_LEFT * lower.width);
        int x1 = Math.max(x0 + 1, (int) (SAMPLE_RIGHT * lower.width));
        double r = 0;
        double g = 0;
        double b = 0;
        int n = 0;
        for (int y = 0; y < lower.height; y++) {
            if (lower.rowCentre(y) < SAMPLE_TOP) {
                continue;
            }
            for (int x = x0; x < x1 && x < lower.width; x++) {
                int p = lower.at(x, y);
                r += (p >> 16) & 0xff;
                g += (p >> 8) & 0xff;
                b += p & 0xff;
                n++;
            }
        }
        if (n == 0) {
            return;
        }
        float mr = (float) (r / n);
        float mg = (float) (g / n);
        float mb = (float) (b / n);
        if (luma(mr, mg, mb) < SAMPLE_LUMA) {
            return;
        }
        double var = 0;
        for (int y = 0; y < lower.height; y++) {
            if (lower.rowCentre(y) < SAMPLE_TOP) {
                continue;
            }
            for (int x = x0; x < x1 && x < lower.width; x++) {
                float d = distance(lower.at(x, y), mr, mg, mb);
                var += d * d;
            }
        }
        float spread = (float) Math.sqrt(var / n);
        if (spread > MAX_SAMPLE_SPREAD) {
            return;
        }
        // The patch's spread widens its tolerance, which is in apart()'s scaled units.
        pending.add(new Sample(frameMs, mr, mg, mb, spread * dimScale(luma(mr, mg, mb))));
        while (pending.size() > MAX_PENDING) {
            pending.remove(0);
        }
    }

    private void learn(Sample s) {
        Patch near = null;
        float best = Float.MAX_VALUE;
        for (Patch p : patches) {
            float d = apart(s.r, s.g, s.b, p.r, p.g, p.b);
            if (d <= p.tolerance() && d < best) {
                best = d;
                near = p;
            }
        }
        if (near == null) {
            near = new Patch();
            near.r = s.r;
            near.g = s.g;
            near.b = s.b;
            near.spread = s.spread;
            near.taughtSeq = ++seq;
            patches.add(near);
            if (patches.size() > MAX_PATCHES) {
                Patch stale = patches.get(0);
                for (Patch p : patches) {
                    if (p.taughtSeq < stale.taughtSeq) {
                        stale = p;
                    }
                }
                patches.remove(stale);
            }
        } else {
            float k = 1f / (Math.min(near.count, MERGE_WEIGHT_CAP) + 1f);
            near.r += (s.r - near.r) * k;
            near.g += (s.g - near.g) * k;
            near.b += (s.b - near.b) * k;
            near.spread += (s.spread - near.spread) * k;
        }
        near.count++;
        near.taughtSeq = ++seq;
    }

    // ---- colour ----

    private static float meanLuma(Frame f) {
        double sum = 0;
        int n = f.width * f.height;
        for (int i = 0; i < n; i++) {
            int p = f.rgb[i];
            sum += luma((p >> 16) & 0xff, (p >> 8) & 0xff, p & 0xff);
        }
        return (float) (sum / n);
    }

    private static float luma(float r, float g, float b) {
        return 0.299f * r + 0.587f * g + 0.114f * b;
    }

    /** Colour distance, scaled up in dim light where the same surface's pixels sit closer together. */
    private static float apart(float r1, float g1, float b1, float r2, float g2, float b2) {
        float dr = r1 - r2;
        float dg = g1 - g2;
        float db = b1 - b2;
        float level = (luma(r1, g1, b1) + luma(r2, g2, b2)) / 2f;
        return (float) Math.sqrt(dr * dr + dg * dg + db * db) * dimScale(level);
    }

    private static float dimScale(float luma) {
        return LIGHT_REFERENCE / Math.max(MIN_LIGHT, Math.min(LIGHT_REFERENCE, luma));
    }

    private static float distance(int p, float r, float g, float b) {
        float dr = ((p >> 16) & 0xff) - r;
        float dg = ((p >> 8) & 0xff) - g;
        float db = (p & 0xff) - b;
        return (float) Math.sqrt(dr * dr + dg * dg + db * db);
    }

    private static float clamp01(float v) {
        return Float.isNaN(v) ? 0f : Math.max(0f, Math.min(1f, v));
    }
}
