package com.miko3.launcher;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Owner 2026-10-03: the opt-in multi-model voice evaluation (VoiceTuning.EVAL_PROP). CAM++ took
 * the owner's wife for him through the robot's mic chain, so other speaker models are compared
 * on the same clips: each clean answer's 3 s clip (the one CAM++ embedded) is embedded by every
 * extra model, sequentially, on this class's own background thread, matched against that model's
 * own prints, and logged as "voice eval: model=NAME best= second= margin= ms=". The people
 * layer's enrol calls enrol each model's embedding of the same answer under the same person id,
 * logging first "voice eval: truth model=NAME true= other_best=" (the answer against the true
 * person's prints and the best of everyone else's), the ground truth that
 * scripts/voice-eval-report.py compares the models on. CAM++ gets the same two lines (model=
 * campplus) from VoiceId. Never logs an id or a name; never holds audio past the clip's
 * embedding; each model's prints (embeddings only) live in prints/NAME.bin and are forgotten
 * with the person. Plain Java, proven in the host harness.
 */
final class VoiceEval {
    static final String CAMPPLUS = "campplus";
    /** Answers whose extra embeddings stay in memory for the enrol calls. */
    static final int RECENT = VoiceTuning.RECENT;

    static final class Model {
        final String tag;
        final VoiceId.Embedder embedder;
        final VoiceStore store;
        /** at -> this model's embedding, the most recent last; touched only on the eval thread. */
        final LinkedHashMap<Long, float[]> recent = new LinkedHashMap<Long, float[]>();

        /** The model's file name less ".onnx", the name in its log lines (never a person's). */
        Model(String name, VoiceId.Embedder embedder, VoiceStore store) {
            this.tag = name;
            this.embedder = embedder;
            this.store = store;
        }
    }

    private final File printsDir;
    private final VoiceTuning tuning;
    private final VoiceStore.Diag diag;
    private final List<Model> models = new CopyOnWriteArrayList<Model>();

    /**
     * One thread and a short queue: a backlog is dropped, never queued up. Made at the lowest Java
     * priority; on the robot SherpaVoiceEmbedder raises it to the default, as on the voice thread,
     * so each model's ms compares with CAM++'s.
     */
    private final ThreadPoolExecutor thread = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(8), new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "voice-eval");
                    t.setDaemon(true);
                    t.setPriority(Thread.MIN_PRIORITY);
                    return t;
                }
            });

    VoiceEval(File printsDir, VoiceTuning tuning, VoiceStore.Diag diag) {
        this.printsDir = printsDir;
        this.tuning = tuning;
        this.diag = diag;
    }

    /** The model file's name less ".onnx": the name logged for it. */
    static String modelName(String fileName) {
        return fileName.endsWith(".onnx") ? fileName.substring(0, fileName.length() - 5) : fileName;
    }

    /** Adds an extra model, with its own prints file. */
    void addModel(String name, VoiceId.Embedder embedder) {
        printsDir.mkdirs();
        models.add(new Model(name, embedder,
                new VoiceStore(new File(printsDir, name + ".bin"), VoiceTuning.MAX_PER_PERSON, diag)));
    }

    int models() {
        return models.size();
    }

    /** Runs r on the eval thread (the models' load); false when it is busy or shut down. */
    boolean run(Runnable r) {
        try {
            thread.execute(r);
            return true;
        } catch (RejectedExecutionException e) {
            return false;
        }
    }

    // ---- the log lines ----

    static String num(float v) {
        return Float.isNaN(v) ? "nan" : String.format(Locale.US, "%.3f", v);
    }

    void answerLine(String model, VoiceStore.Match m, long ms) {
        diag.log("voice eval: model=" + model + " best=" + num(m.score) + " second=" + num(m.second) + " margin="
                + num(m.margin()) + " ms=" + ms);
    }

    /** The truth line, only when the true person already has prints of this model. */
    void truthLine(String model, float trueScore, float otherBest) {
        if (!Float.isNaN(trueScore)) {
            diag.log("voice eval: truth model=" + model + " true=" + num(trueScore) + " other_best=" + num(otherBest));
        }
    }

    // ---- from VoiceId ----

    /** The clean answer at's clip (already a copy, never kept): every extra model embeds it on the eval thread. */
    void submit(final long at, final float[] clip, final int n) {
        if (models.isEmpty()) {
            return;
        }
        if (!run(new Runnable() {
            @Override
            public void run() {
                embedAll(at, clip, n);
            }
        })) {
            diag.log("voice eval: busy, an answer was skipped");
        }
    }

    private void embedAll(long at, float[] clip, int n) {
        for (Model m : models) {
            long t0 = System.nanoTime();
            float[] e;
            try {
                e = m.embedder.embed(clip, n);
            } catch (Exception | LinkageError ex) {
                diag.log("voice eval: model=" + m.tag + " failed: " + ex.getClass().getSimpleName());
                continue;
            }
            long ms = (System.nanoTime() - t0) / 1000000;
            if (e == null || e.length == 0) {
                diag.log("voice eval: model=" + m.tag + " no embedding");
                continue;
            }
            remember(m, at, e);
            answerLine(m.tag, m.store.match(e, tuning), ms);
        }
    }

    private static void remember(Model m, long at, float[] e) {
        m.recent.remove(at);
        m.recent.put(at, e);
        Iterator<Map.Entry<Long, float[]>> it = m.recent.entrySet().iterator();
        while (m.recent.size() > RECENT && it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    /** The people layer settled the answer at on personId: each model logs its truth line, then enrols it. */
    void enrol(final String personId, final long at) {
        if (models.isEmpty() || personId == null) {
            return;
        }
        if (!run(new Runnable() {
            @Override
            public void run() {
                for (Model m : models) {
                    float[] e = m.recent.get(at);
                    if (e == null) {
                        continue;
                    }
                    truthLine(m.tag, m.store.score(personId, e), m.store.bestOther(personId, e));
                    m.store.add(personId, e);
                }
            }
        })) {
            diag.log("voice eval: busy, an enrolment was skipped");
        }
    }

    /** Forgets personId from every model's prints, at once (the stores are thread-safe). */
    void forget(String personId) {
        for (Model m : models) {
            m.store.forget(personId);
        }
    }

    /** How many prints model name holds for personId (the harness). */
    int count(String name, String personId) {
        for (Model m : models) {
            if (m.tag.equals(name)) {
                return m.store.count(personId);
            }
        }
        return 0;
    }

    /** The prints directory and everything in it (evaluation off: its prints are not kept). */
    static boolean deletePrints(File dir) {
        if (!dir.isDirectory()) {
            return false;
        }
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                f.delete();
            }
        }
        return dir.delete();
    }

    /** The model files in dir (name order), the .onnx ones only. */
    static List<File> modelFiles(File dir) {
        List<File> out = new ArrayList<File>();
        File[] files = dir.listFiles();
        if (files == null) {
            return out;
        }
        java.util.Arrays.sort(files);
        for (File f : files) {
            if (f.isFile() && f.getName().endsWith(".onnx")) {
                out.add(f);
            }
        }
        return out;
    }

    void shutdown() {
        thread.shutdown();
    }

    boolean awaitIdle(long ms) throws InterruptedException {
        return thread.awaitTermination(ms, TimeUnit.MILLISECONDS);
    }
}
