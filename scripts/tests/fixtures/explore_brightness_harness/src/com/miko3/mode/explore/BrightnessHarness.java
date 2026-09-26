package com.miko3.mode.explore;

/**
 * Host harness for Brightness (explore nav plan U9, KTD10). Feeds synthetic
 * mean-luma series, and a simulated scene whose luma follows exposure times
 * sensitivity, and prints one PASS/FAIL line per scenario.
 */
public final class BrightnessHarness {
    private static final long MS = 1_000_000L;
    /** Frames about a second apart, as the detect thread delivers them. */
    private static final long FRAME_GAP_MS = 1000;

    public static void main(String[] args) {
        check("dark_series_raises_exposure_to_the_cap_then_sensitivity", darkSeries());
        check("bright_series_lowers_sensitivity_then_exposure", brightSeries());
        check("inside_the_dead_band_changes_nothing", deadBand());
        check("hysteresis_settles_into_the_inner_band_then_holds", hysteresis());
        check("steps_are_small_and_multiplicative", smallSteps());
        check("simulated_rooms_converge_without_oscillating", simulatedRooms());
        check("moving_pulls_a_long_exposure_to_the_moving_cap_at_once", movingPullsDown());
        check("still_allows_a_longer_exposure_than_moving", stillLonger());
        check("settings_stay_within_the_reported_ranges", withinRanges());
        check("missing_ranges_use_safe_defaults", missingRanges());
        check("frame_duration_follows_the_exposure", frameDuration());
        check("covered_lens_stops_at_the_limits_and_never_oscillates", coveredLens());
        check("frames_just_after_a_change_are_ignored", settleAfterChange());
        check("bad_luma_changes_nothing", badLuma());
        check("next_open_starts_from_the_last_settings_that_worked", startsFromLastWorked());
        check("mean_luma_of_pixels", meanLuma());
    }

    // ---- helpers ----

    /** A camera like the robot's: 0.1 ms..1 s exposure, ISO 100..3200. */
    private static Brightness typical(boolean moving) {
        Brightness b = new Brightness();
        b.setRanges(100_000L, 1_000_000_000L, 100, 3200, 2_000_000_000L);
        b.setMoving(0, moving);
        return b;
    }

    /** A driver for one controller: its clock and the settings in effect. */
    private static final class Run {
        final Brightness b;
        long now;
        Brightness.Settings s;
        int changes;

        Run(Brightness b) {
            this.b = b;
            this.s = b.start(now);
        }

        /** One frame at this luma; the new settings, or null when unchanged. */
        Brightness.Settings frame(double luma) {
            now += FRAME_GAP_MS;
            Brightness.Settings next = b.onFrame(now, now, luma);
            if (next != null) {
                s = next;
                changes++;
            }
            return next;
        }
    }

    /** A simulated room: luma from exposure and sensitivity, gamma-encoded, clipped. */
    private static double scene(Brightness.Settings s, double k, double gamma) {
        double light = k * (s.exposureNs / (double) MS) * (s.sensitivity / 100.0);
        return Math.min(255.0, 255.0 * Math.pow(Math.min(1.0, light / 1000.0), gamma));
    }

    private static boolean within(Brightness.Settings s, long expLo, long expHi, int isoLo, int isoHi) {
        return s.exposureNs >= expLo && s.exposureNs <= expHi && s.sensitivity >= isoLo
                && s.sensitivity <= isoHi && s.frameDurationNs >= s.exposureNs;
    }

    // ---- scenarios ----

    private static String darkSeries() {
        Brightness b = typical(true);
        b.seed(5 * MS, 100);
        Run r = new Run(b);
        long cap = b.exposureCapNs();
        boolean capReached = false;
        long lastExp = r.s.exposureNs;
        int lastIso = r.s.sensitivity;
        int expSteps = 0;
        for (int i = 0; i < 40; i++) {
            Brightness.Settings n = r.frame(20);
            if (n == null) {
                continue;
            }
            if (n.exposureNs < lastExp || n.sensitivity < lastIso) {
                return "a dark frame lowered a setting at step " + i + ": " + n;
            }
            if (n.exposureNs > lastExp) {
                expSteps++;
            }
            if (n.sensitivity > lastIso && lastExp < cap && n.exposureNs < cap) {
                return "sensitivity rose before exposure reached the cap: " + n;
            }
            if (n.exposureNs == cap) {
                capReached = true;
            }
            lastExp = n.exposureNs;
            lastIso = n.sensitivity;
        }
        if (!capReached || lastExp != cap) {
            return "exposure never reached the cap " + cap + ": " + r.s;
        }
        if (expSteps < 3) {
            return "exposure jumped instead of stepping (" + expSteps + " steps)";
        }
        if (lastIso <= 100) {
            return "sensitivity never rose after the cap: " + r.s;
        }
        return null;
    }

    private static String brightSeries() {
        Brightness b = typical(true);
        b.seed(60 * MS, 3200);
        Run r = new Run(b);
        long lastExp = r.s.exposureNs;
        int lastIso = r.s.sensitivity;
        boolean isoFloor = false;
        boolean expFell = false;
        for (int i = 0; i < 40; i++) {
            Brightness.Settings n = r.frame(250);
            if (n == null) {
                continue;
            }
            if (n.exposureNs > lastExp || n.sensitivity > lastIso) {
                return "a bright frame raised a setting: " + n;
            }
            if (n.exposureNs < lastExp && lastIso > 100 && n.sensitivity > 100) {
                return "exposure fell before sensitivity reached its floor: " + n;
            }
            if (n.sensitivity == 100) {
                isoFloor = true;
            }
            if (n.exposureNs < lastExp) {
                expFell = true;
            }
            lastExp = n.exposureNs;
            lastIso = n.sensitivity;
        }
        if (!isoFloor || !expFell) {
            return "expected sensitivity to reach 100 then exposure to fall: " + r.s;
        }
        return null;
    }

    private static String deadBand() {
        Run r = new Run(typical(true));
        Brightness.Settings first = r.s;
        double[] series = {110, 100, 120, 85, 140, 110, 90, 130};
        for (double l : series) {
            if (r.frame(l) != null) {
                return "luma " + l + " inside the dead band changed the settings";
            }
        }
        return r.s.equals(first) ? null : "settings drifted: " + r.s;
    }

    private static String hysteresis() {
        Brightness b = typical(true);
        b.seed(10 * MS, 100);
        Run r = new Run(b);
        // Outside the outer band: it starts adjusting.
        if (r.frame(60) == null) {
            return "luma 60 did not start an adjustment";
        }
        // Between the bands while settling: it keeps going.
        if (r.frame(88) == null) {
            return "luma 88 stopped the adjustment before the inner band";
        }
        // Inside the inner band: settled.
        if (r.frame(105) != null) {
            return "luma 105 still adjusted";
        }
        // Between the bands once settled: it holds.
        if (r.frame(88) != null || r.frame(140) != null) {
            return "settled controller moved for luma between the bands";
        }
        return null;
    }

    private static String smallSteps() {
        Brightness b = typical(false);
        b.seed(2 * MS, 100);
        Run r = new Run(b);
        double lastGain = gain(r.s);
        for (int i = 0; i < 30; i++) {
            Brightness.Settings n = r.frame(i < 15 ? 1 : 255);
            if (n == null) {
                continue;
            }
            double ratio = gain(n) / lastGain;
            if (ratio > Brightness.MAX_STEP * 1.01 || ratio < 1.0 / Brightness.MAX_STEP / 1.01) {
                return "a step changed exposure x sensitivity by " + ratio;
            }
            lastGain = gain(n);
        }
        return Brightness.MAX_STEP > 1.0 && Brightness.MAX_STEP <= 2.0 ? null
                : "MAX_STEP is not a small multiplicative step: " + Brightness.MAX_STEP;
    }

    private static double gain(Brightness.Settings s) {
        return (double) s.exposureNs * s.sensitivity;
    }

    private static String simulatedRooms() {
        double[] rooms = {0.05, 0.3, 1, 5, 40, 400};
        double[] gammas = {1.0, 0.45};
        for (boolean moving : new boolean[] {true, false}) {
            for (double k : rooms) {
                for (double g : gammas) {
                    Run r = new Run(typical(moving));
                    int lastDir = 0;
                    int flips = 0;
                    for (int i = 0; i < 80; i++) {
                        Brightness.Settings before = r.s;
                        Brightness.Settings n = r.frame(scene(r.s, k, g));
                        if (n != null) {
                            int dir = Double.compare(gain(n), gain(before));
                            if (lastDir != 0 && dir != lastDir) {
                                flips++;
                            }
                            lastDir = dir;
                        }
                    }
                    if (flips > 0) {
                        return "room k=" + k + " gamma=" + g + " moving=" + moving + " reversed " + flips + " times";
                    }
                    // Settled: the last 10 frames change nothing.
                    for (int i = 0; i < 10; i++) {
                        if (r.frame(scene(r.s, k, g)) != null) {
                            return "room k=" + k + " gamma=" + g + " moving=" + moving + " never settled";
                        }
                    }
                    double luma = scene(r.s, k, g);
                    boolean atLimit = r.s.sensitivity == 3200 && r.s.exposureNs == r.b.exposureCapNs()
                            || r.s.sensitivity == 100 && r.s.exposureNs == 100_000L;
                    if (!atLimit && (luma < Brightness.OUTER_LOW || luma > Brightness.OUTER_HIGH)) {
                        return "room k=" + k + " gamma=" + g + " settled at luma " + luma + " away from the limits";
                    }
                }
            }
        }
        return null;
    }

    private static String movingPullsDown() {
        Brightness b = typical(false);
        b.seed(180 * MS, 400);
        Run r = new Run(b);
        if (r.s.exposureNs != 180 * MS) {
            return "still start was not the seeded 180 ms: " + r.s;
        }
        Brightness.Settings n = b.setMoving(r.now + 10, true);
        if (n == null) {
            return "switching to moving changed nothing";
        }
        if (n.exposureNs != b.exposureCapNs() || n.exposureNs > Brightness.MOVING_CAP_NS) {
            return "exposure not pulled to the moving cap: " + n;
        }
        if (n.sensitivity <= 400) {
            return "sensitivity did not make up for the shorter exposure: " + n;
        }
        if (b.setMoving(r.now + 20, true) != null) {
            return "saying moving again changed the settings";
        }
        Brightness short_ = typical(false);
        short_.seed(20 * MS, 400);
        new Run(short_);
        return short_.setMoving(5, true) == null ? null : "a short exposure changed on moving";
    }

    private static String stillLonger() {
        Brightness moving = typical(true);
        Brightness still = typical(false);
        if (still.exposureCapNs() <= moving.exposureCapNs()) {
            return "still cap " + still.exposureCapNs() + " is not above moving cap " + moving.exposureCapNs();
        }
        if (moving.exposureCapNs() > Brightness.MOVING_CAP_NS || still.exposureCapNs() > Brightness.STILL_CAP_NS) {
            return "caps exceed their constants";
        }
        Run r = new Run(still);
        for (int i = 0; i < 60; i++) {
            r.frame(5);
        }
        return r.s.exposureNs == still.exposureCapNs() && r.s.exposureNs > Brightness.MOVING_CAP_NS ? null
                : "still dark series ended at " + r.s;
    }

    private static String withinRanges() {
        long[][] exp = {{100_000L, 1_000_000_000L}, {1_000_000L, 30_000_000L}, {50_000_000L, 80_000_000L}};
        int[][] iso = {{100, 3200}, {50, 800}, {400, 400}};
        java.util.Random rnd = new java.util.Random(7);
        for (long[] e : exp) {
            for (int[] s : iso) {
                for (boolean moving : new boolean[] {true, false}) {
                    Brightness b = new Brightness();
                    b.setRanges(e[0], e[1], s[0], s[1], null);
                    b.setMoving(0, moving);
                    Run r = new Run(b);
                    int isoHi = Math.min(s[1], Brightness.ISO_CEILING);
                    if (!within(r.s, e[0], e[1], s[0], isoHi)) {
                        return "start out of range " + r.s;
                    }
                    for (int i = 0; i < 200; i++) {
                        r.frame(rnd.nextInt(256));
                        if (i % 37 == 0) {
                            Brightness.Settings n = b.setMoving(r.now, !moving);
                            if (n != null) {
                                r.s = n;
                            }
                        }
                        if (!within(r.s, e[0], e[1], s[0], isoHi)) {
                            return "out of range " + r.s + " for exp " + e[0] + ".." + e[1]
                                    + " iso " + s[0] + ".." + s[1];
                        }
                    }
                }
            }
        }
        // The camera's maximum frame duration bounds the exposure too.
        Brightness b = new Brightness();
        b.setRanges(100_000L, 1_000_000_000L, 100, 3200, 100 * MS);
        b.setMoving(0, false);
        Run r = new Run(b);
        for (int i = 0; i < 60; i++) {
            r.frame(0);
        }
        return r.s.exposureNs <= 100 * MS && r.s.frameDurationNs <= 100 * MS ? null
                : "max frame duration ignored: " + r.s;
    }

    private static String missingRanges() {
        Brightness b = new Brightness();
        b.setRanges(null, null, null, null, null);
        b.setMoving(0, false);
        Run r = new Run(b);
        for (int i = 0; i < 60; i++) {
            r.frame(0);
        }
        Brightness.Settings dark = r.s;
        if (dark.exposureNs != Brightness.DEFAULT_EXPOSURE_HI_NS || dark.sensitivity != Brightness.DEFAULT_ISO_HI) {
            return "dark limit without ranges is " + dark;
        }
        for (int i = 0; i < 80; i++) {
            r.frame(255);
        }
        if (r.s.exposureNs != Brightness.DEFAULT_EXPOSURE_LO_NS || r.s.sensitivity != Brightness.DEFAULT_ISO_LO) {
            return "bright limit without ranges is " + r.s;
        }
        // Nonsense ranges count as missing.
        Brightness odd = new Brightness();
        odd.setRanges(50_000_000L, 1_000L, 0, -5, -1L);
        Run o = new Run(odd);
        return within(o.s, Brightness.DEFAULT_EXPOSURE_LO_NS, Brightness.DEFAULT_EXPOSURE_HI_NS,
                Brightness.DEFAULT_ISO_LO, Brightness.DEFAULT_ISO_HI) ? null : "nonsense ranges gave " + o.s;
    }

    private static String frameDuration() {
        Brightness b = typical(false);
        b.seed(2 * MS, 100);
        Run r = new Run(b);
        if (r.s.frameDurationNs != Brightness.MIN_FRAME_DURATION_NS) {
            return "a short exposure's frame duration is " + r.s.frameDurationNs;
        }
        for (int i = 0; i < 60; i++) {
            r.frame(3);
        }
        return r.s.frameDurationNs == r.s.exposureNs && r.s.exposureNs > Brightness.MIN_FRAME_DURATION_NS ? null
                : "a long exposure's frame duration does not follow it: " + r.s;
    }

    private static String coveredLens() {
        for (boolean moving : new boolean[] {true, false}) {
            Brightness b = typical(moving);
            Run r = new Run(b);
            long lastExp = r.s.exposureNs;
            int lastIso = r.s.sensitivity;
            for (int i = 0; i < 100; i++) {
                Brightness.Settings n = r.frame(0);
                if (n != null && (n.exposureNs < lastExp || n.sensitivity < lastIso)) {
                    return "black frames lowered a setting: " + n;
                }
                lastExp = r.s.exposureNs;
                lastIso = r.s.sensitivity;
            }
            if (r.s.exposureNs != b.exposureCapNs() || r.s.sensitivity != 3200) {
                return "covered lens stopped short of the limits: " + r.s;
            }
            int before = r.changes;
            for (int i = 0; i < 50; i++) {
                r.frame(0);
            }
            if (r.changes != before) {
                return "covered lens kept changing at the limits";
            }
            if (before > 20) {
                return "covered lens took " + before + " changes";
            }
        }
        return null;
    }

    private static String settleAfterChange() {
        Brightness b = typical(true);
        b.seed(5 * MS, 100);
        Brightness.Settings s = b.start(0);
        Brightness.Settings n = b.onFrame(1000, 1000, 20);
        if (n == null) {
            return "dark frame did not adjust";
        }
        // A frame captured just after the change may still carry the old settings.
        if (b.onFrame(1050, 1050, 20) != null) {
            return "a frame 50 ms after the change adjusted again";
        }
        return b.onFrame(3000, 3000, 20) != null ? null : "a frame 2 s later did not adjust";
    }

    private static String badLuma() {
        Run r = new Run(typical(true));
        double[] bad = {Double.NaN, -1, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (double l : bad) {
            if (r.frame(l) != null) {
                return "luma " + l + " changed the settings";
            }
        }
        return null;
    }

    private static String startsFromLastWorked() {
        Brightness b = typical(true);
        b.seed(5 * MS, 100);
        Run r = new Run(b);
        for (int i = 0; i < 6; i++) {
            r.frame(20);
        }
        Brightness.Settings worked = r.s;
        // One more change that no frame confirms before the camera closes.
        r.frame(20);
        Brightness.Settings unconfirmed = r.s;
        if (unconfirmed.equals(worked)) {
            return "expected a further change";
        }
        Brightness.Settings reopened = b.start(r.now + 5000);
        if (!reopened.equals(worked)) {
            return "reopened at " + reopened + ", last worked " + worked;
        }
        Brightness fresh = typical(true);
        Brightness.Settings first = fresh.start(0);
        return first.exposureNs == Math.min(Brightness.DEFAULT_START_EXPOSURE_NS, fresh.exposureCapNs())
                && first.sensitivity == Brightness.DEFAULT_START_ISO ? null : "fresh start is " + first;
    }

    private static String meanLuma() {
        int[] grey = new int[100];
        java.util.Arrays.fill(grey, 0xff808080);
        double g = Brightness.meanLuma(grey, grey.length);
        if (Math.abs(g - 128) > 1) {
            return "grey 128 reads " + g;
        }
        int[] black = new int[50];
        if (Brightness.meanLuma(black, black.length) != 0) {
            return "black reads " + Brightness.meanLuma(black, black.length);
        }
        int[] white = new int[50];
        java.util.Arrays.fill(white, 0xffffffff);
        if (Math.abs(Brightness.meanLuma(white, white.length) - 255) > 1) {
            return "white reads " + Brightness.meanLuma(white, white.length);
        }
        // Green weighs most, blue least.
        double green = Brightness.meanLuma(new int[] {0xff00ff00}, 1);
        double blue = Brightness.meanLuma(new int[] {0xff0000ff}, 1);
        if (!(green > 140 && blue < 35)) {
            return "weights off: green " + green + " blue " + blue;
        }
        // Only the first n pixels count; none is no reading.
        if (Math.abs(Brightness.meanLuma(new int[] {0xffffffff, 0}, 1) - 255) > 1) {
            return "count ignored";
        }
        return Double.isNaN(Brightness.meanLuma(new int[0], 0)) && Double.isNaN(Brightness.meanLuma(null, 3))
                ? null : "no pixels should read NaN";
    }

    private static void check(String name, String failure) {
        System.out.println(failure == null ? "PASS " + name : "FAIL " + name + ": " + failure);
    }
}
