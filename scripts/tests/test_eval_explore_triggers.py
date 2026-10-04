"""Tests for scripts/eval-explore-triggers.py: the bathroom rule replayed over ExploreCamera's
look lines, and its constants kept in step with ExploreBrain.java's BATHROOM_* (the rule the
robot runs). Synthetic look lines."""
import contextlib
import datetime as dt
import importlib.util
import io
import json
import re
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
BRAIN = REPO / "mode-explore" / "src" / "com" / "miko3" / "mode" / "explore" / "ExploreBrain.java"


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


ev = load("eval_explore_triggers", HERE.parent / "eval-explore-triggers.py")
nav = load("nav_report_t", HERE.parent / "nav-report.py")
BASE = dt.datetime(2026, 10, 2, 12, 0, 0)


def L(sec, msg, pid=7841, tag="ExploreCamera"):
    t = BASE + dt.timedelta(seconds=sec)
    return f"{t:%m-%d %H:%M:%S}.{t.microsecond // 1000:03d} I/{tag}({pid:>5}): {msg}\n"


def look(sec, *boxes, pid=7841):
    return L(sec, "look in 700 ms: [" + ", ".join(boxes) + "]", pid=pid)


def run(lines, **kw):
    return ev.evaluate(nav.parse_lines(lines, 2026), **kw)


class ConstantsInStepTest(unittest.TestCase):
    """The replay is only useful while it runs the robot's rule."""

    def java_value(self, name):
        m = re.search(rf"static final \w+ {name} = ([^;]+);", BRAIN.read_text())
        self.assertIsNotNone(m, name)
        return m.group(1).strip()

    def java_set(self, name):
        m = re.search(rf"static final Set<String> {name} = new HashSet<String>\(java\.util\.Arrays\.asList\((.*?)\)\);",
                      BRAIN.read_text(), re.S)
        self.assertIsNotNone(m, name)
        return tuple(re.findall(r'"([^"]+)"', m.group(1)))

    def test_label_sets(self):
        self.assertEqual(self.java_set("BATHROOM_STRONG"), ev.BATHROOM_STRONG)
        self.assertEqual(self.java_set("BATHROOM_WEAK"), ev.BATHROOM_WEAK)

    def test_thresholds_and_window(self):
        self.assertEqual(float(self.java_value("BATHROOM_STRONG_MIN").rstrip("f")), ev.BATHROOM_STRONG_MIN)
        self.assertEqual(float(self.java_value("BATHROOM_WEAK_MIN").rstrip("f")), ev.BATHROOM_WEAK_MIN)
        self.assertEqual(float(self.java_value("BATHROOM_MIN_AREA").rstrip("f")), ev.BATHROOM_MIN_AREA)
        self.assertEqual(int(self.java_value("BATHROOM_WEAK_LOOKS")), ev.BATHROOM_WEAK_LOOKS)
        self.assertEqual(int(self.java_value("BATHROOM_WEAK_WINDOW_MS")), ev.BATHROOM_WEAK_WINDOW_MS)
        # Robot 2026-10-02 14:51: a strong box a non-bathroom label also claims is only weak, and
        # the strong rule needs it in 2 of the last 3 looks.
        self.assertEqual(float(self.java_value("BATHROOM_RIVAL_IOU").rstrip("f")), ev.BATHROOM_RIVAL_IOU)
        self.assertEqual(float(self.java_value("BATHROOM_RIVAL_MIN").rstrip("f")), ev.BATHROOM_RIVAL_MIN)
        self.assertEqual(int(self.java_value("BATHROOM_STRONG_LOOKS")), ev.BATHROOM_STRONG_LOOKS)


class ReplayTest(unittest.TestCase):
    def test_parse_look_keeps_labels_with_spaces(self):
        boxes = ev.parse_look("look in 791 ms: [toilet paper 0.35 [0.30,0.89,0.35,0.95], chair 0.73 [0.35,0.36,0.92,1.00]]")
        self.assertEqual(boxes[0], ("toilet paper", 0.35, (0.30, 0.89, 0.35, 0.95)))
        self.assertEqual(boxes[1][0], "chair")
        self.assertEqual(ev.parse_look("look in 5 ms: []"), [])
        self.assertIsNone(ev.parse_look("openness in 9 ms"))

    def test_a_strong_toilet_in_two_of_three_looks_triggers_and_a_faint_tiny_or_lone_one_does_not(self):
        toilet = "toilet 0.60 [0.35,0.40,0.65,0.80]"
        r = run([look(0, toilet), look(1, "chair 0.5 [0,0,1,1]"), look(2, toilet)])
        self.assertEqual([(x["rule"], x["looks"]) for x in r["triggers"]], [("strong", 2)])
        self.assertEqual(run([look(0, toilet)])["triggers"], [])
        self.assertEqual(run([look(0, toilet), look(1), look(2), look(3, toilet)])["triggers"], [])
        self.assertEqual(run([look(0, toilet), look(11, toilet)])["triggers"], [])
        faint, tiny = "toilet 0.30 [0.35,0.40,0.65,0.80]", "toilet 0.90 [0.50,0.50,0.55,0.55]"
        self.assertEqual(run([look(0, faint), look(1, faint), look(2, tiny), look(3, tiny)])["triggers"], [])

    def test_the_1451_office_chair_toilet_is_only_weak_and_never_triggers(self):
        """Robot 2026-10-02 14:51: a close office chair filled the frame and the detector gave the
        same box "toilet" 0.62 too: a strong box with a non-bathroom rival (IoU >= 0.8, score >= 0.25)."""
        chair = ("toilet 0.62 [0.32,0.00,1.00,1.00]", "office chair 0.33 [0.32,0.00,1.00,1.00]",
                 "sink 0.26 [0.32,0.00,1.00,0.53]")
        self.assertEqual(run([look(0, *chair), look(1, *chair), look(2, *chair)])["triggers"], [])
        # Demoted to weak, it still counts with another weak label.
        r = run([look(0, *chair), look(1, "mirror 0.55 [0.10,0.10,0.30,0.50]")])
        self.assertEqual([(x["rule"], [b[0] for b in x["boxes"]]) for x in r["triggers"]],
                         [("weak2", ["toilet", "mirror"])])
        # A rival under 0.25, or one that overlaps less, leaves it strong.
        faint = ("toilet 0.62 [0.32,0.00,1.00,1.00]", "office chair 0.20 [0.32,0.00,1.00,1.00]")
        self.assertEqual(len(run([look(0, *faint), look(1, *faint)])["triggers"]), 1)
        apart = ("toilet 0.62 [0.32,0.00,1.00,1.00]", "office chair 0.40 [0.00,0.00,0.60,1.00]")
        self.assertEqual(len(run([look(0, *apart), look(1, *apart)])["triggers"]), 1)

    def test_toilet_paper_alone_never_triggers_and_with_a_sink_does(self):
        r = run([look(0, "toilet paper 0.80 [0.30,0.30,0.50,0.60]")])
        self.assertEqual(r["triggers"], [])
        r = run([look(0, "toilet paper 0.55 [0.30,0.30,0.50,0.60]", "sink 0.55 [0.55,0.35,0.85,0.65]")])
        self.assertEqual([(x["rule"], [b[0] for b in x["boxes"]]) for x in r["triggers"]],
                         [("weak2", ["toilet paper", "sink"])])

    def test_two_weak_within_three_looks_and_ten_s_but_not_further_apart(self):
        sink, soap = "sink 0.55 [0.30,0.30,0.60,0.60]", "soap 0.55 [0.30,0.30,0.60,0.60]"
        near = run([look(0, sink), look(1, "chair 0.5 [0,0,1,1]"), look(2, soap)])
        self.assertEqual([x["looks"] for x in near["triggers"]], [2])
        many_looks = run([look(0, sink), look(1), look(2), look(3, soap)])
        self.assertEqual(many_looks["triggers"], [])
        slow = run([look(0, sink), look(11, soap)])
        self.assertEqual(slow["triggers"], [])

    def test_a_new_pid_starts_afresh_and_logged_triggers_are_matched(self):
        r = run([look(0, "sink 0.55 [0.30,0.30,0.60,0.60]", pid=1),
                 look(1, "soap 0.55 [0.30,0.30,0.60,0.60]", pid=2),
                 look(49, "toilet 0.60 [0.35,0.40,0.65,0.80]"),
                 look(50, "toilet 0.60 [0.35,0.40,0.65,0.80]"),
                 L(50.5, "bathroom: toilet; leaving and beeping (privacy)", tag="ExploreBrain"),
                 L(90, "bathroom: toilet paper; leaving and beeping (privacy)", tag="ExploreBrain")])
        self.assertEqual(len(r["triggers"]), 1)
        self.assertTrue(r["triggers"][0]["logged"])
        self.assertEqual(len(r["logged_not_replayed"]), 1)

    def test_thresholds_can_be_tried(self):
        lines = [look(0, "toilet 0.30 [0.35,0.40,0.65,0.80]"), look(1, "toilet 0.30 [0.35,0.40,0.65,0.80]")]
        self.assertEqual(run(lines)["triggers"], [])
        self.assertEqual(len(run(lines, strong_min=0.25)["triggers"]), 1)

    def test_cli_lists_new_triggers_and_writes_json(self):
        with tempfile.TemporaryDirectory() as d:
            log = Path(d, "day.log")
            log.write_text(look(0, "toilet 0.60 [0.35,0.40,0.65,0.80]") + look(1, "toilet 0.60 [0.35,0.40,0.65,0.80]"))
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                ev.main([str(log), "--year", "2026"])
            self.assertIn("strong looks=2 NEW: toilet 0.60 [0.35,0.40,0.65,0.80]", out.getvalue())
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                ev.main([str(log), "--year", "2026", "--json", "-"])
            self.assertEqual(len(json.loads(out.getvalue())["triggers"]), 1)


if __name__ == "__main__":
    unittest.main()
