package com.miko3.mode.explore;

import android.content.Context;

import com.miko3.shared.RobotEars;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs EarsAdapter.onHeard on the host JVM (PR #29 review: until now only
 * regexes over its source checked it). Each scenario opens a fresh adapter
 * against the stub client, plays the launcher's deliveries in order, and reads
 * the brain's side through drain() and an armed Reply. Prints one line per
 * scenario: "PASS name" or "FAIL name: detail".
 */
public final class EarsAdapterHarness {
    private static int failures;

    /** A Reply that records what it was handed. */
    static final class Recorder implements EarsAdapter.Reply {
        final List<String> heard = new ArrayList<String>();

        @Override
        public void heard(String transcript) {
            heard.add(transcript);
        }

        final List<Long> answering = new ArrayList<Long>();

        @Override
        public void answering(long at) {
            answering.add(at);
        }

        final List<Long> answerOver = new ArrayList<Long>();

        @Override
        public void answerOver(long at) {
            answerOver.add(at);
        }
    }

    public static void main(String[] args) {
        run("early_wake_cue_is_queued_not_given_to_armed_reply", EarsAdapterHarness::earlyCueIsQueued);
        run("called_end_delivery_goes_to_armed_reply", EarsAdapterHarness::calledEndGoesToReply);
        run("called_end_delivery_without_reply_is_queued_called", EarsAdapterHarness::calledEndQueuedWhenUnarmed);
        run("called_partial_keeps_mark_and_is_not_joined", EarsAdapterHarness::calledPartialNotJoined);
        run("uncalled_strong_partial_joins_later_weak_cue", EarsAdapterHarness::uncalledPartialJoins);
        run("queue_overflow_drops_oldest", EarsAdapterHarness::overflowDropsOldest);
        run("worded_utterance_queued_with_side_and_angle", EarsAdapterHarness::wordedQueuedWithSideAndAngle);
        run("answering_goes_to_the_armed_reply_and_its_words_still_answer_it",
                EarsAdapterHarness::answeringGoesToArmedReply);
        run("answering_with_no_armed_reply_is_ignored_and_queues_nothing", EarsAdapterHarness::answeringUnarmedIgnored);
        run("answer_over_goes_to_the_armed_reply_and_queues_nothing", EarsAdapterHarness::answerOverGoesToArmedReply);
        run("answer_over_with_no_armed_reply_is_ignored", EarsAdapterHarness::answerOverUnarmedIgnored);
        run("a_called_end_carries_the_callers_message_and_an_older_launcher_none",
                EarsAdapterHarness::calledEndCarriesTheMessage);
        run("a_partial_answer_with_words_still_answers_the_armed_reply", EarsAdapterHarness::partialAnswerAnswers);
        System.exit(failures == 0 ? 0 : 1);
    }

    interface Scenario {
        String check() throws Exception;
    }

    private static void run(String name, Scenario s) {
        String detail;
        try {
            detail = s.check();
        } catch (Throwable t) {
            detail = "threw " + t;
        }
        if (detail == null) {
            System.out.println("PASS " + name);
        } else {
            failures++;
            System.out.println("FAIL " + name + ": " + detail);
        }
    }

    private static EarsAdapter opened() {
        EarsAdapter a = new EarsAdapter(new Context());
        a.open();
        return a;
    }

    /** The launcher's early wake cue: empty text, strong, kind WAKE_WORD, not yet called. */
    private static void earlyWake(EarsAdapter a, long at) {
        a.onHeard("", RobotEars.SIDE_LEFT, -20f, RobotEars.TIER_STRONG, at, false, RobotEars.KIND_WAKE_WORD, false, null);
    }

    /** The same utterance's delivery at its end, marked called, with its words. */
    private static void calledEnd(EarsAdapter a, long at, boolean partial) {
        a.onHeard("hey miko what is that", RobotEars.SIDE_LEFT, -20f, RobotEars.TIER_STRONG, at, partial,
                RobotEars.KIND_WAKE_WORD, true, "what is that");
    }

    private static String earlyCueIsQueued() {
        EarsAdapter a = opened();
        Recorder r = new Recorder();
        a.listen(8000, r);
        earlyWake(a, 1000);
        if (!r.heard.isEmpty()) {
            return "the armed reply was handed the early cue: " + r.heard;
        }
        List<Ears.Cue> q = a.drain();
        if (q.size() != 1) {
            return "expected one queued cue, got " + q;
        }
        Ears.Cue c = q.get(0);
        if (c.kind != Ears.Kind.WAKE_WORD || c.at != 1000 || c.alreadyCalled() || !c.strong()) {
            return "expected an uncalled strong WAKE_WORD@1000, got " + c + " called=" + c.called;
        }
        // The reply stays armed for the words: a worded utterance still reaches it.
        a.onHeard("hello", RobotEars.SIDE_NONE, Float.NaN, RobotEars.TIER_WEAK, 1500, false,
                RobotEars.KIND_VOICE, false, null);
        if (!r.heard.equals(java.util.Collections.singletonList("hello"))) {
            return "the reply was disarmed by the early cue; it heard " + r.heard;
        }
        return null;
    }

    private static String calledEndGoesToReply() {
        EarsAdapter a = opened();
        Recorder r = new Recorder();
        a.listen(8000, r);
        earlyWake(a, 1000);
        calledEnd(a, 1000, false);
        if (!r.heard.equals(java.util.Collections.singletonList("hey miko what is that"))) {
            return "the armed reply heard " + r.heard;
        }
        List<Ears.Cue> q = a.drain();
        if (q.size() != 1 || q.get(0).alreadyCalled()) {
            return "expected only the uncalled early cue in the queue, got " + q;
        }
        // The reply is spent: the next worded utterance is a cue.
        a.onHeard("again", RobotEars.SIDE_NONE, Float.NaN, RobotEars.TIER_WEAK, 3000, false,
                RobotEars.KIND_VOICE, false, null);
        if (r.heard.size() != 1 || a.drain().size() != 1) {
            return "the reply was not disarmed after its words";
        }
        return null;
    }

    private static String calledEndQueuedWhenUnarmed() {
        EarsAdapter a = opened();
        earlyWake(a, 1000);
        calledEnd(a, 1000, false);
        List<Ears.Cue> q = a.drain();
        if (q.size() != 2) {
            return "expected the early cue and the called end, got " + q;
        }
        Ears.Cue early = q.get(0);
        Ears.Cue end = q.get(1);
        if (early.alreadyCalled()) {
            return "the early cue came out called";
        }
        if (!end.alreadyCalled() || end.kind != Ears.Kind.WAKE_WORD || end.at != 1000) {
            return "expected a called WAKE_WORD@1000 at the end, got " + end + " called=" + end.called;
        }
        return null;
    }

    /** The adapter's held partial, read through reflection: nothing else exposes it. */
    private static Ears.Cue heldPartial(EarsAdapter a) throws Exception {
        Field f = EarsAdapter.class.getDeclaredField("partial");
        f.setAccessible(true);
        return (Ears.Cue) f.get(a);
    }

    /** The plan's U3: early cue, a clip-window cut makes the end partial, then a weak utterance. */
    private static String calledPartialNotJoined() throws Exception {
        EarsAdapter a = opened();
        earlyWake(a, 1000);
        calledEnd(a, 1000, true);
        Ears.Cue held = heldPartial(a);
        if (held == null) {
            return "the partial end delivery was not held";
        }
        if (!held.alreadyCalled() || held.kind != Ears.Kind.WAKE_WORD) {
            return "the held partial lost its mark: " + held + " called=" + held.called;
        }
        List<Ears.Cue> first = a.drain();
        if (first.size() != 1 || first.get(0).kind != Ears.Kind.WAKE_WORD) {
            return "expected only the early cue before the weak utterance, got " + first;
        }
        // Well inside PARTIAL_JOIN_MS of the partial's at.
        a.onHeard("mm", RobotEars.SIDE_RIGHT, 30f, RobotEars.TIER_WEAK, 1800, false, RobotEars.KIND_VOICE, false, null);
        List<Ears.Cue> q = a.drain();
        if (q.size() != 1) {
            return "expected one cue for the weak utterance, got " + q;
        }
        Ears.Cue c = q.get(0);
        if (c.kind == Ears.Kind.WAKE_WORD || c.strong()) {
            return "the called partial was joined into a second wake cue: " + c;
        }
        if (c.kind != Ears.Kind.VOICE || c.at != 1800 || c.side != Ears.Side.RIGHT || c.alreadyCalled()) {
            return "expected an uncalled weak VOICE RIGHT@1800, got " + c + " called=" + c.called;
        }
        if (heldPartial(a) != null) {
            return "the partial was still held after the next utterance";
        }
        return null;
    }

    /** Control for the scenario above: an uncalled strong partial does join, so the check has teeth. */
    private static String uncalledPartialJoins() {
        EarsAdapter a = opened();
        a.onHeard("hi th", RobotEars.SIDE_LEFT, -10f, RobotEars.TIER_STRONG, 1000, true,
                RobotEars.KIND_GREETING, false, null);
        if (!a.drain().isEmpty()) {
            return "a partial reached the queue";
        }
        a.onHeard("ere", RobotEars.SIDE_RIGHT, 25f, RobotEars.TIER_WEAK, 1000 + EarsAdapter.PARTIAL_JOIN_MS, false,
                RobotEars.KIND_VOICE, false, null);
        List<Ears.Cue> q = a.drain();
        if (q.size() != 1) {
            return "expected one joined cue, got " + q;
        }
        Ears.Cue c = q.get(0);
        if (c.kind != Ears.Kind.GREETING || !c.strong() || c.at != 2500 || c.side != Ears.Side.RIGHT
                || c.angleDeg != 25f) {
            return "expected a strong GREETING RIGHT 25deg@2500 (partial's kind, whole's side/angle/at), got " + c;
        }
        return null;
    }

    private static String overflowDropsOldest() {
        EarsAdapter a = opened();
        int n = EarsAdapter.QUEUE_MAX + 2;
        for (int i = 1; i <= n; i++) {
            a.onHeard("word " + i, RobotEars.SIDE_NONE, Float.NaN, RobotEars.TIER_WEAK, i * 100L, false,
                    RobotEars.KIND_VOICE, false, null);
        }
        List<Ears.Cue> q = a.drain();
        if (q.size() != EarsAdapter.QUEUE_MAX) {
            return "expected " + EarsAdapter.QUEUE_MAX + " cues, got " + q.size();
        }
        // The oldest two (at 100 and 200) are gone; the rest are in arrival order.
        for (int i = 0; i < q.size(); i++) {
            long want = (i + 3) * 100L;
            if (q.get(i).at != want) {
                return "cue " + i + " is @" + q.get(i).at + ", expected @" + want + "; queue " + q;
            }
        }
        if (!a.drain().isEmpty()) {
            return "drain did not empty the queue";
        }
        return null;
    }

    /** Robot 2026-10-01: the launcher's "answering" reaches the armed reply; the words at its end answer it. */
    private static String answeringGoesToArmedReply() {
        EarsAdapter a = opened();
        Recorder r = new Recorder();
        a.listen(4000, r);
        a.onAnswering(2500);
        if (!r.answering.equals(java.util.Collections.singletonList(2500L)) || !r.heard.isEmpty()) {
            return "the reply was told " + r.answering + " and heard " + r.heard;
        }
        if (!a.drain().isEmpty()) {
            return "answering queued a cue";
        }
        a.onHeard("we went to the beach", RobotEars.SIDE_LEFT, -20f, RobotEars.TIER_WEAK, 2500, false,
                RobotEars.KIND_VOICE, false, null);
        if (!r.heard.equals(java.util.Collections.singletonList("we went to the beach")) || !a.drain().isEmpty()) {
            return "the answer's words did not reach the reply: " + r.heard;
        }
        return null;
    }

    /** An answer after the listen was retired (or before any) goes nowhere: the brain moved on. */
    private static String answeringUnarmedIgnored() {
        EarsAdapter a = opened();
        a.onAnswering(100);
        Recorder r = new Recorder();
        a.listen(4000, r);
        a.listenOver(r);
        a.onAnswering(2500);
        EarsAdapter closed = new EarsAdapter(new Context());
        Recorder c = new Recorder();
        closed.listen(4000, c);
        closed.onAnswering(300);
        if (!r.answering.isEmpty() || !c.answering.isEmpty() || !a.drain().isEmpty()) {
            return "an unarmed or closed adapter passed answering on: " + r.answering + " " + c.answering;
        }
        return null;
    }

    /** Review P2-2: the launcher's "answer over" reaches the armed reply, which stays armed; no cue. */
    private static String answerOverGoesToArmedReply() {
        EarsAdapter a = opened();
        Recorder r = new Recorder();
        a.listen(4000, r);
        a.onAnswering(1000);
        a.onAnswerOver(1000);
        if (!r.answerOver.equals(java.util.Collections.singletonList(1000L)) || !r.heard.isEmpty()
                || !a.drain().isEmpty()) {
            return "the reply was told over=" + r.answerOver + " heard=" + r.heard;
        }
        return null;
    }

    /** "answer over" for a retired listen (or a closed adapter) goes nowhere. */
    private static String answerOverUnarmedIgnored() {
        EarsAdapter a = opened();
        Recorder r = new Recorder();
        a.listen(4000, r);
        a.listenOver(r);
        a.onAnswerOver(2500);
        EarsAdapter closed = new EarsAdapter(new Context());
        Recorder c = new Recorder();
        closed.listen(4000, c);
        closed.onAnswerOver(300);
        if (!r.answerOver.isEmpty() || !c.answerOver.isEmpty() || !a.drain().isEmpty()) {
            return "an unarmed or closed adapter passed answer over on: " + r.answerOver + " " + c.answerOver;
        }
        return null;
    }

    private static String wordedQueuedWithSideAndAngle() {
        EarsAdapter a = opened();
        a.onHeard("over here", RobotEars.SIDE_LEFT, -35f, RobotEars.TIER_WEAK, 100, false,
                RobotEars.KIND_VOICE, false, null);
        a.onHeard("and here", RobotEars.SIDE_RIGHT, 42.5f, RobotEars.TIER_STRONG, 200, false,
                RobotEars.KIND_NAME, false, null);
        a.onHeard("somewhere", RobotEars.SIDE_NONE, Float.NaN, RobotEars.TIER_WEAK, 300, false,
                RobotEars.KIND_VOICE, false, null);
        List<Ears.Cue> q = a.drain();
        if (q.size() != 3) {
            return "expected three cues, got " + q;
        }
        Ears.Cue l = q.get(0);
        Ears.Cue r = q.get(1);
        Ears.Cue u = q.get(2);
        if (l.kind != Ears.Kind.VOICE || l.side != Ears.Side.LEFT || l.angleDeg != -35f || l.strong()
                || l.at != 100 || l.alreadyCalled()) {
            return "left cue wrong: " + l;
        }
        if (r.kind != Ears.Kind.NAME || r.side != Ears.Side.RIGHT || r.angleDeg != 42.5f || !r.strong()
                || r.at != 200) {
            return "right cue wrong: " + r;
        }
        if (u.side != Ears.Side.UNKNOWN || !Float.isNaN(u.angleDeg) || u.hasAngle() || u.at != 300) {
            return "no-angle cue wrong: " + u;
        }
        return null;
    }

    /** Owner 2026-10-02: the caller's words besides the address ride the called end's cue. */
    private static String calledEndCarriesTheMessage() {
        EarsAdapter a = opened();
        earlyWake(a, 1000);
        calledEnd(a, 1000, false);
        a.onHeard("hey miko", RobotEars.SIDE_LEFT, -20f, RobotEars.TIER_STRONG, 5000, false,
                RobotEars.KIND_WAKE_WORD, true, null);
        a.onHeard("miko come here", RobotEars.SIDE_LEFT, -20f, RobotEars.TIER_STRONG, 9000, false,
                RobotEars.KIND_NAME, false, "come here");
        List<Ears.Cue> q = a.drain();
        if (q.size() != 4) {
            return "expected four cues, got " + q;
        }
        if (!"".equals(q.get(0).message) || !"what is that".equals(q.get(1).message)
                || !"".equals(q.get(2).message) || !"come here".equals(q.get(3).message)) {
            return "messages early=" + q.get(0).message + " end=" + q.get(1).message + " old=" + q.get(2).message
                    + " name=" + q.get(3).message;
        }
        if (!q.get(1).hasMessage() || q.get(2).hasMessage() || q.get(0).hasMessage()) {
            return "hasMessage wrong";
        }
        return null;
    }

    /**
     * Robot 2026-10-02: an answer that began as the deaf window closed (they spoke as his line
     * ended) comes flagged partial. The launcher ends its listen on its words, so the armed
     * reply must hear them; held as a partial cue, they were lost and the mode waited out the
     * 23 s hold for words that never came.
     */
    private static String partialAnswerAnswers() {
        EarsAdapter a = opened();
        Recorder r = new Recorder();
        a.listen(4000, r);
        a.onHeard("we went to the beach", RobotEars.SIDE_NONE, Float.NaN, RobotEars.TIER_WEAK, 2000, true,
                RobotEars.KIND_VOICE, false, null);
        if (!r.heard.equals(java.util.Collections.singletonList("we went to the beach"))) {
            return "the armed reply heard " + r.heard;
        }
        // Unarmed, a wordless or worded partial is still held as before (joined or dropped).
        a.onHeard("hi th", RobotEars.SIDE_LEFT, -10f, RobotEars.TIER_STRONG, 6000, true,
                RobotEars.KIND_GREETING, false, null);
        if (!a.drain().isEmpty()) {
            return "an unarmed partial reached the queue";
        }
        return null;
    }
}
