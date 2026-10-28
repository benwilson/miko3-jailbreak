package com.miko3.shared;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One piece of feedback a person gave the robot about himself in a
 * conversation (owner 2026-10-02): its kind, a one-sentence summary in neutral
 * words, and the person's key sentence verbatim, short. It is what the
 * feedback log keeps instead of the conversation; the quote is the only
 * verbatim text the robot ever stores.
 *
 * Plain Java, shared by Explore (reading the conversation turn's "feedback"
 * field) and the launcher (re-checking what arrives over Binder), so both
 * apply the same rules. Anything malformed reads as no feedback, never as a
 * guess.
 */
public final class Feedback {
    public static final String SUGGESTION = "suggestion";
    public static final String COMPLAINT = "complaint";
    public static final String PRAISE = "praise";
    public static final String BUG = "bug";
    /** The kinds an entry may have, in the order the prompt names them. */
    public static final List<String> KINDS =
            Collections.unmodifiableList(Arrays.asList(SUGGESTION, COMPLAINT, PRAISE, BUG));

    public static final int MAX_SUMMARY_CHARS = 200;
    /** The prompt asks for at most about 25 words; a little slack, then cut. */
    public static final int MAX_QUOTE_WORDS = 30;
    public static final int MAX_QUOTE_CHARS = 240;
    public static final int MAX_CONTEXT_CHARS = 60;

    public final String kind;
    public final String summary;
    /** "" when the person's words gave no single sentence worth keeping. */
    public final String quote;

    private Feedback(String kind, String summary, String quote) {
        this.kind = kind;
        this.summary = summary;
        this.quote = quote;
    }

    /** Checked and cleaned feedback, or null for an unknown kind or an empty summary. */
    public static Feedback of(String kind, String summary, String quote) {
        String k = kind == null ? "" : kind.trim().toLowerCase(Locale.US);
        if (!KINDS.contains(k)) {
            return null;
        }
        String s = clean(summary, MAX_SUMMARY_CHARS);
        if (s.isEmpty()) {
            return null;
        }
        return new Feedback(k, s, capWords(clean(quote, MAX_QUOTE_CHARS), MAX_QUOTE_WORDS));
    }

    /**
     * The conversation turn's "feedback" field as parsed JSON: an object with
     * string kind, summary and (optional) quote. Null for JSON null, the kind
     * "none", or anything malformed.
     */
    public static Feedback fromJson(Object v) {
        if (!(v instanceof Map)) {
            return null;
        }
        Map<?, ?> m = (Map<?, ?>) v;
        Object kind = m.get("kind");
        Object summary = m.get("summary");
        Object quote = m.get("quote");
        if (!(kind instanceof String) || !(summary instanceof String) || quote != null && !(quote instanceof String)) {
            return null;
        }
        return of((String) kind, (String) summary, (String) quote);
    }

    /** A context phrase ("while docked"), cleaned and capped; "" for none. */
    public static String cleanContext(String context) {
        return clean(context, MAX_CONTEXT_CHARS);
    }

    /** Trimmed, whitespace and control characters collapsed to single spaces, capped; "" for null. */
    public static String clean(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(Math.min(text.length(), maxChars + 1));
        boolean space = false;
        for (int i = 0; i < text.length() && out.length() < maxChars; i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c)) {
                space = true;
                continue;
            }
            if (space && out.length() > 0) {
                out.append(' ');
            }
            space = false;
            out.append(c);
        }
        String s = out.toString();
        return s.length() > maxChars ? s.substring(0, maxChars).trim() : s.trim();
    }

    private static String capWords(String text, int maxWords) {
        if (text.isEmpty()) {
            return text;
        }
        String[] words = text.split(" ");
        if (words.length <= maxWords) {
            return text;
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < maxWords; i++) {
            b.append(i == 0 ? "" : " ").append(words[i]);
        }
        return b.toString();
    }

    /** The kind only, so a stray log line never carries what was said. */
    @Override
    public String toString() {
        return kind;
    }
}
