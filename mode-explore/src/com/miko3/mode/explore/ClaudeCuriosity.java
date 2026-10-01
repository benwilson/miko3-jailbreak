package com.miko3.mode.explore;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.util.Log;

import com.miko3.shared.ClaudeAccess;
import com.miko3.shared.ClaudeApi;
import com.miko3.shared.ClaudeHttpsTransport;
import com.miko3.shared.FaceCheck;
import com.miko3.shared.FaceSettings;
import com.miko3.shared.LauncherProtocol;
import com.miko3.shared.PersonNotes;
import com.miko3.shared.Json;
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
import java.util.Arrays;
import java.util.List;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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
 * Meeting someone is matched on the robot (on-device face recognition plan
 * U6): the face is found, gated, straightened and embedded here and compared
 * with every stored photo's embedding; no face or name goes to Claude for it.
 * A start-up migration embeds stored photos that predate the current model.
 *
 * Privacy (R13): face crops and frames leave the robot only inside requests to
 * the configured endpoint (the conversation's opener and the recently-met
 * check). Nothing here logs an image, the key, a reply's
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
    /** With the switch on, each meeting's source frame is also kept here, newest FACE_FRAMES_KEPT (U9's bench). */
    static final String FACE_FRAMES = "face-frames";
    static final int FACE_FRAMES_KEPT = 50;
    /** A stored photo is a face crop already: the whole of it is the person box (KTD11). */
    private static final Detection WHOLE_PHOTO = new Detection("person", 1f, 0f, 0f, 1f, 1f);
    /** Assumed when a frame's size can't be read: the camera's own (ExploreCamera). */
    private static final int FRAME_W = 640;
    private static final int FRAME_H = 480;

    private final Context app;
    private final ClaudeApi claude = new ClaudeApi(new ClaudeHttpsTransport());
    private final RobotSpeechClient speech;
    private final RobotListenClient ears;
    /** The continuous ears session (meeting plan U7, KTD1), set by ModeApp; null means the one-shot listen only. */
    private volatile EarsAdapter session;
    /** YuNet on ONNX Runtime; its model loads at the first MEET and is freed by release(). */
    private final FaceCropper cropper;
    /** SFace (face plan U3): loads on the first embedding, freed by release(). */
    private final FaceEmbedder embedder;
    /** The start-up migration (KTD11), run on the worker while the brain allows face work. */
    private final FaceMigration migration;
    private volatile boolean faceWorkAllowed;
    private final AtomicBoolean migrating = new AtomicBoolean();
    /** The current migration pass's gate; null until the pass first needs it. */
    private FaceQuality.Thresholds passGate;
    private final ExecutorService worker = Executors.newCachedThreadPool(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "explore-claude");
            t.setDaemon(true);
            return t;
        }
    });
    /** The ears listens' silence deadlines (Heard.NOTHING after maxMs): one daemon timer, no thread parked. */
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "explore-claude-timer");
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
    /** The conversation (meeting plan U8): one turn, a notes delta, a forget and a keep at a time. */
    private final Slot<Turn> turns = new Slot<Turn>();
    private final Slot<Done> notes = new Slot<Done>();
    private final Slot<Done> forgets = new Slot<Done>();
    private final Slot<Kept> keeps = new Slot<Kept>();
    /** Confirming and resolving names (face plan U7): one resolve and one added photo at a time. */
    private final Slot<Resolved> resolves = new Slot<Resolved>();
    private final Slot<MatchAnswer> photoAdds = new Slot<MatchAnswer>();
    /** The recommended effort for a turn (KTD9); the client's gate drops it where a model refuses it. */
    private static final String TURN_EFFORT = "low";

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
        /**
         * What the meeting may store (face plan U6, R18): the probe embedding and
         * the brightened loose crop, both null when the meeting is faceless (no
         * face, a rejected crop, the store not ready). U7's resolver adds the crop
         * to a person by id with these (addPhoto).
         */
        volatile float[] probe;
        volatile byte[] storeCrop;
        /** The gallery and close threshold the match ran with (KTD11: the gallery
         * once per meeting); the resolver scores against these. Set before probe. */
        volatile List<FaceMatcher.Entry> entries;
        volatile float close;
        /** The matcher's decision (null when none ran) and the face check's handle (-1: none). */
        volatile FaceMatcher.Result result;
        volatile long checkHandle = -1;
        /** A name the resolver asked the last name for (U7, KTD10): robot-side only, never logged. */
        volatile String pendingFirst;
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
        embedder = new FaceEmbedder(app);
        migration = new FaceMigration(new MigrationStore(), new FaceMigration.Faces() {
            @Override
            public float[] embed(byte[] jpeg) {
                return embedStored(jpeg);
            }
        }, new FaceMigration.Gate() {
            @Override
            public boolean open() {
                return faceWorkAllowed && !released;
            }
        });
        refreshSettings();
    }

    /** The continuous ears session the port's ears calls and the meeting's listen go through (KTD1). */
    void setEars(EarsAdapter ears) {
        session = ears;
    }

    /** Drops any line still queued and stops answering; ModeApp calls it as Explore stops. */
    void release() {
        released = true;
        speech.cancel();
        // A new adapter is built per Explore start: give back the clients' threads.
        speech.close();
        ears.close();
        worker.shutdownNow();
        timer.shutdownNow();
        // Waits for a crop or an embedding still running, then frees the face models.
        cropper.close();
        embedder.close();
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
        int firstHeight = 0;
        for (int i = 0; i < n; i++) {
            int[] size = jpegSize(request.frames.get(i).jpeg);
            w[i] = size[0];
            if (i == 0) {
                firstHeight = size[1];
            }
        }
        List<Map<String, Object>> content = new ArrayList<Map<String, Object>>();
        content.add(ClaudeApi.textBlock(ExplorePrompts.wayOutIntro(n, w[0], firstHeight, request.second)));
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

    // ---- the conversation (meeting plan U8; KTD9, KTD10): one multi-turn request per turn, the store, the ears ----

    /**
     * One turn (KTD9): the frozen system prefix from the request's persona snapshot
     * and notes, the transcript window as user and assistant messages, what was just
     * heard as the last user message (the opener ask instead for turn 1, with the
     * face crop sent that once), the reply schema, effort low behind the client's
     * gate, and this try's budget as the read timeout. Nothing said or heard is logged.
     */
    @Override
    public void turn(final TurnRequest request, final long timeoutMs) {
        final int g = turns.start();
        // The opener carries the current meeting's own crop, never one a late match left behind.
        final MetFace met = meeting;
        final byte[] face = request.heard == null && request.transcript.isEmpty() && met != null
                ? met.storeCrop : null;
        run(new Runnable() {
            @Override
            public void run() {
                turns.finish(g, oneTurn(request, face, timeoutMs));
            }
        }, turns, g, Turn.failed());
    }

    private Turn oneTurn(TurnRequest request, byte[] face, long timeoutMs) {
        long t0 = System.currentTimeMillis();
        String system = ExplorePrompts.systemPrefix(request.persona, request.notes);
        List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
        // A conversation that opened faceless invites them down instead of asking the name (robot 2026-10-01).
        String first = request.faceless ? ExplorePrompts.FACELESS_OPENER : ExplorePrompts.openerAsk(request.name);
        for (Exchange e : request.transcript) {
            messages.add(ClaudeApi.message("user", e.heard == null ? first : e.heard));
            messages.add(ClaudeApi.message("assistant", ExplorePrompts.saidAsJson(e.said == null ? "" : e.said)));
        }
        String ask = request.heard == null ? first : request.heard;
        if (request.avoidQuestion != null) {
            ask = ask + "\n\n" + ExplorePrompts.avoidQuestion(request.avoidQuestion);
        }
        if (request.faceSeen) {
            ask = ask + "\n\n" + ExplorePrompts.FACE_SEEN;
        }
        if (face != null) {
            List<Map<String, Object>> content = new ArrayList<Map<String, Object>>();
            content.add(ClaudeApi.jpegBlock(face));
            content.add(ClaudeApi.textBlock(ask));
            messages.add(ClaudeApi.message("user", content));
        } else {
            messages.add(ClaudeApi.message("user", ask));
        }
        ClaudeApi.MessageResult r = claude.conversation(fetchSettings(), system, messages, ExplorePrompts.REPLY_SCHEMA,
                TURN_EFFORT, (int) timeoutMs);
        long ms = System.currentTimeMillis() - t0;
        Turn t = turnOf(r);
        String opener = messages.size() == 1 ? " (the opener)" : "";
        Log.i(TAG, "turn request with " + messages.size() + " message(s)" + opener + ": "
                + (r.ok() ? t.status.toString() : r.describe()) + " in " + ms + " ms");
        return t;
    }

    /** The client's reason as the brain's turn status; a name given passes NameExtractor's word list first. */
    private static Turn turnOf(ClaudeApi.MessageResult r) {
        if (r.ok()) {
            Object delta = r.json.get("notes_update");
            Turn t = ClaudeReplies.turn(r.json, delta instanceof Map ? Json.write(delta) : null);
            if (t.status != Turn.Status.LINE || t.nameGiven == null) {
                return t;
            }
            return Turn.line(t.line, t.questionAsked, NameExtractor.validName(t.nameGiven), t.endsConversation,
                    t.deflected, t.notesUpdate);
        }
        switch (r.reason) {
            case REFUSED:
                return Turn.refused();
            case UNREACHABLE:
            case OVERLOADED:
            case RATE_LIMITED:
            case ENDPOINT_ERROR:
                return Turn.unreachable();
            default:
                return Turn.failed();
        }
    }

    @Override
    public Turn turnAnswer() {
        return turns.poll();
    }

    @Override
    public void cancelTurn() {
        turns.cancel();
    }

    /** A notes delta merged through the People store (KTD10); the store's fixed refusal reason is all that is logged. */
    @Override
    public void notesDelta(final String personId, final String notesUpdate, final long timeoutMs) {
        final int g = notes.start();
        run(new Runnable() {
            @Override
            public void run() {
                long t0 = System.currentTimeMillis();
                Done d;
                try {
                    RobotPeopleClient.mergeNotes(app, personId, notesUpdate);
                    d = Done.OK;
                } catch (IOException e) {
                    Log.w(TAG, "notes delta refused or the store is unavailable: " + e.getMessage());
                    d = Done.FAILED;
                }
                Log.i(TAG, "notes delta: " + d.status + " in " + (System.currentTimeMillis() - t0) + " ms");
                notes.finish(g, d);
            }
        }, notes, g, Done.FAILED);
    }

    @Override
    public Done notesDeltaAnswer() {
        return notes.poll();
    }

    @Override
    public void cancelNotesDelta() {
        notes.cancel();
    }

    /** Forget by id (R18, KTD10): the store wipes index, notes and face; the meeting's handle forgets the id too. */
    @Override
    public void forget(final String personId, final long timeoutMs) {
        final int g = forgets.start();
        run(new Runnable() {
            @Override
            public void run() {
                Done d;
                try {
                    d = RobotPeopleClient.forget(app, personId) ? Done.OK : Done.FAILED;
                } catch (IOException e) {
                    Log.w(TAG, "forget failed or the store is unavailable: " + e.getMessage());
                    d = Done.FAILED;
                }
                if (d.ok()) {
                    for (MetFace mf : metFaces.values()) {
                        if (personId.equals(mf.storeId)) {
                            mf.storeId = null;
                        }
                    }
                    MetFace mf = meeting;
                    if (mf != null && personId.equals(mf.storeId)) {
                        mf.storeId = null;
                    }
                }
                Log.i(TAG, "forget: " + d.status);
                forgets.finish(g, d);
            }
        }, forgets, g, Done.FAILED);
    }

    @Override
    public Done forgetAnswer() {
        return forgets.poll();
    }

    @Override
    public void cancelForget() {
        forgets.cancel();
    }

    /** A conversation listen through the session (KTD8); without one, the one-shot listen as the meeting's. */
    @Override
    public void chatListen(final long maxMs, final float newcomerAngleDeg) {
        final EarsAdapter s = session;
        if (s == null || !s.isOpen()) {
            listen(maxMs);
            return;
        }
        earsListen(s, maxMs, newcomerAngleDeg);
    }

    /**
     * A listen through the ears session: the next utterance with words finishes
     * WORDS; with none by maxMs the timer finishes NOTHING, unless that generation
     * was replaced or answered meanwhile. newcomerAngleDeg is NaN for a meeting listen.
     */
    private void earsListen(final EarsAdapter s, long maxMs, float newcomerAngleDeg) {
        final int g = hearings.start();
        // A newer listen retires this reply by replacing it in the session, and the
        // session's close or loss clears it; the silence deadline retires it below.
        final EarsAdapter.Reply reply = new EarsAdapter.Reply() {
            @Override
            public void heard(String transcript) {
                hearings.finish(g, new Heard(Heard.Status.WORDS, transcript));
            }
        };
        s.listen(maxMs, newcomerAngleDeg, reply);
        try {
            timer.schedule(new Runnable() {
                @Override
                public void run() {
                    if (hearings.current(g) && hearings.poll() == null) {
                        // Silence: retire the reply first, or the next utterance with
                        // words would answer this dead listen instead of queuing as a cue.
                        s.listenOver(reply);
                        hearings.finish(g, Heard.NOTHING);
                    }
                }
            }, maxMs, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            // The timer has shut down with Explore: no silence deadline, as the pool gave none once shut.
        }
    }

    /**
     * The retained crop stored under a new record with this name (KTD10), with its
     * embedding in one step (face plan U6, KTD12): someone new the resolver found for
     * a name given (U7, KTD6). A faceless meeting (no face, a rejected crop, the store not
     * ready) has nothing to store (R11, R18). No line is asked for and the face
     * debug dump never runs here (it belongs to the match, before the conversation).
     */
    @Override
    public void keep(final String name, final long timeoutMs) {
        final int g = keeps.start();
        final MetFace mf = meeting;
        final byte[] face = mf == null ? null : mf.storeCrop;
        final float[] probe = mf == null ? null : mf.probe;
        run(new Runnable() {
            @Override
            public void run() {
                if (face == null || probe == null) {
                    Log.w(TAG, "keep: nothing storable from the match (faceless, rejected or not ready)");
                    keeps.finish(g, Kept.FAILED);
                    return;
                }
                Kept k;
                try {
                    String id = RobotPeopleClient.addPerson(app, face, name, FaceMatcher.MODEL_ID, probe);
                    mf.storeId = id;
                    checkOutcome(mf, FaceCheck.NEW_PERSON, id);
                    k = Kept.done(id);
                    Log.i(TAG, "kept a new record, id " + id);
                } catch (IOException e) {
                    Log.w(TAG, "keep: the people store refused or is unavailable: " + e.getMessage());
                    k = Kept.FAILED;
                }
                keeps.finish(g, k);
            }
        }, keeps, g, Kept.FAILED);
    }

    @Override
    public Kept keptAnswer() {
        return keeps.poll();
    }

    @Override
    public void cancelKeep() {
        keeps.cancel();
    }

    /**
     * The conversation's fields on a match or lines answer (U8, KTD9, KTD11): the
     * persona snapshot from the Settings Binder, and for a known person their notes
     * and the questions on record. An older launcher with no conversation settings
     * leaves them off, and the meeting runs as it did before.
     */
    private MatchAnswer forConversation(MatchAnswer a, String personId) {
        if (a.status == MatchAnswer.Status.FAILED) {
            return a;
        }
        String persona;
        try {
            persona = RobotSettingsClient.fetchConversation(app).persona;
        } catch (IOException e) {
            Log.w(TAG, "conversation settings unavailable; the meeting runs as before: " + e.getMessage());
            return a;
        }
        String notesJson = null;
        List<String> asked = null;
        if (personId != null) {
            try {
                PersonNotes n = RobotPeopleClient.notesOf(app, personId);
                notesJson = n.toJson();
                asked = n.questionsAsked;
                Log.i(TAG, "notes for the conversation: " + n.byteLength() + " bytes, " + asked.size() + " question(s)");
            } catch (IOException e) {
                Log.w(TAG, "notes unavailable; the conversation runs without them: " + e.getMessage());
            }
        }
        return a.withConversation(persona, personId, notesJson, asked);
    }

    @Override
    public void earsOpen() {
        EarsAdapter s = session;
        if (s != null) {
            s.open();
        }
    }

    @Override
    public void earsClose() {
        EarsAdapter s = session;
        if (s != null) {
            s.close();
        }
    }

    @Override
    public void clipWindow(long ms) {
        EarsAdapter s = session;
        if (s != null) {
            s.clipWindow(ms);
        }
    }

    @Override
    public void earsShoved(long atMs) {
        EarsAdapter s = session;
        if (s != null) {
            s.shoved(atMs);
        }
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

    // ---- people (U5; on-device matching, face plan U6: KTD3, KTD7, KTD8, KTD11) ----

    @Override
    public void match(final byte[] frameJpeg, final Detection personBox, final long timeoutMs) {
        final int g = matches.start();
        // Publish the new meeting first, then close the old one's check (KTD8): a late
        // record() that misses this close sees the new meeting and closes its own (record()).
        final MetFace previous = meeting;
        final MetFace mf = new MetFace();
        meeting = mf;
        checkOutcome(previous, FaceCheck.ENDED_WITHOUT_ANSWER, null);
        run(new Runnable() {
            @Override
            public void run() {
                matches.finish(g, person(g, frameJpeg, personBox, mf));
            }
        }, matches, g, MatchAnswer.FAILED);
    }

    @Override
    public MatchAnswer matchAnswer() {
        return matches.poll();
    }

    /**
     * The meeting's match, on the robot (KTD3): find the face, check its size,
     * straighten it, check darkness and blur, brighten it when dim, embed it, and
     * compare it with every stored photo of every named person. Every outcome is
     * recorded as a face check (KTD8). The answer carries no lines (KTD7) and
     * nothing is sent to Claude. Only a confident, close or weak match leaves
     * something to store (R10, R11, R18); the rest meet facelessly.
     */
    private MatchAnswer person(int g, byte[] frameJpeg, Detection personBox, MetFace mf) {
        long t0 = System.currentTimeMillis();
        FaceCropper.Located found = cropper.locate(frameJpeg, personBox);
        debugFace(frameJpeg, personBox, found);
        if (found == null) {
            // No face in the person box: nothing to match or store (R10).
            Log.i(TAG, "person match: no face found in the person box; meeting without storing anything");
            long h = record(mf, check(FaceCheck.NO_FACE, FaceCheck.REASON_NONE, null, null, null));
            return facelessMeeting(null, h);
        }
        byte[] loose = found.crop.face;
        if (matches.current(g)) {
            // In memory only, for the recently-met check (explore nav plan U7).
            mf.crop = loose;
        }
        FaceSettings settings;
        try {
            settings = RobotSettingsClient.fetchFaceSettings(app);
        } catch (IOException e) {
            return facelessBecause("the face settings", e);
        }
        FaceQuality.Thresholds gate = FaceQuality.Thresholds.of(settings);
        int[] aligned = FaceAlign.align(found.argb, found.frameW, found.frameH, found.face.landmarks);
        if (aligned == null) {
            Log.w(TAG, "person match: the face could not be straightened; meeting without storing anything");
            long h = record(mf, check(FaceCheck.NO_FACE, FaceCheck.REASON_NONE, null, null, null));
            return facelessMeeting(null, h);
        }
        FaceQuality.Verdict v = FaceQuality.check(found.faceWidth(), aligned, FaceAlign.SIDE, FaceAlign.SIDE, gate,
                false);
        if (!v.ok()) {
            // Too small, dark or blurry: neither matched nor stored (R11, R14).
            Log.i(TAG, "person match: rejected as " + v.reason + "; meeting without storing anything");
            long h = record(mf, check(FaceCheck.REJECTED, reasonCode(v.reason), loose, null, null));
            return facelessMeeting(null, h);
        }
        // A dim crop is brightened before it is matched or stored, once (R12).
        byte[] storable = v.dim ? FaceCropper.brighten(loose, v.table) : loose;
        if (!matches.current(g)) {
            return MatchAnswer.FAILED;
        }
        float[] probe = embedder.embed(v.brighten(aligned));
        if (probe == null) {
            Log.w(TAG, "person match: no embedding, the face model is unavailable; meeting without storing anything");
            long h = record(mf, check(FaceCheck.NOT_READY, FaceCheck.REASON_NONE, storable, null, null));
            return facelessMeeting(null, h);
        }
        RobotPeople.GalleryPhoto[] gallery;
        try {
            gallery = RobotPeopleClient.gallery(app);
        } catch (IOException e) {
            return facelessBecause("the people store", e);
        }
        if (!matches.current(g)) {
            return MatchAnswer.FAILED;
        }
        boolean ready = FaceMigration.ready(photosOf(gallery));
        List<FaceMatcher.Entry> entries = entriesOf(gallery);
        FaceMatcher.Result r = FaceMatcher.match(probe, entries,
                new FaceMatcher.Thresholds(settings.confident, settings.close, settings.margin), ready);
        long ms = System.currentTimeMillis() - t0;
        int photos = gallery == null ? 0 : gallery.length;
        Log.i(TAG, "person match against " + photos + " stored photos" + (v.dim ? " (brightened)" : "") + ": " + r
                + " in " + ms + " ms");
        mf.result = r;
        if (r.band == FaceMatcher.Band.NOT_READY) {
            // A named person's photo still waits for its embedding: store nobody (R18).
            long h = record(mf, check(FaceCheck.NOT_READY, FaceCheck.REASON_NONE, storable, null, null));
            return facelessMeeting(r.band, h);
        }
        long h = record(mf, check(decisionOf(r.band), FaceCheck.REASON_NONE, storable, r, gallery));
        mf.entries = entries;
        mf.close = settings.close;
        mf.probe = probe;
        mf.storeCrop = storable;
        if (r.band == FaceMatcher.Band.CONFIDENT) {
            String stored;
            try {
                stored = RobotPeopleClient.nameOf(app, r.bestId);
            } catch (IOException e) {
                stored = null;
            }
            if (stored != null) {
                mf.storeId = r.bestId;
                // A nameless record takes the stranger path (KTD10): no id for the conversation.
                return forConversation(MatchAnswer.known(stored.isEmpty() ? null : stored)
                        .withMatch(r.band, r.bestId, r.score, h), stored.isEmpty() ? null : r.bestId);
            }
            // Forgotten since the gallery was read (or the store is gone): someone new.
            Log.i(TAG, "person match: the confident candidate is gone; meeting as someone new");
        }
        // Close and weak alike meet as someone new; a close one carries the name the brain
        // confirms aloud first (U7, KTD6).
        MatchAnswer stranger = MatchAnswer.stranger().withMatch(r.band, r.bestId, r.score, h);
        if (r.band == FaceMatcher.Band.CLOSE && r.bestId != null) {
            stranger = stranger.withConfirm(confirmName(r.bestId));
        }
        return forConversation(stranger, null);
    }

    /**
     * The name "Is that you, {name}?" asks about this candidate (KTD6): the first
     * word of their stored name, or the full stored name when another stored
     * person shares that first name. Null (nothing to confirm) for a nameless
     * record or a store that can't answer.
     */
    private String confirmName(String candidateId) {
        try {
            String stored = RobotPeopleClient.nameOf(app, candidateId);
            if (stored == null || stored.trim().isEmpty()) {
                return null;
            }
            String[] sharing = RobotPeopleClient.idsNamed(app, NameResolver.firstWord(stored.trim()));
            return NameResolver.askedName(stored, sharing == null ? 1 : sharing.length);
        } catch (IOException e) {
            Log.w(TAG, "close match: the candidate's name is unavailable; meeting as someone new: " + e.getMessage());
            return null;
        }
    }

    // ---- confirming and resolving names (face plan U7; KTD6, KTD9, KTD10, KTD12) ----

    @Override
    public String nameIn(String transcript) {
        return NameExtractor.extract(transcript);
    }

    @Override
    public void resolveName(final String name, final long timeoutMs) {
        final int g = resolves.start();
        final MetFace mf = meeting;
        run(new Runnable() {
            @Override
            public void run() {
                resolves.finish(g, resolveNow(mf, name));
            }
        }, resolves, g, Resolved.FAILED);
    }

    /** KTD10 on the robot: the store's ids for the name, scored against this meeting's face. Logs ids and counts only. */
    private Resolved resolveNow(MetFace mf, String name) {
        float[] probe = mf == null ? null : mf.probe;
        if (probe == null || name == null || name.trim().isEmpty()) {
            Log.w(TAG, "resolve: no face from the match to compare; nothing is stored");
            return Resolved.FAILED;
        }
        try {
            String[] found = RobotPeopleClient.idsNamed(app, name);
            List<String> ids = found == null ? Collections.<String>emptyList() : Arrays.asList(found);
            List<FaceMatcher.Entry> entries = mf.entries;
            float close = mf.close;
            NameResolver.Decision d = NameResolver.resolve(name, probe, ids, entries, close);
            Log.i(TAG, "name resolved over " + ids.size() + " stored id(s): " + d);
            switch (d.kind) {
                case JOIN: {
                    String stored = RobotPeopleClient.nameOf(app, d.personId);
                    return stored == null ? Resolved.FAILED : Resolved.join(d.personId, stored);
                }
                case ASK_LAST_NAME:
                    mf.pendingFirst = name;
                    return Resolved.askLastName(name);
                default:
                    return Resolved.newPerson(name);
            }
        } catch (IOException e) {
            Log.w(TAG, "resolve: the people store or the face settings are unavailable: " + e.getMessage());
            return Resolved.FAILED;
        }
    }

    @Override
    public void resolveLastName(final String lastName, final long timeoutMs) {
        final int g = resolves.start();
        final MetFace mf = meeting;
        run(new Runnable() {
            @Override
            public void run() {
                resolves.finish(g, resolveLastNow(mf, lastName));
            }
        }, resolves, g, Resolved.FAILED);
    }

    /** After the last name (KTD10): join the id whose full stored name equals it, else someone new under it. */
    private Resolved resolveLastNow(MetFace mf, String lastName) {
        String first = mf == null ? null : mf.pendingFirst;
        if (first == null || mf.probe == null || lastName == null || lastName.trim().isEmpty()) {
            Log.w(TAG, "resolve last name: no pending first name or face; nothing is stored");
            return Resolved.FAILED;
        }
        try {
            String[] found = RobotPeopleClient.idsNamed(app, first + " " + lastName.trim());
            String full = NameResolver.fullName(first, lastName);
            Map<String, String> stored = new LinkedHashMap<String, String>();
            if (found != null) {
                for (String id : found) {
                    String n = RobotPeopleClient.nameOf(app, id);
                    if (n != null) {
                        stored.put(id, n);
                        if (AnswerParser.same(n, full)) {
                            // afterLastName joins the first match: later names are not needed.
                            break;
                        }
                    }
                }
            }
            NameResolver.Decision d = NameResolver.afterLastName(first, lastName, stored);
            Log.i(TAG, "last name resolved over " + stored.size() + " stored id(s): " + d);
            mf.pendingFirst = null;
            return d.kind == NameResolver.Kind.JOIN ? Resolved.join(d.personId, stored.get(d.personId))
                    : Resolved.newPerson(d.name);
        } catch (IOException e) {
            Log.w(TAG, "resolve last name: the people store is unavailable: " + e.getMessage());
            return Resolved.FAILED;
        }
    }

    @Override
    public Resolved resolved() {
        return resolves.poll();
    }

    @Override
    public void cancelResolve() {
        resolves.cancel();
    }

    /**
     * The meeting's crop and embedding added to this person by id (R5, KTD12): the
     * store refuses an unknown id, so a yes racing a forget re-creates nobody.
     */
    @Override
    public void addPhoto(final String personId, final long timeoutMs) {
        final int g = photoAdds.start();
        final MetFace mf = meeting;
        run(new Runnable() {
            @Override
            public void run() {
                photoAdds.finish(g, addPhotoNow(mf, personId));
            }
        }, photoAdds, g, MatchAnswer.FAILED);
    }

    private MatchAnswer addPhotoNow(MetFace mf, String id) {
        byte[] face = mf == null ? null : mf.storeCrop;
        float[] probe = mf == null ? null : mf.probe;
        if (face == null || probe == null || id == null) {
            Log.w(TAG, "add photo: nothing storable from the match");
            return MatchAnswer.FAILED;
        }
        try {
            int slot = RobotPeopleClient.addPhoto(app, id, face, FaceMatcher.MODEL_ID, probe);
            String stored = RobotPeopleClient.nameOf(app, id);
            mf.storeId = id;
            Log.i(TAG, "photo added to id " + id + " in slot " + slot);
            FaceMatcher.Result r = mf.result;
            return forConversation(MatchAnswer.known(stored == null || stored.isEmpty() ? null : stored)
                    .withMatch(r == null ? null : r.band, id, r == null ? Float.NaN : r.score, mf.checkHandle),
                    stored == null || stored.isEmpty() ? null : id);
        } catch (IOException e) {
            Log.w(TAG, "add photo: the store refused (forgotten meanwhile?) or is unavailable: " + e.getMessage());
            return MatchAnswer.FAILED;
        }
    }

    @Override
    public MatchAnswer photoAdded() {
        return photoAdds.poll();
    }

    @Override
    public void cancelAddPhoto() {
        photoAdds.cancel();
    }

    @Override
    public void checkOutcome(Outcome outcome, String joinedId) {
        checkOutcome(meeting, outcomeCode(outcome), joinedId);
    }

    private static int outcomeCode(Outcome outcome) {
        switch (outcome) {
            case YES:
                return FaceCheck.YES;
            case NO:
                return FaceCheck.NO;
            case JOINED:
                return FaceCheck.JOINED;
            case NO_REPLY:
                return FaceCheck.NO_REPLY;
            default:
                return FaceCheck.NAME_GIVEN;
        }
    }

    @Override
    public void meetingOver() {
        // A check still waiting for an answer ends "without an answer" (KTD8).
        checkOutcome(meeting, FaceCheck.ENDED_WITHOUT_ANSWER, null);
    }

    /**
     * The gallery or the face settings could not be read: an old launcher (its
     * fixed reason, KTD12) or none. Nothing can be matched or stored, so the
     * meeting runs as with no face found.
     */
    private MatchAnswer facelessBecause(String what, IOException e) {
        if (LauncherProtocol.LAUNCHER_TOO_OLD.equals(e.getMessage())) {
            Log.w(TAG, "person match: the launcher predates on-device matching; meeting without storing anything");
        } else {
            Log.w(TAG, "person match: " + what + " unavailable, " + e.getClass().getSimpleName()
                    + "; meeting without storing anything");
        }
        return facelessMeeting(null, -1L);
    }

    /**
     * A meeting with nothing to match or store (R10, R11, R18): someone to talk
     * to as with no face found, carrying the band (NOT_READY, or null) and the
     * face check's handle.
     */
    private MatchAnswer facelessMeeting(FaceMatcher.Band band, long checkHandle) {
        return forConversation(MatchAnswer.faceless().withMatch(band, null, Float.NaN, checkHandle), null);
    }

    /** The gallery as the migration and readiness see it: ids, slots and whether each waits. */
    private static List<FaceMigration.Photo> photosOf(RobotPeople.GalleryPhoto[] gallery) {
        List<FaceMigration.Photo> out = new ArrayList<FaceMigration.Photo>();
        if (gallery != null) {
            for (RobotPeople.GalleryPhoto p : gallery) {
                out.add(new FaceMigration.Photo(p.id, p.slot, p.addedAtMillis, p.isPending(FaceMatcher.MODEL_ID)));
            }
        }
        return out;
    }

    /** Every usable exemplar for the current model: unusable and stale photos never match (KTD11). */
    private static List<FaceMatcher.Entry> entriesOf(RobotPeople.GalleryPhoto[] gallery) {
        List<FaceMatcher.Entry> out = new ArrayList<FaceMatcher.Entry>();
        if (gallery != null) {
            for (RobotPeople.GalleryPhoto p : gallery) {
                if (!p.unusable && p.embedding != null && FaceMatcher.MODEL_ID.equals(p.modelId)) {
                    out.add(new FaceMatcher.Entry(p.id, p.slot, p.embedding));
                }
            }
        }
        return out;
    }

    /** One face check (KTD8): the crop only when the launcher will keep it, the best and runner-up when matched. */
    private static FaceCheck check(int decision, int reason, byte[] cropJpeg, FaceMatcher.Result r,
                                   RobotPeople.GalleryPhoto[] gallery) {
        byte[] kept = cropJpeg != null && cropJpeg.length <= FaceCheck.MAX_CROP_BYTES ? cropJpeg : null;
        if (r == null || !r.hasBest()) {
            return new FaceCheck(decision, reason, kept, "", -1, 0L, r == null ? Float.NaN : r.score, "", Float.NaN,
                    false);
        }
        long addedAt = 0L;
        if (gallery != null) {
            for (RobotPeople.GalleryPhoto p : gallery) {
                if (p.id.equals(r.bestId) && p.slot == r.bestSlot) {
                    addedAt = p.addedAtMillis;
                }
            }
        }
        return new FaceCheck(decision, reason, kept, r.bestId, r.bestSlot, addedAt, r.score, r.runnerUpId,
                r.runnerUpScore, r.nearTie);
    }

    private static int decisionOf(FaceMatcher.Band band) {
        switch (band) {
            case CONFIDENT:
                return FaceCheck.CONFIDENT;
            case CLOSE:
                return FaceCheck.CLOSE;
            case WEAK:
                return FaceCheck.WEAK;
            default:
                return FaceCheck.NOT_READY;
        }
    }

    private static int reasonCode(FaceQuality.Reason reason) {
        switch (reason) {
            case TOO_DARK:
                return FaceCheck.TOO_DARK;
            case TOO_BLURRY:
                return FaceCheck.TOO_BLURRY;
            default:
                return FaceCheck.TOO_SMALL;
        }
    }

    /** Records the check for the Settings page and keeps its handle on the meeting; -1 when it can't. */
    private long record(MetFace mf, FaceCheck check) {
        long h;
        try {
            h = RobotPeopleClient.recordCheck(app, check);
        } catch (IOException e) {
            Log.w(TAG, "face check not recorded: " + e.getMessage());
            h = -1;
        }
        mf.checkHandle = h;
        // A match that outlived its meeting (PR #23 review P1): match() closed that meeting's
        // check before this handle existed, so close it here; the second close is a no-op.
        if (h >= 0 && meeting != mf) {
            checkOutcome(mf, FaceCheck.ENDED_WITHOUT_ANSWER, null);
        }
        return h;
    }

    /** A face check's outcome (KTD8), fire and forget; nothing when the meeting recorded none. */
    private void checkOutcome(final MetFace mf, final int outcome, final String joinedId) {
        final long h = mf == null ? -1 : mf.checkHandle;
        if (h < 0) {
            return;
        }
        run(new Runnable() {
            @Override
            public void run() {
                try {
                    RobotPeopleClient.updateCheck(app, h, outcome, joinedId);
                } catch (IOException e) {
                    Log.w(TAG, "face check outcome not recorded: " + e.getMessage());
                }
            }
        }, null, 0, null);
    }

    // ---- the start-up migration (face plan U6, KTD11) ----

    /** ModeApp.startExplore: embed the stored photos that predate the current model, while the brain allows. */
    void startMigration() {
        runMigration();
    }

    @Override
    public boolean migrated() {
        return migration.settled();
    }

    @Override
    public void faceWork(boolean allowed) {
        faceWorkAllowed = allowed;
        if (allowed) {
            runMigration();
        }
    }

    /** One pass on the worker, unless one is running or none is due (an interrupted pass resumes, a partial one retries). */
    private void runMigration() {
        if (released || !faceWorkAllowed || !migrating.compareAndSet(false, true)) {
            return;
        }
        if (!migration.due(System.currentTimeMillis())) {
            migrating.set(false);
            return;
        }
        run(new Runnable() {
            @Override
            public void run() {
                try {
                    long t0 = System.currentTimeMillis();
                    passGate = null;
                    FaceMigration.Outcome o = migration.pass();
                    Log.i(TAG, "face migration " + o + ": " + migration.embedded() + " embedded, "
                            + migration.unusable() + " marked unusable, " + migration.waiting() + " waiting, in "
                            + (System.currentTimeMillis() - t0) + " ms");
                } finally {
                    migrating.set(false);
                }
            }
        }, null, 0, null);
    }

    /**
     * A stored crop through the pipeline in migration mode (KTD11): detect, align,
     * brighten when dim, never reject, embed. NO_FACE when the finder sees no face
     * in it; null when a model is unavailable, so the photo waits rather than being
     * marked unusable.
     */
    private float[] embedStored(byte[] jpeg) {
        FaceCropper.Located found = cropper.locate(jpeg, WHOLE_PHOTO);
        if (found == null) {
            return cropper.loaded() ? FaceMigration.NO_FACE : null;
        }
        int[] aligned = FaceAlign.align(found.argb, found.frameW, found.frameH, found.face.landmarks);
        if (aligned == null) {
            return FaceMigration.NO_FACE;
        }
        FaceQuality.Verdict v = FaceQuality.check(found.faceWidth(), aligned, FaceAlign.SIDE, FaceAlign.SIDE,
                passGate(), true);
        return embedder.embed(v.brighten(aligned));
    }

    /** migrationGate(), fetched once per migration pass (on the pass's first
     * found face), not once per photo. Migration worker only. */
    private FaceQuality.Thresholds passGate() {
        if (passGate == null) {
            passGate = migrationGate();
        }
        return passGate;
    }

    /** The dim level from the launcher's face settings, else the defaults (nothing is rejected here). */
    private FaceQuality.Thresholds migrationGate() {
        try {
            FaceSettings s = RobotSettingsClient.fetchFaceSettings(app);
            return FaceQuality.Thresholds.of(s);
        } catch (IOException e) {
            return FaceQuality.Thresholds.DEFAULTS;
        }
    }

    /** The people store's migration calls (KTD11): one photo per call, embeddings tagged with the model. */
    private final class MigrationStore implements FaceMigration.Store {
        @Override
        public List<FaceMigration.Photo> gallery() throws IOException {
            return photosOf(RobotPeopleClient.gallery(app));
        }

        @Override
        public byte[] photo(String id, int slot) throws IOException {
            return RobotPeopleClient.photo(app, id, slot);
        }

        @Override
        public boolean setEmbedding(String id, int slot, long addedAtMillis, float[] embedding) throws IOException {
            return RobotPeopleClient.setEmbedding(app, id, slot, addedAtMillis, FaceMatcher.MODEL_ID, embedding);
        }

        @Override
        public boolean markUnusable(String id, int slot, long addedAtMillis) throws IOException {
            return RobotPeopleClient.markUnusable(app, id, slot, addedAtMillis);
        }
    }

    @Override
    public void touch() {
        // The id the current meeting matched or stored; a late match cannot set it.
        final MetFace met = meeting;
        final String id = met == null ? null : met.storeId;
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
                strangerLines.finish(g, forConversation(a, null));
            }
        }, strangerLines, g, MatchAnswer.FAILED);
    }

    @Override
    public MatchAnswer linesAnswer() {
        return strangerLines.poll();
    }

    @Override
    public void listen(final long maxMs) {
        final EarsAdapter s = session;
        if (s != null && s.isOpen()) {
            // One microphone capture per device (KTD1): with the session open the reply comes
            // through it; a listen that hears nothing in maxMs is silence, as the one-shot's is.
            earsListen(s, maxMs, Float.NaN);
            return;
        }
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
    public void remember(final String name, final long timeoutMs) {
        final int g = remembers.start();
        final MetFace mf = meeting;
        run(new Runnable() {
            @Override
            public void run() {
                remembers.finish(g, keep(mf, name, timeoutMs));
            }
        }, remembers, g, Answer.failed());
    }

    /** Store the face and its embedding under the name (a reply with a name is the consent,
     * R19), then ask for the text-only "I'll remember you" line. Without a storable face
     * (faceless, rejected, not ready: R11, R18) or a name nothing is stored and the line
     * makes no promise, whatever the brain asked for. */
    private Answer keep(MetFace mf, String name, long timeoutMs) {
        byte[] face = mf == null ? null : mf.storeCrop;
        float[] probe = mf == null ? null : mf.probe;
        if (face == null || probe == null) {
            // Nothing to store: never promise to remember them.
            Log.w(TAG, "remember: nothing storable from the match; a hello without the promise");
            return hello(name, timeoutMs);
        }
        if (name == null || name.trim().isEmpty()) {
            // The store refuses a nameless face (R19); don't even ask it.
            Log.w(TAG, "remember: no name to store the face under; a hello without the promise");
            return hello(null, timeoutMs);
        }
        try {
            String id = RobotPeopleClient.addPerson(app, face, name, FaceMatcher.MODEL_ID, probe);
            mf.storeId = id;
            checkOutcome(mf, FaceCheck.NEW_PERSON, id);
            Log.i(TAG, "remembered a new person with a name, id " + id);
        } catch (IOException e) {
            Log.w(TAG, "remember: the people store refused or is unavailable: " + e.getMessage());
            return Answer.failed();
        }
        long t0 = System.currentTimeMillis();
        // Text only (face plan U6): no face leaves the robot outside the conversation's opener
        // and the recently-met check.
        ClaudeApi.MessageResult r = claude.messages(fetchSettings(), ExplorePrompts.SYSTEM,
                textOnly(ExplorePrompts.rememberAsk(name)), ExplorePrompts.REMEMBER_SCHEMA, (int) timeoutMs);
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
     * Each meeting's source frame is also kept under face-frames/ with a
     * timestamped name, the newest FACE_FRAMES_KEPT, for the bench (face plan U9).
     */
    private void debugFace(byte[] frameJpeg, Detection personBox, FaceCropper.Located found) {
        if (!Log.isLoggable(FACE_DEBUG_TAG, Log.DEBUG)) {
            return;
        }
        File dir = app.getFilesDir();
        write(new File(dir, LAST_FACE_SRC), frameJpeg);
        keepFrame(new File(dir, FACE_FRAMES), frameJpeg);
        File face = new File(dir, LAST_FACE);
        if (found != null) {
            write(face, found.crop.face);
        } else if (face.exists() && !face.delete()) {
            Log.w(FACE_DEBUG_TAG, "could not delete the previous " + LAST_FACE);
        }
        // Geometry only: never image data, and never the box's label (Claude's description of a person).
        int[] square = found == null ? null : found.crop.square;
        Log.d(FACE_DEBUG_TAG, String.format(Locale.US, "person box [%.2f,%.2f,%.2f,%.2f]",
                personBox.x0, personBox.y0, personBox.x1, personBox.y1) + (square != null
                ? ": face at " + Arrays.toString(square) + " (left, top, side px)"
                : ": no face found"));
    }

    /** One source frame into the rolling set: a timestamped name, and the oldest beyond FACE_FRAMES_KEPT deleted. */
    private static void keepFrame(File frames, byte[] frameJpeg) {
        if (!frames.isDirectory() && !frames.mkdirs()) {
            Log.w(FACE_DEBUG_TAG, "could not make " + FACE_FRAMES);
            return;
        }
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)
                .format(new java.util.Date());
        write(new File(frames, stamp + ".jpg"), frameJpeg);
        File[] all = frames.listFiles();
        if (all == null || all.length <= FACE_FRAMES_KEPT) {
            return;
        }
        // The timestamped names sort oldest first.
        Arrays.sort(all);
        for (int i = 0; i < all.length - FACE_FRAMES_KEPT; i++) {
            if (!all[i].delete()) {
                Log.w(FACE_DEBUG_TAG, "could not delete an old frame in " + FACE_FRAMES);
            }
        }
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
