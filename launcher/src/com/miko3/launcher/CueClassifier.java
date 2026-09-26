package com.miko3.launcher;

import java.util.Locale;

/**
 * Tiers for one heard utterance (meeting plan U3; R2, R3, R5, KTD3, KTD11).
 * Plain Java, proven in scripts/tests/test_cue_classifier.py.
 *
 * Strong: the wake word (the vendor engine fired), his name in any form, or a
 * clear greeting that opens a short utterance ("hey buddy", "morning"), and
 * "sorry" or "oops" within SORRY_WINDOW_MS of a shove or a collision stop the
 * brain stamped through shoved(). Weak: anything else that was heard, a lone
 * "hey", a burst with no words. None: nothing to report.
 *
 * The "answers when spoken to" switch (KTD11) is read at classify time: off,
 * only the wake word produces a cue. A conversation listen bypasses the
 * switch, because the person in front of him is answering, not addressing.
 *
 * This is the one class that sees every utterance's words, so it logs nothing
 * at all; the session logs counters.
 */
final class CueClassifier {
    static final int TIER_NONE = 0;
    static final int TIER_WEAK = 1;
    static final int TIER_STRONG = 2;

    static final int SIDE_LEFT = -1;
    static final int SIDE_NONE = 0;
    static final int SIDE_RIGHT = 1;

    /** KTD3: "sorry" or "oops" this soon after a shove or a collision stop is strong. */
    static final long SORRY_WINDOW_MS = 2000;
    /** A greeting counts only when it opens an utterance this short ("hey buddy
     * how are you"); "this morning the build broke" is office talk, not a greeting. */
    static final int GREETING_MAX_WORDS = 6;

    /** His name as the recogniser tends to spell it (KTD2's hotwords bias toward these). */
    static final String[] NAMES = {"miko", "mika", "mikey", "mikko", "meeko", "mico", "niko"};
    /** Greetings aimed at him: the buddy, time-of-day and robot forms. A bare
     * "hello" or "hey" is a weak cue: it may be for a phone. */
    static final String[] GREETINGS = {
            "hey buddy", "hi buddy", "hello buddy", "yo buddy", "morning buddy",
            "morning", "good morning", "afternoon", "good afternoon", "evening", "good evening",
            "hey robot", "hi robot", "hello robot", "hey little guy", "hey little robot", "howdy",
    };
    static final String[] SORRY_WORDS = {"sorry", "oops", "whoops", "oop"};
    static final String[] SORRY_PHRASES = {"my bad", "excuse me"};

    /** The Settings page's "answers when spoken to" switch (KTD11). */
    interface Switch {
        boolean answersWhenSpokenTo();
    }

    private final Switch answers;
    /** When the brain last stamped a shove or a collision stop, or NEVER. */
    private volatile long shovedAtMs = NEVER;
    static final long NEVER = Long.MIN_VALUE;

    CueClassifier(Switch answers) {
        this.answers = answers;
    }

    /** The brain stamped a shove (accelerometer, wheels stopped) or a collision stop at atMs. */
    void shoved(long atMs) {
        shovedAtMs = atMs;
    }

    long lastShoveMs() {
        return shovedAtMs;
    }

    /**
     * The tier of an utterance: text as the recogniser gave it (may be empty
     * for a burst), wake when the vendor engine fired inside it, listening
     * while a conversation listen is active, atMs when the speech started.
     */
    int tier(String text, boolean wake, boolean listening, long atMs) {
        if (wake) {
            return TIER_STRONG;
        }
        if (!listening && !answers.answersWhenSpokenTo()) {
            return TIER_NONE;
        }
        String norm = normalize(text);
        if (norm.isEmpty()) {
            return TIER_WEAK;
        }
        if (namesHim(norm) || isGreeting(norm)) {
            return TIER_STRONG;
        }
        if (isSorry(norm)) {
            long shove = shovedAtMs;
            if (shove != NEVER && atMs >= shove && atMs - shove <= SORRY_WINDOW_MS) {
                return TIER_STRONG;
            }
        }
        return TIER_WEAK;
    }

    /** Which side a direction angle puts the voice on: negative is left,
     * positive right, and none (null, NaN or dead ahead) is SIDE_NONE. */
    static int side(Float angle) {
        if (angle == null || angle.isNaN() || angle == 0f) {
            return SIDE_NONE;
        }
        return angle < 0f ? SIDE_LEFT : SIDE_RIGHT;
    }

    /** Lower case, letters and apostrophes only, single spaces, trimmed. */
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

    static boolean namesHim(String norm) {
        for (String word : norm.split(" ")) {
            for (String name : NAMES) {
                if (word.equals(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean isGreeting(String norm) {
        int words = norm.split(" ").length;
        if (words > GREETING_MAX_WORDS) {
            return false;
        }
        for (String g : GREETINGS) {
            if (norm.equals(g) || norm.startsWith(g + " ")) {
                return true;
            }
        }
        return false;
    }

    static boolean isSorry(String norm) {
        for (String word : norm.split(" ")) {
            for (String s : SORRY_WORDS) {
                if (word.equals(s)) {
                    return true;
                }
            }
        }
        for (String p : SORRY_PHRASES) {
            if (norm.equals(p) || norm.startsWith(p + " ") || norm.endsWith(" " + p) || norm.contains(" " + p + " ")) {
                return true;
            }
        }
        return false;
    }
}
