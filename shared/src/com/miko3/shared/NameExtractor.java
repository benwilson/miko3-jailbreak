package com.miko3.shared;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pulls a name out of a short spoken reply to "what's your name?"
 * (explore-on-claude plan U3; R11, R12, KTD4). Plain Java, so host tests run it.
 *
 * The launcher's recognizer returns upper case with no punctuation ("MY NAME
 * IS SARAH"); people type or say it any way. The patterns: "my name is X",
 * "my name's X", "I'm X", "I am X", "call me X", "it's X", "this is X", or the
 * name alone. X is one or two words ("i am sarah jones" gives "Sarah Jones"),
 * stopping at a word like "thanks" or "from". A greeting or filler before it
 * ("hi", "um") is skipped, and so is a contraction tail the recognizer
 * clipped from the front ("'S BEN" gives "Ben"). Stray single letters are dropped.
 *
 * Returns the name in title case, or null when the reply has no clear name
 * ("what?", "no", "I don't know", "I'm fine"). null is not "no reply": the
 * caller heard something, so per R12 it may keep the person unnamed, or ask
 * Claude to find a name in the transcript (the KTD4 fallback).
 */
public final class NameExtractor {
    /** Longest name word kept; a longer "word" is a misrecognition. */
    static final int MAX_WORD = 20;

    /** Lead-ins, longest first so "my name is" wins over "my". */
    private static final String[][] LEAD_INS = {
        {"my", "name", "is"}, {"my", "names"}, {"my", "name's"}, {"they", "call", "me"},
        {"you", "can", "call", "me"}, {"call", "me"}, {"i", "am"}, {"i'm"}, {"im"},
        {"it's"}, {"its"}, {"it", "is"}, {"this", "is"}, {"name's"}, {"names"}, {"the", "name's"},
    };

    /** Contraction tails left when the recognizer clips the front of a reply
     * ("'S BEN" from "name's Ben"); skipped at the start like fillers. */
    private static final Set<String> FRAGMENTS = set("s", "m", "re", "ll", "ve", "d", "t");

    /** Skipped at the start of a reply. */
    private static final Set<String> FILLERS = set(
            "hi", "hello", "hey", "hiya", "um", "uh", "erm", "er", "oh", "well", "so", "yeah", "yes",
            "ok", "okay", "sure", "oh", "ah", "hmm", "robot", "miko");

    /** Ends the name: what comes after it is not part of it. */
    private static final Set<String> STOPS = set(
            "thanks", "thank", "please", "and", "but", "from", "nice", "to", "here", "by", "what",
            "whats", "what's", "who", "you", "your", "the", "a", "an", "of", "in", "at", "on", "sir",
            "robot", "miko", "hi", "hello", "hey", "yes", "yeah", "ok", "okay", "too", "also", "now",
            "then", "if", "because", "so", "um", "uh");

    /** All that may follow a bare name. */
    private static final Set<String> COURTESY = set("thanks", "thank", "you", "please");

    /** Never a name, so a reply whose name slot starts with one has no name. */
    private static final Set<String> NOT_NAMES = set(
            "i", "me", "my", "you", "he", "she", "we", "they", "it", "its", "it's", "this", "that",
            "what", "who", "why", "how", "where", "when", "no", "not", "nope", "yes", "yeah", "ok",
            "okay", "fine", "good", "great", "well", "sorry", "pardon", "huh", "hello", "hi", "hey",
            "don't", "dont", "do", "doing", "going", "just", "very", "so", "here", "there", "busy",
            "tired", "happy", "sad", "a", "an", "the", "and", "but", "or", "to", "is", "am", "are",
            "was", "be", "nothing", "nobody", "none", "thanks", "thank", "please", "bye", "goodbye",
            "um", "uh", "hmm", "oh", "robot", "miko", "your", "mine", "fun", "cool", "nice", "can't",
            "cant", "won't", "wont", "never", "maybe", "secret", "sure", "really", "all", "right",
            "hungry", "back", "done", "ready", "in", "on", "at", "from", "of", "with", "about");

    private NameExtractor() {
    }

    /** The name in transcript, title-cased, or null when there's no clear one. */
    public static String extract(String transcript) {
        List<String> words = words(transcript);
        int start = 0;
        while (start < words.size()
                && (FILLERS.contains(words.get(start)) || FRAGMENTS.contains(words.get(start)))) {
            start++;
        }
        if (start >= words.size()) {
            return null;
        }
        for (String[] lead : LEAD_INS) {
            if (startsWith(words, start, lead)) {
                return nameAt(words, start + lead.length);
            }
        }
        // A bare answer: the whole reply (after fillers) is one or two name
        // words, with at most a courtesy ("thanks", "please") after them.
        int n = nameWords(words, start);
        if (n == 0) {
            return null;
        }
        for (int i = start + n; i < words.size(); i++) {
            if (!COURTESY.contains(words.get(i))) {
                return null;
            }
        }
        return title(words.subList(start, start + n));
    }

    private static String nameAt(List<String> words, int at) {
        int n = nameWords(words, at);
        return n == 0 ? null : title(words.subList(at, at + n));
    }

    /** How many name words (0-2) start at index at. */
    private static int nameWords(List<String> words, int at) {
        int n = 0;
        while (n < 2 && at + n < words.size()) {
            String w = words.get(at + n);
            if (!isNameWord(w) || STOPS.contains(w)) {
                break;
            }
            n++;
        }
        return n;
    }

    private static boolean isNameWord(String w) {
        if (w.isEmpty() || w.length() > MAX_WORD || NOT_NAMES.contains(w)) {
            return false;
        }
        if (!Character.isLetter(w.charAt(0)) || !Character.isLetter(w.charAt(w.length() - 1))) {
            return false;
        }
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            if (!Character.isLetter(c) && c != '\'' && c != '-') {
                return false;
            }
        }
        return true;
    }

    private static boolean startsWith(List<String> words, int at, String[] lead) {
        if (at + lead.length > words.size()) {
            return false;
        }
        for (int i = 0; i < lead.length; i++) {
            if (!words.get(at + i).equals(lead[i])) {
                return false;
            }
        }
        return true;
    }

    /** Lower-case words, with punctuation other than ' and - dropped and
     * curly apostrophes made straight. */
    static List<String> words(String text) {
        List<String> out = new ArrayList<String>();
        if (text == null) {
            return out;
        }
        String t = text.toLowerCase(Locale.ROOT).replace('\u2019', '\'').replace('\u2018', '\'');
        StringBuilder sb = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            sb.append(Character.isLetterOrDigit(c) || c == '\'' || c == '-' ? c : ' ');
        }
        for (String w : sb.toString().trim().split("\\s+")) {
            // Strip quote marks and dashes used as punctuation around a word.
            w = w.replaceAll("^['-]+|['-]+$", "");
            // A stray single letter ("a", a clipped "s") is never part of a
            // name; "i" stays for "i am".
            if (!w.isEmpty() && (w.length() > 1 || w.equals("i"))) {
                out.add(w);
            }
        }
        return out;
    }

    private static String title(List<String> words) {
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            boolean up = true;
            for (int i = 0; i < w.length(); i++) {
                char c = w.charAt(i);
                sb.append(up ? Character.toUpperCase(c) : c);
                up = c == '-';
            }
        }
        return sb.toString();
    }

    private static Set<String> set(String... words) {
        return new HashSet<String>(Arrays.asList(words));
    }
}
