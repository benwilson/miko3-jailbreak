package com.miko3.mode.explore;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Where he has been this session, roughly (explore nav plan U10, R18; KTD1): a
 * coarse grid of visited cells so the steer can prefer ground he has not covered.
 *
 * Position is dead-reckoned: each reading's signed encoder change (the mean of the
 * two wheels, so a turn in place moves him nowhere and a back-off moves him
 * backwards) at coverageCountsPerMetre, along the gyro heading. Nothing moves him
 * while the heading is not usable; a push by hand or wheel slip just drifts the
 * grid, which fades anyway. Every cell he passes through is stamped with the time;
 * a stamp counts less as it ages and is gone after coverageFadeMs.
 *
 * Only this session: nothing is saved, and clear() at shutdown forgets it all (the
 * plan's no-map-between-sessions boundary). Heading 0..360, positive left, as in
 * Heading; x along heading 0, y along heading 90.
 *
 * Plain Java (no Android or shared-driver imports), so it runs on the host JVM.
 * Not thread-safe; the brain calls it from its one thread.
 */
final class Coverage {
    /** Wheel changes beyond this in one reading are a counter reset or a glitch, not motion. */
    private static final long MAX_STEP_COUNTS = 5000;

    private final ExploreTuning tuning;
    /** Cell key -> when he was last in it. */
    private final Map<Long, Long> visited = new HashMap<Long, Long>();
    private double x;
    private double y;
    private boolean tracking;
    private SensorReading lastWheels;
    private long lastMs;

    Coverage(ExploreTuning tuning) {
        this.tuning = tuning;
    }

    /** One reading, with the heading tracker's degrees and whether they are usable now. */
    void offer(SensorReading r, double headingDeg, boolean headingUsable) {
        if (!r.hasWheels()) {
            return;
        }
        SensorReading prev = lastWheels;
        lastWheels = r;
        if (!headingUsable) {
            return;
        }
        lastMs = r.timestampMs;
        if (!tracking) {
            tracking = true;
            mark(r.timestampMs);
            return;
        }
        if (prev == null) {
            return;
        }
        long dl = r.wheelLeft - prev.wheelLeft;
        long dr = r.wheelRight - prev.wheelRight;
        if (Math.abs(dl) > MAX_STEP_COUNTS || Math.abs(dr) > MAX_STEP_COUNTS) {
            return;
        }
        double metres = (dl + dr) / 2.0 / tuning.coverageCountsPerMetre;
        if (metres == 0) {
            mark(r.timestampMs);
            return;
        }
        double rad = Math.toRadians(headingDeg);
        double step = tuning.coverageCellM / 2;
        int n = Math.max(1, (int) Math.ceil(Math.abs(metres) / step));
        double sx = metres * Math.cos(rad) / n;
        double sy = metres * Math.sin(rad) / n;
        for (int i = 0; i < n; i++) {
            x += sx;
            y += sy;
            mark(r.timestampMs);
        }
    }

    /** A position is being kept (the heading has been usable with wheel counts at least once). */
    boolean tracking() {
        return tracking;
    }

    /**
     * How new the next coverageLookaheadM along headingDeg is at nowMs: 1 for no
     * recent visits, 0 for every cell just visited. The cell he stands in does not
     * count. NaN while not tracking.
     */
    double novelty(double headingDeg, long nowMs) {
        if (!tracking) {
            return Double.NaN;
        }
        double rad = Math.toRadians(headingDeg);
        double cx = Math.cos(rad);
        double cy = Math.sin(rad);
        long here = key(x, y);
        Set<Long> seen = new HashSet<Long>();
        double sum = 0;
        double step = tuning.coverageCellM / 2;
        for (double d = step; d <= tuning.coverageLookaheadM + 1e-9; d += step) {
            long k = key(x + cx * d, y + cy * d);
            if (k == here || !seen.add(k)) {
                continue;
            }
            sum += visitedness(k, nowMs);
        }
        return seen.isEmpty() ? 1.0 : 1.0 - sum / seen.size();
    }

    /** Cells visited within coverageFadeMs of nowMs (older ones are dropped). */
    int cells(long nowMs) {
        for (Iterator<Long> it = visited.values().iterator(); it.hasNext(); ) {
            if (nowMs - it.next() >= tuning.coverageFadeMs) {
                it.remove();
            }
        }
        return visited.size();
    }

    /** Cells visited, as of the last reading. */
    int cells() {
        return cells(lastMs);
    }

    /** Forget everything (Explore stopping). */
    void clear() {
        visited.clear();
        x = 0;
        y = 0;
        tracking = false;
        lastWheels = null;
        lastMs = 0;
    }

    /** The dead-reckoned position, in metres from where tracking began. */
    double x() {
        return x;
    }

    double y() {
        return y;
    }

    /** 1 for a cell just visited, falling to 0 at coverageFadeMs. */
    private double visitedness(long k, long nowMs) {
        Long t = visited.get(k);
        if (t == null) {
            return 0;
        }
        double age = Math.max(0, nowMs - t);
        return Math.max(0, 1 - age / tuning.coverageFadeMs);
    }

    private void mark(long ms) {
        visited.put(key(x, y), ms);
    }

    private long key(double px, double py) {
        long cx = (long) Math.floor(px / tuning.coverageCellM);
        long cy = (long) Math.floor(py / tuning.coverageCellM);
        return (cx << 32) ^ (cy & 0xffffffffL);
    }
}
