package com.miko3.mode.explore;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PointF;
import android.media.FaceDetector;

import java.io.ByteArrayOutputStream;

/**
 * FaceCrop on the robot (explore on Claude plan KTD5): Android's built-in
 * FaceDetector (API 1, offline, no new model) looks inside the person box,
 * and the first face it finds is cut out 1.6x larger as a 224 px JPEG. With
 * no face, the top quarter of the person box stands in. See FaceCrop for the
 * geometry. Called on the curiosity adapter's worker thread, never the brain's.
 */
final class FaceCropper implements FaceCrop {
    /** FaceDetector's own floor for a real face. */
    private static final float MIN_CONFIDENCE = 0.4f;
    private static final int MAX_FACES = 3;
    private static final int JPEG_QUALITY = 85;

    @Override
    public byte[] crop(byte[] frameJpeg, Detection personBox) {
        if (frameJpeg == null || personBox == null) {
            return null;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        // FaceDetector reads RGB_565 only.
        opts.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap frame = BitmapFactory.decodeByteArray(frameJpeg, 0, frameJpeg.length, opts);
        if (frame == null) {
            return null;
        }
        try {
            int w = frame.getWidth();
            int h = frame.getHeight();
            int[] sq = findFace(frame, personBox, w, h);
            if (sq == null) {
                sq = FaceCrop.Square.topOfPerson(personBox, w, h);
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
            return out.toByteArray();
        } finally {
            frame.recycle();
        }
    }

    /** The square around the most confident face inside the person box, in frame pixels, or null. */
    private static int[] findFace(Bitmap frame, Detection box, int w, int h) {
        int left = Math.max(0, (int) (box.x0 * w));
        int top = Math.max(0, (int) (box.y0 * h));
        int right = Math.min(w, (int) Math.ceil(box.x1 * w));
        int bottom = Math.min(h, (int) Math.ceil(box.y1 * h));
        int bw = (right - left) & ~1; // FaceDetector needs an even width
        int bh = bottom - top;
        if (bw < 16 || bh < 16) {
            return null;
        }
        Bitmap region = Bitmap.createBitmap(frame, left, top, bw, bh);
        try {
            FaceDetector.Face[] faces = new FaceDetector.Face[MAX_FACES];
            int found = new FaceDetector(bw, bh, MAX_FACES).findFaces(region, faces);
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
            return FaceCrop.Square.aroundFace(left + mid.x, top + mid.y, best.eyesDistance(), w, h);
        } finally {
            if (region != frame) {
                region.recycle();
            }
        }
    }
}
