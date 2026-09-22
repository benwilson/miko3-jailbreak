package com.miko3.shared;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The launcher's mode registry (KTD8, U9): every mode app it can launch, and
 * how it finds out which one is running — by asking each mode's presence
 * route, not by reading the drive lease holder (the voice mode never takes
 * the lease, R18) and not by checking whether its port is open (a mode's
 * server starts in its Application and is never stopped, so a cleanly exited
 * mode's cached process keeps its port bound).
 *
 * Plain Java, no android.*, so scripts/tests/test_mode_registry.py can run
 * the lookups, the presence parse, the force-exit decision, and the probe
 * itself on the host JVM. Ports here must match each mode's own
 * ModeApp.PORT/HTTPS_PORT (the modes keep their literals; nothing on their
 * side reads this class except the ids and presence helpers).
 */
public final class ModeRegistry {
    private ModeRegistry() {
    }

    /** One mode's answer to the presence probe. UNKNOWN covers every way of not
     * getting a usable answer — refused (process dead), timed out (wedged),
     * non-200 (a build without the route), or a body naming another mode or no
     * active flag — and is treated as "nothing to exit". */
    public enum Presence {
        ACTIVE, INACTIVE, UNKNOWN
    }

    public static final class Mode {
        /** Registry id: /launch-mode?mode=ID and the presence body's "mode". */
        public final String id;
        public final String packageName;
        /** Fully qualified Activity class the launcher starts and sends force-exit to. */
        public final String activityClassName;
        /** Plain HTTP port: the presence probe's target and where the browser is
         * sent after launch (the mode's own server redirects on to HTTPS). */
        public final int httpPort;
        public final int httpsPort;
        public final String displayName;

        Mode(String id, String packageName, String activityClassName, int httpPort, int httpsPort,
             String displayName) {
            this.id = id;
            this.packageName = packageName;
            this.activityClassName = activityClassName;
            this.httpPort = httpPort;
            this.httpsPort = httpsPort;
            this.displayName = displayName;
        }
    }

    public static final Mode REMOTE_CONTROL = new Mode(LauncherProtocol.MODE_REMOTE_CONTROL,
            "com.miko3.mode.remotecontrol", "com.miko3.mode.remotecontrol.MainActivity",
            8081, 8444, "Remote Control / Telepresence");

    public static final Mode VOICE = new Mode(LauncherProtocol.MODE_VOICE,
            "com.miko3.mode.voice", "com.miko3.mode.voice.MainActivity",
            8082, 8445, "Voice Conversation");

    public static final Mode EXPLORE = new Mode(LauncherProtocol.MODE_EXPLORE,
            "com.miko3.mode.explore", "com.miko3.mode.explore.MainActivity",
            8083, 8446, "Explore");

    private static final List<Mode> ALL =
            Collections.unmodifiableList(Arrays.asList(REMOTE_CONTROL, VOICE, EXPLORE));

    /** Every registered mode, in launcher-page order. */
    public static List<Mode> all() {
        return ALL;
    }

    /** The registered mode with this id, or null. */
    public static Mode byId(String id) {
        if (id == null) {
            return null;
        }
        for (Mode m : ALL) {
            if (m.id.equals(id)) {
                return m;
            }
        }
        return null;
    }

    /**
     * The mode a /launch-mode request names: absent or empty means
     * remote-control, so the pre-registry "/launch-mode" link keeps working;
     * an unknown id is null (the caller launches nothing rather than guess).
     */
    public static Mode resolveLaunchTarget(String modeParam) {
        if (modeParam == null || modeParam.isEmpty()) {
            return REMOTE_CONTROL;
        }
        return byId(modeParam);
    }

    /** The presence route's body; every mode answers with exactly this shape. */
    public static String presenceJson(String modeId, boolean active) {
        return "{\"mode\":\"" + modeId + "\",\"active\":" + active + "}";
    }

    private static final Pattern MODE_FIELD = Pattern.compile("\"mode\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern ACTIVE_FIELD = Pattern.compile("\"active\"\\s*:\\s*(true|false)\\b");

    /** Reads a presence body; anything but this mode's own well-formed answer is UNKNOWN. */
    public static Presence parsePresence(String body, String expectedModeId) {
        if (body == null) {
            return Presence.UNKNOWN;
        }
        Matcher mode = MODE_FIELD.matcher(body);
        Matcher active = ACTIVE_FIELD.matcher(body);
        if (!mode.find() || !mode.group(1).equals(expectedModeId) || !active.find()) {
            return Presence.UNKNOWN;
        }
        return "true".equals(active.group(1)) ? Presence.ACTIVE : Presence.INACTIVE;
    }

    /** Probes a registered mode over loopback plain HTTP. */
    public static Presence probePresence(Mode mode, int timeoutMs) {
        return probePresence("127.0.0.1", mode.httpPort, mode.id, timeoutMs);
    }

    /**
     * GETs the presence route over plain HTTP with timeoutMs bounding both the
     * connect and each read. A raw socket rather than HttpURLConnection: one
     * request, no connection pooling or silent retry to stretch the bound, and
     * nothing to configure about redirects (RoutingHttpServer answers this
     * path on its plain listener directly — see LauncherProtocol.PRESENCE_PATH).
     */
    public static Presence probePresence(String host, int port, String modeId, int timeoutMs) {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            OutputStream out = socket.getOutputStream();
            out.write(("GET " + LauncherProtocol.PRESENCE_PATH + " HTTP/1.0\r\nHost: " + host + ":" + port
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String response = readCapped(socket.getInputStream(), 4096);
            if (!response.startsWith("HTTP/1.0 200 ") && !response.startsWith("HTTP/1.1 200 ")) {
                return Presence.UNKNOWN;
            }
            int bodyStart = response.indexOf("\r\n\r\n");
            return bodyStart < 0 ? Presence.UNKNOWN : parsePresence(response.substring(bodyStart + 4), modeId);
        } catch (IOException e) {
            return Presence.UNKNOWN;
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** Probes every mode in order; keyed by mode id. */
    public static Map<String, Presence> probeAll(List<Mode> modes, int timeoutMs) {
        Map<String, Presence> out = new LinkedHashMap<String, Presence>();
        for (Mode m : modes) {
            out.put(m.id, probePresence(m, timeoutMs));
        }
        return out;
    }

    /**
     * The modes whose presence says ACTIVE, in registry order: the ones
     * running now, and so the ones exit-then-launch sends force-exit to (the
     * launch target included — relaunching the running mode exits it first,
     * as the lease-based handoff always did). UNKNOWN is not running: a dead
     * process has nothing to exit, and a silent one cannot be asked.
     */
    public static List<Mode> activeModes(List<Mode> modes, Map<String, Presence> presence) {
        List<Mode> out = new ArrayList<Mode>();
        for (Mode m : modes) {
            if (presence.get(m.id) == Presence.ACTIVE) {
                out.add(m);
            }
        }
        return out;
    }

    private static String readCapped(InputStream in, int cap) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[512];
        int n;
        while (buf.size() < cap && (n = in.read(chunk, 0, Math.min(chunk.length, cap - buf.size()))) != -1) {
            buf.write(chunk, 0, n);
        }
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }
}
