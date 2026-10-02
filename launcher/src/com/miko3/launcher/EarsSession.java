package com.miko3.launcher;

import com.miko3.shared.LauncherProtocol;
import com.miko3.shared.VoiceDirection;

import java.io.IOException;
import java.util.ArrayList;
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
 * or a close release the microphone. The capture runs whenever the session
 * is held, on the charger too (Hey Miko plan KTD5, replacing the meeting
 * plan's KTD6 close): a docked robot still answers his name. The client's
 * charger latch is logged on open and otherwise ignored. One capture loop feeds every chunk to the
 * wake-word engine and the VAD gate, and to the recogniser only while speech
 * is present (plus a short hangover so its endpoint rule sees silence). The
 * direction angle is sampled at DIRECTION_PERIOD_MS on the sampler's own
 * thread only while speech is present and latched as the median (KTD4).
 *
 * The deaf window (KTD1) runs from a spoken line's start to playback idle
 * plus the tuned tail, or for a clip window's stated duration plus the tail:
 * chunks inside it are dropped, an utterance the window cut short is
 * delivered flagged partial, and the streams are reset when it closes. An
 * utterance is delivered as {text, side, angle, tier, at, partial, kind,
 * called} when the classifier gives it a tier and it carries words, the wake
 * word or a side; kind is the classifier's naming of the cue.
 *
 * The wake word (Hey Miko plan KTD4): when the engine fires while speech is
 * present, an early cue goes out in that same chunk (empty text, the median
 * angle so far, at = the speech start, KIND_WAKE_WORD), once per utterance.
 * The utterance's own delivery at its end, for the same at, is then marked
 * called so the mode makes no second call from it. The engine firing with the
 * gate closed still sends a bare wake cue at once, as before.
 *
 * The wake gate (EarsTuning, off by default): while it is on and the
 * classifier would ignore the words anyway (CueClassifier.wordsMatter: no
 * conversation listen and the "answers when spoken to" switch off), the
 * utterance's audio is held instead of decoded; the recogniser gets the held
 * audio and then every chunk as soon as the wake engine fires in the utterance
 * or a listen opens, so it hears what it would have heard. Shut, the
 * recogniser's endpoint cannot end the utterance; the hangover does.
 *
 * Logs counters through Diag, never words, and the recogniser's cost: each
 * utterance's decode milliseconds per 80 ms chunk fed, as p50 and p95 over the
 * utterances since the last summary, with the worst single chunk.
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
    /** Robot 2026-10-01: a conversation listen's maxMs is the window to start answering; an
     * answer begun in it runs to its endpoint, but never past this long after the listen opened. */
    static final long LISTEN_HARD_CAP_MS = LauncherProtocol.EARS_LISTEN_HARD_CAP_MS;
    /** Speech that began this soon before a listen opened (they answered as his question ended) is its answer. */
    static final long LISTEN_EARLY_START_MS = 500;
    /**
     * The pre-roll (TODO 2026-10-01): the VAD opens after a word has begun, so the
     * recogniser first hears this much of the audio just before the onset, or
     * "Miko" decodes as "O". Host sweep on synthetic clips (relative guidance only),
     * launcher decoding, at 0/160/240/300/400/500 ms: name hits 44/102/101/98/98/105
     * of 162 (flat past 160), greetings 7/19/24/26/22/21 of 54, chatter WER
     * 42.7/30.1/27.5/22.1/23.9/24.4 %, decode CPU +9/+15/+18/+25/+28 %.
     */
    static final long DEFAULT_PREROLL_MS = 300;
    static final long MAX_PREROLL_MS = 1000;
    static final String PREROLL_PROP = "persist.miko3.ears.preroll_ms";
    static final long SUMMARY_MS = 60000;
    /** The wake gate holds at most this much of an utterance (the oldest goes first). */
    static final int HELD_MAX_SAMPLES = SAMPLE_RATE * 10;
    /** Utterance decode costs kept for one summary's percentiles. */
    static final int DECODE_SAMPLES_MAX = 256;

    static final String REFUSE_HELD = "ears held by another app";
    static final String REFUSE_EARS_OPEN = "ears session open";

    interface Clock {
        long nowMs();

        /** For the decode timing only. */
        default long nanoTime() {
            return System.nanoTime();
        }
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

        /** True when the angle is a side and not a bearing: the NC chip in side mode
         * (robot, 2026-09-29) measures only how far left or right a voice is and
         * cannot tell front from back, so its -90/+90 says which side, never where.
         * The utterance then carries the side and no angle, and the brain's side
         * search looks toward that side first, then behind, then the other side. */
        default boolean sideOnly() {
            return false;
        }
    }

    interface Sampling {
        List<Float> drain();

        void stop();
    }

    /** The opener's callback. Called on the capture thread; must not block. */
    interface Client {
        void heard(Utterance u);

        /**
         * Robot 2026-10-01: the open conversation listen claimed an utterance (it
         * began inside the start window, or just before the listen opened), so its
         * answer has started; at is when the speech began. Once per listen, before
         * the answer's delivery. Called on the capture thread; must not block.
         */
        void answering(long at);
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
        /** CueClassifier.KIND_*: what the tier came from. */
        final int kind;
        /** Hey Miko plan KTD4: the early wake cue for this at was already sent, so this delivery makes no call. */
        final boolean called;

        Utterance(String text, int side, Float angle, int tier, long at, boolean partial, int kind) {
            this(text, side, angle, tier, at, partial, kind, false);
        }

        Utterance(String text, int side, Float angle, int tier, long at, boolean partial, int kind, boolean called) {
            this.text = text;
            this.side = side;
            this.angle = angle;
            this.tier = tier;
            this.at = at;
            this.partial = partial;
            this.kind = kind;
            this.called = called;
        }

        @Override
        public String toString() {
            return "tier " + tier + " kind " + kind + " side " + side + (partial ? " partial" : "")
                    + (called ? " called" : "") + " at " + at + " (" + text.length() + " chars)";
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
    private final boolean gateWake;
    private final long prerollMs;

    // Guarded by this.
    private Client client;
    private long listenUntil; // 0 when no conversation listen is active; else the end of its start window
    private long listenOpenedAt;
    private long listenCapAt; // listenOpenedAt + LISTEN_HARD_CAP_MS
    private boolean answering; // the utterance in progress is the open listen's answer
    private boolean answerAnnounced; // Client.answering went out for this listen (robot 2026-10-01)
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
    /**
     * The pre-roll ring: the latest heard audio no recogniser or hold has taken,
     * oldest at preStart. Emptied at every onset (it is fed first), in and after
     * the deaf window, and when a capture starts, so it never repeats audio or
     * carries the robot's own line.
     */
    private final short[] pre;
    private final float[] preOut;
    private int preStart;
    private int preLen;
    private boolean wasDeaf;
    private boolean inSpeech;
    private boolean partialHead;
    private boolean wake;
    private long speechStartMs;
    private long lastSpeechMs;
    private long hearingSince = Long.MIN_VALUE / 4;
    private Sampling sampling;
    /** Every angle drained during the utterance so far: the early cue's median takes some, the end all. */
    private final List<Float> angles = new ArrayList<Float>();
    /** False while the wake gate holds this utterance's audio instead of decoding it. */
    private boolean recognising;
    private float[] held = new float[0];
    private int heldLen;
    private long uttDecodeNs;
    private long uttFed;
    /** When the utterance in progress, a listen's answer, is cut; Long.MAX_VALUE when it is no answer. */
    private long uttCapAt = Long.MAX_VALUE;

    // Decode cost per utterance (ms per chunk) and the worst chunk, since the last summary.
    private final Object statsLock = new Object();
    private final double[] decodeMs = new double[DECODE_SAMPLES_MAX];
    private int decodeCount;
    private double decodeMaxMs;

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
    private long fed;
    private long gated;

    EarsSession(Clock clock, Capture capture, Spotter spotter, Gate gate, Recognizer recognizer, Direction direction,
                CueClassifier classifier, long deafTailMs, Diag diag) {
        this(clock, capture, spotter, gate, recognizer, direction, classifier, deafTailMs, diag, false);
    }

    EarsSession(Clock clock, Capture capture, Spotter spotter, Gate gate, Recognizer recognizer, Direction direction,
                CueClassifier classifier, long deafTailMs, Diag diag, boolean gateWake) {
        this(clock, capture, spotter, gate, recognizer, direction, classifier, deafTailMs, diag, gateWake,
                DEFAULT_PREROLL_MS);
    }

    /** gateWake: EarsTuning's wake gate (see the class comment); false is the plain session.
     * prerollMs: how much audio before the VAD onset the recogniser hears first (0: none). */
    EarsSession(Clock clock, Capture capture, Spotter spotter, Gate gate, Recognizer recognizer, Direction direction,
                CueClassifier classifier, long deafTailMs, Diag diag, boolean gateWake, long prerollMs) {
        this.clock = clock;
        this.capture = capture;
        this.spotter = spotter;
        this.gate = gate;
        this.recognizer = recognizer;
        this.direction = direction;
        this.classifier = classifier;
        this.deafTailMs = deafTailMs;
        this.diag = diag;
        this.gateWake = gateWake;
        this.prerollMs = Math.max(0, Math.min(MAX_PREROLL_MS, prerollMs));
        this.pre = new short[(int) (this.prerollMs * SAMPLE_RATE / 1000)];
        this.preOut = new float[pre.length];
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
        closeListen();
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
     * A conversation listen: the switch does not apply while it is open. maxMs
     * (clamped as a one-shot listen's cap) is the window to start answering
     * (robot 2026-10-01): an utterance that starts inside it, or at most
     * LISTEN_EARLY_START_MS before it opened, is its answer and holds it open
     * to the utterance's own end (the endpoint or the hangover), cut at
     * LISTEN_HARD_CAP_MS after the listen opened with the words so far. Ends
     * at the first utterance delivered with words or strong (an early wake cue
     * does not end it), at an answer's end once the start window is over, or,
     * with no answer, at maxMs. False for a non-holder.
     */
    synchronized boolean listen(String holder, long maxMs) {
        if (!isHolder(holder)) {
            diag.log("listen refused from uid " + holder);
            return false;
        }
        long now = clock.nowMs();
        listenOpenedAt = now;
        listenUntil = now + ListenSession.clampCap(maxMs);
        listenCapAt = now + LISTEN_HARD_CAP_MS;
        answering = false; // the next chunk claims an utterance in progress if it started in time
        answerAnnounced = false;
        reconcile();
        return true;
    }

    /** Caller holds the lock. No conversation listen. */
    private void closeListen() {
        listenUntil = 0;
        answering = false;
    }

    /**
     * Caller holds feedLock. Whether the utterance that started at startMs is
     * the open listen's answer: its hard cap if so (marking the listen as
     * answering), else Long.MAX_VALUE.
     */
    private long claimAnswer(long startMs) {
        Client announce = null;
        long cap;
        synchronized (this) {
            if (listenUntil == 0 || startMs < listenOpenedAt - LISTEN_EARLY_START_MS || startMs >= listenUntil) {
                return Long.MAX_VALUE;
            }
            answering = true;
            cap = listenCapAt;
            if (!answerAnnounced) {
                // Robot 2026-10-01: the mode hears once that the answer started, so it holds its listen.
                answerAnnounced = true;
                announce = client;
                diag.log("conversation listen answering: speech began " + (startMs - listenOpenedAt)
                        + " ms after it opened");
            }
        }
        if (announce != null) {
            try {
                announce.answering(startMs);
            } catch (RuntimeException e) {
                diag.log("answering delivery failed: " + e.getClass().getSimpleName());
            }
        }
        return cap;
    }

    /** Caller holds feedLock. The answer in progress ended; past its start window the listen ends with it. */
    private void answerEnded(long now) {
        synchronized (this) {
            if (!answering) {
                return;
            }
            answering = false;
            if (listenUntil != 0 && now >= listenUntil) {
                closeListen();
            }
        }
    }

    /** Opens the deaf window for durationMs plus the tail, as a spoken line would. */
    synchronized boolean clipWindow(String holder, long durationMs) {
        if (!isHolder(holder)) {
            diag.log("clip window refused from uid " + holder);
            return false;
        }
        extendDeaf(clock.nowMs() + Math.max(0, durationMs) + deafTailMs);
        return true;
    }

    /** Raises the deaf window to until; an earlier until never shortens it. */
    private void extendDeaf(long until) {
        if (until > deafUntil) {
            deafUntil = until;
        }
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
        // An answer in progress holds the listen open; the capture cuts it at the hard
        // cap, and this backstop ends the listen should the capture stall.
        if (listenUntil != 0 && now >= listenUntil
                && (!answering || now >= listenCapAt + ListenSession.BACKSTOP_MS)) {
            closeListen();
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
        extendDeaf(clock.nowMs() + deafTailMs);
        lineOpen = false;
    }

    // ---- release and capture lifecycle ----

    private void onReleased(String holder, String reason) {
        synchronized (this) {
            diag.log("released uid " + holder + ": " + reason + "; " + summary());
            client = null;
            closeListen();
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
        // Capture runs whenever held, charger included (KTD5).
        boolean want = keeper.holder() != null;
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
            synchronized (feedLock) {
                preLen = 0; // a new capture: the last one's audio is not this utterance's head
            }
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
                preLen = 0;
                wasDeaf = true;
                dropped++;
                return;
            }
            if (wasDeaf) {
                recognizer.reset();
                gate.reset();
                spotter.reset();
                resets++;
                preLen = 0;
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
                    angles.clear();
                    speechStartMs = now;
                    partialHead = now - hearingSince < PARTIAL_HEAD_MS;
                    sampling = direction.start();
                    recognising = !gateWake || wordsMatter();
                    heldLen = 0;
                    uttDecodeNs = 0;
                    uttFed = 0;
                    // The word began before the gate saw it: its head goes in first.
                    int head = drainPreroll();
                    if (head > 0) {
                        if (recognising) {
                            decode(preOut, head);
                        } else {
                            hold(preOut, head);
                        }
                    }
                }
                lastSpeechMs = now;
            }
            if (inSpeech) {
                // Every chunk: a listen opened mid-utterance claims it if it began in time.
                uttCapAt = claimAnswer(speechStartMs);
            }
            if (hit) {
                wakes++;
                if (inSpeech) {
                    if (!wake) {
                        // KTD4: the call goes out now, not 0.8-1 s after the speaker stops.
                        wake = true;
                        utterances++;
                        Float so = latchAngle();
                        int soSide = CueClassifier.side(so);
                        // A side-only chip's angle is not a bearing: send the side alone.
                        if (direction.sideOnly()) {
                            so = null;
                        }
                        // It leaves a conversation listen armed: the words at the end are the reply.
                        deliver(new Utterance("", soSide, so, CueClassifier.TIER_STRONG, speechStartMs,
                                false, CueClassifier.KIND_WAKE_WORD), false);
                    }
                } else {
                    // The engine fired with the gate closed: a bare wake cue.
                    utterances++;
                    deliver(new Utterance("", CueClassifier.SIDE_NONE, null, CueClassifier.TIER_STRONG, now, false,
                            CueClassifier.KIND_WAKE_WORD));
                }
            }
            if (inSpeech) {
                if (!recognising && (wake || wordsMatter())) {
                    recognising = true;
                    if (heldLen > 0) {
                        decode(held, heldLen);
                        heldLen = 0;
                    }
                }
                if (recognising) {
                    decode(samples, n);
                } else {
                    hold(samples, n);
                }
                boolean endpoint = recognising && recognizer.isEndpoint();
                // A listen's answer that will not stop is cut at the hard cap with the words so far.
                if (endpoint || now >= uttCapAt || (!speech && now - lastSpeechMs >= ENDPOINT_HANGOVER_MS)) {
                    endUtterance(now, false);
                }
            } else {
                keepPreroll(pcm, n); // nobody took it: the next onset's head
            }
        }
    }

    /** Caller holds feedLock. Appends a chunk no one took to the pre-roll ring, the oldest audio going first. */
    private void keepPreroll(short[] pcm, int n) {
        int cap = pre.length;
        if (cap == 0) {
            return;
        }
        int from = 0;
        if (n > cap) {
            from = n - cap;
        }
        for (int i = from; i < n; i++) {
            if (preLen < cap) {
                pre[(preStart + preLen) % cap] = pcm[i];
                preLen++;
            } else {
                pre[preStart] = pcm[i];
                preStart = (preStart + 1) % cap;
            }
        }
    }

    /** Caller holds feedLock. Empties the ring into preOut, oldest first, as samples; returns how many. */
    private int drainPreroll() {
        int cap = pre.length;
        int len = preLen;
        for (int i = 0; i < len; i++) {
            preOut[i] = pre[(preStart + i) % cap] / 32768f;
        }
        preLen = 0;
        preStart = 0;
        return len;
    }

    /** "persist.miko3.ears.preroll_ms": a length in ms within [0, MAX_PREROLL_MS], else the default. */
    static long prerollMs(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return DEFAULT_PREROLL_MS;
        }
        try {
            return Math.max(0, Math.min(MAX_PREROLL_MS, Long.parseLong(raw.trim())));
        } catch (NumberFormatException e) {
            return DEFAULT_PREROLL_MS;
        }
    }

    /** Caller holds feedLock. Whether the classifier could use this utterance's words (the wake gate). */
    private boolean wordsMatter() {
        boolean listening;
        synchronized (this) {
            listening = listenUntil != 0;
        }
        return classifier.wordsMatter(listening);
    }

    /** Caller holds feedLock. Hands audio to the recogniser, timing it for the summary. */
    private void decode(float[] buf, int n) {
        long t0 = clock.nanoTime();
        recognizer.accept(buf, n);
        long dt = clock.nanoTime() - t0;
        int chunksIn = Math.max(1, (n + CHUNK_SAMPLES - 1) / CHUNK_SAMPLES);
        uttDecodeNs += dt;
        uttFed += chunksIn;
        fed += chunksIn;
        double ms = dt / 1e6 / chunksIn; // a held utterance's catch-up counts per chunk
        synchronized (statsLock) {
            if (ms > decodeMaxMs) {
                decodeMaxMs = ms;
            }
        }
    }

    /** Caller holds feedLock. The wake gate keeps the chunk for a later decode; past the cap the oldest goes. */
    private void hold(float[] buf, int n) {
        gated++;
        if (held.length < HELD_MAX_SAMPLES) {
            held = java.util.Arrays.copyOf(held, Math.min(HELD_MAX_SAMPLES, Math.max(heldLen + n, held.length * 2)));
        }
        int over = heldLen + n - held.length;
        if (over > 0) {
            System.arraycopy(held, over, held, 0, heldLen - over);
            heldLen -= over;
        }
        System.arraycopy(buf, 0, held, heldLen, n);
        heldLen += n;
    }

    /** Caller holds feedLock. Records the utterance's decode cost per chunk fed, if it was decoded. */
    private void recordDecode() {
        if (uttFed > 0) {
            double perChunk = uttDecodeNs / 1e6 / uttFed;
            synchronized (statsLock) {
                if (decodeCount < decodeMs.length) {
                    decodeMs[decodeCount++] = perChunk;
                }
            }
        }
        uttDecodeNs = 0;
        uttFed = 0;
        heldLen = 0;
    }

    /** Caller holds feedLock. Closes the utterance in progress and delivers it if it earns a tier. */
    private void endUtterance(long now, boolean cutShort) {
        if (!recognising && heldLen > 0 && (wake || wordsMatter())) {
            recognising = true; // a listen opened in this very chunk
            decode(held, heldLen);
        }
        recordDecode();
        String text = recognising ? recognizer.text() : "";
        text = text == null ? "" : text.trim();
        Float angle = latchAngle();
        if (sampling != null) {
            sampling.stop();
            sampling = null;
        }
        angles.clear();
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
        if (uttCapAt != Long.MAX_VALUE) {
            // The listen's answer: tiered as heard in it, and the listen ends with it once its window is over.
            uttCapAt = Long.MAX_VALUE;
            answerEnded(now);
        }
        int tier = classifier.tier(text, wasWake, listening, at);
        int side = CueClassifier.side(angle);
        if (direction.sideOnly()) {
            // The chip's -90/+90 is a side, not a bearing: the brain searches that side.
            angle = null;
        }
        if (tier == CueClassifier.TIER_NONE || (text.isEmpty() && !wasWake && side == CueClassifier.SIDE_NONE)) {
            return;
        }
        if (partial) {
            partials++;
        }
        int kind = CueClassifier.kind(text, wasWake, tier);
        // wasWake: the early cue for this at already went out (every in-speech hit sends one).
        deliver(new Utterance(text, side, angle, tier, at, partial, kind, wasWake));
    }

    /** Caller holds feedLock. The median of every angle drained this utterance, or null when none. */
    private Float latchAngle() {
        if (sampling != null) {
            angles.addAll(sampling.drain());
        }
        return angles.isEmpty() ? null : VoiceDirection.median(angles);
    }

    /** Caller holds feedLock. The capture is closing: nothing is delivered. */
    private void dropUtterance() {
        if (sampling != null) {
            sampling.stop();
            sampling = null;
        }
        inSpeech = false;
        wake = false;
        angles.clear();
        recordDecode();
        recognizer.reset();
        preLen = 0;
        if (uttCapAt != Long.MAX_VALUE) {
            uttCapAt = Long.MAX_VALUE;
            answerEnded(clock.nowMs());
        }
    }

    private void deliver(Utterance u) {
        deliver(u, true);
    }

    /** endsListen: false only for the early wake cue, which must not take a conversation listen's reply. */
    private void deliver(Utterance u, boolean endsListen) {
        Client c;
        synchronized (this) {
            c = client;
            if (u.tier == CueClassifier.TIER_STRONG) {
                strong++;
            } else {
                weak++;
            }
            if (endsListen && listenUntil != 0 && (!u.text.isEmpty() || u.tier == CueClassifier.TIER_STRONG)) {
                closeListen();
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
                + " wakes=" + wakes + " " + decodeSummary() + " fed=" + fed + " gated=" + gated;
    }

    /** "decode_p50=… decode_p95=… decode_max=… decoded=N" (ms per 80 ms chunk) since the last summary, then cleared. */
    private String decodeSummary() {
        synchronized (statsLock) {
            String out;
            if (decodeCount == 0) {
                out = "decode_p50=- decode_p95=- decode_max=- decoded=0";
            } else {
                double[] sorted = java.util.Arrays.copyOf(decodeMs, decodeCount);
                java.util.Arrays.sort(sorted);
                out = "decode_p50=" + ms(percentile(sorted, 50)) + " decode_p95=" + ms(percentile(sorted, 95))
                        + " decode_max=" + ms(decodeMaxMs) + " decoded=" + decodeCount;
            }
            decodeCount = 0;
            decodeMaxMs = 0;
            return out;
        }
    }

    /** Nearest-rank percentile of an ascending, non-empty array. */
    static double percentile(double[] sorted, int p) {
        int rank = (int) Math.ceil(p / 100.0 * sorted.length);
        return sorted[Math.max(0, Math.min(sorted.length - 1, rank - 1))];
    }

    private static String ms(double v) {
        return String.valueOf(Math.round(v * 10) / 10.0);
    }
}
