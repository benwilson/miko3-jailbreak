# Kiosk recovery — the actual fix (2026-09-12)

Follow-up to `docs/mishap-recovery.md`. That doc described the symptom and the
general recovery shape. This one records the **exact fix that worked**, including
the real root cause we only found after decompiling MikoPlus.

## Root cause (final)

Two independent breakages had to both be repaired:

1. **MikoPlus update was purged.** The kiosk activity
   `com.miko.mikoplus.activity.appui.MikoActivity` lives only in the *updated*
   MikoPlus (v69), which had been installed at
   `/data/app/com.miko.mikoplus-jejrc6689SElcf6hABYUJw==/`. Clearing
   `packages.xml` purged that directory, leaving only the **system** MikoPlus
   (v11) at `/system/app/file2`, which lacks `appui.MikoActivity`.

2. **AIDL interface mismatch between MikoPlus and ServiceExam.** Even after
   MikoPlus v69 was restored, the kiosk still looped on the splash video. Logcat
   showed the real reason:

   ```
   E AIDLProcess: onServiceConnected:
   E AIDLProcess: onServiceConnected in callback
   W System.err: java.lang.SecurityException: Binder invocation to an incorrect interface
       at com.root.aidlFiles.UIEventAIDL$Stub$Proxy.init(UIEventAIDL.java:136)
       at com.miko3.aidl_lib.AIDLProcess$sconn$1.onServiceConnected(AIDLProcess.kt:118)
   ```

   MikoPlus v69 binds to ServiceExam's `MyService` (action `my.service`) and
   calls `UIEventAIDL.init(...)`. The **system** ServiceExam (v40) exposes an
   older `UIEventAIDL` whose binder descriptor doesn't match, so the call throws
   and `onServiceConnected` aborts before the init handshake completes.

   MikoPlus's `BotRestartScreenUI` plays its video with `setLooping(true)` and
   only stops when `MikoActivity.endSplash()` → `onServiceInitDone()` is called,
   which is driven by that AIDL handshake. No handshake → **infinite splash**.

**Both apps must be the matching versions from the same `APPS.zip`.** Restoring
only one is not enough.

## The fix

### Step 1 — restore MikoPlus v69 into its original `/data/app/` path

The original `packages.xml` (backup) told us the exact path and metadata:

```xml
<package name="com.miko.mikoplus"
  codePath="/data/app/com.miko.mikoplus-jejrc6689SElcf6hABYUJw=="
  nativeLibraryPath="/data/app/com.miko.mikoplus-jejrc6689SElcf6hABYUJw==/lib"
  primaryCpuAbi="arm64-v8a" publicFlags="676904645" privateFlags="0"
  ft="1a08d005b70" it="188a5db24eb" ut="1a08d00601f" version="69"
  sharedUserId="1000" installer="com.miko.update_app" isOrphaned="true">
    <sigs count="1" schemeVersion="2"><cert index="0" /></sigs>
    <perms> ... </perms>
    <proper-signing-keyset identifier="1" />
</package>
```

Steps:

```bash
# a) recreate the exact directory with the update APK (file2.ia is a plain APK)
adb shell 'D=/data/app/com.miko.mikoplus-jejrc6689SElcf6hABYUJw==
  mkdir -p $D/lib
  cp /data/local/tmp/file2.ia $D/base.apk
  chmod 644 $D/base.apk; chmod 755 $D $D/lib
  chown -R system:system $D'

# b) insert the original <package> block into /data/system/packages.xml
#    (extracted verbatim from the backup; inserted before </packages>)
adb pull /data/system/packages.xml /tmp/packages.current.xml
#   -> insert block, then:
adb push /tmp/packages.new.xml /data/system/packages.xml
adb shell 'chown system:system /data/system/packages.xml; chmod 660 /data/system/packages.xml'

# c) reboot
adb reboot
```

After reboot:

```
dumpsys package com.miko.mikoplus | grep -E 'versionCode|appui'
#   b9c004e com.miko.mikoplus/.activity.appui.MikoActivity filter a0a75ef
#   versionCode=69
pm path com.miko.mikoplus
#   package:/data/app/com.miko.mikoplus-jejrc6689SElcf6hABYUJw==/base.apk
```

### Step 2 — install the matching ServiceExam update (v92)

The system ServiceExam is v40; the update payload `file1.ia` is v92. Because
ServiceExam is a registered system app, the update install takes the **update**
path (no MediaTek PM NPE):

```bash
adb shell 'pm install -r -t /data/local/tmp/file1.ia'
#   Success
pm path com.example.root.serviceexam
#   package:/data/app/com.example.root.serviceexam-i7wVMoIJW0lO8uJ_B0RCDA==/base.apk
dumpsys package com.example.root.serviceexam | grep versionCode
#   versionCode=92
```

### Step 3 — reboot

After both updates are in place, the AIDL handshake succeeds, `endSplash` runs,
the looping video is dismissed, and the kiosk opens normally.

## Result

Normal boot now reaches the working kiosk (`activity.appui.MikoActivity`), the
launcher logs `opening the apps` instead of `showing error animation`, and there
are no crashes.

**ADB is off in this normal state** (USB config is MTP-only once ServiceExam v92
drives it). To get root again, power-cycle and run `scripts/factory-root.sh`
(META → FACTFACT).

## Why the earlier "reinstall via pm" attempts failed

- `pm install -r file2.ia` as a *fresh* package → `system_server` crash:
  `PmsExtImpl.updatePackageSettings` NPE on the new-package path
  (`installNewPackageLIF`). The package must already be registered.
- The system MikoPlus was in a **hidden** state with **empty signatures**
  (`signatures=[]`), which is what fed the NPE. Manually restoring the original
  `<package>` entry (with `<sigs><cert index="0"/></sigs>`) plus the matching
  `/data/app/` directory avoids the installer entirely.

## Diagnostic that found it

`logcat -d | grep -A15 'onServiceConnected in callback'` — the
`SecurityException: Binder invocation to an incorrect interface` on
`UIEventAIDL.init` is the definitive signal that the two app versions don't
match. If you ever see the splash loop again, check this first.
