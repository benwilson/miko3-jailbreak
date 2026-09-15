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
        // Always-visible way back to the launcher, independent of whether its own
        // /exit request succeeds — a network hiccup or a mode that's already
        // mid-teardown shouldn't leave the operator stuck on a dead page with no way
        // out (navigation below always happens, success or failure). Doubles as this
        // page's own "Exit mode" control (U19p — the separate button DriveSection
        // used to render was removed as redundant with this link, but the /exit call
        // it made is NOT purely cosmetic: it releases the drive lease and tells the
        // robot's own on-device screen to return to the launcher (MainActivity's
        // exitMode()) — a remote browser just navigating itself away doesn't touch
        // any of that, so this link now makes the same call the old button did
        // before navigating, not just a plain href). LauncherApp.HTTPS_PORT is
        // hardcoded here (no shared constant between the two apps' build units) —
        // keep the literal 8443 in sync if that ever changes. Uses the current
        // page's own hostname rather than a baked-in one, since the robot's WiFi IP
        // isn't known at build time (same pattern RoutingHttpServer's redirect uses).
        html.append("<p><a id=\"home-link\" href=\"/\">&larr; Robot Home</a></p>");
        html.append("<script>"
                + "var homeLink=document.getElementById('home-link');"
                + "var launcherUrl='https://'+location.hostname+':8443/';"
                + "homeLink.href=launcherUrl;"
                + "homeLink.addEventListener('click',function(e){"
                + "e.preventDefault();"
                + "fetch('/exit?ct='+encodeURIComponent(CLIENT_TOKEN)).catch(function(){})"
                + ".then(function(){window.location.href=launcherUrl;});"
                + "});"
                + "</script>");
        html.append("<h1>Remote Control / Telepresence</h1>");
        html.append(CameraSection.HTML);
        // U19p: Drive and Audio/Video side by side (a 50/50 split, matching
        // CameraSection's own two-feed flex row) rather than stacked -- both are
        // short enough that stacking them left a lot of unused horizontal space on
        // anything wider than a phone.
        html.append("<div style=\"display:flex;gap:1rem;flex-wrap:wrap;align-items:flex-start\">");
        html.append("<div style=\"flex:1;min-width:280px\">").append(DriveSection.HTML).append("</div>");
        html.append("<div style=\"flex:1;min-width:280px\">").append(ToggleSection.HTML).append("</div>");
        html.append("</div>");
        html.append("</main></body></html>");
        return html.toString();
    }

    private static String jsonString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
