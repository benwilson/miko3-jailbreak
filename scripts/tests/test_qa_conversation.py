"""Tests for scripts/qa-conversation.py's host-side logic (meeting plan U9): the
paired install's build-id check, the guided checks (each prints its instruction
and records pass or fail from the owner's answer and the state page's counters;
the hallway check prints the six stage stamps), and the property cleanup on
exit and on an interrupt. The robot itself is faked: adb calls are recorded and
the state and Settings pages answer scripted bodies."""
import contextlib
import importlib.util
import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock

HERE = Path(__file__).resolve().parent
SCRIPT = HERE.parent / "qa-conversation.py"


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load {path}")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


qa = load("qa_conversation", SCRIPT)

BUILD = "df61598abcde"
LEFTOVER_PROPS = ("[log.tag.MikoExploreCurious]: [DEBUG]\n"
                  "[log.tag.MikoExploreStale]: [INFO]\n"
                  "[log.tag.MikoDmdRaw]: [DEBUG]\n"
                  "[debug.miko3.ears_probe]: [feedfacefeedface]\n")


def state(counters=None, stages=None):
    """A /state body the way ExploreState renders it (U7): counters and stages default to 0."""
    c = {k: 0 for k in qa.COUNTERS}
    c.update(counters or {})
    s = {k: 0 for k in qa.STAGES}
    s.update(stages or {})
    return json.dumps({"state": "wander", "lookX": 0.0, "lookY": 0.0, "gauges": {**c, "stages": s}})


def people_page(*people):
    """The launcher's Settings page People section (U5): (id, name, notes_html) per person."""
    html = ['<html><body><form method="post" action="/settings/claude">'
            '<input type="hidden" name="t" value="feedfacefeedfacefeedfacefeedface"></form>'
            '<section id="people"><h2>People</h2>']
    if not people:
        html.append('<p id="people-empty">He hasn\'t met anyone yet.</p>')
    for pid, name, notes in people:
        html.append(f'<article id="person-{pid}"><img src="/settings/people/face?id={pid}" alt="{name}">'
                    f'<p><strong>{name}</strong><br><small>Last seen 2026-09-25 10:00</small></p>')
        if notes:
            html.append(f'<div class="notes">{notes}</div>')
        else:
            html.append('<p class="notes"><small>No notes yet.</small></p>')
        html.append('<form method="post" action="/settings/people/forget"><button>Forget</button></form></article>')
    html.append('</section></body></html>')
    return "".join(html)


BOAT_NOTES = ('<p><strong>Interests</strong></p><ul><li>sailing</li></ul>'
              '<p><strong>Open threads</strong></p><ul><li>boat trip this weekend <small>(since 2026-09-25)</small></li></ul>')

LOG_OPENED_TO_NOBODY = "\n".join([
    "09-25 16:01:02.345  1234  1250 I ExploreBrain: a conversation with someone unnamed",
    "09-25 16:01:12.000  1234  1250 I ExploreBrain: first unanswered listen: one look for them",
    "09-25 16:01:18.000  1234  1250 I ExploreBrain: two unanswered listens: the sign-off",
    "09-25 16:01:21.000  1234  1250 I ExploreBrain: conversation over after 1 turn(s), 0 note delta(s) kept",
])


class FakeRobot(qa.Robot):
    """Records adb calls; dumpsys answers the given build ids; the state and
    Settings pages answer scripted bodies in order (the last one repeats)."""

    def __init__(self, launcher_id=BUILD, explore_id=BUILD, states=(), pages=(), logs=(), props=""):
        super().__init__("fake:5555")
        self.launcher_id = launcher_id
        self.explore_id = explore_id
        self.states = list(states) or [state()]
        self.pages = list(pages) or [people_page()]
        self.logs = list(logs)
        self.props = props
        self.calls = []
        self.sleep = lambda seconds: None

    def connect(self):
        self.calls.append(("connect",))

    def adb(self, *args, check=True):
        self.calls.append(args)
        if args == ("get-state",):
            return "device\n"
        if args[:3] == ("shell", "dumpsys", "package"):
            build = self.launcher_id if args[3] == qa.LAUNCHER_PKG else self.explore_id
            return f"  Package [{args[3]}]\n    versionCode=1 minSdk=28\n    versionName={build}\n"
        if args == ("shell", "getprop"):
            return self.props
        if args[:2] == ("logcat", "-d"):
            return self.logs.pop(0) if self.logs else ""
        return ""

    def http_get(self, port, path):
        queue = self.states if port == qa.EXPLORE_HTTPS_PORT else self.pages
        body = queue.pop(0) if len(queue) > 1 else queue[0]
        return body.encode()

    def setprops(self):
        return [c[2:] for c in self.calls if c[:2] == ("shell", "setprop")]


class ScriptedAsk:
    """Answers the owner's y/n questions from a list, in order; raises what it is told to."""

    def __init__(self, answers):
        self.answers = list(answers)
        self.questions = []

    def __call__(self, question):
        self.questions.append(question)
        answer = self.answers.pop(0)
        if isinstance(answer, BaseException):
            raise answer
        return answer


def run_main(robot, argv, ask):
    """main() with the robot, the owner and the APK files faked; returns (exit code, output)."""
    out = io.StringIO()
    with tempfile.TemporaryDirectory() as tmp:
        launcher = Path(tmp) / "miko3-launcher.apk"
        explore = Path(tmp) / "miko3-mode-explore.apk"
        launcher.write_bytes(b"apk")
        explore.write_bytes(b"apk")
        with mock.patch.object(qa, "Robot", lambda serial: robot), \
                mock.patch.object(qa, "LAUNCHER_APK", launcher), \
                mock.patch.object(qa, "EXPLORE_APK", explore), \
                mock.patch.object(qa, "ask", ask), \
                contextlib.redirect_stdout(out), contextlib.redirect_stderr(out):
            code = qa.main(list(argv))
    return code, out.getvalue()


class BuildIdTest(unittest.TestCase):
    def test_different_build_ids_are_refused_before_any_check(self):
        robot = FakeRobot(launcher_id="aaaaaaaaaaaa", explore_id="bbbbbbbbbbbb")
        ask = ScriptedAsk([True] * 20)
        with self.assertRaises(SystemExit) as cm:
            run_main(robot, ["--only", "greet"], ask)
        message = str(cm.exception.code)
        self.assertIn("differ", message)
        self.assertIn("aaaaaaaaaaaa", message)
        self.assertIn("bbbbbbbbbbbb", message)
        self.assertEqual(ask.questions, [])
        # Both APKs were installed and started (install -r, then am start: the HOME
        # key would not bring the launcher back), launcher first.
        installs = [c for c in robot.calls if c[:3] == ("install", "-r", "-t")]
        self.assertEqual([Path(c[3]).name for c in installs], ["miko3-launcher.apk", "miko3-mode-explore.apk"])
        starts = [c[4] for c in robot.calls if c[:4] == ("shell", "am", "start", "-n")]
        self.assertEqual(starts, [qa.LAUNCHER_COMPONENT, qa.EXPLORE_COMPONENT])
        self.assertNotIn(("shell", "am", "force-stop", qa.LAUNCHER_PKG), robot.calls)
        self.assertFalse(any("kill" in c for c in robot.calls))

    def test_one_build_id_is_printed_and_the_checks_run(self):
        robot = FakeRobot()
        ask = ScriptedAsk([True, True])
        code, out = run_main(robot, ["--only", "greet"], ask)
        self.assertEqual(code, 0)
        self.assertIn(BUILD, out)
        self.assertEqual(len(ask.questions), 1)

    def test_version_name_is_read_from_dumpsys(self):
        robot = FakeRobot(launcher_id="cafe0123beef+1a2b3c4d")
        self.assertEqual(robot.version_name(qa.LAUNCHER_PKG), "cafe0123beef+1a2b3c4d")
        self.assertIn(("shell", "dumpsys", "package", qa.LAUNCHER_PKG), robot.calls)


class ChecksTest(unittest.TestCase):
    """Each check prints its instruction and records pass or fail from the
    owner's answer and the state counters; the hallway check prints six stamps."""

    def test_checks_print_instructions_and_judge_answers_with_the_counters(self):
        names = ["greet", "hallway", "leanin", "behind", "charger"]
        states = [
            state(),                                                            # before greet
            state(),                                                            # after greet: nothing moved
            state({"cues": 1, "strongCues": 1, "searches": 1, "facesFound": 1},  # after hallway
                  {"cueAt": 1000, "turnDone": 3100, "faceFound": 4000, "matchAnswered": 6500,
                   "lineRequested": 6600, "firstSound": 9200}),
            state({"cues": 2, "strongCues": 1, "weakCues": 1, "searches": 2, "facesFound": 1,
                   "leanIns": 1, "quietResumes": 1},                             # after leanin
                  {"cueAt": 1000, "turnDone": 3100, "faceFound": 4000, "matchAnswered": 6500,
                   "lineRequested": 6600, "firstSound": 9200}),
            state({"cues": 3, "strongCues": 2, "weakCues": 1, "searches": 3, "facesFound": 2,
                   "leanIns": 1, "quietResumes": 1},                             # after behind
                  {"cueAt": 20000, "turnDone": 24000, "faceFound": 26000, "matchAnswered": 29000,
                   "lineRequested": 29100, "firstSound": 31000}),
            state({"cues": 4, "strongCues": 3, "weakCues": 1, "searches": 3, "facesFound": 2,
                   "leanIns": 1, "quietResumes": 1, "repeats": 1},               # after charger: a cue got through
                  {"cueAt": 20000, "turnDone": 24000, "faceFound": 26000, "matchAnswered": 29000,
                   "lineRequested": 29100, "firstSound": 31000}),
        ]
        robot = FakeRobot(states=states, logs=["", "", LOG_OPENED_TO_NOBODY, "", ""])
        # greet: yes (its answer is the face sample); hallway: yes + greeted by name;
        # leanin: yes; behind: no + not by name; charger: yes, but a cue got through.
        ask = ScriptedAsk([True, True, True, True, False, False, True])
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            results, summary = qa.run_checks(robot, names, ask)
        text = out.getvalue()
        for name in names:
            self.assertIn(qa.CHECKS[name].instruction, text)
            self.assertIn(qa.CHECKS[name].question, ask.questions)
        self.assertEqual(results, [("greet", True), ("hallway", True), ("leanin", True),
                                   ("behind", False), ("charger", False)])
        for name, ok in results:
            self.assertIn(f"   {'PASS' if ok else 'FAIL'} {name}", text)
        # The hallway check's six stage stamps, relative to the cue.
        for label in ("cue at", "turn done", "face found", "match answered", "line requested", "first sound"):
            self.assertIn(label, text)
        self.assertIn("first sound +8.2 s", text)
        self.assertIn("turn done +2.1 s", text)
        # Counter evidence: the lean-in counted at nobody, the cue that got through on the charger.
        self.assertIn("leanIns +1", text)
        self.assertIn("cues +1", text)
        self.assertEqual(summary["face_match"], (2, 3))
        self.assertEqual(summary["lean_ins"], 1)
        self.assertEqual(summary["opened_to_nobody"], 1)
        self.assertEqual(summary["repeats"], 1)
        self.assertIn("lean-ins 1", text)
        self.assertIn("opened to nobody 1", text)
        self.assertIn("face match 2/3", text)

    def test_hallway_with_a_stale_stamp_fails_and_prints_dashes(self):
        # cueAt did not move since before the check: the stamps are an earlier meeting's.
        stale = {"cueAt": 500, "turnDone": 900, "faceFound": 0, "matchAnswered": 0, "lineRequested": 0,
                 "firstSound": 0}
        robot = FakeRobot(states=[state(stages=stale), state(stages=stale)])
        ask = ScriptedAsk([True, True])
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            results, _ = qa.run_checks(robot, ["hallway"], ask)
        self.assertEqual(results, [("hallway", False)])
        self.assertIn("first sound -", out.getvalue())

    def test_people_page_rules_for_stranger_goodbye_and_forget(self):
        dave = ("0123456789abcdef", "Dave", "")
        dave_noted = ("0123456789abcdef", "Dave", BOAT_NOTES)
        pages = [people_page(dave),          # before the run
                 people_page(dave),          # after stranger: nobody new
                 people_page(dave_noted),    # after goodbye: notes landed
                 people_page()]              # after forget: gone
        robot = FakeRobot(pages=pages)
        ask = ScriptedAsk([True, True, True, True, True])
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            results, _ = qa.run_checks(robot, ["stranger", "goodbye", "forget"], ask)
        self.assertEqual(results, [("stranger", True), ("goodbye", True), ("forget", True)])
        text = out.getvalue()
        self.assertIn("boat trip this weekend", text)
        self.assertIn("Dave", text)

    def test_a_stranger_who_left_a_record_fails_the_check(self):
        robot = FakeRobot(pages=[people_page(), people_page(("fedcba9876543210", "Visitor", ""))])
        ask = ScriptedAsk([True])
        with contextlib.redirect_stdout(io.StringIO()):
            results, _ = qa.run_checks(robot, ["stranger"], ask)
        self.assertEqual(results, [("stranger", False)])

    def test_walk_off_needs_the_brains_two_unanswered_listens(self):
        robot = FakeRobot(logs=["09-25 16:01:18.000  1234  1250 I ExploreBrain: they said goodbye: the sign-off"])
        with contextlib.redirect_stdout(io.StringIO()):
            results, _ = qa.run_checks(robot, ["walkoff"], ScriptedAsk([True]))
        self.assertEqual(results, [("walkoff", False)])
        robot = FakeRobot(logs=[LOG_OPENED_TO_NOBODY])
        with contextlib.redirect_stdout(io.StringIO()):
            results, _ = qa.run_checks(robot, ["walkoff"], ScriptedAsk([True]))
        self.assertEqual(results, [("walkoff", True)])

    def test_every_listed_acceptance_example_has_a_check_in_the_plans_order(self):
        self.assertEqual(list(qa.CHECKS), ["greet", "hallway", "leanin", "behind", "wedge", "stranger", "goodbye",
                                           "walkoff", "newcomer", "forget", "bait", "switch", "charger", "persona"])
        aes = {c.ae for c in qa.CHECKS.values() if c.ae}
        for ae in ("AE1", "AE2", "AE3", "AE5", "AE6", "AE7", "AE8", "AE9", "AE10", "AE11", "AE12", "AE13"):
            self.assertIn(ae, aes)
        self.assertEqual(qa.parse_only(None), list(qa.CHECKS))
        self.assertEqual(qa.parse_only("Hallway, forget"), ["hallway", "forget"])
        with self.assertRaises(ValueError):
            qa.parse_only("ae4")


class ParsingTest(unittest.TestCase):
    def test_state_page_gauges_and_stages(self):
        g = qa.parse_state(state({"cues": 2, "repeats": 1}, {"cueAt": 123}))
        self.assertEqual((g["cues"], g["repeats"], g["leanIns"]), (2, 1, 0))
        self.assertEqual(g["stages"]["cueAt"], 123)
        self.assertIsNone(qa.parse_state("unreachable"))
        self.assertIsNone(qa.parse_state('{"state":"idle","lookX":0.0,"lookY":0.0}'))

    def test_people_section_names_and_notes(self):
        people = qa.parse_people(people_page(("0123456789abcdef", "Dave &amp; co", BOAT_NOTES),
                                             ("fedcba9876543210", "Ann", "")))
        self.assertEqual([(p.id, p.name) for p in people],
                         [("0123456789abcdef", "Dave & co"), ("fedcba9876543210", "Ann")])
        self.assertIn("boat trip this weekend", people[0].notes)
        self.assertNotIn("<", people[0].notes)
        self.assertEqual(people[1].notes, "")
        self.assertEqual(qa.parse_people(people_page()), [])

    def test_opened_to_nobody_counts_first_turn_sign_offs_only(self):
        notes = qa.brain_notes(LOG_OPENED_TO_NOBODY)
        self.assertEqual(qa.opened_to_nobody(notes), 1)
        later = ["a conversation with someone known (3 questions on record)",
                 "two unanswered listens: the sign-off",
                 "conversation over after 4 turn(s), 2 note delta(s) kept"]
        self.assertEqual(qa.opened_to_nobody(later), 0)

    def test_leftover_hooks_and_probe_property(self):
        self.assertEqual(qa.leftover_hooks(LEFTOVER_PROPS), {"MikoExploreCurious": "DEBUG", "MikoDmdRaw": "DEBUG"})


class CleanupTest(unittest.TestCase):
    """The log.tag.MikoExplore* hooks and the ears-probe properties are cleared on
    exit, including on an interrupt."""

    def expected_cleanup(self, robot):
        writes = robot.setprops()
        self.assertIn(("log.tag.MikoExploreCurious", "INFO"), writes)
        self.assertIn(("log.tag.MikoDmdRaw", "INFO"), writes)
        self.assertIn((qa.PROBE_PROPERTY, '""'), writes)
        self.assertNotIn(("log.tag.MikoExploreStale", "INFO"), writes)

    def test_cleared_on_a_normal_exit_after_the_last_check(self):
        robot = FakeRobot(props=LEFTOVER_PROPS)
        ask = ScriptedAsk([True])
        code, out = run_main(robot, ["--only", "greet"], ask)
        self.assertEqual(code, 0)
        self.expected_cleanup(robot)
        last_prop = max(i for i, c in enumerate(robot.calls) if c[:2] == ("shell", "setprop"))
        last_dump = max(i for i, c in enumerate(robot.calls) if c[:2] == ("logcat", "-d"))
        self.assertGreater(last_prop, last_dump)

    def test_cleared_on_an_interrupt_mid_check(self):
        robot = FakeRobot(props=LEFTOVER_PROPS)
        ask = ScriptedAsk([True, KeyboardInterrupt()])
        code, out = run_main(robot, ["--only", "greet,hallway"], ask)
        self.assertEqual(code, 130)
        self.assertIn("interrupted", out)
        self.expected_cleanup(robot)

    def test_a_failed_check_exits_one_and_still_cleans_up(self):
        robot = FakeRobot(props=LEFTOVER_PROPS)
        code, out = run_main(robot, ["--only", "greet"], ScriptedAsk([False]))
        self.assertEqual(code, 1)
        self.assertIn("FAIL greet", out)
        self.expected_cleanup(robot)


if __name__ == "__main__":
    unittest.main()
