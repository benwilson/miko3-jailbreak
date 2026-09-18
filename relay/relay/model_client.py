"""Adapter for the owner's model server (webchat/server.py), per plan U4 and KTD1/KTD5.

Protocol reference: relay/docs/model-server-protocol.md. One ModelClient is one
conversation: it connects to the plain-WebSocket port, sends `reset` then `system` with the
persona before any audio (a fresh, forgetful session every time, R9), and then keeps the
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
INPUT_RATE = 16000  # uplink PCM: 16-bit LE mono
OUTPUT_RATE = 22050  # reply PCM: 16-bit LE mono
FRAME_SECONDS = 0.080
ZERO_FRAME = bytes(int(INPUT_RATE * FRAME_SECONDS) * 2)  # 1,280 samples, 2,560 bytes
SYSTEM_TEXT_FIELD = "text"  # assumption: field carrying the persona on `system` (see protocol doc)
UPLINK_QUEUE_CHUNKS = 64  # about 5 s of 80 ms chunks; beyond that the oldest is dropped
EVENT_QUEUE_EVENTS = 1024


class ProbeError(Exception):
    """The model server did not answer a `status` request with a `status` reply."""


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


@dataclass(frozen=True)
class UserTextDelta(ModelEvent):
    """One piece of the person's running transcript (U3). `transcript` is the turn so far."""
    text: str = ""
    transcript: str = ""


@dataclass(frozen=True)
class UserText(ModelEvent):
    """The person's finished transcript for the turn (U3); the running transcript resets."""
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
    pass


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
    """A JSON frame of any other type (`reset`, or something newer than this adapter)."""
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


class ModelClient:
    """One conversation's connection to the model server.

        client = ModelClient(host, persona=persona)
        await client.open(backlog=buffered_chunks)
        client.send_audio(chunk)            # from the robot link, any time, non-blocking
        async for event in client.events(): # ends after Closed
            ...
        await client.close()
    """

    def __init__(self, host, port=DEFAULT_PORT, persona="", *, open_timeout=3.0):
        check_persona(persona)
        self.host = host
        self.port = port
        self.persona = persona
        self.open_timeout = open_timeout
        self.transcript = ""  # the person's running transcript for the current turn
        self.chunks_sent = 0
        self.zero_frames_sent = 0
        self.dropped_chunks = 0
        self._ws = None
        self._uplink = asyncio.Queue(UPLINK_QUEUE_CHUNKS)
        self._events = asyncio.Queue(EVENT_QUEUE_EVENTS)
        self._sender = None
        self._receiver = None
        self._closed_event = None  # the Closed emitted, once

    @property
    def uri(self):
        return f"ws://{self.host}:{self.port}/"

    @property
    def closed(self):
        return self._closed_event is not None

    async def open(self, backlog=()):
        """Connect, reset the session, send the persona, then forward `backlog` back to back.
        Raises OSError, TimeoutError or websockets' InvalidHandshake if the server is not there."""
        self._ws = await connect(self.uri, compression=None, max_size=None,
                                 open_timeout=self.open_timeout, close_timeout=1.0)
        await self._ws.send(json.dumps({"type": "reset"}))
        await self._ws.send(json.dumps({"type": "system", SYSTEM_TEXT_FIELD: self.persona}))
        for chunk in backlog:
            await self._send_chunk(chunk)
        log.info("model connection open %s (backlog %d chunks)", self.uri, self.chunks_sent)
        self._sender = asyncio.create_task(self._send_loop(), name="model-uplink")
        self._receiver = asyncio.create_task(self._receive_loop(), name="model-downlink")

    def send_audio(self, chunk):
        """Queue one uplink chunk (16-bit LE 16 kHz mono, any whole number of samples) for
        immediate forwarding. Never blocks; a no-op once closed."""
        if len(chunk) % 2:
            raise ValueError(f"uplink chunk of {len(chunk)} bytes is not whole 16-bit samples")
        if self.closed:
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
            return AssistantTextDelta(data, text=str(data.get("text", "")))
        if kind == "warning":
            return ModelWarning(data, message=_message_of(data), code=data.get("code"))
        if kind == "error":
            return ModelError(data, message=_message_of(data), code=data.get("code"))
        simple = {"user_start": UserStart, "user_end": UserEnd, "agent_start": AgentStart,
                  "agent_end": AgentEnd, "flush": Flush, "stats": Stats, "status": Status}
        if kind in simple:
            return simple[kind](data)
        return OtherMessage(data, type=str(kind))

    def _emit_closed(self, event):
        if self._closed_event is not None:
            return
        self._closed_event = event
        try:
            self._events.put_nowait(event)
        except asyncio.QueueFull:
            # A reader that stopped reading must still see the end: make room for it.
            self._events.get_nowait()
            self._events.put_nowait(event)


async def probe(host, port=DEFAULT_PORT, timeout=2.0):
    """Health check (KTD4): connect, send `status`, require a `status` reply, close.
    Returns the status frame; raises ProbeError otherwise. Evicts any open conversation,
    since the server is single-client, so never run it alongside a ModelClient."""
    try:
        async with asyncio.timeout(timeout):
            async with connect(f"ws://{host}:{port}/", compression=None, open_timeout=None,
                               close_timeout=0.5) as ws:
                await ws.send(json.dumps({"type": "status"}))
                async for message in ws:
                    if isinstance(message, str):
                        try:
                            data = json.loads(message)
                        except ValueError:
                            continue
                        if isinstance(data, dict) and data.get("type") == "status":
                            return data
                raise ProbeError(f"model server closed before replying to status "
                                 f"(code {ws.close_code})")
    except TimeoutError:
        raise ProbeError(f"no status reply from {host}:{port} within {timeout} s") from None
    except (OSError, InvalidHandshake, InvalidURI, ConnectionClosed) as exc:
        raise ProbeError(f"model server {host}:{port} unreachable: {exc}") from exc
