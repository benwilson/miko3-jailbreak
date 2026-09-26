package com.miko3.launcher;

/**
 * Host-JVM checks for the launcher's cue classifier (meeting plan U3; R2, R3,
 * R5, KTD3, KTD11), driven by scripts/tests/test_cue_classifier.py. Lives in
 * the launcher's package to reach the package-private CueClassifier; touches
 * no android.*. Prints one "PASS <name>" or "FAIL <name>: <detail>" line per
 * scenario.
 */
public final class CueClassifierHarness {
    static final class Switch implements CueClassifier.Switch {
        boolean on = true;

        @Override
        public boolean answersWhenSpokenTo() {
            return on;
        }
    }

    interface Scenario {
        void run(String name) throws Exception;
    }

    static void scenario(String name, Scenario s) {
        try {
            s.run(name);
        } catch (Throwable t) {
            System.out.println("FAIL " + name + ": threw " + t);
        }
    }

    static void check(String name, boolean ok, String detail) {
        System.out.println(ok ? "PASS " + name : "FAIL " + name + ": " + detail);
    }

    static String tiers(CueClassifier c, String... texts) {
        StringBuilder b = new StringBuilder();
        for (String t : texts) {
            b.append('"').append(t).append("\"=").append(c.tier(t, false, false, 10000)).append(' ');
        }
        return b.toString();
    }

    static boolean all(CueClassifier c, int tier, String... texts) {
        for (String t : texts) {
            if (c.tier(t, false, false, 10000) != tier) {
                return false;
            }
        }
        return true;
    }

    public static void main(String[] args) {
        scenario("names_and_greetings_are_strong", new Scenario() {
            public void run(String n) {
                CueClassifier c = new CueClassifier(new Switch());
                String[] strong = {"hey miko", "miko", "mikey", "mika", "hey buddy", "morning"};
                check(n, all(c, CueClassifier.TIER_STRONG, strong), tiers(c, strong));
            }
        });
        scenario("lone_hey_and_burst_are_weak", new Scenario() {
            public void run(String n) {
                CueClassifier c = new CueClassifier(new Switch());
                check(n, all(c, CueClassifier.TIER_WEAK, "hey", "", "   ", "the printer is out of toner"),
                        tiers(c, "hey", "", "the printer is out of toner"));
            }
        });
        scenario("sorry_soon_after_shove_is_strong", new Scenario() {
            public void run(String n) {
                CueClassifier c = new CueClassifier(new Switch());
                c.shoved(5000);
                int sorry = c.tier("sorry", false, false, 6500);
                int oops = c.tier("oh oops", false, false, 5200);
                int edge = c.tier("sorry", false, false, 5000 + CueClassifier.SORRY_WINDOW_MS);
                check(n, sorry == CueClassifier.TIER_STRONG && oops == CueClassifier.TIER_STRONG
                        && edge == CueClassifier.TIER_STRONG, sorry + " " + oops + " " + edge);
            }
        });
        scenario("sorry_late_after_shove_is_weak", new Scenario() {
            public void run(String n) {
                CueClassifier c = new CueClassifier(new Switch());
                c.shoved(5000);
                int late = c.tier("sorry", false, false, 9000);
                int before = c.tier("sorry", false, false, 4000);
                CueClassifier never = new CueClassifier(new Switch());
                int noShove = never.tier("sorry", false, false, 9000);
                check(n, late == CueClassifier.TIER_WEAK && before == CueClassifier.TIER_WEAK
                        && noShove == CueClassifier.TIER_WEAK, late + " " + before + " " + noShove);
            }
        });
        scenario("switch_off_leaves_only_the_wake_word", new Scenario() {
            public void run(String n) {
                Switch sw = new Switch();
                sw.on = false;
                CueClassifier c = new CueClassifier(sw);
                c.shoved(5000);
                boolean wake = c.tier("hey miko", true, false, 5100) == CueClassifier.TIER_STRONG
                        && c.tier("", true, false, 5100) == CueClassifier.TIER_STRONG;
                boolean rest = all(c, CueClassifier.TIER_NONE, "hey miko", "miko", "hey buddy", "morning", "hey", "")
                        && c.tier("sorry", false, false, 5500) == CueClassifier.TIER_NONE;
                check(n, wake && rest, "wake=" + wake + " " + tiers(c, "hey miko", "miko", "hey buddy", "hey", ""));
            }
        });
        scenario("conversation_listen_ignores_the_switch", new Scenario() {
            public void run(String n) {
                Switch sw = new Switch();
                sw.on = false;
                CueClassifier c = new CueClassifier(sw);
                int reply = c.tier("pretty good thanks", false, true, 5000);
                int name = c.tier("hey miko", false, true, 5000);
                check(n, reply == CueClassifier.TIER_WEAK && name == CueClassifier.TIER_STRONG, reply + " " + name);
            }
        });
        scenario("side_follows_the_angle_sign", new Scenario() {
            public void run(String n) {
                check(n, CueClassifier.side(null) == CueClassifier.SIDE_NONE
                        && CueClassifier.side(-35f) == CueClassifier.SIDE_LEFT
                        && CueClassifier.side(20f) == CueClassifier.SIDE_RIGHT
                        && CueClassifier.side(0f) == CueClassifier.SIDE_NONE
                        && CueClassifier.side(Float.NaN) == CueClassifier.SIDE_NONE,
                        CueClassifier.side(-35f) + " " + CueClassifier.side(20f) + " " + CueClassifier.side(0f));
            }
        });
        scenario("greeting_inside_a_long_sentence_is_weak", new Scenario() {
            public void run(String n) {
                CueClassifier c = new CueClassifier(new Switch());
                check(n, all(c, CueClassifier.TIER_WEAK, "this morning the build broke again",
                        "I said good morning to everyone in the kitchen")
                        && all(c, CueClassifier.TIER_STRONG, "good morning", "morning miko", "hey buddy how are you"),
                        tiers(c, "this morning the build broke again", "good morning", "hey buddy how are you"));
            }
        });
        scenario("normalisation_ignores_case_and_punctuation", new Scenario() {
            public void run(String n) {
                CueClassifier c = new CueClassifier(new Switch());
                check(n, all(c, CueClassifier.TIER_STRONG, "HEY MIKO!", "Miko?", "Hey, buddy.", "MORNING,")
                        && "hey miko".equals(CueClassifier.normalize("  HEY,  Miko! ")),
                        tiers(c, "HEY MIKO!", "Miko?", "Hey, buddy.") + "/" + CueClassifier.normalize("  HEY,  Miko! "));
            }
        });
    }
}
