package com.miko3.mode.explore;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PointF;
import android.media.FaceDetector;

import java.io.ByteArrayOutputStream;

/**
 * FaceCrop on the robot (explore on Claude plan KTD5): Android's built-in
 * FaceDetector (API 1, offline, no new model) looks inside the person box, and
 * the most confident face it finds is cut out 1.6x larger as a 224 px JPEG.
 * With no face there is no crop at all: nothing that isn't a detected face is
 * matched or stored. See FaceCrop for the geometry. Called on the curiosity
 * adapter's worker thread, never the brain's.
 */
final class FaceCropper implements FaceCrop {
    /** FaceDetector's own floor for a real face. */
    private static final float MIN_CONFIDENCE = 0.4f;
    private static final int MAX_FACES = 3;
    private static final int JPEG_QUALITY = 85;

    @Override
    public Result crop(byte[] frameJpeg, Detection personBox) {
        if (frameJpeg == null || personBox == null) {
            return Result.NONE;
        }
        Bitmap frame = BitmapFactory.decodeByteArray(frameJpeg, 0, frameJpeg.length);
        if (frame == null) {
            return Result.NONE;
        }
        try {
            int w = frame.getWidth();
            int h = frame.getHeight();
            int[] sq = findFace(frame, personBox, w, h);
            if (sq == null) {
                return Result.NONE;
            }
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
            return new Result(out.toByteArray(), sq);
        } finally {
            frame.recycle();
        }
    }

    /** The square around the most confident face inside the person box, in frame pixels, or null. */
    private static int[] findFace(Bitmap frame, Detection box, int w, int h) {
        int[] r = FaceCrop.Square.region(box, w, h);
        if (r == null) {
            return null;
        }
        // FaceDetector reads RGB_565 only, an even width, and misses faces whose
        // eyes are a few pixels apart: an RGB_565 copy of the region, scaled up if small.
        float s = FaceCrop.Square.detectScale(r[2]);
        int dw = FaceCrop.Square.evenScaled(r[2], s);
        int dh = Math.max(2, (int) (r[3] * s));
        Bitmap region = Bitmap.createBitmap(frame, r[0], r[1], r[2], r[3]);
        Bitmap sized = dw == r[2] && dh == r[3] ? region : Bitmap.createScaledBitmap(region, dw, dh, true);
        Bitmap rgb565 = sized.copy(Bitmap.Config.RGB_565, false);
        try {
            if (rgb565 == null) {
                return null;
            }
            FaceDetector.Face[] faces = new FaceDetector.Face[MAX_FACES];
            int found = new FaceDetector(dw, dh, MAX_FACES).findFaces(rgb565, faces);
            FaceDetector.Face best = null;
            for (int i = 0; i < found; i++) {
                if (faces[i] != null && faces[i].confidence() >= MIN_CONFIDENCE
                        && (best == null || faces[i].confidence() > best.confidence())) {
                    best = faces[i];
                }
            }
            if (best == null) {
                return null;
            }
            PointF mid = new PointF();
            best.getMidPoint(mid);
            // Back from the scaled region to frame pixels.
            float sx = r[2] / (float) dw;
            float sy = r[3] / (float) dh;
            return FaceCrop.Square.aroundFace(r[0] + mid.x * sx, r[1] + mid.y * sy, best.eyesDistance() * sx, w, h);
        } finally {
            if (rgb565 != null) {
                rgb565.recycle();
            }
            if (sized != region) {
                sized.recycle();
            }
            if (region != frame) {
                region.recycle();
            }
        }
    }
}
