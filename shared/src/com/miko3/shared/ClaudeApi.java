package com.miko3.shared;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.SSLException;

/**
 * The robot's Claude API client (settings plan U3, KTD3-KTD5): lists the
 * models an endpoint serves and runs a one-token connection test. Every
 * failure comes back as one Reason from a fixed set, so the settings page,
 * the push script and the modes all say the same plain thing.
 *
 * The base URL is an Anthropic-style endpoint (the default is a proxy);
 * normalizeBaseUrl() drops a trailing "/" or "/v1" and each call appends its
 * own "/v1/..." path. Each request carries the key in both x-api-key and
 * Authorization: Bearer, because which one a proxy reads is unknown.
 *
 * Secrecy (R14): the key goes only into request headers, never into a URL,
 * a log line or a Reason. Reason text is fixed; nothing from a response body
 * is ever copied into it, since an endpoint may echo the key back.
 *
 * Plain Java (no android.*) so scripts/tests can exercise it on the host JVM
 * with a fake Transport; ClaudeHttpsTransport is the real one.
 */
public final class ClaudeApi {
    public static final String VERSION = "2023-06-01";
    /** The page size GET /v1/models allows at most. */
    private static final int PAGE_LIMIT = 1000;
    /** A proxy that keeps answering has_more would otherwise page forever. */
    private static final int MAX_PAGES = 20;

    /** Why a call failed. The text is fixed and safe to show anywhere. */
    public enum Reason {
        NOT_SET_UP("The API key or model is not set up yet."),
        BAD_BASE_URL("The base URL must be an https:// address with a host name."),
        BAD_KEY_FORMAT("The saved API key contains characters a key can't have; enter it again."),
        BAD_MODEL_NAME("The model name contains characters a model name can't have."),
        BAD_KEY("The endpoint rejected the API key."),
        NO_PERMISSION("The API key doesn't have permission for this."),
        BILLING("The account is out of credit or has hit its spend limit."),
        INVALID_REQUEST("The endpoint rejected the request as invalid."),
        UNKNOWN_MODEL("The endpoint doesn't know this model."),
        MODELS_NOT_LISTED("The endpoint doesn't list its models; type a model name instead."),
        RATE_LIMITED("The endpoint is rate limiting this key; try again shortly."),
        OVERLOADED("The endpoint is overloaded; try again shortly."),
        ENDPOINT_ERROR("The endpoint is overloaded or erroring."),
        UNREACHABLE("The endpoint can't be reached (DNS, connection or timeout)."),
        TLS_FAILED("TLS failed; check the robot's clock and the endpoint's certificate.");

        public final String text;

        Reason(String text) {
            this.text = text;
        }
    }

    /** Either success (reason null, models filled for a listing) or one Reason. */
    public static final class Result {
        public final Reason reason;
        /** The HTTP status behind a failure, or 0 when there was no response. */
        public final int httpStatus;
        /** Model ids in the endpoint's order; empty unless a listing succeeded. */
        public final List<String> models;

        private Result(Reason reason, int httpStatus, List<String> models) {
            this.reason = reason;
            this.httpStatus = httpStatus;
            this.models = Collections.unmodifiableList(models);
        }

        static Result success(List<String> models) {
            return new Result(null, 0, models);
        }

        static Result failure(Reason reason, int httpStatus) {
            return new Result(reason, httpStatus, new ArrayList<String>());
        }

        public boolean ok() {
            return reason == null;
        }

        /** One line for a page or a terminal: the reason plus the status, if any. */
        public String describe() {
            if (ok()) {
                return "OK";
            }
            return httpStatus > 0 ? reason.text + " (HTTP " + httpStatus + ")" : reason.text;
        }

        @Override
        public String toString() {
            return describe();
        }
    }

    /** One HTTP exchange. Host tests fake it; ClaudeHttpsTransport does it for real. */
    public interface Transport {
        /** Must not follow redirects: a 3xx comes back as a Response. Throws on no response at all. */
        Response send(Request request) throws IOException;
    }

    public static final class Request {
        public final String method;
        public final String url;
        /** Header name to value, in the order they are sent. Holds the key. */
        public final Map<String, String> headers;
        /** The JSON body, or null for a GET. */
        public final String body;

        Request(String method, String url, Map<String, String> headers, String body) {
            this.method = method;
            this.url = url;
            this.headers = Collections.unmodifiableMap(headers);
            this.body = body;
        }
    }

    public static final class Response {
        public final int status;
        /** The body as text, from the error stream for a failure; never null. */
        public final String body;

        public Response(int status, String body) {
            this.status = status;
            this.body = body == null ? "" : body;
        }
    }

    private final Transport transport;

    public ClaudeApi(Transport transport) {
        this.transport = transport;
    }

    /**
     * The canonical form of a base URL, or null if it isn't usable: it must be
     * https:// with a host (no userinfo, any port 1-65535), and have no
     * whitespace, query or fragment. A trailing "/" or "/v1" (or "/v1/") is
     * dropped, so all of those spellings compare equal (R6). Callers trim form input before calling.
     */
    public static String normalizeBaseUrl(String raw) {
        if (raw == null || !raw.regionMatches(true, 0, "https://", 0, 8)) {
            return null;
        }
        for (int k = 0; k < raw.length(); k++) {
            char c = raw.charAt(k);
            if (Character.isWhitespace(c) || Character.isISOControl(c) || c == '?' || c == '#') {
                return null;
            }
        }
        String rest = stripTrailingSlashes(raw.substring(8));
        if (rest.endsWith("/v1")) {
            rest = stripTrailingSlashes(rest.substring(0, rest.length() - 3));
        }
        int slash = rest.indexOf('/');
        String authority = slash < 0 ? rest : rest.substring(0, slash);
        int colon = authority.lastIndexOf(':');
        String host = colon < 0 ? authority : authority.substring(0, colon);
        if (host.isEmpty() || host.indexOf('@') >= 0) {
            return null;
        }
        if (colon >= 0) {
            String port = authority.substring(colon + 1);
            if (!port.matches("[0-9]{1,5}") || Integer.parseInt(port) < 1 || Integer.parseInt(port) > 65535) {
                return null;
            }
        }
        return "https://" + rest;
    }

    private static String stripTrailingSlashes(String s) {
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /** True when the key is printable ASCII with no spaces, as a header value
     * must be; a CR/LF would also split the header. */
    public static boolean isValidKeyFormat(String key) {
        if (key == null) {
            return false;
        }
        for (int k = 0; k < key.length(); k++) {
            char c = key.charAt(k);
            if (c <= 0x20 || c >= 0x7f) {
                return false;
            }
        }
        return true;
    }

    /** Every model id the endpoint lists, reading all pages (KTD5). */
    public Result listModels(String baseUrl, String key) {
        String base = normalizeBaseUrl(baseUrl);
        Result bad = checkSetup(base, key);
        if (bad != null) {
            return bad;
        }
        Map<String, String> headers = headers(key, false);
        List<String> ids = new ArrayList<String>();
        String after = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            String url = base + "/v1/models?limit=" + PAGE_LIMIT + (after == null ? "" : "&after_id=" + encode(after));
            Response resp;
            try {
                resp = transport.send(new Request("GET", url, headers, null));
            } catch (IOException e) {
                return Result.failure(forException(e), 0);
            }
            if (resp.status < 200 || resp.status > 299) {
                return Result.failure(forStatus(resp, true), resp.status);
            }
            Map<?, ?> body = parseObject(resp.body);
            if (body == null || !(body.get("data") instanceof List)) {
                return Result.failure(Reason.ENDPOINT_ERROR, resp.status);
            }
            // Only data[].id is relied on; a proxy may leave out the other fields.
            // An id that echoes the key is dropped: ids are stored, shown in the
            // settings page and printed by scripts, and the key must never be.
            for (Object entry : (List<?>) body.get("data")) {
                Object id = entry instanceof Map ? ((Map<?, ?>) entry).get("id") : null;
                if (id instanceof String && !((String) id).contains(key)) {
                    ids.add((String) id);
                }
            }
            Object next = body.get("last_id");
            if (!Boolean.TRUE.equals(body.get("has_more")) || !(next instanceof String)
                    || ((String) next).isEmpty() || next.equals(after)) {
                break;
            }
            after = (String) next;
        }
        return Result.success(ids);
    }

    /**
     * Sends one Messages request with max_tokens 1 (KTD4), which checks the
     * URL, TLS, key, billing and model in one go for almost no cost.
     */
    public Result testConnection(String baseUrl, String key, String model) {
        String base = normalizeBaseUrl(baseUrl);
        Result bad = checkSetup(base, key);
        if (bad != null) {
            return bad;
        }
        if (model == null || model.isEmpty()) {
            return Result.failure(Reason.NOT_SET_UP, 0);
        }
        for (int k = 0; k < model.length(); k++) {
            if (Character.isISOControl(model.charAt(k))) {
                return Result.failure(Reason.BAD_MODEL_NAME, 0);
            }
        }
        Map<String, Object> message = new LinkedHashMap<String, Object>();
        message.put("role", "user");
        message.put("content", "ping");
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("model", model);
        body.put("max_tokens", 1);
        body.put("messages", Collections.singletonList(message));
        String url = base + "/v1/messages";
        Response resp;
        try {
            resp = transport.send(new Request("POST", url, headers(key, true), Json.write(body)));
        } catch (IOException e) {
            return Result.failure(forException(e), 0);
        }
        if (resp.status >= 200 && resp.status <= 299) {
            return Result.success(new ArrayList<String>());
        }
        return Result.failure(forStatus(resp, false), resp.status);
    }

    /** NOT_SET_UP, BAD_BASE_URL or BAD_KEY_FORMAT before any request is made; null if fine.
     * base is the already-normalized base URL (null if it wasn't usable). */
    private static Result checkSetup(String base, String key) {
        if (key == null || key.isEmpty()) {
            return Result.failure(Reason.NOT_SET_UP, 0);
        }
        if (base == null) {
            return Result.failure(Reason.BAD_BASE_URL, 0);
        }
        if (!isValidKeyFormat(key)) {
            return Result.failure(Reason.BAD_KEY_FORMAT, 0);
        }
        return null;
    }

    private static Map<String, String> headers(String key, boolean json) {
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put("x-api-key", key);
        h.put("Authorization", "Bearer " + key);
        h.put("anthropic-version", VERSION);
        if (json) {
            h.put("content-type", "application/json");
        }
        return h;
    }

    /**
     * A TLS failure (a bad clock is the usual cause on the robot) versus
     * everything else: DNS, a refused connection, a timeout or a dropped
     * exchange all read as "unreachable" to the owner.
     */
    private static Reason forException(IOException e) {
        return e instanceof SSLException ? Reason.TLS_FAILED : Reason.UNREACHABLE;
    }

    /**
     * By HTTP status first, then by the body's error.type for statuses with no
     * mapping of their own. A body that isn't JSON leaves only the status.
     */
    private static Reason forStatus(Response resp, boolean listing) {
        int status = resp.status;
        if (status >= 300 && status <= 399) {
            return Reason.ENDPOINT_ERROR; // never followed (KTD3)
        }
        switch (status) {
            case 401:
                return Reason.BAD_KEY;
            case 402:
                return Reason.BILLING;
            case 403:
                return Reason.NO_PERMISSION;
            case 404:
                return listing ? Reason.MODELS_NOT_LISTED : Reason.UNKNOWN_MODEL;
            case 429:
                return Reason.RATE_LIMITED;
            case 529:
                return Reason.OVERLOADED;
            default:
                break;
        }
        String type = "";
        String message = "";
        Map<?, ?> body = parseObject(resp.body);
        if (body != null && body.get("error") instanceof Map) {
            Map<?, ?> error = (Map<?, ?>) body.get("error");
            type = error.get("type") instanceof String ? (String) error.get("type") : "";
            message = error.get("message") instanceof String ? (String) error.get("message") : "";
        }
        // Read only to choose a reason; the message itself is never shown.
        String lower = message.toLowerCase(Locale.US);
        if (status == 400 && (lower.contains("spend limit") || lower.contains("credit balance")
                || lower.contains("usage limit") || lower.contains("billing"))) {
            return Reason.BILLING;
        }
        Reason byType = forType(type, listing);
        if (byType != null) {
            return byType;
        }
        return status == 400 ? Reason.INVALID_REQUEST : Reason.ENDPOINT_ERROR;
    }

    private static Reason forType(String type, boolean listing) {
        if ("authentication_error".equals(type)) {
            return Reason.BAD_KEY;
        } else if ("permission_error".equals(type)) {
            return Reason.NO_PERMISSION;
        } else if ("billing_error".equals(type)) {
            return Reason.BILLING;
        } else if ("not_found_error".equals(type)) {
            return listing ? Reason.MODELS_NOT_LISTED : Reason.UNKNOWN_MODEL;
        } else if ("rate_limit_error".equals(type)) {
            return Reason.RATE_LIMITED;
        } else if ("overloaded_error".equals(type)) {
            return Reason.OVERLOADED;
        }
        return null;
    }

    /** The body as a JSON object, or null if it is anything else. */
    private static Map<?, ?> parseObject(String text) {
        try {
            Object v = Json.parse(text);
            return v instanceof Map ? (Map<?, ?>) v : null;
        } catch (RuntimeException e) {
            return null; // not JSON; the caller falls back to the status
        }
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError(e); // UTF-8 always exists
        }
    }
}
