package com.miko3.mode.voice;

import java.util.ArrayList;
import java.util.List;

/**
 * Host-JVM checks for UplinkGate (U11), driven by
 * scripts/tests/test_voice_uplink_gate.py. Lives in the mode's own package so
 * it can reach the package-private class; UplinkGate and VoiceSettings touch
 * no android.*, so the decision that used to be locked inside VoiceEngine runs
 * under plain javac here.
 *
 * The streams are counting ramps: sample n carries the value n (mod 65536), so
 * a forwarded frame says exactly which microphone samples it holds, and "the
 * wake word was trimmed" is an assertion about sample numbers rather than
 * about byte counts.
 *
 * Prints one "PASS <name>" or "FAIL <name>: <detail>" line per scenario; the
 * Python side asserts on each by name.
 */
public final class UplinkGateHarness {
    private static final int RATE = 16000;
    private static final int CHUNK_MS = 80;
    private static final int PRE_READY_MS = 3000;
    /** Samples in one millisecond of 16 kHz mono. */
    private static final int PER_MS = RATE / 1000;

    /** Collects what the gate decided to send. */
    static final class Recorder implements UplinkGate.Sink {
        final List<byte[]> frames = new ArrayList<byte[]>();

        @Override
        public void frame(byte[] frame) {
            frames.add(frame);
        }

        /** The microphone sample number the nth sent frame starts at. */
        int startOf(int n) {
            return sampleAt(frames.get(n), 0);
        }

        /** Every sample sent, in order, as its microphone sample number. */
        List<Integer> samples() {
            List<Integer> out = new ArrayList<Integer>();
            for (byte[] f : frames) {
                for (int i = 0; i < f.length / 2; i++) {
                    out.add(sampleAt(f, i));
                }
            }
            return out;
        }
    }

    /** 16-bit little-endian mono holding sample numbers from start. */
    private static byte[] ramp(int startSample, int samples) {
        byte[] b = new byte[samples * 2];
        for (int i = 0; i < samples; i++) {
            int v = (startSample + i) & 0xFFFF;
            b[i * 2] = (byte) (v & 0xFF);
            b[i * 2 + 1] = (byte) ((v >>> 8) & 0xFF);
        }
        return b;
    }

    private static int sampleAt(byte[] frame, int i) {
        return (frame[i * 2] & 0xFF) | ((frame[i * 2 + 1] & 0xFF) << 8);
    }

    /** Offers ms milliseconds of ramp starting at sample fromMs*PER_MS, 80 ms at a time. */
    private static void speak(UplinkGate gate, int fromMs, int ms) {
        for (int t = 0; t < ms; t += CHUNK_MS) {
            int slice = Math.min(CHUNK_MS, ms - t);
            byte[] chunk = ramp((fromMs + t) * PER_MS, slice * PER_MS);
            gate.offer(chunk, 0, chunk.length);
        }
    }

    /** True when the values run 1 up from the first, with no gap or reorder. */
    private static boolean contiguous(List<Integer> samples, int firstSample) {
        for (int i = 0; i < samples.size(); i++) {
            if (samples.get(i) != ((firstSample + i) & 0xFFFF)) {
                return false;
            }
        }
        return true;
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
        scenario("nothing_before_the_wake_is_forwarded", new Scenario() {
            public void run(String n) throws Exception {
                UplinkGate gate = new UplinkGate(RATE);
                boolean droppingFirst = gate.dropping();
                // A whole second of "Hey Miko" and the room before it.
                speak(gate, 0, 1000);
                boolean nothingKept = gate.bufferedFrames() == 0 && gate.forwardedMs() == 0;
                gate.wake(CHUNK_MS, PRE_READY_MS, 0);
                speak(gate, 1000, CHUNK_MS);
                Recorder rec = new Recorder();
                int sent = gate.ready(rec);
                check(n, droppingFirst && nothingKept && sent == 1 && rec.frames.size() == 1
                                && rec.startOf(0) == 1000 * PER_MS,
                        "dropping=" + droppingFirst + " nothingKept=" + nothingKept + " sent=" + sent
                                + " first=" + (rec.frames.isEmpty() ? -1 : rec.startOf(0)));
            }
        });
        scenario("trim_drops_exactly_the_configured_milliseconds", new Scenario() {
            public void run(String n) throws Exception {
                UplinkGate gate = new UplinkGate(RATE);
                gate.wake(CHUNK_MS, PRE_READY_MS, 200);
                speak(gate, 0, 1000);
                Recorder rec = new Recorder();
                int sent = gate.ready(rec);
                check(n, gate.droppedPreRollMs() == 200 && gate.forwardedMs() == 800
                                && gate.trimMs() == 200 && sent == 10
                                && rec.startOf(0) == 200 * PER_MS,
                        "dropped=" + gate.droppedPreRollMs() + " forwarded=" + gate.forwardedMs()
                                + " sent=" + sent + " first=" + (rec.frames.isEmpty() ? -1 : rec.startOf(0)));
            }
        });
        scenario("trim_can_end_inside_a_chunk", new Scenario() {
            public void run(String n) throws Exception {
                // 200 ms is two and a half 80 ms chunks: the third chunk is half kept.
                UplinkGate gate = new UplinkGate(RATE);
                gate.wake(CHUNK_MS, PRE_READY_MS, 200);
                speak(gate, 0, 240);
                Recorder rec = new Recorder();
                gate.ready(rec);
                List<Integer> kept = rec.samples();
                // 40 ms survived the trim: not a whole frame yet, so nothing is out.
                boolean nothingYet = kept.isEmpty() && gate.forwardedMs() == 40;
                speak(gate, 240, 40);
                check(n, nothingYet && rec.frames.size() == 1 && rec.startOf(0) == 200 * PER_MS
                                && contiguous(rec.samples(), 200 * PER_MS),
                        "nothingYet=" + nothingYet + " frames=" + rec.frames.size()
                                + " forwarded=" + gate.forwardedMs());
            }
        });
        scenario("speech_after_the_trim_is_forwarded_in_order", new Scenario() {
            public void run(String n) throws Exception {
                UplinkGate gate = new UplinkGate(RATE);
                gate.wake(CHUNK_MS, PRE_READY_MS, 200);
                speak(gate, 0, 1000);
                Recorder rec = new Recorder();
                gate.ready(rec);
                speak(gate, 1000, 400);
                List<Integer> kept = rec.samples();
                check(n, kept.size() == (1400 - 200) * PER_MS && contiguous(kept, 200 * PER_MS),
                        "samples=" + kept.size() + " expected=" + ((1400 - 200) * PER_MS)
                                + " first=" + (kept.isEmpty() ? -1 : kept.get(0)));
            }
        });
        scenario("zero_trim_forwards_everything_from_the_wake", new Scenario() {
            public void run(String n) throws Exception {
                UplinkGate gate = new UplinkGate(RATE);
                gate.wake(CHUNK_MS, PRE_READY_MS, 0);
                speak(gate, 0, 400);
                Recorder rec = new Recorder();
                int sent = gate.ready(rec);
                check(n, gate.droppedPreRollMs() == 0 && gate.forwardedMs() == 400 && sent == 5
                                && rec.startOf(0) == 0 && contiguous(rec.samples(), 0),
                        "dropped=" + gate.droppedPreRollMs() + " forwarded=" + gate.forwardedMs()
                                + " sent=" + sent);
            }
        });
        scenario("pre_ready_bound_drops_oldest", new Scenario() {
            public void run(String n) throws Exception {
                UplinkGate gate = new UplinkGate(RATE);
                gate.wake(CHUNK_MS, PRE_READY_MS, 0);
                // Five seconds with no conv.ready: the 3 s bound keeps the newest.
                speak(gate, 0, 5000);
                int held = gate.bufferedFrames();
                int lost = gate.oldestDropped();
                Recorder rec = new Recorder();
                int sent = gate.ready(rec);
                int firstKeptSample = lost * CHUNK_MS * PER_MS;
                check(n, held == PRE_READY_MS / CHUNK_MS && sent == held && lost == 62 - held
                                && gate.forwardedMs() == 5000
                                && rec.startOf(0) == (firstKeptSample & 0xFFFF)
                                && contiguous(rec.samples(), firstKeptSample),
                        "held=" + held + " lost=" + lost + " sent=" + sent
                                + " forwarded=" + gate.forwardedMs());
            }
        });
        scenario("trim_applies_before_the_bound", new Scenario() {
            public void run(String n) throws Exception {
                UplinkGate gate = new UplinkGate(RATE);
                gate.wake(CHUNK_MS, PRE_READY_MS, 200);
                speak(gate, 0, 3200);
                Recorder rec = new Recorder();
                gate.ready(rec);
                // 3,000 ms kept of 3,200 offered: exactly the bound, nothing dropped by it.
                check(n, gate.forwardedMs() == 3000 && gate.oldestDropped() == 0
                                && rec.frames.size() == PRE_READY_MS / CHUNK_MS
                                && rec.startOf(0) == 200 * PER_MS,
                        "forwarded=" + gate.forwardedMs() + " lost=" + gate.oldestDropped()
                                + " frames=" + rec.frames.size());
            }
        });
        scenario("live_audio_goes_straight_out_after_ready", new Scenario() {
            public void run(String n) throws Exception {
                UplinkGate gate = new UplinkGate(RATE);
                gate.wake(CHUNK_MS, PRE_READY_MS, 0);
                speak(gate, 0, 160);
                Recorder rec = new Recorder();
                int buffered = gate.ready(rec);
                speak(gate, 160, 160);
                check(n, buffered == 2 && rec.frames.size() == 4 && contiguous(rec.samples(), 0),
                        "buffered=" + buffered + " frames=" + rec.frames.size());
            }
        });
        scenario("drop_stops_and_clears_the_buffer", new Scenario() {
            public void run(String n) throws Exception {
                UplinkGate gate = new UplinkGate(RATE);
                gate.wake(CHUNK_MS, PRE_READY_MS, 0);
                speak(gate, 0, 400);
                gate.drop();
                speak(gate, 400, 400);
                boolean clear = gate.dropping() && gate.bufferedFrames() == 0;
                // The next wake starts from nothing, not from what the last one held.
                gate.wake(CHUNK_MS, PRE_READY_MS, 0);
                speak(gate, 800, 80);
                Recorder rec = new Recorder();
                int sent = gate.ready(rec);
                check(n, clear && sent == 1 && rec.startOf(0) == (800 * PER_MS & 0xFFFF)
                                && gate.forwardedMs() == 80,
                        "clear=" + clear + " sent=" + sent + " forwarded=" + gate.forwardedMs());
            }
        });
        scenario("summary_reports_pre_roll_and_forwarded", new Scenario() {
            public void run(String n) throws Exception {
                UplinkGate gate = new UplinkGate(RATE);
                gate.wake(CHUNK_MS, PRE_READY_MS, 200);
                speak(gate, 0, 1000);
                String s = gate.summary();
                check(n, s.contains("dropped 200 ms as wake-word pre-roll")
                                && s.contains(VoiceSettings.KEY_WAKE_TRIM_MS + "=200")
                                && s.contains("forwarded 800 ms"),
                        "summary=" + s);
            }
        });
        System.out.println(failures == 0 ? "ALL OK" : ("FAILURES " + failures));
        System.exit(failures == 0 ? 0 : 1);
    }
}
