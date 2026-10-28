# Concepts

> Shared domain vocabulary for this project — entities, named processes, and status concepts with project-specific meaning. Seeded with core domain vocabulary, then accretes as ce-compound and ce-compound-refresh process learnings; direct edits are fine. Glossary only, not a spec or catch-all.

## Boot ladder

The sequence of USB enumeration states a Miko 3 passes through after a power cycle: BROM, the MediaTek preloader stage, the fastboot gadget once a mode name has been written, the normal Android gadget, and the accessory state reached from an AOA handshake. It is the schedule every host-side route is timed against: each stage has its own window, its own interface descriptor, and its own usable or unusable character, and the ladder recurs within one power cycle rather than being a one-shot event.

## Preloader window

The short, repeating interval during a boot in which the MediaTek preloader stage holds the USB connection and answers on its own serial node. It is the only stage that gives a host a bidirectional console without the kiosk app's cooperation, and it is short enough that the order of host-side attempts matters more than their content. A missed occurrence is retried within the same power cycle, since the stage comes back several times before the unit settles.

## META mode

The MediaTek text handshake the preloader window speaks: the host sends the download-mode sync, the device answers that it is ready, and the host then names the stage to switch to. The mode name is what makes the handshake useful — the device moves to the named stage rather than merely acknowledging. Named stages are the ones the firmware recognises, not arbitrary labels.

## Accessory mode

The USB state a unit enters once an Android Open Accessories handshake succeeds, in which the host's descriptor and HID registrations drive input to the device. It is a distinct enumeration state from the normal Android gadget state, so its appearance confirms the handshake and says nothing about whether adb is exposed.

## Interface triplet

The class, subclass, and protocol values read from a device's USB interface descriptor, used here as the machine-readable check of what a given boot stage actually offers. It is preferred over parsing adb's own device listing because it needs no RSA authorization and answers even when that listing is empty. Three states carry meaning: a vendor-only triplet means no adb interface is present, a triplet with the adb subclass and protocol means adb is reachable, and the fastboot gadget's triplet means fastboot answers while adb still does not.

## Kiosk

The unit's single-purpose home screen, reached only through a normal boot. It is the state the unit is meant to settle into, and the state every recovery effort is trying to restore; a host-side console is a detour around it, not a substitute for it. The kiosk is composed of paired system apps, so "the kiosk boots" means both halves reached their expected versions, not merely that a home activity launched.

## Paired system apps

Two preloaded apps that share a binder contract and therefore must be restored together at matching versions: one exposes a bound service, the other binds to it and drives the visible screen. An OTA ships them as a set, and applying only one leaves the contract skewed. The pairing is not expressed anywhere in the code — it is discovered from the failure it causes when broken.

## Service init handshake

The binder exchange a kiosk performs at startup before it will show anything, in which the screen-driving app binds to the service-exposing app and calls an initialization method across that binder. It is a gate, not a notification: until it completes, the kiosk stays on its startup screen, and a version skew between the pair makes it fail silently rather than report an error.

## Bot restart screen

The looping startup animation a kiosk shows while it waits for the service init handshake to complete. It repeats deliberately and indefinitely, so it is indistinguishable from a hang to a viewer; it is dismissed only by the handshake succeeding, never by a timeout. It is the visible symptom of any failure in that handshake.

## Watchdog

The kiosk-side watchdog that unconditionally disables adb access on every normal boot, and separately, on an ongoing timer, reboots the unit if it observes the adb daemon running anyway — two independent hostile actions, not one, so defeating the reboot alone does not keep adb reachable.

The adb-disable runs once, early, before the reboot check starts polling, which is why a countermeasure that only defeats the reboot arrives too late to matter unless it also blocks the disable itself.

## Boot agent

The app that runs the root payload on every normal boot: a manifest `BOOT_COMPLETED` receiver that execs the setuid `su` and then brings adb up. It is the only way to run a root command at boot on this unit, because verity keeps the system and vendor init read-only and no init trigger consumes an adb property. Being installed means the receiver is registered and past the stopped state, not merely that the APK is present.

## Neuterd

The small freestanding arm64 daemon, reused from the openmiko research, that keeps the watchdog's hostile actions neutered by shadowing the system commands it uses to perform them. It enters init's global mount namespace once, then continuously re-applies each shadow whenever the command it targets reverts to its real, unshadowed form, so the neuter survives whatever wipes it. It exists because the mount has to be made in the namespace the watchdog actually sees, not an app's private one.

A shadow is not always a blanket no-op: it can instead filter, letting a targeted command through unchanged except for the one specific call it exists to block, which matters when the same command is also needed for legitimate purposes.

## Global mount namespace

The namespace init owns, as opposed to the private one an app process gets. A bind mount made from an app lands in the app's namespace and is invisible to the watchdog, a zygote child in the global one, so a neuter is only real when it is made after entering init's namespace. It is why the watchdog fix needs a native daemon rather than an app-side mount.

## Stopped state

Android's per-package flag, recorded in the per-user package-restrictions file, that keeps a freshly installed app from receiving broadcasts until it has been launched once. It is what makes a boot receiver silent, and the reason installation cannot stop at placing the APK: this unit has no activity manager in factory mode, so the flag is cleared directly rather than by launching the app.

## Mode

A purpose-built robot capability — remote control, telepresence, autonomous operation, and others planned — that ships as its own installed app rather than a feature bolted onto the custom launcher. The launcher starts a mode and regains the home screen when the operator exits it; only one mode's control logic drives the robot at a time. A shared module gives every mode the same robot-control and UI plumbing instead of each one reimplementing it.

## Startle

An autonomous mode's full reaction to an edge, obstacle, or other hazard that appears while the robot is moving. It stops at once, plays a short startled sound, flinches its eyes, backs up briefly for a fixed short time, then looks toward a new heading and turns away. It differs from the quiet turn-away used when a hazard is already in view before a move starts: that turn has no sound and no back-off. The back-off is blind, because nothing senses behind the robot, so it is kept deliberately short. Too many hazard reactions (startles or quiet turn-aways) within a short window make the robot rest instead of backing off and turning.

## Curiosity stop

An autonomous mode's periodic pause to look around with the camera. It scans with a few short turns and picks the most prominent thing it recognizes. Something new this session, or any pet, it turns to face, rolls up to, and reacts to aloud, saying the thing's name; a person is met if the face check gets a usable face, or, facelessly, if the detector was fairly sure of them or a voice came from their side just before; something already inspected gets a disappointed look from where it stands, and something it cannot make out a puzzled one. Arriving next to the thing is not a hazard, but an edge met on the way is still a startle. If the camera gives nothing, the stop tries once more shortly after; only a second miss switches curiosity stops off for a while, and the robot carries on wandering. A voice that is not a call does not cancel a stop that already has its remark: the remark is said first.

## Forward refusal

The motor controller's own refusal to drive the robot forward when its front sensor reading falls outside the band it considers safe: too close, meaning something is in front, or too far, meaning no surface, as past an edge. It happens in the controller's firmware, independent of any mode, and acknowledges the command as refused instead of moving, so it acts as a backstop beneath a mode's own hazard checks and is never overridden. It covers forward motion only; reversing and turning are not protected by it. Because the sensor looks down at the surface ahead, the robot rocking on its wheels can briefly push a reading out of the band and trigger a refusal on open ground. The controller has its own command to switch the check off, and only dark-floor mode uses it.

## Dark-floor mode

An opt-in way for Explore to roam a floor the front sensor cannot see, such as a black floor that returns no reading at all. The owner switches it on for a floor with no drops anywhere. The robot then tells the motor controller to stop making its forward refusal on that sensor, and treats a missing floor reading as clear floor, not as a dead sensor or an edge. It relies on the camera, its wheels stalling and its own tilt to find trouble. A nose dip or a climb counts as a hazard ahead, and being lifted stops it until it is flat again. Every other move, such as a back-up, a turn or a probe after a stall, stops at the first sign of a tip and goes a little the opposite way. A stall probe never turns, and tipping every way out counts as a jam. It keeps its legs short, and the controller's check comes back on whenever the mode is switched off or Explore stops. It must never be used where there is a drop, because nothing then stops the robot driving off it.

## Conversation

The window in which the robot is talking with one person. In Voice mode it opens when the on-device wake-word spotter hears "Hey Miko" and closes when the relay matches "Goodbye Miko" in the transcript or the silence timeout passes, and only inside it does microphone audio leave the robot. In Explore mode it opens on the wake word or on an address decision made on the robot, runs turn by turn for as long as the person keeps answering, and closes on a goodbye, the person walking off, or unanswered listens (he waits about 7 seconds for an answer to start; the second unanswered listen in a row gets one gentle follow-up question instead of silence, and the next one ends it; one a call opened never closes because he cannot see them, only on a goodbye or three unanswered listens in a row); an answer that ended without words gets up to two "Sorry, I didn't catch that. Could you come a bit closer or speak up?" in a row that do not count; a turn whose message the model judges was not said to him, such as people talking to each other nearby, speaks nothing and counts as an unanswered listen, and when he has heard nothing said to him for about a minute he says "I'll leave you to it" and the conversation ends; a turn on which he is given an instruction he can follow ends it after the line that says he will (see Intent); a listen whose answer has started waits for the person to finish rather than ending on its usual short timer; only inside it does a transcript leave the robot, and audio never does. Each reply may use one of his tools first: a fresh look through the camera (never in bathroom privacy or do-not-disturb, when he says he can't look), what he remembers of the person he is talking to (about anyone else, only whether he knows the name), his own status, or the places he looked at lately; he says a few words such as "Let me look." while it runs. When the person explicitly asks him to do something he can do himself, the reply may instead use one of his action tools (see Intent and Task), one per reply, and is told honestly whether it started or why he can't. When the owner has written a note about the person by name (see Owner's note), it shapes how he approaches them. Reaching the charger during a conversation does not end it; once it is over he stays docked and quiet. Between conversations a mode listens locally and streams nothing.

## Relay

The owner's own service, on the local network, that sits between a mode app and the hosted speech model. It holds the model session, applies the persona, matches the sleep word, runs the silence timeout, and is where tools will live, so that behavior changes are server-side edits rather than an APK reinstall on the robot.

## Command lane

The relay-to-robot direction of a mode's link to the relay, reserved for physical actions the model may later request. It sits beside the control lane, the small set of messages the link needs in every version (conversation open and close, playback state, flush, keepalive). In the talk-only version nothing travels on the command lane and the robot answers anything it does not recognize with an "unsupported" reply; it exists so adding actions is a server change, not a protocol redesign.

## Settings page

The launcher-served page that is the robot's single home for owner settings, organized in sections, opened either from a LAN browser or from the robot's own screen. Settings a mode needs live here rather than on the mode's own page; a mode reads them from the launcher at the time it uses them. Its first section is Claude API access, whose key the page never displays back and never lets a base-URL change carry forward without being re-entered.

## Lean-in

An autonomous mode's cheap first response to a weak sign that someone may be speaking to it: a voice from one side, a half-heard word, or a shove with no words. The wheels stop, the eyes slide toward the sound, and the robot turns and looks for a person toward it. A person found there opens a conversation only if the face check then gets a usable face; anything less, including a person box with no face in it, ends in a quiet resume with nothing said, nothing remembered and nothing sent. A strong cue, meaning the wake word, the robot's name, or a clear greeting, skips the lean-in and goes straight to the greeting. It exists because two microphones only tell left from right, the robot cannot hear well over its own motors, and a missed or wrong first hearing should cost a glance, not a conversation.

## Workplace test

The single rule bounding what the robot says in its persona: it never says anything that would get a coworker fired if they said it. Slightly edgy office small talk is inside the line; the persona text carries the concrete list of what falls outside it. It is the owner's rule, applied by the model that writes the robot's lines, not a filter running on the robot.

## Person notes

The short record an autonomous mode keeps about a person who has given their name, beside the stored face: interests, open threads with roughly when they came up, topics covered, and questions already asked. Never a transcript. Written during and at the end of each conversation and read before the next, so the robot follows up on what the person said and never asks them the same question twice. One person is one record under one id: their name (with a last name when one was given, which tells apart people who share a first name), their notes, their face photos and their voice prints. Who someone is comes from their voice first, but a voice match only counts when it is clear: the best-matching person must lead the second-best by a margin (or, when only one person has voice prints, score well above the strong threshold) and have at least four prints, and the same person must win two clean answers in a row before he goes on as them. A face that turns up breaks the tie: one that agrees with the voice settles it at once, one that disagrees cancels the voice for the rest of the conversation, undoes any identity the voice gave, and he asks their name. A match that is not clear, a weak one, lets him ask their name. Once a conversation settles who someone is (a name given or confirmed, or voice and face agreeing), a few of their answers become voice prints, a new person's as soon as they give their name, so the people he knows build up prints and compete on the margin. A person the robot could not see well is remembered by the name alone, with no face yet: the next conversation where the same name is given loads their notes (a full name picks its person when several share a first name; a first name several people share loads nothing), and a face captured later in a conversation under that name is added to them. A person who never gave a name gets no notes, and anyone can have their face, name, notes, voice and feedback (below) wiped by asking to be forgotten or by Forget on the Settings page.

## Feedback log

The record of what people tell the robot about himself in a conversation, so the owner can act on it: a suggestion, a complaint, praise or a bug, about how he behaves, what he can or can't do, his voice, his driving, getting stuck or interrupting. Each entry is when it was said, what kind it is, the point in one neutral sentence, the person's key sentence word for word, who said it (a remembered person's first name, otherwise "someone"), and a few words on where, such as on the charger or in a conversation a call opened. That quoted sentence is the only verbatim text the robot ever keeps; the conversation itself is never kept, and small talk about anything else is never logged. He acknowledges feedback out loud as he hears it. The log keeps only the most recent entries, oldest dropped first, the owner reads and clears it from the Settings page, and asking to be forgotten deletes that person's entries.

## Cue

A sign, judged on the robot, that someone may be speaking to it. Cues come in two tiers. A strong cue is the wake word, the robot's name in any form, or a clear greeting aimed at it, and goes straight to a greeting. A weak cue is a voice from one side, a half-heard word, or a shove with no words, and earns only a lean-in; a shove followed within a couple of seconds by "sorry" or "oops" becomes strong. A cue carries the side the sound came from and the moment it landed, a newer or stronger cue replaces a pending one, and a held cue expires after a few seconds. The wake word and the robot's name are the exception: each is a call (below), which never expires. Nothing leaves the robot on a cue alone.

## Call

In Explore mode, the wake word or the robot's name, heard as a strong cue: someone asking for the robot's attention. A call is never dropped and never expires. It waits only for four moments to end: a back-off away from a hazard, a line he is already speaking, a conversation with someone else, and the charger, where he answers without moving. It overrides every leave-alone rule, so a person he just met can still call him. When it is taken he stops, answers out loud with a short on-robot clip ("Oh hi?", "What?", "Yes?") as soon as the caller's utterance ends, without waiting for Claude, and the conversation opens at once, before he has seen them: the words said with the wake word ("Hey Miko, how's it going?") are its first message, and a bare call gets a short greeting-question. He looks for the caller during the conversation, turning toward the voice's side in short turns between utterances, never while he speaks or while an answer is under way, and once he finds them he stops searching and faces them. A call, a person the detector was fairly sure of, or one a voice came from just before, can open a conversation without a usable face; he never asks anyone to show him their face: he asks their name, uses it for the rest of the conversation and remembers them by it (see Person notes), and checks for their face quietly about every 8 seconds the whole time, never while he speaks and never saying anything about it; a face that turns up settles who they are. The call's own voice is the conversation's voice reference, so words in another voice, such as a TV's, are not taken as said to him. With conversation first switched off, he finds the caller before the conversation opens, as he used to. Two calls before the answer are one call, and the newer direction wins. A hazard or a lost drive lease while he is finding the caller puts the call back to wait, already answered. The call is done when the conversation opens, or after the answer alone when no conversation can open.

## Intent

In Explore mode, an instruction someone gave him in a conversation that he is now carrying out as he roams: go away, go and find someone (by name, or anyone new), come over, be quiet for some minutes, turn or drive a short way (at most a full turn, 1.5 m forward or 0.5 m back), stop, stay or wait where he is, search for a thing his detector can name and go over to it (a short way, shorter the bigger it looks, done where something stops him), or go to a place he knows only by what he saw there (a kitchen: a refrigerator, a microwave), never toward a bathroom thing. The model asks for it by calling one of his action tools, only when the person explicitly asks; he checks it first and tells the model honestly whether it started or why he can't (on the charger, in bathroom privacy, without wheels, stuck, toward where something just stopped him, or a thing his detector doesn't know), and the line he says follows from that; anything else he can't do gets an honest no and no intent. Stop takes effect at once and the conversation goes on; stay and wait still answer a call where he stands. The conversation ends after that line without a sign-off. Going away turns him about and leaves that person alone and their way avoided for ten minutes (when he can't drive, he only leaves them alone and avoids their way, and never drives off later); going elsewhere starts a seek at once and avoids the spot for fifteen; finding someone seeks and lets him go over to people he sees for up to five minutes, counting only the named person's face when a name was given; coming over is a call's search and approach without the answer; being quiet is do-not-disturb for ten minutes. A call drops any intent at once, except being quiet, under which a call gets a glance; hazards, recovery waits, a jam, bathroom privacy and do-not-disturb always come first. The trace records the intent and whether it had a target, never the target itself; a move's kind and amount are recorded, and each intent's start and end are learning-log records.

## Task

In Explore mode, a short errand of several steps the model planned for him in one action: go somewhere, look, come back to where it started, say what he found, and the like, at most eight steps. He runs the steps in order himself; after a look, after a step marked as deciding the rest, and when a step fails, he asks the model for the rest of the plan, sending the goal, the steps so far with their outcomes, his status and what his detector names now, never a picture. A task gets at most ten such asks and five minutes, then ends with a short apology. His reflexes always come first: an edge, an obstacle or a stall fails the step, and a revised plan never drives while he waits for his motors or straight back into what just stopped him; bathroom privacy, the charger, losing his wheels, a call or being told to stop ends the task; a conversation that opens on its own pauses it. The learning log keeps each task's step tools, their outcomes, the asks, how it ended and how long it took, never the goal or anything said.

## Owner's note

A note the owner writes about a person by name on the Settings page, kept only on the robot and never logged. When the person he is talking to is known by that name, the note goes into that conversation's instructions to the model; known means a face-matched person's stored name, or the name the person said, equal to the note's full name (a first name alone never finds a note, since anyone can say one): he follows it for how to approach them, but never deceives them, never pressures them once they say no or ask him to stop or leave, never reveals or quotes the note, and if asked whether someone told him about them, says honestly that the owner mentioned them. It is separate from Person notes, which he keeps himself, and no tool ever returns it.

## Ears session

The one long-lived microphone capture the launcher holds on behalf of an autonomous mode while it runs, on the charger too, as distinct from the one-shot listen used for a single short reply. It feeds the wake-word engine and the recogniser from the same audio, reports each utterance with its side and tier, is kept alive by the mode's renewals and released when the mode goes away. A session the launcher drops while the mode still wants it is reopened on the same backoff as the drive lease, 2 s doubling to 30 s, so a launcher restart brings the ears back with the wheels. It also makes voice prints: a few seconds of a clean conversation answer, or of a call long enough to say something about the voice, become an embedding (never the audio) that is matched against the people it knows and can be enrolled to the person the conversation settles on. It exists because the device allows one capture at a time and a robot that is addressable while roaming cannot open and close the microphone per question.

## Direction chip

The robot's voice-processing chip that reports which way a voice came from (its direction of arrival), as distinct from the side a cue carries, which is all the robot knows without it. The chip is opened only after the owner has confirmed which port it is on, gives a direction only once the owner has calibrated which reading means straight ahead, and is only ever read in a way that cannot hang or crash the process that holds the ears. Until both steps are done the robot has no direction, and a call is found by turning and looking instead. Only the chip's reported angle, corrected for the robot's own turning since the voice was heard, lets a call turn straight to the caller.

## Deaf window

The stretch from the moment the robot starts a spoken line until playback goes idle plus a short tail, during which nothing said to it can be heard because its own voice ducks the microphone. Anything said inside the window is dropped by design, the recogniser is reset at its end so the ducked audio never becomes an utterance, and it is the reason the robot keeps its lines to two sentences and listens the instant a line ends.


## Face check

An autonomous mode's decision about one face it has just cropped at a meeting: whether the crop is usable at all, which stored person it most resembles, how strongly, and which of three answers follows. A confident match is greeted by name, a close one is asked "Is that you?", and a weak one is treated as someone new and asked their name. The person's answer teaches the robot another photo of them. A crop too dark, blurry or small is rejected rather than guessed at; a crop that passes is a usable face. Seen from the floor, most faces are too small or out of frame, so a meeting the robot starts on its own needs a usable face unless the detector was fairly sure of the person or a voice came from them, and a call may always proceed without one; a conversation without one keeps checking quietly and, when a usable face turns up, a confident match makes it that person's conversation and a new face is kept under the name they gave. A voice match comes first and the face verifies it; a confident face alone still identifies someone when no voice match is available. Two stored people scoring almost equally is treated as a close call and asked about rather than greeted, and while stored photos are still waiting to be re-processed for the current matcher, the robot still talks with the person but matches nobody and stores or learns nobody. Each check is kept briefly with its crop, score and outcome so the owner can see why the robot did or didn't recognise someone.

## Learning log

The app-private file (`learn.log`) in which Explore keeps its structured records while it roams, so a developer can improve the code between runs; the robot never reads it back or changes itself. It holds only records: one line per decision or episode (`leg:`, `trig:` for a decision a detection set off, `turn:` for a conversation turn, `act:` for an action tool's start and end, `task:` for a task's steps and end, escapes, seeks, modes, bathroom, power and stall lines, and in dark-floor mode a `floor:` line per leg) and a start line naming the build and the tuning. No words, names or pixels. Lines are written as logcat lines, so the same reports read a pulled learning log and a logcat capture.
