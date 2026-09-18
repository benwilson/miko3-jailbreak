# relay

The Python service between the robot's voice mode and the owner's model server
(VoiceChat 11B behind `webchat/server.py` on the Linux host). One asyncio process per
robot; it opens one model connection per conversation. Design: plan KTD1, KTD4, KTD5 in
`docs/plans/2026-09-17-1617-feat-autonomous-voice-mode-plan.md`.

- `relay/main.py` — the entry point: robot lane, conversation engine, log endpoints.
- `relay/conversation.py` — the conversation engine: per-robot state machine, reply-audio
  gate, silence timer, farewell drain, barge-in flush, strict turn-taking gate, model
  health probe.
- `relay/sleepword.py` — two matchers. `FarewellPhraseMatcher` is **live**: it watches the
  model's own reply text for the farewell phrase. `SleepWordMatcher`, the "Goodbye Miko"
  matcher over the person's transcript, is **dormant** — the model server transcribes
  nothing the person says (`docs/model-server-protocol.md`), so it has no input; it is kept
  for a future recogniser.
- `relay/lane.py` — the robot-facing WebSocket link and reply pacing.
- `relay/logging.py`, `relay/http.py` — per-conversation JSONL logs and their endpoints.
- `relay/model_client.py` — adapter for the model server's WebSocket protocol, plus the
  `probe()` health check.
- `personas/default.txt` — the default persona (the model's system prompt), kept short on
  purpose; `personas/full.txt` is the longer original. Both end with the farewell-phrase
  instruction, which is what ends a conversation.
- `tests/fake_model_server.py` — scripted stand-in for the model server, used by the tests.
- `docs/model-server-protocol.md` — the model server's protocol, the in-repo source of truth.

## Setup

Python 3.11 or newer; the only dependency is `websockets`. From the repo root:

```
python3 -m venv relay/.venv
relay/.venv/bin/pip install 'websockets>=14'
```

`relay/.venv/` is gitignored.

## Tests

```
relay/.venv/bin/python -m unittest discover -s relay/tests -t relay
```

The tests run against the fake server on 127.0.0.1 and need no network or robot. They
take a few seconds.

## Using it with the real model server

The model server accepts exactly one client, and a new connection closes the previous one.
Do not keep the model server's browser page open while the robot is in use: loading or
reconnecting it evicts the relay mid-conversation, and the relay's health probe in turn
evicts the page. The same goes for any other tool that connects to port 8765 or 8766.

## Running the relay

From `relay/`, pointing it at the model server's host (plain-WebSocket port 8766):

```
.venv/bin/python -m relay.main --model-host <model server address>
```

It prints `LISTENING lane ws://<lan address>:8790/ logs http://<lan address>:8791/conversations ...`
once bound. The robot's voice mode connects to the lane address and port. By default both
ports bind only this host's LAN address (never every interface); `--lane-host` and
`--http-host` override that. There is no authentication, like the other modes.

Every option can also be set in the environment as `RELAY_<OPTION>`, e.g.
`RELAY_MODEL_HOST=192.168.1.50`. `--help` lists them all; the ones you will tune:

| Option | Default | What it does |
|---|---|---|
| `--model-host`, `--model-port` | 127.0.0.1, 8766 | the model server |
| `--model-path` | `/ws` | the model server's WebSocket route (its `/` is the browser page) |
| `--lane-port` | 8790 | the robot's link |
| `--http-port` | 8791 | conversation logs |
| `--persona` | `personas/default.txt` | the persona file |
| `--log-dir` | `out/conversations` (gitignored) | one JSONL file per conversation |
| `--silence-timeout` | 90 | close when nobody has spoken this many seconds |
| `--drain-cap` | 8 | longest farewell after the phrase matches |
| `--farewell-start` | 1.5 | close at once if no farewell starts this soon |
| `--cooldown` | 0.3 | strict turn-taking: mic stays muted this long after the robot stops |
| `--reply-silence-ms` | 600 | a reply ends this long after its last non-silent audio chunk |
| `--reply-start-grace-ms` | 3000 | how long a reply opened by `agent_start` waits for its first sound (the TTS lags it) |
| `--reply-silence-rms` | 50 | reply-channel audio below this RMS counts as silence |
| `--uplink-gate-rms` | 700 | mute uplink audio below this RMS before it reaches the model; 0 disables the gate |
| `--uplink-gate-hang-ms` | 200 | the uplink gate keeps passing audio this long after the last loud frame |
| `--ready-timeout` | 12 | the model session must be ready this fast, else `model_error` |
| `--probe-interval` | 5 | model health probe period while the robot is listening |
| `--farewell-phrase` | `talk to you later` | end the conversation when the model's own reply says this |
| `--sleep-settle` | 0.5 | transcript age before the dormant "Goodbye Miko" matcher decides |

Run it under the service manager of your choice; stop it with SIGINT or SIGTERM. The
relay's own log goes to stderr (`-v` for every frame and event).

### Persona

The persona file is the model's system prompt, sent after `reset` at the start of every
conversation, so an edit takes effect at the next conversation after a relay restart.
Point `--persona` at your own file to try another one without editing the default.

**Keep it short.** The model reads the system prompt in before the session can be used and
the relay waits for that to finish before it sends `conv.ready`, so every word is paid again
at every wake. Measured against the real server: **1,495 ms for 30 words, 2,250 ms for 45**
— roughly 40-50 ms a word (the model server's own page says 80 ms, which is the figure to
plan against). The 29-word `personas/default.txt` costs about **1.5 s**; the long
`personas/full.txt` costs about **6 s**, and a page-long persona would push past
`--ready-timeout` and fail every conversation. The robot only buffers 3 s of microphone
audio while it waits, so a long persona also loses the first words the person says. What it
actually cost is in each conversation's log as `system_ms`.

It must also be **ASCII only**: the model server rejects anything else, and the relay refuses
to start with a non-ASCII persona (it names the offending character).

**Every persona must carry the farewell instruction.** Both files end with:

> When the person says goodbye, reply with a short farewell ending in the exact words: talk
> to you later.

That phrase is the only thing that ends a conversation by itself (below). It has to be
**the same string** in the persona file and in `--farewell-phrase` (default `talk to you
later`, `RELAY_FAREWELL_PHRASE` in the environment): **change one and you must change the
other**, or the relay will listen for words the model is never told to say and every
conversation will run until the silence timer. `tests/test_sleepword.py` checks that the
built-in default and both persona files still agree.

### How a conversation ends

The model server transcribes nothing the person says, so nothing the person says can end a
conversation — the "Goodbye Miko" sleep word has no input on this build. Instead the persona
is told to end its farewell with an exact phrase, and the relay watches the model's own reply
text (`assistant_text_delta`) for it. Deltas are accumulated, so a phrase split across two of
them still matches, and case, punctuation and whitespace are ignored.

On a match the relay drains: the farewell already playing is forwarded to the end, and the
conversation closes with `sleep_word` once the farewell's audio has gone quiet for
`--reply-silence-ms` and the robot's `playback{idle}` has arrived — or with
`farewell_timeout` at `--drain-cap` if the audio never goes quiet or the robot never reports
idle. The match arrives **before** the goodbye is spoken — the model's text runs seconds
ahead of its own TTS — so a reply that carried no speech does not count as the farewell; the
drain keeps waiting for one that did, because the point of draining is that the person hears
the goodbye. It deliberately does **not** wait for the model's `agent_end`, which never comes (see
below). Conversations also still end on the silence timer, on the robot's own `conv.close`,
and on a model error.

Prompt-declared **tool calls do not work** on this build — both a plain instruction and a
`<TOOLS>[...]</TOOLS>` JSON block were tried and the model just spoke the instruction aloud.
`docs/model-server-protocol.md` has the evidence.

### Reply audio: where a reply starts and stops

Two measured facts about the model server decide this:

- it streams reply-channel audio **continuously**, not only while it is speaking (228 frames
  over one 20 s run, no gap above 0.4 s, most of it while nobody was talking), and what it
  streams between replies is **exact digital silence, RMS 0.0**;
- **`agent_end` never fires** when a reply simply finishes. The child emits it only when the
  model closes its turn because the person started talking, so a reply that just ends
  produces an `agent_start` and nothing else.

A third one decides how they are timed: the server's **TTS lags its own `agent_start`** by a
variable amount — about 240 ms in one probe, over 640 ms in a live run where that cost the
person the whole farewell.

So the relay derives each reply's boundaries from the audio, on two timers that never run at
once. A reply **begins** at the first non-silent chunk (or at `agent_start`, if that lands
first). One begun by `agent_start` then has `--reply-start-grace-ms` (default 3000 ms) to
produce its first sound; if the grace expires first the reply ends empty (`no-speech`), and
audio arriving later still opens a fresh reply. From its first loud chunk the reply **ends**
`--reply-silence-ms` after its last non-silent chunk — or at `agent_end` or `flush`, if one
of those does arrive. Silence between two loud chunks is forwarded like any other audio; the
hangover is what stops a pause between two sentences from splitting a reply in two. Silence
outside a reply, and the lag at the head of one, are dropped and counted in the
conversation's `close` record as `out_of_reply`.

Arming the hangover at `agent_start` instead — before the reply had any speech — ended the
reply after 600 ms of silence, so the speech that followed arrived out of reply and was
dropped, and the farewell was never heard. `docs/model-server-protocol.md` has that run.

The derived end is what calls `end_reply()` on the lane, so the paced queue finishes and the
robot's speaker can run dry and report `playback{idle}`. Everything downstream keys off it:
the farewell drain, the strict turn-taking gate's reopening, and the conversation silence
timer. Waiting on `agent_end` instead meant the gate never reopened, the robot never went
idle, and every farewell closed on the 8 s drain cap with `farewell_timeout`.

### Uplink noise gate: the robot's room noise mutes the model

The Miko's microphone uplink has a **noise floor of about RMS 300-470** — measured per second
over a recorded uplink, where the quiet stretches ran 73-470 and speech ran 2,745-6,541. The
model's turn detection is a bare energy VAD that opens above roughly RMS 131 and **only
re-estimates its noise while it is not voiced**, so a floor that permanently exceeds its
threshold latches it "voiced" for good. Observed twice on the robot in strict turn-taking:
`user_start` fired, `user_end` never did, and the robot sat in a conversation the model never
replied to.

So the relay gates the uplink. Every chunk the robot sends is judged in **20 ms sub-frames**;
a sub-frame under `--uplink-gate-rms` (default 700) is replaced by **true digital silence**,
and `--uplink-gate-hang-ms` (default 200 ms) keeps passing audio for that long after the last
loud sub-frame so a word's decaying tail is not clipped. Chunks are never dropped, resized or
reordered — the model needs a continuous stream — and the same gate applies to the backlog
buffered while the model connection is still opening, so the VAD never meets the raw floor at
all. `--uplink-gate-rms 0` disables it.

The default sits in an empty valley. Over the recorded uplink, its 800 20 ms frames split
into 637 below RMS 1,000 (median **92**, max 982) and 163 above (min 1,001, median **4,333**):
nothing lives between 982 and 1,001, and 700 is the middle of that gap, which is why 400,
700 and 1,000 all gate within 7% of each other. At the default the recording loses 507 of its
800 frames, the longest unmuted run is 1.9 s and the longest muted one 3.7 s — plenty of
unvoiced time for the VAD's noise estimate. Gating that same recording offline made `user_end`
fire 2.8 s in and the model answer normally; ungated, `user_end` came far later or not at all.

This is independent of strict turn-taking and runs in both modes. Strict turn-taking's own
zero-fill (the whole mic muted while the robot speaks) is unchanged, comes first, and is
counted separately: the `close` record's `uplink` carries `zeroed` for that and `gated` /
`gated_frames` for this gate, so the field data shows whether the threshold is right.

### Conversation logs

`GET /conversations` lists every conversation (`id`, `started`, `close_reason`,
`records`, `bytes`); `GET /conversations/<id>` returns its JSONL. The `conv.ready` and
`close` records carry `system_ms`, the time the model spent reading the persona in. Each line is one record:
`ev` says what it is (`open`, `model`, `model.audio`, `robot`, `relay`, `sleepword`,
`drain`, `gate.close`, `gate.open`, `reply.end`, `underrun`, `turn`, `close`), `ts` is
wall-clock time and `rt_ms`
is milliseconds since the conversation opened on the relay's clock. The `turn` records
carry the latency figures: `user_end_to_first_chunk_ms` (relay-measured),
`robot_first_chunk_to_play_ms` (the robot's own figure) and `half_ping_rtt_ms`, plus the
model server's last `stats`, and how the reply was bounded: `begun_by`, `end_reason`,
`reply_ms`, `loud_chunks` and `silent_chunks`. One `reply.end` record per reply repeats
the duration and the `why` (`silence`, `no-speech`, `agent_end`, `flush` or `agent_start`),
so a field
log shows at a glance which signal ended each reply — in practice always `silence`. `sleepword` records log every match and near miss, with the
text it was decided on and a `source` saying which matcher decided: `assistant_text` is the
live farewell-phrase matcher, `final` and `partial` the dormant transcript one. The `close`
record's `out_of_reply` counts the reply-channel audio the model streamed outside any reply,
which the relay dropped, and its `uplink` counts what went the other way: `frames` and
`bytes` forwarded, `zeroed` chunks muted wholesale by strict turn-taking, `gated` chunks the
uplink noise gate touched with `gated_frames` 20 ms sub-frames inside them (backlog chunks
included, so `gated` can exceed `frames`), and the `backlog` buffered before the model was
ready. Audio is never logged.
