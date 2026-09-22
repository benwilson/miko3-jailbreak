#!/usr/bin/env python3
"""Tests for the launcher's mode registry and presence probe (U9, KTD8).

The robot has no Android unit-test harness, so the shared module's
ModeRegistry and RoutingHttpServer are compiled for the host JVM (with the
stub android.util.Log / android.content.Context from fixtures/ws_harness/stubs)
and exercised by fixtures/mode_registry_harness, which prints one PASS/FAIL
line per scenario:

- registry lookups and the /launch-mode target (no param keeps meaning
  remote-control; an unknown id resolves to nothing),
- presence JSON parsing,
- which mode to force-exit given each mode's presence answer,
- a real plain + HTTPS RoutingHttpServer pair (HTTPS from
  shared/assets/server.p12): /presence is answered with a 200 on the plain
  listener while HTTPS is up, every other path still redirects, and the
  probe the launcher uses reads active/inactive through it and treats
  refused, silent, 404, and wrong-mode answers as "not known to be running".

SourceWiringTest checks the two sides that cannot run on the host: the
remote-control mode serves the presence route and its exit path releases
the microphone and operator speaker, and the launcher builds its launch
links and exit decision from the registry. Device behavior (switching
modes both ways, the mic actually freed) is the plan's on-device gate.
"""
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
SCRIPTS = REPO / "scripts"
SHARED_SRC = REPO / "shared" / "src"
STUBS = REPO / "scripts" / "tests" / "fixtures" / "ws_harness" / "stubs"
HARNESS = REPO / "scripts" / "tests" / "fixtures" / "mode_registry_harness" / "src"
HARNESS_MAIN = HARNESS / "com" / "miko3" / "shared" / "ModeRegistryHarness.java"
SERVER_P12 = REPO / "shared" / "assets" / "server.p12"
RC_SRC = REPO / "mode-remote-control" / "src" / "com" / "miko3" / "mode" / "remotecontrol"
VOICE_SRC = REPO / "mode-voice" / "src" / "com" / "miko3" / "mode" / "voice"
EXPLORE_SRC = REPO / "mode-explore" / "src" / "com" / "miko3" / "mode" / "explore"
REGISTRY_SRC = SHARED_SRC / "com" / "miko3" / "shared" / "ModeRegistry.java"
LAUNCHER_SRC = REPO / "launcher" / "src" / "com" / "miko3" / "launcher"


class ModeRegistryHarnessTest(unittest.TestCase):
    """One harness run, one assertion per scenario."""

    SCENARIOS = (
        # registry lookups
        "registry_order_and_ids",
        "remote_control_entry",
        "voice_entry",
        "explore_entry",
        "ids_match_protocol_constants",
        "by_id_unknown_is_null",
        "registry_is_unmodifiable",
        "launch_target_no_param_is_remote_control",
        "launch_target_by_id",
        "launch_target_unknown_is_null",
        "presence_path_constant",
        # presence JSON
        "parse_active_true",
        "parse_active_false",
        "parse_whitespace_and_order_tolerant",
        "parse_other_mode_is_unknown",
        "parse_malformed_is_unknown",
        "presence_json_shape",
        "presence_json_round_trips",
        # force-exit decision
        "none_active_exits_nothing",
        "remote_control_active_is_exited",
        "voice_active_is_exited",
        "explore_active_is_exited",
        "probe_failures_exit_nothing",
        "failure_plus_active_exits_only_active",
        "both_active_exits_both_in_registry_order",
        "missing_and_unregistered_ids_ignored",
        # RoutingHttpServer + probe over real sockets
        "https_listener_up",
        "other_path_still_redirects_to_https",
        "presence_served_plain_while_https_up",
        "presence_with_query_served_plain",
        "presence_prefix_path_still_redirects",
        "probe_active_over_plain_while_https_up",
        "probe_inactive_flips",
        "probe_wrong_mode_is_unknown",
        "probe_404_is_unknown",
        "probe_refused_is_unknown",
        "probe_silent_server_times_out",
    )

    @classmethod
    def setUpClass(cls):
        jdk = jvm_harness.find_jdk()
        if jdk is None:
            raise unittest.SkipTest("no JDK (javac + java) found")
        cls._td = tempfile.TemporaryDirectory(prefix="mode_registry_harness_")
        out = cls._td.name
        c = subprocess.run(jvm_harness.javac_cmd(jdk[0], out, [HARNESS_MAIN], [HARNESS, SHARED_SRC, STUBS]),
                           capture_output=True, text=True)
        cls.compiled = c.returncode == 0
        cls.compile_output = (c.stdout + c.stderr)[-3000:]
        cls.results = {}
        cls.run_output = ""
        if c.returncode == 0:
            r = subprocess.run([jdk[1], "-cp", out, "com.miko3.shared.ModeRegistryHarness", str(SERVER_P12)],
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


jvm_harness.add_scenario_tests(ModeRegistryHarnessTest)


def _method_body(source, signature):
    """The brace-balanced body of the first method whose declaration contains signature."""
    start = source.index(signature)
    open_brace = source.index("{", start)
    depth = 0
    for i in range(open_brace, len(source)):
        if source[i] == "{":
            depth += 1
        elif source[i] == "}":
            depth -= 1
            if depth == 0:
                return source[open_brace:i + 1]
    raise AssertionError(f"unbalanced body for {signature}")


class SourceWiringTest(unittest.TestCase):
    """The Android-only halves of U9, checked at the source level."""

    # assertIn/assertNotIn would print the whole source file on failure.
    def assertIn(self, needle, haystack, msg=None):
        self.assertTrue(needle in haystack, msg or f"missing: {needle!r}")

    def assertNotIn(self, needle, haystack, msg=None):
        self.assertFalse(needle in haystack, msg or f"still present: {needle!r}")

    def assertRegex(self, text, pattern, msg=None):
        self.assertTrue(re.search(pattern, text), msg or f"no match for {pattern!r}")

    def test_remote_control_serves_presence_from_explicit_flag(self):
        app = (RC_SRC / "ModeApp.java").read_text()
        self.assertIn("server.route(LauncherProtocol.PRESENCE_PATH", app)
        self.assertIn("ModeRegistry.presenceJson(LauncherProtocol.MODE_REMOTE_CONTROL", app)
        # An explicit flag, not derived from the only-increasing generation counter.
        self.assertRegex(app, r"private volatile boolean active;")

    def test_remote_control_teardown_releases_mic_and_speaker_and_presence(self):
        activity = (RC_SRC / "MainActivity.java").read_text()
        for signature in ("private void exitMode()", "protected void onDestroy()"):
            body = _method_body(activity, signature)
            with self.subTest(path=signature):
                self.assertIn("app.stopMicAndSpeaker()", body)
                self.assertIn("app.deactivate(myGeneration)", body)
                # Presence clears only after the mic is released.
                self.assertLess(body.index("app.stopMicAndSpeaker()"), body.index("app.deactivate(myGeneration)"))
                self.assertIn("releaseCaptureBridge()", body)
                # The instance's own mic passthrough is released before presence clears too.
                self.assertLess(body.index("releaseCaptureBridge()"), body.index("app.deactivate(myGeneration)"))
        self.assertIn("captureBridge.toggleOperatorMic(false)", _method_body(activity, "void releaseCaptureBridge()"))

    def test_voice_teardown_releases_mic_before_presence(self):
        activity = (VOICE_SRC / "MainActivity.java").read_text()
        for signature in ("private void exitMode()", "protected void onDestroy()"):
            with self.subTest(path=signature):
                self.assertIn("releaseIfCurrent()", _method_body(activity, signature))
        body = _method_body(activity, "private boolean releaseIfCurrent()")
        self.assertIn("app.stopVoice()", body)
        self.assertIn("app.deactivate(myGeneration)", body)
        # Presence clears only after the mic is released, so the launcher's wait
        # for inactive presence implies a free mic (as in remote-control).
        self.assertLess(body.index("app.stopVoice()"), body.index("app.deactivate(myGeneration)"))
        # Stale-instance guard: only the current generation stops the engine.
        self.assertIn("app.isCurrent(myGeneration)", body)
        self.assertLess(body.index("app.isCurrent(myGeneration)"), body.index("app.stopVoice()"))

    def test_explore_serves_presence_from_explicit_flag(self):
        app = (EXPLORE_SRC / "ModeApp.java").read_text()
        self.assertIn("server.route(LauncherProtocol.PRESENCE_PATH", app)
        self.assertIn("ModeRegistry.presenceJson(LauncherProtocol.MODE_EXPLORE", app)
        self.assertRegex(app, r"private volatile boolean active;")

    def test_explore_ports_match_the_registry(self):
        """The mode keeps its own port literals; they must equal ModeRegistry.EXPLORE's."""
        app = (EXPLORE_SRC / "ModeApp.java").read_text()
        self.assertRegex(app, r"static final int PORT = 8083;")
        self.assertRegex(app, r"static final int HTTPS_PORT = 8446;")
        self.assertRegex(REGISTRY_SRC.read_text(), r"com\.miko3\.mode\.explore\.MainActivity\",\s*8083, 8446,")

    def test_explore_teardown_stops_wander_before_presence(self):
        activity = (EXPLORE_SRC / "MainActivity.java").read_text()
        for signature in ("private void exitMode()", "protected void onDestroy()"):
            with self.subTest(path=signature):
                self.assertIn("releaseIfCurrent()", _method_body(activity, signature))
        body = _method_body(activity, "private boolean releaseIfCurrent()")
        # The robot is stopped (and the lease released, U5) before the launcher
        # can see presence go inactive and start another mode (R5).
        self.assertIn("app.stopExplore()", body)
        self.assertIn("app.deactivate(myGeneration)", body)
        self.assertLess(body.index("app.stopExplore()"), body.index("app.deactivate(myGeneration)"))
        # Stale-instance guard: only the current generation stops the wander.
        self.assertIn("app.isCurrent(myGeneration)", body)
        self.assertLess(body.index("app.isCurrent(myGeneration)"), body.index("app.stopExplore()"))
        # Force-exit is honored on a fresh instance and on the running one.
        self.assertIn("LauncherProtocol.EXTRA_FORCE_EXIT", activity)
        self.assertIn("exitMode()", _method_body(activity, "protected void onNewIntent(Intent intent)"))

    def test_launcher_has_no_hard_coded_mode(self):
        app = (LAUNCHER_SRC / "LauncherApp.java").read_text()
        self.assertNotIn("com.miko3.mode.remotecontrol", app)
        self.assertNotIn("MODE_PORT", app)
        self.assertIn("ModeRegistry.resolveLaunchTarget(", app)
        self.assertIn("ModeRegistry.activeModes(", app)
        self.assertIn("LauncherProtocol.EXTRA_FORCE_EXIT", app)

    def test_launcher_page_links_every_registered_mode(self):
        page = (LAUNCHER_SRC / "LauncherPage.java").read_text()
        self.assertIn("ModeRegistry.all()", page)
        self.assertIn("LauncherProtocol.LAUNCH_MODE_PATH", page)
        self.assertIn("LauncherProtocol.LAUNCH_MODE_PARAM", page)


if __name__ == "__main__":
    unittest.main()
