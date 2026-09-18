"""Tests for relay/relay/conversation.py, the KTD4 conversation engine (plan U5).

The engine runs as the lane's handler between the fake robot and the scripted fake model
server, on shortened timeouts. Covers U5's scenarios: the sleep-word drain (AE3) and its
two time limits, the silence timer (AE4), strict turn-taking (AE6) and its underrun rule,
barge-in flush, a fresh model session per conversation (AE7), link loss (AE10), model
failures and the health probe (KTD4), per-turn latency fields and the log never blocking.
"""
import asyncio
import contextlib
import json
import re
import signal
import sys
import tempfile
import time
import unittest
from pathlib import Path


RELAY_ROOT = Path(__file__).resolve().parents[1]
if str(RELAY_ROOT) not in sys.path:
    sys.path.insert(0, str(RELAY_ROOT))

from relay.conversation import Config, ConversationEngine  # noqa: E402
from relay.lane import LaneServer  # noqa: E402
from relay.logging import ConversationLogs  # noqa: E402
from tests.fake_model_server import FakeModelServer, Script, Turn  # noqa: E402
from tests.fake_robot import FakeRobot  # noqa: E402

PERSONA = "You are Miko, a friendly robot. Keep replies short."
LOUD = (8000).to_bytes(2, "little", signed=True) * 1280  # 80 ms of 16 kHz, RMS 8000


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
                    turn_taking="interruptible", **overrides):
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
                        probe_timeout=0.3, sleep_settle=0.1, strict_idle_grace=1.0)
        settings.update(overrides)
        self.engine = ConversationEngine(Config(**settings), self.logs)
        await self.engine.start()
        self.stack.push_async_callback(self.engine.stop)
        self.lane = LaneServer(self.engine, "127.0.0.1", 0, ping_interval=lane_ping)
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
        frames = [(100 + i).to_bytes(2, "little") * 640 for i in range(5)]
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
        model_events = {r["msg"]["type"] for r in self.of(records, "model")}
        self.assertTrue({"user_start", "user_end", "user_text", "agent_start",
                         "agent_end", "stats"} <= model_events)

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


if __name__ == "__main__":
    unittest.main()
