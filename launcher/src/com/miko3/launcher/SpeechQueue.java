package com.miko3.launcher;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The speech service's queue of lines (voice plan U5; R10, R11, KTD5). Plain
 * Java, so host tests run it with a fake voice.
 *
 * Speaking while already speaking queues the new line behind the others,
 * whoever asked; a line is never cut off mid-sentence. cancel(owner) drops
 * that caller's waiting lines at once and stops its playing line at the next
 * sentence boundary; other callers' lines are never touched. Every line ends
 * with exactly one callback: finished, cancelled (its owner asked), or
 * failed(reason) (the voice broke while making or playing it, or the queue
 * shut down with it still waiting, reason REFUSE_UNAVAILABLE).
 *
 * It also owns splitting a line into what sherpa-onnx is handed per generate
 * call: one sentence at a time, so the first sentence plays while the next is
 * made (R10), and a sentence longer than maxWords is split at its commas (U1:
 * the medium voice makes about 16 words in under 2 s on the robot).
 *
 * Threads: speak, cancel, cancelLine and shutdown come from Binder threads;
 * playNext runs on the one audio thread. Callbacks run outside the lock, on
 * whichever of those threads ended the line.
 */
final class SpeechQueue {
    static final String REFUSE_EMPTY = "nothing to say";
    static final String REFUSE_TOO_LONG = "line too long";
    static final String REFUSE_UNAVAILABLE = "speech unavailable";
    /** Why a line failed when synthesis or playback broke part way. */
    static final String FAIL_VOICE = "voice failed";
    /** About a minute of speech: plenty for any one line a mode says. */
    static final int MAX_CHARS = 1000;

    /** Told once how a line ended. */
    interface Listener {
        void finished();

        void cancelled();

        /** The line could not be (fully) said: reason is FAIL_VOICE or
         * REFUSE_UNAVAILABLE. */
        void failed(String reason);
    }

    /** Synthesis and playback, called only from the thread running playNext. */
    interface Voice {
        void startLine(long id, String text, long queuedAtNanos);

        /** Synthesizes one chunk and hands its audio to the speaker. May return
         * before it has all played. */
        void speak(String chunk);

        /** Returns once everything spoken for this line has played; or, if the
         * line is cancelled (cancelled here, or cancelRequested(id) while it
         * drains), once the sentence playing at that moment has finished. */
        void endLine(long id, boolean cancelled);
    }

    private static final class Line {
        final long id;
        final Object owner;
        final String text;
        final List<String> chunks;
        final Listener listener;
        final long queuedAtNanos;
        boolean cancelled; // guarded by the queue
        String failure; // guarded by the queue

        Line(long id, Object owner, String text, List<String> chunks, Listener listener) {
            this.id = id;
            this.owner = owner;
            this.text = text;
            this.chunks = chunks;
            this.listener = listener;
            this.queuedAtNanos = System.nanoTime();
        }
    }

    private final int maxWords;
    private final ArrayDeque<Line> waiting = new ArrayDeque<Line>();
    private Line playing;
    private boolean shut;
    private long nextId = 1;

    SpeechQueue(int maxWords) {
        this.maxWords = maxWords;
    }

    /**
     * Queues text for owner (the caller's uid in the service). Returns the
     * line's id. Throws IllegalArgumentException with one of the fixed
     * REFUSE_* reasons, having queued nothing.
     */
    long speak(Object owner, String text, Listener listener) {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException(REFUSE_EMPTY);
        }
        if (text.length() > MAX_CHARS) {
            throw new IllegalArgumentException(REFUSE_TOO_LONG);
        }
        List<String> chunks = chunks(text, maxWords);
        synchronized (this) {
            if (shut) {
                throw new IllegalArgumentException(REFUSE_UNAVAILABLE);
            }
            Line line = new Line(nextId++, owner, collapse(text), chunks, listener);
            waiting.addLast(line);
            notifyAll();
            return line.id;
        }
    }

    /** Drops owner's waiting lines and stops its playing line at the next
     * sentence boundary. Other owners' lines are untouched. */
    void cancel(Object owner) {
        List<Line> dropped = new ArrayList<Line>();
        synchronized (this) {
            for (Iterator<Line> it = waiting.iterator(); it.hasNext(); ) {
                Line l = it.next();
                if (l.owner.equals(owner)) {
                    it.remove();
                    dropped.add(l);
                }
            }
            if (playing != null && playing.owner.equals(owner)) {
                playing.cancelled = true;
            }
        }
        fireCancelled(dropped);
    }

    /** Cancels one line by id, as cancel() would; used when its caller dies. */
    void cancelLine(long id) {
        List<Line> dropped = new ArrayList<Line>();
        synchronized (this) {
            for (Iterator<Line> it = waiting.iterator(); it.hasNext(); ) {
                Line l = it.next();
                if (l.id == id) {
                    it.remove();
                    dropped.add(l);
                }
            }
            if (playing != null && playing.id == id) {
                playing.cancelled = true;
            }
        }
        fireCancelled(dropped);
    }

    /** No voice (it failed to load, or the launcher is going away): fails
     * every waiting line (and the playing one, at its next sentence boundary)
     * with REFUSE_UNAVAILABLE, and refuses new lines with it. */
    void shutdown() {
        List<Line> dropped;
        synchronized (this) {
            shut = true;
            dropped = new ArrayList<Line>(waiting);
            waiting.clear();
            if (playing != null) {
                playing.cancelled = true;
                playing.failure = REFUSE_UNAVAILABLE;
            }
            notifyAll();
        }
        for (Line l : dropped) {
            fireFailed(l, REFUSE_UNAVAILABLE);
        }
    }

    /** Lines waiting to play, not counting the one playing. */
    synchronized int queued() {
        return waiting.size();
    }

    /**
     * Plays the next line through voice and fires its callback. With block,
     * waits for a line; returns false once shut down (or, without block, when
     * nothing is waiting).
     */
    boolean playNext(Voice voice, boolean block) throws InterruptedException {
        Line line;
        synchronized (this) {
            while (block && waiting.isEmpty() && !shut) {
                wait();
            }
            if (shut || waiting.isEmpty()) {
                return false;
            }
            line = waiting.pollFirst();
            playing = line;
        }
        String failure = null;
        try {
            voice.startLine(line.id, line.text, line.queuedAtNanos);
            for (String chunk : line.chunks) {
                if (isCancelled(line)) {
                    break;
                }
                voice.speak(chunk);
            }
        } catch (RuntimeException e) {
            failure = FAIL_VOICE;
        }
        try {
            voice.endLine(line.id, failure != null || isCancelled(line));
        } catch (RuntimeException e) {
            failure = FAIL_VOICE;
        }
        boolean cancelled;
        synchronized (this) {
            cancelled = line.cancelled;
            if (failure == null) {
                failure = line.failure;
            }
            playing = null;
        }
        if (failure != null) {
            fireFailed(line, failure);
        } else {
            fire(line, cancelled);
        }
        return true;
    }

    /** True once the line with this id, playing now, has been cancelled (or
     * the queue shut down under it). The voice polls this while the line's
     * last audio drains, to stop at the end of the sentence then playing. */
    synchronized boolean cancelRequested(long id) {
        return playing != null && playing.id == id && playing.cancelled;
    }

    private synchronized boolean isCancelled(Line line) {
        return line.cancelled;
    }

    private static void fireCancelled(List<Line> lines) {
        for (Line l : lines) {
            fire(l, true);
        }
    }

    private static void fireFailed(Line line, String reason) {
        try {
            line.listener.failed(reason);
        } catch (RuntimeException ignored) {
            // A broken listener must not stop the queue.
        }
    }

    private static void fire(Line line, boolean cancelled) {
        try {
            if (cancelled) {
                line.listener.cancelled();
            } else {
                line.listener.finished();
            }
        } catch (RuntimeException ignored) {
            // A broken listener must not stop the queue.
        }
    }

    // ---- splitting ----

    /** Words before a "." that do not end a sentence. */
    private static final Set<String> ABBREVIATIONS = new HashSet<String>(Arrays.asList(
            "mr", "mrs", "ms", "dr", "prof", "st", "jr", "sr", "mt", "vs", "e.g", "i.e"));
    private static final String TERMINALS = ".!?";
    private static final String CLOSERS = "\"')]”’";

    static String collapse(String text) {
        return text.trim().replaceAll("\\s+", " ");
    }

    /** What sherpa-onnx is handed, in order: one sentence per chunk, with any
     * sentence over maxWords words split at its commas (at word boundaries
     * when a clause alone is too long). */
    static List<String> chunks(String text, int maxWords) {
        List<String> out = new ArrayList<String>();
        for (String sentence : sentences(text)) {
            out.addAll(splitLong(sentence, maxWords));
        }
        return out;
    }

    static List<String> sentences(String text) {
        String t = collapse(text == null ? "" : text);
        List<String> out = new ArrayList<String>();
        int start = 0;
        int n = t.length();
        for (int i = 0; i < n; i++) {
            char c = t.charAt(i);
            if (TERMINALS.indexOf(c) < 0) {
                continue;
            }
            int j = i;
            while (j + 1 < n && TERMINALS.indexOf(t.charAt(j + 1)) >= 0) {
                j++;
            }
            while (j + 1 < n && CLOSERS.indexOf(t.charAt(j + 1)) >= 0) {
                j++;
            }
            // "2.5", "example.com": no space after, so not an end.
            if (j + 1 < n && t.charAt(j + 1) != ' ') {
                i = j;
                continue;
            }
            if (c == '.' && j == i && isAbbreviation(t, start, i)) {
                continue;
            }
            String s = t.substring(start, j + 1).trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
            start = j + 1;
            i = j;
        }
        String rest = t.substring(Math.min(start, n)).trim();
        if (!rest.isEmpty()) {
            out.add(rest);
        }
        return out;
    }

    private static boolean isAbbreviation(String t, int start, int dot) {
        int b = t.lastIndexOf(' ', dot - 1) + 1;
        if (b < start) {
            b = start;
        }
        String word = t.substring(b, dot);
        if (word.length() == 1 && Character.isUpperCase(word.charAt(0))) {
            return true; // an initial: "J. Smith"
        }
        return ABBREVIATIONS.contains(word.toLowerCase(Locale.ROOT));
    }

    static List<String> splitLong(String sentence, int maxWords) {
        String[] words = sentence.split(" ");
        List<String> out = new ArrayList<String>();
        if (words.length <= maxWords) {
            out.add(sentence);
            return out;
        }
        // Clauses end at a word carrying , ; or :
        List<List<String>> clauses = new ArrayList<List<String>>();
        List<String> clause = new ArrayList<String>();
        for (String w : words) {
            clause.add(w);
            char last = w.charAt(w.length() - 1);
            if (last == ',' || last == ';' || last == ':') {
                clauses.add(clause);
                clause = new ArrayList<String>();
            }
        }
        if (!clause.isEmpty()) {
            clauses.add(clause);
        }
        List<String> current = new ArrayList<String>();
        for (List<String> c : clauses) {
            if (current.size() + c.size() <= maxWords) {
                current.addAll(c);
                continue;
            }
            if (!current.isEmpty()) {
                out.add(join(current));
                current = new ArrayList<String>();
            }
            if (c.size() <= maxWords) {
                current.addAll(c);
            } else {
                int i = 0;
                for (; i + maxWords < c.size(); i += maxWords) {
                    out.add(join(c.subList(i, i + maxWords)));
                }
                current.addAll(c.subList(i, c.size()));
            }
        }
        if (!current.isEmpty()) {
            out.add(join(current));
        }
        return out;
    }

    private static String join(List<String> words) {
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(w);
        }
        return sb.toString();
    }
}
