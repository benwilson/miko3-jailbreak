package com.miko3.mode.voice;

/**
 * How much reply audio each of the speaker's buffers holds, and when playback
 * may start. Plain Java with no android.* imports, so the arithmetic runs on
 * the host JVM in scripts/tests (fixtures/voice_sizing_harness) rather than
 * only on the robot; VoicePlayer owns an instance and does the AudioTrack
 * calls. Immutable, so any thread may read it.
 *
 * There are three buffers between the relay and the speaker, and the U12
 * measurements (every reply logging underruns=2..3, audible stutter) came from
 * the last two being far too small:
 *
 *  1. The player queue (queueChunks), chunks the relay has delivered that the
 *     track has not accepted yet. The relay pushes a cushion of up to
 *     RELAY_BURST_MS ahead of playback, so a queue smaller than that throws
 *     away audio that has already been paid for; the default holds the burst
 *     with room to spare and drop-oldest stays only as a backstop.
 *  2. The AudioTrack's own buffer (trackFrames), the frames AudioFlinger's
 *     mixer can pull without the player thread waking up. This is the one that
 *     decides underruns: at the old 80 ms the voice-player thread had to wake
 *     and refill inside 80 ms, every 80 ms, while the wake-word spotter ran
 *     inference and the settings WebView polled on the same quad-core. About a
 *     second absorbs that scheduling jitter.
 *  3. The prebuffer (prebufferChunks), how much is queued before the track is
 *     started at all -- the cushion playback *begins* with.
 *
 * How the prebuffer and the track buffer interact (the deliberate part):
 * on this platform a streaming AudioTrack does not emit anything until its
 * buffer has been filled, so the track's buffer size doubles as a start
 * threshold. Sizing the track buffer at a second and leaving it there would
 * therefore hold the first word until a second of speech existed -- and would
 * hang a reply shorter than that forever. So the two are decoupled in time
 * rather than in size: the track buffer is opened to startFrames() (the
 * prebuffer) while the track is starting, and raised to trackFrames() once the
 * playback head has actually moved. Playback starts on the prebuffer's
 * latency, then runs on the full cushion. VoicePlayer also keeps a silence
 * kick for the case where even the prebuffer never fills (a reply shorter than
 * it), so the start threshold can never strand a reply.
 *
 * The capacity asked of AudioTrack.Builder is the *maximum* the buffer can
 * later be raised to and cannot be changed afterwards, so it is sized for the
 * cushion (plus a chunk of headroom) even though the track starts far below it.
 */
final class SpeakerSizing {
    /** Milliseconds of reply audio in one chunk from the relay. */
    static final int CHUNK_MS = 80;
    static final int CHUNK_FRAMES = ConversationClient.SPEAKER_RATE * CHUNK_MS / 1000; // 1,764
    static final int CHUNK_BYTES = CHUNK_FRAMES * 2; // 3,528
    /** What the relay is allowed to push ahead of playback (relay/relay/lane.py BURST_SECONDS). */
    static final int RELAY_BURST_MS = 2000;

    /** The stream has gone quiet this long: start with whatever is queued, so a
     * reply shorter than the prebuffer still plays promptly. */
    static final long QUIET_START_MS = 200;
    /** However the chunks trickle in, the first word waits no longer than this. */
    static final long MAX_START_WAIT_MS = 600;

    private final int prebufferChunks;
    private final int queueChunks;
    private final int trackFrames;
    private final int startFrames;
    private final int capacityBytes;

    /** The sizes the preferences ask for, against a track that cannot go below
     * minTrackFrames (AudioTrack.getMinBufferSize for this format). */
    SpeakerSizing(int prebufferChunks, int queueChunks, int speakerBufferMs, int minTrackFrames) {
        this.prebufferChunks = Math.max(1, prebufferChunks);
        this.queueChunks = Math.max(1, queueChunks);
        int floor = Math.max(CHUNK_FRAMES, Math.max(0, minTrackFrames));
        int prebufferFrames = this.prebufferChunks * CHUNK_FRAMES;
        // The cushion is never smaller than what playback starts with, or than
        // the smallest buffer the hardware will give us.
        this.trackFrames = Math.max(msToFrames(speakerBufferMs), Math.max(prebufferFrames, floor));
        this.startFrames = Math.min(Math.max(prebufferFrames, floor), trackFrames);
        this.capacityBytes = trackFrames * 2 + CHUNK_BYTES;
    }

    /** The sizing the stored preferences ask for (VoiceSettings clamps them). */
    static SpeakerSizing of(VoiceSettings settings, int minTrackFrames) {
        return new SpeakerSizing(settings.prebufferChunks(), settings.playerQueueChunks(),
                settings.speakerBufferMs(), minTrackFrames);
    }

    static int msToFrames(int ms) {
        return (int) ((long) ConversationClient.SPEAKER_RATE * Math.max(0, ms) / 1000);
    }

    static int framesToMs(int frames) {
        return (int) ((long) frames * 1000 / ConversationClient.SPEAKER_RATE);
    }

    /** Chunks queued before the track is started. */
    int prebufferChunks() {
        return prebufferChunks;
    }

    int prebufferMs() {
        return prebufferChunks * CHUNK_MS;
    }

    /** Chunks the player holds for the track; past this, enqueue drops the oldest. */
    int queueChunks() {
        return queueChunks;
    }

    int queueMs() {
        return queueChunks * CHUNK_MS;
    }

    /** The track's buffer while it is starting: also its start threshold. */
    int startFrames() {
        return startFrames;
    }

    /** The track's buffer once playback has begun: the underrun cushion. */
    int trackFrames() {
        return trackFrames;
    }

    int trackMs() {
        return framesToMs(trackFrames);
    }

    /** Bytes asked of AudioTrack.Builder: the ceiling trackFrames() is raised to. */
    int capacityBytes() {
        return capacityBytes;
    }

    /** True when the queue holds the whole cushion the relay may send. */
    boolean queueHoldsRelayBurst() {
        return queueMs() >= RELAY_BURST_MS;
    }

    /**
     * Whether the track should be started now, given what is queued and how
     * long chunks have been arriving. The prebuffer is the goal; a stream that
     * has gone quiet (a short reply, all of it already here) or one that
     * trickles for too long starts on what it has rather than stalling.
     * sinceFirstMs/sinceLastMs are negative when no chunk has arrived yet.
     */
    boolean shouldStart(int queuedChunks, long sinceFirstMs, long sinceLastMs) {
        if (queuedChunks <= 0) {
            return false;
        }
        return queuedChunks >= prebufferChunks
                || (sinceLastMs >= 0 && sinceLastMs >= QUIET_START_MS)
                || (sinceFirstMs >= 0 && sinceFirstMs >= MAX_START_WAIT_MS);
    }
}
