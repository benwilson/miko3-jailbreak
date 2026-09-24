---
title: Explore Mode on Claude - Plan
type: feat
date: 2026-09-24
topic: explore-on-claude
artifact_contract: ce-unified-plan/v1
product_contract_source: ce-brainstorm
execution: code
---

# Explore Mode on Claude - Plan

## Goal Capsule

- **Objective:** At each curiosity stop, the robot notices what is genuinely interesting to him (people first, then animals, then technology), says something personal and lively about it in words Claude wrote, remembers the people he meets across days by face and by name, and then returns to exploring.
- **Product authority:** This Product Contract. It covers Explore's curiosity stops, people memory, and the People section of the Settings page. The robot's voice is a separate plan and is used as-is; Voice mode on Claude is not in scope.
- **Open blockers:** None.

---

## Product Contract

### Summary

Claude becomes the robot's curiosity. At each stop, his scan frames go to Claude in one call; Claude picks the most interesting thing and writes what he says, and he turns toward it and speaks through the launcher's speech service. He remembers faces on the robot indefinitely: a new person is asked their name, which he hears with on-robot speech recognition, and a second Claude call writes a personal "nice to meet you, I'll remember you" line. Known people are greeted by name. The Settings page lists the people he knows, each with Forget and Rename.

### Problem Frame

Explore's curiosity stops today recognize only 341 fixed object names with an on-robot detector, pick the biggest box, and say one pre-recorded clip ("ooh, a plant"). He treats a person, a pet, and a chair the same way, repeats himself, and forgets everyone. The owner wants him to talk more about what he sees, care most about living things and technology, speak to people and about animals with compliments and jokes, get more excited about anything new and most of all new people, and know people again later. The robot can now reach Claude (the Claude settings plan) and speak any sentence (the robot voice plan, placeholder voice for now).

### Key Decisions

- **Claude picks the target and writes the words; the on-robot detector only steers.** (session-settled: user-directed — chosen over the detector picking the target with Claude only commenting, and over Claude steering every correction: Claude should do the choosing, and steering must stay fast.) Governs R1, R2, R3, R4.
- **Every line he says is written by Claude; nothing he says in a curiosity reaction is canned.** (session-settled: user-directed — decided by the owner at scope confirmation.) Governs R5, R6, R11.
- **A slow or failed Claude call is retried before falling back.** (session-settled: user-directed — the owner asked that he prompt Claude again rather than give up; the retry limit is this plan's choice.) Governs R7, R8.
- **People are remembered across days by face, kept until the owner forgets them, and only once they reply.** (session-settled: user-directed — chosen over session-only text memory, no people memory, and automatic expiry; and over storing faces of people who don't answer.) Governs R9, R10, R12, R13, R14.
- **A new person is asked their name, heard with on-robot speech recognition.** (session-settled: user-directed — chosen over sending the reply to the Linux host and over asking without listening.) Governs R11, R12.
- **Explore goes on Claude before Voice mode.** (session-settled: user-directed — chosen over Voice first.)

### Requirements

**Choosing and reacting**

- R1. At each curiosity stop, the frames from his scan turns go to Claude in one request, which returns the most interesting thing (or nothing worth reacting to), which frame it is in and roughly where, what it is, and the line he says.
- R2. Claude's choice follows the owner's priorities: people first, then animals, then technology, then anything else; things and people he has not seen before are more interesting than familiar ones.
- R3. He turns toward the chosen thing. When the on-robot detector recognizes it, the detector guides the approach as today; when it does not, he faces the reported direction without driving up to it.
- R4. When Claude reports nothing worth reacting to, he carries on exploring without speaking.
- R5. What he says to a person is addressed to them (a greeting, compliment, or joke); what he says about an animal or thing is about it. Lines are short enough to speak in a few seconds and sound excited, especially for anything new.
- R6. He speaks after the scan's looks are finished, not while the camera and detector are busy, so speech stays fast (robot voice plan KTD8).

**When Claude is slow or unreachable**

- R7. While waiting for Claude he shows his thinking eyes. A request that times out or fails is retried; after 2 attempts of about 10 seconds each, he gives up for this stop.
- R8. Giving up means today's behavior: the detector's pick and its pre-recorded name clip, or nothing if the detector found nothing. He never freezes or goes silent for longer than the retry limit.

**People**

- R9. When the chosen thing is a person, he compares their face with the people he has already met and learns whether this is someone known, and their name if one is stored.
- R10. A known person is greeted by name in a Claude-written line that shows he remembers them; an unnamed known person is greeted as someone he has seen before.
- R11. A new person is asked their name in a Claude-written line. After he finishes speaking he listens for a few seconds, hears the reply with on-robot speech recognition, and stores the name. A second Claude request, given the name and the person's photo, writes a personal line about them that tells them he will remember them.
- R12. A face is stored only once the person answers (a name or any reply he can hear): talking to the robot is the consent to be remembered. If he hears no reply, he says a friendly Claude-written line and does not keep their face. If he hears a reply but no clear name, he keeps the face as an unnamed person, whom the owner can name on the Settings page.
- R13. Face images stay on the robot and are kept until the owner forgets that person. They leave the robot only inside requests to the configured Claude endpoint.
- R14. Each face comparison includes at most a fixed number of stored faces, most recently seen first, so requests stay bounded as he meets more people.

**People on the Settings page**

- R15. The Settings page has a People section listing everyone he remembers, with their face, name (or "unnamed"), and when he last saw them.
- R16. Each person has a Forget button, which deletes their face and name for good, and a Rename field, which changes the name he uses next time.

### Acceptance Examples

- AE1. **Covers R1, R2, R3.** Given the scan frames show a chair on the left and a cat on the right, when Claude answers, then he turns right toward the cat and speaks a line about the cat, even if the detector did not recognize the cat.
- AE2. **Covers R9, R11.** Given a person he has never seen, when he reacts, then he asks their name, listens after he finishes speaking, stores "Sarah" with her face, and says a Claude-written line using "Sarah" that tells her he'll remember her.
- AE3. **Covers R10.** Given Sarah was stored yesterday, when he sees her today, then he greets her by name.
- AE4. **Covers R7, R8.** Given the Claude endpoint is unreachable, when a curiosity stop happens, then he shows his thinking eyes, tries twice, and then reacts with the detector's pick and its name clip, all within about 20 seconds.
- AE5. **Covers R12.** Given a new person who doesn't answer, when he listens, then he says something friendly and stores nothing; given a person who answers with something that isn't a clear name, he stores them as unnamed for the owner to name later.
- AE6. **Covers R16.** Given Sarah is stored, when the owner presses Forget, then her face and name are deleted, and next time he treats her as someone new.

### Scope Boundaries

- Voice mode on Claude, and any conversation beyond asking a name.
- The robot's trained voice (separate plan); this plan uses whatever voice is loaded.
- Recognizing people without a network connection. Face matching needs Claude.
- Automatic expiry of remembered people.

<!-- ce-section: work-relationships -->
### How This Work Fits Together

This plan covers Explore's curiosity stops and people memory. The breakdown below is the current understanding, not a committed roadmap.

- Robot voice (`docs/plans/2026-09-24-1406-feat-robot-voice-on-device-tts-plan.md`). This plan depends on its speech service and needs no change when the trained voice lands.
- Claude settings (`docs/plans/2026-09-24-1019-feat-robot-settings-claude-api-plan.md`). This plan reads the endpoint, key, and model through it.
- Voice mode on Claude. Could reuse this plan's speech recognition and people memory. Still to decide.

### Dependencies / Assumptions

- Claude reads several images in one request and can report roughly where something is in a frame.
- The robot's microphone works in Explore's process; voice mode verified the audio path. The microphone is ducked while the robot speaks, so he listens only after he finishes speaking.
- sherpa-onnx, already in the launcher, also runs small English speech-recognition models. Unusual names may be misheard; Rename (R16) is the correction path.
- Consent basis (owner's statement): people who talk to the robot have consented to having their face remembered. Faces are stored only after a reply (R12).
- Owner-accepted: the Settings page is reachable by anyone on the Wi-Fi without a login, so the People section is too.
- A curiosity stop happens every 20–40 seconds, so the Claude cost is one or two image requests per stop.

### Sources / Research

- Grounding for curiosity stops, timing, frames, and speech: `mode-explore/src/com/miko3/mode/explore/` (ExploreBrain, ExploreCamera, ExploreTuning, the detector) and `docs/plans/2026-09-23-1037-feat-explore-camera-curiosity-plan.md`.
- `shared/src/com/miko3/shared/ClaudeApi.java`: listModels and testConnection only; no image Messages call yet.
- `shared/src/com/miko3/shared/RobotSpeechClient.java` and the robot voice plan KTD8 (speak after heavy work pauses).
- `docs/hardware/voice-mic.md`: microphone path and ducking.
