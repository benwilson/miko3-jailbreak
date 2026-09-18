#!/usr/bin/env python3
"""A relay_stub variant for the two robot behaviors the real relay never provokes, for
test_voice_conversation_client.py (U8). Serves the real lane (relay/relay/lane.py) with
relay/tests/relay_stub.py's StubHandler, changed only where these options say:

  --ready-delay S     answer the first conv.open with conv.ready S seconds late, sent
                      even if the robot has given up on that conversation by then (the
                      lane itself would suppress it; the robot must ignore it). A robot
                      conv.close that arrives first is answered, like the real relay
                      (KTD4), with a fresh status{model_ok: true}. Later conv.opens are
                      answered at once.
  --unknown-type T    after conv.ready, send a message of type T the robot cannot know;
                      it must answer cmd.result{status: unsupported} and carry on.

Prints "LISTENING <host>:<port>" once bound; logs to stderr like the stub. Run with the
relay venv's python.
"""
import argparse
import asyncio
import logging
import sys
from pathlib import Path

RELAY_ROOT = Path(__file__).resolve().parents[4] / "relay"
sys.path.insert(0, str(RELAY_ROOT))

from relay.lane import LaneServer  # noqa: E402
from tests.relay_stub import StubHandler, tone  # noqa: E402

log = logging.getLogger("relay.fixture")


class FixtureHandler(StubHandler):
    def __init__(self, pcm, ready_delay=None, unknown_type=None):
        super().__init__(pcm, reply_delay=0.3)
        self.ready_delay = ready_delay
        self.unknown_type = unknown_type
        self.pending = set()  # conv ids opened but not yet answered

    def on_conv_open(self, link, conv, turn_taking, msg):
        log.info("<- conv.open %s (turn_taking=%s)", msg, turn_taking)
        if self.ready_delay is None:
            self._ready(link, conv)
        else:
            delay = self.ready_delay
            self.ready_delay = None
            self.pending.add(conv)
            self._spawn(self._late_ready(link, conv, delay))

    async def _late_ready(self, link, conv, delay):
        await asyncio.sleep(delay)
        still_open = conv in self.pending
        self.pending.discard(conv)
        # Raw send: Link.send_conv_ready() refuses a conversation that is not open.
        link.send("conv.ready", conv)
        log.info("-> conv.ready %s (late; robot %s)", conv,
                 "still waiting" if still_open else "had already closed it")
        if still_open:
            self._after_ready(link, conv)

    def _ready(self, link, conv):
        link.send_conv_ready(conv)
        self._after_ready(link, conv)

    def _after_ready(self, link, conv):
        if self.unknown_type:
            link.send(self.unknown_type, conv, detail="from the future")
            log.info("-> %s (expect unsupported)", self.unknown_type)
        self._spawn(self._script(link, conv))

    def on_conv_close(self, link, conv, reason, msg):
        super().on_conv_close(link, conv, reason, msg)
        if conv in self.pending:
            self.pending.discard(conv)
            link.send_status(True)
            log.info("-> status model_ok=true (robot closed while connecting)")


async def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=0)
    parser.add_argument("--ready-delay", type=float)
    parser.add_argument("--unknown-type")
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(message)s")
    handler = FixtureHandler(tone(1.0), args.ready_delay, args.unknown_type)
    async with LaneServer(handler, args.host, args.port) as server:
        print(f"LISTENING {server.host}:{server.port}", flush=True)
        await asyncio.Event().wait()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
