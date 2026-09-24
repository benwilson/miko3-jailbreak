# To-do

The running list of what is left to do on the robot's modes. Add to it when something comes up, and tick items off (or delete them) in the PR that finishes them.

## Explore mode

- [ ] **Better escape from being stuck (owner request, 2026-09-24).** He gets stuck too easily. He sees a wall, turns a little, sees the same wall, turns back the other way, sees it again, and three hazards in a short window put him in the 30 s "cornered" rest. Instead:
  - Treat a wall ahead as something to escape from. Turn, check the front sensor, and if the wall is still there, turn again. Keep going until the way is clear, rather than giving up after three quick hazard reactions.
  - Count *failed escapes*, not hazard sightings, toward "stuck". Rest only after about three escape attempts that each ran their turns and still found no clear way, within a short period.
  - Keep one turn direction through an escape, so he doesn't flip back and forth toward the same wall.
  - Where it lives: `ExploreBrain` (`refuse`, `hazardInMotion`, `recordHazard`, `enterCornered`), `ExploreTuning.cap(...)`.
- [ ] **Floor tuning session with the owner** (camera curiosity plan U8): `python3 scripts/qa-explore-mode.py --only curiosity --curious-seconds 90`.
  - Confirm he turns *toward* an off-centre target. Steering assumes the camera frame isn't mirrored; this hasn't been checked on the robot.
  - Trim near-synonyms in `mode-explore/assets/vocabulary.txt` (tv/monitor/computer, plant/succulent, the chair/laptop pair seen on one box), so one thing isn't inspected twice under two names. Re-export the model and regenerate the name clips afterwards.
  - Tune the confidence and unsure floors, frame fill and approach legs (`ExploreTuning`) against junk sightings (a full-frame "train") and frequent "lost sight".
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
