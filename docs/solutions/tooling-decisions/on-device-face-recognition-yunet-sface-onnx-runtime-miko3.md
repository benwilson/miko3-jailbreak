---
title: "On-device face recognition on the Miko 3: YuNet 2026may + SFace fp32 on ONNX Runtime 1.30, with 32-multiple input padding and no-normalisation SFace input"
module: "Miko 3 explore mode face recognition (mode-explore YuNetDecoder, FaceCropper, FaceAlign, FaceQuality, FaceEmbedder, FaceMatcher; scripts/face-bench.py)"
date: "2026-09-27"
problem_type: tooling_decision
category: tooling-decisions
component: tooling
severity: medium
related_components:
  - "development_workflow"
tags:
  - "face-recognition"
  - "yunet"
  - "sface"
  - "onnx-runtime"
  - "arcface-alignment"
  - "model-licensing"
  - "public-domain-fixtures"
  - "mt8168"
applies_when:
  - "Running the YuNet 2026may detector (symbolic H/W) in ONNX Runtime, where every input side must be a multiple of 32"
  - "Feeding SFace embeddings: raw 0-255 RGB NCHW, no normalisation, 5-point ArcFace-template alignment"
  - "Choosing a face embedder for a public repo where the weights licence must allow commercial use"
  - "Loading a ~38.7 MB ONNX asset on the robot without doubling it on the heap"
  - "Proving real-model Python tests pass, which skip under system python3 without onnxruntime/numpy"
symptoms:
  - "YuNet 2026may fails in ONNX Runtime 1.30 at 320x240 with an Add node error (Add_44)"
  - "YuNet reports -1 for input H/W so the input size cannot be read from the model"
  - "Real-model face tests silently skip under system python3 3.14"
resolution_type: code_fix
---

# On-device face recognition on the Miko 3: YuNet 2026may + SFace fp32 on ONNX Runtime 1.30, with 32-multiple input padding and no-normalisation SFace input

## Context

On-device face recognition in explore mode (branch `feat/explore-on-device-face-recognition`, no PR yet) runs two OpenCV Zoo models in the ONNX Runtime 1.30 the mode already bundles for the object detector (see `docs/solutions/tooling-decisions/on-device-object-detection-yoloe-onnx-runtime-miko3.md` for the AAR bundling and the two-thread MT8168 rule):

- **Detector:** YuNet 2026may, shipped as `mode-explore/assets/face_yunet.onnx` (MIT, licence file beside it). Replaced Android's `FaceDetector`, which missed an obvious, well-lit frontal face (`FaceCropper.java:30-33`).
- **Embedder:** SFace 2021dec fp32, shipped as `mode-explore/assets/face_sface.onnx` (Apache-2.0, licence file beside it) (`FaceEmbedder.java:25-26`).

Pipeline: `FaceCropper` (YuNet run) -> `YuNetDecoder` (boxes + 5 landmarks) -> `FaceAlign` (5-point similarity onto the ArcFace 112x112 template) -> `FaceMatcher.input` (pack pixels) -> `FaceEmbedder` (128-d) -> `FaceMatcher.match` (dot product). All under `mode-explore/src/com/miko3/mode/explore/`.

Neither model's input contract is self-describing, and each has at least one way to fail silently or loudly that is not in the upstream README. This doc records those contracts.

## Guidance

### YuNet 2026may: input size is a constant, both sides multiples of 32

- The 2026may graph has symbolic height/width (reports `-1`), so the input size cannot be read from the model. It is a fixed constant: `YuNetDecoder.INPUT_W = 320`, `INPUT_H = 256` (`YuNetDecoder.java:40-41`). `FaceCropper` takes its size from those constants, not the session (`FaceCropper.java:66-67`, comment at `:316`).
- Every input side must be a multiple of 32. At 320x240 ORT 1.30 fails inside the graph (per this session's run: an `Add` node, `Add_44`); 320x256 and 640x480 run. The regression test `test_320x240_is_not_a_usable_input_so_the_frame_is_padded` pins this (`scripts/tests/test_explore_yunet.py:260-264`).
- So a 640x480 camera frame is scaled by 0.5 to 320x240 and padded with 16 **black rows at the bottom** (as OpenCV's `FaceDetectorYN` pads bottom/right), giving whole grids at every stride: 40x32, 20x16, 10x8 (`YuNetDecoder.java:36-38`). Scale comes from `YuNetDecoder.fitScale` (`YuNetDecoder.java:213`, used at `FaceCropper.java:226-228`).
- Input is **BGR** 0..255 floats, CHW, no normalisation (`FaceCropper.java:38`, packing at `:241-243`).
- Decode, per stride S in {8,16,32} (`YuNetDecoder.java:34`), outputs `cls/obj/bbox/kps_{8,16,32}` of shape `[1, anchors, {1,1,4,10}]`:
  - score = `sqrt(clamp01(cls) * clamp01(obj))` (`YuNetDecoder.java:149`)
  - centre = `(col + dx) * S, (row + dy) * S`; size = `exp(dw|dh) * S` (`:153-156`); landmarks likewise `(k + cell) * S`.
  - Faces whose centre falls in the padding (`cx >= contentW || cy >= contentH`) are dropped (`:157`), then NMS.
- Boxes are unscaled back to frame pixels (`FaceCropper.java:253`).

### SFace 2021dec: RGB, raw 0..255, no normalisation

- Input `data` `[1,3,112,112]`, output `fc1` `[1,128]`. `FaceEmbedder.checkShapes` (`FaceEmbedder.java:171`) refuses a model whose 4-D input is not ?x3x112x112 or whose output does not end in 128; the names `data`/`fc1` are pinned by `test_io_is_data_1x3x112x112_to_fc1_1x128` in `scripts/tests/test_face_matcher.py`.
- Pixels are **RGB** (note: the opposite channel order to YuNet) as raw 0..255 floats, NCHW, **no mean/std** — the graph normalises internally (`FaceEmbedder.java:29-31`; packing at `FaceMatcher.java:224-240`: R plane `(p>>16)&0xff`, G `(p>>8)&0xff`, B `p&0xff`). Adding a normalisation step silently degrades scores; it does not error.
- Output is L2-normalised, then compared by dot product (`FaceEmbedder.java:102`, `FaceMatcher.java:13`, `:187`).
- Alignment: the least-squares similarity transform (Umeyama with its reflection guard: uniform scale, rotation, shift, never a reflection) from the 5 YuNet landmarks to the ArcFace template (`FaceAlign.java:4-11`, template `:32-36`):
  `(38.2946,51.6963) (73.5318,51.5014) (56.0252,71.7366) (41.5493,92.3655) (70.7299,92.2041)`, `FaceAlign.SIDE = 112` (`:28`).
- Load the ~38.7 MB model from a **file path**, not a byte array: `FaceEmbedder` streams the asset once into the files dir under a name carrying `FaceMatcher.MODEL_ID` (`"sface-2021dec"`, `FaceMatcher.java:37`), via temp file + rename, and calls `env.createSession(path, ...)` (`FaceEmbedder.java:34-39`, `:49`, `:150-160`, copy at `:197`). A byte[] load would hold the model twice on the heap. (Contrast: YuNet is small and still loads via `HttpUtil.readAssetBytes`, `FaceCropper.java:315`.)
- SFace lists its initializers as graph inputs, so ORT logs one "initializer appears in graph inputs" warning per weight. Session log level is set to `ORT_LOGGING_LEVEL_ERROR` to silence them (`FaceEmbedder.java:157-158`). The warnings are harmless; do not "fix" the model to remove them.
- Both sessions use 2 intra-op threads and `ALL_OPT` (`FaceEmbedder.java:50,155`; `FaceCropper.java:54,313`), and the embedder is never loaded alongside the object detector (`FaceEmbedder.java:41-42`).

### Why these models (licence and precision)

- SFace (Apache-2.0 weights; training data undocumented upstream) was the only commercially clean embedder found. InsightFace MobileFaceNet (`w600k_mbf`) and EdgeFace are non-commercial; GhostFaceNets has no licence path.
- fp32, not int8: per this session's research, int8 is slower on Cortex-A53 in the opencv_zoo benchmark and has a reported accuracy-collapse issue (opencv_zoo #189).

### Testing

- The repo is public. Face fixtures are US federal public-domain portraits only (`scripts/tests/fixtures/faces/`, with `README.md`). Office captures live under `tools/face-bench/`, which is gitignored (`.gitignore:75-76`) and must never be committed.
- System `python3` (3.14) has no onnxruntime/numpy, so the real-model tests **skip** there (`test_explore_yunet.py:186-194`, `test_face_matcher.py:334-340`). A green run under system python proves nothing about the models. Run them with `tools/face-bench/venv/bin/python` (onnxruntime 1.30, numpy, pillow).
- Per this session's run on the fixtures: same person 0.756, different people 0.16 / 0.24. The tests assert same > 0.5, different < 0.3 (`test_face_matcher.py:17`, `:368-372`).

## Why This Matters

Each contract here fails in a different way. The wrong YuNet size fails loudly, but only in the graph at run time, and the model's `-1` shape gives no hint of the multiple-of-32 rule. The wrong SFace channel order or an added normalisation fails **silently**: embeddings still come out unit-length, and only the match scores get worse. Loading SFace as bytes works on the Mac and risks OOM on the robot. And real-model tests that skip under system python look like passes.

## When to Apply

- Swapping either model's version (YuNet release, SFace to another embedder): re-check the input size rule, channel order, normalisation, output names/shapes, and the alignment template. Bump `FaceMatcher.MODEL_ID` so the cached copy is replaced and stored embeddings are recognisably from the old model. Update `MODEL_SHA256` in `test_explore_yunet.py:41` for YuNet.
- Changing the YuNet input size or camera resolution: keep both sides multiples of 32, pad bottom/right, keep the padding filter in the decoder.
- Adding any model over a few MB: load from a file path, not `readAssetBytes`; keep to 2 threads; do not co-load with the object detector.
- Any face test change: run it under `tools/face-bench/venv/bin/python` and confirm it did not skip; use only public-domain fixtures.

## Examples

Frame to YuNet input (640x480 camera):

```
640x480  --x0.5-->  320x240  --pad 16 black rows below-->  320x256
grids: stride 8 -> 40x32, stride 16 -> 20x16, stride 32 -> 10x8
faces with centre y >= 240 are dropped
```

Channel order differs between the two models:

```java
// FaceCropper (YuNet): BGR planes
chw.put(i, p & 0xff); chw.put(plane + i, (p >> 8) & 0xff); chw.put(2 * plane + i, (p >> 16) & 0xff);
// FaceMatcher.input (SFace): RGB planes, raw 0..255, no mean/std
out[i] = (p >> 16) & 0xff; out[plane + i] = (p >> 8) & 0xff; out[2 * plane + i] = p & 0xff;
```

Running the real-model tests:

```
tools/face-bench/venv/bin/python -m unittest scripts/tests/test_explore_yunet.py scripts/tests/test_face_matcher.py -v   # check for "skipped"
```
