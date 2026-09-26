package com.miko3.launcher;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Process;
import android.os.RemoteException;
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
import com.k2fsa.sherpa.onnx.SileroVadModelConfig;
import com.k2fsa.sherpa.onnx.Vad;
import com.k2fsa.sherpa.onnx.VadModelConfig;
import com.miko3.shared.RobotEars;
import com.miko3.shared.VoiceDirection;

import recognizer.WakeWord;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * The launcher's ears (explore-on-claude plan U3; meeting plan U3): one
 * sherpa-onnx streaming zipformer-en-20M (int8) recogniser configured per
 * KTD2, the vendor wake-word engine, a Silero VAD gate and the direction
 * sampler, behind two plain-Java rule sets: ListenSession for the one-shot
 * listen and EarsSession for the continuous session. Lives in the launcher
 * next to SpeechEngine because sherpa-onnx is already here (Explore's own
 * ONNX Runtime would clash with it), and so the launcher, not each mode,
 * holds the microphone.
 *
 * start() copies assets/listen/ (encoder, decoder, joiner, tokens.txt, the
 * bpe vocabulary and the VAD model, stamped like the voice) out of the APK
 * when the stamp changed, reads the hotwords file from the asset root, and
 * loads the recogniser, the VAD and the wake-word engine on the listen
 * thread; listens are refused until then. The one recogniser decodes with
 * modified_beam_search, the hotwords file, bpe modelling with its vocabulary,
 * 2 threads and 2 active paths, and ends an utterance 0.8 s after its last
 * word or after about 2 s of nothing decoded.
 *
 * The one-shot listen runs on the "listen" thread as before. The ears
 * session runs its capture on its own "ears" thread, samples the direction
 * angle on VoiceDirection's thread, delivers utterances from a "ears-deliver"
 * thread, and is ticked twice a second for its keeper.
 */
final class ListenEngine implements ListenSession.Ears {
    static final String TAG = "ListenEngine";
    private static final String MODEL_ASSETS = "listen";
    private static final String STAMP = "stamp.txt";
    private static final String BPE_VOCAB = "bpe.vocab";
    private static final String VAD_MODEL = "silero_vad.onnx";
    /** KTD2: at the asset root, outside the staged model directory. */
    private static final String HOTWORDS_ASSET = "hotwords.txt";
    private static final String WAKE_MODEL_ASSET = "miko_wakeword_model.tflite";
    /** How long a listen waits for the robot to finish speaking. */
    static final long IDLE_TIMEOUT_MS = 20000;
    /** KTD2: threads and active paths for the one recogniser. */
    private static final int THREADS = 2;
    private static final int MAX_ACTIVE_PATHS = 2;
    private static final float HOTWORDS_SCORE = 2.0f;
    /** The wake-word thresholds voice mode measured (VoiceEngine). */
    private static final float HEY_THRESHOLD = 0.65f;
    private static final float HELLO_THRESHOLD = 0.6f;
    /** Silero: speech ends 250 ms after the last voiced window; the session's
     * hangover then gives the recogniser its trailing silence. */
    private static final float VAD_THRESHOLD = 0.5f;
    private static final float VAD_MIN_SILENCE_S = 0.25f;
    private static final float VAD_MIN_SPEECH_S = 0.1f;
    private static final int VAD_WINDOW = 512;
    private static final float VAD_MAX_SPEECH_S = 20f;
    private static final long TICK_MS = 500;
    /** Silence handed to the model after a capped listen, so its last words come out. */
    private static final int TAIL_SAMPLES = ListenSession.SAMPLE_RATE * 3 / 10;

    /** Told once how a listen ended, on the listen thread. */
    interface Answer {
        void answer(ListenSession.Result result);
    }

    private final Context context;
    private final ListenSession session;
    private final EarsSession ears;
    private final SpeechTuning tuning;
    private volatile OnlineRecognizer recognizer;
    private volatile Vad vad;
    private volatile WakeWord wakeWord;
    private volatile int wakeClasses;
    private volatile String hotwordsPath;
    /** The ears probe's tap on the running listen (meeting plan U1), or null. Set and
     * cleared on the listen thread around the one session.run() it wraps. */
    private volatile EarsProbe.Tap tap;
    private final ExecutorService thread = Executors.newSingleThreadExecutor(named("listen"));
    private final ExecutorService deliver = Executors.newSingleThreadExecutor(named("ears-deliver"));
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(named("ears-tick"));

    private static ThreadFactory named(final String name) {
        return new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, name);
                t.setDaemon(true);
                return t;
            }
        };
    }

    ListenEngine(Context context, final SpeechQueue speech, final ClaudeSettings settings) {
        this.context = context.getApplicationContext();
        this.session = new ListenSession(new ListenSession.Idle() {
            @Override
            public boolean awaitIdle(long timeoutMs) throws InterruptedException {
                return speech.awaitIdle(timeoutMs);
            }
        }, this, IDLE_TIMEOUT_MS);
        this.tuning = SpeechTuning.from(new SpeechTuning.Props() {
            @Override
            public String get(String key) {
                return SpeechEngine.systemProperty(key);
            }
        });
        // KTD11: the Settings page's "answers when spoken to" switch, read through
        // the launcher's settings (its one parser of the stored value) at classify time.
        CueClassifier.Switch earsSwitch = new CueClassifier.Switch() {
            @Override
            public boolean answersWhenSpokenTo() {
                return settings.conversation().answersWhenSpokenTo;
            }
        };
        this.ears = new EarsSession(new EarsSession.Clock() {
            @Override
            public long nowMs() {
                return SystemClock.elapsedRealtime();
            }
        }, earsCapture, earsSpotter, earsGate, earsRecognizer, earsDirection, new CueClassifier(earsSwitch),
                tuning.deafTailMs, new EarsSession.Diag() {
                    @Override
                    public void log(String note) {
                        Log.i(TAG, "ears: " + note);
                    }
                });
        // KTD1: the deaf window follows the speech queue's line start and idle.
        speech.setSpeaking(new SpeechQueue.Speaking() {
            @Override
            public void started() {
                ears.lineStarted();
            }

            @Override
            public void idle() {
                ears.playbackIdle();
            }
        });
    }

    ListenSession session() {
        return session;
    }

    EarsSession ears() {
        return ears;
    }

    /** Loads the models on the listen thread (so a listen queued behind it waits),
     * and starts the ears session's tick. */
    void start() {
        thread.execute(new Runnable() {
            @Override
            public void run() {
                load();
            }
        });
        ticker.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    ears.tick();
                } catch (RuntimeException e) {
                    Log.e(TAG, "ears tick failed", e);
                }
            }
        }, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
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

    /** Throws IllegalStateException(EarsSession.REFUSE_EARS_OPEN) while a mode
     * holds the ears session: the one-shot listen and the probe keep off its microphone. */
    void refuseOneShot() {
        ears.refuseOneShot();
    }

    /** Opens the ears session for uid, delivering to callback until its binder
     * dies, it closes, or it misses three renews (EarsSession). */
    void openEars(int uid, final RobotEars.Callback callback, boolean chargerLatched) {
        LeaseKeeper.Token token = new BinderToken(callback.asBinder());
        EarsSession.Client client = new EarsSession.Client() {
            @Override
            public void heard(final EarsSession.Utterance u) {
                try {
                    deliver.execute(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                callback.heard(u.text, u.side, u.angle == null ? Float.NaN : u.angle, u.tier, u.at,
                                        u.partial);
                            } catch (RemoteException | RuntimeException e) {
                                // The client is gone; its death releases the session.
                            }
                        }
                    });
                } catch (RejectedExecutionException ignored) {
                    // Shutting down.
                }
            }
        };
        ears.open(String.valueOf(uid), token, client, chargerLatched);
    }

    /**
     * The ears probe (meeting plan U1, step 4): claims the microphone exactly as a
     * listen does (IllegalStateException with REFUSE_BUSY or REFUSE_UNAVAILABLE at
     * once when it cannot, or REFUSE_EARS_OPEN while a session holds it), then runs
     * one capped listen on the listen thread with the probe's tap on the microphone
     * and the recogniser. The listen's own transcript is dropped; the Future carries
     * the tap's per-second rows, or an IllegalStateException with the listen's fixed
     * failure reason (FAIL_*).
     */
    Future<List<EarsProbe.Row>> probe(final EarsProbe.Tap probeTap, final int seconds) {
        ears.refuseOneShot();
        session.claim();
        return thread.submit(new Callable<List<EarsProbe.Row>>() {
            @Override
            public List<EarsProbe.Row> call() {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
                tap = probeTap;
                ListenSession.Result r;
                try {
                    r = session.run(seconds * 1000L);
                } finally {
                    tap = null;
                }
                if (r.outcome == ListenSession.Outcome.FAILED) {
                    throw new IllegalStateException(r.reason);
                }
                return probeTap.rows();
            }
        });
    }

    private void load() {
        long t0 = SystemClock.elapsedRealtime();
        try {
            File dir = installAssets(context, MODEL_ASSETS);
            hotwordsPath = copyAsset(context, HOTWORDS_ASSET).getAbsolutePath();
            long copied = SystemClock.elapsedRealtime();
            OnlineRecognizer r = new OnlineRecognizer(config(dir, hotwordsPath));
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
            vad = new Vad(vadConfig(dir));
            recognizer = r;
            Log.i(TAG, "recognizer ready in " + (SystemClock.elapsedRealtime() - t0) + " ms (files "
                    + (copied - t0) + " ms, load " + (loaded - copied) + " ms, warm-up and VAD "
                    + (SystemClock.elapsedRealtime() - loaded) + " ms)");
        } catch (Throwable t) {
            Log.e(TAG, "recognizer failed to load; the robot cannot listen", t);
        }
        loadWakeWord();
    }

    /** The vendor engine, as VoiceEngine loads it; without it the session still
     * hears his name through the hotwords, so a failure is logged, not fatal. */
    private void loadWakeWord() {
        long t0 = SystemClock.elapsedRealtime();
        try {
            WakeWord ww = new WakeWord();
            if (!ww.init(context, WAKE_MODEL_ASSET)) {
                Log.e(TAG, "wake-word engine init() returned false; the ears hear the name only by transcription");
                return;
            }
            wakeClasses = ww.getNumOutputClasses();
            ww.setThresholds(HEY_THRESHOLD, HELLO_THRESHOLD);
            ww.resetState();
            wakeWord = ww;
            Log.i(TAG, "wake-word engine ready in " + (SystemClock.elapsedRealtime() - t0) + " ms: outputClasses="
                    + wakeClasses);
        } catch (LinkageError | RuntimeException e) {
            Log.e(TAG, "wake-word engine failed to load in lib" + WakeWord.LIBRARY
                    + ".so; the ears hear the name only by transcription", e);
        }
    }

    /** KTD2: the one configuration that serves roaming and the conversation. */
    private static OnlineRecognizerConfig config(File dir, String hotwords) {
        OnlineTransducerModelConfig transducer = OnlineTransducerModelConfig.builder()
                .setEncoder(new File(dir, "encoder.onnx").getAbsolutePath())
                .setDecoder(new File(dir, "decoder.onnx").getAbsolutePath())
                .setJoiner(new File(dir, "joiner.onnx").getAbsolutePath())
                .build();
        OnlineModelConfig model = OnlineModelConfig.builder()
                .setTransducer(transducer)
                .setTokens(new File(dir, "tokens.txt").getAbsolutePath())
                .setModelingUnit("bpe")
                .setBpeVocab(new File(dir, BPE_VOCAB).getAbsolutePath())
                .setNumThreads(THREADS)
                .setDebug(false)
                .setProvider("cpu")
                .build();
        // KTD2: an utterance ends 0.8 s after its last word (rule 2) or after about
        // 2 s of nothing decoded (rule 1); the brain keeps the unanswered-listen
        // clock, and the one-shot's cap stops anything longer, so rule 3 is only a
        // far backstop.
        EndpointConfig endpoint = EndpointConfig.builder()
                .setRule1(EndpointRule.builder().setMustContainNonSilence(false)
                        .setMinTrailingSilence(2.0f).setMinUtteranceLength(0f).build())
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
                .setDecodingMethod("modified_beam_search")
                .setMaxActivePaths(MAX_ACTIVE_PATHS)
                .setHotwordsFile(hotwords)
                .setHotwordsScore(HOTWORDS_SCORE)
                .build();
    }

    private static VadModelConfig vadConfig(File dir) {
        return VadModelConfig.builder()
                .setSileroVadModelConfig(SileroVadModelConfig.builder()
                        .setModel(new File(dir, VAD_MODEL).getAbsolutePath())
                        .setThreshold(VAD_THRESHOLD)
                        .setMinSilenceDuration(VAD_MIN_SILENCE_S)
                        .setMinSpeechDuration(VAD_MIN_SPEECH_S)
                        .setWindowSize(VAD_WINDOW)
                        .setMaxSpeechDuration(VAD_MAX_SPEECH_S)
                        .build())
                .setSampleRate(ListenSession.SAMPLE_RATE)
                .setNumThreads(1)
                .setDebug(false)
                .setProvider("cpu")
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

    /** One AudioRecord on VOICE_COMMUNICATION, started, or IOException with why not. */
    private AudioRecord openRecord() throws IOException {
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
        return record;
    }

    @Override
    public ListenSession.Mic openMic() throws IOException {
        final AudioRecord record = openRecord();
        EarsProbe.Tap t = tap;
        ListenSession.Mic mic = new ListenSession.Mic() {
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
        return t == null ? mic : t.mic(mic);
    }

    @Override
    public ListenSession.Recognizer newRecognizer() {
        final OnlineRecognizer r = recognizer;
        final OnlineStream stream = r.createStream();
        EarsProbe.Tap t = tap;
        ListenSession.Recognizer rec = new ListenSession.Recognizer() {
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
        return t == null ? rec : t.recognizer(rec);
    }

    // ---- EarsSession's ears, on the "ears" capture thread ----

    private final EarsSession.Capture earsCapture = new EarsSession.Capture() {
        @Override
        public EarsSession.Mic open() throws IOException {
            if (!ready() || vad == null) {
                throw new IOException(ListenSession.REFUSE_UNAVAILABLE);
            }
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
            final AudioRecord record = openRecord();
            return new EarsSession.Mic() {
                private long count;

                @Override
                public int read(short[] pcm) {
                    int n = record.read(pcm, 0, pcm.length);
                    if (n < 0) {
                        Log.w(TAG, "ears microphone read failed: " + n);
                        return -1;
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
                    Log.i(TAG, "ears microphone closed after " + count + " samples");
                }
            };
        }
    };

    private final EarsSession.Spotter earsSpotter = new EarsSession.Spotter() {
        private final short[] whole = new short[WakeWord.AUDIO_CHUNK_SIZE];
        private float[] scores = new float[0];

        @Override
        public boolean hears(short[] pcm, int n) {
            WakeWord ww = wakeWord;
            if (ww == null) {
                return false;
            }
            short[] in = pcm;
            if (n != whole.length) {
                Arrays.fill(whole, (short) 0);
                System.arraycopy(pcm, 0, whole, 0, Math.min(n, whole.length));
                in = whole;
            }
            boolean silent = true;
            for (int i = 0; i < in.length && silent; i++) {
                silent = in[i] == 0;
            }
            if (silent) {
                return false; // the vendor skips all-zero chunks the same way
            }
            if (scores.length < Math.max(1, wakeClasses)) {
                scores = new float[Math.max(1, wakeClasses)];
            }
            Arrays.fill(scores, 0f);
            int result = ww.processChunk(in, scores);
            if (result == WakeWord.DETECTION_HEY_MIKO) {
                ww.resetState();
                return true;
            }
            if (result == WakeWord.DETECTION_HELLO_MIKO) {
                ww.resetState(); // logged by voice mode; never a cue here
            }
            return false;
        }

        @Override
        public void reset() {
            WakeWord ww = wakeWord;
            if (ww != null) {
                ww.resetState();
            }
        }
    };

    private final EarsSession.Gate earsGate = new EarsSession.Gate() {
        @Override
        public boolean speech(float[] samples, int n) {
            Vad v = vad;
            if (v == null) {
                return false;
            }
            v.acceptWaveform(n == samples.length ? samples : Arrays.copyOf(samples, n));
            while (!v.empty()) {
                v.pop(); // the segments are not used; the gate is isSpeechDetected()
            }
            return v.isSpeechDetected();
        }

        @Override
        public void reset() {
            Vad v = vad;
            if (v != null) {
                v.reset();
            }
        }
    };

    /** The one recogniser's one long-lived stream; reset() is the stream reset. */
    private final EarsSession.Recognizer earsRecognizer = new EarsSession.Recognizer() {
        private OnlineRecognizer owner;
        private OnlineStream stream;

        private OnlineStream stream() {
            OnlineRecognizer r = recognizer;
            if (r == null) {
                return null;
            }
            if (stream == null || owner != r) {
                stream = r.createStream();
                owner = r;
            }
            return stream;
        }

        @Override
        public void accept(float[] samples, int n) {
            OnlineStream s = stream();
            if (s == null) {
                return;
            }
            s.acceptWaveform(n == samples.length ? samples : Arrays.copyOf(samples, n), ListenSession.SAMPLE_RATE);
            while (owner.isReady(s)) {
                owner.decode(s);
            }
        }

        @Override
        public boolean isEndpoint() {
            OnlineStream s = stream();
            return s != null && owner.isEndpoint(s);
        }

        @Override
        public String text() {
            OnlineStream s = stream();
            return s == null ? "" : owner.getResult(s).getText();
        }

        @Override
        public void reset() {
            OnlineStream s = stream();
            if (s != null) {
                owner.reset(s);
            }
        }
    };

    /** KTD4: sampled on VoiceDirection's own thread, never the capture thread. */
    private final EarsSession.Direction earsDirection = new EarsSession.Direction() {
        @Override
        public EarsSession.Sampling start() {
            final VoiceDirection.Sampler sampler = VoiceDirection.open().sample(EarsSession.DIRECTION_PERIOD_MS);
            return new EarsSession.Sampling() {
                @Override
                public List<Float> drain() {
                    return sampler.drain();
                }

                @Override
                public void stop() {
                    sampler.stop();
                }
            };
        }
    };

    // ---- model files ----

    /** assets/model/ copied into the files directory, unless the copy there
     * already carries this build's stamp (written last, so an interrupted
     * copy is redone). Same scheme as SpeechEngine's voice. */
    static File installAssets(Context context, String model) throws IOException {
        AssetManager assets = context.getAssets();
        String stamp = SpeechEngine.readAll(assets.open(model + "/" + STAMP)).trim();
        File dir = new File(context.getFilesDir(), model);
        File stampFile = new File(dir, STAMP);
        if (stampFile.isFile() && stamp.equals(SpeechEngine.readAll(new FileInputStream(stampFile)).trim())) {
            return dir;
        }
        Log.i(TAG, "copying " + model + " " + stamp + " out of the APK");
        File tmp = new File(context.getFilesDir(), model + ".tmp");
        SpeechEngine.deleteTree(tmp);
        SpeechEngine.copyAssets(assets, model, tmp);
        new File(tmp, STAMP).delete();
        SpeechEngine.deleteTree(dir);
        if (!tmp.renameTo(dir)) {
            throw new IOException("could not move " + model + " into " + dir);
        }
        OutputStream out = new FileOutputStream(stampFile);
        try {
            out.write(stamp.getBytes(StandardCharsets.UTF_8));
        } finally {
            out.close();
        }
        return dir;
    }

    /** One small asset copied afresh into the files directory (the hotwords file is read at start). */
    static File copyAsset(Context context, String name) throws IOException {
        File dest = new File(context.getFilesDir(), name);
        InputStream in = context.getAssets().open(name);
        try {
            OutputStream out = new FileOutputStream(dest);
            try {
                byte[] buf = new byte[8192];
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
        return dest;
    }
}
