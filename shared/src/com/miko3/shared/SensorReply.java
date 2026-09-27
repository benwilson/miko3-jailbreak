package com.miko3.shared;

import java.nio.charset.StandardCharsets;

/**
 * Parses the MCU's text replies (docs/hardware/tof-sensor.md). A reply is a run
 * of sections, each an uppercase key and '=' (POWER=, TOFIR=, GSTFL=...) with no
 * separator between sections, and 'X' used as padding both inside fields and at
 * the end. Sections are found by their token, never by offset: the decompiled
 * sources disagree on where TOFIR sits, and live records match none of them.
 *
 * Never throws on malformed input; anything it cannot read confidently comes
 * back as "no reading" rather than as zeros, because a made-up zero distance
 * and a made-up clear path are both unsafe.
 *
 * Plain Java (no android.*) so it runs on the host JVM under
 * scripts/tests/test_sensor_reply.py.
 */
public final class SensorReply {
    private static final String TOFIR = "TOFIR=";
    private static final String CPL = "CPL=";
    private static final String LEFT = "Left=";
    private static final String RIGHT = "Right=";
    private static final String IMUGY = "IMUGY=";
    private static final String IMUAC = "IMUAC=";

    private SensorReply() {
    }

    /** The TOFIR reading in {@code reply}, stamped {@code timestampMs}, or null when the
     * reply has no TOFIR section or its tof field is not a whole comma-terminated number. */
    public static SensorSnapshot parse(byte[] reply, long timestampMs) {
        return reply == null ? null : parse(text(reply), timestampMs);
    }

    /** As {@link #parse(byte[], long)}, on a reply already decoded with {@link #text}. */
    public static SensorSnapshot parse(String text, long timestampMs) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        int start = text.indexOf(TOFIR);
        if (start < 0) {
            return null;
        }
        String[] fields = sectionBody(text, start + TOFIR.length()).split(",", -1);
        // A tof with no comma after it is a record cut off mid-field; "002" there
        // could be the front of "00248".
        if (fields.length < 2) {
            return null;
        }
        int tof = number(fields[0]);
        if (tof == SensorSnapshot.ABSENT) {
            return null;
        }
        int ir1 = number(fields[1]);
        int ir2 = fields.length > 2 ? number(fields[2]) : SensorSnapshot.ABSENT;
        Long left = count(text, LEFT);
        Long right = count(text, RIGHT);
        boolean wheels = left != null && right != null;
        int[] gyro = signedTriple(text, IMUGY);
        boolean hasGyro = gyro != null;
        if (!hasGyro) {
            gyro = new int[] {SensorSnapshot.ABSENT, SensorSnapshot.ABSENT, SensorSnapshot.ABSENT};
        }
        int[] accel = signedTriple(text, IMUAC);
        boolean hasAccel = accel != null;
        if (!hasAccel) {
            accel = new int[] {SensorSnapshot.ABSENT, SensorSnapshot.ABSENT, SensorSnapshot.ABSENT};
        }
        return new SensorSnapshot(timestampMs, tof, ir1, ir2, wheels, wheels ? left : SensorSnapshot.ABSENT,
                wheels ? right : SensorSnapshot.ABSENT, hasGyro, gyro[0], gyro[1], gyro[2],
                hasAccel, accel[0], accel[1], accel[2]);
    }

    /** The three signed fields after {@code key}: the gyro rates after IMUGY=
     * ("0000000062,-000000757,0000000093", explore nav plan U1) or the accelerometer
     * after IMUAC= ("-000002110,0000000295,0000023297X", meeting plan U1, KTD5). Null
     * unless all three read cleanly. The section must end at the next section key:
     * one running into the end of the reply may be cut off mid-field, and a partial
     * value would read as a smaller one. Trailing 'X' padding (the live IMUAC section
     * ends in one) is not part of the last field. */
    private static int[] signedTriple(String text, String key) {
        int start = text.indexOf(key);
        if (start < 0) {
            return null;
        }
        int from = start + key.length();
        String body = sectionBody(text, from);
        if (from + body.length() >= text.length()) {
            return null;
        }
        int end = body.length();
        while (end > 0 && body.charAt(end - 1) == 'X') {
            end--;
        }
        String[] fields = body.substring(0, end).split(",", -1);
        if (fields.length != 3) {
            return null;
        }
        int[] out = new int[3];
        for (int i = 0; i < 3; i++) {
            Integer v = signed(fields[i]);
            if (v == null) {
                return null;
            }
            out[i] = v;
        }
        return out;
    }

    /** An optional '-' then 1-10 ASCII digits, as its value; anything else (padding,
     * empty, a stray sign, out of int range) is null. Beside number(), which reads only
     * unsigned fields of up to 5 digits. */
    private static Integer signed(String field) {
        int i = field.startsWith("-") ? 1 : 0;
        int digits = field.length() - i;
        if (digits < 1 || digits > 10) {
            return null;
        }
        for (int k = i; k < field.length(); k++) {
            if (field.charAt(k) < '0' || field.charAt(k) > '9') {
                return null;
            }
        }
        long v = Long.parseLong(field);
        return v < Integer.MIN_VALUE || v > Integer.MAX_VALUE ? null : Integer.valueOf((int) v);
    }

    /** The wheel encoder count after {@code key} ("Left=0000068312," or, reversing past
     * zero, "Left=-000002764,"): an optional '-' then 1-10 digits, ended by a comma (both
     * counts always have one), else null. A count cut off mid-field is null, never a
     * smaller number. Signed: -1 is a real count (live 2026-09-25). */
    private static Long count(String text, String key) {
        int start = text.indexOf(key);
        if (start < 0) {
            return null;
        }
        int sign = start + key.length();
        int i = sign < text.length() && text.charAt(sign) == '-' ? sign + 1 : sign;
        int end = i;
        while (end < text.length() && text.charAt(end) >= '0' && text.charAt(end) <= '9') {
            end++;
        }
        if (end == i || end - i > 10 || end >= text.length() || text.charAt(end) != ',') {
            return null;
        }
        return Long.parseLong(text.substring(sign, end));
    }

    /** The CPL motion-ack value in {@code reply} (2 = the MCU refused forward motion for an
     * edge/obstacle), or ABSENT when the reply carries none. Missing is never read as 0. */
    public static int parseCpl(byte[] reply) {
        return reply == null ? SensorSnapshot.ABSENT : parseCpl(text(reply));
    }

    /** As {@link #parseCpl(byte[])}, on a reply already decoded with {@link #text}. */
    public static int parseCpl(String text) {
        if (text == null) {
            return SensorSnapshot.ABSENT;
        }
        int start = text.indexOf(CPL);
        if (start < 0) {
            return SensorSnapshot.ABSENT;
        }
        int i = start + CPL.length();
        int end = i;
        while (end < text.length() && Character.isDigit(text.charAt(end))) {
            end++;
        }
        return end == i ? SensorSnapshot.ABSENT : number(text.substring(i, end));
    }

    /**
     * The charger latch after {@code reply} (meeting plan U1, KTD6). The MCU reports
     * CPL=3 (charger connected, motion stopped; docs/hardware/motors-wheels.md) only in
     * motion acknowledgements, so the flag is a latch: set by any reply carrying CPL=3,
     * kept by a reply with no CPL at all (every POWER poll), and cleared only by a later
     * acknowledgement whose CPL is another value. CPL=2 is a forward refusal and never
     * reads as charging; it clears the latch like any other acknowledgement.
     */
    public static boolean chargerLatch(boolean latched, String reply) {
        int cpl = parseCpl(reply);
        if (cpl == 3) {
            return true;
        }
        return cpl == SensorSnapshot.ABSENT ? latched : false;
    }

    /** A reply as the ASCII text both parsers read, so a caller parsing it twice decodes once. */
    public static String text(byte[] reply) {
        return new String(reply, StandardCharsets.US_ASCII);
    }

    /** From {@code from} up to the next section key: an uppercase letter other than the
     * 'X' padding character. */
    private static String sectionBody(String text, int from) {
        int end = from;
        while (end < text.length()) {
            char c = text.charAt(end);
            if (c >= 'A' && c <= 'Z' && c != 'X') {
                break;
            }
            end++;
        }
        return text.substring(from, end);
    }

    /** A field of 1-5 ASCII digits as its value; padding, empty, or anything else is ABSENT. */
    private static int number(String field) {
        String f = field.trim();
        if (f.isEmpty() || f.length() > 5) {
            return SensorSnapshot.ABSENT;
        }
        for (int i = 0; i < f.length(); i++) {
            if (f.charAt(i) < '0' || f.charAt(i) > '9') {
                return SensorSnapshot.ABSENT;
            }
        }
        return Integer.parseInt(f);
    }
}
