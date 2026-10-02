#!/usr/bin/env python3
"""robot-say.py — make the robot "hear" text, to test conversations and spoken commands
end to end on the real robot with nobody speaking (debug only, 2026-10-02).

The launcher's ears take the text through EarsInject (launcher/src/.../EarsInject.java):
it plays the words through the real wake-word engine, VAD gate and recogniser path, so
Explore receives them over the normal Binder cue path with plausible timing: a call's
early wake cue, a listen's "answering" and answer hold, the provisional answer at the
endpoint, and the end after 2 s of silence. The words go to the launcher's unexported
EarsInjectReceiver by `adb shell am broadcast` (adbd runs as root; `su 0` otherwise).

The injection is inert unless the debug property debug.miko3.ears_inject is 1. This
script sets it at start and always clears it on exit (finally, Ctrl-C, SIGTERM). If a
killed run left it on: adb shell setprop debug.miko3.ears_inject '""'

  python3 scripts/robot-say.py --wake "turn around"           # "Hey Miko, turn around"
  python3 scripts/robot-say.py --wake                         # a bare "Hey Miko"
  python3 scripts/robot-say.py "yes, go find the printer"     # an answer, sent now
  python3 scripts/robot-say.py --script scripts/robot-say-scripts/commands.txt

A script is one step per line; blank lines and # comments are skipped:
  @wake [words]   a call ("Hey Miko" and the words), sent at once
  @pause N        wait N seconds
  anything else   an answer: sent once the robot is listening (the launcher's
                  "ears: conversation listen open" line after the previous step's
                  words were taken), or the run stops after --timeout seconds.

For each step it prints the robot's relevant log lines (turn:, act:, task:, intent:,
conversation over, the ears' listen and answer lines); after the last step it follows
them for --tail seconds or until the conversation is over.
"""
import argparse
import queue
import re
import shlex
import signal
import subprocess
import sys
import threading
import time
from pathlib import Path

PROPERTY = "debug.miko3.ears_inject"
COMPONENT = "com.miko3.launcher/.EarsInjectReceiver"
ACTION = "com.miko3.launcher.action.EARS_INJECT"
TAGS = ("ListenEngine", "EarsInject", "ExploreBrain", "ExploreClaude")
DEFAULT_TIMEOUT_S = 45.0
DEFAULT_TAIL_S = 20.0
ACK_S = 5.0

LISTEN_OPEN = "ears: conversation listen open"
SPENT = "ears: inject spent"
CONVERSATION_OVER = "conversation over"
# The lines worth showing, by the start of their message.
RELEVANT = ("turn:", "act:", "task:", "intent:", CONVERSATION_OVER, "listening for a reply", "answering the call",
            "listen past", "ears: conversation listen", "ears: inject", "queued ", "refused ", "ignored: ")
_LOG = re.compile(r"^(?:\d\d-\d\d \d\d:\d\d:\d\d\.\d+\s+)?[VDIWEF]/\s*([\w.$-]+)\s*\(\s*\d+\):\s?(.*)$")


class SayError(Exception):
    pass


class Step:
    """One line of a script: kind is "wake", "say" or "pause"."""

    def __init__(self, kind, text="", seconds=0.0):
        self.kind = kind
        self.text = text
        self.seconds = seconds

    def __eq__(self, other):
        return (self.kind, self.text, self.seconds) == (other.kind, other.text, other.seconds)

    def __repr__(self):
        return f"Step({self.kind!r}, {self.text!r}, {self.seconds!r})"

    def describe(self):
        if self.kind == "wake":
            return "Hey Miko" + (f", {self.text}" if self.text else "")
        if self.kind == "pause":
            return f"pause {self.seconds:g} s"
        return self.text


def parse_script(text):
    """The steps of a script file's text. Raises SayError naming the bad line."""
    steps = []
    for no, raw in enumerate(text.splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("@"):
            word, _, rest = line[1:].partition(" ")
            rest = rest.strip()
            if word == "wake":
                steps.append(Step("wake", rest))
            elif word == "pause":
                try:
                    seconds = float(rest)
                except ValueError:
                    raise SayError(f"line {no}: @pause needs a number of seconds, got {rest!r}") from None
                if seconds < 0:
                    raise SayError(f"line {no}: @pause cannot be negative")
                steps.append(Step("pause", seconds=seconds))
            else:
                raise SayError(f"line {no}: unknown directive @{word} (use @wake or @pause)")
        else:
            steps.append(Step("say", line))
    if not any(s.kind != "pause" for s in steps):
        raise SayError("the script has nothing to say")
    return steps


def parse_log(line):
    """(tag, message) of a `logcat -v time` line, or None."""
    m = _LOG.match(line.rstrip("\r\n"))
    return (m.group(1), m.group(2)) if m else None


def relevant(message):
    return message.startswith(RELEVANT)


def broadcast_command(text, wake, root=True):
    """The remote shell command delivering one utterance to the launcher's receiver."""
    parts = ["am", "broadcast", "--user", "0", "-f", "0x10000000", "-n", COMPONENT, "-a", ACTION]
    if text:
        parts += ["--es", "text", text]
    if wake:
        parts += ["--ez", "wake", "true"]
    cmd = " ".join(shlex.quote(p) for p in parts)
    return cmd if root else "su 0 " + cmd


def run(cmd, timeout=30):
    try:
        return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    except (OSError, subprocess.TimeoutExpired) as e:
        return subprocess.CompletedProcess(cmd, 1, "", str(e))


class Robot:
    def __init__(self, serial=None, runner=run):
        self.serial = serial
        self.runner = runner
        self.root = True

    def cmd(self, *args):
        return ["adb"] + (["-s", self.serial] if self.serial else []) + list(args)

    def shell(self, command, check=True):
        r = self.runner(self.cmd("shell", command))
        if check and r.returncode != 0:
            raise SayError(f"adb shell {command!r} failed: {(r.stderr or r.stdout).strip()}")
        return (r.stdout or "").strip()

    def connect(self):
        self.shell("true")
        self.root = self.shell("id -u", check=False) == "0"

    def arm(self):
        left = self.shell(f"getprop {PROPERTY}", check=False)
        if left == "1":
            print(f"note: {PROPERTY} was already 1 (an interrupted run?); it is cleared on exit")
        self.shell(f"setprop {PROPERTY} 1")

    def disarm(self):
        self.shell(f'setprop {PROPERTY} ""', check=False)

    def device_time(self):
        return self.shell("date +'%m-%d %H:%M:%S.000'", check=False)

    def say(self, text, wake):
        out = self.shell(broadcast_command(text, wake, self.root))
        if "Broadcast completed" not in out:
            raise SayError(f"the broadcast did not complete: {out}")


class LogStream:
    """`adb logcat` for the launcher's and Explore's tags from now on, read on a thread."""

    def __init__(self, robot):
        since = robot.device_time()
        # `adb logcat` hands its arguments over as they are (robot 2026-10-02: a quoted time
        # reached logcat with its quotes, "not in time format", and the reader saw nothing).
        args = ["logcat", "-v", "time"] + (["-T", since] if since else ["-T", "1"]) + ["-s"]
        args += [f"{t}:I" for t in TAGS]
        self.proc = subprocess.Popen(robot.cmd(*args), stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                     text=True, errors="replace")
        self.lines = queue.Queue()
        self.skip_first = not since
        threading.Thread(target=self._pump, daemon=True).start()

    def _pump(self):
        for line in self.proc.stdout:
            if self.skip_first:
                self.skip_first = False
                continue
            parsed = parse_log(line)
            if parsed:
                self.lines.put(parsed)

    def get(self, timeout):
        try:
            return self.lines.get(timeout=max(0.0, timeout))
        except queue.Empty:
            return None

    def close(self):
        try:
            self.proc.terminate()
        except OSError:
            pass


class Runner:
    """Sends steps and shows the log; stream.get(timeout) gives (tag, message) or None."""

    def __init__(self, robot, stream, out=print, clock=time.monotonic, sleep=time.sleep,
                 timeout=DEFAULT_TIMEOUT_S, tail=DEFAULT_TAIL_S):
        self.robot = robot
        self.stream = stream
        self.out = out
        self.clock = clock
        self.sleep = sleep
        self.timeout = timeout
        self.tail = tail
        self.listening = False
        self.injecting = False
        self.over = False
        self.acked = None

    def pump(self, seconds, until=None):
        """Shows relevant lines for up to seconds; stops early once until() holds. Returns until()'s last value."""
        end = self.clock() + seconds
        while True:
            if until is not None and until():
                return True
            left = end - self.clock()
            if left <= 0:
                return False
            item = self.stream.get(min(left, 0.5))
            if item is not None:
                self.see(*item)

    def see(self, tag, message):
        if message.startswith(SPENT):
            self.injecting = False
        elif message.startswith(LISTEN_OPEN) and not self.injecting:
            # A listen that opened while the words played took them; the next one waits for us.
            self.listening = True
        if message.startswith(CONVERSATION_OVER):
            self.over = True
        if tag == "EarsInject" or message.startswith("ears: inject refused"):
            self.acked = message
        if relevant(message):
            self.out(f"    {tag}: {message}")

    def send(self, step):
        self.listening = False
        self.injecting = True
        self.over = False
        self.acked = None
        self.robot.say(step.text, step.kind == "wake")
        self.pump(ACK_S, lambda: self.acked is not None)
        if self.acked is None:
            self.out("    !! no word from the launcher: is a build with EarsInjectReceiver installed?")
            self.injecting = False
        elif not self.acked.startswith("queued"):
            raise SayError(f"the launcher did not take it: {self.acked}")

    def run(self, steps):
        """True when every step was sent; False when the robot never listened for one."""
        for i, step in enumerate(steps, 1):
            self.out(f"[{i}/{len(steps)}] {step.describe()}")
            if step.kind == "pause":
                self.pump(step.seconds)
                continue
            if step.kind == "say" and i > 1 and not self.listening:
                self.out("    (waiting for the robot to listen)")
                if not self.pump(self.timeout, lambda: self.listening):
                    self.out(f"    !! the robot did not listen within {self.timeout:g} s; stopping")
                    return False
            self.send(step)
        self.out(f"(following for up to {self.tail:g} s)")
        self.pump(self.tail, lambda: self.over)
        return True


def main(argv=None, robot=None, stream_factory=LogStream):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("text", nargs="?", default="", help="the words heard")
    p.add_argument("--wake", action="store_true", help="a call: 'Hey Miko' before the words")
    p.add_argument("--script", type=Path, help="a file of steps (see above)")
    p.add_argument("--serial")
    p.add_argument("--timeout", type=float, default=DEFAULT_TIMEOUT_S,
                   help="seconds to wait for the robot to listen before each answer")
    p.add_argument("--tail", type=float, default=DEFAULT_TAIL_S, help="seconds to follow the log after the last step")
    args = p.parse_args(argv)

    try:
        if args.script:
            if args.text or args.wake:
                raise SayError("--script takes no text or --wake")
            steps = parse_script(args.script.read_text())
        elif args.text.strip() or args.wake:
            steps = [Step("wake" if args.wake else "say", args.text.strip())]
        else:
            raise SayError("say something: TEXT, --wake or --script FILE")
    except (SayError, OSError) as e:
        print(f"robot-say: {e}", file=sys.stderr)
        return 2

    robot = robot or Robot(args.serial)

    def on_term(signum, frame):
        raise KeyboardInterrupt

    old_term = signal.signal(signal.SIGTERM, on_term) if threading.current_thread() is threading.main_thread() else None
    stream = None
    try:
        robot.connect()
        robot.arm()
        stream = stream_factory(robot)
        ok = Runner(robot, stream, timeout=args.timeout, tail=args.tail).run(steps)
        return 0 if ok else 1
    except SayError as e:
        print(f"robot-say: {e}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("robot-say: interrupted", file=sys.stderr)
        return 130
    finally:
        if stream is not None:
            stream.close()
        robot.disarm()
        print(f"cleared {PROPERTY}")
        if old_term is not None:
            signal.signal(signal.SIGTERM, old_term)


if __name__ == "__main__":
    sys.exit(main())
