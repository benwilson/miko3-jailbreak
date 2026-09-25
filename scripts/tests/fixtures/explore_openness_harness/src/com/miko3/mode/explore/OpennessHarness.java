package com.miko3.mode.explore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Host harness for Openness (explore nav plan U3, KTD3). Paints synthetic
 * scenes -- a far wall above the horizon, floor below it, and rectangles laid
 * over them in frame fractions -- samples them the way ExploreCamera does (a
 * small whole frame plus a sharper floor band below the horizon), and prints
 * one PASS/FAIL line per scenario.
 */
public final class OpennessHarness {
    private static final int FLOOR = rgb(150, 120, 90);
    private static final int FAR_WALL = rgb(205, 205, 215);
    private static final int NEAR_WALL = rgb(60, 80, 160);
    private static final int RUG = rgb(170, 40, 40);
    private static final int GREEN = rgb(40, 160, 60);
    private static final int DARK = rgb(4, 4, 6);
    private static final List<Detection> NONE = Collections.<Detection>emptyList();

    public static void main(String[] args) {
        check("wall_on_the_left_scores_left_low_and_right_high", wallOnTheLeft());
        check("all_floor_frame_scores_every_bin_open", allFloor());
        check("box_reaching_the_bottom_blocks_floor_coloured_columns", lowBox());
        check("high_box_lowers_its_columns_only_slightly", highBox());
        check("taught_rug_stays_open_and_untaught_colour_reads_unsure", rugAndUntaught());
        check("taught_floor_raises_confidence", taughtConfidence());
        check("profile_has_sixteen_bins_in_range", binsInRange());
        check("all_dark_frame_has_low_confidence", allDark());
        check("no_detections_and_no_taught_floor_is_low_confidence", untaughtNoBoxes());
        check("textured_bottom_rows_are_not_a_floor_sample", texturedBottom());
        check("floor_patches_are_capped", patchesCapped());
        check("band_optional_whole_frame_alone_still_scores", wholeOnly());
        check("frame_not_teachable_never_teaches", notTeachable());
        check("pending_dropped_by_a_hazard_before_driving_there", droppedByHazard());
        check("pending_taught_once_driven_over_cleanly", taughtWhenDriven());
        check("late_frame_from_before_a_hazard_is_refused", lateFrameRefused());
        check("pending_samples_are_capped_oldest_first", pendingCapped());
        check("malformed_input_never_throws", malformed());
        check("look_carries_the_profile_and_older_constructors_leave_it_null", lookCarriesProfile());
        check("no_camera_accepts_the_floor_calls", noCamera());
    }

    // ---- scenes ----

    /** A room: a far wall whose base sits just below the horizon, floor below it, then each layer painted over. */
    private static final class Scene {
        private final List<float[]> rects = new ArrayList<>();
        private final List<Integer> colours = new ArrayList<>();
        private boolean checker;
        private float[] checkerRect;

        Scene paint(float x0, float y0, float x1, float y1, int colour) {
            rects.add(new float[] {x0, y0, x1, y1});
            colours.add(colour);
            return this;
        }

        /** A fine two-colour checkerboard (cells 0.025 of the frame) over this rectangle. */
        Scene checker(float x0, float y0, float x1, float y1) {
            checker = true;
            checkerRect = new float[] {x0, y0, x1, y1};
            return this;
        }

        int at(float fx, float fy) {
            int c = fy < Openness.HORIZON + 0.05f ? FAR_WALL : FLOOR;
            for (int i = 0; i < rects.size(); i++) {
                float[] r = rects.get(i);
                if (fx >= r[0] && fx < r[2] && fy >= r[1] && fy < r[3]) {
                    c = colours.get(i);
                }
            }
            if (checker && fx >= checkerRect[0] && fx < checkerRect[2] && fy >= checkerRect[1] && fy < checkerRect[3]) {
                int cx = (int) (fx / 0.025f);
                int cy = (int) (fy / 0.025f);
                c = ((cx + cy) & 1) == 0 ? rgb(240, 240, 240) : rgb(20, 20, 20);
            }
            return c;
        }

        /** The whole frame, 80x60, as ExploreCamera averages it down. */
        Openness.Frame whole() {
            return sample(80, 60, 0f, 1f);
        }

        /** The floor band: the rows below the horizon, twice as sharp. */
        Openness.Frame band() {
            int rows = Math.round((1f - Openness.HORIZON) * 120);
            return sample(160, rows, Openness.HORIZON, 1f);
        }

        private Openness.Frame sample(int w, int h, float top, float bottom) {
            int[] px = new int[w * h];
            for (int y = 0; y < h; y++) {
                float fy = top + (y + 0.5f) * (bottom - top) / h;
                for (int x = 0; x < w; x++) {
                    px[y * w + x] = at((x + 0.5f) / w, fy);
                }
            }
            return new Openness.Frame(px, w, h, top, bottom);
        }
    }

    private static Scene room() {
        return new Scene();
    }

    private static Openness.Profile score(Openness o, Scene s, List<Detection> d, long ms, boolean teachable) {
        return o.score(s.whole(), s.band(), d, ms, teachable);
    }

    /** Teaches the scene's bottom-centre patch: a clean frame, then driven over. */
    private static void teach(Openness o, Scene s, long ms) {
        score(o, s, NONE, ms, true);
        o.floorDrivenOver(ms);
    }

    private static Openness taughtRoom() {
        Openness o = new Openness();
        teach(o, room(), 100);
        return o;
    }

    // ---- scenarios ----

    private static String wallOnTheLeft() {
        Openness o = taughtRoom();
        Openness.Profile p = score(o, room().paint(0f, 0f, 0.35f, 0.85f, NEAR_WALL), NONE, 200, false);
        float left = max(p.bins, 0, 3);
        float right = min(p.bins, 8, 15);
        if (left >= 0.4f || right <= 0.8f) {
            return "left " + left + " right " + right + " " + p;
        }
        return null;
    }

    private static String allFloor() {
        Openness o = taughtRoom();
        Openness.Profile p = score(o, room().paint(0f, 0f, 1f, 1f, FLOOR), NONE, 200, false);
        float lo = min(p.bins, 0, Openness.BINS - 1);
        return lo > 0.8f ? null : "lowest bin " + lo + " " + p;
    }

    private static String lowBox() {
        Openness o = taughtRoom();
        List<Detection> d = Arrays.asList(new Detection("chair", 0.8f, 0.5f, 0.3f, 0.75f, 0.9f));
        Openness.Profile p = score(o, room(), d, 200, false);
        float boxed = max(p.bins, 8, 11);
        float clear = min(p.bins, 0, 6);
        if (boxed >= 0.1f || clear <= 0.8f) {
            return "boxed " + boxed + " clear " + clear + " " + p;
        }
        return null;
    }

    private static String highBox() {
        Openness o = taughtRoom();
        List<Detection> d = Arrays.asList(new Detection("clock", 0.8f, 0.5f, 0.1f, 0.75f, 0.4f));
        Openness.Profile p = score(o, room(), d, 200, false);
        float boxed = min(p.bins, 8, 11);
        float clear = min(p.bins, 0, 6);
        if (boxed < 0.7f || boxed >= clear) {
            return "boxed " + boxed + " clear " + clear + " " + p;
        }
        return null;
    }

    private static String rugAndUntaught() {
        Openness o = taughtRoom();
        teach(o, room().paint(0.2f, 0.8f, 0.8f, 1f, RUG), 150);
        Scene s = room().paint(0f, 0.6f, 0.5f, 1f, RUG).paint(0.5f, 0.6f, 1f, 1f, GREEN);
        Openness.Profile p = score(o, s, NONE, 200, false);
        float rug = min(p.bins, 0, 6);
        float greenLo = min(p.bins, 9, 15);
        float greenHi = max(p.bins, 9, 15);
        if (rug <= 0.8f) {
            return "taught rug reads " + rug + " " + p;
        }
        if (greenLo < 0.35f || greenHi > 0.65f) {
            return "untaught colour reads " + greenLo + ".." + greenHi + ", not unsure " + p;
        }
        return null;
    }

    private static String taughtConfidence() {
        Openness o = new Openness();
        float before = score(o, room(), NONE, 50, false).confidence;
        teach(o, room(), 100);
        float after = score(o, room(), NONE, 200, false).confidence;
        if (before >= 0.4f || after < 0.7f) {
            return "untaught " + before + ", taught " + after;
        }
        return null;
    }

    private static String binsInRange() {
        Openness o = taughtRoom();
        List<Detection> d = Arrays.asList(new Detection("chair", 0.8f, 0.1f, 0.2f, 0.4f, 0.95f),
                new Detection("plant", 0.6f, 0.6f, 0.1f, 0.9f, 0.5f));
        Openness.Profile p = score(o, room().paint(0.7f, 0f, 1f, 0.9f, NEAR_WALL), d, 200, false);
        if (Openness.BINS != 16 || p.bins.length != 16) {
            return "bins " + p.bins.length;
        }
        for (float b : p.bins) {
            if (!(b >= 0f && b <= 1f)) {
                return "bin out of range " + p;
            }
        }
        return p.confidence >= 0f && p.confidence <= 1f ? null : "confidence " + p.confidence;
    }

    private static String allDark() {
        Openness o = taughtRoom();
        Openness.Profile p = score(o, room().paint(0f, 0f, 1f, 1f, DARK), NONE, 200, false);
        if (p.confidence >= 0.2f) {
            return "dark frame confidence " + p.confidence;
        }
        Openness fresh = new Openness();
        Openness.Profile q = score(fresh, room().paint(0f, 0f, 1f, 1f, DARK), NONE, 200, true);
        fresh.floorDrivenOver(Long.MAX_VALUE);
        if (fresh.patches() != 0) {
            return "a dark frame taught the floor";
        }
        return q.confidence < 0.2f ? null : "untaught dark confidence " + q.confidence;
    }

    private static String untaughtNoBoxes() {
        Openness o = new Openness();
        Openness.Profile p = score(o, room(), NONE, 200, false);
        Openness.Profile q = score(o, room(), null, 300, false);
        if (p.confidence >= 0.4f || q.confidence >= 0.4f) {
            return "confidence " + p.confidence + " / " + q.confidence;
        }
        return null;
    }

    private static String texturedBottom() {
        Openness o = new Openness();
        score(o, room().checker(0f, 0.75f, 1f, 1f), NONE, 100, true);
        o.floorDrivenOver(Long.MAX_VALUE);
        return o.patches() == 0 ? null : "a checkerboard was taught as floor";
    }

    private static String patchesCapped() {
        Openness o = new Openness();
        int[] colours = {FLOOR, RUG, GREEN, rgb(90, 90, 200), rgb(220, 180, 60), rgb(120, 60, 150)};
        for (int i = 0; i < colours.length; i++) {
            teach(o, room().paint(0f, Openness.HORIZON, 1f, 1f, colours[i]), 100 + i);
        }
        if (o.patches() != Openness.MAX_PATCHES) {
            return "patches " + o.patches();
        }
        // The newest is kept: a floor of that colour still reads open.
        Openness.Profile p = score(o, room().paint(0f, Openness.HORIZON, 1f, 1f, colours[5]), NONE, 200, false);
        float lo = min(p.bins, 0, Openness.BINS - 1);
        return lo > 0.8f ? null : "newest patch forgotten: " + p;
    }

    private static String wholeOnly() {
        Openness o = new Openness();
        o.score(room().whole(), null, NONE, 100, true);
        o.floorDrivenOver(100);
        if (o.patches() != 1) {
            return "whole-frame sample not taught: patches " + o.patches();
        }
        Openness.Profile p = o.score(room().paint(0f, 0f, 0.35f, 0.85f, NEAR_WALL).whole(), null, NONE, 200, false);
        float left = max(p.bins, 0, 3);
        float right = min(p.bins, 8, 15);
        return left < 0.4f && right > 0.8f ? null : "left " + left + " right " + right + " " + p;
    }

    private static String notTeachable() {
        Openness o = new Openness();
        score(o, room(), NONE, 100, false);
        if (o.pending() != 0) {
            return "pending " + o.pending();
        }
        o.floorDrivenOver(Long.MAX_VALUE);
        return o.patches() == 0 ? null : "taught from a frame not marked clear";
    }

    private static String droppedByHazard() {
        Openness o = new Openness();
        score(o, room(), NONE, 1000, true);
        if (o.pending() != 1) {
            return "pending before hazard " + o.pending();
        }
        o.floorHazard(1500);
        if (o.pending() != 0) {
            return "pending after hazard " + o.pending();
        }
        o.floorDrivenOver(Long.MAX_VALUE);
        return o.patches() == 0 ? null : "dropped sample was taught";
    }

    private static String taughtWhenDriven() {
        Openness o = new Openness();
        score(o, room(), NONE, 1000, true);
        score(o, room(), NONE, 2000, true);
        o.floorDrivenOver(1000);
        if (o.patches() != 1 || o.pending() != 1) {
            return "after the first patch: patches " + o.patches() + " pending " + o.pending();
        }
        o.floorHazard(1500);
        if (o.pending() != 1) {
            return "a hazard before the second frame dropped it";
        }
        o.floorDrivenOver(2000);
        if (o.pending() != 0 || o.patches() != 1) {
            return "after the second: patches " + o.patches() + " pending " + o.pending();
        }
        return null;
    }

    private static String lateFrameRefused() {
        Openness o = new Openness();
        o.floorHazard(1500);
        score(o, room(), NONE, 1200, true);
        if (o.pending() != 0) {
            return "a frame from before the hazard became pending";
        }
        score(o, room(), NONE, 1600, true);
        return o.pending() == 1 ? null : "a frame after the hazard was refused";
    }

    private static String pendingCapped() {
        Openness o = new Openness();
        for (int i = 0; i < Openness.MAX_PENDING + 5; i++) {
            score(o, room(), NONE, 1000 + i, true);
        }
        if (o.pending() != Openness.MAX_PENDING) {
            return "pending " + o.pending();
        }
        o.floorDrivenOver(1004);
        if (o.patches() != 0) {
            return "the oldest samples were kept";
        }
        o.floorDrivenOver(1005);
        return o.patches() == 1 ? null : "the newest samples were dropped";
    }

    private static String malformed() {
        Openness o = new Openness();
        try {
            List<Openness.Profile> ps = new ArrayList<>();
            ps.add(o.score(null, null, null, 0, true));
            ps.add(o.score(new Openness.Frame(null, 80, 60, 0f, 1f), null, NONE, 1, true));
            ps.add(o.score(new Openness.Frame(new int[10], 80, 60, 0f, 1f), null, NONE, 2, true));
            ps.add(o.score(new Openness.Frame(new int[0], 0, 0, 0f, 1f), new Openness.Frame(new int[4], -2, -2, 1f, 0f),
                    NONE, 3, true));
            ps.add(o.score(new Openness.Frame(new int[16], 4, 4, 0.9f, 0.1f), null, NONE, 4, true));
            ps.add(o.score(new Openness.Frame(new int[16], 4, 4, Float.NaN, 1f), null, NONE, 5, true));
            List<Detection> odd = new ArrayList<>();
            odd.add(null);
            odd.add(new Detection("x", Float.NaN, Float.NaN, 0.2f, 0.5f, Float.NaN));
            ps.add(o.score(room().whole(), room().band(), odd, 6, true));
            o.floorHazard(Long.MIN_VALUE);
            o.floorDrivenOver(Long.MIN_VALUE);
            o.floorDrivenOver(Long.MAX_VALUE);
            for (int i = 0; i < ps.size(); i++) {
                Openness.Profile p = ps.get(i);
                if (p == null || p.bins == null || p.bins.length != Openness.BINS) {
                    return "case " + i + " gave no profile";
                }
                if (i < 6 && p.confidence > 0.1f) {
                    return "case " + i + " (no usable image) confidence " + p.confidence;
                }
            }
            return null;
        } catch (RuntimeException e) {
            return "threw " + e;
        }
    }

    private static String lookCarriesProfile() {
        Openness.Profile p = new Openness().score(room().whole(), room().band(), NONE, 1, false);
        List<Detection> d = NONE;
        ExploreBrain.Look withProfile = new ExploreBrain.Look(1, d, new byte[] {1}, p);
        ExploreBrain.Look withJpeg = new ExploreBrain.Look(1, d, new byte[] {1});
        ExploreBrain.Look bare = new ExploreBrain.Look(1, d);
        if (withProfile.openness != p || withProfile.jpeg == null) {
            return "the profile did not ride on the look";
        }
        return withJpeg.openness == null && bare.openness == null ? null : "older constructors invented a profile";
    }

    private static String noCamera() {
        try {
            ExploreBrain.NO_CAMERA.setFloorClear(0, true);
            ExploreBrain.NO_CAMERA.setFloorClear(1, false);
            ExploreBrain.NO_CAMERA.floorDrivenOver(1);
            return null;
        } catch (RuntimeException e) {
            return "threw " + e;
        }
    }

    // ---- helpers ----

    private static int rgb(int r, int g, int b) {
        return (r << 16) | (g << 8) | b;
    }

    private static float min(float[] v, int from, int to) {
        float m = Float.MAX_VALUE;
        for (int i = from; i <= to; i++) {
            m = Math.min(m, v[i]);
        }
        return m;
    }

    private static float max(float[] v, int from, int to) {
        float m = -Float.MAX_VALUE;
        for (int i = from; i <= to; i++) {
            m = Math.max(m, v[i]);
        }
        return m;
    }

    private static void check(String name, String failure) {
        System.out.println(failure == null ? "PASS " + name : "FAIL " + name + ": " + failure);
    }
}
