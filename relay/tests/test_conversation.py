"""Tests for relay/relay/conversation.py, the KTD4 conversation engine (plan U5).

The engine runs as the lane's handler between the fake robot and the scripted fake model
server, on shortened timeouts. Covers U5's scenarios: the farewell-phrase drain and the
dormant sleep-word one (AE3) with their two time limits, the reply-audio gate that keeps
the model's continuous out-of-reply audio off the robot and derives each reply's start and
end from the audio itself (the real server never sends agent_end), the silence timer (AE4), strict
turn-taking (AE6) and its underrun rule, barge-in flush, a fresh model session per
conversation (AE7), link loss (AE10), model failures and the health probe (KTD4), per-turn
latency fields and the log never blocking.
"""
import asyncio
import contextlib
import json
import os
import re
import signal
import sys
import tempfile
import time
import unittest
import unittest.mock
from pathlib import Path


RELAY_ROOT = Path(__file__).resolve().parents[1]
if str(RELAY_ROOT) not in sys.path:
    sys.path.insert(0, str(RELAY_ROOT))

from relay.conversation import (  # noqa: E402
    Config,
    ConversationEngine,
    LoopGuard,
    UplinkGate,
)
from relay.lane import LaneServer, Pacing  # noqa: E402
from relay.logging import ConversationLogs  # noqa: E402
from relay import main  # noqa: E402
from tests.fake_model_server import (  # noqa: E402
    Burst,
    FakeModelServer,
    Script,
    Turn,
    idle_chunk,
    reply_chunks,
    speech_chunks,
)
from tests.fake_robot import FakeRobot  # noqa: E402

PERSONA = "You are Miko, a friendly robot. Keep replies short."
LOUD = (8000).to_bytes(2, "little", signed=True) * 1280  # 80 ms of 16 kHz, RMS 8000
NOISE = (400).to_bytes(2, "little", signed=True) * 1280  # 80 ms of the robot's room noise
SPEECH = (3000).to_bytes(2, "little", signed=True) * 1280  # 80 ms of someone talking
GATE_FRAME = 640  # bytes in the gate's 20 ms decision frame


class PortedFakeServer(FakeModelServer):
    """The fake model server on a chosen port (to stop and restart it in place), able to
    hold the handshake of its next connection for `delay_next` seconds."""

    def __init__(self, script=None, port=0):
        super().__init__(script, port)
        self.delay_next = 0.0
        self.connections = 0

    async def _process_request(self, connection, request):
        self.connections += 1
        delay, self.delay_next = self.delay_next, 0.0
        if delay:
            await asyncio.sleep(delay)
        return None


def nonzero_frames(session):
    return [f for f in session.binaries() if any(f.data)]


class EngineTestCase(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.stack = contextlib.AsyncExitStack()
        self.log_dir = Path(tempfile.mkdtemp(prefix="relay-conv-"))

    async def asyncTearDown(self):
        await self.stack.aclose()

    async def start(self, script=None, *, model=None, lane_ping=2.0, logs_writer=None,
                    turn_taking="interruptible", pacing=None, **overrides):
        """Fake model server, logs, engine, lane and a connected fake robot."""
        self.model = model or FakeModelServer(script or Script())
        if model is None:
            await self.stack.enter_async_context(self.model)
        self.logs = ConversationLogs(self.log_dir, flush_interval=0.02)
        if logs_writer is not None:
            self.logs._write_batch = logs_writer
        await self.logs.start()
        self.stack.push_async_callback(self.logs.stop)
        settings = dict(model_host="127.0.0.1", model_port=self.model.port, persona=PERSONA,
                        ready_timeout=0.5, silence_timeout=5.0, drain_cap=2.0,
                        farewell_start_timeout=0.3, cooldown=0.05, probe_interval=0.1,
                        probe_timeout=0.3, sleep_settle=0.1, strict_idle_grace=1.0,
                        reply_silence_ms=250,
                        # Default off here so every test in this file exercises the
                        # connect-per-conversation path unchanged; tests/test_prewarm.py
                        # covers the warm session, and MainTests below runs the real
                        # default (on) end to end.
                        prewarm=False)
        settings.update(overrides)
        self.engine = ConversationEngine(Config(**settings), self.logs)
        await self.engine.start()
        self.stack.push_async_callback(self.engine.stop)
        self.lane = LaneServer(self.engine, "127.0.0.1", 0, ping_interval=lane_ping,
                               pacing=pacing or Pacing())
        await self.lane.start()
        self.stack.push_async_callback(self.lane.stop)
        robot = FakeRobot(self.lane.port, turn_taking=turn_taking)
        self.stack.push_async_callback(robot.close)
        self.welcome = await robot.connect()
        return robot

    async def open_ready(self, robot, turn_taking=None):
        mark = len(robot.frames)
        conv = await robot.open_conv(turn_taking)
        ready = await robot.wait_text("conv.ready", after=mark)
        self.assertEqual(ready.data["conv"], conv)
        return ready

    async def wait_close(self, robot, after=0, timeout=2.0):
        return (await robot.wait_text("conv.close", timeout=timeout, after=after)).data

    async def probes_done(self, model):
        """Wait out engine.start()'s probe and the new link's first one, so that
        model.delay_next applies to the conversation's own connection."""
        await model.wait_until(lambda: len(model.sessions) >= 2 and all(
            s.ws.close_code is not None for s in model.sessions))

    def conv_sessions(self):
        return [s for s in self.model.sessions
                if any(d.get("type") == "system" for d in s.texts())]

    async def records(self, conv):
        """The log records of the robot conversation `conv` (after a flush)."""
        await asyncio.sleep(0.1)
        for path in sorted(self.log_dir.glob("*.jsonl")):
            records = [json.loads(line) for line in path.read_text().splitlines()]
            if records and records[0]["ev"] == "open" and records[0]["conv"] == conv:
                return records
        self.fail(f"no log for conversation {conv}")

    async def agent_end(self, index=0):
        """Wait until the fake server has sent the index-th agent_end."""
        def done():
            sent = self.conv_sessions()[0].sent
            return len([m for m in sent if m.get("kind") == "agent_end"]) > index
        await self.model.wait_until(done)

    @staticmethod
    def of(records, ev):
        return [r for r in records if r["ev"] == ev]


class DrainTests(EngineTestCase):
    async def test_goodbye_drains_the_farewell_then_closes_on_playback_idle(self):  # AE3
        script = Script(turns=[Turn(user_end_at=0.1, user_text=["goodbye ", "miko"],
                                    reply_delay=0.1, reply_seconds=0.24)])
        robot = await self.start(script)
        await self.open_ready(robot)
        conv = robot.conv
        await robot.wait_for(lambda: len(robot.binaries()) >= 3)  # the farewell, forwarded
        await robot.send_audio(LOUD)  # uplink is silence while draining
        await asyncio.sleep(0.2)  # agent_end has arrived; the robot has not reported idle
        self.assertEqual(robot.texts("conv.close"), [])
        self.assertEqual(nonzero_frames(self.conv_sessions()[0]), [])
        await robot.playback("idle")
        self.assertEqual((await self.wait_close(robot))["reason"], "sleep_word")
        records = await self.records(conv)
        match = self.of(records, "sleepword")[0]
        self.assertEqual((match["kind"], match["source"]), ("match", "final"))
        self.assertEqual(match["transcript"], "goodbye miko")
        self.assertEqual(len(self.of(records, "drain")), 1)
        self.assertEqual(records[-1]["ev"], "close")
        self.assertEqual(records[-1]["reason"], "sleep_word")
        await asyncio.sleep(0.1)
        self.assertIsNotNone(self.conv_sessions()[0].ws.close_code)  # model connection closed

    async def test_no_farewell_within_start_timeout_closes_at_once(self):  # AE3
        script = Script(turns=[Turn(user_end_at=0.1, user_text=["bye meeko"], reply_delay=None)])
        robot = await self.start(script, farewell_start_timeout=0.3)
        ready = await self.open_ready(robot)
        closed = await self.wait_close(robot)
        self.assertEqual(closed["reason"], "sleep_word")
        elapsed = robot.texts("conv.close")[0].t - ready.t
        self.assertGreater(elapsed, 0.35)
        self.assertLess(elapsed, 1.0)

    async def test_drain_longer_than_cap_closes_with_farewell_timeout(self):
        script = Script(turns=[Turn(user_end_at=0.1, user_text=["goodbye miko"],
                                    reply_delay=0.05, reply_seconds=2.0)])
        robot = await self.start(script, drain_cap=0.5)
        ready = await self.open_ready(robot)
        closed = await self.wait_close(robot)
        self.assertEqual(closed["reason"], "farewell_timeout")
        self.assertLess(robot.texts("conv.close")[0].t - ready.t, 1.0)
        self.assertGreater(len(robot.binaries()), 0)

    async def test_split_deltas_match_once_settled_before_user_end(self):
        script = Script(turns=[Turn(user_start_at=0.05, user_end_at=0.8,
                                    user_text=["goodbye ", "miko"], reply_delay=None)])
        robot = await self.start(script)
        await self.open_ready(robot)
        conv = robot.conv
        self.assertEqual((await self.wait_close(robot))["reason"], "sleep_word")
        match = self.of(await self.records(conv), "sleepword")[0]
        self.assertEqual((match["kind"], match["source"]), ("match", "partial"))

    async def test_near_miss_is_logged_and_does_not_close(self):
        script = Script(turns=[Turn(user_end_at=0.1, user_text=["goodbye mike"],
                                    reply_delay=0.05, reply_seconds=0.16)])
        robot = await self.start(script)
        await self.open_ready(robot)
        conv = robot.conv
        await asyncio.sleep(0.5)
        self.assertEqual(robot.texts("conv.close"), [])
        await robot.close_conv()
        near = self.of(await self.records(conv), "sleepword")
        self.assertEqual(len(near), 1)
        self.assertEqual(near[0]["kind"], "near_miss")
        self.assertEqual(near[0]["transcript"], "goodbye mike")
        self.assertTrue(70 <= near[0]["score"] < 85)

    async def test_assistant_saying_goodbye_miko_never_matches(self):
        script = Script(turns=[Turn(user_end_at=0.1, user_text=["tell me a joke"],
                                    reply_delay=0.05, reply_seconds=0.16,
                                    reply_text=["Goodbye ", "Miko!"])])
        robot = await self.start(script)
        await self.open_ready(robot)
        conv = robot.conv
        await asyncio.sleep(0.6)
        self.assertEqual(robot.texts("conv.close"), [])
        await robot.close_conv()
        self.assertEqual(self.of(await self.records(conv), "sleepword"), [])


class ReplyAudioGateTests(EngineTestCase):
    """The model streams reply-channel audio continuously, not only while it is speaking;
    only audio between agent_start and agent_end may reach the robot."""

    async def test_audio_outside_a_reply_is_not_forwarded_and_is_counted(self):
        robot = await self.start(Script(idle_audio_every=0.04))
        await self.open_ready(robot)
        conv = robot.conv
        await self.model.wait_until(lambda: self.conv_sessions()[0].idle_bytes_sent >= 5 * 3528)
        await asyncio.sleep(0.1)
        self.assertEqual(robot.binaries(), [])
        self.assertEqual(robot.texts("reply"), [])
        await robot.close_conv()
        close = self.of(await self.records(conv), "close")[0]
        self.assertGreaterEqual(close["out_of_reply"]["chunks"], 5)
        self.assertEqual(close["out_of_reply"]["bytes"],
                         close["out_of_reply"]["chunks"] * 3528)

    async def test_audio_inside_a_reply_is_forwarded_unchanged(self):
        script = Script(turns=[Turn(user_end_at=0.05, reply_delay=0.05, reply_seconds=0.24)])
        robot = await self.start(script)
        await self.open_ready(robot)
        conv = robot.conv
        await robot.wait_for(lambda: len(robot.binaries()) >= 3)
        got = [f.data for f in robot.binaries()]
        self.assertEqual(len(got), 3)
        self.assertEqual(got, reply_chunks(script, 0))
        await robot.close_conv()
        close = self.of(await self.records(conv), "close")[0]
        self.assertEqual(close["out_of_reply"]["chunks"], 0)

    async def test_the_reply_is_forwarded_while_out_of_reply_audio_is_dropped(self):
        script = Script(turns=[Turn(user_end_at=0.1, reply_delay=0.05, reply_seconds=0.24)],
                        idle_audio_every=0.04)
        robot = await self.start(script)
        await self.open_ready(robot)
        conv = robot.conv
        await self.agent_end(0)
        await asyncio.sleep(0.4)  # the paced queue empties; idle audio keeps arriving
        got = [f.data for f in robot.binaries()]
        for chunk in reply_chunks(script, 0):
            self.assertIn(chunk, got)  # the reply itself is forwarded
        self.assertTrue(all(f.t >= robot.texts("reply")[0].t for f in robot.binaries()))
        # Idle audio that lands inside the reply is indistinguishable and rides along; what
        # matters is that the stream stops reaching the robot once the reply is over.
        settled = len(robot.binaries())
        self.assertGreater(self.conv_sessions()[0].idle_bytes_sent, settled * 3528)
        await asyncio.sleep(0.3)
        self.assertEqual(len(robot.binaries()), settled)
        await robot.close_conv()
        close = self.of(await self.records(conv), "close")[0]
        self.assertGreater(close["out_of_reply"]["chunks"], 0)

    async def test_flush_mid_reply_discards_and_the_next_reply_still_plays(self):
        script = Script(turns=[Turn(user_end_at=0.05, reply_delay=0.05, reply_seconds=1.6),
                               Turn(user_start_at=0.9, user_end_at=1.0, reply_delay=0.05,
                                    reply_seconds=0.24)],
                        flush_rms=1000, idle_audio_every=0.04)
        robot = await self.start(script)
        await self.open_ready(robot)
        await robot.wait_for(lambda: robot.binaries())
        await robot.send_audio(LOUD)
        flush = await robot.wait_text("audio.flush")
        index = robot.frames.index(flush)
        await asyncio.sleep(0.3)  # idle audio keeps arriving; none of it may be forwarded
        self.assertEqual([f for f in robot.frames[index:] if f.kind == "binary"], [])
        second = await robot.wait_text("reply", after=index)
        await robot.wait_for(lambda: any(f.kind == "binary"
                                         for f in robot.frames[robot.frames.index(second):]))

    async def test_playback_idle_can_arrive_because_the_speaker_drains(self):
        """The bug this gate fixes: with audio flowing forever the robot never went idle."""
        script = Script(turns=[Turn(user_end_at=0.1, reply_delay=0.05, reply_seconds=0.24,
                                    reply_text=["Sure. Talk to you later."])],
                        idle_audio_every=0.04)
        robot = await self.start(script)
        await self.open_ready(robot)
        await self.agent_end(0)  # an idle before agent_end would only be an underrun
        await robot.playback("idle")
        self.assertEqual((await self.wait_close(robot))["reason"], "sleep_word")


class DerivedReplyBoundaryTests(EngineTestCase):
    """Measured against the real server: `agent_end` never fires when a reply simply
    finishes (the child emits it only when the person takes the floor back), and the reply
    channel is exact digital silence between replies. So the relay derives both ends of a
    reply from the audio, with agent_start / agent_end / flush as secondary signals."""

    def real_script(self, **turn_fields):
        """A reply the way the real server sends one: loud audio, then continuous digital
        silence, and no agent_end at all."""
        fields = dict(user_end_at=0.05, reply_delay=0.05, reply_seconds=0.24,
                      agent_end=False)
        fields.update(turn_fields)
        return Script(turns=[Turn(**fields)], idle_audio_every=0.08)

    async def test_reply_without_agent_end_ends_after_the_silence_hangover(self):
        script = self.real_script()
        robot = await self.start(script)
        await self.open_ready(robot)
        conv = robot.conv
        await robot.wait_for(lambda: len(robot.binaries()) >= 3)
        await asyncio.sleep(0.6)  # past the hangover, with silence still arriving
        session = self.conv_sessions()[0]
        self.assertEqual([m for m in session.sent if m.get("kind") == "agent_end"], [])
        self.assertEqual(len(robot.texts("reply")), 1)  # exactly one reply
        got = [f.data for f in robot.binaries()]
        for chunk in reply_chunks(script, 0):
            self.assertIn(chunk, got)  # with all of its audio
        settled = len(robot.binaries())
        await asyncio.sleep(0.3)
        self.assertEqual(len(robot.binaries()), settled)  # and a clean end
        await robot.playback("idle")  # a real idle, not an underrun
        await asyncio.sleep(0.1)
        await robot.close_conv()
        records = await self.records(conv)
        ends = self.of(records, "reply.end")
        self.assertEqual(len(ends), 1, ends)
        self.assertEqual(ends[0]["why"], "silence")
        self.assertGreater(ends[0]["duration_ms"], 240)
        self.assertEqual(self.of(records, "underrun"), [])
        turn = self.of(records, "turn")[0]
        self.assertEqual(turn["end_reason"], "silence")
        self.assertGreaterEqual(turn["loud_chunks"], 3)

    async def test_a_short_silence_inside_a_reply_does_not_split_it(self):
        script = self.real_script(reply_seconds=0.4, reply_gap_after=0.16,
                                  reply_gap_seconds=0.16)
        robot = await self.start(script, reply_silence_ms=400)
        await self.open_ready(robot)
        conv = robot.conv
        await robot.wait_for(lambda: len(robot.binaries()) >= 7)
        await asyncio.sleep(0.7)
        self.assertEqual(len(robot.texts("reply")), 1)
        await robot.close_conv()
        records = await self.records(conv)
        ends = self.of(records, "reply.end")
        self.assertEqual(len(ends), 1, ends)
        self.assertEqual(ends[0]["why"], "silence")
        turn = self.of(records, "turn")[0]
        self.assertEqual(turn["loud_chunks"], 5)
        self.assertGreaterEqual(turn["silent_chunks"], 2)  # the gap rode along

    async def test_silence_after_the_derived_end_is_dropped_and_counted(self):
        script = self.real_script()
        self.assertEqual(idle_chunk(script), bytes(3528))  # measured: out-of-reply is RMS 0
        robot = await self.start(script)
        await self.open_ready(robot)
        conv = robot.conv
        await robot.wait_for(lambda: len(robot.binaries()) >= 3)
        await asyncio.sleep(0.8)
        settled = len(robot.binaries())
        await asyncio.sleep(0.4)
        self.assertEqual(len(robot.binaries()), settled)
        await robot.close_conv()
        close = self.of(await self.records(conv), "close")[0]
        self.assertGreaterEqual(close["out_of_reply"]["chunks"], 3)
        self.assertEqual(close["out_of_reply"]["bytes"],
                         close["out_of_reply"]["chunks"] * 3528)

    async def test_farewell_without_agent_end_closes_with_sleep_word(self):
        script = self.real_script(reply_text=["Sure. Talk to you later."])
        robot = await self.start(script, drain_cap=3.0)
        ready = await self.open_ready(robot)
        conv = robot.conv
        await robot.wait_for(lambda: len(robot.binaries()) >= 3)
        await asyncio.sleep(0.6)  # the hangover has ended the reply by now
        self.assertEqual(robot.texts("conv.close"), [])
        await robot.playback("idle")
        closed = await self.wait_close(robot)
        self.assertEqual(closed["reason"], "sleep_word")  # not farewell_timeout
        self.assertLess(robot.texts("conv.close")[0].t - ready.t, 2.5)  # well inside the cap
        records = await self.records(conv)
        self.assertEqual(self.of(records, "reply.end")[0]["why"], "silence")
        self.assertEqual(self.of(records, "underrun"), [])

    async def test_the_drain_cap_still_closes_a_reply_that_never_goes_quiet(self):
        script = Script(turns=[Turn(user_end_at=0.05, reply_delay=0.05, reply_seconds=3.0,
                                    reply_text=["Talk to you later."], agent_end=False)])
        robot = await self.start(script, drain_cap=0.6)
        ready = await self.open_ready(robot)
        closed = await self.wait_close(robot, timeout=3.0)
        self.assertEqual(closed["reason"], "farewell_timeout")
        self.assertLess(robot.texts("conv.close")[0].t - ready.t, 1.5)

    async def test_strict_gate_reopens_after_the_derived_reply_end(self):  # R7
        script = self.real_script(user_text=["tell me something"], reply_seconds=0.4)
        robot = await self.start(script, turn_taking="strict", strict_idle_grace=5.0)
        await self.open_ready(robot, "strict")
        conv = robot.conv
        session = self.conv_sessions()[0]
        await robot.wait_for(lambda: robot.binaries())
        for _ in range(8):  # talking over the reply must not reach the model
            await robot.send_audio(LOUD)
            await asyncio.sleep(0.04)
        self.assertEqual(nonzero_frames(session), [])
        await asyncio.sleep(0.8)  # the hangover ends the reply; no agent_end ever comes
        await robot.playback("idle")
        await asyncio.sleep(0.1)  # the cooldown
        await robot.send_audio(LOUD)
        await self.model.wait_until(lambda: nonzero_frames(session))
        await robot.close_conv()
        self.assertEqual([m for m in session.sent if m.get("kind") == "agent_end"], [])
        records = await self.records(conv)
        self.assertEqual(self.of(records, "underrun"), [])
        opened = self.of(records, "gate.open")
        self.assertEqual([r["why"] for r in opened], ["idle"])  # not the backstop

    async def test_tts_lag_after_agent_start_keeps_one_reply(self):
        """Measured: the child's TTS lags its own agent_start (~240 ms in one probe, over
        640 ms in the run that lost a farewell). The hangover must not run before the reply
        has any speech, or the reply ends empty and its audio is dropped as out-of-reply."""
        script = self.real_script(reply_silence_before=1.5)
        robot = await self.start(script, reply_start_grace_ms=3000)
        ready = await self.open_ready(robot)
        conv = robot.conv
        await robot.wait_for(lambda: len(robot.binaries()) >= 3, timeout=3.0)
        self.assertGreater(robot.binaries()[0].t - ready.t, 1.3)  # the lag itself is not sent
        await asyncio.sleep(0.5)  # past the hangover
        self.assertEqual(len(robot.texts("reply")), 1)  # one reply, not an empty one and a late one
        got = [f.data for f in robot.binaries()]
        for chunk in reply_chunks(script, 0):
            self.assertIn(chunk, got)  # carrying all of the speech
        await robot.close_conv()
        records = await self.records(conv)
        ends = self.of(records, "reply.end")
        self.assertEqual(len(ends), 1, ends)
        self.assertEqual(ends[0]["why"], "silence")
        turn = self.of(records, "turn")[0]
        self.assertEqual((turn["begun_by"], turn["loud_chunks"]), ("agent_start", 3))
        close = self.of(records, "close")[0]
        self.assertEqual(close["out_of_reply"]["chunks"] * 3528,
                         close["out_of_reply"]["bytes"])  # the lag is counted, not forwarded

    async def test_agent_start_the_model_never_speaks_after_ends_as_an_empty_reply(self):
        """The start grace bounds a reply the model opens and never speaks in: it ends as an
        empty reply, and nothing -- the strict gate, the silence timer -- is left wedged."""
        script = Script(turns=[Turn(user_end_at=0.05, user_text=["hello"], reply_delay=None)],
                        events=[(0.2, {"kind": "agent_start"})], idle_audio_every=0.08)
        robot = await self.start(script, turn_taking="strict", strict_idle_grace=5.0,
                                 reply_start_grace_ms=400, silence_timeout=1.0)
        await self.open_ready(robot, "strict")
        conv = robot.conv
        session = self.conv_sessions()[0]
        # No playback report is coming: the robot was never sent anything to play.
        await asyncio.sleep(0.8)  # the empty reply has ended and the gate has reopened
        self.assertEqual(robot.binaries(), [])  # the silence it opened on was not forwarded
        await robot.send_audio(LOUD)
        await self.model.wait_until(lambda: nonzero_frames(session))
        closed = await self.wait_close(robot, timeout=2.0)  # the silence timer still runs
        self.assertEqual(closed["reason"], "silence")
        records = await self.records(conv)
        ends = self.of(records, "reply.end")
        self.assertEqual([(r["why"], r["chunks"]) for r in ends], [("no-speech", 0)])
        self.assertEqual([r["why"] for r in self.of(records, "gate.open")], ["empty"])

    async def test_the_farewell_drain_waits_for_the_late_farewell_audio(self):
        """The model's text runs seconds ahead of its TTS, so the farewell matches before a
        word of it is spoken. A reply that carried no speech must not end the drain: the
        person would hear nothing of the farewell (R2)."""
        script = self.real_script(reply_text=["Sure. Talk to you later."],
                                  reply_silence_before=2.0)
        robot = await self.start(script, drain_cap=6.0, reply_start_grace_ms=3000)
        await self.open_ready(robot)
        conv = robot.conv
        await asyncio.sleep(1.0)  # matched about 0.9 s ago; the farewell is still silent
        self.assertEqual(robot.binaries(), [])
        await robot.playback("idle")  # the robot has nothing to play, and says so
        await asyncio.sleep(0.2)
        self.assertEqual(robot.texts("conv.close"), [])  # not a word has been spoken yet
        await robot.wait_for(lambda: len(robot.binaries()) >= 3, timeout=3.0)
        got = [f.data for f in robot.binaries()]
        for chunk in reply_chunks(script, 0):
            self.assertIn(chunk, got)  # the spoken farewell reached the robot
        await asyncio.sleep(0.5)  # the hangover has ended the reply
        self.assertEqual(robot.texts("conv.close"), [])  # still waiting for playback idle
        await robot.playback("idle")
        self.assertEqual((await self.wait_close(robot))["reason"], "sleep_word")
        records = await self.records(conv)
        self.assertEqual(len(self.of(records, "reply.end")), 1)
        self.assertEqual(self.of(records, "underrun"), [])

    async def test_a_farewell_whose_lag_outruns_the_start_grace_still_plays(self):
        """When the lag outruns the start grace the reply does end empty -- and that empty
        reply must not end the drain. The farewell that arrives after it is what the person
        hears, so the close waits for it (R2)."""
        script = self.real_script(reply_text=["Sure. Talk to you later."],
                                  reply_silence_before=1.2)
        robot = await self.start(script, drain_cap=6.0, reply_start_grace_ms=400)
        await self.open_ready(robot)
        conv = robot.conv
        await asyncio.sleep(0.8)  # the empty reply has ended; not a word has been spoken
        self.assertEqual(robot.binaries(), [])
        await robot.playback("idle")  # the robot is idle, with nothing played
        await asyncio.sleep(0.15)
        self.assertEqual(robot.texts("conv.close"), [])  # an empty reply is not the farewell
        await robot.wait_for(lambda: len(robot.binaries()) >= 3, timeout=3.0)
        got = [f.data for f in robot.binaries()]
        for chunk in reply_chunks(script, 0):
            self.assertIn(chunk, got)  # the late farewell reached the robot
        await asyncio.sleep(0.5)  # the hangover has ended the reply that carried it
        await robot.playback("idle")
        self.assertEqual((await self.wait_close(robot))["reason"], "sleep_word")
        records = await self.records(conv)
        self.assertEqual([r["why"] for r in self.of(records, "reply.end")],
                         ["no-speech", "silence"])
        self.assertEqual(len(self.of(records, "drain.empty")), 1)

    async def test_the_drain_cap_closes_a_farewell_whose_audio_never_arrives(self):
        script = self.real_script(reply_seconds=0, reply_silence_before=3.0,
                                  reply_text=["Talk to you later."])
        robot = await self.start(script, drain_cap=0.8, reply_start_grace_ms=3000)
        ready = await self.open_ready(robot)
        closed = await self.wait_close(robot, timeout=2.5)
        self.assertEqual(closed["reason"], "farewell_timeout")
        self.assertLess(robot.texts("conv.close")[0].t - ready.t, 1.6)
        self.assertEqual(robot.binaries(), [])

    async def test_a_reply_that_starts_speaking_after_the_start_grace_is_forwarded_in_full(self):
        """The start grace ends a reply the model has not spoken in yet. That must cost
        nothing but a reply id: every loud chunk that arrives afterwards still reaches the
        robot, attributed to a new reply. Losing it is what left a person hearing silence."""
        script = self.real_script(reply_silence_before=1.2)
        robot = await self.start(script, reply_start_grace_ms=400, silence_timeout=10.0)
        await self.open_ready(robot)
        conv = robot.conv
        await asyncio.sleep(0.8)  # the grace has expired; not a word has been spoken
        await robot.wait_for(lambda: len(robot.binaries()) >= 3, timeout=3.0)
        got = [f.data for f in robot.binaries()]
        for chunk in reply_chunks(script, 0):
            self.assertIn(chunk, got)  # every loud chunk, after the grace gave up on it
        await asyncio.sleep(0.5)  # past the hangover
        await robot.close_conv()
        records = await self.records(conv)
        self.assertEqual([r["why"] for r in self.of(records, "reply.end")],
                         ["no-speech", "silence"])
        loud = sum(t["loud_chunks"] for t in self.of(records, "turn"))
        self.assertEqual(loud, len(reply_chunks(script, 0)))
        close = self.of(records, "close")[0]
        # Only silence is ever counted out of reply, so the byte count divides evenly.
        self.assertEqual(close["out_of_reply"]["bytes"],
                         close["out_of_reply"]["chunks"] * 3528)

    async def test_a_farewell_that_starts_speaking_late_still_plays_before_the_close(self):
        """The incident: the model's text reached the farewell phrase while no reply was
        open (the start grace had already given up on the one agent_start opened), and the
        drain closed on its farewell-start timer 0.3 s later -- before the child's TTS had
        produced a sound. The person heard nothing of the goodbye (R2). The drain must wait
        for the speech the model still owes it, bounded by the drain cap."""
        script = self.real_script(reply_silence_before=1.6, reply_seconds=0.24)
        script.events = [(0.9, {"kind": "assistant_text_delta",
                                "delta": "Sure. Talk to you later."})]
        robot = await self.start(script, reply_start_grace_ms=400, drain_cap=6.0,
                                 farewell_start_timeout=0.3)
        await self.open_ready(robot)
        conv = robot.conv
        await robot.wait_for(lambda: len(robot.binaries()) >= 3, timeout=4.0)
        got = [f.data for f in robot.binaries()]
        for chunk in reply_chunks(script, 0):
            self.assertIn(chunk, got)  # the late farewell was spoken to the person
        self.assertEqual(robot.texts("conv.close"), [])  # still playing it out
        await asyncio.sleep(0.5)  # past the hangover: the reply that carried it has ended
        await robot.playback("idle")
        self.assertEqual((await self.wait_close(robot))["reason"], "sleep_word")
        records = await self.records(conv)
        self.assertTrue(any(t["loud_chunks"] for t in self.of(records, "turn")))

    async def test_out_of_reply_counts_only_silence(self):
        """Out-of-reply audio is dropped only because it is silence the robot's speaker
        would choke on. Loud audio is never out of reply: it opens one."""
        script = Script(idle_audio_every=0.08, idle_audio_sample=6000)
        robot = await self.start(script, silence_timeout=10.0)
        await self.open_ready(robot)
        conv = robot.conv
        await robot.wait_for(lambda: len(robot.binaries()) >= 3, timeout=3.0)
        self.assertIn(idle_chunk(script), [f.data for f in robot.binaries()])
        await robot.close_conv()
        close = self.of(await self.records(conv), "close")[0]
        self.assertEqual(close["out_of_reply"], {"chunks": 0, "bytes": 0})

    async def test_agent_end_still_ends_the_reply_at_once(self):
        script = Script(turns=[Turn(user_end_at=0.05, reply_delay=0.05, reply_seconds=0.24)],
                        idle_audio_every=0.08)
        robot = await self.start(script, reply_silence_ms=2000)
        await self.open_ready(robot)
        conv = robot.conv
        await self.agent_end(0)
        await asyncio.sleep(0.25)
        records = await self.records(conv)
        ends = self.of(records, "reply.end")
        self.assertEqual(len(ends), 1, ends)
        self.assertEqual(ends[0]["why"], "agent_end")
        self.assertLess(ends[0]["duration_ms"], 1000)  # nowhere near the 2 s hangover
        await robot.close_conv()


class SlowServerBurstTests(EngineTestCase):
    """A model server slower than realtime does not stream its reply evenly: the audio
    arrives in BURSTS -- a loud stretch, a gap of silence while it computes, another loud
    stretch -- and the gaps measured live run past 800 ms. The hangover alone treated each
    gap as the end of the reply, so one spoken sentence became a dozen replies and the
    robot's player restarted a dozen times: the stuttering the person heard. So while the
    model is evidently still talking -- its `assistant_text_delta` still arriving, or its
    `stats.speech_queue` still non-zero -- the hangover does not end the reply."""

    def bursty_script(self, bursts, **turn_fields):
        """One reply sent in bursts, the way the slow server sends it: no agent_end, and
        the reply channel still streaming silence between replies."""
        fields = dict(user_end_at=0.05, reply_delay=0.05, agent_end=False,
                      reply_bursts=bursts)
        fields.update(turn_fields)
        return Script(turns=[Turn(**fields)], idle_audio_every=0.08)

    @staticmethod
    def loud(robot):
        return [f.data for f in robot.binaries() if any(f.data)]

    async def test_a_bursty_reply_is_one_reply_not_a_dozen(self):
        """The live failure: gaps of 800-900 ms inside one spoken turn. The text deltas
        that keep arriving through them are what says the model is still talking."""
        script = self.bursty_script([
            Burst(speech=0.16),
            Burst(silence=0.56, speech=0.16, text=["I am ", "still ", "talking "]),
            Burst(silence=0.64, speech=0.16, text=["and ", "still ", "talking."]),
        ])
        robot = await self.start(script, reply_silence_ms=400)  # under both gaps
        await self.open_ready(robot)
        conv = robot.conv
        await robot.wait_for(lambda: len(self.loud(robot)) >= 6, timeout=4.0)
        await asyncio.sleep(0.5)  # past the hangover, with the reply now genuinely over
        self.assertEqual(len(robot.texts("reply")), 1)  # ONE reply, not one per burst
        self.assertEqual(self.loud(robot), speech_chunks(script, 0))  # all of it, in order
        await robot.close_conv()
        records = await self.records(conv)
        ends = self.of(records, "reply.end")
        self.assertEqual(len(ends), 1, ends)
        self.assertEqual(ends[0]["why"], "silence")
        self.assertGreater(ends[0]["duration_ms"], 1300)  # it spanned both gaps
        turn = self.of(records, "turn")[0]
        self.assertEqual(turn["loud_chunks"], 6)

    async def test_text_deltas_with_no_audio_hold_the_reply_open(self):
        """Text alone keeps a reply that has spoken alive past the hangover; once both the
        text and the audio have been quiet for it, the reply ends normally."""
        script = self.bursty_script([
            Burst(speech=0.16),
            Burst(silence=1.2, text=["one ", "two ", "three ", "four"]),
        ])
        robot = await self.start(script, reply_silence_ms=400)
        await self.open_ready(robot)
        conv = robot.conv
        await robot.wait_for(lambda: len(self.loud(robot)) >= 2, timeout=3.0)
        await asyncio.sleep(1.8)
        self.assertEqual(len(robot.texts("reply")), 1)
        await robot.close_conv()
        records = await self.records(conv)
        ends = self.of(records, "reply.end")
        self.assertEqual(len(ends), 1, ends)
        self.assertEqual(ends[0]["why"], "silence")  # it does end, once the text stops
        self.assertGreater(ends[0]["duration_ms"], 1200)  # long past the 400 ms hangover
        self.assertEqual(self.of(records, "turn")[0]["loud_chunks"], 2)

    async def test_a_non_zero_speech_queue_holds_the_reply_open(self):
        """`stats.speech_queue` is TTS still queued for playback: more speech is coming,
        whatever the reply channel sounds like right now. Samples arrive every few
        seconds, so the newest one is used and a missing one blocks nothing."""
        script = Script(turns=[Turn(user_end_at=0.05, reply_delay=0.05, reply_seconds=0.16,
                                    agent_end=False)],
                        idle_audio_every=0.08,
                        events=[(t, {"kind": "stats", "input_backlog_frames": 0,
                                     "speech_queue": queue})
                                for t, queue in [(0.2, 6), (0.5, 4), (0.8, 2), (1.1, 0)]])
        robot = await self.start(script)  # hangover 250 ms
        await self.open_ready(robot)
        conv = robot.conv
        await robot.wait_for(lambda: len(self.loud(robot)) >= 2, timeout=3.0)
        await asyncio.sleep(1.6)
        self.assertEqual(len(robot.texts("reply")), 1)
        await robot.close_conv()
        records = await self.records(conv)
        ends = self.of(records, "reply.end")
        self.assertEqual(len(ends), 1, ends)
        self.assertEqual(ends[0]["why"], "silence")
        self.assertGreater(ends[0]["duration_ms"], 900)  # held while the queue was draining
        self.assertLess(ends[0]["duration_ms"], 2000)  # and released once it hit zero

    async def test_a_zero_speech_queue_does_not_delay_the_end(self):
        """The mirror of the test above, and of the no-stats case the other tests in this
        file run: a queue of zero leaves the hangover in charge."""
        script = Script(turns=[Turn(user_end_at=0.05, reply_delay=0.05, reply_seconds=0.16,
                                    agent_end=False)],
                        idle_audio_every=0.08,
                        events=[(t, {"kind": "stats", "input_backlog_frames": 0,
                                     "speech_queue": 0}) for t in (0.2, 0.5, 0.8)])
        robot = await self.start(script)  # hangover 250 ms
        await self.open_ready(robot)
        conv = robot.conv
        await robot.wait_for(lambda: len(self.loud(robot)) >= 2, timeout=3.0)
        await asyncio.sleep(0.6)
        await robot.close_conv()
        records = await self.records(conv)
        ends = self.of(records, "reply.end")
        self.assertEqual(len(ends), 1, ends)
        self.assertEqual(ends[0]["why"], "silence")
        self.assertLess(ends[0]["duration_ms"], 700)  # the hangover, not a held reply

    async def test_a_bursty_farewell_still_drains_and_closes_on_sleep_word(self):
        """The whole point of holding the reply open is that the person hears one
        continuous goodbye; the drain must still close on it once it has played."""
        script = self.bursty_script([
            Burst(speech=0.16),
            Burst(silence=0.4, speech=0.16, text=["Sure. ", "Talk ", "to you later."]),
        ])
        robot = await self.start(script, drain_cap=3.0)
        ready = await self.open_ready(robot)
        conv = robot.conv
        await robot.wait_for(lambda: len(self.loud(robot)) >= 4, timeout=4.0)
        await asyncio.sleep(0.5)
        self.assertEqual(len(robot.texts("reply")), 1)
        self.assertEqual(self.loud(robot), speech_chunks(script, 0))
        self.assertEqual(robot.texts("conv.close"), [])  # not before the goodbye played
        await robot.playback("idle")
        closed = await self.wait_close(robot)
        self.assertEqual(closed["reason"], "sleep_word")  # not farewell_timeout
        self.assertLess(robot.texts("conv.close")[0].t - ready.t, 3.0)
        records = await self.records(conv)
        self.assertEqual(len(self.of(records, "reply.end")), 1)
        self.assertEqual(self.of(records, "underrun"), [])


class ReplyCushionTests(EngineTestCase):
    """What the robot is holding while a reply plays (round 10).

    Measured live: the model's speech generation is capped at about 1.0x realtime by its
    duplex timeline (one 80 ms audio frame per 80 ms step) and comes out at 0.88-0.97x, so
    reply audio arrives slightly SLOWER than the robot plays it and the speaker drains --
    `reply N played: underruns=2..3` on every reply, heard as stuttering. The cure is a
    cushion, and ReplyPacer's burst cap is what decides how big a cushion the robot is
    ALLOWED to hold: raising the robot's own prebuffer did nothing while the relay refused
    to send more than half a second ahead of its estimate of the speaker.
    """

    HEAD = 18  # chunks already in the server's TTS queue, flushed in a lump: 1.44 s
    TAIL = 25  # chunks it then generates, at 0.95x realtime: 2.0 s of audio in 2.1 s

    def cushioned_script(self):
        chunk = 0.080
        return Script(turns=[Turn(user_end_at=0.05, reply_delay=0.05, agent_end=False,
                                  reply_bursts=[Burst(speech=self.HEAD * chunk, pace=0.0),
                                                Burst(speech=self.TAIL * chunk,
                                                      pace=1 / 0.95)])])

    @staticmethod
    def cushion(robot):
        """Seconds of reply audio the robot is holding as each chunk lands, taking it to
        start playing at the first chunk and to play on without a gap at 1.0x. Below zero
        is an underrun: the speaker wanted a sample that had not arrived."""
        chunks = [f for f in robot.binaries() if any(f.data)]
        t0 = chunks[0].t
        return [(i + 1) * 0.080 - (f.t - t0) for i, f in enumerate(chunks)]

    async def played(self, robot, timeout):
        await robot.wait_for(lambda: len([f for f in robot.binaries() if any(f.data)])
                             == self.HEAD + self.TAIL, timeout=timeout)
        return self.cushion(robot)

    async def test_the_default_cap_leaves_the_robot_a_seconds_long_cushion(self):
        robot = await self.start(self.cushioned_script())
        await self.open_ready(robot)
        cushion = await self.played(robot, 8)
        self.assertGreater(max(cushion), 1.2)  # the head start is banked, not clipped
        # and it is still there at the end: the 0.95x tail eats it at 50 ms per second
        self.assertGreater(min(cushion[self.HEAD - 1:]), 1.0)
        self.assertGreater(cushion[-1], 1.1)
        self.assertLess(max(cushion) - cushion[-1], 0.5)  # steady, not runaway
        self.assertGreater(min(cushion), 0.0)  # never starved

    async def test_the_old_half_second_cap_pins_the_cushion_at_the_cap(self):
        """The same reply through the cap this relay used to ship: the robot cannot hold
        more than the cap however early the audio arrived. This is why raising the robot's
        prebuffer changed nothing."""
        robot = await self.start(self.cushioned_script(), pacing=Pacing(burst_seconds=0.5))
        await self.open_ready(robot)
        cushion = await self.played(robot, 10)
        self.assertLess(max(cushion), 0.6)  # the cap, not the audio, is the constraint
        self.assertGreater(max(cushion), 0.4)


class BurstSecondsOptionTests(unittest.TestCase):
    """--burst-seconds / RELAY_BURST_SECONDS, and that it reaches the lane's pacer."""

    def test_default_flag_and_environment(self):
        self.assertEqual(main.parse_args([]).burst_seconds, 2.0)
        self.assertEqual(main.parse_args(["--burst-seconds", "0.5"]).burst_seconds, 0.5)
        with unittest.mock.patch.dict(os.environ, {"RELAY_BURST_SECONDS": "1.25"}):
            self.assertEqual(main.parse_args([]).burst_seconds, 1.25)
            self.assertEqual(main.parse_args(["--burst-seconds", "3"]).burst_seconds, 3.0)

    def test_it_reaches_the_lane_servers_pacing(self):
        recorded = {}

        class RecordingLaneServer:
            def __init__(self, handler, host, port, **kwargs):
                recorded.update(kwargs)
                self.host, self.port = host, port

            async def start(self):
                raise KeyboardInterrupt  # nothing past the lane needs to run

            async def stop(self):
                pass

        log_dir = Path(tempfile.mkdtemp(prefix="relay-burst-"))
        persona = log_dir / "persona.txt"
        persona.write_text(PERSONA + "\n")
        args = main.parse_args(["--lane-host", "127.0.0.1", "--lane-port", "0",
                                "--no-prewarm", "--burst-seconds", "1.5",
                                "--persona", str(persona), "--log-dir", str(log_dir)])
        with unittest.mock.patch.object(main, "LaneServer", RecordingLaneServer):
            with contextlib.suppress(KeyboardInterrupt):
                asyncio.run(main.run(args))
        self.assertEqual(recorded["pacing"], Pacing(burst_seconds=1.5))


class SlowServerDefaultsTests(unittest.TestCase):
    """Defaults measured against the slow server: the bursts' gaps ran past 800 ms, and a
    farewell it is still speaking can outlive an 8 s drain cap."""

    def test_the_hangover_outlasts_a_bursts_gap_and_the_drain_cap_a_slow_farewell(self):
        self.assertEqual(Config().reply_silence_ms, 1500.0)
        self.assertEqual(Config().drain_cap, 12.0)


class FarewellPhraseTests(EngineTestCase):
    """The conversation ends when the model's own reply text reaches the farewell phrase;
    live mode has no user transcript, so nothing else can end it (KTD2 stays dormant)."""

    async def test_phrase_split_across_deltas_drains_the_farewell_then_closes(self):
        script = Script(turns=[Turn(user_end_at=0.1, reply_delay=0.05, reply_seconds=0.24,
                                    reply_text=["Okay. Talk to ", "you later."])])
        robot = await self.start(script)
        await self.open_ready(robot)
        conv = robot.conv
        await robot.wait_for(lambda: len(robot.binaries()) >= 3)  # the farewell, forwarded
        await asyncio.sleep(0.2)  # agent_end has arrived; the robot has not reported idle
        self.assertEqual(robot.texts("conv.close"), [])
        await robot.playback("idle")
        self.assertEqual((await self.wait_close(robot))["reason"], "sleep_word")
        records = await self.records(conv)
        match = self.of(records, "sleepword")[0]
        self.assertEqual((match["kind"], match["source"]), ("match", "assistant_text"))
        self.assertEqual(match["words"], ["talk", "to", "you", "later"])
        self.assertEqual(len(self.of(records, "drain")), 1)
        self.assertEqual(records[-1]["reason"], "sleep_word")

    async def test_the_phrase_in_the_persons_transcript_does_not_match(self):
        script = Script(turns=[Turn(user_end_at=0.1, user_text=["talk to ", "you later"],
                                    reply_delay=0.05, reply_seconds=0.16,
                                    reply_text=["Sure thing."])])
        robot = await self.start(script)
        await self.open_ready(robot)
        conv = robot.conv
        await asyncio.sleep(0.6)
        self.assertEqual(robot.texts("conv.close"), [])
        await robot.close_conv()
        self.assertEqual(self.of(await self.records(conv), "sleepword"), [])

    async def test_assistant_text_without_the_phrase_does_not_match(self):
        script = Script(turns=[Turn(user_end_at=0.1, reply_delay=0.05, reply_seconds=0.16,
                                    reply_text=["I will talk to you ", "tomorrow, then."])])
        robot = await self.start(script)
        await self.open_ready(robot)
        conv = robot.conv
        await asyncio.sleep(0.6)
        self.assertEqual(robot.texts("conv.close"), [])
        await robot.close_conv()
        records = await self.records(conv)
        self.assertEqual(self.of(records, "sleepword"), [])
        self.assertEqual(self.of(records, "drain"), [])

    async def test_the_drain_cap_closes_when_the_robot_never_reports_idle(self):
        script = Script(turns=[Turn(user_end_at=0.1, reply_delay=0.05, reply_seconds=0.24,
                                    reply_text=["Talk to you later."])])
        robot = await self.start(script, drain_cap=0.5)
        ready = await self.open_ready(robot)
        closed = await self.wait_close(robot)  # no playback idle is ever sent
        self.assertEqual(closed["reason"], "farewell_timeout")
        self.assertLess(robot.texts("conv.close")[0].t - ready.t, 1.3)

    async def test_the_phrase_is_configurable(self):
        script = Script(turns=[Turn(user_end_at=0.1, reply_delay=0.05, reply_seconds=0.16,
                                    reply_text=["Talk to you later, ", "see you soon."])])
        robot = await self.start(script, farewell_phrase="see you soon")
        await self.open_ready(robot)
        conv = robot.conv
        await robot.wait_for(lambda: len(robot.binaries()) >= 2)
        await asyncio.sleep(0.15)
        await robot.playback("idle")
        self.assertEqual((await self.wait_close(robot))["reason"], "sleep_word")
        match = self.of(await self.records(conv), "sleepword")[0]
        self.assertEqual(match["words"], ["see", "you", "soon"])


class SilenceTests(EngineTestCase):
    async def test_silence_after_the_last_turn_closes(self):  # AE4
        script = Script(turns=[Turn(user_end_at=0.1, user_text=["hi"], reply_delay=0.05,
                                    reply_seconds=0.24)])
        robot = await self.start(script, silence_timeout=0.3)
        ready = await self.open_ready(robot)
        self.assertEqual((await self.wait_close(robot))["reason"], "silence")
        self.assertGreater(robot.texts("conv.close")[0].t - ready.t, 0.6)

    async def test_long_reply_is_never_cut_by_the_silence_timer(self):  # AE4
        script = Script(turns=[Turn(user_end_at=0.05, reply_delay=0.05, reply_seconds=0.8)])
        robot = await self.start(script, silence_timeout=0.3)
        ready = await self.open_ready(robot)
        self.assertEqual((await self.wait_close(robot))["reason"], "silence")
        self.assertGreater(robot.texts("conv.close")[0].t - ready.t, 1.1)
        self.assertGreaterEqual(len(robot.binaries()), 10)

    async def test_wake_followed_by_nothing_closes_with_silence(self):  # AE4
        robot = await self.start(Script(), silence_timeout=0.3)
        ready = await self.open_ready(robot)
        self.assertEqual((await self.wait_close(robot))["reason"], "silence")
        elapsed = robot.texts("conv.close")[0].t - ready.t
        self.assertGreater(elapsed, 0.25)
        self.assertLess(elapsed, 0.6)


class TurnTakingTests(EngineTestCase):
    REPLY = Turn(user_end_at=0.05, user_text=["tell me something"], reply_delay=0.05,
                 reply_seconds=0.64)

    async def talk(self, robot, frames, spacing=0.04):
        for _ in range(frames):
            await robot.send_audio(LOUD)
            await asyncio.sleep(spacing)

    async def test_strict_mode_zeroes_uplink_during_reply_and_ignores_goodbye(self):  # AE6
        goodbye = Turn(user_start_at=0.3, user_end_at=0.4, user_text=["goodbye ", "miko"],
                       reply_delay=None)
        script = Script(turns=[self.REPLY, goodbye], flush_rms=1000)
        robot = await self.start(script)
        await self.open_ready(robot, "strict")
        conv = robot.conv
        await robot.wait_for(lambda: robot.binaries())
        await self.talk(robot, 16)  # over the rest of the reply
        await robot.wait_for(lambda: len(robot.binaries()) >= 8)
        await asyncio.sleep(0.1)
        session = self.conv_sessions()[0]
        self.assertEqual(nonzero_frames(session), [])
        self.assertEqual(session.flushes, 0)
        self.assertEqual(robot.texts("audio.flush"), [])
        self.assertEqual(robot.texts("conv.close"), [])
        await robot.playback("idle")  # after agent_end: the gate opens after the cooldown
        await asyncio.sleep(0.1)
        await robot.send_audio(LOUD)
        await self.model.wait_until(lambda: nonzero_frames(session))
        await robot.close_conv()
        records = await self.records(conv)
        self.assertEqual(self.of(records, "drain"), [])
        self.assertTrue(self.of(records, "gate.open"))

    async def test_strict_idle_before_agent_end_is_an_underrun_and_gate_stays_closed(self):
        robot = await self.start(Script(turns=[self.REPLY]))
        await self.open_ready(robot, "strict")
        conv = robot.conv
        await robot.wait_for(lambda: robot.binaries())
        await robot.playback("idle")  # before agent_end
        await self.talk(robot, 4)
        session = self.conv_sessions()[0]
        self.assertEqual(nonzero_frames(session), [])
        await robot.wait_for(lambda: len(robot.binaries()) >= 8)
        await asyncio.sleep(0.15)
        await robot.send_audio(LOUD)
        await asyncio.sleep(0.1)
        self.assertEqual(nonzero_frames(session), [])  # agent_end came, but no idle after it
        await robot.playback("idle")
        await asyncio.sleep(0.1)
        await robot.send_audio(LOUD)
        await self.model.wait_until(lambda: nonzero_frames(session))
        await robot.close_conv()
        self.assertEqual(len(self.of(await self.records(conv), "underrun")), 1)

    async def test_consecutive_conversations_gate_by_their_own_turn_taking(self):  # R7
        robot = await self.start(Script(turns=[self.REPLY], flush_rms=1000))
        await self.open_ready(robot, "strict")
        await robot.wait_for(lambda: robot.binaries())
        await self.talk(robot, 3)
        await asyncio.sleep(0.1)
        first = self.conv_sessions()[0]
        self.assertEqual(first.flushes, 0)
        await robot.close_conv()
        mark = len(robot.frames)
        await self.open_ready(robot, "interruptible")
        await robot.wait_for(lambda: any(f.kind == "binary" for f in robot.frames[mark:]))
        await self.talk(robot, 3)
        await robot.wait_text("audio.flush", after=mark)
        self.assertEqual(self.conv_sessions()[1].flushes, 1)

    async def test_flush_stops_the_reply_at_once(self):  # KTD5, AE5
        script = Script(turns=[Turn(user_end_at=0.05, reply_delay=0.05, reply_seconds=1.6)],
                        flush_rms=1000)
        robot = await self.start(script)
        await self.open_ready(robot)
        await robot.wait_for(lambda: robot.binaries())
        reply = robot.texts("reply")[0].data["id"]
        sent = asyncio.get_running_loop().time()
        await robot.send_audio(LOUD)
        flush = await robot.wait_text("audio.flush")
        self.assertEqual(flush.data["reply"], reply)
        self.assertLess(flush.t - sent, 0.15)
        index = robot.frames.index(flush)
        await asyncio.sleep(0.3)
        self.assertEqual([f for f in robot.frames[index:] if f.kind == "binary"], [])
        self.assertLess(len(robot.binaries()), 20)


class SessionTests(EngineTestCase):
    async def test_every_conversation_is_a_fresh_model_session(self):  # AE7, R9
        script = Script(turns=[Turn(user_end_at=0.05, user_text=["my name is Ben"],
                                    reply_delay=0.05, reply_seconds=0.16)])
        robot = await self.start(script)
        await self.open_ready(robot)
        await robot.wait_for(lambda: len(robot.binaries()) >= 2)
        await robot.close_conv()
        await self.open_ready(robot)
        sessions = self.conv_sessions()
        self.assertEqual(len(sessions), 2)
        for session in sessions:
            texts = session.texts()
            self.assertEqual(texts[0], {"type": "reset"})
            self.assertEqual(texts[1]["type"], "system")
            self.assertEqual(texts[1]["text"], PERSONA)
            system_at = session.received[1].t
            self.assertTrue(all(f.t >= system_at for f in session.binaries()))
        await self.model.wait_until(lambda: sessions[0].ws.close_code is not None)

    async def test_second_conv_open_replaces_the_first(self):
        robot = await self.start(Script())
        await self.open_ready(robot)
        first = robot.conv
        await self.open_ready(robot)
        await self.model.wait_until(lambda: len(self.conv_sessions()) == 2)
        await self.model.wait_until(lambda: self.conv_sessions()[0].ws.close_code is not None)
        self.assertEqual(robot.texts("conv.close"), [])
        self.assertEqual((await self.records(first))[-1]["reason"], "robot_request")

    async def test_link_loss_closes_the_model_connection(self):  # AE10
        robot = await self.start(Script(), lane_ping=0.05)
        await self.open_ready(robot)
        conv = robot.conv
        session = self.conv_sessions()[0]
        robot.go_silent()
        await self.model.wait_until(lambda: session.ws.close_code is not None, timeout=2.0)
        self.assertEqual((await self.records(conv))[-1]["reason"], "link_lost")

    async def test_backlog_before_ready_is_forwarded_first_in_order(self):
        model = PortedFakeServer(Script())
        await self.stack.enter_async_context(model)
        robot = await self.start(model=model, probe_interval=30)
        await self.probes_done(model)
        model.delay_next = 0.2
        mark = len(robot.frames)
        await robot.open_conv()
        # Loud enough to be distinct AND to pass the uplink noise gate unaltered, so what
        # arrives proves the order.
        frames = [(3000 + i).to_bytes(2, "little", signed=True) * 640 for i in range(5)]
        for frame in frames:
            await robot.send_audio(frame)
        await robot.wait_text("conv.ready", after=mark)
        session = await model.wait_until(lambda: self.conv_sessions() and self.conv_sessions()[0])
        await model.wait_until(lambda: len(session.binaries()) >= 5)
        self.assertEqual([f.data for f in session.binaries()[:5]], frames)


class ModelHealthTests(EngineTestCase):
    async def test_model_dropping_mid_conversation_is_model_error_and_link_stays(self):
        robot = await self.start(Script(close_at=0.2))
        await self.open_ready(robot)
        mark = len(robot.frames)
        self.assertEqual((await self.wait_close(robot))["reason"], "model_error")
        status = await robot.wait_text("status", after=mark)
        self.assertIs(status.data["model_ok"], True)
        self.assertIsNone(robot.ws.close_code)
        self.model.script = Script()  # the next conversation works
        await self.open_ready(robot)

    async def test_model_error_and_fatal_warning_end_the_conversation(self):
        for event, closes in (({"type": "error", "message": "child died"}, True),
                              ({"type": "warning", "code": "fatal", "message": "x"}, True),
                              ({"type": "warning", "code": "slow", "message": "x"}, False)):
            with self.subTest(event=event):
                await self.stack.aclose()
                self.stack = contextlib.AsyncExitStack()
                robot = await self.start(Script(events=[(0.05, event)]))
                mark = len(robot.frames)
                await self.open_ready(robot)
                if closes:
                    self.assertEqual((await self.wait_close(robot, mark))["reason"], "model_error")
                else:
                    await asyncio.sleep(0.3)
                    self.assertEqual(robot.texts("conv.close"), [])

    async def test_model_port_closed_and_reopened_while_idle(self):
        model = PortedFakeServer(Script())
        await model.__aenter__()
        port = model.port
        robot = await self.start(model=model)
        self.assertIs(self.welcome["model_ok"], True)
        await model.__aexit__(None, None, None)
        down = await robot.wait_text("status", timeout=1.0)
        self.assertIs(down.data["model_ok"], False)
        again = PortedFakeServer(Script(), port=port)
        await self.stack.enter_async_context(again)
        up = await robot.wait_text("status", timeout=1.0, after=robot.frames.index(down) + 1)
        self.assertIs(up.data["model_ok"], True)

    async def test_failed_open_is_followed_by_a_fresh_status(self):
        model = PortedFakeServer(Script())
        await self.stack.enter_async_context(model)
        robot = await self.start(model=model, probe_interval=30, ready_timeout=0.3)
        await self.probes_done(model)
        self.assertEqual(robot.texts("status"), [])  # reachability never changed
        model.delay_next = 1.0  # the conversation's handshake stalls past conv.ready's limit
        mark = len(robot.frames)
        await robot.open_conv()
        self.assertEqual((await self.wait_close(robot, mark))["reason"], "model_error")
        status = await robot.wait_text("status", after=mark)
        self.assertIs(status.data["model_ok"], True)
        self.assertEqual(robot.texts("conv.ready"), [])

    async def test_robot_close_while_connecting_is_followed_by_a_fresh_status(self):
        model = PortedFakeServer(Script())
        await self.stack.enter_async_context(model)
        robot = await self.start(model=model, probe_interval=30, ready_timeout=2.0)
        await self.probes_done(model)
        model.delay_next = 0.5
        mark = len(robot.frames)
        await robot.open_conv()
        await asyncio.sleep(0.05)
        await robot.close_conv()
        status = await robot.wait_text("status", after=mark)
        self.assertIs(status.data["model_ok"], True)
        self.assertEqual(robot.texts("conv.ready"), [])

    async def test_conv_open_cancels_an_in_flight_probe(self):
        robot = await self.start(Script(status_delay=0.3), probe_interval=0.05,
                                 probe_timeout=1.0)

        def probe_in_flight():
            return [s for s in self.model.sessions if s.ws.close_code is None
                    and any(d.get("type") == "status" for d in s.texts())]
        probes = await self.model.wait_until(probe_in_flight)
        mark = len(robot.frames)
        opened = asyncio.get_running_loop().time()
        ready = await self.open_ready(robot)
        self.assertLess(ready.t - opened, 0.25)  # did not wait out the probe's 0.3 s
        await self.model.wait_until(lambda: probes[0].ws.close_code is not None)
        session = self.conv_sessions()[0]
        await asyncio.sleep(0.6)
        self.assertEqual(robot.texts("conv.close"), [])
        self.assertIsNone(session.ws.close_code)  # never evicted
        self.assertIs(self.model._active, session)
        self.assertEqual([f for f in robot.frames[mark:] if f.kind == "text"
                          and f.data["type"] == "status"], [])


class LogTests(EngineTestCase):
    async def test_turn_record_carries_latency_fields(self):
        script = Script(turns=[Turn(user_end_at=0.1, user_text=["hello"], reply_delay=0.1,
                                    reply_seconds=0.24)],
                        stats_every=0.05, stats_backlog=0.12)
        robot = await self.start(script, lane_ping=0.05)
        await self.open_ready(robot)
        conv = robot.conv
        await robot.wait_for(lambda: robot.binaries())
        await robot.playback("playing", buffered_ms=240, first_chunk_to_play_ms=42)
        await robot.wait_for(lambda: len(robot.binaries()) >= 3)
        await asyncio.sleep(0.1)
        await robot.playback("idle")
        await robot.close_conv()
        records = await self.records(conv)
        turn = self.of(records, "turn")[0]
        self.assertTrue(50 <= turn["user_end_to_first_chunk_ms"] < 400, turn)
        self.assertEqual(turn["robot_first_chunk_to_play_ms"], 42)
        self.assertIsInstance(turn["half_ping_rtt_ms"], (int, float))
        self.assertEqual(turn["stats"]["input_backlog"], 0.12)
        self.assertEqual(turn["reply_chunks"], 3)
        for record in records:
            self.assertNotIn("pcm", record)
        model_events = {r["msg"]["kind"] for r in self.of(records, "model")}
        self.assertTrue({"user_start", "user_end", "user_text", "agent_start",
                         "agent_end", "stats"} <= model_events)

    async def test_conv_ready_waits_for_the_system_ack_and_logs_its_cost(self):
        # The persona is read in before the session is usable (about 80 ms a word on the
        # real child): conv.ready must wait for the `system` ack, not just the socket.
        robot = await self.start(Script(system_ack_delay=0.5), ready_timeout=4.0)
        mark = len(robot.frames)
        conv = await robot.open_conv()
        await asyncio.sleep(0.25)
        self.assertEqual([f for f in robot.texts("conv.ready") if f.t], [],
                         "conv.ready arrived before the model acknowledged the persona")
        await robot.wait_text("conv.ready", after=mark, timeout=3.0)
        await robot.close_conv()
        records = await self.records(conv)
        ready = [r for r in self.of(records, "relay")
                 if (r.get("msg") or {}).get("type") == "conv.ready"]
        self.assertEqual(len(ready), 1, records)
        self.assertGreaterEqual(ready[0]["system_ms"], 450)

    async def test_slow_log_disk_does_not_delay_reply_audio(self):
        def slow_disk(batch, logs=None):
            time.sleep(0.3)
            ConversationLogs._write_batch(self.logs, batch)
        script = Script(turns=[Turn(user_end_at=0.05, reply_delay=0.05, reply_seconds=1.2)])
        robot = await self.start(script, logs_writer=slow_disk)
        await self.open_ready(robot)
        await robot.wait_for(lambda: len(robot.binaries()) >= 15, timeout=3.0)
        times = [f.t for f in robot.binaries()]
        self.assertLess(max(b - a for a, b in zip(times, times[1:])), 0.2)


class MainTests(unittest.IsolatedAsyncioTestCase):
    async def test_relay_main_serves_a_conversation_and_its_log(self):
        log_dir = Path(tempfile.mkdtemp(prefix="relay-main-"))
        persona = log_dir / "persona.txt"
        persona.write_text(PERSONA + "\n")
        async with FakeModelServer(Script()) as model:
            proc = await asyncio.create_subprocess_exec(
                sys.executable, "-m", "relay.main", "--model-port", str(model.port),
                "--model-host", "127.0.0.1", "--lane-host", "127.0.0.1", "--lane-port", "0",
                "--http-port", "0", "--persona", str(persona), "--log-dir", str(log_dir),
                cwd=RELAY_ROOT, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
            try:
                line = (await asyncio.wait_for(proc.stdout.readline(), 10)).decode()
                lane_port, http_port = map(int, re.search(
                    r"ws://127.0.0.1:(\d+)/ logs http://127.0.0.1:(\d+)/", line).groups())
                async with FakeRobot(lane_port) as robot:
                    self.assertIs((await robot.connect())["model_ok"], True)
                    await robot.open_conv()
                    await robot.wait_text("conv.ready")
                    await robot.close_conv()
                    system = [d for s in model.sessions for d in s.texts()
                              if d.get("type") == "system"]
                    self.assertEqual(system[0]["text"], PERSONA)
                async with asyncio.timeout(3):
                    while True:
                        await asyncio.sleep(0.2)
                        reader, writer = await asyncio.open_connection("127.0.0.1", http_port)
                        writer.write(b"GET /conversations HTTP/1.1\r\nHost: x\r\n\r\n")
                        body = (await reader.read()).partition(b"\r\n\r\n")[2]
                        writer.close()
                        items = json.loads(body)["conversations"]
                        if items and items[0]["close_reason"]:
                            break
                self.assertEqual(items[0]["close_reason"], "robot_request")
            finally:
                if proc.returncode is None:
                    proc.send_signal(signal.SIGTERM)
                code = await asyncio.wait_for(proc.wait(), 10)
                err = (await proc.stderr.read()).decode()
            self.assertEqual(code, 0, err)
            self.assertNotIn("Traceback", err)


class UplinkGateTests(unittest.TestCase):
    """The uplink noise gate on its own (R5). The robot's microphone floor sits at about
    RMS 300-470, above the model VAD's own opening threshold, so ungated room noise can
    latch the model "voiced" for good and `user_end` never fires. The gate replaces those
    frames with true digital silence without touching the cadence."""

    def gate(self, threshold=700, hang_ms=0.0):
        return UplinkGate(threshold, hang_ms)

    def test_room_noise_becomes_exact_zeros_and_is_counted(self):
        out, muted = self.gate().process(NOISE)
        self.assertEqual(out, bytes(len(NOISE)))
        self.assertEqual(muted, 4)  # four 20 ms frames in an 80 ms chunk

    def test_speech_is_forwarded_byte_for_byte(self):
        out, muted = self.gate().process(SPEECH)
        self.assertEqual(out, SPEECH)
        self.assertEqual(muted, 0)

    def test_a_disabled_gate_alters_nothing(self):
        gate = self.gate(threshold=0)
        for pcm in (NOISE, SPEECH, bytes(len(NOISE))):
            out, muted = gate.process(pcm)
            self.assertIs(out, pcm)
            self.assertEqual(muted, 0)

    def test_the_hangover_keeps_a_quiet_tail_then_expires(self):
        gate = self.gate(hang_ms=200)
        gate.process(SPEECH)  # arms 200 ms == ten 20 ms frames of hangover
        for _ in range(2):  # 80 ms + 80 ms of tail, all of it inside the hangover
            self.assertEqual(gate.process(NOISE), (NOISE, 0))
        out, muted = gate.process(NOISE)  # the hangover runs out mid-chunk
        self.assertEqual(out[: 2 * GATE_FRAME], NOISE[: 2 * GATE_FRAME])
        self.assertEqual(out[2 * GATE_FRAME:], bytes(len(NOISE) - 2 * GATE_FRAME))
        self.assertEqual(muted, 2)
        self.assertEqual(gate.process(NOISE), (bytes(len(NOISE)), 4))
        self.assertEqual(gate.process(SPEECH), (SPEECH, 0))  # and it re-arms on the next word

    def test_only_the_loud_part_of_a_mixed_chunk_survives(self):
        out, muted = self.gate().process(SPEECH[:GATE_FRAME] + NOISE[GATE_FRAME:])
        self.assertEqual(out, SPEECH[:GATE_FRAME] + bytes(len(NOISE) - GATE_FRAME))
        self.assertEqual(muted, 3)

    def test_a_chunk_shorter_than_one_decision_frame_is_gated_whole(self):
        out, muted = self.gate().process(NOISE[:100])  # 50 samples, a ragged frame
        self.assertEqual(out, bytes(100))
        self.assertEqual(muted, 1)

    def test_sizes_and_order_never_change_and_no_chunk_is_dropped(self):
        gate = self.gate(hang_ms=200)
        stream = [NOISE, SPEECH, SPEECH, NOISE, NOISE, NOISE, NOISE, NOISE, SPEECH, NOISE]
        out = [gate.process(pcm)[0] for pcm in stream]
        self.assertEqual(len(out), len(stream))
        self.assertEqual([len(p) for p in out], [len(p) for p in stream])
        self.assertEqual([out[1], out[2], out[8]], [SPEECH, SPEECH, SPEECH])
        self.assertEqual([out[0], out[7]], [bytes(len(NOISE))] * 2)

    def test_the_hangover_does_not_survive_a_reset(self):
        gate = self.gate(hang_ms=200)
        gate.process(SPEECH)
        gate.reset()
        self.assertEqual(gate.process(NOISE), (bytes(len(NOISE)), 4))


class UplinkNoiseGateEngineTests(EngineTestCase):
    """The gate in the engine (R5): the model never sees the robot's room noise, the count
    reaches the close record, and the strict-turn-taking zero-fill is untouched."""

    async def conversing(self, **overrides):
        robot = await self.start(Script(), **overrides)
        await self.open_ready(robot)
        return robot

    async def test_room_noise_reaches_the_model_as_silence_and_is_counted(self):
        robot = await self.conversing()
        conv = robot.conv
        for _ in range(4):
            await robot.send_audio(NOISE)
            await asyncio.sleep(0.02)
        session = self.conv_sessions()[0]
        await self.model.wait_until(lambda: len(session.binaries()) >= 4)
        self.assertEqual(nonzero_frames(session), [])
        self.assertTrue(all(len(f.data) == len(NOISE) for f in session.binaries()))
        await robot.close_conv()
        uplink = self.of(await self.records(conv), "close")[0]["uplink"]
        self.assertEqual((uplink["frames"], uplink["gated"]), (4, 4))
        self.assertEqual(uplink["gated_frames"], 16)

    async def test_speech_is_forwarded_byte_for_byte(self):
        robot = await self.conversing()
        conv = robot.conv
        await robot.send_audio(SPEECH)
        session = self.conv_sessions()[0]
        await self.model.wait_until(lambda: nonzero_frames(session))
        self.assertEqual(nonzero_frames(session)[0].data, SPEECH)
        await robot.close_conv()
        self.assertEqual(self.of(await self.records(conv), "close")[0]["uplink"]["gated"], 0)

    async def test_the_gate_off_forwards_room_noise_untouched(self):
        robot = await self.conversing(uplink_gate_rms=0)
        conv = robot.conv
        await robot.send_audio(NOISE)
        session = self.conv_sessions()[0]
        await self.model.wait_until(lambda: nonzero_frames(session))
        self.assertEqual(nonzero_frames(session)[0].data, NOISE)
        await robot.close_conv()
        self.assertEqual(self.of(await self.records(conv), "close")[0]["uplink"]["gated"], 0)

    async def test_the_hangover_carries_a_quiet_tail_through_to_the_model(self):
        robot = await self.conversing(uplink_gate_hang_ms=200)
        await robot.send_audio(SPEECH)
        await robot.send_audio(NOISE)  # 80 ms of tail, inside the hangover
        session = self.conv_sessions()[0]
        await self.model.wait_until(lambda: len(nonzero_frames(session)) >= 2)
        self.assertEqual([f.data for f in nonzero_frames(session)[:2]], [SPEECH, NOISE])

    async def test_the_backlog_is_gated_too_so_the_vad_never_sees_the_floor(self):
        model = PortedFakeServer(Script())
        await self.stack.enter_async_context(model)
        robot = await self.start(model=model, probe_interval=30)
        await self.probes_done(model)
        model.delay_next = 0.2
        mark = len(robot.frames)
        conv = await robot.open_conv()
        for _ in range(3):
            await robot.send_audio(NOISE)  # buffered while the model connection is opening
        await robot.wait_text("conv.ready", after=mark)
        session = await model.wait_until(lambda: self.conv_sessions() and self.conv_sessions()[0])
        await model.wait_until(lambda: len(session.binaries()) >= 3)
        self.assertEqual(nonzero_frames(session), [])
        await robot.close_conv()
        uplink = self.of(await self.records(conv), "close")[0]["uplink"]
        self.assertEqual((uplink["backlog"], uplink["gated"]), (3, 3))

    async def test_the_strict_gate_zero_fill_is_unchanged_and_counted_apart(self):
        script = Script(turns=[Turn(user_end_at=0.05, user_text=["hello"], reply_delay=0.05,
                                    reply_seconds=0.64)])
        robot = await self.start(script)
        await self.open_ready(robot, "strict")
        conv = robot.conv
        await robot.wait_for(lambda: robot.binaries())  # the reply: the strict gate is shut
        for _ in range(4):
            await robot.send_audio(SPEECH)  # loud, but strict turn-taking zeroes it anyway
            await asyncio.sleep(0.02)
        session = self.conv_sessions()[0]
        self.assertEqual(nonzero_frames(session), [])
        await robot.close_conv()
        uplink = self.of(await self.records(conv), "close")[0]["uplink"]
        self.assertEqual(uplink["zeroed"], 4)
        self.assertEqual(uplink["gated"], 0)


# The live failure this guards, one conversation, 42 replies: the person asked about zebras
# and the quantized model answered "They are used for decoration... used in fashion... used
# in art... used in music... used in sports... used in religion... used in education..."
# and then looped back to fashion/art/music/education and kept going. The machine was not
# the constraint (rtf 0.64-0.70, input backlog 1-2 frames) -- the model simply would not
# stop. Each phrase differs from the last by ONE word, which is why the unit the detector
# counts is a sliding four-word window and not a sentence.
ZEBRA_LOOP = ["Zebras are used for a lot of things. ", "They are used for decoration. ",
              "They are used in fashion. ", "They are used in art. ",
              "They are used in music. ", "They are used in sports. ",
              "They are used in religion. ", "They are used in education. ",
              "They are used in fashion. ", "They are used in art. ",
              "They are used in music. ", "They are used in education. ",
              "They are used in fashion. ", "They are used in art. ",
              "They are used in music. ", "They are used in education. ",
              "They are used in fashion. "]
# The negative case, deliberately LONGER than the loop above (103 words against 88): a real
# answer to the same question that rambles without ever repeating itself. If the detector
# fires on this it is unusable, because this is what a good model sounds like.
ZEBRA_ANSWER = ["Zebras are wild horses that live in Africa. ",
                "Every one of them has a stripe pattern nobody else shares, ",
                "a bit like a fingerprint, and a foal learns to follow its mother by it. ",
                "The stripes probably keep biting flies away, ",
                "because flies land far less often on striped surfaces than on plain ones. ",
                "They also help a herd look like one big shape to a lion at dusk. ",
                "Plains zebras migrate hundreds of miles between water holes, ",
                "and they sleep standing up, taking turns to watch for danger. ",
                "Nobody has ever tamed one properly, which is why you do not ride them. "]


class LoopGuardTests(unittest.TestCase):
    """The repetition detector on its own: normalized four-word windows, counted as they
    arrive, tripping on the third occurrence of any one of them."""

    def guard(self, **kw):
        return LoopGuard(**kw)

    @staticmethod
    def feed_all(guard, phrases):
        for phrase in phrases:
            hit = guard.feed(phrase)
            if hit is not None:
                return hit
        return None

    def test_the_zebra_loop_trips_and_names_the_repeated_window(self):
        self.assertEqual(self.feed_all(self.guard(), ZEBRA_LOOP), "they are used in")

    def test_a_longer_varied_answer_never_trips(self):
        self.assertIsNone(self.feed_all(self.guard(), ZEBRA_ANSWER))

    def test_it_trips_early_not_only_on_the_verbatim_loop_back(self):
        """The first pass alone -- every sentence different by one word -- is enough."""
        self.assertIsNotNone(self.feed_all(self.guard(), ZEBRA_LOOP[:5]))

    def test_ordinary_repeated_words_do_not_trip(self):
        text = ["The dog and the cat and the bird were in the garden. ",
                "You are right, that is the one I meant. ",
                "The weather was warm and the sky was clear, so the walk was nice."]
        self.assertIsNone(self.feed_all(self.guard(), text))

    def test_a_word_split_across_deltas_is_still_one_word(self):
        """The server streams text in deltas that cut words in half."""
        guard = self.guard()
        hit = self.feed_all(guard, ["they are us", "ed in fash", "ion they are used in art ",
                                    "they are used in mus", "ic"])
        self.assertEqual(hit, "they are used in")

    def test_punctuation_and_case_are_ignored(self):
        guard = self.guard()
        self.assertIsNone(guard.feed("Round and round we go! "))
        self.assertIsNone(guard.feed("round, and round we go... "))
        self.assertEqual(guard.feed("ROUND AND ROUND WE GO"), "round and round we")

    def test_a_stutter_of_one_word_trips(self):
        self.assertIsNotNone(self.guard().feed("no no no no no no no"))

    def test_the_threshold_is_configurable(self):
        self.assertIsNone(self.feed_all(self.guard(repeats=9), ZEBRA_LOOP[:5]))
        self.assertIsNotNone(self.feed_all(self.guard(repeats=2), ZEBRA_LOOP[:4]))

    def test_the_window_is_configurable(self):
        # A five-word window misses the zebra loop's first pass entirely: each sentence
        # differs from the last by its final word. That is why the default is four.
        self.assertIsNone(self.feed_all(self.guard(window=5), ZEBRA_LOOP[:8]))

    def test_reset_forgets_everything(self):
        guard = self.guard()
        self.feed_all(guard, ZEBRA_LOOP[:4])
        guard.reset()
        self.assertIsNone(self.feed_all(guard, ZEBRA_LOOP[:3]))

    def test_a_disabled_guard_never_trips(self):
        self.assertIsNone(self.feed_all(self.guard(repeats=0), ZEBRA_LOOP))


class ReplyCutTests(EngineTestCase):
    """Two independent cut-offs on a reply in flight, for a model that will not stop
    talking (see ZEBRA_LOOP). Either one flushes the robot's buffer, ends the reply with
    its own `why`, and MUTES the rest of the model's turn -- the model keeps generating for
    a while and its continuing audio must not open a fresh reply."""

    def cut_script(self, *, phrases=None, seconds=6.0, turns=None, **turn_fields):
        """One long reply, streamed as fast as the socket takes it and with no agent_end --
        which is what the real server does when a reply simply finishes."""
        fields = dict(user_end_at=0.05, reply_delay=0.05, agent_end=False,
                      reply_seconds=seconds, reply_phrases=phrases)
        fields.update(turn_fields)
        return Script(turns=turns or [Turn(**fields)], pace=0, idle_audio_every=0.08)

    @staticmethod
    def loud(robot):
        return [f for f in robot.binaries() if any(f.data)]

    async def cut(self, robot, why, timeout=4.0):
        """Wait for the robot's audio.flush and return the cut's log record."""
        flush = await robot.wait_text("audio.flush", timeout=timeout)
        await robot.wait_for(lambda: True)
        return flush

    async def test_a_reply_past_the_cap_is_cut_and_nothing_more_is_forwarded(self):
        robot = await self.start(self.cut_script(), reply_max_seconds=1.0)
        await self.open_ready(robot)
        conv = robot.conv
        await self.cut(robot, "too-long")
        mark = len(robot.frames)
        await asyncio.sleep(0.6)  # the rest of a 6 s reply is still arriving from the model
        self.assertEqual([f for f in robot.frames[mark:] if f.kind == "binary"], [])
        self.assertEqual(len(robot.texts("reply")), 1)  # and it opened no new reply
        await robot.close_conv()
        records = await self.records(conv)
        end = self.of(records, "reply.end")[0]
        self.assertEqual(end["why"], "too-long")
        # Measured on the audio forwarded, not on the clock: the fake sent all 6 s at once.
        self.assertGreaterEqual(end["audio_ms"], 1000)
        self.assertLess(end["audio_ms"], 1200)
        cut = self.of(records, "reply.cut")[0]
        self.assertEqual(cut["why"], "too-long")
        self.assertGreater(self.of(records, "close")[0]["cut"]["muted_chunks"], 0)

    async def test_a_looping_reply_is_cut_by_the_repetition_guard(self):
        robot = await self.start(self.cut_script(phrases=ZEBRA_LOOP),
                                 reply_max_seconds=0)  # only the loop guard can fire
        await self.open_ready(robot)
        conv = robot.conv
        await self.cut(robot, "looping")
        await robot.close_conv()
        records = await self.records(conv)
        self.assertEqual(self.of(records, "reply.end")[0]["why"], "looping")
        cut = self.of(records, "reply.cut")[0]
        self.assertEqual(cut["why"], "looping")
        self.assertEqual(cut["phrase"], "they are used in")
        self.assertIn("used in", cut["text"])  # the offending text, trimmed

    async def test_a_long_varied_answer_of_the_same_shape_is_not_cut(self):
        """The negative case that matters: a real, rambling, non-repetitive answer -- one
        word LONGER than the loop above -- has to survive both guards untouched."""
        robot = await self.start(self.cut_script(phrases=ZEBRA_ANSWER, seconds=6.0),
                                 reply_max_seconds=0)
        await self.open_ready(robot)
        conv = robot.conv
        await robot.wait_for(lambda: len(self.loud(robot)) >= 20, timeout=5.0)
        await asyncio.sleep(0.6)
        self.assertEqual(robot.texts("audio.flush"), [])
        self.assertEqual(len(robot.texts("reply")), 1)
        await robot.close_conv()
        records = await self.records(conv)
        self.assertEqual(self.of(records, "reply.cut"), [])
        self.assertEqual(self.of(records, "reply.end")[0]["why"], "silence")

    async def test_the_muted_tail_opens_no_new_reply_and_its_drops_are_counted(self):
        robot = await self.start(self.cut_script(phrases=ZEBRA_LOOP), reply_max_seconds=0)
        await self.open_ready(robot)
        conv = robot.conv
        await self.cut(robot, "looping")
        await asyncio.sleep(0.8)  # the model talks on, and the idle channel keeps streaming
        self.assertEqual(len(robot.texts("reply")), 1)
        await robot.close_conv()
        cut = self.of(await self.records(conv), "close")[0]["cut"]
        self.assertEqual(cut["looping"], 1)
        self.assertEqual(cut["mutes"], 1)
        self.assertGreater(cut["muted_chunks"], 0)

    async def test_the_next_turn_is_completely_normal_after_a_cut(self):
        """The conversation stays open: the person speaks again, the model's next reply
        plays in full, and its farewell phrase still closes the conversation."""
        script = self.cut_script(turns=[
            Turn(user_end_at=0.05, reply_delay=0.05, agent_end=False, reply_seconds=6.0,
                 reply_phrases=ZEBRA_LOOP),
            Turn(user_end_at=2.0, reply_delay=0.1, agent_end=False, reply_seconds=0.24,
                 reply_text=["Sure. Talk to you later."]),
        ])
        robot = await self.start(script, reply_max_seconds=0)
        await self.open_ready(robot)
        conv = robot.conv
        await self.cut(robot, "looping")
        await robot.wait_for(lambda: len(robot.texts("reply")) == 2, timeout=5.0)
        mark = len(robot.frames)
        await robot.wait_for(lambda: [f for f in robot.frames[mark:] if f.kind == "binary"],
                             timeout=3.0)
        await asyncio.sleep(0.4)
        await robot.playback("idle")
        self.assertEqual((await self.wait_close(robot, timeout=3.0))["reason"], "sleep_word")
        records = await self.records(conv)
        ends = self.of(records, "reply.end")
        self.assertEqual([e["why"] for e in ends], ["looping", "silence"])
        self.assertEqual(self.of(records, "reply.unmute")[0]["why"], "user_start")
        self.assertEqual(len(self.of(records, "drain")), 1)

    async def test_the_silence_timer_still_closes_the_conversation_after_a_cut(self):
        robot = await self.start(self.cut_script(phrases=ZEBRA_LOOP), reply_max_seconds=0,
                                 silence_timeout=0.8)
        await self.open_ready(robot)
        await self.cut(robot, "looping")
        self.assertEqual((await self.wait_close(robot, timeout=4.0))["reason"], "silence")

    async def test_reply_max_seconds_zero_disables_the_length_cap(self):
        robot = await self.start(self.cut_script(seconds=3.0), reply_max_seconds=0,
                                 loop_guard=False)
        await self.open_ready(robot)
        conv = robot.conv
        await robot.wait_for(lambda: len(self.loud(robot)) >= 20, timeout=5.0)
        await asyncio.sleep(0.6)
        self.assertEqual(robot.texts("audio.flush"), [])
        await robot.close_conv()
        self.assertEqual(self.of(await self.records(conv), "reply.cut"), [])

    async def test_no_loop_guard_disables_the_repetition_guard(self):
        robot = await self.start(self.cut_script(phrases=ZEBRA_LOOP), reply_max_seconds=0,
                                 loop_guard=False)
        await self.open_ready(robot)
        conv = robot.conv
        await robot.wait_for(lambda: len(self.loud(robot)) >= 20, timeout=5.0)
        await asyncio.sleep(0.6)
        self.assertEqual(robot.texts("audio.flush"), [])
        await robot.close_conv()
        self.assertEqual(self.of(await self.records(conv), "reply.cut"), [])

    async def test_the_length_cap_alone_still_cuts_a_loop_with_the_guard_off(self):
        robot = await self.start(self.cut_script(phrases=ZEBRA_LOOP), reply_max_seconds=1.0,
                                 loop_guard=False)
        await self.open_ready(robot)
        conv = robot.conv
        await self.cut(robot, "too-long")
        await robot.close_conv()
        self.assertEqual(self.of(await self.records(conv), "reply.end")[0]["why"], "too-long")


class BacklogAlarmTests(EngineTestCase):
    """Round 9. Measured live, `stats.input_backlog_frames` climbed 3 -> 7 -> 14 -> 20 -> 22
    -> 28 while the server was faster than realtime, and nothing in the relay said so. The
    alarm cannot fix it -- the frame clock in ModelClient does that -- but it must never let
    the same failure be invisible again."""

    async def alarms(self, conv):
        return self.of(await self.records(conv), "uplink.backlog")

    async def test_the_alarm_fires_once_when_the_model_falls_behind(self):
        script = Script(stats_every=0.04, stats_backlog_frames=40.0, stats_rtf=0.72)
        robot = await self.start(script, backlog_alarm_frames=25.0)
        await self.open_ready(robot)
        conv = robot.conv
        await asyncio.sleep(0.5)  # a dozen stats frames, every one of them over the limit
        alarms = await self.alarms(conv)
        self.assertEqual(len(alarms), 1, alarms)
        self.assertEqual(alarms[0]["frames"], 40.0)
        self.assertEqual(alarms[0]["limit"], 25.0)
        self.assertEqual(alarms[0]["rtf"], 0.72)
        # It names the measured send rate, which is the number that proves whose fault it is.
        self.assertIn("rate", alarms[0]["uplink"])
        self.assertIn("sent", alarms[0]["uplink"])

    async def test_a_backlog_under_the_threshold_never_alarms(self):
        script = Script(stats_every=0.04, stats_backlog_frames=3.0)
        robot = await self.start(script, backlog_alarm_frames=25.0)
        await self.open_ready(robot)
        conv = robot.conv
        await asyncio.sleep(0.4)
        self.assertEqual(await self.alarms(conv), [])

    async def test_the_alarm_re_arms_only_after_the_backlog_recovers(self):
        script = Script(stats_every=0.04, stats_backlog_frames=40.0)
        robot = await self.start(script, backlog_alarm_frames=25.0)
        await self.open_ready(robot)
        conv = robot.conv
        await asyncio.sleep(0.3)
        self.assertEqual(len(await self.alarms(conv)), 1)
        script.stats_backlog_frames = 2.0  # the model caught up
        await asyncio.sleep(0.2)
        self.assertEqual(len(await self.alarms(conv)), 1)
        script.stats_backlog_frames = 60.0  # and fell behind again: a new excursion
        await asyncio.sleep(0.3)
        alarms = await self.alarms(conv)
        self.assertEqual(len(alarms), 2, alarms)
        self.assertEqual(alarms[1]["frames"], 60.0)

    async def test_the_close_record_carries_what_the_model_was_actually_sent(self):
        robot = await self.start(Script())
        await self.open_ready(robot)
        conv = robot.conv
        for _ in range(4):
            await robot.send_audio(SPEECH)
            await asyncio.sleep(0.08)
        await robot.close_conv()
        close = self.of(await self.records(conv), "close")[0]
        model = close["uplink"]["model"]
        self.assertEqual(model["sent"] + model["zero"], model["frames"])
        self.assertTrue(0.7 <= model["rate"] <= 1.3, model)


class BacklogAlarmDefaultsTests(unittest.TestCase):
    def test_the_alarm_threshold_is_two_seconds_of_frames_by_default(self):
        self.assertEqual(Config().backlog_alarm_frames, 25.0)


class ReplyCutDefaultsTests(unittest.TestCase):
    def test_the_guards_are_on_by_default_at_the_documented_settings(self):
        self.assertEqual(Config().reply_max_seconds, 30.0)
        self.assertIs(Config().loop_guard, True)
        self.assertEqual(Config().loop_guard_repeats, 3)
        self.assertEqual(Config().loop_guard_window, 4)

if __name__ == "__main__":
    unittest.main()
