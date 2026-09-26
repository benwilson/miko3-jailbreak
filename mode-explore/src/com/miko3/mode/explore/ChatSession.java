package com.miko3.mode.explore;

/**
 * The conversation beside the brain (meeting plan U6 skeleton; U8 fills it;
 * KTD7): the cue and conversation states, driven by the brain's tick inside
 * inStop() with the camera open and the detector parked. Plain Java with no
 * android.* imports and no clock of its own: the brain hands it the time, the
 * port and the ears, and it answers with what to do next.
 *
 * Until U8 lands it has no behaviour: it is constructed, reports the state it
 * was created in, and does nothing else, so the harness and the plain-Java rule
 * can already cover it.
 */
final class ChatSession {

    /**
     * The states (KTD7): turning toward the voice, the look that decides
     * (KTD4), then thinking (the turn request), speaking (the deaf window),
     * listening (the only state where looks run) and the notes merge at the end.
     */
    enum State { CUE_TURN, CUE_LOOK, CHAT_THINK, CHAT_SPEAK, CHAT_LISTEN, CHAT_NOTES }

    private final ExploreTuning tuning;
    private final CuriosityPort port;
    private final Ears ears;
    private State state;

    ChatSession(ExploreTuning tuning, CuriosityPort port, Ears ears, State from) {
        this.tuning = tuning;
        this.port = port;
        this.ears = ears;
        this.state = from;
    }

    State state() {
        return state;
    }

    ExploreTuning tuning() {
        return tuning;
    }

    CuriosityPort port() {
        return port;
    }

    Ears ears() {
        return ears;
    }
}
