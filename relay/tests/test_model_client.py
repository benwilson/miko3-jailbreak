"""Tests for relay/relay/model_client.py against the scripted fake model server.

Covers plan U4's scenarios: `reset` then `system` before any audio, the frame clock that
paces the uplink at realtime (one frame per frame period, zero-filled when nothing is
queued -- bursts and the open-time backlog included), ordered reply events with the
audio bytes intact, the running user transcript, error and takeover closes, persona
validation, and the health probe KTD4 uses. Also the verified server contract: events keyed
by `kind`, assistant text in `delta`, and the `system` ack that gates a ready session.
Timelines are kept short so the file runs in a few seconds.
"""
import asyncio
import sys
import unittest
from pathlib import Path

RELAY_ROOT = Path(__file__).resolve().parents[1]
if str(RELAY_ROOT) not in sys.path:
    sys.path.insert(0, str(RELAY_ROOT))

from relay.model_client import (  # noqa: E402
    DEFAULT_PATH,
    FRAME_SECONDS,
    ZERO_FRAME,
    AgentEnd,
    AgentStart,
    AssistantTextDelta,
    AudioChunk,
    Closed,
    Flush,
    ModelClient,
    ModelError,
    ModelWarning,
    ProbeError,
    Stats,
    SystemAck,
    SystemAckTimeout,
    UserEnd,
    UserStart,
    UserText,
    UserTextDelta,
    model_reachable,
    model_state,
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


def after_ack(events):
    """The conversation proper: what follows the persona being read in (system_start,
    progress, the `system` ack), which starts every session."""
    for i, event in enumerate(events):
        if isinstance(event, SystemAck):
            return events[i + 1:]
    return events


async def collect_until(client, predicate, timeout=3.0):
    events = []
    async with asyncio.timeout(timeout):
        async for event in client.events():
            events.append(event)
            if predicate(event):
                return events
    return events


class ClientTestCase(unittest.IsolatedAsyncioTestCase):
    """Opens clients against a fake server and closes them all at the end."""

    async def asyncSetUp(self):
        self.clients = []

    async def asyncTearDown(self):
        for client in self.clients:
            await client.close()

    def new_client(self, server, **kwargs):
        client = ModelClient("127.0.0.1", server.port, persona=PERSONA, **kwargs)
        self.clients.append(client)
        return client

    async def open_client(self, server, backlog=()):
        client = self.new_client(server)
        await client.open(backlog=backlog)
        return client


class ModelClientTests(ClientTestCase):

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

    async def test_chunks_of_any_size_are_forwarded_intact_and_in_order(self):
        # Uplink frames may be any whole number of samples: 50 ms here, 800 samples. The
        # frame clock advances by the audio duration of the frame it just sent, not by a
        # flat 80 ms, so a robot handing over 50 ms of audio every 50 ms is keeping up
        # exactly and nothing of it is dropped. A slot its chunk has not reached yet still
        # gets a zero frame (round 9: one frame per slot, never two).
        async with FakeModelServer() as server:
            client = await self.open_client(server)
            sent = []
            for i in range(8):
                chunk = pcm(800, i + 1)
                sent.append(chunk)
                client.send_audio(chunk)
                await asyncio.sleep(0.05)
            session = server.last_session
            real = lambda: [f.data for f in session.binaries() if f.data != ZERO_FRAME]  # noqa: E731
            await server.wait_until(lambda: len(real()) >= 8, timeout=3.0)
            self.assertEqual(real(), sent)
            self.assertEqual(client.uplink_dropped, 0)

    async def test_burst_of_five_chunks_is_released_in_order_at_the_frame_rate(self):
        # Round 9: a burst is queued and released one frame per FRAME_SECONDS. It used to
        # go out at once, which is how the model's input backlog outran realtime.
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
            self.assertGreater(arrived[-1].t - t_sent, 4 * FRAME_SECONDS * 0.8)

    async def test_backlog_at_open_is_released_at_the_frame_rate(self):
        backlog = [pcm(1280, 200 + i) for i in range(10)]  # 800 ms of buffered speech
        async with FakeModelServer() as server:
            await self.open_client(server, backlog=backlog)
            session = server.last_session
            await server.wait_until(lambda: len(session.binaries()) >= 10, timeout=4.0)
            frames = session.binaries()[:10]
            self.assertEqual([f.data for f in frames], backlog)
            # 800 ms of recorded audio takes 800 ms to hand over, not 800 ms of instant lag.
            self.assertGreater(frames[-1].t - frames[0].t, 9 * FRAME_SECONDS * 0.8)

    async def test_reply_events_in_order_with_audio_bytes_intact(self):
        script = Script(turns=[Turn(user_end_at=0.05, reply_delay=0.05, reply_seconds=0.24)], pace=0.1)
        async with FakeModelServer(script) as server:
            client = await self.open_client(server)
            events = after_ack(await collect_until(client, lambda e: isinstance(e, AgentEnd)))
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
            events = after_ack(await collect(client))
            self.assertEqual(len(events), 1)  # nothing but the end after the ack
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
            self.assertEqual(status["kind"], "status")
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


class PathTests(ClientTestCase):
    """The server's WebSocket lives at /ws; / is the browser page and fails the handshake."""

    async def test_client_targets_the_ws_path_by_default(self):
        self.assertEqual(DEFAULT_PATH, "/ws")
        async with FakeModelServer() as server:
            client = await self.open_client(server)
            self.assertTrue(client.uri.endswith("/ws"), client.uri)
            self.assertEqual(server.last_session.path, "/ws")

    async def test_client_path_is_configurable(self):
        async with FakeModelServer() as server:
            client = ModelClient("127.0.0.1", server.port, persona=PERSONA, path="/other")
            self.clients.append(client)
            await client.open()
            self.assertEqual(server.last_session.path, "/other")

    async def test_probe_targets_the_ws_path_by_default(self):
        async with FakeModelServer() as server:
            await probe("127.0.0.1", server.port, timeout=1.0)
            self.assertEqual(server.last_session.path, "/ws")


class EventKeyTests(ClientTestCase):
    """The real server keys its events by `kind`; `type` is only a fallback for old fixtures."""

    async def test_event_keyed_by_kind_is_parsed(self):
        script = Script(events=[(0.02, {"kind": "warning", "message": "backlog high"})],
                        turns=[Turn(user_end_at=0.06, reply_delay=None)])
        async with FakeModelServer(script) as server:
            client = await self.open_client(server)
            events = await collect_until(client, lambda e: isinstance(e, UserEnd))
            self.assertEqual([e.message for e in events if isinstance(e, ModelWarning)],
                             ["backlog high"])

    async def test_event_keyed_by_type_is_still_parsed(self):
        script = Script(events=[(0.02, {"type": "warning", "message": "old fixture"})],
                        turns=[Turn(user_end_at=0.06, reply_delay=None)])
        async with FakeModelServer(script) as server:
            client = await self.open_client(server)
            events = await collect_until(client, lambda e: isinstance(e, UserEnd))
            self.assertEqual([e.message for e in events if isinstance(e, ModelWarning)],
                             ["old fixture"])

    async def test_kind_wins_over_a_stale_type_on_the_same_frame(self):
        script = Script(events=[(0.02, {"kind": "warning", "type": "error", "message": "m"})],
                        turns=[Turn(user_end_at=0.06, reply_delay=None)])
        async with FakeModelServer(script) as server:
            client = await self.open_client(server)
            events = await collect_until(client, lambda e: isinstance(e, UserEnd))
            self.assertTrue(any(isinstance(e, ModelWarning) for e in events))
            self.assertFalse(any(isinstance(e, ModelError) for e in events))

    async def test_assistant_delta_text_comes_from_delta(self):
        script = Script(turns=[Turn(user_end_at=0.02, reply_delay=0.02, reply_seconds=0.08,
                                    reply_text=["It's ", "sunny"])], pace=0.1)
        async with FakeModelServer(script) as server:
            client = await self.open_client(server)
            events = await collect_until(client, lambda e: isinstance(e, AgentEnd))
            deltas = [e for e in events if isinstance(e, AssistantTextDelta)]
            self.assertEqual([d.text for d in deltas], ["It's ", "sunny"])

    async def test_assistant_delta_falls_back_to_text(self):
        script = Script(events=[(0.02, {"kind": "assistant_text_delta", "text": "hello"})],
                        turns=[Turn(user_end_at=0.06, reply_delay=None)])
        async with FakeModelServer(script) as server:
            client = await self.open_client(server)
            events = await collect_until(client, lambda e: isinstance(e, UserEnd))
            deltas = [e for e in events if isinstance(e, AssistantTextDelta)]
            self.assertEqual([d.text for d in deltas], ["hello"])


class UplinkFrameClockTests(ClientTestCase):
    """Round 9. Measured live against the real server, `stats.input_backlog_frames` climbed
    3 -> 7 -> 14 -> 20 -> 22 -> 28 while the server itself was FASTER than realtime (rtf
    0.70-0.76). The only way that happens is that the relay handed it more than one 80 ms
    frame per 80 ms of wall clock: chunks were forwarded the instant they arrived AND the
    watchdog filled the slot a jittery late chunk had not arrived for, so a late chunk cost
    two frames for one slot. These pin the frame clock that replaced that."""

    async def rate(self, session, seconds):
        """Frames the model received per frame period over `seconds` of wall clock."""
        loop = asyncio.get_running_loop()
        t0 = loop.time()
        before = len(session.binaries())
        await asyncio.sleep(seconds)
        elapsed = loop.time() - t0
        return (len(session.binaries()) - before) / (elapsed / FRAME_SECONDS)

    async def test_twenty_chunks_fed_instantly_arrive_spread_at_the_frame_rate(self):
        async with FakeModelServer() as server:
            client = await self.open_client(server)
            await asyncio.sleep(0.02)
            burst = [pcm(1280, 300 + i) for i in range(20)]
            for chunk in burst:
                client.send_audio(chunk)
            session = server.last_session
            await server.wait_until(
                lambda: sum(f.data != ZERO_FRAME for f in session.binaries()) >= 20,
                timeout=6.0)
            arrived = [f for f in session.binaries() if f.data != ZERO_FRAME]
            self.assertEqual([f.data for f in arrived], burst)  # in order, none dropped
            span = arrived[-1].t - arrived[0].t
            # 20 frames is 1.6 s of audio and must take about 1.6 s of wall clock to hand
            # over, not the ~0 s the unpaced uplink took.
            self.assertGreater(span, 19 * FRAME_SECONDS * 0.8)
            self.assertLess(span, 19 * FRAME_SECONDS * 1.6)
            gaps = [b.t - a.t for a, b in zip(arrived, arrived[1:])]
            self.assertLess(max(gaps), 3 * FRAME_SECONDS, gaps)

    async def test_one_zero_frame_per_frame_period_with_no_input(self):
        async with FakeModelServer() as server:
            client = await self.open_client(server)
            session = server.last_session
            rate = await self.rate(session, 0.6)
            self.assertTrue(all(f.data == ZERO_FRAME for f in session.binaries()))
            self.assertTrue(0.8 <= rate <= 1.2, rate)
            self.assertEqual(client.chunks_sent, 0)

    async def test_jitter_never_buys_a_second_frame_for_one_slot(self):
        # The live failure, driven explicitly: chunks arrive early, late and in pairs. The
        # model must still receive exactly one frame per frame period.
        jitter = [0.02, 0.14, 0.0, 0.11, 0.05, 0.16, 0.03, 0.09, 0.13, 0.02, 0.15, 0.06]
        async with FakeModelServer() as server:
            client = await self.open_client(server)
            session = server.last_session
            await asyncio.sleep(0.02)
            loop = asyncio.get_running_loop()
            t0 = loop.time()
            before = len(session.binaries())
            for i, gap in enumerate(jitter):
                await asyncio.sleep(gap)
                client.send_audio(pcm(1280, 400 + i))
            elapsed = loop.time() - t0
            got = len(session.binaries()) - before
            expected = elapsed / FRAME_SECONDS
            rate = got / expected
            self.assertTrue(0.85 <= rate <= 1.15, f"{got} frames in {expected:.1f} slots")
            self.assertTrue(0.85 <= client.send_rate <= 1.15, client.send_rate)

    async def test_release_queue_bound_drops_the_oldest_and_counts_it(self):
        async with FakeModelServer() as server:
            client = self.new_client(server, uplink_queue=4)
            await client.open()
            session = server.last_session
            for i in range(10):  # 4 fit, the first 6 are pushed off the front
                client.send_audio(pcm(1280, 500 + i))
            self.assertEqual(client.uplink_dropped, 6)
            await server.wait_until(
                lambda: sum(f.data != ZERO_FRAME for f in session.binaries()) >= 4, timeout=3.0)
            kept = [f.data for f in session.binaries() if f.data != ZERO_FRAME]
            self.assertEqual(kept[:4], [pcm(1280, 506 + i) for i in range(4)])  # newest kept
            stats = client.uplink_stats()
            self.assertEqual(stats["dropped"], 6)
            self.assertEqual(stats["queued"], 10)
            self.assertEqual(stats["sent"], 4)

    async def test_the_pre_ready_backlog_is_paced_like_everything_else(self):
        # DECISION: the robot's pre-`conv.ready` buffer is released through the same frame
        # clock. Dumping it would put the model that many frames behind for the rest of the
        # conversation, which is the failure this round exists to end.
        backlog = [pcm(1280, 600 + i) for i in range(6)]
        async with FakeModelServer() as server:
            client = self.new_client(server)
            await client.open(quiet=True)
            session = server.last_session
            loop = asyncio.get_running_loop()
            t0 = loop.time()
            client.start_uplink(backlog=backlog)
            await server.wait_until(
                lambda: sum(f.data != ZERO_FRAME for f in session.binaries()) >= 6, timeout=3.0)
            arrived = [f for f in session.binaries() if f.data != ZERO_FRAME]
            self.assertEqual([f.data for f in arrived], backlog)
            self.assertGreater(arrived[-1].t - t0, 5 * FRAME_SECONDS * 0.8)

    async def test_uplink_stats_report_the_real_send_rate(self):
        async with FakeModelServer() as server:
            client = await self.open_client(server)
            for i in range(6):
                client.send_audio(pcm(1280, 700 + i))
                await asyncio.sleep(0.05)  # the robot running ahead of the clock
            await asyncio.sleep(0.3)
            stats = client.uplink_stats()
            self.assertEqual(stats["sent"] + stats["zero"], client.frames_sent)
            self.assertTrue(0.85 <= stats["rate"] <= 1.15, stats)
            self.assertGreaterEqual(stats["queue_peak"], 1)


class QuietSessionTests(ClientTestCase):
    """A session opened `quiet` (the relay's warm one) is silent until it is adopted. The
    zero-frame watchdog exists so the model's VAD hears the person stop talking; before a
    conversation there is nobody to hear, and this server runs slower than realtime
    (measured rtf 1.8-3.3), so every zero frame only lengthens its input backlog."""

    async def test_a_quiet_session_sends_nothing_until_the_uplink_starts(self):
        async with FakeModelServer() as server:
            client = self.new_client(server)
            await client.open(quiet=True)
            session = server.last_session
            self.assertTrue(client.system_acked)  # the persona is in: adoption is instant
            self.assertFalse(client.ready)
            client.send_audio(pcm(1280, 5))  # held, not forwarded
            await asyncio.sleep(0.3)  # several 80 ms watchdog intervals
            self.assertEqual(session.binaries(), [])
            self.assertEqual(client.zero_frames_sent, 0)
            self.assertEqual(client.chunks_sent, 0)
            client.start_uplink()
            self.assertTrue(client.ready)
            await server.wait_until(lambda: len(session.binaries()) >= 3)
            frames = [f.data for f in session.binaries()]
            self.assertEqual(frames[0], pcm(1280, 5))  # what was held, first and in order
            self.assertEqual(frames[1:], [ZERO_FRAME] * (len(frames) - 1))  # then watchdog
            self.assertGreaterEqual(client.zero_frames_sent, 2)

    async def test_starting_the_uplink_forwards_a_backlog_before_later_audio(self):
        async with FakeModelServer() as server:
            client = self.new_client(server)
            await client.open(quiet=True)
            session = server.last_session
            client.start_uplink(backlog=[pcm(1280, 1), pcm(1280, 2)])
            client.send_audio(pcm(1280, 3))
            await server.wait_until(lambda: len(session.binaries()) >= 3)
            self.assertEqual([f.data for f in session.binaries()][:3],
                             [pcm(1280, 1), pcm(1280, 2), pcm(1280, 3)])


class SystemAckTests(ClientTestCase):
    """open() is not ready until the child has finished reading the persona in."""

    async def test_open_is_not_ready_before_the_ack_and_is_after(self):
        async with FakeModelServer(Script(system_ack_delay=0.4)) as server:
            client = self.new_client(server)
            opening = asyncio.create_task(client.open())
            session = await server.wait_until(lambda: server.last_session)
            await asyncio.sleep(0.2)
            self.assertFalse(opening.done())
            self.assertFalse(client.ready)
            self.assertEqual(session.binaries(), [])  # not even a watchdog frame yet
            await asyncio.wait_for(opening, 2.0)
            self.assertTrue(client.ready)
            self.assertGreaterEqual(client.system_ms, 350)

    async def test_ack_is_an_observable_event_before_the_first_turn(self):
        async with FakeModelServer(Script(turns=[Turn(user_end_at=0.05, reply_delay=None)])) as server:
            client = await self.open_client(server)
            events = await collect_until(client, lambda e: isinstance(e, UserEnd))
            kinds = [type(e) for e in events]
            self.assertEqual(kinds.count(SystemAck), 1)
            self.assertLess(kinds.index(SystemAck), kinds.index(UserStart))

    async def test_system_start_and_progress_do_not_count_as_the_ack(self):
        # The fake sends system_start and progress before the ack, as the child does.
        async with FakeModelServer(Script(system_ack_delay=0.3)) as server:
            client = self.new_client(server)
            opening = asyncio.create_task(client.open())
            await asyncio.sleep(0.15)
            session = server.last_session
            sent = [m.get("kind") for m in session.sent]
            self.assertEqual(sent, ["system_start", "progress"])
            self.assertFalse(opening.done())
            await asyncio.wait_for(opening, 2.0)
            self.assertTrue(client.ready)

    async def test_slow_ack_past_the_timeout_raises(self):
        async with FakeModelServer(Script(system_ack_delay=1.5)) as server:
            client = self.new_client(server, system_timeout=0.2)
            with self.assertRaises(SystemAckTimeout) as caught:
                await client.open()
            self.assertIn("0.2", str(caught.exception))
            self.assertFalse(client.ready)

    async def test_no_ack_at_all_raises(self):
        async with FakeModelServer(Script(system_ack=False)) as server:
            client = self.new_client(server, system_timeout=0.2)
            with self.assertRaises(SystemAckTimeout):
                await client.open()

    async def test_audio_handed_over_before_the_ack_is_kept_and_sent_in_order(self):
        backlog = [pcm(1280, 100 + i) for i in range(3)]
        early = [pcm(1280, 200 + i) for i in range(4)]
        async with FakeModelServer(Script(system_ack_delay=0.35)) as server:
            client = self.new_client(server)
            opening = asyncio.create_task(client.open(backlog=backlog))
            await asyncio.sleep(0.1)
            for chunk in early:
                client.send_audio(chunk)
            await asyncio.wait_for(opening, 2.0)
            session = server.last_session
            await server.wait_until(lambda: len(session.binaries()) >= 7)
            self.assertEqual([f.data for f in session.binaries()[:7]], backlog + early)


class ProbeStateTests(ClientTestCase):

    async def test_ready_state_is_reachable(self):
        async with FakeModelServer(Script(status_state="ready")) as server:
            status = await probe("127.0.0.1", server.port, timeout=1.0)
            self.assertEqual(model_state(status), "ready")
            self.assertTrue(model_reachable(status))

    async def test_loading_state_is_not_reachable(self):
        async with FakeModelServer(Script(status_state="loading")) as server:
            status = await probe("127.0.0.1", server.port, timeout=1.0)
            self.assertEqual(model_state(status), "loading")
            self.assertFalse(model_reachable(status))

    async def test_dead_state_is_not_reachable(self):
        async with FakeModelServer(Script(status_state="dead")) as server:
            status = await probe("127.0.0.1", server.port, timeout=1.0)
            self.assertEqual(model_state(status), "dead")
            self.assertFalse(model_reachable(status))


if __name__ == "__main__":
    unittest.main()
