"""Tests for scripts/bench-explore-detector.py (detector speed plan): its numpy-free
helpers (nearest-rank percentile, YoloeDecoder's suppression, the agreement
count and the ship gate), that its variant names are ones the app will load, and
that every variant shipped in mode-explore/assets is one it builds. With
numpy/onnx/onnxruntime importable (the detector export venv) it also checks the
graph surgery on a tiny synthetic head: the pruned, top-k and RGBA variants give
the same detections as the original."""
import importlib.util
import re
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
SCRIPT = HERE.parent / "bench-explore-detector.py"
PKG = REPO / "mode-explore" / "src" / "com" / "miko3" / "mode" / "explore"
ASSETS = REPO / "mode-explore" / "assets"


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


bench = load("bench_explore_detector", SCRIPT)


def have(*mods):
    return all(importlib.util.find_spec(m) is not None for m in mods)


class HelpersTest(unittest.TestCase):
    def test_percentile_is_nearest_rank_like_detector_stages(self):
        self.assertEqual(bench.percentile([50, 10, 40, 20, 30], 50), 30)
        self.assertEqual(bench.percentile([50, 10, 40, 20, 30], 95), 50)
        self.assertEqual(bench.percentile(list(range(1, 21)), 95), 19)
        self.assertEqual(bench.percentile([], 50), 0.0)

    def test_nms_merges_same_name_only_and_caps(self):
        a = (0, 0.9, (0.0, 0.0, 0.5, 0.5))
        b = (0, 0.8, (0.01, 0.01, 0.5, 0.5))
        c = (1, 0.7, (0.0, 0.0, 0.5, 0.5))
        self.assertEqual(bench.nms([b, c, a]), [a, c])
        many = [(i, 1 - i / 100, (0, 0, 1, 1)) for i in range(30)]
        self.assertEqual(len(bench.nms(many)), bench.MAX_COUNT)

    def test_agreement_matches_by_name_and_overlap(self):
        ref = [("person", 0.8, (0, 0, 0.5, 0.5)), ("cup", 0.4, (0.6, 0.6, 0.7, 0.7))]
        got = [("person", 0.75, (0.01, 0, 0.5, 0.5)), ("cup", 0.5, (0.0, 0.0, 0.1, 0.1)), ("tv", 0.3, (0, 0, 1, 1))]
        a = bench.agreement(ref, got)
        self.assertEqual((a["matched"], a["extra"], a["person_ref"], a["person_hit"]), (1, 2, 1, 1))
        self.assertAlmostEqual(a["deltas"][0], -0.05)

    def test_ship_gate_needs_every_person_recall_and_few_extras(self):
        ok = {"ref": 20, "matched": 19, "extra": 2, "person_ref": 3, "person_hit": 3}
        self.assertTrue(bench.ship_verdict(ok)[0])
        self.assertFalse(bench.ship_verdict({**ok, "person_hit": 2})[0])
        self.assertFalse(bench.ship_verdict({**ok, "matched": 16})[0])
        self.assertFalse(bench.ship_verdict({**ok, "extra": 4})[0])

    def test_variant_asset_names_are_ones_the_app_loads(self):
        java = (PKG / "DetectorConfig.java").read_text()
        pattern = re.search(r'MODEL_NAME = Pattern.compile\("(.*)"\);', java).group(1).replace("\\\\", "\\")
        self.assertEqual(bench.asset_name("fp32"), "detector.onnx")
        for v in bench.VARIANT_NAMES:
            self.assertRegex(bench.asset_name(v), f"^{pattern}$")

    def test_only_built_variants_ship(self):
        shipped = sorted(p.name for p in ASSETS.glob("detector-*.onnx"))
        known = {bench.asset_name(v) for v in bench.VARIANT_NAMES}
        self.assertTrue(set(shipped) <= known, shipped)
        # int8 and s256 failed the accuracy check on 2026-09-30: never shipped.
        self.assertNotIn("detector-int8.onnx", shipped)
        self.assertNotIn("detector-s256.onnx", shipped)


@unittest.skipUnless(have("numpy", "onnx", "onnxruntime"), "needs numpy, onnx and onnxruntime (export venv)")
class GraphSurgeryTest(unittest.TestCase):
    """A tiny stand-in for the YOLOE head: Conv on the input, then boxes, score
    logits and mask coefficients concatenated into output0, plus an output1."""
    H, W, C, A = 8, 12, 3, 96

    def _model(self):
        import numpy as np
        import onnx
        from onnx import TensorProto, helper, numpy_helper
        rng = np.random.default_rng(1)
        H, W, C, A = self.H, self.W, self.C, self.A
        conv_w = rng.normal(size=(4 + C + 32, 3, 1, 1)).astype(np.float32)
        nodes = [
            helper.make_node("Conv", ["images", "w"], ["feat"], name="/model.0/conv/Conv"),
            helper.make_node("Reshape", ["feat", "shape"], ["flat"], name="/model.23/Reshape"),
            helper.make_node("Slice", ["flat", "s0", "s4", "ax"], ["boxraw"]),
            helper.make_node("Mul", ["boxraw", "scale"], ["boxes"]),
            helper.make_node("Slice", ["flat", "s4", "s4c", "ax"], ["logits"]),
            helper.make_node("Sigmoid", ["logits"], ["scores"], name="/model.23/Sigmoid"),
            helper.make_node("Slice", ["flat", "s4c", "send", "ax"], ["mask"]),
            helper.make_node("Concat", ["boxes", "scores", "mask"], ["output0"], axis=1, name="/model.23/Concat_4"),
            helper.make_node("Relu", ["feat"], ["output1"], name="/model.23/proto"),
        ]
        inits = [numpy_helper.from_array(conv_w, "w"),
                 numpy_helper.from_array(np.array([1, 4 + C + 32, A], dtype=np.int64), "shape"),
                 numpy_helper.from_array(np.array([0], dtype=np.int64), "s0"),
                 numpy_helper.from_array(np.array([4], dtype=np.int64), "s4"),
                 numpy_helper.from_array(np.array([4 + C], dtype=np.int64), "s4c"),
                 numpy_helper.from_array(np.array([4 + C + 32], dtype=np.int64), "send"),
                 numpy_helper.from_array(np.array([1], dtype=np.int64), "ax"),
                 numpy_helper.from_array(np.array([W, H, W, H], dtype=np.float32).reshape(1, 4, 1), "scale")]
        g = helper.make_graph(nodes, "head", [helper.make_tensor_value_info("images", TensorProto.FLOAT,
                                                                          [1, 3, H, W])],
                              [helper.make_tensor_value_info("output0", TensorProto.FLOAT, [1, 4 + C + 32, A]),
                               helper.make_tensor_value_info("output1", TensorProto.FLOAT, [1, 4 + C + 32, H, W])],
                              inits)
        # IR 10 (onnxruntime 1.30 reads up to 13; a newer onnx would stamp 14).
        return helper.make_model(g, opset_imports=[helper.make_opsetid("", 18)], ir_version=10)

    def _run(self, model, feed):
        import onnxruntime as ort
        s = ort.InferenceSession(model.SerializeToString(), providers=["CPUExecutionProvider"])
        return s.run(None, feed)

    def test_variants_decode_alike(self):
        import numpy as np
        names = ["person", "cat", "plant"]
        base = self._model()
        rng = np.random.default_rng(2)
        rgba = rng.integers(0, 256, size=(1, self.H, self.W, 4), dtype=np.uint8)
        chw = (rgba[..., :3].transpose(0, 3, 1, 2).astype(np.float32) / 255).astype(np.float32)
        out0 = self._run(base, {"images": chw})[0]
        ref = bench.decode_raw(out0[:, :4 + self.C], names, (self.H, self.W), min_score=0.0)

        pruned = bench.make_pruned(base)
        self.assertEqual([o.name for o in pruned.graph.output], ["output0"])
        self.assertNotIn("/model.23/proto", [n.name for n in pruned.graph.node])
        p0 = self._run(pruned, {"images": chw})[0]
        self.assertEqual(p0.shape, (1, 4 + self.C, self.A))
        np.testing.assert_allclose(p0, out0[:, :4 + self.C], rtol=1e-6)

        topk = bench.make_topk(pruned, k=10)
        det = self._run(topk, {"images": chw})[0]
        self.assertEqual(det.shape, (1, 6, 10))
        got = bench.decode_topk(det, names, min_score=0.0)
        self.assertEqual([(n, round(s, 5)) for n, s, _ in got[:5]], [(n, round(s, 5)) for n, s, _ in ref[:5]])
        for (_, _, a), (_, _, b) in zip(got[:5], ref[:5]):
            np.testing.assert_allclose(a, b, atol=1e-5)

        rg = bench.make_rgba(topk)
        self.assertEqual(rg.graph.input[0].name, "rgba")
        det2 = self._run(rg, {"rgba": rgba})[0]
        np.testing.assert_allclose(det2, det, rtol=1e-4, atol=1e-4)


if __name__ == "__main__":
    unittest.main()
