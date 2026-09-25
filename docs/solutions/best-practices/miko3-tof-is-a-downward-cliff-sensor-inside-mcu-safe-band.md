---
title: "The Miko 3 front ToF is a downward cliff sensor gated by the MCU's own safe band, not a forward rangefinder"
date: 2026-09-22
last_updated: 2026-09-25
category: best-practices
module: "Miko 3 front ToF/edge sensor (shared DirectMotorDriver + SensorReply, mode-explore HazardClassifier)"
problem_type: best_practice
component: infrastructure
severity: medium
related_components:
  - "service_layer"
  - "tooling"
tags:
  - "tof-sensor"
  - "cliff-sensor"
  - "mcu-refusal"
  - "cpl-ack"
  - "sensor-calibration"
  - "nose-bob"
  - "explore-mode"
  - "hazard-classification"
  - "carpet"
  - "surface-change"
applies_when:
  - "Tuning explore mode's hazard thresholds or cornered cap, or reading its startle logs"
  - "Writing another autonomous mode that drives forward on the ToF/TOFIR readings"
  - "The robot stops well short of a desk edge, or startles on an open desk"
  - "A reply reads TOFIR=16383 and you need to decide between edge, lifted, and dead sensor"
  - "The robot moved to a new surface (desk to floor, or a different carpet) and now spins or startles in open space"
---

# The Miko 3 front ToF is a downward cliff sensor gated by the MCU's own safe band, not a forward rangefinder

## Context

Explore mode (PR #4, benwilson/miko3-jailbreak, unmerged as of writing) wanders a desk using the front ToF reading that the MCU returns in every `POWER` keepalive reply. `DirectMotorDriver.sendFrame()` parses each reply (`shared/src/com/miko3/shared/DirectMotorDriver.java:300-322`), `SensorReply` pulls out the `TOFIR=` fields and any `CPL=` ack (`shared/src/com/miko3/shared/SensorReply.java:33-76`), and `HazardClassifier` turns them into EDGE/OBSTACLE/CPL hazards (`mode-explore/src/com/miko3/mode/explore/HazardClassifier.java:115-142`).

The mode was designed as if the ToF were a forward rangefinder with an independent edge flag. Device captures on 2026-09-22 (raw-reply logging plus `scripts/qa-explore-sensors.py` and `scripts/qa-explore-mode.py`) showed otherwise. The robot always stopped about 20 cm short of the real edge, startled on an open desk, and hit the "cornered" rest after three hazards in 20 s. In the end it stayed in a small patch of desk. None of this was a code bug. It came from how the sensor and the MCU actually behave.

## Guidance

**1. The ToF looks down at the desk about 20 cm ahead. It is a cliff sensor.** A clear, flat desk reads a moderate value (about 190-260 counts depending on heading). A near object in the beam reads low. No surface in range (past an edge, or lifted) reads `16383`. The robot stops about 20 cm before the real edge because that is where the beam meets the edge. That margin is geometry. You cannot tune it away with thresholds. The units are still unconfirmed (millimetres is plausible), so the values here are called "counts".

**2. `ir2` is the MCU's own hazard flag, and `ir1` is absent.** The `TOFIR=` section is `<tof, 5 digits>,<ir1: always all-X padding = absent>,<ir2: one digit>,<X padding>`. `ir2` goes to 1 for **both** a near object and an edge, so it is a single "outside my safe band" flag, not an edge-only flag. `SensorReply` returns the all-X field as `ABSENT` (`SensorReply.java:97-109`), and `HazardClassifier.irEdge()` never treats an absent field as an edge (`HazardClassifier.java:153-159`). One consequence: with the calibration the session produced (`edgeIr>0`), the EDGE rule (`HazardClassifier.java:120-131`) fires before the OBSTACLE rule (`:135-137`). A hand in front is therefore reported as `EDGE`, not `OBSTACLE`. Do not rely on the hazard *kind* to tell an edge from an object. The `side` is also meaningless while only `ir2` exists (`ir1IsLeft` is unverified, `ExploreTuning.java:51-52`).

**3. The MCU keeps its own safe band of about tof 170-280, and it refuses forward motion outside it with `CPL=2`. Never override that.** Outside the band the MCU sets `ir2=1` and acks a forward command with `CPL=2` instead of driving. `CPL` appears in the `POWER` replies while the robot moves (`CPL=1` during motion, `CPL=2` when refused). Device captures recorded refusals at tof 166 (too close) and 289 (too far). The driver timestamps any reply that carries `CPL=2` in `lastRefusalMs` (`DirectMotorDriver.java:135-136, 252-256, 320-322`). `ExploreDrive` attaches `cpl=2` to a reading when a refusal landed no more than 250 ms before it, or at any time after it (`mode-explore/src/com/miko3/mode/explore/ExploreDrive.java:46-47, 234-235`), and the classifier reports that as a `CPL` hazard (`HazardClassifier.java:138-140`). The MCU's refusal is the last line of defence against driving off the desk. Treat it as authoritative. Do not resend forward to push past it, and do not widen app thresholds past the band expecting the robot to go there.

**4. The robot's nose bobs by 40-60 counts when a hop starts, stops, or turns.** The chassis rocks, so the downward beam sweeps nearer and farther along the desk. A reading can briefly leave the MCU's band on open desk. The MCU then refuses forward (`CPL=2`, `ir2=1`), and a brain that treats `ir2`/`CPL=2` as a hazard startles. Three of those within `capWindowMs` (20 s) and no successful hop in between trips the cornered rest (`ExploreTuning.java:47-50, 141-143`). Once the robot is still, readings settle back into range within about 250 ms. Some startles on open desk are therefore bounce artifacts rather than real hazards. Real clutter shows up differently: a steady fall in tof across several readings, not a single spike.

**5. `16383` has three meanings.** It can mean (a) no surface in range: past an edge, facing open space, or lifted, which comes with `ir2=1`; (b) the known dead-sensor value from the bent-pin fault (`docs/hardware/motors-wheels.md`); or (c) a transient stuck state. During the session it read a constant `16383`/`ir2=1` for a while, `TOFEN` did not change it, and it then recovered on its own. The classifier treats `16383` together with a calibrated IR edge flag as an edge to back away from, and `16383` without the flag as sensor-unavailable (`HazardClassifier.java:170-174`; `tofFault` default `ExploreTuning.java:136`). If it reads `16383` while sitting on the desk facing an object, reseat the sensor connector before calibrating.

**6. Calibrate on the robot, and read raw replies when anything looks odd.**
- `python3 scripts/qa-explore-mode.py --only calibrate` captures clear, hand, and edge readings. `suggest_calibration()` then puts `obstacleTofBelow` 30% of the way from the clear cluster toward the hand cluster, and it prefers an IR flag that separates edge from clear over a ToF threshold (`scripts/qa-explore-mode.py:72-111`). It writes `explore-calibration.properties` to the app's files dir (`:31`). The mode will not drive without a complete calibration (`HazardClassifier.java:57-61`).
- `adb shell setprop log.tag.MikoDmdRaw DEBUG` makes `DirectMotorDriver` log every raw reply, tagged with the frame that was sent (`DirectMotorDriver.java:263-290, 308-310`). `python3 scripts/qa-explore-sensors.py` switches this on and off for you and parses the lines (`scripts/qa-explore-sensors.py:25-27, 104, 125`). Look at `TOFIR=` and `CPL=` in the `sent=POWER` lines while the robot hops.

**7. Recalibrate whenever the surface changes, and take the "clear" numbers while he drives, not while he stands still.** The thresholds belong to one surface. On 2026-09-25 the robot moved from the desk to office carpet with the desk calibration still loaded (`obstacleTofBelow=157`). He then spun full circles in open carpet logging "no clear way" and rested as cornered. The escape turn only ends when the floor reads clear for `escapeClearMs`, and it never did. Carpet reads differently from the desk, and differently again in motion:
- standing still: about 175 (169-185);
- driving: 200-220, because the nose pitches up;
- starts, stops and turns: dips to 146-170, the nose bob again.

`--only calibrate` captures "clear" with the robot standing still, so on carpet it suggested 157 again, which sits inside the motion dips. The fix was to record raw replies with `MikoDmdRaw` for 30 s of roaming on open carpet, look at the tof histogram, and set `obstacleTofBelow=135`, below every in-motion dip. It was set by editing `explore-calibration.properties` in the app's files dir on the robot, keeping the gyro keys already in it.

Two related facts from the same session:
- Standing on carpet at about 175 sits just above the MCU's band floor (about 170). The MCU's own refusal (`CPL=2`) therefore still fired about 3 times in 6 minutes of roaming. It is firmware; app thresholds cannot remove it, and it must not be overridden (point 3).
- The ToF does not see a cabinet or wall the robot is pressed against: with his face on a cabinet it read about 175, plain floor. Walls and furniture are for the camera and wheel-stall detection, not this sensor.

**8. Available option, not adopted: a settle filter.** One way to cut bounce startles: stop at once on a hazard, wait about 300 ms still, and startle only if the hazard is still there. This was prototyped during the session, and the owner **declined** it. They accepted the edge margin and the occasional bounce startle as they are, so the current tree does not include it. If bounce startles become a problem later, this is the known lever. Keep the immediate stop and delay only the startle and cornered-count reaction, because the MCU refusal must still take effect at once.

## Why This Matters

If you read the ToF as a forward rangefinder, every one of these mistakes looks reasonable:
- Thresholds set tight around "distance ahead", which then trip on the nose bob.
- Retrying or overriding forward after a `CPL=2`, which fights the MCU's safety refusal.
- Chasing "phantom" hazards on an open desk that are really the robot rocking.
- Trying to tune away the 20 cm edge margin, which is set by where the beam lands.
- Reading `16383` as "sensor dead" when the robot is simply over an edge or lifted.
- Trusting `EDGE` vs `OBSTACLE` or left/right labels that a single shared `ir2` flag cannot support.

The existing hardware note (`docs/hardware/tof-sensor.md`) was written before calibration and before the band and bob findings were known, so it cannot warn about any of these.

## When to Apply

- Changing `ExploreTuning` hazard or cornered-cap values, or the calibration margin in `suggest_calibration()`.
- Building any new mode that drives forward autonomously on `latestSensors` / `lastRefusalMs`.
- Diagnosing "stops short of the edge", "startles on open desk", "always cornered", or "sensors unavailable".
- Before trusting hazard kind/side labels for behavior such as turning away from a specific side.

## Examples

**Calibration captures (device captures 2026-09-22, robot still):**

| Situation | tof | ir1 | ir2 |
|---|---|---|---|
| Clear flat desk (calibration capture) | 203-219 | absent (all X) | 0 |
| Clear flat desk, across headings | ~190-260 | absent | 0 |
| Hand ~5 cm in front | 38-50 | absent | 1 |
| Front held past the desk edge | 16383 | absent | 1 |

`suggest_calibration()` result: `obstacleTofBelow=157` (203 − 0.3 × (203 − 50) ≈ 157), `edgeIr>0` via `ir2`, `edgeTofAbove=-1` (off).

**Office carpet, desk calibration still loaded (device captures 2026-09-25, `MikoDmdRaw`):**

| Situation | tof |
|---|---|
| Standing on open carpet | 169-185 (mostly ~175) |
| Roaming on open carpet, 30 s, 241 readings | median 208, 5th percentile 153, min 146 |
| Pressed against a cabinet (face on it) | ~175, same as floor |

Histogram of the roaming capture: 140s: 4, 150s: 12, 160s: 5, 170s: 4, 180s: 15, 190s: 14, 200s: 93, 210s: 90, 220s: 4. With `obstacleTofBelow=157` the 140-160 dips read as obstacles; `135` cleared them, and the stops the owner checked afterwards were all real obstacles.

**Hop trace: the nose bob carries tof past the MCU band (device captures 2026-09-22):**

| Moment | tof | In MCU band (~170-280)? | MCU response |
|---|---|---|---|
| Before hop start | 239 | yes | drives (`CPL=1`) |
| Hop start, nose rocks | 289 | no (too far) | refuses forward, `CPL=2`, `ir2=1` |
| Still rocking | 321 | no | out of band (refusal expected) |
| Stopped, ~250 ms later | back in ~190-260 | yes | clear again |

A turn swung a reading from 235 to 190 in the same way. The refusal at 289 happened on open desk, so the brain logged a hazard during the hop and startled. Two more like it within 20 s put the robot in the cornered rest.

**Cluttered spot: real objects inside the look-ahead zone (device captures 2026-09-22):**

| Time into hop | tof |
|---|---|
| 0 ms | 220 |
| ... steady decline over ~4 polls ... | |
| ~500 ms | 166 (below band, MCU refused with `CPL=2`) |

The steady fall across consecutive replies (about 124 ms apart) is the signature of real clutter coming into the beam. Compare it with the one-reading spike-and-recover of a bob.

## Related

- `docs/hardware/tof-sensor.md`: record layout and first baseline. **Needs an update:** `:26` says the `POWER` reply carries no `CPL=` field, but the 2026-09-22 captures show `CPL=` in `POWER` replies while moving. `:24` still calls `ir2` "unconfirmed", but it is the MCU's shared near/edge flag. The owner-attended list (`:47-55`) is now mostly answered (hand, edge, `ir2` behavior, which reply carries `CPL=2`). Units and stopping distance remain open.
- `docs/hardware/motors-wheels.md`: the bent-pin `16383` fault.
- `docs/solutions/integration-issues/soundpool-plays-silently-on-miko3-use-mediaplayer.md`: the explore-mode startle chirp this behavior triggers.
- PR #4 (benwilson/miko3-jailbreak, unmerged as of writing): explore mode, including the fix for absent IR fields and edge-flagged fault readings.
