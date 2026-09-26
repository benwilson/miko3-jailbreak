"""Tests for scripts/qa-explore-sensors.py's host-side parsing: pulling MikoDmdRaw
records out of logcat text and summarizing a capture window, plus the gyro
circle capture's math and the calibration file's read-modify-write (explore nav
plan U1). The adb capture itself is device-only; these cover what the summary
claims about it."""
import importlib.util
import random
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCRIPT = HERE.parent / "qa-explore-sensors.py"
FIXTURES = HERE / "fixtures" / "explore_sensor_records"


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load {path}")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


qa = load("qa_explore_sensors", SCRIPT)


def baseline_replies():
    return [line.split("\t", 1)[1] for line in
            (FIXTURES / "baseline.txt").read_text().splitlines() if "\t" in line]


class ExtractRecordsTest(unittest.TestCase):
    def test_keeps_only_raw_tag_lines_and_whole_replies(self):
        reply = baseline_replies()[0]
        text = ("--------- beginning of main\n"
                "09-22 15:08:03.623  2249  2338 D MikoDmdRaw: sent=POWER len=500 reply=" + reply + "\n"
                "09-22 15:08:03.700  2249  2338 I DriveController: lease acquired\n"
                "09-22 15:08:03.749  2249  2338 D MikoDmdRaw: sent=VEL1 reply=null\n")
        self.assertEqual(qa.extract_records(text), [("POWER", reply), ("VEL1", "null")])


class TofirFieldsTest(unittest.TestCase):
    def test_captured_record_gives_tof_and_marks_padding_fields_absent(self):
        fields = qa.tofir_fields(baseline_replies()[0])
        self.assertEqual(fields[0], "00251")
        self.assertIsNone(fields[1])
        self.assertEqual(fields[2], "0")

    def test_record_without_tofir_gives_none(self):
        self.assertIsNone(qa.tofir_fields("POWER=0,0,07884GSTFL=0,0"))


class SummarizeTest(unittest.TestCase):
    def test_baseline_reports_live_spread(self):
        summary = qa.summarize([("POWER", r) for r in baseline_replies()])
        self.assertIn("tof: min=", summary)
        self.assertNotIn("frozen", summary)

    def test_identical_tof_values_flag_frozen(self):
        reply = baseline_replies()[0]
        summary = qa.summarize([("POWER", reply)] * 5)
        self.assertIn("frozen", summary)

    def test_no_tofir_is_warned(self):
        self.assertIn("no TOFIR", qa.summarize([("POWER", "POWER=0,0,1")]))

    def test_cpl_carrier_is_named(self):
        summary = qa.summarize([("POWER", baseline_replies()[0]), ("VEL1", "CPL=2,XXXX")])
        self.assertIn("CPL= seen in: VEL1", summary)


# ---- explore nav plan U1: gyro readings, the --gyro-circle capture, the calibration file ----

class GyroFieldsTest(unittest.TestCase):
    def test_captured_record_gives_signed_rates(self):
        self.assertEqual(qa.gyro_fields(baseline_replies()[0]), (62, -757, 93))

    def test_negative_fields_with_leading_zeros(self):
        self.assertEqual(qa.gyro_fields("IMUGY=-000001234,0000000005,-000000001IMUMG=0"), (-1234, 5, -1))

    def test_absent_padded_or_cut_off_gyro_gives_none(self):
        for reply in ("POWER=0,0TOFIR=00251,",
                      "IMUGY=XXXXXXXXXX,XXXXXXXXXX,XXXXXXXXXXIMUMG=0",
                      "TOFIR=00251,IMUGY=0000000062,-000000757,00000",
                      "IMUGY=0000000062,-000000757IMUMG=0",
                      "IMUGY=1,--2,3IMUMG=0"):
            self.assertIsNone(qa.gyro_fields(reply), reply)


def stamp(t):
    """Seconds after 10:00:00 on 09-25 as a logcat timestamp."""
    whole = int(t)
    ms = int(round((t - whole) * 1000))
    if ms == 1000:
        whole, ms = whole + 1, 0
    return f"09-25 10:{whole // 60:02d}:{whole % 60:02d}.{ms:03d}"


def gyro_reply(x, y, z, left=0, right=0):
    def f(v):
        return f"-{abs(v):09d}" if v < 0 else f"{v:010d}"
    return (f"POWER=0,0,07884IMUAC=0,0,0XIMUGY={f(x)},{f(y)},{f(z)}IMUMG=0,0,0"
            f"TOFIR=00251,XXXX,0,XXXXLeft={left:010d},Right={right:010d},00,0")


def synthetic_circle_log(axis=2, rate=900, turn_s=5.0, turns=3, left_sign=1, bias=(55, -757, 94),
                         noise=6.0, seed=1):
    """A --gyro-circle capture as logcat text: still, LEFT, still, RIGHT, still. The yaw
    axis reads a constant-rate plateau while turning (left_sign * rate going left), all
    three axes carry the bias plus noise, and the owner's marks land a whole turn apart."""
    rnd = random.Random(seed)
    lines, t = [], 0.0
    phases = [("still", 3.0), ("LEFT", turns * turn_s + 2.0), ("still", 3.0),
              ("RIGHT", turns * turn_s + 2.0), ("still", 3.0)]
    for phase, length in phases:
        lines.append(f"{stamp(t)}  2249  2400 I ExploreBrain: spin {phase}")
        start = t
        if phase != "still":
            for k in range(turns + 1):
                lines.append(f"{stamp(start + 0.73 + k * turn_s)}  3001  3001 I MikoExploreSpinMark: mark")
        while t < start + length - 1e-9:
            v = [b + rnd.gauss(0, noise) for b in bias]
            if phase != "still":
                v[axis] += rate * left_sign * (1 if phase == "LEFT" else -1)
            lines.append(f"{stamp(t)}  2249  2338 D MikoDmdRaw: sent=POWER len=500 reply="
                         + gyro_reply(*[int(round(c)) for c in v]))
            t += 0.1 + rnd.uniform(-0.01, 0.01)
    lines.append(f"{stamp(t)}  2249  2400 I ExploreBrain: spin off")
    return "\n".join(sorted(lines))


class GyroCircleTest(unittest.TestCase):
    def test_picks_the_plateau_axis_sign_and_scale_within_one_percent(self):
        for axis, left_sign in ((2, 1), (0, -1), (1, 1)):
            with self.subTest(axis=axis, left_sign=left_sign):
                result = qa.gyro_circle(qa.parse_circle_log(synthetic_circle_log(axis=axis, left_sign=left_sign)))
                self.assertEqual(result["axis"], "xyz"[axis])
                self.assertEqual(result["sign"], left_sign)
                self.assertAlmostEqual(result["countSecondsPer360"], 900 * 5.0, delta=900 * 5.0 * 0.01)
                self.assertEqual(result["turns"], {"LEFT": 3, "RIGHT": 3})

    def test_bias_comes_from_the_still_spells(self):
        result = qa.gyro_circle(qa.parse_circle_log(synthetic_circle_log()))
        for got, want in zip(result["bias"], (55, -757, 94)):
            self.assertAlmostEqual(got, want, delta=2)

    def test_only_noise_is_the_stop_condition(self):
        # No plateau anywhere: some axis may flip sign by chance, but none stands out
        # of the still-spell noise, so there is no usable yaw signal (plan stop condition).
        with self.assertRaisesRegex(ValueError, "no usable yaw"):
            qa.gyro_circle(qa.parse_circle_log(synthetic_circle_log(rate=0)))

    def test_an_axis_that_reads_the_same_both_ways_is_not_yaw(self):
        # The plateau keeps its sign in both directions (say, the tilt of a wobble).
        text = synthetic_circle_log(rate=900)
        log = qa.parse_circle_log(text)
        right_start = next(t for t, p in log["phases"] if p == "RIGHT")
        right_end = next(t for t, p in log["phases"] if t > right_start)
        log["records"] = [(t, (g[0], g[1], g[2] + (1800 if right_start <= t < right_end else 0)), w)
                          for t, g, w in log["records"]]
        with self.assertRaisesRegex(ValueError, "no usable yaw"):
            qa.gyro_circle(log)

    def test_too_few_marks_in_a_direction_is_refused(self):
        text = "\n".join(line for line in synthetic_circle_log().splitlines()
                         if not ("MikoExploreSpinMark" in line and line > stamp(25)))
        with self.assertRaisesRegex(ValueError, "RIGHT"):
            qa.gyro_circle(qa.parse_circle_log(text))

    def test_no_still_spell_is_refused(self):
        text = "\n".join(line for line in synthetic_circle_log().splitlines() if "spin still" not in line)
        with self.assertRaisesRegex(ValueError, "still"):
            qa.gyro_circle(qa.parse_circle_log(text))

    def test_marks_during_a_halt_are_not_counted(self):
        text = synthetic_circle_log() + f"\n{stamp(4.0)}  2249  2400 I ExploreBrain: spin halted: readings stale"
        log = qa.parse_circle_log("\n".join(sorted(text.splitlines())))
        # The halt at 4 s ends the first LEFT turn's segment early; its marks are ignored.
        with self.assertRaisesRegex(ValueError, "LEFT"):
            qa.gyro_circle(log)

    def test_parse_reads_both_logcat_formats(self):
        text = ("09-25 10:00:00.000 I/ExploreBrain( 2249): spin still\n"
                "09-25 10:00:00.100  2249  2338 D MikoDmdRaw: sent=POWER len=500 reply=" + gyro_reply(1, -2, 3) + "\n"
                "09-25 10:00:00.150 I/MikoExploreSpinMark( 3001): mark\n"
                "09-25 10:00:00.200  2249  2338 D MikoDmdRaw: sent=VEL1 reply=null\n")
        log = qa.parse_circle_log(text)
        self.assertEqual(log["phases"], [(0.0, "still")])
        self.assertEqual([g for _, g, _ in log["records"]], [(1, -2, 3)])
        self.assertEqual(log["marks"], [0.15])


class StraightDriveTest(unittest.TestCase):
    def test_reports_wheel_travel_and_heading_drift_on_the_yaw_axis(self):
        lines = []
        for i in range(50):
            lines.append(f"{stamp(i * 0.1)}  2249  2338 D MikoDmdRaw: sent=POWER len=500 reply="
                         + gyro_reply(55, -757, 94 + 10, left=1000 + i * 20, right=2000 + i * 20))
        log = qa.parse_circle_log("\n".join(lines))
        summary = qa.straight_drive_summary(log, bias=(55, -757, 94),
                                            calibration={"axis": "z", "sign": 1, "countSecondsPer360": 4500.0})
        self.assertIn("wheels moved: left=980 right=980", summary)
        # 10 counts of rate for 4.9 s = 49 count-seconds = 3.9 degrees.
        self.assertIn("heading drift: 3.9 deg", summary)
        self.assertNotIn("SATURATED", summary)

    def test_flags_a_saturated_axis_and_a_still_robot(self):
        lines = [f"{stamp(i * 0.1)}  2249  2338 D MikoDmdRaw: sent=POWER len=500 reply="
                 + gyro_reply(32767, -757, 94, left=5, right=5) for i in range(10)]
        summary = qa.straight_drive_summary(qa.parse_circle_log("\n".join(lines)), bias=(55, -757, 94),
                                            calibration=None)
        self.assertIn("SATURATED", summary)
        self.assertIn("the wheels did not move", summary)


class CalibrationPropertiesTest(unittest.TestCase):
    FLOOR = "obstacleTofBelow=60\nedgeTofAbove=420\nedgeIr=-1\nedgeIrAbove=true\n"
    STORED = ("#explore mode sensor calibration (scripts/qa-explore-mode.py)\n#Thu Sep 25 10:00:00 BST 2026\n"
              "gyroSign=-1\nobstacleTofBelow=60\ngyroAxis=z\n")

    def test_parse_skips_comments_and_blank_lines(self):
        self.assertEqual(qa.parse_properties(self.STORED + "\n! bang comment\nedgeIr = 5\n"),
                         {"gyroSign": "-1", "obstacleTofBelow": "60", "gyroAxis": "z", "edgeIr": "5"})

    def test_merging_gyro_keys_keeps_the_floor_keys(self):
        merged = qa.merge_properties(self.FLOOR, qa.gyro_calibration_keys(
            {"axis": "z", "sign": -1, "countSecondsPer360": 4512.25}))
        props = qa.parse_properties(merged)
        self.assertEqual(props["obstacleTofBelow"], "60")
        self.assertEqual(props["edgeIrAbove"], "true")
        self.assertEqual((props["gyroAxis"], props["gyroSign"], props["gyroCountSecondsPer360"]),
                         ("z", "-1", "4512.25"))

    def test_merging_replaces_a_key_in_place(self):
        merged = qa.merge_properties(self.FLOOR, {"edgeTofAbove": "500"})
        self.assertEqual(merged, self.FLOOR.replace("420", "500"))

    def test_merging_into_no_file_writes_just_the_new_keys(self):
        self.assertEqual(qa.merge_properties("", {"gyroAxis": "x"}), "gyroAxis=x\n")


class FakeDevice:
    """An adb stand-in holding one calibration file, for the pull/merge/push round trip."""

    def __init__(self, text=None):
        self.files = {} if text is None else {qa.CAL_PATH: text}
        self.staged = {}

    def adb(self, *args, check=True):
        if args[:2] == ("shell", "cat"):
            return self.files.get(args[2], "")
        if args[0] == "push":
            self.staged[args[2]] = Path(args[1]).read_text()
        elif args[:2] == ("shell", "cp"):
            self.files[args[3]] = self.staged[args[2]]
        elif args[:2] == ("shell", "stat"):
            return "10090:10090\n"
        return ""


class CalibrationRoundTripTest(unittest.TestCase):
    def test_writing_gyro_keys_on_the_robot_keeps_its_floor_keys(self):
        device = FakeDevice(CalibrationPropertiesTest.FLOOR)
        qa.write_calibration_keys(device.adb, {"gyroAxis": "y", "gyroSign": "1"})
        props = qa.parse_properties(device.files[qa.CAL_PATH])
        self.assertEqual(props["obstacleTofBelow"], "60")
        self.assertEqual(props["gyroAxis"], "y")

    def test_no_file_on_the_robot_yet(self):
        device = FakeDevice()
        qa.write_calibration_keys(device.adb, {"gyroAxis": "y"})
        self.assertEqual(device.files[qa.CAL_PATH], "gyroAxis=y\n")


if __name__ == "__main__":
    unittest.main()
