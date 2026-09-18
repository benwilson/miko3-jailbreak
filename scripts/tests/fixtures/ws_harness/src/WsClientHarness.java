import com.miko3.shared.WebSocketClient;

import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Drives com.miko3.shared.WebSocketClient on the host JVM against
 * relay/tests/lane_echo_server.py, one U2 conformance scenario per run.
 * Usage: WsClientHarness SCENARIO HOST PORT. Prints "PASS SCENARIO" and
 * exits 0, or "FAIL SCENARIO: why" and exits 1. Driven by
 * scripts/tests/test_websocket_conformance.py.
 */
public final class WsClientHarness {
    private static final String FRAGMENT_TEXT_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int CONNECT_TIMEOUT_MS = 2000;
    private static final long PING_MS = WebSocketClient.PING_INTERVAL_MS;

    private static String host;
    private static int port;

    /** One listener callback, as recorded by {@link Recorder}. */
    static final class Event {
        final String kind;
        final String text;
        final byte[] data;
        final long atNanos = System.nanoTime();
        final String thread = Thread.currentThread().getName();

        Event(String kind, String text, byte[] data) {
            this.kind = kind;
            this.text = text;
            this.data = data;
        }

        @Override
        public String toString() {
            return kind + (text != null ? "(" + abbreviate(text) + ")" : "")
                    + (data != null ? "[" + data.length + " bytes]" : "");
        }
    }

    static final class Recorder implements WebSocketClient.Listener {
        final BlockingQueue<Event> events = new LinkedBlockingQueue<Event>();

        @Override
        public void onOpen(WebSocketClient client) {
            events.add(new Event("open", null, null));
        }

        @Override
        public void onText(WebSocketClient client, String text) {
            events.add(new Event("text", text, null));
        }

        @Override
        public void onBinary(WebSocketClient client, byte[] data) {
            events.add(new Event("binary", null, data));
        }

        @Override
        public void onConnectFailed(WebSocketClient client, String reason) {
            events.add(new Event("connectFailed", reason, null));
        }

        @Override
        public void onLinkLost(WebSocketClient client, String reason) {
            events.add(new Event("linkLost", reason, null));
        }

        Event next(long timeoutMs) throws InterruptedException {
            Event e = events.poll(timeoutMs, TimeUnit.MILLISECONDS);
            check(e != null, "no listener event within " + timeoutMs + " ms");
            return e;
        }

        Event expect(String kind, long timeoutMs) throws InterruptedException {
            Event e = next(timeoutMs);
            check(kind.equals(e.kind), "expected " + kind + ", got " + e);
            return e;
        }
    }

    static final class Failure extends RuntimeException {
        Failure(String msg) {
            super(msg);
        }
    }

    static void check(boolean ok, String msg) {
        if (!ok) {
            throw new Failure(msg);
        }
    }

    static String abbreviate(String s) {
        return s.length() > 80 ? s.substring(0, 80) + "..." : s;
    }

    public static void main(String[] args) throws Exception {
        String scenario = args[0];
        host = args[1];
        port = Integer.parseInt(args[2]);
        try {
            run(scenario);
            System.out.println("PASS " + scenario);
            System.out.flush();
            System.exit(0);
        } catch (Throwable t) {
            System.out.println("FAIL " + scenario + ": " + t);
            System.out.flush();
            System.exit(1);
        }
    }

    static void run(String scenario) throws Exception {
        if ("binary".equals(scenario)) {
            binaryRoundTrip();
        } else if ("text".equals(scenario)) {
            textRoundTrip();
        } else if ("large".equals(scenario)) {
            largeFrame();
        } else if ("masked".equals(scenario)) {
            maskedAtEveryLengthBoundary();
        } else if ("fragments".equals(scenario)) {
            fragmentsReassembled();
        } else if ("abrupt".equals(scenario)) {
            abruptClose();
        } else if ("silent".equals(scenario)) {
            silentServer();
        } else if ("wedged".equals(scenario)) {
            wedgedWriter();
        } else if ("refused".equals(scenario)) {
            connectRefused();
        } else if ("handshake-timeout".equals(scenario)) {
            handshakeTimeout();
        } else if ("concurrent".equals(scenario)) {
            concurrentSends();
        } else if ("server-close".equals(scenario)) {
            serverClose();
        } else if ("local-close".equals(scenario)) {
            localClose();
        } else if ("ping-under-traffic".equals(scenario)) {
            pingUnderTraffic();
        } else {
            throw new Failure("unknown scenario " + scenario);
        }
    }

    // ---- helpers -----------------------------------------------------------

    static WebSocketClient open(Recorder rec) throws InterruptedException {
        return open(rec, "/lane");
    }

    static WebSocketClient open(Recorder rec, String path) throws InterruptedException {
        WebSocketClient client = new WebSocketClient(host, port, path, rec);
        client.connect(CONNECT_TIMEOUT_MS);
        Event e = rec.expect("open", CONNECT_TIMEOUT_MS + 1000);
        check(!"main".equals(e.thread), "onOpen ran on the caller's thread");
        check(client.isOpen(), "isOpen() false after onOpen");
        return client;
    }

    static byte[] pattern(int size, int seed) {
        byte[] b = new byte[size];
        for (int i = 0; i < size; i++) {
            b[i] = (byte) (i * 31 + seed);
        }
        return b;
    }

    /** Sends "!stats" and returns the JSON reply (skipping nothing else). */
    static String stats(WebSocketClient client, Recorder rec) throws InterruptedException {
        check(client.sendText("!stats"), "sendText(!stats) failed");
        return rec.expect("text", 3000).text;
    }

    /** Pulls an integer counter out of the stats JSON: section is "conn" or "global". */
    static long counter(String json, String section, String key) {
        int s = json.indexOf("\"" + section + "\"");
        check(s >= 0, "no " + section + " in " + json);
        int k = json.indexOf("\"" + key + "\":", s);
        check(k >= 0, "no " + key + " in " + json);
        int v = k + key.length() + 3;
        int end = v;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == ' ')) {
            end++;
        }
        return Long.parseLong(json.substring(v, end).trim());
    }

    static List<String> liveClientThreads() {
        List<String> names = new ArrayList<String>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.isAlive() && t.getName().startsWith("ws-client")) {
                names.add(t.getName());
            }
        }
        return names;
    }

    static void expectNoClientThreads(long withinMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + withinMs;
        List<String> live = liveClientThreads();
        while (!live.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
            live = liveClientThreads();
        }
        check(live.isEmpty(), "client threads still running: " + live);
    }

    static double seconds(long nanos) {
        return nanos / 1e9;
    }

    // ---- scenarios ---------------------------------------------------------

    /** KTD3's downstream audio chunk size: one 80 ms chunk at 22.05 kHz. */
    static void binaryRoundTrip() throws Exception {
        Recorder rec = new Recorder();
        WebSocketClient client = open(rec);
        byte[] sent = pattern(3528, 7);
        check(client.sendBinary(sent), "sendBinary failed");
        Event e = rec.expect("binary", 3000);
        check(Arrays.equals(sent, e.data), "3,528-byte echo differs");
        check(!"main".equals(e.thread), "onBinary ran on the caller's thread");
        // The offset/length overload sends exactly that slice.
        byte[] big = pattern(4000, 3);
        check(client.sendBinary(big, 100, 3528), "sendBinary(off,len) failed");
        e = rec.expect("binary", 3000);
        check(Arrays.equals(Arrays.copyOfRange(big, 100, 3628), e.data), "slice echo differs");
        client.close();
    }

    static void textRoundTrip() throws Exception {
        Recorder rec = new Recorder();
        WebSocketClient client = open(rec);
        String sent = "{\"type\":\"hello\",\"id\":\"1\",\"note\":\"café — 🎤\"}";
        check(client.sendText(sent), "sendText failed");
        check(sent.equals(rec.expect("text", 3000).text), "text echo differs");
        check(client.sendText(""), "empty sendText failed");
        check("".equals(rec.expect("text", 3000).text), "empty text echo differs");
        client.close();
    }

    static void largeFrame() throws Exception {
        Recorder rec = new Recorder();
        WebSocketClient client = open(rec);
        byte[] sent = pattern(70000, 11);
        check(client.sendBinary(sent), "sendBinary(70000) failed");
        Event e = rec.expect("binary", 5000);
        check(Arrays.equals(sent, e.data), "70,000-byte echo differs");
        String json = stats(client, rec);
        check(counter(json, "conn", "64") == 1, "server did not see one 8-byte-length frame: " + json);
        check(counter(json, "conn", "unmasked_rejections") == 0, "unmasked frame: " + json);
        client.close();
    }

    static void maskedAtEveryLengthBoundary() throws Exception {
        Recorder rec = new Recorder();
        WebSocketClient client = open(rec);
        int[] sizes = {0, 1, 125, 126, 127, 65535, 65536, 3528, 2560};
        for (int size : sizes) {
            byte[] sent = pattern(size, size);
            check(client.sendBinary(sent), "sendBinary(" + size + ") failed");
            Event e = rec.expect("binary", 5000);
            check(Arrays.equals(sent, e.data), size + "-byte echo differs");
        }
        String json = stats(client, rec);
        check(counter(json, "conn", "unmasked_rejections") == 0, "server rejected a frame: " + json);
        check(counter(json, "conn", "protocol_errors") == 0, "protocol error: " + json);
        // 126, 127, 65535, 3528 and 2560 take the 2-byte form; only 65536 needs 8 bytes.
        check(counter(json, "conn", "16") == 5, "expected 5 2-byte-length frames: " + json);
        check(counter(json, "conn", "64") == 1, "expected 1 8-byte-length frame: " + json);
        client.close();
    }

    static void fragmentsReassembled() throws Exception {
        Recorder rec = new Recorder();
        WebSocketClient client = open(rec);
        check(client.sendText("!fragment text 3 10000"), "send failed");
        Event e = rec.expect("text", 3000);
        StringBuilder want = new StringBuilder();
        while (want.length() < 10000) {
            want.append(FRAGMENT_TEXT_ALPHABET);
        }
        check(want.substring(0, 10000).equals(e.text), "reassembled text differs (" + e.text.length() + " chars)");
        check(client.sendText("!fragment binary 4 5000"), "send failed");
        e = rec.expect("binary", 3000);
        byte[] wantBin = new byte[5000];
        for (int i = 0; i < wantBin.length; i++) {
            wantBin[i] = (byte) (i % 251);
        }
        check(Arrays.equals(wantBin, e.data), "reassembled binary differs (" + e.data.length + " bytes)");
        // Nothing else was delivered: no partial fragment leaked out as its own message.
        String json = stats(client, rec);
        check(json.startsWith("{"), "unexpected extra message before stats: " + abbreviate(json));
        client.close();
    }

    static void abruptClose() throws Exception {
        Recorder rec = new Recorder();
        WebSocketClient client = open(rec);
        long t0 = System.nanoTime();
        check(client.sendText("!abort"), "send failed");
        Event e = rec.expect("linkLost", 3 * PING_MS);
        System.out.println("link lost after " + seconds(e.atNanos - t0) + " s: " + e.text);
        check(!client.isOpen(), "isOpen() true after link lost");
        check(!client.sendText("x"), "send after link lost returned true");
        expectNoClientThreads(1000);
        check(rec.events.poll(300, TimeUnit.MILLISECONDS) == null, "event after link lost");
    }

    /** A dead peer on a live socket while the uplink streams 80 ms binary frames. */
    static void silentServer() throws Exception {
        Recorder rec = new Recorder();
        final WebSocketClient client = open(rec);
        final AtomicBoolean streaming = new AtomicBoolean(true);
        Thread streamer = startStreamer(client, streaming, 2560, 80);
        Thread.sleep(1000);
        check(client.sendText("!silent"), "send failed");
        long t0 = System.nanoTime();
        // Echoes of frames sent before "!silent" may still arrive; skip them.
        Event e;
        do {
            e = rec.next(5 * PING_MS);
        } while ("binary".equals(e.kind));
        check("linkLost".equals(e.kind), "expected linkLost, got " + e);
        double after = seconds(e.atNanos - t0);
        System.out.println("link lost after " + after + " s: " + e.text);
        // Three unanswered pings, each judged missed one interval after it went
        // out; the first goes out at most one interval after the server fell
        // silent. So 3 intervals after the first unanswered ping: 6-8 s here.
        check(after >= (2.5 * PING_MS) / 1000.0, "link lost too early (" + after + " s) for missed pongs");
        check(after <= (4 * PING_MS + 500) / 1000.0, "link lost too late (" + after + " s)");
        check(e.text.contains("pong"), "reason does not name missed pongs: " + e.text);
        streaming.set(false);
        streamer.join(2000);
        check(!streamer.isAlive(), "streaming thread stuck after link lost");
        expectNoClientThreads(1000);
    }

    /** The peer stops reading, so the uplink's writes block on a full TCP window:
     * the watchdog must still declare link loss and unblock the writer. */
    static void wedgedWriter() throws Exception {
        Recorder rec = new Recorder();
        final WebSocketClient client = open(rec);
        check(client.sendText("!deaf"), "send failed");
        long t0 = System.nanoTime();
        final AtomicBoolean streaming = new AtomicBoolean(true);
        final AtomicReference<Boolean> lastSend = new AtomicReference<Boolean>();
        Thread streamer = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] chunk = new byte[65536];
                while (streaming.get()) {
                    boolean ok = client.sendBinary(chunk);
                    lastSend.set(ok);
                    if (!ok) {
                        return;
                    }
                }
            }
        }, "harness-streamer");
        streamer.setDaemon(true);
        streamer.start();
        Event e = rec.expect("linkLost", 4 * PING_MS + 1500);
        System.out.println("link lost after " + seconds(e.atNanos - t0) + " s: " + e.text);
        streamer.join(2000);
        check(!streamer.isAlive(), "writer still blocked 2 s after link lost");
        check(Boolean.FALSE.equals(lastSend.get()), "blocked send did not report failure");
        long c0 = System.nanoTime();
        client.close();
        check(System.nanoTime() - c0 < 1500000000L, "close() blocked");
        expectNoClientThreads(1500);
    }

    static void connectRefused() throws Exception {
        ServerSocket probe = new ServerSocket(0);
        int closedPort = probe.getLocalPort();
        probe.close();
        Recorder rec = new Recorder();
        WebSocketClient client = new WebSocketClient("127.0.0.1", closedPort, "/lane", rec);
        check(!client.sendText("early"), "send before connect returned true");
        long t0 = System.nanoTime();
        client.connect(CONNECT_TIMEOUT_MS);
        Event e = rec.expect("connectFailed", CONNECT_TIMEOUT_MS + 500);
        System.out.println("connect failed after " + seconds(e.atNanos - t0) + " s: " + e.text);
        check(!"main".equals(e.thread), "onConnectFailed ran on the caller's thread");
        check(!client.isOpen(), "isOpen() true after connect failure");
        expectNoClientThreads(500);
        check(rec.events.poll(300, TimeUnit.MILLISECONDS) == null, "event after connect failure");
    }

    /** TCP accepts but the upgrade is never answered: the timeout still bounds it. */
    static void handshakeTimeout() throws Exception {
        Recorder rec = new Recorder();
        WebSocketClient client = new WebSocketClient(host, port, "/mute-handshake", rec);
        long t0 = System.nanoTime();
        client.connect(1500);
        Event e = rec.expect("connectFailed", 2500);
        double after = seconds(e.atNanos - t0);
        System.out.println("connect failed after " + after + " s: " + e.text);
        check(after >= 1.0, "failed before the handshake timeout: " + after);
        expectNoClientThreads(500);
        // The close() that follows a failure is harmless and fires nothing.
        client.close();
        check(rec.events.poll(300, TimeUnit.MILLISECONDS) == null, "event after connect failure");
    }

    static void concurrentSends() throws Exception {
        final Recorder rec = new Recorder();
        final WebSocketClient client = open(rec);
        final int perThread = 200;
        final AtomicReference<String> sendError = new AtomicReference<String>();
        Thread[] senders = new Thread[2];
        for (int t = 0; t < 2; t++) {
            final int id = t;
            senders[t] = new Thread(new Runnable() {
                @Override
                public void run() {
                    for (int seq = 0; seq < perThread; seq++) {
                        byte[] frame = new byte[3528];
                        Arrays.fill(frame, (byte) (0xA0 + id));
                        frame[0] = (byte) id;
                        frame[1] = (byte) (seq >> 8);
                        frame[2] = (byte) seq;
                        if (!client.sendBinary(frame)) {
                            sendError.set("thread " + id + " send " + seq + " failed");
                            return;
                        }
                    }
                }
            }, "harness-sender-" + t);
        }
        for (Thread s : senders) {
            s.start();
        }
        for (Thread s : senders) {
            s.join(20000);
        }
        check(sendError.get() == null, String.valueOf(sendError.get()));
        int[] nextSeq = new int[2];
        for (int i = 0; i < 2 * perThread; i++) {
            Event e = rec.expect("binary", 5000);
            check(e.data.length == 3528, "frame of " + e.data.length + " bytes");
            int id = e.data[0];
            check(id == 0 || id == 1, "bad sender id " + id);
            int seq = ((e.data[1] & 0xFF) << 8) | (e.data[2] & 0xFF);
            check(seq == nextSeq[id], "thread " + id + " frame " + seq + " out of order, wanted " + nextSeq[id]);
            nextSeq[id]++;
            for (int j = 3; j < e.data.length; j++) {
                check(e.data[j] == (byte) (0xA0 + id), "interleaved bytes in thread " + id + " frame " + seq);
            }
        }
        String json = stats(client, rec);
        check(counter(json, "conn", "protocol_errors") == 0, "protocol error: " + json);
        check(counter(json, "conn", "unmasked_rejections") == 0, "unmasked frame: " + json);
        client.close();
    }

    static void serverClose() throws Exception {
        Recorder rec = new Recorder();
        WebSocketClient client = open(rec);
        check(client.sendText("!close"), "send failed");
        Event e = rec.expect("linkLost", 3000);
        check(e.text.contains("1000"), "reason lacks the server's close code: " + e.text);
        expectNoClientThreads(1000);
        // The client answered the close frame: the server counted a close reply.
        Recorder rec2 = new Recorder();
        WebSocketClient probe = open(rec2);
        String json = stats(probe, rec2);
        check(counter(json, "global", "close_replies") >= 1, "client never answered the close: " + json);
        probe.close();
    }

    static void localClose() throws Exception {
        Recorder rec = new Recorder();
        WebSocketClient client = open(rec);
        client.close();
        check(!client.isOpen(), "isOpen() true after close()");
        check(!client.sendText("x"), "send after close() returned true");
        expectNoClientThreads(1000);
        check(rec.events.poll(500, TimeUnit.MILLISECONDS) == null, "callback fired after local close()");
        client.close(); // idempotent
        Recorder rec2 = new Recorder();
        WebSocketClient probe = open(rec2);
        String json = stats(probe, rec2);
        check(counter(json, "global", "client_closes") >= 1, "server never saw a close frame: " + json);
        probe.close();
    }

    /** KTD3: pings go out on schedule even while the uplink is never idle. */
    static void pingUnderTraffic() throws Exception {
        Recorder rec = new Recorder();
        WebSocketClient client = open(rec);
        AtomicBoolean streaming = new AtomicBoolean(true);
        Thread streamer = startStreamer(client, streaming, 2560, 80);
        Thread.sleep(3 * PING_MS + 500);
        streaming.set(false);
        streamer.join(2000);
        Event e;
        while ((e = rec.events.poll(500, TimeUnit.MILLISECONDS)) != null) {
            check("binary".equals(e.kind), "unexpected " + e);
        }
        String json = stats(client, rec);
        long pings = counter(json, "conn", "pings");
        check(pings >= 3, "only " + pings + " pings in " + (3 * PING_MS + 500) + " ms of traffic: " + json);
        client.close();
    }

    static Thread startStreamer(final WebSocketClient client, final AtomicBoolean streaming,
                                final int size, final long periodMs) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] chunk = new byte[size];
                while (streaming.get()) {
                    if (!client.sendBinary(chunk)) {
                        return;
                    }
                    try {
                        Thread.sleep(periodMs);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "harness-streamer");
        t.setDaemon(true);
        t.start();
        return t;
    }
}
