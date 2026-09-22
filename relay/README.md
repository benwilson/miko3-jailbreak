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
reconnecting it evicts the relay mid-conversation, and the relay in turn evicts the page.
The same goes for any other tool that connects to port 8765 or 8766.

### The warm session (`--prewarm`, on by default)

Reading the persona into the model is not instant, and it sits between "Hey Miko" and the
robot being able to hear the person. Measured live: **1,495 ms, 2,250 ms and 2,834 ms with
the server idle, and 10,902 ms with it busy**, against the robot's 15 s ready timeout. So
by default the relay keeps one **warm session** open: a model connection that has already
sent `reset` and the persona and had it acknowledged. A wake takes that session over and
sends `conv.ready` immediately, with **no persona read on the critical path at all**.

A warm session is opened when a robot links, and a new one after every conversation closes
— a session that has carried a conversation is never handed to another one, so each
conversation still starts with nothing remembered (R9). If no warm session is ready in
time, the conversation opens its own exactly as it would with prewarm off (and its log says
so: `warm.miss`).

**With prewarm on, the relay holds the model server for as long as a robot is linked, so
the owner's browser page cannot use the model at the same time.** The server accepts one
client and the newest connection evicts the previous one, so opening that page takes the
model away from the robot: the relay notices within a moment, reports `model_ok: false` to
the robot, and then re-warms every `--probe-interval` — which takes the model straight back
off the page, exactly as the health probe already did. The two cannot share it. The relay
does release the model whenever no robot is linked, so shutting voice mode down on the
robot gives the page the model back.

`--no-prewarm` (or `RELAY_PREWARM=0`) restores the previous behaviour: nothing is held
while idle, the relay connects per conversation, and every wake pays the persona read.
Health then comes from the `status` probe every `--probe-interval`, as before. With prewarm
on the warm session is the health signal instead — its liveness *is* the answer, and no
separate probe connection is ever opened beside it.

A warm session **waits in silence**: it sends `reset` and the persona and then nothing at
all until a conversation adopts it, when its uplink — and with it the 80 ms zero-frame
watchdog the model's VAD needs between the person's words — starts. The watchdog is not free
here: the model runs **slower than realtime** (measured `rtf` 1.8–3.3), so frames sent into
an idle session only pile up as input backlog. Live, a conversation opened with the model
already **221 frames (~18 s) behind and climbing to 489**, and after a couple of hours of it
the model degenerated into a repetition loop ("you are right. I am Miko.") reproducible in
the plain browser page with no relay involved. Liveness is still watched — the connection
closing, not traffic on it, is what says the model went away.

### The uplink runs on a frame clock: never more than realtime

**Measured live (round 9):** during a conversation `stats.input_backlog_frames` climbed
3 → 7 → 14 → 20 → 22 → 28 while the server itself was **faster** than realtime (`rtf`
0.70–0.76), and earlier in the same session it compounded until the model was **~39 s
behind** and degenerated. A server with 30% of headroom can only fall behind if it is fed
faster than realtime. It was: the relay forwarded each uplink chunk the instant it arrived,
**and** a watchdog filled any 80 ms slot nothing had gone out in — so over Wi-Fi a chunk
arriving a few milliseconds late cost **two frames for one slot**, about 15–20% over
realtime, every second of which is input backlog the model never gets back.

`ModelClient` now paces the uplink on a frame clock. One frame leaves per frame period, on
a deadline that advances by the audio duration of the frame just sent (so it can neither
drift late nor bunch to catch up). Whatever is queued at the deadline goes; when nothing is,
the zero frame goes, exactly as before — the model's VAD still needs silence to hear the
person stop. Chunks arriving faster than the clock are **queued, not dumped**: the release
queue holds about 5 s and past that drops the **oldest** and counts it, because a robot
persistently ahead of realtime is a bug and silently growing lag is worse than a drop.

The robot's pre-`conv.ready` buffer is paced the same way. It used to go out back to back to
catch up, and that is the one thing the model's input cannot absorb — its input is a
timeline, so N frames dumped in leave it N frames behind and it stays there (at `rtf` 0.70 a
10 s buffer costs over 30 s of lag; at the `rtf` 1.8–3.3 also measured it never drains).
Paced, the model hears the buffer at the rate the person spoke it: the first response comes
later by however long the robot was buffering — near zero on the warm path, where adoption
is same-tick — and the model is never left behind.

As a backstop the relay watches `stats.input_backlog_frames` itself. Past
`--backlog-alarm-frames` (default 25 frames, 2 s) it logs **one** loud warning per
excursion, naming its own measured send rate, and re-arms when the backlog recovers. It
takes no corrective action: the frame clock is the fix, and dropping audio on the alarm
would only hide a regression in it.

What each wake actually cost is in the conversation's log: `system_ms` is what that
conversation paid (0 when it adopted a warm session), and `warm_system_ms` what the warm
session had already paid for it.

### The downlink carries a cushion: seconds, not milliseconds

The reply direction has the opposite problem. The model's speech generation is capped at
about **1.0x realtime** by its duplex timeline — one 80 ms audio frame per 80 ms step — and
measures **0.88–0.97x** in practice, so reply audio arrives slightly *slower* than the robot
plays it. The robot's speaker drained mid-reply on every reply (`underruns=2..3` in its
engine log), heard as stuttering.

The fix is a cushion: the robot accumulates before it starts playing and keeps a margin
while it plays. Raising the robot's own prebuffer alone did nothing, because the relay was
the binding constraint — `ReplyPacer` sends chunks back to back only while its estimate of
the robot's speaker is under `--burst-seconds`, and that was **0.5 s**. Whatever the robot
was willing to hold, the relay would not send it. The default is now **2 s**, measured end
to end as a cushion that grows to about 1.4 s and holds there through a 0.95x reply instead
of sitting pinned at 0.50 s.

The small cap was there so a flush (barge-in) threw away little audio. Barge-in has since
been measured **not to work on this hardware at all**: the platform echo canceller ducks the
robot's microphone to RMS 41 while its speaker plays, against 399 in a quiet room, so the
person cannot be heard mid-reply and the flush path is nearly dead weight. A cushion against
a sub-realtime producer is worth far more than cheap flushes. A flush still discards
everything queued for that reply and still sends `audio.flush` ahead of anything else — it
simply discards more now.

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
| `--prewarm` / `--no-prewarm` | on | keep a model session warm so a wake does not pay the persona read; on, the relay holds the model server while a robot is linked (see above) |
| `--silence-timeout` | 90 | close when nobody has spoken this many seconds |
| `--drain-cap` | 12 | longest farewell after the phrase matches (a backstop; a slow server needs longer than 8 s to speak one) |
| `--farewell-start` | 1.5 | close at once if no farewell starts this soon |
| `--cooldown` | 0.3 | strict turn-taking: mic stays muted this long after the robot stops |
| `--reply-silence-ms` | 1500 | a reply ends this long after its last non-silent audio chunk (the gaps inside a slow server's bursty reply run past 800 ms) |
| `--reply-start-grace-ms` | 3000 | how long a reply opened by `agent_start` waits for its first sound (the TTS lags it) |
| `--reply-silence-rms` | 50 | reply-channel audio below this RMS counts as silence |
| `--reply-max-seconds` | 30 | hard cap on one reply, in seconds of the audio actually forwarded to the robot; 0 disables it |
| `--burst-seconds` | 2 | the largest cushion of reply audio the robot may hold; the relay sends back to back until its estimate of the speaker reaches this, then one chunk per chunk period (see above) |
| `--loop-guard` / `--no-loop-guard` | on | cut a reply once the model starts repeating itself |
| `--uplink-gate-rms` | 700 | mute uplink audio below this RMS before it reaches the model; 0 disables the gate |
| `--uplink-gate-hang-ms` | 200 | the uplink gate keeps passing audio this long after the last loud frame |
| `--backlog-alarm-frames` | 25 | warn once per excursion when the model's `input_backlog_frames` passes this (2 s); 0 disables |
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
the relay waits for that to finish before it sends `conv.ready`. With `--prewarm` on (the
default) that wait happens on the warm session, ahead of the wake, so a long persona costs
patience at link time rather than at every wake — but with `--no-prewarm`, or whenever no
warm session was ready, every word is paid again at every wake. Measured against the real
server: **1,495 ms for 30 words, 2,250 ms for 45**
— roughly 40-50 ms a word (the model server's own page says 80 ms, which is the figure to
plan against). The 29-word `personas/default.txt` costs about **1.5 s**; the long
`personas/full.txt` costs about **6 s**, and a page-long persona would push past
`--ready-timeout` and fail every conversation. The robot only buffers 3 s of microphone
audio while it waits, so a long persona also loses the first words the person says whenever
the read is on the wake path. What it actually cost is in each conversation's log as
`system_ms` (and `warm_system_ms` when a warm session paid it instead).

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

**A server slower than realtime sends its reply in bursts** — a loud stretch, then 800–900 ms
of silence while it computes, then another — and the hangover alone read every gap as the end
of the reply. One spoken sentence became a dozen 80 ms replies back to back
(`reply.end {"why": "silence"}` against `turn {"reply_bytes": 7056, "loud_chunks": 1}`), and
each one made the robot's player start and stop: the stuttering the person actually heard. So
the hangover does not end a reply while the model is evidently still talking:

- an `assistant_text_delta` for the reply in flight refreshes it exactly as a loud chunk does
  — the model keeps producing text right through the gaps — but only once the reply has
  spoken, because before that the text runs *ahead* of the TTS and the start grace governs;
- a non-zero `speech_queue` in the newest `stats` sample is TTS still queued for playback, so
  the hangover re-arms instead of ending the reply. Samples arrive only every few seconds, so
  a missing one blocks nothing and a stale one cannot hold a reply open for good.

The default hangover is **1500 ms**, above the measured gaps. Every other end condition is
unchanged: `agent_end`, `flush`, the next `agent_start`, and the start grace for a reply that
never speaks. In-reply silence is still forwarded, which is what keeps the robot's speaker fed
across a gap, so the player runs continuously through a burst. The `turn` record counts the
`text_deltas` that arrived inside the reply and the `queue_holds` the speech queue bought.

Arming the hangover at `agent_start` instead — before the reply had any speech — ended the
reply after 600 ms of silence, so the speech that followed arrived out of reply and was
dropped, and the farewell was never heard. `docs/model-server-protocol.md` has that run.

The derived end is what calls `end_reply()` on the lane, so the paced queue finishes and the
robot's speaker can run dry and report `playback{idle}`. Everything downstream keys off it:
the farewell drain, the strict turn-taking gate's reopening, and the conversation silence
timer. Waiting on `agent_end` instead meant the gate never reopened, the robot never went
idle, and every farewell closed on the drain cap with `farewell_timeout`.

### Making the robot stop talking: the length cap and the loop guard

The quantized model degenerates into repetition and will not stop. Live, one conversation,
42 replies: the person asked a simple question about zebras and got

> They are used for decoration... used in fashion... used in art... used in music... used
> in sports... used in religion... used in education...

and then it looped back to fashion/art/music/education and kept going. The machine was
**not** the constraint — rtf 0.64–0.70, input backlog 1–2 frames — this is the model. The
person asked for the robot to stop talking. A better quantization is a separate track; these
two guards are what make the robot usable meanwhile. They are independent, either one can
fire, and both are configurable.

**Hard length cap** (`--reply-max-seconds`, default 30, `0` disables). Measured on the audio
actually forwarded to the robot, not on the clock, so a server slower than realtime is never
cut off for being slow — only a reply that is genuinely too long to sit through is. Ends the
reply `too-long`.

**Repetition guard** (`--loop-guard` / `--no-loop-guard`, on by default). It watches the
assistant text of the reply in flight. Normalize — lowercase, every run of non-alphanumeric
characters becomes one space — then count each sliding window of **four consecutive words**
as it arrives, and trip on the **third** occurrence of any one window. Ends the reply
`looping`, and the repeated phrase goes in the log.

Four words, and not sentences, because of the transcript above: every sentence differs from
the last by *one* word, so counting whole sentences catches nothing until the model loops
back verbatim — another six sentences later. The window `they are used in` recurs in every
one of them and trips on the third, four sentences in. It is also why the window is not
longer: at five words `they are used in fashion` and `they are used in art` are different
units and the first pass is invisible. Not shorter, and not two occurrences, because
ordinary English repeats three-word runs innocently (`one of the`, `a lot of`) and any
phrase said once or twice is normal speech — "you are right" is an answer, "you are right"
three times is a stuck model. A window is counted at every position, so a one-word stutter
(`no no no no no no`) trips too. A long, rambling, non-repetitive answer survives untouched;
the tests assert that against a real answer to the same question that is *longer* than the
loop above. `Config.loop_guard_repeats` and `Config.loop_guard_window` are the thresholds.

When either one trips, the same thing happens:

- forwarding stops at once and the lane's `audio.flush` goes out for that reply, so the
  robot drops what it has buffered and goes quiet instead of talking on for as long as its
  buffer lasts;
- the reply ends with its own reason — `reply.end {"why": "too-long"}` or `"looping"`;
- **the rest of that model turn is muted.** The model keeps generating for a while after the
  cut — that is the whole reason it had to be cut — and every chunk of it would otherwise
  open a fresh reply and start the robot talking again. `dropping` (the barge-in path) is
  not enough here: it clears on the first silent chunk, and the server streams silence
  between its own bursts. The mute lasts until the turn genuinely ends — `agent_end`, a
  `flush` from the model, the next `agent_start`, `user_start` (the person speaks again) —
  or, if none of those ever arrives, `Config.cut_mute_cap` (20 s). Text arriving while muted
  is not matched against the farewell phrase either: a goodbye has to be *heard*, and this
  one will not be. What the mute dropped is counted and logged;
- **the conversation stays open.** The person can talk again, the silence timer still
  applies, the next reply plays normally and the farewell phrase still closes the
  conversation on it.

Both events are logged loudly at info level with the reason and a trimmed snippet of the
offending text, and recorded in the conversation JSONL — `reply.cut` (with `phrase` for a
loop), `reply.unmute` (with what it dropped) and a `cut` summary in the `close` record — so
the field data shows how often this actually fires.

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
ready. Its `uplink.model` is what the *model* was handed, past the relay's own gates:
`frames` in total, `sent` real ones and `zero` slots filled with silence, `queued` chunks
that went through the frame clock's release queue with its `queue_peak` depth, `dropped`
(`dropped_queue` at the bound plus `dropped_preready` while the persona was still reading
in), the `seconds` of audio and — the one that matters — `rate`, audio seconds per wall
second. **1.0 is realtime.** Audio is never logged.
