package com.miko3.mode.explore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * How open the floor ahead looks in one camera frame (explore nav plan U3,
 * KTD3): BINS column bins across the frame, each 0 (blocked) to 1 (open), plus
 * a confidence. Cheap signals, never a depth model:
 * <ol>
 * <li>the detector's boxes: a box whose bottom edge reaches low in the frame is
 * near and blocks its columns; one ending above the horizon only dents them;</li>
 * <li>the free-space boundary: in narrow column strips, from the frame bottom
 * upward, where the surface at the bottom ends (the first clear colour edge).
 * Floor never shows above the horizon and everything standing on the floor
 * taller than the camera crosses it, so a bottom surface that ends below the
 * horizon is floor, and the higher its end the farther the free floor runs;
 * one that carries on past the horizon (or fills the band) is a wall, a desk
 * side, a chair leg: blocked. A bin is its most blocked strip, so a chair leg
 * a few pixels wide blocks its bin;</li>
 * <li>a floor-colour model: a bottom surface whose colour was never taught
 * reads unsure (UNSURE) at best, not blocked (KTD9).</li>
 * </ol>
 * Robot 2026-10-01 (scripts/eval-explore-openness.py, 46 labelled frames): the
 * old colour-run scorer was taught WALLS. A plain wall fills the bottom-centre
 * sample evenly, so it passed the evenness test, while real carpet is grainy at
 * this camera's ISO and was refused; a wall-taught model then read the hallway
 * carpet as a standing surface (every bin 0.00) and the white wall as open.
 * Geometry decides now; colour only caps.
 *
 * Teaching (R2, "floor sensor and camera together"): a frame captured while the
 * floor was clear and the wheels free leaves its bottom-centre patch pending,
 * but only when the boundary says that patch is floor (its surface ends below
 * the horizon, above the patch). It is taught once the brain reports he drove
 * over it (floorDrivenOver), and dropped if a hazard or stall comes first
 * (floorHazard); a late frame from before that hazard is refused. Low light
 * gives low confidence, and the brain then leans on the boxes and the floor
 * sensor.
 *
 * Plain Java (no Android or shared-driver imports) so it runs on the host JVM.
 * It never logs and keeps no pixels, only colour means (R15). Methods are
 * synchronized: ExploreCamera scores on its detect thread and forwards the
 * brain's teach and hazard calls. This camera sits ~15 cm up, tilted up ~16
 * degrees (see HORIZON): the nearest floor it sees, the frame's bottom row, is
 * already about 1 m ahead.
 */
final class Openness {
    static final int BINS = 16;
    /**
     * Where the horizon falls, as a fraction of frame height (top 0). The camera
     * tilts UP, so its horizon is well below the frame's middle, not above it:
     * on the robot's frames the far end of a hallway's floor ends at 0.85-0.86
     * and door jambs lean in toward the top (vertical lines meeting above the
     * frame), both putting it near 0.82 (a ~16 degree tilt at 526 px focal).
     * Floor never shows above it; everything standing on the floor crosses it.
     */
    static final float HORIZON = 0.82f;
    /**
     * The sharper band ExploreCamera passes starts this high (fraction of frame
     * height), a little above HORIZON, so a surface can be followed past it.
     */
    static final float BAND_TOP = 0.7f;
    /** Floor colours kept; the least recently taught is forgotten first. */
    static final int MAX_PATCHES = 4;
    /** Samples awaiting the brain's word; the oldest is dropped first. */
    static final int MAX_PENDING = 8;
    /** What a colour the model never saw scores at best: unsure, not blocked. */
    static final float UNSURE = 0.5f;

    /** A box whose bottom edge is at or below this (the nearer half of the ground below the horizon) blocks its columns. */
    private static final float NEAR_BOX_BOTTOM = 0.9f;
    /** What a box ending at or above the horizon (far away) takes off its columns. */
    private static final float FAR_BOX_PENALTY = 0.15f;
    /** A box counts for a bin when it covers at least this share of the bin's width. */
    private static final float MIN_BIN_OVERLAP = 0.25f;
    /** Strip width, as a share of the frame width: 5 px of the 160 px band, two strips a bin. */
    private static final float STRIP = 1f / 32f;
    /**
     * The boundary: two rows apart, a colour step over EDGE (RGB distance scaled
     * by EDGE_LIGHT / (luma + EDGE_DARK), so a step means as much in a dim frame
     * as in a bright one without blowing up sensor noise in the dark) ends the
     * surface at the bottom. A slow shading of the carpet toward the light never
     * steps that far between two rows.
     */
    private static final int EDGE_GAP = 2;
    private static final float EDGE = 25f;
    private static final float EDGE_LIGHT = 64f;
    private static final float EDGE_DARK = 20f;
    /** A boundary this little above the horizon (share of frame height) is still the floor's far end. */
    private static final float HORIZON_SLACK = 0.01f;
    /**
     * The camera's geometry, to turn a boundary row into a distance along the
     * floor: focal length as a share of frame height (526 px of 480), and its
     * height above the floor. Its tilt follows from HORIZON. The bottom row is
     * then ~1.0 m ahead, 0.96 ~1.3 m, 0.9 ~2.3 m, 0.86 ~4 m.
     */
    private static final float FOCAL = 526f / 480f;
    private static final float CAMERA_HEIGHT_M = 0.15f;
    /**
     * Free floor of d metres scores (d - NEAR_M) / (d - NEAR_M + SOFT_M): 0 at
     * NEAR_M (about the nearest floor the camera sees), the steer's blocked line
     * (0.35) at ~1.25 m, its fully open line (0.7) at ~1.7 m, 0.9 at ~3.4 m. Tuned on the labelled frames
     * (scripts/eval-explore-openness.py): something standing within ~1.2 m
     * (reaching the bottom ~3% of the frame) is blocked.
     */
    private static final float NEAR_M = 1.1f;
    private static final float SOFT_M = 0.25f;
    /** The bottom rows (share of frame height) whose colour is the strip's floor colour. */
    private static final float FLOOR_ROWS = 0.03f;
    /** A floor patch matches within this distance plus twice its spread, up to MAX_FLOOR_TOLERANCE. */
    private static final float FLOOR_TOLERANCE = 30f;
    private static final float MAX_FLOOR_TOLERANCE = 60f;
    /**
     * Shading and sensor noise scale with brightness, so below this luma floor
     * colour distances are scaled up by LIGHT_REFERENCE / luma (this camera's
     * frames are dim: luma 20-35 in office light), never by more than
     * LIGHT_REFERENCE / MIN_LIGHT.
     */
    private static final float LIGHT_REFERENCE = 64f;
    private static final float MIN_LIGHT = 16f;
    /** The teaching sample: the bottom rows (the nearer ground), centre columns (what he drives onto next). */
    private static final float SAMPLE_TOP = 0.9f;
    private static final float SAMPLE_LEFT = 0.3f;
    private static final float SAMPLE_RIGHT = 0.7f;
    /** A centre strip is sampled only when its floor runs at least this high above the frame bottom (share of height). */
    private static final float SAMPLE_FLOOR = 0.04f;
    /**
     * A sample more varied than this is not one floor (a toy, a seam, an edge).
     * Measured over strip-row means (a carpet's grain at ISO 3200 averaged out),
     * and growing with brightness, so the limit is the larger of a fixed floor
     * and a share of the sample's luma.
     */
    private static final float MAX_SAMPLE_SPREAD = 18f;
    private static final float MAX_SAMPLE_SPREAD_SHARE = 0.45f;
    /**
     * Mean frame luma below DARK: no confidence; above DIM: full. This camera's
     * office-light frames average luma ~30, so DIM sits there, not at a bright
     * room's 60. A sample needs SAMPLE_LUMA: darker than that is sensor noise.
     */
    private static final float DARK_LUMA = 8f;
    private static final float DIM_LUMA = 30f;
    private static final float SAMPLE_LUMA = 12f;
    /**
     * The boundary needs no taught floor, so an untaught profile is trusted
     * (over the steer's 0.5), just less than one whose floor colour is known.
     */
    private static final float TAUGHT_CONFIDENCE = 0.9f;
    private static final float UNTAUGHT_CONFIDENCE = 0.6f;
    /** Merging stops weighting history beyond this many samples, so a patch follows the light. */
    private static final int MERGE_WEIGHT_CAP = 20;
    /**
     * Relative margin around a squared threshold within which apartAtMost() falls
     * back to apart()'s exact float arithmetic (float rounding is ~1e-7 of it).
     */
    private static final double SQUARED_GUARD = 1e-5;

    /**
     * Part of a frame, full width: 0xRRGGBB pixels row-major, covering rows
     * top..bottom (fractions). Its rows start at rgb's row firstRow, so a band
     * can be a view into a whole frame's pixels.
     */
    static final class Frame {
        final int[] rgb;
        final int width;
        final int height;
        final float top;
        final float bottom;
        final int firstRow;

        Frame(int[] rgb, int width, int height, float top, float bottom) {
            this(rgb, width, height, top, bottom, 0);
        }

        Frame(int[] rgb, int width, int height, float top, float bottom, int firstRow) {
            this.rgb = rgb;
            this.width = width;
            this.height = height;
            this.top = top;
            this.bottom = bottom;
            this.firstRow = firstRow;
        }

        boolean usable() {
            return rgb != null && width > 0 && height > 0 && firstRow >= 0
                    && (long) width * ((long) firstRow + height) <= rgb.length
                    && top >= 0f && bottom <= 1f && top < bottom;
        }

        float rowCentre(int row) {
            return top + (row + 0.5f) * (bottom - top) / height;
        }

        float rowBottom(int row) {
            return top + (row + 1f) * (bottom - top) / height;
        }

        int at(int x, int y) {
            return rgb[(firstRow + y) * width + x];
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
     * Scoring buffers, kept between frames (score() is synchronized); resized when
     * the strip count changes. Per strip: where its bottom surface ends (fraction
     * of frame height; NaN: it carries on past the horizon or fills the band),
     * and that surface's colour at the bottom, packed r,g,b.
     */
    private float[] boundary = new float[0];
    private float[] bottomColour = new float[0];
    /** One strip's row colours, packed r,g,b, bottom row last. */
    private float[] rows = new float[0];

    /**
     * Scores one frame. whole covers the full frame at low resolution; band, when
     * given, the rows from BAND_TOP down at a higher one (else whole serves).
     * teachable: captured while the floor was clear and the wheels free, so its
     * bottom patch may become a pending sample. Never throws.
     */
    synchronized Profile score(Frame whole, Frame band, List<Detection> detections, long frameMs, boolean teachable) {
        float[] boxes = null;
        try {
            boxes = boxOpenness(detections);
            if (whole == null || !whole.usable()) {
                return blind(boxes);
            }
            Frame lower = band != null && band.usable() ? band : whole;
            int strips = boundaries(lower);
            if (teachable && frameMs > hazardMs) {
                sample(lower, strips, frameMs);
            }
            float light = clamp01((meanLuma(whole) - DARK_LUMA) / (DIM_LUMA - DARK_LUMA));
            float[] bins = judge(lower, strips, boxes);
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

    /** No usable image: the boxes (null: none), capped at unsure, and no confidence. */
    private static Profile blind(float[] boxes) {
        float[] bins = new float[BINS];
        for (int i = 0; i < BINS; i++) {
            bins[i] = boxes == null ? UNSURE : Math.min(UNSURE, boxes[i]);
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

    /**
     * Each bin: its most blocked strip's openness from the free floor below its
     * boundary, capped at UNSURE where the strip's floor colour was never taught,
     * and never above the boxes.
     */
    private float[] judge(Frame lower, int strips, float[] boxes) {
        float[] bins = new float[BINS];
        boolean[] seen = new boolean[BINS];
        Arrays.fill(bins, 1f);
        for (int i = 0; i < strips; i++) {
            float open = stripOpen(i);
            int b = Math.min(BINS - 1, (int) ((i + 0.5f) * BINS / strips));
            bins[b] = Math.min(bins[b], open);
            seen[b] = true;
        }
        for (int b = 0; b < BINS; b++) {
            if (!seen[b]) {
                // Fewer strips than bins (a narrow frame): the strip under its centre.
                bins[b] = stripOpen(Math.min(strips - 1, (int) ((b + 0.5f) * strips / BINS)));
            }
            bins[b] = clamp01(Math.min(boxes[b], bins[b]));
        }
        return bins;
    }

    /** One strip's openness: 0 standing, else its free floor, capped at UNSURE for an untaught colour. */
    private float stripOpen(int i) {
        float end = boundary[i];
        if (Float.isNaN(end)) {
            return 0f;
        }
        float open = freeOpen(end);
        if (open > UNSURE && !taught(bottomColour[i * 3], bottomColour[i * 3 + 1], bottomColour[i * 3 + 2])) {
            open = UNSURE;
        }
        return open;
    }

    /** Openness of free floor running up to row end (fraction of frame height), from its distance. */
    static float freeOpen(float end) {
        double below = Math.atan((end - 0.5) / FOCAL) - Math.atan((HORIZON - 0.5) / FOCAL);
        if (below <= 1e-4) {
            return 1f;
        }
        double d = CAMERA_HEIGHT_M / Math.tan(below);
        return clamp01((float) ((d - NEAR_M) / (d - NEAR_M + SOFT_M)));
    }

    /**
     * Fills boundary and bottomColour for each strip of lower and returns the
     * strip count. A strip's rows are averaged across it, then walked from the
     * bottom up: the first step over EDGE between rows EDGE_GAP apart ends the
     * surface at the bottom. Ending above the horizon (by more than
     * HORIZON_SLACK), or never ending inside the band, it is standing: NaN.
     */
    private int boundaries(Frame lower) {
        int w = lower.width;
        int h = lower.height;
        int strips = Math.max(Math.min(w, BINS), w / Math.max(1, Math.round(w * STRIP)));
        if (boundary.length != strips) {
            boundary = new float[strips];
            bottomColour = new float[strips * 3];
        }
        if (rows.length != h * 3) {
            rows = new float[h * 3];
        }
        int floorRows = Math.max(1, Math.round(FLOOR_ROWS * h / (lower.bottom - lower.top)));
        for (int i = 0; i < strips; i++) {
            int x0 = i * w / strips;
            int x1 = Math.max(x0 + 1, (i + 1) * w / strips);
            for (int y = 0; y < h; y++) {
                float r = 0f;
                float g = 0f;
                float b = 0f;
                for (int x = x0; x < x1; x++) {
                    int p = lower.at(x, y);
                    r += (p >> 16) & 0xff;
                    g += (p >> 8) & 0xff;
                    b += p & 0xff;
                }
                int n = x1 - x0;
                rows[y * 3] = r / n;
                rows[y * 3 + 1] = g / n;
                rows[y * 3 + 2] = b / n;
            }
            float br = 0f;
            float bg = 0f;
            float bb = 0f;
            int from = Math.max(0, h - floorRows);
            for (int y = from; y < h; y++) {
                br += rows[y * 3];
                bg += rows[y * 3 + 1];
                bb += rows[y * 3 + 2];
            }
            bottomColour[i * 3] = br / (h - from);
            bottomColour[i * 3 + 1] = bg / (h - from);
            bottomColour[i * 3 + 2] = bb / (h - from);
            float end = Float.NaN;
            for (int y = h - 1 - EDGE_GAP; y >= 0; y--) {
                if (apart(y, y + EDGE_GAP, EDGE)) {
                    end = lower.rowBottom(y);
                    break;
                }
            }
            boundary[i] = Float.isNaN(end) || end < HORIZON - HORIZON_SLACK ? Float.NaN : end;
        }
        return strips;
    }

    /** Whether rows a and b of the current strip differ by more than limit, scaled for their light. */
    private boolean apart(int a, int b, float limit) {
        return stepApart(rows[a * 3], rows[a * 3 + 1], rows[a * 3 + 2], rows[b * 3], rows[b * 3 + 1], rows[b * 3 + 2],
                limit);
    }

    private static boolean stepApart(float r1, float g1, float b1, float r2, float g2, float b2, float limit) {
        float dr = r1 - r2;
        float dg = g1 - g2;
        float db = b1 - b2;
        float scale = EDGE_LIGHT / ((luma(r1, g1, b1) + luma(r2, g2, b2)) / 2f + EDGE_DARK);
        return (dr * dr + dg * dg + db * db) * scale * scale > limit * limit;
    }

    /** Whether a colour matches a taught floor patch (false when nothing is taught). */
    private boolean taught(float r, float g, float b) {
        for (Patch f : patches) {
            float dr = r - f.r;
            float dg = g - f.g;
            float db = b - f.b;
            if (apartAtMost(dr * dr + dg * dg + db * db, dimScale((luma(r, g, b) + luma(f.r, f.g, f.b)) / 2f),
                    f.tolerance())) {
                return true;
            }
        }
        return false;
    }

    // ---- teaching ----

    /**
     * The bottom-centre patch as a pending sample, if it is one bright, even
     * surface and the boundary says it is floor: at least half the centre strips
     * have a bottom surface that ends below the horizon, at least SAMPLE_FLOOR
     * above the frame bottom, and only those strips' rows under their boundary
     * (and under SAMPLE_TOP) are sampled. A wall he faces fills the patch just
     * as evenly and is never floor. Evenness is measured over strip-row means,
     * so a grainy carpet in a dim, noisy frame still counts.
     */
    private void sample(Frame lower, int strips, long frameMs) {
        int s0 = (int) (SAMPLE_LEFT * strips);
        int s1 = Math.min(strips, Math.max(s0 + 1, (int) Math.ceil(SAMPLE_RIGHT * strips)));
        int floor = 0;
        for (int i = s0; i < s1; i++) {
            if (!Float.isNaN(boundary[i]) && boundary[i] <= 1f - SAMPLE_FLOOR) {
                floor++;
            }
        }
        if (floor * 2 < s1 - s0) {
            return;
        }
        int w = lower.width;
        int cells = 0;
        double r = 0;
        double g = 0;
        double b = 0;
        float[] means = new float[(s1 - s0) * lower.height * 3];
        for (int i = s0; i < s1; i++) {
            if (Float.isNaN(boundary[i]) || boundary[i] > 1f - SAMPLE_FLOOR) {
                continue;
            }
            int x0 = i * w / strips;
            int x1 = Math.max(x0 + 1, (i + 1) * w / strips);
            for (int y = 0; y < lower.height; y++) {
                if (lower.rowCentre(y) < Math.max(SAMPLE_TOP, boundary[i])) {
                    continue;
                }
                float cr = 0f;
                float cg = 0f;
                float cb = 0f;
                for (int x = x0; x < x1; x++) {
                    int p = lower.at(x, y);
                    cr += (p >> 16) & 0xff;
                    cg += (p >> 8) & 0xff;
                    cb += p & 0xff;
                }
                int n = x1 - x0;
                means[cells * 3] = cr / n;
                means[cells * 3 + 1] = cg / n;
                means[cells * 3 + 2] = cb / n;
                r += cr / n;
                g += cg / n;
                b += cb / n;
                cells++;
            }
        }
        if (cells == 0) {
            return;
        }
        float mr = (float) (r / cells);
        float mg = (float) (g / cells);
        float mb = (float) (b / cells);
        if (luma(mr, mg, mb) < SAMPLE_LUMA) {
            return;
        }
        double var = 0;
        for (int c = 0; c < cells; c++) {
            float dr = means[c * 3] - mr;
            float dg = means[c * 3 + 1] - mg;
            float db = means[c * 3 + 2] - mb;
            var += dr * dr + dg * dg + db * db;
        }
        float spread = (float) Math.sqrt(var / cells);
        if (spread > Math.max(MAX_SAMPLE_SPREAD, MAX_SAMPLE_SPREAD_SHARE * luma(mr, mg, mb))) {
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
        int from = f.firstRow * f.width;
        for (int i = 0; i < n; i++) {
            int p = f.rgb[from + i];
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

    /**
     * Whether apart()'s value for a colour pair, (float) Math.sqrt(sq) * scale
     * (sq its float sum of squared differences, scale its dimScale), is at most
     * limit (limit > 0). Compared squared in double away from the limit; within
     * SQUARED_GUARD of it, apart()'s own float arithmetic decides, so every
     * decision matches apart()'s exactly.
     */
    private static boolean apartAtMost(float sq, float scale, float limit) {
        double v = (double) sq * scale * scale;
        double t = (double) limit * limit;
        if (v < t * (1 - SQUARED_GUARD)) {
            return true;
        }
        if (v > t * (1 + SQUARED_GUARD)) {
            return false;
        }
        return (float) Math.sqrt(sq) * scale <= limit;
    }

    private static float dimScale(float luma) {
        return LIGHT_REFERENCE / Math.max(MIN_LIGHT, Math.min(LIGHT_REFERENCE, luma));
    }

    private static float clamp01(float v) {
        return Float.isNaN(v) ? 0f : Math.max(0f, Math.min(1f, v));
    }
}
