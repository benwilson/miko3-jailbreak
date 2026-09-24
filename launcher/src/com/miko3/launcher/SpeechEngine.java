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
 * Log lines carry the spoken text and the timing (time to first audio is
 * measured from speak() to the head first moving).
 */
final class SpeechEngine implements SpeechQueue.Voice, Runnable {
    static final String TAG = "SpeechEngine";
    private static final String VOICE_ASSETS = "voice";
    private static final String STAMP = "stamp.txt";
    /** Frames per blocking write; between slices the head is checked. */
    private static final int SLICE_FRAMES = 1024;
    /** Slack past a line's own length before it is given up on. */
    private static final long DRAIN_SLACK_MS = 3000;
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
    private long lineId;
    private long queuedAtNanos;
    private long startedAtNanos;
    private long written;
    private boolean heard;
    private short[] pcm = new short[0];

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
        } catch (Throwable t) {
            Log.e(TAG, "voice failed to load; the robot cannot speak", t);
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

    private static void copyAssets(AssetManager assets, String path, File dest) throws IOException {
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

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                deleteTree(k);
            }
        }
        f.delete();
    }

    private static String readAll(InputStream in) throws IOException {
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
        startedAtNanos = System.nanoTime();
        written = 0;
        heard = false;
        track.setBufferSizeInFrames(startFrames);
        track.play();
        Log.i(TAG, "line " + id + " starting after " + ms(startedAtNanos - queuedAt) + " ms in the queue: \""
                + text + "\"");
    }

    @Override
    public void speak(String chunk) {
        long t0 = System.nanoTime();
        try {
            tts.generateWithCallback(chunk, 0, 1.0f, new OfflineTtsCallback() {
                @Override
                public Integer invoke(float[] samples) {
                    write(samples);
                    return 1;
                }
            });
        } catch (RuntimeException e) {
            Log.e(TAG, "line " + lineId + ": synthesis failed for \"" + chunk + "\"", e);
            throw e;
        }
        Log.i(TAG, "line " + lineId + ": made \"" + chunk + "\" in " + ms(System.nanoTime() - t0) + " ms");
    }

    @Override
    public void endLine(long id, boolean cancelled) {
        long speechEnd = written;
        // Pad with a start buffer of silence, so a short line still fills the
        // buffer once and starts; the line has ended when the head passes speechEnd.
        writePcm(new short[startFrames], startFrames);
        long deadline = SystemClock.elapsedRealtime() + speechEnd * 1000 / rate + DRAIN_SLACK_MS;
        while (head() < speechEnd) {
            checkHead();
            if (SystemClock.elapsedRealtime() > deadline) {
                Log.w(TAG, "line " + id + ": playback head stuck at " + head() + " of " + speechEnd
                        + " frames; ending the line");
                break;
            }
            SystemClock.sleep(10);
        }
        checkHead();
        try {
            track.pause();
            track.flush();
        } catch (IllegalStateException ignored) {
        }
        Log.i(TAG, "line " + id + (cancelled ? " cancelled" : " finished") + " " + ms(System.nanoTime()
                - queuedAtNanos) + " ms after speak(), " + (speechEnd * 1000 / Math.max(1, rate))
                + " ms of audio");
    }

    private void write(float[] samples) {
        if (pcm.length < samples.length) {
            pcm = new short[samples.length];
        }
        for (int i = 0; i < samples.length; i++) {
            float s = samples[i];
            s = s > 1f ? 1f : (s < -1f ? -1f : s);
            pcm[i] = (short) (s * 32767f);
        }
        writePcm(pcm, samples.length);
        written += samples.length;
    }

    /** Blocking writes in slices, checking the head between them. */
    private void writePcm(short[] data, int frames) {
        int off = 0;
        while (off < frames) {
            int n = track.write(data, off, Math.min(SLICE_FRAMES, frames - off), AudioTrack.WRITE_BLOCKING);
            if (n < 0) {
                throw new IllegalStateException("speaker write failed: " + n);
            }
            off += n;
            checkHead();
        }
    }

    /** Once the head first moves: log the time to first audio and open the
     * buffer to its full capacity, so later writes stop waiting on playback. */
    private void checkHead() {
        if (heard || head() <= 0) {
            return;
        }
        heard = true;
        track.setBufferSizeInFrames(capacityFrames);
        long now = System.nanoTime();
        Log.i(TAG, "line " + lineId + ": first audio " + ms(now - queuedAtNanos) + " ms after speak() ("
                + ms(now - startedAtNanos) + " ms after it started)");
    }

    private long head() {
        return track.getPlaybackHeadPosition() & 0xffffffffL;
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
