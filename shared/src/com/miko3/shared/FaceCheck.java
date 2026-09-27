package com.miko3.shared;

/**
 * One face check as Explore records it with the launcher (face plan U5,
 * KTD8; R13, R14): the crop it looked at, the best-matching person's id and
 * the slot and added-at time of their photo that matched, the score, the
 * runner-up with a near-tie marker when R17 demoted the result, and the
 * decision. The launcher keeps the last few in memory for the Settings page
 * (FaceChecks) and answers a handle; the outcome follows later through
 * RobotPeople.updateCheck with one of the OUTCOME codes here.
 *
 * Ids only, never names, like every other RobotPeople record. Plain Java so
 * the launcher's ring and the host tests can use it; RobotPeople carries it
 * across Binder. toString() gives the crop's size, never its bytes.
 */
public final class FaceCheck {
    /** The crop's cap: the same as a stored photo's (PeopleStore.MAX_FACE_BYTES). */
    public static final int MAX_CROP_BYTES = 40 * 1024;

    // Decisions.
    /** Greeted by name. */
    public static final int CONFIDENT = 1;
    /** Asked "Is that you, <name>?". */
    public static final int CLOSE = 2;
    /** Treated as someone new and asked their name. */
    public static final int WEAK = 3;
    /** A gate turned the crop away before matching; rejectReason says which. */
    public static final int REJECTED = 4;
    /** The store's photos were still waiting for embeddings (R18). */
    public static final int NOT_READY = 5;
    /** No face was found in the frame; recorded with no crop. */
    public static final int NO_FACE = 6;

    // Reasons, for REJECTED only.
    public static final int REASON_NONE = 0;
    public static final int TOO_DARK = 1;
    public static final int TOO_BLURRY = 2;
    public static final int TOO_SMALL = 3;

    // Outcomes, set after recording.
    public static final int OUTCOME_PENDING = 0;
    public static final int YES = 1;
    public static final int NO = 2;
    public static final int NAME_GIVEN = 3;
    public static final int JOINED = 4;
    public static final int NEW_PERSON = 5;
    public static final int NO_REPLY = 6;
    /** The meeting ended with the check still pending. */
    public static final int ENDED_WITHOUT_ANSWER = 7;

    public final int decision;
    /** One of the reasons for REJECTED, else REASON_NONE. */
    public final int rejectReason;
    /** The crop JPEG, at most MAX_CROP_BYTES; null for NO_FACE or no crop. */
    public final byte[] cropJpeg;
    /** "" when there was no match to make (rejected, not ready, no face). */
    public final String bestId;
    /** The matching photo's slot, or -1 with no best match. */
    public final int bestSlot;
    /** That photo's added-at time, so the page can tell it was replaced since. */
    public final long bestAddedAtMillis;
    public final float score;
    /** The best different person after bestId, or "" when there was none. */
    public final String runnerUpId;
    public final float runnerUpScore;
    /** True when R17's margin demoted a confident result to close. */
    public final boolean nearTie;

    public FaceCheck(int decision, int rejectReason, byte[] cropJpeg, String bestId, int bestSlot,
                     long bestAddedAtMillis, float score, String runnerUpId, float runnerUpScore, boolean nearTie) {
        this.decision = decision;
        this.rejectReason = rejectReason;
        this.cropJpeg = cropJpeg;
        this.bestId = bestId == null ? "" : bestId;
        this.bestSlot = bestSlot;
        this.bestAddedAtMillis = bestAddedAtMillis;
        this.score = score;
        this.runnerUpId = runnerUpId == null ? "" : runnerUpId;
        this.runnerUpScore = runnerUpScore;
        this.nearTie = nearTie;
    }

    @Override
    public String toString() {
        return "FaceCheck{decision=" + decision + ", reason=" + rejectReason + ", crop="
                + (cropJpeg == null ? "none" : cropJpeg.length + " bytes") + ", best=" + bestId + "/" + bestSlot
                + ", score=" + score + ", runnerUp=" + runnerUpId + ", nearTie=" + nearTie + "}";
    }
}
