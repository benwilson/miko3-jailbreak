package com.miko3.launcher;

import com.miko3.shared.FaceCheck;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Host-JVM checks for the launcher's in-memory ring of recent face checks
 * (face plan U5, KTD8; R13, R14, R19), driven by
 * scripts/tests/test_launcher_settings.py. Lives in the launcher's package to
 * reach the package-private FaceChecks; compiles against launcher/src and
 * shared/src only (neither class touches android.*).
 *
 * Prints one "PASS <name>" or "FAIL <name>: <detail>" line per scenario.
 */
public final class FaceChecksHarness {
    static final String SARAH = "00000000000000aa";
    static final String TOM = "00000000000000bb";
    static final String NEW = "00000000000000cc";

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

    static FaceCheck match(int decision, String bestId, float score) {
        return new FaceCheck(decision, FaceCheck.REASON_NONE, jpeg(1), bestId, 2, 1_600_000_000_000L, score,
                "", 0f, false);
    }

    static FaceCheck rejected(int reason) {
        return new FaceCheck(FaceCheck.REJECTED, reason, jpeg(2), "", -1, 0, 0f, "", 0f, false);
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

    private static String refusal(FaceChecks checks, FaceCheck c) {
        try {
            checks.record(c);
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    public static void main(String[] args) {
        scenario("record_answers_distinct_handles_newest_first", new Scenario() {
            public void run(String n) {
                FakeClock clock = new FakeClock();
                FaceChecks checks = new FaceChecks(clock);
                long a = checks.record(match(FaceCheck.CONFIDENT, SARAH, 0.61f));
                clock.now += 1000;
                long b = checks.record(rejected(FaceCheck.TOO_DARK));
                List<FaceChecks.Entry> list = checks.list();
                check(n, a > 0 && b > 0 && a != b && list.size() == 2 && list.get(0).handle == b
                                && list.get(1).handle == a && list.get(1).atMillis == 1_700_000_000_000L
                                && list.get(0).outcome == FaceCheck.OUTCOME_PENDING
                                && SARAH.equals(list.get(1).check.bestId) && list.get(1).check.score == 0.61f,
                        "a=" + a + " b=" + b + " list=" + list);
            }
        });

        scenario("eleventh_check_evicts_the_oldest_and_its_handle_updates_nothing", new Scenario() {
            public void run(String n) {
                FaceChecks checks = new FaceChecks(new FakeClock());
                long first = checks.record(match(FaceCheck.CLOSE, SARAH, 0.4f));
                long last = 0;
                for (int i = 0; i < FaceChecks.CAPACITY; i++) {
                    last = checks.record(match(FaceCheck.WEAK, TOM, 0.1f));
                }
                boolean updated = checks.updateOutcome(first, FaceCheck.YES, null);
                boolean closed = checks.closeAsEnded(first);
                List<FaceChecks.Entry> list = checks.list();
                boolean anyFirst = false;
                for (FaceChecks.Entry e : list) {
                    anyFirst |= e.handle == first || e.outcome != FaceCheck.OUTCOME_PENDING;
                }
                check(n, FaceChecks.CAPACITY == 10 && list.size() == 10 && list.get(0).handle == last && !anyFirst
                                && !updated && !closed && checks.crop(first) == null,
                        "size=" + list.size() + " updated=" + updated + " closed=" + closed);
            }
        });

        scenario("update_outcome_sets_outcome_and_joined_id", new Scenario() {
            public void run(String n) {
                FaceChecks checks = new FaceChecks(new FakeClock());
                long h = checks.record(match(FaceCheck.CLOSE, SARAH, 0.4f));
                boolean ok = checks.updateOutcome(h, FaceCheck.JOINED, TOM);
                FaceChecks.Entry e = checks.list().get(0);
                check(n, ok && e.outcome == FaceCheck.JOINED && TOM.equals(e.joinedId), "entry=" + e);
            }
        });

        scenario("update_unknown_handle_is_a_no_op", new Scenario() {
            public void run(String n) {
                FaceChecks checks = new FaceChecks(new FakeClock());
                long h = checks.record(match(FaceCheck.CLOSE, SARAH, 0.4f));
                boolean a = checks.updateOutcome(h + 99, FaceCheck.YES, null);
                boolean b = checks.updateOutcome(0, FaceCheck.YES, null);
                boolean c = checks.closeAsEnded(-5);
                check(n, !a && !b && !c && checks.list().get(0).outcome == FaceCheck.OUTCOME_PENDING,
                        "a=" + a + " b=" + b + " c=" + c);
            }
        });

        scenario("close_as_ended_closes_only_pending_checks", new Scenario() {
            public void run(String n) {
                FaceChecks checks = new FaceChecks(new FakeClock());
                long open = checks.record(match(FaceCheck.CLOSE, SARAH, 0.4f));
                long answered = checks.record(match(FaceCheck.CLOSE, TOM, 0.4f));
                checks.updateOutcome(answered, FaceCheck.NO, null);
                boolean a = checks.closeAsEnded(open);
                boolean b = checks.closeAsEnded(answered);
                List<FaceChecks.Entry> list = checks.list();
                check(n, a && !b && list.get(1).outcome == FaceCheck.ENDED_WITHOUT_ANSWER
                        && list.get(0).outcome == FaceCheck.NO, "a=" + a + " b=" + b + " list=" + list);
            }
        });

        scenario("purge_removes_checks_matching_or_joining_the_person", new Scenario() {
            public void run(String n) {
                FaceChecks checks = new FaceChecks(new FakeClock());
                long matched = checks.record(match(FaceCheck.CONFIDENT, SARAH, 0.7f));
                long joined = checks.record(match(FaceCheck.CLOSE, TOM, 0.4f));
                checks.updateOutcome(joined, FaceCheck.JOINED, SARAH);
                long newPerson = checks.record(match(FaceCheck.WEAK, TOM, 0.1f));
                checks.updateOutcome(newPerson, FaceCheck.NEW_PERSON, NEW);
                long other = checks.record(match(FaceCheck.CONFIDENT, TOM, 0.8f));
                long rejectedCheck = checks.record(rejected(FaceCheck.TOO_BLURRY));
                int removed = checks.purgePerson(SARAH);
                List<Long> left = new ArrayList<Long>();
                for (FaceChecks.Entry e : checks.list()) {
                    left.add(e.handle);
                }
                boolean gone = checks.crop(matched) == null && checks.crop(joined) == null
                        && !checks.updateOutcome(matched, FaceCheck.YES, null);
                int removedNew = checks.purgePerson(NEW);
                check(n, removed == 2 && gone && left.equals(Arrays.asList(rejectedCheck, other, newPerson))
                                && removedNew == 1 && checks.list().size() == 2,
                        "removed=" + removed + " left=" + left + " removedNew=" + removedNew);
            }
        });

        scenario("crop_is_served_by_handle", new Scenario() {
            public void run(String n) {
                FaceChecks checks = new FaceChecks(new FakeClock());
                long h = checks.record(match(FaceCheck.CONFIDENT, SARAH, 0.7f));
                check(n, Arrays.equals(checks.crop(h), jpeg(1)), "crop differs");
            }
        });

        scenario("oversized_or_non_jpeg_crop_refused", new Scenario() {
            public void run(String n) {
                FaceChecks checks = new FaceChecks(new FakeClock());
                byte[] big = new byte[FaceCheck.MAX_CROP_BYTES + 1];
                big[0] = (byte) 0xFF;
                big[1] = (byte) 0xD8;
                String a = refusal(checks, new FaceCheck(FaceCheck.WEAK, 0, big, SARAH, 0, 1, 0.1f, "", 0f, false));
                String b = refusal(checks, new FaceCheck(FaceCheck.WEAK, 0, new byte[] {1, 2, 3}, SARAH, 0, 1, 0.1f,
                        "", 0f, false));
                check(n, PeopleStore.REFUSE_TOO_BIG.equals(a) && PeopleStore.REFUSE_NOT_JPEG.equals(b)
                        && checks.list().isEmpty(), "a=" + a + " b=" + b);
            }
        });

        scenario("no_face_check_keeps_no_crop", new Scenario() {
            public void run(String n) {
                FaceChecks checks = new FaceChecks(new FakeClock());
                long h = checks.record(new FaceCheck(FaceCheck.NO_FACE, 0, jpeg(3), "", -1, 0, 0f, "", 0f, false));
                long notReady = checks.record(new FaceCheck(FaceCheck.NOT_READY, 0, jpeg(4), "", -1, 0, 0f, "", 0f,
                        false));
                check(n, checks.crop(h) == null && checks.list().get(1).check.cropJpeg == null
                        && Arrays.equals(checks.crop(notReady), jpeg(4)), "no-face crop kept");
            }
        });

        scenario("bad_ids_and_codes_refused", new Scenario() {
            public void run(String n) {
                FaceChecks checks = new FaceChecks(new FakeClock());
                List<String> accepted = new ArrayList<String>();
                FaceCheck[] bad = {
                    new FaceCheck(0, 0, jpeg(1), SARAH, 0, 1, 0.5f, "", 0f, false),
                    new FaceCheck(7, 0, jpeg(1), SARAH, 0, 1, 0.5f, "", 0f, false),
                    new FaceCheck(FaceCheck.REJECTED, 0, jpeg(1), "", -1, 0, 0f, "", 0f, false),
                    new FaceCheck(FaceCheck.REJECTED, 4, jpeg(1), "", -1, 0, 0f, "", 0f, false),
                    new FaceCheck(FaceCheck.WEAK, FaceCheck.TOO_DARK, jpeg(1), SARAH, 0, 1, 0.1f, "", 0f, false),
                    new FaceCheck(FaceCheck.CONFIDENT, 0, jpeg(1), "../people.index", 0, 1, 0.9f, "", 0f, false),
                    new FaceCheck(FaceCheck.CONFIDENT, 0, jpeg(1), SARAH, 5, 1, 0.9f, "", 0f, false),
                    new FaceCheck(FaceCheck.CLOSE, 0, jpeg(1), SARAH, 0, 1, 0.5f, "<b>", 0.48f, true),
                    new FaceCheck(FaceCheck.CONFIDENT, 0, jpeg(1), "", -1, 0, 0.9f, "", 0f, false),
                };
                for (FaceCheck c : bad) {
                    String why = refusal(checks, c);
                    if (why == null) {
                        accepted.add(c.toString());
                    }
                }
                long h = checks.record(match(FaceCheck.CLOSE, SARAH, 0.4f));
                boolean badOutcome;
                try {
                    checks.updateOutcome(h, 99, null);
                    badOutcome = false;
                } catch (IllegalArgumentException e) {
                    badOutcome = FaceChecks.REFUSE_BAD_CHECK.equals(e.getMessage());
                }
                boolean badJoined;
                try {
                    checks.updateOutcome(h, FaceCheck.JOINED, "nope");
                    badJoined = false;
                } catch (IllegalArgumentException e) {
                    badJoined = true;
                }
                check(n, accepted.isEmpty() && checks.list().size() == 1 && badOutcome && badJoined
                                && checks.list().get(0).outcome == FaceCheck.OUTCOME_PENDING,
                        "accepted=" + accepted + " badOutcome=" + badOutcome + " badJoined=" + badJoined);
            }
        });

        scenario("list_is_a_snapshot", new Scenario() {
            public void run(String n) {
                FaceChecks checks = new FaceChecks(new FakeClock());
                long h = checks.record(match(FaceCheck.CLOSE, SARAH, 0.4f));
                List<FaceChecks.Entry> before = checks.list();
                checks.updateOutcome(h, FaceCheck.YES, null);
                checks.record(match(FaceCheck.CLOSE, TOM, 0.4f));
                check(n, before.size() == 1 && before.get(0).outcome == FaceCheck.OUTCOME_PENDING,
                        "before=" + before);
            }
        });

        scenario("to_string_carries_no_crop_bytes", new Scenario() {
            public void run(String n) {
                FaceChecks checks = new FaceChecks(new FakeClock());
                checks.record(match(FaceCheck.CLOSE, SARAH, 0.4f));
                String s = checks.list().get(0).toString() + match(FaceCheck.CLOSE, SARAH, 0.4f);
                check(n, !s.contains("[B@") && !s.contains("-1, 2, 3"), s);
            }
        });

        if (failures > 0) {
            System.exit(1);
        }
    }

    private FaceChecksHarness() {
    }
}
