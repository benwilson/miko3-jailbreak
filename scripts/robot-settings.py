#!/usr/bin/env python3
"""
robot-settings.py — push the Claude API settings (base URL, key, model) to a
connected robot, test them, or list the endpoint's models, over adb (U6, R11, R12).

It opens an adb forward to the launcher's HTTPS port (8443, self-signed),
fetches the Settings page for its token, and POSTs the same forms a browser
does. The forward is removed on every exit path. No new robot endpoint.

The key (R14):
  - comes from ANTHROPIC_API_KEY, or a hidden prompt when that is unset;
    it is never a command-line argument (there is no --api-key flag);
  - goes only in the POST body, never in an adb argv or a URL;
  - is never printed: output shows its last four characters, and only for
    keys of 12 characters or more, as the page does.

A push always sends a key, so it also needs an explicit base URL
(--base-url or ANTHROPIC_BASE_URL); a key must never be paired with a URL
it was not meant for. The URL must be https://. Both are checked before any
adb command runs.

The model comes from --model, then ANTHROPIC_MODEL, then the model already
stored on the robot. With none, the push saves the rest and prints the
endpoint's model list so you can pick one.

Usage:
  python3 scripts/robot-settings.py                      # push (the default), then test
  python3 scripts/robot-settings.py push --model claude-opus-5-5
  python3 scripts/robot-settings.py test                 # run Test connection
  python3 scripts/robot-settings.py models               # refresh and print the model list
  python3 scripts/robot-settings.py --serial 10.0.0.5:5555 test
"""
import argparse
import getpass
import http.client
import os
import ssl
import subprocess
import sys
from collections import namedtuple
from contextlib import contextmanager
from html.parser import HTMLParser
from urllib.parse import parse_qs, urlencode, urlsplit

DEFAULT_SERIAL = "192.168.19.74:5555"
LAUNCHER_HTTPS_PORT = 8443

# Paths from shared/src/com/miko3/shared/LauncherProtocol.java.
SETTINGS_PATH = "/settings"
SAVE_PATH = "/settings/claude"
MODELS_PATH = "/settings/claude/models"
TEST_PATH = "/settings/claude/test"

ADB_TIMEOUT = 30
CONNECT_TIMEOUT = 15
# The robot's own Test and Refresh calls reach the endpoint, so allow for them.
HTTP_TIMEOUT = 60

# Mirrors the page: the last four are shown only for keys this long (R4).
MIN_KEY_FOR_LAST_FOUR = 12

Response = namedtuple("Response", "status headers body")


class SettingsError(Exception):
    """A step failed; the message says what happened and what to do."""


# --- the key ---

def mask_key(key):
    if len(key) >= MIN_KEY_FOR_LAST_FOUR:
        return "…" + key[-4:]
    return "(hidden: shorter than %d characters)" % MIN_KEY_FOR_LAST_FOUR


def redact(text, key):
    """A safety net for anything printed: the key never appears in output."""
    text = str(text)
    return text.replace(key, "[key redacted]") if key else text


def validate_base_url(url):
    parts = urlsplit(url)
    if parts.scheme.lower() != "https" or not parts.hostname:
        raise SettingsError(f"!! base URL must start with https:// and name a host, got: {url}\n"
                            "   The robot never stores a non-https URL (R7). Fix --base-url or "
                            "ANTHROPIC_BASE_URL and re-run.")


def resolve_push_inputs(args):
    """(base_url, key, model-or-empty) for a push; every check runs before any adb call."""
    base_url = (args.base_url or os.environ.get("ANTHROPIC_BASE_URL") or "").strip()
    if not base_url:
        raise SettingsError("!! no base URL: set ANTHROPIC_BASE_URL or pass --base-url.\n"
                            "   A push always sends the key, and the key must only go with the URL\n"
                            "   it was issued for, so the script will not reuse the robot's stored URL.")
    validate_base_url(base_url)
    key = os.environ.get("ANTHROPIC_API_KEY") or ""
    if not key:
        key = getpass.getpass("Claude API key (input hidden): ")
    key = key.strip()
    if not key:
        raise SettingsError("!! no API key: set ANTHROPIC_API_KEY or type the key at the prompt.")
    model = (args.model or os.environ.get("ANTHROPIC_MODEL") or "").strip()
    return base_url, key, model


# --- adb ---

def adb_cmd(serial, *args):
    return ["adb", "-s", serial] + list(args)


def _run(cmd, timeout):
    print("  $ " + " ".join(cmd), flush=True)
    try:
        return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    except FileNotFoundError:
        raise SettingsError("!! adb not found on PATH — install Android platform-tools "
                            "(brew install android-platform-tools) and re-run.")
    except subprocess.TimeoutExpired:
        raise SettingsError(f"!! adb timed out after {timeout}s: {' '.join(cmd)}\n"
                            "   The robot stopped answering. Check it is powered on and on Wi-Fi, then re-run.")


def ensure_reachable(serial):
    """Connect a TCP serial, then require `get-state` to say "device"."""
    detail = ""
    if ":" in serial:
        r = _run(["adb", "connect", serial], CONNECT_TIMEOUT)
        detail = (r.stdout + r.stderr).strip()
    r = _run(adb_cmd(serial, "get-state"), CONNECT_TIMEOUT)
    if r.returncode != 0 or r.stdout.strip() != "device":
        detail = "\n".join(s for s in (detail, (r.stdout + r.stderr).strip()) if s)
        raise SettingsError(
            f"!! robot not reachable over adb at {serial}\n"
            + (f"   adb said: {detail}\n" if detail else "")
            + "   Check the robot is powered on and on Wi-Fi (or plugged in over USB),\n"
              "   or pass --serial.")


def parse_forward_port(stdout):
    """`adb forward tcp:0 ...` prints the local port it allocated."""
    text = (stdout or "").strip()
    return int(text) if text.isdigit() else None


@contextmanager
def port_forward(serial):
    """Yields the https base URL of the launcher's port; the forward is always removed."""
    r = _run(adb_cmd(serial, "forward", "tcp:0", f"tcp:{LAUNCHER_HTTPS_PORT}"), ADB_TIMEOUT)
    port = parse_forward_port(r.stdout) if r.returncode == 0 else None
    if port is None:
        raise SettingsError(f"!! adb forward to tcp:{LAUNCHER_HTTPS_PORT} failed: "
                            f"{(r.stdout + r.stderr).strip() or 'no port printed'}")
    try:
        yield f"https://127.0.0.1:{port}"
    finally:
        try:
            r = _run(adb_cmd(serial, "forward", "--remove", f"tcp:{port}"), ADB_TIMEOUT)
            if r.returncode != 0:
                print(f"!! could not remove the adb forward tcp:{port}; "
                      f"run: adb -s {serial} forward --remove tcp:{port}", file=sys.stderr)
        except SettingsError as exc:
            print(f"{exc}\n!! the adb forward tcp:{port} may still be open", file=sys.stderr)


# --- HTTP ---

def _insecure_context():
    # The launcher's HTTPS port uses a self-signed certificate (HttpsSupport).
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    return ctx


def http_request(method, url, body=None, headers=None, timeout=HTTP_TIMEOUT):
    """One request, redirects not followed. Connection trouble of any kind is OSError."""
    parts = urlsplit(url)
    conn = http.client.HTTPSConnection(parts.hostname, parts.port or 443, timeout=timeout,
                                       context=_insecure_context())
    path = (parts.path or "/") + (f"?{parts.query}" if parts.query else "")
    try:
        conn.request(method, path, body=body, headers=headers or {})
        r = conn.getresponse()
        data = r.read()
    except http.client.HTTPException as exc:
        raise OSError(f"{url}: {type(exc).__name__}: {exc}") from exc
    finally:
        conn.close()
    return Response(r.status, {k.lower(): v for k, v in r.getheaders()}, data)


def status_from_location(location):
    """The ?status= message of a redirect Location, or ""."""
    values = parse_qs(urlsplit(location or "").query).get("status")
    return values[0] if values else ""


class _SettingsParser(HTMLParser):
    """The Claude save form's inputs, the model datalist, and the key status line."""

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.in_form = False
        self.in_datalist = False
        self.in_key_status = False
        self.fields = {}
        self.models = []
        self.key_status = ""

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if tag == "form":
            self.in_form = a.get("action") == SAVE_PATH
        elif tag == "input" and self.in_form and a.get("name"):
            self.fields[a["name"]] = a.get("value") or ""
        elif tag == "datalist":
            self.in_datalist = a.get("id") == "claude-models"
        elif tag == "option" and self.in_datalist and a.get("value"):
            self.models.append(a["value"])
        elif tag == "p" and a.get("id") == "claude-key-status":
            self.in_key_status = True

    def handle_endtag(self, tag):
        if tag == "form":
            self.in_form = False
        elif tag == "datalist":
            self.in_datalist = False
        elif tag == "p":
            self.in_key_status = False

    def handle_data(self, data):
        if self.in_key_status:
            self.key_status += data


SettingsPage = namedtuple("SettingsPage", "token model models key_status")


def fetch_page(base):
    try:
        r = http_request("GET", base + SETTINGS_PATH)
    except OSError as exc:
        raise SettingsError(f"!! could not reach the launcher's Settings page over the adb forward: {exc}\n"
                            "   Check the custom launcher is running on the robot, then re-run.")
    if r.status != 200:
        raise SettingsError(f"!! GET {SETTINGS_PATH} answered {r.status}; the launcher on the robot may "
                            "predate the Settings page — install the current launcher.")
    p = _SettingsParser()
    p.feed(r.body.decode("utf-8", "replace"))
    token = p.fields.get("t")
    if not token:
        raise SettingsError(f"!! the Settings page has no Claude form token (name=\"t\" in {SAVE_PATH}); "
                            "install the current launcher.")
    return SettingsPage(token, p.fields.get("model", ""), p.models, p.key_status.strip())


def post(base, path, fields):
    """POSTs a form with a fresh page token; returns the status line from the redirect."""
    token = fetch_page(base).token
    body = urlencode([("t", token)] + list(fields))
    try:
        r = http_request("POST", base + path, body=body,
                         headers={"Content-Type": "application/x-www-form-urlencoded"})
    except OSError as exc:
        raise SettingsError(f"!! POST {path} failed: {exc}\n"
                            "   Check the launcher is still running on the robot, then re-run.")
    if r.status not in (301, 302, 303) or "location" not in r.headers:
        raise SettingsError(f"!! POST {path} answered {r.status} instead of a redirect; "
                            "install the current launcher.")
    return status_from_location(r.headers["location"])


# --- the commands ---

def run_test(base):
    status = post(base, TEST_PATH, [])
    if not status.startswith("Connection works"):
        raise SettingsError(f"!! {status or 'the test gave no status'}")
    print(f"Test: {status}")


def run_models(base):
    status = post(base, MODELS_PATH, [])
    if status.startswith("Models not refreshed") or not status:
        raise SettingsError(f"!! {status or 'Refresh models gave no status'}")
    print(f"Models: {status}")
    models = fetch_page(base).models
    for m in models:
        print(f"  {m}")
    return models


def run_push(base, base_url, key, model):
    if not model:
        model = fetch_page(base).model
        if model:
            print(f"Keeping the stored model: {model}")
    print(f"Pushing base URL {base_url}, key {mask_key(key)}, model {model or '(none yet)'}")
    status = post(base, SAVE_PATH, [("base_url", base_url), ("key", key), ("model", model)])
    if not status.startswith("Saved"):
        raise SettingsError(f"!! {status or 'the save gave no status'}")
    print(redact(f"Save: {status}", key))
    if model:
        run_test(base)
    else:
        run_models(base)
        print("No model is set yet. Pick one above and re-run with --model <id> "
              "(or set ANTHROPIC_MODEL).")
    key_status = fetch_page(base).key_status
    if key_status:
        print(redact(f"Robot: {key_status}", key))


def parse_args(argv=None):
    ap = argparse.ArgumentParser(
        description="Push, test, or list the robot's Claude API settings over adb. "
                    "The key comes from ANTHROPIC_API_KEY or a hidden prompt, never from a flag.")
    ap.add_argument("command", nargs="?", default="push", choices=["push", "test", "models"],
                    help="push (default): save base URL, key, and model, then test; "
                         "test: run Test connection; models: refresh and print the model list")
    ap.add_argument("--serial", default=DEFAULT_SERIAL,
                    help=f"adb serial (default: {DEFAULT_SERIAL}; host:port is adb-connected first)")
    ap.add_argument("--base-url", help="Claude API base URL, https:// (default: $ANTHROPIC_BASE_URL)")
    ap.add_argument("--model", help="model id (default: $ANTHROPIC_MODEL, then the robot's stored model)")
    return ap.parse_args(argv)


def main(argv=None):
    args = parse_args(argv)
    key = ""
    try:
        if args.command == "push":
            base_url, key, model = resolve_push_inputs(args)
        elif args.base_url or args.model:
            raise SettingsError(f"!! --base-url and --model apply to push only, not {args.command}.")
        print(f"== reaching the robot at {args.serial} ==", flush=True)
        ensure_reachable(args.serial)
        with port_forward(args.serial) as base:
            if args.command == "push":
                run_push(base, base_url, key, model)
            elif args.command == "test":
                run_test(base)
            else:
                run_models(base)
    except SettingsError as exc:
        print(redact(exc, key), file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("\n!! interrupted; any adb forward this run opened was removed.", file=sys.stderr)
        return 130
    return 0


if __name__ == "__main__":
    sys.exit(main())
