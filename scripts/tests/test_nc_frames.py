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
        cls.results, cls.run_output = {}, ""
        if cls.compiled:
            r = subprocess.run([jdk[1], "-cp", out, "com.miko3.shared.NcFramesHarness"],
                               capture_output=True, text=True, timeout=60)
            cls.run_output = (r.stdout + r.stderr)[-6000:]
            cls.results = jvm_harness.parse_verdicts(r.stdout)

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


jvm_harness.add_scenario_tests(NcFramesHarnessTest)


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
