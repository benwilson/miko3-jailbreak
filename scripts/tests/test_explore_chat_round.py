#!/usr/bin/env python3
"""Host tests for ChatRound (owner 2026-10-03): Explore's conversation turn replies through
the respond tool (about 1.05 s to the line on Haiku, against 2.1 s for a JSON-schema reply)
and runs at most one tool round (look, recall_person, robot_status, places) before it.

ChatRound is plain Java on the shared Claude client, so this compiles it with the brain's
plain-Java classes and the shared client, and runs fixtures/explore_chat_round_harness
with a fake transport: no request leaves the machine.
"""
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

TESTS = Path(__file__).resolve().parent
if str(TESTS) not in sys.path:
    sys.path.insert(0, str(TESTS))
import jvm_harness  # noqa: E402

REPO = Path(__file__).resolve().parents[2]
EXPLORE_SRC = REPO / "mode-explore" / "src"
EXPLORE_PKG = EXPLORE_SRC / "com" / "miko3" / "mode" / "explore"
SHARED = REPO / "shared" / "src" / "com" / "miko3" / "shared"
HARNESS = TESTS / "fixtures" / "explore_chat_round_harness" / "src"
HARNESS_MAIN = HARNESS / "com" / "miko3" / "mode" / "explore" / "ChatRoundHarness.java"
# The shared client's plain-Java classes by name (the rest of shared/ needs the Android SDK);
# the explore classes ChatRound reaches come from the sourcepath, as the brain harness does.
SOURCES = [HARNESS_MAIN, EXPLORE_PKG / "ChatRound.java"] + [
    SHARED / n for n in ("ClaudeApi.java", "Json.java", "ClaudeAccess.java", "JpegSlim.java", "NameExtractor.java")]


class ChatRoundIsPlainJavaTest(unittest.TestCase):
    def test_no_android_imports_and_no_logging(self):
        text = (EXPLORE_PKG / "ChatRound.java").read_text()
        self.assertNotIn("import android", text)
        self.assertNotIn("Log.", text)
        self.assertNotIn("System.out", text)


class ChatRoundHarnessTest(unittest.TestCase):
    SCENARIOS = (
        "round_history_is_respond_calls_each_answered_by_said_before_the_next_words",
        "round_first_request_offers_every_tool_with_auto_choice_and_no_json_format",
        "round_a_respond_reply_is_one_request_with_addressed_told_early",
        "round_a_look_says_the_preamble_waits_for_the_frame_and_forces_respond",
        "round_a_look_he_cant_take_is_a_tool_error_saying_why_and_sends_no_image",
        "round_status_places_and_recall_answer_from_the_turns_facts_and_share_no_ones_notes",
        "round_a_speculation_that_wants_a_tool_is_dropped_before_any_preamble_or_look",
        "round_a_turn_abandoned_during_its_tool_round_sends_no_second_request",
        "round_a_prose_reply_is_a_line_said_to_him_and_an_empty_one_is_no_reply",
        "round_a_failed_first_or_second_request_carries_its_reason",
        # Owner 2026-10-03: the action tools (ChatActions) and the owner's notes.
        "round_an_action_alone_is_checked_its_honest_result_goes_back_and_the_act_comes_out",
        "round_a_drive_he_cant_make_is_a_tool_error_saying_why_and_no_act_but_be_quiet_still_works",
        "round_one_action_per_reply_a_second_is_refused_and_not_done",
        "round_an_accepted_action_beside_respond_is_taken_with_that_reply_in_one_request",
        "round_a_refused_action_beside_respond_runs_the_round_so_the_line_says_why",
        "round_find_thing_and_go_to_place_check_labels_against_his_vocabulary_and_places",
        "round_run_task_checks_every_step_and_a_bad_step_or_a_drive_he_cant_make_refuses_it",
        "round_move_stay_and_wait_amounts_are_capped_and_said_so",
        "round_the_owners_note_goes_in_the_system_context_with_its_guard_and_no_tool_returns_it",
        "round_tool_definitions_printed",
        "round_a_streamed_frame_the_detector_never_saw_goes_with_no_labels_said_so",
        # Review 2026-10-03: the flight, ChatRound -> TurnFlight -> ChatSession.
        "flight_an_accepted_action_after_an_early_line_reaches_the_conversation_and_ends_it",
        "flight_a_refused_action_after_an_early_line_says_the_corrected_line_next",
        "flight_an_unstreamed_action_beside_respond_reaches_the_conversation",
    )

    @classmethod
    def setUpClass(cls):
        jdk = jvm_harness.find_jdk()
        if jdk is None:
            raise unittest.SkipTest("no JDK (javac + java) found")
        cls._td = tempfile.TemporaryDirectory(prefix="chat_round_harness_")
        out = cls._td.name
        c = subprocess.run(jvm_harness.javac_cmd(jdk[0], out, SOURCES, [HARNESS, EXPLORE_SRC]), capture_output=True, text=True)
        cls.compiled = c.returncode == 0
        cls.compile_output = (c.stdout + c.stderr)[-3000:]
        cls.results = {}
        cls.printed = {}
        cls.run_output = ""
        if cls.compiled:
            r = subprocess.run([jdk[1], "-cp", out, "com.miko3.mode.explore.ChatRoundHarness"],
                               capture_output=True, text=True, timeout=60)
            cls.run_output = (r.stdout + r.stderr)[-6000:]
            cls.results = jvm_harness.parse_verdicts(r.stdout)
            cls.printed = {k: v for k, _, v in (line.partition(" ") for line in r.stdout.splitlines())
                           if k in ("TOOLS", "PLAN")}

    @classmethod
    def tearDownClass(cls):
        cls._td.cleanup()

    def setUp(self):
        self.assertTrue(self.compiled, f"harness failed to compile:\n{self.compile_output}")

    def _assert_pass(self, name):
        self.assertIn(name, self.results, f"scenario {name} never reported:\n{self.run_output}")
        verdict, detail = self.results[name]
        self.assertEqual(verdict, "PASS", f"{name}: {detail}")

    def test_harness_reports_exactly_the_expected_scenarios(self):
        self.assertEqual(sorted(self.results), sorted(self.SCENARIOS), self.run_output)


    def test_the_bench_sends_the_robots_tool_definitions_byte_for_byte(self):
        """Owner 2026-10-03: every tool's name, description and schema, in order, as ChatRound sends them."""
        import importlib.util
        import json
        spec = importlib.util.spec_from_file_location("claude_chat_bench", REPO / "scripts" / "claude-chat-bench.py")
        bench = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(bench)
        self.assertIn("TOOLS", self.printed, self.run_output)
        self.assertEqual(json.dumps(json.loads(self.printed["TOOLS"])), json.dumps(bench.TOOLS))
        self.assertEqual(json.dumps(json.loads(self.printed["PLAN"])), json.dumps(bench.PLAN_TOOL))


jvm_harness.add_scenario_tests(ChatRoundHarnessTest)


if __name__ == "__main__":
    unittest.main()
