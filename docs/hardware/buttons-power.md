# Physical buttons — power, volume+/-, mute

Scope: power button, volume up/down, mute — Android keycodes, app-layer
handling, and the alleged "special encoding" on volume+/-. Sources: same four
decompiled apps as `motors-wheels.md` — `com.miko.launcher_app`
(`tools/extracted/`, the device's HOME/LAUNCHER app, see the source-attribution
note at the top of `motors-wheels.md` for why this is not "MikoPlus" despite
earlier task framing), `com.example.root.serviceexam` (v92), and
`com.miko.mikoplus` (`tools/base.apk`/`tools/extracted-system/`, v69) — cross-
referenced against `docs/hardware/boot-hal-reference.md` (firmware-image
agent's static pull of `/system/usr/keylayout/mtk-kpd.kl` from `system.img`).

## Keycodes at the kernel/HAL layer (CONFIRMED, from boot-hal-reference.md)

`/system/usr/keylayout/mtk-kpd.kl` (MediaTek keypad-matrix driver) maps:

```
key 116   POWER
key 115   VOLUME_UP
key 114   VOLUME_DOWN
key 113   MUTE
```

These are **stock, unmodified Linux evdev keycodes**
(`KEY_POWER=116`, `KEY_VOLUMEUP=115`, `KEY_VOLUMEDOWN=114`, `KEY_MUTE=113` —
`linux/input-event-codes.h`), in stock AOSP `.kl` syntax. **There is no
custom keycode remapping at the keylayout layer.** If "special encoding"
exists, it is not a remapped keycode — it has to be app-layer logic (combo,
quantization, etc.) built on top of these standard events, or it doesn't
exist as literally described. See below for what app-layer logic actually
was found.

## App-layer key handling (CONFIRMED, negative result — independently re-confirmed 2026-09-14)

**None of the four decompiled apps contain any code that reads
`KEYCODE_VOLUME_UP`, `KEYCODE_VOLUME_DOWN`, `KEYCODE_MUTE`, `KEYCODE_POWER`,
overrides `onKeyDown`/`dispatchKeyEvent`, or registers a receiver for
`android.media.VOLUME_CHANGED_ACTION`.** This was checked by grepping every
`.java` file (10,000+ classes per build) across `com.miko.mikoplus` (both
copies), `com.example.root.serviceexam`, and `com.miko.launcher_app` for
these exact tokens — zero hits beyond unrelated library code (ExoPlayer's
internal `StreamVolumeManager`, which manages *media-player* ducking, not
hardware keys).

**Independently re-confirmed** against a second, separately-produced jadx
decompile of ServiceExam (`tools/serviceexam_jadx/`, same package/version
`com.example.root.serviceexam` v92, 13,100 `.java` files across 5 dex files,
produced while tracing the AIDL dispatch layer for
`docs/hardware/aidl-dispatch.md`): `grep -rl "KEYCODE_CAMERA\|KEYCODE_FOCUS\|KEYCODE_VOLUME_UP\|KEYCODE_VOLUME_DOWN\|KEYCODE_POWER\|KEYCODE_MUTE"`
across this entire independent decompile — **zero hits**, including in every
third-party/library package bundled in the app (not just Miko's own code).
This rules out the possibility that the original pass's ServiceExam source
(`tools/APPS-extracted/file1.ia`) was somehow an incomplete/mismatched
extraction — two independently-produced decompiles of the same version now
agree.

The closest thing to a hardware "key" concept anywhere in ServiceExam's AIDL
dispatch table (see `docs/hardware/aidl-dispatch.md` for the full table) is
AIDL code **104**'s `KEY_START`/`KEY_STOP` string payloads
(`ServiceClientInterface.java:909-914`), which map to
`AndroidUnityInterface.TRIGGER` with data `"1"`/`"0"` — this is a **soft,
UI-driven trigger** (e.g. an on-screen button or a skill-initiated event),
not a hardware `KeyEvent`. Nothing in the dispatch table's ~90 identified
codes reads a raw Android keycode either. This further corroborates —
without proving beyond doubt, since ServiceExam is still a large codebase —
that physical key handling, if it exists at all, is not in either app-layer
component analyzed across either pass.

**Same negative result for the camera shutter button** (`KEY 212 CAMERA` /
`KEY 211 FOCUS` in `mtk-kpd.kl`, per `boot-hal-reference.md`): grepping all
four apps for `KEYCODE_CAMERA`/`KEYCODE_FOCUS` found zero hits, including in
`com.miko.launcher_app`'s `MainActivity.java` (730 lines, the one Activity
in that app, checked in full) despite that app declaring
`android.permission.CAMERA` and `android.hardware.camera`/`camera.front`
`<uses-feature>` in its manifest. If this device has a physical camera
shutter button, no app analyzed in this pass consumes it as a key event —
same "reaches stock framework, or is consumed somewhere unanalyzed" pattern
as power/volume/mute. Worth checking `docs/hardware/camera-vision.md`
(sibling agent) for whether MikoPlus's camera fragment handles it instead.

**Conclusion: the physical volume/power/mute buttons are not intercepted by
either app.** They almost certainly reach the stock AOSP
`PhoneWindowManager` → `AudioService`/`PowerManagerService` path unmodified —
this is the same conclusion `boot-hal-reference.md` reasoned toward from the
HAL side (no vendor HAL, no custom keylayout). **UNKNOWN**: whether a vendor
overlay `.jar` (not decompiled by either pass) intercepts these keys before
they reach the stock framework path — out of scope for a static app-layer
pass, would need `dumpsys window` or framework-jar decompilation on a live
device.

## What volume code DOES exist — and it's voice/skill-driven, not button-driven

The one substantial piece of volume-control logic found lives in
`com.common_source.emotix.interaction.interaction.SocialInteraction_SpeechChat`
(ServiceExam) and is triggered exclusively by **spoken/skill commands**, not
hardware key events. A skill response can embed a `SYSTEM_COMMAND`-typed
`MessageFrame` whose string body is one of these `Globals` constants:

```java
SYSTEM_VOLUME_UP = "SYSTEM_VOLUME_UP"
SYSTEM_VOLUME_DOWN = "SYSTEM_VOLUME_DOWN"
SYSTEM_VOLUME_MAXIMUM = "SYSTEM_VOLUME_MAXIMUM"
SYSTEM_VOLUME_MINIMUM = "SYSTEM_VOLUME_MINIMUM"
SYSTEM_VOLUME_MUTE = "SYSTEM_VOLUME_MUTE"
SYSTEM_VOLUME_UNMUTE = "SYSTEM_VOLUME_UNMUTE"
SYSTEM_VOLUME_25 / _50 / _75 = "SYSTEM_VOLUME_25" / "_50" / "_75"
SYSTEM_SLEEP / SYSTEM_SHUTDOWN = "SYSTEM_SLEEP" / "SYSTEM_SHUTDOWN"
```
These dispatch to `systemVolumeCommand(int)` / `setVolumeMaximumCommand()` /
`setVolumeMuteCommand()` / etc., all of which funnel into `setVolumeCommand(int direction, boolean charging)`.

### The actual "special encoding" found (CONFIRMED): a 6-level quantized volume scale

`setVolumeCommand` does **not** use the OS-default ±1 step. It maintains
volume as a custom int (`MikoStateMachine.volume`) applied identically to
three Android audio streams (`STREAM_MUSIC=3`, `STREAM_ALARM=4`,
`STREAM_NOTIFICATION=5`) simultaneously via `AudioManager.setStreamVolume()`,
and steps by **±2**, snapped at the edges:

```java
public void setVolumeCommand(int i, boolean z) {
    double volume = getVolume();
    if (i == 0) {                 // down
        volume -= 2.0d;
        if (volume == 13.0d) volume = 12.0d;
    } else if (i == 1) {          // up
        volume += 2.0d;
        if (volume == 14.0d) volume = 15.0d;
    }
    int i2 = (int) volume;
    if (i2 < 6) i2 = (i == 0) ? 0 : 6;   // floor: snaps to mute or to 6, never 1-5
    MikoStateMachine.volume = i2;
    audioManager.setStreamVolume(3, i2, 0);
    audioManager.setStreamVolume(4, i2, 0);
    audioManager.setStreamVolume(5, i2, 0);
    addVolumeEvent(i2);   // drives the 5-bar overlay UI
}
```

The result: volume only ever lands on **exactly six values: 0, 6, 8, 10, 12,
15** — matching the 5-bar `VolumeOverlayService` UI (0 bars=0, 1 bar=6,
2 bars=8, 3 bars=10, 4 bars=12, 5 bars=15) one-to-one. There is no
intermediate "quiet" state between mute and the first bar. `setVolumeMinimumCommand()`
hardcodes level 6 (not 0 — "minimum" ≠ "mute", they're different named
commands), `setVolumeMaximumCommand()` hardcodes 15, `setVolume25/50/75Command()`
hardcode 6/10/12 respectively (i.e. these are quantized to the same 6-value
scale, not a true 25/50/75% of 15).

**This is a real, confirmed, non-obvious behavior a custom launcher would
need to replicate for consistent-feeling volume UX** — but it was only
proven reachable via voice/skill commands, not a physical button press (see
above: no key-event code exists to invoke it from a hardware press). If the
physical buttons really do exhibit this quantization, the code path that
connects a `KEYCODE_VOLUME_UP` press to `setVolumeCommand` was not found by
this static pass — flagged as the top open question below.

### On-screen volume indicator (CONFIRMED)

`com.example.notification_library.systemoverlay.view.VolumeOverlayService`
(ServiceExam) is a custom system-overlay window — **not** the stock Android
volume dialog — showing 5 discrete bars, a mute icon, and touch-drag zones
(`decreaseSoundIv`/`increaseSoundIv`) that each call `increaseSoundLevel()`/
`decreaseSoundLevel()` (±1 *bar*, which internally calls
`overlayCallback.setVolume(1/0, false)` — a different, simpler code path
than `setVolumeCommand`, worth noting as a second, parallel volume-adjustment
entry point). Auto-dismisses after a 3-second idle timer
(`CounterTimer(3000L, 200L)`), restarted on every interaction.

## Power button (CONFIRMED: not intercepted; MCU serial "POWER" tag is unrelated telemetry)

No code reads `KEYCODE_POWER` in either app (see negative result above). The
serial-protocol tag **`"POWER"`** (`TransportLayer.TAG_POWER`, part of the
Arya MCU link documented in `motors-wheels.md`) is **not a button-press
notification** — it's the tag under which the peripheral MCU reports
**battery/charger telemetry**, parsed by `com.models.sensor.Power`:
voltage (`battery_voltage`), charging state (`soc_charger_status`), low/
critical/full-battery flags, faulty/slow/negative-charger detection. `Power.SHUTDOWN`
(constant value 8) is a *software-initiated* shutdown event (e.g. triggered
by critically-low battery), fired via `pc.chargingEvent(Power.SHUTDOWN)` —
unrelated to a physical power-button press.

Separately, `serialDevice.generate500ByteData("SHTDN", 500)` is sent to the
MCU by the app's own shutdown flow (`SocialInteraction_SpeechChat`'s
shutdown handling) — this is the app *telling* the MCU the device is
powering off, not the MCU reporting a button press. `TransportLayer` also
defines (but this pass found no user of) `TAG_WAKEUP = "WAKEUP"` and
`TAG_SHTDN = "SHTDN"` tags — plausibly symmetric counterparts for MCU-initiated
wake/shutdown requests, not confirmed either direction.

**Best current understanding**: the physical power button almost certainly
goes straight to the stock Android `PowerManagerService` (short press =
screen off/lock or power menu depending on OS config; long press = shutdown
dialog), with **no evidence of app-level interception** in either app.

## Mute button (CONFIRMED: standard keycode; app-level mute is voice-triggered only)

`KEY_MUTE=113` in the `.kl` file, standard. App-side, `setVolumeMuteCommand()`/
`setVolumeUnmuteCommand()` exist (set stream volume to 0 / back to 10) but,
like volume up/down, were only proven reachable from the `SYSTEM_VOLUME_MUTE`/
`SYSTEM_VOLUME_UNMUTE` voice/skill command strings — no code ties a hardware
`KEYCODE_MUTE` press to them.

## Summary table

| Button | Kernel keycode (CONFIRMED) | App-layer interception (CONFIRMED) | "Special encoding" found |
|---|---|---|---|
| Power | `KEY_POWER=116`, standard | None found | None — no app code reads this key at all |
| Volume Up | `KEY_VOLUMEUP=115`, standard | None found for the *key event*; extensive logic exists for *voice-triggered* volume changes | 6-level quantized scale (0/6/8/10/12/15, ±2 steps) — proven for voice path only |
| Volume Down | `KEY_VOLUMEDOWN=114`, standard | Same as above | Same as above |
| Mute | `KEY_MUTE=113`, standard | None found for the key event; voice-triggered mute exists | None beyond the same quantized scale (mute = level 0) |

## Implementation

### 1. What a custom launcher needs to ship for standard key handling

Since the keycodes are 100% stock AOSP and the `.kl` file is unmodified
(confirmed in `boot-hal-reference.md`), **no custom `.kl`/`.kcm` is required**
for a custom ROM/launcher to receive these events — standard
`KeyEvent.KEYCODE_VOLUME_UP` / `_DOWN` / `MUTE` / `POWER` will arrive exactly
as they would on any AOSP device. A launcher wanting to intercept them itself
(instead of letting the framework handle them by default) would override:

```kotlin
override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    if (event.action == KeyEvent.ACTION_DOWN) {
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP   -> { onVolumeStep(direction = +1); return true }
            KeyEvent.KEYCODE_VOLUME_DOWN -> { onVolumeStep(direction = -1); return true }
            KeyEvent.KEYCODE_MUTE        -> { onMuteToggle(); return true }
            // KEYCODE_POWER should generally NOT be consumed here — intercepting
            // it app-side on a non-rooted stock framework requires a device-owner/
            // accessibility-service trick; out of scope for a plain launcher.
        }
    }
    return super.dispatchKeyEvent(event)
}
```

### 2. Reproducing the stock quantized volume scale (real code, transcribed from decompile)

```kotlin
// Faithful port of SocialInteraction_SpeechChat.setVolumeCommand — the ONLY
// confirmed non-default volume behavior in the stock app. Reproduce this if
// you want feature parity with ServiceExam's voice-driven volume, and as the
// best current guess for what button-driven behavior *should* feel like
// (unconfirmed whether the hardware button path actually hits this or the
// stock linear step — see Open questions).
fun quantizedVolumeStep(current: Int, up: Boolean): Int {
    var v = current + if (up) 2 else -2
    if (!up && v == 13) v = 12
    if (up && v == 14) v = 15
    if (v < 6) v = if (up) 6 else 0
    return v.coerceIn(0, 15)
}

fun applyVolume(am: AudioManager, level: Int) {
    for (stream in intArrayOf(AudioManager.STREAM_MUSIC,
                               AudioManager.STREAM_ALARM,
                               AudioManager.STREAM_NOTIFICATION)) {
        am.setStreamVolume(stream, level, 0)
    }
}
```

### 3. What's NOT reproducible from static analysis

There is no confirmed code to port for "physical button → this quantized
scale" — that link was not found. Shipping the launcher code above and
wiring it to `dispatchKeyEvent` is an **inference**, not a transcription of
observed behavior for the hardware-button path specifically.

### 4. Replicating the voice-triggered volume path via AIDL, if that turns out to be what physical buttons hit too

Per `docs/hardware/aidl-dispatch.md`, ServiceExam's `MyService` is
`exported=true`, gated only by the auto-granted `android.permission.INTERNET`
(no signature check found) — so a custom launcher can reach the exact same
`setVolumeCommand`-driving codes MikoPlus uses (AIDL codes 172/173,
`SET_BOT_VOLUME`/`GET_BOT_VOLUME`) without reimplementing the quantization
logic in §2 at all:

```kotlin
// Using ServiceExamClient from aidl-dispatch.md; sets the 6-level quantized
// volume directly (0/6/8/10/12/15), bypassing the need to replicate
// setVolumeCommand's math client-side:
fun setBotVolume(client: ServiceExamClient, level: Int) = client.sendCode(172, level.toString())
```
If open question 1 below resolves to "yes, physical buttons do hit this
path," this AIDL route becomes the simplest way for a custom launcher to get
identical button-driven volume behavior — call this instead of §2's ported
Kotlin whenever ServiceExam is present and bindable.

## Open questions

1. **Top priority**: is there any code path — in a vendor overlay `.jar`, in
   `game_robot_maker.apk`, or elsewhere not covered by this pass — that
   connects a physical `KEYCODE_VOLUME_UP/DOWN` press to
   `setVolumeCommand`/`VolumeOverlayService`? Or do physical presses just
   hit the stock AOSP linear ±1 step with the stock system volume panel
   (which this kiosk UI might visually suppress) — meaning ServiceExam's
   custom 5-bar overlay and 6-level quantization are *voice-only* features
   and never fire from the hardware buttons at all?
2. Live-device confirmation needed (out of scope here): `getevent -lt` while
   pressing each physical button, to confirm they actually surface as evdev
   113/114/115/116 the way `mtk-kpd.kl` implies, and to see whether any
   userspace process holds `/dev/input/eventN` open besides the standard
   `InputReader`.
3. Does `TAG_WAKEUP`/`TAG_SHTDN` on the Arya MCU serial link ever fire
   MCU→phone (bot-initiated wake/shutdown request, e.g. from a physical
   button wired to the MCU instead of the SoC's own keypad matrix)? No
   receiving code was found, but the tags' existence alongside `TAG_POWER`
   is suggestive.
4. What does a vendor `PhoneWindowManager`/`SystemUI` overlay (not part of
   either decompiled app) do with these keys, if anything custom exists
   there? Out of scope for an app-layer-only pass.
