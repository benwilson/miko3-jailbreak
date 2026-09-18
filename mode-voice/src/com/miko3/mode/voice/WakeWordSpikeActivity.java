package com.miko3.mode.voice;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Locale;

import recognizer.WakeWord;

/**
 * U1 spike: proves the vendor's wake-word engine (recognizer.WakeWord, KTD7) runs
 * from our own process and measures what it costs on this SoC. Nothing depends on
 * this class; it is removed once U8's real listening path lands (Definition of Done).
 *
 * Pipeline, the same shape U8 ships (KTD6): one 16 kHz mono AudioRecord, by default
 * on the voice-communication source with AcousticEchoCanceler attached when
 * available, read in 1,280-sample (80 ms) chunks by an urgent-audio capture thread
 * that never blocks on inference; each chunk goes through a one-deep drop-oldest
 * slot to a separate spotter thread that runs the engine. Bytes are converted to
 * shorts little-endian exactly as the vendor's KeywordTask2.setData() does, and
 * all-zero chunks are skipped the same way.
 *
 * Launch over adb (the launcher does not know about this Activity):
 *   am start -n com.miko3.mode.voice/.WakeWordSpikeActivity
 * Intent extras, all optional:
 *   --es source recognition     capture from VOICE_RECOGNITION (the vendor's own
 *                               source) instead of VOICE_COMMUNICATION, so detection
 *                               and score distributions can be compared (U1 step 3)
 *   --ef hey_threshold 0.65     "Hey Miko" threshold (vendor default 0.65)
 *   --ef hello_threshold 0.6    "Hello Miko" threshold (vendor default 0.6)
 *   --es model_path /abs/path   init from a model file instead of the bundled asset
 *                               (the optional Hey-Miko-only model measurement)
 *   --ez log_all_scores true    log every chunk's scores, not only near-misses
 *   --ez force_init true        init even if the previous init never returned
 *
 * Logcat tag "WakeWordSpike": "DETECTION" lines for Hey Miko; "HELLO (not a
 * detection)" lines for the model's second class; a "minute" summary with
 * inference p50/p95/max, over-budget chunks, dropped chunks, and score percentiles.
 *
 * Failures are logged once and left alone -- never retried, never a self-restart.
 * A native crash inside init cannot be caught, so a marker file is written before
 * init and removed after; if it is still there on the next launch the previous init
 * never returned, which is logged (naming the library) and init is skipped.
 */
public class WakeWordSpikeActivity extends Activity {
    private static final String TAG = "WakeWordSpike";
    private static final int SAMPLE_RATE = 16000;
    private static final int CHUNK_SAMPLES = WakeWord.AUDIO_CHUNK_SIZE;
    private static final int CHUNK_BYTES = CHUNK_SAMPLES * 2;
    private static final long CHUNK_PERIOD_US = 80000;
    private static final String MODEL_ASSET = "miko_wakeword_model.tflite";
    private static final String INIT_MARKER = "wakeword_init_in_progress";
    private static final float VENDOR_HEY_THRESHOLD = 0.65f;
    private static final float VENDOR_HELLO_THRESHOLD = 0.6f;
    /** Per-chunk score lines are logged only above this, unless log_all_scores. */
    private static final float NEAR_MISS_FLOOR = 0.2f;
    private static final long SUMMARY_PERIOD_MS = 60000;
    private static final int REQ_RECORD_AUDIO = 1;

    private TextView statusView;

    private boolean useRecognitionSource;
    private float heyThreshold;
    private float helloThreshold;
    private String modelPath;
    private boolean logAllScores;
    private boolean forceInit;

    private WakeWord wakeWord;
    private int numClasses;

    private AudioRecord audioRecord;
    private AcousticEchoCanceler echoCanceler;
    private Thread captureThread;
    private Thread spotterThread;
    private volatile boolean running;
    private volatile boolean destroyed;

    // One-deep drop-oldest handoff: the capture thread overwrites, the spotter takes.
    private final Object slotLock = new Object();
    private short[] slot;
    private int droppedSinceSummary;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        statusView = new TextView(this);
        statusView.setTextSize(20);
        statusView.setGravity(Gravity.CENTER);
        setContentView(statusView);

        Intent intent = getIntent();
        useRecognitionSource = "recognition".equals(intent.getStringExtra("source"));
        heyThreshold = intent.getFloatExtra("hey_threshold", VENDOR_HEY_THRESHOLD);
        helloThreshold = intent.getFloatExtra("hello_threshold", VENDOR_HELLO_THRESHOLD);
        modelPath = intent.getStringExtra("model_path");
        logAllScores = intent.getBooleanExtra("log_all_scores", false);
        forceInit = intent.getBooleanExtra("force_init", false);
        Log.i(TAG, "spike start: source=" + sourceName() + " hey=" + heyThreshold
                + " hello=" + helloThreshold + " model=" + (modelPath != null ? modelPath : "asset:" + MODEL_ASSET));
        setStatus("starting (" + sourceName() + ")");

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            begin();
        } else {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RECORD_AUDIO);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != REQ_RECORD_AUDIO) {
            return;
        }
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            begin();
        } else {
            fail("RECORD_AUDIO not granted");
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // singleTop: a second am start with different extras must not silently keep
        // the old configuration -- say so instead of pretending it took effect.
        Log.w(TAG, "already running (" + sourceName() + "); finish the Activity to change extras");
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        stopListening();
        super.onDestroy();
    }

    private String sourceName() {
        return useRecognitionSource ? "VOICE_RECOGNITION" : "VOICE_COMMUNICATION";
    }

    /** Engine init off the UI thread (a 13 MB library plus model load), then audio. */
    private void begin() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (initEngine()) {
                    startListening();
                }
            }
        }, "wakeword-init").start();
    }

    private boolean initEngine() {
        File marker = new File(getFilesDir(), INIT_MARKER);
        if (marker.exists() && !forceInit) {
            fail("previous engine init never returned (native crash in lib"
                    + WakeWord.LIBRARY + ".so?); not retrying -- relaunch with --ez force_init true");
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
            Log.i(TAG, "loading lib" + WakeWord.LIBRARY + ".so and initializing engine");
            WakeWord ww = new WakeWord();
            ok = modelPath != null ? ww.initFromPath(this, modelPath) : ww.init(this, MODEL_ASSET);
            if (ok) {
                numClasses = ww.getNumOutputClasses();
                ww.setThresholds(heyThreshold, helloThreshold);
                ww.resetState();
                wakeWord = ww;
            }
        } catch (LinkageError e) {
            // UnsatisfiedLinkError (library or a dependency missing / wrong ABI /
            // namespace-blocked), ExceptionInInitializerError, NoClassDefFoundError.
            marker.delete();
            Log.e(TAG, "engine load failed in lib" + WakeWord.LIBRARY + ".so", e);
            fail("load failed: lib" + WakeWord.LIBRARY + ".so: " + e);
            return false;
        } catch (RuntimeException e) {
            marker.delete();
            Log.e(TAG, "engine init threw (lib" + WakeWord.LIBRARY + ".so)", e);
            fail("init threw: " + e);
            return false;
        }
        marker.delete();
        long ms = SystemClock.elapsedRealtime() - t0;
        if (!ok) {
            fail("init() returned false (lib" + WakeWord.LIBRARY + ".so, model "
                    + (modelPath != null ? modelPath : MODEL_ASSET) + ") after " + ms + " ms");
            return false;
        }
        Log.i(TAG, "engine ready in " + ms + " ms: outputClasses=" + numClasses
                + " thresholds hey=" + heyThreshold + " hello=" + helloThreshold);
        return true;
    }

    private synchronized void startListening() {
        if (destroyed) {
            // Finished while the engine was still initializing: never open the mic.
            Log.i(TAG, "Activity destroyed during init; not opening the microphone");
            return;
        }
        int source = useRecognitionSource
                ? MediaRecorder.AudioSource.VOICE_RECOGNITION
                : MediaRecorder.AudioSource.VOICE_COMMUNICATION;
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) {
            fail("unsupported AudioRecord configuration (" + minBuf + ")");
            return;
        }
        AudioRecord rec;
        try {
            rec = new AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(minBuf, CHUNK_BYTES * 4));
        } catch (RuntimeException e) {
            fail("AudioRecord open failed: " + e);
            return;
        }
        if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
            rec.release();
            fail("AudioRecord failed to initialize on " + sourceName());
            return;
        }
        audioRecord = rec;
        attachEchoCanceler(rec.getAudioSessionId());

        running = true;
        spotterThread = new Thread(new Runnable() {
            @Override
            public void run() {
                spotLoop();
            }
        }, "wakeword-spotter");
        spotterThread.start();
        try {
            rec.startRecording();
        } catch (IllegalStateException e) {
            fail("startRecording failed: " + e);
            stopListening();
            return;
        }
        captureThread = new Thread(new Runnable() {
            @Override
            public void run() {
                captureLoop();
            }
        }, "wakeword-capture");
        captureThread.start();
        setStatus("listening (" + sourceName() + ", aec=" + (echoCanceler != null) + ")");
    }

    private void attachEchoCanceler(int sessionId) {
        if (!AcousticEchoCanceler.isAvailable()) {
            Log.i(TAG, "AcousticEchoCanceler not available on this device");
            return;
        }
        try {
            echoCanceler = AcousticEchoCanceler.create(sessionId);
            if (echoCanceler == null) {
                Log.w(TAG, "AcousticEchoCanceler.create returned null");
                return;
            }
            int rc = echoCanceler.setEnabled(true);
            Log.i(TAG, "AcousticEchoCanceler attached, setEnabled rc=" + rc
                    + " enabled=" + echoCanceler.getEnabled());
        } catch (RuntimeException e) {
            Log.w(TAG, "AcousticEchoCanceler attach failed: " + e);
            echoCanceler = null;
        }
    }

    private void captureLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        byte[] buf = new byte[CHUNK_BYTES];
        int consecutiveErrors = 0;
        while (running) {
            int filled = 0;
            while (running && filled < CHUNK_BYTES) {
                int n = audioRecord.read(buf, filled, CHUNK_BYTES - filled);
                if (n > 0) {
                    filled += n;
                    consecutiveErrors = 0;
                } else {
                    // Negative is a real AudioRecord/HAL error (see MicCapture: -38 when
                    // the vendor's own wake-word loop still holds the mic), not a
                    // transient empty read; don't busy-spin on it.
                    consecutiveErrors++;
                    Log.e(TAG, "AudioRecord.read() returned " + n);
                    if (consecutiveErrors >= 5) {
                        running = false;
                        fail("microphone busy or unavailable (AudioRecord error " + n + ")");
                        synchronized (slotLock) {
                            slotLock.notifyAll();
                        }
                        return;
                    }
                    SystemClock.sleep(20);
                }
            }
            if (filled < CHUNK_BYTES) {
                break;
            }
            short[] chunk = new short[CHUNK_SAMPLES];
            ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(chunk);
            synchronized (slotLock) {
                if (slot != null) {
                    droppedSinceSummary++;
                }
                slot = chunk;
                slotLock.notifyAll();
            }
        }
    }

    private void spotLoop() {
        float[] scores = new float[Math.max(3, numClasses)];
        Stats stats = new Stats();
        long windowStart = SystemClock.elapsedRealtime();
        while (running) {
            short[] chunk;
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
            }
            if (chunk == null) {
                break;
            }
            if (isSilent(chunk)) {
                stats.zeroChunks++;
            } else {
                Arrays.fill(scores, 0f);
                long t0 = System.nanoTime();
                int result = wakeWord.processChunk(chunk, scores);
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
                String line = stats.summary(sourceName(), (now - windowStart) / 1000, dropped);
                Log.i(TAG, line);
                setStatus("listening (" + sourceName() + ")\n" + line);
                stats = new Stats();
                windowStart = now;
            }
        }
    }

    /** The vendor skips chunks whose peak amplitude is zero (KeywordTask2.setData). */
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

    private void handleResult(int result, float[] scores, long us, Stats stats) {
        String scoreText = String.format(Locale.US, "scores=%s inference=%.1fms",
                formatScores(scores), us / 1000f);
        if (result == WakeWord.DETECTION_HEY_MIKO) {
            stats.detections++;
            Log.i(TAG, "##### DETECTION Hey Miko score=" + heyScore(scores) + " " + scoreText + " #####");
            setStatus("DETECTED Hey Miko (" + String.format(Locale.US, "%.2f", heyScore(scores)) + ")");
            wakeWord.resetState();
        } else if (result == WakeWord.DETECTION_HELLO_MIKO) {
            // KTD7: the model's second class is logged but never opens a conversation.
            stats.hellos++;
            Log.i(TAG, "HELLO (not a detection) Hello Miko score=" + helloScore(scores) + " " + scoreText);
            wakeWord.resetState();
        } else if (result != WakeWord.DETECTION_NONE) {
            Log.w(TAG, "unexpected processChunk result " + result + " " + scoreText);
        } else if (logAllScores || heyScore(scores) >= NEAR_MISS_FLOOR || helloScore(scores) >= NEAR_MISS_FLOOR) {
            Log.d(TAG, "chunk " + scoreText);
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

    private synchronized void stopListening() {
        running = false;
        synchronized (slotLock) {
            slotLock.notifyAll();
        }
        joinQuietly(captureThread);
        captureThread = null;
        joinQuietly(spotterThread);
        spotterThread = null;
        if (echoCanceler != null) {
            echoCanceler.release();
            echoCanceler = null;
        }
        if (audioRecord != null) {
            try {
                audioRecord.stop();
            } catch (IllegalStateException ignored) {
            }
            audioRecord.release();
            audioRecord = null;
        }
        Log.i(TAG, "listening stopped");
    }

    private static void joinQuietly(Thread t) {
        if (t == null || t == Thread.currentThread()) {
            return;
        }
        try {
            t.join(1000);
        } catch (InterruptedException ignored) {
        }
    }

    private void fail(String reason) {
        Log.e(TAG, "FAILED: " + reason);
        setStatus("FAILED: " + reason);
    }

    private void setStatus(final String text) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                statusView.setText(text);
            }
        });
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
            if (sorted.length == 0) {
                return 0;
            }
            return sorted[rank(sorted.length, p)];
        }

        private static float pct(float[] sorted, int p) {
            if (sorted.length == 0) {
                return 0f;
            }
            return sorted[rank(sorted.length, p)];
        }

        /** Nearest-rank percentile index into a sorted array of length n. */
        private static int rank(int n, int p) {
            int r = (int) Math.ceil(p / 100.0 * n) - 1;
            return Math.max(0, Math.min(n - 1, r));
        }
    }
}
