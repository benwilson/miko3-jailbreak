package com.miko3.shared;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
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
    private static final class FakeTransport implements ClaudeApi.Transport {
        final ArrayDeque<Object> outcomes = new ArrayDeque<Object>();
        final List<ClaudeApi.Request> requests = new ArrayList<ClaudeApi.Request>();

        FakeTransport reply(int status, String body) {
            outcomes.add(new ClaudeApi.Response(status, body));
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

    private static String urls(FakeTransport t) {
        StringBuilder sb = new StringBuilder();
        for (ClaudeApi.Request q : t.requests) {
            sb.append(q.method).append(' ').append(q.url).append(' ');
        }
        return sb.toString();
    }
}
