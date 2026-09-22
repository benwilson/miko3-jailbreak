package com.miko3.mode.voice;

import java.util.HashMap;
import java.util.Map;

/**
 * Host-JVM checks for the speaker's buffer arithmetic (U12), driven by
 * scripts/tests/test_voice_speaker_sizing.py. Lives in the mode's own package
 * so it can reach the package-private classes; SpeakerSizing and VoiceSettings
 * touch no android.*, so the sizing that VoicePlayer hands to AudioTrack runs
 * under plain javac here.
 *
 * What the scenarios pin down is the shape the robot measurements asked for:
 * playback starts on a real prebuffer, the track then holds about a second so
 * a late player-thread wakeup is not an underrun, the queue holds everything
 * the relay is allowed to burst, and a hand-written tuning value lands on a
 * bound instead of anywhere the typist meant.
 *
 * Prints one "PASS <name>" or "FAIL <name>: <detail>" line per scenario; the
 * Python side asserts on each by name.
 */
public final class SpeakerSizingHarness {
    /** The minimum AudioTrack.getMinBufferSize reports on the robot, in frames;
     * small enough that the preferences, not the hardware, decide the sizes. */
    private static final int MIN_FRAMES = 1024;

    /** In-memory stand-in for the SharedPreferences-backed store. */
    static final class MemStore implements VoiceSettings.Store {
        final Map<String, Object> values = new HashMap<String, Object>();

        @Override
        public String getString(String key, String def) {
            Object v = values.get(key);
            return v instanceof String ? (String) v : def;
        }

        @Override
        public boolean getBoolean(String key, boolean def) {
            Object v = values.get(key);
            return v instanceof Boolean ? (Boolean) v : def;
        }

        @Override
        public int getInt(String key, int def) {
            Object v = values.get(key);
            return v instanceof Integer ? (Integer) v : def;
        }

        @Override
        public void putString(String key, String value) {
            values.put(key, value);
        }

        @Override
        public void putBoolean(String key, boolean value) {
            values.put(key, value);
        }
    }

    /** Settings over a store holding just these tuning keys. */
    private static VoiceSettings settings(Object... keysAndValues) {
        MemStore store = new MemStore();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            store.values.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return new VoiceSettings(store);
    }

    private static SpeakerSizing sizing(Object... keysAndValues) {
        return SpeakerSizing.of(settings(keysAndValues), MIN_FRAMES);
    }

    public static void main(String[] args) {
        scenario("defaults", new Scenario() {
            public void run(String n) throws Exception {
                VoiceSettings s = settings();
                SpeakerSizing z = SpeakerSizing.of(s, MIN_FRAMES);
                // 6 chunks of prebuffer (480 ms), a second in the track, 38
                // chunks (3.04 s) of queue: the sizes the robot is tuned to.
                check(n, s.prebufferChunks() == 6 && s.speakerBufferMs() == 1000
                                && s.playerQueueChunks() == 38
                                && z.prebufferMs() == 480 && z.queueChunks() == 38 && z.queueMs() == 3040
                                && z.trackFrames() == SpeakerSizing.msToFrames(1000)
                                && z.trackMs() == 1000
                                && z.startFrames() == 6 * SpeakerSizing.CHUNK_FRAMES,
                        "prebuffer=" + z.prebufferMs() + " queue=" + z.queueMs() + " track=" + z.trackMs()
                                + " start=" + z.startFrames() + " chunkFrames=" + SpeakerSizing.CHUNK_FRAMES);
            }
        });
        scenario("chunk_is_eighty_milliseconds_of_the_speaker_rate", new Scenario() {
            public void run(String n) throws Exception {
                check(n, SpeakerSizing.CHUNK_FRAMES == 1764 && SpeakerSizing.CHUNK_BYTES == 3528
                                && SpeakerSizing.framesToMs(SpeakerSizing.CHUNK_FRAMES) == 80,
                        "frames=" + SpeakerSizing.CHUNK_FRAMES + " bytes=" + SpeakerSizing.CHUNK_BYTES);
            }
        });
        scenario("absent_preferences_fall_back_to_defaults", new Scenario() {
            public void run(String n) throws Exception {
                SpeakerSizing absent = sizing();
                SpeakerSizing spelled = sizing(VoiceSettings.KEY_PREBUFFER_CHUNKS, 6,
                        VoiceSettings.KEY_SPEAKER_BUFFER_MS, 1000,
                        VoiceSettings.KEY_PLAYER_QUEUE_CHUNKS, 38);
                check(n, same(absent, spelled), describe(absent) + " vs " + describe(spelled));
            }
        });
        scenario("zero_preferences_fall_back_to_defaults", new Scenario() {
            public void run(String n) throws Exception {
                // A cleared key reads back as 0; that is "unset", not "no buffer".
                SpeakerSizing zeroed = sizing(VoiceSettings.KEY_PREBUFFER_CHUNKS, 0,
                        VoiceSettings.KEY_SPEAKER_BUFFER_MS, 0,
                        VoiceSettings.KEY_PLAYER_QUEUE_CHUNKS, 0);
                SpeakerSizing negative = sizing(VoiceSettings.KEY_PREBUFFER_CHUNKS, -3,
                        VoiceSettings.KEY_SPEAKER_BUFFER_MS, -1,
                        VoiceSettings.KEY_PLAYER_QUEUE_CHUNKS, -40);
                check(n, same(zeroed, sizing()) && same(negative, sizing()),
                        describe(zeroed) + " / " + describe(negative));
            }
        });
        scenario("prebuffer_clamped_at_its_bounds", new Scenario() {
            public void run(String n) throws Exception {
                VoiceSettings low = settings(VoiceSettings.KEY_PREBUFFER_CHUNKS, 1);
                VoiceSettings high = settings(VoiceSettings.KEY_PREBUFFER_CHUNKS, 4000);
                VoiceSettings edge = settings(VoiceSettings.KEY_PREBUFFER_CHUNKS,
                        VoiceSettings.MAX_PREBUFFER_CHUNKS);
                check(n, low.prebufferChunks() == 1
                                && high.prebufferChunks() == VoiceSettings.MAX_PREBUFFER_CHUNKS
                                && edge.prebufferChunks() == VoiceSettings.MAX_PREBUFFER_CHUNKS,
                        "low=" + low.prebufferChunks() + " high=" + high.prebufferChunks()
                                + " edge=" + edge.prebufferChunks());
            }
        });
        scenario("speaker_buffer_ms_clamped_at_its_bounds", new Scenario() {
            public void run(String n) throws Exception {
                VoiceSettings low = settings(VoiceSettings.KEY_SPEAKER_BUFFER_MS, 5);
                VoiceSettings high = settings(VoiceSettings.KEY_SPEAKER_BUFFER_MS, 600000);
                VoiceSettings inRange = settings(VoiceSettings.KEY_SPEAKER_BUFFER_MS, 2000);
                check(n, low.speakerBufferMs() == VoiceSettings.MIN_SPEAKER_BUFFER_MS
                                && high.speakerBufferMs() == VoiceSettings.MAX_SPEAKER_BUFFER_MS
                                && inRange.speakerBufferMs() == 2000
                                && sizing(VoiceSettings.KEY_SPEAKER_BUFFER_MS, 2000).trackMs() == 2000,
                        "low=" + low.speakerBufferMs() + " high=" + high.speakerBufferMs()
                                + " inRange=" + inRange.speakerBufferMs());
            }
        });
        scenario("player_queue_chunks_clamped_at_its_bounds", new Scenario() {
            public void run(String n) throws Exception {
                VoiceSettings low = settings(VoiceSettings.KEY_PLAYER_QUEUE_CHUNKS, 1);
                VoiceSettings high = settings(VoiceSettings.KEY_PLAYER_QUEUE_CHUNKS, 100000);
                VoiceSettings inRange = settings(VoiceSettings.KEY_PLAYER_QUEUE_CHUNKS, 50);
                check(n, low.playerQueueChunks() == VoiceSettings.MIN_PLAYER_QUEUE_CHUNKS
                                && high.playerQueueChunks() == VoiceSettings.MAX_PLAYER_QUEUE_CHUNKS
                                && inRange.playerQueueChunks() == 50
                                && sizing(VoiceSettings.KEY_PLAYER_QUEUE_CHUNKS, 50).queueChunks() == 50,
                        "low=" + low.playerQueueChunks() + " high=" + high.playerQueueChunks()
                                + " inRange=" + inRange.playerQueueChunks());
            }
        });
        scenario("queue_holds_the_relay_burst", new Scenario() {
            public void run(String n) throws Exception {
                SpeakerSizing z = sizing();
                // The relay banks up to BURST_SECONDS ahead of playback; a queue
                // shorter than that throws away audio it already paid for.
                check(n, z.queueHoldsRelayBurst() && z.queueMs() >= SpeakerSizing.RELAY_BURST_MS
                                && z.queueMs() >= 2500,
                        "queue=" + z.queueMs() + " burst=" + SpeakerSizing.RELAY_BURST_MS);
            }
        });
        scenario("queue_bottom_bound_is_the_old_one_second", new Scenario() {
            public void run(String n) throws Exception {
                // Wound all the way down, the queue is still what shipped before
                // U12, so a typo cannot make it tighter than the known-bad size.
                SpeakerSizing z = sizing(VoiceSettings.KEY_PLAYER_QUEUE_CHUNKS, 1);
                check(n, z.queueChunks() == VoiceSettings.MIN_PLAYER_QUEUE_CHUNKS
                                && z.queueMs() == 1040 && z.queueMs() + z.trackMs() >= SpeakerSizing.RELAY_BURST_MS,
                        "queue=" + z.queueMs() + " track=" + z.trackMs());
            }
        });
        scenario("track_buffer_is_never_below_the_prebuffer", new Scenario() {
            public void run(String n) throws Exception {
                // A big prebuffer against the smallest legal track: the cushion
                // cannot be smaller than what playback starts with, or the track
                // could not even accept the prebuffer.
                SpeakerSizing z = sizing(VoiceSettings.KEY_PREBUFFER_CHUNKS, 25,
                        VoiceSettings.KEY_SPEAKER_BUFFER_MS, 160);
                int prebufferFrames = 25 * SpeakerSizing.CHUNK_FRAMES;
                check(n, z.trackFrames() >= prebufferFrames && z.startFrames() == prebufferFrames
                                && z.startFrames() <= z.trackFrames(),
                        "track=" + z.trackFrames() + " start=" + z.startFrames()
                                + " prebuffer=" + prebufferFrames);
            }
        });
        scenario("start_threshold_is_the_prebuffer_not_the_cushion", new Scenario() {
            public void run(String n) throws Exception {
                // The whole point of the U12 split: a 1 s cushion must not make
                // the first word wait for 1 s of audio.
                SpeakerSizing z = sizing();
                check(n, z.startFrames() == SpeakerSizing.msToFrames(480) && z.trackMs() == 1000
                                && z.startFrames() < z.trackFrames()
                                && SpeakerSizing.framesToMs(z.startFrames()) == 480,
                        "start=" + z.startFrames() + " (" + SpeakerSizing.framesToMs(z.startFrames())
                                + " ms) track=" + z.trackFrames());
            }
        });
        scenario("hardware_minimum_raises_both_sizes", new Scenario() {
            public void run(String n) throws Exception {
                // A device whose smallest track is bigger than everything asked for.
                int min = SpeakerSizing.msToFrames(1500);
                SpeakerSizing z = SpeakerSizing.of(settings(VoiceSettings.KEY_PREBUFFER_CHUNKS, 1,
                        VoiceSettings.KEY_SPEAKER_BUFFER_MS, 200), min);
                check(n, z.trackFrames() >= min && z.startFrames() >= min
                                && z.startFrames() <= z.trackFrames()
                                && z.capacityBytes() >= z.trackFrames() * 2,
                        "min=" + min + " track=" + z.trackFrames() + " start=" + z.startFrames());
            }
        });
        scenario("capacity_leaves_room_above_the_cushion", new Scenario() {
            public void run(String n) throws Exception {
                // setBufferSizeInFrames can only be raised towards the capacity
                // asked of the Builder, and that ask cannot be changed later.
                SpeakerSizing z = sizing();
                check(n, z.capacityBytes() >= z.trackFrames() * 2 + SpeakerSizing.CHUNK_BYTES
                                && z.capacityBytes() >= z.startFrames() * 2,
                        "capacity=" + z.capacityBytes() + " track=" + z.trackFrames() * 2);
            }
        });
        scenario("start_waits_for_the_prebuffer", new Scenario() {
            public void run(String n) throws Exception {
                SpeakerSizing z = sizing();
                check(n, !z.shouldStart(5, 100, 10) && z.shouldStart(6, 100, 10)
                                && z.shouldStart(7, 100, 10),
                        "five=" + z.shouldStart(5, 100, 10) + " six=" + z.shouldStart(6, 100, 10));
            }
        });
        scenario("short_reply_starts_when_the_stream_goes_quiet", new Scenario() {
            public void run(String n) throws Exception {
                // "Yes." is two chunks and no more are coming: it must not wait
                // for a prebuffer that will never arrive.
                SpeakerSizing z = sizing();
                check(n, z.shouldStart(2, 300, SpeakerSizing.QUIET_START_MS)
                                && !z.shouldStart(2, 300, SpeakerSizing.QUIET_START_MS - 1),
                        "quiet=" + SpeakerSizing.QUIET_START_MS);
            }
        });
        scenario("trickling_reply_starts_at_the_wait_cap", new Scenario() {
            public void run(String n) throws Exception {
                // Chunks arriving just often enough to keep resetting the quiet
                // timer still cannot hold the first word past the cap.
                SpeakerSizing z = sizing();
                check(n, z.shouldStart(3, SpeakerSizing.MAX_START_WAIT_MS, 10)
                                && !z.shouldStart(3, SpeakerSizing.MAX_START_WAIT_MS - 1, 10),
                        "cap=" + SpeakerSizing.MAX_START_WAIT_MS);
            }
        });
        scenario("nothing_queued_never_starts", new Scenario() {
            public void run(String n) throws Exception {
                SpeakerSizing z = sizing();
                check(n, !z.shouldStart(0, 5000, 5000) && !z.shouldStart(0, -1, -1),
                        "started on an empty queue");
            }
        });
        scenario("no_arrival_times_yet_waits_for_the_prebuffer", new Scenario() {
            public void run(String n) throws Exception {
                // -1 means "no chunk has arrived": neither timer may fire on it.
                SpeakerSizing z = sizing();
                check(n, !z.shouldStart(1, -1, -1) && z.shouldStart(6, -1, -1),
                        "one=" + z.shouldStart(1, -1, -1) + " six=" + z.shouldStart(6, -1, -1));
            }
        });
        System.out.println(failures == 0 ? "ALL OK" : ("FAILURES " + failures));
        System.exit(failures == 0 ? 0 : 1);
    }

    private static boolean same(SpeakerSizing a, SpeakerSizing b) {
        return a.prebufferChunks() == b.prebufferChunks() && a.queueChunks() == b.queueChunks()
                && a.trackFrames() == b.trackFrames() && a.startFrames() == b.startFrames()
                && a.capacityBytes() == b.capacityBytes();
    }

    private static String describe(SpeakerSizing z) {
        return "prebuffer=" + z.prebufferChunks() + " queue=" + z.queueChunks()
                + " track=" + z.trackFrames() + " start=" + z.startFrames();
    }

    private static int failures;

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            System.out.println("PASS " + name);
        } else {
            failures++;
            System.out.println("FAIL " + name + ": " + detail);
        }
    }

    interface Scenario {
        void run(String name) throws Exception;
    }

    private static void scenario(String name, Scenario s) {
        try {
            s.run(name);
        } catch (Throwable t) {
            failures++;
            System.out.println("FAIL " + name + ": threw " + t);
        }
    }
}
