package com.miko3.launcher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Host-JVM checks for the launcher's speech service (voice plan U5; R8-R11,
 * AE3, KTD5, KTD8), driven by scripts/tests/test_speech_service.py. Lives in
 * the launcher's package so it can reach the package-private SpeechQueue,
 * SpeechTuning and CallerCheck; none of them touches android.*. A fake voice
 * records what the queue asks it to say, so ordering, cancelling and the
 * per-line callbacks are checked without sherpa-onnx or an AudioTrack.
 *
 * Prints one "PASS <name>" or "FAIL <name>: <detail>" line per scenario.
 */
public final class SpeechServiceHarness {
    static final Integer EXPLORE = 10101;
    static final Integer VOICE = 10102;

    /** Shared event log: the voice's and the listeners' events in order. */
    static final class Log {
        final List<String> events = Collections.synchronizedList(new ArrayList<String>());

        void add(String e) {
            events.add(e);
        }

        int indexOf(String e) {
            return events.indexOf(e);
        }

        int count(String e) {
            int n = 0;
            for (String s : new ArrayList<String>(events)) {
                if (s.equals(e)) {
                    n++;
                }
            }
            return n;
        }

        @Override
        public String toString() {
            return events.toString();
        }
    }

    static final class Line implements SpeechQueue.Listener {
        final String name;
        final Log log;

        Line(String name, Log log) {
            this.name = name;
            this.log = log;
        }

        @Override
        public void finished() {
            log.add("finished:" + name);
        }

        @Override
        public void cancelled() {
            log.add("cancelled:" + name);
        }
    }

    /** Records each call; runs a hook when a given chunk is spoken, standing in
     * for another caller acting while that chunk plays. */
    static final class FakeVoice implements SpeechQueue.Voice {
        final Log log;
        final Map<String, Runnable> hooks = new HashMap<String, Runnable>();
        String failOn;

        FakeVoice(Log log) {
            this.log = log;
        }

        @Override
        public void startLine(long id, String text, long queuedAtNanos) {
            log.add("start:" + text);
        }

        @Override
        public void speak(String chunk) {
            if (chunk.equals(failOn)) {
                throw new IllegalStateException("synthesis failed");
            }
            log.add("say:" + chunk);
            Runnable hook = hooks.get(chunk);
            if (hook != null) {
                hook.run();
            }
        }

        @Override
        public void endLine(long id, boolean cancelled) {
            log.add("end:" + (cancelled ? "cancelled" : "played"));
        }
    }

    interface Scenario {
        void run(String name) throws Exception;
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            System.out.println("PASS " + name);
        } else {
            System.out.println("FAIL " + name + ": " + detail);
        }
    }

    private static void scenario(String name, Scenario s) {
        try {
            s.run(name);
        } catch (Throwable t) {
            System.out.println("FAIL " + name + ": threw " + t);
        }
    }

    static void drain(SpeechQueue q, SpeechQueue.Voice v) throws InterruptedException {
        while (q.playNext(v, false)) {
            // one line per call
        }
    }

    static String refusal(SpeechQueue q, String text) {
        try {
            q.speak(EXPLORE, text, new Line("x", new Log()));
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    static int words(String s) {
        return s.trim().isEmpty() ? 0 : s.trim().split("\\s+").length;
    }

    public static void main(String[] args) {
        scenario("second_line_queues_behind_first", new Scenario() {
            public void run(String n) throws Exception {
                Log log = new Log();
                SpeechQueue q = new SpeechQueue(16);
                q.speak(EXPLORE, "Hi there.", new Line("a", log));
                q.speak(VOICE, "Hello.", new Line("b", log));
                drain(q, new FakeVoice(log));
                List<String> want = Arrays.asList("start:Hi there.", "say:Hi there.", "end:played", "finished:a",
                        "start:Hello.", "say:Hello.", "end:played", "finished:b");
                check(n, log.events.equals(want), log.toString());
            }
        });
        scenario("first_callback_fires_before_second_starts", new Scenario() {
            public void run(String n) throws Exception {
                Log log = new Log();
                SpeechQueue q = new SpeechQueue(16);
                q.speak(EXPLORE, "One.", new Line("a", log));
                FakeVoice v = new FakeVoice(log);
                // A second mode asks while the first line is playing (AE3).
                final SpeechQueue fq = q;
                final Log fl = log;
                v.hooks.put("One.", new Runnable() {
                    public void run() {
                        fq.speak(VOICE, "Two.", new Line("b", fl));
                    }
                });
                drain(q, v);
                int fin = log.indexOf("finished:a");
                int second = log.indexOf("start:Two.");
                check(n, fin >= 0 && second > fin && log.indexOf("say:One.") < fin, log.toString());
            }
        });
        scenario("finished_fires_once_per_line", new Scenario() {
            public void run(String n) throws Exception {
                Log log = new Log();
                SpeechQueue q = new SpeechQueue(16);
                q.speak(EXPLORE, "One. Two. Three.", new Line("a", log));
                drain(q, new FakeVoice(log));
                check(n, log.count("finished:a") == 1 && log.count("say:Two.") == 1
                        && log.count("end:played") == 1, log.toString());
            }
        });
        scenario("cancel_drops_only_callers_queued_lines", new Scenario() {
            public void run(String n) throws Exception {
                Log log = new Log();
                SpeechQueue q = new SpeechQueue(16);
                q.speak(EXPLORE, "E1.", new Line("e1", log));
                q.speak(VOICE, "V1.", new Line("v1", log));
                q.speak(EXPLORE, "E2.", new Line("e2", log));
                q.cancel(EXPLORE);
                boolean firedAtOnce = log.count("cancelled:e1") == 1 && log.count("cancelled:e2") == 1;
                drain(q, new FakeVoice(log));
                check(n, firedAtOnce && log.indexOf("say:E1.") < 0 && log.indexOf("say:E2.") < 0
                        && log.count("finished:v1") == 1, log.toString());
            }
        });
        scenario("cancel_leaves_other_callers_playing_line", new Scenario() {
            public void run(String n) throws Exception {
                Log log = new Log();
                final SpeechQueue q = new SpeechQueue(16);
                q.speak(VOICE, "First part. Second part.", new Line("v", log));
                q.speak(EXPLORE, "Mine.", new Line("e", log));
                FakeVoice v = new FakeVoice(log);
                v.hooks.put("First part.", new Runnable() {
                    public void run() {
                        q.cancel(EXPLORE);
                    }
                });
                drain(q, v);
                check(n, log.count("say:Second part.") == 1 && log.count("finished:v") == 1
                        && log.count("cancelled:e") == 1 && log.indexOf("say:Mine.") < 0, log.toString());
            }
        });
        scenario("cancel_stops_own_playing_line_at_sentence_boundary", new Scenario() {
            public void run(String n) throws Exception {
                Log log = new Log();
                final SpeechQueue q = new SpeechQueue(16);
                q.speak(EXPLORE, "First part. Second part.", new Line("e", log));
                FakeVoice v = new FakeVoice(log);
                v.hooks.put("First part.", new Runnable() {
                    public void run() {
                        q.cancel(EXPLORE);
                    }
                });
                drain(q, v);
                // The sentence already playing is let through (never cut mid-word),
                // the rest is skipped, and the callback says cancelled.
                check(n, log.count("say:First part.") == 1 && log.count("say:Second part.") == 0
                        && log.count("end:cancelled") == 1 && log.count("cancelled:e") == 1
                        && log.count("finished:e") == 0, log.toString());
            }
        });
        scenario("cancel_with_nothing_queued_is_harmless", new Scenario() {
            public void run(String n) throws Exception {
                SpeechQueue q = new SpeechQueue(16);
                q.cancel(EXPLORE);
                q.cancelLine(12345);
                check(n, q.queued() == 0, "queued " + q.queued());
            }
        });
        scenario("dead_caller_lines_are_dropped", new Scenario() {
            public void run(String n) throws Exception {
                Log log = new Log();
                SpeechQueue q = new SpeechQueue(16);
                long a = q.speak(EXPLORE, "A.", new Line("a", log));
                long b = q.speak(EXPLORE, "B.", new Line("b", log));
                q.speak(VOICE, "C.", new Line("c", log));
                // What each line's DeathRecipient does when the caller's process dies.
                q.cancelLine(a);
                q.cancelLine(b);
                drain(q, new FakeVoice(log));
                check(n, log.count("cancelled:a") == 1 && log.count("cancelled:b") == 1
                        && log.indexOf("say:A.") < 0 && log.indexOf("say:B.") < 0
                        && log.count("finished:c") == 1, log.toString());
            }
        });
        scenario("multi_sentence_line_one_chunk_per_sentence", new Scenario() {
            public void run(String n) throws Exception {
                List<String> c = SpeechQueue.chunks("Hi there! I don't think we've met. Who are you?", 16);
                check(n, c.equals(Arrays.asList("Hi there!", "I don't think we've met.", "Who are you?")),
                        c.toString());
            }
        });
        scenario("thirty_word_sentence_split_at_commas_within_limit", new Scenario() {
            public void run(String n) throws Exception {
                String s = "When the sun comes up over the hills, the birds start to sing their songs, "
                        + "and the little robot rolls out of his corner, looking for someone to talk to.";
                List<String> c = SpeechQueue.chunks(s, 16);
                boolean ok = words(s) == 30 && c.size() > 1;
                StringBuilder joined = new StringBuilder();
                for (int i = 0; i < c.size(); i++) {
                    ok &= words(c.get(i)) <= 16;
                    if (i < c.size() - 1) {
                        ok &= c.get(i).endsWith(",");
                    }
                    joined.append(i == 0 ? "" : " ").append(c.get(i));
                }
                ok &= joined.toString().equals(s);
                check(n, ok, c.toString());
            }
        });
        scenario("chunk_limit_is_configurable", new Scenario() {
            public void run(String n) throws Exception {
                String s = "one two three, four five six, seven eight nine, ten eleven twelve.";
                List<String> wide = SpeechQueue.chunks(s, 16);
                List<String> narrow = SpeechQueue.chunks(s, 6);
                check(n, wide.size() == 1 && narrow.equals(Arrays.asList("one two three, four five six,",
                        "seven eight nine, ten eleven twelve.")), wide + " / " + narrow);
            }
        });
        scenario("clause_without_commas_split_at_word_limit", new Scenario() {
            public void run(String n) throws Exception {
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i <= 20; i++) {
                    sb.append(i == 1 ? "" : " ").append("w").append(i);
                }
                List<String> c = SpeechQueue.chunks(sb.toString(), 16);
                check(n, c.size() == 2 && words(c.get(0)) == 16 && words(c.get(1)) == 4, c.toString());
            }
        });
        scenario("abbreviation_dr_does_not_end_sentence", new Scenario() {
            public void run(String n) throws Exception {
                List<String> c = SpeechQueue.chunks("Dr. Smith is here. Mr. Jones left.", 16);
                check(n, c.equals(Arrays.asList("Dr. Smith is here.", "Mr. Jones left.")), c.toString());
            }
        });
        scenario("decimal_number_does_not_end_sentence", new Scenario() {
            public void run(String n) throws Exception {
                List<String> c = SpeechQueue.chunks("It is 2.5 meters away. Wow.", 16);
                check(n, c.equals(Arrays.asList("It is 2.5 meters away.", "Wow.")), c.toString());
            }
        });
        scenario("line_without_punctuation_is_one_chunk", new Scenario() {
            public void run(String n) throws Exception {
                List<String> c = SpeechQueue.chunks("hello there little robot", 16);
                check(n, c.equals(Arrays.asList("hello there little robot")), c.toString());
            }
        });
        scenario("whitespace_is_collapsed", new Scenario() {
            public void run(String n) throws Exception {
                List<String> c = SpeechQueue.chunks("  Hi\nthere.\t\tBye.  ", 16);
                check(n, c.equals(Arrays.asList("Hi there.", "Bye.")), c.toString());
            }
        });
        scenario("empty_line_refused_with_fixed_reason", new Scenario() {
            public void run(String n) throws Exception {
                SpeechQueue q = new SpeechQueue(16);
                String a = refusal(q, "");
                String b = refusal(q, null);
                check(n, SpeechQueue.REFUSE_EMPTY.equals(a) && SpeechQueue.REFUSE_EMPTY.equals(b), a + " / " + b);
            }
        });
        scenario("blank_line_refused_with_fixed_reason", new Scenario() {
            public void run(String n) throws Exception {
                String r = refusal(new SpeechQueue(16), " \n\t ");
                check(n, SpeechQueue.REFUSE_EMPTY.equals(r), String.valueOf(r));
            }
        });
        scenario("overlong_line_refused_with_fixed_reason", new Scenario() {
            public void run(String n) throws Exception {
                StringBuilder sb = new StringBuilder();
                while (sb.length() <= SpeechQueue.MAX_CHARS) {
                    sb.append("word ");
                }
                String r = refusal(new SpeechQueue(16), sb.toString());
                check(n, SpeechQueue.REFUSE_TOO_LONG.equals(r), String.valueOf(r));
            }
        });
        scenario("refused_line_queues_nothing", new Scenario() {
            public void run(String n) throws Exception {
                SpeechQueue q = new SpeechQueue(16);
                refusal(q, "");
                check(n, q.queued() == 0, "queued " + q.queued());
            }
        });
        scenario("unpinned_caller_is_denied", new Scenario() {
            public void run(String n) throws Exception {
                // SpeechService runs the same CallerCheck as RobotSettingsService
                // (through CallerGate) before it touches the queue.
                CallerCheck.Signers vendor = new CallerCheck.Signers() {
                    public List<String> digestsOf(String p) {
                        return Arrays.asList("00");
                    }
                };
                check(n, !CallerCheck.allows(new String[] {"com.miko.launcher_app"}, vendor)
                        && !CallerCheck.allows(new String[0], vendor)
                        && !CallerCheck.allows(null, vendor), "allowed");
            }
        });
        scenario("shutdown_cancels_queued_and_refuses_new", new Scenario() {
            public void run(String n) throws Exception {
                Log log = new Log();
                SpeechQueue q = new SpeechQueue(16);
                q.speak(EXPLORE, "A.", new Line("a", log));
                q.shutdown();
                String r = refusal(q, "B.");
                boolean more = q.playNext(new FakeVoice(log), false);
                check(n, log.count("cancelled:a") == 1 && SpeechQueue.REFUSE_UNAVAILABLE.equals(r) && !more,
                        log + " " + r);
            }
        });
        scenario("voice_failure_cancels_that_line_only", new Scenario() {
            public void run(String n) throws Exception {
                Log log = new Log();
                SpeechQueue q = new SpeechQueue(16);
                q.speak(EXPLORE, "Broken.", new Line("a", log));
                q.speak(VOICE, "Fine.", new Line("b", log));
                FakeVoice v = new FakeVoice(log);
                v.failOn = "Broken.";
                drain(q, v);
                check(n, log.count("cancelled:a") == 1 && log.count("finished:b") == 1, log.toString());
            }
        });
        scenario("tuning_defaults", new Scenario() {
            public void run(String n) throws Exception {
                SpeechTuning t = SpeechTuning.from(new SpeechTuning.Props() {
                    public String get(String key) {
                        return "";
                    }
                });
                check(n, t.threads == 4 && t.maxWords == 16, t.threads + "/" + t.maxWords);
            }
        });
        scenario("tuning_overrides_and_clamps", new Scenario() {
            public void run(String n) throws Exception {
                final Map<String, String> p = new HashMap<String, String>();
                SpeechTuning.Props props = new SpeechTuning.Props() {
                    public String get(String key) {
                        return p.get(key);
                    }
                };
                p.put(SpeechTuning.THREADS_PROP, "2");
                p.put(SpeechTuning.MAX_WORDS_PROP, "10");
                SpeechTuning a = SpeechTuning.from(props);
                p.put(SpeechTuning.THREADS_PROP, "64");
                p.put(SpeechTuning.MAX_WORDS_PROP, "1");
                SpeechTuning b = SpeechTuning.from(props);
                p.put(SpeechTuning.THREADS_PROP, "lots");
                SpeechTuning c = SpeechTuning.from(props);
                check(n, a.threads == 2 && a.maxWords == 10 && b.threads == 4 && b.maxWords == 4
                        && c.threads == 4, a.threads + "," + a.maxWords + " " + b.threads + "," + b.maxWords
                        + " " + c.threads);
            }
        });
    }
}
