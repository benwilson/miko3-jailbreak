---
title: "SoundPool plays silently on the Miko 3 even though load and play succeed; use MediaPlayer on STREAM_MUSIC"
date: 2026-09-22
category: integration-issues
module: "Miko 3 explore mode startle chirp (mode-explore ClipPlayer)"
problem_type: integration_issue
component: frontend
symptoms:
  - "Startle chirp is inaudible on the robot although the brain logs 'hazard while HOP' and calls playStartle()"
  - "SoundPool OnLoadCompleteListener reports status 0 for every clip and pool.play() returns a non-zero stream id"
  - "dumpsys audio shows STREAM_MUSIC unmuted at 10/15, no audio focus holders, and logcat has no SoundPool errors"
  - "The same short WAV played through MediaPlayer on STREAM_MUSIC is clearly audible"
root_cause: wrong_api
resolution_type: code_fix
severity: medium
related_components:
  - "infrastructure"
tags:
  - "soundpool"
  - "mediaplayer"
  - "android-audio"
  - "silent-playback"
  - "vendor-audio-hal"
  - "explore-mode"
  - "handlerthread"
---

# SoundPool plays silently on the Miko 3 even though load and play succeed; use MediaPlayer on STREAM_MUSIC

## Problem

On the Miko 3, the explore mode's startle "whoa" chirp made no sound. `ClipPlayer` played it through `android.media.SoundPool`. Every Android call reported success, but the speaker stayed silent. Swapping to one `MediaPlayer` per clip on `STREAM_MUSIC` fixed it.

## Symptoms

- The brain logged `hazard while HOP` and called `playStartle()`, so the mode did ask for the sound.
- `ClipPlayer` logged `clip N loaded, status 0` for all three clips (from `OnLoadCompleteListener`), then `startle clip 2 -> stream 1`. `SoundPool.play()` returned a non-zero stream id, which means Android accepted the playback. The owner, standing at the robot, heard nothing.
- `dumpsys audio` looked healthy: `STREAM_MUSIC` not muted, speaker volume 10/15, no audio focus holders.
- logcat showed no SoundPool or AudioFlinger errors.
- The clips (`mode-explore/assets/startle-*.wav`: 22050 Hz, mono, 16-bit, 0.28-0.40 s) were confirmed as `Stored` (uncompressed) in the APK with `unzip -v`.

## What Didn't Work

Each of these was checked and ruled out before the fix:

- **Clip packaging or compression.** `unzip -v` showed the WAVs stored uncompressed, which is what the `AssetFileDescriptor` load needs.
- **Load failure.** All three loads reported status 0.
- **Muted or zero-volume stream.** `dumpsys audio` showed `STREAM_MUSIC` unmuted at 10/15.
- **The app never calling play.** Logs show it called play and got a stream id back.
- **Speaker amp still waking up** (the theory that a 0.3 s clip ends before the amplifier powers on). Ruled out because `MediaPlayer` played the same 0.3 s clip audibly with no warm-up.

Tuning SoundPool (attributes, priority, volume, max streams) was not pursued further. The A/B test below showed a different player on the same stream worked, so the fault lies in the SoundPool path itself on this device.

## Solution

**The decisive test.** A temporary debug HTTP route in the explore mode played the same `startle-1.wav` three ways, one at a time, with the owner listening:

1. SoundPool, configured exactly as the mode had it: **silent**.
2. `MediaPlayer` on `AudioManager.STREAM_MUSIC` via `assets.openFd` (the path `SongPlayer` uses for `danger-zone.mp3`): **audible**.
3. `MediaPlayer` after 600 ms of `AudioTrack` silence (the amp warm-up check): **audible**.

After `ClipPlayer` was switched to `MediaPlayer`, the mode's own `playStartle()` was triggered through the route and was audible. On the robot, a hand held in front of it mid-hop now produces an audible chirp. The debug route was removed afterwards.

**Before**, SoundPool (from the pre-fix `ClipPlayer`):

```java
pool = new SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build())
        .build();
startleIds[i] = pool.load(afd, 1);           // status 0 on load
...
pool.play(id, 1f, 1f, 1, 0, 1f);             // returns stream 1, no sound
```

**After**, one prepared `MediaPlayer` per clip, owned by a private `HandlerThread` (`mode-explore/src/com/miko3/mode/explore/ClipPlayer.java`):

```java
private final HandlerThread thread = new HandlerThread("explore-clips");   // :31
private final MediaPlayer[] startles = new MediaPlayer[STARTLE_CLIPS.length];

ClipPlayer(Context context) {                                              // :36-47
    thread.start();
    handler = new Handler(thread.getLooper());
    handler.post(() -> { for (...) startles[i] = prepare(context, STARTLE_CLIPS[i]); });
}

private static MediaPlayer prepare(Context context, String asset) {        // :49-66
    MediaPlayer player = new MediaPlayer();
    AssetFileDescriptor afd = context.getAssets().openFd(asset);
    player.setAudioStreamType(AudioManager.STREAM_MUSIC);
    player.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
    afd.close();
    player.prepare();          // on failure: log, release, return null
    return player;
}

void playStartle() {                                                       // :70-88
    handler.post(() -> { p.seekTo(0); p.start(); });   // null clip is skipped
}

void release() {                                                           // :90-103
    handler.post(() -> { /* release each player, null it */ });
    thread.quitSafely();
}
```

(The real file uses anonymous `Runnable`s. The lambdas above are shorthand.)

The fix is on branch `feat/explore-mode` in PR #4 (benwilson/miko3-jailbreak), which is **unmerged as of this writing**.

## Why This Works

- `MediaPlayer` on `STREAM_MUSIC` is a path already proven audible on this robot. `mode-remote-control/src/com/miko3/mode/remotecontrol/SongPlayer.java:33-44` plays its bundled song this way, with the same `openFd` + `setAudioStreamType(STREAM_MUSIC)` + `setDataSource(fd, offset, length)` + `prepare()` sequence. The new `ClipPlayer` copies that sequence.
- Nobody knows why SoundPool is silent. The presumed cause is the vendor audio policy or HAL on this Miko 3 build mishandling SoundPool's output (for example its `USAGE_GAME`/`SONIFICATION` routing, or its fast-track mixer path). This is **unverified**. The A/B test shows only that SoundPool is silent and `MediaPlayer` is audible on the same stream, speaker and volume.
- The original reason for choosing SoundPool was that `play()` returns immediately, so a startle never delays the motor `stop()` the brain sends just before it. That reason still holds with the new design. The players are prepared once, up front, on the `explore-clips` thread, and `playStartle()` only posts `seekTo(0)` + `start()` to that thread. The brain thread never blocks on audio I/O or decode.
- Because each clip has its own player, clips never compete for one player's state. `seekTo(0)` lets a clip replay from the start whether or not it has finished.

## Prevention

- **Do not use SoundPool on this robot.** When the next mode needs sound, start from a path already proven here:
  - Short or looping bundled assets: `MediaPlayer` on `STREAM_MUSIC` from `assets.openFd`, as in `SongPlayer.java:33-44` and `ClipPlayer.java:49-66`. For latency-sensitive triggers, prepare players ahead of time and start them on a dedicated `HandlerThread`, as `ClipPlayer` does.
  - Streamed or generated PCM: `AudioTrack` in `MODE_STREAM`, as in `mode-voice/src/com/miko3/mode/voice/VoicePlayer.java:72-82` (`USAGE_VOICE_COMMUNICATION` / `CONTENT_TYPE_SPEECH`, 16-bit mono). The voice-mode audio path has been verified on the robot (auto memory [claude]). The same memory note records that AEC ducks the mic while the speaker plays, so do not design for barge-in.
  - After this fix, `ClipPlayer.java` is the only source file that mentions SoundPool, and only in its comment warning against it (`ClipPlayer.java:19-21`). Keep that warning if the file is refactored.
- **A non-zero SoundPool stream id does not prove sound came out.** The same goes for `load` status 0, a successful `MediaPlayer.start()`, or `AudioTrack.write()` returning bytes. On this device, "Android accepted it" and "the speaker played it" are separate facts. The only acceptance check for audio is a person listening at the robot.
- **A/B test audio paths on the device quickly.** When a sound is silent despite success logs, stop reasoning from logs and put a temporary debug HTTP route in the mode (the modes already serve HTTP). The route should play one asset through each candidate path in turn: SoundPool, `MediaPlayer`/`STREAM_MUSIC`, and `AudioTrack` (optionally after a few hundred ms of silence, to test amp warm-up). Hit the route one path at a time with someone at the robot saying which ones are audible. It takes minutes and rules out packaging, volume and amp theories in one go. Delete the route before merging.
- **Quick checks before the A/B test, all cheap and none conclusive alone:** `unzip -v <apk> | grep wav` (must be `Stored` for `openFd`); `adb shell dumpsys audio` (stream mute and volume, focus holders); logcat for `AudioTrack`/`AudioFlinger`/`SoundPool` errors.

## Related Issues

- `docs/hardware/voice-mic.md`: the robot's audio hardware. This doc is its first device-verified finding about which Android playback APIs work from app code.
- `mode-remote-control/src/com/miko3/mode/remotecontrol/SongPlayer.java` and `mode-voice/src/com/miko3/mode/voice/VoicePlayer.java`: the two playback paths known to work.
- PR #4 (benwilson/miko3-jailbreak): the explore mode, where this was found and fixed.
