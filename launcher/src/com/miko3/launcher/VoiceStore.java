package com.miko3.launcher;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Owner 2026-10-02: the voices the robot knows, as speaker embeddings only (never audio),
 * up to a cap per person, keyed by an opaque person id the people layer supplies. A probe
 * embedding is scored against each person's centroid (the mean of their normalised
 * embeddings) by cosine; the best person and a band (VoiceTuning) come back, a close
 * runner-up making a strong match only weak. Kept in one app-private file, rewritten
 * whole (through a .tmp and a rename) on every change. Plain Java, proven in the host
 * harness; never logs an id.
 */
final class VoiceStore {
    /** Same values as RobotEars.VOICE_*. */
    static final int BAND_NONE = 0;
    static final int BAND_WEAK = 1;
    static final int BAND_STRONG = 2;

    private static final int MAGIC = 0x4D564F31; // "MVO1"

    interface Diag {
        void log(String line);
    }

    static final class Match {
        /** The best person, or null when the band is none (or nobody is stored). */
        final String id;
        final float score;
        final int band;

        Match(String id, float score, int band) {
            this.id = id;
            this.score = score;
            this.band = band;
        }

        @Override
        public String toString() {
            return String.format(Locale.US, "%s %.3f %s", id, score, VoiceTuning.bandName(band));
        }
    }

    private final File file;
    private final int maxPerPerson;
    private final Diag diag;
    /** Person id -> their embeddings, each unit length, oldest first. */
    private final Map<String, List<float[]>> people = new LinkedHashMap<String, List<float[]>>();
    private boolean loaded;

    VoiceStore(File file, int maxPerPerson, Diag diag) {
        this.file = file;
        this.maxPerPerson = maxPerPerson;
        this.diag = diag;
    }

    /** Adds one embedding for id, dropping their oldest beyond the cap; false for a null id or empty embedding. */
    synchronized boolean add(String id, float[] embedding) {
        if (id == null || id.isEmpty() || embedding == null || embedding.length == 0) {
            return false;
        }
        float[] unit = unit(embedding);
        if (unit == null) {
            return false;
        }
        load();
        List<float[]> list = people.get(id);
        if (list == null) {
            list = new ArrayList<float[]>();
            people.put(id, list);
        }
        list.add(unit);
        while (list.size() > maxPerPerson) {
            list.remove(0);
        }
        save();
        return true;
    }

    /** Drops every embedding stored for id; false when there were none. */
    synchronized boolean forget(String id) {
        load();
        if (id == null || people.remove(id) == null) {
            return false;
        }
        save();
        return true;
    }

    synchronized int count(String id) {
        load();
        List<float[]> list = people.get(id);
        return list == null ? 0 : list.size();
    }

    synchronized int people() {
        load();
        return people.size();
    }

    /** The closest stored person to embedding, and how close. Embeddings of another size are skipped. */
    synchronized Match match(float[] embedding, VoiceTuning tuning) {
        load();
        float[] probe = embedding == null ? null : unit(embedding);
        if (probe == null) {
            return new Match(null, 0f, BAND_NONE);
        }
        String bestId = null;
        float best = -2f;
        float second = -2f;
        for (Map.Entry<String, List<float[]>> e : people.entrySet()) {
            float[] centroid = centroid(e.getValue(), probe.length);
            if (centroid == null) {
                continue;
            }
            float s = dot(probe, centroid);
            if (s > best) {
                second = best;
                best = s;
                bestId = e.getKey();
            } else if (s > second) {
                second = s;
            }
        }
        if (bestId == null) {
            return new Match(null, 0f, BAND_NONE);
        }
        int band = tuning.band(best);
        if (band == BAND_STRONG && best - second < VoiceTuning.TIE_MARGIN) {
            band = BAND_WEAK;
        }
        return new Match(band == BAND_NONE ? null : bestId, best, band);
    }

    private static float[] centroid(List<float[]> list, int dim) {
        float[] sum = null;
        for (float[] v : list) {
            if (v.length != dim) {
                continue;
            }
            if (sum == null) {
                sum = new float[dim];
            }
            for (int i = 0; i < dim; i++) {
                sum[i] += v[i];
            }
        }
        return sum == null ? null : unit(sum);
    }

    private static float dot(float[] a, float[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) {
            s += a[i] * b[i];
        }
        return (float) s;
    }

    /** v scaled to unit length, or null for a zero (or non-finite) vector. */
    static float[] unit(float[] v) {
        double n = 0;
        for (float x : v) {
            n += x * x;
        }
        n = Math.sqrt(n);
        if (!(n > 0) || Double.isInfinite(n)) {
            return null;
        }
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = (float) (v[i] / n);
        }
        return out;
    }

    // ---- the file ----

    private void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        if (!file.isFile()) {
            return;
        }
        Map<String, List<float[]>> read = new LinkedHashMap<String, List<float[]>>();
        try {
            DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)));
            try {
                if (in.readInt() != MAGIC) {
                    throw new IOException("bad magic");
                }
                int n = in.readInt();
                for (int p = 0; p < n; p++) {
                    String id = in.readUTF();
                    int count = in.readInt();
                    int dim = in.readInt();
                    if (count < 0 || count > 1000 || dim < 0 || dim > 8192) {
                        throw new IOException("bad sizes");
                    }
                    List<float[]> list = new ArrayList<float[]>();
                    for (int k = 0; k < count; k++) {
                        float[] v = new float[dim];
                        for (int i = 0; i < dim; i++) {
                            v[i] = in.readFloat();
                        }
                        list.add(v);
                    }
                    read.put(id, list);
                }
            } finally {
                in.close();
            }
        } catch (IOException | RuntimeException e) {
            diag.log("voice: store unreadable (" + e.getClass().getSimpleName() + "), starting empty");
            return;
        }
        people.putAll(read);
        int total = 0;
        for (List<float[]> list : people.values()) {
            total += list.size();
        }
        diag.log("voice: store loaded, " + people.size() + " people, " + total + " embeddings");
    }

    /** Caller holds this. Writes every embedding (never audio) through a .tmp file and a rename. */
    private void save() {
        File tmp = new File(file.getPath() + ".tmp");
        try {
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)));
            try {
                out.writeInt(MAGIC);
                out.writeInt(people.size());
                for (Map.Entry<String, List<float[]>> e : people.entrySet()) {
                    List<float[]> list = e.getValue();
                    int dim = list.isEmpty() ? 0 : list.get(0).length;
                    // One size per person: a previous model's embeddings never mix with this one's.
                    List<float[]> same = new ArrayList<float[]>();
                    for (Iterator<float[]> it = list.iterator(); it.hasNext(); ) {
                        float[] v = it.next();
                        if (v.length == dim) {
                            same.add(v);
                        }
                    }
                    out.writeUTF(e.getKey());
                    out.writeInt(same.size());
                    out.writeInt(dim);
                    for (float[] v : same) {
                        for (float x : v) {
                            out.writeFloat(x);
                        }
                    }
                }
            } finally {
                out.close();
            }
            if (!tmp.renameTo(file)) {
                throw new IOException("rename failed");
            }
        } catch (IOException e) {
            tmp.delete();
            diag.log("voice: store not saved (" + e.getClass().getSimpleName() + ")");
        }
    }
}
