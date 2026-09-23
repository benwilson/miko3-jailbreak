package com.miko3.mode.explore;

import android.content.Context;
import android.graphics.Bitmap;

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

import com.miko3.shared.HttpUtil;

/**
 * The on-device recognizer (KTD2): the YOLOE detector exported with the mode's
 * own vocabulary (assets/detector.onnx, assets/vocabulary.txt), run by ONNX
 * Runtime on the CPU. Frames must be the model's input size (640x480); the
 * camera already delivers that.
 *
 * Not thread-safe: one caller (the camera worker) at a time.
 */
final class OnnxRecognizer implements Recognizer {
    static final int INPUT_WIDTH = 640;
    static final int INPUT_HEIGHT = 480;
    /** Two of the four cores: the camera HAL and the brain need the rest. */
    private static final int THREADS = 2;

    private final OrtEnvironment env;
    private final OrtSession session;
    private final YoloeDecoder decoder;
    private final float minScore;
    /** The input, CHW floats 0..1, in a direct buffer the input tensor shares, so
     * each frame is written in place with no per-frame copy or allocation. */
    private final FloatBuffer chw = ByteBuffer.allocateDirect(4 * 3 * INPUT_WIDTH * INPUT_HEIGHT)
            .order(ByteOrder.nativeOrder()).asFloatBuffer();
    private final OnnxTensor input;
    private final Map<String, OnnxTensor> inputs;
    private final int[] pixels = new int[INPUT_WIDTH * INPUT_HEIGHT];
    /** The output, reused: it is ~9.5 MB (377 x 6300 floats) a frame. */
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
        input = OnnxTensor.createTensor(env, chw, new long[]{1, 3, INPUT_HEIGHT, INPUT_WIDTH});
        inputs = Collections.singletonMap(session.getInputNames().iterator().next(), input);
        decoder = new YoloeDecoder(names, INPUT_WIDTH, INPUT_HEIGHT);
    }

    @Override
    public List<Detection> detect(Bitmap frame) throws OrtException {
        if (frame.getWidth() != INPUT_WIDTH || frame.getHeight() != INPUT_HEIGHT) {
            frame = Bitmap.createScaledBitmap(frame, INPUT_WIDTH, INPUT_HEIGHT, true);
        }
        frame.getPixels(pixels, 0, INPUT_WIDTH, 0, 0, INPUT_WIDTH, INPUT_HEIGHT);
        int plane = INPUT_WIDTH * INPUT_HEIGHT;
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
