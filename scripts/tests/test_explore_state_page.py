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

    def test_brain_facing_states_are_the_expected_set(self):
        names = [s["state"] for s in self.states[:-1]]
        self.assertEqual(sorted(names), sorted(["idle", "look", "flinch", "eyes-only", "resting"]))

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
