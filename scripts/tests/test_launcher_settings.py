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
    "SETTINGS_CONVERSATION_PATH",
    "SETTINGS_FACE_CHECK_CROP_PATH",
    "SETTINGS_PEOPLE_PHOTO_PATH",
    "SETTINGS_PEOPLE_PHOTO_DELETE_PATH",
    "SETTINGS_FACE_THRESHOLDS_PATH",
    "SETTINGS_FACE_STATE_PATH",
)
PROBE = LAUNCHER / "EarsProbe.java"
CONVERSATION = SHARED_SRC / "com" / "miko3" / "shared" / "ConversationSettings.java"
PROBE_SCRIPT = REPO / "scripts" / "qa-ears-probe.py"
FACE_CHECKS = LAUNCHER / "FaceChecks.java"
FACE_CHECK = SHARED_SRC / "com" / "miko3" / "shared" / "FaceCheck.java"
FACE_SETTINGS = SHARED_SRC / "com" / "miko3" / "shared" / "FaceSettings.java"
REQUEST = SHARED_SRC / "com" / "miko3" / "shared" / "HttpRequest.java"
CHECKS_HARNESS = TESTS / "fixtures" / "face_checks_harness" / "src"
CHECKS_HARNESS_MAIN = CHECKS_HARNESS / "com" / "miko3" / "launcher" / "FaceChecksHarness.java"


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

    def test_default_persona_is_a_launcher_constant_with_the_workplace_test(self):
        """Meeting plan U4 (KTD11, R13, R20): the built-in persona is the launcher's
        constant, and it carries the workplace test with a concrete list under it."""
        raw = SETTINGS.read_text()
        m = re.search(r"static final String DEFAULT_PERSONA\s*=", raw)
        self.assertIsNotNone(m, "ClaudeSettings has no DEFAULT_PERSONA")
        body = raw[m.end():]
        body = body[:body.index(";\n")]
        self.assertRegex(body, r"(?i)fired")
        # A concrete list, not just the rule: several distinct "never" items.
        self.assertGreaterEqual(len(re.findall(r'"- ', body)), 4, body)

    def test_persona_cap_is_the_shared_constant(self):
        src = _strip_comments(SETTINGS.read_text())
        self.assertIn("ConversationSettings.MAX_PERSONA_CHARS", src)
        conv = CONVERSATION.read_text() if CONVERSATION.exists() else ""
        self.assertRegex(conv, r"MAX_PERSONA_CHARS\s*=\s*2500\s*;")
        self.assertEqual([ln for ln in conv.splitlines() if ln.startswith("import android")], [])

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
        "persona_round_trips_same_bytes",
        "blank_persona_reads_as_unset_and_default",
        "persona_over_cap_rejected_with_message",
        "persona_at_cap_accepted",
        "persona_crlf_saved_as_lf",
        "answers_switch_defaults_on_and_round_trips",
        "persona_and_switch_leave_the_key_alone",
        # Face plan U5 (KTD5): band and gate thresholds.
        "face_settings_default_to_the_starting_values",
        "face_settings_round_trip_and_survive_a_new_instance",
        "face_save_close_above_confident_refused_and_unchanged",
        "face_save_dark_floor_above_dim_level_refused_and_unchanged",
        "face_save_out_of_range_values_refused",
        "face_save_edges_accepted",
        "face_refusals_are_fixed_text",
        "corrupt_stored_face_value_reads_as_defaults",
        "face_settings_leave_the_key_alone",
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
            "SETTINGS_EARS_PROBE_PATH": "/settings/ears-probe",
            "SETTINGS_CONVERSATION_PATH": "/settings/conversation",
            "SETTINGS_FACE_CHECK_CROP_PATH": "/settings/face/check-crop",
            "SETTINGS_PEOPLE_PHOTO_PATH": "/settings/people/photo",
            "SETTINGS_PEOPLE_PHOTO_DELETE_PATH": "/settings/people/photo/delete",
            "SETTINGS_FACE_THRESHOLDS_PATH": "/settings/face/thresholds",
            "SETTINGS_FACE_STATE_PATH": "/settings/face/state",
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

    def test_launcher_registers_the_ears_probe_route(self):
        """Meeting plan U1: the probe is its own handler on the TLS settings path,
        gated by the debug property and the page token, never by the URL."""
        self.assertRegex(self.app, r"server\.route\(\s*LauncherProtocol\.SETTINGS_EARS_PROBE_PATH\s*,")
        self.assertIn("earsProbe.handle(", self.app)
        self.assertRegex(self.app, r"new\s+EarsProbe\(")
        self.assertIn("settingsToken", self.app.split("new EarsProbe(", 1)[1].split(";", 1)[0])

    def test_ears_probe_is_plain_java_and_never_logs(self):
        raw = PROBE.read_text() if PROBE.exists() else ""
        self.assertTrue(raw, "EarsProbe.java missing")
        self.assertEqual([ln for ln in raw.splitlines() if ln.startswith("import android")], [])
        stripped = _strip_comments(raw)
        self.assertNotRegex(stripped, r"\bLog\.\w\(")
        for needle in ("System.out", "System.err", "printStackTrace"):
            self.assertNotIn(needle, stripped)
        # No query parameter is read: the nonce and the token travel in the body only.
        self.assertNotIn("queryParam(", stripped)
        self.assertNotIn("req.query", stripped)

    def test_ears_probe_property_matches_the_script(self):
        java = re.search(r'PROPERTY\s*=\s*"([^"]+)"', PROBE.read_text() if PROBE.exists() else "")
        py = re.search(r'^PROPERTY\s*=\s*"([^"]+)"', PROBE_SCRIPT.read_text() if PROBE_SCRIPT.exists() else "",
                       flags=re.M)
        self.assertIsNotNone(java, "EarsProbe declares no PROPERTY")
        self.assertIsNotNone(py, "qa-ears-probe.py declares no PROPERTY")
        self.assertEqual(java.group(1), py.group(1))
        self.assertTrue(java.group(1).startswith("debug."), "apps may only set/read debug.* properties over adb")

    def test_page_reads_no_query_parameter_but_status(self):
        # No settings path takes the key (or anything else) from the URL, which
        # RoutingHttpServer logs. The image GETs take only an id (a person's or
        # a check's handle), a photo slot and its recorded added-at time.
        params = re.findall(r"queryParam\(\s*\"([^\"]*)\"", self.page)
        self.assertEqual(set(params), {"status", "id", "slot", "at"})
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
        "ears_probe_is_404_without_the_property",
        "ears_probe_is_404_on_a_get",
        "ears_probe_is_404_with_a_wrong_nonce_or_no_token",
        "ears_probe_is_404_fifteen_minutes_after_the_property_was_first_read",
        "ears_probe_rows_carry_counts_and_match_flags_never_text",
        "ears_probe_clamps_seconds_and_reports_a_busy_microphone",
        "ears_probe_path_is_tls_only",
        "conversation_section_shows_default_persona_and_switch_on",
        "conversation_section_shows_stored_persona_escaped_and_never_the_key",
        "conversation_form_carries_token_persona_and_switch",
        "conversation_save_stores_persona_and_switch",
        "conversation_save_switch_off_when_box_unchecked",
        "conversation_save_over_cap_refused_and_unchanged",
        "conversation_save_blank_resets_to_default",
        "conversation_save_with_stale_token_rejected",
        "conversation_status_never_echoes_persona",
        "get_on_conversation_path_refused",
        "conversation_path_is_tls_only",
        "people_notes_render_escaped",
        "people_notes_list_fields_and_thread_dates",
        "people_nameless_record_marked_legacy",
        "forget_removes_notes_too",
        # Face plan U5 (KTD5, KTD8): face checks, thresholds, photo strips, the CLI routes.
        "face_checks_section_empty",
        "face_check_renders_crop_photo_name_score_band_and_outcome_escaped",
        "rejected_check_shows_reason_and_no_best_match_photo",
        "not_ready_and_no_face_checks_render_decision_and_no_face_has_no_crop",
        "near_tie_check_shows_runner_up_name_and_score",
        "replaced_best_match_slot_shows_marker_not_new_photo",
        "photo_route_serves_the_photo_only_while_added_at_matches",
        "check_crop_route_serves_jpeg_and_404s_unknown",
        "forget_on_page_purges_face_checks",
        "thresholds_block_is_read_only_with_current_values",
        "threshold_save_from_loopback_stores_values",
        "threshold_save_from_non_loopback_refused",
        "threshold_save_invalid_refused_and_unchanged",
        "threshold_save_needs_the_page_token",
        "photo_strip_shows_every_photo_and_marks_unusable",
        "delete_photo_removes_slot_and_embedding",
        "delete_last_photo_refused",
        "delete_photo_unknown_or_stale_token_changes_nothing",
        "face_state_refuses_missing_or_wrong_token",
        "face_state_carries_checks_people_and_thresholds_without_images",
        "face_paths_are_tls_only",
        "get_on_face_action_paths_refused",
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


class FaceChecksHarnessTest(unittest.TestCase):
    """Face plan U5 (KTD8; R13, R14, R19): the in-memory ring of recent face checks."""
    SCENARIOS = (
        "record_answers_distinct_handles_newest_first",
        "eleventh_check_evicts_the_oldest_and_its_handle_updates_nothing",
        "update_outcome_sets_outcome_and_joined_id",
        "update_unknown_handle_is_a_no_op",
        "close_as_ended_closes_only_pending_checks",
        "purge_removes_checks_matching_or_joining_the_person",
        "crop_is_served_by_handle",
        "oversized_or_non_jpeg_crop_refused",
        "no_face_check_keeps_no_crop",
        "bad_ids_and_codes_refused",
        "list_is_a_snapshot",
        "to_string_carries_no_crop_bytes",
    )

    @classmethod
    def setUpClass(cls):
        jdk = jvm_harness.find_jdk()
        if jdk is None:
            raise unittest.SkipTest("no JDK (javac + java) found")
        cls._td = tempfile.TemporaryDirectory(prefix="face_checks_harness_")
        out = cls._td.name
        c = subprocess.run(jvm_harness.javac_cmd(jdk[0], out, [CHECKS_HARNESS_MAIN],
                                                 [CHECKS_HARNESS, LAUNCHER_SRC, SHARED_SRC]),
                           capture_output=True, text=True)
        cls.compiled = c.returncode == 0
        cls.compile_output = (c.stdout + c.stderr)[-3000:]
        cls.results = {}
        cls.run_output = ""
        if c.returncode == 0:
            r = subprocess.run([jdk[1], "-cp", out, "com.miko3.launcher.FaceChecksHarness"],
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


jvm_harness.add_scenario_tests(FaceChecksHarnessTest)


class FaceWiringTest(unittest.TestCase):
    """Face plan U5: what the harnesses can't reach, and the quality gates."""

    @classmethod
    def setUpClass(cls):
        cls.page = _strip_comments(PAGE.read_text())
        cls.app = _strip_comments(APP.read_text())

    def test_new_classes_are_plain_java_and_never_log(self):
        for path in (FACE_CHECKS, FACE_CHECK, FACE_SETTINGS):
            with self.subTest(file=path.name):
                raw = path.read_text() if path.exists() else ""
                self.assertTrue(raw, f"{path.name} missing")
                self.assertEqual([ln for ln in raw.splitlines() if ln.startswith("import android")], [])
                stripped = _strip_comments(raw)
                self.assertNotRegex(stripped, r"\bLog\.\w\(")
                for needle in ("System.out", "System.err", "printStackTrace"):
                    self.assertNotIn(needle, stripped)

    def test_launcher_owns_one_ring_and_passes_it_to_the_page(self):
        self.assertEqual(self.app.count("new FaceChecks("), 1)
        self.assertIn("FaceChecks faceChecks()", self.app)
        m = re.search(r"SettingsPage\.handle\(([^;]*)\);", self.app)
        self.assertIsNotNone(m)
        self.assertIn("faceChecks", m.group(1))

    def test_page_forget_purges_the_checks(self):
        m = re.search(r"private static String forget\([^)]*\)\s*\{(.*?)\n    \}", self.page, flags=re.S)
        self.assertIsNotNone(m, "SettingsPage has no forget()")
        # The page and PeopleService share one forget path: the person, then their checks.
        self.assertIn("FaceChecks.forget(", m.group(1))
        checks = _strip_comments(FACE_CHECKS.read_text())
        f = re.search(r"static boolean forget\([^)]*\)\s*\{(.*?)\n    \}", checks, flags=re.S)
        self.assertIsNotNone(f, "FaceChecks has no forget()")
        self.assertGreaterEqual(f.group(1).find(".forget("), 0)
        self.assertLess(f.group(1).find(".forget("), f.group(1).find(".purgePerson("))

    def test_threshold_save_checks_loopback_first(self):
        m = re.search(r"static void handle\([^)]*\)[^{]*\{(.*?)\n    \}", self.page, flags=re.S)
        self.assertIsNotNone(m, "SettingsPage has no handle()")
        body = m.group(1)
        self.assertRegex(body, r"SETTINGS_FACE_THRESHOLDS_PATH\.equals\(req\.path\) && !req\.fromLoopback")
        self.assertGreaterEqual(body.find("fromLoopback"), 0)
        self.assertLess(body.find("fromLoopback"), body.find("readForm("))

    def test_router_records_whether_the_caller_is_loopback(self):
        router = _strip_comments(ROUTER.read_text())
        self.assertIn("isLoopbackAddress()", router)
        self.assertIn("public final boolean fromLoopback", _strip_comments(REQUEST.read_text()))


if __name__ == "__main__":
    unittest.main()
