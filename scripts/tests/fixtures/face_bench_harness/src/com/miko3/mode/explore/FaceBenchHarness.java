package com.miko3.mode.explore;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Prints what scripts/face-bench.py's numpy mirror must reproduce (U9).
 *
 *   --align IN.raw W H x0 y0 ... x4 y4 WARP.raw ARGB.raw
 *       FaceAlign.fit on the landmarks ("FIT m0..m5" or "FIT null"), then the
 *       float warp (little-endian float32 RGB) and FaceAlign.toArgb of it
 *       (little-endian int32 ARGB), 112x112 each.
 *   --quality IN.raw W H
 *       FaceQuality.meanLuma, laplacianVariance and table.
 *
 * IN.raw is W*H little-endian int32 ARGB pixels.
 */
public final class FaceBenchHarness {
    private FaceBenchHarness() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 16 && args[0].equals("--align")) {
            int w = Integer.parseInt(args[2]);
            int h = Integer.parseInt(args[3]);
            float[] lm = new float[10];
            for (int i = 0; i < 10; i++) {
                lm[i] = Float.parseFloat(args[4 + i]);
            }
            double[] m = FaceAlign.fit(lm);
            if (m == null) {
                System.out.println("FIT null");
                return;
            }
            StringBuilder sb = new StringBuilder("FIT");
            for (double v : m) {
                sb.append(' ').append(v);
            }
            System.out.println(sb);
            float[] rgb = FaceAlign.warp(readArgb(args[1], w * h), w, h, m);
            ByteBuffer f = ByteBuffer.allocate(rgb.length * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (float v : rgb) {
                f.putFloat(v);
            }
            Files.write(Paths.get(args[14]), f.array());
            int[] argb = FaceAlign.toArgb(rgb);
            ByteBuffer a = ByteBuffer.allocate(argb.length * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (int v : argb) {
                a.putInt(v);
            }
            Files.write(Paths.get(args[15]), a.array());
            return;
        }
        if (args.length == 4 && args[0].equals("--quality")) {
            int w = Integer.parseInt(args[2]);
            int h = Integer.parseInt(args[3]);
            int[] argb = readArgb(args[1], w * h);
            System.out.println("LUMA " + FaceQuality.meanLuma(argb));
            System.out.println("LAPVAR " + FaceQuality.laplacianVariance(argb, w, h));
            StringBuilder sb = new StringBuilder("TABLE");
            for (int v : FaceQuality.table(argb)) {
                sb.append(' ').append(v);
            }
            System.out.println(sb);
            return;
        }
        System.err.println("usage: --align IN.raw W H x0 y0 .. x4 y4 WARP.raw ARGB.raw | --quality IN.raw W H");
        System.exit(2);
    }

    private static int[] readArgb(String path, int n) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(Paths.get(path))).order(ByteOrder.LITTLE_ENDIAN);
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            out[i] = b.getInt();
        }
        return out;
    }
}
