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
    }
}
