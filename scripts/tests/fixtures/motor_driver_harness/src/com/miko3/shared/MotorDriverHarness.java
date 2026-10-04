package com.miko3.shared;

import emotix.com.drivers.SensorModule;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Host harness for DirectMotorDriver's tagged MCU frames (dark-floor mode): TOFDS turns
 * the MCU's own ToF safe-band check off, TOFEN turns it back on. The JNI UART is a stub
 * that records every write. Prints "PASS name" / "FAIL name: detail" per scenario.
 */
public final class MotorDriverHarness {
    private static void check(String name, boolean ok, String detail) {
        System.out.println(ok ? "PASS " + name : "FAIL " + name + ": " + detail);
    }

    /** tag + 'X' padding to 500 bytes, the shape generate500ByteData() builds. */
    static byte[] expected(String tag) {
        byte[] f = new byte[500];
        byte[] t = tag.getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < f.length; i++) {
            f[i] = i < t.length ? t[i] : (byte) 'X';
        }
        return f;
    }

    /** The frames written since mark whose first five bytes are tag. */
    static List<byte[]> written(int mark, String tag) {
        List<byte[]> out = new ArrayList<byte[]>();
        synchronized (SensorModule.WRITES) {
            for (int i = mark; i < SensorModule.WRITES.size(); i++) {
                byte[] f = SensorModule.WRITES.get(i);
                if (f.length >= 5 && new String(f, 0, 5, StandardCharsets.US_ASCII).equals(tag)) {
                    out.add(f);
                }
            }
        }
        return out;
    }

    static String describe(List<byte[]> frames) {
        if (frames.isEmpty()) {
            return "none written";
        }
        byte[] f = frames.get(0);
        return frames.size() + " written, first len=" + f.length + " head="
                + new String(f, 0, Math.min(12, f.length), StandardCharsets.US_ASCII);
    }

    public static void main(String[] args) throws Exception {
        DirectMotorDriver unconnected = new DirectMotorDriver();
        boolean threw = false;
        try {
            unconnected.disableTofCheck();
        } catch (IOException e) {
            threw = true;
        }
        boolean threwOn = false;
        try {
            unconnected.enableTofCheck();
        } catch (IOException e) {
            threwOn = true;
        }
        check("tof_check_commands_need_a_connection", threw && threwOn,
                "disable threw=" + threw + " enable threw=" + threwOn);

        DirectMotorDriver d = new DirectMotorDriver();
        d.connect();
        try {
            int mark = SensorModule.WRITES.size();
            d.disableTofCheck();
            List<byte[]> ds = written(mark, "TOFDS");
            check("disable_sends_exactly_tofds_padded_to_500",
                    ds.size() == 1 && java.util.Arrays.equals(ds.get(0), expected("TOFDS")), describe(ds));
            check("disable_sends_no_tofen", written(mark, "TOFEN").isEmpty(), describe(written(mark, "TOFEN")));

            mark = SensorModule.WRITES.size();
            d.enableTofCheck();
            List<byte[]> en = written(mark, "TOFEN");
            check("enable_sends_exactly_tofen_padded_to_500",
                    en.size() == 1 && java.util.Arrays.equals(en.get(0), expected("TOFEN")), describe(en));
            check("enable_sends_no_tofds", written(mark, "TOFDS").isEmpty(), describe(written(mark, "TOFDS")));

            mark = SensorModule.WRITES.size();
            d.enableTof();
            List<byte[]> legacy = written(mark, "TOFEN");
            check("enable_tof_is_the_same_tofen_frame",
                    legacy.size() == 1 && java.util.Arrays.equals(legacy.get(0), expected("TOFEN")), describe(legacy));
        } finally {
            d.disconnect();
        }
    }
}
