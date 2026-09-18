#!/usr/bin/env python3
"""Tests for the shared eyes page and the voice mode's state hook (U7, KTD9).

The eyes moved out of mode-remote-control's DeviceViewPage into the shared
EyesPage builder. The remote-control page is loaded by that mode's Activity
and must not change, so RemoteControlGoldenTest compiles DeviceViewPage for the
host JVM and asserts the page it serves is byte-for-byte the pre-U7 constant,
captured before the refactor into fixtures/eyes_golden/remote_control_device_view.html.
A later eye tweak that is meant to change the remote-control mode too must
recapture that file on purpose.

VoiceEyesHarnessTest runs fixtures/eyes_golden's voice harness, which prints
one PASS/FAIL line per scenario for the VoiceState enum and its holder (what
/voice-state serves), and checks the voice page carries the same eyes plus
the poll logic and every state name.

VoicePollRateTest runs the voice page's own script under node with a fake DOM,
XMLHttpRequest and setTimeout, answering each /voice-state poll in turn, and
asserts the next poll is scheduled at 1 s after listening/unreachable and at
250 ms otherwise, switching on the transition the page observes (KTD9).

No Android harness exists, so the on-device look and the WebView's CPU cost
are the plan's device checks, not this file. Skips cleanly without a JDK that
accepts -source 8 (or, for the poll test, without node).
"""
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SCRIPTS = REPO / "scripts"
FIXTURES = SCRIPTS / "tests" / "fixtures" / "eyes_golden"
GOLDEN = FIXTURES / "remote_control_device_view.html"
HARNESS = FIXTURES / "src"
SHARED_SRC = REPO / "shared" / "src"
REMOTE_SRC = REPO / "mode-remote-control" / "src"
VOICE_SRC = REPO / "mode-voice" / "src"
DEVICE_VIEW_JAVA = REMOTE_SRC / "com" / "miko3" / "mode" / "remotecontrol" / "DeviceViewPage.java"
REMOTE_HARNESS = HARNESS / "com" / "miko3" / "mode" / "remotecontrol" / "EyesGoldenHarness.java"
VOICE_HARNESS = HARNESS / "com" / "miko3" / "mode" / "voice" / "VoiceEyesHarness.java"

STATES = ("listening", "connecting", "conversing", "speaking", "closing", "unreachable")


def find_jdk():
    """(javac, java) from the JDK build_common picks for the APK builds, else PATH."""
    sys.path.insert(0, str(SCRIPTS))
    try:
        import build_common
        home = build_common.java_home()
    except Exception:
        home = ""
    finally:
        sys.path.remove(str(SCRIPTS))
    if home and (Path(home) / "bin" / "javac").exists():
        return str(Path(home) / "bin" / "javac"), str(Path(home) / "bin" / "java")
    javac, java = shutil.which("javac"), shutil.which("java")
    return (javac, java) if javac and java else None


def compile_harness(jdk, out_dir, main, source_roots):
    """Returns (ok, output). Same language level as build_common.compile_java;
    -Xlint:-options hides the "source 8 is obsolete" chatter from modern JDKs."""
    sourcepath = os.pathsep.join(str(p) for p in [HARNESS] + list(source_roots))
    r = subprocess.run([jdk[0], "-source", "8", "-target", "8", "-encoding", "UTF-8", "-Xlint:-options",
                        "-sourcepath", sourcepath, "-d", str(out_dir), str(main)],
                       capture_output=True, text=True)
    return r.returncode == 0, (r.stdout + r.stderr)[-3000:]


def first_difference(a, b):
    """A readable pointer at the first byte where two pages part ways."""
    n = next((i for i in range(min(len(a), len(b))) if a[i] != b[i]), min(len(a), len(b)))
    return (f"lengths {len(a)} vs {len(b)}; first difference at byte {n}:\n"
            f"  golden:   {a[max(0, n - 60):n + 60]!r}\n"
            f"  composed: {b[max(0, n - 60):n + 60]!r}")


class _HarnessCase(unittest.TestCase):
    """Compiles one harness main into a per-class temp dir."""
    MAIN = None
    SOURCE_ROOTS = ()

    @classmethod
    def setUpClass(cls):
        cls.jdk = find_jdk()
        if cls.jdk is None:
            raise unittest.SkipTest("no JDK (javac + java) found")
        cls._td = tempfile.TemporaryDirectory(prefix="eyes_golden_")
        cls.out = Path(cls._td.name)
        cls.compiled, cls.compile_output = compile_harness(cls.jdk, cls.out / "classes", cls.MAIN, cls.SOURCE_ROOTS)

    @classmethod
    def tearDownClass(cls):
        cls._td.cleanup()

    def setUp(self):
        self.assertTrue(self.compiled, f"harness failed to compile:\n{self.compile_output}")

    @classmethod
    def run_main(cls, class_name, *args):
        return subprocess.run([cls.jdk[1], "-cp", str(cls.out / "classes"), class_name] + list(args),
                              capture_output=True, text=True, timeout=60)


class RemoteControlGoldenTest(_HarnessCase):
    """The remote-control page composed from EyesPage equals the pre-U7 constant."""
    MAIN = REMOTE_HARNESS
    SOURCE_ROOTS = (REMOTE_SRC, SHARED_SRC)

    def test_composed_page_is_byte_identical_to_golden(self):
        page = self.out / "remote.html"
        r = self.run_main("com.miko3.mode.remotecontrol.EyesGoldenHarness", str(page))
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        golden, composed = GOLDEN.read_bytes(), page.read_bytes()
        self.assertTrue(golden == composed, first_difference(golden, composed))

    def test_page_is_composed_from_the_shared_builder(self):
        src = DEVICE_VIEW_JAVA.read_text(encoding="utf-8")
        self.assertIn("EyesPage.build(", src)
        # The eyes live in one place only: no copy of the lens CSS or gaze JS here.
        for eyes_only in (".housing{", ".glow-core{", "function nextGlance()", "@keyframes blink"):
            self.assertNotIn(eyes_only, src)


class VoiceEyesHarnessTest(_HarnessCase):
    """VoiceState, its holder, and the voice page, on the host JVM."""
    MAIN = VOICE_HARNESS
    SOURCE_ROOTS = (VOICE_SRC, SHARED_SRC)

    SCENARIOS = (
        "states_are_exactly_the_ktd9_six",
        "wire_names_round_trip",
        "unknown_wire_name_is_null",
        "slow_poll_only_listening_and_unreachable",
        "holder_defaults_to_listening_text",
        "holder_text_follows_each_set",
        "holder_refuses_null",
        "settings_line_placeholder_before_first_set",
        "settings_line_label_then_detail",
        "holder_reads_never_torn_under_concurrent_sets",
        "page_names_every_state",
    )

    @classmethod
    def setUpClass(cls):
        super().setUpClass()
        cls.results, cls.run_output, cls.page = {}, "", ""
        if not cls.compiled:
            return
        r = cls.run_main("com.miko3.mode.voice.VoiceEyesHarness")
        cls.run_output = (r.stdout + r.stderr)[-6000:]
        for line in r.stdout.splitlines():
            verdict, _, rest = line.partition(" ")
            if verdict in ("PASS", "FAIL"):
                name, _, detail = rest.partition(": ")
                cls.results[name] = (verdict, detail)
        page = cls.out / "voice.html"
        if cls.run_main("com.miko3.mode.voice.VoiceEyesHarness", "page", str(page)).returncode == 0:
            cls.page = page.read_text(encoding="utf-8")

    def _assert_pass(self, name):
        self.assertIn(name, self.results, f"scenario {name} never reported:\n{self.run_output}")
        verdict, detail = self.results[name]
        self.assertEqual(verdict, "PASS", f"{name}: {detail}")

    def test_harness_reports_exactly_the_expected_scenarios(self):
        self.assertEqual(sorted(self.results), sorted(self.SCENARIOS), self.run_output)

    def test_voice_page_has_the_same_eyes(self):
        golden = GOLDEN.read_text(encoding="ascii")
        # The lens CSS (everything before the remote page's own #video rule) and the
        # blink/gaze script must appear verbatim: same eyes, not a lookalike.
        lens_css = golden[golden.index("<style>") + len("<style>"):golden.index("#video{")]
        eyes_js = golden[golden.index("var glows="):golden.index("</script>")]
        rig = golden[golden.index('<div id="rig">'):golden.index('<img id="video"')]
        self.assertIn(lens_css, self.page)
        self.assertIn(eyes_js, self.page)
        self.assertIn(rig, self.page)

    def test_voice_page_polls_the_state_endpoint_at_both_rates(self):
        self.assertIn("'/voice-state'", self.page)
        self.assertIn("1000", self.page)
        self.assertIn("250", self.page)
        for state in STATES:
            self.assertIn(f"#rig.s-{state}" if state != "listening" else "'listening'", self.page)

    def test_voice_page_needs_no_in_process_bridge(self):
        # Must also render in a remote browser (U10 QA): plain relative HTTP only.
        self.assertNotIn("Android.", self.page)
        self.assertNotIn("http://", self.page)
        self.assertNotIn("operator-video", self.page)


def _add_scenario_tests():
    for _name in VoiceEyesHarnessTest.SCENARIOS:
        def _test(self, name=_name):
            self._assert_pass(name)
        setattr(VoiceEyesHarnessTest, f"test_{_name}", _test)


_add_scenario_tests()


# Runs the page script in a vm context with just enough DOM for the eyes and the
# poll: every XMLHttpRequest is parked until the test answers it, and every
# setTimeout is recorded rather than run, so each answer's scheduled poll delay
# can be read off exactly. After each answer it also runs one glance with
# Math.random pinned high, to see how far the gaze is allowed to travel.
NODE_RUNNER = r"""
const vm = require('vm');
const fs = require('fs');
const script = fs.readFileSync(process.argv[2], 'utf8');
const answers = JSON.parse(fs.readFileSync(process.argv[3], 'utf8'));
function el() { return {style: {}, className: ''}; }
const rig = el(), glows = [el(), el()], cores = [el(), el()];
const timers = [], requests = [];
function XHR() {}
XHR.prototype.open = function (method, url) { this.method = method; this.url = url; };
XHR.prototype.send = function () { requests.push(this); };
const math = Object.create(Math);
math.random = function () { return 0.99; };
const ctx = {
  document: {
    getElementById: function (id) { return id === 'rig' ? rig : el(); },
    getElementsByClassName: function (c) { return c === 'glow' ? glows : c === 'glow-core' ? cores : []; },
  },
  XMLHttpRequest: XHR,
  setTimeout: function (fn, d) { timers.push({fn: fn, d: d}); return timers.length; },
  setInterval: function () { throw new Error('setInterval used'); },
  fetch: function () { throw new Error('fetch used'); },
  Math: math, Date: Date, JSON: JSON,
};
vm.createContext(ctx);
vm.runInContext(script, ctx);
const out = {initial: rig.className, steps: []};
for (const [status, body] of answers) {
  if (requests.length !== 1) { out.error = 'pending requests: ' + requests.length; break; }
  const x = requests.shift();
  const req = {method: x.method, url: x.url, timeout: x.timeout};
  const mark = timers.length;
  x.status = status; x.responseText = body;
  x.onloadend();
  const polls = timers.slice(mark).filter(function (t) { return t.fn === ctx.pollVoiceState; });
  ctx.nextGlance();
  out.steps.push({req: req, cls: rig.className, delays: polls.map(function (t) { return t.d; }),
                  gaze: glows[0].style.transform});
  if (polls.length !== 1) { out.error = 'polls scheduled: ' + polls.length; break; }
  polls[0].fn();
}
console.log(JSON.stringify(out));
"""


class VoicePollRateTest(unittest.TestCase):
    """KTD9's two-rate poll, exercised by running the page's own script."""

    @classmethod
    def setUpClass(cls):
        cls.node = shutil.which("node")
        if cls.node is None:
            raise unittest.SkipTest("node not found")
        jdk = find_jdk()
        if jdk is None:
            raise unittest.SkipTest("no JDK (javac + java) found")
        cls._td = tempfile.TemporaryDirectory(prefix="eyes_poll_")
        td = Path(cls._td.name)
        ok, output = compile_harness(jdk, td / "classes", VOICE_HARNESS, (VOICE_SRC, SHARED_SRC))
        cls.setup_error = None if ok else f"harness failed to compile:\n{output}"
        cls.script = td / "voice.js"
        cls.runner = td / "runner.js"
        cls.runner.write_text(NODE_RUNNER)
        if ok:
            page = td / "voice.html"
            r = subprocess.run([jdk[1], "-cp", str(td / "classes"), "com.miko3.mode.voice.VoiceEyesHarness",
                                "page", str(page)], capture_output=True, text=True, timeout=60)
            html = page.read_text(encoding="utf-8") if r.returncode == 0 else ""
            scripts = re.findall(r"<script>(.*?)</script>", html, re.S)
            if len(scripts) != 1:
                cls.setup_error = f"expected one inline script in the voice page, found {len(scripts)}"
            else:
                cls.script.write_text(scripts[0], encoding="utf-8")

    @classmethod
    def tearDownClass(cls):
        cls._td.cleanup()

    def setUp(self):
        self.assertIsNone(self.setup_error, self.setup_error)

    def drive(self, answers):
        answers_file = Path(self._td.name) / "answers.json"
        answers_file.write_text(json.dumps(answers))
        r = subprocess.run([self.node, str(self.runner), str(self.script), str(answers_file)],
                           capture_output=True, text=True, timeout=30)
        self.assertEqual(r.returncode, 0, r.stderr[-3000:])
        out = json.loads(r.stdout.strip().splitlines()[-1])
        self.assertNotIn("error", out, out)
        return out

    def test_rate_follows_each_observed_state(self):
        seq = ["listening", "listening", "connecting", "conversing", "speaking", "conversing",
               "closing", "listening", "unreachable", "unreachable", "listening"]
        out = self.drive([[200, s] for s in seq])
        self.assertEqual(out["initial"], "s-listening")
        for state, step in zip(seq, out["steps"]):
            self.assertEqual(step["cls"], f"s-{state}")
            slow = state in ("listening", "unreachable")
            self.assertEqual(step["delays"], [1000 if slow else 250], f"after {state}")
            self.assertEqual((step["req"]["method"], step["req"]["url"]), ("GET", "/voice-state"))
            # A hung request must not stop the poll: every one carries a timeout.
            self.assertTrue(0 < step["req"]["timeout"] <= 1000, step["req"])

    def test_bad_answers_keep_the_last_state_and_its_rate(self):
        answers = [[200, "conversing"], [0, ""], [500, "listening"], [200, "dancing"],
                   [200, "speaking\n"], [200, "unreachable"], [0, ""]]
        out = self.drive(answers)
        got = [(s["cls"], s["delays"]) for s in out["steps"]]
        self.assertEqual(got, [
            ("s-conversing", [250]),
            ("s-conversing", [250]),    # network error
            ("s-conversing", [250]),    # non-200 body ignored
            ("s-conversing", [250]),    # unknown state ignored
            ("s-speaking", [250]),      # surrounding whitespace tolerated
            ("s-unreachable", [1000]),
            ("s-unreachable", [1000]),
        ])

    def test_gaze_steadies_while_engaged(self):
        def x_of(transform):
            m = re.match(r"translate\((-?[\d.e-]+)vmin,(-?[\d.e-]+)vmin\)", transform)
            self.assertIsNotNone(m, transform)
            return abs(float(m.group(1))), abs(float(m.group(2)))

        seq = ["listening", "connecting", "conversing", "speaking", "closing", "unreachable"]
        out = self.drive([[200, s] for s in seq])
        gaze = {s: x_of(step["gaze"]) for s, step in zip(seq, out["steps"])}
        # Math.random is pinned at 0.99: a free glance goes nearly to the edge.
        for free in ("listening", "closing", "unreachable"):
            self.assertGreater(gaze[free][0], 9, free)
        self.assertEqual(gaze["connecting"], (0.0, 0.0))
        for steady in ("conversing", "speaking"):
            self.assertLess(max(gaze[steady]), 4, steady)


if __name__ == "__main__":
    unittest.main()
