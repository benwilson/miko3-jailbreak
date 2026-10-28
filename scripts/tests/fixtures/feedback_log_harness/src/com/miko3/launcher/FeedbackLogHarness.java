package com.miko3.launcher;

import com.miko3.shared.Feedback;
import com.miko3.shared.Json;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Host-JVM checks for the feedback log (owner 2026-10-02: capture the feedback,
 * not the conversation), driven by scripts/tests/test_feedback_log.py: the
 * shared Feedback value's parsing of a turn's "feedback" field, the launcher's
 * FeedbackStore (append-only, capped, newest first, persisted), and PeopleStore's
 * side of it (who an entry names, and forget deleting a person's entries).
 *
 * Prints one "PASS <name>" or "FAIL <name>: <detail>" line per scenario.
 */
public final class FeedbackLogHarness {
    static final class FakeClock implements PeopleStore.Clock {
        long now = 1_700_000_000_000L;

        @Override
        public long nowMillis() {
            return now;
        }
    }

    static byte[] jpeg(int tag) {
        return new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) tag, 1, 2, 3, (byte) 0xFF, (byte) 0xD9};
    }

    static int failures;

    static void check(String name, boolean ok, String detail) {
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

    static void scenario(String name, Scenario s) {
        try {
            s.run(name);
        } catch (Throwable t) {
            failures++;
            System.out.println("FAIL " + name + ": threw " + t);
        }
    }

    static File tempDir() throws Exception {
        File d = File.createTempFile("feedback_log_", "");
        d.delete();
        d.mkdirs();
        d.deleteOnExit();
        return d;
    }

    static Map<String, Object> fb(Object kind, Object summary, Object quote) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("kind", kind);
        m.put("summary", summary);
        m.put("quote", quote);
        return m;
    }

    static Feedback complaint(String summary) {
        return Feedback.of("complaint", summary, "you keep bumping into my chair");
    }

    static String summaries(List<FeedbackStore.Entry> entries) {
        StringBuilder b = new StringBuilder();
        for (FeedbackStore.Entry e : entries) {
            b.append(b.length() == 0 ? "" : ",").append(e.summary);
        }
        return b.toString();
    }

    public static void main(String[] args) {
        // ---- the shared value: a turn's "feedback" field ----
        scenario("parse_accepts_each_kind_and_cleans_the_text", n -> {
            boolean all = true;
            for (String k : Arrays.asList("suggestion", "complaint", "praise", "bug")) {
                Feedback f = Feedback.fromJson(fb(" " + k.toUpperCase() + " ", "  He should\n  slow down. ", " go\tslower "));
                all &= f != null && k.equals(f.kind) && "He should slow down.".equals(f.summary)
                        && "go slower".equals(f.quote);
            }
            Feedback noQuote = Feedback.fromJson(fb("praise", "Likes his voice.", ""));
            check(n, all && noQuote != null && "".equals(noQuote.quote), "noQuote=" + noQuote);
        });
        scenario("parse_ignores_a_malformed_or_empty_field", n -> {
            Map<String, Object> missing = new HashMap<String, Object>();
            missing.put("kind", "complaint");
            Object[] bad = {
                null, "complaint", 7L, Arrays.asList("complaint"), missing,
                fb("none", "", ""), fb("rant", "He is slow.", ""), fb("complaint", "", "x"),
                fb("complaint", "   ", "x"), fb(3L, "He is slow.", ""), fb("complaint", 5L, ""),
                fb("complaint", "He is slow.", 9L), fb(null, null, null),
            };
            StringBuilder accepted = new StringBuilder();
            for (Object o : bad) {
                if (Feedback.fromJson(o) != null) {
                    accepted.append(o).append(' ');
                }
            }
            // A missing quote reads as no quote, not as malformed.
            Map<String, Object> noQuote = new HashMap<String, Object>();
            noQuote.put("kind", "bug");
            noQuote.put("summary", "He froze mid-sentence.");
            Feedback f = Feedback.fromJson(noQuote);
            check(n, accepted.length() == 0 && f != null && "".equals(f.quote), "accepted=" + accepted + " f=" + f);
        });
        scenario("the_quote_and_summary_are_capped", n -> {
            StringBuilder words = new StringBuilder();
            for (int i = 0; i < 60; i++) {
                words.append("word").append(i).append(' ');
            }
            StringBuilder longSummary = new StringBuilder();
            for (int i = 0; i < 500; i++) {
                longSummary.append('a');
            }
            Feedback f = Feedback.of("suggestion", longSummary.toString(), words.toString());
            int quoteWords = f == null ? -1 : f.quote.split(" ").length;
            check(n, f != null && quoteWords == Feedback.MAX_QUOTE_WORDS && f.quote.length() <= Feedback.MAX_QUOTE_CHARS
                            && f.summary.length() == Feedback.MAX_SUMMARY_CHARS && f.quote.startsWith("word0 "),
                    "f=" + f + " words=" + quoteWords);
        });

        // ---- the launcher's log ----
        scenario("record_then_newest_first_with_every_field", n -> {
            FakeClock clock = new FakeClock();
            FeedbackStore log = new FeedbackStore(new File(tempDir(), FeedbackStore.FILE), clock);
            log.record(complaint("First."), "Sarah", "0123456789abcdef", "in a call's conversation");
            clock.now += 1000;
            log.record(Feedback.of("praise", "Second.", ""), FeedbackStore.SOMEONE, "", "while docked");
            List<FeedbackStore.Entry> all = log.newestFirst();
            FeedbackStore.Entry newest = all.get(0);
            FeedbackStore.Entry oldest = all.get(1);
            check(n, all.size() == 2 && log.size() == 2 && "Second.".equals(newest.summary) && "praise".equals(newest.kind)
                            && "someone".equals(newest.who) && "while docked".equals(newest.context)
                            && newest.atMillis == clock.now && "First.".equals(oldest.summary)
                            && "you keep bumping into my chair".equals(oldest.quote) && "Sarah".equals(oldest.who)
                            && oldest.atMillis == clock.now - 1000,
                    "all=" + summaries(all));
        });
        scenario("the_cap_drops_the_oldest", n -> {
            FakeClock clock = new FakeClock();
            File file = new File(tempDir(), FeedbackStore.FILE);
            FeedbackStore log = new FeedbackStore(file, clock);
            for (int i = 0; i < FeedbackStore.MAX_ENTRIES + 5; i++) {
                clock.now += 1;
                log.record(complaint("n" + i), FeedbackStore.SOMEONE, "", "x");
            }
            List<FeedbackStore.Entry> all = log.newestFirst();
            FeedbackStore reloaded = new FeedbackStore(file, clock);
            check(n, all.size() == FeedbackStore.MAX_ENTRIES && ("n" + (FeedbackStore.MAX_ENTRIES + 4)).equals(all.get(0).summary)
                            && "n5".equals(all.get(all.size() - 1).summary)
                            && reloaded.size() == FeedbackStore.MAX_ENTRIES
                            && "n5".equals(reloaded.newestFirst().get(FeedbackStore.MAX_ENTRIES - 1).summary),
                    "size=" + all.size() + " newest=" + all.get(0).summary + " oldest=" + all.get(all.size() - 1).summary);
        });
        scenario("the_log_survives_a_reload_and_skips_corrupt_lines", n -> {
            FakeClock clock = new FakeClock();
            File file = new File(tempDir(), FeedbackStore.FILE);
            FeedbackStore log = new FeedbackStore(file, clock);
            log.record(complaint("Kept."), "Tom", "0123456789abcdef", "while roaming");
            FileOutputStream out = new FileOutputStream(file, true);
            out.write(("not json\n{\"kind\":\"rant\",\"summary\":\"x\"}\n[1,2]\n"
                    + Json.write(fb("bug", "Also kept.", "q")) + "\n").getBytes(StandardCharsets.UTF_8));
            out.close();
            FeedbackStore reloaded = new FeedbackStore(file, clock);
            List<FeedbackStore.Entry> all = reloaded.newestFirst();
            check(n, all.size() == 2 && "Also kept.".equals(all.get(0).summary) && "someone".equals(all.get(0).who)
                            && "Kept.".equals(all.get(1).summary) && "Tom".equals(all.get(1).who)
                            && "0123456789abcdef".equals(all.get(1).personId)
                            && !new File(file.getParentFile(), FeedbackStore.FILE + ".tmp").exists(),
                    "all=" + summaries(all));
        });
        scenario("clear_empties_the_log_for_good", n -> {
            FakeClock clock = new FakeClock();
            File file = new File(tempDir(), FeedbackStore.FILE);
            FeedbackStore log = new FeedbackStore(file, clock);
            log.record(complaint("One."), FeedbackStore.SOMEONE, "", "x");
            log.record(complaint("Two."), FeedbackStore.SOMEONE, "", "x");
            int cleared = log.clear();
            check(n, cleared == 2 && log.size() == 0 && new FeedbackStore(file, clock).size() == 0, "cleared=" + cleared);
        });
        scenario("a_refused_entry_writes_nothing", n -> {
            FakeClock clock = new FakeClock();
            FeedbackStore log = new FeedbackStore(new File(tempDir(), FeedbackStore.FILE), clock);
            boolean nullOk = log.record(null, FeedbackStore.SOMEONE, "", "x");
            check(n, !nullOk && log.size() == 0, "nullOk=" + nullOk);
        });
        scenario("context_is_cleaned_and_capped", n -> {
            FakeClock clock = new FakeClock();
            FeedbackStore log = new FeedbackStore(new File(tempDir(), FeedbackStore.FILE), clock);
            StringBuilder longCtx = new StringBuilder("while\ndocked ");
            for (int i = 0; i < 100; i++) {
                longCtx.append('z');
            }
            log.record(complaint("C."), FeedbackStore.SOMEONE, "", longCtx.toString());
            log.record(complaint("D."), FeedbackStore.SOMEONE, "", null);
            List<FeedbackStore.Entry> all = log.newestFirst();
            check(n, all.get(1).context.startsWith("while docked z") && all.get(1).context.length() <= Feedback.MAX_CONTEXT_CHARS
                            && "".equals(all.get(0).context),
                    "ctx=" + all.get(1).context);
        });

        // ---- PeopleStore's side: who, and forget ----
        scenario("an_unknown_or_unnamed_person_is_someone", n -> {
            FakeClock clock = new FakeClock();
            PeopleStore s = new PeopleStore(tempDir(), clock);
            s.recordFeedback(null, complaint("A."), "x");
            s.recordFeedback("0123456789abcdef", complaint("B."), "x");
            s.recordFeedback("../../etc", complaint("C."), "x");
            List<FeedbackStore.Entry> all = s.feedback().newestFirst();
            boolean someone = true;
            for (FeedbackStore.Entry e : all) {
                someone &= "someone".equals(e.who) && "".equals(e.personId);
            }
            check(n, all.size() == 3 && someone, "all=" + summaries(all));
        });
        scenario("a_known_named_person_gives_their_first_name_only", n -> {
            FakeClock clock = new FakeClock();
            PeopleStore s = new PeopleStore(tempDir(), clock);
            String id = s.add(jpeg(1), "Sarah  Connor");
            s.recordFeedback(id, complaint("A."), "in a call's conversation");
            FeedbackStore.Entry e = s.feedback().newestFirst().get(0);
            check(n, "Sarah".equals(e.who) && id.equals(e.personId), "who=" + e.who);
        });
        scenario("forget_deletes_that_persons_entries_and_keeps_the_rest", n -> {
            FakeClock clock = new FakeClock();
            File dir = tempDir();
            PeopleStore s = new PeopleStore(dir, clock);
            String sarah = s.add(jpeg(1), "Sarah");
            String tom = s.add(jpeg(2), "Tom");
            s.recordFeedback(sarah, complaint("Hers."), "x");
            s.recordFeedback(tom, complaint("His."), "x");
            s.recordFeedback(null, complaint("Anyone's."), "x");
            s.forget(sarah);
            List<FeedbackStore.Entry> all = s.feedback().newestFirst();
            List<FeedbackStore.Entry> reloaded = new PeopleStore(dir, clock).feedback().newestFirst();
            check(n, "Anyone's.,His.".equals(summaries(all)) && "Anyone's.,His.".equals(summaries(reloaded)),
                    "all=" + summaries(all) + " reloaded=" + summaries(reloaded));
        });
        scenario("the_people_stores_orphan_sweep_keeps_the_log", n -> {
            FakeClock clock = new FakeClock();
            File dir = tempDir();
            PeopleStore s = new PeopleStore(dir, clock);
            s.add(jpeg(1), "Ann");
            s.recordFeedback(null, complaint("Stays."), "x");
            PeopleStore again = new PeopleStore(dir, clock);
            check(n, again.feedback().size() == 1 && new File(dir, FeedbackStore.FILE).isFile(),
                    "size=" + again.feedback().size());
        });

        System.exit(failures == 0 ? 0 : 1);
    }
}
