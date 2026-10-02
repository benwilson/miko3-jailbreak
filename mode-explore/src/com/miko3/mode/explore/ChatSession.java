package com.miko3.mode.explore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The conversation beside the brain (meeting plan U8; R4, R10-R21; KTD7-KTD12,
 * KTD14): the CHAT states, driven by the brain's tick inside inStop() with the
 * camera open and the detector parked. Plain Java with no android.* imports and
 * no clock of its own: the brain hands it the time, the port and a
 * Host for the eyes, clips, gauges, trace and looks, and it answers with its
 * state. It keeps every word it hears and says out of the trace.
 *
 *   CHAT_THINK   one turn request out (the opener the moment the match answers,
 *                then each reply); 5 s, one 3 s retry on an unreachable answer,
 *                then the local sign-off ends it; a refusal is the deflection clip
 *   CHAT_SPEAK   the line, cut to the sentence cap, handed to the voice with the
 *                camera open and no quiet wait (KTD7); or a clip in its window
 *   CHAT_LISTEN  the only state where looks run: the conversation listen, the 4 s
 *                unanswered timer, the single walked-off look after the first
 *                unanswered listen, the newcomer glance and "one sec" at the
 *                listen's end, goodbye and forget-me on the speaker's side;
 *                in a conversation that opened with no usable face, the face
 *                retries' looks (robot 2026-10-01: faceStep)
 *   CHAT_NOTES   the buffered notes deltas drained through the People store,
 *                then the brain resumes roaming on a leg turned away
 *
 * The prefix the port builds (persona snapshot, notes as data) is taken from the
 * match answer once, so an edit to the persona is heard in the next conversation
 * (KTD11). ends_conversation is advisory: the conversation ends only on a goodbye,
 * a walk-off, two unanswered listens, the charger, a spent turn budget or a
 * sensor stall the brain reports (KTD9).
 */
final class ChatSession {

    /**
     * The states (KTD7): thinking (the turn request), speaking (the deaf window),
     * listening (the only state where looks run) and the notes merge at the end.
     * The turn toward the voice and the look that decides (KTD4) are the brain's
     * own CUE states, before the session exists.
     */
    enum State { CHAT_THINK, CHAT_SPEAK, CHAT_LISTEN, CHAT_NOTES }

    /** What the session needs from the brain: eyes, clips, gauges, the trace and the camera. */
    interface Host {
        void eyes(ExploreBrain.EyeState state, ExploreBrain.Direction gaze);

        /** A local clip (KTD12), after the session has opened its clip window on the port. */
        void playClip(String group);

        void stamp(ExploreBrain.Gauges.Stage stage, long atMs);

        void count(ExploreBrain.Gauges.Counter counter);

        /** The brain's trace: fixed text and counts only, never a word heard or said. */
        void note(String message);

        /** Whether a camera look may run now: the lease held and the camera available (KTD7). */
        boolean looksAllowed();

        /** Unpark the detector for one look (true), or park it again (false). */
        void wantLook(boolean want);

        /** The camera's newest look, or null. */
        ExploreBrain.Look look();

        /** "A face turned toward him" in this look (KTD4). */
        boolean facing(ExploreBrain.Look look);

        /**
         * Whether a look for a face retry may run now (robot 2026-10-01): the walked-off
         * look's camera rule (the lease and the camera, KTD7) without its listen check.
         */
        boolean faceLooksAllowed();

        /**
         * The charger latch (KTD6). Owner 2026-10-03: a conversation it arrives in goes on;
         * the brain drives no resume leg after it and docks once it is over. Read for the
         * feedback's context.
         */
        boolean charger();

        /**
         * Owner 2026-10-03: why the look tool can't take a frame now (bathroom privacy, do
         * not disturb, no lease or camera), as a few words Claude can read; null when it can.
         */
        String lookBlocked();

        /** Owner 2026-10-03: what robot_status and places answer with, as the brain sees it now. */
        CuriosityPort.ToolFacts toolFacts();

        /**
         * Owner 2026-10-02: the next move of the search for a caller during the conversation.
         * TURNING: a short turn toward the plan's next bearing started; LOOK: he faces a bearing
         * not yet looked at; DONE: the plan is spent (or the caller is faced); HELD: he cannot
         * turn now (no fresh reading, a hazard, no wheels, the charger, turns that do nothing).
         * Called only while he is neither speaking nor hearing an answer.
         */
        Seek seek(long now);

        /** The bearing seek() said LOOK at has been looked at: the plan moves on. */
        void seekLooked();

        /** A search turn is under way. */
        boolean seekTurning();

        /** Stop a search turn now: he is about to speak, or an answer has started. */
        void holdStill();

        /** The caller in this look by the call's person rule, or null. */
        Detection callerIn(ExploreBrain.Look look);

        /** The caller was found in this box: the search ends and seek() turns to centre them. */
        void faceCaller(Detection box);
    }

    /** The brain's answer to Host.seek (owner 2026-10-02). */
    enum Seek { TURNING, LOOK, DONE, HELD }

    // ---- fixed local lines (KTD12): the clip groups and the on-device voice's templates ----
    static final String CLIP_SIGN_OFF = "sign-off";
    static final String CLIP_ONE_SEC = "one-sec";
    static final String CLIP_DEFLECT = "deflect";
    static final String CLIP_NOTHING_KEPT = "nothing-kept";
    /** What the deflection clip says, as the transcript records it. */
    static final String DEFLECT_SAID = "Nice try, but no.";
    /** The prompt-free line when stripping a repeated question leaves nothing (KTD9). */
    static final String CANNED_NO_QUESTION = "Fair enough.";
    /** The forget confirmation, with the stored name (R18): spoken through the on-device voice. */
    static final String CONFIRM_FORGET = "Forget you, {name}?";
    /**
     * A known person's greeting when no line came (face plan U6, KTD7): spoken
     * through the on-device voice like CONFIRM_FORGET, before the sign-off when
     * turn 1 fails, and by the brain's degraded ladder when lines() fails.
     */
    static final String LOCAL_GREETING = "Hi, {name}!";
    /**
     * A close match's question and the last-name question (face plan U7, KTD6):
     * fixed lines spoken through the on-device voice, never Claude's. {name} is
     * the stored name the robot fills in.
     */
    static final String CONFIRM_QUESTION = "Is that you, {name}?";
    static final String LAST_NAME_QUESTION = "And your last name?";
    static final String FORGOTTEN = "Done. I've forgotten you.";
    static final String KEPT = "Okay, keeping you.";
    static final String FORGET_FAILED = "That didn't work; I still remember you.";
    /** Owner 2026-10-02: a call's conversation, after an answer that ended without words: once, through the on-device voice. */
    static final String DIDNT_CATCH = "Sorry, I didn't catch that?";
    /**
     * Owner 2026-10-02: the polite end of a conversation nobody is having with him (turns not
     * said to him, or no message said to him for chatNoReplyMs), through the on-device voice.
     */
    static final String LEAVE_THEM = "I'll leave you to it.";
    /** The whole utterance that confirms a forget (KTD9); anything else, a negation included, is a no. */
    static final String[] AFFIRMATIVES = {"yes", "yeah", "yep", "do it"};
    private static final String[] GOODBYES = {"bye", "goodbye", "see you", "see ya", "catch you later", "later miko",
            "gotta go", "got to go", "have to go", "i should go", "id better go", "i d better go", "take care",
            "so long", "talk later", "talk to you later"};
    private static final String[] FORGET_ME = {"forget me", "forget about me", "delete me", "wipe me", "erase me"};
    private static final String[] NOT_FORGET = {"dont forget", "do not forget", "never forget", "wont forget",
            "will not forget"};

    private enum Phase { NONE, WAIT_TURN, RESOLVING, WAIT_CLIP, SAYING, CLIP_THEN_LISTEN, CLIP_THEN_END, LISTENING,
        LOOKING, FORGETTING, PERSISTING }

    /** Which answer the resolver owes (face plan U7, KTD6): a spoken name's, or the last name's. */
    private enum Resolving { NONE, NAME, LAST_NAME }

    private final ExploreTuning tuning;
    private final CuriosityPort port;
    private final Host host;
    private State state;
    private Phase phase = Phase.NONE;

    // ---- who he is talking to ----
    private String persona = "";
    private String name;
    private String personId;
    private String notes;
    private boolean faceless;
    private final Set<String> asked = new HashSet<String>();
    private final List<CuriosityPort.Exchange> transcript = new ArrayList<CuriosityPort.Exchange>();
    /** The notes deltas not yet persisted (KTD10: drain-on-persist). */
    private final List<String> buffer = new ArrayList<String>();

    // ---- the turn ----
    private String heard;
    private CuriosityPort.TurnRequest request;
    private int attempt;
    private long turnDeadline;
    /**
     * Robot 2026-10-01: a turn held back by Claude's rate-limit pause, sent at turnHeldUntil
     * with heldBudget; heldSince is when this turn first waited (the whole wait stays within
     * chatPauseWaitMs) and secSaid that its "one sec" has played.
     */
    private boolean turnHeld;
    private long turnHeldUntil;
    private long heldBudget;
    private long heldSince;
    private boolean secSaid;
    private boolean reRequested;
    private boolean opener = true;
    /** Turn 1 failed for a known person: the local greeting is playing, then the sign-off (U6, KTD7). */
    private boolean greetedLocally;
    private boolean signOffAfterLine;
    private int turns;
    private int repeats;

    // ---- the line and the clips ----
    private String pendingLine;
    private long sayUntil;
    private long clipUntil = ExploreBrain.NEVER;
    private boolean endAfterLine;
    /** The pending line is a turn's (it joins the transcript), not a forget template. */
    private boolean lineIsTurn;
    private boolean confirmingForget;

    // ---- listening ----
    private int unanswered;
    private long listenDeadline;
    /** Robot 2026-10-01: this listen's deadline was moved to the answer hold once already. */
    private boolean answerHeld;
    /** Robot 2026-10-02: the provisional answer this listen already started a turn on (or passed over), or null. */
    private String speculated;
    private long lookFrom;
    private long lookDeadline;
    private ExploreBrain.Direction newcomerSide;
    private boolean newcomerPending;
    private int newcomers;

    // ---- who they are (face plan U7; KTD6, KTD10, R6, R7, R20, R21) ----
    private Resolving resolving = Resolving.NONE;
    private long resolveDeadline;
    /** The turn's line, held while the name it came with is resolved; dropped for the last-name question. */
    private String heldLine;
    /** A name given on a first answer that was then re-requested for a repeat. */
    private String heldGiven;
    /** The first name waiting for its last name, the reply it came in, and the last-name reply. */
    private String pendingFirst;
    private String nameReply;
    private String lastNameReply;
    private boolean askingLastName;
    /** A first name whose last name never came (R21): not resolved or asked again this conversation. */
    private String settledName;
    /** The meeting's face check still waits for its outcome (the brain has not recorded one). */
    private boolean checkOpen = true;
    private boolean photoPending;
    private long photoDeadline;
    private String photoFor;

    // ---- the face retries (robot 2026-10-01) ----
    /**
     * The conversation opened with no usable face: the opener invited them down to his
     * level instead of asking the name (TurnRequest.faceless), and a fresh look's face is
     * checked again up to chatFaceTries times. faceSeen: a retry found a usable face, so an
     * unnamed person may now be asked their name.
     */
    private boolean openedFaceless;
    private boolean faceSeen;
    private int faceTries;
    private boolean faceLooking;
    private long faceLookFrom;
    private long faceLookDeadline;
    private boolean faceMatching;
    private long faceMatchDeadline;
    private long listenStartedAt = ExploreBrain.NEVER;
    private long faceTryEndedAt = ExploreBrain.NEVER;
    /** A name given while faceless, checked against the store once a retry found a usable face. */
    private boolean heldResolving;
    private long heldResolveDeadline;
    /** The held name's resolver asked for the last name: asked in place of the next turn's line. */
    private boolean lastNameNext;
    /** The face retry's box when the look has no person box: the whole frame. */
    private static final Detection WHOLE_FRAME = new Detection("person", 1f, 0f, 0f, 1f, 1f);

    // ---- a call's conversation (owner 2026-10-02) ----
    /** Opened on a call, before he had seen them: not seeing them never ends it. */
    private boolean called;
    /** Looking for the caller between utterances; centring: turning to face the one found. */
    private boolean seeking;
    private boolean centring;
    /** The face look in flight is a search look: nobody in it spends no face try. */
    private boolean seekLooking;
    private int seekLooks;
    /** The person box a search look found, while its face is checked. */
    private Detection seekFound;
    /** He knows he can't see them: the next turn invites them down to his level, once. */
    private boolean cantSeeDue;
    /** This run of unanswered listens has had its "didn't catch that". */
    private boolean reasked;

    // ---- turns not said to him, and instructions (owner 2026-10-02) ----
    /** The run of unanswered listens before the message being answered: a reply not said to him adds to it. */
    private int unansweredBefore;
    /** When the message being answered was heard, and when the last one said to him was (or the opening). */
    private long heardAt = ExploreBrain.NEVER;
    private long lastAddressedAt;
    /** An instruction he was given (the line said it), for the brain once the conversation is over. */
    private CuriosityPort.Action action = CuriosityPort.Action.NONE;
    private String actionTarget;

    // ---- the store ----
    private boolean keepPending;
    private long keepDeadline;
    private boolean persistWanted;
    private boolean deltaInFlight;
    private long deltaDeadline;
    private long forgetDeadline;
    private int persisted;

    // ---- a turn's tool round (owner 2026-10-03) ----
    /** This turn's deadline has been moved for its tool round. */
    private boolean toolRound;
    /** The tool round's preamble is being said: the line waits for it, until preambleUntil. */
    private boolean preambleSaying;
    private long preambleUntil;
    /** The look tool's frame is taken once the preamble is said (KTD7: the detector parks while he speaks). */
    private boolean lookAfterPreamble;
    /** The look tool's fresh frame is wanted: one captured from toolLookFrom, until toolLookDeadline. */
    private boolean toolLooking;
    private long toolLookFrom;
    private long toolLookDeadline;

    // ---- how it ends ----
    /** Opened on the charger (hey-miko plan KTD5, R11): for the feedback's context. */
    private boolean startedOnCharger;
    private boolean finished;
    private boolean signedOff;
    private boolean walkedOff;
    private boolean ending;

    ChatSession(ExploreTuning tuning, CuriosityPort port, Host host) {
        this.tuning = tuning;
        this.port = port;
        this.host = host;
        this.state = State.CHAT_THINK;
    }

    State state() {
        return state;
    }

    boolean finished() {
        return finished;
    }

    /** True once the sign-off clip has been played (R11). */
    boolean signedOff() {
        return signedOff;
    }

    /** True when the walked-off look found nobody. */
    boolean walkedOff() {
        return walkedOff;
    }

    /** The store id the notes belong to, or null when the conversation runs unnamed (R19). */
    String personId() {
        return personId;
    }

    boolean named() {
        return personId != null;
    }

    int turns() {
        return turns;
    }

    int repeats() {
        return repeats;
    }

    /** Owner 2026-10-02: the instruction this conversation ended on, or NONE. */
    CuriosityPort.Action action() {
        return action;
    }

    /** Its target as Claude gave it (a short name or place), or null. Never traced. */
    String actionTarget() {
        return actionTarget;
    }

    // ---- entry points from the brain ----

    /**
     * The conversation opens on the match answer (KTD9): the persona snapshot,
     * the person's name, id, notes and questions asked, and turn 1 goes out at
     * once. faceless: no face was cut out, so nothing can ever be stored.
     */
    void start(long now, CuriosityPort.MatchAnswer a, boolean faceless) {
        start(now, a, faceless, true, null);
    }

    /**
     * As start(), after the brain's CONFIRM (face plan U7, KTD6): checkOpen is
     * false when the brain already recorded the check's outcome, and settled is
     * a first name whose last name never came, which is not asked about again.
     */
    void start(long now, CuriosityPort.MatchAnswer a, boolean faceless, boolean checkOpen, String settled) {
        open(a, faceless, checkOpen, settled);
        lastAddressedAt = now;
        requestTurn(now, null);
    }

    /**
     * A conversation a call opened at once (owner 2026-10-02), before he has seen them: faceless,
     * with no face check behind it. message is what they said with the wake word ("" for a bare
     * call): turn 1 answers it, else the opener greets them with a question. search: he looks for
     * them between utterances (off on the charger and wherever he cannot turn). quietUntil: the
     * answer clip's window, which no line plays over.
     */
    void startCall(long now, CuriosityPort.MatchAnswer a, String message, boolean search, long quietUntil) {
        called = true;
        seeking = search;
        clipUntil = Math.max(clipUntil, quietUntil);
        open(a, true, false, null);
        lastAddressedAt = now;
        if (search) {
            host.note("looking for the caller during the conversation");
        }
        requestTurn(now, message == null || message.trim().isEmpty() ? null : message.trim());
    }

    private void open(CuriosityPort.MatchAnswer a, boolean faceless, boolean checkOpen, String settled) {
        // Robot 2026-10-02: a late notes update left over from another conversation is never this one's.
        while (port.lateNotes() != null) {
            // dropped
        }
        // Owner 2026-10-02: so is late feedback; whoever gave it, it is not this person's.
        while (port.lateFeedback() != null) {
            // dropped
        }
        this.checkOpen = checkOpen;
        this.settledName = settled;
        startedOnCharger = host.charger();
        this.faceless = faceless;
        String storedName = a.name == null ? "" : a.name.trim();
        openedFaceless = faceless && storedName.isEmpty();
        persona = a.persona == null ? "" : a.persona;
        String stored = a.name == null ? "" : a.name.trim();
        // A known match with an empty name takes the stranger path (KTD10): unnamed, the old id never written.
        name = stored.isEmpty() ? null : stored;
        personId = name == null ? null : a.personId;
        notes = personId == null ? null : a.notes;
        if (personId != null) {
            for (String q : a.questionsAsked) {
                String n = normalize(q);
                if (!n.isEmpty()) {
                    asked.add(n);
                }
            }
        }
        host.note(name == null ? "a conversation with someone unnamed" : "a conversation with someone known ("
                + asked.size() + " questions on record)");
    }

    /**
     * Owner 2026-10-02: feedback about the robot goes to the launcher's feedback log, from
     * the stored person he is talking to (null: someone unknown, logged as "someone"),
     * with a few words of where it happened. Only the feedback: never a line or a transcript.
     */
    private void passOn(CuriosityPort.Feedback f) {
        port.feedback(personId, f, feedbackContext());
        host.note("feedback (" + f.kind + ") passed on to the feedback log");
    }

    /** Where the feedback was given, in a few plain words. */
    private String feedbackContext() {
        String where = called ? "in a call's conversation" : "in a conversation";
        return startedOnCharger || host.charger() ? where + ", while docked" : where;
    }

    /** One brain tick in a CHAT state. */
    void step(long now) {
        if (finished) {
            return;
        }
        // Robot 2026-10-02: a streamed turn's notes, which came after its line, join the buffer.
        for (String late = port.lateNotes(); late != null; late = port.lateNotes()) {
            buffer.add(late);
        }
        // Owner 2026-10-02: and its feedback about him is passed on, after its line went.
        for (CuriosityPort.Feedback late = port.lateFeedback(); late != null; late = port.lateFeedback()) {
            passOn(late);
        }
        keepStep(now);
        photoStep(now);
        faceStep(now);
        notesStep(now);
        switch (state) {
            case CHAT_THINK:
                thinkStep(now);
                break;
            case CHAT_SPEAK:
                speakStep(now);
                break;
            case CHAT_LISTEN:
                listenStep(now);
                break;
            case CHAT_NOTES:
                if (!deltaInFlight && !keepPending && !photoPending && !heldResolving && !faceMatching
                        && !port.turnTailPending() && (!persistWanted || buffer.isEmpty() || personId == null)) {
                    finished = true;
                    host.note("conversation over after " + turns + " turn(s), " + persisted + " note delta(s) kept");
                }
                break;
            default:
                break;
        }
    }

    /** A strong utterance outside the newcomer angle, held by the brain (KTD8): a glance and "one sec" at the listen's end. */
    void newcomer(ExploreBrain.Direction side) {
        newcomerSide = side;
        newcomerPending = true;
        newcomers++;
        host.note("a newcomer called (" + newcomers + " so far): held until the conversation ends");
    }

    /** A sensor stall past the grace (KTD7): the local sign-off ends it. */
    void endWithSignOff(long now, String why) {
        if (finished || ending) {
            return;
        }
        host.note("ending the conversation: " + why);
        signOff(now);
    }

    // ---- CHAT_THINK ----

    private void requestTurn(long now, String heardText) {
        state = State.CHAT_THINK;
        phase = Phase.WAIT_TURN;
        heard = heardText;
        attempt = 1;
        reRequested = false;
        request = turnRequest(heardText);
        cantSeeDue = false;
        turnHeld = false;
        secSaid = false;
        heldSince = -1;
        toolRound = false;
        preambleSaying = false;
        lookAfterPreamble = false;
        dropToolLook();
        host.eyes(ExploreBrain.EyeState.THINKING, null);
        if (opener) {
            host.stamp(ExploreBrain.Gauges.Stage.LINE_REQUESTED, now);
        }
        sendTurn(now, tuning.turnBudgetMs);
    }

    /**
     * Sends the turn with this budget, unless Claude is paused after a 429 or 529 (robot
     * 2026-10-01): a pause the turn can wait out within chatPauseWaitMs gets "one sec" (once
     * per turn) and holds the turn until it ends; a longer one ends the conversation politely.
     */
    private void sendTurn(long now, long budget) {
        long paused = port.claudePausedMs();
        if (paused > 0) {
            if (heldSince < 0) {
                heldSince = now;
            }
            if (now + paused - heldSince > tuning.chatPauseWaitMs) {
                turnHeld = false;
                host.note("Claude is paused for " + paused + " ms: too long to wait, the sign-off ends the conversation");
                turnFailed(now);
                return;
            }
            host.note("Claude is paused for " + paused + " ms: one sec, and the turn waits");
            if (!secSaid) {
                secSaid = true;
                openClip(now, CLIP_ONE_SEC);
            }
            turnHeld = true;
            turnHeldUntil = now + paused;
            heldBudget = budget;
            return;
        }
        turnHeld = false;
        turnDeadline = now + budget;
        port.turn(request, budget);
    }

    /** The turn request for what was heard, as it stands now; building it changes nothing. */
    private CuriosityPort.TurnRequest turnRequest(String heardText) {
        return new CuriosityPort.TurnRequest(persona, name, notes, window(), heardText)
                .face(openedFaceless, faceSeen && name == null).call(called, cantSeeDue).withFacts(host.toolFacts());
    }

    /**
     * Robot 2026-10-02: the launcher's provisional answer (its recogniser endpointed inside
     * the answer, about 0.8 s after the last word) starts the turn the final answer would
     * ask for, while the 2 s silence rule still runs. Only words that would go straight to
     * a turn qualify (no goodbye, forget-me, last name or forget confirmation), each once.
     * The port uses it only if the final answer makes exactly the same request; its line is
     * never spoken before that.
     */
    private void speculate(String words) {
        String text = words == null ? "" : words.trim();
        if (text.isEmpty() || text.equals(speculated)) {
            return;
        }
        speculated = text;
        if (askingLastName || confirmingForget || goodbye(text) || forgetMe(text)) {
            return;
        }
        host.note("a provisional answer: its turn starts early");
        port.speculateTurn(turnRequest(text), tuning.turnBudgetMs);
    }

    /** The transcript window (KTD9): the last transcriptWindow exchanges, oldest dropped first. */
    private List<CuriosityPort.Exchange> window() {
        int from = Math.max(0, transcript.size() - tuning.transcriptWindow);
        return new ArrayList<CuriosityPort.Exchange>(transcript.subList(from, transcript.size()));
    }

    private void thinkStep(long now) {
        if (phase == Phase.RESOLVING) {
            resolveStep(now);
            return;
        }
        if (phase != Phase.WAIT_TURN) {
            return;
        }
        if (turnHeld) {
            if (now >= turnHeldUntil) {
                sendTurn(now, heldBudget);
            }
            return;
        }
        if (preambleSaying && (port.sayFinished() || now >= preambleUntil)) {
            preambleSaying = false;
        }
        toolStep(now);
        if (preambleSaying || toolLooking) {
            return;
        }
        CuriosityPort.Turn t = port.turnAnswer();
        if (t == null) {
            if (now < turnDeadline) {
                return;
            }
            port.cancelTurn();
            retryOrSignOff(now, "no line in " + (attempt == 1 ? tuning.turnBudgetMs : tuning.turnRetryMs) + " ms");
            return;
        }
        switch (t.status) {
            case LINE:
                onLine(now, t);
                break;
            case REFUSED:
                host.note("the line was refused: the deflection, and the conversation goes on");
                transcript.add(new CuriosityPort.Exchange(heard, DEFLECT_SAID));
                playClip(now, CLIP_DEFLECT, Phase.CLIP_THEN_LISTEN);
                break;
            case UNREACHABLE:
                retryOrSignOff(now, "the endpoint was unreachable or busy");
                break;
            default:
                host.note("the turn failed: the local sign-off ends the conversation");
                turnFailed(now);
                break;
        }
    }

    /**
     * Owner 2026-10-03: the turn's tool round. Its ask (once) moves the deadline out by
     * toolRoundMs, says the preamble (the line then waits for it to finish), and for a look
     * asks the camera for a frame captured from now: the port gets it with the detector's
     * labels, or why not (bathroom privacy and do not disturb first; no frame in
     * toolLookMs). Nothing heard or said is traced, only that a round ran.
     */
    private void toolStep(long now) {
        CuriosityPort.ToolAsk ask = port.toolAsk();
        if (ask != null) {
            if (!toolRound) {
                toolRound = true;
                turnDeadline = Math.max(turnDeadline, now + tuning.toolRoundMs);
            }
            host.note("a tool round" + (ask.look ? " with a look" : "") + (ask.preamble != null ? ", said first" : "")
                    + ": the turn may take " + tuning.toolRoundMs + " ms");
            if (ask.preamble != null) {
                // As every line (KTD7): still, and the detector parked while he speaks.
                host.holdStill();
                dropFaceLook(true);
                preambleSaying = true;
                preambleUntil = now + ChatTools.PREAMBLE_WAIT_MS;
                port.say(ask.preamble);
            }
            lookAfterPreamble = ask.look;
        }
        if (lookAfterPreamble && !preambleSaying) {
            lookAfterPreamble = false;
            startToolLook(now);
        }
        if (!toolLooking) {
            return;
        }
        ExploreBrain.Look look = host.look();
        if (look != null && look.frameMs >= toolLookFrom && look.jpeg != null) {
            dropToolLook();
            // The fresh frame itself may have just put him in bathroom privacy: nothing goes then.
            String blocked = host.lookBlocked();
            host.note(blocked == null ? "the tool's look: a fresh frame for Claude" : "the tool's look: refused");
            port.lookAnswer(blocked == null ? CuriosityPort.LookResult.of(look.jpeg, labels(look))
                    : CuriosityPort.LookResult.refused(blocked));
        } else if (now >= toolLookDeadline) {
            dropToolLook();
            host.note("the tool's look: no frame in " + tuning.toolLookMs + " ms");
            port.lookAnswer(CuriosityPort.LookResult.refused("his camera gave no picture in time"));
        }
    }

    private void startToolLook(long now) {
        String blocked = host.lookBlocked();
        if (blocked != null) {
            host.note("the tool's look: refused (privacy, do not disturb or no camera)");
            port.lookAnswer(CuriosityPort.LookResult.refused(blocked));
            return;
        }
        toolLooking = true;
        toolLookFrom = now;
        toolLookDeadline = now + tuning.toolLookMs;
        host.wantLook(true);
    }

    /** The tool's look, if one is wanted, is no longer: the detector parks unless a face look still runs. */
    private void dropToolLook() {
        if (!toolLooking) {
            return;
        }
        toolLooking = false;
        if (!faceLooking && phase != Phase.LOOKING) {
            host.wantLook(false);
        }
    }

    /** The look's labels, each once, in box order. */
    private static List<String> labels(ExploreBrain.Look look) {
        List<String> out = new ArrayList<String>();
        if (look.detections != null) {
            for (Detection d : look.detections) {
                if (!out.contains(d.label)) {
                    out.add(d.label);
                }
            }
        }
        return out;
    }

    /**
     * A failed turn ends the conversation with the sign-off (KTD9). When it is
     * turn 1 for a known person, nothing has greeted them yet: the local greeting
     * plays first (face plan U6, KTD7).
     */
    private void turnFailed(long now) {
        if (opener && name != null && !greetedLocally) {
            greetedLocally = true;
            host.note("turn 1 failed for someone known: the local greeting, then the sign-off");
            signOffAfterLine = true;
            speak(now, ClaudeReplies.fill(LOCAL_GREETING, name), false);
            return;
        }
        signOff(now);
    }

    /** One retry on a timed-out or unreachable turn (KTD9), then the local sign-off. */
    private void retryOrSignOff(long now, String why) {
        if (attempt == 1 && tuning.turnRetryMs > 0) {
            attempt = 2;
            // Robot 2026-10-01: never straight back into a rate limit; sendTurn waits out a short pause.
            host.note("turn attempt 1 failed (" + why + "): "
                    + (port.claudePausedMs() > 0 ? "a retry once Claude's pause is over" : "retrying once"));
            sendTurn(now, tuning.turnRetryMs);
            return;
        }
        host.note("turn attempt " + attempt + " failed (" + why + "): the local sign-off ends the conversation");
        turnFailed(now);
    }

    /** The robot's side of KTD9: the name, the sentence cap, the repeat check, the notes delta, then the line. */
    private void onLine(long now, CuriosityPort.Turn t) {
        if (!t.addressed) {
            notAddressed(now);
            return;
        }
        if (heard != null && heardAt != ExploreBrain.NEVER) {
            lastAddressedAt = Math.max(lastAddressedAt, heardAt);
        }
        String given = validName(t.nameGiven);
        if (given == null) {
            given = heldGiven;
        }
        heldGiven = null;
        String line = capSentences(t.line);
        String q = normalize(t.questionAsked);
        if (!q.isEmpty() && asked.contains(q)) {
            long left = turnDeadline - now;
            if (!reRequested && left > 0) {
                reRequested = true;
                heldGiven = given;
                host.note("the line asks a question already asked: one re-request in the " + left + " ms left");
                port.turn(request.avoiding(t.questionAsked), left);
                return;
            }
            repeats++;
            host.count(ExploreBrain.Gauges.Counter.REPEATS);
            host.note("the question was repeated again: stripped from the line (" + repeats + " so far)");
            line = stripQuestion(line, t.questionAsked);
        } else if (!q.isEmpty()) {
            asked.add(q);
        }
        if (line.isEmpty()) {
            line = CANNED_NO_QUESTION;
        }
        if (t.notesUpdate != null && !t.notesUpdate.trim().isEmpty()) {
            buffer.add(t.notesUpdate);
        }
        if (t.feedback != null) {
            passOn(t.feedback);
        }
        if (t.deflected) {
            host.note("the line deflects a task");
        }
        turns++;
        if (t.action != CuriosityPort.Action.NONE) {
            // Owner 2026-10-02: an instruction he will try to follow. The line already said so:
            // it ends the conversation with no sign-off, and the brain takes it from there.
            action = t.action;
            actionTarget = t.target;
            host.note("an instruction (" + action.word() + "): the line, then the conversation ends");
            speak(now, line, true);
            endAfterLine = true;
            return;
        }
        if (lastNameNext) {
            // The held name matched someone stored whose face is weak for them (KTD6): the
            // last-name question replaces this line, as when the name comes with a face.
            lastNameNext = false;
            nameReply = heard;
            askLastName(now);
            return;
        }
        if (given != null && nameGiven(now, given, line)) {
            // The line waits for the resolver: the last-name question may replace it (KTD6).
            return;
        }
        speak(now, line, true);
    }

    /**
     * A name given in the conversation (face plan U7, KTD6, R20): it goes to the
     * resolver on the port, which joins a stored person, asks the last name or
     * stores someone new; this replaces the meeting plan's KTD10 mismatch rule,
     * under which a differing name always made a new record. The turn's line
     * waits for the answer (true). A name equal to the one already resolved, or a
     * first name whose last name never came, is a no-op; with no face nothing can
     * be stored (R19) and the name is just used.
     */
    private boolean nameGiven(long now, String given, String line) {
        if (sameName(given)) {
            return false;
        }
        if (faceless) {
            // Robot 2026-10-01: from the floor the face is out of frame or too small. The name is
            // used for the rest of the conversation, in memory only; it is stored only if a face
            // retry finds a usable face, else it goes with the conversation (R19).
            host.note(faceRetrying() ? "a name given; no usable face yet, so the name is held for this conversation"
                    + " while he looks for one" : "a name given; no face to keep them by, so nothing is stored");
            name = given;
            personId = null;
            asked.clear();
            notes = null;
            return false;
        }
        if (heldResolving) {
            heldResolving = false;
            port.cancelResolve();
        }
        host.note(name == null ? "a name given: checking it against the people stored"
                : "a name given that differs from the stored one: checking it against the people stored");
        heldLine = line;
        pendingFirst = given;
        nameReply = heard;
        startResolve(now, Resolving.NAME);
        port.resolveName(given, tuning.meetTimeoutMs);
        return true;
    }

    /** The name already in use (or its first word), or a first name settled without a last name. */
    private boolean sameName(String given) {
        if (name != null && (AnswerParser.same(name, given) || (given.trim().indexOf(' ') < 0
                && given.trim().equalsIgnoreCase(NameResolver.firstWord(name))))) {
            return true;
        }
        return settledName != null && AnswerParser.same(settledName, given);
    }

    private void startResolve(long now, Resolving what) {
        state = State.CHAT_THINK;
        phase = Phase.RESOLVING;
        resolving = what;
        resolveDeadline = now + tuning.meetTimeoutMs;
        host.eyes(ExploreBrain.EyeState.THINKING, null);
    }

    /** The resolver's answer (KTD10): join, store someone new, or ask the last name. */
    private void resolveStep(long now) {
        CuriosityPort.Resolved r = port.resolved();
        if (r == null) {
            if (now < resolveDeadline) {
                return;
            }
            port.cancelResolve();
            host.note("the store did not answer about the name in time");
            r = CuriosityPort.Resolved.FAILED;
        }
        Resolving from = resolving;
        resolving = Resolving.NONE;
        switch (r.status) {
            case JOIN:
                join(now, r.personId, r.name != null ? r.name : pendingFirst);
                break;
            case NEW:
                storeNew(now, r.name);
                break;
            case ASK_LAST_NAME:
                if (from == Resolving.NAME) {
                    askLastName(now);
                    return;
                }
                // A last name cannot ask again: nobody is stored.
                settle(now);
                break;
            default:
                host.note("the name could not be checked: nothing is stored");
                if (from == Resolving.LAST_NAME) {
                    settle(now);
                }
                break;
        }
        afterResolve(now, from);
    }

    /** Back to the conversation: the held line is said, or after the last name the next turn goes out with both replies. */
    private void afterResolve(long now, Resolving from) {
        if (from == Resolving.LAST_NAME) {
            transcript.add(new CuriosityPort.Exchange(nameReply, LAST_NAME_QUESTION));
            requestTurn(now, lastNameReply);
            return;
        }
        String line = heldLine;
        heldLine = null;
        speak(now, line == null || line.isEmpty() ? CANNED_NO_QUESTION : line, true);
    }

    /**
     * A join (R6, R20): this conversation's notes buffer moves to the joined id
     * once the photo is added; their older notes are not loaded mid-conversation,
     * because the prefix is frozen.
     */
    private void join(long now, String id, String storedName) {
        host.note("the name belongs to someone stored whose face is close enough: adding the photo to them");
        name = storedName;
        personId = null;
        notes = null;
        asked.clear();
        photoPending = true;
        photoFor = id;
        photoDeadline = now + tuning.meetTimeoutMs;
        port.addPhoto(id, tuning.meetTimeoutMs);
    }

    /** Someone new under this name (R7): kept with the retained crop; the conversation continues known, not asked again. */
    private void storeNew(long now, String newName) {
        host.note("nobody stored has that name" + (resolvingLastName() ? " and last name" : "") + ": keeping the face");
        name = newName;
        personId = null;
        asked.clear();
        notes = null;
        checkOpen = false;
        keepPending = true;
        keepDeadline = now + tuning.meetTimeoutMs;
        port.keep(newName, tuning.meetTimeoutMs);
    }

    private boolean resolvingLastName() {
        return lastNameReply != null;
    }

    /** "And your last name?" in place of the turn's line (KTD6); the reply is listened for once. */
    private void askLastName(long now) {
        host.note("the name matches someone stored but the face is weak for them: asking the last name; the turn's line is dropped");
        heldLine = null;
        askingLastName = true;
        speak(now, LAST_NAME_QUESTION, false);
    }

    /**
     * No last name came (R21): nobody is stored and the conversation runs unnamed;
     * the first name is not asked about again. The exchange joins the transcript.
     */
    private void declineLastName(long now) {
        host.note("no last name came: nobody is stored and the conversation runs unnamed");
        transcript.add(new CuriosityPort.Exchange(nameReply, LAST_NAME_QUESTION));
        settle(now);
    }

    private void settle(long now) {
        settledName = pendingFirst;
        pendingFirst = null;
        name = null;
        personId = null;
        notes = null;
        asked.clear();
        if (checkOpen) {
            checkOpen = false;
            port.checkOutcome(CuriosityPort.Outcome.NAME_GIVEN, null);
        }
    }

    private void photoStep(long now) {
        if (!photoPending) {
            return;
        }
        CuriosityPort.MatchAnswer a = port.photoAdded();
        if (a == null) {
            if (now < photoDeadline) {
                return;
            }
            port.cancelAddPhoto();
            photoPending = false;
            host.note("the store did not answer the photo in time; the conversation runs unnamed");
            return;
        }
        photoPending = false;
        if (a.status == CuriosityPort.MatchAnswer.Status.KNOWN) {
            personId = photoFor;
            persistWanted = true;
            host.note("the photo joined them: the notes persist to them now");
            if (checkOpen) {
                checkOpen = false;
                port.checkOutcome(CuriosityPort.Outcome.JOINED, photoFor);
            }
        } else {
            host.note("the store refused the photo (forgotten meanwhile): nobody is re-created and the conversation runs unnamed");
        }
    }

    private void keepStep(long now) {
        if (!keepPending) {
            return;
        }
        CuriosityPort.Kept k = port.keptAnswer();
        if (k == null) {
            if (now < keepDeadline) {
                return;
            }
            port.cancelKeep();
            keepPending = false;
            host.note("the store did not answer the keep in time; the conversation runs unnamed");
            return;
        }
        keepPending = false;
        if (k.ok()) {
            personId = k.personId;
            host.note("kept under a new record; the notes persist now");
            persistWanted = true;
        } else {
            host.note("the store refused the keep; the conversation runs unnamed");
        }
    }

    // ---- the face retries (robot 2026-10-01) ----

    /** Another face try may still come: the conversation opened faceless and the tries are not spent. */
    private boolean faceRetrying() {
        return faceless && !ending && (seeking || faceLooking || faceMatching || faceTries < tuning.chatFaceTries);
    }

    /**
     * A faceless conversation's face retries: chatFaceDelayMs into a listen (time to crouch
     * down to him) and chatFaceGapMs after the last try, one look; the face in its person box
     * (the whole frame when it has none) goes through the meeting's own match (port.match).
     * A usable face lets him ask the name, or stores a name already given (R19 then keeps the
     * notes). The look runs only while he is not speaking (KTD7).
     */
    private void faceStep(long now) {
        if (heldResolving) {
            heldResolveStep(now);
        }
        if (faceMatching) {
            CuriosityPort.MatchAnswer a = port.matchAnswer();
            if (a == null) {
                if (now < faceMatchDeadline) {
                    return;
                }
                host.note("face try " + faceTries + ": no answer about the face in " + tuning.meetTimeoutMs + " ms");
                a = CuriosityPort.MatchAnswer.FAILED;
            }
            faceMatching = false;
            faceAnswered(now, a);
            return;
        }
        if (faceLooking) {
            ExploreBrain.Look look = host.look();
            boolean arrived = look != null && look.frameMs >= faceLookFrom && look.jpeg != null;
            if (!arrived && now < faceLookDeadline) {
                return;
            }
            boolean search = seekLooking;
            dropFaceLook(false);
            if (search) {
                searchLookIn(now, arrived ? look : null);
                return;
            }
            if (!arrived) {
                host.note("face try " + faceTries + " of " + tuning.chatFaceTries + ": no look in time");
                faceTryOver(now);
                return;
            }
            Detection box = personBox(look.detections);
            host.note("face try " + faceTries + " of " + tuning.chatFaceTries + ": checking the face in a fresh look"
                    + (box == null ? " (no person box: the whole frame)" : ""));
            faceMatching = true;
            faceMatchDeadline = now + tuning.meetTimeoutMs;
            port.match(look.jpeg, box == null ? WHOLE_FRAME : box, tuning.meetTimeoutMs);
            return;
        }
        if ((seeking || centring) && !ending) {
            seekStep(now);
            return;
        }
        if (faceless && !ending && faceTries < tuning.chatFaceTries && state == State.CHAT_LISTEN
                && phase == Phase.LISTENING && now >= listenStartedAt + tuning.chatFaceDelayMs
                && now >= faceTryEndedAt + tuning.chatFaceGapMs && host.faceLooksAllowed()) {
            faceTries++;
            faceLooking = true;
            faceLookFrom = now;
            faceLookDeadline = now + tuning.lookSettleMs + tuning.lookTimeoutMs;
            host.wantLook(true);
        }
    }

    /** The largest person box in the look, or null. */
    private static Detection personBox(List<Detection> found) {
        Detection best = null;
        if (found != null) {
            for (Detection d : found) {
                if (CuriosityPort.Kind.of(d.label) == CuriosityPort.Kind.PERSON
                        && (best == null || d.area() > best.area())) {
                    best = d;
                }
            }
        }
        return best;
    }

    /** A face try's answer: usable (a match, or a new face past the quality gate), or another try later. */
    private void faceAnswered(long now, CuriosityPort.MatchAnswer a) {
        boolean usable = a.status == CuriosityPort.MatchAnswer.Status.KNOWN
                || a.status == CuriosityPort.MatchAnswer.Status.NEW && !a.faceless;
        if (seekFound != null) {
            // Owner 2026-10-02: someone where the caller's voice was: the search stops and he faces them.
            Detection box = seekFound;
            seekFound = null;
            seeking = false;
            centring = !ending;
            host.faceCaller(box);
            host.note("the caller found on search look " + seekLooks + (usable ? " with a usable face" : " with no usable face")
                    + ": the search stops, facing them");
            if (!usable) {
                cantSeeDue = !ending;
            }
        }
        if (!usable) {
            host.note("face try " + faceTries + " of " + tuning.chatFaceTries + ": no usable face");
            faceTryOver(now);
            return;
        }
        faceless = false;
        // The retry's match opened its own face check, which now waits for this conversation's outcome.
        checkOpen = true;
        if (name == null) {
            faceSeen = true;
            host.note("a usable face on try " + faceTries + " of " + tuning.chatFaceTries
                    + (ending ? ", but the conversation is ending: nothing is stored" : ": he may ask the name now"));
            return;
        }
        host.note("a usable face on try " + faceTries + " of " + tuning.chatFaceTries
                + ": checking the name held against the people stored");
        pendingFirst = name;
        heldResolving = true;
        heldResolveDeadline = now + tuning.meetTimeoutMs;
        port.resolveName(name, tuning.meetTimeoutMs);
    }

    /**
     * The search for the caller during a call's conversation (owner 2026-10-02): only while he is
     * neither speaking nor hearing an answer (the motors would swallow their words), a short turn
     * toward the plan's next bearing, then a look there; a turn under way stops the moment that
     * changes. Nobody anywhere ends the search, never the conversation.
     */
    private void seekStep(long now) {
        boolean quiet = quietForSearch(now);
        if (host.seekTurning()) {
            if (!quiet) {
                host.holdStill();
            }
            return;
        }
        if (!quiet || faceLooking || faceMatching) {
            return;
        }
        if (centring) {
            Seek s = host.seek(now);
            if (s == Seek.DONE || s == Seek.LOOK) {
                centring = false;
            }
            return;
        }
        if (!host.faceLooksAllowed()) {
            return;
        }
        switch (host.seek(now)) {
            case LOOK:
                faceLooking = true;
                seekLooking = true;
                faceLookFrom = now;
                faceLookDeadline = now + tuning.lookSettleMs + tuning.lookTimeoutMs;
                host.wantLook(true);
                break;
            case DONE:
                seeking = false;
                cantSeeDue = true;
                host.note("nobody found in the search after " + seekLooks + " look(s): the conversation goes on,"
                        + " and he asks them down to his level");
                break;
            default:
                break;
        }
    }

    /** He may turn or look for the caller now: waiting on a line, or listening with no answer under way. */
    private boolean quietForSearch(long now) {
        if (now < clipUntil) {
            return false;
        }
        if (state == State.CHAT_THINK) {
            return phase == Phase.WAIT_TURN || phase == Phase.RESOLVING;
        }
        return state == State.CHAT_LISTEN && phase == Phase.LISTENING && !port.answering();
    }

    /** A search look came in (null: none in time): the caller in it has their face checked; nobody moves the plan on. */
    private void searchLookIn(long now, ExploreBrain.Look look) {
        if (look == null) {
            host.note("search look: no look in time");
            return;
        }
        host.seekLooked();
        seekLooks++;
        Detection p = host.callerIn(look);
        if (p == null) {
            host.note("search look " + seekLooks + ": nobody here");
            return;
        }
        faceTries++;
        seekFound = p;
        host.note("search look " + seekLooks + ": someone here; checking their face (try " + faceTries + " of "
                + tuning.chatFaceTries + ")");
        faceMatching = true;
        faceMatchDeadline = now + tuning.meetTimeoutMs;
        port.match(look.jpeg, p, tuning.meetTimeoutMs);
    }

    private void faceTryOver(long now) {
        faceTryEndedAt = now;
        if (faceTries >= tuning.chatFaceTries) {
            host.note("no usable face after " + faceTries + " tries: the conversation runs unnamed"
                    + (name != null ? " and the name held is not stored" : ""));
        }
    }

    /**
     * The held name's resolve (face plan U7, KTD10), off the turn: join, store someone new,
     * or ask the last name in place of the next turn's line.
     */
    private void heldResolveStep(long now) {
        CuriosityPort.Resolved r = port.resolved();
        if (r == null) {
            if (now < heldResolveDeadline) {
                return;
            }
            port.cancelResolve();
            r = CuriosityPort.Resolved.FAILED;
        }
        heldResolving = false;
        switch (r.status) {
            case JOIN:
                join(now, r.personId, r.name != null ? r.name : pendingFirst);
                break;
            case NEW:
                storeNew(now, r.name);
                break;
            case ASK_LAST_NAME:
                if (ending) {
                    settle(now);
                } else {
                    host.note("the name held matches someone stored but the face is weak for them: the last name"
                            + " is asked after the next reply");
                    lastNameNext = true;
                }
                break;
            default:
                host.note("the name held could not be checked: nothing is stored");
                break;
        }
    }

    /** The face look ends (its look in, or he is about to speak: then the try is not spent). */
    private void dropFaceLook(boolean unspent) {
        if (!faceLooking) {
            return;
        }
        faceLooking = false;
        if (unspent && !seekLooking) {
            faceTries--;
        }
        seekLooking = false;
        if (phase != Phase.LOOKING && !toolLooking) {
            host.wantLook(false);
        }
    }

    /** The conversation is ending: no new face try; a match in flight is waited for only to store a name held. */
    private void endFaceRetries() {
        seeking = false;
        centring = false;
        host.holdStill();
        dropFaceLook(true);
        if (faceMatching && (name == null || personId != null)) {
            faceMatching = false;
        }
    }

    // ---- CHAT_SPEAK ----

    /** A line through the voice with the camera open and no quiet wait (KTD7): a turn's line, or a fixed template. */
    private void speak(long now, String line, boolean isTurn) {
        // Owner 2026-10-02: never turning while he speaks.
        host.holdStill();
        // The detector parks before he speaks (KTD7): a face look not yet in is asked again later.
        dropFaceLook(true);
        dropToolLook();
        state = State.CHAT_SPEAK;
        phase = Phase.WAIT_CLIP;
        pendingLine = line;
        lineIsTurn = isTurn;
        endAfterLine = false;
        host.eyes(ExploreBrain.EyeState.STARE, null);
        speakStep(now);
    }

    private void playClip(long now, String group, Phase then) {
        state = State.CHAT_SPEAK;
        phase = then;
        openClip(now, group);
    }

    /** A local clip in its deaf window (KTD12): the port's clip window, the clip, then the tail. */
    private void openClip(long now, String group) {
        host.holdStill();
        port.clipWindow(tuning.chatClipMs);
        host.playClip(group);
        clipUntil = now + tuning.chatClipMs + tuning.deafTailMs;
    }

    private void speakStep(long now) {
        switch (phase) {
            case WAIT_CLIP:
                if (now < clipUntil) {
                    return;
                }
                phase = Phase.SAYING;
                sayUntil = now + tuning.sayTimeoutMs;
                if (opener) {
                    opener = false;
                    host.stamp(ExploreBrain.Gauges.Stage.FIRST_SOUND, now);
                }
                port.say(pendingLine);
                break;
            case SAYING:
                boolean done = port.sayFinished();
                if (!done && now < sayUntil) {
                    return;
                }
                if (!done) {
                    host.note("speech never reported finished; moving on");
                }
                lineDone(now);
                break;
            case CLIP_THEN_LISTEN:
                if (now >= clipUntil) {
                    startListen(now);
                }
                break;
            case CLIP_THEN_END:
                if (now >= clipUntil) {
                    enterNotes(now);
                }
                break;
            default:
                break;
        }
    }

    private void lineDone(long now) {
        String line = pendingLine;
        pendingLine = null;
        if (lineIsTurn) {
            transcript.add(new CuriosityPort.Exchange(heard, line));
        }
        if (endAfterLine) {
            enterNotes(now);
            return;
        }
        if (signOffAfterLine) {
            signOffAfterLine = false;
            signOff(now);
            return;
        }
        startListen(now);
    }

    // ---- CHAT_LISTEN ----

    /** The conversation listen; a clip still playing (its window is the deaf window) delays it. */
    private void startListen(long now) {
        state = State.CHAT_LISTEN;
        host.eyes(ExploreBrain.EyeState.LISTENING, null);
        if (now < clipUntil) {
            phase = Phase.CLIP_THEN_LISTEN;
            return;
        }
        phase = Phase.LISTENING;
        listenStartedAt = now;
        listenDeadline = now + tuning.unansweredListenMs;
        answerHeld = false;
        speculated = null;
        port.chatListen(tuning.unansweredListenMs, tuning.newcomerAngleDeg);
    }

    private void listenStep(long now) {
        switch (phase) {
            case CLIP_THEN_LISTEN:
                if (now >= clipUntil) {
                    startListen(now);
                }
                break;
            case LISTENING: {
                CuriosityPort.Heard h = port.heard();
                if (h != null && h.status == CuriosityPort.Heard.Status.WORDS && h.text != null
                        && !h.text.trim().isEmpty()) {
                    onHeard(now, h.text.trim());
                    return;
                }
                if (h == null) {
                    speculate(port.provisional());
                }
                if (answerHeld && h != null) {
                    // Review P2-2: the held answer ended without words (the launcher's "answer
                    // over" ends the port's listen as silence): unanswered now, not at the hold.
                    host.note("the answer ended without words: an unanswered listen");
                    onUnanswered(now, true);
                    return;
                }
                // Silence or a failure before the timer: the timer is the authority (KTD2).
                if (now >= listenDeadline) {
                    if (!answerHeld && port.answering()) {
                        // Robot 2026-10-01: they started answering in time; the words come at its end.
                        answerHeld = true;
                        listenDeadline = listenStartedAt + tuning.answerHoldMs;
                        host.note("an answer has started: the listen holds for it up to " + tuning.answerHoldMs
                                + " ms from its start");
                        break;
                    }
                    if (answerHeld) {
                        host.note("the answer's words never came: an unanswered listen");
                    }
                    onUnanswered(now, answerHeld);
                }
                break;
            }
            case LOOKING:
                lookStep(now);
                break;
            case FORGETTING:
                forgetStep(now);
                break;
            default:
                break;
        }
    }

    private void onHeard(long now, String text) {
        // A reply not said to him (owner 2026-10-02) puts the run back and adds to it.
        unansweredBefore = unanswered;
        heardAt = now;
        unanswered = 0;
        reasked = false;
        glanceIfNewcomer(now);
        if (askingLastName) {
            askingLastName = false;
            String last = AnswerParser.lastName(text, pendingFirst, port);
            if (last != null) {
                lastNameReply = text;
                startResolve(now, Resolving.LAST_NAME);
                port.resolveLastName(last, tuning.meetTimeoutMs);
                return;
            }
            // Not a last name: nobody is stored, and the reply is taken as any other.
            declineLastName(now);
        }
        if (confirmingForget) {
            confirmingForget = false;
            if (affirmative(text)) {
                host.note("forget confirmed: forgetting by id");
                phase = Phase.FORGETTING;
                forgetDeadline = now + tuning.meetTimeoutMs;
                port.forget(personId, tuning.meetTimeoutMs);
                host.eyes(ExploreBrain.EyeState.THINKING, null);
            } else {
                host.note("forget not confirmed: keeping them");
                speak(now, KEPT, false);
            }
            return;
        }
        if (goodbye(text)) {
            host.note("they said goodbye: the sign-off");
            signOff(now);
            return;
        }
        if (forgetMe(text)) {
            if (personId == null) {
                host.note("a forget request from someone unnamed: nothing is kept");
                playClip(now, CLIP_NOTHING_KEPT, Phase.CLIP_THEN_LISTEN);
            } else {
                host.note("a forget request: asking them to confirm by name");
                confirmingForget = true;
                speak(now, ClaudeReplies.fill(CONFIRM_FORGET, name), false);
            }
            return;
        }
        requestTurn(now, text);
    }

    /**
     * Owner 2026-10-02: Claude judged the message not said to him (people talking nearby):
     * nothing is said, it counts as an unanswered listen, and the run's limit, or no message
     * said to him for chatNoReplyMs, ends the conversation with a short "I'll leave you to it".
     */
    private void notAddressed(long now) {
        unanswered = unansweredBefore + 1;
        int max = called ? tuning.callChatUnansweredMax : 2;
        if (unanswered >= max) {
            host.note("not said to him: " + unanswered + " unanswered in a row: he leaves them to it");
            leaveThem(now);
            return;
        }
        if (noReplyTooLong(now)) {
            return;
        }
        host.note("not said to him (" + unanswered + " of " + max + "): nothing said, listening again");
        startListen(now);
    }

    /** No message said to him for chatNoReplyMs: true when that ended the conversation. */
    private boolean noReplyTooLong(long now) {
        if (tuning.chatNoReplyMs <= 0 || ending || now - lastAddressedAt < tuning.chatNoReplyMs) {
            return false;
        }
        host.note("no message said to him for " + tuning.chatNoReplyMs / 1000 + " s or more: he leaves them to it");
        leaveThem(now);
        return true;
    }

    /** The polite end of a conversation nobody is having with him: the short line, then the notes. */
    private void leaveThem(long now) {
        confirmingForget = false;
        if (askingLastName) {
            askingLastName = false;
            declineLastName(now);
        }
        port.cancelTurn();
        ending = true;
        endFaceRetries();
        speak(now, LEAVE_THEM, false);
        endAfterLine = true;
    }

    private void onUnanswered(long now, boolean wordless) {
        if (called && wordless && !reasked && !confirmingForget && !askingLastName) {
            // Owner 2026-10-02: an answer that ended without words gets one re-ask, which does not count.
            reasked = true;
            host.note("the answer ended without words: one \"didn't catch that\", not counted");
            speak(now, DIDNT_CATCH, false);
            return;
        }
        unanswered++;
        confirmingForget = false;
        if (askingLastName) {
            askingLastName = false;
            declineLastName(now);
        }
        glanceIfNewcomer(now);
        if (called) {
            // Owner 2026-10-02: not seeing them never ends a call's conversation; only silence does.
            if (unanswered >= tuning.callChatUnansweredMax) {
                host.note(unanswered + " unanswered listens in a row: the sign-off");
                signOff(now);
                return;
            }
            if (noReplyTooLong(now)) {
                return;
            }
            host.note("unanswered listen " + unanswered + " of " + tuning.callChatUnansweredMax
                    + " in a call's conversation: listening again");
            startListen(now);
            return;
        }
        if (unanswered >= 2) {
            host.note("two unanswered listens: the sign-off");
            signOff(now);
            return;
        }
        // The single walked-off look (KTD7), only here, and only with the lease and the camera.
        if (!host.looksAllowed()) {
            host.note("first unanswered listen; no look possible, so they are taken as still there");
            startListen(now);
            return;
        }
        host.note("first unanswered listen: one look for them");
        phase = Phase.LOOKING;
        lookFrom = now;
        lookDeadline = now + tuning.lookSettleMs + tuning.lookTimeoutMs;
        host.wantLook(true);
    }

    private void lookStep(long now) {
        ExploreBrain.Look look = host.look();
        boolean arrived = look != null && look.frameMs >= lookFrom;
        if (!arrived && now < lookDeadline) {
            return;
        }
        if (!faceLooking) {
            host.wantLook(false);
        }
        if (arrived && !host.facing(look)) {
            host.note("they have walked off: no sign-off");
            walkedOff = true;
            enterNotes(now);
            return;
        }
        host.note(arrived ? "still there: listening once more" : "no look in time: taken as still there");
        startListen(now);
    }

    private void glanceIfNewcomer(long now) {
        if (!newcomerPending) {
            return;
        }
        newcomerPending = false;
        host.note("the newcomer gets a glance and one sec");
        host.eyes(ExploreBrain.EyeState.GLANCE, newcomerSide);
        openClip(now, CLIP_ONE_SEC);
    }

    private void forgetStep(long now) {
        CuriosityPort.Done d = port.forgetAnswer();
        if (d == null && now < forgetDeadline) {
            return;
        }
        if (d == null) {
            port.cancelForget();
        }
        if (d != null && d.ok()) {
            host.note("forgotten: the id and the notes buffer are cleared; the rest runs unnamed");
            personId = null;
            name = null;
            notes = null;
            asked.clear();
            buffer.clear();
            persistWanted = false;
            speak(now, FORGOTTEN, false);
        } else {
            host.note("the forget failed or timed out: still remembered");
            speak(now, FORGET_FAILED, false);
        }
    }

    // ---- ending and CHAT_NOTES ----

    /** The sign-off clip (R11), then the notes; the person is still in front of him. */
    private void signOff(long now) {
        ending = true;
        endFaceRetries();
        port.cancelTurn();
        if (resolving != Resolving.NONE) {
            resolving = Resolving.NONE;
            port.cancelResolve();
        }
        signedOff = true;
        playClip(now, CLIP_SIGN_OFF, Phase.CLIP_THEN_END);
    }

    private void enterNotes(long now) {
        ending = true;
        endFaceRetries();
        state = State.CHAT_NOTES;
        phase = Phase.PERSISTING;
        host.eyes(ExploreBrain.EyeState.THINKING, null);
        if (personId == null && !keepPending && !photoPending && !heldResolving && !faceMatching) {
            if (!buffer.isEmpty()) {
                host.note("unnamed: " + buffer.size() + " note delta(s) discarded (R19)");
                buffer.clear();
            }
        } else if (!buffer.isEmpty()) {
            persistWanted = true;
        }
    }

    /** The store side (KTD10): one delta at a time, drained on each success; a failure ends this persist round. */
    private void notesStep(long now) {
        if (deltaInFlight) {
            CuriosityPort.Done d = port.notesDeltaAnswer();
            if (d == null && now < deltaDeadline) {
                return;
            }
            deltaInFlight = false;
            if (d == null) {
                port.cancelNotesDelta();
            }
            if (d != null && d.ok()) {
                buffer.remove(0);
                persisted++;
            } else {
                host.note("a notes delta was refused or timed out: " + buffer.size() + " left unmerged");
                persistWanted = false;
                return;
            }
        }
        if (!persistWanted || personId == null || buffer.isEmpty()) {
            if (persistWanted && buffer.isEmpty()) {
                persistWanted = false;
                host.note("notes persisted (" + persisted + " delta(s) so far)");
            }
            return;
        }
        deltaInFlight = true;
        deltaDeadline = now + tuning.meetTimeoutMs;
        port.notesDelta(personId, buffer.get(0), tuning.meetTimeoutMs);
    }

    // ---- the robot-side rules (KTD9), plain text only ----

    /** The line cut to the sentence cap: drop from the third (KTD9). */
    String capSentences(String line) {
        if (line == null) {
            return "";
        }
        String[] parts = sentences(line);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.length && i < tuning.sentenceCap; i++) {
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(parts[i]);
        }
        return out.toString();
    }

    static String[] sentences(String line) {
        String t = line.trim();
        if (t.isEmpty()) {
            return new String[0];
        }
        return t.split("(?<=[.!?])\\s+");
    }

    /** The line without the sentence that asks the question: the one ending in "?", else the one holding it. */
    static String stripQuestion(String line, String question) {
        String[] parts = sentences(line);
        String q = normalize(question);
        StringBuilder out = new StringBuilder();
        for (String p : parts) {
            String n = normalize(p);
            boolean asks = p.trim().endsWith("?") || (!q.isEmpty() && (n.contains(q) || q.contains(n)));
            if (asks) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(p);
        }
        return out.toString().trim();
    }

    /**
     * The syntactic half of the name check (KTD9): one or two words of letters
     * (with an inner apostrophe or hyphen), each at most 20 characters, else no
     * name. The adapter runs NameExtractor's word list on the live reply first.
     */
    static String validName(String given) {
        if (given == null) {
            return null;
        }
        String[] words = given.trim().split("\\s+");
        if (words.length < 1 || words.length > 2 || words[0].isEmpty()) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (String w : words) {
            if (w.length() > 20 || !w.matches("\\p{L}(?:[\\p{L}'-]*\\p{L})?")) {
                return null;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return out.toString();
    }

    /**
     * The key questions and utterances compare on (the same rule as the People
     * store's PersonNotes.normalize): lower case, apostrophes and quotes dropped,
     * any other non-letter, non-digit run a single space, trimmed.
     */
    static String normalize(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length());
        boolean space = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                if (space && out.length() > 0) {
                    out.append(' ');
                }
                space = false;
                out.append(Character.toLowerCase(c));
            } else if (c != '\'' && c != '’' && c != '"' && c != '“' && c != '”') {
                space = true;
            }
        }
        return out.toString();
    }

    static boolean affirmative(String text) {
        String n = normalize(text);
        for (String a : AFFIRMATIVES) {
            if (n.equals(a)) {
                return true;
            }
        }
        return false;
    }

    static boolean goodbye(String text) {
        return holdsAny(text, GOODBYES);
    }

    /** "Forget me" and its kin on the speaker's side (R18); "don't forget me" does nothing. */
    static boolean forgetMe(String text) {
        return holdsAny(text, FORGET_ME) && !holdsAny(text, NOT_FORGET);
    }

    private static boolean holdsAny(String text, String[] phrases) {
        String padded = " " + normalize(text) + " ";
        for (String p : phrases) {
            if (padded.contains(" " + p + " ")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return String.format(Locale.US, "chat %s/%s turns=%d asked=%d buffer=%d unanswered=%d", state, phase, turns,
                asked.size(), buffer.size(), unanswered);
    }
}
