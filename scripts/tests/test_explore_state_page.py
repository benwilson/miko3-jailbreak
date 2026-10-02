"""Host-side tests for the explore eyes page (explore plan U6): every state the
brain can publish has a look on the page, a look-ahead holds the gaze on the
published direction, and the shared eyes are reused unchanged."""
import json
import re
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
SHARED_SRC = REPO / "shared" / "src"
HARNESS = TESTS / "fixtures" / "explore_state_harness" / "src"
HARNESS_MAIN = HARNESS / "com" / "miko3" / "mode" / "explore" / "ExploreStateDump.java"
STATE_JAVA = EXPLORE_SRC / "com" / "miko3" / "mode" / "explore" / "ExploreState.java"
EYES_JAVA = SHARED_SRC / "com" / "miko3" / "shared" / "EyesPage.java"


class ExploreStatePageTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        jdk = jvm_harness.find_jdk()
        if jdk is None:
            raise unittest.SkipTest("no JDK (javac + java) found")
        with tempfile.TemporaryDirectory(prefix="explore_state_") as out:
            c = subprocess.run(jvm_harness.javac_cmd(jdk[0], out, [HARNESS_MAIN, STATE_JAVA, EYES_JAVA], [HARNESS]),
                               capture_output=True, text=True)
            if c.returncode != 0:
                raise AssertionError(f"harness failed to compile:\n{(c.stdout + c.stderr)[-3000:]}")
            r = subprocess.run([jdk[1], "-cp", out, "com.miko3.mode.explore.ExploreStateDump"],
                               capture_output=True, text=True, timeout=60)
        text = r.stdout
        cls.page = text[text.index("PAGE-BEGIN") + len("PAGE-BEGIN"):text.index("PAGE-END")]
        cls.states = [json.loads(line[len("STATE "):]) for line in text.splitlines() if line.startswith("STATE ")]
        cls.holder = json.loads([line for line in text.splitlines() if line.startswith("HOLDER ")][0][len("HOLDER "):])

    def test_brain_facing_states_are_the_expected_set(self):
        names = [s["state"] for s in self.states[:-1]]
        self.assertEqual(sorted(names), sorted(["idle", "look", "flinch", "eyes-only", "resting", "thinking",
                                                "listening", "glance-left", "glance-right"]))

    def test_every_non_idle_state_has_its_own_look(self):
        # idle is the other modes' look unchanged (R6); the rest need a rule keyed on #rig.
        for s in self.states[:-1]:
            if s["state"] != "idle":
                self.assertIn(f"#rig.s-{s['state']}", self.page, s["state"])

    def test_look_state_json_carries_the_clamped_direction(self):
        look = self.states[-1]
        self.assertEqual(look, {"state": "look", "lookX": -1.0, "lookY": 0.25})

    def test_plain_states_carry_no_direction(self):
        for s in self.states[:-1]:
            self.assertEqual((s["lookX"], s["lookY"]), (0.0, 0.0), s["state"])

    def test_animated_looks_override_the_inline_blink(self):
        # The shared eyes start their blink as an inline style on each .glow-core
        # (cores[g].style.animation), and an inline animation beats any stylesheet
        # rule that is not !important, so without it these looks never show on the
        # robot (found by browser test: eyes-only rendered fully open).
        self.assertIn("cores[g].style.animation=", self.page)
        for state in ("flinch", "eyes-only", "resting", "thinking", "listening", "glance-left", "glance-right"):
            # A rule may cover several states (the two glances share one): find the block whose
            # selector list names this state.
            rule = re.search(r"#rig\.s-" + re.escape(state) + r" \.glow-core[^{]*\{([^}]*)\}", self.page)
            self.assertIsNotNone(rule, state)
            self.assertRegex(rule.group(1), r"animation:[^;]*!important", state)

    def test_thinking_spins_a_pinwheel_rather_than_only_dimming(self):
        # Owner request: while he waits for Claude's reply each eye shows a turning
        # "working on it" spinner, not just the old bright-dim "ponder" pulse. The
        # spinner is one pseudo-element per eye under the thinking class (so it
        # goes the instant the class changes), turned by a single transform
        # rotate keyframe, no per-frame JS.
        self.assertNotIn("animation:ponder", self.page)
        spin = re.search(r"@keyframes (\w+)\{from\{transform:rotate\(0(?:deg)?\)\}to\{transform:rotate\(360deg\)\}\}",
                         self.page)
        self.assertIsNotNone(spin, "a rotate-only keyframe for the spinner")
        rule = re.search(r"#rig\.s-thinking \.glow-core::(?:before|after)\{([^}]*)\}", self.page)
        self.assertIsNotNone(rule, "the spinner rides a pseudo-element of each core")
        self.assertRegex(rule.group(1), r"animation:" + spin.group(1) + r" [0-9.]+s linear infinite")
        self.assertIn("content:''", rule.group(1))
        # It sits on the core, so it follows the gaze; the core itself stops the blink
        # (inline, so !important), which would otherwise squash the spinner with it.
        core = re.search(r"#rig\.s-thinking \.glow-core\{([^}]*)\}", self.page)
        self.assertIsNotNone(core)
        self.assertIn("animation:none!important", core.group(1))

    def test_glances_slide_the_gaze_to_the_voices_side_and_listening_keeps_it_still(self):
        # Meeting plan R3, KTD12: a glance holds the eyes on the side the voice came from (the
        # page shows the robot's left as +x, as the look state does) as soon as it lands.
        self.assertRegex(self.page, r"exploreState\.state==='glance-left'\)\{x=10;y=0;\}")
        self.assertRegex(self.page, r"exploreState\.state==='glance-right'\)\{x=-10;y=0;\}")
        self.assertRegex(self.page, r"exploreState\.state==='listening'\)\{x\*=")
        self.assertIn("s.state==='glance-left'||s.state==='glance-right')&&was.state!==s.state)gazeTo(0,0,180)", self.page)

    def test_state_json_carries_the_cue_gauges_and_stage_stamps(self):
        # Meeting plan U7, KTD14: /state carries the cue counters and the stage stamps beside the
        # eye state, so the owner QA reads them off the page. Unknown keys change nothing.
        self.assertEqual(self.holder["state"], "listening")
        gauges = self.holder["gauges"]
        counters = ["cues", "strongCues", "weakCues", "leanIns", "searches", "facesFound", "quietResumes",
                    "cuesHeld", "cuesDropped", "retargets", "shoves", "repeats", "remarks"]
        stages = ["cueAt", "turnDone", "faceFound", "matchAnswered", "lineRequested", "firstSound",
                  "callHeard", "callAnswered", "callFacing", "callArrived"]
        self.assertEqual(sorted(k for k in gauges if k != "stages"), sorted(counters))
        self.assertEqual(sorted(gauges["stages"]), sorted(stages))
        self.assertEqual(gauges["cues"], 2)
        self.assertEqual(gauges["leanIns"], 1)
        self.assertEqual(gauges["searches"], 0)
        self.assertEqual(gauges["stages"]["cueAt"], 1234)
        self.assertEqual(gauges["stages"]["firstSound"], 5678)
        self.assertEqual(gauges["stages"]["turnDone"], 0)
        # Hey-miko plan U6: the call's stamps for U7's QA script, 0 until a call reaches them.
        self.assertEqual(gauges["stages"]["callHeard"], 2468)
        self.assertEqual(gauges["stages"]["callArrived"], 0)

    def test_gaze_wrap_holds_the_look_direction(self):
        # R7: while looking, every glance goes to the published direction.
        self.assertIn("var eyesGazeTo=gazeTo;", self.page)
        self.assertRegex(self.page, r"exploreState\.state==='look'\)\{x=exploreState\.lookX")

    def test_look_moves_the_eyes_on_arrival_not_at_the_next_glance(self):
        # The brain waits ~500ms before turning; a glance can be ~2s away, so the
        # page must move the eyes as soon as the look state lands.
        self.assertIn("var showExploreStateBase=showExploreState;", self.page)

    def test_not_moving_looks_keep_the_eyes_alive(self):
        # R10: eyes-only must not look frozen, so its glances are damped, not stopped.
        self.assertRegex(self.page, r"eyes-only'\|\|exploreState\.state==='resting'\)\{x\*=")

    def test_shared_eyes_are_embedded_unchanged(self):
        # The eyes' own glance loop is present verbatim; customization lives only in
        # the mode's CSS and after-eyes script.
        glance = re.search(r'"function nextGlance\(\)\{', EYES_JAVA.read_text())
        self.assertIsNotNone(glance)
        self.assertIn("function nextGlance(){", self.page)
        self.assertIn("function gazeTo(x,y,speedMs){", self.page)


if __name__ == "__main__":
    unittest.main()
