"""Scripted stand-in for the owner's model server (webchat/server.py), for relay tests.

Speaks the protocol in relay/docs/model-server-protocol.md: JSON text frames and raw PCM
binary frames over one WebSocket, single client (a new connection evicts the previous one
the way the real server does). What it sends is a scripted timeline, so relay tests are
deterministic:

    script = Script(turns=[Turn(user_end_at=0.05, user_text=["hi ", "there"],
                                reply_delay=0.05, reply_seconds=0.24)],
                    pace=0.1, flush_rms=2000)
    async with FakeModelServer(script) as server:
        client = ModelClient("127.0.0.1", server.port, persona="...")

Timeline times are seconds after the session's `system` frame arrives (a connection that
never sends `system`, like a health probe, runs no timeline). Everything the client sends is
recorded on the session (`server.last_session.received`) with arrival times.
"""
import asyncio
import json
import math
from array import array
from dataclasses import dataclass, field

from websockets.asyncio.server import serve
from websockets.exceptions import ConnectionClosed

OUTPUT_RATE = 22050  # the real server's reply PCM: 16-bit mono 22.05 kHz
TAKEOVER_CLOSE_CODE = 1000  # assumption: the real server's close code on takeover is unknown
TAKEOVER_CLOSE_REASON = "replaced by a new client"


@dataclass
class Turn:
    """One user turn and the model's reply to it."""
    user_end_at: float
    # Deltas sent evenly between user_start and user_end, then one user_text with their
    # concatenation at user_end. None models today's server, which sends no transcript (U3).
    user_text: list[str] | None = None
    user_start_at: float | None = None  # default: 0.1 s before user_end_at
    reply_delay: float = 0.4  # agent_start this long after user_end; None = never reply
    reply_seconds: float = 0.4
    reply_text: list[str] = field(default_factory=list)  # assistant_text_delta after agent_start


@dataclass
class Script:
    turns: list[Turn] = field(default_factory=list)
    chunk_ms: int = 80  # reply audio chunk duration
    pace: float = 1.0  # reply chunk spacing as a fraction of real time; 0 = as fast as possible
    flush_rms: float | None = None  # uplink RMS that interrupts an active reply (None = never)
    stats_every: float | None = None  # seconds between stats frames (None = none)
    stats_backlog: float = 0.0  # input backlog reported in stats, seconds
    stats_speech_queue: int = 0
    events: list[tuple[float, dict]] = field(default_factory=list)  # raw JSON frames at time t
    close_at: float | None = None  # server closes the socket at t, as a takeover would
    status_reply: bool = True  # answer the client's `status` request
    status_delay: float = 0.0


def reply_chunks(script, turn_index):
    """The exact reply PCM chunks the fake sends for a turn, so tests can check bytes."""
    turn = script.turns[turn_index]
    samples = OUTPUT_RATE * script.chunk_ms // 1000
    count = max(1, round(turn.reply_seconds * 1000 / script.chunk_ms))
    chunks = []
    for i in range(count):
        base = (turn_index * 7919 + i * 131) % 20000
        pcm = array("h", ((base + k) % 20000 - 10000 for k in range(samples)))
        chunks.append(pcm.tobytes())
    return chunks


def rms(pcm):
    """RMS of 16-bit little-endian PCM (host order is little-endian on every test machine)."""
    samples = array("h")
    samples.frombytes(pcm[: len(pcm) - len(pcm) % 2])
    if not samples:
        return 0.0
    return math.sqrt(sum(s * s for s in samples) / len(samples))


@dataclass
class Frame:
    t: float  # loop.time() at arrival
    kind: str  # "text" or "binary"
    data: object  # dict for text, bytes for binary


class Session:
    """One client connection: what it sent and the timeline played to it."""

    def __init__(self, server, ws, script):
        self.server = server
        self.ws = ws
        self.script = script
        self.received: list[Frame] = []
        self.sent: list[dict] = []  # JSON frames the fake sent, in order
        self.reply_bytes_sent = 0
        self.flushes = 0
        self._tasks: set[asyncio.Task] = set()
        self._reply_task: asyncio.Task | None = None
        self._flushed_current = False

    def texts(self):
        return [f.data for f in self.received if f.kind == "text"]

    def binaries(self):
        return [f for f in self.received if f.kind == "binary"]

    async def _send_json(self, message):
        self.sent.append(message)
        await self.ws.send(json.dumps(message))

    def _spawn(self, coro):
        task = asyncio.create_task(coro)
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)
        return task

    async def run(self):
        try:
            async for message in self.ws:
                loop = asyncio.get_running_loop()
                if isinstance(message, bytes):
                    self.received.append(Frame(loop.time(), "binary", message))
                    await self._on_audio(message)
                else:
                    data = json.loads(message)
                    self.received.append(Frame(loop.time(), "text", data))
                    await self._on_text(data)
                self.server._notify()
        except ConnectionClosed:
            pass
        finally:
            for task in list(self._tasks):
                task.cancel()
            self.server._notify()

    async def _on_text(self, data):
        kind = data.get("type")
        if kind == "system":
            self._spawn(self._timeline())
        elif kind == "status" and self.script.status_reply:
            self._spawn(self._status())

    async def _status(self):
        await asyncio.sleep(self.script.status_delay)
        await self._send_json({"type": "status", "state": "ready"})

    async def _on_audio(self, pcm):
        script = self.script
        if (script.flush_rms is not None and self._reply_task is not None
                and not self._reply_task.done() and not self._flushed_current
                and rms(pcm) > script.flush_rms):
            self._flushed_current = True
            self._reply_task.cancel()
            self.flushes += 1
            await self._send_json({"type": "flush"})
            await self._send_json({"type": "agent_end"})

    async def _at(self, t0, t, coro_fn):
        loop = asyncio.get_running_loop()
        await asyncio.sleep(max(0.0, t0 + t - loop.time()))
        await coro_fn()

    async def _timeline(self):
        loop = asyncio.get_running_loop()
        t0 = loop.time()
        script = self.script
        for index, turn in enumerate(script.turns):
            self._spawn(self._turn(t0, index, turn))
        for t, message in script.events:
            self._spawn(self._at(t0, t, lambda m=message: self._send_json(m)))
        if script.close_at is not None:
            self._spawn(self._at(t0, script.close_at,
                                 lambda: self.ws.close(TAKEOVER_CLOSE_CODE, TAKEOVER_CLOSE_REASON)))
        if script.stats_every:
            self._spawn(self._stats())

    async def _turn(self, t0, index, turn):
        loop = asyncio.get_running_loop()
        start = turn.user_start_at if turn.user_start_at is not None else max(0.0, turn.user_end_at - 0.1)
        await asyncio.sleep(max(0.0, t0 + start - loop.time()))
        await self._send_json({"type": "user_start"})
        deltas = turn.user_text or []
        step = (turn.user_end_at - start) / (len(deltas) + 1)
        for i, delta in enumerate(deltas):
            await asyncio.sleep(max(0.0, t0 + start + step * (i + 1) - loop.time()))
            await self._send_json({"type": "user_text_delta", "text": delta})
        await asyncio.sleep(max(0.0, t0 + turn.user_end_at - loop.time()))
        await self._send_json({"type": "user_end"})
        if turn.user_text is not None:
            await self._send_json({"type": "user_text", "text": "".join(turn.user_text)})
        if turn.reply_delay is None:
            return
        await asyncio.sleep(max(0.0, t0 + turn.user_end_at + turn.reply_delay - loop.time()))
        self._flushed_current = False
        self._reply_task = self._spawn(self._reply(index, turn))

    async def _reply(self, index, turn):
        await self._send_json({"type": "agent_start"})
        for text in turn.reply_text:
            await self._send_json({"type": "assistant_text_delta", "text": text})
        spacing = self.script.chunk_ms / 1000 * self.script.pace
        for chunk in reply_chunks(self.script, index):
            await self.ws.send(chunk)
            self.reply_bytes_sent += len(chunk)
            await asyncio.sleep(spacing)
        await self._send_json({"type": "agent_end"})

    async def _stats(self):
        while True:
            await asyncio.sleep(self.script.stats_every)
            await self._send_json({"type": "stats", "input_backlog": self.script.stats_backlog,
                                   "speech_queue": self.script.stats_speech_queue})


class FakeModelServer:
    """Binds 127.0.0.1 on an ephemeral port. Use as an async context manager."""

    def __init__(self, script=None):
        self.script = script or Script()
        self.sessions: list[Session] = []
        self.port = None
        self._server = None
        self._active: Session | None = None
        self._changed = asyncio.Event()

    @property
    def last_session(self):
        return self.sessions[-1] if self.sessions else None

    async def __aenter__(self):
        self._server = await serve(self._handle, "127.0.0.1", 0, compression=None)
        self.port = self._server.sockets[0].getsockname()[1]
        return self

    async def __aexit__(self, *exc):
        self._server.close()
        await self._server.wait_closed()

    async def _handle(self, ws):
        previous = self._active
        if previous is not None:
            await previous.ws.close(TAKEOVER_CLOSE_CODE, TAKEOVER_CLOSE_REASON)
        session = Session(self, ws, self.script)
        self.sessions.append(session)
        self._active = session
        self._notify()
        try:
            await session.run()
        finally:
            if self._active is session:
                self._active = None

    def _notify(self):
        self._changed.set()

    async def wait_until(self, predicate, timeout=2.0):
        """Wait until predicate() is truthy, re-checking on every frame; returns its value."""
        async with asyncio.timeout(timeout):
            while True:
                value = predicate()
                if value:
                    return value
                self._changed.clear()
                await self._changed.wait()
