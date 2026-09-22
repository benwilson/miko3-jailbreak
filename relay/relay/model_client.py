"""Adapter for the owner's model server (webchat/server.py), per plan U4 and KTD1/KTD5.

Protocol reference: relay/docs/model-server-protocol.md, verified against the server's own
sources. Two things that page gets wrong at your peril: the WebSocket is at `/ws` (`/` is
the browser page), and the server's own events are keyed by `kind`, not `type` -- only the
client's commands use `type`.

One ModelClient is one conversation: it connects to the plain-WebSocket port, sends `reset`
then `system` with the persona, waits for the child's `system` ack (reading the persona in
costs roughly 80 ms a word, so this is not instant) and only then counts as ready. Audio
handed over before the ack is kept and forwarded in order once it arrives, so a fresh,
forgetful session every time (R9) never costs the first words. After that it keeps the
model's input stream continuous, because silence is how the model hears the person stop:

- the uplink runs on a FRAME CLOCK: exactly one frame leaves per frame period of wall
  clock, on a deadline that advances by the audio duration of the frame just sent, so it
  can neither drift nor bunch. Whatever is queued at the deadline goes; if nothing is,
  a zero-filled 80 ms frame goes instead, because silence is how the model's VAD hears the
  person stop. Chunks arriving faster than the clock (Wi-Fi jitter catching up, or a
  pre-ready buffer) are queued and released at that rate, never dumped; past
  UPLINK_QUEUE_CHUNKS the oldest is dropped and counted, because a robot persistently
  ahead of realtime is a bug and silently growing lag is worse than a drop;
- this is only once the uplink has started: a session opened `quiet` (the relay's warm
  one) sends nothing at all until start_uplink(), because idle frames only build the
  model's input backlog;
- a backlog handed to open() (the robot's pre-`conv.ready` buffer) is queued like the rest
  and released at the frame rate -- see open().

Round 9, measured live: `stats.input_backlog_frames` climbed 3 -> 7 -> 14 -> 20 -> 22 -> 28
while the server itself was FASTER than realtime (rtf 0.70-0.76), and earlier in the same
session compounded until the model was ~39 s behind and degenerated. A server with headroom
can only fall behind if it is fed faster than realtime: chunks were forwarded the instant
they arrived AND the watchdog filled the slot a late chunk had not arrived for, so one
jittery chunk cost two frames for one 80 ms slot. uplink_stats()["rate"] is that number,
measured, so the failure can be seen rather than inferred.

Server frames come back as a typed event stream (events()), ending with exactly one Closed.
A server `error` closes the connection and the stream, with the error's code on Closed.

The server is single-client: any new connection, including probe(), evicts the current one.
Audio bytes are never logged.
"""
import asyncio
import json
import logging
from dataclasses import dataclass, field

from websockets.asyncio.client import connect
from websockets.exceptions import ConnectionClosed, InvalidHandshake, InvalidURI

log = logging.getLogger("relay.model_client")

DEFAULT_PORT = 8766  # plain WebSocket; 8765 is the same app behind a self-signed TLS cert
DEFAULT_PATH = "/ws"  # verified: `/` serves the browser page, so the handshake fails there
INPUT_RATE = 16000  # uplink PCM: 16-bit LE mono
OUTPUT_RATE = 22050  # reply PCM: 16-bit LE mono
FRAME_SECONDS = 0.080
ZERO_FRAME = bytes(int(INPUT_RATE * FRAME_SECONDS) * 2)  # 1,280 samples, 2,560 bytes
SYSTEM_TEXT_FIELD = "text"  # assumption: field carrying the persona on `system` (see protocol doc)
# The release queue in front of the frame clock. Bounding it bounds the latency a robot
# running ahead of realtime can build: 64 chunks is about 5 s of 80 ms audio, and past that
# the OLDEST is dropped, so what survives is what the person just said.
UPLINK_QUEUE_CHUNKS = 64
EVENT_QUEUE_EVENTS = 1024
PREREADY_CHUNKS = 512  # about 40 s of 80 ms chunks held while the persona is read in
SYSTEM_TIMEOUT = 20.0  # generous: a long persona is read in at roughly 80 ms a word
READY_STATE = "ready"  # status.state; the others seen in the wild are "loading" and "dead"


class ProbeError(Exception):
    """The model server did not answer a `status` request with a `status` reply."""


class SystemAckError(Exception):
    """The session never became usable: the persona was not acknowledged."""


class SystemAckTimeout(SystemAckError):
    """No `system` ack within the timeout (a very long persona, or a wedged child)."""


@dataclass(frozen=True)
class ModelEvent:
    """Base of every event on ModelClient.events(). `data` is the raw JSON frame, if any."""
    data: dict = field(default_factory=dict, repr=False, compare=False)


@dataclass(frozen=True)
class AudioChunk(ModelEvent):
    pcm: bytes = b""
    sample_rate: int = OUTPUT_RATE


@dataclass(frozen=True)
class UserStart(ModelEvent):
    pass


@dataclass(frozen=True)
class UserEnd(ModelEvent):
    pass


# Live mode has no user transcript at all: the person's words are never transcribed, and
# user_start/user_end come from an energy VAD in the child. These two never fire against the
# real server; they are kept so a transcript source (or a fixture) can drive the matcher.
@dataclass(frozen=True)
class UserTextDelta(ModelEvent):
    """One piece of the person's running transcript. `transcript` is the turn so far."""
    text: str = ""
    transcript: str = ""


@dataclass(frozen=True)
class UserText(ModelEvent):
    """The person's finished transcript for the turn; the running transcript resets."""
    text: str = ""


@dataclass(frozen=True)
class AssistantTextDelta(ModelEvent):
    text: str = ""


@dataclass(frozen=True)
class AgentStart(ModelEvent):
    pass


@dataclass(frozen=True)
class AgentEnd(ModelEvent):
    pass


@dataclass(frozen=True)
class Flush(ModelEvent):
    """The model yielded to the person: drop any reply audio not yet played."""


@dataclass(frozen=True)
class Stats(ModelEvent):
    """Input backlog and speech queue; the field names are in `data` (shape unconfirmed)."""


@dataclass(frozen=True)
class Status(ModelEvent):
    """status{state,system_set,t,host,device,voice}; `state` is ready | loading | dead."""
    state: str = ""


@dataclass(frozen=True)
class SystemAck(ModelEvent):
    """The child finished reading the persona in: the session is usable from here."""


@dataclass(frozen=True)
class ModelWarning(ModelEvent):
    message: str = ""
    code: object = None


@dataclass(frozen=True)
class ModelError(ModelEvent):
    """Always followed by Closed(error=True): the client closes on a server error."""
    message: str = ""
    code: object = None


@dataclass(frozen=True)
class OtherMessage(ModelEvent):
    """Any other frame: `reset`, `system_start`, `progress`, `turn_start`, `turn_end`,
    `pong`, `function_delta`, the `tool_*` family, `audio`, or something newer than this
    adapter. `type` is the frame's `kind`."""
    type: str = ""


@dataclass(frozen=True)
class Closed(ModelEvent):
    """Last event on the stream. `code` is the server error's code when error=True, else the
    WebSocket close code (1000 on a takeover by another client), or None if never received."""
    code: object = None
    reason: str = ""
    error: bool = False


def frame_seconds(frame):
    """Seconds of audio in one uplink frame (16-bit LE mono at INPUT_RATE)."""
    return len(frame) / 2 / INPUT_RATE


def check_persona(persona):
    """ValueError naming the first non-ASCII character, which the model server rejects."""
    try:
        persona.encode("ascii")
    except UnicodeEncodeError as exc:
        bad = persona[exc.start]
        raise ValueError(
            f"persona must be ASCII only (the model server rejects anything else in `system`); "
            f"found {bad!r} (U+{ord(bad):04X}) at position {exc.start}") from None


def _message_of(data):
    return str(data.get("message", data.get("text", "")))


def model_state(status):
    """The `state` of a status frame from probe(): "ready", "loading", "dead" or "" ."""
    return str(status.get("state", "")) if isinstance(status, dict) else ""


def model_reachable(status):
    """True only for state "ready": a loading or dead model answers, but cannot converse."""
    return model_state(status) == READY_STATE


class ModelClient:
    """One conversation's connection to the model server.

        client = ModelClient(host, persona=persona)
        await client.open(backlog=buffered_chunks)
        client.send_audio(chunk)            # from the robot link, any time, non-blocking
        async for event in client.events(): # ends after Closed
            ...
        await client.close()
    """

    def __init__(self, host, port=DEFAULT_PORT, persona="", *, path=DEFAULT_PATH,
                 open_timeout=3.0, system_timeout=SYSTEM_TIMEOUT,
                 uplink_queue=UPLINK_QUEUE_CHUNKS):
        check_persona(persona)
        self.host = host
        self.port = port
        self.path = path
        self.persona = persona
        self.open_timeout = open_timeout
        self.system_timeout = system_timeout
        self.transcript = ""  # the person's running transcript for the current turn
        self.chunks_sent = 0  # real uplink frames forwarded
        self.zero_frames_sent = 0  # frame slots nothing had arrived for
        self.dropped_chunks = 0  # total dropped: the pre-ready hold plus the release queue
        self.dropped_preready = 0  # dropped while held before the ack / while quiet
        self.uplink_dropped = 0  # dropped oldest at the release queue's bound
        self.chunks_queued = 0  # chunks ever put on the release queue
        self.queue_peak = 0  # deepest the release queue ever got: how far ahead the robot ran
        self.audio_seconds_sent = 0.0  # audio duration handed to the model, zero frames included
        self.system_acked = False  # the `system` ack arrived: the session is usable
        self.quiet = False  # opened quiet (a warm session): hold everything, send nothing
        self.system_ms = None  # how long the child took to read the persona in
        self.ready = False  # open() finished: the ack is in and everything held is away
        self._ws = None
        self._uplink = asyncio.Queue(uplink_queue)
        # The frame clock's own measurement: first send, last send, and the audio covered
        # by every frame but the one in flight. Over exactly [first send, last send] those
        # two are the same interval, so send_rate is unbiased even a few frames in.
        self._first_send_at = None
        self._last_send_at = None
        self._audio_before_last = 0.0
        self._preready = []  # audio handed over before the ack, kept in order
        self._events = asyncio.Queue(EVENT_QUEUE_EVENTS)
        self._acked = asyncio.Event()
        self._gone = asyncio.Event()  # the connection ended (so a waiter stops waiting)
        self._sender = None
        self._receiver = None
        self._closed_event = None  # the Closed emitted, once

    @property
    def uri(self):
        return f"ws://{self.host}:{self.port}{self.path}"

    @property
    def closed(self):
        return self._closed_event is not None

    async def open(self, backlog=(), *, quiet=False):
        """Connect, reset the session, send the persona, wait for the child's `system` ack,
        then start the frame clock on `backlog` and anything handed to send_audio meanwhile,
        oldest first.

        THE PRE-READY BACKLOG IS PACED LIKE EVERYTHING ELSE (round 9). It used to go out
        back to back, to catch up faster. That is the one thing the model's input stream
        cannot absorb: its input is a timeline, so N frames dumped in put it N frames
        behind, and it stays there. Measured, the server had 30% of headroom at best (rtf
        0.70), which drains one frame of backlog for every three it plays -- so a 10 s
        buffer costs over 30 s of lag -- and at the rtf 1.8-3.3 also measured it never
        drains at all. Paced, the model hears the buffer at the rate the person spoke it:
        first response comes later by however long the robot was buffering, which is
        near zero on the warm path (adoption is same-tick) and the persona read on the cold
        one, and the model is never left behind. The release queue's bound caps even that,
        dropping the oldest of an over-long buffer rather than the newest.

        `quiet=True` stops there and sends NOTHING more until start_uplink(): no audio, and
        no watchdog zero frames. That is the relay's warm session, waiting to be adopted.
        The watchdog exists so the model's VAD hears the person stop talking; before a
        conversation nobody is talking, and this server runs slower than realtime (measured
        rtf 1.8-3.3), so every frame sent into it only lengthens its input backlog -- live,
        a conversation opened with the model already ~18 s behind and it went on climbing.
        The session stays live and instantly usable: the persona is already in, and the
        connection itself (its WebSocket keepalive included) is the liveness signal.

        Raises OSError, TimeoutError or websockets' InvalidHandshake if the server is not
        there, and SystemAckTimeout if the persona is never acknowledged: without that ack
        the model is still reading the prompt in and the first words would be lost."""
        self._ws = await connect(self.uri, compression=None, max_size=None,
                                 open_timeout=self.open_timeout, close_timeout=1.0)
        self._receiver = asyncio.create_task(self._receive_loop(), name="model-downlink")
        await self._ws.send(json.dumps({"type": "reset"}))
        await self._ws.send(json.dumps({"type": "system", SYSTEM_TEXT_FIELD: self.persona}))
        started = asyncio.get_running_loop().time()
        try:
            await self._await_system_ack()
        except SystemAckError:
            await self.close()
            raise
        self.system_ms = round((asyncio.get_running_loop().time() - started) * 1000, 1)
        self.system_acked = True
        if quiet:
            self.quiet = True
            self._preready = list(backlog) + self._preready
            log.info("model session quiet %s (persona read in %s ms; silent until the "
                     "uplink starts)", self.uri, self.system_ms)
            return
        # The ack is in: everything held joins the release queue in order, oldest first,
        # and the frame clock hands it over at the rate the model can actually hear it.
        held, self._preready = self._preready, []
        for chunk in list(backlog) + held:
            self._enqueue(chunk)
        self._start_uplink_loop()

    def start_uplink(self, backlog=()):
        """Start forwarding uplink on a session opened `quiet`: `backlog` first, then
        everything held while it was quiet, then live audio and the watchdog's zero frames.
        Synchronous, so adoption can go straight on to conv.ready with nothing between."""
        for chunk in backlog:
            self.send_audio(chunk)  # while quiet these are held, in order
        self.quiet = False
        held, self._preready = self._preready, []
        for chunk in held:
            self._enqueue(chunk)
        self._start_uplink_loop()

    def _start_uplink_loop(self):
        self.ready = True
        log.info("model session ready %s (persona read in %s ms, %d chunks queued)",
                 self.uri, self.system_ms, self._uplink.qsize())
        if self._sender is None and not self.closed:
            self._sender = asyncio.create_task(self._send_loop(), name="model-uplink")

    async def _await_system_ack(self):
        """Wait for the `system` ack, tolerating the `system_start` and `progress` frames
        the child sends while it reads the persona in."""
        acked = asyncio.ensure_future(self._acked.wait())
        gone = asyncio.ensure_future(self._gone.wait())
        try:
            done, _ = await asyncio.wait({acked, gone}, timeout=self.system_timeout,
                                         return_when=asyncio.FIRST_COMPLETED)
        finally:
            acked.cancel()
            gone.cancel()
        if acked in done:
            return
        if gone in done:
            raise SystemAckError(
                f"model closed the connection before acknowledging the persona ({self.uri})")
        raise SystemAckTimeout(
            f"no `system` ack from {self.uri} within {self.system_timeout} s: the model is "
            f"still reading the persona in (about 80 ms a word) or its child is wedged")

    def send_audio(self, chunk):
        """Queue one uplink chunk (16-bit LE 16 kHz mono, any whole number of samples) for
        immediate forwarding. Never blocks; a no-op once closed. Before the `system` ack --
        and on a quiet session, until start_uplink() -- it is held instead, and goes out in
        order as soon as the session is ready."""
        if len(chunk) % 2:
            raise ValueError(f"uplink chunk of {len(chunk)} bytes is not whole 16-bit samples")
        if self.closed:
            return
        if not self.system_acked or self.quiet:
            if len(self._preready) >= PREREADY_CHUNKS:
                self._preready.pop(0)
                self.dropped_chunks += 1
                self.dropped_preready += 1
            self._preready.append(chunk)
            return
        self._enqueue(chunk)

    def _enqueue(self, chunk):
        """Put one chunk on the release queue the frame clock drains. Past the bound the
        OLDEST goes: a robot that is persistently ahead of realtime is a bug, and the newest
        audio is the audio the person is speaking now."""
        self.chunks_queued += 1
        if self._uplink.full():
            self._uplink.get_nowait()
            self.dropped_chunks += 1
            self.uplink_dropped += 1
            if self.uplink_dropped == 1 or self.uplink_dropped % 50 == 0:
                log.warning("model uplink is ahead of realtime: %d chunks dropped from the "
                            "front of a %d-chunk release queue (send rate %.2f x realtime)",
                            self.uplink_dropped, self._uplink.maxsize, self.send_rate)
        self._uplink.put_nowait(chunk)
        self.queue_peak = max(self.queue_peak, self._uplink.qsize())

    @property
    def frames_sent(self):
        """Every frame the model was handed: real chunks plus the clock's zero frames."""
        return self.chunks_sent + self.zero_frames_sent

    @property
    def send_rate(self):
        """Audio seconds handed to the model per second of wall clock -- THE number this
        round is about. The frame clock holds it at 1.00; the unpaced uplink it replaced
        measured 1.15-1.5, and every percent over 1.0 is input backlog the model never gets
        back. 0.0 until two frames have gone out."""
        if self._first_send_at is None or self._last_send_at is None:
            return 0.0
        elapsed = self._last_send_at - self._first_send_at
        if elapsed <= 0:
            return 0.0
        return self._audio_before_last / elapsed

    def uplink_stats(self):
        """What the model was actually sent, for the conversation's own uplink stats."""
        return {"frames": self.frames_sent, "sent": self.chunks_sent,
                "zero": self.zero_frames_sent, "queued": self.chunks_queued,
                "queue_depth": self._uplink.qsize(), "queue_peak": self.queue_peak,
                "dropped": self.dropped_chunks, "dropped_queue": self.uplink_dropped,
                "dropped_preready": self.dropped_preready,
                "seconds": round(self.audio_seconds_sent, 3),
                "rate": round(self.send_rate, 3)}

    async def events(self):
        """Server events in arrival order; the stream ends after its single Closed."""
        while True:
            event = await self._events.get()
            yield event
            if isinstance(event, Closed):
                self._events.put_nowait(event)  # later readers see the end too
                return

    async def close(self):
        """Close the connection (idempotent). The stream ends with Closed(error=False)."""
        if self._sender is not None:
            self._sender.cancel()
        if self._ws is not None:
            try:
                await self._ws.close()
            except Exception:  # noqa: BLE001 - closing a broken socket must not raise
                log.exception("model connection close failed")
        if self._receiver is not None:
            try:
                await asyncio.wait_for(asyncio.shield(self._receiver), 2.0)
            except (asyncio.TimeoutError, asyncio.CancelledError):
                self._receiver.cancel()
        self._emit_closed(Closed(code=self._ws.close_code if self._ws else None,
                                 reason="closed by relay"))

    async def _send_frame(self, frame, now, *, zero):
        await self._ws.send(frame)
        if zero:
            self.zero_frames_sent += 1
        else:
            self.chunks_sent += 1
        if self._first_send_at is None:
            self._first_send_at = now
        self._last_send_at = now
        self._audio_before_last = self.audio_seconds_sent
        self.audio_seconds_sent += frame_seconds(frame)

    async def _send_loop(self):
        """The frame clock. One frame leaves per frame period of wall clock and no more,
        ever: that is the whole fix. The deadline advances by the audio duration of the
        frame just sent -- one FRAME_SECONDS for the robot's 80 ms chunks and for every zero
        frame -- rather than being reset to "now", so scheduling jitter can neither make the
        clock drift late nor let it bunch frames to catch up.

        Advancing by the frame's own duration rather than a flat FRAME_SECONDS matters
        because the uplink contract allows any whole number of samples per chunk: the
        invariant that has to hold is audio seconds sent <= wall seconds elapsed, not frames
        sent <= slots elapsed. For 80 ms chunks the two are the same thing.

        Whatever is queued at the deadline goes; when nothing is, the zero frame goes, so
        the model's VAD still hears silence and the person's turn still ends. What it will
        NOT do any more is send both for one slot, which is how a late chunk over Wi-Fi used
        to buy the model an extra 80 ms of permanent input backlog."""
        loop = asyncio.get_running_loop()
        deadline = loop.time()
        try:
            while True:
                wait = deadline - loop.time()
                if wait > 0:
                    await asyncio.sleep(wait)
                try:
                    chunk, zero = self._uplink.get_nowait(), False
                except asyncio.QueueEmpty:
                    chunk, zero = ZERO_FRAME, True
                now = loop.time()
                await self._send_frame(chunk, now, zero=zero)
                deadline += frame_seconds(chunk)
                # If the event loop itself stalled for longer than a frame, resync instead
                # of firing off the frames the stall owes: catching up is exactly the lag
                # this clock exists to prevent.
                if deadline < now - FRAME_SECONDS:
                    deadline = now
        except ConnectionClosed:
            pass  # the receive loop reports the close
        except asyncio.CancelledError:
            raise
        except Exception:  # noqa: BLE001
            log.exception("model uplink failed")

    async def _receive_loop(self):
        ws = self._ws
        try:
            async for message in ws:
                if isinstance(message, bytes):
                    await self._events.put(AudioChunk(pcm=message))
                    continue
                try:
                    data = json.loads(message)
                except ValueError:
                    log.warning("model sent unparseable text frame (%d chars)", len(message))
                    continue
                if not isinstance(data, dict):
                    log.warning("model sent non-object JSON frame")
                    continue
                event = self._parse(data)
                if isinstance(event, SystemAck):
                    self._acked.set()
                if isinstance(event, ModelError):
                    log.error("model error code=%r: %s", event.code, event.message)
                    await self._events.put(event)
                    if self._sender is not None:
                        self._sender.cancel()
                    self._emit_closed(Closed(data, code=event.code if event.code is not None else "error",
                                             reason=event.message, error=True))
                    await ws.close()
                    return
                await self._events.put(event)
        except ConnectionClosed:
            pass
        except Exception:  # noqa: BLE001
            log.exception("model downlink failed")
        if self._sender is not None:
            self._sender.cancel()
        log.info("model connection closed code=%s reason=%r", ws.close_code, ws.close_reason)
        self._emit_closed(Closed(code=ws.close_code, reason=ws.close_reason or ""))

    def _parse(self, data):
        # The server keys its own events by `kind`; `type` is only a fallback so older
        # fixtures (and any hand-written frame) still parse.
        kind = data.get("kind")
        if kind is None:
            kind = data.get("type")
        if kind == "user_text_delta":
            text = str(data.get("text", ""))
            self.transcript += text
            return UserTextDelta(data, text=text, transcript=self.transcript)
        if kind == "user_text":
            text = data.get("text")
            final = self.transcript if text is None else str(text)
            self.transcript = ""
            return UserText(data, text=final)
        if kind == "assistant_text_delta":
            # verified: the reply text arrives in `delta`, not `text`
            return AssistantTextDelta(data, text=str(data.get("delta", data.get("text", ""))))
        if kind == "status":
            return Status(data, state=str(data.get("state", "")))
        if kind == "system":
            return SystemAck(data)
        if kind == "warning":
            return ModelWarning(data, message=_message_of(data), code=data.get("code"))
        if kind == "error":
            return ModelError(data, message=_message_of(data), code=data.get("code"))
        simple = {"user_start": UserStart, "user_end": UserEnd, "agent_start": AgentStart,
                  "agent_end": AgentEnd, "flush": Flush, "stats": Stats}
        if kind in simple:
            return simple[kind](data)
        return OtherMessage(data, type=str(kind))

    def _emit_closed(self, event):
        self._gone.set()
        if self._closed_event is not None:
            return
        self._closed_event = event
        try:
            self._events.put_nowait(event)
        except asyncio.QueueFull:
            # A reader that stopped reading must still see the end: make room for it.
            self._events.get_nowait()
            self._events.put_nowait(event)


async def probe(host, port=DEFAULT_PORT, timeout=2.0, path=DEFAULT_PATH):
    """Health check (KTD4): connect, send `status`, require a `status` reply, close.

    Returns the status frame, whatever it says; ProbeError means nothing answered at all
    (refused, or no reply in time). Whether the model can actually converse is a separate
    question: only state "ready" counts, so pass the frame to model_reachable(), and log
    model_state() to say which of "loading" (still starting up) or "dead" (its child
    exited) you got. Evicts any open conversation, since the server is single-client, so
    never run it alongside a ModelClient."""
    try:
        async with asyncio.timeout(timeout):
            async with connect(f"ws://{host}:{port}{path}", compression=None, open_timeout=None,
                               close_timeout=0.5) as ws:
                await ws.send(json.dumps({"type": "status"}))
                async for message in ws:
                    if isinstance(message, str):
                        try:
                            data = json.loads(message)
                        except ValueError:
                            continue
                        if isinstance(data, dict) and data.get("kind",
                                                               data.get("type")) == "status":
                            return data
                raise ProbeError(f"model server closed before replying to status "
                                 f"(code {ws.close_code})")
    except TimeoutError:
        raise ProbeError(f"no status reply from {host}:{port} within {timeout} s") from None
    except (OSError, InvalidHandshake, InvalidURI, ConnectionClosed) as exc:
        raise ProbeError(f"model server {host}:{port} unreachable: {exc}") from exc
