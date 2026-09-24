package com.miko3.shared;

/**
 * What the launcher's settings service tells a mode (settings plan U5, R13):
 * the Claude API base URL, key, and model, or "not set up yet" (R7, R16).
 * Plain Java so host tests can build and inspect it; RobotSettings carries it
 * across Binder.
 *
 * Holds the full key. toString() never shows it (R14), so logging an answer
 * by accident can't leak it.
 */
public final class ClaudeAccess {
    private static final ClaudeAccess NOT_SET_UP = new ClaudeAccess("", "", "");

    public final String baseUrl;
    public final String apiKey;
    public final String model;

    private ClaudeAccess(String baseUrl, String apiKey, String model) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
    }

    /** No key or no model yet, or the key was forgotten: every field is empty. */
    public static ClaudeAccess notSetUp() {
        return NOT_SET_UP;
    }

    /** The values to use. Falls back to notSetUp() when the key or model is
     * missing, so a mode never gets half an answer as if it were whole. */
    public static ClaudeAccess setUp(String baseUrl, String apiKey, String model) {
        if (isBlank(baseUrl) || isBlank(apiKey) || isBlank(model)) {
            return NOT_SET_UP;
        }
        return new ClaudeAccess(baseUrl, apiKey, model);
    }

    public boolean isSetUp() {
        return !apiKey.isEmpty();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isEmpty();
    }

    @Override
    public String toString() {
        return isSetUp() ? "ClaudeAccess{base=" + baseUrl + ", key=set, model=" + model + "}"
                : "ClaudeAccess{not set up}";
    }
}
