#!/usr/bin/env python3
"""Runs relay/tests/fake_model_server.py as a standalone process for
test_voice_conversation_client.py (U8): the full relay dials it as its model server.

The fake server is a library (an async context manager with a Script); this wraps it
with named scripts and a few stdin commands so a test can steer it between
conversations:

  script NAME     the script later sessions run (see SCRIPTS)
  delay SECONDS   hold every later WebSocket handshake this long before accepting it,
                  so the relay's conv.ready comes late (probes are held too)

Prints "LISTENING <port>" once bound. When a session that ran a timeline (one that sent
`system`, i.e. a conversation rather than a health probe) ends, prints
"SESSION <n> script=<name> binaries=<count> seqs=<a,b,...>": the sequence numbers the
Java harness stamps into the first four bytes of every uplink chunk, in arrival order,
skipping the relay's zero-filled frames. Run with the relay venv's python.
"""
import argparse
import asyncio
import struct
import sys
from pathlib import Path

RELAY_ROOT = Path(__file__).resolve().parents[4] / "relay"
sys.path.insert(0, str(RELAY_ROOT))

from websockets.asyncio.server import serve  # noqa: E402

from tests.fake_model_server import FakeModelServer, Script, Turn  # noqa: E402

SCRIPTS = {
    # One exchange, then "goodbye miko": the relay drains the farewell and closes with
    # sleep_word once the robot reports playback idle.
    "chat": Script(turns=[
        Turn(user_end_at=0.6, user_text=["hello ", "there"], reply_delay=0.2, reply_seconds=0.8),
        Turn(user_end_at=2.4, user_text=["goodbye ", "miko"], reply_delay=0.2, reply_seconds=0.64),
    ]),
    # A farewell longer than the relay's drain cap (the test runs the relay with a short
    # --drain-cap): the relay closes farewell_timeout while the robot still has audio.
    "long_farewell": Script(turns=[
        Turn(user_end_at=0.3, user_text=["goodbye miko"], reply_delay=0.1, reply_seconds=4.0),
    ]),
    # A long reply, for losing the relay mid-reply.
    "long_reply": Script(turns=[
        Turn(user_end_at=0.3, user_text=["tell me a story"], reply_delay=0.1, reply_seconds=8.0),
    ]),
    # Nobody speaks: the conversation stays open until the robot or the silence timer ends it.
    "quiet": Script(),
}


class LauncherServer(FakeModelServer):
    def __init__(self):
        super().__init__(SCRIPTS["quiet"])
        self.script_name = "quiet"
        self.handshake_delay = 0.0
        self.reported = 0

    async def __aenter__(self):
        self._server = await serve(self._handle, "127.0.0.1", 0, compression=None,
                                   process_request=self._hold)
        self.port = self._server.sockets[0].getsockname()[1]
        return self

    async def _hold(self, connection, request):
        if self.handshake_delay:
            await asyncio.sleep(self.handshake_delay)
        return None

    async def _handle(self, ws):
        name = self.script_name
        try:
            await super()._handle(ws)
        finally:
            session = next((s for s in self.sessions if s.ws is ws), None)
            if session is not None and any(t.get("type") == "system" for t in session.texts()):
                self.reported += 1
                seqs = []
                for frame in session.binaries():
                    data = frame.data
                    if len(data) >= 4 and any(data):
                        seqs.append(struct.unpack("<i", data[:4])[0])
                print(f"SESSION {self.reported} script={name} binaries={len(session.binaries())} "
                      f"seqs={','.join(map(str, seqs))}", flush=True)


async def commands(server):
    while True:
        line = await asyncio.to_thread(sys.stdin.readline)
        if not line:
            return
        words = line.split()
        if len(words) == 2 and words[0] == "script" and words[1] in SCRIPTS:
            server.script = SCRIPTS[words[1]]
            server.script_name = words[1]
            print(f"SCRIPT {words[1]}", flush=True)
        elif len(words) == 2 and words[0] == "delay":
            server.handshake_delay = float(words[1])
            print(f"DELAY {server.handshake_delay}", flush=True)
        elif words:
            print(f"UNKNOWN {line.strip()}", flush=True)


async def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--script", default="quiet", choices=sorted(SCRIPTS))
    args = parser.parse_args()
    async with LauncherServer() as server:
        server.script = SCRIPTS[args.script]
        server.script_name = args.script
        print(f"LISTENING {server.port}", flush=True)
        await asyncio.gather(commands(server), asyncio.Event().wait())


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
