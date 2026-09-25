package com.miko3.launcher;

/**
 * Plays one line at a time through a streaming speaker track (voice plan U5;
 * R10, R11). SpeechEngine hands it each sentence's audio as sherpa-onnx makes
 * it; this class owns the buffer sizing, the head checks and the drain. Plain
 * Java: SpeechEngine backs Speaker with an AudioTrack, and the host harness
 * backs it with a simulated track.
 *
 * Buffer sizing (VoicePlayer's lesson): the platform holds a streaming track
 * until its buffer is full once, so each line starts with a small working
 * size (startFrames) and opens to the full capacity once the head first
 * moves, so synthesis can run ahead of playback.
 *
 * Underruns: when a sentence takes longer to make than the one before it
 * takes to play (a short "Hello!" then a long sentence), the track runs dry.
 * The platform then holds it again until its buffer is full, and with the
 * buffer opened to 20 s that never happens: the head stayed stuck with 73k
 * frames written (robot, line 4). So when the head has not moved for
 * STALL_MS while frames are waiting, the working size drops back to
 * startFrames (already queued, so it restarts at once) and the track is told
 * to play; it opens to the full capacity again once the head moves.
 *
 * Speech thread only.
 */
final class SpeechPlayer {
    /** The speaker track, as the player sees it. */
    interface Speaker {
        /** Frames played since the last flush. */
        long head();

        /** A blocking write; returns the frames written, or a negative error. */
        int write(short[] data, int off, int frames);

        void setBufferSizeInFrames(int frames);

        void play();

        void pause();

        void flush();

        long nowMs();

        void sleep(long ms);

        /** An informational log line (never the spoken text). */
        void note(String message);
    }

    /** Frames per blocking write; between slices the head is checked. */
    static final int SLICE_FRAMES = 1024;
    /** Slack past a line's own length before it is given up on. */
    static final long DRAIN_SLACK_MS = 3000;
    /** A head still this long, with frames waiting, has underrun and stalled. */
    static final long STALL_MS = 250;

    private final Speaker speaker;
    private final int rate;
    private final int startFrames;
    private final int capacityFrames;
    private final LineDrain drain = new LineDrain();
    private final LineDrain.Track drainTrack = new LineDrain.Track() {
        @Override
        public long head() {
            return speaker.head();
        }

        @Override
        public void idle() {
            checkHead();
            speaker.sleep(10);
        }

        @Override
        public long nowMs() {
            return speaker.nowMs();
        }
    };

    private long queuedAtNanos;
    private long startedAtNanos;
    private long written;
    private boolean heard;
    // The working size is at full capacity (since the head last started moving).
    private boolean open;
    // Where the head stood when the working size was last shrunk (0 at the
    // start of a line): the buffer reopens once the head moves past it.
    private long stallHead;
    private long lastHead;
    private long lastMoveMs;
    private short[] pcm = new short[0];

    SpeechPlayer(Speaker speaker, int rate, int startFrames, int capacityFrames) {
        this.speaker = speaker;
        this.rate = rate;
        this.startFrames = startFrames;
        this.capacityFrames = capacityFrames;
    }

    /** Frames written for the current line so far. */
    long written() {
        return written;
    }

    void startLine(long queuedAtNanos) {
        this.queuedAtNanos = queuedAtNanos;
        startedAtNanos = System.nanoTime();
        written = 0;
        heard = false;
        open = false;
        stallHead = 0;
        lastHead = 0;
        lastMoveMs = speaker.nowMs();
        drain.startLine();
        speaker.setBufferSizeInFrames(startFrames);
        speaker.play();
    }

    /** Hands one callback's worth of audio to the speaker. */
    void write(float[] samples) {
        if (pcm.length < samples.length) {
            pcm = new short[samples.length];
        }
        for (int i = 0; i < samples.length; i++) {
            float s = samples[i];
            s = s > 1f ? 1f : (s < -1f ? -1f : s);
            pcm[i] = (short) (s * 32767f);
        }
        writePcm(pcm, samples.length);
        written += samples.length;
    }

    /** Marks the end of a chunk (sentence) at the frames written so far. */
    void chunkEnded() {
        drain.chunkEnded(written);
    }

    /**
     * Waits for the line to play out (or, once cancelled, the sentence playing
     * then; see LineDrain), then pauses and flushes the track. Returns false if
     * the head got stuck and the line was given up on.
     */
    boolean endLine(boolean cancelled, LineDrain.CancelCheck check) {
        long speechEnd = written;
        // Pad with a start buffer of silence, so a short line still fills the
        // buffer once and starts; the line has ended when the head passes speechEnd,
        // or, once cancelled, the end of the sentence playing then (the pause and
        // flush below drop whatever was written past it).
        writePcm(new short[startFrames], startFrames);
        long deadline = speaker.nowMs() + speechEnd * 1000 / rate + DRAIN_SLACK_MS;
        boolean reached = drain.await(drainTrack, speechEnd, cancelled, check, deadline);
        checkHead();
        try {
            speaker.pause();
            speaker.flush();
        } catch (IllegalStateException ignored) {
        }
        return reached;
    }

    /** Blocking writes in slices, checking the head between them. */
    private void writePcm(short[] data, int frames) {
        int off = 0;
        while (off < frames) {
            int n = speaker.write(data, off, Math.min(SLICE_FRAMES, frames - off));
            if (n < 0) {
                throw new IllegalStateException("speaker write failed: " + n);
            }
            off += n;
            checkHead();
        }
    }

    /**
     * Between writes and while draining. Once the head first moves: note the
     * time to first audio and open the buffer to its full capacity, so later
     * writes stop waiting on playback. If the head then stalls with frames
     * waiting (an underrun), shrink back to startFrames and play again.
     */
    private void checkHead() {
        long head = speaker.head();
        long now = speaker.nowMs();
        if (head != lastHead) {
            lastHead = head;
            lastMoveMs = now;
        }
        if (!open) {
            if (head > stallHead) {
                open = true;
                speaker.setBufferSizeInFrames(capacityFrames);
                if (!heard) {
                    heard = true;
                    long t = System.nanoTime();
                    speaker.note("first audio " + ms(t - queuedAtNanos) + " ms after speak() ("
                            + ms(t - startedAtNanos) + " ms after it started)");
                }
            }
            return;
        }
        if (head < written && now - lastMoveMs >= STALL_MS) {
            speaker.note("playback underran at frame " + head + " with " + (written - head)
                    + " frames waiting; restarting");
            stallHead = head;
            open = false;
            lastMoveMs = now;
            speaker.setBufferSizeInFrames(startFrames);
            speaker.play();
        }
    }

    private static long ms(long nanos) {
        return nanos / 1000000L;
    }
}
