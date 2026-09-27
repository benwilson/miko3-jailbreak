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

Then one guided check at a time, in the plan's order: the greet-by-name
recognition (the unconfirmed base), the hallway "hey buddy" from the side with
the six stage stamps (KTD14), a weak-cue lean-in at nobody, a wake word from
behind, his name while he backs out of a wedge, a stranger who declines a name,
a goodbye and the notes on the People page, a silent walk-off, a newcomer
calling mid-conversation, forget-me with the confirm step, a baiting topic, the
switch off, the charger connected, and a persona edit heard in the next
conversation. Each prints its instruction, takes a y/n answer, then reads the
state page's counters and stamps (and the People page or the brain's notes
where they are the evidence) and records PASS or FAIL from both.

The report is the pass list, the observed face-match rate, the lean-in, cue and
repeat counters, and the count of conversations whose first person turn ended
in two unanswered listens (he opened a conversation with nobody) beside the
lean-in count. Every `log.tag.MikoExplore*` hook and the ears-probe properties
are cleared on exit, including on an interrupt.

  python3 scripts/qa-conversation.py                     # install, then every check
  python3 scripts/qa-conversation.py --build             # rebuild both APKs first
  python3 scripts/qa-conversation.py --only hallway,walkoff
  python3 scripts/qa-conversation.py --no-install        # the robot already runs this build
"""
import argparse
import html
import http.client
import json
import re
import signal
import ssl
import subprocess
import sys
import threading
import time
from collections import namedtuple
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
LAUNCHER_APK = REPO / "launcher" / "miko3-launcher.apk"
EXPLORE_APK = REPO / "mode-explore" / "miko3-mode-explore.apk"
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
STAGES = ("cueAt", "turnDone", "faceFound", "matchAnswered", "lineRequested", "firstSound")
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
Check = namedtuple("Check", "ae instruction question face people page")

CHECKS = {
    "greet": Check(
        "", "Stand in front of him at his height at one of his stops and say nothing (the greet-by-name base: "
            "he stored your face and name earlier).",
        "did he say your name instead of asking for it?", "answer", False, False),
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
        "did he finish the escape first and only then turn toward you?", "ask", False, False),
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
        "AE8", "As a named coworker with notes, say 'forget me'. He should ask 'forget you, <your name>?'; "
               "answer 'yes'.",
        "did he confirm the wipe aloud?", "ask", True, False),
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
}
FACE_QUESTION = "did he greet you by name (a face match)?"


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


def names_of(people):
    return {p.id: p.name for p in people or []}


def judge(name, d, before, after, people_before, people_after, notes):
    """(ok, evidence lines) from the counters, stamps, People page and brain notes
    the check names; a check with no rule passes on the answer alone."""
    if name in ("hallway", "behind"):
        lines, fresh = stamp_lines(before, after)
        if name == "behind":
            ok = d is not None and d["strongCues"] >= 1 and d["searches"] >= 1
            return ok, lines + ([] if ok else ["no strong cue and search counted"])
        return fresh, lines + ([] if fresh else ["not all six stages were stamped fresh for this cue"])
    if name == "leanin":
        ok = d is not None and d["leanIns"] >= 1 and d["facesFound"] == 0
        return ok, [] if ok else ["expected one lean-in counted and no face found"]
    if name in ("wedge", "newcomer"):
        ok = d is not None and d["cuesHeld"] >= 1
        return ok, [] if ok else ["expected the cue to be counted as held"]
    if name == "switch":
        ok = d is not None and d["strongCues"] >= 1
        return ok, [] if ok else ["expected the wake word counted as a strong cue"]
    if name == "charger":
        ok = d is not None and d["cues"] == 0
        return ok, [] if ok else ["a cue got through on the charger"]
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


def run_checks(robot, names, ask_fn=None):
    """Each named check in order; returns ([(name, ok)], summary) and prints the report.
    ask_fn defaults to ask(), looked up when called so a test can stand in for the owner."""
    ask_fn = ask_fn or ask
    robot.adb("logcat", "-c", check=False)
    before = robot.gauges()
    people_before = robot.people() if any(CHECKS[n].people for n in names) else None
    results, samples, notes_all, totals = [], [], [], {k: 0 for k in COUNTERS}
    for name in names:
        check = CHECKS[name]
        print(f"\n== {name}{f' ({check.ae})' if check.ae else ''} ==")
        print(f"   {check.instruction}")
        if check.page:
            print(f"   Settings page: https://{robot.host}:{LAUNCHER_HTTPS_PORT}{SETTINGS_PATH}#conversation")
        answered = ask_fn(check.question)
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
        ok, evidence = judge(name, d, before, after, people_before, people_after, notes)
        for line in evidence:
            print(f"   {line}")
        ok = ok and answered
        print(f"   {'PASS' if ok else 'FAIL'} {name}")
        results.append((name, ok))
        before = after if after is not None else before
        if check.people and people_after is not None:
            people_before = people_after
    summary = {"face_match": (sum(samples), len(samples)), "lean_ins": totals["leanIns"],
               "opened_to_nobody": opened_to_nobody(notes_all), "repeats": totals["repeats"], "counters": totals}
    print_report(results, summary)
    return results, summary


def print_report(results, s):
    print("\n== report ==")
    for name, ok in results:
        print(f"   {'PASS' if ok else 'FAIL'} {name}")
    matched, presented = s["face_match"]
    print(f"   face match {matched}/{presented} known faces greeted by name")
    c = s["counters"]
    print(f"   cues {c['cues']} (strong {c['strongCues']}, weak {c['weakCues']}), searches {c['searches']}, "
          f"faces found {c['facesFound']}, quiet resumes {c['quietResumes']}, held {c['cuesHeld']}, "
          f"dropped {c['cuesDropped']}, retargets {c['retargets']}, shoves {c['shoves']}, repeats {s['repeats']}")
    print(f"   lean-ins {s['lean_ins']} (stopped at nobody); opened to nobody {s['opened_to_nobody']} "
          "(conversations whose first person turn ended in two unanswered listens)")


def build_parser():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--serial", default=DEFAULT_SERIAL)
    ap.add_argument("--only", help="comma-separated checks: " + ",".join(CHECKS))
    ap.add_argument("--build", action="store_true", help="rebuild both APKs before installing")
    ap.add_argument("--no-install", action="store_true",
                    help="skip the install (the build ids are still compared)")
    return ap


def main(argv=None):
    args = build_parser().parse_args(argv)
    try:
        names = parse_only(args.only)
    except ValueError as exc:
        raise QaError(f"!! {exc}")
    if args.build:
        build_both()
    robot = Robot(args.serial)
    if threading.current_thread() is threading.main_thread():
        # A SIGTERM (a closed terminal, a kill) must reach the finally block like Ctrl-C does.
        signal.signal(signal.SIGTERM, lambda *_: sys.exit(143))
    try:
        if args.no_install:
            robot.ensure_reachable()
            check_build_ids(robot)
        else:
            install_both(robot)
        left = robot.cleanup()
        if left:
            print("   debug hooks left on from an earlier run, now off: " + ", ".join(left))
        results, _ = run_checks(robot, names)
    except KeyboardInterrupt:
        print("\ninterrupted; the debug properties are cleared", file=sys.stderr)
        return 130
    finally:
        robot.cleanup()
    return 0 if all(ok for _, ok in results) else 1


if __name__ == "__main__":
    sys.exit(main())
