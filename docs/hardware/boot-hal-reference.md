# Boot/HAL reference — device nodes, init services, key layout

Extracted read-only from `firmware/dump/{boot,vendor,system}.img` (dump README:
MT8167/MT8168, Android 9 userdebug, dm-verity ENFORCING on system/vendor).
Cross-reference target for the `libmiko_drivers.so`/`libserial_port.so` app-layer
findings and the camera/voice main-app findings. Every claim below is either
**CONFIRMED** (pulled directly from an image with the path given, so you can
re-pull it yourself — see Implementation §5) or **UNKNOWN** (not found in these
three images, or out of scope for this pass).

## Extraction method (for reproducibility)

- `vendor.img` (400MB) and `system.img` (8.3GB) are ext4. macOS can't mount
  ext4 natively; used `debugfs` from `e2fsprogs` (installed via
  `brew install e2fsprogs`, keg-only — binary is at
  `/opt/homebrew/Cellar/e2fsprogs/<ver>/sbin/debugfs`, not linked into PATH).
  `debugfs -R 'ls -l <path>' img` to navigate, `debugfs -R 'dump <path> <out>' img`
  to pull a file. Fully read-only against the image; never mounted it.
- `boot.img`: AOSP legacy boot header, `header_version=1`, parsed by hand
  (script below) — **`ramdisk_size == 0`**. This device uses **system-as-root**:
  there is no ramdisk to unpack, and `/init.rc` + friends live at the root of
  `system.img` itself (confirmed: `/bin` and `/etc` at system.img root are
  symlinks to `/system/bin` and `/system/etc`). `kernel_size` is ~8.5MB, gzip
  (`\x1f\x8b`) — not unpacked further, out of scope for this pass.
- Did not touch the 8.3GB `system.img` wholesale — every pull below is a
  targeted `debugfs ls`/`dump` against one path.

## 1. Button/key layout ground truth (CONFIRMED)

Path: `/system/usr/keylayout/mtk-kpd.kl` (pulled from `system.img`). This is
the key layout for MediaTek's keypad driver (`mtk-kpd`), which is what the
physical buttons enumerate as. Full hardware-relevant subset:

```
key 115   VOLUME_UP
key 114   VOLUME_DOWN
key 113   MUTE
key 116   POWER
key 212   CAMERA
key 211   FOCUS
key 102   HOME
```

These are **standard Linux evdev keycodes** (`KEY_VOLUMEUP=115`,
`KEY_VOLUMEDOWN=114`, `KEY_MUTE=113`, `KEY_POWER=116` — see
`linux/input-event-codes.h`). There is **no custom/non-standard keycode
remapping** at this layer — the `.kl` file is stock AOSP `.kl` syntax
(`key <linux-keycode> <android-keylabel>`), nothing MediaTek- or
Miko-specific about the mapping itself. If the app layer sees "special
encoding" for volume+/-, it is not happening here — it's either a
combination/gesture built on top of these standard events, or it's coming
from a second source (see ACCDET below), not a remapped keycode. Flagging
this explicitly for the app-layer agents: **don't assume a custom keycode —
check for double-press/long-press/combo logic in the app or in
`libmiko_drivers.so` instead.**

A second, smaller layout exists at `/system/usr/keylayout/ACCDET.kl` (MediaTek
"accessory detect" — headset-jack button driver, not the robot's own buttons):

```
key 122   ENDCALL
key 163   MEDIA_NEXT
key 164   HEADSETHOOK
key 165   MEDIA_PREVIOUS
key 114   VOLUME_DOWN
key 115   VOLUME_UP
```

Both `.kl` files coexist under `/system/usr/keylayout/` alongside ~35 generic
USB-HID vendor/product `.kl` files (Logitech, Sony DualShock, Xbox
controllers, etc. — stock AOSP keylayout set, unmodified, listed but not
reproduced here). Key character maps (`/system/usr/keychars/`: `Generic.kcm`,
`qwerty.kcm`, `qwerty2.kcm`, `Virtual.kcm`) are also stock AOSP; no
Miko-specific `.kcm`.

**UNKNOWN**: which physical button maps to which evdev device node
(`/dev/input/eventN`) at runtime — that requires a live device (`getevent -lt`)
or the kernel input driver's probe name, neither available from a static dump.
The `.kl` filename (`mtk-kpd`) strongly implies the MTK keypad-matrix driver
(`drivers/input/keyboard/mtk-kpd.c` in MTK kernels) rather than a GPIO-keys
driver, but the DTS/DTB wasn't decoded to confirm the physical GPIO wiring.

## 2. Device nodes relevant to hardware control

**CONFIRMED** — full `ueventd.rc` dumped from both `vendor.img` (`/ueventd.rc`)
and `system.img` root (`/ueventd.rc`, the "hardware" one applied at
`/vendor/ueventd.rc` at runtime is actually the vendor copy; system's root
ueventd.rc is effectively the stock AOSP generic template — msm/htc-acoustic/
akm8976 leftovers and all, completely unmodified from upstream AOSP).

- **vendor's `ueventd.rc`** is the customized one. Robot/board-relevant nodes:
  - `/dev/video0`, `/dev/video1`, `/dev/video2` — `0666 root:root` (listed
    twice in the file, once early at `root:root`, once again at the end
    at `system:camera` — the later rule in ueventd wins, so effective owner
    is `system:camera`; this double-listing itself may be worth noting to
    the camera-agent as a possible merge artifact from OEM customization).
  - `/dev/video3`, `/dev/video4` — `0666 system:camera`, explicitly commented
    `# usb camera`.
  - `/dev/fm` — `0660 media:media` (FM radio, likely unused hardware).
  - `/dev/dri/card0`, `/dev/pvrsrvkm`, `/dev/pvr_sync` — GPU, standard MTK.
  - LED blink nodes: `/sys/devices/platform/leds-mt65xx/leds/{red,green,blue}/delay_on|delay_off`
    at `0664 system:system` — **this is the only LED/indicator hardware
    control path found in ueventd**; if Miko has an RGB status LED, this is
    almost certainly how it's driven (raw sysfs blink nodes, not a `light`
    HAL — see §4, `android.hardware.light@2.0-service-mediatek` HAL is
    declared, but the raw sysfs path here suggests direct control is also
    possible/used).
  - **No custom nodes for `/dev/tty*`, `/dev/i2c*`, `/dev/spi*` in either
    vendor's or system's `ueventd.rc`.** The generic `/dev/input/*` catchall
    (`0660 root:input`) is the only input-related permission rule.

- **`/dev/ttyS1` and `/dev/ttyS2` — CONFIRMED, found in
  `vendor/etc/init/hw/init.mt8168.rc` (not ueventd — set via `chown`/`chmod`
  actions in an `on <trigger>` block), NOT in any ueventd.rc:**
  ```
  #ttyS1
  chown system system /dev/ttyS1
  chmod 0666 /dev/ttyS1

  #ttyS2
  chown system system /dev/ttyS2
  chmod 0666 /dev/ttyS2
  ```
  This sits immediately after a similarly hand-formatted block
  (`#dsp` / `chown system system /sys/devices/platform/odm/odm:gpio_dsp/mid_dsp`
  / `chmod 0666 ...`), all indented with tabs in a file that is otherwise
  spaces-indented stock MediaTek boilerplate — **strong evidence this whole
  three-node block (`mid_dsp`, `ttyS1`, `ttyS2`) is an OEM (Chidakashi)
  addition on top of the stock `init.mt8168.rc`**, not MediaTek reference
  code. `0666` (world read/write, no group gate at all) on two UART nodes is
  unusual — this is the strongest candidate in the whole boot/HAL layer for
  **"the serial device `libserial_port.so` opens to talk to the motor/button
  microcontroller."** Flagging directly for the `libmiko_drivers.so`/
  `libserial_port.so` agent: **check whether the library opens
  `/dev/ttyS1` or `/dev/ttyS2` (or both) — this is the one place in the
  entire boot/HAL layer where node permissions were loosened from default.**
  `mid_dsp` is unexplained (UNKNOWN) — name suggests "mic ID" or a DSP
  identify line, could be audio-related rather than motor-related; not
  confirmed either way from static analysis.

- **`/dev/ttyGS0`, `/dev/ttyGS1`** — `0660`, chowned to `system`, set in
  `vendor/etc/init/hw/init.project.rc` under a `#Define device for ATCID`
  comment. These are USB-gadget-serial (ConfigFS ACM), used by MediaTek's
  factory/AT-command test tooling (`atcid`) — **not** robot hardware, but
  worth knowing they exist so they aren't confused with `ttyS1`/`ttyS2`
  above. Same file also sets standard MTK camera-AF-lens device node
  permissions (`/dev/MAINAF`, `/dev/DW9714AF`, etc. — all stock MTK lens
  driver names, nothing robot-custom).

**UNKNOWN**: no `/dev/i2c*` or `/dev/spi*` node ever gets a permission rule
in any `.rc` file pulled. If the motor/button MCU is actually on I2C or SPI
rather than UART, its node either uses whatever default the kernel driver
sets (unconfirmed, static analysis can't see kernel-internal default chmod)
or it isn't exposed as a raw character device at all. The `ttyS1`/`ttyS2`
finding above is circumstantial, not proven — it needs the
`libserial_port.so` string/import analysis to confirm which node(s) it
actually opens.

## 3. Init services tied to hardware (CONFIRMED)

`/vendor/etc/init/*.rc` (44 files) and `/vendor/etc/init/hw/*.rc` (14 files,
imported via `import /vendor/etc/init/hw/init.${ro.hardware}.rc` from the
root `init.rc`) are **entirely stock MediaTek/AOSP HAL service definitions** —
no service name, binary path, or class contains `motor`, `robot`, `miko`,
`serial`, or any other robot-hardware-suggestive string. Full `vendor/bin` and
`vendor/etc/init` listings were grepped for these keywords: **zero matches**.
Same negative result for `/system/bin` and `/system/xbin` (373 + 27 entries
grepped, zero hits).

This is itself a finding worth stating plainly: **there is no standalone
native daemon or init service anywhere in vendor.img or system.img that
looks like a "motor/button/robot hardware" service.** Whatever
`libmiko_drivers.so`/`libserial_port.so` do, they do it as a library loaded
directly into an app process (JNI), talking straight to a device node —
there's no HAL-mediated or init-managed daemon standing in between.

Representative stock services present (for completeness, not robot-specific):
`android.hardware.audio@4.0-service-mediatek`,
`android.hardware.camera.provider@2.4-external-service`,
`android.hardware.sensors@1.0-service-mediatek`,
`android.hardware.light@2.0-service-mediatek`,
`vendor.mediatek.hardware.power@2.0-service`, `vpud` (MTK VPU daemon),
`atcid`/`atci_service` (AT-command engineering interface), `rild.rc`, `mnld`
(GNSS), `gsm0710muxd` (modem mux — all present despite this being a
non-cellular robot; standard MTK reference build carries the full modem
stack even when unused).

Root `init.rc` (`system.img:/init.rc`, 29790 bytes) only directly declares 3
services (`ueventd`, `flash_recovery`, `daemonsu`); everything else comes in
via `import` of `/init.${ro.hardware}.rc` (→ `init.mt8168.rc`) and
`/vendor/etc/init/hw/init.${ro.hardware}.rc`. `init.mt8168.rc` itself
(24221 bytes) is otherwise stock MTK reference code except for the
`ttyS1`/`ttyS2`/`mid_dsp` block noted in §2.

## 4. HAL manifest findings (CONFIRMED)

`vendor/etc/vintf/manifest.xml` (16391 bytes) and `system/etc/vintf/manifest.xml`
(3726 bytes) declared HAL interfaces, deduplicated:

Standard AOSP HALs: `audio`, `audio.effect`, `bluetooth`, `camera.provider`,
`cas`, `configstore`, `drm`, `gatekeeper`, `gnss`, `graphics.allocator`,
`graphics.composer`, `graphics.mapper`, `health`, `keymaster`, `light`,
`media.omx`, `memtrack`, `neuralnetworks`, `power`, `renderscript`,
`sensors`, `thermal`, `usb`, `wifi`, `wifi.hostapd`, `wifi.supplicant`.

MediaTek vendor-extension HALs (all under `vendor.mediatek.hardware.*`):
`camera.ccap`, `camera.security`, `engineermode`, `gnss`, `keymanage`,
`keymaster_attestation`, `lbs`, `log`, `mtkcodecservice`, `netdagent`,
`nvram`, `power`, `pq` (picture quality), `wifi.hostapd`, `wifi.supplicant`.

**No custom/non-AOSP, non-MediaTek HAL exists anywhere in either manifest.**
No `vendor.chidakashi.*`, `vendor.miko.*`, or similarly-named HAL. This
reinforces §3: motor/button/robot control is **not** implemented as a
Treble HAL. It's a plain shared library (`libmiko_drivers.so`) with JNI
entry points, sitting entirely outside the HAL/init-service world this
document covers.

`system/etc/vintf/compatibility_matrix.{1,2,3,legacy,device}.xml` and
`vendor/etc/vintf/compatibility_matrix.xml` are stock framework compat
matrices; `odm/etc/vintf/manifest_{dsds,ss,tsts,qsqs}` are SIM-config
manifest fragments for MediaTek's modem stack (dual/quad-SIM variants) —
irrelevant to hardware control, not analyzed further.

## 5. Implementation — how to reproduce/verify this yourself

### 5a. Pulling files from the images (debugfs)

```sh
DBGFS=/opt/homebrew/Cellar/e2fsprogs/<version>/sbin/debugfs   # keg-only, not on PATH
DBGFS=$(brew --prefix e2fsprogs)/sbin/debugfs                 # or resolve dynamically

# list a directory
$DBGFS -R 'ls -l /system/usr/keylayout' firmware/dump/system.img

# pull one file out, read-only against the image
$DBGFS -R 'dump /system/usr/keylayout/mtk-kpd.kl /tmp/mtk-kpd.kl' firmware/dump/system.img
$DBGFS -R 'dump /etc/init/hw/init.mt8168.rc /tmp/init.mt8168.rc' firmware/dump/vendor.img
```

Never mount the images (`mount` requires root + a real ext4 driver macOS
doesn't have); `debugfs -R` is read-only and works directly against the
image file. `system.img` is 8.3GB — always target a specific path, never
`ls -R /` or a full `rdump /`.

### 5b. Equivalent commands on the live device (if adb/root is available)

```sh
adb shell su -c 'cat /system/usr/keylayout/mtk-kpd.kl'
adb shell su -c 'getevent -lt'          # watch live keycodes when pressing buttons —
                                         # confirms which /dev/input/eventN carries
                                         # KEY_VOLUMEUP/DOWN/MUTE/POWER
adb shell su -c 'ls -l /dev/ttyS1 /dev/ttyS2'   # confirm live perms match static finding
adb shell su -c 'lsof | grep -E "ttyS1|ttyS2"'  # confirm which process (if any) holds
                                                  # the node open — would directly identify
                                                  # the libserial_port.so-using process
```

(These are documented for completeness per the coordinator's request; none
were run — this pass was static-image-only, no live-device interaction, per
the task's constraints.)

### 5c. `.kl` file syntax, if a custom ROM needs to ship one

AOSP key layout syntax, one mapping per line:
```
key <linux-input-keycode>   <ANDROID_KEYCODE_LABEL>
```
For a custom ROM/kernel that needs POWER/VOLUME_UP/VOLUME_DOWN/MUTE on this
hardware, the **confirmed-working** stanza (copied verbatim from
`/system/usr/keylayout/mtk-kpd.kl` on this device) is:
```
key 116   POWER
key 115   VOLUME_UP
key 114   VOLUME_DOWN
key 113   MUTE
```
This file must be named to match the kernel input driver's device name
(`/proc/bus/input/devices` → `Name=` field, sanitized to `[A-Za-z0-9_]`,
`.kl` appended) and placed at `/system/usr/keylayout/<name>.kl` or
`/vendor/usr/keylayout/<name>.kl` (Android resolves the input device name to
a keylayout file at input-device-add time — see
`frameworks/native/libs/input/InputDevice.cpp` /
`InputReader` `KeyMap::load`; not verified against this specific kernel
source, standard AOSP behavior). Given the driver here is `mtk-kpd`, the
device is likely enumerated with a matching name — **UNKNOWN**: exact
`Name=` string, not observable from a static dump; would need
`/proc/bus/input/devices` from a live boot or the kernel DTS node label.

### 5d. init.rc service-block syntax, if a custom ROM needs to add a hardware daemon

No existing Miko hardware daemon was found to use as a template (§3) — there
is nothing to port forward. If the reverse-engineered `libserial_port.so`
protocol needs to be wrapped in a standalone daemon on a custom ROM (e.g. to
mediate `/dev/ttyS1` access instead of letting the app open it directly),
the stock MTK `.rc` service-block form actually used in these images (from
`vendor/etc/init/hw/init.mt8168.rc`'s neighbors, e.g. `vpud.rc`) is:
```
service vpud /vendor/bin/vpud
    class core
    user system
    group system
```
A permission-widening rule for a hardware node, in the exact form found for
`ttyS1`/`ttyS2` (`vendor/etc/init/hw/init.mt8168.rc`, under an `on <trigger>`
block — the trigger for that specific block was not re-derived in this pass,
treat as **UNKNOWN**, re-check the full file before relying on trigger
timing):
```
on <trigger>
    chown system system /dev/ttyS1
    chmod 0666 /dev/ttyS1
```

## Open questions for the other agents

1. **Does `libserial_port.so` open `/dev/ttyS1` or `/dev/ttyS2` (or another
   path entirely)?** This is the single highest-value cross-check — §2 found
   exactly two UART nodes with OEM-loosened (`0666`) permissions and nothing
   else in the entire boot/HAL layer that looks robot-specific. If the
   library's strings/imports reference a different path (e.g. an I2C bus
   device, or `/dev/ttyMT*`), that contradicts this finding and should
   override it.
2. **What is `/sys/devices/platform/odm/odm:gpio_dsp/mid_dsp`?** Found in
   the same hand-edited block as `ttyS1`/`ttyS2`. Could be audio-DSP-related
   (unrelated to motors) or could be a GPIO used for motor/board
   identification. Not resolved from static analysis; would need DTS
   decoding or a live `cat` of that sysfs node.
3. **Which `/dev/input/eventN` does `mtk-kpd` enumerate as, at runtime?**
   Confirmed static keycodes (§1) but not the live device node/name — needed
   to fully explain any app-layer "combo" logic for volume+/- that looks
   like non-standard encoding.
4. **Is the double-listed `/dev/video0-2` permission rule in vendor's
   `ueventd.rc` (§2) meaningful** (e.g. two camera sensors intentionally
   re-permissioned) or just copy-paste residue from OEM customization? Might
   matter to the camera-features agent.
5. **Where does the main Miko app (the one under investigation for
   camera/voice) actually live?** `/system/app/` (not `priv-app/`) has
   `MikoST.apk` (47KB — likely a small companion/stub, not the main app) and
   `game_robot_maker.apk` (46.8MB — large enough to be the main app, name
   suggests a "robot maker" activity/game). Neither was opened/analyzed here
   (app-layer is out of scope for this doc) — flagging both paths for the
   app-layer agent to confirm which is the actual main system app.
