package com.miko3.shared;

import java.util.zip.CRC32;

/**
 * The NC direction chip's byte stream (explore plan U2; KTD11, KTD12) and the
 * mapping from its raw 0 to 255 direction value to signed degrees.
 *
 * Verified on the robot (2026-09-29): the chip on /dev/ttyS1 streams by
 * itself, one 19-byte frame a second whether or not anyone speaks, and needs
 * no request. A frame is "XXUB" (58 58 55 42), module, op, a sequence number
 * that goes up by one per frame, then 01 05 00, then a CRC32 little-endian
 * over bytes 0 to 9 at bytes 10 to 13, then a 5-byte payload. A direction
 * frame is module 0xa3, op 0x03, and its first payload byte (frame byte 14)
 * is the raw direction (seen in steps of 5, from 55 to 90). Any other frame
 * (a module 03 / op 02 frame came once right after vendor setup), a frame with
 * a bad CRC, bytes between frames and a frame torn off at the end of a read
 * all occur; the parse skips them without error.
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

    private static final byte[] PREFIX = {0x58, 0x58, 0x55, 0x42}; // "XXUB"

    private NcFrames() {
    }

    /** Called for each valid direction frame, oldest first: the frame is buf[at..at+19). */
    public interface Sink {
        void direction(byte[] buf, int at, int raw);
    }

    /**
     * Hands every valid direction frame in buf[0..length) to sink, oldest first, and
     * returns how many leading bytes are done with. What follows them (fewer than
     * FRAME_LENGTH bytes) may be the start of a frame still arriving: keep it and
     * append the next read. Never throws on any content.
     */
    public static int parseStream(byte[] buf, int length, Sink sink) {
        int i = 0;
        while (i + FRAME_LENGTH <= length) {
            if (prefixed(buf, i) && crcOk(buf, i)) {
                if ((buf[i + 4] & 0xff) == MODULE_DIRECTION && (buf[i + 5] & 0xff) == OP_DIRECTION) {
                    sink.direction(buf, i, buf[i + RAW_INDEX] & 0xff);
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
}
