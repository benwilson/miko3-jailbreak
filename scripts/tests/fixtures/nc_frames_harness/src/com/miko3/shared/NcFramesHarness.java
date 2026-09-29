package com.miko3.shared;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Host harness for the NC direction chip (explore plan U2; KTD10 to KTD12):
 * NcFrames' stream format and calibration, and VoiceDirection's NC backend on a
 * fake port setup and a fake port node that streams frames by time, as the chip
 * does on the robot (2026-09-29). Prints "PASS name" or
 * "FAIL name: detail" per scenario (scripts/tests/test_nc_frames.py).
 */
public final class NcFramesHarness {
    static final String PORT = "/dev/ttyS1";
    /** Frames captured on the robot (2026-09-29), CRCs valid. */
    static final String RAW_85 = "58585542a30344010500de9c7afe55f64a03c9";
    static final String RAW_85_NEXT = "58585542a303470105003033cfec55f64a03c9";
    static final String RAW_50 = "58585542a3038f010500d9f5365f320dbed51a";
    /** The other frame type, seen once right after vendor setup: module 03, op 02. */
    static final String OTHER = "58585542030200010500ea6b70ce011bdf05a5";

    interface Scenario {
        void run(String n) throws Exception;
    }

    static void scenario(String name, Scenario s) {
        try {
            s.run(name);
        } catch (Throwable t) {
            System.out.println("FAIL " + name + ": threw " + t);
        }
    }

    static void check(String name, boolean ok, String detail) {
        System.out.println(ok ? "PASS " + name : "FAIL " + name + ": " + detail);
    }

    static byte[] hex(String s) {
        s = s.replace(" ", "");
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    /** A 19-byte reply frame as the chip sends it (seen on the robot, 2026-09-29): XXUB,
     * six header bytes, CRC32 LE over the first 10, then a 5-byte payload. */
    static byte[] frame(int module, int op, int h6, int h7, int h8, int h9, int p0) {
        byte[] f = new byte[19];
        System.arraycopy(hex("58585542"), 0, f, 0, 4);
        f[4] = (byte) module;
        f[5] = (byte) op;
        f[6] = (byte) h6;
        f[7] = (byte) h7;
        f[8] = (byte) h8;
        f[9] = (byte) h9;
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(f, 0, 10);
        long c = crc.getValue();
        for (int i = 0; i < 4; i++) {
            f[10 + i] = (byte) (c >>> (8 * i));
        }
        f[14] = (byte) p0;
        return f;
    }

    /** A direction frame with raw value p0 and sequence number seq. */
    static byte[] direction(int seq, int raw) {
        return frame(0xa3, 3, seq, 1, 5, 0, raw);
    }

    static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] b : parts) {
            n += b.length;
        }
        byte[] out = new byte[n];
        int at = 0;
        for (byte[] b : parts) {
            System.arraycopy(b, 0, out, at, b.length);
            at += b.length;
        }
        return out;
    }

    // ---- fakes ----

    /** The port setup (stty): counts calls, answers initStatus (0 = configured). */
    static final class FakeNative implements VoiceDirection.PortSetup {
        int creates;
        int inits;
        int initStatus;
        String node;

        public int configure(String path) {
            creates++;
            inits++;
            node = path;
            return initStatus;
        }
    }

    /** A port that streams: bytes fed with a due time become readable once it passes.
     * Every write is recorded (and must never happen). */
    static final class FakePort implements VoiceDirection.Port {
        final long t0 = System.nanoTime();
        final List<byte[]> writes = new ArrayList<byte[]>();
        final List<Byte> pending = new ArrayList<Byte>();
        final List<Long> due = new ArrayList<Long>();
        boolean closed;
        boolean failReads;

        /** Bytes readable atMs after the port was made; feed in time order. */
        synchronized FakePort feedAt(long atMs, byte[] bytes) {
            long at = t0 + atMs * 1000000L;
            for (byte b : bytes) {
                pending.add(b);
                due.add(at);
            }
            return this;
        }

        /** Bytes readable now. */
        synchronized FakePort feed(byte[] bytes) {
            return feedAt((System.nanoTime() - t0) / 1000000L, bytes);
        }

        public synchronized int available() throws IOException {
            if (failReads) {
                throw new IOException("gone");
            }
            long now = System.nanoTime();
            int n = 0;
            while (n < due.size() && due.get(n) <= now) {
                n++;
            }
            return n;
        }

        public synchronized int read(byte[] b, int off, int len) throws IOException {
            int n = Math.min(len, available());
            for (int i = 0; i < n; i++) {
                b[off + i] = pending.remove(0);
                due.remove(0);
            }
            return n;
        }

        public synchronized void write(byte[] frame) throws IOException {
            writes.add(frame.clone());
            if (closed) {
                throw new IOException("closed");
            }
        }

        public synchronized void close() {
            closed = true;
        }
    }

    static final class FakeNodes implements VoiceDirection.Nodes {
        final Set<String> present = new HashSet<String>();
        final FakePort port;
        int opens;

        FakeNodes(FakePort port, String... nodes) {
            this.port = port;
            for (String s : nodes) {
                present.add(s);
            }
        }

        public boolean exists(String path) {
            return present.contains(path);
        }

        public VoiceDirection.Port open(String path) throws IOException {
            opens++;
            if (!present.contains(path)) {
                throw new IOException("no " + path);
            }
            return port;
        }
    }

    static final class Lines implements VoiceDirection.Logger {
        final List<String> lines = new ArrayList<String>();

        public synchronized void log(String msg) {
            lines.add(msg);
        }

        synchronized int count(String needle) {
            int n = 0;
            for (String s : lines) {
                if (s.contains(needle)) {
                    n++;
                }
            }
            return n;
        }
    }

    /** A port with one direction frame (raw) readable at once. */
    static FakePort streaming(int raw) {
        return new FakePort().feedAt(0, direction(1, raw));
    }

    /** The raw values of every direction frame in buf, and how many bytes the parse consumed. */
    static List<Integer> raws(byte[] buf, int[] consumed) {
        final List<Integer> out = new ArrayList<Integer>();
        int used = NcFrames.parseStream(buf, buf.length, new NcFrames.Sink() {
            public void direction(byte[] b, int at, int raw) {
                out.add(raw);
            }
        });
        if (consumed != null) {
            consumed[0] = used;
        }
        return out;
    }

    static VoiceDirection.Config uncalibrated() {
        return VoiceDirection.Config.of(PORT, "", "", "");
    }

    static VoiceDirection open(VoiceDirection.Config cfg, FakeNative nat, FakeNodes nodes, Lines log) {
        return VoiceDirection.openWith(cfg, nat, nodes, VoiceDirection.FIRST_FRAME_MS, VoiceDirection.FRESH_MS, log);
    }

    static boolean near(float a, float b) {
        return Math.abs(a - b) < 1e-3f;
    }

    public static void main(String[] args) {
        scenario("robot_frames_parse_to_85_and_50_and_the_03_02_frame_is_ignored", new Scenario() {
            public void run(String n) {
                List<Integer> a = raws(hex(RAW_85), null);
                List<Integer> b = raws(hex(RAW_85_NEXT), null);
                List<Integer> c = raws(hex(RAW_50), null);
                int[] used = new int[1];
                List<Integer> other = raws(hex(OTHER), used);
                List<Integer> mixed = raws(hex(OTHER + RAW_50), null);
                check(n, a.equals(java.util.Arrays.asList(85)) && b.equals(java.util.Arrays.asList(85))
                                && c.equals(java.util.Arrays.asList(50)) && other.isEmpty() && used[0] == 19
                                && mixed.equals(java.util.Arrays.asList(50)),
                        "a=" + a + " b=" + b + " c=" + c + " other=" + other + " mixed=" + mixed);
            }
        });
        scenario("junk_a_torn_frame_and_a_bad_crc_are_skipped", new Scenario() {
            public void run(String n) {
                byte[] bad = hex(RAW_85_NEXT);
                bad[12] ^= 1;
                byte[] torn = java.util.Arrays.copyOf(hex(RAW_50), 10);
                byte[] buf = concat(hex("0102035858"), hex(RAW_85), bad, hex(OTHER), direction(9, 70), torn);
                int[] used = new int[1];
                List<Integer> got = raws(buf, used);
                // Across reads: the torn frame's tail arrives later and completes it.
                FakePort port = new FakePort().feedAt(0, buf);
                VoiceDirection d = open(uncalibrated(), new FakeNative(), new FakeNodes(port, PORT), new Lines());
                int before = d.lastRaw();
                port.feed(java.util.Arrays.copyOfRange(hex(RAW_50), 10, 19));
                d.degrees();
                check(n, got.equals(java.util.Arrays.asList(85, 70)) && used[0] == buf.length - 10
                                && d.backend() == VoiceDirection.Backend.NC && before == 70 && d.lastRaw() == 50,
                        "got=" + got + " used=" + used[0] + "/" + buf.length + " before=" + before
                                + " after=" + d.lastRaw() + " backend=" + d.backend());
            }
        });
        scenario("the_newest_frame_wins", new Scenario() {
            public void run(String n) {
                FakePort port = new FakePort().feedAt(0, concat(hex(RAW_85), hex(RAW_50)));
                VoiceDirection d = open(uncalibrated(), new FakeNative(), new FakeNodes(port, PORT), new Lines());
                int first = d.lastRaw();
                port.feed(concat(direction(10, 60), direction(11, 65), direction(12, 90)));
                d.degrees();
                check(n, first == 50 && d.lastRaw() == 90, "first=" + first + " then=" + d.lastRaw());
            }
        });
        scenario("a_stale_reading_is_nan_and_a_fresh_one_is_degrees", new Scenario() {
            public void run(String n) throws Exception {
                FakePort port = streaming(74);
                VoiceDirection d = open(VoiceDirection.Config.of(PORT, "10", "-1", "360/256"), new FakeNative(),
                        new FakeNodes(port, PORT), new Lines());
                float fresh = d.degrees();
                Thread.sleep(VoiceDirection.FRESH_MS + 150);
                float stale = d.degrees();
                Float boxed = d.angle();
                port.feed(direction(2, 74));
                float again = d.degrees();
                check(n, d.backend() == VoiceDirection.Backend.NC && near(fresh, -90f) && Float.isNaN(stale)
                                && boxed == null && near(again, -90f) && d.lastRaw() == 74,
                        "fresh=" + fresh + " stale=" + stale + " again=" + again);
            }
        });
        scenario("no_frame_within_first_frame_ms_is_none", new Scenario() {
            public void run(String n) {
                // Junk and the other frame type only: no direction frame ever comes.
                FakePort port = new FakePort().feedAt(0, hex("0102")).feedAt(300, hex(OTHER));
                Lines log = new Lines();
                long t0 = System.nanoTime();
                VoiceDirection d = open(uncalibrated(), new FakeNative(), new FakeNodes(port, PORT), log);
                long ms = (System.nanoTime() - t0) / 1000000L;
                check(n, d.backend() == VoiceDirection.Backend.NONE && port.closed
                                && d.detail().contains("nc: no frames on " + PORT) && d.firstReplyHex() == null
                                && ms >= VoiceDirection.FIRST_FRAME_MS - 20 && ms < VoiceDirection.FIRST_FRAME_MS + 500
                                && Float.isNaN(d.degrees()),
                        "backend=" + d.backend() + " detail=" + d.detail() + " ms=" + ms);
            }
        });
        scenario("a_frame_at_200_ms_opens_nc_with_it_as_the_first_reply", new Scenario() {
            public void run(String n) {
                FakePort port = new FakePort().feedAt(100, hex(OTHER)).feedAt(200, hex(RAW_85));
                long t0 = System.nanoTime();
                VoiceDirection d = open(uncalibrated(), new FakeNative(), new FakeNodes(port, PORT), new Lines());
                long ms = (System.nanoTime() - t0) / 1000000L;
                check(n, d.backend() == VoiceDirection.Backend.NC && RAW_85.equals(d.firstReplyHex())
                                && d.lastRaw() == 85 && !port.closed && ms >= 150 && ms < 1000,
                        "backend=" + d.backend() + " first=" + d.firstReplyHex() + " ms=" + ms);
            }
        });
        scenario("the_port_is_never_written_to", new Scenario() {
            public void run(String n) throws Exception {
                FakePort port = new FakePort();
                for (int i = 0; i < 20; i++) {
                    port.feedAt(50L * i, direction(i, 55 + (i % 8) * 5));
                }
                VoiceDirection d = open(VoiceDirection.Config.of(PORT, "10", "1", "1"), new FakeNative(),
                        new FakeNodes(port, PORT), new Lines());
                for (int i = 0; i < 20; i++) {
                    d.degrees();
                    Thread.sleep(20);
                }
                check(n, d.backend() == VoiceDirection.Backend.NC && port.writes.isEmpty(),
                        "backend=" + d.backend() + " writes=" + port.writes.size());
            }
        });
        scenario("a_read_error_closes_the_backend_and_logs_once", new Scenario() {
            public void run(String n) {
                FakePort port = streaming(80);
                Lines log = new Lines();
                VoiceDirection d = open(VoiceDirection.Config.of(PORT, "10", "1", "1"), new FakeNative(),
                        new FakeNodes(port, PORT), log);
                boolean wasNc = d.backend() == VoiceDirection.Backend.NC;
                port.failReads = true;
                boolean nan = true;
                for (int i = 0; i < 3; i++) {
                    nan &= Float.isNaN(d.degrees());
                }
                check(n, wasNc && nan && d.backend() == VoiceDirection.Backend.NONE && port.closed
                                && log.count("nc read failed") == 1,
                        "wasNc=" + wasNc + " backend=" + d.backend() + " log=" + log.lines);
            }
        });
        scenario("stty_input_flags_are_zeroed_from_the_g_string", new Scenario() {
            public void run(String n) {
                String g = "ffffff14:0:18b2:0:0:0:0:0:0:0:1:0:0:0:0:0:0:0:0:0:0:0:0";
                String z = VoiceDirection.zeroInputFlags(g);
                String zn = VoiceDirection.zeroInputFlags(g + "\n");
                boolean ok = "0:0:18b2:0:0:0:0:0:0:0:1:0:0:0:0:0:0:0:0:0:0:0:0".equals(z) && z.equals(zn)
                        && VoiceDirection.inputFlagsZero(z) && VoiceDirection.inputFlagsZero(z + "\n")
                        && !VoiceDirection.inputFlagsZero(g)
                        && VoiceDirection.zeroInputFlags(null) == null
                        && VoiceDirection.zeroInputFlags("") == null
                        && VoiceDirection.zeroInputFlags("ff:0:18b2") == null
                        && VoiceDirection.zeroInputFlags("ff:zz:18b2:0") == null
                        && VoiceDirection.zeroInputFlags("ff::18b2:0") == null
                        && VoiceDirection.zeroInputFlags("stty: /dev/ttyS1: No such file") == null
                        && !VoiceDirection.inputFlagsZero(null)
                        && !VoiceDirection.inputFlagsZero("0:0")
                        && !VoiceDirection.inputFlagsZero("0:zz:1:2")
                        && !VoiceDirection.inputFlagsZero("00:0:18b2:0");
                check(n, ok, "z=" + z);
            }
        });
        scenario("stty_setup_sets_115200_8n1_without_echo_or_the_raw_keyword", new Scenario() {
            // toybox 0.7.6 stty's `raw` sets icrnl/ixon/ixoff/inpck and cannot clear them by
            // flag (robot, 2026-09-29): the input flags are zeroed through -g instead.
            public void run(String n) {
                java.util.List<String> cmd = VoiceDirection.sttyCommand("/dev/ttyS1");
                String joined = String.join(" ", cmd);
                java.util.List<String> read = VoiceDirection.sttyReadCommand("/dev/ttyS1");
                java.util.List<String> write = VoiceDirection.sttyWriteCommand("/dev/ttyS1", "0:0:18b2:0");
                boolean ok = cmd.subList(0, 3).equals(java.util.Arrays.asList("stty", "-F", "/dev/ttyS1"));
                for (String f : new String[]{"115200", "cs8", "-cstopb", "-parenb", "clocal", "-crtscts", "-hupcl",
                        "-opost", "-isig", "-icanon", "-iexten", "-echo", "-echoe", "-echok", "-echonl"}) {
                    ok &= cmd.contains(f);
                }
                check(n, ok && !cmd.contains("raw") && !cmd.contains("-ixon") && !cmd.contains("-icrnl")
                                && !joined.contains(">")
                                && read.equals(java.util.Arrays.asList("stty", "-F", "/dev/ttyS1", "-g"))
                                && write.equals(java.util.Arrays.asList("stty", "-F", "/dev/ttyS1", "0:0:18b2:0")),
                        joined + " | " + read + " | " + write);
            }
        });
        scenario("calibration_maps_raw_to_signed_degrees", new Scenario() {
            public void run(String n) {
                NcFrames.Calibration zero = NcFrames.Calibration.parse("10", "1", "1.40625");
                NcFrames.Calibration flip = NcFrames.Calibration.parse("10", "-1", "360/256");
                float a = NcFrames.degrees(10, zero);
                float b = NcFrames.degrees(74, flip);
                check(n, zero != null && flip != null && near(a, 0f) && near(b, -90f), "a=" + a + " b=" + b);
            }
        });
        scenario("calibration_wraps_to_plus_minus_180", new Scenario() {
            public void run(String n) {
                NcFrames.Calibration c = NcFrames.Calibration.parse("0", "1", "360/256");
                NcFrames.Calibration back = NcFrames.Calibration.parse("138", "1", "360/256");
                float a = NcFrames.degrees(200, c);   // 281.25 -> -78.75
                float b = NcFrames.degrees(10, back); // -180
                float d = NcFrames.degrees(128, c);   // 180 -> -180
                float e = NcFrames.degrees(255, NcFrames.Calibration.parse("0", "-1", "360/256")); // -358.6 -> 1.4
                check(n, near(a, -78.75f) && near(Math.abs(b), 180f) && near(Math.abs(d), 180f)
                                && near(e, 1.40625f) && a >= -180f && a <= 180f,
                        "a=" + a + " b=" + b + " d=" + d + " e=" + e);
            }
        });
        scenario("uncalibrated_is_nan", new Scenario() {
            public void run(String n) {
                boolean ok = NcFrames.Calibration.parse("", "1", "1.4") == null
                        && NcFrames.Calibration.parse("10", "", "1.4") == null
                        && NcFrames.Calibration.parse("10", "1", "") == null
                        && NcFrames.Calibration.parse("10", "0", "1.4") == null
                        && NcFrames.Calibration.parse("10", "1", "0") == null
                        && NcFrames.Calibration.parse("x", "1", "1.4") == null
                        && NcFrames.Calibration.parse("10", "1", "1/0") == null
                        && Float.isNaN(NcFrames.degrees(74, null))
                        && Float.isNaN(NcFrames.degrees(-1, NcFrames.Calibration.parse("10", "1", "1")));
                check(n, ok, "an uncalibrated mapping gave an angle");
            }
        });
        scenario("unset_port_is_none_with_no_native_call", new Scenario() {
            public void run(String n) {
                FakeNative nat = new FakeNative();
                FakeNodes nodes = new FakeNodes(streaming(100), PORT);
                VoiceDirection d = open(VoiceDirection.Config.of("", "10", "1", "1"), nat, nodes, new Lines());
                VoiceDirection d2 = open(VoiceDirection.Config.of(null, "10", "1", "1"), nat, nodes, new Lines());
                check(n, d.backend() == VoiceDirection.Backend.NONE && d2.backend() == VoiceDirection.Backend.NONE
                                && nat.creates == 0 && nat.inits == 0 && nodes.opens == 0
                                && Float.isNaN(d.degrees()) && d.angle() == null,
                        "backend=" + d.backend() + " creates=" + nat.creates + " opens=" + nodes.opens);
            }
        });
        scenario("missing_node_is_none_with_no_native_call", new Scenario() {
            public void run(String n) {
                FakeNative nat = new FakeNative();
                FakeNodes nodes = new FakeNodes(streaming(100));
                VoiceDirection d = open(uncalibrated(), nat, nodes, new Lines());
                check(n, d.backend() == VoiceDirection.Backend.NONE && nat.creates == 0 && nat.inits == 0
                                && nodes.opens == 0 && d.detail().contains(PORT),
                        "backend=" + d.backend() + " creates=" + nat.creates + " detail=" + d.detail());
            }
        });
        scenario("a_failed_stty_is_none", new Scenario() {
            public void run(String n) {
                FakeNative nat = new FakeNative();
                nat.initStatus = 1;
                FakeNodes nodes = new FakeNodes(streaming(100), PORT);
                VoiceDirection d = open(uncalibrated(), nat, nodes, new Lines());
                check(n, d.backend() == VoiceDirection.Backend.NONE && nat.inits == 1 && nodes.opens == 0
                                && PORT.equals(nat.node) && d.detail().contains("stty 1"),
                        "backend=" + d.backend() + " opens=" + nodes.opens + " detail=" + d.detail());
            }
        });
        scenario("confirmed_port_opens_nc_and_reports_calibrated_degrees", new Scenario() {
            public void run(String n) {
                FakeNative nat = new FakeNative();
                FakePort port = streaming(74);
                VoiceDirection d = open(VoiceDirection.Config.of(PORT, "10", "-1", "360/256"), nat,
                        new FakeNodes(port, PORT), new Lines());
                float a = d.degrees();
                Float boxed = d.angle();
                check(n, d.backend() == VoiceDirection.Backend.NC && near(a, -90f) && boxed != null
                                && near(boxed, -90f) && d.lastRaw() == 74 && nat.inits == 1,
                        "backend=" + d.backend() + " a=" + a + " detail=" + d.detail());
            }
        });
        scenario("uncalibrated_nc_gives_nan_but_keeps_the_raw_value", new Scenario() {
            public void run(String n) {
                FakePort port = streaming(138);
                Lines log = new Lines();
                VoiceDirection d = open(uncalibrated(), new FakeNative(), new FakeNodes(port, PORT), log);
                float a = d.degrees();
                d.degrees();
                check(n, d.backend() == VoiceDirection.Backend.NC && Float.isNaN(a) && d.angle() == null
                                && d.lastRaw() == 138 && log.count("voice direction: nc raw 138 (uncalibrated)") == 1,
                        "backend=" + d.backend() + " a=" + a + " raw=" + d.lastRaw() + " log=" + log.lines);
            }
        });
        scenario("first_frames_are_logged_in_hex", new Scenario() {
            public void run(String n) {
                FakePort port = new FakePort().feedAt(0, hex(RAW_85));
                Lines log = new Lines();
                VoiceDirection d = open(uncalibrated(), new FakeNative(), new FakeNodes(port, PORT), log);
                for (int i = 0; i < 10; i++) {
                    port.feed(direction(i, 60));
                    d.degrees();
                }
                check(n, RAW_85.equals(d.firstReplyHex()) && log.count("voice direction: nc frame " + RAW_85) == 1
                                && log.count("voice direction: nc frame ") == VoiceDirection.LOGGED_FRAMES,
                        "first=" + d.firstReplyHex() + " log=" + log.lines);
            }
        });
        scenario("lazy_sampler_opens_on_its_own_thread_and_never_blocks_the_caller", new Scenario() {
            // Review P1 (2026-09-29): the first open ran on the ears capture thread holding
            // feedLock, so a tty that blocked on open or read would stall the ears.
            public void run(String n) throws Exception {
                final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
                final String[] openedOn = new String[1];
                final VoiceDirection ready = open(uncalibrated(), new FakeNative(),
                        new FakeNodes(streaming(138), PORT), new Lines());
                long t0 = System.nanoTime();
                VoiceDirection.Sampler s = VoiceDirection.sampleLazily(20, new java.util.concurrent.Callable<VoiceDirection>() {
                    public VoiceDirection call() throws Exception {
                        openedOn[0] = Thread.currentThread().getName();
                        release.await();
                        return ready;
                    }
                });
                long startMs = (System.nanoTime() - t0) / 1000000L;
                Thread.sleep(100);
                int whileBlocked = s.drain().size();
                release.countDown();
                Thread.sleep(150);
                int after = s.drain().size();
                s.stop();
                check(n, startMs < 20 && whileBlocked == 0 && after > 0
                                && "voice-direction".equals(openedOn[0]),
                        "start=" + startMs + "ms blocked=" + whileBlocked + " after=" + after + " on=" + openedOn[0]);
            }
        });
        scenario("config_reads_port_and_calibration", new Scenario() {
            public void run(String n) {
                VoiceDirection.Config c = VoiceDirection.Config.of(" /dev/ttyS1 ", "10", "-1", "1.40625");
                VoiceDirection.Config u = VoiceDirection.Config.of("/dev/ttyS1", "10", "", "1.40625");
                check(n, PORT.equals(c.port) && c.calibration != null && u.calibration == null,
                        "port=" + c.port);
            }
        });
        // Robot finding (2026-09-29): the chip tells only left from right (right ~35,
        // front ~80, behind ~90, left ~100), so the owner uses it for the side alone.
        scenario("side_thresholds_map_raw_to_left_right_or_neither", new Scenario() {
            public void run(String n) {
                NcFrames.Sides s = NcFrames.Sides.parse("100", "60");
                StringBuilder got = new StringBuilder();
                boolean ok = s != null;
                if (ok) {
                    int[] raws = {35, 60, 61, 80, 99, 100, 110, -1};
                    float[] want = {90f, 90f, Float.NaN, Float.NaN, Float.NaN, -90f, -90f, Float.NaN};
                    for (int i = 0; i < raws.length; i++) {
                        float d = NcFrames.sideDegrees(raws[i], s);
                        got.append(raws[i]).append("->").append(d).append(' ');
                        ok &= Float.isNaN(want[i]) ? Float.isNaN(d) : near(d, want[i]);
                    }
                    ok &= Float.isNaN(NcFrames.sideDegrees(35, null));
                }
                check(n, ok, "sides=" + s + " " + got);
            }
        });
        scenario("config_of_six_arguments_parses_both_thresholds", new Scenario() {
            public void run(String n) {
                VoiceDirection.Config c = VoiceDirection.Config.of(" /dev/ttyS1 ", "", "", "", " 100 ", "60");
                VoiceDirection.Config four = VoiceDirection.Config.of(PORT, "", "", "");
                check(n, PORT.equals(c.port) && c.calibration == null && c.sides != null && c.sides.left == 100
                                && c.sides.right == 60 && four.sides == null,
                        "port=" + c.port + " sides=" + c.sides + " four=" + four.sides);
            }
        });
        scenario("calibration_wins_over_side_thresholds", new Scenario() {
            public void run(String n) {
                VoiceDirection.Config c = VoiceDirection.Config.of(PORT, "10", "-1", "360/256", "100", "60");
                Lines log = new Lines();
                VoiceDirection d = open(c, new FakeNative(), new FakeNodes(streaming(35), PORT), log);
                float a = d.degrees(); // calibrated: -(35 - 10) * 1.40625; side mode would say +90
                check(n, c.calibration != null && c.sides == null && d.backend() == VoiceDirection.Backend.NC
                                && near(a, -35.15625f) && !d.sideOnly() && d.detail().endsWith("calibrated")
                                && log.count("side mode") == 0 && log.count("(side)") == 0,
                        "a=" + a + " sideOnly=" + d.sideOnly() + " detail=" + d.detail() + " log=" + log.lines);
            }
        });
        scenario("bad_side_thresholds_give_no_side_mode", new Scenario() {
            public void run(String n) {
                boolean parse = NcFrames.Sides.parse("60", "100") == null
                        && NcFrames.Sides.parse("100", "100") == null
                        && NcFrames.Sides.parse("x", "60") == null
                        && NcFrames.Sides.parse("100", "y") == null
                        && NcFrames.Sides.parse("256", "60") == null
                        && NcFrames.Sides.parse("100", "-1") == null
                        && NcFrames.Sides.parse("100.5", "60") == null
                        && NcFrames.Sides.parse("", "") == null
                        && NcFrames.Sides.parse(null, null) == null
                        && NcFrames.Sides.parse("255", "0") != null;
                VoiceDirection.Config c = VoiceDirection.Config.of(PORT, "", "", "", "60", "100");
                Lines log = new Lines();
                VoiceDirection d = open(c, new FakeNative(), new FakeNodes(streaming(35), PORT), log);
                check(n, parse && c.sides == null && d.backend() == VoiceDirection.Backend.NC && !d.sideOnly()
                                && Float.isNaN(d.degrees()) && log.count("nc raw 35 (uncalibrated)") == 1
                                && log.count("side mode") == 0,
                        "parse=" + parse + " sides=" + c.sides + " sideOnly=" + d.sideOnly() + " log=" + log.lines);
            }
        });
        scenario("side_mode_nc_gives_plus_minus_90_and_logs_its_raw_values", new Scenario() {
            public void run(String n) throws Exception {
                FakePort port = streaming(35);
                Lines log = new Lines();
                VoiceDirection d = open(VoiceDirection.Config.of(PORT, "", "", "", "100", "60"), new FakeNative(),
                        new FakeNodes(port, PORT), log);
                float right = d.degrees();
                Float boxed = d.angle();
                port.feed(direction(2, 100));
                float left = d.degrees();
                port.feed(direction(3, 80));
                float ahead = d.degrees();
                port.feed(direction(4, 105));
                d.degrees();
                Thread.sleep(VoiceDirection.FRESH_MS + 150);
                float stale = d.degrees();
                check(n, d.backend() == VoiceDirection.Backend.NC && d.sideOnly() && near(right, 90f)
                                && boxed != null && near(boxed, 90f) && near(left, -90f) && Float.isNaN(ahead)
                                && Float.isNaN(stale) && ("nc on " + PORT + ", side").equals(d.detail())
                                && log.count("voice direction: nc side mode (left >= 100, right <= 60)") == 1
                                && log.count("voice direction: nc raw 35 (side)") == 1
                                && log.count("(uncalibrated)") == 0 && log.count("voice direction: nc raw ") == 1,
                        "right=" + right + " left=" + left + " ahead=" + ahead + " stale=" + stale + " detail="
                                + d.detail() + " log=" + log.lines);
            }
        });
        scenario("side_only_is_false_without_an_nc_backend", new Scenario() {
            public void run(String n) {
                VoiceDirection missing = open(VoiceDirection.Config.of(PORT, "", "", "", "100", "60"), new FakeNative(),
                        new FakeNodes(streaming(35)), new Lines());
                VoiceDirection plain = open(uncalibrated(), new FakeNative(), new FakeNodes(streaming(35), PORT),
                        new Lines());
                // The process instance was never opened here.
                check(n, missing.backend() == VoiceDirection.Backend.NONE && !missing.sideOnly() && !plain.sideOnly()
                                && !VoiceDirection.sideOnlyConfigured(),
                        "missing=" + missing.sideOnly() + " plain=" + plain.sideOnly());
            }
        });
    }
}
