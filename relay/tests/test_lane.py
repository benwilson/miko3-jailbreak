"""Tests for relay/relay/lane.py (plan U11) against the fake robot, plus the relay stub.

Covers U11's scenarios: `hello`/`welcome` and proto rejection, the unknown-type and
unknown-field tolerance rules, uplink routing only while a conversation is open, the
KTD5 reply pacer (burst cap, steady cadence, flush), the command lane (AE8), link loss on
missed pongs, and a second connection for the same robot id replacing the first. Pacing
runs on a time-scaled Pacing so a "3-second" reply takes under a second.
"""
import asyncio
import sys
import tempfile
import unittest
import wave
from pathlib import Path

from websockets.asyncio.client import connect

RELAY_ROOT = Path(__file__).resolve().parents[1]
if str(RELAY_ROOT) not in sys.path:
    sys.path.insert(0, str(RELAY_ROOT))

from relay.lane import (  # noqa: E402
    CLOSE_BAD_HELLO,
    CLOSE_PROTO,
    CLOSE_REPLACED,
    REPLY_CHUNK_BYTES,
    LaneHandler,
    LaneServer,
    Pacing,
)
from tests.fake_robot import FakeRobot  # noqa: E402
from tests.relay_stub import StubHandler, load_wav, tone  # noqa: E402

FAST_PACING = Pacing(chunk_seconds=0.02, burst_seconds=0.125)  # 4x time scale, 6-chunk burst


def pcm(samples, value=0):
    return int(value).to_bytes(2, "little", signed=True) * samples


def reply_audio(chunks):
    """Distinct, checkable reply PCM: chunk i is filled with byte i % 251."""
    return b"".join(bytes([i % 251]) * REPLY_CHUNK_BYTES for i in range(chunks))


async def poll(predicate, timeout=2.0):
    """Wait until predicate() is truthy, checking every few milliseconds; returns its value."""
    async with asyncio.timeout(timeout):
        while not (value := predicate()):
            await asyncio.sleep(0.002)
        return value


class RecordingHandler(LaneHandler):
    """Records every callback; can auto-answer conv.open with conv.ready."""

    def __init__(self, model_ok=True, auto_ready=True):
        self.ok = model_ok
        self.auto_ready = auto_ready
        self.calls = []
        self.uplink = []
        self.changed = asyncio.Event()

    def _record(self, *call):
        self.calls.append(call)
        self.changed.set()

    def model_ok(self, link):
        return self.ok

    def on_link(self, link):
        self._record("link", link)

    def on_conv_open(self, link, conv, turn_taking, msg):
        self._record("conv_open", link, conv, turn_taking)
        if self.auto_ready:
            link.send_conv_ready(conv)

    def on_uplink(self, link, conv, pcm):
        self.uplink.append((conv, pcm))
        self.changed.set()

    def on_conv_close(self, link, conv, reason, msg):
        self._record("conv_close", link, conv, reason)

    def on_playback(self, link, msg):
        self._record("playback", link, msg)

    def on_cmd_result(self, link, msg):
        self._record("cmd_result", link, msg)

    def on_link_closed(self, link, why):
        self._record("link_closed", link, why)

    def named(self, name):
        return [c for c in self.calls if c[0] == name]

    async def wait_for(self, predicate, timeout=2.0):
        async with asyncio.timeout(timeout):
            while True:
                value = predicate()
                if value:
                    return value
                self.changed.clear()
                await self.changed.wait()


class LaneTestCase(unittest.IsolatedAsyncioTestCase):
    pacing = FAST_PACING
    ping_interval = 2.0

    def make_handler(self):
        return RecordingHandler()

    async def asyncSetUp(self):
        self.handler = self.make_handler()
        self.server = LaneServer(self.handler, host="127.0.0.1", port=0, pacing=self.pacing,
                                 ping_interval=self.ping_interval, hello_timeout=0.5)
        await self.server.start()
        self.robots = []

    async def asyncTearDown(self):
        for robot in self.robots:
            await robot.close()
        await self.server.stop()

    def robot(self, **kw):
        robot = FakeRobot(self.server.port, **kw)
        self.robots.append(robot)
        return robot

    async def connected(self, **kw):
        robot = self.robot(**kw)
        welcome = await robot.connect()
        link = await poll(lambda: self.server.links.get(robot.robot_id))
        return robot, link, welcome

    async def conversing(self, **kw):
        robot, link, _ = await self.connected(**kw)
        conv = await robot.open_conv()
        await robot.wait_text("conv.ready")
        return robot, link, conv


class HelloTests(LaneTestCase):
    async def test_hello_gets_welcome_with_envelope_and_model_flag(self):
        robot, link, welcome = await self.connected()
        self.assertEqual(welcome["type"], "welcome")
        self.assertEqual(welcome["proto"], 1)
        self.assertIs(welcome["model_ok"], True)
        self.assertIn("relay_version", welcome)
        self.assertIsInstance(welcome["id"], int)
        self.assertIsInstance(welcome["t"], int)
        self.assertIn("conv", welcome)
        self.assertIsNone(welcome["conv"])
        self.assertEqual(link.robot_id, "miko-test")
        self.assertEqual(self.handler.named("link")[0][1], link)

    async def test_welcome_reports_model_down(self):
        self.handler.ok = False
        _, _, welcome = await self.connected()
        self.assertIs(welcome["model_ok"], False)

    async def test_unknown_proto_is_closed_with_reason(self):
        robot = self.robot()
        await robot.connect({"proto": 2}, expect_welcome=False)
        code, reason = await robot.wait_closed()
        self.assertEqual(code, CLOSE_PROTO)
        self.assertIn("proto", reason)
        self.assertEqual(robot.texts("welcome"), [])
        self.assertEqual(self.server.links, {})

    async def test_first_frame_must_be_hello(self):
        async with connect(f"ws://127.0.0.1:{self.server.port}/") as ws:
            await ws.send('{"type":"conv.open","id":1,"conv":"c1","t":0}')
            async with asyncio.timeout(2):
                await ws.wait_closed()
            self.assertEqual(ws.close_code, CLOSE_BAD_HELLO)

    async def test_hello_without_robot_id_is_closed(self):
        robot = self.robot()
        await robot.connect({"robot_id": ""}, expect_welcome=False)
        code, _ = await robot.wait_closed()
        self.assertEqual(code, CLOSE_BAD_HELLO)

    async def test_no_hello_times_out(self):
        async with connect(f"ws://127.0.0.1:{self.server.port}/") as ws:
            async with asyncio.timeout(2):
                await ws.wait_closed()
            self.assertEqual(ws.close_code, CLOSE_BAD_HELLO)

    async def test_hello_with_unknown_field_is_welcomed(self):
        robot = self.robot()
        welcome = await robot.connect({"future_field": {"x": 1}})
        self.assertEqual(welcome["type"], "welcome")

    async def test_same_robot_id_replaces_first_connection(self):
        first, first_link, _ = await self.connected()
        conv = await first.open_conv()
        await first.wait_text("conv.ready")
        second = self.robot()
        await second.connect()
        code, _ = await first.wait_closed()
        self.assertEqual(code, CLOSE_REPLACED)
        new_link = self.server.links["miko-test"]
        self.assertIsNot(new_link, first_link)
        self.assertIn(("conv_close", first_link, conv, "link_lost"), self.handler.calls)
        # the old link's cleanup ran before the new link was announced
        order = [c[0] for c in self.handler.calls if c[0] in ("link", "conv_close")]
        self.assertEqual(order, ["link", "conv_close", "link"])
        # the replacement stays registered after the old socket finishes closing
        await asyncio.sleep(0.05)
        self.assertIs(self.server.links["miko-test"], new_link)


class ToleranceTests(LaneTestCase):
    async def test_unknown_type_gets_unsupported(self):
        robot, _, _ = await self.connected()
        msg_id = await robot.send("dance.now", conv="c9", speed=3)
        result = (await robot.wait_text("cmd.result")).data
        self.assertEqual(result["re"], msg_id)
        self.assertEqual(result["status"], "unsupported")
        self.assertEqual(result["conv"], "c9")

    async def test_unknown_field_is_ignored(self):
        robot, _, _ = await self.connected()
        conv = await robot.open_conv(turn_taking="strict", mood="sunny")
        ready = (await robot.wait_text("conv.ready")).data
        self.assertEqual(ready["conv"], conv)
        call = self.handler.named("conv_open")[0]
        self.assertEqual(call[2:], (conv, "strict"))
        self.assertEqual(robot.texts("cmd.result"), [])

    async def test_conv_open_turn_taking_defaults_to_hello_value(self):
        robot, _, _ = await self.connected(turn_taking="strict")
        await robot.open_conv()
        await robot.wait_text("conv.ready")
        self.assertEqual(self.handler.named("conv_open")[0][3], "strict")

    async def test_malformed_json_does_not_kill_link(self):
        robot, _, _ = await self.connected()
        await robot.send_raw("{not json")
        await robot.open_conv()
        await robot.wait_text("conv.ready")

    async def test_handler_exception_does_not_kill_link(self):
        def boom(*args):
            raise RuntimeError("engine bug")
        self.handler.on_playback = boom
        robot, _, _ = await self.connected()
        with self.assertLogs("relay.lane", "ERROR"):
            await robot.playback("idle")
            await robot.open_conv()
            await robot.wait_text("conv.ready")


class ConversationTests(LaneTestCase):
    async def test_uplink_dropped_without_conversation_and_forwarded_within(self):
        robot, link, _ = await self.connected()
        await robot.send_audio(pcm(1280, 5))
        await robot.send_audio(pcm(1280, 5))
        await poll(lambda: link.stats["uplink_dropped"] == 2)
        conv = await robot.open_conv()
        await robot.wait_text("conv.ready")
        frame = pcm(321, 7)  # any whole number of samples
        await robot.send_audio(frame)
        await self.handler.wait_for(lambda: self.handler.uplink)
        self.assertEqual(self.handler.uplink, [(conv, frame)])
        await robot.close_conv()
        await self.handler.wait_for(lambda: self.handler.named("conv_close"))
        await robot.send_audio(pcm(1280, 9))
        await poll(lambda: link.stats["uplink_dropped"] == 3)
        self.assertEqual(len(self.handler.uplink), 1)
        self.assertEqual(self.handler.named("conv_close")[0][2:], (conv, "robot_request"))

    async def test_odd_length_uplink_is_dropped(self):
        robot, link, conv = await self.conversing()
        await robot.send_audio(b"\x01\x02\x03")
        await poll(lambda: link.stats["uplink_dropped"] == 1)
        self.assertEqual(self.handler.uplink, [])

    async def test_relay_close_sends_reason_and_rejects_unknown_reason(self):
        robot, link, conv = await self.conversing()
        with self.assertRaises(ValueError):
            link.close_conv("bored")
        self.assertTrue(link.close_conv("silence"))
        close = (await robot.wait_text("conv.close")).data
        self.assertEqual((close["conv"], close["reason"]), (conv, "silence"))
        self.assertIsNone(link.conv)
        self.assertEqual(self.handler.named("conv_close"), [])  # engine-initiated: no callback

    async def test_late_conv_ready_is_not_sent(self):
        self.handler.auto_ready = False
        robot, link, _ = await self.connected()
        conv = await robot.open_conv()
        await self.handler.wait_for(lambda: self.handler.named("conv_open"))
        await robot.close_conv()
        await self.handler.wait_for(lambda: self.handler.named("conv_close"))
        self.assertFalse(link.send_conv_ready(conv))
        await asyncio.sleep(0.05)
        self.assertEqual(robot.texts("conv.ready"), [])

    async def test_second_conv_open_replaces_first(self):
        robot, link, first = await self.conversing()
        second = await robot.open_conv()
        await robot.wait_text("conv.ready", after=len(robot.frames))
        self.assertEqual(link.conv, second)
        self.assertEqual(self.handler.named("conv_close")[0][2:], (first, "robot_request"))

    async def test_status_and_playback(self):
        robot, link, conv = await self.conversing()
        link.send_status(False)
        status = (await robot.wait_text("status")).data
        self.assertIs(status["model_ok"], False)
        await robot.playback("playing", buffered_ms=240, first_chunk_to_play_ms=95)
        call = await self.handler.wait_for(lambda: self.handler.named("playback"))
        msg = call[0][2]
        self.assertEqual((msg["state"], msg["buffered_ms"], msg["first_chunk_to_play_ms"]),
                         ("playing", 240, 95))


class PacingTests(LaneTestCase):
    async def test_instant_reply_is_burst_then_steady_cadence(self):
        robot, link, conv = await self.conversing()
        chunk_s = FAST_PACING.chunk_seconds
        total = round(3.0 / 0.080)  # a 3-second reply, 38 chunks
        audio = reply_audio(total)
        reply_id = link.begin_reply()
        link.send_reply_audio(reply_id, audio)
        link.end_reply(reply_id)
        await robot.wait_for(lambda: len(robot.binaries()) == total, timeout=3)
        mark = robot.texts("reply")[0]
        self.assertEqual(mark.data["id"], reply_id)
        self.assertEqual(mark.data["conv"], conv)
        chunks = robot.binaries()
        self.assertLessEqual(mark.t, chunks[0].t)
        self.assertLess(robot.frames.index(mark), robot.frames.index(chunks[0]))
        self.assertEqual(b"".join(c.data for c in chunks), audio)
        self.assertTrue(all(len(c.data) == REPLY_CHUNK_BYTES for c in chunks))
        t0 = chunks[0].t
        burst = [c for c in chunks if c.t - t0 < chunk_s / 2]
        self.assertEqual(len(burst), 6)  # 480 ms of audio, the most under the 500 ms cap
        # never more than the burst ahead of the estimated playback position
        for i, c in enumerate(chunks):
            ahead = (i + 1) * chunk_s - (c.t - t0)
            self.assertLessEqual(ahead, FAST_PACING.burst_seconds + 0.005, f"chunk {i}")
        # after the burst, one chunk per chunk period, on a drift-free timer
        steady = chunks[6:]
        span = steady[-1].t - steady[0].t
        self.assertAlmostEqual(span / (len(steady) - 1), chunk_s, delta=0.003)
        # the first steady chunk goes when the estimate has room for it under the cap
        first_due = 7 * chunk_s - FAST_PACING.burst_seconds
        self.assertAlmostEqual(steady[0].t - t0, first_due, delta=0.006)
        self.assertAlmostEqual(steady[-1].t - t0, first_due + (len(steady) - 1) * chunk_s,
                               delta=0.008)  # no drift

    async def test_tail_is_zero_padded_to_a_whole_chunk(self):
        robot, link, _ = await self.conversing()
        reply_id = link.begin_reply()
        link.send_reply_audio(reply_id, b"\x11" * 1000)
        link.send_reply_audio(reply_id, b"\x22" * 3000)
        link.end_reply(reply_id)
        await robot.wait_for(lambda: len(robot.binaries()) == 2)
        data = b"".join(c.data for c in robot.binaries())
        self.assertEqual(data, b"\x11" * 1000 + b"\x22" * 3000 + bytes(2 * 3528 - 4000))

    async def test_flush_discards_queued_audio_and_next_reply_resumes(self):
        robot, link, conv = await self.conversing()
        first = link.begin_reply()
        link.send_reply_audio(first, reply_audio(38))
        await robot.wait_for(lambda: len(robot.binaries()) >= 8)
        discarded = link.flush_reply(first)
        self.assertGreater(discarded, 20)
        link.send_reply_audio(first, reply_audio(5))  # late audio for the flushed reply
        flush = await robot.wait_text("audio.flush")
        self.assertEqual(flush.data["reply"], first)
        await asyncio.sleep(0.15)  # several chunk periods
        flush_index = robot.frames.index(flush)
        self.assertEqual([f for f in robot.frames[flush_index:] if f.kind == "binary"], [])
        before = len(robot.binaries())
        self.assertLess(before, 38)
        second = link.begin_reply()
        link.send_reply_audio(second, reply_audio(3))
        link.end_reply(second)
        await robot.wait_for(lambda: len(robot.binaries()) == before + 3)
        mark = robot.texts("reply")[-1]
        self.assertEqual(mark.data["id"], second)
        self.assertGreater(robot.frames.index(mark), flush_index)
        after = [f for f in robot.frames[flush_index:] if f.kind == "binary"]
        self.assertGreater(robot.frames.index(after[0]), robot.frames.index(mark))

    async def test_conv_close_discards_queued_reply(self):
        robot, link, _ = await self.conversing()
        reply_id = link.begin_reply()
        link.send_reply_audio(reply_id, reply_audio(38))
        await robot.wait_for(lambda: len(robot.binaries()) >= 6)
        link.close_conv("silence")
        close = await robot.wait_text("conv.close")
        await asyncio.sleep(0.1)
        after = [f for f in robot.frames[robot.frames.index(close):] if f.kind == "binary"]
        self.assertEqual(after, [])
        self.assertFalse(link.send_reply_audio(reply_id, reply_audio(1)))


class RealTimePacingTests(LaneTestCase):
    pacing = Pacing()

    async def test_default_pacing_is_80ms(self):
        robot, link, _ = await self.conversing()
        reply_id = link.begin_reply()
        link.send_reply_audio(reply_id, reply_audio(9))
        link.end_reply(reply_id)
        await robot.wait_for(lambda: len(robot.binaries()) == 9, timeout=2)
        chunks = robot.binaries()
        t0 = chunks[0].t
        self.assertLess(chunks[5].t - t0, 0.02)  # 6-chunk burst
        self.assertAlmostEqual(chunks[6].t - t0, 7 * 0.080 - 0.5, delta=0.012)
        self.assertAlmostEqual(chunks[8].t - chunks[6].t, 2 * 0.080, delta=0.012)


class CommandLaneTests(LaneTestCase):
    async def test_cmd_answered_unsupported(self):  # AE8
        robot, link, conv = await self.conversing()
        cmd_id = link.send_cmd("wave", arm="left")
        call = await self.handler.wait_for(lambda: self.handler.named("cmd_result"))
        result = call[0][2]
        self.assertEqual((result["re"], result["status"], result["conv"]),
                         (cmd_id, "unsupported", conv))
        cmd = robot.texts("cmd")[0].data
        self.assertEqual((cmd["action"], cmd["arm"], cmd["conv"]), ("wave", "left", conv))
        # the conversation continues
        await robot.send_audio(pcm(1280))
        await self.handler.wait_for(lambda: self.handler.uplink)

    async def test_cmd_in_flight_at_conv_close_is_cancelled(self):
        robot, link, conv = await self.conversing(hold_cmds=True)
        cmd_id = link.send_cmd("wave")
        await robot.wait_text("cmd")
        link.close_conv("silence")
        call = await self.handler.wait_for(lambda: self.handler.named("cmd_result"))
        self.assertEqual((call[0][2]["re"], call[0][2]["status"]), (cmd_id, "cancelled"))


class LinkLossTests(LaneTestCase):
    ping_interval = 0.05

    async def test_missed_pongs_mark_link_lost(self):
        robot, link, conv = await self.conversing()
        robot.go_silent()
        call = await self.handler.wait_for(lambda: self.handler.named("link_closed"),
                                           timeout=2)
        self.assertIn("ping", call[0][2])
        self.assertIn(("conv_close", link, conv, "link_lost"), self.handler.calls)
        self.assertNotIn("miko-test", self.server.links)
        self.assertTrue(link.closed)
        self.assertIsNone(link.send("status", model_ok=True))

    async def test_peer_close_ends_conversation_with_link_lost(self):
        robot, link, conv = await self.conversing()
        await robot.close()
        await self.handler.wait_for(lambda: self.handler.named("link_closed"))
        self.assertIn(("conv_close", link, conv, "link_lost"), self.handler.calls)


class StubTests(LaneTestCase):
    def make_handler(self):
        return StubHandler(reply_pcm=tone(0.4), reply_delay=0.0, unknown_cmd="wave")

    async def test_stub_answers_and_plays_paced_reply(self):
        robot = self.robot()
        welcome = await robot.connect()
        self.assertIs(welcome["model_ok"], True)
        conv = await robot.open_conv(turn_taking="strict")
        await robot.wait_text("conv.ready")
        await robot.wait_text("reply")
        await robot.wait_for(lambda: len(robot.binaries()) == 5)  # 0.4 s tone
        self.assertTrue(all(len(f.data) == REPLY_CHUNK_BYTES for f in robot.binaries()))
        cmd = (await robot.wait_text("cmd")).data  # AE8 exercise
        result = await poll(lambda: self.handler.cmd_results)
        self.assertEqual((result[0]["re"], result[0]["status"]), (cmd["id"], "unsupported"))
        link = self.server.links["miko-test"]
        link.close_conv("sleep_word")
        close = (await robot.wait_text("conv.close")).data
        self.assertEqual((close["conv"], close["reason"]), (conv, "sleep_word"))

    async def test_stub_flush_on_request(self):
        self.handler.reply_pcm = tone(3.0)
        robot, link, _ = await self.conversing()
        await robot.wait_for(lambda: len(robot.binaries()) >= 6)
        self.assertTrue(self.handler.flush(link))
        flush = (await robot.wait_text("audio.flush")).data
        self.assertEqual(flush["reply"], robot.texts("reply")[0].data["id"])

    def test_load_wav_mixes_to_mono_and_resamples(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "stereo16k.wav"
            with wave.open(str(path), "wb") as w:
                w.setnchannels(2)
                w.setsampwidth(2)
                w.setframerate(16000)
                w.writeframes((pcm(1, 1000) + pcm(1, 3000)) * 16000)  # 1 s, L=1000 R=3000
            out = load_wav(path)
        self.assertAlmostEqual(len(out) / 2, 22050, delta=2)
        self.assertEqual(out[:4], pcm(2, 2000))


if __name__ == "__main__":
    unittest.main()
