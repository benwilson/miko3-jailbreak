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
 *
 * SpeakerSizing owns every size here and explains the trade; this class only
 * makes the AudioTrack calls. The one piece of choreography worth repeating:
 * the track's buffer is its start threshold on this platform, so it is opened
 * to the prebuffer while starting and raised to the full cushion as soon as
 * the playback head moves (openCushion), and kickStart() pads with silence in
 * the corner where even the prebuffer never fills, so a short reply can never
 * sit in a track that refuses to start.
 */
final class VoicePlayer implements Runnable {
    /** Nothing has played this long after the track was started: force it out. */
    private static final long START_KICK_MS = 400;
    /** Not even the kick got a frame out: give the conversation its turn back. */
    private static final long STALL_GIVEUP_MS = 3000;
    private static final byte[] SILENCE = new byte[SpeakerSizing.CHUNK_BYTES];

    private final VoiceEngine engine;
    private final AudioTrack track;
    private final SpeakerSizing sizing;
    private final int capacityFrames;
    private final Thread thread;
    private volatile boolean running = true;

    // Guarded by this: the queue and the requests other threads make.
    private final ArrayDeque<byte[]> queue = new ArrayDeque<byte[]>();
    private boolean flushRequested;
    private long firstChunkAtMs = -1; // arrival of the first chunk since idle
    private long lastChunkAtMs = -1; // arrival of the newest chunk since idle
    private int overruns; // chunks dropped on a full queue this turn
    private int peakQueued; // deepest the queue got this turn
    private String replyId;

    // Player thread only (pending() reads the volatiles).
    private volatile boolean started; // track started, not yet idle
    private boolean needPlay; // started, play() waits for the first write
    private volatile byte[] current;
    private int currentOff;
    private long framesWritten; // since creation or the last flush
    private long headAtStart;
    private long startedAtMs;
    private boolean playing;
    private boolean cushionOpen; // buffer raised from the prebuffer to the cushion
    private boolean kicked; // silence already used to force this start
    private int trackBufferFrames; // what the track last reported for its buffer
    private int underrunsAtStart;

    static VoicePlayer create(VoiceEngine engine, VoiceSettings settings) {
        int minBuf = AudioTrack.getMinBufferSize(ConversationClient.SPEAKER_RATE, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) {
            Log.e(VoiceEngine.TAG, "unsupported speaker configuration (" + minBuf + "); replies will not play");
            return null;
        }
        SpeakerSizing sizing = SpeakerSizing.of(settings, minBuf / 2);
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
                    // The ceiling, not the working size: the track starts on the
                    // prebuffer and is raised towards this once it is playing.
                    .setBufferSizeInBytes(Math.max(minBuf, sizing.capacityBytes()))
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
        // U12 replaced "hold about the prebuffer, so a flush discards little"
        // with a cushion: barge-in was measured to be impossible on this
        // hardware (the platform echo canceller ducks the microphone to RMS 41
        // while the speaker plays), so a cheap flush buys nothing and smooth
        // playback buys everything. Each start still opens at startFrames().
        int effective = track.setBufferSizeInFrames(sizing.startFrames());
        Log.i(VoiceEngine.TAG, "speaker track ready: " + ConversationClient.SPEAKER_RATE
                + " Hz voice-communication, capacity " + track.getBufferCapacityInFrames()
                + " frames, start buffer " + effective + " frames (asked " + sizing.startFrames()
                + "), cushion " + sizing.trackFrames() + " frames (" + sizing.trackMs() + " ms), prebuffer "
                + sizing.prebufferChunks() + " chunks (" + sizing.prebufferMs() + " ms), queue "
                + sizing.queueChunks() + " chunks (" + sizing.queueMs() + " ms)");
        return new VoicePlayer(engine, track, sizing, effective);
    }

    private VoicePlayer(VoiceEngine engine, AudioTrack track, SpeakerSizing sizing, int bufferFrames) {
        this.engine = engine;
        this.track = track;
        this.sizing = sizing;
        this.capacityFrames = track.getBufferCapacityInFrames();
        this.trackBufferFrames = bufferFrames;
        this.thread = new Thread(this, "voice-player");
        thread.start();
    }

    synchronized void replyBegins(String id) {
        replyId = id;
    }

    synchronized void enqueue(byte[] chunk) {
        // The queue now holds the relay's whole burst, so this is a backstop
        // against a wedged track rather than routine trimming (U12).
        if (queue.size() >= sizing.queueChunks()) {
            overruns++;
            if (overruns == 1 || overruns % 25 == 0) {
                Log.w(VoiceEngine.TAG, "speaker queue full at " + queue.size() + " chunks: dropped "
                        + overruns + " chunks of reply " + replyId);
            }
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (!started && firstChunkAtMs < 0) {
            firstChunkAtMs = now;
        }
        lastChunkAtMs = now;
        queue.add(chunk);
        if (queue.size() > peakQueued) {
            peakQueued = queue.size();
        }
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
                long last;
                synchronized (this) {
                    first = firstChunkAtMs;
                    last = lastChunkAtMs;
                }
                if (sizing.shouldStart(queued, first >= 0 ? now - first : -1, last >= 0 ? now - last : -1)) {
                    startTrack(now);
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

    private void startTrack(long now) {
        started = true;
        needPlay = true;
        cushionOpen = false;
        kicked = false;
        startedAtMs = now;
        headAtStart = head();
        // Back down to the start threshold: the track is drained and paused
        // here, so this only decides how little has to be written before the
        // first frame is emitted. openCushion() raises it again once it is.
        setTrackBuffer(sizing.startFrames());
    }

    /** setBufferSizeInFrames, remembering what the track actually gave us. */
    private void setTrackBuffer(int frames) {
        try {
            int got = track.setBufferSizeInFrames(frames);
            if (got > 0) {
                trackBufferFrames = got;
            }
        } catch (IllegalStateException e) {
            Log.w(VoiceEngine.TAG, "speaker buffer resize to " + frames + " failed: " + e);
        }
    }

    /** Frames are audible: open the buffer from the start threshold to the full
     * cushion, so the rest of the reply survives a late player-thread wakeup. */
    private void openCushion() {
        cushionOpen = true;
        int before = trackBufferFrames;
        setTrackBuffer(sizing.trackFrames());
        if (trackBufferFrames != before) {
            Log.i(VoiceEngine.TAG, "speaker cushion open: buffer " + before + " -> " + trackBufferFrames
                    + " frames of " + capacityFrames);
        }
    }

    /**
     * The track was started but nothing has come out: less than a start
     * threshold of audio exists (a reply shorter than the prebuffer) and the
     * platform is holding it. Open the buffer all the way and top it up with
     * silence until it refuses more, which crosses the threshold whether the
     * platform measures it against the buffer size or the capacity. The padding
     * lands after the words, so it costs a beat before the mic reopens and is
     * logged -- if this line ever shows up on hardware, the start threshold is
     * real and the prebuffer is the thing to tune.
     */
    private void kickStart() {
        kicked = true;
        setTrackBuffer(capacityFrames);
        int padded = 0;
        for (int i = 0; i < capacityFrames / SpeakerSizing.CHUNK_FRAMES + 2; i++) {
            int n = track.write(SILENCE, 0, SILENCE.length, AudioTrack.WRITE_NON_BLOCKING);
            if (n <= 0) {
                break;
            }
            padded += n / 2;
            framesWritten += n / 2;
        }
        Log.w(VoiceEngine.TAG, "speaker start kick: reply " + replyId + " had not played after "
                + START_KICK_MS + " ms; padded " + SpeakerSizing.framesToMs(padded) + " ms of silence");
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
        if (playing && !cushionOpen) {
            openCushion();
        }
        boolean empty;
        synchronized (this) {
            empty = queue.isEmpty() && current == null;
        }
        if (!playing && !kicked && !needPlay && empty && now - startedAtMs >= START_KICK_MS) {
            kickStart();
            return;
        }
        if (!playing && now - startedAtMs >= STALL_GIVEUP_MS) {
            // The head never moved. Whatever is wrong with the track, holding
            // the turn forever is worse: pending() would stay true and the
            // conversation would never listen again without a restart.
            Log.e(VoiceEngine.TAG, "speaker stalled: no frame played " + (now - startedAtMs)
                    + " ms after start (reply " + replyId + "); dropping what is buffered");
            doFlush();
            return;
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
        int peak;
        int queued;
        String id;
        synchronized (this) {
            dropped = overruns;
            peak = peakQueued;
            queued = queue.size();
            overruns = 0;
            peakQueued = 0;
            firstChunkAtMs = -1;
            lastChunkAtMs = -1;
            id = replyId;
        }
        cushionOpen = false;
        if (playing) {
            playing = false;
            // queue/track numbers for the next tuning pass: peak says whether the
            // queue is anywhere near its bound, buffer is what the track really
            // gave us (an ask is advisory) against the capacity it was built with.
            Log.i(VoiceEngine.TAG, "reply " + id + " played: underruns=" + underruns + " dropped=" + dropped
                    + " queue=" + queued + " peakQueue=" + peak + "/" + sizing.queueChunks()
                    + " buffer=" + track.getBufferSizeInFrames() + "/" + capacityFrames + " frames ("
                    + SpeakerSizing.framesToMs(track.getBufferSizeInFrames()) + " ms)"
                    + (kicked ? " kicked=1" : ""));
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
        long bufferedFrames = Math.max(0, framesWritten - head) + (long) queued * SpeakerSizing.CHUNK_FRAMES;
        c.onPlayback(nowPlaying, (int) (bufferedFrames * 1000 / ConversationClient.SPEAKER_RATE),
                firstChunkToPlayMs);
    }
}
