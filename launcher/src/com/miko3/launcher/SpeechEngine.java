package com.miko3.launcher;

import android.content.Context;
import android.content.res.AssetManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsCallback;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

/**
 * The launcher's voice (voice plan U5; R8-R10, KTD3, KTD4, KTD8): one
 * sherpa-onnx VITS (Piper) voice, loaded once per process at launcher start,
 * and the one "speech" thread that makes and plays every queued line.
 *
 * The thread runs at THREAD_PRIORITY_URGENT_AUDIO, as VoicePlayer's does. It
 * first copies assets/voice/ (model.onnx, tokens.txt, espeak-ng-data/) into
 * the files directory, keyed by the build's voice/stamp.txt so an updated
 * voice is copied again, and loads it; then it takes SpeechQueue's lines one
 * at a time. Each chunk (one sentence) goes to generateWithCallback, whose
 * audio is written into one streaming AudioTrack on the music stream, so the
 * first sentence plays while the next is made (R10). A line has finished when
 * the playback head has passed its last frame, as VoicePlayer judges it,
 * never when the writes return.
 *
 * The track handling (buffer sizing, recovering from an underrun between
 * sentences, the drain) lives in the plain-Java SpeechPlayer so host tests
 * can run it. Synthesis runs ahead of playback, so a cancel usually arrives
 * with more sentences already written than played; the player (LineDrain)
 * stops the track at the end of the sentence playing when the cancel is seen,
 * polling the queue while the line drains, and the rest is dropped.
 *
 * Log lines carry line ids, character counts and the timing (time to first
 * audio is measured from speak() to the head first moving), never the spoken
 * text itself.
 */
final class SpeechEngine implements SpeechQueue.Voice, Runnable {
    static final String TAG = "SpeechEngine";
    private static final String VOICE_ASSETS = "voice";
    private static final String STAMP = "stamp.txt";
    /** Which voice the build staged ("stock lessac medium" or "trained"). */
    private static final String LABEL = "label.txt";
    private static final String WARM_UP = "Hello there, it is nice to meet you, and I hope we can play together soon.";

    private final Context context;
    final SpeechTuning tuning;
    private final SpeechQueue queue;

    // Speech thread only.
    private OfflineTts tts;
    private AudioTrack track;
    private int rate;
    private int startFrames;
    private int capacityFrames;
    private SpeechPlayer player;
    private long lineId;
    private long queuedAtNanos;
    // Set once the voice has loaded and warmed up; read by the Settings page.
    private volatile boolean ready;
    // Set if the voice failed to load; it will then never be ready.
    private volatile boolean failed;

    SpeechEngine(Context context) {
        this.context = context.getApplicationContext();
        this.tuning = SpeechTuning.from(new SpeechTuning.Props() {
            @Override
            public String get(String key) {
                return systemProperty(key);
            }
        });
        this.queue = new SpeechQueue(tuning.maxWords);
    }

    SpeechQueue queue() {
        return queue;
    }

    /** True once the voice has loaded and warmed up, so a line plays soon. */
    boolean isReady() {
        return ready;
    }

    /** True once the voice has failed to load: the robot cannot speak until
     * the launcher restarts. */
    boolean hasFailed() {
        return failed;
    }

    /** The staged voice's label from assets/voice/label.txt, or "unknown". */
    String voiceName() {
        try {
            String label = readAll(context.getAssets().open(VOICE_ASSETS + "/" + LABEL)).trim();
            return label.isEmpty() ? "unknown" : label;
        } catch (IOException e) {
            return "unknown";
        }
    }

    /** Starts the speech thread, which loads the voice and then plays lines.
     * Lines queued before the voice is ready wait for it. */
    void start() {
        Thread t = new Thread(this, "speech");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        long t0 = SystemClock.elapsedRealtime();
        try {
            File dir = installVoice();
            long copied = SystemClock.elapsedRealtime();
            tts = load(dir);
            rate = tts.getSampleRate();
            track = createTrack(rate);
            player = new SpeechPlayer(new TrackSpeaker(track), rate, startFrames, capacityFrames);
            // One generate before the first real line, about as long as the
            // longest chunk, so the first line doesn't also pay ONNX Runtime's
            // first-run allocations (the first line after loading was seen at
            // 2.5 s to first audio, the next ones at 1.2 s, with Explore busy).
            long warm = System.nanoTime();
            tts.generate(WARM_UP);
            Log.i(TAG, "voice warmed up in " + ms(System.nanoTime() - warm) + " ms");
            Log.i(TAG, "voice ready in " + (SystemClock.elapsedRealtime() - t0) + " ms (files "
                    + (copied - t0) + " ms): " + rate + " Hz, " + tuning + ", start buffer " + startFrames
                    + " frames, capacity " + capacityFrames + " frames");
            ready = true;
        } catch (Throwable t) {
            Log.e(TAG, "voice failed to load; the robot cannot speak", t);
            failed = true;
            queue.shutdown();
            return;
        }
        try {
            while (queue.playNext(this, true)) {
                // one line per call
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- voice files ----

    /** The voice's files directory, copied from assets/voice/ unless the copy
     * there already carries this build's stamp. The stamp is written last, so
     * an interrupted copy is redone. */
    private File installVoice() throws IOException {
        AssetManager assets = context.getAssets();
        String stamp = readAll(assets.open("voice/stamp.txt")).trim();
        File dir = new File(context.getFilesDir(), VOICE_ASSETS);
        File stampFile = new File(dir, STAMP);
        if (stampFile.isFile() && stamp.equals(readAll(new FileInputStream(stampFile)).trim())) {
            return dir;
        }
        Log.i(TAG, "copying voice " + stamp + " out of the APK");
        File tmp = new File(context.getFilesDir(), VOICE_ASSETS + ".tmp");
        deleteTree(tmp);
        copyAssets(assets, VOICE_ASSETS, tmp);
        new File(tmp, STAMP).delete();
        deleteTree(dir);
        if (!tmp.renameTo(dir)) {
            throw new IOException("could not move the voice into " + dir);
        }
        OutputStream out = new FileOutputStream(stampFile);
        try {
            out.write(stamp.getBytes(StandardCharsets.UTF_8));
        } finally {
            out.close();
        }
        return dir;
    }

    static void copyAssets(AssetManager assets, String path, File dest) throws IOException {
        String[] children = assets.list(path);
        if (children == null || children.length == 0) {
            InputStream in = assets.open(path);
            try {
                OutputStream out = new FileOutputStream(dest);
                try {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                } finally {
                    out.close();
                }
            } finally {
                in.close();
            }
            return;
        }
        if (!dest.isDirectory() && !dest.mkdirs()) {
            throw new IOException("could not create " + dest);
        }
        for (String child : children) {
            copyAssets(assets, path + "/" + child, new File(dest, child));
        }
    }

    static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                deleteTree(k);
            }
        }
        f.delete();
    }

    static String readAll(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }

    // ---- sherpa-onnx and the track ----

    private OfflineTts load(File dir) {
        OfflineTtsVitsModelConfig vits = OfflineTtsVitsModelConfig.builder()
                .setModel(new File(dir, "model.onnx").getAbsolutePath())
                .setTokens(new File(dir, "tokens.txt").getAbsolutePath())
                .setDataDir(new File(dir, "espeak-ng-data").getAbsolutePath())
                .build();
        OfflineTtsModelConfig model = OfflineTtsModelConfig.builder()
                .setVits(vits)
                .setNumThreads(tuning.threads)
                .setDebug(false)
                .setProvider("cpu")
                .build();
        // One sentence per callback: the first plays while the next is made.
        OfflineTtsConfig config = OfflineTtsConfig.builder()
                .setModel(model)
                .setMaxNumSentences(1)
                .build();
        return new OfflineTts(config);
    }

    private AudioTrack createTrack(int sampleRate) {
        int minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) {
            throw new IllegalStateException("unsupported speaker configuration (" + minBuf + ")");
        }
        // A roomy capacity (20 s) so synthesis of the next sentence never waits
        // on playback, but a small working size at each start: the platform
        // holds a streaming track until its buffer is full once, so a large
        // buffer from the start would delay the first sound (VoicePlayer's lesson).
        AudioTrack t = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(Math.max(minBuf, sampleRate * 2 * 20))
                .build();
        if (t.getState() != AudioTrack.STATE_INITIALIZED) {
            t.release();
            throw new IllegalStateException("speaker track failed to initialize");
        }
        capacityFrames = t.getBufferCapacityInFrames();
        startFrames = Math.min(capacityFrames, Math.max(minBuf / 2, sampleRate / 5));
        return t;
    }

    // ---- SpeechQueue.Voice, on the speech thread ----

    @Override
    public void startLine(long id, String text, long queuedAt) {
        lineId = id;
        queuedAtNanos = queuedAt;
        player.startLine(queuedAt);
        Log.i(TAG, "line " + id + " starting after " + ms(System.nanoTime() - queuedAt) + " ms in the queue ("
                + text.length() + " chars)");
    }

    @Override
    public void speak(String chunk) {
        long t0 = System.nanoTime();
        try {
            tts.generateWithCallback(chunk, 0, 1.0f, new OfflineTtsCallback() {
                @Override
                public Integer invoke(float[] samples) {
                    player.write(samples);
                    return 1;
                }
            });
        } catch (RuntimeException e) {
            Log.e(TAG, "line " + lineId + ": synthesis failed for a chunk of " + chunk.length() + " chars", e);
            throw e;
        }
        player.chunkEnded();
        Log.i(TAG, "line " + lineId + ": made a chunk of " + chunk.length() + " chars in "
                + ms(System.nanoTime() - t0) + " ms");
    }

    @Override
    public void endLine(final long id, boolean cancelled) {
        long speechEnd = player.written();
        // Polled while the line drains, so a cancel stops it at the end of the
        // sentence playing then, however far synthesis ran ahead.
        boolean reached = player.endLine(cancelled, new LineDrain.CancelCheck() {
            @Override
            public boolean cancelled() {
                return queue.cancelRequested(id);
            }
        });
        if (!reached) {
            Log.w(TAG, "line " + id + ": playback head stuck short of " + speechEnd + " frames; ended the line");
        }
        cancelled |= queue.cancelRequested(id);
        Log.i(TAG, "line " + id + (cancelled ? " cancelled" : " finished") + " " + ms(System.nanoTime()
                - queuedAtNanos) + " ms after speak(), " + (speechEnd * 1000 / Math.max(1, rate))
                + " ms of audio");
    }

    /** The AudioTrack behind SpeechPlayer. */
    private final class TrackSpeaker implements SpeechPlayer.Speaker {
        private final AudioTrack t;

        TrackSpeaker(AudioTrack t) {
            this.t = t;
        }

        @Override
        public long head() {
            return t.getPlaybackHeadPosition() & 0xffffffffL;
        }

        @Override
        public int write(short[] data, int off, int frames) {
            return t.write(data, off, frames, AudioTrack.WRITE_BLOCKING);
        }

        @Override
        public void setBufferSizeInFrames(int frames) {
            t.setBufferSizeInFrames(frames);
        }

        @Override
        public void play() {
            t.play();
        }

        @Override
        public void pause() {
            t.pause();
        }

        @Override
        public void flush() {
            t.flush();
        }

        @Override
        public long nowMs() {
            return SystemClock.elapsedRealtime();
        }

        @Override
        public void sleep(long ms) {
            SystemClock.sleep(ms);
        }

        @Override
        public void note(String message) {
            Log.i(TAG, "line " + lineId + ": " + message);
        }
    }

    private static long ms(long nanos) {
        return nanos / 1000000L;
    }

    /** android.os.SystemProperties.get(key), which apps may read; "" if unset
     * or unreadable. */
    private static String systemProperty(String key) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            Method get = c.getMethod("get", String.class, String.class);
            return (String) get.invoke(null, key, "");
        } catch (Exception e) {
            return "";
        }
    }
}
