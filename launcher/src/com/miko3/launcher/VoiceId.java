package com.miko3.launcher;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Owner 2026-10-02: who is speaking, by voice. The ears session hands it the audio it
 * gives the recogniser (append, on the capture thread under its feed lock), marks where
 * speech was last heard (speech) and starts a fresh buffer at each utterance's onset;
 * the buffer holds at most VoiceTuning.MAX_BUFFER_MS, the start of the utterance. When a
 * conversation listen's answer has been delivered, clean (words, a tier, not clipped by
 * the robot's own speech), with at least VoiceTuning.MIN_SPEECH_MS up to its last speech,
 * answered() copies that span and returns; the embedding is computed on one low-priority
 * background thread, matched against the VoiceStore, and reported to the Listener with the
 * answer's at. The buffer is always cleared. Audio never leaves memory and is never kept
 * past the copy; the embedding is kept briefly (the last VoiceTuning.RECENT answers) so
 * the people layer can enrol it (VoicePrints). Plain Java: the extractor is an Embedder.
 */
final class VoiceId implements VoicePrints {
    /** Speaker embedding of n samples at 16 kHz; null when the extractor could not make one. */
    interface Embedder {
        float[] embed(float[] samples, int n) throws Exception;
    }

    /** Told once per embedded answer, on the voice thread: the best person (or null), score and band. */
    interface Listener {
        void voice(long at, String person, float score, int band);
    }

    private static final int CAP = (int) (VoiceTuning.SAMPLE_RATE * VoiceTuning.MAX_BUFFER_MS / 1000);
    private static final int MIN_SAMPLES = (int) (VoiceTuning.SAMPLE_RATE * VoiceTuning.MIN_SPEECH_MS / 1000);

    private final VoiceStore store;
    private final VoiceTuning tuning;
    private final VoiceStore.Diag diag;
    private volatile Embedder embedder;
    private volatile Listener listener;

    // The buffer: written only on the capture thread, guarded by itself for the snapshot.
    private final float[] buf = new float[CAP];
    private int len;
    /** len when speech was last marked: the answer's trailing silence is left out. */
    private int speechLen;

    /** at -> embedding, the most recent last. */
    private final LinkedHashMap<Long, float[]> recent = new LinkedHashMap<Long, float[]>();

    /** One thread, lowest priority, and at most two answers waiting: a backlog is dropped, never queued up. */
    private final ThreadPoolExecutor thread = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(2), new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "voice-id");
                    t.setDaemon(true);
                    t.setPriority(Thread.MIN_PRIORITY);
                    return t;
                }
            });

    VoiceId(VoiceStore store, VoiceTuning tuning, VoiceStore.Diag diag) {
        this.store = store;
        this.tuning = tuning;
        this.diag = diag;
    }

    void setEmbedder(Embedder e) {
        embedder = e;
    }

    void setListener(Listener l) {
        listener = l;
    }

    /** Runs r on the voice thread (the model's load, the bench); false when it is busy or shut down. */
    boolean runOnVoiceThread(Runnable r) {
        try {
            thread.execute(r);
            return true;
        } catch (RejectedExecutionException e) {
            return false;
        }
    }

    // ---- the capture thread ----

    /** An utterance began: a fresh buffer. */
    void start() {
        synchronized (buf) {
            len = 0;
            speechLen = 0;
        }
    }

    /** The utterance was dropped: nothing kept. */
    void reset() {
        start();
    }

    /** The audio handed to the recogniser, appended until the cap. */
    void append(float[] samples, int n) {
        synchronized (buf) {
            int take = Math.min(n, CAP - len);
            if (take > 0) {
                System.arraycopy(samples, 0, buf, len, take);
                len += take;
            }
        }
    }

    /** Speech was heard in the chunk just appended. */
    void speech() {
        synchronized (buf) {
            speechLen = len;
        }
    }

    /** Samples held now (the harness). */
    int buffered() {
        synchronized (buf) {
            return len;
        }
    }

    /**
     * The utterance whose speech began at at was delivered; clean when it was a listen's
     * answer with words, not clipped by the deaf window. Copies the speech span and queues
     * its embedding when it is long enough and the model is loaded; always clears the buffer.
     * Returns at once: true when an embedding was queued.
     */
    boolean answered(final long at, boolean clean) {
        final float[] snap;
        final int n;
        synchronized (buf) {
            n = speechLen;
            if (!clean || n < MIN_SAMPLES || embedder == null) {
                len = 0;
                speechLen = 0;
                return false;
            }
            snap = new float[n];
            System.arraycopy(buf, 0, snap, 0, n);
            len = 0;
            speechLen = 0;
        }
        boolean queued = runOnVoiceThread(new Runnable() {
            @Override
            public void run() {
                identify(at, snap, n);
            }
        });
        if (!queued) {
            diag.log("voice: busy, an answer was not identified");
        }
        return queued;
    }

    /** On the voice thread. */
    private void identify(long at, float[] samples, int n) {
        Embedder e = embedder;
        if (e == null) {
            return;
        }
        long t0 = System.nanoTime();
        float[] embedding;
        try {
            embedding = e.embed(samples, n);
        } catch (Exception | LinkageError ex) {
            diag.log("voice: embedding failed: " + ex.getClass().getSimpleName());
            return;
        }
        long ms = (System.nanoTime() - t0) / 1000000;
        long durMs = n * 1000L / VoiceTuning.SAMPLE_RATE;
        if (embedding == null || embedding.length == 0) {
            diag.log("voice: no embedding (dur " + durMs + " ms)");
            return;
        }
        diag.log("voice: embedding in " + ms + " ms (dur " + durMs + " ms)");
        remember(at, embedding);
        VoiceStore.Match m = store.match(embedding, tuning);
        diag.log(String.format(Locale.US, "voice: match band=%s score=%.2f", VoiceTuning.bandName(m.band), m.score));
        Listener l = listener;
        if (l != null) {
            try {
                l.voice(at, m.id, m.score, m.band);
            } catch (RuntimeException ex) {
                diag.log("voice: delivery failed: " + ex.getClass().getSimpleName());
            }
        }
    }

    /** Keeps the embedding for at, dropping the oldest beyond VoiceTuning.RECENT. */
    void remember(long at, float[] embedding) {
        synchronized (recent) {
            recent.remove(at);
            recent.put(at, embedding);
            Iterator<Map.Entry<Long, float[]>> it = recent.entrySet().iterator();
            while (recent.size() > VoiceTuning.RECENT && it.hasNext()) {
                it.next();
                it.remove();
            }
        }
    }

    // ---- the people layer (VoicePrints) ----

    @Override
    public float[] lastEmbeddingFor(long at) {
        synchronized (recent) {
            float[] e = recent.get(at);
            return e == null ? null : e.clone();
        }
    }

    @Override
    public boolean enrolVoice(String personId, float[] embedding) {
        boolean ok = store.add(personId, embedding);
        if (ok) {
            int stored = store.count(personId);
            diag.log("voice: enrolled, " + stored + " stored for that person");
        }
        return ok;
    }

    @Override
    public boolean enrolVoice(String personId, long at) {
        float[] e = lastEmbeddingFor(at);
        return e != null && enrolVoice(personId, e);
    }

    @Override
    public boolean forgetVoice(String personId) {
        boolean ok = store.forget(personId);
        if (ok) {
            diag.log("voice: forgot a person's voice");
        }
        return ok;
    }

    @Override
    public int voiceCount(String personId) {
        return store.count(personId);
    }

    // ---- the debug bench (VoiceTuning.BENCH_PROP) ----

    /**
     * On the voice thread: times the embedder on synthetic audio of each length, reps
     * times, logging "voice: bench dur M ms: embedding in N ms" per run. Debug only.
     */
    void bench(final long[] durationsMs, final int reps) {
        runOnVoiceThread(new Runnable() {
            @Override
            public void run() {
                Embedder e = embedder;
                if (e == null) {
                    diag.log("voice: bench skipped, no model");
                    return;
                }
                Random rnd = new Random(7);
                for (long d : durationsMs) {
                    int n = (int) (VoiceTuning.SAMPLE_RATE * d / 1000);
                    float[] s = new float[n];
                    for (int i = 0; i < n; i++) {
                        double t = i / (double) VoiceTuning.SAMPLE_RATE;
                        s[i] = (float) (0.2 * Math.sin(2 * Math.PI * 140 * t) + 0.1 * Math.sin(2 * Math.PI * 420 * t)
                                + 0.02 * rnd.nextGaussian());
                    }
                    for (int r = 0; r < reps; r++) {
                        long t0 = System.nanoTime();
                        int dim;
                        try {
                            float[] out = e.embed(s, n);
                            dim = out == null ? 0 : out.length;
                        } catch (Exception | LinkageError ex) {
                            diag.log("voice: bench failed: " + ex.getClass().getSimpleName());
                            return;
                        }
                        diag.log("voice: bench dur " + d + " ms: embedding in " + (System.nanoTime() - t0) / 1000000
                                + " ms (dim " + dim + ")");
                    }
                }
                diag.log("voice: bench done");
            }
        });
    }

    /** Lets queued work finish, then stops the thread. */
    void shutdown() {
        thread.shutdown();
    }

    /** After shutdown(): waits up to ms for queued work to finish (the harness). */
    boolean awaitIdle(long ms) throws InterruptedException {
        return thread.awaitTermination(ms, TimeUnit.MILLISECONDS);
    }
}
