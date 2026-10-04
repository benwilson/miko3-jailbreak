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
 * does on the robot (2026-09-29). After a cold boot the chip's reporting is off
 * (robot, 2026-09-30): FakeChip answers the vendor's status query and starts
 * streaming only once the reporting toggle is written. Prints "PASS name" or
 * "FAIL name: detail" per scenario (scripts/tests/test_nc_frames.py).
 */
public final class NcFramesHarness {
    static final String PORT = "/dev/ttyS1";
    /** Frames captured on the robot (2026-09-29), CRCs valid. */
    static final String RAW_85 = "58585542a30344010500de9c7afe55f64a03c9";
    static final String RAW_85_NEXT = "58585542a303470105003033cfec55f64a03c9";
    static final String RAW_50 = "58585542a3038f010500d9f5365f320dbed51a";
    /** The other frame type, seen once right after vendor setup: module 03, op 02. It is the
     * status frame with reporting ON (payload byte 0 = 01), as the chip answered on 2026-09-29. */
    static final String OTHER = "58585542030200010500ea6b70ce011bdf05a5";
    static final String STATUS_ON = OTHER;
    /** The status frame after a cold boot (robot, 2026-09-30): payload byte 0 = 00, reporting OFF. */
    static final String STATUS_OFF = "58585542030200010500ea6b70ce008def02d2";
    /** The vendor's status query and reporting toggle, as sent on the robot (2026-09-30). */
    static final String QUERY = "58585542030103000000a620d0e7";
    static final String TOGGLE = "58585542030302000000a314ac25";

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
     * Every write is recorded (open may write only the status query and the toggle). */
    static class FakePort implements VoiceDirection.Port {
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

    /** The chip after a cold boot: answers the status query with `status` (or not at all when
     * null) after 30 ms, and once the toggle is written streams direction frames from sequence
     * 0, one a second from 300 ms after it (when streamsOnToggle). */
    static final class FakeChip extends FakePort {
        final String status;
        final boolean streamsOnToggle;

        FakeChip(String status, boolean streamsOnToggle) {
            this.status = status;
            this.streamsOnToggle = streamsOnToggle;
        }

        @Override
        public synchronized void write(byte[] frame) throws IOException {
            super.write(frame);
            long now = (System.nanoTime() - t0) / 1000000L;
            if (java.util.Arrays.equals(frame, hex(QUERY)) && status != null) {
                feedAt(now + 30, hex(status));
            } else if (java.util.Arrays.equals(frame, hex(TOGGLE)) && streamsOnToggle) {
                for (int i = 0; i < 10; i++) {
                    feedAt(now + 300 + 1000L * i, direction(i, 55 + (i % 8) * 5));
                }
            }
        }

        /** The writes in hex, oldest first. */
        synchronized List<String> written() {
            List<String> out = new ArrayList<String>();
            for (byte[] w : writes) {
                out.add(NcFrames.hex(w, w.length));
            }
            return out;
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


    // ---- the NC DSP's settings (vendor libconexant_dsp_lib.so, 2026-10-02) ----

    /** A read reply as the status frame is (robot): op 02, value at byte 14, its CRC32 LE after. */
    static byte[] reply(int module, int value) {
        byte[] f = frame(module, 2, 0, 1, 5, 0, value);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(f, 14, 1);
        long c = crc.getValue();
        for (int i = 0; i < 4; i++) {
            f[15 + i] = (byte) (c >>> (8 * i));
        }
        return f;
    }

    /** The DSP: answers each read 15 ms later from its state (unless the module is mute), takes
     * a set's payload as the new value and flips a 0/1 switch on a toggle; streams a direction
     * frame every 50 ms from 0 so open() succeeds at once and frames interleave with replies. */
    static final class FakeDsp extends FakePort {
        final java.util.Map<Integer, Integer> state = new java.util.HashMap<Integer, Integer>();
        final Set<Integer> mute = new HashSet<Integer>();

        FakeDsp(int gain, int dgain, int aec, int laec, int ns, int lns, int ch, int voip) {
            state.put(0x04, gain);
            state.put(0x12, dgain);
            state.put(0x06, aec);
            state.put(0x16, laec);
            state.put(0x08, ns);
            state.put(0x18, lns);
            state.put(0x11, ch);
            state.put(0x05, voip);
            for (int i = 0; i < 200; i++) {
                feedAt(50L * i, direction(i & 0xff, 55 + (i % 8) * 5));
            }
        }

        static FakeDsp vendor() {
            return new FakeDsp(30, 0, 1, 1, 1, 1, 0, 0);
        }

        @Override
        public synchronized void write(byte[] f) throws IOException {
            super.write(f);
            long now = (System.nanoTime() - t0) / 1000000L;
            int module = f[4] & 0xff;
            int op = f[5] & 0xff;
            if (op == 1 && state.containsKey(module) && !mute.contains(module)) {
                insertAt(now + 15, reply(module, state.get(module)));
            } else if (op == 3 && f.length == 19) {
                state.put(module, f[14] & 0xff);
            } else if (op == 3) {
                int v = state.get(module);
                state.put(module, v == 0 ? 1 : v == 1 ? 0 : v);
            }
        }

        /** Bytes readable at atMs, slotted in between the direction frames already queued. */
        synchronized void insertAt(long atMs, byte[] bytes) {
            long at = t0 + atMs * 1000000L;
            int i = 0;
            while (i < due.size() && due.get(i) <= at) {
                i++;
            }
            // Never split a queued frame: move to the next frame boundary.
            while (i < due.size() && i > 0 && due.get(i).equals(due.get(i - 1))) {
                i++;
            }
            for (int k = 0; k < bytes.length; k++) {
                pending.add(i + k, bytes[k]);
                due.add(i + k, at);
            }
        }

        synchronized List<String> written() {
            List<String> out = new ArrayList<String>();
            for (byte[] w : writes) {
                out.add(NcFrames.hex(w, w.length));
            }
            return out;
        }
    }

    static List<String> readsHex() {
        List<String> out = new ArrayList<String>();
        for (NcFrames.Setting s : NcFrames.Setting.values()) {
            byte[] f = s.read();
            out.add(NcFrames.hex(f, f.length));
        }
        return out;
    }

    static String hexOf(byte[] f) {
        return NcFrames.hex(f, f.length);
    }

    static java.util.Map<NcFrames.Setting, Integer> values(Object... kv) {
        java.util.Map<NcFrames.Setting, Integer> m =
                new java.util.EnumMap<NcFrames.Setting, Integer>(NcFrames.Setting.class);
        for (int i = 0; i < kv.length; i += 2) {
            m.put((NcFrames.Setting) kv[i], (Integer) kv[i + 1]);
        }
        return m;
    }

    static java.util.Map<NcFrames.Setting, Integer> vendorValues() {
        java.util.Map<NcFrames.Setting, Integer> m =
                new java.util.EnumMap<NcFrames.Setting, Integer>(NcFrames.Setting.class);
        for (NcFrames.Setting s : NcFrames.Setting.values()) {
            m.put(s, NcFrames.vendorTarget(s));
        }
        return m;
    }

    static final long PACE = 5;
    static final long REPLY = 150;

    /** Every request the NC frames can build, for the Python test to check byte for byte. */
    static void dumpFrames() {
        for (NcFrames.Setting s : NcFrames.Setting.values()) {
            System.out.println("FRAME " + s.label + ".read " + hexOf(s.read()));
            if (s.toggleable()) {
                System.out.println("FRAME " + s.label + ".toggle " + hexOf(s.toggle()));
            }
        }
        System.out.println("FRAME gain.set30 " + hexOf(NcFrames.Setting.GAIN.set(30)));
        System.out.println("FRAME gain.set0 " + hexOf(NcFrames.Setting.GAIN.set(0)));
        System.out.println("FRAME dgain.set0 " + hexOf(NcFrames.Setting.DGAIN.set(0)));
        System.out.println("FRAME dgain.set30 " + hexOf(NcFrames.Setting.DGAIN.set(30)));
    }

    static void ncScenarios() {
        scenario("nc_replies_parse_between_direction_frames", new Scenario() {
            public void run(String n) {
                final List<String> seen = new ArrayList<String>();
                NcFrames.Sink sink = new NcFrames.Sink() {
                    public void direction(byte[] b, int at, int raw) {
                        seen.add("dir " + raw);
                    }

                    public void status(boolean on) {
                        seen.add("status " + on);
                    }

                    public void reply(NcFrames.Setting s, int v) {
                        seen.add(s.label + " " + v);
                    }
                };
                byte[] badPayload = reply(0x08, 1);
                badPayload[16] ^= 1;
                byte[] badHeader = reply(0x05, 0);
                badHeader[11] ^= 1;
                byte[] op01 = reply(0x11, 0);
                op01[5] = 1;
                byte[] fixed = frame(0x11, 1, 0, 1, 5, 0, 0);
                System.arraycopy(op01, 14, fixed, 14, 5);
                byte[] set = frame(0x04, 3, 1, 1, 5, 0, 30); // an op 03 frame is no reply
                byte[] factory = frame(0x09, 2, 0, 1, 5, 0, 1);
                byte[] buf = concat(hex(RAW_85), reply(0x04, 30), hex("0011"), hex(STATUS_ON), reply(0x12, 0),
                        badPayload, direction(3, 70), badHeader, reply(0x06, 1), fixed, set, factory,
                        reply(0x16, 1), hex(RAW_50), reply(0x18, 0), reply(0x05, 0));
                int used = NcFrames.parseStream(buf, buf.length, sink);
                check(n, seen.equals(java.util.Arrays.asList("dir 85", "gain 30", "status true", "dgain 0", "dir 70",
                                "aec 1", "ch 0", "laec 1", "dir 50", "lns 0", "voip 0")) && used == buf.length,
                        "seen=" + seen + " used=" + used + "/" + buf.length);
            }
        });
        scenario("nc_request_frames_are_built_with_their_crcs", new Scenario() {
            public void run(String n) {
                boolean ok = hexOf(NcFrames.Setting.GAIN.read()).equals("5858554204010c0000004800b6a2")
                        && hexOf(NcFrames.Setting.GAIN.set(30)).equals("585855420403010105008715a9561eeed20d28");
                boolean threw = false;
                try {
                    NcFrames.Setting.LAEC.toggle();
                } catch (IllegalStateException e) {
                    threw = true;
                }
                boolean gainNoToggle = !NcFrames.Setting.GAIN.toggleable() && !NcFrames.Setting.DGAIN.toggleable();
                boolean noSet = false;
                try {
                    NcFrames.Setting.AEC.set(1);
                } catch (IllegalStateException e) {
                    noSet = true;
                }
                check(n, ok && threw && gainNoToggle && noSet && NcFrames.Setting.of(0x09) == null,
                        "gain read " + hexOf(NcFrames.Setting.GAIN.read()) + " set30 "
                                + hexOf(NcFrames.Setting.GAIN.set(30)) + " laecThrew=" + threw + " noSet=" + noSet);
            }
        });
        scenario("nc_plan_sends_only_what_differs", new Scenario() {
            public void run(String n) {
                List<byte[]> none = NcFrames.plan(vendorValues(), 30, 0);
                java.util.Map<NcFrames.Setting, Integer> m = vendorValues();
                m.put(NcFrames.Setting.GAIN, 20);
                m.put(NcFrames.Setting.AEC, 0);
                m.put(NcFrames.Setting.VOIP, 1);
                List<String> got = new ArrayList<String>();
                for (byte[] f : NcFrames.plan(m, 30, 0)) {
                    got.add(hexOf(f));
                }
                List<String> want = java.util.Arrays.asList(hexOf(NcFrames.Setting.GAIN.set(30)),
                        hexOf(NcFrames.Setting.AEC.toggle()), hexOf(NcFrames.Setting.VOIP.toggle()));
                // Custom targets from the properties: gain 40, digital gain 6.
                List<byte[]> custom = NcFrames.plan(vendorValues(), 40, 6);
                check(n, none.isEmpty() && got.equals(want) && custom.size() == 2
                                && hexOf(custom.get(0)).equals(hexOf(NcFrames.Setting.GAIN.set(40)))
                                && hexOf(custom.get(1)).equals(hexOf(NcFrames.Setting.DGAIN.set(6))),
                        "none=" + none.size() + " got=" + got + " custom=" + custom.size());
            }
        });
        scenario("nc_plan_never_toggles_unknown_values", new Scenario() {
            public void run(String n) {
                // Unread: nothing. Read as 2 (not a 0/1 switch): nothing. Unread gains: no set.
                List<byte[]> empty = NcFrames.plan(values(), 30, 0);
                List<byte[]> odd = NcFrames.plan(values(NcFrames.Setting.AEC, 2, NcFrames.Setting.NS, 7,
                        NcFrames.Setting.CH, 255, NcFrames.Setting.VOIP, 3), 30, 0);
                check(n, empty.isEmpty() && odd.isEmpty(), "empty=" + empty.size() + " odd=" + odd.size());
            }
        });
        scenario("nc_plan_never_sends_factory_or_left_aec", new Scenario() {
            public void run(String n) {
                int[] choices = {-1, 0, 1, 2, 30};
                NcFrames.Setting[] all = NcFrames.Setting.values();
                Set<String> allowed = new HashSet<String>();
                for (NcFrames.Setting s : all) {
                    if (s.toggleable()) {
                        allowed.add(hexOf(s.toggle()));
                    }
                }
                for (int g = 0; g <= 60; g++) {
                    allowed.add(hexOf(NcFrames.Setting.GAIN.set(g)));
                }
                for (int g = 0; g <= 30; g++) {
                    allowed.add(hexOf(NcFrames.Setting.DGAIN.set(g)));
                }
                String bad = null;
                int combos = 1;
                for (int i = 0; i < all.length; i++) {
                    combos *= choices.length;
                }
                for (int c = 0; c < combos && bad == null; c++) {
                    java.util.Map<NcFrames.Setting, Integer> m = values();
                    int x = c;
                    for (NcFrames.Setting s : all) {
                        int v = choices[x % choices.length];
                        x /= choices.length;
                        if (v >= 0) {
                            m.put(s, v);
                        }
                    }
                    for (byte[] f : NcFrames.plan(m, c % 61, c % 31)) {
                        int module = f[4] & 0xff;
                        if (module == 0x09 || module == 0x16 || !allowed.contains(hexOf(f))) {
                            bad = hexOf(f) + " for " + m;
                        }
                    }
                }
                // The source has no factory frame or left-AEC toggle bytes at all.
                check(n, bad == null, "sent " + bad);
            }
        });
        scenario("nc_control_parses_properties", new Scenario() {
            public void run(String n) {
                NcFrames.Control dflt = NcFrames.Control.of("", "", "", "");
                NcFrames.Control nulls = NcFrames.Control.of(null, null, null, null);
                NcFrames.Control on = NcFrames.Control.of("0", "1", "45", "12");
                NcFrames.Control clamp = NcFrames.Control.of("1", "true", "99", "-4");
                NcFrames.Control junk = NcFrames.Control.of("false", "yes", "x", "3.5");
                NcFrames.Control off = NcFrames.Control.of("0", "0", "", "");
                check(n, dflt.status && !dflt.apply && dflt.gain == 30 && dflt.dgain == 0 && dflt.any()
                                && nulls.status && !nulls.apply && nulls.gain == 30
                                && !on.status && on.apply && on.gain == 45 && on.dgain == 12 && on.any()
                                && clamp.status && clamp.apply && clamp.gain == 60 && clamp.dgain == 0
                                && !junk.status && !junk.apply && junk.gain == 30 && junk.dgain == 0
                                && !off.any(),
                        "dflt=" + dflt + " on=" + on + " clamp=" + clamp + " junk=" + junk + " off=" + off);
            }
        });
        scenario("nc_status_logs_every_setting_and_writes_only_reads", new Scenario() {
            public void run(String n) throws Exception {
                FakeDsp dsp = FakeDsp.vendor();
                Lines log = new Lines();
                VoiceDirection d = open(uncalibrated(), new FakeNative(), new FakeNodes(dsp, PORT), log);
                int before = dsp.writes.size();
                java.util.Map<NcFrames.Setting, Integer> got =
                        d.ncCheck(NcFrames.Control.of("1", "", "", ""), PACE, REPLY);
                List<String> w = dsp.written();
                Thread.sleep(120);
                int raw = d.lastRaw();
                check(n, d.backend() == VoiceDirection.Backend.NC && before == 0
                                && w.equals(readsHex()) && got.equals(vendorValues())
                                && log.count("nc: gain=30 dgain=0 aec=1 laec=1 ns=1 lns=1 ch=0 voip=0") == 1
                                && log.count("nc: after") == 0 && log.count("nc: apply") == 0
                                && raw >= 55 && d.degrees() != d.degrees(),
                        "writes=" + w + " got=" + got + " raw=" + raw + " log=" + log.lines);
            }
        });
        scenario("nc_unanswered_settings_log_question_marks_and_are_not_applied", new Scenario() {
            public void run(String n) throws Exception {
                FakeDsp dsp = new FakeDsp(30, 0, 0, 1, 0, 1, 0, 0);
                dsp.mute.add(0x08); // NS never answers (and is off): no toggle
                dsp.mute.add(0x04); // gain never answers: no set
                Lines log = new Lines();
                VoiceDirection d = open(uncalibrated(), new FakeNative(), new FakeNodes(dsp, PORT), log);
                d.ncCheck(NcFrames.Control.of("", "1", "", ""), PACE, REPLY);
                List<String> want = new ArrayList<String>(readsHex());
                want.add(hexOf(NcFrames.Setting.AEC.toggle()));
                want.addAll(readsHex());
                check(n, dsp.written().equals(want)
                                && log.count("nc: gain=? dgain=0 aec=0 laec=1 ns=? lns=1 ch=0 voip=0") == 1
                                && log.count("nc: apply aec toggle") == 1
                                && log.count("nc: after gain=? dgain=0 aec=1 laec=1 ns=? lns=1 ch=0 voip=0") == 1,
                        "writes=" + dsp.written() + " log=" + log.lines);
            }
        });
        scenario("nc_apply_sets_and_toggles_only_differences_then_rereads", new Scenario() {
            public void run(String n) throws Exception {
                FakeDsp dsp = new FakeDsp(20, 5, 0, 0, 1, 0, 1, 1);
                Lines log = new Lines();
                VoiceDirection d = open(uncalibrated(), new FakeNative(), new FakeNodes(dsp, PORT), log);
                java.util.Map<NcFrames.Setting, Integer> after =
                        d.ncCheck(NcFrames.Control.of("", "1", "", ""), PACE, REPLY);
                List<String> want = new ArrayList<String>(readsHex());
                want.addAll(java.util.Arrays.asList(hexOf(NcFrames.Setting.GAIN.set(30)),
                        hexOf(NcFrames.Setting.DGAIN.set(0)), hexOf(NcFrames.Setting.AEC.toggle()),
                        hexOf(NcFrames.Setting.LNS.toggle()), hexOf(NcFrames.Setting.CH.toggle()),
                        hexOf(NcFrames.Setting.VOIP.toggle())));
                want.addAll(readsHex());
                java.util.Map<NcFrames.Setting, Integer> expect = vendorValues();
                expect.put(NcFrames.Setting.LAEC, 0); // left AEC is never toggled
                check(n, dsp.written().equals(want) && after.equals(expect)
                                && log.count("nc: gain=20 dgain=5 aec=0 laec=0 ns=1 lns=0 ch=1 voip=1") == 1
                                && log.count("nc: apply gain 20->30, dgain 5->0, aec toggle, lns toggle, ch toggle, "
                                + "voip toggle") == 1
                                && log.count("nc: after gain=30 dgain=0 aec=1 laec=0 ns=1 lns=1 ch=0 voip=0") == 1
                                && d.backend() == VoiceDirection.Backend.NC,
                        "writes=" + dsp.written() + " after=" + after + " log=" + log.lines);
            }
        });
        scenario("nc_apply_with_custom_gains_and_nothing_else_to_change", new Scenario() {
            public void run(String n) throws Exception {
                FakeDsp dsp = FakeDsp.vendor();
                Lines log = new Lines();
                VoiceDirection d = open(uncalibrated(), new FakeNative(), new FakeNodes(dsp, PORT), log);
                d.ncCheck(NcFrames.Control.of("", "1", "", ""), PACE, REPLY);
                int first = dsp.writes.size();
                d.ncCheck(NcFrames.Control.of("", "1", "42", "3"), PACE, REPLY);
                check(n, first == 8 && log.count("nc: apply: nothing to change") == 1
                                && log.count("nc: apply gain 30->42, dgain 0->3") == 1
                                && log.count("nc: after gain=42 dgain=3 aec=1") == 1 && dsp.writes.size() == 8 + 8 + 2 + 8,
                        "first=" + first + " writes=" + dsp.writes.size() + " log=" + log.lines);
            }
        });
    }

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("frames")) {
            dumpFrames();
            return;
        }
        ncScenarios();
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
                // Junk and a status frame before any query only: no direction frame ever comes,
                // so open asks the chip once (2026-09-30), and nothing answers the query.
                FakePort port = new FakePort().feedAt(0, hex("0102")).feedAt(300, hex(OTHER));
                Lines log = new Lines();
                long t0 = System.nanoTime();
                VoiceDirection d = open(uncalibrated(), new FakeNative(), new FakeNodes(port, PORT), log);
                long ms = (System.nanoTime() - t0) / 1000000L;
                long want = VoiceDirection.FIRST_FRAME_MS + VoiceDirection.STATUS_REPLY_MS;
                check(n, d.backend() == VoiceDirection.Backend.NONE && port.closed
                                && ("nc: no answer on " + PORT).equals(d.detail()) && d.firstReplyHex() == null
                                && port.writes.size() == 1 && java.util.Arrays.equals(port.writes.get(0), hex(QUERY))
                                && ms >= want - 20 && ms < want + 500 && Float.isNaN(d.degrees()),
                        "backend=" + d.backend() + " detail=" + d.detail() + " ms=" + ms + " writes="
                                + port.writes.size());
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
        scenario("after_an_nc_open_twenty_samples_add_no_writes", new Scenario() {
            // Only open may write, and only when no frame came (2026-09-30); sampling only reads.
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

        // Robot finding (2026-09-30): after a cold boot the chip's reporting is OFF. open asks
        // once with the vendor's status query and, only when the answer is OFF, toggles once.
        scenario("the_robot_status_and_direction_frames_parse", new Scenario() {
            public void run(String n) {
                final List<String> seen = new ArrayList<String>();
                NcFrames.Sink sink = new NcFrames.Sink() {
                    public void direction(byte[] b, int at, int raw) {
                        seen.add("dir " + raw);
                    }

                    public void status(boolean on) {
                        seen.add("status " + on);
                    }
                };
                byte[] badOn = hex(STATUS_ON);
                badOn[11] ^= 1;
                byte[] buf = concat(hex("00"), hex(STATUS_OFF), hex(RAW_85), badOn, hex(STATUS_ON));
                int used = NcFrames.parseStream(buf, buf.length, sink);
                // A Sink that only takes directions still compiles and ignores status frames.
                List<Integer> plain = raws(concat(hex(STATUS_OFF), hex(RAW_50)), null);
                check(n, seen.equals(java.util.Arrays.asList("status false", "dir 85", "status true"))
                                && used == buf.length && plain.equals(java.util.Arrays.asList(50)),
                        "seen=" + seen + " used=" + used + "/" + buf.length + " plain=" + plain);
            }
        });
        scenario("the_request_frames_are_the_vendors_and_never_shared", new Scenario() {
            public void run(String n) {
                byte[] q = NcFrames.statusQuery();
                byte[] t = NcFrames.reportingToggle();
                boolean same = QUERY.equals(NcFrames.hex(q, q.length)) && TOGGLE.equals(NcFrames.hex(t, t.length));
                q[0] = 0;
                t[0] = 0;
                byte[] q2 = NcFrames.statusQuery();
                byte[] t2 = NcFrames.reportingToggle();
                check(n, same && QUERY.equals(NcFrames.hex(q2, q2.length)) && TOGGLE.equals(NcFrames.hex(t2, t2.length))
                                && q.length == 14 && t.length == 14,
                        "query=" + NcFrames.hex(q2, q2.length) + " toggle=" + NcFrames.hex(t2, t2.length));
            }
        });
        scenario("reporting_already_on_opens_nc_with_no_writes", new Scenario() {
            public void run(String n) {
                FakeChip chip = new FakeChip(STATUS_ON, true);
                chip.feedAt(0, direction(0, 80));
                Lines log = new Lines();
                VoiceDirection d = open(uncalibrated(), new FakeNative(), new FakeNodes(chip, PORT), log);
                check(n, d.backend() == VoiceDirection.Backend.NC && d.lastRaw() == 80 && chip.writes.isEmpty()
                                && log.count("switched on") == 0,
                        "backend=" + d.backend() + " writes=" + chip.written() + " log=" + log.lines);
            }
        });
        scenario("off_after_boot_queries_then_toggles_once_and_opens_nc", new Scenario() {
            public void run(String n) {
                FakeChip chip = new FakeChip(STATUS_OFF, true);
                Lines log = new Lines();
                long t0 = System.nanoTime();
                VoiceDirection d = open(uncalibrated(), new FakeNative(), new FakeNodes(chip, PORT), log);
                long ms = (System.nanoTime() - t0) / 1000000L;
                check(n, d.backend() == VoiceDirection.Backend.NC && d.lastRaw() == 55 && !chip.closed
                                && chip.written().equals(java.util.Arrays.asList(QUERY, TOGGLE))
                                && log.count("voice direction: nc reporting was off, switched on") == 1
                                && d.firstReplyHex() != null && d.firstReplyHex().startsWith("58585542a30300")
                                && ms < VoiceDirection.FIRST_FRAME_MS + 1000,
                        "backend=" + d.backend() + " writes=" + chip.written() + " ms=" + ms + " log=" + log.lines);
            }
        });
        scenario("status_on_but_silent_is_none_without_a_toggle", new Scenario() {
            public void run(String n) {
                FakeChip chip = new FakeChip(STATUS_ON, true);
                Lines log = new Lines();
                long t0 = System.nanoTime();
                VoiceDirection d = open(uncalibrated(), new FakeNative(), new FakeNodes(chip, PORT), log);
                long ms = (System.nanoTime() - t0) / 1000000L;
                long min = 2 * VoiceDirection.FIRST_FRAME_MS;
                check(n, d.backend() == VoiceDirection.Backend.NONE && chip.closed
                                && ("nc: reporting on but no frames on " + PORT).equals(d.detail())
                                && chip.written().equals(java.util.Arrays.asList(QUERY))
                                && log.count("switched on") == 0 && ms >= min - 20
                                && ms < min + VoiceDirection.STATUS_REPLY_MS + 500,
                        "backend=" + d.backend() + " detail=" + d.detail() + " writes=" + chip.written() + " ms=" + ms);
            }
        });
        scenario("no_status_reply_is_none_after_one_write", new Scenario() {
            public void run(String n) {
                FakeChip chip = new FakeChip(null, true);
                VoiceDirection d = open(uncalibrated(), new FakeNative(), new FakeNodes(chip, PORT), new Lines());
                check(n, d.backend() == VoiceDirection.Backend.NONE && chip.closed
                                && ("nc: no answer on " + PORT).equals(d.detail())
                                && chip.written().equals(java.util.Arrays.asList(QUERY)),
                        "backend=" + d.backend() + " detail=" + d.detail() + " writes=" + chip.written());
            }
        });
        scenario("a_toggle_with_no_frames_after_is_none_after_one_toggle", new Scenario() {
            public void run(String n) {
                FakeChip chip = new FakeChip(STATUS_OFF, false);
                Lines log = new Lines();
                long t0 = System.nanoTime();
                VoiceDirection d = open(uncalibrated(), new FakeNative(), new FakeNodes(chip, PORT), log);
                long ms = (System.nanoTime() - t0) / 1000000L;
                long min = VoiceDirection.FIRST_FRAME_MS + VoiceDirection.WAKE_FRAME_MS;
                check(n, d.backend() == VoiceDirection.Backend.NONE && chip.closed
                                && ("nc: no frames after switching reporting on, on " + PORT).equals(d.detail())
                                && chip.written().equals(java.util.Arrays.asList(QUERY, TOGGLE))
                                && log.count("voice direction: nc reporting was off, switched on") == 1
                                && ms >= min - 20 && ms < min + VoiceDirection.STATUS_REPLY_MS + 500,
                        "backend=" + d.backend() + " detail=" + d.detail() + " writes=" + chip.written() + " ms=" + ms);
            }
        });
        scenario("after_an_off_boot_open_twenty_samples_add_no_writes", new Scenario() {
            public void run(String n) throws Exception {
                FakeChip chip = new FakeChip(STATUS_OFF, true);
                VoiceDirection d = open(uncalibrated(), new FakeNative(), new FakeNodes(chip, PORT), new Lines());
                int after = chip.writes.size();
                for (int i = 0; i < 20; i++) {
                    d.degrees();
                    d.lastRaw();
                    Thread.sleep(20);
                }
                check(n, d.backend() == VoiceDirection.Backend.NC && after == 2 && chip.writes.size() == 2,
                        "backend=" + d.backend() + " after open=" + after + " now=" + chip.writes.size());
            }
        });
    }
}
