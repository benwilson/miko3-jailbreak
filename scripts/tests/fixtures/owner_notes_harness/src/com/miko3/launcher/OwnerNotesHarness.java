package com.miko3.launcher;

import com.miko3.shared.OwnerNotes;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Host-JVM checks for the owner's notes about people by name (owner 2026-10-03),
 * driven by scripts/tests/test_owner_notes.py: the shared OwnerNotes value
 * (caps, JSON, add/edit/delete, the name match) and the launcher's
 * OwnerNotesStore (app-private file, atomic write, reload).
 *
 * Prints one "PASS <name>" or "FAIL <name>: <detail>" line per scenario.
 */
public final class OwnerNotesHarness {
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
        File d = File.createTempFile("owner_notes_", "");
        d.delete();
        d.mkdirs();
        d.deleteOnExit();
        return d;
    }

    static String repeat(char c, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) {
            b.append(c);
        }
        return b.toString();
    }

    static String refusal(OwnerNotes notes, String name, String note) {
        try {
            notes.add(name, note);
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    static OwnerNotes team() {
        OwnerNotes n = new OwnerNotes();
        n.add("Priya Shah", "priya-shah");
        n.add("Sam", "sam");
        n.add("Alex Kim", "alex-kim");
        n.add("Alex Ng", "alex-ng");
        return n;
    }

    public static void main(String[] args) {
        scenario("add_keeps_name_and_note_trimmed_with_a_stable_id", n -> {
            OwnerNotes notes = new OwnerNotes();
            String a = notes.add("  Priya   Shah ", "  Ask about her\n marathon. ");
            String b = notes.add("Sam", "Likes puns.");
            List<OwnerNotes.Entry> all = notes.all();
            check(n, a != null && b != null && !a.equals(b) && all.size() == 2
                            && "Priya Shah".equals(all.get(0).name) && "Ask about her\n marathon.".equals(all.get(0).note)
                            && a.equals(all.get(0).id) && OwnerNotes.isValidId(a),
                    "a=" + a + " b=" + b + " size=" + all.size());
        });
        scenario("a_blank_name_or_note_is_refused", n -> {
            OwnerNotes notes = new OwnerNotes();
            String r1 = refusal(notes, "  ", "x");
            String r2 = refusal(notes, null, "x");
            String r3 = refusal(notes, "Sam", "   ");
            String r4 = refusal(notes, "Sam", null);
            check(n, OwnerNotes.REFUSE_NAME.equals(r1) && OwnerNotes.REFUSE_NAME.equals(r2)
                            && OwnerNotes.REFUSE_NOTE_EMPTY.equals(r3) && OwnerNotes.REFUSE_NOTE_EMPTY.equals(r4)
                            && notes.all().isEmpty(), r1 + "|" + r2 + "|" + r3 + "|" + r4);
        });
        scenario("a_note_over_600_chars_or_a_name_over_80_is_refused", n -> {
            OwnerNotes notes = new OwnerNotes();
            String ok = notes.add("Sam", repeat('a', 600));
            String r1 = refusal(notes, "Sam", repeat('a', 601));
            String r2 = refusal(notes, repeat('b', 81), "x");
            String ok2 = notes.add(repeat('b', 80), "x");
            check(n, OwnerNotes.MAX_NOTE_CHARS == 600 && ok != null && ok2 != null
                            && OwnerNotes.REFUSE_NOTE_TOO_LONG.equals(r1) && OwnerNotes.REFUSE_NAME.equals(r2)
                            && notes.all().size() == 2, r1 + "|" + r2);
        });
        scenario("edit_changes_one_entry_and_unknown_id_is_false", n -> {
            OwnerNotes notes = new OwnerNotes();
            String a = notes.add("Sam", "old");
            String b = notes.add("Ann", "keep");
            boolean edited = notes.edit(a, "Sam Lee", "new");
            boolean unknown = notes.edit("999", "X", "y");
            String refused = null;
            try {
                notes.edit(a, "Sam Lee", repeat('z', 601));
            } catch (IllegalArgumentException e) {
                refused = e.getMessage();
            }
            OwnerNotes.Entry e = notes.get(a);
            check(n, edited && !unknown && e != null && "Sam Lee".equals(e.name) && "new".equals(e.note)
                            && "keep".equals(notes.get(b).note) && OwnerNotes.REFUSE_NOTE_TOO_LONG.equals(refused),
                    "edited=" + edited + " unknown=" + unknown);
        });
        scenario("delete_removes_one_entry_and_unknown_id_is_false", n -> {
            OwnerNotes notes = new OwnerNotes();
            String a = notes.add("Sam", "x");
            String b = notes.add("Ann", "y");
            boolean del = notes.delete(a);
            boolean again = notes.delete(a);
            String c = notes.add("Bo", "z");
            check(n, del && !again && notes.all().size() == 2 && notes.get(b) != null && notes.get(a) == null
                            && !a.equals(c), "c=" + c);
        });
        scenario("json_round_trip_keeps_entries_and_ids", n -> {
            OwnerNotes notes = team();
            String del = notes.all().get(1).id;
            notes.delete(del);
            OwnerNotes back = OwnerNotes.fromJson(notes.toJson());
            String fresh = back.add("New", "n");
            boolean same = back.all().size() == 4;
            for (int i = 0; i < 3 && same; i++) {
                OwnerNotes.Entry x = notes.all().get(i);
                OwnerNotes.Entry y = back.all().get(i);
                same = x.id.equals(y.id) && x.name.equals(y.name) && x.note.equals(y.note);
            }
            boolean freshIsNew = true;
            for (OwnerNotes.Entry e : notes.all()) {
                freshIsNew &= !e.id.equals(fresh);
            }
            check(n, same && freshIsNew && !fresh.equals(del), notes.toJson());
        });
        scenario("bad_json_reads_as_no_notes_and_bad_entries_are_skipped", n -> {
            boolean empty = OwnerNotes.fromJson(null).all().isEmpty() && OwnerNotes.fromJson("{nope").all().isEmpty()
                    && OwnerNotes.fromJson("[]").all().isEmpty();
            OwnerNotes mixed = OwnerNotes.fromJson("{\"next\":5,\"entries\":[{\"id\":\"1\",\"name\":\"Sam\",\"note\":\"ok\"},"
                    + "{\"id\":\"2\",\"name\":\"\",\"note\":\"x\"},{\"id\":\"3\",\"name\":\"Bo\",\"note\":\""
                    + repeat('a', 601) + "\"},{\"id\":\"bad id\",\"name\":\"C\",\"note\":\"x\"},7]}");
            check(n, empty && mixed.all().size() == 1 && "Sam".equals(mixed.all().get(0).name),
                    "size=" + mixed.all().size());
        });
        scenario("match_is_case_insensitive_and_whitespace_collapsed", n -> {
            OwnerNotes t = team();
            check(n, "priya-shah".equals(t.noteFor("  PRIYA   shah ")) && "sam".equals(t.noteFor("sam")),
                    t.noteFor("  PRIYA   shah "));
        });
        scenario("full_name_match_wins_over_first_name", n -> {
            OwnerNotes t = team();
            check(n, "alex-kim".equals(t.noteFor("Alex Kim")) && "alex-ng".equals(t.noteFor("alex ng")),
                    t.noteFor("Alex Kim"));
        });
        scenario("first_name_matches_a_unique_entry", n -> {
            OwnerNotes t = team();
            check(n, "priya-shah".equals(t.noteFor("Priya")), t.noteFor("Priya"));
        });
        scenario("first_name_shared_by_two_entries_matches_nothing", n -> {
            OwnerNotes t = team();
            check(n, t.noteFor("Alex") == null && t.noteFor("Alex Smith") == null, t.noteFor("Alex"));
        });
        scenario("full_name_asked_matches_a_unique_first_name_entry", n -> {
            OwnerNotes t = team();
            check(n, "sam".equals(t.noteFor("Sam Lee")), t.noteFor("Sam Lee"));
        });
        scenario("different_last_names_never_match", n -> {
            OwnerNotes t = team();
            check(n, t.noteFor("Priya Jones") == null, t.noteFor("Priya Jones"));
        });
        scenario("unknown_or_blank_name_is_null", n -> {
            OwnerNotes t = team();
            check(n, t.noteFor("Zed") == null && t.noteFor("") == null && t.noteFor("   ") == null
                    && t.noteFor(null) == null, "");
        });
        scenario("store_persists_atomically_and_reloads", n -> {
            File dir = tempDir();
            File f = new File(dir, OwnerNotesStore.FILE);
            OwnerNotesStore s = new OwnerNotesStore(f);
            String a = s.add("Priya Shah", "Ask about the marathon.");
            String b = s.add("Sam", "x");
            boolean edited = s.edit(b, "Sam", "Likes puns.");
            boolean deleted = s.delete(a);
            OwnerNotesStore again = new OwnerNotesStore(f);
            check(n, edited && deleted && f.isFile() && !new File(dir, OwnerNotesStore.FILE + ".tmp").exists()
                            && again.all().size() == 1 && "Likes puns.".equals(again.noteFor("sam"))
                            && again.noteFor("Priya") == null,
                    "size=" + again.all().size());
        });
        scenario("store_refusal_writes_nothing", n -> {
            File dir = tempDir();
            File f = new File(dir, OwnerNotesStore.FILE);
            OwnerNotesStore s = new OwnerNotesStore(f);
            String refused = null;
            try {
                s.add("Sam", repeat('a', 601));
            } catch (IllegalArgumentException e) {
                refused = e.getMessage();
            }
            FileOutputStream os = new FileOutputStream(new File(dir, "other"));
            os.write("x".getBytes(StandardCharsets.UTF_8));
            os.close();
            check(n, OwnerNotes.REFUSE_NOTE_TOO_LONG.equals(refused) && !f.exists() && s.all().isEmpty(),
                    "refused=" + refused);
        });
        scenario("store_lives_beside_the_people_store", n -> {
            File dir = tempDir();
            PeopleStore p = new PeopleStore(dir, () -> 1_700_000_000_000L);
            p.ownerNotes().add("Ann", "Quiet mornings.");
            PeopleStore again = new PeopleStore(dir, () -> 1_700_000_000_000L);
            check(n, "Quiet mornings.".equals(again.ownerNotes().noteFor("ann"))
                    && new File(dir, OwnerNotesStore.FILE).isFile(), "");
        });

        System.exit(failures == 0 ? 0 : 1);
    }
}
