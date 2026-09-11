# Device intel — confirmed facts about our Miko 3

Sourced from our own enumeration **plus** the empirical notes in
`ne3d-4-steve/miko3-adb-boot-agent` (author verified on the same hardware).
Source copies under `recon/sources/miko3-adb-boot-agent/`.

## Hardware / OS
- **SoC: MediaTek MT8167** (quad Cortex-A35). VID 0x0E8D confirmed by us.
- **Android 9** (Pie).
- USB (normal Android): `MIKO3` / vendor `alps`, PID 0x2008, serial
  `MIKO3250XXM3Q0636CB`. Single vendor-specific interface (255/255/0), **no adb**.

## Security posture (this is the important part)
- **`/system/bin/su` is present and setuid-root, world-executable.** Any process
  can `exec /system/bin/su` to get a root shell. (Note: this `su` has **no `-c`**;
  feed commands via stdin.)
- **SELinux is Permissive** (`ro.secure=0`, `selinux=disable`) — root context can
  setns/mount freely.
- **dm-verity ENFORCING** on `/system` + `/vendor`; those are read-only, and
  their init is RO — so no persistent change via init/props/init.d.
- **adbd** is `disabled` in init and **USB-config-gated → OFF at boot**. No init
  trigger consumes `persist.adb.tcp.port`.
- **Watchdog: `com.example.ServiceExam`** — its `SecurityMonitor` greps `ps` for
  `adbd` every ~2s and, if found, execs `su -> reboot`. This is why naive "turn on
  adb" attempts cause reboots.

## Consequences for us
- Because `su` is setuid root + SELinux permissive, **the moment we get ANY shell
  on the device we have trivial root.** The entire challenge is getting that first
  shell — adb is off and must be enabled from the UI (keyboard method) or the
  bootloader opened (BROM, stalled on macOS).
- Persistent adb must **neutralize the ServiceExam watchdog**. The boot-agent does
  this correctly: a freestanding arm64 daemon (`neuterd`) enters **init's global
  mount namespace** (`setns /proc/1/ns/mnt`) and bind-mounts a no-op over
  `/system/bin/reboot`, self-healing. An app-side bind-mount would be invisible to
  ServiceExam (different mount ns) — hence the native global-ns daemon.
- Default persistent channel: **adb over TCP 5555** (`stay_on_while_plugged_in`
  set so the screen idle doesn't drop it).

## Boot-agent install flow (once first adb session exists)
1. `adb install -r openmiko-bootagent.apk`
2. `adb shell am start -n com.openmiko.bootagent/.MainActivity` (clears the
   post-install "stopped" state so BOOT_COMPLETED will fire).
3. Reboot → BootReceiver → `su` → neuterd (global-ns reboot shadow) → adb tcp 5555.
`install-permanent-adb.sh` automates this against `<ip>:5555`.
