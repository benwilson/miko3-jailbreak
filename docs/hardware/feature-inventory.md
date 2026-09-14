# MikoPlus feature inventory — master catalog

Decompiled from `tools/base.apk` (identical dex set to `tools/extracted-system/`:
`classes.dex`..`classes5.dex`, same `lib/arm64-v8a/*.so`, same `assets/`) using
`apktool d` (manifest + resources) and `jadx` (full Java source, 14.8k files,
`sources/com/miko/**`). Cross-reference target for `docs/hardware/boot-hal-reference.md`
(HAL/driver-layer findings) and `docs/hardware/camera-vision.md` /
`docs/hardware/voice-mic.md` (deep dives spun out of this doc).

**Package: `com.miko.mikoplus`**, `sharedUserId="android.uid.system"`,
`compileSdk 33` / `platformBuildVersionName 13` (built against a much newer SDK
than the device's Android 9 — normal for an OTA-updated system app; matches
`docs/kiosk-recovery-fix.md`'s "MikoPlus v69" update payload). This is almost
certainly the same app whose kiosk splash-loop bug that doc fixed.

## Architecture — CONFIRMED: single-Activity, not multi-component

This is the single most important structural finding: **the manifest declares
exactly one `<activity>`.** Nearly every "feature" you'd expect to see as a
separate exported Activity/Service in a normal Android app inventory is instead
a `Fragment` (28 of them under `com/miko/mikoplus/activity/appui/*`) swapped
into `MikoActivity`, a single 8428-line god-activity
(`com/miko/mikoplus/activity/appui/MikoActivity.java`) that implements ~15
callback interfaces (`OnActionSelected`, `AIDLCallbackListner`,
`TTM3ScreenUI.TTMInteractor`, `CallProtocol`, etc.) and owns the AIDL
connection to ServiceExam. `MikoActivity.endSplash()` is the exact method
`docs/kiosk-recovery-fix.md` traced the splash-loop bug to.

**Implication for a custom launcher:** you cannot replicate MikoPlus by
re-declaring its manifest components — there's almost nothing there to copy.
Replication means re-implementing the *fragment graph* hosted inside your own
single activity, and re-establishing the same AIDL contract with ServiceExam
(or whatever you replace it with).

## Manifest catalog (CONFIRMED — from `apktool d tools/base.apk`)

### Activities
| Component | Exported | Notes |
|---|---|---|
| `activity.appui.MikoActivity` | **true** | `launchMode="singleInstance"`, landscape, `MAIN`/`LAUNCHER`. The entire app. |

(`activity.TestActivity` exists in the dex but is **not declared in the
manifest** — dead/debug code, unreachable via `am start` unless manually
exported.)

### Services
| Component | Exported | Notes |
|---|---|---|
| `io.livekit.android.room.track.screencapture.ScreenCaptureService` | not exported | `foregroundServiceType="mediaProjection"`, `stopWithTask="true"`. Screen-share half of LiveKit video calling — see camera-vision.md §6. |
| `androidx.room.MultiInstanceInvalidationService` | not exported | Stock Jetpack Room boilerplate, not Miko-specific. |

No custom always-on background service, no `BootReceiver`, no
`BroadcastReceiver` at all is declared in this manifest. (`services/SdCardReceiver.java`
exists in source but is registered dynamically, not in the manifest, or is
dead code — not confirmed reachable.)

### Providers
| Component | Exported | Notes |
|---|---|---|
| `com.squareup.picasso.PicassoProvider` | false | Stock Picasso image-loading library init. |
| `androidx.lifecycle.ProcessLifecycleOwnerInitializer` | false | Stock Jetpack boilerplate. |

### `uses-permission` (CONFIRMED, full list)
`INTERNET`, `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `ACCESS_WIFI_STATE`,
`READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE`, `STORAGE`,
`MANAGE_EXTERNAL_STORAGE`, `CHANGE_WIFI_STATE`, **`CAMERA`**,
`MODIFY_AUDIO_SETTINGS` (declared twice), **`RECORD_AUDIO`**, `VIBRATE`,
`READ_PHONE_STATE`, `BLUETOOTH` (`maxSdkVersion=30`, also declared again
without the cap), `BLUETOOTH_ADMIN` (declared twice), `CHANGE_NETWORK_STATE`,
`ACCESS_NETWORK_STATE`, `WRITE_SETTINGS`, `WRITE_SECURE_SETTINGS`,
`QUERY_ALL_PACKAGES`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROJECTION`,
plus a self-defined signature permission
`com.miko.mikoplus.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`.

`WRITE_SECURE_SETTINGS` + `sharedUserId="android.uid.system"` is why this app
can silently flip system settings (it's how the ServiceExam/settings-shadow
interplay in `docs/kiosk-recovery-fix.md` and the bootagent's shadowed
`/system/bin/settings` technique matter — MikoPlus itself has the platform
privilege to write secure settings directly, no root needed, because it runs
as the system UID).

### `uses-feature`
`android.hardware.camera` (required), `android.hardware.camera.autofocus`
(required), `android.hardware.camera.front` (not required),
`android.hardware.telephony` (not required — vestigial, no phone-call code
found).

## Feature catalog by fragment package (CONFIRMED — file listing of `com/miko/mikoplus/activity/appui/*`)

| Package | Feature | Replication note |
|---|---|---|
| `childProfile/facetraining/` | Face enrollment + recognition (BlazeFace → FaceMesh → MobileFaceNet → SVM/Euclidean classifier) | See `camera-vision.md`. Needs Camera2, 3 bundled TFLite models, `libfaceDetection.so`. |
| `childProfile/` (VisionFaceTrain, ChildProfileHome) | Child-profile CRUD, voice-preset picker (`VoiceChangeDialog`/`VoiceChangePresenter`/`VoiceConfig`) | Voice picker is just a REST-backed list of TTS voice IDs, not on-device voice tech — see voice-mic.md. |
| `ftue/` | First-time-setup wizard: language select, child interests, **parent-app pairing over polling** (`ParentCheckPollingThread`, `FtueParentPairingPresenter`), linking-code error states | Cross-refs `docs/parent-account-unlock.md` / `docs/parent-unlock-email-verification-blocker.md` — this is the code path those docs fight with. |
| `mission/` | Gamification: gems, rewards, "adventure milestone" journey, daily bonus, NPS feedback popup, parent-profile setup nudge — ~50 model classes, all REST-backed (`mission/controller/*Contract.java` + Retrofit) | Needs a backend that speaks the same JSON contracts (`MissionJourneyModel`, `RewardData`, etc.) — pure app-side reimplementation of the UI without the backend just shows empty state. |
| `skillOperation/` + `skills/` | "Skill store": iHeartRadio-branded dialog (`IHeartSkillDialogFragment`), Story Maker (AI/templated story generation + deletion), Riddles skill (`RiddlesSkillMainFragment`, timer-driven) | Riddles/skills are server-driven content, not on-device logic — a custom launcher needs the content API, not new code. |
| `ganPainting/` | "GAN Painting" talent — a `BaseDialogFragment` using `ExoPlayerListener`; **no TensorFlow/OpenCV imports found** (checked) | INFERRED: plays a pre-rendered video/animation of generated art rather than running a GAN on-device. Not a vision-model feature despite the name. |
| `automode/` | `AutoModeFragment` — idle/ambient/autonomous mode when no child is interacting | Not deep-dived this pass; flagged for follow-up. |
| `max/` | `MikoMax` subscription tier dialogs (subscribe, commercial pitch, "talent" gating) | Pure paywall UI, REST-backed entitlement check. |
| `voiceTraining/` | **Correction (verified directly against source, 2026-09-14):** `VoiceTrainingMainFrag.java` (2818 lines) is a real multi-step phrase-enrollment flow, not a TTS-preset picker. It sends `AIDLProcess.GameEvent(181, "START_VOICE_TRAINING")` / `"STOP_VOICE_TRAINING"` (lines 471, 2008, 2258, 2305-2307, 2671) and renders server/ServiceExam-driven `ENROLL_PHRASE:<text>` callbacks per step (`sendcallBack`, ~line 2096-2168); step 1's phrase is literally `AppConstant.Mixpanel_Hello_Miko` ("Hello Miko", the wake phrase). It also gates on a device property `mikoProperties.getpropVal("ENABLE_VT_ON_DEVICE_VAD")` (line 472) — if not `"Y"`, it starts an internet-connectivity check, implying voice training normally depends on a cloud path unless on-device VAD is enabled. Separately, the same class also plays a preview sound (`/sdcard/klug/audio/blink3.m` via `MediaPlayer`, ~line 2600) — a UI cue, not the enrollment content. See `voice-mic.md` §4/§6 for full evidence; do not confuse this with the actually-separate `VoiceChangeDialog`/`VoiceChangePresenter` REST voice-preset picker (`PUT v1/updateVoice/<botId>`), which is the feature this row previously (incorrectly) described. | All actual mic capture/phrase matching/voiceprint training happens outside this APK (ServiceExam or lower); MikoPlus only orchestrates the AIDL start/stop and renders prompts. |
| `wifi/` | Wi-Fi setup fragment (`WifiHomeFragment`) | Standard `WifiManager` usage, not investigated deep. |
| `mediaPlayer/` | In-app media/story playback (ExoPlayer-based) | Standard. |
| `notification/` | In-app notification center + `BotCallModel` (incoming-call notification tie-in to Mikonnect) | — |
| `speedTest/` | Self-contained upload/download network speed test | Own HTTP client, not Miko-specific. |
| `dialog/`, `utils/`, `setting/`, `childdata/` | Supporting chrome/utility fragments | Not individually deep-dived. |

## Cross-cutting libraries bundled in the same APK

| Package | What it is | Note |
|---|---|---|
| `com.miko.mikonnect` (`com/miko/mikonnect/**`, ~45 files) | Parent-child **video calling** — dual backend: LiveKit (`LiveKitService.java`, WebRTC via `liblkjingle_peerconnection_so.so`) **and** Agora (`AgoraConfig.java`, `IRtcEngineCallback.java`) abstracted behind `CallServiceProtocol`/`CallProtocol` | See camera-vision.md §6 for the confirmed LiveKit `Room.connect()` call sequence. Agora path present but not traced this pass — flag as a fallback/legacy backend. |
| `com.emotix.arya` (`com/emotix/arya/**`, 74 files) | Grab-bag: `expression/` (`ImageMsg1`, `ImageFrame1` — matches `ImageMsg1AIDL`/`ImageFrame1AIDL` in the AIDL package, i.e. image-frame transport to/from ServiceExam), `networkio/controller/ClassRoomNetworkController` (remote-classroom/tutoring feature), crypto utils | INFERRED: "Arya" is a separate internal SDK Miko licenses/reuses across products; only a slice of it is wired into MikoPlus. Not deep-dived beyond package survey. |
| `com.miko3.aidl_lib` / `com.root.aidlFiles` | **The IPC contract with ServiceExam.** Interfaces: `UIEventAIDL` (the one `kiosk-recovery-fix.md` found version-mismatched), `ExpressionEventAIDL.expressionEvent(String)`, `TouchEventAIDL.touchEvent(String)` + `init(AnalyticsAIDL, GameControllerAIDL)`, `GameControllerAIDL.GameEvent(String)`/`getData(String)`/`getConnectivityInitialisationStatus(String)`, `AnalyticsAIDL`, `LoggerAIDL`, `UIDataAIDL` | **This is the integration point for the other agent's `libmiko_drivers.so`/`libserial_port.so` work.** MikoPlus does **not** link either library directly (confirmed: `grep -r` for both names across the entire decompiled source and `lib/arm64-v8a/*.so` filenames returns nothing). All physical-robot I/O (expressions/face display, touch sensors, "game controller" i.e. physical buttons/motors) crosses a Binder boundary as opaque JSON-ish `String` payloads into ServiceExam's process, which is presumably where those native libs actually live. A custom launcher needs to either speak this same AIDL contract to the stock ServiceExam, or reimplement ServiceExam's side too. |
| `com.miko3.downloader_lib` | Custom app-store/content downloader (progress-tracked, resumable) used by `mission`/`skillOperation` content delivery | — |
| `com.couchbase` + `libLiteCoreJNI.so` | Couchbase Lite embedded NoSQL — local cache/sync store, likely for the mission/skill content and child profile data | Not deep-dived; explains a self-contained sync-capable local DB used alongside Room/SQLite. |
| `com.amazonaws`, `com.auth0`, `com.nimbusds` | AWS SDK (S3 content fetch, per `download/models/request/FetchPresignedUrlRequest`), Auth0 + Nimbus JOSE (JWT) for parent-account auth | Backend-integration plumbing, not robot-hardware-specific. |

## Native libraries bundled (`lib/arm64-v8a/*.so`) and what loads them

| Library | Loaded by | Purpose |
|---|---|---|
| `libopencv_java3.so` | `VisionFaceTrain.java` (imports `org.opencv.*`) | Face-training capture/image-processing helper alongside Camera2 — see camera-vision.md. |
| `libtensorflowlite_jni.so`, `libtensorflowlite_gpu_jni.so` | `org.tensorflow.lite.Interpreter` used by `FaceMesh`, `MobileFaceNet` (loads `face_landmark_192x192.tflite`, `MobileFaceNet.tflite`) | Landmark + face-embedding inference, optionally GPU-delegated. |
| `libfaceDetection.so` | `BlazeFace.java` and `Yolov5.java` (both `System.loadLibrary("faceDetection")`, both expose `native` `detect()`/`init()`) | Custom JNI face detector — **both** the BlazeFace path (loads `face_detection_front.tflite`) and an alternate Yolov5n-based face+landmark detector are native code in this one `.so`, selected at runtime by `Recognizer.FACE_DETECTOR` (defaults to `BLAZEFACE_SHORT_RANGE_128_DETECTOR`). |
| `libtensorflow_inference.so`, `libtensorflow_demo.so` | **Not traced to a call site this pass** | Names match the legacy TensorFlow-Android "TF Classify" demo library naming convention; INFERRED to be dead/vestigial (no source file in `com/miko` imports `org.tensorflow.contrib.android.TensorFlowInferenceInterface` or similar — grep came up empty). Flag as open question. |
| `liblkjingle_peerconnection_so.so` | LiveKit's bundled WebRTC (`livekit.org.webrtc.*` imports in `LiveKitService`) | WebRTC media engine for video calling. |
| `libLiteCoreJNI.so` | `com.couchbase.lite.*` | Couchbase Lite local DB engine. |
| `libpl_droidsonroids_gif.so` | `pl.droidsonroids.gif.GifImageView` (used for all the `ft_*.gif`/onboarding animations) | GIF decode/playback. |
| `libconceal.so` | Facebook Conceal (encryption for local storage, e.g. SharedPreferences) | Not deep-dived. |
| `libc++_shared.so` | Shared C++ runtime for the above | — |

**Absent:** `libmiko_drivers.so`, `libserial_port.so` — confirmed not present in
`lib/arm64-v8a/` and not referenced anywhere in the decompiled source. These
belong to whatever hardware-driver-layer component sits behind the AIDL
boundary above (out of scope for this app; see `docs/hardware/boot-hal-reference.md`).

## "Crazy features" summary

- **Face enrollment/recognition** (BlazeFace + FaceMesh + MobileFaceNet, on-device, no cloud round-trip needed for matching) — see camera-vision.md.
- **Parent-child WebRTC video calling**, dual-backend (LiveKit primary, Agora present as alternate) — see camera-vision.md §6.
- **Gamified missions/rewards economy** (gems, milestones, daily bonus) — fully server-driven.
- **Skill store**: iHeartRadio audio, AI/templated Story Maker, Riddles.
- **"GAN Painting" talent** — name suggests on-device generative art; code shows only video playback, no ML imports. Likely server-rendered.
- **MikoMax subscription paywall.**
- **No laser, no SLAM/mapping, no teleop/remote-control-of-motors, no eye-tracking/face-follow motor control found** — confirmed absent by full-source grep (`laser` zero hits; `follow`/`track`/`pan`/`tilt`/`servo` near camera code not found tied to any actuator call — the only actuator-adjacent surface is the generic `GameControllerAIDL`/`touchEvent` Binder calls into ServiceExam, whose actual native implementation is out of scope for this app).
- **No on-device wake-word/STT/TTS/NLU code found in this APK** — see voice-mic.md for the full evidence and the implication (voice assistant logic lives elsewhere, not in MikoPlus).

## Open questions

- Where does conversational voice (wake word, STT, NLU, TTS synthesis) actually live, if not in MikoPlus? Candidates: ServiceExam itself, or a separate system app not present in `tools/extracted-system/`. Needs enumeration of `/system/app/` and `/system/priv-app/` beyond MikoPlus/ServiceExam.
- `libtensorflow_inference.so` / `libtensorflow_demo.so`: no call site found. Dead weight from a template, or reachable via reflection/JNI we didn't grep for correctly? Worth a `strings`/symbol-table pass on the `.so` directly if it matters.
- `automode/AutoModeFragment` (idle/autonomous behavior) not deep-dived.
- Agora path in `com.miko.mikonnect` (`AgoraConfig`, `IRtcEngineCallback`) not traced to see whether it's live/selected under any condition, or fully superseded by LiveKit.
- `com.emotix.arya` package (74 files) only surveyed at the directory level; `ClassRoomNetworkController` ("remote classroom") not investigated.
- Exact enrollment step order for face training (`ft0..ft3`, `ft_faceinframe`, `ft_moveyourface`, `ft_remvglasses`, `ft_lowlight`, `ft_facetraindone` GIFs) is INFERRED from asset naming only — the driving sequence in `VisionFaceTrain.java`/`ChildProfileHome` wasn't fully traced to confirm order and branching (e.g. what triggers the low-light or glasses-removal prompts specifically).
- **Cross-reference check (2026-09-14):** confirmed `tools/extracted-system` is
  genuinely the right target for this trio of docs — its decoded manifest
  (`com.miko.mikoplus`, `versionCode=69`, `versionName=7.3.0`) matches the
  MikoPlus v69 update already documented in `docs/kiosk-recovery-fix.md`, so
  this is the same build, not a mismatched artifact. For completeness,
  `recon/MikoST.apk` decodes to a **separate** package `com.miko.st`
  (single `MainActivity`, zero declared permissions, no `LAUNCHER`
  intent-filter) — reads as a minimal self-test/diagnostic stub, not a
  camera/vision/voice feature source, and not investigated further. A
  parallel firmware-image pass pulled `/system/app/game_robot_maker.apk`
  (46.8MB) from `firmware/dump/system.img` and flagged it as a possible
  additional "main app" candidate — **not decompiled in this doc** (out of
  scope: the brief was specifically `tools/extracted-system`). Given the
  version-number match above, `game_robot_maker.apk` is more likely a
  distinct bundled game/content app than the kiosk shell itself, but that's
  INFERRED, not confirmed — worth a dedicated pass if its feature set turns
  out not to be a subset of what's cataloged here.
- Button keycodes: a separate firmware-layer pass confirmed `POWER`/
  `VOLUME_UP`/`VOLUME_DOWN`/`MUTE` are standard evdev codes with no keylayout
  remap. Consistent with that here: no `KEYCODE_VOLUME*`/`KEYCODE_POWER`/
  `KEYCODE_MUTE`/`onKeyDown` hits anywhere under `com.miko.mikoplus` — the
  app does not intercept or reinterpret these keys itself.
