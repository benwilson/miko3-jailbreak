# Miko 3 root-ADB boot hook

Hooks the kiosk's **own** startup to run a root payload — no `packages.xml` surgery,
no hand-registered APK, no `adb` install step. Derived from decompiling
`/system/app/launcher_alpha_V2-v4.2.apk` and `/system/app/file3/file3.apk` (the
update app). Supersedes the plan's KTD4 install path, which device QA disproved
(see `recon/captures/hand-registration-negative-*.txt`).

## The mechanism

`com.miko.launcher_app` declares `android.intent.category.HOME` — it is the kiosk
home screen, so **Android starts it on every boot, unattended**. Its `onCreate`
spawns `process1()` → `processInstall()`, which reaches the bundled Miko update
engine. That engine runs a listing file's `commands[]` through
`AppUtils.runCommands1()`:

```java
public static void runCommands(String str) {
    if (properties.enableSuCommand()) {          // returns TRUE unconditionally
        Process p = Runtime.getRuntime().exec("su");
        new DataOutputStream(p.getOutputStream()).writeBytes(str + ";\r\n");
    }
}
```

So the engine is a **root shell at boot**, and the listing format is trivial —
`{"files":[],"commands":["..."]}`; `files[]` may be empty.

## Two routes

### Route A — SD card (simplest, strongest persistence)

`getUpdateAppInstallDirectory()` returns `/storage/sdcard1/UPDATE_APP_INSTALL_DIR/`.
**Requires an SD card**, because the property that would redirect it
(`UPDATE_APP_DIR`) is dead code: `mikoProperties.init()` merges with

```java
if (properties2.getProperty(str) == null && !properties2.getProperty(str).trim().equals(""))
```

where `str` comes from `properties2.keySet()`, so the first test is never true and
`setProperty` never runs. `/sdcard/klug/miko.properties` is loaded and then
discarded. The card is also the best answer to "survives a manufacturer OTA" — it
is physically separate from the device.

```
UPDATE_APP_INSTALL_DIR/1_miko3.l      driver
miko3/payload.sh                      payload
miko3/neuterd                         arm64 daemon
```

### Route B — no SD card

`UpdateActivity.onCreate` branches only on the `LAUNCHED_BY` intent extra:

| Extra | Effect |
|---|---|
| `LAUNCHER_APP` | `launchedByLauncher = true` |
| empty (defaults to `FTUE`) | **`launchedByLauncher = false`** |
| `SERVICE` / `MOCK` | **`launchedByLauncher = false`** |

With `launchedByLauncher == false` the engine takes the branch that reads
**`/sdcard/klug/downloads/APPS.zip`** — writable, persistent emulated storage.

The launcher reaches that app through `apps.json`, and its launch call passes
`null` extras:

```java
AppUtils.openApp1(this, parent, false, name, null, checkIfAppInForeground());
```

so `LAUNCHED_BY` is absent, defaults to `FTUE`, and the APPS.zip branch runs.

```
/sdcard/klug/APPS/apps.json           repointed at com.miko.update_app
/sdcard/klug/downloads/APPS.zip       contains 3_files.l
/sdcard/klug/miko3/payload.sh         payload
/sdcard/klug/miko3/neuterd            arm64 daemon
```

Because `apps.json` then points at the updater instead of the kiosk, the payload
starts the kiosk itself with `am start`, so the display is unchanged.

## Persistence

Both payloads **re-arm themselves**: they rewrite the driver (`1_miko3.l` /
`APPS.zip`) as their last step, because the engine consumes the directory it read.
Route A's driver lives on the card; Route B's lives on emulated storage. Either way
the hook re-establishes itself on every boot without re-flashing anything.

Route A additionally survives a manufacturer OTA (separate physical medium). Route B
lives on `/sdcard`, which an OTA may rewrite.

## Status

- **Verified by decompilation**: the `HOME` category, the `onCreate` → `processInstall`
  call path, the unconditional `su`, the `LAUNCHED_BY` branches, the APPS.zip branch,
  the `.l` format, and the dead property merge.
- **Not yet verified on hardware.** The 2026-09-12 attempt could not exercise either
  route: Route A had no SD card inserted (`/storage/sdcard1` absent), and the test
  `.l` placed for Route B relied on the property override that turned out to be dead.
