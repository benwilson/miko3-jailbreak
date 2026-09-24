package com.miko3.mode.explore;

/**
 * Cuts a person's face out of a scan frame for the match request (explore on
 * Claude plan KTD5, R9, R13). FaceCropper does it on the robot with Android's
 * FaceDetector; this interface and its geometry are plain Java, so the brain
 * and the host harness never touch android.*.
 *
 * The geometry, all in the frame's pixels:
 *  - A face hit (the midpoint between the eyes and the eye distance) becomes a
 *    square about FACE_WIDTHS eye distances wide, a little below the eyes so
 *    the chin is in, then EXPAND times larger (1.6x) for hair and shoulders.
 *  - With no hit, the top TOP_SHARE (25%) of the person box, squared about its
 *    centre, stands in for the face.
 * Either square is clamped inside the frame (shrunk if it can't fit), and is
 * then scaled to SIDE_PX (224) and saved as a JPEG.
 */
interface FaceCrop {
    /** The face as a SIDE_PX square JPEG, or null when the frame can't be decoded. */
    byte[] crop(byte[] frameJpeg, Detection personBox);

    int SIDE_PX = 224;
    float EXPAND = 1.6f;
    /** A face is about this many eye distances wide (FaceDetector's own guidance: 2.5-3). */
    float FACE_WIDTHS = 2.5f;
    /** The face's centre sits this many eye distances below the eyes' midpoint. */
    float DROP = 0.25f;
    float TOP_SHARE = 0.25f;

    /** Where to cut, in the frame's pixels: each answer is {left, top, side}. */
    final class Square {
        private Square() {
        }

        /** The square around a face hit, inside a w x h frame. */
        static int[] aroundFace(float midX, float midY, float eyesDistance, int w, int h) {
            float side = eyesDistance * FACE_WIDTHS * EXPAND;
            return clamp(midX, midY + eyesDistance * DROP, side, w, h);
        }

        /** The square standing in for the face: the top quarter of the person box (frame fractions), squared. */
        static int[] topOfPerson(Detection box, int w, int h) {
            float band = box.height() * h * TOP_SHARE;
            float cx = (box.x0 + box.x1) / 2f * w;
            return clamp(cx, box.y0 * h + band / 2f, band, w, h);
        }

        /** A square of the given side centred on (cx, cy), shrunk to fit and moved inside the frame. */
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
