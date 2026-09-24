package com.miko3.launcher;

import com.miko3.shared.ClaudeApi;
import com.miko3.shared.HttpRequest;
import com.miko3.shared.HttpResponse;
import com.miko3.shared.PageToken;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Host-JVM checks for the launcher's Settings page (settings plan U4, R1-R10,
 * R14, R16, AE1-AE3, AE6), driven by scripts/tests/test_launcher_settings.py.
 * Drives SettingsPage.handle() with in-memory requests, a real ClaudeSettings
 * over an in-memory store, and a ClaudeApi over a fake transport, so no
 * network is touched. Lives in the launcher's package to reach the
 * package-private classes; compiles against launcher/src and shared/src only.
 *
 * Prints one "PASS <name>" or "FAIL <name>: <detail>" line per scenario.
 */
public final class SettingsPageHarness {
    static final String KEY_A = "sk-ant-api03-AAAAAAAAAAAAAAAAAAAAAAAAAAAA-wxyz";
    static final String KEY_B = "sk-ant-api03-BBBBBBBBBBBBBBBBBBBBBBBBBBBB-1234";
    static final String MODELS_JSON =
            "{\"data\":[{\"id\":\"claude-a\"},{\"id\":\"claude-b\"}],\"has_more\":false}";

    static final class MemStore implements ClaudeSettings.Store {
        final Map<String, String> values = new HashMap<String, String>();

        @Override
        public String getString(String key, String def) {
            String v = values.get(key);
            return v == null ? def : v;
        }

        @Override
        public void putStrings(Map<String, String> entries) {
            values.putAll(entries);
        }
    }

    /** Answers every request with the queued status/body; records what was sent. */
    static final class FakeTransport implements ClaudeApi.Transport {
        final List<ClaudeApi.Request> sent = new ArrayList<ClaudeApi.Request>();
        int status = 200;
        String body = MODELS_JSON;
        IOException fail;

        @Override
        public ClaudeApi.Response send(ClaudeApi.Request request) throws IOException {
            sent.add(request);
            if (fail != null) {
                throw fail;
            }
            return new ClaudeApi.Response(status, body);
        }
    }

    static final class Fixture {
        final MemStore store = new MemStore();
        final ClaudeSettings settings = new ClaudeSettings(store, new ClaudeSettings.Clock() {
            @Override
            public long nowMillis() {
                return 1_700_000_000_000L;
            }
        });
        final PageToken token = new PageToken(4);
        final FakeTransport transport = new FakeTransport();
        final ClaudeApi api = new ClaudeApi(transport);
    }

    static final class Resp {
        String head;
        String body;

        int code() {
            return Integer.parseInt(head.split(" ")[1]);
        }

        String location() {
            for (String line : head.split("\r\n")) {
                if (line.toLowerCase().startsWith("location:")) {
                    return line.substring(9).trim();
                }
            }
            return null;
        }

        String status() throws Exception {
            String loc = location();
            if (loc == null || loc.indexOf("status=") < 0) {
                return null;
            }
            return URLDecoder.decode(loc.substring(loc.indexOf("status=") + 7), "UTF-8");
        }
    }

    static Resp request(Fixture f, String method, String path, String query, String form) throws Exception {
        Map<String, String> headers = new HashMap<String, String>();
        headers.put("host", "192.168.19.74:8443");
        byte[] body = form == null ? new byte[0] : form.getBytes(StandardCharsets.UTF_8);
        HttpRequest req = new HttpRequest(method, path, HttpRequest.parseQuery(query), headers,
                new ByteArrayInputStream(body));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        SettingsPage.handle(req, new HttpResponse(out), f.token, f.settings, f.api);
        String raw = new String(out.toByteArray(), StandardCharsets.UTF_8);
        int split = raw.indexOf("\r\n\r\n");
        Resp r = new Resp();
        r.head = split < 0 ? raw : raw.substring(0, split);
        r.body = split < 0 ? "" : raw.substring(split + 4);
        return r;
    }

    static String get(Fixture f) throws Exception {
        return request(f, "GET", "/settings", "", null).body;
    }

    static String get(Fixture f, String query) throws Exception {
        return request(f, "GET", "/settings", query, null).body;
    }

    /** A fresh token, from a real GET of the page. */
    static String token(Fixture f) throws Exception {
        Matcher m = Pattern.compile("name=\"t\" value=\"([0-9a-f]+)\"").matcher(get(f));
        return m.find() ? m.group(1) : "";
    }

    static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
    }

    static Resp save(Fixture f, String url, String key, String model) throws Exception {
        return request(f, "POST", "/settings/claude", "", "t=" + token(f) + "&base_url=" + enc(url)
                + "&key=" + enc(key) + "&model=" + enc(model));
    }

    static Resp action(Fixture f, String path) throws Exception {
        return request(f, "POST", path, "", "t=" + token(f));
    }

    /** The <form> whose action is the given path, or "" when there is none. */
    static String form(String html, String action) {
        Matcher m = Pattern.compile("<form[^>]*action=\"" + Pattern.quote(action) + "\"[^>]*>(.*?)</form>",
                Pattern.DOTALL).matcher(html);
        return m.find() ? m.group(0) : "";
    }

    /** The name= of every input/select/textarea in a fragment. */
    static List<String> fieldNames(String fragment) {
        List<String> names = new ArrayList<String>();
        Matcher m = Pattern.compile("<(?:input|select|textarea)[^>]*\\bname=\"([^\"]*)\"").matcher(fragment);
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }

    static String snapshot(Fixture f) {
        return new java.util.TreeMap<String, String>(f.store.values).toString();
    }

    /** A Fixture with KEY_A saved for the default URL and model m1. */
    static Fixture withKey() throws Exception {
        Fixture f = new Fixture();
        f.settings.save(ClaudeSettings.DEFAULT_BASE_URL, KEY_A, "m1");
        return f;
    }

    private static int failures;

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            System.out.println("PASS " + name);
        } else {
            failures++;
            System.out.println("FAIL " + name + ": " + detail);
        }
    }

    interface Scenario {
        void run(String name) throws Exception;
    }

    private static void scenario(String name, Scenario s) {
        try {
            s.run(name);
        } catch (Throwable t) {
            failures++;
            System.out.println("FAIL " + name + ": threw " + t);
        }
    }

    public static void main(String[] args) {
        // R1, R2: a Home link back to "/" first, then the Claude section.
        scenario("page_has_home_link_and_claude_section", new Scenario() {
            public void run(String n) throws Exception {
                String html = get(new Fixture());
                int home = html.indexOf("href=\"/\"");
                int claude = html.indexOf("Claude API");
                check(n, home > 0 && claude > home && html.contains("/assets/pico.min.css"),
                        "home=" + home + " claude=" + claude);
            }
        });

        // R3: the base URL starts pre-filled.
        scenario("fresh_page_prefills_default_url", new Scenario() {
            public void run(String n) throws Exception {
                String html = get(new Fixture());
                check(n, html.contains("value=\"https://teamclaude.rentaladvantage.rent\""), html);
            }
        });

        // R4, R14: not even inside an input's value attribute.
        scenario("html_never_contains_stored_key", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = withKey();
                String html = get(f);
                Matcher m = Pattern.compile("<input[^>]*name=\"key\"[^>]*>").matcher(html);
                String input = m.find() ? m.group(0) : "";
                check(n, !html.contains(KEY_A) && !html.contains(KEY_A.substring(0, 20))
                                && input.contains("type=\"password\"") && input.contains("autocomplete=\"off\"")
                                && !input.contains("value="),
                        "input=" + input);
            }
        });

        scenario("key_status_shows_last_four_and_saved_time", new Scenario() {
            public void run(String n) throws Exception {
                String html = get(withKey());
                check(n, html.contains("Key set, ends in …wxyz, saved ") && html.contains("2023-11-"),
                        html);
            }
        });

        scenario("fresh_page_says_no_key_set", new Scenario() {
            public void run(String n) throws Exception {
                String html = get(new Fixture());
                check(n, html.contains("No key set") && !html.contains("Key set,"), html);
            }
        });

        // AE6's first step: a fresh robot saves only a key.
        scenario("save_stores_key", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                Resp r = save(f, ClaudeSettings.DEFAULT_BASE_URL, KEY_A, "");
                String st = r.status();
                check(n, r.code() == 302 && r.location().startsWith("/settings?status=")
                                && KEY_A.equals(f.settings.credentialsForRequests().apiKey)
                                && st != null && st.startsWith("Saved") && !st.contains(KEY_A),
                        "loc=" + r.location());
            }
        });

        // AE2: a blank key keeps the stored one; the model updates.
        scenario("save_blank_key_keeps_key_and_updates_model", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = withKey();
                Resp r = save(f, ClaudeSettings.DEFAULT_BASE_URL + "/v1/", "", "claude-b");
                ClaudeSettings.Credentials c = f.settings.credentialsForRequests();
                check(n, KEY_A.equals(c.apiKey) && "claude-b".equals(c.model) && r.status().startsWith("Saved"),
                        "status=" + r.status() + " model=" + c.model);
            }
        });

        scenario("post_without_token_rejected", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = withKey();
                String before = snapshot(f);
                Resp r = request(f, "POST", "/settings/claude", "",
                        "base_url=" + enc(ClaudeSettings.DEFAULT_BASE_URL) + "&key=" + enc(KEY_B) + "&model=m2");
                Resp r2 = request(f, "POST", "/settings/claude/forget", "", "");
                check(n, before.equals(snapshot(f)) && r.code() == 302 && r2.code() == 302
                                && r.status().contains("expired") && r2.status().contains("expired"),
                        "status=" + r.status() + " / " + r2.status());
            }
        });

        // Capacity 4: a fifth later page load pushes the first token out.
        scenario("post_with_stale_token_rejected", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = withKey();
                String stale = token(f);
                for (int k = 0; k < 4; k++) {
                    token(f);
                }
                String before = snapshot(f);
                Resp r = request(f, "POST", "/settings/claude/forget", "", "t=" + stale);
                check(n, before.equals(snapshot(f)) && r.status().contains("expired"), "status=" + r.status());
            }
        });

        // KTD6: the token counts only in the body, never the URL.
        scenario("token_in_query_only_rejected", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = withKey();
                String before = snapshot(f);
                Resp r = request(f, "POST", "/settings/claude/forget", "t=" + token(f), "");
                check(n, before.equals(snapshot(f)) && r.status().contains("expired"), "status=" + r.status());
            }
        });

        // A token from any of the last few page loads works (robot screen + LAN browser).
        scenario("recent_token_accepted", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = withKey();
                String older = token(f);
                token(f);
                Resp r = request(f, "POST", "/settings/claude/forget", "", "t=" + older);
                check(n, !f.settings.status().keySet, "status=" + r.status());
            }
        });

        // AE1: a changed URL without a new key is refused, and nothing typed comes back.
        scenario("rejected_save_does_not_echo_input", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = withKey();
                String before = snapshot(f);
                Resp r = save(f, "https://attacker.example", "", "m1");
                Resp r2 = save(f, "http://evil.example/x", KEY_B, "m1");
                String st = r.status();
                String st2 = r2.status();
                check(n, before.equals(snapshot(f)) && st.startsWith("Not saved") && st.contains("key")
                                && !r.location().contains("attacker") && !r2.location().contains("evil")
                                && !r2.location().contains("BBBB") && !st2.contains(KEY_B)
                                && st2.contains("https://"),
                        "status=" + st + " / " + st2);
            }
        });

        scenario("oversized_form_rejected", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = withKey();
                String before = snapshot(f);
                StringBuilder big = new StringBuilder("t=" + token(f) + "&model=m2&pad=");
                for (int k = 0; k < SettingsPage.MAX_FORM_BYTES; k++) {
                    big.append('x');
                }
                Resp r = request(f, "POST", "/settings/claude", "", big.toString());
                check(n, before.equals(snapshot(f)) && r.status().contains("too large"), "status=" + r.status());
            }
        });

        // "No settings path handles GET with a key parameter": POST paths refuse GET.
        scenario("get_on_action_paths_refused", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = withKey();
                String before = snapshot(f);
                String t = token(f);
                boolean all405 = true;
                for (String p : Arrays.asList("/settings/claude", "/settings/claude/models",
                        "/settings/claude/test", "/settings/claude/forget")) {
                    Resp r = request(f, "GET", p, "t=" + t + "&base_url=https%3A%2F%2Fx.example&key="
                            + enc(KEY_B) + "&model=m2", null);
                    all405 &= r.code() == 405;
                }
                check(n, all405 && before.equals(snapshot(f)) && f.transport.sent.isEmpty(), snapshot(f));
            }
        });

        // R8: Refresh stores the ids; the next GET offers them as suggestions.
        scenario("refresh_stores_ids_and_page_suggests_them", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = withKey();
                Resp r = action(f, "/settings/claude/models");
                String html = get(f);
                check(n, Arrays.asList("claude-a", "claude-b").equals(f.settings.models())
                                && html.contains("<datalist") && html.contains("<option value=\"claude-a\">")
                                && html.contains("<option value=\"claude-b\">")
                                && r.status().contains("2"),
                        "status=" + r.status() + " models=" + f.settings.models());
            }
        });

        // Refresh and Test use the saved URL and key, in headers only (R14).
        scenario("refresh_uses_saved_credentials", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = withKey();
                action(f, "/settings/claude/models");
                ClaudeApi.Request sent = f.transport.sent.isEmpty() ? null : f.transport.sent.get(0);
                check(n, sent != null && sent.url.startsWith(ClaudeSettings.DEFAULT_BASE_URL + "/v1/models")
                                && !sent.url.contains("wxyz") && KEY_A.equals(sent.headers.get("x-api-key")),
                        sent == null ? "nothing sent" : sent.url);
            }
        });

        // KTD5: a saved model missing from a fresh, non-empty list stays saved and is marked.
        scenario("saved_model_missing_from_list_marked", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = withKey();
                action(f, "/settings/claude/models");
                String html = get(f);
                Fixture listed = new Fixture();
                listed.settings.save(ClaudeSettings.DEFAULT_BASE_URL, KEY_A, "claude-a");
                action(listed, "/settings/claude/models");
                Fixture noList = withKey();
                check(n, "m1".equals(f.settings.credentialsForRequests().model)
                                && html.contains("not listed by endpoint")
                                && !get(listed).contains("not listed by endpoint")
                                && !get(noList).contains("not listed by endpoint"),
                        html);
            }
        });

        // AE3, R9: a rejected key shows the reason and keeps the model and old list.
        scenario("refresh_failure_shows_reason_and_keeps_model", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = withKey();
                f.settings.saveModels(Arrays.asList("claude-old"));
                f.transport.status = 401;
                f.transport.body = "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\","
                        + "\"message\":\"bad key " + KEY_A + "\"}}";
                Resp r = action(f, "/settings/claude/models");
                String st = r.status();
                check(n, st.contains(ClaudeApi.Reason.BAD_KEY.text) && !st.contains(KEY_A)
                                && !r.location().contains("wxyz")
                                && "m1".equals(f.settings.credentialsForRequests().model)
                                && Arrays.asList("claude-old").equals(f.settings.models()),
                        "status=" + st);
            }
        });

        scenario("refresh_404_says_type_a_model", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = withKey();
                f.transport.status = 404;
                f.transport.body = "";
                Resp r = action(f, "/settings/claude/models");
                check(n, r.status().contains(ClaudeApi.Reason.MODELS_NOT_LISTED.text), "status=" + r.status());
            }
        });

        scenario("test_success_shows_status", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = withKey();
                f.transport.body = "{\"type\":\"message\"}";
                Resp r = action(f, "/settings/claude/test");
                ClaudeApi.Request sent = f.transport.sent.isEmpty() ? null : f.transport.sent.get(0);
                check(n, sent != null && sent.url.endsWith("/v1/messages") && sent.body.contains("\"m1\"")
                                && r.status().startsWith("Connection works"),
                        "status=" + r.status());
            }
        });

        scenario("test_failure_shows_reason", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = withKey();
                f.transport.fail = new java.net.UnknownHostException("teamclaude.rentaladvantage.rent");
                Resp r = action(f, "/settings/claude/test");
                check(n, r.status().contains(ClaudeApi.Reason.UNREACHABLE.text), "status=" + r.status());
            }
        });

        scenario("test_without_key_says_not_set_up", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                Resp r = action(f, "/settings/claude/test");
                check(n, r.status().contains(ClaudeApi.Reason.NOT_SET_UP.text) && f.transport.sent.isEmpty(),
                        "status=" + r.status());
            }
        });

        // R16, AE7.
        scenario("forget_key_then_page_shows_no_key", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = withKey();
                Resp r = action(f, "/settings/claude/forget");
                String html = get(f);
                check(n, !f.settings.status().keySet && "".equals(f.settings.credentialsForRequests().apiKey)
                                && html.contains("No key set") && r.code() == 302,
                        "status=" + r.status());
            }
        });

        // The three action buttons carry only the token (they use the saved settings).
        scenario("action_forms_carry_only_the_token", new Scenario() {
            public void run(String n) throws Exception {
                String html = get(withKey());
                StringBuilder detail = new StringBuilder();
                boolean ok = html.contains("These use the saved settings — save changes first.");
                for (String p : Arrays.asList("/settings/claude/models", "/settings/claude/test",
                        "/settings/claude/forget")) {
                    String fm = form(html, p);
                    List<String> names = fieldNames(fm);
                    ok &= fm.contains("method=\"post\"") && Arrays.asList("t").equals(names);
                    detail.append(p).append('=').append(names).append(' ');
                }
                List<String> save = fieldNames(form(html, "/settings/claude"));
                ok &= save.containsAll(Arrays.asList("t", "base_url", "key", "model"));
                check(n, ok, detail + " save=" + save);
            }
        });

        // Every value that reaches the page is escaped, the status line included.
        scenario("status_line_is_escaped", new Scenario() {
            public void run(String n) throws Exception {
                String html = get(new Fixture(), "status=%3Cscript%3Ealert(1)%3C%2Fscript%3E");
                check(n, !html.contains("<script>alert") && html.contains("&lt;script&gt;"), html);
            }
        });

        // AE6 end to end: key, Refresh, pick, save, Test.
        scenario("fresh_robot_flow", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                String s1 = save(f, ClaudeSettings.DEFAULT_BASE_URL, KEY_A, "").status();
                String s2 = action(f, "/settings/claude/models").status();
                String s3 = save(f, ClaudeSettings.DEFAULT_BASE_URL, "", "claude-b").status();
                f.transport.body = "{\"type\":\"message\"}";
                String s4 = action(f, "/settings/claude/test").status();
                check(n, s1.startsWith("Saved") && s2.startsWith("Found") && s3.startsWith("Saved")
                                && s4.startsWith("Connection works") && f.settings.credentialsForRequests().isSetUp(),
                        s1 + " | " + s2 + " | " + s3 + " | " + s4);
            }
        });

        // KTD9: MainActivity reloads on Wi-Fi changes only while on the home page.
        scenario("home_page_url_check", new Scenario() {
            public void run(String n) {
                boolean ok = SettingsPage.isHomePageUrl("https://127.0.0.1:8443/")
                        && SettingsPage.isHomePageUrl("http://127.0.0.1:8080/?status=Scanning...")
                        && SettingsPage.isHomePageUrl("https://127.0.0.1:8443")
                        && !SettingsPage.isHomePageUrl("https://127.0.0.1:8443/settings")
                        && !SettingsPage.isHomePageUrl("https://127.0.0.1:8443/settings?status=Saved.")
                        && !SettingsPage.isHomePageUrl(null)
                        && !SettingsPage.isHomePageUrl("not a url %%");
                check(n, ok, "");
            }
        });

        if (failures > 0) {
            System.exit(1);
        }
    }
}
