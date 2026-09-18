"""Per-conversation JSONL logs (plan KTD11), written off the event loop.

One file per conversation, <log_dir>/<conversation id>.jsonl, one JSON object per line:
{"ts": <wall-clock epoch seconds>, "ev": <what happened>, ...fields}. The conversation
engine adds its own relay-monotonic offsets; durations it logs are always measured on one
clock, never computed across the robot's and the relay's.

write() only queues the record: a single writer task hands everything queued to a worker
thread once per flush interval, so a slow disk never stalls robot or model frames. If the
queue is full the record is dropped and counted, never waited for. Bytes values (audio)
are replaced by their length: audio never reaches a log.

This module is relay.logging; inside the package, `import logging` is still the standard
library (absolute imports). Only running a file from relay/relay/ directly would put this
directory on sys.path and hide it; relay/main.py guards against that.
"""
import asyncio
import json
import logging
import re
import secrets
import time
from pathlib import Path

log = logging.getLogger("relay.logs")

CONVERSATION_ID = re.compile(r"^[0-9A-Za-z_-]+$")
FLUSH_INTERVAL = 1.0
MAX_QUEUE = 50000  # records; about ten minutes of a busy conversation


def new_conversation_id(now=None):
    """UTC timestamp to the millisecond plus a short random suffix, e.g.
    20260918T013000123-3fa9c1. Always matches CONVERSATION_ID."""
    now = time.time() if now is None else now
    stamp = time.strftime("%Y%m%dT%H%M%S", time.gmtime(now))
    return f"{stamp}{int(now * 1000) % 1000:03d}-{secrets.token_hex(3)}"


def _clean(value):
    """The value with every bytes-like replaced by {"bytes": length}."""
    if isinstance(value, (bytes, bytearray, memoryview)):
        return {"bytes": len(value)}
    if isinstance(value, dict):
        return {str(k): _clean(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [_clean(v) for v in value]
    return value


class ConversationLogs:
    """The relay's conversation logs.

        logs = ConversationLogs(log_dir)
        await logs.start()
        logs.write(conv_id, "open", robot="miko-1")   # never blocks
        await logs.stop()                             # flushes what is queued
    """

    def __init__(self, log_dir, flush_interval=FLUSH_INTERVAL, max_queue=MAX_QUEUE):
        self.dir = Path(log_dir)
        self.flush_interval = flush_interval
        self.max_queue = max_queue
        self.dropped = 0
        self._pending = []  # (conversation id, JSON line), in write order
        self._writer = None
        self._stopping = None

    async def start(self):
        self.dir.mkdir(parents=True, exist_ok=True)
        self._stopping = asyncio.Event()
        self._writer = asyncio.create_task(self._run(), name="conversation-log-writer")

    async def stop(self):
        if self._writer is None:
            return
        self._stopping.set()
        await self._writer
        self._writer = None

    def write(self, conv_id, ev, **fields):
        """Queue one record; False if it was dropped because the queue is full."""
        if not isinstance(conv_id, str) or not CONVERSATION_ID.fullmatch(conv_id):
            raise ValueError(f"not a conversation id: {conv_id!r}")
        if len(self._pending) >= self.max_queue:
            self.dropped += 1
            if self.dropped == 1 or self.dropped % 1000 == 0:
                log.warning("conversation log queue full: %d records dropped", self.dropped)
            return False
        record = {"ts": round(time.time(), 3), "ev": ev, **fields}
        line = json.dumps(_clean(record), separators=(",", ":"), default=str)
        self._pending.append((conv_id, line))
        return True

    def path(self, conv_id):
        """The log file of a relay-generated id, resolved inside the log directory; None
        for anything else (the file need not exist)."""
        if not isinstance(conv_id, str) or not CONVERSATION_ID.fullmatch(conv_id):
            return None
        base = self.dir.resolve()
        path = (base / f"{conv_id}.jsonl").resolve()
        if path.parent != base:
            return None
        return path

    def list(self):
        """Summaries of every conversation log, oldest first. Blocking: reads the files."""
        items = []
        for path in sorted(self.dir.glob("*.jsonl")):
            conv_id = path.stem
            if self.path(conv_id) != path.resolve():
                continue
            started, close_reason, records = None, None, 0
            try:
                with path.open() as f:
                    for line in f:
                        records += 1
                        try:
                            record = json.loads(line)
                        except ValueError:
                            continue
                        if started is None:
                            started = record.get("ts")
                        if record.get("ev") == "close":
                            close_reason = record.get("reason")
                size = path.stat().st_size
            except OSError:
                continue
            items.append({"id": conv_id, "started": started, "close_reason": close_reason,
                          "records": records, "bytes": size})
        return items

    async def _run(self):
        while not self._stopping.is_set():
            try:
                await asyncio.wait_for(self._stopping.wait(), self.flush_interval)
            except TimeoutError:
                pass
            await self._flush()
        await self._flush()  # anything queued before a stop the loop never saw

    async def _flush(self):
        if not self._pending:
            return
        batch, self._pending = self._pending, []
        try:
            await asyncio.to_thread(self._write_batch, batch)
        except Exception:  # noqa: BLE001 - a full disk must not kill the writer
            log.exception("conversation log write failed (%d records lost)", len(batch))

    def _write_batch(self, batch):
        by_conv = {}
        for conv_id, line in batch:
            by_conv.setdefault(conv_id, []).append(line)
        for conv_id, lines in by_conv.items():
            with (self.dir / f"{conv_id}.jsonl").open("a") as f:
                f.write("\n".join(lines) + "\n")
