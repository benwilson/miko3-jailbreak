package com.miko3.mode.explore;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Which detector model and execution provider Explore runs (detector speed
 * plan), read from system properties. Every switch is off by default: with none
 * set it is the shipped detector.onnx on ONNX Runtime's CPU provider with 2
 * intra-op threads, exactly as before.
 *
 *   persist.miko3.explore.detector_ep     cpu (default) | xnnpack | nnapi
 *   persist.miko3.explore.detector_model  detector.onnx (default) or a shipped
 *                                         variant asset, e.g. detector-rgba.onnx
 *   debug.miko3.explore.bench             1: at startup, time the detector over
 *                                         the bench frames and never open the
 *                                         camera (DetectorBench)
 *   debug.miko3.explore.bench_configs     ep/model,... for the bench (default:
 *                                         every shipped model on cpu and xnnpack)
 *   debug.miko3.explore.bench_rounds      timed passes over the frames (default 5)
 *
 * Plain Java so it runs on the host JVM (scripts/tests/test_explore_detector.py).
 */
final class DetectorConfig {
    static final String EP_PROPERTY = "persist.miko3.explore.detector_ep";
    static final String MODEL_PROPERTY = "persist.miko3.explore.detector_model";
    static final String BENCH_PROPERTY = "debug.miko3.explore.bench";
    static final String BENCH_CONFIGS_PROPERTY = "debug.miko3.explore.bench_configs";
    static final String BENCH_ROUNDS_PROPERTY = "debug.miko3.explore.bench_rounds";

    static final String CPU = "cpu";
    static final String XNNPACK = "xnnpack";
    static final String NNAPI = "nnapi";
    static final String DEFAULT_MODEL = "detector.onnx";
    static final int DEFAULT_ROUNDS = 5;
    static final int MAX_ROUNDS = 50;
    /** Asset or pushed-file names the app will load: no paths, no other files. */
    private static final Pattern MODEL_NAME = Pattern.compile("detector(-[a-z0-9]+)*\\.onnx");

    final String ep;
    final String model;

    DetectorConfig(String ep, String model) {
        this.ep = ep;
        this.model = model;
    }

    static DetectorConfig defaults() {
        return new DetectorConfig(CPU, DEFAULT_MODEL);
    }

    /** From the properties' values; unknown or malformed values fall back to the default. */
    static DetectorConfig parse(String ep, String model) {
        return new DetectorConfig(parseEp(ep), isModelName(model) ? model.trim() : DEFAULT_MODEL);
    }

    static DetectorConfig fromSystem() {
        return parse(systemProperty(EP_PROPERTY), systemProperty(MODEL_PROPERTY));
    }

    static String parseEp(String value) {
        String v = value == null ? "" : value.trim().toLowerCase(java.util.Locale.US);
        return XNNPACK.equals(v) || NNAPI.equals(v) ? v : CPU;
    }

    static boolean isModelName(String value) {
        return value != null && MODEL_NAME.matcher(value.trim()).matches();
    }

    /**
     * What OnnxRecognizer tries, in order: this configuration; the same model on
     * the CPU if another provider was asked for; the shipped model on the CPU if
     * a variant was. The default configuration is a single attempt, as before.
     */
    List<DetectorConfig> attempts() {
        List<DetectorConfig> out = new ArrayList<DetectorConfig>();
        out.add(this);
        if (!CPU.equals(ep)) {
            out.add(new DetectorConfig(CPU, model));
        }
        if (!DEFAULT_MODEL.equals(model)) {
            out.add(defaults());
        }
        return out;
    }

    boolean isDefault() {
        return CPU.equals(ep) && DEFAULT_MODEL.equals(model);
    }

    @Override
    public String toString() {
        return "ep=" + ep + " model=" + model;
    }

    static boolean benchRequested(String value) {
        String v = value == null ? "" : value.trim();
        return "1".equals(v) || "true".equalsIgnoreCase(v);
    }

    static int benchRounds(String value) {
        try {
            int n = Integer.parseInt(value == null ? "" : value.trim());
            return n < 1 ? DEFAULT_ROUNDS : Math.min(n, MAX_ROUNDS);
        } catch (NumberFormatException e) {
            return DEFAULT_ROUNDS;
        }
    }

    /**
     * The bench's configurations: "ep/model,ep/model" (unknown entries skipped),
     * or, when blank, every available model on cpu then xnnpack. Models not in
     * available are skipped too, so a typo can't make the bench load a missing file.
     */
    static List<DetectorConfig> benchConfigs(String spec, List<String> available) {
        List<DetectorConfig> out = new ArrayList<DetectorConfig>();
        String s = spec == null ? "" : spec.trim();
        if (s.isEmpty()) {
            for (String ep : new String[]{CPU, XNNPACK}) {
                for (String m : available) {
                    out.add(new DetectorConfig(ep, m));
                }
            }
            return out;
        }
        for (String item : s.split(",")) {
            String[] parts = item.trim().split("/", 2);
            if (parts.length != 2) {
                continue;
            }
            String ep = parts[0].trim().toLowerCase(java.util.Locale.US);
            String m = parts[1].trim();
            if ((CPU.equals(ep) || XNNPACK.equals(ep) || NNAPI.equals(ep)) && available.contains(m)) {
                out.add(new DetectorConfig(ep, m));
            }
        }
        return out;
    }

    /** android.os.SystemProperties.get(key), which apps may read; "" if unset,
     * unreadable, or off the robot (the host JVM has no such class). */
    static String systemProperty(String key) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            Method get = c.getMethod("get", String.class, String.class);
            return (String) get.invoke(null, key, "");
        } catch (Exception e) {
            return "";
        }
    }
}
