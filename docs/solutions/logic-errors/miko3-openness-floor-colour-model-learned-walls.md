---
title: "Explore's openness scorer learned white walls as floor, so it read open hallways as blocked and walls as open"
date: 2026-10-01
category: logic-errors
module: "mode-explore Openness (with ExploreCamera's openness frames and RoamSteer's bins)"
problem_type: logic_error
component: service_layer
severity: high
related_components:
  - "infrastructure"
tags:
  - "openness"
  - "free-space"
  - "camera-geometry"
  - "floor-colour"
  - "explore-mode"
  - "navigation"
symptoms:
  - "Standing in an open doorway, 4 of 6 captures scored all 16 openness bins 0.00, including the clear carpeted hallway"
  - "A plain white wall scored 0.56-0.81 open"
  - "Look-arounds never found a heading above 0.38, so seeks toward a real doorway gave up as blocked and he called himself boxed in"
root_cause: logic_error
resolution_type: code_fix
---

# The openness scorer learned white walls as floor

## Problem

Openness (`mode-explore/.../Openness.java`) scores 16 bins across each camera look for how open the floor is ahead. The steer, the look-around, the boxed-in rule and seeks all read it. On the robot on 2026-10-01 it was badly wrong in the office: open hallway carpet read 0.00, and a white wall read 0.81.

## Symptoms

- The six doorway captures (NavDebug `last-nav.txt`): four had every bin at 0.00. One read `0.11 0.13 0.56 0.81 0.81 0.27 0.20` on its right side, which was a wall.
- "seeking: gave up (the target reads blocked …)" while aiming at a doorway that Claude had correctly reported.
- Repeated "boxed in" retraces in open areas.

## What Didn't Work

- **Blaming the camera tilt.** The tilt does limit the floor to the bottom of the frame, but the old horizon value (0.80 from the top) was already close to the measured 0.82. The first hypothesis even had the sign backwards: a camera tilted *up* puts the horizon *below* the frame centre.
- **Trusting confidence.** It was always 0.90, because it only checked brightness and whether any colour had been taught, never whether the taught colour was floor.
- **Working around it in the brain.** Trusting Claude's doorway over openness (PR #29) helped seeks, but every other consumer stayed wrong.

## Solution

Measured on 46 hand-labelled robot frames with `scripts/eval-explore-openness.py`. The labels and 160x120 frames are in `scripts/tests/fixtures/explore_openness_eval/`, with people pixelated.

**Root cause: floor-colour teaching only accepted very even patches.** Plain walls are perfectly even, while carpet is grainy at ISO up to 3200. Of the 10 patches the old rule would teach, 7 were walls or a red pot and only 1 was floor. With a wall taught as "floor", the scorer reproduced the robot's numbers almost exactly. Its "standing surface" test then read the end of the hallway as right in front of him, so every bin came out 0.

**Fix: openness from camera geometry.**
- **A free-space boundary per strip.** In each 5 px strip of the frame, walk up from the bottom to the first clear colour edge. A surface that carries on past the horizon blocks the strip; otherwise the edge's row is converted to a distance with the camera geometry and scored (0.35 at about 1.25 m, 0.9 at about 3.4 m).
- **The colour model only caps an untaught floor colour.** Teaching now needs a patch the geometry says is floor, with evenness measured on strip averages so carpet passes.
- **Confidence means something.** It is 0.6 untaught and 0.9 taught.

| Labelled set (594 bins) | Accuracy, before → after | False-open, before → after |
|---|---|---|
| wall-taught (the robot's state that day) | 0.64 → 0.88 | 0.38 → 0.08 |
| model carried across frames | 0.54 → 0.88 | 0.63 → 0.08 |

## Why This Works

Geometry doesn't depend on what colour the floor is, so a mislearned colour can no longer flip walls to open and floor to blocked. The camera facts it relies on:
- the image is 640 px wide and the field of view is 62.6° (focal length about 526 px), from `scripts/calibrate-camera-fov.py`;
- the horizon sits about 0.82 down the frame, measured from where the hallway floor ends and where the door jambs converge;
- the camera is about 0.15-0.2 m above the floor.

## Prevention

- Any learned appearance model on this robot needs a geometric or semantic sanity gate before it teaches. "Even" is not "floor".
- Keep the labelled set and the evaluation script current. Run `scripts/eval-explore-openness.py` before changing Openness, and add frames from each new room the robot struggles in.
- Known gaps: there is no held-out set (the 46 frames were used both to tune and to measure). A very dark doorway (luma 7-9) is still misread. False-blocked rose slightly (0.11 → 0.19), because the new scorer is stricter in shadow.
- Not yet confirmed on the robot.
