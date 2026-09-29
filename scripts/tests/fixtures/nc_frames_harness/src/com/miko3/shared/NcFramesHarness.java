package com.miko3.shared;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Host harness for the NC direction chip (explore plan U2; KTD10 to KTD12):
 * NcFrames' byte format and calibration, and VoiceDirection's NC backend on a
 * fake native layer and a fake port node. Prints "PASS name" or
 * "FAIL name: detail" per scenario (scripts/tests/test_nc_frames.py).
 */
public final class NcFramesHarness {
    static final String PORT = "/dev/ttyS1";
    static final long DEADLINE_MS = 60;

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

    /** A 38-byte reply with reporting on and raw value raw at byte 0x21. */
    static byte[] onReply(int raw) {
        byte[] r = new byte[38];
        System.arraycopy(hex("58585542"), 0, r, 0, 4);
        r[4] = 3;
        r[5] = 1;
        r[14] = 1;
        r[0x21] = (byte) raw;
        return r;
    }

    /** A 15-byte reply with reporting off (status byte 14 = 0). */
    static byte[] offReply() {
        byte[] r = new byte[15];
        System.arraycopy(hex("58585542"), 0, r, 0, 4);
        r[4] = 3;
        r[5] = 1;
        return r;
    }

    // ---- fakes ----

    static final class FakeNative implements VoiceDirection.NcNative {
        int creates;
        int inits;
        int initStatus;
        String node;

        public long createUART(int buffer, String path) {
            creates++;
            node = path;
            return 42;
        }

        public int initNCUART(long handle) {
            inits++;
            return initStatus;
        }
    }

    /** What the fake chip answers to a write: the bytes, and after how long. */
    interface Chip {
        byte[] answer(byte[] frame, int writeNo);

        long delayMs(int writeNo);
    }

    static final class FakePort implements VoiceDirection.Port {
        final Chip chip;
        final List<byte[]> writes = new ArrayList<byte[]>();
        final List<Byte> pending = new ArrayList<Byte>();
        final List<Long> due = new ArrayList<Long>();
        boolean closed;
        int flushed;

        FakePort(Chip chip) {
            this.chip = chip;
        }

        synchronized void preload(byte[] bytes) {
            for (byte b : bytes) {
                pending.add(b);
                due.add(0L);
            }
        }

        public synchronized int available() {
            long now = System.nanoTime();
            int n = 0;
            while (n < due.size() && due.get(n) <= now) {
                n++;
            }
            return n;
        }

        public synchronized int read(byte[] b, int off, int len) {
            int n = Math.min(len, available());
            for (int i = 0; i < n; i++) {
                b[off + i] = pending.remove(0);
                due.remove(0);
            }
            return n;
        }

        public synchronized void write(byte[] frame) throws IOException {
            if (closed) {
                throw new IOException("closed");
            }
            writes.add(frame.clone());
            byte[] a = chip.answer(frame, writes.size());
            if (a != null) {
                long at = System.nanoTime() + chip.delayMs(writes.size()) * 1000000L;
                for (byte b : a) {
                    pending.add(b);
                    due.add(at);
                }
            }
        }

        public synchronized void close() {
            closed = true;
        }

        synchronized int count(byte[] frame) {
            int n = 0;
            for (byte[] w : writes) {
                if (java.util.Arrays.equals(w, frame)) {
                    n++;
                }
            }
            return n;
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

    /** A chip that answers every GET with raw at once. */
    static Chip steady(final int raw) {
        return new Chip() {
            public byte[] answer(byte[] frame, int writeNo) {
                return java.util.Arrays.equals(frame, NcFrames.doaQuery()) ? onReply(raw) : null;
            }

            public long delayMs(int writeNo) {
                return 0;
            }
        };
    }

    static VoiceDirection.Config uncalibrated() {
        return VoiceDirection.Config.of(PORT, "", "", "");
    }

    static VoiceDirection open(VoiceDirection.Config cfg, FakeNative nat, FakeNodes nodes, Lines log) {
        return VoiceDirection.openWith(cfg, nat, nodes, DEADLINE_MS, log);
    }

    static boolean near(float a, float b) {
        return Math.abs(a - b) < 1e-3f;
    }

    public static void main(String[] args) {
        scenario("query_frame_matches_the_decoded_bytes", new Scenario() {
            public void run(String n) {
                byte[] q = NcFrames.doaQuery();
                check(n, java.util.Arrays.equals(q, hex("58585542 03 01 03 000000 a620d0e7"))
                                && java.util.Arrays.equals(NcFrames.request(3, 1, 3), q),
                        "got " + NcFrames.hex(q, q.length));
            }
        });
        scenario("toggle_frame_matches_the_decoded_bytes", new Scenario() {
            public void run(String n) {
                byte[] t = NcFrames.doaToggle();
                check(n, java.util.Arrays.equals(t, hex("58585542 03 03 02 000000 a314ac25")),
                        "got " + NcFrames.hex(t, t.length));
            }
        });
        scenario("status_on_reply_parses_to_raw_138", new Scenario() {
            public void run(String n) {
                byte[] r = onReply(138);
                int p = NcFrames.parseDoa(r, r.length);
                check(n, p == 138 && NcFrames.rawOrNaN(r, r.length) == 138f, "parsed " + p);
            }
        });
        scenario("status_off_reply_reports_off", new Scenario() {
            public void run(String n) {
                byte[] r = offReply();
                int p = NcFrames.parseDoa(r, r.length);
                check(n, p == NcFrames.OFF && Float.isNaN(NcFrames.rawOrNaN(r, r.length)), "parsed " + p);
            }
        });
        scenario("truncated_or_unprefixed_reply_is_nan", new Scenario() {
            public void run(String n) {
                byte[] on = onReply(138);
                byte[] bad = onReply(138);
                bad[0] = 0x59;
                boolean ok = Float.isNaN(NcFrames.rawOrNaN(on, 37))
                        && Float.isNaN(NcFrames.rawOrNaN(on, 15))
                        && Float.isNaN(NcFrames.rawOrNaN(on, 14))
                        && Float.isNaN(NcFrames.rawOrNaN(bad, bad.length))
                        && Float.isNaN(NcFrames.rawOrNaN(new byte[0], 0))
                        && Float.isNaN(NcFrames.rawOrNaN(null, 0))
                        && NcFrames.parseDoa(on, 20) == NcFrames.MALFORMED
                        && NcFrames.parseDoa(bad, bad.length) == NcFrames.MALFORMED;
                check(n, ok, "a malformed reply parsed");
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
                        && Float.isNaN(NcFrames.degrees(NcFrames.OFF, NcFrames.Calibration.parse("10", "1", "1")));
                check(n, ok, "an uncalibrated mapping gave an angle");
            }
        });
        scenario("unset_port_is_none_with_no_native_call", new Scenario() {
            public void run(String n) {
                FakeNative nat = new FakeNative();
                FakeNodes nodes = new FakeNodes(new FakePort(steady(100)), PORT);
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
                FakeNodes nodes = new FakeNodes(new FakePort(steady(100)));
                VoiceDirection d = open(uncalibrated(), nat, nodes, new Lines());
                check(n, d.backend() == VoiceDirection.Backend.NONE && nat.creates == 0 && nat.inits == 0
                                && nodes.opens == 0 && d.detail().contains(PORT),
                        "backend=" + d.backend() + " creates=" + nat.creates + " detail=" + d.detail());
            }
        });
        scenario("init_returning_one_is_none", new Scenario() {
            public void run(String n) {
                FakeNative nat = new FakeNative();
                nat.initStatus = 1;
                FakeNodes nodes = new FakeNodes(new FakePort(steady(100)), PORT);
                VoiceDirection d = open(uncalibrated(), nat, nodes, new Lines());
                check(n, d.backend() == VoiceDirection.Backend.NONE && nat.inits == 1 && nodes.opens == 0
                                && PORT.equals(nat.node) && d.detail().contains("init 1"),
                        "backend=" + d.backend() + " opens=" + nodes.opens + " detail=" + d.detail());
            }
        });
        scenario("confirmed_port_opens_nc_and_reports_calibrated_degrees", new Scenario() {
            public void run(String n) {
                FakeNative nat = new FakeNative();
                FakePort port = new FakePort(steady(74));
                VoiceDirection d = open(VoiceDirection.Config.of(PORT, "10", "-1", "360/256"), nat,
                        new FakeNodes(port, PORT), new Lines());
                float a = d.degrees();
                Float boxed = d.angle();
                check(n, d.backend() == VoiceDirection.Backend.NC && near(a, -90f) && boxed != null
                                && near(boxed, -90f) && d.lastRaw() == 74 && port.count(NcFrames.doaToggle()) == 0,
                        "backend=" + d.backend() + " a=" + a + " detail=" + d.detail());
            }
        });
        scenario("uncalibrated_nc_gives_nan_but_keeps_the_raw_value", new Scenario() {
            public void run(String n) {
                FakePort port = new FakePort(steady(138));
                VoiceDirection d = open(uncalibrated(), new FakeNative(), new FakeNodes(port, PORT), new Lines());
                float a = d.degrees();
                check(n, d.backend() == VoiceDirection.Backend.NC && Float.isNaN(a) && d.angle() == null
                                && d.lastRaw() == 138,
                        "backend=" + d.backend() + " a=" + a + " raw=" + d.lastRaw());
            }
        });
        scenario("status_off_toggles_once_per_open", new Scenario() {
            public void run(String n) {
                FakePort port = new FakePort(new Chip() {
                    public byte[] answer(byte[] frame, int writeNo) {
                        return java.util.Arrays.equals(frame, NcFrames.doaQuery()) ? offReply() : null;
                    }

                    public long delayMs(int writeNo) {
                        return 0;
                    }
                });
                VoiceDirection d = open(VoiceDirection.Config.of(PORT, "10", "1", "1"), new FakeNative(),
                        new FakeNodes(port, PORT), new Lines());
                boolean nan = true;
                for (int i = 0; i < 5; i++) {
                    nan &= Float.isNaN(d.degrees());
                }
                check(n, d.backend() == VoiceDirection.Backend.NC && nan && port.count(NcFrames.doaToggle()) == 1
                                && d.backend() == VoiceDirection.Backend.NC,
                        "backend=" + d.backend() + " toggles=" + port.count(NcFrames.doaToggle()));
            }
        });
        scenario("a_status_on_reply_never_toggles", new Scenario() {
            public void run(String n) {
                FakePort port = new FakePort(steady(20));
                VoiceDirection d = open(uncalibrated(), new FakeNative(), new FakeNodes(port, PORT), new Lines());
                for (int i = 0; i < 3; i++) {
                    d.degrees();
                }
                check(n, port.count(NcFrames.doaToggle()) == 0, "toggles=" + port.count(NcFrames.doaToggle()));
            }
        });
        scenario("silent_node_returns_nan_within_the_deadline", new Scenario() {
            public void run(String n) {
                // Answers the opening GET, then nothing until write 4, which answers again.
                FakePort port = new FakePort(new Chip() {
                    public byte[] answer(byte[] frame, int writeNo) {
                        return writeNo == 1 ? onReply(90) : writeNo == 4 ? onReply(91) : null;
                    }

                    public long delayMs(int writeNo) {
                        return 0;
                    }
                });
                VoiceDirection d = open(uncalibrated(), new FakeNative(), new FakeNodes(port, PORT), new Lines());
                long t0 = System.nanoTime();
                float a = d.degrees();
                long ms = (System.nanoTime() - t0) / 1000000L;
                long t1 = System.nanoTime();
                float b = d.degrees();
                long ms2 = (System.nanoTime() - t1) / 1000000L;
                d.degrees();
                int raw = d.lastRaw();
                check(n, Float.isNaN(a) && Float.isNaN(b) && ms >= DEADLINE_MS - 5 && ms < DEADLINE_MS + 40
                                && ms2 < DEADLINE_MS + 40 && raw == 91 && d.backend() == VoiceDirection.Backend.NC,
                        "a=" + a + " took " + ms + " ms, then " + ms2 + " ms, raw=" + raw);
            }
        });
        scenario("stale_bytes_are_discarded_before_the_next_get", new Scenario() {
            public void run(String n) throws Exception {
                // Write 2's reply lands after the deadline (a late 50); write 3 answers 138 at once.
                FakePort port = new FakePort(new Chip() {
                    public byte[] answer(byte[] frame, int writeNo) {
                        return onReply(writeNo == 2 ? 50 : 138);
                    }

                    public long delayMs(int writeNo) {
                        return writeNo == 2 ? DEADLINE_MS + 40 : 0;
                    }
                });
                VoiceDirection d = open(uncalibrated(), new FakeNative(), new FakeNodes(port, PORT), new Lines());
                d.degrees(); // write 2: misses its deadline
                Thread.sleep(DEADLINE_MS + 80); // its 38 bytes are now buffered
                boolean stale = port.available() == 38;
                port.preload(new byte[]{1, 2, 3}); // and some line noise behind them
                d.degrees(); // write 3
                check(n, stale && d.lastRaw() == 138 && port.available() == 0,
                        "stale=" + stale + " raw=" + d.lastRaw() + " left=" + port.available());
            }
        });
        scenario("three_misses_close_the_backend", new Scenario() {
            public void run(String n) {
                FakePort port = new FakePort(new Chip() {
                    public byte[] answer(byte[] frame, int writeNo) {
                        return writeNo == 1 ? onReply(90) : null;
                    }

                    public long delayMs(int writeNo) {
                        return 0;
                    }
                });
                Lines log = new Lines();
                VoiceDirection d = open(uncalibrated(), new FakeNative(), new FakeNodes(port, PORT), log);
                for (int i = 0; i < 3; i++) {
                    d.degrees();
                }
                int writes = port.writes.size();
                long t0 = System.nanoTime();
                float a = d.degrees();
                d.degrees();
                long ms = (System.nanoTime() - t0) / 1000000L;
                check(n, writes == 4 && port.writes.size() == 4 && Float.isNaN(a) && ms < 10 && port.closed
                                && log.count("closed") == 1 && d.backend() == VoiceDirection.Backend.NONE,
                        "writes=" + port.writes.size() + " ms=" + ms + " closed=" + port.closed + " log=" + log.lines);
            }
        });
        scenario("a_reply_resets_the_miss_count", new Scenario() {
            public void run(String n) {
                FakePort port = new FakePort(new Chip() {
                    public byte[] answer(byte[] frame, int writeNo) {
                        return writeNo == 1 || writeNo == 4 ? onReply(90) : null;
                    }

                    public long delayMs(int writeNo) {
                        return 0;
                    }
                });
                VoiceDirection d = open(uncalibrated(), new FakeNative(), new FakeNodes(port, PORT), new Lines());
                for (int i = 0; i < 4; i++) {
                    d.degrees(); // miss, miss, answer, miss
                }
                check(n, !port.closed && d.backend() == VoiceDirection.Backend.NC, "closed after non-consecutive misses");
            }
        });
        scenario("silent_node_at_open_is_none", new Scenario() {
            public void run(String n) {
                FakePort port = new FakePort(new Chip() {
                    public byte[] answer(byte[] frame, int writeNo) {
                        return null;
                    }

                    public long delayMs(int writeNo) {
                        return 0;
                    }
                });
                VoiceDirection d = open(uncalibrated(), new FakeNative(), new FakeNodes(port, PORT), new Lines());
                check(n, d.backend() == VoiceDirection.Backend.NONE && port.closed && d.detail().contains("no reply"),
                        "backend=" + d.backend() + " detail=" + d.detail());
            }
        });
        scenario("first_raw_replies_are_logged_in_hex", new Scenario() {
            public void run(String n) {
                FakePort port = new FakePort(steady(138));
                Lines log = new Lines();
                VoiceDirection d = open(uncalibrated(), new FakeNative(), new FakeNodes(port, PORT), log);
                for (int i = 0; i < 10; i++) {
                    d.degrees();
                }
                byte[] r = onReply(138);
                String h = NcFrames.hex(r, r.length);
                String first = d.firstReplyHex();
                check(n, h.startsWith("58585542") && h.equals(first) && log.count(h) >= 1
                                && log.count(h) <= VoiceDirection.LOGGED_REPLIES,
                        "first=" + first + " logged=" + log.count(h));
            }
        });
        scenario("lazy_sampler_opens_on_its_own_thread_and_never_blocks_the_caller", new Scenario() {
            // Review P1 (2026-09-29): the first open ran on the ears capture thread holding
            // feedLock, so a tty that blocked on open or write would stall the ears.
            public void run(String n) throws Exception {
                final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
                final String[] openedOn = new String[1];
                final VoiceDirection ready = open(uncalibrated(), new FakeNative(),
                        new FakeNodes(new FakePort(steady(138)), PORT), new Lines());
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
    }
}
