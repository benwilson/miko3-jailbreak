package com.miko3.mode.explore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What the brain asks of Claude and the launcher at a curiosity stop (explore
 * on Claude plan U4, KTD2, KTD6, KTD8). Plain Java with no shared-module
 * and no android.* imports, so ExploreBrain stays host-testable; the adapter
 * (U6) implements it over ClaudeApi, RobotSpeechClient and the people and
 * listen clients, doing every network or Binder call on its own worker thread.
 *
 * Every request returns at once. The brain polls the matching getter on each
 * tick, as it polls Camera.latest(): null means "not finished yet". Starting a
 * new request of a kind abandons any earlier one of that kind, and its late
 * answer must never be returned. The brain keeps its own deadline per try, so
 * an adapter that never answers can't hang a stop.
 */
interface CuriosityPort extends AnswerParser.Names {

    /**
     * False when Claude isn't set up or reachable at all, or while its requests are
     * paused after a rate limit (claudePausedMs() above 0): the stop runs as it did before U4.
     */
    boolean canAsk();

    /**
     * Robot 2026-10-01: how long Claude requests stay paused after a 429 or 529 (its
     * retry-after, else 30 s doubling to 5 min), or 0 when they may go. No request of
     * any kind is sent meanwhile; a conversation turn waits out a short one.
     */
    long claudePausedMs();

    // ---- the look request (KTD2) ----

    /** Start one look request. timeoutMs is this try's budget (the transport's read timeout, KTD6). */
    void ask(LookRequest request, long timeoutMs);

    /** The answer to the last ask(), or null while it is still running. */
    Answer answer();

    /** Abandon the running ask(), if any (the brain's own deadline passed, or the stop ended). */
    void cancelAsk();

    // ---- speaking (KTD8) ----

    /** Queue one line on the launcher's speech service. */
    void say(String line);

    /** True once the last say() has finished, been cancelled or failed; false while it is queued or playing. */
    boolean sayFinished();

    // ---- people (U5): match, then greet, or ask the name, listen and remember ----

    /**
     * Compare the face in this frame's person box with every stored photo, on
     * the robot (face plan U6, KTD3, KTD11): nothing is sent to Claude. The
     * answer carries no lines (KTD7): KNOWN for a confident match, NEW for a
     * close or weak one, and a faceless NEW when no face was found, the crop was
     * rejected, or the store is not ready (R10, R11, R18). Each carries its band,
     * candidate, score and face-check handle.
     */
    void match(byte[] frameJpeg, Detection personBox, long timeoutMs);

    /** The answer to the last match(), or null while it is running. */
    MatchAnswer matchAnswer();

    /**
     * True once the start-up face migration has nothing left to do for now
     * (done, or it cannot run): the brain holds its first roam on it, for at
     * most tuning.faceHoldMs (face plan U6, KTD11).
     */
    boolean migrated();

    /**
     * Whether the face models may run now: the object detector is closed and
     * quiet, or parked (the two-thread rule). The brain says so on every change;
     * the migration's remaining photos run only while it is true (KTD11).
     */
    void faceWork(boolean allowed);

    /** Listen for a reply once speech is idle: up to maxMs, ending on trailing silence (KTD4). */
    void listen(long maxMs);

    /** What listen() heard, or null while it is listening. */
    Heard heard();

    /**
     * Robot 2026-10-01: true while the current listen's answer has started (the
     * launcher's "answering", sent once speech begins inside the listen's start
     * window) and its words have not come. A listen's own deadline does not end it
     * then: the brain holds it for up to tuning.answerHoldMs from its start. Always
     * false for a one-shot listen, a silent one, and under an older launcher.
     */
    boolean answering();

    /**
     * Robot 2026-10-02: the words of the current listen's answer so far, as the launcher
     * sent them when its recogniser endpointed inside the answer (about 0.8 s after the
     * last word, while the 2 s silence rule still runs), or null when none has come. The
     * final answer (heard()) may differ: they kept talking. Never spoken, never logged.
     */
    String provisional();

    /** A new person replied but nothing will be kept: no face was found, or no name was heard
     * (R19). Ask for a text-only "nice to meet you" line that never promises to remember
     * them. Stores nothing. */
    void welcome(String name, long timeoutMs);

    /** The line to say after welcome(), or null while it is running. */
    Answer welcomed();

    /** Store the face from the last match() with this name and ask for the "I'll remember
     * you" line (R11). The name is required: nobody is stored without one (R19), so a null
     * or blank name gets the welcome() line instead and stores nothing. */
    void remember(String name, long timeoutMs);

    /** The line to say after remember(), or null while it is running. */
    Answer remembered();

    /** Mark the person the last match() found as seen now (R10). Fire and forget. */
    void touch();

    /**
     * The text-only lines request (KTD7): named_line (with {name} for the robot
     * to fill; names are never sent), ask_line and no_reply_line. Fetched only
     * where they are spoken: the degraded ladder and a meeting with no look.
     */
    void lines(long timeoutMs);

    /** NEW with the lines, FAILED, or null while it is running. */
    MatchAnswer linesAnswer();

    /**
     * A call's conversation, opened at once (owner 2026-10-02): no Claude request and no face,
     * only the persona snapshot from the launcher's settings, as a faceless NEW answer with
     * the conversation attached (FAILED when the settings are unavailable).
     */
    void callChat(long timeoutMs);

    /** callChat's answer, or null while it is running. */
    MatchAnswer callChatAnswer();

    /** The name in a heard reply: the robot's own patterns first, then a small text-only Claude request (KTD4). */
    void findName(String transcript, long timeoutMs);

    /** What findName() found, or null while it is running. */
    Named foundName();

    // ---- the way out of a wedge (explore nav plan U5, KTD4) ----

    /**
     * Ask which way is out: the circle's frames (with their look indices), or, for the
     * second ask, the one frame he sees now. One schema serves both. The frames leave
     * the robot only inside this request (R15).
     */
    void wayOut(WayOutRequest request, long timeoutMs);

    /** The answer to the last wayOut(), or null while it is running. */
    WayOut wayOutAnswer();

    /** Abandon the running wayOut(), if any: its late answer must never be returned. */
    void cancelWayOut();

    // ---- open doorways (explore nav plan U6, KTD4) ----

    /**
     * Ask whether an open doorway is in this one roaming frame, and where across it.
     * The frame leaves the robot only inside this request (R15).
     */
    void doorway(byte[] jpeg, long timeoutMs);

    /** The answer to the last doorway(), or null while it is running. */
    Doorway doorwayAnswer();

    /** Abandon the running doorway(), if any: its late answer must never be returned. */
    void cancelDoorway();

    // ---- seeking the unfamiliar (owner 2026-10-01) ----

    /**
     * Ask which of a familiar curiosity stop's frames shows the most unexplored-looking
     * place to go, and where across it: answered like the way-out ask (WAY with the frame,
     * an index into request.frames, and x; NONE; FAILED). The frames leave the robot only
     * inside this request, described by numbers and detector labels, never a name (R15).
     */
    void seek(SeekRequest request, long timeoutMs);

    /** The answer to the last seek(), or null while it is running. */
    WayOut seekAnswer();

    /** Abandon the running seek(), if any: its late answer must never be returned. */
    void cancelSeek();

    // ---- people while roaming: the recently-met check (explore nav plan U7, KTD4, KTD8) ----

    /**
     * Ask whether the person in this frame's person box is one of the people met in
     * the last 10 minutes (request.met: the handles metId() gave for them, oldest
     * first). The adapter crops the face from the frame and sends it with the stored
     * faces of those people, like match(). Never carries names, and nothing is
     * written anywhere (R15): not even the face debug switch sees this crop.
     */
    void recentlyMet(RecentlyMetRequest request, long timeoutMs);

    /** The answer to the last recentlyMet(), or null while it is running. */
    Recently recentlyMetAnswer();

    /** Abandon the running recentlyMet(), if any: its late answer must never be returned. */
    void cancelRecentlyMet();

    /**
     * A meeting just ended: an opaque handle for the person the last match() met,
     * for later recentlyMet() requests, or null when there is no face of them to
     * compare against (no face was found). Never a name.
     */
    String metId();

    // ---- the conversation (meeting plan U6, KTD7, KTD9, KTD10): one turn, the notes, forget, the ears ----

    /**
     * Start one conversation turn: the persona snapshot, the notes, the
     * transcript window and what was just heard go to Claude, and the answer
     * is his next line with its flags (U8). timeoutMs is this try's budget
     * (the per-turn budget, then the retry's).
     */
    void turn(TurnRequest request, long timeoutMs);

    /** The answer to the last turn(), or null while it is still running. */
    Turn turnAnswer();

    /** Abandon the running turn(), if any; a late answer must never be returned. */
    void cancelTurn();

    /**
     * Robot 2026-10-02: start this turn now, on a provisional answer, so its reply is
     * ready (or nearly) when the final answer confirms it. Nothing comes of it unless
     * the next turn() asks for exactly the same request; any other turn() discards it.
     * Its line is never handed over before that turn() asks for it.
     */
    void speculateTurn(TurnRequest request, long timeoutMs);

    /**
     * Robot 2026-10-02: a streamed turn's notes update that came after its line was
     * handed over (turnAnswer() returned the line before the reply's tail), oldest
     * first, or null when there is none.
     */
    String lateNotes();

    /** Robot 2026-10-02: a turn's line was handed over and the rest of its reply (its notes) is still coming. */
    boolean turnTailPending();

    /**
     * Owner 2026-10-02: a streamed turn's feedback that came after its line was handed
     * over, oldest first, or null when there is none (as lateNotes()).
     */
    default Feedback lateFeedback() {
        return null;
    }

    /**
     * Owner 2026-10-02: pass one piece of feedback about the robot on to the launcher's
     * feedback log, from this stored person (null: someone unknown), with a few words of
     * context. Fire and forget; it carries no line and no transcript.
     */
    default void feedback(String personId, Feedback feedback, String context) {
    }

    /**
     * Owner 2026-10-03: the turn under way called a tool (look, recall_person, robot_status,
     * places) and asks the conversation for its part, once: a short preamble to say now
     * (null for none) and, for look, a fresh camera frame (answered through lookAnswer).
     * Null when there is nothing to ask. Only the turn() asked last ever asks.
     */
    default ToolAsk toolAsk() {
        return null;
    }

    /** The answer to the last ToolAsk's look: a fresh frame and its labels, or why there is none. */
    default void lookAnswer(LookResult result) {
    }

    /**
     * Merge a notes delta into this person's record through the People store
     * (KTD10, drain-on-persist). Carries no lines and no transcript.
     */
    void notesDelta(String personId, String notesUpdate, long timeoutMs);

    /** The result of the last notesDelta(), or null while it is running. */
    Done notesDeltaAnswer();

    /** Abandon the running notesDelta(), if any. */
    void cancelNotesDelta();

    /** Forget this person entirely (R18): their record, faces and notes, by id. */
    void forget(String personId, long timeoutMs);

    /** The result of the last forget(), or null while it is running. */
    Done forgetAnswer();

    /** Abandon the running forget(), if any. */
    void cancelForget();

    /**
     * A conversation listen (U8, KTD8): the next whole utterance with words is
     * the reply, except a strong one whose latched angle magnitude exceeds
     * newcomerAngleDeg, which the session hands the brain as a newcomer cue
     * instead (NaN: everything is the reply). heard() answers it like listen().
     */
    void chatListen(long maxMs, float newcomerAngleDeg);

    /**
     * Store the face the last match() cut out under a new record with this
     * name (U8, KTD10): someone new the resolver found for a name given (face
     * plan U7, KTD6). Never asks Claude for a line and never writes a debug dump.
     */
    void keep(String name, long timeoutMs);

    /** The result of the last keep(), or null while it is running. */
    Kept keptAnswer();

    /** Abandon the running keep(), if any. */
    void cancelKeep();

    // ---- confirming a close match and resolving names (face plan U7; KTD6, KTD9, KTD10, KTD12) ----

    /**
     * The name in a reply by the robot's own patterns (NameExtractor), title-cased,
     * or null. Synchronous and local: no request, nothing logged. AnswerParser
     * reads the confirmation and last-name replies with it.
     */
    @Override
    String nameIn(String transcript);

    /**
     * Who a spoken name belongs to (KTD10), on the robot: the store's ids for the
     * name, scored against this meeting's face. JOIN (the id and its stored name),
     * ASK_LAST_NAME (the port keeps the name as the pending first name) or NEW
     * (the name to store). Stores nothing itself; names never reach Claude.
     */
    void resolveName(String name, long timeoutMs);

    /**
     * After "And your last name?": JOIN the id whose full stored name equals the
     * pending first name plus this one, else NEW under the full name.
     */
    void resolveLastName(String lastName, long timeoutMs);

    /** The answer to the last resolveName() or resolveLastName(), or null while it runs. */
    Resolved resolved();

    /** Abandon the running resolve, if any. */
    void cancelResolve();

    /**
     * Add this meeting's crop and its embedding to this person (R5, KTD12). The
     * answer is KNOWN with their stored name and the conversation's fields (their
     * notes), or FAILED when the store refused (the id was forgotten meanwhile:
     * nobody is re-created) or the meeting has nothing to store.
     */
    void addPhoto(String personId, long timeoutMs);

    /** The answer to the last addPhoto(), or null while it runs. */
    MatchAnswer photoAdded();

    /** Abandon the running addPhoto(), if any. */
    void cancelAddPhoto();

    /**
     * This meeting's face check gets its outcome (KTD8), with the id the crop
     * joined (null: none). Fire and forget. A new person's outcome is recorded by
     * keep() and remember() themselves.
     */
    void checkOutcome(Outcome outcome, String joinedId);

    /** The meeting is over: its face check, if still waiting for an answer, ends "without an answer" (KTD8). */
    void meetingOver();

    /** Open the launcher's continuous listening session (KTD1); cues then arrive as Ears step input. */
    void earsOpen();

    /** Close the session and release the microphone (the charger flag, shutdown; KTD6). */
    void earsClose();

    /**
     * A clip is about to play for this long: the session keeps the recogniser
     * deaf for it plus the deaf-window tail, so he never hears his own clip
     * (KTD1, KTD12). Lines said through say() need no call: the session sees
     * the speech service itself.
     */
    void clipWindow(long ms);

    /**
     * A shove while stopped, or a collision stop while driving, happened at this
     * brain time (KTD3, KTD5): the session's classifier makes "sorry" or "oops"
     * within its window a strong cue. The brain applies the same rule itself, so
     * this only keeps the launcher's counters and tiers in step.
     */
    void earsShoved(long atMs);

    /** No Claude: every stop takes the path it took before U4. */
    CuriosityPort NONE = new CuriosityPort() {
        public boolean canAsk() {
            return false;
        }

        public long claudePausedMs() {
            return 0;
        }

        public void ask(LookRequest request, long timeoutMs) {
        }

        public Answer answer() {
            return Answer.failed();
        }

        public void cancelAsk() {
        }

        public void say(String line) {
        }

        public boolean sayFinished() {
            return true;
        }

        public void match(byte[] frameJpeg, Detection personBox, long timeoutMs) {
        }

        public MatchAnswer matchAnswer() {
            return MatchAnswer.FAILED;
        }

        public boolean migrated() {
            return true;
        }

        public void faceWork(boolean allowed) {
        }

        public void listen(long maxMs) {
        }

        public Heard heard() {
            return Heard.NOTHING;
        }

        public boolean answering() {
            return false;
        }

        public String provisional() {
            return null;
        }

        public void speculateTurn(TurnRequest request, long timeoutMs) {
        }

        public String lateNotes() {
            return null;
        }

        public boolean turnTailPending() {
            return false;
        }

        public void remember(String name, long timeoutMs) {
        }

        public void welcome(String name, long timeoutMs) {
        }

        public Answer welcomed() {
            return Answer.failed();
        }

        public Answer remembered() {
            return Answer.failed();
        }

        public void touch() {
        }

        public void lines(long timeoutMs) {
        }

        public MatchAnswer linesAnswer() {
            return MatchAnswer.FAILED;
        }

        public void callChat(long timeoutMs) {
        }

        public MatchAnswer callChatAnswer() {
            return MatchAnswer.FAILED;
        }

        public void findName(String transcript, long timeoutMs) {
        }

        public Named foundName() {
            return Named.FAILED;
        }

        public void wayOut(WayOutRequest request, long timeoutMs) {
        }

        public WayOut wayOutAnswer() {
            return WayOut.failed();
        }

        public void cancelWayOut() {
        }

        public void doorway(byte[] jpeg, long timeoutMs) {
        }

        public Doorway doorwayAnswer() {
            return Doorway.failed();
        }

        public void cancelDoorway() {
        }

        public void seek(SeekRequest request, long timeoutMs) {
        }

        public WayOut seekAnswer() {
            return WayOut.failed();
        }

        public void cancelSeek() {
        }

        public void recentlyMet(RecentlyMetRequest request, long timeoutMs) {
        }

        public Recently recentlyMetAnswer() {
            return Recently.failed();
        }

        public void cancelRecentlyMet() {
        }

        public String metId() {
            return null;
        }

        public void turn(TurnRequest request, long timeoutMs) {
        }

        public Turn turnAnswer() {
            return Turn.failed();
        }

        public void cancelTurn() {
        }

        public void notesDelta(String personId, String notesUpdate, long timeoutMs) {
        }

        public Done notesDeltaAnswer() {
            return Done.FAILED;
        }

        public void cancelNotesDelta() {
        }

        public void forget(String personId, long timeoutMs) {
        }

        public Done forgetAnswer() {
            return Done.FAILED;
        }

        public void cancelForget() {
        }

        public void chatListen(long maxMs, float newcomerAngleDeg) {
        }

        public void keep(String name, long timeoutMs) {
        }

        public Kept keptAnswer() {
            return Kept.FAILED;
        }

        public void cancelKeep() {
        }

        public String nameIn(String transcript) {
            return null;
        }

        public void resolveName(String name, long timeoutMs) {
        }

        public void resolveLastName(String lastName, long timeoutMs) {
        }

        public Resolved resolved() {
            return Resolved.FAILED;
        }

        public void cancelResolve() {
        }

        public void addPhoto(String personId, long timeoutMs) {
        }

        public MatchAnswer photoAdded() {
            return MatchAnswer.FAILED;
        }

        public void cancelAddPhoto() {
        }

        public void checkOutcome(Outcome outcome, String joinedId) {
        }

        public void meetingOver() {
        }

        public void earsOpen() {
        }

        public void earsClose() {
        }

        public void clipWindow(long ms) {
        }

        public void earsShoved(long atMs) {
        }
    };

    /** Claude's broad kinds (KTD2). The detector's labels map onto them with of(label). */
    enum Kind {
        PERSON, ANIMAL, TECHNOLOGY, OTHER;

        /** The detector's person/pet vocabulary is people and animals; every other label is a thing. */
        static Kind of(String detectorLabel) {
            if ("person".equals(detectorLabel) || "baby".equals(detectorLabel)) {
                return PERSON;
            }
            return Sighting.isPersonOrPet(detectorLabel) ? ANIMAL : OTHER;
        }

        /** People and animals are one kind each; technology and other are both "a thing" (KTD7). */
        boolean sameBroadKind(Kind other) {
            return this == other || (isThing() && other.isThing());
        }

        boolean isThing() {
            return this == TECHNOLOGY || this == OTHER;
        }

        /** People and animals get the cool-down, as people and pets did before (R12 of the curiosity plan). */
        boolean isLiving() {
            return this == PERSON || this == ANIMAL;
        }
    }

    /** One scan look's photo: which look it was (0 = first) and its JPEG. */
    final class Frame {
        final int look;
        final byte[] jpeg;

        Frame(int look, byte[] jpeg) {
            this.look = look;
            this.jpeg = jpeg;
        }
    }

    /** Something he reacted to earlier this session, so Claude can tell new from familiar (R2). */
    final class Recent {
        final String label;
        final Kind kind;
        final long agoMs;

        Recent(String label, Kind kind, long agoMs) {
            this.label = label;
            this.kind = kind;
            this.agoMs = agoMs;
        }

        @Override
        public String toString() {
            return kind + " " + label + " " + (agoMs / 1000) + "s ago";
        }
    }

    /**
     * The look request: the scan's frames in order, the recent picks, whether people
     * are cooling down, the things he has reacted to this session (labels of things
     * only, most recent first: never a person, a name or notes) and his last remarks
     * about things (most recent first), so a familiar room still gets a fresh line.
     */
    final class LookRequest {
        final List<Frame> frames;
        final List<Recent> recent;
        /** People and animals were greeted within the cool-down: prefer anything else. */
        final boolean livingCoolingDown;
        /** Labels of things (never people or animals) he has reacted to this session, most recent first. */
        final List<String> reacted;
        /** His remarks about things this session, most recent first: not to be repeated. */
        final List<String> said;

        LookRequest(List<Frame> frames, List<Recent> recent, boolean livingCoolingDown) {
            this(frames, recent, livingCoolingDown, Collections.<String>emptyList(), Collections.<String>emptyList());
        }

        LookRequest(List<Frame> frames, List<Recent> recent, boolean livingCoolingDown, List<String> reacted,
                List<String> said) {
            this.frames = Collections.unmodifiableList(frames);
            this.recent = Collections.unmodifiableList(recent);
            this.livingCoolingDown = livingCoolingDown;
            this.reacted = Collections.unmodifiableList(reacted);
            this.said = Collections.unmodifiableList(said);
        }
    }

    /**
     * Claude's answer. PICK carries the frame index (into LookRequest.frames), the
     * box as frame fractions (Detection's 0..1 coordinates, label = Claude's
     * label), the kind, and the line; NOTHING means nothing worth a reaction;
     * FAILED is a refused, malformed or failed request (retried by the brain).
     */
    final class Answer {
        enum Status { PICK, NOTHING, FAILED }

        final Status status;
        final int frame;
        final Detection box;
        final Kind kind;
        final String line;

        private Answer(Status status, int frame, Detection box, Kind kind, String line) {
            this.status = status;
            this.frame = frame;
            this.box = box;
            this.kind = kind;
            this.line = line;
        }

        static Answer pick(int frame, Detection box, Kind kind, String line) {
            return new Answer(Status.PICK, frame, box, kind, line);
        }

        static Answer nothing() {
            return new Answer(Status.NOTHING, -1, null, null, null);
        }

        static Answer failed() {
            return new Answer(Status.FAILED, -1, null, null, null);
        }

        /** A spoken line on its own (remembered()). */
        static Answer line(String line) {
            return new Answer(Status.PICK, -1, null, null, line);
        }

        @Override
        public String toString() {
            return status == Status.PICK && box != null ? "pick " + kind + " " + box + " in frame " + frame
                    : status.toString();
        }
    }

    /**
     * The meeting's answer (face plan U6, KTD7). A match answer carries no lines:
     * KNOWN carries the stored name (null when unnamed), NEW is someone to ask.
     * A lines answer (lines()) is NEW with Claude's lines, {name} still in
     * namedLine. The match fields say what the robot's matcher made of the face
     * (U7 builds the confirmation on them).
     */
    final class MatchAnswer {
        enum Status { KNOWN, NEW, FAILED }

        static final MatchAnswer FAILED = new MatchAnswer(Status.FAILED, null, null, null, null, null);

        /** NEW with nothing to store: no face found, a rejected crop, or the store not ready (R10, R11, R18). */
        final boolean faceless;

        final Status status;
        final String name;
        final String namedLine;
        final String unnamedLine;
        final String askLine;
        final String noReplyLine;
        /**
         * What the conversation needs at its start (U8, KTD9, KTD11): the persona
         * snapshot the adapter took when this answer was built (null: none), the
         * store id of a known person (null for a stranger or a nameless record),
         * their notes rendered as data (null when none) and the questions already
         * asked them, normalised. The stand-ins and the meeting-as-today path leave
         * them null.
         */
        final String persona;
        final String personId;
        final String notes;
        final List<String> questionsAsked;
        /**
         * The on-device match (face plan U6): the band (null when no match ran: no
         * face, a rejected crop, the store or models unavailable), the best
         * candidate's store id (null: none), its score (NaN: none) and the face
         * check's handle for updateCheck (-1: none recorded).
         */
        final FaceMatcher.Band band;
        final String candidateId;
        final float score;
        final long checkHandle;
        /**
         * A close match's question (face plan U7, KTD6): the name "Is that you,
         * {name}?" asks about the candidate, the full stored name when another
         * stored person shares its first name. Null: nothing to confirm.
         */
        final String confirmName;

        /** This answer with the conversation's fields attached. */
        MatchAnswer withConversation(String persona, String personId, String notes, List<String> questionsAsked) {
            return new MatchAnswer(status, name, namedLine, unnamedLine, askLine, noReplyLine, faceless, persona,
                    personId, notes, questionsAsked, band, candidateId, score, checkHandle, confirmName);
        }

        /** This answer with the on-device match's fields attached. */
        MatchAnswer withMatch(FaceMatcher.Band band, String candidateId, float score, long checkHandle) {
            return new MatchAnswer(status, name, namedLine, unnamedLine, askLine, noReplyLine, faceless, persona,
                    personId, notes, questionsAsked, band, candidateId, score, checkHandle, confirmName);
        }

        /** This answer with the close match's question name (U7, KTD6). */
        MatchAnswer withConfirm(String confirmName) {
            return new MatchAnswer(status, name, namedLine, unnamedLine, askLine, noReplyLine, faceless, persona,
                    personId, notes, questionsAsked, band, candidateId, score, checkHandle, confirmName);
        }

        /** A confident match (U6): the stored name, and no lines (KTD7). */
        static MatchAnswer known(String nameOrNull) {
            return new MatchAnswer(Status.KNOWN, nameOrNull, null, null, null, null);
        }

        /** A close or weak match (U6): someone to ask, whose face can be stored; no lines. */
        static MatchAnswer stranger() {
            return new MatchAnswer(Status.NEW, null, null, null, null, null);
        }

        /** Someone to talk to with nothing to store (R10, R11, R18); no lines. */
        static MatchAnswer faceless() {
            return new MatchAnswer(Status.NEW, null, null, null, null, null, true);
        }

        static MatchAnswer known(String nameOrNull, String namedLine, String unnamedLine) {
            return new MatchAnswer(Status.KNOWN, nameOrNull, namedLine, unnamedLine, null, null);
        }

        static MatchAnswer stranger(String askLine, String noReplyLine) {
            return new MatchAnswer(Status.NEW, null, null, null, askLine, noReplyLine);
        }

        /** No face in the person box: a new person to talk to, with nothing to store. */
        static MatchAnswer faceless(String askLine, String noReplyLine) {
            return new MatchAnswer(Status.NEW, null, null, null, askLine, noReplyLine, true);
        }

        MatchAnswer(Status status, String name, String namedLine, String unnamedLine, String askLine,
                    String noReplyLine) {
            this(status, name, namedLine, unnamedLine, askLine, noReplyLine, false);
        }

        MatchAnswer(Status status, String name, String namedLine, String unnamedLine, String askLine,
                    String noReplyLine, boolean faceless) {
            this(status, name, namedLine, unnamedLine, askLine, noReplyLine, faceless, null, null, null, null, null,
                    null, Float.NaN, -1L, null);
        }

        private MatchAnswer(Status status, String name, String namedLine, String unnamedLine, String askLine,
                            String noReplyLine, boolean faceless, String persona, String personId, String notes,
                            List<String> questionsAsked, FaceMatcher.Band band, String candidateId, float score,
                            long checkHandle, String confirmName) {
            this.faceless = faceless;
            this.status = status;
            this.name = name;
            this.namedLine = namedLine;
            this.unnamedLine = unnamedLine;
            this.askLine = askLine;
            this.noReplyLine = noReplyLine;
            this.persona = persona;
            this.personId = personId;
            this.notes = notes;
            this.questionsAsked = questionsAsked == null ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(new ArrayList<String>(questionsAsked));
            this.band = band;
            this.candidateId = candidateId;
            this.score = score;
            this.checkHandle = checkHandle;
            this.confirmName = confirmName;
        }
    }

    /** A face check's answer (KTD8), as the brain and the conversation know it; the adapter maps it to FaceCheck's codes. */
    enum Outcome { YES, NO, NAME_GIVEN, JOINED, NO_REPLY }

    /**
     * Who a spoken name belongs to (face plan U7, KTD10): JOIN a stored person
     * (their id and stored name), ASK_LAST_NAME, or NEW (the name to store);
     * FAILED when the store could not answer or the meeting has no face to compare.
     */
    final class Resolved {
        enum Status { JOIN, ASK_LAST_NAME, NEW, FAILED }

        static final Resolved FAILED = new Resolved(Status.FAILED, null, null);

        final Status status;
        final String personId;
        final String name;

        private Resolved(Status status, String personId, String name) {
            this.status = status;
            this.personId = personId;
            this.name = name;
        }

        static Resolved join(String personId, String storedName) {
            return personId == null ? FAILED : new Resolved(Status.JOIN, personId, storedName);
        }

        static Resolved askLastName(String firstName) {
            return new Resolved(Status.ASK_LAST_NAME, null, firstName);
        }

        static Resolved newPerson(String name) {
            return name == null || name.trim().isEmpty() ? FAILED : new Resolved(Status.NEW, null, name.trim());
        }
    }

    /** What listen() heard: the words, or nothing (no reply, R12), or a failure. */
    final class Heard {
        enum Status { WORDS, SILENCE, FAILED }

        static final Heard NOTHING = new Heard(Status.SILENCE, null);

        final Status status;
        final String text;

        Heard(Status status, String text) {
            this.status = status;
            this.text = text;
        }
    }

    /** What findName() found: a name, no clear name (nothing is kept and he just says hello, R19; meeting plan line 309), or a failure. */
    final class Named {
        enum Status { NAME, NO_NAME, FAILED }

        static final Named NONE = new Named(Status.NO_NAME, null);
        static final Named FAILED = new Named(Status.FAILED, null);

        final Status status;
        final String name;

        Named(Status status, String name) {
            this.status = status;
            this.name = name;
        }

        static Named of(String nameOrNull) {
            return nameOrNull == null || nameOrNull.trim().isEmpty() ? NONE : new Named(Status.NAME, nameOrNull.trim());
        }
    }

    /** The way-out request: the frames in order, and whether this is the second ask (one frame, now). */
    final class WayOutRequest {
        final List<Frame> frames;
        final boolean second;

        WayOutRequest(List<Frame> frames, boolean second) {
            this.frames = Collections.unmodifiableList(frames);
            this.second = second;
        }
    }

    /**
     * Claude's way out. WAY carries the frame (an index into WayOutRequest.frames) and
     * where across it the way out is (x, -1 at the frame's left edge .. 1 at its right);
     * the brain turns it into a heading at once. NONE: nothing looks open. FAILED: a
     * refused, malformed, out-of-range or failed request.
     */
    final class WayOut {
        enum Status { WAY, NONE, FAILED }

        final Status status;
        final int frame;
        final float x;

        private WayOut(Status status, int frame, float x) {
            this.status = status;
            this.frame = frame;
            this.x = x;
        }

        static WayOut way(int frame, float x) {
            return new WayOut(Status.WAY, frame, x);
        }

        static WayOut none() {
            return new WayOut(Status.NONE, -1, 0f);
        }

        static WayOut failed() {
            return new WayOut(Status.FAILED, -1, 0f);
        }

        /** Numbers only, for the trace. */
        @Override
        public String toString() {
            return status == Status.WAY
                    ? String.format(java.util.Locale.US, "way out in frame %d at x %.2f", frame, x)
                    : status.toString();
        }
    }

    /** The seek request: a familiar stop's frames, in the order he took them. */
    final class SeekRequest {
        final List<SeekFrame> frames;

        SeekRequest(List<SeekFrame> frames) {
            this.frames = Collections.unmodifiableList(frames);
        }
    }

    /**
     * One frame of a seek request and what he knows about it: its bearing from the
     * first frame (left positive), its place novelty (0 familiar .. 1 new; NaN: too
     * plain to tell), how long ago he saw that view (-1: not seen), whether it looks
     * like where his last seek went, and the detector's labels in it. Numbers and
     * labels only (R15).
     */
    final class SeekFrame {
        final Frame frame;
        final double bearingDeg;
        final double novelty;
        final long seenAgoMs;
        final boolean wentThere;
        final List<String> labels;

        SeekFrame(Frame frame, double bearingDeg, double novelty, long seenAgoMs, boolean wentThere,
                  List<String> labels) {
            this.frame = frame;
            this.bearingDeg = bearingDeg;
            this.novelty = novelty;
            this.seenAgoMs = seenAgoMs;
            this.wentThere = wentThere;
            this.labels = labels == null ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(new ArrayList<String>(labels));
        }
    }

    /**
     * Claude's doorway answer. DOOR carries where across the frame the open doorway
     * is (x, -1 at the frame's left edge .. 1 at its right); the brain turns it into a
     * heading at once. NONE: no open doorway in view (a closed door is not one).
     * FAILED: a refused, malformed, out-of-range or failed request.
     */
    final class Doorway {
        enum Status { DOOR, NONE, FAILED }

        final Status status;
        final float x;

        private Doorway(Status status, float x) {
            this.status = status;
            this.x = x;
        }

        static Doorway door(float x) {
            return new Doorway(Status.DOOR, x);
        }

        static Doorway none() {
            return new Doorway(Status.NONE, 0f);
        }

        static Doorway failed() {
            return new Doorway(Status.FAILED, 0f);
        }

        /** Numbers only, for the trace. */
        @Override
        public String toString() {
            return status == Status.DOOR ? String.format(java.util.Locale.US, "open doorway at x %.2f", x)
                    : status.toString();
        }
    }

    /**
     * The recently-met check: the frame and the person box in it (frame fractions)
     * to crop the face from, and the handles of everyone met in the last 10 minutes,
     * oldest first.
     */
    final class RecentlyMetRequest {
        final byte[] frameJpeg;
        final Detection personBox;
        final List<String> met;

        RecentlyMetRequest(byte[] frameJpeg, Detection personBox, List<String> met) {
            this.frameJpeg = frameJpeg;
            this.personBox = personBox;
            this.met = Collections.unmodifiableList(met);
        }
    }

    /**
     * Claude's recently-met answer. SAME carries which of the request's people it is
     * (index into RecentlyMetRequest.met); DIFFERENT: none of them; UNSURE: can't
     * tell (no face found, too small, turned away); FAILED: a refused, malformed or
     * failed request. Only DIFFERENT lets him approach (KTD8).
     */
    final class Recently {
        enum Status { SAME, DIFFERENT, UNSURE, FAILED }

        final Status status;
        final int index;

        private Recently(Status status, int index) {
            this.status = status;
            this.index = index;
        }

        static Recently same(int index) {
            return new Recently(Status.SAME, index);
        }

        static Recently different() {
            return new Recently(Status.DIFFERENT, -1);
        }

        static Recently unsure() {
            return new Recently(Status.UNSURE, -1);
        }

        static Recently failed() {
            return new Recently(Status.FAILED, -1);
        }

        /** Numbers only, for the trace. */
        @Override
        public String toString() {
            return status == Status.SAME ? "same as person " + (index + 1) : status.toString();
        }
    }

    /** One exchange of the transcript window (KTD9): what they said and what he answered. */
    final class Exchange {
        final String heard;
        final String said;

        Exchange(String heard, String said) {
            this.heard = heard;
            this.said = said;
        }
    }

    /**
     * Owner 2026-10-03: what a turn's tools may tell Claude about him, as the brain saw it
     * when the request was built (robot_status and places): fixed-format text, never a
     * frame, a transcript or anyone's notes. NONE when the brain gave nothing.
     */
    final class ToolFacts {
        static final ToolFacts NONE = new ToolFacts("Nothing is known about his state right now.",
                "He remembers no places right now.");

        final String status;
        final String places;

        ToolFacts(String status, String places) {
            this.status = status == null || status.trim().isEmpty() ? NONE.status : status.trim();
            this.places = places == null || places.trim().isEmpty() ? NONE.places : places.trim();
        }
    }

    /** Owner 2026-10-03: a tool round's ask of the conversation: a preamble to say (or null), and a look. */
    final class ToolAsk {
        final String preamble;
        final boolean look;

        ToolAsk(String preamble, boolean look) {
            String p = preamble == null ? "" : preamble.trim();
            this.preamble = p.isEmpty() ? null : p;
            this.look = look;
        }
    }

    /**
     * Owner 2026-10-03: the look tool's frame and the detector's labels in it, or why he
     * can't look now (refused: bathroom privacy, do not disturb, no camera, no frame in time).
     */
    final class LookResult {
        final byte[] jpeg;
        final List<String> labels;
        /** Null when the frame is here; else a short reason Claude can read. */
        final String refused;

        private LookResult(byte[] jpeg, List<String> labels, String refused) {
            this.jpeg = jpeg;
            this.labels = labels == null ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(new ArrayList<String>(labels));
            this.refused = refused;
        }

        static LookResult of(byte[] jpeg, List<String> labels) {
            return jpeg == null ? refused("the camera gave no picture") : new LookResult(jpeg, labels, null);
        }

        static LookResult refused(String why) {
            return new LookResult(null, null, why == null || why.trim().isEmpty() ? "he can't look right now" : why);
        }
    }

    /**
     * One turn's request (KTD9): the persona snapshot taken when the conversation
     * began (KTD11), the person's name (null for a stranger), their notes
     * rendered as data (null when none), the transcript window, and what was
     * just heard (null for the opener). The adapter builds the prompt from it
     * and never logs any of it.
     */
    final class TurnRequest {
        final String persona;
        final String name;
        final String notes;
        final List<Exchange> transcript;
        final String heard;
        /**
         * The re-request (U8, KTD9): the question the last answer repeated, as the
         * model wrote it, so the adapter adds the "not that one" reminder; null on
         * a first request.
         */
        final String avoidQuestion;
        /**
         * The conversation opened with no usable face (robot 2026-10-01): the opener
         * invites them down to his level and never asks the name, since nothing could
         * be stored without a face (R19). Fixed for the conversation, so the replayed
         * opener reads as it was sent.
         */
        final boolean faceless;
        /** A usable face arrived on a retry since: he may ask the name of someone still unnamed. */
        final boolean faceSeen;
        /**
         * Owner 2026-10-02: the conversation opened on a call, before he had seen them. Its opener
         * is a short greeting-question (ExplorePrompts.CALL_OPENER), or, when they said words with
         * the wake word, those words are turn 1's heard. Fixed for the conversation.
         */
        final boolean called;
        /**
         * Owner 2026-10-02: he now knows he can't see them (the search found nobody, or found
         * them with no usable face): this turn invites them down to his level. Once.
         */
        final boolean cantSee;
        /** Owner 2026-10-03: what robot_status and places answer with for this turn. */
        final ToolFacts facts;

        TurnRequest(String persona, String name, String notes, List<Exchange> transcript, String heard) {
            this(persona, name, notes, transcript, heard, null);
        }

        TurnRequest(String persona, String name, String notes, List<Exchange> transcript, String heard,
                    String avoidQuestion) {
            this(persona, name, notes, transcript, heard, avoidQuestion, false, false);
        }

        TurnRequest(String persona, String name, String notes, List<Exchange> transcript, String heard,
                    String avoidQuestion, boolean faceless, boolean faceSeen) {
            this(persona, name, notes, transcript, heard, avoidQuestion, faceless, faceSeen, false, false);
        }

        TurnRequest(String persona, String name, String notes, List<Exchange> transcript, String heard,
                    String avoidQuestion, boolean faceless, boolean faceSeen, boolean called, boolean cantSee) {
            this(persona, name, notes, transcript, heard, avoidQuestion, faceless, faceSeen, called, cantSee, null);
        }

        TurnRequest(String persona, String name, String notes, List<Exchange> transcript, String heard,
                    String avoidQuestion, boolean faceless, boolean faceSeen, boolean called, boolean cantSee,
                    ToolFacts facts) {
            this.facts = facts == null ? ToolFacts.NONE : facts;
            this.called = called;
            this.cantSee = cantSee;
            this.persona = persona;
            this.name = name;
            this.notes = notes;
            this.transcript = transcript == null ? Collections.<Exchange>emptyList() : transcript;
            this.heard = heard;
            this.avoidQuestion = avoidQuestion;
            this.faceless = faceless;
            this.faceSeen = faceSeen;
        }

        /** This request again, with the repeated question to avoid. */
        TurnRequest avoiding(String question) {
            return new TurnRequest(persona, name, notes, transcript, heard, question, faceless, faceSeen, called,
                    cantSee, facts);
        }

        /** This request as one in a conversation that opened faceless, with or without a face since. */
        TurnRequest face(boolean openedFaceless, boolean seenSince) {
            return new TurnRequest(persona, name, notes, transcript, heard, avoidQuestion, openedFaceless, seenSince,
                    called, cantSee, facts);
        }

        /** This request in a conversation a call opened (owner 2026-10-02), with or without the crouch invitation. */
        TurnRequest call(boolean openedOnACall, boolean cantSeeThem) {
            return new TurnRequest(persona, name, notes, transcript, heard, avoidQuestion, faceless, faceSeen,
                    openedOnACall, cantSeeThem, facts);
        }

        /** This request with what robot_status and places answer (owner 2026-10-03). */
        TurnRequest withFacts(ToolFacts f) {
            return new TurnRequest(persona, name, notes, transcript, heard, avoidQuestion, faceless, faceSeen, called,
                    cantSee, f);
        }

        /** The opener: nothing heard yet. */
        static TurnRequest opener(String persona, String name, String notes) {
            return new TurnRequest(persona, name, notes, null, null);
        }
    }

    /**
     * One turn's answer (KTD9): his line, the question it asks (normalised by
     * the brain against the notes so he never repeats one), a name the person
     * gave (validated by the brain), whether Claude thinks the conversation is
     * over (spoken as a normal line; the brain decides), whether the line is a
     * deflection (a task he does not take), and the notes delta to merge at the
     * end; or a refusal (the deflection clip), unreachable (retry once, then
     * the local sign-off) or a failure.
     */
    /**
     * Robot 2026-10-02: the conversation turns in flight, for the live adapter. A turn
     * started on a provisional answer (speculate) waits, unseen, until a turn() asks
     * for the same request (adopt, by the request's key), which then answers that
     * turn() with what it has or will have; any other turn() discards it. A streamed
     * turn hands its line over early (early) and its whole reply later (whole): when
     * the line already went, only the reply's notes follow, as late notes. Deliver
     * says whether the turn() it answers is still the one asked; a dead one takes
     * nothing, notes included. Thread-safe; plain Java.
     */
    final class TurnFlight {
        /** Hands a turn's answer to turnAnswer(): true when generation g is still the turn asked. */
        interface Deliver {
            boolean turn(int g, Turn t);
        }

        /** One request in flight. */
        final class Call {
            final String key;
            /** The turn() generation it answers; 0 while it is a speculation nobody has asked for. */
            int gen;
            Turn early;
            Turn whole;
            boolean earlyHandedOver;

            Call(String key, int gen) {
                this.key = key;
                this.gen = gen;
            }
        }

        private final Deliver deliver;
        private Call speculation;
        private final List<String> late = new ArrayList<String>();
        private final List<Feedback> lateFeedback = new ArrayList<Feedback>();
        private Call tail;

        TurnFlight(Deliver deliver) {
            this.deliver = deliver;
        }

        /** A new speculation for this key (replacing any other), or null when one for it is already running. */
        synchronized Call speculate(String key) {
            if (speculation != null && speculation.key.equals(key)) {
                return null;
            }
            speculation = new Call(key, 0);
            return speculation;
        }

        /**
         * The speculation for this key, now answering generation g with whatever it
         * already has; null when there is none for this key (any other is discarded).
         */
        synchronized Call adopt(String key, int g) {
            dropTail();
            Call c = speculation;
            speculation = null;
            if (c == null || !c.key.equals(key)) {
                return null;
            }
            c.gen = g;
            if (c.early != null) {
                handEarly(c);
                if (c.whole != null) {
                    handWhole(c);
                }
            } else if (c.whole != null) {
                handWhole(c);
            }
            return c;
        }

        /** A plain turn, answering generation g. */
        synchronized Call start(String key, int g) {
            dropTail();
            return new Call(key, g);
        }

        /**
         * Owner 2026-10-03: whether this call may run a tool round (say a preamble, take a
         * look): true once a turn() it answers is still asked. A speculation nobody has asked
         * for yet is dropped instead, so the turn() that follows starts afresh.
         */
        synchronized boolean claimForTools(Call c) {
            if (c == speculation) {
                speculation = null;
                return false;
            }
            return c.gen > 0;
        }

        /** Its line is known (a LINE turn with no notes yet): handed over now when its turn() asked for it. */
        synchronized void early(Call c, Turn t) {
            if (c.early != null || c.whole != null || t == null || t.status != Turn.Status.LINE) {
                return;
            }
            c.early = t;
            if (c.gen != 0 && c != speculation) {
                handEarly(c);
            }
        }

        /** Its whole reply (or failure): handed over, or only its notes when the line already went. */
        synchronized void whole(Call c, Turn t) {
            if (c.whole != null) {
                return;
            }
            c.whole = t == null ? Turn.failed() : t;
            if (c.gen != 0 && c != speculation) {
                handWhole(c);
            }
        }

        private void handEarly(Call c) {
            c.earlyHandedOver = deliver.turn(c.gen, c.early);
            if (c.earlyHandedOver && c.whole == null) {
                tail = c;
            }
        }

        private void handWhole(Call c) {
            if (tail == c) {
                tail = null;
            }
            if (c.gen < 0) {
                return; // its turn was cancelled after the line went: no notes
            }
            if (!c.earlyHandedOver) {
                deliver.turn(c.gen, c.whole);
                return;
            }
            String notes = c.whole.status == Turn.Status.LINE ? c.whole.notesUpdate : null;
            if (notes != null && !notes.trim().isEmpty()) {
                late.add(notes);
            }
            if (c.whole.status == Turn.Status.LINE && c.whole.feedback != null) {
                lateFeedback.add(c.whole.feedback);
            }
        }

        /** The oldest late feedback, or null. */
        synchronized Feedback lateFeedback() {
            return lateFeedback.isEmpty() ? null : lateFeedback.remove(0);
        }

        /** The oldest late notes update, or null. */
        synchronized String lateNotes() {
            return late.isEmpty() ? null : late.remove(0);
        }

        /** A line went and its reply's tail has not come yet. */
        synchronized boolean tailPending() {
            return tail != null;
        }

        /** The turn asked was abandoned: a reply tail still coming brings no late notes. */
        synchronized void cancel() {
            dropTail();
        }

        /**
         * A new turn() was asked, or the last abandoned: the previous turn's tail brings no
         * notes (a re-request's rejected reply must not record its question as asked).
         */
        private void dropTail() {
            if (tail != null) {
                tail.gen = -1;
                tail = null;
            }
        }

        /** Explore stops: no speculation, no late notes. */
        synchronized void clear() {
            speculation = null;
            tail = null;
            late.clear();
            lateFeedback.clear();
        }
    }

    /**
     * Owner 2026-10-02: feedback a person gave about the robot himself in a turn (his
     * behaviour, abilities, voice, driving, getting stuck, interrupting): its kind, a
     * one-sentence neutral summary and their key sentence, short and verbatim. The
     * launcher re-checks it with the shared Feedback rules before keeping it;
     * this side only refuses an unknown kind or an empty summary and trims.
     */
    final class Feedback {
        static final List<String> KINDS = java.util.Arrays.asList("suggestion", "complaint", "praise", "bug");
        static final int MAX_SUMMARY_CHARS = 200;
        static final int MAX_QUOTE_CHARS = 240;

        final String kind;
        final String summary;
        /** "" when there is none. */
        final String quote;

        private Feedback(String kind, String summary, String quote) {
            this.kind = kind;
            this.summary = summary;
            this.quote = quote;
        }

        /** Null for an unknown kind (including "none") or an empty summary. */
        static Feedback of(String kind, String summary, String quote) {
            String k = kind == null ? "" : kind.trim().toLowerCase(java.util.Locale.US);
            String s = squash(summary, MAX_SUMMARY_CHARS);
            if (!KINDS.contains(k) || s.isEmpty()) {
                return null;
            }
            return new Feedback(k, s, squash(quote, MAX_QUOTE_CHARS));
        }

        private static String squash(String text, int max) {
            if (text == null) {
                return "";
            }
            String t = text.replaceAll("[\\s\\p{Cntrl}]+", " ").trim();
            return t.length() > max ? t.substring(0, max).trim() : t;
        }

        /** The kind only, so a stray trace line never carries what was said. */
        @Override
        public String toString() {
            return kind;
        }
    }

    /**
     * Owner 2026-10-02: an instruction the person explicitly gave him in a turn, which he
     * tries to follow ("go away", "go to another room", "go find someone", "come here",
     * "be quiet"). Anything else he can't do is NONE, and his line says so.
     */
    enum Action {
        NONE, GO_AWAY, GO_ELSEWHERE, FIND_PERSON, COME_HERE, BE_QUIET;

        /** The schema's word ("go_away"), or NONE for anything else. */
        static Action of(Object v) {
            if (!(v instanceof String)) {
                return NONE;
            }
            try {
                return valueOf(((String) v).trim().toUpperCase(java.util.Locale.US));
            } catch (IllegalArgumentException e) {
                return NONE;
            }
        }

        /** The schema's word, for the trace. */
        String word() {
            return name().toLowerCase(java.util.Locale.US);
        }
    }

    final class Turn {
        enum Status { LINE, REFUSED, UNREACHABLE, FAILED }

        final Status status;
        final String line;
        final String questionAsked;
        final String nameGiven;
        final boolean endsConversation;
        final boolean deflected;
        final String notesUpdate;
        /** Owner 2026-10-02: feedback the person gave about the robot himself, or null. */
        final Feedback feedback;
        /**
         * Owner 2026-10-02: the message was said to him (Claude's judgement), not people talking
         * to each other nearby; a turn that was not has no line to speak and counts as unanswered.
         */
        final boolean addressed;
        /** Owner 2026-10-02: an instruction he was given, and its short target (a name or place), or null. */
        final Action action;
        final String target;

        private Turn(Status status, String line, String questionAsked, String nameGiven, boolean endsConversation,
                     boolean deflected, String notesUpdate) {
            this(status, line, questionAsked, nameGiven, endsConversation, deflected, notesUpdate, null, true,
                    Action.NONE, null);
        }

        private Turn(Status status, String line, String questionAsked, String nameGiven, boolean endsConversation,
                     boolean deflected, String notesUpdate, Feedback feedback, boolean addressed, Action action,
                     String target) {
            this.status = status;
            this.line = line;
            this.questionAsked = questionAsked;
            this.nameGiven = nameGiven;
            this.endsConversation = endsConversation;
            this.deflected = deflected;
            this.notesUpdate = notesUpdate;
            this.feedback = feedback;
            this.addressed = addressed;
            this.action = action == null ? Action.NONE : action;
            this.target = target;
        }

        /** This turn carrying feedback (null: none). */
        Turn withFeedback(Feedback f) {
            return new Turn(status, line, questionAsked, nameGiven, endsConversation, deflected, notesUpdate, f,
                    addressed, action, target);
        }

        /** This turn, said to him or not. */
        Turn withAddressed(boolean a) {
            return new Turn(status, line, questionAsked, nameGiven, endsConversation, deflected, notesUpdate, feedback,
                    a, action, target);
        }

        /** This turn carrying an instruction and its target (null or blank: none). */
        Turn withAction(Action a, String t) {
            String tt = t == null || t.trim().isEmpty() ? null : t.trim();
            return new Turn(status, line, questionAsked, nameGiven, endsConversation, deflected, notesUpdate, feedback,
                    addressed, a, tt);
        }

        static Turn line(String line, String questionAsked, String nameGiven, boolean endsConversation,
                         boolean deflected, String notesUpdate) {
            return new Turn(Status.LINE, line, questionAsked, nameGiven, endsConversation, deflected, notesUpdate);
        }

        /** A plain line with no question, no name, no ending, no deflection and no notes. */
        static Turn line(String line) {
            return line(line, null, null, false, false, null);
        }

        static Turn refused() {
            return new Turn(Status.REFUSED, null, null, null, false, false, null);
        }

        static Turn unreachable() {
            return new Turn(Status.UNREACHABLE, null, null, null, false, false, null);
        }

        static Turn failed() {
            return new Turn(Status.FAILED, null, null, null, false, false, null);
        }
    }

    /** The result of keep(): the new record's id, or a failure (no face, or the store refused). */
    final class Kept {
        enum Status { DONE, FAILED }

        static final Kept FAILED = new Kept(Status.FAILED, null);

        final Status status;
        final String personId;

        private Kept(Status status, String personId) {
            this.status = status;
            this.personId = personId;
        }

        static Kept done(String personId) {
            return personId == null || personId.isEmpty() ? FAILED : new Kept(Status.DONE, personId);
        }

        boolean ok() {
            return status == Status.DONE && personId != null;
        }
    }

    /** The result of a store write (notesDelta, forget): done, or failed. */
    final class Done {
        enum Status { DONE, FAILED }

        static final Done OK = new Done(Status.DONE);
        static final Done FAILED = new Done(Status.FAILED);

        final Status status;

        private Done(Status status) {
            this.status = status;
        }

        boolean ok() {
            return status == Status.DONE;
        }
    }
}
