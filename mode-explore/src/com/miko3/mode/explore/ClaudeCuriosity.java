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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
    /**
     * The owner's face-crop check: with log.tag.MikoExploreFaceDebug=DEBUG, the
     * last crop and its source frame go to this app's private files directory
     * (last-face.jpg, last-face-src.jpg), overwritten each time. Never shared
     * storage, never image data in the log.
     */
    static final String FACE_DEBUG_TAG = "MikoExploreFaceDebug";
    static final String LAST_FACE = "last-face.jpg";
    static final String LAST_FACE_SRC = "last-face-src.jpg";
    /** Assumed when a frame's size can't be read: the camera's own (ExploreCamera). */
    private static final int FRAME_W = 640;
    private static final int FRAME_H = 480;

    private final Context app;
    private final ClaudeApi claude = new ClaudeApi(new ClaudeHttpsTransport());
    private final RobotSpeechClient speech;
    private final RobotListenClient ears;
    /** YuNet on ONNX Runtime; its model loads at the first MEET and is freed by release(). */
    private final FaceCropper cropper;
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
    private final Slot<Answer> welcomes = new Slot<Answer>();
    private final Slot<WayOut> wayOuts = new Slot<WayOut>();
    private final Slot<Doorway> doorways = new Slot<Doorway>();
    private final Slot<Recently> recents = new Slot<Recently>();

    /** The face cut out by the last match(), for remember(); and the id it matched, for touch(). */
    private volatile byte[] meetFace;
    private volatile String matchedId;

    /**
     * Someone met (explore nav plan U7): the face their meeting's match cut out, in
     * memory only and never written anywhere, and their people-store id once known
     * (matched, or stored by remember()). The recently-met check compares against the
     * stored face, else this crop (someone who didn't reply is never stored).
     */
    private static final class MetFace {
        final long at = System.currentTimeMillis();
        volatile byte[] crop;
        volatile String storeId;
    }

    /** The current meeting's, from match(); and everyone metId() handed out a handle for. */
    private volatile MetFace meeting;
    private final Map<String, MetFace> metFaces = new ConcurrentHashMap<String, MetFace>();
    private final AtomicInteger metHandles = new AtomicInteger();
    /** Past the brain's 10-minute leave-alone (with a margin): the crop is dropped. */
    private static final long MET_KEEP_MS = 15 * 60 * 1000L;

    ClaudeCuriosity(Context context) {
        app = context.getApplicationContext();
        speech = new RobotSpeechClient(app);
        ears = new RobotListenClient(app);
        cropper = new FaceCropper(app);
        refreshSettings();
    }

    /** Drops any line still queued and stops answering; ModeApp calls it as Explore stops. */
    void release() {
        released = true;
        speech.cancel();
        // A new adapter is built per Explore start: give back the clients' threads.
        speech.close();
        ears.close();
        worker.shutdownNow();
        // Waits for a crop still running, then frees the face model.
        cropper.close();
        metFaces.clear();
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

    // ---- the way out of a wedge (explore nav plan U5, KTD4) ----

    @Override
    public void wayOut(final WayOutRequest request, final long timeoutMs) {
        final int g = wayOuts.start();
        run(new Runnable() {
            @Override
            public void run() {
                wayOuts.finish(g, findWayOut(request, timeoutMs));
            }
        }, wayOuts, g, WayOut.failed());
    }

    @Override
    public WayOut wayOutAnswer() {
        return wayOuts.poll();
    }

    @Override
    public void cancelWayOut() {
        wayOuts.cancel();
    }

    /** The frames go only into this request (R15): nothing is kept, written or logged but counts. */
    private WayOut findWayOut(WayOutRequest request, long timeoutMs) {
        long t0 = System.currentTimeMillis();
        int n = request.frames.size();
        if (n == 0) {
            return WayOut.failed();
        }
        int[] w = new int[n];
        int[] h = new int[n];
        for (int i = 0; i < n; i++) {
            int[] size = jpegSize(request.frames.get(i).jpeg);
            w[i] = size[0];
            h[i] = size[1];
        }
        List<Map<String, Object>> content = new ArrayList<Map<String, Object>>();
        content.add(ClaudeApi.textBlock(ExplorePrompts.wayOutIntro(n, w[0], h[0], request.second)));
        for (int i = 0; i < n; i++) {
            content.add(ClaudeApi.textBlock("Frame " + (i + 1) + ":"));
            content.add(ClaudeApi.jpegBlock(request.frames.get(i).jpeg));
        }
        content.add(ClaudeApi.textBlock(ExplorePrompts.wayOutAsk(n, request.second)));
        ClaudeApi.MessageResult r = claude.messages(fetchSettings(), ExplorePrompts.NAV_SYSTEM, content,
                ExplorePrompts.WAY_OUT_SCHEMA, (int) timeoutMs);
        WayOut a = r.ok() ? ClaudeReplies.wayOut(r.json, w) : WayOut.failed();
        String outcome = r.ok() ? a.status.toString() : r.describe();
        Log.i(TAG, (request.second ? "second " : "") + "way-out request with " + n + " frames: " + outcome + " in "
                + (System.currentTimeMillis() - t0) + " ms");
        return a;
    }

    // ---- open doorways (explore nav plan U6, KTD4) ----

    @Override
    public void doorway(final byte[] jpeg, final long timeoutMs) {
        final int g = doorways.start();
        run(new Runnable() {
            @Override
            public void run() {
                doorways.finish(g, findDoorway(jpeg, timeoutMs));
            }
        }, doorways, g, Doorway.failed());
    }

    @Override
    public Doorway doorwayAnswer() {
        return doorways.poll();
    }

    @Override
    public void cancelDoorway() {
        doorways.cancel();
    }

    /** The one frame goes only into this request (R15): nothing is kept, written or logged but numbers. */
    private Doorway findDoorway(byte[] jpeg, long timeoutMs) {
        long t0 = System.currentTimeMillis();
        if (jpeg == null) {
            return Doorway.failed();
        }
        int[] size = jpegSize(jpeg);
        List<Map<String, Object>> content = new ArrayList<Map<String, Object>>();
        content.add(ClaudeApi.textBlock(ExplorePrompts.doorwayIntro(size[0], size[1])));
        content.add(ClaudeApi.jpegBlock(jpeg));
        content.add(ClaudeApi.textBlock(ExplorePrompts.doorwayAsk()));
        ClaudeApi.MessageResult r = claude.messages(fetchSettings(), ExplorePrompts.NAV_SYSTEM, content,
                ExplorePrompts.DOORWAY_SCHEMA, (int) timeoutMs);
        Doorway a = r.ok() ? ClaudeReplies.doorway(r.json, size[0]) : Doorway.failed();
        String outcome = r.ok() ? a.status.toString() : r.describe();
        Log.i(TAG, "doorway request: " + outcome + " in " + (System.currentTimeMillis() - t0) + " ms");
        return a;
    }

    // ---- people while roaming: the recently-met check (explore nav plan U7, KTD4, KTD8) ----

    @Override
    public String metId() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, MetFace> e : metFaces.entrySet()) {
            if (now - e.getValue().at > MET_KEEP_MS) {
                metFaces.remove(e.getKey());
            }
        }
        MetFace mf = meeting;
        if (mf == null || mf.crop == null) {
            return null;
        }
        String id = "met-" + metHandles.incrementAndGet();
        metFaces.put(id, mf);
        return id;
    }

    @Override
    public void recentlyMet(final RecentlyMetRequest request, final long timeoutMs) {
        final int g = recents.start();
        run(new Runnable() {
            @Override
            public void run() {
                recents.finish(g, checkRecentlyMet(g, request, timeoutMs));
            }
        }, recents, g, Recently.failed());
    }

    @Override
    public Recently recentlyMetAnswer() {
        return recents.poll();
    }

    @Override
    public void cancelRecentlyMet() {
        recents.cancel();
    }

    /**
     * Modelled on the person request: the face cut from the roaming frame's person
     * box, against the faces of everyone met recently, labelled by number only.
     * Never debugFace: no roaming frame or crop is written anywhere, even with the
     * face debug switch on, and nothing is logged but counts, statuses and handles (R15).
     */
    private Recently checkRecentlyMet(int g, RecentlyMetRequest request, long timeoutMs) {
        long t0 = System.currentTimeMillis();
        int n = request.met.size();
        if (n == 0 || request.frameJpeg == null || request.personBox == null) {
            return Recently.failed();
        }
        FaceCrop.Result crop = cropper.crop(request.frameJpeg, request.personBox);
        if (!crop.found()) {
            Log.i(TAG, "recently-met check: no face found in the person box; unsure");
            return Recently.unsure();
        }
        RobotPeople.Face[] gallery = null;
        try {
            gallery = RobotPeopleClient.recent(app, RobotPeople.MAX_RECENT);
        } catch (IOException e) {
            Log.w(TAG, "recently-met check: people store unavailable, comparing with this session's crops: "
                    + e.getMessage());
        }
        if (!recents.current(g)) {
            return Recently.failed();
        }
        List<byte[]> refs = new ArrayList<byte[]>();
        for (String id : request.met) {
            MetFace mf = metFaces.get(id);
            byte[] ref = mf == null ? null : storedFace(gallery, mf.storeId);
            if (ref == null && mf != null) {
                ref = mf.crop;
            }
            if (ref == null) {
                Log.w(TAG, "recently-met check: nothing to compare for handle " + id + "; unsure");
                return Recently.unsure();
            }
            refs.add(ref);
        }
        List<Map<String, Object>> content = new ArrayList<Map<String, Object>>();
        content.add(ClaudeApi.textBlock(ExplorePrompts.recentlyMetIntro(n)));
        content.add(ClaudeApi.textBlock("Query:"));
        content.add(ClaudeApi.jpegBlock(crop.face));
        for (int i = 0; i < n; i++) {
            content.add(ClaudeApi.textBlock("Person " + (i + 1) + ":"));
            content.add(ClaudeApi.jpegBlock(refs.get(i)));
        }
        content.add(ClaudeApi.textBlock(ExplorePrompts.recentlyMetAsk(n)));
        ClaudeApi.MessageResult r = claude.messages(fetchSettings(), ExplorePrompts.SYSTEM, content,
                ExplorePrompts.RECENTLY_MET_SCHEMA, (int) timeoutMs);
        Recently a = r.ok() ? ClaudeReplies.recentlyMet(r.json, n) : Recently.failed();
        Log.i(TAG, "recently-met check against " + n + " people: " + (r.ok() ? a.toString() : r.describe())
                + " in " + (System.currentTimeMillis() - t0) + " ms");
        return a;
    }

    /** The people store's face for this id, from a recent() gallery, or null. */
    private static byte[] storedFace(RobotPeople.Face[] gallery, String storeId) {
        if (gallery == null || storeId == null) {
            return null;
        }
        for (RobotPeople.Face f : gallery) {
            if (storeId.equals(f.id)) {
                return f.jpeg;
            }
        }
        return null;
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
        final MetFace mf = new MetFace();
        meeting = mf;
        run(new Runnable() {
            @Override
            public void run() {
                matches.finish(g, person(g, frameJpeg, personBox, timeoutMs, mf));
            }
        }, matches, g, MatchAnswer.FAILED);
    }

    @Override
    public MatchAnswer matchAnswer() {
        return matches.poll();
    }

    /** The person request: the new face and up to MAX_RECENT stored ones, labelled by number only. */
    private MatchAnswer person(int g, byte[] frameJpeg, Detection personBox, long timeoutMs, MetFace mf) {
        long t0 = System.currentTimeMillis();
        FaceCrop.Result crop = cropper.crop(frameJpeg, personBox);
        debugFace(frameJpeg, personBox, crop);
        if (!crop.found()) {
            // No face in the person box: nothing to match or store (R12). A new person
            // to talk to, with the text-only lines; the brain promises nothing.
            Log.i(TAG, "person request: no face found in the person box; asking as a new person, storing nothing");
            ClaudeApi.MessageResult r = claude.messages(fetchSettings(), ExplorePrompts.SYSTEM,
                    textOnly(ExplorePrompts.LINES_ASK), ExplorePrompts.LINES_SCHEMA, (int) timeoutMs);
            MatchAnswer lines = r.ok() ? ClaudeReplies.lines(r.json) : MatchAnswer.FAILED;
            Log.i(TAG, "faceless lines request: " + (r.ok() ? lines.status.toString() : r.describe()) + " in "
                    + (System.currentTimeMillis() - t0) + " ms");
            return lines.status == MatchAnswer.Status.NEW
                    ? MatchAnswer.faceless(lines.askLine, lines.noReplyLine) : MatchAnswer.FAILED;
        }
        byte[] face = crop.face;
        if (matches.current(g)) {
            mf.crop = face;
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
                mf.storeId = id;
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
                long t0 = System.currentTimeMillis();
                List<Map<String, Object>> content = new ArrayList<Map<String, Object>>();
                content.add(ClaudeApi.textBlock(ExplorePrompts.LINES_ASK));
                ClaudeApi.MessageResult r = claude.messages(fetchSettings(), ExplorePrompts.SYSTEM, content,
                        ExplorePrompts.LINES_SCHEMA, (int) timeoutMs);
                MatchAnswer a = r.ok() ? ClaudeReplies.lines(r.json) : MatchAnswer.FAILED;
                Log.i(TAG, "lines request: " + (r.ok() ? a.status.toString() : r.describe()) + " in "
                        + (System.currentTimeMillis() - t0) + " ms");
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
                long t0 = System.currentTimeMillis();
                List<Map<String, Object>> content = new ArrayList<Map<String, Object>>();
                content.add(ClaudeApi.textBlock(ExplorePrompts.nameAsk(transcript)));
                ClaudeApi.MessageResult r = claude.messages(fetchSettings(), ExplorePrompts.SYSTEM, content,
                        ExplorePrompts.NAME_SCHEMA, (int) timeoutMs);
                Named found = r.ok() ? ClaudeReplies.name(r.json) : Named.FAILED;
                Log.i(TAG, "name request: " + (r.ok() ? found.status.toString() : r.describe()) + " in "
                        + (System.currentTimeMillis() - t0) + " ms");
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
        final MetFace mf = meeting;
        run(new Runnable() {
            @Override
            public void run() {
                remembers.finish(g, keep(face, nameOrNull, timeoutMs, mf));
            }
        }, remembers, g, Answer.failed());
    }

    /** Store the face (a reply is the consent, R12), then ask for the "I'll remember you" line. */
    private Answer keep(byte[] face, String nameOrNull, long timeoutMs, MetFace mf) {
        if (face == null) {
            // Nothing to store: never promise to remember them.
            Log.w(TAG, "remember: no face from the match to store; a hello without the promise");
            return hello(nameOrNull, timeoutMs);
        }
        String how = nameOrNull == null ? "unnamed" : "with a name";
        try {
            String id = RobotPeopleClient.add(app, face, nameOrNull);
            if (mf != null) {
                mf.storeId = id;
            }
            Log.i(TAG, "remembered a new person " + how + ", id " + id);
        } catch (IOException e) {
            Log.w(TAG, "remember: the people store refused or is unavailable: " + e.getMessage());
            return Answer.failed();
        }
        long t0 = System.currentTimeMillis();
        List<Map<String, Object>> content = new ArrayList<Map<String, Object>>();
        content.add(ClaudeApi.jpegBlock(face));
        content.add(ClaudeApi.textBlock(ExplorePrompts.rememberAsk(nameOrNull)));
        ClaudeApi.MessageResult r = claude.messages(fetchSettings(), ExplorePrompts.SYSTEM, content,
                ExplorePrompts.REMEMBER_SCHEMA, (int) timeoutMs);
        Answer a = r.ok() ? ClaudeReplies.remembered(r.json) : Answer.failed();
        Log.i(TAG, "remember request: " + (r.ok() ? a.status.toString() : r.describe()) + " in "
                + (System.currentTimeMillis() - t0) + " ms");
        return a;
    }

    @Override
    public Answer remembered() {
        return remembers.poll();
    }

    @Override
    public void welcome(final String nameOrNull, final long timeoutMs) {
        final int g = welcomes.start();
        run(new Runnable() {
            @Override
            public void run() {
                welcomes.finish(g, hello(nameOrNull, timeoutMs));
            }
        }, welcomes, g, Answer.failed());
    }

    @Override
    public Answer welcomed() {
        return welcomes.poll();
    }

    /** Text only: a "nice to meet you" that doesn't promise to remember them. Stores nothing. */
    private Answer hello(String nameOrNull, long timeoutMs) {
        long t0 = System.currentTimeMillis();
        ClaudeApi.MessageResult r = claude.messages(fetchSettings(), ExplorePrompts.SYSTEM,
                textOnly(ExplorePrompts.welcomeAsk(nameOrNull)), ExplorePrompts.REMEMBER_SCHEMA, (int) timeoutMs);
        Answer a = r.ok() ? ClaudeReplies.remembered(r.json) : Answer.failed();
        Log.i(TAG, "hello request: " + (r.ok() ? a.status.toString() : r.describe()) + " in "
                + (System.currentTimeMillis() - t0) + " ms");
        return a;
    }

    private static List<Map<String, Object>> textOnly(String text) {
        List<Map<String, Object>> content = new ArrayList<Map<String, Object>>();
        content.add(ClaudeApi.textBlock(text));
        return content;
    }

    /**
     * The owner's crop check (FACE_DEBUG_TAG): the source frame and the crop, in
     * this app's private files directory, overwritten each time. No crop (no
     * face found) deletes last-face.jpg, so a stale one is never mistaken for it.
     */
    private void debugFace(byte[] frameJpeg, Detection personBox, FaceCrop.Result crop) {
        if (!Log.isLoggable(FACE_DEBUG_TAG, Log.DEBUG)) {
            return;
        }
        File dir = app.getFilesDir();
        write(new File(dir, LAST_FACE_SRC), frameJpeg);
        File face = new File(dir, LAST_FACE);
        if (crop.found()) {
            write(face, crop.face);
        } else if (face.exists() && !face.delete()) {
            Log.w(FACE_DEBUG_TAG, "could not delete the previous " + LAST_FACE);
        }
        // Geometry only: never image data, and never the box's label (Claude's description of a person).
        int[] square = crop.square;
        Log.d(FACE_DEBUG_TAG, String.format(java.util.Locale.US, "person box [%.2f,%.2f,%.2f,%.2f]",
                personBox.x0, personBox.y0, personBox.x1, personBox.y1) + (square != null
                ? ": face at " + java.util.Arrays.toString(square) + " (left, top, side px)"
                : ": no face found"));
    }

    private static void write(File f, byte[] bytes) {
        if (bytes == null) {
            return;
        }
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(f);
            out.write(bytes);
        } catch (IOException e) {
            Log.w(FACE_DEBUG_TAG, "could not write " + f.getName() + ": " + e.getMessage());
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                    // Nothing more to do for a debug file.
                }
            }
        }
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
