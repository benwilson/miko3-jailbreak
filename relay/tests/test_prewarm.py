"""Tests for the warm model session (prewarm): the persona read taken off the wake path.

Reading the persona into the model costs 1.5-3 s when the server is idle and was measured
at 10.9 s when it was busy, against the robot's 15 s ready timeout -- and every millisecond
of it sits between "Hey Miko" and the robot being able to hear the person. With prewarm on
the relay keeps one session that has already sent `reset` and the persona, so `conv.open`
only has to start streaming.

The rules these cover:

- a conversation that adopts a warm session does not pay the persona read (asserted on the
  engine's own records, not on wall-clock sleeps: the fake's ack is slower than the
  conversation's whole ready timeout, and conv.ready still arrives);
- R9 still holds: the adopted session saw `reset` + the persona and nothing else, and a
  session that has carried a conversation is never handed to another one;
- a fresh warm session is prepared after every conversation;
- `--no-prewarm` is exactly today's behaviour: nothing is held while idle and the
  conversation opens (and pays for) its own session;
- a warm session that is evicted -- the owner opening the model server's browser page --
  is noticed, reported as model_ok:false, and re-warmed;
- the model server takes one client, so the relay never holds two connections at once.
"""
import asyncio
import sys
from pathlib import Path

RELAY_ROOT = Path(__file__).resolve().parents[1]
if str(RELAY_ROOT) not in sys.path:
    sys.path.insert(0, str(RELAY_ROOT))

from websockets.asyncio.client import connect  # noqa: E402

from relay.model_client import ZERO_FRAME  # noqa: E402
from tests.fake_model_server import Script, Turn  # noqa: E402
from tests.test_conversation import PERSONA, EngineTestCase  # noqa: E402

LOUD = (8000).to_bytes(2, "little", signed=True) * 1280  # 80 ms of 16 kHz, RMS 8000


async def until(predicate, timeout=3.0, step=0.01):
    """Poll `predicate` until it is truthy; returns its value. Used instead of a fixed
    sleep so a slow machine lengthens the test rather than failing it."""
    async with asyncio.timeout(timeout):
        while True:
            value = predicate()
            if value:
                return value
            await asyncio.sleep(step)


class PrewarmTestCase(EngineTestCase):
    async def start(self, script=None, **overrides):
        overrides.setdefault("prewarm", True)
        return await super().start(script, **overrides)

    def persona_sessions(self):
        """Every session the relay sent the persona on: the warm ones and their heirs."""
        return [s for s in self.model.sessions
                if any(d.get("type") == "system" for d in s.texts())]

    async def wait_warm(self, after=0, timeout=3.0):
        """Wait until a warm session beyond the first `after` is ready; returns it."""
        await until(lambda: self.engine.warm is not None and len(self.persona_sessions()) > after,
                    timeout=timeout)
        return self.engine.warm

    async def ready_record(self, conv):
        """The conv.ready log record the engine wrote for `conv`."""
        records = await self.records(conv)
        ready = [r for r in self.of(records, "relay")
                 if (r.get("msg") or {}).get("type") == "conv.ready"]
        self.assertEqual(len(ready), 1, records)
        return ready[0]


class PrewarmTests(PrewarmTestCase):
    async def test_conv_open_does_not_pay_the_persona_read(self):
        # The fake takes 0.5 s to read the persona in -- twice this conversation's whole
        # ready timeout. Without a warm session conv.open could only end in model_error.
        robot = await self.start(Script(system_ack_delay=0.5), ready_timeout=0.25)
        await self.wait_warm()
        mark = len(robot.frames)
        conv = await robot.open_conv()
        await robot.wait_text("conv.ready", after=mark, timeout=1.0)
        ready = await self.ready_record(conv)
        self.assertIs(ready["warm"], True)
        self.assertEqual(ready["system_ms"], 0.0)  # this conversation read no persona in
        self.assertGreaterEqual(ready["warm_system_ms"], 450)  # the warm session paid it
        self.assertLess(ready["rt_ms"], 150)  # conv.open to conv.ready, engine-measured
        adopt = self.of(await self.records(conv), "warm.adopt")
        self.assertEqual(len(adopt), 1)
        self.assertGreaterEqual(adopt[0]["age_ms"], 0)
        self.assertGreaterEqual(adopt[0]["system_ms"], 450)

    async def test_a_warm_session_still_opening_is_waited_for_and_adopted(self):
        # conv.open lands while the warm session is still reading the persona in: it waits
        # for that one rather than opening a second connection beside it.
        robot = await self.start(Script(system_ack_delay=0.3), ready_timeout=2.0)
        mark = len(robot.frames)
        conv = await robot.open_conv()
        await robot.wait_text("conv.ready", after=mark, timeout=2.0)
        self.assertIs((await self.ready_record(conv))["warm"], True)
        self.assertEqual(self.model.evictions, 0)
        self.assertEqual(len(self.persona_sessions()), 1)

    async def test_the_adopted_session_is_fresh(self):  # R9
        script = Script(turns=[Turn(user_end_at=0.05, reply_delay=0.05, reply_seconds=0.16)])
        robot = await self.start(script)
        first = await self.wait_warm()
        await self.open_ready(robot)
        for _ in range(3):
            await robot.send_audio(LOUD)
        await robot.wait_for(lambda: len(robot.binaries()) >= 2)
        await robot.close_conv()
        second = await self.wait_warm(after=1)
        self.assertIsNot(second.client, first.client)
        await self.open_ready(robot)
        sessions = self.persona_sessions()
        self.assertEqual(len(sessions), 2)
        for session in sessions:
            texts = session.texts()
            self.assertEqual(texts[0], {"type": "reset"})
            self.assertEqual(texts[1]["type"], "system")
            self.assertEqual(texts[1]["text"], PERSONA)
            self.assertEqual([d for d in texts[2:] if d.get("type") == "system"], [])
        # The second conversation's session never heard the first one's audio: the only
        # loud frames on it are its own (there are none yet).
        self.assertTrue([f for f in sessions[0].binaries() if f.data == LOUD])
        self.assertEqual([f for f in sessions[1].binaries() if f.data == LOUD], [])
        # And the session that carried the first conversation is gone, not recycled.
        await until(lambda: sessions[0].ws.close_code is not None)

    async def test_a_fresh_warm_session_follows_every_conversation(self):
        robot = await self.start(Script())
        warm = await self.wait_warm()
        conv1 = await robot.open_conv()
        await robot.wait_text("conv.ready")
        self.assertIsNone(self.engine.warm)  # it was taken, not shared
        await robot.close_conv()
        again = await self.wait_warm(after=1)
        self.assertIsNot(again.client, warm.client)
        mark = len(robot.frames)
        conv2 = await robot.open_conv()
        await robot.wait_text("conv.ready", after=mark)
        self.assertIs((await self.ready_record(conv1))["warm"], True)
        self.assertIs((await self.ready_record(conv2))["warm"], True)
        self.assertEqual(len(self.persona_sessions()), 2)

    async def test_never_more_than_one_model_connection(self):
        script = Script(turns=[Turn(user_end_at=0.05, reply_delay=0.05, reply_seconds=0.16)])
        robot = await self.start(script)
        await self.wait_warm()
        for _ in range(2):
            await self.open_ready(robot)
            await robot.wait_for(lambda: len(robot.binaries()) >= 1, timeout=2.0)
            await robot.close_conv()
            await self.wait_warm(after=len(self.persona_sessions()))
        self.assertEqual(self.model.evictions, 0)
        self.assertLessEqual(self.model.max_open, 1)


class QuietWarmSessionTests(PrewarmTestCase):
    """A warm session waits SILENTLY. ModelClient's watchdog sends an 80 ms zero frame
    whenever nothing else has gone out, which is what lets the model's VAD hear the person
    stop talking -- but before a conversation there is nobody talking, and this server runs
    slower than realtime (measured rtf 1.8-3.3), so those frames only pile into its input
    backlog. Live evidence: a conversation opened with the model already 221 frames (~18 s)
    behind and climbing to 489, and after hours of it the model degenerated into a
    repetition loop. Its liveness is still watched -- the connection closing is what says
    the model went away (WarmRecoveryTests), not any traffic on it."""

    async def test_an_unadopted_warm_session_sends_the_model_nothing(self):
        robot = await self.start()
        warm = await self.wait_warm()
        session = self.persona_sessions()[0]
        await asyncio.sleep(0.5)  # six watchdog intervals; the old code sent six frames
        self.assertEqual(session.binaries(), [])
        self.assertEqual(warm.client.zero_frames_sent, 0)
        self.assertEqual(warm.client.chunks_sent, 0)
        mark = len(robot.frames)  # and it is still instantly adoptable
        conv = await robot.open_conv()
        await robot.wait_text("conv.ready", after=mark, timeout=1.0)
        self.assertIs((await self.ready_record(conv))["warm"], True)

    async def test_the_watchdog_runs_as_before_once_the_session_is_adopted(self):
        robot = await self.start()
        warm = await self.wait_warm()
        session = self.persona_sessions()[0]
        await self.open_ready(robot)
        await until(lambda: len(session.binaries()) >= 3)
        self.assertEqual([f.data for f in session.binaries()], [ZERO_FRAME] * len(session.binaries()))
        self.assertGreaterEqual(warm.client.zero_frames_sent, 3)
        await robot.send_audio(LOUD)  # and real uplink still goes out beside it
        await until(lambda: LOUD in [f.data for f in session.binaries()])


class NoPrewarmTests(EngineTestCase):
    async def test_no_prewarm_is_exactly_todays_behaviour(self):
        robot = await self.start(Script(), prewarm=False, probe_interval=0.1)
        await asyncio.sleep(0.3)
        # Nothing is held while the robot is listening: the probe connects, asks and goes.
        self.assertEqual([s for s in self.model.sessions
                          if any(d.get("type") == "system" for d in s.texts())], [])
        self.assertTrue([s for s in self.model.sessions
                         if any(d.get("type") == "status" for d in s.texts())])
        self.assertTrue(all(s.ws.close_code is not None for s in self.model.sessions))
        conv = await robot.open_conv()
        await robot.wait_text("conv.ready")
        records = await self.records(conv)
        ready = [r for r in self.of(records, "relay")
                 if (r.get("msg") or {}).get("type") == "conv.ready"][0]
        self.assertIs(ready["warm"], False)
        self.assertGreater(ready["system_ms"], 0.0)  # this conversation paid the read
        self.assertEqual(len(self.of(records, "warm.miss")), 1)
        self.assertIsNone(self.engine.warm)
        self.assertEqual(self.model.evictions, 0)


class WarmRecoveryTests(PrewarmTestCase):
    async def test_an_evicted_warm_session_is_reported_and_re_warmed(self):
        robot = await self.start(Script(), probe_interval=0.15)
        warm = await self.wait_warm()
        mark = len(robot.frames)
        # The owner opens the model server's browser page: the newest client wins.
        page = await connect(f"ws://127.0.0.1:{self.model.port}/ws", compression=None,
                             close_timeout=0.5)
        await until(lambda: warm.client.closed)
        down = await robot.wait_text("status", after=mark, timeout=2.0)
        self.assertIs(down.data["model_ok"], False)
        self.assertIsNone(self.engine.warm)
        await page.close()  # the page is closed again; the relay re-warms on its own
        await self.wait_warm(after=1, timeout=3.0)
        up = await robot.wait_text("status", after=robot.frames.index(down) + 1, timeout=3.0)
        self.assertIs(up.data["model_ok"], True)
        mark = len(robot.frames)
        conv = await robot.open_conv()
        await robot.wait_text("conv.ready", after=mark, timeout=2.0)
        self.assertIs((await self.ready_record(conv))["warm"], True)

    async def test_the_warm_session_does_not_hammer_a_dead_server(self):
        robot = await self.start(Script(), probe_interval=0.2)
        await self.wait_warm()
        await self.model.__aexit__(None, None, None)  # the model server goes away
        down = await robot.wait_text("status", timeout=2.0)
        self.assertIs(down.data["model_ok"], False)
        before = len(self.model.sessions)
        await asyncio.sleep(0.6)
        self.assertEqual(len(self.model.sessions), before)  # nothing got through, no storm
        self.assertIsNone(self.engine.warm)
