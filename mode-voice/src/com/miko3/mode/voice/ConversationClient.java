package com.miko3.mode.voice;

import com.miko3.shared.Json;
import com.miko3.shared.WebSocketClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * The robot's end of the relay link (U8, KTD3, KTD4): one persistent
 * WebSocket to the relay, the lane protocol over it, and the robot-side
 * conversation state machine that the eyes mirror.
 *
 * States (the plan's robot-side diagram):
 *   UNREACHABLE  launched, link down, no welcome yet, model reported down, or a
 *                conversation that failed to open; left only on welcome or
 *                status{model_ok: true}
 *   LISTENING    welcomed, model up, waiting for the spotter's "Hey Miko"
 *   CONNECTING   conv.open sent; mic audio fills the pre-ready buffer
 *   CONVERSING   conv.ready arrived: pre-ready buffer then live mic go up, reply
 *                audio comes down (SPEAKING while the speaker is playing)
 *   CLOSING      conv.close(sleep_word / farewell_timeout) with reply audio still
 *                to play: plays out, then LISTENING on playback idle
 *
 * Audio leaves the robot only in CONVERSING (R1): sendUplink() hands every
 * chunk to UplinkGate, which drops them all in any other state, whatever the
 * capture side offers. The gate also drops the wake trim (wake_trim_ms) after
 * each wake, so the model hears the question and never "Hey Miko" (U11), and
 * holds the pre-ready buffer. Every path into LISTENING calls Audio.listen(),
 * which resumes the spotter (KTD6).
 *
 * Threads: every protocol event, timer and state change runs on one
 * "voice-client" thread, so the state machine needs no locks. Frames go out
 * through one "voice-lane-tx" thread in the order they were queued (sends can
 * block on a congested socket; nothing else waits for them). The capture
 * thread's sendUplink() and the spotter's onWake() only touch the uplink
 * state under uplinkLock and never block.
 *
 * Reconnects are in-process with backoff (base 2 s doubling to 30 s), reset
 * only by welcome or a saved relay-address change (Implementation
 * Constraints); a TCP connect alone never resets it. Commands and message
 * types this version does not know are answered cmd.result{unsupported} and
 * never acted on (R12).
 *
 * No android.*: scripts/tests/test_voice_conversation_client.py runs this
 * class on the host JVM against the real relay. VoiceEngine is the Audio;
 * ModeApp supplies the StateListener and an android.util.Log Logger.
 */
final class ConversationClient implements VoiceSettings.RelayAddressListener {
    static final int PROTO = 1;
    static final int MIC_RATE = 16000;
    static final int SPEAKER_RATE = 22050;
    static final String STRICT = "strict";
    static final String INTERRUPTIBLE = "interruptible";

    /** What the client drives on the audio side; VoiceEngine on the robot. Called
     * on the "voice-client" thread; implementations must not block. */
    interface Audio {
        /** Capture feeds the spotter again, reset and resumed; nothing goes to the
         * uplink until the next wake. Called on every path into LISTENING. */
        void listen();

        /** conv.ready: create the speaker track, ready for the first reply. */
        void openPlayer();

        /** A new reply starts; its chunks follow. */
        void replyBegins(String replyId);

        /** One downlink chunk (16-bit LE 22.05 kHz mono) of the current reply. */
        void play(byte[] chunk);

        /** audio.flush: silence the speaker now and drop what is queued. */
        void flushReply();

        /** Whether received reply audio has not finished playing. */
        boolean hasPendingAudio();

        /** Stop the speaker at once and release the track. Idempotent. */
        void closePlayer();
    }

    interface StateListener {
        /** The state the eyes should show; detail is null or a short reason. */
        void onState(VoiceState state, String detail);
    }

    interface Logger {
        void info(String msg);

        void warn(String msg);

        void error(String msg, Throwable t);
    }

    /** Timings and identity; the defaults are the plan's values. */
    static final class Config {
        String robotId = "miko3";
        String appVersion = "1.0";
        String path = "/lane";
        int connectTimeoutMs = 5000;
        /** From hello to welcome; a relay that accepts but never welcomes is down. */
        int welcomeTimeoutMs = 5000;
        /**
         * Comfortably above the relay's own 12 s so the two cannot race (KTD4). The relay
         * waits for the model to read the persona in (roughly 80 ms a word) before it
         * sends conv.ready, so this is long: the relay must always be the one to give up.
         */
        int readyTimeoutMs = 15000;
        long backoffBaseMs = 2000;
        long backoffCapMs = 30000;
        /** Mic audio kept between the wake and conv.ready (KTD6), drop-oldest. */
        int preReadyMs = 3000;
        /** CLOSING gives up waiting for playback idle after this long. */
        int closingCapMs = 10000;
        /** Uplink frames queued for a congested socket before the oldest is dropped. */
        int maxQueuedUplink = 50;
    }

    enum Phase { UNREACHABLE, LISTENING, CONNECTING, CONVERSING, CLOSING, STOPPED }

    private final VoiceSettings settings;
    private final Audio audio;
    private final StateListener states;
    private final Logger log;
    private final Config cfg;
    private final ScheduledExecutorService loop;
    private final LaneSender tx;

    // ---- loop-thread state ----------------------------------------------------
    private volatile Phase phase = Phase.UNREACHABLE;
    private volatile boolean stopped;
    private WebSocketClient link;
    private boolean welcomed;
    private long helloNanos;
    private long nextId;
    private int attempt;
    private long backoffMs;
    private ScheduledFuture<?> retryTimer;
    private ScheduledFuture<?> welcomeTimer;
    private ScheduledFuture<?> readyTimer;
    private ScheduledFuture<?> closingTimer;
    private String conv;
    private int convCount;
    private boolean speaking;
    private boolean dropping;
    private int droppedAfterFlush;
    private String detail;
    private VoiceState shownState;
    private String shownDetail;
    private boolean reportedNoAddress;

    // ---- uplink, shared with the capture and spotter threads ------------------
    private final Object uplinkLock = new Object();
    /** Decides what of the wake is kept and what is dropped; guarded by uplinkLock. */
    private final UplinkGate gate = new UplinkGate(MIC_RATE);

    ConversationClient(VoiceSettings settings, Audio audio, StateListener states, Logger log, Config cfg) {
        this.settings = settings;
        this.audio = audio;
        this.states = states;
        this.log = log;
        this.cfg = cfg;
        this.backoffMs = cfg.backoffBaseMs;
        ScheduledThreadPoolExecutor exec = new ScheduledThreadPoolExecutor(1, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "voice-client");
                t.setDaemon(true);
                return t;
            }
        });
        exec.setRemoveOnCancelPolicy(true);
        this.loop = exec;
        this.tx = new LaneSender(cfg.maxQueuedUplink, log);
    }

    /** Starts connecting to the saved relay address; returns at once. */
    void start() {
        settings.addRelayAddressListener(this);
        tx.start();
        post(new Runnable() {
            @Override
            public void run() {
                detail = "connecting to the relay";
                audio.listen();
                publish();
                connectNow();
            }
        });
    }

    /**
     * Ends everything, synchronously enough for the Activity's exit: an open
     * conversation gets conv.close{robot_request} (best effort), the link is
     * closed, and the client's threads end. Blocks the caller for at most
     * about 1.5 s. Idempotent.
     */
    void stop() {
        if (stopped) {
            return;
        }
        settings.removeRelayAddressListener(this);
        final CountDownLatch done = new CountDownLatch(1);
        boolean posted = post(new Runnable() {
            @Override
            public void run() {
                try {
                    shutdownOnLoop();
                } finally {
                    done.countDown();
                }
            }
        });
        if (posted) {
            try {
                done.await(1000, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        stopped = true;
        tx.finish(500);
        loop.shutdownNow();
    }

    Phase phase() {
        return phase;
    }

    // ---- calls from the audio side ---------------------------------------------

    /** The spotter heard the wake word. Buffering starts here, on the caller's
     * thread, so the words said right after the wake word are kept -- minus the
     * gate's wake trim, which swallows the tail of the wake word itself. */
    void onWake() {
        synchronized (uplinkLock) {
            if (phase == Phase.LISTENING && gate.dropping()) {
                beginBufferLocked();
            }
        }
        post(new Runnable() {
            @Override
            public void run() {
                handleWake();
            }
        });
    }

    /** Mic audio, 16-bit LE 16 kHz mono, any whole number of samples, captured
     * after the wake callback. Kept only while a conversation is open or opening
     * (R1), and only past the wake trim; never blocks. */
    void sendUplink(byte[] pcm) {
        sendUplink(pcm, 0, pcm.length);
    }

    void sendUplink(byte[] pcm, int off, int len) {
        synchronized (uplinkLock) {
            gate.offer(pcm, off, len);
        }
    }

    /**
     * The speaker started (playing=true, head position first advancing) or
     * stopped (false, head caught up with everything written). bufferedMs is
     * what is still to play; firstChunkToPlayMs (playing only, else -1) is the
     * time from the first chunk's arrival to the head moving.
     */
    void onPlayback(final boolean playing, final int bufferedMs, final int firstChunkToPlayMs) {
        post(new Runnable() {
            @Override
            public void run() {
                handlePlayback(playing, bufferedMs, firstChunkToPlayMs);
            }
        });
    }

    /** The settings page saved a different relay address (HTTP thread). */
    @Override
    public void onRelayAddressChanged(String newAddress) {
        post(new Runnable() {
            @Override
            public void run() {
                handleAddressChange();
            }
        });
    }

    // ---- the state machine (loop thread) ----------------------------------------

    private void handleWake() {
        if (stopped) {
            return;
        }
        if (phase != Phase.LISTENING || link == null || !welcomed) {
            log.info("wake ignored: " + phase.name().toLowerCase() + (detail != null ? " (" + detail + ")" : ""));
            if (phase == Phase.LISTENING || phase == Phase.UNREACHABLE) {
                dropUplink();
                audio.listen();
            }
            return;
        }
        final String id = "c" + Long.toString(System.currentTimeMillis(), 36) + "-" + (++convCount);
        conv = id;
        boolean strict = settings.turnTaking();
        synchronized (uplinkLock) {
            if (gate.dropping()) {
                beginBufferLocked();
            }
        }
        send("conv.open", id, "turn_taking", turnTakingName(strict));
        setPhase(Phase.CONNECTING, null);
        readyTimer = schedule(new Runnable() {
            @Override
            public void run() {
                readyExpired(id);
            }
        }, cfg.readyTimeoutMs);
    }

    private void readyExpired(String id) {
        if (phase != Phase.CONNECTING || !id.equals(conv)) {
            return;
        }
        log.warn("no conv.ready for " + id + " within " + cfg.readyTimeoutMs + " ms: closing it");
        send("conv.close", id, "reason", "robot_request");
        endConversation();
        enterUnreachable("the relay did not open the conversation");
    }

    private void onConvReady(String id) {
        if (phase != Phase.CONNECTING || id == null || !id.equals(conv)) {
            log.info("ignoring conv.ready for " + id + " (" + phase.name().toLowerCase()
                    + ", current conversation " + conv + ")");
            return;
        }
        cancel(readyTimer);
        dropping = false;
        speaking = false;
        audio.openPlayer();
        final WebSocketClient sending = link;
        int sent;
        int dropped;
        String wake;
        synchronized (uplinkLock) {
            dropped = gate.oldestDropped();
            wake = gate.summary();
            sent = gate.ready(new UplinkGate.Sink() {
                @Override
                public void frame(byte[] frame) {
                    tx.binary(sending, frame);
                }
            });
        }
        log.info("pre-ready buffer: " + sent + " chunks sent"
                + (dropped > 0 ? " (" + dropped + " oldest dropped)" : ""));
        log.info("wake audio at conv.ready: " + wake);
        setPhase(Phase.CONVERSING, null);
    }

    private void onConvClose(String id, String reason) {
        if (id == null || !id.equals(conv)) {
            log.info("ignoring conv.close for " + id + " (current conversation " + conv + ")");
            return;
        }
        if (phase == Phase.CONNECTING) {
            endConversation();
            enterUnreachable("the conversation failed to open (" + reason + ")");
        } else if (phase == Phase.CONVERSING) {
            if (("sleep_word".equals(reason) || "farewell_timeout".equals(reason)) && audio.hasPendingAudio()) {
                dropUplink();
                setPhase(Phase.CLOSING, null);
                closingTimer = schedule(new Runnable() {
                    @Override
                    public void run() {
                        if (phase == Phase.CLOSING) {
                            log.warn("no playback idle within " + cfg.closingCapMs + " ms of the close; ending");
                            endConversation();
                            enterListening();
                        }
                    }
                }, cfg.closingCapMs);
            } else {
                endConversation();
                enterListening();
            }
        }
    }

    private void handlePlayback(boolean playing, int bufferedMs, int firstChunkToPlayMs) {
        if (conv == null || stopped) {
            return;
        }
        speaking = playing;
        if (playing) {
            if (firstChunkToPlayMs >= 0) {
                send("playback", conv, "state", "playing", "buffered_ms", bufferedMs,
                        "first_chunk_to_play_ms", firstChunkToPlayMs);
            } else {
                send("playback", conv, "state", "playing", "buffered_ms", bufferedMs);
            }
        } else {
            send("playback", conv, "state", "idle", "buffered_ms", bufferedMs);
        }
        if (!playing && phase == Phase.CLOSING) {
            endConversation();
            enterListening();
        } else {
            publish();
        }
    }

    private static String turnTakingName(boolean strict) {
        return strict ? STRICT : INTERRUPTIBLE;
    }

    private void onStatus(boolean modelOk) {
        if (phase == Phase.UNREACHABLE && welcomed && modelOk) {
            enterListening();
        } else if (!modelOk && (phase == Phase.LISTENING || (phase == Phase.UNREACHABLE && welcomed))) {
            enterUnreachable("the relay reports the model server unreachable");
        }
    }

    private void onWelcome(boolean modelOk) {
        cancel(welcomeTimer);
        welcomed = true;
        backoffMs = cfg.backoffBaseMs;
        if (modelOk) {
            enterListening();
        } else {
            enterUnreachable("relay up, but it reports the model server unreachable");
        }
    }

    private void handleAddressChange() {
        if (stopped) {
            return;
        }
        log.info("relay address changed to \"" + settings.relayAddress() + "\": reconnecting");
        dropLink("relay address changed");
        backoffMs = cfg.backoffBaseMs;
        enterUnreachable("connecting to the new relay address");
        connectNow();
    }

    /** Ends any conversation with robot_request and closes the link after it. */
    private void dropLink(String why) {
        cancel(retryTimer);
        cancel(welcomeTimer);
        attempt++; // a resolve still in flight is stale now
        if (conv != null) {
            send("conv.close", conv, "reason", "robot_request");
            endConversation();
        }
        if (link != null) {
            log.info("closing the relay link: " + why);
            tx.close(link);
            link = null;
        }
        welcomed = false;
    }

    private void shutdownOnLoop() {
        dropLink("mode exiting");
        cancel(readyTimer);
        cancel(closingTimer);
        stopped = true;
        phase = Phase.STOPPED;
        dropUplink();
    }

    private void endConversation() {
        cancel(readyTimer);
        cancel(closingTimer);
        dropUplink();
        audio.closePlayer();
        endDropping();
        conv = null;
        speaking = false;
    }

    /** Ends a post-flush drop window, logging what it dropped. */
    private void endDropping() {
        if (dropping && droppedAfterFlush > 0) {
            log.info("dropped " + droppedAfterFlush + " chunks after the last flush");
        }
        dropping = false;
        droppedAfterFlush = 0;
    }

    private void enterListening() {
        dropUplink();
        audio.listen();
        setPhase(Phase.LISTENING, null);
    }

    private void enterUnreachable(String why) {
        Phase from = phase;
        dropUplink();
        if (from != Phase.LISTENING && from != Phase.UNREACHABLE) {
            audio.listen();
        }
        setPhase(Phase.UNREACHABLE, why);
    }

    private void setPhase(Phase p, String why) {
        if (phase != p) {
            log.info("state " + phase.name().toLowerCase() + " -> " + p.name().toLowerCase()
                    + (why != null ? " (" + why + ")" : ""));
        }
        phase = p;
        detail = why;
        publish();
    }

    private void publish() {
        if (stopped) {
            return;
        }
        VoiceState s;
        switch (phase) {
            case LISTENING:
                s = VoiceState.LISTENING;
                break;
            case CONNECTING:
                s = VoiceState.CONNECTING;
                break;
            case CONVERSING:
                s = speaking ? VoiceState.SPEAKING : VoiceState.CONVERSING;
                break;
            case CLOSING:
                s = VoiceState.CLOSING;
                break;
            default:
                s = VoiceState.UNREACHABLE;
                break;
        }
        String d = s == VoiceState.UNREACHABLE ? detail : null;
        if (s == shownState && (d == null ? shownDetail == null : d.equals(shownDetail))) {
            return;
        }
        shownState = s;
        shownDetail = d;
        try {
            states.onState(s, d);
        } catch (RuntimeException e) {
            log.error("state listener failed", e);
        }
    }

    // ---- connecting ------------------------------------------------------------

    private void connectNow() {
        cancel(retryTimer);
        if (stopped || link != null) {
            return;
        }
        String raw = settings.relayAddress();
        if (raw.isEmpty()) {
            if (!reportedNoAddress) {
                reportedNoAddress = true;
                log.warn("no relay address set; not connecting until one is saved on the settings page");
            }
            enterUnreachable("no relay address");
            return;
        }
        reportedNoAddress = false;
        final RelayAddress address;
        try {
            address = RelayAddress.parse(raw);
        } catch (RelayAddress.InvalidException e) {
            log.warn("saved relay address \"" + raw + "\" is invalid: " + e.getMessage());
            enterUnreachable("invalid relay address");
            return;
        }
        final int token = ++attempt;
        log.info("connecting to " + address);
        // Resolving a hostname blocks on DNS: never on the loop thread.
        Thread resolver = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final InetSocketAddress resolved = address.resolveAllowed();
                    post(new Runnable() {
                        @Override
                        public void run() {
                            openLink(token, address, resolved);
                        }
                    });
                } catch (final IOException e) {
                    post(new Runnable() {
                        @Override
                        public void run() {
                            if (token == attempt) {
                                log.warn("relay address " + address + " refused: " + e.getMessage());
                                connectFailed("cannot reach the relay: " + e.getMessage());
                            }
                        }
                    });
                }
            }
        }, "voice-relay-resolve");
        resolver.setDaemon(true);
        resolver.start();
    }

    private void openLink(int token, RelayAddress address, InetSocketAddress resolved) {
        if (token != attempt || stopped || link != null) {
            return;
        }
        // Dial the checked IP, not the name, so a second lookup can't land elsewhere.
        link = new WebSocketClient(resolved.getAddress().getHostAddress(), resolved.getPort(), cfg.path,
                new LinkListener());
        link.connect(cfg.connectTimeoutMs);
    }

    private void connectFailed(String why) {
        enterUnreachable(why);
        scheduleRetry();
    }

    private void scheduleRetry() {
        if (stopped) {
            return;
        }
        cancel(retryTimer);
        long delay = backoffMs;
        backoffMs = Math.min(backoffMs * 2, cfg.backoffCapMs);
        log.info("retrying in " + delay + " ms");
        retryTimer = schedule(new Runnable() {
            @Override
            public void run() {
                connectNow();
            }
        }, delay);
    }

    private void onOpen(final WebSocketClient c) {
        if (c != link) {
            return;
        }
        helloNanos = System.nanoTime();
        nextId = 0;
        List<Object> capabilities = Collections.emptyList();
        send("hello", null, "robot_id", cfg.robotId, "proto", PROTO, "app_version", cfg.appVersion,
                "capabilities", capabilities, "mic_rate", MIC_RATE, "speaker_rate", SPEAKER_RATE,
                "turn_taking", turnTakingName(settings.turnTaking()));
        welcomeTimer = schedule(new Runnable() {
            @Override
            public void run() {
                if (c == link && !welcomed) {
                    log.warn("no welcome within " + cfg.welcomeTimeoutMs + " ms");
                    tx.close(c);
                    link = null;
                    connectFailed("the relay did not answer hello");
                }
            }
        }, cfg.welcomeTimeoutMs);
    }

    private void onLinkEnded(WebSocketClient c, String reason, boolean wasOpen) {
        if (c != link) {
            return;
        }
        link = null;
        welcomed = false;
        cancel(welcomeTimer);
        if (conv != null) {
            log.warn("conversation " + conv + " ended: relay link lost");
            endConversation();
        }
        log.warn((wasOpen ? "relay link lost: " : "relay connect failed: ") + reason);
        connectFailed(wasOpen ? "relay link lost" : "cannot reach the relay");
    }

    private void onText(WebSocketClient c, String text) {
        if (c != link) {
            return;
        }
        log.info("rx " + text);
        Map<String, Object> msg;
        try {
            Object parsed = Json.parse(text);
            if (!(parsed instanceof Map)) {
                log.warn("relay sent a JSON value that is not an object; ignored");
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) parsed;
            msg = m;
        } catch (IllegalArgumentException e) {
            log.warn("relay sent a text frame that is not JSON: " + e.getMessage());
            return;
        }
        String type = msg.get("type") instanceof String ? (String) msg.get("type") : null;
        String msgConv = msg.get("conv") instanceof String ? (String) msg.get("conv") : null;
        if ("welcome".equals(type)) {
            onWelcome(Boolean.TRUE.equals(msg.get("model_ok")));
        } else if (!welcomed) {
            log.warn("relay sent " + type + " before welcome; ignored");
        } else if ("status".equals(type)) {
            onStatus(Boolean.TRUE.equals(msg.get("model_ok")));
        } else if ("conv.ready".equals(type)) {
            onConvReady(msgConv);
        } else if ("conv.close".equals(type)) {
            onConvClose(msgConv, String.valueOf(msg.get("reason")));
        } else if ("reply".equals(type)) {
            onReply(msgConv, idText(msg.get("id")));
        } else if ("audio.flush".equals(type)) {
            onFlush(msgConv, idText(msg.get("reply")));
        } else {
            // cmd (v1 acts on none) and any type this version does not know (R12).
            log.info(("cmd".equals(type) ? "command " + msg.get("action") : "unknown message type " + type)
                    + ": answering unsupported");
            send("cmd.result", msgConv, "re", msg.get("id"), "status", "unsupported");
        }
    }

    private boolean inReply(String msgConv) {
        return (phase == Phase.CONVERSING || phase == Phase.CLOSING) && conv != null && conv.equals(msgConv);
    }

    private void onReply(String msgConv, String replyId) {
        if (!inReply(msgConv)) {
            return;
        }
        endDropping();
        audio.replyBegins(replyId);
    }

    private void onFlush(String msgConv, String replyId) {
        if (!inReply(msgConv)) {
            return;
        }
        log.info("reply " + replyId + " flushed: dropping audio until the next reply");
        dropping = true;
        droppedAfterFlush = 0;
        audio.flushReply();
    }

    private void onBinary(WebSocketClient c, byte[] data) {
        if (c != link || conv == null) {
            return;
        }
        if (phase != Phase.CONVERSING && phase != Phase.CLOSING) {
            return;
        }
        if (dropping) {
            droppedAfterFlush++;
            return;
        }
        audio.play(data);
    }

    private static String idText(Object id) {
        return id == null ? null : String.valueOf(id);
    }

    /** WebSocketClient callbacks, moved onto the loop thread. */
    private final class LinkListener implements WebSocketClient.Listener {
        @Override
        public void onOpen(final WebSocketClient c) {
            post(new Runnable() {
                @Override
                public void run() {
                    ConversationClient.this.onOpen(c);
                }
            });
        }

        @Override
        public void onText(final WebSocketClient c, final String text) {
            post(new Runnable() {
                @Override
                public void run() {
                    ConversationClient.this.onText(c, text);
                }
            });
        }

        @Override
        public void onBinary(final WebSocketClient c, final byte[] data) {
            post(new Runnable() {
                @Override
                public void run() {
                    ConversationClient.this.onBinary(c, data);
                }
            });
        }

        @Override
        public void onConnectFailed(final WebSocketClient c, final String reason) {
            post(new Runnable() {
                @Override
                public void run() {
                    onLinkEnded(c, reason, false);
                }
            });
        }

        @Override
        public void onLinkLost(final WebSocketClient c, final String reason) {
            post(new Runnable() {
                @Override
                public void run() {
                    onLinkEnded(c, reason, true);
                }
            });
        }
    }

    // ---- sending -------------------------------------------------------------------

    /** Queues one enveloped text frame {type, id, conv, t, fields...} on the link. */
    private void send(String type, String msgConv, Object... fields) {
        if (link == null) {
            log.info("not sending " + type + ": no relay link");
            return;
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("type", type);
        m.put("id", ++nextId);
        m.put("conv", msgConv);
        m.put("t", (System.nanoTime() - helloNanos) / 1000000L);
        for (int i = 0; i + 1 < fields.length; i += 2) {
            m.put((String) fields[i], fields[i + 1]);
        }
        String text = Json.write(m);
        log.info("tx " + text);
        tx.text(link, text);
    }

    /** Stops keeping mic audio, logging what this wake had kept. */
    private void dropUplink() {
        String wake = null;
        synchronized (uplinkLock) {
            if (!gate.dropping()) {
                wake = gate.summary();
            }
            gate.drop();
        }
        if (wake != null) {
            log.info("wake audio this conversation: " + wake);
        }
    }

    /** Caller holds uplinkLock. Reads the uplink chunk size and the wake trim at each wake. */
    private void beginBufferLocked() {
        int chunkMs = Math.max(10, Math.min(1000, settings.uplinkChunkMs()));
        gate.wake(chunkMs, cfg.preReadyMs, settings.wakeTrimMs());
    }

    // ---- loop plumbing ------------------------------------------------------------

    private boolean post(Runnable r) {
        try {
            loop.execute(guard(r));
            return true;
        } catch (RejectedExecutionException e) {
            return false; // stopped
        }
    }

    private ScheduledFuture<?> schedule(Runnable r, long delayMs) {
        try {
            return loop.schedule(guard(r), delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            return null;
        }
    }

    private static void cancel(ScheduledFuture<?> f) {
        if (f != null) {
            f.cancel(false);
        }
    }

    /** An exception in a handler is logged loudly and the loop carries on. */
    private Runnable guard(final Runnable r) {
        return new Runnable() {
            @Override
            public void run() {
                try {
                    r.run();
                } catch (RuntimeException e) {
                    log.error("voice client handler failed", e);
                }
            }
        };
    }
}
