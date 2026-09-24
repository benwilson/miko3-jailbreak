package com.miko3.mode.explore;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

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
 * Not thread-safe: one caller (the camera worker) at a time.
 */
final class OnnxRecognizer implements Recognizer {
    /** Two of the four cores: the camera HAL and the brain need the rest. */
    private static final int THREADS = 2;

    private final OrtEnvironment env;
    private final OrtSession session;
    private final YoloeDecoder decoder;
    private final float minScore;
    /** The model's input size, read from the model (scripts/export-explore-detector.py --imgsz). */
    private final int width;
    private final int height;
    /** The input, CHW floats 0..1, in a direct buffer the input tensor shares, so
     * each frame is written in place with no per-frame copy or allocation. */
    private final FloatBuffer chw;
    private final OnnxTensor input;
    private final Map<String, OnnxTensor> inputs;
    private final int[] pixels;
    /** Frames not already the input size are drawn into this, reused. */
    private Bitmap scaled;
    private Canvas canvas;
    private Rect whole;
    private final Paint filter = new Paint(Paint.FILTER_BITMAP_FLAG);
    /** The output, reused: several MB (4 + names + 32 rows x anchors floats) a frame. */
    private float[] values;

    /** @param minScore detections below this are dropped (the unsure floor, KTD6) */
    OnnxRecognizer(Context context, float minScore) throws IOException, OrtException {
        this.minScore = minScore;
        String[] names = readVocabulary(context);
        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setIntraOpNumThreads(THREADS);
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        session = env.createSession(HttpUtil.readAssetBytes(context, "detector.onnx"), options);
        String inputName = session.getInputNames().iterator().next();
        long[] shape = ((TensorInfo) session.getInputInfo().get(inputName).getInfo()).getShape();
        height = (int) shape[2];
        width = (int) shape[3];
        chw = ByteBuffer.allocateDirect(4 * 3 * width * height).order(ByteOrder.nativeOrder()).asFloatBuffer();
        pixels = new int[width * height];
        input = OnnxTensor.createTensor(env, chw, new long[]{1, 3, height, width});
        inputs = Collections.singletonMap(inputName, input);
        decoder = new YoloeDecoder(names, width, height);
    }

    @Override
    public List<Detection> detect(Bitmap frame) throws OrtException {
        if (frame.getWidth() != width || frame.getHeight() != height) {
            if (scaled == null) {
                scaled = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                canvas = new Canvas(scaled);
                whole = new Rect(0, 0, width, height);
            }
            canvas.drawBitmap(frame, null, whole, filter);
            frame = scaled;
        }
        frame.getPixels(pixels, 0, width, 0, 0, width, height);
        int plane = width * height;
        for (int i = 0; i < plane; i++) {
            int p = pixels[i];
            chw.put(i, ((p >> 16) & 0xff) / 255f);
            chw.put(plane + i, ((p >> 8) & 0xff) / 255f);
            chw.put(2 * plane + i, (p & 0xff) / 255f);
        }
        OrtSession.Result result = session.run(inputs);
        try {
            OnnxTensor out = (OnnxTensor) result.get(0);
            long[] outShape = out.getInfo().getShape();
            FloatBuffer buf = out.getFloatBuffer();
            if (values == null || values.length != buf.remaining()) {
                values = new float[buf.remaining()];
            }
            buf.get(values);
            return decoder.decode(values, (int) outShape[2], minScore, 0.5f, 20);
        } finally {
            result.close();
        }
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
