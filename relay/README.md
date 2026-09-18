# relay

The Python service between the robot's voice mode and the owner's model server
(VoiceChat 11B behind `webchat/server.py` on the Linux host). One asyncio process per
robot; it opens one model connection per conversation. Design: plan KTD1, KTD4, KTD5 in
`docs/plans/2026-09-17-1617-feat-autonomous-voice-mode-plan.md`.

- `relay/model_client.py` — adapter for the model server's WebSocket protocol, plus the
  `probe()` health check.
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
