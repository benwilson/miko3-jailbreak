package com.miko3.launcher;

import com.miko3.shared.OwnerNotes;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The owner's notes about people by name (owner 2026-10-03), kept in one
 * app-private JSON file beside the people store and written whole through a
 * temporary file and a rename. Only the Settings page writes them; Explore
 * reads one note at a time through PeopleService.ownerNoteFor. Nothing here
 * logs, and the file never leaves the robot.
 *
 * A change that can't be saved is dropped, so what the page shows is always
 * what is on disk. Refusals throw OwnerNotes' fixed REFUSE_* reasons.
 * Plain Java, so the host harnesses run it.
 */
final class OwnerNotesStore {
    static final String FILE = "owner-notes.json";
    /** Far above MAX_ENTRIES full notes; a bigger file is not read. */
    private static final int MAX_FILE_BYTES = 1024 * 1024;

    private final File file;
    private OwnerNotes notes;

    OwnerNotesStore(File file) {
        this.file = file;
        this.notes = OwnerNotes.fromJson(read(file));
    }

    /** Adds a note; answers its id, or null when it could not be saved. */
    synchronized String add(String name, String note) {
        OwnerNotes next = notes.copy();
        String id = next.add(name, note);
        return commit(next) ? id : null;
    }

    /** False for an unknown id or a failed save. */
    synchronized boolean edit(String id, String name, String note) {
        OwnerNotes next = notes.copy();
        return next.edit(id, name, note) && commit(next);
    }

    synchronized boolean delete(String id) {
        OwnerNotes next = notes.copy();
        return next.delete(id) && commit(next);
    }

    synchronized boolean has(String id) {
        return notes.get(id) != null;
    }

    synchronized List<OwnerNotes.Entry> all() {
        return notes.all();
    }

    synchronized String noteFor(String name) {
        return notes.noteFor(name);
    }

    private boolean commit(OwnerNotes next) {
        if (!write(next.toJson())) {
            return false;
        }
        notes = next;
        return true;
    }

    private boolean write(String json) {
        try {
            File dir = file.getParentFile();
            if (dir != null) {
                dir.mkdirs();
            }
            File tmp = new File(dir, file.getName() + ".tmp");
            FileOutputStream os = new FileOutputStream(tmp);
            try {
                os.write(json.getBytes(StandardCharsets.UTF_8));
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

    private static String read(File file) {
        if (!file.isFile() || file.length() > MAX_FILE_BYTES) {
            return null;
        }
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // Nothing to do.
                }
            }
        }
    }
}
