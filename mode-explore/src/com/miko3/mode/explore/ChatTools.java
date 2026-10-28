package com.miko3.mode.explore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The conversation's tools (owner 2026-10-03). Measured on Haiku through the gateway, a
 * JSON-schema reply took about 2.1 s to its line and the same fields as a final "respond"
 * tool about 1.05 s, so every reply is a call to respond, whose input carries
 * ExplorePrompts.REPLY_SCHEMA's fields in the same order. Four more tools answer what
 * the person asks about him, at most one round per reply:
 * - look: a fresh camera frame with the detector's labels (never in bathroom privacy
 *   or do not disturb: then he says he can't look);
 * - recall_person: what he remembers of the person he is talking to, or whether he
 *   knows someone they name (never anyone else's notes);
 * - robot_status: battery and charging, sound, do not disturb, how long he has explored;
 * - places: the places he looked at lately, by what he saw there.
 *
 * The wording here is the source; scripts/claude-chat-bench.py carries the same names,
 * descriptions and schemas byte for byte (test_explore_claude_wiring.py holds them together).
 * Plain Java (no Android or shared imports), so the brain harness runs it.
 */
final class ChatTools {
    private ChatTools() {
    }

    static final String RESPOND = "respond";
    static final String LOOK = "look";
    static final String RECALL = "recall_person";
    static final String STATUS = "robot_status";
    static final String PLACES = "places";

    static final String RESPOND_DESCRIPTION = "Say Miko's reply. Every reply ends with exactly one call to this tool, "
            + "holding the whole reply.";
    static final String LOOK_DESCRIPTION = "Take a fresh photo with Miko's camera and see it, with the labels his "
            + "detector found in it. Use it only when the person asks what he can see, asks him to look at something, "
            + "or shows him something; never for small talk (\"how was your morning\" needs no look). If he can't look "
            + "right now, the line says so.";
    static final String RECALL_DESCRIPTION = "What Miko remembers about someone: the person he is talking to (name "
            + "empty), or whether he knows someone they name. Use it only when they ask what he remembers or knows "
            + "about them or someone; he never shares anyone else's notes.";
    static final String STATUS_DESCRIPTION = "Miko's own state: battery and charging, his sound and do not disturb, "
            + "how long he has been exploring and what he is doing. Use it only when they ask about those.";
    static final String PLACES_DESCRIPTION = "The places Miko has looked at lately, newest first, each described by "
            + "what his camera saw there. Use it only when they ask where he has been or what he has been up to or seen today; never make up sightings.";

    /** The tool definitions' parts, in the order they are sent: {name, description, input schema}. */
    static List<Object[]> definitions() {
        List<Object[]> out = new ArrayList<Object[]>();
        out.add(new Object[]{RESPOND, RESPOND_DESCRIPTION, ExplorePrompts.REPLY_SCHEMA});
        out.add(new Object[]{LOOK, LOOK_DESCRIPTION, ExplorePrompts.object()});
        out.add(new Object[]{RECALL, RECALL_DESCRIPTION, RECALL_SCHEMA});
        out.add(new Object[]{STATUS, STATUS_DESCRIPTION, ExplorePrompts.object()});
        out.add(new Object[]{PLACES, PLACES_DESCRIPTION, ExplorePrompts.object()});
        // Owner 2026-10-03: the action tools after them (ChatActions), one per reply.
        out.addAll(ChatActions.definitions());
        return out;
    }

    static final Map<String, Object> RECALL_SCHEMA = ExplorePrompts.object("name", ExplorePrompts.described(
            ExplorePrompts.type("string"), "The name they asked about; empty for the person Miko is talking to."));

    /**
     * Brain side: the preamble is said first (KTD7: the detector parks while he speaks), for
     * at most PREAMBLE_WAIT_MS, then the look waits LOOK_WAIT_MS for a fresh frame
     * (ExploreTuning.toolLookMs). The adapter waits a little longer than both, so the
     * brain's own "no picture in time" is what Claude reads.
     */
    static final long PREAMBLE_WAIT_MS = 3000;
    static final long LOOK_WAIT_MS = 4000;
    static final long ADAPTER_LOOK_WAIT_MS = PREAMBLE_WAIT_MS + LOOK_WAIT_MS + 1000;
    /** A preamble is a few words said while the tool runs; anything longer is cut to its first sentence. */
    static final int MAX_PREAMBLE_CHARS = 80;
    /** What a tool_result answering an earlier respond call says. */
    static final String SAID = "said";

    /**
     * An earlier exchange's line as the respond call that said it (the history Claude reads):
     * every field in the schema's order, addressed, nothing else asked, given or noted.
     */
    static Map<String, Object> saidInput(String said) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("addressed", Boolean.TRUE);
        m.put("line", said == null ? "" : said);
        m.put("question_asked", "");
        m.put("name_given", "");
        m.put("ends_conversation", Boolean.FALSE);
        m.put("deflected", Boolean.FALSE);
        Map<String, Object> notes = new LinkedHashMap<String, Object>();
        for (String k : Arrays.asList("interests", "open_threads", "closed_threads", "topics", "questions_asked")) {
            notes.put(k, new ArrayList<Object>());
        }
        m.put("notes_update", notes);
        Map<String, Object> fb = new LinkedHashMap<String, Object>();
        fb.put("kind", "none");
        fb.put("summary", "");
        fb.put("quote", "");
        m.put("feedback", fb);
        return m;
    }

    /** The id of the respond call that said exchange i (the API takes letters, digits, '_' and '-'). */
    static String saidId(int i) {
        return "toolu_said_" + i;
    }

    /** A preamble as he says it: trimmed, one sentence, at most MAX_PREAMBLE_CHARS; null for none. */
    static String preamble(String text) {
        if (text == null) {
            return null;
        }
        String t = text.replaceAll("\\s+", " ").trim();
        if (t.isEmpty()) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^.*?[.!?](?=\\s|$)").matcher(t);
        if (m.find()) {
            t = m.group();
        }
        if (t.length() > MAX_PREAMBLE_CHARS) {
            return null; // not a preamble: a line written outside respond is never said twice
        }
        return t;
    }

    /** The look tool's caption beside the photo: its labels, or none when the detector did not run on it (null). */
    static String lookCaption(Collection<String> labels) {
        if (labels == null) {
            return "A photo Miko just took. His detector has not looked at this one, so there are no labels.";
        }
        if (labels == null || labels.isEmpty()) {
            return "A photo Miko just took. His detector named nothing in it.";
        }
        return "A photo Miko just took. His detector's labels: " + join(labels) + ".";
    }

    /** The look tool's error when he can't look: why, and what the line does. */
    static String cantLook(String why) {
        return "Miko can't look right now (" + why + "). Say so in the line; do not guess what is there.";
    }

    /**
     * recall_person's answer. Asked about the person he is talking to (an empty name, or
     * theirs): their name and the notes he has of them (the same notes the system prompt
     * carries). Asked about anyone else: only whether he knows someone by that name
     * (known: TRUE, FALSE, null when the store could not be asked), never their notes.
     */
    static String recall(String asked, String name, String notes, Boolean knowsOther) {
        String who = asked == null ? "" : asked.trim();
        boolean current = who.isEmpty() || name != null && AnswerParser.same(name, who);
        if (current) {
            String n = notes == null || notes.trim().isEmpty() || notes.trim().equals("{}") ? null : notes.trim();
            if (name == null) {
                return "Miko does not know the name of the person he is talking to yet, and has no notes about them.";
            }
            return "Miko is talking to " + name + ". "
                    + (n == null ? "He has no notes about them yet." : "What he remembers of them (data): " + n);
        }
        if (knowsOther == null) {
            return "Miko can't check his memory right now.";
        }
        if (!knowsOther) {
            return "Miko doesn't know anyone called " + who + ".";
        }
        return "Miko has met someone called " + who + ". What other people tell him stays with them, so he shares "
                + "nothing more about them.";
    }

    /** robot_status's text from the brain's state; battery -1 when the motor board gave none. */
    static String statusText(int batteryPercent, boolean charging, boolean muted, boolean doNotDisturb,
            long exploringMs, String doing) {
        StringBuilder b = new StringBuilder();
        b.append("Battery: ").append(batteryPercent < 0 ? "unknown" : "about " + batteryPercent + "%")
                .append(charging ? ", charging on the dock. " : ", not on the charger. ");
        b.append("Sound: ").append(muted ? "muted" : "on").append(". ");
        b.append("Do not disturb: ").append(doNotDisturb ? "on" : "off").append(". ");
        b.append("Exploring for ").append(span(exploringMs)).append(". ");
        b.append("Now: ").append(doing == null || doing.isEmpty() ? "talking with someone" : doing).append(".");
        return b.toString();
    }

    /** One place he looked at: how long ago, and the labels seen there. */
    static final class Place {
        final long agoMs;
        final List<String> labels;

        Place(long agoMs, List<String> labels) {
            this.agoMs = agoMs;
            this.labels = labels;
        }
    }

    /** The most places places() lists. */
    static final int MAX_PLACES = 8;

    /**
     * places' text: newest first, at most MAX_PLACES, each by its labels; a place whose
     * labels include any in hidden (the bathroom's) is left out, and so is one with no labels.
     */
    static String placesText(List<Place> newestFirst, Collection<String> hidden) {
        List<String> lines = new ArrayList<String>();
        List<String> seenSets = new ArrayList<String>();
        if (newestFirst != null) {
            for (Place p : newestFirst) {
                if (lines.size() >= MAX_PLACES) {
                    break;
                }
                if (p.labels == null || p.labels.isEmpty() || hidden != null && !disjoint(p.labels, hidden)) {
                    continue;
                }
                String set = join(p.labels);
                if (seenSets.contains(set)) {
                    continue;
                }
                seenSets.add(set);
                lines.add(span(p.agoMs) + " ago: " + set);
            }
        }
        if (lines.isEmpty()) {
            return "Miko remembers no places right now.";
        }
        StringBuilder b = new StringBuilder("Places Miko looked at lately, newest first (he does not name places; "
                + "each is what his camera saw there): ");
        for (int i = 0; i < lines.size(); i++) {
            b.append(i == 0 ? "" : "; ").append(lines.get(i));
        }
        return b.append(".").toString();
    }

    private static boolean disjoint(Collection<String> a, Collection<String> b) {
        for (String x : a) {
            if (b.contains(x)) {
                return false;
            }
        }
        return true;
    }

    /** "under a minute", "1 min", "23 min", "2 h 5 min". */
    static String span(long ms) {
        long min = Math.max(0, ms) / 60000;
        if (min < 1) {
            return "under a minute";
        }
        if (min < 60) {
            return min + " min";
        }
        return String.format(Locale.US, "%d h %d min", min / 60, min % 60);
    }

    private static String join(Collection<String> items) {
        StringBuilder b = new StringBuilder();
        for (String s : items) {
            b.append(b.length() == 0 ? "" : ", ").append(s);
        }
        return b.toString();
    }
}
