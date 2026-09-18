"""The robot-facing side of the relay: one WebSocket link per robot (plan U11, KTD3, KTD5).

The lane owns framing, the message envelope, reply pacing and liveness; a LaneHandler
(the conversation engine, U5, or the bring-up stub) owns what conversations do. Protocol,
version 1:

- Every text frame is JSON with the envelope {type, id, conv, t}: `id` is unique per
  sender and link, `conv` is the conversation id the robot chose on `conv.open` (null
  outside one), `t` is the sender's milliseconds since its `hello`. Unknown fields are
  ignored; an unknown `type` is answered cmd.result{re: <its id>, status: "unsupported"}.
- The robot's first frame is hello{robot_id, proto, app_version, capabilities, mic_rate,
  speaker_rate, turn_taking}, answered welcome{proto, relay_version, model_ok}. A wrong
  proto or a bad hello closes with CLOSE_PROTO or CLOSE_BAD_HELLO; a second link with the
  same robot id replaces the first (CLOSE_REPLACED).
- conv.open{turn_taking} / conv.ready / conv.close{reason}, reasons in CLOSE_REASONS; a
  conv.open without turn_taking takes the hello's value. status{model_ok} from the relay;
  playback{state, buffered_ms, first_chunk_to_play_ms} from the robot.
- reply (its envelope id is the reply id) precedes the first audio chunk of each reply;
  audio.flush{reply} names the reply being discarded. Downstream audio is binary frames of
  REPLY_CHUNK_BYTES (80 ms at 22.05 kHz), paced by ReplyPacer; anything after audio.flush
  belongs to the next reply.
- cmd{action, ...} from the relay (none in v1), cmd.result{re, status} from the robot.
- Uplink audio is binary frames of any whole number of 16 kHz samples, routed to the
  handler only while a conversation is open, otherwise dropped and counted.
- Liveness is RFC 6455 ping/pong every ping_interval; no pong within ping_misses intervals
  is link loss. There is no JSON ping.

Handler callbacks are plain functions run on the event loop and must not block; slow work
(opening the model connection) goes in a task the handler spawns. Each call is guarded:
an exception is logged loudly and the link carries on. Audio bytes are never logged.
"""
import asyncio
import collections
import ipaddress
import itertools
import json
import logging
import socket
from dataclasses import dataclass

from websockets.asyncio.server import serve
from websockets.exceptions import ConnectionClosed

from relay.model_client import FRAME_SECONDS, OUTPUT_RATE

log = logging.getLogger("relay.lane")

PROTO_VERSION = 1
RELAY_VERSION = "0.1.0"
DEFAULT_PORT = 8790
REPLY_CHUNK_BYTES = int(OUTPUT_RATE * FRAME_SECONDS) * 2  # 1,764 samples, 3,528 bytes
BURST_SECONDS = 0.5
PING_INTERVAL = 2.0
PING_MISSES = 3
HELLO_TIMEOUT = 5.0

CLOSE_REASONS = frozenset({"sleep_word", "silence", "farewell_timeout", "robot_request",
                           "model_error", "link_lost"})
# Application close codes (RFC 6455 reserves 4000-4999 for them).
CLOSE_REPLACED = 4000  # a newer link for the same robot id took over
CLOSE_PROTO = 4001  # hello carried a proto version this relay does not speak
CLOSE_BAD_HELLO = 4002  # first frame not a valid hello, or none within HELLO_TIMEOUT

ENVELOPE = ("type", "id", "conv", "t")
ROBOT_TYPES = frozenset({"hello", "conv.open", "conv.close", "playback", "cmd.result"})


def lan_address():
    """The IPv4 address of the interface that routes to the private LAN, else loopback.
    The relay binds only there by default, never to every interface."""
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        try:
            s.connect(("192.168.0.1", 9))  # UDP connect sends nothing; it only picks a route
            address = s.getsockname()[0]
        except OSError:
            return "127.0.0.1"
    return address if ipaddress.ip_address(address).is_private else "127.0.0.1"


@dataclass(frozen=True)
class Pacing:
    """Reply pacing (KTD5). chunk_seconds is the playback time of one REPLY_CHUNK_BYTES
    chunk; tests shrink it to run the same schedule faster."""
    chunk_seconds: float = FRAME_SECONDS
    burst_seconds: float = BURST_SECONDS


class LaneHandler:
    """What the lane calls. Override what you need; the defaults do nothing.

    on_conv_close fires only for closes the handler did not start (the robot's
    conv.close, a replacing conv.open, or link loss), never for Link.close_conv().
    """

    def model_ok(self, link):
        """The model-reachable flag for `welcome`."""
        return True

    def on_link(self, link):
        """A robot said hello and was welcomed."""

    def on_conv_open(self, link, conv, turn_taking, msg):
        """Answer with link.send_conv_ready(conv) or link.close_conv(reason, conv)."""

    def on_uplink(self, link, conv, pcm):
        """One uplink frame of 16-bit LE 16 kHz mono PCM, any whole number of samples."""

    def on_conv_close(self, link, conv, reason, msg):
        """`msg` is the robot's conv.close, or None when the lane ended it (link_lost)."""

    def on_playback(self, link, msg):
        """playback{state, buffered_ms, first_chunk_to_play_ms} from the robot."""

    def on_cmd_result(self, link, msg):
        """cmd.result{re, status} from the robot."""

    def on_link_closed(self, link, why):
        """The link is gone (`why` is a short description); any conversation already got
        on_conv_close(..., "link_lost")."""


class ReplyPacer:
    """Sends reply audio no more than burst_seconds ahead of the robot's speaker (KTD5).

    The robot's buffer is estimated as audio sent minus playback elapsed, taking playback
    to start when a chunk goes out onto an empty buffer. While the estimate leaves room
    for another chunk under the burst cap, chunks go out back to back; after that one
    goes out each time the estimate drops by a chunk, which is one per chunk period, on a
    schedule computed from the estimate so it does not drift.
    """

    def __init__(self, emit_mark, emit_chunk, pacing=Pacing()):
        self._emit_mark = emit_mark  # emit_mark(reply_id): queue the `reply` frame
        self._emit_chunk = emit_chunk  # emit_chunk(bytes): queue one binary frame
        self.pacing = pacing
        self._queue = collections.deque()  # ("mark"|"chunk", reply_id, bytes|None)
        self._partial = {}  # live reply id -> bytearray of audio short of a whole chunk
        self._ahead_until = 0.0  # loop time at which the robot's buffer runs dry
        self._wake = asyncio.Event()
        self.chunks_sent = 0
        self.chunks_discarded = 0

    def estimated_buffer(self):
        """Seconds of reply audio the robot is estimated to still have to play."""
        return max(0.0, self._ahead_until - asyncio.get_running_loop().time())

    def begin(self, reply_id):
        if reply_id in self._partial:
            raise ValueError(f"reply {reply_id} already begun")
        self._partial[reply_id] = bytearray()
        self._push(("mark", reply_id, None))

    def audio(self, reply_id, pcm):
        """Queue reply PCM in any size; it goes out in whole chunks. False (and dropped) if
        the reply is not live: ended, flushed, or discarded."""
        partial = self._partial.get(reply_id)
        if partial is None:
            self.chunks_discarded += len(pcm) // REPLY_CHUNK_BYTES
            return False
        partial += pcm
        whole = len(partial) - len(partial) % REPLY_CHUNK_BYTES
        for i in range(0, whole, REPLY_CHUNK_BYTES):
            self._push(("chunk", reply_id, bytes(partial[i:i + REPLY_CHUNK_BYTES])))
        del partial[:whole]
        return True

    def end(self, reply_id):
        """No more audio for this reply: the tail is zero-padded to a whole chunk."""
        partial = self._partial.pop(reply_id, None)
        if partial:
            self._push(("chunk", reply_id, bytes(partial) + bytes(REPLY_CHUNK_BYTES - len(partial))))

    def flush(self, reply_id):
        """Drop everything queued for the reply and any audio still to come for it.
        Returns the number of chunks discarded."""
        self._partial.pop(reply_id, None)
        kept = collections.deque(item for item in self._queue if item[1] != reply_id)
        dropped = sum(1 for item in self._queue if item[1] == reply_id and item[0] == "chunk")
        self._queue = kept
        self.chunks_discarded += dropped
        return dropped

    def discard_all(self):
        self._partial.clear()
        self.chunks_discarded += sum(1 for item in self._queue if item[0] == "chunk")
        self._queue.clear()

    def _push(self, item):
        self._queue.append(item)
        self._wake.set()

    async def run(self):
        loop = asyncio.get_running_loop()
        chunk_s = self.pacing.chunk_seconds
        room = self.pacing.burst_seconds - chunk_s + 1e-6
        while True:
            while self._queue and self._queue[0][0] == "mark":
                self._emit_mark(self._queue.popleft()[1])
            if not self._queue:
                self._wake.clear()
                await self._wake.wait()
                continue
            now = loop.time()
            wait = self._ahead_until - now - room
            if wait > 0:
                await asyncio.sleep(wait)
                continue
            _, _, chunk = self._queue.popleft()
            self._ahead_until = max(self._ahead_until, now) + chunk_s
            self.chunks_sent += 1
            self._emit_chunk(chunk)


class Link:
    """One robot's connection, from its welcome until it closes. Senders never block and
    return None once the link is closed."""

    def __init__(self, server, ws):
        self.server = server
        self.ws = ws
        self.robot_id = None
        self.hello = {}
        self.conv = None  # the open conversation's id
        self.turn_taking = None  # the open conversation's turn-taking value
        self.last_playback = None
        self.closed = False
        self.stats = {"uplink_frames": 0, "uplink_dropped": 0, "unsupported": 0}
        self._ids = itertools.count(1)
        self._t0 = None
        self._out = asyncio.Queue()  # every outbound frame, in order; audio is bounded by the pacer
        self.pacer = ReplyPacer(self._emit_mark, self._out.put_nowait, server.pacing)
        self._tasks = []

    def __repr__(self):
        return f"Link({self.robot_id!r})"

    # --- sending ---

    def send(self, type_, conv=None, *, msg_id=None, **fields):
        """Queue one enveloped text frame; returns its id (None if the link is closed)."""
        if self.closed:
            log.debug("%s closed, not sending %s", self.robot_id, type_)
            return None
        clash = set(fields) & set(ENVELOPE)
        if clash:
            raise ValueError(f"fields {sorted(clash)} would overwrite the envelope")
        msg_id = next(self._ids) if msg_id is None else msg_id
        frame = {"type": type_, "id": msg_id, "conv": conv, "t": self.t_ms(), **fields}
        self._out.put_nowait(json.dumps(frame, separators=(",", ":")))
        log.debug("%s -> %s", self.robot_id, frame)
        return msg_id

    def t_ms(self):
        return int((asyncio.get_running_loop().time() - self._t0) * 1000) if self._t0 else 0

    def send_conv_ready(self, conv):
        """False (and nothing sent) if `conv` is no longer the open conversation."""
        if self.closed or conv is None or conv != self.conv:
            log.info("%s conv.ready for %s not sent: conversation not open", self.robot_id, conv)
            return False
        self.send("conv.ready", conv)
        return True

    def close_conv(self, reason, conv=None):
        """End the open conversation (or `conv`, if it is the open one) with `reason`.
        Queued reply audio is discarded. False if there was nothing to close."""
        if reason not in CLOSE_REASONS:
            raise ValueError(f"unknown conv.close reason {reason!r}")
        conv = self.conv if conv is None else conv
        if self.closed or conv is None or conv != self.conv:
            return False
        self._end_conv()
        self.send("conv.close", conv, reason=reason)
        log.info("%s conv %s closed by relay: %s", self.robot_id, conv, reason)
        return True

    def send_status(self, model_ok):
        return self.send("status", self.conv, model_ok=bool(model_ok))

    def send_cmd(self, action, **fields):
        """A command-lane message for the open conversation; returns its id, which the
        robot's cmd.result carries as `re`. The relay sends none in v1."""
        return self.send("cmd", self.conv, action=action, **fields)

    def begin_reply(self):
        """Start a reply in the open conversation; returns its id. The `reply` frame goes
        out just ahead of the reply's first chunk."""
        if self.closed:
            return None
        reply_id = next(self._ids)
        self.pacer.begin(reply_id)
        return reply_id

    def send_reply_audio(self, reply_id, pcm):
        """Queue 16-bit LE 22.05 kHz mono reply PCM of any size. False if dropped."""
        if self.closed:
            return False
        return self.pacer.audio(reply_id, pcm)

    def end_reply(self, reply_id):
        self.pacer.end(reply_id)

    def flush_reply(self, reply_id):
        """Discard the reply's unsent audio and send audio.flush{reply}; returns the number
        of chunks discarded. Chunks already sent all precede the audio.flush."""
        dropped = self.pacer.flush(reply_id)
        self.send("audio.flush", self.conv, reply=reply_id)
        log.info("%s reply %s flushed, %d chunks discarded", self.robot_id, reply_id, dropped)
        return dropped

    async def close(self, code=1000, reason=""):
        await self.ws.close(code, reason)

    def _emit_mark(self, reply_id):
        self.send("reply", self.conv, msg_id=reply_id)

    # --- receiving ---

    def _start(self, hello):
        self.hello = hello
        self.robot_id = hello["robot_id"]
        self._t0 = asyncio.get_running_loop().time()
        self._tasks = [asyncio.create_task(self._write_loop(), name=f"lane-tx-{self.robot_id}"),
                       asyncio.create_task(self.pacer.run(), name=f"lane-pacer-{self.robot_id}")]

    async def _write_loop(self):
        while True:
            frame = await self._out.get()
            try:
                await self.ws.send(frame)
            except ConnectionClosed:
                return

    def _on_frame(self, message):
        if isinstance(message, bytes):
            self._on_uplink(message)
            return
        try:
            msg = json.loads(message)
        except ValueError:
            log.warning("%s sent a text frame that is not JSON (%d chars)", self.robot_id,
                        len(message))
            return
        if not isinstance(msg, dict):
            log.warning("%s sent a JSON frame that is not an object", self.robot_id)
            return
        log.debug("%s <- %s", self.robot_id, msg)
        kind = msg.get("type")
        if kind not in ROBOT_TYPES:
            self.stats["unsupported"] += 1
            log.info("%s sent unknown type %r: unsupported", self.robot_id, kind)
            self.send("cmd.result", msg.get("conv"), re=msg.get("id"), status="unsupported")
        elif kind == "hello":
            log.warning("%s sent a second hello; ignored", self.robot_id)
        elif kind == "conv.open":
            self._on_conv_open(msg)
        elif kind == "conv.close":
            self._on_conv_close(msg)
        elif kind == "playback":
            self.last_playback = msg
            self.server._call("on_playback", self, msg)
        elif kind == "cmd.result":
            log.info("%s cmd.result re=%s status=%s", self.robot_id, msg.get("re"),
                     msg.get("status"))
            self.server._call("on_cmd_result", self, msg)

    def _on_conv_open(self, msg):
        conv = msg.get("conv")
        if conv is None:
            log.error("%s conv.open without a conv id; ignored", self.robot_id)
            return
        if self.conv is not None:
            old = self.conv
            log.info("%s conv.open %s replaces open conv %s", self.robot_id, conv, old)
            self._end_conv()
            self.server._call("on_conv_close", self, old, "robot_request", None)
        self.conv = conv
        self.turn_taking = msg.get("turn_taking", self.hello.get("turn_taking"))
        log.info("%s conv %s open, turn_taking=%s", self.robot_id, conv, self.turn_taking)
        self.server._call("on_conv_open", self, conv, self.turn_taking, msg)

    def _on_conv_close(self, msg):
        conv, reason = msg.get("conv"), msg.get("reason")
        if conv is None or conv != self.conv:
            log.info("%s conv.close for %s, not the open conv %s; ignored", self.robot_id,
                     conv, self.conv)
            return
        if reason not in CLOSE_REASONS:
            log.warning("%s conv.close with unknown reason %r", self.robot_id, reason)
        self._end_conv()
        log.info("%s conv %s closed by robot: %s", self.robot_id, conv, reason)
        self.server._call("on_conv_close", self, conv, reason, msg)

    def _on_uplink(self, pcm):
        if self.conv is None or len(pcm) % 2:
            self.stats["uplink_dropped"] += 1
            dropped = self.stats["uplink_dropped"]
            if dropped == 1 or dropped % 50 == 0:
                log.info("%s uplink frame dropped (%d bytes, conv %s); %d dropped so far",
                         self.robot_id, len(pcm), self.conv, dropped)
            return
        self.stats["uplink_frames"] += 1
        self.server._call("on_uplink", self, self.conv, pcm)

    def _end_conv(self):
        self.pacer.discard_all()
        self.conv = None
        self.turn_taking = None

    def _detach(self, why):
        """Stop sending and tell the handler the link is gone; runs once."""
        if self.closed:
            return
        self.closed = True
        for task in self._tasks:
            task.cancel()
        if self.conv is not None:
            conv = self.conv
            self._end_conv()
            self.server._call("on_conv_close", self, conv, "link_lost", None)
        log.info("%s link closed: %s", self.robot_id, why)
        self.server._call("on_link_closed", self, why)


class LaneServer:
    """The relay's robot-facing WebSocket server.

        server = LaneServer(handler)          # binds the LAN address, DEFAULT_PORT
        await server.start()
        server.links["miko-1"].send_status(True)
        await server.stop()

    host=None binds lan_address(); port=0 picks a free port (see .port after start()).
    """

    def __init__(self, handler, host=None, port=DEFAULT_PORT, *, pacing=Pacing(),
                 ping_interval=PING_INTERVAL, ping_misses=PING_MISSES,
                 hello_timeout=HELLO_TIMEOUT):
        self.handler = handler
        self.host = lan_address() if host is None else host
        self.port = port
        self.pacing = pacing
        self.ping_interval = ping_interval
        self.ping_misses = ping_misses
        self.hello_timeout = hello_timeout
        self.links: dict[str, Link] = {}
        self._server = None
        self._closing = set()  # close tasks of replaced links

    async def __aenter__(self):
        await self.start()
        return self

    async def __aexit__(self, *exc):
        await self.stop()

    async def start(self):
        address = ipaddress.ip_address(self.host) if self.host[:1].isdigit() else None
        if address is not None and not (address.is_private or address.is_loopback):
            log.warning("lane binding %s, which is not a private LAN address", self.host)
        # websockets waits for each ping's pong for ping_timeout before sending the next,
        # so a timeout of ping_misses intervals is "ping_misses missed pongs".
        self._server = await serve(self._handle, self.host, self.port, compression=None,
                                   ping_interval=self.ping_interval,
                                   ping_timeout=self.ping_interval * self.ping_misses,
                                   close_timeout=1.0, max_size=1 << 20)
        self.port = self._server.sockets[0].getsockname()[1]
        log.info("lane listening on ws://%s:%d/", self.host, self.port)

    async def stop(self):
        if self._server is not None:
            self._server.close()
            await self._server.wait_closed()

    def _call(self, name, *args):
        try:
            return getattr(self.handler, name)(*args)
        except Exception:  # noqa: BLE001 - a handler bug must not take the link down
            log.exception("lane handler %s failed", name)
            return None

    async def _handle(self, ws):
        link = Link(self, ws)
        why = "closed"
        try:
            hello = await self._hello(ws)
            if hello is None:
                return
            old = self.links.get(hello["robot_id"])
            if old is not None:
                old._detach("replaced by a new link")
                closing = asyncio.create_task(old.close(CLOSE_REPLACED, "replaced by a new link"))
                self._closing.add(closing)
                closing.add_done_callback(self._closing.discard)
            self.links[hello["robot_id"]] = link
            link._start(hello)
            model_ok = bool(self._call("model_ok", link))
            link.send("welcome", proto=PROTO_VERSION, relay_version=RELAY_VERSION,
                      model_ok=model_ok)
            log.info("%s welcomed from %s (app %s, turn_taking %s, model_ok %s)",
                     link.robot_id, ws.remote_address, hello.get("app_version"),
                     hello.get("turn_taking"), model_ok)
            self._call("on_link", link)
            async for message in ws:
                if link.closed:
                    break
                link._on_frame(message)
        except ConnectionClosed:
            pass
        finally:
            why = _close_description(ws, why)
            link._detach(why)
            if link.robot_id is not None and self.links.get(link.robot_id) is link:
                del self.links[link.robot_id]

    async def _hello(self, ws):
        """The validated hello, or None after closing the connection."""
        try:
            message = await asyncio.wait_for(ws.recv(), self.hello_timeout)
        except asyncio.TimeoutError:
            await ws.close(CLOSE_BAD_HELLO, "no hello")
            return None
        try:
            hello = json.loads(message) if isinstance(message, str) else None
        except ValueError:
            hello = None
        if not isinstance(hello, dict) or hello.get("type") != "hello":
            await ws.close(CLOSE_BAD_HELLO, "first frame must be hello")
            return None
        if hello.get("proto") != PROTO_VERSION:
            log.warning("hello from %s with proto %r refused", ws.remote_address,
                        hello.get("proto"))
            await ws.close(CLOSE_PROTO, f"unsupported proto {hello.get('proto')!r}; "
                                        f"relay speaks {PROTO_VERSION}")
            return None
        robot_id = hello.get("robot_id")
        if not isinstance(robot_id, str) or not robot_id:
            await ws.close(CLOSE_BAD_HELLO, "hello without robot_id")
            return None
        return hello


def _close_description(ws, default):
    """A short reason the link ended, naming a keepalive timeout as such."""
    sent, rcvd = ws.protocol.close_sent, ws.protocol.close_rcvd
    if rcvd is None and sent is not None and sent.code == 1011:
        return f"link lost: {sent.reason or 'ping timeout'}"
    if rcvd is not None:
        return f"robot closed ({rcvd.code} {rcvd.reason})".rstrip()
    if sent is not None:
        return f"relay closed ({sent.code} {sent.reason})".rstrip()
    return default
