package com.miko3.launcher;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
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
                List<String> got = ids(s.recent(10));
                check(n, got.equals(Arrays.asList(c, b, a)) && !a.equals(b), got.toString());
            }
        });

        scenario("recent_is_capped_at_n", new Scenario() {
            public void run(String n) throws Exception {
                PeopleStore s = new PeopleStore(tempDir(), new FakeClock());
                List<String> added = new ArrayList<String>();
                for (int i = 0; i < 12; i++) {
                    added.add(s.add(jpeg(i), null));
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

        if (failures > 0) {
            System.exit(1);
        }
    }
}
