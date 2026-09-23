#!/usr/bin/env python3
"""Host-side tests for explore's object detector (camera curiosity, KTD2).

The harness decodes synthetic tensors in the exported YOLOE layout through
YoloeDecoder: the best name per anchor, box fractions of the frame, the score
floor, same-name non-maximum suppression, ordering and the cap, and that the
32 mask rows are never read as scores. The vocabulary checks keep the file the
exporter bakes into the model and the file the app decodes against identical in
meaning: no duplicate names, people first, and the same comment rules; and
Detection.nameClip names each clip exactly as gen-explore-voice.py writes it.
"""
import importlib.util
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

TESTS = Path(__file__).resolve().parent
if str(TESTS) not in sys.path:
    sys.path.insert(0, str(TESTS))
import jvm_harness  # noqa: E402

REPO = Path(__file__).resolve().parents[2]
EXPLORE_SRC = REPO / "mode-explore" / "src"
PKG = EXPLORE_SRC / "com" / "miko3" / "mode" / "explore"
HARNESS = TESTS / "fixtures" / "explore_detector_harness" / "src"
HARNESS_MAIN = HARNESS / "com" / "miko3" / "mode" / "explore" / "DetectorHarness.java"
PLAIN_JAVA = [PKG / "YoloeDecoder.java", PKG / "Detection.java"]
VOCABULARY = REPO / "mode-explore" / "assets" / "vocabulary.txt"
EXPORT_PY = REPO / "scripts" / "export-explore-detector.py"
VOICE_PY = REPO / "scripts" / "gen-explore-voice.py"


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


export = load("export_explore_detector", EXPORT_PY)
voice = load("gen_explore_voice_for_detector", VOICE_PY)
NAMES = export.read_vocabulary(VOCABULARY)


class DetectorDecodeTest(unittest.TestCase):
    SCENARIOS = (
        "decodes_best_name_and_frame_fractions",
        "below_threshold_dropped",
        "same_name_overlaps_merged",
        "different_names_overlapping_both_kept",
        "far_apart_same_name_both_kept",
        "highest_score_first_and_capped",
        "short_output_rejected",
        "detection_clamps_to_frame",
        "center_x_spans_minus_one_to_one",
    )

    @classmethod
    def setUpClass(cls):
        jdk = jvm_harness.find_jdk()
        if jdk is None:
            raise unittest.SkipTest("no JDK (javac + java) found")
        cls.results = {}
        cls.run_output = ""
        with tempfile.TemporaryDirectory(prefix="explore_detector_harness_") as out:
            c = subprocess.run(jvm_harness.javac_cmd(jdk[0], out, [HARNESS_MAIN] + PLAIN_JAVA, [HARNESS]),
                               capture_output=True, text=True)
            cls.compiled = c.returncode == 0
            cls.compile_output = (c.stdout + c.stderr)[-3000:]
            if cls.compiled:
                r = subprocess.run([jdk[1], "-cp", out, "com.miko3.mode.explore.DetectorHarness"] + NAMES,
                                   capture_output=True, text=True, timeout=60)
                cls.run_output = (r.stdout + r.stderr)[-6000:]
                cls.results = jvm_harness.parse_verdicts(r.stdout)
                cls.name_clips = dict(line[len("NAMECLIP "):].split("\t")
                                      for line in r.stdout.splitlines() if line.startswith("NAMECLIP "))

    def setUp(self):
        self.assertTrue(self.compiled, f"harness failed to compile:\n{self.compile_output}")

    def _assert_pass(self, name):
        verdict, detail = self.results.get(name, ("MISSING", self.run_output))
        self.assertEqual(verdict, "PASS", detail)

    def test_harness_ran_exactly_the_listed_scenarios(self):
        self.assertEqual(sorted(self.results), sorted(self.SCENARIOS), self.run_output)

    def test_app_and_voice_generator_name_every_clip_alike(self):
        self.assertEqual(len(self.name_clips), len(NAMES), self.run_output)
        for name in NAMES:
            self.assertEqual(self.name_clips[name], voice.clip_name(name), name)
        self.assertEqual(self.name_clips["rubik's cube"], "name-rubik-s-cube.webm")


jvm_harness.add_scenario_tests(DetectorDecodeTest)


class VocabularyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.export = export
        cls.names = NAMES

    def test_exporter_reads_the_shipped_vocabulary(self):
        self.assertEqual(self.export.VOCABULARY, VOCABULARY)

    def test_a_few_hundred_names_with_people_first(self):
        self.assertGreaterEqual(len(self.names), 300)
        self.assertEqual(self.names[0], "person")

    def test_names_are_lowercase_single_line(self):
        for n in self.names:
            self.assertEqual(n, n.strip().lower(), n)
            self.assertNotIn("#", n)

    def test_voice_generator_reads_the_same_names(self):
        self.assertEqual(list(voice.VOCABULARY), self.names)

    def test_duplicate_names_are_rejected(self):
        with tempfile.TemporaryDirectory() as td:
            p = Path(td) / "v.txt"
            p.write_text("# header\nperson\n\ncat\nperson\n")
            with self.assertRaises(SystemExit):
                self.export.read_vocabulary(p)

    def test_comments_and_blanks_are_skipped_like_the_app_does(self):
        # OnnxRecognizer.readVocabulary: trim, skip empty and '#'-led lines.
        java = (PKG / "OnnxRecognizer.java").read_text()
        self.assertIn('!line.isEmpty() && !line.startsWith("#")', java)
        with tempfile.TemporaryDirectory() as td:
            p = Path(td) / "v.txt"
            p.write_text("# people\n  person  \n\n# pets\ncat\n")
            self.assertEqual(self.export.read_vocabulary(p), ["person", "cat"])


if __name__ == "__main__":
    unittest.main()
