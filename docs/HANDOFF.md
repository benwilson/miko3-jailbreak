# Handoff: Explore, 2026-10-02 to 10-03

This document is where to pick up. Branch `feat/explore-hey-miko-always-answers` (PR #29) is 110 commits ahead of `main`, and HEAD is `de50b62`. Everything is committed and pushed. The repo has no CI, so a change counts as ready when the local tests pass, the APK builds, and it works on the robot.

## The robot right now

- **Off.** The last install was `1164320` (launcher and Explore). Since then **`de50b62`** (safer voice ID and the multi-model voice eval) has been committed but **not installed**.
- **Address:** home is **192.168.1.115** (DHCP: it was .113 the night before). The office is 192.168.19.74. The scripts default to the office address; pass the serial or IP explicitly.
- **Properties set on the robot.** `persist.*` survives a reboot:

  | Property | Value | Why |
  |---|---|---|
  | `persist.miko3.explore.dark_floor` | `1` | black floor at home (see below); set it to 0 for the office/desk |
  | `persist.miko3.ears.source` | `recognition` | VOICE_RECOGNITION mic source (the vendor's choice) |
  | `persist.miko3.ears.gain_db` | `12` | software mic gain, raised from 6 |
  | `persist.miko3.ears.nc_apply` / `nc_dgain` | `1` / `0` | the NC mic chip had digital gain 50; the vendor value is 0 |
  | `persist.miko3.voice.strong` / `.weak` | `0.95` / `0.90` | **stop-gap, clear it**: `setprop … '""'`. de50b62 renamed the props to `persist.miko3.voiceid.*` and ignores these. |

- `log.tag.MikoExploreSayDebug` (the debug log of what he heard and said) is not persistent and is cleared by a reboot.
- **Volume:** 10 of 15, unmuted.

## Install recipe (this one works; Wi-Fi drops during long copies)

1. **Build from a clean worktree of HEAD,** so other agents' uncommitted edits don't get in. `/tmp/miko-build` may be gone; recreate it:
   - `git worktree add /tmp/miko-build HEAD`
   - copy `tools/serviceexam_jadx/resources/lib/arm64-v8a/` into the worktree, plus `tools/third_party/*`, so the models aren't downloaded again;
   - then run `scripts/build-custom-launcher.py` and `scripts/build-mode-explore.py`.
2. **Push while he runs:** `adb push … /data/local/tmp/{l,e}.apk`, retrying, since the 191 MB launcher copy often drops. Then:
   - `am force-stop com.miko3.mode.explore`;
   - `pm install -r -t` both APKs;
   - `kill -9 cameraserver camerahalserver`;
   - `am start` the launcher, wait 25 s, then `am start` Explore.
   
   `/tmp/miko-install2.sh` was this exact script.
3. **After a cold boot:** the camera HAL crash-loops (AEE floods the 256 KB log). Kill the camera servers before starting Explore.
4. **Never gate on load average.** This robot sits at 18–25 all the time.

## Next steps, in order

1. **Install `de50b62`** and clear the stop-gap properties above.
2. **Voice eval.** Steps are in `docs/TODO.md` ("voice"):
   1. `python3 scripts/push-voice-eval-models.py --restart`;
   2. capture the `voice eval` lines to `out/voice-eval.log`;
   3. Ben and his wife each have 3–4 conversations, giving their names;
   4. `python3 scripts/voice-eval-report.py out/voice-eval.log` picks the voice model.
   
   CAM++ matched the wife to Ben at 0.67–0.82 (memory: miko3-voice-id-false-match). She should give her name early, since she can still pass the solo bar until she has her own prints.
3. **Speech recognition decision (open; ask the owner).**
   - The mic captures fine: the same answer gave a strong voice match and "no words".
   - The recogniser (zipformer-en-20M, trained on LibriSpeech) is the weak link. A TV was also feeding it garbage.
   - Options: cloud AssemblyAI Universal-Streaming (best far-field; needs a key; audio leaves the robot during conversations), or on-device Kroko-EN (about 2–3× the CPU). The research summary is in this session's notes; the agent reports aren't saved anywhere.
4. **Robot checks in `docs/TODO.md`:**
   - RECOVER probe A/B schedule A/B (needs a roaming day): the learning log and `scripts/daily-diff.py`;
   - run_task errands (never run on the robot);
   - the mute button press (the watcher autostarts; a real press is untested);
   - the TIP-docked harness scenario;
   - the call-with-words opener.

## What landed and was verified on the robot

- **Conversation**
  - respond-tool replies with `tool_choice: any`: no reasoning spoken, and lines ready in about 0.3–1.5 s;
  - chattier faceless meetings;
  - no crouch requests; asks names; memory by name;
  - "come find me" maps to come_here;
  - replies capped at 700 tokens; the 400 cap cut chatty replies off.
- **Action tools:** move spin and turn around, go_away, be_quiet, and refusals on the dock work. run_task has only run on the bench.
- **Voice ID:** recognised Ben by voice and greeted him by name. It also false-matched his wife, so the margin rule is in de50b62.
- **Dark floor:** TOFDS turns off the MCU's cliff refusal, found by disassembling the firmware. Driving on the black floor works, and the tip guard has saved him.
- **Docking:** POWER dock detection; docking from the TIP state; the camera closes between docked looks (HAL 68% → 0.2% of a core); idle eyes are static; `/state` is polled every 400 ms.
- **Bathroom privacy:** stricter rules. A living-room mirror plus a floor strip had triggered it, and privacy now always clears after 30 s with no bathroom label.
- **The NC mic chip:** read and set over ttyS1, using the vendor protocol.
- **Testing tools:**
  - `scripts/robot-say.py` injects heard text, debug-gated;
  - the learning log via `scripts/pull-learn-log.py`;
  - `scripts/chat-report.py`, `scripts/daily-diff.py`, `scripts/eval-explore-triggers.py`.

## Known issues (not fixed)

- `shoved: ~2150 counts while stopped` every 0.5 s while the wheels read still. It's a counter glitch that also creates fake voice cues; the cause is unknown.
- A possible double opener on a call: two "Hey there…" lines 2.5 s apart (unconfirmed).
- The "heard:" debug log shows the tail of the message, so long instructions hide the words. Log the head too.
- Wi-Fi drops on large pushes or at the edge of range.
- The voice embedding takes 1.8–8 s; it was cut to 3 s clips at normal priority in 5b743f7.

## Where things are

- **Docs:** `docs/TODO.md` (the to-do list and robot checks), `CONCEPTS.md` (the vocabulary), `docs/solutions/` (verified learnings; read them before touching hardware code).
- **Memory index:** `~/.claude/projects/-Users-bwilson-Documents-miko3-jailbreak/memory/MEMORY.md`. The relevant entries:
  - miko3-session-2026-10-02-state
  - miko3-voice-id-false-match
  - owner-away-work-autonomously
- **Logs:** robot logs from these days are under `/tmp/compound-engineering-501/lfg/` (day3.log, home1.log, home2.log). They're temporary and may be gone.
- **Owner preferences:**
  - works autonomously when he says he's away;
  - person-likeness over caution;
  - Python scripts, not shell;
  - no attribution lines in commits or PRs from now on.
