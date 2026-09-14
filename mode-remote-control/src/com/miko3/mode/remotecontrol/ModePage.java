package com.miko3.mode.remotecontrol;

/**
 * Builds the mode's single served HTML page (R11: one UI, on-device
 * WebView and networked browser both render the same markup). Sections
 * are appended here unit by unit (U6 camera view, U7 drive controls, U8
 * audio/video toggles) rather than templated up front, since each one's
 * shape wasn't settled until its own unit.
 */
final class ModePage {
    private ModePage() {
    }

    static String buildIndexHtml(String clientToken) {
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html><head><meta charset=\"utf-8\">");
        html.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">");
        html.append("<title>Remote Control / Telepresence</title>");
        html.append("<link rel=\"stylesheet\" href=\"/assets/pico.min.css\">");
        // Embeds the server-assigned client token (R17): loading this page is "a
        // newer connection" that already claimed control server-side (see ModeApp's
        // "/" handler) — every subsequent fetch() carries this token so the server
        // can tell this page's requests apart from a stale/older one's.
        html.append("<script>const CLIENT_TOKEN=").append(jsonString(clientToken)).append(";</script>");
        html.append("</head><body><main class=\"container\">");
        // Always-visible way back to the launcher, independent of whether the
        // "Exit mode" button's own /exit request succeeds — a network hiccup or a
        // mode that's already mid-teardown shouldn't leave the operator stuck on a
        // dead page with no way out. LauncherApp.HTTPS_PORT is hardcoded here (no
        // shared constant between the two apps' build units) — keep the literal
        // 8443 in sync if that ever changes. Uses the current page's own hostname
        // rather than a baked-in one, since the robot's WiFi IP isn't known at
        // build time (same pattern RoutingHttpServer's redirect uses).
        html.append("<p><a id=\"home-link\" href=\"/\">&larr; Robot Home</a></p>");
        html.append("<script>document.getElementById('home-link').href="
                + "'https://'+location.hostname+':8443/';</script>");
        html.append("<h1>Remote Control / Telepresence</h1>");
        html.append(CameraSection.HTML);
        html.append(DriveSection.HTML);
        html.append(ToggleSection.HTML);
        html.append("</main></body></html>");
        return html.toString();
    }

    private static String jsonString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
