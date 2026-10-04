package com.miko3.mode.explore;

import java.util.List;

/**
 * The ears as step input (meeting plan U6, KTD1, KTD3, KTD4), beside
 * ExploreBrain.Camera: what the launcher's continuous listening session has
 * classified since the brain last looked, the latched direction angle's trend
 * while he turns toward a voice, and accelerometer shove spikes. Plain Java
 * with no android.* imports, so the brain stays host-testable; the live
 * adapter (U7) fills it from the session's callbacks on its own thread and
 * only enqueues, and the harness rig fakes it.
 *
 * The brain drains it once per tick, as it polls Camera.latest(), and applies
 * the replacement rule (KTD3) itself; nothing here decides anything. An
 * utterance's words reach the brain through CuriosityPort.listen()/heard()
 * while he is deliberately listening (KTD1), with one exception (owner
 * 2026-10-02): a call's own words besides the address ride its cue as its
 * message, since a call opens the conversation and they are its first message.
 */
interface Ears {

    /** A cue's strength (KTD3): strong opens a conversation, weak earns a lean-in. */
    enum Tier { WEAK, STRONG }

    /**
     * What the session heard (KTD3). Strong: the wake word, his name, a clear
     * greeting. Weak: a burst of voice or a half-heard word. An apology is weak
     * on its own; the brain makes it strong within 2 s of a shove or a bump.
     * The session sets the tier and names the kind (it alone sees the
     * wake-word engine and the shove clock; the kind rides its callback); the
     * kind is for the rules that need it: only the wake word opens a
     * conversation in EYES_ONLY (KTD8), only an apology upgrades after a shove.
     */
    enum Kind {
        WAKE_WORD(Tier.STRONG), NAME(Tier.STRONG), GREETING(Tier.STRONG), APOLOGY(Tier.WEAK), VOICE(Tier.WEAK);

        final Tier tier;

        Kind(Tier tier) {
            this.tier = tier;
        }
    }

    /** Which side the voice came from; UNKNOWN when the mics tie and no angle backend answered. */
    enum Side { LEFT, RIGHT, UNKNOWN }

    /**
     * One cue: {kind, tier, side, angle, at}. angleDeg is the angle latched over
     * the utterance (the median of the samples, KTD4), signed: negative to his
     * left, positive to his right, near zero ahead, a magnitude toward 180 behind
     * him; NaN when this unit has no angle backend. U2's measurement fixes the
     * mapping from the DSP's raw value; the adapter applies it. at is brain time.
     */
    final class Cue {
        final Kind kind;
        final Tier tier;
        final Side side;
        final float angleDeg;
        final long at;
        /**
         * Hey Miko plan KTD4: the end-of-utterance delivery of a wake word the
         * launcher already sent as an early cue for this at. The call was made
         * then, so this cue makes no second one. False unless the launcher says so.
         */
        final boolean called;
        /**
         * Owner 2026-10-02: a call's words besides the address ("how's it going" from "Hey Miko,
         * how's it going?"), the caller's first message; "" for a bare call, an early cue, any
         * other cue, and everything from an older launcher.
         */
        final String message;

        Cue(Kind kind, Tier tier, Side side, float angleDeg, long at) {
            this(kind, tier, side, angleDeg, at, false);
        }

        Cue(Kind kind, Tier tier, Side side, float angleDeg, long at, boolean called) {
            this(kind, tier, side, angleDeg, at, called, "");
        }

        Cue(Kind kind, Tier tier, Side side, float angleDeg, long at, boolean called, String message) {
            this.message = message == null ? "" : message.trim();
            this.kind = kind;
            this.tier = tier;
            this.side = side;
            this.angleDeg = angleDeg;
            this.at = at;
            this.called = called;
        }

        /** A cue whose tier is its kind's. */
        static Cue of(Kind kind, Side side, float angleDeg, long at) {
            return new Cue(kind, kind.tier, side, angleDeg, at);
        }

        boolean strong() {
            return tier == Tier.STRONG;
        }

        boolean hasAngle() {
            return !Float.isNaN(angleDeg);
        }

        /** Words of the caller's besides the address (owner 2026-10-02). */
        boolean hasMessage() {
            return !message.isEmpty();
        }

        /** The call for this at was already made by the early cue (KTD4). */
        boolean alreadyCalled() {
            return called;
        }

        @Override
        public String toString() {
            return kind + " " + tier + " " + side + " " + (hasAngle() ? Math.round(angleDeg) + "deg" : "no-angle")
                    + "@" + at;
        }
    }

    /**
     * The latched angle's trend (KTD4): the newest sample while speech is
     * present or during CUE_TURN, and the one before it (NaN when there is
     * none). He stops turning when the magnitude is under the stop band or
     * starts growing, which means the voice was behind him.
     */
    final class Trend {
        final float angleDeg;
        final float previousDeg;
        final long at;

        Trend(float angleDeg, float previousDeg, long at) {
            this.angleDeg = angleDeg;
            this.previousDeg = previousDeg;
            this.at = at;
        }

        /** True when the magnitude grew since the previous sample (a voice behind him). */
        boolean growing() {
            return !Float.isNaN(previousDeg) && Math.abs(angleDeg) > Math.abs(previousDeg);
        }

        @Override
        public String toString() {
            return Math.round(angleDeg) + "deg" + (Float.isNaN(previousDeg) ? "" : " from " + Math.round(previousDeg))
                    + "@" + at;
        }
    }

    /**
     * An accelerometer spike (KTD5): a shove or a bump, in the controller's
     * counts above rest. The brain arms it as a weak cue only while the wheels
     * are commanded stopped and outside the blanking window after a motor command.
     */
    final class Shove {
        final int counts;
        final long at;

        Shove(int counts, long at) {
            this.counts = counts;
            this.at = at;
        }

        @Override
        public String toString() {
            return "shove " + counts + "@" + at;
        }
    }

    /** False when no ears session can ever open (an older launcher, no microphone): cues never come. */
    boolean present();

    /** True while the session is open and classifying (the charger flag closes it, KTD6). */
    boolean listening();

    /**
     * Every cue enqueued since the last drain, oldest first; never null, empty
     * when nothing came. Called once per tick by the brain (KTD1).
     */
    List<Cue> drain();

    /** The latched angle's newest trend, or null when none has arrived since the last call. */
    Trend trend();

    /** The newest shove spike since the last call, or null. */
    Shove shove();

    /** No ears (an older launcher): nothing is ever heard and the meeting runs as it does today. */
    Ears NONE = new Ears() {
        public boolean present() {
            return false;
        }

        public boolean listening() {
            return false;
        }

        public List<Cue> drain() {
            return java.util.Collections.emptyList();
        }

        public Trend trend() {
            return null;
        }

        public Shove shove() {
            return null;
        }
    };

    /**
     * One conversation answer's voice identification (owner 2026-10-02): at is the answer's
     * speech start (the launcher's key for its embedding), personId the closest stored voice
     * or null, band one of NONE, WEAK, STRONG (RobotEars.VOICE_*). The id is never logged.
     */
    final class Voice {
        static final int NONE = 0;
        static final int WEAK = 1;
        static final int STRONG = 2;

        final long at;
        final String personId;
        final float score;
        final int band;
        /**
         * Owner 2026-10-03: the score's lead over the second-best stored person, NaN when only
         * one person has prints (or an older launcher sent none). The launcher's STRONG band
         * already requires it to be wide (VoiceTuning.MARGIN); it is here for the identity log.
         */
        final float margin;

        Voice(long at, String personId, float score, int band) {
            this(at, personId, score, band, Float.NaN);
        }

        Voice(long at, String personId, float score, int band, float margin) {
            this.at = at;
            this.band = band == STRONG || band == WEAK ? band : NONE;
            this.personId = this.band == NONE || personId == null || personId.isEmpty() ? null : personId;
            this.score = score;
            this.margin = margin;
        }

        /** The margin for a log line: two decimals, or "solo" when nobody else has prints. */
        String marginWord() {
            return Float.isNaN(margin) ? "solo" : String.format(java.util.Locale.US, "%.2f", margin);
        }

        boolean strong() {
            return band == STRONG && personId != null;
        }

        /** strong, weak or none, for the learning log. */
        String word() {
            return band == STRONG ? "strong" : band == WEAK ? "weak" : "none";
        }

        /** The band only: never the id or the score. */
        @Override
        public String toString() {
            return "voice " + word();
        }
    }

}
