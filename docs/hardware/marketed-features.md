# Marketed features — Miko 3 (public/official sources only)

Desk research from Miko/Emotix's own marketing, help-center articles, app
store listings, FCC filings, patents, and third-party reviews. Purpose:
enumerate everything the robot is **advertised or documented** to do, so the
code-level decompilation passes (`feature-inventory.md`, `camera-vision.md`,
`voice-mic.md`, `boot-hal-reference.md`) have a checklist to confirm against.
No account access, no live backend interaction — public sources only.

Company: Miko is the consumer brand; the legal entity is **RN Chidakashi
Technologies Pvt Ltd** (also trades as "Emotix"), Mumbai, India. Current
robot: **Miko 3**, model **EMK301**, FCC ID **2AS3S-EMK301**. A cheaper
sibling product ("Miko Mini") exists and shares app/backend infrastructure —
flagged wherever a claim comes from a Miko Mini source and hasn't been
separately confirmed for Miko 3.

Confidence key: **HIGH** = official spec sheet/manual/FCC filing language.
**MEDIUM** = official marketing copy or help-center article, less precise.
**LOW** = third-party review/inference only. Every entry not already
cross-confirmed by the code-level docs gets a **NEEDS FIRMWARE/CODE
CONFIRMATION** flag.

## 1. Locomotion

- **Claim:** Three rubberized wheels; robot "rolls toward a child," navigates
  a room rather than sitting static; "advanced sensors for safe navigation
  and environmental awareness." (miko.ai/products/miko-3, MEDIUM; multiple
  reviews, LOW.)
- **Sensors named on the official spec sheet:** "Time of Flight Range
  Sensor" and "Odometric Sensors" (miko.ai/products/miko-3, HIGH — exact
  spec-sheet wording).
- **Obstacle/edge avoidance:** help-center troubleshooting copy says Miko
  "may have difficulty navigating on certain surfaces" and instructs owners
  to keep it off glass/glossy/black surfaces (help.miko.ai sensor
  troubleshooting article, MEDIUM) — this is the classic failure mode of an
  optical ToF sensor (specular/dark surfaces don't return the signal), which
  corroborates the ToF spec-sheet claim rather than, say, ultrasonic or
  bump-sensor navigation.
- **No autonomous person-following ("follow me") claim found** in any
  official source searched. Reviews describe Miko moving toward a child
  already in view/interacting, not tracking and following a moving target
  around a room.
- **Cross-check against code:** `camera-vision.md` §3 confirms **no**
  `follow`/`pan`/`tilt`/`servo`/`motor` call sites tied to face-detection
  code in MikoPlus, consistent with "moves when addressed" rather than true
  visual person-following. Motor control is inferred to live in ServiceExam
  or the native driver layer (`libmiko_drivers.so`/`libserial_port.so`),
  which is exactly what this marketing research **cannot** resolve — actual
  drive-control logic (differential drive? navigation algorithm? does the
  ToF sensor feed a real obstacle-avoidance loop or just a "don't drive off
  a table" cliff-style cutoff?) is **NEEDS FIRMWARE/CODE CONFIRMATION.**

## 2. The "laser" — resolved: it is the Time-of-Flight ranging sensor, not a laser pointer/projector

- **What triggered the ambiguity:** the official spec sheet lists a
  "Time of Flight Range Sensor," and separately, general product-safety
  documentation for Miko 3 describes it as containing a **Class 1 laser
  product** (IEC 60825-1 classification — eye-safe under all normal
  operating conditions). Search results did not surface this as a distinct
  marketed "laser feature," "laser pointer," "laser projector," or laser-tag
  style game.
- **Mechanism, inferred from the sensor category (MEDIUM confidence,
  not vendor-confirmed for this exact part):** consumer ToF range sensors in
  this size/cost class (e.g. STMicroelectronics' VL53L family — "VL53L0X
  Time-of-Flight **Laser** Ranging Sensor") work by pulsing a VCSEL
  (vertical-cavity surface-emitting laser) at ~940nm and timing the
  reflection back to a SPAD array. These parts are literally laser rangers
  and are exactly why a small consumer robot would carry a Class 1 laser
  safety rating without ever marketing "laser" as a headline feature — it's
  a rangefinder component, not a pointer/game mechanic.
- **We could not confirm the exact part number** used in Miko 3 from public
  sources (FCC filing PDFs for FCC ID 2AS3S-EMK301 were located but not
  successfully parsed by automated fetch in this pass — see Sources below;
  worth a manual pull if precision matters).
- **CONFIRMED by code-level research** (`camera-vision.md` §4): `grep -ri
  laser` across the entire decompiled MikoPlus source (14.8k files) returns
  **zero matches** — there is no app-level laser pointer/game/projector
  feature. This is fully consistent with the "ToF sensor, not a pointer"
  conclusion above.
- **Bottom line: no evidence anywhere, marketing or code, of a laser
  pointer, laser projector, or laser-based game.** The word "laser" on this
  robot refers to the emitter inside the distance-ranging sensor. Treat as
  **RESOLVED**, not a gap for the driver-layer agent to chase — unless a
  physical teardown finds a second, unexplained laser-class component.

## 3. Camera / vision

- **Camera hardware:** "Galaxy Core HD 5 MP wide-angle camera" per one
  help-center spec article; a separate help-center camera-app article
  states a "2-megapixel camera" for the in-robot Camera app's photo/video
  capture. **These two figures conflict** (help.miko.ai, MEDIUM confidence
  each, contradiction not resolved by this pass) — flag for the vision
  agent to resolve against the actual sensor driver/Camera2 `CameraCharacteristics`
  once device access is available. `camera-vision.md` independently
  confirms Camera2 API, `Size(640, 480)` preview and no facing-check
  (`getCameraIdList()[0]`), which doesn't itself resolve the MP figure.
- **Face & voice recognition / personalization ("Miko knows me" style):**
  Official claim: "identifies and responds to your unique voice and face"
  (miko.ai product page, HIGH — spec-sheet language); help-center: optional
  "Face Training" during setup lets Miko recognize a specific child for a
  "more personalized and engaging experience"; explicitly stated **opt-in**
  and **"all facial recognition data is stored locally on the Miko
  device"** (help.miko.ai "How Does Miko 3 Use Face Recognition," MEDIUM —
  paraphrased from search snippet, not the full article text since the page
  435 blocked automated fetch — verbatim text not independently re-read).
  This **matches** `camera-vision.md`'s fully-traced on-device pipeline
  (BlazeFace → FaceMesh → MobileFaceNet → SVM/Euclidean classifier, no cloud
  round-trip) almost exactly — strong marketing/code agreement.
- **"Mirror Me" app:** Miko's expression engine mimics the child's detected
  facial expression back at them in real time (help.miko.ai + miko.ai blog,
  MEDIUM). **NEEDS FIRMWARE/CODE CONFIRMATION** — not specifically
  identified as a named fragment/class in `feature-inventory.md`'s fragment
  catalog; the underlying expression-classification model (separate from
  the enrollment/recognition MobileFaceNet pipeline, or reusing FaceMesh's
  468-point landmarks for a coarse expression heuristic) is unconfirmed.
- **Photo/video capture:** in-robot Camera app takes selfie photos and short
  (~1 minute) video clips, stored in a "Moments" section (help.miko.ai,
  MEDIUM).
- **Video calling ("Mikonnect"):** two-way parent↔child video calling,
  initiable from either the robot or the parent app (miko.ai blog +
  help.miko.ai, HIGH — consistent, repeated marketing claim). **Fully
  confirmed and exceeded by code research**: `camera-vision.md` §6 traced
  the actual LiveKit/WebRTC join sequence (1280×720@30fps, VP9, 1.2Mbps,
  dual LiveKit/Agora backend) — this is the single best marketing↔code
  cross-confirmation in this whole doc.
- **Object recognition / educational AR:** No dedicated "object recognition"
  or "AR" robot-camera feature found in official sources. `miko.ai/pages/ar`
  exists as a URL but its content wasn't retrievable as a snippet in this
  pass — **UNCONFIRMED, worth a direct fetch** before concluding AR doesn't
  exist as a feature; it may refer to a phone-app AR mode rather than the
  robot's own camera. `camera-vision.md` §7 independently confirms no
  general object-detection/classification code exists in MikoPlus beyond
  the two **face**-specific detectors (including the confusingly-named
  `Yolov5.java`, which is a face+landmark detector, not a COCO object
  detector) — so if a marketed AR/object-recognition feature exists at all,
  it is almost certainly phone-side, not robot-camera-side.
- **"GAN Painting" talent:** exists as a named Talent (referenced in
  help-center Talent lists) but public marketing does not explain the
  mechanism — generic "draw, color, and code with Miko's AI collaboration
  features" copy only. `feature-inventory.md`/`camera-vision.md` **already
  resolved this from code**: no TensorFlow/OpenCV imports in
  `ganPainting/GanPaintingFragment.java`, which is an `ExoPlayerListener`
  dialog — i.e. **CONFIRMED (by code, not marketing): pre-rendered/
  server-generated video playback, not an on-device generative model**,
  despite the "GAN" branding implying otherwise. Marketing research adds
  nothing new here beyond confirming the feature is real and named
  "GAN Painting" in-product.

## 4. Microphone / voice

- **Wake phrase:** "Hey Miko" per the current official product page
  (miko.ai/products/miko-3, MEDIUM — marketing copy); the decompiled app's
  internal constant is `Mixpanel_Hello_Miko = "Hello Miko"`
  (`voice-mic.md` §2, HIGH/CONFIRMED-from-code). **These are two different
  phrases** ("Hey Miko" vs "Hello Miko") — either the product page is stale/
  imprecise marketing copy, the wake phrase changed between firmware
  revisions and the analytics constant name wasn't renamed, or both phrases
  are accepted. **NEEDS FIRMWARE/CODE CONFIRMATION** — worth checking
  whichever wake-word engine ships (location still unknown per
  `voice-mic.md`'s own open questions) for the literal grammar/phrase list.
- **Hardware:** "Dual MEMS Microphone" per the official spec sheet
  (miko.ai, HIGH). Only two mics — this caps what direction-finding is
  physically possible; a 2-mic array can only resolve azimuth ambiguously
  (front/back mirror ambiguity) without additional cues, unlike a 3+ mic
  circular array. Worth flagging directly to the driver-layer agent: if a
  "voice direction" feature is confirmed to exist on Miko 3, a 2-mic DOA
  implementation is the physical constraint it has to work within.
- **"Voice direction" (turn-toward-speaker):** **CONFIRMED as a real,
  named, user-facing toggle — but only directly sourced for Miko Mini**, not
  Miko 3. Miko's own help center: "Miko Mini turns toward the sound
  direction when this feature is turned ON, or listens without turning when
  turned OFF" (help.miko.ai Miko Mini voice-settings article, MEDIUM).
  Separately, the parent app's Miko 3 profile settings include "Tune Miko's
  Listening Power" / voice-sensitivity controls (help.miko.ai, MEDIUM), but
  no source found explicitly describing a turn-toward-speaker toggle
  *for Miko 3 specifically*. Given the app is shared (`com.miko3.app`
  serves both devices per the Play Store listing) this is plausibly a
  shared feature, but **mark UNCONFIRMED for Miko 3 until a source
  specifically names it there — do not assume parity with Mini.**
  **A related patent, "Robot voice direction-seeking turning system and
  method" (US10343287B2 / WO2017000775A1), is assigned to Yutou Technology
  Hangzhou Co Ltd — an unrelated Chinese robotics company, not Emotix/RN
  Chidakashi.** This patent surfaced prominently in search because of
  matching terminology; it is **not evidence of Miko's own mechanism** and
  should not be cited as such. No Emotix/Chidakashi-assigned patent on
  sound localization was found.
- **STT/NLU/conversational AI:** Marketing describes open-ended
  conversation ("fun, educational, age-appropriate conversations... Space,
  Wildlife, History, Geography, jokes, Dinosaurs, riddles" — App Store
  listing, MEDIUM) but never names an STT/NLU vendor or says on-device vs.
  cloud. This matches `voice-mic.md`'s finding of **zero** STT-related code
  in MikoPlus itself — marketing is equally silent on mechanism, so this
  stays **UNKNOWN / NEEDS FIRMWARE CONFIRMATION**, not contradicted, just
  un-illuminated by public sources.
- **TTS / voice personality:** parent app lets a caregiver pick a TTS voice
  preset (provider/gender/language/pitch/rate/volume) — this is the feature
  `voice-mic.md` §4 already traced in full as a pure REST config
  (`VoiceConfig`, `PUT v1/updateVoice/<botId>`). No new marketing detail
  beyond confirming this is user-facing (parent app "Profile Settings,"
  help.miko.ai, MEDIUM).
- **Voice enrollment / "voice training":** not separately marketed as a
  headline feature in the consumer-facing pages found (it reads as
  onboarding plumbing, not an advertised capability) — but `voice-mic.md`
  §5 already fully traced this from code as a real AIDL-driven multi-phrase
  enrollment flow, including the "Hello Miko" phrase substitution. No
  marketing source contradicts or adds to that code-level finding.
- **Singing / storytelling / language learning:** "sings, dances, tells
  stories" (miko.ai product copy, MEDIUM); "Story Maker" app lets a child
  co-create AI/templated stories with Miko, saved as a "digital memory
  book" (miko.ai blog + help.miko.ai, MEDIUM) — matches
  `feature-inventory.md`'s `skillOperation/skills/` "Story Maker" finding
  almost exactly (server-driven content, not on-device generation, per that
  doc). 8-language voice support claimed: English, Spanish, Mandarin,
  Italian, German, French, Arabic (+ one more regional variant) (miko.ai
  spec sheet, MEDIUM) — plausibly the consumer of `languageList.json`/
  `languageJson.json`, which `voice-mic.md` §8 found bundled but unconsumed
  in this pass; **NEEDS FIRMWARE/CODE CONFIRMATION** to tie the language
  list asset to this specific marketed claim.

## 5. Speaker / audio

- "High Performance Speakers" (spec sheet, HIGH — spec-sheet language, no
  further detail: no count, wattage, or driver size published).
- Music playback via a dedicated "Miko Music" Talent (help.miko.ai, MEDIUM)
  and iHeartRadio-branded audio content (matches `feature-inventory.md`'s
  code-level finding of an `IHeartSkillDialogFragment` — strong
  marketing/code agreement, though the exact branding partner wasn't
  independently re-confirmed by name in marketing search results this
  pass — **the code finding is the stronger evidence here**).
- No separately marketed "sound effects" feature beyond the general
  TTS/expression/personality framing already covered in §4.

## 6. Buttons / physical controls

- **Official button list (help.miko.ai "How To Use Miko 3's Buttons",
  MEDIUM–HIGH, paraphrased from search snippet):** Power button, Volume
  rocker (5 discrete levels), Mute button, **Camera shutter button**,
  charging indicator LED, charging point.
  - Power: short-press = sleep, press-and-hold 4 seconds = full power off.
  - **The "Camera shutter" button is a new find vs. the code-level docs so
    far** — none of `feature-inventory.md`/`camera-vision.md` mention a
    dedicated hardware camera-shutter key, only the in-app Camera app UI.
    This **directly corroborates** `boot-hal-reference.md` §1's confirmed
    key-layout dump (`/system/usr/keylayout/mtk-kpd.kl`), which independently
    lists **`key 212 CAMERA`** and **`key 211 FOCUS`** alongside
    POWER/VOLUME_UP/VOLUME_DOWN/MUTE — evdev keycodes for a physical camera
    shutter/focus button that the marketing/help-center language now
    confirms is real and user-facing, not vestigial MTK reference-board
    wiring. **Flag for the app-layer agent:** find the code that listens for
    `KEYCODE_CAMERA`/`KEYCODE_FOCUS` (212/211) — it wasn't located in
    MikoPlus by the fragment-catalog pass, so it likely lives in
    ServiceExam or a different system app (`MikoST.apk`/`game_robot_maker.apk`,
    both flagged unopened in `boot-hal-reference.md`'s open questions).
- **Touch sensors beyond the screen:** searched specifically for a
  "head pat" / capacitive body touch sensor (common on companion robots
  like Jibo, Cozmo, Vector). **No evidence found** of any touch sensor on
  Miko 3 beyond the main IPS/capacitive touchscreen itself. Given
  `feature-inventory.md`'s AIDL catalog already includes a generic
  `TouchEventAIDL.touchEvent(String)` interface, it's plausible that
  interface is screen-touch-only rather than body-touch, but this wasn't
  resolvable from marketing sources either way — **NEEDS FIRMWARE/CODE
  CONFIRMATION**, specifically: does `TouchEventAIDL` ever fire from
  anything other than the front screen? No body/head touch sensor should be
  assumed to exist absent stronger evidence.

## 7. Screen / display

- 4.46" (one source: "4.7-inch," a minor spec-sheet discrepancy across
  sources, MEDIUM) HD IPS touchscreen, 1280×720, 16:9 landscape, renders
  Miko's animated face/eyes plus all app/game UI (miko.ai spec sheet +
  help-center, HIGH for the core claim, MEDIUM for the exact diagonal size
  given the cross-source conflict).
- Facial expressions react live to conversation tone/content: "children who
  are clearly excited get a more animated Miko; quieter interactions
  produce a calmer, gentler response" (review/marketing paraphrase, LOW–
  MEDIUM — not a verbatim spec-sheet quote, treat as directional not
  precise).
- **"Mikojis"** — a dedicated app/Talent framing expression-triggering as
  emoji-like: child can "make Miko laugh, cry, blush or smile" on demand
  (miko.ai blog + help.miko.ai, MEDIUM). This looks like the marketing-
  facing name for the same underlying expression-display system
  `AIDLProcess.playExpression()`/`ExpressionEventAIDL` already traced in
  `voice-mic.md`/`feature-inventory.md` — **NEEDS FIRMWARE/CODE
  CONFIRMATION** to tie the `Mikojis` app specifically to that AIDL call
  rather than a separate expression path.

## 8. Games / apps / content ecosystem ("Talents")

- Branded as a library of **"Talents"** — 1000+ games/stories/videos/music/
  puzzles, STEAM-focused (miko.ai + App Store listing, HIGH — consistent
  across every official source checked).
- Named Talents/apps found in help-center articles: **Story Maker** (AI/
  templated story co-creation), **Mikojis** (expression play), **Ting Ting
  Tales** (stories), **Miko Music**, **Da Vinci Kids** (licensed STEAM video
  content), **iHeartRadio**-branded audio (via code cross-reference, not
  independently re-confirmed by name in this marketing pass), **Riddles**
  (via code cross-reference), quiz/STEAM "Journeys" with unlockable Yoga/
  Story/Quiz activities, and **"GAN Painting"** (see §3). This list is a
  **union of marketing-found and code-found names** — treat the two sets as
  cross-confirming each other rather than duplicative.
- **Licensed content brands named in marketing:** Disney, Paramount,
  Da Vinci Kids, Lingokids, Sesame Street, Koala Moon, "Mr. Jim's Bedtime
  Stories" (miko.ai + reviews, MEDIUM). "140-country launch... bringing
  Disney and Pixar characters to Miko 3" per a TechCrunch mention (HIGH —
  named outlet, though the exact article wasn't independently refetched
  this pass, only a search-result paraphrase).
- **Coding:** advertised STEAM/coding content exists as a Talent category;
  no further mechanism detail found (block-based coding vs. app-guided
  activities not distinguished by any source checked).
- **Gamification (gems/rewards/missions):** not independently re-confirmed
  by marketing search this pass, but `feature-inventory.md`'s
  `mission/` fragment-package finding (gems, rewards, "adventure milestone"
  journey, daily bonus, NPS feedback popup — fully server-driven) is
  detailed and specific enough that it stands on its own as CONFIRMED by
  code; no marketing contradiction found.

## 9. Subscription tier — Miko Max

- $14.99/month or $99/year (help.miko.ai + miko.ai/products/miko-max,
  MEDIUM — pricing noted as country-variable by Miko's own help article).
- Unlocks the full licensed-content catalog (Disney, Paramount, Da Vinci
  Kids, Lingokids, etc. — "50,000+ hours") beyond a baseline free tier that
  already includes "a dozen-plus" Talents (Mikojis, Ting Ting Tales, Miko
  Music confirmed free; exact full free-vs-paid split not fully enumerated
  from search snippets — help.miko.ai has a chart for this that wasn't
  fully retrieved, **worth a direct fetch if the launcher needs to
  replicate entitlement gating precisely**).
- Matches `feature-inventory.md`'s `max/MikoMax` fragment-package finding
  ("subscription tier dialogs... pure paywall UI, REST-backed entitlement
  check") — marketing confirms the tier is real and priced; code confirms
  the gating mechanism is a REST entitlement check, not a local flag.

## 10. Parental controls / companion app

- App name: **"Miko - Play, Learn, & Connect"** (`com.miko3.app` on Google
  Play; same listing serves both iOS/Android), HIGH — verified store
  listing.
- **Parental Controls, per the store listing (HIGH — direct app-store
  marketing copy):** set bedtime, limit screen time, lock apps.
- Profile settings: change timezone/language, adjust voice sensitivity
  ("Tune Miko's Listening Power").
- Unlimited video calling from app to robot (Mikonnect, see §3).
- Screen-time / activity insights: "see insights and highlights of how they
  use their Miko 3" (help.miko.ai, MEDIUM — vaguer than a precise "activity
  report" claim; exact report contents not detailed in sources found).
- This entire category is backend/account-mediated by design and out of
  scope for on-device replication in the strict sense, but relevant context
  for a custom launcher that wants to preserve parental-control parity —
  flagged as such per the task brief, not itself something to reverse
  engineer.

## 11. Connectivity

- **WiFi required; no cellular.** Confirmed by spec sheet ("WiFi connection
  required," HIGH) and consistent with `feature-inventory.md`'s manifest
  finding of `android.hardware.telephony` declared but **not required**
  ("vestigial, no phone-call code found").
- **Bluetooth: marketing says NO.** A dedicated Miko help-center article
  title directly answers "Does Miko 3 robot has Bluetooth?" with **Miko 3
  does not support Bluetooth connectivity** (help.miko.ai, MEDIUM —
  paraphrased from search snippet, exact article wording not independently
  re-read). **This conflicts with the decompiled manifest**, which declares
  `BLUETOOTH` (twice, one capped at `maxSdkVersion=30`) and
  `BLUETOOTH_ADMIN` (twice) (`feature-inventory.md`, CONFIRMED from
  `apktool d` manifest dump). Plausible explanations, none confirmed:
  (a) the permissions are vestigial/shared-codebase leftovers from another
  Miko product that does use Bluetooth (Miko Mini? an earlier Miko
  revision?), (b) Bluetooth exists in hardware but is deliberately unused/
  disabled and the help-center answer is accurate for the shipped
  experience, or (c) the help-center answer is simplified/inaccurate
  consumer-support copy. **Flag directly for the driver-layer agent:**
  check whether the Bluetooth radio is present in the BOM/FCC filing at all
  (worth a manual FCC-filing pull — this pass's automated fetch of the FCC
  PDFs failed, see Sources) before concluding either way.

## Consolidated feature checklist

For the code-level agents to check off against decompiled apps/firmware.
✅ = already cross-confirmed by existing code docs. ⚠️ = marketing claim
exists, code confirmation still needed. ❌(resolved) = investigated and
found NOT to exist, by both marketing and code.

**Locomotion**
- [x] ✅ Three-wheel autonomous drive toward an engaged child
- [ ] ⚠️ Time-of-Flight range sensor drives real-time obstacle avoidance (mechanism/thresholds unconfirmed)
- [ ] ⚠️ Odometric sensors (dead-reckoning / wheel encoders?) — role unconfirmed
- [x] ❌(resolved) No autonomous "follow me" person-tracking — absent from both marketing and code
- [ ] Actual drive-control/navigation algorithm — entirely unresolved, lives below the app layer

**"Laser"**
- [x] ❌(resolved) Not a laser pointer/projector/game — it's the ToF sensor's ranging emitter. No further investigation needed unless a teardown contradicts this.

**Camera / vision**
- [x] ✅ On-device face enrollment + recognition (BlazeFace → FaceMesh → MobileFaceNet → classifier), opt-in, locally stored
- [ ] ⚠️ Camera resolution: 5MP (spec article) vs 2MP (camera-app article) — reconcile against actual `CameraCharacteristics`
- [ ] ⚠️ "Mirror Me" live expression-mirroring — mechanism/model not identified in code yet
- [x] ✅ Two-way video calling (Mikonnect) — LiveKit/WebRTC, fully traced
- [ ] Object recognition / AR — likely doesn't exist on-robot; `miko.ai/pages/ar` not yet actually fetched, worth 5 minutes to rule out definitively
- [x] ❌(resolved) "GAN Painting" — pre-rendered video, not an on-device generative model, per code (matches vague marketing)

**Microphone / voice**
- [ ] ⚠️ Wake phrase: "Hey Miko" (marketing) vs "Hello Miko" (code constant name) — reconcile
- [x] ✅ Dual MEMS mic hardware (constrains any DOA claim to 2-mic-array physics)
- [ ] ⚠️ "Voice direction" turn-toward-speaker — confirmed for Miko Mini, UNCONFIRMED for Miko 3 specifically; find out if Miko 3 has this toggle at all
- [ ] STT/NLU engine and location — totally unresolved by both marketing and code
- [x] ✅ TTS voice-preset picker — REST-backed, fully traced
- [x] ✅ Voice enrollment flow — AIDL-driven, fully traced, "Hello Miko" is a taught enrollment phrase
- [ ] ⚠️ 8-language voice support — tie to `languageList.json`/`languageJson.json` assets

**Speaker / audio**
- [x] ✅ Music playback Talent, iHeartRadio content (from code; not independently re-confirmed by name in marketing this pass)

**Buttons / touch**
- [x] ✅ Power / Volume(5-level) / Mute — matches `mtk-kpd.kl` keylayout exactly
- [ ] ⚠️ **New: dedicated Camera shutter + Focus buttons** (marketing-confirmed, matches `KEY 212 CAMERA`/`KEY 211 FOCUS` in keylayout) — find the consuming code (not in MikoPlus; check ServiceExam/MikoST.apk/game_robot_maker.apk)
- [x] ❌(resolved, low confidence) No body/head touch sensor found in marketing; likely doesn't exist beyond the screen — confirm `TouchEventAIDL` is screen-only if possible

**Screen / display**
- [x] ✅ IPS touchscreen renders animated face/expressions, reactive to conversation
- [ ] ⚠️ "Mikojis" expression-triggering app — tie to `ExpressionEventAIDL`/`playExpression()` specifically

**Games / apps**
- [x] ✅ Talents ecosystem: Story Maker, Mikojis, Ting Ting Tales, Miko Music, Da Vinci Kids, iHeartRadio, Riddles, Quiz/Yoga/Story "Journeys," GAN Painting — union of marketing + code findings
- [x] ✅ Gamification economy (gems/rewards/missions) — server-driven, from code
- [ ] Coding-content mechanism (block-based? app-guided?) — undetermined

**Subscription**
- [x] ✅ Miko Max — REST entitlement-gated paywall, priced $14.99/mo or $99/yr, confirmed both sides

**Parental controls / app**
- [x] ✅ Bedtime / screen-time limits / app-locking, video calling, voice-sensitivity tuning — all confirmed real, backend-mediated (context only, not an on-device reverse-engineering target)

**Connectivity**
- [x] ✅ WiFi-only, no cellular (telephony permission vestigial)
- [ ] ⚠️⚠️ **Bluetooth: marketing says absent, manifest declares the permissions twice over — unresolved contradiction, worth a BOM/FCC check before assuming either way**

## Sources

- [Miko 3 AI Robot – The Ultimate Educational Partner for Kids](https://miko.ai/products/miko-3) (official spec sheet)
- [Miko 3](https://miko.ai/products/miko-3-2) (official, alternate/older page)
- [Miko Max: One Plan for Unlimited Playful Learning](https://miko.ai/products/miko-max)
- [Miko - Play, Learn, & Connect - App Store](https://apps.apple.com/us/app/miko-play-learn-connect/id1588895826)
- [Miko - Play, Learn, & Connect - Google Play](https://play.google.com/store/apps/details?id=com.miko3.app&hl=en_US)
- help.miko.ai articles (fetched via search snippets only — direct WebFetch returned HTTP 403 on this help-center host every time it was tried in this pass; a browser-based re-pull would get verbatim text): "What Are the Key Features of Miko 3?", "How Does Miko 3 Use Face Recognition?", "What is Mirror Me app on Miko 3?", "What Are Miko 3 Tech Specs?", "What Are Miko 3's Camera Specifications?", "What is the Camera app on Miko 3?", "What Is Mikonnect Video Calling on Miko 3?", "Does Miko 3 robot has Bluetooth?", "How To Use Miko 3's Buttons?", "Which Talents Are Included Without A Max Subscription in Miko 3?", "What is the Mikojis app on Miko 3?", "How can I customise settings for Miko Mini's voice experience for my child" (Miko Mini source, flagged).
- [Miko 3 Review | The Toy Insider](https://thetoyinsider.com/miko-3-ai-robot-review/)
- [Miko 3: The AI-Powered Learning Robot review — The Gadgeteer](https://the-gadgeteer.com/2025/03/27/miko-3-the-ai-powered-learning-robot-review-a-small-tablet-dressed-as-a-robot/)
- [Miko 3 Review — Serious Insights](https://www.seriousinsights.net/miko-3-review/)
- [Miko 3 compliances manual — FCC Report](https://fcc.report/FCC-ID/2AS3S-EMK301/5615932.pdf) (redirects to fccid.io, which 403'd automated fetch; PDF exists and should be pulled manually for exact laser-source-component and RF-module identification)
- [Miko 3 Quick Start Guide — manuals.plus](https://manuals.plus/m/bd90b123c90802a3609fb828c4e034c1f2793bd10ed56b98a64737ed1280e628) (binary PDF, not machine-readable via this pass's tooling — worth a manual read)
- [WO2017000775A1 / US10343287B2 — "Robot voice direction-seeking turning system and method"](https://patents.google.com/patent/US10343287) — assigned to Yutou Technology Hangzhou Co Ltd, **not Emotix/Chidakashi**; cited here only to document that it is NOT relevant, despite surfacing prominently in search.
- [USD822770S1 — "Emotive robotic creature" design patent](https://patents.google.com/patent/USD822770S1/en) — the one Emotix/Miko-attributable patent found in this pass; a design (ornamental appearance) patent, not a utility/mechanism patent, so it contributes no functional detail.
- [Miko Robotics acquires majority stake in AI chess startup, Square Off — TechCrunch](https://techcrunch.com/2022/10/06/miko-robotics-acquires-majority-stake-in-ai-chess-startup-square-off/) (context: Miko/Emotix corporate history, not a Miko 3 feature source)
- STMicroelectronics VL53L0X / VL53L5CX product pages (general ToF-sensor-family background, not confirmed as the exact part in Miko 3): [VL53L0X](https://www.st.com/en/imaging-and-photonics-solutions/vl53l0x.html), [VL53L5CX](https://www.st.com/en/imaging-and-photonics-solutions/vl53l5cx.html)

## What this pass did NOT resolve (worth a follow-up)

1. Exact camera megapixel count (5MP vs 2MP conflict across Miko's own
   help-center articles).
2. Whether "voice direction" (turn-toward-speaker) exists on Miko 3 itself,
   vs. being Miko-Mini-exclusive.
3. Exact ToF sensor part number / laser wavelength / classification
   component — the FCC filing PDF that likely contains this was located but
   not successfully parsed automatically; try a direct browser fetch of
   `fccid.io/2AS3S-EMK301` or a local `pdftotext` pass on the two FCC PDFs
   already referenced above.
4. The Bluetooth marketing-vs-manifest contradiction (§11).
5. `miko.ai/pages/ar` was found but not fetched — confirm whether a robot-
   camera AR feature exists before ruling it out entirely.
6. Whether "Mikojis" and "Mirror Me" are two names for the same underlying
   AIDL expression mechanism or genuinely separate code paths.
