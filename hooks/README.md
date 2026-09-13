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

### Route B — no SD card (weaker; network-dependent)

`UpdateActivity.onCreate` branches only on the `LAUNCHED_BY` intent extra:

| Extra | Effect |
|---|---|
| `LAUNCHER_APP` | `launchedByLauncher = true` |
| empty (defaults to `FTUE`) | **`launchedByLauncher = false`** |
| `SERVICE` / `MOCK` | **`launchedByLauncher = false`** |

With `launchedByLauncher == false` the engine takes the branch that reads
`/sdcard/klug/downloads/APPS.zip` — writable, persistent emulated storage — and the
launcher reaches that app through `apps.json`, whose launch call passes `null`
extras (`AppUtils.openApp1(this, parent, false, name, null, …)`).

**Caveat found after writing this: `pu.processInstall()` is not reached directly.**
In `UpdateActivity` it is called only from `updateProcess()`, which is gated:

```java
if (str.equals("DOWNLOADS") && z) { updateProcess(); }   // -> pu.processInstall()
```

`str` is a state in the app's update flow, which fetches bot details from Miko's
servers (`prod-userproperties.miko-robot.in`). So Route B **depends on that network
flow**, and the app may overwrite `APPS.zip` from its own download before processing
it. Route A has neither problem — the launcher calls the engine directly at boot.

Treat Route B as a fallback to try only if Route A cannot be used.

## Persistence

Both payloads **re-arm themselves**: they rewrite the driver (`1_miko3.l` /
`APPS.zip`) as their last step, because the engine consumes the directory it read.
Route A's driver lives on the card; Route B's lives on emulated storage. Either way
the hook re-establishes itself on every boot without re-flashing anything.

Route A additionally survives a manufacturer OTA (separate physical medium). Route B
lives on `/sdcard`, which an OTA may rewrite.

## Status

Full technical findings — including the four dead ends and the device facts
measured — are in **`docs/boot-hook-findings.md`**. Summary:

- **Route A**: mechanism confirmed by decompilation; **never blocked on anything
  but hardware.** Not yet exercised (no SD card was inserted). It never unzips and
  never touches the network, so it avoids every failure Route B hit.
- **Route B**: **three separate blockers, and it does not currently work.**
  1. A `version.txt` advertising `version_code: 42` — the already-installed
     version — made the app stall at 0% before reaching the engine.
  2. The updater's flow is cloud-gated (`APIS.init`, bot-details fetch).
  3. **Current blocker:** `unzipUsingLibray1` (zip4j) extracts nothing from our
     `APPS.zip`; `ENC/` ends up holding only the engine's own `enc_k.kf`/`enc_m.kf`,
     so the listing is empty and the engine reports `-22` → "Oops, the update
     failed". Why the extraction yields nothing is **undetermined**.

If Route B is pursued, the narrow open question is #3. Both archives that failed
were built with macOS `zip`; a host-side zip4j reproduction, or an archive built
with a different tool, would settle it.

## Deployed-and-restored log

Every device write in this work has a timestamped backup beside it:

- `apps.json.pre-hook-<stamp>` / `.pre-hook2-` / `.pre-hook3-` in `/sdcard/klug/APPS/`
  and `/sdcard/klug/RECOVERY/`
- `miko.properties.pre-hook-<stamp>` in `/sdcard/klug/`

Restore with `cp <file>.pre-hook3-<stamp> <file>`, then reboot.
