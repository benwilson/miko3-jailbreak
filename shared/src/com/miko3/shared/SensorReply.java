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
    private static final String POWER = "POWER=";

    /**
     * The charging current, in the 4th POWER field's own units (mA by the look of it), from
     * which a reply reads as docked even when the 1st field is 0. Measured 2026-10-01/02:
     * -357 and -234 off the charger (draining), +1002 on it (charging). Fully charged on
     * the dock the current may fall toward 0, which the 1st field (2 there) still covers;
     * +200 is far above any draining value and far below a real charge, so neither noise
     * around 0 nor a small positive blip off the dock reads as docked.
     */
    public static final int DOCKED_CURRENT_MIN = 200;

    /**
     * The MCU's POWER section (docs/hardware/motors-wheels.md), as far as it is read here:
     * "POWER=state,?,mV,current,mV,current,percent,..". Off the charger state was 0
     * (2026-10-01 and the older doc sample), on the dock 2 (2026-10-02, charging). What a
     * fully charged dock reports is unmeasured (1 or 3 are both plausible), so any state
     * above 0 reads as docked, as does a current of at least DOCKED_CURRENT_MIN.
     */
    public static final class Power {
        /** The 1st field: 0 off the dock, 2 on it charging (others unmeasured). */
        public final int state;
        /** The 4th field, signed: negative while draining, positive while charging. */
        public final int current;
        /** The 7th field, which reads like a battery percentage (56 off, 100 on); ABSENT when not read. */
        public final int percent;

        public Power(int state, int current, int percent) {
            this.state = state;
            this.current = current;
            this.percent = percent;
        }

        /** On the dock: the state field above 0, or a charging current of at least DOCKED_CURRENT_MIN. */
        public boolean docked() {
            return state > 0 || current >= DOCKED_CURRENT_MIN;
        }

        @Override
        public String toString() {
            return "power state=" + state + " current=" + current
                    + (percent == SensorSnapshot.ABSENT ? "" : " percent=" + percent) + (docked() ? " DOCKED" : "");
        }
    }

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
                hasAccel, accel[0], accel[1], accel[2], parsePower(text));
    }

    /**
     * The POWER section in {@code text}, or null when there is none or it cannot be read
     * confidently. The state (1st field) must be 1-5 digits and the current (4th) an
     * optional '-' and 1-10 digits, each ended by a comma: a field cut off at the end
     * of the section ("01002" could be the front of "010020") never reads as a value.
     * The percentage (7th) is ABSENT unless it too is whole and comma-terminated. Works
     * on a POWER reply whether or not it carried TOFIR, so a dock is seen with the ToF
     * dead.
     */
    public static Power parsePower(String text) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf(POWER);
        if (start < 0) {
            return null;
        }
        String[] fields = sectionBody(text, start + POWER.length()).split(",", -1);
        if (fields.length < 5) {
            return null;
        }
        int state = number(fields[0]);
        Integer current = signed(fields[3]);
        if (state == SensorSnapshot.ABSENT || current == null) {
            return null;
        }
        int percent = fields.length > 7 ? number(fields[6]) : SensorSnapshot.ABSENT;
        return new Power(state, current, percent);
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
