#!/usr/bin/env python3
"""
pull-feedback.py — read the robot's feedback log over adb into a markdown file
to act on (owner 2026-10-02).

When people tell Miko something about himself in a conversation (a suggestion,
a complaint, praise, a bug), Explore keeps only that: its kind, a one-sentence
summary, the person's key sentence and who (a known person's first name, else
"someone"), never the conversation. This script fetches the log the same way
scripts/robot-faces.py fetches the face checks, borrowing robot-settings.py's
helpers: an adb forward to the launcher's HTTPS port, the page token scraped
from the Settings page, then

  POST /settings/feedback/state    the log as JSON, newest first, no person ids

The forward is removed on every exit path, Ctrl-C included. The page token
only ever travels in a POST body; it is never printed, written or put in an
adb argv. The markdown goes to out/ by default, which is gitignored: the quotes
are people's own words and stay off the repository.

The same entries show on the robot's Settings page, under "Feedback from
conversations", which also has the button that clears the log.

Usage:
  python3 scripts/pull-feedback.py
  python3 scripts/pull-feedback.py --out /tmp/feedback.md --serial 10.0.0.5:5555
"""
import argparse
import importlib.util
import json
import sys
from datetime import datetime
from pathlib import Path
from urllib.parse import urlencode

HERE = Path(__file__).resolve().parent
REPO = HERE.parent


def _load_robot_settings():
    spec = importlib.util.spec_from_file_location("robot_settings", HERE / "robot-settings.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


# adb, the forward, HTTPS and the token scrape all come from here.
rs = _load_robot_settings()
SettingsError = rs.SettingsError

DEFAULT_SERIAL = rs.DEFAULT_SERIAL
# From shared/src/com/miko3/shared/LauncherProtocol.java.
STATE_PATH = "/settings/feedback/state"
DEFAULT_OUT = REPO / "out" / "robot-feedback.md"
KINDS = ("complaint", "suggestion", "praise", "bug")


def format_time(ms):
    return datetime.fromtimestamp(ms / 1000).strftime("%Y-%m-%d %H:%M")


def _line(text):
    """One markdown line: no newlines, so an entry cannot break the list."""
    return " ".join(str(text or "").split())


def render(entries, serial, pulled_at=None):
    entries = sorted(entries, key=lambda e: e.get("at", 0), reverse=True)
    pulled = (pulled_at or datetime.now()).strftime("%Y-%m-%d %H:%M")
    counts = ", ".join(f"{sum(1 for e in entries if e.get('kind') == k)} {k}" for k in KINDS)
    out = ["# Feedback from conversations", "",
           f"Pulled {pulled} from the robot at {serial}: {len(entries)} entries ({counts}), newest first.",
           "Each is what someone told Miko about himself; only their key sentence is quoted, never the "
           "conversation. Tick an entry off once it is acted on.", ""]
    if not entries:
        out.append("No feedback yet.")
    for e in entries:
        head = f"- [ ] **{_line(e.get('kind'))}** · {_line(e.get('who')) or 'someone'} · {format_time(e.get('at', 0))}"
        if e.get("context"):
            head += f" · {_line(e['context'])}"
        out.append(head + f" — {_line(e.get('summary'))}")
        if e.get("quote"):
            out.append(f"  > {_line(e['quote'])}")
    return "\n".join(out) + "\n"


def fetch_entries(base):
    token = rs.fetch_page(base).token
    try:
        r = rs.http_request("POST", base + STATE_PATH, body=urlencode([("t", token)]),
                            headers={"Content-Type": "application/x-www-form-urlencoded"})
    except OSError as exc:
        raise SettingsError(f"!! POST {STATE_PATH} failed: {exc}\n"
                            "   Check the launcher is still running on the robot, then re-run.")
    if r.status != 200:
        raise SettingsError(f"!! POST {STATE_PATH} answered {r.status}; the launcher on the robot may predate "
                            "the feedback log — install the current launcher.")
    try:
        state = json.loads(r.body.decode("utf-8"))
    except ValueError:
        raise SettingsError(f"!! POST {STATE_PATH} did not answer JSON; install the current launcher.")
    entries = state.get("entries") if isinstance(state, dict) else None
    if not isinstance(entries, list):
        raise SettingsError(f"!! POST {STATE_PATH} answered no entries; install the current launcher.")
    return [e for e in entries if isinstance(e, dict)]


def parse_args(argv=None):
    ap = argparse.ArgumentParser(description="Read the robot's feedback log over adb into a markdown file.")
    ap.add_argument("--serial", default=DEFAULT_SERIAL,
                    help=f"adb serial (default: {DEFAULT_SERIAL}; host:port is adb-connected first)")
    ap.add_argument("--out", default=str(DEFAULT_OUT), help=f"markdown file to write (default: {DEFAULT_OUT})")
    return ap.parse_args(argv)


def main(argv=None):
    args = parse_args(argv)
    try:
        print(f"== reaching the robot at {args.serial} ==", flush=True)
        rs.ensure_reachable(args.serial)
        with rs.port_forward(args.serial) as base:
            entries = fetch_entries(base)
        out = Path(args.out)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(render(entries, args.serial))
        print(f"{len(entries)} entries written to {out}")
    except SettingsError as exc:
        print(exc, file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("\n!! interrupted; any adb forward this run opened was removed.", file=sys.stderr)
        return 130
    return 0


if __name__ == "__main__":
    sys.exit(main())
