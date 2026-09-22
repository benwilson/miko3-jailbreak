"""Scripted stand-in for the owner's model server (webchat/server.py), for relay tests.

Speaks the protocol in relay/docs/model-server-protocol.md: JSON text frames and raw PCM
binary frames over one WebSocket, single client (a new connection evicts the previous one
the way the real server does). Frames it sends are keyed by `kind`, like the real server;
frames scripted by hand in Script.events are sent verbatim, so a test can still use the old
`type` key to exercise the adapter's fallback. What it sends is a scripted timeline, so
relay tests are deterministic:

    script = Script(turns=[Turn(user_end_at=0.05, user_text=["hi ", "there"],
                                reply_delay=0.05, reply_seconds=0.24)],
                    pace=0.1, flush_rms=2000)
    async with FakeModelServer(script) as server:
        client = ModelClient("127.0.0.1", server.port, persona="...")

A turn can stream `assistant_text_delta` through its own reply audio (`reply_phrases`),
the way the real server keeps producing text while it speaks -- a long reply whose phrases
repeat scripts the quantized model's degenerate loop.

A turn can script a slower-than-realtime server's BURSTY reply (`reply_bursts=[Burst(...)]`):
stretches of speech separated by gaps of digital silence, with `assistant_text_delta` still
arriving through the gaps. A turn can also script the real child's TTS lag: `reply_silence_before` seconds of digital
silence on the reply channel between `agent_start` (with its `reply_text` deltas) and the
first speech chunk, and `agent_end=False` for the real server's missing `agent_end`.

Timeline times are seconds after the session's `system` frame arrives (a connection that
never sends `system`, like a health probe, runs no timeline). `system` is answered the way
the real child answers it: `system_start`, a `progress` frame, then the `system` ack
`system_ack_delay` seconds later (the real cost is about 80 ms per persona word, so a test
that cares about a slow persona read scripts a long delay). Everything the client sends is
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
class Burst:
    """One stretch of a bursty reply, the way a slower-than-realtime server sends one:
    `silence` seconds of digital silence while the server computes, then `speech` seconds
    of audio. Its `text` deltas are spread evenly through the silence (the model's text
    runs ahead of its TTS, so it keeps talking in text while the audio has not caught up);
    with no silence they go out at the head of the burst."""
    silence: float = 0.0
    speech: float = 0.0
    text: list[str] = field(default_factory=list)
    # Wall-clock delivery rate for THIS burst's chunks, overriding Script.pace (0 = as
    # fast as the socket takes them). The real server's speech generation is capped at
    # about 1.0x realtime by its duplex timeline, but what it has already generated sits
    # in its TTS queue (`stats.speech_queue`) and goes out in a lump when it flushes. So
    # one reply is a fast head followed by a sub-realtime tail, and how much of that head
    # the robot is allowed to bank is exactly what the relay's burst cap decides.
    pace: float | None = None


@dataclass
class Turn:
    """One user turn and the model's reply to it."""
    user_end_at: float
    # Deltas sent evenly between user_start and user_end, then one user_text with their
    # concatenation at user_end. The real server sends neither (live mode has no user
    # transcript at all); they stay here to exercise the adapter's dormant transcript path.
    user_text: list[str] | None = None
    user_start_at: float | None = None  # default: 0.1 s before user_end_at
    reply_delay: float = 0.4  # agent_start this long after user_end; None = never reply
    reply_seconds: float = 0.4
    reply_text: list[str] = field(default_factory=list)  # assistant_text_delta after agent_start
    # Measured: the real child emits agent_end only when the model closes its turn because
    # the person started talking, so a reply that simply finishes never produces one. Set
    # False to script that -- the reply is then loud audio and nothing else.
    agent_end: bool = True
    # A stretch of digital silence inside the reply: `reply_gap_seconds` of zeros after
    # `reply_gap_after` seconds of speech. The relay's hangover must not split the reply.
    reply_gap_after: float | None = None
    reply_gap_seconds: float = 0.0
    # Measured: the child's TTS lags its own `agent_start` -- about 240 ms in one probe,
    # over 640 ms in a run that lost a farewell. This scripts that lag: `reply_silence_before`
    # seconds of digital silence on the reply channel after agent_start (and after the
    # reply_text deltas, which go out with agent_start) before the first speech chunk.
    # `reply_seconds=0` scripts an agent_start the model never speaks after at all.
    reply_silence_before: float = 0.0
    # Measured on a server slower than realtime: the reply audio arrives in BURSTS -- a
    # loud stretch, then a gap of silence while the server computes, then another -- with
    # `assistant_text_delta` still flowing through the gaps. Set to a list of Burst to
    # script that; it replaces reply_seconds / reply_gap_* for this turn's reply body.
    reply_bursts: list[Burst] | None = None
    # `assistant_text_delta` spread evenly THROUGH the reply's own audio, rather than all
    # at agent_start the way `reply_text` goes out. That is what the real server does -- it
    # keeps producing text while it speaks -- and it is what a guard watching the reply in
    # flight has to see. A long `reply_seconds` with a repeating phrase list scripts the
    # quantized model's degenerate loop; with a varied one, an ordinary long answer.
    reply_phrases: list[str] | None = None


@dataclass
class Script:
    turns: list[Turn] = field(default_factory=list)
    chunk_ms: int = 80  # reply audio chunk duration
    pace: float = 1.0  # reply chunk spacing as a fraction of real time; 0 = as fast as possible
    flush_rms: float | None = None  # uplink RMS that interrupts an active reply (None = never)
    stats_every: float | None = None  # seconds between stats frames (None = none)
    stats_backlog: float = 0.0  # input backlog reported in stats, seconds
    # `input_backlog_frames`: how many 80 ms frames of uplink the child has not consumed
    # yet. This is the field the real server sends and the relay's backlog alarm watches;
    # a test may change it while the session runs to drive an excursion and a recovery.
    stats_backlog_frames: float = 0.0
    stats_rtf: float | None = None  # `rtf`; < 1 means the server is faster than realtime
    stats_speech_queue: int = 0
    # Seconds between out-of-reply reply-channel audio chunks (None = none). The real
    # server streams reply audio continuously, whether or not it is replying -- see
    # docs/model-server-protocol.md. There is only ever one reply channel, so these fill
    # the gaps between replies: it carries speech or silence, never both at once.
    idle_audio_every: float | None = None
    # Every sample of an out-of-reply chunk. 0 is what the real server sends: measured, the
    # reply channel is exact digital silence (RMS 0.0) whenever the model is not speaking.
    idle_audio_sample: int = 0
    events: list[tuple[float, dict]] = field(default_factory=list)  # raw JSON frames at time t
    close_at: float | None = None  # server closes the socket at t, as a takeover would
    status_reply: bool = True  # answer the client's `status` request
    status_delay: float = 0.0
    status_state: str = "ready"  # `state` in the status reply: ready | loading | dead
    system_ack: bool = True  # answer `system` with the `system` ack the client waits for
    system_ack_delay: float = 0.01  # how long the persona takes to read in


def chunk_count(script, seconds):
    """How many chunks the fake sends for a stretch of `seconds`."""
    return max(0, round(seconds * 1000 / script.chunk_ms))


def speech_chunk(script, turn_index, i):
    """The i-th loud chunk of a turn's reply: distinct bytes per turn and per chunk, so a
    test can assert exactly which chunks reached the robot and in which order."""
    samples = OUTPUT_RATE * script.chunk_ms // 1000
    base = (turn_index * 7919 + i * 131) % 20000
    return array("h", ((base + k) % 20000 - 10000 for k in range(samples))).tobytes()


def silence_chunk(script):
    """One chunk of the exact digital silence the real server sends when it is not
    speaking -- between replies, and inside one while it is still computing."""
    return bytes(OUTPUT_RATE * script.chunk_ms // 1000 * 2)


def speech_chunks(script, turn_index):
    """The loud chunks of a turn's reply, without any scripted in-reply silence."""
    turn = script.turns[turn_index]
    if turn.reply_bursts is not None:
        return [pcm for pcm, loud in burst_frames(script, turn_index) if loud]
    if turn.reply_seconds <= 0:
        return []  # an agent_start with no speech behind it
    count = max(1, round(turn.reply_seconds * 1000 / script.chunk_ms))
    return [speech_chunk(script, turn_index, i) for i in range(count)]


def burst_frames(script, turn_index):
    """Every reply chunk of a bursty turn in order, as (pcm, is_speech) pairs."""
    turn = script.turns[turn_index]
    frames, i = [], 0
    for burst in turn.reply_bursts or []:
        frames += [(silence_chunk(script), False)] * chunk_count(script, burst.silence)
        for _ in range(chunk_count(script, burst.speech)):
            frames.append((speech_chunk(script, turn_index, i), True))
            i += 1
    return frames


def reply_chunks(script, turn_index):
    """The exact reply PCM chunks the fake sends for a turn, so tests can check bytes:
    its speech, with any scripted gap of digital silence spliced in (or, for a bursty
    turn, the whole burst timeline)."""
    turn = script.turns[turn_index]
    if turn.reply_bursts is not None:
        return [pcm for pcm, _loud in burst_frames(script, turn_index)]
    chunks = speech_chunks(script, turn_index)
    if turn.reply_gap_after is None or turn.reply_gap_seconds <= 0:
        return chunks
    at = max(1, round(turn.reply_gap_after * 1000 / script.chunk_ms))
    gap = max(1, round(turn.reply_gap_seconds * 1000 / script.chunk_ms))
    return chunks[:at] + [silence_chunk(script)] * gap + chunks[at:]


def lead_silence_chunks(script, turn_index):
    """The digital silence a turn sends between its `agent_start` and its first speech
    chunk: the real server's TTS lag (see Turn.reply_silence_before)."""
    turn = script.turns[turn_index]
    return [silence_chunk(script)] * chunk_count(script, turn.reply_silence_before)


def phrase_schedule(phrases, chunks):
    """Which `reply_phrases` deltas go out just before which reply chunk, spread evenly
    over the reply's audio; {} when there is nothing to spread."""
    due = {}
    phrases = list(phrases or ())
    if not phrases or chunks <= 0:
        return due
    for j, text in enumerate(phrases):
        due.setdefault(min(chunks - 1, j * chunks // len(phrases)), []).append(text)
    return due


def idle_chunk(script):
    """One out-of-reply chunk. By default digital silence, which is what the real server
    sends between replies; `idle_audio_sample` makes it audible instead."""
    samples = OUTPUT_RATE * script.chunk_ms // 1000
    if not script.idle_audio_sample:
        return silence_chunk(script)
    return array("h", [script.idle_audio_sample] * samples).tobytes()


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
        # The real server serves the WebSocket at /ws only; the fake answers any path so an
        # older fixture aimed at / still connects, but records which one was asked for.
        self.path = getattr(getattr(ws, "request", None), "path", "")
        self.received: list[Frame] = []
        self.sent: list[dict] = []  # JSON frames the fake sent, in order
        self.reply_bytes_sent = 0
        self.idle_bytes_sent = 0  # reply-channel audio sent outside any reply
        self.lead_silence_bytes_sent = 0  # silence between agent_start and the first speech
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
            self._spawn(self._session(str(data.get("text", ""))))
        elif kind == "status" and self.script.status_reply:
            self._spawn(self._status())

    async def _session(self, persona):
        """The persona is read in first; only then does the child hear anything, so the
        timeline's t=0 is the `system` ack, not the client's request."""
        await self._system(persona)
        await self._timeline()

    async def _system(self, persona):
        """Reading the persona in: the child's system_start / progress / system sequence."""
        if not self.script.system_ack:
            return
        words = max(1, len(persona.split()))
        await self._send_json({"kind": "system_start", "tokens": words, "t": 0})
        await self._send_json({"kind": "progress", "t": 0, "phase": "system", "left": words})
        await asyncio.sleep(self.script.system_ack_delay)
        await self._send_json({"kind": "system", "t": 0})

    async def _status(self):
        await asyncio.sleep(self.script.status_delay)
        await self._send_json({"kind": "status", "state": self.script.status_state,
                               "system_set": False, "t": 0})

    async def _on_audio(self, pcm):
        script = self.script
        if (script.flush_rms is not None and self._reply_task is not None
                and not self._reply_task.done() and not self._flushed_current
                and rms(pcm) > script.flush_rms):
            self._flushed_current = True
            self._reply_task.cancel()
            self.flushes += 1
            await self._send_json({"kind": "flush"})
            await self._send_json({"kind": "agent_end"})

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
        if script.idle_audio_every:
            self._spawn(self._idle_audio())

    async def _turn(self, t0, index, turn):
        loop = asyncio.get_running_loop()
        start = turn.user_start_at if turn.user_start_at is not None else max(0.0, turn.user_end_at - 0.1)
        await asyncio.sleep(max(0.0, t0 + start - loop.time()))
        await self._send_json({"kind": "user_start"})
        deltas = turn.user_text or []
        step = (turn.user_end_at - start) / (len(deltas) + 1)
        for i, delta in enumerate(deltas):
            await asyncio.sleep(max(0.0, t0 + start + step * (i + 1) - loop.time()))
            await self._send_json({"kind": "user_text_delta", "text": delta})
        await asyncio.sleep(max(0.0, t0 + turn.user_end_at - loop.time()))
        await self._send_json({"kind": "user_end"})
        if turn.user_text is not None:
            await self._send_json({"kind": "user_text", "text": "".join(turn.user_text)})
        if turn.reply_delay is None:
            return
        await asyncio.sleep(max(0.0, t0 + turn.user_end_at + turn.reply_delay - loop.time()))
        self._flushed_current = False
        self._reply_task = self._spawn(self._reply(index, turn))

    async def _reply(self, index, turn):
        await self._send_json({"kind": "agent_start"})
        for text in turn.reply_text:
            await self._send_json({"kind": "assistant_text_delta", "delta": text})
        spacing = self.script.chunk_ms / 1000 * self.script.pace
        for chunk in lead_silence_chunks(self.script, index):
            await self.ws.send(chunk)
            self.lead_silence_bytes_sent += len(chunk)
            await asyncio.sleep(spacing)
        if turn.reply_bursts is not None:
            await self._bursts(index, turn, spacing)
        else:
            chunks = reply_chunks(self.script, index)
            due = phrase_schedule(turn.reply_phrases, len(chunks))
            if turn.reply_phrases and not due:  # a reply with no audio to spread them over
                due = {None: list(turn.reply_phrases)}
                for text in due[None]:
                    await self._send_json({"kind": "assistant_text_delta", "delta": text})
            for k, chunk in enumerate(chunks):
                for text in due.get(k, ()):
                    await self._send_json({"kind": "assistant_text_delta", "delta": text})
                await self.ws.send(chunk)
                self.reply_bytes_sent += len(chunk)
                await asyncio.sleep(spacing)
        if turn.agent_end:
            await self._send_json({"kind": "agent_end"})

    async def _bursts(self, index, turn, spacing):
        """A slow server's bursty reply: speech, then a gap of digital silence while the
        server computes -- with the turn's text deltas still arriving through the gap,
        spread evenly through it, because the model's text runs ahead of its TTS."""
        i = 0
        for burst in turn.reply_bursts:
            step = spacing if burst.pace is None else self.script.chunk_ms / 1000 * burst.pace
            gap = chunk_count(self.script, burst.silence)
            deltas = list(burst.text)
            due = {}
            for j, text in enumerate(deltas):
                due.setdefault(round((j + 1) * gap / (len(deltas) + 1)), []).append(text)
            for k in range(gap):
                for text in due.get(k, []):
                    await self._send_json({"kind": "assistant_text_delta", "delta": text})
                chunk = silence_chunk(self.script)
                await self.ws.send(chunk)
                self.reply_bytes_sent += len(chunk)
                await asyncio.sleep(step)
            if not gap:
                for text in deltas:
                    await self._send_json({"kind": "assistant_text_delta", "delta": text})
            for _ in range(chunk_count(self.script, burst.speech)):
                chunk = speech_chunk(self.script, index, i)
                i += 1
                await self.ws.send(chunk)
                self.reply_bytes_sent += len(chunk)
                await asyncio.sleep(step)

    async def _idle_audio(self):
        """Reply-channel audio outside any reply, the way the real server streams it."""
        chunk = idle_chunk(self.script)
        try:
            while True:
                await asyncio.sleep(self.script.idle_audio_every)
                if self._reply_task is not None and not self._reply_task.done():
                    continue  # the one reply channel is carrying speech right now
                await self.ws.send(chunk)
                self.idle_bytes_sent += len(chunk)
        except ConnectionClosed:
            pass  # the client went away mid-stream, as the real server's would

    async def _stats(self):
        while True:
            await asyncio.sleep(self.script.stats_every)
            frame = {"kind": "stats", "input_backlog": self.script.stats_backlog,
                     "input_backlog_frames": self.script.stats_backlog_frames,
                     "speech_queue": self.script.stats_speech_queue}
            if self.script.stats_rtf is not None:
                frame["rtf"] = self.script.stats_rtf
            await self._send_json(frame)


class FakeModelServer:
    """Binds 127.0.0.1 on `port` (0: an ephemeral one). Use as an async context manager.
    Subclasses override _process_request to act on each connection before its handshake."""

    def __init__(self, script=None, port=0):
        self.script = script or Script()
        self.sessions: list[Session] = []
        self.bind_port = port
        self.port = None
        # The real server takes one client and the newest connection evicts the previous
        # one, so a client that means to keep a session must never open a second beside it.
        # `evictions` counts the times a new connection landed on a live one; `max_open`
        # is the high-water mark of connections open at once.
        self.evictions = 0
        self.open_now = 0
        self.max_open = 0
        self._server = None
        self._active: Session | None = None
        self._changed = asyncio.Event()

    @property
    def last_session(self):
        return self.sessions[-1] if self.sessions else None

    async def __aenter__(self):
        self._server = await serve(self._handle, "127.0.0.1", self.bind_port, compression=None,
                                   process_request=self._process_request)
        self.port = self._server.sockets[0].getsockname()[1]
        return self

    async def _process_request(self, connection, request):
        return None  # go on with the handshake

    async def __aexit__(self, *exc):
        self._server.close()
        await self._server.wait_closed()

    async def _handle(self, ws):
        self.open_now += 1
        self.max_open = max(self.max_open, self.open_now)
        try:
            previous = self._active
            if previous is not None:
                self.evictions += 1
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
        finally:
            self.open_now -= 1

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
