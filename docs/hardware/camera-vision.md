# Camera / vision pipeline — MikoPlus (`com.miko.mikoplus`)

Deep dive spun out of `docs/hardware/feature-inventory.md`. Source: jadx
decompile of `tools/base.apk` (`sources/com/miko/mikoplus/activity/appui/childProfile/**`
and `sources/com/miko/mikonnect/**`). All file paths below are relative to that
`sources/` root unless stated otherwise.

## 1. Camera API — CONFIRMED: Camera2, not Camera1/CameraX

`com/miko/mikoplus/activity/appui/childProfile/view/VisionFaceTrain.java`
(1449 lines) opens the camera directly with `android.hardware.camera2`:

```java
// VisionFaceTrain.java:693-745 (condensed)
private void openCamera() {
    CameraManager cameraManager = (CameraManager) this.context.getSystemService("camera");
    String str = cameraManager.getCameraIdList()[0];      // first camera, no facing check
    Size size = new Size(640, 480);
    this.previewSize = size;
    this.previewWidth = size.getWidth();
    this.previewHeight = size.getHeight();
    this.rgbBytes = new int[this.previewWidth * this.previewHeight];
    this.tracker = new MultiBoxTracker(this.context);
    this.sensorOrientation = 0;
    rgbFrameBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888);
    // CROP_WIDTH/HEIGHT set from the active face-detector's expected input size (128x128 for BlazeFace short-range)
    this.croppedBitmap = Bitmap.createBitmap(CROP_WIDTH, CROP_HEIGHT, Bitmap.Config.ARGB_8888);
    this.frameToCropTransform = ImageUtils.getTransformationMatrix(
        previewWidth, previewHeight, CROP_WIDTH, CROP_HEIGHT, sensorOrientation, false);
    if (ActivityCompat.checkSelfPermission(context, "android.permission.CAMERA") != 0) return;
    cameraManager.openCamera(str, this.stateCallback, backgroundHandler);
}
```

Capture-session setup (`createCameraPreviewSession()`, line 770) is notable
for **manual exposure control read from a JSON file on external storage**,
not from any Android UI:

```java
// VisionFaceTrain.java:774-789 (condensed)
String dataFromJsonFile = FileUtils.getInstance(context).getDataFromJsonFile(
    new File(Environment.getExternalStorageDirectory(), "/klug/APPS/Camera/cameraApp.json"));
CameraModel cameraModel = new Gson().fromJson(dataFromJsonFile, CameraModel.class);
previewRequestBuilder = cameraDevice.createCaptureRequest(1); // TEMPLATE_PREVIEW
if (cameraModel != null && cameraModel.getCameraParams() != null) {
    sensitivity = Integer.parseInt(cameraModel.getCameraParams().getSensitivity());
    exposureTime = Long.parseLong(cameraModel.getCameraParams().getExposureTime());
    frameDuration = Long.parseLong(cameraModel.getCameraParams().getFrameDuration());
    previewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, 0);           // manual AE
    previewRequestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureTime);
    previewRequestBuilder.set(CaptureRequest.SENSOR_FRAME_DURATION, frameDuration);
    previewRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, sensitivity);
} else {
    previewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, 1);           // fallback: auto AE
}
```

**CONFIRMED:** `/klug/APPS/Camera/cameraApp.json` on external storage is a
device-specific camera-tuning file (exposure/sensitivity/frame-duration)
that overrides Android's auto-exposure. This is a useful artifact to look
for on-device if you want to replicate stock image quality — it's outside
the APK, presumably provisioned at manufacture/update time (same `/klug/`
tree the app also stores its face-recognition models and per-user data in;
see §2).

Preview frames come back through `ImageReader.newInstance(w, h, ImageFormat.YUV_420_888 /* 35 */, 1)`
with `setOnImageAvailableListener`, i.e. YUV_420_888 planar frames converted
to an RGB `int[]`/`Bitmap` in app code (`ImageUtils`) before being handed to
the detector — standard Camera2 + `ImageUtils.convertYUV420ToARGB8888`-style
pipeline, not `ImageAnalysis`/CameraX.

## 2. Face detection → landmark → embedding → match pipeline — CONFIRMED

All in `com/miko/mikoplus/activity/appui/childProfile/facetraining/`:

- `Recognizer.java` — orchestrator/singleton (`Recognizer.getInstance(...)`).
- `ml/BlazeFace.java` — face **detector**, native JNI (`System.loadLibrary("faceDetection")` → `libfaceDetection.so`), loads `face_detection_front.tflite` (short-range, 128×128 input) or `face_detection_back.tflite` (long-range, 256×256 — bundled name only, **not present** in `assets/`, so the long-range path would crash if selected).
- `ml/Yolov5.java` — **alternate** face detector, same native lib (`libfaceDetection.so`), 320×240 input, also native `detect()`/`init()`; selectable via `Recognizer.FACE_DETECTOR = YOLOV5N_DETECTOR` but not the default.
- `ml/FaceMesh.java` — landmark model, TFLite Java API, `face_landmark_192x192.tflite`, 192×192 input, outputs 468×2 = 936 floats (`facemesh_landmarks_2D = new float[936]`) plus an 18-float head-pose vector.
- `ml/MobileFaceNet.java` — embedding model, TFLite Java API, `MobileFaceNet.tflite`, 112×112 input → 192-dim L2-normalized float embedding.
- `ml/EuclidDistClassifier.java` and `ml/LibSVM.java` — two interchangeable **classifiers** over the embedding (`Recognizer.CLASSIFIER_TYPE`); Euclidean is a simple best-match-by-score loop (see excerpt below), SVM is a bundled libsvm model.
- `Recognizer.getInstance()` (lines 180-233) wires this up at app start:

```java
// Recognizer.java:186-216 (condensed)
if (FACE_DETECTOR == BLAZEFACE_SHORT_RANGE_128_DETECTOR || FACE_DETECTOR == BLAZEFACE_LONG_RANGE_256_DETECTOR) {
    recognizer.blazeFace = BlazeFace.create(assetManager);
    landmarkPointsCount = 12;
    recognizer.increaseBoxSize = 0.15d;
} else if (FACE_DETECTOR == YOLOV5N_DETECTOR) {
    recognizer3.yolov5 = Yolov5.create(assetManager);
    landmarkPointsCount = Yolov5.NUM_OF_LANDMARK_POINTS;
    recognizer.increaseBoxSize = 0.05d;
}
if (CLASSIFIER_TYPE == SVM_CLASSIFIER) {
    recognizer.svm = LibSVM.getInstance();
} else if (CLASSIFIER_TYPE == EUCLIDEAN_DIST_CLASSIFIER) {
    recognizer.euclidDistClassifier = EuclidDistClassifier.getInstance();
}
recognizer.mobileFaceNet = MobileFaceNet.create(assetManager);
recognizer.faceMesh = FaceMesh.create(assetManager);
recognizer.classNames = FileUtils.verifyFaceTraining() ? FileUtils.readLabel(FileUtils.LABEL_FILE) : new ArrayList<>();
```

**CONFIRMED — models are NOT read from the APK's `assets/` at inference time.**
`MobileFaceNet.loadModelFile()` and `FaceMesh`'s equivalent hardcode absolute
paths on external storage:

```java
// MobileFaceNet.java:62-67
public static MappedByteBuffer loadModelFile() throws IOException {
    FileChannel channel = new FileInputStream(new File("/sdcard/klug/vision/MobileFaceNet.tflite")).getChannel();
    return channel.map(FileChannel.MapMode.READ_ONLY, 0L, channel.size());
}
```
(`FaceMesh` does the identical thing against `/sdcard/klug/vision/face_landmark_192x192.tflite`.)
So on first run the app must copy the bundled `assets/*.tflite` out to
`/sdcard/klug/vision/` — the `AssetManager`-based `loadModelFile(AssetManager, String)` overload exists but is dead code for these two classes. `BlazeFace`/`Yolov5` go through native `init(AssetManager, ...)` instead (asset path stays inside JNI). **Open question:** the exact copy-out call site wasn't located this pass.

**GPU delegate — CONFIRMED**, `MobileFaceNet.create()`:
```java
// MobileFaceNet.java:43-56
Interpreter.Options options = new Interpreter.Options();
if (ENABLE_GPU) {   // true by default
    GpuDelegate.Options best = new CompatibilityList().getBestOptionsForThisDevice();
    best.setSerializationParams("/storage/emulated/0/klug/model_serialization_dir", "2");
    options.addDelegate(new GpuDelegate(best));
}
options.setNumThreads(NUM_OF_THREADS);
mobileFaceNet.interpreter = new Interpreter(loadModelFile(), options);
```
This uses `libtensorflowlite_gpu_jni.so` via the standard `org.tensorflow.lite.gpu.GpuDelegate` + `CompatibilityList` pattern, with delegate **serialization caching** to disk (`/storage/emulated/0/klug/model_serialization_dir`) so GPU compilation isn't repeated every launch — worth replicating for cold-start latency on the same MT8167 GPU.

**Matching — CONFIRMED**, `EuclidDistClassifier.predict()` is an unweighted
best-match scan (score, not distance, despite the class name — `evaluate()`
returns a similarity that's maximized, not a raw L2 distance minimized):
```java
// EuclidDistClassifier.java:133-149
public Prediction predict(float[] embedding) {
    int bestIndex = -1;
    float best = classifierData.isEmpty() ? 0f : classifierData.get(0).getIndex();
    for (ClassifierData c : classifierData) {
        float score = evaluate(embedding, c.getEmbeddingVector());
        if (score >= best) { bestIndex = c.getIndex(); best = score; }
    }
    return new Prediction(bestIndex, best);
}
```
There's no confirmed global rejection threshold in the excerpt read (i.e. it
always returns *some* best match even if nothing enrolled is close) — flagged
as an open question; a real deployment likely thresholds `best` in the caller
(`Recognizer.recognizeImage`, not fully traced) before accepting the match.

### Enrollment flow (GIF-driven wizard) — INFERRED (order/branching not fully traced)

Assets `assets/ft0.gif`…`ft3.gif`, `ft_camra_slider.gif`, `ft_faceinframe.gif`,
`ft_moveyourface.gif`, `ft_remvglasses.gif`, `ft_lowlight.gif`,
`ft_facetraindone.gif` clearly back a step-by-step "look at camera → move your
face → remove glasses if worn → handle low light → done" capture wizard
launched from `childProfile/view/ChildProfileHome.java` into
`VisionFaceTrain`. The GIF filenames are not referenced as literal strings
anywhere in `com/miko/mikoplus` (they're presumably wired through
`R.drawable`/layout XML resolved at a resource ID, or through a JSON-driven
step config like the camera tuning file above) — exact trigger conditions
for the low-light/glasses prompts were **not** located this pass. Enrolled
data is written under `FileUtils.LABEL_FILE` (class-name list) plus
per-class embedding vectors consumed by `EuclidDistClassifier`/`LibSVM` —
exact on-disk format not traced.

## 3. Face-tracking / "follow me" / actuator control — UNKNOWN, likely absent in this app

No hit for `follow`, `eyeTrack`, `pan`, `tilt`, `servo`, or `motor` tied to
face/camera code in `com/miko/mikoplus`. The only actuator-adjacent surface
found anywhere is the generic AIDL calls into ServiceExam
(`GameControllerAIDL.GameEvent(String)`, `TouchEventAIDL.touchEvent(String)`,
`ExpressionEventAIDL.expressionEvent(String)` — see feature-inventory.md).
It's plausible detected-face position is packaged into one of those opaque
`String` payloads and physical head/eye tracking happens on the ServiceExam
side (or in `libmiko_drivers.so`/`libserial_port.so`, out of scope here), but
**no such call site was found in MikoPlus itself.** Flagged for the
driver-layer analysis to confirm/deny from the other side of the AIDL
boundary.

**Update (ServiceExam-side pass, see `docs/hardware/person-tracking.md`):**
resolved from the other side of the boundary — ServiceExam does have a
general "person" object detector (`com.miko.objectDetection.YOLOv4`, ncnn)
and vestigial per-frame tracking-error fields (`errorx`/`errory`,
`prev_centroidx`/`prev_centroidy`, etc. in `com.vision.vision`), but neither
is ever wired to a live camera mode, and no code anywhere constructs a real
motor/motion command (`docs/hardware/motors-wheels.md`, independently
confirmed). **No visual person-following pipeline was found reachable in
either app.** See `person-tracking.md` for the full trace.

## 4. Laser — CONFIRMED absent

`grep -ri laser` across the entire decompiled source tree (`sources/com/**`,
14.8k files) returns **zero matches**. There is no laser-related vision
feature in this app.

## 5. OpenCV usage — CONFIRMED, narrow

`org.opencv.*` imports appear in exactly two files:
`VisionFaceTrain.java` and the generated `PhotoFragmentBinding.java` (a
databinding artifact, not real usage). OpenCV is used inside the face-training
capture flow for image-processing helpers (bitmap manipulation alongside the
Camera2 preview) rather than as an independent CV pipeline (no
`CascadeClassifier`/Haar-cascade usage found — face detection is entirely the
TFLite/native `libfaceDetection.so` path above, not OpenCV's classic
detectors).

## 6. WebRTC / LiveKit video calling — CONFIRMED, full call sequence recovered

`com.miko.mikonnect` (separate module bundled in the same APK) implements
parent↔robot video calling with **two selectable backends**: LiveKit
(primary, fully traced below) and Agora (`AgoraConfig.java`,
`IRtcEngineCallback.java` present, **not traced** — flagged as open question).

`LiveKitService$callService$1$joinCall$1.invokeSuspend()` (Kotlin coroutine,
decompiled) is the actual join sequence:

```java
// LiveKitService$callService$1$joinCall$1.java:92-121 (condensed, real call sequence)
PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions());
eglBase = EglBase.create();
DefaultVideoDecoderFactory decoderFactory = new DefaultVideoDecoderFactory(eglBase.getEglBaseContext());
DefaultVideoEncoderFactory encoderFactory = new DefaultVideoEncoderFactory(eglBase.getEglBaseContext(), true, true);
LiveKitOverrides overrides = new LiveKitOverrides(null, encoderFactory, decoderFactory, null, null, null, 57, null);
RoomOptions roomOptions = new RoomOptions(/* e2ee off by default */ false, false, null, null, null, null, null, null, null, null, 1024, null);

this.room = LiveKit.INSTANCE.create(context, roomOptions, overrides);
collectRoomEvents();                              // subscribes to RoomEvent flow (Disconnected/Reconnecting/ParticipantDisconnected/...)
Room.connect$default(room, wsUrl, token, null, this, 4, null);   // wsUrl + JWT-style token, both server-issued

room.getLocalParticipant().setMicrophoneEnabled(true, this);

LocalVideoTrack camTrack = LocalParticipant.createVideoTrack$default(
    room.getLocalParticipant(), "camera", getCustomCapturer(),
    new LocalVideoTrackOptions(false, null, null,
        new VideoCaptureParameter(/* width */ 1280, /* height */ 720, /* fps */ 30, false, 8, null),
        7, null), null, 8, null);

LocalParticipant.publishVideoTrack$default(
    room.getLocalParticipant(), camTrack,
    new VideoTrackPublishOptions(null, new VideoEncoding(/* bitrate bps */ 1200000, /* fps */ 30), false, "vp9", null, null, null, null, null, null, 1009, null),
    null, this, 4, null);

setSpeakerOn(true);
callback.onConnected(channel, userId, 0);
startStatsTimer();
```

Key confirmed parameters: **1280×720 @ 30fps, VP9, 1.2 Mbps target
bitrate**, custom video capturer (`CustomYuvCapturer.java` — feeds camera
frames into the LiveKit/WebRTC pipeline instead of WebRTC's own camera
capturer, presumably to share the same Camera2 session used elsewhere in the
app), speakerphone forced on for calls. `wsUrl`/`token` are server-issued
(join endpoint not traced in this pass — request model exists at
`com/miko/mikonnect/data/Request4VideoCall.java`/`TokenResponse.java`).
The manifest's `io.livekit.android.room.track.screencapture.ScreenCaptureService`
(`FOREGROUND_SERVICE_MEDIA_PROJECTION`) backs screen-share within the same
LiveKit room, not investigated further.

This is used for parent↔robot video calling (the "Mikonnect" branded feature
— confirms `notification/model/BotCallModel.java`'s incoming-call tie-in).
No evidence found of a teleop/remote-motor-control channel riding the same
WebRTC data channel (`sendStreamMessage` exists but its payload schema wasn't
traced) — flagged as open question, not confirmed either way.

## 7. Other vision surfaces — CONFIRMED narrow scope

- No general object-detection/classification code found beyond the two
  face-detector variants above; `Yolov5` in this app is a **face** detector
  (facial landmarks, not COCO classes) despite the generic name.
- `libtensorflow_inference.so` / `libtensorflow_demo.so` are bundled but
  **no call site found** anywhere in `com/miko/**` — see feature-inventory.md
  open questions. INFERRED dead weight from a template/demo integration.
- No QR/barcode scanning, no SLAM/mapping, no gesture recognition found.
- "GAN Painting" (`ganPainting/GanPaintingFragment.java`) has **no**
  TensorFlow/OpenCV imports — it's an `ExoPlayerListener`-based dialog,
  almost certainly playing back a pre-rendered/server-generated video rather
  than running any generative model on-device.

## 8. `libmiko_drivers.so` / `libserial_port.so` — CONFIRMED absent from this app

Neither library name appears in `lib/arm64-v8a/` of this APK, nor as a
`System.loadLibrary(...)` argument or bare string anywhere in the decompiled
source. All hardware-actuation surfaces this app exposes go through the AIDL
interfaces documented in feature-inventory.md instead. Flag for the
driver-layer agent: if a physical eye/head-tracking-on-face-detection
behavior exists on the robot, its trigger is either inside ServiceExam
(consuming the `ExpressionEventAIDL`/`GameControllerAIDL` calls MikoPlus
makes) or entirely below the app layer — not in MikoPlus.

## 9. ServiceExam (`com.example.root.serviceexam`) duplicates this pipeline — CONFIRMED (new pass, `tools/serviceexam_jadx/`)

Added by a later pass that decompiled `tools/serviceexam.apk` (the separate
privileged process MikoPlus talks to over AIDL for everything hardware —
see `feature-inventory.md`). **This section documents ServiceExam's own,
independent copy of the face pipeline; it does not change or supersede
anything above, which is MikoPlus's (`tools/base.apk`).** Full detail on
ServiceExam's *object*-detection/tracking code (which is a materially
different, unwired story) is in `docs/hardware/person-tracking.md` — this
section is face-recognition only.

ServiceExam has its own complete BlazeFace→MobileFaceNet→classifier chain
under `com.vision`/`com.vision.ml` (`tools/serviceexam_jadx/sources/com/vision/**`),
architecturally parallel to MikoPlus's `com.miko.mikoplus...facetraining`
package traced above, with one notable difference and one notable match:

- **Match — same on-disk model paths, same threshold.** ServiceExam's
  `BlazeFace.loadModelFile()` reads from the identical hardcoded location
  MikoPlus uses:
  ```java
  // com/vision/ml/BlazeFace.java:121-126
  private static ByteBuffer loadModelFile() throws IOException {
      FileChannel channel = new FileInputStream(new File("/sdcard/klug/vision/" + MODEL_FILE)).getChannel();
      return channel.map(FileChannel.MapMode.READ_ONLY, 0L, channel.size());
  }
  ```
  (`MODEL_FILE` is `face_detection_front.tflite`/`face_detection_back.tflite`,
  same filenames as MikoPlus's §2.) `com.vision.Recognizer.FACE_THRESHOLD = 0.8f`
  — **the exact same 0.8 threshold** already documented for MikoPlus's
  classifier gate. **CONFIRMED implication: MikoPlus and ServiceExam read and
  write the same enrolled-face data on shared external storage** — a face
  enrolled via one process's flow is visible to the other's recognizer,
  because both hardcode the same `/sdcard/klug/vision/` path rather than
  using their own private app storage.
- **Difference — this copy of BlazeFace is pure Java/TFLite, not JNI.**
  Unlike MikoPlus's `BlazeFace.java` (native `detect()`/`init()` via
  `System.loadLibrary("faceDetection")`, opaque JNI math — see §"Implementation"
  above), ServiceExam's `com/vision/ml/BlazeFace.java` is a **full Java-side
  reimplementation**: anchor generation, sigmoid scoring, and weighted
  non-max-suppression are all plain Java (`GenerateAnchors()`,
  `WeightedNonMaxSuppression()`, both readable/portable in full — no
  native-library dependency for the detector itself, only for the TFLite
  interpreter runtime). It uses a plain `org.tensorflow.lite.Interpreter`
  the same way MobileFaceNet does in both apps. **This means ServiceExam's
  BlazeFace decode math, unlike MikoPlus's, doesn't require reusing
  `libfaceDetection.so` at all — it's directly portable Java.**
  Confirmed by `nm -D`/`strings` on ServiceExam's own `libfaceDetection.so`
  copy (6.03 MB): it exports no `Java_com_vision_ml_BlazeFace_*` symbols at
  all — its actual JNI surface is unrelated utility functions
  (`miko.vision.edgeAI.env.ImageUtils.{checkCameraCoverNative,checkLowLightNative,
  findLightSourceNative,increaseBrightnessNative}`,
  `miko.vision.edgeAI.faceRecognizer.HeadPose.{get,set}HeadPoseParams}`) —
  a `miko.vision.edgeAI.*` Java package that **does not exist anywhere in
  ServiceExam's own decompiled source** (grep-confirmed), meaning this
  particular `.so` build is shared/vestigial from a different, unanalyzed
  app package, not something ServiceExam's own Java code calls into for face
  detection.
- **ServiceExam's chain skips the FaceMesh landmark step.** `com.vision.Recognizer`
  wires `BlazeFace` → `MobileFaceNet` → `EuclidDistClassifier` directly (no
  `FaceMesh`/landmark-alignment class exists anywhere under `com/vision/**`);
  a separate `com.vision.Align` class (`rotateAlignFace()`/`warpAffineAlignFace()`)
  provides landmark-based rotation alignment using BlazeFace's own 6
  keypoints instead of a dedicated 468-point mesh model. `Align.getDistance(float[])`
  additionally computes a rough face-to-camera distance estimate from
  interocular pixel spacing (`22050f / pixelDistance`) — present but,
  per `person-tracking.md`, never actually called anywhere.
- **A third, fully dead face-recognition path also exists**:
  `com.example.root.vision.PersonRecognizer` (classic OpenCV
  `org.opencv.face.FisherFaceRecognizer`, `facerec_fisher.model`) and
  `com.example.root.vision.FaceDetect` (Haar-cascade `CascadeClassifier`
  against `/sdcard/klug/vision/haarcascade_frontalface_alt2.xml`). Neither is
  ever instantiated from live code (`vision.java`'s `FaceDetect dd` field is
  declared but never assigned) — dead/vestigial, likely an earlier
  generation of the recognizer predating the BlazeFace/MobileFaceNet
  pipeline, left in place. Flagged here only so it isn't mistaken for a live
  alternate pipeline.

**Not investigated this pass:** a byte-exact diff of ServiceExam's
`EuclidDistClassifier`/`MobileFaceNet` against MikoPlus's (both under the
same open question already logged below — exact `evaluate()` math not
transcribed for either app).

## Implementation — replicating the face pipeline in a custom launcher

This is directly reproducible from the confirmed code above; sample assumes
you've copied `assets/face_detection_front.tflite`,
`assets/face_landmark_192x192.tflite`, `assets/MobileFaceNet.tflite` and
`lib/arm64-v8a/libfaceDetection.so` out of `tools/extracted-system/` into your
own app (same TFLite Java dependency; the BlazeFace detector's native
`detect()`/`init()` JNI surface is opaque — you cannot reimplement it without
either reusing `libfaceDetection.so` as-is, matching its exact JNI signature
below, or swapping in MediaPipe's public BlazeFace TFLite model + a
pure-Java/Kotlin decoder instead of the native lib):

```java
// 1. Face detector — reuse the stock native lib; JNI signature confirmed from BlazeFace.java
static { System.loadLibrary("faceDetection"); }
public static native boolean init(AssetManager am, int modelId, int enableGpu, int numThreads);
public static native Detection[] detect(Bitmap bitmap, float probThreshold, float nmsThreshold);
// init(assetManager, /*short-range*/ 0, /*gpu*/ 0, /*threads*/ 1) then detect(frameBitmap, 0.5f, 0.45f)
// matches Recognizer's defaults (BLAZEFACE_SHORT_RANGE_128_DETECTOR, 128x128 input).

// 2. Embedding — plain TFLite Java, confirmed signature from MobileFaceNet.java
Interpreter.Options opts = new Interpreter.Options();
opts.setNumThreads(1);
// Optional GPU delegate (confirmed pattern):
GpuDelegate.Options gpuOpts = new CompatibilityList().getBestOptionsForThisDevice();
gpuOpts.setSerializationParams(getFilesDir() + "/model_cache", "2");
opts.addDelegate(new GpuDelegate(gpuOpts));

File model = new File(getFilesDir(), "MobileFaceNet.tflite"); // copy from assets on first run
Interpreter mobileFaceNet = new Interpreter(
    new FileInputStream(model).getChannel().map(FileChannel.MapMode.READ_ONLY, 0, model.length()), opts);

Bitmap face112 = Bitmap.createScaledBitmap(croppedFaceBitmap, 112, 112, true);
float[][][][] input = /* normalize face112 into NHWC float array, as MobileFaceNet.getTwoImageDatasets does */;
float[][] output = new float[2][192];   // model batches two inputs; duplicate the same face into both slots
mobileFaceNet.run(input, output);
float[] embedding = l2Normalize(output[0]); // 192-d unit vector

// 3. Match against enrolled embeddings — confirmed algorithm from EuclidDistClassifier.predict()
int bestIndex = -1; float bestScore = enrolled.isEmpty() ? 0f : enrolled.get(0).index;
for (EnrolledFace c : enrolled) {
    float score = similarity(embedding, c.embedding); // same "evaluate" scoring MobileFaceNet/EuclidDistClassifier use
    if (score >= bestScore) { bestIndex = c.index; bestScore = score; }
}
```

What's **not** recoverable from decompiled source without further work
(say so explicitly rather than guessing):
- The exact `similarity()`/`evaluate()` math inside `EuclidDistClassifier`
  and `MobileFaceNet` (the file was read enough to confirm the *shape* —
  L2-normalize then some pairwise scalar — but the literal arithmetic
  wasn't transcribed; re-read `EuclidDistClassifier.java:100-131` and
  `MobileFaceNet.java`'s `evaluate()`/`l2Normalize()` bodies directly before
  shipping a numeric reimplementation).
- The NHWC input normalization MobileFaceNet applies before `interpreter.run`
  (`getTwoImageDatasets`, not transcribed here).
- BlazeFace/Yolov5's native detection math is entirely inside
  `libfaceDetection.so` — there is no Java-side algorithm to port; you either
  call the existing `.so` (JNI signature above) or replace it wholesale with
  a different (e.g. MediaPipe) face detector.

For the LiveKit video-call flow, the §6 code block above is close to a
literal, working join sequence (constructor arguments trimmed of
default/null params where decompilation obscured Kotlin default-value
desugaring) — the only missing piece is the join-token issuance, which is a
server-side concern (`wsUrl`/`token` are fetched from Miko's backend, not
computed on-device).

## Open questions

- Where/how are `assets/*.tflite` copied out to `/sdcard/klug/vision/` on
  first run? Call site not found.
- Exact `evaluate()`/similarity math in `EuclidDistClassifier` and
  `MobileFaceNet.evaluate()` — read but not transcribed; needed for a
  bit-exact reimplementation of match scoring/threshold.
- Face-enrollment step ordering/branching (glasses/low-light prompts).
- Whether Agora is a live fallback path in `com.miko.mikonnect` or dead code.
- Whether `sendStreamMessage` on the LiveKit data channel carries any
  teleop/robot-control payload alongside video.
- Whether detected-face position ever reaches a physical actuator (motor/eye)
  — no call site in MikoPlus; needs confirmation from the ServiceExam/driver
  side.
