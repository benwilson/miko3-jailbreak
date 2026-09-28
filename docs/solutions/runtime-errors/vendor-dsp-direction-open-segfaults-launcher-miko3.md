---
title: "The vendor NC direction DSP reports success on a missing UART, then segfaults the launcher"
date: 2026-09-28
category: runtime-errors
module: "Miko 3 launcher ears: voice direction (shared VoiceDirection, vendor libconexant_dsp_lib.so)"
problem_type: runtime_error
component: tooling
symptoms:
  - "The launcher dies every 10-120 s once Explore runs: ActivityManager logs Process com.miko3.launcher has died, and DriveLeaseService and ListenService restart"
  - "Explore sits in eyes-only (slitted eyes) because every launcher death drops the drive lease"
  - "The only crash line is libsigchain: exiting due to SIG_DFL handler for signal 11, preceded by a google-breakpad banner; /data/tombstones stays empty"
  - "The crashing thread differs between crashes (the ears thread, a Binder thread, mali-mem-purge), which looks like heap corruption or a GPU driver bug"
root_cause: wrong_api
resolution_type: code_fix
severity: critical
related_components:
  - "development_workflow"
tags:
  - "native-crash"
  - "vendor-jni"
  - "voice-direction"
  - "nc-dsp"
  - "breakpad"
  - "on-device-bisection"
---

# The vendor NC direction DSP reports success on a missing UART, then segfaults the launcher

## Problem

The first time the launcher's ears hear speech, `VoiceDirection.open()` probes the vendor's direction-of-arrival DSPs. On the office robot neither DSP is present, but the NC fallback reports success anyway. Its next native call segfaults the whole launcher process, which also hosts the drive-lease and listening services, so Explore can't drive or listen. This blocked all on-robot testing on 2026-09-28, the first session after PR #18 added the ears.

## Symptoms

- The launcher dies 10 to 120 seconds after Explore starts, restarts, and dies again at the first speech the new process hears (the wake word, a voice, or motor noise the voice gate takes for speech).
- Explore falls back to eyes-only and stops driving.
- No tombstone is written. The launcher hosts a WebView, so Chrome's `google-breakpad` installs the crash handler, and MediaTek's `aee_core_forwarder` saves nothing either.
- The crashing thread is different each time, so the crash site says nothing about the cause.

## What Didn't Work

- **Memory pressure:** ruled out. `free` showed 28 MB, but `MemAvailable` in `/proc/meminfo` was about 870 MB, and no `am_kill` or low-memory kill was logged.
- **A thread race on the wake-word engine:** ruled out. Every call to the vendor detector runs on the ears capture thread under one lock (`EarsSession.feed`).
- **The wake-word engine's GPU delegate:** a build without `libtensorflowlite_gpu_delegate.so` fell back to the CPU (the vendor logs `GPU delegate .so not found ... using CPU`) and still crashed.
- **malloc debug via `setprop wrap.com.miko3.launcher`:** it works on this userdebug build, but any `backtrace` option made the launcher miss its 10-second attach timeout. `guard` alone started, but that run was invalid: starting the launcher's activity sends Explore to the background, so the ears never opened.

## Solution

Try the NC backend only when its UART node exists (`shared/src/com/miko3/shared/VoiceDirection.java:84`). Otherwise report `Backend.NONE`:

```java
if (!new File(NC_UART).exists()) {
    why.append("; nc: no ").append(NC_UART);
    return new VoiceDirection(Backend.NONE, why.toString(), null, null, 0);
}
```

With no direction, the meeting plan's stop condition 1 applies: no turn to the voice, and the camera meeting look decides. The fix is pending in PR #28, unmerged as of this writing. On the robot, the fixed launcher ran five minutes of Explore with a conversation and 0 crashes, on one process.

## Why This Works

The chain, confirmed with step logs inside `open()` on the robot:

1. The ears start direction sampling on their first detected speech, which calls `VoiceDirection.open()` (`launcher/src/com/miko3/launcher/ListenEngine.java:684`).
2. `ConexantDSP.initDSPComm()` returns `-19` (no device) (`VoiceDirection.java:75`).
3. The fallback calls `NCDsp.createUART(500, "/dev/ttyMT2")`. It returns a handle even though `/dev/ttyMT2` doesn't exist on this robot, and `initNCUART(handle)` returns `1`, which passes the `status >= 0` check (`VoiceDirection.java:92-93`).
4. `getCurrentDOAStatus(handle)` on that unopened UART segfaults (`VoiceDirection.java:95`), and the launcher dies with it.

The vendor app never reaches this state. `DSPSettings` opens NC only when its caller has already set `isNC`, and it picks `/dev/ttyS1` on "JoyAR" units (`tools/serviceexam_jadx/sources/com/example/conexantapi/DSPSettings.java:41`, `:177`). Its return codes can't be trusted on hardware that lacks the chip. `/dev/ttyS1` exists on this robot, but it was deliberately not probed: writing DSP commands to an unidentified UART could hit another peripheral. Whether this robot has an NC DSP there is a question for the meeting plan's measurement session (U2).

## Prevention

- Before any vendor native `init`/`open` call, check what the call needs on this unit yourself: the device node exists, the vendor's own detection flag is set. Treat a vendor "success" on a probe path as unproven.
- For a native crash on this robot with no tombstone, find the component by bisection on the device rather than reading crash sites:
  - Build a diagnostic-only launcher in a worktree (never committed) whose ears components each check a debug property, for example `debug.miko3.ears.off=wake,vad,rec,dir`. Toggle them live with `setprop`, with 5-minute survival windows.
  - Then add step logs around each native call, plus a startup hook that calls the suspect `open()` once, so the reproduction doesn't depend on someone speaking.
  - All four off for five minutes, then halving.
- Confirm the ears are actually open before trusting a quiet window: `ListenEngine: ears: chunks=` should be climbing. Bringing the launcher's activity to the front stops Explore, and with it the ears.
- The host suite can't reach this branch, because the vendor natives don't load on the Mac. `test_voice_direction_tries_nc_only_when_its_uart_exists` in `scripts/tests/test_listen_service.py:698` pins the guard's shape, and the robot is the behavioural check.

## Related Issues

- PR #18 (meeting and small talk) added `VoiceDirection` and the ears. The fix is PR #28.
- `docs/solutions/runtime-errors/kill-9-on-watched-service-permanently-disables-restart.md`: the other way this robot's supervised services stop coming back.
