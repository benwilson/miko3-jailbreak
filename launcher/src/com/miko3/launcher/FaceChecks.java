package com.miko3.launcher;

import com.miko3.shared.FaceCheck;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

/**
 * The robot's most recent face checks (face plan U5, KTD8; R13, R14, R19):
 * a ring of the last CAPACITY checks Explore recorded, each with its crop,
 * best match, score, decision and, once the answer arrives, the outcome. The
 * Settings page renders them so the owner can see why the robot did or
 * didn't recognise someone.
 *
 * Memory only: nothing is written to disk, so every check is gone when the
 * launcher restarts (R19). record() answers a handle that later updates the
 * outcome; a handle whose check has rolled off the ring, or was purged,
 * updates nothing. purgePerson() removes every check whose best match or
 * joined id is that person, which forget calls on both the page and the
 * Binder path.
 *
 * Plain Java (no android.*), synchronized on this: PeopleService's Binder
 * threads and the settings server's connection threads share one instance
 * (LauncherApp.faceChecks()). Nothing here logs.
 */
final class FaceChecks {
    /** About ten checks on the page (R13). */
    static final int CAPACITY = 10;

    /** Refusal for a check or outcome code this ring doesn't know. */
    static final String REFUSE_BAD_CHECK = "that face check is not valid";

    /** Wall-clock time, injected so tests can pin when a check happened. */
    interface Clock {
        long nowMillis();
    }

    /** One recorded check as of the call that returned it. */
    static final class Entry {
        final long handle;
        final long atMillis;
        final FaceCheck check;
        final int outcome;
        /** The person the answer joined this crop to, or "". */
        final String joinedId;

        Entry(long handle, long atMillis, FaceCheck check, int outcome, String joinedId) {
            this.handle = handle;
            this.atMillis = atMillis;
            this.check = check;
            this.outcome = outcome;
            this.joinedId = joinedId;
        }

        @Override
        public String toString() {
            return "Entry{handle=" + handle + ", " + check + ", outcome=" + outcome + ", joined=" + joinedId + "}";
        }
    }

    private static final class Slot {
        final long handle;
        final long atMillis;
        final FaceCheck check;
        int outcome = FaceCheck.OUTCOME_PENDING;
        String joinedId = "";

        Slot(long handle, long atMillis, FaceCheck check) {
            this.handle = handle;
            this.atMillis = atMillis;
            this.check = check;
        }

        Entry snapshot() {
            return new Entry(handle, atMillis, check, outcome, joinedId);
        }
    }

    private final Clock clock;
    /** Newest first; never longer than CAPACITY. */
    private final LinkedList<Slot> ring = new LinkedList<Slot>();
    private long lastHandle;

    FaceChecks(Clock clock) {
        this.clock = clock;
    }

    /**
     * Records one check, evicting the oldest past CAPACITY, and answers its
     * handle (always above 0). A no-face check keeps no crop. Throws
     * IllegalArgumentException with a fixed reason for a crop that isn't a
     * JPEG or is over FaceCheck.MAX_CROP_BYTES (PeopleStore's reasons), and
     * REFUSE_BAD_CHECK for an unknown decision or reason or a malformed id.
     */
    synchronized long record(FaceCheck check) {
        FaceCheck c = checked(check);
        long handle = ++lastHandle;
        ring.addFirst(new Slot(handle, clock.nowMillis(), c));
        while (ring.size() > CAPACITY) {
            ring.removeLast();
        }
        return handle;
    }

    /**
     * Sets a check's outcome, and the person the answer joined it to when
     * there is one (joinedId may be null or ""). False, changing nothing,
     * when the handle is unknown or its check has rolled off. Throws
     * IllegalArgumentException(REFUSE_BAD_CHECK) for an unknown outcome or a
     * malformed id.
     */
    synchronized boolean updateOutcome(long handle, int outcome, String joinedId) {
        if (outcome < FaceCheck.YES || outcome > FaceCheck.ENDED_WITHOUT_ANSWER) {
            throw new IllegalArgumentException(REFUSE_BAD_CHECK);
        }
        String joined = optionalId(joinedId);
        Slot s = find(handle);
        if (s == null) {
            return false;
        }
        s.outcome = outcome;
        s.joinedId = joined;
        return true;
    }

    /** A check still pending when the meeting ended becomes "ended without an
     * answer"; one already answered keeps its outcome. False when nothing changed. */
    synchronized boolean closeAsEnded(long handle) {
        Slot s = find(handle);
        if (s == null || s.outcome != FaceCheck.OUTCOME_PENDING) {
            return false;
        }
        s.outcome = FaceCheck.ENDED_WITHOUT_ANSWER;
        return true;
    }

    /** R19: removes every check whose best match or joined id is id; answers how many. */
    synchronized int purgePerson(String id) {
        if (id == null || id.isEmpty()) {
            return 0;
        }
        int removed = 0;
        for (Iterator<Slot> it = ring.iterator(); it.hasNext(); ) {
            Slot s = it.next();
            if (id.equals(s.check.bestId) || id.equals(s.joinedId)) {
                it.remove();
                removed++;
            }
        }
        return removed;
    }

    /** Every check, newest first, as it stands now. */
    synchronized List<Entry> list() {
        List<Entry> out = new ArrayList<Entry>(ring.size());
        for (Slot s : ring) {
            out.add(s.snapshot());
        }
        return Collections.unmodifiableList(out);
    }

    /** The crop JPEG for a handle, or null when unknown, rolled off, or recorded without one. */
    synchronized byte[] crop(long handle) {
        Slot s = find(handle);
        return s == null || s.check.cropJpeg == null ? null : s.check.cropJpeg.clone();
    }

    private Slot find(long handle) {
        for (Slot s : ring) {
            if (s.handle == handle) {
                return s;
            }
        }
        return null;
    }

    /** The check as stored: validated, its crop copied, no crop for no face. */
    private static FaceCheck checked(FaceCheck c) {
        if (c == null || c.decision < FaceCheck.CONFIDENT || c.decision > FaceCheck.NO_FACE) {
            throw new IllegalArgumentException(REFUSE_BAD_CHECK);
        }
        boolean rejected = c.decision == FaceCheck.REJECTED;
        boolean hasReason = c.rejectReason >= FaceCheck.TOO_DARK && c.rejectReason <= FaceCheck.TOO_SMALL;
        if (rejected ? !hasReason : c.rejectReason != FaceCheck.REASON_NONE) {
            throw new IllegalArgumentException(REFUSE_BAD_CHECK);
        }
        String best = optionalId(c.bestId);
        String runnerUp = optionalId(c.runnerUpId);
        boolean matched = c.decision == FaceCheck.CONFIDENT || c.decision == FaceCheck.CLOSE
                || c.decision == FaceCheck.WEAK;
        // A confident or close result names who it thinks this is, and every
        // best match names the photo that matched.
        if ((c.decision == FaceCheck.CONFIDENT || c.decision == FaceCheck.CLOSE) && best.isEmpty()) {
            throw new IllegalArgumentException(REFUSE_BAD_CHECK);
        }
        if (!best.isEmpty() && (!matched || c.bestSlot < 0 || c.bestSlot >= PeopleStore.MAX_PHOTOS)) {
            throw new IllegalArgumentException(REFUSE_BAD_CHECK);
        }
        byte[] crop = null;
        if (c.decision != FaceCheck.NO_FACE && c.cropJpeg != null) {
            checkJpeg(c.cropJpeg);
            crop = c.cropJpeg.clone();
        }
        return new FaceCheck(c.decision, c.rejectReason, crop, best, best.isEmpty() ? -1 : c.bestSlot,
                best.isEmpty() ? 0 : c.bestAddedAtMillis, c.score, runnerUp, runnerUp.isEmpty() ? 0f : c.runnerUpScore,
                !runnerUp.isEmpty() && c.nearTie);
    }

    /** "" for none, the id when it has PeopleStore's shape; throws otherwise. */
    private static String optionalId(String id) {
        if (id == null || id.isEmpty()) {
            return "";
        }
        if (!PeopleStore.isValidId(id)) {
            throw new IllegalArgumentException(REFUSE_BAD_CHECK);
        }
        return id;
    }

    /** PeopleStore's JPEG rule and reasons, against the crop's own cap. */
    private static void checkJpeg(byte[] jpeg) {
        if (jpeg.length > FaceCheck.MAX_CROP_BYTES) {
            throw new IllegalArgumentException(PeopleStore.REFUSE_TOO_BIG);
        }
        if (jpeg.length < 4 || (jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) {
            throw new IllegalArgumentException(PeopleStore.REFUSE_NOT_JPEG);
        }
    }
}
