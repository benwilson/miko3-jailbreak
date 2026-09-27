# To-do

The running list of what is left to do on the robot's modes. Add to it when something comes up, and tick items off (or delete them) in the PR that finishes them.

## Explore mode

- [ ] **Measurement session gaps (meeting plan U2).** `scripts/qa-ears-probe.py --session` and `scripts/claude-chat-bench.py` cover the direction angle, decode time, phrase match, CPU and launcher RSS, the shove signature and the ten-turn conversation. What the scripts cannot yet measure, because no device route exposes it:
  - Face-box shape at frontal, 45 degrees and profile (KTD4's width-to-height and size thresholds): Explore's `/state` carries no face box (state, look and, since U7, the cue gauges). The face steps record the state timeline and the thresholds stay at the plan's defaults until the state page (or a probe route) carries the last face box.
  - Per-chunk decode p95: the ears probe reports each second's decode total and worst chunk, so the CSV's `decode_p95_ms` is the p95 of per-second means and `decode_max_p95_ms` the p95 of worst chunks; the true per-chunk p95 lies between them. Fine for KTD2's 80 ms decision unless the two straddle it.
  - Wake-word engine cost beside the recogniser, detector look time with the ears open, the capture level during a launcher-spoken line (the deaf-window tail, default 500 ms), and speech first sound at 2 and 4 threads in the CHAT_SPEAK configuration: these need the U3 session and U7/U8 states on the robot; measure them in U9's QA once those land.
- [ ] **Meeting and small talk: the pending owner runs and what they could overturn (meeting plan U2, U9).** Neither the measurement session (`python3 scripts/qa-ears-probe.py --session` with `scripts/claude-chat-bench.py`) nor the QA (`python3 scripts/qa-conversation.py`: installs both APKs from one build, then the hallway test and AE1 to AE13 with the stage stamps, the face-match rate and the lean-in, cue and repeat counters) has been run on the robot. Record their numbers in the plan and here. Until then the code runs on the plan's defaults, and the measurements can invalidate three of them:
  - KTD2's single recogniser. Decode above 80 ms per 80 ms chunk at p95 with the detector roaming selects the keyword-spotter fallback: the 3.3M-parameter sherpa KeywordSpotter takes the roaming cue words and the recogniser runs only during conversation listens. That path is not built; build it only if the measurement demands it.
  - KTD4's turn to the voice. No usable direction angle from our own process (stop condition 1) drops CUE_TURN; strong cues and the wake word still reach the conversation through the camera meeting look.
  - KTD7's speech promise. A detector look above 1.0 s at p95 with the ears open, or first sound above 2.0 s in the conversation configuration (stop condition 2), has no fallback: stop and report.
- [ ] **Live direction-angle trend.** KTD4 stops the turn when the angle starts growing (the voice was behind him), but the Binder carries only the angle latched over the utterance, so the turn stops on the side and the camera decides. A live 10 Hz angle feed during CUE_TURN is a launcher-side addition.
- [ ] **Appearance remarks (the owner's direction, a later plan).** He may comment on what he sees, still bounded by the workplace rule (R13, R14). The owner chose "read and remark" over quiet tailoring and sliced it out of the meeting plan so the first slice shipped address, turning, conversation, notes and persona.
- [ ] **Docked addressability.** On the charger he does not listen for address (KTD6: the ears follow the `CPL=3` latch; a conversation already open finishes). Whether he answers there belongs to the always-on plan, with the battery and dock behaviour.
- [ ] **Low obstacles: camera check if wheels ever spin in place.** Stalls against something too low for the front sensor are now caught by the wheel encoders (PR for stall detection, 2026-09-24): on the robot the wheels stopped dead. If he's ever seen pushing with wheels *spinning* (encoders counting, robot not moving), add the owner's camera idea: compare frames during a leg, and treat "the view hasn't changed" as blocked.
- [ ] **Floor tuning session with the owner** (camera curiosity plan U8): `python3 scripts/qa-explore-mode.py --only curiosity --curious-seconds 90`.
  - Confirm he turns *toward* an off-centre target. Steering assumes the camera frame isn't mirrored; this hasn't been checked on the robot.
  - Trim near-synonyms in `mode-explore/assets/vocabulary.txt` (tv/monitor/computer, plant/succulent, the chair/laptop pair seen on one box), so one thing isn't inspected twice under two names. Re-export the model and regenerate the name clips afterwards.
  - Tune the confidence and unsure floors, frame fill and approach legs (`ExploreTuning`) against junk sightings (a full-frame "train") and frequent "lost sight".
  - Watch escapes (PR #11): an escape sweep gives up after 6 s of turning, assumed to be about a full circle (`ExploreTuning.escapeSweepMaxMs`). If he spins well past a full turn, or gives up short of one, adjust it.
  - The detector now runs at 320x416 (~0.6 s a look, was ~1.4 s). Watch whether he misses small or far-away things he used to see; if so, try 384x512 (`scripts/export-explore-detector.py --imgsz 384 512`).
- [ ] **Explore on Claude: checks left from the 2026-09-25 session.**
  - Greet-by-name: he stored the owner's face and name (a real face this time, found by YuNet), but hasn't yet recognized them at a later stop. Stand in front of him at his height and confirm he says "Ben" instead of asking (the first check of `scripts/qa-conversation.py`).
  - Brightness: exposure compensation is now maxed (+2 EV, commit fe705ab). Pull `last-face.jpg` after the next face stop and compare with the dark 2026-09-25 09:59 one; if still dim, lift shadows on the face crop in software.
  - He picked a ceiling light twice in a row; check whether the camera tilt or the no-repeat rule keeps people out of his picks (top-3 picks with "seen" expiring after ~10 min is the planned fix).
- [ ] **Trap memory (owner deferred 2026-09-25).** After a wedge, remember that heading and steer away from it for ~5 min, so he doesn't drive straight back into the same trap (seen live: freed from a desk-and-cable wedge, he drove right back). Revisit after the nav plan's escapes (U5) and doorway attraction (U6) are tried on the robot.
- [ ] **Coverage memory: stop him circling one patch (owner, 2026-09-25).** Seen live: he wanders one area and stays there. Use the nav plan's path log (U2, heading tracker) as a dead-reckoned "where I've been" map and bias wander headings toward places he has not been recently, and toward doorways when the current patch is worn out. Raised during the meeting-and-small-talk brainstorm, which does not own navigation.
- [ ] **Detect the motor stall cutout.** After repeated stalled pushing the motor board stops executing turns and reverse until a full power cycle (seen 2026-09-25: motors buzz, encoders and gyro flat, even in open space). Explore could notice "every turn and reverse blocked, forward also still" across two rested ladders, stop driving, and show a "please restart me" face instead of grinding.
- [ ] **Desk-edge fix.** Blind turns and back-offs at desk edges. This blocks putting him back on the desk, and blocks acceptance check AE3 (an edge during an approach) on the desk.
- [ ] **Recover a wedged camera at boot.** After a cold power-up the camera HAL can wedge, and only a root restart of `cameraserver`/`camerahalserver` clears it. Have the boot agent do that with `setprop ctl.restart`, never `kill -9` (see `docs/solutions/runtime-errors/kill-9-on-watched-service-permanently-disables-restart.md`). Until then, curiosity retries every 2 min, each time with an ~8 s stop and no picture.
- [ ] **Review residuals from PR #7** (not urgent):
  - Move the curiosity half of `ExploreBrain` (988 lines) into its own class.
  - Add a build check that `assets/detector.onnx` was exported from the shipped `vocabulary.txt`.
  - Share one vocabulary parser between `gen-explore-voice.py` and `export-explore-detector.py`.
  - Make Camera2 callbacks in `ExploreCamera` ignore a stale open (per-open generation).
  - Add harness scenarios for the approach give-up, the mid-approach re-centre, the face-turn cap, and a scan that ends with nothing seen.

## Remote-control mode

- [ ] Confirm it still drives forward after the shared `DirectMotorDriver` change (needs someone watching the robot).

## Launcher

- [ ] **LAN authentication for the Settings page.** It is reachable from any browser on the office network with no login, and since the meeting plan it carries the persona box and every person's notes (KTD11). Until it has one, the mitigations are the 2,500-character persona cap and the guard block that sits outside the persona in the system prefix, so an edit cannot relax it.
- [ ] Stop the Wi-Fi password reaching logcat. The Wi-Fi forms send it in a GET query (`/wifi/connect?...&password=`), and `RoutingHttpServer` logs every request line. Move those forms to POST, or redact the query in the log line.

## Spike drive test

- [ ] Fix `spike-drive-test/build.py`: it fails on committed code because its shared-class list is missing `SensorSnapshot` and `SensorReply`.

## Robot voice

- [ ] **Speech under Explore's load (measured 2026-09-24, `scripts/voice/speed-check.py`).** Idle, the stock lessac voices meet the 2 s target at 4 threads: low 0.84 s to first sound (RTF 0.41), medium 1.10 s (RTF 0.52). With Explore frozen mid-scan (camera and detector busy), medium at 4 threads fell to 4.89 s and RTF 2.96, too slow to stream. The owner chose to train medium anyway (lessac medium checkpoint `en/en_US/lessac/medium/epoch=2164-step=1355540.ckpt`, 22050 Hz). The speech service has to make it work under load: speak only after a look finishes (the detector is idle while he talks), and measure 2 threads under load, which the check didn't cover.
