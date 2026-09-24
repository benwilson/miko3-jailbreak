package com.miko3.launcher;

import com.miko3.shared.ClaudeApi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The robot's Claude API access (settings plan U2, KTD2): base URL, API key
 * and model, plus the model ids the last Refresh found. Stored in the
 * launcher's own SharedPreferences file (PREFS_NAME) behind the small Store
 * interface, unencrypted (root adb can read anything on this device anyway),
 * so the save rules run on the host JVM in scripts/tests. LauncherApp
 * supplies the SharedPreferences-backed Store.
 *
 * The save rules are the key's security boundary (R6): anyone on the LAN can
 * post the settings form, so a save that changes the base URL must carry a
 * new key, or someone could send the stored key to their own server. URLs
 * compare in ClaudeApi.normalizeBaseUrl()'s form, so "https://host/v1/" is
 * no change from "https://host". A blank key field keeps the stored key (R5).
 *
 * Secrecy (R4, R14): status() is what pages render from, and it carries only
 * whether a key is set, its last four characters and when it was saved.
 * credentialsForRequests() is the one accessor with the full key, for the
 * settings service and the Claude client. InvalidException messages are
 * fixed text and never repeat what was typed.
 */
final class ClaudeSettings {
    static final String PREFS_NAME = "claude_settings";

    /** Normalized https:// base URL (ClaudeApi form); absent means DEFAULT_BASE_URL. */
    static final String KEY_BASE_URL = "base_url";
    /** The API key, or "" when none is stored. */
    static final String KEY_API_KEY = "api_key";
    /** Wall-clock milliseconds when the key was saved, as a decimal string; "" with no key. */
    static final String KEY_KEY_SAVED_AT = "api_key_saved_at";
    /** Model id, or "" until the owner picks one (R7). */
    static final String KEY_MODEL = "model";
    /** Model ids from the last Refresh, one per line (ids can't contain a newline). */
    static final String KEY_MODELS = "models";

    static final String DEFAULT_BASE_URL = "https://teamclaude.rentaladvantage.rent";

    /** Model ids as Anthropic, Bedrock ("...-v1:0"), Vertex ("...@date") and
     * proxies ("vendor/model") spell them. Conservative on purpose: the id goes
     * into a JSON body, an HTML attribute and a status redirect. */
    private static final int MAX_MODEL_LENGTH = 128;
    private static final Pattern MODEL_ID = Pattern.compile("[A-Za-z0-9._:@/-]{1," + MAX_MODEL_LENGTH + "}");
    /** Below this, four characters are too much of the key to show (R4). */
    private static final int MIN_KEY_LENGTH_FOR_SUFFIX = 12;

    /** Minimal key-value surface over SharedPreferences. putStrings writes every
     * entry in one commit that is durable when it returns (LauncherApp's
     * implementation uses commit(), not apply()), so a save never leaves a new
     * base URL next to the old key. */
    interface Store {
        String getString(String key, String def);

        void putStrings(Map<String, String> entries);
    }

    /** Wall-clock time, injected so tests can pin the saved-at time. */
    interface Clock {
        long nowMillis();
    }

    /** A rejected save. The message is fixed text, shown to the owner verbatim. */
    static final class InvalidException extends Exception {
        InvalidException(String message) {
            super(message);
        }
    }

    /** What a page may show. Never holds the full key. */
    static final class Status {
        final boolean keySet;
        /** The key's last four characters, or "" with no key or a very short one. */
        final String keyLastFour;
        /** When the key was saved (wall-clock ms), or 0 when unknown or unset. */
        final long keySavedAtMillis;
        final String baseUrl;
        /** "" until the owner picks one. */
        final String model;

        Status(boolean keySet, String keyLastFour, long keySavedAtMillis, String baseUrl, String model) {
            this.keySet = keySet;
            this.keyLastFour = keyLastFour;
            this.keySavedAtMillis = keySavedAtMillis;
            this.baseUrl = baseUrl;
            this.model = model;
        }

        @Override
        public String toString() {
            return "Status{keySet=" + keySet + ", base=" + baseUrl + ", model=" + model + "}";
        }
    }

    /** The values a Claude request needs, read together so a concurrent save
     * can't pair one base URL with another's key. Holds the full key: only
     * for the settings service and the client, never for rendering. */
    static final class Credentials {
        final String baseUrl;
        final String apiKey;
        final String model;

        Credentials(String baseUrl, String apiKey, String model) {
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
            this.model = model;
        }

        /** False means "not set up yet" (R7, R16): no key, or no model picked. */
        boolean isSetUp() {
            return !apiKey.isEmpty() && !model.isEmpty();
        }

        @Override
        public String toString() {
            return "Credentials{base=" + baseUrl + ", key=" + (apiKey.isEmpty() ? "unset" : "set")
                    + ", model=" + model + "}";
        }
    }

    private final Store store;
    private final Clock clock;

    ClaudeSettings(Store store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /**
     * Applies the save rules to one form submission, in order: the URL must be
     * https:// (R7), a blank key keeps the stored one (R5), and a changed URL
     * needs a new key (R6). An empty model is allowed (R7). Nothing is stored
     * unless every rule passes; a changed URL also drops the model list, which
     * belonged to the old endpoint.
     */
    synchronized void save(String baseUrl, String keyOrBlank, String model) throws InvalidException {
        String url = ClaudeApi.normalizeBaseUrl(trim(baseUrl));
        if (url == null) {
            throw new InvalidException("The base URL must be an https:// address with a host name, "
                    + "for example " + DEFAULT_BASE_URL + ".");
        }
        String key = trim(keyOrBlank);
        if (!key.isEmpty()) {
            checkKey(key);
        }
        String modelId = checkModel(model);
        boolean urlChanged = !url.equals(ClaudeApi.normalizeBaseUrl(storedBaseUrl()));
        if (urlChanged && key.isEmpty()) {
            throw new InvalidException("Changing the base URL needs the API key entered again in the same save.");
        }

        Map<String, String> entries = new HashMap<String, String>();
        entries.put(KEY_BASE_URL, url);
        entries.put(KEY_MODEL, modelId);
        if (!key.isEmpty()) {
            entries.put(KEY_API_KEY, key);
            entries.put(KEY_KEY_SAVED_AT, Long.toString(clock.nowMillis()));
        }
        if (urlChanged) {
            entries.put(KEY_MODELS, "");
        }
        store.putStrings(entries);
    }

    /** Removes the stored key (R16); the base URL and model stay. */
    synchronized void forgetKey() {
        Map<String, String> entries = new HashMap<String, String>();
        entries.put(KEY_API_KEY, "");
        entries.put(KEY_KEY_SAVED_AT, "");
        store.putStrings(entries);
    }

    synchronized Status status() {
        String key = storedKey();
        String suffix = key.length() >= MIN_KEY_LENGTH_FOR_SUFFIX ? key.substring(key.length() - 4) : "";
        long savedAt = 0;
        if (!key.isEmpty()) {
            try {
                savedAt = Long.parseLong(store.getString(KEY_KEY_SAVED_AT, ""));
            } catch (NumberFormatException e) {
                savedAt = 0;
            }
        }
        return new Status(!key.isEmpty(), suffix, savedAt, storedBaseUrl(), storedModel());
    }

    /** The full key with its base URL and model. Only the settings service and
     * Claude client callers use this; pages render from status(). */
    synchronized Credentials credentialsForRequests() {
        return new Credentials(storedBaseUrl(), storedKey(), storedModel());
    }

    /** Model ids from the last Refresh (KTD5), in the endpoint's order. */
    synchronized List<String> models() {
        String joined = store.getString(KEY_MODELS, "");
        List<String> ids = new ArrayList<String>();
        if (joined == null) {
            return ids;
        }
        for (String id : joined.split("\n")) {
            if (isModelId(id)) {
                ids.add(id);
            }
        }
        return ids;
    }

    /** Stores a Refresh result, dropping duplicates and ids this class wouldn't
     * accept as a model, so the list is safe to render and to save back.
     * Returns how many ids were kept. */
    synchronized int saveModels(List<String> ids) {
        LinkedHashSet<String> kept = new LinkedHashSet<String>();
        for (String id : ids == null ? Collections.<String>emptyList() : ids) {
            if (isModelId(id)) {
                kept.add(id);
            }
        }
        StringBuilder joined = new StringBuilder();
        for (String id : kept) {
            if (joined.length() > 0) {
                joined.append('\n');
            }
            joined.append(id);
        }
        store.putStrings(Collections.singletonMap(KEY_MODELS, joined.toString()));
        return kept.size();
    }

    /** The trimmed model id, or "" for none; throws with a readable reason otherwise. */
    static String checkModel(String raw) throws InvalidException {
        String id = trim(raw);
        if (id.isEmpty()) {
            return id;
        }
        if (id.length() > MAX_MODEL_LENGTH) {
            throw new InvalidException("The model name is too long; model names are at most "
                    + MAX_MODEL_LENGTH + " characters.");
        }
        if (!isModelId(id)) {
            throw new InvalidException("The model name can only use letters, digits and . _ : @ / -, "
                    + "for example claude-sonnet-4-5.");
        }
        return id;
    }

    private static boolean isModelId(String id) {
        return id != null && MODEL_ID.matcher(id).matches();
    }

    /** Header values are printable ASCII with no spaces (the client checks the
     * same before sending); catching it here tells the owner at save time. */
    private static void checkKey(String key) throws InvalidException {
        if (!ClaudeApi.isValidKeyFormat(key)) {
            throw new InvalidException("The API key contains spaces or characters a key can't have; "
                    + "paste it again.");
        }
    }

    private String storedBaseUrl() {
        String v = store.getString(KEY_BASE_URL, "");
        return v == null || v.isEmpty() ? DEFAULT_BASE_URL : v;
    }

    private String storedKey() {
        String v = store.getString(KEY_API_KEY, "");
        return v == null ? "" : v;
    }

    private String storedModel() {
        String v = store.getString(KEY_MODEL, "");
        return v == null ? "" : v;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
