#!/usr/bin/env python3
"""Explore conversation scorecard from logcat captures or pulled learn.log files.

Reads the brain's `turn:` records (one per conversation turn, since 2026-10-03):

  turn: c=<conversation> t=<turn> open=<y|n> addr=<y|n|?> req=<ms> line=<ms> sound=<ms> done=<ms>
        spec=<y|n|?> tools=<a+b|-|?> tries=<1|2> retry=<why|-> fail=<why|-> unans=<n> nowords=<n>
        reask=<y|n> end=<reason|-> meet=<call|cue|roaming|claude-pick|other> faceless=<y|n>
        faceseen=<y|n> replies=<n>

The ms are from t0, when the answer's words reached the brain (the request itself for the
opener or a call's own words): to the request going out (negative when the speculation on the
provisional answer was used), to the line ready, to the line handed to speech, to speech
finished. A conversation's last record carries its end reason (goodbye, noreply, walkedoff,
notaddr, instruction, failure, stall, cut, eyes, muted, other). Every record also names the
meeting the conversation came from, whether it opened with nobody in view (faceless), whether a
usable face turned up later, and how many turns so far answered words the partner said to him
(a call's own words not counted): a conversation with replies=0 at its end was likely nobody.

From logcat captures it also reads what the learn.log does not keep: the launcher's
"answer over: no words (...)" details (ListenEngine) and the Claude adapter's "turn request
with N message(s) ... in N ms (... line at N ms ...)" lines (ExploreClaude). Older captures
with no turn: records still get those and a conversation count.

Reported: p50/p95 per latency stage (all turns, openers, replies), the unaddressed rate,
the cut-off rate (conversations the robot ended for its own reasons: failure, stall, cut,
eyes, muted, not the person), end reasons and early-end reasons (conversations over within
two addressed turns), tool use, retries and failures, wordless answers.

The robot never reads this. It is for the coding agent improving the conversation code.

Usage:
  scripts/chat-report.py LOG [LOG ...] [-o report.md] [--json report.json|-] [--year 2026] [--tz ZONE]
"""
import argparse
import datetime as dt
import importlib.util
import json
import re
import sys
from collections import Counter
from pathlib import Path

HERE = Path(__file__).resolve().parent


def _load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


nav = _load("nav_report_for_chat", HERE / "nav-report.py")

TURN_RE = re.compile(r"^turn: (c=.*)$")
KV_RE = re.compile(r"(\w+)=(\S+)")
STAGES = ("req", "line", "sound", "done")
# Ends the robot chose for reasons of its own, not the person's.
CUT_ENDS = ("failure", "stall", "cut", "eyes", "muted")
NOWORDS_RE = re.compile(r"answer over: no words(?: \((.*)\))?$")
REQUEST_RE = re.compile(r"^(speculative )?turn request with (\d+) message\(s\).*? in (\d+) ms"
                        r"(?: \(settings (\d+) ms, line at (-|\d+) ms)?")
OVER_RE = re.compile(r"^conversation over after (\d+) turn")


def num(v):
    try:
        return int(v)
    except (TypeError, ValueError):
        return None


def pct(xs, q):
    """Nearest-rank percentile (q in 0..100) of a non-empty list, else None."""
    xs = sorted(x for x in xs if x is not None)
    if not xs:
        return None
    k = max(0, min(len(xs) - 1, int(-(-q * len(xs) // 100)) - 1))
    return xs[k]


def stage_stats(turns):
    out = {}
    for s in STAGES:
        xs = [t[s] for t in turns if t[s] is not None]
        out[s] = {"n": len(xs), "p50": pct(xs, 50), "p95": pct(xs, 95)}
    return out


def parse_turn(body):
    kv = dict(KV_RE.findall(body))
    tools = kv.get("tools", "?")
    return {
        "c": num(kv.get("c")), "t": num(kv.get("t")), "open": kv.get("open") == "y", "addr": kv.get("addr", "?"),
        "req": num(kv.get("req")), "line": num(kv.get("line")), "sound": num(kv.get("sound")),
        "done": num(kv.get("done")), "spec": kv.get("spec", "?"),
        "tools": [] if tools in ("-", "?") else tools.split("+"), "tools_known": tools != "?",
        "tries": num(kv.get("tries")) or 1, "retry": kv.get("retry", "-"), "fail": kv.get("fail", "-"),
        "unans": num(kv.get("unans")) or 0, "nowords": num(kv.get("nowords")) or 0, "reask": kv.get("reask") == "y",
        "end": kv.get("end", "-"), "meet": kv.get("meet", "?"), "faceless": kv.get("faceless") == "y",
        "replies": num(kv.get("replies")),
    }


def nowords_detail(text):
    """The launcher's why, as numbers: answer length, deaf-clipped, how soon after the deaf window."""
    if not text:
        return {"detail": False}
    m_len = re.search(r"answer (\d+) ms long", text)
    m_began = re.search(r"began (\d+) ms after the deaf window", text)
    return {"detail": True, "answer_ms": int(m_len.group(1)) if m_len else None,
            "deaf_clipped": "not deaf-clipped" not in text and "deaf-clipped" in text,
            "began_after_deaf_ms": int(m_began.group(1)) if m_began else None}


def analyze(lines):
    turns, nowords, requests, overs = [], [], [], []
    for p in lines:
        m = TURN_RE.match(p.msg)
        if m:
            t = parse_turn(m.group(1))
            t["pid"], t["time"] = p.pid, p.ts
            turns.append(t)
            continue
        m = NOWORDS_RE.search(p.msg)
        if m and "conversation listen" in p.msg:
            nowords.append(nowords_detail(m.group(1)))
            continue
        m = REQUEST_RE.match(p.msg)
        if m and p.tag.startswith("Explore"):
            requests.append({"speculative": bool(m.group(1)), "messages": int(m.group(2)), "ms": int(m.group(3)),
                             "line_at": num(m.group(5))})
            continue
        m = OVER_RE.match(p.msg)
        if m:
            overs.append(int(m.group(1)))
    return report(turns, nowords, requests, overs)


def report(turns, nowords, requests, overs):
    convs = {}
    for t in turns:
        convs.setdefault((t["pid"], t["c"]), []).append(t)
    ends = Counter()
    early = Counter()
    for recs in convs.values():
        last = recs[-1]
        end = last["end"] if last["end"] != "-" else "unclosed"
        ends[end] += 1
        if sum(r["addr"] == "y" for r in recs) <= 2:
            early[end] += 1
    closed = sum(ends.values()) - ends.get("unclosed", 0)
    by_meet = {}
    for recs in convs.values():
        last = recs[-1]
        m = by_meet.setdefault(last["meet"], {"conversations": 0, "faceless": 0, "replied": 0, "faceless_replied": 0,
                                              "replies": []})
        replied = (last["replies"] or 0) > 0
        m["conversations"] += 1
        m["faceless"] += last["faceless"]
        m["replied"] += replied
        m["faceless_replied"] += last["faceless"] and replied
        m["replies"].append(last["replies"] or 0)
    for m in by_meet.values():
        m["replies_p50"] = pct(m.pop("replies"), 50)
    replied_all = sum(m["replied"] for m in by_meet.values())
    cut = sum(ends.get(e, 0) for e in CUT_ENDS)
    addr = Counter(t["addr"] for t in turns)
    judged = addr["y"] + addr["n"]
    tools = Counter(name for t in turns for name in t["tools"])
    known = [t for t in turns if t["tools_known"]]
    clipped = [n for n in nowords if n["detail"]]
    out = {
        "turns": len(turns),
        "conversations": len(convs) if convs else len(overs),
        "conversations_from": "turn records" if convs else "conversation over notes",
        "addressed": dict(addr),
        "unaddressed_rate": addr["n"] / judged if judged else None,
        "latency": {"all": stage_stats(turns), "opener": stage_stats([t for t in turns if t["open"]]),
                    "reply": stage_stats([t for t in turns if not t["open"] and t["addr"] == "y"])},
        "speculative_used": sum(t["spec"] == "y" for t in turns),
        "end_reasons": dict(ends),
        "early_end_reasons": dict(early),
        "cut_off": cut,
        "cut_off_rate": cut / closed if closed else None,
        "by_meeting": by_meet,
        "replied_rate": replied_all / len(convs) if convs else None,
        "tools": dict(tools),
        "turns_with_tools": sum(bool(t["tools"]) for t in known),
        "tools_known_turns": len(known),
        "retried": sum(t["tries"] > 1 for t in turns),
        "retry_reasons": dict(Counter(t["retry"] for t in turns if t["retry"] != "-")),
        "failures": dict(Counter(t["fail"] for t in turns if t["fail"] != "-")),
        "unanswered_listens": sum(t["unans"] for t in turns),
        "wordless_answers": sum(t["nowords"] for t in turns),
        "reasks": sum(t["reask"] for t in turns),
        "no_words_lines": len(nowords),
        "no_words_detail": {
            "n": len(clipped), "deaf_clipped": sum(n["deaf_clipped"] for n in clipped),
            "answer_ms_p50": pct([n["answer_ms"] for n in clipped], 50),
            "began_after_deaf_ms_p50": pct([n["began_after_deaf_ms"] for n in clipped], 50),
        },
        "claude_requests": {
            "n": len(requests), "speculative": sum(r["speculative"] for r in requests),
            "ms_p50": pct([r["ms"] for r in requests if not r["speculative"]], 50),
            "ms_p95": pct([r["ms"] for r in requests if not r["speculative"]], 95),
            "line_at_p50": pct([r["line_at"] for r in requests if not r["speculative"]], 50),
            "line_at_p95": pct([r["line_at"] for r in requests if not r["speculative"]], 95),
        },
    }
    return out


def fmt(v, pct_=False):
    if v is None:
        return "-"
    if pct_:
        return f"{100 * v:.0f}%"
    return str(v)


def render_markdown(r, sources):
    lines = ["# Explore conversation report", "", "Sources: " + ", ".join(str(s) for s in sources), ""]
    lines += [f"- Conversations: {r['conversations']} ({r['conversations_from']}); turns: {r['turns']}",
              f"- Addressed: {r['addressed']}; unaddressed rate {fmt(r['unaddressed_rate'], True)}",
              f"- Cut-off (robot ended it: {', '.join(CUT_ENDS)}): {r['cut_off']} "
              f"({fmt(r['cut_off_rate'], True)} of closed conversations)",
              f"- End reasons: {r['end_reasons']}",
              f"- Early ends (<= 2 addressed turns): {r['early_end_reasons']}",
              f"- Replied to (the partner said something to him): {fmt(r['replied_rate'], True)}",
              f"- By meeting (conversations, faceless, replied, faceless and replied, median replies): "
              f"{r['by_meeting']}",
              f"- Speculation used: {r['speculative_used']}",
              f"- Tools: {r['tools']} ({r['turns_with_tools']} of {r['tools_known_turns']} turns with a known round)",
              f"- Retried: {r['retried']} {r['retry_reasons']}; failures: {r['failures']}",
              f"- Unanswered listens {r['unanswered_listens']}, wordless answers {r['wordless_answers']}, "
              f"re-asks {r['reasks']}; launcher no-words lines {r['no_words_lines']} {r['no_words_detail']}",
              f"- Claude requests (logcat): {r['claude_requests']}", "",
              "## Latency from the answer's words (ms)", "",
              "| turns | stage | n | p50 | p95 |", "|---|---|---|---|---|"]
    for group in ("all", "opener", "reply"):
        for s in STAGES:
            st = r["latency"][group][s]
            lines.append(f"| {group} | {s} | {st['n']} | {fmt(st['p50'])} | {fmt(st['p95'])} |")
    return "\n".join(lines) + "\n"


def build(paths, year, tz):
    raws = []
    for path in paths:
        with open(path, errors="replace") as f:
            raws.extend(f)
    return analyze(nav.parse_lines(raws, year, tz))


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("logs", nargs="+", help="logcat captures (-v time or -v epoch) or pulled learn.log files")
    ap.add_argument("-o", "--out", help="markdown report path (default: stdout)")
    ap.add_argument("--json", help="JSON report path, or - for stdout")
    ap.add_argument("--year", type=int, default=dt.date.today().year, help="year for -v time lines")
    ap.add_argument("--tz", default=nav.DEFAULT_TZ, help="the robot's local zone (epoch lines)")
    args = ap.parse_args(argv)
    r = build(args.logs, args.year, args.tz)
    md = render_markdown(r, args.logs)
    if args.out:
        Path(args.out).write_text(md)
    elif args.json != "-":
        sys.stdout.write(md)
    if args.json:
        text = json.dumps(r, indent=1, default=str)
        if args.json == "-":
            sys.stdout.write(text + "\n")
        else:
            Path(args.json).write_text(text + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
