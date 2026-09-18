package com.miko3.mode.voice;

import com.miko3.shared.HttpRequest;
import com.miko3.shared.HttpResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Host-JVM checks for the voice mode's settings page (U6, KTD10), driven by
 * scripts/tests/test_voice_settings.py. Lives in the mode's own package so it
 * can reach the package-private helpers; compiles against mode-voice/src and
 * shared/src only (none of the classes under test touch android.*).
 *
 * Prints one "PASS <name>" or "FAIL <name>: <detail>" line per scenario; the
 * Python side asserts on each by name.
 */
public final class VoiceSettingsHarness {
    private static final Pattern TOKEN = Pattern.compile("name=\"t\" value=\"([^\"]*)\"");

    /** In-memory stand-in for the SharedPreferences-backed store. */
    static final class MemStore implements VoiceSettings.Store {
        final Map<String, Object> values = new HashMap<String, Object>();
        int writes;

        @Override
        public String getString(String key, String def) {
            Object v = values.get(key);
            return v instanceof String ? (String) v : def;
        }

        @Override
        public boolean getBoolean(String key, boolean def) {
            Object v = values.get(key);
            return v instanceof Boolean ? (Boolean) v : def;
        }

        @Override
        public int getInt(String key, int def) {
            Object v = values.get(key);
            return v instanceof Integer ? (Integer) v : def;
        }

        @Override
        public void putString(String key, String value) {
            writes++;
            values.put(key, value);
        }

        @Override
        public void putBoolean(String key, boolean value) {
            writes++;
            values.put(key, value);
        }
    }

    static final class Recorder implements VoiceSettings.RelayAddressListener {
        final List<String> seen = new ArrayList<String>();

        @Override
        public void onRelayAddressChanged(String newAddress) {
            seen.add(newAddress);
        }
    }

    /** A fresh page: store pre-seeded with a known-good address, one GET done. */
    static final class Fixture {
        final MemStore store = new MemStore();
        final VoiceSettings settings;
        final PageToken token = new PageToken();
        final Recorder listener = new Recorder();

        Fixture() {
            store.values.put(VoiceSettings.KEY_RELAY_ADDRESS, "192.168.1.10:8790");
            settings = new VoiceSettings(store);
            settings.addRelayAddressListener(listener);
        }

        String get(String query) throws Exception {
            Resp r = request(this, "GET", "/", query, null);
            Matcher m = TOKEN.matcher(r.body);
            return m.find() ? m.group(1) : null;
        }
    }

    static final class Resp {
        String head;
        String body;

        String location() {
            for (String line : head.split("\r\n")) {
                if (line.toLowerCase().startsWith("location:")) {
                    return line.substring(9).trim();
                }
            }
            return null;
        }

        String statusParam() throws Exception {
            String loc = location();
            if (loc == null || loc.indexOf("status=") < 0) {
                return null;
            }
            return URLDecoder.decode(loc.substring(loc.indexOf("status=") + 7), "UTF-8");
        }

        int code() {
            return Integer.parseInt(head.split(" ")[1]);
        }
    }

    static Resp request(Fixture f, String method, String path, String query, String form) throws Exception {
        Map<String, String> headers = new HashMap<String, String>();
        headers.put("host", "192.168.19.74:8445");
        byte[] body = form == null ? new byte[0] : form.getBytes(StandardCharsets.UTF_8);
        HttpRequest req = new HttpRequest(method, path, HttpRequest.parseQuery(query), headers,
                new ByteArrayInputStream(body));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        HttpResponse res = new HttpResponse(out);
        if ("/exit".equals(path)) {
            lastExitAuthorized = SettingsPage.handleExit(req, res, f.token);
        } else {
            SettingsPage.handleRoot(req, res, f.token, f.settings, "Listening");
        }
        String raw = new String(out.toByteArray(), StandardCharsets.UTF_8);
        int split = raw.indexOf("\r\n\r\n");
        Resp r = new Resp();
        r.head = split < 0 ? raw : raw.substring(0, split);
        r.body = split < 0 ? "" : raw.substring(split + 4);
        return r;
    }

    static boolean lastExitAuthorized;

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

    private static boolean accepts(String raw) {
        try {
            RelayAddress.parse(raw);
            return true;
        } catch (RelayAddress.InvalidException e) {
            return false;
        }
    }

    private static String refusal(String raw) {
        try {
            RelayAddress.parse(raw);
            return null;
        } catch (RelayAddress.InvalidException e) {
            return e.getMessage();
        }
    }

    public static void main(String[] args) {
        scenario("valid_private_address_saves", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                String t = f.get("");
                Resp r = request(f, "POST", "/", "", "t=" + t + "&relay=192.168.1.20%3A8790&turn_taking=on");
                check(n, "192.168.1.20:8790".equals(f.settings.relayAddress())
                                && f.settings.turnTaking()
                                && r.code() == 302 && r.location().startsWith("/?status="),
                        "stored=" + f.settings.relayAddress() + " turn=" + f.settings.turnTaking()
                                + " head=" + r.head);
            }
        });
        scenario("saved_address_is_trimmed_and_normalized", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                String t = f.get("");
                request(f, "POST", "/", "", "t=" + t + "&relay=+10.0.0.7%3A8790+");
                check(n, "10.0.0.7:8790".equals(f.settings.relayAddress()), "stored=" + f.settings.relayAddress());
            }
        });
        scenario("missing_port_refused", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                String t = f.get("");
                Resp r = request(f, "POST", "/", "", "t=" + t + "&relay=192.168.1.20");
                String status = r.statusParam();
                check(n, "192.168.1.10:8790".equals(f.settings.relayAddress())
                                && status != null && status.toLowerCase().contains("port"),
                        "stored=" + f.settings.relayAddress() + " status=" + status);
            }
        });
        scenario("public_ip_refused", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                String t = f.get("");
                Resp r = request(f, "POST", "/", "", "t=" + t + "&relay=8.8.8.8%3A8790");
                String status = r.statusParam();
                check(n, "192.168.1.10:8790".equals(f.settings.relayAddress())
                                && status != null && status.toLowerCase().contains("private"),
                        "stored=" + f.settings.relayAddress() + " status=" + status);
            }
        });
        scenario("invalid_value_does_not_change_turn_taking", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                String t = f.get("");
                int before = f.store.writes;
                request(f, "POST", "/", "", "t=" + t + "&relay=8.8.8.8%3A8790&turn_taking=on");
                check(n, !f.settings.turnTaking() && f.store.writes == before && f.listener.seen.isEmpty(),
                        "turn=" + f.settings.turnTaking() + " writes=" + (f.store.writes - before));
            }
        });
        scenario("post_without_token_refused", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                f.get("");
                int before = f.store.writes;
                Resp r = request(f, "POST", "/", "", "relay=192.168.1.99%3A8790&turn_taking=on");
                check(n, "192.168.1.10:8790".equals(f.settings.relayAddress()) && !f.settings.turnTaking()
                                && f.store.writes == before && f.listener.seen.isEmpty()
                                && r.statusParam() != null,
                        "stored=" + f.settings.relayAddress() + " head=" + r.head);
            }
        });
        scenario("post_with_token_in_query_only_refused", new Scenario() {
            public void run(String n) throws Exception {
                // The token rides in the form body only: the request line (and any
                // query string) is logged by RoutingHttpServer.
                Fixture f = new Fixture();
                String t = f.get("");
                request(f, "POST", "/", "t=" + t, "relay=192.168.1.99%3A8790");
                check(n, "192.168.1.10:8790".equals(f.settings.relayAddress()), "stored=" + f.settings.relayAddress());
            }
        });
        scenario("token_from_other_page_load_refused", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                String first = f.get("");
                String second = f.get("");
                request(f, "POST", "/", "", "t=" + first + "&relay=192.168.1.99%3A8790");
                boolean refused = "192.168.1.10:8790".equals(f.settings.relayAddress());
                request(f, "POST", "/", "", "t=" + second + "&relay=192.168.1.99%3A8790");
                boolean accepted = "192.168.1.99:8790".equals(f.settings.relayAddress());
                check(n, first != null && second != null && !first.equals(second) && refused && accepted,
                        "first=" + first + " second=" + second + " refused=" + refused + " accepted=" + accepted);
            }
        });
        scenario("forged_token_refused", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                request(f, "POST", "/", "", "t=deadbeef&relay=192.168.1.99%3A8790");
                f.get("");
                request(f, "POST", "/", "", "t=deadbeef&relay=192.168.1.99%3A8790");
                request(f, "POST", "/", "", "t=&relay=192.168.1.99%3A8790");
                check(n, "192.168.1.10:8790".equals(f.settings.relayAddress()), "stored=" + f.settings.relayAddress());
            }
        });
        scenario("listener_fires_once_on_address_change", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                String t = f.get("");
                request(f, "POST", "/", "", "t=" + t + "&relay=192.168.1.20%3A8790");
                t = f.get("");
                request(f, "POST", "/", "", "t=" + t + "&relay=192.168.1.20%3A8790&turn_taking=on");
                check(n, f.listener.seen.size() == 1 && "192.168.1.20:8790".equals(f.listener.seen.get(0))
                                && f.settings.turnTaking(),
                        "seen=" + f.listener.seen);
            }
        });
        scenario("removed_listener_not_called", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                f.settings.removeRelayAddressListener(f.listener);
                String t = f.get("");
                request(f, "POST", "/", "", "t=" + t + "&relay=192.168.1.20%3A8790");
                check(n, f.listener.seen.isEmpty(), "seen=" + f.listener.seen);
            }
        });
        scenario("blank_address_clears_and_notifies", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                String t = f.get("");
                request(f, "POST", "/", "", "t=" + t + "&relay=+");
                check(n, "".equals(f.settings.relayAddress()) && f.listener.seen.size() == 1
                                && "".equals(f.listener.seen.get(0)),
                        "stored=" + f.settings.relayAddress() + " seen=" + f.listener.seen);
            }
        });
        scenario("oversized_form_refused", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                String t = f.get("");
                StringBuilder pad = new StringBuilder();
                for (int i = 0; i < 20000; i++) {
                    pad.append('a');
                }
                request(f, "POST", "/", "", "t=" + t + "&relay=192.168.1.99%3A8790&pad=" + pad);
                check(n, "192.168.1.10:8790".equals(f.settings.relayAddress()), "stored=" + f.settings.relayAddress());
            }
        });
        scenario("get_renders_form_with_token_and_state", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                Resp r = request(f, "GET", "/", "status=%3Cb%3Ebad%3C%2Fb%3E", null);
                boolean ok = r.code() == 200
                        && TOKEN.matcher(r.body).find()
                        && r.body.contains("value=\"192.168.1.10:8790\"")
                        && r.body.contains("/assets/pico.min.css")
                        && r.body.contains("role=\"switch\"")
                        && r.body.contains("Listening")
                        && r.body.contains("action=\"/exit\"")
                        && r.body.contains("&lt;b&gt;bad&lt;/b&gt;")
                        && !r.body.contains("<b>bad</b>");
                check(n, ok, "body=" + r.body);
            }
        });
        scenario("get_marks_turn_taking_checked", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                f.store.values.put(VoiceSettings.KEY_TURN_TAKING, Boolean.TRUE);
                Resp r = request(f, "GET", "/", "", null);
                check(n, r.body.contains(" checked"), "body=" + r.body);
            }
        });
        scenario("other_methods_refused", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                Resp root = request(f, "PUT", "/", "", "relay=192.168.1.99%3A8790");
                Resp exit = request(f, "GET", "/exit", "", null);
                check(n, root.code() == 405 && exit.code() == 405 && !lastExitAuthorized,
                        "root=" + root.head + " exit=" + exit.head);
            }
        });
        scenario("exit_with_issued_token_goes_to_launcher", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                String t = f.get("");
                Resp r = request(f, "POST", "/exit", "", "t=" + t);
                check(n, lastExitAuthorized && r.code() == 302
                                && "http://192.168.19.74:8080/".equals(r.location()),
                        "authorized=" + lastExitAuthorized + " head=" + r.head);
            }
        });
        scenario("exit_without_token_refused", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                f.get("");
                Resp r = request(f, "POST", "/exit", "", "");
                boolean noToken = !lastExitAuthorized && r.location() != null && r.location().startsWith("/?status=");
                request(f, "POST", "/exit", "", "t=deadbeef");
                check(n, noToken && !lastExitAuthorized, "head=" + r.head);
            }
        });
        scenario("exit_with_stale_token_refused", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                String first = f.get("");
                f.get("");
                request(f, "POST", "/exit", "", "t=" + first);
                check(n, !lastExitAuthorized, "stale token authorized exit");
            }
        });
        scenario("defaults", new Scenario() {
            public void run(String n) throws Exception {
                VoiceSettings s = new VoiceSettings(new MemStore());
                check(n, "".equals(s.relayAddress()) && !s.turnTaking()
                                && s.prebufferChunks() == 1 && s.uplinkChunkMs() == 80,
                        "relay=" + s.relayAddress() + " turn=" + s.turnTaking() + " prebuffer="
                                + s.prebufferChunks() + " uplink=" + s.uplinkChunkMs());
            }
        });
        scenario("tuning_keys_read_from_store", new Scenario() {
            public void run(String n) throws Exception {
                MemStore store = new MemStore();
                store.values.put(VoiceSettings.KEY_PREBUFFER_CHUNKS, 3);
                store.values.put(VoiceSettings.KEY_UPLINK_CHUNK_MS, 40);
                VoiceSettings s = new VoiceSettings(store);
                MemStore bad = new MemStore();
                bad.values.put(VoiceSettings.KEY_PREBUFFER_CHUNKS, -2);
                bad.values.put(VoiceSettings.KEY_UPLINK_CHUNK_MS, 0);
                VoiceSettings b = new VoiceSettings(bad);
                check(n, s.prebufferChunks() == 3 && s.uplinkChunkMs() == 40
                                && b.prebufferChunks() == 1 && b.uplinkChunkMs() == 80,
                        "s=" + s.prebufferChunks() + "/" + s.uplinkChunkMs() + " b=" + b.prebufferChunks()
                                + "/" + b.uplinkChunkMs());
            }
        });
        scenario("address_parser_accepts_private_and_link_local", new Scenario() {
            public void run(String n) throws Exception {
                String[] ok = {"10.0.0.5:1", "172.16.0.1:8790", "172.31.255.255:8790", "192.168.0.1:65535",
                        "169.254.10.10:8790", "127.0.0.1:8790", "[fd00::5]:8790", "[fe80::1]:8790",
                        "relay.local:8790", "my-host:8790", "RELAY:8790"};
                List<String> refused = new ArrayList<String>();
                for (String s : ok) {
                    if (!accepts(s)) {
                        refused.add(s + " (" + refusal(s) + ")");
                    }
                }
                check(n, refused.isEmpty(), "refused " + refused);
            }
        });
        scenario("address_parser_refuses_bad_input", new Scenario() {
            public void run(String n) throws Exception {
                String[] bad = {"", "192.168.1.5", "192.168.1.5:", ":8790", "192.168.1.5:0", "192.168.1.5:65536",
                        "192.168.1.5:abc", "192.168.1.5:-1", "172.32.0.1:8790", "100.64.0.1:8790", "0.0.0.0:8790",
                        "8.8.8.8:8790", "224.0.0.1:8790", "256.1.1.1:8790", "1.2.3:8790", "1.2.3.4.5:8790",
                        "ws://192.168.1.5:8790", "192.168.1.5:8790/path", "fd00::5:8790", "[2001:db8::1]:8790",
                        "[fd00::5]", "-bad.host:8790", "bad_host:8790", "host name:8790", "[::]:8790"};
                List<String> accepted = new ArrayList<String>();
                for (String s : bad) {
                    if (accepts(s)) {
                        accepted.add(s);
                    }
                }
                check(n, accepted.isEmpty(), "accepted " + accepted);
            }
        });
        scenario("address_parser_fields", new Scenario() {
            public void run(String n) throws Exception {
                RelayAddress a = RelayAddress.parse("relay.local:8790");
                RelayAddress b = RelayAddress.parse("192.168.1.5:9000");
                RelayAddress c = RelayAddress.parse("[fd00::5]:8790");
                check(n, "relay.local".equals(a.host) && a.port == 8790 && a.isHostname()
                                && !b.isHostname() && "192.168.1.5:9000".equals(b.toString())
                                && "[fd00::5]:8790".equals(c.toString()) && !c.isHostname(),
                        a.host + "|" + a.port + "|" + a.isHostname() + "|" + b + "|" + c);
            }
        });
        scenario("connect_time_check_on_resolved_ip", new Scenario() {
            public void run(String n) throws Exception {
                boolean publicRefused = !RelayAddress.isAllowedRelayAddress(InetAddress.getByName("8.8.8.8"));
                boolean cgnatRefused = !RelayAddress.isAllowedRelayAddress(InetAddress.getByName("100.64.1.1"));
                boolean nullRefused = !RelayAddress.isAllowedRelayAddress(null);
                boolean privateOk = RelayAddress.isAllowedRelayAddress(InetAddress.getByName("192.168.19.5"));
                InetSocketAddress local = RelayAddress.parse("localhost:8790").resolveAllowed();
                check(n, publicRefused && cgnatRefused && nullRefused && privateOk
                                && local.getAddress().isLoopbackAddress() && local.getPort() == 8790,
                        "public=" + publicRefused + " cgnat=" + cgnatRefused + " null=" + nullRefused
                                + " private=" + privateOk + " local=" + local);
            }
        });
        System.out.println(failures == 0 ? "ALL OK" : ("FAILURES " + failures));
        System.exit(failures == 0 ? 0 : 1);
    }
}
