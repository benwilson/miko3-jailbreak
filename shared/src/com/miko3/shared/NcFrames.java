package com.miko3.shared;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * The same chip is the mic-array DSP (vendor libconexant_dsp_lib.so, disassembled
 * 2026-10-02, not yet sent on the robot): Setting builds the vendor's read, set and toggle
 * requests, a read's 19-byte reply (op 02, value at byte 14 with its own CRC32) reaches
 * Sink.reply(), and plan() picks the vendor-style changes. Nothing builds factory mode or
 * left AEC's (malformed) toggle.
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
    /** A request's op: read a setting. The chip answers with one 19-byte frame. */
    static final int OP_READ = 0x01;
    /** The op of a read's answer, as module 03's status frame shows (robot, 2026-09-30); a
     * 19-byte op 01 frame for a setting's module is taken as its answer too. */
    static final int OP_REPLY = 0x02;
    /** A request's op: set a value, or flip a switch. The vendor reads no reply. */
    static final int OP_SET = 0x03;
    /** Factory mode's module. Nothing here builds a frame for it, ever. */
    static final int MODULE_FACTORY = 0x09;

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

        /** A read reply for one DSP setting: its value, payload byte 0 (frame byte 14). */
        default void reply(Setting setting, int value) {
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
                } else if ((op == OP_READ || op == OP_REPLY) && Setting.of(module) != null
                        && payloadCrcOk(buf, i)) {
                    sink.reply(Setting.of(module), buf[i + RAW_INDEX] & 0xff);
                }
                i += FRAME_LENGTH;
            } else {
                // Junk, or a frame whose CRC fails: a real frame may start at the next byte.
                i++;
            }
        }
        return i;
    }

    /** The frame at `at` carries CRC32 LE of payload byte 0 at bytes 15 to 18, as every
     * status and direction frame captured on the robot does. Required of read replies only:
     * a misread value could make the apply send a toggle it should not. */
    private static boolean payloadCrcOk(byte[] b, int at) {
        CRC32 crc = new CRC32();
        crc.update(b, at + RAW_INDEX, 1);
        long c = crc.getValue();
        for (int i = 0; i < 4; i++) {
            if (b[at + RAW_INDEX + 1 + i] != (byte) (c >>> (8 * i))) {
                return false;
            }
        }
        return true;
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

    // ---- the DSP's settings (vendor libconexant_dsp_lib.so, disassembled 2026-10-02) ----

    /**
     * The mic-array DSP settings the vendor reads at start: each has a read request (14 bytes,
     * op 01, the vendor's own sequence byte), and gain and digital gain have a set request
     * (op 03 with a one-byte payload and its CRC); the switches have a toggle (op 03, no
     * payload, no reply) that flips them. Left AEC's vendor toggle frame is malformed, so it
     * has none here; factory mode (module 09) is not a setting here at all.
     */
    public enum Setting {
        GAIN(0x04, "gain", 0x0c, -1, true),
        DGAIN(0x12, "dgain", 0x0c, -1, true),
        AEC(0x06, "aec", 0x15, 0x13, false),
        LAEC(0x16, "laec", 0x01, -1, false),
        NS(0x08, "ns", 0x01, 0x01, false),
        LNS(0x18, "lns", 0x05, 0x04, false),
        CH(0x11, "ch", 0x01, 0x01, false),
        VOIP(0x05, "voip", 0x05, 0x04, false);

        public final int module;
        /** The name in the "nc:" log lines. */
        public final String label;
        private final int readSeq;
        private final int toggleSeq;
        /** Whether the value is set (gain, digital gain) rather than toggled. */
        public final boolean settable;

        Setting(int module, String label, int readSeq, int toggleSeq, boolean settable) {
            this.module = module;
            this.label = label;
            this.readSeq = readSeq;
            this.toggleSeq = toggleSeq;
            this.settable = settable;
        }

        /** The setting on that module, or null. */
        public static Setting of(int module) {
            for (Setting s : values()) {
                if (s.module == module) {
                    return s;
                }
            }
            return null;
        }

        /** The vendor's read request for this setting. */
        public byte[] read() {
            return request(module, OP_READ, readSeq, 0, null);
        }

        /** Whether this setting has a safe toggle (not gains, not left AEC). */
        public boolean toggleable() {
            return toggleSeq >= 0;
        }

        /** The vendor's toggle; IllegalStateException for a setting with none. */
        public byte[] toggle() {
            if (!toggleable()) {
                throw new IllegalStateException("no toggle for " + label);
            }
            return request(module, OP_SET, toggleSeq, 0, null);
        }

        /** The vendor's set request with value (0 to 255); IllegalStateException unless settable. */
        public byte[] set(int value) {
            if (!settable) {
                throw new IllegalStateException("no set for " + label);
            }
            return request(module, OP_SET, 0x01, 0x01, new byte[] {(byte) value});
        }
    }

    /** XXUB, module, op, seq, flag, the payload's length with its CRC (LE, 2 bytes), the CRC32
     * LE of those 10 bytes, then the payload and its CRC32 LE when there is one. */
    private static byte[] request(int module, int op, int seq, int flag, byte[] payload) {
        int len = payload == null ? 0 : payload.length + 4;
        byte[] f = new byte[14 + len];
        System.arraycopy(PREFIX, 0, f, 0, 4);
        f[4] = (byte) module;
        f[5] = (byte) op;
        f[6] = (byte) seq;
        f[7] = (byte) flag;
        f[8] = (byte) len;
        f[9] = (byte) (len >>> 8);
        putCrc(f, 0, 10, 10);
        if (payload != null) {
            System.arraycopy(payload, 0, f, 14, payload.length);
            putCrc(f, 14, payload.length, 14 + payload.length);
        }
        return f;
    }

    private static void putCrc(byte[] f, int from, int length, int to) {
        CRC32 crc = new CRC32();
        crc.update(f, from, length);
        long c = crc.getValue();
        for (int i = 0; i < 4; i++) {
            f[to + i] = (byte) (c >>> (8 * i));
        }
    }

    /** The vendor's targets: gain 30 (listening, speaking and idle alike), digital gain 0,
     * AEC, left AEC, NS and left NS on, channel 0, VOIP off. */
    public static int vendorTarget(Setting s) {
        switch (s) {
            case GAIN:
                return 30;
            case DGAIN:
            case CH:
            case VOIP:
                return 0;
            default:
                return 1;
        }
    }

    /**
     * The requests that bring the read values (setting -> value, absent when the chip did not
     * answer) to the targets, the vendor's way: a gain is set only when it was read and
     * differs; a switch is toggled only when it was read as 0 or 1 and differs. An unread
     * setting, a switch read as anything else, left AEC and factory mode are never sent.
     * In Setting order.
     */
    public static List<byte[]> plan(Map<Setting, Integer> read, int gain, int dgain) {
        List<byte[]> out = new ArrayList<byte[]>();
        for (Setting s : Setting.values()) {
            Integer v = read.get(s);
            if (v == null) {
                continue;
            }
            if (s.settable) {
                int want = s == Setting.GAIN ? gain : dgain;
                if (v != want) {
                    out.add(s.set(want));
                }
            } else if (s.toggleable() && (v == 0 || v == 1) && v != vendorTarget(s)) {
                out.add(s.toggle());
            }
        }
        return out;
    }

    /** "gain=30 dgain=0 aec=1 ..." in Setting order, '?' for a setting not read. */
    public static String describe(Map<Setting, Integer> read) {
        StringBuilder sb = new StringBuilder();
        for (Setting s : Setting.values()) {
            Integer v = read.get(s);
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(s.label).append('=').append(v == null ? "?" : String.valueOf(v));
        }
        return sb.toString();
    }

    /** The launcher's NC DSP settings from their properties ("" or null when unset). */
    public static final class Control {
        /** Log the settings at each ears open; default on (reads are vendor-normal). */
        public final boolean status;
        /** Apply the targets at each ears open; default off. */
        public final boolean apply;
        public final int gain;
        public final int dgain;

        private Control(boolean status, boolean apply, int gain, int dgain) {
            this.status = status;
            this.apply = apply;
            this.gain = gain;
            this.dgain = dgain;
        }

        /** status: on unless "0"/"false"; apply: off unless "1"/"true"; gain default 30
         * clamped to 0..60; dgain default 0 clamped to 0..30. */
        public static Control of(String status, String apply, String gain, String dgain) {
            return new Control(!isFalse(status), isTrue(apply), number(gain, 30, 0, 60), number(dgain, 0, 0, 30));
        }

        /** Whether anything is to be done at ears open. */
        public boolean any() {
            return status || apply;
        }

        private static boolean isTrue(String v) {
            v = v == null ? "" : v.trim();
            return v.equals("1") || v.equalsIgnoreCase("true");
        }

        private static boolean isFalse(String v) {
            v = v == null ? "" : v.trim();
            return v.equals("0") || v.equalsIgnoreCase("false");
        }

        private static int number(String v, int dflt, int min, int max) {
            try {
                return Math.max(min, Math.min(max, Integer.parseInt(v.trim())));
            } catch (RuntimeException e) {
                return dflt;
            }
        }

        @Override
        public String toString() {
            return "status " + status + " apply " + apply + " gain " + gain + " dgain " + dgain;
        }
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
