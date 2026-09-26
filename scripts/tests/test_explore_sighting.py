#!/usr/bin/env python3
"""Host-side tests for Sighting (camera curiosity, KTD6): the most prominent
confident detection (largest, ties to the most centred), unsure looks (R13),
frame fill (R6), the people/pets set (R11), the cool-down skip (R12), and
background surfaces that are never a target. The people/pets set must be
exactly the vocabulary file's first section, and every background name must
be in the vocabulary, so the file and the code cannot drift apart.
"""
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
PKG = REPO / "mode-explore" / "src" / "com" / "miko3" / "mode" / "explore"
HARNESS = TESTS / "fixtures" / "explore_sighting_harness" / "src"
HARNESS_MAIN = HARNESS / "com" / "miko3" / "mode" / "explore" / "SightingHarness.java"
# ExploreTuning carries the gyro calibration (explore nav plan U2), which reads SensorReading.
PLAIN_JAVA = [PKG / n for n in ("Sighting.java", "Detection.java", "ExploreTuning.java", "ExploreCalibration.java",
                                 "SensorReading.java")]
VOCABULARY = REPO / "mode-explore" / "assets" / "vocabulary.txt"


def vocabulary_sections():
    """{section header: [names]} in file order."""
    sections, current = {}, None
    for line in VOCABULARY.read_text().splitlines():
        line = line.strip()
        if line.startswith("#"):
            current = line.lstrip("# ")
        elif line and current is not None:
            sections.setdefault(current, []).append(line)
    return sections


class SightingTest(unittest.TestCase):
    SCENARIOS = (
        "largest_confident_box_wins",
        "tie_goes_to_more_centred",
        "low_scores_only_is_unsure",
        "no_boxes_is_nothing",
        "below_unsure_floor_is_nothing",
        "confident_small_beats_unsure_large",
        "background_is_never_a_target",
        "cooldown_skips_people_and_pets",
        "people_and_pets_set",
        "tall_box_fills_frame",
        "wide_large_box_fills_frame",
        "small_central_box_does_not_fill",
    )

    @classmethod
    def setUpClass(cls):
        jdk = jvm_harness.find_jdk()
        if jdk is None:
            raise unittest.SkipTest("no JDK (javac + java) found")
        cls.results, cls.sets, cls.run_output = {}, {}, ""
        with tempfile.TemporaryDirectory(prefix="explore_sighting_harness_") as out:
            c = subprocess.run(jvm_harness.javac_cmd(jdk[0], out, [HARNESS_MAIN] + PLAIN_JAVA, [HARNESS]),
                               capture_output=True, text=True)
            cls.compiled = c.returncode == 0
            cls.compile_output = (c.stdout + c.stderr)[-3000:]
            if cls.compiled:
                r = subprocess.run([jdk[1], "-cp", out, "com.miko3.mode.explore.SightingHarness", "--sets"],
                                   capture_output=True, text=True, timeout=60)
                cls.run_output = (r.stdout + r.stderr)[-6000:]
                cls.results = jvm_harness.parse_verdicts(r.stdout)
                for line in r.stdout.splitlines():
                    key, _, rest = line.partition(" ")
                    if key in ("PEOPLE_AND_PETS", "BACKGROUND"):
                        cls.sets[key] = set(rest.split("|"))

    def setUp(self):
        self.assertTrue(self.compiled, f"harness failed to compile:\n{self.compile_output}")

    def _assert_pass(self, name):
        verdict, detail = self.results.get(name, ("MISSING", self.run_output))
        self.assertEqual(verdict, "PASS", detail)

    def test_harness_ran_exactly_the_listed_scenarios(self):
        self.assertEqual(sorted(self.results), sorted(self.SCENARIOS), self.run_output)

    def test_people_and_pets_are_the_vocabularys_first_section(self):
        first_header, first_names = next(iter(vocabulary_sections().items()))
        self.assertTrue(first_header.startswith("people and pets"), first_header)
        self.assertEqual(self.sets["PEOPLE_AND_PETS"], set(first_names))

    def test_background_names_are_in_the_vocabulary(self):
        names = {n for ns in vocabulary_sections().values() for n in ns}
        self.assertEqual(self.sets["BACKGROUND"] - names, set())


jvm_harness.add_scenario_tests(SightingTest)


if __name__ == "__main__":
    unittest.main()
