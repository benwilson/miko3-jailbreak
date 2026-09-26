package com.miko3.mode.explore;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * U3's on-robot gate (explore nav plan U3, KTD3), run on the host against pairs
 * of real frames from the robot: open floor with a doorway in the middle (a
 * plant on the left, a chair on the right) and a plain wall 2-3 ft away. There
 * are two pairs from the same spots: dim (before the camera ran its own
 * exposure, U9) and bright (after). The frames are the owner's and private
 * (never in git); the test skips a pair without them. Each is sampled the way
 * ExploreCamera.scoreOpenness does it: decoded at 160x120, the floor band the
 * rows below the horizon at that size, the whole frame halved to 80x60. Prints
 * PASS/FAIL and INFO lines of numbers only, never pixels.
 *
 * Usage: OpennessGate label open.jpg wall.jpg doorwayFrom doorwayTo [label open.jpg wall.jpg from to]...
 * Each pair is taught from its own open frame. With two pairs, each pair's
 * taught model also scores the other pair's wall (light changed under him).
 */
public final class OpennessGate {
    /** Bins across the plant (~100..200 of 640) and the chair's base (~480..600); the doorway's bins come per pair. */
    private static final int[] PLANT = {1, 4};
    private static final int[] CHAIR = {12, 14};
    /** How far the doorway must clear the wall and the plant and chair. */
    private static final float MARGIN = 0.3f;
    /** Every bin of a wall 2-3 ft away reads at most this once the floor is taught. */
    private static final float WALL_MAX = 0.2f;
    /** A wall seen under the other pair's light must still never read open. */
    private static final float CROSS_WALL_MAX = 0.5f;
    private static final List<Detection> NONE = Collections.<Detection>emptyList();

    public static void main(String[] args) throws Exception {
        int pairs = args.length / 5;
        String[] labels = new String[pairs];
        Openness[] models = new Openness[pairs];
        Openness.Frame[][] walls = new Openness.Frame[pairs][];
        for (int i = 0; i < pairs; i++) {
            labels[i] = args[i * 5];
            Openness.Frame[] open = frames(args[i * 5 + 1]);
            walls[i] = frames(args[i * 5 + 2]);
            int[] doorway = {Integer.parseInt(args[i * 5 + 3]), Integer.parseInt(args[i * 5 + 4])};
            models[i] = new Openness();
            gate(labels[i], models[i], open, walls[i], doorway);
        }
        for (int i = 0; i < pairs; i++) {
            for (int j = 0; j < pairs; j++) {
                if (i == j) {
                    continue;
                }
                Openness.Profile q = models[i].score(walls[j][0], walls[j][1], NONE, 4000 + j, false);
                float hi = max(q.bins, 0, Openness.BINS - 1);
                System.out.println("INFO " + labels[i] + "_taught " + labels[j] + "_wall " + q);
                check(labels[i] + "_taught_" + labels[j] + "_wall_never_reads_open",
                        hi <= CROSS_WALL_MAX ? null : "wall " + hi + " " + q);
            }
        }
    }

    private static void gate(String label, Openness o, Openness.Frame[] open, Openness.Frame[] wall, int[] doorway) {
        o.score(open[0], open[1], NONE, 1000, true);
        o.floorDrivenOver(1000);
        check(label + "_carpet_teaches", o.patches() >= 1 ? null : "patches " + o.patches());
        Openness.Profile p = o.score(open[0], open[1], NONE, 2000, false);
        Openness.Profile q = o.score(wall[0], wall[1], NONE, 3000, false);
        System.out.println("INFO " + label + "_open " + p);
        System.out.println("INFO " + label + "_wall " + q);
        float door = min(p.bins, doorway);
        float wallHi = max(q.bins, 0, Openness.BINS - 1);
        float things = Math.max(max(p.bins, PLANT[0], PLANT[1]), max(p.bins, CHAIR[0], CHAIR[1]));
        check(label + "_open_frame_is_trusted_once_taught", p.confidence >= 0.6f ? null : "confidence " + p.confidence);
        check(label + "_wall_reads_blocked_in_every_bin", wallHi <= WALL_MAX ? null : "wall " + wallHi + " " + q);
        check(label + "_wall_scores_clearly_below_the_doorway",
                door >= wallHi + MARGIN ? null : "doorway " + door + " wall " + wallHi + " " + p + " / " + q);
        check(label + "_plant_and_chair_stay_below_the_doorway",
                door >= things + MARGIN ? null : "doorway " + door + " plant/chair " + things + " " + p);
    }

    static Openness.Frame[] frames(String path) throws Exception {
        BufferedImage src = ImageIO.read(new File(path));
        int w = 160;
        int h = 120;
        BufferedImage small = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = small.createGraphics();
        g.drawImage(src.getScaledInstance(w, h, Image.SCALE_AREA_AVERAGING), 0, 0, null);
        g.dispose();
        int[] px = small.getRGB(0, 0, w, h, null, 0, w);
        for (int i = 0; i < px.length; i++) {
            px[i] &= 0xffffff;
        }
        int first = Math.min(h - 1, (int) (Openness.HORIZON * h));
        Openness.Frame band = new Openness.Frame(Arrays.copyOfRange(px, first * w, h * w), w, h - first,
                (float) first / h, 1f);
        int hw = w / 2;
        int hh = h / 2;
        int[] half = new int[hw * hh];
        for (int y = 0; y < hh; y++) {
            for (int x = 0; x < hw; x++) {
                int r = 0;
                int gr = 0;
                int b = 0;
                for (int dy = 0; dy < 2; dy++) {
                    for (int dx = 0; dx < 2; dx++) {
                        int p = px[(y * 2 + dy) * w + x * 2 + dx];
                        r += (p >> 16) & 0xff;
                        gr += (p >> 8) & 0xff;
                        b += p & 0xff;
                    }
                }
                half[y * hw + x] = ((r / 4) << 16) | ((gr / 4) << 8) | (b / 4);
            }
        }
        return new Openness.Frame[] {new Openness.Frame(half, hw, hh, 0f, 1f), band};
    }

    private static float min(float[] v, int[] range) {
        float m = Float.MAX_VALUE;
        for (int i = range[0]; i <= range[1]; i++) {
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
