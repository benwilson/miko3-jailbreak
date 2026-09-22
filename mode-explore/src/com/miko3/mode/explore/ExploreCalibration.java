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
 * Plain Java so the host harness can round-trip it.
 */
final class ExploreCalibration {
    static final String FILE_NAME = "explore-calibration.properties";

    private ExploreCalibration() {
    }

    /** The calibration in {@code file}, or null when it is missing, unreadable, malformed,
     * or not complete enough to drive on. */
    static ExploreTuning.Calibration read(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        Properties p = new Properties();
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            p.load(in);
        } catch (IOException | IllegalArgumentException e) {
            return null;
        } finally {
            closeQuietly(in);
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

    static void write(File file, ExploreTuning.Calibration c) throws IOException {
        Properties p = new Properties();
        p.setProperty("obstacleTofBelow", Integer.toString(c.obstacleTofBelow));
        p.setProperty("edgeTofAbove", Integer.toString(c.edgeTofAbove));
        p.setProperty("edgeIr", Integer.toString(c.edgeIr));
        p.setProperty("edgeIrAbove", Boolean.toString(c.edgeIrAbove));
        OutputStream out = new FileOutputStream(file);
        try {
            p.store(out, "explore mode sensor calibration (scripts/qa-explore-mode.py)");
        } finally {
            out.close();
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
