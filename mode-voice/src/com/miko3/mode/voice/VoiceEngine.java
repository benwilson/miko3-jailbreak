package com.miko3.mode.voice;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Locale;

import recognizer.WakeWord;

/**
 * The robot's audio side of a conversation (U8, KTD6, KTD7): one microphone
 * capture fanned to the wake-word spotter or the uplink, and one speaker
 * track for the replies. ConversationClient drives it through the Audio
 * interface; it calls back into the client with wakes, uplink audio and
 * playback transitions.
 *
 * Capture: one 16 kHz mono AudioRecord on VOICE_COMMUNICATION, with
 * AcousticEchoCanceler attached when available (the platform canceller only
 * references the voice-communication output path, which is what the speaker
 * track below uses). An urgent-audio "voice-capture" thread reads 80 ms
 * (1,280-sample) chunks and routes each one:
 *   - listening: to the "voice-spotter" thread through a one-deep drop-oldest
 *     slot, so capture never waits on inference;
 *   - from a "Hey Miko" hit until listen() is called again: to
 *     ConversationClient.sendUplink(), which buffers it until conv.ready (the
 *     pre-ready buffer), then sends it, and drops it in every other state.
 * The spotter is therefore paused for the whole conversation and reset and
 * resumed by listen(), which the client calls on every path back to
 * listening. A busy or failing microphone is logged ("microphone busy"),
 * shown as state detail, and retried with backoff; it never crashes the mode.
 *
 * Diagnostics kept from U1's spike so the on-device wake-word proof can be
 * done from the real mode (logcat tag VoiceEngine): "DETECTION" lines with
 * scores; "HELLO (not a detection)" lines for the model's second class,
 * which never opens a conversation; a per-minute summary with inference
 * p50/p95/max, over-budget and dropped chunks, and score percentiles. A
 * hand-written preference capture_source=recognition (voice_settings.xml,
 * written over root adb like the other tuning keys) captures from
 * VOICE_RECOGNITION instead, for comparing wake recall between sources.
 *
 * Playback: an AudioTrack tagged USAGE_VOICE_COMMUNICATION /
 * CONTENT_TYPE_SPEECH, 22,050 Hz mono 16-bit, MODE_STREAM, created at
 * conv.ready (openPlayer) and released when the conversation ends. An
 * urgent-audio "voice-player" thread drains a queue bounded at about one
 * second (overflow drops the chunk, logged) with non-blocking writes, starts
 * the track once the prebuffer (preference, default one chunk) is queued,
 * and derives playing and idle from the playback head position, never from
 * the queue emptying. flushReply() pauses and flushes the track at once; the
 * next reply's audio plays it again. Underruns (getUnderrunCount) and
 * dropped chunks are logged per turn.
 *
 * stop() is synchronous (the Activity's exit): the speaker goes silent first,
 * then the capture and spotter threads end and the record is released.
 */
final class VoiceEngine implements ConversationClient.Audio {
    static final String TAG = "VoiceEngine";

    private static final int CHUNK_SAMPLES = WakeWord.AUDIO_CHUNK_SIZE;
    private static final int CHUNK_BYTES = CHUNK_SAMPLES * 2;
    private static final long CHUNK_PERIOD_US = 80000;
    private static final String MODEL_ASSET = "miko_wakeword_model.tflite";
    private static final String INIT_MARKER = "wakeword_init_in_progress";
    // The vendor's thresholds (KTD7); tuned on-device in U10, not before the loop runs.
    private static final float HEY_THRESHOLD = 0.65f;
    private static final float HELLO_THRESHOLD = 0.6f;
    /** Per-chunk score lines are logged only above this. */
    private static final float NEAR_MISS_FLOOR = 0.2f;
    private static final long SUMMARY_PERIOD_MS = 60000;
    private static final long MIC_RETRY_BASE_MS = 2000;
    private static final long MIC_RETRY_CAP_MS = 30000;
    /** A capture that ran this long resets the retry backoff. */
    private static final long MIC_HEALTHY_MS = 10000;

    static final int SPEAKER_CHUNK_FRAMES = ConversationClient.SPEAKER_RATE * 80 / 1000; // 1,764
    static final int SPEAKER_CHUNK_BYTES = SPEAKER_CHUNK_FRAMES * 2; // 3,528
    /** About one second of reply audio (KTD6). */
    static final int PLAYER_QUEUE_CHUNKS = 13;
    /** A reply shorter than the prebuffer still starts after this long. */
    static final long PREBUFFER_HOLD_MS = 200;

    /** Short text for the settings page's state line, or null when all is well. */
    interface DetailListener {
        void onEngineDetail(String detail);
    }

    // One engine per process: the vendor library's init is not known to be safe
    // to repeat, so a relaunched mode reuses the instance and only resets it.
    private static WakeWord sharedWakeWord;
    private static int sharedNumClasses;

    private final Context context;
    private final VoiceSettings settings;
    private final DetailListener detailListener;
    volatile ConversationClient client;

    private volatile boolean running;
    private volatile boolean spotterReady;
    private Thread captureThread;
    private Thread spotterThread;
    private WakeWord wakeWord;
    private int numClasses;
    private boolean useRecognitionSource;

    // The capture record; set and cleared by the capture thread, read by stop().
    private final Object recordLock = new Object();
    private AudioRecord record;
    private AcousticEchoCanceler echoCanceler;

    // Capture routing and the spotter's one-deep slot.
    private final Object slotLock = new Object();
    private boolean toClient; // guarded by slotLock
    private byte[] slot; // guarded by slotLock
    private boolean resetSpotter; // guarded by slotLock
    private int droppedSinceSummary; // guarded by slotLock

    private final Object playerLock = new Object();
    private VoicePlayer player; // guarded by playerLock

    VoiceEngine(Context context, VoiceSettings settings, DetailListener detailListener) {
        this.context = context.getApplicationContext();
        this.settings = settings;
        this.detailListener = detailListener;
    }

    void setClient(ConversationClient client) {
        this.client = client;
    }

    /** Loads the wake-word engine and opens the microphone, off the caller's thread. */
    synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        useRecognitionSource = settings.captureFromRecognition();
        if (useRecognitionSource) {
            Log.w(TAG, VoiceSettings.KEY_CAPTURE_SOURCE + "=recognition: capturing from VOICE_RECOGNITION, without the "
                    + "voice-communication echo path (diagnostics only)");
        }
        captureThread = new Thread(new Runnable() {
            @Override
            public void run() {
                if (!initEngine()) {
                    return;
                }
                if (!running) {
                    return;
                }
                startSpotter();
                captureMain();
            }
        }, "voice-capture");
        captureThread.start();
    }

    /** Synchronous: the speaker is silent and the microphone released on return. */
    void stop() {
        Thread capture;
        Thread spotter;
        synchronized (this) {
            running = false;
            spotterReady = false;
            capture = captureThread;
            spotter = spotterThread;
            captureThread = null;
            spotterThread = null;
        }
        closePlayer();
        synchronized (slotLock) {
            slot = null;
            slotLock.notifyAll();
        }
        if (capture != null) {
            capture.interrupt(); // a retry backoff sleep
            joinQuietly(capture, 1000);
        }
        joinQuietly(spotter, 1000);
        // Normally the capture thread released it on its way out; if it is stuck,
        // stop() under it unblocks the read and the release happens here.
        releaseRecord();
        Log.i(TAG, "stopped");
    }

    // ---- ConversationClient.Audio ----------------------------------------------

    @Override
    public void listen() {
        synchronized (slotLock) {
            toClient = false;
            slot = null;
            resetSpotter = true;
            slotLock.notifyAll();
        }
    }

    @Override
    public void openPlayer() {
        VoicePlayer old;
        VoicePlayer created = VoicePlayer.create(this, settings.prebufferChunks());
        synchronized (playerLock) {
            old = player;
            player = created;
        }
        if (old != null) {
            old.shutdown();
        }
    }

    @Override
    public void replyBegins(String replyId) {
        VoicePlayer p = currentPlayer();
        if (p != null) {
            p.replyBegins(replyId);
        }
    }

    @Override
    public void play(byte[] chunk) {
        VoicePlayer p = currentPlayer();
        if (p != null) {
            p.enqueue(chunk);
        }
    }

    @Override
    public void flushReply() {
        VoicePlayer p = currentPlayer();
        if (p != null) {
            p.flush();
        }
    }

    @Override
    public boolean hasPendingAudio() {
        VoicePlayer p = currentPlayer();
        return p != null && p.pending();
    }

    @Override
    public void closePlayer() {
        VoicePlayer p;
        synchronized (playerLock) {
            p = player;
            player = null;
        }
        if (p != null) {
            p.shutdown();
        }
    }

    private VoicePlayer currentPlayer() {
        synchronized (playerLock) {
            return player;
        }
    }

    // ---- wake-word engine --------------------------------------------------------

    /** A native crash inside init can't be caught: a marker file written before init
     * and removed after tells the next start that it never returned. That start
     * skips init once (and says so); the one after tries again. */
    private boolean initEngine() {
        synchronized (VoiceEngine.class) {
            if (sharedWakeWord != null) {
                wakeWord = sharedWakeWord;
                numClasses = sharedNumClasses;
                return true;
            }
            File marker = new File(context.getFilesDir(), INIT_MARKER);
            if (marker.exists()) {
                marker.delete();
                fail("the wake-word engine crashed during its last start (lib" + WakeWord.LIBRARY
                        + ".so); relaunch the mode to retry");
                return false;
            }
            try {
                marker.createNewFile();
            } catch (IOException e) {
                Log.w(TAG, "could not write init marker: " + e.getMessage());
            }
            long t0 = SystemClock.elapsedRealtime();
            boolean ok;
            try {
                WakeWord ww = new WakeWord();
                ok = ww.init(context, MODEL_ASSET);
                if (ok) {
                    sharedNumClasses = ww.getNumOutputClasses();
                    ww.setThresholds(HEY_THRESHOLD, HELLO_THRESHOLD);
                    ww.resetState();
                    sharedWakeWord = ww;
                }
            } catch (LinkageError e) {
                // UnsatisfiedLinkError (library or a dependency missing, wrong ABI,
                // namespace-blocked), ExceptionInInitializerError, NoClassDefFoundError.
                marker.delete();
                Log.e(TAG, "wake-word engine load failed in lib" + WakeWord.LIBRARY + ".so", e);
                fail("wake-word engine failed to load: " + e);
                return false;
            } catch (RuntimeException e) {
                marker.delete();
                Log.e(TAG, "wake-word engine init threw", e);
                fail("wake-word engine init failed: " + e);
                return false;
            }
            marker.delete();
            if (!ok) {
                fail("wake-word engine init() returned false (model " + MODEL_ASSET + ")");
                return false;
            }
            wakeWord = sharedWakeWord;
            numClasses = sharedNumClasses;
            Log.i(TAG, "wake-word engine ready in " + (SystemClock.elapsedRealtime() - t0)
                    + " ms: outputClasses=" + numClasses + " thresholds hey=" + HEY_THRESHOLD
                    + " hello=" + HELLO_THRESHOLD);
            return true;
        }
    }

    private void fail(String reason) {
        Log.e(TAG, "FAILED: " + reason);
        detail(reason);
    }

    private void detail(String text) {
        try {
            detailListener.onEngineDetail(text);
        } catch (RuntimeException e) {
            Log.e(TAG, "detail listener failed", e);
        }
    }

    // ---- capture -------------------------------------------------------------------

    private void captureMain() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        long retryMs = MIC_RETRY_BASE_MS;
        while (running) {
            AudioRecord rec = openRecord();
            if (rec != null) {
                long t0 = SystemClock.elapsedRealtime();
                String error = readLoop(rec);
                releaseRecord();
                if (!running) {
                    break;
                }
                if (SystemClock.elapsedRealtime() - t0 >= MIC_HEALTHY_MS) {
                    retryMs = MIC_RETRY_BASE_MS;
                }
                Log.e(TAG, "microphone busy: " + error + "; retrying in " + retryMs + " ms");
                detail("microphone busy");
            } else {
                Log.e(TAG, "microphone busy or unavailable; retrying in " + retryMs + " ms");
            }
            try {
                Thread.sleep(retryMs);
            } catch (InterruptedException e) {
                break;
            }
            retryMs = Math.min(retryMs * 2, MIC_RETRY_CAP_MS);
        }
        releaseRecord();
    }

    private String sourceName() {
        return useRecognitionSource ? "VOICE_RECOGNITION" : "VOICE_COMMUNICATION";
    }

    /** The started record, or null (detail set) when the mic can't be opened now. */
    private AudioRecord openRecord() {
        int source = useRecognitionSource
                ? MediaRecorder.AudioSource.VOICE_RECOGNITION
                : MediaRecorder.AudioSource.VOICE_COMMUNICATION;
        int minBuf = AudioRecord.getMinBufferSize(ConversationClient.MIC_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) {
            detail("microphone unavailable (unsupported AudioRecord configuration " + minBuf + ")");
            return null;
        }
        AudioRecord rec;
        try {
            rec = new AudioRecord(source, ConversationClient.MIC_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(minBuf, CHUNK_BYTES * 4));
        } catch (RuntimeException e) {
            // SecurityException without RECORD_AUDIO, IllegalArgumentException.
            Log.e(TAG, "AudioRecord open failed: " + e);
            detail("microphone busy (" + e.getClass().getSimpleName() + ")");
            return null;
        }
        if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
            rec.release();
            detail("microphone busy (AudioRecord failed to initialize)");
            return null;
        }
        AcousticEchoCanceler aec = attachEchoCanceler(rec.getAudioSessionId());
        try {
            rec.startRecording();
        } catch (IllegalStateException e) {
            Log.e(TAG, "startRecording failed: " + e);
            if (aec != null) {
                aec.release();
            }
            rec.release();
            detail("microphone busy");
            return null;
        }
        if (rec.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            if (aec != null) {
                aec.release();
            }
            rec.release();
            detail("microphone busy");
            return null;
        }
        synchronized (recordLock) {
            record = rec;
            echoCanceler = aec;
        }
        Log.i(TAG, "capturing from " + sourceName() + " at " + ConversationClient.MIC_RATE + " Hz, aec="
                + (aec != null));
        detail(null);
        return rec;
    }

    private AcousticEchoCanceler attachEchoCanceler(int sessionId) {
        if (!AcousticEchoCanceler.isAvailable()) {
            Log.i(TAG, "AcousticEchoCanceler not available on this device");
            return null;
        }
        try {
            AcousticEchoCanceler aec = AcousticEchoCanceler.create(sessionId);
            if (aec == null) {
                Log.w(TAG, "AcousticEchoCanceler.create returned null");
                return null;
            }
            int rc = aec.setEnabled(true);
            Log.i(TAG, "AcousticEchoCanceler attached, setEnabled rc=" + rc + " enabled=" + aec.getEnabled());
            return aec;
        } catch (RuntimeException e) {
            Log.w(TAG, "AcousticEchoCanceler attach failed: " + e);
            return null;
        }
    }

    /** Reads until stop() or a run of read errors; returns the error, if any. */
    private String readLoop(AudioRecord rec) {
        int consecutiveErrors = 0;
        while (running) {
            byte[] buf = new byte[CHUNK_BYTES];
            int filled = 0;
            while (running && filled < CHUNK_BYTES) {
                int n = rec.read(buf, filled, CHUNK_BYTES - filled);
                if (n > 0) {
                    filled += n;
                    consecutiveErrors = 0;
                } else {
                    // Negative is a real AudioRecord/HAL error (MicCapture: -38 while
                    // another capture holds the mic), not a transient empty read.
                    consecutiveErrors++;
                    if (consecutiveErrors >= 5) {
                        return "AudioRecord.read() returned " + n + " five times";
                    }
                    SystemClock.sleep(20);
                }
            }
            if (filled < CHUNK_BYTES) {
                return null;
            }
            route(buf);
        }
        return null;
    }

    /** Spotter slot while listening, else the client (which decides what is sent). */
    private void route(byte[] chunk) {
        synchronized (slotLock) {
            if (toClient) {
                ConversationClient c = client;
                if (c != null) {
                    c.sendUplink(chunk);
                }
                return;
            }
            if (!spotterReady) {
                return; // no wake-word engine: nothing listens
            }
            if (slot != null) {
                droppedSinceSummary++;
            }
            slot = chunk;
            slotLock.notifyAll();
        }
    }

    private void releaseRecord() {
        AudioRecord rec;
        AcousticEchoCanceler aec;
        synchronized (recordLock) {
            rec = record;
            aec = echoCanceler;
            record = null;
            echoCanceler = null;
        }
        if (aec != null) {
            aec.release();
        }
        if (rec != null) {
            try {
                rec.stop();
            } catch (IllegalStateException ignored) {
            }
            rec.release();
            Log.i(TAG, "microphone released");
        }
    }

    // ---- spotter --------------------------------------------------------------------

    private void startSpotter() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                spotLoop();
            }
        }, "voice-spotter");
        synchronized (this) {
            if (!running) {
                return;
            }
            spotterThread = t;
        }
        synchronized (slotLock) {
            resetSpotter = true;
        }
        spotterReady = true;
        t.start();
    }

    private void spotLoop() {
        float[] scores = new float[Math.max(3, numClasses)];
        short[] samples = new short[CHUNK_SAMPLES];
        Stats stats = new Stats();
        long windowStart = SystemClock.elapsedRealtime();
        while (running) {
            byte[] chunk;
            boolean reset;
            synchronized (slotLock) {
                while (running && slot == null) {
                    try {
                        slotLock.wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                chunk = slot;
                slot = null;
                reset = resetSpotter;
                resetSpotter = false;
            }
            if (chunk == null) {
                break;
            }
            if (reset) {
                wakeWord.resetState();
            }
            // Little-endian bytes to shorts, as the vendor's KeywordTask2.setData() does.
            ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples);
            if (isSilent(samples)) {
                // The vendor skips all-zero chunks the same way.
                stats.zeroChunks++;
            } else {
                Arrays.fill(scores, 0f);
                long t0 = System.nanoTime();
                int result = wakeWord.processChunk(samples, scores);
                long us = (System.nanoTime() - t0) / 1000;
                stats.addChunk(us, heyScore(scores), helloScore(scores));
                handleResult(result, scores, us, stats);
            }
            long now = SystemClock.elapsedRealtime();
            if (now - windowStart >= SUMMARY_PERIOD_MS) {
                int dropped;
                synchronized (slotLock) {
                    dropped = droppedSinceSummary;
                    droppedSinceSummary = 0;
                }
                Log.i(TAG, stats.summary(sourceName(), (now - windowStart) / 1000, dropped));
                stats = new Stats();
                windowStart = now;
            }
        }
    }

    private static boolean isSilent(short[] chunk) {
        for (short s : chunk) {
            if (s != 0) {
                return false;
            }
        }
        return true;
    }

    private float heyScore(float[] scores) {
        return numClasses == 1 ? scores[0] : scores[WakeWord.DETECTION_HEY_MIKO];
    }

    private float helloScore(float[] scores) {
        return numClasses == 1 ? 0f : scores[WakeWord.DETECTION_HELLO_MIKO];
    }

    /** Formatted only in the branches that log it: most chunks log nothing. */
    private void handleResult(int result, float[] scores, long us, Stats stats) {
        if (result == WakeWord.DETECTION_HEY_MIKO) {
            stats.detections++;
            Log.i(TAG, "##### DETECTION Hey Miko score=" + heyScore(scores) + " " + scoreText(scores, us) + " #####");
            wakeWord.resetState();
            onHit();
        } else if (result == WakeWord.DETECTION_HELLO_MIKO) {
            // KTD7: the model's second class is logged but never opens a conversation.
            stats.hellos++;
            Log.i(TAG, "HELLO (not a detection) Hello Miko score=" + helloScore(scores) + " " + scoreText(scores, us));
            wakeWord.resetState();
        } else if (result != WakeWord.DETECTION_NONE) {
            Log.w(TAG, "unexpected processChunk result " + result + " " + scoreText(scores, us));
        } else if (heyScore(scores) >= NEAR_MISS_FLOOR || helloScore(scores) >= NEAR_MISS_FLOOR) {
            Log.d(TAG, "chunk " + scoreText(scores, us));
        }
    }

    private static String scoreText(float[] scores, long us) {
        return String.format(Locale.US, "scores=%s inference=%.1fms", formatScores(scores), us / 1000f);
    }

    /** Capture now goes to the client (which starts its pre-ready buffer in onWake)
     * until listen(); the chunk already waiting in the slot goes with it. */
    private void onHit() {
        synchronized (slotLock) {
            if (toClient) {
                return;
            }
            ConversationClient c = client;
            if (c == null) {
                return;
            }
            c.onWake();
            toClient = true;
            byte[] waiting = slot;
            slot = null;
            if (waiting != null) {
                c.sendUplink(waiting);
            }
        }
    }

    private static String formatScores(float[] scores) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < scores.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(String.format(Locale.US, "%.3f", scores[i]));
        }
        return sb.append(']').toString();
    }

    static void joinQuietly(Thread t, long ms) {
        if (t == null || t == Thread.currentThread()) {
            return;
        }
        try {
            t.join(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** One summary window's measurements; owned by the spotter thread. */
    private static final class Stats {
        private int[] inferenceUs = new int[1024];
        private float[] hey = new float[1024];
        private float[] hello = new float[1024];
        private int count;
        int zeroChunks;
        int detections;
        int hellos;

        void addChunk(long us, float heyScore, float helloScore) {
            if (count == inferenceUs.length) {
                inferenceUs = Arrays.copyOf(inferenceUs, count * 2);
                hey = Arrays.copyOf(hey, count * 2);
                hello = Arrays.copyOf(hello, count * 2);
            }
            inferenceUs[count] = (int) Math.min(us, Integer.MAX_VALUE);
            hey[count] = heyScore;
            hello[count] = helloScore;
            count++;
        }

        String summary(String source, long seconds, int dropped) {
            int[] inf = Arrays.copyOf(inferenceUs, count);
            Arrays.sort(inf);
            float[] h = Arrays.copyOf(hey, count);
            Arrays.sort(h);
            float[] l = Arrays.copyOf(hello, count);
            Arrays.sort(l);
            int overBudget = 0;
            for (int v : inf) {
                if (v > CHUNK_PERIOD_US) {
                    overBudget++;
                }
            }
            return String.format(Locale.US,
                    "minute: source=%s window=%ds chunks=%d zero=%d dropped=%d detections=%d hellos=%d"
                            + " inference_ms p50=%.1f p95=%.1f max=%.1f over80ms=%d"
                            + " hey p50=%.3f p95=%.3f max=%.3f hello p50=%.3f p95=%.3f max=%.3f",
                    source, seconds, count, zeroChunks, dropped, detections, hellos,
                    pct(inf, 50) / 1000f, pct(inf, 95) / 1000f, pct(inf, 100) / 1000f, overBudget,
                    pct(h, 50), pct(h, 95), pct(h, 100), pct(l, 50), pct(l, 95), pct(l, 100));
        }

        private static int pct(int[] sorted, int p) {
            return sorted.length == 0 ? 0 : sorted[rank(sorted.length, p)];
        }

        private static float pct(float[] sorted, int p) {
            return sorted.length == 0 ? 0f : sorted[rank(sorted.length, p)];
        }

        /** Nearest-rank percentile index into a sorted array of length n. */
        private static int rank(int n, int p) {
            int r = (int) Math.ceil(p / 100.0 * n) - 1;
            return Math.max(0, Math.min(n - 1, r));
        }
    }
}
