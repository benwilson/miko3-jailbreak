---
title: "SIGKILL-ing a watched Android service to suppress it permanently disables its auto-restart"
date: 2026-09-14
category: runtime-errors
module: "Miko 3 root-adb recovery automation (scripts/autonomous-recovery.py watchdog neuter)"
problem_type: runtime_error
component: tooling
symptoms:
  - "Kiosk sits on its splash/loading animation forever; MikoPlus never advances past it, even though root adb stays up and stable"
  - "dumpsys activity services com.example.root.serviceexam shows the AIDL connection stuck ConnectionRecord{... DEAD ...}, bound to a service that never comes back"
  - "logcat shows the target process dying 2-3 times in immediate succession (Process ... has died: fore BFGS) with no FATAL EXCEPTION anywhere — an external kill, not a Java crash"
  - "No further Scheduling restart of crashed service or Start proc ... for service lines appear for that process for the rest of the boot"
root_cause: logic_error
resolution_type: code_fix
severity: high
related_components:
  - "infrastructure"
  - "development_workflow"
tags:
  - "android-crash-loop"
  - "activity-manager"
  - "process-supervision"
  - "kiosk-splash-loop"
  - "watchdog-neutralization"
  - "sigkill"
applies_when:
  - "You're suppressing a hostile Android service (a watchdog, anti-debugging monitor, license checker, etc.) by repeatedly killing its process rather than disarming its specific harmful action"
  - "A bound/AIDL-dependent UI is stuck on a loading or splash screen and the service it depends on is not running"
  - "A recovery or automation script has root and is tempted to use kill -9 or am force-stop as a quick safety measure against a process it doesn't fully control"
---

# SIGKILL-ing a watched Android service to suppress it permanently disables its auto-restart

## Problem

A root-adb recovery script (`scripts/autonomous-recovery.py`) tried to buy
itself time against a hostile watchdog service by repeatedly `kill -9`-ing
its process the instant it reappeared. The watchdog stopped rebooting the
device, as intended — but the kiosk UI that depends on that same service
never recovered either. It got stuck on its splash screen permanently, with
no software-visible way back, because Android's own crash-loop protection
had quietly stopped trying to restart the service at all.

## Symptoms

- The kiosk UI (`com.miko.mikoplus`, `MikoActivity`) sits on its
  splash/loading animation forever. Root adb itself stays up and stable —
  the persistence goal looked achieved — but nothing else ever happens.
- `dumpsys activity services com.example.root.serviceexam` shows the bind
  stuck dead:

  ```
  ConnectionRecord{... DEAD com.example.root.serviceexam/.MyService:...}
  binding=AppBindRecord{... com.example.root.serviceexam/.MyService:com.miko.mikoplus}
  ```

- `logcat` shows the target process dying 2-3 times in immediate succession
  (`Process ... has died: fore BFGS`) with **no `FATAL EXCEPTION`** anywhere
  — the signature of an external kill, not a Java crash.
- After the last death, `ActivityManager` never logs another
  `Scheduling restart of crashed service` or `Start proc ... for service`
  line for that component, for the rest of the boot. It isn't slow to
  restart — it has stopped trying.

(`docs/boot-sequence.md:395-417` records the live evidence from the session
that found this.)

## What Didn't Work

- **Assuming a killed process is simply "off" until restarted.** The mental
  model was: `kill -9` the watchdog's process, it can't do anything while
  dead, `pidof` will show it come back on the platform's own restart
  schedule, repeat. That model is correct for a handful of restarts but
  ignores that the platform is *also* counting those deaths against the
  process.
- **Trying to bring the service back with `am start-service` after the
  fact**, once it had stopped restarting on its own. This was only tried
  once and inconclusively: `/system/bin/reboot` had not yet been shadowed at
  that point, so the moment the service's `onCreate()` chain reached its
  watchdog-arming code, the *real* watchdog reboot fired before the "is it
  still marked bad" question could be answered
  (`docs/boot-sequence.md:412-417`). Whether `am start-service` can clear the
  bad-process mark once the reboot vector is already neutralized is still
  untested.

## Solution

Stop killing the process. Neutralize the *specific action* you're afraid of
instead of the process that performs it.

```c
// bootagent/native/neuterd.c — reboot_is_real() / write_noop()
// Shadow the one command the watchdog uses to punish you (reboot),
// bind-mounted in init's global mount namespace so a zygote-forked
// service actually sees it (see docs/persistent-adb-normal-boot.md).
```

```diff
- for i in $(seq 1 600); do
-   P=$(pidof com.example.root.serviceexam 2>/dev/null)
-   [ -n "$P" ] && kill -9 $P 2>/dev/null
-   settings put global adb_enabled 1 2>/dev/null
-   ...
+ for i in $(seq 1 600); do
+   settings put global adb_enabled 1 2>/dev/null
+   ...
```

(The real diff landed in `scripts/autonomous-recovery.py`'s `RACE_SCRIPT`,
commit message `fix(recovery): stop killing ServiceExam to buy time — it
permanently wedges the kiosk`; `docs/boot-sequence.md:431-436` narrates it.)
The watched service is now left completely alone: it starts, runs, and
stays running. Its harmful actions are defused individually — the reboot
call via the bind-mount shadow above, and a separately unconditional
settings write via a loop that keeps re-asserting the setting it wants
flipped back.

## Why This Works

Android's `ActivityManagerService` doesn't distinguish "this process died
because something outside it sent SIGKILL" from "this process keeps
crashing." Either way, a handful of near-immediate deaths trips its
crash-loop protection, and the service is marked bad: no more automatic
restarts, silently, with no log line announcing the state itself — only the
absence of the retry lines that would otherwise appear. A UI that depends on
binding to that service (here, over AIDL) then hangs forever, because there
is nothing left trying to bring the service back.

Neutering `reboot` avoids this entirely because it doesn't touch the
watched process's lifecycle at all — the service keeps running normally;
only the one command it uses against you becomes a no-op. Re-asserting a
setting the same service tries to flip works the same way: let the process
live, race the *effect*, not the *process*.

## Prevention

- **Never `kill -9` or `am force-stop` a process to suppress it, when
  anything else in the system depends on that process staying up** (an AIDL
  bind, a shared UID, a UI waiting on a callback). Android's crash-loop
  mercy-kill has no adb-visible undo short of a fresh boot.
- **Diagnose "stuck AIDL bind" by checking for a dead connection first,
  before assuming the client side is the problem:**

  ```bash
  adb shell dumpsys activity services <server-package> | grep -A2 ConnectionRecord
  ```

  A `DEAD` connection with no further `Scheduling restart` lines in `logcat`
  means the platform gave up on the service, not that the client is
  misbehaving.
- **When you need to stop a service from doing something harmful, find and
  disarm the specific mechanism it uses** (the binary it execs, the setting
  it writes, the syscall it makes) rather than the process itself. If that
  mechanism resolves through `PATH` to a system binary, shadowing that
  binary in the mount namespace the target process actually sees is a
  general-purpose lever (`bootagent/native/neuterd.c`).

## Related Issues

- `docs/boot-sequence.md` — full incident writeup, live evidence, and the
  applied fix
- `docs/persistent-adb-normal-boot.md` — the original watchdog discovery and
  why the mount-namespace shadow has to happen in init's namespace, not an
  app's
- `docs/solutions/integration-issues/aidl-interface-mismatch-kiosk-splash-loop.md` —
  a different root cause (version-skewed AIDL contract) producing the same
  visible symptom (kiosk stuck on its splash screen); worth ruling out first
  since the symptom alone doesn't distinguish them
