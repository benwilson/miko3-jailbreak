package com.miko3.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Locale;

/**
 * Gathers a snapshot of basic device/network info, shared by the on-screen
 * UI (MainActivity) and the web page (InfoHttpServer) so the two never drift.
 */
final class DeviceInfo {

    private DeviceInfo() {}

    static String model() {
        return Build.MODEL;
    }

    /** Build.getSerial() needs READ_PHONE_STATE on this API level; read the prop directly instead. */
    static String serial() {
        String s = getprop("ro.serialno");
        if (s.isEmpty()) s = getprop("ro.boot.serialno");
        return s.isEmpty() ? "unknown" : s;
    }

    static int batteryPercent(Context ctx) {
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent status = ctx.getApplicationContext().registerReceiver(null, filter);
        if (status == null) return -1;
        int level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        if (level < 0 || scale <= 0) return -1;
        return Math.round(100f * level / scale);
    }

    static String uptime() {
        long totalSeconds = SystemClock.elapsedRealtime() / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return String.format(Locale.US, "%dh %dm %ds", hours, minutes, seconds);
    }

    static String wifiSsid(Context ctx) {
        WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm == null) return "unavailable";
        WifiInfo info = wm.getConnectionInfo();
        if (info == null) return "not connected";
        String ssid = info.getSSID();
        if (ssid == null || ssid.isEmpty() || ssid.equals("<unknown ssid>")) return "not connected";
        return stripQuotes(ssid);
    }

    static String wifiIp(Context ctx) {
        WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm == null) return "";
        WifiInfo info = wm.getConnectionInfo();
        if (info == null) return "";
        int ip = info.getIpAddress();
        if (ip == 0) return "";
        return String.format(Locale.US, "%d.%d.%d.%d",
                (ip & 0xff), (ip >> 8 & 0xff), (ip >> 16 & 0xff), (ip >> 24 & 0xff));
    }

    static String stripQuotes(String s) {
        if (s != null && s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String getprop(String key) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"getprop", key});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = r.readLine();
            p.waitFor();
            return line == null ? "" : line.trim();
        } catch (Exception e) {
            return "";
        }
    }

    static String escapeHtml(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&#39;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    /** The one page InfoHttpServer serves — read-only, no user input reflected. */
    static String buildHtmlPage(Context ctx) {
        String model = escapeHtml(model());
        String serial = escapeHtml(serial());
        int battery = batteryPercent(ctx);
        String batteryStr = battery < 0 ? "unknown" : (battery + "%");
        String uptime = escapeHtml(uptime());
        String ssid = escapeHtml(wifiSsid(ctx));
        String ip = wifiIp(ctx);

        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html><head><meta charset=\"utf-8\">");
        html.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">");
        html.append("<meta http-equiv=\"refresh\" content=\"10\">");
        html.append("<title>Miko 3</title><style>");
        html.append("body{font-family:-apple-system,Segoe UI,Roboto,sans-serif;background:#111;color:#eee;margin:0;padding:24px}");
        html.append("h1{font-size:1.5em;margin:0 0 4px}");
        html.append(".sub{color:#888;margin-bottom:24px;font-size:.9em}");
        html.append("table{border-collapse:collapse;width:100%;max-width:480px}");
        html.append("td{padding:8px 4px;border-bottom:1px solid #333}");
        html.append("td:first-child{color:#8ab4f8;width:40%}");
        html.append("</style></head><body>");
        html.append("<h1>Miko 3</h1>");
        html.append("<div class=\"sub\">Custom launcher &mdash; proof of concept</div>");
        html.append("<table>");
        html.append(row("Model", model));
        html.append(row("Serial", serial));
        html.append(row("Battery", batteryStr));
        html.append(row("Uptime", uptime));
        html.append(row("Wi-Fi network", ssid));
        html.append(row("IP address", ip.isEmpty() ? "unknown" : escapeHtml(ip)));
        html.append("</table>");
        html.append("</body></html>");
        return html.toString();
    }

    private static String row(String key, String value) {
        return "<tr><td>" + key + "</td><td>" + value + "</td></tr>";
    }
}
