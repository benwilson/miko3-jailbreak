# Boot-hook findings — replacing the hand-registration install path

**Date:** 2026-09-12 / 2026-09-13 · **Device:** `MIKO3250XXM3Q0636CB`
**Status:** mechanism confirmed by decompilation and partly by hardware; **no working install yet**
**Supersedes:** the install premise of `docs/plans/2026-09-11-2341-feat-persistent-adb-root-plan.md` (KTD4)

This records what was actually measured while trying to install the boot agent, so
none of it has to be re-derived. Three of the four sections are things that *do not
work* — those cost the most to find.

---

## 1. The install premise is false

The plan's KTD4 assumed PackageManagerService would register an APK hand-placed
under `/data/app/<pkg>-<suffix>==` on the next boot. **It does not.** Three attempts:

| # | Approach | Outcome |
|---|----------|---------|
| 1 | Place the APK under `/data/app` | not registered; **PMS deleted the directory** |
| 2 | + synthesized `<package>` entry (own cert DER inline at index 5, own `public-key` + `<keyset>` identifier 6, `lastIssued*` bumped) | PMS **parsed the file**, removed our entry *and* our keyset, kept the counter bump, deleted the directory |
| 3 | + every attribute corrected to match all 111 registered packages | **identical to attempt 2** |

Attempt 3 is the informative one: it matched `nativeLibraryPath`, `privateFlags`,
`ft`/`it`/`ut`, `isOrphaned`, `publicFlags` (copied from the other `/data/app`
packages) and correctly omitted `primaryCpuAbi` and `sharedUserId` — and changed
nothing. **Attribute shape is not the discriminator.** The remaining difference is
that every registered package references a `<cert>`/`<keyset>` pair PMS created
itself; ours were hand-authored.

**No PMS observability exists in factory mode** — no persistent logd, `logcat -L`
empty, nothing in `/data/system/dropbox` names the package. The one vantage point
that would show its reasoning is a root adb session on a normal boot, which is the
thing this work exists to create.

**Safety record: all three failed cleanly.** `packages.xml` stayed well-formed and
lost only our entry; `/data/app` kept exactly the two kiosk apps at v69/v92.

### What a valid entry would need
`<cert index="N" key="…"/>` holds the certificate's **DER bytes**
(observed `key="308204a8 30820390 a0030201 …"`), **not** a SHA-256 digest — a
synthesized entry that writes a digest is writing the wrong thing into a
boot-critical file. All 111 entries also carry `<proper-signing-keyset>`.

---

## 2. The boot hook — what actually works

`com.miko.launcher_app` (`/system/app/launcher_alpha_V2-v4.2.apk`, v4, system UID)
declares **`android.intent.category.HOME`** — it *is* the kiosk home screen, so
Android starts it on every boot, unattended.

```
boot → HOME → MainActivity.onCreate → new Thread(() -> process1())
     → processInstall() → Miko update engine → listing commands[] via
       AppUtils.runCommands1() → Runtime.exec("su")   ← ROOT
```

`runCommands1` is a genuine root shell, gated only on `enableSuCommand()`, which
**returns `true` unconditionally** (`mikoProperties.java`). The listing format is
trivial and `files[]` may be empty:

```json
{"files":[],"commands":["…"]}
```

`processFiles` returns early only when `files` is `null` — an empty array is fine,
and its `commands[]` loop runs regardless.

### Two routes

**Route A — SD card (no unzip, no network).** `getUpdateAppInstallDirectory()`
returns `/storage/sdcard1/UPDATE_APP_INSTALL_DIR/`. The launcher calls the engine
**directly** from `onCreate`:

```java
ProcessUpdates.launchedByLauncher = true;
ProcessUpdates.updateAppInstallation = true;
this.pu.processInstall();     // -> reads *.l in that directory -> commands[] -> su
```

If exactly **one** `.l` is present, the launcher instead hands off to
`com.miko.update_app` — the system-UID installer — which is the "install it
properly" path.

**Route B — no SD card (network-dependent, and it does not currently work).**
`apps.json` → updater launched with no extras → `LAUNCHED_BY` defaults to `FTUE` →
`launchedByLauncher = false` → the engine takes the branch that reads
`/sdcard/klug/downloads/APPS.zip`.

---

## 3. Why Route B fails — three separate, localised blockers

### 3a. A `version.txt` advertising the installed version stalls it
`UpdateActivity` compares the archive's version against what is installed. The
unit's real `version.txt` has `"version_code": "42"`, and the payload is
literally `klug_42.z` — **42 is the current version**. Including it made the app
conclude "already up to date" and sit at **0%**, never reaching the engine.
Omitting it restored the engine path. *This was my own error, introduced while
"fixing" a difference that was load-bearing.*

### 3b. The update app's flow is cloud-gated
`pu.processInstall()` in `UpdateActivity` is reached only from `updateProcess()`,
which is gated on a `"DOWNLOADS"` state inside the app's own update flow. That flow
runs `APIS.init(...)` and a bot-details fetch against Miko's servers first.

### 3c. The unzip extracts nothing — the current blocker
Across two attempts with two different archives, `unzipUsingLibray1()` (zip4j)
produced **nothing** in `/sdcard/klug/downloads/ENC/`; only the engine's own
`enc_k.kf` / `enc_m.kf` appeared. With no other files, the listing is empty and the
engine calls `downloadStatus(0, 0, -22)` → **"Oops, the update failed."**

`unzipUsingLibray1` is a plain zip4j extraction with no password and no
encryption requirement, and `file1.ia` / `file2.ia` / `klug_42.z` on the device all
begin `50 4b 03 04` — plain ZIPs. So the routine *should* work; why it does not is
**not determined**.

**Route A has none of these three problems** — it never unzips and never touches
the network.

---

## 4. Dead ends (do not re-tread)

- **`mikoProperties.init()` merge is dead code.** It merges with
  `if (getProperty(str) == null && !getProperty(str).trim().equals(""))` where `str`
  comes from `keySet()`, so the first test is never true and `setProperty` never
  runs. **Every `/sdcard/klug/miko.properties` override is loaded and discarded** —
  `EXTERNAL_DIR`, `UPDATE_APP_DIR`, `RLOGS*`, all of them.
- **`checkApps` has an inverted guard.** `return !launchApps1(external…) || …` — a
  *missing* `/storage/sdcard1/APPS/apps.json` makes the whole expression `true`, so
  it succeeds rather than failing and **`recoveryInstall()` is unreachable**. That
  was the one path reading a writable directory (`/sdcard/klug/RECOVERY/`).
- **`apps.json` only checks-and-launches.** `readApps` verifies packages exist;
  `launchApps` opens the first `target":"true"` entry via `openApp1(parent, name)`
  with **`null` extras**. There is no install path.
- **`/storage` is tmpfs** (`tmpfs /storage tmpfs … 991644K, 0 used`), so
  `/storage/sdcard1` cannot be created persistently. Every launcher-driven hook path
  needs a physically inserted card.
- **The update app has no boot trigger.** `/system/app/file3` has no boot receiver
  and no service — two activities, a Picasso provider, and a download-library
  notification receiver.

---

## 5. Device facts measured (reusable)

| Fact | Value |
|---|---|
| Normal boot | PID `0x2008`, product `MIKO3`, interface triplet **`ff/ff/00`** → **no adb** |
| Factory mode | PID `0x2006` / `mt6763`; `id -u` 0; **no package manager** (`pm path` → "Can't find service: package") |
| Factory-mode neuter | `/system/bin/reboot` = 24 bytes, first bytes `23 21 2f 73` (`#!/s`) |
| `ro.adb.secure` | unset |
| `adb_keys` | **absent** pre-install |
| Kiosk apps | `com.miko.mikoplus` v69, `com.example.root.serviceexam` v92 |
| State files | `packages.xml` 1,109,401 B; `package-restrictions.xml` 9,148 B; both `660 system:system` |
| Real OTA pieces | `/data/local/tmp/`: `file1.ia` (106 MB), `file2.ia` (173 MB), `klug_42.z` (185 MB), `miko_recovery.z` |
| OTA crypto | `/sdcard/klug/OTA_1100/`: `aes_key_security_miko3.bin`, `key_for_command.bin` (32 B each); AES-256-CBC per `testing_all_in_one.sh` |
| Real `3_files.l` | `files[]` populated with `{target,source}` pairs; `commands[]` are plain shell (`rm`, `mv`) |
| `version.txt` | `{"app_id":"1","version_code":"42","update":"8.3",…}` |

**Two measurement traps hit in this session:**
- **`wc -l` on `adb_keys` returns 0 for a correct write.** Adb public keys have no
  trailing newline, so a single-key file counts zero lines. Use **`wc -c`**. This
  aborted a live install and was fixed in `ac9c9a9`.
- **`aoa-inject.py` needs its venv wrapper** (`scripts/aoa-inject.sh`), and macOS
  `awk` lacks gawk's `match(…, array)` form.

---

## 6. Environment findings (not device-specific)

- **A subagent dispatched with `model: "sonnet"` fails here.** It resolves to
  `claude-sonnet-5`, which returns HTTP 404 `model_not_found`. The failure is
  **silent**: the agent stalls at ~35 KB of transcript and never returns. Six
  subagents died this way across the doc-review and simplify passes; re-dispatching
  the identical prompt **without** the override fixed every one. Diagnose a
  "slow" subagent by comparing transcript size and mtime against its peers' —
  frozen at a uniform size means dead, not slow.
- **USB contention is real and documented** (`docs/gotchas.md:17`): browser WebUSB
  and native libusb/adb are mutually exclusive. A wedged adb transport (device
  shows `offline`, every shell call hangs) is cleared by a physical replug.

---

## 7. Where to go next

**Route A is the sound design and is blocked only on hardware — a microSD card.**
It is not a workaround: it never unzips, never talks to the network, calls the
engine directly at boot, and the card is physically separate from the device so a
manufacturer OTA cannot remove it.

Artifacts are committed:
- `hooks/sd/` — Route A (`1_miko3.l`, `payload.sh`, `neuterd`)
- `hooks/sd-free/` — Route B (`APPS.zip`, `3_files.l`, `payload.sh`, `neuterd`)
- `hooks/deploy.sh` — deployment, with `--sd` and `--verify`
- `hooks/README.md` — the decompiled reasoning for both routes

If Route B is pursued further, the open question is narrow and specific:
**why does `unzipUsingLibray1` extract nothing from our archive?** Both archives
that failed were built with macOS `zip`; a host-side reproduction against zip4j, or
an archive built with a different tool, would settle it.
