"""Tests for relay/relay/model_client.py against the scripted fake model server.

Covers plan U4's scenarios: `reset` then `system` before any audio, the 80 ms zero-frame
watchdog, bursts and the open-time backlog forwarded unpaced, ordered reply events with the
audio bytes intact, the running user transcript, error and takeover closes, persona
validation, and the health probe KTD4 uses. Timelines are kept short so the file runs in a
few seconds.
"""
import asyncio
import sys
import unittest
from pathlib import Path

RELAY_ROOT = Path(__file__).resolve().parents[1]
if str(RELAY_ROOT) not in sys.path:
    sys.path.insert(0, str(RELAY_ROOT))

from relay.model_client import (  # noqa: E402
    ZERO_FRAME,
    AgentEnd,
    AgentStart,
    AudioChunk,
    Closed,
    Flush,
    ModelClient,
    ModelError,
    ModelWarning,
    ProbeError,
    Stats,
    UserEnd,
    UserStart,
    UserText,
    UserTextDelta,
    probe,
)
from tests.fake_model_server import FakeModelServer, Script, Turn, reply_chunks  # noqa: E402

PERSONA = "You are Miko, a friendly robot. Keep replies short."


def pcm(samples, value=0):
    """16-bit LE mono PCM of `samples` samples at a constant value."""
    return int(value).to_bytes(2, "little", signed=True) * samples


async def collect(client, timeout=3.0):
    """All events until the stream ends."""
    events = []
    async with asyncio.timeout(timeout):
        async for event in client.events():
            events.append(event)
    return events


async def collect_until(client, predicate, timeout=3.0):
    events = []
    async with asyncio.timeout(timeout):
        async for event in client.events():
            events.append(event)
            if predicate(event):
                return events
    return events


class ModelClientTests(unittest.IsolatedAsyncioTestCase):

    async def asyncSetUp(self):
        self.clients = []

    async def asyncTearDown(self):
        for client in self.clients:
            await client.close()

    async def open_client(self, server, backlog=()):
        client = ModelClient("127.0.0.1", server.port, persona=PERSONA)
        self.clients.append(client)
        await client.open(backlog=backlog)
        return client

    async def test_reset_then_system_before_any_binary_frame(self):
        async with FakeModelServer() as server:
            client = await self.open_client(server, backlog=[pcm(1280, 5)])
            client.send_audio(pcm(1280, 7))
            session = await server.wait_until(lambda: server.last_session)
            await server.wait_until(lambda: len(session.binaries()) >= 2)
            kinds = [(f.kind, f.data if f.kind == "text" else None) for f in session.received[:3]]
            self.assertEqual(kinds[0], ("text", {"type": "reset"}))
            self.assertEqual(kinds[1], ("text", {"type": "system", "text": PERSONA}))
            self.assertEqual(kinds[2][0], "binary")

    async def test_one_zero_frame_per_80ms_with_no_input(self):
        async with FakeModelServer() as server:
            await self.open_client(server)
            await asyncio.sleep(0.45)
            frames = server.last_session.binaries()
            self.assertTrue(all(f.data == ZERO_FRAME for f in frames))
            self.assertEqual(len(ZERO_FRAME), 2560)
            self.assertTrue(4 <= len(frames) <= 6, len(frames))
            gaps = [b.t - a.t for a, b in zip(frames, frames[1:])]
            self.assertTrue(all(0.06 <= g <= 0.12 for g in gaps), gaps)

    async def test_no_zero_frames_while_chunks_arrive_faster_than_80ms(self):
        # Uplink frames may be any whole number of samples: 50 ms here, 800 samples.
        async with FakeModelServer() as server:
            client = await self.open_client(server)
            sent = []
            for i in range(8):
                chunk = pcm(800, i + 1)
                sent.append(chunk)
                client.send_audio(chunk)
                await asyncio.sleep(0.05)
            session = server.last_session
            await server.wait_until(lambda: len(session.binaries()) >= 8)
            self.assertEqual([f.data for f in session.binaries()], sent)
            self.assertEqual(client.zero_frames_sent, 0)

    async def test_burst_of_five_chunks_forwarded_at_once(self):
        async with FakeModelServer() as server:
            client = await self.open_client(server)
            await asyncio.sleep(0.02)
            loop = asyncio.get_running_loop()
            t_sent = loop.time()
            burst = [pcm(1280, 100 + i) for i in range(5)]
            for chunk in burst:
                client.send_audio(chunk)
            session = server.last_session
            await server.wait_until(lambda: sum(f.data != ZERO_FRAME for f in session.binaries()) >= 5)
            arrived = [f for f in session.binaries() if f.data != ZERO_FRAME]
            self.assertEqual([f.data for f in arrived], burst)
            self.assertLess(arrived[-1].t - t_sent, 0.05)

    async def test_backlog_at_open_forwarded_without_pacing(self):
        backlog = [pcm(1280, 200 + i) for i in range(10)]  # 800 ms of buffered speech
        async with FakeModelServer() as server:
            await self.open_client(server, backlog=backlog)
            session = server.last_session
            await server.wait_until(lambda: len(session.binaries()) >= 10)
            frames = session.binaries()[:10]
            self.assertEqual([f.data for f in frames], backlog)
            self.assertLess(frames[-1].t - frames[0].t, 0.05)

    async def test_reply_events_in_order_with_audio_bytes_intact(self):
        script = Script(turns=[Turn(user_end_at=0.05, reply_delay=0.05, reply_seconds=0.24)], pace=0.1)
        async with FakeModelServer(script) as server:
            client = await self.open_client(server)
            events = await collect_until(client, lambda e: isinstance(e, AgentEnd))
            kinds = [type(e) for e in events]
            self.assertEqual(kinds[:3], [UserStart, UserEnd, AgentStart])
            self.assertEqual(kinds[-1], AgentEnd)
            audio = [e for e in events[3:-1]]
            self.assertTrue(all(isinstance(e, AudioChunk) for e in audio))
            self.assertEqual(b"".join(e.pcm for e in audio), b"".join(reply_chunks(script, 0)))
            self.assertEqual(audio[0].sample_rate, 22050)

    async def test_user_text_deltas_build_transcript_and_user_text_finalizes(self):
        script = Script(turns=[Turn(user_end_at=0.08, user_text=["what ", "is the ", "weather"],
                                    reply_delay=None)])
        async with FakeModelServer(script) as server:
            client = await self.open_client(server)
            events = await collect_until(client, lambda e: isinstance(e, UserText))
            deltas = [e for e in events if isinstance(e, UserTextDelta)]
            self.assertEqual([d.text for d in deltas], ["what ", "is the ", "weather"])
            self.assertEqual([d.transcript for d in deltas],
                             ["what ", "what is the ", "what is the weather"])
            self.assertEqual(events[-1].text, "what is the weather")
            self.assertEqual(client.transcript, "")  # finalized: the next turn starts fresh

    async def test_server_error_closes_stream_with_code(self):
        script = Script(events=[(0.05, {"type": "error", "code": "llm_exit", "message": "child died"})])
        async with FakeModelServer(script) as server:
            client = await self.open_client(server)
            events = await collect(client)
            self.assertIsInstance(events[-2], ModelError)
            self.assertEqual(events[-2].code, "llm_exit")
            self.assertEqual(events[-2].message, "child died")
            self.assertIsInstance(events[-1], Closed)
            self.assertEqual(events[-1].code, "llm_exit")
            self.assertTrue(events[-1].error)
            self.assertTrue(client.closed)

    async def test_warning_is_surfaced_without_closing(self):
        script = Script(events=[(0.02, {"type": "warning", "message": "backlog high"})],
                        turns=[Turn(user_end_at=0.06, reply_delay=None)])
        async with FakeModelServer(script) as server:
            client = await self.open_client(server)
            events = await collect_until(client, lambda e: isinstance(e, UserEnd))
            warnings = [e for e in events if isinstance(e, ModelWarning)]
            self.assertEqual([w.message for w in warnings], ["backlog high"])
            self.assertFalse(client.closed)

    async def test_takeover_surfaces_as_close_event_not_exception(self):
        async with FakeModelServer() as server:
            client = await self.open_client(server)
            other = await self.open_client(server)  # a second client evicts the first
            events = await collect(client)
            self.assertIsInstance(events[-1], Closed)
            self.assertFalse(events[-1].error)
            self.assertFalse(other.closed)

    async def test_server_close_at_scripted_time_ends_stream(self):
        async with FakeModelServer(Script(close_at=0.05)) as server:
            client = await self.open_client(server)
            events = await collect(client)
            self.assertIsInstance(events[-1], Closed)
            self.assertEqual(events[-1].code, 1000)
            client.send_audio(pcm(1280))  # no-op after close, never raises

    async def test_client_close_ends_stream(self):
        async with FakeModelServer() as server:
            client = await self.open_client(server)
            await client.close()
            events = await collect(client)
            self.assertEqual(len(events), 1)
            self.assertIsInstance(events[0], Closed)
            self.assertFalse(events[0].error)

    async def test_non_ascii_persona_rejected_before_connecting(self):
        async with FakeModelServer() as server:
            with self.assertRaisesRegex(ValueError, "ASCII"):
                ModelClient("127.0.0.1", server.port, persona="Tu es Miko, un robot très gentil.")
            await asyncio.sleep(0.05)
            self.assertEqual(server.sessions, [])

    async def test_odd_length_chunk_rejected(self):
        async with FakeModelServer() as server:
            client = await self.open_client(server)
            with self.assertRaises(ValueError):
                client.send_audio(b"\x00\x01\x02")

    async def test_flush_on_loud_uplink_interrupts_reply(self):
        script = Script(turns=[Turn(user_end_at=0.02, reply_delay=0.02, reply_seconds=1.6)],
                        pace=0.5, flush_rms=2000)
        async with FakeModelServer(script) as server:
            client = await self.open_client(server)
            events = await collect_until(client, lambda e: isinstance(e, AgentStart))
            client.send_audio(pcm(1280, 8000))
            events += await collect_until(client, lambda e: isinstance(e, AgentEnd))
            kinds = [type(e) for e in events]
            self.assertIn(Flush, kinds)
            self.assertEqual(kinds.index(Flush), len(kinds) - 2)
            self.assertLess(server.last_session.reply_bytes_sent,
                            len(b"".join(reply_chunks(script, 0))))

    async def test_stats_carry_backlog(self):
        async with FakeModelServer(Script(stats_every=0.03, stats_backlog=1.5)) as server:
            client = await self.open_client(server)
            events = await collect_until(client, lambda e: isinstance(e, Stats))
            self.assertEqual(events[-1].data["input_backlog"], 1.5)


class ProbeTests(unittest.IsolatedAsyncioTestCase):

    async def test_probe_returns_status_payload(self):
        async with FakeModelServer() as server:
            status = await probe("127.0.0.1", server.port, timeout=1.0)
            self.assertEqual(status["type"], "status")
            self.assertEqual(server.last_session.texts(), [{"type": "status"}])

    async def test_probe_fails_when_status_never_answered(self):
        async with FakeModelServer(Script(status_reply=False)) as server:
            with self.assertRaises(ProbeError):
                await probe("127.0.0.1", server.port, timeout=0.2)

    async def test_probe_fails_when_status_too_late(self):
        async with FakeModelServer(Script(status_delay=0.5)) as server:
            with self.assertRaises(ProbeError):
                await probe("127.0.0.1", server.port, timeout=0.2)

    async def test_probe_fails_when_nothing_listening(self):
        async with FakeModelServer() as server:
            port = server.port
        with self.assertRaises(ProbeError):
            await probe("127.0.0.1", port, timeout=0.5)

    async def test_probe_evicts_an_open_conversation(self):
        # Documents why KTD4 never overlaps a probe with a conversation connection.
        async with FakeModelServer() as server:
            client = ModelClient("127.0.0.1", server.port, persona=PERSONA)
            await client.open()
            await probe("127.0.0.1", server.port, timeout=1.0)
            events = await collect(client)
            self.assertIsInstance(events[-1], Closed)


if __name__ == "__main__":
    unittest.main()
