# To-do

The running list of what is left to do on the robot's modes. Add to it when something comes up, and tick items off (or delete them) in the PR that finishes them.

## Explore mode

- [ ] **Low obstacles: camera check if wheels ever spin in place.** Stalls against something too low for the front sensor are now caught by the wheel encoders (PR for stall detection, 2026-09-24): on the robot the wheels stopped dead. If he's ever seen pushing with wheels *spinning* (encoders counting, robot not moving), add the owner's camera idea: compare frames during a leg, and treat "the view hasn't changed" as blocked.
- [ ] **Floor tuning session with the owner** (camera curiosity plan U8): `python3 scripts/qa-explore-mode.py --only curiosity --curious-seconds 90`.
  - Confirm he turns *toward* an off-centre target. Steering assumes the camera frame isn't mirrored; this hasn't been checked on the robot.
  - Trim near-synonyms in `mode-explore/assets/vocabulary.txt` (tv/monitor/computer, plant/succulent, the chair/laptop pair seen on one box), so one thing isn't inspected twice under two names. Re-export the model and regenerate the name clips afterwards.
  - Tune the confidence and unsure floors, frame fill and approach legs (`ExploreTuning`) against junk sightings (a full-frame "train") and frequent "lost sight".
  - Watch escapes (PR #11): an escape sweep gives up after 6 s of turning, assumed to be about a full circle (`ExploreTuning.escapeSweepMaxMs`). If he spins well past a full turn, or gives up short of one, adjust it.
  - The detector now runs at 320x416 (~0.6 s a look, was ~1.4 s). Watch whether he misses small or far-away things he used to see; if so, try 384x512 (`scripts/export-explore-detector.py --imgsz 384 512`).
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

- [ ] Stop the Wi-Fi password reaching logcat. The Wi-Fi forms send it in a GET query (`/wifi/connect?...&password=`), and `RoutingHttpServer` logs every request line. Move those forms to POST, or redact the query in the log line.

## Spike drive test

- [ ] Fix `spike-drive-test/build.py`: it fails on committed code because its shared-class list is missing `SensorSnapshot` and `SensorReply`.
