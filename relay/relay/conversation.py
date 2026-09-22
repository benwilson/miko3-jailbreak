"""The conversation engine (plan U5, KTD4, KTD5): the lane's handler for the real relay.

Per robot link the engine is `idle` (listening) or has one conversation, which moves
connecting -> conversing -> [draining] -> closed:

- connecting: conv.open arrived. With prewarm on this is usually instant: a warm session
  is waiting, already reset and carrying the persona, and the conversation takes it over
  and sends conv.ready in the same turn of the event loop. Otherwise (prewarm off, or no
  warm session ready) any health probe in flight is cancelled and its result discarded
  (the model server is single-client, so a probe would evict the conversation), then one
  ModelClient connects and sends `reset` and `system` (the persona) -- and this wake pays
  that persona read, measured at 1.5-2.8 s idle and 10.9 s on a busy server. Either way
  the session is fresh (R9). Uplink that arrives meanwhile is kept and goes to the model
  back to back once the persona has been acknowledged. No ready session within
  ready_timeout (12 s) ends it with conv.close{model_error}.
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
  of it is that the person hears the goodbye (R2). If a match arrives between replies with
  no speech outstanding, no agent_start within farewell_start_timeout (1.5 s) closes at
  once; if the model still owes speech it opened a reply for, only drain_cap (8 s) bounds
  the wait, because that is the goodbye still on its way. drain_cap closes with
  farewell_timeout either way.
- closed: conv.close sent (unless the robot or the lane ended it) and the model
  connection closed. A model error, a fatal warning or the model connection closing ends
  it with model_error; the robot link stays up.

Reply audio (see docs/model-server-protocol.md): the model streams reply-channel audio
continuously, and between replies it is exact digital silence. `agent_end` is measured
never to arrive when a reply simply finishes -- the child emits it only when the person
takes the floor back -- so the reply's boundaries are derived from the audio itself:

- a LOUD chunk is ALWAYS forwarded, in every state. If no reply is open it opens one first
  -- including while the conversation is DRAINING, because a farewell that starts speaking
  late must still be heard. Loud audio is never dropped and never counted out of reply;
- a SILENT chunk is forwarded only inside a reply that has already spoken, which is what
  keeps the pause between two sentences from splitting one reply. Otherwise it is dropped
  and counted as `out_of_reply`, so that counter only ever counts silence;
- a reply BEGINS at its first loud chunk, or at agent_start if that comes first;
- one opened by agent_start then waits `reply_start_grace_ms` for its first sound, because
  the child's TTS lags its own agent_start by a variable amount (~240 ms in one probe, over
  640 ms in a run that lost a farewell, and past 3 s in the run that lost a whole reply).
  The grace expiring ends that reply `no-speech` and costs nothing but a reply id: audio
  that arrives afterwards opens its own reply and plays in full. It also records that the
  model still owes this conversation speech, which is what the drain below waits for;
- from its first loud chunk it ENDS `reply_silence_ms` after its last loud chunk, or at
  agent_end or flush if one of those arrives first.

Dropping the silence keeps the robot's speaker draining, so its playback{idle} arrives and
neither the farewell drain nor the strict gate has to fall back to its timer. Every reply
logs a `reply.end` record with its duration and which signal ended it, so the field data
shows whether silence, agent_end or flush is doing the work.

Turn-taking (KTD5): in "interruptible" conversations the model's `flush` stops the reply
at once (the paced queue is discarded and audio.flush{reply} sent). In "strict" ones the
uplink is replaced by zeros from the reply's start until it has ended and the robot then
reports playback idle, plus the cooldown; an idle before the derived end is an underrun,
logged, and the gate stays closed. The dormant transcript matcher is not fed while the
gate is closed; the farewell matcher reads the model's own text and is unaffected.

The warm session (prewarm, on by default): one is opened when a robot links and after
every conversation closes, and it is handed to a conversation exactly once -- a session
that has carried a conversation has heard it, so it can never be warm again (R9). It costs
the model server's one client slot for as long as a robot is linked, which is why
--no-prewarm exists and why the owner's browser page cannot be open at the same time.

Health (KTD4): while a robot is listening the engine pushes status{model_ok} when it
changes, and after a conversation that failed to open or ended with model_error, or that
the robot closed while connecting, it pushes the result even if unchanged. Where that
answer comes from depends on prewarm, because the server takes one client and the two
sources must never race:

A warm session waits SILENTLY: it sends `reset` and the persona and then nothing at all
until a conversation adopts it, when its uplink (and the zero-frame watchdog the model's VAD
needs between the person's words) starts. The model server is slower than realtime, so idle
frames only build its input backlog. Its liveness is still watched: the connection closing,
not traffic on it, is what says the model went away.

- prewarm ON: the warm session IS the health signal. Opening one is a stronger check than
  `status` (it proves the persona can be read in, not just that the port answers), and its
  connection dying is what says the model went away. A failed or lost warm session is
  retried every probe_interval, which is also what re-reports model_ok when it comes back.
- prewarm OFF: the probe path, unchanged -- probe() every probe_interval while the robot is
  listening, never while a conversation connection is open or opening.

engine.start() probes once in both modes: no robot is linked yet, so nothing is warm.

Every conversation writes one JSONL log (relay.logging) with the model events, lane
frames (never audio bytes), matcher decisions (`sleepword`, whichever matcher made them)
and a per-turn latency record.
"""
import asyncio
import collections
import logging
import math
import re
from array import array
from dataclasses import dataclass, field

from relay.lane import CLOSE_REASONS
from relay.logging import new_conversation_id
from relay.model_client import (
    DEFAULT_PATH,
    DEFAULT_PORT,
    FRAME_SECONDS,
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
TOO_LONG = "too-long"
LOOPING = "looping"
# Ending a reply for one of these discards what the robot has already buffered
# (audio.flush) instead of letting the lane's pacer play it out.
FLUSH_REASONS = frozenset({"flush", TOO_LONG, LOOPING})
CUT_SNIPPET_CHARS = 240  # how much of the offending text is logged
_NOT_WORD = re.compile(r"[^a-z0-9]+")


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


class LoopGuard:
    """Trip when the reply in flight starts repeating itself.

    THE EVIDENCE (one live conversation, 42 replies): asked a simple question about zebras,
    the quantized model answered "They are used for decoration... used in fashion... used in
    art... used in music... used in sports... used in religion... used in education..." and
    then looped back to fashion/art/music/education and kept going. The machine was not the
    constraint -- rtf 0.64-0.70, input backlog 1-2 frames -- the model simply would not stop.

    THE RULE, chosen from exactly that: normalize (lowercase, every run of non-alphanumeric
    characters becomes one space), then count each sliding WINDOW of `window` consecutive
    words as it arrives. Trip on the `repeats`-th occurrence of any one window, and report
    it. Defaults: a four-word window, three occurrences.

    Why a four-word window and not sentences: every sentence in that answer differs from the
    last by ONE word, so counting whole sentences catches nothing until the model loops back
    verbatim, which took it another six sentences. The window "they are used in" recurs in
    every one of them and trips on the third -- four sentences in. It is also why the window
    is not longer: at five words, "they are used in fashion" and "they are used in art" are
    different units and the first pass is invisible (the test asserts this).

    Why not shorter, and why three: ordinary English repeats three-word runs innocently
    ("one of the", "a lot of", "the rest of"), and any phrase said once or twice is normal
    speech -- "you are right" is an answer, "you are right" three times is a stuck model. A
    window is counted at every position, so a one-word stutter ("no no no no no no") also
    trips, which is the other shape this failure takes.

    Text arrives as deltas that cut words in half ("they are us" / "ed in fash" / "ion"), so
    the trailing partial word is held back until a separator arrives for it.

    `repeats=0` disables the guard entirely."""

    def __init__(self, repeats=3, window=4):
        self.repeats = repeats
        self.window = max(1, window)
        self.reset()

    @property
    def enabled(self):
        return self.repeats > 0

    def reset(self):
        self._buf = ""
        self._recent = collections.deque(maxlen=self.window)
        self._counts = {}
        self._tripped = False

    def feed(self, text):
        """Add the next assistant-text delta; returns the repeated phrase, or None."""
        if not self.enabled or self._tripped or not text:
            return None
        self._buf += text
        norm = _NOT_WORD.sub(" ", self._buf.lower())
        words = norm.split()
        # A trailing character that is not a separator means the last word may still be
        # half-written, so carry it into the next delta rather than counting a fragment.
        self._buf = words.pop() if (words and not norm.endswith(" ")) else ""
        for word in words:
            self._recent.append(word)
            if len(self._recent) < self.window:
                continue
            phrase = " ".join(self._recent)
            seen = self._counts[phrase] = self._counts.get(phrase, 0) + 1
            if seen >= self.repeats:
                self._tripped = True
                return phrase
        return None


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
    # Backstop on the farewell, not a normal path: the drain closes on the robot's
    # playback{idle} as soon as the goodbye has been spoken. A server slower than realtime
    # takes longer than 8 s to speak one, and cutting it off there is exactly what the
    # drain exists to prevent, so the cap sits well above a slow farewell.
    drain_cap: float = 12.0
    farewell_start_timeout: float = 1.5  # match to agent_start, else close at once
    # A reply ends this long (milliseconds) after its last non-silent audio chunk. It has
    # to outlast the pauses the model leaves between sentences and fall well inside the
    # drain cap; `agent_end` would end a reply sooner, but the real server never sends one.
    # Measured on a server slower than realtime: the reply arrives in bursts whose gaps run
    # past 800 ms, and at 600 ms each gap ended the reply -- one sentence became a dozen
    # 80 ms replies, which is the stuttering the person heard. Continued `assistant_text_delta`
    # and a non-zero `stats.speech_queue` hold a reply open past this as well.
    reply_silence_ms: float = 1500.0
    # A reply opened by `agent_start` waits this long (milliseconds) for its first sound
    # before it ends as an empty one (`why: "no-speech"`). The child's TTS lags its own
    # agent_start -- about 240 ms in one probe, over 640 ms in a run that lost a farewell --
    # so the end-of-speech hangover above must not start until the reply has any speech.
    reply_start_grace_ms: float = 3000.0
    # The newest `stats` sample counts as current for this long. The server sends stats
    # only every few seconds, so a reply held open by a non-zero `speech_queue` must not be
    # held open for good by a sample that stopped being refreshed (a stalled server, or one
    # that went quiet); past this the hangover decides on the audio alone.
    stats_max_age: float = 10.0
    # SAFETY NET on the failure round 9 fixed (see ModelClient's frame clock). The model
    # reports how far behind its input is in `stats.input_backlog_frames`; past this many
    # frames -- 25 is 2 s, well beyond the 1-2 frames a healthy session sits at -- the relay
    # logs one loud warning naming its own measured send rate, so a relay feeding the model
    # faster than realtime can never again be invisible in the field. Logging only: the
    # frame clock is the fix, and dropping audio on an alarm would hide it again. 0 disables.
    backlog_alarm_frames: float = 25.0
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
    # HARD LENGTH CAP on one reply, in seconds of the audio actually forwarded to the
    # robot -- not wall clock, so a server slower than realtime is not cut off early for
    # being slow. 0 disables it. The quantized model does not stop talking on its own (see
    # LoopGuard), and 30 s is already far past anything this persona is asked for.
    reply_max_seconds: float = 30.0
    # Watch the reply's own text and cut it when the model starts repeating itself.
    loop_guard: bool = True
    loop_guard_repeats: int = 3  # occurrences of one window that trip it
    loop_guard_window: int = 4  # words per counted window; see LoopGuard for why four
    # Either guard MUTES the rest of the model's turn: it keeps generating for a while
    # after the cut, and that audio must not open a fresh reply. The mute ends at the
    # turn's real end (agent_end, a flush, the next agent_start, or the person speaking
    # again); this is only the backstop if none of those ever arrives.
    cut_mute_cap: float = 20.0
    cooldown: float = 0.3  # strict gate stays shut this long after the robot goes idle
    strict_idle_grace: float = 2.0  # strict gate opens anyway if no idle comes after a reply
    # Keep one model session open, already reset and carrying the persona, so that a wake
    # does not pay the persona read (measured live: 1.5-2.8 s idle, 10.9 s on a busy
    # server, against the robot's 15 s ready timeout). The model server takes ONE client,
    # so with this on the relay holds it for as long as a robot is linked and the owner's
    # browser page cannot use the model at the same time. Off, the relay connects per
    # conversation and the persona read is back on the critical path.
    prewarm: bool = True
    probe_interval: float = 5.0  # also the re-warm retry interval when a warm session fails
    probe_timeout: float = 2.0
    sleep_settle: float = 0.5  # running transcript age before the matcher decides on it
    # The farewell phrase the persona is told to end its goodbye with, and the only way a
    # conversation ends by itself today. Must match the persona file; "" disables it.
    farewell_phrase: str = DEFAULT_FAREWELL_PHRASE
    fatal_warning_codes: frozenset = field(default=FATAL_WARNING_CODES)


class _Warm:
    """A model session that has already sent `reset` and the persona and is waiting for a
    conversation to take it over, so that conv.open only has to start streaming.

    Handed out exactly once (ConversationEngine._take_warm) and never handed out again: a
    session that has carried a conversation has heard that conversation, and R9 says every
    conversation starts fresh with nothing remembered. A new one is opened after each
    conversation closes."""

    def __init__(self, client, opened_at, system_ms):
        self.client = client
        self.opened_at = opened_at
        self.system_ms = system_ms  # what reading the persona in cost, off the wake path
        self.watcher = None  # drains its events and notices it dying

    def age_ms(self, now):
        return round((now - self.opened_at) * 1000, 1)


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
        # The model opened a reply (agent_start) and the start grace expired before its TTS
        # produced a sound: it still owes this conversation that speech. Cleared by the
        # first loud chunk of any reply. The drain reads it, because a farewell matched
        # while this is set is a farewell that has been written but not yet spoken.
        self.speech_pending = False
        self.turn = None  # the latency record of the current reply
        self.turns = 0
        self.warm = False  # took over a warm session instead of opening its own
        self.warm_age_ms = None  # how long that session had been waiting
        self.warm_system_ms = None  # what it had already paid to read the persona in
        self.system_ms = 0.0  # what THIS conversation paid for the persona, on its own path
        self.last_stats = None
        self.last_stats_at = None  # loop time of that sample, so a stale one cannot hold a reply
        # The backlog alarm has fired for the excursion the model is in now; it re-arms when
        # the backlog comes back under the threshold, so one bad stretch logs one warning.
        self.backlog_alarm = False
        # `zeroed`: chunks the strict/draining gate replaced wholesale. `gated`: chunks the
        # uplink noise gate muted part or all of (backlog ones included, so it can exceed
        # `frames`); `gated_frames`: the 20 ms sub-frames inside them.
        self.uplink = {"frames": 0, "bytes": 0, "zeroed": 0, "gated": 0, "gated_frames": 0,
                       "backlog": 0, "backlog_dropped": 0}
        self.uplink_gate = UplinkGate(engine.config.uplink_gate_rms,
                                      engine.config.uplink_gate_hang_ms)
        # Reply-channel audio the model streamed outside any reply, dropped by the gate.
        self.out_of_reply = {"chunks": 0, "bytes": 0}
        # The reply guards (see LoopGuard and Config.reply_max_seconds). `muted` is the
        # rest of a cut turn: every reply-channel chunk is dropped and no reply may open
        # until the turn really ends.
        self.loop_guard = LoopGuard(
            engine.config.loop_guard_repeats if engine.config.loop_guard else 0,
            engine.config.loop_guard_window)
        self.reply_text = ""  # assistant text of the reply in flight, for the cut snippet
        self.muted = False
        self.muted_drops = {"chunks": 0, "bytes": 0}  # this mute's, reset when one starts
        self.cut = {"too_long": 0, "looping": 0, "mutes": 0, "muted_chunks": 0,
                    "muted_bytes": 0}
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
        self.warm = None  # the _Warm session waiting for a conversation, if any
        self._warm_task = None  # the one opening it
        self._warm_retry = None  # the backoff timer after a failed or lost warm session
        self._tasks = set()

    async def start(self):
        self.model_ok_last = await self._probe_once()
        log.info("model server %s:%d %s", self.config.model_host, self.config.model_port,
                 "reachable" if self.model_ok_last else "NOT reachable")

    async def stop(self):
        self._drop_warm("relay stopping")
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
        if self.config.prewarm:
            # The warm session is the health signal too: opening one is a stronger probe
            # than `status`, and a probe beside it would evict it (one client only).
            self._want_warm()
        else:
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
        warm = self._take_warm() if cfg.prewarm else None
        if warm is not None:
            # Nothing to wait for: the session is reset, carries the persona and has been
            # acknowledged. conv.ready goes out in this same turn of the event loop.
            self._adopt(c, warm)
        else:
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
        cfg = self.config
        if cfg.prewarm and self._warm_task is not None and not self._warm_task.done():
            # A warm session is already being opened. Wait for that one rather than
            # connect beside it: the model server takes a single client, and this
            # conversation would have to pay the same persona read anyway. The `ready`
            # timer bounds the wait.
            await asyncio.wait({self._warm_task})
            if c.state != CONNECTING:
                return
        waits = [t for t in (robot.probe_task, robot.model_closing)
                 if t is not None and not t.done()]
        if waits:
            await asyncio.wait(waits)
        if c.state != CONNECTING:
            return
        warm = self._take_warm() if cfg.prewarm else None
        if warm is not None:
            self._adopt(c, warm)
            return
        why = "prewarm is off" if not cfg.prewarm else "no warm session was ready"
        c.log("warm.miss", why=why)
        log.info("%s conv %s is opening its own model session (%s): the persona read is on "
                 "the critical path of this wake", c.link.robot_id, c.conv, why)
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
        c.system_ms = c.client.system_ms  # what reading the persona in cost this wake
        c.backlog = None  # open() already sent it, as it iterated it
        self._ready(c)

    def _adopt(self, c, warm):
        """Take over a warm session: this conversation pays no persona read at all. The
        session has been silent until now (see _open_warm); its uplink, and with it the
        zero-frame watchdog the model's VAD needs, starts here."""
        now = asyncio.get_running_loop().time()
        c.client = warm.client
        c.client.start_uplink()
        c.warm = True
        c.warm_age_ms = warm.age_ms(now)
        c.warm_system_ms = warm.system_ms
        c.log("warm.adopt", age_ms=c.warm_age_ms, system_ms=warm.system_ms)
        log.info("%s conv %s adopted the warm model session (%s ms old, its persona read "
                 "cost %s ms, off this wake's critical path)", c.link.robot_id, c.conv,
                 c.warm_age_ms, warm.system_ms)
        self._ready(c)

    def _ready(self, c):
        """The model session is usable: hand over anything held and tell the robot. No
        await between the backlog and CONVERSING, so uplink cannot overtake it."""
        if c.state != CONNECTING:
            return
        for chunk in c.backlog or ():  # only on the adopted path; open() sends its own
            c.client.send_audio(chunk)
        c.backlog = None
        c.state = CONVERSING
        c.cancel_timer("ready")
        c.link.send_conv_ready(c.conv)
        c.log("relay", msg={"type": "conv.ready"}, backlog_chunks=c.uplink["backlog"],
              system_ms=c.system_ms, warm=c.warm, warm_age_ms=c.warm_age_ms,
              warm_system_ms=c.warm_system_ms)
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
              system_ms=c.client.system_ms if c.client else None, warm=c.warm,
              turns=c.turns, uplink=self._uplink_record(c), out_of_reply=c.out_of_reply,
              cut=c.cut,
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

    @staticmethod
    def _uplink_record(c):
        """The conversation's uplink counters, plus what the model was actually handed:
        frames sent, frames zero-filled for an empty slot, chunks queued behind the frame
        clock and chunks dropped at its bound, and the measured send rate in audio seconds
        per wall second. `rate` is the number the live failure needed and did not have."""
        record = dict(c.uplink)
        record["model"] = c.client.uplink_stats() if c.client is not None else {}
        return record

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
            # The session just closed has heard a conversation, so it can never be warm
            # again (R9). Open a new one for the next wake.
            self._want_warm()

    def _forget(self, robot):
        self.robots.pop(robot.link, None)
        for task in (robot.prober, robot.probe_task):
            if task is not None:
                task.cancel()
        if not self.robots:
            # Nothing to be ready for: give the model server's single client slot back, so
            # the owner's browser page works again while no robot is linked.
            self._drop_warm("no robot is linked")

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
            self._unmute(c, "user_start")  # the person has the floor: the turn is over
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
            self._unmute(c, "flush")
            if c.reply_id is not None:
                self._end_reply(c, now, "flush")
            else:
                c.dropping = True
        elif isinstance(ev, Stats):
            c.last_stats = ev.data
            c.last_stats_at = now
            self._check_backlog(c, ev.data)
        elif isinstance(ev, ModelError):
            self._end(c, "model_error", detail=f"model error {ev.code!r}: {ev.message}")
        elif isinstance(ev, ModelWarning):
            if ev.data.get("fatal") is True or ev.code in self.config.fatal_warning_codes:
                self._end(c, "model_error", detail=f"fatal warning {ev.code!r}: {ev.message}")
            else:
                log.warning("%s conv %s: model warning %r: %s", c.link.robot_id, c.conv,
                            ev.code, ev.message)
        elif isinstance(ev, AssistantTextDelta):
            if c.muted:
                # The tail of a cut turn. It is not going to be spoken, so it is not
                # matched against the farewell either: the goodbye has to be heard (R2).
                return
            # Text still arriving for this reply says the model is still producing the turn,
            # so it holds the reply open exactly as a loud chunk does (see _keep_speaking).
            self._keep_speaking(c)
            # The model's own text is the only text on the channel, and its farewell phrase
            # is how a conversation ends here. The person's words are never matched against
            # it: there is no transcript of them at all (KTD2 stays dormant).
            if c.state == CONVERSING:
                self._decide(c, c.farewell.feed(ev.text))
            if c.reply_id is not None:
                # The repetition guard reads the reply in flight. Only a reply that is
                # actually open can be cut, so text between replies is not accumulated.
                c.reply_text += ev.text
                phrase = c.loop_guard.feed(ev.text)
                if phrase is not None:
                    self._cut_reply(c, now, LOOPING, phrase)

    def _on_agent_start(self, c, now):
        """agent_start does arrive (measured); it is only agent_end that never does. If the
        audio beat it to the reply by a chunk or two, that reply is this one."""
        self._unmute(c, "agent_start")  # a new reply is a new turn
        if c.reply_id is not None:
            if c.turn is not None and c.turn["begun_by"] == "audio":
                c.turn["begun_by"] = "audio+agent_start"
                c.agent_speaking = True
                return
            self._end_reply(c, now, "agent_start")  # back-to-back replies, no agent_end
        self._begin_reply(c, now, "agent_start")

    def _on_agent_end(self, c, now):
        self._unmute(c, "agent_end")
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
        c.loop_guard.reset()  # each reply is judged on its own text
        c.reply_text = ""
        c.turns += 1
        c.turn = {"n": c.turns, "reply": c.reply_id, "begun_by": why, "end_reason": None,
                  "reply_ms": None, "user_end_to_first_chunk_ms": None,
                  "robot_first_chunk_to_play_ms": None, "reply_chunks": 0, "reply_bytes": 0,
                  "loud_chunks": 0, "silent_chunks": 0, "dropped_chunks": 0, "flushed": False,
                  "text_deltas": 0, "queue_holds": 0,
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
        if why == "no-speech":
            # agent_start with nothing behind it yet: the child's TTS is still lagging, so
            # the speech is owed, not lost. Audio that arrives later opens its own reply.
            c.speech_pending = True
        if why in FLUSH_REASONS:
            # Stop playing this reply AT ONCE: the robot drops what it has buffered and
            # goes quiet, rather than talking on for as long as the pacer's queue lasts.
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

    def _cut_reply(self, c, now, why, detail):
        """A guard tripped on the reply in flight (see Config.reply_max_seconds and
        LoopGuard). Stop forwarding it, flush what the robot has buffered so it goes quiet
        and the person can speak again, end the reply with its own `why`, and mute the rest
        of the model's turn. The conversation itself stays open."""
        reply_id = c.reply_id
        if reply_id is None:
            return
        snippet = c.reply_text[-CUT_SNIPPET_CHARS:]
        audio_ms = round(c.turn["reply_bytes"] / 2 / OUTPUT_RATE * 1000, 1) if c.turn else None
        c.cut["too_long" if why == TOO_LONG else "looping"] += 1
        c.log("reply.cut", reply=reply_id, why=why, detail=detail, audio_ms=audio_ms,
              phrase=detail if why == LOOPING else None,
              text=("..." + snippet) if len(c.reply_text) > len(snippet) else snippet)
        log.info("%s conv %s: CUTTING reply %s (%s: %s) after %s ms of audio -- the rest of "
                 "this turn is muted. Text: %r", c.link.robot_id, c.conv, reply_id, why,
                 detail, audio_ms, snippet)
        self._end_reply(c, now, why)  # flushes, because why is in FLUSH_REASONS
        self._mute_turn(c)

    def _mute_turn(self, c):
        """Drop the rest of the model's turn. The model keeps generating for a while after
        a cut -- that is the whole reason it had to be cut -- and every chunk of it would
        otherwise open a fresh reply and start the robot talking again. `dropping` is not
        enough: it clears on the first silent chunk, and the server streams silence between
        its own bursts. The mute ends only when the turn really does (see _unmute)."""
        c.muted = True
        c.muted_drops = {"chunks": 0, "bytes": 0}
        c.cut["mutes"] += 1
        c.set_timer("mute_cap", self.config.cut_mute_cap, self._mute_cap_expired)

    def _unmute(self, c, why):
        """The model's turn ended: back to normal. Counts what the mute dropped."""
        if not c.muted:
            return
        c.muted = False
        c.dropping = False  # the stale tail is already gone; do not drop the next reply
        c.cancel_timer("mute_cap")
        c.log("reply.unmute", why=why, **c.muted_drops)
        log.info("%s conv %s: muted turn ended (%s); %d chunks (%d bytes) dropped",
                 c.link.robot_id, c.conv, why, c.muted_drops["chunks"],
                 c.muted_drops["bytes"])

    def _mute_cap_expired(self, c):
        if c.state != CLOSED:
            self._unmute(c, "timeout")

    def _over_length(self, c):
        """True once this reply has forwarded more than reply_max_seconds of audio. Measured
        on the audio itself, so a server slower than realtime is never cut short for being
        slow -- only a reply that is genuinely too long to sit through is."""
        cap = self.config.reply_max_seconds
        if cap <= 0 or c.turn is None:
            return False
        return c.turn["reply_bytes"] / 2 / OUTPUT_RATE > cap

    def _arm_hangover(self, c):
        """(Re-)start the end-of-speech hangover. Anything that says the model is still
        producing this reply comes through here: its loud audio, its text, its speech
        queue."""
        c.set_timer("reply_silence", self.config.reply_silence_ms / 1000,
                    self._reply_silence_expired)

    def _keep_speaking(self, c):
        """`assistant_text_delta` for the reply in flight: the model is still producing this
        turn, so the gap in its audio is the server being slow, not the reply ending.
        Measured live, that is exactly what a slower-than-realtime server does -- speech,
        800-900 ms of silence while it computes, more speech -- and ending the reply at each
        gap restarted the robot's player a dozen times inside one sentence.

        Only once the reply has spoken, because until then the START GRACE governs and the
        text means the opposite: it runs seconds ahead of the TTS, so text before any sound
        is the lag the grace exists to bound, not evidence of speech."""
        if c.reply_id is None or c.turn is None or c.turn["_first_loud"] is None:
            return
        c.turn["text_deltas"] += 1
        self._arm_hangover(c)

    def _check_backlog(self, c, data):
        """Watch how far behind the model's input is (`stats.input_backlog_frames`) and log
        ONE loud warning per excursion past Config.backlog_alarm_frames.

        Round 9, measured live: the backlog climbed 3 -> 7 -> 14 -> 20 -> 22 -> 28 while the
        server itself was faster than realtime (rtf 0.70-0.76), and nothing said so -- the
        only sign was the conversation degenerating. A server with headroom can only fall
        behind if it is being fed faster than realtime, so the warning names the relay's own
        measured send rate: >= 1.0 and the relay is the cause, well under 1.0 and the server
        is. No corrective action: the frame clock in ModelClient is the fix, and dropping
        audio here would only hide a regression in it."""
        limit = self.config.backlog_alarm_frames
        if not limit or not isinstance(data, dict):
            return
        try:
            frames = float(data.get("input_backlog_frames") or 0)
        except (TypeError, ValueError):
            return
        if frames <= limit:
            c.backlog_alarm = False  # recovered: the next excursion gets its own warning
            return
        if c.backlog_alarm:
            return
        c.backlog_alarm = True
        stats = c.client.uplink_stats() if c.client is not None else {}
        c.log("uplink.backlog", frames=frames, limit=limit, rtf=data.get("rtf"), uplink=stats)
        log.warning(
            "%s conv %s: the model is %g frames (%.1f s) behind on its input, past the %g "
            "frame alarm -- this relay has sent it %s s of audio in %s frames at %s x "
            "realtime (server rtf %s). At or above 1.0 the relay is overfeeding it; well "
            "below 1.0 the server itself cannot keep up.",
            c.link.robot_id, c.conv, frames, frames * FRAME_SECONDS, limit,
            stats.get("seconds"), stats.get("frames"), stats.get("rate"), data.get("rtf"))

    def _speech_queued(self, c, now):
        """Whether the model's newest `stats` says it still has TTS queued for playback
        (`speech_queue`, in frames). A sample is optional and arrives only every few
        seconds, so a missing -- or stale, see Config.stats_max_age -- one blocks nothing:
        this answers False and the audio alone decides."""
        data = c.last_stats
        if not isinstance(data, dict) or c.last_stats_at is None:
            return False
        if now - c.last_stats_at > self.config.stats_max_age:
            return False
        try:
            return float(data.get("speech_queue") or 0) > 0
        except (TypeError, ValueError):
            return False

    def _reply_silence_expired(self, c):
        """No non-silent audio for reply_silence_ms (the hangover): the reply is over --
        unless the model still has speech queued for playback, which says more of this
        reply is on its way and only the server's pace is in the way of hearing it."""
        if c.state == CLOSED or c.reply_id is None:
            return
        now = asyncio.get_running_loop().time()
        if self._speech_queued(c, now):
            if c.turn is not None:
                c.turn["queue_holds"] += 1
            self._arm_hangover(c)
            return
        self._end_reply(c, now, "silence")

    def _reply_start_grace_expired(self, c):
        """An agent_start the model has still not spoken after reply_start_grace_ms: end the
        reply empty rather than hold the conversation open. Nothing was forwarded, so the
        person heard nothing; audio that arrives later still opens a fresh reply."""
        if c.state != CLOSED and c.reply_id is not None:
            self._end_reply(c, asyncio.get_running_loop().time(), "no-speech")

    def _drop_silence(self, c, pcm):
        """Count one silent chunk the robot is not given. Only silence ever comes here:
        `out_of_reply` is a silence counter, and a non-zero one is not a lost reply."""
        c.out_of_reply["chunks"] += 1
        c.out_of_reply["bytes"] += len(pcm)

    def _on_reply_audio(self, c, pcm, now):
        """Forward the model's reply-channel audio to the robot, and derive each reply's
        own start and end from that audio.

        ONE RULE, because breaking it once cost a person a whole spoken goodbye: a LOUD
        chunk is always forwarded, whatever the state, and opens a reply if none is open --
        DRAINING included, since a farewell whose TTS starts late must still be heard. Only
        SILENCE is ever dropped, and only outside a reply that has already spoken.

        The model streams the reply channel continuously, whether or not it is speaking
        (docs/model-server-protocol.md: 228 frames over one 20 s run, no gap above 0.4 s),
        and what it streams outside a reply is exact digital silence. Forwarding that too
        would keep the robot's speaker permanently busy, so its playback{idle} would never
        arrive, the farewell drain would always run to its cap and the strict gate would
        only ever reopen on its backstop. agent_start still says "I am speaking", but the
        matching agent_end is measured never to come, so sound starting and sound stopping
        are what open and close a reply.

        agent_start runs ahead of the sound: the child's TTS lags it, by over 3 s in the
        run that lost a reply. While a reply agent_start opened is still waiting for its
        first loud chunk, the silence it waits through is dropped like any other, and
        reply_start_grace rather than the hangover bounds the wait -- but the grace giving
        up never puts later audio at risk: that audio is loud, so it opens its own reply."""
        if c.muted:
            # A guard cut this turn's reply; everything the model produces until the turn
            # ends goes nowhere. Counted, so the field data shows how much that is.
            c.muted_drops["chunks"] += 1
            c.muted_drops["bytes"] += len(pcm)
            c.cut["muted_chunks"] += 1
            c.cut["muted_bytes"] += len(pcm)
            return
        silent = is_silent(pcm, self.config.reply_silence_rms)
        if c.reply_id is None:
            if c.dropping:
                # The one place loud audio is deliberately not forwarded, and it is not a
                # drop of anything the person should hear: the conversation is interruptible
                # and the person barged in, so the tail the model had already produced is
                # stale and must not play over them. Counted apart, as `dropped_chunks`, and
                # only until the model's stream goes quiet (or agent_start opens the next
                # reply). Nothing here is counted out of reply.
                if not silent:
                    if c.turn is not None:
                        c.turn["dropped_chunks"] += 1
                    return
                c.dropping = False
            if silent:
                self._drop_silence(c, pcm)
                return
            self._begin_reply(c, now, "audio")
        turn = c.turn
        if silent and turn is not None and turn["_first_loud"] is None:
            # The reply is open (agent_start) but has not started speaking yet: this is the
            # TTS lag. Drop it like any other out-of-reply silence, so the robot is not given
            # a lag to play before the reply's first word and an empty reply forwards nothing.
            self._drop_silence(c, pcm)
            return
        if not c.link.send_reply_audio(c.reply_id, pcm):
            return
        if turn["_first_chunk"] is None:
            turn["_first_chunk"] = now
        turn["reply_chunks"] += 1
        turn["reply_bytes"] += len(pcm)
        if silent:
            turn["silent_chunks"] += 1
            # the hangover armed by the last non-silent chunk keeps running
        else:
            turn["loud_chunks"] += 1
            if turn["_first_loud"] is None:
                turn["_first_loud"] = now
                c.speech_pending = False  # the model caught up with itself
                c.cancel_timer("reply_start_grace")  # the hangover governs from here
                if turn["_user_end"] is not None:
                    turn["user_end_to_first_chunk_ms"] = round((now - turn["_user_end"]) * 1000,
                                                               1)
            self._arm_hangover(c)
        if self._over_length(c):
            self._cut_reply(c, now, TOO_LONG,
                            f"{self.config.reply_max_seconds} s of reply audio")

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
        # the reply in flight IS the farewell and no further agent_start is coming. It is
        # also the farewell when the model has opened a reply and not yet spoken in it
        # (`speech_pending`): the text runs seconds ahead of the TTS, and closing on the
        # farewell-start timer before a sound arrives is exactly how a person is left
        # hearing nothing of the goodbye (R2). The drain cap is the bound in that case.
        c.farewell_started = c.agent_speaking or c.speech_pending
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

    # --- the warm session ---

    def _want_warm(self):
        """Open a warm session if prewarm is on, a robot is linked and listening, and none
        is open or opening. Cheap and idempotent: call it wherever that might have become
        true."""
        if not self.config.prewarm or self.warm is not None or self._warm_task is not None:
            return
        if not self.robots or any(r.conversation is not None for r in self.robots.values()):
            return  # a conversation owns the model server's one client slot
        if all(r.link.closed for r in self.robots.values()):
            return
        self._warm_task = self._spawn(self._open_warm(), "model-warm")

    async def _open_warm(self):
        """Connect, `reset`, send the persona and wait for its ack -- all of it before any
        wake, so that conv.open only has to start streaming. Failing is also the health
        answer: it is a stronger check than `status`, and running both would mean two
        connections to a server that takes one."""
        me = asyncio.current_task()
        cfg = self.config
        closing = [r.model_closing for r in self.robots.values()
                   if r.model_closing is not None and not r.model_closing.done()]
        if closing:
            await asyncio.wait(closing)  # never connect on top of a closing conversation
        client = ModelClient(cfg.model_host, cfg.model_port, persona=cfg.persona,
                             path=cfg.model_path, open_timeout=cfg.probe_timeout)
        try:
            # Quiet: a warm session must not stream anything at the model while it waits.
            # ModelClient's watchdog would fill the wait with 80 ms zero frames, and this
            # server runs slower than realtime (measured rtf 1.8-3.3), so they queue up as
            # input backlog -- live, a conversation started with the model ~18 s behind and
            # climbing, and hours of it degenerated the model into a repetition loop. The
            # uplink (watchdog included) starts at adoption, where the VAD needs it.
            await client.open(quiet=True)
        except asyncio.CancelledError:
            self._spawn(self._close_client(client), "model-warm-drop")
            raise
        except Exception as exc:  # noqa: BLE001 - OSError, TimeoutError, InvalidHandshake...
            if self._warm_task is me:
                self._warm_task = None
            log.info("no warm model session: %r", exc)
            self._spawn(self._close_client(client), "model-warm-drop")
            self._report_all(False)
            self._retry_warm()
            return
        now = asyncio.get_running_loop().time()
        if self._warm_task is me:
            self._warm_task = None
        if not self.robots or not self.config.prewarm:  # nobody is waiting for it any more
            self._spawn(self._close_client(client), "model-warm-drop")
            return
        warm = _Warm(client, now, client.system_ms)
        self.warm = warm  # no await since the check above: nothing can have taken it
        warm.watcher = self._spawn(self._watch_warm(warm), "model-warm-watch")
        log.info("warm model session ready (persona read in %s ms, off the wake path); the "
                 "relay now holds the model server's single client slot",
                 client.system_ms)
        self._report_all(True)

    async def _watch_warm(self, warm):
        """Hold the session open and discard everything the model says on it: no
        conversation has started, so none of it belongs to one. Ends when the connection
        does -- which, while a warm session is held, is the model-not-reachable signal."""
        async for _event in warm.client.events():
            pass
        if self.warm is not warm:
            return  # already adopted or released; whoever took it owns the close
        self.warm = None
        log.warning("warm model session lost: the model server evicted it (its browser page "
                    "opened?) or went away; model not reachable until it is back")
        self._report_all(False)
        self._retry_warm()

    def _take_warm(self):
        """The warm session, handed out once and then gone. A session that has carried a
        conversation is never warm again (R9), so there is no way back into this."""
        warm, self.warm = self.warm, None
        if warm is None:
            return None
        if warm.watcher is not None:
            warm.watcher.cancel()  # its events belong to the conversation from here
        if warm.client.closed:  # died between the watcher noticing and now
            self._spawn(self._close_client(warm.client), "model-warm-drop")
            return None
        return warm

    def _drop_warm(self, why):
        """Release the warm session and stop trying to keep one."""
        if self._warm_task is not None:
            self._warm_task.cancel()
            self._warm_task = None
        if self._warm_retry is not None:
            self._warm_retry.cancel()
            self._warm_retry = None
        warm = self._take_warm()
        if warm is not None:
            log.info("warm model session released (%s)", why)
            self._spawn(self._close_client(warm.client), "model-warm-drop")

    def _retry_warm(self):
        """Try again after one probe interval. One timer at a time, so a model server that
        is down is retried at the prober's own pace and never hammered."""
        if self._warm_retry is not None or not self.config.prewarm:
            return
        loop = asyncio.get_running_loop()
        self._warm_retry = loop.call_later(self.config.probe_interval, self._guard,
                                           self._warm_retry_due)

    def _warm_retry_due(self):
        self._warm_retry = None
        self._want_warm()

    async def _close_client(self, client):
        try:
            await client.close()
        except Exception:  # noqa: BLE001 - closing a broken socket must not raise
            log.exception("closing a model connection failed")

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

    def _report_all(self, ok):
        """Push model_ok to every linked robot, by the same rules a probe result follows:
        on a change, or when a failed conversation asked for a fresh status either way."""
        self.model_ok_last = ok
        for robot in list(self.robots.values()):
            if robot.link.closed:
                continue
            force, robot.force_probe = robot.force_probe, False
            self._report_model_ok(robot, ok, force)

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
