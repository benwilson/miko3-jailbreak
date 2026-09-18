package com.miko3.mode.voice;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayDeque;

/**
 * One conversation's speaker track and its "voice-player" thread. The track
 * is paused whenever it has nothing to play (idle), so the per-turn
 * underrun count covers only the time a reply was actually playing.
 */
final class VoicePlayer implements Runnable {
    private final VoiceEngine engine;
    private final AudioTrack track;
    private final int prebufferChunks;
    private final Thread thread;
    private volatile boolean running = true;

    // Guarded by this: the queue and the requests other threads make.
    private final ArrayDeque<byte[]> queue = new ArrayDeque<byte[]>();
    private boolean flushRequested;
    private long firstChunkAtMs = -1; // arrival of the first chunk since idle
    private int overruns; // chunks dropped on a full queue this turn
    private String replyId;

    // Player thread only (pending() reads the volatiles).
    private volatile boolean started; // track started, not yet idle
    private boolean needPlay; // started, play() waits for the first write
    private volatile byte[] current;
    private int currentOff;
    private long framesWritten; // since creation or the last flush
    private long headAtStart;
    private boolean playing;
    private int underrunsAtStart;

    static VoicePlayer create(VoiceEngine engine, int prebufferChunks) {
        int minBuf = AudioTrack.getMinBufferSize(ConversationClient.SPEAKER_RATE, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) {
            Log.e(VoiceEngine.TAG, "unsupported speaker configuration (" + minBuf + "); replies will not play");
            return null;
        }
        AudioTrack track;
        try {
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(ConversationClient.SPEAKER_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(Math.max(minBuf, VoiceEngine.SPEAKER_CHUNK_BYTES * (prebufferChunks + 1)))
                    .build();
        } catch (RuntimeException e) {
            Log.e(VoiceEngine.TAG, "speaker track creation failed; replies will not play", e);
            return null;
        }
        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            Log.e(VoiceEngine.TAG, "speaker track failed to initialize; replies will not play");
            track.release();
            return null;
        }
        // Cap what the track itself holds to about the prebuffer, so a flush
        // discards little and the head position tracks what is audible.
        int asked = VoiceEngine.SPEAKER_CHUNK_FRAMES * prebufferChunks;
        int effective = track.setBufferSizeInFrames(asked);
        Log.i(VoiceEngine.TAG, "speaker track ready: " + ConversationClient.SPEAKER_RATE
                + " Hz voice-communication, capacity " + track.getBufferCapacityInFrames()
                + " frames, effective buffer " + effective + " frames (asked " + asked + "), prebuffer "
                + prebufferChunks + " chunks");
        return new VoicePlayer(engine, track, prebufferChunks);
    }

    private VoicePlayer(VoiceEngine engine, AudioTrack track, int prebufferChunks) {
        this.engine = engine;
        this.track = track;
        this.prebufferChunks = prebufferChunks;
        this.thread = new Thread(this, "voice-player");
        thread.start();
    }

    synchronized void replyBegins(String id) {
        replyId = id;
    }

    synchronized void enqueue(byte[] chunk) {
        if (queue.size() >= VoiceEngine.PLAYER_QUEUE_CHUNKS) {
            overruns++;
            if (overruns == 1 || overruns % 25 == 0) {
                Log.w(VoiceEngine.TAG, "speaker queue full: dropped " + overruns + " chunks of reply " + replyId);
            }
            return;
        }
        if (!started && firstChunkAtMs < 0) {
            firstChunkAtMs = SystemClock.elapsedRealtime();
        }
        queue.add(chunk);
        notifyAll();
    }

    synchronized void flush() {
        queue.clear();
        flushRequested = true;
        notifyAll();
    }

    boolean pending() {
        synchronized (this) {
            if (!queue.isEmpty()) {
                return true;
            }
        }
        return started || current != null;
    }

    /** Silences the speaker at once, ends the thread, releases the track. */
    void shutdown() {
        running = false;
        try {
            track.pause();
            track.flush();
        } catch (IllegalStateException ignored) {
        }
        synchronized (this) {
            notifyAll();
        }
        VoiceEngine.joinQuietly(thread, 500);
        track.release();
        Log.i(VoiceEngine.TAG, "speaker track released");
    }

    @Override
    public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        try {
            loop();
        } catch (RuntimeException e) {
            Log.e(VoiceEngine.TAG, "speaker thread failed", e);
        }
    }

    private void loop() {
        while (running) {
            boolean flush;
            int queued;
            synchronized (this) {
                flush = flushRequested;
                flushRequested = false;
                if (current == null && !flush) {
                    byte[] next = queue.poll();
                    if (next != null) {
                        current = next;
                        currentOff = 0;
                    }
                }
                queued = queue.size() + (current != null ? 1 : 0);
            }
            if (flush) {
                doFlush();
                continue;
            }
            long now = SystemClock.elapsedRealtime();
            if (!started && current != null) {
                long first;
                synchronized (this) {
                    first = firstChunkAtMs;
                }
                if (queued >= prebufferChunks || (first >= 0 && now - first >= VoiceEngine.PREBUFFER_HOLD_MS)) {
                    startTrack();
                }
            }
            boolean wrote = false;
            if (started && current != null) {
                int n = track.write(current, currentOff, current.length - currentOff, AudioTrack.WRITE_NON_BLOCKING);
                if (n > 0) {
                    wrote = true;
                    currentOff += n;
                    framesWritten += n / 2;
                    if (currentOff >= current.length) {
                        current = null;
                    }
                } else if (n < 0) {
                    Log.e(VoiceEngine.TAG, "speaker write failed: " + n);
                    current = null;
                }
            }
            if (needPlay && (wrote || current == null)) {
                // Data first, then play(): a streaming track started empty
                // underruns before its first frame.
                needPlay = false;
                underrunsAtStart = track.getUnderrunCount();
                try {
                    track.play();
                } catch (IllegalStateException e) {
                    Log.e(VoiceEngine.TAG, "speaker play failed: " + e);
                }
            }
            if (started) {
                checkHead(now);
            }
            if (!wrote) {
                synchronized (this) {
                    if (running && !flushRequested) {
                        try {
                            if (!started && current == null && queue.isEmpty()) {
                                // Idle: only enqueue(), flush() or shutdown() (all notify
                                // under this lock) can give the thread work.
                                wait();
                            } else {
                                wait(started ? 10 : 50);
                            }
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                }
            }
        }
    }

    private void startTrack() {
        started = true;
        needPlay = true;
        headAtStart = head();
    }

    private long head() {
        return track.getPlaybackHeadPosition() & 0xffffffffL;
    }

    /** Playing when the head first moves; idle when it has caught up with every
     * frame written and nothing is queued (KTD6: never from the queue alone). */
    private void checkHead(long now) {
        long head = head();
        if (!playing && head > headAtStart) {
            playing = true;
            int toPlay;
            synchronized (this) {
                toPlay = firstChunkAtMs >= 0 ? (int) (now - firstChunkAtMs) : -1;
            }
            report(true, head, toPlay);
        }
        boolean empty;
        synchronized (this) {
            empty = queue.isEmpty() && current == null;
        }
        if (empty && head >= framesWritten) {
            goIdle(head);
        }
    }

    private void goIdle(long head) {
        try {
            track.pause();
        } catch (IllegalStateException ignored) {
        }
        started = false;
        needPlay = false;
        int underruns = track.getUnderrunCount() - underrunsAtStart;
        int dropped;
        String id;
        synchronized (this) {
            dropped = overruns;
            overruns = 0;
            firstChunkAtMs = -1;
            id = replyId;
        }
        if (playing) {
            playing = false;
            Log.i(VoiceEngine.TAG, "reply " + id + " played: underruns=" + underruns + " dropped=" + dropped);
            report(false, head, -1);
        }
    }

    private void doFlush() {
        try {
            track.pause();
            track.flush();
        } catch (IllegalStateException e) {
            Log.w(VoiceEngine.TAG, "speaker flush failed: " + e);
        }
        current = null;
        framesWritten = 0;
        headAtStart = 0;
        Log.i(VoiceEngine.TAG, "speaker flushed (reply " + replyId + ")");
        goIdle(0);
        // The next reply's first chunk starts the track again (play after flush).
    }

    private void report(boolean nowPlaying, long head, int firstChunkToPlayMs) {
        ConversationClient c = engine.client;
        if (c == null) {
            return;
        }
        int queued;
        synchronized (this) {
            queued = queue.size();
        }
        long bufferedFrames = Math.max(0, framesWritten - head) + (long) queued * VoiceEngine.SPEAKER_CHUNK_FRAMES;
        c.onPlayback(nowPlaying, (int) (bufferedFrames * 1000 / ConversationClient.SPEAKER_RATE),
                firstChunkToPlayMs);
    }
}
