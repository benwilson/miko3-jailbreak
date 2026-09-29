package com.miko3.shared;

import java.util.zip.CRC32;

/**
 * The NC direction chip's byte format (explore plan U2; KTD11, KTD12), as
 * decoded from the vendor's libconexant_dsp_lib.so, and the mapping from its
 * raw 0 to 255 direction value to signed degrees.
 *
 * A request is 14 bytes: "XXUB", module, op, param, three zeros, then a
 * CRC32 over the first 10 bytes, little-endian. A reply is validated the way
 * the vendor library does it: the "XXUB" prefix and at least 15 bytes; byte
 * 14 is the status (0: reporting is off), and with reporting on the reply
 * runs to 38 bytes with the raw value at byte 0x21. Where a reply's own CRC
 * sits is unverified, so it is not checked (VoiceDirection logs the first
 * replies in full so the robot session can establish it).
 *
 * Plain Java with no android.* imports and no I/O: VoiceDirection does the
 * reading and writing, and the host tests run this directly.
 */
public final class NcFrames {
    public static final int REQUEST_LENGTH = 14;
    /** Bytes the vendor reads before it looks at the status byte. */
    public static final int SHORT_REPLY = 15;
    /** A reply's full length with reporting on. */
    public static final int LONG_REPLY = 38;
    public static final int STATUS_INDEX = 14;
    public static final int RAW_INDEX = 0x21;

    /** parseDoa: the reply says reporting is off. */
    public static final int OFF = -1;
    /** parseDoa: no reply, a short one, one without the prefix, or a frame with a bad CRC. */
    public static final int MALFORMED = -2;
    /** parseDoa: reporting is on but only the status frame came (no direction yet,
     * nobody talking). An answer, not a miss. */
    public static final int NO_READING = -3;
    /** A reply frame (seen on the robot, 2026-09-29): XXUB, six header bytes, CRC32 LE
     * over the first 10, then a 5-byte payload. A reply with a direction is the status
     * frame followed by a direction frame whose first payload byte is the raw value. */
    public static final int FRAME_LENGTH = 19;

    static final int MODULE_DOA = 3;
    static final int OP_GET = 1;
    static final int OP_TOGGLE = 3;
    static final int PARAM_DOA_QUERY = 3;
    static final int PARAM_DOA_TOGGLE = 2;

    private static final byte[] PREFIX = {0x58, 0x58, 0x55, 0x42}; // "XXUB"

    private NcFrames() {
    }

    /** A 14-byte request frame with its CRC. */
    public static byte[] request(int module, int op, int param) {
        byte[] f = new byte[REQUEST_LENGTH];
        System.arraycopy(PREFIX, 0, f, 0, PREFIX.length);
        f[4] = (byte) module;
        f[5] = (byte) op;
        f[6] = (byte) param;
        CRC32 crc = new CRC32();
        crc.update(f, 0, 10);
        long c = crc.getValue();
        for (int i = 0; i < 4; i++) {
            f[10 + i] = (byte) (c >>> (8 * i));
        }
        return f;
    }

    /** The DOA status query (vendor getCurrentDOAStatus). */
    public static byte[] doaQuery() {
        return request(MODULE_DOA, OP_GET, PARAM_DOA_QUERY);
    }

    /** The DOA reporting toggle (vendor toggleDOA): write-only, flips the state on every send. */
    public static byte[] doaToggle() {
        return request(MODULE_DOA, OP_TOGGLE, PARAM_DOA_TOGGLE);
    }

    /** How many bytes the reply in buf[0..have) needs in all: SHORT_REPLY until
     * the status byte is in, then LONG_REPLY when reporting is on (the direction frame
     * may never come; the caller's deadline ends the wait). */
    public static int replyLength(byte[] buf, int have) {
        if (have <= STATUS_INDEX) {
            return SHORT_REPLY;
        }
        return buf[STATUS_INDEX] == 0 ? SHORT_REPLY : LONG_REPLY;
    }

    /** The raw direction 0 to 255, OFF, NO_READING, or MALFORMED; never throws. */
    public static int parseDoa(byte[] reply, int length) {
        if (reply == null || length < SHORT_REPLY || length > reply.length || !prefixed(reply, 0)) {
            return MALFORMED;
        }
        if (reply[STATUS_INDEX] == 0) {
            return OFF;
        }
        if (length >= FRAME_LENGTH && !crcOk(reply, 0)) {
            return MALFORMED;
        }
        if (length < LONG_REPLY) {
            // The status frame alone is an answer; a frame torn off mid-way is not.
            return length == FRAME_LENGTH ? NO_READING : MALFORMED;
        }
        if (!prefixed(reply, FRAME_LENGTH) || !crcOk(reply, FRAME_LENGTH)) {
            return MALFORMED;
        }
        return reply[RAW_INDEX] & 0xff;
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

    /** The raw direction as a float, or NaN for a reply that is off or malformed. */
    public static float rawOrNaN(byte[] reply, int length) {
        int p = parseDoa(reply, length);
        return p < 0 ? Float.NaN : p;
    }

    /** Signed degrees in [-180, 180) for a raw value, or NaN when uncalibrated
     * (cal null) or raw is not a value (OFF, MALFORMED). */
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
        StringBuilder sb = new StringBuilder(length * 2);
        for (int i = 0; i < length; i++) {
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
