package com.miko3.launcher;

/**
 * Where each chunk (sentence) of the playing line ends in the speaker track,
 * and the wait for the line's audio to play out (voice plan U5; R11). Plain
 * Java, so the host harness drives it with a simulated track.
 *
 * Synthesis runs well ahead of playback (the track holds 20 s), so by the time
 * a cancel arrives every sentence may already be written. Stopping "at the
 * next sentence boundary" therefore means stopping the track at the end of the
 * chunk that is playing when the cancel is seen, not after everything written.
 *
 * Speech thread only.
 */
final class LineDrain {
    /** The speaker as the drain sees it. */
    interface Track {
        /** Frames played so far in this line. */
        long head();

        /** Waits a moment before the head is checked again. */
        void idle();

        long nowMs();
    }

    /** Asked while draining: has the line been cancelled since? */
    interface CancelCheck {
        boolean cancelled();
    }

    private long[] ends = new long[8];
    private int count;

    /** Forgets the last line's chunks. */
    void startLine() {
        count = 0;
    }

    /** Records that a chunk's audio ends at frame (frames written so far). */
    void chunkEnded(long frame) {
        if (count == ends.length) {
            long[] bigger = new long[count * 2];
            System.arraycopy(ends, 0, bigger, 0, count);
            ends = bigger;
        }
        ends[count++] = frame;
    }

    /** The end of the chunk playing at head (never past speechEnd). */
    long stopFrame(long head, long speechEnd) {
        for (int i = 0; i < count; i++) {
            if (ends[i] >= head) {
                return Math.min(ends[i], speechEnd);
            }
        }
        return speechEnd;
    }

    /**
     * Waits until the head reaches speechEnd; or, once the line is cancelled
     * (already, or seen through check while waiting), until the end of the
     * chunk playing at that moment. Returns false if deadlineMs passed first
     * (the head is stuck). The caller then stops and flushes the track, so
     * nothing written past that point is heard.
     */
    boolean await(Track track, long speechEnd, boolean cancelled, CancelCheck check, long deadlineMs) {
        long target = cancelled ? stopFrame(track.head(), speechEnd) : speechEnd;
        while (track.head() < target) {
            if (!cancelled && check.cancelled()) {
                cancelled = true;
                target = stopFrame(track.head(), speechEnd);
                continue;
            }
            if (track.nowMs() > deadlineMs) {
                return false;
            }
            track.idle();
        }
        return true;
    }
}
