#!/usr/bin/env python3
"""relay_stub.py -- a scripted relay for robot bring-up before the conversation engine
exists (plan U11, U8 bring-up, U10 lane scenarios).

Serves the real lane (relay/relay/lane.py) with a canned handler instead of a model:

- answers `hello` with `welcome` (model_ok from --model-down), and `conv.open` with
  `conv.ready`, logging the conversation's turn-taking value;
- --reply-delay seconds after `conv.ready` streams a WAV file (--wav; 16-bit PCM, mixed to
  mono and resampled to 22.05 kHz if needed) or, without one, a 440 Hz tone
  (--tone-seconds) as one paced reply;
- optionally sends `audio.flush` part way through (--flush-after), a command the robot
  cannot know (--unknown-cmd, which it must answer `unsupported`, AE8), and closes the
  conversation (--close-after, --close-reason);
- logs every frame it receives (binary frames by size only, never their bytes).

Typed commands on stdin act on every linked robot's open conversation:
  reply            play the reply again        flush        flush the playing reply
  cmd ACTION       send cmd{action: ACTION}    status 0|1   send status{model_ok}
  close REASON     close with REASON (sleep_word, silence, farewell_timeout, ...)

Run from relay/ with the relay venv:
  .venv/bin/python -m tests.relay_stub --wav hello.wav
or by path from anywhere:  relay/.venv/bin/python relay/tests/relay_stub.py
It binds the LAN address (lane.lan_address()) on port 8790 unless --host/--port say
otherwise, and prints "LISTENING <host>:<port>" once bound.
"""
import argparse
import asyncio
import logging
import math
import sys
import wave
from array import array
from pathlib import Path

RELAY_ROOT = Path(__file__).resolve().parents[1]
if str(RELAY_ROOT) not in sys.path:
    sys.path.insert(0, str(RELAY_ROOT))

from relay.lane import CLOSE_REASONS, DEFAULT_PORT, LaneHandler, LaneServer  # noqa: E402
from relay.model_client import OUTPUT_RATE  # noqa: E402

log = logging.getLogger("relay.stub")


def tone(seconds, freq=440.0, level=0.25):
    """A sine at OUTPUT_RATE as 16-bit LE mono PCM, with 10 ms fades against clicks."""
    n = int(OUTPUT_RATE * seconds)
    fade = int(OUTPUT_RATE * 0.010)
    samples = array("h")
    for i in range(n):
        gain = min(1.0, i / fade, (n - 1 - i) / fade) if fade else 1.0
        samples.append(int(32767 * level * gain * math.sin(2 * math.pi * freq * i / OUTPUT_RATE)))
    return samples.tobytes()


def load_wav(path):
    """A 16-bit PCM WAV as OUTPUT_RATE mono PCM (channels averaged, linear resampling)."""
    with wave.open(str(path), "rb") as w:
        if w.getsampwidth() != 2:
            raise ValueError(f"{path}: need 16-bit PCM, got {8 * w.getsampwidth()}-bit")
        channels, rate = w.getnchannels(), w.getframerate()
        raw = array("h")
        raw.frombytes(w.readframes(w.getnframes()))
    if sys.byteorder == "big":
        raw.byteswap()
    mono = [sum(raw[i:i + channels]) / channels for i in range(0, len(raw), channels)]
    if rate != OUTPUT_RATE and mono:
        step = rate / OUTPUT_RATE
        out_n = int(len(mono) / step)
        resampled = []
        for k in range(out_n):
            x = k * step
            i = int(x)
            j = min(i + 1, len(mono) - 1)
            resampled.append(mono[i] + (mono[j] - mono[i]) * (x - i))
        mono = resampled
    out = array("h", (max(-32768, min(32767, int(s))) for s in mono))
    if sys.byteorder == "big":
        out.byteswap()
    return out.tobytes()


class StubHandler(LaneHandler):
    """Canned conversation behavior; see the module docstring."""

    def __init__(self, reply_pcm, reply_delay=1.0, *, flush_after=None, unknown_cmd=None,
                 close_after=None, close_reason="silence", model_ok=True):
        self.reply_pcm = reply_pcm
        self.reply_delay = reply_delay
        self.flush_after = flush_after
        self.unknown_cmd = unknown_cmd
        self.close_after = close_after
        self.close_reason = close_reason
        self.ok = model_ok
        self.uplink_bytes = 0
        self.cmd_results = []
        self._replies = {}  # link -> id of the reply being played
        self._tasks = set()

    def model_ok(self, link):
        return self.ok

    def on_link(self, link):
        log.info("<- hello %s", link.hello)

    def on_conv_open(self, link, conv, turn_taking, msg):
        log.info("<- conv.open %s (turn_taking=%s)", msg, turn_taking)
        link.send_conv_ready(conv)
        self._spawn(self._script(link, conv))

    def on_uplink(self, link, conv, pcm):
        self.uplink_bytes += len(pcm)
        log.info("<- uplink %d bytes (conv %s, %d bytes so far)", len(pcm), conv,
                 self.uplink_bytes)

    def on_conv_close(self, link, conv, reason, msg):
        log.info("<- conv.close %s (reason %s)", msg if msg else conv, reason)
        self._replies.pop(link, None)

    def on_playback(self, link, msg):
        log.info("<- playback %s", msg)

    def on_cmd_result(self, link, msg):
        log.info("<- cmd.result %s", msg)
        self.cmd_results.append(msg)

    def on_link_closed(self, link, why):
        log.info("link %s closed: %s", link.robot_id, why)
        self._replies.pop(link, None)

    def play(self, link):
        """Stream reply_pcm as one paced reply in the open conversation."""
        reply_id = link.begin_reply()
        if reply_id is None:
            return None
        self._replies[link] = reply_id
        link.send_reply_audio(reply_id, self.reply_pcm)
        link.end_reply(reply_id)
        log.info("-> reply %s (%.2f s)", reply_id, len(self.reply_pcm) / 2 / OUTPUT_RATE)
        return reply_id

    def flush(self, link):
        """Flush the reply last played on this link; False if there was none."""
        reply_id = self._replies.pop(link, None)
        if reply_id is None:
            return False
        link.flush_reply(reply_id)
        return True

    async def _script(self, link, conv):
        await asyncio.sleep(self.reply_delay)
        if link.conv != conv:
            return
        self.play(link)
        if self.unknown_cmd:
            log.info("-> cmd %s (expect unsupported)", self.unknown_cmd)
            link.send_cmd(self.unknown_cmd)
        steps = []
        if self.flush_after is not None:
            steps.append((self.flush_after, lambda: self.flush(link)))
        if self.close_after is not None:
            steps.append((self.close_after, lambda: link.close_conv(self.close_reason, conv)))
        elapsed = 0.0
        for at, action in sorted(steps, key=lambda s: s[0]):
            await asyncio.sleep(max(0.0, at - elapsed))
            elapsed = at
            if link.conv != conv:
                return
            action()

    def _spawn(self, coro):
        task = asyncio.create_task(coro)
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)


async def _stdin_commands(server, handler):
    """Apply typed commands to every linked robot's open conversation."""
    while True:
        line = await asyncio.to_thread(sys.stdin.readline)
        if not line:
            return
        words = line.split()
        if not words:
            continue
        for link in list(server.links.values()):
            try:
                if words[0] == "reply":
                    handler.play(link)
                elif words[0] == "flush":
                    handler.flush(link)
                elif words[0] == "cmd" and len(words) > 1:
                    link.send_cmd(words[1])
                elif words[0] == "status" and len(words) > 1:
                    link.send_status(words[1] not in ("0", "false"))
                elif words[0] == "close" and len(words) > 1:
                    link.close_conv(words[1])
                else:
                    log.warning("unknown command %r", line.strip())
            except ValueError as e:
                log.warning("%s", e)


async def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--host", default=None, help="bind address (default: LAN address)")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--wav", type=Path, help="reply audio (default: a tone)")
    parser.add_argument("--tone-seconds", type=float, default=3.0)
    parser.add_argument("--reply-delay", type=float, default=1.0,
                        help="seconds from conv.ready to the reply")
    parser.add_argument("--flush-after", type=float, help="seconds into the reply to flush")
    parser.add_argument("--unknown-cmd", metavar="ACTION", help="send cmd{action} (AE8)")
    parser.add_argument("--close-after", type=float, help="seconds into the reply to close")
    parser.add_argument("--close-reason", default="silence", choices=sorted(CLOSE_REASONS))
    parser.add_argument("--model-down", action="store_true", help="welcome with model_ok false")
    parser.add_argument("-v", "--verbose", action="store_true", help="log every lane frame")
    args = parser.parse_args(argv)
    logging.basicConfig(level=logging.DEBUG if args.verbose else logging.INFO,
                        format="%(asctime)s %(name)s %(message)s")
    pcm = load_wav(args.wav) if args.wav else tone(args.tone_seconds)
    handler = StubHandler(pcm, args.reply_delay, flush_after=args.flush_after,
                          unknown_cmd=args.unknown_cmd, close_after=args.close_after,
                          close_reason=args.close_reason, model_ok=not args.model_down)
    async with LaneServer(handler, args.host, args.port) as server:
        print(f"LISTENING {server.host}:{server.port}", flush=True)
        await asyncio.gather(_stdin_commands(server, handler), asyncio.Event().wait())


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
