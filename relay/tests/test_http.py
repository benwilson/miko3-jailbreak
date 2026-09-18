"""Tests for relay/relay/http.py, the conversation-log endpoints (plan U5, KTD11).

Listing shows each conversation with its close reason; fetching returns the JSONL; any id
that is not a relay-generated one (dots, separators, encodings) is 404 without a file ever
being opened. Requests are sent raw so no client library normalizes the paths first.
"""
import asyncio
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

RELAY_ROOT = Path(__file__).resolve().parents[1]
if str(RELAY_ROOT) not in sys.path:
    sys.path.insert(0, str(RELAY_ROOT))

from relay import http as relay_http  # noqa: E402
from relay.http import LogHttpServer  # noqa: E402
from relay.logging import ConversationLogs, new_conversation_id  # noqa: E402


async def request(port, path, method="GET"):
    reader, writer = await asyncio.open_connection("127.0.0.1", port)
    writer.write(f"{method} {path} HTTP/1.1\r\nHost: relay\r\nConnection: close\r\n\r\n".encode())
    await writer.drain()
    raw = await asyncio.wait_for(reader.read(), 2.0)
    writer.close()
    head, _, body = raw.partition(b"\r\n\r\n")
    lines = head.decode().split("\r\n")
    status = int(lines[0].split()[1])
    headers = dict(line.split(": ", 1) for line in lines[1:])
    return status, headers, body


class LogHttpTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        root = Path(tempfile.mkdtemp(prefix="relay-http-"))
        self.dir = root / "logs"
        (root / "secret.jsonl").write_text('{"secret": true}\n')
        self.logs = ConversationLogs(self.dir, flush_interval=0.02)
        await self.logs.start()
        self.done = new_conversation_id()
        self.logs.write(self.done, "open", robot="miko-1")
        self.logs.write(self.done, "close", reason="silence")
        await self.logs.stop()
        self.server = LogHttpServer(self.logs, "127.0.0.1", 0)
        await self.server.start()

    async def asyncTearDown(self):
        await self.server.stop()

    async def test_listing_shows_conversation_and_close_reason(self):
        status, headers, body = await request(self.server.port, "/conversations")
        self.assertEqual(status, 200)
        self.assertEqual(headers["Content-Type"], "application/json")
        items = json.loads(body)["conversations"]
        self.assertEqual([(i["id"], i["close_reason"]) for i in items], [(self.done, "silence")])

    async def test_fetch_returns_the_jsonl(self):
        status, headers, body = await request(self.server.port, f"/conversations/{self.done}")
        self.assertEqual(status, 200)
        self.assertEqual(headers["Content-Type"], "application/x-ndjson")
        self.assertEqual(body, (self.dir / f"{self.done}.jsonl").read_bytes())
        self.assertEqual(int(headers["Content-Length"]), len(body))

    async def test_unknown_but_well_formed_id_is_404(self):
        status, _, _ = await request(self.server.port, "/conversations/20990101T000000000-abcdef")
        self.assertEqual(status, 404)

    async def test_traversal_ids_are_404_and_touch_no_file(self):
        bad = ["..", "../secret", "..%2Fsecret", "%2e%2e%2fsecret", "a%2Fb", "..\\secret",
               "/secret", f"{self.done}/../../secret", ".", f"{self.done}.jsonl", "..;"]
        with mock.patch.object(relay_http, "_read_file",
                               side_effect=AssertionError("file read")) as read:
            for tail in bad:
                with self.subTest(tail=tail):
                    status, _, body = await request(self.server.port, f"/conversations/{tail}")
                    self.assertEqual(status, 404)
                    self.assertNotIn(b"secret\": true", body)
            read.assert_not_called()

    async def test_other_paths_and_methods(self):
        self.assertEqual((await request(self.server.port, "/"))[0], 404)
        self.assertEqual((await request(self.server.port, "/etc/passwd"))[0], 404)
        self.assertEqual((await request(self.server.port, "/conversations", "POST"))[0], 405)
        status, _, body = await request(self.server.port, "/conversations", "HEAD")
        self.assertEqual((status, body), (200, b""))

    async def test_garbage_request_does_not_kill_the_server(self):
        reader, writer = await asyncio.open_connection("127.0.0.1", self.server.port)
        writer.write(b"\x00\xff nonsense\r\n\r\n")
        await writer.drain()
        await asyncio.wait_for(reader.read(), 2.0)
        writer.close()
        self.assertEqual((await request(self.server.port, "/conversations"))[0], 200)


if __name__ == "__main__":
    unittest.main()
