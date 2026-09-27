#!/usr/bin/env python3
"""
robot-faces.py — see the robot's recent face checks and the people he knows,
save the check crops for the bench, and read or set the face thresholds, over
adb (on-device face recognition plan U8, R15; KTD5, KTD8).

It follows scripts/robot-settings.py and borrows its adb and HTTPS helpers:
an adb forward to the launcher's HTTPS port (8443, self-signed), the page
token scraped from the Settings page, then the launcher's face routes:

  POST /settings/face/state        the checks, people and thresholds as JSON
  POST /settings/face/thresholds   the thresholds save (loopback callers only,
                                   which is how the adb forward arrives)
  GET  /settings/face/check-crop   one check's crop by handle

The forward is removed on every exit path, Ctrl-C included. The page token
only ever travels in a POST body; it is never printed or put in an adb argv.

Threshold edits are checked here with the launcher's own rules
(ClaudeSettings.checkFace) before anything is sent; when only some are given,
the rest come from the robot's current values first. Fields not given keep
their stored values on the robot.

Usage:
  python3 scripts/robot-faces.py checks                 # newest first
  python3 scripts/robot-faces.py checks --save [DIR]    # also the crops as JPEGs
  python3 scripts/robot-faces.py people
  python3 scripts/robot-faces.py thresholds             # print the current values
  python3 scripts/robot-faces.py thresholds --confident 0.5 --close 0.363 --margin 0.05 \\
      --min-width 48 --dark-floor 40 --dim-level 90 --blur-floor 30
  python3 scripts/robot-faces.py people --serial 10.0.0.5:5555
"""
import argparse
import importlib.util
import json
import math
import re
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


# adb, the forward, HTTPS, the token scrape and the redirect status all come from here.
rs = _load_robot_settings()
SettingsError = rs.SettingsError

DEFAULT_SERIAL = rs.DEFAULT_SERIAL

# Paths from shared/src/com/miko3/shared/LauncherProtocol.java.
STATE_PATH = "/settings/face/state"
THRESHOLDS_PATH = "/settings/face/thresholds"
CROP_PATH = "/settings/face/check-crop"

# Gitignored (tools/face-bench/). The leading "_" keeps face-bench.py's `run`
# from taking the folder for a person, as with its own _unsorted/.
DEFAULT_SAVE_DIR = REPO / "tools" / "face-bench" / "captures" / "_checks"

# SettingsPage's fixed status texts.
FACE_THRESHOLDS_SAVED = "Face thresholds saved; they apply from the next meeting."

# ClaudeSettings' fixed refusals and rules (KTD5), in the launcher's order.
FACE_REFUSE_BANDS = "The bands need 0 <= close <= confident <= 1."
FACE_REFUSE_MARGIN = "The near-tie margin must be between 0 and 0.3."
FACE_REFUSE_LUMA = "The dark floor must be below the dim level, both between 0 and 255."
FACE_REFUSE_BLUR = "The blur floor must be 0 or more."
FACE_REFUSE_WIDTH = "The minimum face width must be between 16 and 640 pixels."

RULES = [
    (("close", "confident"), lambda v: 0 <= v["close"] <= v["confident"] <= 1, FACE_REFUSE_BANDS),
    (("margin",), lambda v: 0 <= v["margin"] <= 0.3, FACE_REFUSE_MARGIN),
    (("dark_floor", "dim_level"), lambda v: 0 <= v["dark_floor"] < v["dim_level"] <= 255, FACE_REFUSE_LUMA),
    (("blur_floor",), lambda v: 0 <= v["blur_floor"] < math.inf, FACE_REFUSE_BLUR),
    (("min_width",), lambda v: 16 <= v["min_width"] <= 640, FACE_REFUSE_WIDTH),
]

# (form field, flag, label) in the order they are shown.
FIELDS = [
    ("confident", "--confident", "confident"),
    ("close", "--close", "close"),
    ("margin", "--margin", "near-tie margin"),
    ("min_width", "--min-width", "min face width (px)"),
    ("dark_floor", "--dark-floor", "dark floor (luma)"),
    ("dim_level", "--dim-level", "dim level (luma)"),
    ("blur_floor", "--blur-floor", "blur floor"),
]

# SettingsPage.number(): what the launcher accepts as a plain decimal.
PLAIN_DECIMAL = re.compile(r"-?[0-9]{1,6}(\.[0-9]{1,6})?")


# --- formatting ---

def format_time(ms):
    return datetime.fromtimestamp(ms / 1000).strftime("%Y-%m-%d %H:%M:%S")


def plain_decimal(v):
    """A number as the launcher's form wants it: no exponent, at most 6 places."""
    if isinstance(v, int):
        return str(v)
    if not math.isfinite(v):
        return str(v)
    text = f"{v:.6f}".rstrip("0").rstrip(".")
    return "0" if text in ("", "-0") else text


def _cell(value):
    """Names come from the robot: keep them on one line and out of the column bars."""
    return re.sub(r"[\x00-\x1f\x7f|]", " ", str(value))


def table(headers, rows):
    widths = [max([len(h)] + [len(r[i]) for r in rows]) for i, h in enumerate(headers)]
    line = lambda cells: "| " + " | ".join(c.ljust(w) for c, w in zip(cells, widths)) + " |"
    return "\n".join([line(headers), "|" + "|".join("-" * (w + 2) for w in widths) + "|"]
                     + [line(r) for r in rows])


def newest_first(checks):
    return sorted(checks, key=lambda c: (c.get("at_ms", 0), c.get("handle", 0)), reverse=True)


def _who(pid, name):
    return (name or "(unnamed)") if pid else "-"


def render_checks(checks):
    if not checks:
        return "No face checks since the launcher started."
    rows = []
    for c in newest_first(checks):
        decision = c.get("decision", "")
        if c.get("reason"):
            decision += f" ({c['reason']})"
        best = c.get("best_id", "")
        score = f"{c.get('score') or 0:.2f}" if best else "-"
        runner = "-"
        if c.get("near_tie") and c.get("runner_up_id"):
            runner = (f"near tie: {_who(c['runner_up_id'], c.get('runner_up_name'))} "
                      f"{c.get('runner_up_score') or 0:.2f}")
        outcome = c.get("outcome", "")
        if c.get("joined_id"):
            outcome += f" -> {_who(c['joined_id'], c.get('joined_name'))}"
        rows.append([_cell(x) for x in (format_time(c.get("at_ms", 0)), decision,
                                        _who(best, c.get("best_name")), score, runner, outcome)])
    return table(["time", "decision", "best match", "score", "runner-up", "outcome"], rows)


def render_people(people):
    if not people:
        return "The robot remembers nobody yet."
    rows = [[_cell(x) for x in (p.get("name") or "(unnamed)", p.get("photos", 0), p.get("with_embedding", 0),
                                p.get("unusable", 0),
                                format_time(p["last_seen_ms"]) if p.get("last_seen_ms") else "never")]
            for p in people]
    return table(["name", "photos", "with embedding", "unusable", "last seen"], rows)


def render_thresholds(th):
    width = max(len(label) for _, _, label in FIELDS)
    return "\n".join(f"  {label.ljust(width)}  {plain_decimal(th[name]) if name in th else '?'}"
                     for name, _, label in FIELDS)


def crop_filename(c):
    stamp = datetime.fromtimestamp(c["at_ms"] / 1000).strftime("%Y%m%d-%H%M%S")
    outcome = re.sub(r"[^a-z0-9]+", "-", (c.get("outcome") or "unknown").lower()).strip("-")
    return f"{stamp}-{outcome}-{c['handle']}.jpg"


# --- thresholds, checked locally ---

def check_thresholds(values, only_given=None):
    """Raises with the launcher's fixed text for the first rule that fails.
    With only_given, checks just the rules whose fields were all given."""
    for keys, ok, text in RULES:
        if only_given is not None and not set(keys) <= only_given:
            continue
        try:
            passed = ok(values)
        except TypeError:
            passed = False
        if not passed:
            raise SettingsError(f"!! not sent: {text}")
    for name, flag, _ in FIELDS:
        if only_given is not None and name not in only_given:
            continue
        if not PLAIN_DECIMAL.fullmatch(plain_decimal(values[name])):
            raise SettingsError(f"!! not sent: {flag} {values[name]} is not a plain decimal the robot "
                                "accepts (at most 6 digits either side of the point).")


def given_thresholds(args):
    return {name: getattr(args, name) for name, _, _ in FIELDS if getattr(args, name, None) is not None}


# --- the robot ---

def fetch_state(base):
    token = rs.fetch_page(base).token
    try:
        r = rs.http_request("POST", base + STATE_PATH, body=urlencode([("t", token)]),
                            headers={"Content-Type": "application/x-www-form-urlencoded"})
    except OSError as exc:
        raise SettingsError(f"!! POST {STATE_PATH} failed: {exc}\n"
                            "   Check the launcher is still running on the robot, then re-run.")
    if r.status != 200:
        detail = r.body.decode("utf-8", "replace").strip()[:200]
        raise SettingsError(f"!! POST {STATE_PATH} answered {r.status}"
                            + (f" ({detail})" if detail else "")
                            + "; the launcher on the robot may predate face checks — install the current launcher.")
    try:
        state = json.loads(r.body.decode("utf-8"))
    except ValueError:
        raise SettingsError(f"!! POST {STATE_PATH} did not answer JSON; install the current launcher.")
    for k in ("thresholds", "checks", "people"):
        state.setdefault(k, {} if k == "thresholds" else [])
    return state


def save_crops(base, checks, dest):
    dest = Path(dest)
    dest.mkdir(parents=True, exist_ok=True)
    saved = 0
    for c in newest_first(checks):
        if not c.get("has_crop"):
            continue
        url = f"{base}{CROP_PATH}?{urlencode([('id', c['handle'])])}"
        try:
            r = rs.http_request("GET", url)
        except OSError as exc:
            raise SettingsError(f"!! GET {CROP_PATH} failed: {exc}")
        if r.status == 404:
            print(f"!! check {c['handle']} has rolled off the robot's list; its crop was not saved.",
                  file=sys.stderr)
            continue
        if r.status != 200:
            raise SettingsError(f"!! GET {CROP_PATH} answered {r.status}; install the current launcher.")
        (dest / crop_filename(c)).write_bytes(r.body)
        saved += 1
    print(f"Saved {saved} crop(s) to {dest}")


def run_checks(base, save):
    state = fetch_state(base)
    print(render_checks(state["checks"]))
    if save is not None:
        save_crops(base, state["checks"], save)


def run_people(base):
    print(render_people(fetch_state(base)["people"]))


def run_thresholds(base, given):
    current = fetch_state(base)["thresholds"]
    if not given:
        print("Face thresholds on the robot:")
        print(render_thresholds(current))
        return
    merged = dict(current)
    merged.update(given)
    check_thresholds(merged)
    fields = [("t", rs.fetch_page(base).token)] + [(name, plain_decimal(given[name]))
                                                  for name, _, _ in FIELDS if name in given]
    try:
        r = rs.http_request("POST", base + THRESHOLDS_PATH, body=urlencode(fields),
                            headers={"Content-Type": "application/x-www-form-urlencoded"})
    except OSError as exc:
        raise SettingsError(f"!! POST {THRESHOLDS_PATH} failed: {exc}\n"
                            "   Check the launcher is still running on the robot, then re-run.")
    if r.status == 403:
        detail = r.body.decode("utf-8", "replace").strip()[:200]
        raise SettingsError(f"!! not saved: POST {THRESHOLDS_PATH} answered 403"
                            + (f" ({detail})" if detail else ""))
    if r.status not in (301, 302, 303) or "location" not in r.headers:
        raise SettingsError(f"!! POST {THRESHOLDS_PATH} answered {r.status} instead of a redirect; "
                            "install the current launcher.")
    status = rs.status_from_location(r.headers["location"])
    if status != FACE_THRESHOLDS_SAVED:
        raise SettingsError(f"!! save failed: {status or 'the save gave no status'}")
    print(f"Thresholds: {status}")
    print(render_thresholds(fetch_state(base)["thresholds"]))


# --- the command line ---

def parse_args(argv=None):
    ap = argparse.ArgumentParser(
        description="See the robot's recent face checks and people, save check crops, and read or set "
                    "the face thresholds, over adb.")
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--serial", default=DEFAULT_SERIAL,
                        help=f"adb serial (default: {DEFAULT_SERIAL}; host:port is adb-connected first)")
    sub = ap.add_subparsers(dest="command", required=True)
    p = sub.add_parser("checks", parents=[common], help="recent face checks, newest first")
    p.add_argument("--save", nargs="?", const=str(DEFAULT_SAVE_DIR), default=None, metavar="DIR",
                   help=f"also save each check's crop as a JPEG (default DIR: {DEFAULT_SAVE_DIR})")
    sub.add_parser("people", parents=[common], help="people with their photo counts")
    p = sub.add_parser("thresholds", parents=[common],
                       help="print the face thresholds, or set any of them")
    for name, flag, label in FIELDS:
        p.add_argument(flag, dest=name, type=int if name == "min_width" else float, help=label)
    return ap.parse_args(argv)


def main(argv=None):
    args = parse_args(argv)
    try:
        given = given_thresholds(args) if args.command == "thresholds" else {}
        # Rules whose fields were all given are checked before any adb command.
        check_thresholds(given, only_given=set(given))
        print(f"== reaching the robot at {args.serial} ==", flush=True)
        rs.ensure_reachable(args.serial)
        with rs.port_forward(args.serial) as base:
            if args.command == "checks":
                run_checks(base, args.save)
            elif args.command == "people":
                run_people(base)
            else:
                run_thresholds(base, given)
    except SettingsError as exc:
        print(exc, file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("\n!! interrupted; any adb forward this run opened was removed.", file=sys.stderr)
        return 130
    return 0


if __name__ == "__main__":
    sys.exit(main())
