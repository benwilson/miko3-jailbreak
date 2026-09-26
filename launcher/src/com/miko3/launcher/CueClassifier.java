package com.miko3.launcher;

import com.miko3.shared.CueWords;

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
 * at all; the session logs counters. The name and apology vocabulary and the
 * normalisation live in the shared CueWords.
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

    /** Greetings aimed at him: the buddy, time-of-day and robot forms. A bare
     * "hello" or "hey" is a weak cue: it may be for a phone. */
    static final String[] GREETINGS = {
            "hey buddy", "hi buddy", "hello buddy", "yo buddy", "morning buddy",
            "morning", "good morning", "afternoon", "good afternoon", "evening", "good evening",
            "hey robot", "hi robot", "hello robot", "hey little guy", "hey little robot", "howdy",
    };

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
        String[] words = norm.split(" ");
        if (namesHim(words) || isGreeting(norm, words.length)) {
            return TIER_STRONG;
        }
        if (isSorry(norm, words)) {
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

    /** Lower case, letters, digits and apostrophes only, single spaces, trimmed. */
    static String normalize(String text) {
        return CueWords.normalize(text);
    }

    static boolean namesHim(String norm) {
        return namesHim(norm.split(" "));
    }

    /** words: the normalised text split on " ". */
    static boolean namesHim(String[] words) {
        return CueWords.hasWord(words, CueWords.NAMES);
    }

    static boolean isGreeting(String norm) {
        return isGreeting(norm, norm.split(" ").length);
    }

    /** wordCount: how many words the normalised text split into. */
    static boolean isGreeting(String norm, int wordCount) {
        if (wordCount > GREETING_MAX_WORDS) {
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
        return isSorry(norm, norm.split(" "));
    }

    /** words: the normalised text split on " ". */
    static boolean isSorry(String norm, String[] words) {
        return CueWords.hasWord(words, CueWords.SORRY_WORDS) || CueWords.hasPhrase(norm, CueWords.SORRY_PHRASES);
    }
}
