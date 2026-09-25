package com.miko3.mode.explore;

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
interface CuriosityPort {

    /** False when Claude isn't set up or reachable at all: the stop runs as it did before U4. */
    boolean canAsk();

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
     * Compare the face in this frame's person box with the stored faces (KTD3).
     * Never carries names. When no face is found in the box, nothing is sent
     * for matching: the answer is a faceless NEW person (text-only lines).
     */
    void match(byte[] frameJpeg, Detection personBox, long timeoutMs);

    /** The answer to the last match(), or null while it is running. */
    MatchAnswer matchAnswer();

    /** Listen for a reply once speech is idle: up to maxMs, ending on trailing silence (KTD4). */
    void listen(long maxMs);

    /** What listen() heard, or null while it is listening. */
    Heard heard();

    /** A faceless new person replied: ask for a text-only "nice to meet you" line that never
     * promises to remember them. Stores nothing (R12). */
    void welcome(String name, long timeoutMs);

    /** The line to say after welcome(), or null while it is running. */
    Answer welcomed();

    /** Store the face from the last match() with this name (null: unnamed) and ask for the
     * "I'll remember you" line (R11, R12). */
    void remember(String name, long timeoutMs);

    /** The line to say after remember(), or null while it is running. */
    Answer remembered();

    /** Mark the person the last match() found as seen now (R10). Fire and forget. */
    void touch();

    /** A text-only request for ask_line and no_reply_line, when match() failed or was refused (KTD3). */
    void lines(long timeoutMs);

    /** NEW with the two lines, FAILED, or null while it is running. */
    MatchAnswer linesAnswer();

    /** The name in a heard reply: the robot's own patterns first, then a small text-only Claude request (KTD4). */
    void findName(String transcript, long timeoutMs);

    /** What findName() found, or null while it is running. */
    Named foundName();

    /** No Claude: every stop takes the path it took before U4. */
    CuriosityPort NONE = new CuriosityPort() {
        public boolean canAsk() {
            return false;
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

        public void listen(long maxMs) {
        }

        public Heard heard() {
            return Heard.NOTHING;
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

        public void findName(String transcript, long timeoutMs) {
        }

        public Named foundName() {
            return Named.FAILED;
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

    /** The look request: the scan's frames in order, the recent picks, and whether people are cooling down. */
    final class LookRequest {
        final List<Frame> frames;
        final List<Recent> recent;
        /** People and animals were greeted within the cool-down: prefer anything else. */
        final boolean livingCoolingDown;

        LookRequest(List<Frame> frames, List<Recent> recent, boolean livingCoolingDown) {
            this.frames = Collections.unmodifiableList(frames);
            this.recent = Collections.unmodifiableList(recent);
            this.livingCoolingDown = livingCoolingDown;
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
     * The person request's answer (KTD3). KNOWN carries the stored name (null when
     * unnamed); the lines are Claude's, with {name} still in namedLine.
     */
    final class MatchAnswer {
        enum Status { KNOWN, NEW, FAILED }

        static final MatchAnswer FAILED = new MatchAnswer(Status.FAILED, null, null, null, null, null);

        /** NEW with no face found: asked their name, but never matched or stored (R12). */
        final boolean faceless;

        final Status status;
        final String name;
        final String namedLine;
        final String unnamedLine;
        final String askLine;
        final String noReplyLine;

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
            this.faceless = faceless;
            this.status = status;
            this.name = name;
            this.namedLine = namedLine;
            this.unnamedLine = unnamedLine;
            this.askLine = askLine;
            this.noReplyLine = noReplyLine;
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

    /** What findName() found: a name, no clear name (the person is kept unnamed, R12), or a failure. */
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
}
