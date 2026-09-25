"""Host-side tests for the launcher's Claude settings: the plain-Java save
rules that guard the stored API key (settings plan U2, KTD2; R4-R7, R15, R16),
the Settings page and its actions (U4, KTD6; R1-R10, R14, R16), and
source-wiring checks for the Android glue (LauncherApp's store and routes,
the home-page link, MainActivity's reload rule), which host tests can't run."""
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
PAGE_HARNESS = TESTS / "fixtures" / "launcher_settings_page_harness" / "src"
PAGE_HARNESS_MAIN = PAGE_HARNESS / "com" / "miko3" / "launcher" / "SettingsPageHarness.java"
SETTINGS = LAUNCHER / "ClaudeSettings.java"
APP = LAUNCHER / "LauncherApp.java"
PAGE = LAUNCHER / "SettingsPage.java"
HOME_PAGE = LAUNCHER / "LauncherPage.java"
ACTIVITY = LAUNCHER / "MainActivity.java"
PROTOCOL = SHARED_SRC / "com" / "miko3" / "shared" / "LauncherProtocol.java"
REGISTRY = SHARED_SRC / "com" / "miko3" / "shared" / "ModeRegistry.java"
ROUTER = SHARED_SRC / "com" / "miko3" / "shared" / "RoutingHttpServer.java"
SETTINGS_PATHS = (
    "SETTINGS_PATH",
    "SETTINGS_CLAUDE_PATH",
    "SETTINGS_CLAUDE_MODELS_PATH",
    "SETTINGS_CLAUDE_TEST_PATH",
    "SETTINGS_CLAUDE_FORGET_PATH",
    "SETTINGS_VOICE_SAY_PATH",
    "SETTINGS_PEOPLE_RENAME_PATH",
    "SETTINGS_PEOPLE_FORGET_PATH",
    "SETTINGS_PEOPLE_FACE_PATH",
)


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
        "model_containing_the_new_key_rejected",
        "model_containing_the_stored_key_rejected",
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


class SettingsPageSourceTest(unittest.TestCase):
    """U4's source wiring: what the harness can't reach (routes, Android glue)
    and the R14 rules that are easiest to hold at the source level."""

    @classmethod
    def setUpClass(cls):
        cls.page = _strip_comments(PAGE.read_text())
        cls.app = _strip_comments(APP.read_text())

    def test_no_android_imports(self):
        offenders = [line for line in PAGE.read_text().splitlines() if line.startswith("import android")]
        self.assertEqual(offenders, [])

    def test_protocol_declares_the_paths(self):
        src = PROTOCOL.read_text()
        expected = {
            "SETTINGS_PATH": "/settings",
            "SETTINGS_CLAUDE_PATH": "/settings/claude",
            "SETTINGS_CLAUDE_MODELS_PATH": "/settings/claude/models",
            "SETTINGS_CLAUDE_TEST_PATH": "/settings/claude/test",
            "SETTINGS_CLAUDE_FORGET_PATH": "/settings/claude/forget",
            "SETTINGS_VOICE_SAY_PATH": "/settings/voice/say",
        }
        for name, path in expected.items():
            self.assertRegex(src, rf'public static final String {name} = "{re.escape(path)}";')

    def test_launcher_registers_every_settings_route(self):
        for name in SETTINGS_PATHS:
            self.assertRegex(self.app, rf"server\.route\(\s*LauncherProtocol\.{name}\s*,")
        self.assertIn("SettingsPage.handle(", self.app)
        # The page speaks in-process, through the engine's own queue.
        self.assertRegex(self.app, r"implements\s+SettingsPage\.Speaker")
        self.assertIn("speech.queue().speak(", self.app)
        self.assertRegex(self.app, r"new\s+PageToken\(\s*4\s*\)")
        self.assertRegex(self.app, r"new\s+ClaudeApi\(\s*new\s+ClaudeHttpsTransport\(\s*\)\s*\)")

    def test_page_reads_no_query_parameter_but_status(self):
        # No settings path takes the key (or anything else) from the URL, which
        # RoutingHttpServer logs. The face image GET takes only a person's id.
        params = re.findall(r"queryParam\(\s*\"([^\"]*)\"", self.page)
        self.assertEqual(set(params), {"status", "id"})
        self.assertNotIn("req.query.", self.page)

    def test_no_logging_near_the_key(self):
        for needle in ("System.out", "System.err", "Log.", "printStackTrace"):
            self.assertNotIn(needle, self.page)
        # LauncherApp does log, but never a settings value.
        for line in self.app.splitlines():
            if "Log." in line:
                self.assertNotRegex(line, r"apiKey|credentials|keyLastFour|\.status\(\)|form\.get|req\.body", line)

    def test_page_renders_from_status_only(self):
        # R4: the full key is fetched only to hand to the client.
        self.assertIn(".status()", self.page)
        self.assertEqual(len(re.findall(r"credentialsForRequests\(\)", self.page)), 2,
                         "expected exactly Refresh and Test to read the full key")
        self.assertEqual(len(re.findall(r"\.apiKey\b", self.page)), 2,
                         "the full key should go only to listModels() and testConnection()")

    def test_home_page_links_to_settings(self):
        src = _strip_comments(HOME_PAGE.read_text())
        self.assertIn("LauncherProtocol.SETTINGS_PATH", src)
        self.assertIn('role=\\"button\\"', src)

    def test_settings_page_links_home(self):
        self.assertIn('href=\\"/\\"', self.page)

    def test_settings_is_not_a_mode(self):
        self.assertNotIn("settings", REGISTRY.read_text().lower())

    def test_activity_reloads_only_on_the_home_page(self):
        src = _strip_comments(ACTIVITY.read_text())
        body = src[src.index("public void onReceive("):]
        body = body[:body.index("}")]
        self.assertIn("SettingsPage.isHomePageUrl(webView.getUrl())", body)
        self.assertLess(body.index("isHomePageUrl"), body.index("webView.reload()"))


class SettingsTlsOnlyWiringTest(unittest.TestCase):
    """RoutingHttpServer needs Android, so its plain-listener refusal of settings
    paths is checked at the source: it runs before the request body is wrapped
    or any route is looked up, and a non-GET is never redirected."""

    @classmethod
    def setUpClass(cls):
        cls.src = _strip_comments(ROUTER.read_text())
        cls.handle = cls.src[cls.src.index("private void handle(Socket client, boolean isTls)"):]

    def test_plain_listener_refuses_settings_paths_before_the_body(self):
        m = re.search(r"if\s*\(\s*!isTls\s*&&\s*LauncherProtocol\.isTlsOnlyPath\(\s*path\s*\)\s*\)\s*\{"
                      r"[^}]*sendText\(\s*503\s*,[^}]*LauncherProtocol\.settingsNeedHttpsMessage\([^}]*"
                      r"client\.close\(\);\s*return;", self.handle)
        self.assertIsNotNone(m, "no 503 branch for plain-listener settings paths")
        for later in ("bodyStream(", "new HttpRequest(", "wsRoutes.get(", "routes.get("):
            self.assertLess(m.start(), self.handle.index(later), later)

    def test_redirect_skips_non_get_settings_requests(self):
        redirect = self.handle.index("res.redirect(")
        guard = self.handle.rindex("if (!isTls && httpsPort > 0", 0, redirect)
        cond = self.handle[guard:redirect]
        self.assertIn("LauncherProtocol.PRESENCE_PATH", cond)
        self.assertRegex(cond, r"isTlsOnlyPath\(\s*path\s*\)")
        self.assertRegex(cond, r'"GET"\.equals\(\s*method\s*\)')
        # The redirect comes first so a GET still reaches HTTPS once it is up.
        self.assertLess(redirect, self.handle.index("sendText(503"))

    def test_launcher_https_port_comes_from_the_protocol(self):
        self.assertRegex(_strip_comments(APP.read_text()),
                         r"HTTPS_PORT\s*=\s*LauncherProtocol\.LAUNCHER_HTTPS_PORT\s*;")


class SettingsPageHarnessTest(unittest.TestCase):
    SCENARIOS = (
        "page_has_home_link_and_claude_section",
        "fresh_page_prefills_default_url",
        "html_never_contains_stored_key",
        "key_status_shows_last_four_and_saved_time",
        "fresh_page_says_no_key_set",
        "save_stores_key",
        "save_blank_key_keeps_key_and_updates_model",
        "post_without_token_rejected",
        "post_with_stale_token_rejected",
        "token_in_query_only_rejected",
        "recent_token_accepted",
        "rejected_save_does_not_echo_input",
        "oversized_form_rejected",
        "get_on_action_paths_refused",
        "refresh_stores_ids_and_page_suggests_them",
        "refresh_uses_saved_credentials",
        "saved_model_missing_from_list_marked",
        "refresh_failure_shows_reason_and_keeps_model",
        "refresh_404_says_type_a_model",
        "test_success_shows_status",
        "test_failure_shows_reason",
        "test_without_key_says_not_set_up",
        "forget_key_then_page_shows_no_key",
        "action_forms_carry_only_the_token",
        "status_line_is_escaped",
        "fresh_robot_flow",
        "home_page_url_check",
        "settings_paths_are_tls_only",
        "plain_http_settings_refusal_is_fixed_text",
        "voice_section_shows_say_form_and_voice_name",
        "say_without_token_rejected",
        "say_with_stale_token_rejected",
        "say_empty_text_refused",
        "say_too_long_text_refused",
        "say_while_voice_loading_refused",
        "say_after_voice_failed_says_not_available",
        "say_speaks_and_redirects",
        "say_status_never_echoes_text",
        "get_on_say_path_refused",
        "people_section_empty",
        "people_section_lists_faces_names_and_last_seen",
        "people_name_is_escaped",
        "people_forms_carry_token_and_id_only",
        "rename_changes_name_and_redirects",
        "rename_empty_makes_unnamed",
        "forget_removes_person_from_store_and_page",
        "people_actions_on_unknown_id_change_nothing",
        "people_actions_with_stale_token_refused",
        "people_status_never_echoes_name",
        "get_on_people_action_paths_refused",
        "face_get_serves_the_jpeg",
        "face_get_refuses_unknown_and_bad_ids",
        "people_paths_are_tls_only",
    )

    @classmethod
    def setUpClass(cls):
        jdk = jvm_harness.find_jdk()
        if jdk is None:
            raise unittest.SkipTest("no JDK (javac + java) found")
        cls._td = tempfile.TemporaryDirectory(prefix="launcher_settings_page_harness_")
        out = cls._td.name
        # As above: SettingsPage, ClaudeSettings and the shared HTTP and Claude
        # classes are plain Java, so javac never reaches an android.* class.
        c = subprocess.run(jvm_harness.javac_cmd(jdk[0], out, [PAGE_HARNESS_MAIN],
                                                 [PAGE_HARNESS, LAUNCHER_SRC, SHARED_SRC]),
                           capture_output=True, text=True)
        cls.compiled = c.returncode == 0
        cls.compile_output = (c.stdout + c.stderr)[-3000:]
        cls.results = {}
        cls.run_output = ""
        if c.returncode == 0:
            r = subprocess.run([jdk[1], "-cp", out, "com.miko3.launcher.SettingsPageHarness"],
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


jvm_harness.add_scenario_tests(SettingsPageHarnessTest)


if __name__ == "__main__":
    unittest.main()
