package com.miko3.mode.voice;

import com.miko3.shared.HttpRequest;
import com.miko3.shared.HttpResponse;
import com.miko3.shared.PageToken;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * The mode's settings page at "/" (KTD10): relay address, strict
 * turn-taking switch, current state, and an Exit button, in the launcher's
 * Pico form style. The launcher redirects the browser to this page's root
 * after a launch, so this is what a LAN browser sees.
 *
 * Follows the launcher page's status-redirect pattern: a POST (valid or
 * not) answers with a redirect to "/?status=<message>", and the GET that
 * follows re-renders the form from what is actually stored, with the
 * message in a status line. An invalid value is never stored.
 *
 * Both POSTs (settings, /exit) must carry the page token the rendering GET
 * issued (see PageToken). The token travels in the form body only, never
 * the URL: RoutingHttpServer logs every request line to logcat.
 *
 * Plain Java (no android.*): ModeApp's route handlers delegate here, and
 * scripts/tests drive the same entry points on the host JVM.
 */
final class SettingsPage {
    private SettingsPage() {
    }

    /** Shown in the state line until the voice engine (U8) reports a state. */
    static final String STATE_PLACEHOLDER = "Not running yet";

    // launcher/LauncherApp.PORT. No shared constant between the two apps' build
    // units (the remote-control mode hardcodes the launcher's 8443 the same way) —
    // keep in sync if that ever changes. The plain port, not HTTPS: the launcher's
    // own listener forwards to its HTTPS port whenever that one is up.
    static final int LAUNCHER_PORT = 8080;

    // Far above anything the form sends; refuses a body that would otherwise be
    // read into memory whole.
    static final int MAX_FORM_BYTES = 8192;

    /** "/": GET renders the form (minting a new page token), POST saves it. */
    static void handleRoot(HttpRequest req, HttpResponse res, PageToken token, VoiceSettings settings,
                           String stateText) throws IOException {
        if ("POST".equals(req.method)) {
            String status = submit(readForm(req), token, settings);
            res.redirect("/?status=" + urlEncode(status));
            return;
        }
        if (!"GET".equals(req.method) && !"HEAD".equals(req.method)) {
            res.sendText(405, "Method Not Allowed", "text/plain; charset=utf-8", "GET or POST only");
            return;
        }
        res.sendText(200, "OK", "text/html; charset=utf-8",
                buildHtml(token.issue(), settings, stateText, req.queryParam("status", null)));
    }

    /**
     * "/exit": returns true when the caller should exit the mode. Only a POST
     * carrying the issued page token passes; it is answered with a redirect
     * to the launcher's page on the same host. Anything else is refused and
     * sent back to this page with a status line.
     */
    static boolean handleExit(HttpRequest req, HttpResponse res, PageToken token) throws IOException {
        if (!"POST".equals(req.method)) {
            res.sendText(405, "Method Not Allowed", "text/plain; charset=utf-8", "POST only");
            return false;
        }
        Map<String, String> form = readForm(req);
        if (form == null || !token.check(form.get("t"))) {
            res.redirect("/?status=" + urlEncode("Exit refused: this page had expired. Press Exit again."));
            return false;
        }
        res.redirect(launcherUrl(req.headers.get("host")));
        return true;
    }

    /** Validates and stores one form submission; returns the status line to show. */
    static String submit(Map<String, String> form, PageToken token, VoiceSettings settings) {
        if (form == null) {
            return "Not saved: the form was too large.";
        }
        if (!token.check(form.get("t"))) {
            return "Not saved: this page had expired. Enter the settings again and save.";
        }
        String relay = form.get("relay");
        relay = relay == null ? "" : relay.trim();
        // An unchecked checkbox is simply absent from the form.
        boolean turnTaking = form.get("turn_taking") != null;
        if (relay.isEmpty()) {
            settings.save("", turnTaking);
            return "Saved. No relay address is set, so the robot can't hold a conversation until one is.";
        }
        RelayAddress address;
        try {
            address = RelayAddress.parse(relay);
        } catch (RelayAddress.InvalidException e) {
            return "Not saved: " + e.getMessage();
        }
        boolean changed = settings.save(address.toString(), turnTaking);
        return changed ? "Saved. Connecting to the relay at " + address + "." : "Saved.";
    }

    static String buildHtml(String token, VoiceSettings settings, String stateText, String status) {
        String t = escapeHtml(token);
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html><head><meta charset=\"utf-8\">");
        html.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">");
        html.append("<title>Miko3 Voice</title>");
        html.append("<link rel=\"stylesheet\" href=\"/assets/pico.min.css\">");
        html.append("</head><body><main class=\"container\">");
        html.append("<h1>Miko 3 &mdash; Voice Conversation</h1>");
        html.append("<p id=\"voice-state\">State: <strong>")
                .append(escapeHtml(stateText == null ? STATE_PLACEHOLDER : stateText))
                .append("</strong></p>");
        if (status != null) {
            html.append("<p id=\"settings-status\" role=\"status\">").append(escapeHtml(status)).append("</p>");
        }

        html.append("<section id=\"settings\"><h2>Settings</h2>");
        html.append("<form method=\"post\" action=\"/\">");
        html.append("<input type=\"hidden\" name=\"t\" value=\"").append(t).append("\">");
        html.append("<label for=\"relay\">Relay address");
        html.append("<input type=\"text\" id=\"relay\" name=\"relay\" value=\"")
                .append(escapeHtml(settings.relayAddress())).append("\" placeholder=\"")
                .append(RelayAddress.EXAMPLE)
                .append("\" autocomplete=\"off\" autocapitalize=\"off\" spellcheck=\"false\">");
        html.append("<small>host:port of the relay on this local network (a private or link-local "
                + "address).</small></label>");
        html.append("<label for=\"turn_taking\"><input type=\"checkbox\" role=\"switch\" id=\"turn_taking\" "
                + "name=\"turn_taking\"").append(settings.turnTaking() ? " checked" : "").append(">");
        html.append(" Strict turn-taking: the robot doesn't listen while it is speaking, so it can't be "
                + "interrupted. Applies from the next conversation.</label>");
        html.append("<button type=\"submit\">Save</button>");
        html.append("</form></section>");

        // A plain form rather than fetch(): the server answers a valid exit with a
        // redirect to the launcher's page, so the browser lands there without any
        // script, and a refused one comes back here with the reason.
        html.append("<form method=\"post\" action=\"/exit\">");
        html.append("<input type=\"hidden\" name=\"t\" value=\"").append(t).append("\">");
        html.append("<button type=\"submit\" class=\"secondary\">Exit voice mode</button>");
        html.append("</form>");

        html.append("</main></body></html>");
        return html.toString();
    }

    /** The launcher's page on the host the browser used to reach us. */
    static String launcherUrl(String hostHeader) {
        if (hostHeader == null || hostHeader.isEmpty()) {
            return "/";
        }
        String host = hostHeader;
        if (host.startsWith("[")) {
            int close = host.indexOf(']');
            host = close > 0 ? host.substring(0, close + 1) : host;
        } else {
            int colon = host.indexOf(':');
            if (colon >= 0) {
                host = host.substring(0, colon);
            }
        }
        return "http://" + host + ":" + LAUNCHER_PORT + "/";
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
