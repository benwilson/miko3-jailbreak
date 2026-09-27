package com.miko3.mode.explore;

import java.util.List;
import java.util.Map;

/**
 * Who a spoken name belongs to (face plan U7; KTD6, KTD10, R6, R7, R20): plain
 * Java, run robot-side by the adapter with the ids the store's idsNamed gave
 * (full name when two words are given, else the first word, case-insensitive,
 * over every named person) and their photos' embeddings. Nothing here goes to
 * Claude, and nothing here logs.
 *
 *   resolve        the face against each matching id's photos, best photo
 *                  counts: at or above the close threshold it JOINs that id
 *                  (R6); below it, with at least one match, it asks the last
 *                  name (R7); with no match it stores someone NEW. A full name
 *                  already given skips the question: the matching ids carry that
 *                  full name, so the last-name step would join them anyway.
 *   afterLastName  JOIN an id whose full stored name equals the given full name,
 *                  else NEW under the full name (a stored person with no last
 *                  name can't match one: the duplicate is fixed on the People page).
 */
final class NameResolver {

    enum Kind { JOIN, ASK_LAST_NAME, NEW }

    /** The decision: JOIN carries the id; NEW and ASK_LAST_NAME carry the name to store or complete. */
    static final class Decision {
        final Kind kind;
        final String personId;
        final String name;
        final float score;

        private Decision(Kind kind, String personId, String name, float score) {
            this.kind = kind;
            this.personId = personId;
            this.name = name;
            this.score = score;
        }

        /** Ids and scores only: never a name. */
        @Override
        public String toString() {
            return kind + (personId == null ? "" : " " + personId) + (Float.isNaN(score) ? "" : " " + score);
        }
    }

    private NameResolver() {
    }

    /**
     * The rule for a spoken name (KTD10). candidateIds are the store's idsNamed
     * answer for it; gallery is every usable embedding (only the candidates'
     * count); a null probe scores nothing.
     */
    static Decision resolve(String name, float[] probe, List<String> candidateIds, List<FaceMatcher.Entry> gallery,
                            float close) {
        if (candidateIds == null || candidateIds.isEmpty()) {
            return new Decision(Kind.NEW, null, name, Float.NaN);
        }
        String bestId = null;
        float best = Float.NEGATIVE_INFINITY;
        if (probe != null && gallery != null) {
            for (FaceMatcher.Entry e : gallery) {
                if (e == null || e.embedding == null || e.embedding.length != probe.length
                        || !candidateIds.contains(e.personId)) {
                    continue;
                }
                double sum = 0;
                for (int i = 0; i < probe.length; i++) {
                    sum += probe[i] * (double) e.embedding[i];
                }
                float s = (float) sum;
                if (Float.isNaN(s) || Float.isInfinite(s)) {
                    continue;
                }
                if (bestId == null || s > best || (s == best && e.personId.compareTo(bestId) < 0)) {
                    best = s;
                    bestId = e.personId;
                }
            }
        }
        if (bestId != null && best >= close) {
            return new Decision(Kind.JOIN, bestId, name, best);
        }
        if (words(name) == 2) {
            // A full name matches by full name: the last-name question would only repeat it.
            return new Decision(Kind.JOIN, bestId != null ? bestId : candidateIds.get(0), name,
                    bestId != null ? best : Float.NaN);
        }
        return new Decision(Kind.ASK_LAST_NAME, null, name, bestId != null ? best : Float.NaN);
    }

    /**
     * After "And your last name?" (KTD10): the id whose full stored name equals
     * first + last (storedNames: the full-name candidates' ids and stored names,
     * in the store's order), else someone new under the full name.
     */
    static Decision afterLastName(String first, String last, Map<String, String> storedNames) {
        String full = (first == null ? "" : first.trim()) + " " + (last == null ? "" : last.trim());
        full = full.trim().replaceAll("\\s+", " ");
        if (storedNames != null) {
            for (Map.Entry<String, String> e : storedNames.entrySet()) {
                if (AnswerParser.same(e.getValue(), full)) {
                    return new Decision(Kind.JOIN, e.getKey(), e.getValue().trim(), Float.NaN);
                }
            }
        }
        return new Decision(Kind.NEW, null, titled(full), Float.NaN);
    }

    /**
     * The name "Is that you, {name}?" asks (KTD6): the first word of the stored
     * name, or the full stored name when another stored person shares that first
     * name (sharingFirstName counts everyone idsNamed gives for it, them included),
     * so the other Ben cannot truthfully say yes to the wrong one. Null for a
     * nameless record.
     */
    static String askedName(String storedName, int sharingFirstName) {
        if (storedName == null || storedName.trim().isEmpty()) {
            return null;
        }
        String full = storedName.trim().replaceAll("\\s+", " ");
        return sharingFirstName > 1 ? full : firstWord(full);
    }

    static String firstWord(String name) {
        if (name == null) {
            return null;
        }
        String t = name.trim();
        int sp = t.indexOf(' ');
        return sp < 0 ? t : t.substring(0, sp);
    }

    private static int words(String name) {
        return name == null || name.trim().isEmpty() ? 0 : name.trim().split("\\s+").length;
    }

    /** Each word's first letter upper-cased, the rest kept as given. */
    private static String titled(String s) {
        StringBuilder out = new StringBuilder();
        for (String w : s.split(" ")) {
            if (w.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return out.toString();
    }
}
