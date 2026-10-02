package com.miko3.mode.explore;

import java.util.List;
import java.util.Map;

/**
 * Reads Claude's JSON replies (the schemas in ExplorePrompts) into the
 * brain's answers (explore on Claude plan U6; KTD2, KTD3, KTD4). Plain Java
 * over the parsed JSON (Maps, Lists, Long, Double, String, Boolean), so the
 * host harness tests it. Anything missing or out of range reads as a failure
 * (or, for a match, as a new person), never as a guess.
 */
final class ClaudeReplies {
    private ClaudeReplies() {
    }

    /** The longest line he'll say; a longer one is not the short spoken line asked for. */
    static final int MAX_LINE = 300;

    /**
     * The look reply. frameCount frames were sent, each widths[i] x heights[i]
     * pixels; "frame" is 1-based and the box is in that frame's pixels (or, if
     * all four values are 0..1, already fractions of it).
     */
    static CuriosityPort.Answer look(Map<String, Object> json, int[] widths, int[] heights) {
        Object interesting = json.get("interesting");
        if (Boolean.FALSE.equals(interesting)) {
            return CuriosityPort.Answer.nothing();
        }
        if (!Boolean.TRUE.equals(interesting)) {
            return CuriosityPort.Answer.failed();
        }
        Long frame = integer(json.get("frame"));
        CuriosityPort.Kind kind = kind(json.get("kind"));
        String label = text(json.get("label"));
        String line = line(json.get("line"));
        Object box = json.get("box");
        if (frame == null || frame < 1 || frame > widths.length || kind == null || label == null || line == null
                || !(box instanceof List) || ((List<?>) box).size() != 4) {
            return CuriosityPort.Answer.failed();
        }
        int f = (int) (frame - 1);
        double[] raw = new double[4];
        for (int i = 0; i < 4; i++) {
            Object v = ((List<?>) box).get(i);
            if (!(v instanceof Number)) {
                return CuriosityPort.Answer.failed();
            }
            raw[i] = ((Number) v).doubleValue();
        }
        // Pixels of this frame, or already 0..1 (FaceCrop.Square.fractions).
        float[] b = FaceCrop.Square.fractions(raw, widths[f], heights[f]);
        if (b == null) {
            return CuriosityPort.Answer.failed();
        }
        Detection d = new Detection(label, 1f, b[0], b[1], b[2], b[3]);
        if (d.width() <= 0f || d.height() <= 0f) {
            return CuriosityPort.Answer.failed();
        }
        return CuriosityPort.Answer.pick(f, d, kind, line);
    }

    /**
     * The way-out reply (explore nav plan U5): "frame" 1-based among the frames sent
     * (widths[i] pixels wide each), "x" the pixel column of the way out (or, as a
     * fraction, 0..1). A frame out of range (frame 7 of 6), a column outside its frame,
     * or anything missing is a failure; way_out false is NONE.
     */
    static CuriosityPort.WayOut wayOut(Map<String, Object> json, int[] widths) {
        return framePick(json, "way_out", widths);
    }

    /**
     * The seek reply (seeking the unfamiliar): read like the way-out reply, with
     * "unexplored" in place of "way_out": the frame and x of the most unexplored-looking
     * place to go, or NONE.
     */
    static CuriosityPort.WayOut seek(Map<String, Object> json, int[] widths) {
        return framePick(json, "unexplored", widths);
    }

    /** A frame and x behind a yes/no flag (the way-out and seek replies). */
    private static CuriosityPort.WayOut framePick(Map<String, Object> json, String flag, int[] widths) {
        Object way = json.get(flag);
        if (Boolean.FALSE.equals(way)) {
            return CuriosityPort.WayOut.none();
        }
        if (!Boolean.TRUE.equals(way)) {
            return CuriosityPort.WayOut.failed();
        }
        Long frame = integer(json.get("frame"));
        Object xv = json.get("x");
        if (frame == null || frame < 1 || frame > widths.length) {
            return CuriosityPort.WayOut.failed();
        }
        int f = (int) (frame - 1);
        double at = columnAt(xv, widths[f]);
        if (Double.isNaN(at)) {
            return CuriosityPort.WayOut.failed();
        }
        return CuriosityPort.WayOut.way(f, (float) (at * 2 - 1));
    }

    /**
     * The doorway reply (explore nav plan U6): "x" the pixel column of the open
     * doorway in a frame width pixels wide (or, as a fraction, 0..1). A column outside
     * the frame, a non-number, or anything missing is a failure; open_doorway false is NONE.
     */
    static CuriosityPort.Doorway doorway(Map<String, Object> json, int width) {
        Object door = json.get("open_doorway");
        if (Boolean.FALSE.equals(door)) {
            return CuriosityPort.Doorway.none();
        }
        if (!Boolean.TRUE.equals(door)) {
            return CuriosityPort.Doorway.failed();
        }
        double at = columnAt(json.get("x"), width);
        if (Double.isNaN(at)) {
            return CuriosityPort.Doorway.failed();
        }
        return CuriosityPort.Doorway.door((float) (at * 2 - 1));
    }

    /**
     * A reply's "x" across a frame width pixels wide, 0..1: a whole number is a
     * pixel column, anything else a fraction of the width. NaN when it is not a
     * number, the width is not positive, or the column falls outside the frame.
     */
    private static double columnAt(Object xv, double width) {
        if (!(xv instanceof Number) || width <= 0) {
            return Double.NaN;
        }
        Long px = integer(xv);
        double at = px != null ? px / width : ((Number) xv).doubleValue();
        return Double.isNaN(at) || at < 0 || at > 1 ? Double.NaN : at;
    }

    /**
     * The recently-met reply (explore nav plan U7) against people photos: "same_as"
     * is a 1-based photo number (as a number, "2" or "Person 2"), "none" or
     * "unsure". A number outside 1..people, or anything else, is a failure, which
     * the brain treats like unsure: he leaves the person alone (KTD8).
     */
    static CuriosityPort.Recently recentlyMet(Map<String, Object> json, int people) {
        Object v = json.get("same_as");
        Long n = integer(v);
        if (n == null && v instanceof String) {
            String s = ((String) v).trim().toLowerCase(java.util.Locale.US);
            if (s.equals("none")) {
                return CuriosityPort.Recently.different();
            }
            if (s.equals("unsure")) {
                return CuriosityPort.Recently.unsure();
            }
            if (s.startsWith("person ")) {
                s = s.substring("person ".length()).trim();
            }
            try {
                n = Long.parseLong(s);
            } catch (NumberFormatException e) {
                n = null;
            }
        }
        if (n == null || n < 1 || n > people) {
            return CuriosityPort.Recently.failed();
        }
        return CuriosityPort.Recently.same((int) (n - 1));
    }

    /**
     * The lines reply (KTD7): the named greeting (kept only with its {name}
     * placeholder, which the robot fills), the ask line and the no-reply line.
     * NEW when either the named greeting or the ask line is usable, else FAILED.
     */
    static CuriosityPort.MatchAnswer lines(Map<String, Object> json) {
        String named = line(json.get("named_line"));
        if (named != null && !named.contains("{name}")) {
            named = null;
        }
        String ask = line(json.get("ask_line"));
        return ask == null && named == null ? CuriosityPort.MatchAnswer.FAILED
                : new CuriosityPort.MatchAnswer(CuriosityPort.MatchAnswer.Status.NEW, null, named, null, ask,
                line(json.get("no_reply_line")));
    }

    /** The name reply (KTD4's fallback): one or two plain words, else no name. */
    static CuriosityPort.Named name(Map<String, Object> json) {
        Object v = json.get("name");
        if (!(v instanceof String)) {
            return CuriosityPort.Named.FAILED;
        }
        String s = ((String) v).trim();
        if (s.isEmpty() || s.length() > 40 || s.split("\\s+").length > 2 || !s.matches("[\\p{L}' .-]+")) {
            return CuriosityPort.Named.NONE;
        }
        return CuriosityPort.Named.of(s);
    }

    /** The remember reply: the line, or a failure. */
    static CuriosityPort.Answer remembered(Map<String, Object> json) {
        String line = line(json.get("line"));
        return line == null ? CuriosityPort.Answer.failed() : CuriosityPort.Answer.line(line);
    }

    /**
     * One conversation turn's reply (meeting plan U8, KTD9): the line, the question it
     * asks, a name given, the advisory ending, the deflection flag and the notes delta,
     * which the adapter hands over already serialised (null or empty: none). No line
     * is a failure. The brain caps the sentences and validates the name.
     */
    static CuriosityPort.Turn turn(Map<String, Object> json, String notesUpdateJson) {
        // Owner 2026-10-02: a message not said to him (people talking nearby) has no line to
        // say and no action; "addressed" missing (an older reply) reads as said to him.
        boolean addressed = !Boolean.FALSE.equals(json.get("addressed"));
        String line = text(json.get("line"));
        if (line == null && addressed) {
            return CuriosityPort.Turn.failed();
        }
        String notes = notesUpdateJson == null || notesUpdateJson.trim().isEmpty() || "{}".equals(notesUpdateJson.trim())
                ? null : notesUpdateJson;
        CuriosityPort.Turn t = CuriosityPort.Turn.line(line == null ? "" : line, text(json.get("question_asked")),
                text(json.get("name_given")), Boolean.TRUE.equals(json.get("ends_conversation")),
                Boolean.TRUE.equals(json.get("deflected")), notes)
                .withFeedback(feedback(json.get("feedback")));
        if (!addressed) {
            return t.withAddressed(false);
        }
        String target = text(json.get("target"));
        if (target != null && target.length() > MAX_TARGET) {
            target = target.substring(0, MAX_TARGET).trim();
        }
        return t.withAction(CuriosityPort.Action.of(json.get("action")), target);
    }

    /** The longest action target kept: a short name or place. */
    static final int MAX_TARGET = 60;

    /**
     * Owner 2026-10-02: the turn's "feedback" field, an object with string kind, summary
     * and quote, or null. JSON null, the kind "none", an empty summary or anything
     * malformed is no feedback; the line goes on either way.
     */
    static CuriosityPort.Feedback feedback(Object v) {
        if (!(v instanceof Map)) {
            return null;
        }
        Map<?, ?> m = (Map<?, ?>) v;
        Object kind = m.get("kind");
        Object summary = m.get("summary");
        Object quote = m.get("quote");
        if (!(kind instanceof String) || !(summary instanceof String) || quote != null && !(quote instanceof String)) {
            return null;
        }
        return CuriosityPort.Feedback.of((String) kind, (String) summary, (String) quote);
    }

    /** Claude's named line with the stored name put in (the robot's side of KTD7: names are never sent). */
    static String fill(String namedLine, String name) {
        return namedLine.replace("{name}", name);
    }

    private static CuriosityPort.Kind kind(Object v) {
        if (!(v instanceof String)) {
            return null;
        }
        try {
            return CuriosityPort.Kind.valueOf(((String) v).trim().toUpperCase(java.util.Locale.US));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Long integer(Object v) {
        if (v instanceof Long || v instanceof Integer) {
            return ((Number) v).longValue();
        }
        if (v instanceof Double && ((Double) v) == Math.rint((Double) v)) {
            return ((Double) v).longValue();
        }
        return null;
    }

    private static String text(Object v) {
        if (!(v instanceof String)) {
            return null;
        }
        String s = ((String) v).trim();
        return s.isEmpty() ? null : s;
    }

    private static String line(Object v) {
        String s = text(v);
        return s == null || s.length() > MAX_LINE ? null : s;
    }
}
