package com.miko3.launcher;

import android.content.Context;

/**
 * Builds the launcher's single served page (R8/R11): device info plus the
 * Wi-Fi section, styled with Pico.css — the same UI approach modes use,
 * replacing the old separate dark-themed InfoHttpServer page and the
 * native-Views Wi-Fi screen.
 */
final class LauncherPage {
    private LauncherPage() {
    }

    static String buildIndexHtml(Context ctx, WifiHttpHandler wifi, String connectStatusMessage,
                                  boolean pendingConnect) {
        String model = DeviceInfo.escapeHtml(DeviceInfo.model());
        String serial = DeviceInfo.escapeHtml(DeviceInfo.serial());
        int battery = DeviceInfo.batteryPercent(ctx);
        String batteryStr = battery < 0 ? "unknown" : (battery + "%");
        String uptime = DeviceInfo.escapeHtml(DeviceInfo.uptime());

        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html><head><meta charset=\"utf-8\">");
        html.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">");
        html.append("<title>Miko3 Launcher</title>");
        html.append("<link rel=\"stylesheet\" href=\"/assets/pico.min.css\">");
        html.append("</head><body><main class=\"container\">");
        html.append("<h1>Miko 3 &mdash; Custom Launcher</h1>");

        html.append("<section id=\"info\"><h2>Device</h2><table>");
        html.append("<tr><td>Model</td><td>").append(model).append("</td></tr>");
        html.append("<tr><td>Serial</td><td>").append(serial).append("</td></tr>");
        html.append("<tr><td>Battery</td><td>").append(batteryStr).append("</td></tr>");
        html.append("<tr><td>Uptime</td><td>").append(uptime).append("</td></tr>");
        html.append("</table></section>");

        html.append("<section id=\"modes\"><h2>Modes</h2>");
        html.append("<a href=\"/launch-mode\" role=\"button\">Remote Control / Telepresence</a>");
        html.append("</section>");

        if (connectStatusMessage != null) {
            html.append("<p id=\"connect-status\" role=\"status\">")
                    .append(DeviceInfo.escapeHtml(connectStatusMessage)).append("</p>");
        }

        html.append(wifi.renderSection());

        if (pendingConnect) {
            // A connect attempt was just issued — WifiManager.reconnect() returns
            // immediately regardless of eventual success, so poll /wifi/status for a
            // bounded window and show a definite failure message rather than leaving
            // "Connecting..." on screen forever if the password was wrong or
            // association otherwise fails (required error-path behavior).
            html.append("<script>");
            html.append("(function(){var tries=0;var max=6;function poll(){");
            html.append("fetch('/wifi/status').then(function(r){return r.text();}).then(function(t){");
            html.append("if(t.indexOf('connected:')===0){");
            html.append("document.getElementById('connect-status').textContent='Connected: '+t.substring(10);");
            html.append("return;}");
            html.append("tries++;if(tries>=max){");
            html.append("document.getElementById('connect-status').textContent=");
            html.append("'Connection failed or timed out — check the password and try again.';");
            html.append("return;}");
            html.append("setTimeout(poll,1500);");
            html.append("});}");
            html.append("poll();})();");
            html.append("</script>");
        }

        html.append("</main></body></html>");
        return html.toString();
    }
}
