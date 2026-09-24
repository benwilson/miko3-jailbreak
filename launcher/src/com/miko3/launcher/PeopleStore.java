package com.miko3.launcher;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The people the robot remembers (explore-on-claude plan U2; R13-R16, KTD1,
 * KTD3): for each person an id, a name ("" when unnamed), when he last saw
 * them, and one 224 px face JPEG.
 *
 * Lives in one launcher-private directory (LauncherApp passes
 * getFilesDir()/people): a face file "<id>.jpg" per person, plus an index
 * file with one line per person, most recently seen first. Every change
 * rewrites the index through a temp file that is fsynced and then renamed
 * over the old one, so a power cut leaves either the old index or the new
 * one, never half of one. A face file is fsynced before the index names it.
 * Faces stay here until the owner forgets that person (R13); nothing expires.
 *
 * Ids are 16 lower-case hex digits from SecureRandom. Every method that takes
 * an id checks it against that shape before touching the file system, so an
 * id from a URL (the Settings page's face GET) can never name another file.
 *
 * Plain Java (no android.*), so scripts/tests runs it on the host JVM over a
 * temp directory. Thread-safe: every public method is synchronized, since the
 * Binder service and the web server call it from their own threads.
 */
final class PeopleStore {
    static final String INDEX_FILE = "people.index";
    static final int MAX_NAME_CHARS = 40;
    /** A 224x224 face JPEG is about 15 KB; this cap keeps ten of them (KTD3)
     * far below Binder's 1 MB transaction buffer. */
    static final int MAX_FACE_BYTES = 40 * 1024;

    static final String REFUSE_NOT_JPEG = "that face is not a JPEG";
    static final String REFUSE_TOO_BIG = "that face image is too large";
    static final String REFUSE_NOT_SAVED = "the face could not be saved";

    private static final Pattern ID = Pattern.compile("[0-9a-f]{16}");

    interface Clock {
        long nowMillis();
    }

    /** One remembered person, as of the call that returned it. */
    static final class Person {
        final String id;
        /** "" when unnamed. */
        final String name;
        final long lastSeenMillis;

        Person(String id, String name, long lastSeenMillis) {
            this.id = id;
            this.name = name;
            this.lastSeenMillis = lastSeenMillis;
        }
    }

    private final File dir;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    // Most recently seen first.
    private final List<Person> people = new ArrayList<Person>();

    PeopleStore(File dir, Clock clock) {
        this.dir = dir;
        this.clock = clock;
        load();
    }

    /** True for the only id shape the store ever issues. */
    static boolean isValidId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    /** Trimmed, control characters turned into spaces and runs of spaces
     * collapsed, capped at MAX_NAME_CHARS; "" for null or blank. */
    static String cleanName(String name) {
        if (name == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(name.length());
        boolean space = false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
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
        return s.length() > MAX_NAME_CHARS ? s.substring(0, MAX_NAME_CHARS).trim() : s;
    }

    /**
     * Remembers a new person, seen now, at the front of the list. Returns
     * their id. Throws IllegalArgumentException with one of the fixed
     * REFUSE_* reasons when the face isn't a JPEG, is too large, or can't be
     * written.
     */
    synchronized String add(byte[] faceJpeg, String nameOrNull) {
        if (faceJpeg == null || faceJpeg.length < 4 || (faceJpeg[0] & 0xff) != 0xFF
                || (faceJpeg[1] & 0xff) != 0xD8) {
            throw new IllegalArgumentException(REFUSE_NOT_JPEG);
        }
        if (faceJpeg.length > MAX_FACE_BYTES) {
            throw new IllegalArgumentException(REFUSE_TOO_BIG);
        }
        String id = newId();
        File face = faceFile(id);
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new IOException("no people directory");
            }
            writeDurably(face, faceJpeg);
            people.add(0, new Person(id, cleanName(nameOrNull), clock.nowMillis()));
            saveIndex();
        } catch (IOException e) {
            remove(id);
            face.delete();
            throw new IllegalArgumentException(REFUSE_NOT_SAVED);
        }
        return id;
    }

    /** Marks a person seen now and moves them to the front. False if unknown. */
    synchronized boolean touch(String id) {
        int i = indexOf(id);
        if (i < 0) {
            return false;
        }
        Person p = people.remove(i);
        people.add(0, new Person(p.id, p.name, clock.nowMillis()));
        return saveIndexQuietly();
    }

    /** Changes the name he uses next time; a blank name makes them unnamed.
     * Keeps their place in the list. False if unknown. */
    synchronized boolean rename(String id, String name) {
        int i = indexOf(id);
        if (i < 0) {
            return false;
        }
        Person p = people.get(i);
        people.set(i, new Person(p.id, cleanName(name), p.lastSeenMillis));
        return saveIndexQuietly();
    }

    /** Deletes a person's face and name for good (R16, AE6). False if unknown. */
    synchronized boolean forget(String id) {
        if (indexOf(id) < 0) {
            return false;
        }
        remove(id);
        // Index first: once it no longer names them, a failed delete leaves
        // only an orphan file, which load() never reads.
        saveIndexQuietly();
        faceFile(id).delete();
        return true;
    }

    /** The name, "" when unnamed, or null when there is no such person. */
    synchronized String nameOf(String id) {
        int i = indexOf(id);
        return i < 0 ? null : people.get(i).name;
    }

    /** The face JPEG, or null for an unknown or malformed id. */
    synchronized byte[] face(String id) {
        if (indexOf(id) < 0) {
            return null;
        }
        try {
            return readAll(faceFile(id), MAX_FACE_BYTES);
        } catch (IOException e) {
            return null;
        }
    }

    /** Everyone, most recently seen first. */
    synchronized List<Person> all() {
        return Collections.unmodifiableList(new ArrayList<Person>(people));
    }

    /** The n most recently seen (R14); empty for n <= 0. */
    synchronized List<Person> recent(int n) {
        if (n <= 0) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<Person>(people.subList(0, Math.min(n, people.size()))));
    }

    private int indexOf(String id) {
        if (!isValidId(id)) {
            return -1;
        }
        for (int i = 0; i < people.size(); i++) {
            if (people.get(i).id.equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private void remove(String id) {
        int i = indexOf(id);
        if (i >= 0) {
            people.remove(i);
        }
    }

    private String newId() {
        while (true) {
            long v = random.nextLong();
            String id = String.format(Locale.ROOT, "%016x", v);
            if (indexOf(id) < 0 && !faceFile(id).exists()) {
                return id;
            }
        }
    }

    private File faceFile(String id) {
        return new File(dir, id + ".jpg");
    }

    /** Reads the index; skips any line that is malformed or whose face file
     * is missing (a hand edit, or a crash between writing a face and the index). */
    private void load() {
        File index = new File(dir, INDEX_FILE);
        if (!index.isFile()) {
            return;
        }
        BufferedReader in = null;
        try {
            in = new BufferedReader(new InputStreamReader(new FileInputStream(index), StandardCharsets.UTF_8));
            String line;
            while ((line = in.readLine()) != null) {
                String[] f = line.split("\t", -1);
                if (f.length != 3 || !isValidId(f[0]) || indexOf(f[0]) >= 0 || !faceFile(f[0]).isFile()) {
                    continue;
                }
                long seen;
                try {
                    seen = Long.parseLong(f[1]);
                } catch (NumberFormatException e) {
                    continue;
                }
                people.add(new Person(f[0], cleanName(f[2]), seen));
            }
        } catch (IOException e) {
            // Keep whatever was read; the next write replaces the index whole.
        } finally {
            closeQuietly(in);
        }
    }

    private boolean saveIndexQuietly() {
        try {
            saveIndex();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void saveIndex() throws IOException {
        StringBuilder out = new StringBuilder();
        for (Person p : people) {
            // cleanName() leaves no tab or newline in a name.
            out.append(p.id).append('\t').append(p.lastSeenMillis).append('\t').append(p.name).append('\n');
        }
        writeDurably(new File(dir, INDEX_FILE), out.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Writes a temp file, fsyncs it, and renames it over target. */
    private static void writeDurably(File target, byte[] bytes) throws IOException {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            out.write(bytes);
            out.flush();
            out.getFD().sync();
        } finally {
            out.close();
        }
        if (!tmp.renameTo(target)) {
            tmp.delete();
            throw new IOException("rename failed");
        }
    }

    private static byte[] readAll(File f, int max) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                if (out.size() > max) {
                    throw new IOException("face file too large");
                }
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private static void closeQuietly(BufferedReader in) {
        if (in != null) {
            try {
                in.close();
            } catch (IOException ignored) {
                // Nothing to do.
            }
        }
    }
}
