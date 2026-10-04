package com.miko3.shared;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Base64;
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
 * messages() (explore plan U1, KTD2/KTD3/KTD6) is the one deliberate
 * exception: it returns the JSON object Claude wrote, because that is the
 * point of the call. Its failures are still fixed Reasons, and nothing (the
 * reply, the images, the key) is logged.
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
    /** Room for a JSON reply of a few short lines; the structured replies are far smaller. */
    private static final int MESSAGES_MAX_TOKENS = 1024;
    /**
     * A conversation turn's cap (robot 2026-10-02): two short sentences and the notes
     * object come to about 90-130 tokens on Haiku 4.5; 400 leaves room for a long notes
     * update without letting a runaway reply run on.
     */
    public static final int CONVERSATION_MAX_TOKENS = 400;
    /** A streamed body is cut off past this many characters, as ClaudeHttpsTransport cuts a whole one. */
    private static final int MAX_STREAM_CHARS = 1024 * 1024;

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
        TLS_FAILED("TLS failed; check the robot's clock and the endpoint's certificate."),
        /** messages(): Claude answered in words with no JSON, or stopped with stop_reason "refusal". */
        REFUSED("Claude declined to answer this request."),
        /** messages(): Claude's reply held JSON that isn't one valid object. */
        BAD_REPLY("Claude's reply wasn't the JSON object that was asked for.");

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
        /** A failure's retry-after header in ms, or -1 when it had none (or one that isn't whole seconds). */
        public final long retryAfterMs;

        private Result(Reason reason, int httpStatus, List<String> models, long retryAfterMs) {
            this.reason = reason;
            this.httpStatus = httpStatus;
            this.models = Collections.unmodifiableList(models);
            this.retryAfterMs = retryAfterMs;
        }

        static Result success(List<String> models) {
            return new Result(null, 0, models, -1);
        }

        static Result failure(Reason reason, int httpStatus) {
            return failure(reason, httpStatus, -1);
        }

        static Result failure(Reason reason, int httpStatus, long retryAfterMs) {
            return new Result(reason, httpStatus, new ArrayList<String>(), retryAfterMs);
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

    /**
     * A transport that can hand a 2xx body over line by line as it arrives (robot
     * 2026-10-02: the conversation streams so the line is known before the tail).
     * Any other status comes back whole in the Response, as send() returns it.
     */
    public interface StreamingTransport extends Transport {
        /** As send(); a 2xx body goes to sink one line at a time instead (its Response body is then ""). */
        Response stream(Request request, LineSink sink) throws IOException;
    }

    /** Where a streamed body's lines go, on the request's thread. */
    public interface LineSink {
        void line(String line);
    }

    /**
     * Told once, on the request's thread, when every named top-level string field
     * of a conversation reply is complete: from the stream as soon as the last one
     * closes, or from the whole reply when the transport cannot stream. A named
     * boolean field comes as the word "true" or "false". Never told for a reply that
     * fails before they are complete.
     */
    public interface EarlyFields {
        void complete(Map<String, String> fields);
    }

    /**
     * Told each text block of a conversation reply, in order, on the request's thread:
     * from the stream at the block's content_block_stop (so prose before a tool call
     * can be spoken while the call is still streaming), or from the whole reply when
     * the transport cannot stream. Never told for a reply that fails first.
     */
    public interface TextBlocks {
        void complete(String text);
    }

    /** One tool call in a reply: the id its tool_result must name, the tool's name and its input. */
    public static final class ToolUse {
        public final String id;
        public final String name;
        public final Map<String, Object> input;

        ToolUse(String id, String name, Map<String, Object> input) {
            this.id = id;
            this.name = name;
            this.input = Collections.unmodifiableMap(input);
        }
    }

    /**
     * The tools a conversation request offers (tool(...) definitions, sent in order),
     * how Claude may choose among them ("auto" unless choice() names "any", "none" or
     * one tool), the reply tool whose input carries the structured reply (its input
     * becomes MessageResult.json and feeds EarlyFields as it streams), and an optional
     * TextBlocks listener. Immutable; each setter returns a copy.
     */
    public static final class Tools {
        public final List<Map<String, Object>> definitions;
        /** "auto", "any", "none", or a tool's name to force that tool. */
        public final String choice;
        /** The tool whose input is the structured reply, or null. */
        public final String replyTool;
        public final TextBlocks textBlocks;

        public Tools(List<Map<String, Object>> definitions) {
            this(definitions, "auto", null, null);
        }

        private Tools(List<Map<String, Object>> definitions, String choice, String replyTool, TextBlocks textBlocks) {
            this.definitions = Collections.unmodifiableList(new ArrayList<Map<String, Object>>(
                    definitions == null ? Collections.<Map<String, Object>>emptyList() : definitions));
            this.choice = choice == null || choice.isEmpty() ? "auto" : choice;
            this.replyTool = replyTool;
            this.textBlocks = textBlocks;
        }

        public Tools choice(String choice) {
            return new Tools(definitions, choice, replyTool, textBlocks);
        }

        public Tools replyTool(String name) {
            return new Tools(definitions, choice, name, textBlocks);
        }

        public Tools onText(TextBlocks listener) {
            return new Tools(definitions, choice, replyTool, listener);
        }

        /** The tool_choice block: {"type": "auto" | "any" | "none"}, or {"type": "tool", "name": ...}. */
        Map<String, Object> choiceBlock() {
            Map<String, Object> c = new LinkedHashMap<String, Object>();
            if ("auto".equals(choice) || "any".equals(choice) || "none".equals(choice)) {
                c.put("type", choice);
            } else {
                c.put("type", "tool");
                c.put("name", choice);
            }
            return c;
        }
    }

    public static final class Request {
        public final String method;
        public final String url;
        /** Header name to value, in the order they are sent. Holds the key. */
        public final Map<String, String> headers;
        /** The JSON body, or null for a GET. */
        public final String body;
        /** This call's read timeout in ms, or 0 for the transport's default. */
        public final int readTimeoutMs;

        Request(String method, String url, Map<String, String> headers, String body) {
            this(method, url, headers, body, 0);
        }

        Request(String method, String url, Map<String, String> headers, String body, int readTimeoutMs) {
            this.method = method;
            this.url = url;
            this.headers = Collections.unmodifiableMap(headers);
            this.body = body;
            this.readTimeoutMs = Math.max(0, readTimeoutMs);
        }
    }

    public static final class Response {
        public final int status;
        /** The body as text, from the error stream for a failure; never null. */
        public final String body;
        /** The raw retry-after header, or null when there was none. */
        public final String retryAfter;

        public Response(int status, String body) {
            this(status, body, null);
        }

        public Response(int status, String body, String retryAfter) {
            this.status = status;
            this.body = body == null ? "" : body;
            this.retryAfter = retryAfter;
        }
    }

    /** messages()' outcome: the JSON object Claude replied with, or one Reason. */
    public static final class MessageResult {
        public final Reason reason;
        /** The HTTP status behind a failure, or 0 when there was no response. */
        public final int httpStatus;
        /** The parsed reply; empty unless ok(). */
        public final Map<String, Object> json;
        /** A failure's retry-after header in ms, or -1 when it had none (or one that isn't whole seconds). */
        public final long retryAfterMs;
        /** The reply's stop_reason ("end_turn", "tool_use", "max_tokens"...); null unless ok(). */
        public final String stopReason;
        /** The reply's text blocks joined; "" unless ok(). */
        public final String text;
        /**
         * The reply's content blocks, text ({type, text}) and tool_use ({type, id, name,
         * input}) in order, ready to send back as the assistant turn (assistantTurn());
         * empty unless ok().
         */
        public final List<Map<String, Object>> content;
        /** The tool calls the reply asked for, in order; empty unless ok(). */
        public final List<ToolUse> toolUses;

        private MessageResult(Reason reason, int httpStatus, Map<String, Object> json, long retryAfterMs) {
            this(reason, httpStatus, json, retryAfterMs, null, "", new ArrayList<Map<String, Object>>(),
                    new ArrayList<ToolUse>());
        }

        private MessageResult(Reason reason, int httpStatus, Map<String, Object> json, long retryAfterMs,
                String stopReason, String text, List<Map<String, Object>> content, List<ToolUse> toolUses) {
            this.reason = reason;
            this.httpStatus = httpStatus;
            this.json = Collections.unmodifiableMap(json);
            this.retryAfterMs = retryAfterMs;
            this.stopReason = stopReason;
            this.text = text;
            this.content = Collections.unmodifiableList(content);
            this.toolUses = Collections.unmodifiableList(toolUses);
        }

        /** The tool call with this name, or null when the reply made none. */
        public ToolUse toolUse(String name) {
            for (ToolUse u : toolUses) {
                if (u.name.equals(name)) {
                    return u;
                }
            }
            return null;
        }

        static MessageResult failure(Reason reason, int httpStatus) {
            return failure(reason, httpStatus, -1);
        }

        static MessageResult failure(Reason reason, int httpStatus, long retryAfterMs) {
            return new MessageResult(reason, httpStatus, new LinkedHashMap<String, Object>(), retryAfterMs);
        }

        /**
         * The stand-in for a request a running Backoff kept from being sent: RATE_LIMITED
         * with no HTTP status, so it reads as a rate limit and never starts a pause of its own.
         */
        public static MessageResult paused() {
            return failure(Reason.RATE_LIMITED, 0);
        }

        public boolean ok() {
            return reason == null;
        }

        /** The reason plus the status, as Result.describe(); never the reply itself. */
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

    /**
     * One back-off clock for rate limits (robot 2026-10-01: a 429 retried 0.6 s later made
     * it worse). A 429 or 529 (RATE_LIMITED or OVERLOADED with a status) pauses requests for
     * its retry-after, or without one for FIRST_MS, never longer than CAP_MS and without
     * doubling (owner, 2026-10-01: the doubling to 5 min throttled him "way too hard"). A rate limit that lands inside a
     * running pause (a request already in flight) starts nothing new. Thread-safe; the caller
     * owns the clock and decides what a pause holds back.
     */
    public static final class Backoff {
        public static final long FIRST_MS = 15000;
        public static final long CAP_MS = 60000;
        /** A retry-after above this is taken as this. */
        private static final long RETRY_AFTER_CAP_MS = CAP_MS;

        /** False: a rate limit starts no pause and nothing is held back (the robot's choice). */
        private final boolean enabled;

        public Backoff() {
            this(true);
        }

        public Backoff(boolean enabled) {
            this.enabled = enabled;
        }

        private long until = Long.MIN_VALUE;
        private long next = FIRST_MS;

        /** Records one result at nowMs; returns the pause it started in ms, or 0 when it started none. */
        public synchronized long record(MessageResult r, long nowMs) {
            return record(r.ok(), r.reason, r.httpStatus, r.retryAfterMs, nowMs);
        }

        /** As record(MessageResult), for a listing or connection test's Result. */
        public synchronized long record(Result r, long nowMs) {
            return record(r.ok(), r.reason, r.httpStatus, r.retryAfterMs, nowMs);
        }

        private long record(boolean ok, Reason reason, int status, long retryAfterMs, long nowMs) {
            if (!enabled) {
                return 0;
            }
            if (ok) {
                next = FIRST_MS;
                return 0;
            }
            if (status <= 0 || (reason != Reason.RATE_LIMITED && reason != Reason.OVERLOADED)) {
                return 0;
            }
            if (nowMs < until) {
                return 0;
            }
            long pause;
            if (retryAfterMs >= 0) {
                pause = Math.min(retryAfterMs, RETRY_AFTER_CAP_MS);
            } else {
                pause = Math.min(CAP_MS, next);
            }
            until = nowMs + pause;
            return pause;
        }

        /** How much of the pause is left at nowMs; 0 when requests may go. */
        public synchronized long remainingMs(long nowMs) {
            return nowMs < until ? until - nowMs : 0;
        }
    }

    /** A retry-after header as ms: whole (or decimal) seconds, 0 or more; -1 for none or an HTTP date. */
    static long retryAfterMs(String header) {
        if (header == null) {
            return -1;
        }
        String h = header.trim();
        if (h.length() > 12 || !h.matches("[0-9]+(\\.[0-9]+)?")) {
            return -1;
        }
        return (long) Math.ceil(Double.parseDouble(h) * 1000);
    }

    private final Transport transport;
    /** Set once the endpoint has rejected output_config; later calls put the schema in the prompt. */
    private volatile boolean schemaInPrompt;
    /**
     * conversation(): the endpoint answered 400 naming effort, so this ClaudeApi
     * sends no effort from then on (meeting plan U8, KTD9). Separate from the
     * schema-in-prompt gate above, which a 400 naming the output format alone sets.
     */
    private volatile boolean effortUnsupported;

    public ClaudeApi(Transport transport) {
        this.transport = transport;
    }

    /** Whether the endpoint refused output_config, so the schema now rides in the system prompt. */
    public boolean schemaInPrompt() {
        return schemaInPrompt;
    }

    /** Whether the endpoint refused effort, so conversation() no longer sends it. */
    public boolean effortRefused() {
        return effortUnsupported;
    }

    /** A text content block for messages(). */
    public static Map<String, Object> textBlock(String text) {
        Map<String, Object> b = new LinkedHashMap<String, Object>();
        b.put("type", "text");
        b.put("text", text);
        return b;
    }

    /**
     * An image content block for messages(): the JPEG bytes as base64 with no
     * line breaks. The bytes are slimmed first (JpegSlim: the camera vendor's
     * APPn metadata dropped, pixels untouched), which cuts a robot frame from
     * ~445 KB to ~140 KB; the caller's array is not modified.
     */
    public static Map<String, Object> jpegBlock(byte[] jpeg) {
        Map<String, Object> source = new LinkedHashMap<String, Object>();
        source.put("type", "base64");
        source.put("media_type", "image/jpeg");
        source.put("data", Base64.getEncoder().encodeToString(JpegSlim.slim(jpeg)));
        Map<String, Object> b = new LinkedHashMap<String, Object>();
        b.put("type", "image");
        b.put("source", source);
        return b;
    }

    /**
     * One tool definition for Tools: name, description and the input's JSON schema
     * (nested Maps/Lists; null for a tool that takes no input, sent as an empty object schema).
     */
    public static Map<String, Object> tool(String name, String description, Map<String, ?> inputSchema) {
        Map<String, Object> t = new LinkedHashMap<String, Object>();
        t.put("name", name);
        if (description != null && !description.isEmpty()) {
            t.put("description", description);
        }
        if (inputSchema == null) {
            Map<String, Object> empty = new LinkedHashMap<String, Object>();
            empty.put("type", "object");
            empty.put("properties", new LinkedHashMap<String, Object>());
            t.put("input_schema", empty);
        } else {
            t.put("input_schema", inputSchema);
        }
        return t;
    }

    /** A tool_result block answering the tool call with this id in text. */
    public static Map<String, Object> toolResult(String toolUseId, String text) {
        return toolResult(toolUseId, (Object) (text == null ? "" : text), false);
    }

    /** A tool_result block saying the call failed (is_error), with why in text Claude can read. */
    public static Map<String, Object> toolError(String toolUseId, String message) {
        return toolResult(toolUseId, (Object) (message == null ? "" : message), true);
    }

    /**
     * A tool_result block answering with a photo: the JPEG as jpegBlock() makes it
     * (slimmed, base64), then the caption as a text block when there is one.
     */
    public static Map<String, Object> toolResultImage(String toolUseId, byte[] jpeg, String caption) {
        List<Map<String, Object>> content = new ArrayList<Map<String, Object>>();
        content.add(jpegBlock(jpeg));
        if (caption != null && !caption.isEmpty()) {
            content.add(textBlock(caption));
        }
        return toolResult(toolUseId, content, false);
    }

    /** A tool_result block with any content: a String, or a list of text and image blocks. */
    public static Map<String, Object> toolResult(String toolUseId, Object content, boolean isError) {
        Map<String, Object> b = new LinkedHashMap<String, Object>();
        b.put("type", "tool_result");
        b.put("tool_use_id", toolUseId);
        b.put("content", content);
        if (isError) {
            b.put("is_error", Boolean.TRUE);
        }
        return b;
    }

    /** The assistant turn to put back in the history: the reply's content blocks as they came. */
    public static Map<String, Object> assistantTurn(MessageResult reply) {
        return message("assistant", new ArrayList<Map<String, Object>>(reply.content));
    }

    /** The user turn that answers a tool-using reply: every tool_result, in one message (parallel calls included). */
    public static Map<String, Object> toolResults(List<Map<String, Object>> results) {
        return message("user", new ArrayList<Map<String, Object>>(results));
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
                return Result.failure(forStatus(resp, true), resp.status, retryAfterMs(resp.retryAfter));
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
        if (hasControlChar(model)) {
            return Result.failure(Reason.BAD_MODEL_NAME, 0);
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
        return Result.failure(forStatus(resp, false), resp.status, retryAfterMs(resp.retryAfter));
    }

    /**
     * One user turn of content blocks (textBlock/jpegBlock, sent in the given
     * order) and the JSON object Claude replies with. Blocking: call it off
     * the main thread.
     *
     * With a schema, it is sent as output_config.format (json_schema). If the
     * endpoint answers 400 naming output_config, the call is retried once
     * with the schema in the system prompt instead, and every later call on
     * this ClaudeApi does the same. Without a schema the prompt must ask for
     * JSON itself.
     *
     * timeoutMs is this call's read timeout (0 or less: the transport's
     * default); a timeout is UNREACHABLE. A reply in words with no JSON, or
     * stop_reason "refusal", is REFUSED; JSON that isn't one object is
     * BAD_REPLY; a response body that isn't a Messages reply is ENDPOINT_ERROR.
     *
     * @param system the system prompt, or null for none
     * @param schema a JSON schema as nested Maps/Lists, or null
     */
    public MessageResult messages(ClaudeAccess access, String system, List<Map<String, Object>> content,
            Map<String, ?> schema, int timeoutMs) {
        MessageResult notReady = preflight(access);
        if (notReady != null) {
            return notReady;
        }
        String base = normalizeBaseUrl(access.baseUrl);
        boolean useOutputConfig = schema != null && !schemaInPrompt;
        Request request = messagesRequest(base, access, system, content, schema, useOutputConfig, timeoutMs);
        Response resp;
        try {
            resp = transport.send(request);
            String error = errorMessage(resp);
            if (useOutputConfig && error != null && error.contains("output_config")) {
                schemaInPrompt = true;
                resp = transport.send(messagesRequest(base, access, system, content, schema, false, timeoutMs));
            }
        } catch (IOException e) {
            return MessageResult.failure(forException(e), 0);
        }
        return reply(resp);
    }

    /**
     * Robot 2026-10-02: keeps the pooled HTTPS connection to the endpoint warm, so the
     * next real request skips the TCP and TLS setup (about 0.4 s on the robot). One
     * GET of a single models page: no tokens, no cost. True on a 2xx; never throws.
     */
    public boolean keepWarm(ClaudeAccess access, int timeoutMs) {
        if (access == null || !access.isSetUp()) {
            return false;
        }
        String base = normalizeBaseUrl(access.baseUrl);
        if (checkSetup(base, access.apiKey) != null) {
            return false;
        }
        try {
            Response resp = transport.send(new Request("GET", base + "/v1/models?limit=1", headers(access.apiKey, false),
                    null, timeoutMs));
            return resp.status >= 200 && resp.status <= 299;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** One message of a conversation: role "user" or "assistant" with text, or "user" with content blocks. */
    public static Map<String, Object> message(String role, Object content) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    /**
     * Sends one multi-turn Messages request (meeting plan U8, KTD9): the frozen
     * system prefix, the message list as given, max_tokens 400, the JSON schema
     * as output_config.format, effort (null: none) beside it, and the top-level
     * automatic cache breakpoint. Two gates, each remembered for this ClaudeApi
     * and each retrying once: a 400 naming effort drops effort and keeps the
     * JSON-schema format; a 400 naming output_config without naming effort moves
     * the schema into the system prompt. Everything else is as messages().
     */
    public MessageResult conversation(ClaudeAccess access, String system, List<Map<String, Object>> messages,
            Map<String, ?> schema, String effort, int timeoutMs) {
        return conversation(access, system, messages, schema, effort, timeoutMs, null, null);
    }

    /**
     * As conversation(), and streamed when the transport can (robot 2026-10-02):
     * early is told the named top-level string fields (the line, the question, the
     * name) as soon as they are all complete, while the rest of the reply is still
     * coming; the result is the whole reply, as before. Without early nothing streams.
     * Effort is never sent to a model known to refuse it (Haiku, the Claude 3 family).
     */
    public MessageResult conversation(ClaudeAccess access, String system, List<Map<String, Object>> messages,
            Map<String, ?> schema, String effort, int timeoutMs, List<String> earlyNames, EarlyFields early) {
        return conversation(access, system, messages, schema, effort, timeoutMs, earlyNames, early, null);
    }

    /**
     * As conversation(), offering tools (null: none, and the request and reply are
     * exactly as without this overload). With tools:
     * - the request carries tools and tool_choice beside the schema (if any); the gateway
     *   takes both together (robot 2026-10-02), and the gates retry keeping the tools;
     * - a reply that calls a tool is ok: MessageResult.toolUses, .content and .stopReason
     *   ("tool_use") say what to run; json is the reply text's JSON object when it has one;
     * - with tools.replyTool, that tool's input is the structured reply: it becomes json,
     *   and early is told its named fields as the input streams, but never when the reply
     *   also calls another tool (one that started before them, or any in the whole reply):
     *   such a reply is not the final one;
     * - tools.textBlocks is told each text block as it closes (a reason to stream on its own);
     * - a tool input that isn't one JSON object (cut off by max_tokens) is BAD_REPLY.
     * Answer a tool call by sending the history plus assistantTurn(result) and
     * toolResults([...toolResult/toolError/toolResultImage]) in the next call.
     */
    public MessageResult conversation(ClaudeAccess access, String system, List<Map<String, Object>> messages,
            Map<String, ?> schema, String effort, int timeoutMs, List<String> earlyNames, EarlyFields early,
            Tools tools) {
        MessageResult notReady = preflight(access);
        if (notReady != null) {
            return notReady;
        }
        String base = normalizeBaseUrl(access.baseUrl);
        boolean wantsEarly = early != null && earlyNames != null;
        TextBlocks texts = tools == null ? null : tools.textBlocks;
        boolean stream = (wantsEarly || texts != null) && transport instanceof StreamingTransport;
        Stream sink = stream ? new Stream(wantsEarly ? earlyNames : null, wantsEarly ? early : null, tools,
                schema != null) : null;
        Response resp;
        try {
            boolean useOutputConfig = schema != null && !schemaInPrompt;
            String sendEffort = effortUnsupported || !takesEffort(access.model) ? null : effort;
            resp = send(conversationRequest(base, access, system, messages, schema, useOutputConfig,
                    sendEffort, true, stream, tools, timeoutMs), sink);
            // The 400's error message, read only to choose the retry; never shown.
            String error = errorMessage(resp);
            if (sendEffort != null && error != null && error.contains("effort")) {
                effortUnsupported = true;
                sendEffort = null;
                resp = send(conversationRequest(base, access, system, messages, schema, useOutputConfig,
                        null, true, stream, tools, timeoutMs), sink);
                error = errorMessage(resp);
            }
            if (useOutputConfig && error != null && error.contains("output_config") && !error.contains("effort")) {
                schemaInPrompt = true;
                resp = send(conversationRequest(base, access, system, messages, schema, false, sendEffort,
                        true, stream, tools, timeoutMs), sink);
            }
        } catch (IOException e) {
            return MessageResult.failure(forException(e), 0);
        }
        MessageResult r = sink != null && resp.status >= 200 && resp.status <= 299 && resp.body.isEmpty()
                ? sink.result(resp.status) : reply(resp, tools, schema != null);
        if (texts != null && r.ok() && sink == null) {
            for (Map<String, Object> block : r.content) {
                if ("text".equals(block.get("type"))) {
                    texts.complete((String) block.get("text"));
                }
            }
        }
        // A tool call with no structured reply yet (no JSON text, no reply tool) has no fields to tell,
        // and a reply tool's input beside another tool call is not the final reply (review 2026-10-03).
        if (wantsEarly && r.ok() && (sink == null || !sink.told) && (tools == null || !r.json.isEmpty())
                && !otherToolUse(r, tools)) {
            Map<String, String> fields = new LinkedHashMap<String, String>();
            for (String name : earlyNames) {
                Object v = r.json.get(name);
                fields.put(name, v instanceof String || v instanceof Boolean ? String.valueOf(v) : "");
            }
            early.complete(fields);
        }
        return r;
    }

    /** Whether the reply calls a tool other than tools.replyTool (none when there is no reply tool). */
    private static boolean otherToolUse(MessageResult r, Tools tools) {
        if (tools == null || tools.replyTool == null) {
            return false;
        }
        for (ToolUse u : r.toolUses) {
            if (!tools.replyTool.equals(u.name)) {
                return true;
            }
        }
        return false;
    }

    /** One request: streamed into sink when there is one, else sent whole. */
    private Response send(Request request, Stream sink) throws IOException {
        if (sink == null) {
            return transport.send(request);
        }
        sink.reset();
        return ((StreamingTransport) transport).stream(request, sink);
    }

    /** Whether a model takes output_config.effort: Haiku and the Claude 3 family answer 400. */
    public static boolean takesEffort(String model) {
        String m = model == null ? "" : model.toLowerCase(Locale.US);
        return !m.contains("haiku") && !m.startsWith("claude-3");
    }

    /**
     * A streamed Messages reply (SSE), read as it arrives: each content block by its
     * index (text deltas, and a tool_use's input_json_delta chunks, parsed at the end),
     * the stop reason, and an error event. early is told the named fields once they
     * are all complete, from the text or from the reply tool's input; a Tools text
     * listener is told each text block at its content_block_stop. Nothing in it is logged.
     */
    private static final class Stream implements LineSink {
        /** One content block as it streams: text, or a tool_use's id, name and raw input JSON. */
        private static final class Block {
            final String type;
            final String id;
            final String name;
            final StringBuilder buf = new StringBuilder();

            Block(String type, String id, String name) {
                this.type = type;
                this.id = id;
                this.name = name;
            }
        }

        private final List<String> names;
        private final EarlyFields early;
        private final Tools tools;
        private final boolean wantJson;
        private final StringBuilder text = new StringBuilder();
        /** By index, in the order they started. */
        private final Map<Integer, Block> blocks = new LinkedHashMap<Integer, Block>();
        private int chars;
        private String stopReason;
        private String errorType;
        private boolean any;
        boolean told;

        Stream(List<String> names, EarlyFields early, Tools tools, boolean wantJson) {
            this.names = names;
            this.early = early;
            this.tools = tools;
            this.wantJson = wantJson;
        }

        /** A retried request starts afresh. */
        void reset() {
            text.setLength(0);
            blocks.clear();
            chars = 0;
            stopReason = null;
            errorType = null;
            any = false;
        }

        @Override
        public void line(String line) {
            if (line == null || !line.startsWith("data:")) {
                return;
            }
            Map<?, ?> ev = parseObject(line.substring(5).trim());
            if (ev == null) {
                return;
            }
            any = true;
            Object type = ev.get("type");
            if ("content_block_start".equals(type) && ev.get("content_block") instanceof Map) {
                Map<?, ?> cb = (Map<?, ?>) ev.get("content_block");
                blocks.put(index(ev), new Block(String.valueOf(cb.get("type")), string(cb.get("id")),
                        string(cb.get("name"))));
            } else if ("content_block_delta".equals(type) && ev.get("delta") instanceof Map) {
                Map<?, ?> delta = (Map<?, ?>) ev.get("delta");
                Object t = delta.get("text");
                Object partial = delta.get("partial_json");
                if (t instanceof String && chars < MAX_STREAM_CHARS) {
                    chars += ((String) t).length();
                    block(ev, "text").buf.append((String) t);
                    text.append((String) t);
                    tellIfComplete(text);
                } else if (partial instanceof String && chars < MAX_STREAM_CHARS) {
                    chars += ((String) partial).length();
                    Block b = block(ev, "tool_use");
                    b.buf.append((String) partial);
                    if (tools != null && tools.replyTool != null && tools.replyTool.equals(b.name)) {
                        tellIfComplete(b.buf);
                    }
                }
            } else if ("content_block_stop".equals(type)) {
                Block b = blocks.get(index(ev));
                if (b != null && "text".equals(b.type) && tools != null && tools.textBlocks != null
                        && errorType == null) {
                    tools.textBlocks.complete(b.buf.toString());
                }
            } else if ("message_delta".equals(type) && ev.get("delta") instanceof Map) {
                Object r = ((Map<?, ?>) ev.get("delta")).get("stop_reason");
                if (r instanceof String) {
                    stopReason = (String) r;
                }
            } else if ("error".equals(type)) {
                Object e = ev.get("error");
                Object et = e instanceof Map ? ((Map<?, ?>) e).get("type") : null;
                errorType = et instanceof String ? (String) et : "";
            }
        }

        private static int index(Map<?, ?> ev) {
            Object i = ev.get("index");
            return i instanceof Number ? ((Number) i).intValue() : 0;
        }

        private static String string(Object v) {
            return v instanceof String ? (String) v : "";
        }

        /**
         * Review 2026-10-03: a tool call other than the reply tool has started, so this reply is not
         * the final one (a tool round may replace its line): its fields are not told early.
         */
        private boolean otherToolStarted() {
            if (tools == null || tools.replyTool == null) {
                return false;
            }
            for (Block b : blocks.values()) {
                if ("tool_use".equals(b.type) && !tools.replyTool.equals(b.name)) {
                    return true;
                }
            }
            return false;
        }

        /** The block a delta belongs to; one with no content_block_start is taken to be of this type. */
        private Block block(Map<?, ?> ev, String type) {
            int i = index(ev);
            Block b = blocks.get(i);
            if (b == null) {
                b = new Block(type, "", "");
                blocks.put(i, b);
            }
            return b;
        }

        private void tellIfComplete(CharSequence source) {
            if (told || errorType != null || early == null || names == null || otherToolStarted()) {
                return;
            }
            Map<String, String> found = completeStringFields(source.toString());
            Map<String, String> fields = new LinkedHashMap<String, String>();
            for (String name : names) {
                String v = found.get(name);
                if (v == null) {
                    return;
                }
                fields.put(name, v);
            }
            told = true;
            early.complete(fields);
        }

        /** The whole reply, read as a Messages body would be. */
        MessageResult result(int status) {
            if (errorType != null) {
                Reason byType = forType(errorType, false);
                return MessageResult.failure(byType != null ? byType : Reason.ENDPOINT_ERROR, status);
            }
            if (!any) {
                return MessageResult.failure(Reason.ENDPOINT_ERROR, status);
            }
            List<Map<String, Object>> content = new ArrayList<Map<String, Object>>();
            for (Block b : blocks.values()) {
                Map<String, Object> block = new LinkedHashMap<String, Object>();
                if ("text".equals(b.type)) {
                    block.put("type", "text");
                    block.put("text", b.buf.toString());
                } else if ("tool_use".equals(b.type)) {
                    String raw = b.buf.toString().trim();
                    Object input = raw.isEmpty() ? new LinkedHashMap<String, Object>() : parseAny(raw);
                    if (!(input instanceof Map)) {
                        // Cut off (max_tokens) or not an object: nothing that can be run.
                        return MessageResult.failure("refusal".equals(stopReason) ? Reason.REFUSED : Reason.BAD_REPLY,
                                status);
                    }
                    block.put("type", "tool_use");
                    block.put("id", b.id);
                    block.put("name", b.name);
                    block.put("input", input);
                } else {
                    continue; // thinking and other block types are not kept
                }
                content.add(block);
            }
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("content", content);
            body.put("stop_reason", stopReason);
            return readReply(new Response(status, Json.write(body)), tools, wantJson);
        }
    }

    /**
     * The top-level string fields of a JSON object that are already complete in this
     * prefix of it (from its first '{'; a ```json fence before it is skipped), and its
     * top-level booleans as the words "true" and "false" once the value is closed by the
     * next ',' or '}' (owner 2026-10-03: the reply's "addressed" comes before the line).
     * A field still open, a nested one, or any other value is not in the map. Never throws.
     */
    static Map<String, String> completeStringFields(String partial) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        if (partial == null) {
            return out;
        }
        int n = partial.length();
        int i = partial.indexOf('{');
        if (i < 0) {
            return out;
        }
        i++;
        while (true) {
            i = skipSpace(partial, i);
            if (i < n && partial.charAt(i) == ',') {
                i = skipSpace(partial, i + 1);
            }
            if (i >= n || partial.charAt(i) != '"') {
                return out;
            }
            int keyEnd = stringEnd(partial, i);
            if (keyEnd < 0) {
                return out;
            }
            Object key = parseAny(partial.substring(i, keyEnd));
            i = skipSpace(partial, keyEnd);
            if (i >= n || partial.charAt(i) != ':') {
                return out;
            }
            i = skipSpace(partial, i + 1);
            if (i >= n) {
                return out;
            }
            if (partial.charAt(i) == '"') {
                int end = stringEnd(partial, i);
                if (end < 0) {
                    return out;
                }
                Object v = parseAny(partial.substring(i, end));
                if (key instanceof String && v instanceof String) {
                    out.put((String) key, (String) v);
                }
                i = end;
                continue;
            }
            int start = i;
            i = valueEnd(partial, i);
            if (i < 0) {
                return out;
            }
            String scalar = partial.substring(start, i).trim();
            if (key instanceof String && (scalar.equals("true") || scalar.equals("false"))) {
                out.put((String) key, scalar);
            }
        }
    }

    private static int skipSpace(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    /** The index just past the string starting at the quote at i, or -1 while it is still open. */
    private static int stringEnd(String s, int i) {
        for (int k = i + 1; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c == '\\') {
                k++;
            } else if (c == '"') {
                return k + 1;
            }
        }
        return -1;
    }

    /** The index just past the non-string value at i (an object, array or scalar), or -1 while it is still open. */
    private static int valueEnd(String s, int i) {
        int depth = 0;
        for (int k = i; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c == '"') {
                int end = stringEnd(s, k);
                if (end < 0) {
                    return -1;
                }
                k = end - 1;
            } else if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                if (depth == 0) {
                    return k;
                }
                depth--;
                if (depth == 0) {
                    return k + 1;
                }
            } else if (c == ',' && depth == 0) {
                return k;
            }
        }
        return -1;
    }

    /**
     * The checks messages() and conversation() both make before any request:
     * NOT_SET_UP, BAD_BASE_URL, BAD_KEY_FORMAT or BAD_MODEL_NAME, or null when
     * the access is fine to send with.
     */
    private static MessageResult preflight(ClaudeAccess access) {
        if (access == null || !access.isSetUp()) {
            return MessageResult.failure(Reason.NOT_SET_UP, 0);
        }
        Result bad = checkSetup(normalizeBaseUrl(access.baseUrl), access.apiKey);
        if (bad != null) {
            return MessageResult.failure(bad.reason, 0);
        }
        if (hasControlChar(access.model)) {
            return MessageResult.failure(Reason.BAD_MODEL_NAME, 0);
        }
        return null;
    }

    /** The last response of messages() or conversation(): a non-2xx maps to its reason, a 2xx is read. */
    private static MessageResult reply(Response resp) {
        return reply(resp, null, true);
    }

    private static MessageResult reply(Response resp, Tools tools, boolean wantJson) {
        if (resp.status < 200 || resp.status > 299) {
            return MessageResult.failure(forStatus(resp, false), resp.status, retryAfterMs(resp.retryAfter));
        }
        return readReply(resp, tools, wantJson);
    }

    /**
     * One Messages request body. The schema goes into output_config.format when
     * useOutputConfig, else as an ask appended to the system prompt; effort
     * (null or empty: none) sits beside the format; cacheControl adds the
     * top-level automatic cache breakpoint (KTD9: the prefix may sit under a
     * model's silent minimum), which the single-turn ask of messages() never gains.
     */
    private static Request conversationRequest(String base, ClaudeAccess access, String system,
            List<Map<String, Object>> messages, Map<String, ?> schema, boolean useOutputConfig, String effort,
            boolean cacheControl, boolean stream, Tools tools, int timeoutMs) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("model", access.model);
        // The conversation (cacheControl) has its own, smaller cap; messages() keeps 1024.
        body.put("max_tokens", cacheControl ? CONVERSATION_MAX_TOKENS : MESSAGES_MAX_TOKENS);
        String sys = schema != null && !useOutputConfig ? withSchemaAsk(system, schema) : system;
        if (sys != null && !sys.isEmpty()) {
            body.put("system", sys);
        }
        body.put("messages", messages == null ? new ArrayList<Object>() : messages);
        if (tools != null && !tools.definitions.isEmpty()) {
            body.put("tools", tools.definitions);
            body.put("tool_choice", tools.choiceBlock());
        }
        Map<String, Object> outputConfig = new LinkedHashMap<String, Object>();
        if (useOutputConfig) {
            outputConfig.put("format", formatBlock(schema));
        }
        if (effort != null && !effort.isEmpty()) {
            outputConfig.put("effort", effort);
        }
        if (!outputConfig.isEmpty()) {
            body.put("output_config", outputConfig);
        }
        if (cacheControl) {
            body.put("cache_control", Collections.singletonMap("type", "ephemeral"));
        }
        if (stream) {
            body.put("stream", Boolean.TRUE);
        }
        return new Request("POST", base + "/v1/messages", headers(access.apiKey, true), Json.write(body),
                timeoutMs);
    }

    /** The single-turn request of messages(): one user message, no effort, no cache breakpoint. */
    private static Request messagesRequest(String base, ClaudeAccess access, String system,
            List<Map<String, Object>> content, Map<String, ?> schema, boolean useOutputConfig, int timeoutMs) {
        Map<String, Object> message = new LinkedHashMap<String, Object>();
        message.put("role", "user");
        message.put("content", content == null ? new ArrayList<Object>() : content);
        return conversationRequest(base, access, system, Collections.singletonList(message), schema,
                useOutputConfig, null, false, false, null, timeoutMs);
    }

    /** The system prompt with the schema-in-prompt ask appended (the ask alone when there is no prompt). */
    private static String withSchemaAsk(String system, Map<String, ?> schema) {
        String ask = "Reply with only a JSON object that matches this JSON schema, and no other text: "
                + Json.write(schema);
        return system == null || system.isEmpty() ? ask : system + "\n\n" + ask;
    }

    /** The output_config.format block asking for the schema. */
    private static Map<String, Object> formatBlock(Map<String, ?> schema) {
        Map<String, Object> format = new LinkedHashMap<String, Object>();
        format.put("type", "json_schema");
        format.put("schema", schema);
        return format;
    }

    private static boolean hasControlChar(String s) {
        for (int k = 0; k < s.length(); k++) {
            if (Character.isISOControl(s.charAt(k))) {
                return true;
            }
        }
        return false;
    }

    /**
     * A 400's error.message, or null for any other status or body shape. One
     * naming output_config means the endpoint (or a proxy) doesn't take it; one
     * naming effort is the effort gate.
     */
    private static String errorMessage(Response resp) {
        if (resp.status != 400) {
            return null;
        }
        Map<?, ?> body = parseObject(resp.body);
        Object error = body == null ? null : body.get("error");
        Object message = error instanceof Map ? ((Map<?, ?>) error).get("message") : null;
        return message instanceof String ? (String) message : null;
    }

    /**
     * The JSON object in a 2xx Messages reply, or why there isn't one. With tools, a
     * reply is also read for its tool calls: one that calls a tool, or answers in prose
     * when no schema asked for JSON, is ok without a JSON object.
     */
    private static MessageResult readReply(Response resp, Tools tools, boolean wantJson) {
        Map<?, ?> body = parseObject(resp.body);
        if (body == null) {
            return MessageResult.failure(Reason.ENDPOINT_ERROR, resp.status);
        }
        if ("refusal".equals(body.get("stop_reason"))) {
            return MessageResult.failure(Reason.REFUSED, resp.status);
        }
        if (!(body.get("content") instanceof List)) {
            return MessageResult.failure(Reason.ENDPOINT_ERROR, resp.status);
        }
        StringBuilder text = new StringBuilder();
        boolean anyText = false;
        List<Map<String, Object>> content = new ArrayList<Map<String, Object>>();
        List<ToolUse> uses = new ArrayList<ToolUse>();
        for (Object block : (List<?>) body.get("content")) {
            if (!(block instanceof Map)) {
                continue;
            }
            Map<?, ?> b = (Map<?, ?>) block;
            if ("text".equals(b.get("type")) && b.get("text") instanceof String) {
                text.append((String) b.get("text"));
                anyText = true;
                content.add(textBlock((String) b.get("text")));
            } else if (tools != null && "tool_use".equals(b.get("type"))) {
                if (!(b.get("id") instanceof String) || !(b.get("name") instanceof String)
                        || !(b.get("input") instanceof Map)) {
                    return MessageResult.failure(Reason.BAD_REPLY, resp.status);
                }
                Map<String, Object> input = stringKeys((Map<?, ?>) b.get("input"));
                ToolUse use = new ToolUse((String) b.get("id"), (String) b.get("name"), input);
                uses.add(use);
                // Only the fields the API takes back; a gateway's extras (caller) are dropped.
                Map<String, Object> echo = new LinkedHashMap<String, Object>();
                echo.put("type", "tool_use");
                echo.put("id", use.id);
                echo.put("name", use.name);
                echo.put("input", input);
                content.add(echo);
            }
        }
        String stopReason = body.get("stop_reason") instanceof String ? (String) body.get("stop_reason") : null;
        ToolUse replyUse = null;
        if (tools != null && tools.replyTool != null) {
            for (ToolUse u : uses) {
                if (tools.replyTool.equals(u.name)) {
                    replyUse = u;
                    break;
                }
            }
        }
        Map<String, Object> json = new LinkedHashMap<String, Object>();
        if (replyUse != null) {
            json.putAll(replyUse.input);
        } else {
            if (!anyText && uses.isEmpty()) {
                return MessageResult.failure(Reason.ENDPOINT_ERROR, resp.status);
            }
            // Without tools every reply must be the JSON object (as it always was); with
            // tools, only a schema-asking reply that calls no tool must be.
            boolean jsonRequired = tools == null || (wantJson && uses.isEmpty());
            String t = text.toString().trim();
            Object parsed = t.isEmpty() ? null : parseAny(t);
            if (parsed == null) {
                // Prompt-only JSON may come wrapped in prose or a ```json fence.
                int open = t.indexOf('{');
                if (open < 0) {
                    if (jsonRequired) {
                        return MessageResult.failure(t.indexOf('[') >= 0 ? Reason.BAD_REPLY : Reason.REFUSED,
                                resp.status);
                    }
                } else {
                    int close = t.lastIndexOf('}');
                    parsed = close > open ? parseAny(t.substring(open, close + 1)) : null;
                }
            }
            if (parsed instanceof Map) {
                json.putAll(stringKeys((Map<?, ?>) parsed));
            } else if (jsonRequired) {
                return MessageResult.failure(Reason.BAD_REPLY, resp.status);
            }
        }
        return new MessageResult(null, 0, json, -1, stopReason, text.toString(), content, uses);
    }

    private static Map<String, Object> stringKeys(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
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

    /** Any JSON value, or null if the text isn't JSON. */
    private static Object parseAny(String text) {
        try {
            return Json.parse(text);
        } catch (RuntimeException e) {
            return null;
        }
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
