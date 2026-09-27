package com.miko3.mode.explore;

import java.io.IOException;
import java.util.List;

/**
 * The start-up migration's decisions (on-device face recognition plan U6,
 * KTD11), in plain Java so the brain harness can run them: every stored photo
 * still waiting for an embedding from the current model is fetched, one per
 * call, and either gets its embedding written back or, when the face finder
 * sees no face in it, is marked unusable for the owner to replace.
 *
 * A pass fetches the gallery once and works through the waiting photos while
 * the gate is open; the adapter's gate is the brain's "the detector is not
 * running" (the two-thread rule: the face models never run beside the object
 * detector). A closed gate interrupts the pass between photos; the adapter
 * runs a new pass when the brain opens it again.
 *
 * Nothing here logs: the adapter logs counts and outcomes, never ids' names or
 * pixels. Called on the adapter's worker thread; synchronized so two passes
 * never overlap.
 */
final class FaceMigration {
    /** What Faces.embed() answers for a stored crop with no findable face. */
    static final float[] NO_FACE = new float[0];

    enum Outcome {
        /** Every waiting photo was tried. */
        DONE,
        /** The gate closed between photos; the rest waits for the next pass. */
        INTERRUPTED,
        /** The gallery could not be read (an old launcher, or none): nothing to do now. */
        FAILED
    }

    /** One stored photo as the migration sees it: no name, no pixels. */
    static final class Photo {
        final String id;
        final int slot;
        final long addedAtMillis;
        /** Waiting for an embedding from the current model (not unusable). */
        final boolean pending;

        Photo(String id, int slot, long addedAtMillis, boolean pending) {
            this.id = id;
            this.slot = slot;
            this.addedAtMillis = addedAtMillis;
            this.pending = pending;
        }
    }

    /** The people store's migration calls (RobotPeopleClient in the adapter). */
    interface Store {
        List<Photo> gallery() throws IOException;

        /** The stored photo JPEG, or null when it is gone. */
        byte[] photo(String id, int slot) throws IOException;

        /** False when the person is gone or the slot has since been replaced. */
        boolean setEmbedding(String id, int slot, long addedAtMillis, float[] embedding) throws IOException;

        boolean markUnusable(String id, int slot, long addedAtMillis) throws IOException;
    }

    /** The face pipeline in migration mode (detect, align, brighten when dim, never reject, embed). */
    interface Faces {
        /** The embedding; NO_FACE when no face is found; null when the models failed (try again later). */
        float[] embed(byte[] jpeg);
    }

    /** True while the face models may run (the detector is parked or closed). */
    interface Gate {
        boolean open();
    }

    private final Store store;
    private final Faces faces;
    private final Gate gate;

    private volatile boolean settled;
    private volatile boolean ready;
    private volatile int embedded;
    private volatile int unusable;
    private volatile int waiting;

    FaceMigration(Store store, Faces faces, Gate gate) {
        this.store = store;
        this.faces = faces;
        this.gate = gate;
    }

    /** True when no photo in this gallery is waiting for an embedding (R18's readiness). */
    static boolean ready(List<Photo> photos) {
        if (photos == null) {
            return false;
        }
        for (Photo p : photos) {
            if (p.pending) {
                return false;
            }
        }
        return true;
    }

    /** One pass over the waiting photos, while the gate stays open. */
    synchronized Outcome pass() {
        List<Photo> photos;
        try {
            photos = store.gallery();
        } catch (IOException e) {
            ready = false;
            settled = true;
            return Outcome.FAILED;
        }
        int left = 0;
        for (int i = 0; i < photos.size(); i++) {
            Photo p = photos.get(i);
            if (!p.pending) {
                continue;
            }
            if (!gate.open()) {
                // This one and every later waiting photo wait for the next pass.
                for (int j = i; j < photos.size(); j++) {
                    if (photos.get(j).pending) {
                        left++;
                    }
                }
                waiting = left;
                return Outcome.INTERRUPTED;
            }
            if (!one(p)) {
                left++;
            }
        }
        waiting = left;
        ready = left == 0;
        settled = true;
        return Outcome.DONE;
    }

    /** One waiting photo; false when it still waits (a failed fetch, model or write). */
    private boolean one(Photo p) {
        try {
            byte[] jpeg = store.photo(p.id, p.slot);
            if (jpeg == null) {
                // Gone since the gallery was read: nothing waits on it any more.
                return true;
            }
            float[] e = faces.embed(jpeg);
            if (e == null) {
                return false;
            }
            if (e.length == 0) {
                store.markUnusable(p.id, p.slot, p.addedAtMillis);
                unusable++;
            } else {
                store.setEmbedding(p.id, p.slot, p.addedAtMillis, e);
                embedded++;
            }
            // A false answer means the slot was replaced or the person forgotten: not waiting either.
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** A pass has run to its end (done or failed); an interrupted pass leaves this false. */
    boolean settled() {
        return settled;
    }

    /** The last finished pass left nothing waiting. */
    boolean ready() {
        return ready;
    }

    int embedded() {
        return embedded;
    }

    int unusable() {
        return unusable;
    }

    int waiting() {
        return waiting;
    }
}
