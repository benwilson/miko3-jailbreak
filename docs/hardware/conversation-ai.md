# "Real-time conversation" / conversational AI — cloud, not on-device

## Headline finding (CONFIRMED): there is no local LLM or offline STT/TTS on the Miko 3

Every step of the speech-in → understanding → speech-out loop that isn't
wake-word spotting or VAD (see `docs/hardware/voice-mic.md` §1-2) is a
**network call from ServiceExam to Miko's own backend**
(`m3usa1.miko-robot.in`, confirmed default in
`tools/serviceexam_jadx/resources/assets/miko.properties`), which itself
appears to broker to third-party cloud STT/TTS providers (Google, Azure,
Houndify, Amazon Polly — all four named explicitly in code/config, see
below). **No `.onnx`/`.tflite`/`.gguf`/GGML file anywhere in the decompiled
APK, its assets, or referenced `/sdcard` paths is a language model** — the
only on-device ML models found (`libonnxruntime.so`, `libncnn.so`,
`libtensorflowlite*.so`, and the bundled `.tflite` assets) are all used for
**wake-word spotting** (`recognizer/Openwakeword.java`, dead code — see
voice-mic.md §1) and **VAD** (`recognizer/OnDeviceVAD.java`, YAMNet) —
confirmed by reading every class that touches these runtimes. `libncnn.so`
is used by the **vision** pipeline (face/object detection —
`libfaceDetection.so`/`libobjectDetection.so` sit alongside it in
`lib/arm64-v8a/`), not speech.

What is genuinely **UNKNOWN** (this pass could not determine, and could not
ethically/safely determine given the no-live-backend-calls constraint on
this project) is **what generates the dialogue reply text itself** on the
backend side — i.e. whether Miko's server runs an LLM, a classic
intent/slot NLU + scripted-response system (AIML-flavored — note
`parseAIMLexpression`, `"<block>\n<expression>"` XML wrapper syntax
throughout the codebase, strongly suggestive of an AIML lineage), or some
hybrid. **The client-side evidence only tells you the transport and the
STT/TTS vendor split — not the "brain" algorithm**, because that lives
entirely server-side and is opaque to a device-side decompile. See "What
this doc cannot tell you" below.

## The pipeline, end to end (CONFIRMED)

```
[mic audio, PCM]
      |
      v
 recognizer/SpeechRecoTask.java  --WebSocket (wss)-->  Miko backend  --???-->  Google Cloud STT
      |                                                      |                  or Houndify
      | (binary frames = raw PCM; text frames = protocol)     |
      v                                                      v
 "TEXT<<<<transcript>"   <---------------------------  backend replies
 "RESPONSE<<<<dialogue reply text>"  <-------------------------+  (the "conversation" part —
                                                                     generation mechanism opaque)
      |
      v
 com/speech/synthesis/TTSService.java  --REST POST-->  Miko backend  --???-->  Google Cloud TTS
      |                                                                          or Azure or Polly
      v
 [audio bytes written to local file, played via AudioPlayer]
```

## 1. Transport & endpoints (CONFIRMED)

**STT / dialogue channel — WebSocket.** Opened in
`recognizer/SpeechRecoTask.addListener()`, called from
`SocialInteraction_SpeechChat.java:1903`:
```java
// SocialInteraction_SpeechChat.java:1903 — confirmed verbatim
SpeechRecoTask.addListener(this, this.act, this.p.getSpeechWebsocketURL() + botDetails.getUsername());
```
i.e. the URL is `<SPEECH_WEBSOCKET_URL property><bot's username>` — the
bot's own username is the entire "auth" appended to the socket URL (no
separate token/header seen in the connect call itself; TLS is required when
the URL is `wss://` — `SpeechRecoTask.java:576-583` branches on
`url.startsWith("wss")` to build an `SSLContext`-backed client vs. a plain
one). `SPEECH_WEBSOCKET_URL` itself is **not** in the bundled
`miko.properties` default file (only `BACKEND_URL`, `NET_URL`, `BASE_URL`,
`IP_URL`, `MIKO3_BASE_URL`, `NOTIFICATION_URL`,
`MIKO3_APPSTORE_BASE_URL` are) — it must be provisioned to the device at
runtime (post-login config push), so its live value is **UNKNOWN** from
static analysis; by analogy with `BACKEND_URL=http://m3usa1.miko-robot.in/miko/miko/`
a `wss://m3usa1.miko-robot.in/...` shape is the most likely (**INFERRED**,
not confirmed).

**TTS channel — plain REST POST**, `com/speech/synthesis/TTSService.java`,
to device properties `TTS_SERVICE_GETTTS_URL` / `TTS_SERVICE_GETMP3_URL`
(also not in the bundled defaults — server-provisioned, live value
**UNKNOWN**). Auth is a `Bearer <token>` header
(`APIS.getAccess_token()`), i.e. the device's own OAuth-style session token
from Miko's login/app-store API, not a hardcoded API key.

**Command/text channel** (typed input, or backend-side "commands"
independent of a fresh STT utterance) reuses the *same* WebSocket via a
`SEND_COMMAND<<<{json}` frame — see §3.

No evidence anywhere of a **direct** client → Google/Azure/AWS/OpenAI call.
`SpeechRecoTask.googleCloudAPI()` — the one method whose name suggests a
direct Google call — is dead code (just a null check that logs and
returns, `SpeechRecoTask.java:663-673`). The Amazon AWS SDK bundled in the
APK (`com.amazonaws.*`) is used only for S3 presigned-URL content download
(consistent with the earlier MikoPlus-only finding), not Polly's speech
API directly — Polly is only ever referenced as a **config string**
(`"TTS_PROVIDER":"POLLY"` in `miko.properties`' locale table), meaning the
backend calls Polly, not the device.

## 2. STT request/response shape (CONFIRMED)

Control frame sent once per new speech session (`speechServiceWebsocket()`,
`recognizer/SpeechRecoTask.java:716-785`), a JSON-serialized
`SpeechRecoConfiguration` (`recognizer/SpeechRecoConfiguration.java`) prefixed
with a literal tag and `<<<` delimiter:
```
START_WITH_CONFIG<<<{
  "Ack": <int>,
  "Active Skill": <int>,
  "Speech Timeout": <int ms>,
  "Speech Reco Provider": "GOOGLE" | "AZURE" | "HOUNDIFY",
  "Language Code": "en-US",
  "Servlet Session ID": "<session id>",
  "Backend URL": "http://m3usa1.miko-robot.in/miko/miko/",
  "Enable Data Compression": <bool>,
  "Disable Decryption": <bool>,
  "TTS Voice Name": "...", "TTS Language Code": "...", "TTS Provider": "...",
  "TTS Gender": "...", "TTS Rate": "...", "TTS Pitch": "...",
  "Idle Mode": <bool>, "EnableDictionary": <bool>, "DisableVAD": <bool>,
  "OnlyASR": <bool>,
  "product_id": "1", "parent_id": "...", "profile_id": "...",
  "bot_session_id": "...", "iheart_success": "..."
}
```
(field names are the exact `@SerializedName` values,
`SpeechRecoConfiguration.java:6-82`). Immediately after, raw 16-bit PCM
audio is streamed as **binary** WebSocket frames
(`speechSocket.send(ByteString.of(this.buffer, 0, this.buffer.length))`,
`SpeechRecoTask.java:769`) — no separate "end of utterance" framing beyond
VAD-driven silence detection client-side and an explicit
`sendToWebSocket(WS_END_SPEECH_RECO)` (`"END_SPEECH_RECO"`) /
`WS_CANCEL_SPEECH_RECO` (`"CANCEL_SPEECH_RECO"`) control message
(`SpeechRecoTask.java:538,550`).

Server → client messages are **text frames**, `<TAG><<<<payload>` (same `<<<`
delimiter), dispatched in `SpeechServiceSocketListener.onMessage`
(`SpeechRecoTask.java:217-377`). Confirmed tags and their meaning by call
site:

| Tag | Meaning | Handler |
|---|---|---|
| `TEXT<<<<...` | Recognized transcript (STT result) | `onSpeechRecognized(text, 0.0f, true)` |
| `DISPLAY<<<<...` | Text to show on screen (may differ from spoken reply) | `onDisplay(text)` |
| `RESPONSE<<<<...` | **The conversational reply text** — this is the "brain" output | `onResponse(text)` |
| `COMMAND<<<<...` | A structured command the backend wants the robot to execute | `onCommandResponse(text)` |
| `KW_REVALIDATION_PASS` / `_FAIL` | Result of cloud-side Sensory wake-word revalidation (see voice-mic.md §1) | `onSensoryRevlidationResult(bool)` |
| `RECO<<<<...` | "No input" recommendation/prompt | `noInputRecommendation(text)` |
| `BACKEND_ERROR` | Backend-side failure | `onBackendError()` |
| `SPEECH_STARTED` / `SPEECH_ENDED` | Server-confirmed VAD boundaries | `onSpeechStarted()`/`onSpeechStopped()` |
| `FAILURE_REASON_SILENCE` / (client-side) `FAILURE_REASON_NOISE` | STT failure reason | `onSpeechFailure(reason)` |
| `VOICE_TRAIN_SAMPLE_END` | Voice-training sample boundary | `onVoiceTrainSampleEnd()` |

**Binary server→client frames are gzip-compressed text** carrying the same
tag protocol — confirmed:
```java
// SpeechRecoTask.java:380-418 — confirmed verbatim (condensed)
public void onMessage(WebSocket webSocket, ByteString byteString) {
    BufferedReader r = new BufferedReader(new InputStreamReader(
        new GZIPInputStream(new ByteArrayInputStream(byteString.toByteArray())), "UTF-8"));
    String str = /* read all lines */;
    if (str.startsWith("RESPONSE")) {
        String reply = str.split(delimiter)[1];
        if (p.disableDecryption()) speechRecoListener.onEncryptedResponse(reply);
        else speechRecoListener.onResponse(reply);
    }
}
```
i.e. the same `RESPONSE<<<<reply` tag can arrive either as plain text or
gzip-compressed binary, and there's an `onEncryptedResponse` variant gated
by a `disableDecryption` device property — implying the `RESPONSE` payload
**can be encrypted** (on top of the gzip), with decryption normally handled
client-side before reaching `onResponse`. The encryption scheme itself
wasn't located this pass (see open questions).

**Typed/command input** reuses the same socket via a differently-shaped
config object, `CommandConfiguration`
(`recognizer/CommandConfiguration.java`, sent from
`SpeechRecoTask.sendText()`, `SpeechRecoTask.java:675-714`):
```
SEND_COMMAND<<<{
  "Ack": <int>, "Active Skill": <int>, "Servlet Session ID": "...",
  "Backend URL": "...", "Command": "<the text/command itself>", "Tag": "...",
  "Input Type": "...", "Enable Data Compression": <bool>, "Disable Decryption": <bool>,
  "TTS Voice Name": "...", "TTS Language Code": "...", "TTS Provider": "...",
  "TTS Gender": "...", "TTS Rate": "...", "TTS Pitch": "...",
  "product_id": "1", "parent_id": "...", "profile_id": "...",
  "bot_session_id": "...", "iheart_success": "..."
}
```
This is the path used when input bypasses STT entirely (e.g. app-driven
skill invocation, or typed chat) but still wants a conversational
`RESPONSE`/`COMMAND` back — strong evidence that **"conversation" is a
single backend concept regardless of whether the input came from voice or
text**, i.e. whatever generates `RESPONSE` text is a shared dialogue engine,
not something wake-word/STT-specific.

## 3. TTS request/response shape (CONFIRMED)

See `docs/hardware/voice-mic.md` §5 for the full `ttsRequest` field table —
reproduced here for this doc's self-containedness:
```
POST <TTS_SERVICE_GETTTS_URL>
Authorization: Bearer <device access token>
Content-Type: application/octet-stream   (body is actually JSON, see below)

{ "TTS Provider": "GOOGLE"|"AZURE"|"POLLY",
  "Language": "en-US", "Voice Name": "en-US-Standard-I",
  "Text": "<sentence to speak>",
  "Gender": "male"|"female", "Pitch": "+3st", "Rate": "100%",
  "miko_id": "...", "parent_id": "...", "product_id": "1" }

--> 200 OK, body = raw audio bytes (written straight to a file, no further
    client-side decode step visible — codec unconfirmed, likely MP3 given
    the sibling "GETMP3_URL" property name)
```
Voice/provider/gender/pitch/rate defaults come from
`synthesis/VoiceConfig.java` (`VoiceConfig.getActiveVoice()`), itself seeded
per-locale from `miko.properties`' `locale_master` JSON blob (each locale
entry has a `"voice_config"` object naming exactly these fields — see
voice-mic.md §5 for concrete per-locale examples spanning all three
providers).

## 4. On-device pieces that are NOT the conversational brain (to avoid confusion)

- **Wake-word** (`KeywordTask2`/`WakeWord.java`, TFLite;
  `KeywordTask`/Sensory `.snsr`) — only decides *when to start listening*,
  contributes nothing to what gets said back. See voice-mic.md §1.
- **VAD** (`OnDeviceVAD`, YAMNet TFLite; `NativeVAD`, native heuristic) —
  only decides *speech present/absent* for endpointing, not content. See
  voice-mic.md §2.
- **`Openwakeword.java`'s ONNX/TFLite pipeline** — dead code, and even if
  live, is a keyword spotter (binary "did they say the wake phrase"), not a
  language model — it has no text output, only a wake-class score.
- **`GoogleTranslate.java`** (`recognizer/GoogleTranslate.java`, 100 lines,
  not fully read this pass but named and structured as a translation
  helper) — worth flagging as a **separate** Google Cloud integration from
  STT/TTS; likely used for cross-language content, not dialogue generation.
  Marked **UNKNOWN** in depth, flagged for follow-up.
- **AIML-flavored expression parsing** (`parseAIMLexpression`,
  `<block><expression>{...}</expression></block>` XML wrapper) — this is
  the **client-side player** for combined speech+motion+lights+animation
  "expression" packets (see voice-mic.md §3 and §6), not a dialogue-generation
  engine. The AIML-style XML wrapper syntax is suggestive of Miko's
  *scripted/skill content format* having AIML lineage, but that's a
  content-authoring format for canned responses/skills, separate from
  whatever produces the `RESPONSE<<<<...` text for free-form conversation —
  these may or may not be the same underlying system server-side;
  **UNKNOWN**.

## What this doc cannot tell you (and why)

The device is, by design, a thin client for conversation: it streams audio
out and plays audio/text back in. **Nothing in ServiceExam's client code
reveals what algorithm the backend uses to go from a recognized transcript
to a `RESPONSE` string** — that logic runs entirely on
`m3usa1.miko-robot.in`'s servers, which are out of scope for a device-side
decompile and (per this project's constraints) were not probed live. It is
plausible the backend uses an LLM (the "real-time conversation" marketing
language and the free-form `RESPONSE` framing are consistent with one), a
traditional NLU/dialogue-manager stack (also consistent — Miko is
positioned as a structured, curated kids' product, and `Active Skill`/
`DEFAULT_SKILL`/`KW_SKILL` fields throughout the config suggest a
skill-routing architecture more than an open-ended chat completion API), or
a hybrid (skill router falling through to an LLM for open-ended chat) — **all
three are consistent with the evidence and none can be confirmed or ruled
out from this decompile.**

## Implementation — replicating equivalent conversational capability

Since the real backend cannot and should not be called from a third-party
app, replicating "real-time conversation" for a custom launcher means
**standing up your own backend** that speaks a compatible (or your own
simpler) protocol, and pointing the same client code at it. The client-side
integration is fully reproducible:

```java
// Minimal compatible client, mirroring recognizer/SpeechRecoTask.java's
// real protocol exactly (tag + "<<<" delimiter framing over OkHttp WebSocket).
OkHttpClient client = new OkHttpClient.Builder()
    .readTimeout(0, TimeUnit.MILLISECONDS)   // WS: no read timeout
    .build();
Request request = new Request.Builder().url("wss://your-backend/speech/" + botUsername).build();
WebSocket socket = client.newWebSocket(request, new WebSocketListener() {
    @Override public void onOpen(WebSocket ws, Response r) {
        SpeechRecoConfigLike cfg = new SpeechRecoConfigLike();
        cfg.speechRecoProvider = "GOOGLE";      // or your own STT choice server-side
        cfg.languageCode = "en-US";
        cfg.ttsProvider = "GOOGLE"; cfg.ttsVoiceName = "en-US-Standard-I";
        ws.send("START_WITH_CONFIG<<<" + new Gson().toJson(cfg));
    }
    @Override public void onMessage(WebSocket ws, String text) {
        if (text.startsWith("TEXT<<<")) { /* STT transcript */ }
        if (text.startsWith("RESPONSE<<<")) { /* your dialogue engine's reply text */ }
        if (text.startsWith("COMMAND<<<")) { /* structured action */ }
    }
});
// Stream mic audio (16-bit PCM) as binary frames, same as stock:
socket.send(ByteString.of(pcmBuffer, 0, pcmBuffer.length));
// End of utterance:
socket.send("END_SPEECH_RECO");
```
```java
// TTS: plain REST POST, same JSON shape as stock TTSService.getTTSBytes():
JsonObject body = new JsonObject();
body.addProperty("TTS Provider", "GOOGLE");   // or your own choice server-side
body.addProperty("Language", "en-US");
body.addProperty("Voice Name", "en-US-Standard-I");
body.addProperty("Text", replyText);
Request req = new Request.Builder()
    .url("https://your-backend/tts")
    .header("Authorization", "Bearer " + yourDeviceToken)
    .post(RequestBody.create(MediaType.parse("application/json"), body.toString()))
    .build();
byte[] audioBytes = client.newCall(req).execute().body().bytes();
```
For the dialogue engine itself (what fills in `RESPONSE`), since the real
one is opaque, you're free to choose: a hosted LLM API (e.g. Claude,
straightforwardly — see the `claude-api` skill/reference in this
environment for request shapes, streaming, and tool use if you want the
robot to take actions from natural language) or a rules/intent-based system
if you want tighter, more predictable-for-kids behavior similar to what the
`Active Skill`/`DEFAULT_SKILL` framing in Miko's own protocol suggests it
uses.

## Open questions

- **What algorithm the real Miko backend uses to generate `RESPONSE` text**
  — LLM, NLU/dialogue-tree, or hybrid. Unknowable from this decompile; would
  require either a leak/reverse-engineering of the backend itself (out of
  scope) or vendor disclosure.
- **The `RESPONSE` encryption scheme** implied by `disableDecryption`/
  `onEncryptedResponse` (`SpeechRecoTask.java`) — algorithm, key
  provisioning, and whether it's on by default were not traced.
- **Audio codec/sample-rate contract** for the STT binary PCM stream and the
  TTS response body — not found as hardcoded constants; likely fixed by
  convention with the backend (16kHz/16-bit mono is the industry-standard
  guess for STT PCM but unconfirmed).
- **`GoogleTranslate.java`** — not read in full this pass; may be relevant
  to multi-language conversation flows and worth a follow-up read.
- **Live values of `SPEECH_WEBSOCKET_URL`, `TTS_SERVICE_GETTTS_URL`,
  `TTS_SERVICE_GETMP3_URL`** — server-provisioned, not in the bundled
  properties; would need a passive capture during normal (authorized,
  already-owned-device) operation to pin down exactly, which this pass
  intentionally did not do (no live-backend calls per project constraints).
- Whether **`Active Skill`/`DEFAULT_SKILL`/`KW_SKILL` routing** happens
  before or after whatever generates free-form `RESPONSE` text (i.e. is
  there a skill-router that only falls through to open-ended conversation
  for unmatched input, or are skills and conversation the same backend
  code path with different config) — the client only sees the field names,
  not the server-side routing logic.
