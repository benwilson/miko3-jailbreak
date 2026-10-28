package com.miko3.shared;

/**
 * The words a heard utterance is matched against (meeting plan U3, U7; KTD3):
 * his name as the recogniser tends to spell it, the wake phrases the vendor
 * engine fires on, the apologies that count after a shove, and the
 * normalisation and matchers over them. Plain Java with no
 * android.* imports, so the launcher's classifier harness proves it. It sees
 * every utterance's words, so it logs nothing at all.
 */
public final class CueWords {
    /** His name as the recogniser tends to spell it (KTD2's hotwords bias toward these). */
    public static final String[] NAMES = {"miko", "mika", "mikey", "mikko", "meeko", "mico", "niko"};
    /** The wake phrases the vendor engine fires on, as the recogniser writes them: the
     * classifier's text fallback for naming a KIND_WAKE_WORD cue when the engine missed. */
    public static final String[] WAKE_PHRASES = {"hey miko", "hi miko", "hey mika", "hey mikey", "ok miko"};
    public static final String[] SORRY_WORDS = {"sorry", "oops", "whoops", "oop"};
    public static final String[] SORRY_PHRASES = {"my bad", "excuse me"};

    private CueWords() {
    }

    /** Lower case, letters, digits and apostrophes only, single spaces, trimmed. */
    public static String normalize(String text) {
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
        return b.toString().trim();
    }

    /** Whether any of the normalised text's words (its split on " ") is one of wanted. */
    public static boolean hasWord(String[] words, String[] wanted) {
        for (String word : words) {
            for (String want : wanted) {
                if (word.equals(want)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The words that make an address with his name ("hey miko"): dropped with it from a call's message. */
    static final String[] ADDRESS_WORDS = {"hey", "hi", "hello", "ok", "okay", "yo", "oh"};

    /**
     * A call's message (owner 2026-10-02): the utterance's words besides the address itself,
     * normalised, so "Hey Miko, how's it going?" is "how's it going" and a bare "Hey Miko" is
     * "". The first of his name's spellings goes, with an address word just before it; with
     * none of them, only a leading address word goes.
     */
    public static String message(String text) {
        String norm = normalize(text);
        if (norm.isEmpty()) {
            return "";
        }
        String[] words = norm.split(" ");
        int name = -1;
        for (int i = 0; i < words.length && name < 0; i++) {
            for (String want : NAMES) {
                if (words[i].equals(want)) {
                    name = i;
                    break;
                }
            }
        }
        int dropFrom = name;
        if (name < 0) {
            dropFrom = isAddressWord(words[0]) ? 0 : -1;
            name = dropFrom;
        } else if (name > 0 && isAddressWord(words[name - 1])) {
            dropFrom = name - 1;
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (dropFrom >= 0 && i >= dropFrom && i <= name) {
                continue;
            }
            if (b.length() > 0) {
                b.append(' ');
            }
            b.append(words[i]);
        }
        return b.toString();
    }

    private static boolean isAddressWord(String word) {
        for (String a : ADDRESS_WORDS) {
            if (word.equals(a)) {
                return true;
            }
        }
        return false;
    }

    /** Whether the normalised text holds one of phrases as whole words. */
    public static boolean hasPhrase(String norm, String[] phrases) {
        for (String p : phrases) {
            if (norm.equals(p) || norm.startsWith(p + " ") || norm.endsWith(" " + p) || norm.contains(" " + p + " ")) {
                return true;
            }
        }
        return false;
    }
}
