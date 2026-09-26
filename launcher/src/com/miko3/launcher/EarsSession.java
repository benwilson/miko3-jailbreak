package com.miko3.launcher;

import com.miko3.shared.VoiceDirection;

import java.io.IOException;
import java.util.List;

/**
 * The launcher's one continuous listening session (meeting plan U3; R1-R6,
 * R21; KTD1, KTD2, KTD3, KTD6). Plain Java: the microphone, the vendor
 * wake-word engine, the Silero gate, the one recogniser, the direction
 * sampler and the clock are injected, so scripts/tests/test_listen_service.py
 * runs the state machine chunk by chunk; ListenEngine supplies the real ones.
 *
 * One client (Explore, by uid) opens the session and renews it every
 * RENEW_PERIOD_MS through a LeaseKeeper; three missed renews, a Binder death
 * or a close release the microphone. The capture runs only while the charger
 * latch the client reports is clear (KTD6), except that a conversation listen
 * already running finishes first. One capture loop feeds every chunk to the
 * wake-word engine and the VAD gate, and to the recogniser only while speech
 * is present (plus a short hangover so its endpoint rule sees silence). The
 * direction angle is sampled at DIRECTION_PERIOD_MS on the sampler's own
 * thread only while speech is present and latched as the median (KTD4).
 *
 * The deaf window (KTD1) runs from a spoken line's start to playback idle
 * plus the tuned tail, or for a clip window's stated duration plus the tail:
 * chunks inside it are dropped, an utterance the window cut short is
 * delivered flagged partial, and the streams are reset when it closes. An
 * utterance is delivered as {text, side, angle, tier, at, partial} when the
 * classifier gives it a tier and it carries words, the wake word or a side.
 *
 * Logs counters through Diag, never words.
 */
final class EarsSession {
    static final int SAMPLE_RATE = ListenSession.SAMPLE_RATE;
    static final int CHUNK_SAMPLES = ListenSession.CHUNK_SAMPLES;
    /** KTD1: the client renews every second; three missed renews release the microphone. */
    static final long RENEW_PERIOD_MS = 1000;
    static final int MISSED_RENEWS = 3;
    static final long TTL_MS = RENEW_PERIOD_MS * MISSED_RENEWS;
    /** A capture that would not open is retried this long after, on a tick. */
    static final long RETRY_MS = 1000;
    /** KTD2: the recogniser keeps hearing this long after the gate drops, so
     * its 0.8 s trailing-silence rule can fire; past it the utterance is closed anyway. */
    static final long ENDPOINT_HANGOVER_MS = 1000;
    /** KTD4: the angle is sampled at 10 Hz while speech is present. */
    static final long DIRECTION_PERIOD_MS = 100;
    /** Speech already present this soon after the deaf window closed lost its head. */
    static final long PARTIAL_HEAD_MS = 120;
    static final long SUMMARY_MS = 60000;

    static final String REFUSE_HELD = "ears held by another app";
    static final String REFUSE_EARS_OPEN = "ears session open";

    interface Clock {
        long nowMs();
    }

    /** An open microphone handing out 16 kHz mono PCM. */
    interface Mic {
        /** Fills pcm; returns how many samples, or a negative number on failure. Blocks for about a chunk. */
        int read(short[] pcm);

        void close();
    }

    interface Capture {
        Mic open() throws IOException;
    }

    /** The vendor wake-word engine. */
    interface Spotter {
        /** True when "Hey Miko" ended in this chunk. */
        boolean hears(short[] pcm, int n);

        void reset();
    }

    /** The Silero VAD gate. */
    interface Gate {
        /** True while speech is present after this chunk. */
        boolean speech(float[] samples, int n);

        void reset();
    }

    /** The one recogniser's one stream. */
    interface Recognizer {
        void accept(float[] samples, int n);

        boolean isEndpoint();

        String text();

        /** Drops the utterance so far: the stream reset. */
        void reset();
    }

    /** The direction library: one sampling per utterance, on its own thread. */
    interface Direction {
        Sampling start();
    }

    interface Sampling {
        List<Float> drain();

        void stop();
    }

    /** The opener's callback. Called on the capture thread; must not block. */
    interface Client {
        void heard(Utterance u);
    }

    /** Where counters and refusals go. Never given words. */
    interface Diag {
        void log(String line);
    }

    static final class Utterance {
        final String text;
        final int side;
        /** The latched median direction angle, or null when the backend gave none. */
        final Float angle;
        final int tier;
        /** When the speech started (the session clock). */
        final long at;
        /** True when the deaf window clipped the utterance. */
        final boolean partial;

        Utterance(String text, int side, Float angle, int tier, long at, boolean partial) {
            this.text = text;
            this.side = side;
            this.angle = angle;
            this.tier = tier;
            this.at = at;
            this.partial = partial;
        }

        @Override
        public String toString() {
            return "tier " + tier + " side " + side + (partial ? " partial" : "") + " at " + at + " ("
                    + text.length() + " chars)";
        }
    }

    private final Clock clock;
    private final Capture capture;
    private final Spotter spotter;
    private final Gate gate;
    private final Recognizer recognizer;
    private final Direction direction;
    private final CueClassifier classifier;
    private final long deafTailMs;
    private final Diag diag;
    private final LeaseKeeper keeper;

    // Guarded by this.
    private Client client;
    private boolean charger;
    private long listenUntil; // 0 when no conversation listen is active
    private Thread captureThread;
    private boolean captureWanted;
    private long captureFailedAt = Long.MIN_VALUE / 4;
    private long lastSummaryMs;

    private volatile boolean capturing;
    private volatile boolean lineOpen;
    private volatile long deafUntil = Long.MIN_VALUE / 4;

    // The capture thread's own state (feed() is serialized on feedLock).
    private final Object feedLock = new Object();
    private final float[] samples = new float[CHUNK_SAMPLES];
    private boolean wasDeaf;
    private boolean inSpeech;
    private boolean partialHead;
    private boolean wake;
    private long speechStartMs;
    private long lastSpeechMs;
    private long hearingSince = Long.MIN_VALUE / 4;
    private Sampling sampling;

    // Counters, for Diag.
    private long chunks;
    private long utterances;
    private long delivered;
    private long dropped;
    private long partials;
    private long resets;
    private long wakes;
    private long strong;
    private long weak;

    EarsSession(Clock clock, Capture capture, Spotter spotter, Gate gate, Recognizer recognizer, Direction direction,
                CueClassifier classifier, long deafTailMs, Diag diag) {
        this.clock = clock;
        this.capture = capture;
        this.spotter = spotter;
        this.gate = gate;
        this.recognizer = recognizer;
        this.direction = direction;
        this.classifier = classifier;
        this.deafTailMs = deafTailMs;
        this.diag = diag;
        this.keeper = new LeaseKeeper(TTL_MS, new LeaseKeeper.Released() {
            @Override
            public void released(String holder, String reason) {
                onReleased(holder, reason);
            }
        });
    }

    // ---- the Binder side ----

    /**
     * Opens the session for holder (a uid), bound to token's death, reporting
     * through client. The same holder may open again (a restarted client),
     * replacing its callback; another holder gets IllegalStateException with
     * REFUSE_HELD, and a token already dead IllegalStateException too.
     */
    synchronized void open(String holder, LeaseKeeper.Token token, Client client, boolean chargerLatched) {
        String current = keeper.holder();
        if (current != null && !current.equals(holder)) {
            diag.log("open refused from uid " + holder + ": held by uid " + current);
            throw new IllegalStateException(REFUSE_HELD);
        }
        if (current != null) {
            keeper.release(holder);
        }
        if (!keeper.acquire(holder, guarded(token), clock.nowMs())) {
            throw new IllegalStateException("client already dead");
        }
        this.client = client;
        this.charger = chargerLatched;
        this.listenUntil = 0;
        this.lastSummaryMs = clock.nowMs();
        diag.log("opened by uid " + holder + (chargerLatched ? " (charger latched)" : ""));
        reconcile();
    }

    /** The holder's heartbeat, carrying its charger latch (KTD6). False for anyone else. */
    synchronized boolean renew(String holder, boolean chargerLatched) {
        if (!keeper.renew(holder, clock.nowMs())) {
            diag.log("renew refused from uid " + holder);
            return false;
        }
        charger = chargerLatched;
        reconcile();
        return true;
    }

    /** A clean close by the holder; false (and nothing happens) for anyone else. */
    synchronized boolean close(String holder) {
        if (!keeper.release(holder)) {
            diag.log("close refused from uid " + holder);
            return false;
        }
        return true;
    }

    /**
     * A conversation listen: for up to maxMs (clamped as a one-shot listen's
     * cap) the switch does not apply and the capture keeps running even when
     * the charger latches. Ends at the first utterance delivered or at the
     * cap. False for a non-holder, or while docked with the capture closed.
     */
    synchronized boolean listen(String holder, long maxMs) {
        if (!isHolder(holder)) {
            diag.log("listen refused from uid " + holder);
            return false;
        }
        if (charger && !captureWanted) {
            diag.log("listen refused: charger latched");
            return false;
        }
        listenUntil = clock.nowMs() + ListenSession.clampCap(maxMs);
        reconcile();
        return true;
    }

    /** Opens the deaf window for durationMs plus the tail, as a spoken line would. */
    synchronized boolean clipWindow(String holder, long durationMs) {
        if (!isHolder(holder)) {
            diag.log("clip window refused from uid " + holder);
            return false;
        }
        long until = clock.nowMs() + Math.max(0, durationMs) + deafTailMs;
        if (until > deafUntil) {
            deafUntil = until;
        }
        return true;
    }

    /** The brain's shove or collision stamp (KTD3, KTD5), for the classifier. */
    synchronized void shoved(String holder, long atMs) {
        if (isHolder(holder)) {
            classifier.shoved(atMs);
        } else {
            diag.log("shove refused from uid " + holder);
        }
    }

    /** Throws IllegalStateException(REFUSE_EARS_OPEN) while a session is held:
     * the one-shot listen and the probe stay for callers with no session. */
    synchronized void refuseOneShot() {
        if (keeper.holder() != null) {
            throw new IllegalStateException(REFUSE_EARS_OPEN);
        }
    }

    /** The keeper's TTL, the listen cap, a capture retry and the summary; run about twice a second. */
    synchronized void tick() {
        long now = clock.nowMs();
        keeper.check(now);
        if (listenUntil != 0 && now >= listenUntil) {
            listenUntil = 0;
        }
        reconcile();
        if (keeper.holder() != null && now - lastSummaryMs >= SUMMARY_MS) {
            lastSummaryMs = now;
            diag.log(summary());
        }
    }

    synchronized boolean held() {
        return keeper.holder() != null;
    }

    synchronized String holder() {
        return keeper.holder();
    }

    synchronized boolean listening() {
        return listenUntil != 0;
    }

    boolean capturing() {
        return capturing;
    }

    /** When the deaf window closes; Long.MAX_VALUE while a line is playing. */
    long deafUntilMs() {
        return lineOpen ? Long.MAX_VALUE : deafUntil;
    }

    // ---- the speech queue's hooks (its playing thread) ----

    /** A line started playing: deaf from now. */
    void lineStarted() {
        lineOpen = true;
    }

    /** Nothing plays or waits any more: deaf for the tail, then a stream reset. */
    void playbackIdle() {
        long until = clock.nowMs() + deafTailMs;
        if (until > deafUntil) {
            deafUntil = until;
        }
        lineOpen = false;
    }

    // ---- release and capture lifecycle ----

    private void onReleased(String holder, String reason) {
        synchronized (this) {
            diag.log("released uid " + holder + ": " + reason + "; " + summary());
            client = null;
            listenUntil = 0;
            reconcile();
        }
    }

    private boolean isHolder(String holder) {
        return holder != null && holder.equals(keeper.holder());
    }

    /**
     * Every path into the keeper takes this session's lock first and the
     * keeper's second; a Binder death would take them the other way round
     * (the keeper runs its recipient inside its own lock, and the release
     * callback then needs ours), so the death is wrapped to take ours first.
     */
    private LeaseKeeper.Token guarded(final LeaseKeeper.Token token) {
        return new LeaseKeeper.Token() {
            private Runnable linked;

            @Override
            public void linkToDeath(final Runnable onDeath) throws Exception {
                Runnable outer = new Runnable() {
                    @Override
                    public void run() {
                        synchronized (EarsSession.this) {
                            onDeath.run();
                        }
                    }
                };
                token.linkToDeath(outer);
                linked = outer;
            }

            @Override
            public void unlinkToDeath(Runnable onDeath) {
                if (linked != null) {
                    token.unlinkToDeath(linked);
                    linked = null;
                }
            }
        };
    }

    /** Caller holds the lock. Starts or stops the capture to match the rules. */
    private void reconcile() {
        boolean want = keeper.holder() != null && (!charger || listenUntil != 0);
        captureWanted = want;
        if (want && captureThread == null && clock.nowMs() - captureFailedAt >= RETRY_MS) {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    captureLoop();
                }
            }, "ears");
            t.setDaemon(true);
            captureThread = t;
            t.start();
        }
        // A stop is the loop's own business: it sees captureWanted drop within a chunk.
    }

    private void captureLoop() {
        Mic mic = null;
        try {
            mic = capture.open();
            capturing = true;
            diag.log("capture open");
            short[] pcm = new short[CHUNK_SAMPLES];
            while (captureWanted) {
                int n = mic.read(pcm);
                if (n < 0) {
                    diag.log("capture read failed");
                    break;
                }
                if (n > 0) {
                    feed(pcm, n);
                }
            }
        } catch (IOException e) {
            synchronized (this) {
                captureFailedAt = clock.nowMs();
            }
            diag.log("capture failed to open: " + e.getMessage());
        } catch (RuntimeException e) {
            diag.log("capture failed: " + e.getClass().getSimpleName());
        } finally {
            synchronized (feedLock) {
                if (inSpeech) {
                    dropUtterance();
                }
            }
            if (mic != null) {
                try {
                    mic.close();
                } catch (RuntimeException ignored) {
                    // Closing is best effort.
                }
                diag.log("capture closed");
            }
            synchronized (this) {
                capturing = false;
                if (captureThread == Thread.currentThread()) {
                    captureThread = null;
                }
                if (captureWanted) {
                    // A read failure, or a restart requested: the next tick retries.
                    captureFailedAt = clock.nowMs();
                }
            }
        }
    }

    // ---- one chunk (the capture thread; the harness calls it directly) ----

    void feed(short[] pcm, int n) {
        synchronized (feedLock) {
            long now = clock.nowMs();
            chunks++;
            boolean deaf = lineOpen || now < deafUntil;
            if (deaf) {
                if (inSpeech) {
                    endUtterance(now, true);
                }
                wasDeaf = true;
                dropped++;
                return;
            }
            if (wasDeaf) {
                recognizer.reset();
                gate.reset();
                spotter.reset();
                resets++;
                wasDeaf = false;
                hearingSince = now;
            }
            boolean hit = spotter.hears(pcm, n);
            for (int i = 0; i < n; i++) {
                samples[i] = pcm[i] / 32768f;
            }
            boolean speech = gate.speech(samples, n);
            if (speech) {
                if (!inSpeech) {
                    inSpeech = true;
                    wake = false;
                    speechStartMs = now;
                    partialHead = now - hearingSince < PARTIAL_HEAD_MS;
                    sampling = direction.start();
                }
                lastSpeechMs = now;
            }
            if (hit) {
                wakes++;
                if (inSpeech) {
                    wake = true;
                } else {
                    // The engine fired with the gate closed: a bare wake cue.
                    utterances++;
                    deliver(new Utterance("", CueClassifier.SIDE_NONE, null, CueClassifier.TIER_STRONG, now, false));
                }
            }
            if (inSpeech) {
                recognizer.accept(samples, n);
                if (recognizer.isEndpoint() || (!speech && now - lastSpeechMs >= ENDPOINT_HANGOVER_MS)) {
                    endUtterance(now, false);
                }
            }
        }
    }

    /** Caller holds feedLock. Closes the utterance in progress and delivers it if it earns a tier. */
    private void endUtterance(long now, boolean cutShort) {
        String text = recognizer.text();
        text = text == null ? "" : text.trim();
        Float angle = null;
        if (sampling != null) {
            angle = VoiceDirection.median(sampling.drain());
            sampling.stop();
            sampling = null;
        }
        boolean partial = cutShort || partialHead;
        boolean wasWake = wake;
        long at = speechStartMs;
        inSpeech = false;
        wake = false;
        partialHead = false;
        recognizer.reset();
        utterances++;
        boolean listening;
        synchronized (this) {
            listening = listenUntil != 0;
        }
        int tier = classifier.tier(text, wasWake, listening, at);
        int side = CueClassifier.side(angle);
        if (tier == CueClassifier.TIER_NONE || (text.isEmpty() && !wasWake && side == CueClassifier.SIDE_NONE)) {
            return;
        }
        if (partial) {
            partials++;
        }
        deliver(new Utterance(text, side, angle, tier, at, partial));
    }

    /** Caller holds feedLock. The capture is closing: nothing is delivered. */
    private void dropUtterance() {
        if (sampling != null) {
            sampling.stop();
            sampling = null;
        }
        inSpeech = false;
        wake = false;
        recognizer.reset();
    }

    private void deliver(Utterance u) {
        Client c;
        synchronized (this) {
            c = client;
            if (u.tier == CueClassifier.TIER_STRONG) {
                strong++;
            } else {
                weak++;
            }
            if (listenUntil != 0 && (!u.text.isEmpty() || u.tier == CueClassifier.TIER_STRONG)) {
                listenUntil = 0;
                reconcile();
            }
        }
        if (c == null) {
            return;
        }
        delivered++;
        try {
            c.heard(u);
        } catch (RuntimeException e) {
            diag.log("delivery failed: " + e.getClass().getSimpleName());
        }
    }

    private String summary() {
        return "chunks=" + chunks + " utterances=" + utterances + " delivered=" + delivered + " strong=" + strong
                + " weak=" + weak + " partial=" + partials + " dropped=" + dropped + " resets=" + resets
                + " wakes=" + wakes;
    }
}
