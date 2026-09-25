package com.miko3.launcher;

import java.io.IOException;

/**
 * One short listen for a spoken reply (explore-on-claude plan U3; R11, R12,
 * KTD4, KTD8), and the rule that only one runs at a time. Plain Java, so host
 * tests run it with a fake microphone and recognizer; ListenEngine supplies
 * the real ones (an AudioRecord and sherpa-onnx's streaming zipformer).
 *
 * A listen goes: claim() on the caller's Binder thread (refused while another
 * listen holds the microphone, or before the model has loaded), then run() on
 * the listen thread, which
 *   1. waits until the speech queue is idle (KTD8: the platform ducks the
 *      microphone while the robot speaks, so a reply heard over his own
 *      voice would be lost), failing if it stays busy past idleTimeoutMs;
 *   2. records 16 kHz mono in 80 ms chunks into the recognizer;
 *   3. stops at the recognizer's endpoint (trailing silence after speech,
 *      KTD4) or at the cap, whichever comes first; and
 *   4. returns the transcript, or "no speech" when it heard no words.
 * The cap is counted in recorded audio, so a slow decode never cuts a reply
 * short; a wall-clock backstop guards against a microphone that stops
 * delivering.
 */
final class ListenSession {
    static final int SAMPLE_RATE = 16000;
    /** 80 ms: voice mode's capture chunk, and a fine grain for the endpoint. */
    static final int CHUNK_SAMPLES = 1280;
    static final long DEFAULT_CAP_MS = 6000;
    static final long MIN_CAP_MS = 1000;
    static final long MAX_CAP_MS = 15000;
    /** Past the cap in wall-clock time, a stalled microphone ends the listen. */
    static final long BACKSTOP_MS = 3000;

    static final String REFUSE_BUSY = "already listening";
    static final String REFUSE_UNAVAILABLE = "listening unavailable";
    static final String FAIL_MIC = "microphone failed";
    static final String FAIL_SPEECH_BUSY = "robot still speaking";
    static final String FAIL_INTERRUPTED = "listen interrupted";
    static final String FAIL_RECOGNIZER = "recognizer failed";

    enum Outcome { HEARD, NO_SPEECH, FAILED }

    enum Stop { ENDPOINT, CAP, ERROR }

    /** The speech queue, as far as listening cares. */
    interface Idle {
        /** True once nothing is playing or queued; false after timeoutMs. */
        boolean awaitIdle(long timeoutMs) throws InterruptedException;
    }

    /** An open microphone. */
    interface Mic {
        /** Fills buf with samples in [-1, 1]; returns how many, or a negative
         * number when the microphone failed. Blocks for about a chunk. */
        int read(float[] buf);

        void close();
    }

    /** One utterance's streaming recognizer. */
    interface Recognizer {
        /** Feeds samples and decodes what is ready. */
        void accept(float[] samples, int n);

        /** True once the speaker has finished (the model's endpoint rules). */
        boolean isEndpoint();

        /** The transcript so far. */
        String text();

        /** No more audio: flushes the last words into text(). */
        void finish();

        void close();
    }

    /** Where run() gets its microphone and recognizer. */
    interface Ears {
        /** False until the model has loaded, and forever if it failed to. */
        boolean ready();

        Mic openMic() throws IOException;

        Recognizer newRecognizer();
    }

    static final class Result {
        final Outcome outcome;
        final Stop stop;
        /** Trimmed transcript; "" unless HEARD. */
        final String text;
        /** Audio recorded, in ms. */
        final long audioMs;
        /** Time spent waiting for the speech queue, in ms. */
        long waitedMs;
        /** Why a FAILED listen failed; null otherwise. */
        final String reason;

        Result(Outcome outcome, Stop stop, String text, long audioMs, String reason) {
            this.outcome = outcome;
            this.stop = stop;
            this.text = text;
            this.audioMs = audioMs;
            this.reason = reason;
        }

        static Result failed(String reason, long audioMs) {
            return new Result(Outcome.FAILED, Stop.ERROR, "", audioMs, reason);
        }

        @Override
        public String toString() {
            return outcome + " (" + stop + ", " + audioMs + " ms audio, waited " + waitedMs + " ms"
                    + (reason == null ? "" : ", " + reason) + ")";
        }
    }

    private final Idle idle;
    private final Ears ears;
    private final long idleTimeoutMs;
    private boolean claimed; // guarded by this

    ListenSession(Idle idle, Ears ears, long idleTimeoutMs) {
        this.idle = idle;
        this.ears = ears;
        this.idleTimeoutMs = idleTimeoutMs;
    }

    /** Takes the microphone for one listen, or throws IllegalStateException
     * with REFUSE_BUSY or REFUSE_UNAVAILABLE. Every successful claim must be
     * followed by exactly one run(), which gives it back. */
    synchronized void claim() {
        if (claimed) {
            throw new IllegalStateException(REFUSE_BUSY);
        }
        if (!ears.ready()) {
            throw new IllegalStateException(REFUSE_UNAVAILABLE);
        }
        claimed = true;
    }

    /** The listen claim() allowed; never throws, and always releases the claim. */
    Result run(long maxMs) {
        long capMs = clampCap(maxMs);
        long t0 = System.nanoTime();
        long waited = 0;
        Recognizer rec = null;
        Mic mic = null;
        Result r;
        try {
            boolean quiet = idle.awaitIdle(idleTimeoutMs);
            waited = (System.nanoTime() - t0) / 1000000L;
            if (!quiet) {
                r = Result.failed(FAIL_SPEECH_BUSY, 0);
            } else {
                rec = ears.newRecognizer();
                try {
                    mic = ears.openMic();
                    r = capture(mic, rec, capMs);
                } catch (IOException e) {
                    r = Result.failed(FAIL_MIC, 0);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            r = Result.failed(FAIL_INTERRUPTED, 0);
        } catch (RuntimeException e) {
            r = Result.failed(FAIL_RECOGNIZER, 0);
        } finally {
            closeQuietly(mic, rec);
            release();
        }
        r.waitedMs = waited;
        return r;
    }

    private synchronized void release() {
        claimed = false;
    }

    private static void closeQuietly(Mic mic, Recognizer rec) {
        if (mic != null) {
            try {
                mic.close();
            } catch (RuntimeException ignored) {
                // Closing is best effort.
            }
        }
        if (rec != null) {
            try {
                rec.close();
            } catch (RuntimeException ignored) {
                // Closing is best effort.
            }
        }
    }

    /** Records from mic into rec until the endpoint or capMs of audio. */
    static Result capture(Mic mic, Recognizer rec, long capMs) {
        long capSamples = capMs * SAMPLE_RATE / 1000;
        long backstop = System.nanoTime() + (capMs + BACKSTOP_MS) * 1000000L;
        float[] buf = new float[CHUNK_SAMPLES];
        long samples = 0;
        Stop stop = Stop.CAP;
        while (samples < capSamples) {
            int n = mic.read(buf);
            if (n < 0) {
                return Result.failed(FAIL_MIC, samples * 1000 / SAMPLE_RATE);
            }
            if (n > 0) {
                rec.accept(buf, n);
                samples += n;
                if (rec.isEndpoint()) {
                    stop = Stop.ENDPOINT;
                    break;
                }
            }
            if (System.nanoTime() > backstop) {
                return Result.failed(FAIL_MIC, samples * 1000 / SAMPLE_RATE);
            }
        }
        if (stop == Stop.CAP) {
            // Cut off mid-reply: let the model flush its last words.
            rec.finish();
        }
        String text = rec.text();
        text = text == null ? "" : text.trim();
        long audioMs = samples * 1000 / SAMPLE_RATE;
        if (text.isEmpty()) {
            return new Result(Outcome.NO_SPEECH, stop, "", audioMs, null);
        }
        return new Result(Outcome.HEARD, stop, text, audioMs, null);
    }

    /** maxMs as a cap, within [MIN_CAP_MS, MAX_CAP_MS]. */
    static long clampCap(long maxMs) {
        return Math.max(MIN_CAP_MS, Math.min(MAX_CAP_MS, maxMs));
    }
}
