---
title: "The vendor NC direction DSP's failure code reads as success, then its next call segfaults the launcher"
date: 2026-09-28
last_updated: 2026-09-29
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

The design below is on branch `feat/explore-hey-miko-always-answers` (PR #29, unmerged as of this writing) and was run on the robot on 2026-09-29. No vendor code runs on the NC path any more.

**The port comes only from the owner.** The NC backend runs only when `persist.miko3.voice_dir.port` names an existing node (`VoiceDirection.openNc`). The owner confirms the port with `scripts/qa-direction-chip.py`, whose identification reads only `/proc`, `/sys` and logs and never opens the port. On this robot the owner confirmed `/dev/ttyS1` after its counters showed a device talking on it.

**The chip streams; the launcher only listens.** On the robot, the chip on `/dev/ttyS1` sends a 19-byte frame by itself about once or twice a second, whether anyone speaks or not: `XXUB`, module `a3`, op `03`, a sequence byte, `01 05 00`, a CRC32 little-endian over the first 10 bytes, then a 5-byte payload whose first byte is the raw value (for example `58585542a30344010500de9c7afe55f64a03c9`, raw 85). `NcFrames.parseStream` keeps CRC-valid `a3`/`03` frames and skips everything else, and `VoiceDirection` keeps the newest, fresh for 1.5 s. It never writes to the chip: the query/toggle protocol the disassembly suggested (below) turned out to be unnecessary, and the chip's replies to it were just more stream frames.

**The port is configured with `stty`, not the vendor library,** because with the vendor's `createUART`/`initNCUART` handle in the process the launcher died seconds after each open (this is suspected, not bisected: the probe crash below muddied it). toybox 0.7.6's `stty` has its own trap, recorded in `docs/solutions/tooling-decisions/toybox-stty-cannot-clear-input-flags-miko3.md`.

**The chip gives a side, not a direction.** With the owner about 1 m away counting aloud, the raw value read right about 35, front 80, behind 90 and left 100: it separates left from right but not front from back, so no full-circle calibration fits. `persist.miko3.voice_dir.left` and `.right` (100 and 60 on this robot) map raw values to left, right or neither, and the cue carries the side with no angle (`NcFrames.sideDegrees`, `EarsSession.Direction.sideOnly`).

With no direction the camera meeting look decides, as before.
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

The vendor app never reached the crash state. `DSPSettings` opens NC only when its caller has already set `isNC`, and it picks `/dev/ttyS1` on "JoyAR" units (`DSPSettings.java:41`, `:177`). That turned out right: the chip is on `/dev/ttyS1` on this robot.

## Prevention

- Before any vendor native `init`/`open` call, check what the call needs on this unit yourself: the device node exists, and the vendor's own detection flag is set. Treat a vendor return code as unproven until the disassembly says what it means: here 0 is success and 1 is failure, and both our `>= 0` check and the vendor's own `< 0` check read the failure as success.
- Listen before you speak to an unknown serial peripheral: this chip streams by itself, and the request/reply protocol read out of the disassembly was never needed.
- Never open an unconfirmed tty "read-only" in cooked mode: with echo on, the kernel sends received bytes back out. An `stty -a < /dev/ttyS1` for evidence took this port's counters from tx 0 to tx 98.
- Keep a separate diagnostic tool from the thing it diagnoses: the launcher's ears probe (`scripts/qa-ears-probe.py`) crashes the launcher on `main` too, and every chip test routed through it looked like a chip crash until the probe was run with the chip off (`docs/TODO.md`).
- For a native crash on this robot with no tombstone, find the component by bisection on the device rather than reading crash sites:
  - Build a diagnostic-only launcher in a worktree (never committed) whose ears components each check a debug property, for example `debug.miko3.ears.off=wake,vad,rec,dir`. Toggle them live with `setprop`, with 5-minute survival windows.
  - Then add step logs around each native call, plus a startup hook that calls the suspect `open()` once, so the reproduction doesn't depend on someone speaking.
  - Start with all four off for five minutes, then halve.
- Confirm the ears are actually open before trusting a quiet window: `ListenEngine: ears: chunks=` should be climbing. Bringing the launcher's activity to the front stops Explore, and with it the ears.
- Tests:
  - `test_voice_direction_tries_nc_only_on_the_confirmed_port_property_and_node` in `scripts/tests/test_listen_service.py` pins the source shape: no `/dev/ttyMT2`, the port property before the node check before `configure(node)`, no vendor NC calls, and no port writes.
  - `scripts/tests/test_nc_frames.py` runs a JVM harness through `VoiceDirection.openWith()` with a fake port setup and a fake streaming node, using frames captured on the robot: parsing, junk and torn frames, freshness, the `stty -g` rewrite, side mapping, no writes, and the lazy open off the capture thread.
  - The robot is still the behavioural check: the counters in `/proc/tty/driver/serial` show whether anything wrote to the port.
- **Keep a direction port away from the motor board's UART** (session history): the drive talks over a UART too, and the PR #28 session checked that a candidate direction port was not the drive's before going further. Any code or script that opens a direction port must not collide with it.

## Related Issues

- PR #18 (meeting and small talk) added `VoiceDirection` and the ears. PR #28 (merged, `12f2967`) was the first fix, the `/dev/ttyMT2` existence guard.
- Branch `feat/explore-hey-miko-always-answers` (unmerged) replaces that guard with the confirmed-port property and the Java protocol: `docs/plans/2026-09-28-1427-feat-explore-hey-miko-always-answers-plan.md`, U1 and U2 (KTD10 to KTD13).
- `docs/solutions/runtime-errors/kill-9-on-watched-service-permanently-disables-restart.md`: the other way this robot's supervised services stop coming back.
