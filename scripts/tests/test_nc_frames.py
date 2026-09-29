"""Host-side tests for the NC direction chip (explore plan U2; R7, R8, KTD10
to KTD12).

NcFrames (the 14-byte request frames, reply parsing, the calibration from raw
0 to 255 to signed degrees) and VoiceDirection's NC backend run under a JVM
harness with a fake native layer and a fake port node, so the gating (the
confirmed-port property, the node, initNCUART's 0 = success), the reply
deadline, the stale-byte flush, the one toggle per open and the three-miss
close are all proven without the robot or the vendor library.
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
        "query_frame_matches_the_decoded_bytes",
        "toggle_frame_matches_the_decoded_bytes",
        "status_on_reply_parses_to_raw_138",
        "status_off_reply_reports_off",
        "truncated_or_unprefixed_reply_is_nan",
        "calibration_maps_raw_to_signed_degrees",
        "calibration_wraps_to_plus_minus_180",
        "uncalibrated_is_nan",
        "unset_port_is_none_with_no_native_call",
        "lazy_sampler_opens_on_its_own_thread_and_never_blocks_the_caller",
        "missing_node_is_none_with_no_native_call",
        "init_returning_one_is_none",
        "confirmed_port_opens_nc_and_reports_calibrated_degrees",
        "uncalibrated_nc_gives_nan_but_keeps_the_raw_value",
        "status_off_toggles_once_per_open",
        "a_status_on_reply_never_toggles",
        "silent_node_returns_nan_within_the_deadline",
        "stale_bytes_are_discarded_before_the_next_get",
        "three_misses_close_the_backend",
        "a_reply_resets_the_miss_count",
        "silent_node_at_open_is_none",
        "first_raw_replies_are_logged_in_hex",
        "config_reads_port_and_calibration",
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
