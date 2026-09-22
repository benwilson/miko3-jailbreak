package com.miko3.mode.voice;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The mode's settings (KTD10), stored in its own SharedPreferences file
 * (PREFS_NAME) behind the small Store interface so the page logic runs on
 * the host JVM in scripts/tests. ModeApp supplies the SharedPreferences-
 * backed Store.
 *
 * Two values are user-facing and edited on the settings page: the relay
 * address and the strict turn-taking switch. Five are tuning values with no
 * user-facing meaning (prebuffer_chunks, speaker_buffer_ms,
 * player_queue_chunks, uplink_chunk_ms, wake_trim_ms), read here with defaults
 * and written only over root adb (U10), never shown on the form:
 *
 *   adb shell "cat > /data/data/com.miko3.mode.voice/shared_prefs/voice_settings.xml"
 *   ... <int name="prebuffer_chunks" value="2" /> ... then restart the app
 *
 * capture_source is a diagnostics switch written the same way.
 *
 * The turn-taking value is read at each wake (KTD3), so a change applies at
 * the next conversation. A relay-address change applies at once: save()
 * notifies every RelayAddressListener (the voice engine closes its link,
 * resets its backoff, and reconnects to the new address).
 */
final class VoiceSettings {
    static final String PREFS_NAME = "voice_settings";

    /** host:port (RelayAddress form), or "" when unset. */
    static final String KEY_RELAY_ADDRESS = "relay_address";
    /** Strict turn-taking (KTD5); false = interruptible, the settled default. */
    static final String KEY_TURN_TAKING = "turn_taking";
    /** Reply chunks the robot buffers before starting its speaker (KTD6). */
    static final String KEY_PREBUFFER_CHUNKS = "prebuffer_chunks";
    /** Milliseconds of reply audio the AudioTrack itself holds once playing (SpeakerSizing). */
    static final String KEY_SPEAKER_BUFFER_MS = "speaker_buffer_ms";
    /** Reply chunks the player queues for the track before it drops the oldest. */
    static final String KEY_PLAYER_QUEUE_CHUNKS = "player_queue_chunks";
    /** Milliseconds of mic audio per uplink chunk. */
    static final String KEY_UPLINK_CHUNK_MS = "uplink_chunk_ms";
    /** Milliseconds of mic audio dropped after a wake, so the model never hears "Hey Miko". */
    static final String KEY_WAKE_TRIM_MS = "wake_trim_ms";
    /** Diagnostics only: "recognition" captures from VOICE_RECOGNITION (VoiceEngine). */
    static final String KEY_CAPTURE_SOURCE = "capture_source";

    static final boolean DEFAULT_TURN_TAKING = false;
    /** Six 80 ms chunks (480 ms): playback begins with a real cushion (U12). */
    static final int DEFAULT_PREBUFFER_CHUNKS = 6;
    /** Beyond two seconds the prebuffer costs more first-word latency than any
     * cushion is worth, so a hand-typed value above it is clamped. */
    static final int MAX_PREBUFFER_CHUNKS = 25;
    /** About a second of audio in the track, enough to ride out scheduling
     * jitter from the wake-word spotter and the settings WebView (U12). */
    static final int DEFAULT_SPEAKER_BUFFER_MS = 1000;
    /** Two chunks: below this the track is back to refilling inside one chunk. */
    static final int MIN_SPEAKER_BUFFER_MS = 160;
    /** Four seconds of speaker buffer is already more than any reply needs. */
    static final int MAX_SPEAKER_BUFFER_MS = 4000;
    /** 3.04 s: the relay's 2 s burst plus room for the track's own second. */
    static final int DEFAULT_PLAYER_QUEUE_CHUNKS = 38;
    /** 1.04 s: the queue that shipped before U12, and already too small for the
     * relay's burst -- no hand-written value may go tighter. */
    static final int MIN_PLAYER_QUEUE_CHUNKS = 13;
    /** 10 s of queued reply; past this a stuck player would just hoard memory. */
    static final int MAX_PLAYER_QUEUE_CHUNKS = 125;
    static final int DEFAULT_UPLINK_CHUNK_MS = 80;
    /** Two and a half 80 ms chunks: the detector's slack plus the tail of "Miko" (U11). */
    static final int DEFAULT_WAKE_TRIM_MS = 200;
    /** A hand-typed trim beyond this would swallow the question, so it is treated as a typo. */
    static final int MAX_WAKE_TRIM_MS = 2000;

    /** Minimal key-value surface over SharedPreferences. Writes must be durable
     * when they return (ModeApp's implementation uses commit(), not apply()). */
    interface Store {
        String getString(String key, String def);

        boolean getBoolean(String key, boolean def);

        int getInt(String key, int def);

        void putString(String key, String value);

        void putBoolean(String key, boolean value);
    }

    /** Called after a saved relay address differs from the previous one.
     * Runs on the HTTP server thread that handled the POST: don't block it. */
    interface RelayAddressListener {
        /** newAddress is host:port, or "" when the address was cleared. */
        void onRelayAddressChanged(String newAddress);
    }

    private final Store store;
    private final CopyOnWriteArrayList<RelayAddressListener> listeners =
            new CopyOnWriteArrayList<RelayAddressListener>();

    VoiceSettings(Store store) {
        this.store = store;
    }

    void addRelayAddressListener(RelayAddressListener l) {
        listeners.addIfAbsent(l);
    }

    void removeRelayAddressListener(RelayAddressListener l) {
        listeners.remove(l);
    }

    String relayAddress() {
        String v = store.getString(KEY_RELAY_ADDRESS, "");
        return v == null ? "" : v;
    }

    boolean turnTaking() {
        return store.getBoolean(KEY_TURN_TAKING, DEFAULT_TURN_TAKING);
    }

    /**
     * Chunks queued before the speaker starts. A hand-written value outside the
     * sane range is clamped to the nearest bound rather than ignored; absent, or
     * non-positive (a cleared key), falls back to the default. The three speaker
     * sizes all read this way, so a typo costs the tuning pass a bound, not a
     * silent revert to the default it was trying to move away from.
     */
    int prebufferChunks() {
        int v = store.getInt(KEY_PREBUFFER_CHUNKS, DEFAULT_PREBUFFER_CHUNKS);
        return v > 0 ? Math.min(v, MAX_PREBUFFER_CHUNKS) : DEFAULT_PREBUFFER_CHUNKS;
    }

    /** Milliseconds the AudioTrack buffers once playing; see prebufferChunks(). */
    int speakerBufferMs() {
        int v = store.getInt(KEY_SPEAKER_BUFFER_MS, DEFAULT_SPEAKER_BUFFER_MS);
        return v > 0 ? clamp(v, MIN_SPEAKER_BUFFER_MS, MAX_SPEAKER_BUFFER_MS) : DEFAULT_SPEAKER_BUFFER_MS;
    }

    /** Chunks the player queue holds before dropping the oldest; see prebufferChunks(). */
    int playerQueueChunks() {
        int v = store.getInt(KEY_PLAYER_QUEUE_CHUNKS, DEFAULT_PLAYER_QUEUE_CHUNKS);
        return v > 0 ? clamp(v, MIN_PLAYER_QUEUE_CHUNKS, MAX_PLAYER_QUEUE_CHUNKS) : DEFAULT_PLAYER_QUEUE_CHUNKS;
    }

    private static int clamp(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }

    /** Falls back to the default for a non-positive value written by hand. */
    int uplinkChunkMs() {
        int v = store.getInt(KEY_UPLINK_CHUNK_MS, DEFAULT_UPLINK_CHUNK_MS);
        return v > 0 ? v : DEFAULT_UPLINK_CHUNK_MS;
    }

    /**
     * Milliseconds of microphone audio thrown away after the wake-word callback
     * before anything is forwarded (UplinkGate). Zero is meaningful -- it forwards
     * everything from the detection onward -- so only a negative or implausibly
     * large hand-written value falls back to the default.
     */
    int wakeTrimMs() {
        int v = store.getInt(KEY_WAKE_TRIM_MS, DEFAULT_WAKE_TRIM_MS);
        return v >= 0 && v <= MAX_WAKE_TRIM_MS ? v : DEFAULT_WAKE_TRIM_MS;
    }

    /** True when capture_source is hand-written as "recognition"; false for any
     * other value, and for a non-string value. */
    boolean captureFromRecognition() {
        try {
            return "recognition".equals(store.getString(KEY_CAPTURE_SOURCE, ""));
        } catch (ClassCastException e) {
            return false;
        }
    }

    /**
     * Stores both form values. relayAddress must already be validated (a
     * RelayAddress.toString(), or "" to clear). Returns true when the address
     * changed, after the listeners have run.
     */
    boolean save(String relayAddress, boolean turnTaking) {
        String previous;
        synchronized (this) {
            previous = relayAddress();
            store.putBoolean(KEY_TURN_TAKING, turnTaking);
            store.putString(KEY_RELAY_ADDRESS, relayAddress);
        }
        if (previous.equals(relayAddress)) {
            return false;
        }
        for (RelayAddressListener l : listeners) {
            // One broken listener must neither skip the others nor escape into the
            // HTTP server's connection thread, where an uncaught exception would
            // crash the app. System.err lands in logcat as W/System.err.
            try {
                l.onRelayAddressChanged(relayAddress);
            } catch (RuntimeException e) {
                System.err.println("VoiceSettings: relay-address listener " + l + " failed");
                e.printStackTrace();
            }
        }
        return true;
    }
}
