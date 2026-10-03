package com.miko3.mode.explore;

import com.miko3.shared.ClaudeApi;
import com.miko3.shared.Json;
import com.miko3.shared.NameExtractor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One conversation turn's requests (owner 2026-10-03). The reply is a call to the respond
 * tool (ChatTools) rather than a JSON-schema answer: on Haiku through the gateway its line
 * came in about 1.05 s instead of 2.1 s. The first request offers every tool with
 * tool_choice auto; when Claude calls look, recall_person, robot_status or places instead,
 * the conversation says the few words written before the call (the preamble), the tools
 * run, and one more request, with respond forced, gives the reply: at most one tool round.
 * A speculation (a turn started on a provisional answer) that wants a tool is dropped
 * before it says or looks at anything; the turn() that follows starts afresh.
 *
 * The history Claude reads: each earlier line as the respond call that said it, answered
 * by a "said" tool_result at the start of the next user message (the API requires every
 * tool_use to be answered there).
 *
 * Plain Java on the shared client, so the host harness runs it; nothing here logs.
 */
final class ChatRound {
    private ChatRound() {
    }

    /**
     * The reply fields the turn's line needs before it is spoken: "addressed" (a boolean,
     * first, so a turn not said to him is known before its empty line), then the line,
     * the question and the name (the repeat check and the name read them). The rest (the
     * notes, the feedback) follow as the tail.
     */
    static final List<String> EARLY_FIELDS = Arrays.asList("addressed", "line", "question_asked", "name_given");

    /** Sends one request of the turn: these messages, with these tools. */
    interface Sender {
        ClaudeApi.MessageResult send(List<Map<String, Object>> messages, ClaudeApi.Tools tools);
    }

    /** What a tool round needs from outside the requests. */
    interface Host {
        /** False for a speculation nobody has asked for yet: it may not run a tool round, and is dropped. */
        boolean mayUseTools();

        /**
         * Ask the conversation to say this preamble (null: none) and, when look, for a fresh
         * frame, waiting at most waitMs for it; null when no answer came (or look is false).
         */
        CuriosityPort.LookResult ask(String preamble, boolean look, long waitMs);

        /** Whether someone with this name is in his people store; null when it can't be asked. */
        Boolean knows(String name);

        /** The turn is still the one asked (not cancelled or replaced): else no second request is sent. */
        boolean stillAsked();
    }

    /** One turn's request: the system prefix, the messages, and the key a speculation is matched by. */
    static final class Body {
        final String system;
        final List<Map<String, Object>> messages;
        final String key;

        Body(String system, List<Map<String, Object>> messages, String key) {
            this.system = system;
            this.messages = messages;
            this.key = key;
        }
    }

    /** How the turn went. */
    static final class Outcome {
        /** The last request's result (its reason when it failed). */
        final ClaudeApi.MessageResult result;
        /** The reply's fields (respond's input, or a prose reply's line); null when there is none. */
        final Map<String, Object> reply;
        /** The tools run in this turn's round, comma-separated, or null for none. */
        final String tools;
        /** A speculation that wanted a tool, or a turn abandoned during its tool round: nothing comes of it. */
        final boolean dropped;
        /** Owner 2026-10-03: the action tool call accepted this turn (ChatActions), or null. */
        final ChatActions.Act act;

        Outcome(ClaudeApi.MessageResult result, Map<String, Object> reply, String tools, boolean dropped) {
            this(result, reply, tools, dropped, null);
        }

        Outcome(ClaudeApi.MessageResult result, Map<String, Object> reply, String tools, boolean dropped,
                ChatActions.Act act) {
            this.result = result;
            this.reply = reply;
            this.tools = tools;
            this.dropped = dropped;
            this.act = act;
        }
    }

    /** The tools every request offers, in their fixed order (they sit in the cached prefix). */
    static List<Map<String, Object>> definitions() {
        List<Map<String, Object>> defs = new ArrayList<Map<String, Object>>();
        for (Object[] d : ChatTools.definitions()) {
            @SuppressWarnings("unchecked")
            Map<String, ?> schema = (Map<String, ?>) d[2];
            defs.add(ClaudeApi.tool((String) d[0], (String) d[1], schema));
        }
        return defs;
    }

    /**
     * The first request's tools: every tool, respond's input the reply. tool_choice "any": Claude
     * must call a tool, so it can write no free text at all (robot 2026-10-02 at home: text Claude
     * wrote before a tool, its reasoning, was spoken aloud as the preamble).
     */
    static ClaudeApi.Tools tools() {
        return new ClaudeApi.Tools(definitions()).replyTool(ChatTools.RESPOND).choice("any");
    }

    /**
     * One turn's body: the frozen system prefix from the request's persona snapshot and
     * notes, the transcript window (each heard message, then the respond call that said
     * his line), what was just heard as the last user message (the opener ask instead for
     * turn 1, with the face crop sent that once), its reminders appended.
     */
    static Body body(CuriosityPort.TurnRequest request, byte[] face) {
        String system = ExplorePrompts.systemPrefix(request.persona, request.notes, request.ownerName,
                request.ownerNote);
        List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
        // A conversation that opened faceless invites them down instead of asking the name (robot 2026-10-01);
        // one a call opened greets them first, before he has seen them (owner 2026-10-02).
        String first = request.called ? ExplorePrompts.CALL_OPENER
                : request.faceless ? ExplorePrompts.FACELESS_OPENER : ExplorePrompts.openerAsk(request.name);
        String saidBefore = null;
        int i = 0;
        for (CuriosityPort.Exchange e : request.transcript) {
            messages.add(userMessage(saidBefore, null, e.heard == null ? first : e.heard));
            saidBefore = ChatTools.saidId(i++);
            messages.add(saidTurn(saidBefore, e.said));
        }
        String ask = request.heard == null ? first : request.heard;
        if (request.avoidQuestion != null) {
            ask = ask + "\n\n" + ExplorePrompts.avoidQuestion(request.avoidQuestion);
        }
        if (request.called && request.heard != null && request.transcript.isEmpty()) {
            ask = ask + "\n\n" + ExplorePrompts.CALL_WORDS;
        }
        if (request.cantSee) {
            ask = ask + "\n\n" + ExplorePrompts.CANT_SEE;
        }
        if (request.faceSeen) {
            ask = ask + "\n\n" + ExplorePrompts.FACE_SEEN;
        }
        messages.add(userMessage(saidBefore, face, ask));
        return new Body(system, messages, system + "\u0000" + Json.write(messages));
    }

    /** A user message: the "said" result for the respond call before it (if any), the photo (if any), the words. */
    private static Map<String, Object> userMessage(String saidId, byte[] face, String text) {
        if (saidId == null && face == null) {
            return ClaudeApi.message("user", text);
        }
        List<Map<String, Object>> content = new ArrayList<Map<String, Object>>();
        if (saidId != null) {
            content.add(ClaudeApi.toolResult(saidId, ChatTools.SAID));
        }
        if (face != null) {
            content.add(ClaudeApi.jpegBlock(face));
        }
        content.add(ClaudeApi.textBlock(text));
        return ClaudeApi.message("user", content);
    }

    /** His earlier line as the assistant's respond call. */
    private static Map<String, Object> saidTurn(String id, String said) {
        Map<String, Object> use = new LinkedHashMap<String, Object>();
        use.put("type", "tool_use");
        use.put("id", id);
        use.put("name", ChatTools.RESPOND);
        use.put("input", ChatTools.saidInput(said == null ? "" : said));
        List<Map<String, Object>> content = new ArrayList<Map<String, Object>>();
        content.add(use);
        return ClaudeApi.message("assistant", content);
    }

    /**
     * Runs the turn: the first request, then at most one tool round and its forced respond.
     * Owner 2026-10-03, the action tools (ChatActions): at most one action per reply, checked
     * against the turn's facts; its honest result goes back to Claude in the round, and the
     * accepted act comes out with the reply. An action called beside respond (the prompt asks
     * for it alone) is taken with that reply when it is accepted; refused, the round runs so
     * the line says why.
     */
    static Outcome run(Body body, CuriosityPort.TurnRequest request, Sender sender, Host host) {
        List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>(body.messages);
        ClaudeApi.Tools tools = tools();
        ClaudeApi.MessageResult first = sender.send(messages, tools);
        if (!first.ok()) {
            return new Outcome(first, null, null, false);
        }
        ClaudeApi.ToolUse action = firstAction(first);
        if (first.toolUse(ChatTools.RESPOND) != null || first.toolUses.isEmpty()) {
            if (action == null) {
                return new Outcome(first, reply(first), null, false);
            }
            ChatActions.Verdict v = ChatActions.check(action.name, action.input, request.facts);
            if (v.ok()) {
                return new Outcome(first, reply(first), action.name, false, v.act);
            }
        }
        if (!host.mayUseTools()) {
            return new Outcome(first, null, null, true);
        }
        boolean look = false;
        StringBuilder used = new StringBuilder();
        for (ClaudeApi.ToolUse u : first.toolUses) {
            look |= ChatTools.LOOK.equals(u.name);
            if (!ChatTools.RESPOND.equals(u.name)) {
                used.append(used.length() == 0 ? "" : ",").append(u.name);
            }
        }
        // Never Claude's own text (with tool_choice "any" there is none): a fixed few words per tool.
        CuriosityPort.LookResult seen = host.ask(preambleFor(first.toolUses), look,
                ChatTools.ADAPTER_LOOK_WAIT_MS);
        List<Map<String, Object>> results = new ArrayList<Map<String, Object>>();
        ChatActions.Act act = null;
        for (ClaudeApi.ToolUse u : first.toolUses) {
            if (ChatActions.isAction(u.name)) {
                if (u != action) {
                    results.add(ClaudeApi.toolError(u.id, ONE_ACTION));
                    continue;
                }
                ChatActions.Verdict v = ChatActions.check(u.name, u.input, request.facts);
                act = v.act;
                results.add(v.ok() ? ClaudeApi.toolResult(u.id, v.result) : ClaudeApi.toolError(u.id, v.result));
            } else if (ChatTools.RESPOND.equals(u.name)) {
                results.add(ClaudeApi.toolResult(u.id, NOT_SAID));
            } else {
                results.add(result(u, request, seen, host));
            }
        }
        if (!host.stillAsked()) {
            return new Outcome(first, null, used.toString(), true);
        }
        messages.add(ClaudeApi.assistantTurn(first));
        messages.add(ClaudeApi.toolResults(results));
        ClaudeApi.MessageResult second = sender.send(messages, tools.choice(ChatTools.RESPOND));
        if (!second.ok()) {
            return new Outcome(second, null, used.toString(), false);
        }
        return new Outcome(second, reply(second), used.toString(), false, act);
    }

    // ---- the turn into its flight (review 2026-10-03) ----

    /** Sends one request of the turn, telling early its named fields as they stream (ClaudeApi.conversation). */
    interface Asker {
        ClaudeApi.MessageResult send(List<Map<String, Object>> messages, ClaudeApi.Tools tools,
                ClaudeApi.EarlyFields early);
    }

    /** Told the turn's outcome just before its whole reply goes to the flight (the adapter's turn info). */
    interface BeforeWhole {
        void outcome(Outcome o);
    }

    /**
     * Runs the turn (run()) into its flight: the line goes early from the request that turns out
     * to be the last (ClaudeApi tells no fields of a reply that calls another tool first), the
     * whole reply after it, with its act. Review 2026-10-03: an accepted action came only in the
     * whole reply, which after an early line passed on only its notes, so the act was lost; now
     * the flight passes it on (TurnFlight.lateTurn), and when a tool round replaced the line that
     * went early (an action beside respond, refused), its line follows as a correction. The
     * adapter (ClaudeCuriosity.oneTurn) and the host harness both run exactly this. earlyAt[0]
     * gets when the line was known (0: never). A dropped turn gives the flight nothing.
     */
    static Outcome fly(final Body body, CuriosityPort.TurnRequest request, final Asker asker, Host host,
            final CuriosityPort.TurnFlight flight, final CuriosityPort.TurnFlight.Call call, final long[] earlyAt,
            BeforeWhole before) {
        final int[] sends = {0};
        final int[] earlyIn = {0};
        final ClaudeApi.EarlyFields early = new ClaudeApi.EarlyFields() {
            @Override
            public void complete(Map<String, String> fields) {
                CuriosityPort.Turn t = earlyTurn(fields);
                if (t == null) {
                    return;
                }
                if (earlyAt[0] == 0) {
                    earlyAt[0] = System.currentTimeMillis();
                }
                if (earlyIn[0] == 0) {
                    earlyIn[0] = sends[0];
                }
                // Owner 2026-10-02: "addressed" comes before the line; only a line known to be
                // said to him goes early (unknown, as from an unstreamed reply: the whole decides).
                flight.early(call, t);
            }
        };
        Outcome o = run(body, request, new Sender() {
            @Override
            public ClaudeApi.MessageResult send(List<Map<String, Object>> messages, ClaudeApi.Tools tools) {
                sends[0]++;
                return asker.send(messages, tools, early);
            }
        }, host);
        if (o.dropped) {
            return o;
        }
        if (before != null) {
            before.outcome(o);
        }
        flight.whole(call, turnOf(o), earlyIn[0] != 0 && earlyIn[0] < sends[0]);
        return o;
    }

    /**
     * The early fields as a LINE turn with no notes yet (the name through NameExtractor, as
     * turnOf does), or null while it is not known to be said to him: a turn not addressed to
     * him is never spoken, so it waits for the whole reply.
     */
    static CuriosityPort.Turn earlyTurn(Map<String, String> fields) {
        if (!"true".equals(fields.get("addressed"))) {
            return null;
        }
        Map<String, Object> json = new LinkedHashMap<String, Object>(fields);
        json.put("addressed", Boolean.TRUE);
        CuriosityPort.Turn t = ClaudeReplies.turn(json, null);
        if (t.status != CuriosityPort.Turn.Status.LINE || t.nameGiven == null) {
            return t;
        }
        return CuriosityPort.Turn.line(t.line, t.questionAsked, NameExtractor.validName(t.nameGiven), false, false,
                null);
    }

    /**
     * The client's reason as the brain's turn status, from the reply's fields (respond's
     * input); a name given passes NameExtractor's word list first.
     */
    static CuriosityPort.Turn turnOf(Outcome o) {
        ClaudeApi.MessageResult r = o.result;
        if (r.ok()) {
            if (o.reply == null) {
                return CuriosityPort.Turn.failed();
            }
            Object delta = o.reply.get("notes_update");
            CuriosityPort.Turn t = ClaudeReplies.turn(o.reply, delta instanceof Map ? Json.write(delta) : null);
            // Owner 2026-10-03: the action tool accepted this turn (ChatActions) is the turn's act.
            if (o.act != null && t.status == CuriosityPort.Turn.Status.LINE && t.addressed) {
                t = t.withAct(o.act);
            }
            if (t.status != CuriosityPort.Turn.Status.LINE || t.nameGiven == null) {
                return t;
            }
            return CuriosityPort.Turn.line(t.line, t.questionAsked, NameExtractor.validName(t.nameGiven),
                    t.endsConversation, t.deflected, t.notesUpdate).withFeedback(t.feedback)
                    .withAddressed(t.addressed).withAct(t.act);
        }
        switch (r.reason) {
            case REFUSED:
                return CuriosityPort.Turn.refused();
            case UNREACHABLE:
            case OVERLOADED:
            case RATE_LIMITED:
            case ENDPOINT_ERROR:
                return CuriosityPort.Turn.unreachable();
            default:
                return CuriosityPort.Turn.failed();
        }
    }

    /** What a second action in the same reply is told. */
    static final String ONE_ACTION = "Only one action per reply: this one was not done.";
    /** What a respond call answered in the round is told (its line is replaced by the next one). */
    static final String NOT_SAID = "not said: reply again after the tool results";

    /** The reply's first action tool call, or null. */
    private static ClaudeApi.ToolUse firstAction(ClaudeApi.MessageResult r) {
        for (ClaudeApi.ToolUse u : r.toolUses) {
            if (ChatActions.isAction(u.name)) {
                return u;
            }
        }
        return null;
    }

    /** One tool call's result block. */
    private static Map<String, Object> result(ClaudeApi.ToolUse u, CuriosityPort.TurnRequest request,
            CuriosityPort.LookResult seen, Host host) {
        if (ChatTools.LOOK.equals(u.name)) {
            if (seen == null) {
                return ClaudeApi.toolError(u.id, ChatTools.cantLook("his camera gave no picture in time"));
            }
            if (seen.refused != null) {
                return ClaudeApi.toolError(u.id, ChatTools.cantLook(seen.refused));
            }
            return ClaudeApi.toolResultImage(u.id, seen.jpeg, ChatTools.lookCaption(seen.detected ? seen.labels : null));
        }
        if (ChatTools.RECALL.equals(u.name)) {
            Object asked = u.input.get("name");
            String who = asked instanceof String ? ((String) asked).trim() : "";
            boolean current = who.isEmpty() || request.name != null && AnswerParser.same(request.name, who);
            Boolean knows = current ? null : host.knows(who);
            return ClaudeApi.toolResult(u.id, ChatTools.recall(who, request.name, request.notes, knows));
        }
        if (ChatTools.STATUS.equals(u.name)) {
            return ClaudeApi.toolResult(u.id, request.facts.status);
        }
        if (ChatTools.PLACES.equals(u.name)) {
            return ClaudeApi.toolResult(u.id, request.facts.places);
        }
        if (ChatTools.RESPOND.equals(u.name)) {
            return ClaudeApi.toolResult(u.id, ChatTools.SAID);
        }
        return ClaudeApi.toolError(u.id, "There is no tool called that.");
    }

    /**
     * The reply's fields: respond's input; else a reply that came as words (Claude ignored
     * the tools) as a line said to him, or as its JSON when it wrote the fields as text.
     */
    private static Map<String, Object> reply(ClaudeApi.MessageResult r) {
        if (r.toolUse(ChatTools.RESPOND) != null || r.json.get("line") instanceof String) {
            return r.json;
        }
        String text = r.text == null ? "" : r.text.trim();
        if (text.isEmpty() || looksLikeReasoning(text)) {
            // Robot 2026-10-02: never speak Claude talking about the conversation ("I should respond...").
            return null;
        }
        Map<String, Object> json = new LinkedHashMap<String, Object>();
        json.put("addressed", Boolean.TRUE);
        json.put("line", text);
        return json;
    }

    /** Words that are Claude reasoning about the reply rather than a line Miko would say. */
    static boolean looksLikeReasoning(String text) {
        String t = text.toLowerCase(java.util.Locale.ROOT);
        return t.contains("i should") || t.contains("the person") || t.contains("the user") || t.contains("respond")
                || t.contains("let me think") || t.contains("tool") || t.contains("miko should") || t.contains("they said");
    }

    /** The few words said while a tool runs: fixed per tool, never Claude's text (null: none). */
    static String preambleFor(List<ClaudeApi.ToolUse> uses) {
        if (uses != null) {
            for (ClaudeApi.ToolUse u : uses) {
                if (ChatTools.LOOK.equals(u.name)) {
                    return "Let me look.";
                }
            }
        }
        return null;
    }
}
