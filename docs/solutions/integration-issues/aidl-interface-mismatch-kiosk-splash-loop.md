---
title: "Restoring paired Android system apps from an OTA requires both, not one — the AIDL binder interface must match"
date: 2026-09-12
category: integration-issues
module: "Miko 3 kiosk boot (com.miko.mikoplus + com.example.root.serviceexam)"
problem_type: integration_issue
component: service_layer
symptoms:
  - "Kiosk boots but loops forever on the splash animation; it never reaches the home screen"
  - "Logcat shows SecurityException: Binder invocation to an incorrect interface at UIEventAIDL$Stub$Proxy.init"
  - "onServiceConnected fires, then immediately logs 'onServiceConnected in callback' from the catch block"
  - "dumpsys activity shows the looping fragment is BotRestartScreenUI (tag BOTRESTARTVIDEO_FRAGMENT)"
root_cause: incomplete_setup
resolution_type: dependency_update
severity: high
related_components:
  - "infrastructure"
  - "development_workflow"
tags:
  - "aidl"
  - "binder-interface"
  - "version-skew"
  - "kiosk-splash-loop"
  - "system-app-restore"
  - "android-bound-service"
applies_when:
  - "Two Android apps communicate over an AIDL bound service and only one of them was updated"
  - "A restored/backed-up app pair loops on a startup animation instead of erroring loudly"
  - "You are restoring preloaded system apps from an OTA payload and must decide which to install"
---

# Restoring paired Android system apps from an OTA requires both, not one — the AIDL binder interface must match

## Problem

The Miko 3 kiosk booted to Android and rendered its splash animation forever
without ever reaching the home screen. Nothing crashed, nothing logged a fatal
error, and the launcher reported success — the unit simply never left the
startup screen. The cause was an AIDL binder-interface mismatch between the two
preloaded system apps that make up the kiosk, produced by restoring only one of
the pair from the OTA payload.

## Symptoms

- The kiosk loops on the splash ("slot machine eyes") animation indefinitely.
- `dumpsys activity com.miko.mikoplus` shows the looping fragment is
  `BotRestartScreenUI`, tag `BOTRESTARTVIDEO_FRAGMENT`.
- `logcat` shows the binding succeeding and then failing in the callback:

  ```
  E AIDLProcess: onServiceConnected:
  E AIDLProcess: onServiceConnected in callback
  W System.err: java.lang.SecurityException: Binder invocation to an incorrect interface
      at com.root.aidlFiles.UIEventAIDL$Stub$Proxy.init(UIEventAIDL.java:136)
      at com.miko3.aidl_lib.AIDLProcess$sconn$1.onServiceConnected(AIDLProcess.kt:118)
  ```

- The launcher (`com.miko.launcher_app`) logs `opening the apps`, **not**
  `error in opening the apps`, so the app-level check passes and gives no hint.

## What Didn't Work

- **Treating it as a rendering or media problem.** The splash is a `VideoView`
  playing a looping video; the obvious first theories were a missing video
  resource or a decoder stall. Both were wrong — the video was playing fine at
  ~30 fps, which is exactly what the fragment is designed to do.
- **Restoring only MikoPlus.** The kiosk activity
  `com.miko.mikoplus.activity.appui.MikoActivity` lives only in the *updated*
  MikoPlus, so restoring that update was necessary — but not sufficient. The
  splash still looped.
- **`pm install` of the update payload as a fresh package.** Installing the
  MikoPlus update when the package was in a half-registered "hidden" state with
  empty signatures crashed `system_server`:
  `PmsExtImpl.updatePackageSettings` NPE on the new-package path
  (`installNewPackageLIF`). Installing via the **update** path (package already
  registered) works; the fresh-install path does not on this MediaTek build.
- **Assuming a crash would be visible.** There was no ANR, no `FATAL`, and no
  app restart. The failure is swallowed into a `catch` that logs one line and
  returns, which is why the loop looks like a hang rather than an error.

## Solution

Restore **both** apps from the same OTA payload, so their AIDL interfaces match.
The pair here is `com.miko.mikoplus` and `com.example.root.serviceexam`.

```bash
# 1. MikoPlus: recreate the original /data/app/ directory and re-insert the
#    original <package> entry (with <sigs>) into packages.xml, then reboot.
#    This avoids the installer entirely, so the MediaTek PM NPE never fires.
adb shell 'D=/data/app/com.miko.mikoplus-jejrc6689SElcf6hABYUJw==
  mkdir -p $D/lib
  cp /data/local/tmp/file2.ia $D/base.apk
  chmod 644 $D/base.apk; chmod 755 $D $D/lib; chown -R system:system $D'

# 2. ServiceExam: it is a registered system app, so the UPDATE path works.
adb shell 'pm install -r -t /data/local/tmp/file1.ia'   # system v40 -> update v92

adb reboot
```

Verify the pair is at matching versions before expecting the kiosk to open:

```bash
adb shell 'dumpsys package com.miko.mikoplus | grep -m1 versionCode'        # 69
adb shell 'dumpsys package com.example.root.serviceexam | grep -m1 versionCode'  # 92
```

## Why This Works

MikoPlus binds to ServiceExam's bound service (action `my.service`) and calls
`UIEventAIDL.init(...)` across it. The binder enforces that the interface
descriptor on both ends matches; when it does not, the call throws
`SecurityException: Binder invocation to an incorrect interface` and
`onServiceConnected` aborts before the handshake completes.

`BotRestartScreenUI` plays its video with `setLooping(true)` and is dismissed
only when `MikoActivity.endSplash(int)` calls `onServiceInitDone()` on it — and
`endSplash` is driven by that AIDL handshake. So a mismatched binder interface
does not produce an error screen; it produces an **infinite loop of the exact
animation that is supposed to cover the wait**. The system ServiceExam (v40)
exposes an older `UIEventAIDL` than MikoPlus v69 expects; installing the matching
ServiceExam update (v92) restores the contract.

The general rule this exposes: **an app that talks to a preloaded system app over
AIDL is only as correct as the older of the two versions.** An OTA that ships
both as a pair must be applied as a pair.

## Prevention

- **When restoring preloaded system apps from an OTA, restore every app the OTA
  ships, not just the one with the visible symptom.** Here the payload contained
  both `file1.ia` (ServiceExam) and `file2.ia` (MikoPlus); restoring only the
  second left the pair skewed.
- **Check the paired version numbers before declaring the restore done.** A
  matching-version assertion (`versionCode` of both apps) is the cheap guardrail.
- **Diagnose a silent startup loop by reading the AIDL callback path first.**
  This one grep is the definitive signal:

  ```bash
  adb shell "logcat -d | grep -A15 'onServiceConnected in callback'"
  ```

  `Binder invocation to an incorrect interface` means version skew between the
  two ends — go straight to comparing versions rather than chasing the UI.
- **Prefer the update install path for preloaded system apps.** `pm install -r`
  against an already-registered package works; a fresh install of a package
  whose registration is missing or hidden can crash `system_server` on this
  MediaTek build (`PmsExtImpl.updatePackageSettings` NPE). Restoring the
  original `<package>` entry plus its `/data/app/` directory sidesteps the
  installer entirely.

## Related Issues

- `docs/kiosk-recovery-fix.md` — the full incident and recovery procedure
- `docs/update-mechanism.md` — the OTA engine that ships the paired payloads
- `docs/mishap-recovery.md` — the earlier, distinct failure (launcher error
  screen) that preceded this one
- `docs/solutions/tooling-decisions/mediatek-preloader-meta-console-route.md` —
  how to reach the device at all, via the preloader META port
