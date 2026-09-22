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
        return new SensorSnapshot(timestampMs, tof, number(fields[1]),
                fields.length > 2 ? number(fields[2]) : SensorSnapshot.ABSENT);
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
