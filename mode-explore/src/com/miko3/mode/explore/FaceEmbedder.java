package com.miko3.mode.explore;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Collections;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtLoggingLevel;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;

/**
 * The SFace face embedding on the robot (on-device face recognition plan U3,
 * KTD1): assets/face_sface.onnx (OpenCV Zoo face_recognition_sface_2021dec,
 * fp32, Apache-2.0, licence beside it) run by the ONNX Runtime the mode
 * already bundles. No network is involved (R3).
 *
 * Input is an aligned FaceAlign crop (112x112 ARGB, brightened when dim), fed
 * as RGB 0..255 floats in NCHW order with no normalisation, since the model
 * normalises internally (FaceMatcher.input). The 128 outputs are L2-normalised,
 * so FaceMatcher compares embeddings by dot product.
 *
 * The model is ~38 MB, so it never passes through a Java byte array (plan
 * Risks: model load memory): the asset is streamed once into the app's files
 * directory, under a name carrying FaceMatcher.MODEL_ID so a new model gets a
 * new file, and the session is created from that path. The copy goes through
 * a temp file and a rename, so a half-written copy is never loaded; a copy
 * that still fails to load is deleted and copied again once.
 *
 * Like FaceCropper: loaded on first use (MEET time or migration, never
 * alongside the object detector), 2 intra-op threads, ALL_OPT, one reused
 * direct input buffer, freed on close(). Methods are synchronized so close()
 * never frees the session under a running embed.
 */
final class FaceEmbedder {
    private static final String TAG = "ExploreClaude";
    static final String MODEL = "face_sface.onnx";
    private static final String CACHED = "face_" + FaceMatcher.MODEL_ID + ".onnx";
    private static final int THREADS = 2;

    private final Context context;
    private OrtEnvironment env;
    private OrtSession session;
    private String inputName;
    private FloatBuffer chw;
    private float[] planes;
    /** Set once loading failed or close() ran: no more tries. */
    private boolean done;

    FaceEmbedder(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * The unit-length embedding (FaceMatcher.DIM floats) of an aligned
     * FaceMatcher.INPUT_SIDE square ARGB crop, or null when the model is
     * unavailable, the run failed, or the output is degenerate.
     */
    synchronized float[] embed(int[] alignedArgb) {
        if (alignedArgb == null || alignedArgb.length != FaceMatcher.INPUT_SIDE * FaceMatcher.INPUT_SIDE) {
            return null;
        }
        if (!load()) {
            return null;
        }
        long t0 = System.currentTimeMillis();
        FaceMatcher.input(alignedArgb, planes);
        chw.clear();
        chw.put(planes);
        chw.rewind();
        float[] raw;
        try {
            OnnxTensor tensor = OnnxTensor.createTensor(env, chw,
                    new long[]{1, 3, FaceMatcher.INPUT_SIDE, FaceMatcher.INPUT_SIDE});
            try {
                OrtSession.Result result = session.run(Collections.singletonMap(inputName, tensor));
                try {
                    FloatBuffer out = ((OnnxTensor) result.get(0)).getFloatBuffer();
                    raw = new float[out.remaining()];
                    out.get(raw);
                } finally {
                    result.close();
                }
            } finally {
                tensor.close();
            }
        } catch (OrtException | RuntimeException e) {
            Log.w(TAG, "face embedder run failed: " + e.getClass().getSimpleName());
            return null;
        }
        float[] unit = raw.length == FaceMatcher.DIM ? FaceMatcher.normalize(raw) : null;
        Log.i(TAG, "face embedder: " + (unit != null ? "ok" : "no embedding") + " in "
                + (System.currentTimeMillis() - t0) + " ms");
        return unit;
    }

    /** Frees the model; later embeds give null. ClaudeCuriosity.release() calls it. */
    synchronized void close() {
        done = true;
        closeSession();
        chw = null;
        planes = null;
    }

    /** Loads the model on first use. False when it can't be loaded (logged once). */
    private boolean load() {
        if (session != null) {
            return true;
        }
        if (done) {
            return false;
        }
        try {
            env = OrtEnvironment.getEnvironment();
            File model = new File(context.getFilesDir(), CACHED);
            try {
                session = open(model);
            } catch (OrtException | IOException | RuntimeException first) {
                // A damaged cached copy: copy it again, once.
                closeSession();
                if (!model.delete() && model.exists()) {
                    throw first;
                }
                session = open(model);
            }
            int side = FaceMatcher.INPUT_SIDE;
            chw = ByteBuffer.allocateDirect(4 * 3 * side * side).order(ByteOrder.nativeOrder()).asFloatBuffer();
            planes = new float[3 * side * side];
            return true;
        } catch (IOException | OrtException | RuntimeException e) {
            Log.w(TAG, "face embedder unavailable: " + e.getClass().getSimpleName());
            done = true;
            closeSession();
            return false;
        }
    }

    /** A session from the cached copy (copied from the asset first when missing), its shapes checked. */
    private OrtSession open(File model) throws IOException, OrtException {
        if (!model.isFile() || model.length() == 0) {
            copyAsset(model);
        }
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setIntraOpNumThreads(THREADS);
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        // SFace lists its initializers as graph inputs: a harmless warning per weight.
        options.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR);
        String path = model.getAbsolutePath();
        OrtSession s = env.createSession(path, options);
        try {
            inputName = checkShapes(s);
        } catch (OrtException | RuntimeException e) {
            s.close();
            throw e;
        }
        return s;
    }

    /** The image input's name, after checking it takes 1x3x112x112 and the output is 128 floats. */
    private static String checkShapes(OrtSession s) throws OrtException {
        String name = null;
        for (NodeInfo in : s.getInputInfo().values()) {
            long[] shape = ((TensorInfo) in.getInfo()).getShape();
            // Initializers listed as inputs have other shapes; the image is the 4-D one.
            if (shape.length == 4) {
                int side = FaceMatcher.INPUT_SIDE;
                if (shape[1] != 3 || shape[2] != side || shape[3] != side) {
                    throw new IllegalStateException("face embedder input is not 3x" + side + "x" + side);
                }
                name = in.getName();
                break;
            }
        }
        if (name == null) {
            throw new IllegalStateException("face embedder has no image input");
        }
        NodeInfo out = s.getOutputInfo().values().iterator().next();
        long[] shape = ((TensorInfo) out.getInfo()).getShape();
        if (shape.length == 0 || shape[shape.length - 1] != FaceMatcher.DIM) {
            throw new IllegalStateException("face embedder output is not " + FaceMatcher.DIM + " floats");
        }
        return name;
    }

    /** Streams the asset to dst through a temp file and a rename, never whole in memory. */
    private void copyAsset(File dst) throws IOException {
        File tmp = new File(dst.getPath() + ".tmp");
        byte[] buf = new byte[64 * 1024];
        try (InputStream in = context.getAssets().open(MODEL);
             FileOutputStream out = new FileOutputStream(tmp)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.getFD().sync();
        } catch (IOException e) {
            tmp.delete();
            throw e;
        }
        if (!tmp.renameTo(dst)) {
            tmp.delete();
            throw new IOException("could not place the face embedder model");
        }
    }

    private void closeSession() {
        if (session != null) {
            try {
                session.close();
            } catch (OrtException ignored) {
                // Closing; nothing to recover.
            }
            session = null;
        }
    }
}
