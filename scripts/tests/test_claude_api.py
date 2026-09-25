"""Host-side tests for shared/ClaudeApi (settings plan U3, KTD3, KTD4, KTD5):
the plain-Java Claude client that lists models, runs the one-token
connection test and sends image+text requests for JSON (explore plan U1), mapping every failure to a fixed reason. The harness swaps
in a fake transport, so no request leaves the machine."""
import re
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
SHARED = REPO / "shared" / "src" / "com" / "miko3" / "shared"
HARNESS = TESTS / "fixtures" / "claude_api_harness" / "src"
HARNESS_MAIN = HARNESS / "com" / "miko3" / "shared" / "ClaudeApiHarness.java"
CLIENT = SHARED / "ClaudeApi.java"
TRANSPORT = SHARED / "ClaudeHttpsTransport.java"
PLAIN_JAVA = [CLIENT, TRANSPORT, SHARED / "Json.java", SHARED / "ClaudeAccess.java"]


class ClaudeClientIsPlainJavaTest(unittest.TestCase):
    """Both classes compile into every APK and must also run on the host JVM."""

    def test_no_android_imports(self):
        for path in PLAIN_JAVA:
            offenders = [line for line in path.read_text().splitlines() if line.startswith("import android")]
            self.assertEqual(offenders, [], path.name)


class HttpsTransportWiringTest(unittest.TestCase):
    """Source-wiring checks for the real transport, which host tests can't reach
    (it only speaks HTTPS to a real endpoint)."""

    @classmethod
    def setUpClass(cls):
        # Comments stripped, so a mention in prose can't satisfy a check.
        text = TRANSPORT.read_text()
        text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
        cls.src = re.sub(r"//[^\n]*", "", text)

    def test_connect_timeout_set(self):
        self.assertRegex(self.src, r"\.setConnectTimeout\(\s*[A-Z_0-9]+\s*\)")

    def test_read_timeout_set(self):
        # The per-call timeout (explore plan U1, KTD6) with the 30 s default behind it.
        self.assertRegex(self.src, r"\.setReadTimeout\(\s*request\.readTimeoutMs\s*>\s*0\s*\?\s*request\.readTimeoutMs\s*:\s*READ_TIMEOUT_MS\s*\)")

    def test_default_read_timeout_is_30_s(self):
        self.assertRegex(self.src, r"READ_TIMEOUT_MS\s*=\s*30000\s*;")

    def test_timeouts_are_positive(self):
        for name in ("CONNECT_TIMEOUT_MS", "READ_TIMEOUT_MS"):
            m = re.search(name + r"\s*=\s*([0-9_]+)\s*;", self.src)
            self.assertIsNotNone(m, name)
            self.assertGreater(int(m.group(1).replace("_", "")), 0, name)

    def test_redirects_disabled(self):
        self.assertIn(".setInstanceFollowRedirects(false)", self.src)
        self.assertNotIn(".setInstanceFollowRedirects(true)", self.src)
        self.assertNotIn("setFollowRedirects(", self.src.replace(".setInstanceFollowRedirects(", ""))

    def test_error_bodies_read_from_error_stream(self):
        self.assertIn(".getErrorStream()", self.src)

    def test_only_https_connections(self):
        self.assertIn("HttpsURLConnection", self.src)

    def test_no_logging(self):
        # R14: nothing about a request (and so nothing near the key) reaches logcat.
        self.assertNotIn("Log.", self.src)
        self.assertNotIn("System.out", self.src)
        self.assertNotIn("System.err", self.src)


class ClaudeApiHarnessTest(unittest.TestCase):
    """One harness run, one assertion per scenario."""

    SCENARIOS = (
        "normalize_drops_trailing_slash_and_v1",
        "normalize_keeps_a_path_prefix",
        "normalize_rejects_non_https",
        "normalize_rejects_missing_host",
        "normalize_rejects_whitespace",
        "normalize_rejects_query_and_fragment",
        "normalize_rejects_userinfo_and_bad_ports",
        "list_single_page_returns_all_ids",
        "list_two_pages_fetched_with_after_id",
        "list_has_more_without_last_id_stops",
        "list_404_endpoint_does_not_list_models",
        "list_401_bad_key",
        "list_200_not_json_is_endpoint_error",
        "list_drops_ids_that_echo_the_key",
        "list_deeply_nested_json_is_endpoint_error",
        "list_missing_key_makes_no_request",
        "list_bad_base_url_makes_no_request",
        "test_200_success",
        "test_401_bad_key",
        "test_403_no_permission",
        "test_402_billing",
        "test_400_spend_limit_is_billing",
        "test_400_other_is_invalid_request",
        "test_404_not_found_error_unknown_model",
        "test_429_rate_limited",
        "test_529_overloaded",
        "test_500_endpoint_error",
        "test_504_endpoint_error",
        "test_unmapped_status_endpoint_error",
        "test_non_json_error_body_falls_back_to_status",
        "test_error_type_refines_unmapped_status",
        "redirect_is_error_and_not_followed",
        "every_request_sends_auth_and_version_headers",
        "post_sends_json_content_type",
        "paths_are_v1_whatever_the_base_form",
        "request_body_is_one_token_ping",
        "request_body_escapes_the_model",
        "missing_key_makes_no_request",
        "empty_model_makes_no_request",
        "model_with_control_chars_makes_no_request",
        "key_with_control_chars_makes_no_request",
        "key_never_in_reason",
        "unknown_host_is_unreachable",
        "connection_refused_is_unreachable",
        "timeout_is_unreachable",
        "ssl_failure_is_tls_failed",
        "reasons_are_fixed_text",
        "messages_images_and_text_serialize_in_order",
        "messages_json_reply_parses",
        "messages_schema_sent_as_output_config",
        "messages_sends_auth_version_and_json_headers",
        "messages_timeout_reaches_transport",
        "messages_without_schema_sends_no_output_config",
        "existing_calls_keep_the_default_timeout",
        "messages_output_config_400_retries_once_without_it",
        "messages_later_calls_skip_output_config",
        "messages_fallback_retries_only_once",
        "messages_other_400_does_not_retry",
        "messages_timeout_is_unreachable",
        "messages_error_status_maps_like_the_connection_test",
        "messages_invalid_json_is_bad_reply",
        "messages_malformed_response_body_is_endpoint_error",
        "messages_prose_wrapped_json_parses",
        "messages_text_without_json_is_refused",
        "messages_refusal_stop_reason_is_refused",
        "messages_not_set_up_makes_no_request",
        "jpeg_block_base64_has_no_newlines",
    )

    @classmethod
    def setUpClass(cls):
        jdk = jvm_harness.find_jdk()
        if jdk is None:
            raise unittest.SkipTest("no JDK (javac + java) found")
        cls._td = tempfile.TemporaryDirectory(prefix="claude_api_harness_")
        out = cls._td.name
        # Only the plain-Java classes are compiled; the rest of shared/ needs the Android SDK.
        # The real transport is compiled too, to prove it builds without Android.
        sources = [HARNESS_MAIN, CLIENT, TRANSPORT, SHARED / "Json.java", SHARED / "ClaudeAccess.java"]
        c = subprocess.run(jvm_harness.javac_cmd(jdk[0], out, sources, [HARNESS]),
                           capture_output=True, text=True)
        cls.compiled = c.returncode == 0
        cls.compile_output = (c.stdout + c.stderr)[-3000:]
        cls.results = {}
        cls.run_output = ""
        if c.returncode == 0:
            r = subprocess.run([jdk[1], "-cp", out, "com.miko3.shared.ClaudeApiHarness"],
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

    def test_harness_reports_exactly_the_expected_scenarios(self):
        self.assertEqual(sorted(self.results), sorted(self.SCENARIOS), self.run_output)


jvm_harness.add_scenario_tests(ClaudeApiHarnessTest)


if __name__ == "__main__":
    unittest.main()
