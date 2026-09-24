"""Host-side tests for the launcher's Claude settings (settings plan U2, KTD2):
the plain-Java save rules that guard the stored API key (R4-R7, R15, R16),
plus source-wiring checks for the SharedPreferences store in LauncherApp,
which host tests can't run."""
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
LAUNCHER_SRC = REPO / "launcher" / "src"
LAUNCHER = LAUNCHER_SRC / "com" / "miko3" / "launcher"
SHARED_SRC = REPO / "shared" / "src"
HARNESS = TESTS / "fixtures" / "launcher_settings_harness" / "src"
HARNESS_MAIN = HARNESS / "com" / "miko3" / "launcher" / "ClaudeSettingsHarness.java"
SETTINGS = LAUNCHER / "ClaudeSettings.java"
APP = LAUNCHER / "LauncherApp.java"


def _strip_comments(text):
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


class ClaudeSettingsSourceTest(unittest.TestCase):
    def test_no_android_imports(self):
        offenders = [line for line in SETTINGS.read_text().splitlines() if line.startswith("import android")]
        self.assertEqual(offenders, [])

    def test_url_normalization_comes_from_shared_client(self):
        # One normalization for the page, the client and the script (R6).
        self.assertIn("ClaudeApi.normalizeBaseUrl(", _strip_comments(SETTINGS.read_text()))

    def test_no_logging(self):
        # R14: the settings class never writes anything near the key to logcat.
        src = _strip_comments(SETTINGS.read_text())
        for needle in ("System.out", "System.err", "Log.", "printStackTrace"):
            self.assertNotIn(needle, src)

    def test_only_one_accessor_returns_the_full_key(self):
        # R4: the page renders from status(); only credentialsForRequests() carries the key.
        src = _strip_comments(SETTINGS.read_text())
        self.assertIn("credentialsForRequests()", src)
        self.assertEqual(len(re.findall(r"\bapiKey\s*=", src)), 1, "apiKey assigned in more than one place")


class LauncherAppStoreWiringTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.src = _strip_comments(APP.read_text())

    def test_prefs_store_implements_the_interface(self):
        self.assertRegex(self.src, r"implements\s+ClaudeSettings\.Store")

    def test_writes_are_durable(self):
        # KTD2/R15: commit(), not apply(), so a save is on disk before the redirect.
        self.assertIn(".commit()", self.src)
        self.assertNotIn(".apply()", self.src)

    def test_private_prefs_file(self):
        self.assertRegex(self.src, r"getSharedPreferences\(\s*ClaudeSettings\.PREFS_NAME\s*,\s*MODE_PRIVATE\s*\)")

    def test_application_exposes_the_settings(self):
        self.assertRegex(self.src, r"ClaudeSettings\s+claudeSettings\(\)")


class ClaudeSettingsHarnessTest(unittest.TestCase):
    SCENARIOS = (
        "fresh_store_defaults",
        "fresh_store_saves_only_a_key",
        "blank_key_keeps_stored_key",
        "whitespace_key_counts_as_blank",
        "pasted_key_is_trimmed",
        "url_change_with_blank_key_rejected",
        "url_change_with_blank_key_rejected_even_without_stored_key",
        "url_change_with_new_key_succeeds",
        "url_spellings_count_as_the_same_url",
        "stored_url_is_normalized",
        "http_url_rejected",
        "url_without_host_rejected",
        "url_with_spaces_rejected",
        "empty_url_rejected",
        "refusals_never_echo_input",
        "key_with_control_chars_rejected",
        "forget_key_clears_key",
        "status_shows_last_four_and_saved_at",
        "saved_at_kept_when_key_kept",
        "short_key_shows_no_suffix",
        "status_never_exposes_full_key",
        "credentials_to_string_hides_key",
        "save_is_one_commit",
        "values_survive_a_new_instance",
        "empty_model_allowed",
        "model_ids_accepted",
        "model_with_control_chars_rejected",
        "model_with_space_rejected",
        "model_with_markup_rejected",
        "model_with_quote_rejected",
        "model_too_long_rejected",
        "model_at_length_limit_accepted",
        "model_list_round_trips",
        "model_list_drops_invalid_ids",
        "url_change_clears_model_list",
    )

    @classmethod
    def setUpClass(cls):
        jdk = jvm_harness.find_jdk()
        if jdk is None:
            raise unittest.SkipTest("no JDK (javac + java) found")
        cls._td = tempfile.TemporaryDirectory(prefix="launcher_settings_harness_")
        out = cls._td.name
        # javac pulls in only what the harness references: ClaudeSettings and the
        # plain-Java shared client, never the Android-bound launcher classes.
        c = subprocess.run(jvm_harness.javac_cmd(jdk[0], out, [HARNESS_MAIN],
                                                 [HARNESS, LAUNCHER_SRC, SHARED_SRC]),
                           capture_output=True, text=True)
        cls.compiled = c.returncode == 0
        cls.compile_output = (c.stdout + c.stderr)[-3000:]
        cls.results = {}
        cls.run_output = ""
        if c.returncode == 0:
            r = subprocess.run([jdk[1], "-cp", out, "com.miko3.launcher.ClaudeSettingsHarness"],
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


jvm_harness.add_scenario_tests(ClaudeSettingsHarnessTest)


if __name__ == "__main__":
    unittest.main()
