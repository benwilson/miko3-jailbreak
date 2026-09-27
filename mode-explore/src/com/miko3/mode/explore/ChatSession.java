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
 *                listen's end, goodbye and forget-me on the speaker's side
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

        /** The charger latch (KTD6): the conversation finishes and no resume leg is driven. */
        boolean charger();
    }

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
    /** The names in a reply by the robot's own patterns (the port's NameExtractor). */
    private final AnswerParser.Names names;
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

    // ---- the store ----
    private boolean keepPending;
    private long keepDeadline;
    private boolean persistWanted;
    private boolean deltaInFlight;
    private long deltaDeadline;
    private long forgetDeadline;
    private int persisted;

    // ---- how it ends ----
    private boolean endOnCharger;
    private boolean finished;
    private boolean signedOff;
    private boolean walkedOff;
    private boolean ending;

    ChatSession(ExploreTuning tuning, CuriosityPort port, Host host) {
        this.tuning = tuning;
        this.port = port;
        this.host = host;
        this.state = State.CHAT_THINK;
        this.names = new AnswerParser.Names() {
            @Override
            public String nameIn(String transcript) {
                return ChatSession.this.port.nameIn(transcript);
            }
        };
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
        this.checkOpen = checkOpen;
        this.settledName = settled;
        this.faceless = faceless;
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
        requestTurn(now, null);
    }

    /** One brain tick in a CHAT state. */
    void step(long now) {
        if (finished) {
            return;
        }
        if (host.charger() && !endOnCharger) {
            endOnCharger = true;
            host.note("charger connected: the conversation finishes and no resume leg is driven");
        }
        keepStep(now);
        photoStep(now);
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
                if (!deltaInFlight && !keepPending && !photoPending
                        && (!persistWanted || buffer.isEmpty() || personId == null)) {
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
        request = new CuriosityPort.TurnRequest(persona, name, notes, window(), heardText);
        turnDeadline = now + tuning.turnBudgetMs;
        host.eyes(ExploreBrain.EyeState.THINKING, null);
        if (opener) {
            host.stamp(ExploreBrain.Gauges.Stage.LINE_REQUESTED, now);
        }
        port.turn(request, tuning.turnBudgetMs);
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
     * A failed turn ends the conversation with the sign-off (KTD9). When it is
     * turn 1 for a known person, nothing has greeted them yet: the local greeting
     * plays first (face plan U6, KTD7).
     */
    private void turnFailed(long now) {
        if (opener && name != null && !greetedLocally) {
            greetedLocally = true;
            host.note("turn 1 failed for someone known: the local greeting, then the sign-off");
            signOffAfterLine = true;
            speak(now, LOCAL_GREETING.replace("{name}", name), false);
            return;
        }
        signOff(now);
    }

    /** One retry on a timed-out or unreachable turn (KTD9), then the local sign-off. */
    private void retryOrSignOff(long now, String why) {
        if (attempt == 1 && tuning.turnRetryMs > 0) {
            attempt = 2;
            host.note("turn attempt 1 failed (" + why + "): retrying once");
            turnDeadline = now + tuning.turnRetryMs;
            port.turn(request, tuning.turnRetryMs);
            return;
        }
        host.note("turn attempt " + attempt + " failed (" + why + "): the local sign-off ends the conversation");
        turnFailed(now);
    }

    /** The robot's side of KTD9: the name, the sentence cap, the repeat check, the notes delta, then the line. */
    private void onLine(long now, CuriosityPort.Turn t) {
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
        if (t.deflected) {
            host.note("the line deflects a task");
        }
        turns++;
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
            host.note("a name given; no face to keep them by, so nothing is stored");
            name = given;
            personId = null;
            asked.clear();
            notes = null;
            return false;
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

    // ---- CHAT_SPEAK ----

    /** A line through the voice with the camera open and no quiet wait (KTD7): a turn's line, or a fixed template. */
    private void speak(long now, String line, boolean isTurn) {
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
        if (endOnCharger && !confirmingForget) {
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
        listenDeadline = now + tuning.unansweredListenMs;
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
                // Silence or a failure before the timer: the timer is the authority (KTD2).
                if (now >= listenDeadline) {
                    onUnanswered(now);
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
        unanswered = 0;
        glanceIfNewcomer(now);
        if (askingLastName) {
            askingLastName = false;
            String last = AnswerParser.lastName(text, pendingFirst, names);
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
        if (endOnCharger) {
            signOff(now);
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
                speak(now, CONFIRM_FORGET.replace("{name}", name), false);
            }
            return;
        }
        requestTurn(now, text);
    }

    private void onUnanswered(long now) {
        unanswered++;
        confirmingForget = false;
        if (askingLastName) {
            askingLastName = false;
            declineLastName(now);
        }
        glanceIfNewcomer(now);
        if (endOnCharger) {
            signOff(now);
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
        host.wantLook(false);
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
        state = State.CHAT_NOTES;
        phase = Phase.PERSISTING;
        host.eyes(ExploreBrain.EyeState.THINKING, null);
        if (personId == null && !keepPending && !photoPending) {
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
