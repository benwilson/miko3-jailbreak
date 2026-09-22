package com.miko3.mode.voice;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * Host-JVM driver for the real ConversationClient (U8), run by
 * scripts/tests/test_voice_conversation_client.py against the real relay
 * (relay_stub.py or relay.main). It stands in for VoiceEngine with a fake
 * microphone and a fake speaker, and for the settings page with an in-memory
 * VoiceSettings store.
 *
 * Args (key=value, all optional): address=host:port, strict=true|false,
 * ready_ms, backoff_base_ms, backoff_cap_ms, welcome_ms, connect_ms, trim
 * (wake_trim_ms, default 0 here so mic sequence numbers stay frame-aligned).
 *
 * Commands on stdin, one per line:
 *   wake              the spotter heard "Hey Miko" (prints WAKE mic_seq=<next chunk's seq>)
 *   address HOST:PORT save a new relay address ("address" alone clears it)
 *   strict true|false save the turn-taking switch
 *   stop              ConversationClient.stop(), as the Activity's exit does
 *
 * Every line printed is one event, flushed at once:
 *   STATE <wire name> | <detail>   each state the eyes would show
 *   AUDIO <what>                   each call the client makes on its audio side
 *   LOG <level> <message>          the client's own log
 *
 * The fake microphone produces an 80 ms chunk every 80 ms from start to
 * stop and hands every one to sendUplink() whatever the state, so the client
 * alone decides what leaves the robot (R1). Each chunk's first four bytes are
 * its sequence number (little-endian int); the other samples are a constant
 * 1000. The fake speaker plays one received chunk per 80 ms of wall time and
 * reports playing/idle the way VoiceEngine derives them from the head position.
 */
public final class VoiceClientHarness {
    private static final Object OUT = new Object();

    static void out(String line) {
        synchronized (OUT) {
            System.out.println(line);
            System.out.flush();
        }
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = new HashMap<String, String>();
        for (String a : args) {
            int eq = a.indexOf('=');
            opts.put(a.substring(0, eq), a.substring(eq + 1));
        }
        final Map<String, Object> prefs = new HashMap<String, Object>();
        prefs.put(VoiceSettings.KEY_RELAY_ADDRESS, opt(opts, "address", ""));
        prefs.put(VoiceSettings.KEY_TURN_TAKING, Boolean.valueOf(opt(opts, "strict", "false")));
        // Each mic chunk carries its sequence number in its first four bytes, which a
        // trim that is not a whole number of chunks would shift out of a frame's head.
        // The default here is therefore 0, not the product's wake_trim_ms default; the
        // trim tests pass a multiple of the 80 ms chunk.
        prefs.put(VoiceSettings.KEY_WAKE_TRIM_MS, Integer.valueOf(opt(opts, "trim", "0")));
        final VoiceSettings settings = new VoiceSettings(new VoiceSettings.Store() {
            @Override
            public synchronized String getString(String key, String def) {
                Object v = prefs.get(key);
                return v instanceof String ? (String) v : def;
            }

            @Override
            public synchronized boolean getBoolean(String key, boolean def) {
                Object v = prefs.get(key);
                return v instanceof Boolean ? (Boolean) v : def;
            }

            @Override
            public synchronized int getInt(String key, int def) {
                Object v = prefs.get(key);
                return v instanceof Integer ? (Integer) v : def;
            }

            @Override
            public synchronized void putString(String key, String value) {
                prefs.put(key, value);
            }

            @Override
            public synchronized void putBoolean(String key, boolean value) {
                prefs.put(key, value);
            }
        });

        ConversationClient.Config cfg = new ConversationClient.Config();
        cfg.robotId = "harness-robot";
        cfg.appVersion = "harness";
        cfg.readyTimeoutMs = Integer.parseInt(opt(opts, "ready_ms", String.valueOf(cfg.readyTimeoutMs)));
        cfg.backoffBaseMs = Long.parseLong(opt(opts, "backoff_base_ms", String.valueOf(cfg.backoffBaseMs)));
        cfg.backoffCapMs = Long.parseLong(opt(opts, "backoff_cap_ms", String.valueOf(cfg.backoffCapMs)));
        cfg.welcomeTimeoutMs = Integer.parseInt(opt(opts, "welcome_ms", String.valueOf(cfg.welcomeTimeoutMs)));
        cfg.connectTimeoutMs = Integer.parseInt(opt(opts, "connect_ms", String.valueOf(cfg.connectTimeoutMs)));

        final FakeAudio audio = new FakeAudio();
        ConversationClient.StateListener states = new ConversationClient.StateListener() {
            @Override
            public void onState(VoiceState state, String detail) {
                out("STATE " + state.wireName() + " | " + (detail == null ? "" : detail));
            }
        };
        ConversationClient.Logger logger = new ConversationClient.Logger() {
            @Override
            public void info(String msg) {
                out("LOG I " + msg);
            }

            @Override
            public void warn(String msg) {
                out("LOG W " + msg);
            }

            @Override
            public void error(String msg, Throwable t) {
                out("LOG E " + msg + (t != null ? " (" + t + ")" : ""));
            }
        };
        final ConversationClient client = new ConversationClient(settings, audio, states, logger, cfg);
        audio.client = client;
        audio.start();
        client.start();

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            line = line.trim();
            if (line.equals("wake")) {
                out("WAKE mic_seq=" + audio.micSeq);
                client.onWake();
            } else if (line.equals("address") || line.startsWith("address ")) {
                String addr = line.length() > 8 ? line.substring(8).trim() : "";
                settings.save(addr, settings.turnTaking());
                out("SAVED address " + addr);
            } else if (line.startsWith("strict ")) {
                settings.save(settings.relayAddress(), Boolean.parseBoolean(line.substring(7).trim()));
                out("SAVED strict " + settings.turnTaking());
            } else if (line.equals("stop")) {
                client.stop();
                audio.stopMic();
                out("STOPPED");
            } else if (!line.isEmpty()) {
                out("UNKNOWN " + line);
            }
        }
        client.stop();
        audio.stopMic();
        System.exit(0);
    }

    private static String opt(Map<String, String> opts, String key, String def) {
        String v = opts.get(key);
        return v != null ? v : def;
    }

    /** VoiceEngine's stand-in: see the class comment. */
    static final class FakeAudio implements ConversationClient.Audio {
        static final int MIC_CHUNK_BYTES = 2560;
        static final int SPEAKER_CHUNK_MS = 80;

        volatile ConversationClient client;
        /** The sequence number the next mic chunk will carry. */
        volatile int micSeq = 1;
        private volatile boolean micRunning = true;
        private final ArrayDeque<byte[]> queue = new ArrayDeque<byte[]>();
        private boolean open; // guarded by this
        private boolean playing; // guarded by this
        private long playEndMs; // guarded by this
        private long firstChunkAtMs = -1; // guarded by this
        private long playedBytes; // guarded by this, since openPlayer

        void start() {
            Thread mic = new Thread(new Runnable() {
                @Override
                public void run() {
                    long next = System.currentTimeMillis();
                    while (micRunning) {
                        byte[] chunk = new byte[MIC_CHUNK_BYTES];
                        for (int i = 4; i < chunk.length; i += 2) {
                            chunk[i] = (byte) (1000 & 0xff);
                            chunk[i + 1] = (byte) (1000 >> 8);
                        }
                        int seq = micSeq;
                        chunk[0] = (byte) seq;
                        chunk[1] = (byte) (seq >> 8);
                        chunk[2] = (byte) (seq >> 16);
                        chunk[3] = (byte) (seq >> 24);
                        micSeq = seq + 1;
                        ConversationClient c = client;
                        if (c != null) {
                            c.sendUplink(chunk);
                        }
                        next += 80;
                        long wait = next - System.currentTimeMillis();
                        if (wait > 0) {
                            try {
                                Thread.sleep(wait);
                            } catch (InterruptedException e) {
                                return;
                            }
                        }
                    }
                }
            }, "fake-mic");
            mic.setDaemon(true);
            mic.start();
            Thread speaker = new Thread(new Runnable() {
                @Override
                public void run() {
                    speakerLoop();
                }
            }, "fake-speaker");
            speaker.setDaemon(true);
            speaker.start();
        }

        void stopMic() {
            micRunning = false;
        }

        private void speakerLoop() {
            while (true) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    return;
                }
                boolean report = false;
                boolean nowPlaying = false;
                int buffered = 0;
                int toPlay = -1;
                long played = 0;
                synchronized (this) {
                    long now = System.currentTimeMillis();
                    if (!open) {
                        continue;
                    }
                    if (now >= playEndMs && !queue.isEmpty()) {
                        byte[] chunk = queue.poll();
                        playedBytes += chunk.length;
                        playEndMs = Math.max(now, playEndMs) + SPEAKER_CHUNK_MS;
                        if (!playing) {
                            playing = true;
                            report = true;
                            nowPlaying = true;
                            toPlay = firstChunkAtMs >= 0 ? (int) (now - firstChunkAtMs) : 0;
                        }
                    } else if (playing && now >= playEndMs && queue.isEmpty()) {
                        playing = false;
                        firstChunkAtMs = -1;
                        report = true;
                        played = playedBytes;
                    }
                    buffered = (int) (Math.max(0, playEndMs - now) + queue.size() * SPEAKER_CHUNK_MS);
                }
                if (report) {
                    out(nowPlaying ? "AUDIO playing" : "AUDIO idle played=" + played);
                    client.onPlayback(nowPlaying, buffered, toPlay);
                }
            }
        }

        @Override
        public void listen() {
            out("AUDIO listen");
        }

        @Override
        public synchronized void openPlayer() {
            open = true;
            playing = false;
            playedBytes = 0;
            queue.clear();
            out("AUDIO open-player");
        }

        @Override
        public void replyBegins(String replyId) {
            out("AUDIO reply " + replyId);
        }

        @Override
        public synchronized void play(byte[] chunk) {
            if (!open) {
                out("AUDIO play-without-player");
                return;
            }
            if (!playing && queue.isEmpty() && firstChunkAtMs < 0) {
                firstChunkAtMs = System.currentTimeMillis();
            }
            queue.add(chunk);
        }

        @Override
        public synchronized void flushReply() {
            int dropped = queue.size();
            queue.clear();
            playEndMs = System.currentTimeMillis();
            out("AUDIO flush dropped=" + dropped);
        }

        @Override
        public synchronized boolean hasPendingAudio() {
            return open && (!queue.isEmpty() || System.currentTimeMillis() < playEndMs);
        }

        @Override
        public synchronized void closePlayer() {
            if (!open) {
                return;
            }
            out("AUDIO close-player pending=" + queue.size() + " played=" + playedBytes);
            open = false;
            playing = false;
            queue.clear();
            playEndMs = 0;
            firstChunkAtMs = -1;
        }
    }
}
