package com.miko3.mode.explore;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import com.miko3.shared.HttpUtil;

/**
 * FaceCrop on the robot (explore on Claude plan KTD5): the YuNet face detector
 * (assets/face_yunet.onnx, OpenCV Zoo 2026may, MIT) run by the ONNX Runtime the
 * mode already bundles. Android's FaceDetector replaced it: it missed an
 * obvious, well-lit frontal face in glasses ~115 px wide in a 640x480 frame.
 *
 * The 2026may model takes any size, so the input is a fixed
 * YuNetDecoder.INPUT_W x INPUT_H (320x256, on-device face recognition KTD2), not
 * read from the model: the 640x480 frame is halved to 320x240 and padded black
 * below, as BGR 0..255 floats, the way OpenCV's FaceDetectorYN feeds it.
 * YuNetDecoder reads the faces and their five landmarks back, scaled to frame
 * pixels, and the largest one centred inside the person box is cut out 1.6x
 * larger as a 224 px JPEG. locate() also hands back that face's box and
 * landmarks, to straighten it before matching. With no face there is no crop at
 * all: nothing that isn't a detected face is matched or stored (R10). See
 * FaceCrop for the geometry.
 *
 * The model loads on the first crop (MEET time only) and stays until close().
 * Called on the curiosity adapter's worker thread, never the brain's;
 * synchronized so close() never frees the session under a running crop.
 */
final class FaceCropper implements FaceCrop {
    private static final String TAG = "ExploreClaude";
    static final String MODEL = "face_yunet.onnx";
    /** A small model run only at MEET time: one core is plenty, two keeps it quick. */
    private static final int THREADS = 2;
    /** OpenCV's default is 0.9; 0.6 keeps a face in glasses or dim light (the owner's scored 0.88). */
    private static final float MIN_SCORE = 0.6f;
    private static final float NMS_IOU = 0.3f;
    private static final int MAX_FACES = 10;
    private static final int JPEG_QUALITY = 85;

    private final Context context;
    private final Paint filter = new Paint(Paint.FILTER_BITMAP_FLAG);
    private OrtEnvironment env;
    private OrtSession session;
    private String inputName;
    private final int inW = YuNetDecoder.INPUT_W;
    private final int inH = YuNetDecoder.INPUT_H;
    /** The input, CHW BGR floats, and the pixels it is filled from: reused across crops. */
    private FloatBuffer chw;
    private int[] pixels;
    /** Set once loading failed or close() ran: no more tries. */
    private boolean done;

    FaceCropper(Context context) {
        this.context = context.getApplicationContext();
    }

    /** A found face: the loose crop callers store, and the face's box and landmarks for alignment. */
    static final class Located {
        /** The loose SIDE_PX square crop, as crop() returns it. */
        final Result crop;
        /** The face in frame pixels: its box, score and five landmarks (YuNetDecoder.Face). */
        final YuNetDecoder.Face face;
        /** The decoded frame's size, the space the face's coordinates are in. */
        final int frameW;
        final int frameH;
        /** The decoded frame's ARGB pixels, row by row, for FaceAlign (face plan U6). */
        final int[] argb;

        Located(Result crop, YuNetDecoder.Face face, int frameW, int frameH, int[] argb) {
            this.crop = crop;
            this.face = face;
            this.frameW = frameW;
            this.frameH = frameH;
            this.argb = argb;
        }

        /** The face box's width in frame pixels: the size gate's measure (KTD3). */
        float faceWidth() {
            return face.x1 - face.x0;
        }
    }

    @Override
    public synchronized Result crop(byte[] frameJpeg, Detection personBox) {
        Located found = locate(frameJpeg, personBox);
        if (found == null) {
            return Result.NONE;
        }
        return found.crop;
    }

    /**
     * The largest face centred in the person box, with its loose crop and its
     * landmarks in frame pixels; null when there is no face (R10), so nothing
     * is matched or stored.
     */
    synchronized Located locate(byte[] frameJpeg, Detection personBox) {
        if (frameJpeg == null || personBox == null) {
            return null;
        }
        Bitmap frame = BitmapFactory.decodeByteArray(frameJpeg, 0, frameJpeg.length);
        if (frame == null) {
            return null;
        }
        try {
            int w = frame.getWidth();
            int h = frame.getHeight();
            int[] r = FaceCrop.Square.region(personBox, w, h);
            YuNetDecoder.Face best = r == null ? null : findFace(frame, r);
            if (best == null || best.landmarks == null) {
                return null;
            }
            int[] sq = FaceCrop.Square.aroundFace(best.x0, best.y0, best.x1, best.y1, w, h);
            Bitmap cut = Bitmap.createBitmap(frame, sq[0], sq[1], sq[2], sq[2]);
            Bitmap scaled = Bitmap.createScaledBitmap(cut, SIDE_PX, SIDE_PX, true);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out);
            if (scaled != cut) {
                scaled.recycle();
            }
            if (cut != frame) {
                cut.recycle();
            }
            int[] argb = new int[w * h];
            frame.getPixels(argb, 0, w, 0, 0, w, h);
            return new Located(new Result(out.toByteArray(), sq), best, w, h, argb);
        } finally {
            frame.recycle();
        }
    }

    /**
     * True when the face model is loaded (loading it now if needed): the
     * migration tells "no face in this photo" from "no model to look with",
     * and never marks a photo unusable for the second (KTD11).
     */
    synchronized boolean loaded() {
        return load();
    }

    /**
     * The loose crop brightened by the gate's table (KTD3): the same luma table
     * on all three channels, re-encoded as the stored JPEG. The crop unchanged
     * when there is no table or it can't be decoded.
     */
    static byte[] brighten(byte[] jpeg, int[] table) {
        if (jpeg == null || table == null) {
            return jpeg;
        }
        Bitmap in = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        if (in == null) {
            return jpeg;
        }
        try {
            int w = in.getWidth();
            int h = in.getHeight();
            int[] px = new int[w * h];
            in.getPixels(px, 0, w, 0, 0, w, h);
            int[] lit = FaceQuality.apply(px, table);
            Bitmap out = Bitmap.createBitmap(lit, w, h, Bitmap.Config.ARGB_8888);
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                out.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bytes);
                return bytes.toByteArray();
            } finally {
                out.recycle();
            }
        } finally {
            in.recycle();
        }
    }

    /** Frees the model; later crops find nothing. ClaudeCuriosity.release() calls it. */
    synchronized void close() {
        done = true;
        if (session != null) {
            try {
                session.close();
            } catch (OrtException ignored) {
                // Closing on exit; nothing to recover.
            }
            session = null;
        }
        chw = null;
        pixels = null;
    }

    /** The largest face centred inside region r {left, top, width, height}, in frame pixels, or null. */
    private YuNetDecoder.Face findFace(Bitmap frame, int[] r) {
        if (!load()) {
            return null;
        }
        long t0 = System.currentTimeMillis();
        int w = frame.getWidth();
        int h = frame.getHeight();
        float scale = YuNetDecoder.fitScale(w, h, inW, inH);
        int contentW = Math.round(w * scale);
        int contentH = Math.round(h * scale);
        Bitmap input = Bitmap.createBitmap(inW, inH, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(input);
            canvas.drawColor(Color.BLACK);
            canvas.drawBitmap(frame, null, new Rect(0, 0, contentW, contentH), filter);
            input.getPixels(pixels, 0, inW, 0, 0, inW, inH);
        } finally {
            input.recycle();
        }
        int plane = inW * inH;
        for (int i = 0; i < plane; i++) {
            int p = pixels[i];
            chw.put(i, p & 0xff);
            chw.put(plane + i, (p >> 8) & 0xff);
            chw.put(2 * plane + i, (p >> 16) & 0xff);
        }
        List<YuNetDecoder.Face> faces;
        try {
            faces = run(new YuNetDecoder(inW, inH, contentW, contentH));
        } catch (OrtException e) {
            Log.w(TAG, "face detector run failed: " + e.getClass().getSimpleName());
            return null;
        }
        for (int i = 0; i < faces.size(); i++) {
            faces.set(i, faces.get(i).unscaled(scale));
        }
        YuNetDecoder.Face best = YuNetDecoder.largestInside(faces, r[0], r[1], r[0] + r[2], r[1] + r[3]);
        Log.i(TAG, "face detector: " + faces.size() + " found, " + (best != null ? "one" : "none")
                + " in the person box, in " + (System.currentTimeMillis() - t0) + " ms");
        return best;
    }

    private List<YuNetDecoder.Face> run(YuNetDecoder decoder) throws OrtException {
        int n = YuNetDecoder.STRIDES.length;
        float[][] cls = new float[n][];
        float[][] obj = new float[n][];
        float[][] bbox = new float[n][];
        float[][] kps = new float[n][];
        OnnxTensor tensor = OnnxTensor.createTensor(env, chw, new long[]{1, 3, inH, inW});
        try {
            OrtSession.Result result = session.run(Collections.singletonMap(inputName, tensor));
            try {
                for (Map.Entry<String, OnnxValue> e : result) {
                    String out = e.getKey();
                    for (int s = 0; s < n; s++) {
                        String suffix = "_" + YuNetDecoder.STRIDES[s];
                        if (out.equals("cls" + suffix)) {
                            cls[s] = values(e.getValue());
                        } else if (out.equals("obj" + suffix)) {
                            obj[s] = values(e.getValue());
                        } else if (out.equals("bbox" + suffix)) {
                            bbox[s] = values(e.getValue());
                        } else if (out.equals("kps" + suffix)) {
                            kps[s] = values(e.getValue());
                        }
                    }
                }
            } finally {
                result.close();
            }
        } finally {
            tensor.close();
        }
        return decoder.decode(cls, obj, bbox, kps, MIN_SCORE, NMS_IOU, MAX_FACES);
    }

    private static float[] values(OnnxValue v) {
        FloatBuffer buf = ((OnnxTensor) v).getFloatBuffer();
        float[] out = new float[buf.remaining()];
        buf.get(out);
        return out;
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
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            options.setIntraOpNumThreads(THREADS);
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            session = env.createSession(HttpUtil.readAssetBytes(context, MODEL), options);
            // The model's height and width read -1 (any size): the input size is YuNetDecoder's.
            inputName = session.getInputNames().iterator().next();
            chw = ByteBuffer.allocateDirect(4 * 3 * inW * inH).order(ByteOrder.nativeOrder()).asFloatBuffer();
            pixels = new int[inW * inH];
            return true;
        } catch (IOException | OrtException | RuntimeException e) {
            Log.w(TAG, "face detector unavailable: " + e.getClass().getSimpleName());
            done = true;
            if (session != null) {
                try {
                    session.close();
                } catch (OrtException ignored) {
                    // Already failing.
                }
                session = null;
            }
            return false;
        }
    }
}
