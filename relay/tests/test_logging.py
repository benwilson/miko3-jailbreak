"""Tests for relay/relay/logging.py, the per-conversation JSONL logs (plan U5, KTD11).

Covers: conversation ids, one file per conversation written by a single writer task
through a queue, audio bytes never reaching the file, a slow disk never stalling the
event loop, the listing the HTTP endpoint serves, and the module name not shadowing the
standard library's `logging` (nor relay/http.py shadowing `http`).
"""
import asyncio
import json
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

RELAY_ROOT = Path(__file__).resolve().parents[1]
if str(RELAY_ROOT) not in sys.path:
    sys.path.insert(0, str(RELAY_ROOT))

from relay.logging import CONVERSATION_ID, ConversationLogs, new_conversation_id  # noqa: E402


def read_jsonl(path):
    return [json.loads(line) for line in Path(path).read_text().splitlines()]


class IdTests(unittest.TestCase):
    def test_ids_match_the_pattern_and_are_unique(self):
        ids = {new_conversation_id() for _ in range(200)}
        self.assertEqual(len(ids), 200)
        for conv_id in ids:
            self.assertRegex(conv_id, CONVERSATION_ID)
        self.assertRegex(new_conversation_id(0), r"^19700101T000000")


class LogsTestCase(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.dir = Path(tempfile.mkdtemp(prefix="relay-logs-"))
        self.logs = ConversationLogs(self.dir, flush_interval=0.05)
        await self.logs.start()

    async def asyncTearDown(self):
        await self.logs.stop()


class ConversationLogsTests(LogsTestCase):
    async def test_records_reach_one_file_per_conversation_in_order(self):
        a, b = new_conversation_id(), new_conversation_id()
        self.logs.write(a, "open", robot="r1")
        self.logs.write(b, "open", robot="r2")
        self.logs.write(a, "close", reason="silence")
        self.assertFalse((self.dir / f"{a}.jsonl").exists())  # nothing written inline
        await asyncio.sleep(0.15)
        records = read_jsonl(self.dir / f"{a}.jsonl")
        self.assertEqual([r["ev"] for r in records], ["open", "close"])
        self.assertEqual(records[1]["reason"], "silence")
        self.assertIn("ts", records[0])
        self.assertEqual(read_jsonl(self.dir / f"{b}.jsonl")[0]["robot"], "r2")

    async def test_audio_bytes_are_never_written(self):
        conv = new_conversation_id()
        self.logs.write(conv, "frame", pcm=b"\x7f" * 3528, nested={"chunk": bytearray(10)})
        await self.logs.stop()
        text = (self.dir / f"{conv}.jsonl").read_text()
        record = json.loads(text)
        self.assertEqual(record["pcm"], {"bytes": 3528})
        self.assertEqual(record["nested"]["chunk"], {"bytes": 10})
        self.assertNotIn("\\u007f", text)

    async def test_stop_flushes_what_is_queued(self):
        conv = new_conversation_id()
        slow = ConversationLogs(self.dir, flush_interval=60)
        await slow.start()
        slow.write(conv, "open")
        await slow.stop()
        self.assertEqual(read_jsonl(self.dir / f"{conv}.jsonl")[0]["ev"], "open")

    async def test_slow_disk_does_not_stall_the_loop(self):
        def slow_write(batch):
            time.sleep(0.4)  # a disk that blocks the writing thread
            ConversationLogs._write_batch(self.logs, batch)
        self.logs._write_batch = slow_write
        conv = new_conversation_id()
        loop = asyncio.get_running_loop()
        worst, last = 0.0, loop.time()
        end = last + 0.6
        while loop.time() < end:
            self.logs.write(conv, "tick")
            await asyncio.sleep(0.005)
            now = loop.time()
            worst, last = max(worst, now - last), now
        self.assertLess(worst, 0.1)

    async def test_queue_overflow_drops_and_counts_instead_of_blocking(self):
        small = ConversationLogs(self.dir, flush_interval=60, max_queue=3)
        conv = new_conversation_id()
        results = [small.write(conv, "x", n=i) for i in range(5)]
        self.assertEqual(results, [True, True, True, False, False])
        self.assertEqual(small.dropped, 2)

    async def test_path_accepts_only_relay_ids_inside_the_directory(self):
        conv = new_conversation_id()
        self.assertEqual(self.logs.path(conv), (self.dir / f"{conv}.jsonl").resolve())
        for bad in ("..", "../x", "a/b", "a\\b", "", ".hidden", "x.jsonl", "a b", "%2e%2e",
                    None, 5):
            with self.subTest(bad=bad):
                self.assertIsNone(self.logs.path(bad))

    async def test_listing_shows_close_reason(self):
        done, open_ = new_conversation_id(), new_conversation_id()
        self.logs.write(done, "open", robot="r1")
        self.logs.write(done, "close", reason="sleep_word")
        self.logs.write(open_, "open", robot="r1")
        await asyncio.sleep(0.15)
        (self.dir / "notes.txt").write_text("not a log")
        listing = {item["id"]: item for item in self.logs.list()}
        self.assertEqual(set(listing), {done, open_})
        self.assertEqual(listing[done]["close_reason"], "sleep_word")
        self.assertIsNone(listing[open_]["close_reason"])
        self.assertIsInstance(listing[done]["started"], float)


class ModuleNameTests(unittest.TestCase):
    """relay/logging.py and relay/http.py must not hide the standard library's modules."""

    def run_python(self, *args, cwd):
        return subprocess.run([sys.executable, *args], cwd=cwd, capture_output=True,
                              text=True, timeout=30)

    def test_stdlib_logging_and_http_still_import_next_to_the_package(self):
        code = ("import relay.logging, relay.http, relay.main, logging, logging.handlers, http;"
                "import http.client;"
                "assert logging.getLogger is not None and relay.logging is not logging;"
                "assert hasattr(http, 'HTTPStatus');"
                "print(logging.__file__)")
        result = self.run_python("-c", code, cwd=RELAY_ROOT)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn(str(RELAY_ROOT / "relay"), result.stdout)

    def test_main_runs_as_a_module_and_as_a_file(self):
        result = self.run_python("-m", "relay.main", "--help", cwd=RELAY_ROOT)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("--model-host", result.stdout)
        # Run by path, Python puts relay/relay/ on sys.path, where logging.py and http.py
        # would shadow the standard library unless main.py guards against it.
        result = self.run_python(str(RELAY_ROOT / "relay" / "main.py"), "--help",
                                 cwd=RELAY_ROOT.parent)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("--model-host", result.stdout)


if __name__ == "__main__":
    unittest.main()
