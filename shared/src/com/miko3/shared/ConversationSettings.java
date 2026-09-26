package com.miko3.shared;

/**
 * What the launcher's settings service tells a mode about conversations
 * (meeting plan U4, KTD11; R5, R20): the persona text Explore puts in the
 * middle block of its system prefix, and the "answers when spoken to" switch
 * the launcher's listening session reads when it classifies a cue.
 *
 * The persona is always usable text: the owner's box when it holds something,
 * else the launcher's built-in default, with personaSet saying which. The
 * launcher owns the default, so a mode never needs a copy of it. Plain Java
 * so host tests can build and inspect it; RobotSettings carries it across
 * Binder. toString() gives the length, never the text, so a log line stays
 * short.
 */
public final class ConversationSettings {
    /** The persona box's cap (KTD11), about 600 tokens. The page, the store
     * and scripts/robot-settings.py all enforce this one number. */
    public static final int MAX_PERSONA_CHARS = 2500;

    /** The text to use: the owner's, or the built-in default when unset. */
    public final String persona;
    /** True when the owner's box holds text; false means persona is the default. */
    public final boolean personaSet;
    /** R5: when false, only the wake word opens a conversation. Defaults to on. */
    public final boolean answersWhenSpokenTo;

    public ConversationSettings(String persona, boolean personaSet, boolean answersWhenSpokenTo) {
        this.persona = persona == null ? "" : persona;
        this.personaSet = personaSet;
        this.answersWhenSpokenTo = answersWhenSpokenTo;
    }

    @Override
    public String toString() {
        return "ConversationSettings{persona=" + persona.length() + " chars" + (personaSet ? "" : " (built-in)")
                + ", answersWhenSpokenTo=" + answersWhenSpokenTo + "}";
    }
}
