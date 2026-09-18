#!/usr/bin/env python3
"""Conformance tests for the shared module's RFC 6455 code (U2).

The robot has no Android unit-test harness, so the shared Java classes are
compiled for the host JVM (with stub android.util.Log / android.content.Context
from fixtures/ws_harness/stubs) and exercised over loopback:

- ClientConformanceTest drives com.miko3.shared.WebSocketClient against
  relay/tests/lane_echo_server.py, one JVM per scenario
  (fixtures/ws_harness/src/WsClientHarness.java does the checking).
- DriveWebSocketRegressionTest serves the remote-control mode's "/drive-ws"
  read-loop shape from RoutingHttpServer and pokes it with a raw Python
  client, proving the server side of WebSocketConnection is unchanged:
  unmasked server frames, pings answered, readText() null on close.

The device run of the same scenarios is the plan's Verification Contract
gate, not this file. Skips cleanly without a JDK that accepts -source 8 or
without the relay venv (relay/.venv) that runs the echo server.
"""
import atexit
import base64
import os
import queue
import shutil
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import unittest
from pathlib import Path

TESTS = Path(__file__).resolve().parent
if str(TESTS) not in sys.path:
    sys.path.insert(0, str(TESTS))
import jvm_harness  # noqa: E402

REPO = Path(__file__).resolve().parents[2]
SCRIPTS = REPO / "scripts"
HARNESS = SCRIPTS / "tests" / "fixtures" / "ws_harness"
SHARED_SRC = REPO / "shared" / "src"
ECHO_SERVER = REPO / "relay" / "tests" / "lane_echo_server.py"
RELAY_PYTHON = REPO / "relay" / ".venv" / "bin" / "python"

OP_TEXT, OP_BINARY, OP_CLOSE, OP_PING, OP_PONG = 0x1, 0x2, 0x8, 0x9, 0xA


def javac_cmd(javac, out_dir, sources):
    return jvm_harness.javac_cmd(javac, out_dir, sources, [SHARED_SRC, HARNESS / "stubs"])


class HostJvm:
    """Compiles each harness main once per test run, into one temp dir."""
    _tmp = None
    _jdk = None
    _compiled = {}

    @classmethod
    def require(cls):
        if cls._jdk is None:
            jdk = jvm_harness.find_jdk()
            if jdk is None:
                raise unittest.SkipTest("no JDK (javac + java) found")
            cls._tmp = tempfile.mkdtemp(prefix="ws_harness_")
            atexit.register(shutil.rmtree, cls._tmp, True)
            probe = Path(cls._tmp) / "Probe.java"
            probe.write_text("class Probe {}\n")
            r = subprocess.run(javac_cmd(jdk[0], cls._tmp, [probe]), capture_output=True, text=True)
            if r.returncode != 0:
                raise unittest.SkipTest(f"{jdk[0]} cannot compile -source 8: {r.stderr.strip()[:200]}")
            cls._jdk = jdk
        return cls._jdk

    @classmethod
    def compile(cls, main):
        """Returns (ok, output); a failure here is the test's failure, not a skip."""
        if main not in cls._compiled:
            javac, _ = cls.require()
            r = subprocess.run(javac_cmd(javac, cls._tmp, [HARNESS / "src" / f"{main}.java"]),
                               capture_output=True, text=True)
            cls._compiled[main] = (r.returncode == 0, (r.stdout + r.stderr)[-3000:])
        return cls._compiled[main]

    @classmethod
    def java(cls):
        return cls.require()[1]

    @classmethod
    def classpath(cls):
        return cls._tmp


def spawn_with_port(cmd):
    """Starts a server process that prints "LISTENING <port>"; returns (proc, port, lines)."""
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
    lines = queue.Queue()

    def pump():
        for line in proc.stdout:
            lines.put(line.strip())

    threading.Thread(target=pump, daemon=True).start()
    try:
        first = lines.get(timeout=15)
    except queue.Empty:
        proc.kill()
        raise AssertionError(f"{cmd[-1]} never printed LISTENING")
    if not first.startswith("LISTENING "):
        proc.kill()
        raise AssertionError(f"unexpected first line from {cmd}: {first!r}")
    return proc, int(first.split()[1]), lines


# ---- a raw client, for the frames the Java client must never send ----------

def ws_handshake(port, path):
    sock = socket.create_connection(("127.0.0.1", port), timeout=5)
    key = base64.b64encode(os.urandom(16)).decode()
    sock.sendall((f"GET {path} HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nUpgrade: websocket\r\n"
                  f"Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\n"
                  "Sec-WebSocket-Version: 13\r\n\r\n").encode())
    head = b""
    while b"\r\n\r\n" not in head:
        chunk = sock.recv(1)
        if not chunk:
            raise AssertionError(f"handshake closed early: {head!r}")
        head += chunk
    if not head.startswith(b"HTTP/1.1 101"):
        raise AssertionError(f"handshake refused: {head!r}")
    return sock


def send_frame(sock, opcode, payload, mask=True):
    head = bytearray([0x80 | opcode])
    n = len(payload)
    bit = 0x80 if mask else 0
    if n <= 125:
        head.append(bit | n)
    elif n <= 0xFFFF:
        head.append(bit | 126)
        head += struct.pack("!H", n)
    else:
        head.append(bit | 127)
        head += struct.pack("!Q", n)
    if mask:
        key = os.urandom(4)
        head += key
        payload = bytes(c ^ key[i % 4] for i, c in enumerate(payload))
    sock.sendall(bytes(head) + payload)


def recv_exact(sock, n):
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise EOFError("socket closed mid-frame")
        buf += chunk
    return buf


def recv_frame(sock):
    """Returns (fin, opcode, masked, payload)."""
    b0, b1 = recv_exact(sock, 2)
    n = b1 & 0x7F
    if n == 126:
        (n,) = struct.unpack("!H", recv_exact(sock, 2))
    elif n == 127:
        (n,) = struct.unpack("!Q", recv_exact(sock, 8))
    key = recv_exact(sock, 4) if b1 & 0x80 else None
    payload = recv_exact(sock, n)
    if key:
        payload = bytes(c ^ key[i % 4] for i, c in enumerate(payload))
    return bool(b0 & 0x80), b0 & 0x0F, bool(b1 & 0x80), payload


# ---- tests ------------------------------------------------------------------

class ClientConformanceTest(unittest.TestCase):
    """U2 test scenarios for WebSocketClient against the lane echo server."""

    @classmethod
    def setUpClass(cls):
        if not RELAY_PYTHON.exists():
            raise unittest.SkipTest(f"relay venv missing ({RELAY_PYTHON}); it runs the echo server")
        HostJvm.require()
        cls.server, cls.port, _ = spawn_with_port([str(RELAY_PYTHON), str(ECHO_SERVER), "--port", "0"])

    @classmethod
    def tearDownClass(cls):
        cls.server.kill()
        cls.server.wait()
        cls.server.stdout.close()

    def scenario(self, name):
        ok, out = HostJvm.compile("WsClientHarness")
        self.assertTrue(ok, f"harness does not compile against shared/src:\n{out}")
        r = subprocess.run([HostJvm.java(), "-cp", HostJvm.classpath(), "WsClientHarness",
                            name, "127.0.0.1", str(self.port)],
                           capture_output=True, text=True, timeout=90)
        detail = f"stdout:\n{r.stdout[-2000:]}\nstderr:\n{r.stderr[-2000:]}"
        self.assertEqual(r.returncode, 0, detail)
        self.assertIn(f"PASS {name}", r.stdout, detail)

    def test_3528_byte_binary_round_trips_unchanged(self):
        self.scenario("binary")

    def test_text_round_trips_unchanged(self):
        self.scenario("text")

    def test_frame_over_65535_bytes_uses_8_byte_length(self):
        self.scenario("large")

    def test_client_frames_masked_at_every_length_boundary(self):
        self.scenario("masked")

    def test_fragmented_server_messages_reassembled(self):
        self.scenario("fragments")

    def test_abrupt_server_close_reports_link_lost(self):
        self.scenario("abrupt")

    def test_silent_server_under_streaming_reports_link_lost_on_missed_pongs(self):
        self.scenario("silent")

    def test_peer_that_stops_reading_still_reports_link_lost_and_unblocks_writer(self):
        self.scenario("wedged")

    def test_connect_to_closed_port_fails_within_timeout_leaving_no_thread(self):
        self.scenario("refused")

    def test_unanswered_handshake_fails_within_timeout_leaving_no_thread(self):
        self.scenario("handshake-timeout")

    def test_concurrent_sends_from_two_threads_stay_intact(self):
        self.scenario("concurrent")

    def test_server_close_frame_answered_and_reported(self):
        self.scenario("server-close")

    def test_local_close_fires_no_callback_and_stops_threads(self):
        self.scenario("local-close")

    def test_pings_sent_regardless_of_uplink_traffic(self):
        self.scenario("ping-under-traffic")

    def test_echo_server_rejects_unmasked_client_frame(self):
        # The conformance target itself: an unmasked frame gets close 1002, so a
        # client that ever forgot to mask could not pass the scenarios above.
        sock = ws_handshake(self.port, "/lane")
        try:
            send_frame(sock, OP_TEXT, b"hello", mask=True)
            self.assertEqual(recv_frame(sock)[1:], (OP_TEXT, False, b"hello"))
            send_frame(sock, OP_TEXT, b"no mask", mask=False)
            fin, opcode, masked, payload = recv_frame(sock)
            self.assertEqual(opcode, OP_CLOSE)
            self.assertEqual(struct.unpack("!H", payload[:2])[0], 1002)
        finally:
            sock.close()


class DriveWebSocketRegressionTest(unittest.TestCase):
    """The remote-control "/drive-ws" route's server behavior, before and after U2."""

    def setUp(self):
        HostJvm.require()
        ok, out = HostJvm.compile("WsServerHarness")
        self.assertTrue(ok, f"server harness does not compile:\n{out}")
        self.proc, self.port, self.lines = spawn_with_port(
            [HostJvm.java(), "-cp", HostJvm.classpath(), "WsServerHarness"])

    def tearDown(self):
        self.proc.kill()
        self.proc.wait()
        self.proc.stdout.close()

    def expect_line(self, want, timeout=5):
        while True:
            try:
                line = self.lines.get(timeout=timeout)
            except queue.Empty:
                self.fail(f"harness never printed {want}")
            if line == want:
                return
            self.assertNotIn("IOEXCEPTION", line)

    def test_text_ping_binary_and_close_behave_as_before(self):
        sock = ws_handshake(self.port, "/drive-ws?ct=token")
        try:
            send_frame(sock, OP_TEXT, b"drive 20 0")
            self.assertEqual(recv_frame(sock), (True, OP_TEXT, False, b"got:drive 20 0"))
            send_frame(sock, OP_PING, b"p1")
            self.assertEqual(recv_frame(sock), (True, OP_PONG, False, b"p1"))
            # Binary is not part of the drive protocol: readText() skips it.
            send_frame(sock, OP_BINARY, b"\x00\x01\x02")
            send_frame(sock, OP_TEXT, b"stop")
            self.assertEqual(recv_frame(sock), (True, OP_TEXT, False, b"got:stop"))
            send_frame(sock, OP_CLOSE, struct.pack("!H", 1000))
            fin, opcode, masked, _ = recv_frame(sock)
            self.assertEqual((opcode, masked), (OP_CLOSE, False))
            self.expect_line("READTEXT_NULL")
            self.expect_line("HANDLER_FINALLY")
        finally:
            sock.close()

    def test_dropped_connection_ends_read_loop_with_null(self):
        sock = ws_handshake(self.port, "/drive-ws?ct=token")
        send_frame(sock, OP_TEXT, b"drive 0 30")
        self.assertEqual(recv_frame(sock)[3], b"got:drive 0 30")
        sock.close()
        self.expect_line("READTEXT_NULL")
        self.expect_line("HANDLER_FINALLY")

    def test_large_masked_text_frame_read_intact(self):
        sock = ws_handshake(self.port, "/drive-ws?ct=token")
        try:
            body = b"x" * 70000
            send_frame(sock, OP_TEXT, body)
            fin, opcode, masked, payload = recv_frame(sock)
            self.assertEqual((opcode, masked, payload), (OP_TEXT, False, b"got:" + body))
        finally:
            sock.close()


if __name__ == "__main__":
    unittest.main()
