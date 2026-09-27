package com.miko3.launcher;

import com.miko3.shared.PersonNotes;

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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The people the robot remembers (explore-on-claude plan U2; R13-R16, KTD1,
 * KTD3): for each person an id, a name ("" when unnamed), when he last saw
 * them, one 224 px face JPEG, and (meeting plan U5, KTD10) their notes.
 *
 * Lives in one launcher-private directory (LauncherApp passes
 * getFilesDir()/people): a face file "<id>.jpg" per person, a notes file
 * "<id>.json" once they have notes, plus an index file with one line per
 * person, most recently seen first. Every change rewrites the index through
 * a temp file that is fsynced and then renamed over the old one, so a power
 * cut leaves either the old index or the new one, never half of one. A face
 * file is fsynced before the index names it, and the notes file is written
 * the same way. Forget rewrites the index, then deletes the notes, then the
 * face, so a crash leaves only orphan files, and load() deletes any face or
 * notes file whose id the index does not name. Faces stay here until the
 * owner forgets that person (R13); nothing expires.
 *
 * Notes (KTD10): notes() answers PersonNotes.EMPTY for an unknown id, a
 * missing file or a malformed one; mergeNotes() validates the delta through
 * PersonNotes and refuses an unknown id, leaving the document untouched.
 * The gallery Explore matches against, recent(), skips records with no name
 * (legacy records from before names were required); all() still lists them
 * for the People page.
 *
 * Ids are 16 lower-case hex digits from SecureRandom. Every method that takes
 * an id checks it against that shape before touching the file system, so an
 * id from a URL (the Settings page's face GET) can never name another file.
 *
 * Photos and embeddings (face plan U4; R1, R5, R8, R9, KTD4, KTD10-KTD12):
 * each person keeps up to MAX_PHOTOS photos. Slot 0 is the "<id>.jpg" every
 * person already has (R9); slots 1 to 4 are "<id>-<n>.jpg". One "<id>.faces"
 * file holds, per slot, when the photo was added, whether the mode found it
 * unusable (no face in it, KTD11), and the embedding the mode computed with
 * the id of the model that computed it. The launcher never runs a model: it
 * stores what the mode sends and serves it back by id, never with a name. A
 * photo with no .faces entry (a legacy one, or one a crash left behind) is
 * pending, added at 0, which also makes it the first to be replaced. The
 * .faces file never keeps an entry for a photo that is gone: a replacement
 * drops the slot's entry, writes the photo, then writes the new entry, and
 * a delete drops the entry before the photo, so a crash leaves at worst a
 * pending photo, never one paired with another photo's embedding.
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
    static final String REFUSE_UNKNOWN_PERSON = "that person is not remembered";
    static final String REFUSE_NOTES_NOT_SAVED = "the notes could not be saved";
    /** Nobody is stored without a name (R19, meeting plan line 309). */
    static final String REFUSE_NO_NAME = "a person needs a name to be remembered";
    static final String REFUSE_BAD_EMBEDDING = "that face embedding is not usable";
    static final String REFUSE_LAST_PHOTO = "a person keeps at least one photo";

    /** Photo slots per person (R5); slot 0 is the legacy "<id>.jpg". */
    static final int MAX_PHOTOS = 5;
    /** SFace answers 128; the cap is for any model and keeps the gallery reply
     * (RobotPeople.MAX_GALLERY entries) far below Binder's 1 MB buffer. */
    static final int MAX_EMBEDDING_FLOATS = 256;
    static final String FACES_SUFFIX = ".faces";

    /** A notes file larger than this (a hand edit) reads as empty. */
    private static final int MAX_NOTES_FILE_BYTES = 4 * PersonNotes.MAX_DOCUMENT_BYTES;

    /** First line of a .faces file; anything else reads as all pending. */
    private static final String FACES_HEADER = "faces1";
    private static final int MAX_FACES_FILE_BYTES = 64 * 1024;

    private static final Pattern ID = Pattern.compile("[0-9a-f]{16}");
    /** A model id is a short token: it is stored on a tab-separated line. */
    private static final Pattern MODEL_ID = Pattern.compile("[A-Za-z0-9._:-]{1,64}");
    /** The files this store owns, named by an id: slot 0's photo, slots 1-4
     * (MAX_PHOTOS - 1), the notes, and the .faces file. */
    private static final Pattern OWNED_FILE = Pattern.compile("([0-9a-f]{16})(?:(?:-[1-4])?\\.jpg|\\.json|\\.faces)");

    interface Clock {
        long nowMillis();
    }

    /** Opens the index for reading: the file itself, or a stream a host test
     * makes fail partway to prove a torn read never triggers the sweep. */
    interface IndexOpener {
        InputStream open(File index) throws IOException;
    }

    private static final IndexOpener FILE_OPENER = new IndexOpener() {
        @Override
        public InputStream open(File index) throws IOException {
            return new FileInputStream(index);
        }
    };

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

    /**
     * One stored photo of a person, as of the call that returned it: its id
     * and slot, never a name (KTD10). A photo is pending (R18) until it has
     * an embedding from the current model or is marked unusable.
     */
    static final class Photo {
        final String id;
        final int slot;
        /** 0 for a photo whose added-at time was never recorded (R9). */
        final long addedAtMillis;
        /** The mode found no face in it (KTD11): never a match exemplar. */
        final boolean unusable;
        /** "" when there is no embedding. */
        final String modelId;
        /** null when there is no embedding. */
        final float[] embedding;

        Photo(String id, int slot, long addedAtMillis, boolean unusable, String modelId, float[] embedding) {
            this.id = id;
            this.slot = slot;
            this.addedAtMillis = addedAtMillis;
            this.unusable = unusable;
            this.modelId = modelId;
            this.embedding = embedding;
        }

        /** True while this photo still waits for an embedding from currentModelId. */
        boolean isPending(String currentModelId) {
            return !unusable && (embedding == null || !modelId.equals(currentModelId));
        }
    }

    /** A slot's .faces entry in memory; null in a slot array means no photo. */
    private static final class Entry {
        final long addedAtMillis;
        final boolean unusable;
        final String modelId;
        final float[] embedding;

        Entry(long addedAtMillis, boolean unusable, String modelId, float[] embedding) {
            this.addedAtMillis = addedAtMillis;
            this.unusable = unusable;
            this.modelId = modelId;
            this.embedding = embedding;
        }
    }

    private static final Entry PENDING_LEGACY = new Entry(0, false, "", null);

    private final File dir;
    private final Clock clock;
    private final IndexOpener indexOpener;
    private final SecureRandom random = new SecureRandom();
    // Most recently seen first.
    private final List<Person> people = new ArrayList<Person>();
    // Each person's MAX_PHOTOS slots; a null slot has no photo.
    private final Map<String, Entry[]> slots = new HashMap<String, Entry[]>();

    PeopleStore(File dir, Clock clock) {
        this(dir, clock, FILE_OPENER);
    }

    PeopleStore(File dir, Clock clock, IndexOpener indexOpener) {
        this.dir = dir;
        this.clock = clock;
        this.indexOpener = indexOpener;
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
     * REFUSE_* reasons when the face isn't a JPEG, is too large, has no name
     * (R19: nothing is written then), or can't be written.
     */
    synchronized String add(byte[] faceJpeg, String name) {
        checkJpeg(faceJpeg);
        String cleanName = cleanName(name);
        if (cleanName.isEmpty()) {
            throw new IllegalArgumentException(REFUSE_NO_NAME);
        }
        String id = newId();
        File face = faceFile(id);
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new IOException("no people directory");
            }
            writeDurably(face, faceJpeg);
            // No .faces file: the photo waits for the mode's embedding (R18).
            Entry[] e = new Entry[MAX_PHOTOS];
            e[0] = PENDING_LEGACY;
            slots.put(id, e);
            people.add(0, new Person(id, cleanName, clock.nowMillis()));
            saveIndex();
        } catch (IOException e) {
            remove(id);
            face.delete();
            throw new IllegalArgumentException(REFUSE_NOT_SAVED);
        }
        return id;
    }

    /**
     * Remembers a new person, seen now, with their first photo in slot 0 and
     * its embedding (KTD12), and answers their id. The photo and the .faces
     * file are written before the index names the person, so a crash leaves
     * only orphans that load() sweeps. Throws IllegalArgumentException with a
     * fixed REFUSE_* reason, having written nothing, for a bad photo, a
     * missing name, or a bad model id or embedding.
     */
    synchronized String addPerson(byte[] faceJpeg, String name, String modelId, float[] embedding) {
        checkJpeg(faceJpeg);
        String cleanName = cleanName(name);
        if (cleanName.isEmpty()) {
            throw new IllegalArgumentException(REFUSE_NO_NAME);
        }
        checkEmbedding(modelId, embedding);
        String id = newId();
        Entry[] e = new Entry[MAX_PHOTOS];
        e[0] = new Entry(clock.nowMillis(), false, modelId, embedding.clone());
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new IOException("no people directory");
            }
            writeDurably(faceFile(id), faceJpeg);
            writeFaces(id, e);
            slots.put(id, e);
            people.add(0, new Person(id, cleanName, clock.nowMillis()));
            saveIndex();
        } catch (IOException ex) {
            remove(id);
            facesFile(id).delete();
            faceFile(id).delete();
            throw new IllegalArgumentException(REFUSE_NOT_SAVED);
        }
        return id;
    }

    /**
     * Adds a photo and its embedding to a remembered person (R5) and answers
     * the slot it took: the lowest empty slot, or else the one added longest
     * ago (slot 0 gets no protection). Refuses an id the index does not name
     * with REFUSE_UNKNOWN_PERSON, writing nothing, so a "yes" that races a
     * forget cannot bring the person back (KTD12). Other refusals as addPerson.
     */
    synchronized int addPhoto(String id, byte[] faceJpeg, String modelId, float[] embedding) {
        if (indexOf(id) < 0) {
            throw new IllegalArgumentException(REFUSE_UNKNOWN_PERSON);
        }
        checkJpeg(faceJpeg);
        checkEmbedding(modelId, embedding);
        Entry[] e = slots.get(id).clone();
        int slot = -1;
        for (int i = 0; i < MAX_PHOTOS && slot < 0; i++) {
            if (e[i] == null) {
                slot = i;
            }
        }
        if (slot < 0) {
            slot = 0;
            for (int i = 1; i < MAX_PHOTOS; i++) {
                if (e[i].addedAtMillis < e[slot].addedAtMillis) {
                    slot = i;
                }
            }
        }
        try {
            if (e[slot] != null) {
                // Drop the old entry first: a crash below must not pair the
                // new photo with the old photo's embedding.
                e[slot] = null;
                writeFaces(id, e);
            }
            writeDurably(photoFile(id, slot), faceJpeg);
            e[slot] = new Entry(clock.nowMillis(), false, modelId, embedding.clone());
            writeFaces(id, e);
            slots.put(id, e);
        } catch (IOException ex) {
            // Whatever reached the disk is what the store now holds.
            slots.put(id, readSlots(id));
            throw new IllegalArgumentException(REFUSE_NOT_SAVED);
        }
        return slot;
    }

    /**
     * Stores the embedding the mode computed for a photo it fetched (KTD11
     * migration) and clears any unusable mark. False, writing nothing, when
     * the person is gone or the slot no longer holds the photo added at
     * addedAtMillis (replaced or deleted since the mode fetched it), or the
     * .faces file can't be written. IllegalArgumentException with
     * REFUSE_BAD_EMBEDDING for a bad model id or embedding.
     */
    synchronized boolean setEmbedding(String id, int slot, long addedAtMillis, String modelId, float[] embedding) {
        checkEmbedding(modelId, embedding);
        return replaceEntry(id, slot, addedAtMillis, new Entry(addedAtMillis, false, modelId, embedding.clone()));
    }

    /** Marks a photo in which the mode found no face (KTD11): it gets no
     * embedding and stops counting as pending. Refused as setEmbedding. */
    synchronized boolean markUnusable(String id, int slot, long addedAtMillis) {
        return replaceEntry(id, slot, addedAtMillis, new Entry(addedAtMillis, true, "", null));
    }

    private boolean replaceEntry(String id, int slot, long addedAtMillis, Entry entry) {
        if (indexOf(id) < 0 || slot < 0 || slot >= MAX_PHOTOS) {
            return false;
        }
        Entry[] e = slots.get(id).clone();
        if (e[slot] == null || e[slot].addedAtMillis != addedAtMillis) {
            return false;
        }
        e[slot] = entry;
        try {
            writeFaces(id, e);
        } catch (IOException ex) {
            return false;
        }
        slots.put(id, e);
        return true;
    }

    /**
     * Deletes one photo and its embedding (the People page). False for an
     * unknown id or an empty slot; IllegalArgumentException with
     * REFUSE_LAST_PHOTO for the person's only photo, since a person with no
     * photo would not load again. The entry goes before the photo, so a
     * crash leaves the photo back as pending, not an entry with no photo.
     */
    synchronized boolean deletePhoto(String id, int slot) {
        if (indexOf(id) < 0 || slot < 0 || slot >= MAX_PHOTOS) {
            return false;
        }
        Entry[] e = slots.get(id).clone();
        if (e[slot] == null) {
            return false;
        }
        int count = 0;
        for (Entry x : e) {
            count += x == null ? 0 : 1;
        }
        if (count <= 1) {
            throw new IllegalArgumentException(REFUSE_LAST_PHOTO);
        }
        e[slot] = null;
        try {
            writeFaces(id, e);
        } catch (IOException ex) {
            return false;
        }
        slots.put(id, e);
        photoFile(id, slot).delete();
        return true;
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

    /** Deletes a person's photos, embeddings, name and notes for good (R16,
     * R18, AE6; face plan R8). False if unknown. */
    synchronized boolean forget(String id) {
        if (indexOf(id) < 0) {
            return false;
        }
        remove(id);
        // Index first: once it no longer names them, a failed delete leaves
        // only orphan files, which load() never reads and sweeps away (KTD10).
        saveIndexQuietly();
        notesFile(id).delete();
        facesFile(id).delete();
        faceFile(id).delete();
        for (int slot = 1; slot < MAX_PHOTOS; slot++) {
            photoFile(id, slot).delete();
        }
        return true;
    }

    /** The person's notes, or PersonNotes.EMPTY for an unknown id, no notes
     * file yet, or a malformed one (KTD10). */
    synchronized PersonNotes notes(String id) {
        if (indexOf(id) < 0) {
            return PersonNotes.EMPTY;
        }
        return readNotes(id);
    }

    /**
     * Merges a delta (a JSON object, see PersonNotes) into a person's notes
     * and returns the merged document. Throws IllegalArgumentException with
     * REFUSE_UNKNOWN_PERSON for an id the index does not name, PersonNotes'
     * fixed REFUSE_* reason for a bad delta (the document is unchanged), or
     * REFUSE_NOTES_NOT_SAVED when the file can't be written.
     */
    synchronized PersonNotes mergeNotes(String id, String deltaJson) {
        if (indexOf(id) < 0) {
            throw new IllegalArgumentException(REFUSE_UNKNOWN_PERSON);
        }
        PersonNotes merged = readNotes(id).merge(deltaJson, clock.nowMillis());
        try {
            writeDurably(notesFile(id), merged.toJson().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalArgumentException(REFUSE_NOTES_NOT_SAVED);
        }
        return merged;
    }

    private PersonNotes readNotes(String id) {
        File f = notesFile(id);
        if (!f.isFile()) {
            return PersonNotes.EMPTY;
        }
        try {
            return PersonNotes.parse(new String(readAll(f, MAX_NOTES_FILE_BYTES), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return PersonNotes.EMPTY;
        } catch (IllegalArgumentException e) {
            // Malformed: treated as empty; the next merge rewrites it whole.
            return PersonNotes.EMPTY;
        }
    }

    /** The name, "" when unnamed, or null when there is no such person. */
    synchronized String nameOf(String id) {
        int i = indexOf(id);
        return i < 0 ? null : people.get(i).name;
    }

    /** The person's newest photo (the one recent() and the People page
     * show), or null for an unknown or malformed id. */
    synchronized byte[] face(String id) {
        if (indexOf(id) < 0) {
            return null;
        }
        Entry[] e = slots.get(id);
        int newest = -1;
        for (int i = 0; i < MAX_PHOTOS; i++) {
            if (e[i] != null && (newest < 0 || e[i].addedAtMillis > e[newest].addedAtMillis)) {
                newest = i;
            }
        }
        return newest < 0 ? null : photo(id, newest);
    }

    /** One photo JPEG by slot, or null for an unknown or malformed id or an
     * empty or out-of-range slot. */
    synchronized byte[] photo(String id, int slot) {
        if (indexOf(id) < 0 || slot < 0 || slot >= MAX_PHOTOS || slots.get(id)[slot] == null) {
            return null;
        }
        try {
            return readAll(photoFile(id, slot), MAX_FACE_BYTES);
        } catch (IOException e) {
            return null;
        }
    }

    /** When the slot's photo was added, or -1 for an unknown or malformed id
     * or an empty or out-of-range slot. */
    synchronized long addedAt(String id, int slot) {
        if (indexOf(id) < 0 || slot < 0 || slot >= MAX_PHOTOS || slots.get(id)[slot] == null) {
            return -1;
        }
        return slots.get(id)[slot].addedAtMillis;
    }

    /** The slot's photo JPEG while it is still the one added at addedAtMillis
     * (KTD8), checked and read in one step; otherwise null. */
    synchronized byte[] photoIfAddedAt(String id, int slot, long addedAtMillis) {
        return addedAt(id, slot) == addedAtMillis ? photo(id, slot) : null;
    }

    /** One person's photos in slot order, named or not (the People page);
     * empty for an unknown id. */
    synchronized List<Photo> photos(String id) {
        List<Photo> out = new ArrayList<Photo>();
        if (indexOf(id) >= 0) {
            addPhotos(id, out);
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * Every photo of every named person, most recently seen person first,
     * slots in order (KTD11): what the mode matches against and migrates.
     * Pending and unusable photos are included so the mode can tell whether
     * the store is ready (R18). Nameless records are skipped, and no record
     * carries a name.
     */
    synchronized List<Photo> gallery() {
        List<Photo> out = new ArrayList<Photo>();
        for (Person p : people) {
            if (!p.name.isEmpty()) {
                addPhotos(p.id, out);
            }
        }
        return Collections.unmodifiableList(out);
    }

    private void addPhotos(String id, List<Photo> out) {
        Entry[] e = slots.get(id);
        for (int i = 0; i < MAX_PHOTOS; i++) {
            if (e[i] != null) {
                out.add(new Photo(id, i, e[i].addedAtMillis, e[i].unusable, e[i].modelId,
                        e[i].embedding == null ? null : e[i].embedding.clone()));
            }
        }
    }

    /**
     * The ids of named people a spoken name could mean (KTD10), most recently
     * seen first: two or more words match the full stored name, one word
     * matches the stored first word; case-insensitive, so "ben" matches
     * "Ben Wilson" and "Ben Smith" but never "Benjamin". Empty for a blank
     * name. Answers ids only: names still leave only through nameOf().
     */
    synchronized List<String> idsNamed(String name) {
        List<String> out = new ArrayList<String>();
        String query = cleanName(name).toLowerCase(Locale.ROOT);
        if (query.isEmpty()) {
            return out;
        }
        boolean full = query.indexOf(' ') >= 0;
        for (Person p : people) {
            if (p.name.isEmpty()) {
                continue;
            }
            String stored = p.name.toLowerCase(Locale.ROOT);
            int space = stored.indexOf(' ');
            String compared = full || space < 0 ? stored : stored.substring(0, space);
            if (compared.equals(query)) {
                out.add(p.id);
            }
        }
        return out;
    }

    /** Everyone, most recently seen first. */
    synchronized List<Person> all() {
        return Collections.unmodifiableList(new ArrayList<Person>(people));
    }

    /** The n most recently seen named people (R14), the gallery Explore
     * matches against; nameless legacy records are skipped (KTD10). Empty
     * for n <= 0. */
    synchronized List<Person> recent(int n) {
        List<Person> out = new ArrayList<Person>();
        for (Person p : people) {
            if (out.size() >= n) {
                break;
            }
            if (!p.name.isEmpty()) {
                out.add(p);
            }
        }
        return Collections.unmodifiableList(out);
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
        slots.remove(id);
    }

    private static void checkJpeg(byte[] faceJpeg) {
        if (faceJpeg == null || faceJpeg.length < 4 || (faceJpeg[0] & 0xff) != 0xFF
                || (faceJpeg[1] & 0xff) != 0xD8) {
            throw new IllegalArgumentException(REFUSE_NOT_JPEG);
        }
        if (faceJpeg.length > MAX_FACE_BYTES) {
            throw new IllegalArgumentException(REFUSE_TOO_BIG);
        }
    }

    /** A model id token and 1 to MAX_EMBEDDING_FLOATS finite floats. */
    private static void checkEmbedding(String modelId, float[] embedding) {
        if (!isValidModelId(modelId) || !isValidEmbedding(embedding)) {
            throw new IllegalArgumentException(REFUSE_BAD_EMBEDDING);
        }
    }

    private static boolean isValidModelId(String modelId) {
        return modelId != null && MODEL_ID.matcher(modelId).matches();
    }

    private static boolean isValidEmbedding(float[] embedding) {
        if (embedding == null || embedding.length == 0 || embedding.length > MAX_EMBEDDING_FLOATS) {
            return false;
        }
        for (float f : embedding) {
            if (Float.isNaN(f) || Float.isInfinite(f)) {
                return false;
            }
        }
        return true;
    }

    private String newId() {
        while (true) {
            long v = random.nextLong();
            String id = String.format(Locale.ROOT, "%016x", v);
            if (indexOf(id) < 0 && !faceFile(id).exists() && !facesFile(id).exists()) {
                return id;
            }
        }
    }

    private File faceFile(String id) {
        return new File(dir, id + ".jpg");
    }

    private File notesFile(String id) {
        return new File(dir, id + ".json");
    }

    /** Slot 0 is the legacy "<id>.jpg" (R9); slots 1 to 4 are "<id>-<n>.jpg". */
    private File photoFile(String id, int slot) {
        return slot == 0 ? faceFile(id) : new File(dir, id + "-" + slot + ".jpg");
    }

    private File facesFile(String id) {
        return new File(dir, id + FACES_SUFFIX);
    }

    private boolean hasPhoto(String id) {
        for (int slot = 0; slot < MAX_PHOTOS; slot++) {
            if (photoFile(id, slot).isFile()) {
                return true;
            }
        }
        return false;
    }

    /** A person's slots from disk: one entry per photo file present, from the
     * .faces file when it has a well-formed line for that slot, else pending
     * and added at 0. A missing, oversized or malformed .faces file reads as
     * every photo pending; the next write replaces it whole. */
    private Entry[] readSlots(String id) {
        Entry[] e = new Entry[MAX_PHOTOS];
        for (int slot = 0; slot < MAX_PHOTOS; slot++) {
            if (photoFile(id, slot).isFile()) {
                e[slot] = PENDING_LEGACY;
            }
        }
        File f = facesFile(id);
        if (!f.isFile()) {
            return e;
        }
        String[] lines;
        try {
            lines = new String(readAll(f, MAX_FACES_FILE_BYTES), StandardCharsets.UTF_8).split("\n");
        } catch (IOException ex) {
            return e;
        }
        if (lines.length == 0 || !FACES_HEADER.equals(lines[0])) {
            return e;
        }
        for (int i = 1; i < lines.length; i++) {
            String[] fields = lines[i].split("\t", -1);
            if (fields.length != 5 || !("0".equals(fields[2]) || "1".equals(fields[2]))) {
                continue;
            }
            try {
                int slot = Integer.parseInt(fields[0]);
                if (slot < 0 || slot >= MAX_PHOTOS || e[slot] == null) {
                    continue;
                }
                long addedAt = Long.parseLong(fields[1]);
                boolean unusable = "1".equals(fields[2]);
                String modelId = fields[3];
                float[] embedding = null;
                if (!fields[4].isEmpty()) {
                    String[] parts = fields[4].split(",", -1);
                    embedding = new float[parts.length];
                    for (int j = 0; j < parts.length; j++) {
                        embedding[j] = Float.parseFloat(parts[j]);
                    }
                }
                // Pending or unusable: no embedding. Embedded: a valid pair.
                boolean none = modelId.isEmpty() && embedding == null;
                boolean embedded = !unusable && isValidModelId(modelId) && isValidEmbedding(embedding);
                if (none || embedded) {
                    e[slot] = new Entry(addedAt, unusable, modelId, embedding);
                }
            } catch (NumberFormatException ex) {
                // A torn or hand-edited line: that photo stays pending.
            }
        }
        return e;
    }

    /** Rewrites the person's .faces file whole from e, durably. */
    private void writeFaces(String id, Entry[] e) throws IOException {
        StringBuilder out = new StringBuilder(FACES_HEADER).append('\n');
        for (int slot = 0; slot < MAX_PHOTOS; slot++) {
            Entry x = e[slot];
            if (x == null) {
                continue;
            }
            out.append(slot).append('\t').append(x.addedAtMillis).append('\t').append(x.unusable ? '1' : '0')
                    .append('\t').append(x.modelId).append('\t');
            if (x.embedding != null) {
                for (int i = 0; i < x.embedding.length; i++) {
                    if (i > 0) {
                        out.append(',');
                    }
                    // Float.toString round-trips exactly through parseFloat.
                    out.append(Float.toString(x.embedding[i]));
                }
            }
            out.append('\n');
        }
        writeDurably(facesFile(id), out.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Reads the index, skipping any line that is malformed or that has no
     * photo in any slot (a hand edit, or a crash between writing a face and
     * the index), then deletes every photo, notes or .faces file whose id the
     * index does not name: what a crash mid-add or mid-forget leaves behind (KTD10). The
     * sweep needs the whole index: after a read that failed partway, the
     * people it never reached would look like orphans. */
    private void load() {
        File index = new File(dir, INDEX_FILE);
        boolean complete = !index.isFile() || readIndex(index);
        for (Person p : people) {
            slots.put(p.id, readSlots(p.id));
        }
        if (!complete) {
            return;
        }
        String[] names = dir.list();
        if (names == null) {
            return;
        }
        for (String name : names) {
            Matcher m = OWNED_FILE.matcher(name);
            if (m.matches() && indexOf(m.group(1)) < 0) {
                new File(dir, name).delete();
            }
        }
    }

    /** True when the index was read to its end; false when a read failed
     * partway, so the index may name people that were never loaded. */
    private boolean readIndex(File index) {
        BufferedReader in = null;
        try {
            in = new BufferedReader(new InputStreamReader(indexOpener.open(index), StandardCharsets.UTF_8));
            String line;
            while ((line = in.readLine()) != null) {
                String[] f = line.split("\t", -1);
                if (f.length != 3 || !isValidId(f[0]) || indexOf(f[0]) >= 0 || !hasPhoto(f[0])) {
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
            return true;
        } catch (IOException e) {
            // Keep whatever was read; the next write replaces the index whole.
            return false;
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
