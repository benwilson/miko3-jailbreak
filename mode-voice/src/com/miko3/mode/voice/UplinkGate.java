package com.miko3.mode.voice;

import java.util.ArrayDeque;

/**
 * What the robot forwards after a "Hey Miko", and what it throws away (KTD6,
 * U11). Plain Java with no android.* imports, so the decision runs on the host
 * JVM in scripts/tests (fixtures/voice_uplink_harness) rather than only on the
 * robot; ConversationClient owns an instance and calls it under uplinkLock,
 * which is the only synchronization -- this class is not thread-safe on its own.
 *
 * The model must hear the question, never the wake word that opened the
 * conversation. Three rules, in this order:
 *
 *  1. Nothing captured before the detection callback is forwarded. The gate
 *     drops everything until wake() opens it, and the spotter hands it nothing
 *     earlier: the chunk the detector fired on, and the chunk left waiting in
 *     the spotter's slot, are both discarded by VoiceEngine.
 *  2. The first wakeTrimMs of audio offered after the wake is dropped too, to
 *     swallow the tail of "Hey Miko" still in flight at the callback (the
 *     detector can cross its threshold before the final syllable is over, and
 *     it can only report on an 80 ms chunk boundary) plus the detector's own
 *     latency. Configurable because only on-device tuning can settle it.
 *  3. Everything after that is forwarded in order, whole: a question spoken in
 *     the same breath as the wake word must still arrive. Until conv.ready the
 *     frames go into a bounded buffer (about preReadyMs, drop-oldest, so a
 *     slow relay costs the oldest audio and never the newest), and ready()
 *     sends that buffer ahead of the live stream.
 *
 * Audio is 16-bit little-endian mono at the microphone rate, framed into
 * chunkMs pieces for the lane; the trim is applied to the byte stream before
 * framing, so it does not have to land on a frame boundary.
 *
 * droppedPreRollMs() and forwardedMs() are the per-wake counters the logs
 * carry, so wake_trim_ms can be tuned from logcat.
 */
final class UplinkGate {
    /** Where a frame goes once the conversation is open. */
    interface Sink {
        void frame(byte[] frame);
    }

    private enum Mode { DROP, BUFFER, SEND }

    private static final int BYTES_PER_SAMPLE = 2;

    private final int sampleRate;
    private final ArrayDeque<byte[]> buffered = new ArrayDeque<byte[]>();

    private Mode mode = Mode.DROP;
    private Sink sink;
    private byte[] partial = new byte[0];
    private int partialLen;
    private int maxFrames = 1;
    private int oldestDropped;
    private long trimBytes;
    private long trimRemaining;
    private long droppedBytes;
    private long forwardedBytes;

    UplinkGate(int sampleRate) {
        this.sampleRate = sampleRate;
    }

    /** True while nothing is being kept: every offer() is thrown away. */
    boolean dropping() {
        return mode == Mode.DROP;
    }

    /**
     * The wake word was heard: start keeping audio, after trimMs of it. Called
     * again only from DROP, so a second wake inside one conversation cannot
     * restart the trim or discard what is already buffered.
     */
    void wake(int chunkMs, int preReadyMs, int trimMs) {
        int ms = Math.max(1, chunkMs);
        partial = new byte[ms * sampleRate / 1000 * BYTES_PER_SAMPLE];
        partialLen = 0;
        buffered.clear();
        maxFrames = Math.max(1, preReadyMs / ms);
        oldestDropped = 0;
        trimBytes = (long) Math.max(0, trimMs) * sampleRate / 1000 * BYTES_PER_SAMPLE;
        trimRemaining = trimBytes;
        droppedBytes = 0;
        forwardedBytes = 0;
        sink = null;
        mode = Mode.BUFFER;
    }

    /** Microphone audio offered to the uplink. Never blocks; never grows without bound. */
    void offer(byte[] pcm, int off, int len) {
        if (mode == Mode.DROP || len <= 0) {
            return;
        }
        if (trimRemaining > 0) {
            int skip = (int) Math.min(trimRemaining, (long) len);
            trimRemaining -= skip;
            droppedBytes += skip;
            off += skip;
            len -= skip;
        }
        forwardedBytes += len;
        while (len > 0) {
            int n = Math.min(len, partial.length - partialLen);
            System.arraycopy(pcm, off, partial, partialLen, n);
            partialLen += n;
            off += n;
            len -= n;
            if (partialLen == partial.length) {
                byte[] frame = partial;
                partial = new byte[frame.length];
                partialLen = 0;
                if (mode == Mode.BUFFER) {
                    if (buffered.size() >= maxFrames) {
                        buffered.poll();
                        oldestDropped++;
                    }
                    buffered.add(frame);
                } else {
                    sink.frame(frame);
                }
            }
        }
    }

    /**
     * The conversation is open: the buffer goes out ahead of the live stream,
     * which from now on goes straight to sink. Returns the frames it sent.
     */
    int ready(Sink sink) {
        int sent = buffered.size();
        for (byte[] frame : buffered) {
            sink.frame(frame);
        }
        buffered.clear();
        this.sink = sink;
        mode = Mode.SEND;
        return sent;
    }

    /** No conversation: throw everything away until the next wake. */
    void drop() {
        mode = Mode.DROP;
        sink = null;
        buffered.clear();
        partialLen = 0;
    }

    /** Frames waiting for conv.ready. */
    int bufferedFrames() {
        return buffered.size();
    }

    /** Frames the bound threw away, oldest first, since the wake. */
    int oldestDropped() {
        return oldestDropped;
    }

    /** Milliseconds dropped after the wake as wake-word pre-roll. */
    int droppedPreRollMs() {
        return toMs(droppedBytes);
    }

    /** Milliseconds kept for the model since the wake (buffered or sent). */
    int forwardedMs() {
        return toMs(forwardedBytes);
    }

    /** The trim this wake asked for, which is all of it once speech has flowed. */
    int trimMs() {
        return toMs(trimBytes);
    }

    /** One line for the logs, per wake. */
    String summary() {
        return "dropped " + droppedPreRollMs() + " ms as wake-word pre-roll (" + VoiceSettings.KEY_WAKE_TRIM_MS
                + "=" + trimMs() + "), forwarded " + forwardedMs() + " ms";
    }

    private int toMs(long bytes) {
        int bytesPerMs = Math.max(1, sampleRate / 1000 * BYTES_PER_SAMPLE);
        return (int) (bytes / bytesPerMs);
    }
}
