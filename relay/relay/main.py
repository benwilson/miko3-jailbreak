#!/usr/bin/env python3
"""Run the relay: the robot lane, the conversation engine and the conversation-log HTTP
endpoints, against the owner's model server (plan KTD1, U5).

    cd relay && .venv/bin/python -m relay.main --model-host 192.168.1.50
    relay/.venv/bin/python relay/relay/main.py --model-host 192.168.1.50   # by path

Every option can also come from the environment as RELAY_<OPTION> (e.g. RELAY_MODEL_HOST,
RELAY_SILENCE_TIMEOUT); a flag beats the environment. Prints "LISTENING ..." once bound.
Standard-library logging goes through a queue to a listener thread, so a slow terminal
never blocks the event loop.
"""
import os
import sys

if __package__ in (None, ""):
    # Run by path, Python put relay/relay/ first on sys.path, where this package's
    # logging.py and http.py would hide the standard library's. Use its parent instead.
    _here = os.path.dirname(os.path.abspath(__file__))
    sys.path[:] = [p for p in sys.path if os.path.abspath(p or os.curdir) != _here]
    sys.path.insert(0, os.path.dirname(_here))

import argparse  # noqa: E402
import asyncio  # noqa: E402
import logging  # noqa: E402
import logging.handlers  # noqa: E402
import queue  # noqa: E402
import signal  # noqa: E402
from pathlib import Path  # noqa: E402

from relay.conversation import Config, ConversationEngine  # noqa: E402
from relay.http import DEFAULT_PORT as HTTP_PORT  # noqa: E402
from relay.http import LogHttpServer  # noqa: E402
from relay.lane import BURST_SECONDS  # noqa: E402
from relay.lane import DEFAULT_PORT as LANE_PORT  # noqa: E402
from relay.lane import LaneServer, Pacing  # noqa: E402
from relay.logging import ConversationLogs  # noqa: E402
from relay.model_client import DEFAULT_PORT as MODEL_PORT  # noqa: E402
from relay.model_client import check_persona  # noqa: E402

log = logging.getLogger("relay.main")

RELAY_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_PERSONA = RELAY_ROOT / "personas" / "default.txt"
DEFAULT_LOG_DIR = RELAY_ROOT / "out" / "conversations"  # out/ is gitignored
DEFAULTS = Config()


def load_persona(path):
    """The persona file's text, stripped; the model server takes ASCII only."""
    text = Path(path).read_text(encoding="utf-8").strip()
    try:
        check_persona(text)
    except ValueError as exc:
        raise SystemExit(f"{path}: {exc}") from None
    if not text:
        raise SystemExit(f"{path}: persona file is empty")
    return text


def parse_args(argv=None):
    def env(name, default):
        return os.environ.get("RELAY_" + name.upper().replace("-", "_"), default)

    def env_flag(name, default):
        """A boolean RELAY_<NAME>: 0/false/no/off is off, anything else is on."""
        value = os.environ.get("RELAY_" + name.upper().replace("-", "_"))
        if value is None:
            return default
        return value.strip().lower() not in ("0", "false", "no", "off", "")

    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    g = p.add_argument_group("addresses")
    g.add_argument("--model-host", default=env("model-host", "127.0.0.1"),
                   help="model server host (webchat/server.py) (default %(default)s)")
    g.add_argument("--model-port", type=int, default=int(env("model-port", MODEL_PORT)),
                   help="model server plain-WebSocket port (default %(default)s)")
    g.add_argument("--model-path", default=env("model-path", DEFAULTS.model_path),
                   help="model server WebSocket route; its `/` is the browser page "
                        "(default %(default)s)")
    g.add_argument("--lane-host", default=env("lane-host", None),
                   help="robot lane bind address (default: this host's LAN address)")
    g.add_argument("--lane-port", type=int, default=int(env("lane-port", LANE_PORT)),
                   help="robot lane port (default %(default)s)")
    g.add_argument("--http-host", default=env("http-host", None),
                   help="conversation-log HTTP bind address (default: the lane's)")
    g.add_argument("--http-port", type=int, default=int(env("http-port", HTTP_PORT)),
                   help="conversation-log HTTP port (default %(default)s)")
    g = p.add_argument_group("conversation")
    g.add_argument("--persona", type=Path, default=Path(env("persona", DEFAULT_PERSONA)),
                   help="persona file, ASCII, sent as the model's system prompt at the "
                        "start of every conversation (default %(default)s)")
    g.add_argument("--farewell-phrase", default=env("farewell-phrase", DEFAULTS.farewell_phrase),
                   help="end the conversation once the model's own reply text says this "
                        "(case, punctuation and whitespace ignored). The persona must tell "
                        "it to say exactly this, so change both together; \"\" disables it "
                        "and only the silence timer is left (default %(default)r)")
    g.add_argument("--log-dir", type=Path, default=Path(env("log-dir", DEFAULT_LOG_DIR)),
                   help="per-conversation JSONL logs (default %(default)s)")
    g.add_argument("--prewarm", action=argparse.BooleanOptionalAction,
                   default=env_flag("prewarm", DEFAULTS.prewarm),
                   help="keep one model session open, already reset and carrying the "
                        "persona, so a wake does not wait for the persona to be read in "
                        "(measured at 1.5-2.8 s idle and 10.9 s on a busy server). The "
                        "model server accepts ONE client, so while this is on and a robot "
                        "is linked the relay holds it and its browser page cannot be used; "
                        "--no-prewarm connects per conversation instead (default: on)")

    def seconds(name, default, text):
        g.add_argument(f"--{name}", type=float, default=float(env(name, default)),
                       help=f"{text} (default %(default)s s)")
    seconds("silence-timeout", DEFAULTS.silence_timeout, "close after nobody speaks this long")
    seconds("drain-cap", DEFAULTS.drain_cap, "longest farewell after the sleep word; a "
                                             "backstop, since the drain normally closes on "
                                             "the robot's playback idle")
    seconds("farewell-start", DEFAULTS.farewell_start_timeout,
            "close at once if no farewell starts this soon after the sleep word")
    seconds("cooldown", DEFAULTS.cooldown, "strict turn-taking: mic stays muted this long "
                                           "after the robot stops speaking")
    g.add_argument("--reply-silence-ms", type=float,
                   default=float(env("reply-silence-ms", DEFAULTS.reply_silence_ms)),
                   help="a reply ends this long after its last non-silent audio chunk. The "
                        "model server streams reply audio continuously and never sends "
                        "agent_end when a reply simply finishes, so this is what ends one. "
                        "A server slower than realtime replies in bursts with gaps past "
                        "800 ms, so this must outlast them; continued assistant text and a "
                        "non-zero stats.speech_queue hold a reply open past it too "
                        "(default %(default)s ms)")
    g.add_argument("--reply-start-grace-ms", type=float,
                   default=float(env("reply-start-grace-ms", DEFAULTS.reply_start_grace_ms)),
                   help="a reply opened by agent_start waits this long for its first sound "
                        "before it ends as an empty one. The server's TTS lags its own "
                        "agent_start by a variable amount, so --reply-silence-ms only starts "
                        "once the reply has speech (default %(default)s ms)")
    g.add_argument("--reply-max-seconds", type=float,
                   default=float(env("reply-max-seconds", DEFAULTS.reply_max_seconds)),
                   help="hard cap on one reply, in seconds of the audio actually forwarded "
                        "to the robot (not wall clock, so a slow server is not cut off for "
                        "being slow). Past it the reply is flushed and ended \"too-long\" "
                        "and the rest of that model turn is muted. The quantized model does "
                        "not reliably stop talking; 0 disables the cap "
                        "(default %(default)s s)")
    g.add_argument("--loop-guard", action=argparse.BooleanOptionalAction,
                   default=env_flag("loop-guard", DEFAULTS.loop_guard),
                   help="watch the reply's own text and cut it when the model starts "
                        "repeating itself (flushed, ended \"looping\", rest of the turn "
                        "muted). Trips when one four-word window recurs "
                        f"{DEFAULTS.loop_guard_repeats} times (default: on)")
    g.add_argument("--reply-silence-rms", type=float,
                   default=float(env("reply-silence-rms", DEFAULTS.reply_silence_rms)),
                   help="reply-channel audio below this RMS counts as silence; the server "
                        "sends exact digital silence between replies (default %(default)s)")
    g.add_argument("--uplink-gate-rms", type=float,
                   default=float(env("uplink-gate-rms", DEFAULTS.uplink_gate_rms)),
                   help="uplink audio below this RMS is replaced by digital silence before "
                        "it reaches the model, in 20 ms frames. The robot's microphone "
                        "floor (RMS 300-470) otherwise latches the model's energy VAD "
                        "\"voiced\" and its user_end never fires; 0 disables the gate "
                        "(default %(default)s)")
    g.add_argument("--uplink-gate-hang-ms", type=float,
                   default=float(env("uplink-gate-hang-ms", DEFAULTS.uplink_gate_hang_ms)),
                   help="the uplink gate keeps passing audio this long after the last loud "
                        "frame, so a word's decaying tail is not clipped; 0 mutes on the "
                        "first quiet frame (default %(default)s ms)")
    g.add_argument("--backlog-alarm-frames", type=float,
                   default=float(env("backlog-alarm-frames", DEFAULTS.backlog_alarm_frames)),
                   help="log one loud warning per excursion when the model reports its "
                        "input more than this many 80 ms frames behind "
                        "(stats.input_backlog_frames), naming the relay's own measured send "
                        "rate; 0 disables the alarm (default %(default)s frames)")
    g.add_argument("--burst-seconds", type=float, default=float(env("burst-seconds",
                                                                     BURST_SECONDS)),
                   help="the largest cushion of reply audio the robot may hold: the relay "
                        "sends chunks back to back until its estimate of the robot's "
                        "speaker reaches this, then one per chunk period. The model "
                        "generates speech at 0.88-0.97x realtime, so without a cushion the "
                        "speaker drains mid-reply and stutters; a bigger one only costs "
                        "audio thrown away on a flush (default %(default)s s)")
    seconds("ready-timeout", DEFAULTS.ready_timeout, "conv.open to conv.ready limit")
    seconds("probe-interval", DEFAULTS.probe_interval, "model health probe period while idle")
    seconds("sleep-settle", DEFAULTS.sleep_settle,
            "age of the running transcript before the dormant sleep word is matched on it")
    p.add_argument("-v", "--verbose", action="store_true",
                   help="debug logging (every lane frame and model event)")
    return p.parse_args(argv)


def setup_logging(verbose):
    """Route stdlib logging through a queue; returns the listener to stop at exit."""
    records = queue.SimpleQueue()
    out = logging.StreamHandler()
    out.setFormatter(logging.Formatter("%(asctime)s %(levelname).1s %(name)s %(message)s"))
    listener = logging.handlers.QueueListener(records, out)
    root = logging.getLogger()
    root.handlers[:] = [logging.handlers.QueueHandler(records)]
    root.setLevel(logging.DEBUG if verbose else logging.INFO)
    logging.getLogger("websockets").setLevel(logging.INFO if verbose else logging.WARNING)
    listener.start()
    return listener


async def run(args):
    persona = load_persona(args.persona)
    config = Config(model_host=args.model_host, model_port=args.model_port,
                    model_path=args.model_path, persona=persona,
                    ready_timeout=args.ready_timeout, silence_timeout=args.silence_timeout,
                    drain_cap=args.drain_cap, farewell_start_timeout=args.farewell_start,
                    cooldown=args.cooldown, probe_interval=args.probe_interval,
                    sleep_settle=args.sleep_settle, farewell_phrase=args.farewell_phrase,
                    prewarm=args.prewarm,
                    reply_silence_ms=args.reply_silence_ms,
                    reply_start_grace_ms=args.reply_start_grace_ms,
                    reply_silence_rms=args.reply_silence_rms,
                    uplink_gate_rms=args.uplink_gate_rms,
                    uplink_gate_hang_ms=args.uplink_gate_hang_ms,
                    backlog_alarm_frames=args.backlog_alarm_frames,
                    reply_max_seconds=args.reply_max_seconds,
                    loop_guard=args.loop_guard)
    logs = ConversationLogs(args.log_dir)
    await logs.start()
    engine = ConversationEngine(config, logs)
    lane = LaneServer(engine, args.lane_host, args.lane_port,
                      pacing=Pacing(burst_seconds=args.burst_seconds))
    http = None
    try:
        await engine.start()
        await lane.start()
        http = LogHttpServer(logs, args.http_host or lane.host, args.http_port)
        await http.start()
        log.info("persona %s (%d chars), logs in %s", args.persona, len(persona), args.log_dir)
        print(f"LISTENING lane ws://{lane.host}:{lane.port}/ "
              f"logs http://{http.host}:{http.port}/conversations "
              f"model ws://{config.model_host}:{config.model_port}{config.model_path}",
              flush=True)
        stop = asyncio.Event()
        loop = asyncio.get_running_loop()
        for sig in (signal.SIGINT, signal.SIGTERM):
            loop.add_signal_handler(sig, stop.set)
        await stop.wait()
    finally:
        await lane.stop()
        await engine.stop()
        if http is not None:
            await http.stop()
        await logs.stop()


def main(argv=None):
    args = parse_args(argv)
    listener = setup_logging(args.verbose)
    try:
        asyncio.run(run(args))
    finally:
        listener.stop()


if __name__ == "__main__":
    main()
