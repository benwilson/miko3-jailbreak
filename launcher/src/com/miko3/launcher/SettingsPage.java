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
 * has the Claude API section: a Save form (base URL, key, model) and
 * three token-only buttons (Refresh models, Test connection, Forget key)
 * that act on what is already saved. Then the Voice section: type a line,
 * press Say it, and the robot speaks it through the launcher's own speech
 * queue (the request returns once it is queued, not once it has played).
 * Then the People section (explore-on-claude plan U2, R15, R16): everyone
 * the robot remembers, with their face, name or "unnamed", and when he last
 * saw them, each with a Rename form and a Forget button. The same page serves
 * the robot's own WebView and a LAN browser.
 *
 * Follows voice mode's SettingsPage pattern: every action is a POST that
 * answers with a redirect to "/settings?status=<message>", and the GET that
 * follows re-renders from what is actually stored. Status messages are fixed
 * text, ClaudeApi's fixed reason text, or stored model ids, never anything
 * that was typed (KTD6), so a refused URL or key never lands in a URL, and
 * a person's name never does either.
 *
 * Faces: the page's <img> tags load SETTINGS_PEOPLE_FACE_PATH?id=<id>, the
 * one GET besides the page itself. It is under SETTINGS_PATH, so TLS-only,
 * and PeopleStore accepts only its own 16-hex-digit id shape before it
 * touches a file, so the id can't name any other file.
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

    static final String SAY_EMPTY = "Nothing said: type something to say.";
    static final String SAY_TOO_LONG = "Nothing said: that is longer than " + SpeechQueue.MAX_CHARS + " characters.";
    static final String SAY_LOADING = "The voice is still loading; try again in a few seconds.";
    static final String SAY_SPEAKING = "Speaking.";
    static final String SAY_UNAVAILABLE = "Nothing said: the robot's voice is not available.";

    static final String PEOPLE_RENAMED = "Name changed.";
    static final String PEOPLE_UNNAMED = "Name cleared; that person is now unnamed.";
    static final String PEOPLE_FORGOTTEN = "Forgotten: their face and name are deleted.";
    static final String PEOPLE_UNKNOWN = "Nothing changed: that person is not remembered.";
    static final String PEOPLE_NOT_SAVED = "Nothing changed: the change could not be saved.";

    /**
     * The robot's voice, as the Voice section sees it. LauncherApp backs it
     * with SpeechEngine's queue in-process; the host harness fakes it.
     */
    interface Speaker {
        /** True once the voice has loaded and warmed up. */
        boolean ready();

        /** True once the voice has failed to load: it will never be ready. */
        boolean failed();

        /** Which voice the build staged, e.g. "stock lessac medium" or "trained". */
        String voiceName();

        /** Queues text and returns at once, without waiting for playback.
         * Throws IllegalArgumentException (SpeechQueue's fixed reasons) when refused. */
        void say(String text);
    }

    /** Every settings route. GET the page; POST an action with the page token. */
    static void handle(HttpRequest req, HttpResponse res, PageToken token, ClaudeSettings settings,
                       ClaudeApi api, Speaker speaker, PeopleStore people) throws IOException {
        if (LauncherProtocol.SETTINGS_PATH.equals(req.path)) {
            if (!"GET".equals(req.method) && !"HEAD".equals(req.method)) {
                res.sendText(405, "Method Not Allowed", "text/plain; charset=utf-8", "GET only");
                return;
            }
            res.sendText(200, "OK", "text/html; charset=utf-8", buildHtml(token.issue(), settings.status(),
                    settings.models(), speaker.voiceName(), people.all(), req.queryParam("status", null)));
            return;
        }
        if (LauncherProtocol.SETTINGS_PEOPLE_FACE_PATH.equals(req.path)) {
            sendFace(req, res, people);
            return;
        }
        // The action paths never act on a GET, so nothing in a URL (which the
        // server logs) can change a setting.
        if (!"POST".equals(req.method)) {
            res.sendText(405, "Method Not Allowed", "text/plain; charset=utf-8", "POST only");
            return;
        }
        String status = act(req.path, readForm(req), token, settings, api, speaker, people);
        res.redirect(LauncherProtocol.SETTINGS_PATH + "?status=" + urlEncode(status));
    }

    /** Runs one action; returns the status line to show. */
    static String act(String path, Map<String, String> form, PageToken token, ClaudeSettings settings,
                      ClaudeApi api, Speaker speaker, PeopleStore people) {
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
        if (LauncherProtocol.SETTINGS_VOICE_SAY_PATH.equals(path)) {
            return say(form.get("text"), speaker);
        }
        if (LauncherProtocol.SETTINGS_PEOPLE_RENAME_PATH.equals(path)) {
            return rename(form.get("id"), form.get("name"), people);
        }
        if (LauncherProtocol.SETTINGS_PEOPLE_FORGET_PATH.equals(path)) {
            return forget(form.get("id"), people);
        }
        return "Nothing changed: unknown action.";
    }

    /** The face JPEG for ?id=, or 404 for anything that isn't a remembered
     * person's id. The id is checked by PeopleStore before any file access. */
    private static void sendFace(HttpRequest req, HttpResponse res, PeopleStore people) throws IOException {
        if (!"GET".equals(req.method) && !"HEAD".equals(req.method)) {
            res.sendText(405, "Method Not Allowed", "text/plain; charset=utf-8", "GET only");
            return;
        }
        byte[] face = people.face(req.queryParam("id", null));
        if (face == null) {
            res.sendText(404, "Not Found", "text/plain; charset=utf-8", "No such person.");
            return;
        }
        res.sendBytes(200, "OK", "image/jpeg", face);
    }

    /** Fixed text only: never the name that was typed or stored. */
    private static String rename(String id, String name, PeopleStore people) {
        if (people.nameOf(id) == null) {
            return PEOPLE_UNKNOWN;
        }
        if (!people.rename(id, name)) {
            return PEOPLE_NOT_SAVED;
        }
        return people.nameOf(id).isEmpty() ? PEOPLE_UNNAMED : PEOPLE_RENAMED;
    }

    private static String forget(String id, PeopleStore people) {
        return people.forget(id) ? PEOPLE_FORGOTTEN : PEOPLE_UNKNOWN;
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

    /** Queues the typed line. Every return is fixed text: never the line itself. */
    private static String say(String text, Speaker speaker) {
        if (text == null || text.trim().isEmpty()) {
            return SAY_EMPTY;
        }
        if (text.length() > SpeechQueue.MAX_CHARS) {
            return SAY_TOO_LONG;
        }
        if (speaker.failed()) {
            return SAY_UNAVAILABLE;
        }
        if (!speaker.ready()) {
            return SAY_LOADING;
        }
        try {
            speaker.say(text);
        } catch (IllegalArgumentException e) {
            // The queue shut down (the voice failed to load) or refused the line.
            return SpeechQueue.REFUSE_UNAVAILABLE.equals(e.getMessage())
                    ? SAY_UNAVAILABLE : SAY_EMPTY;
        }
        return SAY_SPEAKING;
    }

    static String buildHtml(String token, ClaudeSettings.Status st, List<String> models, String voiceName,
                            List<PeopleStore.Person> people, String status) {
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

        html.append("<section id=\"voice\"><h2>Voice</h2>");
        html.append("<p id=\"voice-name\">Voice: ").append(escapeHtml(voiceName)).append("</p>");
        html.append("<form method=\"post\" action=\"").append(LauncherProtocol.SETTINGS_VOICE_SAY_PATH)
                .append("\">");
        html.append("<input type=\"hidden\" name=\"t\" value=\"").append(t).append("\">");
        html.append("<label for=\"say-text\">Something to say");
        html.append("<input type=\"text\" id=\"say-text\" name=\"text\" maxlength=\"")
                .append(SpeechQueue.MAX_CHARS).append("\" autocomplete=\"off\">");
        html.append("<small>The robot says it out loud.</small></label>");
        html.append("<button type=\"submit\">Say it</button>");
        html.append("</form>");
        html.append("</section>");

        appendPeople(html, t, people);

        html.append("</main></body></html>");
        return html.toString();
    }

    /** R15, R16: everyone he remembers, most recently seen first. */
    private static void appendPeople(StringBuilder html, String t, List<PeopleStore.Person> people) {
        html.append("<section id=\"people\"><h2>People</h2>");
        html.append("<p>The people the robot remembers. Forget deletes their face and name for good.</p>");
        if (people.isEmpty()) {
            html.append("<p id=\"people-empty\">He hasn't met anyone yet.</p>");
        }
        for (PeopleStore.Person p : people) {
            // Ids are 16 hex digits (PeopleStore), so safe in a URL and an attribute.
            String id = escapeHtml(p.id);
            String name = escapeHtml(p.name);
            html.append("<article id=\"person-").append(id).append("\">");
            html.append("<img src=\"").append(LauncherProtocol.SETTINGS_PEOPLE_FACE_PATH).append("?id=").append(id)
                    .append("\" alt=\"").append(p.name.isEmpty() ? "unnamed person" : name)
                    .append("\" width=\"112\" height=\"112\">");
            html.append("<p><strong>").append(p.name.isEmpty() ? "<em>unnamed</em>" : name).append("</strong><br>");
            html.append("<small>Last seen ").append(escapeHtml(lastSeen(p.lastSeenMillis))).append("</small></p>");
            html.append("<form method=\"post\" action=\"").append(LauncherProtocol.SETTINGS_PEOPLE_RENAME_PATH)
                    .append("\">");
            html.append("<input type=\"hidden\" name=\"t\" value=\"").append(t).append("\">");
            html.append("<input type=\"hidden\" name=\"id\" value=\"").append(id).append("\">");
            html.append("<label>Name");
            html.append("<input type=\"text\" name=\"name\" value=\"").append(name).append("\" maxlength=\"")
                    .append(PeopleStore.MAX_NAME_CHARS).append("\" autocomplete=\"off\">");
            html.append("<small>Leave empty to make them unnamed.</small></label>");
            html.append("<button type=\"submit\" class=\"secondary\">Rename</button></form>");
            html.append("<form method=\"post\" action=\"").append(LauncherProtocol.SETTINGS_PEOPLE_FORGET_PATH)
                    .append("\">");
            html.append("<input type=\"hidden\" name=\"t\" value=\"").append(t).append("\">");
            html.append("<input type=\"hidden\" name=\"id\" value=\"").append(id).append("\">");
            html.append("<button type=\"submit\" class=\"contrast\">Forget</button></form>");
            html.append("</article>");
        }
        html.append("</section>");
    }

    /** "2026-09-24 15:45", the robot's local time. */
    static String lastSeen(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(millis));
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
