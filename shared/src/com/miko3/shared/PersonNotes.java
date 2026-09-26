package com.miko3.shared;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One named person's notes (meeting plan U5, KTD10; R17): interests, open
 * threads with roughly when they came up, topics covered, and questions
 * already asked. Never a transcript. One small JSON document per person,
 * kept by the launcher's PeopleStore beside the face and merged robot-side
 * from the per-turn deltas Explore's conversation model returns.
 *
 * Every list is a set keyed on normalize(): a retried turn's repeated delta
 * is a no-op, and a thread named in closed_threads moves from the open
 * threads to the topics in one merge. The caps are the named constants
 * below; a list over its cap drops its oldest entries (open threads: closed
 * first, then oldest), and MAX_DOCUMENT_BYTES is the binding limit, trimmed
 * in the order topics, open threads, interests, questions asked, so a
 * question he has asked is never dropped while an interest remains.
 *
 * A delta is a JSON object whose fields are FIELD_INTERESTS, FIELD_OPEN_THREADS,
 * FIELD_CLOSED_THREADS, FIELD_TOPICS and FIELD_QUESTIONS_ASKED, each a list
 * of strings; merge() refuses anything else with a fixed REFUSE_* reason and
 * leaves the document as it was. Immutable and plain Java (no android.*), so
 * the launcher, Explore and the host harnesses share it.
 */
public final class PersonNotes {
    /** Enough for ten open-ended conversations (KTD10). */
    public static final int MAX_QUESTIONS_ASKED = 120;
    public static final int MAX_OPEN_THREADS = 6;
    public static final int MAX_INTERESTS = 8;
    public static final int MAX_TOPICS = 12;
    /** Plain characters after trimming and collapsing spaces; no line breaks. */
    public static final int MAX_ENTRY_CHARS = 80;
    /** The whole document as UTF-8 JSON, about 1,000 tokens in the prefix; the binding limit. */
    public static final int MAX_DOCUMENT_BYTES = 4096;

    public static final String FIELD_INTERESTS = "interests";
    public static final String FIELD_OPEN_THREADS = "open_threads";
    /** Delta only: threads now closed, which move to the topics. */
    public static final String FIELD_CLOSED_THREADS = "closed_threads";
    public static final String FIELD_TOPICS = "topics";
    public static final String FIELD_QUESTIONS_ASKED = "questions_asked";
    /** An open thread in the stored document: {"text": ..., "since": millis}. */
    public static final String KEY_TEXT = "text";
    public static final String KEY_SINCE = "since";

    public static final String REFUSE_NOT_JSON = "the notes delta is not a JSON object";
    public static final String REFUSE_UNKNOWN_FIELD = "the notes delta has an unknown field";
    public static final String REFUSE_NOT_STRINGS = "a notes delta field is not a list of strings";
    public static final String REFUSE_ENTRY_TOO_LONG = "a notes entry is longer than " + MAX_ENTRY_CHARS + " characters";
    public static final String REFUSE_LINE_BREAK = "a notes entry contains a line break or control character";

    private static final Set<String> DELTA_FIELDS = new HashSet<String>(Arrays.asList(FIELD_INTERESTS,
            FIELD_OPEN_THREADS, FIELD_CLOSED_THREADS, FIELD_TOPICS, FIELD_QUESTIONS_ASKED));

    public static final PersonNotes EMPTY = new PersonNotes(new ArrayList<String>(), new ArrayList<Thread>(),
            new ArrayList<String>(), new ArrayList<String>());

    /** An open thread and roughly when it came up. */
    public static final class Thread {
        public final String text;
        public final long sinceMillis;

        public Thread(String text, long sinceMillis) {
            this.text = text;
            this.sinceMillis = sinceMillis;
        }
    }

    public final List<String> interests;
    public final List<Thread> openThreads;
    public final List<String> topics;
    public final List<String> questionsAsked;

    private PersonNotes(List<String> interests, List<Thread> openThreads, List<String> topics,
                        List<String> questionsAsked) {
        this.interests = Collections.unmodifiableList(interests);
        this.openThreads = Collections.unmodifiableList(openThreads);
        this.topics = Collections.unmodifiableList(topics);
        this.questionsAsked = Collections.unmodifiableList(questionsAsked);
    }

    public boolean isEmpty() {
        return interests.isEmpty() && openThreads.isEmpty() && topics.isEmpty() && questionsAsked.isEmpty();
    }

    /** True when a question with the same normalized text has been asked. */
    public boolean hasAsked(String question) {
        return contains(questionsAsked, normalize(question));
    }

    /**
     * The key entries are set-compared on: lower case, apostrophes and quotes
     * dropped, any other non-letter, non-digit run a single space, trimmed.
     * "What's your name?" and "whats your name" are the same question.
     */
    public static String normalize(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length());
        boolean space = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                if (space && out.length() > 0) {
                    out.append(' ');
                }
                space = false;
                out.append(Character.toLowerCase(c));
            } else if (c != '\'' && c != '’' && c != '"' && c != '“' && c != '”') {
                space = true;
            }
        }
        return out.toString();
    }

    /** The stored form of an entry: trimmed, runs of whitespace one space. */
    static String clean(String s) {
        StringBuilder out = new StringBuilder(s.length());
        boolean space = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                space = true;
                continue;
            }
            if (space && out.length() > 0) {
                out.append(' ');
            }
            space = false;
            out.append(c);
        }
        return out.toString();
    }

    /**
     * The stored document. Throws IllegalArgumentException for anything that
     * is not a document this class wrote (the store then treats the file as
     * empty).
     */
    public static PersonNotes parse(String json) {
        Object v = Json.parse(json == null ? "" : json);
        if (!(v instanceof Map)) {
            throw new IllegalArgumentException("not a notes document");
        }
        Map<?, ?> doc = (Map<?, ?>) v;
        List<Thread> threads = new ArrayList<Thread>();
        Object open = doc.get(FIELD_OPEN_THREADS);
        if (open != null) {
            if (!(open instanceof List)) {
                throw new IllegalArgumentException("open threads are not a list");
            }
            for (Object o : (List<?>) open) {
                if (!(o instanceof Map) || !(((Map<?, ?>) o).get(KEY_TEXT) instanceof String)
                        || !(((Map<?, ?>) o).get(KEY_SINCE) instanceof Number)) {
                    throw new IllegalArgumentException("malformed open thread");
                }
                threads.add(new Thread((String) ((Map<?, ?>) o).get(KEY_TEXT),
                        ((Number) ((Map<?, ?>) o).get(KEY_SINCE)).longValue()));
            }
        }
        return new PersonNotes(strings(doc.get(FIELD_INTERESTS)), threads, strings(doc.get(FIELD_TOPICS)),
                strings(doc.get(FIELD_QUESTIONS_ASKED)));
    }

    private static List<String> strings(Object v) {
        List<String> out = new ArrayList<String>();
        if (v == null) {
            return out;
        }
        if (!(v instanceof List)) {
            throw new IllegalArgumentException("field is not a list");
        }
        for (Object o : (List<?>) v) {
            if (!(o instanceof String)) {
                throw new IllegalArgumentException("entry is not a string");
            }
            out.add((String) o);
        }
        return out;
    }

    public String toJson() {
        Map<String, Object> doc = new LinkedHashMap<String, Object>();
        doc.put(FIELD_INTERESTS, interests);
        List<Map<String, Object>> open = new ArrayList<Map<String, Object>>();
        for (Thread t : openThreads) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put(KEY_TEXT, t.text);
            m.put(KEY_SINCE, t.sinceMillis);
            open.add(m);
        }
        doc.put(FIELD_OPEN_THREADS, open);
        doc.put(FIELD_TOPICS, topics);
        doc.put(FIELD_QUESTIONS_ASKED, questionsAsked);
        return Json.write(doc);
    }

    /** The document's size as the store keeps it. */
    public int byteLength() {
        return toJson().getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * This document with a delta merged in (KTD10). Validates the whole delta
     * first and throws IllegalArgumentException with a fixed REFUSE_* reason,
     * leaving this document untouched, when a field is unknown, a value is
     * not a list of strings, or an entry is over MAX_ENTRY_CHARS or holds a
     * line break. Blank entries are ignored. New open threads are stamped
     * nowMillis; an entry already present keeps its place and its stamp.
     */
    public PersonNotes merge(String deltaJson, long nowMillis) {
        Object v;
        try {
            v = Json.parse(deltaJson == null ? "" : deltaJson);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(REFUSE_NOT_JSON);
        }
        if (!(v instanceof Map)) {
            throw new IllegalArgumentException(REFUSE_NOT_JSON);
        }
        Map<String, List<String>> delta = new LinkedHashMap<String, List<String>>();
        for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
            String field = String.valueOf(e.getKey());
            if (!DELTA_FIELDS.contains(field)) {
                throw new IllegalArgumentException(REFUSE_UNKNOWN_FIELD);
            }
            delta.put(field, checkEntries(e.getValue()));
        }
        return merge(delta, nowMillis);
    }

    private static List<String> checkEntries(Object value) {
        if (!(value instanceof List)) {
            throw new IllegalArgumentException(REFUSE_NOT_STRINGS);
        }
        List<String> out = new ArrayList<String>();
        for (Object o : (List<?>) value) {
            if (!(o instanceof String)) {
                throw new IllegalArgumentException(REFUSE_NOT_STRINGS);
            }
            String s = (String) o;
            for (int i = 0; i < s.length(); i++) {
                if (Character.isISOControl(s.charAt(i))) {
                    throw new IllegalArgumentException(REFUSE_LINE_BREAK);
                }
            }
            String cleaned = clean(s);
            if (cleaned.length() > MAX_ENTRY_CHARS) {
                throw new IllegalArgumentException(REFUSE_ENTRY_TOO_LONG);
            }
            if (!cleaned.isEmpty()) {
                out.add(cleaned);
            }
        }
        return out;
    }

    private PersonNotes merge(Map<String, List<String>> delta, long nowMillis) {
        List<String> interests = new ArrayList<String>(this.interests);
        List<Thread> open = new ArrayList<Thread>(this.openThreads);
        List<String> topics = new ArrayList<String>(this.topics);
        List<String> questions = new ArrayList<String>(this.questionsAsked);

        addAll(interests, delta.get(FIELD_INTERESTS));
        // A closed thread leaves the open threads and joins the topics.
        List<String> closed = delta.get(FIELD_CLOSED_THREADS);
        if (closed != null) {
            for (String c : closed) {
                String key = normalize(c);
                for (int i = open.size() - 1; i >= 0; i--) {
                    if (normalize(open.get(i).text).equals(key)) {
                        open.remove(i);
                    }
                }
            }
            addAll(topics, closed);
        }
        List<String> opened = delta.get(FIELD_OPEN_THREADS);
        if (opened != null) {
            for (String text : opened) {
                String key = normalize(text);
                boolean present = false;
                for (Thread t : open) {
                    present |= normalize(t.text).equals(key);
                }
                if (!present) {
                    open.add(new Thread(text, nowMillis));
                }
            }
        }
        addAll(topics, delta.get(FIELD_TOPICS));
        addAll(questions, delta.get(FIELD_QUESTIONS_ASKED));

        // Per-list caps: oldest first (open threads: the closed ones already left).
        trimFront(interests, MAX_INTERESTS);
        while (open.size() > MAX_OPEN_THREADS) {
            removeOldest(open);
        }
        trimFront(topics, MAX_TOPICS);
        trimFront(questions, MAX_QUESTIONS_ASKED);

        // The document cap binds last, in the named order.
        PersonNotes merged = new PersonNotes(interests, open, topics, questions);
        while (merged.byteLength() > MAX_DOCUMENT_BYTES) {
            if (!topics.isEmpty()) {
                topics.remove(0);
            } else if (!open.isEmpty()) {
                removeOldest(open);
            } else if (!interests.isEmpty()) {
                interests.remove(0);
            } else if (!questions.isEmpty()) {
                questions.remove(0);
            } else {
                break;
            }
            merged = new PersonNotes(interests, open, topics, questions);
        }
        return merged;
    }

    private static void addAll(List<String> list, List<String> entries) {
        if (entries == null) {
            return;
        }
        for (String e : entries) {
            if (!contains(list, normalize(e))) {
                list.add(e);
            }
        }
    }

    private static boolean contains(List<String> list, String normalizedKey) {
        for (String s : list) {
            if (normalize(s).equals(normalizedKey)) {
                return true;
            }
        }
        return false;
    }

    private static void trimFront(List<String> list, int cap) {
        while (list.size() > cap) {
            list.remove(0);
        }
    }

    private static void removeOldest(List<Thread> open) {
        int oldest = 0;
        for (int i = 1; i < open.size(); i++) {
            if (open.get(i).sinceMillis < open.get(oldest).sinceMillis) {
                oldest = i;
            }
        }
        open.remove(oldest);
    }

    @Override
    public String toString() {
        return "PersonNotes{interests=" + interests.size() + ", open=" + openThreads.size() + ", topics="
                + topics.size() + ", asked=" + questionsAsked.size() + "}";
    }
}
