package com.miko3.shared;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.SSLHandshakeException;

/**
 * Host-JVM checks for shared/ClaudeApi (settings plan U3), driven by
 * scripts/tests/test_claude_api.py. A fake transport replays canned
 * responses (or throws canned exceptions) and records every request.
 *
 * Prints one "PASS <name>" or "FAIL <name>: <detail>" line per scenario; the
 * Python side asserts on each by name.
 */
public final class ClaudeApiHarness {
    private static final String BASE = "https://example.test";
    private static final String KEY = "sk-test-SECRETKEY-1234";
    private static final String MODEL = "claude-test-1";

    /** Replays queued outcomes in order; each is a Response or an IOException. */
    private static class FakeTransport implements ClaudeApi.Transport {
        final ArrayDeque<Object> outcomes = new ArrayDeque<Object>();
        final List<ClaudeApi.Request> requests = new ArrayList<ClaudeApi.Request>();

        FakeTransport reply(int status, String body) {
            outcomes.add(new ClaudeApi.Response(status, body));
            return this;
        }

        FakeTransport reply(int status, String body, String retryAfter) {
            outcomes.add(new ClaudeApi.Response(status, body, retryAfter));
            return this;
        }

        FakeTransport fail(IOException e) {
            outcomes.add(e);
            return this;
        }

        @Override
        public ClaudeApi.Response send(ClaudeApi.Request request) throws IOException {
            requests.add(request);
            Object next = outcomes.poll();
            if (next == null) {
                throw new IllegalStateException("unexpected extra request to " + request.url);
            }
            if (next instanceof IOException) {
                throw (IOException) next;
            }
            return (ClaudeApi.Response) next;
        }
    }

    private static void check(String name, boolean ok, String detail) {
        System.out.println(ok ? "PASS " + name : "FAIL " + name + ": " + detail);
    }

    private static String error(String type, String message) {
        return "{\"type\":\"error\",\"error\":{\"type\":\"" + type + "\",\"message\":\"" + message + "\"}}";
    }

    private static String describe(ClaudeApi.Result r) {
        return r.ok() ? "ok models=" + r.models : r.reason + " status=" + r.httpStatus;
    }

    /** Runs one connection test against a single canned reply and checks its reason. */
    private static void testMaps(String name, int status, String body, ClaudeApi.Reason want) {
        FakeTransport t = new FakeTransport().reply(status, body);
        ClaudeApi.Result r = new ClaudeApi(t).testConnection(BASE, KEY, MODEL);
        check(name, !r.ok() && r.reason == want && r.httpStatus == status && t.requests.size() == 1,
                describe(r) + " requests=" + t.requests.size());
    }

    private static void testFails(String name, IOException e, ClaudeApi.Reason want) {
        FakeTransport t = new FakeTransport().fail(e);
        ClaudeApi.Result r = new ClaudeApi(t).testConnection(BASE, KEY, MODEL);
        check(name, !r.ok() && r.reason == want && r.httpStatus == 0, describe(r));
    }

    public static void main(String[] args) {
        normalization();
        listing();
        connectionTest();
        wire();
        noRequestWhenNotSetUp();
        secrecy();
        messages();
        conversation();
        streaming();
        keepWarm();
        rateLimits();
    }

    private static void normalization() {
        boolean all = true;
        String got = "";
        for (String raw : new String[] {"https://h.test", "https://h.test/", "https://h.test/v1",
                "https://h.test/v1/", "HTTPS://h.test/v1"}) {
            String n = ClaudeApi.normalizeBaseUrl(raw);
            got += raw + "->" + n + " ";
            all &= "https://h.test".equals(n);
        }
        check("normalize_drops_trailing_slash_and_v1", all, got);
        check("normalize_keeps_a_path_prefix",
                "https://h.test:8443/proxy".equals(ClaudeApi.normalizeBaseUrl("https://h.test:8443/proxy/v1/")),
                String.valueOf(ClaudeApi.normalizeBaseUrl("https://h.test:8443/proxy/v1/")));
        check("normalize_rejects_non_https",
                ClaudeApi.normalizeBaseUrl("http://h.test") == null
                        && ClaudeApi.normalizeBaseUrl("h.test") == null
                        && ClaudeApi.normalizeBaseUrl("ftp://h.test") == null
                        && ClaudeApi.normalizeBaseUrl(null) == null,
                "a non-https URL was accepted");
        check("normalize_rejects_missing_host",
                ClaudeApi.normalizeBaseUrl("https://") == null
                        && ClaudeApi.normalizeBaseUrl("https:///v1") == null
                        && ClaudeApi.normalizeBaseUrl("https://:443") == null,
                "a URL without a host was accepted");
        check("normalize_rejects_whitespace",
                ClaudeApi.normalizeBaseUrl("https://h .test") == null
                        && ClaudeApi.normalizeBaseUrl(" https://h.test") == null
                        && ClaudeApi.normalizeBaseUrl("https://h.test\n") == null,
                "a URL with whitespace was accepted");
        check("normalize_rejects_query_and_fragment",
                ClaudeApi.normalizeBaseUrl("https://h.test/?a=1") == null
                        && ClaudeApi.normalizeBaseUrl("https://h.test#x") == null,
                "a URL with a query or fragment was accepted");
        check("normalize_rejects_userinfo_and_bad_ports",
                ClaudeApi.normalizeBaseUrl("https://h.test@evil.test") == null
                        && ClaudeApi.normalizeBaseUrl("https://user:pw@h.test") == null
                        && ClaudeApi.normalizeBaseUrl("https://h.test:abc") == null
                        && ClaudeApi.normalizeBaseUrl("https://h.test:123456") == null
                        && ClaudeApi.normalizeBaseUrl("https://h.test:65536") == null
                        && ClaudeApi.normalizeBaseUrl("https://h.test:0") == null
                        && "https://h.test:8443".equals(ClaudeApi.normalizeBaseUrl("https://h.test:8443")),
                "userinfo or a bad port was accepted, or a good port rejected: "
                        + ClaudeApi.normalizeBaseUrl("https://h.test:8443"));
    }

    private static void listing() {
        FakeTransport one = new FakeTransport().reply(200,
                "{\"data\":[{\"id\":\"m-a\",\"type\":\"model\"},{\"id\":\"m-b\"}],\"has_more\":false,\"last_id\":\"m-b\"}");
        ClaudeApi.Result r = new ClaudeApi(one).listModels(BASE, KEY);
        check("list_single_page_returns_all_ids",
                r.ok() && r.models.equals(Arrays.asList("m-a", "m-b")) && one.requests.size() == 1
                        && one.requests.get(0).method.equals("GET")
                        && one.requests.get(0).url.equals(BASE + "/v1/models?limit=1000"),
                describe(r) + " requests=" + one.requests.size());

        FakeTransport two = new FakeTransport()
                .reply(200, "{\"data\":[{\"id\":\"m-a\"}],\"has_more\":true,\"last_id\":\"m-a\"}")
                .reply(200, "{\"data\":[{\"id\":\"m-b\"}],\"has_more\":false}");
        r = new ClaudeApi(two).listModels(BASE, KEY);
        check("list_two_pages_fetched_with_after_id",
                r.ok() && r.models.equals(Arrays.asList("m-a", "m-b")) && two.requests.size() == 2
                        && two.requests.get(1).url.equals(BASE + "/v1/models?limit=1000&after_id=m-a"),
                describe(r) + " urls=" + urls(two));

        FakeTransport noCursor = new FakeTransport()
                .reply(200, "{\"data\":[{\"id\":\"m-a\"}],\"has_more\":true}");
        r = new ClaudeApi(noCursor).listModels(BASE, KEY);
        check("list_has_more_without_last_id_stops",
                r.ok() && r.models.equals(Arrays.asList("m-a")) && noCursor.requests.size() == 1,
                describe(r) + " requests=" + noCursor.requests.size());

        FakeTransport missing = new FakeTransport().reply(404, error("not_found_error", "Not found"));
        r = new ClaudeApi(missing).listModels(BASE, KEY);
        check("list_404_endpoint_does_not_list_models",
                !r.ok() && r.reason == ClaudeApi.Reason.MODELS_NOT_LISTED, describe(r));

        FakeTransport bad = new FakeTransport().reply(401, error("authentication_error", "Invalid proxy API key"));
        r = new ClaudeApi(bad).listModels(BASE, KEY);
        check("list_401_bad_key", !r.ok() && r.reason == ClaudeApi.Reason.BAD_KEY, describe(r));

        FakeTransport html = new FakeTransport().reply(200, "<html>hello</html>");
        r = new ClaudeApi(html).listModels(BASE, KEY);
        check("list_200_not_json_is_endpoint_error",
                !r.ok() && r.reason == ClaudeApi.Reason.ENDPOINT_ERROR, describe(r));

        FakeTransport echo = new FakeTransport().reply(200, "{\"data\":[{\"id\":\"m-a\"},{\"id\":\"" + KEY
                + "\"},{\"id\":\"x-" + KEY + "-y\"},{\"id\":\"m-b\"}],\"has_more\":false}");
        r = new ClaudeApi(echo).listModels(BASE, KEY);
        check("list_drops_ids_that_echo_the_key",
                r.ok() && r.models.equals(Arrays.asList("m-a", "m-b")), describe(r).replace(KEY, "<KEY>"));

        StringBuilder deep = new StringBuilder("{\"data\":");
        for (int k = 0; k < 10000; k++) {
            deep.append('[');
        }
        FakeTransport nested = new FakeTransport().reply(200, deep.toString());
        try {
            r = new ClaudeApi(nested).listModels(BASE, KEY);
            check("list_deeply_nested_json_is_endpoint_error",
                    !r.ok() && r.reason == ClaudeApi.Reason.ENDPOINT_ERROR, describe(r));
        } catch (Throwable t) {
            check("list_deeply_nested_json_is_endpoint_error", false, "threw " + t.getClass().getName());
        }

        FakeTransport none = new FakeTransport();
        r = new ClaudeApi(none).listModels(BASE, "");
        ClaudeApi.Result r2 = new ClaudeApi(none).listModels(BASE, null);
        check("list_missing_key_makes_no_request",
                r.reason == ClaudeApi.Reason.NOT_SET_UP && r2.reason == ClaudeApi.Reason.NOT_SET_UP
                        && none.requests.isEmpty(),
                describe(r) + " / " + describe(r2));

        r = new ClaudeApi(none).listModels("http://example.test", KEY);
        check("list_bad_base_url_makes_no_request",
                r.reason == ClaudeApi.Reason.BAD_BASE_URL && none.requests.isEmpty(), describe(r));
    }

    private static void connectionTest() {
        FakeTransport okT = new FakeTransport().reply(200,
                "{\"id\":\"msg_1\",\"type\":\"message\",\"content\":[{\"type\":\"text\",\"text\":\"p\"}]}");
        ClaudeApi.Result r = new ClaudeApi(okT).testConnection(BASE, KEY, MODEL);
        check("test_200_success", r.ok() && r.reason == null && okT.requests.size() == 1, describe(r));

        testMaps("test_401_bad_key", 401, error("authentication_error", "bad"), ClaudeApi.Reason.BAD_KEY);
        testMaps("test_403_no_permission", 403, error("permission_error", "no"), ClaudeApi.Reason.NO_PERMISSION);
        testMaps("test_402_billing", 402, error("billing_error", "pay"), ClaudeApi.Reason.BILLING);
        testMaps("test_400_spend_limit_is_billing", 400,
                error("invalid_request_error", "You have reached your specified workspace API spend limit."),
                ClaudeApi.Reason.BILLING);
        testMaps("test_400_other_is_invalid_request", 400,
                error("invalid_request_error", "max_tokens: field required"), ClaudeApi.Reason.INVALID_REQUEST);
        testMaps("test_404_not_found_error_unknown_model", 404,
                error("not_found_error", "model: claude-test-1"), ClaudeApi.Reason.UNKNOWN_MODEL);
        testMaps("test_429_rate_limited", 429, error("rate_limit_error", "slow"), ClaudeApi.Reason.RATE_LIMITED);
        testMaps("test_529_overloaded", 529, error("overloaded_error", "busy"), ClaudeApi.Reason.OVERLOADED);
        testMaps("test_500_endpoint_error", 500, error("api_error", "oops"), ClaudeApi.Reason.ENDPOINT_ERROR);
        testMaps("test_504_endpoint_error", 504, "<html>Gateway Timeout</html>", ClaudeApi.Reason.ENDPOINT_ERROR);
        testMaps("test_unmapped_status_endpoint_error", 418, "", ClaudeApi.Reason.ENDPOINT_ERROR);
        testMaps("test_non_json_error_body_falls_back_to_status", 429, "<html>Too Many</html>",
                ClaudeApi.Reason.RATE_LIMITED);
        testMaps("test_error_type_refines_unmapped_status", 503, error("overloaded_error", "busy"),
                ClaudeApi.Reason.OVERLOADED);

        FakeTransport redirect = new FakeTransport().reply(302, "").reply(200, "{}");
        r = new ClaudeApi(redirect).testConnection(BASE, KEY, MODEL);
        check("redirect_is_error_and_not_followed",
                !r.ok() && r.reason == ClaudeApi.Reason.ENDPOINT_ERROR && r.httpStatus == 302
                        && redirect.requests.size() == 1,
                describe(r) + " requests=" + redirect.requests.size());

        testFails("unknown_host_is_unreachable", new UnknownHostException("example.test"),
                ClaudeApi.Reason.UNREACHABLE);
        testFails("connection_refused_is_unreachable", new ConnectException("refused"),
                ClaudeApi.Reason.UNREACHABLE);
        testFails("timeout_is_unreachable", new SocketTimeoutException("read timed out"),
                ClaudeApi.Reason.UNREACHABLE);
        testFails("ssl_failure_is_tls_failed", new SSLHandshakeException("cert not yet valid"),
                ClaudeApi.Reason.TLS_FAILED);
    }

    private static void wire() {
        FakeTransport t = new FakeTransport()
                .reply(200, "{\"data\":[{\"id\":\"m-a\"}],\"has_more\":true,\"last_id\":\"m-a\"}")
                .reply(200, "{\"data\":[],\"has_more\":false}")
                .reply(200, "{}");
        ClaudeApi api = new ClaudeApi(t);
        api.listModels(BASE, KEY);
        api.testConnection(BASE, KEY, MODEL);
        boolean headers = t.requests.size() == 3;
        for (ClaudeApi.Request q : t.requests) {
            headers &= KEY.equals(q.headers.get("x-api-key"))
                    && ("Bearer " + KEY).equals(q.headers.get("Authorization"))
                    && "2023-06-01".equals(q.headers.get("anthropic-version"))
                    && !q.url.contains(KEY);
        }
        check("every_request_sends_auth_and_version_headers", headers, "requests=" + t.requests.size());

        ClaudeApi.Request post = t.requests.get(t.requests.size() - 1);
        String ct = post.headers.get("content-type");
        check("post_sends_json_content_type",
                post.method.equals("POST") && ct != null && ct.startsWith("application/json"),
                post.method + " content-type=" + ct);

        boolean paths = true;
        String seen = "";
        for (String base : new String[] {"https://example.test", "https://example.test/",
                "https://example.test/v1", "https://example.test/v1/"}) {
            FakeTransport p = new FakeTransport().reply(200, "{\"data\":[]}").reply(200, "{}");
            new ClaudeApi(p).listModels(base, KEY);
            new ClaudeApi(p).testConnection(base, KEY, MODEL);
            seen += urls(p) + " ";
            paths &= p.requests.size() == 2
                    && p.requests.get(0).url.equals("https://example.test/v1/models?limit=1000")
                    && p.requests.get(1).url.equals("https://example.test/v1/messages");
        }
        check("paths_are_v1_whatever_the_base_form", paths, seen);

        Object body = Json.parse(post.body);
        boolean ping = false;
        if (body instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) body;
            Object messages = m.get("messages");
            ping = MODEL.equals(m.get("model")) && Long.valueOf(1).equals(m.get("max_tokens"))
                    && messages instanceof List && ((List<?>) messages).size() == 1;
            if (ping) {
                Map<?, ?> msg = (Map<?, ?>) ((List<?>) messages).get(0);
                ping = "user".equals(msg.get("role")) && "ping".equals(msg.get("content"));
            }
        }
        check("request_body_is_one_token_ping", ping, post.body);

        String odd = "we\"ird\\model";
        FakeTransport e = new FakeTransport().reply(200, "{}");
        new ClaudeApi(e).testConnection(BASE, KEY, odd);
        Object parsed = e.requests.isEmpty() ? null : Json.parse(e.requests.get(0).body);
        check("request_body_escapes_the_model",
                parsed instanceof Map && odd.equals(((Map<?, ?>) parsed).get("model")),
                e.requests.isEmpty() ? "no request" : e.requests.get(0).body);
    }

    private static void noRequestWhenNotSetUp() {
        FakeTransport t = new FakeTransport();
        ClaudeApi api = new ClaudeApi(t);
        ClaudeApi.Result a = api.testConnection(BASE, "", MODEL);
        ClaudeApi.Result b = api.testConnection(BASE, null, MODEL);
        check("missing_key_makes_no_request",
                a.reason == ClaudeApi.Reason.NOT_SET_UP && b.reason == ClaudeApi.Reason.NOT_SET_UP
                        && t.requests.isEmpty(),
                describe(a) + " / " + describe(b));
        a = api.testConnection(BASE, KEY, "");
        b = api.testConnection(BASE, KEY, null);
        check("empty_model_makes_no_request",
                a.reason == ClaudeApi.Reason.NOT_SET_UP && b.reason == ClaudeApi.Reason.NOT_SET_UP
                        && t.requests.isEmpty(),
                describe(a) + " / " + describe(b));
        a = api.testConnection(BASE, KEY, "claude\n-x");
        b = api.testConnection(BASE, KEY, "claude\u0000");
        check("model_with_control_chars_makes_no_request",
                a.reason == ClaudeApi.Reason.BAD_MODEL_NAME && b.reason == ClaudeApi.Reason.BAD_MODEL_NAME
                        && t.requests.isEmpty(),
                describe(a) + " / " + describe(b));
        a = api.testConnection(BASE, "sk-abc\r\nX-Evil: 1", MODEL);
        b = api.listModels(BASE, "sk-é");
        check("key_with_control_chars_makes_no_request",
                a.reason == ClaudeApi.Reason.BAD_KEY_FORMAT && b.reason == ClaudeApi.Reason.BAD_KEY_FORMAT
                        && t.requests.isEmpty(),
                describe(a) + " / " + describe(b));
    }

    private static void secrecy() {
        // The endpoint echoes the key back in every error body we can think of.
        int[] statuses = {200, 302, 400, 401, 402, 403, 404, 418, 429, 500, 529};
        String leaky = error("authentication_error", "key " + KEY + " is not valid spend limit");
        String leak = "";
        for (int status : statuses) {
            for (int which = 0; which < 2; which++) {
                FakeTransport t = new FakeTransport().reply(status, status == 200 ? KEY : leaky);
                ClaudeApi api = new ClaudeApi(t);
                ClaudeApi.Result r = which == 0 ? api.listModels(BASE, KEY) : api.testConnection(BASE, KEY, MODEL);
                if (which == 1 && status == 200) {
                    continue; // a 200 connection test is plain success
                }
                String all = r.describe() + " " + r + " " + (r.reason == null ? "" : r.reason.text);
                if (all.contains(KEY) || all.contains("SECRETKEY")) {
                    leak += status + "/" + which + " ";
                }
            }
        }
        check("key_never_in_reason", leak.isEmpty(), "leaked for " + leak);

        boolean fixed = true;
        String bad = "";
        for (ClaudeApi.Reason reason : ClaudeApi.Reason.values()) {
            boolean ok = reason.text != null && !reason.text.isEmpty() && !reason.text.contains("%");
            fixed &= ok;
            if (!ok) {
                bad += reason + " ";
            }
        }
        ClaudeApi.Result tls = new ClaudeApi(new FakeTransport().fail(new SSLHandshakeException(KEY)))
                .testConnection(BASE, KEY, MODEL);
        fixed &= tls.reason.text.contains("clock") && tls.reason.text.contains("certificate");
        check("reasons_are_fixed_text", fixed, "bad=" + bad + " tls=" + tls.reason.text);
    }

    // ---- messages(): images + text in, parsed JSON out (explore plan U1) ----

    private static final ClaudeAccess ACCESS = ClaudeAccess.setUp(BASE, KEY, MODEL);

    private static Map<String, Object> schema() {
        Map<String, Object> props = new LinkedHashMap<String, Object>();
        props.put("interesting", Collections.singletonMap("type", "boolean"));
        Map<String, Object> s = new LinkedHashMap<String, Object>();
        s.put("type", "object");
        s.put("properties", props);
        s.put("required", Collections.singletonList("interesting"));
        s.put("additionalProperties", Boolean.FALSE);
        return s;
    }

    /** A Messages API success whose single text block is the given text. */
    private static String reply(String text, String stopReason) {
        Map<String, Object> block = new LinkedHashMap<String, Object>();
        block.put("type", "text");
        block.put("text", text);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("type", "message");
        body.put("role", "assistant");
        body.put("content", Collections.singletonList(block));
        body.put("stop_reason", stopReason);
        return Json.write(body);
    }

    private static String reply(String text) {
        return reply(text, "end_turn");
    }

    private static List<Map<String, Object>> threeFramesAndAQuestion() {
        List<Map<String, Object>> blocks = new ArrayList<Map<String, Object>>();
        for (int k = 1; k <= 3; k++) {
            blocks.add(ClaudeApi.textBlock("Image " + k + ":"));
            blocks.add(ClaudeApi.jpegBlock(new byte[] {(byte) 0xff, (byte) 0xd8, (byte) k}));
        }
        blocks.add(ClaudeApi.textBlock("What is most interesting?"));
        return blocks;
    }

    private static Map<?, ?> body(ClaudeApi.Request q) {
        Object v = Json.parse(q.body);
        return v instanceof Map ? (Map<?, ?>) v : Collections.emptyMap();
    }

    private static String describe(ClaudeApi.MessageResult r) {
        return r.ok() ? "ok json=" + r.json : r.reason + " status=" + r.httpStatus;
    }

    private static ClaudeApi.MessageResult ask(FakeTransport t, String answer) {
        t.reply(200, answer);
        return new ClaudeApi(t).messages(ACCESS, "sys", threeFramesAndAQuestion(), schema(), 10000);
    }

    private static void messages() {
        FakeTransport t = new FakeTransport();
        ClaudeApi.MessageResult r = ask(t, reply("{\"interesting\":true,\"label\":\"cat\"}"));
        Map<?, ?> b = t.requests.isEmpty() ? Collections.emptyMap() : body(t.requests.get(0));
        String order = "";
        Object msgs = b.get("messages");
        if (msgs instanceof List && ((List<?>) msgs).size() == 1) {
            Map<?, ?> msg = (Map<?, ?>) ((List<?>) msgs).get(0);
            if ("user".equals(msg.get("role")) && msg.get("content") instanceof List) {
                for (Object o : (List<?>) msg.get("content")) {
                    Map<?, ?> blk = (Map<?, ?>) o;
                    if ("image".equals(blk.get("type"))) {
                        Map<?, ?> src = (Map<?, ?>) blk.get("source");
                        order += "img(" + src.get("type") + "," + src.get("media_type") + "," + src.get("data") + ") ";
                    } else {
                        order += "txt(" + blk.get("text") + ") ";
                    }
                }
            }
        }
        String want = "txt(Image 1:) img(base64,image/jpeg,/9gB) txt(Image 2:) img(base64,image/jpeg,/9gC) "
                + "txt(Image 3:) img(base64,image/jpeg,/9gD) txt(What is most interesting?) ";
        check("messages_images_and_text_serialize_in_order",
                want.equals(order) && "sys".equals(b.get("system")) && MODEL.equals(b.get("model"))
                        && t.requests.size() == 1 && t.requests.get(0).url.equals(BASE + "/v1/messages")
                        && "POST".equals(t.requests.get(0).method),
                order);
        check("messages_json_reply_parses",
                r.ok() && Boolean.TRUE.equals(r.json.get("interesting")) && "cat".equals(r.json.get("label")),
                describe(r));
        Object oc = b.get("output_config");
        Object fmt = oc instanceof Map ? ((Map<?, ?>) oc).get("format") : null;
        check("messages_schema_sent_as_output_config",
                fmt instanceof Map && "json_schema".equals(((Map<?, ?>) fmt).get("type"))
                        && Json.write(schema()).equals(Json.write(((Map<?, ?>) fmt).get("schema"))),
                String.valueOf(oc));
        check("messages_sends_auth_version_and_json_headers",
                !t.requests.isEmpty() && KEY.equals(t.requests.get(0).headers.get("x-api-key"))
                        && "2023-06-01".equals(t.requests.get(0).headers.get("anthropic-version"))
                        && "application/json".equals(t.requests.get(0).headers.get("content-type")),
                "headers");
        check("messages_timeout_reaches_transport",
                !t.requests.isEmpty() && t.requests.get(0).readTimeoutMs == 10000,
                t.requests.isEmpty() ? "no request" : "readTimeoutMs=" + t.requests.get(0).readTimeoutMs);

        FakeTransport n = new FakeTransport().reply(200, reply("{\"ask_line\":\"Hi!\"}"));
        ClaudeApi.MessageResult nr = new ClaudeApi(n).messages(ACCESS, "sys",
                Collections.singletonList(ClaudeApi.textBlock("Ask a name.")), null, 10000);
        check("messages_without_schema_sends_no_output_config",
                nr.ok() && !body(n.requests.get(0)).containsKey("output_config")
                        && "Hi!".equals(nr.json.get("ask_line")),
                describe(nr) + " " + n.requests.get(0).body);

        FakeTransport d = new FakeTransport().reply(200, "{}").reply(200, "{\"data\":[]}");
        ClaudeApi dapi = new ClaudeApi(d);
        dapi.testConnection(BASE, KEY, MODEL);
        dapi.listModels(BASE, KEY);
        check("existing_calls_keep_the_default_timeout",
                d.requests.size() == 2 && d.requests.get(0).readTimeoutMs == 0 && d.requests.get(1).readTimeoutMs == 0,
                "requests=" + d.requests.size());

        // The 400 fallback: one retry without output_config, remembered afterwards.
        FakeTransport f = new FakeTransport()
                .reply(400, error("invalid_request_error", "output_config: Extra inputs are not permitted"))
                .reply(200, reply("{\"interesting\":false}"))
                .reply(200, reply("{\"interesting\":true}"));
        ClaudeApi fapi = new ClaudeApi(f);
        ClaudeApi.MessageResult f1 = fapi.messages(ACCESS, "sys", threeFramesAndAQuestion(), schema(), 10000);
        boolean retried = f.requests.size() == 2 && body(f.requests.get(0)).containsKey("output_config")
                && !body(f.requests.get(1)).containsKey("output_config");
        String sys2 = f.requests.size() == 2 ? String.valueOf(body(f.requests.get(1)).get("system")) : "";
        check("messages_output_config_400_retries_once_without_it",
                retried && f1.ok() && Boolean.FALSE.equals(f1.json.get("interesting"))
                        && sys2.startsWith("sys") && sys2.contains("\"additionalProperties\":false")
                        && f.requests.get(1).readTimeoutMs == 10000,
                describe(f1) + " requests=" + f.requests.size() + " system=" + sys2);
        ClaudeApi.MessageResult f2 = fapi.messages(ACCESS, "sys", threeFramesAndAQuestion(), schema(), 10000);
        check("messages_later_calls_skip_output_config",
                f2.ok() && f.requests.size() == 3 && !body(f.requests.get(2)).containsKey("output_config")
                        && String.valueOf(body(f.requests.get(2)).get("system")).contains("additionalProperties"),
                describe(f2) + " requests=" + f.requests.size());

        FakeTransport twice = new FakeTransport()
                .reply(400, error("invalid_request_error", "output_config: Extra inputs are not permitted"))
                .reply(400, error("invalid_request_error", "messages: something else"));
        ClaudeApi.MessageResult tw = new ClaudeApi(twice).messages(ACCESS, "sys", threeFramesAndAQuestion(), schema(), 10000);
        check("messages_fallback_retries_only_once",
                !tw.ok() && tw.reason == ClaudeApi.Reason.INVALID_REQUEST && twice.requests.size() == 2,
                describe(tw) + " requests=" + twice.requests.size());

        FakeTransport other = new FakeTransport().reply(400, error("invalid_request_error", "messages: bad image"));
        ClaudeApi.MessageResult o = new ClaudeApi(other).messages(ACCESS, "sys", threeFramesAndAQuestion(), schema(), 10000);
        check("messages_other_400_does_not_retry",
                !o.ok() && o.reason == ClaudeApi.Reason.INVALID_REQUEST && o.httpStatus == 400
                        && other.requests.size() == 1,
                describe(o) + " requests=" + other.requests.size());

        FakeTransport to = new FakeTransport().fail(new SocketTimeoutException("Read timed out"));
        ClaudeApi.MessageResult tr = new ClaudeApi(to).messages(ACCESS, "sys", threeFramesAndAQuestion(), schema(), 10000);
        check("messages_timeout_is_unreachable",
                !tr.ok() && tr.reason == ClaudeApi.Reason.UNREACHABLE && tr.httpStatus == 0 && to.requests.size() == 1,
                describe(tr));

        FakeTransport k = new FakeTransport().reply(401, error("authentication_error", "bad key " + KEY));
        ClaudeApi.MessageResult kr = new ClaudeApi(k).messages(ACCESS, "sys", threeFramesAndAQuestion(), schema(), 10000);
        check("messages_error_status_maps_like_the_connection_test",
                !kr.ok() && kr.reason == ClaudeApi.Reason.BAD_KEY && kr.httpStatus == 401
                        && !kr.describe().contains(KEY),
                describe(kr));

        String bad = "";
        for (String answer : new String[] {"{\"interesting\": tru", "[1,2,3]", "{\"a\":1} and {\"b\":2}"}) {
            ClaudeApi.MessageResult x = ask(new FakeTransport(), reply(answer));
            if (x.ok() || x.reason != ClaudeApi.Reason.BAD_REPLY) {
                bad += answer + "->" + describe(x) + " ";
            }
        }
        check("messages_invalid_json_is_bad_reply", bad.isEmpty(), bad);

        String badBody = "";
        for (String raw : new String[] {"not json at all", "{\"content\":\"nope\"}", "{\"content\":[]}", ""}) {
            ClaudeApi.MessageResult x = ask(new FakeTransport(), raw);
            if (x.ok() || x.reason != ClaudeApi.Reason.ENDPOINT_ERROR) {
                badBody += "[" + raw + "]->" + describe(x) + " ";
            }
        }
        check("messages_malformed_response_body_is_endpoint_error", badBody.isEmpty(), badBody);

        ClaudeApi.MessageResult fenced = ask(new FakeTransport(),
                reply("Here you go:\n```json\n{\"interesting\":true}\n```"));
        check("messages_prose_wrapped_json_parses",
                fenced.ok() && Boolean.TRUE.equals(fenced.json.get("interesting")), describe(fenced));

        ClaudeApi.MessageResult prose = ask(new FakeTransport(),
                reply("I'm not able to identify people from their faces."));
        check("messages_text_without_json_is_refused",
                !prose.ok() && prose.reason == ClaudeApi.Reason.REFUSED && prose.httpStatus == 200, describe(prose));

        ClaudeApi.MessageResult stop = ask(new FakeTransport(), reply("{\"interesting\":true}", "refusal"));
        ClaudeApi.MessageResult empty = ask(new FakeTransport(),
                "{\"type\":\"message\",\"content\":[],\"stop_reason\":\"refusal\"}");
        check("messages_refusal_stop_reason_is_refused",
                !stop.ok() && stop.reason == ClaudeApi.Reason.REFUSED
                        && !empty.ok() && empty.reason == ClaudeApi.Reason.REFUSED,
                describe(stop) + " / " + describe(empty));

        FakeTransport none = new FakeTransport();
        ClaudeApi napi = new ClaudeApi(none);
        ClaudeApi.MessageResult n1 = napi.messages(ClaudeAccess.notSetUp(), "sys", threeFramesAndAQuestion(), schema(), 10000);
        ClaudeApi.MessageResult n2 = napi.messages(null, "sys", threeFramesAndAQuestion(), schema(), 10000);
        ClaudeApi.MessageResult n3 = napi.messages(ClaudeAccess.setUp("http://x.test", KEY, MODEL), "sys",
                threeFramesAndAQuestion(), schema(), 10000);
        check("messages_not_set_up_makes_no_request",
                none.requests.isEmpty() && n1.reason == ClaudeApi.Reason.NOT_SET_UP
                        && n2.reason == ClaudeApi.Reason.NOT_SET_UP && n3.reason == ClaudeApi.Reason.BAD_BASE_URL,
                describe(n1) + " " + describe(n2) + " " + describe(n3) + " requests=" + none.requests.size());

        String big = ClaudeApi.jpegBlock(new byte[4000]).toString();
        check("jpeg_block_base64_has_no_newlines", !big.contains("\n") && !big.contains("\r"), "newline in base64");
    }

    private static String urls(FakeTransport t) {
        StringBuilder sb = new StringBuilder();
        for (ClaudeApi.Request q : t.requests) {
            sb.append(q.method).append(' ').append(q.url).append(' ');
        }
        return sb.toString();
    }

    // ---- the conversation overload (meeting plan U8, KTD9) ----

    private static List<Map<String, Object>> chat() {
        List<Map<String, Object>> m = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> opener = new ArrayList<Map<String, Object>>();
        opener.add(ClaudeApi.textBlock("Write the opener."));
        opener.add(ClaudeApi.jpegBlock(new byte[] {(byte) 0xFF, (byte) 0xD8, 1, 2, 3}));
        m.add(ClaudeApi.message("user", opener));
        m.add(ClaudeApi.message("assistant", "{\"line\":\"Hi Sam.\"}"));
        m.add(ClaudeApi.message("user", "not bad, just back from a long weekend"));
        return m;
    }

    private static String turnReply() {
        return reply("{\"line\":\"Camping?\",\"question_asked\":\"Camping?\",\"name_given\":\"\","
                + "\"ends_conversation\":false,\"deflected\":false,\"notes_update\":{}}", "end_turn");
    }

    private static void conversation() {
        FakeTransport t = new FakeTransport().reply(200, turnReply());
        ClaudeApi.MessageResult r = new ClaudeApi(t).conversation(ACCESS, "PREFIX", chat(), schema(), "low", 5000);
        Map<?, ?> b = t.requests.isEmpty() ? Collections.emptyMap() : body(t.requests.get(0));
        Object msgs = b.get("messages");
        String order = "";
        boolean roles = false;
        if (msgs instanceof List && ((List<?>) msgs).size() == 3) {
            List<?> l = (List<?>) msgs;
            roles = "user".equals(((Map<?, ?>) l.get(0)).get("role")) && "assistant".equals(((Map<?, ?>) l.get(1)).get("role"))
                    && "user".equals(((Map<?, ?>) l.get(2)).get("role"))
                    && "not bad, just back from a long weekend".equals(((Map<?, ?>) l.get(2)).get("content"))
                    && ((Map<?, ?>) l.get(0)).get("content") instanceof List
                    && ((List<?>) ((Map<?, ?>) l.get(0)).get("content")).size() == 2;
            order = String.valueOf(l);
        }
        check("conversation_sends_the_message_list_in_order_with_its_roles",
                r.ok() && "Camping?".equals(r.json.get("line")) && roles && t.requests.size() == 1
                        && "PREFIX".equals(b.get("system")) && t.requests.get(0).url.equals(BASE + "/v1/messages"),
                describe(r) + " " + order);
        Object cc = b.get("cache_control");
        check("conversation_sets_the_top_level_cache_breakpoint_and_max_tokens_400",
                cc instanceof Map && "ephemeral".equals(((Map<?, ?>) cc).get("type"))
                        && Long.valueOf(400).equals(b.get("max_tokens")) && !b.containsKey("stream") && t.requests.get(0).readTimeoutMs == 5000,
                "cache_control=" + cc + " max_tokens=" + b.get("max_tokens") + " timeout=" + t.requests.get(0).readTimeoutMs);
        Object oc = b.get("output_config");
        Object fmt = oc instanceof Map ? ((Map<?, ?>) oc).get("format") : null;
        check("conversation_sends_effort_beside_the_json_schema_format",
                fmt instanceof Map && "json_schema".equals(((Map<?, ?>) fmt).get("type"))
                        && Json.write(schema()).equals(Json.write(((Map<?, ?>) fmt).get("schema")))
                        && oc instanceof Map && "low".equals(((Map<?, ?>) oc).get("effort")),
                String.valueOf(oc));
        // The effort gate: a 400 naming effort retries once without it, keeping the format, and is remembered.
        FakeTransport e = new FakeTransport()
                .reply(400, error("invalid_request_error", "output_config.effort: Extra inputs are not permitted"))
                .reply(200, turnReply())
                .reply(200, turnReply());
        ClaudeApi eapi = new ClaudeApi(e);
        ClaudeApi.MessageResult e1 = eapi.conversation(ACCESS, "PREFIX", chat(), schema(), "low", 5000);
        Map<?, ?> second = e.requests.size() >= 2 ? body(e.requests.get(1)) : Collections.emptyMap();
        Object oc2 = second.get("output_config");
        boolean keptFormat = oc2 instanceof Map && ((Map<?, ?>) oc2).get("format") instanceof Map
                && !((Map<?, ?>) oc2).containsKey("effort");
        check("conversation_effort_400_retries_once_without_effort_keeping_the_schema_format",
                e1.ok() && e.requests.size() == 2 && keptFormat && !String.valueOf(second.get("system")).contains("JSON schema")
                        && e.requests.get(1).readTimeoutMs == 5000,
                describe(e1) + " requests=" + e.requests.size() + " second=" + oc2);
        ClaudeApi.MessageResult e2 = eapi.conversation(ACCESS, "PREFIX", chat(), schema(), "low", 5000);
        Map<?, ?> third = e.requests.size() >= 3 ? body(e.requests.get(2)) : Collections.emptyMap();
        Object oc3 = third.get("output_config");
        check("conversation_later_calls_send_no_effort_and_keep_the_format",
                e2.ok() && e.requests.size() == 3 && oc3 instanceof Map && !((Map<?, ?>) oc3).containsKey("effort")
                        && ((Map<?, ?>) oc3).get("format") instanceof Map,
                describe(e2) + " requests=" + e.requests.size() + " third=" + oc3);
        // The schema gate alone: a 400 naming output_config without effort moves the schema into the prompt, effort kept.
        FakeTransport f = new FakeTransport()
                .reply(400, error("invalid_request_error", "output_config: Extra inputs are not permitted"))
                .reply(200, turnReply());
        ClaudeApi.MessageResult f1 = new ClaudeApi(f).conversation(ACCESS, "PREFIX", chat(), schema(), "low", 5000);
        Map<?, ?> f2 = f.requests.size() >= 2 ? body(f.requests.get(1)) : Collections.emptyMap();
        Object ocf = f2.get("output_config");
        check("conversation_output_config_400_without_effort_moves_the_schema_into_the_prompt_and_keeps_effort",
                f1.ok() && f.requests.size() == 2 && String.valueOf(f2.get("system")).startsWith("PREFIX")
                        && String.valueOf(f2.get("system")).contains("\"additionalProperties\":false")
                        && ocf instanceof Map && "low".equals(((Map<?, ?>) ocf).get("effort"))
                        && !((Map<?, ?>) ocf).containsKey("format"),
                describe(f1) + " requests=" + f.requests.size() + " second=" + ocf + " system=" + f2.get("system"));
        // Both gates in one call: the effort 400 first, then the format 400: three requests, each gate once.
        FakeTransport g = new FakeTransport()
                .reply(400, error("invalid_request_error", "output_config.effort: Extra inputs are not permitted"))
                .reply(400, error("invalid_request_error", "output_config: Extra inputs are not permitted"))
                .reply(200, turnReply());
        ClaudeApi.MessageResult g1 = new ClaudeApi(g).conversation(ACCESS, "PREFIX", chat(), schema(), "low", 5000);
        Map<?, ?> g3 = g.requests.size() >= 3 ? body(g.requests.get(2)) : Collections.emptyMap();
        check("conversation_effort_400_then_output_config_400_fires_each_gate_once",
                g1.ok() && g.requests.size() == 3 && !g3.containsKey("output_config")
                        && String.valueOf(g3.get("system")).contains("additionalProperties"),
                describe(g1) + " requests=" + g.requests.size() + " third=" + g3.get("output_config"));
        // A 400 naming both is the effort gate only; a second, unrelated 400 does not retry.
        FakeTransport h = new FakeTransport()
                .reply(400, error("invalid_request_error", "output_config.effort: not permitted"))
                .reply(400, error("invalid_request_error", "messages: something else"));
        ClaudeApi.MessageResult h1 = new ClaudeApi(h).conversation(ACCESS, "PREFIX", chat(), schema(), "low", 5000);
        check("conversation_gates_retry_at_most_once_each_and_another_400_is_invalid_request",
                !h1.ok() && h1.reason == ClaudeApi.Reason.INVALID_REQUEST && h.requests.size() == 2,
                describe(h1) + " requests=" + h.requests.size());
        // Without effort, no output_config.effort is sent and no gate can fire on it.
        FakeTransport n = new FakeTransport().reply(200, turnReply());
        new ClaudeApi(n).conversation(ACCESS, "PREFIX", chat(), schema(), null, 3000);
        Object ocn = body(n.requests.get(0)).get("output_config");
        check("conversation_without_effort_sends_only_the_format_and_the_retry_budget",
                ocn instanceof Map && !((Map<?, ?>) ocn).containsKey("effort") && n.requests.get(0).readTimeoutMs == 3000,
                String.valueOf(ocn));
        // Budgets: a timeout is UNREACHABLE, overload and rate limits map as before, a refusal is REFUSED.
        ClaudeApi.MessageResult to = new ClaudeApi(new FakeTransport().fail(new java.net.SocketTimeoutException("read")))
                .conversation(ACCESS, "PREFIX", chat(), schema(), "low", 5000);
        ClaudeApi.MessageResult ov = new ClaudeApi(new FakeTransport().reply(529, error("overloaded_error", "Overloaded")))
                .conversation(ACCESS, "PREFIX", chat(), schema(), "low", 5000);
        ClaudeApi.MessageResult rl = new ClaudeApi(new FakeTransport().reply(429, error("rate_limit_error", "slow down")))
                .conversation(ACCESS, "PREFIX", chat(), schema(), "low", 5000);
        ClaudeApi.MessageResult rf = new ClaudeApi(new FakeTransport().reply(200, reply("I'd rather not.", "refusal")))
                .conversation(ACCESS, "PREFIX", chat(), schema(), "low", 5000);
        check("conversation_timeout_overload_rate_limit_and_refusal_map_to_their_reasons",
                to.reason == ClaudeApi.Reason.UNREACHABLE && ov.reason == ClaudeApi.Reason.OVERLOADED
                        && rl.reason == ClaudeApi.Reason.RATE_LIMITED && rf.reason == ClaudeApi.Reason.REFUSED,
                to.reason + " " + ov.reason + " " + rl.reason + " " + rf.reason);
        // The error output never carries the messages, the prefix or the key.
        String shown = to.describe() + ov.describe() + rl.describe() + rf.describe() + h1.describe() + to + ov;
        check("conversation_error_output_carries_no_transcript_prefix_or_key",
                !shown.contains("long weekend") && !shown.contains("PREFIX") && !shown.contains("Hi Sam")
                        && !shown.contains(KEY) && !shown.contains("something else"),
                shown);
    }

    // ---- the streamed conversation (robot 2026-10-02: speak as soon as the line is known) ----

    /** A transport that streams: an outcome is a Response (sent whole, as an error is) or SSE lines for a 200. */
    private static final class FakeStreamingTransport extends FakeTransport implements ClaudeApi.StreamingTransport {
        int fed;

        FakeStreamingTransport sse(List<String> lines) {
            outcomes.add(lines);
            return this;
        }

        @Override
        public ClaudeApi.Response stream(ClaudeApi.Request request, ClaudeApi.LineSink sink) throws IOException {
            requests.add(request);
            Object next = outcomes.poll();
            if (next == null) {
                throw new IllegalStateException("unexpected extra request to " + request.url);
            }
            if (next instanceof IOException) {
                throw (IOException) next;
            }
            if (next instanceof ClaudeApi.Response) {
                return (ClaudeApi.Response) next;
            }
            for (Object line : (List<?>) next) {
                fed++;
                sink.line((String) line);
            }
            return new ClaudeApi.Response(200, "");
        }
    }

    /** The early-fields listener: what it was given, how often, and how many SSE lines had arrived by then. */
    private static final class Early implements ClaudeApi.EarlyFields {
        final FakeStreamingTransport t;
        Map<String, String> fields;
        int calls;
        int atLine = -1;

        Early(FakeStreamingTransport t) {
            this.t = t;
        }

        @Override
        public void complete(Map<String, String> f) {
            calls++;
            fields = f;
            atLine = t == null ? -1 : t.fed;
        }
    }

    private static final List<String> EARLY = Arrays.asList("line", "question_asked", "name_given");

    /** A Messages stream whose text arrives in these deltas, ending with this stop reason (null: no message_delta). */
    private static List<String> sse(String stopReason, String... deltas) {
        List<String> l = new ArrayList<String>();
        l.add("event: message_start");
        l.add("data: {\"type\":\"message_start\",\"message\":{\"id\":\"m\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"content\":[],\"stop_reason\":null,\"usage\":{\"input_tokens\":9,\"output_tokens\":1}}}");
        l.add("");
        l.add("event: content_block_start");
        l.add("data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}");
        l.add("");
        l.add("event: ping");
        l.add("data: {\"type\":\"ping\"}");
        l.add("");
        for (String d : deltas) {
            Map<String, Object> delta = new LinkedHashMap<String, Object>();
            delta.put("type", "text_delta");
            delta.put("text", d);
            Map<String, Object> ev = new LinkedHashMap<String, Object>();
            ev.put("type", "content_block_delta");
            ev.put("index", 0);
            ev.put("delta", delta);
            l.add("event: content_block_delta");
            l.add("data: " + Json.write(ev));
            l.add("");
        }
        l.add("event: content_block_stop");
        l.add("data: {\"type\":\"content_block_stop\",\"index\":0}");
        l.add("");
        if (stopReason != null) {
            l.add("event: message_delta");
            l.add("data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"" + stopReason
                    + "\"},\"usage\":{\"output_tokens\":40}}");
            l.add("");
        }
        l.add("event: message_stop");
        l.add("data: {\"type\":\"message_stop\"}");
        l.add("");
        return l;
    }

    private static final String[] TURN_DELTAS = {
            "{\"line\":\"Camping? ", "Nice.\",\"question_asked\":\"Camp", "ing?\"", ",\"name_given\":\"Sam\"",
            ",\"ends_conversation\":false,\"deflected\":false,", "\"notes_update\":{\"topics\":[\"camping\"]}}"};

    /** The SSE line index of the delta that carries the n-th text delta (1-based). */
    private static int deltaLine(int n) {
        return 9 + 3 * n - 1;
    }

    private static void streaming() {
        FakeStreamingTransport t = new FakeStreamingTransport();
        t.sse(sse("end_turn", TURN_DELTAS));
        Early e = new Early(t);
        ClaudeApi.MessageResult r = new ClaudeApi(t).conversation(ACCESS, "PREFIX", chat(), schema(), null, 5000,
                EARLY, e);
        Map<?, ?> b = t.requests.isEmpty() ? Collections.emptyMap() : body(t.requests.get(0));
        check("conversation_streamed_asks_for_a_stream",
                Boolean.TRUE.equals(b.get("stream")) && Long.valueOf(400).equals(b.get("max_tokens")),
                "stream=" + b.get("stream") + " max_tokens=" + b.get("max_tokens"));
        boolean early = e.calls == 1 && e.fields != null && "Camping? Nice.".equals(e.fields.get("line"))
                && "Camping?".equals(e.fields.get("question_asked")) && "Sam".equals(e.fields.get("name_given"));
        check("conversation_streamed_reports_the_line_question_and_name_before_the_tail",
                early && e.atLine == deltaLine(4) && e.atLine < t.fed,
                "calls=" + e.calls + " fields=" + e.fields + " atLine=" + e.atLine + " want " + deltaLine(4)
                        + " of " + t.fed);
        Object notes = r.ok() ? r.json.get("notes_update") : null;
        check("conversation_streamed_result_is_the_whole_reply",
                r.ok() && "Camping? Nice.".equals(r.json.get("line")) && notes instanceof Map
                        && String.valueOf(notes).contains("camping") && Boolean.FALSE.equals(r.json.get("deflected")),
                describe(r));
        // An escape split across two deltas, a colon and a brace inside the line, and an empty name.
        FakeStreamingTransport s = new FakeStreamingTransport();
        s.sse(sse("end_turn", "{ \"line\" : \"He said \\", "\"hi: {there}\\\" ", "\\u00e9\" , \"question_asked\":\"\",",
                "\"name_given\":\"\"", ",\"ends_conversation\":true,\"deflected\":false,\"notes_update\":{}}"));
        Early se = new Early(s);
        ClaudeApi.MessageResult sr = new ClaudeApi(s).conversation(ACCESS, "PREFIX", chat(), schema(), null, 5000,
                EARLY, se);
        check("conversation_streamed_fields_survive_escapes_split_across_deltas",
                sr.ok() && se.calls == 1 && "He said \"hi: {there}\" \u00e9".equals(se.fields.get("line"))
                        && "".equals(se.fields.get("name_given")) && se.atLine == deltaLine(4),
                describe(sr) + " fields=" + se.fields + " atLine=" + se.atLine);
        // The gates still work: the 400 comes back whole, before any stream; the retry streams.
        FakeStreamingTransport g = new FakeStreamingTransport();
        g.reply(400, error("invalid_request_error", "output_config.effort: Extra inputs are not permitted"));
        g.sse(sse("end_turn", TURN_DELTAS));
        Early ge = new Early(g);
        ClaudeApi.MessageResult gr = new ClaudeApi(g).conversation(ACCESS, "PREFIX", chat(), schema(), "low", 5000,
                EARLY, ge);
        Object oc2 = g.requests.size() >= 2 ? body(g.requests.get(1)).get("output_config") : null;
        check("conversation_streamed_400_gates_still_retry_once",
                gr.ok() && g.requests.size() == 2 && ge.calls == 1 && oc2 instanceof Map
                        && !((Map<?, ?>) oc2).containsKey("effort"),
                describe(gr) + " requests=" + g.requests.size() + " calls=" + ge.calls);
        // A stream that breaks with an error event, before the fields are complete.
        List<String> broken = sse(null, "{\"line\":\"Camp");
        broken.add(broken.size() - 3, "event: error");
        broken.add(broken.size() - 3, "data: {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}");
        FakeStreamingTransport o = new FakeStreamingTransport().sse(broken);
        Early oe = new Early(o);
        ClaudeApi.MessageResult or = new ClaudeApi(o).conversation(ACCESS, "PREFIX", chat(), schema(), null, 5000,
                EARLY, oe);
        FakeStreamingTransport rf = new FakeStreamingTransport().sse(sse("refusal", "I'd rather not."));
        ClaudeApi.MessageResult rr = new ClaudeApi(rf).conversation(ACCESS, "PREFIX", chat(), schema(), null, 5000,
                EARLY, new Early(rf));
        check("conversation_streamed_error_event_and_refusal_map_to_their_reasons",
                or.reason == ClaudeApi.Reason.OVERLOADED && oe.calls == 0 && rr.reason == ClaudeApi.Reason.REFUSED,
                or.reason + " calls=" + oe.calls + " " + rr.reason);
        // A transport that cannot stream: the whole reply, then the fields once, from it.
        FakeTransport plain = new FakeTransport().reply(200, turnReply());
        Early pe = new Early(null);
        ClaudeApi.MessageResult pr = new ClaudeApi(plain).conversation(ACCESS, "PREFIX", chat(), schema(), null, 5000,
                EARLY, pe);
        check("conversation_without_a_streaming_transport_reports_the_fields_from_the_whole_reply",
                pr.ok() && pe.calls == 1 && "Camping?".equals(pe.fields.get("line"))
                        && !body(plain.requests.get(0)).containsKey("stream"),
                describe(pr) + " calls=" + pe.calls + " fields=" + pe.fields);
        // Haiku refuses effort (robot 2026-10-02: a 400 on every first turn): it is never sent to it.
        FakeTransport hk = new FakeTransport().reply(200, turnReply());
        ClaudeApi.MessageResult hr = new ClaudeApi(hk).conversation(
                ClaudeAccess.setUp(BASE, KEY, "claude-haiku-4-5-20251001"), "PREFIX", chat(), schema(), "low", 5000);
        Object hoc = body(hk.requests.get(0)).get("output_config");
        check("conversation_never_sends_effort_to_a_haiku_model",
                hr.ok() && hk.requests.size() == 1 && hoc instanceof Map && !((Map<?, ?>) hoc).containsKey("effort")
                        && ((Map<?, ?>) hoc).get("format") instanceof Map,
                describe(hr) + " output_config=" + hoc);
        // The partial-JSON scanner on its own: only closed top-level strings count.
        Map<String, String> part = ClaudeApi.completeStringFields(
                "```json\n{\"a\":\"x\",\"n\":{\"line\":\"inner\"},\"b\":[1,\"]\"],\"c\":tr");
        Map<String, String> open = ClaudeApi.completeStringFields("{\"line\":\"still going");
        check("partial_json_scanner_reads_only_closed_top_level_strings",
                "x".equals(part.get("a")) && !part.containsKey("line") && !part.containsKey("c") && part.size() == 1
                        && open.isEmpty(),
                part + " " + open);
    }

    // ---- keep-warm (robot 2026-10-02: a cold connection costs ~0.4 s on the first turn) ----

    private static void keepWarm() {
        FakeTransport t = new FakeTransport().reply(200, "{\"data\":[{\"id\":\"m\"}],\"has_more\":true}");
        boolean ok = new ClaudeApi(t).keepWarm(ACCESS, 4000);
        ClaudeApi.Request q = t.requests.isEmpty() ? null : t.requests.get(0);
        check("keep_warm_is_one_tokenless_models_page_of_one",
                ok && t.requests.size() == 1 && q != null && "GET".equals(q.method)
                        && q.url.equals(BASE + "/v1/models?limit=1") && q.body == null && q.readTimeoutMs == 4000
                        && KEY.equals(q.headers.get("x-api-key")),
                "ok=" + ok + " " + (q == null ? "no request" : q.method + " " + q.url + " timeout=" + q.readTimeoutMs));
        boolean failed = new ClaudeApi(new FakeTransport().reply(500, "oops")).keepWarm(ACCESS, 4000);
        boolean thrown = new ClaudeApi(new FakeTransport().fail(new java.net.SocketTimeoutException("read")))
                .keepWarm(ACCESS, 4000);
        FakeTransport none = new FakeTransport();
        boolean notSetUp = new ClaudeApi(none).keepWarm(ClaudeAccess.notSetUp(), 4000);
        check("keep_warm_failures_are_false_and_never_throw_and_unset_sends_nothing",
                !failed && !thrown && !notSetUp && none.requests.isEmpty(),
                failed + " " + thrown + " " + notSetUp + " requests=" + none.requests.size());
    }

    // ---- rate limits (robot 2026-10-01: a 429 retried 0.6 s later) ----

    private static ClaudeApi.MessageResult ask(int status, String body, String retryAfter) {
        return new ClaudeApi(new FakeTransport().reply(status, body, retryAfter))
                .messages(ACCESS, null, Collections.singletonList(ClaudeApi.textBlock("hi")), null, 5000);
    }

    private static void rateLimits() {
        // retry-after (seconds) reaches both result kinds; absent or unreadable is -1.
        ClaudeApi.MessageResult m20 = ask(429, error("rate_limit_error", "slow"), "20");
        ClaudeApi.Result r20 = new ClaudeApi(new FakeTransport().reply(429, error("rate_limit_error", "slow"), " 20 "))
                .testConnection(BASE, KEY, MODEL);
        check("retry_after_seconds_reaches_both_results",
                m20.reason == ClaudeApi.Reason.RATE_LIMITED && m20.retryAfterMs == 20000 && r20.retryAfterMs == 20000,
                m20.retryAfterMs + " " + r20.retryAfterMs);
        ClaudeApi.MessageResult none = ask(429, error("rate_limit_error", "slow"), null);
        ClaudeApi.MessageResult date = ask(529, error("overloaded_error", "busy"), "Wed, 21 Oct 2026 07:28:00 GMT");
        ClaudeApi.MessageResult neg = ask(429, "", "-5");
        ClaudeApi.MessageResult ok = ask(200, reply("{\"a\":1}"), "20");
        check("retry_after_missing_or_unreadable_is_minus_one",
                none.retryAfterMs == -1 && date.retryAfterMs == -1 && neg.retryAfterMs == -1 && ok.retryAfterMs == -1
                        && date.reason == ClaudeApi.Reason.OVERLOADED,
                none.retryAfterMs + " " + date.retryAfterMs + " " + neg.retryAfterMs + " " + ok.retryAfterMs);
        // The client itself never retries a 429 or a 529: one request each.
        FakeTransport t429 = new FakeTransport().reply(429, error("rate_limit_error", "slow"), "1");
        new ClaudeApi(t429).conversation(ACCESS, "PREFIX", chat(), schema(), "low", 5000);
        FakeTransport t529 = new FakeTransport().reply(529, error("overloaded_error", "busy"));
        new ClaudeApi(t529).messages(ACCESS, null, Collections.singletonList(ClaudeApi.textBlock("hi")), schema(), 5000);
        check("rate_limited_and_overloaded_are_never_retried_by_the_client",
                t429.requests.size() == 1 && t529.requests.size() == 1,
                t429.requests.size() + " " + t529.requests.size());

        // The back-off clock: retry-after when given.
        ClaudeApi.Backoff b = new ClaudeApi.Backoff();
        long started = b.record(m20, 1000);
        check("backoff_honours_retry_after",
                started == 20000 && b.remainingMs(1000) == 20000 && b.remainingMs(20999) == 1
                        && b.remainingMs(21000) == 0 && b.remainingMs(50000) == 0,
                started + " " + b.remainingMs(20999) + " " + b.remainingMs(21000));
        // Without it: 30 s, doubling to a 5 min cap, and back to 30 s after a success.
        ClaudeApi.Backoff e = new ClaudeApi.Backoff();
        long now = 0;
        List<Long> got = new ArrayList<Long>();
        for (int i = 0; i < 6; i++) {
            long p = e.record(none, now);
            got.add(p);
            now += p;
        }
        long afterOk = e.record(ok, now);
        long again = e.record(none, now);
        check("backoff_without_retry_after_is_a_fixed_15_s_and_never_doubles",
                got.equals(Arrays.asList(15000L, 15000L, 15000L, 15000L, 15000L, 15000L))
                        && afterOk == 0 && again == 15000,
                got + " afterOk=" + afterOk + " again=" + again);
        // A 529 pauses too; other failures don't.
        ClaudeApi.Backoff o = new ClaudeApi.Backoff();
        long p529 = o.record(date, 0);
        ClaudeApi.Backoff x = new ClaudeApi.Backoff();
        long p500 = x.record(ask(500, error("api_error", "boom"), "20"), 0);
        long pTimeout = x.record(new ClaudeApi(new FakeTransport().fail(new SocketTimeoutException("read")))
                .messages(ACCESS, null, Collections.singletonList(ClaudeApi.textBlock("hi")), null, 5000), 0);
        check("backoff_pauses_on_529_but_not_on_other_failures",
                p529 == 15000 && o.remainingMs(0) == 15000 && p500 == 0 && pTimeout == 0 && x.remainingMs(0) == 0,
                p529 + " " + p500 + " " + pTimeout);
        // A second 429 inside a running pause (a request already in flight) starts nothing new.
        ClaudeApi.Backoff d = new ClaudeApi.Backoff();
        d.record(none, 0);
        long inside = d.record(none, 5000);
        long left = d.remainingMs(5000);
        long next = d.record(none, 30000);
        check("backoff_a_429_inside_a_pause_starts_no_new_pause",
                inside == 0 && left == 10000 && next == 15000,
                inside + " " + left + " next=" + next);
        // The stand-in result for a request the pause kept from being sent: rate limited, no status, not a new pause.
        ClaudeApi.MessageResult held = ClaudeApi.MessageResult.paused();
        ClaudeApi.Backoff q = new ClaudeApi.Backoff();
        check("a_paused_result_is_rate_limited_without_a_status_and_starts_no_pause",
                !held.ok() && held.reason == ClaudeApi.Reason.RATE_LIMITED && held.httpStatus == 0
                        && q.record(held, 0) == 0 && q.remainingMs(0) == 0,
                held.describe());
    }
}
