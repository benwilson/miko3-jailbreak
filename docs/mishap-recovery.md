# The "robo mishap" incident — cause and recovery

## Symptom

Normal (non-factory) boot reaches Android, USB enumerates as `MIKO3` (PID
`0x2008`), but the display shows a static error screen ("Mishap… contact
support") instead of the kiosk. The launcher process is alive and rendering;
the kiosk never appears.

## Root cause (confirmed from logcat + decompiled launcher)

The launcher (`com.miko.launcher_app`) reads
`/storage/emulated/0/klug/APPS/apps.json`, and for each entry checks that the
**package** named in the source path exists, then launches the entry marked
`"target":"true"`:

```json
{"target":"true","source":"com.miko.mikoplus/com.miko.mikoplus.activity.appui.MikoActivity.apk"}
```

On our unit the launcher logged:

```
LAUNCHER_APP.MainAct: checkApps  launchApps1 false:true
LAUNCHER_APP.MainAct: Status is false
LAUNCHER_APP.MainAct: Failure tag is 1
LAUNCHER_APP.MainAct: Error in opening apps
LAUNCHER_APP.MainAct: showing error animation
```

`launchApps1` returns false → the launcher falls through to
`/storage/emulated/0/klug/RECOVERY/apps.json`, which fails the same way, then
shows the error animation. That animation **is** the "robo mishap" screen.

Why the launch failed:

1. The **system** MikoPlus (`/system/app/file2/file2.apk`, versionCode 11) does
   **not** contain `activity.appui.MikoActivity`. It only has
   `.activity.MainActivity` (a factory diagnostic page),
   `com.example.conexantapi.MainActivity`, and `com.emotix.gifplayer.MainActivity`.
2. The activity the kiosk needs lives in the **updated** MikoPlus
   (`file2.ia`, versionCode 69), which had been installed under
   `/data/app/com.miko.mikoplus-*` — and that directory was purged when we
   cleared `packages.xml`.

So the launcher asked for an activity that no longer existed on the device.

## The chain of events that broke it

1. We deleted `/data/app/com.example.root.serviceexam-*` (attempting to
   "disable the watchdog" by renaming). ServiceExam is the **installer of record**
   for every child app (`installer="com.example.root.serviceexam"` in
   `packages.xml`), so removing it broke the ecosystem.
2. We restored ServiceExam from `file1.ia` and, to force re-registration,
   cleared `/data/system/packages.xml` + `packages.list` and rebooted.
3. On the rebuild scan, the package manager **purged `/data/app/*`** — which also
   deleted the updated MikoPlus, all the child-app updates, and every `oat/`.
4. Only system versions remained. The kiosk activity was gone.

## Additional trap: MediaTek package-manager crash

Trying to reinstall the MikoPlus update as a *fresh* package kills `system_server`:

```
*** FATAL EXCEPTION IN SYSTEM PROCESS: PackageManager
java.lang.NullPointerException: Attempt to get length of null array
    at com.mediatek.server.pm.PmsExtImpl.updatePackageSettings(PmsExtImpl.java:539)
    at com.android.server.pm.PackageManagerService.installNewPackageLIF(...)
```

The MediaTek PM extension NPEs on the **new-package** path. The install only
succeeds on the **update** path (target package already registered). Practical
consequences:

- Never `pm uninstall` a preloaded system app here — you may not be able to
  reinstall it.
- Always install with `-r` (or `setAppPackageName`) so the update path is taken.

## Recovery procedure

Preconditions: root ADB in **factory mode** (see `docs/method-factory-root.md`).
Keep the update staged on the sdcard.

```bash
# 0. root ADB
adb shell id          # uid=0(root)

# 1. Make sure the update payload is staged
#    (it is already on the unit at /storage/emulated/0/klug/downloads/APPS.zip)

# 2. Unpack the runtime + recovery trees (order matters — runtime first)
adb shell 'D=/storage/emulated/0/klug/downloads
  E=/storage/emulated/0/klug/downloads/ENC
  mkdir -p "$E"
  busybox unzip -o "$D/APPS.zip" -d "$E"
  busybox unzip -o "$E/klug_42.z"     -d /storage/emulated/0/klug
  busybox unzip -o "$E/miko_recovery.z" -d /storage/emulated/0'

# 3. Restore the RECOVERY apps.json (launcher's fallback path)
adb shell 'mkdir -p /storage/emulated/0/klug/RECOVERY
  cp /storage/emulated/0/klug/RECOVERY/miko_recovery/apps.json \
     /storage/emulated/0/klug/RECOVERY/apps.json 2>/dev/null || true'

# 4. Install the APK payloads AS UPDATES (never uninstall first)
adb shell 'E=/storage/emulated/0/klug/downloads/ENC
  pm install -r -t "$E/file1.ia"    # com.example.root.serviceexam
  pm install -r -t "$E/file2.ia"'   # com.miko.mikoplus  -> adds appui.MikoActivity

# 5. Verify the kiosk activity now resolves
adb shell 'dumpsys package com.miko.mikoplus | grep appui.MikoActivity'
# expect: com.miko.mikoplus/.activity.appui.MikoActivity

# 6. If the watchdog would reboot you, neuter it first
adb shell 'echo "#!/system/bin/sh" > /data/local/tmp/nr; echo "exit 0" >> /data/local/tmp/nr
  chmod 755 /data/local/tmp/nr; mount --bind /data/local/tmp/nr /system/bin/reboot'

# 7. Restart the kiosk
adb shell 'am force-stop com.miko.mikoplus; input keyevent KEYCODE_HOME'
```

## Verifying each layer

| Check | Command | Expected |
|-------|---------|----------|
| ServiceExam installed | `pm list packages \| grep serviceexam` | `package:com.example.root.serviceexam` |
| MikoPlus updated | `dumpsys package com.miko.mikoplus \| grep versionCode` | `versionCode=69` |
| Kiosk activity present | `dumpsys package com.miko.mikoplus \| grep appui` | `appui.MikoActivity` |
| Runtime present | `ls /storage/emulated/0/klug/miko.properties` | exists |
| Launcher happy | `logcat -d \| grep LAUNCHER_APP \| tail` | no `showing error animation` |

## What NOT to do (learned the hard way)

1. **Don't rename/delete `com.example.root.serviceexam`.** It is the installer of
   record for every app. Neuter the watchdog by shadowing `/system/bin/reboot`
   instead (`docs/device-intel.md`, `neuterd` approach).
2. **Don't `rm /data/system/packages.xml`** to "force a rescan". It purges
   `/data/app/*`.
3. **Don't `pm uninstall` a system app** on this build — the reinstall hits the
   MediaTek PM NPE and can crash `system_server`.
4. **Don't assume `pm install` of a `.ia` is harmless.** It streams a 173 MB APK;
   on this 2 GB device it needs headroom. Free memory first, or install via the
   update path with the package already registered.

## Related docs

- `docs/update-mechanism.md` — full reverse-engineered OTA engine
- `docs/secrets-and-auth.md` — provisioning/auth, redacted
- `docs/device-intel.md` — watchdog (`ServiceExam`) and `su` details
- `secrets/README.md` — full secret values (local only)
