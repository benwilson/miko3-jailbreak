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
        scenario("kinds_follow_the_wake_flag_the_words_and_the_tier", new Scenario() {
            public void run(String n) {
                CueClassifier c = new CueClassifier(new Switch());
                int s = CueClassifier.TIER_STRONG;
                int w = CueClassifier.TIER_WEAK;
                // The engine's flag is authoritative, even with words the recogniser decoded.
                boolean wake = CueClassifier.kind("come here", true, s) == CueClassifier.KIND_WAKE_WORD
                        && CueClassifier.kind("", true, s) == CueClassifier.KIND_WAKE_WORD;
                // Without the flag: nothing decoded, or the phrase itself, is still the wake word.
                boolean phrase = CueClassifier.kind("", false, s) == CueClassifier.KIND_WAKE_WORD
                        && CueClassifier.kind("hey miko, come here", false, s) == CueClassifier.KIND_WAKE_WORD
                        && CueClassifier.kind("Hey Miko", false, s) == CueClassifier.KIND_WAKE_WORD;
                boolean name = CueClassifier.kind("Miko!", false, s) == CueClassifier.KIND_NAME
                        && CueClassifier.kind("hi mikey", false, s) == CueClassifier.KIND_NAME
                        && CueClassifier.kind("sorry miko", false, s) == CueClassifier.KIND_NAME;
                boolean greet = CueClassifier.kind("hey buddy", false, s) == CueClassifier.KIND_GREETING
                        && CueClassifier.kind("morning", false, s) == CueClassifier.KIND_GREETING;
                c.shoved(5000);
                int shoved = c.tier("oops sorry", false, false, 5800);
                boolean apology = shoved == s && CueClassifier.kind("oops sorry", false, shoved) == CueClassifier.KIND_APOLOGY
                        && CueClassifier.kind("sorry", false, w) == CueClassifier.KIND_APOLOGY
                        && CueClassifier.kind("whoops!", false, w) == CueClassifier.KIND_APOLOGY;
                boolean voice = CueClassifier.kind("", false, w) == CueClassifier.KIND_VOICE
                        && CueClassifier.kind("the printer again", false, w) == CueClassifier.KIND_VOICE
                        && CueClassifier.kind(null, false, w) == CueClassifier.KIND_VOICE;
                check(n, wake && phrase && name && greet && apology && voice,
                        "wake=" + wake + " phrase=" + phrase + " name=" + name + " greet=" + greet
                                + " apology=" + apology + " voice=" + voice);
            }
        });
        scenario("excuse_me_and_my_bad_after_a_shove_are_strong_apologies", new Scenario() {
            public void run(String n) {
                // The PR #18 finding: the apology phrases count too, not only the sorry words.
                CueClassifier c = new CueClassifier(new Switch());
                c.shoved(5000);
                int excuse = c.tier("excuse me", false, false, 6000);
                int myBad = c.tier("my bad", false, false, 6000);
                CueClassifier calm = new CueClassifier(new Switch());
                int unshoved = calm.tier("excuse me", false, false, 6000);
                check(n, excuse == CueClassifier.TIER_STRONG && myBad == CueClassifier.TIER_STRONG
                        && CueClassifier.kind("excuse me", false, excuse) == CueClassifier.KIND_APOLOGY
                        && CueClassifier.kind("my bad", false, myBad) == CueClassifier.KIND_APOLOGY
                        && unshoved == CueClassifier.TIER_WEAK
                        && CueClassifier.kind("excuse me", false, unshoved) == CueClassifier.KIND_APOLOGY,
                        "excuse=" + excuse + " myBad=" + myBad + " unshoved=" + unshoved + " kind="
                                + CueClassifier.kind("excuse me", false, excuse));
            }
        });
        scenario("a_calls_message_is_its_words_besides_the_address", new Scenario() {
            public void run(String n) {
                // Owner 2026-10-02: "Hey Miko, how's it going?" is the caller's first message,
                // "how's it going". The address alone (a bare wake, his name alone) leaves nothing.
                String[][] cases = {
                        {"Hey Miko, how's it going?", "how's it going"},
                        {"hey miko", ""},
                        {"Miko", ""},
                        {"hi mikey what are you up to", "what are you up to"},
                        {"Miko, come over here", "come over here"},
                        {"how's it going miko", "how's it going"},
                        {"so hey miko did you see that", "so did you see that"},
                        {"ok miko", ""},
                        {"", ""},
                        {null, ""},
                };
                StringBuilder bad = new StringBuilder();
                for (String[] c : cases) {
                    String got = com.miko3.shared.CueWords.message(c[0]);
                    if (!c[1].equals(got)) {
                        bad.append("[").append(c[0]).append(" -> ").append(got).append("] ");
                    }
                }
                check(n, bad.length() == 0, bad.toString());
            }
        });
    }
}
