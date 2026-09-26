---
title: "A stop that resets settle state before reading it discards every look: Explore's camera never steered"
date: 2026-09-25
category: logic-errors
module: "Miko 3 explore mode brain (mode-explore ExploreBrain.stopMotors, RoamSteer)"
problem_type: logic_error
component: service_layer
symptoms:
  - "On the robot, a 7-minute roam logged zero 'steer:' notes although the camera was open and looks were logged"
  - "The steer, the doorway pull and go-somewhere-new never ran; legs were chosen as if the camera were off"
  - "No error or warning anywhere: the only sign was a missing log line"
root_cause: logic_error
resolution_type: code_fix
severity: high
related_components:
  - "testing_framework"
tags:
  - "explore-mode"
  - "camera-steering"
  - "state-ordering"
  - "stale-look"
  - "live-only-bug"
applies_when:
  - "A stop or reset path clears a flag that a later check in the same path reads"
  - "A feature is gated on 'fresh' data and simply never fires, with no error"
---

# A stop that resets settle state before reading it discards every look

## Problem

Explore steers by camera looks, but only by looks taken after the last turn
ended: a look from before a turn faced somewhere else. `stopMotors()` decided
whether the move that just ended was a turn by asking `drivingForward()`, which
reads `moving`. It cleared `moving` first:

```java
moving = false;
motor.stop();
...
if (!drivingForward()) {          // always true now: moving is already false
    headingSettledAt = now;       // every leg end looked like a turn
}
```

So every leg end reset `headingSettledAt`, every look taken during the leg was
"from before the turn", and the steer never had a usable look. Nothing failed
loudly: the brain fell back to choosing legs as before the camera roamed.

## Fix

Read the state before resetting it (commit `5c08014`):

```java
boolean forward = drivingForward();   // asked before moving goes false
moving = false;
...
if (!forward) { headingSettledAt = now; }
```

The same commit added a bounded wait (`steerWaitMs`, 2 s) for a fresh look at
each leg decision, and a mid-leg re-aim toward open space.

## Lesson

- In a stop or reset path, capture every value a later branch needs before
  clearing the flags it derives from.
- A feature gated on fresh data should log when it goes unused. Here the only
  signal was a missing note, found by counting `steer:` lines in a live log.
  `python3 scripts/qa-explore-mode.py --only roam-summary` now prints that count
  (with re-aims, CPL hiccups, wedges and coverage), so a zero shows up at once.
