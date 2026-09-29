"""Tests for scripts/qa-direction-chip.py (hey-miko plan U1; R8, KTD10, KTD12,
KTD13): the read-only evidence it gathers about /dev/ttyS1, the fixed rule that
decides confirmation, the property it writes only after confirmation and unsets
when the launcher's probe does not show the NC chip, and the calibration fit.

Every adb call goes through a fake robot that records it, so the tests can
check that no run ever writes to the node and that nothing is set while the
port is unconfirmed. Nothing here needs the robot."""
import contextlib
import importlib.util
import io
import json
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

# The DOA query from the plan: XXUB, module 03, op 01, param 03, three zeros, CRC32 LE.
DOA_QUERY = bytes.fromhex("58585542030103000000a620d0e7")

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


def od(data):
    """What `od -An -tx1 -v` prints for these bytes (16 per line)."""
    return "".join(" " + " ".join(f"{b:02x}" for b in data[i:i + 16]) + "\n" for i in range(0, len(data), 16))


def make_frame(module, op, param):
    head = b"XXUB" + bytes([module, op, param, 0, 0, 0])
    return head + zlib.crc32(head).to_bytes(4, "little")


class FakeRobot(ears.Robot):
    """Answers each evidence command from a canned output, keeps a property table
    for setprop and getprop, and records every adb call in order."""

    def __init__(self, outputs=None, props=None, setprop_sticks=True, timeouts=()):
        super().__init__("fake:5555")
        self.timeouts = set(timeouts)
        self.calls = []
        self.outputs = dict(outputs or {})
        self.props = dict(props or {})
        self.setprop_sticks = setprop_sticks

    def adb(self, *args, check=True):
        self.calls.append(args)
        if args[:2] == ("shell", "setprop"):
            if self.setprop_sticks:
                value = args[3]
                self.props[args[2]] = "" if value == '""' else value
            return ""
        if args[:2] == ("shell", "getprop"):
            return self.props.get(args[2], "") + "\n"
        if args[:1] == ("shell",) and len(args) == 2:
            for name, script in chip.EVIDENCE_COMMANDS.items():
                if args[1] == script:
                    if name in self.timeouts:
                        raise ears.ProbeError(f"!! adb timed out: adb shell {script}")
                    return self.outputs.get(name, "")
        return ""

    @contextlib.contextmanager
    def forward(self, port):
        self.calls.append(("forward", port))
        yield f"https://127.0.0.1:{10000 + port}"
        self.calls.append(("forward-removed", port))

    def setprops(self):
        return [(c[2], c[3]) for c in self.calls if c[:2] == ("shell", "setprop")]


def evidence_outputs(**over):
    out = {"holder": "", "driver": DRIVER, "dmesg": DMESG, "dtree": DTREE, "vendor_log": ""}
    out.update(over)
    return out


def nc_answer(backend="NC", reply="58585542030103000000a620d0e70100", raws=(138,)):
    rows = [{"second": i + 1, "angle": None, "raw": r, "rms": 900, "decode_ms": 200, "decode_max_ms": 20,
             "chunks": 13, "words": 0, "matched": False} for i, r in enumerate(raws)]
    return ears.Answer(backend, len(rows), rows, reply)


def quiet(fn, *args, **kw):
    with contextlib.redirect_stdout(io.StringIO()):
        return fn(*args, **kw)


# Writes aimed at the node: redirections, dd of=, tee, and a read-write open.
NODE_WRITE = re.compile(r"(>>?\s*|of=|tee\s+(-a\s+)?)/dev/ttyS1\b|<>\s*/dev/ttyS1\b")
# Any form that opens the node: a redirect from it, a program reading it, stty on it.
NODE_OPEN = re.compile(r"<\s*/dev/ttyS1|\b(?:cat|od|dd|hexdump|stty|timeout)\b[^;|]*/dev/ttyS1")


def node_writes(calls):
    return [c for c in calls if any(NODE_WRITE.search(str(a)) for a in c)]


class FrameTest(unittest.TestCase):
    def test_the_plans_doa_query_is_what_we_build(self):
        self.assertEqual(make_frame(3, 1, 3), DOA_QUERY)
        self.assertEqual(chip.request_frame(3, 1, 3), DOA_QUERY)

    def test_parse_od_reads_hex_bytes_and_ignores_noise(self):
        self.assertEqual(chip.parse_od(od(DOA_QUERY)), DOA_QUERY)
        self.assertEqual(chip.parse_od(""), b"")
        self.assertEqual(chip.parse_od("timeout: sending signal TERM\n 01 02\n"), b"\x01\x02")

    def test_a_frame_with_a_valid_crc_is_found_amid_noise(self):
        found = chip.find_frames(b"\x00\xff\x13" + DOA_QUERY + b"\x7f")
        self.assertEqual([(f.offset, f.length) for f in found], [(3, 14)])

    def test_a_frame_with_a_wrong_crc_is_ignored(self):
        bad = DOA_QUERY[:-1] + bytes([DOA_QUERY[-1] ^ 0x01])
        self.assertEqual(chip.find_frames(bad), [])
        self.assertEqual(chip.find_frames(b"XXUB" + b"\x00" * 6), [])

    def test_a_longer_reply_with_its_crc_last_is_found(self):
        """Where a reply's CRC sits is unverified (KTD11), so a trailing CRC also counts."""
        body = b"XXUB" + bytes(range(1, 30))
        reply = body + zlib.crc32(body).to_bytes(4, "little")
        found = chip.find_frames(reply)
        self.assertEqual([(f.offset, f.length) for f in found], [(0, len(reply))])


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
    def run_identify(self, robot, owner_confirms=False, probe_answer=None, probe_raises=None):
        probes = []

        def probe(r):
            probes.append(r)
            if probe_raises is not None:
                raise probe_raises
            return probe_answer

        code = quiet(chip.identify, robot, owner_confirms=owner_confirms, probe=probe, sleep=lambda s: None)
        return code, probes

    def test_unconfirmed_never_issues_a_setprop(self):
        robot = FakeRobot(evidence_outputs())
        code, probes = self.run_identify(robot, probe_answer=nc_answer())
        self.assertNotEqual(code, 0)
        self.assertEqual(robot.setprops(), [])
        self.assertFalse(any("setprop" in " ".join(map(str, c)) for c in robot.calls), robot.calls)
        self.assertEqual(probes, [])

    def test_confirmed_sets_the_port_and_keeps_it_when_the_probe_shows_nc(self):
        robot = FakeRobot(evidence_outputs())
        code, probes = self.run_identify(robot, owner_confirms=True, probe_answer=nc_answer())
        self.assertEqual(code, 0)
        self.assertEqual(len(probes), 1)
        self.assertEqual(robot.setprops(), [(chip.PORT_PROPERTY, "/dev/ttyS1")])
        self.assertEqual(robot.props[chip.PORT_PROPERTY], "/dev/ttyS1")
        # The launcher restarts after the property is set, so it opens the chip afresh.
        set_at = robot.calls.index(("shell", "setprop", chip.PORT_PROPERTY, "/dev/ttyS1"))
        self.assertIn(("shell", "am", "force-stop", chip.LAUNCHER_PACKAGE), robot.calls[set_at:])

    def test_a_probe_reporting_backend_none_unsets_the_port(self):
        robot = FakeRobot(evidence_outputs())
        code, _ = self.run_identify(robot, owner_confirms=True, probe_answer=nc_answer(backend="NONE", reply=None))
        self.assertNotEqual(code, 0)
        self.assertEqual(robot.setprops(), [(chip.PORT_PROPERTY, "/dev/ttyS1"), (chip.PORT_PROPERTY, '""')])
        self.assertEqual(robot.props[chip.PORT_PROPERTY], "")

    def test_a_probe_without_an_xxub_reply_unsets_the_port(self):
        for reply in (None, "", "00112233", ["deadbeef"]):
            robot = FakeRobot(evidence_outputs())
            code, _ = self.run_identify(robot, owner_confirms=True, probe_answer=nc_answer(reply=reply))
            self.assertNotEqual(code, 0, reply)
            self.assertEqual(robot.props[chip.PORT_PROPERTY], "", reply)

    def test_a_failed_probe_unsets_the_port(self):
        robot = FakeRobot(evidence_outputs())
        code, _ = self.run_identify(robot, owner_confirms=True, probe_raises=ears.ProbeError("!! 404"))
        self.assertNotEqual(code, 0)
        self.assertEqual(robot.props[chip.PORT_PROPERTY], "")

    def test_an_interrupted_probe_still_unsets_the_port(self):
        robot = FakeRobot(evidence_outputs())
        with self.assertRaises(KeyboardInterrupt):
            self.run_identify(robot, owner_confirms=True, probe_raises=KeyboardInterrupt())
        self.assertEqual(robot.props[chip.PORT_PROPERTY], "")

    def test_a_setprop_that_does_not_stick_is_an_error_and_skips_the_probe(self):
        robot = FakeRobot(evidence_outputs(), setprop_sticks=False)
        with self.assertRaises(ears.ProbeError) as ctx:
            self.run_identify(robot, owner_confirms=True, probe_answer=nc_answer())
        self.assertIn("root", str(ctx.exception))

    def test_a_vendor_log_confirmation_needs_no_owner_flag(self):
        robot = FakeRobot(evidence_outputs(vendor_log=NC_LOG))
        code, probes = self.run_identify(robot, probe_answer=nc_answer())
        self.assertEqual(code, 0)
        self.assertEqual(len(probes), 1)

    def test_no_run_ever_writes_to_the_node_and_only_voice_dir_props_are_set(self):
        runs = [
            dict(robot=FakeRobot(evidence_outputs()), owner_confirms=False, probe_answer=nc_answer()),
            dict(robot=FakeRobot(evidence_outputs(vendor_log=NC_LOG)), probe_answer=nc_answer()),
            dict(robot=FakeRobot(evidence_outputs()), owner_confirms=True,
                 probe_answer=nc_answer(backend="NONE", reply=None)),
            dict(robot=FakeRobot(evidence_outputs()), owner_confirms=True, probe_answer=nc_answer()),
        ]
        for spec in runs:
            robot = spec.pop("robot")
            self.run_identify(robot, **spec)
            self.assertEqual(node_writes(robot.calls), [], robot.calls)
            for key, _ in robot.setprops():
                self.assertTrue(key.startswith("persist.miko3.voice_dir."), key)

    def test_the_node_write_detector_catches_the_usual_forms(self):
        for cmd in ("echo -n x > /dev/ttyS1", "dd if=/tmp/f of=/dev/ttyS1", "cat f >> /dev/ttyS1",
                    "printf x | tee /dev/ttyS1", "exec 3<> /dev/ttyS1"):
            self.assertTrue(node_writes([("shell", cmd)]), cmd)
        self.assertFalse(node_writes([("shell", chip.EVIDENCE_COMMANDS["holder"])]))


class ProbeExpectationTest(unittest.TestCase):
    """The one place that reads U2's probe answer for the chip (probe_shows_nc)."""

    def test_nc_backend_with_an_xxub_reply_passes(self):
        ok, why = chip.probe_shows_nc(nc_answer())
        self.assertTrue(ok, why)

    def test_reply_hex_may_be_spaced_upper_case_or_a_list(self):
        self.assertTrue(chip.probe_shows_nc(nc_answer(reply="58 58 55 42 03 01"))[0])
        self.assertTrue(chip.probe_shows_nc(nc_answer(reply="58585542AABB"))[0])
        self.assertTrue(chip.probe_shows_nc(nc_answer(reply=["00", "58585542aa"]))[0])

    def test_other_backends_or_replies_fail_with_a_reason(self):
        for answer, needle in ((nc_answer(backend="conexant"), "backend"),
                               (nc_answer(backend="NONE"), "backend"),
                               (nc_answer(reply=None), "reply"),
                               (nc_answer(reply="zz58585542"), "reply")):
            ok, why = chip.probe_shows_nc(answer)
            self.assertFalse(ok)
            self.assertIn(needle, why)

    def test_the_real_probe_route_output_reaches_the_expectation(self):
        """Through qa-ears-probe's run and parse_answer, as the script does on the robot."""
        dump = json.dumps({"backend": "NC", "raw_reply": "58585542030103000000a620d0e70100", "seconds": 3,
                           "rows": nc_answer().rows})
        page = b'<form><input type="hidden" name="t" value="feedfacefeedfacefeedfacefeedface"></form>'

        def http(method, url, body=None, headers=None, timeout=None):
            return ears.Response(200, {}, page if method == "GET" else dump.encode())

        answer = quiet(chip.run_probe, FakeRobot(), http=http)
        self.assertTrue(chip.probe_shows_nc(answer)[0])


class CalibrationFitTest(unittest.TestCase):
    def test_the_plans_readings_fit_zero_10_sign_plus_scale_360_over_256(self):
        fit = chip.fit_calibration({"front": 10, "right": 74, "behind": 138, "left": 202})
        self.assertEqual(fit.zero, 10)
        self.assertEqual(fit.sign, 1)
        self.assertAlmostEqual(fit.scale, 360 / 256, places=6)
        self.assertLess(fit.max_error_deg, 1e-6)

    def test_swapped_sides_give_sign_minus_one(self):
        fit = chip.fit_calibration({"front": 10, "right": 202, "behind": 138, "left": 74})
        self.assertEqual(fit.sign, -1)
        self.assertEqual(fit.zero, 10)
        self.assertAlmostEqual(fit.scale, 360 / 256, places=6)

    def test_readings_across_the_wrap_still_fit(self):
        fit = chip.fit_calibration({"front": 250, "right": 58, "behind": 122, "left": 186})
        self.assertEqual((fit.zero, fit.sign), (250, 1))
        self.assertAlmostEqual(fit.scale, 360 / 256, places=6)

    def test_three_positions_are_enough(self):
        fit = chip.fit_calibration({"front": 10, "right": 74, "left": 202})
        self.assertEqual((fit.zero, fit.sign), (10, 1))
        self.assertAlmostEqual(fit.scale, 360 / 256, places=6)

    def test_fewer_than_three_distinct_readings_refuse(self):
        for readings in ({"front": 10, "right": 74}, {"front": 10, "right": 74, "behind": 74, "left": 10},
                         {"front": 10, "right": 10, "behind": 10}):
            with self.assertRaises(chip.CalibrationError, msg=readings):
                chip.fit_calibration(readings)

    def test_no_front_reading_refuses(self):
        with self.assertRaises(chip.CalibrationError):
            chip.fit_calibration({"right": 74, "behind": 138, "left": 202})

    def test_readings_that_fit_no_rotation_refuse(self):
        with self.assertRaises(chip.CalibrationError) as ctx:
            chip.fit_calibration({"front": 10, "right": 40, "behind": 200, "left": 60})
        self.assertIn("error", str(ctx.exception))

    def test_position_reading_is_the_circular_medoid_of_raws(self):
        self.assertEqual(chip.position_reading([250, 252, 3, 251, 251]), 251)
        self.assertEqual(chip.position_reading([74, 70, 78]), 74)
        self.assertIsNone(chip.position_reading([]))

    def test_property_values(self):
        fit = chip.fit_calibration({"front": 10, "right": 74, "behind": 138, "left": 202})
        self.assertEqual(chip.calibration_props(fit),
                         [(chip.ZERO_PROPERTY, "10"), (chip.SIGN_PROPERTY, "1"), (chip.SCALE_PROPERTY, "1.40625")])

    def test_parse_readings_option(self):
        self.assertEqual(chip.parse_readings(["front=10", "left=202"]), {"front": 10, "left": 202})
        for bad in (["up=10"], ["front=300"], ["front"], ["front=x"]):
            with self.assertRaises(ValueError, msg=bad):
                chip.parse_readings(bad)


class CalibrateRunTest(unittest.TestCase):
    RAWS = {"front": [10, 11, 9], "left": [202, 200, 204], "right": [74, 75, None], "behind": [138]}

    def probe_for(self, raws_by_pos, order, backend="NC"):
        def probe(robot, phrase=None, seconds=None):
            return nc_answer(backend=backend, raws=raws_by_pos[order.pop(0)])
        return probe

    def test_walks_the_four_positions_and_writes_the_fit(self):
        robot = FakeRobot(props={chip.PORT_PROPERTY: "/dev/ttyS1"})
        prompts = []
        order = [p for p, _ in chip.POSITIONS]
        code = quiet(chip.calibrate, robot, probe=self.probe_for(self.RAWS, list(order)),
                     ask=prompts.append)
        self.assertEqual(code, 0)
        self.assertEqual(len(prompts), 4)
        for (pos, _), prompt in zip(chip.POSITIONS, prompts):
            self.assertIn(pos.upper(), prompt)
        self.assertEqual(robot.props[chip.ZERO_PROPERTY], "10")
        self.assertEqual(robot.props[chip.SIGN_PROPERTY], "1")
        self.assertEqual(robot.props[chip.SCALE_PROPERTY], "1.40625")
        self.assertEqual(node_writes(robot.calls), [])

    def test_refuses_without_a_confirmed_port(self):
        robot = FakeRobot()
        code = quiet(chip.calibrate, robot, probe=lambda *a, **k: nc_answer(), ask=lambda t: None)
        self.assertNotEqual(code, 0)
        self.assertEqual(robot.setprops(), [])

    def test_too_few_distinct_readings_writes_nothing(self):
        raws = {"front": [10], "left": [], "right": [None], "behind": [10]}
        robot = FakeRobot(props={chip.PORT_PROPERTY: "/dev/ttyS1"})
        order = [p for p, _ in chip.POSITIONS]
        code = quiet(chip.calibrate, robot, probe=self.probe_for(raws, list(order)), ask=lambda t: None)
        self.assertNotEqual(code, 0)
        self.assertEqual(robot.setprops(), [])

    def test_a_probe_that_is_not_the_chip_writes_nothing(self):
        robot = FakeRobot(props={chip.PORT_PROPERTY: "/dev/ttyS1"})
        order = [p for p, _ in chip.POSITIONS]
        code = quiet(chip.calibrate, robot, probe=self.probe_for(self.RAWS, list(order), backend="NONE"),
                     ask=lambda t: None)
        self.assertNotEqual(code, 0)
        self.assertEqual(robot.setprops(), [])

    def test_given_readings_skip_the_probe(self):
        robot = FakeRobot(props={chip.PORT_PROPERTY: "/dev/ttyS1"})
        code = quiet(chip.calibrate, robot, readings={"front": 10, "right": 202, "behind": 138, "left": 74},
                     probe=lambda *a, **k: self.fail("probed"), ask=lambda t: self.fail("asked"))
        self.assertEqual(code, 0)
        self.assertEqual(robot.props[chip.SIGN_PROPERTY], "-1")


class MainTest(unittest.TestCase):
    def test_parser(self):
        args = chip.build_parser().parse_args([])
        self.assertFalse(args.owner_confirms)
        self.assertFalse(args.calibrate)
        self.assertIsNone(args.readings)
        args = chip.build_parser().parse_args(["--calibrate", "--readings", "front=10", "left=202"])
        self.assertTrue(args.calibrate)
        self.assertEqual(args.readings, ["front=10", "left=202"])
        self.assertTrue(chip.build_parser().parse_args(["--owner-confirms"]).owner_confirms)

    def test_property_names(self):
        self.assertEqual(chip.PORT_PROPERTY, "persist.miko3.voice_dir.port")
        self.assertEqual(chip.ZERO_PROPERTY, "persist.miko3.voice_dir.zero")
        self.assertEqual(chip.SIGN_PROPERTY, "persist.miko3.voice_dir.sign")
        self.assertEqual(chip.SCALE_PROPERTY, "persist.miko3.voice_dir.scale")
        self.assertEqual(chip.NODE, "/dev/ttyS1")


if __name__ == "__main__":
    unittest.main()
