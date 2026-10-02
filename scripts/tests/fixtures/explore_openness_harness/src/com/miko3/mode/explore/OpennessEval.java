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
 * The offline openness evaluation's JVM side (scripts/eval-explore-openness.py):
 * scores each labelled frame with the real Openness, sampled as
 * ExploreCamera.scoreOpenness samples it (as OpennessGate.frames does, but
 * finding the band's top by reflection so an older Openness without BAND_TOP
 * can be measured too, with --src), under four teaching conditions, and prints
 * one line per frame and condition:
 * "condition path confidence bin0 .. bin15". Then "TIME ms" (mean per score
 * on this host, after a warm-up). Numbers only, never pixels.
 *
 * Conditions: untaught; self (taught from the frame itself, then scored);
 * carried (one model through all frames in order, each scored then taught);
 * walltaught (taught first from the frames named after "--walls", as the
 * robot was on 2026-10-01 when it faced a plain wall, stood, then drove a
 * short way: the old scorer took the wall's colour for floor).
 *
 * Usage: OpennessEval frame.png... [--walls wall.png...]
 */
public final class OpennessEval {
    private static final List<Detection> NONE = Collections.<Detection>emptyList();

    public static void main(String[] args) throws Exception {
        int sep = Arrays.asList(args).indexOf("--walls");
        String[] walls = sep < 0 ? new String[0] : Arrays.copyOfRange(args, sep + 1, args.length);
        args = sep < 0 ? args : Arrays.copyOf(args, sep);
        Openness.Frame[][] frames = new Openness.Frame[args.length][];
        for (int i = 0; i < args.length; i++) {
            frames[i] = frames(args[i]);
        }
        Openness wallTaught = new Openness();
        for (int i = 0; i < walls.length; i++) {
            Openness.Frame[] f = frames(walls[i]);
            wallTaught.score(f[0], f[1], NONE, 1 + i, true);
            wallTaught.floorDrivenOver(1 + i);
        }
        System.out.println("WALLPATCHES " + wallTaught.patches());
        Openness carried = new Openness();
        for (int i = 0; i < args.length; i++) {
            Openness.Frame[] f = frames[i];
            long t = 1000L * (i + 1);
            System.out.println("untaught " + args[i] + " " + numbers(new Openness().score(f[0], f[1], NONE, t, false)));
            Openness self = new Openness();
            self.score(f[0], f[1], NONE, t, true);
            self.floorDrivenOver(t);
            System.out.println("self " + args[i] + " " + numbers(self.score(f[0], f[1], NONE, t + 1, false)));
            System.out.println("carried " + args[i] + " " + numbers(carried.score(f[0], f[1], NONE, t, true)));
            carried.floorDrivenOver(t);
            System.out.println("walltaught " + args[i] + " " + numbers(wallTaught.score(f[0], f[1], NONE, t, false)));
        }
        if (args.length > 0) {
            Openness o = new Openness();
            o.score(frames[0][0], frames[0][1], NONE, 1, true);
            o.floorDrivenOver(1);
            for (int r = 0; r < 20; r++) {
                for (Openness.Frame[] f : frames) {
                    o.score(f[0], f[1], NONE, 2, false);
                }
            }
            int reps = 50;
            long t0 = System.nanoTime();
            for (int r = 0; r < reps; r++) {
                for (Openness.Frame[] f : frames) {
                    o.score(f[0], f[1], NONE, 2, false);
                }
            }
            double ms = (System.nanoTime() - t0) / 1e6 / reps / frames.length;
            System.out.println(String.format(java.util.Locale.US, "TIME %.3f", ms));
        }
    }

    /** As ExploreCamera samples a frame: 160x120, the band from its top down, the whole halved to 80x60. */
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
        int first = Math.min(h - 1, (int) (bandTop() * h));
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

    /** Openness.BAND_TOP, or HORIZON for an older Openness whose band started there. */
    private static float bandTop() throws Exception {
        try {
            return Openness.class.getDeclaredField("BAND_TOP").getFloat(null);
        } catch (NoSuchFieldException e) {
            return Openness.class.getDeclaredField("HORIZON").getFloat(null);
        }
    }

    private static String numbers(Openness.Profile p) {
        return p.toString().replace("confidence ", "").replace(" bins", "");
    }
}
