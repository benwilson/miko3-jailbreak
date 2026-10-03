---
title: "From floor height the Miko 3 cannot tell people from furniture by detector score and rarely sees a usable face, so only a real call may open a faceless meeting"
date: 2026-10-01
category: best-practices
module: "Miko 3 explore mode meetings (mode-explore ExploreBrain, ChatSession, ExplorePrompts, ExploreTuning, FaceQuality; shared FaceSettings)"
problem_type: best_practice
component: infrastructure
severity: medium
related_components:
  - "service_layer"
  - "tooling"
tags:
  - "person-detection"
  - "face-recognition"
  - "yoloe"
  - "yunet"
  - "false-positive"
  - "camera-viewpoint"
  - "explore-mode"
  - "meeting-rules"
applies_when:
  - "Deciding whether a YOLOE person box is a real person: furniture and shadows score about 0.53 and people from the floor 0.55-0.75, and box persistence does not separate them"
  - "Learning or asking a name, where YuNet faces seen from the floor are 25-46 px, under FaceSettings minWidth 48"
  - "Changing what a faceless conversation says or stores: it asks the name, keeps notes by the name alone, and checks the face silently in the background (R19 as changed 2026-10-02)"
  - "Opening a meeting from roaming or a voice cue versus a real call (wake word or name)"
  - "A call search on a slow detector that discards frames showing the caller as stale"
resolution_type: code_fix
---

# From floor height, a usable face is the only reliable sign of a person

## Context

On 2026-10-01 the owner reported two problems from a day of explore mode in the office. Miko greeted things that were not people: a red plant pot, a doorway, a wastebasket. He also asked three regulars their names and remembered none of them.

Both came from the same weak point. The object detector's `person` class is a poor signal at the robot's floor-level viewpoint. When it is right, the face behind it is usually too small to keep.

**False person boxes.** The robot log for that day has `look in` lines from `ExploreCamera` that list each detection's score and box. Saved frames matched to those lines show:

- the shadow under a desk scored `person 0.54 [0.00,0.00,0.40,1.00]` (frame f14);
- a dark chair edge scored `person 0.53 [0.00,0.00,0.12,0.99]` (frame f17);
- the seated owner, a real person, scored only 0.59-0.75 (frames f02, f11).

Real people and furniture overlap in score, so no person-score threshold separates them. A host check ran the old and new detector models on these frames and got the same scores, so this is not a regression from the detector model swap. It is how the class behaves on this camera.

**Faces too small to keep.** A worker measured 49 readable frames with 178 person boxes. Every YuNet face it found was 25-46 px wide. All of them fall below the face-quality gate's minimum width of 48 px (`FaceSettings.DEFAULTS`, shared/src/com/miko3/shared/FaceSettings.java:19; the check is at mode-explore/src/com/miko3/mode/explore/FaceQuality.java:130, which returns `Reason.TOO_SMALL`). Of 54 distinct person boxes, 36 touched the top of the frame: from the floor, the head is often cut off or barely in view. The log shows the result: `person match: rejected as TOO_SMALL; meeting without storing anything`, then `a name given; no face to keep them by, so nothing is stored`.

**Earlier sessions saw the same limit without measuring it (session history).** On 2026-09-25, the first greet-by-name test stored a photo of the wall. The fix then was to store a face only when a face detector actually finds one inside the person box. The same session noted that a standing person is "mostly legs" to his camera and logged it as a design issue. On 2026-09-28, the latency-baseline meetings kept logging "0 found, none in the person box". Every meeting that worked needed the owner to crouch about a metre in front of him, square-on. Those sessions blamed pose and height and never logged face sizes, so the sub-48 px rejection went unnoticed until this measurement.

Sharper detection did not help. YuNet normally runs on the 640x480 frame halved to 320x240 and padded to 320x256 (`YuNetDecoder.INPUT_W`/`INPUT_H`, mode-explore/src/com/miko3/mode/explore/YuNetDecoder.java:40-41; described at FaceCropper.java:36-37). On the same frames the half-res full frame found 12 faces, a full-res head crop found 11, and the full frame at 640x480 found 11. The faces are small because the people are far away and above the camera, not because the input is downscaled.

## Guidance

**Treat a usable face as the proof that a person box is a person.** Do not trust the detector's person score, and do not trust a box that persists across looks. As the owner put it: "If he can't get a good capture of the person's face, then he probably shouldn't be trying to talk to them."

The rules on PR #29 (branch `feat/explore-hey-miko-always-answers`, not yet merged):

1. **Roaming and cue meetings need a usable face.** In `matchAnswered` (mode-explore/src/com/miko3/mode/explore/ExploreBrain.java:3090-3100), a roaming pick or a non-call voice-cue pick (`roamingPick`, `cuePick`, fields at ExploreBrain.java:664-674) whose face check is not usable goes to `phantomPerson` (ExploreBrain.java:3121-3128). It is dropped with nothing said and logged as `no usable face in the roaming person pick's box` or `no usable face in the cue's person box`. Roaming person picks are then ignored for `phantomPersonCooldownMs` (20000 ms, ExploreTuning.java:949; the check is at ExploreBrain.java:4948), so he does not go straight back to the same shadow.
2. **A usable face** is defined in `usableFace` (ExploreBrain.java:3108-3111): a `KNOWN` match, or a `NEW` face that passed the quality gate (`!a.faceless`). No face, a crop rejected as too small, dark or blurry, face models not ready, and a failed or timed-out check all count as unusable.
3. **Calls may be faceless.** Someone said "Hey Miko" or his name, so a call's meeting is never dropped (`!callsOwn()` in the same condition; `callsOwn` is at ExploreBrain.java:5595). What a faceless conversation does was changed by the owner on 2026-10-02 (see "R19 as changed" below): he asks the name and remembers by it, and checks the face silently. The first version, superseded the same day, asked people to crouch to his level, asked no name, retried the face 3 times and discarded the notes of anyone with no face.

**Related fix in the same PR: a person in a stale frame still gives a bearing.** The detector takes 1.4-4.8 s per look. During a call search the robot is turning, so by the time a frame showing the caller is decoded he has turned past them. Such frames used to be discarded as stale, and the search went on blind; the owner was seen 37 and 61 deg off. Now:

- a person in a look captured within `callStaleLookDeg` (30 deg, ExploreTuning.java:1002) of his current heading is found at once (ExploreBrain.java:5694-5700);
- one captured farther off becomes a bearing (`seenBearing`, ExploreBrain.java:5755-5757: the capture heading plus the box's position in the frame). He turns to that bearing and takes one confirming look (`turnToSeenCaller`, ExploreBrain.java:5767-5799).

This happens at most `callSeenRetargetsMax` = 2 times per call (ExploreTuning.java:1004, enforced at ExploreBrain.java:5771), so a series of blurred boxes cannot swing him back and forth.

**Update 2026-10-02 (owner, at home: "he doesn't really talk to us").** Rule 1 was too strict: from the floor a face is almost never usable, so he met nobody unless called (home log 20:44: people at score 0.76 and 0.80 dropped). A roaming or cue pick with no usable face is now met as a faceless conversation (crouch invitation, no name asked) when its person box scored at least `facelessMeetMinScore` = 0.65 or a voice from the person's known side landed within `facelessMeetVoiceMs` = 5000 ms (`ExploreBrain.facelessMeetable`). Weaker picks with no voice stay phantoms; furniture scores about 0.53. A shove's cue has no side and never counts: at home, a stopped robot logged `shoved: ~3700 counts` every 0.5 s, and each one was noted as "a voice from the person's side".

**R19 as changed 2026-10-02 (owner, at home: "There should be less interruptions as he tries to find your face. Rather than trying to force someone to show them their face, he should always be looking for their face to try and identify them. But he can ask a question and just say 'What's your name?' and then base his conversation off the name.").** The old R19 was "a faceless conversation asks no name and discards its notes". Now:

- **He never asks to see a face.** The crouch invitation is gone: `CANT_SEE`, `FACE_SEEN` and the crouch text in `FACELESS_OPENER` were removed. `FACELESS_OPENER` greets them and asks their name ("What's your name?"); a call's opener still greets first, and `NAME_ASK` lets the next turn ask the name while he doesn't know it (`ChatRound.body`).
- **The face is checked silently for the whole conversation.** `ChatSession.faceStep` takes one fresh look and runs the meeting's match `chatFaceDelayMs` (1000 ms) into a listen and at least `chatFaceGapMs` (8000 ms) after the last check, with no cap (`chatFaceTries` is gone). Checks run only in a listen with the camera open (`faceLooksAllowed`), never while he speaks (KTD7), and never cause a line: logged as `face check N: no usable face; he keeps looking quietly`.
- **Memory by name.** A name given with no usable face is used at once and looked up by the name alone (`CuriosityPort.recallName`, `NameResolver.byName`): a stored name equal to the one given wins, so a full name picks its person when several share a first name; a first name only one person has finds them; several sharing it and none equal loads and stores nothing. Someone found lends their notes (the system prefix changes once; `ExplorePrompts.recalled` tells that turn who they are). Nobody found: the launcher keeps a new record **by the name alone** (`PeopleStore.addNamed`, Binder `addNamed`/`hasFace`, transactions 19-20), and the notes persist to it. Still no name, no notes: an unnamed conversation's notes are discarded.
- **A usable face settles who they are, silently** (`ChatSession.faceAnswered`). Unnamed and the face is KNOWN: he goes on as that person, with their notes. Unnamed and the face is new: it waits for their name (the meeting's own resolve, then `keep`). Named by a record with no face: a new face is enrolled to it (`addPhoto`). A face that is not the stored face of the person the name found: the name stands and nothing more is stored. In a meeting with a face in hand, `NameResolver.resolve` joins a name-only record whose stored name equals the name given, so the face is enrolled there too.
- **Privacy is unchanged.** Names never reach a log (ids and counts only); Forget on the Settings page deletes a name-only record and its notes (`PeopleStore.forget`, which also runs the forget hooks for other stores keyed by the same id, such as voice prints); owner's notes stay separate and still need the full name spoken.

Not yet measured on the robot: whether the endless checks (one look and match about every 8 s) cost too much CPU or upset the camera HAL over a long conversation, and how often a bystander's face is matched instead of the partner's (the largest person box wins).

## Why This Matters

Without the face rule, every chair edge or desk shadow at 0.53 is a meeting. He drives up to furniture and talks to it, and the people in the office see it. Even with real people, asking a name he cannot store makes him look forgetful: the regulars told him their names and he greeted them as strangers the next time.

The usable-face rule fixes both with one check that already exists, because the face match runs anyway before a meeting can store someone. It needs no new model and no extra Claude call.

Approaches tried or considered and rejected:

- **Persistence rescue** (a person box in 2 of 3 still looks counts as a person). This let static furniture through, since furniture stays in place as well as people do. Do not try it again.
- **Claude person-confirmation** (send the crop to Claude and ask whether it is a person). This was considered and dropped in favour of the simpler face rule, which the owner chose. It also adds latency and cost and still would not give a face to store.
- **Asking the name with no face, storing nothing.** This was pointless: `a name given; no face to keep them by, so nothing is stored`. Superseded on 2026-10-02 by memory by name, not by not asking.
- **Asking people to crouch so he can see their face** (2026-10-01 to 10-02). The owner found it an interruption; the face is now checked silently instead.
- **Higher-resolution face detection** (full-res head crop, 640x480 full frame). This found no more faces than the default half-res path (11 vs 12). The faces really are small.

## When to Apply

- He greets or approaches objects (plants, doors, bins, chair edges, shadows). Check the `look in` line's person score and box before touching detector thresholds. Edge-hugging, full-height boxes around 0.5 are the usual signature.
- He meets people but forgets them. Grep the log for `TOO_SMALL` and `no face to keep them by` before suspecting the people store or the matcher.
- You are considering a new way to decide "this is a person". Run it on the saved frames first. Score thresholds and persistence both fail on this camera.
- A call search turns past a caller who was visible in an earlier look. Look for `a caller in a look captured ... deg from here` lines and the retarget count.
- **Open question:** could `minWidth` (48 px) safely drop? Nearly every face seen from the floor is 25-46 px, so today almost no roaming face passes. This has **not** been measured. Before lowering it, measure SFace match quality (same-person and different-person similarity) on real faces of 32-46 px. A lower threshold that produces false matches would be worse than storing nothing. Related background is in docs/solutions/tooling-decisions/on-device-face-recognition-yunet-sface-onnx-runtime-miko3.md and docs/solutions/tooling-decisions/on-device-object-detection-yoloe-onnx-runtime-miko3.md.

## Examples

A phantom dropped while roaming (the shape of the note from `phantomPerson`):

```
no usable face in the roaming person pick's box (no face, or one rejected): not meeting them; roaming on, roaming people ignored for 20000 ms
```

The detections that caused it on 2026-10-01:

```
look in 1192 ms: [speaker 0.66 [...], person 0.54 [0.00,0.00,0.40,1.00], ...]   <- shadow under a desk
look in 1554 ms: [chair 0.87 [...], ..., person 0.53 [0.00,0.00,0.12,0.99], ...] <- dark chair edge
```

A faceless conversation since 2026-10-02 (ChatSession notes; no names in them):

```
a name given with no usable face yet: looking the name up in the people stored
nobody stored has that name: kept under the name alone, no face yet; the notes persist to it
face check 2: no usable face; he keeps looking quietly
a usable new face on check 3: enrolling it to the person kept by name alone
the face is enrolled to the person kept by name alone
```

A later conversation where they give the same name:

```
the name belongs to someone stored by name alone: their notes join the conversation (2 questions on record)
```

A call search that turns to a caller seen in a stale look (format of the note; the numbers are illustrative):

```
a caller in a look captured 61 deg from here, 24 deg to the left: turning to face them (1 of 2)
```
