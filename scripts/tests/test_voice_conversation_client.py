#!/usr/bin/env python3
"""Host-JVM integration tests for the voice mode's ConversationClient (U8).

The robot has no Android unit-test harness, so the real Java ConversationClient is
compiled for the host JVM (it touches no android.*; the shared WebSocketClient's
android.util.Log comes from fixtures/ws_harness/stubs) and driven by
fixtures/voice_client_harness/src/.../VoiceClientHarness.java, which stands in for
VoiceEngine with a fake microphone and speaker. Each test runs it against a real relay
process over loopback:

- relay/tests/relay_stub.py for the bring-up order (link, hello, welcome; wake to
  conv.ready; reply audio played; flush), commands (AE8), model-down status, a missing or
  changed relay address;
- fixtures/voice_client_harness/lane_fixture.py for what the real relay never does (a
  conv.ready after the robot gave up, a message type from the future);
- the full relay (relay.main) pointed at relay/tests/fake_model_server.py, run through
  fixtures/voice_client_harness/fake_model_launcher.py, for uplink order, reply playback
  reports, the sleep word, the farewell cap, the ready timeout and losing the relay.

The on-device scenarios (wake from across the room, echo, barge-in by voice, Wi-Fi loss,
exit mid-reply, underruns over five minutes) are U8's device QA, not this file. Skips
cleanly without a JDK that accepts -source 8 or without the relay venv (relay/.venv).
"""
import atexit
import json
import os
import re
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SCRIPTS = REPO / "scripts"
FIXTURE = SCRIPTS / "tests" / "fixtures" / "voice_client_harness"
HARNESS_MAIN = FIXTURE / "src" / "com" / "miko3" / "mode" / "voice" / "VoiceClientHarness.java"
LOG_STUBS = SCRIPTS / "tests" / "fixtures" / "ws_harness" / "stubs"
VOICE_SRC = REPO / "mode-voice" / "src"
SHARED_SRC = REPO / "shared" / "src"
RELAY = REPO / "relay"
RELAY_PYTHON = RELAY / ".venv" / "bin" / "python"
RELAY_STUB = RELAY / "tests" / "relay_stub.py"
MODEL_LAUNCHER = FIXTURE / "fake_model_launcher.py"
LANE_FIXTURE = FIXTURE / "lane_fixture.py"

REPLY_CHUNK_BYTES = 3528
TONE_BYTES_PER_SECOND = 22050 * 2

# Short timings so the suite runs in about a minute; the defaults are the plan's
# (2 s / 30 s backoff, 3.5 s ready timeout).
FAST = {"backoff_base_ms": "250", "backoff_cap_ms": "1000", "connect_ms": "2000"}


def find_jdk():
    """(javac, java) from the JDK build_common picks for the APK builds, else PATH."""
    sys.path.insert(0, str(SCRIPTS))
    try:
        import build_common
        home = build_common.java_home()
    except Exception:
        home = ""
    finally:
        sys.path.remove(str(SCRIPTS))
    if home and (Path(home) / "bin" / "javac").exists():
        return str(Path(home) / "bin" / "javac"), str(Path(home) / "bin" / "java")
    javac, java = shutil.which("javac"), shutil.which("java")
    return (javac, java) if javac and java else None


class Harness:
    """Compiles VoiceClientHarness (and, through -sourcepath, the real client) once."""
    _java = None
    _classes = None
    _error = None

    @classmethod
    def require(cls):
        if cls._java is not None:
            return
        if cls._error is not None:
            raise AssertionError(cls._error)
        if not RELAY_PYTHON.exists():
            raise unittest.SkipTest(f"relay venv missing ({RELAY_PYTHON})")
        jdk = find_jdk()
        if jdk is None:
            raise unittest.SkipTest("no JDK (javac + java) found")
        out = tempfile.mkdtemp(prefix="voice_client_harness_")
        atexit.register(shutil.rmtree, out, True)
        probe = Path(out) / "Probe.java"
        probe.write_text("class Probe {}\n")
        base = [jdk[0], "-source", "8", "-target", "8", "-encoding", "UTF-8", "-Xlint:-options"]
        if subprocess.run(base + ["-d", out, str(probe)], capture_output=True).returncode != 0:
            raise unittest.SkipTest(f"{jdk[0]} cannot compile -source 8")
        sourcepath = os.pathsep.join(str(p) for p in (FIXTURE / "src", VOICE_SRC, SHARED_SRC, LOG_STUBS))
        r = subprocess.run(base + ["-sourcepath", sourcepath, "-d", out, str(HARNESS_MAIN)],
                           capture_output=True, text=True)
        if r.returncode != 0:
            cls._error = f"harness does not compile:\n{(r.stdout + r.stderr)[-3000:]}"
            raise AssertionError(cls._error)
        cls._java, cls._classes = jdk[1], out

    @classmethod
    def cmd(cls, **opts):
        cls.require()
        return [cls._java, "-cp", cls._classes, "com.miko3.mode.voice.VoiceClientHarness"] + [
            f"{k}={v}" for k, v in opts.items()]


class Proc:
    """A child process whose merged output is collected line by line; expect() waits for
    a line matching a pattern, scanning forward from where the last expect() stopped."""

    def __init__(self, name, cmd, cwd=None):
        self.name = name
        self.lines = []
        self.cursor = 0
        self.cond = threading.Condition()
        self.proc = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                     stderr=subprocess.STDOUT, text=True, bufsize=1, cwd=cwd)
        threading.Thread(target=self._pump, daemon=True).start()

    def _pump(self):
        for line in self.proc.stdout:
            with self.cond:
                self.lines.append((time.monotonic(), line.rstrip("\n")))
                self.cond.notify_all()
        with self.cond:
            self.cond.notify_all()

    def expect(self, pattern, timeout=8.0):
        """The match object of the first matching line after the cursor (which then moves
        past it), or an assertion error carrying the output so far."""
        regex = re.compile(pattern)
        deadline = time.monotonic() + timeout
        with self.cond:
            while True:
                while self.cursor < len(self.lines):
                    t, line = self.lines[self.cursor]
                    self.cursor += 1
                    m = regex.search(line)
                    if m:
                        self.last_time = t
                        return m
                left = deadline - time.monotonic()
                if left <= 0 or (self.proc.poll() is not None and self.cursor >= len(self.lines)):
                    raise AssertionError(f"{self.name}: no line matching {pattern!r} within "
                                         f"{timeout} s. Output:\n{self.tail()}")
                self.cond.wait(min(left, 0.2))

    def seen(self, pattern, start=0, end=None):
        """Matching lines among lines[start:end], without moving the cursor."""
        regex = re.compile(pattern)
        with self.cond:
            return [line for _, line in self.lines[start:end] if regex.search(line)]

    def mark(self):
        with self.cond:
            return len(self.lines)

    def send(self, text):
        self.proc.stdin.write(text + "\n")
        self.proc.stdin.flush()

    def tail(self, n=80):
        with self.cond:
            return "\n".join(line for _, line in self.lines[-n:])

    def stop(self, sig=signal.SIGTERM):
        if self.proc.poll() is None:
            self.proc.send_signal(sig)
            try:
                self.proc.wait(5)
            except subprocess.TimeoutExpired:
                self.proc.kill()
                self.proc.wait()


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class ClientTestCase(unittest.TestCase):
    def setUp(self):
        Harness.require()
        self.procs = []
        self.tmp = tempfile.mkdtemp(prefix="voice_client_test_")

    def tearDown(self):
        for p in reversed(self.procs):
            p.stop(signal.SIGKILL if p.name != "harness" else signal.SIGTERM)
            p.proc.stdout.close()
            p.proc.stdin.close()
        shutil.rmtree(self.tmp, True)

    def start(self, name, cmd, cwd=None):
        p = Proc(name, cmd, cwd)
        self.procs.append(p)
        return p

    def stub(self, *args, port=None, name="stub"):
        port = port or free_port()
        p = self.start(name, [str(RELAY_PYTHON), str(RELAY_STUB), "--host", "127.0.0.1",
                              "--port", str(port)] + list(args))
        p.expect(r"^LISTENING ")
        p.port = port
        return p

    def harness(self, address, **opts):
        merged = dict(FAST)
        merged.update(opts)
        merged["address"] = address
        return self.start("harness", Harness.cmd(**merged))

    def full_relay(self, script, *args, lane_port=None):
        model = self.start("model", [str(RELAY_PYTHON), str(MODEL_LAUNCHER), "--script", script])
        model.port = int(model.expect(r"^LISTENING (\d+)").group(1))
        relay = self.relay_main(model.port, lane_port or free_port(), *args)
        return model, relay

    def relay_main(self, model_port, lane_port, *args, name="relay"):
        relay = self.start(name, [str(RELAY_PYTHON), "-m", "relay.main", "--model-host", "127.0.0.1",
                                  "--model-port", str(model_port), "--lane-host", "127.0.0.1",
                                  "--lane-port", str(lane_port), "--http-host", "127.0.0.1",
                                  "--http-port", "0", "--log-dir", self.tmp,
                                  "--probe-interval", "0.5"] + list(args), cwd=str(RELAY))
        relay.expect(r"^LISTENING lane", timeout=15)
        relay.port = lane_port
        return relay

    def wake_until_conversing(self, h):
        h.send("wake")
        conv = h.expect(r'^LOG I tx \{"type":"conv\.open","id":\d+,"conv":"([^"]+)"').group(1)
        h.expect(r"^STATE connecting")
        h.expect(r"^STATE conversing")
        return conv

    def jsonl_records(self):
        records = []
        for path in Path(self.tmp).glob("*.jsonl"):
            for line in path.read_text().splitlines():
                records.append(json.loads(line))
        return records


class StubBringUpTest(ClientTestCase):
    """U8's execution-note order against relay_stub.py."""

    def test_link_hello_welcome_then_wake_reply_played_with_playback_reports(self):
        stub = self.stub("--tone-seconds", "1.0", "--reply-delay", "0.3")
        h = self.harness(f"127.0.0.1:{stub.port}")
        h.expect(r"^STATE unreachable")  # launched: unreachable until welcome
        hello = json.loads(h.expect(r"^LOG I tx (\{\"type\":\"hello\".*)").group(1))
        self.assertEqual(hello["proto"], 1)
        self.assertEqual((hello["mic_rate"], hello["speaker_rate"]), (16000, 22050))
        self.assertEqual(hello["capabilities"], [])
        self.assertEqual(hello["turn_taking"], "interruptible")
        self.assertIsNone(hello["conv"])
        h.expect(r"^LOG I rx .*\"type\":\"welcome\"")
        h.expect(r"^STATE listening")
        stub.expect(r"<- hello")
        time.sleep(1.0)  # the fake mic has been feeding the client all along

        conv = self.wake_until_conversing(h)
        self.assertEqual(len(h.seen(r"^AUDIO open-player")), 1)  # created at conv.ready
        h.expect(r"^AUDIO reply \d+")
        h.expect(r"^AUDIO playing")
        playing = json.loads(h.expect(r"^LOG I tx (\{\"type\":\"playback\".*\"state\":\"playing\".*)").group(1))
        h.expect(r"^STATE speaking")
        self.assertEqual(playing["conv"], conv)
        self.assertGreaterEqual(playing["first_chunk_to_play_ms"], 0)
        played = int(h.expect(r"^AUDIO idle played=(\d+)").group(1))
        self.assertGreaterEqual(played, TONE_BYTES_PER_SECOND)
        self.assertLessEqual(played, TONE_BYTES_PER_SECOND + REPLY_CHUNK_BYTES)
        idle = json.loads(h.expect(r"^LOG I tx (\{\"type\":\"playback\".*\"state\":\"idle\".*)").group(1))
        self.assertEqual(idle["conv"], conv)
        h.expect(r"^STATE conversing")

        # AE1 on the lane: no uplink before conv.open, uplink after it.
        stub.expect(r"<- conv\.open")
        stub.expect(r"<- uplink \d+ bytes")
        stub.expect(r"<- playback .*'state': 'idle'")
        opened = next(i for i, (_, line) in enumerate(stub.lines) if "<- conv.open" in line)
        self.assertEqual(stub.seen(r"uplink", 0, opened), [], "audio left the robot before conv.open")
        self.assertEqual(stub.seen(r"uplink frame dropped"), [])

    def test_flush_drops_rest_of_reply_and_next_reply_plays_whole(self):
        stub = self.stub("--tone-seconds", "3.0", "--reply-delay", "0.2", "--flush-after", "0.8")
        h = self.harness(f"127.0.0.1:{stub.port}")
        h.expect(r"^STATE listening")
        self.wake_until_conversing(h)
        h.expect(r"^AUDIO playing")
        h.expect(r"^LOG I rx .*\"type\":\"audio\.flush\"")
        h.expect(r"^AUDIO flush dropped=\d+")
        first = int(h.expect(r"^AUDIO idle played=(\d+)").group(1))
        self.assertLess(first, 3 * TONE_BYTES_PER_SECOND - 4 * REPLY_CHUNK_BYTES,
                        "the flushed reply played on")
        h.expect(r"^LOG I tx .*\"state\":\"idle\"")
        stub.send("reply")
        h.expect(r"^AUDIO reply \d+")
        total = int(h.expect(r"^AUDIO idle played=(\d+)", timeout=10).group(1))
        self.assertGreaterEqual(total - first, 3 * TONE_BYTES_PER_SECOND)

    def test_unknown_command_answered_unsupported_and_conversation_continues(self):
        # AE8: the stub sends cmd{action: dance} right after starting the reply.
        stub = self.stub("--tone-seconds", "1.0", "--reply-delay", "0.2", "--unknown-cmd", "dance")
        h = self.harness(f"127.0.0.1:{stub.port}")
        h.expect(r"^STATE listening")
        conv = self.wake_until_conversing(h)
        cmd = json.loads(h.expect(r"^LOG I rx (\{.*\"type\":\"cmd\".*)").group(1))
        result = json.loads(h.expect(r"^LOG I tx (\{\"type\":\"cmd\.result\".*)").group(1))
        self.assertEqual((result["re"], result["status"], result["conv"]), (cmd["id"], "unsupported", conv))
        stub.expect(r"<- cmd\.result .*'status': 'unsupported'")
        h.expect(r"^AUDIO idle played=\d+")  # the reply still played out
        time.sleep(0.3)
        self.assertEqual(h.seen(r"conv\.close"), [])
        self.assertEqual(h.seen(r"^STATE ")[-1].split()[1], "conversing")

    def test_model_down_is_unreachable_wake_ignored_until_status(self):
        stub = self.stub("--model-down")
        h = self.harness(f"127.0.0.1:{stub.port}")
        h.expect(r"^LOG I rx .*\"type\":\"welcome\"")
        h.expect(r"^STATE unreachable \| .*model")
        h.send("wake")
        h.expect(r"^LOG I wake ignored")
        h.expect(r"^AUDIO listen")
        stub.send("status 1")
        h.expect(r"^STATE listening")
        self.assertEqual(h.seen(r"conv\.open"), [])
        self.wake_until_conversing(h)
        stub.send("close silence")
        h.expect(r"^AUDIO listen")
        h.expect(r"^STATE listening")
        stub.send("status 0")
        h.expect(r"^STATE unreachable")

    def test_no_relay_address_is_unreachable_until_one_is_saved(self):
        stub = self.stub()
        h = self.harness("")
        h.expect(r"^STATE unreachable \| no relay address")
        time.sleep(1.0)
        self.assertEqual(h.seen(r"^LOG I connecting to"), [])
        self.assertEqual(len(h.seen(r"no relay address")), 2, "one log line and one state, no retry loop")
        h.send(f"address 127.0.0.1:{stub.port}")
        h.expect(r"^STATE listening")
        stub.expect(r"<- hello")

    def test_address_change_ends_conversation_and_reconnects_with_reset_backoff(self):
        a = self.stub(name="stub-a")
        b = self.stub(name="stub-b")
        h = self.harness(f"127.0.0.1:{a.port}", backoff_cap_ms="4000")
        h.expect(r"^STATE listening")
        self.wake_until_conversing(h)
        a.expect(r"<- conv\.open")
        h.send(f"address 127.0.0.1:{b.port}")
        a.expect(r"<- conv\.close .*robot_request")
        h.expect(r"^AUDIO close-player")
        h.expect(r"^STATE listening")
        b.expect(r"<- hello")

        # Lose B, let the backoff climb to its cap, then point back at A: the saved
        # change connects at once instead of after the 4 s the backoff had reached.
        b.stop(signal.SIGKILL)
        h.expect(r"^STATE unreachable")
        h.expect(r"retrying in 4000 ms", timeout=10)
        mark = h.mark()
        t0 = time.monotonic()
        h.send(f"address 127.0.0.1:{a.port}")
        h.expect(r"^STATE listening")
        self.assertLess(time.monotonic() - t0, 1.5, h.tail())
        self.assertEqual(h.seen(r"retrying in", mark), [])


class LaneFixtureTest(ClientTestCase):
    """Relay behaviors the real relay never shows, from lane_fixture.py."""

    def fixture(self, *args):
        p = self.start("fixture", [str(RELAY_PYTHON), str(LANE_FIXTURE)] + list(args))
        p.port = int(p.expect(r"^LISTENING 127\.0\.0\.1:(\d+)").group(1))
        return p

    def test_late_conv_ready_ignored_and_next_wake_works(self):
        fx = self.fixture("--ready-delay", "1.2")
        h = self.harness(f"127.0.0.1:{fx.port}", ready_ms="500")
        h.expect(r"^STATE listening")
        h.send("wake")
        conv = h.expect(r'^LOG I tx \{"type":"conv\.open","id":\d+,"conv":"([^"]+)"').group(1)
        h.expect(r"^STATE connecting")
        close = json.loads(h.expect(r"^LOG I tx (\{\"type\":\"conv\.close\".*)").group(1))
        self.assertEqual((close["conv"], close["reason"]), (conv, "robot_request"))
        h.expect(r"^STATE unreachable")
        h.expect(r"^STATE listening")  # the fixture's status{model_ok: true}
        h.expect(r"^LOG I rx .*\"type\":\"conv\.ready\"")
        h.expect(r"^LOG I ignoring conv\.ready")
        fx.expect(r"-> conv\.ready .*already closed")
        self.assertEqual(h.seen(r"^STATE ")[-1].split()[1], "listening")
        self.assertEqual(h.seen(r"^AUDIO open-player"), [])
        self.assertEqual(fx.seen(r"<- uplink"), [], "the pre-ready buffer was discarded, not sent")
        self.wake_until_conversing(h)  # no restart needed

    def test_unknown_message_type_answered_unsupported(self):
        fx = self.fixture("--unknown-type", "dance.party")
        h = self.harness(f"127.0.0.1:{fx.port}")
        h.expect(r"^STATE listening")
        conv = self.wake_until_conversing(h)
        result = json.loads(h.expect(r"^LOG I tx (\{\"type\":\"cmd\.result\".*)").group(1))
        self.assertEqual((result["status"], result["conv"]), ("unsupported", conv))
        fx.expect(r"cmd\.result .*unsupported")
        h.expect(r"^AUDIO idle played=\d+")
        self.assertEqual(h.seen(r"conv\.close"), [])


class FullRelayTest(ClientTestCase):
    """The real relay (relay.main) against the fake model server."""

    def test_uplink_in_order_reply_reports_and_sleep_word_back_to_listening(self):
        model, relay = self.full_relay("chat")
        h = self.harness(f"127.0.0.1:{relay.port}")
        h.expect(r"^STATE listening", timeout=10)
        # conv.ready ~0.8 s late, so the wake's pre-ready buffer has something in it.
        model.send("delay 0.8")
        model.expect(r"^DELAY")
        time.sleep(0.3)
        h.send("wake")
        wake_seq = int(h.expect(r"^WAKE mic_seq=(\d+)").group(1))
        conv = h.expect(r'^LOG I tx \{"type":"conv\.open","id":\d+,"conv":"([^"]+)"').group(1)
        buffered = int(h.expect(r"^LOG I pre-ready buffer: (\d+) chunks sent", timeout=5).group(1))
        h.expect(r"^STATE conversing")
        self.assertGreaterEqual(buffered, 5)
        model.send("delay 0")
        # First reply, then the farewell after "goodbye miko".
        h.expect(r"^AUDIO playing")
        h.expect(r"^AUDIO idle played=\d+", timeout=5)
        h.expect(r"^AUDIO playing", timeout=5)
        h.expect(r"^AUDIO idle played=\d+", timeout=5)
        close = json.loads(h.expect(r"^LOG I rx (\{\"type\":\"conv\.close\".*)", timeout=5).group(1))
        self.assertEqual((close["conv"], close["reason"]), (conv, "sleep_word"))
        h.expect(r"^AUDIO listen")
        h.expect(r"^STATE listening")

        session = model.expect(r"^SESSION 1 .*seqs=([\d,]*)", timeout=5).group(1)
        seqs = [int(s) for s in session.split(",") if s]
        self.assertGreater(len(seqs), buffered)
        self.assertLessEqual(abs(seqs[0] - wake_seq), 1, f"first uplink seq {seqs[0]}, wake at {wake_seq}")
        self.assertEqual(seqs, list(range(seqs[0], seqs[0] + len(seqs))),
                         "uplink reached the model out of order or with gaps")

        time.sleep(1.5)  # the relay's JSONL writer flushes once a second
        states = [r["msg"]["state"] for r in self.jsonl_records()
                  if r.get("ev") == "robot" and r.get("msg", {}).get("type") == "playback"
                  and r["msg"].get("conv") == conv]
        self.assertEqual(states, ["playing", "idle", "playing", "idle"])

        self.wake_until_conversing(h)  # the spotter was resumed: the next wake works

    def test_ready_timeout_closes_then_forced_status_and_next_wake(self):
        model, relay = self.full_relay("quiet")
        h = self.harness(f"127.0.0.1:{relay.port}", ready_ms="700")
        h.expect(r"^STATE listening", timeout=10)
        model.send("delay 1.5")
        model.expect(r"^DELAY")
        h.send("wake")
        conv = h.expect(r'^LOG I tx \{"type":"conv\.open","id":\d+,"conv":"([^"]+)"').group(1)
        close = json.loads(h.expect(r"^LOG I tx (\{\"type\":\"conv\.close\".*)").group(1))
        self.assertEqual((close["conv"], close["reason"]), (conv, "robot_request"))
        h.expect(r"^STATE unreachable")
        model.send("delay 0")
        h.expect(r"^LOG I rx .*\"type\":\"status\".*\"model_ok\":true", timeout=6)
        h.expect(r"^STATE listening")
        self.assertEqual(h.seen(r"^AUDIO open-player"), [])
        self.wake_until_conversing(h)

    def test_farewell_cut_by_drain_cap_plays_out_in_closing(self):
        model, relay = self.full_relay("long_farewell", "--drain-cap", "1.2")
        h = self.harness(f"127.0.0.1:{relay.port}")
        h.expect(r"^STATE listening", timeout=10)
        conv = self.wake_until_conversing(h)
        h.expect(r"^AUDIO playing", timeout=5)
        close = json.loads(h.expect(r"^LOG I rx (\{\"type\":\"conv\.close\".*)", timeout=5).group(1))
        self.assertEqual((close["conv"], close["reason"]), (conv, "farewell_timeout"))
        h.expect(r"^STATE closing")
        h.expect(r"^AUDIO idle played=\d+")
        idle = json.loads(h.expect(r"^LOG I tx (\{\"type\":\"playback\".*\"state\":\"idle\".*)").group(1))
        self.assertEqual(idle["conv"], conv)
        h.expect(r"^AUDIO close-player pending=0")
        h.expect(r"^STATE listening")

    def test_relay_lost_mid_reply_then_back_with_welcome(self):
        model, relay = self.full_relay("long_reply")
        h = self.harness(f"127.0.0.1:{relay.port}")
        h.expect(r"^STATE listening", timeout=10)
        self.wake_until_conversing(h)
        h.expect(r"^STATE speaking", timeout=5)
        relay.stop(signal.SIGKILL)
        h.expect(r"^AUDIO close-player")  # the speaker stops with the link (AE10)
        h.expect(r"^AUDIO listen")
        h.expect(r"^STATE unreachable")
        h.expect(r"retrying in \d+ ms")
        self.relay_main(model.port, relay.port, name="relay-2")
        h.expect(r"^LOG I rx .*\"type\":\"welcome\"", timeout=10)
        h.expect(r"^STATE listening")
        self.wake_until_conversing(h)


if __name__ == "__main__":
    unittest.main()
