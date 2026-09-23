package com.miko3.mode.explore;

import android.content.Context;
import android.graphics.Bitmap;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

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
    private final String inputName;
    private final YoloeDecoder decoder;
    private final float minScore;
    private final float[] chw = new float[3 * INPUT_WIDTH * INPUT_HEIGHT];
    private final int[] pixels = new int[INPUT_WIDTH * INPUT_HEIGHT];

    /** @param minScore detections below this are dropped (the unsure floor, KTD6) */
    OnnxRecognizer(Context context, float minScore) throws IOException, OrtException {
        this.minScore = minScore;
        String[] names = readVocabulary(context);
        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setIntraOpNumThreads(THREADS);
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        session = env.createSession(readAsset(context, "detector.onnx"), options);
        inputName = session.getInputNames().iterator().next();
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
            chw[i] = ((p >> 16) & 0xff) / 255f;
            chw[plane + i] = ((p >> 8) & 0xff) / 255f;
            chw[2 * plane + i] = (p & 0xff) / 255f;
        }
        long[] shape = {1, 3, INPUT_HEIGHT, INPUT_WIDTH};
        OnnxTensor input = OnnxTensor.createTensor(env, FloatBuffer.wrap(chw), shape);
        try {
            OrtSession.Result result = session.run(Collections.singletonMap(inputName, input));
            try {
                OnnxTensor out = (OnnxTensor) result.get(0);
                long[] outShape = out.getInfo().getShape();
                FloatBuffer buf = out.getFloatBuffer();
                float[] values = new float[buf.remaining()];
                buf.get(values);
                return decoder.decode(values, (int) outShape[2], minScore, 0.5f, 20);
            } finally {
                result.close();
            }
        } finally {
            input.close();
        }
    }

    @Override
    public void close() {
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

    private static byte[] readAsset(Context context, String name) throws IOException {
        InputStream in = context.getAssets().open(name);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[1 << 16];
            for (int n = in.read(buf); n > 0; n = in.read(buf)) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }
}
