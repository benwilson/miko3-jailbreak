"""Tests for scripts/qa-explore-mode.py's host-side logic (explore plan U8): the
threshold suggestion from calibration captures, the calibration file it writes
(merged with the gyro keys, explore nav plan U1), step selection, and the nav
step's log parsing and roam summary (explore nav plan U8). The adb
steps themselves are owner-attended on the robot."""
import contextlib
import importlib.util
import io
import tempfile
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


class CalibrationPushTest(unittest.TestCase):
    """explore nav plan U1: the push merges into the robot's file, keeping the gyro keys
    qa-explore-sensors.py --gyro-circle wrote there."""

    def test_pushing_floor_keys_keeps_the_gyro_keys(self):
        files = {}
        staged = {}
        path = f"/data/data/{qa.PACKAGE}/files/{qa.CAL_NAME}"
        files[path] = "#stored by the app\ngyroAxis=z\ngyroSign=-1\ngyroCountSecondsPer360=4512.25\nobstacleTofBelow=10\n"

        def fake_adb(*args, check=True):
            if args[:2] == ("shell", "cat"):
                return files.get(args[2], "")
            if args[0] == "push":
                staged[args[2]] = Path(args[1]).read_text()
            elif args[:2] == ("shell", "cp"):
                files[args[3]] = staged[args[2]]
            elif args[:2] == ("shell", "stat"):
                return "10090:10090\n"
            return ""

        robot = qa.Robot("serial")
        robot.adb = fake_adb
        robot.push_calibration({"obstacleTofBelow": 120, "edgeTofAbove": -1, "edgeIr": 0, "edgeIrAbove": True})
        props = qa.sensors_module().parse_properties(files[path])
        self.assertEqual(props, {"gyroAxis": "z", "gyroSign": "-1", "gyroCountSecondsPer360": "4512.25",
                                 "obstacleTofBelow": "120", "edgeTofAbove": "-1", "edgeIr": "0",
                                 "edgeIrAbove": "true"})


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


def brain(stamp, msg, tag="ExploreBrain", level="I"):
    """A threadtime logcat line, the format the nav step's stream uses."""
    return f"09-25 {stamp}  4321  4400 {level} {tag}: {msg}"


# Built from the brain's own note() strings (ExploreBrain, explore nav plan U4-U7, U10).
ROAM_LOG = "\n".join([
    brain("16:00:00.000", "coverage: 3 cells"),
    brain("16:00:01.000", "steer: left 20 deg, open 0.85, leg x1.00, new 0.40"),
    brain("16:00:02.000", "look in 612 ms: [chair 0.51 [0.1,0.2,0.3,0.4]]", tag="ExploreCamera"),
    brain("16:00:02.100", "openness in 18 ms", tag="ExploreCamera"),
    brain("16:00:03.000", "re-aim: open space 25 deg LEFT, turning 25 deg, then 12 ticks more"),
    brain("16:00:04.000", "re-aim RIGHT skipped: that side is blocked"),
    brain("16:00:05.000", "controller refused forward (CPL) on plain floor: a hiccup; 9 ticks to go,"
                          " trying once more in 400 ms"),
    brain("16:00:06.000", "hazard while ROAM: OBSTACLE/LEFT"),
    brain("16:00:07.000", "measured turn blocked: turned 2 of 90 deg in 1500 ms"),
    brain("16:00:08.000", "hazard while TURN: CPL"),
    brain("16:00:09.000", "wedged: 3 hazards (3 hazards, 0 stalls, 0 failed escapes in a row); escaping"),
    brain("16:00:10.000", "retrace: facing 170 deg"),
    brain("16:00:11.000", "circle look 1 of 8 at 45 deg"),
    brain("16:00:12.000", "circle look 2 of 8 at 90 deg"),
    brain("16:00:13.000", "asking Claude the way out (8 frames)"),
    brain("16:00:15.000", "Claude's WAY_OUT 3: the way out is at 135 deg"),
    brain("16:00:18.000", "free after 9200 ms: drove off the way out"),
    brain("16:00:19.000", "steer: straight 0 deg, open 0.90, leg x1.00, toward the doorway"),
    brain("16:00:20.000", "coverage: 7 cells"),
    brain("16:00:21.000", "wedged: turn blocked (1 hazards, 2 stalls, 0 failed escapes in a row); escaping"),
    brain("16:00:22.000", "way out on the robot: 90 deg (from 8 looks)"),
    brain("16:00:23.000", "cornered: 1 failed escapes in a row, 0 hazards; resting 20000 ms"),
    brain("16:00:24.000", "asking Claude for an open doorway (facing 10 deg)"),
    brain("16:00:25.000", "Claude sees an OPEN_DOORWAY 2: remembered at 40 deg"),
    brain("16:00:26.000", "through the doorway at 40 deg"),
    brain("16:00:27.000", "a person while roaming: going over to meet them"),
    brain("16:00:28.000", "met someone: left alone for 600 s (1 met recently)"),
    brain("16:00:29.000", "recently-met check: JUST_MET: just met, leaving them alone"),
    brain("16:00:30.000", "camera gave no look in time while roaming; camera off for 30000 ms"),
    brain("16:00:31.000", "camera disconnected", tag="ExploreCamera", level="W"),
    brain("16:00:32.000", "line 7: first audio 1180 ms after speak() (40 ms after it started)", tag="SpeechEngine"),
    brain("16:01:00.000", "line 8: first audio 1320 ms after speak() (35 ms after it started)", tag="SpeechEngine"),
])


class ParseLogcatTest(unittest.TestCase):
    def test_threadtime_and_brief_lines_give_time_tag_and_message(self):
        rows = qa.parse_logcat(brain("16:00:01.250", "steer: left 20 deg") + "\n"
                               "09-25 16:00:02.000 I/ExploreBrain( 1234): hazard while ROAM: EDGE\n"
                               "--------- beginning of main")
        self.assertEqual([(tag, msg) for _, tag, msg in rows],
                         [("ExploreBrain", "steer: left 20 deg"), ("ExploreBrain", "hazard while ROAM: EDGE")])
        self.assertAlmostEqual(rows[1][0] - rows[0][0], 0.75)


class RoamSummaryTest(unittest.TestCase):
    def setUp(self):
        self.s = qa.roam_summary(ROAM_LOG)

    def test_counts_steering_reaims_and_cpl_hiccups(self):
        s = self.s
        self.assertEqual((s["steers"], s["steers_doorway"], s["reaims"], s["reaims_skipped"], s["cpl_hiccups"]),
                         (2, 1, 1, 1, 1))
        self.assertEqual(s["hazards"], {"OBSTACLE": 1, "CPL": 1})
        self.assertEqual((s["blocked_turns"], s["coverage_cells"]), (1, 7))

    def test_counts_escapes_retraces_scans_and_way_out_answers(self):
        s = self.s
        self.assertEqual((s["wedges"], s["free_after_ms"], s["not_freed"], s["cornered"]), (2, [9200], 1, 1))
        self.assertEqual((s["retraces"], s["circle_looks"]), (1, 2))
        self.assertEqual((s["wayout_asks"], s["wayout_answers"], s["wayout_robot"], s["wayout_unusable"]),
                         (1, 1, 1, 0))

    def test_counts_doorways_people_camera_and_first_audio(self):
        s = self.s
        self.assertEqual((s["doorway_asks"], s["doorway_seen"], s["doorway_through"]), (1, 1, 1))
        self.assertEqual((s["person_approaches"], s["met"], s["left_alone"]), (1, 1, 1))
        self.assertEqual((s["look_ms"], s["openness_ms"], s["no_look"], s["camera_errors"]), ([612], [18], 1, 1))
        self.assertEqual(s["first_audio_ms"], [1180, 1320])
        self.assertEqual(s["seconds"], 60.0)

    def test_the_owners_summary_is_counts_and_timings_only(self):
        text = "\n".join(qa.format_roam_summary(self.s))
        self.assertIn("roam summary (1.0 min of log)", text)
        self.assertIn("steers 2 (toward a doorway 1); re-aims 1", text)
        self.assertIn("CPL hiccups 1", text)
        self.assertIn("wedges 2: free after 9.2 s (0 over 30 s); not freed 1", text)
        self.assertIn("blocked turns 1; coverage 7 cells", text)
        self.assertIn("median 1.25 s", text)
        # The detector's labels stay out of it.
        self.assertNotIn("chair", text)

    def test_an_empty_log_summarises_to_zeros(self):
        s = qa.roam_summary("")
        self.assertEqual((s["steers"], s["wedges"], s["seconds"]), (0, 0, None))
        self.assertTrue(qa.format_roam_summary(s))


class WedgeTrialTest(unittest.TestCase):
    def test_free_after_a_wedge_is_timed(self):
        log = "\n".join([brain("16:00:00.000", "hazard while ROAM: OBSTACLE"),
                         brain("16:00:05.000", "wedged: 3 hazards (...); escaping"),
                         brain("16:00:17.500", "free after 12500 ms: retraced 800 counts")])
        self.assertEqual(qa.wedge_trial(log), ("free", 12500, 17500))

    def test_a_cornered_rest_after_a_wedge_is_not_freed(self):
        log = "\n".join([brain("16:00:05.000", "wedged: turn blocked (...); escaping"),
                         brain("16:00:50.000", "cornered: 1 failed escapes in a row, 0 hazards; resting 20000 ms")])
        self.assertEqual(qa.wedge_trial(log)[0], "not freed")

    def test_still_escaping_is_pending_and_a_free_without_a_wedge_does_not_count(self):
        self.assertEqual(qa.wedge_trial(brain("16:00:05.000", "wedged: x; escaping")), (None, None, None))
        self.assertEqual(qa.wedge_trial(brain("16:00:05.000", "free after 900 ms: backed out")), (None, None, None))

    def test_four_of_five_within_30_s_passes(self):
        self.assertEqual(qa.wedge_verdict([("free", 9000), ("free", 36000), ("free", 20000), ("out", None),
                                           ("free", 29999)]), (4, True))
        self.assertEqual(qa.wedge_verdict([("free", 9000), ("free", 36000), ("not freed", None), ("stuck", None),
                                           ("free", 20000)]), (2, False))
        self.assertEqual(qa.needed_passes(5), 4)
        self.assertEqual(qa.needed_passes(3), 3)


class ProbesTest(unittest.TestCase):
    def test_leftover_hooks_are_found_in_getprop(self):
        text = ("[log.tag.MikoExploreCurious]: [DEBUG]\n[log.tag.MikoExploreStale]: [INFO]\n"
                "[log.tag.MikoDmdRaw]: [VERBOSE]\n[log.tag.SomethingElse]: [DEBUG]\n[ro.build.id]: [x]\n")
        self.assertEqual(qa.leftover_hooks(text), {"MikoExploreCurious": "DEBUG", "MikoDmdRaw": "VERBOSE"})

    def test_thermal_zones_and_battery(self):
        temps = qa.thermal_temps("mtktscpu 48500\nmtktsbattery 31000\nmtktsAP 45\nbroken\n")
        self.assertEqual(temps, {"mtktscpu": 48.5, "mtktsbattery": 31.0, "mtktsAP": 45.0})
        self.assertEqual(qa.cpu_temp(temps), 48.5)
        self.assertEqual(qa.cpu_temp({"a": 40.0, "b": 52.0}), 52.0)
        self.assertIsNone(qa.cpu_temp({}))
        self.assertEqual(qa.battery_level("Current Battery Service state:\n  AC powered: false\n  level: 83\n"
                                          "  scale: 100\n"), 83)
        self.assertIsNone(qa.battery_level(""))

    def test_first_audio_near_the_idle_figure(self):
        self.assertEqual(qa.first_audio_verdict([1100, 1200, 1500]), (1.2, True))
        self.assertFalse(qa.first_audio_verdict([2500, 2800])[1])
        self.assertEqual(qa.first_audio_verdict([]), (None, False))


class FakeRobot:
    def __init__(self, getprop=""):
        self.serial = "serial"
        self.getprop = getprop
        self.calls = []

    def adb(self, *args, check=True):
        self.calls.append(args)
        return self.getprop if args == ("shell", "getprop") else ""

    def start_mode(self):
        self.calls.append(("start",))


class NavStepTest(unittest.TestCase):
    @staticmethod
    def nav(robot, opts, runners):
        with contextlib.redirect_stdout(io.StringIO()):
            return qa.step_nav(robot, opts, runners)

    def opts(self, nav=None):
        args = qa.build_parser().parse_args(["--only", "nav"] + (["--nav-steps", nav] if nav else []))
        args.nav_steps = qa.parse_nav_steps(args.nav_steps)
        return args

    def test_only_nav_is_accepted_and_runs_its_sub_steps_in_order(self):
        self.assertEqual(qa.parse_only("nav"), ["nav"])
        ran = []
        runners = {n: (lambda n: lambda robot, opts, logs: ran.append(n) or True)(n) for n in qa.NAV_STEPS}
        robot = FakeRobot()
        robot.reset_hooks = lambda: {}
        self.assertTrue(self.nav(robot, self.opts(), runners))
        self.assertEqual(ran, ["gyro", "wedge", "doorway", "person", "speech"])

    def test_nav_steps_keep_the_plan_order_and_a_failure_fails_the_step(self):
        self.assertEqual(qa.parse_nav_steps("speech, wedge"), ["wedge", "speech"])
        with self.assertRaises(ValueError):
            qa.parse_nav_steps("dance")
        runners = {"wedge": lambda r, o, l: False, "speech": lambda r, o, l: True}
        robot = FakeRobot()
        robot.reset_hooks = lambda: {}
        self.assertFalse(self.nav(robot, self.opts("wedge,speech"), runners))

    def test_a_leftover_curious_hook_is_reset_to_info_before_the_run(self):
        robot = qa.Robot("serial")
        fake = FakeRobot("[log.tag.MikoExploreCurious]: [DEBUG]\n[log.tag.MikoExploreSpin]: [INFO]\n")
        robot.adb = fake.adb
        robot.start_mode = fake.start_mode
        seen = []
        self.nav(robot, self.opts("speech"), {"speech": lambda r, o, l: seen.append(list(fake.calls)) or True})
        before = seen[0]
        self.assertIn(("shell", "setprop", "log.tag.MikoExploreCurious", "INFO"), before)
        self.assertLess(before.index(("shell", "setprop", "log.tag.MikoExploreCurious", "INFO")),
                        before.index(("start",)))
        self.assertNotIn(("shell", "setprop", "log.tag.MikoExploreSpin", "INFO"), before)

    def test_roam_summary_runs_alone_and_reads_a_log_file(self):
        self.assertEqual(qa.parse_only("roam-summary"), ["roam-summary"])
        with self.assertRaises(ValueError):
            qa.parse_only("nav,roam-summary")
        self.assertNotIn("roam-summary", qa.parse_only(None))
        with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False) as f:
            f.write(ROAM_LOG)
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            qa.main(["--only", "roam-summary", "--log", f.name])
        Path(f.name).unlink()
        self.assertIn("coverage 7 cells", out.getvalue())

    def test_bad_nav_arguments_exit(self):
        with self.assertRaises(SystemExit):
            qa.main(["--only", "nav", "--nav-steps", "dance"])
        with self.assertRaises(SystemExit):
            qa.main(["--only", "nav", "--wedge-trials", "0"])


if __name__ == "__main__":
    unittest.main()
