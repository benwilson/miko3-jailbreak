# ToF / edge sensor readings

How the front ToF sensor's readings reach a mode app, from live captures on 2026-09-22 (firmware as shipped on this unit, ServiceExam disabled). Captured with `scripts/qa-explore-sensors.py`; sample records live in `scripts/tests/fixtures/explore_sensor_records/`.

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
  - A single digit, always `0` in the baseline. This is `ir2` in the decompiled parser, and a likely digital edge/obstacle flag, **unconfirmed**.
  - A trailing `X`-padding run.
- The POWER reply carries **no `CPL=` field**. The motion-refusal ack (`CPL=2`) must come in the reply to the drive frame (`VEL1`), which the owner-attended capture confirms (below).

## Baseline behavior

The robot sat still on the desk with nothing close in front:

- `tof` ranged 234–263 across 20 s, a spread of about 20–30. The value is live: it changes on every read. ToF is therefore enabled by default with ServiceExam disabled, and no `TOFEN` is needed.
- The frozen-`tof` rule (KTD3 in the explore plan) is viable. A still robot still jitters by tens of counts, so identical values over about 2 s (roughly 16 replies) mean a stuck sensor. The rule applies to `tof` only.
- `16383` (`0x3FFF`) is the known dead-sensor value (bent-pin fault, `docs/hardware/motors-wheels.md`).

Logcat kept 74 of the roughly 160 replies in a 20 s window, most likely because of logcat's own rate limiting. The mode reads replies directly, not through logcat, so this doesn't affect it.

## Still to capture (owner-attended, U8)

These steps need someone to move the robot or block the sensor, so they happen in the U8 calibration session:

- `tof` with a hand about 3 cm in front, and with the front held just past a table edge. This shows whether an edge reads high (the floor is far away) or maps to the digit field.
- What the single-digit field does at an edge and at an obstacle.
- Which reply carries `CPL=2` after a refused forward command.
- The units of `tof`. Millimetres is plausible from the baseline distance, but unconfirmed.
- Stopping distance: how far the robot travels after `stop()` from a forward hop.

Run `python3 scripts/qa-explore-sensors.py` (without `--non-interactive`) for the guided steps.
