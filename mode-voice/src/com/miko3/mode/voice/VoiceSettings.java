package com.miko3.mode.voice;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The mode's settings (KTD10), stored in its own SharedPreferences file
 * (PREFS_NAME) behind the small Store interface so the page logic runs on
 * the host JVM in scripts/tests. ModeApp supplies the SharedPreferences-
 * backed Store.
 *
 * Two values are user-facing and edited on the settings page: the relay
 * address and the strict turn-taking switch. Two are tuning values with no
 * user-facing meaning, read here with defaults and written only over root
 * adb (U10), never shown on the form:
 *
 *   adb shell "cat > /data/data/com.miko3.mode.voice/shared_prefs/voice_settings.xml"
 *   ... <int name="prebuffer_chunks" value="2" /> ... then restart the app
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
    /** Milliseconds of mic audio per uplink chunk. */
    static final String KEY_UPLINK_CHUNK_MS = "uplink_chunk_ms";

    static final boolean DEFAULT_TURN_TAKING = false;
    static final int DEFAULT_PREBUFFER_CHUNKS = 1;
    static final int DEFAULT_UPLINK_CHUNK_MS = 80;

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

    /** Falls back to the default for a non-positive value written by hand. */
    int prebufferChunks() {
        int v = store.getInt(KEY_PREBUFFER_CHUNKS, DEFAULT_PREBUFFER_CHUNKS);
        return v > 0 ? v : DEFAULT_PREBUFFER_CHUNKS;
    }

    /** Falls back to the default for a non-positive value written by hand. */
    int uplinkChunkMs() {
        int v = store.getInt(KEY_UPLINK_CHUNK_MS, DEFAULT_UPLINK_CHUNK_MS);
        return v > 0 ? v : DEFAULT_UPLINK_CHUNK_MS;
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
