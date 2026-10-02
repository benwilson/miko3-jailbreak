package com.miko3.shared;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The owner's notes about people by name (owner 2026-10-03): short notes the
 * owner writes on the Settings page, one per person, keyed by the name as the
 * owner typed it. Explore asks for the note of the person it is talking to
 * (noteFor) and follows it for how to approach them; the note itself is never
 * said, logged or shown anywhere but the Settings page.
 *
 * Each entry has a stable id (digits, never reused within a document), a name
 * (trimmed, whitespace collapsed, 1 to MAX_NAME_CHARS) and a note (trimmed,
 * 1 to MAX_NOTE_CHARS). add() and edit() throw IllegalArgumentException with
 * a fixed REFUSE_* reason, never echoing what was typed.
 *
 * Matching (noteFor), case-insensitive with whitespace collapsed: an exact
 * full-name match wins; otherwise the asked name's first word must match the
 * first word of exactly one entry, and either the asked name or that entry is
 * a single word ("Priya" finds "Priya Shah", "Priya Shah" finds "Priya", but
 * "Priya Jones" never finds "Priya Shah"). Ambiguous or none: null.
 *
 * Plain Java (no android.*), not thread-safe: OwnerNotesStore guards it.
 */
public final class OwnerNotes {
    public static final int MAX_NOTE_CHARS = 600;
    public static final int MAX_NAME_CHARS = 80;
    public static final int MAX_ENTRIES = 200;

    public static final String REFUSE_NAME = "a note needs the person's name (at most " + MAX_NAME_CHARS
            + " characters)";
    public static final String REFUSE_NOTE_EMPTY = "the note is empty";
    public static final String REFUSE_NOTE_TOO_LONG = "the note is longer than " + MAX_NOTE_CHARS + " characters";
    public static final String REFUSE_FULL = "there are already " + MAX_ENTRIES + " notes";

    public static final class Entry {
        public final String id;
        public final String name;
        public final String note;

        Entry(String id, String name, String note) {
            this.id = id;
            this.name = name;
            this.note = note;
        }

        /** Never the name or the note. */
        @Override
        public String toString() {
            return "OwnerNotes.Entry(" + id + ")";
        }
    }

    private final List<Entry> entries = new ArrayList<Entry>();
    private long next = 1;

    public OwnerNotes() {
    }

    /** A copy, so a refused save can be dropped whole. */
    public OwnerNotes copy() {
        OwnerNotes c = new OwnerNotes();
        c.entries.addAll(entries);
        c.next = next;
        return c;
    }

    public static boolean isValidId(String id) {
        return id != null && id.matches("[0-9]{1,18}");
    }

    /** Adds an entry; answers its id. */
    public String add(String name, String note) {
        String n = cleanName(name);
        String t = cleanNote(note);
        if (entries.size() >= MAX_ENTRIES) {
            throw new IllegalArgumentException(REFUSE_FULL);
        }
        String id = String.valueOf(next++);
        entries.add(new Entry(id, n, t));
        return id;
    }

    /** Replaces an entry's name and note; false for an unknown id. */
    public boolean edit(String id, String name, String note) {
        int i = indexOf(id);
        if (i < 0) {
            return false;
        }
        entries.set(i, new Entry(id, cleanName(name), cleanNote(note)));
        return true;
    }

    public boolean delete(String id) {
        int i = indexOf(id);
        if (i < 0) {
            return false;
        }
        entries.remove(i);
        return true;
    }

    public Entry get(String id) {
        int i = indexOf(id);
        return i < 0 ? null : entries.get(i);
    }

    /** Every entry, in the order added. */
    public List<Entry> all() {
        return Collections.unmodifiableList(new ArrayList<Entry>(entries));
    }

    /** The note for the person with this name, or null (see the class comment for the rules). */
    public String noteFor(String name) {
        String asked = normalize(name);
        if (asked.isEmpty()) {
            return null;
        }
        for (Entry e : entries) {
            if (normalize(e.name).equals(asked)) {
                return e.note;
            }
        }
        String first = firstWord(asked);
        Entry only = null;
        int count = 0;
        for (Entry e : entries) {
            if (firstWord(normalize(e.name)).equals(first)) {
                only = e;
                count++;
            }
        }
        if (count != 1) {
            return null;
        }
        boolean askedSingle = asked.indexOf(' ') < 0;
        boolean entrySingle = normalize(only.name).indexOf(' ') < 0;
        return askedSingle || entrySingle ? only.note : null;
    }

    public String toJson() {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("next", next);
        List<Object> list = new ArrayList<Object>();
        for (Entry e : entries) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("id", e.id);
            m.put("name", e.name);
            m.put("note", e.note);
            list.add(m);
        }
        root.put("entries", list);
        return Json.write(root);
    }

    /** The document in this JSON; anything unreadable is no notes, and a bad entry is skipped. */
    public static OwnerNotes fromJson(String json) {
        OwnerNotes out = new OwnerNotes();
        if (json == null) {
            return out;
        }
        Object v;
        try {
            v = Json.parse(json);
        } catch (RuntimeException e) {
            return out;
        }
        if (!(v instanceof Map)) {
            return out;
        }
        Map<?, ?> root = (Map<?, ?>) v;
        Object list = root.get("entries");
        long maxId = 0;
        if (list instanceof List) {
            for (Object o : (List<?>) list) {
                if (!(o instanceof Map) || out.entries.size() >= MAX_ENTRIES) {
                    continue;
                }
                Map<?, ?> m = (Map<?, ?>) o;
                Object id = m.get("id");
                Object name = m.get("name");
                Object note = m.get("note");
                if (!(id instanceof String) || !isValidId((String) id) || !(name instanceof String)
                        || !(note instanceof String) || out.indexOf((String) id) >= 0) {
                    continue;
                }
                try {
                    out.entries.add(new Entry((String) id, cleanName((String) name), cleanNote((String) note)));
                    maxId = Math.max(maxId, Long.parseLong((String) id));
                } catch (IllegalArgumentException e) {
                    // Skipped: the page never stores such an entry.
                }
            }
        }
        Object next = root.get("next");
        long n = next instanceof Number ? ((Number) next).longValue() : 1;
        out.next = Math.max(n, maxId + 1);
        return out;
    }

    private int indexOf(String id) {
        if (id == null) {
            return -1;
        }
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).id.equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private static String cleanName(String name) {
        String n = name == null ? "" : name.replaceAll("\\s+", " ").trim();
        if (n.isEmpty() || n.length() > MAX_NAME_CHARS) {
            throw new IllegalArgumentException(REFUSE_NAME);
        }
        return n;
    }

    private static String cleanNote(String note) {
        String t = note == null ? "" : note.trim();
        if (t.isEmpty()) {
            throw new IllegalArgumentException(REFUSE_NOTE_EMPTY);
        }
        if (t.length() > MAX_NOTE_CHARS) {
            throw new IllegalArgumentException(REFUSE_NOTE_TOO_LONG);
        }
        return t;
    }

    static String normalize(String name) {
        return name == null ? "" : name.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private static String firstWord(String normalized) {
        int sp = normalized.indexOf(' ');
        return sp < 0 ? normalized : normalized.substring(0, sp);
    }
}
