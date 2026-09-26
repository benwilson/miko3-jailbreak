package com.miko3.mode.explore;

/**
 * Explore's self-adjusting camera brightness (explore nav plan U9, KTD10, R17).
 * Plain Java: ExploreCamera feeds each scored frame's mean luma (0..255) and the
 * brain's "moving" flag, and applies what comes back as manual exposure
 * (CONTROL_AE_MODE_OFF, the path remote-control proved safe on this camera).
 * The frame-rate range is never touched: a variable one trips this HAL's
 * "pixel rate should not be zero" bug.
 *
 * The controller steers exposure time x sensitivity toward TARGET_LUMA with
 * small multiplicative steps (at most MAX_STEP a frame). Brighter means longer
 * exposure first, up to the cap, then more sensitivity; darker means less
 * sensitivity first, then shorter exposure, so the picture stays as clean as
 * the light allows. Hysteresis keeps it still: it starts adjusting only once
 * the luma leaves the outer band and stops once it is back in the inner band,
 * and each step is at most the correction that would land on the target, so
 * it never overshoots into the opposite side. At a limit (a covered lens) it
 * simply reports no change.
 *
 * The exposure cap is short while he drives (motion blur) and longer while he
 * stands still. Frames captured soon after a change may still carry the old
 * settings, so they are ignored for a few frame times.
 *
 * Thread-safe: the camera, detect and brain threads all call in.
 */
final class Brightness {
    /** Mid-grey-ish: enough light for the detector without clipping a bright window. */
    static final double TARGET_LUMA = 110;
    /** Start adjusting outside [OUTER_LOW, OUTER_HIGH] (about +-35% of the target). */
    static final double OUTER_LOW = 80;
    static final double OUTER_HIGH = 150;
    /** Stop adjusting inside [INNER_LOW, INNER_HIGH] (about +-15% of the target). */
    static final double INNER_LOW = 95;
    static final double INNER_HIGH = 128;
    /** The most exposure x sensitivity may change in one step, either way. */
    static final double MAX_STEP = 1.5;

    /** A 15 fps frame: the ceiling the old fixed-range auto exposure had, short enough for driving. */
    static final long MOVING_CAP_NS = 66_000_000L;
    /** Standing still he can take a slower frame (5 fps); the detector runs about 1 fps anyway. */
    static final long STILL_CAP_NS = 200_000_000L;
    /** Above this, sensor noise costs the detector more than the extra light gives. */
    static final int ISO_CEILING = 3200;
    /** Never faster than 15 fps: more frames only cost CPU, the detector keeps ~1 a second. */
    static final long MIN_FRAME_DURATION_NS = 66_666_667L;

    /** Used when the camera reports no (or a nonsense) exposure range. */
    static final long DEFAULT_EXPOSURE_LO_NS = 100_000L;
    static final long DEFAULT_EXPOSURE_HI_NS = 66_000_000L;
    /** Used when the camera reports no (or a nonsense) sensitivity range. */
    static final int DEFAULT_ISO_LO = 100;
    static final int DEFAULT_ISO_HI = 800;

    /** The first open's settings: the old auto-exposure ceiling, at a moderate gain. */
    static final long DEFAULT_START_EXPOSURE_NS = 66_000_000L;
    static final int DEFAULT_START_ISO = 800;

    /** Frames this long after a change (plus SETTLE_FRAMES frame times) are trusted. */
    private static final long SETTLE_MS = 300;
    private static final int SETTLE_FRAMES = 3;

    /** One manual-exposure request. Only numbers: safe to log. */
    static final class Settings {
        final long exposureNs;
        final int sensitivity;
        final long frameDurationNs;

        Settings(long exposureNs, int sensitivity, long frameDurationNs) {
            this.exposureNs = exposureNs;
            this.sensitivity = sensitivity;
            this.frameDurationNs = frameDurationNs;
        }

        /** Exposure in whole milliseconds, for the log. */
        long exposureMs() {
            return Math.round(exposureNs / 1_000_000.0);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Settings)) {
                return false;
            }
            Settings s = (Settings) o;
            return s.exposureNs == exposureNs && s.sensitivity == sensitivity && s.frameDurationNs == frameDurationNs;
        }

        @Override
        public int hashCode() {
            return (int) (exposureNs * 31 + sensitivity * 17 + frameDurationNs);
        }

        @Override
        public String toString() {
            return "exposure " + exposureNs + " ns, ISO " + sensitivity + ", frame " + frameDurationNs + " ns";
        }
    }

    private long expLo = DEFAULT_EXPOSURE_LO_NS;
    private long expHi = DEFAULT_EXPOSURE_HI_NS;
    private int isoLo = DEFAULT_ISO_LO;
    private int isoHi = DEFAULT_ISO_HI;
    private long maxFrameNs = Long.MAX_VALUE;
    /** Until the brain says otherwise, assume he drives: the short cap never blurs. */
    private boolean moving = true;

    private Settings current = make(DEFAULT_START_EXPOSURE_NS, DEFAULT_START_ISO);
    /** The settings of the newest frame that was read after settling; null before any. */
    private Settings lastWorked;
    private boolean settling;
    private long changedAtMs = Long.MIN_VALUE / 2;

    /**
     * The camera's ranges; null, non-positive or inverted values count as
     * missing and fall back to the defaults. maxFrameDurationNs bounds the
     * exposure too. The current settings are clamped into them.
     */
    synchronized void setRanges(Long exposureLoNs, Long exposureHiNs, Integer isoLoIn, Integer isoHiIn,
            Long maxFrameDurationNs) {
        boolean expOk = exposureLoNs != null && exposureHiNs != null && exposureLoNs > 0
                && exposureHiNs >= exposureLoNs;
        expLo = expOk ? exposureLoNs : DEFAULT_EXPOSURE_LO_NS;
        expHi = expOk ? exposureHiNs : DEFAULT_EXPOSURE_HI_NS;
        boolean isoOk = isoLoIn != null && isoHiIn != null && isoLoIn > 0 && isoHiIn >= isoLoIn;
        isoLo = isoOk ? isoLoIn : DEFAULT_ISO_LO;
        isoHi = isoOk ? Math.max(isoLo, Math.min(isoHiIn, ISO_CEILING)) : DEFAULT_ISO_HI;
        maxFrameNs = maxFrameDurationNs != null && maxFrameDurationNs > 0 ? maxFrameDurationNs : Long.MAX_VALUE;
        expHi = Math.max(expLo, Math.min(expHi, maxFrameNs));
        current = make(current.exposureNs, current.sensitivity);
        if (lastWorked != null) {
            lastWorked = make(lastWorked.exposureNs, lastWorked.sensitivity);
        }
    }

    /** Package-private for the harness: set the current settings, as if reached earlier. */
    synchronized void seed(long exposureNs, int sensitivity) {
        current = make(exposureNs, sensitivity);
        lastWorked = null;
    }

    /** The longest exposure allowed right now. */
    synchronized long exposureCapNs() {
        return cap();
    }

    synchronized Settings current() {
        return current;
    }

    /**
     * A camera open: the last settings a frame was read with this session (or the
     * defaults), within the current ranges and cap.
     */
    synchronized Settings start(long nowMs) {
        Settings from = lastWorked != null ? lastWorked : current;
        current = make(from.exposureNs, from.sensitivity);
        settling = false;
        changedAtMs = nowMs;
        return current;
    }

    /**
     * Whether he is driving. Going still only raises the cap; going moving pulls a
     * longer exposure down to the moving cap at once, with sensitivity making up
     * the difference as far as it can. The new settings, or null when unchanged.
     */
    synchronized Settings setMoving(long nowMs, boolean isMoving) {
        moving = isMoving;
        long cap = cap();
        if (current.exposureNs <= cap) {
            return null;
        }
        double ratio = current.exposureNs / (double) cap;
        return change(make(cap, (int) Math.min(isoHi, Math.round(current.sensitivity * ratio))), nowMs);
    }

    /**
     * One scored frame, captured at frameMs, with its mean luma. The new settings,
     * or null for no change (settling, inside the band, at a limit, or bad luma).
     */
    synchronized Settings onFrame(long frameMs, long nowMs, double meanLuma) {
        if (Double.isNaN(meanLuma) || Double.isInfinite(meanLuma) || meanLuma < 0) {
            return null;
        }
        if (frameMs < changedAtMs + SETTLE_MS + SETTLE_FRAMES * current.frameDurationNs / 1_000_000L) {
            return null;
        }
        lastWorked = current;
        double luma = Math.max(1, Math.min(255, meanLuma));
        if (!settling) {
            if (luma >= OUTER_LOW && luma <= OUTER_HIGH) {
                return null;
            }
            settling = true;
        }
        if (luma >= INNER_LOW && luma <= INNER_HIGH) {
            settling = false;
            return null;
        }
        double f = Math.max(1 / MAX_STEP, Math.min(MAX_STEP, TARGET_LUMA / luma));
        return change(f > 1 ? raise(f) : lower(f), nowMs);
    }

    /** Mean luma (BT.601 weights) of the first n ARGB pixels; NaN when there are none. */
    static double meanLuma(int[] argb, int n) {
        if (argb == null || n <= 0) {
            return Double.NaN;
        }
        int count = Math.min(n, argb.length);
        if (count == 0) {
            return Double.NaN;
        }
        long sum = 0;
        for (int i = 0; i < count; i++) {
            int p = argb[i];
            sum += 77 * ((p >> 16) & 0xff) + 150 * ((p >> 8) & 0xff) + 29 * (p & 0xff);
        }
        return sum / 256.0 / count;
    }

    // ---- internals (caller holds the lock) ----

    private Settings change(Settings next, long nowMs) {
        if (next.equals(current)) {
            return null;
        }
        current = next;
        changedAtMs = nowMs;
        return next;
    }

    /** Longer exposure up to the cap, then whatever is left as sensitivity. */
    private Settings raise(double f) {
        long cap = cap();
        long exp = current.exposureNs;
        double left = f;
        if (exp < cap) {
            long next = Math.min(cap, Math.round(exp * f));
            left = f * exp / next;
            exp = next;
        }
        int iso = current.sensitivity;
        // Rounding leaves a sliver below the cap; only a capped exposure hands on.
        if (exp == cap && left > 1.0001) {
            iso = (int) Math.min(isoHi, Math.round(iso * left));
        }
        return make(exp, iso);
    }

    /** Less sensitivity down to its floor, then whatever is left as shorter exposure. */
    private Settings lower(double f) {
        int iso = current.sensitivity;
        int next = (int) Math.max(isoLo, Math.round(iso * f));
        double left = f * iso / next;
        long exp = current.exposureNs;
        if (next == isoLo && left < 0.9999) {
            exp = Math.max(expLo, Math.round(exp * left));
        }
        return make(exp, next);
    }

    private long cap() {
        return Math.max(expLo, Math.min(expHi, moving ? MOVING_CAP_NS : STILL_CAP_NS));
    }

    /** Clamped into the ranges and cap, with the frame duration that fits it. */
    private Settings make(long exposureNs, int sensitivity) {
        long exp = Math.max(expLo, Math.min(cap(), exposureNs));
        int iso = Math.max(isoLo, Math.min(isoHi, sensitivity));
        long frame = Math.min(maxFrameNs, Math.max(exp, MIN_FRAME_DURATION_NS));
        return new Settings(exp, iso, Math.max(frame, exp));
    }
}
