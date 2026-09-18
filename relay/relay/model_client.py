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

- every uplink chunk is forwarded the moment it is handed over, never re-paced, so Wi-Fi
  jitter cannot build into permanent input lag;
- a watchdog sends one zero-filled 80 ms frame only when a full 80 ms passes with nothing
  forwarded, whatever size the uplink frames are;
- a backlog handed to open() (the robot's pre-`conv.ready` buffer) goes out back to back.

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
UPLINK_QUEUE_CHUNKS = 64  # about 5 s of 80 ms chunks; beyond that the oldest is dropped
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
                 open_timeout=3.0, system_timeout=SYSTEM_TIMEOUT):
        check_persona(persona)
        self.host = host
        self.port = port
        self.path = path
        self.persona = persona
        self.open_timeout = open_timeout
        self.system_timeout = system_timeout
        self.transcript = ""  # the person's running transcript for the current turn
        self.chunks_sent = 0
        self.zero_frames_sent = 0
        self.dropped_chunks = 0
        self.system_acked = False  # the `system` ack arrived: the session is usable
        self.system_ms = None  # how long the child took to read the persona in
        self.ready = False  # open() finished: the ack is in and everything held is away
        self._ws = None
        self._uplink = asyncio.Queue(UPLINK_QUEUE_CHUNKS)
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

    async def open(self, backlog=()):
        """Connect, reset the session, send the persona, wait for the child's `system` ack,
        then forward `backlog` and anything handed to send_audio meanwhile, back to back.

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
        # The ack is in: everything held goes out in order, oldest first, before the
        # watchdog starts filling silence.
        held, self._preready = self._preready, []
        for chunk in list(backlog) + held:
            await self._send_chunk(chunk)
        self.ready = True
        log.info("model session ready %s (persona read in %s ms, %d chunks held)",
                 self.uri, self.system_ms, self.chunks_sent)
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
        immediate forwarding. Never blocks; a no-op once closed. Before the `system` ack it
        is held instead, and goes out in order as soon as the session is ready."""
        if len(chunk) % 2:
            raise ValueError(f"uplink chunk of {len(chunk)} bytes is not whole 16-bit samples")
        if self.closed:
            return
        if not self.system_acked:
            if len(self._preready) >= PREREADY_CHUNKS:
                self._preready.pop(0)
                self.dropped_chunks += 1
            self._preready.append(chunk)
            return
        if self._uplink.full():
            self._uplink.get_nowait()
            self.dropped_chunks += 1
            if self.dropped_chunks == 1 or self.dropped_chunks % 50 == 0:
                log.warning("model uplink stalled: %d chunks dropped", self.dropped_chunks)
        self._uplink.put_nowait(chunk)

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

    async def _send_chunk(self, chunk):
        await self._ws.send(chunk)
        self.chunks_sent += 1

    async def _send_loop(self):
        loop = asyncio.get_running_loop()
        last = loop.time()
        try:
            while True:
                try:
                    chunk = self._uplink.get_nowait()
                except asyncio.QueueEmpty:
                    wait = last + FRAME_SECONDS - loop.time()
                    try:
                        chunk = await asyncio.wait_for(self._uplink.get(), max(wait, 0.0))
                    except asyncio.TimeoutError:
                        await self._ws.send(ZERO_FRAME)
                        self.zero_frames_sent += 1
                        # Keep an even 80 ms cadence, but never burst zeros to catch up after
                        # the loop itself stalled: that would be the lag this avoids.
                        now = loop.time()
                        last = last + FRAME_SECONDS if now - last < 2 * FRAME_SECONDS else now
                        continue
                await self._send_chunk(chunk)
                last = loop.time()
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
