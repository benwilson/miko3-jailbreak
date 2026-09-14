# Visual person-following / person-tracking — ServiceExam (`com.example.root.serviceexam`)

Scope: does the robot visually detect and follow/track a person by moving its
wheels, as distinct from audio-based "turn toward voice" (covered elsewhere)?
Source: jadx decompile of `tools/serviceexam.apk` → `tools/serviceexam_jadx/sources/`
(pre-decompiled, not re-run this pass — see the repo's top-level note). All
paths below are relative to that `sources/` root unless stated otherwise.
Cross-references: `docs/hardware/camera-vision.md` (MikoPlus's own,
confirmed-separate, face-only pipeline), `docs/hardware/motors-wheels.md`
(sibling agent's independent, already-CONFIRMED finding that **no app
constructs a real motor/motion command anywhere in the 4 decompiled apps**),
`docs/hardware/feature-inventory.md` (MikoPlus catalog, `automode/` flagged
there as "not deep-dived" — resolved in §4 below).

## Bottom line — CONFIRMED (negative result)

**No visual person-following/tracking-to-motor pipeline is reachable in the
decompiled code.** All three layers a real "follow me" feature would need
exist in ServiceExam in some form, but none of them connect to the next one:

| Layer | What exists | Is it wired up? |
|---|---|---|
| **Detect** — a general object detector with a "person" class | `com.miko.objectDetection.YOLOv4` (native, ncnn, `libobjectDetection.so`) + `PERSON_THRESHOLD`/`ALLOWED_LABELS_MAP` in `com.vision.vision` | **No** — the only method that calls `YOLOv4.init()` (`vision.initModels()`) has **zero call sites** anywhere in the decompiled tree (CONFIRMED by grep). Never invoked. |
| **Track/decide** — turn a detection into a movement error signal | Per-frame tracking state fields in `com.vision.vision`: `start_tracking`, `faceTracking`, `prev_centroidx`/`prev_centroidy`, `prev_corners`, `prevP`, `errorx`, `errory`, `isUserInFrame`, `personDetectedTime`; also an imported-but-unused `com.vision.tracking.MultiBoxTracker` | **No** — every one of these fields is only ever *declared* and *reset to zero* (in `stopCamera()`); none is ever computed or read. `MultiBoxTracker`'s constructor has zero call sites. CONFIRMED by grep across the whole tree. |
| **Actuate** — turn a decision into a wheel command | `MOTION_MESSAGE` (id 4) / `MOTION2_MESSAGE` (id 21) in the Arya serial protocol's `commandInterface` | **No** — per `docs/hardware/motors-wheels.md` (already independently confirmed): these command types are defined but **never constructed with real payload data anywhere in ServiceExam, the launcher, or MikoPlus.** |

The one place camera-driven detection *does* reach a live behavior is purely
cosmetic: `com.common_source.emotix.statemachine.MikoStateMachine`'s
`AutoMode` idle loop plays a **facial/animation expression**
(`AutoModeExpressions.FaceDetected` / `FaceRecognized`, rendered via
`parseAIMLexpression`) when a face-recognition event fires — see §4. It never
touches a motor, wheel, pan, tilt, or serial-motion API.

**Two readings are equally consistent with this evidence** (same caveat
`motors-wheels.md` already raises about the hardware as a whole): (1) this is
inherited/dead code from a shared "Arya" vision+motion SDK used across
multiple Miko hardware generations, some of which may have had wheels/pan-tilt
and a real follow feature, and this SKU's software never finished wiring it
up; or (2) the caller lives in `/system/app/game_robot_maker.apk` (46.8 MB,
flagged but not opened by `motors-wheels.md`), which was out of scope for
this pass. Given the user's report that the physical unit does move, **do not
treat "the feature doesn't exist" as settled** — treat it as "not present in
the code surfaces analyzed so far." See Open questions.

## 1. The object detector that exists — CONFIRMED

`com.miko.objectDetection.YOLOv4` (`com/miko/objectDetection/YOLOv4.java`):

```java
package com.miko.objectDetection;
public class YOLOv4 {
    public static native Box[] detect(Bitmap bitmap, double d, double d2);
    public static native void init(AssetManager assetManager, int i);
    static { System.loadLibrary("objectDetection"); }
}
```

Backing native lib: `lib/arm64-v8a/libobjectDetection.so` (4.08 MB). `nm -D`
on the extracted `.so` shows the JNI exports and confirms the inference
engine:

```
Java_com_miko_objectDetection_YOLOv4_detect
Java_com_miko_objectDetection_YOLOv4_init
Java_com_miko_objectDetection_NanoDet_detect
Java_com_miko_objectDetection_NanoDet_init
```

and mangled C++ symbols referencing `ncnn::Mat`/`yolocv::YoloSize` (e.g.
`_ZN6YoloV412decode_inferERN4ncnn3MatERKN6yolocv8YoloSizeEiif`) — **CONFIRMED:
this is Tencent's `ncnn` mobile inference framework**, not TFLite/ONNX
Runtime, despite `libtensorflowlite*.so` and `libonnxruntime.so` also being
bundled in the same APK for the (separate) face and wake-word pipelines.
`strings` on the `.so` also lists four candidate bundled-model names as
literals: `yolo-fastest-opt.{param,bin}`, `yolov4-tiny-opt.{param,bin}`,
`MobileNetV2-YOLOv3-Nano-coco.{param,bin}`, `nanodet_m.{param,bin}` —
matching `vision.YOLOV4_TINY`/`YOLO_FASTEST_XL`/`MOBILENETV2_YOLOV3_NANO`/
`NANODET` int constants used as the `init(assets, id)` selector.

**Only one of those four model pairs is actually bundled in the APK:**
```
$ unzip -l tools/serviceexam.apk | grep -iE "\.(bin|param)$"
   687260  assets/yolo-fastest-opt.bin
    17464  assets/yolo-fastest-opt.param
```
`yolov4-tiny-opt.*`, `MobileNetV2-YOLOv3-Nano-coco.*`, `nanodet_m.*` are
**not present in `assets/`** — so 3 of the 4 selectable detector configs
would fail to load if ever selected (INFERRED: `AAssetManager_open` on a
missing asset returns null and the native `init()` would either no-op or
crash, not traced further into the `.so`'s error handling).

`Box.java` (`com/miko/objectDetection/Box.java`) confirms the label set is
the **standard 80-class COCO list**, `person` at index 0:
```java
private static String[] labels = {"person", "bicycle", "car", "motorcycle",
  "airplane", "bus", "train", "truck", "boat", ... /* 80 total, COCO order */};
```
i.e. **this is a generic COCO object detector, not a person-specific model**
— "follow the person" would need the caller to filter `Box.getLabel().equals("person")`
itself; the detector has no person-only mode.

`com.vision.vision` (the ServiceExam-side singleton that owns the whole
camera pipeline — see camera-vision.md's MikoPlus write-up for the parallel
class there) declares the plumbing to use this detector, gated by
confidence thresholds specifically naming people/cats/dogs:

```java
// com/vision/vision.java:216-224 (static init)
BASE_THRESHOLD = 0.5f;
NMS_THRESHOLD = 0.7f;
PERSON_THRESHOLD = 0.7f;
CAT_THRESHOLD = 0.7f;
DOG_THRESHOLD = 0.7f;
MAX_DETECTIONS_COUNT = 10;
...
USE_MODEL = BLAZEFACE_SHORT_RANGE_128_DETECTOR; // = 5 -- NOT one of the 4 YOLOv4/NanoDet ids

// com/vision/vision.java:302-324
public void initModels() {
    this.ALLOWED_LABELS_MAP.put("person", Float.valueOf(PERSON_THRESHOLD));
    if (USE_MODEL == YOLOV4_TINY) { YOLOv4.init(MyService.context.getAssets(), 0); ... }
    else if (USE_MODEL == MOBILENETV2_YOLOV3_NANO) { YOLOv4.init(MyService.context.getAssets(), 1); ... }
    else if (USE_MODEL == YOLO_FASTEST_XL) { YOLOv4.init(MyService.context.getAssets(), 2); ... }
    else if (USE_MODEL == NANODET) { /* NanoDet path, no init call shown here */ }
}
```

**CONFIRMED dead code:** `USE_MODEL` is a `static` field set once, at class
load, to `BLAZEFACE_SHORT_RANGE_128_DETECTOR` (5) — a value `initModels()`'s
`if`/`else-if` chain never matches — and nothing else in the entire
decompiled tree ever assigns `vision.USE_MODEL` a different value (grep
confirmed zero other assignment sites). Independently, `initModels()` itself
is **never called** from anywhere (grep confirmed zero call sites for
`initModels(` besides its own declaration). So even ignoring the `USE_MODEL`
mismatch, the method that would initialize the ncnn object detector simply
never runs. `ALLOWED_LABELS_MAP` — the structure that would let a caller ask
"is this detection a person above threshold?" — is populated inside that same
dead method, so it's always empty at runtime too.

## 2. Which camera modes are actually reachable — CONFIRMED

`vision.Camera_Mode` has five declared values: `IDLE`, `CAPTURE_USER_FACE`,
`RUN_FACE_RECOGNITION`, `RUN_FACE_SESSION`, `RUN_FACE_SESSION_END` — **all
enrollment/recognition-of-faces states, no object-detection or "follow" state
exists.** `setCameraMode(int)` only branches on the first three:

```java
// com/vision/vision.java:426-449
public void setCameraMode(int i) {
    if (i == IDLE) { ...; stopCamera(); }
    else if (i == CAPTURE_USER_FACE) { ...; startCamera(); }
    else if (i == RUN_FACE_RECOGNITION) { ...; startCamera(); }
}
```
and the per-frame `ImageReader.OnImageAvailableListener` (`AnonymousClass5.onImageAvailable`,
lines 568-638) only has code paths for `Camera_Mode == IDLE`,
`CAPTURE_USER_FACE`, and `RUN_FACE_RECOGNITION` — `RUN_FACE_RECOGNITION`'s
branch calls `vision.this.processImage()`, whose only inference call is
`this.f4recognizer.recognizeImage(...)` (face detect→embed→classify, the same
BlazeFace→MobileFaceNet→EuclidDistClassifier chain camera-vision.md already
traced for MikoPlus — see §9 added there this pass). **No branch ever calls
`YOLOv4.detect(...)`.**

Entry points into `setCameraMode` are equally narrow. `IPC_AIDL.java` (the
AIDL surface MikoPlus/the app layer calls into) has a `sendCommand(String)`
handler covering exactly these string commands:

```java
// com/example/root/serviceexam/IPC_AIDL.java:87-130 (condensed)
if (str.startsWith("START_IMAGE_CAPTURE")) { MyService.setCameraMode(vision.CAPTURE_USER_FACE); }
else if (str.startsWith("STOP_IMAGE_CAPTURE"))  { MyService.setCameraMode(vision.IDLE); }
else if (str.startsWith("SEND_CAPTURED_FACE"))  { MyService.sendCapturedImage(); }
else if (str.startsWith("TRAIN_IMAGES"))        { MyService.train_images(); }
else if (str.startsWith("DELETE_IMAGES"))       { MyService.delete_images(); }
else if (str.startsWith("SAVE_IMAGE_AND_TRAIN")){ MyService.saveImageandTrain(); }
else if (str.startsWith("SAVE_IMAGE"))          { MyService.saveImage(); }
else if (str.startsWith("START_FACE_RECO"))     { MyService.setCameraMode(vision.RUN_FACE_RECOGNITION); }
else if (str.startsWith("STOP_FACE_RECO"))      { MyService.setCameraMode(vision.IDLE); }
else if (str.startsWith("START_IMU_CALIBRATION")) { MyService.startIMUCalibration(); }
else if (str.equals("START_AUTO_MODE"))         { MyService.startAutoMode(); }
else if (str.equals("END_AUTO_MODE"))           { MyService.endAutoMode(); }
```
No `"START_OBJECT_DETECTION"`/`"FOLLOW_ME"`/`"TRACK_PERSON"`-style command
exists anywhere in this switch or anywhere else in the decompiled tree
(confirmed by a case-insensitive `follow`/`track` sweep across the whole
`sources/` tree, excluding third-party library noise — zero feature-relevant
hits).

## 3. The vestigial per-frame tracking scaffold — CONFIRMED present, CONFIRMED unused

`com.vision.vision` carries a full set of fields that look exactly like what
a classic optical-flow/centroid-error visual-servo tracker would need —
naming strongly suggests a Lucas-Kanade-style corner tracker (`prev_corners`,
`prevP` as a `Vector[]`, `radius[]`) feeding a proportional error signal
(`errorx`/`errory`) the way a pan-tilt or wheel-steering control loop would
consume:

```java
// com/vision/vision.java:128-141
int isUserInFrame = 0;
boolean start_tracking = false;
double[] prev_centroidx = new double[2];
double[] prev_centroidy = new double[2];
Mat[] prev_corners = new Mat[2];
Vector[] prevP = new Vector[2];
int[] radius = new int[2];
double errorx = 0.0d;
double errory = 0.0d;
int[] originalCorners = new int[2];
String facelabel = "";
int faceTracking = 0;
```

**CONFIRMED: every one of these is write-only-zero.** The *only* place any of
them is ever touched again in the entire decompiled tree is `stopCamera()`,
which just resets them back to empty/zero when the camera session ends:

```java
// com/vision/vision.java:384-394 (inside stopCamera()'s Runnable)
vision.this.prev_centroidx = new double[2];
vision.this.prev_centroidy = new double[2];
vision.this.prev_image = new Mat();
vision.this.prev_corners = new Mat[2];
vision.this.prevP = new Vector[2];
vision.this.radius = new int[2];
vision.this.errorx = 0.0d;
vision.this.errory = 0.0d;
vision.this.originalCorners = new int[2];
vision.this.facelabel = "";
vision.this.faceTracking = 0;
```
No optical-flow call (`Video.calcOpticalFlowPyrLK` or similar OpenCV API,
which is bundled — `libopencv_java3.so` is linked) or any other computation
ever *writes* a non-zero value into `errorx`/`errory`/`prev_centroidx`/`prev_centroidy`/
`faceTracking`/`start_tracking`/`isUserInFrame`/`personDetectedTime` anywhere
in the decompiled source (grep-confirmed, whole-tree).

Same story for the imported `com.vision.tracking.MultiBoxTracker` (`tracker`
field, line 126) — this is the box-smoothing tracker class from Google's
public "TF Detect" TensorFlow-Android object-detection demo (recognizable by
its API shape; not reproduced here since it's unused). Its constructor
(`MultiBoxTracker(Context)`) has **zero call sites** anywhere — `tracker` is
declared and never assigned, so it's `null` for the object's whole lifetime.

Separately, `com.vision.Align.getDistance(float[])` computes a rough
face-to-camera distance estimate from interocular pixel spacing (a common
technique — bigger apparent eye spacing means closer face):
```java
// com/vision/Align.java:16-18
public static double getDistance(float[] fArr) {
    return ((double) 22050.0f) / calculateDistance(fArr[0], fArr[1], fArr[2], fArr[3]);
}
```
This is exactly the kind of primitive a real "stay N cm from the person while
tracking" loop would use — and it too is **never called anywhere** (grep
confirmed, whole tree). Another disconnected primitive.

A third, entirely separate and also-dead face pipeline exists at
`com.example.root.vision.PersonRecognizer`/`FaceDetect` — classic OpenCV
`CascadeClassifier` (Haar cascade, `/sdcard/klug/vision/haarcascade_frontalface_alt2.xml`)
plus `org.opencv.face.FisherFaceRecognizer` (`facerec_fisher.model`). `vision`
declares a `FaceDetect dd` field and forwards `trainFace()` to it
(`this.dd.trainFace()`, line 482), but `dd` is **never assigned**
(`new FaceDetect(...)` has zero call sites in `vision.java`) — that call path
would NPE if ever exercised. `PersonRecognizer` itself is referenced by
nothing outside its own file and `FaceDetect.java`'s unused `p` field — fully
dead. Not part of the tracking story, but worth flagging so it isn't
mistaken for a live "person recognition" surface: it is not.

## 4. What camera detection *does* trigger — CONFIRMED: an animation, not a movement

`MyService.startAutoMode()`/`endAutoMode()` (the handlers behind the AIDL
`START_AUTO_MODE`/`END_AUTO_MODE` commands, §2) forward into
`SocialInteraction_SpeechChat`:
```java
// com/common_source/emotix/interaction/interaction/SocialInteraction_SpeechChat.java:1149-1154
public void startAutoMode() { this.statemachine.startAutoMode(); }
public void endAutoMode()   { this.statemachine.endAutoModeEvent("Regular"); }
```
which runs `MikoStateMachine`'s idle/ambient behavior loop (this is the class
behind MikoPlus's `automode/AutoModeFragment`, flagged as "not deep-dived" in
`feature-inventory.md` — resolved here from the ServiceExam side).
`MikoStateMachine` tracks `lastVisionTime`/`visionCount` and reacts to
face-detection/recognition results by **playing a pre-authored expression**,
nothing else:

```java
// com/common_source/emotix/statemachine/MikoStateMachine.java:554-564 (condensed)
} else if (this.autoModeExpressionState == 16) {
    MikoStateMachine.lastVisionTime = SystemClock.uptimeMillis();
    String expression8 = AutoModeExpressions.getExpression(AutoModeExpressions.FaceDetected);
    MikoStateMachine.uinterface.parseAIMLexpression(expression8, true);
} else if (this.autoModeExpressionState == 15) {
    MikoStateMachine.lastVisionTime = SystemClock.uptimeMillis();
    String expression9 = AutoModeExpressions.getExpression(AutoModeExpressions.FaceRecognized);
    MikoStateMachine.uinterface.parseAIMLexpression(expression9, true);
}
```
`parseAIMLexpression` drives the Unity-rendered face/animation layer (the
same expression-playback surface used for scripted dialogue reactions
elsewhere in this class) — **not** a motor, pan/tilt, or serial-motion API.
Grep of the entire `MikoStateMachine.java` (5900+ lines) for
`MOTION|moveRobot|wheel|Wheel|drive(|GameEvent(|GameControllerAIDL` returns
zero hits: this state machine never issues a movement command of any kind.

## 5. Connecting to the motor layer — CONFIRMED absent (cross-referenced)

Even in the counterfactual where §1-§3's detector/tracker were wired up, the
receiving end doesn't exist either. Per `docs/hardware/motors-wheels.md`
(sibling doc, independently derived, already CONFIRMED — summarized here,
not re-derived): the physical link out of ServiceExam is a fixed-frame
2 Mbps UART protocol (`/dev/ttyS2` for this unit's `M3Q`-prefixed serial) with
a `commandInterface` command-type table that includes `MOTION_MESSAGE` (id 4)
and `MOTION2_MESSAGE` (id 21) — but **no code anywhere in ServiceExam, the
launcher, or MikoPlus ever constructs a framed message of either type with
real payload data.** The only traffic actually sent over that link is
bootloader-flash raw writes and a literal `"SHTDN"` shutdown tag. So: even a
fully-computed `errorx`/`errory` in `vision.java` would currently have
nowhere real to go.

## Open questions

- **`game_robot_maker.apk`** (`/system/app/`, 46.8 MB, found by the
  firmware-image pass, flagged in both `feature-inventory.md` and
  `motors-wheels.md`) — not opened by this pass. Given its name, it is the
  single highest-value next step for confirming/denying whether a real
  person-following implementation exists in a component this doc didn't
  cover.
- Whether any of `YOLOv4`/`NanoDet`'s native detection code is reachable via
  a path this pass didn't find (e.g. reflection, a different privileged
  caller process talking to ServiceExam over a Binder/socket interface not
  grepped for) — not found, but native-only reachability (JNI called from
  outside the Java call graph entirely) can't be fully ruled out by static
  Java-source grep alone.
- Whether the `game_robot_maker.apk`/a different Miko hardware SKU is where
  `MOTION_MESSAGE`/`errorx`/`YOLOv4.init()` all *do* connect, making this
  ServiceExam build a "same firmware family, wheels-disabled" variant rather
  than genuinely broken code — INFERRED as plausible, not confirmed.
- Exact semantics of `Recognizer.FACE_DETECTOR`/model-selection interplay
  between `com.vision.Recognizer` (ServiceExam's own, distinct copy) and
  `USE_MODEL` in `vision.java` — both exist, only loosely cross-referenced in
  this pass (see camera-vision.md §9 for the face-pipeline side).
- Live confirmation was explicitly out of scope for this pass
  (documentation-only, no device interaction) — a `logcat`/`getevent` capture
  while the physical unit is observed moving toward a person, if reproducible,
  would be the fastest way to settle the open question above one way or the
  other.

## Implementation — what a custom launcher can and can't reuse

**What's directly reusable (real, working API surface):**

```java
// 1. Reuse the stock ncnn object detector as-is.
//    Requires: lib/arm64-v8a/libobjectDetection.so + assets/yolo-fastest-opt.{param,bin}
//    pulled from tools/serviceexam.apk (these three files are a matched,
//    load-bearing set -- the other three model pairs referenced in the .so's
//    strings are NOT bundled and will not load).
package com.miko.objectDetection; // reuse this exact package/class name --
                                   // the JNI symbols are pre-compiled against it
public class YOLOv4 {
    public static native Box[] detect(Bitmap bitmap, double confThresh, double nmsThresh);
    public static native void init(AssetManager assetManager, int modelId); // modelId = 2 for yolo-fastest-opt
    static { System.loadLibrary("objectDetection"); }
}
// AssetManager must resolve "yolo-fastest-opt.param"/".bin" via assets/ (copy them
// into your own app's assets/, same filenames -- the native loader hardcodes them).

// 2. Filter detections to "person" yourself -- the detector has no person-only mode.
Box[] boxes = YOLOv4.detect(frameBitmap, /*conf*/ 0.5, /*nms*/ 0.7);
for (Box b : boxes) {
    if (b.getLabel().equals("person") && b.getScore() >= 0.7f) {
        RectF box = b.getRect(); // pixel-space bounding box in the frame you passed in
        // ... your own tracking/decision logic goes here -- see below ...
    }
}
```

**What is NOT reusable — no real implementation exists to copy.** The
centroid/error computation and the motor command are both things you'd have
to design yourself; the field names in `vision.java` (`errorx`/`errory`,
`prev_centroidx`/`prev_centroidy`) are a strong hint at the *shape* of a
reasonable design, but **do not treat the sketch below as verbatim-recovered
code — it is a reconstruction from field names and comments, offered only as
a starting point**, clearly distinguished from the CONFIRMED excerpts above:

```java
// INFERRED / reconstructed control-loop shape -- NOT decompiled code.
// A plausible fill-in for what vision.java's errorx/errory fields suggest
// was intended, using only primitives this pass confirmed exist (person
// detection above, Align.getDistance() for a rough range estimate):
RectF box = /* best "person" detection this frame, from YOLOv4.detect() above */;
float frameCenterX = frameWidth / 2f;
float boxCenterX = (box.left + box.right) / 2f;
double errorx = (boxCenterX - frameCenterX) / frameWidth; // [-0.5, 0.5], negative = person left of center

// Turn errorx into a differential wheel command. THIS PART HAS NO PRECEDENT
// IN THE DECOMPILED CODE AT ALL -- docs/hardware/motors-wheels.md confirms
// no MOTION_MESSAGE payload was ever observed, so the byte layout below is
// a placeholder shape only (100-byte frame, StartTag/AckMessage/EndTag
// framing per motors-wheels.md), not a working command:
//   frame = StartTag(0x3C) + AckMessage(type=TYPE_PHONE, id=MOTION_MESSAGE) + payload + EndTag(0x3E)
//   payload = <your own encoding of turn-rate/forward-speed from errorx>  // UNKNOWN byte layout
// Until that payload layout is recovered (e.g. from game_robot_maker.apk,
// or a live serial capture while the physical unit moves), there is no
// confirmed way to make a detected person actually move the wheels.
```

For face enrollment/recognition (the one vision.java feature that *is* fully
wired end-to-end), reuse `docs/hardware/camera-vision.md`'s Implementation
section — ServiceExam's copy of that pipeline is functionally identical (same
model files, same `/sdcard/klug/vision/` paths, same `FACE_THRESHOLD = 0.8f`)
per the new §9 added there this pass.
