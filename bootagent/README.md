# Miko 3 boot agent

A tiny Android app that brings **root adb up on every normal boot** — over USB and
Wi-Fi (tcp 5555) — with the kiosk still running. It is the mechanism behind
`scripts/install-persistent-adb.py`; see `docs/plans/2026-09-11-2341-feat-persistent-adb-root-plan.md`
for the full design.

## What it does

On `BOOT_COMPLETED` the receiver execs `/system/bin/su` (world-exec setuid root on this
unit) and feeds it a payload that, in order:

1. materializes and starts **neuterd**, which `setns()`es into init's global mount
   namespace and shadows `/system/bin/reboot` with a no-op (self-healing every 5 s);
2. waits for the shadow to land — **before** adbd is touched, so the ServiceExam
   watchdog never sees an adbd it can act on;
3. sets `service.adb.tcp.port=5555` and `sys.usb.config=mtp,adb`;
4. starts a shell watcher that re-asserts the USB config and restarts adbd if
   ServiceExam reverts them;
5. sets `stay_on_while_plugged_in`.

The USB combo is set **directly** on `sys.usb.config` and never persisted, because
`/init.usb.rc` applies `persist.sys.usb.config` at boot and would start adbd before the
neuter exists.

## Provenance

`native/neuterd.c` is vendored **verbatim** from
[`ne3d-4-steve/miko3-adb-boot-agent`](https://github.com/ne3d-4-steve/miko3-adb-boot-agent)
(the `native/neuterd.c` source), with an attribution header added and the two `0755`
octal literals written as `0x1ED` to satisfy this repo's lint. The Java structure follows
that project's `BootReceiver`/`MainActivity`/`RootOps`. A research copy also lives under
`recon/sources/miko3-adb-boot-agent/`.

## Layout

| Path | Contents |
| ------ | ---------- |
| `AndroidManifest.xml` | `com.miko3.bootagent`; `BOOT_COMPLETED` receiver + a launcher activity |
| `src/com/miko3/bootagent/` | `BootReceiver`, `MainActivity`, `RootOps` (payload) |
| `native/neuterd.c`, `native/build-neuterd.sh` | the reboot-shadow daemon and its build |
| `miko3-bootagent.apk` | the committed, signed APK (normal use needs no SDK) |
| `miko3-bootagent.keystore` | the debug keystore that keeps the signature stable |
| `build/` | transient build intermediates (not committed) |

## Build

```bash
python3 scripts/build-bootagent.py            # bootstraps SDK + lld if missing
python3 scripts/build-bootagent.py --no-bootstrap
```

The build is byte-reproducible given the same keystore: zip timestamps are normalized
before signing.
