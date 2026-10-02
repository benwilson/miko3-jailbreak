package com.miko3.mode.explore;

import android.content.Context;
import android.util.Log;

import com.miko3.shared.RobotEars;
import com.miko3.shared.RobotEarsClient;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The launcher's continuous ears as the brain's step input (meeting plan U7;
 * KTD1, KTD3, KTD5, KTD6). One RobotEarsClient per open: ClaudeCuriosity's
 * earsOpen() and earsClose() come from the brain as the charger latch clears
 * and sets, so a fresh client is bound each time (close() is terminal on the
 * client). Every heard utterance {text, side, angle, tier, at, partial, kind}
 * is mapped onto an Ears.Cue on the client's thread and enqueued in a small
 * bounded queue the brain drains once per tick; nothing is decided here. The
 * kind arrives named by the launcher's classifier (the last field, owner's
 * ruling 2026-09-26) and is only mapped onto Ears.Kind: the brain needs it
 * because only the wake word opens a conversation in EYES_ONLY and only an
 * apology upgrades after a shove, and the launcher alone sees the wake-word
 * engine and the shove clock. No lexicon lives here.
 *
 * Partial utterances (the deaf window clipped them) are held rather than
 * enqueued: the next whole utterance within PARTIAL_JOIN_MS takes the stronger
 * of the two tiers, and a partial with nothing after it is dropped.
 *
 * The wake word (Hey Miko plan KTD4): the launcher sends it as an early cue
 * (empty text) as soon as it is spotted, and marks the utterance's own
 * delivery at its end as already called. The mark rides the cue into the
 * queue, and a held partial keeps it. An already-called partial is never
 * joined into a later cue: that would be a second call with a new at. An
 * early cue has no words, so it never goes to an armed reply; it reaches the
 * queue while the reply stays armed for the words.
 *
 * A conversation listen's answer that has started (robot 2026-10-01): the
 * launcher says so once, before the words, and the armed reply hears it, so
 * ClaudeCuriosity holds the listen past its maxMs for the words.
 *
 * The accelerometer arrives on the drive's readings (ExploreDrive.ReadingListener):
 * a magnitude step above the resting level is a shove spike for the brain, which
 * arms it only while stopped and past its blanking window (KTD5). The charger
 * latch on each reading goes to the client's renews. A conversation listen
 * (the meeting's name reply) routes through the session while it is open, so
 * the one microphone capture is never contended (KTD1).
 *
 * Privacy (R21): the text is classified into a kind and forgotten; nothing here
 * logs an utterance, only counts and fixed reasons.
 */
final class EarsAdapter implements Ears, RobotEarsClient.Listener, ExploreDrive.ReadingListener {
    private static final String TAG = "ExploreEars";
    /** Cues waiting for the brain's next tick; older ones are dropped when it fills. */
    static final int QUEUE_MAX = 8;
    /** A whole utterance this soon after a clipped one is the same address. */
    static final long PARTIAL_JOIN_MS = 1500;
    /**
     * Accelerometer magnitude above the resting level that counts as a shove, in
     * the controller's counts; a placeholder until U2's measurement session sets it.
     */
    static final int SHOVE_MIN_COUNTS = 300;
    /** Two spikes closer than this are one shove. */
    static final long SHOVE_GAP_MS = 500;
    /** The resting magnitude follows readings slowly (one part in twenty per reading). */
    private static final double REST_ALPHA = 0.05;

    /** A conversation listen's answer (KTD1), delivered on the client's thread. */
    interface Reply {
        void heard(String transcript);

        /** Robot 2026-10-01: the launcher says this listen's answer has started (speech began at at). */
        void answering(long at);

        /** Review 2026-10-01 (P2-2): the launcher says that answer ended without words. */
        void answerOver(long at);
    }

    private final Context app;
    private final Object lock = new Object();
    private RobotEarsClient client;
    private boolean open;
    private final ArrayDeque<Ears.Cue> queue = new ArrayDeque<Ears.Cue>();
    /**
     * The angle's trend during the turn (KTD4). The ears Binder (U3) carries only
     * the angle latched over an utterance, not samples, so live it stays null and
     * the turn goes by the latched angle alone; the brain's trend rule is proven in
     * the harness. Fed here when the session streams samples.
     */
    private Ears.Trend trend;
    private Ears.Shove shove;
    private Ears.Cue partial;
    private Reply reply;
    /** The conversation listen's newcomer angle (KTD8), or NaN for a meeting listen. */
    private float replyAngleDeg = Float.NaN;
    private volatile boolean charger;
    private long heardCount;
    private long droppedCount;
    private long partialCount;
    // The accelerometer's resting magnitude and the last spike, on the brain's thread only.
    private double restMagnitude = Double.NaN;
    private long lastSpikeAt = Long.MIN_VALUE / 4;

    EarsAdapter(Context context) {
        app = context.getApplicationContext();
    }

    // ---- the port's ears calls (ClaudeCuriosity) ----

    /** Opens the session with the current charger latch; a session already open is kept. */
    void open() {
        RobotEarsClient c;
        synchronized (lock) {
            if (open) {
                return;
            }
            open = true;
            c = new RobotEarsClient(app);
            client = c;
        }
        Log.i(TAG, "ears open" + (charger ? " (charger latched)" : ""));
        c.open(charger, this);
    }

    /** Closes the session and releases the microphone; idempotent. */
    void close() {
        RobotEarsClient c;
        synchronized (lock) {
            if (!open) {
                return;
            }
            c = client;
            resetLocked();
        }
        Log.i(TAG, "ears closed: heard " + heardCount + ", dropped " + droppedCount + ", partial " + partialCount);
        if (c != null) {
            c.close();
        }
    }

    /** Back to closed with nothing pending: no client, no cues, no partial, no reply. Under lock. */
    private void resetLocked() {
        open = false;
        client = null;
        queue.clear();
        partial = null;
        reply = null;
    }

    boolean isOpen() {
        synchronized (lock) {
            return open;
        }
    }

    /** ModeApp, as Explore stops: nothing reaches the brain after this. */
    void release() {
        close();
    }

    /** The deaf window for a local clip (KTD1, KTD12). */
    void clipWindow(long ms) {
        RobotEarsClient c = current();
        if (c != null) {
            c.clipWindow(ms);
        }
    }

    /** A shove while stopped or a collision stop while driving, at brain time (KTD5). */
    void shoved(long atMs) {
        RobotEarsClient c = current();
        if (c != null) {
            c.shoved(atMs);
        }
    }

    /** A listen through the session: the next whole utterance with words is the reply. */
    void listen(long maxMs, Reply r) {
        listen(maxMs, Float.NaN, r);
    }

    /**
     * A conversation listen (meeting plan U8, KTD8): the next whole utterance with
     * words is the reply, except a strong one whose latched angle magnitude exceeds
     * newcomerAngleDeg (NaN: none does), which is queued as a newcomer cue instead.
     */
    void listen(long maxMs, float newcomerAngleDeg, Reply r) {
        RobotEarsClient c;
        synchronized (lock) {
            reply = r;
            replyAngleDeg = newcomerAngleDeg;
            c = client;
        }
        if (c != null) {
            c.listen(maxMs);
        }
    }

    /**
     * Retires r once its listen is over (silence, or the brain moving on), but
     * only while r is still the armed reply, so a newer listen's reply stays.
     * Without this a reply whose listen ended in silence would capture the next
     * utterance with words, which then never reached the cue queue.
     */
    void listenOver(Reply r) {
        synchronized (lock) {
            if (reply == r) {
                reply = null;
                replyAngleDeg = Float.NaN;
            }
        }
    }

    private RobotEarsClient current() {
        synchronized (lock) {
            return open ? client : null;
        }
    }

    // ---- RobotEarsClient.Listener: the launcher's thread ----

    @Override
    public void onHeard(String text, int side, float angle, int tier, long at, boolean partialUtterance, int kind,
                        boolean called) {
        Ears.Tier t = tier == RobotEars.TIER_STRONG ? Ears.Tier.STRONG : Ears.Tier.WEAK;
        Ears.Side s = side == RobotEars.SIDE_LEFT ? Ears.Side.LEFT
                : side == RobotEars.SIDE_RIGHT ? Ears.Side.RIGHT : Ears.Side.UNKNOWN;
        Ears.Kind k = kindOf(kind, t);
        // The DSP's angle is already signed the brain's way (VoiceDirection: negative left); NaN passes through.
        Ears.Cue cue = new Ears.Cue(k, t, s, angle, at, called);
        boolean words = text != null && !text.trim().isEmpty();
        Reply r = null;
        synchronized (lock) {
            if (!open) {
                return;
            }
            heardCount++;
            if (partialUtterance) {
                partialCount++;
                partial = cue;
                return;
            }
            boolean newcomer = cue.strong() && !Float.isNaN(replyAngleDeg) && cue.hasAngle()
                    && Math.abs(angle) > replyAngleDeg;
            if (reply != null && words && !newcomer) {
                r = reply;
                reply = null;
            } else {
                if (partial != null && !partial.alreadyCalled() && at - partial.at <= PARTIAL_JOIN_MS
                        && partial.strong() && !cue.strong()) {
                    cue = new Ears.Cue(partial.kind, Ears.Tier.STRONG, s, angle, at);
                }
                partial = null;
                if (queue.size() >= QUEUE_MAX) {
                    queue.pollFirst();
                    droppedCount++;
                }
                queue.addLast(cue);
            }
        }
        if (r != null) {
            r.heard(text);
        }
    }

    /**
     * Robot 2026-10-01: the launcher's conversation listen claimed an utterance, so its
     * answer has started. It goes to the armed reply, which stays armed for the words;
     * with none armed (the listen already ended, or none was open) it is dropped: no cue.
     */
    @Override
    public void onAnswering(long at) {
        Reply r;
        synchronized (lock) {
            r = open ? reply : null;
        }
        if (r != null) {
            r.answering(at);
        }
    }

    /**
     * Review 2026-10-01 (P2-2): the answer the launcher announced ended without words. It
     * goes to the armed reply (which ends its hold); with none armed it is dropped: no cue.
     */
    @Override
    public void onAnswerOver(long at) {
        Reply r;
        synchronized (lock) {
            r = open ? reply : null;
        }
        if (r != null) {
            r.answerOver(at);
        }
    }

    /** The wire's RobotEars.KIND_* as an Ears.Kind; a kind this build does not know falls back by tier. */
    static Ears.Kind kindOf(int wire, Ears.Tier tier) {
        switch (wire) {
            case RobotEars.KIND_WAKE_WORD:
                return Ears.Kind.WAKE_WORD;
            case RobotEars.KIND_NAME:
                return Ears.Kind.NAME;
            case RobotEars.KIND_GREETING:
                return Ears.Kind.GREETING;
            case RobotEars.KIND_APOLOGY:
                return Ears.Kind.APOLOGY;
            case RobotEars.KIND_VOICE:
                return Ears.Kind.VOICE;
            default:
                Log.w(TAG, "unknown cue kind " + wire + " from the launcher; taking the tier's default");
                return tier == Ears.Tier.STRONG ? Ears.Kind.GREETING : Ears.Kind.VOICE;
        }
    }

    @Override
    public void onLost(String reason) {
        Log.w(TAG, "ears lost: " + reason);
        RobotEarsClient c;
        synchronized (lock) {
            // The brain re-opens on its own terms (the next charger clear or start); until then no cues come.
            c = client;
            resetLocked();
        }
        if (c != null) {
            // Unbind and stop its worker now, not at the next open: close() is
            // idempotent and safe from the client's own worker, which calls here.
            c.close();
        }
    }

    // ---- ExploreDrive.ReadingListener: the brain's thread ----

    @Override
    public void onReading(SensorReading r) {
        if (charger != r.charger) {
            charger = r.charger;
            RobotEarsClient c = current();
            if (c != null) {
                c.setCharger(r.charger);
            }
        }
        if (!r.hasAccel) {
            return;
        }
        double m = Math.sqrt((double) r.accelX * r.accelX + (double) r.accelY * r.accelY
                + (double) r.accelZ * r.accelZ);
        if (Double.isNaN(restMagnitude)) {
            restMagnitude = m;
            return;
        }
        double above = Math.abs(m - restMagnitude);
        if (above >= SHOVE_MIN_COUNTS) {
            if (r.timestampMs - lastSpikeAt >= SHOVE_GAP_MS) {
                lastSpikeAt = r.timestampMs;
                synchronized (lock) {
                    shove = new Ears.Shove((int) Math.round(above), r.timestampMs);
                }
            }
            return;
        }
        restMagnitude += REST_ALPHA * (m - restMagnitude);
    }

    // ---- Ears: the brain's thread, once per tick ----

    @Override
    public boolean present() {
        return true;
    }

    @Override
    public boolean listening() {
        return isOpen();
    }

    @Override
    public List<Ears.Cue> drain() {
        synchronized (lock) {
            if (queue.isEmpty()) {
                return Collections.emptyList();
            }
            List<Ears.Cue> out = new ArrayList<Ears.Cue>(queue);
            queue.clear();
            return out;
        }
    }

    @Override
    public Ears.Trend trend() {
        synchronized (lock) {
            Ears.Trend t = trend;
            trend = null;
            return t;
        }
    }

    @Override
    public Ears.Shove shove() {
        synchronized (lock) {
            Ears.Shove s = shove;
            shove = null;
            return s;
        }
    }
}
