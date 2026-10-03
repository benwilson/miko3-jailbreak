package com.miko3.launcher;

/**
 * Owner 2026-10-02: what the launcher's people layer calls to teach and unlearn voices.
 * The ears identify each clean conversation answer and send the mode
 * RobotEars.Callback.voice(at, person, score, band), keyed by the answer's at; once the
 * mode knows who said it (a name confirmed, a face matched), the people layer enrols that
 * answer's embedding under the person's id. Reached in-process as
 * ((LauncherApp) getApplication()).listen().voicePrints(). Ids are opaque to the voice
 * store and never logged. Never holds or returns audio.
 */
interface VoicePrints {
    /** The embedding computed for the answer whose speech began at at, or null (not computed, or too old). */
    float[] lastEmbeddingFor(long at);

    /** Stores embedding under personId (capped per person, oldest dropped); false for a null id or embedding. */
    boolean enrolVoice(String personId, float[] embedding);

    /** enrolVoice(personId, lastEmbeddingFor(at)). */
    boolean enrolVoice(String personId, long at);

    /** Forgets every voice embedding stored for personId; false when there were none. */
    boolean forgetVoice(String personId);

    /** How many embeddings are stored for personId. */
    int voiceCount(String personId);
}
