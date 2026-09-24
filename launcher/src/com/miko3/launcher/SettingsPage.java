package com.miko3.launcher;

import com.miko3.shared.ClaudeApi;
import com.miko3.shared.HttpRequest;
import com.miko3.shared.HttpResponse;
import com.miko3.shared.LauncherProtocol;
import com.miko3.shared.PageToken;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The robot's Settings page at LauncherProtocol.SETTINGS_PATH (settings plan
 * U4, R1-R10, R16): a Home link, then one section per group of settings. v1
 * has only the Claude API section: a Save form (base URL, key, model) and
 * three token-only buttons (Refresh models, Test connection, Forget key)
 * that act on what is already saved. The same page serves the robot's own
 * WebView and a LAN browser.
 *
 * Follows voice mode's SettingsPage pattern: every action is a POST that
 * answers with a redirect to "/settings?status=<message>", and the GET that
 * follows re-renders from what is actually stored. Status messages are fixed
 * text, ClaudeApi's fixed reason text, or stored model ids, never anything
 * that was typed (KTD6), so a refused URL or key never lands in a URL.
 *
 * Secrecy (R4, R14): the page renders only from ClaudeSettings.status(), and
 * the key input is always empty. The key arrives only in a POST body, and
 * the page token only in the body too, because RoutingHttpServer logs every
 * request line. The full key is read (credentialsForRequests()) only to hand
 * to the Claude client for Refresh and Test, which run synchronously on the
 * server's per-connection thread within the client's timeouts.
 *
 * Plain Java (no android.*): LauncherApp's routes delegate to handle(), and
 * scripts/tests drive the same entry point on the host JVM with a fake
 * ClaudeApi transport.
 */
final class SettingsPage {
    private SettingsPage() {
    }

    // Far above anything the form sends; refuses a body that would otherwise be
    // read into memory whole.
    static final int MAX_FORM_BYTES = 8192;

    /** Every settings route. GET the page; POST an action with the page token. */
    static void handle(HttpRequest req, HttpResponse res, PageToken token, ClaudeSettings settings,
                       ClaudeApi api) throws IOException {
        if (LauncherProtocol.SETTINGS_PATH.equals(req.path)) {
            if (!"GET".equals(req.method) && !"HEAD".equals(req.method)) {
                res.sendText(405, "Method Not Allowed", "text/plain; charset=utf-8", "GET only");
                return;
            }
            res.sendText(200, "OK", "text/html; charset=utf-8", buildHtml(token.issue(), settings.status(),
                    settings.models(), req.queryParam("status", null)));
            return;
        }
        // The action paths never act on a GET, so nothing in a URL (which the
        // server logs) can change a setting.
        if (!"POST".equals(req.method)) {
            res.sendText(405, "Method Not Allowed", "text/plain; charset=utf-8", "POST only");
            return;
        }
        String status = act(req.path, readForm(req), token, settings, api);
        res.redirect(LauncherProtocol.SETTINGS_PATH + "?status=" + urlEncode(status));
    }

    /** Runs one action; returns the status line to show. */
    static String act(String path, Map<String, String> form, PageToken token, ClaudeSettings settings,
                      ClaudeApi api) {
        if (form == null) {
            return "Nothing changed: the form was too large.";
        }
        if (!token.check(form.get("t"))) {
            return "Nothing changed: this page had expired. Try again.";
        }
        if (LauncherProtocol.SETTINGS_CLAUDE_PATH.equals(path)) {
            return save(form, settings);
        }
        if (LauncherProtocol.SETTINGS_CLAUDE_MODELS_PATH.equals(path)) {
            return refreshModels(settings, api);
        }
        if (LauncherProtocol.SETTINGS_CLAUDE_TEST_PATH.equals(path)) {
            return testConnection(settings, api);
        }
        if (LauncherProtocol.SETTINGS_CLAUDE_FORGET_PATH.equals(path)) {
            settings.forgetKey();
            return "Key forgotten. No key is set.";
        }
        return "Nothing changed: unknown action.";
    }

    private static String save(Map<String, String> form, ClaudeSettings settings) {
        try {
            settings.save(form.get("base_url"), form.get("key"), form.get("model"));
        } catch (ClaudeSettings.InvalidException e) {
            // Fixed text by contract (ClaudeSettings), never what was typed.
            return "Not saved: " + e.getMessage();
        }
        ClaudeSettings.Status st = settings.status();
        if (!st.keySet) {
            return "Saved. Enter an API key to use Claude.";
        }
        if (st.model.isEmpty()) {
            return "Saved. Press Refresh models, pick a model, and save again.";
        }
        return "Saved.";
    }

    private static String refreshModels(ClaudeSettings settings, ClaudeApi api) {
        ClaudeSettings.Credentials c = settings.credentialsForRequests();
        ClaudeApi.Result result = api.listModels(c.baseUrl, c.apiKey);
        if (!result.ok()) {
            // R9: the stored model and the last list stay as they were.
            return "Models not refreshed: " + result.describe();
        }
        int found = settings.saveModels(result.models);
        if (found == 0) {
            return "The endpoint listed no models; type a model name instead.";
        }
        return "Found " + found + (found == 1 ? " model." : " models.");
    }

    private static String testConnection(ClaudeSettings settings, ClaudeApi api) {
        ClaudeSettings.Credentials c = settings.credentialsForRequests();
        ClaudeApi.Result result = api.testConnection(c.baseUrl, c.apiKey, c.model);
        if (!result.ok()) {
            return "Test failed: " + result.describe();
        }
        // The stored model passed ClaudeSettings.checkModel(), so it is safe to show.
        return "Connection works: " + c.model + " answered.";
    }

    static String buildHtml(String token, ClaudeSettings.Status st, List<String> models, String status) {
        String t = escapeHtml(token);
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html><head><meta charset=\"utf-8\">");
        html.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">");
        html.append("<title>Miko3 Settings</title>");
        html.append("<link rel=\"stylesheet\" href=\"/assets/pico.min.css\">");
        html.append("</head><body><main class=\"container\">");
        html.append("<nav><ul><li><a href=\"/\">&larr; Home</a></li></ul></nav>");
        html.append("<h1>Settings</h1>");
        if (status != null) {
            html.append("<p id=\"settings-status\" role=\"status\">").append(escapeHtml(status)).append("</p>");
        }

        // One <section> per group of settings (R1); later ones go after this.
        html.append("<section id=\"claude\"><h2>Claude API</h2>");
        html.append("<form method=\"post\" action=\"").append(LauncherProtocol.SETTINGS_CLAUDE_PATH).append("\">");
        html.append("<input type=\"hidden\" name=\"t\" value=\"").append(t).append("\">");
        html.append("<label for=\"base_url\">Base URL");
        html.append("<input type=\"url\" id=\"base_url\" name=\"base_url\" value=\"")
                .append(escapeHtml(st.baseUrl))
                .append("\" autocomplete=\"off\" autocapitalize=\"off\" spellcheck=\"false\">");
        html.append("<small>Changing it needs the key entered again in the same save.</small></label>");
        // Never pre-filled (R4): empty keeps the stored key (R5).
        html.append("<label for=\"key\">API key");
        html.append("<input type=\"password\" id=\"key\" name=\"key\" autocomplete=\"off\" "
                + "autocapitalize=\"off\" spellcheck=\"false\">");
        html.append("<small>Leave empty to keep the saved key.</small></label>");
        html.append("<label for=\"model\">Model");
        html.append("<input type=\"text\" id=\"model\" name=\"model\" list=\"claude-models\" value=\"")
                .append(escapeHtml(st.model))
                .append("\" autocomplete=\"off\" autocapitalize=\"off\" spellcheck=\"false\">");
        html.append("<datalist id=\"claude-models\">");
        for (String id : models) {
            html.append("<option value=\"").append(escapeHtml(id)).append("\">");
        }
        html.append("</datalist>");
        if (!models.isEmpty() && !st.model.isEmpty() && !models.contains(st.model)) {
            // KTD5: kept, since the endpoint's list may be incomplete.
            html.append("<small id=\"model-unlisted\">").append(escapeHtml(st.model))
                    .append(" is not listed by endpoint.</small>");
        } else {
            html.append("<small>Pick from the list after Refresh models, or type a model name.</small>");
        }
        html.append("</label>");
        html.append("<button type=\"submit\">Save</button>");
        html.append("</form>");

        html.append("<p id=\"claude-key-status\">").append(escapeHtml(keyStatus(st))).append("</p>");

        // Token-only forms: they act on the saved settings, never on the fields above.
        html.append("<p>These use the saved settings \u2014 save changes first.</p>");
        html.append("<div class=\"grid\">");
        tokenForm(html, t, LauncherProtocol.SETTINGS_CLAUDE_MODELS_PATH, "Refresh models", "secondary");
        tokenForm(html, t, LauncherProtocol.SETTINGS_CLAUDE_TEST_PATH, "Test connection", "secondary");
        tokenForm(html, t, LauncherProtocol.SETTINGS_CLAUDE_FORGET_PATH, "Forget key", "contrast");
        html.append("</div>");
        html.append("</section>");

        html.append("</main></body></html>");
        return html.toString();
    }

    private static void tokenForm(StringBuilder html, String escapedToken, String action, String label,
                                  String cls) {
        html.append("<form method=\"post\" action=\"").append(action).append("\">");
        html.append("<input type=\"hidden\" name=\"t\" value=\"").append(escapedToken).append("\">");
        html.append("<button type=\"submit\" class=\"").append(cls).append("\">").append(label)
                .append("</button></form>");
    }

    /** "Key set, ends in …abcd, saved <time>" or "No key set" (R4). */
    static String keyStatus(ClaudeSettings.Status st) {
        if (!st.keySet) {
            return "No key set";
        }
        StringBuilder s = new StringBuilder("Key set");
        if (!st.keyLastFour.isEmpty()) {
            s.append(", ends in …").append(st.keyLastFour);
        }
        if (st.keySavedAtMillis > 0) {
            s.append(", saved ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                    .format(new Date(st.keySavedAtMillis)));
        }
        return s.toString();
    }

    /**
     * True when the URL is the launcher's home page ("/", any query). The
     * robot's WebView reloads on Wi-Fi broadcasts only then (KTD9), so a scan
     * never wipes a half-typed key here or re-issues the page token.
     */
    static boolean isHomePageUrl(String url) {
        if (url == null) {
            return false;
        }
        try {
            String path = new URI(url).getPath();
            return path == null || path.isEmpty() || "/".equals(path);
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /** The urlencoded form body, or null when it is larger than MAX_FORM_BYTES. */
    static Map<String, String> readForm(HttpRequest req) throws IOException {
        InputStream in = req.body;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
            if (out.size() > MAX_FORM_BYTES) {
                return null;
            }
        }
        return HttpRequest.parseQuery(new String(out.toByteArray(), StandardCharsets.UTF_8));
    }

    static String escapeHtml(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': out.append("&amp;"); break;
                case '<': out.append("&lt;"); break;
                case '>': out.append("&gt;"); break;
                case '"': out.append("&quot;"); break;
                case '\'': out.append("&#39;"); break;
                default: out.append(c);
            }
        }
        return out.toString();
    }

    private static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return "";
        }
    }
}
