# Model server protocol

The owner's model server is `webchat/server.py` (aiohttp) on a Linux host on the LAN, outside
this repo. It fronts a quantized VoiceChat 11B build run as a `llama-voicechat --live` child
process over stdin/stdout. This page is the relay's source of truth for that server's wire
protocol. The relay's adapter is `relay/model_client.py`; the scripted test double is
`tests/fake_model_server.py`.

**Verified** two ways. By reading the server's own sources, not from a description:

- `webchat/server.py` — the routes, the commands it accepts, what it forwards, its own
  `status` and `error` frames;
- `webchat/index.html` — its `onEvent()` switch, which is the complete list of event kinds
  the browser page handles, and the 80 ms-a-word note under the system-prompt box;
- `llama-voicechat.cpp/tools/voicechat/voicechat-cli.cpp` — every event the child emits,
  including `vad()`, which is where `user_start` and `user_end` come from.

And by **probing the running server**: `/tmp/miko-voice-test/probe.py` speaks a WAV at
`ws://127.0.0.1:8766/ws`, then prints every event kind, the reply audio's frame times and
gaps, the system-prompt read time and any tool call. Anything below marked *measured* comes
from that probe against the real VoiceChat 11B build, not from the sources.

Two things to get right or nothing works: the WebSocket is at **`/ws`**, and the server's
events are keyed by **`kind`**. Only the client's commands use `type`.

## Transport

| Port | Scheme | Notes |
|---|---|---|
| 8765 | `wss://` | TLS with a self-signed certificate. What the browser page uses. |
| 8766 | `ws://` | The same app without TLS. **The relay uses this port.** |

- **Path `/ws`.** `app.router.add_get("/ws", ws_handler)`; `/` is the browser page and
  answers a WebSocket handshake with the HTML, so connecting there fails. The relay's
  default is `/ws` (`--model-path` overrides it).
- Binds all interfaces. No authentication.
- **Single client.** `ws_handler` closes the previous client on a new connection, after
  sending it `{"kind":"error","message":"Another tab took over the conversation."}`. A health
  probe, the browser page, or a second relay connection all evict whatever conversation is
  open. The relay treats any server-side close as the end of the conversation.

## Client to server

### Binary frames: microphone audio

Raw PCM, 16-bit signed little-endian, 16 kHz, mono, no header. The client sends continuously,
**including silence**: the child's VAD detects the end of the person's turn from the silence,
so the stream must never stall. The browser page sends 80 ms chunks (1,280 samples, 2,560
bytes). Any whole number of samples per frame works; the relay forwards robot chunks as they
arrive and fills gaps of 80 ms or more with zero-filled 80 ms frames. Audio sent while the
model is not `ready` is dropped by the server (`Model.audio`).

#### The server's turn detection is a bare energy VAD, and a noise floor latches it

The server does not run a neural VAD. Turn detection is `vad()` in
`tools/voicechat/voicechat-cli.cpp` (llama-voicechat.cpp): it calls a frame **voiced** when
its RMS exceeds `max(vad_min, 3 x rolling noise)`, where `vad_min` is about 0.004 of full
scale — **around RMS 131** on the 16-bit scale used here — and, decisively, it **updates its
rolling noise estimate only while it is not voiced**.

**Measured on the robot:** the Miko's microphone uplink has a noise floor of about **RMS
300-470** — per-second over a recorded uplink, quiet stretches ran 73-470, speech ran
2,745-6,541, and peaks clipped at 32,766. That floor sits permanently above `vad_min`, so the
VAD never gets an unvoiced frame, never re-estimates its noise, and stays voiced forever.

**Observed twice on the robot**, in strict turn-taking: `user_start` fired and `user_end`
**never did**. The model never took its turn and the robot sat in a conversation with no
reply. Nothing in the protocol reports this — the relay sees a `user_start` and then silence
from the server, indistinguishable from a person still talking.

The fix is on the client side, because the server offers no VAD knob over the wire: the relay
**gates its uplink**, replacing sub-threshold audio with true digital silence so the VAD gets
the unvoiced frames its noise estimate depends on. **Proof:** gating that same recording
offline (20 ms frames, zeroed below RMS 700) made `user_end` fire **2.8 s in** and the model
answer normally; ungated, the same audio produced far later and fewer `user_end` events.

The gate cannot drop frames, only zero them: the stream must never stall (above), and a
dropped frame would also shorten the silence the VAD is being given. See
`relay/relay/conversation.py` (`UplinkGate`) and `--uplink-gate-rms` / `--uplink-gate-hang-ms`
in `relay/README.md`, which also records how the default threshold was chosen.

### Text frames: JSON commands, keyed by `type`

| Frame | Meaning |
|---|---|
| `{"type": "reset"}` | Start a fresh session: forget the conversation so far. |
| `{"type": "system", "text": "<persona>"}` | Set the system prompt (persona). **ASCII only** — the server replies with an `error` frame otherwise, and an empty or whitespace-only text is ignored. |
| `{"type": "status"}` | Ask for a `status` reply. |

Anything else is ignored. The relay sends `reset`, then `system`, then audio, on every new
connection, so each conversation starts fresh (R9). It rejects a non-ASCII persona before
connecting.

### The persona is not free: about 80 ms a word

The child reads the system prompt in before the session is usable, and emits
`system_start` -> `progress`... -> `system` while it does. The browser page puts the cost at
"about 80 ms to read in" per word. **Measured** on this build it is nearer 50 ms: a 30-word
persona took **1,495 ms**, a 45-word one **2,250 ms**. The relay therefore **waits for the
`system` ack** before it reports the conversation ready, holding any audio handed to it
meanwhile and forwarding it in order once the ack arrives. A long persona delays every single
wake by that much, and the cost of each one is in its conversation log as `system_ms`.

## Server to client

### Binary frames: reply audio

Raw PCM, 16-bit signed little-endian, **22.05 kHz**, mono, no header. The child emits these as
`{"kind":"pcm","pcm":"<base64>"}` and the server decodes them into binary frames, so `pcm`
never reaches the client as a text frame.

#### The reply channel never stops, and its gaps are exact silence

**Measured:** the server sends reply audio **continuously, whether or not the model is
speaking.** One 20 s probe run produced **228 audio frames, from t=0.69 s to t=19.28 s, with
no gap over 0.4 s** — including long stretches where nobody was speaking and the model was
not replying. The stream is not framed by `agent_start` / `agent_end`; those events only say
when the model considers itself to be talking.

**Measured:** what it streams between replies is **exact digital silence, RMS 0.0**. In one
run the reply audio was loud (RMS > 200) from t=5.58 s to t=8.98 s and **every frame for the
next 17 s was RMS 0**. So the channel is one stream that carries speech or silence, never
both, and the silence is unambiguous — no noise floor to threshold against.

So a relay cannot treat "a binary frame arrived" as "the model is replying", and (see the
next section) it cannot wait for `agent_end` either. The relay **derives each reply's start
and end from the audio itself** (`relay/conversation.py`, `_on_reply_audio`), on **two
timers, never both at once**:

- a reply **begins** at the first non-silent chunk, or at `agent_start` if that lands first;
- a reply begun by `agent_start` then has `--reply-start-grace-ms` (default 3000 ms) to
  produce its first sound — the **start grace**. If it does, the hangover below takes over;
  if the grace expires first, the reply ends empty, logged as `why: "no-speech"`;
- from its first loud chunk it **ends** `--reply-silence-ms` (default 600 ms) after its last
  non-silent chunk — the **end-of-speech hangover** — or at `agent_end` or `flush` if one of
  those arrives first.

Silence *between* two loud chunks of a reply is forwarded like any other audio — the
hangover is exactly what stops a pause between two sentences from splitting one reply into
two. Silence *outside* a reply, and the TTS lag at the head of one (below), are dropped and
counted in the conversation's `close` record as `out_of_reply`.
Forwarding all of it keeps the robot's speaker permanently busy: its `playback{state:"idle"}`
never arrives, so the farewell drain always runs to its 8 s cap and the strict turn-taking
gate only ever reopens on its backstop. Each reply logs a `reply.end` record with its
duration and `why` (`silence`, `no-speech`, `agent_end`, `flush` or `agent_start`), so the
field data shows which signal is actually doing the work.

#### The TTS lags `agent_start`, so the hangover cannot start at the reply

**Measured:** reply audio does **not** start with `agent_start`. One probe put the first loud
chunk **~240 ms** after it; a live run
(`relay/out/conversations/20260918T195654082-030ac0.jsonl`) had **nothing but silence for
over 640 ms**, so a hangover armed at `agent_start` expired first and the reply ended
**empty, with zero loud chunks** — `reply.end {"why": "silence", "audio_ms": 640.0,
"chunks": 4}` against `turn {"loud_chunks": 0, "silent_chunks": 4}`. The farewell's real
speech (34 chunks, ~2.7 s) then arrived **outside** any reply, was dropped as `out_of_reply`,
and the drain — seeing a finished reply plus a robot `playback{idle}` — closed the
conversation. The person heard 0.6 s of silence instead of the goodbye.

The lag is **variable**, so the start grace is set well above the largest one seen. Two
consequences the relay depends on:

- the **hangover never runs before a reply has speech**; only the start grace does;
- an **empty reply is not a farewell.** The model's `assistant_text_delta` stream runs
  seconds ahead of its TTS, so the farewell phrase matches before a word of it is spoken. The
  drain waits for a reply that actually carried speech to finish and the robot to report
  `playback{idle}`, with `--drain-cap` as the backstop.

#### `agent_end` does not fire in normal use

**Measured:** over a 35 s window after a complete spoken reply, the model emitted exactly one
`agent_start` and **no `agent_end` at all**. In `llama-voicechat.cpp`
(`tools/voicechat/voicechat-cli.cpp`) `agent_end` is emitted only when the model emits EOS,
and the source's own comment says *"the model closes its turn when the user starts talking"*.
A reply that simply finishes therefore never produces one; the only reliable way to see an
`agent_end` is to interrupt (where it arrives with `interrupted: true`, after `flush`).

Everything that used to wait on `agent_end` now waits on the derived reply end instead: the
farewell drain, the strict turn-taking gate, the conversation silence timer, and the lane's
`end_reply()` that lets the paced queue finish so the robot can report playback idle. The
event is still honoured the moment it does arrive — it just cannot be depended on.

### Text frames: JSON events, keyed by `kind`

The server forwards the child's events verbatim and adds `status` and `error` of its own. The
child's `ready` is the one event the server swallows: it turns into `status` with
`state: "ready"`.

| `kind` | From | Meaning | Fields |
|---|---|---|---|
| `status` | server | Reply to `status`, and pushed on connect and on any state change. | `state` (`loading` \| `ready` \| `dead`), `system_set`, `t`, `frame_cap`, `host`, `device`, `voice` |
| `error` | server or child | Fatal, or a rejected persona, or a takeover. The relay closes the connection and ends the conversation with `model_error`. | `message` |
| `warning` | child | Non-fatal (`session frame cap reached`, and a few others). | `message`, sometimes `t` |
| `system_start` | child | It began reading the persona in. | `tokens`, `t` |
| `progress` | child | Still reading it in. | `t`, `phase` (`system`), `left` |
| `system` | child | **The persona is in; the session is usable.** | `t` |
| `reset` | child | The session was reset (by the client's `reset`, or on the session cap, which sets `reason`). | `t`, sometimes `reason` |
| `user_start` | child | The **energy VAD** says the person started speaking. | `t` |
| `user_end` | child | The VAD says they stopped. | `t` |
| `agent_start` | child | The model starts replying; reply audio follows. | `t`, `turn` |
| `assistant_text_delta` | child | A piece of the model's own reply text. | **`delta`** (not `text`) |
| `agent_end` | child | The reply is over. **Measured: never sent when a reply simply finishes** — only when the person takes the floor back, with `interrupted: true`. See above. | `t`, `interrupted` |
| `flush` | child | Drop any reply audio buffered and not yet played. | none |
| `stats` | child | Periodic health of the pipeline. | `t`, `frame_cap`, `rtf`, `enc_ms`, `llm_ms`, `input_backlog_frames`, `speech_queue` |
| `pong` | child | Answer to its own `ping` command. | `t`, `turns` |
| `turn_start`, `turn_end` | child | Batch-mode turn boundaries. | `turn`, `text`, ... |
| `function_delta`, `tool_call_start`, `tool_call`, `tool_response`, `tool_response_end` | child | Tool calling. In the child's source, but **measured: never emitted on this build** — see "Prompt-declared tool calls do not fire" below. | varies |
| `audio` | child | A reply written to a WAV file (batch mode). | `turn`, `path` |

The relay maps `status`, `system`, `user_start`, `user_end`, `agent_start`, `agent_end`,
`assistant_text_delta`, `flush`, `stats`, `warning` and `error` onto typed events; everything
else arrives as `OtherMessage` and is logged. Frames keyed by `type` still parse, so old
fixtures keep working, but nothing the real server sends uses that key.

## There is no user transcript in live mode

**The person's words are never transcribed.** `--live` has no ASR for the input: `user_start`
and `user_end` come from `vad()` in `voicechat-cli.cpp`, which is pure RMS energy with
hysteresis (a floor of 0.004, three times the rolling noise estimate to open, two voiced
frames to start, 800 ms of silence to close). The only text on the channel is the **model's
own** reply, as `assistant_text_delta`. There is no `user_text` or `user_text_delta` event,
and no plan in the server that would add one.

Consequences, on purpose and not a bug:

- **The sleep word has no input today.** The "Goodbye Miko" matcher
  (`sleepword.SleepWordMatcher`, KTD2) is fed from the person's transcript, so with this
  server it is never fed at all. It stays in the tree, with its tests, for a future
  recogniser. See below for what ends a conversation instead.
- `UserTextDelta` and `UserText` stay in `model_client.py`, dormant, and the fake server can
  still script them, so the matcher and its tests keep their coverage.
- Turn-taking still works: `user_start`/`user_end`/`agent_start`/`agent_end` are all the
  engine needs for the silence timer and the strict gate.

## How a conversation ends: the model says a farewell phrase

With no transcript of the person, the only text on the channel is the model's own, as
`assistant_text_delta`. So the model is made to signal the end itself.

### Prompt-declared tool calls do not fire on this build

**Tested, twice, and neither worked.** The child does emit tool events
(`function_delta`, `tool_call_start`, `tool_call`, `tool_response`) — they are in its source
— but nothing in the system prompt made it produce one:

| What was put in the system prompt | Result |
|---|---|
| A plain instruction: *"...give a short farewell and then call the tool end_conversation. Tools: end_conversation() ends the conversation."* | No tool event of any kind. The model **spoke the instruction aloud** instead. |
| A JSON declaration block ahead of the persona: `<TOOLS>[{"name":"end_conversation","description":"Call this right after saying goodbye...","parameters":{"type":"object","properties":{}}}]</TOOLS>` | Same: no `function_delta`, no `tool_call_start`, no `tool_call`. |

There is no `tools` command in `webchat/server.py` either, so there is no channel to declare
them on. Treat tool calling as unavailable until the server grows one.

### An exact farewell phrase does work

**Measured, reliably.** With the persona told:

> When the person says goodbye, reply with a short farewell ending in the exact words: talk
> to you later.

the model replied *"Okay, you are right. Miko is a great name for a friendly robot. Talk to
you later."* — the phrase, verbatim, at the end.

That is what the relay listens for (`sleepword.FarewellPhraseMatcher`, fed from
`assistant_text_delta`'s **`delta`** field). Deltas are accumulated, so a phrase split across
two of them — or mid-word — still matches; case, punctuation and whitespace are ignored. On a
match the relay runs the same drain as before: the farewell already in flight plays out, and
the conversation closes with reason `sleep_word` once the farewell's **derived end** (its
audio going quiet for `--reply-silence-ms`) and the robot's `playback{idle}` have both
arrived, or on the drain cap if the audio never goes quiet or the robot never reports idle.
Keying this off `agent_end` was what made every farewell close with `farewell_timeout` at
the 8 s cap instead.

The phrase is `--farewell-phrase` (default `talk to you later`). **It must match the persona
file**, which is what tells the model to say it; changing one means changing the other.
Nothing the person says can trigger it — there is no transcript of them to trigger it with.

## A typical conversation

```
client: {"type":"reset"}
client: {"type":"system","text":"You are Miko, ..."}
server: {"kind":"system_start","tokens":37,"t":0}
server: {"kind":"progress","t":0,"phase":"system","left":21}
server: {"kind":"system","t":12}                        the session is ready here
client: <PCM 16 kHz> <PCM> <PCM> ...                    continuous, silence included
server: <PCM 22.05 kHz> <PCM> ...                       also continuous, and already
                                                        flowing before anyone has spoken:
                                                        all zeros, the relay drops it
server: {"kind":"user_start","t":40}                    energy VAD, no transcript
server: {"kind":"user_end","t":78}
server: {"kind":"agent_start","t":79,"turn":1}          the relay opens a reply here
server: {"kind":"assistant_text_delta","delta":"It's sunny"}
server: <PCM 22.05 kHz> <PCM> ...                       loud: forwarded to the robot
server: <PCM zeros> <PCM zeros> ...                     no agent_end ever comes; 600 ms
                                                        of these end the reply, and the
                                                        rest is dropped again
```

And the last turn of one, which is how the conversation ends:

```
server: {"kind":"agent_start","t":310,"turn":7}
server: {"kind":"assistant_text_delta","delta":"Talk to "}
server: {"kind":"assistant_text_delta","delta":"you later."}   the farewell phrase matches
server: <PCM 22.05 kHz> <PCM> ...                              the farewell, forwarded
server: <PCM zeros> <PCM zeros> ...                            600 ms of silence: the
                                                               reply's derived end, and
                                                               end_reply() on the lane
robot:  {"type":"playback","state":"idle"}                     the speaker has run dry
relay:  {"type":"conv.close","reason":"sleep_word"}
```

If the person talks over the reply, the child sends `flush` and then `agent_end` with
`interrupted: true`; the relay stops forwarding that reply's audio on the `flush` and does
not depend on the `agent_end` arriving. That interrupted case is the only one where an
`agent_end` was ever observed.
