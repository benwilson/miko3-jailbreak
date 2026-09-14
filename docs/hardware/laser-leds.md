# Laser / LEDs — light-emitting hardware in the driver layer

Scope: laser and any LED/light features found in `libmiko_drivers.so` /
`libserial_port.so` and the apps that load them. Sources: same as
`motors-wheels.md` and `buttons-power.md`. Cross-reference:
`docs/hardware/boot-hal-reference.md` (firmware-image agent, sysfs/HAL LED
findings).

## Laser: CONFIRMED absent from this driver layer

**No "laser" string, class, resource, or symbol exists anywhere in
`libmiko_drivers.so`, `libserial_port.so`, or the decompiled dex of
`com.miko.launcher_app` (`tools/extracted/` — see the source-attribution note
at the top of `motors-wheels.md`: this is the device's HOME/LAUNCHER app, not
MikoPlus, despite earlier task framing), `com.miko.mikoplus`
(`tools/base.apk`/`tools/extracted-system/`, v69), or
`com.example.root.serviceexam` (v92).** Checked with a case-insensitive
search across every `.so`'s string table (`strings -a | grep -i laser`) and
every `.dex`'s string pool across all four apps (11 dex files total) — zero
hits. This is a genuine negative result, not an oversight: the same searches
that found the RGB/touch-light code below (which *does* exist) turned up
nothing for "laser".

**If Miko 3 has a laser (e.g. a laser pointer/projector feature), it is not
implemented in any code this pass had access to.** Per the task brief, check
whether it surfaces in the main camera/voice system app instead —
`docs/hardware/camera-vision.md` and `docs/hardware/voice-mic.md` (sibling
agents) cover that territory; neither was cross-checked for "laser" as part
of this pass. Also flagging `/system/app/game_robot_maker.apk` (found by the
firmware-image agent, unopened by any pass so far) as another place a
laser/projector feature could live if this device has one.

## LEDs: two separate, seemingly redundant control surfaces (CONFIRMED)

### 1. I2C-driven RGB controller (`emotix.com.drivers.RGBController`)

Part of `libmiko_drivers.so` (the same native library documented in
`motors-wheels.md` for the sensor/UART link). Java-side class:

```java
public class RGBController {
    public native long createRGB(long i2c);
    public native void initRGB(long nativeRGB);
    public native void parseRGB(long nativeRGB, byte[] data, int len);

    public void init() {
        this.i2c = mikoI2C.getInstance().getI2C0();   // /dev/i2c-0, per .so strings
        this.nativeRGB = createRGB(this.i2c);
        initRGB(this.nativeRGB);
    }
}
```
`mikoI2C` (also in `libmiko_drivers.so`) opens `/dev/i2c-0` or `/dev/i2c-2`
(both strings present in the `.so`'s string table — `"DEV I2C: open i2c
device from %s"`, `"I2c device %s is not present."`). `RGBController` uses
`getI2C0()` specifically, i.e. **`/dev/i2c-0`**.

### 2. LED animation-sequence format (`RGBMsg`/`RGBFrame`, CONFIRMED byte layout)

A proper keyframe-animation wire format exists in the same package:

```java
public class RGBFrame {           // one keyframe
    int pattern;   // meaning UNKNOWN — never observed populated
    int rate;      // meaning UNKNOWN
    int color;     // meaning UNKNOWN — packed RGB int? palette index? not observed
    int time;      // meaning UNKNOWN — presumably duration
}

public class RGBMsg {             // a full animation
    int type;
    int loop;
    int seqCount;
    ArrayList<RGBFrame> seq;
}
```
`RGBMsg.packbytes()` serializes (all fields 3-byte big-endian, same
`ByteUtils` convention documented in `motors-wheels.md`):

```
[type(3B)] [datasize(3B)] [loop(3B)] [seqCount(3B)]
  then seqCount × { pattern(3B), rate(3B), color(3B), time(3B) }
```

This is clearly a "blink/color sequence" format — pattern + rate + color +
duration per frame, looped `loop` times — but **no call site anywhere in the
four decompiled apps constructs an `RGBMsg`/`RGBFrame` with real data**. The
only callers of `RGBController`/`SensorModule` at all are
`BootloaderLibrary` (`com.miko.launcher_app` — MCU firmware flashing,
unrelated) and `SocialInteraction_SpeechChat` (ServiceExam — just
instantiates `SensorModule`, for the touch-sensor side — see below). Same
"fully-specified format, never observed exercised" pattern as
`MOTION_MESSAGE` in `motors-wheels.md`.

Separately, the Arya MCU serial-protocol command table (see
`motors-wheels.md`) has a `LCD_MESSAGE`/`RGB_MESSAGE` constant — **both names
share the same value, 5** — suggesting this `RGBMsg` payload is meant to be
carried as the payload of a transport-layer frame with command-type 5, but
that link (which class actually calls `TransportLayer.sendMessage(rgbMsg.packbytes(), ..., 5)`)
was not found either.

### 3. Touch-panel light (`SensorModule.touchLightsOn()`/`touchLightsOff()`)

Simple on/off, no color/pattern control:
```java
public native void touchLightsOn(long nativeObject);
public native void touchLightsOff(long nativeObject);
```
Exported natively as `Java_emotix_com_drivers_SensorModule_touchLightsOn`/`_Off`
in `libmiko_drivers.so`. Same `SensorModule` instance used for the UART
sensor link documented in `motors-wheels.md` — this is almost certainly a
light behind/around the capacitive touch sensor panel (see also
`touchStatus()`, `ir1Status()`/`ir2Status()`, `rgbStatus()` on the same
class — all named alongside touch/IR sensing, reinforcing "touch panel
backlight" as the likely purpose). No call site with real usage was found
beyond `SensorModule` being instantiated for the sensor/bootloader flows —
whether/when the app actually toggles this light is **UNKNOWN**.

### 4. OS-level raw LED sysfs nodes (CONFIRMED, from boot-hal-reference.md)

The firmware-image agent's pass over `vendor.img`'s `ueventd.rc` found:
```
/sys/devices/platform/leds-mt65xx/leds/{red,green,blue}/delay_on
/sys/devices/platform/leds-mt65xx/leds/{red,green,blue}/delay_off
```
at `0664 system:system` — **the only LED/indicator hardware control path
that agent found in the entire boot/HAL layer.** This is a generic MediaTek
3-channel (red/green/blue) blink-rate LED, controllable by writing
millisecond values to the `delay_on`/`delay_off` sysfs nodes (standard Linux
LED-class blink-trigger interface) — no app code needed at all, any process
with write access can drive it directly.

**Not reconciled**: whether this sysfs LED is the *same physical LED* as the
I2C-driven `RGBController` above (i.e. two redundant control paths to one
LED — one low-level generic Linux LED-class interface, one app-level I2C
driver with animation support), or two *different* LEDs (e.g. a simple
red/green/blue status indicator on sysfs, separate from an "eye"/touch-ring
RGB LED on I2C). Static analysis alone can't distinguish these — would need
either a live `cat`/`echo` test against the sysfs nodes while watching for a
visible light change, or datasheet/schematic access.

`boot-hal-reference.md` also notes a declared (not confirmed used)
`android.hardware.light@2.0-service-mediatek` HAL — the "proper" Treble path
for LED control that AOSP apps would normally use instead of raw sysfs;
whether MikoPlus/ServiceExam go through this HAL for anything was not
checked by either pass (no `ILight`/`android.hardware.light` reference found
in this pass's app-layer searches, for what that's worth as a partial
negative).

## Summary table

| Feature | Status | Control path |
|---|---|---|
| Laser | **Not found anywhere in this driver layer or either app** | — |
| RGB LED (I2C) | CONFIRMED to exist, format fully recovered, never observed used with real data | `emotix.com.drivers.RGBController` → `mikoI2C` → `/dev/i2c-0` |
| LED animation format | CONFIRMED byte layout (`RGBMsg`/`RGBFrame`) | Same `libmiko_drivers.so`, never observed populated |
| Touch-panel light | CONFIRMED on/off API exists | `SensorModule.touchLightsOn/Off()`, same UART/sensor MCU as `motors-wheels.md` |
| Red/green/blue blink LED | CONFIRMED sysfs nodes exist (loosened perms) | `/sys/devices/platform/leds-mt65xx/leds/{red,green,blue}/delay_{on,off}` — raw Linux LED-class, no app code required |
| `light@2.0` HAL | Declared in VINTF manifest, usage unconfirmed | Standard AOSP path, not traced |

## Implementation

### 1. JNI call sequence to drive the RGB controller (real API, unverified payload semantics)

```java
import emotix.com.drivers.RGBController;

RGBController rgb = RGBController.getInstance();   // opens I2C0, calls createRGB/initRGB
byte[] data = buildRgbMsgBytes(...);                // see below — semantics of the
                                                      // fields are NOT reverse-engineered
rgb.parseRGB(data, data.length);
```

### 2. Building an `RGBMsg` frame (framing is real/transcribed; field semantics are a guess)

```java
// Transcribed directly from RGBMsg.packbytes() / ByteUtils.convertToBytes.
// The wire LAYOUT below is confirmed. The MEANING of pattern/rate/color/time
// is NOT confirmed -- treat any values you put here as a starting guess to
// test against the real MCU, not a known-good command.
static byte[] u3(int n) {
    byte[] b = new byte[3];
    byte[] raw = java.math.BigInteger.valueOf(n).toByteArray();
    System.arraycopy(raw, 0, b, Math.max(0, 3 - raw.length), Math.min(3, raw.length));
    return b;
}

static byte[] buildRgbMsgBytes(int type, int loop, int[] patterns, int[] rates,
                                int[] colors, int[] times) {
    int seqCount = patterns.length;
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    try {
        out.write(u3(type));
        // datasize is computed post-hoc in the real code (base size + per-frame
        // bytes); recompute it the same way if you need an exact match:
        int datasize = (seqCount * 4 + 2) * 3;   // 4 fields/frame + loop + seqCount, all 3B
        out.write(u3(datasize));
        out.write(u3(loop));
        out.write(u3(seqCount));
        for (int i = 0; i < seqCount; i++) {
            out.write(u3(patterns[i]));
            out.write(u3(rates[i]));
            out.write(u3(colors[i]));
            out.write(u3(times[i]));
        }
    } catch (java.io.IOException e) { /* ByteArrayOutputStream never throws */ }
    return out.toByteArray();
}
```

### 3. Simpler alternative: drive the raw sysfs blink LED directly (fully reproducible, no JNI needed)

```sh
# As root (su). Values are typically milliseconds on/off for a blink pattern;
# a 0 delay_off with nonzero delay_on is commonly "solid on" on this driver
# class -- not verified against this specific kernel, standard Linux
# leds-class behavior.
echo 0   > /sys/class/leds/red/delay_off     # path may also be
echo 500 > /sys/class/leds/red/delay_on      # /sys/devices/platform/leds-mt65xx/leds/red/...
```
This bypasses the app layer entirely and does not require reverse-engineering
`RGBMsg` semantics — the most practical starting point for a custom
launcher/ROM that just wants *some* LED feedback working, before the I2C
`RGBController` payload format is fully understood.

## Open questions

1. Is there a laser/projector feature on this hardware at all? Not found in
   this driver layer or either app analyzed — check `game_robot_maker.apk`
   and the camera/voice apps (`camera-vision.md`/`voice-mic.md` territory)
   next, and ultimately confirm/deny against the physical unit.
2. Are the sysfs `leds-mt65xx` RGB LED and the I2C `RGBController` the same
   physical LED or two different ones?
3. What do `RGBFrame.pattern`/`.color` actually encode (packed 0xRRGGBB?
   palette index? blink-pattern enum?) — never observed populated with real
   values in any pass so far.
4. Which class (if any) actually sends an `RGBMsg` payload as a
   `LCD_MESSAGE`/`RGB_MESSAGE`-typed frame over the Arya UART link
   documented in `motors-wheels.md`? Not found — same "wired but unused"
   pattern as the motion messages.
5. Does the app layer ever go through `android.hardware.light@2.0-service-mediatek`
   instead of/in addition to raw sysfs or the I2C driver? Not checked.
