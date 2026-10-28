#!/usr/bin/env python3
"""Tests for the robot's volume keys (owner 2026-10-02: "turn them down or mute them
if I'm having a conversation with someone else").

shared/VolumeKeys maps volume up/down to raising and lowering STREAM_MUSIC (which
both the launcher's speech and Explore's clips play on) and either mute keycode
to toggling its mute. It needs the Android SDK, so the harness compiles it against
small host stubs whose constants are checked here against the platform jar.

The source-shape tests make every activity that can be in front (the launcher's
and each mode's) route its keys through the helper, and make Explore's brain hear
the speaker's mute as do-not-disturb.
"""
import re
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

TESTS = Path(__file__).resolve().parent
SCRIPTS = TESTS.parent
if str(TESTS) not in sys.path:
    sys.path.insert(0, str(TESTS))
import jvm_harness  # noqa: E402

REPO = SCRIPTS.parent
SHARED = REPO / "shared" / "src" / "com" / "miko3" / "shared"
VOLUME_KEYS = SHARED / "VolumeKeys.java"
HARNESS = TESTS / "fixtures" / "volume_keys_harness" / "src"
HARNESS_MAIN = HARNESS / "com" / "miko3" / "shared" / "VolumeKeysHarness.java"
STUBS = [HARNESS / "android" / "app" / "Activity.java", HARNESS / "android" / "content" / "Context.java",
         HARNESS / "android" / "media" / "AudioManager.java", HARNESS / "android" / "view" / "KeyEvent.java"]
EXPLORE = REPO / "mode-explore" / "src" / "com" / "miko3" / "mode" / "explore"

# The apps whose activities can be in front of the robot's screen. The boot agent's
# one-shot installer screen and the drive spike are not among them (no shared module).
APP_DIRS = ("launcher", "mode-explore", "mode-voice", "mode-remote-control")


def code_only(text):
    """The source without comments, so a mention in prose never satisfies a check."""
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def activities():
    found = []
    for app in APP_DIRS:
        for path in sorted((REPO / app / "src").rglob("*.java")):
            if re.search(r"\bextends\s+Activity\b", code_only(path.read_text())):
                found.append(path)
    return found


class VolumeKeysHarnessTest(unittest.TestCase):
    """One harness run, one assertion per scenario."""

    SCENARIOS = (
        "volume_up_raises_music_without_ui",
        "volume_down_lowers_music_without_ui",
        "mute_toggles_music_mute",
        "volume_mute_toggles_music_mute",
        "held_volume_key_keeps_stepping",
        "held_mute_key_toggles_once",
        "other_keys_pass_through",
        "no_audio_service_still_consumes_the_key",
        "adjustment_for_maps_each_key",
        "attach_makes_music_the_volume_stream",
        "silenced_when_muted_or_all_the_way_down",
        "toggle_mute_toggles_music_mute",
    )

    @classmethod
    def setUpClass(cls):
        jdk = jvm_harness.find_jdk()
        if jdk is None:
            raise unittest.SkipTest("no JDK (javac + java) found")
        cls._td = tempfile.TemporaryDirectory(prefix="volume_keys_harness_")
        out = cls._td.name
        c = subprocess.run(jvm_harness.javac_cmd(jdk[0], out, [HARNESS_MAIN, VOLUME_KEYS] + STUBS, [HARNESS]),
                           capture_output=True, text=True)
        cls.compiled = c.returncode == 0
        cls.compile_output = (c.stdout + c.stderr)[-3000:]
        cls.results = {}
        cls.run_output = ""
        if cls.compiled:
            r = subprocess.run([jdk[1], "-cp", out, "com.miko3.shared.VolumeKeysHarness"],
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


jvm_harness.add_scenario_tests(VolumeKeysHarnessTest)


class StubConstantsMatchThePlatformTest(unittest.TestCase):
    """The stubs' constants are the platform's, or the harness proves nothing."""

    def _android_jar(self):
        sys.path.insert(0, str(SCRIPTS))
        try:
            import build_common
            sdk = build_common.find_sdk(None)
            platform = build_common.PLATFORM
        except Exception:
            sdk = None
        finally:
            sys.path.remove(str(SCRIPTS))
        jar = sdk / "platforms" / platform / "android.jar" if sdk else None
        if jar is None or not jar.exists():
            self.skipTest("no Android platform jar")
        return jar

    def _platform_constants(self, jar, cls):
        jdk = jvm_harness.find_jdk()
        if jdk is None:
            self.skipTest("no JDK")
        javap = str(Path(jdk[0]).with_name("javap"))
        with tempfile.TemporaryDirectory() as td:
            with zipfile.ZipFile(jar) as z:
                z.extract(cls.replace(".", "/") + ".class", td)
            out = subprocess.run([javap, "-constants", "-cp", td, cls], capture_output=True, text=True).stdout
        return dict(re.findall(r"static final int (\w+) = (-?\d+);", out))

    def test_stub_constants(self):
        jar = self._android_jar()
        for stub, cls in ((STUBS[2], "android.media.AudioManager"), (STUBS[3], "android.view.KeyEvent")):
            platform = self._platform_constants(jar, cls)
            ours = dict(re.findall(r"static final int (\w+) = (-?\d+);", stub.read_text()))
            for name, value in ours.items():
                self.assertEqual(platform.get(name), value, f"{cls}.{name}")


class EveryActivityRoutesItsKeysTest(unittest.TestCase):
    """Each activity that can be in front hands its keys to VolumeKeys before anything else sees them."""

    def test_the_known_activities_are_found(self):
        names = {p.relative_to(REPO).parts[0] for p in activities()}
        self.assertEqual(names, set(APP_DIRS))

    def test_dispatch_key_event_goes_through_the_helper_first(self):
        for path in activities():
            src = code_only(path.read_text())
            m = re.search(r"public boolean dispatchKeyEvent\(KeyEvent (\w+)\) \{(.*?)\n    \}", src, re.S)
            self.assertIsNotNone(m, f"{path.name} ({path.parent.name}) has no dispatchKeyEvent")
            body = m.group(2)
            self.assertIn(f"VolumeKeys.dispatch(this, {m.group(1)})", body, path)
            self.assertIn("super.dispatchKeyEvent(", body, path)
            self.assertLess(body.index("VolumeKeys.dispatch("), body.index("super.dispatchKeyEvent("), path)

    def test_on_create_makes_music_the_volume_stream(self):
        for path in activities():
            src = code_only(path.read_text())
            m = re.search(r"protected void onCreate\(Bundle \w+\) \{(.*?)\n    \}", src, re.S)
            self.assertIsNotNone(m, path)
            self.assertIn("VolumeKeys.attach(this)", m.group(1), path)

    def test_no_activity_adjusts_the_volume_itself(self):
        for path in activities():
            src = code_only(path.read_text())
            for word in ("adjustStreamVolume", "ADJUST_TOGGLE_MUTE", "KEYCODE_VOLUME", "setVolumeControlStream"):
                self.assertNotIn(word, src, f"{path}: the helper owns {word}")

    def test_the_helper_shows_no_system_volume_ui(self):
        src = code_only(VOLUME_KEYS.read_text())
        self.assertNotIn("FLAG_SHOW_UI", src)
        self.assertRegex(src, r"adjustStreamVolume\(AudioManager\.STREAM_MUSIC, \w+, 0\)")


class ExploreHearsTheMuteTest(unittest.TestCase):
    """The speaker's mute reaches the brain as do-not-disturb (owner 2026-10-02)."""

    def test_the_loop_reports_mute_changes_to_the_brain(self):
        src = code_only((EXPLORE / "ExploreLoop.java").read_text())
        self.assertIn("interface Mute", src)
        self.assertIn("void setMute(Mute", src)
        run = re.search(r"private void runBrain\(\) \{(.*?)\n    \}", src, re.S).group(1)
        self.assertIn("mute.muted()", run)
        self.assertIn("brain.setMuted(", run)

    def test_mode_app_gives_the_loop_the_speakers_mute(self):
        src = code_only((EXPLORE / "ModeApp.java").read_text())
        self.assertIn("loop.setMute(", src)
        self.assertIn("VolumeKeys.silenced(", src)
        self.assertLess(src.index("loop.setMute("), src.index("loop.start()"))

    def test_mute_hushes_the_clips_and_the_sound_skips_new_ones(self):
        src = code_only((EXPLORE / "ModeApp.java").read_text())
        self.assertIn(".hush()", src)
        self.assertIn("void hush()", code_only((EXPLORE / "ClipPlayer.java").read_text()))
        sound = re.search(r"ExploreBrain\.Sound sound = new ExploreBrain\.Sound\(\) \{(.*?)\n    \};", src, re.S)
        self.assertIsNotNone(sound)
        self.assertEqual(sound.group(1).count("speakerMuted"), 3, "each of the three clip calls checks the mute")

    def test_the_brain_logs_the_two_lines(self):
        src = (EXPLORE / "ExploreBrain.java").read_text()
        self.assertIn('"muted: do not disturb (no remarks, calls get a glance)"', src)
        self.assertIn('"unmuted: talking again"', src)


if __name__ == "__main__":
    unittest.main()
