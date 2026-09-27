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

The persona command (meeting plan U4, KTD11) posts the Conversation form:
--file pushes a text file as the persona (an empty file goes back to the
built-in text), --answers on|off sets the "answers when spoken to" switch.
Whichever is not given keeps what the page shows. The file is checked
against the 2,500-character cap before any adb command runs.

Usage:
  python3 scripts/robot-settings.py                      # push (the default), then test
  python3 scripts/robot-settings.py push --model claude-opus-5-5
  python3 scripts/robot-settings.py test                 # run Test connection
  python3 scripts/robot-settings.py models               # refresh and print the model list
  python3 scripts/robot-settings.py persona --file persona.txt
  python3 scripts/robot-settings.py persona --answers off
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
CONVERSATION_PATH = "/settings/conversation"

# ConversationSettings.MAX_PERSONA_CHARS: the launcher refuses more.
PERSONA_MAX_CHARS = 2500

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
    # Userinfo could carry a password, so this message never echoes the URL.
    if parts.username is not None or parts.password is not None:
        raise SettingsError("!! base URL must not contain a user name or password (user@host).\n"
                            "   The robot rejects it too. Fix --base-url or ANTHROPIC_BASE_URL and re-run.")
    try:
        bad_port = parts.port == 0 or parts.netloc.endswith(":")
    except ValueError:  # non-numeric or out of range
        bad_port = True
    if bad_port:
        raise SettingsError(f"!! base URL port must be a number from 1 to 65535, got: {url}\n"
                            "   Fix --base-url or ANTHROPIC_BASE_URL and re-run.")


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
    """The Claude save form's inputs, the model datalist, the key status line,
    and the Conversation form's persona box and switch."""

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.in_form = False
        self.in_datalist = False
        self.in_key_status = False
        self.in_conversation = False
        self.in_persona = False
        self.fields = {}
        self.models = []
        self.key_status = ""
        # None until the page shows a persona box; "" when it shows the built-in text.
        self.persona = None
        self.persona_default = False
        self.answers = None

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if tag == "form":
            self.in_form = a.get("action") == SAVE_PATH
            self.in_conversation = a.get("action") == CONVERSATION_PATH
        elif tag == "input" and self.in_form and a.get("name"):
            self.fields[a["name"]] = a.get("value") or ""
        elif tag == "input" and self.in_conversation and a.get("name") == "answers":
            self.answers = "checked" in a
        elif tag == "textarea" and self.in_conversation and a.get("name") == "persona":
            self.in_persona = True
            self.persona = ""
            self.persona_default = "data-default" in a
        elif tag == "datalist":
            self.in_datalist = a.get("id") == "claude-models"
        elif tag == "option" and self.in_datalist and a.get("value"):
            self.models.append(a["value"])
        elif tag == "p" and a.get("id") == "claude-key-status":
            self.in_key_status = True

    def handle_endtag(self, tag):
        if tag == "form":
            self.in_form = False
            self.in_conversation = False
        elif tag == "textarea":
            self.in_persona = False
        elif tag == "datalist":
            self.in_datalist = False
        elif tag == "p":
            self.in_key_status = False

    def handle_data(self, data):
        if self.in_key_status:
            self.key_status += data
        if self.in_persona:
            self.persona += data


SettingsPage = namedtuple("SettingsPage", "token model models key_status persona persona_default answers")


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
    return SettingsPage(token, p.fields.get("model", ""), p.models, p.key_status.strip(),
                        p.persona, p.persona_default, p.answers)


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

def describe_conversation(page):
    """One line on the stored persona and switch, or None for a launcher without them."""
    if page.persona is None or page.answers is None:
        return None
    persona = ("the built-in text" if page.persona_default
               else f"{len(page.persona.strip())} characters, the owner's")
    return f"Conversation: persona {persona}; answers when spoken to: {'on' if page.answers else 'off'}"


def run_test(base):
    status = post(base, TEST_PATH, [])
    if not status.startswith("Connection works"):
        raise SettingsError(f"!! {status or 'the test gave no status'}")
    print(f"Test: {status}")
    # So a persona edit on the page shows up here (meeting plan U4).
    conversation = describe_conversation(fetch_page(base))
    if conversation:
        print(conversation)


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


def resolve_persona_inputs(args):
    """(persona text or None, answers bool or None); every check runs before any adb call."""
    if args.file is None and args.answers is None:
        raise SettingsError("!! persona needs --file <text file> and/or --answers on|off.")
    text = None
    if args.file is not None:
        try:
            with open(args.file, encoding="utf-8") as f:
                text = f.read()
        except OSError as exc:
            raise SettingsError(f"!! could not read the persona file {args.file}: {exc.strerror or exc}")
        except UnicodeDecodeError:
            raise SettingsError(f"!! the persona file {args.file} is not UTF-8 text.")
        # The launcher's own rule (ClaudeSettings.checkPersona): LF endings, trimmed, then the cap.
        length = len(text.replace("\r\n", "\n").strip())
        if length > PERSONA_MAX_CHARS:
            raise SettingsError(f"!! the persona is {length} characters; the robot keeps at most "
                                f"{PERSONA_MAX_CHARS}. Shorten {args.file} and re-run.")
    answers = None if args.answers is None else args.answers == "on"
    return text, answers


def run_persona(base, text, answers):
    page = fetch_page(base)
    if page.persona is None or page.answers is None:
        raise SettingsError("!! the Settings page has no Conversation section; install the current launcher.")
    if text is None:
        # Keep what is stored: "" while the page shows the built-in text, so a
        # switch-only change never turns the default into the owner's text.
        text = "" if page.persona_default else page.persona
    if answers is None:
        answers = page.answers
    fields = [("persona", text)] + ([("answers", "on")] if answers else [])
    status = post(base, CONVERSATION_PATH, fields)
    if not status.startswith(("Conversation settings saved", "Saved")):
        raise SettingsError(f"!! {status or 'the save gave no status'}")
    print(f"Persona: {status}")
    # Read back what the robot stored rather than echoing what was sent.
    conversation = describe_conversation(fetch_page(base))
    if conversation:
        print(conversation)


def parse_args(argv=None):
    ap = argparse.ArgumentParser(
        description="Push, test, or list the robot's Claude API settings over adb, or set the "
                    "conversation persona and switch. The key comes from ANTHROPIC_API_KEY or a hidden "
                    "prompt, never from a flag.")
    ap.add_argument("command", nargs="?", default="push", choices=["push", "test", "models", "persona"],
                    help="push (default): save base URL, key, and model, then test; "
                         "test: run Test connection; models: refresh and print the model list; "
                         "persona: push --file as the persona text and/or set --answers")
    ap.add_argument("--serial", default=DEFAULT_SERIAL,
                    help=f"adb serial (default: {DEFAULT_SERIAL}; host:port is adb-connected first)")
    ap.add_argument("--base-url", help="Claude API base URL, https:// (default: $ANTHROPIC_BASE_URL)")
    ap.add_argument("--model", help="model id (default: $ANTHROPIC_MODEL, then the robot's stored model)")
    ap.add_argument("--file", help="persona only: a UTF-8 text file to push as the persona "
                                   f"(at most {PERSONA_MAX_CHARS} characters; empty goes back to the built-in text)")
    ap.add_argument("--answers", choices=["on", "off"],
                    help="persona only: the \"answers when spoken to\" switch (off: only the wake word "
                         "opens a conversation)")
    return ap.parse_args(argv)


def main(argv=None):
    args = parse_args(argv)
    key = ""
    try:
        if (args.file is not None or args.answers is not None) and args.command != "persona":
            raise SettingsError(f"!! --file and --answers apply to persona only, not {args.command}.")
        if args.command == "push":
            base_url, key, model = resolve_push_inputs(args)
        elif args.base_url or args.model:
            raise SettingsError(f"!! --base-url and --model apply to push only, not {args.command}.")
        if args.command == "persona":
            persona_text, persona_answers = resolve_persona_inputs(args)
        print(f"== reaching the robot at {args.serial} ==", flush=True)
        ensure_reachable(args.serial)
        with port_forward(args.serial) as base:
            if args.command == "push":
                run_push(base, base_url, key, model)
            elif args.command == "test":
                run_test(base)
            elif args.command == "persona":
                run_persona(base, persona_text, persona_answers)
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
