#!/usr/bin/env python3
"""Tests for the physical mute button on top of the robot (owner 2026-10-02).

On the robot the button is its own input device, "mutekey" (/dev/input/event2), and
reports a SWITCH (EV_SW, code 0x17 SW_MID_MUTE_KEY), not a key. It has no key layout,
so Android drops it and no app can see it; reading the device needs root. So:

  boot agent (root) -> bootagent/native/miko3-mute-watch.sh reads the raw events
                    -> am broadcast to the launcher's non-exported MuteKeyReceiver
  launcher          -> VolumeKeys.toggleMute: ADJUST_TOGGLE_MUTE on STREAM_MUSIC,
                       which Explore already hears as do-not-disturb.

The watcher is run here on the host, under /bin/sh and dash, against files of raw
struct input_event bytes (24-byte arm64 layout and the 16-byte 32-bit one). The rest
are shape tests of the relay and the receiver.
"""
import base64
import importlib.util
import os
import re
import shutil
import struct
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
WATCHER = REPO / "bootagent" / "native" / "miko3-mute-watch.sh"
ROOTOPS = REPO / "bootagent" / "src" / "com" / "miko3" / "bootagent" / "RootOps.java"
BUILD_PY = REPO / "scripts" / "build-bootagent.py"
LAUNCHER = REPO / "launcher"
RECEIVER = LAUNCHER / "src" / "com" / "miko3" / "launcher" / "MuteKeyReceiver.java"
VOLUME_KEYS = REPO / "shared" / "src" / "com" / "miko3" / "shared" / "VolumeKeys.java"

EV_SYN, EV_KEY, EV_SW = 0, 1, 5
SW_MID_MUTE_KEY = 0x17
T0 = 1790000000  # a realistic CLOCK_REALTIME second, so the 32-bit sec field matters


def event(sec, usec, etype, code, value, size=24):
    if size == 24:
        return struct.pack("<qqHHi", sec, usec, etype, code, value)
    return struct.pack("<iiHHi", sec, usec, etype, code, value)


def press(sec, usec, value, size=24):
    """One switch report as the kernel sends it: the EV_SW event, then its EV_SYN."""
    return event(sec, usec, EV_SW, SW_MID_MUTE_KEY, value, size) + event(sec, usec, EV_SYN, 0, 0, size)


def shells():
    found = ["/bin/sh"]
    for name in ("dash", "mksh", "bash"):
        path = shutil.which(name)
        if path and path not in found:
            found.append(path)
    return found


class WatcherBehaviourTest(unittest.TestCase):
    """The watcher run on raw bytes: each press is relayed once, and every event is logged raw."""

    def run_watcher(self, data, size=24, shell="/bin/sh", device=None):
        with tempfile.TemporaryDirectory() as td:
            path = Path(td) / "event2"
            if device is None:
                path.write_bytes(data)
                device = str(path)
            env = dict(os.environ, MUTE_WATCH_TEST="1", MUTE_DEV=device, MUTE_EVSZ=str(size))
            r = subprocess.run([shell, str(WATCHER)], env=env, capture_output=True, text=True, timeout=30)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        return r.stdout.splitlines()

    def relays(self, lines):
        return [line for line in lines if line.startswith("RELAY ")]

    def each_shell(self, data, size=24):
        for shell in shells():
            with self.subTest(shell=shell, size=size):
                yield self.run_watcher(data, size, shell)

    def test_a_momentary_press_mutes_once(self):
        data = press(T0, 100000, 1) + press(T0, 250000, 0)
        for lines in self.each_shell(data):
            self.assertEqual(len(self.relays(lines)), 1, lines)

    def test_two_momentary_presses_toggle_twice(self):
        data = press(T0, 0, 1) + press(T0, 150000, 0) + press(T0 + 4, 0, 1) + press(T0 + 4, 120000, 0)
        for lines in self.each_shell(data):
            self.assertEqual(len(self.relays(lines)), 2, lines)

    def test_a_latching_switch_toggles_on_each_change(self):
        """1 on one press, 0 on the next one seconds later: both are presses."""
        data = press(T0, 0, 1) + press(T0 + 6, 0, 0) + press(T0 + 9, 500000, 1)
        for lines in self.each_shell(data):
            self.assertEqual(len(self.relays(lines)), 3, lines)

    def test_a_release_just_under_a_second_is_not_a_press(self):
        data = press(T0, 600000, 1) + press(T0 + 1, 500000, 0)  # 900 ms, across a second boundary
        for lines in self.each_shell(data):
            self.assertEqual(len(self.relays(lines)), 1, lines)

    def test_a_release_with_no_press_seen_is_a_press(self):
        """A latching switch already on when the watcher started: its first change is a 0."""
        for lines in self.each_shell(press(T0, 0, 0)):
            self.assertEqual(len(self.relays(lines)), 1, lines)

    def test_the_32_bit_layout_reads_the_same(self):
        data = press(T0, 0, 1, 16) + press(T0, 200000, 0, 16) + press(T0 + 3, 0, 1, 16)
        for lines in self.each_shell(data, size=16):
            self.assertEqual(len(self.relays(lines)), 2, lines)

    def test_other_events_are_logged_raw_but_never_relayed(self):
        data = (event(T0, 0, EV_KEY, 113, 1) + event(T0, 0, EV_SW, 0x0e, 1)
                + event(T0, 0, EV_SYN, 0, 0) + press(T0 + 1, 0, 2))
        for lines in self.each_shell(data):
            self.assertEqual(self.relays(lines), [], lines)
            raw = [line for line in lines if line.startswith("d ") and "raw " in line]
            self.assertEqual(len(raw), 5, lines)
            self.assertIn("raw type=1 code=113 value=1", "\n".join(raw))

    def test_each_switch_event_is_logged_raw_at_debug(self):
        lines = self.run_watcher(press(T0, 250000, 1))
        self.assertTrue(any(line.startswith("d ") and "raw type=5 code=23 value=1" in line
                            and f"t={T0}.250000" in line for line in lines), lines)

    def test_a_press_is_logged_at_info_before_its_relay(self):
        lines = self.run_watcher(press(T0, 0, 1))
        info = [i for i, line in enumerate(lines) if line.startswith("i ") and "mute pressed" in line]
        relay = [i for i, line in enumerate(lines) if line.startswith("RELAY ")]
        self.assertTrue(info and relay and info[0] < relay[0], lines)

    def test_the_relay_is_an_explicit_broadcast_to_the_launcher(self):
        relay = self.relays(self.run_watcher(press(T0, 0, 1)))[0]
        self.assertIn("am broadcast", relay)
        self.assertIn("-n com.miko3.launcher/.MuteKeyReceiver", relay)
        self.assertIn("-a com.miko3.launcher.action.MUTE_KEY", relay)

    def test_a_trailing_partial_event_is_ignored(self):
        lines = self.run_watcher(press(T0, 0, 1) + b"\x01\x02\x03")
        self.assertEqual(len(self.relays(lines)), 1, lines)

    def test_an_absent_device_is_survived(self):
        lines = self.run_watcher(b"", device="/nonexistent/event2")
        self.assertEqual(self.relays(lines), [])
        self.assertTrue(any("absent" in line for line in lines), lines)


class WatcherRobustnessShapeTest(unittest.TestCase):
    """What the host run cannot show: on the robot it never exits and never spins."""

    def setUp(self):
        self.src = WATCHER.read_text()

    def test_every_retry_path_sleeps(self):
        loop = self.src[self.src.index("while true"):]
        self.assertGreaterEqual(len(re.findall(r"\bsleep \d+", loop)), 2, "absent and closed both back off")

    def test_the_event_size_follows_the_abi(self):
        self.assertIn("ro.product.cpu.abi", self.src)
        self.assertRegex(self.src, r"\*64\*\)\s*EVSZ=24")

    def test_it_reads_the_mutekey_device(self):
        self.assertIn("/dev/input/event2", self.src)
        self.assertIn("EV_SW=5", self.src)
        self.assertIn("SW_MID_MUTE_KEY=23", self.src)

    def test_a_failed_broadcast_does_not_stop_the_watcher(self):
        self.assertRegex(self.src, r"\$AM_BROADCAST [^\n]*\|\| say w")


class BootAgentStartsTheWatcherTest(unittest.TestCase):
    """The boot payload materializes the watcher and starts it in the background."""

    def setUp(self):
        self.src = ROOTOPS.read_text()

    def test_payload_embeds_the_watcher(self):
        self.assertIn("@@MUTEWATCH_B64@@", self.src)
        self.assertIn("/data/local/tmp/miko3-mute-watch.sh", self.src)

    def test_watcher_is_detached_and_never_blocks_the_payload(self):
        self.assertRegex(self.src, r'setsid \\"\$M\\" </dev/null >/dev/null 2>&1 &')
        self.assertIn('pkill -f /data/local/tmp/miko3-mute-watch.sh', self.src)

    def test_watcher_starts_after_adb_is_up(self):
        self.assertLess(self.src.index("setprop ctl.restart adbd"), self.src.index('setsid \\"$M\\"'))
        self.assertLess(self.src.index('setsid \\"$M\\"'), self.src.index("done."))

    def test_build_injects_the_watcher(self):
        spec = importlib.util.spec_from_file_location("build_bootagent", BUILD_PY)
        build = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(build)
        text = "a @@NEUTERD_B64@@ b @@MUTEWATCH_B64@@ c"
        out = build.inject_payloads(text, b"\x7fELF", b"#!/system/bin/sh\n")
        self.assertEqual(out, "a " + base64.b64encode(b"\x7fELF").decode() + " b "
                         + base64.b64encode(b"#!/system/bin/sh\n").decode() + " c")
        with self.assertRaises(build.BuildError):
            build.inject_payloads("@@NEUTERD_B64@@ only", b"x", b"y")


class LauncherReceiverTest(unittest.TestCase):
    """Only root (or the system) can reach the receiver, and it toggles the speaker's mute."""

    def test_manifest_receiver_is_not_exported_and_has_no_filter(self):
        manifest = (LAUNCHER / "AndroidManifest.xml").read_text()
        manifest = re.sub(r"<!--.*?-->", "", manifest, flags=re.S)
        m = re.search(r'<receiver android:name="\.MuteKeyReceiver"([^>]*?)(/>|>(.*?)</receiver>)', manifest, re.S)
        self.assertIsNotNone(m, "launcher manifest declares no MuteKeyReceiver")
        self.assertIn('android:exported="false"', m.group(1))
        self.assertNotIn("intent-filter", m.group(0))

    def test_receiver_toggles_through_volume_keys_for_its_action_only(self):
        src = re.sub(r"//[^\n]*", "", re.sub(r"/\*.*?\*/", "", RECEIVER.read_text(), flags=re.S))
        self.assertIn('"com.miko3.launcher.action.MUTE_KEY"', src)
        self.assertRegex(src, r"if \(!ACTION\.equals\(\w+\.getAction\(\)\)\)")
        self.assertIn("VolumeKeys.toggleMute(", src)
        self.assertNotIn("adjustStreamVolume", src, "VolumeKeys owns the adjustment")

    def test_volume_keys_has_one_toggle(self):
        src = VOLUME_KEYS.read_text()
        self.assertIn("public static void toggleMute(Context context)", src)


if __name__ == "__main__":
    unittest.main()
