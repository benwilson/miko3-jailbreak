"""Tests for scripts/qa-detector-bench.py's host-side logic (detector speed
plan): parsing Explore's ExploreBench log lines, collecting a run, the table it
prints, and that its property names, tag and package mirror the app's. The adb
run itself needs the robot."""
import contextlib
import importlib.util
import io
import re
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
SCRIPT = HERE.parent / "qa-detector-bench.py"
PKG = REPO / "mode-explore" / "src" / "com" / "miko3" / "mode" / "explore"


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


qa = load("qa_detector_bench", SCRIPT)

START = ("09-30 18:00:00.000 I/ExploreBench( 4321): bench start frames=4 source=assets rounds=5 "
         "configs=cpu/detector.onnx,cpu/detector-rgba.onnx,xnnpack/detector.onnx,xnnpack/detector-rgba.onnx "
         "(no camera)")


def result(ep, model, total, run, first=1700):
    return (f"I/ExploreBench( 4321): bench result ep={ep} model={model} frames=4 rounds=5 looks=20 "
            f"load_ms=812 first_ms={first} total={total} decode=31.0/40.5 prep=12.0/15.1 run={run} "
            f"copy=21.3/30.0 detect=16.0/20.2")


def dets(ep, model, frame, found):
    return f"I/ExploreBench( 4321): bench detections ep={ep} model={model} frame={frame}: {found}"


LOG = [
    "--------- beginning of main",
    START,
    dets("cpu", "detector.onnx", "person-desk.jpg", "[person 0.85 [0.10,0.20,0.30,0.40], rubik's cube 0.31 "
         "[0.50,0.50,0.60,0.60]]"),
    dets("cpu", "detector.onnx", "wall.jpg", "[]"),
    result("cpu", "detector.onnx", "1080.5/1400.2", "1000.2/1300.0"),
    dets("cpu", "detector-rgba.onnx", "person-desk.jpg", "[rubik's cube 0.30 [0.50,0.50,0.60,0.60], person 0.84 "
         "[0.10,0.20,0.30,0.40]]"),
    dets("cpu", "detector-rgba.onnx", "wall.jpg", "[]"),
    result("cpu", "detector-rgba.onnx", "620.0/700.0", "560.0/640.0"),
    dets("xnnpack", "detector.onnx", "person-desk.jpg", "[chair 0.40 [0.10,0.20,0.30,0.40]]"),
    dets("xnnpack", "detector.onnx", "wall.jpg", "[]"),
    result("xnnpack", "detector.onnx", "900.0/1100.0", "820.0/1000.0"),
    "W/ExploreBench( 4321): bench error ep=xnnpack model=detector-rgba.onnx: ai.onnxruntime.OrtException: boom",
    "I/ExploreDetector( 4321): detector ep=xnnpack model=detector.onnx",
    "I/ExploreBench( 4321): bench done in 123456 ms",
]


class ParseEventTest(unittest.TestCase):
    def test_result_fields_and_pairs(self):
        kind, f = qa.parse_event(LOG[4])
        self.assertEqual(kind, "result")
        self.assertEqual((f["ep"], f["model"]), ("cpu", "detector.onnx"))
        self.assertEqual(f["total"], (1080.5, 1400.2))
        self.assertEqual(f["run"], (1000.2, 1300.0))
        self.assertEqual((f["frames"], f["looks"], f["load_ms"], f["first_ms"]), (4, 20, 812, 1700))
        for stage in qa.STAGES:
            self.assertIn(stage, f)

    def test_detections_with_spaces_and_apostrophes_in_names(self):
        kind, f = qa.parse_event(LOG[2])
        self.assertEqual(kind, "detections")
        self.assertEqual(f["frame"], "person-desk.jpg")
        self.assertEqual(f["found"], [("person", 0.85), ("rubik's cube", 0.31)])
        self.assertEqual(qa.parse_event(LOG[3])[1]["found"], [])

    def test_start_error_done(self):
        kind, f = qa.parse_event(START)
        self.assertEqual((kind, f["frames"], f["source"], f["rounds"]), ("start", "4", "assets", "5"))
        kind, f = qa.parse_event(LOG[11])
        self.assertEqual((kind, f["ep"], f["model"]), ("error", "xnnpack", "detector-rgba.onnx"))
        self.assertIn("OrtException: boom", f["message"])
        kind, f = qa.parse_event("W/ExploreBench( 1): bench error: no frames")
        self.assertEqual((kind, f["message"]), ("error", "no frames"))
        self.assertEqual(qa.parse_event(LOG[-1]), ("done", {"ms": 123456}))
        self.assertEqual(qa.parse_event("I/ExploreBench( 1): bench cancelled in 99 ms"), ("cancelled", {"ms": 99}))

    def test_other_lines_ignored(self):
        for line in ("--------- beginning of main", LOG[12], "I/ExploreCamera( 1): look in 1295 ms: []",
                     "I/ExploreModeApp( 1): detector bench requested -- no camera, no driving this run"):
            self.assertIsNone(qa.parse_event(line), line)


class CollectAndTableTest(unittest.TestCase):
    def setUp(self):
        self.run = qa.collect(LOG)

    def test_collects_every_config(self):
        self.assertEqual(set(self.run["results"]), {("cpu", "detector.onnx"), ("cpu", "detector-rgba.onnx"),
                                                    ("xnnpack", "detector.onnx")})
        self.assertEqual(self.run["errors"], {("xnnpack", "detector-rgba.onnx"):
                                              "ai.onnxruntime.OrtException: boom"})
        self.assertEqual(self.run["done"], 123456)
        self.assertFalse(self.run["cancelled"])

    def test_labels_agree_ignores_order_and_scores(self):
        base = self.run["detections"][("cpu", "detector.onnx")]
        self.assertEqual(qa.labels_agree(base, self.run["detections"][("cpu", "detector-rgba.onnx")]),
                         {"person-desk.jpg": True, "wall.jpg": True})
        self.assertEqual(qa.labels_agree(base, self.run["detections"][("xnnpack", "detector.onnx")]),
                         {"person-desk.jpg": False, "wall.jpg": True})

    def test_table_fastest_first_with_speedup_budget_and_names(self):
        table = qa.format_table(self.run)
        rows = [r for r in table.splitlines() if r.startswith("| cpu/") or r.startswith("| xnnpack/")]
        self.assertTrue(rows[0].startswith("| cpu/detector-rgba.onnx |"), table)
        self.assertIn("| 620/700 |", rows[0])
        self.assertIn("| 1.74x | yes | yes |", rows[0])
        self.assertIn("| 1.00x | no | - |", [r for r in rows if r.startswith("| cpu/detector.onnx")][0])
        self.assertIn("no: person-desk.jpg", [r for r in rows if r.startswith("| xnnpack/detector.onnx")][0])
        self.assertIn("| xnnpack/detector-rgba.onnx | error: ai.onnxruntime.OrtException: boom |", table)

    def test_summary_flags_a_missing_start_or_end(self):
        self.assertIn("bench took 123 s", qa.summary_text(self.run))
        self.assertIn("did not run the bench", qa.summary_text(qa.collect(LOG[2:])))
        self.assertIn("did not finish", qa.summary_text(qa.collect(LOG[:-1])))

    def test_parse_mode_prints_the_table_from_a_saved_log(self):
        with tempfile.TemporaryDirectory() as td:
            p = Path(td) / "bench.log"
            p.write_text("\n".join(LOG) + "\n")
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                self.assertEqual(qa.main(["--parse", str(p)]), 0)
        self.assertIn("| cpu/detector-rgba.onnx |", out.getvalue())

    def test_push_model_refuses_names_the_app_would_not_load(self):
        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            self.assertEqual(qa.main(["--push-model", "out/face_sface.onnx"]), 1)
        self.assertIn("detector-<variant>.onnx", err.getvalue())


class MirrorsTheAppTest(unittest.TestCase):
    def test_property_names_tag_and_stage_names(self):
        config = (PKG / "DetectorConfig.java").read_text()
        bench = (PKG / "DetectorBench.java").read_text()
        stages = (PKG / "DetectorStages.java").read_text()
        for prop in qa.PROPERTIES:
            self.assertIn(f'"{prop}"', config)
        self.assertIn(f'TAG = "{qa.TAGS[0]}"', bench)
        self.assertIn('DIR = "bench"', bench)
        self.assertTrue(qa.REMOTE_DIR.endswith(f"/{qa.PACKAGE}/files/bench"))
        names = re.search(r"STAGES = \{([^}]*)\}", stages).group(1)
        self.assertEqual(tuple(n.strip().strip('"') for n in names.split(",")), qa.STAGES)


if __name__ == "__main__":
    unittest.main()
