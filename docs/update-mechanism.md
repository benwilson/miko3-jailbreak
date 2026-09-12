# Miko 3 Update Mechanism (reverse-engineered)

Source: decompiled `com.miko.update_app` (`/data/app/com.miko.update_app-*/base.apk`)
using jadx. Key classes:

- `com.miko.app_update.update.ProcessUpdates` — the whole update engine
- `com.miko.app_update.update.farrayCopy` / `fileCopy` — the `3_files.l` JSON model
- `com.emotix.arya.app_utils.FileUtils` — path helpers
- `com.emotix.arya.app_utils.crypto.KeyUtils` — encrypt/decrypt of `enc_*` files
- `com.miko.app_update.boot.BootloaderLibrary` — firmware (`*.ba`) flashing

## Update package layout

An OTA is a zip (`APPS.zip`) downloaded to
`/storage/emulated/0/klug/downloads/APPS.zip`. It contains:

| File | Purpose |
|------|---------|
| `3_files.l` | JSON listing: which file installs where + shell commands |
| `file1.ia` | APK payload (e.g. `com.example.root.serviceexam`) |
| `file2.ia` | APK payload (e.g. `com.miko.mikoplus`) |
| `klug_42.z` | zip: klug runtime + APPS content tree |
| `miko_recovery.z` | zip: recovery assets (`apps.json`, `node.p12`, sqlite dbs, `miko.properties`) |
| `version.txt` | version JSON |

The `.ia` files are **ordinary APKs** (zip with `AndroidManifest.xml`). The `.ia`
extension is only a marker for the installer — no encryption at the APPS.zip level.

`3_files.l` (real example from our unit):

```json
{
  "files": [
    {"target": "klug", "source": "klug_42.z"},
    {"target": "", "source": "miko_recovery.z"},
    {"target": "com.example.root.serviceexam", "source": "file1.ia"},
    {"target": "com.miko.mikoplus", "source": "file2.ia"}
  ],
  "commands": [
    "rm -f /sdcard/klug/audio/en_US/kw_no_input.m",
    "...",
    "mv /sdcard/klug/ftue/version.txt /sdcard/klug"
  ]
}
```

## The engine, step by step

`ProcessUpdates.processInstall()` → `processInstall(dir, true, 2)`:

1. **Unzip** `APPS.zip` into `/storage/emulated/0/klug/downloads/ENC/`
   (`getDownloadAppDir()` returns `.../klug/downloads/ENC`).
2. **List** files in that dir, drop directories and `*.kf`, then sort by the
   numeric comparator (files named `N…`, digit parsed from name[4]).
3. For each file whose extension is **`l`** → `processFiles(file)`.
4. `processFiles()` reads the JSON and walks `files[]`:

   | `source` extension | Action |
   |--------------------|--------|
   | `.z`  | unzip into `Environment.getExternalStorageDirectory() + target` |
   | `.ia` | `updateApp(file, target, 0)` → `UpdateSilent()` → **install APK as `target` package** |
   | `.ra` | `updateApp(..., 2)` → `UpdateSilent2()` → **uninstall `target`** |
   | `.ba` | **firmware flash** via `BootloaderLibrary.start_program_flash()` (copies to `/sdcard/miko_recovery/`, then flashes the MCU/bootloader) |
   | else  | `copyDirectoryOneLocationToAnotherLocation()` |

5. After all `files[]`, run each string in `commands[]` via
   `AppUtils.runCommands1()` (root shell).

### APK install path — the important part

`UpdateSilent(File file, String packageName)` has two modes:

```java
if (useShellCommands) {          // static flag, default FALSE
    // su -> cp <apk> /data/local/tmp/
    // su -> pm install -r -t /data/local/tmp/<apk>
    // su -> rm /data/local/tmp/<apk>
} else {
    PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
    SessionParams p = new SessionParams(MODE_FULL_INSTALL);
    p.setAppPackageName(packageName);          // <-- key: names the target package
    int id = pi.createSession(p);
    Session s = pi.openSession(id);
    OutputStream os = s.openWrite(packageName, 0, -1);
    // stream 64 KiB at a time, s.fsync(os), s.commit(PendingIntent...)
}
```

**`sessionParams.setAppPackageName(target)` is what makes this an *update* to an
existing package rather than a fresh install.** That matters on this MediaTek
build — see the mishap section below.

## Path map (from `FileUtils`)

| Helper | Path |
|--------|------|
| `getRootInternalStorage()` | `/storage/emulated/0/klug` |
| `getInternalStorage()` | `/storage/emulated/0/klug/APPS` |
| `getDownloadDir()` | `/storage/emulated/0/klug/downloads` |
| `getDownloadAppDir()` | `/storage/emulated/0/klug/downloads/ENC` |
| `checkProperties()` | `/storage/emulated/0/klug/miko.properties` must exist |

`apps.json` (the launcher's check file) lives at
`/storage/emulated/0/klug/APPS/apps.json` and
`/storage/emulated/0/klug/RECOVERY/apps.json`.

## The launcher check/launch path

`com.miko.launcher_app` reads `apps.json`, and for each entry with an `.apk`
source it parses `pkg/activity.Class.apk`:

```
baseName = "com.miko.mikoplus/com.miko.mikoplus.activity.appui.MikoActivity"
parent   = "com.miko.mikoplus"
name     = "com.miko.mikoplus.activity.appui.MikoActivity"
AppUtils.packageExists(ctx, parent)   // just checks the PACKAGE exists
```

If every listed package exists → launch the `"target":"true"` entry via
`AppUtils.openApp1(pkg/activity)`. If any is missing → `launchApps1` returns
false → `Status is false` / `Failure tag is 1` / **"showing error animation"**
(the "robo mishap" screen).

It then retries with `.../klug/RECOVERY/apps.json`; if that also fails it stays
on the error animation.

## Why our unit hit "robo mishap"

1. We deleted `/data/app/com.example.root.serviceexam-*` → ServiceExam gone.
2. ServiceExam is the *installer* for every child app (`installer="com.example.root.serviceexam"`
   in `packages.xml`), so removing it broke the app ecosystem.
3. Re-installing ServiceExam + clearing `packages.xml` caused the package manager
   to purge `/data/app/*`, which also deleted the **updated MikoPlus**
   (`com.miko.mikoplus`, versionCode 69) that contained
   `activity.appui.MikoActivity`.
4. Only the **system** MikoPlus (v11, `/system/app/file2/file2.apk`) remained — it
   has `activity.MainActivity` (factory diagnostic) but **not** `appui.MikoActivity`.
5. `apps.json` asks the launcher to open `appui.MikoActivity` → class/activity
   missing → `launchApps1` false → error animation.

## MediaTek package-manager bug (install crash)

Installing `file2.ia` as a *new* package crashes `system_server`:

```
*** FATAL EXCEPTION IN SYSTEM PROCESS: PackageManager
java.lang.NullPointerException: Attempt to get length of null array
    at com.mediatek.server.pm.PmsExtImpl.updatePackageSettings(PmsExtImpl.java:539)
    at com.android.server.pm.PackageManagerService.updateSettingsInternalLI(PackageManagerService.java:17078)
    at com.android.server.pm.PackageManagerService.installNewPackageLIF(PackageManagerService.java:16423)
```

`installNewPackageLIF` = the *new package* path. The first `pm install -r file2.ia`
worked when the system MikoPlus was still registered (update path); after
`pm uninstall com.miko.mikoplus` the package was de-registered and the next
install took the new-package path and crashed.

**Practical rule: never `pm uninstall` a preloaded system app on this device, and
always install updates with `setAppPackageName`/`-r` so the update path is used.**

## Reproducing the official update by hand

```bash
# 0. root ADB (factory mode or normal mode with persist.sys.usb.config=mtp,adb)
adb shell su 0 sh -c '
  D=/storage/emulated/0/klug/downloads
  E=/storage/emulated/0/klug/downloads/ENC
  mkdir -p "$E"
  # 1. unzip APPS.zip
  busybox unzip -o "$D/APPS.zip" -d "$E"
  # 2. unzip klug runtime + recovery to their targets
  busybox unzip -o "$E/klug_42.z" -d /storage/emulated/0/klug
  busybox unzip -o "$E/miko_recovery.z" -d /storage/emulated/0
  # 3. install the APK payloads AS UPDATES (target package named)
  pm install -r -t "$E/file1.ia"   # com.example.root.serviceexam
  pm install -r -t "$E/file2.ia"   # com.miko.mikoplus
  # 4. run the commands[] from 3_files.l
  mv /storage/emulated/0/klug/ftue/version.txt /storage/emulated/0/klug
'
```

Note: this mirrors `UpdateSilent`'s shell branch (`pm install -r -t`). The app's
default branch uses `PackageInstaller` + `setAppPackageName`, which is the safer
path for a *registered* package.

## Firmware (`*.ba`) — do not touch

`source` ending `.ba` is not an APK: it is a **firmware image flashed through the
MCU/bootloader** (`BootloaderLibrary.start_program_flash`), staged via
`/sdcard/miko_recovery/src.ba`. `firmware_flashing_in_progress` gates the wait
loop. Getting this wrong is a hard-brick class operation — leave it alone.

## Artifacts saved

- `recon/sources/miko-update-app-decompiled/` — key decompiled sources
- `recon/sources/miko-update-app-strings.txt` — URL/endpoint strings
- `docs/mishap-recovery.md` — the exact incident and recovery steps
