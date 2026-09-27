package com.miko3.mode.explore;

import java.util.Locale;

/**
 * What kind of cue an utterance is (meeting plan U7; KTD3, KTD8), from the
 * launcher's tier and the words it heard. The launcher's session decides the
 * tier on the robot; the brain needs one thing more: whether a strong cue was
 * the wake word (the only cue that opens a conversation in EYES_ONLY) and
 * whether a weak one was an apology (strong within 2 s of a shove or a bump).
 * Plain Java with no android.* imports, so the harness proves it; the live
 * adapter calls it on the callback thread and keeps nothing of the text.
 */
final class CueKinds {
    /** The wake phrases the vendor engine fires on, as the recogniser writes them. */
    static final String[] WAKE_PHRASES = {"hey miko", "hi miko", "hey mika", "hey mikey", "ok miko"};
    /** His name in the forms the recogniser produces (the launcher's list, kept in step by hand). */
    static final String[] NAMES = {"miko", "mika", "mikey", "mikko", "meeko", "mico", "niko"};
    static final String[] SORRY_WORDS = {"sorry", "oops", "whoops", "oop"};

    private CueKinds() {
    }

    /**
     * The kind for a heard utterance. Strong: the wake phrase, or nothing decoded
     * (the engine fired with the gate closed) is WAKE_WORD; his name is NAME; any
     * other strong utterance is a GREETING (the launcher only makes greetings
     * strong), except an apology, which the launcher upgrades after a shove.
     * Weak: an apology is APOLOGY, anything else a VOICE burst.
     */
    static Ears.Kind of(String text, Ears.Tier tier) {
        String norm = normalize(text);
        if (tier == Ears.Tier.STRONG) {
            if (norm.isEmpty() || hasPhrase(norm, WAKE_PHRASES)) {
                return Ears.Kind.WAKE_WORD;
            }
            if (hasWord(norm, SORRY_WORDS) && !hasWord(norm, NAMES)) {
                return Ears.Kind.APOLOGY;
            }
            return hasWord(norm, NAMES) ? Ears.Kind.NAME : Ears.Kind.GREETING;
        }
        return hasWord(norm, SORRY_WORDS) ? Ears.Kind.APOLOGY : Ears.Kind.VOICE;
    }

    /** Lower case, letters and digits only, one space between words. */
    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(text.length());
        boolean space = true;
        for (int i = 0; i < text.length(); i++) {
            char c = Character.toLowerCase(text.charAt(i));
            if (Character.isLetterOrDigit(c) || c == '\'') {
                b.append(c);
                space = false;
            } else if (!space) {
                b.append(' ');
                space = true;
            }
        }
        return b.toString().trim().toLowerCase(Locale.ROOT);
    }

    private static boolean hasWord(String norm, String[] words) {
        for (String w : norm.split(" ")) {
            for (String want : words) {
                if (w.equals(want)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasPhrase(String norm, String[] phrases) {
        for (String p : phrases) {
            if (norm.equals(p) || norm.startsWith(p + " ") || norm.endsWith(" " + p) || norm.contains(" " + p + " ")) {
                return true;
            }
        }
        return false;
    }
}
