package com.miko3.mode.explore;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Host JVM timing of OnnxRecognizer's Java stages over a real model output
 * (scripts/bench-explore-detector.py bench --java), not a test: the output
 * copy as OnnxTensor.getFloatBuffer() does it (a fresh heap FloatBuffer filled
 * from the native one) plus OnnxRecognizer's get() into its reused float[];
 * the decode (YoloeDecoder.decode or decodeTopK); and the preprocessing (the
 * per-pixel CHW float loop, or for an RGBA-input model a single bulk byte copy
 * standing in for Bitmap.copyPixelsToBuffer).
 *
 * Args: names.txt output.bin raw|topk lastDim width height minScore chw|rgba.
 * Prints "TIMING key value" lines (median ms) and "DET name score x0 y0 x1 y1".
 */
public final class DetectorTiming {
    private static final int WARMUP = 20;
    private static final int RUNS = 60;

    public static void main(String[] args) throws IOException {
        List<String> names = new ArrayList<String>();
        for (String line : Files.readAllLines(Paths.get(args[0]), StandardCharsets.UTF_8)) {
            if (!line.trim().isEmpty()) {
                names.add(line.trim());
            }
        }
        byte[] raw = Files.readAllBytes(Paths.get(args[1]));
        final boolean topk = "topk".equals(args[2]);
        final int last = Integer.parseInt(args[3]);
        final int width = Integer.parseInt(args[4]);
        final int height = Integer.parseInt(args[5]);
        final float minScore = Float.parseFloat(args[6]);
        final boolean rgba = args.length > 7 && "rgba".equals(args[7]);

        final ByteBuffer nativeOut = ByteBuffer.allocateDirect(raw.length).order(ByteOrder.nativeOrder());
        FloatBuffer le = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        while (le.hasRemaining()) {
            nativeOut.putFloat(le.get());
        }
        nativeOut.rewind();
        final float[] values = new float[raw.length / 4];
        final YoloeDecoder decoder = new YoloeDecoder(names.toArray(new String[0]), width, height);

        double copy = median(new Runnable() {
            @Override
            public void run() {
                FloatBuffer src = nativeOut.asFloatBuffer();
                FloatBuffer heap = FloatBuffer.allocate(src.capacity());
                heap.put(src);
                heap.rewind();
                heap.get(values);
            }
        });
        double decode = median(new Runnable() {
            @Override
            public void run() {
                if (topk) {
                    decoder.decodeTopK(values, last, minScore, 0.5f, 20);
                } else {
                    decoder.decode(values, last, minScore, 0.5f, 20);
                }
            }
        });
        final int plane = width * height;
        final int[] pixels = new int[plane];
        for (int i = 0; i < plane; i++) {
            pixels[i] = 0xff000000 | ((i * 7919) & 0xffffff);
        }
        final FloatBuffer chw = ByteBuffer.allocateDirect(4 * 3 * plane).order(ByteOrder.nativeOrder()).asFloatBuffer();
        final byte[] rgbaBytes = new byte[4 * plane];
        final ByteBuffer rgbaIn = ByteBuffer.allocateDirect(4 * plane);
        double prep = median(new Runnable() {
            @Override
            public void run() {
                if (rgba) {
                    rgbaIn.rewind();
                    rgbaIn.put(rgbaBytes);
                    return;
                }
                for (int i = 0; i < plane; i++) {
                    int p = pixels[i];
                    chw.put(i, ((p >> 16) & 0xff) / 255f);
                    chw.put(plane + i, ((p >> 8) & 0xff) / 255f);
                    chw.put(2 * plane + i, (p & 0xff) / 255f);
                }
            }
        });
        System.out.println(String.format(Locale.US, "TIMING copy_ms %.3f", copy));
        System.out.println(String.format(Locale.US, "TIMING decode_ms %.3f", decode));
        System.out.println(String.format(Locale.US, "TIMING prep_ms %.3f", prep));
        List<Detection> found = topk ? decoder.decodeTopK(values, last, minScore, 0.5f, 20)
                : decoder.decode(values, last, minScore, 0.5f, 20);
        for (Detection d : found) {
            System.out.println(String.format(Locale.US, "DET %s %.4f %.4f %.4f %.4f %.4f",
                    d.label, d.score, d.x0, d.y0, d.x1, d.y1));
        }
    }

    private static double median(Runnable r) {
        for (int i = 0; i < WARMUP; i++) {
            r.run();
        }
        long[] t = new long[RUNS];
        for (int i = 0; i < RUNS; i++) {
            long a = System.nanoTime();
            r.run();
            t[i] = System.nanoTime() - a;
        }
        return DetectorStages.percentile(t, 50) / 1e6;
    }
}
