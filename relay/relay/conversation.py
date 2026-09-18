"""The conversation engine (plan U5, KTD4, KTD5): the lane's handler for the real relay.

Per robot link the engine is `idle` (listening) or has one conversation, which moves
connecting -> conversing -> [draining] -> closed:

- connecting: conv.open arrived. Any health probe in flight is cancelled and its result
  discarded (the model server is single-client, so a probe would evict the conversation),
  then one ModelClient connects and sends `reset` and `system` (the persona): every
  conversation is a fresh session (R9). Uplink that arrives meanwhile is kept and goes to
  the model back to back once connected and the persona has been acknowledged (the model
  reads it in at roughly 80 ms a word). No ready session within ready_timeout (12 s) ends
  it with conv.close{model_error}.
- conversing: conv.ready sent. Uplink goes straight to the model; model events and the
  reply audio drive the reply (reply / audio / audio.flush on the lane), the silence timer
  and the farewell matcher. The silence timer is armed at conv.ready, re-armed at user_end
  or the end of a reply (whichever is later), and cancelled by user_start or the start of
  a reply, so it never cuts a reply off (R3).
- draining: the model's own reply text reached the farewell phrase, "talk to you later"
  by default (sleepword.FarewellPhraseMatcher). That is how a conversation ends here: live
  mode transcribes nothing the person says, so KTD2's transcript matcher never fires. The
  uplink is replaced by silence; the farewell already in flight is forwarded until its
  derived end and a robot playback{idle} after it, then conv.close{sleep_word}. The match
  runs seconds ahead of the audio (text deltas outrun the TTS), so a reply that carried no
  speech does not count as the farewell: the drain waits for one that did, since the point
  of it is that the person hears the goodbye (R2). If a match ever arrives between replies,
  no agent_start within farewell_start_timeout (1.5 s) closes at once; drain_cap (8 s)
  closes with farewell_timeout either way.
- closed: conv.close sent (unless the robot or the lane ended it) and the model
  connection closed. A model error, a fatal warning or the model connection closing ends
  it with model_error; the robot link stays up.

Reply audio (see docs/model-server-protocol.md): the model streams reply-channel audio
continuously, and between replies it is exact digital silence. `agent_end` is measured
never to arrive when a reply simply finishes -- the child emits it only when the person
takes the floor back -- so the reply's boundaries are derived from the audio itself:

- a reply BEGINS at the first non-silent chunk, or at agent_start if that comes first;
- one opened by agent_start then waits `reply_start_grace_ms` for its first sound, because
  the child's TTS lags its own agent_start by a variable amount (~240 ms in one probe, over
  640 ms in a run that lost a farewell). The grace expiring ends it as an empty reply
  (`why: "no-speech"`); the silence it waited through is dropped, not forwarded;
- from its first loud chunk it ENDS `reply_silence_ms` after its last non-silent chunk, or
  at agent_end or flush if one of those arrives first. Silence BETWEEN two loud chunks is
  forwarded, not dropped: that hangover is exactly what keeps a pause between two sentences
  from splitting a reply. Running the hangover before the reply has any speech is what
  ended a reply empty and left its farewell to arrive out of reply and be dropped.

Only a reply's audio reaches the robot; silence outside one, and the TTS lag at the head of
one, are dropped and counted in the close record as `out_of_reply`. Without that the robot's speaker never drains, its
playback{idle} never arrives, and both the farewell drain and the strict gate fall back to
their timers. Every reply logs a `reply.end` record with its duration and which signal
ended it, so the field data shows whether silence, agent_end or flush is doing the work.

Turn-taking (KTD5): in "interruptible" conversations the model's `flush` stops the reply
at once (the paced queue is discarded and audio.flush{reply} sent). In "strict" ones the
uplink is replaced by zeros from the reply's start until it has ended and the robot then
reports playback idle, plus the cooldown; an idle before the derived end is an underrun,
logged, and the gate stays closed. The dormant transcript matcher is not fed while the
gate is closed; the farewell matcher reads the model's own text and is unaffected.

Health (KTD4): while a robot is listening the engine probes the model server every
probe_interval and pushes status{model_ok} when it changes. After a conversation that
failed to open or ended with model_error, or that the robot closed while connecting, it
probes at once and pushes the result even if unchanged. Probes run only while no
conversation connection is open or opening.

Every conversation writes one JSONL log (relay.logging) with the model events, lane
frames (never audio bytes), matcher decisions (`sleepword`, whichever matcher made them)
and a per-turn latency record.
"""
import asyncio
import logging
import math
from array import array
from dataclasses import dataclass, field

from relay.lane import CLOSE_REASONS
from relay.logging import new_conversation_id
from relay.model_client import (
    DEFAULT_PATH,
    DEFAULT_PORT,
    INPUT_RATE,
    OUTPUT_RATE,
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
    UserEnd,
    UserStart,
    UserText,
    UserTextDelta,
    model_reachable,
    model_state,
    probe,
)
from relay.sleepword import (
    DEFAULT_FAREWELL_PHRASE,
    MATCH,
    NEAR_MISS,
    FarewellPhraseMatcher,
    SleepWordMatcher,
)

log = logging.getLogger("relay.conversation")

IDLE = "idle"
CONNECTING = "connecting"
CONVERSING = "conversing"
DRAINING = "draining"
CLOSED = "closed"

STRICT = "strict"
INTERRUPTIBLE = "interruptible"
# Assumption (see docs/model-server-protocol.md): the server's warning codes are not
# documented. A warning ends the conversation if it carries "fatal": true or one of these.
FATAL_WARNING_CODES = frozenset({"fatal"})
BACKLOG_MAX_CHUNKS = 128  # about 10 s of 80 ms uplink frames buffered while connecting
UPLINK_GATE_FRAME_MS = 20  # the uplink gate decides per 20 ms of the robot's 80 ms chunk


def chunk_rms(pcm):
    """RMS of 16-bit little-endian PCM (host order; every target here is little-endian)."""
    samples = array("h")
    samples.frombytes(pcm[: len(pcm) - len(pcm) % 2])
    if not samples:
        return 0.0
    return math.sqrt(sum(s * s for s in samples) / len(samples))


def is_silent(pcm, threshold):
    """True when this reply-channel chunk carries nothing the robot needs to play.

    Exact digital silence is what the server actually sends between replies and is the
    common case, so it is caught by a byte scan, which runs in C; only a chunk with a
    non-zero byte in it costs an RMS."""
    if not any(pcm):
        return True
    return threshold > 0 and chunk_rms(pcm) < threshold


class UplinkGate:
    """Mute the robot's room noise on the audio it sends toward the model.

    Measured on the robot: the microphone uplink has a noise floor of about RMS 300-470,
    while the model's own turn detection is a bare energy VAD that opens above roughly RMS
    131 and only updates its noise estimate while it is NOT voiced (see
    docs/model-server-protocol.md). A floor that never drops below its threshold therefore
    latches it "voiced" for good: `user_start` fires, `user_end` never does, and the model
    never takes its turn. Replacing those frames with true digital silence gives the VAD
    the unvoiced stretches it needs.

    The decision is made per `frame_ms` sub-frame of each chunk, and a chunk is always
    forwarded whole and at its own size -- the model needs a continuous stream, so a quiet
    frame is zeroed, never dropped. `hang_ms` keeps frames unmuted for that long after the
    last loud one so a word's decaying tail is not clipped. A threshold of 0 disables the
    gate and the audio is passed through untouched."""

    def __init__(self, threshold, hang_ms=0.0, rate=INPUT_RATE, frame_ms=UPLINK_GATE_FRAME_MS):
        self.threshold = threshold
        self.hang_ms = max(0.0, hang_ms)
        self.frame_bytes = max(2, int(rate * frame_ms / 1000) * 2)
        self.ms_per_byte = 1000.0 / (rate * 2)  # 16-bit mono
        self.hang_left = 0.0

    @property
    def enabled(self):
        return self.threshold > 0

    def reset(self):
        """Forget the hangover: nothing was said during the audio that skipped the gate."""
        self.hang_left = 0.0

    def process(self, pcm):
        """(audio to forward, sub-frames muted). The result is always len(pcm) bytes."""
        if not self.enabled or not pcm:
            return pcm, 0
        muted = []
        for start in range(0, len(pcm), self.frame_bytes):
            frame = pcm[start:start + self.frame_bytes]
            if chunk_rms(frame) >= self.threshold:
                self.hang_left = self.hang_ms
            elif self.hang_left > 0:
                self.hang_left -= len(frame) * self.ms_per_byte
            else:
                muted.append((start, len(frame)))
        if not muted:
            return pcm, 0
        if sum(n for _, n in muted) == len(pcm):
            return bytes(len(pcm)), len(muted)
        out = bytearray(pcm)
        for start, n in muted:
            out[start:start + n] = bytes(n)
        return bytes(out), len(muted)


@dataclass(frozen=True)
class Config:
    model_host: str = "127.0.0.1"
    model_port: int = DEFAULT_PORT
    model_path: str = DEFAULT_PATH  # the server's WebSocket route; `/` is its browser page
    persona: str = ""
    # conv.open to conv.ready, else conv.close{model_error}. Generous because the model
    # reads the persona in before the session is usable, at roughly 80 ms a word, and the
    # relay waits for that ack; the robot's own limit (15 s) stays above this one.
    ready_timeout: float = 12.0
    silence_timeout: float = 90.0
    drain_cap: float = 8.0
    farewell_start_timeout: float = 1.5  # match to agent_start, else close at once
    # A reply ends this long (milliseconds) after its last non-silent audio chunk. It has
    # to outlast the pauses the model leaves between sentences and fall well inside the
    # drain cap; `agent_end` would end a reply sooner, but the real server never sends one.
    reply_silence_ms: float = 600.0
    # A reply opened by `agent_start` waits this long (milliseconds) for its first sound
    # before it ends as an empty one (`why: "no-speech"`). The child's TTS lags its own
    # agent_start -- about 240 ms in one probe, over 640 ms in a run that lost a farewell --
    # so the end-of-speech hangover above must not start until the reply has any speech.
    reply_start_grace_ms: float = 3000.0
    # Below this RMS a reply-channel chunk counts as silence. The real server sends exact
    # digital silence (RMS 0.0) between replies; the margin is for a noisier build.
    reply_silence_rms: float = 50.0
    # The robot's microphone uplink has a measured noise floor of about RMS 300-470, above
    # the model VAD's own opening threshold (~131), and the VAD only re-estimates its noise
    # while unvoiced -- so ungated room noise latches it "voiced" and `user_end` never
    # fires. Below this RMS an uplink sub-frame is replaced by true digital silence; 0
    # disables the gate. Gating a recorded uplink at 700 made `user_end` fire 2.8 s in.
    uplink_gate_rms: float = 700.0
    # Uplink frames stay unmuted this long (milliseconds) after the last loud one, so the
    # decaying tail of a word is not clipped; 0 mutes on the first quiet frame.
    uplink_gate_hang_ms: float = 200.0
    cooldown: float = 0.3  # strict gate stays shut this long after the robot goes idle
    strict_idle_grace: float = 2.0  # strict gate opens anyway if no idle comes after a reply
    probe_interval: float = 5.0
    probe_timeout: float = 2.0
    sleep_settle: float = 0.5  # running transcript age before the matcher decides on it
    # The farewell phrase the persona is told to end its goodbye with, and the only way a
    # conversation ends by itself today. Must match the persona file; "" disables it.
    farewell_phrase: str = DEFAULT_FAREWELL_PHRASE
    fatal_warning_codes: frozenset = field(default=FATAL_WARNING_CODES)


class _Robot:
    """One linked robot: its conversation, if any, and its health-probe state."""

    def __init__(self, link, model_ok):
        self.link = link
        self.conversation = None
        self.model_ok = model_ok  # what the robot was last told
        self.listening = asyncio.Event()  # no conversation and no model connection open
        self.listening.set()
        self.kick = asyncio.Event()  # wakes the prober early
        self.force_probe = False
        self.probe_task = None  # the probe in flight
        self.model_closing = None  # the task closing the last conversation's connection
        self.prober = None


class Conversation:
    """One conversation's state; the engine drives it."""

    def __init__(self, engine, robot, conv, turn_taking):
        loop = asyncio.get_running_loop()
        self.engine = engine
        self.robot = robot
        self.link = robot.link
        self.conv = conv  # the robot's id for it
        self.id = new_conversation_id()  # the relay's id: the log file's name
        self.turn_taking = turn_taking
        self.strict = turn_taking == STRICT
        self.state = CONNECTING
        self.t0 = loop.time()
        self.client = None
        self.backlog = []  # uplink received while connecting (None once connected)
        self.opener = None
        self.pump = None
        # The live matcher (the model's own farewell phrase) and the dormant transcript
        # one, which has no input against the real server. See relay/sleepword.py.
        self.farewell = FarewellPhraseMatcher(engine.config.farewell_phrase)
        self.matcher = SleepWordMatcher(settle=engine.config.sleep_settle)
        self.user_speaking = False
        self.agent_speaking = False  # a reply is open (derived from the audio)
        self.last_user_end = None  # loop time of the latest user_end not yet answered
        self.reply_id = None  # the reply being forwarded to the robot
        # After a flush: drop what the model had already produced until its stream goes
        # quiet again (or an agent_start says the next reply has begun).
        self.dropping = False
        self.gate_closed = False  # strict: uplink zeroed
        self.gate_reply_ended = False  # strict: the gated reply has reached its derived end
        self.gate_open_at = 0.0  # strict: end of the cooldown
        self.farewell_started = False
        self.farewell_ended = False
        self.turn = None  # the latency record of the current reply
        self.turns = 0
        self.last_stats = None
        # `zeroed`: chunks the strict/draining gate replaced wholesale. `gated`: chunks the
        # uplink noise gate muted part or all of (backlog ones included, so it can exceed
        # `frames`); `gated_frames`: the 20 ms sub-frames inside them.
        self.uplink = {"frames": 0, "bytes": 0, "zeroed": 0, "gated": 0, "gated_frames": 0,
                       "backlog": 0, "backlog_dropped": 0}
        self.uplink_gate = UplinkGate(engine.config.uplink_gate_rms,
                                      engine.config.uplink_gate_hang_ms)
        # Reply-channel audio the model streamed outside any reply, dropped by the gate.
        self.out_of_reply = {"chunks": 0, "bytes": 0}
        self._timers = {}

    def log(self, ev, **fields):
        rt_ms = round((asyncio.get_running_loop().time() - self.t0) * 1000, 1)
        self.engine.logs.write(self.id, ev, rt_ms=rt_ms, **fields)

    def set_timer(self, name, delay, callback, *args):
        self.cancel_timer(name)
        loop = asyncio.get_running_loop()
        self._timers[name] = loop.call_later(max(0.0, delay), self.engine._guard, callback,
                                             self, *args)

    def cancel_timer(self, name):
        handle = self._timers.pop(name, None)
        if handle is not None:
            handle.cancel()

    def cancel_timers(self):
        for handle in self._timers.values():
            handle.cancel()
        self._timers.clear()

    def gated(self, now):
        return self.strict and (self.gate_closed or now < self.gate_open_at)

    def gate_noise(self, pcm):
        """The uplink noise gate applied to one chunk, counted. Same size, always."""
        pcm, muted = self.uplink_gate.process(pcm)
        if muted:
            self.uplink["gated"] += 1
            self.uplink["gated_frames"] += muted
        return pcm


class _NullLogs:
    def write(self, conv_id, ev, **fields):
        return True


class ConversationEngine:
    """The relay's LaneHandler (see relay.lane.LaneHandler for the callback contract).

        engine = ConversationEngine(Config(model_host=..., persona=...), logs)
        await engine.start()                  # first health probe
        async with LaneServer(engine, host, port): ...
        await engine.stop()
    """

    def __init__(self, config, logs=None):
        self.config = config
        self.logs = logs if logs is not None else _NullLogs()
        self.robots = {}  # Link -> _Robot
        self.model_ok_last = True  # the latest probe result, for `welcome`
        self._tasks = set()

    async def start(self):
        self.model_ok_last = await self._probe_once()
        log.info("model server %s:%d %s", self.config.model_host, self.config.model_port,
                 "reachable" if self.model_ok_last else "NOT reachable")

    async def stop(self):
        for robot in list(self.robots.values()):
            if robot.conversation is not None:
                self._end(robot.conversation, "link_lost", notify=False, detail="relay stopping")
            self._forget(robot)
        tasks = [t for t in self._tasks if not t.done()]
        if tasks:
            await asyncio.wait(tasks, timeout=3.0)

    def robot(self, robot_id):
        """The linked robot with this id (for tests and diagnostics), or None."""
        return next((r for r in self.robots.values() if r.link.robot_id == robot_id), None)

    # --- LaneHandler callbacks ---

    def model_ok(self, link):
        return self.model_ok_last

    def on_link(self, link):
        robot = _Robot(link, self.model_ok_last)
        self.robots[link] = robot
        robot.prober = self._spawn(self._prober(robot), f"probe-{link.robot_id}")

    def on_link_closed(self, link, why):
        robot = self.robots.get(link)
        if robot is not None:
            self._forget(robot)

    def on_conv_open(self, link, conv, turn_taking, msg):
        robot = self.robots.get(link)
        if robot is None:  # on_link always comes first; be safe anyway
            self.on_link(link)
            robot = self.robots[link]
        if turn_taking not in (STRICT, INTERRUPTIBLE):
            log.warning("%s conv %s: unknown turn_taking %r, using interruptible",
                        link.robot_id, conv, turn_taking)
        robot.listening.clear()
        robot.force_probe = False
        if robot.probe_task is not None:
            robot.probe_task.cancel()  # its result is discarded; it must not evict us
        c = Conversation(self, robot, conv, turn_taking)
        robot.conversation = c
        cfg = self.config
        c.log("open", conv=conv, robot=link.robot_id, turn_taking=turn_taking,
              model=f"{cfg.model_host}:{cfg.model_port}", persona_chars=len(cfg.persona),
              msg=msg)
        log.info("%s conv %s open (%s, log %s)", link.robot_id, conv, turn_taking, c.id)
        c.set_timer("ready", cfg.ready_timeout, self._ready_expired)
        c.opener = self._spawn(self._open(c), f"model-open-{c.id}")

    def on_uplink(self, link, conv, pcm):
        c = self._current(link, conv)
        if c is None:
            return
        if c.state == CONNECTING:
            if len(c.backlog) >= BACKLOG_MAX_CHUNKS:
                c.uplink["backlog_dropped"] += 1
                return
            c.backlog.append(c.gate_noise(pcm))
            c.uplink["backlog"] += 1
            return
        if c.state not in (CONVERSING, DRAINING):
            return
        c.uplink["frames"] += 1
        c.uplink["bytes"] += len(pcm)
        if c.state == DRAINING or c.gated(asyncio.get_running_loop().time()):
            c.uplink["zeroed"] += 1
            pcm = bytes(len(pcm))
            c.uplink_gate.reset()  # nothing was said into audio the robot never got to send
        else:
            pcm = c.gate_noise(pcm)
        c.client.send_audio(pcm)

    def on_conv_close(self, link, conv, reason, msg):
        c = self._current(link, conv)
        if c is None:
            return
        if msg is not None:
            c.log("robot", msg=msg)
        self._end(c, reason if reason in CLOSE_REASONS else "robot_request", notify=False)

    def on_playback(self, link, msg):
        c = self._current(link, msg.get("conv"))
        if c is None:
            return
        c.log("robot", msg=msg)
        to_play = msg.get("first_chunk_to_play_ms")
        if (to_play is not None and c.turn is not None
                and c.turn.get("robot_first_chunk_to_play_ms") is None):
            c.turn["robot_first_chunk_to_play_ms"] = to_play
        if msg.get("state") != "idle":
            return
        if c.agent_speaking and c.turn is not None and c.turn["reply_chunks"]:
            # The robot ran dry while a reply is still open: an underrun, not the end of
            # the reply, so a strict gate stays closed. A reply still waiting out the TTS
            # lag has been sent nothing to play, so an idle during it is not an underrun --
            # it is the robot finishing whatever came before.
            if c.turn is not None:
                c.turn["underruns"] += 1
            c.log("underrun", reply=c.reply_id)
            log.warning("%s conv %s: playback idle before agent_end (underrun)",
                        link.robot_id, c.conv)
            return
        now = asyncio.get_running_loop().time()
        if c.strict and c.gate_closed and c.gate_reply_ended:
            self._open_gate(c, now, "idle")
        if c.state == DRAINING and c.farewell_ended:
            self._end(c, "sleep_word")

    def on_cmd_result(self, link, msg):
        robot = self.robots.get(link)
        c = robot.conversation if robot is not None else None
        if c is not None and msg.get("conv") == c.conv:
            c.log("robot", msg=msg)

    # --- opening and closing ---

    async def _open(self, c):
        robot = c.robot
        waits = [t for t in (robot.probe_task, robot.model_closing)
                 if t is not None and not t.done()]
        if waits:
            await asyncio.wait(waits)
        if c.state != CONNECTING:
            return
        cfg = self.config
        try:
            c.client = ModelClient(cfg.model_host, cfg.model_port, persona=cfg.persona,
                                   path=cfg.model_path, open_timeout=cfg.ready_timeout)
            # The client's own ack timeout stays at its generous default: this
            # conversation's limit is the "ready" timer above, so the two never race.
            # open() sends the backlog list as it iterates it, so frames the robot sends
            # during the connect are appended to it and still go first, in order.
            await c.client.open(backlog=c.backlog)
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001 - OSError, TimeoutError, InvalidHandshake...
            log.warning("%s conv %s: model connection failed: %r", c.link.robot_id, c.conv, exc)
            if c.state == CONNECTING:
                self._end(c, "model_error", detail=f"model connection failed: {exc!r}")
            return
        if c.state != CONNECTING:
            return
        c.backlog = None
        c.state = CONVERSING
        c.cancel_timer("ready")
        c.link.send_conv_ready(c.conv)
        c.log("relay", msg={"type": "conv.ready"}, backlog_chunks=c.uplink["backlog"],
              system_ms=c.client.system_ms)  # what reading the persona in cost this wake
        self._arm_silence(c)
        c.pump = self._spawn(self._pump(c), f"model-events-{c.id}")

    def _ready_expired(self, c):
        if c.state == CONNECTING:
            self._end(c, "model_error",
                      detail=f"no model connection within {self.config.ready_timeout} s")

    def _end(self, c, reason, notify=True, detail=None):
        """Close the conversation (idempotent): conv.close to the robot unless the robot or
        the lane ended it, then the model connection, then probing resumes."""
        if c.state == CLOSED:
            return
        previous, c.state = c.state, CLOSED
        c.cancel_timers()
        self._finish_turn(c)
        if notify:
            c.link.close_conv(reason, c.conv)
        c.log("close", reason=reason, state=previous, detail=detail,
              duration_ms=round((asyncio.get_running_loop().time() - c.t0) * 1000, 1),
              system_ms=c.client.system_ms if c.client else None,
              turns=c.turns, uplink=c.uplink, out_of_reply=c.out_of_reply,
              model_chunks_sent=c.client.chunks_sent if c.client else 0,
              model_zero_frames=c.client.zero_frames_sent if c.client else 0)
        log.info("%s conv %s closed: %s%s (%d out-of-reply audio chunks dropped)",
                 c.link.robot_id, c.conv, reason, f" ({detail})" if detail else "",
                 c.out_of_reply["chunks"])
        robot = c.robot
        if robot.conversation is c:
            robot.conversation = None
        # Probe at once, and report even an unchanged result, after a failure: the robot's
        # way out of its unreachable state keys off that status frame (KTD3, KTD4).
        force = reason == "model_error" or previous == CONNECTING
        robot.model_closing = self._spawn(self._shutdown(c, force), f"model-close-{c.id}")

    async def _shutdown(self, c, force):
        me = asyncio.current_task()
        tasks = [t for t in (c.opener, c.pump) if t is not None and t is not me and not t.done()]
        for task in tasks:
            task.cancel()
        if tasks:
            await asyncio.wait(tasks)
        if c.client is not None:
            await c.client.close()
        robot = c.robot
        if robot.model_closing is me:
            robot.model_closing = None
        if robot.conversation is None and not robot.link.closed:
            robot.force_probe = robot.force_probe or force
            robot.listening.set()
            robot.kick.set()

    def _forget(self, robot):
        self.robots.pop(robot.link, None)
        for task in (robot.prober, robot.probe_task):
            if task is not None:
                task.cancel()

    # --- model events ---

    async def _pump(self, c):
        async for event in c.client.events():
            if c.state == CLOSED:
                return
            try:
                self._on_model_event(c, event)
            except Exception:  # noqa: BLE001 - one bad event must not end the conversation
                log.exception("%s conv %s: handling %r failed", c.link.robot_id, c.conv,
                              type(event).__name__)

    def _on_model_event(self, c, ev):
        now = asyncio.get_running_loop().time()
        if isinstance(ev, AudioChunk):
            c.log("model.audio", bytes=len(ev.pcm))
            self._on_reply_audio(c, ev.pcm, now)
            return
        if isinstance(ev, Closed):
            c.log("model.closed", code=ev.code, reason=ev.reason, error=ev.error)
            self._end(c, "model_error", detail=f"model connection closed ({ev.code} {ev.reason})")
            return
        c.log("model", msg=ev.data)
        if isinstance(ev, UserStart):
            c.user_speaking = True
            c.cancel_timer("silence")
        elif isinstance(ev, UserTextDelta):
            self._decide(c, c.matcher.feed_partial(ev.transcript, now) if self._can_match(c, now)
                         else None)
            self._schedule_match_poll(c)
        elif isinstance(ev, UserEnd):
            c.user_speaking = False
            c.last_user_end = now
            if not c.agent_speaking:
                self._arm_silence(c)
            if c.client.transcript and self._can_match(c, now):
                self._decide(c, c.matcher.feed_final(c.client.transcript, now))
        elif isinstance(ev, UserText):
            if self._can_match(c, now):
                self._decide(c, c.matcher.feed_final(ev.text, now))
            else:
                c.log("sleepword.skipped", transcript=ev.text,
                      why="draining" if c.state == DRAINING else "strict gate closed")
            c.matcher.reset()
            c.cancel_timer("match_poll")
        elif isinstance(ev, AgentStart):
            self._on_agent_start(c, now)
        elif isinstance(ev, AgentEnd):
            self._on_agent_end(c, now)
        elif isinstance(ev, Flush):
            if c.reply_id is not None:
                self._end_reply(c, now, "flush")
            else:
                c.dropping = True
        elif isinstance(ev, Stats):
            c.last_stats = ev.data
        elif isinstance(ev, ModelError):
            self._end(c, "model_error", detail=f"model error {ev.code!r}: {ev.message}")
        elif isinstance(ev, ModelWarning):
            if ev.data.get("fatal") is True or ev.code in self.config.fatal_warning_codes:
                self._end(c, "model_error", detail=f"fatal warning {ev.code!r}: {ev.message}")
            else:
                log.warning("%s conv %s: model warning %r: %s", c.link.robot_id, c.conv,
                            ev.code, ev.message)
        elif isinstance(ev, AssistantTextDelta):
            # The model's own text is the only text on the channel, and its farewell phrase
            # is how a conversation ends here. The person's words are never matched against
            # it: there is no transcript of them at all (KTD2 stays dormant).
            if c.state == CONVERSING:
                self._decide(c, c.farewell.feed(ev.text))

    def _on_agent_start(self, c, now):
        """agent_start does arrive (measured); it is only agent_end that never does. If the
        audio beat it to the reply by a chunk or two, that reply is this one."""
        if c.reply_id is not None:
            if c.turn is not None and c.turn["begun_by"] == "audio":
                c.turn["begun_by"] = "audio+agent_start"
                c.agent_speaking = True
                return
            self._end_reply(c, now, "agent_start")  # back-to-back replies, no agent_end
        self._begin_reply(c, now, "agent_start")

    def _on_agent_end(self, c, now):
        if c.reply_id is not None:
            self._end_reply(c, now, "agent_end")
            return
        c.agent_speaking = False  # the silence hangover had already ended the reply
        if not c.user_speaking:
            self._arm_silence(c)

    def _gate_fallback(self, c):
        if c.state != CLOSED and c.gate_closed:
            log.warning("%s conv %s: no playback idle after the reply; opening the strict "
                        "gate anyway", c.link.robot_id, c.conv)
            self._open_gate(c, asyncio.get_running_loop().time(), "fallback")

    def _open_gate(self, c, now, why):
        c.gate_closed = False
        c.gate_open_at = now + self.config.cooldown
        c.cancel_timer("gate_fallback")
        c.log("gate.open", why=why, cooldown_ms=round(self.config.cooldown * 1000))

    def _begin_reply(self, c, now, why):
        """Open a reply: the model has started speaking, by its own event or by its audio."""
        self._finish_turn(c)
        c.agent_speaking = True
        c.cancel_timer("silence")
        c.reply_id = c.link.begin_reply()
        c.dropping = False
        c.turns += 1
        c.turn = {"n": c.turns, "reply": c.reply_id, "begun_by": why, "end_reason": None,
                  "reply_ms": None, "user_end_to_first_chunk_ms": None,
                  "robot_first_chunk_to_play_ms": None, "reply_chunks": 0, "reply_bytes": 0,
                  "loud_chunks": 0, "silent_chunks": 0, "dropped_chunks": 0, "flushed": False,
                  "underruns": 0, "_start": now, "_user_end": c.last_user_end,
                  "_first_chunk": None, "_first_loud": None}
        c.last_user_end = None
        c.log("relay", msg={"type": "reply", "id": c.reply_id}, begun_by=why)
        # Two timers govern a reply, never both at once. Until it has any speech that is the
        # START GRACE: the child's TTS lags its own agent_start by a variable amount (~240 ms
        # in one probe, over 640 ms in a run that lost a farewell), and the end-of-speech
        # hangover must not run before there is speech to hang over -- it would end the reply
        # empty and the audio would arrive out of reply and be dropped. From the first loud
        # chunk the HANGOVER governs. The grace is also the bound that stops an agent_start
        # the model never speaks after from holding the reply -- and with it the strict gate
        # and the silence timer -- open for good.
        c.set_timer("reply_start_grace", self.config.reply_start_grace_ms / 1000,
                    self._reply_start_grace_expired)
        if c.strict:
            c.gate_closed = True
            c.gate_reply_ended = False
            c.cancel_timer("gate_fallback")
            c.log("gate.close", reply=c.reply_id)
        if c.state == DRAINING:
            c.farewell_started = True
            c.farewell_ended = False  # this reply is the farewell now; wait for it
            c.cancel_timer("farewell_start")

    def _end_reply(self, c, now, why):
        """Close the reply on its derived end (`silence`) or on whichever model signal got
        there first (`agent_end`, `flush`, the next `agent_start`). end_reply() lets the
        lane's pacer finish the reply, which is what lets the robot's speaker run dry and
        report playback idle -- the drain and the strict gate both wait on that."""
        c.cancel_timer("reply_silence")
        c.cancel_timer("reply_start_grace")
        c.agent_speaking = False
        reply_id, c.reply_id = c.reply_id, None
        if reply_id is None:
            return
        turn = c.turn
        if why == "flush":
            c.link.flush_reply(reply_id)
            c.log("relay", msg={"type": "audio.flush", "reply": reply_id})
            c.dropping = True
            if turn is not None:
                turn["flushed"] = True
        else:
            c.link.end_reply(reply_id)
        duration_ms = round((now - turn["_start"]) * 1000, 1) if turn is not None else None
        if turn is not None:
            turn["end_reason"] = why
            turn["reply_ms"] = duration_ms
            if why == "agent_end":
                turn["agent_end_ms"] = duration_ms
        c.log("reply.end", reply=reply_id, why=why, duration_ms=duration_ms,
              audio_ms=(round(turn["reply_bytes"] / 2 / OUTPUT_RATE * 1000, 1)
                        if turn is not None else None),
              chunks=turn["reply_chunks"] if turn is not None else None)
        if not c.user_speaking:
            self._arm_silence(c)
        if c.strict and c.gate_closed:
            c.gate_reply_ended = True
            if turn is None or turn["reply_bytes"] == 0:
                # Nothing was ever sent for this reply, so there is no playback to wait for
                # and no playback{idle} to expect: open now rather than on the backstop.
                self._open_gate(c, now, "empty")
            else:
                # Backstop: if the robot never reports idle for this reply, open anyway once
                # its audio must have played out, so the person cannot be muted for good.
                left = turn["reply_bytes"] / 2 / OUTPUT_RATE
                if turn["_first_chunk"] is not None:
                    left -= now - turn["_first_chunk"]
                c.set_timer("gate_fallback", max(0.0, left) + self.config.strict_idle_grace,
                            self._gate_fallback)
        if c.state == DRAINING and c.farewell_started:
            if turn is not None and turn["loud_chunks"]:
                # The farewell was spoken. Closing waits for the robot to play it out (R2).
                c.farewell_ended = True
            else:
                # Not a word of it was forwarded: the model's text ran seconds ahead of its
                # TTS and this reply carried only the lag. Closing here would cut the
                # farewell off before it is spoken, which is the one thing the drain exists
                # to prevent, so wait for a reply that does speak. The drain cap is the
                # backstop if none ever comes.
                c.farewell_started = False
                c.log("drain.empty", reply=reply_id, why=why)

    def _reply_silence_expired(self, c):
        """No non-silent audio for reply_silence_ms (the hangover): the reply is over."""
        if c.state != CLOSED and c.reply_id is not None:
            self._end_reply(c, asyncio.get_running_loop().time(), "silence")

    def _reply_start_grace_expired(self, c):
        """An agent_start the model has still not spoken after reply_start_grace_ms: end the
        reply empty rather than hold the conversation open. Nothing was forwarded, so the
        person heard nothing; audio that arrives later still opens a fresh reply."""
        if c.state != CLOSED and c.reply_id is not None:
            self._end_reply(c, asyncio.get_running_loop().time(), "no-speech")

    def _on_reply_audio(self, c, pcm, now):
        """Forward the model's audio to the robot only while a reply is open, and derive
        that reply's own start and end from the audio.

        The model streams reply-channel audio continuously, whether or not it is speaking
        (docs/model-server-protocol.md: 228 frames over one 20 s run, no gap above 0.4 s),
        and what it streams outside a reply is exact digital silence. Forwarding all of it
        would keep the robot's speaker permanently busy, so its playback{idle} would never
        arrive, the farewell drain would always run to its cap and the strict gate would
        only ever reopen on its backstop. agent_start still says "I am speaking", but the
        matching agent_end is measured never to come, so sound starting and sound stopping
        are what open and close a reply.

        agent_start runs ahead of the sound: the child's TTS lags it. While a reply that
        agent_start opened is still waiting for its first loud chunk, the silence it is
        waiting through is dropped like any other out-of-reply silence, and reply_start_grace
        rather than the hangover bounds the wait."""
        silent = is_silent(pcm, self.config.reply_silence_rms)
        if c.reply_id is None:
            if c.dropping:
                # Flushed mid-reply: the tail the model had already produced is discarded
                # until its stream goes quiet again (or agent_start opens the next reply).
                if not silent:
                    if c.turn is not None:
                        c.turn["dropped_chunks"] += 1
                    return
                c.dropping = False
            if silent:
                c.out_of_reply["chunks"] += 1
                c.out_of_reply["bytes"] += len(pcm)
                return
            self._begin_reply(c, now, "audio")
        turn = c.turn
        if silent and turn is not None and turn["_first_loud"] is None:
            # The reply is open (agent_start) but has not started speaking yet: this is the
            # TTS lag. Drop it like any other out-of-reply silence, so the robot is not given
            # a lag to play before the reply's first word and an empty reply forwards nothing.
            c.out_of_reply["chunks"] += 1
            c.out_of_reply["bytes"] += len(pcm)
            return
        if not c.link.send_reply_audio(c.reply_id, pcm):
            return
        if turn["_first_chunk"] is None:
            turn["_first_chunk"] = now
        turn["reply_chunks"] += 1
        turn["reply_bytes"] += len(pcm)
        if silent:
            turn["silent_chunks"] += 1
            return  # the hangover armed by the last non-silent chunk keeps running
        turn["loud_chunks"] += 1
        if turn["_first_loud"] is None:
            turn["_first_loud"] = now
            c.cancel_timer("reply_start_grace")  # the hangover governs from here
            if turn["_user_end"] is not None:
                turn["user_end_to_first_chunk_ms"] = round((now - turn["_user_end"]) * 1000, 1)
        c.set_timer("reply_silence", self.config.reply_silence_ms / 1000,
                    self._reply_silence_expired)

    def _finish_turn(self, c):
        """Write the latency record of the reply just finished (at the next reply or the
        close, so the robot's playback report for it has had time to arrive)."""
        turn, c.turn = c.turn, None
        if turn is None:
            return
        ws = getattr(c.link, "ws", None)
        latency = getattr(ws, "latency", None)
        record = {k: v for k, v in turn.items() if not k.startswith("_")}
        record["reply_audio_ms"] = round(turn["reply_bytes"] / 2 / OUTPUT_RATE * 1000, 1)
        record["half_ping_rtt_ms"] = round(latency * 500, 1) if latency is not None else None
        record["stats"] = c.last_stats
        c.log("turn", **record)

    # --- sleep word ---

    def _can_match(self, c, now):
        return c.state == CONVERSING and not c.gated(now)

    def _schedule_match_poll(self, c):
        due = c.matcher.next_due()
        if due is None:
            c.cancel_timer("match_poll")
        else:
            c.set_timer("match_poll", due - asyncio.get_running_loop().time(), self._poll_match)

    def _poll_match(self, c):
        now = asyncio.get_running_loop().time()
        if self._can_match(c, now):
            self._decide(c, c.matcher.poll(now))
            self._schedule_match_poll(c)

    def _decide(self, c, decision):
        if decision is None:
            return
        c.log("sleepword", kind=decision.kind, score=decision.score, words=decision.words,
              transcript=decision.transcript, source=decision.source)
        if decision.kind == NEAR_MISS:
            log.info("%s conv %s: sleep-word near miss %d %r", c.link.robot_id, c.conv,
                     decision.score, decision.transcript)
        elif decision.kind == MATCH and c.state == CONVERSING:
            log.info("%s conv %s: sleep word %r (%s): draining", c.link.robot_id, c.conv,
                     decision.transcript, decision.source)
            self._start_drain(c)

    def _start_drain(self, c):
        c.state = DRAINING
        c.cancel_timer("silence")
        c.cancel_timer("match_poll")
        # The farewell phrase is matched while the farewell is already being spoken, so
        # the reply in flight IS the farewell and no further agent_start is coming.
        c.farewell_started = c.agent_speaking
        c.farewell_ended = False
        c.log("drain", agent_speaking=c.agent_speaking)
        cfg = self.config
        c.set_timer("drain_cap", cfg.drain_cap, self._drain_expired)
        if not c.farewell_started:
            c.set_timer("farewell_start", cfg.farewell_start_timeout,
                        self._farewell_start_expired)

    def _drain_expired(self, c):
        if c.state == DRAINING:
            self._end(c, "farewell_timeout")

    def _farewell_start_expired(self, c):
        if c.state == DRAINING and not c.farewell_started:
            self._end(c, "sleep_word", detail="no farewell started")

    # --- silence ---

    def _arm_silence(self, c):
        if c.state == CONVERSING:
            c.set_timer("silence", self.config.silence_timeout, self._silence_expired)

    def _silence_expired(self, c):
        if c.state == CONVERSING and not c.agent_speaking:
            self._end(c, "silence")

    # --- health probe ---

    async def _probe_once(self):
        """True only when the server says state "ready": it answers while its model is
        still "loading" and after its child has died ("dead"), and neither can converse."""
        cfg = self.config
        try:
            status = await probe(cfg.model_host, cfg.model_port, timeout=cfg.probe_timeout,
                                 path=cfg.model_path)
        except ProbeError as exc:
            log.debug("probe failed: %s", exc)
            return False
        if not model_reachable(status):
            log.info("model answered but is not ready: state=%r", model_state(status) or "?")
            return False
        return True

    async def _prober(self, robot):
        """Probe while the robot is listening; never while a conversation connection is
        open or opening (a probe connection would evict it)."""
        while not robot.link.closed:
            await robot.listening.wait()
            if not robot.listening.is_set():
                continue
            force, robot.force_probe = robot.force_probe, False
            robot.kick.clear()
            task = asyncio.create_task(self._probe_once(), name=f"probe-{robot.link.robot_id}")
            robot.probe_task = task
            try:
                await asyncio.wait({task})
            except asyncio.CancelledError:
                task.cancel()
                raise
            finally:
                if robot.probe_task is task:
                    robot.probe_task = None
            if task.cancelled() or not robot.listening.is_set():
                continue  # a conversation opened: the result is stale
            self._report_model_ok(robot, task.result(), force)
            try:
                await asyncio.wait_for(robot.kick.wait(), self.config.probe_interval)
            except TimeoutError:
                pass

    def _report_model_ok(self, robot, ok, force):
        changed = ok != robot.model_ok
        robot.model_ok = ok
        self.model_ok_last = ok
        if changed or force:
            robot.link.send_status(ok)
            log.log(logging.INFO if changed else logging.DEBUG, "%s status model_ok=%s%s",
                    robot.link.robot_id, ok, "" if changed else " (unchanged, after a failure)")

    # --- helpers ---

    def _current(self, link, conv):
        robot = self.robots.get(link)
        c = robot.conversation if robot is not None else None
        if c is None or conv is None or conv != c.conv:
            return None
        return c

    def _guard(self, callback, *args):
        try:
            callback(*args)
        except Exception:  # noqa: BLE001 - a timer bug must not take the loop down
            log.exception("conversation timer %s failed", getattr(callback, "__name__", callback))

    def _spawn(self, coro, name=None):
        task = asyncio.create_task(coro, name=name)
        self._tasks.add(task)
        task.add_done_callback(self._task_done)
        return task

    def _task_done(self, task):
        self._tasks.discard(task)
        if not task.cancelled() and task.exception() is not None:
            log.error("relay task %s failed", task.get_name(), exc_info=task.exception())
