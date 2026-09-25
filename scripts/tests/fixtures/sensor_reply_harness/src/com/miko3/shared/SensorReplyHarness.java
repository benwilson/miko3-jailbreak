package com.miko3.shared;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/**
 * Host-JVM checks for SensorReply (U2), driven by
 * scripts/tests/test_sensor_reply.py. The first argument is the captured
 * baseline file (scripts/tests/fixtures/explore_sensor_records/baseline.txt,
 * one "SENT<TAB>reply" line per record), so the main cases run against real MCU
 * records rather than hand-built strings.
 *
 * Prints one "PASS <name>" or "FAIL <name>: <detail>" line per scenario; the
 * Python side asserts on each by name.
 */
public final class SensorReplyHarness {
    private static final long T = 123456L;

    private static String replyOf(String line) {
        return line.substring(line.indexOf('\t') + 1);
    }

    private static void check(String name, boolean ok, String detail) {
        System.out.println(ok ? "PASS " + name : "FAIL " + name + ": " + detail);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    /** The captured record with enough 'X' padding appended to reach the 500 bytes
     * SensorModule.read() actually returns (the capture trimmed trailing padding). */
    private static byte[] padded(String reply) {
        StringBuilder sb = new StringBuilder(reply);
        while (sb.length() < 500) {
            sb.append('X');
        }
        return bytes(sb.toString());
    }

    public static void main(String[] args) throws IOException {
        List<String> lines = Files.readAllLines(Paths.get(args[0]), StandardCharsets.US_ASCII);
        String captured = replyOf(lines.get(0));

        SensorSnapshot s = SensorReply.parse(padded(captured), T);
        check("captured_record_parses",
                s != null && s.tof == 251 && s.ir1 == SensorSnapshot.ABSENT && s.ir2 == 0
                        && s.timestampMs == T && !s.fault,
                "got " + s);

        boolean allParse = true;
        for (String line : lines) {
            SensorSnapshot each = SensorReply.parse(padded(replyOf(line)), T);
            allParse &= each != null && each.tof > 0 && each.tof < 16383;
        }
        check("every_captured_record_parses", allParse, "a baseline record failed to parse");

        // The decompiled sample string puts TOFIR at a different offset; token search
        // must not care.
        String shifted = "POWER=1,1,07000FLBTN=0,0TOFIR=00123,00045,1,XXXXGSTFL=0,0";
        SensorSnapshot sh = SensorReply.parse(bytes(shifted), T);
        check("tofir_found_by_token_at_any_offset",
                sh != null && sh.tof == 123 && sh.ir1 == 45 && sh.ir2 == 1, "got " + sh);

        check("record_without_tofir_gives_no_snapshot",
                SensorReply.parse(bytes("POWER=0,0,07884,-0234GSTFL=0,0,0"), T) == null, "expected null");

        check("truncated_tof_gives_no_snapshot",
                SensorReply.parse(bytes("POWER=0,0GLPOS=00000TOFIR=002"), T) == null,
                "a cut-off tof must not read as a short value");

        check("garbage_gives_no_snapshot",
                SensorReply.parse(bytes("\u0001\u0002TOFIR=abc,,\u0000"), T) == null
                        && SensorReply.parse(new byte[0], T) == null
                        && SensorReply.parse((byte[]) null, T) == null,
                "expected null for garbage/empty/null");

        SensorSnapshot dead = SensorReply.parse(bytes("TOFIR=16383,XXXX,0,XXXXGSTFL=0"), T);
        check("dead_sensor_value_is_flagged_fault",
                dead != null && dead.tof == 16383 && dead.fault, "got " + dead);

        check("error_uart_is_not_sensor_data",
                SensorReply.parse(bytes("ERROR_UART"), T) == null, "expected null");

        check("cpl_two_is_read_from_a_drive_reply",
                SensorReply.parseCpl(bytes("VEL1OKCPL=2,XXXXXXXX")) == 2, "expected 2");

        check("missing_cpl_is_unknown_not_zero",
                SensorReply.parseCpl(padded(captured)) == SensorSnapshot.ABSENT, "expected ABSENT");

        SensorSnapshot w = SensorReply.parse(bytes(
                "POWER=0,0TOFIR=00209,XXXX,0,XXXXHEADTM=0Left=0000068312,Right=0000059810,00,00,00000,0,0"), T);
        check("wheel_counts_are_read",
                w != null && w.wheelLeft == 68312 && w.wheelRight == 59810, "got " + w);

        check("missing_or_cut_off_wheel_counts_are_absent",
                SensorReply.parse(bytes("TOFIR=00209,XXXX,0,XXXX"), T).wheelLeft == SensorSnapshot.ABSENT
                        && SensorReply.parse(bytes("TOFIR=00209,XXXX,0,XXXXLeft=00000683"), T).wheelLeft
                        == SensorSnapshot.ABSENT
                        && SensorReply.parse(bytes("TOFIR=00209,XXXX,0,XXXXLeft=XXXX,Right=1,"), T).wheelLeft
                        == SensorSnapshot.ABSENT,
                "expected ABSENT");

        // ---- gyro (explore nav plan U1): IMUGY= is three signed rate fields ----
        check("captured_record_carries_the_gyro",
                s != null && s.hasGyro && s.gyroX == 62 && s.gyroY == -757 && s.gyroZ == 93, "got " + s);

        boolean allGyro = true;
        for (String line : lines) {
            SensorSnapshot each = SensorReply.parse(padded(replyOf(line)), T);
            allGyro &= each != null && each.hasGyro && each.gyroY < 0;
        }
        check("every_captured_record_carries_the_gyro", allGyro, "a baseline record lost its gyro");

        SensorSnapshot neg = SensorReply.parse(bytes(
                "IMUGY=-000001234,0000000005,-000000001IMUMG=0TOFIR=00209,XXXX,0,XXXX"), T);
        check("gyro_sign_and_leading_zeros_are_read",
                neg != null && neg.hasGyro && neg.gyroX == -1234 && neg.gyroY == 5 && neg.gyroZ == -1,
                "got " + neg);

        SensorSnapshot allX = SensorReply.parse(bytes("IMUGY=XXXXXXXXXX,XXXXXXXXXX,XXXXXXXXXXIMUMG=0"
                + "TOFIR=00209,XXXX,0,XXXXLeft=0000068312,Right=0000059810,00"), T);
        SensorSnapshot none = SensorReply.parse(bytes(
                "POWER=0,0TOFIR=00209,XXXX,0,XXXXLeft=0000068312,Right=0000059810,00"), T);
        check("absent_gyro_leaves_the_rest_of_the_record",
                allX != null && !allX.hasGyro && allX.tof == 209 && allX.wheelLeft == 68312
                        && none != null && !none.hasGyro && none.tof == 209 && none.wheelRight == 59810,
                "all-X " + allX + " / missing " + none);

        String tail = "TOFIR=00209,XXXX,0,XXXX";
        check("cut_off_or_malformed_gyro_is_absent",
                !SensorReply.parse(bytes(tail + "IMUGY=0000000062,-000000757,00000"), T).hasGyro
                        && !SensorReply.parse(bytes("IMUGY=0000000062,-000000757IMUMG=0" + tail), T).hasGyro
                        && !SensorReply.parse(bytes("IMUGY=00000000X2,1,2IMUMG=0" + tail), T).hasGyro
                        && !SensorReply.parse(bytes("IMUGY=1,--2,3IMUMG=0" + tail), T).hasGyro
                        && !SensorReply.parse(bytes("IMUGY=1,,3IMUMG=0" + tail), T).hasGyro
                        && !SensorReply.parse(bytes("IMUGY=1,2,3,4IMUMG=0" + tail), T).hasGyro
                        && !SensorReply.parse(bytes("IMUGY=1,2,00000000001IMUMG=0" + tail), T).hasGyro,
                "a partial or malformed gyro must not read as a smaller rate");

        check("malformed_cpl_is_unknown",
                SensorReply.parseCpl(bytes("CPL=X,")) == SensorSnapshot.ABSENT
                        && SensorReply.parseCpl((byte[]) null) == SensorSnapshot.ABSENT, "expected ABSENT");
    }
}
