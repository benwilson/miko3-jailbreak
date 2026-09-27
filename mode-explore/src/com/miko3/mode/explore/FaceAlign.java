package com.miko3.mode.explore;

/**
 * Straightens a face onto the ArcFace 112x112 template for the embedding model
 * (on-device face recognition plan KTD2, R16). Plain Java so the host harness
 * proves the math against the numpy reference the bench (U9) mirrors.
 *
 * The fit: YuNet's five landmarks (right eye, left eye, nose tip, right and
 * left mouth corners, as the template orders them) are mapped onto TEMPLATE by
 * the least-squares similarity transform -- uniform scale, rotation, shift, and
 * never a reflection. That is Umeyama's fit with its reflection guard: over
 * 2-D points, scale times a proper rotation is a complex number z, and the
 * least-squares z has the closed form sum(conj(src) * dst) / sum(|src|^2) on
 * the centred points, so no SVD is needed and a mirrored input still gives a
 * rotation.
 *
 * The warp: each output pixel's centre goes back through the inverse transform
 * into the frame and is sampled bilinearly. The top-left neighbour is clamped
 * to [0, w-2] x [0, h-2] and the fraction is not, exactly as the numpy
 * reference does, so a sample past the frame edge extrapolates the edge
 * gradient; toArgb() clamps the result to 0..255.
 *
 * Pixels are ARGB ints, as FaceCropper reads them from a Bitmap. warp() gives
 * floats, RGB interleaved row-major (112 * 112 * 3), 0..255-ish, unrounded.
 */
final class FaceAlign {
    /** The aligned crop's side, in pixels. */
    static final int SIDE = 112;

    /** ArcFace's five landmark positions in the 112x112 crop: x0, y0, ... x4, y4. */
    static final double[] TEMPLATE = {
        38.2946, 51.6963,
        73.5318, 51.5014,
        56.0252, 71.7366,
        41.5493, 92.3655,
        70.7299, 92.2041,
    };

    /** Landmark spread (sum of squared distances from their centre) below which there is no fit. */
    private static final double MIN_SPREAD = 1e-6;

    private FaceAlign() {
    }

    /**
     * The similarity transform taking five landmarks (x0, y0, ... x4, y4, frame
     * pixels) onto TEMPLATE, as the 2x3 matrix {m00, m01, m02, m10, m11, m12}:
     * crop x = m00 * x + m01 * y + m02, crop y = m10 * x + m11 * y + m12.
     * Always m00 == m11 and m01 == -m10 (no reflection). Null for anything but
     * ten finite values, or landmarks all on one point.
     */
    static double[] fit(float[] landmarks) {
        if (landmarks == null || landmarks.length != 10) {
            return null;
        }
        double sx = 0;
        double sy = 0;
        double dx = 0;
        double dy = 0;
        for (int i = 0; i < 5; i++) {
            float x = landmarks[2 * i];
            float y = landmarks[2 * i + 1];
            if (Float.isNaN(x) || Float.isInfinite(x) || Float.isNaN(y) || Float.isInfinite(y)) {
                return null;
            }
            sx += x;
            sy += y;
            dx += TEMPLATE[2 * i];
            dy += TEMPLATE[2 * i + 1];
        }
        sx /= 5;
        sy /= 5;
        dx /= 5;
        dy /= 5;
        double spread = 0;
        double re = 0;
        double im = 0;
        for (int i = 0; i < 5; i++) {
            double px = landmarks[2 * i] - sx;
            double py = landmarks[2 * i + 1] - sy;
            double qx = TEMPLATE[2 * i] - dx;
            double qy = TEMPLATE[2 * i + 1] - dy;
            spread += px * px + py * py;
            re += px * qx + py * qy;
            im += px * qy - py * qx;
        }
        if (!(spread > MIN_SPREAD)) {
            return null;
        }
        double a = re / spread;
        double b = im / spread;
        return new double[] {a, -b, dx - (a * sx - b * sy), b, a, dy - (b * sx + a * sy)};
    }

    /** Where the transform m takes the point (x, y): {x', y'}. */
    static double[] apply(double[] m, double x, double y) {
        return new double[] {m[0] * x + m[1] * y + m[2], m[3] * x + m[4] * y + m[5]};
    }

    /** The transform's uniform scale. */
    static double scale(double[] m) {
        return Math.hypot(m[0], m[3]);
    }

    /** The transform's rotation in degrees, counter-clockwise in image axes (y down). */
    static double rotationDegrees(double[] m) {
        return Math.toDegrees(Math.atan2(m[3], m[0]));
    }

    /**
     * The w x h ARGB frame warped by m onto a SIDE x SIDE crop, bilinear: floats,
     * RGB interleaved, row-major. The frame must be at least 2x2 and m invertible.
     */
    static float[] warp(int[] argb, int w, int h, double[] m) {
        if (argb == null || w < 2 || h < 2 || argb.length < w * h || m == null || m.length != 6) {
            throw new IllegalArgumentException("bad frame or transform");
        }
        double det = m[0] * m[4] - m[1] * m[3];
        if (!(Math.abs(det) > 1e-12)) {
            throw new IllegalArgumentException("transform not invertible");
        }
        double i00 = m[4] / det;
        double i01 = -m[1] / det;
        double i10 = -m[3] / det;
        double i11 = m[0] / det;
        float[] out = new float[SIDE * SIDE * 3];
        int o = 0;
        for (int yy = 0; yy < SIDE; yy++) {
            for (int xx = 0; xx < SIDE; xx++) {
                double px = xx - m[2];
                double py = yy - m[5];
                double x = i00 * px + i01 * py;
                double y = i10 * px + i11 * py;
                int x0 = clampIndex(Math.floor(x), w - 2);
                int y0 = clampIndex(Math.floor(y), h - 2);
                double fx = x - x0;
                double fy = y - y0;
                double w00 = (1 - fx) * (1 - fy);
                double w01 = fx * (1 - fy);
                double w10 = (1 - fx) * fy;
                double w11 = fx * fy;
                int p00 = argb[y0 * w + x0];
                int p01 = argb[y0 * w + x0 + 1];
                int p10 = argb[(y0 + 1) * w + x0];
                int p11 = argb[(y0 + 1) * w + x0 + 1];
                for (int shift = 16; shift >= 0; shift -= 8) {
                    out[o++] = (float) (((p00 >> shift) & 0xff) * w00 + ((p01 >> shift) & 0xff) * w01
                            + ((p10 >> shift) & 0xff) * w10 + ((p11 >> shift) & 0xff) * w11);
                }
            }
        }
        return out;
    }

    /** warp() output as opaque ARGB pixels, each channel rounded and clamped to 0..255. */
    static int[] toArgb(float[] rgb) {
        int[] out = new int[rgb.length / 3];
        for (int i = 0; i < out.length; i++) {
            out[i] = 0xff000000 | (channel(rgb[3 * i]) << 16) | (channel(rgb[3 * i + 1]) << 8) | channel(rgb[3 * i + 2]);
        }
        return out;
    }

    /** The aligned SIDE x SIDE ARGB crop for five landmarks, or null when they give no fit. */
    static int[] align(int[] argb, int w, int h, float[] landmarks) {
        double[] m = fit(landmarks);
        return m == null ? null : toArgb(warp(argb, w, h, m));
    }

    private static int clampIndex(double v, int max) {
        if (v < 0) {
            return 0;
        }
        return v > max ? max : (int) v;
    }

    private static int channel(float v) {
        int r = (int) Math.floor(v + 0.5f);
        return r < 0 ? 0 : (r > 255 ? 255 : r);
    }
}
