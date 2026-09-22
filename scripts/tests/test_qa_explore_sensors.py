"""Tests for scripts/qa-explore-sensors.py's host-side parsing: pulling MikoDmdRaw
records out of logcat text and summarizing a capture window. The adb capture
itself is device-only; these cover what the summary claims about it."""
import importlib.util
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


if __name__ == "__main__":
    unittest.main()
