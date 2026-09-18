"""The conversation engine (plan U5, KTD4, KTD5): the lane's handler for the real relay.

Per robot link the engine is `idle` (listening) or has one conversation, which moves
connecting -> conversing -> [draining] -> closed:

- connecting: conv.open arrived. Any health probe in flight is cancelled and its result
  discarded (the model server is single-client, so a probe would evict the conversation),
  then one ModelClient connects and sends `reset` and `system` (the persona): every
  conversation is a fresh session (R9). Uplink that arrives meanwhile is kept and goes to
  the model back to back once connected. No connection within ready_timeout (3 s) ends
  it with conv.close{model_error}.
- conversing: conv.ready sent. Uplink goes straight to the model; model events drive the
  reply (reply / audio / audio.flush on the lane), the silence timer and the sleep-word
  matcher. The silence timer is armed at conv.ready, re-armed at user_end or agent_end
  (whichever is later), and cancelled by user_start or agent_start, so it never cuts a
  reply off (R3).
- draining: the person's transcript said "Goodbye Miko" (sleepword.py). The uplink is
  replaced by silence; the model's farewell is forwarded until agent_end and a robot
  playback{idle} after it, then conv.close{sleep_word}. No agent_start within
  farewell_start_timeout (1.5 s) of the match closes at once; drain_cap (8 s) closes with
  farewell_timeout.
- closed: conv.close sent (unless the robot or the lane ended it) and the model
  connection closed. A model error, a fatal warning or the model connection closing ends
  it with model_error; the robot link stays up.

Turn-taking (KTD5): in "interruptible" conversations the model's `flush` stops the reply
at once (the paced queue is discarded and audio.flush{reply} sent). In "strict" ones the
uplink is replaced by zeros from agent_start until agent_end has arrived and the robot
then reports playback idle, plus the cooldown; an idle before agent_end is an underrun,
logged, and the gate stays closed. The person's transcript is not matched while the gate
is closed.

Health (KTD4): while a robot is listening the engine probes the model server every
probe_interval and pushes status{model_ok} when it changes. After a conversation that
failed to open or ended with model_error, or that the robot closed while connecting, it
probes at once and pushes the result even if unchanged. Probes run only while no
conversation connection is open or opening.

Every conversation writes one JSONL log (relay.logging) with the model events, lane
frames (never audio bytes), matcher decisions and a per-turn latency record.
"""
import asyncio
import logging
from dataclasses import dataclass, field

from relay.lane import CLOSE_REASONS
from relay.logging import new_conversation_id
from relay.model_client import (
    DEFAULT_PORT,
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
    probe,
)
from relay.sleepword import MATCH, NEAR_MISS, SleepWordMatcher

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


@dataclass(frozen=True)
class Config:
    model_host: str = "127.0.0.1"
    model_port: int = DEFAULT_PORT
    persona: str = ""
    ready_timeout: float = 3.0  # conv.open to conv.ready, else conv.close{model_error}
    silence_timeout: float = 90.0
    drain_cap: float = 8.0
    farewell_start_timeout: float = 1.5  # match to agent_start, else close at once
    cooldown: float = 0.3  # strict gate stays shut this long after the robot goes idle
    strict_idle_grace: float = 2.0  # strict gate opens anyway if no idle comes after a reply
    probe_interval: float = 5.0
    probe_timeout: float = 2.0
    sleep_settle: float = 0.5  # running transcript age before the matcher decides on it
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
        self.matcher = SleepWordMatcher(settle=engine.config.sleep_settle)
        self.user_speaking = False
        self.agent_speaking = False  # agent_start seen, agent_end not yet
        self.last_user_end = None  # loop time of the latest user_end not yet answered
        self.reply_id = None  # the reply being forwarded to the robot
        self.dropping = False  # after a flush: drop audio until the next agent_start
        self.gate_closed = False  # strict: uplink zeroed
        self.gate_agent_end = False  # strict: agent_end has arrived for the gated reply
        self.gate_open_at = 0.0  # strict: end of the cooldown
        self.farewell_started = False
        self.farewell_ended = False
        self.turn = None  # the latency record of the current reply
        self.turns = 0
        self.last_stats = None
        self.uplink = {"frames": 0, "bytes": 0, "zeroed": 0, "backlog": 0, "backlog_dropped": 0}
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
            c.backlog.append(pcm)
            c.uplink["backlog"] += 1
            return
        if c.state not in (CONVERSING, DRAINING):
            return
        c.uplink["frames"] += 1
        c.uplink["bytes"] += len(pcm)
        if c.state == DRAINING or c.gated(asyncio.get_running_loop().time()):
            c.uplink["zeroed"] += 1
            pcm = bytes(len(pcm))
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
        if c.agent_speaking:
            # The robot ran dry while the model is still replying: an underrun, not the
            # end of the reply, so a strict gate stays closed.
            if c.turn is not None:
                c.turn["underruns"] += 1
            c.log("underrun", reply=c.reply_id)
            log.warning("%s conv %s: playback idle before agent_end (underrun)",
                        link.robot_id, c.conv)
            return
        now = asyncio.get_running_loop().time()
        if c.strict and c.gate_closed and c.gate_agent_end:
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
                                   open_timeout=cfg.ready_timeout)
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
        c.log("relay", msg={"type": "conv.ready"}, backlog_chunks=c.uplink["backlog"])
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
              turns=c.turns, uplink=c.uplink,
              model_chunks_sent=c.client.chunks_sent if c.client else 0,
              model_zero_frames=c.client.zero_frames_sent if c.client else 0)
        log.info("%s conv %s closed: %s%s", c.link.robot_id, c.conv, reason,
                 f" ({detail})" if detail else "")
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
                c.link.flush_reply(c.reply_id)
                c.log("relay", msg={"type": "audio.flush", "reply": c.reply_id})
                if c.turn is not None:
                    c.turn["flushed"] = True
                c.reply_id = None
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
            pass  # logged above; never matched (KTD2)

    def _on_agent_start(self, c, now):
        self._finish_turn(c)
        c.agent_speaking = True
        c.cancel_timer("silence")
        self._begin_reply(c, now)
        if c.strict:
            c.gate_closed = True
            c.gate_agent_end = False
            c.cancel_timer("gate_fallback")
            c.log("gate.close", reply=c.reply_id)
        if c.state == DRAINING and not c.farewell_started:
            c.farewell_started = True
            c.cancel_timer("farewell_start")

    def _on_agent_end(self, c, now):
        c.agent_speaking = False
        if c.reply_id is not None:
            c.link.end_reply(c.reply_id)
            c.reply_id = None
        turn = c.turn
        if turn is not None:
            turn["agent_end_ms"] = round((now - turn["_start"]) * 1000, 1)
        if not c.user_speaking:
            self._arm_silence(c)
        if c.strict and c.gate_closed:
            c.gate_agent_end = True
            # Backstop: if the robot never reports idle for this reply, open anyway once
            # its audio must have played out, so the person cannot be muted for good.
            left = 0.0
            if turn is not None and turn["_first_chunk"] is not None:
                left = turn["reply_bytes"] / 2 / OUTPUT_RATE - (now - turn["_first_chunk"])
            c.set_timer("gate_fallback", max(0.0, left) + self.config.strict_idle_grace,
                        self._gate_fallback)
        if c.state == DRAINING and c.farewell_started:
            c.farewell_ended = True
            if turn is None or turn["reply_bytes"] == 0:
                self._end(c, "sleep_word")  # a farewell with no audio: nothing to wait for

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

    def _begin_reply(self, c, now):
        c.reply_id = c.link.begin_reply()
        c.dropping = False
        c.turns += 1
        c.turn = {"n": c.turns, "reply": c.reply_id, "user_end_to_first_chunk_ms": None,
                  "robot_first_chunk_to_play_ms": None, "reply_chunks": 0, "reply_bytes": 0,
                  "dropped_chunks": 0, "flushed": False, "underruns": 0,
                  "_start": now, "_user_end": c.last_user_end, "_first_chunk": None}
        c.last_user_end = None
        c.log("relay", msg={"type": "reply", "id": c.reply_id})

    def _on_reply_audio(self, c, pcm, now):
        if c.reply_id is None:
            if c.dropping or c.state == CLOSED:
                if c.turn is not None:
                    c.turn["dropped_chunks"] += 1
                return
            self._begin_reply(c, now)  # audio with no agent_start: still a reply
        if not c.link.send_reply_audio(c.reply_id, pcm):
            return
        turn = c.turn
        if turn["_first_chunk"] is None:
            turn["_first_chunk"] = now
            if turn["_user_end"] is not None:
                turn["user_end_to_first_chunk_ms"] = round((now - turn["_user_end"]) * 1000, 1)
        turn["reply_chunks"] += 1
        turn["reply_bytes"] += len(pcm)

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
        c.farewell_started = False
        c.farewell_ended = False
        c.log("drain", agent_speaking=c.agent_speaking)
        cfg = self.config
        c.set_timer("drain_cap", cfg.drain_cap, self._drain_expired)
        c.set_timer("farewell_start", cfg.farewell_start_timeout, self._farewell_start_expired)

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
        cfg = self.config
        try:
            await probe(cfg.model_host, cfg.model_port, timeout=cfg.probe_timeout)
            return True
        except ProbeError as exc:
            log.debug("probe failed: %s", exc)
            return False

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
