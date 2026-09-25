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
 * U3's on-robot gate (explore nav plan U3, KTD3), run on the host against two
 * real frames from the robot: open floor with a doorway in the middle (a plant
 * on the left, a chair on the right) and a plain wall 2-3 ft away. The frames
 * are the owner's and private (never in git); the test skips without them.
 * Each is sampled the way ExploreCamera.scoreOpenness does it: decoded at
 * 160x120, the floor band the rows below the horizon at that size, the whole
 * frame halved to 80x60. Prints PASS/FAIL lines of numbers only, never pixels.
 *
 * Usage: OpennessGate open-floor-doorway.jpg wall-2ft.jpg
 */
public final class OpennessGate {
    /** Bins across the doorway (x ~305..415 of 640), the plant (~100..200) and the chair's base (~480..600). */
    private static final int[] DOORWAY = {7, 10};
    private static final int[] PLANT = {1, 4};
    private static final int[] CHAIR = {12, 14};
    /** How far the doorway must clear the wall and the plant and chair. */
    private static final float MARGIN = 0.3f;
    private static final List<Detection> NONE = Collections.<Detection>emptyList();

    public static void main(String[] args) throws Exception {
        Openness.Frame[] open = frames(args[0]);
        Openness.Frame[] wall = frames(args[1]);
        Openness o = new Openness();
        o.score(open[0], open[1], NONE, 1000, true);
        o.floorDrivenOver(1000);
        check("the_dim_carpet_teaches", o.patches() >= 1 ? null : "patches " + o.patches());
        Openness.Profile p = o.score(open[0], open[1], NONE, 2000, false);
        Openness.Profile q = o.score(wall[0], wall[1], NONE, 3000, false);
        float doorway = min(p.bins, DOORWAY);
        float wallHi = max(q.bins, 0, Openness.BINS - 1);
        float things = Math.max(max(p.bins, PLANT[0], PLANT[1]), max(p.bins, CHAIR[0], CHAIR[1]));
        check("the_open_frame_is_trusted_once_taught", p.confidence >= 0.6f ? null : "confidence " + p.confidence);
        check("the_wall_scores_clearly_below_the_doorway",
                doorway >= wallHi + MARGIN ? null : "doorway " + doorway + " wall " + wallHi + "\n" + p + "\n" + q);
        check("the_plant_and_chair_stay_below_the_doorway",
                doorway >= things + MARGIN ? null : "doorway " + doorway + " plant/chair " + things + "\n" + p);
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
