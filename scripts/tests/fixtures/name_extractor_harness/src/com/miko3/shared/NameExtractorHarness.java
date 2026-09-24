package com.miko3.shared;

/**
 * Host-JVM checks for NameExtractor (explore-on-claude plan U3; R11, R12,
 * KTD4), driven by scripts/tests/test_name_extractor.py. Each case is a
 * transcript and the name it should give (null: no clear name).
 *
 * Prints one "PASS <name>" or "FAIL <name>: <detail>" line per case.
 */
public final class NameExtractorHarness {
    private static final String[][] CASES = {
        // The plan's scenarios.
        {"my_name_is", "my name is Sarah", "Sarah"},
        {"i_m", "I'm Sarah", "Sarah"},
        {"call_me", "call me Sarah", "Sarah"},
        {"bare_name", "Sarah", "Sarah"},
        {"its_name_thanks", "it's Sarah thanks", "Sarah"},
        {"i_am_full_name", "i am sarah jones", "Sarah Jones"},
        {"what_is_null", "what?", null},
        {"no_is_null", "no", null},
        {"dont_know_is_null", "I don't know", null},
        // The recognizer's own shape: upper case, no punctuation.
        {"recognizer_upper_case", "MY NAME IS SARAH", "Sarah"},
        {"recognizer_i_m_upper", "I'M SARAH", "Sarah"},
        {"greeting_then_name", "hi i'm tom", "Tom"},
        {"filler_then_name", "um it's alex", "Alex"},
        {"this_is", "this is maya", "Maya"},
        {"my_names", "my name's leo", "Leo"},
        {"name_then_trailer", "i'm sarah from next door", "Sarah"},
        {"bare_full_name", "sarah jones", "Sarah Jones"},
        {"bare_name_please", "Sarah, please", "Sarah"},
        {"hyphenated", "call me mary-kate", "Mary-Kate"},
        // Replies that aren't a name.
        {"im_fine_is_null", "I'm fine", null},
        {"im_not_telling_is_null", "I'm not telling you", null},
        {"sentence_is_null", "the dog is over there", null},
        {"empty_is_null", "", null},
        {"null_is_null", null, null},
        {"blank_is_null", "   ", null},
        {"hello_alone_is_null", "hello", null},
        {"yes_is_null", "yes", null},
        {"digits_is_null", "123", null},
    };

    public static void main(String[] args) {
        for (String[] c : CASES) {
            String name = c[0];
            try {
                String got = NameExtractor.extract(c[1]);
                boolean ok = c[2] == null ? got == null : c[2].equals(got);
                System.out.println(ok ? "PASS " + name
                        : "FAIL " + name + ": \"" + c[1] + "\" gave " + got + ", expected " + c[2]);
            } catch (Throwable t) {
                System.out.println("FAIL " + name + ": threw " + t);
            }
        }
    }
}
