package com.miko3.launcher;

/**
 * The ears' software microphone gain (owner 2026-10-02 at home: "his microphone has a hard
 * time hearing things"): persist.miko3.ears.gain_db, a whole number of dB in [0, MAX_DB],
 * applied to every sample before the wake word, the speech detector and the recogniser.
 * Unset or malformed: 0 dB, as before.
 */
final class MicGain {
    static final String PROP = "persist.miko3.ears.gain_db";
    static final int MAX_DB = 18;

    private MicGain() {
    }

    /** The linear factor for the property's value. */
    static float factor(String value) {
        if (value == null) {
            return 1f;
        }
        int db;
        try {
            db = Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 1f;
        }
        db = Math.max(0, Math.min(MAX_DB, db));
        return (float) Math.pow(10, db / 20.0);
    }

    /** persist.miko3.ears.source: recognition, communication (the default), unprocessed or mic. */
    static final String SOURCE_PROP = "persist.miko3.ears.source";

    /** The source's log name for the property's value; anything else is the default. */
    static String sourceName(String value) {
        String v = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
        switch (v) {
            case "recognition":
                return "VOICE_RECOGNITION";
            case "unprocessed":
                return "UNPROCESSED";
            case "mic":
                return "MIC";
            default:
                return "VOICE_COMMUNICATION";
        }
    }

    /** The MediaRecorder.AudioSource value for a sourceName(). */
    static int source(String name) {
        switch (name) {
            case "VOICE_RECOGNITION":
                return android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION;
            case "UNPROCESSED":
                return android.media.MediaRecorder.AudioSource.UNPROCESSED;
            case "MIC":
                return android.media.MediaRecorder.AudioSource.MIC;
            default:
                return android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION;
        }
    }
}
