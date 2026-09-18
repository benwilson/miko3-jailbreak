"""A Python stand-in for the robot's side of the relay link (plan U11, KTD3).

Speaks the lane protocol the Java ConversationClient (U8) will speak: sends `hello` and
waits for `welcome`, opens and closes conversations, streams uplink audio, reports
playback, and answers every `cmd` with `cmd.result{re, status: unsupported}` the way the
real robot must (R12, AE8). With hold_cmds=True it leaves commands unanswered instead and
reports each one `cancelled` when its conversation closes, for the in-flight rule.

Every frame received is recorded with its arrival time (loop.time()) so tests can check
order and pacing. go_silent() stops reading the socket, so pings go unanswered: a dead
robot on a live TCP connection, for the relay's link-loss path.

    async with FakeRobot(port) as robot:
        welcome = await robot.connect()
        conv = await robot.open_conv()
        await robot.wait_text("conv.ready")
"""
import asyncio
import itertools
import json
from dataclasses import dataclass

from websockets.asyncio.client import connect
from websockets.exceptions import ConnectionClosed

UPLINK_RATE = 16000
SPEAKER_RATE = 22050


@dataclass
class Frame:
    t: float  # loop.time() at arrival
    kind: str  # "text" or "binary"
    data: object  # dict for text, bytes for binary


class FakeRobot:
    def __init__(self, port, host="127.0.0.1", robot_id="miko-test", *,
                 turn_taking="interruptible", hold_cmds=False):
        self.host = host
        self.port = port
        self.robot_id = robot_id
        self.turn_taking = turn_taking
        self.hold_cmds = hold_cmds
        self.frames: list[Frame] = []
        self.conv = None
        self.held_cmds = []  # commands not yet answered (hold_cmds only)
        self.ws = None
        self._ids = itertools.count(1)
        self._convs = itertools.count(1)
        self._t0 = None
        self._receiver = None
        self._changed = asyncio.Event()

    async def __aenter__(self):
        return self

    async def __aexit__(self, *exc):
        await self.close()

    # --- link ---

    async def connect(self, hello_overrides=None, expect_welcome=True):
        """Open the socket, send `hello`, and (by default) wait for `welcome`, returned."""
        self.ws = await connect(f"ws://{self.host}:{self.port}/", compression=None,
                                ping_interval=None, close_timeout=0.5, max_size=None)
        self._receiver = asyncio.create_task(self._receive_loop(), name="fake-robot-rx")
        loop = asyncio.get_running_loop()
        self._t0 = loop.time()
        hello = {"robot_id": self.robot_id, "proto": 1, "app_version": "test",
                 "capabilities": [], "mic_rate": UPLINK_RATE, "speaker_rate": SPEAKER_RATE,
                 "turn_taking": self.turn_taking}
        hello.update(hello_overrides or {})
        await self.send("hello", **hello)
        if expect_welcome:
            return (await self.wait_text("welcome")).data
        return None

    def go_silent(self):
        """Stop reading the socket: no more frames are processed and no pongs are sent."""
        self.ws.transport.pause_reading()

    async def close(self):
        if self.ws is not None:
            await self.ws.close()
        if self._receiver is not None:
            self._receiver.cancel()
            try:
                await self._receiver
            except asyncio.CancelledError:
                pass

    async def wait_closed(self, timeout=2.0):
        """Wait for the relay to close the link; returns (code, reason)."""
        async with asyncio.timeout(timeout):
            await self.ws.wait_closed()
        return self.ws.close_code, self.ws.close_reason

    # --- sending ---

    async def send(self, type_, conv=None, **fields):
        """Send one enveloped text frame; returns its id."""
        msg_id = next(self._ids)
        t = int((asyncio.get_running_loop().time() - self._t0) * 1000) if self._t0 else 0
        await self.ws.send(json.dumps({"type": type_, "id": msg_id, "conv": conv, "t": t,
                                       **fields}))
        return msg_id

    async def send_raw(self, text):
        await self.ws.send(text)

    async def open_conv(self, turn_taking=None, **fields):
        """Send `conv.open` for a new conversation; returns its conv id."""
        self.conv = f"c{next(self._convs)}"
        if turn_taking is not None:
            fields["turn_taking"] = turn_taking
        await self.send("conv.open", conv=self.conv, **fields)
        return self.conv

    async def close_conv(self, reason="robot_request"):
        conv, self.conv = self.conv, None
        await self.send("conv.close", conv=conv, reason=reason)
        await self._cancel_held(conv)

    async def send_audio(self, pcm):
        await self.ws.send(bytes(pcm))

    async def playback(self, state, buffered_ms=0, first_chunk_to_play_ms=None):
        await self.send("playback", conv=self.conv, state=state, buffered_ms=buffered_ms,
                        first_chunk_to_play_ms=first_chunk_to_play_ms)

    # --- received frames ---

    def texts(self, type_=None):
        return [f for f in self.frames
                if f.kind == "text" and (type_ is None or f.data.get("type") == type_)]

    def binaries(self):
        return [f for f in self.frames if f.kind == "binary"]

    async def wait_for(self, predicate, timeout=2.0):
        """Wait until predicate() is truthy, re-checking on every frame; returns its value."""
        async with asyncio.timeout(timeout):
            while True:
                value = predicate()
                if value:
                    return value
                self._changed.clear()
                await self._changed.wait()

    async def wait_text(self, type_, timeout=2.0, after=0):
        """The first text frame of `type_` at index >= `after` in self.frames."""
        def find():
            for f in self.frames[after:]:
                if f.kind == "text" and f.data.get("type") == type_:
                    return f
            return None
        return await self.wait_for(find, timeout)

    async def _receive_loop(self):
        loop = asyncio.get_running_loop()
        try:
            async for message in self.ws:
                if isinstance(message, bytes):
                    self.frames.append(Frame(loop.time(), "binary", message))
                else:
                    data = json.loads(message)
                    self.frames.append(Frame(loop.time(), "text", data))
                    await self._on_text(data)
                self._changed.set()
        except ConnectionClosed:
            pass
        finally:
            self._changed.set()

    async def _on_text(self, data):
        kind = data.get("type")
        if kind == "cmd":
            if self.hold_cmds:
                self.held_cmds.append(data)
            else:
                await self.send("cmd.result", conv=data.get("conv"), re=data.get("id"),
                                status="unsupported")
        elif kind == "conv.close" and data.get("conv") == self.conv:
            self.conv = None
            await self._cancel_held(data.get("conv"))

    async def _cancel_held(self, conv):
        """Answer `cancelled` for every held command of the closed conversation."""
        keep = []
        for cmd in self.held_cmds:
            if cmd.get("conv") == conv:
                await self.send("cmd.result", conv=conv, re=cmd.get("id"), status="cancelled")
            else:
                keep.append(cmd)
        self.held_cmds = keep
