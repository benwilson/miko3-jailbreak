---
title: "The vendor NC direction DSP's failure code reads as success, then its next call segfaults the launcher"
date: 2026-09-28
last_updated: 2026-09-28
category: runtime-errors
module: "Miko 3 launcher ears: voice direction (shared VoiceDirection + NcFrames, vendor libconexant_dsp_lib.so, scripts/qa-direction-chip.py)"
problem_type: runtime_error
component: infrastructure
symptoms:
  - "The launcher dies every 10-120 s once Explore runs: ActivityManager logs Process com.miko3.launcher has died, and DriveLeaseService and ListenService restart"
  - "Explore sits in eyes-only (slitted eyes) because every launcher death drops the drive lease"
  - "The only crash line is libsigchain: exiting due to SIG_DFL handler for signal 11, preceded by a google-breakpad banner; /data/tombstones stays empty"
  - "The crashing thread differs between crashes (the ears thread, a Binder thread, mali-mem-purge), which looks like heap corruption or a GPU driver bug"
  - "initNCUART returns 1 on a missing or wrong port, so both a >= 0 check and the vendor's own < 0 check treat the failure as success, and vendor reads on a real port block forever (VMIN=1/VTIME=0)"
root_cause: wrong_api
resolution_type: code_fix
severity: critical
related_components:
  - "development_workflow"
  - "testing_framework"
tags:
  - "native-crash"
  - "vendor-jni"
  - "voice-direction"
  - "nc-dsp"
  - "breakpad"
  - "on-device-bisection"
  - "uart"
  - "conexant"
  - "crc32"
---

# The vendor NC direction DSP's failure code reads as success, then its next call segfaults the launcher

## Problem

The first time the launcher's ears hear speech, `VoiceDirection.open()` probes the vendor's direction-of-arrival DSPs. On the office robot neither DSP answered at the path the code used, but the NC fallback's failure code (1) was read as success, by our check and by the vendor's own. Its next native call segfaulted the whole launcher process, which also hosts the drive-lease and listening services, so Explore couldn't drive or listen. This blocked all on-robot testing on 2026-09-28, the first session after PR #18 added the ears.

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
- **A node-existence guard alone (the first fix, PR #28, merged as `12f2967`):** it stopped the crash on this robot, and the fixed launcher ran five minutes of Explore with a conversation and 0 crashes on one process. But it only covered a missing node. It still trusted `initNCUART`'s return value and would still have made the vendor's blocking native reads on a node that exists but stays silent. That fix tried NC only when the hard-coded `/dev/ttyMT2` existed and otherwise reported `Backend.NONE` (`VoiceDirection.java:84` on `main`).

## Solution

The node guard is superseded on branch `feat/explore-hey-miko-always-answers`, unmerged as of this writing, so the design below is pending. It has been checked by the host tests only.

**The port comes only from the owner.** The NC backend runs only when `persist.miko3.voice_dir.port` names an existing node (`shared/src/com/miko3/shared/VoiceDirection.java:211-218`). The launcher reads the property and hands it to `VoiceDirection.configure()` before the first `open()` (`launcher/src/com/miko3/launcher/ListenEngine.java:684-695`). With the property unset, or naming a missing node, no NC native call is made at all. The owner confirms the port with `scripts/qa-direction-chip.py`. Its identification run is read-only and never writes to the port: it runs a passive read only when the tty's echo is off, and uses `timeout` to bound the blocking open (`scripts/qa-direction-chip.py:2-17`). It sets the property only on a confirmed port. If the launcher's probe then doesn't show the NC backend with an `XXUB` reply, it unsets the property again (`:28-32`).

**The vendor library only configures the port.** `createUART` plus `initNCUART` run once, and only a status of `0` counts as success (`VoiceDirection.java:222-226`). Nothing else in the vendor's NC API (`NCDsp`) is called: the `NcNative` interface exposes just those two calls (`VoiceDirection.java:103-108`). The Conexant backend in the same library (`ConexantDSP.initDSPComm`) is still tried first (`VoiceDirection.java:190-193`).

**The protocol runs in Java on our own streams**, in `shared/src/com/miko3/shared/NcFrames.java` and `VoiceDirection.java`:

- The request is 14 bytes: `XXUB`, module, op, param, three zeros, then a CRC32 over the first 10 bytes, little-endian (`NcFrames.java:47-60`).
- Replies are validated the way the vendor validates them: the `XXUB` prefix and at least 15 bytes, with byte 14 as the status (`0` means reporting is off). With reporting on, the reply runs to 38 bytes, with the raw value at `0x21` (`NcFrames.java:82-98`). Where a reply's own CRC sits is unverified, so the CRC isn't checked. The first three replies are logged in hex so a robot session can place it (`NcFrames.java:14-16`, `VoiceDirection.java:332-341`).
- Reads never block. Bytes already buffered are flushed before each GET (`VoiceDirection.java:301-306`). The reply is then polled with `available()` every 2 ms until it is complete or the 80 ms deadline passes (`:66-67`, `:308-325`).
- Three misses in a row close the NC backend for the life of the process (`VoiceDirection.java:69`, `:372-378`). A node that is silent at `open()` gives `Backend.NONE` straight away (`:231-236`).
- If the chip reports that reporting is off, the toggle is sent at most once per open, because each toggle flips the state (`VoiceDirection.java:349-358`).
- The raw value becomes signed degrees only once the three calibration properties are set. Until then the angle is NaN (`NcFrames.java:108-115`).

With no direction, the meeting plan's stop condition 1 applies: no turn to the voice, and the camera meeting look decides.

## Why This Works

The crash chain, confirmed with step logs inside `open()` on the robot:

1. The ears start direction sampling on their first detected speech, which calls `VoiceDirection.open()`. On the current branch, that path is `EarsSession.feed` → `direction.start()` (`launcher/src/com/miko3/launcher/EarsSession.java:532`) → `VoiceDirection.open().sample(...)` (`ListenEngine.java:701`).
2. `ConexantDSP.initDSPComm()` returned `-19` (no device).
3. The old fallback called `NCDsp.createUART(500, "/dev/ttyMT2")`. It returned a handle even though `/dev/ttyMT2` doesn't exist on this robot, and `initNCUART(handle)` returned `1`, which passed the old `status >= 0` check.
4. `getCurrentDOAStatus(handle)` on that unopened UART segfaulted, and the launcher died with it.

Disassembly of `libconexant_dsp_lib.so` explained more of this:

- **`initNCUART` returns 0 on success and 1 on failure.** So the problem was bigger than our `>= 0` check misreading 1 as success. The vendor's own caller checks `openNCUART() < 0` (`tools/serviceexam_jadx/sources/com/example/conexantapi/DSPSettings.java:44`), and that check misses failure too. The library reported the failure correctly; both callers misread it (session history: the first write-up of this doc, from the PR #28 session, called it a lying success code, which was the wrong diagnosis).
- **`createUART` only allocates.** It returns a handle whether or not the node exists, so a handle proves nothing.
- **`initNCUART` sets VMIN=1/VTIME=0**, so the vendor's native reads block until a byte arrives. On a peer that never answers, they hang forever, and a blocking read can't be timed out or woken from Java. That is a second crash-or-hang path, separate from the segfault. The disassembly points to a heap overflow as the segfault's cause (not re-checked for this doc). The Java protocol avoids both paths because it never calls the vendor's reads.
- **The frames:** `getCurrentDOAStatus` sends `58 58 55 42 03 01 03 00 00 00 a6 20 d0 e7`, and `toggleDOA` sends `58 58 55 42 03 03 02 00 00 00 a3 14 ac 25`. Both CRCs recompute with `zlib.crc32` over the first 10 bytes. `toggleDOA` isn't idempotent: every send flips reporting. The vendor sends it only after reading the current status (`DSPSettings.java:94-105`), and so do we.

The vendor app never reached the crash state. `DSPSettings` opens NC only when its caller has already set `isNC`, and it picks `/dev/ttyS1` on "JoyAR" units (`DSPSettings.java:41`, `:177`). `/dev/ttyS1` exists on this robot, but it was deliberately not probed with writes: writing DSP commands to an unidentified UART could hit another peripheral. That is why the port is now an owner-confirmed property with a read-only identification script, not a guess in code.

## Prevention

- Before any vendor native `init`/`open` call, check what the call needs on this unit yourself: the device node exists, and the vendor's own detection flag is set. Treat a vendor return code as unproven until the disassembly says what it means: here 0 is success and 1 is failure, and both our `>= 0` check and the vendor's own `< 0` check read the failure as success.
- Don't call vendor native reads on a tty the vendor opened blocking (VMIN=1/VTIME=0). Let the vendor configure the port, then read it yourself with a deadline.
- Never send a non-idempotent command such as `toggleDOA` blind. Read the state first, and send it at most once per open.
- For a native crash on this robot with no tombstone, find the component by bisection on the device rather than reading crash sites:
  - Build a diagnostic-only launcher in a worktree (never committed) whose ears components each check a debug property, for example `debug.miko3.ears.off=wake,vad,rec,dir`. Toggle them live with `setprop`, with 5-minute survival windows.
  - Then add step logs around each native call, plus a startup hook that calls the suspect `open()` once, so the reproduction doesn't depend on someone speaking.
  - Start with all four off for five minutes, then halve.
- Confirm the ears are actually open before trusting a quiet window: `ListenEngine: ears: chunks=` should be climbing. Bringing the launcher's activity to the front stops Explore, and with it the ears.
- Tests:
  - `test_voice_direction_tries_nc_only_on_the_confirmed_port_property_and_node` in `scripts/tests/test_listen_service.py:737` pins the source shape: no `/dev/ttyMT2`, the port property, and only 0 as success.
  - `scripts/tests/test_nc_frames.py` runs a JVM harness through `VoiceDirection.openWith()` (`VoiceDirection.java:204`) with a fake native layer and a fake node. It covers the frames and CRC, the reply deadline, a silent node (at open and mid-session), stale bytes, one toggle per open, the three-miss close, and an unset port or missing node making no native call (`scripts/tests/test_nc_frames.py:32-53`).
  - The vendor natives don't load on the Mac, so the robot is still the behavioural check for the real library.
- **Open item (from this branch's code review, not yet fixed):** the first `open()` still runs on the ears capture thread while it holds `feedLock`. `EarsSession.feed` takes the lock (`EarsSession.java:500`) and calls `direction.start()` inside it (`:532`), which runs `VoiceDirection.open()`. Nothing bounds the blocking tty open or the first write in `open()`. A node that blocks on open or write would stall the ears' capture thread. Move the first `open()` off the capture thread, or bound it, before relying on NC on the robot.

- **Keep a direction port away from the motor board's UART** (session history): the drive talks over a UART too, and the PR #28 session checked that a candidate direction port was not the drive's before going further. Any code or script that opens a direction port must not collide with it.

## Related Issues

- PR #18 (meeting and small talk) added `VoiceDirection` and the ears. PR #28 (merged, `12f2967`) was the first fix, the `/dev/ttyMT2` existence guard.
- Branch `feat/explore-hey-miko-always-answers` (unmerged) replaces that guard with the confirmed-port property and the Java protocol: `docs/plans/2026-09-28-1427-feat-explore-hey-miko-always-answers-plan.md`, U1 and U2 (KTD10 to KTD13).
- `docs/solutions/runtime-errors/kill-9-on-watched-service-permanently-disables-restart.md`: the other way this robot's supervised services stop coming back.
