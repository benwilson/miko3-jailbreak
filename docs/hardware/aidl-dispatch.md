# AIDL/IPC dispatch — MikoPlus ↔ ServiceExam

Scope: the full Binder contract between `com.miko.mikoplus` (MikoPlus, the UI
shell) and `com.example.root.serviceexam` (ServiceExam, the privileged
`android.uid.system` process that actually runs interaction/speech/vision
logic and gates access to `libmiko_drivers.so`/`libserial_port.so`). Source:
`tools/serviceexam_jadx/` — a fresh, independent jadx decompile of
`tools/serviceexam.apk` (`com.example.root.serviceexam`, **versionCode 92,
versionName 9.6.0**, `sharedUserId="android.uid.system"`), 5-dex multidex,
13,100 `.java` files. This is the same package/version (`v92`) that
`motors-wheels.md`/`buttons-power.md` previously analyzed from
`tools/APPS-extracted/file1.ia` — findings below that overlap are
cross-confirmed from a second, independently-produced decompile; findings
specific to the IPC/dispatch layer are new (that earlier pass focused on the
Arya serial protocol and button keycodes, not the AIDL surface itself).

Cross-reference: `docs/hardware/feature-inventory.md` §"`com.miko3.aidl_lib` /
`com.root.aidlFiles`" (the MikoPlus/client-side survey that first named these
interfaces and the known codes 152/132/133/181) — this doc traces the same
contract from the **server** (ServiceExam) side, where the dispatch logic and
the AIDL `Stub` implementations actually live.

## Headline finding: no signature/permission gate on the live AIDL surface (CONFIRMED)

`tools/serviceexam_jadx/resources/AndroidManifest.xml:85-96`:

```xml
<service
    android:name="com.example.root.serviceexam.MyService"
    android:permission="android.permission.INTERNET"
    android:enabled="true"
    android:exported="true"
    android:largeHeap="true"
    android:stopWithTask="false"
    android:visibleToInstantApps="true">
    <intent-filter>
        <action android:name="my.service"/>
    </intent-filter>
</service>
```

`MyService` is the **only** component that exposes the live AIDL contract
(see below), and it is `exported="true"` guarded only by
`android.permission.INTERNET` — a **normal** (install-time, non-dangerous,
non-signature) permission that Android auto-grants to any app that simply
declares `<uses-permission android:name="android.permission.INTERNET"/>` in
its own manifest, no user prompt, no runtime check. `IPC_AIDL.java` (the
class that builds the actual `Stub` objects, see below) and `MyService.java`
were grepped in full for `checkCallingOrSelfPermission`,
`checkCallingPermission`, `checkSignatures`, `getCallingUid`,
`getCallingPid`, `verifyCallingPackage` — **zero hits**. There is no
additional caller-identity check anywhere in the bind/dispatch path.

**Implication (the task's top open question, answered): a third-party app
needs no special permission, no signature match, and no root to bind
`MyService` and drive the exact same AIDL surface MikoPlus uses.** Any app
that declares `INTERNET` (near-universal) and sends
`Intent("my.service").setPackage("com.example.root.serviceexam")` to
`bindService()` gets a live `UIEventAIDL` binder into the privileged
system-UID process. This is the strongest finding for "can we just reuse
ServiceExam as-is" — yes, for everything reachable through this one Binder
entry point.

Two sibling services exist with the same posture (`WifiService`,
`action="wifi_service"`, `permission=INTERNET`, `exported=true`) and two
overlay services (`OverlayScreenServiceLib`/`SystemOverlayServiceLib`,
`exported=true`, no permission attribute at all) — not traced in this pass,
flagged as further attack/reuse surface in Open questions.

## Binder chain — how MikoPlus gets a handle, and how the callback loop is wired (CONFIRMED)

There are **six** named AIDL interfaces in play, all under package
`com.root.aidlFiles` (`tools/serviceexam_jadx/sources/com/root/aidlFiles/*.java`,
`classes3.dex`), each hand-generated Binder/Parcel boilerplate (no `.aidl` IDL
file shipped in either app — these are already-compiled `Stub`/`Proxy`
classes, meaning **a third-party app can lift these decompiled `.java` files
verbatim into its own project and use them directly**, no IDL recompilation
needed):

| Interface | Direction | Methods |
|---|---|---|
| `UIEventAIDL` | client→server, **the one `MyService.onBind()` returns** | `init(ExpressionEventAIDL, TouchEventAIDL, UIDataAIDL)`, `UIEvent(String)`, `executeCommand(String)` |
| `TouchEventAIDL` | client-implemented, handed to server via `UIEventAIDL.init()` | `init(AnalyticsAIDL, GameControllerAIDL)` (server→client, hands back 2 more binders), `touchEvent(String)` |
| `ExpressionEventAIDL` | client-implemented callback | `expressionEvent(String)` |
| `UIDataAIDL` | client-implemented callback | `updateUIData(String)`, `onCameraFrame(Bitmap, int)` |
| `AnalyticsAIDL` | **server-implemented**, handed to client via `TouchEventAIDL.init()` | `sendAnalytics(String)` |
| `GameControllerAIDL` | **server-implemented**, handed to client via `TouchEventAIDL.init()` | `GameEvent(String)`, `getConnectivityInitialisationStatus(String)`, `getData(String)` |
| `LoggerAIDL` | defined, **never instantiated or referenced anywhere in ServiceExam** | `LogEvent(String, String)` — dead/vestigial interface, confirmed by `grep -rl LoggerAIDL` returning only its own definition file |

Handshake sequence (`tools/serviceexam_jadx/sources/com/example/root/serviceexam/IPC_AIDL.java`):

1. Client (MikoPlus) sends `Intent("my.service")` targeting
   `com.example.root.serviceexam`, calls `bindService()`.
2. `MyService.onBind()` (`MyService.java:232-240`) returns
   `IPC_AIDL.getInstance().getAIDL()`, which is `this.uiEventAIDL` — a
   `UIEventAIDL.Stub` instance (`IPC_AIDL.java:259-378`, field `uiEventAIDL`).
   This is the **only** live entry point; a second, older AIDL surface
   (`com.example.root.servicepackage.IMyAidlInterface`, field `mBinder11`,
   `IPC_AIDL.java:41-162` — `init`/`sendString`/`sendCommand`/
   `getConnectivityInitialisationStatus`/`sendByte`) is fully implemented but
   **never returned from any `onBind()`** anywhere in the app — confirmed
   dead code, an earlier/alternate IPC shape superseded by the
   `UIEventAIDL`/`GameControllerAIDL` pair. (A third, even more vestigial
   path, `MessengerService.java`, wraps a plain `android.os.Messenger` —
   also never returned from `onBind()`, not wired to anything live.)
3. Client calls `uiEventAidl.init(myExpressionEventAIDL, myTouchEventAIDL, myUIDataAIDL)`
   — passing three client-side `Stub` implementations as Binder callbacks.
   Server-side handler (`IPC_AIDL.java:261-295`) stores them as static
   fields (`expressionEventAIDL`, `touchEventAIDL`, `uiDataAIDL`), fires
   `sendDataToUI("STATE_MACH_INIT_COMPLETE", "")` back to the client via one
   of those callbacks, then immediately calls back out:
   `touchEventAIDL.init(this.analyticsAIDL, this.gameControllerAIDL)`
   (`IPC_AIDL.java:284`) — handing the client two **server-implemented**
   `Stub` objects (`AnalyticsAIDL`, `GameControllerAIDL`) over the wire. This
   is the step `feature-inventory.md` observed from the client side as
   receiving "`GameControllerAIDL.GameEvent(String)`/`getData(String)`" —
   confirmed here: those `Stub`s live in ServiceExam's `IPC_AIDL` instance
   (`IPC_AIDL.java:191-258` for `gameControllerAIDL`, `163-190` for
   `analyticsAIDL`), and calling them from the client marshals a Binder
   transaction back into ServiceExam's process.
4. From then on, the client drives ServiceExam three ways: `GameControllerAIDL.GameEvent(json)`
   (the main command channel — this is where numeric codes like 152/132/133/181
   are sent), `AnalyticsAIDL.sendAnalytics(json)` (same dispatch table, a
   parallel entry point — see below), or `UIEventAIDL.UIEvent(json)` /
   `.executeCommand(str)` (a smaller, separate command set, still on the
   original binder from step 2). ServiceExam drives the client back via the
   three callbacks from step 3, queued through a 3-thread pool
   (`aidlTask.java`, `tools/serviceexam_jadx/sources/com/example/root/serviceexam/aidlTask.java`):
   `expressionEventAIDL.expressionEvent(str)`, `touchEventAIDL.touchEvent(str)`,
   `uiDataAIDL.updateUIData(str)` / `.onCameraFrame(bitmap, i)`.

```
MikoPlus (client)                          ServiceExam (server, uid system)
──────────────────                         ────────────────────────────────
bindService("my.service")  ───────────────▶ MyService.onBind()
                            ◀─────────────── returns UIEventAIDL.Stub
uiEventAidl.init(Expr,Touch,UIData) ───────▶ IPC_AIDL.uiEventAIDL.init()
                            ◀─────────────── touchEventAidl.init(Analytics,GameController)
gameControllerAidl.GameEvent(json) ────────▶ IPC_AIDL.gameControllerAIDL.GameEvent()
                                               → ServiceClientInterface.callEvent(code, data)
                            ◀─────────────── expressionEventAidl.expressionEvent(str)  (async, via aidlTask)
```

## Wire envelope (CONFIRMED, real code)

`GameEvent`/`sendAnalytics`/`UIEvent`/`sendString` (on `IMyAidlInterface`,
dead) all take a single JSON `String` that Gson-deserializes into:

`tools/serviceexam_jadx/sources/com/emotix/arya/required/ServiceData.java`:
```java
public class ServiceData {
    HashMap<Integer, String> data;
    public HashMap<Integer, String> getData() { return this.data; }
    public void setData(HashMap<Integer, String> map) { this.data = map; }
}
```
i.e. the wire shape is `{"data":{"<code>":"<payload-string>"}}` — the numeric
"code" (152, 132, 133, 181, ...) is a **map key**, not a method-argument or
AIDL transaction code. `IPC_AIDL.gameControllerAIDL.GameEvent()`
(`IPC_AIDL.java:193-217`) does, verbatim:
```java
ServiceData serviceData = (ServiceData) IPC_AIDL.this.gson.fromJson(str, ServiceData.class);
HashMap<Integer, String> data = serviceData.getData();
Iterator<Integer> it = data.keySet().iterator();
while (it.hasNext()) {
    int iIntValue = it.next().intValue();
    String str2 = data.get(Integer.valueOf(iIntValue));
    ServiceClientInterface.callEvent(iIntValue, str2);
}
```
`AnalyticsAIDL.sendAnalytics()` and `UIEventAIDL.UIEvent()` do the **identical**
unwrap-and-dispatch (same `ServiceData` → `callEvent(code, payload)` call),
just reached via different Binder methods — three different front doors into
the same dispatcher. `UIEventAIDL.executeCommand(String)` is a fourth,
narrower front door: it does **not** go through `ServiceData`/`callEvent` at
all, it string-matches the raw payload directly (`"START_IMAGE_CAPTURE"`,
`"STOP_FACE_RECO"`, `"GET_APP_VERSIONS"`, `"REINIT_TTS"`,
`"RESET_STATE_MACHINE"`, etc. — `IPC_AIDL.java:324-377`).

The per-code payload (`str2` above) is itself sometimes plain text (e.g. code
181's `"START_VOICE_TRAINING"`), sometimes a second layer of JSON that
deserializes into `ServiceRequest`
(`tools/serviceexam_jadx/sources/com/com/emotix/skills/story/ServiceRequest.java`
— yes, the package really is `com.com.emotix...`, not a typo):
```java
public class ServiceRequest {
    public String name, data, path, audioPath, extra = "";
    public int type;
    public Map<String, String> map;
    // + getters/setters
}
```
used by every TTS/expression code (131, 132, 133, 134, 135, 152, 153, 190,
212 — see table) via `gson.fromJson(str, ServiceRequest.class)`, reading
`.getData()` as the text-to-speak/expression payload and `.getAudioPath()`
for an optional bundled audio path.

## Central dispatcher: `ServiceClientInterface.callEvent(int, String)`

`tools/serviceexam_jadx/sources/com/example/root/serviceexam/ServiceClientInterface.java`,
3485 lines total, `callEvent` at line 549. **Not a `switch`** — a long
sequential chain of `if (i == N) { ... }` blocks (mostly non-exclusive:
several early ones fall through and get re-checked, e.g. `i == 121` is
tested twice, harmlessly). Every code found, with file:line and what it
actually does:

### Codes named in the task brief (CONFIRMED)

| Code | Name (MikoPlus-side) | Line | What it does |
|---|---|---|---|
| **152** | `Speak` | `ServiceClientInterface.java:975-1007` | Clears `pauseTTSPlayer`; on a background thread, if state machine is `Sleep` waits 1s and just sends a "speech complete" ack instead of speaking; otherwise parses `str` as `ServiceRequest`, marks `activeAppModel.isOthers()` state as non-interruptible, and calls `interfacex.si.speakHindi(data, false)` — despite the name, this is the **generic TTS entry point** (not Hindi-specific; `speakHindi` is the shared TTS codepath used for every locale once `LocaleConfig` is set — see `voice-mic.md`). |
| **132** | `StopSpeak` | `ServiceClientInterface.java:1436-1464` | Sets `pauseTTSPlayer = true`; background thread checks the `Sleep` state (same early-out as above), re-enables auto state-transition, then calls `interfacex.si.stop_all()` — halts TTS/audio and any in-flight expression. |
| **133** | expression+audio | `ServiceClientInterface.java:1346-1359` | Parses `ServiceRequest`; calls `sendExpressionStart()` → `parseAIMLexpression(data, true, audioPath, false)` → `sendExpressionComplete()` — plays a named expression (face animation + optional bundled audio) and brackets it with start/complete callbacks to the client. |
| **181** | voice-training start/stop | `ServiceClientInterface.java:1985-2101` | String-matched (not further JSON), five sub-commands: `"START_VOICE_TRAINING"` (sets DSP mic gain for enrollment, resolves the locale's EFT/keyword file, instantiates `EnrollK` and calls `.start()` — this is the on-device enrollment engine `feature-inventory.md`'s `VoiceTrainingMainFrag` drives), `"START_VOICE_TRAINING_MULTI_USER>>>userId"`, `"IS_MULTI_USER_TRAINING_POSSIBLE"`, `"DELETE_USER>>>userId"` (or `>>>ALL"`), and `"STOP_VOICE_TRAINING"` (restores normal mic gain, stops/nulls the `EnrollK` instance, optionally regenerates a background model from the partial enrollment context). Also gated on `mikoProperties.enableVoiceTrainingOnDeviceVAD()` — when off, the payload is additionally forwarded over `SpeechRecoTask.sendToWebSocket(...)`, i.e. voice training has both an on-device (`EnrollK`) and a cloud-relay path, matching `feature-inventory.md`'s finding that on-device VAD is an opt-in property flag. |

### Every other code identified (CONFIRMED, file:line as shown; grouped by rough category)

| Code | Category | Line | Short description |
|---|---|---|---|
| 23 | no-op | 1465 | Logs only, "Entered Globals playing expression" — no action. |
| 98989898 | debug/test | 1644 | Test-only trigger for `showGraphPopupConfirmation`. |
| 103 | WiFi | 1467 | Connect to a saved network (`WifiModel` JSON, password redacted before analytics log). |
| 104 | UI event bus | 794-937 | Large sub-dispatch on the string payload: `SPEECH_START`/`STOP_SPEECH_PAUSE_TTS`(`_CLASSROOM`)/`SPEECH_STOP`/`KEY_START`/`KEY_STOP`/`RESTART`/`ROBOT`/`STOP`/`PLAY_CONNECTING_EXPRESSION`/`PLAY_SDCARD_UNAVAILABLE`/`PLAY_TALENT_HIDDEN`/`WAKEUP_FROM_UI`. `KEY_START`/`KEY_STOP` map to `AndroidUnityInterface.TRIGGER` with data `"1"`/`"0"` — **this is the closest thing to a "key" in the whole dispatcher, and it is a UI-driven soft trigger, not a hardware `KeyEvent`** (consistent with `buttons-power.md`'s finding that no physical key is intercepted anywhere in either app). |
| 105 | chat | 1485-1490 | Forwards `str` as a `ROBOT_CHAT` event to the Unity-rendered face/chat UI. |
| 106 | app-focus state machine | 1496-1600 | Large block: `ActiveAppModel` JSON drives the `MikoStateMachine` between root/TalkToMiko/Classroom/other-app states — the core "what's currently in front" state transition. |
| 107 | vision | 787-790 | `interfacex.setImageComplete()` — face-capture-complete ack. |
| 108 | vision/enrollment | 564-581 | `"...STOP"` stops `EnrollK`/audio recorder; otherwise logs "starting enrollment" (legacy path, largely superseded by 181's `EnrollK` flow). |
| 109 | touch | 938-945 | Sends `"TCHDB"` tag over the Arya serial link (`serialDevice.generate500ByteData`, see `motors-wheels.md`) and sets Bluetooth-interface mode 1. |
| 121 | vision | 582-793 | "Image complete received" ack, logged twice (dead-code duplication, same `if` body). |
| 122 | app-focus events | 590-653 | String-matched sub-events on the active-app state: `ACTIVE_APP`(`>>>pkg`)/`ACTIVE_OTHERS`/`STARTSTATETRANSITION`/`STOPSTATETRANSITION`/`UPDATETIME`/`NO_INTERRUPTION`/`EXIT_NO_INTERRUPTION`. |
| 123 | analytics | 1648-1651 | Forwards a generic `AnalyticsModel` to `APIS.dynamic_analytics_api_ui`. |
| 131 | TTS | 946-974 | Same `ServiceRequest`→`speakText(data, false)` pattern as 152/153 but via a distinct locale-neutral `speakText` call (vs `speakHindi`). |
| 134 | expression | 1325-1344 | `parseAIMLexpression(data, true, audioPath, false)` **without** the start/complete bracketing 133 has. |
| 135 | expression (TTM-aware) | 1374-1435 | Most elaborate expression path: builds an `ExpressionMsg` from a stored expression file (`loadExpressionString`), optionally prefixes audio paths, optionally attaches a generated `TTSMsg`, then `playExpression1(...)` + `waitExpression(-1)` — a synchronous "play this whole expression+TTS sequence and block until done" call. |
| 136 | TOF sensor | 1666-1667 | Forwards `TofValue` back to the client as event code 125. |
| 137 | reset | 1668-1670 | `interfacex.si.resetComplete()`. |
| 138/139 | bot profile | 1671-1701 | Get/Set bot details (child profile JSON) via `APIS`, round-tripped back through `uiDataEvent`. |
| 150 | skill/graph lifecycle | 655-783 | Large sub-dispatch: `ENGLISH_STARTED`/`ENGLISH_CLOSED`/`HINDI_STARTED`/`HINDI_CLOSED`/`SPLASH_PLAYED_KW`/`SELF_TEST*`/`VOICE_SAMPLE_APP*`/`MISSION_GRAPH_STARTED`/generic `Game>>>Locale>>>STARTED|CLOSED[>>>Type]` — this is the "which conversational skill graph is currently driving speech recognition" state machine. |
| 151 | keyword app id | 784-786 | Sets `CUSTOM_KW_APP_ID`. |
| 153 | TTS | 1293-1324 | Identical body to 152 (`speakHindi`) — a second code number for the same TTS call, likely a versioned/legacy duplicate. |
| 155 | skill (CrickoBuzz) | 1728-1737 | Starts/stops a specific third-party skill's websocket session. |
| 157 | (excluded) | 1738 | `else if (i != 157)` — 157 is explicitly the **one** code in this range that does nothing (falls through the whole rest of the `else if` chain). |
| 158 | voice preset | 1740-1752 | Applies a `VoiceList`/TTS voice config (shape/speed) and persists it via `BackendCalls.setVoiceData` — this is the REST-backed voice-preset picker `feature-inventory.md` describes. |
| 159/160 | WiFi | 1811-1841 | Add/forget a network (`WifiModel`, password redacted in logs). |
| 162 | notifications | 1753-1755 | Refresh bounced-notification cache. |
| 163 | notifications | 1756-1778 | Show a notification "graph" popup, with a `TIMEZONE_CHANGE` special-case that clears pending alarms. |
| 164/165 | volume/FTUE | 1860-1865 | 164 logs only ("volume overlay requested"); 165 marks FTUE complete and starts the app-store service. |
| 166/170/200 | analytics/Mixpanel | 1652-1665 | Three near-duplicate Mixpanel-analytics forwarders (typed model, raw JSON, property update). |
| 167 | video-call motion | 1866-1896 | Maps an incoming Teleconnect "Motion" property (during an active video call) to a canned expression via `playExpressionMap`, debounced 500ms. |
| 168/169 | WiFi | 1842-1859 | Enable/disable auto network-switching. |
| 171 | TOF sensor | 1897-1905 | Start/stop the time-of-flight sensor (`startTof()`/`stopTof()`). |
| 172/173 | volume | 1906-1915 | Set/get bot volume by level (see `buttons-power.md` for the quantized-scale logic this calls into). |
| 174 | mic gain | 1916-1922 | Bumps mic DSP gain during "Dance Master" skill, restores default gain otherwise. |
| 175 | auto-mode | 1923-1933 | Start/end idle autonomous mode (`AutoModeFragment` trigger — see `feature-inventory.md`'s open question on this fragment). |
| 176 | TTS (name) | 1934-1940 | Synthesizes the child's name to a fixed file path for use elsewhere. |
| 177 | app-store | 1944-1946 | Triggers `CLEAR_SYSTEM_UPDATE` verification. |
| 178/179 | keyword tuning | 1947-1972 | Set/get an "operating point" integer for keyword-spotter sensitivity (`SocialInteraction_SpeechChat.d.setCurrentOperatingPoint`). |
| 180 | voice enrollment | 1973-1984 | Returns the enrollment-transcript (EFT) file path to the client. |
| 182 | skill lifecycle | 2105-2175 | 6-field `>>>`-delimited payload; `START`/`CLOSE` a "skill application" (sets trigger-enable, locale, keyword-vs-custom mode, short/long skill-type, story-maker flag). |
| 183 | mission graph | 1779-1794 | Plays a guided-mission conversation graph. |
| 184 | vision | 2176-2186 | Stop face-recognition capture. |
| 185 | ritual | 2187-2206 | Plays the wake/greeting "ritual" expression (mute-aware, locale-gated), starts face recognition if the ritual is enabled for the active locale. |
| 186 | state query | 1795-1802 | Sends the current `MikoStateMachine` state name back to the client. |
| 187 | notification retry | 1803-1810 | Runs a lambda on notification-reset failure. |
| 188 | mic diagnostics | 2207-2227+ | `IS_NS_DSP_IS_JOYAR` returns whether the unit is a Joyar-port/NS-DSP variant (ties into the `isJoyarPort`/UART-selection logic documented in `motors-wheels.md`); `CLOSED` disables raw DOA (direction-of-arrival) mic status. |
| 189 | profile | 1941-1943 | Caches child's display name from UI. |
| 190 | TTS (URL) | 1008-1040 | Same `ServiceRequest`→speak pattern, via `speakHindiForURL` (TTS of a URL/link, presumably read-aloud of a web result). |
| 191 | iHeartRadio skill | 1041-1066 | `PAUSE`/`RESUME`/`STOP`/`SKIP`/`EXIT` playback control + Mixpanel event forwarding. |
| 192 | voice training | 1067-1072 | Marks voice-training as done if the enrollment (EFT) file now exists on disk. |
| 193 | notification sound | 1073-1080 | Plays a named popup sound file. |
| 194/195 | iHeart | 1081-1097 | Disable trigger / record success-status of an iHeart request. |
| 196 | session | 1098-1105 | Forwards a bot-session-ended event. |
| 197 | JioSaavn skill | 1285-1292 | Playing-state flag for the JioSaavn music skill. |
| 198 | notifications | 1264-1273 | Clears all priority notifications. |
| 199 | update | 1274-1284 | Deletes the update-status file (twice, 200ms apart) — OTA housekeeping. |
| 201 | TTM (talk-to-Miko) | 1106-1128 | Forces focus to root, shows/hides a "playing graph" dialog, sends a chat event, and pushes a `SPEAKING_STATE_START` state-machine signal — a scripted "interrupt and speak this" path. |
| 202 | story maker | 1129-1140 | Sets a "story maker TTS playing" flag. |
| 203 | locale | 1141-1148 | Directly sets the active locale. |
| 204 | parental controls | 1149-1158 | Re-validates bedtime/daily-limit/break-time rules and applies them. |
| 205 | factory/QA | 1159-1189 | Runs a shell script (`/sdcard/klug/OTA_1100/testing_all_in_one.sh`) via `ProcessBuilder`, reboots on a specific exit code — a factory self-test hook. |
| 206/207 | login | 1190-1199 | Log-only / force a token refresh. |
| 208 | login | 1201-1216 | Reports whether login previously failed due to a key issue, round-tripped to the client. |
| 209 | notifications | 1217-1225 | Marks the app-UI screen as loaded, flushes queued notifications. |
| 210/211 | crypto | 1226-1263 | Decrypt/encrypt an "envelope" message (parent-account or backend payload obfuscation), round-tripped to the client. |
| 212 | expression | 1360-1373 | Same as 133 but with `parseAIMLexpression(..., true)` — the trailing `true` flag differs from 133's `false` (an unconfirmed behavioral variant, not traced further this pass). |
| 1050 | story | 1480-1482 | Logs a `StoryModel`'s name/path — appears to be a debug/log-only stub. |
| 1070 | classroom | 1483-1484 | Forwards to `homeController(str)`. |
| 1080 | classroom | 1491-1492 | Forwards to `classroomController(str, email, token)`. |
| 1090 | developer mode | 1493-1495 | `settings put global adb_enabled 1` — **enables ADB from an app-layer event with no further gating found**, notable for the jailbreak project (this app already runs as `android.uid.system`, so it can freely flip secure settings; see `docs/hardware/boot-hal-reference.md`/the bootagent's settings-shadowing work for the surrounding context). |
| 1110/1111 | dialog confirm | 1603-1632 | Confirms a pending dialog request, stopping any in-progress keyword/speech-reco first. |
| 1121 | video call | 585-586 | Forwards to `videoCallEvent(str)` (see `camera-vision.md` for the LiveKit/Agora call stack this likely feeds). |
| 1122 | audio setting | 1633-1643 | Sets an `invert` flag (mic/audio inversion — not traced further). |

That's every `if (i == N)` branch found in `callEvent`'s 549–2260ish line
range; the method continues past this (not fully transcribed — diminishing
returns past this point, mostly more of the same categories: notifications,
Mixpanel, WiFi).

## `GameControllerAIDL.getData(String)` — a small separate read-only channel

`IPC_AIDL.java:243-257`, string-matched (not numeric-coded):
`"GET_DND_MODE"` returns the current do-not-disturb pref as a string;
`"SET_DND_MODE_ON"`/`"SET_DND_MODE_OFF"` are logged but **the setter body is
empty** — confirmed dead/no-op (`if (str.startsWith("SET_DND_MODE_ON")) { Log.e(...); return ""; }`,
no field write). DND mode toggling from the client side does not currently
work through this call, if it ever did.

## Implementation

### (a) Bind directly to ServiceExam's live AIDL service (works — no permission/signature blocker found)

Since `MyService` is `exported=true` gated only by the auto-granted
`INTERNET` permission, and the four generated interfaces above are plain
Java (no `.aidl` compile step required), the simplest path is to copy
`UIEventAIDL.java`, `ExpressionEventAIDL.java`, `TouchEventAIDL.java`,
`UIDataAIDL.java`, `AnalyticsAIDL.java`, `GameControllerAIDL.java` from
`tools/serviceexam_jadx/sources/com/root/aidlFiles/` verbatim into a custom
app (same package name `com.root.aidlFiles`, so the `DESCRIPTOR` strings —
which are the actual Binder-level contract — line up), then:

```java
package com.root.aidlFiles;
// (paste the 6 files from tools/serviceexam_jadx/sources/com/root/aidlFiles/ unmodified)
```

```kotlin
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import com.root.aidlFiles.*
import org.json.JSONObject

class ServiceExamClient(private val ctx: Context) : ServiceConnection {
    private var uiEvent: UIEventAIDL? = null
    private var gameController: GameControllerAIDL? = null   // handed to us async, see myTouch.init()

    private val myExpr = object : ExpressionEventAIDL.Stub() {
        override fun expressionEvent(data: String?) { /* face/expression events FROM ServiceExam */ }
    }
    private val myUiData = object : UIDataAIDL.Stub() {
        override fun updateUIData(data: String?) { /* generic data-back-channel */ }
        override fun onCameraFrame(bmp: android.graphics.Bitmap?, i: Int) { /* face-cam frames */ }
    }
    private val myTouch = object : TouchEventAIDL.Stub() {
        override fun touchEvent(data: String?) { /* touch sensor events FROM ServiceExam */ }
        override fun init(analytics: AnalyticsAIDL?, gameCtrl: GameControllerAIDL?) {
            // ServiceExam hands us back its own GameControllerAIDL/AnalyticsAIDL
            // Stubs here -- THIS is the object GameEvent()/getData() are called on.
            gameController = gameCtrl
        }
    }

    fun connect() {
        val intent = Intent("my.service").apply { setPackage("com.example.root.serviceexam") }
        ctx.bindService(intent, this, Context.BIND_AUTO_CREATE)
    }

    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
        uiEvent = UIEventAIDL.Stub.asInterface(binder)
        uiEvent?.init(myExpr, myTouch, myUiData)   // triggers ServiceExam's async touchEventAIDL.init() callback above
    }
    override fun onServiceDisconnected(name: ComponentName?) { uiEvent = null; gameController = null }

    /** Send a numeric-code command, e.g. speakText(152, "Hello there") to invoke MikoPlus's "Speak". */
    fun sendCode(code: Int, payloadJson: String) {
        val envelope = JSONObject().put("data", JSONObject().put(code.toString(), payloadJson))
        gameController?.GameEvent(envelope.toString())   // == GameControllerAIDL.GameEvent(String), RemoteException on failure
    }

    /** Code 152/132/133/135/153/190/212 all expect a ServiceRequest-shaped inner payload: */
    fun speak(text: String) {
        val req = JSONObject().put("name", "").put("data", text).put("audioPath", "")
        sendCode(152, req.toString())
    }

    fun stopSpeak() = sendCode(132, "")

    fun startVoiceTraining() = sendCode(181, "START_VOICE_TRAINING")   // plain-string payload, not ServiceRequest
    fun stopVoiceTraining() = sendCode(181, "STOP_VOICE_TRAINING")
}
```

This reproduces exactly what MikoPlus does — `AIDLProcess.GameEvent(181,
"START_VOICE_TRAINING")`, as `feature-inventory.md` observed from the client
side, is precisely `sendCode(181, "START_VOICE_TRAINING")` above, minus a
thin wrapper class name. No root, no signature match, no special permission
beyond `INTERNET` (already required for basically every app) was needed to
reach this point in static analysis — **this has not been verified against
the live device** (out of scope per the task's constraints), so treat "no
blocker found" as CONFIRMED-in-code, not CONFIRMED-on-device.

### (b) Fallback: reimplement ServiceExam's side

Not needed if (a) works, but if a future OS/app update adds a permission
check to `MyService`, the fallback is to reimplement the dispatcher directly
against `libmiko_drivers.so`/`libserial_port.so` per `motors-wheels.md` and
`buttons-power.md`'s Implementation sections — those libraries have no
AIDL/Binder gate at all, only standard Linux file permissions on the UART
device node.

## Open questions

1. **Not verified on the live device**: does `bindService()` from an
   unprivileged third-party app actually succeed against `MyService` as the
   manifest/code above implies? Android's manifest permission enforcement
   for bound services is normally solid, but this needs a live test (out of
   scope per the task's read-only/no-device-interaction constraint) before
   relying on it for the custom launcher.
2. `WifiService` (`action="wifi_service"`, same `INTERNET`-only gating) and
   the two overlay services (`OverlayScreenServiceLib`/
   `SystemOverlayServiceLib`, exported with **no** permission attribute at
   all) were not traced in this pass — worth checking whether they expose
   further reusable surface or a way to inject UI directly into the kiosk's
   system-overlay layer.
3. `IMyAidlInterface`/`mBinder11` and `MessengerService` are fully
   implemented but provably unreachable (never returned from any `onBind()`
   found in the decompile) — confirm there isn't a second, un-analyzed
   `Service` subclass (e.g. in a dex file this pass under-covered) that does
   bind them; if truly dead, they're a red herring for anyone reading
   ServiceExam's source cold.
4. Code 212 vs 133 (`parseAIMLexpression(..., true)` vs `(..., false)`) —
   the trailing boolean's effect wasn't traced into `parseAIMLexpression`
   itself; worth resolving if expression playback behaves unexpectedly when
   porting.
5. `callEvent`'s full extent past ~line 2260 wasn't transcribed in this pass
   (diminishing returns — remaining codes observed to repeat the same
   categories: WiFi, Mixpanel, notifications). If a specific unlisted code
   shows up in captured live traffic, search
   `tools/serviceexam_jadx/sources/com/example/root/serviceexam/ServiceClientInterface.java`
   directly for `if (i ==`.
6. Code 1090 (`settings put global adb_enabled 1`, no gating found) and
   `MyService.startADBserver()`/`grantPerm()` (run `su`, set
   `service.adb.tcp.port 5555`, restart `adbd`, grant location permissions
   to a hardcoded third-party package
   `com.example.joelwasserman.androidbleconnectexample`) are unrelated to
   the AIDL dispatch proper but were surfaced while reading `MyService.java`
   (`MyService.java:74-127`) — flagged for whoever is tracking the device's
   overall attack surface / ADB-enablement story, since they're reachable
   without physical access if code 1090 is genuinely unguarded.
