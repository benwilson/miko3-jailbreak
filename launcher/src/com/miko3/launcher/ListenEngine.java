package com.miko3.launcher;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import com.k2fsa.sherpa.onnx.EndpointConfig;
import com.k2fsa.sherpa.onnx.EndpointRule;
import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * The launcher's ears (explore-on-claude plan U3; R11, KTD1, KTD4, KTD8):
 * sherpa-onnx's streaming zipformer-en-20M (int8) and one AudioRecord per
 * listen, behind ListenSession's plain-Java rules. Lives in the launcher next
 * to SpeechEngine because sherpa-onnx is already here (Explore's own ONNX
 * Runtime would clash with it), and so the launcher, not each mode, holds the
 * microphone.
 *
 * start() copies assets/listen/ (encoder.onnx, decoder.onnx, joiner.onnx,
 * tokens.txt, stamped like the voice) out of the APK when the stamp changed,
 * and loads the recognizer on its own thread; listen() is refused until then.
 * Each listen runs on the one "listen" thread: it waits for the speech queue
 * to go idle, then records 16 kHz mono on VOICE_COMMUNICATION, the source
 * voice mode uses (the vendor's two-mic noise suppression), and ends at the
 * model's endpoint (0.8 s of silence after words) or the cap.
 */
final class ListenEngine implements ListenSession.Ears {
    static final String TAG = "ListenEngine";
    private static final String MODEL_ASSETS = "listen";
    private static final String STAMP = "stamp.txt";
    /** How long a listen waits for the robot to finish speaking. */
    static final long IDLE_TIMEOUT_MS = 20000;
    private static final int THREADS = 2;
    /** Silence handed to the model after a capped listen, so its last words come out. */
    private static final int TAIL_SAMPLES = ListenSession.SAMPLE_RATE * 3 / 10;

    /** Told once how a listen ended, on the listen thread. */
    interface Answer {
        void answer(ListenSession.Result result);
    }

    private final Context context;
    private final ListenSession session;
    private volatile OnlineRecognizer recognizer;
    private final ExecutorService thread = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "listen");
            t.setDaemon(true);
            return t;
        }
    });

    ListenEngine(Context context, final SpeechQueue speech) {
        this.context = context.getApplicationContext();
        this.session = new ListenSession(new ListenSession.Idle() {
            @Override
            public boolean awaitIdle(long timeoutMs) throws InterruptedException {
                return speech.awaitIdle(timeoutMs);
            }
        }, this, IDLE_TIMEOUT_MS);
    }

    ListenSession session() {
        return session;
    }

    /** Loads the model on the listen thread (so a listen queued behind it waits). */
    void start() {
        thread.execute(new Runnable() {
            @Override
            public void run() {
                load();
            }
        });
    }

    /** Runs a claimed listen on the listen thread; answer is told once. */
    void listen(final long maxMs, final Answer answer) {
        thread.execute(new Runnable() {
            @Override
            public void run() {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
                answer.answer(session.run(maxMs));
            }
        });
    }

    private void load() {
        long t0 = SystemClock.elapsedRealtime();
        try {
            File dir = installAssets(context, MODEL_ASSETS);
            long copied = SystemClock.elapsedRealtime();
            OnlineRecognizer r = new OnlineRecognizer(config(dir));
            long loaded = SystemClock.elapsedRealtime();
            // One short decode first, so the first real listen doesn't pay ONNX
            // Runtime's first-run allocations while someone is answering.
            OnlineStream warm = r.createStream();
            warm.acceptWaveform(new float[ListenSession.SAMPLE_RATE / 2], ListenSession.SAMPLE_RATE);
            warm.inputFinished();
            while (r.isReady(warm)) {
                r.decode(warm);
            }
            warm.release();
            recognizer = r;
            Log.i(TAG, "recognizer ready in " + (SystemClock.elapsedRealtime() - t0) + " ms (files "
                    + (copied - t0) + " ms, load " + (loaded - copied) + " ms, warm-up "
                    + (SystemClock.elapsedRealtime() - loaded) + " ms)");
        } catch (Throwable t) {
            Log.e(TAG, "recognizer failed to load; the robot cannot listen", t);
        }
    }

    private static OnlineRecognizerConfig config(File dir) {
        OnlineTransducerModelConfig transducer = OnlineTransducerModelConfig.builder()
                .setEncoder(new File(dir, "encoder.onnx").getAbsolutePath())
                .setDecoder(new File(dir, "decoder.onnx").getAbsolutePath())
                .setJoiner(new File(dir, "joiner.onnx").getAbsolutePath())
                .build();
        OnlineModelConfig model = OnlineModelConfig.builder()
                .setTransducer(transducer)
                .setTokens(new File(dir, "tokens.txt").getAbsolutePath())
                .setNumThreads(THREADS)
                .setDebug(false)
                .setProvider("cpu")
                .build();
        // KTD4: the reply ends 0.8 s after its last word (rule 2); a person who
        // says nothing for 4 s isn't answering (rule 1); the cap (ListenSession)
        // stops anything longer, so rule 3 is only a far backstop.
        EndpointConfig endpoint = EndpointConfig.builder()
                .setRule1(EndpointRule.builder().setMustContainNonSilence(false)
                        .setMinTrailingSilence(4.0f).setMinUtteranceLength(0f).build())
                .setRule2(EndpointRule.builder().setMustContainNonSilence(true)
                        .setMinTrailingSilence(0.8f).setMinUtteranceLength(0f).build())
                .setRule3(EndpointRule.builder().setMustContainNonSilence(false)
                        .setMinTrailingSilence(0f).setMinUtteranceLength(20f).build())
                .build();
        return OnlineRecognizerConfig.builder()
                .setFeatureConfig(FeatureConfig.builder().setSampleRate(ListenSession.SAMPLE_RATE)
                        .setFeatureDim(80).build())
                .setOnlineModelConfig(model)
                .setEndpointConfig(endpoint)
                .setEnableEndpoint(true)
                .setDecodingMethod("greedy_search")
                .build();
    }

    // ---- ListenSession.Ears, on the listen thread ----

    @Override
    public boolean ready() {
        if (recognizer == null) {
            return false;
        }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "RECORD_AUDIO not granted: pm grant com.miko3.launcher android.permission.RECORD_AUDIO");
            return false;
        }
        return true;
    }

    @Override
    public ListenSession.Mic openMic() throws IOException {
        int minBuf = AudioRecord.getMinBufferSize(ListenSession.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) {
            throw new IOException("unsupported microphone configuration (" + minBuf + ")");
        }
        final AudioRecord record;
        try {
            record = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, ListenSession.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minBuf, ListenSession.CHUNK_SAMPLES * 2 * 4));
        } catch (RuntimeException e) {
            throw new IOException("microphone refused: " + e.getMessage());
        }
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            record.release();
            throw new IOException("microphone failed to initialize");
        }
        record.startRecording();
        if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            record.release();
            throw new IOException("microphone did not start recording");
        }
        Log.i(TAG, "microphone open (VOICE_COMMUNICATION, 16 kHz mono, buffer " + minBuf + ")");
        return new ListenSession.Mic() {
            private final short[] pcm = new short[ListenSession.CHUNK_SAMPLES];
            private double sumSquares;
            private long count;
            private int peak;

            @Override
            public int read(float[] buf) {
                int n = record.read(pcm, 0, Math.min(pcm.length, buf.length));
                if (n < 0) {
                    Log.w(TAG, "microphone read failed: " + n);
                    return -1;
                }
                for (int i = 0; i < n; i++) {
                    int s = pcm[i];
                    buf[i] = s / 32768f;
                    sumSquares += (double) s * s;
                    peak = Math.max(peak, Math.abs(s));
                }
                count += n;
                return n;
            }

            @Override
            public void close() {
                try {
                    record.stop();
                } catch (IllegalStateException ignored) {
                    // Already stopped.
                }
                record.release();
                long rms = count == 0 ? 0 : Math.round(Math.sqrt(sumSquares / count));
                Log.i(TAG, "microphone closed after " + count + " samples, RMS " + rms + ", peak " + peak);
            }
        };
    }

    @Override
    public ListenSession.Recognizer newRecognizer() {
        final OnlineRecognizer r = recognizer;
        final OnlineStream stream = r.createStream();
        return new ListenSession.Recognizer() {
            @Override
            public void accept(float[] samples, int n) {
                stream.acceptWaveform(n == samples.length ? samples : Arrays.copyOf(samples, n),
                        ListenSession.SAMPLE_RATE);
                decode();
            }

            @Override
            public boolean isEndpoint() {
                return r.isEndpoint(stream);
            }

            @Override
            public String text() {
                return r.getResult(stream).getText();
            }

            @Override
            public void finish() {
                stream.acceptWaveform(new float[TAIL_SAMPLES], ListenSession.SAMPLE_RATE);
                stream.inputFinished();
                decode();
            }

            @Override
            public void close() {
                stream.release();
            }

            private void decode() {
                while (r.isReady(stream)) {
                    r.decode(stream);
                }
            }
        };
    }

    // ---- model files ----

    /** assets/name/ copied into the files directory, unless the copy there
     * already carries this build's stamp (written last, so an interrupted
     * copy is redone). Same scheme as SpeechEngine's voice. */
    static File installAssets(Context context, String name) throws IOException {
        AssetManager assets = context.getAssets();
        String stamp = SpeechEngine.readAll(assets.open(name + "/" + STAMP)).trim();
        File dir = new File(context.getFilesDir(), name);
        File stampFile = new File(dir, STAMP);
        if (stampFile.isFile() && stamp.equals(SpeechEngine.readAll(new FileInputStream(stampFile)).trim())) {
            return dir;
        }
        Log.i(TAG, "copying " + name + " " + stamp + " out of the APK");
        File tmp = new File(context.getFilesDir(), name + ".tmp");
        SpeechEngine.deleteTree(tmp);
        SpeechEngine.copyAssets(assets, name, tmp);
        new File(tmp, STAMP).delete();
        SpeechEngine.deleteTree(dir);
        if (!tmp.renameTo(dir)) {
            throw new IOException("could not move " + name + " into " + dir);
        }
        OutputStream out = new FileOutputStream(stampFile);
        try {
            out.write(stamp.getBytes(StandardCharsets.UTF_8));
        } finally {
            out.close();
        }
        return dir;
    }
}
