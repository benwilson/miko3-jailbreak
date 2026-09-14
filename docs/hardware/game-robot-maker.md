# Game Robot Maker (`es.monkimun.game_robot_maker`) — pure content game, no hardware ties

Scope: `/system/app/game_robot_maker.apk` (`tools/game_robot_maker.apk`, 46.8MB
compressed / 146.5MB uncompressed). Decompiled with
`jadx -d tools/game_robot_maker_jadx tools/game_robot_maker.apk` and
`apktool d -o /tmp/grm_apktool tools/game_robot_maker.apk` (apktool output not
committed — ephemeral, manifest excerpted below).

**Bottom line (CONFIRMED): this is a licensed Lingokids Unity game
("build a robot" content for kids), not a hardware-control app.** It bundles
zero Miko driver code, makes zero AIDL/IPC calls to ServiceExam or any other
system component, and its 46.8MB size is fully explained by the Unity engine
+ Mono runtime + third-party SDKs it ships, not by robot-control logic. Low
value for the custom-launcher effort — documented briefly per the task brief
rather than padded out.

## Identity (CONFIRMED)

- Package confirmed two ways: `AndroidManifest.xml` (`package="es.monkimun.game_robot_maker"`)
  and `firmware/agent-backups/pre-full-restore-20260914T012507Z/packages.xml`
  line 6265:
  ```
  <package name="es.monkimun.game_robot_maker" codePath="/system/app/game_robot_maker.apk"
    nativeLibraryPath="/system/lib/game_robot_maker" primaryCpuAbi="armeabi-v7a"
    sharedUserId="1000" ... isOrphaned="true">
  ```
  `sharedUserId="1000"` / manifest `android:sharedUserId="android.uid.system"` —
  runs under the system UID like most Miko preloaded system apps. Note the
  `isOrphaned="true"` flag in the packages.xml backup — worth keeping in mind
  if this app shows install/state weirdness on other passes, though it wasn't
  investigated further here (out of scope for a content-game doc).
- "monkimun" is Lingokids' internal/legacy studio name (see Lingokids DLLs
  below) — this app is Lingokids' "Robot Maker" game, white-labeled into
  Miko's app set, not a Miko/Emotix-authored app.

## Manifest / components (CONFIRMED)

Single activity, no services, no receivers, no providers, no AIDL:

```xml
<activity android:name="com.unity3d.player.UnityPlayerActivity"
    android:launchMode="singleTask" android:screenOrientation="sensorLandscape"
    android:hardwareAccelerated="false" android:theme="@style/UnityThemeSelector">
    <intent-filter>
        <action android:name="android.intent.action.MAIN"/>
        <category android:name="android.intent.category.LAUNCHER"/>
        <category android:name="android.intent.category.LEANBACK_LAUNCHER"/>
    </intent-filter>
</activity>
```

`android:isGame="true"`. Permissions: `INTERNET`, `RECORD_AUDIO`,
`MODIFY_AUDIO_SETTINGS`, `BLUETOOTH`, `READ_LOGS`. No `CAMERA`, no motor/serial/
I2C-adjacent permissions, nothing Miko-specific. `RECORD_AUDIO`/`BLUETOOTH` are
almost certainly generic Unity/Lingokids boilerplate (mic input minigames,
default Unity template requests) rather than evidence of real hardware use —
nothing in the decompiled Java or native libs exercises them toward robot
hardware (see below).

## Java layer (CONFIRMED): stock Unity shell, zero custom Miko code

`tools/game_robot_maker_jadx/sources/` contains only:

- `com/unity3d/player/*` — stock Unity player bridge.
- `com/google/androidgamesdk/*` — Google Play Games SDK glue.
- `com/yasirkula/unity/DebugConsole*` — a well-known third-party Unity asset
  ("In-game Debug Console"), dev-only logging utility, unrelated to hardware.
- `org/fmod/*` — FMOD audio middleware Unity ships by default.
- `bitter/jnibridge/*` — generic Unity IL2CPP/Mono JNI bridge helper.
- `es/monkimun/game_robot_maker/` — **only auto-generated `R.java` and
  `BuildConfig.java`**. No custom Activity, no custom Service, no JNI
  glue class, no AIDL stub/proxy anywhere in the tree.

Grepped the full jadx source tree case-insensitively for `serviceexam`,
`IMikoService`, and `com.example.root` — zero hits. There is no `bindService`/
`AIDL` call-out to ServiceExam or any other Miko system component anywhere in
this app's Java code.

## Native libraries (CONFIRMED): standard Unity/Mono only, no hardware drivers

```
$ unzip -l tools/game_robot_maker.apk | grep '\.so$'
   326948  lib/armeabi-v7a/libMonoPosixHelper.so
    18464  lib/armeabi-v7a/libmain.so
  5498616  lib/armeabi-v7a/libmonobdwgc-2.0.so
 16766808  lib/armeabi-v7a/libunity.so
```

All four are stock Unity/Mono runtime components (Unity engine core, Mono
Boehm-GC runtime, Mono POSIX helper, Unity's native bootstrap `libmain.so`).
None of the hardware-driver libraries documented elsewhere in this project
(`libmiko_drivers.so`, `libserial_port.so`, `libfaceDetection.so`, per
`docs/hardware/motors-wheels.md`, `laser-leds.md`, `camera-vision.md`) are
present — confirmed both by this listing and by grepping every other
hardware doc in `docs/hardware/*.md` for those library names (they only
appear in the driver-layer docs, never in a context referencing this app).
This app has no way to reach `/dev/ttyS*`, I2C, or any HAL — it never links
against the code that talks to those devices.

## Managed assemblies (CONFIRMED): explains the size, confirms the publisher

```
assets/bin/Data/Managed/Assembly-CSharp.dll   2.1MB   (the actual game logic — C#, not decompiled here; jadx only handles Java/dex)
assets/bin/Data/Managed/Lingokids.Core.dll
assets/bin/Data/Managed/Lingokids.Frameworks.Activities.dll
assets/bin/Data/Managed/Lingokids.Frameworks.AudioSystem.dll
assets/bin/Data/Managed/Lingokids.Frameworks.AudioSystem.Modules.dll
assets/bin/Data/Managed/Lingokids.Frameworks.System2D.dll
assets/bin/Data/Managed/Lingokids.Library.dll
assets/bin/Data/Managed/DOTween.dll / DOTweenPro.dll / DemiLib.dll   (Unity tweening asset)
+ 80 stock UnityEngine.*Module.dll / System.*.dll / mscorlib.dll / netstandard.dll
```

The `Lingokids.*` assemblies are the tell: **Lingokids** is a well-known
third-party kids' education/gaming company. `game_robot_maker.apk` is a
licensed Lingokids Unity title, reskinned/bundled as a Miko preloaded app —
same pattern as other genuinely-a-game preloaded apps (`puzzlegame.apk`,
`ticTacToe.apk`) but much larger because it ships the full Mono scripting
backend (not IL2CPP) plus the complete `UnityEngine.*` module set plus the
Lingokids SDK, none of which the smaller games apparently bundle. Total
uncompressed payload is 146.5MB, of which 99 files live under
`assets/bin/Data/Managed/` alone — i.e. the size is runtime/engine/SDK
overhead, not evidence of bundled robot-control code.

## Cross-checks against other hardware docs (CONFIRMED)

- **Laser**: `laser-leds.md` flagged this app as unchecked for a possible
  laser/projector feature. Checked here: `strings tools/game_robot_maker.apk
  | grep -i laser` → zero hits. Consistent with the rest of the project:
  no laser feature exists in any app examined so far.
- No `AttributionMsg`/`MotionMsg`/`RGBMsg`/serial-frame vocabulary
  (`docs/hardware/motors-wheels.md`, `laser-leds.md`) appears anywhere in
  this app — further confirming it never talks to the body MCU.

## What's actually in the game (INFERRED, not deeply explored — out of scope)

`Assembly-CSharp.dll` (the real C# game logic) was not decompiled — jadx only
handles the Java/dex layer, and a .NET/Mono decompiler pass (e.g. ILSpy) was
out of scope for a "is this hardware-relevant" pass given the native-lib and
manifest evidence already answered that question negatively. Based on the
package name, Lingokids branding, and `UnityEngine.UI`/`Timeline`/`Animation`/
`ParticleSystem` modules present, this is almost certainly a straightforward
drag-and-drop or tap-to-assemble "build your own robot" edutainment minigame
with no connection to the physical Miko 3's actual servo/wheel layout. Not
worth cataloging further for a replacement launcher — there is no reusable
movement-pattern or expression-asset logic here, just game content.

## Open questions

- `Assembly-CSharp.dll` itself was not decompiled; if someone wants full
  certainty about the in-game content (vs. just ruling out hardware ties),
  an ILSpy/dotPeek pass would confirm it but is unlikely to change the
  conclusion.
- The `isOrphaned="true"` flag on this package in the packages.xml backup
  wasn't investigated — unclear if that's meaningful device state or routine
  for preloaded/disabled apps.
- Whether `RECORD_AUDIO`/`BLUETOOTH` permissions are ever actually exercised
  by the C# game logic (e.g. a mic-input minigame) is unconfirmed; native/Java
  evidence rules out any hardware-driver use, but the *reason* Lingokids
  requests them wasn't traced into the assembly.
