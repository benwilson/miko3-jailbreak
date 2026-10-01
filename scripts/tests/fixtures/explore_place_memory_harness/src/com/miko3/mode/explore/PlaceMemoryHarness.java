package com.miko3.mode.explore;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;

/** Drives PlaceMemory (and RoamSteer with its novelty); one PASS/FAIL line per scenario. */
public final class PlaceMemoryHarness {
    private static final long MIN = 60000;
    private static File frames;

    public static void main(String[] args) throws Exception {
        frames = new File(args[0]);
        check("real_frames_score_like_the_offline_reference", realFrames());
        check("a_plain_wall_is_no_evidence_either_way", plainWall());
        check("a_new_view_gives_full_novelty", newView());
        check("repeating_the_same_view_at_the_same_heading_lowers_its_novelty", repeatedView());
        check("a_view_from_under_a_minute_ago_does_not_count", tooRecent());
        check("a_memory_older_than_30_min_is_forgotten", forgotten());
        check("an_unusable_heading_still_matches_on_appearance", unusableHeading());
        check("the_same_view_more_than_45_deg_away_does_not_match", otherHeading());
        check("keeps_one_print_per_record_interval_and_at_most_the_cap_oldest_first", capped());
        check("the_steer_picks_the_unfamiliar_side", steerPicksUnfamiliar());
        check("grid_and_place_combine_by_the_lower_and_a_blocked_band_never_gains", combine());
        check("the_note_reads_seen_before_with_sim_and_minutes", note());
        check("defaults_30_min_300_prints", defaults());
    }

    private static void check(String name, String failure) {
        System.out.println((failure == null ? "PASS " : "FAIL ") + name + (failure == null ? "" : " :: " + failure));
    }

    private static ExploreTuning tuning() {
        return ExploreTuning.defaults(null);
    }

    // ---- prints ----

    /** An 80x60 frame of the given scene (seed): random 5x5 blocks of colour, so every scene has texture. */
    static PlaceMemory.Print scene(long seed) {
        Random r = new Random(seed);
        int[] rgb = new int[80 * 60];
        int[] cells = new int[16 * 12];
        for (int i = 0; i < cells.length; i++) {
            cells[i] = (r.nextInt(256) << 16) | (r.nextInt(256) << 8) | r.nextInt(256);
        }
        for (int y = 0; y < 60; y++) {
            for (int x = 0; x < 80; x++) {
                rgb[y * 80 + x] = cells[(y / 5) * 16 + x / 5];
            }
        }
        return PlaceMemory.Print.of(rgb, 80, 60);
    }

    private static PlaceMemory.Print frame(String name) throws Exception {
        byte[] b = Files.readAllBytes(new File(frames, name + ".rgb").toPath());
        int[] rgb = new int[80 * 60];
        for (int i = 0; i < rgb.length; i++) {
            rgb[i] = ((b[i * 3] & 0xff) << 16) | ((b[i * 3 + 1] & 0xff) << 8) | (b[i * 3 + 2] & 0xff);
        }
        return PlaceMemory.Print.of(rgb, 80, 60);
    }

    private static String realFrames() throws Exception {
        StringBuilder bad = new StringBuilder();
        int pairs = 0;
        for (String line : Files.readAllLines(new File(frames, "expected.txt").toPath())) {
            String[] f = line.split(" ");
            if (!f[0].equals("pair")) {
                continue;
            }
            pairs++;
            double want = Double.parseDouble(f[4]);
            double got = PlaceMemory.similarity(frame(f[1]), frame(f[2]));
            boolean same = f[3].equals("same");
            ExploreTuning t = tuning();
            if (Math.abs(got - want) > 0.02 || (same ? got < t.placeSeenSim : got >= t.placeSimLow)) {
                bad.append(String.format(Locale.US, " %s %s/%s want %.3f got %.3f;", f[3], f[1], f[2], want, got));
            }
        }
        return pairs == 4 && bad.length() == 0 ? null : "pairs=" + pairs + bad;
    }

    private static String plainWall() throws Exception {
        PlaceMemory.Print wall = frame("1790887822808");
        PlaceMemory m = new PlaceMemory(tuning());
        m.look(wall, null, 0, 0);
        PlaceMemory.Match later = m.look(wall, null, 0, 5 * MIN);
        double s = PlaceMemory.similarity(wall, wall);
        return wall.plain() && Double.isNaN(s) && Double.isNaN(later.novelty) && Double.isNaN(later.sim)
                && m.size() == 0 && !scene(1).plain()
                ? null : "plain=" + wall.plain() + " sim=" + s + " novelty=" + later.novelty + " size=" + m.size();
    }

    private static String newView() {
        PlaceMemory m = new PlaceMemory(tuning());
        PlaceMemory.Match first = m.look(scene(1), null, 0, 0);
        PlaceMemory.Match other = m.look(scene(2), null, 0, 5 * MIN);
        double cross = PlaceMemory.similarity(scene(1), scene(2));
        return first.novelty == 1.0 && other.novelty == 1.0 && !other.seen() && cross < tuning().placeSimLow
                ? null : "first=" + first.novelty + " other=" + other.novelty + " cross=" + cross;
    }

    private static String repeatedView() {
        PlaceMemory m = new PlaceMemory(tuning());
        m.look(scene(1), null, 10, 0);
        PlaceMemory.Match again = m.look(scene(1), null, 30, 5 * MIN);
        return again.seen() && again.sim > 0.99 && again.novelty < 0.05 && again.ageMs == 5 * MIN
                ? null : "sim=" + again.sim + " novelty=" + again.novelty + " age=" + again.ageMs;
    }

    private static String tooRecent() {
        PlaceMemory m = new PlaceMemory(tuning());
        m.look(scene(1), null, 0, 0);
        PlaceMemory.Match soon = m.look(scene(1), null, 0, 20000);
        PlaceMemory.Match minute = m.look(scene(1), null, 0, MIN);
        return soon.novelty == 1.0 && !soon.seen() && minute.seen()
                ? null : "soon=" + soon.novelty + " minute=" + minute.seen();
    }

    private static String forgotten() {
        PlaceMemory m = new PlaceMemory(tuning());
        m.look(scene(1), null, 0, 0);
        PlaceMemory.Match within = m.look(scene(1), null, 0, 29 * MIN);
        PlaceMemory m2 = new PlaceMemory(tuning());
        m2.look(scene(1), null, 0, 0);
        PlaceMemory.Match after = m2.look(scene(1), null, 0, 31 * MIN);
        return within.seen() && !after.seen() && after.novelty == 1.0
                ? null : "within=" + within.seen() + " after=" + after.seen() + "/" + after.novelty;
    }

    private static String unusableHeading() {
        PlaceMemory stored = new PlaceMemory(tuning());
        stored.look(scene(1), null, Double.NaN, 0);
        PlaceMemory.Match a = stored.look(scene(1), null, 200, 5 * MIN);
        PlaceMemory now = new PlaceMemory(tuning());
        now.look(scene(1), null, 90, 0);
        PlaceMemory.Match b = now.look(scene(1), null, Double.NaN, 5 * MIN);
        return a.seen() && b.seen() && a.novelty < 0.05 && b.novelty < 0.05
                ? null : "stored-unusable=" + a.seen() + " looking-unusable=" + b.seen();
    }

    private static String otherHeading() {
        PlaceMemory m = new PlaceMemory(tuning());
        m.look(scene(1), null, 350, 0);
        PlaceMemory.Match near = m.look(scene(1), null, 30, 5 * MIN);
        PlaceMemory.Match far = m.look(scene(1), null, 90, 6 * MIN);
        return near.seen() && !far.seen() && far.novelty == 1.0
                ? null : "near(40 deg)=" + near.seen() + " far(100 deg)=" + far.seen();
    }

    private static String capped() {
        ExploreTuning t = tuning();
        PlaceMemory m = new PlaceMemory(t);
        // A look a second for 2 s: one print kept.
        m.look(scene(1), null, 0, 0);
        m.look(scene(2), null, 0, 1000);
        int one = m.size();
        // A print a second (well inside the 30 min fade): the cap, not the fade, drops the oldest.
        ExploreTuning fast = new ExploreTuning.Builder().placeMemory(t.placeFadeMs, t.placeMax, 1000, MIN).build();
        PlaceMemory big = new PlaceMemory(fast);
        long at = 0;
        for (int i = 0; i < t.placeMax + 20; i++) {
            big.look(scene(100 + i), null, 0, at);
            at += 1000;
        }
        // The first 20 prints are gone, the 21st is kept.
        PlaceMemory.Match kept = big.look(scene(120), null, 0, at);
        PlaceMemory.Match oldest = big.look(scene(100), null, 0, at + 1);
        return one == 1 && big.size() <= t.placeMax && !oldest.seen() && kept.seen()
                ? null : "one=" + one + " size=" + big.size() + " oldest=" + oldest.seen() + " 21st=" + kept.seen();
    }

    // ---- steering ----

    private static Openness.Profile open() {
        float[] b = new float[Openness.BINS];
        Arrays.fill(b, 0.9f);
        return new Openness.Profile(b, 0.9f);
    }

    private static String steerPicksUnfamiliar() {
        ExploreTuning t = tuning();
        PlaceMemory m = new PlaceMemory(t);
        // Five minutes ago, facing 0 and 60 (left): scenes 1 and 2.
        m.look(scene(1), null, 0, 0);
        m.look(scene(2), null, 60, 10000);
        // Now a scan: 60 left (scene 2 again), 300 = 60 right (new), then back to 0 (scene 1 again).
        long now = 5 * MIN;
        m.look(scene(2), null, 60, now);
        m.look(scene(3), null, 300, now + 2000);
        PlaceMemory.Match here = m.look(scene(1), null, 0, now + 4000);
        RoamSteer.Novelty nov = m.steer(null, 0, true, here.novelty, now + 5000);
        RoamSteer.Plan with = new RoamSteer(t).plan(open(), Double.NaN, nov);
        RoamSteer.Plan without = new RoamSteer(t).plan(open(), Double.NaN, null);
        return nov != null && here.seen() && with.turnOnly && with.side == RoamSteer.RIGHT && with.novelty == 1.0
                && without.side == RoamSteer.STRAIGHT && nov.at(60) < 0.05 && nov.at(0) < 0.05
                ? null : "here=" + here.novelty + " with=" + with + " without=" + without
                + " at(60)=" + (nov == null ? "null" : nov.at(60) + " at(-60)=" + nov.at(-60));
    }

    private static String combine() {
        ExploreTuning t = tuning();
        PlaceMemory m = new PlaceMemory(t);
        RoamSteer.Novelty grid = b -> b > 0 ? 0.4 : 1.0;
        RoamSteer.Novelty none = m.steer(null, 0, true, Double.NaN, 0);
        RoamSteer.Novelty gridOnly = m.steer(grid, 0, true, Double.NaN, 0);
        RoamSteer.Novelty familiar = m.steer(grid, 0, true, 0.2, 0);
        RoamSteer.Novelty unusable = m.steer(null, Double.NaN, false, 0.2, 0);
        String err = "";
        if (none != null) {
            err += " no place and no grid should be null;";
        }
        if (gridOnly == null || gridOnly.at(10) != 0.4 || gridOnly.at(-10) != 1.0) {
            err += " grid only should be the grid;";
        }
        if (familiar == null || familiar.at(10) != 0.2 || familiar.at(-10) != 0.2 || familiar.at(-90) != 1.0) {
            err += " in view should be min(grid, place), out of view the grid;";
        }
        if (unusable == null || unusable.at(10) != 0.2 || !Double.isNaN(unusable.at(90))) {
            err += " unusable heading: in view the look, out of view unknown;";
        }
        // Left half blocked and never seen, right half open and familiar: the steer never takes the blocked side.
        float[] b = new float[Openness.BINS];
        for (int i = 0; i < b.length; i++) {
            b[i] = i < 8 ? 0.05f : 0.9f;
        }
        RoamSteer.Plan p = new RoamSteer(t).plan(new Openness.Profile(b, 0.9f), Double.NaN, familiar);
        if (p == null || p.side == RoamSteer.LEFT) {
            err += " plan " + p + " went toward the blocked side;";
        }
        return err.isEmpty() ? null : err;
    }

    private static String note() {
        PlaceMemory m = new PlaceMemory(tuning());
        m.look(scene(1), null, 0, 0);
        PlaceMemory.Match again = m.look(scene(1), null, 0, 4 * MIN + 20000);
        String n = again.note();
        return n.matches("place: seen before \\(sim \\d\\.\\d\\d, \\d+ min ago\\)") && n.contains("4 min ago")
                ? null : n;
    }

    private static String defaults() {
        ExploreTuning t = tuning();
        return t.placeFadeMs == 30 * MIN && t.placeMax == 300 && t.placeHeadingDeg == 45
                && t.placeSimLow < t.placeSeenSim && t.placeSeenSim < t.placeSimHigh
                && t.placeRecordMs * t.placeMax >= t.placeFadeMs
                ? null : "fade=" + t.placeFadeMs + " max=" + t.placeMax + " heading=" + t.placeHeadingDeg;
    }
}
