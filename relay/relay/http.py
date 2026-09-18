"""The relay's conversation-log endpoints (plan KTD11), for the QA script (U10) and humans.

    GET /conversations         {"conversations": [{id, started, close_reason, records, bytes}]}
    GET /conversations/<id>    the conversation's JSONL (application/x-ndjson)

HEAD works on both; every other method is 405. An id is taken raw from the path, with no
percent-decoding, and must be a relay-generated one (CONVERSATION_ID); anything else, or
an id with no file, is 404 without opening a file. The file path is the id joined to the
log directory and must resolve back inside it. One request per connection, stdlib only;
file reads run in a worker thread so the event loop never waits on the disk. No auth, like
the lane (LAN only).
"""
import asyncio
import json
import logging
from http import HTTPStatus
from urllib.parse import urlsplit

log = logging.getLogger("relay.http")

DEFAULT_PORT = 8791
PREFIX = "/conversations"
REQUEST_TIMEOUT = 5.0
MAX_HEAD = 16384


def _read_file(path):
    return path.read_bytes()


class LogHttpServer:
    def __init__(self, logs, host="127.0.0.1", port=DEFAULT_PORT):
        self.logs = logs
        self.host = host
        self.port = port
        self._server = None

    async def __aenter__(self):
        await self.start()
        return self

    async def __aexit__(self, *exc):
        await self.stop()

    async def start(self):
        self._server = await asyncio.start_server(self._handle, self.host, self.port)
        self.port = self._server.sockets[0].getsockname()[1]
        log.info("conversation logs on http://%s:%d%s", self.host, self.port, PREFIX)

    async def stop(self):
        if self._server is not None:
            self._server.close()
            await self._server.wait_closed()

    async def _handle(self, reader, writer):
        try:
            try:
                head = await asyncio.wait_for(reader.readuntil(b"\r\n\r\n"), REQUEST_TIMEOUT)
            except (asyncio.IncompleteReadError, asyncio.LimitOverrunError, TimeoutError):
                return
            if len(head) > MAX_HEAD:
                return await self._send(writer, 400, b"request too large\n", "text/plain")
            parts = head.split(b"\r\n", 1)[0].decode("latin-1").split()
            if len(parts) != 3 or not parts[2].startswith("HTTP/"):
                return await self._send(writer, 400, b"bad request\n", "text/plain")
            method, target = parts[0], parts[1]
            status, body, ctype = await self._route(method, target)
            await self._send(writer, status, body, ctype, head_only=method == "HEAD")
        except Exception:  # noqa: BLE001 - one bad request must not take the server down
            log.exception("http request failed")
            try:
                await self._send(writer, 500, b"error\n", "text/plain")
            except Exception:  # noqa: BLE001
                pass
        finally:
            writer.close()

    async def _route(self, method, target):
        path = urlsplit(target).path
        if path not in (PREFIX, PREFIX + "/") and not path.startswith(PREFIX + "/"):
            return 404, b"not found\n", "text/plain"
        if method not in ("GET", "HEAD"):
            return 405, b"method not allowed\n", "text/plain"
        if path in (PREFIX, PREFIX + "/"):
            items = await asyncio.to_thread(self.logs.list)
            return 200, json.dumps({"conversations": items}).encode(), "application/json"
        file = self.logs.path(path[len(PREFIX) + 1:])
        if file is None:
            return 404, b"not found\n", "text/plain"
        try:
            body = await asyncio.to_thread(_read_file, file)
        except (FileNotFoundError, IsADirectoryError, NotADirectoryError):
            return 404, b"not found\n", "text/plain"
        return 200, body, "application/x-ndjson"

    async def _send(self, writer, status, body, ctype, head_only=False):
        head = (f"HTTP/1.1 {status} {HTTPStatus(status).phrase}\r\n"
                f"Content-Type: {ctype}\r\nContent-Length: {len(body)}\r\n"
                f"Connection: close\r\n\r\n").encode()
        writer.write(head if head_only else head + body)
        await writer.drain()
