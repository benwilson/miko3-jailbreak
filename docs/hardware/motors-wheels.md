# Motors / wheels — locomotion hardware control

## WHEELS CONFIRMED WORKING (2026-09-14, session 2 continued): operator-witnessed movement, corroborated by live encoder telemetry — but not yet reproduced from an external AIDL call

**Resolves the headline open question of this entire document.** Shortly
after the "operator watched, zero movement" result immediately below, with
no test running from this project's side (`spike-drive-test` was not in the
foreground, no `GameEvent`/AIDL call was made), the device owner reported:
first "It just moved... It turned to its left," then minutes later "It just
spun around in a circle and then went backwards."

A live, unfiltered logcat capture (armed proactively after the first report,
same method as the rest of this section — a full capture to a file, not a
filtered live tail, to survive `SocialInteraction`'s buffer-flooding
telemetry) caught the second event directly:

- Real `"VEL1="` frames firing (`E/EE: completed VEL1`), each preceded by an
  `AndroidUnityInterface` sequence loading a **named system expression** —
  an image (`"frame":"eager.json"`) paired with a motion clip (`"string is
  MOTION1" / "found motion" / "motion play"`) — i.e. exactly the
  `ExpressionMsg.packData1()` → `map.put("MOTION1", frame)` mechanism this
  doc's byte-level analysis (below) predicted, firing for real.
- **No `NEWAIDL`/`GameEvent` log line anywhere near either firing.** This
  did not come through the AIDL surface `spike-drive-test` or
  `com.miko3.mode.remotecontrol` use — it's the robot's own internal
  idle/reactive behavior engine invoking the identical low-level pipeline
  autonomously.
- **The `Left=`/`Right=` encoder telemetry — frozen at one single value
  (`Left=-000000637,Right=-000000561`) across this entire session's 20+
  prior drive attempts — changed continuously and smoothly** through dozens
  of distinct values during this window (e.g. `Right` sweeping from
  `-000002587` through a smooth progression to `-000002122`), independently
  corroborating the operator's direct observation with real odometry, not
  just a visual impression.

### What this settles

**The hardware is unambiguously real and functional**: wheels, motor
driver, and the peripheral MCU's motion firmware all genuinely work on this
unit. The "does this Miko 3 have working drive wheels" question — open
since the original static-analysis pass, and still technically open after
this session's own AIDL testing got a positive MCU ACK with no observed
effect — is now closed: **yes**, confirmed by direct observation and
independently by hardware telemetry.

### What's still open

**Why this project's own externally-triggered AIDL command (`GameEvent`
code 135, fully corrected per the section below — verified reaching
`loadExpressionString`, packing a real `VEL1=` frame, and getting a
positive `Motion/VEL1 Completion:CPL=1` ACK from the same MCU) produces no
observed physical effect, while the robot's own internal trigger for the
*identical* low-level pipeline does.** Both paths reach the same
`sensorModule.writeUART()` call and get acknowledged by the MCU; only the
external path fails to actually move anything. Leading hypothesis, not yet
tested: a state-machine gate (`MikoStateMachine`'s `Active`/`Sleep`/
`InActive`/`Update` states, referenced elsewhere in this codebase) that the
internal reactive-behavior engine satisfies as part of its own normal
invocation context, but that an external AIDL caller — which never touches
`MikoStateMachine` at all — does not. Worth checking `si.statemachine`'s
current state before/during an external attempt, and whether forcing it
into whatever state the autonomous path runs under (if reachable via AIDL)
changes the result. Not investigated this pass.

---

## DEFINITIVE RESULT (2026-09-14, session 2 continued): full software round-trip succeeds, MCU acknowledges, zero physical movement, operator-witnessed

**Every software-layer bug in the chain is now fixed and verified; the command reaches the peripheral motor-board and gets a positive completion ACK; the operator watched and confirmed no physical movement occurred. This is the strongest evidence this session can produce, short of opening the unit.**

### Standalone diagnostic app (`spike-drive-test/`)

Rather than wait on the other session's fix to `RobotControlClient.java` (active
WIP code not ours to patch), built a minimal standalone Android app
(`spike-drive-test/`, `com.miko3.spiketest`) that binds directly to
ServiceExam's `MyService` and sends a hand-built `GameEvent` payload — same
AIDL handshake as every other client on this device (`UIEventAIDL.init()` →
`TouchEventAIDL.init()` callback hands back `GameControllerAIDL`), but full
control over the exact envelope bytes. Built with a from-scratch pipeline
mirroring `scripts/build-bootagent.py` (`javac -> d8 -> aapt2 link ->
zipalign -> apksigner`, `spike-drive-test/build.py`), reusing the
already-provisioned Android SDK. Source for the six `com.root.aidlFiles.*`
AIDL stub classes was copied verbatim from the already-decompiled
`com.miko3.mode.remotecontrol` (same package, same interfaces, across every
client on this device — ServiceExam's AIDL surface is not project-specific).

### Three real bugs found and fixed in sequence, each confirmed via ServiceExam's own live logcat

Iterated by capturing ServiceExam's full logcat (unfiltered, to a file, via
`adb logcat -v time > file &`) around each attempt — the ring buffer's
eviction by `SocialInteraction`'s own ~10Hz telemetry chatter makes a
filtered/live tail unreliable for anything slower than a couple seconds; a
full capture read back afterward is the reliable method.

1. **Envelope encoding** (this project's `RobotControlClient.java`, not
   fixed there — reproduced correctly in the standalone app instead):
   `ServiceData.data` is `HashMap<Integer, String>` — the code-135 entry's
   value must be the `ServiceRequest` **JSON-encoded as a string**, not
   nested as a raw object. Sending it as a raw object threw
   `JsonSyntaxException: Expected a string but was BEGIN_OBJECT` inside
   ServiceExam and silently fell through to an unrelated "speak tts" default
   path — never reaching real dispatch. See the "ROOT CAUSE FOUND" analysis
   below (kept, still accurate) for the full trace.
2. **Wrong `ServiceRequest` field.** Code 135's actual handler
   (`ServiceClientInterface.12`, `ServiceClientInterface.java:1391`) does
   **not** read `ServiceRequest.data` for the expression payload — it calls
   `loadExpressionString(serviceRequest.getPath())`. `data` is read
   separately and, if non-empty, becomes **spoken TTS text**
   (`SocialInteraction_SpeechChat.create_TTS(serviceRequest.getData())`),
   layered onto `expressionMsgLoadExpressionString.tx`. Sending the motion
   payload via `data` (matching this project's client, and matching this
   doc's own earlier "Route (a)" Kotlin example — **both wrong** in the same
   way) leaves `path` null, and `loadExpressionString`'s first line,
   `Log.e("yy", str)`, throws `NullPointerException: println needs a
   message` — `android.util.Log.e(tag, msg)` throws exactly this if `msg`
   is null. `ServiceRequest.data` must still be present as `""` (not
   omitted/null) — it's read unconditionally via `.length()` with no null
   check, a second latent NPE if skipped.
3. **Wrong payload shape for the direct path.** `loadExpressionString(String
   str)` is `Log.e("yy", str); return gson.fromJson(str, ExpressionMsg.class);`
   — it Gson-parses `str` **directly as JSON**. The `<block>\n<expression>{json}
   </expression>\n</block>` XML wrapper this doc's earlier examples used
   (matching `startDOAThread()`'s confirmed-live call) is only meaningful to
   the **other** method, `parseAIMLexpression(String xml, ...)`, which
   parses the XML, extracts each `<expression>` element's text content, and
   calls `loadExpressionString` on *that* — unwrapped. Code 135 calls
   `loadExpressionString` **directly**, so `ServiceRequest.path` must be the
   raw `{"tx":...,"mx":...,"ix":...,"rx":...,"id":N}` JSON with no XML
   wrapper at all. Sending the XML-wrapped form here throws
   `JsonSyntaxException: Expected BEGIN_OBJECT but was STRING at line 1
   column 1 path $` — Gson sees text starting with `<`, not `{`.

**Net implication for `docs/hardware/aidl-dispatch.md`'s and this doc's
earlier "Route (a)" examples**: those were reverse-engineered from
`startDOAThread()`, which calls `parseAIMLexpression()` (the XML-wrapped
path) — a **different** entry point than a third-party AIDL client actually
reaches for code 135, which goes through `ServiceClientInterface.12`'s
direct `loadExpressionString` call instead. The two entry points expect
different payload shapes for what is otherwise the same underlying
`ExpressionMsg`/`MotionMsg` pipeline. Anyone building a client should use
the shape confirmed working below, not the XML-wrapped form documented
elsewhere in this file for the (never independently confirmed reachable
from outside ServiceExam) `parseAIMLexpression` path.

### Confirmed-working payload (verified via live ServiceExam logcat, zero exceptions, full pipeline completion)

```json
{"data":{"135":"{\"name\":\"\",\"path\":\"{\\\"tx\\\":{\\\"type\\\":0,\\\"size\\\":0,\\\"loop\\\":0,\\\"seqCount\\\":0,\\\"seq\\\":[]},\\\"ax\\\":{\\\"type\\\":0,\\\"size\\\":0,\\\"loop\\\":0,\\\"seqCount\\\":0,\\\"seq\\\":[]},\\\"mx\\\":{\\\"type\\\":4,\\\"size\\\":0,\\\"motion_type\\\":4,\\\"loop\\\":1,\\\"kp\\\":0,\\\"ki\\\":0,\\\"kd\\\":0,\\\"pidcontrol\\\":0,\\\"seqCount\\\":1,\\\"seq\\\":[{\\\"linear\\\":0,\\\"angular\\\":20,\\\"time\\\":10,\\\"type\\\":1,\\\"id\\\":0}]},\\\"ix\\\":{\\\"type\\\":0,\\\"size\\\":0,\\\"imagetype\\\":0,\\\"loop\\\":0,\\\"seqCount\\\":0},\\\"rx\\\":{\\\"type\\\":0,\\\"size\\\":0,\\\"loop\\\":0,\\\"seqCount\\\":0,\\\"seq\\\":[]},\\\"id\\\":420}\",\"data\":\"\",\"audioPath\":\"\"}"}}
```
(i.e. `{"data":{"135": <ServiceRequest as a JSON string, with .path = the raw expression JSON, .data = "">}}`, sent to a live `GameControllerAIDL` obtained via the standard `UIEventAIDL.init()`/`TouchEventAIDL.init()` handshake — see `spike-drive-test/src/com/miko3/spiketest/MainActivity.java` for the working reference implementation.)

### Confirmed reaching real hardware (CONFIRMED, live logcat, not inferred)

With the corrected payload, ServiceExam's own log shows **zero exceptions**
and the full pipeline completing, ending in a positive round-trip
acknowledgment from the peripheral MCU itself:
```
V/SocialInteraction: PI3 callback string is VEL1=<binary padded frame>
E/EE: completed VEL1
E/SocialInteraction: rrr something new xx:VEL1=<binary padded frame>
D/ack: Motion/VEL1 Completion:CPL=1
```
This is the deepest point any software-only test can confirm: the exact
`"VEL1="`-tagged frame this doc's byte-level analysis (below) predicted,
written via `sensorModule.writeUART()`, received and **acknowledged
complete** by the physical peripheral board over the UART. Six repeated
attempts (small-magnitude rotate, `angular=20`), all completing cleanly with
`CPL=1`, no `ERROR_UART`, no exception.

### Operator-witnessed result: no physical movement (CONFIRMED, negative)

The device owner watched the physical unit for the entire attempt window
and confirmed: **no movement, no effect.** ServiceExam's own live
wheel-encoder-shaped telemetry (`Left=`/`Right=` in the `"PI3 callback
string"` heartbeat) also never changed — one single frozen value
(`Left=-000000637,Right=-000000561`) across this entire session's ~20+ total
drive attempts, including this final, fully-successful-at-the-software-layer
one.

### Assessment, updated

Every software bug in the app→AIDL→ServiceExam→UART chain is now
eliminated and the command chain is proven to reach and be acknowledged by
the physical peripheral board. The "does this unit have working drive
wheels" question from the original static-analysis pass (below) now has a
much better-supported answer: **the motion command pipeline is fully
functional end-to-end in software and firmware-communication terms, but
produces no observed physical effect on this specific unit.** That points
at something downstream of the UART link and the MCU's own acknowledgment
logic — a motor/driver stage that isn't actually populated, wired, or
functional on this board, a safety interlock this pass didn't identify
(e.g. a stand/orientation/lift sensor gating actual motor engagement even
though the command itself is accepted and ACK'd), or a genuine hardware
fault specific to this individual unit — not a protocol, software, or
"wrong app" problem. Distinguishing between those would need physical
inspection of the board (out of scope for this pass) or finding and
triggering whatever safety interlock might exist, if one does.

## ROOT CAUSE FOUND (2026-09-14, session 2 continued): malformed AIDL envelope in this project's own client code — not a hardware limitation

**Supersedes the `bluetooth_flag` hypothesis in the section directly below (kept for the record, but ruled out) and explains the full negative spike-test battery.**

Captured ServiceExam's own logcat (filtered to its PID) live during a real
`/drive` attempt from `com.miko3.mode.remotecontrol`. Two findings, in order:

1. **`RobotConnectCallback`'s peripheral-board handshake succeeds** (`connection successful112 0`, the `i==0` branch) on this boot — `bluetooth_flag` stays at its default `true`. The earlier hypothesis (gate closed from a failed handshake) is **ruled out**.
2. **The AIDL envelope this project's client sends is malformed**, and ServiceExam's own log shows the exact failure:
   ```
   E/NEWAIDL: GameEvent = {"data":{"135":{"name":"","data":"<block>...</block>","audioPath":""}}}
   E/OMKar: In Send String{"data":{"135":{...}}}
   E/IPC_AIDL: calling speak tts event
   E/IPC_AIDL: The data from null
   W/System.err: com.google.gson.JsonSyntaxException: java.lang.IllegalStateException: Expected a string but was BEGIN_OBJECT at line 1 column 17 path $.data.
       at com.example.root.serviceexam.IPC_AIDL$4$1.run(IPC_AIDL.java:381)
   ```
   Traced the exact deserialization contract (`tools/serviceexam_jadx/sources/com/example/root/serviceexam/IPC_AIDL.java:193-215`,
   `.../com/emotix/arya/required/ServiceData.java`):
   ```java
   // IPC_AIDL.java, GameEvent(String str) handler:
   ServiceData serviceData = gson.fromJson(str, ServiceData.class);
   HashMap<Integer, String> data = serviceData.getData();   // note: Map<Integer, STRING>
   for (int code : data.keySet()) {
       ServiceClientInterface.callEvent(code, data.get(code));  // callEvent does its own
   }                                                             // gson.fromJson(str, ServiceRequest.class)
   // ServiceData.java: `HashMap<Integer, String> data;` — the map VALUE must be a
   // JSON-encoded STRING (a serialized ServiceRequest), not a nested raw object.
   ```
   This project's `RobotControlClient.drive()` (`shared/src/com/miko3/shared/RobotControlClient.java`)
   currently builds `{"data":{"135": <ServiceRequest object> }}` — nesting the
   `ServiceRequest` directly. It needs to be `{"data":{"135": "<ServiceRequest serialized to a JSON string>" }}`
   — double-encoded, matching what `ServiceData`/`callEvent` actually deserialize.
   Because of this, **every drive command sent by this app since it was
   written has failed to parse**, silently: no exception crosses the AIDL
   boundary (it's caught and only `e.printStackTrace()`'d inside ServiceExam's
   own process), the client sees a clean `HTTP 200`, and the malformed request
   falls through to an unrelated default path (`"calling speak tts event"`)
   instead of ever reaching `ServiceClientInterface.callEvent`'s real code-135
   dispatch.

**Corroborating telemetry, not just a log line**: ServiceExam's own live
sensor stream (`SocialInteraction`'s `"PI3 callback string"`, ~10Hz,
containing `POWER=`/`IMUAC=`/`IMUGY=`/`GLPOS=`/`TOFIR=`/`Left=`/`Right=`
fields — `Left=`/`Right=` read as wheel-encoder counts) was captured across
the entire multi-attempt spike-test window this session. **The `Left=`/`Right=`
values never changed — not once, across ~6 separate drive attempts spanning
small/large, forward/back, rotate, and combined arc.** That's independent,
device-side evidence (not just "no error was thrown") that the wheels never
actually turned during any of this session's attempts — fully consistent
with the request never reaching real motion dispatch.

**This is a bug in this project's own in-development client code, not a
vendor firmware or hardware limitation, and not the earlier `bluetooth_flag`
theory.** The fix is a specific, small change to how `RobotControlClient`
serializes its AIDL payload (see above) — not investigated further this
pass since it's active WIP code under a collaborator's edit, not something
to patch unilaterally. Once fixed, re-run the same spike-test battery
(`ROT-1`/`LIN-1`/`LIN-2`/`LIN-3`/`ARC-1` shapes below) with a human witness
and/or the same live-logcat-plus-telemetry method to get a real answer to
"does this unit have working drive wheels" — that question is **still
open**, now for a different reason: no attempt has yet sent a
correctly-formed command.

## Live-device spike test (2026-09-14, session 2): AIDL commands accepted, zero observed physical movement — likely cause found

**Provenance correction first — this matters for interpreting everything below.**
`com.miko3.launcher` and `com.miko3.mode.remotecontrol`, pulled live off the
device this session, are **not stock Miko software**. They're this project's
own in-development custom launcher + first "mode" app — source lives in this
repo (`launcher/`, `mode-remote-control/`, `shared/`), plan doc at
`docs/plans/2026-09-14-1035-feat-launcher-mode-architecture-plan.md`
("Launcher Mode Architecture"). An earlier pass this session wrongly
described `com.miko3.mode.remotecontrol` as "a shipped Miko feature" — that
was wrong; the naming similarity to the vendor's `com.miko.*` packages
(`com.miko.launcher_app`, `com.miko.mikoplus`) is coincidental/thematic, not
evidence of vendor origin. The underlying motor-command **protocol** it
drives (`GameControllerAIDL.GameEvent()`, code 135, the `mx` JSON shape) is
still the real, vendor-original `com.example.root.serviceexam` channel
documented elsewhere in this file — only the calling app's provenance was
misattributed.

### `DriveLeaseService` (`com.miko3.launcher`) — confirmed from live source

Server-side arbitration coordinator for the drive channel, not previously
documented: lease TTL 2250ms, checked every 500ms; holder death tracked via
`IBinder.linkToDeath` so a crashed client auto-releases. On any release path
(clean `release()`, TTL expiry, or binder death) the service itself
independently calls `RobotControlClient.stop()` — a second, server-side
safety net layered on top of the drive-client's own 750ms local command
watchdog. Two independent fail-safes, not one.

### `game_robot_maker.apk` ruled out (closes an old open question, negatively)

Pulled and decompiled (`es.monkimun.game_robot_maker`) — this is an
unrelated kids' game from the same Spanish studio that made
`bathroom_game`/`game_pastry_maker`/etc., also bundled on this device. Zero
references to `MotionMsg`/`HeadMsg`/`GameControllerAIDL`/any hardware class.
The "highest-value next step" flagged in the section below was a dead end;
don't re-open it in a future pass.

### Live spike test battery: command accepted, zero physical movement observed (human-witnessed)

Six attempts sent via `com.miko3.mode.remotecontrol`'s `/drive` endpoint
(which reaches ServiceExam through the exact AIDL chain documented below),
directly witnessed by the device's owner watching the physical unit — not
inferred from a camera-frame diff, which an earlier attempt this session
over-trusted (a rotation command's camera view shifted between two frames;
that was wrongly called "confirmed" movement before a human witness was
available — retract that claim, see below):

| ID | Params | Result |
|---|---|---|
| ROT-1 | `angular=20`, ~1.5s | No movement observed |
| LIN-1b | `linear=20`, ~1.5s | No movement observed |
| LIN-2 | `linear=100`, ~2s (large magnitude) | No movement observed |
| LIN-3 | `linear=-100`, ~2s (backward, large magnitude) | No movement observed |
| ARC-1 | `linear=60,angular=30` combined | No movement observed |

Every command returned `HTTP 200`/`"ok"` — the full app→AIDL→ServiceExam
chain completes without error every time. The robot was free-standing (not
docked/cradled) for all of this, ruling out the "blocked by a charging dock"
explanation. **This is a clean, controlled negative result across the full
parameter range tried (small/large, forward/back, rotate, combined) — not
one failed attempt.**

### Leading hypothesis: `bluetooth_flag` gate silently no-ops the hardware write

`SocialInteraction_SpeechChat.SendData(byte[], boolean)`
(`tools/serviceexam_jadx/sources/com/common_source/emotix/interaction/interaction/SocialInteraction_SpeechChat.java:2465-2509`)
is the terminal call of the confirmed-live motion chain (see "The real
motion channel" section below) — `sensorModule.writeUART(bArr)` only runs
**inside** `if (this.uinterface.bluetooth_flag) { ... }`. That field defaults
`true` (`AndroidUnityInterface.java:182`), but `RobotConnectCallback(int)`
(`SocialInteraction_SpeechChat.java:2871-2913`) sets it permanently `false`
for the remainder of that boot if the peripheral motor-board's own
connection handshake reports failure more than twice
(`"ROBOT_CONNECTION_FAILURE3"` / `Log.e(TAG, "connection error >2")` at line
2912). If that's what happened at this boot, every subsequent `writeUART()`
call — motion, head, RGB, everything — becomes a **silent no-op**: no
exception surfaces to the AIDL caller, no error in the HTTP response,
nothing. This exactly matches the observed pattern (clean `HTTP 200`s, zero
physical effect).

**Despite the name, this is not actually about Bluetooth/networking, and not
about how the operator is connected.** `com.miko.app_bluetooth.*`/
`TransportLayer` (see "Transport" section below) is a generic transport
abstraction that backs onto a **wired UART** on this hardware (`/dev/ttyS2`)
— the class names are inherited from an earlier hardware generation that
apparently used real Bluetooth for this link. `bluetooth_flag` gates
ServiceExam's own **internal** connection from the main SoC to the separate
motor-controller peripheral board, established automatically at boot,
regardless of whether the app issuing a drive command is local, remote, the
stock vendor app, or this project's own client. Being "connected locally"
does not route around this gate — it was never a networking gate.

**Not yet confirmed** — this is the leading hypothesis, not a settled
finding. The actual boot-time `RobotConnectCallback`/`"connection error"`
log lines from this session's reboot were already evicted from the logcat
ring buffer by `SECURITY_MONITOR`'s own noisy `ps -ef` dump (fires every 2s)
before they could be captured. Logcat has been cleared and a live filtered
watch armed for the next reboot (whenever it happens) to catch
`RobotConnectCallback`'s actual argument and settle this definitively. If
confirmed, the next question is *why* the handshake fails on this
particular unit/state — a wiring/pairing issue specific to this hardware
revision, a side effect of the rooted/modified boot state, or something
else — not yet investigated.

---

## CORRECTION / major update (2026-09-14, ServiceExam AIDL-dispatch pass)

**The "no motor/wheel code exists" headline finding below is superseded for
motion specifically.** A later pass (this update), working from a fresh,
independent jadx decompile of ServiceExam (`tools/serviceexam_jadx/`,
`com.example.root.serviceexam` v92, same version as this doc's original
ServiceExam source) while tracing the AIDL dispatch layer for
`docs/hardware/aidl-dispatch.md`, found and fully traced a **second,
separate, live serial-write path** that the original four-app sweep below
did not surface — it does not go through `commandInterface.MOTION_MESSAGE`/
`com.miko.app_bluetooth.*` (the protocol this doc's original pass exhaustively
searched and correctly found dead) at all. It is a **different wire protocol
riding a different native bridge** (`emotix.com.drivers.SensorModule` /
`libmiko_drivers.so`, not `android.serialport.SerialPort` /
`libserial_port.so`), reachable from real, non-dead expression-playback code.
See "**The real motion channel**" section below for the fully-traced,
file:line-cited chain from a JSON expression asset down to the
`writeUART()` JNI call. The rest of this document (Arya/`TransportLayer`
protocol, UART device-node resolution, baud rate, frame size for *that*
protocol) remains accurate for what it covers — it's just not the channel
that moves the wheels.

Scope: everything in the app layer that could drive physical motors.

**Source-attribution correction (important):** `tools/extracted/` — the
directory holding `libmiko_drivers.so`/`libserial_port.so` and the
`classes.dex` this doc's findings come from — is **not** `tools/base.apk`.
Verified directly (apktool decode + exact dex-hash match against both
candidates): `tools/extracted/` is the unzipped contents of
**`tools/launcher_alpha_V2-v4.2.apk`, package `com.miko.launcher_app`**
(versionCode 4 / versionName 4.2). It declares `sharedUserId="android.uid.system"`
and is the device's actual `HOME`/`LAUNCHER` activity (`MainActivity`,
`category.HOME`+`category.LAUNCHER`) — i.e. this is the home-screen launcher
app, a privileged system app distinct from `com.miko.mikoplus` (MikoPlus,
`tools/base.apk`, versionCode 69 / versionName 7.3.0) and from
`com.example.root.serviceexam` (ServiceExam, `tools/APPS-extracted/file1.ia`,
v92). MikoPlus's own `classes.dex` (`tools/base.apk` — identical dex content
to `tools/extracted-system/`, confirmed by hash) **contains zero
`emotix.com.drivers`/Arya-protocol classes at all** — consistent with
`feature-inventory.md`'s finding that MikoPlus is a thin AIDL shell with no
hardware code of its own. `com.miko.launcher_app`'s manifest declares **no
`<service>`** (only a provider and a Fetch-library receiver), so it is not
itself an AIDL endpoint MikoPlus binds to — it independently links the same
hardware SDK (`libmiko_drivers.so`, `libserial_port.so`, the
`com.miko.app_bluetooth.*`/Arya-protocol classes) that ServiceExam also
links, presumably because the launcher needs direct sensor/hardware access
for its own UI (e.g. touch-panel light, volume overlay triggers) independent
of ServiceExam.

Sources actually used below: `tools/extracted/` (`com.miko.launcher_app`,
decompiled by running jadx on its `classes.dex` directly — running jadx on
the launcher **APK** produces the same result, no merge issue was actually
found here; see Implementation §1) and `tools/APPS-extracted/file1.ia`
(`com.example.root.serviceexam` v92, 5-dex multidex — has its own,
independent copy of the same `emotix.com.drivers`/Arya-protocol classes).
`tools/base.apk`/`tools/extracted-system/` (MikoPlus) were checked and
confirmed to contain **none** of this code. Cross-reference:
`docs/hardware/boot-hal-reference.md` (firmware-image agent, device-node/init
findings) and `docs/hardware/feature-inventory.md` (sibling agent, full
MikoPlus feature map — explains the MikoPlus/ServiceExam split this doc
found is also mirrored by the launcher app).

## Headline finding (CONFIRMED, negative result)

**No motor/wheel/locomotion code exists in any of the four decompiled apps**
(`com.miko.launcher_app`, `com.example.root.serviceexam`, and
`com.miko.mikoplus` in both its `tools/base.apk`/`tools/extracted-system/`
copies). This was checked four independent ways, all negative:

1. Native JNI exports: `nm -D` on both copies of `libmiko_drivers.so` (the
   157880-byte one bundled in `com.miko.launcher_app`, the 292976-byte one
   bundled in ServiceExam — different builds, identical symbol set) lists 118/193
   functions. **All of them belong to `emotix.com.drivers.SensorModule`,
   `mikoI2C`, or C++ runtime internals** (touch, IR, RGB LED, ambient-light
   sensor, I2C, SPI). Zero motor/PWM/GPIO-drive symbols.
2. `libserial_port.so` (both copies, 9904 bytes, identical) exports exactly
   two JNI functions: `Java_android_serialport_SerialPort_open` and `_close`
   — the generic open-source "android-serialport-api" wrapper, not
   motor-specific.
3. Class-name/string search: `grep`-ing every `.dex`'s string pool (all four
   apps, 11 dex files total) for `Motor`, `Wheel`, `Locomot`, `Drive`, `Chassis`,
   `Servo`, `Gesture` (as a movement concept), `Kinematic`, `Odometry`,
   `encoder` as class-name fragments: no hits beyond false positives
   (`HashedWheelTimer` from Netty, `fa_motorcycle` font-icon resource ID,
   `"Hot Wheels"` — a Miko skill/show title string).
4. Empty stub classes: `com.miko.robot.sensor.{Sensor,PositionSensor,RangeSensor}`
   exist in ServiceExam's dex (classes3.dex) but are **completely empty**
   (`public class Sensor {}`, no fields, no methods) — dead/placeholder code,
   never implemented for this hardware generation.

## The one real lead: the "Arya" serial protocol (CONFIRMED, but never observed driving motors)

There IS a fully-implemented, general-purpose framed serial protocol to a
peripheral MCU, and its command-type table **has movement message IDs** —
but no code anywhere calls them with real data. Full detail below; treat the
framing/transport as solid ground truth and the motor-specific payload as an
open gap.

### Transport: `com.miko.app_bluetooth.*` (present in both `com.miko.launcher_app` and ServiceExam)

Despite the package name, this is a generic transport abstraction
(`DeviceLayer`/`TransportLayer`/`ApplicationLayer`) that can back onto
Bluetooth SPP **or** a wired UART — the wired path is what matters here.

- `com.miko.app_bluetooth.device.serialDevice` (`getDevices()`) advertises one
  device named `"UART2"`, default path **`/dev/ttyMT1`**.
- `com.miko.app_bluetooth.comm.ApplicationLayer.baudRate = 2000000` (2 Mbps) —
  **CONFIRMED constant**, also echoed in a log string:
  `"the uart baud rate is 2000000"`.
- `serialDevice.getSerialPort(path)` opens the node via
  `android.serialport.SerialPort` (i.e. `libserial_port.so`), 8N1 implied
  (constructor is `new SerialPort(File, baudrate, 0)` — the `0` is the flags
  arg to the C `open()`, not parity/stopbits, which the native side hardcodes;
  not further decompiled).

### Which device node — device-specific (CONFIRMED, resolves boot-hal-reference.md open question #1)

`SensorModule.init()` (the JNI-backed sensor driver, `emotix.com.drivers`)
picks the UART path from a `useJoyARPort` flag:
```java
this.nativeObject = createUART(500, this.useJoyARPort ? "/dev/ttyS2" : "/dev/ttyMT1");
```
`SocialInteraction_SpeechChat` (ServiceExam) computes that flag from the bot's
own serial number:
```java
strSubstring = botName.substring(10, 19);                 // e.g. "M3Q0636CB"
if (!strSubstring.startsWith("M3J") || strSubstring.startsWith("M3Q")) {
    isJoyarPort = true;
}
sensorModule = SensorModule.getInstance(isJoyarPort);
```
Our own device's serial (`docs/device-intel.md`: `MIKO3250XXM3Q0636CB`) has
substring `"M3Q0636CB"` — starts with `"M3Q"`, not `"M3J"` — so
**`isJoyarPort = true` for this specific unit, meaning `SensorModule` opens
`/dev/ttyS2`, not `/dev/ttyMT1`.** Only `"M3J..."`-serial units use
`/dev/ttyMT1`; every other serial pattern (including `M3Q`) uses `/dev/ttyS2`.

This directly confirms `boot-hal-reference.md`'s hypothesis: of the two
OEM-loosened (`0666`) UART nodes it found in `vendor/etc/init/hw/init.mt8168.rc`,
**`/dev/ttyS2` is the one the app layer actually opens** (for M3Q-variant
units like ours). `/dev/ttyS1` remains unexplained — not referenced by any
string or class in any of the four apps analyzed here.

**Important scope note (added in the 2026-09-14 update above): this
`/dev/ttyS2` resolution is confirmed correct, but it's resolved by
`emotix.com.drivers.SensorModule.getInstance()`/`.init()` — the exact same
`SensorModule` class that the real motion channel (next section) writes
motion frames through via `sensorModule.writeUART()`. The Arya/`TransportLayer`
protocol described in the rest of *this* section (`com.miko.app_bluetooth.*`,
0x3C/AckMessage/0x3E framing) is a textually separate class stack that opens
its own `android.serialport.SerialPort` (`libserial_port.so`) — it was not
established in this pass whether that opens the *same* `/dev/ttyS2` node a
second time, a second node, or is effectively a legacy/parallel path. Treat
the `/dev/ttyS2` node identity as solid; treat "the Arya protocol below is
how you'd send motion" as superseded by the next section.**

## The real motion channel (CONFIRMED, live-firing code — supersedes the "dead code" reading below for motion)

Found while tracing ServiceExam's AIDL dispatch layer for
`docs/hardware/aidl-dispatch.md`, from `tools/serviceexam_jadx/` (fresh
independent decompile of ServiceExam v92, same version this doc's original
pass analyzed). This is a **second, textually distinct serial-write path**
from the Arya/`TransportLayer`/`commandInterface.MOTION_MESSAGE` protocol
described above and below — it does not construct a `commandInterface`
constant anywhere, uses a different native bridge
(`emotix.com.drivers.SensorModule`/`libmiko_drivers.so`, not
`android.serialport.SerialPort`/`libserial_port.so`), and — critically — **is
reached by real, actually-executing code**, not just declared-but-uncalled
classes.

### The smoking gun: voice-direction auto-turn (CONFIRMED, live)

`SocialInteraction_SpeechChat.startDOAThread()`
(`tools/serviceexam_jadx/sources/com/common_source/emotix/interaction/interaction/SocialInteraction_SpeechChat.java:5323-5420`,
with a near-identical earlier copy as the `doaThread` field initializer at
lines 375-460 of the same file) runs a background loop that:

1. Polls `this.mic.getDSPRawDOA()` — a **native** JNI method
   (`com.example.conexantapi.ConexantDSP.getDSPRawDOA()`,
   `tools/serviceexam_jadx/sources/com/example/conexantapi/ConexantDSP.java:28`,
   `System.loadLibrary("conexant_dsp_lib")` at line 43) returning the mic
   array's direction-of-arrival angle in degrees — i.e. which way the person
   currently speaking is standing relative to the robot.
2. Converts that angle to a clamped ±20 "angular" value
   (`i3 = doaAngle * f < 0.0f ? -20 : 20`, line 5361) once the angle has
   moved more than 2° since the last sample.
3. **Builds a literal expression-XML string containing a motion command and
   feeds it straight into the same `parseAIMLexpression()` call the AIDL
   dispatcher uses for codes 133/134/135/212** (see `aidl-dispatch.md`).
   Verbatim, `SocialInteraction_SpeechChat.java:5364-5368`:
   ```java
   StringBuilder sb = new StringBuilder();
   sb.append("<block>\n<expression>{\"tx\":{\"type\":0,\"size\":0,\"loop\":0,\"seqCount\":0,\"seq\":[]},\"ax\":{\"type\":0,\"size\":0,\"loop\":0,\"seqCount\":0,\"seq\":[]},\"mx\":{\"type\":4,\"size\":0,\"motion_type\":4,\"loop\":1,\"kp\":0,\"ki\":0,\"kd\":0,\"pidcontrol\":0,\"seqCount\":1,\"seq\":[{\"linear\":0,\"angular\":");
   sb.append(i3);                 // -20 or +20
   sb.append(",\"time\":");
   sb.append(10);
   sb.append(",\"type\":1,\"id\":0}]},\"ix\":{\"type\":0,\"size\":0,\"imagetype\":0,\"loop\":0,\"seqCount\":0},\"rx\":{\"type\":0,\"size\":0,\"loop\":0,\"seqCount\":0,\"seq\":[]},\"id\":480}\n</expression>\n</block>");
   String string = sb.toString();
   ...
   SocialInteraction_SpeechChat.this.uinterface.parseAIMLexpression(string);   // line 5375
   ```
   (A zeroed/stop variant — `"angular":0`, same shape — fires when DOA
   tracking should idle, line 5395.)

This settles, with a real fired example rather than dead declared code,
every open question the two prior passes (the launcher-app agent's
`Motion2Msg`/`MotionFrame` find and the original version of this doc) left
open:

- **The outer wire shape actually used is `{"tx":...,"ax":...,"mx":{...},"ix":...,"rx":...,"id":N}`
  JSON**, deserialized by Gson directly into an `ExpressionMsg` — this is
  the *same* `ExpressionMsg` class whose `packData1()` method
  (`tools/serviceexam_jadx/sources/com/common_source/emotix/interaction/interaction/ExpressionMsg.java:317-350`)
  builds the wire frame (see below). The JSON is not a separate/competing
  protocol from the "VEL1="-tagged 100-byte frame — it is the **source
  data** that gets compiled into that frame.
- **`mx.motion_type: 4`** in the confirmed-live JSON matches `Motion2Msg.M4`
  (`= 4`, "a sequence of (linear, angular) signed pairs") from the
  sibling-agent `Motion2Msg`/`MotionFrame` finding — same numbering
  convention, even though (see below) the class that's actually
  instantiated and packed at runtime is a sibling class, `MotionMsg`, not
  `Motion2Msg` itself.
- **`mx.type: 4`** matches `commandInterface.MOTION_MESSAGE = 4` — same
  constant value, confirming the numbering is shared vocabulary across the
  two channels even though (per the scope note above) they are different
  wire protocols.
- The `"kp"/"ki"/"kd"/"pidcontrol"` fields present in this literal JSON
  **do not exist on the `MotionMsg` class** that actually deserializes `mx`
  (see below) — Gson silently drops unknown JSON fields, so these four
  fields are inert in this call. INFERRED: leftover from an earlier/parallel
  schema (possibly `Motion2Msg`, which *does* have `kp`/`ki`/`kd` fields) that
  wasn't cleaned out of this hand-built JSON template.

### Full traced chain, JSON asset → bytes on `/dev/ttyS2` (CONFIRMED, file:line for every hop)

1. **Input**: an XML string `<block><expression>{json}</expression></block>`
   — either hand-built as above, or (for pre-authored choreography) loaded
   from an expression asset file — passed to
   `AndroidUnityInterface.parseAIMLexpression(String, ...)`
   (`tools/serviceexam_jadx/sources/com/common_source/emotix/acattsandroidsdkdemo/AndroidUnityInterface.java:275+`).
   This is the exact method **also invoked by ServiceExam's AIDL dispatcher**
   for codes 133/134/135/212 (`ServiceClientInterface.callEvent`, see
   `docs/hardware/aidl-dispatch.md`) — i.e. a third-party app driving
   ServiceExam over AIDL with one of those codes and a `ServiceRequest`
   whose `data` field is this XML shape reaches **the identical code path**
   the robot's own voice-direction-tracking feature uses.
2. Each `<expression>` element's text content is parsed via
   `AndroidUnityInterface.loadExpressionString(String)`
   (`AndroidUnityInterface.java:270-273`): `Gson.fromJson(json, ExpressionMsg.class)`
   — the JSON's `mx` object deserializes directly into
   `ExpressionMsg.mx`, a `MotionMsg`
   (`tools/serviceexam_jadx/sources/com/common_source/emotix/acattsandroidsdkdemo/MotionMsg.java`),
   whose declared fields are `type`, `size`, `motion_type`, `loop`,
   `seqCount`, `seq: ArrayList<MotionFrame>` (matching the JSON exactly,
   minus the four inert PID fields noted above). Each `seq` entry
   deserializes into a `MotionFrame`
   (`tools/serviceexam_jadx/sources/com/common_source/emotix/acattsandroidsdkdemo/MotionFrame.java`):
   `linear`, `angular`, `time`, `type`, `id` (all `int`) — matching the
   sibling agent's field list exactly.
3. `ExpressionMsg.packData1()`
   (`ExpressionMsg.java:317-350`) — when `mx.getSeqCount() > 0`: forces
   `mx.setType(4)`, then branches on the **first** frame's own `type` field:
   `type` in `[6, 25)` → pack via `mx.packbytes()`, tag the 100-byte result
   `"MOTION3"` in the outgoing map; anything else (including this example's
   `type: 1`) → same pack, tagged `"MOTION1"`. **Both tags produce an
   identical wire frame** (`"VEL1="` ASCII prefix + packed bytes + `0x58`
   padding to 100 bytes, `ExpressionMsg.java:321-333`/`335-346`) — the
   `MOTION1`/`MOTION3` distinction is purely an internal map-key label with
   no wire-format or dispatch-code difference (both funnel to
   `AndroidUnityInterface.MOTION_PLAY`, next step).
4. `MotionMsg.packbytes()` (`MotionMsg.java:84-169`) — the actual byte-layout
   builder. Two cases:
   - **Normal (first frame's `type != 5`, this example's case)**: header
     `type(3B unsigned) + datasize(3B) + motion_type(3B) + loop(3B) + seqCount(3B)`,
     then **per frame** (repeated `seqCount` times):
     `linear(3B signed) + angular(3B signed) + time(3B unsigned) + frame.type(3B unsigned)`.
     All 3-byte fields via `com.emotix.arya.app_utils.ByteUtils`
     (`tools/serviceexam_jadx/sources/com/emotix/arya/app_utils/ByteUtils.java`):
     `convertToBytes(int)` = big-endian unsigned, right-justified in 3 bytes
     (`:29-43`); `convertToBytesSigned(int)` = 2 magnitude bytes (big-endian,
     `BigInteger.valueOf(Math.abs(i))`) + 1 trailing sign byte
     (`1`=positive, `2`=negative, `0`=zero) (`:8-27`) — this is the exact
     encoder the sibling agent identified, confirmed here as the one
     actually used by the live call path, not just declared.
   - **Special case (first frame's `type == 5`)**: treated as a single
     "PID control" frame instead of a velocity sequence — no header fields
     at all, just `frame.type(3B) + linear(3B signed, reused as a PID/config
     value) + angular(3B signed) + time(3B)`, fixed 50-byte buffer
     (`MotionMsg.java:118-159`). This is almost certainly where the
     `Motion2Msg.M1`-style PID/config payload (`kp`/`ki`/`kd`/`target_angle`/
     `zonea`/`zoneb`/position-and-velocity scale factors, per the sibling
     agent's `Motion2Msg` finding) actually surfaces on the wire, even
     though the class carrying those named fields (`Motion2Msg`) is not the
     class this live path instantiates (see "Motion2Msg vs MotionMsg" below).
5. `ExpressionThread.getInfo(Map.Entry, boolean)`
   (`tools/serviceexam_jadx/sources/com/common_source/emotix/acattsandroidsdkdemo/ExpressionThread.java:313-354`)
   — reads the `"MOTION1"`/`"MOTION3"` map entry, wraps the 100-byte frame in
   an `AndroidUnityData` with `code = AndroidUnityInterface.MOTION_PLAY`
   (`=10`), calls `this.uinterface.getEvent(androidUnityData)`.
6. `AndroidUnityInterface.getEvent(AndroidUnityData)`
   (`tools/serviceexam_jadx/sources/com/common_source/emotix/acattsandroidsdkdemo/AndroidUnityInterface.java:2044-2052`),
   `code == MOTION_PLAY` branch: `this.si.SendData(bdata, z)` — `si` is the
   live `SocialInteraction_SpeechChat` instance.
7. **`SocialInteraction_SpeechChat.SendData(byte[], boolean)`**
   (`tools/serviceexam_jadx/sources/com/common_source/emotix/interaction/interaction/SocialInteraction_SpeechChat.java:2465-2509`)
   — the terminal call: gated on `this.uinterface.bluetooth_flag`, then
   **`sensorModule.writeUART(bArr)`** — a `native` JNI method
   (`emotix.com.drivers.SensorModule.writeUART(long, byte[])`,
   `tools/serviceexam_jadx/sources/emotix/com/drivers/SensorModule.java:76`,
   bridging into `libmiko_drivers.so`), writing the raw 100-byte
   `"VEL1=..."` frame directly to whichever UART `SensorModule` opened —
   **`/dev/ttyS2` for this device's M3Q-serial units**, per the
   already-confirmed logic earlier in this doc. Response is read back via
   `sensorModule.readUART()`; a 10-byte literal `"ERROR_UART"` reply
   triggers `sensorModule.initURTAgain()` (UART re-init) instead of a normal
   ack.

```
JSON {"mx":{"motion_type":4,"seqCount":1,"seq":[{"linear":0,"angular":±20,"time":10,"type":1}]}, ...}
   │  Gson.fromJson(..., ExpressionMsg.class)          [AndroidUnityInterface.loadExpressionString]
   ▼
ExpressionMsg.mx (MotionMsg, seq=[MotionFrame])
   │  packData1(): mx.setType(4); mx.packbytes()        [ExpressionMsg.java:317-333]
   ▼
byte[] frame = "VEL1=" + packed(15 + 12×seqCount bytes) + 0x58-pad-to-100
   │  map.put("MOTION1", frame)
   ▼
ExpressionThread.getInfo(): AndroidUnityData(code=MOTION_PLAY, bdata=frame)  [ExpressionThread.java:313-326]
   ▼
AndroidUnityInterface.getEvent(): si.SendData(frame, ...)                    [AndroidUnityInterface.java:2044-2052]
   ▼
SocialInteraction_SpeechChat.SendData(): sensorModule.writeUART(frame)       [SocialInteraction_SpeechChat.java:2465-2509]
   ▼
emotix.com.drivers.SensorModule.writeUART(long, byte[])  — native, libmiko_drivers.so
   ▼
/dev/ttyS2  (this device's M3Q-serial UART node)
```

### `HeadMsg`/`HeadFrame` — a previously-undocumented head-motor axis (CONFIRMED)

`ExpressionMsg.hx` is a `HeadMsg`
(`tools/serviceexam_jadx/sources/com/common_source/emotix/acattsandroidsdkdemo/HeadMsg.java`),
packed and dispatched by the **identical mechanism** as motion, just tagged
`"HEAD="` instead of `"VEL1="` (`ExpressionMsg.java:351-367`, tag bytes `'H','E','A','D','='`)
and routed through `AndroidUnityInterface.HEAD_MOTION_PLAY` (`=16`) instead
of `MOTION_PLAY` — same `si.SendData(bdata, true)` terminal call
(`AndroidUnityInterface.java:2060-2065`), same `sensorModule.writeUART()`.

Byte layout (`HeadMsg.packbytes()`, `HeadMsg.java:80-133`): header
`seqCount(3B) + time(3B) + num_loops(3B) + relative(3B)`, then **per
`HeadFrame`** (`tools/serviceexam_jadx/sources/com/common_source/emotix/acattsandroidsdkdemo/HeadFrame.java`):
`roll(3B signed) + pitch(3B signed) + yaw(3B unsigned) + velocity(3B unsigned)`.
This is a genuine 3-axis (roll/pitch/yaw) head-orientation keyframe format
with a velocity term and a `relative`-vs-absolute mode flag — **no caller
that populates real `HeadFrame` values was found in this pass** (unlike
motion, where `startDOAThread()` provides a confirmed live example) —
flagged as an open question below. `RGBMsg`/`"RGB1="` uses the same
tag-plus-`packbytes()`-plus-0x58-padding scheme for LEDs; see
`docs/hardware/laser-leds.md` for that payload's own field layout (out of
scope here).

### Resolving the "VEL1= vs 0x3C/0x3E" framing question

These are **two independent wire protocols, not nested layers**, confirmed
by tracing both to their respective native bridges:

| | This section's channel | The Arya/`TransportLayer` channel (rest of this doc) |
|---|---|---|
| Trigger | `parseAIMLexpression()` → `ExpressionMsg.packData1()` | `com.miko.app_bluetooth.comm.ApplicationLayer`/`TransportLayer` |
| Frame shape | ASCII tag (`"VEL1="`/`"HEAD="`/`"RGB1="`) + packed binary + `0x58` pad, 100 bytes | `0x3C` StartTag + 9-byte `AckMessage` header + payload + `0x3E` EndTag, 100 bytes |
| Native bridge | `emotix.com.drivers.SensorModule` (`libmiko_drivers.so`) | `android.serialport.SerialPort` (`libserial_port.so`), via `com.miko.app_bluetooth.device.serialDevice` |
| UART open call | `SensorModule.createUART(500, "/dev/ttyS2")`/`initUART()` | `serialDevice.getSerialPort(path)` → `new SerialPort(File, baud, 0)` |
| Confirmed live? | **Yes** — `startDOAThread()` fires it continuously whenever DOA tracking is active | Only for `"TCHDB"` touch-sync tag, `"SHTDN"` shutdown tag, and raw bootloader writes (`commandInterface.MOTION_MESSAGE` itself still never constructed) |

Both channels ultimately target the **same physical device node**
(`/dev/ttyS2` for this unit) via two textually separate Java/JNI stacks that
each independently call `open()` on it. **Not established in this pass**:
whether both are opened simultaneously in the live app (two file descriptors
on one tty, which the peripheral MCU firmware would need to tolerate/arbitrate),
or whether only one is actually live per boot depending on some
configuration this pass didn't find. This is the one piece of the "how does
motion actually reach the wire" question that still needs a live-device
check (`lsof`/`fuser` on `/dev/ttyS2`, or watching which code path fires
during an actual DOA-triggered turn) to fully close out.

### `Motion2Msg` vs `MotionMsg` — parallel classes, only one confirmed live

Two structurally similar but distinct classes exist:
`com.common_source.emotix.acattsandroidsdkdemo.MotionMsg` (confirmed above —
this is the one `ExpressionMsg.mx` actually is, and the one the live
`startDOAThread()` path packs and writes) and
`com.common_source.emotix.acattsandroidsdkdemo.Motion2Msg`
(`tools/serviceexam_jadx/sources/com/common_source/emotix/acattsandroidsdkdemo/Motion2Msg.java`
— the class the sibling agent found, with named `kp`/`ki`/`kd`/`target_angle`/
`zonea`/`zoneb`/`positionScaleA-C`/`velocityScaleMove`/`velocityScaleStop`/
`onewheel`/`steer` fields and `M1`-`M4` sub-type packing). `Motion2Msg` has
exactly one reference anywhere in ServiceExam's decompiled source —
`com.emotix.Utils.deSerializeMotion2String(String)`
(`tools/serviceexam_jadx/sources/com/emotix/Utils.java:39-43`,
`Gson.fromJson(..., Motion2Msg.class)`) — and **that method itself has zero
callers found anywhere in the decompile**. `Motion2Msg` is therefore
**INFERRED dead/unreached code in this build**, structurally a richer
superset of `MotionMsg` (explicit PID/scale-factor fields that `MotionMsg`
only reaches indirectly via its `type==5` special-case, explicit `M1..M4`
naming that `MotionMsg.motion_type`'s raw int values echo). Whether
`Motion2Msg` is a newer, not-yet-wired-up replacement or an older superseded
version wasn't determined — flagged below. **For driving the robot today,
`MotionMsg` (12-byte-per-frame velocity sequences, or the 50-byte `type==5`
PID-frame variant) is the class with a confirmed live call path;
`Motion2Msg`'s richer PID/scale-factor config surface is not confirmed
reachable.**

Note: `serialDevice.getDevices()`'s own default (`/dev/ttyMT1`) is a
*fallback* used when no `device_name` was passed in — in practice the caller
above always supplies a resolved path via `SensorModule`'s logic, so treat
`/dev/ttyS2` as the live answer for this device, `/dev/ttyMT1` as the
alternate-hardware-variant path.

### Frame format (CONFIRMED, fully recovered byte layout)

Fixed 100-byte frames (`TransportLayer.DEFAULT_BUFFER_SIZE = 100`):

```
┌─────────────┬──────────────────────────────┬─────────────────────┬───────────┐
│ StartTag    │ Header (AckMessage)           │ Payload              │ EndTag    │
│ 3 bytes     │ 9 bytes                        │ up to 85 bytes       │ 3 bytes   │
│ 0x00 0x00 0x3C │ type(3B) + msgId(3B) + count(3B) │ data, 0x58-padded │ 0x00 0x00 0x3E │
└─────────────┴──────────────────────────────┴─────────────────────┴───────────┘
```

- Every integer field (`StartTag.id`, `EndTag.id`, `AckMessage.type/id/count`)
  is serialized as **exactly 3 bytes**, via
  `ByteUtils.convertToBytes(int)` → `BigInteger.valueOf(i).toByteArray()`
  right-aligned into a 3-byte buffer (`emotix.com.drivers` and
  `com.emotix.arya.app_utils` both ship an identical `ByteUtils`).
- `StartTag.DEFAULT_ID = 60` (0x3C, ASCII `<`). `EndTag.DEFAULT_ID = 62`
  (0x3E, ASCII `>`).
- Header is an `AckMessage`: `type` is `AckMessage.TYPE_PHONE=2` (phone→bot)
  or `TYPE_BOT=1` (bot→phone); the field named `count` in `AckMessage` is
  where `TransportLayer.send_data()` stuffs the **command-type id**
  (`AryaMessage.getId()`, i.e. one of the `commandInterface` constants below);
  `id` is a rolling per-frame sequence counter (`AckMessage.updateId()`,
  `id++` on every send).
- Payload padding byte is **`0x58`** (ASCII `X`) — see `pad_tag[0] = 88` in
  `TransportLayer` and `serialDevice.generate500ByteData()`.
- **ACK is echo-based, not a dedicated ACK byte**: the sender computes
  `acktag = new String(frame).substring(0, 15)` before transmit, then treats
  any reply frame whose first 5-15 bytes match as the ACK
  (`TransportLayer.handleMessage`, case `2`, tag matching against `acktag`/
  `TAG_MODR = "<MODR"`). Timeout 10s per attempt (`ack_timeout_ms = Globals.
  WIFI_STATUS_UPDATE_MILLIS = 10000`), retried once (2 total attempts) —
  see `TransportLayer.transmit()`.

### Command-type table (CONFIRMED — identical constants in both `com.miko.launcher_app` and ServiceExam)

Defined twice, verbatim: `com.miko.app_bluetooth.comm.message.commandInterface`
(`com.miko.launcher_app` + ServiceExam) and `com.example.root.expression.commandInterface`
(ServiceExam only, a plain class instead of an interface, same values):

| Constant | Value | Notes |
|---|---|---|
| `DATA_MESSAGE` | 1 | generic |
| `ACK_MESSAGE` | 2 | |
| `IMAGE_MESSAGE` | 3 | |
| **`MOTION_MESSAGE`** | **4** | never constructed anywhere in the decompiled code — see below |
| `LCD_MESSAGE` / `RGB_MESSAGE` | 5 | same numeric value, two names — see `docs/hardware/laser-leds.md` |
| `GESTURE_MESSAGE` | 6 | not observed sent either (distinct from the Unity-side `AndroidUnityInterface.MOTION_PLAY`/`MOTION_PLAY2` face-expression codes, which are unrelated — those drive a Unity-rendered face, not this serial link) |
| `POWER_MESSAGE` | 7 | battery/charger telemetry — see `docs/hardware/buttons-power.md` |
| `STATS_MESSAGE` | 8 | |
| `BOOTLOADER_MESSAGE` | 9 | the one command type actually exercised, see below |
| `INFO_MESSAGE` | 10 | |
| `SPEECH_MESSAGE` | 11 | |
| `AUDIO_MESSAGE` | 12 | |
| `DELAY_MESSAGE` | 13 | |
| **`MOTION2_MESSAGE`** | **21** | never constructed anywhere either |
| `ATTRIBUTION_MESSAGE` | 50 | subtitle/caption sync data, not hardware |

### What's actually exercised vs. dead (CONFIRMED)

- **Bootloader flow is real and wired**: `com.miko.app_update.boot.BootloaderLibrary`
  gets a `SensorModule` instance and calls `sensi.writeUART(this.txData)` /
  `writeUART(this.txLongData)` directly — **bypassing the
  StartTag/AckMessage/EndTag framing entirely**, writing raw bytes straight
  to the UART. This is presumably how the peripheral MCU's firmware gets
  reflashed. The exact `txData`/`txLongData` byte layout was not
  reverse-engineered in this pass (out of scope — it's firmware-update
  plumbing, not motor control).
- **Everything else that touches `ApplicationLayer`/`TransportLayer`
  (`SocialInteraction`, an abstract base implemented by
  `SocialInteraction_SpeechChat`)** only ever calls `binterface.setBootloader_flag(...)`
  and sends a literal `"SHTDN"`/`"SHUTDOWN"` tag string on shutdown
  (`serialDevice.generate500ByteData("SHTDN", 500)`) — never a framed
  `MOTION_MESSAGE`/`MOTION2_MESSAGE`/`GESTURE_MESSAGE` packet with real
  payload data, in **any** of the four apps' dex files.

## Assessment: does this hardware unit actually have drive wheels?

**INFERRED, not confirmed either way.** Two readings are equally consistent
with the evidence:

1. **The protocol is inherited, unused, dead code.** `MOTION_MESSAGE`/
   `MOTION2_MESSAGE`/`GESTURE_MESSAGE` look like leftovers from a shared
   "Arya" SDK used across multiple Miko hardware generations/SKUs, and this
   particular Miko 3 unit (stationary desktop companion form factor) never
   wires them up because it has no motors to drive.
2. **The caller lives in an app we don't have.** The firmware/system-image
   agent's pass over `system.img` (`docs/hardware/boot-hal-reference.md`,
   open question #5) found `/system/app/game_robot_maker.apk` (46.8MB) —
   unopened by any pass so far, and its name is a strong hint it could be
   exactly the movement-command caller this analysis didn't find. This is
   the single highest-value next step for anyone continuing this work.

Given reading (1) is also plausible and this is a personally-owned Miko 3
that reportedly does drive around, **do not treat the "dead code" reading as
settled** — it's exactly as unconfirmed as the missing-caller reading. Live
`getevent`/`logcat` capture while physically moving the unit (or opening
`game_robot_maker.apk`) is required to resolve this.

## Device node / hardware summary

| Item | Value | Status |
|---|---|---|
| UART device (this unit, M3Q serial) | `/dev/ttyS2` | CONFIRMED (derived from serial-number branch logic) |
| UART device (M3J-serial units) | `/dev/ttyMT1` | CONFIRMED (code path exists, not our unit) |
| Baud rate | 2,000,000 | CONFIRMED |
| Frame size | 100 bytes fixed | CONFIRMED |
| Wire framing | `0x3C`-tag / 9-byte header / padded payload / `0x3E`-tag | CONFIRMED |
| Motor command payload byte layout | — | **Superseded — see "The real motion channel" above.** This row described the Arya/`TransportLayer` protocol's `MOTION_MESSAGE`, which is CONFIRMED never constructed. The actual live motion channel (`"VEL1="`-tagged frames via `SensorModule.writeUART()`) has a fully CONFIRMED byte layout: `type(3B)+datasize(3B)+motion_type(3B)+loop(3B)+seqCount(3B)` header + per-frame `linear(3B signed)+angular(3B signed)+time(3B)+type(3B)`. |
| Speed/direction/turning parameters | — | CONFIRMED for turning: `startDOAThread()` sends `angular=±20` (turn), `linear=0` (no forward/back) to pivot-turn toward a speaker. `linear`'s real-world units/scale for forward/back motion were not observed with a nonzero value in this pass — INFERRED to be the same signed-3-byte encoding as `angular`, scale unconfirmed. |
| Safety cutoffs (cliff/edge detection, stall, etc.) | `Power.edge_obstacle_detected` field exists (`com.models.sensor.Power`) | Field present, `@Expose`d for telemetry JSON, but no code that *sets* it was found — likely populated from a sensor payload this pass didn't trace fully. INFERRED: an edge/cliff sensor exists and reports through the same telemetry channel as battery data. |

## Implementation

### 0. Driving the wheels for real — the confirmed working path

This replaces the old "placeholder only" example below (§3) for anything
that needs to actually move the robot. It's a direct transcription of
`startDOAThread()`'s confirmed-live JSON shape (see above), generalized to
arbitrary `linear`/`angular` values. **Directly observed**: the XML/JSON
wrapper shape, the `mx` field names and their meaning, the `packbytes()`
byte layout, the `"VEL1="` tag, the `sensorModule.writeUART()` terminal
call, and the `/dev/ttyS2` device node. **My own assembly, not directly
observed**: generalizing the hardcoded `linear:0, angular:±20, time:10`
triple to arbitrary values — no code sample with a nonzero `linear` or a
different `time` was found, so treat `linear`'s scale/units and `time`'s
unit (presumably centiseconds or ticks, unconfirmed) as INFERRED by
extrapolation from the one confirmed example, not independently verified.

**Route (a) — via ServiceExam's AIDL surface (recommended, no native code needed).**
Per `docs/hardware/aidl-dispatch.md`, a third-party app can bind ServiceExam's
`MyService` (`exported=true`, gated only by the auto-granted `INTERNET`
permission — no signature check found) and call
`GameControllerAIDL.GameEvent(json)` with AIDL code 133 or 135 (expression
playback), whose payload is a `ServiceRequest` with `.data` set to the exact
XML/JSON expression string `startDOAThread()` builds. This reuses
ServiceExam's own `parseAIMLexpression()` → `packData1()` →
`SendData()` → `writeUART()` pipeline verbatim — no need to touch
`libmiko_drivers.so` directly:

```kotlin
import org.json.JSONObject

/** linear/angular: raw signed values in whatever units MotionFrame uses (see
 *  Open questions — only ±20 angular / 0 linear is confirmed-observed).
 *  timeCentiseconds: only "10" has been observed live. */
fun buildMotionExpressionXml(linear: Int, angular: Int, timeCentiseconds: Int = 10): String {
    val mx = """{"type":4,"size":0,"motion_type":4,"loop":1,"kp":0,"ki":0,"kd":0,"pidcontrol":0,"seqCount":1,"seq":[{"linear":$linear,"angular":$angular,"time":$timeCentiseconds,"type":1,"id":0}]}"""
    val json = """{"tx":{"type":0,"size":0,"loop":0,"seqCount":0,"seq":[]},"ax":{"type":0,"size":0,"loop":0,"seqCount":0,"seq":[]},"mx":$mx,"ix":{"type":0,"size":0,"imagetype":0,"loop":0,"seqCount":0},"rx":{"type":0,"size":0,"loop":0,"seqCount":0,"seq":[]},"id":${(0..999).random()}}"""
    return "<block>\n<expression>$json</expression>\n</block>"
}

// Using the ServiceExamClient from docs/hardware/aidl-dispatch.md §Implementation(a):
fun turnRight(client: ServiceExamClient) {
    val req = JSONObject()
        .put("name", "")
        .put("data", buildMotionExpressionXml(linear = 0, angular = 20))
        .put("audioPath", "")
    client.sendCode(135, req.toString())   // 135 = expression playback (TTM-aware), see aidl-dispatch.md
}
```

**Route (b) — raw byte-level replication (fallback, if the AIDL route is
ever blocked).** This reproduces `MotionMsg.packbytes()` +
`ExpressionMsg.packData1()`'s framing directly, for a custom app that links
`libmiko_drivers.so` itself (same JNI signatures as
`emotix.com.drivers.SensorModule` — `createUART(int,String)`,
`initUART(long)`, `writeUART(long,byte[])`, `readUART(long)`) or reimplements
an equivalent UART write:

```python
def encode_u3(n: int) -> bytes:
    """MotionMsg's unsigned field encoder (ByteUtils.convertToBytes)."""
    return n.to_bytes(3, "big")

def encode_s3(n: int) -> bytes:
    """MotionMsg's signed field encoder (ByteUtils.convertToBytesSigned):
    2 magnitude bytes (big-endian) + 1 sign byte (1=pos, 2=neg, 0=zero)."""
    mag = abs(n).to_bytes(2, "big")
    sign = bytes([1]) if n > 0 else (bytes([2]) if n < 0 else bytes([0]))
    return mag + sign

def build_motion_msg(linear: int, angular: int, time: int, frame_type: int = 1,
                      motion_type: int = 4, loop: int = 1) -> bytes:
    """MotionMsg.packbytes() for a single-frame velocity sequence (frame_type != 5)."""
    frame = encode_s3(linear) + encode_s3(angular) + encode_u3(time) + encode_u3(frame_type)
    seq_count = 1
    base_size = 6  # ByteUtils.getByteSize(...)*2 -- accounts for the type+datasize fields themselves
    datasize = 3 + 3 + 3 + len(frame) + base_size   # motion_type + loop + seqCount + frame(s) + base
    header = encode_u3(4) + encode_u3(datasize) + encode_u3(motion_type) + encode_u3(loop) + encode_u3(seq_count)
    return header + frame   # == datasize bytes total

def build_vel1_frame(linear: int, angular: int, time: int = 10) -> bytes:
    """ExpressionMsg.packData1()'s outer 100-byte wrapper for the VEL1 tag."""
    packed = build_motion_msg(linear, angular, time)
    frame = b"VEL1=" + packed
    return frame.ljust(100, b"\x58")   # 0x58 = 'X' padding, same as ExpressionMsg.java

# ---- write path (fallback only -- prefer route (a) above) ----
# Equivalent to SensorModule.createUART(500, "/dev/ttyS2") + initUART() + writeUART():
# requires libmiko_drivers.so's JNI symbols, or root + a raw termios-configured
# open() on /dev/ttyS2 if the wire protocol turns out not to need SensorModule's
# specific init sequence (unconfirmed -- SensorModule.init()'s native side was
# not further decompiled, see Open questions).
frame = build_vel1_frame(linear=0, angular=20)   # matches startDOAThread()'s turn-right packet exactly
# sensor_module.writeUART(frame)   # via JNI, or a raw serial.Serial("/dev/ttyS2", ...).write(frame) if
                                    # SensorModule's native init() turns out to be a plain termios open()
```

### 1. Reproducing the decompile

```sh
# tools/extracted/ IS com.miko.launcher_app (launcher_alpha_V2-v4.2.apk), not
# MikoPlus -- see the attribution note at the top of this doc. Either the
# unzipped dir's classes.dex or the original APK decompiles cleanly and
# produces the same emotix/com/drivers/{SensorModule,RGBController,mikoI2C,...}
# classes; no merge issue was found here (that was an earlier mis-attribution
# in this doc, since corrected):
jadx -d out tools/launcher_alpha_V2-v4.2.apk
# -- or, equivalently, against the already-unzipped copy:
cd tools/extracted && jadx -d /tmp/launcher-clean classes.dex

# com.miko.mikoplus (tools/base.apk) genuinely does NOT contain
# emotix.com.drivers at all (confirmed: zero "Lemotix/com/drivers" strings in
# its classes.dex) -- don't expect to find it there.
```
For ServiceExam (multidex), pass all five dex files in one invocation so
cross-dex references resolve: `jadx -d out classes.dex classes2.dex classes3.dex classes4.dex classes5.dex`.

### 2. JNI call sequence to open the Arya UART (fully reproducible — this is real, working API surface)

```java
import emotix.com.drivers.SensorModule;

// isJoyarPort: true for M3Q-serial units (this device) -> /dev/ttyS2
//              false for M3J-serial units              -> /dev/ttyMT1
SensorModule sm = SensorModule.getInstance(true);
// getInstance() internally: chmod's the device node via `su`, then
//   createUART(500, "/dev/ttyS2"); initUART(nativeObject);
sm.writeUART(myFrameBytes);   // raw write, bypasses ACK/retry framing
byte[] resp = sm.readUART();  // raw read
```

### 3. Building a framed request on the Arya/`TransportLayer` channel (NOT the motion channel — see §0)

**Superseded for motion** by §0 above, which is the confirmed live path. This
section is retained because the Arya/`TransportLayer` 0x3C/0x3E-framed
protocol below is still real and still used for other things (`"TCHDB"`
touch-sync, `"SHTDN"` shutdown, raw bootloader writes) — just not for
driving the wheels. This reproduces `TransportLayer.send_data()`'s exact
byte layout. The **framing below is verified against decompiled code**; the
**payload content for `MOTION_MESSAGE` on *this* channel is still unknown
and still believed dead** (`commandInterface.MOTION_MESSAGE` is never
constructed anywhere) — this example sends an empty/placeholder payload and
is kept only as a reference for the touch/shutdown/bootloader use of this
channel, not as a motion-driving recipe.

```python
import struct, serial

def encode_u3(n: int) -> bytes:
    """Matches ByteUtils.convertToBytes: 3-byte big-endian."""
    return n.to_bytes(3, "big")

def build_frame(msg_type: int, msg_id: int, seq: int, payload: bytes) -> bytes:
    PAD = b"\x58"                      # 'X', TransportLayer.pad_tag
    start = encode_u3(60)               # StartTag, 0x3C
    header = encode_u3(msg_type) + encode_u3(msg_id) + encode_u3(seq)
    end = encode_u3(62)                 # EndTag, 0x3E
    budget = 100 - len(start) - len(header) - len(end)   # 85 bytes
    body = payload[:budget].ljust(budget, PAD)
    return start + header + body + end  # always exactly 100 bytes

TYPE_PHONE = 2         # AckMessage.TYPE_PHONE
MOTION_MESSAGE = 4      # commandInterface.MOTION_MESSAGE — payload format UNKNOWN

frame = build_frame(TYPE_PHONE, MOTION_MESSAGE, seq=1, payload=b"")  # placeholder!

ser = serial.Serial("/dev/ttyS2", 2000000, timeout=1)  # our unit's node; su required
ser.write(frame)
ack = ser.read(100)   # expect first 5-15 bytes to echo `frame`'s header if MCU ACKs
```

If/when the real `MOTION_MESSAGE` payload format is recovered (e.g. by
sniffing `game_robot_maker.apk` or live UART capture while the robot moves),
slot it into `payload` above — the framing, device node, and baud rate here
are already confirmed correct.

## Open questions

**Resolved by the 2026-09-14 update above** (kept here struck through for
continuity, not renumbered, since other docs may cite the old numbering):
~~Does `game_robot_maker.apk` contain the `MOTION_MESSAGE` payload builder?~~
— moot, `MOTION_MESSAGE` (the Arya-channel constant) is confirmed dead; the
real motion channel is `MotionMsg`/`"VEL1="`, unrelated to that constant.
~~Does this unit have drive wheels at all?~~ — the app-layer code confirmed
to fire a real, non-placeholder motion command (`startDOAThread()`'s
voice-direction auto-turn) is about as strong a static-analysis signal as
possible short of watching the unit move; still not a substitute for an
actual live observation, so leaving a softer version of this question below.

1. **Live-device confirmation of §0's payload**: does sending the §0 "route
   (a)" AIDL call (or the raw §0 "route (b)" byte frame) via a bound
   `GameControllerAIDL` actually turn the robot, matching `startDOAThread()`'s
   own effect? This is the one thing this entirely-static pass cannot verify
   — everything in "The real motion channel" section is CONFIRMED as *code
   that runs*, not CONFIRMED as *physically moves the unit* (out of scope
   per this task's read-only/no-device-interaction constraint).
2. **`linear`'s scale and units, and forward/backward driving**: every
   observed live `MotionFrame` has `linear=0` — only `angular` (turning) was
   ever seen with a nonzero value. Whether nonzero `linear` actually drives
   the wheels forward/back, what scale (mm/s? encoder ticks? PWM duty?), and
   what range is valid are all UNKNOWN, not just uninstantiated. If this
   robot generation only pivot-turns in place and has no forward/back drive
   at all, that would also explain why `linear` is never observed nonzero —
   worth weighing alongside the original "does it even have wheels" question.
3. **`time` field units** — only the literal value `10` was ever observed.
   INFERRED to be some short duration (centiseconds? a fixed-rate tick
   count?) given the field's name and its role alongside a keyframe
   sequence, not independently confirmed.
4. **`MotionFrame.type` value space** — confirmed behaviorally significant
   (type `5` triggers `MotionMsg.packbytes()`'s alternate 50-byte
   "PID control" framing; types `[6,25)` select the `"MOTION3"` map-key vs
   `"MOTION1"` for types outside that range, though both produce identical
   wire bytes) but the full meaning of each type value 0-24+ was not
   recovered — only `type=1` (the confirmed live turn-command frame) is
   pinned down.
5. **Does `Motion2Msg` (kp/ki/kd/PID/scale-factor config, `M1`-`M4` typed)
   get wired up anywhere this pass didn't reach** — e.g. via reflection, a
   dex this multidex jadx pass under-resolved, or a class only present in a
   *different* ServiceExam build than v92? If it's genuinely dead, it may
   still be useful as a *schema reference* for tuning PID/turning-rate
   constants even without a live call site, since `MotionMsg`'s own
   `type==5` special case appears to serve the same purpose with less
   structure.
6. **Does the Arya/`TransportLayer` channel (0x3C/0x3E framing,
   `libserial_port.so`) and the `SensorModule`/`"VEL1="` channel
   (`libmiko_drivers.so`) both hold `/dev/ttyS2` open simultaneously?** See
   "Resolving the VEL1= vs 0x3C/0x3E framing question" above — needs
   `lsof`/`fuser` on a live device, out of scope for this pass.
7. `HeadMsg`/`HeadFrame` (roll/pitch/yaw head-motor axis, confirmed byte
   layout, no confirmed live caller found) — same open status as `linear`
   above: is there a head-tracking feature (e.g. face-follow) that
   populates this, analogous to `startDOAThread()` for body rotation? Worth
   checking `docs/hardware/camera-vision.md`'s face-tracking findings for a
   caller this pass didn't cross-reference.
8. Does `/system/app/game_robot_maker.apk` (found by the firmware/system-image
   pass, not analyzed by any pass so far) contain a *richer* motion API than
   what's documented here (e.g. `Motion2Msg`'s PID tuning surface, or actual
   forward/back driving)? Lower priority now that a confirmed working
   turn-in-place path exists, but still the best lead for anything beyond
   turning.
9. What is `/dev/ttyS1` used for (found with the same loosened permissions as
   `/dev/ttyS2` in `init.mt8168.rc`, per `boot-hal-reference.md`)? No app-layer
   reference to it was found in this pass — candidate: a second MCU, or the
   same MCU's alternate/debug channel.
10. Exact byte semantics of `RGBFrame` LED payload fields (`pattern`,
    `rate`, `color`, `time`) — see `laser-leds.md`.
11. `BootloaderLibrary.txData`/`txLongData` byte layout (MCU firmware update
    protocol) — not reverse-engineered here, flagged as future work only if
    MCU reflashing becomes relevant to the jailbreak project.
12. Does `Power.edge_obstacle_detected` (cliff/edge sensor flag) actually get
    set anywhere, and via which serial tag? Not traced to a setter in this pass.
13. `SensorModule.init()`/`createUART()`'s native-side implementation
    (whether it's a plain `termios` configuration a non-`SensorModule` app
    could replicate with a raw `open()`, or does something
    `libmiko_drivers.so`-specific like a proprietary handshake) was not
    decompiled (it's native code, out of scope for a Java-source jadx pass)
    — relevant to how hard §0 route (b) is in practice if route (a) (AIDL)
    is ever unavailable.
