"""Host-side tests for the explore eyes page (explore plan U6): every state the
brain can publish has a look on the page, a look-ahead holds the gaze on the
published direction, and the shared eyes are reused unchanged."""
import json
import re
import shutil
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

    def test_animated_looks_override_the_blink(self):
        # Every look that replaces the blink says so with !important: the blink used to be
        # an inline style, which beat any rule that was not (found by browser test: eyes-only
        # rendered fully open). It is now a one-shot class the eyes' script adds, which these
        # selectors outrank anyway; !important keeps them safe if that ever changes again.
        for state in ("flinch", "eyes-only", "resting", "thinking", "listening", "glance-left", "glance-right"):
            # A rule may cover several states (the two glances share one): find the block whose
            # selector list names this state.
            rule = re.search(r"#rig\.s-" + re.escape(state) + r" \.glow-core[^{]*\{([^}]*)\}", self.page)
            self.assertIsNotNone(rule, state)
            self.assertRegex(rule.group(1), r"animation:[^;]*!important", state)

    def css_rules(self):
        css = self.page[self.page.index("<style>") + len("<style>"):self.page.index("</style>")]
        # Drop @keyframes blocks (nested braces), keep plain selector{declarations} rules.
        css = re.sub(r"@keyframes [\w-]+\{(?:[^{}]*\{[^{}]*\})*\}", "", css)
        return re.findall(r"([^{}]+)\{([^{}]*)\}", css)

    def test_idle_eyes_run_no_endless_animation(self):
        # Robot 2026-10-02, docked: an infinite CSS animation keeps the WebView's compositor
        # producing frames all the time (RenderThread ~16% of a core) even when nothing on
        # screen changes. Idle (also the docked look) blinks by script, so the only endless
        # animations left are each scoped to a non-idle state's class on #rig.
        self.assertNotIn(".style.animation", self.page)
        infinite = [(sel, decl) for sel, decl in self.css_rules() if "infinite" in decl]
        self.assertTrue(infinite, "the thinking pinwheel at least")
        for sel, decl in infinite:
            for one in sel.split(","):
                m = re.match(r"\s*#rig\.s-([a-z-]+) ", one)
                self.assertIsNotNone(m, f"endless animation not tied to a state: {one}{{{decl}}}")
                self.assertNotEqual(m.group(1), "idle", one)
        pinwheel = [sel for sel, decl in infinite if "pinwheel" in decl]
        self.assertEqual(pinwheel, ["#rig.s-thinking .glow-core::after"])

    def test_the_blink_is_a_one_shot_class(self):
        blink = [(sel, decl) for sel, decl in self.css_rules() if re.search(r"animation:blink\b", decl)]
        self.assertEqual(len(blink), 1, blink)
        sel, decl = blink[0]
        self.assertEqual(sel, ".glow-core.blink")
        self.assertRegex(decl, r"animation:blink [0-9.]+s (?:ease )?1\b")
        self.assertNotIn("infinite", decl)
        # The animated cores keep their own composited layer, so a blink starting and
        # ending does not re-raster the gradient each time.
        core = [decl for sel, decl in self.css_rules() if sel == ".glow-core"]
        self.assertTrue(core and "will-change:transform,opacity" in core[0], core)

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


# Runs the explore page's script in a vm context with a fake DOM whose
# setTimeout records rather than runs, so the blink schedule can be read off and
# stepped by hand. Prints one JSON object of what happened.
BLINK_RUNNER = r"""
const vm = require('vm');
const fs = require('fs');
const script = fs.readFileSync(process.argv[2], 'utf8');
function el(name) {
  const classes = new Set(), listeners = {};
  return {name: name, style: {}, className: '', offsetWidth: 100, listeners: listeners,
    classList: {add: function (c) { classes.add(c); }, remove: function (c) { classes.delete(c); },
                contains: function (c) { return classes.has(c); }},
    has: function (c) { return classes.has(c); },
    addEventListener: function (t, fn) { (listeners[t] = listeners[t] || []).push(fn); },
    fire: function (t, e) { (listeners[t] || []).forEach(function (fn) { fn(e); }); }};
}
const rig = el('rig'), glows = [el('g0'), el('g1')], cores = [el('c0'), el('c1')];
const timers = [];
function XHR() {}
XHR.prototype.open = function () {};
XHR.prototype.send = function () {};
const ctx = {
  document: {getElementById: function (id) { return id === 'rig' ? rig : el(id); },
             getElementsByClassName: function (c) { return c === 'glow' ? glows : c === 'glow-core' ? cores : []; }},
  XMLHttpRequest: XHR,
  setTimeout: function (fn, d) { timers.push({fn: fn, d: d, ran: false}); return timers.length; },
  setInterval: function () { throw new Error('setInterval used'); },
  requestAnimationFrame: function () { throw new Error('requestAnimationFrame used'); },
  Math: Math, Date: Date, JSON: JSON,
};
vm.createContext(ctx);
vm.runInContext(script, ctx);
const out = {inline: cores.map(function (c) { return c.style.animation || c.style.animationName || ''; })};
const isGlance = function (t) { return t.fn === ctx.nextGlance; };
function pending() { return timers.filter(function (t) { return !t.ran && !isGlance(t); }); }
function run(t) { t.ran = true; t.fn(); }
out.first = pending().map(function (t) { return t.d; });
const firsts = pending();
const mark = timers.length;
run(firsts[0]);
out.afterFirst = cores.map(function (c) { return c.has('blink'); });
out.next = timers.slice(mark).map(function (t) { return t.d; });
cores[0].fire('animationend', {animationName: 'blink'});
out.afterEnd = cores.map(function (c) { return c.has('blink'); });
run(firsts[1]);
out.afterSecond = cores.map(function (c) { return c.has('blink'); });
// A blink the state's CSS suppressed (animation:none, no animationend) must not linger:
// every timer that comes due clears it, so the core is never left half way.
timers.slice(mark).forEach(function (t) { if (!t.ran && t.d < 2000) run(t); });
out.afterFallback = cores.map(function (c) { return c.has('blink'); });
console.log(JSON.stringify(out));
"""


class ExploreBlinkScheduleTest(unittest.TestCase):
    """The idle blink is scheduled by script, one short animation at a time."""

    @classmethod
    def setUpClass(cls):
        cls.node = shutil.which("node")
        if cls.node is None:
            raise unittest.SkipTest("node not found")
        page = ExploreStatePageTest
        page.setUpClass()
        scripts = re.findall(r"<script>(.*?)</script>", page.page, re.S)
        cls._td = tempfile.TemporaryDirectory(prefix="explore_blink_")
        td = Path(cls._td.name)
        (td / "page.js").write_text(scripts[0], encoding="utf-8")
        (td / "runner.js").write_text(BLINK_RUNNER)
        r = subprocess.run([cls.node, str(td / "runner.js"), str(td / "page.js")],
                           capture_output=True, text=True, timeout=30)
        cls.error = None if r.returncode == 0 else r.stderr[-3000:]
        cls.out = json.loads(r.stdout.strip().splitlines()[-1]) if r.returncode == 0 else {}

    @classmethod
    def tearDownClass(cls):
        cls._td.cleanup()

    def setUp(self):
        self.assertIsNone(self.error, self.error)

    def test_no_inline_animation_on_the_cores(self):
        self.assertEqual(self.out["inline"], ["", ""])

    def test_first_blinks_keep_the_old_timing_and_the_second_eye_lags(self):
        # The old cycle closed the eyes at 90% of 6.5 s, the second eye 0.2 s later.
        self.assertEqual(sorted(self.out["first"]), [5850, 6050])

    def test_a_blink_adds_the_class_and_schedules_the_next(self):
        self.assertEqual(self.out["afterFirst"], [True, False])
        self.assertIn(6500, self.out["next"])
        self.assertEqual(self.out["afterEnd"], [False, False])
        self.assertEqual(self.out["afterSecond"], [False, True])

    def test_a_suppressed_blink_is_cleared_without_animationend(self):
        self.assertEqual(self.out["afterFallback"], [False, False])


if __name__ == "__main__":
    unittest.main()
