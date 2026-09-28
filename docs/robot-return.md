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

---

## After the session: record results and finish each plan

| Plan | Finished when | Record results in |
|---|---|---|
| Camera navigation (`docs/plans/2026-09-25-1030-feat-explore-camera-navigation-plan.md`) | Step 7 passes | The plan, and `docs/TODO.md` for anything that failed |
| Meeting and small talk (`docs/plans/2026-09-25-1611-feat-explore-meeting-small-talk-plan.md`) | Step 4 numbers are recorded and no stop condition fired; step 6 meeting checks pass | The plan (its KTD13 asks for the U2 numbers), and `docs/TODO.md` |
| On-device face recognition (`docs/plans/2026-09-26-2239-feat-explore-on-device-face-recognition-plan.md`) | Steps 2, 3, 5 and 6 face checks pass with no wrong names | PR #23 (merged; add a comment), `docs/hardware/camera-vision.md` section 12, and `docs/TODO.md` |
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
