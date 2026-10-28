package com.miko3.launcher;

/**
 * Debug-only "heard text" injection (2026-10-02): lets scripts/robot-say.py test a
 * conversation or a spoken command on the real robot with nobody speaking.
 *
 * It enters exactly where real speech does. It wraps the ears session's wake-word
 * engine, VAD gate and recogniser, and while an injected utterance plays those
 * three act as if someone said it: the gate reports speech for as long as the
 * words would take to say, the wake engine fires about WAKE_AT_MS in for a call,
 * the recogniser's text is the injected words, and its endpoint comes
 * ENDPOINT_AFTER_MS into the silence that follows. Everything after that is
 * EarsSession's real path: the deaf window, the early wake cue, a listen's
 * "answering" and its answer hold, the provisional answer at the endpoint, the
 * ANSWER_SILENCE_MS end, the classifier's tier and the Binder delivery to the
 * mode. While an utterance plays the real microphone's verdicts are ignored, and
 * for TAIL_MS after its words so the silence that ends an answer is real silence;
 * the next offer starts as soon as the session has taken the words, as a speaker
 * going on after a pause would.
 *
 * Gate: the system property PROPERTY must read "1" when an utterance is offered;
 * otherwise offer() refuses it and the wrappers pass every call straight through.
 * The property defaults off; robot-say.py sets it and always clears it on exit.
 * Only root (and the system) can deliver EarsInjectReceiver's broadcast.
 *
 * Privacy: the injected words are handled like the recogniser's own. Nothing here
 * logs them; Diag gets a word count and timings only.
 *
 * Plain Java (no android.*): scripts/tests/test_listen_service.py drives it with
 * the ears session's fakes.
 */
final class EarsInject {
    /** The debug gate: "1" arms injection; unset (the default) leaves it inert. */
    static final String PROPERTY = "debug.miko3.ears_inject";
    /** EarsInjectReceiver's action. */
    static final String ACTION = "com.miko3.launcher.action.EARS_INJECT";
    static final String EXTRA_TEXT = "text";
    static final String EXTRA_WAKE = "wake";

    static final long CHUNK_MS = 80;
    /** Speech before the first word and after the last, as a speaker's breath and trailing sound. */
    static final long LEAD_MS = 240;
    /** About a conversational rate: 200 words a minute. */
    static final long MS_PER_WORD = 300;
    /** The vendor engine fires on "Hey Miko" about this far into the speech. */
    static final long WAKE_AT_MS = 640;
    /** Silence after the words before the recogniser's endpoint, as rule 2's trailing silence. */
    static final long ENDPOINT_AFTER_MS = 480;
    /** Real audio is ignored this long after the words: longer than EarsSession.ANSWER_SILENCE_MS. */
    static final long TAIL_MS = EarsSession.ANSWER_SILENCE_MS + 800;
    /** An offer the capture never picks up (ears closed, a long line playing) is dropped after this. */
    static final long STALE_MS = 30000;
    static final int MAX_CHARS = 300;
    static final String WAKE_WORDS = "HEY MIKO";

    interface Props {
        /** The property's value, or "" when unset. */
        String get(String key);
    }

    private enum Phase { IDLE, SPEAK, TAIL }

    private final EarsSession.Clock clock;
    private final Props props;
    private final EarsSession.Diag diag;

    // Guarded by this: the receiver's thread offers, the capture thread plays.
    private String pendingText;
    private boolean pendingWake;
    private long pendingAt;
    private Phase phase = Phase.IDLE;
    private String text = "";
    private boolean wake;
    private boolean wakeFired;
    private boolean consumed;
    private long speakMs;
    private long spokenMs;
    private long quietMs;

    EarsInject(EarsSession.Clock clock, Props props, EarsSession.Diag diag) {
        this.clock = clock;
        this.props = props;
        this.diag = diag;
    }

    /** Whether the debug property arms injection right now. */
    boolean armed() {
        return "1".equals(props.get(PROPERTY).trim());
    }

    /**
     * Queues one utterance to be "heard" at the capture's next chunk outside the deaf
     * window, replacing any not yet started. wake: a call, so "HEY MIKO" leads the words
     * (unless they already start with it) and the wake engine fires in it. False when
     * the property is off or there is nothing to say.
     */
    synchronized boolean offer(String raw, boolean wake) {
        if (!armed()) {
            diag.log("inject refused: " + PROPERTY + " is off");
            return false;
        }
        String words = normalize(raw);
        if (wake && !words.startsWith(WAKE_WORDS)) {
            words = words.isEmpty() ? WAKE_WORDS : WAKE_WORDS + " " + words;
        }
        if (words.isEmpty()) {
            diag.log("inject refused: no words");
            return false;
        }
        pendingText = words;
        pendingWake = wake;
        pendingAt = clock.nowMs();
        diag.log("inject queued: " + (wake ? "a call, " : "an utterance, ") + count(words) + " word(s)");
        return true;
    }

    /** Upper case like the model's tokens, letters, digits and apostrophes only, single spaces, capped. */
    static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        boolean space = false;
        for (int i = 0; i < raw.length() && b.length() < MAX_CHARS; i++) {
            char c = raw.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '\'') {
                if (space && b.length() > 0) {
                    b.append(' ');
                }
                space = false;
                b.append(Character.toUpperCase(c));
            } else {
                space = true;
            }
        }
        return b.toString().trim();
    }

    static int count(String words) {
        return words.isEmpty() ? 0 : words.split(" ").length;
    }

    /** How long the words take to say, in whole chunks: the lead either side and MS_PER_WORD each. */
    static long speakMs(String words, boolean wake) {
        long ms = 2 * LEAD_MS + MS_PER_WORD * count(words);
        if (wake) {
            ms = Math.max(ms, WAKE_AT_MS + 2 * CHUNK_MS);
        }
        return (ms + CHUNK_MS - 1) / CHUNK_MS * CHUNK_MS;
    }

    synchronized boolean active() {
        return phase != Phase.IDLE;
    }

    /** The gate's call each chunk outside the deaf window drives the injected utterance's clock. */
    private synchronized Boolean gateChunk() {
        if (phase == Phase.TAIL && consumed && pendingText != null) {
            // The last words are spent: the next ones start now, as a speaker going on would.
            phase = Phase.IDLE;
        }
        if (phase == Phase.IDLE) {
            if (pendingText == null) {
                return null;
            }
            String t = pendingText;
            pendingText = null;
            if (clock.nowMs() - pendingAt > STALE_MS) {
                diag.log("inject dropped: not heard within " + STALE_MS + " ms");
                return null;
            }
            text = t;
            wake = pendingWake;
            wakeFired = false;
            consumed = false;
            spokenMs = 0;
            quietMs = 0;
            speakMs = speakMs(t, wake);
            phase = Phase.SPEAK;
            diag.log("inject speaking: " + speakMs + " ms of speech");
        }
        if (phase == Phase.SPEAK) {
            spokenMs += CHUNK_MS;
            if (spokenMs >= speakMs) {
                phase = Phase.TAIL;
                quietMs = 0;
            }
            return Boolean.TRUE;
        }
        quietMs += CHUNK_MS;
        if (quietMs >= TAIL_MS) {
            phase = Phase.IDLE;
            text = "";
        }
        return Boolean.FALSE;
    }

    /** Null to let the real engine's verdict through. */
    private synchronized Boolean spotterChunk() {
        if (phase == Phase.IDLE) {
            return null;
        }
        if (phase == Phase.SPEAK && wake && !wakeFired && spokenMs >= WAKE_AT_MS) {
            wakeFired = true;
            return Boolean.TRUE;
        }
        return Boolean.FALSE;
    }

    private synchronized boolean playing() {
        return phase != Phase.IDLE;
    }

    private synchronized Boolean endpoint() {
        if (phase == Phase.IDLE) {
            return null;
        }
        return phase == Phase.TAIL && !consumed && quietMs >= ENDPOINT_AFTER_MS;
    }

    private synchronized String words() {
        if (phase == Phase.IDLE) {
            return null;
        }
        return consumed ? "" : text;
    }

    /** The session read the words and reset the stream: they are spent; a cut utterance stops speaking. */
    private synchronized void streamReset() {
        if (phase == Phase.IDLE) {
            return;
        }
        if (!consumed) {
            diag.log("inject spent: the session took the words");
        }
        consumed = true;
        if (phase == Phase.SPEAK) {
            phase = Phase.TAIL;
            quietMs = 0;
        }
    }

    EarsSession.Spotter spotter(final EarsSession.Spotter real) {
        return new EarsSession.Spotter() {
            @Override
            public boolean hears(short[] pcm, int n) {
                boolean heard = real.hears(pcm, n);
                Boolean injected = spotterChunk();
                return injected == null ? heard : injected;
            }

            @Override
            public void reset() {
                real.reset();
            }
        };
    }

    EarsSession.Gate gate(final EarsSession.Gate real) {
        return new EarsSession.Gate() {
            @Override
            public boolean speech(float[] samples, int n) {
                boolean heard = real.speech(samples, n); // keeps the real gate's state current
                Boolean injected = gateChunk();
                return injected == null ? heard : injected;
            }

            @Override
            public void reset() {
                real.reset();
            }
        };
    }

    EarsSession.Recognizer recognizer(final EarsSession.Recognizer real) {
        return new EarsSession.Recognizer() {
            @Override
            public void accept(float[] samples, int n) {
                if (!playing()) {
                    real.accept(samples, n);
                }
            }

            @Override
            public boolean isEndpoint() {
                Boolean injected = endpoint();
                return injected == null ? real.isEndpoint() : injected;
            }

            @Override
            public String text() {
                String injected = words();
                return injected == null ? real.text() : injected;
            }

            @Override
            public void reset() {
                real.reset();
                streamReset();
            }
        };
    }
}
