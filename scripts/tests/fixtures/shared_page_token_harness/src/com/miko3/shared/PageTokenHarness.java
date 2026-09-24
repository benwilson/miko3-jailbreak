package com.miko3.shared;

/**
 * Host-JVM checks for shared/PageToken (settings plan U1), driven by
 * scripts/tests/test_shared_page_token.py.
 *
 * Prints one "PASS <name>" or "FAIL <name>: <detail>" line per scenario; the
 * Python side asserts on each by name.
 */
public final class PageTokenHarness {
    private static void check(String name, boolean ok, String detail) {
        System.out.println(ok ? "PASS " + name : "FAIL " + name + ": " + detail);
    }

    public static void main(String[] args) {
        PageToken one = new PageToken(1);
        String a = one.issue();
        String b = one.issue();
        check("capacity_one_accepts_only_latest",
                one.check(b) && !one.check(a),
                "latest=" + one.check(b) + " previous=" + one.check(a));

        PageToken four = new PageToken(4);
        String[] t = new String[5];
        for (int i = 0; i < t.length; i++) {
            t[i] = four.issue();
        }
        boolean lastFour = four.check(t[1]) && four.check(t[2]) && four.check(t[3]) && four.check(t[4]);
        check("capacity_four_accepts_last_four", lastFour, "one of the last four was rejected");
        check("capacity_four_rejects_fifth_oldest", !four.check(t[0]), "oldest token still accepted");

        PageToken fresh = new PageToken(4);
        check("nothing_issued_rejects_everything",
                !fresh.check("") && !fresh.check(null) && !fresh.check("00000000000000000000000000000000"),
                "a never-issued token accepted something");

        PageToken p = new PageToken(4);
        String good = p.issue();
        String wrong = (good.charAt(0) == '0' ? "1" : "0") + good.substring(1);
        check("empty_token_rejected", !p.check(""), "empty accepted");
        check("null_token_rejected", !p.check(null), "null accepted");
        check("wrong_token_rejected", !p.check(wrong), "wrong accepted");
        check("different_length_rejected",
                !p.check(good.substring(1)) && !p.check(good + "0"),
                "prefix or extension accepted");

        check("tokens_are_32_hex_and_distinct",
                good.matches("[0-9a-f]{32}") && !good.equals(p.issue()),
                "token=" + good);

        boolean threw = false;
        try {
            new PageToken(0);
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        check("capacity_below_one_rejected", threw, "new PageToken(0) did not throw");
    }
}
