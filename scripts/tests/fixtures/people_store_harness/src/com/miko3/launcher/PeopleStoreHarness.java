package com.miko3.launcher;

import com.miko3.shared.PersonNotes;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Host-JVM checks for the launcher's people store (explore-on-claude plan
 * U2; R13, R14, R16, AE6), driven by scripts/tests/test_people_store.py.
 * Runs the plain-Java PeopleStore over a temporary directory, the same
 * java.io.File code the launcher runs over its private files dir, and the
 * CallerCheck that PeopleService applies (through CallerGate) before any call.
 *
 * Prints one "PASS <name>" or "FAIL <name>: <detail>" line per scenario.
 */
public final class PeopleStoreHarness {
    static final class FakeClock implements PeopleStore.Clock {
        long now = 1_700_000_000_000L;

        @Override
        public long nowMillis() {
            return now;
        }
    }

    /** A small valid-looking JPEG: SOI marker, a tag byte, EOI marker. */
    static byte[] jpeg(int tag) {
        return new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) tag, 1, 2, 3, (byte) 0xFF, (byte) 0xD9};
    }

    /** Delivers the wrapped stream's bytes through the first newline, then
     * fails every read after it: an index read that dies partway through. */
    static final class FailAfterFirstLine extends InputStream {
        private final InputStream in;
        private boolean lineDelivered;

        FailAfterFirstLine(InputStream in) {
            this.in = in;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (lineDelivered) {
                throw new IOException("index read failed partway");
            }
            int n = 0;
            while (n < len) {
                int c = in.read();
                if (c < 0) {
                    break;
                }
                b[off + n++] = (byte) c;
                if (c == '\n') {
                    lineDelivered = true;
                    break;
                }
            }
            return n == 0 && len > 0 ? -1 : n;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }

    static List<String> sortedFiles(File dir) {
        List<String> names = new ArrayList<String>(Arrays.asList(dir.list()));
        Collections.sort(names);
        return names;
    }

    static File tempDir() throws Exception {
        File d = File.createTempFile("people_store_", "");
        if (!d.delete() || !d.mkdirs()) {
            throw new IllegalStateException("no temp dir");
        }
        return d;
    }

    static List<String> ids(List<PeopleStore.Person> people) {
        List<String> ids = new ArrayList<String>();
        for (PeopleStore.Person p : people) {
            ids.add(p.id);
        }
        return ids;
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

    static String refusal(PeopleStore s, byte[] face, String name) {
        try {
            s.add(face, name);
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    public static void main(String[] args) {
        scenario("add_then_recent_newest_first", new Scenario() {
            public void run(String n) throws Exception {
                FakeClock clock = new FakeClock();
                PeopleStore s = new PeopleStore(tempDir(), clock);
                String a = s.add(jpeg(1), "Ann");
                clock.now += 1000;
                String b = s.add(jpeg(2), null);
                clock.now += 1000;
                String c = s.add(jpeg(3), "Cy");
                // The gallery skips the nameless record (KTD10); the store still lists it.
                List<String> got = ids(s.recent(10));
                check(n, got.equals(Arrays.asList(c, a)) && ids(s.all()).equals(Arrays.asList(c, b, a)) && !a.equals(b),
                        got.toString());
            }
        });

        scenario("recent_is_capped_at_n", new Scenario() {
            public void run(String n) throws Exception {
                PeopleStore s = new PeopleStore(tempDir(), new FakeClock());
                List<String> added = new ArrayList<String>();
                for (int i = 0; i < 12; i++) {
                    added.add(s.add(jpeg(i), "Person " + i));
                }
                List<String> got = ids(s.recent(10));
                boolean newestTen = got.size() == 10 && got.get(0).equals(added.get(11))
                        && got.get(9).equals(added.get(2));
                check(n, newestTen && s.recent(0).isEmpty() && s.recent(-3).isEmpty()
                        && s.all().size() == 12, got.toString());
            }
        });

        scenario("touch_moves_person_to_front", new Scenario() {
            public void run(String n) throws Exception {
                FakeClock clock = new FakeClock();
                PeopleStore s = new PeopleStore(tempDir(), clock);
                String a = s.add(jpeg(1), "Ann");
                String b = s.add(jpeg(2), "Bo");
                clock.now += 60_000;
                boolean touched = s.touch(a);
                List<PeopleStore.Person> got = s.recent(10);
                check(n, touched && got.get(0).id.equals(a) && got.get(1).id.equals(b)
                        && got.get(0).lastSeenMillis == clock.now && !s.touch("0123456789abcdef"),
                        ids(got).toString());
            }
        });

        scenario("face_bytes_round_trip", new Scenario() {
            public void run(String n) throws Exception {
                PeopleStore s = new PeopleStore(tempDir(), new FakeClock());
                String a = s.add(jpeg(7), null);
                check(n, Arrays.equals(s.face(a), jpeg(7)), Arrays.toString(s.face(a)));
            }
        });

        scenario("rename_changes_name_used_next_time", new Scenario() {
            public void run(String n) throws Exception {
                PeopleStore s = new PeopleStore(tempDir(), new FakeClock());
                String a = s.add(jpeg(1), null);
                boolean unnamedFirst = "".equals(s.nameOf(a));
                boolean ok = s.rename(a, "  Sarah  ");
                check(n, unnamedFirst && ok && "Sarah".equals(s.nameOf(a)), s.nameOf(a));
            }
        });

        scenario("rename_empty_makes_person_unnamed", new Scenario() {
            public void run(String n) throws Exception {
                PeopleStore s = new PeopleStore(tempDir(), new FakeClock());
                String a = s.add(jpeg(1), "Sarah");
                boolean ok = s.rename(a, "   ") && "".equals(s.nameOf(a));
                boolean ok2 = s.rename(a, "Sarah") && s.rename(a, null) && "".equals(s.nameOf(a));
                check(n, ok && ok2, s.nameOf(a));
            }
        });

        scenario("name_is_trimmed_capped_and_cleaned", new Scenario() {
            public void run(String n) throws Exception {
                PeopleStore s = new PeopleStore(tempDir(), new FakeClock());
                StringBuilder longName = new StringBuilder();
                for (int i = 0; i < 100; i++) {
                    longName.append('x');
                }
                String a = s.add(jpeg(1), longName.toString());
                String b = s.add(jpeg(2), "Ann\tMarie\nLee\r");
                String nameA = s.nameOf(a);
                String nameB = s.nameOf(b);
                check(n, nameA.length() == PeopleStore.MAX_NAME_CHARS && "Ann Marie Lee".equals(nameB),
                        nameA.length() + " '" + nameB + "'");
            }
        });

        scenario("unknown_person_has_no_name", new Scenario() {
            public void run(String n) throws Exception {
                PeopleStore s = new PeopleStore(tempDir(), new FakeClock());
                check(n, s.nameOf("0123456789abcdef") == null && s.nameOf(null) == null
                        && !s.rename("0123456789abcdef", "X"), "found someone");
            }
        });

        // AE6: Forget deletes the face file and the entry for good.
        scenario("forget_deletes_file_and_entry", new Scenario() {
            public void run(String n) throws Exception {
                File dir = tempDir();
                FakeClock clock = new FakeClock();
                PeopleStore s = new PeopleStore(dir, clock);
                String sarah = s.add(jpeg(1), "Sarah");
                String bo = s.add(jpeg(2), "Bo");
                File file = new File(dir, sarah + ".jpg");
                boolean fileBefore = file.exists();
                boolean forgot = s.forget(sarah);
                PeopleStore reloaded = new PeopleStore(dir, clock);
                boolean gone = !file.exists() && s.nameOf(sarah) == null && s.face(sarah) == null
                        && !ids(s.recent(10)).contains(sarah) && !s.touch(sarah)
                        && reloaded.nameOf(sarah) == null && !ids(reloaded.all()).contains(sarah);
                boolean othersKept = "Bo".equals(reloaded.nameOf(bo)) && !s.forget(sarah);
                check(n, fileBefore && forgot && gone && othersKept,
                        "before=" + fileBefore + " forgot=" + forgot + " gone=" + gone + " kept=" + othersKept);
            }
        });

        scenario("index_survives_reload", new Scenario() {
            public void run(String n) throws Exception {
                File dir = tempDir();
                FakeClock clock = new FakeClock();
                PeopleStore s = new PeopleStore(dir, clock);
                String a = s.add(jpeg(1), "Ann");
                clock.now += 5000;
                String b = s.add(jpeg(2), null);
                clock.now += 5000;
                s.touch(a);
                s.rename(b, "Bo");
                PeopleStore r = new PeopleStore(dir, clock);
                List<PeopleStore.Person> got = r.all();
                check(n, ids(got).equals(Arrays.asList(a, b)) && "Ann".equals(got.get(0).name)
                                && "Bo".equals(got.get(1).name) && got.get(0).lastSeenMillis == clock.now
                                && Arrays.equals(r.face(b), jpeg(2)),
                        ids(got).toString());
            }
        });

        scenario("index_write_leaves_no_temp_file", new Scenario() {
            public void run(String n) throws Exception {
                File dir = tempDir();
                PeopleStore s = new PeopleStore(dir, new FakeClock());
                String a = s.add(jpeg(1), "Ann");
                s.rename(a, "Bo");
                List<String> names = Arrays.asList(dir.list());
                boolean temp = false;
                for (String f : names) {
                    temp |= f.endsWith(".tmp");
                }
                check(n, !temp && names.contains(PeopleStore.INDEX_FILE), names.toString());
            }
        });

        scenario("corrupt_index_lines_are_skipped", new Scenario() {
            public void run(String n) throws Exception {
                File dir = tempDir();
                FakeClock clock = new FakeClock();
                PeopleStore s = new PeopleStore(dir, clock);
                String a = s.add(jpeg(1), "Ann");
                java.io.FileOutputStream out = new java.io.FileOutputStream(new File(dir, PeopleStore.INDEX_FILE), true);
                out.write(("garbage\n../../etc/passwd\t1\tEve\nfedcba9876543210\t1\tNoFace\n")
                        .getBytes("UTF-8"));
                out.close();
                PeopleStore r = new PeopleStore(dir, clock);
                check(n, ids(r.all()).equals(Arrays.asList(a)), ids(r.all()).toString());
            }
        });

        scenario("add_refuses_non_jpeg_empty_and_oversized", new Scenario() {
            public void run(String n) throws Exception {
                File dir = tempDir();
                PeopleStore s = new PeopleStore(dir, new FakeClock());
                byte[] big = new byte[PeopleStore.MAX_FACE_BYTES + 1];
                big[0] = (byte) 0xFF;
                big[1] = (byte) 0xD8;
                String r1 = refusal(s, null, "A");
                String r2 = refusal(s, new byte[0], "A");
                String r3 = refusal(s, new byte[] {'P', 'N', 'G', 0}, "A");
                String r4 = refusal(s, big, "A");
                check(n, PeopleStore.REFUSE_NOT_JPEG.equals(r1) && PeopleStore.REFUSE_NOT_JPEG.equals(r2)
                                && PeopleStore.REFUSE_NOT_JPEG.equals(r3) && PeopleStore.REFUSE_TOO_BIG.equals(r4)
                                && s.all().isEmpty() && dir.list().length <= 1,
                        r1 + "|" + r2 + "|" + r3 + "|" + r4 + " files=" + Arrays.toString(dir.list()));
            }
        });

        // The face GET takes an id from the URL: only 16 lower-case hex digits
        // ever reach the file system.
        scenario("ids_are_validated_before_any_file_access", new Scenario() {
            public void run(String n) throws Exception {
                File dir = tempDir();
                File secret = new File(dir.getParentFile(), dir.getName() + "-secret.jpg");
                java.io.FileOutputStream out = new java.io.FileOutputStream(secret);
                out.write(jpeg(9));
                out.close();
                PeopleStore s = new PeopleStore(dir, new FakeClock());
                String a = s.add(jpeg(1), null);
                List<String> leaked = new ArrayList<String>();
                for (String bad : Arrays.asList("../" + dir.getName() + "-secret", "..", "", null,
                        a.toUpperCase(), a + "0", a.substring(1), a + "/", "index", PeopleStore.INDEX_FILE)) {
                    if (s.face(bad) != null || PeopleStore.isValidId(bad)) {
                        leaked.add(String.valueOf(bad));
                    }
                }
                secret.delete();
                check(n, leaked.isEmpty() && PeopleStore.isValidId(a), leaked.toString());
            }
        });

        scenario("unpinned_caller_is_denied", new Scenario() {
            public void run(String n) throws Exception {
                // PeopleService runs the same CallerCheck (through CallerGate)
                // before it touches the store.
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

        // ---- Notes (meeting plan U5; KTD10, R17, R18, R19) ----

        scenario("merge_adds_interests_and_closes_a_thread", new Scenario() {
            public void run(String n) throws Exception {
                FakeClock clock = new FakeClock();
                PeopleStore s = new PeopleStore(tempDir(), clock);
                String id = s.add(jpeg(1), "Ann");
                long opened = clock.now;
                PersonNotes first = s.mergeNotes(id,
                        "{\"open_threads\":[\"the Q3 launch\"],\"interests\":[\"climbing\"]}");
                clock.now += 60_000;
                PersonNotes second = s.mergeNotes(id, "{\"interests\":[\"chess\",\"baking\"],"
                        + "\"closed_threads\":[\"the Q3 launch\"],\"questions_asked\":[\"How was the launch?\"]}");
                boolean firstOk = first.openThreads.size() == 1 && "the Q3 launch".equals(first.openThreads.get(0).text)
                        && first.openThreads.get(0).sinceMillis == opened && first.topics.isEmpty();
                boolean secondOk = second.interests.equals(Arrays.asList("climbing", "chess", "baking"))
                        && second.openThreads.isEmpty() && second.topics.equals(Arrays.asList("the Q3 launch"))
                        && second.questionsAsked.equals(Arrays.asList("How was the launch?"))
                        && second.hasAsked("how was the launch") && !second.hasAsked("What do you do?");
                check(n, firstOk && secondOk, "first=" + first.toJson() + " second=" + second.toJson());
            }
        });

        scenario("merge_is_idempotent", new Scenario() {
            public void run(String n) throws Exception {
                FakeClock clock = new FakeClock();
                PeopleStore s = new PeopleStore(tempDir(), clock);
                String id = s.add(jpeg(1), "Ann");
                String delta = "{\"interests\":[\"chess\",\"Baking\"],\"open_threads\":[\"the Q3 launch\"],"
                        + "\"topics\":[\"weather\"],\"questions_asked\":[\"What's your name?\"]}";
                String once = s.mergeNotes(id, delta).toJson();
                clock.now += 60_000;
                String twice = s.mergeNotes(id, delta).toJson();
                // Same entries spelt differently are the same entries.
                String again = s.mergeNotes(id, "{\"interests\":[\" baking! \"],\"open_threads\":[\"The Q3 Launch\"],"
                        + "\"questions_asked\":[\"whats your name\"]}").toJson();
                check(n, once.equals(twice) && once.equals(again) && once.equals(s.notes(id).toJson()),
                        "once=" + once + " twice=" + twice + " again=" + again);
            }
        });

        scenario("merge_past_caps_trims_in_named_order", new Scenario() {
            public void run(String n) throws Exception {
                FakeClock clock = new FakeClock();
                PeopleStore s = new PeopleStore(tempDir(), clock);
                String id = s.add(jpeg(1), "Ann");
                // Interests, topics and questions drop the oldest first.
                for (int i = 0; i < PersonNotes.MAX_INTERESTS + 2; i++) {
                    s.mergeNotes(id, "{\"interests\":[\"interest " + i + "\"],\"topics\":[\"topic " + i + "\"],"
                            + "\"questions_asked\":[\"question " + i + "\"]}");
                    clock.now += 1000;
                }
                PersonNotes a = s.notes(id);
                boolean interests = a.interests.size() == PersonNotes.MAX_INTERESTS
                        && a.interests.get(0).equals("interest 2")
                        && a.interests.get(PersonNotes.MAX_INTERESTS - 1).equals("interest " + (PersonNotes.MAX_INTERESTS + 1));
                boolean topics = a.topics.size() == PersonNotes.MAX_INTERESTS + 2;
                boolean questions = a.questionsAsked.size() == PersonNotes.MAX_INTERESTS + 2;
                for (int i = 0; i < PersonNotes.MAX_TOPICS + 1; i++) {
                    s.mergeNotes(id, "{\"topics\":[\"late topic " + i + "\"]}");
                }
                PersonNotes b = s.notes(id);
                boolean topicsCapped = b.topics.size() == PersonNotes.MAX_TOPICS
                        && b.topics.get(0).equals("late topic 1") && !b.topics.contains("topic 0");
                // Open threads: closed first, then the oldest.
                for (int i = 1; i <= PersonNotes.MAX_OPEN_THREADS; i++) {
                    s.mergeNotes(id, "{\"open_threads\":[\"thread " + i + "\"]}");
                    clock.now += 1000;
                }
                PersonNotes c = s.mergeNotes(id, "{\"open_threads\":[\"new 1\",\"new 2\"],\"closed_threads\":[\"thread 3\"]}");
                List<String> open = new ArrayList<String>();
                for (PersonNotes.Thread t : c.openThreads) {
                    open.add(t.text);
                }
                boolean threads = open.equals(Arrays.asList("thread 2", "thread 4", "thread 5", "thread 6", "new 1", "new 2"))
                        && c.topics.contains("thread 3");
                check(n, interests && topics && questions && topicsCapped && threads,
                        "a=" + a.toJson() + " b=" + b.toJson() + " c=" + c.toJson());
            }
        });

        scenario("byte_cap_never_drops_a_question_before_an_interest", new Scenario() {
            public void run(String n) throws Exception {
                PeopleStore s = new PeopleStore(tempDir(), new FakeClock());
                String id = s.add(jpeg(1), "Ann");
                StringBuilder qs = new StringBuilder();
                int count = 50;
                for (int i = 0; i < count; i++) {
                    String q = "question " + i + " ";
                    while (q.length() < PersonNotes.MAX_ENTRY_CHARS - 1) {
                        q += "x";
                    }
                    qs.append(i > 0 ? "," : "").append('"').append(q).append('"');
                }
                PersonNotes notes = s.mergeNotes(id, "{\"interests\":[\"chess\",\"baking\",\"climbing\"],"
                        + "\"topics\":[\"weather\"],\"open_threads\":[\"the Q3 launch\"],\"questions_asked\":[" + qs + "]}");
                int bytes = notes.toJson().getBytes("UTF-8").length;
                boolean underCap = bytes <= PersonNotes.MAX_DOCUMENT_BYTES;
                boolean questionsLast = !notes.questionsAsked.isEmpty()
                        && (notes.questionsAsked.size() == count || notes.interests.isEmpty())
                        && notes.questionsAsked.get(notes.questionsAsked.size() - 1).startsWith("question " + (count - 1));
                // Something was dropped (the cap bound), and it was never a question while an interest remained.
                check(n, underCap && questionsLast && notes.questionsAsked.size() < count,
                        "bytes=" + bytes + " questions=" + notes.questionsAsked.size() + " interests=" + notes.interests);
            }
        });

        scenario("ten_conversations_of_questions_fit_under_the_cap", new Scenario() {
            public void run(String n) throws Exception {
                // Six open-ended questions a conversation, at the length he asks them.
                FakeClock clock = new FakeClock();
                PeopleStore s = new PeopleStore(tempDir(), clock);
                String id = s.add(jpeg(1), "Ann");
                String[] shapes = {"What are you working on this week?", "How did the %s meeting go?",
                        "Are you still into %s at the weekend?", "What did you make of the %s news?",
                        "Any plans for the %s holiday?", "Who else is on the %s project with you?"};
                List<String> asked = new ArrayList<String>();
                for (int c = 0; c < 10; c++) {
                    StringBuilder qs = new StringBuilder();
                    for (int q = 0; q < shapes.length; q++) {
                        String text = shapes[q].replace("%s", "number " + c);
                        if (q == 0) {
                            text = "Conversation " + c + ": " + text;
                        }
                        asked.add(text);
                        qs.append(q > 0 ? "," : "").append('"').append(text).append('"');
                    }
                    s.mergeNotes(id, "{\"interests\":[\"hobby " + c + "\",\"sport " + c + "\"],"
                            + "\"topics\":[\"topic " + c + "\",\"news " + c + "\"],"
                            + "\"open_threads\":[\"the number " + c + " project, due soon\"],"
                            + "\"questions_asked\":[" + qs + "]}");
                    clock.now += 86_400_000L;
                }
                PersonNotes notes = s.notes(id);
                int missing = 0;
                for (String q : asked) {
                    if (!notes.hasAsked(q)) {
                        missing++;
                    }
                }
                int bytes = notes.toJson().getBytes("UTF-8").length;
                check(n, missing == 0 && notes.questionsAsked.size() == asked.size()
                                && bytes <= PersonNotes.MAX_DOCUMENT_BYTES && notes.interests.size() == PersonNotes.MAX_INTERESTS
                                && notes.openThreads.size() == PersonNotes.MAX_OPEN_THREADS,
                        "missing=" + missing + " questions=" + notes.questionsAsked.size() + " bytes=" + bytes);
            }
        });

        scenario("bad_delta_is_refused_and_document_unchanged", new Scenario() {
            public void run(String n) throws Exception {
                File dir = tempDir();
                PeopleStore s = new PeopleStore(dir, new FakeClock());
                String id = s.add(jpeg(1), "Ann");
                String before = s.mergeNotes(id, "{\"interests\":[\"chess\"]}").toJson();
                long stamp = new File(dir, id + ".json").length();
                String longEntry = "";
                for (int i = 0; i < 200; i++) {
                    longEntry += "y";
                }
                String[][] bad = {
                        {"{\"mood\":[\"x\"]}", PersonNotes.REFUSE_UNKNOWN_FIELD},
                        {"{\"interests\":[1]}", PersonNotes.REFUSE_NOT_STRINGS},
                        {"{\"interests\":\"chess\"}", PersonNotes.REFUSE_NOT_STRINGS},
                        {"{\"topics\":[\"" + longEntry + "\"]}", PersonNotes.REFUSE_ENTRY_TOO_LONG},
                        {"{\"questions_asked\":[\"line\\none\"]}", PersonNotes.REFUSE_LINE_BREAK},
                        {"{\"open_threads\":[\"tab\\there\"]}", PersonNotes.REFUSE_LINE_BREAK},
                        {"[1,2]", PersonNotes.REFUSE_NOT_JSON},
                        {"{\"interests\":[\"a\"", PersonNotes.REFUSE_NOT_JSON},
                        {"", PersonNotes.REFUSE_NOT_JSON},
                        {null, PersonNotes.REFUSE_NOT_JSON},
                };
                StringBuilder wrong = new StringBuilder();
                for (String[] b : bad) {
                    String why;
                    try {
                        s.mergeNotes(id, b[0]);
                        why = "accepted";
                    } catch (IllegalArgumentException e) {
                        why = e.getMessage();
                    }
                    if (!b[1].equals(why)) {
                        wrong.append(b[0]).append(" -> ").append(why).append("; ");
                    }
                }
                boolean unchanged = before.equals(s.notes(id).toJson()) && new File(dir, id + ".json").length() == stamp
                        && before.equals(new PeopleStore(dir, new FakeClock()).notes(id).toJson());
                check(n, wrong.length() == 0 && unchanged, "wrong=" + wrong + " unchanged=" + unchanged);
            }
        });

        scenario("merge_for_unknown_id_is_refused", new Scenario() {
            public void run(String n) throws Exception {
                File dir = tempDir();
                PeopleStore s = new PeopleStore(dir, new FakeClock());
                s.add(jpeg(1), "Ann");
                StringBuilder wrong = new StringBuilder();
                for (String id : Arrays.asList("0123456789abcdef", null, "", "..", "../x")) {
                    try {
                        s.mergeNotes(id, "{\"interests\":[\"chess\"]}");
                        wrong.append(id).append(" accepted; ");
                    } catch (IllegalArgumentException e) {
                        if (!PeopleStore.REFUSE_UNKNOWN_PERSON.equals(e.getMessage())) {
                            wrong.append(id).append(" -> ").append(e.getMessage()).append("; ");
                        }
                    }
                }
                check(n, wrong.length() == 0 && dir.list().length == 2, wrong + " files=" + Arrays.toString(dir.list()));
            }
        });

        scenario("notes_survive_reload", new Scenario() {
            public void run(String n) throws Exception {
                File dir = tempDir();
                FakeClock clock = new FakeClock();
                PeopleStore s = new PeopleStore(dir, clock);
                String id = s.add(jpeg(1), "Ann");
                String saved = s.mergeNotes(id, "{\"interests\":[\"chess é\"],\"open_threads\":[\"the Q3 launch\"],"
                        + "\"topics\":[\"weather\"],\"questions_asked\":[\"How was the launch?\"]}").toJson();
                PersonNotes r = new PeopleStore(dir, clock).notes(id);
                check(n, saved.equals(r.toJson()) && r.openThreads.get(0).sinceMillis == clock.now,
                        "saved=" + saved + " reloaded=" + r.toJson());
            }
        });

        scenario("forget_removes_index_notes_and_face", new Scenario() {
            public void run(String n) throws Exception {
                File dir = tempDir();
                FakeClock clock = new FakeClock();
                PeopleStore s = new PeopleStore(dir, clock);
                String id = s.add(jpeg(1), "Ann");
                String bo = s.add(jpeg(2), "Bo");
                s.mergeNotes(id, "{\"interests\":[\"chess\"]}");
                s.mergeNotes(bo, "{\"interests\":[\"golf\"]}");
                File notes = new File(dir, id + ".json");
                File face = new File(dir, id + ".jpg");
                boolean before = notes.isFile() && face.isFile();
                boolean forgot = s.forget(id);
                PeopleStore r = new PeopleStore(dir, clock);
                boolean gone = !notes.exists() && !face.exists() && s.nameOf(id) == null && s.notes(id).isEmpty()
                        && r.nameOf(id) == null && r.notes(id).isEmpty() && !s.forget(id);
                boolean kept = "Bo".equals(r.nameOf(bo)) && r.notes(bo).interests.equals(Arrays.asList("golf"));
                check(n, before && forgot && gone && kept, "before=" + before + " forgot=" + forgot + " gone=" + gone
                        + " kept=" + kept + " files=" + Arrays.toString(dir.list()));
            }
        });

        scenario("orphans_after_a_crash_are_deleted_on_load", new Scenario() {
            public void run(String n) throws Exception {
                File dir = tempDir();
                FakeClock clock = new FakeClock();
                PeopleStore s = new PeopleStore(dir, clock);
                String ann = s.add(jpeg(1), "Ann");
                String bo = s.add(jpeg(2), "Bo");
                s.mergeNotes(ann, "{\"interests\":[\"chess\"]}");
                s.mergeNotes(bo, "{\"interests\":[\"golf\"]}");
                // A forget of Ann that crashed after the index rewrite: the index
                // no longer names her, but her notes and face are still on disk.
                java.io.FileOutputStream out = new java.io.FileOutputStream(new File(dir, PeopleStore.INDEX_FILE));
                out.write((bo + "\t" + clock.now + "\tBo\n").getBytes("UTF-8"));
                out.close();
                // And a face written by an add() that never reached the index.
                out = new java.io.FileOutputStream(new File(dir, "fedcba9876543210.jpg"));
                out.write(jpeg(3));
                out.close();
                PeopleStore r = new PeopleStore(dir, clock);
                List<String> files = Arrays.asList(dir.list());
                boolean swept = !files.contains(ann + ".json") && !files.contains(ann + ".jpg")
                        && !files.contains("fedcba9876543210.jpg");
                boolean kept = files.contains(bo + ".json") && files.contains(bo + ".jpg")
                        && ids(r.all()).equals(Arrays.asList(bo)) && r.notes(bo).interests.equals(Arrays.asList("golf"));
                check(n, swept && kept, "files=" + files);
            }
        });

        scenario("notes_for_unknown_id_are_empty", new Scenario() {
            public void run(String n) throws Exception {
                PeopleStore s = new PeopleStore(tempDir(), new FakeClock());
                String id = s.add(jpeg(1), "Ann");
                PersonNotes fresh = s.notes(id);
                check(n, s.notes("0123456789abcdef").isEmpty() && s.notes(null).isEmpty() && s.notes("..").isEmpty()
                                && fresh.isEmpty() && fresh.interests.isEmpty() && fresh.openThreads.isEmpty()
                                && PersonNotes.EMPTY.toJson().equals(fresh.toJson()),
                        "fresh=" + fresh.toJson());
            }
        });

        scenario("malformed_notes_file_loads_as_empty", new Scenario() {
            public void run(String n) throws Exception {
                File dir = tempDir();
                FakeClock clock = new FakeClock();
                PeopleStore s = new PeopleStore(dir, clock);
                String id = s.add(jpeg(1), "Ann");
                StringBuilder wrong = new StringBuilder();
                for (String junk : Arrays.asList("garbage", "[1,2]", "{\"interests\":\"x\"}", "{\"interests\":[1]}",
                        "{\"open_threads\":[\"bare string\"]}", "")) {
                    java.io.FileOutputStream out = new java.io.FileOutputStream(new File(dir, id + ".json"));
                    out.write(junk.getBytes("UTF-8"));
                    out.close();
                    PeopleStore r = new PeopleStore(dir, clock);
                    if (!r.notes(id).isEmpty()) {
                        wrong.append(junk).append("; ");
                    }
                }
                PeopleStore r = new PeopleStore(dir, clock);
                PersonNotes merged = r.mergeNotes(id, "{\"interests\":[\"chess\"]}");
                check(n, wrong.length() == 0 && merged.interests.equals(Arrays.asList("chess"))
                        && "Ann".equals(r.nameOf(id)), "wrong=" + wrong);
            }
        });

        scenario("gallery_excludes_nameless_records_store_still_lists_them", new Scenario() {
            public void run(String n) throws Exception {
                FakeClock clock = new FakeClock();
                PeopleStore s = new PeopleStore(tempDir(), clock);
                String ann = s.add(jpeg(1), "Ann");
                clock.now += 1000;
                String legacy = s.add(jpeg(2), null);
                boolean excluded = ids(s.recent(10)).equals(Arrays.asList(ann))
                        && ids(s.all()).equals(Arrays.asList(legacy, ann)) && "".equals(s.nameOf(legacy))
                        && s.face(legacy) != null;
                s.rename(legacy, "Bo");
                boolean backIn = ids(s.recent(10)).equals(Arrays.asList(legacy, ann));
                s.rename(legacy, "");
                boolean outAgain = ids(s.recent(1)).equals(Arrays.asList(ann));
                check(n, excluded && backIn && outAgain, "excluded=" + excluded + " backIn=" + backIn + " out=" + outAgain);
            }
        });

        scenario("entries_are_cleaned_and_matched_on_normalised_text", new Scenario() {
            public void run(String n) throws Exception {
                PeopleStore s = new PeopleStore(tempDir(), new FakeClock());
                String id = s.add(jpeg(1), "Ann");
                PersonNotes notes = s.mergeNotes(id, "{\"interests\":[\"  Rock   Climbing \",\"rock climbing!\",\"\",\"   \"],"
                        + "\"questions_asked\":[\"What's your name?\",\"whats your name\"]}");
                String at80 = "";
                for (int i = 0; i < PersonNotes.MAX_ENTRY_CHARS; i++) {
                    at80 += "z";
                }
                PersonNotes more = s.mergeNotes(id, "{\"topics\":[\"  " + at80 + "  \"]}");
                check(n, notes.interests.equals(Arrays.asList("Rock Climbing"))
                                && notes.questionsAsked.equals(Arrays.asList("What's your name?"))
                                && "rock climbing".equals(PersonNotes.normalize(" Rock-Climbing! "))
                                && more.topics.equals(Arrays.asList(at80)),
                        notes.toJson() + " " + more.topics.size());
            }
        });

        scenario("index_read_failure_skips_the_orphan_sweep", new Scenario() {
            public void run(String n) throws Exception {
                File dir = tempDir();
                PeopleStore s = new PeopleStore(dir, new FakeClock());
                String ann = s.add(jpeg(1), "Ann");
                s.mergeNotes(ann, "{\"interests\":[\"chess\"]}");
                String bob = s.add(jpeg(2), "Bob");
                s.mergeNotes(bob, "{\"interests\":[\"go\"]}");
                List<String> before = sortedFiles(dir);
                // The index read delivers its first line, then fails: one person is
                // loaded while the other's face and notes sit on disk, named only by
                // the part of the index that was never read.
                PeopleStore torn = new PeopleStore(dir, new FakeClock(), new PeopleStore.IndexOpener() {
                    @Override
                    public InputStream open(File index) throws IOException {
                        return new FailAfterFirstLine(new FileInputStream(index));
                    }
                });
                List<String> after = sortedFiles(dir);
                int wholeAgain = new PeopleStore(dir, new FakeClock()).all().size();
                check(n, before.size() == 5 && torn.all().size() == 1 && after.equals(before) && wholeAgain == 2,
                        "torn load saw " + torn.all().size() + ", clean reload saw " + wholeAgain
                                + ", before=" + before + " after=" + after);
            }
        });

        if (failures > 0) {
            System.exit(1);
        }
    }
}
