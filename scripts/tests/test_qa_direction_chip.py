"""Tests for scripts/qa-direction-chip.py (hey-miko plan U1; R8, KTD10, KTD12,
KTD13): the read-only evidence it gathers about /dev/ttyS1, the fixed rule that
decides confirmation, the port property it writes only after confirmation and
unsets unless the restarted launcher logs the NC backend without dying, the
parse of the chip's streamed frames, the termios pre-check that must pass before
the stream is read, side calibration and the --watch mode.

Every adb call goes through a fake robot that records it, so the tests can
check that no run ever writes to the node, opens it in any but the two allowed
read-only forms, or sets anything but persist.miko3.voice_dir.*. Nothing here
needs the robot."""
import contextlib
import importlib.util
import io
import math
import re
import unittest
import zlib
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCRIPT = HERE.parent / "qa-direction-chip.py"


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load {path}")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


chip = load("qa_direction_chip", SCRIPT)
ears = chip.ears

DRIVER = ("== /proc/tty/driver/serial\n"
          "serinfo:1.0 driver revision:\n"
          "0: uart:MTK mmio:0x11002000 irq:91 tx:18231 rx:4410 RTS|DTR\n"
          "1: uart:MTK mmio:0x11003000 irq:92 tx:0 rx:0\n"
          "2: uart:MTK mmio:0x11004000 irq:93 tx:0 rx:0\n")
DTREE = ("/serial@11003000\n"
         "mid_dsp  power  uevent\n"
         "/proc/device-tree/odm/gpio_dsp\n")
DMESG = "[    1.234567] 11003000.serial: ttyS1 at MMIO 0x11003000 (irq = 92, base_baud = 1625000) is a MTK UART\n"
NC_LOG = "09-28 10:00:00.000  1234  1234 I nc_dsp  : createUART /dev/ttyS1 fd=41 tx 14 rx 38\n"


# Real frames the chip streamed on the robot (2026-09-29): 19 bytes, XXUB a3 03 seq 01 05 00,
# CRC32 LE over bytes 0..9 at 10..13, then a 5-byte payload whose byte 0 is the raw value.
REAL_85 = bytes.fromhex("58585542a30344010500de9c7afe55f64a03c9")
REAL_50 = bytes.fromhex("58585542a3038f010500d9f5365f320dbed51a")

# What the launcher leaves the port as: input flags 0, lflag with no ECHO (0x8) bit.
RAW_TERMIOS = "0:0:18b2:0:3:1c:7f:15:4:0:1:0:11:13:1a:0:12:f:17:16:0:0:0:0:0:0:0:0:0:0:0:0:0:0:0:0"
COOKED_TERMIOS = "ffffff14:5:4bf:8a3b:3:1c:7f:15:4:0:1:0:11:13:1a:0:12:f:17:16:0:0:0:0:0:0:0:0:0:0:0:0:0:0:0:0"
ECHO_TERMIOS = "0:0:18b2:8:3:1c:7f:15:4:0:1:0:11:13:1a:0:12:f:17:16:0:0:0:0:0:0:0:0:0:0:0:0:0:0:0:0"

ROBOT_DATE = "09-29 10:00:00.000"
LAUNCHER_PID = 4321


def stream_frame(seq, raw, tail=b"\xf6\x4a\x03\xc9"):
    head = b"XXUB" + bytes([0xA3, 0x03, seq & 0xFF, 0x01, 0x05, 0x00])
    return head + zlib.crc32(head).to_bytes(4, "little") + bytes([raw]) + tail


def stream_of(raws):
    """A capture: frames with these raw values, with junk and a torn frame between them."""
    out = bytearray(b"\x00\xffXXU")
    for i, raw in enumerate(raws):
        frame = stream_frame(i, raw)
        out += frame[:11] + b"\x13" + frame
    return bytes(out) + stream_frame(99, 7)[:15]


def launcher_log(*lines, pid=LAUNCHER_PID):
    head = [f"09-29 10:00:00.200  1000  1100 I ActivityManager: Start proc {pid}:com.miko3.launcher/u0a52 "
            f"for activity com.miko3.launcher/.MainActivity"]
    return "\n".join(head + [line.format(pid=pid) for line in lines]) + "\n"


NC_LINE = "09-29 10:00:06.000  {pid}  4400 I MikoLauncher: voice direction: backend NC (/dev/ttyS1, sides left >= 100, right <= 60)"
NONE_LINE = "09-29 10:00:06.000  {pid}  4400 I MikoLauncher: voice direction: backend NONE (no port)"
DIED_LINE = "09-29 10:00:08.000  1000  1120 I ActivityManager: Process com.miko3.launcher (pid {pid}) has died: fore TOP"
SIG_LINE = "09-29 10:00:07.900  {pid}  4410 E chromium: [FATAL] Handling signal 11, re-raising with SIG_DFL"


class FakeRobot(ears.Robot):
    """Answers each evidence command from a canned output, keeps a property table
    for setprop and getprop, answers stty -g, the stream capture (one canned
    stream per capture, handed over by adb pull), date, logcat and pidof, and
    records every adb call in order."""

    def __init__(self, outputs=None, props=None, setprop_sticks=True, timeouts=(), termios=RAW_TERMIOS,
                 streams=(), logcat="", pidof=""):
        super().__init__("fake:5555")
        self.timeouts = set(timeouts)
        self.calls = []
        self.outputs = dict(outputs or {})
        self.props = dict(props or {})
        self.setprop_sticks = setprop_sticks
        self.termios = termios
        self.streams = list(streams)
        self.captured = None
        self.logcat = logcat
        self.pidof = pidof

    def adb(self, *args, check=True):
        self.calls.append(args)
        if args[:2] == ("shell", "setprop"):
            if self.setprop_sticks:
                value = args[3]
                self.props[args[2]] = "" if value == '""' else value
            return ""
        if args[:2] == ("shell", "getprop"):
            return self.props.get(args[2], "") + "\n"
        if args[0] == "pull":
            Path(args[2]).write_bytes(self.captured if self.captured is not None else b"")
            return ""
        if args[:1] == ("shell",) and len(args) == 2:
            cmd = args[1]
            for name, script in chip.EVIDENCE_COMMANDS.items():
                if cmd == script:
                    if name in self.timeouts:
                        raise ears.ProbeError(f"!! adb timed out: adb shell {script}")
                    return self.outputs.get(name, "")
            if cmd == chip.TERMIOS_COMMAND:
                return self.termios + "\n"
            if cmd.startswith("exec 3<"):
                self.captured = self.streams.pop(0) if self.streams else b""
                return ""
            if cmd.startswith("date"):
                return ROBOT_DATE + "\n"
            if cmd.startswith("logcat"):
                return self.logcat
            if cmd.startswith("pidof"):
                return self.pidof
        return ""

    def setprops(self):
        return [(c[2], c[3]) for c in self.calls if c[:2] == ("shell", "setprop")]

    def captures(self):
        return [c for c in self.calls if c[:1] == ("shell",) and len(c) == 2 and c[1].startswith("exec 3<")]

def evidence_outputs(**over):
    out = {"holder": "", "driver": DRIVER, "dmesg": DMESG, "dtree": DTREE, "vendor_log": ""}
    out.update(over)
    return out


def quiet(fn, *args, **kw):
    with contextlib.redirect_stdout(io.StringIO()):
        return fn(*args, **kw)


# Writes aimed at the node: redirections, dd of=, tee, and a read-write open.
NODE_WRITE = re.compile(r"(>>?\s*|of=|tee\s+(-a\s+)?)/dev/ttyS1\b|<>\s*/dev/ttyS1\b")
# Any form that opens the node: a redirect from it, a program reading it, stty on it.
NODE_OPEN = re.compile(r"<>?\s*/dev/ttyS1|\b(?:cat|od|dd|hexdump|stty|timeout)\b[^;|]*/dev/ttyS1")
# The only two opens allowed, both read-only (robot, 2026-09-29): the stream capture's
# `exec 3</dev/ttyS1;` and the termios pre-check `stty -F /dev/ttyS1 -g` on its own.
ALLOWED_OPEN = re.compile(r"(?:^|;\s*)exec 3</dev/ttyS1(?=;)|^stty -F /dev/ttyS1 -g$")


def node_writes(calls):
    return [c for c in calls if any(NODE_WRITE.search(str(a)) for a in c)]


def node_opens(calls):
    """Calls that open the node in any way other than the two allowed read-only forms."""
    return [c for c in calls if any(NODE_OPEN.search(ALLOWED_OPEN.sub("", str(a))) for a in c)]


class StreamFrameTest(unittest.TestCase):
    def test_the_real_frames_parse_to_their_raw_values(self):
        self.assertEqual([(f.seq, f.raw) for f in chip.stream_frames(REAL_85)], [(0x44, 85)])
        self.assertEqual([(f.seq, f.raw) for f in chip.stream_frames(REAL_50)], [(0x8F, 50)])

    def test_the_test_builder_makes_the_real_layout(self):
        self.assertEqual(stream_frame(0x44, 85), REAL_85)

    def test_frames_amid_junk_and_torn_frames(self):
        data = (b"\x00\xff\x13XXU" + REAL_85[:12] + REAL_85 + b"XXUB\x01\x02" + REAL_50[:7]
                + REAL_50 + b"\x7f" + REAL_85[:18])
        frames = chip.stream_frames(data)
        self.assertEqual([f.raw for f in frames], [85, 50])
        self.assertEqual(frames[0].offset, 6 + 12)

    def test_a_wrong_crc_or_another_frame_type_is_ignored(self):
        bad_crc = REAL_85[:10] + bytes([REAL_85[10] ^ 1]) + REAL_85[11:]
        head = b"XXUB" + bytes([0x03, 0x01, 0x03, 0, 0, 0])
        request = head + zlib.crc32(head).to_bytes(4, "little") + b"\x00" * 5
        other_len = b"XXUB" + bytes([0xA3, 0x03, 1, 0x01, 0x06, 0x00])
        other_len += zlib.crc32(other_len).to_bytes(4, "little") + b"\x55" * 5
        for data in (bad_crc, request, other_len, b"", b"XXUB"):
            self.assertEqual(chip.stream_frames(data), [], data.hex())

    def test_the_capture_helper_round_trips(self):
        self.assertEqual([f.raw for f in chip.stream_frames(stream_of([35, 80, 100]))], [35, 80, 100])


class TermiosTest(unittest.TestCase):
    def test_raw_with_echo_off_is_accepted(self):
        self.assertTrue(chip.termios_raw("0:0:18b2:0:3:1c")[0])
        self.assertTrue(chip.termios_raw(RAW_TERMIOS + "\n")[0])

    def test_input_flags_or_echo_or_garbage_are_refused(self):
        for g in ("ffffff14:5:4bf:8a3b:3:1c", COOKED_TERMIOS, ECHO_TERMIOS, "0:0:18b2:8a3b:3",
                  "", "stty: /dev/ttyS1: Permission denied", "0:0:18b2", "zz:0:0:0:0"):
            ok, why = chip.termios_raw(g)
            self.assertFalse(ok, g)
            self.assertTrue(why, g)

    def test_the_pre_check_is_one_read_only_stty(self):
        self.assertEqual(chip.TERMIOS_COMMAND, "stty -F /dev/ttyS1 -g")

    def test_the_capture_command_is_the_read_only_form(self):
        self.assertEqual(chip.capture_command(11),
                         "exec 3</dev/ttyS1; timeout 11 cat <&3 > /data/local/tmp/cal.bin; exec 3<&-")
        self.assertEqual(node_writes([("shell", chip.capture_command(11))]), [])
        self.assertEqual(node_opens([("shell", chip.capture_command(11))]), [])

    def test_the_open_detector_allows_only_the_two_read_only_forms(self):
        for cmd in ("cat /dev/ttyS1", "exec 3<> /dev/ttyS1", "exec 3<>/dev/ttyS1; cat <&3",
                    "stty -F /dev/ttyS1 raw -echo", "stty -F /dev/ttyS1 -g raw", "stty -F /dev/ttyS1",
                    "od -An -tx1 < /dev/ttyS1", "timeout 3 cat /dev/ttyS1", "exec 4</dev/ttyS1; cat <&4",
                    "exec 3< /dev/ttyS1; cat <&3"):
            self.assertTrue(node_opens([("shell", cmd)]), cmd)
        for cmd in (chip.TERMIOS_COMMAND, chip.capture_command(10)):
            self.assertEqual(node_opens([("shell", cmd)]), [], cmd)

class DecideTest(unittest.TestCase):
    def evidence(self, **over):
        return quiet(chip.gather, FakeRobot(evidence_outputs(**over)))

    def test_an_nc_dsp_log_line_naming_ttys1_confirms(self):
        verdict = chip.decide(self.evidence(vendor_log=NC_LOG), owner_confirms=False)
        self.assertTrue(verdict.confirmed)
        self.assertEqual(verdict.reason, "vendor log")
        self.assertEqual(chip.format_verdict(verdict).splitlines()[0], "CONFIRMED (vendor log)")

    def test_an_nc_dsp_file_whose_lines_name_ttys1_confirms(self):
        log = "== /sdcard/nc_dsp.log\nopen /dev/ttyS1 115200 ok\n"
        self.assertEqual(chip.decide(self.evidence(vendor_log=log), owner_confirms=False).reason, "vendor log")

    def test_the_logged_evidence_command_itself_never_confirms(self):
        """Seen on the robot (2026-09-29): logcat records the adb shell command, whose text
        names both nc_dsp and ttyS1, and the check confirmed the port from its own echo."""
        log = ("09-29 10:39:38.411   376   376 I ADB_SERVICES: service_to_fd shell,v2,raw:"
               + chip.EVIDENCE_COMMANDS["vendor_log"] + "\n")
        self.assertFalse(chip.decide(self.evidence(vendor_log=log), owner_confirms=False).confirmed)

    def test_a_line_from_another_tag_mentioning_nc_dsp_does_not_confirm(self):
        log = "09-28 10:00:00.000  1 1 I SomeApp : looked for nc_dsp on /dev/ttyS1\n"
        self.assertFalse(chip.decide(self.evidence(vendor_log=log), owner_confirms=False).confirmed)

    def test_an_nc_dsp_line_about_another_port_does_not_confirm(self):
        log = "09-28 10:00:00.000  1 1 I nc_dsp  : createUART /dev/ttyMT2 failed\n"
        self.assertFalse(chip.decide(self.evidence(vendor_log=log), owner_confirms=False).confirmed)

    def test_a_ttys1_line_from_something_else_does_not_confirm(self):
        log = "09-28 10:00:00.000  1 1 I MikoDmd : opened /dev/ttyS1\n"
        self.assertFalse(chip.decide(self.evidence(vendor_log=log), owner_confirms=False).confirmed)

    def test_device_tree_and_driver_evidence_alone_is_unconfirmed(self):
        ev = self.evidence()
        verdict = chip.decide(ev, owner_confirms=False)
        self.assertFalse(verdict.confirmed)
        self.assertIsNone(verdict.reason)

    def test_owner_confirmation_is_recorded_explicitly(self):
        verdict = chip.decide(self.evidence(), owner_confirms=True)
        self.assertTrue(verdict.confirmed)
        self.assertEqual(verdict.reason, "owner")
        self.assertEqual(chip.format_verdict(verdict).splitlines()[0], "CONFIRMED (owner)")

    def test_ttys1_driver_counters_are_parsed(self):
        self.assertEqual(chip.port_counters(DRIVER), {"tx": 0, "rx": 0})
        self.assertIsNone(chip.port_counters("cat: /proc/tty/driver/serial: Permission denied\n"))


class GatherTest(unittest.TestCase):
    def test_every_evidence_command_is_read_only(self):
        for name, script in chip.EVIDENCE_COMMANDS.items():
            self.assertIsNone(NODE_WRITE.search(script), name)
            self.assertNotIn("setprop", script, name)

    def test_no_evidence_step_ever_opens_the_node(self):
        """Seen on the robot (2026-09-29): an stty read opened ttyS1 in cooked mode with echo
        on while the chip was talking; the counters went from tx 0 to tx 98. Opening a tty
        at all can write to it, so identification only reads /proc, /sys and logs."""
        for name, script in chip.EVIDENCE_COMMANDS.items():
            self.assertIsNone(NODE_OPEN.search(script), f"{name}: {script}")
        self.assertFalse(hasattr(chip, "PASSIVE_READ"))
        self.assertNotIn("settings", chip.EVIDENCE_COMMANDS)

    def test_gather_covers_ktd13s_read_only_list(self):
        for name in ("holder", "driver", "dmesg", "dtree", "vendor_log"):
            self.assertIn(name, chip.EVIDENCE_COMMANDS)

    def test_gather_runs_only_the_evidence_commands(self):
        robot = FakeRobot(evidence_outputs())
        ev = quiet(chip.gather, robot)
        self.assertEqual([c for c in robot.calls if c[:1] == ("shell",)],
                         [("shell", s) for s in chip.EVIDENCE_COMMANDS.values()])
        self.assertIsNone(ev.passive)
        self.assertIn("never opened", ev.passive_note)

    def test_a_timed_out_evidence_step_is_recorded_and_the_rest_still_run(self):
        """Seen on the robot (2026-09-29): the holder scan timed out and aborted the whole run."""
        robot = FakeRobot(evidence_outputs(), timeouts={"holder"})
        ev = quiet(chip.gather, robot)
        self.assertIn("unavailable", ev.sections["holder"])
        self.assertIn("timed out", ev.sections["holder"])
        for name in ("driver", "dmesg", "dtree", "vendor_log"):
            self.assertIn(("shell", chip.EVIDENCE_COMMANDS[name]), robot.calls, name)
        self.assertEqual(ev.sections["driver"], DRIVER)

    def test_the_holder_scan_lists_each_process_once(self):
        """One ls per process, not a shell loop per open file: 300+ processes timed out on the robot."""
        script = chip.EVIDENCE_COMMANDS["holder"]
        self.assertNotIn("readlink", script)
        self.assertIn("ls -l", script)

    def test_the_report_shows_every_section_and_the_traffic_counters(self):
        ev = quiet(chip.gather, FakeRobot(evidence_outputs(holder="812 /vendor/bin/dspd\n")))
        text = chip.format_evidence(ev)
        for needle in ("holder", "812 /vendor/bin/dspd", "tx 0 rx 0", "serial@11003000", "never opened"):
            self.assertIn(needle, text)


class IdentifyTest(unittest.TestCase):
    def run_identify(self, robot, owner_confirms=False, sleep=None):
        sleeps = []
        return quiet(chip.identify, robot, owner_confirms=owner_confirms,
                     sleep=sleep or sleeps.append), sleeps

    def test_unconfirmed_never_issues_a_setprop(self):
        robot = FakeRobot(evidence_outputs(), logcat=launcher_log(NC_LINE))
        code, _ = self.run_identify(robot)
        self.assertNotEqual(code, 0)
        self.assertEqual(robot.setprops(), [])
        self.assertFalse(any("setprop" in " ".join(map(str, c)) for c in robot.calls), robot.calls)
        self.assertFalse(any("logcat" in " ".join(map(str, c)) and "-T" in " ".join(map(str, c))
                             for c in robot.calls))

    def test_confirmed_keeps_the_port_when_the_launcher_reports_the_nc_backend(self):
        robot = FakeRobot(evidence_outputs(), logcat=launcher_log(NC_LINE))
        code, sleeps = self.run_identify(robot, owner_confirms=True)
        self.assertEqual(code, 0)
        self.assertEqual(robot.setprops(), [(chip.PORT_PROPERTY, "/dev/ttyS1")])
        self.assertEqual(robot.props[chip.PORT_PROPERTY], "/dev/ttyS1")
        # The launcher restarts after the property is set, so it opens the chip afresh,
        # and the log is read from just before that restart, after the owner has spoken.
        set_at = robot.calls.index(("shell", "setprop", chip.PORT_PROPERTY, "/dev/ttyS1"))
        after = robot.calls[set_at:]
        stop_at = after.index(("shell", "am", "force-stop", chip.LAUNCHER_PACKAGE))
        date_at = next(i for i, c in enumerate(after) if c[1].startswith("date"))
        log_at = next(i for i, c in enumerate(after) if c[1].startswith("logcat"))
        self.assertLess(date_at, stop_at)
        self.assertLess(stop_at, log_at)
        self.assertIn(f"-T '{ROBOT_DATE}'", after[log_at][1])
        self.assertTrue(10 <= sum(sleeps) <= 20, sleeps)

    def test_confirmed_run_tells_the_owner_to_speak(self):
        robot = FakeRobot(evidence_outputs(), logcat=launcher_log(NC_LINE))
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            chip.identify(robot, owner_confirms=True, sleep=lambda s: None)
        self.assertIn("say something", out.getvalue().lower())

    def assert_unset(self, logcat, pidof=""):
        robot = FakeRobot(evidence_outputs(), logcat=logcat, pidof=pidof)
        code, _ = self.run_identify(robot, owner_confirms=True)
        self.assertNotEqual(code, 0, logcat)
        self.assertEqual(robot.setprops(), [(chip.PORT_PROPERTY, "/dev/ttyS1"), (chip.PORT_PROPERTY, '""')])
        self.assertEqual(robot.props[chip.PORT_PROPERTY], "", logcat)
        # Restarted again so the launcher lets go of the port.
        unset_at = robot.calls.index(("shell", "setprop", chip.PORT_PROPERTY, '""'))
        self.assertIn(("shell", "am", "force-stop", chip.LAUNCHER_PACKAGE), robot.calls[unset_at:])

    def test_no_backend_line_unsets_the_port(self):
        self.assert_unset(launcher_log())
        self.assert_unset("")

    def test_backend_none_unsets_the_port(self):
        self.assert_unset(launcher_log(NONE_LINE))

    def test_a_launcher_that_died_unsets_the_port(self):
        self.assert_unset(launcher_log(NC_LINE, DIED_LINE))

    def test_a_sig_dfl_from_the_launcher_unsets_the_port(self):
        self.assert_unset(launcher_log(NC_LINE, SIG_LINE))

    def test_the_backend_line_must_come_from_the_restarted_launcher(self):
        other = "09-29 10:00:06.000  777  780 I MikoExplore: voice direction: backend NC (/dev/ttyS1)"
        self.assert_unset(launcher_log(other))

    def test_the_launcher_pid_may_come_from_pidof(self):
        log = NC_LINE.format(pid=5555) + "\n"
        robot = FakeRobot(evidence_outputs(), logcat=log, pidof="5555\n")
        code, _ = self.run_identify(robot, owner_confirms=True)
        self.assertEqual(code, 0)
        self.assert_unset(log, pidof="")
        self.assert_unset(log + SIG_LINE.format(pid=5555) + "\n", pidof="5555\n")

    def test_another_process_dying_does_not_count(self):
        died = "09-29 10:00:08.000  1000  1120 I ActivityManager: Process com.other.app (pid 900) has died"
        robot = FakeRobot(evidence_outputs(), logcat=launcher_log(NC_LINE, died))
        self.assertEqual(self.run_identify(robot, owner_confirms=True)[0], 0)

    def test_an_interrupted_wait_still_unsets_the_port(self):
        robot = FakeRobot(evidence_outputs(), logcat=launcher_log(NC_LINE))

        def sleep(_):
            raise KeyboardInterrupt

        with self.assertRaises(KeyboardInterrupt):
            self.run_identify(robot, owner_confirms=True, sleep=sleep)
        self.assertEqual(robot.props[chip.PORT_PROPERTY], "")

    def test_a_setprop_that_does_not_stick_is_an_error(self):
        robot = FakeRobot(evidence_outputs(), setprop_sticks=False)
        with self.assertRaises(ears.ProbeError) as ctx:
            self.run_identify(robot, owner_confirms=True)
        self.assertIn("root", str(ctx.exception))

    def test_a_vendor_log_confirmation_needs_no_owner_flag(self):
        robot = FakeRobot(evidence_outputs(vendor_log=NC_LOG), logcat=launcher_log(NC_LINE))
        self.assertEqual(self.run_identify(robot)[0], 0)

    def test_the_ears_probe_is_never_used(self):
        """It crashes the launcher every time (robot, 2026-09-29), on main too."""
        robot = FakeRobot(evidence_outputs(), logcat=launcher_log(NC_LINE))
        self.run_identify(robot, owner_confirms=True)
        text = " ".join(" ".join(map(str, c)) for c in robot.calls)
        self.assertNotIn("ears_probe", text)
        self.assertNotIn("forward", text)
        self.assertFalse(hasattr(chip, "run_probe"))

    def test_no_run_ever_writes_to_the_node_and_only_voice_dir_props_are_set(self):
        runs = [
            (FakeRobot(evidence_outputs(), logcat=launcher_log(NC_LINE)), False),
            (FakeRobot(evidence_outputs(vendor_log=NC_LOG), logcat=launcher_log(NC_LINE)), False),
            (FakeRobot(evidence_outputs(), logcat=launcher_log(NONE_LINE)), True),
            (FakeRobot(evidence_outputs(), logcat=launcher_log(NC_LINE, DIED_LINE)), True),
            (FakeRobot(evidence_outputs(), logcat=launcher_log(NC_LINE)), True),
        ]
        for robot, owner in runs:
            self.run_identify(robot, owner_confirms=owner)
            self.assertEqual(node_writes(robot.calls), [], robot.calls)
            self.assertEqual(node_opens(robot.calls), [], robot.calls)
            for key, _ in robot.setprops():
                self.assertTrue(key.startswith("persist.miko3.voice_dir."), key)

    def test_the_node_write_detector_catches_the_usual_forms(self):
        for cmd in ("echo -n x > /dev/ttyS1", "dd if=/tmp/f of=/dev/ttyS1", "cat f >> /dev/ttyS1",
                    "printf x | tee /dev/ttyS1", "exec 3<> /dev/ttyS1"):
            self.assertTrue(node_writes([("shell", cmd)]), cmd)
        self.assertFalse(node_writes([("shell", chip.EVIDENCE_COMMANDS["holder"])]))


def round5(x):
    """The rule's rounding: to the nearest multiple of 5, halves up."""
    return int(math.floor(x / 5 + 0.5)) * 5


# Today's readings on the robot (2026-09-29), the owner about 1 m away, counting aloud.
TODAY = {"front": [78, 80, 82], "left": [95, 100, 110], "right": [30, 35, 45], "behind": [85, 90, 100]}
PORT_SET = {chip.PORT_PROPERTY: "/dev/ttyS1"}


class SideSuggestionTest(unittest.TestCase):
    def test_todays_medians_give_the_rule_s_thresholds(self):
        medians = {"right": 35, "front": 80, "behind": 90, "left": 100}
        s = chip.suggest_sides(medians)
        middle = (medians["front"], medians["behind"])
        self.assertEqual(s.right, round5((medians["right"] + min(middle)) / 2))
        self.assertEqual(s.left, round5((medians["left"] + max(middle)) / 2))
        self.assertEqual((s.right, s.left), (60, 95))  # what the rule gives today, as a cross-check

    def test_one_of_front_or_behind_is_enough(self):
        s = chip.suggest_sides({"right": 30, "front": 70, "left": 110})
        self.assertEqual((s.right, s.left), (round5(50), round5(90)))

    def test_thresholds_are_multiples_of_5(self):
        s = chip.suggest_sides({"right": 31, "front": 77, "behind": 83, "left": 104})
        self.assertEqual((s.right % 5, s.left % 5), (0, 0))
        self.assertLess(31, s.right)
        self.assertLess(s.right, 77)
        self.assertLess(83, s.left)
        self.assertLessEqual(s.left, 104)

    def test_bad_ordering_or_small_gaps_refuse(self):
        for medians in ({"right": 35, "front": 80, "behind": 90, "left": 95},   # left gap 5
                        {"right": 75, "front": 80, "behind": 90, "left": 100},  # right gap 5
                        {"right": 100, "front": 80, "behind": 90, "left": 35},  # sides swapped
                        {"right": 35, "front": 110, "behind": 90, "left": 100},  # front past left
                        {"right": 35, "left": 100},                             # no front or behind
                        {"front": 80, "behind": 90, "left": 100}):              # no right
            with self.assertRaises(chip.CalibrationError, msg=medians) as ctx:
                chip.suggest_sides(medians)
            self.assertTrue(str(ctx.exception))

    def test_summary_is_median_and_range(self):
        self.assertEqual(chip.summarize([30, 45, 35]), (35, 30, 45))
        self.assertEqual(chip.summarize([80, 81]), (80.5, 80, 81))
        self.assertIsNone(chip.summarize([]))

    def test_parse_readings_takes_one_value_or_a_list(self):
        self.assertEqual(chip.parse_readings(["front=80", "left=95,100,110"]),
                         {"front": [80], "left": [95, 100, 110]})
        for bad in (["up=10"], ["front=300"], ["front"], ["front=x"], ["front=1,,2"], ["front="]):
            with self.assertRaises(ValueError, msg=bad):
                chip.parse_readings(bad)


class CalibrateRunTest(unittest.TestCase):
    def robot(self, raws=TODAY, **kw):
        streams = [stream_of(raws[pos]) for pos in chip.POSITIONS]
        kw.setdefault("props", dict(PORT_SET))
        return FakeRobot(streams=streams, **kw)

    def run_cal(self, robot, **kw):
        prompts = []
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = chip.calibrate(robot, ask=prompts.append, **kw)
        return code, prompts, out.getvalue()

    def test_walks_the_four_positions_capturing_the_stream_and_only_suggests(self):
        robot = self.robot()
        code, prompts, out = self.run_cal(robot)
        self.assertEqual(code, 0)
        self.assertEqual(len(prompts), 4)
        for pos, prompt in zip(chip.POSITIONS, prompts):
            self.assertIn(pos.upper(), prompt)
            self.assertIn("count", prompt.lower())
        self.assertEqual(len(robot.captures()), 4)
        self.assertEqual(robot.captures()[0], ("shell", chip.capture_command(chip.CAPTURE_SECONDS)))
        self.assertEqual(chip.CAPTURE_SECONDS, 11)
        # The pre-check runs once, before any capture.
        stty = [i for i, c in enumerate(robot.calls) if c == ("shell", chip.TERMIOS_COMMAND)]
        self.assertEqual(len(stty), 1)
        self.assertLess(stty[0], robot.calls.index(robot.captures()[0]))
        for needle in ("right: median 35 (30..45", "left: median 100 (95..110",
                       f"{chip.RIGHT_PROPERTY}=60", f"{chip.LEFT_PROPERTY}=95", "--apply"):
            self.assertIn(needle, out)
        self.assertEqual(robot.setprops(), [])

    def test_apply_sets_only_the_side_thresholds_and_unsets_the_calibration(self):
        robot = self.robot(props=dict(PORT_SET, **{chip.ZERO_PROPERTY: "10", chip.SIGN_PROPERTY: "1",
                                                   chip.SCALE_PROPERTY: "1.4"}))
        code, _, _ = self.run_cal(robot, apply=True)
        self.assertEqual(code, 0)
        self.assertEqual(dict(robot.setprops()),
                         {chip.LEFT_PROPERTY: "95", chip.RIGHT_PROPERTY: "60", chip.ZERO_PROPERTY: '""',
                          chip.SIGN_PROPERTY: '""', chip.SCALE_PROPERTY: '""'})
        self.assertEqual(len(robot.setprops()), 5)
        for key in (chip.ZERO_PROPERTY, chip.SIGN_PROPERTY, chip.SCALE_PROPERTY):
            self.assertEqual(robot.props[key], "")
        self.assertIn(("shell", "am", "force-stop", chip.LAUNCHER_PACKAGE), robot.calls)

    def test_a_port_not_left_raw_refuses_before_any_capture(self):
        for termios in (COOKED_TERMIOS, ECHO_TERMIOS, ""):
            robot = self.robot(termios=termios)
            code, prompts, out = self.run_cal(robot, apply=True)
            self.assertNotEqual(code, 0, termios)
            self.assertEqual(robot.captures(), [], termios)
            self.assertEqual(robot.setprops(), [], termios)
            self.assertEqual(prompts, [], termios)
            self.assertIn("refus", out.lower())

    def test_refuses_without_a_confirmed_port(self):
        robot = self.robot(props={})
        code, _, _ = self.run_cal(robot, apply=True)
        self.assertNotEqual(code, 0)
        self.assertEqual(robot.captures(), [])
        self.assertEqual(robot.setprops(), [])

    def test_bad_ordering_refuses_and_writes_nothing_even_with_apply(self):
        raws = dict(TODAY, left=[80, 85, 90])
        robot = self.robot(raws=raws)
        code, _, out = self.run_cal(robot, apply=True)
        self.assertNotEqual(code, 0)
        self.assertEqual(robot.setprops(), [])
        self.assertIn("nothing was written", out.lower())

    def test_a_position_with_no_frames_refuses(self):
        raws = dict(TODAY, right=[])
        robot = self.robot(raws=raws)
        code, _, out = self.run_cal(robot, apply=True)
        self.assertNotEqual(code, 0)
        self.assertEqual(robot.setprops(), [])
        self.assertIn("right", out)

    def test_given_readings_skip_the_capture(self):
        robot = FakeRobot(props=dict(PORT_SET))
        code, prompts, out = self.run_cal(robot, readings={"front": [80], "left": [95, 100, 110],
                                                            "right": [35], "behind": [90]}, apply=True)
        self.assertEqual(code, 0)
        self.assertEqual(prompts, [])
        self.assertEqual(robot.captures(), [])
        self.assertNotIn(("shell", chip.TERMIOS_COMMAND), robot.calls)
        self.assertEqual(robot.props[chip.LEFT_PROPERTY], "95")
        self.assertEqual(robot.props[chip.RIGHT_PROPERTY], "60")

    def test_given_readings_without_apply_need_no_robot_writes(self):
        robot = FakeRobot()
        code, _, _ = self.run_cal(robot, readings={"front": [80], "left": [100], "right": [35], "behind": [90]})
        self.assertEqual(code, 0)
        self.assertEqual(robot.setprops(), [])

    def test_no_calibration_run_writes_to_or_opens_the_node_beyond_the_read_only_forms(self):
        for kw in ({}, {"apply": True}):
            robot = self.robot()
            self.run_cal(robot, **kw)
            self.assertEqual(node_writes(robot.calls), [])
            self.assertEqual(node_opens(robot.calls), [])
            for key, _ in robot.setprops():
                self.assertTrue(key.startswith("persist.miko3.voice_dir."), key)


class WatchTest(unittest.TestCase):
    def run_watch(self, robot, **kw):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = chip.watch(robot, **kw)
        return code, out.getvalue()

    def test_prints_one_line_per_frame_with_its_side(self):
        robot = FakeRobot(props={chip.LEFT_PROPERTY: "95", chip.RIGHT_PROPERTY: "60"},
                          streams=[stream_of([50, 85, 100])])
        code, out = self.run_watch(robot)
        self.assertEqual(code, 0)
        self.assertEqual(robot.captures(), [("shell", chip.capture_command(chip.WATCH_SECONDS))])
        self.assertEqual(chip.WATCH_SECONDS, 10)
        lines = [l for l in out.splitlines() if re.match(r"\s*seq\s+\d+", l)]
        self.assertEqual(len(lines), 3, out)
        self.assertRegex(lines[0], r"seq\s+0\s+raw\s+50\s+right")
        self.assertRegex(lines[1], r"seq\s+1\s+raw\s+85\s+ahead/behind")
        self.assertRegex(lines[2], r"seq\s+2\s+raw\s+100\s+left")
        self.assertEqual(robot.setprops(), [])
        self.assertEqual(node_writes(robot.calls), [])
        self.assertEqual(node_opens(robot.calls), [])

    def test_without_thresholds_the_side_is_unknown(self):
        robot = FakeRobot(streams=[stream_of([50])])
        code, out = self.run_watch(robot, seconds=5)
        self.assertEqual(code, 0)
        self.assertIn(("shell", chip.capture_command(5)), robot.calls)
        self.assertRegex(out, r"seq\s+0\s+raw\s+50\s+\(no thresholds\)")

    def test_side_of_follows_the_launchers_rule(self):
        sides = chip.Sides(left=95, right=60)
        self.assertEqual(chip.side_of(95, sides), "left")
        self.assertEqual(chip.side_of(60, sides), "right")
        self.assertEqual(chip.side_of(61, sides), "ahead/behind")
        self.assertEqual(chip.side_of(94, sides), "ahead/behind")

    def test_a_port_not_left_raw_refuses(self):
        robot = FakeRobot(termios=COOKED_TERMIOS, streams=[stream_of([50])])
        code, out = self.run_watch(robot)
        self.assertNotEqual(code, 0)
        self.assertEqual(robot.captures(), [])

    def test_no_frames_is_a_failure(self):
        robot = FakeRobot(streams=[b"\x00\x01junk"])
        code, out = self.run_watch(robot)
        self.assertNotEqual(code, 0)
        self.assertIn("no frames", out.lower())


class MainTest(unittest.TestCase):
    def test_parser(self):
        args = chip.build_parser().parse_args([])
        self.assertFalse(args.owner_confirms)
        self.assertFalse(args.calibrate)
        self.assertFalse(args.apply)
        self.assertFalse(args.watch)
        self.assertIsNone(args.readings)
        args = chip.build_parser().parse_args(["--calibrate", "--apply", "--readings", "front=80", "left=95,100"])
        self.assertTrue(args.calibrate and args.apply)
        self.assertEqual(args.readings, ["front=80", "left=95,100"])
        self.assertTrue(chip.build_parser().parse_args(["--owner-confirms"]).owner_confirms)
        args = chip.build_parser().parse_args(["--watch", "--seconds", "20"])
        self.assertTrue(args.watch)
        self.assertEqual(args.seconds, 20)

    def test_bad_combinations_are_refused(self):
        for argv in (["--readings", "front=1"], ["--apply"], ["--watch", "--calibrate"],
                     ["--watch", "--seconds", "0"], ["--watch", "--seconds", "26"]):
            with self.assertRaises(SystemExit, msg=argv):
                with contextlib.redirect_stderr(io.StringIO()):
                    chip.main(argv)

    def test_property_names(self):
        self.assertEqual(chip.PORT_PROPERTY, "persist.miko3.voice_dir.port")
        self.assertEqual(chip.ZERO_PROPERTY, "persist.miko3.voice_dir.zero")
        self.assertEqual(chip.SIGN_PROPERTY, "persist.miko3.voice_dir.sign")
        self.assertEqual(chip.SCALE_PROPERTY, "persist.miko3.voice_dir.scale")
        self.assertEqual(chip.LEFT_PROPERTY, "persist.miko3.voice_dir.left")
        self.assertEqual(chip.RIGHT_PROPERTY, "persist.miko3.voice_dir.right")
        self.assertEqual(chip.NODE, "/dev/ttyS1")


if __name__ == "__main__":
    unittest.main()
