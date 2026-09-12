#!/usr/bin/env python3
"""Tests for scripts/verify-persistent-adb.py.

Covers the pure assessment helpers, the transport targeting every device command must
carry (KTD10), and the registration-vs-execution split (KTD11). The device verdict
itself is proved by the plan's device proof on the unit, not here — nothing in this
file touches a real device.
"""
import contextlib
import importlib.util
import io
import json
import types
import unittest
from pathlib import Path
from unittest import mock

REPO = Path(__file__).resolve().parents[2]
VERIFY_PY = REPO / "scripts" / "verify-persistent-adb.py"

# A normal boot on the documented unit with both transports healthy and the shadow in place.
# reboot_hex is a shell script's first bytes ("#!/s"), i.e. the no-op shadow, not the real ELF.
HEALTHY_STATE = {
    "bootmode": "normal",
    "boot_completed": "1",
    "uid": "0",
    "adbd": "running",
    "usb_config": "mtp,adb",
    "reboot_hex": " 23 21 2f 73",
    "neuterd_alive": True,
    "watcher_alive": True,
    "pm_path": "package:/data/app/com.miko3.bootagent-abc==/base.apk",
    "wlan0_ip": "10.0.0.5",
    "log_mtime": 9005,
    "now_epoch": 10000,
    "uptime": 1000.0,
    "boot_log_tail": "[boot up=42] miko3 bootagent",
}


def load():
    spec = importlib.util.spec_from_file_location("verify_persistent_adb", VERIFY_PY)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load verify module from {VERIFY_PY}")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


v = load()


class BootClassificationTest(unittest.TestCase):
    def test_factory_mode(self):
        self.assertEqual(v.classify_boot("factory", ""), "factory")

    def test_normal_boot(self):
        self.assertEqual(v.classify_boot("normal", "1"), "normal")

    def test_still_booting(self):
        self.assertEqual(v.classify_boot("normal", ""), "booting")


class AdbDevicesParseTest(unittest.TestCase):
    def test_parses_serials_and_states(self):
        raw = ("List of devices attached\n"
               "MIKO3250XXM3Q0636CB\tdevice\n"
               "10.0.0.5:5555\tunauthorized\n\n")
        self.assertEqual(v.parse_adb_devices(raw),
                         {"MIKO3250XXM3Q0636CB": "device", "10.0.0.5:5555": "unauthorized"})

    def test_empty_listing(self):
        self.assertEqual(v.parse_adb_devices("List of devices attached\n\n"), {})


class RebootMagicTest(unittest.TestCase):
    """KTD11: the ELF read, not a byte-size heuristic, decides whether the shadow holds."""

    def test_elf_magic_means_the_real_binary_is_back(self):
        self.assertTrue(v.parse_reboot_magic(" 7f 45 4c 46\n"))

    def test_shadow_script_is_not_elf(self):
        self.assertFalse(v.parse_reboot_magic(" 23 21 2f 73"))

    def test_unreadable_output_is_not_elf(self):
        self.assertFalse(v.parse_reboot_magic(""))


class BootLogFreshnessTest(unittest.TestCase):
    def test_log_written_this_boot_is_fresh(self):
        # device up 1000s, now 10000 -> boot at 9000; a log written at 9005 is this boot
        self.assertTrue(v.log_is_fresh(9005, 10000, 1000))

    def test_log_from_a_previous_boot_is_stale(self):
        """The regression an 'exists' check cannot catch."""
        self.assertFalse(v.log_is_fresh(5000, 10000, 1000))

    def test_missing_inputs_are_not_fresh(self):
        self.assertFalse(v.log_is_fresh(None, 10000, 1000))
        self.assertFalse(v.log_is_fresh(9005, None, 1000))
        self.assertFalse(v.log_is_fresh(9005, 10000, None))


class RegistrationTest(unittest.TestCase):
    """KTD11: 'registered but the payload failed' is not 'never registered'."""

    def test_registered_and_executed(self):
        self.assertEqual(v.assess_registration("package:/data/app/x==/base.apk", True),
                         (True, True))

    def test_registered_but_payload_failed(self):
        self.assertEqual(v.assess_registration("package:/data/app/x==/base.apk", False),
                         (True, False))

    def test_never_registered(self):
        self.assertEqual(v.assess_registration("", False), (False, False))

    def test_not_registered_even_with_a_fresh_log(self):
        self.assertEqual(v.assess_registration("", True), (False, False))


class UsbAssessmentTest(unittest.TestCase):
    def _assess(self, **overrides):
        kwargs = dict(uid="0", adbd="running", usb_config="mtp,adb", reboot_is_real=False,
                      neuterd_alive=True, watcher_alive=True, auth_state="device")
        kwargs.update(overrides)
        return v.assess_usb(**kwargs)

    def test_all_signals_good_is_up(self):
        up, lines = self._assess()
        self.assertTrue(up, lines)

    def test_unauthorized_is_down_and_named(self):
        """A failed KTD5 key pre-authorization must be visible, not folded into 'no adbd'."""
        up, lines = self._assess(auth_state="unauthorized")
        self.assertFalse(up)
        self.assertTrue(any("unauthorized" in line for line in lines), lines)

    def test_non_root_shell_is_down(self):
        up, _ = self._assess(uid="2000")
        self.assertFalse(up)

    def test_real_reboot_binary_is_down(self):
        up, _ = self._assess(reboot_is_real=True)
        self.assertFalse(up)

    def test_dead_neuterd_is_down_even_with_the_shadow_present(self):
        """The shadow outlives the daemon, so liveness must be asserted separately."""
        up, _ = self._assess(neuterd_alive=False)
        self.assertFalse(up)

    def test_dead_watcher_is_down(self):
        """R5's re-assertion is the watcher; a shadow alone does not satisfy it."""
        up, _ = self._assess(watcher_alive=False)
        self.assertFalse(up)

    def test_usb_config_without_adb_is_down(self):
        up, _ = self._assess(usb_config="mtp")
        self.assertFalse(up)


class WifiAssessmentTest(unittest.TestCase):
    def test_tcp_root_is_up(self):
        up, lines = v.assess_wifi("10.0.0.5", "0", "normal")
        self.assertTrue(up, lines)

    def test_factory_mode_names_factory_mode_not_the_network(self):
        """A false negative here was the reported failure: the reason is factory mode."""
        up, lines = v.assess_wifi("", "", "factory")
        self.assertFalse(up)
        self.assertIn("factory mode has no Wi-Fi", lines[0])

    def test_off_network_is_named_as_such(self):
        up, lines = v.assess_wifi("", "", "normal")
        self.assertFalse(up)
        self.assertIn("not on Wi-Fi", lines[0])

    def test_unreachable_tcp_is_down(self):
        up, _ = v.assess_wifi("10.0.0.5", "", "normal")
        self.assertFalse(up)


class TransportTargetingTest(unittest.TestCase):
    """KTD10: every device command names its transport."""

    def test_tcp_root_targets_the_tcp_transport(self):
        """With USB attached too, an untargeted `adb shell` fails with 'more than one
        device' — which reads as Wi-Fi DOWN while it is up."""
        calls = []

        def fake_adb(*args, serial=None, check=False):
            calls.append((args, serial))
            return types.SimpleNamespace(stdout="0\n", stderr="", returncode=0)

        with mock.patch.object(v, "adb", fake_adb):
            self.assertEqual(v.tcp_root("10.0.0.5"), "0")

        shell_calls = [c for c in calls if c[0] and c[0][0] == "shell"]
        self.assertTrue(shell_calls, f"no shell call recorded: {calls}")
        for args, serial in shell_calls:
            self.assertEqual(serial, "10.0.0.5:5555",
                             "the TCP root re-check must name its transport")

    def test_tcp_root_without_an_ip_does_not_call_adb(self):
        with mock.patch.object(v, "adb") as adb_mock:
            self.assertEqual(v.tcp_root(""), "")
        adb_mock.assert_not_called()

    def test_device_reads_name_the_usb_transport(self):
        seen = []

        def fake_sh(cmd, serial=None):
            seen.append(serial)
            return ""

        with mock.patch.object(v, "sh", fake_sh):
            v.read_device_state("MIKO3250XXM3Q0636CB")

        self.assertTrue(seen)
        for serial in seen:
            self.assertEqual(serial, "MIKO3250XXM3Q0636CB",
                             "every USB device read must name its transport")


class PreconditionTest(unittest.TestCase):
    def test_no_device_raises_rather_than_reporting_a_boot(self):
        empty = types.SimpleNamespace(stdout="List of devices attached\n\n", stderr="",
                                      returncode=0)
        with mock.patch.object(v, "adb", lambda *a, **k: empty):
            with self.assertRaises(v.VerifyError) as ctx:
                v.require_device(v.DEFAULT_USB_SERIAL)
        self.assertIn("no device attached", str(ctx.exception))


class VerifyCompositionTest(unittest.TestCase):
    def _run(self, state, tcp_uid="0"):
        buf = io.StringIO()
        with mock.patch.object(v, "require_device", lambda s: {s: "device"}), \
                mock.patch.object(v, "read_device_state", lambda s: state), \
                mock.patch.object(v, "tcp_root", lambda ip: tcp_uid), \
                mock.patch.object(v, "write_capture", lambda r: REPO / "recon" / "_test.json"), \
                contextlib.redirect_stdout(buf):
            rc = v.verify(json_out=True)
        return rc, json.loads(buf.getvalue())

    def test_both_transports_up_returns_zero(self):
        rc, result = self._run(HEALTHY_STATE)
        self.assertEqual(rc, 0, result)
        self.assertTrue(result["usb"]["up"], result["usb"]["evidence"])
        self.assertTrue(result["wifi"]["up"], result["wifi"]["evidence"])

    def test_registered_but_payload_failed_is_distinguishable(self):
        state = dict(HEALTHY_STATE, log_mtime=5000)  # stale log: not this boot
        _, result = self._run(state)
        self.assertTrue(result["registered"])
        self.assertFalse(result["executed"])

    def test_factory_mode_is_reported_and_fails(self):
        state = dict(HEALTHY_STATE, bootmode="factory", boot_completed="",
                     wlan0_ip="")
        rc, result = self._run(state)
        self.assertEqual(result["boot"], "factory")
        self.assertIn("factory mode has no Wi-Fi", result["wifi"]["evidence"][0])
        self.assertEqual(rc, 1)

    def test_wifi_down_returns_nonzero(self):
        rc, result = self._run(HEALTHY_STATE, tcp_uid="")
        self.assertEqual(rc, 1)
        self.assertFalse(result["wifi"]["up"])


if __name__ == "__main__":
    unittest.main()
