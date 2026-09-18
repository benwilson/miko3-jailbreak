package com.miko3.shared;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * Host-JVM checks for the launcher's mode registry and presence probe (U9,
 * KTD8), driven by scripts/tests/test_mode_registry.py. Lives in the shared
 * package so it can build registry entries on arbitrary ports; compiles
 * against shared/src plus the ws_harness android.* stubs.
 *
 * The RoutingHttpServer scenarios stand up a real plain + HTTPS listener
 * pair (the HTTPS one from shared/assets/server.p12, path in args[0]) and
 * check that the presence path is answered on the plain listener while
 * every other path still redirects.
 *
 * Prints one "PASS <name>" or "FAIL <name>: <detail>" line per scenario; the
 * Python side asserts on each by name.
 */
public final class ModeRegistryHarness {
    private static final String P12_PASSWORD = "miko3https";

    public static void main(String[] args) throws Exception {
        registryScenarios();
        parseScenarios();
        decisionScenarios();
        serverScenarios(args[0]);
        System.out.flush();
        // The server threads are daemons; nothing else keeps the JVM alive.
    }

    // ---- registry lookups --------------------------------------------------

    private static void registryScenarios() {
        List<ModeRegistry.Mode> all = ModeRegistry.all();
        check("registry_order_and_ids", all.size() == 2
                        && "remote-control".equals(all.get(0).id) && "voice".equals(all.get(1).id),
                "got " + ids(all));

        ModeRegistry.Mode rc = ModeRegistry.byId("remote-control");
        check("remote_control_entry", rc != null
                        && "com.miko3.mode.remotecontrol".equals(rc.packageName)
                        && "com.miko3.mode.remotecontrol.MainActivity".equals(rc.activityClassName)
                        && rc.httpPort == 8081 && rc.httpsPort == 8444
                        && rc.displayName.length() > 0,
                describe(rc));

        ModeRegistry.Mode voice = ModeRegistry.byId("voice");
        check("voice_entry", voice != null
                        && "com.miko3.mode.voice".equals(voice.packageName)
                        && "com.miko3.mode.voice.MainActivity".equals(voice.activityClassName)
                        && voice.httpPort == 8082 && voice.httpsPort == 8445
                        && voice.displayName.length() > 0,
                describe(voice));

        check("ids_match_protocol_constants",
                LauncherProtocol.MODE_REMOTE_CONTROL.equals(rc == null ? null : rc.id)
                        && LauncherProtocol.MODE_VOICE.equals(voice == null ? null : voice.id),
                "protocol ids " + LauncherProtocol.MODE_REMOTE_CONTROL + "/" + LauncherProtocol.MODE_VOICE);

        check("by_id_unknown_is_null", ModeRegistry.byId("autonomous") == null
                && ModeRegistry.byId(null) == null && ModeRegistry.byId("") == null, "expected null");

        check("registry_is_unmodifiable", isUnmodifiable(all), "all() list could be modified");

        check("launch_target_no_param_is_remote_control",
                ModeRegistry.resolveLaunchTarget(null) == ModeRegistry.REMOTE_CONTROL
                        && ModeRegistry.resolveLaunchTarget("") == ModeRegistry.REMOTE_CONTROL,
                "old no-param /launch-mode link must keep launching remote-control");
        check("launch_target_by_id",
                ModeRegistry.resolveLaunchTarget("voice") == ModeRegistry.VOICE
                        && ModeRegistry.resolveLaunchTarget("remote-control") == ModeRegistry.REMOTE_CONTROL,
                "ids did not resolve");
        check("launch_target_unknown_is_null", ModeRegistry.resolveLaunchTarget("nope") == null,
                "unknown id must not fall back to a default mode");

        check("presence_path_constant", "/presence".equals(LauncherProtocol.PRESENCE_PATH),
                LauncherProtocol.PRESENCE_PATH);
    }

    // ---- presence JSON -----------------------------------------------------

    private static void parseScenarios() {
        check("parse_active_true",
                ModeRegistry.parsePresence("{\"mode\":\"voice\",\"active\":true}", "voice")
                        == ModeRegistry.Presence.ACTIVE, "");
        check("parse_active_false",
                ModeRegistry.parsePresence("{\"mode\":\"voice\",\"active\":false}", "voice")
                        == ModeRegistry.Presence.INACTIVE, "");
        check("parse_whitespace_and_order_tolerant",
                ModeRegistry.parsePresence(" { \"active\" : true ,\n \"mode\" : \"remote-control\" } ",
                        "remote-control") == ModeRegistry.Presence.ACTIVE, "");
        check("parse_other_mode_is_unknown",
                ModeRegistry.parsePresence("{\"mode\":\"voice\",\"active\":true}", "remote-control")
                        == ModeRegistry.Presence.UNKNOWN,
                "a different mode answering on this port must not count as this mode running");
        check("parse_malformed_is_unknown",
                ModeRegistry.parsePresence("<html>not found</html>", "voice") == ModeRegistry.Presence.UNKNOWN
                        && ModeRegistry.parsePresence("{\"mode\":\"voice\"}", "voice") == ModeRegistry.Presence.UNKNOWN
                        && ModeRegistry.parsePresence("{\"mode\":\"voice\",\"active\":\"yes\"}", "voice")
                        == ModeRegistry.Presence.UNKNOWN
                        && ModeRegistry.parsePresence("", "voice") == ModeRegistry.Presence.UNKNOWN
                        && ModeRegistry.parsePresence(null, "voice") == ModeRegistry.Presence.UNKNOWN, "");
        String rcJson = ModeRegistry.presenceJson("remote-control", true);
        check("presence_json_shape", "{\"mode\":\"remote-control\",\"active\":true}".equals(rcJson)
                        && "{\"mode\":\"voice\",\"active\":false}".equals(ModeRegistry.presenceJson("voice", false)),
                rcJson);
        check("presence_json_round_trips",
                ModeRegistry.parsePresence(ModeRegistry.presenceJson("voice", true), "voice")
                        == ModeRegistry.Presence.ACTIVE
                        && ModeRegistry.parsePresence(ModeRegistry.presenceJson("voice", false), "voice")
                        == ModeRegistry.Presence.INACTIVE, "");
    }

    // ---- which mode to force-exit ------------------------------------------

    private static void decisionScenarios() {
        List<ModeRegistry.Mode> all = ModeRegistry.all();

        Map<String, ModeRegistry.Presence> none = presence(ModeRegistry.Presence.INACTIVE, ModeRegistry.Presence.INACTIVE);
        check("none_active_exits_nothing", ModeRegistry.activeModes(all, none).isEmpty(),
                ids(ModeRegistry.activeModes(all, none)));

        Map<String, ModeRegistry.Presence> rcOn = presence(ModeRegistry.Presence.ACTIVE, ModeRegistry.Presence.INACTIVE);
        check("remote_control_active_is_exited", "[remote-control]".equals(ids(ModeRegistry.activeModes(all, rcOn))),
                ids(ModeRegistry.activeModes(all, rcOn)));

        Map<String, ModeRegistry.Presence> voiceOn = presence(ModeRegistry.Presence.INACTIVE, ModeRegistry.Presence.ACTIVE);
        check("voice_active_is_exited", "[voice]".equals(ids(ModeRegistry.activeModes(all, voiceOn))),
                ids(ModeRegistry.activeModes(all, voiceOn)));

        Map<String, ModeRegistry.Presence> failed = presence(ModeRegistry.Presence.UNKNOWN, ModeRegistry.Presence.UNKNOWN);
        check("probe_failures_exit_nothing", ModeRegistry.activeModes(all, failed).isEmpty(),
                "a dead or silent mode has nothing to exit: " + ids(ModeRegistry.activeModes(all, failed)));

        Map<String, ModeRegistry.Presence> mixed = presence(ModeRegistry.Presence.UNKNOWN, ModeRegistry.Presence.ACTIVE);
        check("failure_plus_active_exits_only_active", "[voice]".equals(ids(ModeRegistry.activeModes(all, mixed))),
                ids(ModeRegistry.activeModes(all, mixed)));

        Map<String, ModeRegistry.Presence> both = presence(ModeRegistry.Presence.ACTIVE, ModeRegistry.Presence.ACTIVE);
        check("both_active_exits_both_in_registry_order",
                "[remote-control, voice]".equals(ids(ModeRegistry.activeModes(all, both))),
                ids(ModeRegistry.activeModes(all, both)));

        Map<String, ModeRegistry.Presence> missing = new HashMap<String, ModeRegistry.Presence>();
        missing.put("remote-control", ModeRegistry.Presence.ACTIVE);
        missing.put("autonomous", ModeRegistry.Presence.ACTIVE);
        check("missing_and_unregistered_ids_ignored",
                "[remote-control]".equals(ids(ModeRegistry.activeModes(all, missing))),
                ids(ModeRegistry.activeModes(all, missing)));
    }

    // ---- RoutingHttpServer + probe over real sockets -----------------------

    private static void serverScenarios(String p12Path) throws Exception {
        final boolean[] active = {true};
        int plainPort = freePort();
        int httpsPort = freePort();
        RoutingHttpServer server = new RoutingHttpServer(new Context(), plainPort);
        server.route(LauncherProtocol.PRESENCE_PATH, new RoutingHttpServer.RouteHandler() {
            @Override
            public void handle(HttpRequest req, HttpResponse res) throws IOException {
                res.sendText(200, "OK", "application/json; charset=utf-8",
                        ModeRegistry.presenceJson("voice", active[0]));
            }
        });
        server.route("/other", new RoutingHttpServer.RouteHandler() {
            @Override
            public void handle(HttpRequest req, HttpResponse res) throws IOException {
                res.sendText(200, "OK", "text/plain; charset=utf-8", "other");
            }
        });
        Thread t = new Thread(server, "harness-plain");
        t.setDaemon(true);
        t.start();
        server.startHttps(httpsPort, loadContext(p12Path));

        // HTTPS is up once the plain listener starts redirecting /other.
        String otherHead = null;
        for (int i = 0; i < 100; i++) {
            try {
                otherHead = rawGet(plainPort, "/other");
                if (otherHead.startsWith("HTTP/1.0 302") || otherHead.startsWith("HTTP/1.1 302")) {
                    break;
                }
            } catch (IOException notYet) {
                // listener not bound yet
            }
            Thread.sleep(50);
        }
        boolean httpsUp = otherHead != null && otherHead.contains(" 302 ");
        check("https_listener_up", httpsUp && tlsHandshakes(httpsPort), "plain /other answered " + firstLine(otherHead));
        check("other_path_still_redirects_to_https",
                otherHead != null && otherHead.contains(" 302 ")
                        && otherHead.contains("Location: https://127.0.0.1:" + httpsPort + "/other"),
                firstLine(otherHead));

        String presenceResp = rawGet(plainPort, LauncherProtocol.PRESENCE_PATH);
        check("presence_served_plain_while_https_up",
                presenceResp.contains(" 200 ") && presenceResp.endsWith("{\"mode\":\"voice\",\"active\":true}"),
                "got " + firstLine(presenceResp) + " body=" + body(presenceResp));

        String presenceQuery = rawGet(plainPort, LauncherProtocol.PRESENCE_PATH + "?x=1");
        check("presence_with_query_served_plain", presenceQuery.contains(" 200 "), firstLine(presenceQuery));

        String prefixed = rawGet(plainPort, "/presence-extra");
        check("presence_prefix_path_still_redirects", prefixed.contains(" 302 "), firstLine(prefixed));

        ModeRegistry.Presence probed = ModeRegistry.probePresence("127.0.0.1", plainPort, "voice", 1000);
        check("probe_active_over_plain_while_https_up", probed == ModeRegistry.Presence.ACTIVE, String.valueOf(probed));

        active[0] = false;
        ModeRegistry.Presence probedOff = ModeRegistry.probePresence("127.0.0.1", plainPort, "voice", 1000);
        check("probe_inactive_flips", probedOff == ModeRegistry.Presence.INACTIVE, String.valueOf(probedOff));

        ModeRegistry.Presence wrongMode = ModeRegistry.probePresence("127.0.0.1", plainPort, "remote-control", 1000);
        check("probe_wrong_mode_is_unknown", wrongMode == ModeRegistry.Presence.UNKNOWN, String.valueOf(wrongMode));

        // A server with no presence route (a mode built before U9): 404, not active.
        int bare = freePort();
        RoutingHttpServer bareServer = new RoutingHttpServer(new Context(), bare);
        Thread bt = new Thread(bareServer, "harness-bare");
        bt.setDaemon(true);
        bt.start();
        waitListening(bare);
        ModeRegistry.Presence notFound = ModeRegistry.probePresence("127.0.0.1", bare, "voice", 1000);
        check("probe_404_is_unknown", notFound == ModeRegistry.Presence.UNKNOWN, String.valueOf(notFound));

        // Nothing listening (the mode's process died): fails fast, not active.
        int closed = freePort();
        long t0 = System.nanoTime();
        ModeRegistry.Presence refused = ModeRegistry.probePresence("127.0.0.1", closed, "voice", 500);
        long refusedMs = (System.nanoTime() - t0) / 1000000;
        check("probe_refused_is_unknown", refused == ModeRegistry.Presence.UNKNOWN && refusedMs < 1500,
                refused + " after " + refusedMs + "ms");

        // Accepts but never answers (a wedged process): bounded by the timeout.
        final ServerSocket silent = new ServerSocket(0);
        final List<Socket> held = new ArrayList<Socket>();
        Thread st = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    while (true) {
                        held.add(silent.accept());
                    }
                } catch (IOException ignored) {
                }
            }
        }, "harness-silent");
        st.setDaemon(true);
        st.start();
        t0 = System.nanoTime();
        ModeRegistry.Presence hung = ModeRegistry.probePresence("127.0.0.1", silent.getLocalPort(), "voice", 400);
        long hungMs = (System.nanoTime() - t0) / 1000000;
        check("probe_silent_server_times_out", hung == ModeRegistry.Presence.UNKNOWN && hungMs < 2000,
                hung + " after " + hungMs + "ms");
        silent.close();

        server.stop();
        bareServer.stop();
    }

    // ---- helpers -----------------------------------------------------------

    private static SSLContext loadContext(String p12Path) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        InputStream in = new FileInputStream(p12Path);
        try {
            ks.load(in, P12_PASSWORD.toCharArray());
        } finally {
            in.close();
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, P12_PASSWORD.toCharArray());
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        return ctx;
    }

    /** True if something on the port completes a TLS handshake (certificate not checked). */
    private static boolean tlsHandshakes(int port) {
        try {
            SSLContext trustAll = SSLContext.getInstance("TLS");
            trustAll.init(null, new javax.net.ssl.TrustManager[]{new javax.net.ssl.X509TrustManager() {
                @Override
                public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {
                }

                @Override
                public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {
                }

                @Override
                public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                    return new java.security.cert.X509Certificate[0];
                }
            }}, null);
            javax.net.ssl.SSLSocket s = (javax.net.ssl.SSLSocket) trustAll.getSocketFactory()
                    .createSocket("127.0.0.1", port);
            try {
                s.setSoTimeout(3000);
                s.startHandshake();
                return true;
            } finally {
                s.close();
            }
        } catch (Exception e) {
            return false;
        }
    }

    private static String rawGet(int port, String path) throws IOException {
        Socket s = new Socket("127.0.0.1", port);
        try {
            s.setSoTimeout(3000);
            OutputStream out = s.getOutputStream();
            out.write(("GET " + path + " HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = s.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[1024];
            int n;
            while ((n = in.read(chunk)) != -1) {
                buf.write(chunk, 0, n);
            }
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            s.close();
        }
    }

    private static void waitListening(int port) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            try {
                new Socket("127.0.0.1", port).close();
                return;
            } catch (IOException notYet) {
                Thread.sleep(50);
            }
        }
    }

    private static int freePort() throws IOException {
        ServerSocket probe = new ServerSocket(0);
        int port = probe.getLocalPort();
        probe.close();
        return port;
    }

    private static Map<String, ModeRegistry.Presence> presence(ModeRegistry.Presence rc, ModeRegistry.Presence voice) {
        Map<String, ModeRegistry.Presence> m = new HashMap<String, ModeRegistry.Presence>();
        m.put("remote-control", rc);
        m.put("voice", voice);
        return m;
    }

    private static boolean isUnmodifiable(List<ModeRegistry.Mode> list) {
        try {
            list.add(ModeRegistry.VOICE);
            return false;
        } catch (UnsupportedOperationException expected) {
            return true;
        }
    }

    private static String ids(List<ModeRegistry.Mode> modes) {
        List<String> out = new ArrayList<String>();
        for (ModeRegistry.Mode m : modes) {
            out.add(m.id);
        }
        return out.toString();
    }

    private static String describe(ModeRegistry.Mode m) {
        return m == null ? "null" : m.id + " " + m.packageName + "/" + m.activityClassName
                + " " + m.httpPort + "/" + m.httpsPort + " '" + m.displayName + "'";
    }

    private static String firstLine(String resp) {
        if (resp == null) return "null";
        int eol = resp.indexOf("\r\n");
        return eol >= 0 ? resp.substring(0, eol) : resp;
    }

    private static String body(String resp) {
        int sep = resp.indexOf("\r\n\r\n");
        return sep >= 0 ? resp.substring(sep + 4) : "";
    }

    private static void check(String name, boolean ok, String detail) {
        System.out.println(ok ? "PASS " + name : "FAIL " + name + ": " + detail);
    }
}
