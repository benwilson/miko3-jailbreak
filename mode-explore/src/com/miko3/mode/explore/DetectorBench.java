package com.miko3.mode.explore;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.miko3.shared.HttpUtil;

/**
 * Explore's detector bench (detector speed plan): with debug.miko3.explore.bench
 * set, ModeApp runs this at startup instead of the wander. It never opens the
 * camera, never asks for the drive lease and never moves: it times each
 * configured provider/model over JPEG frames, the same decode and detect a look
 * does, and logs p50/p95 per stage. scripts/qa-detector-bench.py sets the
 * properties, starts Explore, collects the lines and stops it again.
 *
 * Frames: JPEGs pushed to the app's external files dir bench/ when there are
 * any, else the bundled assets/bench/. Models: every shipped detector*.onnx
 * asset, plus detector*.onnx files pushed to that same dir (a speed-only check
 * of a variant that is not shipped; only the bench loads pushed models).
 *
 * Log lines (tag ExploreBench), parsed by qa-detector-bench.py:
 *   bench start frames=4 source=assets rounds=5 configs=cpu/detector.onnx,...
 *   bench detections ep=cpu model=detector.onnx frame=wall.jpg: [...]
 *   bench result ep=cpu model=detector.onnx frames=4 rounds=5 load_ms=812 first_ms=1650 total=p50/p95 decode=...
 *   bench error ep=xnnpack model=detector-int8.onnx: ...
 *   bench done in 123456 ms
 */
final class DetectorBench implements Runnable {
    static final String TAG = "ExploreBench";
    static final String DIR = "bench";
    /** The recognizer's unsure floor default (ExploreTuning.unsureFloor). */
    private static final float MIN_SCORE = 0.2f;

    private final Context context;
    private volatile boolean cancelled;
    private Thread thread;

    DetectorBench(Context context) {
        this.context = context;
    }

    static boolean requested() {
        return DetectorConfig.benchRequested(DetectorConfig.systemProperty(DetectorConfig.BENCH_PROPERTY));
    }

    void start() {
        thread = new Thread(this, "explore-detector-bench");
        thread.start();
    }

    /** Stops after the current look (a running model cannot be interrupted). */
    void cancel() {
        cancelled = true;
    }

    @Override
    public void run() {
        long start = SystemClock.elapsedRealtime();
        try {
            File pushed = context.getExternalFilesDir(DIR);
            List<String> frameNames = new ArrayList<String>();
            List<byte[]> frames = new ArrayList<byte[]>();
            String source = loadFrames(pushed, frameNames, frames);
            List<String> models = models(pushed);
            int rounds = DetectorConfig.benchRounds(
                    DetectorConfig.systemProperty(DetectorConfig.BENCH_ROUNDS_PROPERTY));
            List<DetectorConfig> configs = DetectorConfig.benchConfigs(
                    DetectorConfig.systemProperty(DetectorConfig.BENCH_CONFIGS_PROPERTY), models);
            StringBuilder list = new StringBuilder();
            for (DetectorConfig c : configs) {
                list.append(list.length() == 0 ? "" : ",").append(c.ep).append('/').append(c.model);
            }
            Log.i(TAG, "bench start frames=" + frames.size() + " source=" + source + " rounds=" + rounds
                    + " configs=" + list + " (no camera)");
            if (frames.isEmpty()) {
                Log.w(TAG, "bench error: no frames");
            }
            for (DetectorConfig c : configs) {
                if (cancelled || frames.isEmpty()) {
                    break;
                }
                runOne(c, frameNames, frames, rounds, pushed);
            }
        } catch (Throwable e) {
            Log.w(TAG, "bench error: " + e);
        }
        Log.i(TAG, "bench " + (cancelled ? "cancelled" : "done") + " in "
                + (SystemClock.elapsedRealtime() - start) + " ms");
    }

    private void runOne(DetectorConfig config, List<String> names, List<byte[]> frames, int rounds, File pushed) {
        long t0 = SystemClock.elapsedRealtime();
        OnnxRecognizer r;
        try {
            r = new OnnxRecognizer(context, MIN_SCORE, config, pushed);
        } catch (Throwable e) {
            Log.w(TAG, "bench error " + config + ": " + e);
            return;
        }
        try {
            long loadMs = SystemClock.elapsedRealtime() - t0;
            if (!r.loaded.ep.equals(config.ep) || !r.loaded.model.equals(config.model)) {
                Log.w(TAG, "bench error " + config + ": fell back to " + r.loaded);
                return;
            }
            BitmapFactory.Options decode = new BitmapFactory.Options();
            decode.inPreferredConfig = Bitmap.Config.ARGB_8888;
            decode.inMutable = true;
            DetectorStages.Summary summary = new DetectorStages.Summary();
            long firstMs = -1;
            // Round 0 warms up and logs what each frame shows; rounds 1..n are timed.
            for (int round = 0; round <= rounds && !cancelled; round++) {
                for (int i = 0; i < frames.size() && !cancelled; i++) {
                    byte[] jpeg = frames.get(i);
                    long a = System.nanoTime();
                    Bitmap frame = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, decode);
                    if (frame == null) {
                        continue;
                    }
                    long decodeNs = System.nanoTime() - a;
                    decode.inBitmap = frame;
                    List<Detection> found = r.detect(frame);
                    DetectorStages s = r.stages().copy();
                    s.set(DetectorStages.DECODE, decodeNs);
                    if (round == 0) {
                        if (firstMs < 0) {
                            firstMs = s.totalNanos() / 1000000L;
                        }
                        Log.i(TAG, "bench detections " + config + " frame=" + names.get(i) + ": " + found);
                    } else {
                        summary.add(s);
                    }
                }
            }
            Log.i(TAG, "bench result " + config + " frames=" + frames.size() + " rounds=" + rounds
                    + " looks=" + summary.size() + " load_ms=" + loadMs + " first_ms=" + firstMs + " "
                    + summary.line());
        } catch (Throwable e) {
            Log.w(TAG, "bench error " + config + ": " + e);
        } finally {
            r.close();
        }
    }

    /** Pushed JPEGs if there are any, else the bundled ones; returns which. */
    private String loadFrames(File pushed, List<String> names, List<byte[]> frames) throws IOException {
        String[] files = pushed == null ? null : pushed.list();
        if (files != null) {
            Arrays.sort(files);
            for (String f : files) {
                if (f.toLowerCase(java.util.Locale.US).endsWith(".jpg")) {
                    java.io.InputStream in = new java.io.FileInputStream(new File(pushed, f));
                    try {
                        frames.add(HttpUtil.readAll(in));
                    } finally {
                        in.close();
                    }
                    names.add(f);
                }
            }
            if (!frames.isEmpty()) {
                return "pushed";
            }
        }
        String[] bundled = context.getAssets().list(DIR);
        if (bundled != null) {
            Arrays.sort(bundled);
            for (String f : bundled) {
                if (f.endsWith(".jpg")) {
                    frames.add(HttpUtil.readAssetBytes(context, DIR + "/" + f));
                    names.add(f);
                }
            }
        }
        return "assets";
    }

    /** Shipped detector*.onnx assets, the shipped default first, then pushed ones. */
    private List<String> models(File pushed) throws IOException {
        List<String> out = new ArrayList<String>();
        String[] assets = context.getAssets().list("");
        if (assets != null) {
            for (String a : assets) {
                if (DetectorConfig.isModelName(a)) {
                    out.add(a);
                }
            }
        }
        String[] files = pushed == null ? null : pushed.list();
        if (files != null) {
            for (String f : files) {
                if (DetectorConfig.isModelName(f) && !out.contains(f)) {
                    out.add(f);
                }
            }
        }
        Collections.sort(out);
        if (out.remove(DetectorConfig.DEFAULT_MODEL)) {
            out.add(0, DetectorConfig.DEFAULT_MODEL);
        }
        return out;
    }
}
