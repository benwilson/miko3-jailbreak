package com.miko3.shared;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * A parsed HTTP request: method, path, query params, headers, and a body
 * stream. Shared between the launcher (U9) and mode apps (U5+) — both
 * serve their own on-device+networked UI through RoutingHttpServer.
 */
public final class HttpRequest {
    public final String method;
    public final String path;
    public final Map<String, String> query;
    public final Map<String, String> headers;
    public final InputStream body;

    public HttpRequest(String method, String path, Map<String, String> query,
                Map<String, String> headers, InputStream body) {
        this.method = method;
        this.path = path;
        this.query = query;
        this.headers = headers;
        this.body = body;
    }

    public String queryParam(String name, String fallback) {
        String v = query.get(name);
        return v != null ? v : fallback;
    }

    public static Map<String, String> parseQuery(String raw) {
        Map<String, String> out = new HashMap<String, String>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = eq >= 0 ? pair.substring(0, eq) : pair;
            String value = eq >= 0 ? pair.substring(eq + 1) : "";
            out.put(urlDecode(key), urlDecode(value));
        }
        return out;
    }

    private static String urlDecode(String s) {
        try {
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }
}
