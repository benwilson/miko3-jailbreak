package com.miko3.launcher;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Host-JVM checks for the launcher's listening (explore-on-claude plan U3;
 * R11, R12, KTD4, KTD8), driven by scripts/tests/test_listen_service.py.
 * Lives in the launcher's package to reach the package-private ListenSession,
 * SpeechQueue and CallerCheck; none touches android.*. A fake microphone
 * hands out 80 ms chunks and a fake recognizer decides when the speaker has
 * finished, so the endpoint, the cap, "no speech", the wait for the speech
 * queue and the one-listen-at-a-time rule run without sherpa-onnx or an
 * AudioRecord.
 *
 * Prints one "PASS <name>" or "FAIL <name>: <detail>" line per scenario.
 */
public final class ListenServiceHarness {
    static final int CHUNK = 1280; // 80 ms at 16 kHz

    /** Shared, ordered event log. */
    static final class Log {
        final List<String> events = Collections.synchronizedList(new ArrayList<String>());

        void add(String e) {
            events.add(e);
        }

        int indexOf(String e) {
            return events.indexOf(e);
        }

        @Override
        public String toString() {
            return events.toString();
        }
    }

    static final class FakeMic implements ListenSession.Mic {
        final Log log;
        int reads;
        int failAt = -1;

        FakeMic(Log log) {
            this.log = log;
        }

        @Override
        public int read(float[] buf) {
            if (reads == failAt) {
                return -1;
            }
            reads++;
            Arrays.fill(buf, 0f);
            return Math.min(buf.length, CHUNK);
        }

        @Override
        public void close() {
            log.add("mic-closed");
        }
    }

    /** Says text once it has heard textAfter samples; reports the endpoint
     * once it has heard endpointAfter samples (never, when negative). */
    static final class FakeRecognizer implements ListenSession.Recognizer {
        final Log log;
        final String text;
        final long textAfter;
        final long endpointAfter;
        long heard;
        boolean finished;

        FakeRecognizer(Log log, String text, long textAfter, long endpointAfter) {
            this.log = log;
            this.text = text;
            this.textAfter = textAfter;
            this.endpointAfter = endpointAfter;
        }

        @Override
        public void accept(float[] samples, int n) {
            heard += n;
        }

        @Override
        public boolean isEndpoint() {
            return endpointAfter >= 0 && heard >= endpointAfter;
        }

        @Override
        public String text() {
            return heard >= textAfter ? text : "";
        }

        @Override
        public void finish() {
            finished = true;
            log.add("recognizer-finished");
        }

        @Override
        public void close() {
            log.add("recognizer-closed");
        }
    }

    static final class FakeEars implements ListenSession.Ears {
        final Log log;
        boolean ready = true;
        FakeMic mic;
        FakeRecognizer recognizer;
        boolean openFails;

        FakeEars(Log log, FakeRecognizer recognizer) {
            this.log = log;
            this.recognizer = recognizer;
            this.mic = new FakeMic(log);
        }

        @Override
        public boolean ready() {
            return ready;
        }

        @Override
        public ListenSession.Mic openMic() throws IOException {
            log.add("mic-opened");
            if (openFails) {
                throw new IOException("no microphone");
            }
            return mic;
        }

        @Override
        public ListenSession.Recognizer newRecognizer() {
            return recognizer;
        }
    }

    /** Stands in for the speech queue: always idle. */
    static final ListenSession.Idle ALWAYS_IDLE = new ListenSession.Idle() {
        public boolean awaitIdle(long timeoutMs) {
            return true;
        }
    };

    /** A speech voice whose endLine waits for a latch: the line is "playing"
     * until the latch opens. */
    static final class HeldVoice implements SpeechQueue.Voice {
        final Log log;
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch started = new CountDownLatch(1);

        HeldVoice(Log log) {
            this.log = log;
        }

        public void startLine(long id, String text, long queuedAtNanos) {
            log.add("speech-start");
            started.countDown();
        }

        public void speak(String chunk) {
        }

        public void endLine(long id, boolean cancelled) {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            log.add("speech-end");
        }
    }

    static final class Line implements SpeechQueue.Listener {
        final Log log;

        Line(Log log) {
            this.log = log;
        }

        public void finished() {
            log.add("speech-finished");
        }

        public void cancelled() {
            log.add("speech-cancelled");
        }

        public void failed(String reason) {
            log.add("speech-failed");
        }
    }

    static ListenSession.Idle idleOf(final SpeechQueue q) {
        return new ListenSession.Idle() {
            public boolean awaitIdle(long timeoutMs) throws InterruptedException {
                return q.awaitIdle(timeoutMs);
            }
        };
    }

    interface Scenario {
        void run(String name) throws Exception;
    }

    static int failures;

    static void scenario(String name, Scenario s) {
        try {
            s.run(name);
        } catch (Throwable t) {
            fail(name, "threw " + t);
        }
    }

    static void check(String name, boolean ok, String detail) {
        if (ok) {
            System.out.println("PASS " + name);
        } else {
            fail(name, detail);
        }
    }

    static void fail(String name, String detail) {
        failures++;
        System.out.println("FAIL " + name + ": " + detail);
    }

    static String refusal(ListenSession s) {
        try {
            s.claim();
            return null;
        } catch (IllegalStateException e) {
            return e.getMessage();
        }
    }

    static String describe(ListenSession.Result r) {
        return r.outcome + "/" + r.stop + " \"" + r.text + "\" " + r.audioMs + " ms " + r.reason;
    }

    // ---- fakes for the continuous ears session (meeting plan U3) ----

    static final class FakeClock implements EarsSession.Clock {
        long now = 1000;
        long nanos;

        @Override
        public long nowMs() {
            return now;
        }

        @Override
        public long nanoTime() {
            return nanos;
        }
    }

    /** A microphone whose read() hands out one silent chunk per call, blocking
     * briefly when the capture thread is running so the loop cannot spin. */
    static final class FakeEarsMic implements EarsSession.Mic {
        final Log log;
        volatile boolean closed;

        FakeEarsMic(Log log) {
            this.log = log;
        }

        @Override
        public int read(short[] pcm) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            }
            Arrays.fill(pcm, (short) 0);
            return CHUNK;
        }

        @Override
        public void close() {
            closed = true;
            log.add("mic-closed");
        }
    }

    static final class FakeCapture implements EarsSession.Capture {
        final Log log;
        boolean fails;
        int opens;
        FakeEarsMic mic;

        FakeCapture(Log log) {
            this.log = log;
        }

        @Override
        public EarsSession.Mic open() throws IOException {
            opens++;
            if (fails) {
                throw new IOException("no microphone");
            }
            log.add("mic-opened");
            mic = new FakeEarsMic(log);
            return mic;
        }
    }

    static final class FakeSpotter implements EarsSession.Spotter {
        boolean hitNext;
        int resets;
        int chunks;

        @Override
        public boolean hears(short[] pcm, int n) {
            chunks++;
            boolean hit = hitNext;
            hitNext = false;
            return hit;
        }

        @Override
        public void reset() {
            resets++;
            hitNext = false; // a reset engine forgets what it was about to report
        }
    }

    static final class FakeGate implements EarsSession.Gate {
        boolean speech;
        int resets;
        int chunks;

        @Override
        public boolean speech(float[] samples, int n) {
            chunks++;
            return speech;
        }

        @Override
        public void reset() {
            resets++;
        }
    }

    static final class FakeEarsRecognizer implements EarsSession.Recognizer {
        String text = "";
        boolean endpoint;
        int resets;
        int accepted;
        /** Every sample handed over, held catch-up included. */
        long samples;
        /** When set, each 80 ms of audio "costs" decodeNsPerChunk on this clock. */
        FakeClock clock;
        long decodeNsPerChunk;

        @Override
        public void accept(float[] samples, int n) {
            accepted++;
            this.samples += n;
            if (clock != null) {
                clock.nanos += decodeNsPerChunk * ((n + CHUNK - 1) / CHUNK);
            }
        }

        @Override
        public boolean isEndpoint() {
            return endpoint;
        }

        @Override
        public String text() {
            return text;
        }

        @Override
        public void reset() {
            resets++;
            text = "";
            endpoint = false;
        }
    }

    /** Like VoiceDirection's sampler, drain() hands out only the readings since the last drain. */
    static final class FakeSampling implements EarsSession.Sampling {
        final List<Float> angles;
        int drained;
        boolean stopped;

        FakeSampling(List<Float> angles) {
            this.angles = angles;
        }

        @Override
        public List<Float> drain() {
            List<Float> out = new ArrayList<Float>(angles.subList(drained, angles.size()));
            drained = angles.size();
            return out;
        }

        @Override
        public void stop() {
            stopped = true;
        }
    }

    static final class FakeDirection implements EarsSession.Direction {
        List<Float> angles = new ArrayList<Float>();
        int starts;
        FakeSampling last;
        /** Like the NC chip in side mode: its angle is a side, not a bearing. */
        boolean sideOnly;

        @Override
        public EarsSession.Sampling start() {
            starts++;
            last = new FakeSampling(angles);
            return last;
        }

        @Override
        public boolean sideOnly() {
            return sideOnly;
        }
    }

    static final class FakeEarsClient implements EarsSession.Client {
        final List<EarsSession.Utterance> heard = Collections.synchronizedList(new ArrayList<EarsSession.Utterance>());

        @Override
        public void heard(EarsSession.Utterance u) {
            heard.add(u);
        }
    }

    static final class FakeToken implements LeaseKeeper.Token {
        Runnable onDeath;
        boolean dead;

        @Override
        public void linkToDeath(Runnable r) throws Exception {
            if (dead) {
                throw new Exception("dead");
            }
            onDeath = r;
        }

        @Override
        public void unlinkToDeath(Runnable r) {
            if (onDeath == r) {
                onDeath = null;
            }
        }

        void die() {
            Runnable r = onDeath;
            if (r != null) {
                r.run();
            }
        }
    }

    static final class FakeSwitch implements CueClassifier.Switch {
        boolean on = true;

        @Override
        public boolean answersWhenSpokenTo() {
            return on;
        }
    }

    static final class Diag implements EarsSession.Diag {
        final List<String> lines = Collections.synchronizedList(new ArrayList<String>());

        @Override
        public void log(String line) {
            lines.add(line);
        }

        /** The capture thread appends while a scenario reads: iterate under the list's lock. */
        boolean mention(String needle) {
            synchronized (lines) {
                for (String l : lines) {
                    if (l.contains(needle)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    /** One ears session with every fake wired, driven chunk by chunk. */
    static final class Rig {
        static final long TAIL = 500;
        final Log log = new Log();
        final FakeClock clock = new FakeClock();
        final FakeCapture capture = new FakeCapture(log);
        final FakeSpotter spotter = new FakeSpotter();
        final FakeGate gate = new FakeGate();
        final FakeEarsRecognizer rec = new FakeEarsRecognizer();
        final FakeDirection direction = new FakeDirection();
        final FakeSwitch sw = new FakeSwitch();
        final Diag diag = new Diag();
        final FakeEarsClient client = new FakeEarsClient();
        final FakeToken token = new FakeToken();
        final EarsSession session;
        final short[] pcm = new short[CHUNK];

        Rig() {
            session = new EarsSession(clock, capture, spotter, gate, rec, direction, new CueClassifier(sw), TAIL, diag);
        }

        /** With EarsTuning's wake gate on or off. */
        Rig(boolean gateWake) {
            session = new EarsSession(clock, capture, spotter, gate, rec, direction, new CueClassifier(sw), TAIL, diag,
                    gateWake);
        }

        void open(boolean charger) {
            session.open("10001", token, client, charger);
        }

        /** Advances the clock one 80 ms chunk and feeds it, with speech present or not. */
        void chunk(boolean speech) {
            clock.now += 80;
            gate.speech = speech;
            session.feed(pcm, CHUNK);
        }

        /** Feeds an utterance: speech for n chunks, the words decoded from the
         * second chunk on (a real recogniser's text trails the audio), then the endpoint. */
        void utter(String text, int n) {
            for (int i = 0; i < n; i++) {
                chunk(true);
                rec.text = text;
            }
            rec.endpoint = true;
            chunk(true);
        }

        void silence(long ms) {
            for (long t = 0; t < ms; t += 80) {
                chunk(false);
            }
        }

        /** One chunk, the client's renew and the session's tick, as the engine's ticker would run it. */
        void step(boolean speech) {
            chunk(speech);
            session.renew("10001", false);
            session.tick();
        }

        /** step()s for ms of audio. */
        void steps(boolean speech, long ms) {
            for (long t = 0; t < ms; t += 80) {
                step(speech);
            }
        }

        boolean awaitMic(boolean open) throws InterruptedException {
            for (int i = 0; i < 200; i++) {
                if (session.capturing() == open) {
                    if (!open) {
                        // The loop closes the microphone right after it notices.
                        for (int j = 0; j < 200 && log.indexOf("mic-closed") < 0; j++) {
                            Thread.sleep(5);
                        }
                    }
                    return true;
                }
                Thread.sleep(5);
            }
            return false;
        }

        String heard() {
            StringBuilder b = new StringBuilder();
            synchronized (client.heard) {
                for (EarsSession.Utterance u : client.heard) {
                    b.append(u.tier).append(u.partial ? "p" : "").append(u.called ? "c" : "").append('k')
                            .append(u.kind).append(u.text.isEmpty() ? "-" : "w").append('@').append(u.at).append(' ');
                }
            }
            return b.toString();
        }
    }

    static final class Released implements LeaseKeeper.Released {
        final List<String> reasons = new ArrayList<String>();

        @Override
        public void released(String holder, String reason) {
            reasons.add(holder + ":" + reason);
        }
    }

    public static void main(String[] args) {
        // ---- ListenSession.capture: endpoint, cap, no speech ----
        scenario("stops_at_endpoint", new Scenario() {
            public void run(String n) {
                Log log = new Log();
                FakeMic mic = new FakeMic(log);
                FakeRecognizer rec = new FakeRecognizer(log, "MY NAME IS SARAH", CHUNK * 5, CHUNK * 20);
                ListenSession.Result r = ListenSession.capture(mic, rec, 6000);
                check(n, r.outcome == ListenSession.Outcome.HEARD && r.stop == ListenSession.Stop.ENDPOINT
                        && "MY NAME IS SARAH".equals(r.text) && mic.reads == 20 && r.audioMs == 1600,
                        describe(r) + " reads=" + mic.reads);
            }
        });
        scenario("stops_at_cap", new Scenario() {
            public void run(String n) {
                Log log = new Log();
                FakeMic mic = new FakeMic(log);
                FakeRecognizer rec = new FakeRecognizer(log, "SARAH", CHUNK * 5, -1);
                ListenSession.Result r = ListenSession.capture(mic, rec, 2000);
                check(n, r.outcome == ListenSession.Outcome.HEARD && r.stop == ListenSession.Stop.CAP
                        && "SARAH".equals(r.text) && r.audioMs >= 2000 && r.audioMs < 2080
                        && rec.finished, describe(r) + " finished=" + rec.finished);
            }
        });
        scenario("silence_reports_no_speech", new Scenario() {
            public void run(String n) {
                Log log = new Log();
                FakeRecognizer rec = new FakeRecognizer(log, "", 0, -1);
                ListenSession.Result r = ListenSession.capture(new FakeMic(log), rec, 1000);
                check(n, r.outcome == ListenSession.Outcome.NO_SPEECH && r.stop == ListenSession.Stop.CAP
                        && "".equals(r.text), describe(r));
            }
        });
        scenario("silence_endpoint_reports_no_speech", new Scenario() {
            public void run(String n) {
                Log log = new Log();
                FakeRecognizer rec = new FakeRecognizer(log, "", 0, CHUNK * 10);
                ListenSession.Result r = ListenSession.capture(new FakeMic(log), rec, 6000);
                check(n, r.outcome == ListenSession.Outcome.NO_SPEECH && r.stop == ListenSession.Stop.ENDPOINT,
                        describe(r));
            }
        });
        scenario("whitespace_transcript_is_no_speech", new Scenario() {
            public void run(String n) {
                Log log = new Log();
                FakeRecognizer rec = new FakeRecognizer(log, "   ", 0, CHUNK);
                ListenSession.Result r = ListenSession.capture(new FakeMic(log), rec, 6000);
                check(n, r.outcome == ListenSession.Outcome.NO_SPEECH && "".equals(r.text), describe(r));
            }
        });
        scenario("microphone_failure_reports_failed", new Scenario() {
            public void run(String n) {
                Log log = new Log();
                FakeMic mic = new FakeMic(log);
                mic.failAt = 3;
                FakeRecognizer rec = new FakeRecognizer(log, "SAR", 0, -1);
                ListenSession.Result r = ListenSession.capture(mic, rec, 6000);
                check(n, r.outcome == ListenSession.Outcome.FAILED && r.stop == ListenSession.Stop.ERROR
                        && ListenSession.FAIL_MIC.equals(r.reason), describe(r));
            }
        });
        scenario("cap_is_clamped", new Scenario() {
            public void run(String n) {
                long lo = ListenSession.clampCap(0);
                long hi = ListenSession.clampCap(600000);
                long mid = ListenSession.clampCap(4000);
                check(n, lo == ListenSession.MIN_CAP_MS && hi == ListenSession.MAX_CAP_MS && mid == 4000
                        && ListenSession.clampCap(-5) == ListenSession.MIN_CAP_MS
                        && ListenSession.DEFAULT_CAP_MS == 6000, lo + " " + hi + " " + mid);
            }
        });

        // ---- ListenSession.run: the whole listen ----
        scenario("run_opens_mic_and_closes_everything", new Scenario() {
            public void run(String n) {
                Log log = new Log();
                FakeEars ears = new FakeEars(log, new FakeRecognizer(log, "SARAH", 0, CHUNK * 4));
                ListenSession s = new ListenSession(ALWAYS_IDLE, ears, 1000);
                s.claim();
                ListenSession.Result r = s.run(6000);
                check(n, r.outcome == ListenSession.Outcome.HEARD && log.indexOf("mic-opened") >= 0
                        && log.indexOf("mic-closed") > log.indexOf("mic-opened")
                        && log.indexOf("recognizer-closed") >= 0, describe(r) + " " + log);
            }
        });
        scenario("mic_that_will_not_open_reports_failed", new Scenario() {
            public void run(String n) {
                Log log = new Log();
                FakeEars ears = new FakeEars(log, new FakeRecognizer(log, "", 0, -1));
                ears.openFails = true;
                ListenSession s = new ListenSession(ALWAYS_IDLE, ears, 1000);
                s.claim();
                ListenSession.Result r = s.run(1000);
                check(n, r.outcome == ListenSession.Outcome.FAILED && ListenSession.FAIL_MIC.equals(r.reason)
                        && log.indexOf("recognizer-closed") >= 0 && refusal(s) == null, describe(r) + " " + log);
            }
        });

        // ---- queue interplay (KTD8) ----
        scenario("listen_waits_while_speech_plays_then_starts", new Scenario() {
            public void run(String n) throws Exception {
                final Log log = new Log();
                final SpeechQueue q = new SpeechQueue(16);
                final HeldVoice voice = new HeldVoice(log);
                q.speak(1, "What's your name?", new Line(log));
                Thread player = new Thread(new Runnable() {
                    public void run() {
                        try {
                            q.playNext(voice, true);
                        } catch (InterruptedException ignored) {
                        }
                    }
                });
                player.start();
                voice.started.await(5, TimeUnit.SECONDS);
                FakeEars ears = new FakeEars(log, new FakeRecognizer(log, "SARAH", 0, CHUNK * 4));
                final ListenSession s = new ListenSession(idleOf(q), ears, 5000);
                s.claim();
                final AtomicReference<ListenSession.Result> out = new AtomicReference<ListenSession.Result>();
                Thread listener = new Thread(new Runnable() {
                    public void run() {
                        out.set(s.run(6000));
                    }
                });
                listener.start();
                Thread.sleep(300);
                boolean openedEarly = log.indexOf("mic-opened") >= 0;
                voice.release.countDown();
                listener.join(5000);
                player.join(5000);
                ListenSession.Result r = out.get();
                check(n, !openedEarly && r != null && r.outcome == ListenSession.Outcome.HEARD
                        && log.indexOf("mic-opened") > log.indexOf("speech-end")
                        && r.waitedMs >= 250, "early=" + openedEarly + " " + log
                        + (r == null ? "" : " " + describe(r) + " waited " + r.waitedMs));
            }
        });
        scenario("listen_waits_for_queued_lines_too", new Scenario() {
            public void run(String n) throws Exception {
                SpeechQueue q = new SpeechQueue(16);
                q.speak(1, "Hello.", new Line(new Log()));
                // Queued but not yet playing: not idle.
                boolean idle = q.awaitIdle(50);
                q.playNext(new SpeechQueue.Voice() {
                    public void startLine(long id, String text, long at) {
                    }

                    public void speak(String chunk) {
                    }

                    public void endLine(long id, boolean cancelled) {
                    }
                }, false);
                check(n, !idle && q.awaitIdle(50), "idle while a line waited");
            }
        });
        scenario("listen_at_once_when_queue_idle", new Scenario() {
            public void run(String n) throws Exception {
                Log log = new Log();
                SpeechQueue q = new SpeechQueue(16);
                FakeEars ears = new FakeEars(log, new FakeRecognizer(log, "SARAH", 0, CHUNK * 2));
                ListenSession s = new ListenSession(idleOf(q), ears, 5000);
                s.claim();
                ListenSession.Result r = s.run(6000);
                check(n, r.outcome == ListenSession.Outcome.HEARD && r.waitedMs < 200,
                        describe(r) + " waited " + r.waitedMs);
            }
        });
        scenario("speech_busy_past_timeout_fails_without_opening_mic", new Scenario() {
            public void run(String n) {
                Log log = new Log();
                FakeEars ears = new FakeEars(log, new FakeRecognizer(log, "SARAH", 0, CHUNK));
                ListenSession s = new ListenSession(new ListenSession.Idle() {
                    public boolean awaitIdle(long timeoutMs) {
                        return false;
                    }
                }, ears, 100);
                s.claim();
                ListenSession.Result r = s.run(6000);
                check(n, r.outcome == ListenSession.Outcome.FAILED
                        && ListenSession.FAIL_SPEECH_BUSY.equals(r.reason)
                        && log.indexOf("mic-opened") < 0 && refusal(s) == null, describe(r) + " " + log);
            }
        });

        // ---- one at a time, and callers ----
        scenario("second_concurrent_listen_refused", new Scenario() {
            public void run(String n) {
                Log log = new Log();
                FakeEars ears = new FakeEars(log, new FakeRecognizer(log, "SARAH", 0, CHUNK));
                ListenSession s = new ListenSession(ALWAYS_IDLE, ears, 1000);
                String first = refusal(s);
                String second = refusal(s);
                s.run(1000);
                String third = refusal(s);
                check(n, first == null && ListenSession.REFUSE_BUSY.equals(second) && third == null,
                        first + " / " + second + " / " + third);
            }
        });
        scenario("refused_when_recognizer_not_ready", new Scenario() {
            public void run(String n) {
                Log log = new Log();
                FakeEars ears = new FakeEars(log, new FakeRecognizer(log, "", 0, -1));
                ears.ready = false;
                ListenSession s = new ListenSession(ALWAYS_IDLE, ears, 1000);
                String r = refusal(s);
                ears.ready = true;
                check(n, ListenSession.REFUSE_UNAVAILABLE.equals(r) && refusal(s) == null, String.valueOf(r));
            }
        });
        scenario("unpinned_caller_is_denied", new Scenario() {
            public void run(String n) {
                // ListenService runs the same CallerCheck as SpeechService
                // (through CallerGate) before it claims the microphone.
                CallerCheck.Signers vendor = new CallerCheck.Signers() {
                    public List<String> digestsOf(String p) {
                        return Arrays.asList("00");
                    }
                };
                check(n, !CallerCheck.allows(new String[] {"com.miko.launcher_app"}, vendor)
                        && !CallerCheck.allows(new String[] {"com.miko3.mode.explore"}, vendor)
                        && !CallerCheck.allows(new String[0], vendor)
                        && !CallerCheck.allows(null, vendor), "allowed");
            }
        });

        // ---- the continuous ears session (meeting plan U3): the deaf window ----
        scenario("ears_deaf_window_drops_utterance_inside", new Scenario() {
            public void run(String n) {
                Rig r = new Rig();
                r.open(false);
                r.session.lineStarted();
                r.utter("HELLO THERE", 4);
                r.session.playbackIdle(); // window closes TAIL ms from now
                int resetsBefore = r.rec.resets;
                r.silence(Rig.TAIL + 160);
                check(n, r.client.heard.isEmpty() && r.rec.resets == resetsBefore + 1 && r.gate.resets >= 1
                        && r.spotter.resets >= 1, "heard=" + r.heard() + " resets=" + r.rec.resets + "/" + resetsBefore);
            }
        });
        scenario("ears_utterance_after_window_delivered", new Scenario() {
            public void run(String n) {
                Rig r = new Rig();
                r.open(false);
                r.session.lineStarted();
                r.silence(400);
                r.session.playbackIdle();
                long closes = r.session.deafUntilMs();
                r.silence(Rig.TAIL + 200); // 200 ms of hearing before anyone speaks
                r.utter("SO WHERE ARE YOU OFF TO", 5);
                EarsSession.Utterance u = r.client.heard.isEmpty() ? null : r.client.heard.get(0);
                check(n, u != null && !u.partial && u.at >= closes + 200 && u.tier == CueClassifier.TIER_WEAK
                        && r.client.heard.size() == 1, "heard=" + r.heard() + " closes=" + closes);
            }
        });
        scenario("ears_straddling_utterance_is_partial", new Scenario() {
            public void run(String n) {
                Rig r = new Rig();
                r.open(false);
                r.session.lineStarted();
                r.silence(400);
                r.session.playbackIdle();
                // Speech is already there on the first chunk after the window closes.
                r.silence(Rig.TAIL - 80);
                r.utter("HEY BUDDY", 4);
                EarsSession.Utterance u = r.client.heard.isEmpty() ? null : r.client.heard.get(0);
                check(n, u != null && u.partial && u.tier == CueClassifier.TIER_STRONG && r.client.heard.size() == 1,
                        "heard=" + r.heard());
            }
        });
        scenario("ears_line_start_mid_utterance_delivers_partial", new Scenario() {
            public void run(String n) {
                Rig r = new Rig();
                r.open(false);
                r.rec.text = "MORNING";
                r.chunk(true);
                r.chunk(true);
                r.session.lineStarted();
                r.chunk(true); // the robot is talking: this chunk is dropped
                EarsSession.Utterance u = r.client.heard.isEmpty() ? null : r.client.heard.get(0);
                check(n, u != null && u.partial && u.tier == CueClassifier.TIER_STRONG && r.client.heard.size() == 1
                        && r.direction.last != null && r.direction.last.stopped, "heard=" + r.heard());
            }
        });
        scenario("ears_clip_window_matches_a_spoken_line", new Scenario() {
            public void run(String n) {
                Rig a = new Rig();
                a.open(false);
                boolean opened = a.session.clipWindow("10001", 900);
                long clipUntil = a.session.deafUntilMs();
                a.utter("HELLO", 3); // 320 ms in: inside the window
                int before = a.rec.resets;
                a.silence(900 + Rig.TAIL);
                int after = a.rec.resets;
                Rig b = new Rig();
                b.open(false);
                b.session.lineStarted();
                b.silence(900);
                b.session.playbackIdle();
                long lineUntil = b.session.deafUntilMs();
                // Both windows: 900 ms of sound plus the tail, then one stream reset.
                check(n, opened && clipUntil == 1000 + 900 + Rig.TAIL && lineUntil == b.clock.now + Rig.TAIL
                        && a.client.heard.isEmpty() && after == before + 1
                        && !a.session.clipWindow("10002", 900),
                        "clipUntil=" + clipUntil + " lineUntil=" + lineUntil + " heard=" + a.heard()
                                + " resets " + before + "->" + after);
            }
        });

        // ---- one holder, bound to its uid ----
        scenario("ears_second_open_refused", new Scenario() {
            public void run(String n) {
                Rig r = new Rig();
                r.open(false);
                String refused = null;
                try {
                    r.session.open("10002", new FakeToken(), new FakeEarsClient(), false);
                } catch (IllegalStateException e) {
                    refused = e.getMessage();
                }
                // The same uid may open again (a restarted Explore), replacing its callback.
                r.session.open("10001", new FakeToken(), new FakeEarsClient(), false);
                check(n, EarsSession.REFUSE_HELD.equals(refused) && "10001".equals(r.session.holder()),
                        refused + " holder=" + r.session.holder());
            }
        });
        scenario("ears_one_shot_refused_while_open", new Scenario() {
            public void run(String n) {
                Rig r = new Rig();
                String before = null;
                String during = null;
                try {
                    r.session.refuseOneShot();
                } catch (IllegalStateException e) {
                    before = e.getMessage();
                }
                r.open(true); // held even while the charger keeps the microphone closed
                try {
                    r.session.refuseOneShot();
                } catch (IllegalStateException e) {
                    during = e.getMessage();
                }
                r.session.close("10001");
                String after = null;
                try {
                    r.session.refuseOneShot();
                } catch (IllegalStateException e) {
                    after = e.getMessage();
                }
                check(n, before == null && EarsSession.REFUSE_EARS_OPEN.equals(during) && after == null,
                        before + " / " + during + " / " + after);
            }
        });
        scenario("ears_renew_and_close_from_other_uid_refused", new Scenario() {
            public void run(String n) {
                Rig r = new Rig();
                r.open(false);
                boolean renewed = r.session.renew("10002", false);
                boolean closed = r.session.close("10002");
                boolean listened = r.session.listen("10002", 4000);
                boolean stillHeld = r.session.held() && "10001".equals(r.session.holder());
                boolean ownRenew = r.session.renew("10001", false);
                check(n, !renewed && !closed && !listened && stillHeld && ownRenew && r.diag.mention("10002")
                        && !r.diag.mention("10001 refused"), "renewed=" + renewed + " closed=" + closed
                        + " held=" + stillHeld + " diag=" + r.diag.lines);
            }
        });

        // ---- the keeper releases the microphone ----
        scenario("ears_three_missed_renews_release_capture", new Scenario() {
            public void run(String n) throws Exception {
                Rig r = new Rig();
                r.open(false);
                boolean opened = r.awaitMic(true);
                r.clock.now += EarsSession.TTL_MS - 100;
                r.session.renew("10001", false); // one renew in time keeps it
                r.clock.now += EarsSession.TTL_MS - 100;
                r.session.tick();
                boolean keptAfterRenew = r.session.held() && r.session.capturing();
                r.clock.now += 200; // now past three missed renews since the last one
                r.session.tick();
                boolean released = r.awaitMic(false) && !r.session.held();
                check(n, opened && keptAfterRenew && released && r.log.indexOf("mic-closed") >= 0
                        && r.diag.mention(LeaseKeeper.RELEASE_TTL),
                        "opened=" + opened + " kept=" + keptAfterRenew + " released=" + released + " " + r.log);
            }
        });
        scenario("ears_client_death_releases_capture", new Scenario() {
            public void run(String n) throws Exception {
                Rig r = new Rig();
                r.open(false);
                boolean opened = r.awaitMic(true);
                r.token.die();
                boolean released = r.awaitMic(false) && !r.session.held();
                check(n, opened && released && r.log.indexOf("mic-closed") >= 0
                        && r.diag.mention(LeaseKeeper.RELEASE_DIED), "opened=" + opened + " released=" + released
                        + " " + r.log);
            }
        });
        scenario("ears_close_releases_capture", new Scenario() {
            public void run(String n) throws Exception {
                Rig r = new Rig();
                r.open(false);
                boolean opened = r.awaitMic(true);
                boolean closed = r.session.close("10001");
                boolean released = r.awaitMic(false) && !r.session.held();
                check(n, opened && closed && released && r.log.indexOf("mic-closed") >= 0,
                        "opened=" + opened + " released=" + released + " " + r.log);
            }
        });
        scenario("ears_capture_that_will_not_open_retries_on_tick", new Scenario() {
            public void run(String n) throws Exception {
                Rig r = new Rig();
                r.capture.fails = true;
                r.open(false);
                Thread.sleep(50);
                boolean failedOnce = r.capture.opens == 1 && !r.session.capturing() && r.session.held();
                r.capture.fails = false;
                r.clock.now += EarsSession.RETRY_MS;
                r.session.tick();
                boolean opened = r.awaitMic(true);
                check(n, failedOnce && opened && r.capture.opens == 2, "opens=" + r.capture.opens + " " + r.log);
            }
        });

        // ---- the charger flag (KTD6) ----
        scenario("ears_charger_keeps_capturing_and_delivers_the_wake_word", new Scenario() {
            public void run(String n) throws Exception {
                // Hey Miko plan KTD5: the ears stay open on the charger (this replaces the meeting plan's KTD6 close).
                Rig r = new Rig();
                r.open(true);
                boolean opened = r.awaitMic(true);
                r.chunk(true);
                r.spotter.hitNext = true;
                r.chunk(true);
                int early = r.client.heard.size();
                r.rec.text = "HEY MIKO";
                r.rec.endpoint = true;
                r.chunk(true);
                boolean listenDocked = r.session.listen("10001", 6000);
                r.session.renew("10001", true);
                Thread.sleep(40);
                boolean stillCapturing = r.session.capturing() && r.session.held() && r.capture.opens == 1;
                EarsSession.Utterance u = r.client.heard.isEmpty() ? null : r.client.heard.get(0);
                check(n, opened && early == 1 && u != null && u.kind == CueClassifier.KIND_WAKE_WORD
                        && u.tier == CueClassifier.TIER_STRONG && listenDocked && stillCapturing
                        && r.client.heard.size() == 2,
                        "opened=" + opened + " early=" + early + " listenDocked=" + listenDocked
                                + " stillCapturing=" + stillCapturing + " heard=" + r.heard());
            }
        });
        scenario("ears_charger_latch_changes_never_close_the_capture", new Scenario() {
            public void run(String n) throws Exception {
                Rig r = new Rig();
                r.open(false);
                boolean opened = r.awaitMic(true);
                r.session.renew("10001", true);
                r.session.tick();
                Thread.sleep(40);
                boolean dockedOpen = r.session.capturing();
                r.session.renew("10001", false);
                r.session.tick();
                Thread.sleep(40);
                check(n, opened && dockedOpen && r.session.capturing() && r.capture.opens == 1
                        && r.log.indexOf("mic-closed") < 0,
                        "opened=" + opened + " dockedOpen=" + dockedOpen + " opens=" + r.capture.opens);
            }
        });

        // ---- cues, the wake word, the angle ----
        scenario("ears_wake_word_is_strong_with_the_switch_off", new Scenario() {
            public void run(String n) {
                Rig r = new Rig();
                r.sw.on = false;
                r.open(false);
                r.utter("SO ANYWAY THE PRINTER", 4); // no cue with the switch off
                int quiet = r.client.heard.size();
                r.rec.text = "HEY MIKO";
                r.chunk(true);
                r.spotter.hitNext = true;
                r.chunk(true);
                r.rec.endpoint = true;
                r.chunk(true);
                // The early cue (Hey Miko plan KTD4), then the words, marked already called.
                EarsSession.Utterance u = r.client.heard.size() < 2 ? null : r.client.heard.get(1);
                check(n, quiet == 0 && u != null && u.tier == CueClassifier.TIER_STRONG && !u.partial && u.called
                        && r.client.heard.size() == 2, "quiet=" + quiet + " heard=" + r.heard());
            }
        });
        // ---- Hey Miko plan U3 (KTD4): the wake word is delivered as soon as it is spotted ----
        scenario("ears_wake_mid_speech_delivers_an_early_cue_at_once", new Scenario() {
            public void run(String n) {
                Rig r = new Rig();
                r.open(false);
                r.direction.angles = new ArrayList<Float>(Arrays.asList(20f, 30f, 40f));
                r.chunk(true);
                long start = r.clock.now;
                r.rec.text = "HEY MIKO";
                r.chunk(true);
                r.spotter.hitNext = true;
                r.chunk(true); // the engine fires here, mid-speech
                EarsSession.Utterance e = r.client.heard.isEmpty() ? null : r.client.heard.get(0);
                int atOnce = r.client.heard.size();
                check(n, atOnce == 1 && e != null && "".equals(e.text) && e.kind == CueClassifier.KIND_WAKE_WORD
                        && e.tier == CueClassifier.TIER_STRONG && e.at == start && !e.called && !e.partial
                        && e.angle != null && e.angle == 30f && e.side == CueClassifier.SIDE_RIGHT,
                        "atOnce=" + atOnce + " start=" + start + " heard=" + r.heard()
                                + (e == null ? "" : " angle=" + e.angle));
            }
        });
        scenario("ears_end_of_a_called_utterance_is_marked_already_called", new Scenario() {
            public void run(String n) {
                Rig r = new Rig();
                r.open(false);
                r.direction.angles = new ArrayList<Float>(Arrays.asList(20f, 30f, 40f));
                r.chunk(true);
                long start = r.clock.now;
                r.rec.text = "HEY MIKO";
                r.spotter.hitNext = true;
                r.chunk(true);
                // More readings after the early cue: the final median takes all of them.
                r.direction.angles.add(80f);
                r.direction.angles.add(90f);
                r.utter("HEY MIKO WHAT'S UP", 2);
                EarsSession.Utterance f = r.client.heard.size() < 2 ? null : r.client.heard.get(1);
                check(n, r.client.heard.size() == 2 && f != null && f.called && f.at == start
                        && "HEY MIKO WHAT'S UP".equals(f.text) && f.kind == CueClassifier.KIND_WAKE_WORD
                        && f.angle != null && f.angle == 40f && !r.client.heard.get(0).called,
                        "heard=" + r.heard() + (f == null ? "" : " angle=" + f.angle));
            }
        });
        scenario("ears_two_hits_in_one_utterance_send_one_early_cue", new Scenario() {
            public void run(String n) {
                Rig r = new Rig();
                r.open(false);
                r.chunk(true);
                r.spotter.hitNext = true;
                r.chunk(true);
                r.chunk(true);
                r.spotter.hitNext = true;
                r.chunk(true);
                int early = r.client.heard.size();
                r.utter("HEY MIKO HEY MIKO", 2);
                int bare = 0;
                int called = 0;
                synchronized (r.client.heard) {
                    for (EarsSession.Utterance u : r.client.heard) {
                        bare += u.text.isEmpty() ? 1 : 0;
                        called += u.called ? 1 : 0;
                    }
                }
                check(n, early == 1 && r.client.heard.size() == 2 && bare == 1 && called == 1,
                        "early=" + early + " heard=" + r.heard());
            }
        });
        scenario("ears_wake_inside_the_deaf_window_delivers_nothing", new Scenario() {
            public void run(String n) {
                Rig r = new Rig();
                r.open(false);
                r.session.lineStarted();
                r.chunk(true);
                r.spotter.hitNext = true; // his own clip says the phrase: the window drops it unheard
                r.chunk(true);
                r.chunk(true);
                r.session.playbackIdle();
                r.silence(Rig.TAIL + 400);
                check(n, r.client.heard.isEmpty(), "heard=" + r.heard());
            }
        });
        scenario("ears_early_cue_keeps_the_conversation_listen_for_the_words", new Scenario() {
            public void run(String n) {
                Rig r = new Rig();
                r.open(false);
                boolean listening = r.session.listen("10001", 6000);
                r.chunk(true);
                r.spotter.hitNext = true;
                r.chunk(true);
                boolean stillListening = r.session.listening();
                int early = r.client.heard.size();
                r.utter("HEY MIKO I'M SAM", 2);
                EarsSession.Utterance f = r.client.heard.size() < 2 ? null : r.client.heard.get(1);
                check(n, listening && early == 1 && stillListening && f != null && f.called
                        && "HEY MIKO I'M SAM".equals(f.text) && !r.session.listening(),
                        "early=" + early + " stillListening=" + stillListening + " heard=" + r.heard());
            }
        });
        scenario("ears_bare_wake_with_the_gate_closed_is_unchanged", new Scenario() {
            public void run(String n) {
                Rig r = new Rig();
                r.open(false);
                r.spotter.hitNext = true;
                r.chunk(false);
                EarsSession.Utterance u = r.client.heard.isEmpty() ? null : r.client.heard.get(0);
                check(n, r.client.heard.size() == 1 && u != null && "".equals(u.text) && !u.called
                        && u.at == r.clock.now && u.kind == CueClassifier.KIND_WAKE_WORD, "heard=" + r.heard());
            }
        });
        scenario("ears_side_only_direction_sends_the_side_without_an_angle", new Scenario() {
            // Robot (2026-09-29): the NC chip tells only left from right, so its -90/+90
            // is a side, not a bearing. The brain's side search needs a side and no angle.
            public void run(String n) {
                Rig r = new Rig();
                r.open(false);
                r.direction.sideOnly = true;
                r.direction.angles = new ArrayList<Float>(Arrays.asList(-90f, null, -90f));
                r.chunk(true);
                r.rec.text = "HEY MIKO";
                r.spotter.hitNext = true;
                r.chunk(true);
                EarsSession.Utterance e = r.client.heard.isEmpty() ? null : r.client.heard.get(0);
                r.direction.angles.add(-90f);
                r.utter("HEY MIKO WHAT'S UP", 2);
                EarsSession.Utterance f = r.client.heard.size() < 2 ? null : r.client.heard.get(1);
                // A plain word burst with a side and no words is still delivered on the side.
                r.direction.angles = new ArrayList<Float>(Arrays.asList(90f, 90f));
                r.utter("", 4);
                EarsSession.Utterance b = r.client.heard.size() < 3 ? null : r.client.heard.get(2);
                check(n, e != null && e.kind == CueClassifier.KIND_WAKE_WORD && e.side == CueClassifier.SIDE_LEFT
                                && e.angle == null && f != null && f.called && f.side == CueClassifier.SIDE_LEFT
                                && f.angle == null && b != null && b.side == CueClassifier.SIDE_RIGHT && b.angle == null,
                        "heard=" + r.heard() + (e == null ? "" : " early=" + e.side + "/" + e.angle)
                                + (f == null ? "" : " end=" + f.side + "/" + f.angle)
                                + (b == null ? "" : " burst=" + b.side + "/" + b.angle));
            }
        });
        scenario("ears_direction_sampled_only_while_speech", new Scenario() {
            public void run(String n) {
                Rig r = new Rig();
                r.open(false);
                r.direction.angles = Arrays.asList(30f, 40f, null, 35f);
                r.silence(400);
                int startsWhileQuiet = r.direction.starts;
                r.utter("HEY BUDDY", 4);
                EarsSession.Utterance u = r.client.heard.isEmpty() ? null : r.client.heard.get(0);
                check(n, startsWhileQuiet == 0 && r.direction.starts == 1 && r.direction.last.stopped && u != null
                        && u.angle != null && u.angle == 35f && u.side == CueClassifier.SIDE_RIGHT,
                        "starts=" + r.direction.starts + " heard=" + r.heard()
                                + (u == null ? "" : " angle=" + u.angle + " side=" + u.side));
            }
        });
        scenario("ears_burst_without_words_or_side_is_dropped", new Scenario() {
            public void run(String n) {
                Rig r = new Rig();
                r.open(false);
                r.utter("", 4); // a burst the backend gave no angle for
                int noSide = r.client.heard.size();
                r.direction.angles = Arrays.asList(-50f, -45f);
                r.utter("", 4);
                EarsSession.Utterance u = r.client.heard.isEmpty() ? null : r.client.heard.get(0);
                check(n, noSide == 0 && u != null && u.tier == CueClassifier.TIER_WEAK && u.side == CueClassifier.SIDE_LEFT
                        && "".equals(u.text), "noSide=" + noSide + " heard=" + r.heard());
            }
        });
        scenario("ears_shove_then_sorry_is_strong", new Scenario() {
            public void run(String n) {
                Rig r = new Rig();
                r.open(false);
                r.session.shoved("10001", r.clock.now);
                r.silence(800);
                r.utter("OH SORRY", 4);
                EarsSession.Utterance u = r.client.heard.isEmpty() ? null : r.client.heard.get(0);
                check(n, u != null && u.tier == CueClassifier.TIER_STRONG, "heard=" + r.heard());
            }
        });
        scenario("ears_listen_expires_at_its_cap", new Scenario() {
            public void run(String n) {
                Rig r = new Rig();
                r.open(false);
                boolean started = r.session.listen("10001", 1000);
                r.clock.now += 900;
                r.session.tick();
                boolean stillOn = r.session.listening();
                r.clock.now += 200;
                r.session.tick();
                check(n, started && stillOn && !r.session.listening(), started + " " + stillOn + " " + r.session.listening());
            }
        });
        // ---- Robot 2026-10-01: a conversation listen's maxMs is the window to START answering ----
        scenario("ears_answer_started_in_the_window_runs_past_max", new Scenario() {
            // Speech from 2.5 s for 4 s into a 4 s listen: the whole answer at about 7.3 s
            // (6.5 s plus the recogniser's 0.8 s trailing silence), once, as the listen's.
            public void run(String n) {
                Rig r = new Rig();
                r.sw.on = false; // only a listen gives words a tier: a cue would be dropped
                r.open(false);
                long t0 = r.clock.now;
                r.session.listen("10001", 4000);
                r.steps(false, 2480);
                long start = r.clock.now + 80;
                for (int i = 0; i < 50; i++) { // 4 s of speech
                    r.step(true);
                    r.rec.text = i < 25 ? "WE WENT" : "WE WENT TO THE BEACH WITH MY SISTER";
                }
                boolean pastMax = r.clock.now - t0 > 4000 && r.session.listening() && r.client.heard.isEmpty();
                r.steps(false, 720);
                r.rec.endpoint = true;
                r.step(false);
                long doneAt = r.clock.now - t0;
                EarsSession.Utterance u = r.client.heard.isEmpty() ? null : r.client.heard.get(0);
                r.steps(false, 1000);
                check(n, pastMax && r.client.heard.size() == 1 && u != null
                                && "WE WENT TO THE BEACH WITH MY SISTER".equals(u.text) && u.at == start && !u.partial
                                && u.tier != CueClassifier.TIER_NONE && doneAt >= 7200 && doneAt <= 7500
                                && !r.session.listening(),
                        "pastMax=" + pastMax + " doneAt=" + doneAt + " start=" + start + " heard=" + r.heard()
                                + " listening=" + r.session.listening());
            }
        });
        scenario("ears_silent_listen_still_ends_at_max", new Scenario() {
            public void run(String n) {
                Rig r = new Rig();
                r.open(false);
                long t0 = r.clock.now;
                r.session.listen("10001", 4000);
                r.steps(false, 3920);
                boolean before = r.session.listening();
                long at = r.clock.now - t0;
                r.steps(false, 160);
                check(n, before && at < 4000 && !r.session.listening() && r.client.heard.isEmpty(),
                        "before=" + before + " at=" + at + " after=" + r.session.listening() + " heard=" + r.heard());
            }
        });
        scenario("ears_endless_answer_is_cut_at_the_hard_cap", new Scenario() {
            public void run(String n) {
                Rig r = new Rig();
                r.sw.on = false;
                r.open(false);
                long t0 = r.clock.now;
                r.session.listen("10001", 4000);
                r.steps(false, 1000);
                r.step(true);
                r.rec.text = "AND THEN AND THEN";
                while (r.clock.now - t0 < EarsSession.LISTEN_HARD_CAP_MS - 80) {
                    r.step(true);
                }
                boolean held = r.session.listening() && r.client.heard.isEmpty();
                while (r.client.heard.isEmpty() && r.clock.now - t0 < EarsSession.LISTEN_HARD_CAP_MS + 1000) {
                    r.step(true);
                }
                long cutAt = r.clock.now - t0;
                EarsSession.Utterance u = r.client.heard.isEmpty() ? null : r.client.heard.get(0);
                check(n, EarsSession.LISTEN_HARD_CAP_MS == 20000 && held && u != null
                                && "AND THEN AND THEN".equals(u.text) && !u.partial && u.tier != CueClassifier.TIER_NONE
                                && cutAt >= 20000 && cutAt <= 20160 && !r.session.listening(),
                        "held=" + held + " cutAt=" + cutAt + " heard=" + r.heard() + " listening="
                                + r.session.listening());
            }
        });
        scenario("ears_answer_begun_just_before_the_listen_is_its_answer", new Scenario() {
            // He finishes his question and they are already answering: speech that began
            // within LISTEN_EARLY_START_MS of the listen opening is the listen's answer.
            public void run(String n) {
                Rig r = new Rig();
                r.sw.on = false;
                r.open(false);
                r.step(true);
                long start = r.clock.now;
                r.rec.text = "YES I";
                r.steps(true, 400);
                long t0 = r.clock.now;
                r.session.listen("10001", 4000);
                r.steps(true, 4400);
                r.rec.text = "YES I HAVE BEEN THERE TWICE";
                boolean pastMax = r.clock.now - t0 > 4000 && r.session.listening();
                r.rec.endpoint = true;
                r.step(false);
                EarsSession.Utterance u = r.client.heard.isEmpty() ? null : r.client.heard.get(0);
                // Speech begun well before the listen is not held open past maxMs.
                Rig q = new Rig();
                q.sw.on = false;
                q.open(false);
                q.steps(true, 1040);
                long q0 = q.clock.now;
                q.session.listen("10001", 4000);
                q.steps(true, 4080);
                boolean early = q.session.listening();
                check(n, EarsSession.LISTEN_EARLY_START_MS == 500 && pastMax && r.client.heard.size() == 1
                                && u != null && "YES I HAVE BEEN THERE TWICE".equals(u.text) && u.at == start
                                && !r.session.listening() && !early && q.clock.now - q0 > 4000,
                        "pastMax=" + pastMax + " heard=" + r.heard() + " stillOnForAnOldUtterance=" + early);
            }
        });
        scenario("ears_wake_word_inside_a_long_answer_keeps_the_early_cue", new Scenario() {
            public void run(String n) {
                Rig r = new Rig();
                r.open(false);
                long t0 = r.clock.now;
                r.session.listen("10001", 4000);
                r.steps(false, 2480);
                r.step(true);
                r.rec.text = "HEY MIKO";
                r.spotter.hitNext = true;
                r.step(true);
                int early = r.client.heard.size();
                r.rec.text = "HEY MIKO I SAW A WHALE";
                r.steps(true, 3200);
                boolean pastMax = r.clock.now - t0 > 4000 && r.session.listening() && r.client.heard.size() == 1;
                r.rec.endpoint = true;
                r.step(false);
                EarsSession.Utterance e = r.client.heard.isEmpty() ? null : r.client.heard.get(0);
                EarsSession.Utterance f = r.client.heard.size() < 2 ? null : r.client.heard.get(1);
                check(n, early == 1 && e != null && e.kind == CueClassifier.KIND_WAKE_WORD && "".equals(e.text)
                                && pastMax && r.client.heard.size() == 2 && f != null && f.called
                                && "HEY MIKO I SAW A WHALE".equals(f.text) && f.at == e.at && !r.session.listening(),
                        "early=" + early + " pastMax=" + pastMax + " heard=" + r.heard());
            }
        });
        scenario("ears_logs_counters_not_words", new Scenario() {
            public void run(String n) {
                Rig r = new Rig();
                r.open(false);
                r.utter("HELLO SARAH", 4);
                r.session.close("10001");
                boolean summary = r.diag.mention("utterances");
                check(n, summary && !r.diag.mention("SARAH") && !r.diag.mention("HELLO") && !r.diag.mention("sarah"),
                        String.valueOf(r.diag.lines));
            }
        });

        // ---- Ears CPU switches (2026-09-30): EarsTuning, the wake gate, the decode timing ----
        scenario("ears_tuning_unset_is_ktd2", new Scenario() {
            public void run(String n) {
                final java.util.Map<String, String> p = new java.util.HashMap<String, String>();
                SpeechTuning.Props props = new SpeechTuning.Props() {
                    public String get(String key) {
                        return p.get(key);
                    }
                };
                EarsTuning d = EarsTuning.from(props);
                p.put(EarsTuning.DECODING_PROP, "nonsense");
                p.put(EarsTuning.PATHS_PROP, "x");
                p.put(EarsTuning.THREADS_PROP, "");
                p.put(EarsTuning.GATE_PROP, "maybe");
                EarsTuning junk = EarsTuning.from(props);
                String want = "decoding=modified_beam_search paths=2 threads=2 hotwords=on gate=off";
                check(n, want.equals(d.toString()) && want.equals(junk.toString())
                        && want.equals(EarsTuning.defaults().toString()), d + " / " + junk);
            }
        });
        scenario("ears_tuning_switches_and_clamps", new Scenario() {
            public void run(String n) {
                final java.util.Map<String, String> p = new java.util.HashMap<String, String>();
                SpeechTuning.Props props = new SpeechTuning.Props() {
                    public String get(String key) {
                        return p.get(key);
                    }
                };
                p.put(EarsTuning.DECODING_PROP, " greedy_search ");
                p.put(EarsTuning.PATHS_PROP, "64");
                p.put(EarsTuning.THREADS_PROP, "1");
                p.put(EarsTuning.GATE_PROP, "WAKE");
                EarsTuning a = EarsTuning.from(props);
                p.put(EarsTuning.THREADS_PROP, "0");
                p.put(EarsTuning.PATHS_PROP, "0");
                EarsTuning b = EarsTuning.from(props);
                check(n, "decoding=greedy_search paths=8 threads=1 hotwords=off gate=wake".equals(a.toString())
                        && !a.hotwords() && a.gateWake && b.threads == 1 && b.paths == 1, a + " / " + b);
            }
        });
        scenario("ears_gate_off_decodes_every_speech_chunk", new Scenario() {
            public void run(String n) {
                Rig r = new Rig(false);
                r.sw.on = false;
                r.open(false);
                r.utter("SO ANYWAY THE PRINTER", 4);
                r.session.close("10001");
                check(n, r.rec.samples == 5L * CHUNK && r.client.heard.isEmpty() && r.diag.mention("fed=5 gated=0"),
                        "samples=" + r.rec.samples + " " + r.diag.lines);
            }
        });
        scenario("ears_gate_holds_audio_while_the_words_cannot_matter", new Scenario() {
            public void run(String n) {
                Rig r = new Rig(true);
                r.sw.on = false;
                r.open(false);
                r.utter("SO ANYWAY THE PRINTER", 4); // the endpoint is not heard while shut
                r.silence(1200);                    // the hangover ends it
                r.session.close("10001");
                check(n, r.rec.samples == 0 && r.client.heard.isEmpty() && r.diag.mention("fed=0 gated=")
                        && r.diag.mention("decoded=0") && !r.session.listening(),
                        "samples=" + r.rec.samples + " heard=" + r.heard() + " " + r.diag.lines);
            }
        });
        scenario("ears_gate_feeds_the_held_utterance_when_the_engine_fires", new Scenario() {
            public void run(String n) {
                Rig r = new Rig(true);
                r.sw.on = false;
                r.open(false);
                r.chunk(true);
                r.chunk(true);
                long before = r.rec.samples;
                r.spotter.hitNext = true;
                r.chunk(true); // the early cue, then the two held chunks and this one
                long caught = r.rec.samples;
                r.rec.text = "HEY MIKO COME HERE";
                r.rec.endpoint = true;
                r.chunk(true);
                EarsSession.Utterance end = r.client.heard.size() < 2 ? null : r.client.heard.get(1);
                check(n, before == 0 && caught == 3L * CHUNK && r.rec.samples == 4L * CHUNK && end != null && end.called
                        && "HEY MIKO COME HERE".equals(end.text) && end.tier == CueClassifier.TIER_STRONG,
                        "before=" + before + " caught=" + caught + " heard=" + r.heard());
            }
        });
        scenario("ears_gate_stays_open_with_the_switch_on", new Scenario() {
            public void run(String n) {
                Rig r = new Rig(true); // the switch defaults on, as in the launcher
                r.open(false);
                r.utter("MIKO COME HERE", 3);
                EarsSession.Utterance u = r.client.heard.isEmpty() ? null : r.client.heard.get(0);
                check(n, r.rec.samples == 4L * CHUNK && u != null && u.tier == CueClassifier.TIER_STRONG
                        && u.kind == CueClassifier.KIND_NAME && !u.called, "samples=" + r.rec.samples
                        + " heard=" + r.heard());
            }
        });
        scenario("ears_gate_opens_for_a_conversation_listen", new Scenario() {
            public void run(String n) {
                Rig r = new Rig(true);
                r.sw.on = false;
                r.open(false);
                r.chunk(true);
                r.chunk(true); // held: no listen yet
                long before = r.rec.samples;
                r.session.listen("10001", 6000);
                r.rec.text = "I'M SAM";
                r.rec.endpoint = true;
                r.chunk(true);
                EarsSession.Utterance u = r.client.heard.isEmpty() ? null : r.client.heard.get(0);
                check(n, before == 0 && r.rec.samples == 3L * CHUNK && u != null && "I'M SAM".equals(u.text)
                        && !r.session.listening(), "before=" + before + " samples=" + r.rec.samples
                        + " heard=" + r.heard());
            }
        });
        scenario("ears_summary_reports_decode_ms_per_chunk", new Scenario() {
            public void run(String n) {
                Rig r = new Rig();
                r.rec.clock = r.clock;
                r.rec.decodeNsPerChunk = 12000000L; // 12 ms per 80 ms chunk
                r.open(false);
                r.utter("HELLO THERE", 3);
                r.rec.decodeNsPerChunk = 40000000L;
                r.utter("HELLO AGAIN", 3);
                r.session.close("10001");
                // Released: the summary (nearest-rank p50 of {12, 40} is 12), then cleared.
                boolean first = r.diag.mention("decode_p50=12.0 decode_p95=40.0 decode_max=40.0 decoded=2 fed=8");
                double[] five = {1, 2, 3, 4, 100};
                check(n, first && EarsSession.percentile(five, 95) == 100 && EarsSession.percentile(five, 50) == 3
                        && EarsSession.percentile(new double[] {7}, 95) == 7, String.valueOf(r.diag.lines));
            }
        });

        // ---- LeaseKeeper, extracted from the drive lease service ----
        scenario("keeper_acquire_renew_release", new Scenario() {
            public void run(String n) {
                Released out = new Released();
                LeaseKeeper k = new LeaseKeeper(3000, out);
                boolean a = k.acquire("A", new FakeToken(), 0);
                boolean b = k.acquire("B", new FakeToken(), 10);
                boolean again = k.acquire("A", new FakeToken(), 20);
                boolean renewB = k.renew("B", 30);
                boolean renewA = k.renew("A", 30);
                boolean releaseB = k.release("B");
                boolean heldStill = "A".equals(k.holder());
                boolean releaseA = k.release("A");
                check(n, a && !b && again && !renewB && renewA && !releaseB && heldStill && releaseA
                        && k.holder() == null && out.reasons.equals(Arrays.asList("A:" + LeaseKeeper.RELEASE_CLEAN)),
                        a + " " + b + " " + again + " " + renewB + " " + renewA + " " + out.reasons);
            }
        });
        scenario("keeper_ttl_expiry", new Scenario() {
            public void run(String n) {
                Released out = new Released();
                LeaseKeeper k = new LeaseKeeper(3000, out);
                k.acquire("A", new FakeToken(), 0);
                boolean early = k.check(2999);
                k.renew("A", 2999);
                boolean afterRenew = k.check(5000);
                boolean expired = k.check(6000);
                check(n, !early && !afterRenew && expired && k.holder() == null
                        && out.reasons.equals(Arrays.asList("A:" + LeaseKeeper.RELEASE_TTL)),
                        early + " " + afterRenew + " " + expired + " " + out.reasons);
            }
        });
        scenario("keeper_death_and_stale_death_ignored", new Scenario() {
            public void run(String n) {
                Released out = new Released();
                LeaseKeeper k = new LeaseKeeper(3000, out);
                FakeToken ta = new FakeToken();
                FakeToken tb = new FakeToken();
                k.acquire("A", ta, 0);
                k.release("A");
                boolean unlinked = ta.onDeath == null;
                k.acquire("B", tb, 10);
                Runnable stale = new Runnable() {
                    public void run() {
                    }
                };
                ta.onDeath = stale; // a notification A's token would still deliver
                ta.die();
                boolean bSurvives = "B".equals(k.holder());
                tb.die();
                check(n, unlinked && bSurvives && k.holder() == null
                        && out.reasons.equals(Arrays.asList("A:" + LeaseKeeper.RELEASE_CLEAN, "B:" + LeaseKeeper.RELEASE_DIED)),
                        unlinked + " " + bSurvives + " " + out.reasons);
            }
        });
        scenario("keeper_dead_token_never_acquires", new Scenario() {
            public void run(String n) {
                Released out = new Released();
                LeaseKeeper k = new LeaseKeeper(3000, out);
                FakeToken dead = new FakeToken();
                dead.dead = true;
                boolean got = k.acquire("A", dead, 0);
                check(n, !got && k.holder() == null && out.reasons.isEmpty(), got + " " + k.holder());
            }
        });
    }
}
