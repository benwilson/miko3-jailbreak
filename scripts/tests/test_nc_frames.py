"""Host-side tests for the NC direction chip (explore plan U2; R7, R8, KTD10
to KTD12).

The chip streams one 19-byte direction frame a second (robot, 2026-09-29), but
only once reporting is on, and after a cold boot it is off (robot, 2026-09-30):
open then sends the vendor's status query once and, if the answer is off, the
reporting toggle once. NcFrames (the stream parse, the calibration from raw 0 to 255 to
signed degrees) and VoiceDirection's NC backend run under a JVM harness with a
fake port setup and a fake port node that streams frames by time, so the gating
(the confirmed-port property, the node, stty's 0 = success), the first-frame
wait at open, the freshness window, the skipping of junk, torn and bad-CRC
frames, the close on a read error, the query/toggle sequence at open, and that
sampling never writes are all proven without the robot. The stty -g input-flag rewrite (toybox 0.7.6
cannot clear icrnl/ixon/ixoff/inpck by flag) is checked as pure functions.
"""
import subprocess
import sys
import tempfile
import unittest
import zlib
from pathlib import Path

TESTS = Path(__file__).resolve().parent
if str(TESTS) not in sys.path:
    sys.path.insert(0, str(TESTS))
import jvm_harness  # noqa: E402

REPO = Path(__file__).resolve().parents[2]
SHARED_SRC = REPO / "shared" / "src"
SHARED = SHARED_SRC / "com" / "miko3" / "shared"
HARNESS = TESTS / "fixtures" / "nc_frames_harness" / "src"
HARNESS_MAIN = HARNESS / "com" / "miko3" / "shared" / "NcFramesHarness.java"
FRAMES = SHARED / "NcFrames.java"


class NcFramesHarnessTest(unittest.TestCase):
    SCENARIOS = (
        "robot_frames_parse_to_85_and_50_and_the_03_02_frame_is_ignored",
        "junk_a_torn_frame_and_a_bad_crc_are_skipped",
        "the_newest_frame_wins",
        "a_stale_reading_is_nan_and_a_fresh_one_is_degrees",
        "no_frame_within_first_frame_ms_is_none",
        "a_frame_at_200_ms_opens_nc_with_it_as_the_first_reply",
        "after_an_nc_open_twenty_samples_add_no_writes",
        "a_read_error_closes_the_backend_and_logs_once",
        "stty_input_flags_are_zeroed_from_the_g_string",
        "stty_setup_sets_115200_8n1_without_echo_or_the_raw_keyword",
        "calibration_maps_raw_to_signed_degrees",
        "calibration_wraps_to_plus_minus_180",
        "uncalibrated_is_nan",
        "unset_port_is_none_with_no_native_call",
        "missing_node_is_none_with_no_native_call",
        "a_failed_stty_is_none",
        "confirmed_port_opens_nc_and_reports_calibrated_degrees",
        "uncalibrated_nc_gives_nan_but_keeps_the_raw_value",
        "first_frames_are_logged_in_hex",
        "lazy_sampler_opens_on_its_own_thread_and_never_blocks_the_caller",
        "config_reads_port_and_calibration",
        # Side mode: the chip tells only left from right (robot, 2026-09-29).
        "side_thresholds_map_raw_to_left_right_or_neither",
        "config_of_six_arguments_parses_both_thresholds",
        "calibration_wins_over_side_thresholds",
        "bad_side_thresholds_give_no_side_mode",
        "side_mode_nc_gives_plus_minus_90_and_logs_its_raw_values",
        "side_only_is_false_without_an_nc_backend",
        # Reporting is off after a cold boot (robot, 2026-09-30): the vendor's query, then its toggle.
        "the_robot_status_and_direction_frames_parse",
        "the_request_frames_are_the_vendors_and_never_shared",
        "reporting_already_on_opens_nc_with_no_writes",
        "off_after_boot_queries_then_toggles_once_and_opens_nc",
        "status_on_but_silent_is_none_without_a_toggle",
        "no_status_reply_is_none_after_one_write",
        "a_toggle_with_no_frames_after_is_none_after_one_toggle",
        "after_an_off_boot_open_twenty_samples_add_no_writes",
        # The DSP's settings (vendor libconexant_dsp_lib.so, disassembled 2026-10-02).
        "nc_replies_parse_between_direction_frames",
        "nc_request_frames_are_built_with_their_crcs",
        "nc_plan_sends_only_what_differs",
        "nc_plan_never_toggles_unknown_values",
        "nc_plan_never_sends_factory_or_left_aec",
        "nc_control_parses_properties",
        "nc_status_logs_every_setting_and_writes_only_reads",
        "nc_unanswered_settings_log_question_marks_and_are_not_applied",
        "nc_apply_sets_and_toggles_only_differences_then_rereads",
        "nc_apply_with_custom_gains_and_nothing_else_to_change",
    )

    @classmethod
    def setUpClass(cls):
        jdk = jvm_harness.find_jdk()
        if jdk is None:
            raise unittest.SkipTest("no JDK (javac + java) found")
        cls._td = tempfile.TemporaryDirectory(prefix="nc_frames_harness_")
        out = cls._td.name
        # javac pulls in NcFrames, VoiceDirection and the JNI stubs it names; the
        # stubs' libraries never load, since the harness injects a fake native layer.
        c = subprocess.run(jvm_harness.javac_cmd(jdk[0], out, [HARNESS_MAIN], [HARNESS, SHARED_SRC]),
                           capture_output=True, text=True)
        cls.compiled = c.returncode == 0
        cls.compile_output = (c.stdout + c.stderr)[-3000:]
        cls.results, cls.run_output, cls.frames = {}, "", {}
        if cls.compiled:
            r = subprocess.run([jdk[1], "-cp", out, "com.miko3.shared.NcFramesHarness"],
                               capture_output=True, text=True, timeout=60)
            cls.run_output = (r.stdout + r.stderr)[-6000:]
            cls.results = jvm_harness.parse_verdicts(r.stdout)
            f = subprocess.run([jdk[1], "-cp", out, "com.miko3.shared.NcFramesHarness", "frames"],
                               capture_output=True, text=True, timeout=60)
            cls.frames = {}
            for line in f.stdout.splitlines():
                tag, _, rest = line.partition(" ")
                if tag == "FRAME":
                    name, _, hx = rest.partition(" ")
                    cls.frames[name] = hx

    @classmethod
    def tearDownClass(cls):
        cls._td.cleanup()

    def setUp(self):
        self.assertTrue(self.compiled, f"harness failed to compile:\n{self.compile_output}")

    def _assert_pass(self, name):
        self.assertIn(name, self.results, f"scenario {name} never reported:\n{self.run_output}")
        verdict, detail = self.results[name]
        self.assertEqual(verdict, "PASS", f"{name}: {detail}")

    def test_every_scenario_reported(self):
        self.assertEqual(sorted(self.results), sorted(self.SCENARIOS), self.run_output)


    def test_built_frames_are_the_vendors_byte_for_byte(self):
        want = {name: hx for name, hx in VENDOR_FRAMES.items()}
        for v in (0, 30):
            want[f"gain.set{v}"] = _set_frame(0x04, v)
            want[f"dgain.set{v}"] = _set_frame(0x12, v)
        self.assertEqual(self.frames, want)


jvm_harness.add_scenario_tests(NcFramesHarnessTest)

# The vendor's requests (libconexant_dsp_lib.so, disassembled 2026-10-02), as transcribed.
# Left AEC's toggle (malformed in the vendor) and factory mode (module 09) are absent on purpose.
P = "58585542"
VENDOR_FRAMES = {
    "gain.read": P + "04010c0000004800b6a2",
    "dgain.read": P + "12010c000000cee13977",
    "aec.read": P + "06011500000056b96fc2",
    "aec.toggle": P + "060313000000eab5c49d",
    "laec.read": P + "16010100000005 7bc21e".replace(" ", ""),
    "ns.read": P + "080101000000ee181e27",
    "ns.toggle": P + "0803010000008e4bde5d",
    "lns.read": P + "18010500000022 8daaab".replace(" ", ""),
    "lns.toggle": P + "18030400000027b9d669",
    "ch.read": P + "110101000000bd4bc703",
    "ch.toggle": P + "110301000000dd180779",
    "voip.read": P + "05010500000067 9ce214".replace(" ", ""),
    "voip.toggle": P + "05030400000062a89ed6",
}
VENDOR_SET_HEADERS = {0x04: P + "0403010105008715a956", 0x12: P + "12030101050001f42683"}


def _crc_le(b):
    return zlib.crc32(b).to_bytes(4, "little")


def _set_frame(module, value):
    head = bytes.fromhex(VENDOR_SET_HEADERS[module])
    return (head + bytes([value]) + _crc_le(bytes([value]))).hex()


class NcVendorFrameCrcTest(unittest.TestCase):
    """The transcribed frames themselves: every CRC recomputed from the bytes."""

    def test_every_vendor_header_crc_checks_out(self):
        frames = dict(VENDOR_FRAMES)
        frames.update({f"set{m:02x}": h for m, h in VENDOR_SET_HEADERS.items()})
        for name, hx in frames.items():
            b = bytes.fromhex(hx)
            self.assertEqual(len(b), 14, name)
            self.assertEqual(b[:4], b"XXUB", name)
            self.assertEqual(b[10:14], _crc_le(b[:10]), f"{name}: header CRC does not check out")

    def test_set_gain_30_is_the_vendors_19_bytes(self):
        self.assertEqual(_set_frame(0x04, 30), P + "0403010105008715a956" + "1e" + "eed20d28")
        b = bytes.fromhex(_set_frame(0x12, 0))
        self.assertEqual(len(b), 19)
        self.assertEqual(int.from_bytes(b[8:10], "little"), 5)

    def test_no_factory_or_left_aec_toggle_is_ever_built(self):
        for name, hx in VENDOR_FRAMES.items():
            b = bytes.fromhex(hx)
            self.assertNotEqual(b[4], 0x09, name)
            self.assertFalse(b[4] == 0x16 and b[5] == 0x03, name)
        src = FRAMES.read_text()
        self.assertIn('LAEC(0x16, "laec", 0x01, -1, false)', src)  # no toggle sequence
        self.assertNotIn("FACTORY(", src)


class NcFramesPlainJavaTest(unittest.TestCase):
    def test_frames_are_plain_java_with_no_file_io(self):
        raw = FRAMES.read_text() if FRAMES.exists() else ""
        self.assertTrue(raw, "NcFrames.java missing")
        self.assertEqual([ln for ln in raw.splitlines() if ln.startswith("import android")], [])
        for needle in ("import java.io", "FileInputStream", "FileOutputStream"):
            self.assertNotIn(needle, raw)
        self.assertIn("java.util.zip.CRC32", raw)


if __name__ == "__main__":
    unittest.main()
