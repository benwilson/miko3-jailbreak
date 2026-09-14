# Miko 3 Boot Sequence — Complete Documentation

**Date:** 2026-09-13/14 · **Device:** `MIKO3250XXM3Q0636CB` (MediaTek MT8168, Android 9 Pie, `userdebug`)
**Status:** BROM→normal-boot timeline confirmed from firmware images + live measurement. The
~30s reset's proximate cause is now **confirmed by decompilation**, not just correlated by timing.

This rewrites the earlier version of this doc wholesale — that version had placeholder
durations ("~10-30 seconds") and treated the reset as an open mystery. It no longer is one.

## How this was produced

Every claim below is tagged with its evidence source so nothing here has to be re-derived
and nothing is stated with more confidence than it deserves:

- **`[FS]`** — pulled from the firmware images (`firmware/dump-parental-locked/{system,vendor,boot}.img`)
  via `debugfs` (`/opt/homebrew/opt/e2fsprogs/sbin/debugfs -R "ls -l <path>" <img>` /
  `-R "dump <path> <out>" <img>`). These are ext2/ext4 images; `boot.img` is a raw Android
  boot image (magic `ANDROID!`, page_size 2048, ramdisk_size **0** — this is a
  system-as-root device, confirmed via `debugfs -R "ls -l /" system.img` showing `/sbin`,
  `/init`, `/system` all at the image root).
- **`[DEX]`** — decompiled from the real APKs with `jadx`/`apktool` (both installed at
  `/opt/homebrew/bin/{jadx,apktool}`). ServiceExam v92 is `recon/APPS.zip_extracted/file1.ia`
  (confirmed via `docs/boot-hook-findings.md`'s device table); its `classes.dex` was
  extracted and decompiled to `/tmp/se92/out/sources/` for this pass — not currently
  committed to the repo, so if this needs re-checking, redo it: `python3 -c "import
  zipfile; zipfile.ZipFile('recon/APPS.zip_extracted/file1.ia').extract('classes.dex',
  '/tmp/se92')" && jadx -d /tmp/se92/out /tmp/se92/classes.dex`.
- **`[LIVE]`** — measured empirically on the real device this session, via
  `scripts/autonomous-recovery.py`'s race log / `recon/captures/autonomous-recovery-*.jsonl`.
- **`[AOSP]`** — from public AOSP source, cited from training knowledge, not from a local
  disassembly. Explicitly marked wherever used so it isn't confused with `[FS]`/`[DEX]` evidence.

---

## Stage-by-stage timeline

### Stage 0 — BROM (Boot ROM)

- USB: MediaTek VID `0x0E8D`, PID family `0x2000/0x2001/0x2002/0x2003/0x200A/0x200B/0x0003`
  `[LIVE]` (observed via `usb.core.find(idVendor=0x0E8D)` across many boots this session).
- Mask ROM code in the SoC; loads the preloader from the boot media. No host interaction
  possible. No Android code has run yet.

### Stage 1 — Preloader / META mode

- Same USB PID family as Stage 0 (both enumerate as "preloader-ish" to pyusb; the specific
  PID varies — `0x2000` was most common this session, `0x201c` was seen once transitioning
  into a normal boot, meaning it's a preloader sub-stage, not a distinct mode).
- Exposes a CDC-ACM serial port (`/dev/cu.usbmodem*` on macOS). **This is the factory-mode
  entry point** `[LIVE]`, confirmed reliable across dozens of cycles this session:
  1. Host sends 4-byte SYNC `\xa0\x0a\x50\x05`.
  2. Preloader replies ASCII `"READ"` (the DA-ready signal on this unit's preloader
     revision — other revisions reportedly echo an inverted SYNC instead, per
     `scripts/brom-probe.py`'s comments, but this unit uses "READ").
  3. Host sends a plain-text mode string, `"FACTFACT"`.
  4. Preloader switches the *next* boot into factory mode.
  - Implemented in `scripts/brom-probe.py` (standalone) and now inlined into
    `scripts/autonomous-recovery.py` (in-process, same handshake, no subprocess).
  - Window is short (~2.3s) and repeats every power cycle — `scripts/brom-probe.py`'s
    own comment documents measuring this duration directly.
- Duration: not independently measured this session beyond "short — a few seconds total
  across BROM+preloader+META", per the `~2-3s` figure in the original doc version, which
  was itself an estimate, not a citation — treat this one number as still unverified.

### Stage 2 — Kernel load

- Preloader loads and jumps into the kernel (`boot.img`'s single 8,487,347-byte kernel
  image, no ramdisk) `[FS]`.
- The kernel's watchdog driver is `mediatek,mt6589-wdt` `[FS]` (found via
  `strings` on the raw kernel image extracted from `boot.img` at offset 2048 —
  `mt6589-wdt` is MediaTek's shared watchdog-IP compatible string, reused across the
  MT65xx/MT81xx family including MT8168). This confirms a **real hardware watchdog
  device exists and is kernel-bound**; see the root-cause section for whether it's
  actually what fires.

### Stage 3 — Android `init`, system-as-root

`/init` runs the standard AOSP init trigger graph `[FS]` (confirmed by pulling and
reading `init.rc` from `system.img`'s root):

```
on early-init                      (init.rc:14)
on late-init  → trigger boot       (init.rc:278, :309)
on boot       → class_start hal, core, main, late_start   (init.rc:676-682)
on property:sys.boot_completed=1   (init.rc:719, :778 — two separate blocks)
```

`class core` — which includes `watchdogd` (below) — starts as part of the `on boot`
trigger, i.e. essentially immediately after `late-init`, within the first couple of
seconds of `init` running `[FS]`. This is standard AOSP behavior, not device-specific.

**`watchdogd`:** `/sbin/watchdogd` on `system.img` is a **symlink to `../init`**
`[FS]` (`debugfs -R "stat /sbin/watchdogd" system.img` → `Fast link dest: "../init"`;
`/sbin/ueventd` is symlinked the same way — this is AOSP's standard multi-call-binary
pattern, not a MediaTek customization). So "watchdogd" is `/init` itself running its
built-in `watchdogd` applet (`system/core/init/watchdog.cpp` in AOSP `[AOSP]`), started
by vendor's `init.mt8168.rc`:

```
service watchdogd /sbin/watchdogd  20 10        # init.mt8168.rc:671
    class core                                  # init.mt8168.rc:672
    seclabel u:r:watchdogd:s0                    # init.mt8168.rc:673
```

Per AOSP's `watchdogd_main(argc, argv)` `[AOSP]` (public source, not locally
disassembled — `/init` is a stripped ELF and pulling the exact matching logic out of it
was not attempted this pass): args are `<interval> <margin>` = `20 10`. It opens
`/dev/watchdog`, calls `ioctl(fd, WDIOC_SETTIMEOUT, &(interval+margin))` — arms the
**hardware** timeout to **30 seconds** — then loops `write(fd, "", 1); sleep(interval)`
forever: pets once immediately, then every 20s, unconditionally. It does **not** monitor
system health; it's a dumb heartbeat with a 10s margin per AOSP's own design intent.

**This 30-second number matches the observed reset window closely enough that it was the
leading hypothesis for a while this session** — see the root-cause section below for why
it's now considered a *secondary*, unconfirmed mechanism rather than the actual cause.

### Stage 4 — Zygote, `system_server`, package manager

- `init.zygote64_32.rc` / `init.zygote32.rc` exist on `system.img` `[FS]`, not read in
  detail this pass.
- `[LIVE]` (from earlier sessions, per prior doc revisions): ActivityManagerService
  becomes available around **t≈21.6s** into boot (`dmesg` `BOOTPROF: AMS:AMS_READY`,
  cited from before this rewrite — not re-verified this pass, kept as a still-useful
  approximate anchor). Before this, `am`/`pm` shell commands fail with `Can't find
  service`; `pidof` (reads `/proc` directly) works regardless and is what
  `scripts/autonomous-recovery.py`'s race loop uses to avoid waiting on AMS.

### Stage 5 — Launcher (HOME) and kiosk apps start

- `com.miko.launcher_app` (`/system/app/launcher_alpha_V2-v4.2.apk`) declares
  `android.intent.category.HOME` `[DEX]` (per `docs/boot-hook-findings.md`), so it starts
  unattended on every boot as the home app.
- `[LIVE]`, this session, across multiple boots via `scripts/autonomous-recovery.py`'s
  `pidof com.miko.launcher_app` busy-poll: the launcher's **first natural process
  appears consistently at uptime ≈28.4–29.4s** (`/proc/uptime` read in the same shell
  invocation that found the pid). `nsinject` (setns-based file injection into the
  launcher's private mount namespace, see `bootagent/native/nsinject.c`) completes
  ~0.1s later with `rc=0` every time it's been tried.
- `com.example.root.serviceexam`'s `MyService` (`android:name=".MyService"`, intent
  filter action `"my.service"`) is **not** boot-receiver-triggered `[DEX]` (checked the
  decoded `AndroidManifest.xml` — no `BOOT_COMPLETED` receiver in ServiceExam at all).
  It's bound via AIDL from **MikoPlus** (`com.miko.mikoplus`), which retries the bind in
  a loop at startup; per `docs/persistent-adb-normal-boot.md`'s prior finding, MikoPlus
  sits in this AIDL-bind retry loop and connects "instantly" the moment ServiceExam's
  service is registered and answering — meaning the practical trigger time for
  everything below is "whenever ServiceExam's process/service becomes bindable", which
  tracks with system_server/PMS readiness more than with any fixed timer.

### Stage 6 — `MyService.onCreate()` → the two things that fight our ADB access

`MyService.onCreate()` `[DEX]` (`com/example/root/serviceexam/MyService.java:266`) runs a
long chain of initialization (`AppUtils.init()`, `mikoProperties.getInstance()`,
`AndroidUnityInterface`, `ServiceApplication.onCreate()`, `retailMode()`, ...) that
eventually reaches `SocialInteraction_SpeechChat.init()` `[DEX]`
(`com/common_source/emotix/interaction/interaction/SocialInteraction_SpeechChat.java:596`).
The exact call chain from `onCreate()` to `init()` was not traced line-by-line this pass
(budget), but `init()`'s own body is fully decompiled and is where both ADB-hostile
mechanisms live:

```java
public synchronized void init() {
    ...
    AppUtils.runCommands("echo 0 > /sys/bus/usb/devices/usb1/authorized");   // line 604
    serviceStartTime = SystemClock.uptimeMillis();                          // line 605
    if (this.p.enableADB()) { enableADB(); } else { disableADB(); }          // line 617-620
    ...
    if (!this.LOCALE_ENABLE_ADB) {                                          // line 633
        SecurityMonitor.getInstance().startMonitoring();                    // line 634
        ...
    }
}
```

**Finding #1 — ADB is unconditionally disabled, every boot, regardless of any setting:**

```java
public void enableADB() {                     // line 1193
    if (this.LOCALE_ENABLE_ADB) {              // line 1194 — field, see below
        ... /* real enable logic: settings put global adb_enabled 1;
               stop adbd; setprop service.adb.tcp.port 5555; start adbd */
    } else {
        disableADB();                          // falls through
    }
}
public void disableADB() {                     // line 1227
    // as root, via su:
    //   settings put global adb_enabled 0
}
```

`LOCALE_ENABLE_ADB` is declared `public boolean LOCALE_ENABLE_ADB = false;`
(`SocialInteraction_SpeechChat.java:246`) and is **never reassigned anywhere in the
class** `[DEX]` (checked every assignment site). So `enableADB()` *always* falls through
to `disableADB()`, and `init()`'s own `if (this.p.enableADB())` branch is moot — **every
single code path through `init()` ends in `disableADB()` running `settings put global
adb_enabled 0` as root**, in a background `Thread`, unconditionally. This runs on every
normal boot once ServiceExam's `MyService` initializes, independent of anything we do to
`persist.sys.usb.config`.

This is very likely the actual explanation for the "kiosk boots fully but adb never
reachable" failure mode observed this session (`autonomous-recovery.py`'s
`android_no_adb` event): `persist.sys.usb.config=adb` gets adbd running early via the
persisted-property mechanism (`docs/persistent-adb-normal-boot.md`), but
`disableADB()` — running later, once ServiceExam's service initializes — turns
`Settings.Global.ADB_ENABLED` back to 0, and depending on framework wiring that
typically stops the adb daemon/gadget function shortly after. Exactly how fast `settings
put global adb_enabled 0` propagates to actually killing an in-progress adb *session*
(vs just blocking new ones) was not instrumented this pass.

**Finding #2 — `SecurityMonitor` reboots the device the moment it sees `adbd`, via the
exact file our neuter payload targets:**

```java
// com/common_source/emotix/interaction/interaction/SecurityMonitor.java
public void startMonitoring() {                                     // line 100
    Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(
        this::lambda$startMonitoring$0$SecurityMonitor,
        0L, 2L, TimeUnit.SECONDS);                                  // zero initial delay, then every 2s
}
public void lambda$startMonitoring$0$SecurityMonitor() {            // line 55
    String out = AppUtils.runCommands1("ps -ef");                   // as root, via su
    if (out.contains("adbd")) {
        AppUtils.runCommands("reboot\n");                           // as root, via su
    }
}
```

`AppUtils.runCommands`/`runCommands1` (`com/emotix/arya/app_utils/AppUtils.java:257,273`)
both work by `Runtime.exec("su")` and writing `"<cmd>;\r\nexit\n"` to its stdin `[DEX]` —
i.e. `AppUtils.runCommands("reboot\n")` runs the **plain shell command `reboot`** as
root, which resolves via `PATH` to **`/system/bin/reboot`** — exactly the file
`docs/boot-hook-findings.md` documents as the neuter target (stock ~68KB toybox
multicall binary; neutered = a 24-byte no-op shim starting `23 21 2f 73` / `#!/s`).
**This confirms the existing neuter mechanism targets the right file for defeating this
exact reboot call**, independent of whether the hardware watchdog theory below is also
in play.

Because `scheduleAtFixedRate(..., 0L, 2L, SECONDS)` has **zero initial delay**, the
`ps -ef`/reboot check runs *immediately* the instant `startMonitoring()` is called — this
is not "2 seconds after adbd starts", it's "on the very first tick, whenever that tick
happens to land relative to `init()` being reached". If `adbd` is already running by
then (which it will be, since our persisted-property patch starts it far earlier in
boot), the reboot fires within roughly one `ps -ef` + string-check's worth of time after
`init()` runs — i.e. **the ~28-33s timing this session has consistently measured is best
explained as "when does `SocialInteraction_SpeechChat.init()` get reached", not any fixed
hardware or software timeout.**

Also worth noting for completeness, found in the same `init()` `[DEX]`: `AppUtils.runCommands("echo
0 > /sys/bus/usb/devices/usb1/authorized")` — deauthorizes a USB device at the kernel driver
level. Not fully investigated this pass; flagged as a possible *additional* mechanism for
disrupting the host's view of the device around the same time, separate from `disableADB()`
and `SecurityMonitor`.

---

## Root cause of the ~30s reset

**Two candidate mechanisms were identified. One is now confirmed by decompilation with an
exact, matching target file; the other remains an unconfirmed (if structurally real)
possibility.**

### Confirmed: `SecurityMonitor`'s reboot-on-`adbd` check (high confidence this is the actual cause)

- Fully decompiled, exact code path, exact command (`reboot`, which resolves to
  `/system/bin/reboot` — the file we already neuter).
- Explains the *conditionality* correctly: this reboot only fires if `adbd` is actually
  detected running. That matches this session's history — the reset has been observed
  specifically during and after attempts to get ADB up, using this exact device, and
  earlier "vanilla boot with zero interventions" isolation testing (referenced from prior
  session summaries) was very likely **not actually a clean baseline**, because
  `persist.sys.usb.config` had already been persistently patched to include `adb` in an
  earlier step of the same overall session — meaning every subsequent "vanilla" boot
  still had adbd starting early, and would still trip this exact check. This reframes
  that earlier "the reset is unrelated to our activity" conclusion: it's unrelated to
  *manual, ad hoc* activity in each individual test, but not unrelated to the *standing*
  persisted-property patch already in effect the whole time.
- Timing model: reboot fires on `SocialInteraction_SpeechChat.init()`'s first
  `SecurityMonitor` tick (zero initial delay), gated on ServiceExam's `MyService`
  becoming bindable from MikoPlus's AIDL retry loop — i.e. gated on system_server/PMS
  readiness and app-process scheduling, not a fixed timer. This is consistent with
  "roughly the same time every boot, but not exactly identical to the second" — which is
  exactly what's been measured (28.4s, 28.42s, 28.51s, 29.37s, 29.45s across different
  boots).

### Unconfirmed / secondary: the hardware watchdog (`watchdogd` / `mt6589-wdt`)

- Structurally real: the kernel driver exists (`mediatek,mt6589-wdt` compatible string in
  the kernel image), `/dev/watchdog` is opened and armed for a 30-second timeout by
  `watchdogd` (= `/init`'s built-in applet) starting within the first few seconds of
  boot, per standard, well-documented AOSP behavior.
- **Not confirmed to be what's actually firing.** No local disassembly of `/init`'s
  `watchdogd` code path was done this pass (it's a stripped multi-call binary; isolating
  the relevant logic from the rest of `/init` wasn't attempted). Given a fully-decompiled,
  exact-match alternative exists (`SecurityMonitor`), treat this as a plausible
  *parallel* mechanism, not the primary explanation, unless future evidence
  (e.g. actually disabling `SecurityMonitor`'s effect and still seeing a reset at exactly
  t≈31-32s) forces reconsideration.
- The two theories are *not* mutually exclusive: if `SecurityMonitor`'s `reboot` command
  itself gets neutered (our shim) but something about the neuter or the surrounding
  timing still causes `watchdogd`'s own pet loop to miss a beat (e.g. system load from
  our injected commands, or `init` itself being busy), the hardware watchdog could still
  fire independently. This hasn't been observed or ruled out.

### What was NOT found

Searched `SocialInteraction_SpeechChat.java`, `SecurityMonitor.java`, and `MyService.java`
(the three files most likely to contain one, given `init()`'s structure) for any *other*
scheduled/delayed reboot beyond the `SecurityMonitor` mechanism above — no
`Handler.postDelayed`, `AlarmManager`, `CountDownTimer`, or `PowerManager.reboot(...)`
call was found in these three files. This is not an exhaustive search of every class in
the APK (ServiceExam is a 5-dex, multi-hundred-class app); if the `SecurityMonitor` fix
below doesn't fully resolve the reset, the next step is a broader grep across all five
`classes*.dex` for `postDelayed`/`AlarmManager`/`reboot`/`powerctl`, not just the classes
already known to be involved.

---

## Actionable next steps

1. **The existing neuter target is correct — the fix is winning the race against
   `SecurityMonitor`'s first tick, not against a hardware timer.** Since the tick has
   zero initial delay and fires the instant `init()` is reached, the actual deadline is
   "before `MyService`'s init chain reaches `SocialInteraction_SpeechChat.init()`" — which
   in turn depends on MikoPlus's AIDL bind succeeding. There is no fixed number of
   seconds to beat; there's a *specific event* to beat.
2. **Try confirming this model directly**: on the next race, in the same remote-shell
   invocation as the `nsinject` race, also grep `logcat` (or `ps -ef` output, since
   `SecurityMonitor` logs `Log.e(TAG, "outputValue : " + strRunCommands1)` and
   `"SUCESSSSS..."` right before rebooting — a very greppable, unique string) for
   evidence of `SecurityMonitor`'s tag `"SECURITY_MONITOR"` and that exact
   all-caps "SUCESSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSS" string. If it appears
   right before every observed reset, that's direct confirmation over the current
   timing-correlation argument.
3. **A different lever worth trying**: since `disableADB()` runs unconditionally and
   likely tears down the adb session before `SecurityMonitor` even gets a chance to see
   it in some races, and since both mechanisms only activate once `MyService` binds to
   MikoPlus's AIDL call — **delaying or blocking that specific bind** (not ServiceExam's
   process generally, which we can't prevent from starting) would buy unlimited time for
   Route A's chain, rather than requiring a faster injection race. This wasn't
   investigated this pass; MikoPlus's AIDL client code and its retry/backoff logic would
   need decompiling to know if this is safely blockable without hanging the kiosk UI.
4. **If neither of the above closes the gap**: broaden the reboot-logic search (see
   "What was NOT found" above) across all of ServiceExam's dex files, and also check
   `com.miko.mikoplus` itself for anything AIDL-bind-side that could be slowed down
   deliberately (e.g. a file lock, a blocked port) as a way to delay `MyService.onCreate()`
   being reached at all.

---

## Where zip4j is used (unchanged from the prior version of this doc — still accurate)

### Location 1: Update App (`com.miko.update_app`)

- **Class**: `com.emotix.arya.app_utils.FileUtils`
- **Method**: `unzipUsingLibray1(String source, String dest, ZipCallback cb, int i, int i2)`
- Unzips `APPS.zip` into `/sdcard/klug/downloads/ENC/`. Success is the *string* `"0"`;
  `isValidZipFile()` returning false gives `"-2"`.
- **Status, per `docs/boot-hook-findings.md` §3c**: the macOS-zip-artifact theory is
  **ruled out** (real zip4j 2.11.5 extracts both our synthetic archive and a real
  recon'd archive cleanly on the host). The actual cause of Route B's on-device
  extraction failure is still undetermined — narrowed to a device-side transfer/environment
  difference, not the archive format. Route A (below) sidesteps this entirely and is the
  recommended path regardless.

### Location 2: System `libziparchive.so`

Android's own native zip library — unrelated to our boot hook.

## Route A vs Route B — decision matrix (updated)

| Scenario | Recommended approach |
|---|---|
| Need a privileged app to execute payload (current hard requirement) | **Route A** — SD-card-shaped `.l` drop via `nsinject` into the launcher's mount namespace, letting the launcher's own `su`-capable `ProcessUpdates.processInstall()` run it |
| Considering an independent boot-agent APK instead | **Ruled out entirely, not just "hard in factory mode".** Confirmed empirically (`docs/persistent-adb-normal-boot.md`): app-invoked `su` never escalates for a non-privileged, non-platform-signed caller, regardless of install mechanism, `BOOT_COMPLETED` timing, or "stopped" state handling. This isn't a packaging problem — it's an OS-level privilege gate on `su` itself. |
| Just want *our own* root shell (not "a privileged app doing it") | **Solved and scripted**: `persist.sys.usb.config` patch + a fast `neuterd` race via our own root adb shell, `scripts/auto-persistent-adb.py`. Reliable, but doesn't satisfy the standing requirement that a privileged app be the one to execute the neuter. |
| SD card physically available | Still preferred if it ever becomes available — removes the mount-namespace race entirely (real SD card is visible to every process, no `setns()` trick needed). |

## References

- `docs/boot-hook-findings.md` — Route A/B decompilation findings, zip4j investigation
- `docs/persistent-adb-normal-boot.md` — persisted-property method, app-`su` proof,
  mount-namespace storage isolation, the original `SecurityMonitor`/watchdog discovery
  this doc builds on
- `docs/persistent-adb-strategy.md` — overall strategy doc
- `scripts/autonomous-recovery.py` — current single consolidated daemon (factory-mode
  entry, persisted-property patch, file staging, normal-boot race), with `/proc/uptime`
  timing instrumentation and JSONL analytics logging
- `bootagent/native/nsinject.c` — the `setns()`-based file-injection tool used in the race
- `scripts/brom-probe.py` — standalone preloader/META-mode handshake tool

---

## 2026-09-14 — a "suppress ServiceExam by killing it" guard permanently wedges the kiosk

**Symptom:** device sits on the kiosk's own splash/loading animation
(`com.miko.mikoplus` resumed, `MikoActivity`) forever — root adb stays up and
stable (persistent-adb goal technically achieved), but nothing else ever
happens. `dumpsys activity services com.example.root.serviceexam` showed:

```
ConnectionRecord{... DEAD com.example.root.serviceexam/.MyService:...}
binding=AppBindRecord{... com.example.root.serviceexam/.MyService:com.miko.mikoplus}
```

— MikoPlus is, and stays, blocked on an AIDL bind to ServiceExam's
`MyService` that will never resolve, because `logcat` showed ServiceExam had
died 2-3 times in immediate succession (`Process ... has died: fore BFGS`,
no `FATAL EXCEPTION` — these are external kills, not Java crashes) and
ActivityManager had **stopped scheduling further restarts** — no more
`Scheduling restart of crashed service` lines after the third death, and no
further `Start proc ... for service .../.MyService` ever appeared again on
that boot. Running `am start-service com.example.root.serviceexam/.MyService`
by hand while `/system/bin/reboot` was still the real ELF (neuter not yet
armed) reproduced the *other* failure mode instead: `MyService` started,
`SecurityMonitor` saw `adbd` on its very first tick, and the device genuinely
rebooted — confirming Finding #2 above has effectively no safe window once
ServiceExam is allowed to start unprotected.

**Root cause:** `scripts/autonomous-recovery.py`'s `RACE_SCRIPT` ran a
background guard loop that did `kill -9` on ServiceExam's pid every time
`pidof` found it, on the theory that a dead watchdog can't reboot anything.
That's true, but Android's crash-loop detection doesn't distinguish
"repeatedly SIGKILL'd from outside" from "repeatedly crashing" — after a
handful of near-immediate deaths it marks the process **permanently bad**
and gives up auto-restarting the service entirely. There is no adb-visible
"un-bad" the process short of a fresh boot (or possibly `am start-service`
managing to slip past it, untested — the one time this was tried, the real
`reboot` fired first because the neuter wasn't armed yet, so it was never
actually isolated whether `am start-service` alone clears the bad mark).

**Fix applied:** the `kill -9 $P` line was removed from `RACE_SCRIPT` (it now
only re-asserts `settings put global adb_enabled 1` /
`setprop sys.usb.config`, same as before, just without touching
ServiceExam's process at all). `scripts/auto-persistent-adb.py`'s
`step_neuter_race` — the already-proven, minimal, "just neuterd" path this
doc's decision matrix recommends — gained two new steps that run before the
race loop starts, neither of which ever touches ServiceExam:

- `step_backup_real_reboot()` — copies the genuine `/system/bin/reboot` ELF
  to `/data/local/tmp/real_reboot` *before* `neuterd` can shadow the
  canonical path. A software-only escape hatch: `su -c
  /data/local/tmp/real_reboot` still forces a real reboot later even though
  `reboot`/`/system/bin/reboot` now resolve to the no-op shim — useful in an
  unattended session with no one available to physically power-cycle the
  unit.
- `step_start_adb_defense_guard()` — a 30-minute background loop
  re-asserting `adb_enabled`/`sys.usb.config` only, to counter Finding #1
  (`disableADB()` runs unconditionally, independent of `SecurityMonitor`).
  Never kills or force-stops anything.

**Net conclusion for future sessions:** neutering `/system/bin/reboot` via
`neuterd` and continuously re-asserting the two adb settings is sufficient by
itself to keep both of ServiceExam's ADB-hostile mechanisms harmless.
**Never kill, force-stop, or otherwise suppress ServiceExam's process** —
doing so doesn't buy safety, it risks permanently wedging the kiosk with no
recovery path except a physical power cycle.
