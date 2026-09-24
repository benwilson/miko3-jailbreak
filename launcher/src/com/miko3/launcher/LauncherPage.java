package com.miko3.launcher;

import android.content.Context;

import com.miko3.shared.LauncherProtocol;
import com.miko3.shared.ModeRegistry;

import java.util.List;

/**
 * Builds the launcher's single served page (R8/R11): device info plus the
 * Wi-Fi section, styled with Pico.css — the same UI approach modes use,
 * replacing the old separate dark-themed InfoHttpServer page and the
 * native-Views Wi-Fi screen.
 */
final class LauncherPage {
    private LauncherPage() {
    }

    /** runningModes: the registered modes whose presence route said active when
     * this page was requested (normally zero or one). */
    static String buildIndexHtml(Context ctx, WifiHttpHandler wifi, String connectStatusMessage,
                                  boolean pendingConnect, List<ModeRegistry.Mode> runningModes) {
        String model = DeviceInfo.escapeHtml(DeviceInfo.model());
        String serial = DeviceInfo.escapeHtml(DeviceInfo.serial());
        int battery = DeviceInfo.batteryPercent(ctx);
        String batteryStr = battery < 0 ? "unknown" : (battery + "%");
        String uptime = DeviceInfo.escapeHtml(DeviceInfo.uptime());
        String ip = DeviceInfo.wifiIp(ctx);
        String ipStr = ip.isEmpty() ? "not connected" : DeviceInfo.escapeHtml(ip);

        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html><head><meta charset=\"utf-8\">");
        html.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">");
        html.append("<title>Miko3 Launcher</title>");
        html.append("<link rel=\"stylesheet\" href=\"/assets/pico.min.css\">");
        html.append("</head><body><main class=\"container\">");
        html.append("<h1>Miko 3 &mdash; Custom Launcher</h1>");

        html.append("<section id=\"info\"><h2>Device</h2><table>");
        // First row: the robot has no other way to show which address to
        // open this page (or /settings) at from a laptop.
        html.append("<tr><td>IP address</td><td id=\"ip\">").append(ipStr).append("</td></tr>");
        html.append("<tr><td>Model</td><td>").append(model).append("</td></tr>");
        html.append("<tr><td>Serial</td><td>").append(serial).append("</td></tr>");
        html.append("<tr><td>Battery</td><td id=\"battery\">").append(batteryStr).append("</td></tr>");
        html.append("<tr><td>Uptime</td><td id=\"uptime\">").append(uptime).append("</td></tr>");
        html.append("</table></section>");

        // Battery/uptime/IP go stale without some refresh, but a full page reload
        // every few seconds (the original approach) re-fetches pico.min.css and
        // re-renders the whole Wi-Fi section (a WifiManager query) for two numbers
        // that change every second regardless. A lightweight poll-and-patch avoids
        // both — the Wi-Fi section itself still refreshes on its own real state-change
        // events (see MainActivity's wifiReceiver), not on this timer.
        html.append("<script>");
        html.append("setInterval(function(){fetch('/device-status').then(function(r){return r.text();})");
        html.append(".then(function(t){var parts=t.split('|');");
        html.append("document.getElementById('battery').textContent=parts[0];");
        html.append("document.getElementById('uptime').textContent=parts[1];");
        html.append("document.getElementById('ip').textContent=parts[2]||'not connected';");
        html.append("});},5000);");
        html.append("</script>");

        // One link per registered mode (KTD8). Launching any of them first exits
        // whichever one is running, so the running one's link relaunches it.
        html.append("<section id=\"modes\"><h2>Modes</h2>");
        html.append("<p id=\"running-mode\">Running: ");
        if (runningModes.isEmpty()) {
            html.append("none");
        } else {
            for (int i = 0; i < runningModes.size(); i++) {
                if (i > 0) html.append(", ");
                html.append(DeviceInfo.escapeHtml(runningModes.get(i).displayName));
            }
        }
        html.append("</p>");
        for (ModeRegistry.Mode mode : ModeRegistry.all()) {
            boolean running = runningModes.contains(mode);
            html.append("<a href=\"").append(LauncherProtocol.LAUNCH_MODE_PATH).append('?')
                    .append(LauncherProtocol.LAUNCH_MODE_PARAM).append('=').append(mode.id)
                    .append("\" role=\"button\"")
                    .append(running ? " aria-current=\"true\"" : "")
                    .append(" data-mode=\"").append(mode.id).append("\">")
                    .append(DeviceInfo.escapeHtml(mode.displayName))
                    .append(running ? " (running)" : "")
                    .append("</a> ");
        }
        // Robot-wide settings, next to the modes but not one of them (settings plan U4).
        html.append("<a href=\"").append(LauncherProtocol.SETTINGS_PATH)
                .append("\" role=\"button\" class=\"secondary\" id=\"settings-link\">Settings</a>");
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
