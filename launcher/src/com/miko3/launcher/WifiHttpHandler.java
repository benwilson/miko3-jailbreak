package com.miko3.launcher;

import android.content.Context;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Re-plumbs the Wi-Fi logic that used to live directly in MainActivity's
 * View callbacks (U9) behind plain method calls a RoutingHttpServer route
 * can call, and renders it as an HTML fragment following the tzapu
 * WiFiManager interaction pattern (KTD/Key Decision): saved and nearby
 * networks sorted by signal strength, a signal-bar + lock icon in place
 * of raw dBm numbers, and tapping a nearby network pre-fills the connect
 * form. The underlying WifiManager calls
 * (getConfiguredNetworks/getScanResults/addNetwork/enableNetwork/
 * reconnect/removeNetwork/saveConfiguration) are unchanged from the
 * original native-Views implementation — only what drives them changed.
 */
final class WifiHttpHandler {
    private final WifiManager wifiManager;

    WifiHttpHandler(Context ctx) {
        this.wifiManager = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
    }

    // ---- Actions (called by launcher HTTP routes) ----

    void connectSaved(int networkId) {
        wifiManager.enableNetwork(networkId, true);
        wifiManager.reconnect();
    }

    /** Returns false if adding the network failed (e.g. malformed config). */
    boolean connectManual(String ssid, String password) {
        WifiConfiguration config = new WifiConfiguration();
        config.SSID = "\"" + ssid + "\"";
        if (password == null || password.isEmpty()) {
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        } else {
            config.preSharedKey = "\"" + password + "\"";
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
        }
        int id = wifiManager.addNetwork(config);
        if (id == -1) {
            return false;
        }
        wifiManager.enableNetwork(id, true);
        wifiManager.reconnect();
        wifiManager.saveConfiguration();
        return true;
    }

    void forget(int networkId) {
        wifiManager.removeNetwork(networkId);
        wifiManager.saveConfiguration();
    }

    void disconnect() {
        wifiManager.disconnect();
    }

    boolean startScan() {
        return wifiManager.startScan();
    }

    /** For the post-connect status poll: "connected:<ssid>", "not-connected", or
     * "unknown" if Wi-Fi info isn't available at all. */
    String connectionStatus() {
        android.net.wifi.WifiInfo info = wifiManager.getConnectionInfo();
        if (info == null) {
            return "unknown";
        }
        String ssid = info.getSSID();
        if (ssid == null || ssid.isEmpty() || ssid.equals("<unknown ssid>")) {
            return "not-connected";
        }
        int ip = info.getIpAddress();
        return ip != 0 ? "connected:" + DeviceInfo.stripQuotes(ssid) : "not-connected";
    }

    // ---- Rendering ----

    /** The Wi-Fi section HTML fragment: saved networks, nearby networks (sorted by
     * signal strength with bars/lock icons), and the manual-connect form. */
    String renderSection() {
        StringBuilder html = new StringBuilder();
        html.append("<section id=\"wifi\">");
        html.append("<h2>Wi-Fi</h2>");
        html.append("<p>").append(DeviceInfo.escapeHtml(connectionStatus())).append("</p>");
        html.append("<form method=\"get\" action=\"/wifi/disconnect\" style=\"display:inline\">");
        html.append("<button type=\"submit\" class=\"secondary\">Disconnect</button></form>");
        html.append(" <a href=\"/wifi/rescan\" role=\"button\" class=\"secondary outline\">Rescan</a>");

        html.append("<h3>Saved networks</h3>");
        List<WifiConfiguration> saved = wifiManager.getConfiguredNetworks();
        if (saved == null || saved.isEmpty()) {
            html.append("<p><em>(none saved)</em></p>");
        } else {
            html.append("<ul>");
            for (WifiConfiguration c : saved) {
                String label = DeviceInfo.escapeHtml(DeviceInfo.stripQuotes(c.SSID));
                html.append("<li>").append(label);
                html.append(" <a href=\"/wifi/connect-saved?id=").append(c.networkId)
                        .append("\" role=\"button\">Connect</a>");
                html.append(" <a href=\"/wifi/forget?id=").append(c.networkId)
                        .append("\" role=\"button\" class=\"secondary outline\">Forget</a>");
                html.append("</li>");
            }
            html.append("</ul>");
        }

        html.append("<h3>Nearby networks</h3>");
        List<ScanResult> results;
        try {
            results = wifiManager.getScanResults();
        } catch (SecurityException e) {
            results = null;
        }
        if (results == null) {
            html.append("<p><em>(location permission needed to scan)</em></p>");
        } else {
            List<ScanResult> sorted = new ArrayList<ScanResult>();
            for (ScanResult r : results) {
                if (r.SSID != null && !r.SSID.isEmpty()) {
                    sorted.add(r);
                }
            }
            Collections.sort(sorted, new Comparator<ScanResult>() {
                @Override
                public int compare(ScanResult a, ScanResult b) {
                    return Integer.compare(b.level, a.level); // strongest first
                }
            });
            if (sorted.isEmpty()) {
                html.append("<p><em>(no networks found — tap Rescan)</em></p>");
            } else {
                html.append("<ul>");
                for (ScanResult r : sorted) {
                    boolean secured = r.capabilities != null
                            && (r.capabilities.contains("WPA") || r.capabilities.contains("WEP"));
                    int level = wifiManager.calculateSignalLevel(r.level, 5); // 0-4
                    String ssidEsc = DeviceInfo.escapeHtml(r.SSID);
                    String ssidJs = jsStringEscape(r.SSID);
                    html.append("<li>");
                    html.append(signalBars(level));
                    if (secured) {
                        html.append(" &#128274;"); // lock icon
                    }
                    html.append(" <a href=\"javascript:void(0)\" onclick=\"")
                            .append("document.getElementById('wifi-ssid').value=").append(jsQuote(ssidJs)).append(";")
                            .append("document.getElementById('wifi-password').focus();")
                            .append("\">").append(ssidEsc).append("</a>");
                    html.append("</li>");
                }
                html.append("</ul>");
            }
        }

        html.append("<h3>Connect manually</h3>");
        html.append("<form method=\"get\" action=\"/wifi/connect\">");
        html.append("<input id=\"wifi-ssid\" name=\"ssid\" placeholder=\"SSID\" required>");
        html.append("<input id=\"wifi-password\" name=\"password\" type=\"password\" "
                + "placeholder=\"Password (leave blank for an open network)\">");
        html.append("<button type=\"submit\">Connect</button>");
        html.append("</form>");
        html.append("</section>");
        return html.toString();
    }

    /** Four vertical bars, `level` (0-4) of them filled — the signal-bar convention
     * from iOS/Android, in place of a raw dBm number (Key Decision). */
    private String signalBars(int level) {
        StringBuilder sb = new StringBuilder("<span aria-label=\"signal level " + level + " of 4\" "
                + "style=\"display:inline-flex;align-items:flex-end;gap:1px;height:12px\">");
        int[] heights = {4, 6, 9, 12};
        for (int i = 0; i < 4; i++) {
            String color = i < level ? "#2e7d32" : "#ccc";
            sb.append("<span style=\"display:inline-block;width:3px;height:").append(heights[i])
                    .append("px;background:").append(color).append("\"></span>");
        }
        sb.append("</span>");
        return sb.toString();
    }

    private static String jsStringEscape(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"");
    }

    private static String jsQuote(String jsEscaped) {
        return "'" + jsEscaped + "'";
    }
}
