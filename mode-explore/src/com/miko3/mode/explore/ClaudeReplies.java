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
        Object way = json.get("way_out");
        if (Boolean.FALSE.equals(way)) {
            return CuriosityPort.WayOut.none();
        }
        if (!Boolean.TRUE.equals(way)) {
            return CuriosityPort.WayOut.failed();
        }
        Long frame = integer(json.get("frame"));
        Object xv = json.get("x");
        if (frame == null || frame < 1 || frame > widths.length || !(xv instanceof Number)) {
            return CuriosityPort.WayOut.failed();
        }
        int f = (int) (frame - 1);
        double width = widths[f];
        Long px = integer(xv);
        double at;
        if (px != null) {
            at = px / width;
        } else {
            // Not a whole number: a fraction of the frame's width.
            at = ((Number) xv).doubleValue();
        }
        if (width <= 0 || Double.isNaN(at) || at < 0 || at > 1) {
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
        Object xv = json.get("x");
        if (!Boolean.TRUE.equals(door) || !(xv instanceof Number) || width <= 0) {
            return CuriosityPort.Doorway.failed();
        }
        Long px = integer(xv);
        double at = px != null ? px / (double) width : ((Number) xv).doubleValue();
        if (Double.isNaN(at) || at < 0 || at > 1) {
            return CuriosityPort.Doorway.failed();
        }
        return CuriosityPort.Doorway.door((float) (at * 2 - 1));
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
     * The person reply against a gallery of galleryCount references. Returns
     * the 0-based reference matched, or -1 for "none", "unsure", a number
     * beyond the gallery, or anything else (all a new person, KTD3), plus the
     * lines; null when the lines a stranger needs are missing (a failure).
     */
    static Match match(Map<String, Object> json, int galleryCount) {
        String ask = line(json.get("ask_line"));
        String noReply = line(json.get("no_reply_line"));
        String named = line(json.get("named_line"));
        String unnamed = line(json.get("unnamed_line"));
        int ref = -1;
        Object m = json.get("match");
        Long n = integer(m);
        if (n == null && m instanceof String) {
            String s = ((String) m).trim();
            if (s.toLowerCase(java.util.Locale.US).startsWith("reference ")) {
                s = s.substring("reference ".length()).trim();
            }
            try {
                n = Long.parseLong(s);
            } catch (NumberFormatException e) {
                n = null;
            }
        }
        if (n != null && n >= 1 && n <= galleryCount) {
            ref = (int) (n - 1);
        }
        if (named != null && !named.contains("{name}")) {
            named = null;
        }
        if (ref >= 0 && (named != null || unnamed != null)) {
            return new Match(ref, named, unnamed, ask, noReply);
        }
        if (ask == null) {
            return null;
        }
        return new Match(-1, named, unnamed, ask, noReply);
    }

    /** The lines-only reply (KTD3's refusal path): a new person's two lines, or FAILED. */
    static CuriosityPort.MatchAnswer lines(Map<String, Object> json) {
        String ask = line(json.get("ask_line"));
        return ask == null ? CuriosityPort.MatchAnswer.FAILED
                : CuriosityPort.MatchAnswer.stranger(ask, line(json.get("no_reply_line")));
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

    /** Claude's named line with the stored name put in (the robot's side of KTD3). */
    static String fill(String namedLine, String name) {
        return namedLine.replace("{name}", name);
    }

    /** A person reply read against the gallery. */
    static final class Match {
        /** 0-based reference, or -1 for a new person. */
        final int reference;
        final String namedLine;
        final String unnamedLine;
        final String askLine;
        final String noReplyLine;

        Match(int reference, String namedLine, String unnamedLine, String askLine, String noReplyLine) {
            this.reference = reference;
            this.namedLine = namedLine;
            this.unnamedLine = unnamedLine;
            this.askLine = askLine;
            this.noReplyLine = noReplyLine;
        }
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
