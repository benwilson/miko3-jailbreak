# Model server protocol

The owner's model server is `webchat/server.py` (aiohttp) on a Linux host on the LAN, outside
this repo. It fronts a quantized VoiceChat 11B build run as a `llama-voicechat --live` child
process over stdin/stdout. This page is the relay's source of truth for that server's wire
protocol (plan U3, U4). The relay's adapter is `relay/model_client.py`; the scripted test
double is `tests/fake_model_server.py`.

Sources: the protocol as the owner described it (plan, Dependencies / Assumptions). Nothing
here has been checked against a raw dump of a live session yet. Items marked **Assumption**
are shapes this repo's code relies on that the owner's description does not pin down; confirm
them against the port-8766 dump from U3's verification and fix this page and
`model_client.py` together if they differ.

## Transport

| Port | Scheme | Notes |
|---|---|---|
| 8765 | `wss://` | TLS with a self-signed certificate. What the browser page uses. |
| 8766 | `ws://` | The same app without TLS. **The relay uses this port.** |

- Binds all interfaces. No authentication.
- **Single client.** The server accepts exactly one client; a new connection takes over and
  closes the previous one. A health probe, the browser page, or a second relay connection all
  evict whatever conversation is open.
- **Assumption:** the WebSocket path is `/` (the relay connects to `ws://host:8766/`).
- **Assumption:** the takeover close uses code 1000. The relay treats any server-side close
  as the end of the conversation, whatever the code.

## Client to server

### Binary frames: microphone audio

Raw PCM, 16-bit signed little-endian, 16 kHz, mono, no header. The client sends continuously,
**including silence**: the model detects the end of the person's turn from the silence, so
the stream must never stall. The browser page sends 80 ms chunks (1,280 samples, 2,560
bytes). Any whole number of samples per frame works; the relay forwards robot chunks as
they arrive and fills gaps of 80 ms or more with zero-filled 80 ms frames.

### Text frames: JSON commands

| Frame | Meaning |
|---|---|
| `{"type": "reset"}` | Start a fresh session: forget the conversation so far. |
| `{"type": "system", "text": "<persona>"}` | Set the system prompt (persona). **ASCII only.** Must be sent **before any audio**. |
| `{"type": "status"}` | Ask for a `status` reply. |

- **Assumption:** `system` carries the persona in a field named `text`
  (`SYSTEM_TEXT_FIELD` in `model_client.py`).
- The relay sends `reset`, then `system`, then audio, on every new connection, so each
  conversation starts fresh (R9). It rejects a non-ASCII persona before connecting.

## Server to client

### Binary frames: reply audio

Raw PCM, 16-bit signed little-endian, **22.05 kHz**, mono, no header. Chunk size is whatever
the server produces.

### Text frames: JSON events

| `type` | Meaning | Fields (assumed where marked) |
|---|---|---|
| `status` | Reply to the client's `status`. | **Assumption:** free-form; the relay only checks `type`. |
| `user_start` | The model heard the person start speaking. | none |
| `user_end` | The person stopped; the model's turn follows. | none |
| `agent_start` | The model starts replying; reply audio follows. | none |
| `assistant_text_delta` | A piece of the model's own reply text. | `text` |
| `agent_end` | The model's reply is complete. | none |
| `flush` | The model yielded (the person barged in): drop any reply audio already buffered and not yet played. | none |
| `stats` | Input backlog and speech queue. | **Assumption:** `input_backlog` (seconds) and `speech_queue` (count). The relay passes the frame through untouched. |
| `reset` | Session reset. | **Assumption:** acknowledges the client's `reset`; the relay ignores it. |
| `warning` | Non-fatal problem. | **Assumption:** `message` (or `text`), optional `code`. |
| `error` | Fatal problem. The relay closes the connection and ends the conversation with `model_error`. | **Assumption:** `message` (or `text`), optional `code`. |

### Planned: the person's transcript (pending U3 on the model host)

> **Planned, pending U3 on the model host.** `webchat/server.py` does not send these today.
> U3 adds them by parsing the user transcription that `llama-voicechat --live` prints. The
> relay's sleep-word matcher (KTD2) depends on them. Until U3 lands, a real session has no
> `user_text*` frames at all.

| `type` | Meaning | Fields |
|---|---|---|
| `user_text_delta` | A piece of the person's running transcript, sent while it streams. | `text` |
| `user_text` | The person's full transcript for the turn, sent at `user_end`. | `text` |

- Shapes mirror `assistant_text_delta`: `{"type": "user_text_delta", "text": "..."}` and
  `{"type": "user_text", "text": "..."}`.
- The deltas of one turn concatenate to that turn's `user_text`. The relay keeps the running
  concatenation and resets it when `user_text` arrives.
- **Assumption:** `user_text` is sent immediately after `user_end`, not before it. The relay
  does not depend on that order.
- Assistant speech never produces `user_text*` events.

## A typical turn

```
client: {"type":"reset"}
client: {"type":"system","text":"You are Miko, ..."}
client: <PCM 16 kHz> <PCM> <PCM> ...                   continuous, silence included
server: {"type":"user_start"}
server: {"type":"user_text_delta","text":"what is"}    planned (U3)
server: {"type":"user_text_delta","text":" the weather"}
server: {"type":"user_end"}
server: {"type":"user_text","text":"what is the weather"}   planned (U3)
server: {"type":"agent_start"}
server: {"type":"assistant_text_delta","text":"It's sunny"}
server: <PCM 22.05 kHz> <PCM> ...
server: {"type":"agent_end"}
```

If the person talks over the reply, the server sends `flush` and the relay stops forwarding
that reply's audio.

**Assumption:** an `agent_end` still follows the `flush`; the fake server sends one, and the
relay must not depend on it.
