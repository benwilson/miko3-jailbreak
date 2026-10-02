package com.miko3.launcher;

import com.miko3.shared.Feedback;
import com.miko3.shared.Json;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The robot's feedback log (owner 2026-10-02): what people told him about
 * himself in conversations (suggestions, complaints, praise, bugs), so the
 * owner can act on it. Append-only with a cap of MAX_ENTRIES, the oldest
 * dropped first. Each entry is the time, the Feedback (kind, neutral summary,
 * short verbatim quote), who (a known person's first name, else "someone"),
 * and a few words of context. Nothing else from the conversation is kept.
 *
 * The person's id is kept beside an entry only so that forgetting them
 * (PeopleStore.forget) deletes their entries; it is never shown. Owned by the
 * launcher's PeopleStore and stored as one JSON object per line in the
 * people directory, rewritten whole (temp file, fsync, rename) on each
 * change, which at 200 short entries is a few tens of KB. Thread-safe, plain
 * Java, never logs.
 */
final class FeedbackStore {
    static final String FILE = "feedback.jsonl";
    static final int MAX_ENTRIES = 200;
    static final String SOMEONE = "someone";
    /** A line longer than this is not one of ours; it is skipped on load. */
    private static final int MAX_LINE_CHARS = 4096;

    static final class Entry {
        final long atMillis;
        final String kind;
        final String summary;
        final String quote;
        final String who;
        final String context;
        /** The stored person's id, or "" for someone unknown. Never displayed. */
        final String personId;

        Entry(long atMillis, Feedback f, String who, String context, String personId) {
            this.atMillis = atMillis;
            this.kind = f.kind;
            this.summary = f.summary;
            this.quote = f.quote;
            this.who = who;
            this.context = context;
            this.personId = personId;
        }
    }

    private final File file;
    private final PeopleStore.Clock clock;
    // Oldest first.
    private final List<Entry> entries = new ArrayList<Entry>();

    FeedbackStore(File file, PeopleStore.Clock clock) {
        this.file = file;
        this.clock = clock;
        load();
    }

    /** "someone" unless name is a non-blank stored name; then its first word. */
    static String whoOf(String name) {
        String n = Feedback.clean(name, PeopleStore.MAX_NAME_CHARS);
        return n.isEmpty() ? SOMEONE : n.split(" ")[0];
    }

    /** Appends one entry, now, dropping the oldest past the cap. False for no feedback or a failed write. */
    synchronized boolean record(Feedback f, String who, String personId, String context) {
        if (f == null) {
            return false;
        }
        String w = who == null || who.trim().isEmpty() ? SOMEONE : Feedback.clean(who, PeopleStore.MAX_NAME_CHARS);
        String id = PeopleStore.isValidId(personId) ? personId : "";
        entries.add(new Entry(clock.nowMillis(), f, w, Feedback.cleanContext(context), id));
        while (entries.size() > MAX_ENTRIES) {
            entries.remove(0);
        }
        return save();
    }

    /** Every entry, newest first. */
    synchronized List<Entry> newestFirst() {
        List<Entry> out = new ArrayList<Entry>(entries);
        Collections.reverse(out);
        return out;
    }

    synchronized int size() {
        return entries.size();
    }

    /** Deletes every entry; answers how many there were. */
    synchronized int clear() {
        int n = entries.size();
        entries.clear();
        save();
        return n;
    }

    /** Forget-me: deletes every entry from this person; answers how many. */
    synchronized int purgePerson(String id) {
        if (id == null || id.isEmpty()) {
            return 0;
        }
        int removed = 0;
        for (Iterator<Entry> it = entries.iterator(); it.hasNext(); ) {
            if (id.equals(it.next().personId)) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            save();
        }
        return removed;
    }

    private void load() {
        if (!file.isFile()) {
            return;
        }
        BufferedReader in = null;
        try {
            in = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
            String line;
            while ((line = in.readLine()) != null) {
                Entry e = parse(line);
                if (e != null) {
                    entries.add(e);
                }
            }
        } catch (IOException e) {
            // Keep what was read; the next write replaces the file whole.
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // Nothing to do.
                }
            }
        }
        while (entries.size() > MAX_ENTRIES) {
            entries.remove(0);
        }
    }

    /** One stored line, re-checked with Feedback's rules; null for anything malformed. */
    private static Entry parse(String line) {
        if (line.length() > MAX_LINE_CHARS) {
            return null;
        }
        Object v;
        try {
            v = Json.parse(line);
        } catch (IllegalArgumentException e) {
            return null;
        }
        Feedback f = Feedback.fromJson(v);
        if (f == null) {
            return null;
        }
        Map<?, ?> m = (Map<?, ?>) v;
        Object at = m.get("at");
        Object who = m.get("who");
        Object context = m.get("context");
        Object id = m.get("person");
        long atMillis = at instanceof Number ? ((Number) at).longValue() : 0;
        String w = who instanceof String && !((String) who).trim().isEmpty()
                ? Feedback.clean((String) who, PeopleStore.MAX_NAME_CHARS) : SOMEONE;
        String pid = id instanceof String && PeopleStore.isValidId((String) id) ? (String) id : "";
        return new Entry(atMillis, f, w, context instanceof String ? Feedback.cleanContext((String) context) : "", pid);
    }

    private boolean save() {
        StringBuilder out = new StringBuilder();
        for (Entry e : entries) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("at", e.atMillis);
            m.put("kind", e.kind);
            m.put("summary", e.summary);
            m.put("quote", e.quote);
            m.put("who", e.who);
            m.put("context", e.context);
            m.put("person", e.personId);
            out.append(Json.write(m)).append('\n');
        }
        try {
            File dir = file.getParentFile();
            if (dir != null) {
                dir.mkdirs();
            }
            File tmp = new File(dir, file.getName() + ".tmp");
            FileOutputStream os = new FileOutputStream(tmp);
            try {
                os.write(out.toString().getBytes(StandardCharsets.UTF_8));
                os.flush();
                os.getFD().sync();
            } finally {
                os.close();
            }
            if (!tmp.renameTo(file)) {
                tmp.delete();
                return false;
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
