package com.miko3.mode.explore;

/**
 * Cuts a person's face out of a camera frame for the match request (explore on
 * Claude plan KTD5, R9, R13). FaceCropper does it on the robot with Android's
 * FaceDetector; this interface and its geometry are plain Java, so the brain
 * and the host harness never touch android.*.
 *
 * The geometry, all in the frame's pixels:
 *  - The person box (fractions of the frame, as every Detection is) becomes a
 *    pixel region inside the frame, an even number of pixels wide (FaceDetector
 *    needs that). A small region is scaled up by detectScale() before the
 *    detector looks, since it misses faces whose eyes are only a few pixels apart.
 *  - A face hit (the midpoint between the eyes and the eye distance) becomes a
 *    square about FACE_WIDTHS eye distances wide, a little below the eyes so
 *    the chin is in, then EXPAND times larger (1.6x) for hair and shoulders.
 *  - With no hit there is NO crop: a person box without a detected face is
 *    never stored or matched as a face. (An earlier stand-in, the top quarter
 *    of the box, stored a patch of wall whenever the box was loose or the
 *    person small: the People page showed the wall.)
 * The square is clamped inside the frame (shrunk if it can't fit), and is then
 * scaled to SIDE_PX (224) and saved as a JPEG.
 */
interface FaceCrop {
    /** What crop() made of one frame and person box. */
    final class Result {
        /** The face as a SIDE_PX square JPEG; null when no face was found or the frame couldn't be decoded. */
        final byte[] face;
        /** The square cut, {left, top, side} in frame pixels, or null. For the debug log only. */
        final int[] square;

        Result(byte[] face, int[] square) {
            this.face = face;
            this.square = square;
        }

        static final Result NONE = new Result(null, null);

        boolean found() {
            return face != null;
        }
    }

    /** The face in the person box, only if a face detector found one there. Never null. */
    Result crop(byte[] frameJpeg, Detection personBox);

    int SIDE_PX = 224;
    float EXPAND = 1.6f;
    /** A face is about this many eye distances wide (FaceDetector's own guidance: 2.5-3). */
    float FACE_WIDTHS = 2.5f;
    /** The face's centre sits this many eye distances below the eyes' midpoint. */
    float DROP = 0.25f;
    /** Regions narrower than this are scaled up before detection, by at most MAX_DETECT_SCALE. */
    int DETECT_MIN_WIDTH = 320;
    float MAX_DETECT_SCALE = 4f;

    /** Where to cut and how to read boxes, in the frame's pixels. */
    final class Square {
        private Square() {
        }

        /** The square around a face hit, inside a w x h frame: {left, top, side}. */
        static int[] aroundFace(float midX, float midY, float eyesDistance, int w, int h) {
            float side = eyesDistance * FACE_WIDTHS * EXPAND;
            return clamp(midX, midY + eyesDistance * DROP, side, w, h);
        }

        /**
         * The person box as a pixel region of a w x h frame, {left, top, width,
         * height}: clamped inside the frame, and an even width. Null when it is
         * too small for a detector to look in (under 16 px either way).
         */
        static int[] region(Detection box, int w, int h) {
            int left = Math.max(0, Math.min(w, (int) Math.floor(box.x0 * w)));
            int top = Math.max(0, Math.min(h, (int) Math.floor(box.y0 * h)));
            int right = Math.max(left, Math.min(w, (int) Math.ceil(box.x1 * w)));
            int bottom = Math.max(top, Math.min(h, (int) Math.ceil(box.y1 * h)));
            int rw = (right - left) & ~1;
            int rh = bottom - top;
            if (rw < 16 || rh < 16) {
                return null;
            }
            return new int[] {left, top, rw, rh};
        }

        /** How much to scale a region regionWidth px wide before detection: 1 or more, at most MAX_DETECT_SCALE. */
        static float detectScale(int regionWidth) {
            if (regionWidth <= 0 || regionWidth >= DETECT_MIN_WIDTH) {
                return 1f;
            }
            return Math.min(MAX_DETECT_SCALE, DETECT_MIN_WIDTH / (float) regionWidth);
        }

        /** A dimension scaled by s, rounded down to an even number of pixels (FaceDetector needs an even width). */
        static int evenScaled(int px, float s) {
            return Math.max(2, ((int) (px * s)) & ~1);
        }

        /**
         * A box Claude gave as [left, top, right, bottom] as fractions of a w x h
         * frame. The prompt asks for pixels, and those are divided by the frame's
         * size; but a box whose four values all lie in 0..1 is already normalized
         * (Claude sometimes answers that way, and the prompt-only fallback can't
         * forbid it), and dividing it again would put a tiny box in the top-left
         * corner. Null for anything that isn't four finite numbers.
         */
        static float[] fractions(double[] box, int w, int h) {
            if (box == null || box.length != 4 || w <= 0 || h <= 0) {
                return null;
            }
            boolean normalized = true;
            for (double v : box) {
                if (Double.isNaN(v) || Double.isInfinite(v)) {
                    return null;
                }
                if (v < 0 || v > 1) {
                    normalized = false;
                }
            }
            float[] out = new float[4];
            for (int i = 0; i < 4; i++) {
                out[i] = normalized ? (float) box[i] : (float) (box[i] / (i % 2 == 0 ? w : h));
            }
            return out;
        }

        /** A square of the given side centred on (cx, cy), shrunk to fit and moved inside the frame: {left, top, side}. */
        static int[] clamp(float cx, float cy, float side, int w, int h) {
            int s = Math.max(1, Math.round(Math.min(side, Math.min(w, h))));
            int left = Math.round(cx - s / 2f);
            int top = Math.round(cy - s / 2f);
            left = Math.max(0, Math.min(left, w - s));
            top = Math.max(0, Math.min(top, h - s));
            return new int[] {left, top, s};
        }
    }
}
