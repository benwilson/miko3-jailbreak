"""Tests for scripts/claude-chat-bench.py (meeting plan U2, KTD9): the ten-turn
scripted conversation's request shape (mirroring shared/ClaudeApi), the frozen
system prefix, the SSE stream parse that yields time to first token and the
cache counters, the robot-side rules it counts (sentence cap, repeated
questions), the effort gate, and the pass rule over a canned response log. No
request leaves the machine: a fake transport answers canned streams."""
import contextlib
import importlib.util
import io
import json
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCRIPT = HERE.parent / "claude-chat-bench.py"


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load {path}")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


bench = load("claude_chat_bench", SCRIPT)


def reply(line="Hello there. Nice to see you.", question="", name="", ends=False, deflected=False):
    return {"line": line, "question_asked": question, "name_given": name, "ends_conversation": ends,
            "deflected": deflected,
            "notes_update": {"interests": [], "open_threads": [], "topics": [], "questions_asked": []}}


def sse(reply_obj, input_tokens=1500, cache_creation=0, cache_read=1400, stop_reason="end_turn",
        output_tokens=40):
    """The Messages stream for one reply, as the lines the transport yields."""
    text = json.dumps(reply_obj)
    half = len(text) // 2
    events = [
        ("message_start", {"type": "message_start", "message": {
            "id": "msg_1", "type": "message", "role": "assistant", "content": [], "model": "x",
            "usage": {"input_tokens": input_tokens, "cache_creation_input_tokens": cache_creation,
                      "cache_read_input_tokens": cache_read, "output_tokens": 1}}}),
        ("content_block_start", {"type": "content_block_start", "index": 0,
                                 "content_block": {"type": "text", "text": ""}}),
        ("content_block_delta", {"type": "content_block_delta", "index": 0,
                                 "delta": {"type": "text_delta", "text": text[:half]}}),
        ("content_block_delta", {"type": "content_block_delta", "index": 0,
                                 "delta": {"type": "text_delta", "text": text[half:]}}),
        ("content_block_stop", {"type": "content_block_stop", "index": 0}),
        ("message_delta", {"type": "message_delta", "delta": {"stop_reason": stop_reason, "stop_sequence": None},
                           "usage": {"output_tokens": output_tokens}}),
        ("message_stop", {"type": "message_stop"}),
    ]
    lines = []
    for name, data in events:
        lines += [f"event: {name}", "data: " + json.dumps(data), ""]
    return lines


class FakeClock:
    def __init__(self):
        self.now = 100.0

    def __call__(self):
        return self.now

    def advance(self, s):
        self.now += s


class FakeTransport:
    """Answers each request with the next canned (status, lines) and moves the clock:
    ttft_s before the first line, then total_s - ttft_s over the rest."""

    def __init__(self, clock, answers=None, ttft_s=0.4, total_s=1.2):
        self.clock, self.answers, self.ttft_s, self.total_s = clock, list(answers or []), ttft_s, total_s
        self.requests = []

    def __call__(self, url, headers, body, timeout):
        self.requests.append((url, headers, json.loads(body)))
        status, lines = self.answers.pop(0) if self.answers else (200, sse(reply()))
        if status != 200:
            return bench.Response(status, iter([lines]))
        return bench.Response(200, self._stream(lines))

    def _stream(self, lines):
        first = True
        for line in lines:
            if first:
                self.clock.advance(self.ttft_s)
                first = False
            yield line
        self.clock.advance(self.total_s - self.ttft_s)


def quiet(fn, *args, **kw):
    with contextlib.redirect_stdout(io.StringIO()):
        return fn(*args, **kw)


class RequestShapeTest(unittest.TestCase):
    """The body and headers mirror shared/ClaudeApi.messagesRequest and headers()."""

    def test_body_mirrors_the_java_client(self):
        body = bench.build_body("claude-sonnet-5", "SYSTEM", [{"role": "user", "content": "hi"}],
                                bench.REPLY_SCHEMA, effort="low", schema_in_prompt=False)
        self.assertEqual(body["model"], "claude-sonnet-5")
        self.assertEqual(body["max_tokens"], 400)  # robot 2026-10-02: ClaudeApi.CONVERSATION_MAX_TOKENS
        self.assertEqual(body["system"], "SYSTEM")
        self.assertEqual(body["messages"], [{"role": "user", "content": "hi"}])
        self.assertEqual(body["output_config"]["format"], {"type": "json_schema", "schema": bench.REPLY_SCHEMA})
        self.assertEqual(body["output_config"]["effort"], "low")
        self.assertTrue(body["stream"])
        self.assertEqual(body["cache_control"], {"type": "ephemeral"})
        self.assertEqual(list(body)[:4], ["model", "max_tokens", "system", "messages"])

    def test_schema_in_prompt_fallback_matches_the_java_wording(self):
        body = bench.build_body("m", "SYSTEM", [], bench.REPLY_SCHEMA, effort=None, schema_in_prompt=True)
        self.assertNotIn("format", body.get("output_config", {}))
        self.assertTrue(body["system"].startswith("SYSTEM\n\nReply with only a JSON object that matches "
                                                  "this JSON schema, and no other text: "))
        self.assertNotIn("output_config", body)

    def test_headers_carry_the_key_both_ways_and_the_version(self):
        h = bench.headers("sk-ant-test")
        self.assertEqual(h["x-api-key"], "sk-ant-test")
        self.assertEqual(h["Authorization"], "Bearer sk-ant-test")
        self.assertEqual(h["anthropic-version"], "2023-06-01")
        self.assertEqual(h["content-type"], "application/json")

    def test_base_url_normalised_like_the_java_client(self):
        for given in ("https://api.example.com", "https://api.example.com/", "https://api.example.com/v1",
                      "https://api.example.com/v1/"):
            self.assertEqual(bench.normalize_base_url(given), "https://api.example.com", given)
        self.assertEqual(bench.messages_url("https://api.example.com/v1"), "https://api.example.com/v1/messages")


class SchemaTest(unittest.TestCase):
    def test_reply_schema_has_ktd9_fields_all_required_and_closed(self):
        s = bench.REPLY_SCHEMA
        # Owner 2026-10-02: "addressed" comes first (before the line), the action and target after the name.
        self.assertEqual(list(s["properties"]),
                         ["addressed", "line", "question_asked", "name_given", "action", "target",
                          "ends_conversation", "deflected", "notes_update", "feedback"])
        self.assertEqual(s["properties"]["action"]["enum"],
                         ["none", "go_away", "go_elsewhere", "find_person", "come_here", "be_quiet"])
        self.assertEqual(sorted(s["required"]), sorted(s["properties"]))
        self.assertIs(s["additionalProperties"], False)
        notes = s["properties"]["notes_update"]
        # closed_threads joined in U8 (KTD10: a closed thread moves from open threads to topics in one merge).
        self.assertEqual(sorted(notes["properties"]),
                         ["closed_threads", "interests", "open_threads", "questions_asked", "topics"])


class SystemPrefixTest(unittest.TestCase):
    def test_prefix_order_guard_persona_reminder_notes_schema(self):
        text = bench.system_prefix("Edgy but kind.", {"interests": ["coffee"]})
        parts = [bench.GUARD, "Edgy but kind.", bench.REMINDER, bench.NOTES_HEADING, "coffee", bench.SCHEMA_PREAMBLE]
        positions = [text.index(p) for p in parts]
        self.assertEqual(positions, sorted(positions), positions)

    def test_persona_is_quoted_as_data_and_empty_uses_the_builtin(self):
        text = bench.system_prefix("Say <anything>.", {})
        self.assertIn('"""', text)
        self.assertIn("Say <anything>.", text)
        self.assertIn(bench.DEFAULT_PERSONA.splitlines()[0], bench.system_prefix("", {}))
        self.assertIn(bench.DEFAULT_PERSONA.splitlines()[0], bench.system_prefix(None, {}))

    def test_prefix_is_byte_stable(self):
        a = bench.system_prefix("p", {"interests": ["a", "b"], "open_threads": []})
        b = bench.system_prefix("p", {"open_threads": [], "interests": ["a", "b"]})
        self.assertEqual(a.encode(), b.encode())

    def test_persona_cap_matches_ktd11(self):
        self.assertEqual(bench.PERSONA_CAP, 2500)
        with self.assertRaises(ValueError):
            bench.system_prefix("x" * 2501, {})


class EffortGateTest(unittest.TestCase):
    def test_model_families_that_reject_effort(self):
        for model in ("claude-haiku-4-5", "claude-sonnet-4-5", "claude-3-5-sonnet-latest", "claude-sonnet-4",
                      "claude-opus-4-1", "claude-haiku-3-5", "claude-opus-4-20250514",
                      "claude-sonnet-4-5-20250929"):
            self.assertFalse(bench.supports_effort(model), model)
        for model in ("claude-sonnet-5", "claude-opus-5", "claude-opus-4-5", "claude-opus-4-6",
                      "claude-sonnet-4-6", "claude-fable-5-1", "claude-opus-4-8"):
            self.assertTrue(bench.supports_effort(model), model)

    def test_bench_never_sends_effort_to_a_refusing_family(self):
        clock = FakeClock()
        transport = FakeTransport(clock)
        b = bench.Bench(transport, clock, model="claude-haiku-4-5", api_key="k", base_url="https://x.example")
        quiet(b.run, bench.SCRIPT[:2])
        for _, _, body in transport.requests:
            self.assertNotIn("effort", body.get("output_config", {}))
            self.assertIn("format", body["output_config"])

    def test_a_400_naming_effort_drops_it_for_the_rest_of_the_run(self):
        clock = FakeClock()
        bad = json.dumps({"type": "error", "error": {"type": "invalid_request_error",
                                                     "message": "output_config.effort: Extra inputs are not permitted"}})
        transport = FakeTransport(clock, answers=[(400, bad), (200, sse(reply())), (200, sse(reply()))])
        b = bench.Bench(transport, clock, model="claude-sonnet-5", api_key="k", base_url="https://x.example")
        records = quiet(b.run, bench.SCRIPT[:2])
        self.assertEqual(len(records), 2)
        bodies = [r[2] for r in transport.requests]
        self.assertEqual(len(bodies), 3)
        self.assertEqual(bodies[0]["output_config"]["effort"], "low")
        self.assertNotIn("effort", bodies[1]["output_config"])
        self.assertNotIn("effort", bodies[2]["output_config"])
        # The JSON-schema format survives the retry (KTD9).
        self.assertIn("format", bodies[1]["output_config"])
        self.assertFalse(records[0]["effort_sent"])

    def test_a_400_naming_output_config_but_not_effort_moves_the_schema_into_the_prompt(self):
        clock = FakeClock()
        bad = json.dumps({"type": "error", "error": {"type": "invalid_request_error",
                                                     "message": "output_config: unknown field"}})
        transport = FakeTransport(clock, answers=[(400, bad), (200, sse(reply()))])
        b = bench.Bench(transport, clock, model="claude-sonnet-5", api_key="k", base_url="https://x.example")
        quiet(b.run, bench.SCRIPT[:1])
        bodies = [r[2] for r in transport.requests]
        self.assertNotIn("format", bodies[1].get("output_config", {}))
        self.assertIn("JSON schema", bodies[1]["system"])
        self.assertEqual(bodies[1]["output_config"]["effort"], "low")

    def test_any_other_error_status_stops_the_run_with_the_status_and_no_body_echo(self):
        clock = FakeClock()
        transport = FakeTransport(clock, answers=[(401, '{"error":{"message":"secret sk-ant-abc"}}')])
        b = bench.Bench(transport, clock, model="claude-sonnet-5", api_key="sk-ant-abc", base_url="https://x.example")
        with self.assertRaises(bench.BenchError) as ctx:
            quiet(b.run, bench.SCRIPT[:1])
        self.assertIn("401", str(ctx.exception))
        self.assertNotIn("sk-ant-abc", str(ctx.exception))


class StreamParseTest(unittest.TestCase):
    def test_usage_text_stop_reason_and_first_token_time(self):
        clock = FakeClock()
        lines = sse(reply("One. Two."), input_tokens=1700, cache_creation=1500, cache_read=0,
                    stop_reason="end_turn", output_tokens=33)
        started = clock()

        def stream():
            for i, line in enumerate(lines):
                if i == 0:
                    clock.advance(0.35)
                yield line

        result = bench.parse_stream(stream(), clock, started)
        self.assertEqual(json.loads(result.text)["line"], "One. Two.")
        self.assertEqual(result.stop_reason, "end_turn")
        self.assertEqual(result.input_tokens, 1700)
        self.assertEqual(result.cache_creation_input_tokens, 1500)
        self.assertEqual(result.cache_read_input_tokens, 0)
        self.assertEqual(result.output_tokens, 33)
        self.assertAlmostEqual(result.ttft_s, 0.35, places=3)

    def test_a_stream_with_no_text_has_no_first_token_time(self):
        clock = FakeClock()
        lines = [ln for ln in sse(reply()) if "text_delta" not in ln]
        result = bench.parse_stream(iter(lines), clock, clock())
        self.assertIsNone(result.ttft_s)
        self.assertEqual(result.text, "")

    def test_an_error_event_mid_stream_is_a_bench_error(self):
        clock = FakeClock()
        lines = ["event: error", 'data: {"type":"error","error":{"type":"overloaded_error","message":"x"}}', ""]
        with self.assertRaises(bench.BenchError) as ctx:
            bench.parse_stream(iter(lines), clock, clock())
        self.assertIn("overloaded_error", str(ctx.exception))


class RobotRulesTest(unittest.TestCase):
    def test_sentence_cap_drops_from_the_third(self):
        e = bench.enforce(reply("First one. Second one! Third one? Fourth."), set())
        self.assertEqual(e.line, "First one. Second one!")
        self.assertEqual(e.trimmed, 2)
        e2 = bench.enforce(reply("Just one."), set())
        self.assertEqual(e2.trimmed, 0)

    def test_question_normalised_and_repeats_counted(self):
        self.assertEqual(bench.normalise_question("  What's your  favourite Coffee? "), "whats your favourite coffee")
        asked = {"whats your favourite coffee"}
        e = bench.enforce(reply("Hi.", question="What's your favourite coffee?"), asked)
        self.assertTrue(e.repeat)
        self.assertEqual(e.question, "whats your favourite coffee")
        e2 = bench.enforce(reply("Hi.", question="Do you like tea?"), asked)
        self.assertFalse(e2.repeat)
        self.assertFalse(bench.enforce(reply("Hi.", question=""), asked).repeat)

    def test_a_reply_that_is_not_the_schema_object_counts_as_unparsed(self):
        e = bench.enforce("not json at all", set())
        self.assertFalse(e.parsed)
        self.assertEqual(e.line, "")


class ConversationTest(unittest.TestCase):
    def test_ten_turns_grow_the_transcript_and_log_the_named_fields(self):
        clock = FakeClock()
        transport = FakeTransport(clock)
        b = bench.Bench(transport, clock, model="claude-sonnet-5", api_key="k", base_url="https://x.example/v1")
        records = quiet(b.run, bench.SCRIPT)
        self.assertEqual(len(bench.SCRIPT), 10)
        self.assertEqual(len(records), 10)
        for n, (url, headers, body) in enumerate(transport.requests, start=1):
            self.assertEqual(url, "https://x.example/v1/messages")
            self.assertEqual(len(body["messages"]), 2 * n - 1)
            self.assertEqual(body["messages"][-1], {"role": "user", "content": bench.SCRIPT[n - 1]})
            self.assertEqual(body["system"], transport.requests[0][2]["system"])
        for r in records:
            for key in ("turn", "input_tokens", "cache_creation_input_tokens", "cache_read_input_tokens",
                        "ttft_ms", "total_ms", "stop_reason", "question_asked", "trimmed", "repeat",
                        "max_tokens_hit", "effort_sent", "line"):
                self.assertIn(key, r)
        self.assertEqual(records[0]["turn"], 1)
        self.assertAlmostEqual(records[0]["ttft_ms"], 400, delta=1)
        self.assertAlmostEqual(records[0]["total_ms"], 1200, delta=1)
        self.assertTrue(records[0]["effort_sent"])
        self.assertFalse(records[0]["max_tokens_hit"])
        # The assistant's own JSON is what goes back in the transcript.
        json.loads(transport.requests[1][2]["messages"][1]["content"])

    def test_max_tokens_stop_is_flagged(self):
        clock = FakeClock()
        transport = FakeTransport(clock, answers=[(200, sse(reply(), stop_reason="max_tokens"))])
        b = bench.Bench(transport, clock, model="claude-sonnet-5", api_key="k", base_url="https://x.example")
        records = quiet(b.run, bench.SCRIPT[:1])
        self.assertTrue(records[0]["max_tokens_hit"])

    def test_transcript_window_keeps_the_last_30_exchanges(self):
        self.assertEqual(bench.TRANSCRIPT_WINDOW, 30)
        msgs = [{"role": "user" if i % 2 == 0 else "assistant", "content": str(i)} for i in range(70)]
        kept = bench.window(msgs)
        self.assertEqual(len(kept), 60)
        self.assertEqual(kept[0]["role"], "user")
        self.assertEqual(kept[-1], msgs[-1])


def canned_records(overrides=None, n=10):
    records = []
    for turn in range(1, n + 1):
        r = {"turn": turn, "input_tokens": 1600 + 20 * turn, "cache_creation_input_tokens": 1500 if turn == 1 else 0,
             "cache_read_input_tokens": 0 if turn == 1 else 1500, "ttft_ms": 600.0, "total_ms": 1800.0,
             "stop_reason": "end_turn", "question_asked": f"q{turn}", "trimmed": 0, "repeat": False,
             "max_tokens_hit": False, "effort_sent": True, "line": "Hi."}
        r.update((overrides or {}).get(turn, {}))
        records.append(r)
    return records


class VerdictTest(unittest.TestCase):
    def test_pass_on_the_recommended_model(self):
        v = bench.analyse(canned_records(), "claude-sonnet-5")
        self.assertTrue(v.passed)
        self.assertEqual(v.reasons, [])
        self.assertAlmostEqual(v.total_p95_ms, 1800.0)
        self.assertEqual(v.cache_missing_turns, [])
        self.assertEqual(bench.TURN_BUDGET_MS, 3000)

    def test_a_missing_cache_read_from_turn_two_fails(self):
        v = bench.analyse(canned_records({3: {"cache_read_input_tokens": 0}}), "claude-sonnet-5")
        self.assertFalse(v.passed)
        self.assertEqual(v.cache_missing_turns, [3])
        self.assertTrue(any("cache" in r for r in v.reasons))

    def test_a_turn_over_budget_moves_p95_and_fails(self):
        v = bench.analyse(canned_records({7: {"total_ms": 4200.0}}), "claude-sonnet-5")
        self.assertFalse(v.passed)
        self.assertAlmostEqual(v.total_p95_ms, 4200.0)
        self.assertEqual(v.over_budget_turns, [7])

    def test_max_tokens_turns_are_named(self):
        v = bench.analyse(canned_records({2: {"stop_reason": "max_tokens", "max_tokens_hit": True}}), "claude-sonnet-5")
        self.assertEqual(v.max_tokens_turns, [2])
        self.assertFalse(v.passed)

    def test_pass_rule_is_advisory_off_the_recommended_model(self):
        v = bench.analyse(canned_records({7: {"total_ms": 4200.0}}), "claude-haiku-4-5")
        self.assertFalse(v.recommended)
        self.assertIsNone(v.passed)
        self.assertTrue(any("advisory" in r or "recommended" in r for r in v.reasons))

    def test_table_names_every_turn_and_the_verdict(self):
        records = canned_records({2: {"stop_reason": "max_tokens", "max_tokens_hit": True}})
        text = bench.format_report(records, bench.analyse(records, "claude-sonnet-5"))
        self.assertEqual(len([ln for ln in text.splitlines() if ln.strip().startswith(tuple("123456789"))]), 10)
        self.assertIn("max_tokens", text)
        self.assertIn("FAIL", text)


class LogTest(unittest.TestCase):
    def test_log_round_trips_and_replay_reaches_the_same_verdict(self):
        records = canned_records()
        with tempfile.TemporaryDirectory() as td:
            path = Path(td) / "out" / "bench.jsonl"
            bench.write_log(records, path, model="claude-sonnet-5")
            lines = path.read_text().splitlines()
            self.assertEqual(len(lines), 11)
            self.assertEqual(json.loads(lines[0])["model"], "claude-sonnet-5")
            model, back = bench.read_log(path)
        self.assertEqual(model, "claude-sonnet-5")
        self.assertEqual(back, records)
        self.assertTrue(bench.analyse(back, model).passed)


class MainTest(unittest.TestCase):
    def test_parser_defaults_and_replay(self):
        args = bench.build_parser().parse_args([])
        self.assertEqual(args.model, None)
        self.assertEqual(args.turns, 10)
        self.assertEqual(args.log, "out/claude-chat-bench.jsonl")
        self.assertIsNone(args.replay)
        self.assertEqual(bench.RECOMMENDED_MODEL, "claude-sonnet-5")

    def test_credentials_come_from_the_environment_and_default_the_base_url(self):
        key, base, model = bench.credentials({"ANTHROPIC_API_KEY": "sk-ant-x"}, None, None)
        self.assertEqual((key, base, model), ("sk-ant-x", bench.DEFAULT_BASE_URL, bench.RECOMMENDED_MODEL))
        key, base, model = bench.credentials({"ANTHROPIC_API_KEY": "k", "ANTHROPIC_BASE_URL": "https://p.example/v1",
                                              "ANTHROPIC_MODEL": "claude-opus-5"}, None, None)
        self.assertEqual((base, model), ("https://p.example", "claude-opus-5"))
        with self.assertRaises(bench.BenchError):
            bench.credentials({}, None, None)
        with self.assertRaises(bench.BenchError):
            bench.credentials({"ANTHROPIC_API_KEY": "k"}, "http://plain.example", None)


if __name__ == "__main__":
    unittest.main()
