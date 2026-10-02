package com.miko3.launcher;

import com.miko3.shared.ClaudeApi;
import com.miko3.shared.ConversationSettings;
import com.miko3.shared.FaceCheck;
import com.miko3.shared.FaceSettings;
import com.miko3.shared.HttpRequest;
import com.miko3.shared.HttpResponse;
import com.miko3.shared.Json;
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
        final FaceChecks checks = new FaceChecks(new PeopleStore.Clock() {
            @Override
            public long nowMillis() {
                return 1_700_000_000_000L;
            }
        });
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

    /** A record like the ones stored before R19: a face with no name. The store
     * refuses to add one now, so it is made by blanking a name, as the People
     * page can. */
    static String legacyNameless(PeopleStore people, byte[] face) {
        String id = people.add(face, "legacy");
        if (!people.rename(id, "")) {
            throw new IllegalStateException("rename failed");
        }
        return id;
    }

    static byte[] jpeg(int tag) {
        return new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) tag, 1, 2, 3, (byte) 0xFF, (byte) 0xD9};
    }


    // ---- ears probe (meeting plan U1): fakes for the property, the clock, the direction and the capture ----

    static final class FakeProps implements EarsProbe.Props {
        final Map<String, String> values = new HashMap<String, String>();

        public String get(String key) {
            String v = values.get(key);
            return v == null ? "" : v;
        }
    }

    static final class FakeClock implements EarsProbe.Clock {
        long now = 1_700_000_000_000L;

        public long nowMs() {
            return now;
        }
    }

    /** Answers the scripted sample lists, one per drain, then nothing. */
    static final class FakeDirection implements EarsProbe.Direction {
        final List<List<Float>> drains = new ArrayList<List<Float>>();
        Integer raw;
        String reply;

        public String backend() {
            return "fake";
        }

        public Integer raw() {
            return raw;
        }

        public String rawReply() {
            return reply;
        }

        public List<Float> drain() {
            return drains.isEmpty() ? new ArrayList<Float>() : drains.remove(0);
        }
    }

    /** A microphone of steady 0.25 amplitude (RMS 8192 in 16-bit terms), in 80 ms chunks. */
    static final class FakeMic implements ListenSession.Mic {
        boolean closed;

        public int read(float[] buf) {
            for (int i = 0; i < buf.length; i++) {
                buf[i] = (i % 2 == 0) ? 0.25f : -0.25f;
            }
            return buf.length;
        }

        public void close() {
            closed = true;
        }
    }

    /** Hears "hello" through the first row (which closes on the 13th chunk, 16640
     * samples) and "hello robot friend" from the second row on. */
    static final class FakeRecognizer implements ListenSession.Recognizer {
        long samples;
        boolean closed;

        public void accept(float[] s, int n) {
            samples += n;
        }

        public boolean isEndpoint() {
            return true; // the tap must ignore this and run to the cap
        }

        public String text() {
            // The real one reads a native stream that close() released: reading it after
            // close segfaulted the launcher on the robot (2026-09-29).
            if (closed) {
                throw new IllegalStateException("text() after close(): use after free");
            }
            return samples < 20000 ? "hello" : "hello robot friend";
        }

        public void finish() {
        }

        public void close() {
            closed = true;
        }
    }

    /** The launcher's capture, minus the thread and the claim: the tapped fakes through
     * ListenSession.capture, the very loop the real listen uses. */
    static final class FakeRunner implements EarsProbe.Runner {
        final FakeMic mic = new FakeMic();
        /** The last capture's recogniser: the launcher creates a fresh one per capture. */
        FakeRecognizer rec = new FakeRecognizer();
        int seconds = -1;
        RuntimeException fail;

        public List<EarsProbe.Row> capture(EarsProbe.Tap tap, int seconds) {
            this.seconds = seconds;
            if (fail != null) {
                throw fail;
            }
            rec = new FakeRecognizer();
            ListenSession.Mic m = tap.mic(mic);
            ListenSession.Recognizer r = tap.recognizer(rec);
            ListenSession.capture(m, r, seconds * 1000L);
            m.close();
            r.close();
            return tap.rows();
        }
    }

    static final class Probe {
        final FakeProps props = new FakeProps();
        final FakeClock clock = new FakeClock();
        final FakeDirection direction = new FakeDirection();
        final FakeRunner runner = new FakeRunner();
        final PageToken token = new PageToken(4);
        final EarsProbe probe = new EarsProbe(props, clock, token, direction, runner);

        /** The property set the way scripts/qa-ears-probe.py sets it. */
        Probe armed(String nonce) {
            props.values.put(EarsProbe.PROPERTY, nonce);
            return this;
        }
    }

    static Resp probeRequest(Probe p, String method, String form) throws Exception {
        Map<String, String> headers = new HashMap<String, String>();
        headers.put("host", "192.168.19.74:8443");
        byte[] body = form == null ? new byte[0] : form.getBytes(StandardCharsets.UTF_8);
        HttpRequest req = new HttpRequest(method, LauncherProtocol.SETTINGS_EARS_PROBE_PATH,
                HttpRequest.parseQuery(""), headers, new ByteArrayInputStream(body));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        p.probe.handle(req, new HttpResponse(out));
        String raw = new String(out.toByteArray(), StandardCharsets.UTF_8);
        int split = raw.indexOf("\r\n\r\n");
        Resp r = new Resp();
        r.head = split < 0 ? raw : raw.substring(0, split);
        r.body = split < 0 ? "" : raw.substring(split + 4);
        r.bytes = new byte[0];
        return r;
    }

    static Resp probePost(Probe p, String nonce, int seconds, String phrase) throws Exception {
        String form = "t=" + p.token.issue() + "&nonce=" + nonce + "&seconds=" + seconds
                + (phrase == null ? "" : "&phrase=" + enc(phrase));
        return probeRequest(p, "POST", form);
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
        return request(f, method, path, query, form, false);
    }

    /** As request(), from a loopback caller when loopback is true (how the CLI
     * arrives over adb forward). */
    static Resp request(Fixture f, String method, String path, String query, String form, boolean loopback)
            throws Exception {
        Map<String, String> headers = new HashMap<String, String>();
        headers.put("host", "192.168.19.74:8443");
        byte[] body = form == null ? new byte[0] : form.getBytes(StandardCharsets.UTF_8);
        HttpRequest req = new HttpRequest(method, path, HttpRequest.parseQuery(query), headers,
                new ByteArrayInputStream(body), loopback);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        SettingsPage.handle(req, new HttpResponse(out), f.token, f.settings, f.api, f.speaker, f.people, f.checks);
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

    /** The <section id="..."> ... </section> fragment, or "" when there is none. */
    static String section(String html, String id) {
        Matcher m = Pattern.compile("<section id=\"" + Pattern.quote(id) + "\">(.*?)</section>", Pattern.DOTALL)
                .matcher(html);
        return m.find() ? m.group(0) : "";
    }

    /** The whole <textarea name="..."> element including its content, or "". */
    static String textarea(String fragment, String name) {
        Matcher m = Pattern.compile("<textarea[^>]*\\bname=\"" + Pattern.quote(name) + "\"[^>]*>.*?</textarea>",
                Pattern.DOTALL).matcher(fragment);
        return m.find() ? m.group(0) : "";
    }

    /** The <input name="..."> tag, or "". */
    static String input(String fragment, String name) {
        Matcher m = Pattern.compile("<input[^>]*\\bname=\"" + Pattern.quote(name) + "\"[^>]*>").matcher(fragment);
        return m.find() ? m.group(0) : "";
    }


    /** One person's <article id="person-..."> fragment, or "". */
    static String article(String html, String id) {
        Matcher m = Pattern.compile("<article id=\"person-" + Pattern.quote(id) + "\">(.*?)</article>", Pattern.DOTALL)
                .matcher(html);
        return m.find() ? m.group(0) : "";
    }


    // ---- face plan U5: face checks and photos ----

    static final String MODEL = "sface-2021dec";

    static float[] emb(float v) {
        return new float[] {v, 0.5f, 0.25f};
    }

    /** A named person with n photos, each added a minute after the last. */
    static String personWithPhotos(Fixture f, String name, int n) {
        String id = f.people.addPerson(jpeg(10), name, MODEL, emb(0.1f));
        for (int i = 1; i < n; i++) {
            f.peopleClock.now += 60_000;
            f.people.addPhoto(id, jpeg(10 + i), MODEL, emb(0.1f * (i + 1)));
        }
        return id;
    }

    static long addedAt(Fixture f, String id, int slot) {
        for (PeopleStore.Photo p : f.people.photos(id)) {
            if (p.slot == slot) {
                return p.addedAtMillis;
            }
        }
        return -1;
    }

    static FaceCheck matchCheck(Fixture f, int decision, String id, int slot, float score) {
        return new FaceCheck(decision, FaceCheck.REASON_NONE, jpeg(40), id, slot, addedAt(f, id, slot), score,
                "", 0f, false);
    }

    /** The <article id="check-..."> fragment for a handle, or "". */
    static String checkArticle(String html, long handle) {
        Matcher m = Pattern.compile("<article id=\"check-" + handle + "\">(.*?)</article>", Pattern.DOTALL)
                .matcher(html);
        return m.find() ? m.group(0) : "";
    }

    static Resp thresholds(Fixture f, String fields, boolean loopback) throws Exception {
        return request(f, "POST", LauncherProtocol.SETTINGS_FACE_THRESHOLDS_PATH, "", "t=" + token(f) + fields,
                loopback);
    }

    static String photoQuery(String id, int slot, long at) {
        return "id=" + id + "&slot=" + slot + "&at=" + at;
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

        // The model picked from the list wins over the typed box; an empty pick keeps the typed name.
        scenario("save_picked_model_wins_and_an_empty_pick_keeps_the_typed_name", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = withKey();
                String base = "t=" + token(f) + "&base_url=" + enc(ClaudeSettings.DEFAULT_BASE_URL + "/v1/") + "&key=";
                request(f, "POST", "/settings/claude", "", base + "&model=" + enc("claude-a") + "&model_pick="
                        + enc("claude-b"));
                String picked = f.settings.credentialsForRequests().model;
                request(f, "POST", "/settings/claude", "", "t=" + token(f) + "&base_url="
                        + enc(ClaudeSettings.DEFAULT_BASE_URL + "/v1/") + "&key=&model=" + enc("claude-c")
                        + "&model_pick=");
                String typed = f.settings.credentialsForRequests().model;
                check(n, "claude-b".equals(picked) && "claude-c".equals(typed), "picked=" + picked + " typed=" + typed);
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
                                && html.contains("<select id=\"model_pick\"")
                                && html.contains("<option value=\"claude-a\">claude-a")
                                && html.contains("<option value=\"claude-b\">claude-b")
                                && !html.contains("<datalist")
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
                String anon = legacyNameless(f.people, jpeg(2));
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
                String id = legacyNameless(f.people, jpeg(1));
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
                String id = f.people.add(jpeg(5), "Sam");
                Resp r = request(f, "GET", LauncherProtocol.SETTINGS_PEOPLE_FACE_PATH, "id=" + id, null);
                check(n, r.code() == 200 && r.head.toLowerCase().contains("content-type: image/jpeg")
                        && Arrays.equals(r.bytes, jpeg(5)), r.head);
            }
        });

        scenario("face_get_refuses_unknown_and_bad_ids", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                String id = f.people.add(jpeg(5), "Sam");
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

        // ---- ears probe (meeting plan U1, step 4) ----
        scenario("ears_probe_is_404_without_the_property", new Scenario() {
            public void run(String n) throws Exception {
                Probe p = new Probe();
                Resp r = probePost(p, "abc", 2, null);
                check(n, r.code() == 404 && p.runner.seconds == -1, "code=" + r.code() + " ran=" + p.runner.seconds);
            }
        });
        scenario("ears_probe_is_404_on_a_get", new Scenario() {
            public void run(String n) throws Exception {
                Probe p = new Probe().armed("abc");
                Resp r = probeRequest(p, "GET", null);
                check(n, r.code() == 404 && p.runner.seconds == -1, "code=" + r.code() + " ran=" + p.runner.seconds);
            }
        });
        scenario("ears_probe_is_404_with_a_wrong_nonce_or_no_token", new Scenario() {
            public void run(String n) throws Exception {
                Probe p = new Probe().armed("abc");
                Resp wrong = probePost(p, "abd", 2, null);
                Resp noToken = probeRequest(p, "POST", "nonce=abc&seconds=2");
                Resp staleToken = probeRequest(p, "POST", "t=0123456789abcdef&nonce=abc&seconds=2");
                check(n, wrong.code() == 404 && noToken.code() == 404 && staleToken.code() == 404
                                && p.runner.seconds == -1,
                        "wrong=" + wrong.code() + " noToken=" + noToken.code() + " stale=" + staleToken.code()
                                + " ran=" + p.runner.seconds);
            }
        });
        scenario("ears_probe_is_404_fifteen_minutes_after_the_property_was_first_read", new Scenario() {
            public void run(String n) throws Exception {
                Probe p = new Probe().armed("abc");
                Resp first = probePost(p, "abc", 1, null);
                p.clock.now += EarsProbe.WINDOW_MS - 1;
                Resp inside = probePost(p, "abc", 1, null);
                p.clock.now += 1;
                Resp after = probePost(p, "abc", 1, null);
                // A fresh run (a new nonce in the property) opens a new window.
                p.armed("def");
                Resp fresh = probePost(p, "def", 1, null);
                check(n, first.code() == 200 && inside.code() == 200 && after.code() == 404 && fresh.code() == 200,
                        "first=" + first.code() + " inside=" + inside.code() + " after=" + after.code()
                                + " fresh=" + fresh.code());
            }
        });
        scenario("ears_probe_rows_carry_counts_and_match_flags_never_text", new Scenario() {
            public void run(String n) throws Exception {
                Probe p = new Probe().armed("abc");
                p.direction.drains.add(new ArrayList<Float>(java.util.Arrays.asList(10f, 30f, 12f, 9f, 11f)));
                Resp r = probePost(p, "abc", 2, "Hello, robot!");
                Object parsed = r.code() == 200 ? Json.parse(r.body) : null;
                List<?> rows = parsed instanceof Map ? (List<?>) ((Map<?, ?>) parsed).get("rows") : null;
                Map<?, ?> row1 = rows != null && rows.size() == 2 ? (Map<?, ?>) rows.get(0) : null;
                Map<?, ?> row2 = rows != null && rows.size() == 2 ? (Map<?, ?>) rows.get(1) : null;
                boolean shape = row1 != null && row2 != null
                        && Long.valueOf(1).equals(row1.get("second")) && Long.valueOf(2).equals(row2.get("second"))
                        && Double.valueOf(11.0).equals(row1.get("angle")) && row2.get("angle") == null
                        && row2.containsKey("angle")
                        && Long.valueOf(8192).equals(row1.get("rms"))
                        && ((Long) row1.get("decode_ms")) >= 0 && ((Long) row1.get("chunks")) > 0
                        && Long.valueOf(1).equals(row1.get("words")) && Boolean.FALSE.equals(row1.get("matched"))
                        && Long.valueOf(3).equals(row2.get("words")) && Boolean.TRUE.equals(row2.get("matched"));
                String lower = (r.head + r.body).toLowerCase();
                boolean noWords = !lower.contains("hello") && !lower.contains("robot") && !lower.contains("friend")
                        && !lower.contains("\"text\"");
                check(n, r.code() == 200 && shape && noWords && p.runner.seconds == 2
                                && "fake".equals(((Map<?, ?>) parsed).get("backend"))
                                && p.runner.mic.closed && p.runner.rec.closed,
                        "code=" + r.code() + " body=" + r.body);
            }
        });
        scenario("ears_probe_rows_carry_the_chips_raw_value_for_calibration", new Scenario() {
            public void run(String n) throws Exception {
                // hey-miko plan U1/KTD12: qa-direction-chip.py --calibrate fits zero, sign and
                // scale from each row's raw chip value, and confirms the port from raw_reply.
                Probe p = new Probe().armed("abc");
                p.direction.raw = 138;
                p.direction.reply = "58585542aa";
                Resp r = probePost(p, "abc", 1, null);
                Object parsed = r.code() == 200 ? Json.parse(r.body) : null;
                List<?> rows = parsed instanceof Map ? (List<?>) ((Map<?, ?>) parsed).get("rows") : null;
                Map<?, ?> row1 = rows != null && rows.size() == 1 ? (Map<?, ?>) rows.get(0) : null;
                Probe none = new Probe().armed("abc");
                Resp r2 = probePost(none, "abc", 1, null);
                Object parsed2 = r2.code() == 200 ? Json.parse(r2.body) : null;
                List<?> rows2 = parsed2 instanceof Map ? (List<?>) ((Map<?, ?>) parsed2).get("rows") : null;
                Map<?, ?> row2 = rows2 != null && rows2.size() == 1 ? (Map<?, ?>) rows2.get(0) : null;
                check(n, row1 != null && Long.valueOf(138).equals(row1.get("raw"))
                                && "58585542aa".equals(((Map<?, ?>) parsed).get("raw_reply"))
                                && row2 != null && !row2.containsKey("raw"),
                        "body=" + r.body + " none=" + r2.body);
            }
        });
        scenario("ears_probe_clamps_seconds_and_reports_a_busy_microphone", new Scenario() {
            public void run(String n) throws Exception {
                Probe p = new Probe().armed("abc");
                Resp big = probePost(p, "abc", 999, null);
                int clamped = p.runner.seconds;
                Resp none = probeRequest(p, "POST", "t=" + p.token.issue() + "&nonce=abc");
                int defaulted = p.runner.seconds;
                p.runner.fail = new IllegalStateException(ListenSession.REFUSE_BUSY);
                Resp busy = probePost(p, "abc", 2, null);
                check(n, big.code() == 200 && clamped == EarsProbe.MAX_SECONDS && none.code() == 200
                                && defaulted == EarsProbe.DEFAULT_SECONDS && busy.code() == 503
                                && busy.body.contains(ListenSession.REFUSE_BUSY),
                        "big=" + big.code() + " clamped=" + clamped + " defaulted=" + defaulted
                                + " busy=" + busy.code() + " " + busy.body);
            }
        });
        scenario("ears_probe_path_is_tls_only", new Scenario() {
            public void run(String n) {
                check(n, LauncherProtocol.isTlsOnlyPath(LauncherProtocol.SETTINGS_EARS_PROBE_PATH),
                        "the probe path is served on plain HTTP");
            }
        });

        // ---- Conversation section (meeting plan U4; KTD11, R5, R20) ----

        scenario("conversation_section_shows_default_persona_and_switch_on", new Scenario() {
            public void run(String n) throws Exception {
                String html = get(new Fixture());
                String section = section(html, "conversation");
                String box = textarea(section, "persona");
                String sw = input(section, "answers");
                check(n, box.contains(SettingsPage.escapeHtml(ClaudeSettings.DEFAULT_PERSONA))
                                && sw.contains("type=\"checkbox\"") && sw.contains(" checked")
                                && section.contains(String.valueOf(ConversationSettings.MAX_PERSONA_CHARS))
                                && html.indexOf("<section id=\"conversation\">") > html.indexOf("<section id=\"claude\">"),
                        "box=" + box + " switch=" + sw);
            }
        });

        scenario("conversation_section_shows_stored_persona_escaped_and_never_the_key", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = withKey();
                f.settings.saveConversation("<script>alert(1)</script> & \"quotes\"\nline two", false);
                String html = get(f);
                String box = textarea(section(html, "conversation"), "persona");
                check(n, box.contains("&lt;script&gt;alert(1)&lt;/script&gt; &amp; &quot;quotes&quot;\nline two")
                                && !html.contains("<script>") && !html.contains(KEY_A)
                                && !input(section(html, "conversation"), "answers").contains(" checked"),
                        "box=" + box);
            }
        });

        scenario("conversation_form_carries_token_persona_and_switch", new Scenario() {
            public void run(String n) throws Exception {
                String html = get(new Fixture());
                List<String> names = fieldNames(form(html, LauncherProtocol.SETTINGS_CONVERSATION_PATH));
                check(n, names.equals(Arrays.asList("t", "persona", "answers")), names.toString());
            }
        });

        scenario("conversation_save_stores_persona_and_switch", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                Resp r = request(f, "POST", LauncherProtocol.SETTINGS_CONVERSATION_PATH, "",
                        "t=" + token(f) + "&persona=" + enc("Dry wit.\r\nKind.") + "&answers=on");
                ConversationSettings c = f.settings.conversation();
                check(n, r.code() == 302 && SettingsPage.CONVERSATION_SAVED.equals(r.status())
                                && "Dry wit.\nKind.".equals(c.persona) && c.personaSet && c.answersWhenSpokenTo
                                && get(f).contains("Dry wit.\nKind."),
                        r.head + " " + c);
            }
        });

        scenario("conversation_save_switch_off_when_box_unchecked", new Scenario() {
            public void run(String n) throws Exception {
                // An unchecked checkbox is absent from the post: that is "off".
                Fixture f = new Fixture();
                request(f, "POST", LauncherProtocol.SETTINGS_CONVERSATION_PATH, "",
                        "t=" + token(f) + "&persona=" + enc("Dry wit."));
                boolean off = !f.settings.conversation().answersWhenSpokenTo;
                request(f, "POST", LauncherProtocol.SETTINGS_CONVERSATION_PATH, "",
                        "t=" + token(f) + "&persona=" + enc("Dry wit.") + "&answers=on");
                boolean on = f.settings.conversation().answersWhenSpokenTo;
                check(n, off && on, "off=" + off + " on=" + on);
            }
        });

        scenario("conversation_save_over_cap_refused_and_unchanged", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                f.settings.saveConversation("Keep me.", true);
                Resp r = request(f, "POST", LauncherProtocol.SETTINGS_CONVERSATION_PATH, "",
                        "t=" + token(f) + "&persona=" + repeat('x', ConversationSettings.MAX_PERSONA_CHARS + 1)
                                + "&answers=on");
                String status = r.status();
                check(n, status != null && status.startsWith("Not saved") && !status.contains("xxxx")
                                && "Keep me.".equals(f.settings.conversation().persona),
                        "status=" + status + " persona=" + f.settings.conversation().persona);
            }
        });

        scenario("conversation_save_blank_resets_to_default", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                f.settings.saveConversation("Custom.", true);
                Resp r = request(f, "POST", LauncherProtocol.SETTINGS_CONVERSATION_PATH, "",
                        "t=" + token(f) + "&persona=" + enc("  \r\n ") + "&answers=on");
                ConversationSettings c = f.settings.conversation();
                check(n, SettingsPage.CONVERSATION_DEFAULT.equals(r.status()) && !c.personaSet
                                && ClaudeSettings.DEFAULT_PERSONA.equals(c.persona),
                        "status=" + r.status() + " " + c);
            }
        });

        scenario("conversation_save_with_stale_token_rejected", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                Resp none = request(f, "POST", LauncherProtocol.SETTINGS_CONVERSATION_PATH, "",
                        "persona=" + enc("Nope.") + "&answers=on");
                Resp stale = request(f, "POST", LauncherProtocol.SETTINGS_CONVERSATION_PATH, "",
                        "t=0000&persona=" + enc("Nope.") + "&answers=on");
                ConversationSettings c = f.settings.conversation();
                check(n, none.code() == 302 && stale.code() == 302 && !c.personaSet
                                && none.status().startsWith("Nothing changed") && stale.status().startsWith("Nothing changed"),
                        none.head + " " + stale.head + " " + c);
            }
        });

        scenario("conversation_status_never_echoes_persona", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                String marker = "ZQXJK-marker";
                Resp ok = request(f, "POST", LauncherProtocol.SETTINGS_CONVERSATION_PATH, "",
                        "t=" + token(f) + "&persona=" + enc(marker) + "&answers=on");
                Resp big = request(f, "POST", LauncherProtocol.SETTINGS_CONVERSATION_PATH, "",
                        "t=" + token(f) + "&persona=" + marker + repeat('x', ConversationSettings.MAX_PERSONA_CHARS));
                check(n, !ok.head.contains(marker) && !big.head.contains(marker), ok.head + " " + big.head);
            }
        });

        scenario("get_on_conversation_path_refused", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                Resp r = request(f, "GET", LauncherProtocol.SETTINGS_CONVERSATION_PATH, "persona=Nope&answers=on", null);
                check(n, r.code() == 405 && !f.settings.conversation().personaSet, r.head);
            }
        });

        scenario("conversation_path_is_tls_only", new Scenario() {
            public void run(String n) {
                check(n, LauncherProtocol.isTlsOnlyPath(LauncherProtocol.SETTINGS_CONVERSATION_PATH),
                        "the conversation path is served on plain HTTP");
            }
        });

        // ---- People notes (meeting plan U5; KTD10, R18) ----

        scenario("people_notes_render_escaped", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                String id = f.people.add(jpeg(1), "Ann");
                f.people.mergeNotes(id, "{\"interests\":[\"<script>alert(1)</script>\"],"
                        + "\"open_threads\":[\"\\\"><img src=x onerror=alert(2)>\"],"
                        + "\"topics\":[\"a & b\"],\"questions_asked\":[\"What's <your> name?\"]}");
                String html = get(f);
                String article = article(html, id);
                check(n, article.contains("&lt;script&gt;alert(1)&lt;/script&gt;") && !html.contains("<script>")
                                && article.contains("&quot;&gt;&lt;img src=x onerror=alert(2)&gt;") && !html.contains("<img src=x")
                                && article.contains("a &amp; b") && article.contains("What&#39;s &lt;your&gt; name?"),
                        article);
            }
        });

        scenario("people_notes_list_fields_and_thread_dates", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                String ann = f.people.add(jpeg(1), "Ann");
                String bo = f.people.add(jpeg(2), "Bo");
                f.people.mergeNotes(ann, "{\"interests\":[\"chess\"],\"open_threads\":[\"the Q3 launch\"],"
                        + "\"topics\":[\"weather\"],\"questions_asked\":[\"How was the launch?\"]}");
                String html = get(f);
                String a = article(html, ann);
                String b = article(html, bo);
                String since = SettingsPage.lastSeen(f.peopleClock.now).substring(0, 10);
                boolean fields = a.contains("Interests") && a.contains("chess") && a.contains("Open threads")
                        && a.contains("the Q3 launch") && a.contains(since) && a.contains("Topics") && a.contains("weather")
                        && a.contains("Questions asked") && a.contains("How was the launch?");
                boolean none = b.contains(SettingsPage.PEOPLE_NO_NOTES) && !a.contains(SettingsPage.PEOPLE_NO_NOTES);
                check(n, fields && none, "a=" + a + " b=" + b);
            }
        });

        scenario("people_nameless_record_marked_legacy", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                String ann = f.people.add(jpeg(1), "Ann");
                String legacy = legacyNameless(f.people, jpeg(2));
                String html = get(f);
                String a = article(html, ann);
                String l = article(html, legacy);
                check(n, l.contains(SettingsPage.PEOPLE_LEGACY) && l.contains("unnamed")
                                && !form(l, LauncherProtocol.SETTINGS_PEOPLE_FORGET_PATH).isEmpty()
                                && !form(l, LauncherProtocol.SETTINGS_PEOPLE_RENAME_PATH).isEmpty()
                                && !a.contains(SettingsPage.PEOPLE_LEGACY),
                        "legacy=" + l);
            }
        });

        scenario("forget_removes_notes_too", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                String id = f.people.add(jpeg(1), "Ann");
                f.people.mergeNotes(id, "{\"interests\":[\"chess-ZQXJ\"]}");
                boolean shown = get(f).contains("chess-ZQXJ");
                Resp r = request(f, "POST", LauncherProtocol.SETTINGS_PEOPLE_FORGET_PATH, "",
                        "t=" + token(f) + "&id=" + id);
                String html = get(f);
                check(n, shown && SettingsPage.PEOPLE_FORGOTTEN.equals(r.status()) && f.people.notes(id).isEmpty()
                                && f.people.nameOf(id) == null && !html.contains("chess-ZQXJ") && !html.contains(id),
                        r.head + " " + html);
            }
        });

        // ---- face plan U5 (KTD5, KTD8; R2, R13-R15, R17, R19) ----
        scenario("face_checks_section_empty", new Scenario() {
            public void run(String n) throws Exception {
                String sec = section(get(new Fixture()), "face-checks");
                check(n, sec.contains("Face checks") && sec.contains(SettingsPage.CHECKS_EMPTY), sec);
            }
        });

        scenario("face_check_renders_crop_photo_name_score_band_and_outcome_escaped", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                String id = personWithPhotos(f, "<b>Sam & Co</b>", 2);
                long h = f.checks.record(matchCheck(f, FaceCheck.CONFIDENT, id, 1, 0.6149f));
                f.checks.updateOutcome(h, FaceCheck.YES, null);
                String a = checkArticle(get(f), h);
                check(n, a.contains(LauncherProtocol.SETTINGS_FACE_CHECK_CROP_PATH + "?id=" + h)
                                && a.contains(LauncherProtocol.SETTINGS_PEOPLE_PHOTO_PATH + "?"
                                        + photoQuery(id, 1, addedAt(f, id, 1)).replace("&", "&amp;"))
                                && a.contains("&lt;b&gt;Sam &amp; Co&lt;/b&gt;") && !a.contains("<b>Sam")
                                && a.contains("0.61") && !a.contains("0.6149")
                                && a.contains(SettingsPage.decisionText(FaceCheck.CONFIDENT, 0))
                                && a.contains(SettingsPage.outcomeText(FaceCheck.YES)),
                        a);
            }
        });

        scenario("rejected_check_shows_reason_and_no_best_match_photo", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                personWithPhotos(f, "Sarah", 1);
                long h = f.checks.record(new FaceCheck(FaceCheck.REJECTED, FaceCheck.TOO_DARK, jpeg(41), "", -1, 0,
                        0f, "", 0f, false));
                String a = checkArticle(get(f), h);
                check(n, a.contains("too dark") && a.contains(LauncherProtocol.SETTINGS_FACE_CHECK_CROP_PATH)
                        && !a.contains(LauncherProtocol.SETTINGS_PEOPLE_PHOTO_PATH) && !a.contains("Sarah"), a);
            }
        });

        scenario("not_ready_and_no_face_checks_render_decision_and_no_face_has_no_crop", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                long notReady = f.checks.record(new FaceCheck(FaceCheck.NOT_READY, 0, jpeg(42), "", -1, 0, 0f, "",
                        0f, false));
                long noFace = f.checks.record(new FaceCheck(FaceCheck.NO_FACE, 0, null, "", -1, 0, 0f, "", 0f,
                        false));
                String html = get(f);
                String a = checkArticle(html, notReady);
                String b = checkArticle(html, noFace);
                check(n, a.contains(SettingsPage.decisionText(FaceCheck.NOT_READY, 0))
                                && a.contains(LauncherProtocol.SETTINGS_FACE_CHECK_CROP_PATH)
                                && b.contains(SettingsPage.decisionText(FaceCheck.NO_FACE, 0))
                                && !b.contains(LauncherProtocol.SETTINGS_FACE_CHECK_CROP_PATH)
                                && !b.contains("<img") && html.indexOf("check-" + noFace) < html.indexOf("check-" + notReady),
                        a + "\n" + b);
            }
        });

        scenario("near_tie_check_shows_runner_up_name_and_score", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                String sarah = personWithPhotos(f, "Sarah", 1);
                String sara = personWithPhotos(f, "Sara <i>", 1);
                long h = f.checks.record(new FaceCheck(FaceCheck.CLOSE, 0, jpeg(43), sarah, 0,
                        addedAt(f, sarah, 0), 0.55f, sara, 0.53f, true));
                String a = checkArticle(get(f), h);
                check(n, a.contains("Sarah") && a.contains("Sara &lt;i&gt;") && a.contains("0.53")
                        && a.contains(SettingsPage.NEAR_TIE), a);
            }
        });

        scenario("replaced_best_match_slot_shows_marker_not_new_photo", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                String id = personWithPhotos(f, "Sarah", PeopleStore.MAX_PHOTOS);
                long oldAt = addedAt(f, id, 0);
                long h = f.checks.record(matchCheck(f, FaceCheck.CLOSE, id, 0, 0.4f));
                f.peopleClock.now += 60_000;
                int slot = f.people.addPhoto(id, jpeg(99), MODEL, emb(0.9f));
                String a = checkArticle(get(f), h);
                Resp r = request(f, "GET", LauncherProtocol.SETTINGS_PEOPLE_PHOTO_PATH, photoQuery(id, 0, oldAt), null);
                check(n, slot == 0 && a.contains(SettingsPage.CHECK_PHOTO_REPLACED)
                                && !a.contains(LauncherProtocol.SETTINGS_PEOPLE_PHOTO_PATH) && r.code() == 200
                                && r.head.toLowerCase().contains("image/svg+xml") && !Arrays.equals(r.bytes, jpeg(99))
                                && r.body.contains("replaced"),
                        a + " " + r.head);
            }
        });

        scenario("photo_route_serves_the_photo_only_while_added_at_matches", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                String id = personWithPhotos(f, "Sam", 2);
                long at = addedAt(f, id, 1);
                Resp ok = request(f, "GET", LauncherProtocol.SETTINGS_PEOPLE_PHOTO_PATH, photoQuery(id, 1, at), null);
                Resp wrongAt = request(f, "GET", LauncherProtocol.SETTINGS_PEOPLE_PHOTO_PATH,
                        photoQuery(id, 1, at + 1), null);
                Resp noAt = request(f, "GET", LauncherProtocol.SETTINGS_PEOPLE_PHOTO_PATH, "id=" + id + "&slot=1",
                        null);
                Resp gone = request(f, "GET", LauncherProtocol.SETTINGS_PEOPLE_PHOTO_PATH, photoQuery(id, 4, 0), null);
                Resp badId = request(f, "GET", LauncherProtocol.SETTINGS_PEOPLE_PHOTO_PATH,
                        photoQuery("../people.index", 1, at), null);
                Resp post = request(f, "POST", LauncherProtocol.SETTINGS_PEOPLE_PHOTO_PATH, photoQuery(id, 1, at),
                        "t=" + token(f));
                check(n, ok.code() == 200 && Arrays.equals(ok.bytes, jpeg(11))
                                && wrongAt.head.contains("svg") && !Arrays.equals(wrongAt.bytes, jpeg(11))
                                && noAt.code() == 404 && gone.head.contains("svg") && badId.code() == 404
                                && post.code() == 405,
                        ok.head + "|" + wrongAt.head + "|" + noAt.head + "|" + gone.head + "|" + badId.head);
            }
        });

        scenario("check_crop_route_serves_jpeg_and_404s_unknown", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                long h = f.checks.record(new FaceCheck(FaceCheck.WEAK, 0, jpeg(44), "", -1, 0, 0.1f, "", 0f, false));
                Resp ok = request(f, "GET", LauncherProtocol.SETTINGS_FACE_CHECK_CROP_PATH, "id=" + h, null);
                Resp unknown = request(f, "GET", LauncherProtocol.SETTINGS_FACE_CHECK_CROP_PATH, "id=" + (h + 7), null);
                Resp bad = request(f, "GET", LauncherProtocol.SETTINGS_FACE_CHECK_CROP_PATH, "id=abc", null);
                Resp none = request(f, "GET", LauncherProtocol.SETTINGS_FACE_CHECK_CROP_PATH, "", null);
                check(n, ok.code() == 200 && ok.head.toLowerCase().contains("image/jpeg")
                                && Arrays.equals(ok.bytes, jpeg(44)) && unknown.code() == 404 && bad.code() == 404
                                && none.code() == 404,
                        ok.head + "|" + unknown.head + "|" + bad.head);
            }
        });

        scenario("forget_on_page_purges_face_checks", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                String sarah = personWithPhotos(f, "Sarah", 1);
                String tom = personWithPhotos(f, "Tom", 1);
                long matched = f.checks.record(matchCheck(f, FaceCheck.CONFIDENT, sarah, 0, 0.7f));
                long joined = f.checks.record(matchCheck(f, FaceCheck.CLOSE, tom, 0, 0.4f));
                f.checks.updateOutcome(joined, FaceCheck.JOINED, sarah);
                long kept = f.checks.record(matchCheck(f, FaceCheck.CONFIDENT, tom, 0, 0.8f));
                Resp r = request(f, "POST", LauncherProtocol.SETTINGS_PEOPLE_FORGET_PATH, "",
                        "t=" + token(f) + "&id=" + sarah);
                String html = get(f);
                Resp crop = request(f, "GET", LauncherProtocol.SETTINGS_FACE_CHECK_CROP_PATH, "id=" + matched, null);
                check(n, SettingsPage.PEOPLE_FORGOTTEN.equals(r.status()) && f.checks.list().size() == 1
                                && f.checks.list().get(0).handle == kept && checkArticle(html, matched).isEmpty()
                                && checkArticle(html, joined).isEmpty() && !html.contains("Sarah")
                                && crop.code() == 404,
                        r.head + " " + f.checks.list());
            }
        });

        scenario("thresholds_block_is_read_only_with_current_values", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                f.settings.saveFace(new FaceSettings(0.55f, 0.4f, 0.06f, 50, 38, 95, 31.5));
                String sec = section(get(f), "face-thresholds");
                check(n, sec.contains("0.55") && sec.contains("0.4") && sec.contains("0.06") && sec.contains("50")
                                && sec.contains("38") && sec.contains("95") && sec.contains("31.5")
                                && !sec.contains("<form") && !sec.contains("<input"),
                        sec);
            }
        });

        scenario("threshold_save_from_loopback_stores_values", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                Resp r = thresholds(f, "&confident=0.6&close=0.41&margin=0.04&min_width=56&dark_floor=35"
                        + "&dim_level=100&blur_floor=25.5", true);
                FaceSettings s = f.settings.faceSettings();
                // A partial save keeps the fields it leaves out.
                Resp partial = thresholds(f, "&confident=0.65", true);
                FaceSettings s2 = f.settings.faceSettings();
                check(n, SettingsPage.FACE_THRESHOLDS_SAVED.equals(r.status()) && s.confident == 0.6f
                                && s.close == 0.41f && s.margin == 0.04f && s.minWidth == 56 && s.darkFloor == 35
                                && s.dimLevel == 100 && s.blurFloor == 25.5
                                && SettingsPage.FACE_THRESHOLDS_SAVED.equals(partial.status())
                                && s2.confident == 0.65f && s2.close == 0.41f && s2.minWidth == 56,
                        r.head + " " + s + " " + s2);
            }
        });

        scenario("threshold_save_from_non_loopback_refused", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                Resp r = thresholds(f, "&confident=0&close=0", false);
                check(n, r.code() == 403 && r.body.contains(SettingsPage.FACE_THRESHOLDS_LOOPBACK_ONLY)
                        && snapshot(f).equals(snapshot(new Fixture()))
                        && f.settings.faceSettings().confident == 0.5f, r.head + " " + r.body);
            }
        });

        scenario("threshold_save_invalid_refused_and_unchanged", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                String before = snapshot(f);
                Resp bands = thresholds(f, "&confident=0.5&close=0.6", true);
                Resp luma = thresholds(f, "&dark_floor=95&dim_level=90", true);
                Resp word = thresholds(f, "&margin=%3Cscript%3E", true);
                check(n, ("Not saved: " + ClaudeSettings.FACE_REFUSE_BANDS).equals(bands.status())
                                && ("Not saved: " + ClaudeSettings.FACE_REFUSE_LUMA).equals(luma.status())
                                && SettingsPage.FACE_NOT_A_NUMBER.equals(word.status())
                                && before.equals(snapshot(f)),
                        bands.status() + " | " + luma.status() + " | " + word.status());
            }
        });

        scenario("threshold_save_needs_the_page_token", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                String before = snapshot(f);
                Resp r = request(f, "POST", LauncherProtocol.SETTINGS_FACE_THRESHOLDS_PATH, "", "confident=0.7", true);
                check(n, r.status() != null && r.status().contains("expired") && before.equals(snapshot(f)),
                        r.head);
            }
        });

        scenario("photo_strip_shows_every_photo_and_marks_unusable", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                String id = personWithPhotos(f, "Sam", 3);
                f.people.markUnusable(id, 2, addedAt(f, id, 2));
                String a = article(get(f), id);
                int imgs = 0;
                Matcher m = Pattern.compile(Pattern.quote(LauncherProtocol.SETTINGS_PEOPLE_PHOTO_PATH + "?id=" + id))
                        .matcher(a);
                while (m.find()) {
                    imgs++;
                }
                String del = form(a, LauncherProtocol.SETTINGS_PEOPLE_PHOTO_DELETE_PATH);
                check(n, imgs == 3 && a.contains(SettingsPage.PHOTO_UNUSABLE)
                                && a.indexOf(SettingsPage.PHOTO_UNUSABLE) > a.indexOf("slot=2")
                                && fieldNames(del).equals(Arrays.asList("t", "id", "slot")),
                        a);
            }
        });

        scenario("delete_photo_removes_slot_and_embedding", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                String id = personWithPhotos(f, "Sam", 3);
                Resp r = request(f, "POST", LauncherProtocol.SETTINGS_PEOPLE_PHOTO_DELETE_PATH, "",
                        "t=" + token(f) + "&id=" + id + "&slot=1");
                List<Integer> slots = new ArrayList<Integer>();
                for (PeopleStore.Photo p : f.people.photos(id)) {
                    slots.add(p.slot);
                }
                boolean inGallery = false;
                for (PeopleStore.Photo p : f.people.gallery()) {
                    inGallery |= p.id.equals(id) && p.slot == 1;
                }
                check(n, SettingsPage.PHOTO_DELETED.equals(r.status()) && slots.equals(Arrays.asList(0, 2))
                        && f.people.photo(id, 1) == null && !inGallery, r.head + " slots=" + slots);
            }
        });

        scenario("delete_last_photo_refused", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                String id = personWithPhotos(f, "Sam", 1);
                Resp r = request(f, "POST", LauncherProtocol.SETTINGS_PEOPLE_PHOTO_DELETE_PATH, "",
                        "t=" + token(f) + "&id=" + id + "&slot=0");
                check(n, SettingsPage.PHOTO_LAST.equals(r.status()) && f.people.photo(id, 0) != null
                        && form(article(get(f), id), LauncherProtocol.SETTINGS_PEOPLE_PHOTO_DELETE_PATH).isEmpty(),
                        r.head);
            }
        });

        scenario("delete_photo_unknown_or_stale_token_changes_nothing", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                String id = personWithPhotos(f, "Sam", 2);
                Resp stale = request(f, "POST", LauncherProtocol.SETTINGS_PEOPLE_PHOTO_DELETE_PATH, "",
                        "t=deadbeef&id=" + id + "&slot=1");
                Resp empty = request(f, "POST", LauncherProtocol.SETTINGS_PEOPLE_PHOTO_DELETE_PATH, "",
                        "t=" + token(f) + "&id=" + id + "&slot=3");
                Resp junk = request(f, "POST", LauncherProtocol.SETTINGS_PEOPLE_PHOTO_DELETE_PATH, "",
                        "t=" + token(f) + "&id=" + id + "&slot=%3Cx%3E");
                Resp who = request(f, "POST", LauncherProtocol.SETTINGS_PEOPLE_PHOTO_DELETE_PATH, "",
                        "t=" + token(f) + "&id=0123456789abcdef&slot=0");
                check(n, stale.status().contains("expired") && SettingsPage.PHOTO_UNKNOWN.equals(empty.status())
                                && SettingsPage.PHOTO_UNKNOWN.equals(junk.status())
                                && SettingsPage.PHOTO_UNKNOWN.equals(who.status()) && f.people.photos(id).size() == 2,
                        stale.status() + "|" + empty.status() + "|" + junk.status() + "|" + who.status());
            }
        });

        scenario("face_state_refuses_missing_or_wrong_token", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                personWithPhotos(f, "Sarah", 1);
                Resp none = request(f, "POST", LauncherProtocol.SETTINGS_FACE_STATE_PATH, "", "", true);
                Resp wrong = request(f, "POST", LauncherProtocol.SETTINGS_FACE_STATE_PATH, "", "t=deadbeef", true);
                Resp viaGet = request(f, "GET", LauncherProtocol.SETTINGS_FACE_STATE_PATH, "t=" + token(f), null, true);
                check(n, none.code() == 403 && wrong.code() == 403 && viaGet.code() == 405
                                && !none.body.contains("Sarah") && !wrong.body.contains("Sarah"),
                        none.head + "|" + wrong.head + "|" + viaGet.head);
            }
        });

        scenario("face_state_carries_checks_people_and_thresholds_without_images", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                String sarah = personWithPhotos(f, "Sarah \"S\"", 3);
                String tom = personWithPhotos(f, "Tom", 1);
                f.people.markUnusable(sarah, 2, addedAt(f, sarah, 2));
                long h = f.checks.record(new FaceCheck(FaceCheck.CLOSE, 0, jpeg(45), sarah, 1, addedAt(f, sarah, 1),
                        0.45f, tom, 0.43f, true));
                f.checks.updateOutcome(h, FaceCheck.NAME_GIVEN, null);
                f.checks.record(new FaceCheck(FaceCheck.REJECTED, FaceCheck.TOO_SMALL, jpeg(46), "", -1, 0, 0f, "", 0f,
                        false));
                Resp r = request(f, "POST", LauncherProtocol.SETTINGS_FACE_STATE_PATH, "", "t=" + token(f));
                @SuppressWarnings("unchecked")
                Map<String, Object> root = (Map<String, Object>) Json.parse(r.body);
                @SuppressWarnings("unchecked")
                List<Object> checks = (List<Object>) root.get("checks");
                @SuppressWarnings("unchecked")
                Map<String, Object> rejected = (Map<String, Object>) checks.get(0);
                @SuppressWarnings("unchecked")
                Map<String, Object> close = (Map<String, Object>) checks.get(1);
                @SuppressWarnings("unchecked")
                List<Object> people = (List<Object>) root.get("people");
                @SuppressWarnings("unchecked")
                Map<String, Object> th = (Map<String, Object>) root.get("thresholds");
                Map<String, Object> sarahRow = null;
                for (Object o : people) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> p = (Map<String, Object>) o;
                    if (sarah.equals(p.get("id"))) {
                        sarahRow = p;
                    }
                }
                boolean noImages = !r.body.contains("\u00ff\u00d8") && !r.body.toLowerCase().contains("jpeg")
                        && r.body.length() < 4000;
                check(n, r.code() == 200 && r.head.toLowerCase().contains("application/json") && noImages
                                && checks.size() == 2 && "rejected".equals(rejected.get("decision"))
                                && "too small".equals(rejected.get("reason"))
                                && "close".equals(close.get("decision")) && "Sarah \"S\"".equals(close.get("best_name"))
                                && sarah.equals(close.get("best_id")) && "Tom".equals(close.get("runner_up_name"))
                                && Boolean.TRUE.equals(close.get("near_tie"))
                                && "name given".equals(close.get("outcome"))
                                && Math.abs(((Number) close.get("score")).doubleValue() - 0.45) < 1e-6
                                && people.size() == 2 && sarahRow != null && "Sarah \"S\"".equals(sarahRow.get("name"))
                                && ((Number) sarahRow.get("photos")).intValue() == 3
                                && ((Number) sarahRow.get("unusable")).intValue() == 1
                                && ((Number) th.get("confident")).doubleValue() == 0.5
                                && ((Number) th.get("close")).doubleValue() == 0.363
                                && ((Number) th.get("min_width")).intValue() == 48,
                        r.head + "\n" + r.body);
            }
        });

        scenario("face_paths_are_tls_only", new Scenario() {
            public void run(String n) {
                check(n, LauncherProtocol.isTlsOnlyPath(LauncherProtocol.SETTINGS_FACE_CHECK_CROP_PATH)
                                && LauncherProtocol.isTlsOnlyPath(LauncherProtocol.SETTINGS_PEOPLE_PHOTO_PATH)
                                && LauncherProtocol.isTlsOnlyPath(LauncherProtocol.SETTINGS_PEOPLE_PHOTO_DELETE_PATH)
                                && LauncherProtocol.isTlsOnlyPath(LauncherProtocol.SETTINGS_FACE_THRESHOLDS_PATH)
                                && LauncherProtocol.isTlsOnlyPath(LauncherProtocol.SETTINGS_FACE_STATE_PATH),
                        "a face path is served on plain HTTP");
            }
        });

        scenario("get_on_face_action_paths_refused", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                String id = personWithPhotos(f, "Sam", 2);
                Resp del = request(f, "GET", LauncherProtocol.SETTINGS_PEOPLE_PHOTO_DELETE_PATH,
                        "t=" + token(f) + "&id=" + id + "&slot=1", null);
                Resp th = request(f, "GET", LauncherProtocol.SETTINGS_FACE_THRESHOLDS_PATH,
                        "t=" + token(f) + "&confident=0.9", null, true);
                check(n, del.code() == 405 && th.code() == 405 && f.people.photos(id).size() == 2
                        && f.settings.faceSettings().confident == 0.5f, del.head + "|" + th.head);
            }
        });

        // ---- Owner 2026-10-02: the feedback log ----
        scenario("feedback_section_lists_entries_newest_first", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                String sarah = personWithPhotos(f, "Sarah Connor", 1);
                f.people.recordFeedback(sarah, com.miko3.shared.Feedback.of("complaint", "He keeps interrupting.",
                        "you talk over me every time"), "in a call's conversation");
                f.peopleClock.now += 60_000;
                f.people.recordFeedback(null, com.miko3.shared.Feedback.of("suggestion", "Learn <b>names</b> faster.",
                        "learn names & faces"), "while docked");
                String sec = section(get(f), "feedback");
                int newer = sec.indexOf("Learn &lt;b&gt;names&lt;/b&gt; faster.");
                int older = sec.indexOf("He keeps interrupting.");
                check(n, sec.contains("Feedback from conversations") && newer > 0 && older > newer
                                && sec.contains("you talk over me every time") && sec.contains("learn names &amp; faces")
                                && sec.contains("Sarah") && !sec.contains("Connor") && sec.contains("someone")
                                && sec.contains("while docked") && sec.contains("complaint") && sec.contains("suggestion")
                                && sec.contains("2 entries") && !sec.contains(sarah) && !sec.contains("<b>names")
                                && !form(sec, LauncherProtocol.SETTINGS_FEEDBACK_CLEAR_PATH).isEmpty(),
                        sec);
            }
        });
        scenario("feedback_section_says_when_there_is_none", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                String sec = section(get(f), "feedback");
                check(n, sec.contains(SettingsPage.FEEDBACK_EMPTY), sec);
            }
        });
        scenario("feedback_clear_needs_the_token_and_empties_the_log", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                f.people.recordFeedback(null, com.miko3.shared.Feedback.of("bug", "He froze.", ""), "x");
                Resp stale = request(f, "POST", LauncherProtocol.SETTINGS_FEEDBACK_CLEAR_PATH, "", "t=deadbeef");
                int afterStale = f.people.feedback().size();
                Resp get = request(f, "GET", LauncherProtocol.SETTINGS_FEEDBACK_CLEAR_PATH, "t=" + token(f), null);
                int afterGet = f.people.feedback().size();
                Resp ok = action(f, LauncherProtocol.SETTINGS_FEEDBACK_CLEAR_PATH);
                String sec = section(get(f), "feedback");
                check(n, afterStale == 1 && afterGet == 1 && get.code() == 405
                                && SettingsPage.FEEDBACK_CLEARED.equals(ok.status())
                                && f.people.feedback().size() == 0 && sec.contains(SettingsPage.FEEDBACK_EMPTY),
                        stale.head + " | " + ok.head);
            }
        });
        scenario("feedback_state_needs_the_token_and_carries_entries_newest_first_without_ids", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                String tom = personWithPhotos(f, "Tom", 1);
                f.people.recordFeedback(tom, com.miko3.shared.Feedback.of("praise", "Likes his voice.", "nice voice"),
                        "while roaming");
                f.peopleClock.now += 1000;
                f.people.recordFeedback(null, com.miko3.shared.Feedback.of("bug", "He froze.", ""), "x");
                Resp refused = request(f, "POST", LauncherProtocol.SETTINGS_FEEDBACK_STATE_PATH, "", "t=nope");
                Resp r = request(f, "POST", LauncherProtocol.SETTINGS_FEEDBACK_STATE_PATH, "", "t=" + token(f));
                @SuppressWarnings("unchecked")
                Map<String, Object> root = (Map<String, Object>) Json.parse(r.body);
                @SuppressWarnings("unchecked")
                List<Object> entries = (List<Object>) root.get("entries");
                @SuppressWarnings("unchecked")
                Map<String, Object> first = (Map<String, Object>) entries.get(0);
                @SuppressWarnings("unchecked")
                Map<String, Object> second = (Map<String, Object>) entries.get(1);
                check(n, refused.code() == 403 && r.code() == 200 && entries.size() == 2
                                && "He froze.".equals(first.get("summary")) && "someone".equals(first.get("who"))
                                && "Likes his voice.".equals(second.get("summary")) && "Tom".equals(second.get("who"))
                                && "nice voice".equals(second.get("quote")) && "while roaming".equals(second.get("context"))
                                && "praise".equals(second.get("kind")) && second.get("at") instanceof Number
                                && !r.body.contains(tom) && !second.containsKey("person"),
                        refused.head + " " + r.body);
            }
        });
        scenario("forget_on_page_deletes_their_feedback", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                String sarah = personWithPhotos(f, "Sarah", 1);
                f.people.recordFeedback(sarah, com.miko3.shared.Feedback.of("complaint", "Too loud.", "too loud"), "x");
                f.people.recordFeedback(null, com.miko3.shared.Feedback.of("bug", "He froze.", ""), "x");
                Resp r = request(f, "POST", LauncherProtocol.SETTINGS_PEOPLE_FORGET_PATH, "",
                        "t=" + token(f) + "&id=" + sarah);
                String sec = section(get(f), "feedback");
                check(n, SettingsPage.PEOPLE_FORGOTTEN.equals(r.status()) && f.people.feedback().size() == 1
                                && !sec.contains("Too loud.") && sec.contains("He froze."),
                        sec);
            }
        });

        if (failures > 0) {
            System.exit(1);
        }
    }
}
