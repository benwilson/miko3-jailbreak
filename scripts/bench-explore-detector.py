#!/usr/bin/env python3
"""bench-explore-detector.py — time Explore's object detector stage by stage on
the Mac, build faster variants of it, and check that they still see what the
shipped model sees (detector speed plan; camera curiosity KTD2).

  scripts/bench-explore-detector.py build            # write the variants into out/detector-variants/
  scripts/bench-explore-detector.py bench            # stage breakdown + variant table
  scripts/bench-explore-detector.py bench --java     # also time the app's Java stages on the host JVM
  scripts/bench-explore-detector.py ship NAME...     # copy accepted variants into mode-explore/assets/

The robot's pipeline per look (ExploreCamera.recognize, OnnxRecognizer.detect):
decode the 640x480 camera JPEG; preprocess (scale to the model's input size,
write CHW floats 0..1); run; copy the output into Java; decode detections
(YoloeDecoder: best name per anchor, same-name NMS). `bench` times the same
stages here: decode and scale with OpenCV (bilinear without antialiasing, like
Android's filtered Canvas scale), run with onnxruntime 1.30 (the app's version)
on 2 intra-op threads, and decode with a numpy port of YoloeDecoder. `--java`
compiles the app's own YoloeDecoder plus a timing main
(scripts/tests/fixtures/explore_detector_harness) and times the Java decode,
the CHW float loop and the output copy on the host JVM against the real outputs.

Mac numbers are relative guidance only: an Apple core has dot-product int8
instructions and wide out-of-order execution that the robot's Cortex-A53s lack,
and HotSpot is not ART. The robot's own numbers come from Explore's bench mode
(scripts/qa-detector-bench.py), which never opens the camera.

Variants (`build`), each from the shipped detector.onnx:
  pruned    the mask branch removed (its protos and 32 coefficient rows are
            computed today and never read); same output layout minus those rows,
            so the unchanged Java decoder reads it
  topk      pruned, plus best-name/score per anchor and the top K anchors in the
            graph: output "detections" [1, 6, K] (cx, cy, w, h as frame
            fractions, score, name index); NMS stays in Java over K rows
  rgba      topk with uint8 RGBA input at the model size [1, H, W, 4], the 1/255
            folded into the first convolution: Java fills it with one
            Bitmap.copyPixelsToBuffer instead of a per-pixel float loop
  rgba640   rgba taking the 640x480 frame, with the resize in the graph
  int8      rgba, statically quantized (QDQ, uint8 activations, per-channel int8
            weights, percentile calibration on the robot frames only, the
            detection head left in float)
  s256      rgba at 256x320, from a re-export (needs the export venv; skipped
            when tools/detector-export has no YOLOE weights)

Runs in the detector export venv, tools/detector-export/.venv (onnxruntime 1.30,
onnx, numpy, OpenCV; see scripts/export-explore-detector.py), and re-enters it
automatically.
"""
import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
ASSETS = REPO / "mode-explore" / "assets"
DETECTOR = ASSETS / "detector.onnx"
VOCABULARY = ASSETS / "vocabulary.txt"
BENCH_FRAMES = ASSETS / "bench"
VARIANTS = REPO / "out" / "detector-variants"
EXPORT_DIR = REPO / "tools" / "detector-export"
VENV_PYTHON = EXPORT_DIR / ".venv" / "bin" / "python"
SITE = EXPORT_DIR / ".venv" / "lib" / "python3.11" / "site-packages"
TESTS = REPO / "scripts" / "tests"
JAVA_HARNESS = TESTS / "fixtures" / "explore_detector_harness" / "src"
JAVA_PKG = REPO / "mode-explore" / "src" / "com" / "miko3" / "mode" / "explore"

# OnnxRecognizer / ExploreTuning: 2 intra-op threads, the unsure floor, NMS IoU, cap.
THREADS = 2
MIN_SCORE = 0.2
MAX_IOU = 0.5
MAX_COUNT = 20
# Candidates kept by the graph's top-k. The decoder keeps at most MAX_COUNT after
# NMS; 300 leaves room for 280 suppressed same-name duplicates before any differs.
TOPK = 300
CAMERA = (480, 640)
MATCH_IOU = 0.5
PERSON = "person"
# Variants the robot may load: the app only accepts asset names of this shape
# (DetectorConfig.MODEL_NAME).
VARIANT_NAMES = ("pruned", "topk", "rgba", "rgba640", "int8", "s256")
# Agreement a variant needs before it may ship (ship refuses otherwise): every
# reference person found, at least this share of all reference detections, and
# at most this many detections the shipped model does not make (phantom things
# he would go and name), per reference detection.
SHIP_MIN_RECALL = 0.85
SHIP_MAX_EXTRA = 0.15


class BenchError(Exception):
    pass


# ---- frames ----

def default_frames():
    """Robot frames first (the bundled bench set, re-encoded from 2026-09-25 and
    -30 captures, then the brightened nav captures when the gitignored
    voice-work/ has them), then person-heavy host-only images, de-duplicated."""
    found = []
    for pattern_dir, glob in ((BENCH_FRAMES, "*.jpg"),
                              (REPO / "voice-work" / "nav-frames", "*-bright.jpg"),
                              (SITE / "ultralytics" / "assets", "*.jpg"),
                              (SITE / "matplotlib" / "mpl-data" / "sample_data", "grace_hopper.jpg"),
                              (TESTS / "fixtures" / "faces", "*.jpg")):
        if pattern_dir.is_dir():
            found.extend(sorted(pattern_dir.glob(glob)))
    seen, out = set(), []
    for p in found:
        key = p.read_bytes()[:4096]
        if key not in seen:
            seen.add(key)
            out.append(p)
    return out


def is_robot_frame(path):
    """Robot camera frames carry the MIKO3 EXIF model; the rest are host-only."""
    return b"MIKO3" in Path(path).read_bytes()[:2048]


# ---- pure helpers (host-tested without numpy) ----

def percentile(values, q):
    """Nearest-rank percentile, the same rule DetectorStages.percentile uses."""
    if not values:
        return 0.0
    s = sorted(values)
    k = max(0, min(len(s) - 1, int(-(-q * len(s) // 100)) - 1))
    return s[k]


def iou(a, b):
    iw = max(0.0, min(a[2], b[2]) - max(a[0], b[0]))
    ih = max(0.0, min(a[3], b[3]) - max(a[1], b[1]))
    inter = iw * ih
    union = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
    return 0.0 if union <= 0 else inter / union


def nms(candidates, max_iou=MAX_IOU, max_count=MAX_COUNT):
    """YoloeDecoder's suppression: candidates (cls, score, box) sorted by score,
    a box dropped when a kept box of the same name overlaps it more than max_iou."""
    kept = []
    for c in sorted(candidates, key=lambda c: -c[1]):
        if any(k[0] == c[0] and iou(k[2], c[2]) > max_iou for k in kept):
            continue
        kept.append(c)
        if len(kept) >= max_count:
            break
    return kept


def agreement(ref, got, match_iou=MATCH_IOU):
    """Greedy one-to-one matching of got against ref by name and IoU.
    ref/got: [(name, score, box)]. Returns counts and score deltas."""
    used = set()
    deltas, person_ref, person_hit = [], 0, 0
    matched = 0
    for name, score, box in ref:
        best, best_iou = None, match_iou
        for j, (n2, s2, b2) in enumerate(got):
            if j in used or n2 != name:
                continue
            o = iou(box, b2)
            if o >= best_iou:
                best, best_iou = j, o
        if name == PERSON:
            person_ref += 1
        if best is not None:
            used.add(best)
            matched += 1
            deltas.append(got[best][1] - score)
            if name == PERSON:
                person_hit += 1
    return {"ref": len(ref), "got": len(got), "matched": matched, "extra": len(got) - len(used),
            "deltas": deltas, "person_ref": person_ref, "person_hit": person_hit}


def ship_verdict(summary, min_recall=SHIP_MIN_RECALL, max_extra=SHIP_MAX_EXTRA):
    """(ok, reason) for a variant's summed agreement over the frames."""
    if summary["person_hit"] < summary["person_ref"]:
        return False, f"missed {summary['person_ref'] - summary['person_hit']} of {summary['person_ref']} people"
    recall = summary["matched"] / summary["ref"] if summary["ref"] else 1.0
    if recall < min_recall:
        return False, f"recall {recall:.0%} < {min_recall:.0%}"
    extra = summary["extra"] / summary["ref"] if summary["ref"] else 0.0
    if extra > max_extra:
        return False, f"{summary['extra']} extra detections ({extra:.0%} > {max_extra:.0%})"
    return True, f"recall {recall:.0%}, people {summary['person_hit']}/{summary['person_ref']}"


def asset_name(variant):
    return "detector.onnx" if variant == "fp32" else f"detector-{variant}.onnx"


# ---- graph surgery (onnx) ----

def _head_tensors(model):
    """(boxes, logits, scores) tensor names of the YOLOE head: output0 is
    Concat(boxes [1,4,A], Sigmoid(logits) [1,C,A], mask coefficients [1,32,A]),
    or the same without the mask rows once pruned."""
    producers = {o: n for n in model.graph.node for o in n.output}
    cat = producers["output0"]
    if cat.op_type != "Concat" or len(cat.input) not in (2, 3):
        raise BenchError("!! output0 is not the 3-way head Concat this script knows")
    boxes, scores = cat.input[0], cat.input[1]
    sig = producers[scores]
    if sig.op_type != "Sigmoid":
        raise BenchError("!! the head's score rows are not a Sigmoid")
    return boxes, sig.input[0], scores


def _prune(model):
    """Drop nodes and initializers no graph output depends on."""
    graph = model.graph
    producers = {o: n for n in graph.node for o in n.output}
    needed, stack = set(), [o.name for o in graph.output]
    keep = set()
    while stack:
        t = stack.pop()
        if t in needed:
            continue
        needed.add(t)
        n = producers.get(t)
        if n is not None and id(n) not in keep:
            keep.add(id(n))
            stack.extend(i for i in n.input if i)
    nodes = [n for n in graph.node if id(n) in keep]
    del graph.node[:]
    graph.node.extend(nodes)
    used = {i for n in graph.node for i in n.input} | {o.name for o in graph.output}
    inits = [i for i in graph.initializer if i.name in used]
    del graph.initializer[:]
    graph.initializer.extend(inits)
    vis = [v for v in graph.value_info if v.name in used]
    del graph.value_info[:]
    graph.value_info.extend(vis)
    ins = [i for i in graph.input if i.name in used]
    del graph.input[:]
    graph.input.extend(ins)


def make_pruned(model):
    import onnx
    from onnx import TensorProto, helper
    m = onnx.ModelProto()
    m.CopyFrom(model)
    boxes, _, scores = _head_tensors(m)
    classes = _classes(m)
    anchors = _anchors(m)
    for n in m.graph.node:
        if "output0" in n.output:
            n.output[0] = "output0_full"
    m.graph.node.append(helper.make_node("Concat", [boxes, scores], ["output0"], axis=1, name="pruned/Concat"))
    del m.graph.output[:]
    m.graph.output.append(helper.make_tensor_value_info("output0", TensorProto.FLOAT, [1, 4 + classes, anchors]))
    _prune(m)
    return m


def _classes(model):
    for o in model.graph.output:
        if o.name == "output0":
            rows = o.type.tensor_type.shape.dim[1].dim_value
            # The shipped model's 32 mask rows; a pruned one has none.
            return rows - 4 - 32 if any(x.name == "output1" for x in model.graph.output) else rows - 4
    raise BenchError("!! no output0")


def _anchors(model):
    for o in model.graph.output:
        if o.name == "output0":
            return o.type.tensor_type.shape.dim[2].dim_value
    raise BenchError("!! no output0")


def make_topk(model, k=TOPK):
    """Best name and score per anchor and the top k anchors, in the graph.
    ReduceMax/ArgMax run on the logits, and only each anchor's max logit goes
    through a Sigmoid (monotonic, so the same winner as the scores), not all
    C x A of them."""
    import onnx
    from onnx import TensorProto, helper, numpy_helper
    import numpy as np
    m = onnx.ModelProto()
    m.CopyFrom(model)
    boxes, logits, _ = _head_tensors(m)
    anchors = _anchors(m)
    k = min(k, anchors)
    g = m.graph
    dims = [d.dim_value for d in g.input[0].type.tensor_type.shape.dim]
    h, w = dims[2], dims[3]
    g.initializer.extend([
        numpy_helper.from_array(np.array([w, h, w, h], dtype=np.float32).reshape(1, 4, 1), "topk/frame"),
        numpy_helper.from_array(np.array([1], dtype=np.int64), "topk/axes1"),
        numpy_helper.from_array(np.array([0], dtype=np.int64), "topk/axes0"),
        numpy_helper.from_array(np.array([k], dtype=np.int64), "topk/k"),
    ])
    g.node.extend([
        helper.make_node("ReduceMax", [logits, "topk/axes1"], ["topk/maxlogit"], keepdims=0, name="topk/ReduceMax"),
        helper.make_node("ArgMax", [logits], ["topk/cls"], axis=1, keepdims=0, name="topk/ArgMax"),
        helper.make_node("Sigmoid", ["topk/maxlogit"], ["topk/score"], name="topk/Sigmoid"),
        helper.make_node("TopK", ["topk/score", "topk/k"], ["topk/values", "topk/indices"],
                         axis=-1, largest=1, sorted=1, name="topk/TopK"),
        helper.make_node("Squeeze", ["topk/indices", "topk/axes0"], ["topk/idx"], name="topk/Squeeze"),
        helper.make_node("Gather", [boxes, "topk/idx"], ["topk/pixels"], axis=2, name="topk/GatherBoxes"),
        helper.make_node("Div", ["topk/pixels", "topk/frame"], ["topk/boxes"], name="topk/Fractions"),
        helper.make_node("Gather", ["topk/cls", "topk/idx"], ["topk/clsk"], axis=1, name="topk/GatherCls"),
        helper.make_node("Cast", ["topk/clsk"], ["topk/clsf"], to=TensorProto.FLOAT, name="topk/Cast"),
        helper.make_node("Unsqueeze", ["topk/values", "topk/axes1"], ["topk/values3"], name="topk/UnsqueezeV"),
        helper.make_node("Unsqueeze", ["topk/clsf", "topk/axes1"], ["topk/cls3"], name="topk/UnsqueezeC"),
        helper.make_node("Concat", ["topk/boxes", "topk/values3", "topk/cls3"], ["detections"], axis=1,
                         name="topk/Concat"),
    ])
    del g.output[:]
    g.output.append(helper.make_tensor_value_info("detections", TensorProto.FLOAT, [1, 6, k]))
    _prune(m)
    return m


def make_rgba(model, frame_hw=None):
    """uint8 RGBA [1, h, w, 4] input (Android ARGB_8888's byte order), the alpha
    dropped, transposed to NCHW and, when (h, w) is not the model size, resized
    bilinearly (half-pixel, no antialias: Android's filtered Canvas scale). The
    1/255 is folded into the first convolution's weights, which is exact up to
    float rounding (a convolution is linear and its zero padding stays zero)."""
    import onnx
    from onnx import TensorProto, helper, numpy_helper
    import numpy as np
    m = onnx.ModelProto()
    m.CopyFrom(model)
    g = m.graph
    old = g.input[0]
    dims = [d.dim_value for d in old.type.tensor_type.shape.dim]
    h, w = dims[2], dims[3]
    in_h, in_w = frame_hw or (h, w)
    consumers = [n for n in g.node if old.name in n.input]
    if len(consumers) != 1 or consumers[0].op_type != "Conv":
        raise BenchError("!! the input does not feed exactly one Conv; cannot fold the 1/255")
    conv = consumers[0]
    inits = {i.name: i for i in g.initializer}
    wt = inits[conv.input[1]]
    if sum(1 for n in g.node for i in n.input if i == wt.name) != 1:
        raise BenchError("!! the first Conv's weights are shared; cannot fold the 1/255")
    wt.CopyFrom(numpy_helper.from_array((numpy_helper.to_array(wt) / 255.0).astype(np.float32), wt.name))
    nodes = [
        helper.make_node("Slice", ["rgba", "rgba/s0", "rgba/s3", "rgba/axes"], ["rgba/rgb"], name="rgba/Slice"),
        helper.make_node("Cast", ["rgba/rgb"], ["rgba/f"], to=TensorProto.FLOAT, name="rgba/Cast"),
        helper.make_node("Transpose", ["rgba/f"], ["rgba/chw"], perm=[0, 3, 1, 2], name="rgba/Transpose"),
    ]
    g.initializer.extend([
        numpy_helper.from_array(np.array([0], dtype=np.int64), "rgba/s0"),
        numpy_helper.from_array(np.array([3], dtype=np.int64), "rgba/s3"),
        numpy_helper.from_array(np.array([3], dtype=np.int64), "rgba/axes"),
    ])
    feed = "rgba/chw"
    if (in_h, in_w) != (h, w):
        g.initializer.append(numpy_helper.from_array(np.array([1, 3, h, w], dtype=np.int64), "rgba/sizes"))
        nodes.append(helper.make_node("Resize", ["rgba/chw", "", "", "rgba/sizes"], ["rgba/resized"],
                                      mode="linear", coordinate_transformation_mode="half_pixel",
                                      name="rgba/Resize"))
        feed = "rgba/resized"
    for n in consumers:
        for i, name in enumerate(n.input):
            if name == old.name:
                n.input[i] = feed
    for i, n in enumerate(nodes):
        g.node.insert(i, n)
    del g.input[:]
    g.input.append(helper.make_tensor_value_info("rgba", TensorProto.UINT8, [1, in_h, in_w, 4]))
    _prune(m)
    return m


def quantize_int8(src, dst, frames, scratch):
    """Static QDQ quantization: uint8 activations, per-channel int8 weights (U8S8),
    percentile calibration, the detection head (model.23) left in float.

    On the A53 (ARMv8.0, no sdot/udot) ONNX Runtime's MLAS runs U8U8 and U8S8
    through its plain-NEON U8X8 kernel; S8S8 is only fast with sdot. U8S8 is
    also ORT's documented default and keeps per-channel weights, which a model
    with many depthwise convolutions needs. (XNNPACK prefers signed QS8 with
    per-channel weights; an XNNPACK-first int8 build would use S8S8.)"""
    from onnxruntime.quantization import (CalibrationDataReader, CalibrationMethod, QuantFormat, QuantType,
                                          quantize_static)
    from onnxruntime.quantization.shape_inference import quant_pre_process

    class Reader(CalibrationDataReader):
        def __init__(self):
            hw = rgba_hw(src)
            self.items = iter([{"rgba": rgba_input(camera_frame(load_bgr(p)), hw)} for p in frames])

        def get_next(self):
            return next(self.items, None)

    pre = Path(scratch) / "int8-pre.onnx"
    quant_pre_process(str(src), str(pre), skip_symbolic_shape=True)
    # The head (model.23) and the graph's own pre/post ops stay float. On the host
    # bench (2026-09-30) quantizing everything missed a person; MinMax calibration
    # or Conv-only quantization lost more detections than percentile with a float head.
    quantize_static(str(pre), str(dst), Reader(), quant_format=QuantFormat.QDQ,
                    activation_type=QuantType.QUInt8, weight_type=QuantType.QInt8, per_channel=True,
                    calibrate_method=CalibrationMethod.Percentile,
                    nodes_to_exclude=[n for n in _node_names(pre) if n.startswith(("topk/", "rgba/", "/model.23/"))])


def _node_names(path):
    import onnx
    return [n.name for n in onnx.load(str(path)).graph.node]


# ---- inputs ----

def load_bgr(path):
    import cv2
    return cv2.imdecode(_bytes(path), cv2.IMREAD_COLOR)


def _bytes(path):
    import numpy as np
    return np.frombuffer(Path(path).read_bytes(), dtype=np.uint8)


def camera_frame(bgr):
    """The camera's 640x480 (other images are stretched to it, as a phone photo
    would be if the camera delivered it)."""
    import cv2
    if bgr.shape[:2] != CAMERA:
        bgr = cv2.resize(bgr, (CAMERA[1], CAMERA[0]), interpolation=cv2.INTER_AREA)
    return bgr


def scale(bgr, hw):
    import cv2
    if bgr.shape[:2] == tuple(hw):
        return bgr
    return cv2.resize(bgr, (hw[1], hw[0]), interpolation=cv2.INTER_LINEAR)


def chw_input(bgr, hw):
    """OnnxRecognizer today: scale, then CHW RGB floats 0..1."""
    import numpy as np
    rgb = scale(bgr, hw)[:, :, ::-1]
    return np.ascontiguousarray(rgb.transpose(2, 0, 1), dtype=np.float32)[None] / np.float32(255)


def rgba_input(bgr, hw):
    """What Bitmap.copyPixelsToBuffer writes for ARGB_8888: R, G, B, A bytes."""
    import cv2
    return cv2.cvtColor(scale(bgr, hw), cv2.COLOR_BGR2RGBA)[None]


def rgba_hw(path):
    """The (h, w) an rgba model's input takes."""
    import onnx
    d = onnx.load(str(path), load_external_data=False).graph.input[0].type.tensor_type.shape.dim
    return d[1].dim_value, d[2].dim_value


# ---- decode (numpy port of YoloeDecoder) ----

def decode_raw(out, names, hw, min_score=MIN_SCORE):
    """YoloeDecoder.decode over [4 + C (+32), A]: best name per anchor (ties to
    the later name, as its >= does), boxes as frame fractions, then NMS."""
    import numpy as np
    out = out.reshape(out.shape[-2], out.shape[-1])
    c = len(names)
    scores = out[4:4 + c]
    best = c - 1 - np.argmax(scores[::-1], axis=0)
    best_score = scores[best, np.arange(scores.shape[1])]
    keep = np.nonzero(best_score >= min_score)[0]
    return _finish([(int(best[a]), float(best_score[a]), out[0, a], out[1, a], out[2, a], out[3, a]) for a in keep],
                   names, hw)


def decode_topk(det, names, min_score=MIN_SCORE):
    """The topk variants' [6, K]: cx, cy, w, h (frame fractions), score, name index."""
    det = det.reshape(6, -1)
    rows = [(int(det[5, i]), float(det[4, i]), det[0, i], det[1, i], det[2, i], det[3, i])
            for i in range(det.shape[1]) if det[4, i] >= min_score]
    return _finish(rows, names, (1, 1))


def _finish(rows, names, hw):
    h, w = hw
    cands = [(cls, s, (float(cx - bw / 2) / w, float(cy - bh / 2) / h, float(cx + bw / 2) / w,
                       float(cy + bh / 2) / h)) for cls, s, cx, cy, bw, bh in rows]
    return [(names[c], s, b) for c, s, b in nms(cands)]


# ---- benchmark ----

def session(path, threads=THREADS):
    import onnxruntime as ort
    so = ort.SessionOptions()
    so.intra_op_num_threads = threads
    so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    return ort.InferenceSession(str(path), so, providers=["CPUExecutionProvider"])


def run_variant(path, frames, names, rounds):
    """Per-stage milliseconds over rounds x frames, and the detections per frame."""
    import numpy as np
    t0 = time.perf_counter()
    s = session(path)
    load_ms = (time.perf_counter() - t0) * 1000
    inp = s.get_inputs()[0]
    rgba = inp.name == "rgba"
    in_hw = tuple(inp.shape[1:3]) if rgba else tuple(inp.shape[2:4])
    out_name = s.get_outputs()[0].name
    topk = out_name == "detections"
    hw = (1, 1) if topk else in_hw
    jpegs = [Path(p).read_bytes() for p in frames]
    stages = {k: [] for k in ("decode", "prep", "run", "copy", "detect", "total")}
    dets, outs = {}, {}
    for r in range(rounds + 1):
        for p, jpeg in zip(frames, jpegs):
            import cv2
            a = time.perf_counter()
            bgr = camera_frame(cv2.imdecode(np.frombuffer(jpeg, dtype=np.uint8), cv2.IMREAD_COLOR))
            b = time.perf_counter()
            x = rgba_input(bgr, in_hw) if rgba else chw_input(bgr, in_hw)
            c = time.perf_counter()
            raw = s.run([out_name], {inp.name: x})[0]
            d = time.perf_counter()
            out = np.array(raw, copy=True)
            e = time.perf_counter()
            found = decode_topk(out, names) if topk else decode_raw(out, names, hw)
            f = time.perf_counter()
            if r == 0:
                dets[str(p)] = found
                outs[str(p)] = out
                continue  # warm-up round
            for k, (u, v) in {"decode": (a, b), "prep": (b, c), "run": (c, d), "copy": (d, e),
                              "detect": (e, f), "total": (a, f)}.items():
                stages[k].append((v - u) * 1000)
    return {"load_ms": load_ms, "stages": stages, "dets": dets, "outs": outs,
            "out_bytes": int(next(iter(outs.values())).nbytes), "topk": topk, "hw": in_hw, "rgba": rgba,
            "size": Path(path).stat().st_size}


def variant_paths():
    out = {"fp32": DETECTOR}
    for v in VARIANT_NAMES:
        p = VARIANTS / asset_name(v)
        if p.exists():
            out[v] = p
    return out


def read_names():
    names = []
    for line in VOCABULARY.read_text().splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            names.append(line)
    return names


def summarize_agreement(ref, got, frames):
    tot = {"ref": 0, "got": 0, "matched": 0, "extra": 0, "deltas": [], "person_ref": 0, "person_hit": 0,
           "person_deltas": []}
    for p in frames:
        a = agreement(ref[str(p)], got[str(p)])
        for k in ("ref", "got", "matched", "extra", "person_ref", "person_hit"):
            tot[k] += a[k]
        tot["deltas"] += a["deltas"]
        r = [d for d in ref[str(p)] if d[0] == PERSON]
        g = [d for d in got[str(p)] if d[0] == PERSON]
        tot["person_deltas"] += agreement(r, g)["deltas"]
    return tot


def stage_table(res):
    st = res["stages"]
    total = sum(sum(v) for k, v in st.items() if k != "total")
    lines = ["| stage | p50 ms | p95 ms | share |", "|---|---:|---:|---:|"]
    for k in ("decode", "prep", "run", "copy", "detect"):
        lines.append(f"| {k} | {percentile(st[k], 50):.2f} | {percentile(st[k], 95):.2f} | "
                     f"{100 * sum(st[k]) / total:.1f}% |")
    lines.append(f"| total | {percentile(st['total'], 50):.2f} | {percentile(st['total'], 95):.2f} | |")
    return "\n".join(lines)


def bench(frames, rounds, java, as_json):
    names = read_names()
    paths = variant_paths()
    results = {}
    for v, p in paths.items():
        print(f"== {v}: {p.relative_to(REPO)}", file=sys.stderr, flush=True)
        results[v] = run_variant(p, frames, names, rounds)
    ref = results["fp32"]
    robot = [p for p in frames if is_robot_frame(p)]
    report = {"frames": [str(p) for p in frames], "robot_frames": [str(p) for p in robot], "variants": {}}
    print(f"\n{len(frames)} frames ({len(robot)} from the robot), {rounds} timed rounds, "
          f"{THREADS} intra-op threads, onnxruntime CPU EP. Mac timings: relative guidance only.\n")
    print("## Stage breakdown, shipped fp32 model (host)\n")
    print(stage_table(ref))
    print("\n## Variants (host)\n")
    print("| variant | file KB | output | run p50 ms | vs fp32 | total p50 ms | matched/ref | extra |"
          " mean abs dScore | person hit/ref | person mean dScore | ship check |")
    print("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|")
    base_run = percentile(ref["stages"]["run"], 50)
    for v, res in results.items():
        a = summarize_agreement(ref["dets"], res["dets"], frames)
        ok, why = ship_verdict(a)
        run50 = percentile(res["stages"]["run"], 50)
        mad = sum(abs(d) for d in a["deltas"]) / len(a["deltas"]) if a["deltas"] else 0.0
        pd = sum(a["person_deltas"]) / len(a["person_deltas"]) if a["person_deltas"] else 0.0
        out_desc = f"{res['out_bytes'] / 1024:.0f} KB" if res["out_bytes"] >= 1024 else f"{res['out_bytes']} B"
        print(f"| {v} | {res['size'] // 1024} | {out_desc} | {run50:.2f} | {run50 / base_run:.2f}x | "
              f"{percentile(res['stages']['total'], 50):.2f} | {a['matched']}/{a['ref']} | {a['extra']} | "
              f"{mad:.3f} | {a['person_hit']}/{a['person_ref']} | {pd:+.3f} | "
              f"{'ok' if ok else 'FAIL'}: {why} |")
        report["variants"][v] = {"run_p50_ms": run50, "total_p50_ms": percentile(res["stages"]["total"], 50),
                                 "out_bytes": res["out_bytes"], "file_bytes": res["size"],
                                 "agreement": {k: v2 for k, v2 in a.items() if "deltas" not in k},
                                 "mean_abs_dscore": mad, "ship_ok": ok, "ship_why": why,
                                 "stages_p50": {k: percentile(x, 50) for k, x in res["stages"].items()}}
        if v != "fp32" and robot:
            ar = summarize_agreement(ref["dets"], res["dets"], robot)
            report["variants"][v]["robot_agreement"] = {k: v2 for k, v2 in ar.items() if "deltas" not in k}
    if robot:
        print("\nRobot frames only (matched/ref, people):",
              ", ".join(f"{v} {d['robot_agreement']['matched']}/{d['robot_agreement']['ref']} "
                        f"{d['robot_agreement']['person_hit']}/{d['robot_agreement']['person_ref']}"
                        for v, d in report["variants"].items() if "robot_agreement" in d))
    if java:
        print("\n## Java stages on the host JVM (YoloeDecoder, CHW loop, output copy)\n")
        report["java"] = java_timing(results, names)
    if as_json:
        Path(as_json).write_text(json.dumps(report, indent=1))
        print(f"\nwrote {as_json}")
    return report


def java_timing(results, names):
    """Time the app's Java stages over the real outputs: write each variant's
    first output to a .bin, run DetectorTiming (which also prints its detections
    so they can be checked against the numpy port)."""
    sys.path.insert(0, str(TESTS))
    import jvm_harness
    jdk = jvm_harness.find_jdk()
    if jdk is None:
        print("(no JDK; skipped)")
        return {}
    out = {}
    with tempfile.TemporaryDirectory(prefix="detector_timing_") as td:
        td = Path(td)
        src = [JAVA_HARNESS / "com" / "miko3" / "mode" / "explore" / "DetectorTiming.java",
               JAVA_PKG / "YoloeDecoder.java", JAVA_PKG / "Detection.java", JAVA_PKG / "DetectorStages.java"]
        c = subprocess.run(jvm_harness.javac_cmd(jdk[0], td / "cls", src, [JAVA_HARNESS]),
                           capture_output=True, text=True)
        if c.returncode != 0:
            raise BenchError(f"!! DetectorTiming failed to compile:\n{c.stderr[-2000:]}")
        (td / "names.txt").write_text("\n".join(names) + "\n")
        print("| variant | output floats | copy ms (2 copies, as OnnxTensor.getFloatBuffer + get) |"
              " decode ms | CHW float loop ms | Java = numpy detections |")
        print("|---|---:|---:|---:|---:|---|")
        for v, res in results.items():
            frame, arr = next(iter(res["outs"].items()))
            bin_path = td / f"{v}.bin"
            arr.astype("<f4").tofile(bin_path)
            kind = "topk" if res["topk"] else "raw"
            # The input size: what the CHW loop or the RGBA copy fills, and the
            # raw decoder's box frame (raw outputs only come from CHW models).
            h, w = res["hw"]
            r = subprocess.run([jdk[1], "-cp", str(td / "cls"), "com.miko3.mode.explore.DetectorTiming",
                                str(td / "names.txt"), str(bin_path), kind, str(arr.shape[-1]), str(w), str(h),
                                str(MIN_SCORE), "rgba" if res["rgba"] else "chw"], capture_output=True, text=True, timeout=300)
            if r.returncode != 0:
                raise BenchError(f"!! DetectorTiming failed for {v}:\n{r.stderr[-2000:]}")
            vals = dict(line.split(" ", 2)[1:] for line in r.stdout.splitlines() if line.startswith("TIMING "))
            jdets = [line[len("DET "):] for line in r.stdout.splitlines() if line.startswith("DET ")]
            pdets = [f"{n} {s:.4f}" for n, s, _ in res["dets"][frame]]
            same = [d.rsplit(" ", 4)[0] for d in jdets] == pdets
            print(f"| {v} | {arr.size} | {vals.get('copy_ms')} | {vals.get('decode_ms')} | "
                  f"{vals.get('prep_ms')} | {'yes' if same else 'NO'} |")
            out[v] = {**vals, "same_detections": same}
    print("\nHost JVM (HotSpot JIT) is far faster than ART on a Cortex-A53; read these as relative costs.")
    return out


# ---- build ----

def build(frames, small):
    import onnx
    VARIANTS.mkdir(parents=True, exist_ok=True)
    base = onnx.load(str(DETECTOR))
    names = read_names()
    if _classes(base) != len(names):
        raise BenchError(f"!! detector.onnx has {_classes(base)} names, vocabulary.txt {len(names)}")
    pruned = make_pruned(base)
    topk = make_topk(pruned)
    rgba = make_rgba(topk)
    rgba640 = make_rgba(topk, CAMERA)
    for v, m in (("pruned", pruned), ("topk", topk), ("rgba", rgba), ("rgba640", rgba640)):
        _save(m, v)
    calib = [p for p in frames if is_robot_frame(p)] or frames
    with tempfile.TemporaryDirectory(prefix="detector_int8_") as td:
        quantize_int8(VARIANTS / asset_name("rgba"), VARIANTS / asset_name("int8"), calib, td)
    print(f"wrote {asset_name('int8')} (calibrated on {len(calib)} robot frames)")
    if small:
        export_small()


def _save(model, variant):
    import onnx
    onnx.checker.check_model(model)
    path = VARIANTS / asset_name(variant)
    onnx.save(model, str(path))
    print(f"wrote {path.relative_to(REPO)} ({path.stat().st_size // 1024} KB)")


def export_small(hw=(256, 320)):
    """Re-export YOLOE at a smaller size with the same vocabulary, then wrap it
    like rgba. Needs the YOLOE weights in tools/detector-export (the export's cwd)."""
    import onnx
    if not (EXPORT_DIR / "yoloe-26n-seg.pt").exists():
        print("(no tools/detector-export/yoloe-26n-seg.pt; s256 skipped)")
        return
    raw = VARIANTS / "detector-s256-raw.onnx"
    subprocess.run([str(VENV_PYTHON), str(REPO / "scripts" / "export-explore-detector.py"),
                    "--imgsz", str(hw[0]), str(hw[1]), "--out", str(raw)], cwd=EXPORT_DIR, check=True)
    m = make_rgba(make_topk(make_pruned(onnx.load(str(raw)))))
    _save(m, "s256")


def ship(variants, report_path):
    """Copy variants into mode-explore/assets/ — only ones a bench report passed."""
    if not report_path or not Path(report_path).exists():
        raise BenchError("!! ship needs --report from `bench --json` so only checked variants ship")
    report = json.loads(Path(report_path).read_text())
    for v in variants:
        r = report["variants"].get(v)
        if r is None or not r["ship_ok"]:
            raise BenchError(f"!! {v} did not pass the accuracy check ({r and r['ship_why']}); not shipping")
        shutil.copy(VARIANTS / asset_name(v), ASSETS / asset_name(v))
        print(f"shipped {asset_name(v)} ({(ASSETS / asset_name(v)).stat().st_size // 1024} KB)")


# ---- venv ----

def ensure_env(argv):
    try:
        import numpy  # noqa: F401
        import onnx  # noqa: F401
        import onnxruntime  # noqa: F401
        import cv2  # noqa: F401
        return
    except ImportError:
        pass
    if os.environ.get("DETECTOR_BENCH_IN_VENV") or not VENV_PYTHON.exists():
        raise BenchError(f"!! needs numpy, onnx, onnxruntime and OpenCV: create {VENV_PYTHON.parent.parent} "
                         "as scripts/export-explore-detector.py describes")
    os.environ["DETECTOR_BENCH_IN_VENV"] = "1"
    os.execv(str(VENV_PYTHON), [str(VENV_PYTHON), str(Path(__file__).resolve())] + list(argv))


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    b = sub.add_parser("build", help="write the variants into out/detector-variants/")
    b.add_argument("--small", action="store_true", help="also re-export at 256x320 (s256)")
    r = sub.add_parser("bench", help="stage breakdown and the variant table")
    r.add_argument("--rounds", type=int, default=5)
    r.add_argument("--java", action="store_true", help="also time the Java stages on the host JVM")
    r.add_argument("--json", metavar="FILE", help="write the numbers as JSON (ship reads it)")
    s = sub.add_parser("ship", help="copy checked variants into mode-explore/assets/")
    s.add_argument("variants", nargs="+", choices=VARIANT_NAMES)
    s.add_argument("--report", required=True, help="the bench --json report")
    for p in (b, r):
        p.add_argument("frames", nargs="*", help="JPEGs (default: the bench set, robot captures, test images)")
    args = ap.parse_args(argv)
    try:
        ensure_env(argv)
        if args.cmd == "ship":
            ship(args.variants, args.report)
            return 0
        frames = [Path(f) for f in args.frames] or default_frames()
        if not frames:
            raise BenchError("!! no frames found; pass some JPEGs")
        if args.cmd == "build":
            build(frames, args.small)
        else:
            bench(frames, args.rounds, args.java, args.json)
    except BenchError as e:
        print(e, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
