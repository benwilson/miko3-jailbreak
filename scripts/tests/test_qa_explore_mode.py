"""Tests for scripts/qa-explore-mode.py's host-side logic (explore plan U8): the
threshold suggestion from calibration captures, the calibration file it writes,
and step selection. The adb steps themselves are owner-attended on the robot."""
import importlib.util
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCRIPT = HERE.parent / "qa-explore-mode.py"
FIXTURES = HERE / "fixtures" / "explore_sensor_records"


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load {path}")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


qa = load("qa_explore_mode", SCRIPT)


def cluster(tofs, ir2=0):
    return [(t, -1, ir2) for t in tofs]


class SuggestCalibrationTest(unittest.TestCase):
    def test_obstacle_threshold_sits_between_clusters_on_the_safe_side(self):
        cal, _ = qa.suggest_calibration(cluster([240, 250, 263]), cluster([40, 55]), cluster([900, 950]))
        self.assertGreater(cal["obstacleTofBelow"], 55)
        self.assertLess(cal["obstacleTofBelow"], 240)
        # Safe side: nearer the clear cluster, so he stops early rather than late.
        self.assertGreater(cal["obstacleTofBelow"], (55 + 240) / 2)

    def test_tof_edge_threshold_sits_above_clear_on_the_safe_side(self):
        cal, _ = qa.suggest_calibration(cluster([240, 263]), cluster([40]), cluster([900, 950]))
        self.assertGreater(cal["edgeTofAbove"], 263)
        self.assertLess(cal["edgeTofAbove"], (263 + 900) / 2)
        self.assertEqual(cal["edgeIr"], -1)

    def test_ir_flag_that_separates_edge_from_clear_is_preferred(self):
        # Over an edge this unit reads tof 16383 with the flag digit set.
        cal, notes = qa.suggest_calibration(cluster([240, 263], ir2=0), cluster([40], ir2=0),
                                            cluster([16383, 16383], ir2=1))
        self.assertEqual((cal["edgeIr"], cal["edgeIrAbove"]), (0, True))
        self.assertEqual(cal["edgeTofAbove"], -1)
        self.assertTrue(any("ir2" in n for n in notes))
        self.assertTrue(qa.calibration_complete(cal))

    def test_overlapping_hand_readings_leave_no_obstacle_rule(self):
        cal, notes = qa.suggest_calibration(cluster([240, 263]), cluster([230, 250]), cluster([900]))
        self.assertEqual(cal["obstacleTofBelow"], -1)
        self.assertFalse(qa.calibration_complete(cal))
        self.assertTrue(notes)

    def test_dead_sensor_during_clear_capture_refuses(self):
        cal, notes = qa.suggest_calibration(cluster([16383, 16383], ir2=1), cluster([40]), cluster([16383]))
        self.assertFalse(qa.calibration_complete(cal))
        self.assertTrue(any("clear" in n for n in notes))

    def test_captured_baseline_parses_into_readings(self):
        replies = [line.split("\t", 1)[1] for line in
                   (FIXTURES / "baseline.txt").read_text().splitlines() if "\t" in line]
        rs = qa.readings(replies)
        self.assertEqual(len(rs), len(replies))
        self.assertTrue(all(200 < r[0] < 300 and r[1] == -1 and r[2] == 0 for r in rs), rs[:3])


class CalibrationTextTest(unittest.TestCase):
    def test_properties_match_what_the_app_reads(self):
        text = qa.calibration_text({"obstacleTofBelow": 120, "edgeTofAbove": -1, "edgeIr": 0, "edgeIrAbove": True})
        self.assertEqual(text, "obstacleTofBelow=120\nedgeTofAbove=-1\nedgeIr=0\nedgeIrAbove=true\n")


class ParseOnlyTest(unittest.TestCase):
    def test_default_is_every_step_in_order(self):
        self.assertEqual(qa.parse_only(None), list(qa.ALL_STEPS))

    def test_selects_named_steps(self):
        self.assertEqual(qa.parse_only("AE3, stoptimer"), ["ae3", "stoptimer"])

    def test_unknown_step_is_rejected(self):
        with self.assertRaises(ValueError):
            qa.parse_only("ae9")


class CuriositySummaryTest(unittest.TestCase):
    LOG = "\n".join([
        "09-23 11:43:20.535 I/ExploreBrain(14459): curiosity stop: scanning",
        "09-23 11:43:21.000 I/ExploreCamera(14459): look in 1295 ms: [cup 0.60 [0.1,0.1,0.2,0.2]]",
        "09-23 11:43:22.911 I/ExploreBrain(14459): saw THING speaker 0.59 [0.47,0.37,1.00,1.00]",
        "09-23 11:43:23.000 I/ExploreBrain(14459): approaching the speaker",
        "09-23 11:43:30.000 I/ExploreBrain(14459): arrived: the speaker fills the frame",
        "09-23 11:43:31.000 I/ExploreBrain(14459): hop tick",
    ])

    def test_keeps_the_brains_curiosity_decisions_in_order(self):
        events, looked = qa.curiosity_summary(self.LOG)
        self.assertEqual(events, ["curiosity stop: scanning", "saw THING speaker 0.59 [0.47,0.37,1.00,1.00]",
                                  "approaching the speaker", "arrived: the speaker fills the frame"])
        self.assertTrue(looked)

    def test_a_stop_that_never_got_a_look_is_reported(self):
        events, looked = qa.curiosity_summary(
            "09-23 I/ExploreBrain( 1): curiosity stop: scanning\n"
            "09-23 I/ExploreBrain( 1): camera gave no look in time; curiosity off for 120000 ms")
        self.assertEqual(len(events), 2)
        self.assertFalse(looked)

    def test_in_place_reactions_and_turns_are_reported(self):
        events, looked = qa.curiosity_summary(
            "09-23 I/ExploreBrain( 1): saw THING tv 0.54 [0.71,0.41,0.94,0.66]\n"
            "09-23 I/ExploreBrain( 1): seen the tv already: disappointed\n"
            "09-23 I/ExploreBrain( 1): unsure what the cup is: puzzled\n"
            "09-23 I/ExploreBrain( 1): turning RIGHT to face the plant\n"
            "09-23 I/ExploreBrain( 1): no new look in time; ending this curiosity stop")
        self.assertEqual(len(events), 5)
        self.assertTrue(looked)

    def test_curiosity_step_is_selectable(self):
        self.assertEqual(qa.parse_only("curiosity"), ["curiosity"])


if __name__ == "__main__":
    unittest.main()
