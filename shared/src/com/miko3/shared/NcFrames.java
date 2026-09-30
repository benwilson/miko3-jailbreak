package com.miko3.shared;

import java.util.zip.CRC32;

/**
 * The NC direction chip's byte stream (explore plan U2; KTD11, KTD12) and the
 * mapping from its raw 0 to 255 direction value to signed degrees.
 *
 * Verified on the robot (2026-09-29): once its direction reporting is on, the
 * chip on /dev/ttyS1 streams one 19-byte frame a second whether or not anyone
 * speaks. A frame is "XXUB" (58 58 55 42), module, op, a sequence number that
 * goes up by one per frame, then 01 05 00, then a CRC32 little-endian over
 * bytes 0 to 9 at bytes 10 to 13, then a 5-byte payload. A direction frame is
 * module 0xa3, op 0x03, and its first payload byte (frame byte 14) is the raw
 * direction (seen in steps of 5, from 55 to 90).
 *
 * Verified on the robot (2026-09-30): after a cold boot reporting is OFF and
 * nothing streams. The vendor's status query (STATUS_QUERY, 14 bytes) gets one
 * status frame back, module 0x03, op 0x02, whose payload byte 0 is 0 for off
 * and non-zero for on; the vendor's toggle (REPORTING_TOGGLE, 14 bytes, no
 * reply) flips reporting each time it is sent, and direction frames then come
 * a second apart from sequence 0. VoiceDirection sends the query, and the
 * toggle only when the answer is off, once per open. Any other frame, a frame
 * with a bad CRC, bytes between frames and a frame torn off at the end of a
 * read all occur; the parse skips them without error.
 *
 * Plain Java with no android.* imports and no I/O: VoiceDirection does the
 * reading, and the host tests run this directly.
 */
public final class NcFrames {
    /** Every frame's length. */
    public static final int FRAME_LENGTH = 19;
    /** Where a direction frame carries its raw value (payload byte 0). */
    public static final int RAW_INDEX = 14;

    static final int MODULE_DIRECTION = 0xa3;
    static final int OP_DIRECTION = 0x03;
    static final int MODULE_STATUS = 0x03;
    static final int OP_STATUS = 0x02;

    /** The vendor's status query (robot, 2026-09-30): answered by one status frame. Never
     * mutated; statusQuery() hands out a copy. */
    private static final byte[] STATUS_QUERY = {0x58, 0x58, 0x55, 0x42, 0x03, 0x01, 0x03, 0x00, 0x00, 0x00,
            (byte) 0xa6, 0x20, (byte) 0xd0, (byte) 0xe7};
    /** The vendor's reporting toggle (robot, 2026-09-30): no reply, flips reporting each time
     * it is sent. Never mutated; reportingToggle() hands out a copy. */
    private static final byte[] REPORTING_TOGGLE = {0x58, 0x58, 0x55, 0x42, 0x03, 0x03, 0x02, 0x00, 0x00, 0x00,
            (byte) 0xa3, 0x14, (byte) 0xac, 0x25};

    private static final byte[] PREFIX = {0x58, 0x58, 0x55, 0x42}; // "XXUB"

    private NcFrames() {
    }

    /** A copy of the vendor's status query. */
    public static byte[] statusQuery() {
        return STATUS_QUERY.clone();
    }

    /** A copy of the vendor's reporting toggle. */
    public static byte[] reportingToggle() {
        return REPORTING_TOGGLE.clone();
    }

    /** Called for each valid frame it knows, oldest first. */
    public interface Sink {
        /** A direction frame: the frame is buf[at..at+19). */
        void direction(byte[] buf, int at, int raw);

        /** A status frame (module 03 / op 02): whether direction reporting is on. */
        default void status(boolean on) {
        }
    }

    /**
     * Hands every valid direction and status frame in buf[0..length) to sink, oldest first, and
     * returns how many leading bytes are done with. What follows them (fewer than
     * FRAME_LENGTH bytes) may be the start of a frame still arriving: keep it and
     * append the next read. Never throws on any content.
     */
    public static int parseStream(byte[] buf, int length, Sink sink) {
        int i = 0;
        while (i + FRAME_LENGTH <= length) {
            if (prefixed(buf, i) && crcOk(buf, i)) {
                int module = buf[i + 4] & 0xff;
                int op = buf[i + 5] & 0xff;
                if (module == MODULE_DIRECTION && op == OP_DIRECTION) {
                    sink.direction(buf, i, buf[i + RAW_INDEX] & 0xff);
                } else if (module == MODULE_STATUS && op == OP_STATUS) {
                    sink.status(buf[i + RAW_INDEX] != 0);
                }
                i += FRAME_LENGTH;
            } else {
                // Junk, or a frame whose CRC fails: a real frame may start at the next byte.
                i++;
            }
        }
        return i;
    }

    private static boolean prefixed(byte[] b, int at) {
        for (int i = 0; i < PREFIX.length; i++) {
            if (b[at + i] != PREFIX[i]) {
                return false;
            }
        }
        return true;
    }

    /** The frame at `at` carries CRC32 LE of its first 10 bytes at bytes 10 to 13. */
    private static boolean crcOk(byte[] b, int at) {
        CRC32 crc = new CRC32();
        crc.update(b, at, 10);
        long c = crc.getValue();
        for (int i = 0; i < 4; i++) {
            if (b[at + 10 + i] != (byte) (c >>> (8 * i))) {
                return false;
            }
        }
        return true;
    }

    /** Signed degrees in [-180, 180) for a raw value, or NaN when uncalibrated
     * (cal null) or raw is negative (no reading). */
    public static float degrees(int raw, Calibration cal) {
        if (cal == null || raw < 0) {
            return Float.NaN;
        }
        double d = cal.sign * (raw - cal.zero) * cal.scale;
        d = ((d + 180.0) % 360.0 + 360.0) % 360.0 - 180.0;
        return (float) d;
    }

    /** Side mode: -90 (left, since negative is left) for raw at or above the left
     * threshold, +90 (right) for raw at or below the right one, and NaN between them
     * (ahead or behind: the chip cannot tell which), with no thresholds (sides null)
     * or when raw is negative (no reading). */
    public static float sideDegrees(int raw, Sides sides) {
        if (sides == null || raw < 0) {
            return Float.NaN;
        }
        if (raw >= sides.left) {
            return -90f;
        }
        if (raw <= sides.right) {
            return 90f;
        }
        return Float.NaN;
    }

    /** Lowercase hex of buf[0..length), no separators. */
    public static String hex(byte[] buf, int length) {
        return hex(buf, 0, length);
    }

    /** Lowercase hex of buf[at..at+length), no separators. */
    public static String hex(byte[] buf, int at, int length) {
        StringBuilder sb = new StringBuilder(length * 2);
        for (int i = at; i < at + length; i++) {
            sb.append(Character.forDigit((buf[i] >> 4) & 0xf, 16)).append(Character.forDigit(buf[i] & 0xf, 16));
        }
        return sb.toString();
    }

    /** The calibration (KTD12): the raw value that is straight ahead, the
     * direction of increase (+1 or -1), and degrees per raw step. */
    public static final class Calibration {
        public final double zero;
        public final int sign;
        public final double scale;

        private Calibration(double zero, int sign, double scale) {
            this.zero = zero;
            this.sign = sign;
            this.scale = scale;
        }

        /** The calibration from the property values, or null when any is
         * unset or invalid (sign not +-1, scale not positive). The scale may
         * be a number or a fraction like "360/256". */
        public static Calibration parse(String zero, String sign, String scale) {
            try {
                double z = Double.parseDouble(zero.trim());
                int s = Integer.parseInt(sign.trim().replace("+", ""));
                double k = fraction(scale.trim());
                if ((s != 1 && s != -1) || !(k > 0) || Double.isInfinite(k) || Double.isNaN(z)
                        || Double.isInfinite(z)) {
                    return null;
                }
                return new Calibration(z, s, k);
            } catch (RuntimeException e) {
                return null;
            }
        }

        private static double fraction(String s) {
            int slash = s.indexOf('/');
            if (slash < 0) {
                return Double.parseDouble(s);
            }
            return Double.parseDouble(s.substring(0, slash).trim()) / Double.parseDouble(s.substring(slash + 1).trim());
        }

        @Override
        public String toString() {
            return "zero " + zero + " sign " + sign + " scale " + scale;
        }
    }

    /**
     * Side mode's raw thresholds. Verified on the robot (2026-09-29), the owner
     * talking about 1 m away: right reads 30 to 45, front about 80, behind 85 to
     * 100 and left 95 to 110. The chip measures only how far left or right a voice
     * is, never front from back, so no full-circle calibration fits it; these two
     * thresholds give the side alone.
     */
    public static final class Sides {
        /** Raw at or above this is on the left. */
        public final int left;
        /** Raw at or below this is on the right. */
        public final int right;

        private Sides(int left, int right) {
            this.left = left;
            this.right = right;
        }

        /** The thresholds from the property values, or null when either is unset,
         * not an integer, or not 0 <= right < left <= 255. */
        public static Sides parse(String left, String right) {
            try {
                int l = Integer.parseInt(left.trim());
                int r = Integer.parseInt(right.trim());
                if (r < 0 || r >= l || l > 255) {
                    return null;
                }
                return new Sides(l, r);
            } catch (RuntimeException e) {
                return null;
            }
        }

        @Override
        public String toString() {
            return "left >= " + left + ", right <= " + right;
        }
    }
}
