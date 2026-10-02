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
        actionAlone();
        actionRefused();
        oneAction();
        actionBesideRespond();
        refusedBesideRespond();
        labels();
        tasks();
        caps();
        ownerNote();
        streamedLook();
        definitionsJson();
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
                b != null && names.equals(Arrays.asList("respond", "look", "recall_person", "robot_status", "places",
                        "move", "stop", "stay", "come_here", "go_away", "be_quiet", "find_person", "find_thing",
                        "go_to_place", "wait", "run_task"))
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
    // ---- the action tools (owner 2026-10-03) ----

    private static CuriosityPort.ToolFacts facts(String still) {
        List<ChatTools.Place> places = new ArrayList<ChatTools.Place>();
        places.add(new ChatTools.Place(3 * 60000L, Arrays.asList("sink", "microwave")));
        return new CuriosityPort.ToolFacts("Battery: about 56%.", "Places: 3 min ago: sink, microwave.", still,
                Arrays.asList("printer", "chair", "refrigerator", "sink", "microwave", "person", "bus"), places);
    }

    private static CuriosityPort.TurnRequest request(String heard, String still) {
        return request(heard).withFacts(facts(still));
    }

    private static void actionAlone() {
        FakeTransport t = new FakeTransport()
                .reply(200, reply("tool_use", text("Okay, turning around."),
                        use("toolu_M", "move", "{\"kind\":\"turn_around\",\"amount\":0}")))
                .reply(200, reply("tool_use", respond("toolu_2", "There, I turned around.", true)));
        Host host = new Host();
        ChatRound.Outcome o = run(t, request("Turn around.", null), host, new Early());
        Map<String, Object> r = t.bodies.size() == 2 ? lastResult(t.bodies.get(1)) : null;
        Map<?, ?> choice = t.bodies.size() == 2 ? (Map<?, ?>) t.bodies.get(1).get("tool_choice") : null;
        check("round_an_action_alone_is_checked_its_honest_result_goes_back_and_the_act_comes_out",
                o.act != null && o.act.action == CuriosityPort.Action.MOVE && "turn_around".equals(o.act.kind)
                        && o.act.amount == 180 && "move".equals(o.tools) && o.reply != null
                        && r != null && r.get("is_error") == null
                        && String.valueOf(r.get("content")).startsWith("started: turning around")
                        && host.asks.equals(Arrays.asList("Okay, turning around.|false|" + ChatTools.ADAPTER_LOOK_WAIT_MS))
                        && choice != null && "respond".equals(choice.get("name")),
                "act=" + (o.act == null ? null : o.act.describe()) + " result=" + r + " asks=" + host.asks);
    }

    private static void actionRefused() {
        FakeTransport t = new FakeTransport()
                .reply(200, reply("tool_use", use("toolu_F", "find_thing", "{\"label\":\"printer\"}")))
                .reply(200, reply("tool_use", respond("toolu_2", "I can't, I'm on my charger.", true)));
        ChatRound.Outcome o = run(t, request("Go find the printer.",
                "he is on his charger and does not drive off it when asked"), new Host(), new Early());
        Map<String, Object> r = t.bodies.size() == 2 ? lastResult(t.bodies.get(1)) : null;
        FakeTransport q = new FakeTransport()
                .reply(200, reply("tool_use", use("toolu_Q", "be_quiet", "{\"minutes\":0}")))
                .reply(200, reply("tool_use", respond("toolu_2", "Shh, okay.", true)));
        ChatRound.Outcome quiet = run(q, request("Be quiet.", "he is on his charger and does not drive off it"),
                new Host(), new Early());
        check("round_a_drive_he_cant_make_is_a_tool_error_saying_why_and_no_act_but_be_quiet_still_works",
                o.act == null && o.reply != null && r != null && Boolean.TRUE.equals(r.get("is_error"))
                        && String.valueOf(r.get("content")).startsWith("can't: he is on his charger")
                        && quiet.act != null && quiet.act.action == CuriosityPort.Action.BE_QUIET
                        && quiet.act.ms == 10 * 60000L,
                "act=" + o.act + " result=" + r + " quiet=" + (quiet.act == null ? null : quiet.act.describe()));
    }

    private static void oneAction() {
        FakeTransport t = new FakeTransport()
                .reply(200, reply("tool_use", use("toolu_M", "move", "{\"kind\":\"spin\",\"amount\":0}"),
                        use("toolu_F", "find_thing", "{\"label\":\"chair\"}")))
                .reply(200, reply("tool_use", respond("toolu_2", "Wheee.", true)));
        ChatRound.Outcome o = run(t, request("Spin and find a chair.", null), new Host(), new Early());
        List<Map<String, Object>> results = t.bodies.size() == 2 ? content(messages(t.bodies.get(1))
                .get(messages(t.bodies.get(1)).size() - 1)) : null;
        check("round_one_action_per_reply_a_second_is_refused_and_not_done",
                o.act != null && o.act.action == CuriosityPort.Action.MOVE && "spin".equals(o.act.kind)
                        && results != null && results.size() == 2 && results.get(0).get("is_error") == null
                        && Boolean.TRUE.equals(results.get(1).get("is_error"))
                        && ChatRound.ONE_ACTION.equals(results.get(1).get("content")),
                "act=" + o.act + " results=" + results);
    }

    private static void actionBesideRespond() {
        FakeTransport t = new FakeTransport()
                .reply(200, reply("tool_use", use("toolu_S", "stop", "{}"), respond("toolu_1", "Okay, stopping.", true)));
        ChatRound.Outcome o = run(t, request("Stop that.", null), new Host(), new Early());
        check("round_an_accepted_action_beside_respond_is_taken_with_that_reply_in_one_request",
                t.bodies.size() == 1 && o.act != null && o.act.action == CuriosityPort.Action.STOP
                        && o.act.continuesConversation() && o.reply != null
                        && "Okay, stopping.".equals(o.reply.get("line")) && "stop".equals(o.tools),
                "requests=" + t.bodies.size() + " act=" + o.act);
    }

    private static void refusedBesideRespond() {
        FakeTransport t = new FakeTransport()
                .reply(200, reply("tool_use", use("toolu_M", "move", "{\"kind\":\"forward\",\"amount\":1}"),
                        respond("toolu_1", "Okay, coming forward.", true)))
                .reply(200, reply("tool_use", respond("toolu_2", "I can't drive off my charger.", true)));
        ChatRound.Outcome o = run(t, request("Come forward a bit.", "he is on his charger"), new Host(), new Early());
        List<Map<String, Object>> results = t.bodies.size() == 2 ? content(messages(t.bodies.get(1))
                .get(messages(t.bodies.get(1)).size() - 1)) : null;
        check("round_a_refused_action_beside_respond_runs_the_round_so_the_line_says_why",
                t.bodies.size() == 2 && o.act == null && o.reply != null
                        && "I can't drive off my charger.".equals(o.reply.get("line")) && results != null
                        && results.size() == 2 && Boolean.TRUE.equals(results.get(0).get("is_error"))
                        && ChatRound.NOT_SAID.equals(results.get(1).get("content")),
                "requests=" + t.bodies.size() + " results=" + results);
    }

    private static Map<String, Object> in(String json) {
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) Json.parse(json);
        return m;
    }

    private static void labels() {
        CuriosityPort.ToolFacts f = facts(null);
        ChatActions.Verdict plural = ChatActions.check("find_thing", in("{\"label\":\"the Printers\"}"), f);
        ChatActions.Verdict unknown = ChatActions.check("find_thing", in("{\"label\":\"unicorn\"}"), f);
        ChatActions.Verdict seen = ChatActions.check("go_to_place",
                in("{\"description\":\"the kitchen\",\"labels\":[\"fridge\",\"refrigerator\",\"sink\"]}"), f);
        ChatActions.Verdict unseen = ChatActions.check("go_to_place",
                in("{\"description\":\"the bus stop\",\"labels\":[\"bus\"]}"), f);
        ChatActions.Verdict nowhere = ChatActions.check("go_to_place",
                in("{\"description\":\"the kitchen\",\"labels\":[\"kitchen\"]}"), f);
        check("round_find_thing_and_go_to_place_check_labels_against_his_vocabulary_and_places",
                plural.ok() && "printer".equals(plural.act.target) && plural.act.labels.equals(Arrays.asList("printer"))
                        && !unknown.ok() && unknown.result.startsWith("can't: his detector doesn't know")
                        && unknown.result.contains("printer") && seen.ok()
                        && seen.act.labels.equals(Arrays.asList("refrigerator", "sink"))
                        && seen.result.contains("where he saw a sink 3 min ago")
                        && unseen.ok() && unseen.result.contains("searches through doorways for bus")
                        && !nowhere.ok() && nowhere.result.startsWith("don't know where that is"),
                plural.result + " | " + unknown.result + " | " + seen.result + " | " + unseen.result + " | "
                        + nowhere.result);
    }

    private static void tasks() {
        CuriosityPort.ToolFacts f = facts(null);
        ChatActions.Verdict ok = ChatActions.check("run_task", in("{\"goal\":\"see if anyone's in the kitchen\","
                + "\"steps\":[{\"tool\":\"go_to_place\",\"args\":{\"description\":\"kitchen\",\"labels\":[\"sink\"]},"
                + "\"check\":false},{\"tool\":\"look\",\"args\":{},\"check\":true},"
                + "{\"tool\":\"come_back\",\"args\":{},\"check\":false},"
                + "{\"tool\":\"say\",\"args\":{\"text\":\"Nobody there.\"},\"check\":false}]}"), f);
        ChatActions.Verdict bad = ChatActions.check("run_task", in("{\"goal\":\"x\",\"steps\":[{\"tool\":\"look\","
                + "\"args\":{},\"check\":false},{\"tool\":\"stop\",\"args\":{},\"check\":false}]}"), f);
        StringBuilder many = new StringBuilder("{\"goal\":\"x\",\"steps\":[");
        for (int i = 0; i < 9; i++) {
            many.append(i == 0 ? "" : ",").append("{\"tool\":\"wait\",\"args\":{\"seconds\":1},\"check\":false}");
        }
        ChatActions.Verdict tooMany = ChatActions.check("run_task", in(many + "]}"), f);
        ChatActions.Verdict docked = ChatActions.check("run_task", in("{\"goal\":\"x\",\"steps\":[{\"tool\":"
                + "\"move\",\"args\":{\"kind\":\"forward\",\"amount\":1},\"check\":false}]}"), facts("he is on his charger"));
        ChatActions.Verdict talkOnly = ChatActions.check("run_task", in("{\"goal\":\"x\",\"steps\":[{\"tool\":"
                + "\"say\",\"args\":{\"text\":\"Hi.\"},\"check\":false}]}"), facts("he is on his charger"));
        check("round_run_task_checks_every_step_and_a_bad_step_or_a_drive_he_cant_make_refuses_it",
                ok.ok() && ok.act.action == CuriosityPort.Action.RUN_TASK && ok.act.steps.size() == 4
                        && "go_to_place,look?,come_back,say".equals(ChatActions.stepTools(ok.act.steps))
                        && "Nobody there.".equals(ok.act.steps.get(3).text)
                        && ok.act.describe().equals("run_task 4 steps: go_to_place,look?,come_back,say")
                        && !ok.act.describe().contains("kitchen")
                        && !bad.ok() && bad.result.startsWith("can't: step 2: a task can't use stop")
                        && !tooMany.ok() && !docked.ok() && docked.result.startsWith("can't: he is on his charger")
                        && talkOnly.ok(),
                ok.result + " | " + bad.result + " | " + tooMany.result + " | " + docked.result);
    }

    private static void caps() {
        CuriosityPort.ToolFacts f = facts(null);
        ChatActions.Verdict fwd = ChatActions.check("move", in("{\"kind\":\"forward\",\"amount\":5}"), f);
        ChatActions.Verdict back = ChatActions.check("move", in("{\"kind\":\"back\",\"amount\":2}"), f);
        ChatActions.Verdict left = ChatActions.check("move", in("{\"kind\":\"turn_left\",\"amount\":720}"), f);
        ChatActions.Verdict right = ChatActions.check("move", in("{\"kind\":\"turn_right\",\"amount\":0}"), f);
        ChatActions.Verdict fly = ChatActions.check("move", in("{\"kind\":\"fly\",\"amount\":1}"), f);
        ChatActions.Verdict stay = ChatActions.check("stay", in("{\"minutes\":90}"), f);
        ChatActions.Verdict wait = ChatActions.check("wait", in("{\"seconds\":600}"), f);
        check("round_move_stay_and_wait_amounts_are_capped_and_said_so",
                fwd.ok() && fwd.act.amount == 1.5 && fwd.result.contains("his most at once")
                        && back.ok() && back.act.amount == 0.5 && left.ok() && left.act.amount == 360
                        && right.ok() && right.act.amount == 90 && !fly.ok()
                        && stay.ok() && stay.act.ms == 30 * 60000L && wait.ok() && wait.act.ms == 120000L
                        && fwd.act.describe().equals("move forward 1.50"),
                fwd.result + " | " + back.result + " | " + left.result + " | " + fly.result + " | "
                        + fwd.act.describe());
    }

    private static void ownerNote() {
        CuriosityPort.TurnRequest req = request("What do you know about me?").withOwnerNote("Sarah",
                "She is training for a marathon; be encouraging.");
        String system = ChatRound.body(req, null).system;
        String plain = ChatRound.body(request("What do you know about me?"), null).system;
        FakeTransport t = new FakeTransport()
                .reply(200, reply("tool_use", use("toolu_M", "recall_person", "{\"name\":\"\"}"),
                        use("toolu_T", "recall_person", "{\"name\":\"Tom\"}")))
                .reply(200, reply("tool_use", respond("toolu_2", "You like climbing.", true)));
        run(t, req, new Host(), new Early());
        String results = t.bodies.size() == 2 ? Json.write(lastResultList(t.bodies.get(1))) : "";
        check("round_the_owners_note_goes_in_the_system_context_with_its_guard_and_no_tool_returns_it",
                system.contains(ExplorePrompts.OWNER_NOTE_HEADING + "\nThe owner's note about Sarah: \"\"\"She is "
                        + "training for a marathon; be encouraging.\"\"\"\n\n" + ExplorePrompts.OWNER_NOTE_GUARD)
                        && system.indexOf(ExplorePrompts.NOTES_HEADING) < system.indexOf(ExplorePrompts.OWNER_NOTE_HEADING)
                        && system.indexOf(ExplorePrompts.OWNER_NOTE_HEADING) < system.indexOf("Reply by calling")
                        && !plain.contains("owner's note") && results.contains("climbing")
                        && !results.contains("marathon") && !Json.write(t.bodies.get(1).get("messages")).contains("marathon")
                        && ExplorePrompts.OWNER_NOTE_GUARD.contains("never deceive")
                        && ExplorePrompts.OWNER_NOTE_GUARD.contains("go_away, be_quiet and stop always win")
                        && ExplorePrompts.OWNER_NOTE_GUARD.contains("the owner mentioned them"),
                "results=" + results);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> lastResultList(Map<?, ?> body) {
        List<Map<String, Object>> m = messages(body);
        return content(m.get(m.size() - 1));
    }

    private static void streamedLook() {
        FakeTransport t = new FakeTransport()
                .reply(200, reply("tool_use", text("Let me look."), use("toolu_L", "look", "{}")))
                .reply(200, reply("tool_use", respond("toolu_2", "A desk.", true)));
        Host host = new Host();
        host.look = CuriosityPort.LookResult.streamed("raw@1".getBytes(StandardCharsets.US_ASCII), null, 120);
        run(t, request("What can you see?"), host, new Early());
        Map<String, Object> r = t.bodies.size() == 2 ? lastResult(t.bodies.get(1)) : null;
        List<?> parts = r == null || !(r.get("content") instanceof List) ? null : (List<?>) r.get("content");
        String caption = parts == null || parts.size() < 2 ? "" : String.valueOf(((Map<?, ?>) parts.get(1)).get("text"));
        check("round_a_streamed_frame_the_detector_never_saw_goes_with_no_labels_said_so",
                parts != null && "image".equals(((Map<?, ?>) parts.get(0)).get("type"))
                        && caption.contains("has not looked at this one") && !caption.contains("named nothing"),
                "caption=" + caption);
    }

    /** The tool definitions as sent, for the Python side to hold against the bench byte for byte. */
    private static void definitionsJson() {
        List<Map<String, Object>> plan = new ArrayList<Map<String, Object>>();
        plan.add(ClaudeApi.tool(ChatActions.PLAN, ChatActions.PLAN_DESCRIPTION, ChatActions.PLAN_SCHEMA));
        System.out.println("TOOLS " + Json.write(ChatRound.definitions()));
        System.out.println("PLAN " + Json.write(plan.get(0)));
        check("round_tool_definitions_printed", true, "");
    }
}