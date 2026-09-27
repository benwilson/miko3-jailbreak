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
 * the replacement rule (KTD3) itself; nothing here decides anything. No text
 * crosses this interface: an utterance's words reach the brain only through
 * CuriosityPort.listen()/heard() while he is deliberately listening (KTD1).
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

        Cue(Kind kind, Tier tier, Side side, float angleDeg, long at) {
            this.kind = kind;
            this.tier = tier;
            this.side = side;
            this.angleDeg = angleDeg;
            this.at = at;
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
}
