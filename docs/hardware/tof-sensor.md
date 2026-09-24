# ToF / edge sensor readings

How the front ToF sensor's readings reach a mode app, from live captures on 2026-09-22 (firmware as shipped on this unit, ServiceExam disabled). The sensor looks **down** at the surface about 20 cm ahead of the robot: it is a cliff sensor, not a forward rangefinder. What that means for driving is in `docs/solutions/best-practices/miko3-tof-is-a-downward-cliff-sensor-inside-mcu-safe-band.md`. Captured with `scripts/qa-explore-sensors.py`; sample records live in `scripts/tests/fixtures/explore_sensor_records/`.

## Where the readings come from

There is no free-running sensor stream. `DirectMotorDriver`'s keepalive thread writes the 500-byte `POWER` poll frame every 100 ms, and the MCU answers each poll with one 500-byte text record. `SensorModule.read()` returns that record whole, one per read. Consecutive replies were logged about 124 ms apart.

Reading the reply inside `sendFrame` keeps a single reader on `/dev/ttyS2`. Nothing else may read the device node (see `DirectMotorDriver`'s class comment).

## Record layout

One record, with padding runs shortened:

```
POWER=0,0,07884,-0234,07887,-0234,076,14,15,23FLBTN=0,0,0,0,00000,00000IMUAC=...XIMUGY=...IMUMG=...IMUTM=...,GLPOS=00000,00000,00000TOFIR=00251,XXXX…XXXX,0,XXXX…XXXXGSTFL=0,0,0,0,0,0,0HEADTM=...Left=0000000000,Right=0000000000,00,00,00000,0,0
```

- Sections are keyed by an uppercase token followed by `=`, and are **not** comma-separated from each other. Find each section by its token, never by offset: `TOFIR=` sat at a different offset from every decompiled guess.
- `X` is used as padding **inside** the record, not only at its end. A field made entirely of `X` is absent.
- `TOFIR=` carries four comma-separated fields:
  - `tof`: a five-digit ToF reading.
  - A field that was all `X`, meaning absent, in every capture. This is where decompiled code expected `ir1`.
  - A single digit (`ir2` in the decompiled parser): the motor controller's own hazard flag. It is `0` on clear desk and `1` for **both** a near object and an edge, so it cannot tell them apart.
  - A trailing `X`-padding run.
- `CPL=` (the motion ack) is absent from POWER replies while the robot is still, and **appears in them while it is moving**: `CPL=1` while driving, `CPL=2` when the controller refuses forward motion because the reading is outside its safe band (see Calibrated behavior).
- `Left=`/`Right=` are the wheel encoder counts (10 digits, each followed by a comma). They climb while the wheels turn: about 650–880 counts a second per wheel driving forward on the floor, ~50 per 125 ms reply turning in place (2026-09-24). **They stand still while the wheels are stalled.** Pushed against something too low for the ToF to see (tof reading clear floor, ~200), the controller kept acknowledging forward (`CPL=1`) for 8 s while both counts didn't move. Explore mode's stall rule reads this (fewer than 10 counts in a second of a forward leg). An earlier session saw these counts never change, but at that time the robot wasn't actually driving.

## Baseline behavior

The robot sat still on the desk with nothing close in front:

- `tof` ranged 234–263 across 20 s, a spread of about 20–30. The value is live: it changes on every read. ToF is therefore enabled by default with ServiceExam disabled, and no `TOFEN` is needed.
- The frozen-`tof` rule (KTD3 in the explore plan) is viable. A still robot still jitters by tens of counts, so identical values for the brain's 3 s window mean a stuck sensor. The rule applies to `tof` only.
- `16383` (`0x3FFF`) is the known dead-sensor value (bent-pin fault, `docs/hardware/motors-wheels.md`).

Logcat kept 74 of the roughly 160 replies in a 20 s window, most likely because of logcat's own rate limiting. The mode reads replies directly, not through logcat, so this doesn't affect it.

## 16383 with the flag digit set

About 20 minutes after the baseline, with no code change to the parsing, every reply read `TOFIR=16383,…,1` (40+ consecutive replies). Sending `TOFEN` did not change it, so the ToF had not been switched off. Two readings fit:

- **Out of range, or an edge.** The robot was moved, lifted, or is facing open space, and the flag digit marks "no surface / edge". If the U8 edge capture shows this signature, `scripts/qa-explore-mode.py` calibrates the edge on that flag. The classifier then treats `16383` plus the flag as an edge to back away from, rather than a dead sensor.
- **The old hardware fault.** The bent connector pin also produced a constant 16383 (`docs/hardware/motors-wheels.md`). If the sensor reads 16383 while the robot sits on the desk facing an object, reseat the connector before calibrating.

Either way the mode fails safe: without the flag rule calibrated, a constant 16383 means "no sensors", and the robot stays still with its eyes-only look.

## Calibrated behavior (owner-attended session, later on 2026-09-22)

| Situation | tof | `ir2` |
|---|---|---|
| Clear flat desk | 203–219 in the calibration capture; about 190–260 across headings | 0 |
| Hand about 5 cm in front | 38–50 | 1 |
| Front held past the desk edge | 16383 | 1 |

- The edge signature is `16383` with `ir2=1`, which confirms the first reading of the "16383 with the flag digit set" section above: an edge, or open space.
- The controller's own safe band is roughly **170–280**. Outside it, the controller sets `ir2=1` and refuses forward motion (`CPL=2`). Refusals were seen at 166 (too close) and 289 (too far).
- Because the sensor looks down, the robot's nose bobbing as a hop starts, stops or turns swings the reading by 40–60 counts. That can briefly leave the band on open desk, and it settles within about 250 ms once the robot is still.
- The calibration `scripts/qa-explore-mode.py` wrote from these captures is `obstacleTofBelow=157`, with the edge detected by `ir2 > 0`.

## Still open

- The units of `tof`. Millimetres is plausible, but unconfirmed.
- Stopping distance: how far the robot travels after `stop()` from a forward hop.
