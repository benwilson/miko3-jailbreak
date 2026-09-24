package com.miko3.mode.explore;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.util.Log;

import com.miko3.shared.ClaudeAccess;
import com.miko3.shared.ClaudeApi;
import com.miko3.shared.ClaudeHttpsTransport;
import com.miko3.shared.NameExtractor;
import com.miko3.shared.RobotListenClient;
import com.miko3.shared.RobotPeople;
import com.miko3.shared.RobotPeopleClient;
import com.miko3.shared.RobotSettingsClient;
import com.miko3.shared.RobotSpeechClient;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The real CuriosityPort (explore on Claude plan U6): Claude through
 * ClaudeApi with the endpoint, key and model from RobotSettingsClient, the
 * launcher's voice through RobotSpeechClient, its ears through
 * RobotListenClient, and its people store through RobotPeopleClient.
 *
 * Every request runs on a worker thread and leaves its answer in a Slot the
 * brain polls from its own thread (CuriosityPort's contract): starting a
 * request of a kind abandons the one before it, whose late answer is dropped.
 * Threads are pooled, not one queue, so an abandoned request still waiting
 * out its read timeout can't hold up the retry behind it.
 *
 * The settings are fetched again for every request, and canAsk() (asked once
 * per stop) refreshes them in the background: a key entered or cleared on the
 * Settings page applies from the next stop, and with none set up every stop
 * runs as it did before Claude.
 *
 * Privacy (R13): face crops and frames leave the robot only inside requests to
 * the configured endpoint. Nothing here logs an image, the key, a reply's
 * text, a transcript or a name: only statuses, fixed reasons, counts and ids.
 */
final class ClaudeCuriosity implements CuriosityPort {
    private static final String TAG = "ExploreClaude";
    /** Assumed when a frame's size can't be read: the camera's own (ExploreCamera). */
    private static final int FRAME_W = 640;
    private static final int FRAME_H = 480;

    private final Context app;
    private final ClaudeApi claude = new ClaudeApi(new ClaudeHttpsTransport());
    private final RobotSpeechClient speech;
    private final RobotListenClient ears;
    private final FaceCrop cropper = new FaceCropper();
    private final ExecutorService worker = Executors.newCachedThreadPool(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "explore-claude");
            t.setDaemon(true);
            return t;
        }
    });
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private volatile ClaudeAccess access;
    private volatile boolean released;

    private final Slot<Answer> looks = new Slot<Answer>();
    private final Slot<Boolean> says = new Slot<Boolean>();
    private final Slot<MatchAnswer> matches = new Slot<MatchAnswer>();
    private final Slot<MatchAnswer> strangerLines = new Slot<MatchAnswer>();
    private final Slot<Heard> hearings = new Slot<Heard>();
    private final Slot<Named> names = new Slot<Named>();
    private final Slot<Answer> remembers = new Slot<Answer>();

    /** The face cut out by the last match(), for remember(); and the id it matched, for touch(). */
    private volatile byte[] meetFace;
    private volatile String matchedId;

    ClaudeCuriosity(Context context) {
        app = context.getApplicationContext();
        speech = new RobotSpeechClient(app);
        ears = new RobotListenClient(app);
        refreshSettings();
    }

    /** Drops any line still queued and stops answering; ModeApp calls it as Explore stops. */
    void release() {
        released = true;
        speech.cancel();
        worker.shutdownNow();
    }

    // ---- settings ----

    @Override
    public boolean canAsk() {
        refreshSettings();
        ClaudeAccess a = access;
        return !released && a != null && a.isSetUp();
    }

    /** Fetch the settings in the background (one fetch at a time); canAsk() reads the last answer. */
    private void refreshSettings() {
        if (released || !refreshing.compareAndSet(false, true)) {
            return;
        }
        try {
            worker.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        fetchSettings();
                    } finally {
                        refreshing.set(false);
                    }
                }
            });
        } catch (RuntimeException e) {
            refreshing.set(false);
        }
    }

    /** Blocking: the launcher's current settings, or not set up when it can't be reached. */
    private ClaudeAccess fetchSettings() {
        ClaudeAccess a;
        try {
            a = RobotSettingsClient.fetch(app);
        } catch (IOException e) {
            Log.w(TAG, "settings unavailable: " + e.getMessage());
            a = ClaudeAccess.notSetUp();
        }
        access = a;
        return a;
    }

    // ---- the look request (KTD2) ----

    @Override
    public void ask(final LookRequest request, final long timeoutMs) {
        final int g = looks.start();
        run(new Runnable() {
            @Override
            public void run() {
                looks.finish(g, look(request, timeoutMs));
            }
        }, looks, g, Answer.failed());
    }

    @Override
    public Answer answer() {
        return looks.poll();
    }

    @Override
    public void cancelAsk() {
        looks.cancel();
    }

    private Answer look(LookRequest request, long timeoutMs) {
        long t0 = System.currentTimeMillis();
        int n = request.frames.size();
        int[] w = new int[n];
        int[] h = new int[n];
        List<Map<String, Object>> content = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < n; i++) {
            int[] size = jpegSize(request.frames.get(i).jpeg);
            w[i] = size[0];
            h[i] = size[1];
        }
        content.add(ClaudeApi.textBlock(ExplorePrompts.lookIntro(n, w[0], h[0])));
        for (int i = 0; i < n; i++) {
            content.add(ClaudeApi.textBlock("Frame " + (i + 1) + ":"));
            content.add(ClaudeApi.jpegBlock(request.frames.get(i).jpeg));
        }
        content.add(ClaudeApi.textBlock(ExplorePrompts.lookAsk(request)));
        ClaudeApi.MessageResult r = claude.messages(fetchSettings(), ExplorePrompts.SYSTEM, content,
                ExplorePrompts.LOOK_SCHEMA, (int) timeoutMs);
        Answer a = r.ok() ? ClaudeReplies.look(r.json, w, h) : Answer.failed();
        String outcome = r.ok() ? a.status.toString() : r.describe();
        Log.i(TAG, "look request with " + n + " frames: " + outcome + " in " + (System.currentTimeMillis() - t0)
                + " ms");
        return a;
    }

    // ---- speaking (KTD8) ----

    @Override
    public void say(String line) {
        final int g = says.start();
        speech.speak(line, new RobotSpeechClient.Listener() {
            @Override
            public void onFinished() {
                says.finish(g, Boolean.TRUE);
            }

            @Override
            public void onCancelled() {
                says.finish(g, Boolean.TRUE);
            }

            @Override
            public void onFailed(String reason) {
                Log.w(TAG, "speaking failed: " + reason);
                says.finish(g, Boolean.TRUE);
            }
        });
    }

    @Override
    public boolean sayFinished() {
        return says.poll() != null;
    }

    // ---- people (U5; KTD3, KTD4) ----

    @Override
    public void match(final byte[] frameJpeg, final Detection personBox, final long timeoutMs) {
        final int g = matches.start();
        meetFace = null;
        matchedId = null;
        run(new Runnable() {
            @Override
            public void run() {
                matches.finish(g, person(g, frameJpeg, personBox, timeoutMs));
            }
        }, matches, g, MatchAnswer.FAILED);
    }

    @Override
    public MatchAnswer matchAnswer() {
        return matches.poll();
    }

    /** The person request: the new face and up to MAX_RECENT stored ones, labelled by number only. */
    private MatchAnswer person(int g, byte[] frameJpeg, Detection personBox, long timeoutMs) {
        long t0 = System.currentTimeMillis();
        byte[] face = cropper.crop(frameJpeg, personBox);
        if (face == null) {
            Log.w(TAG, "person request: no face crop");
            return MatchAnswer.FAILED;
        }
        RobotPeople.Face[] gallery;
        try {
            gallery = RobotPeopleClient.recent(app, RobotPeople.MAX_RECENT);
        } catch (IOException e) {
            Log.w(TAG, "person request: people store unavailable: " + e.getMessage());
            return MatchAnswer.FAILED;
        }
        if (!matches.current(g)) {
            return MatchAnswer.FAILED;
        }
        meetFace = face;
        int n = gallery == null ? 0 : gallery.length;
        List<Map<String, Object>> content = new ArrayList<Map<String, Object>>();
        content.add(ClaudeApi.textBlock(ExplorePrompts.matchIntro(n)));
        content.add(ClaudeApi.textBlock("Query:"));
        content.add(ClaudeApi.jpegBlock(face));
        for (int i = 0; i < n; i++) {
            content.add(ClaudeApi.textBlock("Reference " + (i + 1) + ":"));
            content.add(ClaudeApi.jpegBlock(gallery[i].jpeg));
        }
        content.add(ClaudeApi.textBlock(ExplorePrompts.matchAsk(n)));
        ClaudeApi.MessageResult r = claude.messages(fetchSettings(), ExplorePrompts.SYSTEM, content,
                ExplorePrompts.MATCH_SCHEMA, (int) timeoutMs);
        long ms = System.currentTimeMillis() - t0;
        if (!r.ok()) {
            Log.w(TAG, "person request against " + n + " references: " + r.describe() + " in " + ms + " ms");
            return MatchAnswer.FAILED;
        }
        ClaudeReplies.Match m = ClaudeReplies.match(r.json, n);
        if (m == null) {
            Log.w(TAG, "person request against " + n + " references: unusable reply in " + ms + " ms");
            return MatchAnswer.FAILED;
        }
        if (m.reference >= 0) {
            String id = gallery[m.reference].id;
            String stored;
            try {
                stored = RobotPeopleClient.nameOf(app, id);
            } catch (IOException e) {
                stored = null;
            }
            if (stored != null) {
                matchedId = id;
                Log.i(TAG, "person request against " + n + " references: known, reference " + (m.reference + 1)
                        + ", id " + id + " in " + ms + " ms");
                return MatchAnswer.known(stored.isEmpty() ? null : stored, m.namedLine, m.unnamedLine);
            }
            // Forgotten since recent() (or the store is gone): a new person, if the lines allow.
        }
        Log.i(TAG, "person request against " + n + " references: new in " + ms + " ms");
        return m.askLine == null ? MatchAnswer.FAILED : MatchAnswer.stranger(m.askLine, m.noReplyLine);
    }

    @Override
    public void touch() {
        final String id = matchedId;
        if (id == null) {
            return;
        }
        run(new Runnable() {
            @Override
            public void run() {
                try {
                    RobotPeopleClient.touch(app, id);
                } catch (IOException e) {
                    Log.w(TAG, "touch failed for id " + id + ": " + e.getMessage());
                }
            }
        }, null, 0, null);
    }

    @Override
    public void lines(final long timeoutMs) {
        final int g = strangerLines.start();
        run(new Runnable() {
            @Override
            public void run() {
                List<Map<String, Object>> content = new ArrayList<Map<String, Object>>();
                content.add(ClaudeApi.textBlock(ExplorePrompts.LINES_ASK));
                ClaudeApi.MessageResult r = claude.messages(fetchSettings(), ExplorePrompts.SYSTEM, content,
                        ExplorePrompts.LINES_SCHEMA, (int) timeoutMs);
                MatchAnswer a = r.ok() ? ClaudeReplies.lines(r.json) : MatchAnswer.FAILED;
                Log.i(TAG, "lines request: " + (r.ok() ? a.status.toString() : r.describe()));
                strangerLines.finish(g, a);
            }
        }, strangerLines, g, MatchAnswer.FAILED);
    }

    @Override
    public MatchAnswer linesAnswer() {
        return strangerLines.poll();
    }

    @Override
    public void listen(long maxMs) {
        final int g = hearings.start();
        ears.listen(maxMs, new RobotListenClient.Listener() {
            @Override
            public void onHeard(String transcript) {
                hearings.finish(g, new Heard(Heard.Status.WORDS, transcript));
            }

            @Override
            public void onNoSpeech() {
                hearings.finish(g, Heard.NOTHING);
            }

            @Override
            public void onFailed(String reason) {
                Log.w(TAG, "listening failed: " + reason);
                hearings.finish(g, new Heard(Heard.Status.FAILED, null));
            }
        });
    }

    @Override
    public Heard heard() {
        return hearings.poll();
    }

    @Override
    public void findName(final String transcript, final long timeoutMs) {
        final int g = names.start();
        run(new Runnable() {
            @Override
            public void run() {
                String local = NameExtractor.extract(transcript);
                if (local != null) {
                    Log.i(TAG, "name found on the robot");
                    names.finish(g, Named.of(local));
                    return;
                }
                List<Map<String, Object>> content = new ArrayList<Map<String, Object>>();
                content.add(ClaudeApi.textBlock(ExplorePrompts.nameAsk(transcript)));
                ClaudeApi.MessageResult r = claude.messages(fetchSettings(), ExplorePrompts.SYSTEM, content,
                        ExplorePrompts.NAME_SCHEMA, (int) timeoutMs);
                Named found = r.ok() ? ClaudeReplies.name(r.json) : Named.FAILED;
                Log.i(TAG, "name request: " + (r.ok() ? found.status.toString() : r.describe()));
                names.finish(g, found);
            }
        }, names, g, Named.FAILED);
    }

    @Override
    public Named foundName() {
        return names.poll();
    }

    @Override
    public void remember(final String nameOrNull, final long timeoutMs) {
        final int g = remembers.start();
        final byte[] face = meetFace;
        run(new Runnable() {
            @Override
            public void run() {
                remembers.finish(g, keep(face, nameOrNull, timeoutMs));
            }
        }, remembers, g, Answer.failed());
    }

    /** Store the face (a reply is the consent, R12), then ask for the "I'll remember you" line. */
    private Answer keep(byte[] face, String nameOrNull, long timeoutMs) {
        if (face == null) {
            Log.w(TAG, "remember: no face from the match to store");
            return Answer.failed();
        }
        String how = nameOrNull == null ? "unnamed" : "with a name";
        try {
            String id = RobotPeopleClient.add(app, face, nameOrNull);
            Log.i(TAG, "remembered a new person " + how + ", id " + id);
        } catch (IOException e) {
            Log.w(TAG, "remember: the people store refused or is unavailable: " + e.getMessage());
            return Answer.failed();
        }
        List<Map<String, Object>> content = new ArrayList<Map<String, Object>>();
        content.add(ClaudeApi.jpegBlock(face));
        content.add(ClaudeApi.textBlock(ExplorePrompts.rememberAsk(nameOrNull)));
        ClaudeApi.MessageResult r = claude.messages(fetchSettings(), ExplorePrompts.SYSTEM, content,
                ExplorePrompts.REMEMBER_SCHEMA, (int) timeoutMs);
        Answer a = r.ok() ? ClaudeReplies.remembered(r.json) : Answer.failed();
        Log.i(TAG, "remember request: " + (r.ok() ? a.status.toString() : r.describe()));
        return a;
    }

    @Override
    public Answer remembered() {
        return remembers.poll();
    }

    // ---- plumbing ----

    /** Runs a job on the worker; if the pool has shut down, the slot gets its failure at once. */
    private <T> void run(Runnable job, Slot<T> slot, int g, T failure) {
        try {
            worker.execute(job);
        } catch (RuntimeException e) {
            if (slot != null) {
                slot.finish(g, failure);
            }
        }
    }

    /** A JPEG's width and height from its header, or the camera's size when it can't be read. */
    private static int[] jpegSize(byte[] jpeg) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
        return o.outWidth > 0 && o.outHeight > 0 ? new int[] {o.outWidth, o.outHeight}
                : new int[] {FRAME_W, FRAME_H};
    }

    /**
     * One request kind's latest answer. start() begins a generation and clears
     * the answer; finish() keeps an answer only for the current generation, so
     * an abandoned request's late answer is dropped.
     */
    private static final class Slot<T> {
        private int generation;
        private T result;

        synchronized int start() {
            result = null;
            return ++generation;
        }

        synchronized void finish(int g, T value) {
            if (g == generation) {
                result = value;
            }
        }

        synchronized boolean current(int g) {
            return g == generation;
        }

        synchronized void cancel() {
            generation++;
            result = null;
        }

        synchronized T poll() {
            return result;
        }
    }
}
