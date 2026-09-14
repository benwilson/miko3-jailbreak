# Microphone / voice pipeline — the real implementation (ServiceExam)

**Supersedes the "zero audio code" framing of the original version of this
doc.** That earlier pass only had `tools/base.apk` (MikoPlus,
`com.miko.mikoplus`) decompiled, correctly found MikoPlus itself owns none of
the audio pipeline, and flagged ServiceExam as the natural next target. This
pass decompiled ServiceExam itself
(`tools/serviceexam.apk` / `tools/serviceexam_jadx/`, package
`com.example.root.serviceexam`) and found the entire voice stack living
there: wake-word engine(s), VAD, the Conexant mic-array/DOA bridge and its
consumer, cloud STT/TTS clients, and the AIDL dispatch table MikoPlus talks
to. MikoPlus's own findings (AIDL codes, voice-training UI flow) are still
accurate as descriptions of the **UI side** of this same system and are kept
below where they still add value, but the "nothing exists" conclusions in
each section are now wrong and replaced.

Evidence sources for this pass: decompiled Java under
`tools/serviceexam_jadx/sources/`, the default bundled config
`tools/serviceexam_jadx/resources/assets/miko.properties`, and `nm -D`/`strings`
against the native libraries extracted from `tools/serviceexam.apk`
(`lib/arm64-v8a/libSnsr.so`, `libconexant_dsp_lib.so`,
`libnative_wakeword_vad_lib.so`). Everything tagged **CONFIRMED** below has a
file:line citation or a direct symbol/string dump; **INFERRED** is a
reasonable reading of adjacent confirmed code; **UNKNOWN** is flagged
explicitly rather than guessed.

## Architecture at a glance (CONFIRMED)

```
MikoPlus (UI)  <--AIDL (GameControllerAIDL/UIEventAIDL, JSON payloads)-->  ServiceExam
                                                                                |
                                                    SocialInteraction_SpeechChat (the "brain")
                                                                                |
        +--------------------+--------------------+--------------------+------+---------+
        |                    |                     |                    |               |
   KeywordTask2          OnDeviceVAD            DSPSettings          SpeechRecoTask   TTSService
   (local wakeword,      (on-device VAD,         (Conexant DSP       (cloud STT,      (cloud TTS,
   TFLite, always-on)    YAMNet TFLite or        DOA/AEC/NS bridge,   WebSocket to     REST to
        |                native lib)             drives DOA-based     Miko backend)    Miko backend)
   KeywordTask                                    turn-to-speaker)
   (Sensory .snsr,
   cloud revalidation)
```

Everything under `com/example/root/serviceexam`, `recognizer/`, `synthesis/`,
`com/speech/synthesis`, `com/example/conexantapi`, and
`com/common_source/emotix/interaction/interaction/SocialInteraction_SpeechChat.java`
(the ~9,000-line "brain" class referenced throughout) is ServiceExam-only —
none of it exists in MikoPlus.

## 1. Wake-word detection — CONFIRMED, two-stage (on-device + cloud revalidation)

**Primary, always-on detector runs on-device**, in
`recognizer/KeywordTask2.java`, backed by `recognizer/WakeWord.java`
(`System.loadLibrary("native_wakeword_vad_lib")`, confirmed exported JNI
symbols via `nm -D lib/arm64-v8a/libnative_wakeword_vad_lib.so`:
`Java_recognizer_WakeWord_init`, `_processChunk`, `_getLastScore`,
`_getNumOutputClasses`, `_resetState`, `_setThreshold(s)`). The Java side
loads a single multi-class TFLite model:

```java
// recognizer/KeywordTask2.java:62-75 — confirmed verbatim (condensed)
if (wakeWord == null) {
    mixpanelModelName = "miko_wakeword_model";
    wakeWord = new WakeWord();
    boolean zExists = new File("/sdcard/klug/models/miko_wakeword_model.tflite").exists();
    zInit = zExists
        ? wakeWord.initFromPath("/sdcard/klug/models/miko_wakeword_model.tflite")
        : wakeWord.init(mContext, "miko_wakeword_model.tflite"); // falls back to APK asset
    ...
}
```

`miko_wakeword_model.tflite` (535,628 bytes) is a **bundled APK asset**
(CONFIRMED — `unzip -l tools/serviceexam.apk` lists
`assets/miko_wakeword_model.tflite`, `assets/hey_miko_wakeword_model.tflite`,
and `assets/hey_miko_hello_miko_wakeword_model.tflite`), with an on-device
copy at `/sdcard/klug/models/` taking precedence if present (i.e. the model
is over-the-air updatable without an APK push). It classifies raw 16-bit PCM
audio chunks (`AUDIO_CHUNK_SIZE = 1280` samples,
`recognizer/WakeWord.java:13`) into 3 classes —
`DETECTION_NONE=0`, `DETECTION_HEY_MIKO=1`, `DETECTION_HELLO_MIKO=2`
(`WakeWord.java:14-16`) — entirely inside the native library; per-class
thresholds are configurable (`activation_threshold_hey_miko=0.65f`,
`activation_threshold_hello_miko=0.6f` defaults,
`KeywordTask2.java:28-29`, overridable per-state — Speaking/Sleep/Idle —
from locale config, `KeywordTask2.java:295-334`).

**A second, legacy on-device detector using Sensory Inc.'s "TrulyHandsfree"
SDK is instantiated alongside it** for cloud-side revalidation of local
triggers. Confirmed vendor identity via native strings:
```
$ strings libSnsr.so | grep -i sensory
Copyright (C) 2003-2022 Sensory, Inc. All Rights Reserved.
Copyright (c) 2008, Sensory, Inc., This property of Sensory, Inc.,
  is licensed to RN Chidakashi PL   [RN Chidakashi = Miko's manufacturer entity]
```
and JNI bindings under `com/sensory/speech/snsr/*.java` (SWIG-generated —
`SnsrJNI.java`, `SnsrSession.java`, `SnsrStream.java`, `SnsrConfig.java`,
etc.), used by `recognizer/KeywordTask.java`. This class references
per-locale/model `.snsr` files on `/sdcard/klug/enroll/`, e.g.
```java
// recognizer/KeywordTask.java:13,28-31 — confirmed verbatim
public static final String CLOUD_MODEL = "sensory-thf-enUS-hellomiko_phrasespot.snsr";
private String mModelPathUS = "/sdcard/klug/enroll/hello-miko-7_enUS_Improvized.snsr";
private String mModelPathConcurrent = "/sdcard/klug/enroll/tpl-spot-concurrent-1.2.0.snsr";
private String eftModelPath = "/sdcard/klug/enroll/enrolled_model.snsr";
```
and the default `miko.properties` locale table
(`resources/assets/miko.properties`) sets, per language,
`"KEYWORD_FILE":"/sdcard/klug/enroll/hello_miko_5_enUS.snsr"` and
`"EFT_FILE":"/opt/miko/taskData_hellomiko_enUS_v1.0.0.snsr"` (EFT =
"enrolled feature template", used for the per-child voiceprint — see §6 of
the historical MikoPlus section below).

**Both are constructed together at speech-chat init** (CONFIRMED):
```java
// SocialInteraction_SpeechChat.java:8095,8103 — confirmed verbatim (condensed)
KeywordTask keywordTask = new KeywordTask(this, this.act);   // Sensory .snsr path
...
KeywordTask2.init(this.act, this);                            // on-device TFLite path
```
Combined with the `SpeechRecoTask` WebSocket protocol's
`KW_REVALIDATION_PASS`/`KW_REVALIDATION_FAIL` messages (§4) and
`KeywordTask.doCloudRevalidation` (`KeywordTask.java:20`), the architecture
reads as: **the always-on TFLite classifier (KeywordTask2) makes the local
wake decision cheaply; a short audio buffer around the trigger is then
optionally sent to the cloud for Sensory-model revalidation** (reducing false
positives) before the robot commits to a full listening turn — an
**INFERRED** two-stage design consistent with all of the above but not
traced instruction-by-instruction.

A third, **unused/dead-code** wake-word implementation also exists:
`recognizer/Openwakeword.java` (428 lines) reimplements the openWakeWord
architecture directly in Java/ONNX Runtime/TFLite (mel-spectrogram model →
embedding model → per-keyword classifier, with explicit "Hey Miko"/"Hello
Miko" 1500ms/2000ms ONNX classifier heads and iteration-numbered model
filenames like `hey_miko_1500ms_third_iteration_no_amp_bias_noise_-10_to_30_db_with_silence_15_epochs.onnx`).
**CONFIRMED unreferenced**: `grep -rl "Openwakeword" .` returns only the
file itself — nothing else in the decompiled tree instantiates it. Read as
an R&D/training-evaluation harness for the model that was eventually baked
into the single `native_wakeword_vad_lib.so`-backed `WakeWord.java` path
above, not a live code path. Still useful for a custom launcher: it's a
complete, working openWakeWord-style engine in pure Java, if you'd rather not
write JNI for the native lib.

Wake phrase strings recovered on the MikoPlus pass (`"Hello Miko"`,
`Constants.SPEECH_RECO_STARTED_FROM_HELLO_MIKO`) match `WakeWord.classToName()`
(`WakeWord.java:20-25`) exactly — `DETECTION_HEY_MIKO` → `"Hey Miko"`,
`DETECTION_HELLO_MIKO` → the same `Hello_Miko` string constant.

## 2. Voice activity detection (VAD) — CONFIRMED, two implementations

**On-device, model-based** (`recognizer/OnDeviceVAD.java`): loads Google's
**YAMNet** audio-event-classification TFLite model
(`yamnet_classification.tflite`, `OnDeviceVAD.java:29`, 521 AudioSet output
classes, `NUM_OF_YAMNET_CLASSES = 521`) from `/sdcard/klug/enroll/`, runs it
on a rolling 15,600-sample buffer, and treats a frame as "speech" if the
top predicted label is in a fixed set:
```java
// OnDeviceVAD.java:86-90 — confirmed verbatim
SPEECH_CLASSES_SET.add("Speech");
SPEECH_CLASSES_SET.add("Child speech, kid speaking");
SPEECH_CLASSES_SET.add("Conversation");
SPEECH_CLASSES_SET.add("Speech synthesizer");
SPEECH_CLASSES_SET.add("Narration, monologue");
```
with configurable start/end confidence thresholds
(`SPEECH_START_THRESHOLD=0.5`, `SPEECH_END_THRESHOLD=0.2` defaults) and an
amplitude gate (`MIN_AMPLITUDE_THRESHOLD=2000` on raw PCM) before the model
even runs. `speechStarted()`/`speechEnded()` callbacks drive downstream
recording state.

**Native/DSP-side VAD** also exists: `recognizer/NativeVAD.java`
(same `native_wakeword_vad_lib.so` as the wake-word engine — confirmed JNI
symbols `Java_recognizer_NativeVAD_getVADPrediction`, `_runSpeechDetector`,
`_setThresholds`, `_setMinSpeechTime`, `_setMaxSilenceTimeout`,
`_setSpeechTimeout`). This is a lighter-weight, non-ML energy/pattern-based
VAD (function names suggest classic speech-detector heuristics — start/min
speech time, silence timeout — rather than a classifier) that can run
instead of or alongside YAMNet depending on device property gates
(`p.enableOnDeviceVAD()`, `p.enableVoiceTrainingOnDeviceVAD()`; also see
MikoPlus's `ENABLE_VT_ON_DEVICE_VAD` finding — now confirmed to gate this
exact code path). Also confirms the earlier MikoPlus doc's speculation that
"on-device VAD" was a real, named system concept — it is, and there are two
separate on-device implementations of it.

## 3. Microphone array / direction-of-arrival ("voice direction") — CONFIRMED, full mechanism traced

**Hardware/driver**: `com/example/conexantapi/{ConexantDSP,NCDsp,DSPSettings}.java`.
Two native backends behind one Java facade (`DSPSettings`), both in
`libconexant_dsp_lib.so` (native symbols confirmed via `nm -D`):
- `ConexantDSP` — talks to a Conexant DSP chip directly
  (`initDSPComm`, `setDSPMode` with named factory modes `MODE_ZALX`..`MODE_ZWV1`,
  `getDSPRawDOA`, gain controls, firmware flashing). Symbol
  `Java_com_example_conexantapi_ConexantDSP_getDSPRawDOA` confirmed exported;
  string table also contains `getDOA Angle = %d` and `getCurrentDOAStatus = %d`.
- `NCDsp` — an alternate/newer path ("NC" = presumably a second-gen Conexant
  chip) that opens a UART device node directly instead of a binary chip
  protocol: `createUART(500, "/dev/ttyMT2")` (or `/dev/ttyS1` if
  `isJoyARPort`, `DSPSettings.java:177`) with per-channel AEC/NS toggles,
  digital gain, and `toggleDOA`/`getCurrentDOAStatus`.

`DSPSettings.onCreate()` (`DSPSettings.java:35-92`) picks one backend based
on a runtime flag and configures input/output gain and DSP mode
(`MODE_ZMP8` if "factory mode" else `MODE_ZALX`). A background `DoAThread`
(`DSPSettings.java:16-33`) polls `getDSPRawDOA()` once a second as a
fallback path, but the actual consumer uses its own faster loop (next
paragraph).

**The consumer — this is the "turn to face who's speaking" mechanism** —
lives in `SocialInteraction_SpeechChat.java`'s `doaThread`
(`SocialInteraction_SpeechChat.java:375-463`). While `runDOA` is true (and
the robot isn't charging), it polls `mic.getDSPRawDOA()` in a loop, converts
the returned angle in **degrees** to a clamped integer "radians×10" value,
and **builds and executes a motion command** directly:
```java
// SocialInteraction_SpeechChat.java:396-411 — confirmed verbatim (trimmed)
socialInteraction_SpeechChat3.doaAngle = socialInteraction_SpeechChat3.mic.getDSPRawDOA();
int iAbs = (int) (((doaAngle * 3.141592653589793d) * 10.0d) / 180.0d);   // deg -> rad*10
if (Math.abs(doaAngle - prevDoaAngle) > 2.0f) {                          // only act on real movement
    if (Math.abs(iAbs) > 4) { iAbs = (iAbs * 4) / Math.abs(iAbs); }      // clamp to +/-4
    String str = "<block>\n<expression>{\"tx\":{...},\"ax\":{...},"
        + "\"mx\":{\"type\":4,\"size\":0,\"motion_type\":4,\"loop\":1,"
        + "\"kp\":0,\"ki\":0,\"kd\":0,\"pidcontrol\":0,\"seqCount\":2,"
        + "\"seq\":[{\"linear\":0,\"angular\":" + iAbs + ",\"time\":10240,\"type\":4,\"id\":0},"
        +        "{\"linear\":0,\"angular\":0,\"time\":10240,\"type\":4,\"id\":1}]},"
        + "\"ix\":{...},\"rx\":{...},\"id\":480}\n</expression>\n</block>";
    if (runDOA) {
        cpl = false;
        uinterface.parseAIMLexpression(str);   // executes the motion (turns the robot)
        waitCPL(5000);
    }
}
```
The `"mx"` block is a `MotionMsg`-shaped payload with
`"motion_type":4` — this is `commandInterface.MOTION_MESSAGE = 4`
(`com/example/root/expression/commandInterface.java:16`, identical constant
also defined in `com/miko/app_bluetooth/comm/message/commandInterface.java`).
**This is a real, populated motion packet, not a dead constant** — it
directly answers whether the Miko 3 can turn based on sound direction: yes,
and this is the exact code path. `uinterface.parseAIMLexpression(...)`
(`AndroidUnityInterface.java`) parses this same JSON shape used for canned
expressions/animations elsewhere in the file (compare e.g. `motion_stop` and
`auto` constants at `AndroidUnityInterface.java:157-162`, which use the
identical `"mx":{"type":4,"motion_type":4,...}` shape with hand-picked
`linear`/`angular` values) and ultimately reaches `si.SendData(bdata, ...)`
— the same serial-output path used for every other robot command, i.e. it
goes out over the serial link to the motion-control MCU exactly like a
manually authored turn/spin animation would. **This resolves the "how does
voice-direction actually work" question end-to-end**: Conexant DSP DOA angle
(degrees) → clamped scaled integer → synthesized single-axis-rotation motion
JSON (`linear:0, angular:<scaled DOA>`) → `parseAIMLexpression` →
serial motion packet → MCU turns the base to face the speaker. No camera or
vision involvement in this specific mechanism (it's audio-only localization).

Gain/AEC/NS configuration for the NC path is extensive and profile-driven
(`SocialInteraction_SpeechChat.java:6720-6742`): per-channel AEC
(`setLeftAECStatus`/`setRightAECStatus`), per-channel noise suppression
(`setLeftNSStatus`/`setRightNSStatus`), digital gain, VOIP mode, and
"low processed channel" selection (`setNCChannel`) are all set from device
properties (`p.disableAecNCLeftChannel()`, etc.) at startup — this is a
real, configurable two-mic (left/right) AEC/NS/gain pipeline, not a
single-mic passthrough as the MikoPlus-only pass concluded (that conclusion
was correct for MikoPlus's own WebRTC path, but wrong as a statement about
the device as a whole).

## 4. STT (speech-to-text) — CONFIRMED cloud, full protocol traced

See `docs/hardware/conversation-ai.md` for the full endpoint/protocol
writeup (backend URL, message framing, provider list, code sample). Summary:
`recognizer/SpeechRecoTask.java` opens an OkHttp `WebSocket` to
`p.getSpeechWebsocketURL() + botDetails.getUsername()`
(`SocialInteraction_SpeechChat.java:1903`), sends a `START_WITH_CONFIG<<<{json}`
control frame (`SpeechRecoConfiguration`, `recognizer/SpeechRecoConfiguration.java`)
naming a `speech_reco_provider` (`"GOOGLE"` default; `"AZURE"`/`"HOUNDIFY"`
also defined, `SpeechRecoConfiguration.java:7-9,22`) and language code, then
streams raw PCM as binary WebSocket frames. There is **no on-device speech
recognizer** in ServiceExam beyond wake-word spotting and VAD — the
`googleCloudAPI()` method that would be the "call Google directly" fallback
is dead code (`SpeechRecoTask.java:663-673`, just null-checks and returns).
**All STT goes through Miko's own backend**, which itself appears to broker
to Google/Azure/Houndify per the config field, based on locale
(`miko.properties`' `locale_master` table sets `"SPEECH_PROVIDER":"GOOGLE"`
for most locales and `"HOUNDIFY"` for `en_GB`).

## 5. TTS (text-to-speech) — CONFIRMED cloud, full request shape recovered

`com/speech/synthesis/TTSService.java` + `ttsRequest.java`. A plain REST
`POST` (OkHttp, Bearer-token auth via `APIS.getAccess_token()`) to a
device-property URL (`TTS_SERVICE_GETTTS_URL` / `TTS_SERVICE_GETMP3_URL`),
body:
```java
// ttsRequest.java:12-40 — field names as serialized (Gson @SerializedName)
{ "TTS Provider": ttsProvider,   // "GOOGLE" | "AZURE" | "POLLY" (per miko.properties voice_config)
  "Language": languageCode,       // e.g. "en-US"
  "Voice Name": voiceName,        // e.g. "en-US-Standard-I"
  "Text": sentence,
  "Gender": gender, "Pitch": pitch, "Rate": rate,
  "miko_id": ..., "parent_id": ..., "product_id": "1" }
```
Response body is written directly to a local file as raw audio bytes
(`TTSService.java:246-249`, `new FileOutputStream(str).write(responseExecute.body().bytes())`)
— no further decoding logic in this class, so the backend returns
ready-to-play audio (format not confirmed — likely MP3 given the sibling
`TTS_SERVICE_GETMP3_URL` property name). Per-locale defaults in
`miko.properties`' `locale_master` table name specific voices and providers,
e.g. `en_US` → `"TTS_PROVIDER":"GOOGLE"`, voice `"en-US-Standard-I"`; `ja_JP`'s
fallback English voice → `"TTS_PROVIDER":"AZURE"`; `en_GB` → `"TTS_PROVIDER":"POLLY"`
(Amazon Polly) — confirming the MikoPlus pass's speculative "Azure and
Google TTS used somewhere upstream" finding, and adding Amazon Polly as a
third confirmed provider, all selected per-locale/per-voice rather than
device-wide.

Playback itself is `synthesis/audio/AudioPlayer.java` (1,860 lines) — not
read in full this pass, but confirmed to be a local player (given
`TTSService` just writes a file) rather than a streaming decoder; treat its
exact codec support as **UNKNOWN**.

## 6. AIDL dispatch — CONFIRMED, corrects the earlier code-table

The MikoPlus pass's guess at command codes is now directly checkable against
`ServiceClientInterface.callEvent(int, String)`
(`com/example/root/serviceexam/ServiceClientInterface.java:549`), the single
3,485-line dispatcher every AIDL entry point (`GameControllerAIDL.GameEvent`,
`UIEventAIDL.UIEvent`, `IMyAidlInterface.sendString`, `AnalyticsAIDL.sendAnalytics`
— all four AIDL surfaces funnel here, `IPC_AIDL.java:63-217,298-322`) routes
through, keyed by an integer extracted from a `{code: payload}` JSON map
(`ServiceData`/Gson). Relevant codes confirmed by direct code inspection:

| Code | Meaning | Evidence |
|---|---|---|
| 131 | Speak (default/English TTS) | `ServiceClientInterface.java:946` → `interfacex.si.speakText(data, false)` |
| 152 | Speak (Hindi-specific variant) | `ServiceClientInterface.java:975` → `interfacex.si.speakHindi(data, false)` |
| 132 | StopSpeak | `ServiceClientInterface.java:1436` → sets `pauseTTSPlayer = true` |
| 133 | Expression + audio (no state-machine notify) | `ServiceClientInterface.java:1346` → `parseAIMLexpression(...)` |
| 212 | Expression + audio (variant flag `true`) | `ServiceClientInterface.java:1360` |
| 135 | Expression w/ path-relative audio + TTS merge | `ServiceClientInterface.java:1374` |
| 108 | Voice enrollment start/stop (legacy `EnrollK`-based) | `ServiceClientInterface.java:563-579` |
| 181 | Voice training (start/stop, multi-user variant) | `ServiceClientInterface.java:1985` — see below |
| 179 | Get current Sensory "operating point" | `ServiceClientInterface.java:1961` |
| 180 | Get EFT (enrolled feature template) file path | `ServiceClientInterface.java:1973` |

This refines rather than contradicts the original recovery (which only had
the MikoPlus-side call sites, not the dispatcher): **`152` is real but is
specifically the Hindi-locale speak path, not "Speak" in general** — the
general/default path is `131`. `132`/`133`/`181` match exactly.

The `181` (`VOICE_TRAIN_DATA`) handler (`ServiceClientInterface.java:1985+`)
confirms and extends the MikoPlus pass's voice-training finding: on
`"START_VOICE_TRAINING"` it sets DSP gain for voice training specifically
(`p.voiceTrainingNCGain()`/`getDSPVoiceTrainingInputGain()`), resolves the
locale's `EFT_FILE`/`VOICE_TRAIN_FILE` (falling back to `KEYWORD_FILE`), and
constructs an `EnrollK` instance
(`EnrollK.getInstance(DEFAULT_USER_NAME, ..., eftModelName, botname,
voice_train_file, locale, false, p)`) — `com/speech_enroll/EnrollK.java`,
which references `enrollment_context.snsr` (`EnrollK.java:25`) and a
`sv_threshold` (speaker-verification threshold, `EnrollK.java:47`) — **this
is on-device, Sensory-SDK-based speaker enrollment**, not a thin
UI-orchestration layer as the MikoPlus-only pass had to leave open; if
`p.enableVoiceTrainingOnDeviceVAD()` is false it additionally kicks off a
cloud round-trip via `SpeechRecoTask.sendToWebSocket("START_VOICE_TRAINING"...)`
with the same config, i.e. voice training can be on-device, cloud-assisted,
or both depending on device properties. Full trace of `EnrollK`'s internals
(is the voiceprint model itself on-device via `libSnsr.so`, or does it also
round-trip?) is **UNKNOWN** — flagged as an open question below.

## 7. Backend endpoints (CONFIRMED, default/bundled values)

From `tools/serviceexam_jadx/resources/assets/miko.properties` (bundled
default config, verbatim):
```
WEB_URL=http://m3usa2.miko-robot.in
NET_URL=http://m3usa2.miko-robot.in/mikoplus_graphapi/game/WS/
BASE_URL=http://m3usa2.miko-robot.in/mikoplus_graphapi/game/WS/
BACKEND_URL=http://m3usa1.miko-robot.in/miko/miko/
IP_URL=http://m3usa1.miko-robot.in/sparkcommonutil
MIKO3_BASE_URL=http://m3usa1.miko-robot.in/nf/
NOTIFICATION_URL=http://m3usa1.miko-robot.in/nf/
MIKO3_APPSTORE_BASE_URL=http://m3usa1.miko-robot.in/appstore/miko3/appstore/bot
MIKO_ENVIRONMENT=prod_1
```
`SPEECH_WEBSOCKET_URL`, `TTS_SERVICE_GETTTS_URL`, and `TTS_SERVICE_GETMP3_URL`
are **not** in this bundled file — they're read the same way
(`mikoProperties.getProperty(...)`) but must be provisioned at runtime
(server-pushed config after login), so their exact values are **UNKNOWN**
from static analysis alone. Given the `BACKEND_URL` pattern, a `wss://`
sibling under the same `m3usa1.miko-robot.in` host is the most likely shape
— **INFERRED**, not confirmed.

## Implementation — replicating this from a custom launcher

**Binding to ServiceExam's AIDL surface to send TTS / stop TTS / play an
expression** (mirrors `IPC_AIDL.java`'s `GameControllerAIDL.Stub` exactly —
this is the real, corrected code path, using the AIDL stub classes under
`com/root/aidlFiles/` which you can pull from this decompile):
```java
GameControllerAIDL gameControllerAidl = GameControllerAIDL.Stub.asInterface(binder);

// Speak (default/English) — code 131
Map<Integer, String> speak = new HashMap<>();
speak.put(131, new Gson().toJson(new ServiceRequest("Hello there!" /* data */)));
gameControllerAidl.GameEvent(new Gson().toJson(new ServiceData(speak)));

// Stop speaking — code 132
Map<Integer, String> stop = new HashMap<>();
stop.put(132, "");
gameControllerAidl.GameEvent(new Gson().toJson(new ServiceData(stop)));

// Voice training start — code 181 (unchanged from MikoPlus pass, now confirmed server-side too)
Map<Integer, String> vt = new HashMap<>();
vt.put(181, "START_VOICE_TRAINING");
gameControllerAidl.GameEvent(new Gson().toJson(new ServiceData(vt)));
```

**Reproducing local wake-word detection** (fully replicable — the model is
a bundled APK asset, and the native lib's JNI contract is fully typed):
```java
// Mirrors recognizer/WakeWord.java + KeywordTask2.java exactly.
// You will need your own build of (or a JNI-compatible replacement for)
// native_wakeword_vad_lib.so, or extract Miko's copy from the APK/device
// (lib/arm64-v8a/libnative_wakeword_vad_lib.so) — it's a generic TFLite
// wakeword-inference wrapper, not Miko-secret logic itself.
WakeWord wakeWord = new WakeWord();
wakeWord.init(context, "miko_wakeword_model.tflite"); // ship this asset yourself
wakeWord.setThresholds(0.65f /* hey miko */, 0.6f /* hello miko */);
short[] chunk = new short[1280]; // AUDIO_CHUNK_SIZE, 16-bit PCM
float[] scores = new float[3];
int detected = wakeWord.processChunk(chunk, scores); // 0=none,1=Hey Miko,2=Hello Miko
```

**Reproducing on-device VAD** (also fully replicable — YAMNet is a public
Google model, not Miko-proprietary):
```java
// Mirrors recognizer/OnDeviceVAD.java. yamnet_classification.tflite + yamnet_labels.txt
// are publicly available (TensorFlow Hub's YAMNet); Miko's only proprietary
// contribution here is the SPEECH_CLASSES_SET filter and thresholds.
OnDeviceVAD vad = new OnDeviceVAD();
OnDeviceVAD.init(0.5d /* start threshold */, 0.2d /* end threshold */);
vad.setVADData(pcmChunkBytes, isFirstChunk);
vad.run(); // fires vadListener.speechStarted()/speechEnded()
```

**Reading DOA and turning to face the speaker** — this is the piece that
depends on Miko's own Conexant firmware/hardware and therefore can only be
replicated on the same physical mic-array board (extract `libconexant_dsp_lib.so`
from the device, same as the wakeword lib):
```java
DSPSettings mic = new DSPSettings();
mic.onCreate(/* inputGain */ 0, /* outputGain */ 0, /* factoryMode */ false,
             /* isNC */ true, /* isJoyARPort */ false);   // opens /dev/ttyMT2 (or ttyS1)
float angleDeg = mic.getDSPRawDOA();
int scaled = (int) ((angleDeg * Math.PI * 10.0) / 180.0);
if (Math.abs(scaled) > 4) scaled = (scaled * 4) / Math.abs(scaled); // clamp, same as stock

// Build a MOTION_MESSAGE(4) expression block exactly like SocialInteraction_SpeechChat does,
// and hand it to whatever your own AIDL bridge to ServiceExam/expression-player exposes:
String motion = "{\"mx\":{\"type\":4,\"motion_type\":4,\"loop\":1,\"seqCount\":2,"
    + "\"seq\":[{\"linear\":0,\"angular\":" + scaled + ",\"time\":10240,\"type\":4,\"id\":0},"
    +        "{\"linear\":0,\"angular\":0,\"time\":10240,\"type\":4,\"id\":1}]}}";
// -> parseAIMLexpression(motion) equivalent -> serial motion packet -> MCU turns the base.
```

**STT/TTS**: see `docs/hardware/conversation-ai.md` for the full WebSocket
and REST implementations — both are cloud calls to Miko's own backend, so a
custom launcher wanting equivalent behavior needs its **own** backend
speaking a compatible (or self-designed) protocol; Miko's actual
`m3usa1.miko-robot.in` backend cannot and should not be called from a
third-party app (out of scope per this project's constraints, and it
requires a provisioned bot identity/auth this project does not have).

## Open questions

- Exact bytes/config sent inside `START_WITH_CONFIG`'s binary follow-up
  frames beyond what `SpeechRecoConfiguration`'s fields declare (sample
  rate, encoding) — not found as a hardcoded constant in `SpeechRecoTask.java`
  this pass; likely negotiated via the same config or a fixed convention
  agreed with the backend out-of-band.
- `EnrollK`'s internals (`com/speech_enroll/EnrollK.java`) — is the
  voiceprint match itself computed on-device via `libSnsr.so`, fully
  cloud-side, or both? Only the orchestration (thresholds, file paths,
  start/stop) was traced this pass.
- Whether `KeywordTask` (Sensory, cloud-revalidation path) and `KeywordTask2`
  (on-device TFLite) really are wired the "local-trigger → cloud-revalidate"
  way this doc infers, or whether they're two independently-selectable modes
  (e.g. per device tier/region) that never both run at once — the
  instantiation call sites are adjacent but the actual runtime wiring
  between them (does a KeywordTask2 trigger cause a KeywordTask cloud call?)
  wasn't traced statement-by-statement.
- `AudioPlayer.java` (1,860 lines) was not read in full — exact codec
  support and buffering strategy for played-back TTS/story audio is
  unconfirmed.
- Live values of `SPEECH_WEBSOCKET_URL`, `TTS_SERVICE_GETTTS_URL`,
  `TTS_SERVICE_GETMP3_URL` (server-provisioned, not in the bundled
  properties file) — would need a passive network capture during normal
  device operation to confirm, which is out of scope (no live-device
  interaction per this project's constraints).
- Whether `NCDsp`'s `/dev/ttyMT2` vs `/dev/ttyS1` selection
  (`isJoyARPort`) or `ConexantDSP`'s chip-protocol path is what our specific
  hardware revision actually uses — both are compiled in and
  runtime-selected; not resolved from static analysis.
