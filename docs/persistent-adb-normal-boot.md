# Persistent root ADB on normal boot — the method that actually worked (2026-09-13)

**Status: confirmed working.** Root ADB survived 60+ seconds stable before any
app was touched, then survived indefinitely once the watchdog was neutered.
This supersedes the entire Route A/B (kiosk-launcher / update-engine) approach
in `docs/boot-hook-findings.md` for the specific goal of *getting a root shell
on normal boot* — no SD card, no zip4j, no launcher trick required.

## The discovery

`persist.sys.usb.config` — a **persisted** property, read by init before any
app runs — was set to an **empty string** on this unit. That's why normal boot
never exposed adb: nothing ever told the USB gadget driver to include the adb
function, regardless of `ro.debuggable=1` being set.

Persisted properties live in `/data/property/persistent_properties`, a single
binary file: a flat, unframed sequence of protobuf `PersistentPropertyRecord`
messages (`{repeated {name, value}}`, no checksum, no wrapper). Confirmed by
writing a parser, verifying it reproduces the original file byte-for-byte,
then re-serializing with exactly one field changed
(`scripts/patch-persist-usb-config.py`, adapted from the throwaway version at
`persist_props.py` used in-session).

```
persist.sys.usb.config = ''          # before — no adb function ever added
persist.sys.usb.config = 'mtp,adb'   # after
```

## The other half of the discovery: the watchdog doesn't exist yet

`SecurityMonitor` (`com.common_source.emotix.interaction.interaction`) — the
class that greps `ps -ef` for `adbd` every 2 seconds (`scheduleAtFixedRate(...,
0L, 2L, SECONDS)`, **zero initial delay**) and execs `reboot` — was checked by
decompiling every relevant APK on the device:

| APK | Has `SecurityMonitor`? |
|---|---|
| ServiceExam v40 (stock, `/system/app/file1`) | **No** |
| ServiceExam v92 (the real update, `file1.ia`) | **Yes** — this is the only place it exists |
| MikoPlus v69 (the real update, `file2.ia`) | No (`startMonitoring` hit was WebRTC's unrelated `NetworkMonitor`) |

So a unit sitting at stock ServiceExam v40 has **no watchdog code anywhere**.
Setting `persist.sys.usb.config=mtp,adb` on such a unit gets adb that just
stays up — confirmed for 60+ seconds across 12 checks before we touched
anything else.

## Procedure

1. Get root in **factory mode** (`scripts/factory-root.sh`) — needed to write
   to `/data/property/persistent_properties`, since that's not writable from
   an app process and normal boot has no adb yet at this point anyway.
2. Pull `/data/property/persistent_properties`, back it up, patch
   `persist.sys.usb.config` to `mtp,adb` in place (leave every other entry
   byte-identical — verified via round-trip parse before touching the target
   field), push it back with the same ownership/perms (`root:root`, `600`).
3. `adb reboot`.
4. adb reappears on normal boot (`ro.bootmode=normal`) within ~10s and — if
   ServiceExam is still at stock v40 — just stays up.

## The trap: installing ServiceExam v92 re-arms the watchdog almost instantly

Once you `pm install -r -t file1.ia` (ServiceExam v92) for real, `pm install`
itself completes fine (this is a normal boot, `pm` genuinely works, unlike
factory mode) — but the watchdog can fire **within a single adb round-trip**,
not the nominal 2 seconds. In this session it fired before a *second*,
separate `adb shell` call could even execute. Best guess: MikoPlus had been
sitting in its AIDL-bind retry loop the entire time (splash-screen looping,
see `docs/kiosk-recovery-fix.md`), so the instant ServiceExam v92 registers,
the bind succeeds, `MyService.onCreate()` runs, and `SecurityMonitor` starts
polling immediately — there may be effectively **no safe window** after
install.

**What actually recovered it:** a tight retry loop that did nothing but
attempt to launch the pre-staged `neuterd` daemon, no diagnostic calls, retried
every ~2s across as many reboot cycles as needed. It landed on the very first
attempt after the first re-reboot.

**The fix for next time — don't race it at all:**
1. Push `neuterd` to `/data/local/tmp/neuterd` and launch it (`setsid
   /data/local/tmp/neuterd </dev/null >log 2>&1 &`) **before** installing
   ServiceExam v92. `neuterd` doesn't depend on ServiceExam or any app state —
   it `setns()`s into init's global mount namespace and bind-mounts a no-op
   shell script over `/system/bin/reboot` (see
   `bootagent/native/neuterd.c` — this is the *global*-namespace variant;
   an app-triggered bind-mount lands in that app's own mount namespace and is
   invisible to ServiceExam's zygote-forked process, per
   `recon/sources/miko3-adb-boot-agent/src/.../RootOps.java`'s writeup of the
   same problem on a different unit).
2. Confirm the neuter took (`wc -c < /system/bin/reboot` < 100 bytes — the
   shim is 24 bytes, the real ELF is ~68KB).
3. *Then* `pm install -r -t file1.ia`. The watchdog will exist and will try to
   fire, but `reboot` is already a no-op by the time it does.

## Update 2026-09-13 — the app-based boot agent is fundamentally infeasible

Confirmed empirically, not just theorized: **an app-invoked `su` does not grant
real root on this device**, no matter how it's invoked. `/system/bin/su` is
genuinely setuid-root (`-rwsr-sr-x root root`) and its filesystem isn't
`nosuid`, so this isn't a permissions or mount-option block — something else
(likely a uid-range or platform-signature check baked into the binary itself)
gates it to privileged callers only.

Proof, via a temporarily-rebuilt `android:debuggable="true"` bootagent and
`run-as` to reach its real unprivileged uid (10048, `untrusted_app` domain):
- `run-as com.miko3.bootagent sh -c 'echo id | su'` → printed `uid=10048`, not
  `uid=0`. `su` ran the command but never escalated, and exited 0 (no error).
- `su 0` / `su root` → `No such file or directory` — this `su` doesn't even
  support the standard `su <user>` syntax; it treats its argument as a command.
- The real bootagent's `Runtime.exec("/system/bin/su")` (feeding the payload
  via stdin, matching upstream `ne3d-4-steve/miko3-adb-boot-agent`'s approach)
  → exit code 1, **zero bytes ever written to the log** — su refused before
  reading anything from stdin.

Meanwhile `ServiceExam`'s `SecurityMonitor` watchdog — `sharedUserId="1000"`,
platform-signed, uid 1000 — has successfully invoked root via this exact
mechanism all night (every `reboot` it fired). **Privileged/platform-signed
callers can use this `su`; generic third-party apps cannot**, and there is no
fix available to us: we don't have Miko's platform signing key, so our own
app can never join the privileged set. This isn't a bug in `RootOps.java` —
the whole premise of an independent, self-signed boot-agent app is blocked at
the OS level on this specific unit. (`ne3d-4-steve`'s original device may
simply be laxer about this — different chipset, mt8167 vs our mt6763 — but we
have no way to verify that.)

### A tempting-looking workaround that is also blocked: faking the SD card in tmpfs

Route A's actual mechanism doesn't need `su` from *our* app at all — it feeds
commands to the kiosk launcher's own privileged `ProcessUpdates.processInstall()`
(which does successfully invoke root, being called from a uid-1000 process),
by dropping a `.l` file at `/storage/sdcard1/UPDATE_APP_INSTALL_DIR/`. Since
`/storage` is tmpfs (not a real block device), it seemed like we could just
`mkdir`/`adb push` that path ourselves and skip the SD card hardware
requirement entirely.

**This does not work, for the same class of reason `neuterd` needs `setns()`.**
Checked directly: `cat /proc/<launcher-pid>/mounts` shows the launcher's own
`/storage` as its own private tmpfs instance, not the one our adb root shell
sees — Android gives each zygote-specialized app process its own fresh,
per-namespace mount of `/storage` (this is the standard storage-isolation
mechanism, not something specific to this device). Confirmed live: creating
`UPDATE_APP_INSTALL_DIR` and the `.l` file via our root shell, then triggering
the launcher fresh (`am force-stop com.miko.launcher_app` → new process →
fresh `onCreate()`), the launcher's own `getUpdateAppInstallDirectory().exists()`
check came back **false**, and it fell through to the unrelated
`apps.json`/`checkApps()` path instead — our files were simply invisible to it.

A fix would require `setns()`-ing into the *specific, already-running target
process's* mount namespace (not init's — confirmed a plain global-namespace
write, even from init's own mount namespace, does not propagate to an
already-specialized app's private storage view) and writing the files from
inside that namespace before the process's background thread reaches the
`exists()` check — a real race, and one that needs a small new native tool
(no `nsenter` on-device; would need a `neuterd`-style hand-rolled `setns()`
helper targeting a dynamically-discovered PID). Not attempted — flagged here
as the concrete next step if Route A without hardware is worth revisiting.

## The actual working solution: persisted property + a fast manual neuter race

Given the above, full unattended (`BOOT_COMPLETED`-driven) persistence isn't
achievable with tools built so far. What **is** proven, working, and now
scripted (`scripts/auto-persistent-adb.py`):

1. `persist.sys.usb.config=mtp,adb` (§ above) — this part is genuinely
   persistent, survives every reboot, no re-application needed ever.
2. The watchdog neuter is not persistent (mount-namespace-scoped, as covered
   above) and must be re-raced after every reboot. But the race is **fast and
   reliably winnable**: a tight loop with no diagnostic calls in the body,
   just launch `neuterd` and check `/system/bin/reboot`'s size — landed on
   the *first* attempt both times this was tried for real, once right after a
   real `pm install -r -t <ServiceExam v92>` (the tightest, worst-case
   timing: the watchdog can fire within a single adb round-trip once
   ServiceExam actually registers, because MikoPlus is typically already
   mid-retry on the AIDL bind and reconnects instantly) and once on a cold
   boot.
3. `scripts/auto-persistent-adb.py --race-only`, run once per reboot, restores
   the neuter. That's the practical form of "persistent adb" on this unit
   until the per-process namespace-injection tool above gets built.

### A second real bug found and fixed along the way: the "stopped" flag

Not specific to boot agents — worth remembering generally. `am start` looks
like it clears Android's post-install "stopped" state, and does — briefly.
It does **not** survive a reboot on this build (confirmed: `stopped=false`
right after `am start`, back to `stopped=true nl=true` on the very next
boot). The fix that actually persists is editing
`/data/system/users/0/package-restrictions.xml` directly: a normal,
never-stopped package has **no** `stopped`/`nl` attributes on its `<pkg>`
entry at all (just `ceDataInode="..."`) — their mere presence is what blocks
delivery, not their value. Also: never call `am force-stop` near this fix —
it's the exact same mechanism as Settings' "Force Stop" button and re-sets
the flag, undoing an `am start` in the very next line.

### A third: hand-splicing `packages.xml` doesn't reliably survive, even with genuine data

Restoring MikoPlus by splicing its real `<package>` block (lifted from a
known-good `packages.xml` backup, byte-identical, real cert/keyset — not a
synthesized one) into the live file looked like it worked, and its raw XML
text did survive multiple reboots. But it was never actually promoted into
`/data/system/packages.list` (PMS's own export of packages it considers
fully validated) — confirmed by direct comparison against a genuinely-working
reference dump, where both packages appeared in `packages.list` with matching
UID/data-path/gids and ours didn't. Redoing ServiceExam's restore via genuine
`pm install -r -t` (once any root shell — even a temporary factory-mode-
adjacent one — is available) instead of hand-splicing fixed this properly:
`packages.list` populated correctly, no more silent PMS rejection risk. This
also happened to make the previous session's "install premise is false"
finding more precise: hand-splicing can survive as *text* even when PMS
never actually treats the package as fully valid — always prefer genuine
`pm install` the moment any root shell exists, even briefly, over splicing.

**One risk worth flagging for future sessions:** removing a `/data/app`
override via raw `rm -rf` (bypassing `pm uninstall-system-updates`) combined
with several rounds of hand-editing `packages.xml` and
`package-restrictions.xml` across multiple reboots eventually left
`com.example.root.serviceexam` **completely missing** from `packages.xml`
(zero entry, not even a stock fallback), despite the real APK still sitting
untouched on `/system`. A plain `pm install -r -t` of the same package
(pointed at an on-device backup of the real v92 APK) fixed it cleanly with no
crash — but this is exactly the scenario the MediaTek `PmsExtImpl` NPE trap in
`docs/mishap-recovery.md` warns about (a package PMS doesn't currently know
about hitting the "new install" code path instead of "update"). It worked
this time; it isn't guaranteed to. Prefer `pm uninstall-system-updates` over
raw `rm -rf` when reverting a system app's override, if `pm` is available at
the time.

## Caveats / what's not yet solved

- `neuterd` is **not persistent across reboots** — it's a bind-mount, which is
  mount-namespace/boot-session scoped by nature. `persist.sys.usb.config`
  *is* persistent (it's a file on `/data`), so adb will keep coming up on
  every future normal boot, but the watchdog-neuter step (launch `neuterd`
  before it can act) has to be redone after every reboot, same as it always
  did in the Route A/B design — this discovery only replaces *how you get a
  root shell to run that step from*, not the fact that the step is needed
  every boot. A real fix would need `neuterd` (or an equivalent) launched
  automatically and unattended at every boot — still an open problem, but now
  solvable with a boot-time `adb shell` script run from a host machine instead
  of the finicky kiosk-launcher/update-engine trigger.
- Persistent write to `/system` itself is **not viable** on this unit —
  `ro.boot.vbmeta.device_state=locked` and `ro.boot.veritymode=enforcing`,
  confirmed this session. Don't attempt to patch `/system/bin/reboot` on
  disk; the bind-mount-in-global-namespace approach is the correct one, not a
  workaround.
- `pm install` fixes the exact gap that hand-splicing `packages.xml` could
  not: the earlier approach (see `docs/boot-hook-findings.md` §1 and the
  session that produced this doc) left restored packages missing from
  `/data/system/packages.list`, meaning they were never properly validated by
  PMS even when the raw `<package>` XML text survived a reboot. Always prefer
  `pm install` over hand-splicing once any form of root shell is available,
  even briefly.
