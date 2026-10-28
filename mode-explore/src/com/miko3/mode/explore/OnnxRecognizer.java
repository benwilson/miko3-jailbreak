package com.miko3.mode.explore;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;

import com.miko3.shared.HttpUtil;

/**
 * The on-device recognizer (KTD2): the YOLOE detector exported with the mode's
 * own vocabulary (assets/detector.onnx, assets/vocabulary.txt), run by ONNX
 * Runtime on the CPU. Camera frames (640x480) are scaled to the model's input
 * size, which is read from the model.
 *
 * Off by default, two switches (DetectorConfig; detector speed plan): another
 * execution provider (XNNPACK, NNAPI), falling back to the CPU with a log line
 * when it fails; and a variant model asset built by
 * scripts/bench-explore-detector.py. The variant's input and output say how to
 * feed and read it: a uint8 RGBA input is filled by one copyPixelsToBuffer
 * instead of the per-pixel float loop, and a "detections" output is the top K
 * anchors already reduced in the graph (YoloeDecoder.decodeTopK).
 *
 * Not thread-safe: one caller (the camera worker) at a time.
 */
final class OnnxRecognizer implements Recognizer {
    private static final String TAG = "ExploreDetector";
    /** Two of the four cores: the camera HAL and the brain need the rest. */
    private static final int THREADS = 2;
    /** The top-k variants' output name (scripts/bench-explore-detector.py make_topk). */
    static final String TOPK_OUTPUT = "detections";

    private final OrtEnvironment env;
    private final OrtSession session;
    private final YoloeDecoder decoder;
    private final float minScore;
    /** The model's input size, read from the model (scripts/export-explore-detector.py --imgsz). */
    private final int width;
    private final int height;
    /** The input, CHW floats 0..1, in a direct buffer the input tensor shares, so
     * each frame is written in place with no per-frame copy or allocation. Null
     * for an RGBA-input variant, which uses rgba instead. */
    private final FloatBuffer chw;
    /** An RGBA-input variant's input: ARGB_8888 pixels as Bitmap.copyPixelsToBuffer writes them. */
    private final ByteBuffer rgba;
    private final OnnxTensor input;
    private final Map<String, OnnxTensor> inputs;
    private final Set<String> outputs;
    private final boolean topk;
    private final int[] pixels;
    /** Frames not already the input size are drawn into this, reused. */
    private Bitmap scaled;
    private Canvas canvas;
    private Rect whole;
    private final Paint filter = new Paint(Paint.FILTER_BITMAP_FLAG);
    /** The output, reused: several MB (4 + names + 32 rows x anchors floats) a frame. */
    private float[] values;
    /** The last detect()'s stage times; ExploreCamera adds the JPEG decode. */
    private final DetectorStages stages = new DetectorStages();
    /** What actually loaded, after any fallback. */
    final DetectorConfig loaded;

    /** @param minScore detections below this are dropped (the unsure floor, KTD6) */
    OnnxRecognizer(Context context, float minScore) throws IOException, OrtException {
        this(context, minScore, DetectorConfig.fromSystem(), null);
    }

    /**
     * @param config    the provider and model asked for; DetectorConfig.attempts() lists the fallbacks
     * @param pushedDir the bench's directory of pushed models, checked before the
     *                  assets; null outside the bench (only shipped models load then)
     */
    OnnxRecognizer(Context context, float minScore, DetectorConfig config, File pushedDir)
            throws IOException, OrtException {
        this.minScore = minScore;
        String[] names = readVocabulary(context);
        env = OrtEnvironment.getEnvironment();
        OrtSession made = null;
        DetectorConfig used = null;
        Exception last = null;
        for (DetectorConfig attempt : config.attempts()) {
            try {
                made = create(context, attempt, pushedDir);
                used = attempt;
                break;
            } catch (IOException | OrtException | RuntimeException e) {
                // RuntimeException too: an unavailable provider can throw unchecked.
                Log.w(TAG, "detector " + attempt + " failed to load (" + e + ")"
                        + (attempt.isDefault() ? "" : "; falling back"));
                last = e;
            }
        }
        if (made == null) {
            if (last instanceof IOException) {
                throw (IOException) last;
            }
            if (last instanceof OrtException) {
                throw (OrtException) last;
            }
            throw (RuntimeException) last;
        }
        session = made;
        loaded = used;
        if (!loaded.isDefault()) {
            Log.i(TAG, "detector " + loaded);
        }
        String inputName = session.getInputNames().iterator().next();
        TensorInfo info = (TensorInfo) session.getInputInfo().get(inputName).getInfo();
        long[] shape = info.getShape();
        if (info.type == OnnxJavaType.UINT8) {
            // [1, H, W, 4]: RGBA bytes, the resize (if any), transpose and 1/255 in the graph.
            height = (int) shape[1];
            width = (int) shape[2];
            rgba = ByteBuffer.allocateDirect(4 * width * height).order(ByteOrder.nativeOrder());
            chw = null;
            pixels = null;
            input = OnnxTensor.createTensor(env, rgba, new long[]{1, height, width, 4}, OnnxJavaType.UINT8);
        } else {
            height = (int) shape[2];
            width = (int) shape[3];
            chw = ByteBuffer.allocateDirect(4 * 3 * width * height).order(ByteOrder.nativeOrder()).asFloatBuffer();
            rgba = null;
            pixels = new int[width * height];
            input = OnnxTensor.createTensor(env, chw, new long[]{1, 3, height, width});
        }
        inputs = Collections.singletonMap(inputName, input);
        Set<String> outputNames = session.getOutputNames();
        topk = outputNames.contains(TOPK_OUTPUT);
        String outputName = topk ? TOPK_OUTPUT
                : outputNames.contains("output0") ? "output0" : outputNames.iterator().next();
        // Only the output the decoder reads comes back to Java.
        outputs = Collections.singleton(outputName);
        decoder = new YoloeDecoder(names, width, height);
    }

    private OrtSession create(Context context, DetectorConfig config, File pushedDir)
            throws IOException, OrtException {
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        try {
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            if (DetectorConfig.XNNPACK.equals(config.ep)) {
                // ONNX Runtime's XNNPACK guidance: XNNPACK's own thread pool does the
                // parallel work, so ORT's intra-op pool is just the calling thread and
                // does not spin. Two threads in all, as on the CPU provider: the speech
                // recogniser shares these four cores.
                options.setIntraOpNumThreads(1);
                options.addConfigEntry("session.intra_op.allow_spinning", "0");
                Map<String, String> xnnpack = new HashMap<String, String>();
                xnnpack.put("intra_op_num_threads", String.valueOf(THREADS));
                options.addXnnpack(xnnpack);
            } else {
                options.setIntraOpNumThreads(THREADS);
                if (DetectorConfig.NNAPI.equals(config.ep)) {
                    options.addNnapi();
                }
            }
            File pushed = pushedDir == null ? null : new File(pushedDir, config.model);
            if (pushed != null && pushed.isFile()) {
                return env.createSession(pushed.getPath(), options);
            }
            return env.createSession(HttpUtil.readAssetBytes(context, config.model), options);
        } finally {
            options.close();
        }
    }

    @Override
    public List<Detection> detect(Bitmap frame) throws OrtException {
        long t0 = System.nanoTime();
        if (frame.getWidth() != width || frame.getHeight() != height
                || (rgba != null && frame.getConfig() != Bitmap.Config.ARGB_8888)) {
            if (scaled == null) {
                scaled = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                canvas = new Canvas(scaled);
                whole = new Rect(0, 0, width, height);
            }
            canvas.drawBitmap(frame, null, whole, filter);
            frame = scaled;
        }
        if (rgba != null) {
            rgba.rewind();
            frame.copyPixelsToBuffer(rgba);
            rgba.rewind();
        } else {
            frame.getPixels(pixels, 0, width, 0, 0, width, height);
            int plane = width * height;
            for (int i = 0; i < plane; i++) {
                int p = pixels[i];
                chw.put(i, ((p >> 16) & 0xff) / 255f);
                chw.put(plane + i, ((p >> 8) & 0xff) / 255f);
                chw.put(2 * plane + i, (p & 0xff) / 255f);
            }
        }
        long t1 = System.nanoTime();
        OrtSession.Result result = session.run(inputs, outputs);
        try {
            long t2 = System.nanoTime();
            OnnxTensor out = (OnnxTensor) result.get(0);
            long[] outShape = out.getInfo().getShape();
            FloatBuffer buf = out.getFloatBuffer();
            if (values == null || values.length != buf.remaining()) {
                values = new float[buf.remaining()];
            }
            buf.get(values);
            long t3 = System.nanoTime();
            List<Detection> found = topk
                    ? decoder.decodeTopK(values, (int) outShape[2], minScore, 0.5f, 20)
                    : decoder.decode(values, (int) outShape[2], minScore, 0.5f, 20);
            stages.set(DetectorStages.PREP, t1 - t0);
            stages.set(DetectorStages.RUN, t2 - t1);
            stages.set(DetectorStages.COPY, t3 - t2);
            stages.set(DetectorStages.DETECT, System.nanoTime() - t3);
            return found;
        } finally {
            result.close();
        }
    }

    @Override
    public DetectorStages stages() {
        return stages;
    }

    @Override
    public void close() {
        input.close();
        try {
            session.close();
        } catch (OrtException ignored) {
            // Closing on exit; nothing to recover.
        }
    }

    static String[] readVocabulary(Context context) throws IOException {
        List<String> names = new ArrayList<String>();
        BufferedReader r = new BufferedReader(new InputStreamReader(
                context.getAssets().open("vocabulary.txt"), StandardCharsets.UTF_8));
        try {
            for (String line = r.readLine(); line != null; line = r.readLine()) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    names.add(line);
                }
            }
        } finally {
            r.close();
        }
        return names.toArray(new String[0]);
    }
}
