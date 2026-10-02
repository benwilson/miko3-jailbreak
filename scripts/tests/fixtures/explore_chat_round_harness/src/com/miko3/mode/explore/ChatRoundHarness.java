package com.miko3.mode.explore;

import com.miko3.shared.ClaudeAccess;
import com.miko3.shared.ClaudeApi;
import com.miko3.shared.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Host harness for ChatRound (owner 2026-10-03): a conversation turn's reply through the
 * respond tool, and its one tool round. A fake transport answers each request with a
 * scripted Messages body and keeps what was sent; nothing leaves the machine. Prints one
 * "PASS name" or "FAIL name: detail" line per scenario.
 */
public final class ChatRoundHarness {
    private static final ClaudeAccess ACCESS = ClaudeAccess.setUp("https://api.example.com", "sk-ant-test-key-0123456789",
            "claude-haiku-4-5-20251001");
    private static int failures;

    public static void main(String[] args) {
        history();
        firstRequest();
        respondReply();
        lookRound();
        refusedLook();
        factTools();
        speculation();
        abandoned();
        prose();
        failure();
        if (failures > 0) {
            System.exit(1);
        }
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            System.out.println("PASS " + name);
        } else {
            failures++;
            System.out.println("FAIL " + name + ": " + detail);
        }
    }

    // ---- fakes ----

    private static final class FakeTransport implements ClaudeApi.Transport {
        final ArrayDeque<ClaudeApi.Response> outcomes = new ArrayDeque<ClaudeApi.Response>();
        final List<Map<?, ?>> bodies = new ArrayList<Map<?, ?>>();

        FakeTransport reply(int status, String body) {
            outcomes.add(new ClaudeApi.Response(status, body));
            return this;
        }

        @Override
        public ClaudeApi.Response send(ClaudeApi.Request request) throws IOException {
            bodies.add((Map<?, ?>) Json.parse(request.body));
            ClaudeApi.Response next = outcomes.poll();
            if (next == null) {
                throw new IllegalStateException("unexpected extra request");
            }
            return next;
        }
    }

    private static final class Host implements ChatRound.Host {
        boolean may = true;
        boolean still = true;
        Boolean knows = Boolean.TRUE;
        CuriosityPort.LookResult look;
        final List<String> asks = new ArrayList<String>();
        final List<String> knowsAsked = new ArrayList<String>();

        @Override
        public boolean mayUseTools() {
            return may;
        }

        @Override
        public CuriosityPort.LookResult ask(String preamble, boolean wantsLook, long waitMs) {
            asks.add(preamble + "|" + wantsLook + "|" + waitMs);
            return wantsLook ? look : null;
        }

        @Override
        public Boolean knows(String name) {
            knowsAsked.add(name);
            return knows;
        }

        @Override
        public boolean stillAsked() {
            return still;
        }
    }

    private static final class Early implements ClaudeApi.EarlyFields {
        final List<Map<String, String>> told = new ArrayList<Map<String, String>>();

        @Override
        public void complete(Map<String, String> fields) {
            told.add(fields);
        }
    }

    private static ChatRound.Outcome run(FakeTransport t, CuriosityPort.TurnRequest req, Host host, final Early early) {
        final ClaudeApi api = new ClaudeApi(t);
        final ChatRound.Body body = ChatRound.body(req, null);
        return ChatRound.run(body, req, new ChatRound.Sender() {
            @Override
            public ClaudeApi.MessageResult send(List<Map<String, Object>> messages, ClaudeApi.Tools tools) {
                return api.conversation(ACCESS, body.system, messages, null, "low", 5000, ChatRound.EARLY_FIELDS,
                        early, tools);
            }
        }, host);
    }

    private static CuriosityPort.TurnRequest request(String heard) {
        List<CuriosityPort.Exchange> t = new ArrayList<CuriosityPort.Exchange>();
        t.add(new CuriosityPort.Exchange(null, "Hi Sarah! How was the climb?"));
        return new CuriosityPort.TurnRequest("Dry and kind.", "Sarah", "{\"interests\":[\"climbing\"]}", t, heard)
                .withFacts(new CuriosityPort.ToolFacts("Battery: about 56%, charging on the dock.",
                        "Places Miko looked at lately: 1 min ago: chair, desk."));
    }

    private static String respond(String id, String line, boolean addressed) {
        Map<String, Object> input = ChatTools.saidInput(line);
        input.put("addressed", addressed);
        return "{\"type\":\"tool_use\",\"id\":\"" + id + "\",\"name\":\"respond\",\"input\":" + Json.write(input) + "}";
    }

    private static String reply(String stopReason, String... blocks) {
        return "{\"content\":[" + String.join(",", blocks) + "],\"stop_reason\":\"" + stopReason + "\"}";
    }

    private static String text(String s) {
        return "{\"type\":\"text\",\"text\":" + Json.write(s) + "}";
    }

    private static String use(String id, String name, String inputJson) {
        return "{\"type\":\"tool_use\",\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"input\":" + inputJson + "}";
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> messages(Map<?, ?> body) {
        return (List<Map<String, Object>>) body.get("messages");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> content(Map<String, Object> message) {
        Object c = message.get("content");
        return c instanceof List ? (List<Map<String, Object>>) c : null;
    }

    // ---- scenarios ----

    private static void history() {
        List<CuriosityPort.Exchange> t = new ArrayList<CuriosityPort.Exchange>();
        t.add(new CuriosityPort.Exchange(null, "Hi! What's your name?"));
        t.add(new CuriosityPort.Exchange("I'm Sam.", "Nice to meet you, Sam."));
        CuriosityPort.TurnRequest req = new CuriosityPort.TurnRequest("", null, null, t, "How are you?");
        List<Map<String, Object>> m = ChatRound.body(req, null).messages;
        boolean shape = m.size() == 5 && "user".equals(m.get(0).get("role")) && m.get(0).get("content") instanceof String
                && "assistant".equals(m.get(1).get("role")) && "assistant".equals(m.get(3).get("role"));
        Map<String, Object> said0 = shape ? content(m.get(1)).get(0) : null;
        List<Map<String, Object>> u1 = shape ? content(m.get(2)) : null;
        List<Map<String, Object>> last = shape ? content(m.get(4)) : null;
        Map<?, ?> input = said0 == null ? null : (Map<?, ?>) said0.get("input");
        boolean ok = said0 != null && "tool_use".equals(said0.get("type")) && "respond".equals(said0.get("name"))
                && "toolu_said_0".equals(said0.get("id")) && "Hi! What's your name?".equals(input.get("line"))
                && Boolean.TRUE.equals(input.get("addressed"))
                && new ArrayList<Object>(input.keySet()).equals(ExplorePrompts.REPLY_SCHEMA.get("required"))
                && u1 != null && u1.size() == 2 && "tool_result".equals(u1.get(0).get("type"))
                && "toolu_said_0".equals(u1.get(0).get("tool_use_id")) && "said".equals(u1.get(0).get("content"))
                && "I'm Sam.".equals(u1.get(1).get("text"))
                && last != null && "toolu_said_1".equals(last.get(0).get("tool_use_id"))
                && "How are you?".equals(last.get(1).get("text"));
        // The opener with a face: the photo, then the ask; no tool_result before it.
        List<Map<String, Object>> opener = content(ChatRound.body(
                CuriosityPort.TurnRequest.opener("", null, null), new byte[]{(byte) 0xFF, (byte) 0xD8, 1, 2}).messages.get(0));
        boolean openerOk = opener != null && opener.size() == 2 && "image".equals(opener.get(0).get("type"))
                && "text".equals(opener.get(1).get("type"));
        check("round_history_is_respond_calls_each_answered_by_said_before_the_next_words", ok && openerOk,
                Json.write(m) + " opener=" + opener);
    }

    private static void firstRequest() {
        FakeTransport t = new FakeTransport().reply(200, reply("tool_use", respond("toolu_1", "Fine, thanks!", true)));
        run(t, request("How are you?"), new Host(), new Early());
        Map<?, ?> b = t.bodies.isEmpty() ? null : t.bodies.get(0);
        List<String> names = new ArrayList<String>();
        if (b != null && b.get("tools") instanceof List) {
            for (Object d : (List<?>) b.get("tools")) {
                names.add((String) ((Map<?, ?>) d).get("name"));
            }
        }
        Map<?, ?> respond = b == null ? null : (Map<?, ?>) ((List<?>) b.get("tools")).get(0);
        check("round_first_request_offers_every_tool_with_auto_choice_and_no_json_format",
                b != null && names.equals(Arrays.asList("respond", "look", "recall_person", "robot_status", "places"))
                        && ((Map<?, ?>) b.get("tool_choice")).get("type").equals("auto")
                        && !b.containsKey("output_config") && !String.valueOf(b.get("system")).contains("Reply with only a JSON")
                        && String.valueOf(b.get("system")).contains("Reply by calling the respond tool")
                        && Json.write(((Map<?, ?>) respond.get("input_schema")).get("properties"))
                        .equals(Json.write(ExplorePrompts.REPLY_SCHEMA.get("properties"))),
                b == null ? "no request" : names + " choice=" + b.get("tool_choice") + " keys=" + b.keySet());
    }

    private static void respondReply() {
        FakeTransport t = new FakeTransport().reply(200, reply("tool_use", respond("toolu_1", "Fine, thanks!", true)));
        Early early = new Early();
        Host host = new Host();
        ChatRound.Outcome o = run(t, request("How are you?"), host, early);
        check("round_a_respond_reply_is_one_request_with_addressed_told_early",
                o.result.ok() && o.reply != null && "Fine, thanks!".equals(o.reply.get("line")) && o.tools == null
                        && !o.dropped && t.bodies.size() == 1 && host.asks.isEmpty() && early.told.size() == 1
                        && "true".equals(early.told.get(0).get("addressed"))
                        && "Fine, thanks!".equals(early.told.get(0).get("line")),
                "reply=" + o.reply + " requests=" + t.bodies.size() + " early=" + early.told);
    }

    private static void lookRound() {
        FakeTransport t = new FakeTransport()
                .reply(200, reply("tool_use", text("Let me look."), use("toolu_L", "look", "{}")))
                .reply(200, reply("tool_use", respond("toolu_2", "I see a cup on your desk.", true)));
        Host host = new Host();
        host.look = CuriosityPort.LookResult.of("jpeg@9800".getBytes(StandardCharsets.US_ASCII), Arrays.asList("cup", "person"));
        Early early = new Early();
        ChatRound.Outcome o = run(t, request("What can you see?"), host, early);
        Map<?, ?> second = t.bodies.size() == 2 ? t.bodies.get(1) : null;
        List<Map<String, Object>> m = second == null ? null : messages(second);
        Map<String, Object> asked = m == null ? null : m.get(m.size() - 2);
        List<Map<String, Object>> results = m == null ? null : content(m.get(m.size() - 1));
        Map<String, Object> result = results == null || results.isEmpty() ? null : results.get(0);
        List<?> parts = result == null || !(result.get("content") instanceof List) ? null : (List<?>) result.get("content");
        Map<?, ?> choice = second == null ? null : (Map<?, ?>) second.get("tool_choice");
        check("round_a_look_says_the_preamble_waits_for_the_frame_and_forces_respond",
                o.result.ok() && o.reply != null && "I see a cup on your desk.".equals(o.reply.get("line"))
                        && "look".equals(o.tools) && host.asks.equals(Arrays.asList("Let me look.|true|"
                        + ChatTools.ADAPTER_LOOK_WAIT_MS))
                        && choice != null && "tool".equals(choice.get("type")) && "respond".equals(choice.get("name"))
                        && second.get("tools") instanceof List
                        && "assistant".equals(asked.get("role")) && content(asked).size() == 2
                        && "toolu_L".equals(content(asked).get(1).get("id"))
                        && "toolu_L".equals(result.get("tool_use_id")) && result.get("is_error") == null
                        && parts != null && parts.size() == 2 && "image".equals(((Map<?, ?>) parts.get(0)).get("type"))
                        && String.valueOf(((Map<?, ?>) parts.get(1)).get("text")).contains("cup, person")
                        && early.told.size() == 1,
                "asks=" + host.asks + " tools=" + o.tools + " choice=" + choice + " result=" + result);
    }

    private static void refusedLook() {
        FakeTransport t = new FakeTransport()
                .reply(200, reply("tool_use", use("toolu_L", "look", "{}")))
                .reply(200, reply("tool_use", respond("toolu_2", "I can't look right now.", true)));
        Host host = new Host();
        host.look = CuriosityPort.LookResult.refused("privacy: he thinks he is in a bathroom");
        ChatRound.Outcome o = run(t, request("Look at this."), host, new Early());
        Host none = new Host();
        FakeTransport t2 = new FakeTransport()
                .reply(200, reply("tool_use", use("toolu_L", "look", "{}")))
                .reply(200, reply("tool_use", respond("toolu_2", "My camera's being shy.", true)));
        run(t2, request("Look at this."), none, new Early());
        Map<String, Object> r1 = t.bodies.size() == 2 ? lastResult(t.bodies.get(1)) : null;
        Map<String, Object> r2 = t2.bodies.size() == 2 ? lastResult(t2.bodies.get(1)) : null;
        check("round_a_look_he_cant_take_is_a_tool_error_saying_why_and_sends_no_image",
                o.result.ok() && r1 != null && Boolean.TRUE.equals(r1.get("is_error"))
                        && String.valueOf(r1.get("content")).contains("bathroom")
                        && String.valueOf(r1.get("content")).contains("can't look") && !Json.write(t.bodies.get(1)).contains("\"image\"")
                        && r2 != null && Boolean.TRUE.equals(r2.get("is_error"))
                        && String.valueOf(r2.get("content")).contains("no picture in time")
                        && host.asks.equals(Arrays.asList("null|true|" + ChatTools.ADAPTER_LOOK_WAIT_MS)),
                "r1=" + r1 + " r2=" + r2 + " asks=" + host.asks);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> lastResult(Map<?, ?> body) {
        List<Map<String, Object>> m = messages(body);
        List<Map<String, Object>> c = content(m.get(m.size() - 1));
        return c == null || c.isEmpty() ? null : c.get(0);
    }

    private static void factTools() {
        FakeTransport t = new FakeTransport()
                .reply(200, reply("tool_use", use("toolu_S", "robot_status", "{}"),
                        use("toolu_R", "recall_person", "{\"name\":\"Tom\"}"), use("toolu_P", "places", "{}"),
                        use("toolu_M", "recall_person", "{\"name\":\"\"}")))
                .reply(200, reply("tool_use", respond("toolu_2", "Battery's fine.", true)));
        Host host = new Host();
        ChatRound.Outcome o = run(t, request("How's your battery, and do you know Tom?"), host, new Early());
        List<Map<String, Object>> results = t.bodies.size() == 2 ? content(messages(t.bodies.get(1))
                .get(messages(t.bodies.get(1)).size() - 1)) : null;
        String all = results == null ? "" : Json.write(results);
        check("round_status_places_and_recall_answer_from_the_turns_facts_and_share_no_ones_notes",
                o.result.ok() && results != null && results.size() == 4 && "robot_status,recall_person,places,recall_person"
                        .equals(o.tools)
                        && String.valueOf(results.get(0).get("content")).contains("about 56%")
                        && String.valueOf(results.get(1).get("content")).contains("has met someone called Tom")
                        && !String.valueOf(results.get(1).get("content")).contains("climbing")
                        && String.valueOf(results.get(2).get("content")).contains("chair, desk")
                        && String.valueOf(results.get(3).get("content")).contains("climbing")
                        && host.knowsAsked.equals(Arrays.asList("Tom")) && host.asks.equals(Arrays.asList("null|false|"
                        + ChatTools.ADAPTER_LOOK_WAIT_MS)) && !all.contains("\"image\""),
                "tools=" + o.tools + " results=" + all + " knows=" + host.knowsAsked + " asks=" + host.asks);
    }

    private static void speculation() {
        FakeTransport t = new FakeTransport().reply(200, reply("tool_use", text("Let me look."), use("toolu_L", "look", "{}")));
        Host host = new Host();
        host.may = false;
        ChatRound.Outcome o = run(t, request("What can you see?"), host, new Early());
        check("round_a_speculation_that_wants_a_tool_is_dropped_before_any_preamble_or_look",
                o.dropped && o.reply == null && host.asks.isEmpty() && t.bodies.size() == 1,
                "dropped=" + o.dropped + " asks=" + host.asks + " requests=" + t.bodies.size());
    }

    private static void abandoned() {
        FakeTransport t = new FakeTransport().reply(200, reply("tool_use", use("toolu_L", "look", "{}")));
        Host host = new Host();
        host.still = false;
        host.look = CuriosityPort.LookResult.refused("gone");
        ChatRound.Outcome o = run(t, request("What can you see?"), host, new Early());
        check("round_a_turn_abandoned_during_its_tool_round_sends_no_second_request",
                o.dropped && o.reply == null && t.bodies.size() == 1,
                "dropped=" + o.dropped + " requests=" + t.bodies.size());
    }

    private static void prose() {
        FakeTransport t = new FakeTransport().reply(200, reply("end_turn", text("Hi there, nice to see you.")));
        ChatRound.Outcome o = run(t, request("Hello"), new Host(), new Early());
        FakeTransport empty = new FakeTransport().reply(200, reply("end_turn", text("  ")));
        ChatRound.Outcome e = run(empty, request("Hello"), new Host(), new Early());
        check("round_a_prose_reply_is_a_line_said_to_him_and_an_empty_one_is_no_reply",
                o.result.ok() && o.reply != null && Boolean.TRUE.equals(o.reply.get("addressed"))
                        && "Hi there, nice to see you.".equals(o.reply.get("line")) && e.reply == null,
                "reply=" + o.reply + " empty=" + e.reply);
    }

    private static void failure() {
        FakeTransport t = new FakeTransport().reply(529, "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\"}}");
        ChatRound.Outcome o = run(t, request("Hello"), new Host(), new Early());
        FakeTransport second = new FakeTransport()
                .reply(200, reply("tool_use", use("toolu_S", "robot_status", "{}")))
                .reply(529, "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\"}}");
        ChatRound.Outcome o2 = run(second, request("Battery?"), new Host(), new Early());
        check("round_a_failed_first_or_second_request_carries_its_reason",
                !o.result.ok() && o.result.reason == ClaudeApi.Reason.OVERLOADED && o.reply == null
                        && !o2.result.ok() && o2.result.reason == ClaudeApi.Reason.OVERLOADED && o2.reply == null
                        && "robot_status".equals(o2.tools),
                o.result.describe() + " / " + o2.result.describe());
    }
}
