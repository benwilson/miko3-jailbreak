---
title: "On-device object detection on the Miko 3: YOLOE with a custom vocabulary on the prebuilt ONNX Runtime AAR, not the vendor detector"
module: "Miko 3 explore mode camera curiosity (mode-explore OnnxRecognizer, YoloeDecoder, build-mode-explore.py)"
date: "2026-09-23"
problem_type: tooling_decision
category: tooling-decisions
component: tooling
severity: medium
related_components:
  - "frontend"
  - "development_workflow"
tags:
  - "object-detection"
  - "yoloe"
  - "onnx-runtime"
  - "mt8168"
  - "open-vocabulary"
  - "mediaplayer"
  - "opus-webm"
applies_when:
  - "A mode needs to name what the Miko 3 camera sees, beyond the vendor's 80 COCO classes"
  - "A native ML runtime is needed but no Android NDK is available to build a JNI library"
  - "Many short speech clips must ship in an APK without adding tens of megabytes"
---

# On-device object detection on the Miko 3

## Context

The firmware bundles an unused Yolo-Fastest detector (`com.miko.objectDetection.YOLOv4`, 80 COCO classes, JNI). It loads in a custom app and runs in 46-76 ms a frame, but on a floor test frame it called two potted plants "vase" and missed the plants entirely, and 80 COCO labels cannot name most floor and desk things. There is no NDK on the build machine, so compiling a new ncnn/TFLite JNI library was not an option.

## Guidance

**Use YOLOE-26n exported with the mode's own vocabulary, run by the prebuilt ONNX Runtime Android AAR.**

- YOLOE is open-vocabulary: `model.set_classes(names)` bakes the names' text embeddings into the head, and the ONNX export is then a plain detector for exactly those names in that order (`scripts/export-explore-detector.py`, in its own Ultralytics venv under the gitignored `tools/detector-export/`). The vocabulary is `mode-explore/assets/vocabulary.txt` (341 names); the model and the app must be built from the same file.
- ONNX Runtime needs no NDK: the Maven Central AAR (`onnxruntime-android` 1.30.0) carries `classes.jar` plus `libonnxruntime.so` / `libonnxruntime4j_jni.so` for arm64. `build-mode-explore.py` downloads it once into the gitignored `tools/third_party/`, checks a pinned SHA-256, and `build_common.build_apk(..., jars=[...])` compiles and dexes the jar in. It adds ~13 MB compressed to the APK; the 11 MB model adds ~10 MB.
- Measured on the MT8168 at 640x480: session create ~750 ms, first frame 1.3-1.5 s, steady ~1.1 s on **2** intra-op threads (1.4-2.5 s live, with the camera streaming). **Export at 320x416 instead: ~0.6 s a live look** with the same plants found; the app scales camera frames to the model's input size, read from the model. **4 threads is slower** (1.6-1.9 s): it competes with the brain, UI and camera. The same model runs in 25 ms on an Apple-silicon Mac and gave the same detections as the robot, so the Mac is a good place to check vocabulary changes (`--check IMAGE`).
- The segmentation export's `output0` is `[4 + names + 32, anchors]` (box centre/size in input pixels, already-sigmoided scores, 32 mask coefficients to ignore) and is **not** NMS-free, so decode does per-name NMS in Java (`YoloeDecoder`). Scan scores row by row (row-major), not anchor by anchor: the output is ~9.5 MB a frame. Reuse the input tensor over a direct buffer and the output array across frames.
- Labels flicker between near-synonyms on the same box from frame to frame ("tv", "monitor", "computer"; the plants came out as "succulent"). Track a target by box overlap when its name disappears, not by name alone.
- On the floor a rug/mat/carpet fills the bottom of nearly every frame; treat surfaces as background, never as a target.

**Ship speech clips as Opus in WebM.** 341 short WAV name clips would add ~17 MB (aapt stores `.wav` and `.webm` uncompressed for `openFd`); Opus at 24 kbit/s in WebM is ~4 KB each (~1.5 MB total). The robot's `MediaPlayer` (Android 9) prepared and played a WebM Opus clip; Opus in an Ogg container needs Android 10. MediaPlayer is required anyway — SoundPool is silent on this robot (`docs/solutions/integration-issues/soundpool-plays-silently-on-miko3-use-mediaplayer.md`).

## Applicability

Any mode that wants to recognize or name things with the camera. About a second per look suits a robot that stops to look; it is too slow for tracking while driving (re-check between short legs instead), and a smaller export (e.g. 320x416) would trade range for ~2.5x speed. YOLOE is AGPL-3.0: fine for this personal build; publish the source if the APK is ever distributed. Measurements are in `docs/hardware/camera-vision.md` §10.
