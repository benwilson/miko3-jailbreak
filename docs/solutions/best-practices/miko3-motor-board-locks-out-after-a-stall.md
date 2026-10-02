---
title: "After a stall the Miko 3 motor board refuses all motion for several seconds, so escape logic must wait it out and back up first"
date: 2026-10-01
category: best-practices
module: "Miko 3 drive (shared DirectMotorDriver, mode-explore ExploreBrain escape, RECOVER and jam handling)"
problem_type: best_practice
component: infrastructure
severity: high
related_components:
  - "service_layer"
tags:
  - "motor-board"
  - "stall"
  - "cutout"
  - "escape"
  - "explore-mode"
  - "jam"
  - "under-furniture"
applies_when:
  - "Writing or tuning any escape, unstick or recovery logic that drives the Miko 3 after a bump or stall"
  - "The robot looks pinned: turns read 0 deg and back-ups read 0 counts right after a collision"
  - "He keeps getting stuck under desks or chairs and asks for help when the way behind him is clear"
---

# After a stall the motor board refuses all motion for several seconds

## Context

On 2026-10-01 the owner said he was "constantly getting stuck under this chair" and "all he has to do is back up." The robot log showed one pattern again and again:

```
14:33:42.9  wheels stalled while driving: blocked by something low
14:33:44    back-up stalled after 0 counts
14:33:46    measured turn blocked: turned 0 of 179 deg (RIGHT)
14:33:48    measured turn blocked: turned 0 of 181 deg (LEFT)
14:33:51.9  wriggle LEFT/RIGHT: wheels not moving -> fully jammed, asking for help
14:34:11.8  (debug spin hook, same driveTurnSustained command) wheels moving within 0.5 s, gyro ~2300
```

Forward, reverse and both turns all read zero at once, which looked like he was physically pinned. But 29 s after the stall, the identical turn command through the same lease-gated DriveGate moved him at once. The 14:08 episode had the same shape.

**After a drive stall, the motor board refuses all motion for a window of roughly 5-30 s, then recovers by itself.** Three robot recoveries that afternoon came back at the 10 s probe; one came back at 5 s. This is separate from the long-lived latch caused by repeated stalled pushing (auto memory [claude]: about 20 minutes of grinding, cleared only by a power cycle).

Three things made it worse:
- **The escape acted inside the window.** The old ladder tried a back-up, then turns both ways, all within about 5 s. Every move read zero, so he declared himself jammed and asked for help.
- **A probe turn into a desk leg is itself a stall, and it re-arms the window.** At 15:19 a recovery probe turn "moved 109 counts" with no rotation, which was the wheels slipping against a leg. The back-up one second later read zero inside the fresh cutout. At 16:42 a blocked turn just after a successful back-out did the same.
- **A long remote spin to "test" the motors drove him up onto a chair base, and he fell over.** Don't do that.

## Guidance

As implemented on PR #29 (branch `feat/explore-hey-miko-always-answers`; unmerged as of this writing):

1. **After any stall, or any move that reads zero, stop and wait (the RECOVER state).** Probe at 2, 5, 10 and 20 s after the stall (`stallRecoverProbesMs` in `ExploreTuning`).
2. **Probe by backing up, not by turning** (`stallRecoverProbeBackTicks` = 2). He drove in, so behind him is usually clear, and a back-up that moves frees him outright. Use a turn probe only after two back-ups in a row moved nothing.
3. **Treat "wheels moved, heading didn't" on a turn as "that way is blocked", not as recovery.** Switch the remaining probes to back-ups.
4. **Once the board is back, the first move is a full straight back-out, then a turn away from the blocked side.** After a blocked turn, back up a little more and wait about 3 s (`blockedTurnWaitMs`) before trying the other way, so he doesn't re-arm the cutout.
5. **Re-enter RECOVER on every zero move within a stuck spell, up to `recoverMaxPerSpell` (3).** Only then run the long wriggle, the help line and the jam rest. The jam rest probes by backing up at 30, 60 and 120 s (`jamProbeAtMs`), not just once after 2 minutes.
6. **Never grind.** The wriggle stops a direction as soon as the wheels move fewer than 30 counts in 1.5 s. A jam stops all motion, and a shove or outside movement probes at once.

## Why This Matters

Without the wait, every bump into furniture looked like a hard jam: he asked for help, rested, and pushed again, which is exactly what latches the board for good. After the RECOVER wait went in, his first three bumps on the robot all recovered at the 10 s probe and he carried on, with no help line and no human.

## When to Apply

- Any new behaviour that drives after a collision: escapes, seeks, approaches, docking.
- A log showing "back-up stalled after 0 counts" or "turned 0 of N deg" within seconds of "wheels stalled while driving" or "collision stop". That's the cutout, not a pin, unless it persists past about 30 s.
- Confirm a suspected real pin with the raw readings (`log.tag.MikoDmdRaw` DEBUG: the `Left=`/`Right=` counts and `IMUGY` z), not with long spins. Wheels moving with z flat means slipping or blocked. Both flat after 30 s of rest means a real pin, or the long-lived latch.

## Examples

A good recovery on the robot after the fix:

```
14:53:08.99  stall: waiting for the motor board to recover (probes at 2, 5, 10, 20 s)
14:53:19.23  recover probe at 10 s: moved 487 counts (turned 43 deg): the board is back
```

Related: [the ToF cliff sensor](miko3-tof-is-a-downward-cliff-sensor-inside-mcu-safe-band.md) (the forward refusal, CPL, is a separate MCU rule from this cutout).
