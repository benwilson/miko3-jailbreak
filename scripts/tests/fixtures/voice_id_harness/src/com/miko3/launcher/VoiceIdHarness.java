package com.miko3.launcher;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Host harness for the launcher's voice identification (owner 2026-10-02): VoiceStore
 * (per-person embeddings, match bands, cap, forget, persistence), VoiceTuning (the
 * thresholds and their properties) and VoiceId (the capped utterance buffer, the single
 * background embedding thread, enrol by utterance time). Plain Java: the sherpa-onnx
 * extractor is replaced by a fake Embedder.
 *
 * Prints one "PASS <name>" or "FAIL <name>: <detail>" line per scenario.
 */
public final class VoiceIdHarness {
    static final int RATE = 16000;
    static final int CHUNK = 1280; // 80 ms

    interface Scenario {
        void run(String name) throws Exception;
    }

    static int failures;

    static void scenario(String name, Scenario s) {
        try {
            s.run(name);
        } catch (Throwable t) {
            fail(name, "threw " + t);
        }
    }

    static void check(String name, boolean ok, String detail) {
        if (ok) {
            System.out.println("PASS " + name);
        } else {
            fail(name, detail);
        }
    }

    static void fail(String name, String detail) {
        failures++;
        System.out.println("FAIL " + name + ": " + detail);
    }

    static final class Lines implements VoiceStore.Diag {
        final List<String> lines = Collections.synchronizedList(new ArrayList<String>());

        @Override
        public void log(String line) {
            lines.add(line);
        }

        String all() {
            synchronized (lines) {
                return String.join(" | ", lines);
            }
        }

        boolean has(String part) {
            synchronized (lines) {
                for (String l : lines) {
                    if (l.contains(part)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    /** A unit vector along axis i of dim d, tilted toward axis j by t (cosine to axis i falls as t grows). */
    static float[] vec(int d, int i, int j, float t) {
        float[] v = new float[d];
        v[i] = 1f;
        v[j] += t;
        return v;
    }

    static float[] axis(int d, int i) {
        return vec(d, i, i, 0f);
    }

    static File tempFile() throws Exception {
        File dir = Files.createTempDirectory("voice_id_harness").toFile();
        dir.deleteOnExit();
        return new File(dir, "voiceprints.bin");
    }

    static VoiceTuning tuning() {
        return new VoiceTuning(0.65f, 0.45f);
    }

    /** Embeds by returning a fixed vector, recording the thread and the samples it was given. */
    static final class FakeEmbedder implements VoiceId.Embedder {
        volatile float[] out = axis(8, 0);
        volatile String thread;
        volatile int priority;
        volatile int samples = -1;
        volatile float firstSample;
        volatile float lastSample;
        final List<Integer> calls = Collections.synchronizedList(new ArrayList<Integer>());
        volatile CountDownLatch gate;
        final CountDownLatch done = new CountDownLatch(1);

        @Override
        public float[] embed(float[] s, int n) throws Exception {
            thread = Thread.currentThread().getName();
            priority = Thread.currentThread().getPriority();
            samples = n;
            firstSample = n > 0 ? s[0] : Float.NaN;
            lastSample = n > 0 ? s[n - 1] : Float.NaN;
            calls.add(n);
            CountDownLatch g = gate;
            if (g != null) {
                g.await(5, TimeUnit.SECONDS);
            }
            done.countDown();
            return out;
        }
    }

    static final class Heard implements VoiceId.Listener {
        final List<String> results = Collections.synchronizedList(new ArrayList<String>());
        final CountDownLatch one = new CountDownLatch(1);

        @Override
        public void voice(long at, String person, float score, int band) {
            results.add(at + ":" + person + ":" + band + ":" + Math.round(score * 100));
            one.countDown();
        }
    }

    /** Feeds ms of audio in 80 ms chunks, each sample its chunk's index + 1 (scaled), marking speech when asked. */
    static void feed(VoiceId v, long ms, boolean speech, int[] counter) {
        float[] buf = new float[CHUNK];
        for (long t = 0; t < ms; t += 80) {
            counter[0]++;
            java.util.Arrays.fill(buf, counter[0] / 10000f);
            v.append(buf, CHUNK);
            if (speech) {
                v.speech();
            }
        }
    }

    /** Feeds ms of speech in 80 ms chunks, every sample at level. */
    static void feedLevel(VoiceId v, long ms, float level) {
        float[] buf = new float[CHUNK];
        java.util.Arrays.fill(buf, level);
        for (long t = 0; t < ms; t += 80) {
            v.append(buf, CHUNK);
            v.speech();
        }
    }

    public static void main(String[] args) throws Exception {
        // ---- VoiceTuning ----
        scenario("tuning_defaults_and_bands", new Scenario() {
            public void run(String n) {
                VoiceTuning t = VoiceTuning.from(new VoiceTuning.Props() {
                    public String get(String key) {
                        return null;
                    }
                });
                check(n, t.strong == 0.65f && t.weak == 0.45f && t.band(0.7f) == VoiceStore.BAND_STRONG
                                && t.band(0.65f) == VoiceStore.BAND_STRONG && t.band(0.5f) == VoiceStore.BAND_WEAK
                                && t.band(0.45f) == VoiceStore.BAND_WEAK && t.band(0.44f) == VoiceStore.BAND_NONE
                                && VoiceTuning.MIN_SPEECH_MS == 1500 && VoiceTuning.MAX_BUFFER_MS == 8000
                                && VoiceTuning.MAX_PER_PERSON == 10,
                        "t=" + t);
            }
        });
        scenario("tuning_properties_override_and_bad_values_fall_back", new Scenario() {
            public void run(String n) {
                final java.util.Map<String, String> p = new java.util.HashMap<String, String>();
                VoiceTuning.Props props = new VoiceTuning.Props() {
                    public String get(String key) {
                        return p.get(key);
                    }
                };
                p.put(VoiceTuning.STRONG_PROP, "0.7");
                p.put(VoiceTuning.WEAK_PROP, "0.5");
                VoiceTuning a = VoiceTuning.from(props);
                p.put(VoiceTuning.STRONG_PROP, "banana");
                p.put(VoiceTuning.WEAK_PROP, "1.5");
                VoiceTuning b = VoiceTuning.from(props);
                p.put(VoiceTuning.STRONG_PROP, "0.4");
                p.put(VoiceTuning.WEAK_PROP, "0.6"); // weak above strong: both fall back
                VoiceTuning c = VoiceTuning.from(props);
                check(n, a.strong == 0.7f && a.weak == 0.5f && b.strong == 0.65f && b.weak == 0.45f
                        && c.strong == 0.65f && c.weak == 0.45f, "a=" + a + " b=" + b + " c=" + c);
            }
        });

        // ---- VoiceStore ----
        scenario("store_empty_matches_nobody", new Scenario() {
            public void run(String n) throws Exception {
                VoiceStore s = new VoiceStore(tempFile(), 10, new Lines());
                VoiceStore.Match m = s.match(axis(8, 0), tuning());
                check(n, m.id == null && m.band == VoiceStore.BAND_NONE && s.people() == 0, "m=" + m);
            }
        });
        scenario("store_bands_follow_the_thresholds", new Scenario() {
            public void run(String n) throws Exception {
                VoiceStore s = new VoiceStore(tempFile(), 10, new Lines());
                s.add("p1", axis(8, 0));
                // cos = 1/sqrt(1+t^2): t=0.5 -> 0.894 strong, t=1.2 -> 0.640 weak, t=3 -> 0.316 none
                VoiceStore.Match strong = s.match(vec(8, 0, 1, 0.5f), tuning());
                VoiceStore.Match weak = s.match(vec(8, 0, 1, 1.2f), tuning());
                VoiceStore.Match none = s.match(vec(8, 0, 1, 3f), tuning());
                check(n, "p1".equals(strong.id) && strong.band == VoiceStore.BAND_STRONG
                                && Math.abs(strong.score - 0.894f) < 0.01f
                                && "p1".equals(weak.id) && weak.band == VoiceStore.BAND_WEAK
                                && none.id == null && none.band == VoiceStore.BAND_NONE
                                && Math.abs(none.score - 0.316f) < 0.01f,
                        "strong=" + strong + " weak=" + weak + " none=" + none);
            }
        });
        scenario("store_picks_the_closest_person", new Scenario() {
            public void run(String n) throws Exception {
                VoiceStore s = new VoiceStore(tempFile(), 10, new Lines());
                s.add("p1", axis(8, 0));
                s.add("p2", axis(8, 1));
                s.add("p2", vec(8, 1, 2, 0.2f));
                VoiceStore.Match m = s.match(vec(8, 1, 0, 0.1f), tuning());
                check(n, "p2".equals(m.id) && m.band == VoiceStore.BAND_STRONG && s.people() == 2
                        && s.count("p2") == 2 && s.count("p1") == 1, "m=" + m);
            }
        });
        scenario("store_near_tie_is_only_weak", new Scenario() {
            public void run(String n) throws Exception {
                VoiceStore s = new VoiceStore(tempFile(), 10, new Lines());
                s.add("p1", axis(8, 0));
                s.add("p2", axis(8, 1));
                // Equally close to both (cos 0.707 each): a close call, never strong.
                VoiceStore.Match m = s.match(vec(8, 0, 1, 1f), tuning());
                check(n, m.band == VoiceStore.BAND_WEAK && m.id != null, "m=" + m);
            }
        });
        scenario("store_caps_each_person_keeping_the_newest", new Scenario() {
            public void run(String n) throws Exception {
                VoiceStore s = new VoiceStore(tempFile(), 10, new Lines());
                for (int i = 0; i < 12; i++) {
                    s.add("p1", axis(16, i));
                }
                // Axes 0 and 1 were dropped: a probe on axis 0 no longer resembles the centroid at all.
                VoiceStore.Match m0 = s.match(axis(16, 0), tuning());
                VoiceStore.Match m11 = s.match(axis(16, 11), tuning());
                check(n, s.count("p1") == 10 && m0.score < 0.01f && m11.score > 0.3f, "count=" + s.count("p1")
                        + " m0=" + m0 + " m11=" + m11);
            }
        });
        scenario("store_forget_removes_a_person_and_persists", new Scenario() {
            public void run(String n) throws Exception {
                File f = tempFile();
                VoiceStore s = new VoiceStore(f, 10, new Lines());
                s.add("p1", axis(8, 0));
                s.add("p2", axis(8, 1));
                boolean forgot = s.forget("p1");
                boolean again = s.forget("p1");
                VoiceStore reloaded = new VoiceStore(f, 10, new Lines());
                VoiceStore.Match m = reloaded.match(axis(8, 0), tuning());
                check(n, forgot && !again && s.people() == 1 && reloaded.people() == 1 && reloaded.count("p1") == 0
                        && m.id == null, "people=" + s.people() + " reloaded=" + reloaded.people() + " m=" + m);
            }
        });
        scenario("store_persists_and_reloads_embeddings", new Scenario() {
            public void run(String n) throws Exception {
                File f = tempFile();
                VoiceStore s = new VoiceStore(f, 10, new Lines());
                s.add("person-7", vec(8, 2, 3, 0.3f));
                s.add("person-7", axis(8, 2));
                VoiceStore r = new VoiceStore(f, 10, new Lines());
                VoiceStore.Match m = r.match(axis(8, 2), tuning());
                check(n, f.isFile() && r.count("person-7") == 2 && "person-7".equals(m.id)
                        && m.band == VoiceStore.BAND_STRONG && !new File(f.getPath() + ".tmp").exists(), "m=" + m);
            }
        });
        scenario("store_unreadable_file_starts_empty", new Scenario() {
            public void run(String n) throws Exception {
                File f = tempFile();
                FileOutputStream out = new FileOutputStream(f);
                out.write(new byte[] {1, 2, 3, 4, 5, 6, 7});
                out.close();
                Lines lines = new Lines();
                VoiceStore s = new VoiceStore(f, 10, lines);
                int before = s.people();
                s.add("p1", axis(8, 0));
                VoiceStore r = new VoiceStore(f, 10, new Lines());
                check(n, before == 0 && r.count("p1") == 1 && lines.has("unreadable"), "lines=" + lines.all());
            }
        });
        scenario("store_ignores_embeddings_of_another_size", new Scenario() {
            public void run(String n) throws Exception {
                VoiceStore s = new VoiceStore(tempFile(), 10, new Lines());
                s.add("old", axis(4, 0)); // a previous model's size
                s.add("p1", axis(8, 0));
                VoiceStore.Match m = s.match(axis(8, 0), tuning());
                boolean refused = !s.add("bad", new float[0]) && !s.add(null, axis(8, 0)) && !s.add("p1", null);
                check(n, "p1".equals(m.id) && refused, "m=" + m);
            }
        });
        scenario("store_file_holds_no_ids_in_its_logs", new Scenario() {
            public void run(String n) throws Exception {
                Lines lines = new Lines();
                VoiceStore s = new VoiceStore(tempFile(), 10, lines);
                s.add("secret-id-42", axis(8, 0));
                s.match(axis(8, 0), tuning());
                s.forget("secret-id-42");
                check(n, !lines.all().contains("secret-id-42"), "lines=" + lines.all());
            }
        });

        // ---- VoiceId: the utterance buffer ----
        scenario("buffer_caps_at_eight_seconds_and_embeds_its_loudest_three", new Scenario() {
            // Robot 2026-10-03: the buffer keeps the utterance's first 8 s; only the 3 s with the
            // most energy among them is embedded (here the levels rise, so its last 3 s).
            public void run(String n) throws Exception {
                FakeEmbedder e = new FakeEmbedder();
                Lines lines = new Lines();
                VoiceId v = new VoiceId(new VoiceStore(tempFile(), 10, new Lines()), tuning(), lines);
                v.setEmbedder(e);
                v.start();
                int[] c = {0};
                feed(v, 12000, true, c);
                boolean sent = v.answered(5000, true);
                e.done.await(5, TimeUnit.SECONDS);
                v.shutdown();
                v.awaitIdle(2000);
                check(n, sent && e.samples == 3 * RATE && Math.abs(e.firstSample - 63 / 10000f) < 1e-6
                                && Math.abs(e.lastSample - 100 / 10000f) < 1e-6 && v.buffered() == 0
                                && lines.has("(dur 3000 ms of 8000 ms)"),
                        "samples=" + e.samples + " first=" + e.firstSample + " last=" + e.lastSample + " lines="
                                + lines.all());
            }
        });
        scenario("clip_is_the_three_seconds_with_the_most_speech_energy", new Scenario() {
            public void run(String n) throws Exception {
                FakeEmbedder e = new FakeEmbedder();
                VoiceId v = new VoiceId(new VoiceStore(tempFile(), 10, new Lines()), tuning(), new Lines());
                v.setEmbedder(e);
                v.start();
                feedLevel(v, 1600, 0.05f);
                feedLevel(v, 3200, 0.5f);
                feedLevel(v, 2000, 0.05f);
                v.answered(5000, true);
                e.done.await(5, TimeUnit.SECONDS);
                v.shutdown();
                v.awaitIdle(2000);
                check(n, e.samples == 3 * RATE && e.firstSample == 0.5f && e.lastSample == 0.5f,
                        "samples=" + e.samples + " first=" + e.firstSample + " last=" + e.lastSample);
            }
        });
        scenario("loudest_start_is_the_start_for_short_or_even_clips_and_follows_the_energy", new Scenario() {
            public void run(String n) {
                int want = 3 * RATE;
                float[] flat = new float[5 * RATE];
                java.util.Arrays.fill(flat, 0.2f);
                float[] late = new float[5 * RATE];
                java.util.Arrays.fill(late, 0.01f);
                java.util.Arrays.fill(late, 4 * RATE, 5 * RATE, 0.9f);
                int shortStart = VoiceId.loudestStart(new float[2 * RATE], 2 * RATE, want);
                int flatStart = VoiceId.loudestStart(flat, flat.length, want);
                int silentStart = VoiceId.loudestStart(new float[5 * RATE], 5 * RATE, want);
                int lateStart = VoiceId.loudestStart(late, late.length, want);
                check(n, shortStart == 0 && flatStart == 0 && silentStart == 0 && lateStart == 2 * RATE,
                        "short=" + shortStart + " flat=" + flatStart + " silent=" + silentStart + " late=" + lateStart);
            }
        });
        scenario("a_call_needs_only_1_2_seconds_of_speech", new Scenario() {
            // Robot 2026-10-03: "Hey Miko ..." is the conversation's first voice reference.
            public void run(String n) throws Exception {
                FakeEmbedder e = new FakeEmbedder();
                VoiceId v = new VoiceId(new VoiceStore(tempFile(), 10, new Lines()), tuning(), new Lines());
                v.setEmbedder(e);
                int[] c = {0};
                v.start();
                feed(v, 1360, true, c);
                boolean asAnswer = v.answered(1, true);
                v.start();
                feed(v, 1040, true, c);
                boolean tooShort = v.called(2, true);
                v.start();
                feed(v, 1360, true, c);
                boolean clipped = v.called(3, false);
                v.start();
                feed(v, 1360, true, c);
                boolean call = v.called(4, true);
                e.done.await(5, TimeUnit.SECONDS);
                v.shutdown();
                v.awaitIdle(2000);
                check(n, !asAnswer && !tooShort && !clipped && call && e.calls.size() == 1
                                && e.calls.get(0) == 17 * CHUNK && v.buffered() == 0,
                        "asAnswer=" + asAnswer + " tooShort=" + tooShort + " clipped=" + clipped + " call=" + call
                                + " calls=" + e.calls);
            }
        });
        scenario("buffer_trims_the_silence_after_the_last_speech", new Scenario() {
            public void run(String n) throws Exception {
                FakeEmbedder e = new FakeEmbedder();
                VoiceId v = new VoiceId(new VoiceStore(tempFile(), 10, new Lines()), tuning(), new Lines());
                v.setEmbedder(e);
                v.start();
                int[] c = {0};
                feed(v, 2400, true, c);   // 30 chunks of speech
                feed(v, 2000, false, c);  // the answer's 2 s of trailing silence
                v.answered(5000, true);
                e.done.await(5, TimeUnit.SECONDS);
                v.shutdown();
                check(n, e.samples == 30 * CHUNK && Math.abs(e.lastSample - 30 / 10000f) < 1e-6,
                        "samples=" + e.samples + " last=" + e.lastSample);
            }
        });
        scenario("buffer_short_speech_is_not_embedded_and_is_cleared", new Scenario() {
            public void run(String n) throws Exception {
                FakeEmbedder e = new FakeEmbedder();
                Lines lines = new Lines();
                VoiceId v = new VoiceId(new VoiceStore(tempFile(), 10, new Lines()), tuning(), lines);
                v.setEmbedder(e);
                v.start();
                int[] c = {0};
                feed(v, 1200, true, c);
                feed(v, 2000, false, c);
                boolean sent = v.answered(5000, true);
                v.shutdown();
                check(n, !sent && e.calls.isEmpty() && v.buffered() == 0, "sent=" + sent + " lines=" + lines.all());
            }
        });
        scenario("buffer_unclean_answer_is_not_embedded_and_is_cleared", new Scenario() {
            public void run(String n) throws Exception {
                FakeEmbedder e = new FakeEmbedder();
                VoiceId v = new VoiceId(new VoiceStore(tempFile(), 10, new Lines()), tuning(), new Lines());
                v.setEmbedder(e);
                v.start();
                int[] c = {0};
                feed(v, 3000, true, c);
                boolean sent = v.answered(5000, false);
                v.shutdown();
                check(n, !sent && e.calls.isEmpty() && v.buffered() == 0, "sent=" + sent);
            }
        });
        scenario("buffer_start_and_reset_drop_the_previous_utterance", new Scenario() {
            public void run(String n) throws Exception {
                FakeEmbedder e = new FakeEmbedder();
                VoiceId v = new VoiceId(new VoiceStore(tempFile(), 10, new Lines()), tuning(), new Lines());
                v.setEmbedder(e);
                int[] c = {0};
                v.start();
                feed(v, 3000, true, c);
                v.reset();
                int afterReset = v.buffered();
                feed(v, 800, true, c); // appended with no start: still collected, from empty
                v.start();
                int afterStart = v.buffered();
                v.shutdown();
                check(n, afterReset == 0 && afterStart == 0, "afterReset=" + afterReset + " afterStart=" + afterStart);
            }
        });
        scenario("no_embedder_yet_skips_quietly", new Scenario() {
            public void run(String n) throws Exception {
                VoiceId v = new VoiceId(new VoiceStore(tempFile(), 10, new Lines()), tuning(), new Lines());
                v.start();
                int[] c = {0};
                feed(v, 3000, true, c);
                boolean sent = v.answered(5000, true);
                v.shutdown();
                check(n, !sent && v.buffered() == 0, "sent=" + sent);
            }
        });

        // ---- VoiceId: the background thread ----
        scenario("embedding_runs_on_one_normal_priority_background_thread", new Scenario() {
            public void run(String n) throws Exception {
                FakeEmbedder e = new FakeEmbedder();
                e.gate = new CountDownLatch(1);
                Heard h = new Heard();
                VoiceId v = new VoiceId(new VoiceStore(tempFile(), 10, new Lines()), tuning(), new Lines());
                v.setEmbedder(e);
                v.setListener(h);
                v.start();
                int[] c = {0};
                feed(v, 2000, true, c);
                long t0 = System.nanoTime();
                boolean sent = v.answered(4242, true);
                long tookMs = (System.nanoTime() - t0) / 1000000;
                // The embedder is still blocked: answered() returned without waiting for it.
                boolean returnedFirst = h.results.isEmpty();
                e.gate.countDown();
                h.one.await(5, TimeUnit.SECONDS);
                v.shutdown();
                check(n, sent && returnedFirst && tookMs < 200 && "voice-id".equals(e.thread)
                                && e.priority == Thread.NORM_PRIORITY && h.results.size() == 1
                                && h.results.get(0).startsWith("4242:null:" + VoiceStore.BAND_NONE),
                        "sent=" + sent + " returnedFirst=" + returnedFirst + " took=" + tookMs + " thread=" + e.thread
                                + " prio=" + e.priority + " results=" + h.results);
            }
        });
        scenario("a_known_voice_is_reported_with_its_band", new Scenario() {
            public void run(String n) throws Exception {
                FakeEmbedder e = new FakeEmbedder();
                e.out = vec(8, 0, 1, 0.5f);
                Heard h = new Heard();
                Lines lines = new Lines();
                VoiceStore store = new VoiceStore(tempFile(), 10, new Lines());
                store.add("pid-9", axis(8, 0));
                VoiceId v = new VoiceId(store, tuning(), lines);
                v.setEmbedder(e);
                v.setListener(h);
                v.start();
                int[] c = {0};
                feed(v, 2000, true, c);
                v.answered(77, true);
                h.one.await(5, TimeUnit.SECONDS);
                v.shutdown();
                check(n, h.results.size() == 1 && h.results.get(0).equals("77:pid-9:" + VoiceStore.BAND_STRONG + ":89")
                                && lines.has("voice: embedding in ") && lines.has("(dur 2000 ms of 2000 ms)")
                                && lines.has("voice: match band=strong score=0.89") && !lines.all().contains("pid-9"),
                        "results=" + h.results + " lines=" + lines.all());
            }
        });
        scenario("enrol_by_utterance_time_then_match_then_forget", new Scenario() {
            public void run(String n) throws Exception {
                FakeEmbedder e = new FakeEmbedder();
                e.out = axis(8, 3);
                Heard h = new Heard();
                Lines lines = new Lines();
                VoiceStore store = new VoiceStore(tempFile(), 10, new Lines());
                VoiceId v = new VoiceId(store, tuning(), lines);
                v.setEmbedder(e);
                v.setListener(h);
                v.start();
                int[] c = {0};
                feed(v, 2000, true, c);
                v.answered(1234, true);
                h.one.await(5, TimeUnit.SECONDS);
                v.shutdown();
                VoicePrints prints = v;
                boolean unknownAt = prints.lastEmbeddingFor(999) == null;
                boolean enrolled = prints.enrolVoice("pid-3", 1234);
                boolean noEmbedding = !prints.enrolVoice("pid-3", 999);
                VoiceStore.Match m = store.match(axis(8, 3), tuning());
                int count = prints.voiceCount("pid-3");
                boolean forgot = prints.forgetVoice("pid-3");
                check(n, unknownAt && enrolled && noEmbedding && "pid-3".equals(m.id) && count == 1 && forgot
                                && prints.voiceCount("pid-3") == 0 && !lines.all().contains("pid-3"),
                        "enrolled=" + enrolled + " m=" + m + " count=" + count + " lines=" + lines.all());
            }
        });
        scenario("score_an_answer_against_a_person_or_another_answer", new Scenario() {
            public void run(String n) throws Exception {
                // Owner 2026-10-02: the mode asks how close an answer is to a person's prints (a name
                // given) or to another answer of the same conversation (the voice gate).
                VoiceStore store = new VoiceStore(tempFile(), 10, new Lines());
                VoiceId v = new VoiceId(store, tuning(), new Lines());
                v.remember(1, axis(8, 3));
                v.remember(2, axis(8, 3));
                v.remember(3, axis(8, 5));
                VoicePrints prints = v;
                boolean enrolled = prints.enrolVoice("pid-3", 1);
                float same = prints.voiceScore("pid-3", 2);
                float other = prints.voiceScore("pid-3", 3);
                float nobody = prints.voiceScore("pid-9", 2);
                float noAnswer = prints.voiceScore("pid-3", 999);
                float alike = prints.voiceSimilarity(1, 2);
                float apart = prints.voiceSimilarity(1, 3);
                float missing = prints.voiceSimilarity(1, 999);
                check(n, enrolled && Math.abs(same - 1f) < 1e-4 && Math.abs(other) < 1e-4 && Float.isNaN(nobody)
                                && Float.isNaN(noAnswer) && Math.abs(alike - 1f) < 1e-4 && Math.abs(apart) < 1e-4
                                && Float.isNaN(missing) && Float.isNaN(prints.voiceScore(null, 2)),
                        "same=" + same + " other=" + other + " nobody=" + nobody + " alike=" + alike + " apart=" + apart);
            }
        });
        scenario("only_the_most_recent_embeddings_are_kept_for_enrolment", new Scenario() {
            public void run(String n) throws Exception {
                VoiceId v = new VoiceId(new VoiceStore(tempFile(), 10, new Lines()), tuning(), new Lines());
                for (long at = 1; at <= VoiceTuning.RECENT + 3; at++) {
                    v.remember(at, axis(8, 0));
                }
                check(n, v.lastEmbeddingFor(1) == null && v.lastEmbeddingFor(3) == null
                        && v.lastEmbeddingFor(4) != null && v.lastEmbeddingFor(VoiceTuning.RECENT + 3) != null,
                        "kept 1=" + (v.lastEmbeddingFor(1) != null));
            }
        });
        scenario("a_failing_embedder_is_logged_by_kind_and_reports_nothing", new Scenario() {
            public void run(String n) throws Exception {
                Heard h = new Heard();
                Lines lines = new Lines();
                VoiceId v = new VoiceId(new VoiceStore(tempFile(), 10, new Lines()), tuning(), lines);
                v.setEmbedder(new VoiceId.Embedder() {
                    public float[] embed(float[] s, int k) {
                        throw new IllegalStateException("boom");
                    }
                });
                v.setListener(h);
                v.start();
                int[] c = {0};
                feed(v, 2000, true, c);
                v.answered(5, true);
                v.shutdown();
                v.awaitIdle(5000);
                check(n, h.results.isEmpty() && lines.has("voice: embedding failed: IllegalStateException"),
                        "lines=" + lines.all());
            }
        });

        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILED");
        System.exit(0);
    }
}
