package com.miko3.mode.explore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The reply to "Is that you, {name}?" and to "And your last name?" (face plan
 * U7; KTD6, KTD9, R5, R6, R21): a plain-Java table, not a whole-utterance match.
 * Plain Java with no android.* imports and no shared sources, so the brain's
 * host harness runs it; the name in a reply comes from the injected Names, which
 * the adapter wires to the robot's NameExtractor.
 *
 *   YES           a leading affirmative (yes, yeah, yep, yup, that's me, it's me,
 *                 correct, sure), or a name equal to the name asked. When the
 *                 question used a full name, a bare first name is UNCLEAR.
 *   NO_WITH_NAME  a leading negation plus a name once the negation is stripped
 *                 ("no, I'm Sarah"), or any name different from the one asked.
 *   NO            a bare negation (no, nope, nah, not me, wrong).
 *   UNCLEAR       anything else, silence and a question back ("who's Ben?")
 *                 included; it counts as a no (R21).
 *
 * The forget confirmation keeps its own strict list (ChatSession.affirmative).
 */
final class AnswerParser {

    /** The name in some words by the robot's own patterns, title-cased, or null. */
    interface Names {
        String nameIn(String transcript);
    }

    enum Kind { YES, NO_WITH_NAME, NO, UNCLEAR }

    /** A parsed reply: its kind and, for NO_WITH_NAME, the name given. */
    static final class Reply {
        final Kind kind;
        final String name;

        private Reply(Kind kind, String name) {
            this.kind = kind;
            this.name = name;
        }

        boolean yes() {
            return kind == Kind.YES;
        }

        /** The kind only: a name never goes into a trace. */
        @Override
        public String toString() {
            return kind.toString();
        }
    }

    private static final Reply YES = new Reply(Kind.YES, null);
    private static final Reply NO = new Reply(Kind.NO, null);
    private static final Reply UNCLEAR = new Reply(Kind.UNCLEAR, null);

    /** Leading affirmatives, as word sequences (apostrophes kept, as the recogniser gives them, and dropped). */
    private static final String[][] AFFIRMATIVES = {
        {"yes"}, {"yeah"}, {"yep"}, {"yup"}, {"yea"}, {"correct"}, {"sure"},
        {"that's", "me"}, {"thats", "me"}, {"that", "is", "me"}, {"it's", "me"}, {"its", "me"}, {"it", "is", "me"},
    };
    /** Leading negations, longest first so "not me" wins over "not". */
    private static final String[][] NEGATIONS = {
        {"that's", "not", "me"}, {"thats", "not", "me"}, {"that", "is", "not", "me"},
        {"it's", "not", "me"}, {"its", "not", "me"}, {"it", "is", "not", "me"},
        {"no", "way"}, {"not", "me"}, {"no"}, {"nope"}, {"nah"}, {"wrong"}, {"not"},
    };
    /** A question back: counts as no (KTD9). */
    private static final String[] QUESTIONS = {"who", "who's", "whos", "what", "what's", "whats", "why", "how",
        "where", "when", "which", "huh", "pardon", "sorry", "excuse"};

    private AnswerParser() {
    }

    /** The reply to "Is that you, {asked}?"; never null. */
    static Reply parse(String transcript, String asked, Names names) {
        List<String> words = words(transcript);
        if (words.isEmpty()) {
            return UNCLEAR;
        }
        if (contains(QUESTIONS, words.get(0))) {
            return UNCLEAR;
        }
        if (leading(words, AFFIRMATIVES) > 0) {
            return YES;
        }
        if (transcript.indexOf('?') >= 0) {
            return UNCLEAR;
        }
        int neg = leading(words, NEGATIONS);
        if (neg > 0) {
            String name = nameIn(join(words.subList(neg, words.size())), names);
            if (name == null || same(name, asked)) {
                return NO;
            }
            return new Reply(Kind.NO_WITH_NAME, name);
        }
        String name = nameIn(join(words), names);
        if (name == null) {
            return UNCLEAR;
        }
        if (same(name, asked)) {
            return YES;
        }
        String[] a = split(asked);
        String[] n = split(name);
        if (a.length == 2 && n.length == 1 && n[0].equalsIgnoreCase(a[0])) {
            // Asked with a full name, because another stored person shares the first name.
            return UNCLEAR;
        }
        return new Reply(Kind.NO_WITH_NAME, name);
    }

    /**
     * The reply to "And your last name?" (KTD9): one word is the last name; two
     * must start with the pending first name. A repeat of the first name alone,
     * or no name at all, is null: nobody is stored (R21).
     */
    static String lastName(String transcript, String pendingFirst, Names names) {
        String name = nameIn(transcript, names);
        if (name == null) {
            return null;
        }
        String[] n = split(name);
        String first = pendingFirst == null ? "" : pendingFirst.trim();
        if (n.length == 1) {
            return n[0].equalsIgnoreCase(first) ? null : n[0];
        }
        if (n.length == 2 && n[0].equalsIgnoreCase(first)) {
            return n[1];
        }
        return null;
    }

    /** Case-insensitive, whitespace-insensitive name equality. */
    static boolean same(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return join(words(a)).equals(join(words(b)));
    }

    private static String nameIn(String text, Names names) {
        if (names == null || text == null || text.trim().isEmpty()) {
            return null;
        }
        String n = names.nameIn(text);
        return n == null || n.trim().isEmpty() ? null : n.trim().replaceAll("\\s+", " ");
    }

    private static String[] split(String name) {
        return name == null || name.trim().isEmpty() ? new String[0] : name.trim().split("\\s+");
    }

    /** How many words a leading phrase from the table covers, or 0. */
    private static int leading(List<String> words, String[][] table) {
        for (String[] phrase : table) {
            if (phrase.length > words.size()) {
                continue;
            }
            boolean match = true;
            for (int i = 0; i < phrase.length && match; i++) {
                match = words.get(i).equals(phrase[i]);
            }
            if (match) {
                return phrase.length;
            }
        }
        return 0;
    }

    private static boolean contains(String[] list, String w) {
        for (String s : list) {
            if (s.equals(w)) {
                return true;
            }
        }
        return false;
    }

    private static String join(List<String> words) {
        return String.join(" ", words);
    }

    /** Lower-case words; punctuation other than an apostrophe or hyphen is a space; curly apostrophes made straight. */
    static List<String> words(String text) {
        List<String> out = new ArrayList<String>();
        if (text == null) {
            return out;
        }
        String t = text.toLowerCase(Locale.ROOT).replace('’', '\'').replace('‘', '\'');
        StringBuilder sb = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            sb.append(Character.isLetterOrDigit(c) || c == '\'' || c == '-' ? c : ' ');
        }
        for (String w : sb.toString().trim().split("\\s+")) {
            w = w.replaceAll("^['-]+|['-]+$", "");
            if (!w.isEmpty()) {
                out.add(w);
            }
        }
        return out;
    }
}
