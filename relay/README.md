# relay

The Python service between the robot's voice mode and the owner's model server
(VoiceChat 11B behind `webchat/server.py` on the Linux host). One asyncio process per
robot; it opens one model connection per conversation. Design: plan KTD1, KTD4, KTD5 in
`docs/plans/2026-09-17-1617-feat-autonomous-voice-mode-plan.md`.

- `relay/main.py` — the entry point: robot lane, conversation engine, log endpoints.
- `relay/conversation.py` — the conversation engine: per-robot state machine, silence
  timer, sleep-word drain, barge-in flush, strict turn-taking gate, model health probe.
- `relay/sleepword.py` — the "Goodbye Miko" matcher over the person's transcript.
- `relay/lane.py` — the robot-facing WebSocket link and reply pacing.
- `relay/logging.py`, `relay/http.py` — per-conversation JSONL logs and their endpoints.
- `relay/model_client.py` — adapter for the model server's WebSocket protocol, plus the
  `probe()` health check.
- `personas/default.txt` — the default persona (the model's system prompt).
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
| `--lane-port` | 8790 | the robot's link |
| `--http-port` | 8791 | conversation logs |
| `--persona` | `personas/default.txt` | the persona file |
| `--log-dir` | `out/conversations` (gitignored) | one JSONL file per conversation |
| `--silence-timeout` | 90 | close when nobody has spoken this many seconds |
| `--drain-cap` | 8 | longest farewell after "Goodbye Miko" |
| `--farewell-start` | 1.5 | close at once if no farewell starts this soon |
| `--cooldown` | 0.3 | strict turn-taking: mic stays muted this long after the robot stops |
| `--ready-timeout` | 3 | the model connection must open this fast, else `model_error` |
| `--probe-interval` | 5 | model health probe period while the robot is listening |
| `--sleep-settle` | 0.5 | transcript age before "Goodbye Miko" is matched mid-sentence |

Run it under the service manager of your choice; stop it with SIGINT or SIGTERM. The
relay's own log goes to stderr (`-v` for every frame and event).

### Persona

The persona file is the model's system prompt, sent after `reset` at the start of every
conversation, so an edit takes effect at the next conversation after a relay restart.
Keep it short and **ASCII only**: the model server rejects anything else, and the relay
refuses to start with a non-ASCII persona (it names the offending character). Point
`--persona` at your own file to try another one without editing the default.

### Conversation logs

`GET /conversations` lists every conversation (`id`, `started`, `close_reason`,
`records`, `bytes`); `GET /conversations/<id>` returns its JSONL. Each line is one record:
`ev` says what it is (`open`, `model`, `model.audio`, `robot`, `relay`, `sleepword`,
`drain`, `gate.open`, `underrun`, `turn`, `close`), `ts` is wall-clock time and `rt_ms`
is milliseconds since the conversation opened on the relay's clock. The `turn` records
carry the latency figures: `user_end_to_first_chunk_ms` (relay-measured),
`robot_first_chunk_to_play_ms` (the robot's own figure) and `half_ping_rtt_ms`, plus the
model server's last `stats`. `sleepword` records log every match and near miss with the
transcript, so the list of "Miko" spellings can grow from real conversations. Audio is
never logged.
