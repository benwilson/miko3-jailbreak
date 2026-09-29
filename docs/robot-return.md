# Robot return checklist

What to do when the robot is back, in the order to do it. The robot was last reachable on the evening of Friday 2026-09-25. Everything since then is merged on `main` but has never run on the robot:

| PR | What it changed | What it still needs on the robot |
|---|---|---|
| #17 camera navigation | Steering to open space, doorways, new ground; escapes from wedges | Verified live on Friday, except the owner's nav QA run |
| #18 meeting and small talk | Notices being spoken to, turns to the voice, open-ended conversation, notes on named people | The measurement session (plan U2) and the walkthrough (plan U9) |
| #19 ears reopen | A lost ears session comes back on the drive-lease backoff | A launcher restart mid-outing |
| #20 nobody stored without a name | A nameless reply gets a hello and nothing is stored | One nameless reply |
| #21 cue kind over the wire | "Excuse me" or "my bad" after a bump is a strong cue | One bump plus apology |
| #23 on-device face recognition | Matching on the robot, "Is that you?", face checks on the Settings page | The thresholds bench (plan U9) and the QA with before/after latency (plan U10) |
| Hey Miko always answers | "Hey Miko" is a call every state answers: an answer within a second, a turn to the caller, an approach if far, "Where'd you go?"; the NC direction chip on a confirmed port | Chip identification and calibration, and the call walkthrough (step 9) |

Tick items off here as they pass, and move anything that fails into `docs/TODO.md` with what you saw.

---

## Before you start

- [ ] **People.** Three in total:
  - you;
  - a helper he will store during step 5 (`notme`, `midname`);
  - a coworker he has never met, who answers his name question with *your* first name (`samename`, then `neartie`).
- [ ] **Robot.** Charged, on open floor away from desk edges (the desk-edge fix is still open).
- [ ] **Mac.** On this repo's `main`, pulled. `tools/face-bench/venv` exists; `scripts/face-bench.py` recreates it if not.

## 1. Preflight

- [ ] Connect: `adb connect 192.168.19.74:5555`, then `adb devices`.
- [ ] Camera: a cold power-up can wedge the camera HAL. The check reads the remote-control mode's video stream, so it reports `BROKEN` whenever that mode isn't running. Start it first, check, then leave it with Back:
  - `adb shell am start -n com.miko3.mode.remotecontrol/.MainActivity`, then `python3 scripts/recover-camera.py --check-only`.
  - `OK` means healthy. Press Back (`adb shell input keyevent KEYCODE_BACK`), and never force-stop the mode while its camera is open, which can itself wedge the HAL. `adb shell dumpsys media.camera` should then show `Device 0 is closed`.
  - Still `BROKEN` with the mode running: restart the camera services with `adb shell setprop ctl.restart cameraserver` and `adb shell setprop ctl.restart camerahalserver`, then check again. If that fails, reboot the robot.
  - Never run `recover-camera.py` without `--check-only`. Its recovery step restarts the camera services with `kill -9`, which can stop them restarting for good (`docs/solutions/runtime-errors/kill-9-on-watched-service-permanently-disables-restart.md`).
- [ ] Clear leftover debug switches: `adb shell getprop | grep log.tag.MikoExplore`. Anything left on from an interrupted script changes Explore's behaviour. Set each to `INFO`.
- [ ] Motors: if turns or reverse buzz without moving, the motor board has latched after stalled pushing. Only a full power cycle clears it.

## 2. Latency baseline on the last build before face recognition

This has to happen before the new build goes on, because the face PR's latency success criterion compares the Claude matcher against the on-device one. The robot currently runs Friday's build, which predates the stage stamps the measurement reads. So install the last pre-face `main` (commit `3e4151c`) first.

- [ ] Make a worktree at that commit and link the gitignored vendor trees into it, as `docs/solutions/tooling-decisions/agent-worktrees-branch-from-main-pin-baseline-and-link-vendor-trees.md` describes:
  `git worktree add --detach ../miko3-baseline 3e4151c`, then symlink `tools/third_party` and `tools/serviceexam_jadx` into it, and build both APKs there with `python3 scripts/build-custom-launcher.py` and `python3 scripts/build-mode-explore.py`.
- [ ] From the worktree, install both with that commit's own install routine. That is `install_both` in its `scripts/qa-conversation.py`: `install -r`, grant, start, then compare build ids. Running any `--only` check would install too, but then waits on an interactive prompt. Call the routine directly:
  `python3 -c 'import importlib.util,sys; s=importlib.util.spec_from_file_location("qa","scripts/qa-conversation.py"); qa=importlib.util.module_from_spec(s); sys.modules["qa"]=qa; s.loader.exec_module(qa); print(qa.install_both(qa.Robot("192.168.19.74:5555")))'`
  It prints the one build id both APKs report.
- [ ] Back in this checkout: `python3 scripts/qa-conversation.py --latency before`. That is five meetings, each started with "Hey Miko". It writes `tools/face-bench/latency-before.json` and installs nothing.
- [ ] Remove the worktree: delete its two symlinks first (`rm tools/third_party tools/serviceexam_jadx` inside it, which removes only the links), then `git worktree remove ../miko3-baseline`. Built APKs inside it are gitignored and go with it.

## 3. Install current `main` and measure the after latency

- [ ] `python3 scripts/qa-conversation.py --latency after`. This installs both APKs from `main` and runs five meetings. It prints the median and worst stop-to-first-sound, before against after.
- [ ] Include the first meeting after the mode starts. SFace loads cold then, which is a known review finding.
- [ ] Settings page, People section: the person stored on Friday should show one photo. It should either have an embedding or carry the "unusable" mark. If it is unusable, forget them and meet them again.

## 4. Measurement session for meeting and small talk (meeting plan U2)

Do this early. Three of its results can overturn design decisions, which changes what is worth testing after it.

- [ ] `python3 scripts/qa-ears-probe.py --session` on the robot, and `python3 scripts/claude-chat-bench.py` on the Mac.
- [ ] Check the three stop conditions:
  - [ ] Decode at or below 80 ms per 80 ms chunk at p95 with the detector roaming. Above that, the keyword-spotter fallback has to be built (KTD2).
  - [ ] A usable direction angle from our own process. Without one, the turn to the voice is dropped (KTD4).
  - [ ] A detector look at or below 1.0 s at p95 with the ears open, and first sound at or below 2.0 s in the conversation configuration. Beyond either, stop and report: there is no fallback (KTD7).

## 5. Face thresholds bench (face plan U9)

- [ ] Turn on frame capture: `adb shell setprop log.tag.MikoExploreFaceDebug DEBUG`.
- [ ] Meet you, the helper and anyone else willing, several times each, in normal office light, front-on at meeting distance. The helper gives their full name the first time, so the People page lists them. Keep the never-met coworker away from him until step 6.
- [ ] `python3 scripts/face-bench.py pull`. Sort `tools/face-bench/captures/_unsorted/` into one folder per person, named by initials.
- [ ] `python3 scripts/face-bench.py run`. If it prints **STOP CONDITION 2**, the bands do not ship as they are: record the numbers and stop the face work there.
- [ ] Otherwise, run the `python3 scripts/robot-faces.py thresholds ...` command it prints, then `python3 scripts/robot-faces.py thresholds` to confirm.
- [ ] Turn frame capture off and delete the captures: `python3 scripts/face-bench.py clean --robot`.

## 6. The walkthrough: meeting AEs and face AEs (meeting plan U9, face plan U10)

- [ ] `python3 scripts/qa-conversation.py --owner "<your name as stored>" --helper "<helper's name as stored>"`. It runs every check in order: face checks first, then the hallway test and the meeting checks.
- [ ] Face results to record:
  - [ ] `greet` passes at 4 of 5 meetings greeted by name.
  - [ ] Nobody is greeted by another person's name, ever. Any wrong name is a stop.
  - [ ] `close`, `notme`, `samename`, `neartie`, `dark`, `silent`, `midname` and `forget`: record pass or fail. For each miss, record the reason the face-check row gives: a rejected crop, a weak score, or a close question.
- [ ] Meeting results to record: the hallway stage stamps, the lean-in, cue and repeat counters, and the pass list for the remaining checks (`leanin`, `behind`, `wedge`, `stranger`, `goodbye`, `walkoff`, `newcomer`, `bait`, `switch`, `charger`, `persona`).
- [ ] If any check leaves you unsure, `python3 scripts/robot-faces.py checks` shows the last 10 face checks with their decisions and outcomes.

## 7. Navigation QA (#17)

- [ ] `python3 scripts/qa-explore-mode.py --only nav`. This covers the five-try wedge protocol, the doorway, a person, and the ten-minute leave-alone, with CPU temperature and battery.

## 8. Quick checks

- [ ] **#19 ears reopen.** With Explore running, restart the launcher: `adb shell am force-stop` it, then `am start` it again. He should hear you again within about 30 s, without restarting Explore.
- [ ] **#20 nameless reply.** Answer his name question with something that isn't a name. He says hello, and the People page shows no new person.
- [ ] **#21 apology cue.** Let him bump something, then say "excuse me" within 2 s. He treats it as being spoken to.
- [ ] **Camera navigation leftovers** (camera curiosity plan U8): check he turns toward an off-centre target (is the frame mirrored?), and watch for small or far things missed at the 320x416 detector size. Record any escape sweep that overshoots or stops short of a full turn.
- [ ] **Brightness.** After a face stop, open a face-check crop on the Settings page and compare it with the dark crop from 2026-09-25.
- [ ] **Remote-control mode** still drives forward after the shared motor-driver change. Someone needs to watch the robot.

## 9. Hey Miko always answers

The plan is `docs/plans/2026-09-28-1427-feat-explore-hey-miko-always-answers-plan.md` (PR #29, unmerged). On 2026-09-29 the chip on `/dev/ttyS1` was confirmed and put in side mode (`persist.miko3.voice_dir.port=/dev/ttyS1`, `.left=100`, `.right=60`). It tells left from right but not front from back, and it streams frames by itself; the launcher only listens.

- [ ] **Install the branch build.** From the branch: `python3 scripts/build-custom-launcher.py` and `python3 scripts/build-mode-explore.py`, then `adb install -r` both APKs and `adb shell am start -n com.miko3.launcher/.MainActivity` (the HOME key won't restart the launcher). Both `versionName`s must match the branch head.
- [ ] **The ears probe no longer crashes the launcher.** `python3 scripts/qa-ears-probe.py --seconds 3` should print rows, and `adb logcat -d | grep -E 'has died|SIG_DFL'` should show nothing new. It used to segfault every time, on `main` too (a use-after-free, fixed on this branch).
  - If it still crashes, bisect with the diagnostic build kept in `.claude/worktrees/agent-a4ced16e8c78cdffb/` (`diag-launcher.apk` and `PROBE-BISECTION.md`; never merge that branch), then reinstall the branch launcher.
- [ ] **The chip still streams after a cold boot.** Say something to him, then `adb logcat -d | grep 'voice direction'` should show `backend NC (nc on /dev/ttyS1, side)` and `nc raw <v> (side)` lines. `NONE ... no frames` means the chip needs something after a boot that we haven't seen yet: record it in `docs/TODO.md`.
- [ ] **Re-check the side thresholds (optional).** `python3 scripts/qa-direction-chip.py --calibrate`: stand front, left, right and behind, press Enter and count aloud. It suggests left/right thresholds; add `--apply` to set them. Yesterday's numbers suggest left 95 and right 60.
- [ ] **Side test on the stream.** On the charger, `python3 scripts/qa-direction-chip.py --watch --seconds 15` while you talk from his left, then his right: the frames should read `left`, then `right`.
- [ ] **Side test in Explore.** Off the charger, on open floor: call "Hey Miko" from his left side. He should answer, then look left first. Repeat from his right. From ahead or behind he has no side and uses the full circle.
- [ ] **The call walkthrough.** `python3 scripts/qa-conversation.py --only callmet,callbackoff,callchat,callbehind,callwhere,calldock,callfar,callten`. Leave out `--chip`: that flag is for a chip that gives an angle, and side mode keeps the 12 s facing budget. Each check prints the answer, facing and arrival times.
  - `callchat` now also checks the fix for a second caller during the first caller's meeting: the second call must be answered when the first conversation ends.
  - Record the times in the plan. The look budget per stop (`callLookMs`, 700 ms) is a placeholder: the chip showed frames once or twice a second and the camera is similar, so if he circles past people, raise it and note the new time for someone behind.
  - Note any false wake word on the dock: the ears stay open there.
- [ ] **Floor sensor fault.** On 2026-09-29 Explore dropped to eyes-only with `tof at fault value 16383`. If it recurs, note when (cold start, after docking) in `docs/TODO.md`.

---

## After the session: record results and finish each plan

| Plan | Finished when | Record results in |
|---|---|---|
| Camera navigation (`docs/plans/2026-09-25-1030-feat-explore-camera-navigation-plan.md`) | Step 7 passes | The plan, and `docs/TODO.md` for anything that failed |
| Meeting and small talk (`docs/plans/2026-09-25-1611-feat-explore-meeting-small-talk-plan.md`) | Step 4 numbers are recorded and no stop condition fired; step 6 meeting checks pass | The plan (its KTD13 asks for the U2 numbers), and `docs/TODO.md` |
| On-device face recognition (`docs/plans/2026-09-26-2239-feat-explore-on-device-face-recognition-plan.md`) | Steps 2, 3, 5 and 6 face checks pass with no wrong names | PR #23 (merged; add a comment), `docs/hardware/camera-vision.md` section 12, and `docs/TODO.md` |
| Hey Miko always answers (`docs/plans/2026-09-28-1427-feat-explore-hey-miko-always-answers-plan.md`) | Step 9's call checks pass; the chip is confirmed and calibrated, or the owner has decided it stays off | The plan, and `docs/TODO.md` for any placeholder the times contradict |
| Explore on Claude (`docs/plans/2026-09-24-1545-feat-explore-on-claude-plan.md`) | The greet-by-name item in `docs/TODO.md` ticks off when `greet` passes | `docs/TODO.md` |

Decisions the results may force:

- **A meeting stop condition fires** (step 4): build the keyword-spotter fallback, or drop the turn to the voice, or stop and rethink the speech promise. Each is named in the meeting plan's KTD13.
- **Face stop condition 2, or any wrong name** (steps 5 and 6): the bands don't ship as they are. Decide between retuning, more photos per person, or a different model.
- **Open face-review findings to weigh with the results**, from PR #23:
  - Face models can start while a detector frame is still in flight.
  - The name-resolution flow is duplicated in two places.
  - SFace loads cold on the first meeting after each mode start.
  - None was seen live yet. If step 6 shows odd timing, suspect the first one. The P1 late-match finding (a match overwriting the next meeting) is fixed in PR #26.
- **After this session**, the recorded next step for faces is roaming recognition on the on-device matcher (`docs/TODO.md`).
