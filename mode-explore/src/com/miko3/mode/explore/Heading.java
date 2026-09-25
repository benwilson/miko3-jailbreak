package com.miko3.mode.explore;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * His heading in degrees from the gyroscope, measured turns, and the log of the
 * legs he drove (explore nav plan U2; KTD1, KTD5, KTD6; R4, R11).
 *
 * Heading: each reading that carries the gyro adds (rate - bias) x the time since
 * the previous one, scaled by the owner's capture (ExploreCalibration.Gyro).
 * Degrees are 0..360, positive turning LEFT (the driver's convention); 0 is
 * wherever he was facing when the bias was first known, so only differences
 * mean anything. Nothing is integrated until the bias is known.
 *
 * Bias: re-estimated at every stop (Analog Devices RAQ 139). Once the wheels have
 * stood still and no motion has been commanded for headingSettleMs, each raw
 * rate is a bias sample; after biasSamplesMin of them the bias is this stop's
 * mean. Any wheel movement or command restarts the window, so a push or a coast
 * during what looked like a stop never gets averaged in.
 *
 * Measured turns: turn speed cannot be set (DirectMotorDriver.driveTurnSustained
 * uses only the sign), so the caller starts the sustained turn, asks turnReached()
 * on each reading, and stops. It is reached a learned overshoot (the coast after
 * stop) plus half a reading's step short of the amount. Once the coast has died
 * down the overshoot moves toward what was seen, by overshootGain.
 *
 * Legs: (direction of travel, encoder distance) for each straight stretch,
 * newest last; a back-off's direction is his heading plus 180. Distance is the
 * mean of both wheels' counts, none of it while stalled: legStalled() also drops
 * the counts crept during the stall window. droveOffCleanly() restarts the log
 * from the clean leg just finished, so it holds the way in since then (KTD6's
 * retrace walks it backwards). Legs are only logged while the heading is usable.
 *
 * Plain Java, no android.* or shared imports; brain thread only.
 */
final class Heading {
    /** Turn directions, as signs of the heading change. */
    static final int LEFT = 1;
    static final int RIGHT = -1;

    /** Wheel counts (both wheels) that still read as standing still: encoder jitter. */
    private static final long STILL_WHEEL_SLACK = 2;
    /** Below this the coast after a turn is over (deg/s). */
    private static final double COAST_DONE_DEG_S = 3.0;

    /** One straight stretch he drove. */
    static final class Leg {
        /** The way he was travelling, 0..360 (a back-off: facing + 180). */
        final double heading;
        /** Encoder distance: the mean of both wheels' counts. */
        final long counts;

        Leg(double heading, long counts) {
            this.heading = heading;
            this.counts = counts;
        }

        @Override
        public String toString() {
            return Math.round(heading) + "deg/" + counts;
        }
    }

    private final ExploreCalibration.Gyro gyro;
    private final ExploreTuning tuning;
    private final double degPerCountSecond;

    private double degrees;
    private double bias;
    private boolean biasKnown;
    private long lastGyroMs = -1;
    private double lastStepDeg;
    private SensorReading lastWheels;
    private long stillSince = -1;
    private double stillSum;
    private int stillCount;

    private boolean turning;
    private boolean coasting;
    private int turnDir;
    private double turnAmount;
    private double turned;
    private double turnedAtStop;
    private long stoppedAt;
    private double overshoot;
    private double[] result;
    /** When the current turn started (the brain's clock), and the learned turn rate (NaN: none yet). */
    private long turnStartMs = -1;
    private double rateDegS = Double.NaN;

    private final ArrayDeque<Leg> legs = new ArrayDeque<Leg>();
    private boolean legOpen;
    /** The motor stopped: the leg closes on the next reading, which carries its last counts. */
    private boolean legClosing;
    /** droveOffCleanly() came while the leg was closing: restart the log once it has. */
    private boolean cleanPending;
    private boolean legReverse;
    private boolean legStalled;
    /** This leg's moves: {time, counts, direction of travel}. */
    private final ArrayDeque<double[]> legMoves = new ArrayDeque<double[]>();

    /** gyro null: uncalibrated; usable() stays false and the brain times its turns. */
    Heading(ExploreCalibration.Gyro gyro, ExploreTuning tuning) {
        this.gyro = gyro;
        this.tuning = tuning;
        this.degPerCountSecond = gyro == null ? 0 : 360.0 / gyro.countSecondsPer360;
    }

    boolean calibrated() {
        return gyro != null;
    }

    /** Calibrated, the bias known, and a gyro reading within staleMs. */
    boolean usable(long nowMs) {
        return gyro != null && biasKnown && lastGyroMs >= 0 && nowMs - lastGyroMs <= tuning.staleMs;
    }

    /** 0..360, positive left. */
    double degrees() {
        return degrees;
    }

    /** The current bias estimate, in raw counts; NaN before the first. */
    double biasCounts() {
        return biasKnown ? bias : Double.NaN;
    }

    /** The learned coast after a stop, in degrees. */
    double overshootDeg() {
        return overshoot;
    }

    /** One reading; commanded is whether any motion is under way (the brain's own flag). */
    void offer(SensorReading r, boolean commanded) {
        boolean wheelsMoved = false;
        if (r.hasWheels()) {
            if (lastWheels != null) {
                long moved = Math.abs(r.wheelLeft - lastWheels.wheelLeft) + Math.abs(r.wheelRight - lastWheels.wheelRight);
                wheelsMoved = moved > STILL_WHEEL_SLACK;
                if (legOpen && !legStalled && moved > 0) {
                    legMoves.addLast(new double[]{r.timestampMs, moved / 2.0,
                            legReverse ? wrap(degrees + 180) : degrees});
                }
            }
            lastWheels = r;
        }
        if (legClosing) {
            endLeg();
        }
        if (gyro == null || !r.hasGyro) {
            return;
        }
        int raw = gyro.rate(r);
        long gap = lastGyroMs < 0 ? 0 : Math.max(0, Math.min(r.timestampMs - lastGyroMs, tuning.staleMs));
        lastGyroMs = r.timestampMs;
        if (biasKnown) {
            double rate = gyro.sign * (raw - bias) * degPerCountSecond;
            double step = rate * gap / 1000.0;
            degrees = wrap(degrees + step);
            lastStepDeg = Math.abs(step);
            if (turning || coasting) {
                turned += turnDir * step;
            }
            if (coasting && (Math.abs(rate) < COAST_DONE_DEG_S || r.timestampMs - stoppedAt >= tuning.overshootSettleMs)) {
                finishCoast();
            }
        }
        boolean still = !commanded && !wheelsMoved;
        if (!still) {
            stillSince = -1;
            return;
        }
        if (stillSince < 0) {
            stillSince = r.timestampMs;
            stillSum = 0;
            stillCount = 0;
        }
        if (r.timestampMs - stillSince >= tuning.headingSettleMs) {
            stillSum += raw;
            stillCount++;
            if (stillCount >= tuning.biasSamplesMin) {
                bias = stillSum / stillCount;
                biasKnown = true;
            }
        }
    }

    // ---- measured turns ----

    /** The shorter way from `from` to `to`, in (-180, 180]: positive is LEFT. */
    static double delta(double from, double to) {
        double d = wrap(to - from);
        return d > 180 ? d - 360 : d;
    }

    /** The shorter way to face target: LEFT or RIGHT. */
    int directionTo(double target) {
        return delta(degrees, target) >= 0 ? LEFT : RIGHT;
    }

    /** The motor has just started turning dir; it should turn amountDeg in all. */
    void startTurn(int dir, double amountDeg) {
        startTurn(dir, amountDeg, lastGyroMs);
    }

    /** As above, started at nowMs: its degrees over its time teach the turn rate (turnRateDegS). */
    void startTurn(int dir, double amountDeg, long nowMs) {
        finishCoast();
        turnStartMs = nowMs;
        turning = true;
        turnDir = dir;
        turnAmount = amountDeg;
        turned = 0;
    }

    /** Stop now and the coast should carry the turn to its amount. */
    boolean turnReached() {
        return turning && turned >= turnAmount - overshoot - lastStepDeg / 2;
    }

    /** Degrees turned so far, in the turn's own direction (the coast included). */
    double turned() {
        return turned;
    }

    /** The motor stopped: a turn in progress starts coasting; an open leg ends with the next reading. */
    void stopped(long nowMs) {
        if (turning) {
            learnRate(nowMs);
            turning = false;
            coasting = true;
            turnedAtStop = turned;
            stoppedAt = nowMs;
        }
        if (legOpen) {
            legClosing = true;
        }
    }

    /**
     * The turn rate recent measured turns made, degrees turned over the time they
     * took (spin-up included), each turn moving it halfway; NaN before the first
     * turn of at least RATE_MIN_DEG in RATE_MIN_MS. Carpet or a low battery slow him
     * (live 2026-09-25: ~40 deg/s), and the escape's step budgets follow it.
     */
    double turnRateDegS() {
        return rateDegS;
    }

    private static final double RATE_MIN_DEG = 20;
    private static final long RATE_MIN_MS = 300;

    private void learnRate(long nowMs) {
        long ms = nowMs - turnStartMs;
        if (turnStartMs < 0 || ms < RATE_MIN_MS || turned < RATE_MIN_DEG) {
            return;
        }
        double seen = turned * 1000.0 / ms;
        rateDegS = Double.isNaN(rateDegS) ? seen : rateDegS + 0.5 * (seen - rateDegS);
    }

    /** The last finished turn once, as {amount asked, turned, overshoot now}; else null. */
    double[] takeTurnResult() {
        double[] r = result;
        result = null;
        return r;
    }

    private void finishCoast() {
        if (!coasting) {
            return;
        }
        coasting = false;
        double seen = Math.max(0, turned - turnedAtStop);
        overshoot += tuning.overshootGain * (seen - overshoot);
        overshoot = Math.max(0, Math.min(tuning.overshootMaxDeg, overshoot));
        result = new double[]{turnAmount, turned, overshoot};
    }

    // ---- the leg log ----

    /** A straight leg starts: forward, or a back-off (reverse). Logged only while usable. */
    void startLeg(boolean reverse, long nowMs) {
        endLeg();
        if (!usable(nowMs)) {
            return;
        }
        legOpen = true;
        legReverse = reverse;
        legStalled = false;
        legMoves.clear();
    }

    /** The wheels stalled: nothing crept since sinceMs counts, nor anything after. */
    void legStalled(long sinceMs) {
        if (!legOpen) {
            return;
        }
        legStalled = true;
        for (Iterator<double[]> it = legMoves.iterator(); it.hasNext(); ) {
            if (it.next()[0] > sinceMs) {
                it.remove();
            }
        }
    }

    /** He drove a leg off cleanly: the log restarts from that leg. */
    void droveOffCleanly() {
        if (legClosing) {
            cleanPending = true;
        } else {
            // No leg was being logged (the heading was not usable when it began).
            legs.clear();
        }
    }

    void clearLegs() {
        legs.clear();
        cleanPending = false;
    }

    /** The legs since he last drove off cleanly, oldest first (a leg closes on the reading after its stop). */
    List<Leg> legs() {
        return new ArrayList<Leg>(legs);
    }

    private void endLeg() {
        if (!legOpen) {
            return;
        }
        legOpen = false;
        legClosing = false;
        boolean restart = cleanPending;
        cleanPending = false;
        double counts = 0;
        double x = 0;
        double y = 0;
        for (double[] m : legMoves) {
            counts += m[1];
            x += m[1] * Math.cos(Math.toRadians(m[2]));
            y += m[1] * Math.sin(Math.toRadians(m[2]));
        }
        legMoves.clear();
        long c = Math.round(counts);
        if (restart) {
            legs.clear();
        }
        if (c > 0) {
            legs.addLast(new Leg(wrap(Math.toDegrees(Math.atan2(y, x))), c));
            while (legs.size() > tuning.legsMax) {
                legs.pollFirst();
            }
        }
    }

    static double wrap(double d) {
        double w = d % 360;
        w = w < 0 ? w + 360 : w;
        return w >= 360 ? 0 : w;
    }
}
