# Hardware & feature reference — index

Complete reverse-engineering pass over the Miko 3's hardware-control and
"smart" feature stack, built for the custom-launcher/custom-ROM effort. Every
doc here separates CONFIRMED (code/string evidence, cited file:line) from
INFERRED and UNKNOWN, and ends with an Implementation section containing real,
source-derived code samples plus an Open Questions list.

Primary source material: four decompiled APKs pulled from the device itself
(not just static firmware) —

| App | Package | Role |
|---|---|---|
| `tools/base.apk` / `tools/extracted-system/` | `com.miko.mikoplus` (MikoPlus v69) | Kiosk UI shell — single Activity, 28 fragments, **zero** hardware-driver code of its own |
| `tools/serviceexam.apk` → `tools/serviceexam_jadx/` | `com.example.root.serviceexam` (v92) | The real privileged hub — `sharedUserId=android.uid.system`, owns motors, mic, wake-word, DOA, cloud speech, LEDs |
| `tools/launcher_alpha_V2-v4.2.apk` / `tools/extracted/` | `com.miko.launcher_app` | Older/parallel HOME launcher, also links the motor-driver native libs but never calls them |
| `tools/game_robot_maker.apk` | `es.monkimun.game_robot_maker` | Ruled out — licensed Lingokids/Unity game, no hardware ties |

Plus `firmware/dump*/{system,vendor,boot,userdata}.img` (pulled via `debugfs`,
read-only, no mount needed on macOS) and public marketing/spec-sheet research.

## The single most important finding

**`ServiceExam`'s AIDL service (`MyService`) is `exported="true"` gated only by
the `INTERNET` permission — a normal, auto-granted permission. There is no
signature check, no caller-UID check, no root requirement.** Any app that
declares `<uses-permission android:name="android.permission.INTERNET"/>` and
binds `Intent("my.service").setPackage("com.example.root.serviceexam")` gets a
live Binder handle into the same privileged, system-UID process MikoPlus
itself talks to — full access to speech, motion, LEDs, face recognition, the
lot. See `aidl-dispatch.md`.

This resolves the earlier open question from this project's `su`-gating
findings (`docs/persistent-adb-normal-boot.md`): that finding was specific to
*calling `su` directly*. It never applied to *this* path, and this path needs
no platform signing key, no rebuilt system image, and no AVB fight — a normal
third-party APK, installed the normal way, can reuse ServiceExam as-is. This
is the "just replace the kiosk launcher and it works" outcome.

## Reading order

1. **`feature-inventory.md`** — MikoPlus's manifest, fragments, and AIDL
   dispatch codes (starting point/map).
2. **`aidl-dispatch.md`** — the full IPC protocol into ServiceExam, the
   no-auth finding above, ~90-row dispatch-code table, working bind-and-drive
   code.
3. **`motors-wheels.md`** — wheel/base motion. Dead "Arya" 0x3C-framed
   channel vs. the live `MotionMsg`/`"VEL1="` channel actually used by the
   voice-direction auto-turn feature; full call path to `/dev/ttyS2`; a
   previously-undocumented head-motor axis (`HeadMsg`).
4. **`buttons-power.md`** — power/volume/mute/camera-shutter keycodes (all
   stock evdev, no app intercepts them); the real volume control is a
   6-level voice/AIDL-driven scale, not physical-button logic.
5. **`laser-leds.md`** — no laser in this driver layer (see
   `marketed-features.md` for what "laser" actually is); two independent LED
   control surfaces (I2C `RGBController` + sysfs `leds-mt65xx`).
6. **`camera-vision.md`** — Camera2 pipeline, on-device face recognition
   (BlazeFace→FaceMesh→MobileFaceNet, threshold 0.8) present in *both*
   MikoPlus and ServiceExam sharing `/sdcard/klug/vision/` state; LiveKit/
   WebRTC video calling, fully traced.
7. **`person-tracking.md`** — "follow me" is real ncnn/YOLOv4 person
   detection + a tracking scaffold, but never wired to the motor layer —
   honest negative result, not a working feature in this firmware.
8. **`voice-mic.md`** — wake-word (on-device TFLite + legacy Sensory SDK),
   two VAD implementations, and the mic-array DOA → voice-direction turning
   mechanism (Conexant DSP), fully traced end to end.
9. **`conversation-ai.md`** — "real-time conversation" is cloud, not
   on-device: WebSocket/REST to Miko's backend, which brokers Google Cloud
   Speech/Houndify (STT) and Google/Azure/Polly (TTS). No local LLM exists on
   this device.
10. **`boot-hal-reference.md`** — ground-truth key layout, device nodes,
    init services, HAL manifests pulled directly from `system.img`/
    `vendor.img`/`boot.img`.
11. **`marketed-features.md`** — official spec sheets, app-store listings,
    and press coverage, cross-checked against the above; resolves "laser" =
    Time-of-Flight range sensor, confirms a physical camera-shutter button,
    flags an unresolved Bluetooth marketing/manifest contradiction.
12. **`game-robot-maker.md`** — ruled out as a licensed Unity game, no
    reusable hardware code.

## Known gaps (see each doc's Open Questions for detail)

- Exact reconciliation of the `MotionMsg`/`"VEL1="` framing vs. the earlier
  `Motion2Msg`/`ByteUtils` M1–M4 payload variants — both are real, the live
  call path uses `MotionMsg`, `Motion2Msg`'s callers weren't found.
- Whether `WifiService` and the two `OverlayScreenServiceLib` AIDL services
  (same no-auth posture as `MyService`) expose further reusable surface —
  not traced.
- What algorithm Miko's cloud backend uses to generate conversational replies
  (scripted NLU vs. LLM) — not determinable from a device-side decompile.
- The Bluetooth marketing-vs-manifest contradiction from `marketed-features.md`.
