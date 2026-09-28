#!/usr/bin/env python3
"""qa-conversation.py — the owner's QA of meeting and small talk in Explore
(meeting plan U9): install both APKs from one build, then walk the hallway test
and the acceptance examples on the robot.

Install first: the launcher and mode-explore from the current build outputs
(launcher/miko3-launcher.apk, mode-explore/miko3-mode-explore.apk), each with
`adb install -r` then `am start` (the HOME key does not bring our launcher
back; nothing is ever `kill -9`ed). Both version names are read back with
`dumpsys package` and the script refuses to go on when their build ids differ:
the two APKs talk over a Binder contract that changed in this plan, so a stale
half would test old code silently.

Then one guided check at a time, in the plan's order: the face checks of the
on-device face recognition plan (U10) first, then the hallway "hey buddy" from the side with
the six stage stamps (KTD14), a weak-cue lean-in at nobody, a wake word from
behind, his name while he backs out of a wedge, a stranger who declines a name,
a goodbye and the notes on the People page, a silent walk-off, a newcomer
calling mid-conversation, forget-me with the confirm step, a baiting topic, the
switch off, the charger connected, and a persona edit heard in the next
conversation. Each prints its instruction, takes a y/n answer, then reads the
state page's counters and stamps (and the People page or the brain's notes
where they are the evidence) and records PASS or FAIL from both.

The face checks are band-aware. Five front-on meetings with the owner (greeted
by name in at least 4 of 5, and never by another person's name), a close
confirmation (face AE2), "No, I'm <name>" (AE3), the two-Bens case with a
helper (AE4, then AE8's near tie), a dark-corner rejection (AE5), a silent close
question (AE7) and a name given mid-conversation (AE9); forget-me (AE6) rides on
the existing forget check. After each one the face-check list is read through
scripts/robot-faces.py (its fetch_state, over its own adb forward) and the check
passes only when a check recorded since it began carries the expected decision
and outcome; the instruction, the owner's answer and that row are printed.
--owner and --helper name the two stored people exactly as the People list has
them.

--latency measures stop-to-first-sound (the face-found stamp to the first-sound
stamp on Explore's /state) over five meetings into tools/face-bench/ (gitignored):
`--latency before` on the build already installed (nothing is installed), then
`--latency after`, which installs as usual and prints the median and worst case
before against after; `--latency summary` prints that from the two files alone.

The report is the pass list, the observed face-match rate, the lean-in, cue and
repeat counters, and the count of conversations whose first person turn ended
in two unanswered listens (he opened a conversation with nobody) beside the
lean-in count. Every `log.tag.MikoExplore*` hook and the ears-probe properties
are cleared on exit, including on an interrupt.

  python3 scripts/qa-conversation.py                     # install, then every check
  python3 scripts/qa-conversation.py --build             # rebuild both APKs first
  python3 scripts/qa-conversation.py --only hallway,walkoff
  python3 scripts/qa-conversation.py --no-install        # the robot already runs this build
  python3 scripts/qa-conversation.py --owner "Ben Wilson" --helper "Sarah Jones" --only greet,close,dark
  python3 scripts/qa-conversation.py --latency before    # current build, before installing the new one
  python3 scripts/qa-conversation.py --latency after     # installs, measures, prints before against after
  python3 scripts/qa-conversation.py --latency summary
  python3 scripts/qa-conversation.py --only callmet,callbackoff,callchat,callbehind,callwhere,calldock,callfar,callten
  python3 scripts/qa-conversation.py --chip --only callbehind   # after qa-direction-chip.py --calibrate

The call checks (hey-miko plan U7) time each call from the /state call stamps:
answered within 1 s, facing within 12 s (3 s with --chip), and the arrival.
"""
import argparse
import functools
import html
import importlib.util
import http.client
import json
import re
import signal
import ssl
import statistics
import subprocess
import sys
import threading
import time
from collections import namedtuple
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
LAUNCHER_APK = REPO / "launcher" / "miko3-launcher.apk"
EXPLORE_APK = REPO / "mode-explore" / "miko3-mode-explore.apk"
LATENCY_DIR = REPO / "tools" / "face-bench"   # gitignored: before/after timings stay on the Mac
LATENCY_MEETINGS = 5
GREET_MEETINGS = 5
GREET_NEEDED = 4   # Success Criteria: greeted by name in at least 4 of 5 front-on meetings
BUILD_SCRIPTS = (REPO / "scripts" / "build-custom-launcher.py", REPO / "scripts" / "build-mode-explore.py")

DEFAULT_SERIAL = "192.168.19.74:5555"
LAUNCHER_PKG = "com.miko3.launcher"
LAUNCHER_COMPONENT = f"{LAUNCHER_PKG}/{LAUNCHER_PKG}.MainActivity"
LAUNCHER_PERMISSION = "android.permission.RECORD_AUDIO"   # the ears session (U3)
EXPLORE_PKG = "com.miko3.mode.explore"
EXPLORE_COMPONENT = f"{EXPLORE_PKG}/.MainActivity"
EXPLORE_PERMISSION = "android.permission.CAMERA"
# LauncherProtocol.LAUNCHER_HTTPS_PORT and ModeApp.HTTPS_PORT; both self-signed.
LAUNCHER_HTTPS_PORT = 8443
SETTINGS_PATH = "/settings"
EXPLORE_HTTPS_PORT = 8446
STATE_PATH = "/state"
BRAIN_TAG = "ExploreBrain"
ADB_TIMEOUT = 120
HTTP_TIMEOUT = 10

# ExploreState.Gauges (U7): the counters and the six stage stamps on /state.
COUNTERS = ("cues", "strongCues", "weakCues", "leanIns", "searches", "facesFound", "quietResumes", "cuesHeld",
            "cuesDropped", "retargets", "shoves", "repeats")
STAGES = ("cueAt", "turnDone", "faceFound", "matchAnswered", "lineRequested", "firstSound",
          "callHeard", "callAnswered", "callFacing", "callArrived")
# The hey-miko plan's call stamps (U6) and its Success Criteria: the answer within
# about 1 s, facing within about 3 s with the direction chip and 12 s without.
CALL_ANSWER_BUDGET_S = 1.0
CALL_FACING_CHIP_S = 3.0
CALL_FACING_NO_CHIP_S = 12.0
STAGE_LABELS = (("cueAt", "cue at"), ("turnDone", "turn done"), ("faceFound", "face found"),
                ("matchAnswered", "match answered"), ("lineRequested", "line requested"),
                ("firstSound", "first sound"))
# KTD14's per-stage budget, seconds from the previous stage (the look budget is
# the camera-streaming one, since the camera stays open through the meeting).
STAGE_BUDGET_S = {"turnDone": 3.0, "faceFound": 2.4, "matchAnswered": 4.0, "lineRequested": 3.0, "firstSound": 2.0}
FIRST_SOUND_TYPICAL_S = 16.0  # Success Criteria: first line within about 16 s typically

# Debug hooks an interrupted script can leave on (qa-explore-mode.py, qa-ears-probe.py).
HOOK_PREFIXES = ("MikoExplore", "MikoDmdRaw")
PROBE_PROPERTY = "debug.miko3.ears_probe"  # EarsProbe.java: the launcher's probe route

# The brain's notes (ChatSession) that are evidence here.
NOTE_TWO_UNANSWERED = "two unanswered listens: the sign-off"
NOTE_DEFLECTED = "the line deflects a task"
_NOTE_OVER = re.compile(r"^conversation over after (\d+) turn\(s\)")
_LOG_HEAD = re.compile(r"(?:^|\s)[VDIWEF][/ ]\s*([\w.$-]+)\s*(?:\(\s*\d+\))?\s*:\s?(.*)$")
_ARTICLE = re.compile(r'<article id="person-([0-9a-f]+)">(.*?)</article>', re.S)
_STRONG = re.compile(r"<strong>(.*?)</strong>", re.S)
_NOTES_DIV = re.compile(r'<div class="notes">(.*?)</div>', re.S)
_TAG = re.compile(r"<[^>]+>")


class QaError(SystemExit):
    """A precondition failed; the message says what to do."""


Person = namedtuple("Person", "id name notes")

# One guided check: face is how the face-match sample is taken ("answer": the
# y/n answer itself, "ask": a second question, None: no known face in it);
# people: read the People page after it; page: print the Settings page URL.
# faces: read the face-check list (robot-faces.py) before and after it.
Check = namedtuple("Check", "ae instruction question face people page faces", defaults=(False,))

CHECKS = {
    "greet": Check(
        "face AE1", "Five front-on meetings in normal office light: stand in front of him at his height, about a "
                    "metre away, at one of his stops (or say 'Hey Miko' facing him) and say nothing more. Walk "
                    "off after each and let him roam before the next.",
        "did he say your name instead of asking for it?", None, False, False, True),
    "close": Check(
        "face AE2", "Meet him so the match is close, not sure: turned a little, glasses or a hat on, or in "
                    "dimmer light. He should ask 'Is that you, <your name>?'; answer 'yes'.",
        "did he ask whether it was you, then greet you after your yes?", None, False, False, True),
    "notme": Check(
        "face AE3", "Have the helper meet him the same way. If he asks 'Is that you, <someone else>?', the "
                    "helper answers 'No, I'm <helper's first name>'. (If he greets or asks about the helper "
                    "directly, walk off and try again.)",
        "did he take the correction and greet the helper by name?", None, False, False, True),
    "samename": Check(
        "face AE4", "Have a coworker he has never met say 'Hey Miko' and, asked their name, answer with your "
                    "first name. He should ask their last name; they give theirs (not yours).",
        "did he ask for a last name and then greet them with it?", None, False, False, True),
    "neartie": Check(
        "face AE8", "Now that two people share your first name, have the one who scores closest to you (often "
                    "the newcomer from the last check) meet him front-on.",
        "did he ask 'Is that you, <name>?' rather than greet anyone outright?", None, False, False, True),
    "dark": Check(
        "face AE5", "Stand in the darkest corner of the room, front-on, and say 'Hey Miko'.",
        "did he treat you as someone new (no name), without greeting you by anyone's name?",
        None, False, False, True),
    "silent": Check(
        "face AE7", "Meet him so the match is close again; when he asks 'Is that you, <your name>?', say "
                    "nothing and wait.",
        "did he give up on the question and ask your name as he would a stranger?", None, False, False, True),
    "midname": Check(
        "face AE9", "Have the helper meet him so he does not recognise them (turned away, then front-on once he "
                    "is talking); decline a name at first, chat, and on the third turn say 'I'm <helper's "
                    "first name>'.",
        "did he switch to talking with the helper by name?", None, False, False, True),
    "hallway": Check(
        "AE1", "While he roams, say 'hey buddy' at normal volume from his side. If your notes hold an open thread "
               "(a plan you told him about), he should ask about it; if not, answer for the stop, the turn and "
               "the name.",
        "did he stop, turn to face you, and open by greeting you by name (and ask about your open thread)?",
        "ask", False, False),
    "leanin": Check(
        "AE2", "Have two people chat to his right, not to him, while he roams (or play a short voice clip from "
               "the side).",
        "did he stop, look, find nobody facing him and resume within a few seconds, with no greeting?",
        None, False, False),
    "behind": Check(
        "", "Stand behind him while he roams and say 'Hey Miko'. A voice from behind takes two looks; the stamps "
            "say where the time went.",
        "did he turn around, find you and greet you?", "ask", False, False),
    "wedge": Check(
        "AE10", "Put him facing into a nook (a wall and a plant) while he roams; once he is backing out, say his "
                "name.",
        "did he answer at once and turn toward you (only a back-off already under way may finish first)?",
        "ask", False, False),
    "stranger": Check(
        "AE3", "Have someone he has never seen say 'Hey Miko', decline to give a name when he asks, chat for a "
               "turn or two and say goodbye.",
        "did he hold the conversation anyway?", None, True, False),
    "goodbye": Check(
        "AE5", "As a named coworker, talk with him about a couple of topics and one plan of yours, then say "
               "goodbye.",
        "did he sign off in one line?", "ask", True, False),
    "walkoff": Check(
        "AE6", "Open a conversation, then walk away silently mid-conversation.",
        "did he give up after two unanswered listens (about 8 s plus one look) and go back to roaming?",
        None, False, False),
    "newcomer": Check(
        "AE7", "Open a conversation; have a second person say 'Hey Miko' from off to one side.",
        "did he glance at them, say 'one sec', finish with you, and only then turn to them?",
        "ask", False, False),
    "forget": Check(
        "AE8, face AE6", "As a named coworker with notes and photos (the helper), say 'forget me'. He should ask "
                         "'forget you, <your name>?'; answer 'yes'.",
        "did he confirm the wipe aloud?", "ask", True, False, True),
    "bait": Check(
        "AE11", "Bait him with a topic that would get an employee fired.",
        "did he deflect in persona and repeat none of it?", "ask", False, False),
    "switch": Check(
        "AE9", "On the Settings page, switch 'Answers when spoken to' off and save. Say 'hey buddy' from his "
               "side: nothing should happen. Then say 'Hey Miko': a conversation should open. Switch it back on "
               "afterwards.",
        "did 'hey buddy' get no reaction and 'Hey Miko' open a conversation?", None, False, True),
    "charger": Check(
        "AE13", "Put him on the charger and say 'hey buddy' from his side. Take him off again afterwards.",
        "did he not react at all?", None, False, False),
    "persona": Check(
        "AE12", "On the Settings page, edit the persona (a new catchphrase, say) and save, then open a "
                "conversation with 'Hey Miko'. Restore the persona afterwards if you want it back.",
        "was the change audible in his lines, with no reinstall?", "ask", False, True),
    # ---- the hey-miko plan's calls (U7): the stamps time each against the Success Criteria ----
    "callmet": Check(
        "call AE1", "Meet him (let him greet you), walk off, and within two minutes say 'Hey Miko' from a few "
                    "metres away while he roams.",
        "did he answer out loud at once and turn to you?", None, False, False),
    "callbackoff": Check(
        "call AE2", "Let him back away from a table edge or a bump; say 'Hey Miko' while he is still backing.",
        "did he finish backing first, then answer and turn (no turning during the back-off)?", None, False, False),
    "callchat": Check(
        "call AE3", "Talk with him; have a second person say 'Hey Miko' mid-conversation from the side.",
        "did he stay with you, and when your conversation ended, answer the second person and turn to them?",
        None, False, False),
    "callbehind": Check(
        "call AE4", "Stand behind him while he roams and say 'Hey Miko' once.",
        "did he answer, turn in steps and stop facing you?", None, False, False),
    "callwhere": Check(
        "call AE5", "Say 'Hey Miko' and step out of sight before he finds you. When he asks 'Where'd you go?', "
                    "say 'Hey Miko' again.",
        "did he ask where you went, then search again?", None, False, False),
    "calldock": Check(
        "call AE6", "Put him on the charger and say 'Hey Miko'. Take him off again afterwards.",
        "did he answer and hold the conversation without leaving the dock?", None, False, False),
    "callfar": Check(
        "", "Stand 3 m or more away, in his view, and say 'Hey Miko'.",
        "did he come to about a metre from you, facing you, and start the conversation?", None, False, False),
    "callten": Check(
        "", "Say 'Hey Miko' ten times over a few minutes while he roams, escapes, searches or just after meeting "
            "you; count the answers.",
        "did all ten get an answer (the back-off and your own conversation may only delay one)?",
        None, False, False),
}
# Checks timed by the call stamps, and whether a fresh arrival is part of passing.
CALL_CHECKS = {"callmet": False, "callbackoff": False, "callchat": False, "callbehind": False,
               "callwhere": False, "calldock": True, "callfar": True, "callten": False}
FACE_QUESTION = "did he greet you by name (a face match)?"
# The names each face check needs: --owner, --helper.
FACE_NAMES = {"greet": ("owner",), "close": ("owner",), "notme": ("owner", "helper"), "samename": ("owner",),
              "silent": ("owner",), "midname": ("helper",)}
LATENCY_INSTRUCTION = ("Stand front-on to him at his height, about a metre away, and say 'Hey Miko'. Let him "
                       "speak his first line, then walk off and let him roam before the next meeting.")
LATENCY_QUESTION = "did he stop in front of you and speak?"


@functools.lru_cache(maxsize=None)
def robot_faces():
    """scripts/robot-faces.py as a module: its fetch_state and render_checks read the face-check list."""
    spec = importlib.util.spec_from_file_location("robot_faces", REPO / "scripts" / "robot-faces.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def parse_only(value):
    """The --only list, validated; empty means every check in the plan's order."""
    if not value:
        return list(CHECKS)
    names = [s.strip().lower() for s in value.split(",") if s.strip()]
    unknown = [s for s in names if s not in CHECKS]
    if unknown:
        raise ValueError(f"unknown check(s): {', '.join(unknown)} (known: {', '.join(CHECKS)})")
    return names


def ask(question):
    return input(f"   {question} [y/n] ").strip().lower().startswith("y")


# ---- the robot ----

class Robot:
    def __init__(self, serial):
        self.serial = serial
        self.sleep = time.sleep

    @property
    def host(self):
        return self.serial.split(":")[0] if ":" in self.serial else "<robot-ip>"

    def adb(self, *args, check=True):
        # stdin=DEVNULL: adb would otherwise eat the owner's y/n answers.
        try:
            r = subprocess.run(["adb", "-s", self.serial] + list(args), capture_output=True, text=True,
                               timeout=ADB_TIMEOUT, stdin=subprocess.DEVNULL)
        except FileNotFoundError:
            raise QaError("!! adb not found on PATH (brew install android-platform-tools)")
        except subprocess.TimeoutExpired:
            raise QaError(f"!! adb timed out: adb {' '.join(args)}")
        if check and r.returncode != 0:
            raise QaError(f"!! adb {' '.join(args)}: {r.stderr.strip() or r.stdout.strip()}")
        return r.stdout

    def connect(self):
        subprocess.run(["adb", "connect", self.serial], capture_output=True, timeout=30, stdin=subprocess.DEVNULL)

    def ensure_reachable(self):
        if ":" in self.serial:
            self.connect()
        if self.adb("get-state", check=False).strip() != "device":
            raise QaError(f"!! robot not reachable over adb at {self.serial}; check it is on and on Wi-Fi, "
                          "or pass --serial")

    def version_name(self, pkg):
        m = re.search(r"versionName=(\S+)", self.adb("shell", "dumpsys", "package", pkg))
        if not m:
            raise QaError(f"!! {pkg} is not installed (no versionName in dumpsys package)")
        return m.group(1)

    def http_get(self, port, path):
        """The body of GET path on the robot's HTTPS port through an adb forward
        (always removed); the certificates are self-signed. OSError on any trouble."""
        out = self.adb("forward", "tcp:0", f"tcp:{port}").strip()
        if not out.isdigit():
            raise OSError(f"adb forward to tcp:{port} failed: {out or 'no port printed'}")
        ctx = ssl.create_default_context()
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        conn = http.client.HTTPSConnection("127.0.0.1", int(out), timeout=HTTP_TIMEOUT, context=ctx)
        try:
            conn.request("GET", path)
            r = conn.getresponse()
            body = r.read()
            if r.status != 200:
                raise OSError(f"GET {path} answered {r.status}")
            return body
        except http.client.HTTPException as exc:
            raise OSError(f"GET {path}: {exc}") from exc
        finally:
            conn.close()
            self.adb("forward", "--remove", f"tcp:{out}", check=False)

    def gauges(self):
        """The state page's counters and stamps, or None when it cannot be read."""
        try:
            g = parse_state(self.http_get(EXPLORE_HTTPS_PORT, STATE_PATH).decode("utf-8", "replace"))
        except OSError as exc:
            print(f"   state page unreachable: {exc}")
            return None
        if g is None:
            print("   state page carries no gauges: install the current mode-explore")
        return g

    def people(self):
        """The People page, or None when it cannot be read."""
        try:
            return parse_people(self.http_get(LAUNCHER_HTTPS_PORT, SETTINGS_PATH).decode("utf-8", "replace"))
        except OSError as exc:
            print(f"   People page unreachable: {exc}")
            return None

    def face_state(self):
        """The launcher's face checks, people and thresholds through robot-faces.py
        (its own adb forward, always removed), or None when they cannot be read."""
        rf = robot_faces()
        try:
            with rf.rs.port_forward(self.serial) as base:
                return rf.fetch_state(base)
        except rf.SettingsError as exc:
            print(f"   face-check list unreadable: {str(exc).strip()}")
            return None

    def brain_notes(self):
        """The brain's notes logged since the last call, oldest first; the buffer is
        cleared each time so a long run never overflows it."""
        text = self.adb("logcat", "-d", "-s", f"{BRAIN_TAG}:V", check=False)
        self.adb("logcat", "-c", check=False)
        return brain_notes(text)

    def start(self, component):
        self.adb("shell", "am", "start", "-n", component)
        self.sleep(3)

    def cleanup(self):
        """Every leftover debug hook back to INFO and the probe property cleared;
        best effort, never raises (this runs from finally blocks)."""
        try:
            left = leftover_hooks(self.adb("shell", "getprop", check=False))
            for tag in left:
                self.adb("shell", "setprop", f"log.tag.{tag}", "INFO", check=False)
            self.adb("shell", "setprop", PROBE_PROPERTY, '""', check=False)
        except QaError as exc:
            print(f"{exc}\n!! clear them by hand: adb -s {self.serial} shell setprop log.tag.MikoExplore<hook> INFO; "
                  f"setprop {PROBE_PROPERTY} '\"\"'", file=sys.stderr)
            return {}
        return left


# ---- parsing ----

def parse_state(text):
    """{counter: n, ..., "stages": {stage: ms}} from /state, or None without gauges."""
    try:
        data = json.loads(text)
    except ValueError:
        return None
    g = data.get("gauges") if isinstance(data, dict) else None
    if not isinstance(g, dict) or not isinstance(g.get("stages"), dict):
        return None
    out = {k: int(g.get(k, 0)) for k in COUNTERS}
    out["stages"] = {k: int(g["stages"].get(k, 0)) for k in STAGES}
    return out


def parse_people(page):
    """The People section as Person(id, name, notes text) per article; notes is ""
    for "No notes yet." and legacy records."""
    out = []
    for m in _ARTICLE.finditer(page):
        body = m.group(2)
        strong = _STRONG.search(body)
        name = html.unescape(_TAG.sub("", strong.group(1))).strip() if strong else ""
        notes = _NOTES_DIV.search(body)
        text = ""
        if notes:
            text = " ".join(html.unescape(_TAG.sub(" ", notes.group(1))).split())
        out.append(Person(m.group(1), name, text))
    return out


def brain_notes(text):
    """The ExploreBrain messages from a logcat dump, in order."""
    out = []
    for line in text.splitlines():
        m = _LOG_HEAD.search(line)
        if m and m.group(1) == BRAIN_TAG:
            out.append(m.group(2).strip())
    return out


def opened_to_nobody(notes):
    """Conversations whose first person turn ended in two unanswered listens: the
    sign-off note followed by a conversation over after the opener alone."""
    count = 0
    pending = False
    for note in notes:
        if note == NOTE_TWO_UNANSWERED:
            pending = True
            continue
        m = _NOTE_OVER.match(note)
        if m:
            if pending and int(m.group(1)) <= 1:
                count += 1
            pending = False
    return count


def leftover_hooks(getprop_text):
    """Debug hooks left set, as {tag: value}, from `getprop` output
    ("[log.tag.MikoExploreCurious]: [DEBUG]"). INFO or empty counts as off."""
    out = {}
    for m in re.finditer(r"\[log\.tag\.([\w.$-]+)\]:\s*\[([^\]]*)\]", getprop_text):
        tag, value = m.group(1), m.group(2).strip()
        if tag.startswith(HOOK_PREFIXES) and value.upper() not in ("", "INFO"):
            out[tag] = value
    return out


# ---- the install ----

def build_both():
    for script in BUILD_SCRIPTS:
        print(f"== building with {script.name} ==", flush=True)
        r = subprocess.run([sys.executable, str(script)])
        if r.returncode != 0:
            raise QaError(f"!! {script.name} failed ({r.returncode}); see the output above")


def install_both(robot):
    """Install and start the launcher, then Explore, and return the one build id
    both report; refuse when they differ."""
    for apk in (LAUNCHER_APK, EXPLORE_APK):
        if not apk.exists():
            raise QaError(f"!! {apk} not found: build it first (or pass --build)")
    robot.ensure_reachable()
    print(f"== installing {LAUNCHER_APK.name} and {EXPLORE_APK.name} ==")
    # install -r then am start: the HOME key does not restart our launcher, and a
    # supervised service is never kill -9ed (docs/solutions/runtime-errors/).
    robot.adb("install", "-r", "-t", str(LAUNCHER_APK))
    robot.adb("shell", "pm", "grant", LAUNCHER_PKG, LAUNCHER_PERMISSION)
    robot.start(LAUNCHER_COMPONENT)
    robot.adb("install", "-r", "-t", str(EXPLORE_APK))
    robot.adb("shell", "pm", "grant", EXPLORE_PKG, EXPLORE_PERMISSION)
    robot.start(EXPLORE_COMPONENT)
    return check_build_ids(robot)


def check_build_ids(robot):
    launcher = robot.version_name(LAUNCHER_PKG)
    explore = robot.version_name(EXPLORE_PKG)
    if launcher != explore:
        raise QaError(f"!! build ids differ: launcher {launcher}, mode-explore {explore}.\n"
                      "   Build both from one tree (scripts/build-custom-launcher.py and "
                      "scripts/build-mode-explore.py, or --build) and re-run.")
    print(f"   build {launcher} on both")
    return launcher


# ---- the checks ----

def counter_delta(before, after):
    """{counter: change} between two gauges; a restart mid-check (a smaller value)
    counts from zero. None when either read failed."""
    if before is None or after is None:
        return None
    return {k: after[k] - before[k] if after[k] >= before[k] else after[k] for k in COUNTERS}


def delta_text(d):
    moved = [f"{k} +{v}" for k, v in d.items() if v]
    return "counters: " + (", ".join(moved) if moved else "unchanged")


def stamp_lines(before, after):
    """The six stage stamps relative to the cue, one line each, and whether all
    six are fresh (the cue moved since before the check and every stage followed it)."""
    if after is None:
        return ["stamps: state page unreachable"], False
    cue = after["stages"]["cueAt"]
    fresh = cue > 0 and (before is None or cue != before["stages"]["cueAt"])
    lines, all_fresh, prev = [], fresh, cue
    for key, label in STAGE_LABELS:
        v = after["stages"][key]
        if not fresh or v <= 0 or v < cue:
            lines.append(f"{label} -")
            all_fresh = False
            continue
        text = f"{label} +{(v - cue) / 1000:.1f} s"
        budget = STAGE_BUDGET_S.get(key)
        if budget is not None and (v - prev) / 1000 > budget:
            text += f" (over the {budget:.1f} s stage budget)"
        if key == "firstSound" and (v - cue) / 1000 > FIRST_SOUND_TYPICAL_S:
            text += f" (past the {FIRST_SOUND_TYPICAL_S:.0f} s typical)"
        lines.append(text)
        prev = v
    return ["stamps: " + "; ".join(lines)], all_fresh


def call_lines(before, after, facing_budget_s):
    """The call's answer, facing and arrival relative to when it was heard, and whether
    the call is fresh and inside the answer and facing budgets (a missing stage prints -)."""
    if after is None:
        return ["call: state page unreachable"], False
    heard = after["stages"]["callHeard"]
    if heard <= 0 or (before is not None and heard == before["stages"]["callHeard"]):
        return ["call: no fresh call heard"], False
    parts, ok = [], True
    for key, label, budget in (("callAnswered", "answered", CALL_ANSWER_BUDGET_S),
                               ("callFacing", "facing", facing_budget_s),
                               ("callArrived", "arrived", None)):
        v = after["stages"][key]
        if v <= 0 or v < heard:
            parts.append(f"{label} -")
            continue
        text = f"{label} +{(v - heard) / 1000:.1f} s"
        if budget is not None and (v - heard) / 1000 > budget:
            text += f" (over the {budget:.1f} s budget)"
            ok = False
        parts.append(text)
    if after["stages"]["callAnswered"] < heard:
        ok = False
    return ["call: " + "; ".join(parts)], ok


def names_of(people):
    return {p.id: p.name for p in people or []}


def judge(name, d, before, after, people_before, people_after, notes, facing_budget_s=CALL_FACING_NO_CHIP_S):
    """(ok, evidence lines) from the counters, stamps, People page and brain notes
    the check names; a check with no rule passes on the answer alone."""
    if name in CALL_CHECKS:
        lines, ok = call_lines(before, after, facing_budget_s)
        if name == "calldock" and (d is None or d["searches"] > 0):
            return False, lines + ["he searched instead of staying on the dock"]
        if CALL_CHECKS[name] and (after is None or after["stages"]["callArrived"] <= after["stages"]["callHeard"]):
            return False, lines + ["no conversation opened for the call"]
        if name == "callten":
            return True, lines
        return ok, lines
    if name in ("hallway", "behind"):
        lines, fresh = stamp_lines(before, after)
        if name == "behind":
            ok = d is not None and d["strongCues"] >= 1 and d["searches"] >= 1
            return ok, lines + ([] if ok else ["no strong cue and search counted"])
        return fresh, lines + ([] if fresh else ["not all six stages were stamped fresh for this cue"])
    if name == "leanin":
        ok = d is not None and d["leanIns"] >= 1 and d["facesFound"] == 0
        return ok, [] if ok else ["expected one lean-in counted and no face found"]
    if name == "wedge":
        # The hey-miko plan's R2 supersedes meeting AE10: an escape yields to a call at once,
        # and only a back-off in progress delays the answer.
        lines, ok = call_lines(before, after, CALL_FACING_NO_CHIP_S)
        return ok, lines
    if name == "newcomer":
        ok = d is not None and d["cuesHeld"] >= 1
        return ok, [] if ok else ["expected the cue to be counted as held"]
    if name == "switch":
        ok = d is not None and d["strongCues"] >= 1
        return ok, [] if ok else ["expected the wake word counted as a strong cue"]
    if name == "charger":
        # The ears stay open on the charger now (hey-miko plan KTD5): a weak cue is heard,
        # but he must neither search nor leave the dock for it.
        ok = d is not None and d["searches"] == 0
        return ok, [] if ok else ["he searched although he was docked"]
    if name == "stranger":
        if people_before is None or people_after is None:
            return False, ["People page unreadable"]
        new = [n or "(unnamed)" for i, n in names_of(people_after).items() if i not in names_of(people_before)]
        return not new, [] if not new else [f"a record appeared for a stranger: {', '.join(new)}"]
    if name == "goodbye":
        if people_before is None or people_after is None:
            return False, ["People page unreadable"]
        old = {p.id: p.notes for p in people_before}
        changed = [p for p in people_after if p.notes and p.notes != old.get(p.id, "")]
        lines = [f"notes for {p.name or p.id}: {p.notes}" for p in changed]
        return bool(changed), lines or ["no person's notes changed on the People page"]
    if name == "forget":
        if people_before is None or people_after is None:
            return False, ["People page unreadable"]
        gone = [n or i for i, n in names_of(people_before).items() if i not in names_of(people_after)]
        return bool(gone), [f"gone from the People page: {', '.join(gone)}"] if gone else \
            ["nobody left the People page"]
    if name == "walkoff":
        ok = NOTE_TWO_UNANSWERED in notes
        return ok, [] if ok else ["the brain never noted two unanswered listens"]
    if name == "bait":
        return True, ["the brain noted a deflection"] if NOTE_DEFLECTED in notes else []
    return True, []


# ---- the face checks (face plan U10) ----

def same_name(a, b):
    return bool(a) and bool(b) and " ".join(a.split()).casefold() == " ".join(b.split()).casefold()


def first_name(n):
    return (n or "").split()[0].casefold() if (n or "").split() else ""


def new_face_rows(before, after):
    """The checks recorded after `before` was read (a higher handle), newest first."""
    top = max((c.get("handle", 0) for c in before.get("checks", [])), default=0)
    return robot_faces().newest_first([c for c in after.get("checks", []) if c.get("handle", 0) > top])


# What each face check expects of a check recorded during it: (rule, what it says when missing).
FACE_RULES = {
    "greet": (lambda r, o, h: r.get("decision") == "confident" and same_name(r.get("best_name"), o),
              "a confident match to the owner"),
    "close": (lambda r, o, h: r.get("decision") == "close" and same_name(r.get("best_name"), o)
              and r.get("outcome") == "yes", "a close match to the owner answered yes"),
    "notme": (lambda r, o, h: r.get("decision") == "close" and not same_name(r.get("best_name"), h)
              and r.get("outcome") == "joined" and same_name(r.get("joined_name"), h),
              "a close match to someone else, joined to the helper"),
    "samename": (lambda r, o, h: r.get("outcome") == "new person" and not same_name(r.get("joined_name"), o)
                 and first_name(r.get("joined_name")) == first_name(o),
                 "a new person stored with the owner's first name and another last name"),
    "neartie": (lambda r, o, h: r.get("decision") == "close" and bool(r.get("near_tie"))
                and bool(r.get("runner_up_id")), "a close question from a near tie"),
    "dark": (lambda r, o, h: r.get("decision") == "rejected" and r.get("reason") == "too dark"
             and not r.get("best_id"), "a crop rejected as too dark, matched to nobody"),
    "silent": (lambda r, o, h: r.get("decision") == "close" and r.get("outcome") == "no reply"
               and not r.get("joined_id"), "a close question with no reply and no photo added"),
    "midname": (lambda r, o, h: r.get("decision") != "confident" and r.get("outcome") == "joined"
                and same_name(r.get("joined_name"), h), "an unrecognised start joined to the helper by name"),
}


def row_lines(row):
    return ["   " + line for line in robot_faces().render_checks([row]).splitlines()]


def face_judge(name, before, after, owner=None, helper=None):
    """(ok, evidence lines, the relevant row or None) for one face check from the
    face-check list read before and after it."""
    if before is None or after is None:
        return False, ["face-check list unreadable"], None
    rows = new_face_rows(before, after)
    if name == "forget":
        ids_after = {p.get("id") for p in after.get("people", [])}
        gone = {p.get("id"): p.get("name") or p.get("id") for p in before.get("people", [])
                if p.get("id") not in ids_after}
        if not gone:
            return False, ["nobody left the face list's people"], None
        naming = [c for c in after.get("checks", [])
                  if gone.keys() & {c.get("best_id"), c.get("runner_up_id"), c.get("joined_id")}]
        if naming:
            return False, [f"a face check still names {', '.join(gone.values())}"] + row_lines(naming[0]), naming[0]
        return True, [f"gone from the people and every face check: {', '.join(gone.values())}"], None
    rule, wanted = FACE_RULES[name]
    if not rows:
        return False, [f"no new face check was recorded (wanted {wanted})"], None
    hit = next((r for r in rows if rule(r, owner, helper)), None)
    if hit is None:
        return False, [f"expected {wanted}; the newest check was:"] + row_lines(rows[0]), rows[0]
    lines = row_lines(hit)
    if name == "midname":
        ids_before = {p.get("id") for p in before.get("people", [])}
        added = [p.get("name") or p.get("id") for p in after.get("people", []) if p.get("id") not in ids_before]
        if added:
            return False, lines + [f"a new person was stored as well: {', '.join(added)}"], hit
    return True, lines, hit


def run_greet(robot, check, ask_fn, owner, count=GREET_MEETINGS):
    """The five front-on meetings: (ok, [per-meeting greeted], wrong names)."""
    greeted, wrong = [], 0
    state = robot.face_state()
    for i in range(1, count + 1):
        print(f"   -- meeting {i} of {count}")
        answered = ask_fn(check.question)
        print(f"   answer: {'yes' if answered else 'no'}")
        after = robot.face_state()
        ok, lines, _ = face_judge("greet", state, after, owner)
        if state is not None and after is not None:
            others = [r for r in new_face_rows(state, after)
                      if r.get("decision") == "confident" and not same_name(r.get("best_name"), owner)]
            if others:
                wrong += len(others)
                ok = False
                lines = [f"greeted by another person's name: {others[0].get('best_name') or '(unnamed)'}"] \
                    + row_lines(others[0])
        for line in lines:
            print(f"   {line}")
        greeted.append(ok and answered)
        state = after if after is not None else state
    n = sum(greeted)
    print(f"   greeted by name in {n} of {count} (at least {GREET_NEEDED} needed)")
    return n >= GREET_NEEDED and wrong == 0, greeted, wrong


def run_checks(robot, names, ask_fn=None, owner=None, helper=None, facing_budget_s=CALL_FACING_NO_CHIP_S):
    """Each named check in order; returns ([(name, ok)], summary) and prints the report.
    ask_fn defaults to ask(), looked up when called so a test can stand in for the owner."""
    ask_fn = ask_fn or ask
    robot.adb("logcat", "-c", check=False)
    before = robot.gauges()
    people_before = robot.people() if any(CHECKS[n].people for n in names) else None
    results, samples, notes_all, totals = [], [], [], {k: 0 for k in COUNTERS}
    wrong_names = 0
    for name in names:
        check = CHECKS[name]
        print(f"\n== {name}{f' ({check.ae})' if check.ae else ''} ==")
        print(f"   {check.instruction}")
        if check.page:
            print(f"   Settings page: https://{robot.host}:{LAUNCHER_HTTPS_PORT}{SETTINGS_PATH}#conversation")
        if name == "greet":
            ok, greeted, wrong = run_greet(robot, check, ask_fn, owner)
            samples += greeted
            wrong_names += wrong
            print(f"   {'PASS' if ok else 'FAIL'} {name}")
            results.append((name, ok))
            continue
        faces_before = robot.face_state() if check.faces else None
        answered = ask_fn(check.question)
        if check.faces:
            print(f"   answer: {'yes' if answered else 'no'}")
        if check.face == "answer":
            samples.append(answered)
        elif check.face == "ask":
            samples.append(ask_fn(FACE_QUESTION))
        after = robot.gauges()
        people_after = robot.people() if check.people else people_before
        notes = robot.brain_notes()
        notes_all += notes
        d = counter_delta(before, after)
        if d is not None:
            print(f"   {delta_text(d)}")
            for k, v in d.items():
                totals[k] += v
        ok, evidence = judge(name, d, before, after, people_before, people_after, notes, facing_budget_s)
        if check.faces:
            face_ok, face_lines, _ = face_judge(name, faces_before, robot.face_state(), owner, helper)
            ok = ok and face_ok
            evidence = evidence + face_lines
        for line in evidence:
            print(f"   {line}")
        ok = ok and answered
        print(f"   {'PASS' if ok else 'FAIL'} {name}")
        results.append((name, ok))
        before = after if after is not None else before
        if check.people and people_after is not None:
            people_before = people_after
    summary = {"face_match": (sum(samples), len(samples)), "lean_ins": totals["leanIns"],
               "opened_to_nobody": opened_to_nobody(notes_all), "repeats": totals["repeats"], "counters": totals,
               "wrong_names": wrong_names}
    print_report(results, summary)
    return results, summary


def print_report(results, s):
    print("\n== report ==")
    for name, ok in results:
        print(f"   {'PASS' if ok else 'FAIL'} {name}")
    matched, presented = s["face_match"]
    print(f"   face match {matched}/{presented} known faces greeted by name")
    if s.get("wrong_names"):
        print(f"   greeted by another person's name {s['wrong_names']} time(s): no one should be")
    c = s["counters"]
    print(f"   cues {c['cues']} (strong {c['strongCues']}, weak {c['weakCues']}), searches {c['searches']}, "
          f"faces found {c['facesFound']}, quiet resumes {c['quietResumes']}, held {c['cuesHeld']}, "
          f"dropped {c['cuesDropped']}, retargets {c['retargets']}, shoves {c['shoves']}, repeats {s['repeats']}")
    print(f"   lean-ins {s['lean_ins']} (stopped at nobody); opened to nobody {s['opened_to_nobody']} "
          "(conversations whose first person turn ended in two unanswered listens)")


# ---- before/after latency (face plan U10) ----

def stop_to_first_sound(before, after):
    """ms from the face-found stamp (he stopped in front of someone) to the first
    sound, for the meeting after `before`; None when the cue did not move since
    or a stage is missing."""
    if after is None:
        return None
    st = after["stages"]
    cue, found, sound = st.get("cueAt", 0), st.get("faceFound", 0), st.get("firstSound", 0)
    if before is not None and cue == before["stages"].get("cueAt", 0):
        return None
    if cue <= 0 or found < cue or sound < found:
        return None
    return sound - found


def _stats(values):
    if not values:
        return {"n": 0, "median_s": None, "worst_s": None}
    return {"n": len(values), "median_s": round(statistics.median(values) / 1000, 3),
            "worst_s": round(max(values) / 1000, 3)}


def latency_summary(before_ms, after_ms):
    return {"before": _stats(list(before_ms)), "after": _stats(list(after_ms))}


def latency_lines(summary):
    lines = []
    for label in ("before", "after"):
        st = summary[label]
        if st["n"]:
            lines.append(f"{label}: {st['n']} meetings, median {st['median_s']:.1f} s, worst {st['worst_s']:.1f} s")
        else:
            lines.append(f"{label}: not recorded")
    b, a = summary["before"], summary["after"]
    if b["n"] and a["n"]:
        lines.append(f"change: median {a['median_s'] - b['median_s']:+.1f} s, "
                     f"worst {a['worst_s'] - b['worst_s']:+.1f} s")
    return lines


def latency_path(label):
    return LATENCY_DIR / f"latency-{label}.json"


def read_latency(label):
    try:
        data = json.loads(latency_path(label).read_text())
    except (OSError, ValueError):
        return []
    return [int(m["stop_to_first_sound_ms"]) for m in data.get("meetings", []) if "stop_to_first_sound_ms" in m]


def print_latency_summary():
    print("\n== stop to first sound, before against after ==")
    for line in latency_lines(latency_summary(read_latency("before"), read_latency("after"))):
        print(f"   {line}")


def run_latency(robot, label, build, ask_fn=None, meetings=LATENCY_MEETINGS):
    """Five meetings' stop-to-first-sound from /state's stamps, written to
    tools/face-bench/latency-<label>.json; a meeting whose stamps did not move is
    not counted and asked again (up to twice the count in all)."""
    ask_fn = ask_fn or ask
    print(f"\n== latency {label}: {meetings} meetings ==")
    print(f"   {LATENCY_INSTRUCTION}")
    records, attempts = [], 0
    prev = robot.gauges()
    while len(records) < meetings and attempts < 2 * meetings:
        attempts += 1
        print(f"   -- meeting {len(records) + 1} of {meetings}")
        answered = ask_fn(LATENCY_QUESTION)
        after = robot.gauges()
        ms = stop_to_first_sound(prev, after) if answered else None
        if ms is None:
            print("   stamps not fresh (or no meeting): not counted; try again")
        else:
            print(f"   stop to first sound {ms / 1000:.1f} s")
            records.append({"stages": dict(after["stages"]), "stop_to_first_sound_ms": ms})
        prev = after if after is not None else prev
    LATENCY_DIR.mkdir(parents=True, exist_ok=True)
    path = latency_path(label)
    path.write_text(json.dumps({"label": label, "build": build, "recorded_at": time.strftime("%Y-%m-%dT%H:%M:%S"),
                                "meetings": records}, indent=2) + "\n")
    print(f"   {len(records)} meeting(s) written to {path}")
    print_latency_summary()
    return len(records) == meetings


def build_parser():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--serial", default=DEFAULT_SERIAL)
    ap.add_argument("--only", help="comma-separated checks: " + ",".join(CHECKS))
    ap.add_argument("--build", action="store_true", help="rebuild both APKs before installing")
    ap.add_argument("--no-install", action="store_true",
                    help="skip the install (the build ids are still compared)")
    ap.add_argument("--owner", help="the owner's name exactly as the People list has it (face checks)")
    ap.add_argument("--helper", help="a stored coworker's name exactly as the People list has it (face checks)")
    ap.add_argument("--chip", action="store_true",
                    help="the direction chip is confirmed and calibrated: calls must face you within 3 s, not 12 s")
    ap.add_argument("--latency", choices=("before", "after", "summary"),
                    help="measure stop-to-first-sound over five meetings: before (no install), after, or "
                         "summary (the two files only)")
    return ap


def main(argv=None):
    args = build_parser().parse_args(argv)
    if args.latency == "summary":
        print_latency_summary()
        return 0
    try:
        names = [] if args.latency else parse_only(args.only)
    except ValueError as exc:
        raise QaError(f"!! {exc}")
    missing = sorted({f"--{need}" for n in names for need in FACE_NAMES.get(n, ()) if not getattr(args, need)})
    if missing:
        raise QaError(f"!! the face checks need {' and '.join(missing)}: the names exactly as the People list "
                      "has them (python3 scripts/robot-faces.py people)")
    if args.build:
        build_both()
    robot = Robot(args.serial)
    if threading.current_thread() is threading.main_thread():
        # A SIGTERM (a closed terminal, a kill) must reach the finally block like Ctrl-C does.
        signal.signal(signal.SIGTERM, lambda *_: sys.exit(143))
    try:
        # Before: the build already on the robot is measured, so nothing is installed.
        if args.no_install or args.latency == "before":
            robot.ensure_reachable()
            build = check_build_ids(robot)
        else:
            build = install_both(robot)
        left = robot.cleanup()
        if left:
            print("   debug hooks left on from an earlier run, now off: " + ", ".join(left))
        if args.latency:
            results = [("latency", run_latency(robot, args.latency, build))]
        else:
            results, _ = run_checks(robot, names, owner=args.owner, helper=args.helper,
                                    facing_budget_s=CALL_FACING_CHIP_S if args.chip else CALL_FACING_NO_CHIP_S)
    except KeyboardInterrupt:
        print("\ninterrupted; the debug properties are cleared", file=sys.stderr)
        return 130
    finally:
        robot.cleanup()
    return 0 if all(ok for _, ok in results) else 1


if __name__ == "__main__":
    sys.exit(main())
