package com.miko3.launcher;

import com.miko3.shared.ClaudeApi;
import com.miko3.shared.HttpRequest;
import com.miko3.shared.HttpResponse;
import com.miko3.shared.LauncherProtocol;
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

    /** Records what the page asked the robot to say. */
    static final class FakeSpeaker implements SettingsPage.Speaker {
        boolean ready = true;
        boolean failed;
        String voice = "stock lessac medium";
        final List<String> said = new ArrayList<String>();

        public boolean ready() {
            return ready;
        }

        public boolean failed() {
            return failed;
        }

        public String voiceName() {
            return voice;
        }

        public void say(String text) {
            said.add(text);
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
        final FakeSpeaker speaker = new FakeSpeaker();
        final PeopleClock peopleClock = new PeopleClock();
        final PeopleStore people = new PeopleStore(tempDir(), peopleClock);
    }

    static final class PeopleClock implements PeopleStore.Clock {
        long now = 1_700_000_000_000L;

        @Override
        public long nowMillis() {
            return now;
        }
    }

    static java.io.File tempDir() {
        try {
            java.io.File d = java.io.File.createTempFile("settings_people_", "");
            if (!d.delete() || !d.mkdirs()) {
                throw new IllegalStateException("no temp dir");
            }
            d.deleteOnExit();
            return d;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] jpeg(int tag) {
        return new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) tag, 1, 2, 3, (byte) 0xFF, (byte) 0xD9};
    }

    static final class Resp {
        String head;
        String body;
        byte[] bytes;

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
        SettingsPage.handle(req, new HttpResponse(out), f.token, f.settings, f.api, f.speaker, f.people);
        String raw = new String(out.toByteArray(), StandardCharsets.UTF_8);
        int split = raw.indexOf("\r\n\r\n");
        Resp r = new Resp();
        r.head = split < 0 ? raw : raw.substring(0, split);
        r.body = split < 0 ? "" : raw.substring(split + 4);
        byte[] all = out.toByteArray();
        int at = indexOf(all, "\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        r.bytes = at < 0 ? new byte[0] : Arrays.copyOfRange(all, at + 4, all.length);
        return r;
    }

    static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
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

    static Resp say(Fixture f, String text) throws Exception {
        return request(f, "POST", "/settings/voice/say", "", "t=" + token(f) + "&text=" + enc(text));
    }

    static String repeat(char c, int n) {
        StringBuilder b = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            b.append(c);
        }
        return b.toString();
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

        // The API key travels in settings POSTs, so RoutingHttpServer serves every
        // settings path over TLS only and refuses it on the plain listener.
        scenario("settings_paths_are_tls_only", new Scenario() {
            public void run(String n) {
                List<String> wrong = new ArrayList<String>();
                for (String p : Arrays.asList(LauncherProtocol.SETTINGS_PATH, LauncherProtocol.SETTINGS_CLAUDE_PATH,
                        LauncherProtocol.SETTINGS_CLAUDE_MODELS_PATH, LauncherProtocol.SETTINGS_CLAUDE_TEST_PATH,
                        LauncherProtocol.SETTINGS_CLAUDE_FORGET_PATH, "/settings/", "/settings/anything/else")) {
                    if (!LauncherProtocol.isTlsOnlyPath(p)) {
                        wrong.add("plain:" + p);
                    }
                }
                for (String p : Arrays.asList(LauncherProtocol.PRESENCE_PATH, "/", LauncherProtocol.LAUNCH_MODE_PATH,
                        "/settingsx", "/drive-ws", "", null)) {
                    if (LauncherProtocol.isTlsOnlyPath(p)) {
                        wrong.add("tls:" + p);
                    }
                }
                check(n, wrong.isEmpty(), wrong.toString());
            }
        });

        scenario("plain_http_settings_refusal_is_fixed_text", new Scenario() {
            public void run(String n) {
                String named = LauncherProtocol.settingsNeedHttpsMessage("192.168.1.50:8080");
                String bare = LauncherProtocol.settingsNeedHttpsMessage("robot.local");
                String hostile = LauncherProtocol.settingsNeedHttpsMessage("<script>x</script>");
                String none = LauncherProtocol.settingsNeedHttpsMessage(null);
                boolean ok = named.contains("https://192.168.1.50:" + LauncherProtocol.LAUNCHER_HTTPS_PORT + "/settings")
                        && LauncherProtocol.LAUNCHER_HTTPS_PORT == 8443
                        && named.toLowerCase().contains("https")
                        && bare.contains("https://robot.local:8443/settings")
                        && !hostile.contains("script") && hostile.contains("https://<robot address>:")
                        && hostile.contains(":8443/settings") && none.contains(":8443/settings");
                check(n, ok, named + " | " + hostile + " | " + none);
            }
        });

        // Voice: the owner types a line and the robot says it.
        scenario("voice_section_shows_say_form_and_voice_name", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                String html = get(f);
                String form = form(html, "/settings/voice/say");
                f.speaker.voice = "trained";
                String trained = get(f);
                check(n, html.contains("<section id=\"voice\">") && html.indexOf("id=\"voice\"") > html.indexOf("Claude API")
                                && fieldNames(form).equals(Arrays.asList("t", "text"))
                                && form.contains("maxlength=\"" + SpeechQueue.MAX_CHARS + "\"")
                                && form.contains(">Say it</button>")
                                && html.contains("stock lessac medium") && trained.contains("trained"),
                        form);
            }
        });

        scenario("say_without_token_rejected", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                Resp r = request(f, "POST", "/settings/voice/say", "", "text=" + enc("Hello"));
                check(n, r.code() == 302 && r.status().contains("expired") && f.speaker.said.isEmpty(),
                        r.head);
            }
        });

        scenario("say_with_stale_token_rejected", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                String stale = token(f);
                for (int i = 0; i < 4; i++) {
                    token(f);
                }
                Resp r = request(f, "POST", "/settings/voice/say", "", "t=" + stale + "&text=" + enc("Hello"));
                check(n, r.status().contains("expired") && f.speaker.said.isEmpty(), r.status());
            }
        });

        scenario("say_empty_text_refused", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                Resp r = say(f, "   ");
                Resp missing = request(f, "POST", "/settings/voice/say", "", "t=" + token(f));
                check(n, "Nothing said: type something to say.".equals(r.status())
                                && r.status().equals(missing.status()) && f.speaker.said.isEmpty(),
                        r.status() + " | " + missing.status());
            }
        });

        scenario("say_too_long_text_refused", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                Resp r = say(f, repeat('a', SpeechQueue.MAX_CHARS + 1));
                Resp max = say(f, repeat('b', SpeechQueue.MAX_CHARS));
                check(n, ("Nothing said: that is longer than " + SpeechQueue.MAX_CHARS + " characters.")
                                .equals(r.status()) && "Speaking.".equals(max.status())
                                && f.speaker.said.size() == 1,
                        r.status() + " | " + max.status());
            }
        });

        scenario("say_while_voice_loading_refused", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                f.speaker.ready = false;
                Resp r = say(f, "Hello");
                check(n, "The voice is still loading; try again in a few seconds.".equals(r.status())
                        && f.speaker.said.isEmpty(), r.status());
            }
        });

        scenario("say_after_voice_failed_says_not_available", new Scenario() {
            public void run(String n) throws Exception {
                // The voice failed to load: it will never be ready, so the page
                // must not keep saying "still loading".
                Fixture f = new Fixture();
                f.speaker.ready = false;
                f.speaker.failed = true;
                Resp r = say(f, "Hello");
                check(n, "Nothing said: the robot's voice is not available.".equals(r.status())
                        && f.speaker.said.isEmpty(), r.status());
            }
        });

        scenario("say_speaks_and_redirects", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                Resp r = say(f, "Hello! I can talk now.");
                check(n, r.code() == 302 && r.location().startsWith("/settings?status=")
                                && "Speaking.".equals(r.status())
                                && f.speaker.said.equals(Arrays.asList("Hello! I can talk now.")),
                        r.head + " said=" + f.speaker.said);
            }
        });

        scenario("say_status_never_echoes_text", new Scenario() {
            public void run(String n) throws Exception {
                String marker = "zqxmarker";
                List<String> leaks = new ArrayList<String>();
                for (boolean ready : new boolean[] {true, false}) {
                    for (String text : Arrays.asList(marker, marker + repeat('x', SpeechQueue.MAX_CHARS),
                            "<b>" + marker + "</b>")) {
                        Fixture f = new Fixture();
                        f.speaker.ready = ready;
                        Resp r = say(f, text);
                        if (r.location().contains(marker) || r.status().contains(marker)) {
                            leaks.add(r.location());
                        }
                    }
                }
                check(n, leaks.isEmpty(), leaks.toString());
            }
        });

        scenario("get_on_say_path_refused", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                Resp r = request(f, "GET", "/settings/voice/say", "t=" + token(f) + "&text=Hello", null);
                check(n, r.code() == 405 && f.speaker.said.isEmpty()
                                && LauncherProtocol.isTlsOnlyPath(LauncherProtocol.SETTINGS_VOICE_SAY_PATH),
                        r.head);
            }
        });

        // U2 (explore-on-claude plan): the People section, R15, R16, AE6.
        scenario("people_section_empty", new Scenario() {
            public void run(String n) throws Exception {
                String html = get(new Fixture());
                int people = html.indexOf("<section id=\"people\">");
                check(n, people > html.indexOf("<section id=\"voice\">")
                                && html.contains("id=\"people-empty\"") && !html.contains("<img"),
                        html);
            }
        });

        scenario("people_section_lists_faces_names_and_last_seen", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                String sarah = f.people.add(jpeg(1), "Sarah");
                f.peopleClock.now += 60_000;
                String anon = f.people.add(jpeg(2), null);
                String html = get(f);
                String section = html.substring(html.indexOf("<section id=\"people\">"));
                String img = "src=\"" + LauncherProtocol.SETTINGS_PEOPLE_FACE_PATH + "?id=";
                boolean faces = section.contains(img + sarah + "\"") && section.contains(img + anon + "\"");
                boolean names = section.contains("Sarah") && section.contains("unnamed");
                boolean seen = section.contains(SettingsPage.lastSeen(f.people.all().get(0).lastSeenMillis))
                        && section.contains("Last seen");
                boolean order = section.indexOf(anon) < section.indexOf(sarah);
                boolean forms = !form(section, LauncherProtocol.SETTINGS_PEOPLE_FORGET_PATH).isEmpty()
                        && !form(section, LauncherProtocol.SETTINGS_PEOPLE_RENAME_PATH).isEmpty()
                        && !section.contains("people-empty");
                check(n, faces && names && seen && order && forms,
                        "faces=" + faces + " names=" + names + " seen=" + seen + " order=" + order
                                + " forms=" + forms + " " + section);
            }
        });

        scenario("people_name_is_escaped", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                f.people.add(jpeg(1), "<b>Bo</b>");
                String html = get(f);
                check(n, html.contains("&lt;b&gt;Bo&lt;/b&gt;") && !html.contains("<b>Bo"), html);
            }
        });

        scenario("people_forms_carry_token_and_id_only", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                f.people.add(jpeg(1), "Sarah");
                String html = get(f);
                List<String> forget = fieldNames(form(html, LauncherProtocol.SETTINGS_PEOPLE_FORGET_PATH));
                List<String> rename = fieldNames(form(html, LauncherProtocol.SETTINGS_PEOPLE_RENAME_PATH));
                check(n, forget.equals(Arrays.asList("t", "id")) && rename.equals(Arrays.asList("t", "id", "name")),
                        forget + " " + rename);
            }
        });

        scenario("rename_changes_name_and_redirects", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                String id = f.people.add(jpeg(1), null);
                Resp r = request(f, "POST", LauncherProtocol.SETTINGS_PEOPLE_RENAME_PATH, "",
                        "t=" + token(f) + "&id=" + id + "&name=" + enc("  Sarah "));
                check(n, r.location().startsWith("/settings?status=") && "Sarah".equals(f.people.nameOf(id))
                                && SettingsPage.PEOPLE_RENAMED.equals(r.status()),
                        r.head + " name=" + f.people.nameOf(id));
            }
        });

        scenario("rename_empty_makes_unnamed", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                String id = f.people.add(jpeg(1), "Sarah");
                Resp r = request(f, "POST", LauncherProtocol.SETTINGS_PEOPLE_RENAME_PATH, "",
                        "t=" + token(f) + "&id=" + id + "&name=");
                check(n, "".equals(f.people.nameOf(id)) && get(f).contains("unnamed")
                        && SettingsPage.PEOPLE_UNNAMED.equals(r.status()), r.head);
            }
        });

        // AE6: Forget deletes the face and the name for good.
        scenario("forget_removes_person_from_store_and_page", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                String id = f.people.add(jpeg(1), "Sarah");
                Resp r = request(f, "POST", LauncherProtocol.SETTINGS_PEOPLE_FORGET_PATH, "",
                        "t=" + token(f) + "&id=" + id);
                Resp face = request(f, "GET", LauncherProtocol.SETTINGS_PEOPLE_FACE_PATH, "id=" + id, null);
                String html = get(f);
                check(n, f.people.nameOf(id) == null && f.people.face(id) == null && !html.contains(id)
                                && !html.contains("Sarah") && face.code() == 404
                                && SettingsPage.PEOPLE_FORGOTTEN.equals(r.status()),
                        r.head + " " + face.head);
            }
        });

        scenario("people_actions_on_unknown_id_change_nothing", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                String id = f.people.add(jpeg(1), "Sarah");
                Resp r1 = request(f, "POST", LauncherProtocol.SETTINGS_PEOPLE_FORGET_PATH, "",
                        "t=" + token(f) + "&id=" + enc("../x"));
                Resp r2 = request(f, "POST", LauncherProtocol.SETTINGS_PEOPLE_RENAME_PATH, "",
                        "t=" + token(f) + "&id=0123456789abcdef&name=Eve");
                check(n, SettingsPage.PEOPLE_UNKNOWN.equals(r1.status())
                                && SettingsPage.PEOPLE_UNKNOWN.equals(r2.status())
                                && "Sarah".equals(f.people.nameOf(id)) && f.people.all().size() == 1,
                        r1.status() + " | " + r2.status());
            }
        });

        scenario("people_actions_with_stale_token_refused", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                String id = f.people.add(jpeg(1), "Sarah");
                String stale = token(f);
                for (int i = 0; i < 5; i++) {
                    token(f);
                }
                Resp r1 = request(f, "POST", LauncherProtocol.SETTINGS_PEOPLE_FORGET_PATH, "",
                        "t=" + stale + "&id=" + id);
                Resp r2 = request(f, "POST", LauncherProtocol.SETTINGS_PEOPLE_RENAME_PATH, "",
                        "t=" + stale + "&id=" + id + "&name=Eve");
                Resp r3 = request(f, "POST", LauncherProtocol.SETTINGS_PEOPLE_FORGET_PATH, "", "id=" + id);
                check(n, "Sarah".equals(f.people.nameOf(id)) && r1.status().contains("expired")
                                && r2.status().contains("expired") && r3.status().contains("expired"),
                        r1.status() + " | " + r2.status() + " | " + r3.status());
            }
        });

        scenario("people_status_never_echoes_name", new Scenario() {
            public void run(String n) throws Exception {
                String marker = "Zqxmarker";
                List<String> leaks = new ArrayList<String>();
                for (String name : Arrays.asList(marker, "<b>" + marker + "</b>",
                        marker + repeat('x', 200), "")) {
                    Fixture f = new Fixture();
                    String id = f.people.add(jpeg(1), marker);
                    for (Resp r : Arrays.asList(
                            request(f, "POST", LauncherProtocol.SETTINGS_PEOPLE_RENAME_PATH, "",
                                    "t=" + token(f) + "&id=" + id + "&name=" + enc(name)),
                            request(f, "POST", LauncherProtocol.SETTINGS_PEOPLE_FORGET_PATH, "",
                                    "t=" + token(f) + "&id=" + id))) {
                        if (r.location().contains(marker) || r.status().contains(marker)) {
                            leaks.add(r.location());
                        }
                    }
                }
                check(n, leaks.isEmpty(), leaks.toString());
            }
        });

        scenario("get_on_people_action_paths_refused", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                String id = f.people.add(jpeg(1), "Sarah");
                Resp r1 = request(f, "GET", LauncherProtocol.SETTINGS_PEOPLE_FORGET_PATH,
                        "t=" + token(f) + "&id=" + id, null);
                Resp r2 = request(f, "GET", LauncherProtocol.SETTINGS_PEOPLE_RENAME_PATH,
                        "t=" + token(f) + "&id=" + id + "&name=Eve", null);
                check(n, r1.code() == 405 && r2.code() == 405 && "Sarah".equals(f.people.nameOf(id)),
                        r1.head + " | " + r2.head);
            }
        });

        scenario("face_get_serves_the_jpeg", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                String id = f.people.add(jpeg(5), null);
                Resp r = request(f, "GET", LauncherProtocol.SETTINGS_PEOPLE_FACE_PATH, "id=" + id, null);
                check(n, r.code() == 200 && r.head.toLowerCase().contains("content-type: image/jpeg")
                        && Arrays.equals(r.bytes, jpeg(5)), r.head);
            }
        });

        scenario("face_get_refuses_unknown_and_bad_ids", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                String id = f.people.add(jpeg(5), null);
                List<String> wrong = new ArrayList<String>();
                for (String q : Arrays.asList("id=0123456789abcdef", "id=" + enc("../" + id), "id=",
                        "", "id=" + enc(PeopleStore.INDEX_FILE), "id=" + id.toUpperCase())) {
                    Resp r = request(f, "GET", LauncherProtocol.SETTINGS_PEOPLE_FACE_PATH, q, null);
                    if (r.code() != 404 || r.body.contains("ÿ")) {
                        wrong.add(q + "->" + r.head);
                    }
                }
                Resp post = request(f, "POST", LauncherProtocol.SETTINGS_PEOPLE_FACE_PATH, "id=" + id,
                        "t=" + token(f));
                if (post.code() != 405) {
                    wrong.add("POST->" + post.head);
                }
                check(n, wrong.isEmpty(), wrong.toString());
            }
        });

        scenario("people_paths_are_tls_only", new Scenario() {
            public void run(String n) {
                check(n, LauncherProtocol.isTlsOnlyPath(LauncherProtocol.SETTINGS_PEOPLE_FACE_PATH)
                                && LauncherProtocol.isTlsOnlyPath(LauncherProtocol.SETTINGS_PEOPLE_RENAME_PATH)
                                && LauncherProtocol.isTlsOnlyPath(LauncherProtocol.SETTINGS_PEOPLE_FORGET_PATH),
                        "a people path is served on plain HTTP");
            }
        });

        if (failures > 0) {
            System.exit(1);
        }
    }
}
