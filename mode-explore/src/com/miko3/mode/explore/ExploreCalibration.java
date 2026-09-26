package com.miko3.mode.explore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;

/**
 * The on-device sensor calibration (KTD9): the thresholds that turn a ToF/IR
 * reading into "edge" or "obstacle", measured on the owner's desk by
 * scripts/qa-explore-mode.py and pushed into the app's files dir. There are no
 * built-in thresholds: with no file, or an unreadable or incomplete one, the
 * brain treats the sensors as unavailable and the robot stays still (R10), so a
 * fresh install never drives on guessed values.
 *
 * A java.util.Properties file:
 *   obstacleTofBelow=60
 *   edgeTofAbove=400
 *   edgeIr=-1
 *   edgeIrAbove=true
 * A negative threshold switches that rule off (ExploreTuning.Calibration).
 *
 * The same file optionally carries the gyroscope's yaw calibration (explore nav
 * plan U1, KTD1), measured by scripts/qa-explore-sensors.py --gyro-circle:
 *   gyroAxis=z
 *   gyroSign=-1
 *   gyroCountSecondsPer360=41234.5
 * These keys are read on their own (readGyro): without them, or with any of
 * them malformed, the gyro reads as uncalibrated and the floor rules still load.
 * Both QA scripts, and both writers here, read-modify-write the file so each
 * keeps the other's keys.
 *
 * Plain Java so the host harness can round-trip it.
 */
final class ExploreCalibration {
    static final String FILE_NAME = "explore-calibration.properties";

    static final String GYRO_AXIS = "gyroAxis";
    static final String GYRO_SIGN = "gyroSign";
    static final String GYRO_SCALE = "gyroCountSecondsPer360";
    private static final String AXES = "xyz";

    /**
     * Which raw gyro rate is yaw and how to read it (explore nav plan U1). A heading
     * change in degrees is sign * (integral of (rate - bias) dt, in counts x seconds)
     * * 360 / countSecondsPer360, positive turning left (the driver's convention).
     */
    static final class Gyro {
        /** 0, 1 or 2: SensorReading.gyroX, gyroY or gyroZ. */
        final int axis;
        /** +1 or -1: what makes a left turn read positive. */
        final int sign;
        /** The rate integrated over one full turn, bias removed, in counts x seconds. */
        final double countSecondsPer360;

        Gyro(int axis, int sign, double countSecondsPer360) {
            this.axis = axis;
            this.sign = sign;
            this.countSecondsPer360 = countSecondsPer360;
        }

        /** This calibration's axis out of a reading that carried the gyro. */
        int rate(SensorReading r) {
            return axis == 0 ? r.gyroX : axis == 1 ? r.gyroY : r.gyroZ;
        }

        @Override
        public String toString() {
            return "gyro axis=" + AXES.charAt(axis) + " sign=" + sign + " countSecondsPer360=" + countSecondsPer360;
        }
    }

    private ExploreCalibration() {
    }

    /** The calibration in {@code file}, or null when it is missing, unreadable, malformed,
     * or not complete enough to drive on. */
    static ExploreTuning.Calibration read(File file) {
        Properties p = load(file);
        if (p == null) {
            return null;
        }
        try {
            String above = p.getProperty("edgeIrAbove", "true").trim();
            if (!above.equals("true") && !above.equals("false")) {
                return null;
            }
            ExploreTuning.Calibration c = new ExploreTuning.Calibration(
                    Integer.parseInt(p.getProperty("obstacleTofBelow", "-1").trim()),
                    Integer.parseInt(p.getProperty("edgeTofAbove", "-1").trim()),
                    Integer.parseInt(p.getProperty("edgeIr", "-1").trim()),
                    Boolean.parseBoolean(above));
            return c.complete() ? c : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The gyro calibration in {@code file}, or null ("uncalibrated") when the file or any
     * of the three gyro keys is missing or malformed. */
    static Gyro readGyro(File file) {
        Properties p = load(file);
        if (p == null) {
            return null;
        }
        String axis = p.getProperty(GYRO_AXIS, "").trim();
        String sign = p.getProperty(GYRO_SIGN, "").trim();
        String scale = p.getProperty(GYRO_SCALE, "").trim();
        if (axis.length() != 1 || AXES.indexOf(axis.charAt(0)) < 0
                || !(sign.equals("1") || sign.equals("-1"))) {
            return null;
        }
        double counts;
        try {
            counts = Double.parseDouble(scale);
        } catch (NumberFormatException e) {
            return null;
        }
        if (Double.isNaN(counts) || Double.isInfinite(counts) || counts <= 0) {
            return null;
        }
        return new Gyro(AXES.indexOf(axis.charAt(0)), Integer.parseInt(sign), counts);
    }

    /** Writes the floor-sensor thresholds, keeping any gyro keys already in the file. */
    static void write(File file, ExploreTuning.Calibration c) throws IOException {
        Properties p = existing(file);
        p.setProperty("obstacleTofBelow", Integer.toString(c.obstacleTofBelow));
        p.setProperty("edgeTofAbove", Integer.toString(c.edgeTofAbove));
        p.setProperty("edgeIr", Integer.toString(c.edgeIr));
        p.setProperty("edgeIrAbove", Boolean.toString(c.edgeIrAbove));
        store(file, p);
    }

    /** Writes the gyro calibration, keeping any floor-sensor keys already in the file. */
    static void writeGyro(File file, Gyro g) throws IOException {
        Properties p = existing(file);
        p.setProperty(GYRO_AXIS, String.valueOf(AXES.charAt(g.axis)));
        p.setProperty(GYRO_SIGN, Integer.toString(g.sign));
        p.setProperty(GYRO_SCALE, Double.toString(g.countSecondsPer360));
        store(file, p);
    }

    private static Properties existing(File file) {
        Properties p = load(file);
        return p == null ? new Properties() : p;
    }

    private static void store(File file, Properties p) throws IOException {
        OutputStream out = new FileOutputStream(file);
        try {
            p.store(out, "explore mode sensor calibration (scripts/qa-explore-mode.py, qa-explore-sensors.py)");
        } finally {
            out.close();
        }
    }

    /** The file's properties, or null when it is missing or unreadable. */
    private static Properties load(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        Properties p = new Properties();
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            p.load(in);
            return p;
        } catch (IOException | IllegalArgumentException e) {
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    private static void closeQuietly(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }
}
