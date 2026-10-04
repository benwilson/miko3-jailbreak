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


OWNER = "Ben Wilson"
HELPER = "Sarah Jones"
OWNER_ID = "0000000000000001"
HELPER_ID = "0000000000000002"


def face_row(handle, decision, best="", score=0.0, outcome="pending", joined="", reason="", near_tie=False,
             runner_up=""):
    """One face-check row as the launcher's /settings/face/state JSON carries it (SettingsPage.faceStateJson)."""
    ids = {OWNER: OWNER_ID, HELPER: HELPER_ID}
    return {"handle": handle, "at_ms": 1790000000000 + handle * 1000, "decision": decision, "reason": reason,
            "has_crop": decision != "no face", "best_id": ids.get(best, "00000000000000ff" if best else ""),
            "best_name": best, "best_slot": 0 if best else -1, "best_photo": "", "score": score,
            "runner_up_id": ids.get(runner_up, "00000000000000fe" if runner_up else ""),
            "runner_up_name": runner_up, "runner_up_score": 0.5 if runner_up else None, "near_tie": near_tie,
            "outcome": outcome, "joined_id": ids.get(joined, "00000000000000ee" if joined else ""),
            "joined_name": joined}


def face_state(checks=(), people=((OWNER_ID, OWNER), (HELPER_ID, HELPER))):
    return {"thresholds": {"confident": 0.5, "close": 0.363, "margin": 0.05},
            "checks": list(checks),
            "people": [{"id": i, "name": n, "photos": 3, "with_embedding": 3, "unusable": 0,
                        "last_seen_ms": 1790000000000} for i, n in people]}


class FakeRobot(qa.Robot):
    """Records adb calls; dumpsys answers the given build ids; the state and
    Settings pages answer scripted bodies in order (the last one repeats)."""

    def __init__(self, launcher_id=BUILD, explore_id=BUILD, states=(), pages=(), logs=(), props="", faces=()):
        super().__init__("fake:5555")
        self.faces = list(faces) or [face_state()]
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

    def face_state(self):
        """The face-check list the way robot-faces.py's fetch_state returns it (the last repeats)."""
        self.calls.append(("face-state",))
        return self.faces.pop(0) if len(self.faces) > 1 else self.faces[0]

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
            run_main(robot, ["--only", "charger"], ask)
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
        code, out = run_main(robot, ["--only", "charger"], ask)
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
        names = ["hallway", "leanin", "behind", "charger"]
        states = [
            state(),                                                            # before hallway
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
            state({"cues": 4, "strongCues": 3, "weakCues": 1, "searches": 4, "facesFound": 2,
                   "leanIns": 1, "quietResumes": 1, "repeats": 1},               # after charger: he searched off the dock
                  {"cueAt": 20000, "turnDone": 24000, "faceFound": 26000, "matchAnswered": 29000,
                   "lineRequested": 29100, "firstSound": 31000}),
        ]
        robot = FakeRobot(states=states, logs=["", LOG_OPENED_TO_NOBODY, "", ""])
        # hallway: yes + greeted by name; leanin: yes; behind: no + not by name;
        # charger: yes, but he searched although docked.
        ask = ScriptedAsk([True, True, True, False, False, True])
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            results, summary = qa.run_checks(robot, names, ask)
        text = out.getvalue()
        for name in names:
            self.assertIn(qa.CHECKS[name].instruction, text)
            self.assertIn(qa.CHECKS[name].question, ask.questions)
        self.assertEqual(results, [("hallway", True), ("leanin", True),
                                   ("behind", False), ("charger", False)])
        for name, ok in results:
            self.assertIn(f"   {'PASS' if ok else 'FAIL'} {name}", text)
        # The hallway check's six stage stamps, relative to the cue.
        for label in ("cue at", "turn done", "face found", "match answered", "line requested", "first sound"):
            self.assertIn(label, text)
        self.assertIn("first sound +8.2 s", text)
        self.assertIn("turn done +2.1 s", text)
        # Counter evidence: the lean-in counted at nobody; the ears stay open on the charger
        # (hey-miko plan KTD5), so the cue is heard, and the search off the dock fails the check.
        self.assertIn("leanIns +1", text)
        self.assertIn("cues +1", text)
        self.assertEqual(summary["face_match"], (1, 2))
        self.assertEqual(summary["lean_ins"], 1)
        self.assertEqual(summary["opened_to_nobody"], 1)
        self.assertEqual(summary["repeats"], 1)
        self.assertIn("lean-ins 1", text)
        self.assertIn("opened to nobody 1", text)
        self.assertIn("face match 1/2", text)

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
        # The face list (face AE6): Dave and his checks are gone after the forget.
        faces = [face_state([face_row(1, "confident", "Dave", 0.7)], people=(("0123456789abcdef", "Dave"),)),
                 face_state([], people=())]
        robot = FakeRobot(pages=pages, faces=faces)
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
        self.assertEqual(list(qa.CHECKS), ["greet", "close", "notme", "samename", "neartie", "dark", "silent",
                                           "midname", "hallway", "leanin", "behind", "wedge", "stranger", "goodbye",
                                           "walkoff", "newcomer", "forget", "bait", "switch", "charger", "persona",
                                           "callmet", "callbackoff", "callchat", "callbehind", "callwhere",
                                           "calldock", "callfar", "callten"])
        aes = {a for c in qa.CHECKS.values() for a in c.ae.split(", ") if a}
        for ae in ("AE1", "AE2", "AE3", "AE5", "AE6", "AE7", "AE8", "AE9", "AE10", "AE11", "AE12", "AE13"):
            self.assertIn(ae, aes)
        # The hey-miko plan's acceptance examples and its ten-call run.
        for ae in ("call AE1", "call AE2", "call AE3", "call AE4", "call AE5", "call AE6"):
            self.assertIn(ae, aes)
        # The face plan's acceptance examples, beside the meeting plan's.
        for ae in ("face AE1", "face AE2", "face AE3", "face AE4", "face AE5", "face AE6", "face AE7",
                   "face AE8", "face AE9"):
            self.assertIn(ae, aes)
        self.assertEqual(qa.parse_only(None), list(qa.CHECKS))
        self.assertEqual(qa.parse_only("Hallway, forget"), ["hallway", "forget"])
        with self.assertRaises(ValueError):
            qa.parse_only("ae4")


class FaceChecksTest(unittest.TestCase):
    """The band-aware face checks (face plan U10): each reads the face-check list
    through robot-faces.py and passes only when the expected decision and outcome
    appear in a check recorded after the check began."""

    NAMES = {"owner": OWNER, "helper": HELPER}

    def run_one(self, name, faces, answers, people_pages=()):
        robot = FakeRobot(faces=faces, pages=people_pages or ())
        ask = ScriptedAsk(answers)
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            results, summary = qa.run_checks(robot, [name], ask, **self.NAMES)
        return results, summary, out.getvalue(), ask

    def test_greet_counts_five_front_on_meetings_and_passes_at_four(self):
        rows = [face_row(1, "confident", OWNER, 0.71), face_row(2, "confident", OWNER, 0.66),
                face_row(3, "weak", OWNER, 0.21), face_row(4, "confident", OWNER, 0.58),
                face_row(5, "confident", OWNER, 0.62)]
        faces = [face_state()] + [face_state(rows[:i]) for i in range(1, 6)]
        results, summary, text, ask = self.run_one("greet", faces, [True, True, False, True, True])
        self.assertEqual(results, [("greet", True)])
        self.assertEqual(len(ask.questions), 5)
        self.assertEqual(summary["face_match"], (4, 5))
        self.assertIn(qa.CHECKS["greet"].instruction, text)
        self.assertIn("answer: yes", text)
        self.assertIn("answer: no", text)
        self.assertIn("greeted by name in 4 of 5", text)
        # The row behind each meeting is printed as robot-faces.py renders it.
        self.assertIn("| confident", text)
        self.assertIn("weak", text)
        self.assertIn(OWNER, text)

    def test_greet_fails_at_three_of_five(self):
        rows = [face_row(i, "confident" if i <= 3 else "close", OWNER, 0.6) for i in range(1, 6)]
        faces = [face_state()] + [face_state(rows[:i]) for i in range(1, 6)]
        results, summary, text, _ = self.run_one("greet", faces, [True] * 5)
        self.assertEqual(results, [("greet", False)])
        self.assertEqual(summary["face_match"], (3, 5))

    def test_greet_by_another_persons_name_fails_the_check(self):
        rows = [face_row(i, "confident", OWNER, 0.6) for i in range(1, 5)] + [face_row(5, "confident", HELPER, 0.55)]
        faces = [face_state()] + [face_state(rows[:i]) for i in range(1, 6)]
        results, summary, text, _ = self.run_one("greet", faces, [True] * 5)
        self.assertEqual(results, [("greet", False)])
        self.assertIn("another person's name", text)
        self.assertEqual(summary["wrong_names"], 1)

    def test_owner_answer_yes_without_the_row_fails(self):
        faces = [face_state()] + [face_state()] * 5
        results, _, text, _ = self.run_one("greet", faces, [True] * 5)
        self.assertEqual(results, [("greet", False)])
        self.assertIn("no new face check", text)

    # (check, the row that should pass it, a near miss that must not)
    CASES = [
        ("close", face_row(7, "close", OWNER, 0.42, "yes", joined=OWNER),
         face_row(7, "close", OWNER, 0.42, "no reply")),
        ("notme", face_row(7, "close", OWNER, 0.40, "joined", joined=HELPER),
         face_row(7, "close", OWNER, 0.40, "yes", joined=OWNER)),
        ("samename", face_row(7, "weak", OWNER, 0.20, "new person", joined="Ben Smith"),
         face_row(7, "weak", OWNER, 0.20, "yes", joined=OWNER)),
        ("neartie", face_row(7, "close", OWNER, 0.60, near_tie=True, runner_up="Ben Smith"),
         face_row(7, "confident", OWNER, 0.60)),
        ("dark", face_row(7, "rejected", reason="too dark"),
         face_row(7, "rejected", reason="too blurry")),
        ("silent", face_row(7, "close", OWNER, 0.41, "no reply"),
         face_row(7, "close", OWNER, 0.41, "yes", joined=OWNER)),
        ("midname", face_row(7, "weak", HELPER, 0.25, "joined", joined=HELPER),
         face_row(7, "weak", HELPER, 0.25, "new person", joined="Sarah")),
    ]

    def test_each_band_check_passes_only_on_its_band_and_outcome(self):
        old = face_row(6, "confident", OWNER, 0.7)
        for name, good, bad in self.CASES:
            with self.subTest(name):
                results, _, text, _ = self.run_one(name, [face_state([old]), face_state([old, good])],
                                                   [True, True, True])
                self.assertEqual(results, [(name, True)], text)
                self.assertIn(qa.CHECKS[name].instruction, text)
                self.assertIn("answer: yes", text)
                self.assertIn(good["decision"], text)
                results, _, text, _ = self.run_one(name, [face_state([old]), face_state([old, bad])],
                                                   [True, True, True])
                self.assertEqual(results, [(name, False)], text)

    def test_a_matching_row_from_before_the_check_does_not_count(self):
        dark = face_row(3, "rejected", reason="too dark")
        results, _, text, _ = self.run_one("dark", [face_state([dark])], [True, True])
        self.assertEqual(results, [("dark", False)])

    def test_the_owner_saying_no_fails_even_with_the_row(self):
        dark = face_row(3, "rejected", reason="too dark")
        results, _, text, _ = self.run_one("dark", [face_state(), face_state([dark])], [False, False])
        self.assertEqual(results, [("dark", False)])
        self.assertIn("answer: no", text)

    def test_mid_conversation_name_must_not_store_a_new_person(self):
        joined = face_row(7, "weak", HELPER, 0.25, "joined", joined=HELPER)
        newcomer = ((OWNER_ID, OWNER), (HELPER_ID, HELPER), ("00000000000000aa", "Sarah"))
        results, _, text, _ = self.run_one("midname", [face_state(), face_state([joined], people=newcomer)],
                                           [True, True])
        self.assertEqual(results, [("midname", False)])
        self.assertIn("new person", text)

    def test_forget_leaves_no_face_check_or_person_behind(self):
        gone = ((OWNER_ID, OWNER),)
        row = face_row(4, "confident", HELPER, 0.7)
        pages = [people_page((HELPER_ID, HELPER, "")), people_page()]
        # Forgotten cleanly: the person and every check naming her are gone.
        results, _, text, _ = self.run_one("forget", [face_state([row]), face_state([], people=gone)],
                                           [True, True], pages)
        self.assertEqual(results, [("forget", True)], text)
        # A check still names her: the purge (R19) did not happen.
        results, _, text, _ = self.run_one("forget", [face_state([row]), face_state([row], people=gone)],
                                           [True, True], pages)
        self.assertEqual(results, [("forget", False)])
        self.assertIn("still names", text)

    def test_face_checks_need_the_owner_and_helper_names_before_any_adb(self):
        robot = FakeRobot()
        with self.assertRaises(SystemExit) as cm:
            run_main(robot, ["--only", "greet,notme"], ScriptedAsk([]))
        self.assertIn("--owner", str(cm.exception.code))
        self.assertIn("--helper", str(cm.exception.code))
        self.assertEqual(robot.calls, [])

    def test_face_list_is_read_through_robot_faces(self):
        rf = qa.robot_faces()
        self.assertTrue(hasattr(rf, "fetch_state"))
        self.assertTrue(hasattr(rf, "render_checks"))


class LatencyTest(unittest.TestCase):
    """The before/after stop-to-first-sound measurement (face plan U10, Success Criteria)."""

    def test_summary_is_median_and_worst_case(self):
        before = [9000, 8000, 12000, 10000, 9500]
        after = [3000, 4000, 3500, 5000, 2500]
        s = qa.latency_summary(before, after)
        self.assertEqual(s["before"], {"n": 5, "median_s": 9.5, "worst_s": 12.0})
        self.assertEqual(s["after"], {"n": 5, "median_s": 3.5, "worst_s": 5.0})
        text = "\n".join(qa.latency_lines(s))
        self.assertIn("median 9.5 s", text)
        self.assertIn("worst 12.0 s", text)
        self.assertIn("median 3.5 s", text)
        self.assertEqual(qa.latency_summary([], after)["before"], {"n": 0, "median_s": None, "worst_s": None})

    def test_stop_to_first_sound_is_face_found_to_first_sound(self):
        stages = {"cueAt": 1000, "turnDone": 2000, "faceFound": 4000, "matchAnswered": 9000,
                  "lineRequested": 9100, "firstSound": 13500}
        self.assertEqual(qa.stop_to_first_sound(None, {"stages": stages}), 9500)
        # Stale (the cue did not move) or unfinished meetings are not measured.
        self.assertIsNone(qa.stop_to_first_sound({"stages": stages}, {"stages": stages}))
        self.assertIsNone(qa.stop_to_first_sound(None, {"stages": {**stages, "firstSound": 0}}))

    def meeting_states(self, durations):
        states, cue = [state()], 1000
        for d in durations:
            states.append(state(stages={"cueAt": cue, "turnDone": cue + 500, "faceFound": cue + 1000,
                                        "matchAnswered": cue + 2000, "lineRequested": cue + 2100,
                                        "firstSound": cue + 1000 + d}))
            cue += 60000
        return states

    def test_before_run_records_five_meetings_without_installing(self):
        states = self.meeting_states([9000, 8000, 12000, 10000, 9500])
        stale = states[2]
        states.insert(3, stale)  # the third attempt left the stamps unchanged: not counted, asked again
        robot = FakeRobot(states=states, props=LEFTOVER_PROPS)
        with tempfile.TemporaryDirectory() as tmp, mock.patch.object(qa, "LATENCY_DIR", Path(tmp)):
            code, out = run_main(robot, ["--latency", "before"], ScriptedAsk([True] * 6))
            data = json.loads((Path(tmp) / "latency-before.json").read_text())
        self.assertEqual(code, 0, out)
        self.assertEqual(data["label"], "before")
        self.assertEqual(data["build"], BUILD)
        self.assertEqual([m["stop_to_first_sound_ms"] for m in data["meetings"]], [9000, 8000, 12000, 10000, 9500])
        self.assertIn("not counted", out)
        self.assertFalse(any(c[:1] == ("install",) for c in robot.calls))
        self.assertIn("median 9.5 s", out)
        self.expected_cleanup(robot)

    def test_after_run_installs_and_prints_before_against_after(self):
        robot = FakeRobot(states=self.meeting_states([3000, 4000, 3500, 5000, 2500]))
        with tempfile.TemporaryDirectory() as tmp, mock.patch.object(qa, "LATENCY_DIR", Path(tmp)):
            (Path(tmp) / "latency-before.json").write_text(json.dumps(
                {"label": "before", "build": "old", "meetings": [{"stop_to_first_sound_ms": v}
                                                                 for v in (9000, 8000, 12000, 10000, 9500)]}))
            code, out = run_main(robot, ["--latency", "after"], ScriptedAsk([True] * 5))
            self.assertTrue((Path(tmp) / "latency-after.json").exists())
        self.assertEqual(code, 0, out)
        self.assertTrue(any(c[:3] == ("install", "-r", "-t") for c in robot.calls))
        self.assertIn("before: 5 meetings, median 9.5 s, worst 12.0 s", out)
        self.assertIn("after: 5 meetings, median 3.5 s, worst 5.0 s", out)

    def test_summary_mode_reads_the_files_and_touches_no_robot(self):
        robot = FakeRobot()
        with tempfile.TemporaryDirectory() as tmp, mock.patch.object(qa, "LATENCY_DIR", Path(tmp)):
            for label, vals in (("before", (9000, 12000)), ("after", (3000, 5000))):
                (Path(tmp) / f"latency-{label}.json").write_text(json.dumps(
                    {"label": label, "meetings": [{"stop_to_first_sound_ms": v} for v in vals]}))
            code, out = run_main(robot, ["--latency", "summary"], ScriptedAsk([]))
        self.assertEqual(code, 0)
        self.assertIn("median 10.5 s", out)
        self.assertIn("worst 5.0 s", out)
        self.assertEqual(robot.calls, [])

    def test_before_run_refuses_different_build_ids(self):
        robot = FakeRobot(launcher_id="aaaaaaaaaaaa", explore_id="bbbbbbbbbbbb")
        with self.assertRaises(SystemExit) as cm:
            run_main(robot, ["--latency", "before"], ScriptedAsk([]))
        self.assertIn("differ", str(cm.exception.code))

    def test_interrupt_mid_measurement_clears_the_debug_properties(self):
        robot = FakeRobot(states=self.meeting_states([9000] * 5), props=LEFTOVER_PROPS)
        with tempfile.TemporaryDirectory() as tmp, mock.patch.object(qa, "LATENCY_DIR", Path(tmp)):
            code, out = run_main(robot, ["--latency", "before"], ScriptedAsk([True, KeyboardInterrupt()]))
            self.assertFalse((Path(tmp) / "latency-before.json").exists())
        self.assertEqual(code, 130)
        self.expected_cleanup(robot)

    expected_cleanup = lambda self, robot: CleanupTest.expected_cleanup(self, robot)


class CallChecksTest(unittest.TestCase):
    """The hey-miko plan's call checks (U7): the call stamps on /state time the answer,
    the facing and the arrival against the Success Criteria budgets."""

    CALL = {"callHeard": 10000, "callAnswered": 10600, "callFacing": 12400, "callArrived": 17000}

    def run_one(self, name, counters, stages, answers):
        robot = FakeRobot(states=[state(), state(counters, stages)], logs=[""])
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            results, _ = qa.run_checks(robot, [name], ScriptedAsk(answers))
        return results[0][1], out.getvalue()

    def test_call_times_pass_the_one_second_and_three_second_budgets(self):
        lines, ok = qa.call_lines(None, {"stages": {**{k: 0 for k in qa.STAGES}, **self.CALL}}, facing_budget_s=3.0)
        text = "\n".join(lines)
        self.assertTrue(ok)
        self.assertIn("answered +0.6 s", text)
        self.assertIn("facing +2.4 s", text)
        self.assertIn("arrived +7.0 s", text)
        self.assertNotIn("over", text)

    def test_an_answer_after_1_4_s_fails_answers_within_a_second(self):
        late = {**self.CALL, "callAnswered": 11400}
        lines, ok = qa.call_lines(None, {"stages": {**{k: 0 for k in qa.STAGES}, **late}}, facing_budget_s=3.0)
        self.assertFalse(ok)
        self.assertIn("answered +1.4 s (over the 1.0 s budget)", "\n".join(lines))

    def test_facing_budget_is_12_s_without_the_chip(self):
        slow = {**self.CALL, "callFacing": 21000}
        lines, ok = qa.call_lines(None, {"stages": {**{k: 0 for k in qa.STAGES}, **slow}}, facing_budget_s=12.0)
        self.assertTrue(ok)
        lines, ok = qa.call_lines(None, {"stages": {**{k: 0 for k in qa.STAGES}, **slow}}, facing_budget_s=3.0)
        self.assertFalse(ok)

    def test_a_call_stamp_from_before_the_check_is_not_counted(self):
        before = {"stages": {**{k: 0 for k in qa.STAGES}, **self.CALL}}
        lines, ok = qa.call_lines(before, before, facing_budget_s=12.0)
        self.assertFalse(ok)
        self.assertIn("no fresh call", "\n".join(lines))

    def test_callmet_passes_on_fresh_call_stamps_and_the_owners_yes(self):
        ok, text = self.run_one("callmet", {"cues": 1, "strongCues": 1, "searches": 1}, self.CALL, [True])
        self.assertTrue(ok)
        self.assertIn("answered +0.6 s", text)

    def test_calldock_fails_if_he_searched_off_the_dock(self):
        ok, _ = self.run_one("calldock", {"cues": 1, "strongCues": 1, "searches": 1}, self.CALL, [True])
        self.assertFalse(ok)
        ok, _ = self.run_one("calldock", {"cues": 1, "strongCues": 1},
                             {**self.CALL, "callFacing": 0}, [True])
        self.assertTrue(ok)

    def test_callfar_needs_the_arrival_stamp(self):
        ok, _ = self.run_one("callfar", {"cues": 1, "strongCues": 1, "searches": 1},
                             {**self.CALL, "callArrived": 0}, [True])
        self.assertFalse(ok)

    def test_callten_rests_on_the_owners_count(self):
        ok, _ = self.run_one("callten", {"cues": 10, "strongCues": 10, "searches": 10}, self.CALL, [False])
        self.assertFalse(ok)

    def test_chip_run_holds_facing_to_three_seconds(self):
        slow = {**self.CALL, "callFacing": 16000}
        robot = FakeRobot(states=[state(), state({"cues": 1, "strongCues": 1, "searches": 1}, slow)], logs=[""])
        with contextlib.redirect_stdout(io.StringIO()):
            results, _ = qa.run_checks(robot, ["callbehind"], ScriptedAsk([True]),
                                       facing_budget_s=qa.CALL_FACING_CHIP_S)
        self.assertEqual(results, [("callbehind", False)])

    def test_the_call_checks_run_in_ae_order_through_only(self):
        self.assertEqual(qa.parse_only("callmet,callten"), ["callmet", "callten"])


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
        code, out = run_main(robot, ["--only", "charger"], ask)
        self.assertEqual(code, 0)
        self.expected_cleanup(robot)
        last_prop = max(i for i, c in enumerate(robot.calls) if c[:2] == ("shell", "setprop"))
        last_dump = max(i for i, c in enumerate(robot.calls) if c[:2] == ("logcat", "-d"))
        self.assertGreater(last_prop, last_dump)

    def test_cleared_on_an_interrupt_mid_check(self):
        robot = FakeRobot(props=LEFTOVER_PROPS)
        ask = ScriptedAsk([True, KeyboardInterrupt()])
        code, out = run_main(robot, ["--only", "charger,hallway"], ask)
        self.assertEqual(code, 130)
        self.assertIn("interrupted", out)
        self.expected_cleanup(robot)

    def test_a_failed_check_exits_one_and_still_cleans_up(self):
        robot = FakeRobot(props=LEFTOVER_PROPS)
        code, out = run_main(robot, ["--only", "charger"], ScriptedAsk([False]))
        self.assertEqual(code, 1)
        self.assertIn("FAIL charger", out)
        self.expected_cleanup(robot)


if __name__ == "__main__":
    unittest.main()
