#!/usr/bin/env python3
"""Compare the speaker models from the launcher's voice evaluation lines.

Owner 2026-10-03. With debug.miko3.voice_eval=1 (scripts/push-voice-eval-models.py) the
launcher logs, per clean conversation answer and per model (campplus is the bundled CAM++):

  voice eval: model=NAME best=0.712 second=0.540 margin=0.172 ms=812

and, when the conversation settles who was speaking (a name given or confirmed) and the answer
is enrolled to them:

  voice eval: truth model=NAME true=0.701 other_best=0.655

true is the answer against that person's own prints (before it joins them), other_best the
best against everyone else's: a good model keeps true high and other_best low. No audio, names
or ids are in either line.

Per model this reports the answers seen and their timing, the mean true and other_best, the
separation (true - other_best) spread, and an equal-error-rate estimate taking the true scores
as genuine trials and the other_best scores as impostor trials. It recommends the model with
the lowest EER (ties: the wider mean separation, then the faster). Needs truth lines from at
least two enrolled people (other_best is nan until a second person has prints).

Usage:
  python3 scripts/voice-eval-report.py LOG [LOG ...]
  adb logcat -d -s ListenEngine | python3 scripts/voice-eval-report.py -
"""
import argparse
import math
import re
import statistics
import sys
from pathlib import Path

ANSWER_RE = re.compile(r"voice eval: model=(\S+) best=(\S+) second=(\S+) margin=(\S+) ms=(\d+)")
TRUTH_RE = re.compile(r"voice eval: truth model=(\S+) true=(\S+) other_best=(\S+)")
# Too few trials of either kind and the EER says nothing.
MIN_TRIALS = 3


def _num(raw):
    try:
        v = float(raw)
    except ValueError:
        return None
    return None if math.isnan(v) else v


def parse(text):
    """{model: {"ms": [...], "margin": [...], "true": [...], "other": [...], "sep": [...]}}."""
    out = {}

    def model(name):
        return out.setdefault(name, {"ms": [], "margin": [], "true": [], "other": [], "sep": []})

    for line in text.splitlines():
        m = TRUTH_RE.search(line)
        if m:
            d = model(m.group(1))
            t, o = _num(m.group(2)), _num(m.group(3))
            if t is not None:
                d["true"].append(t)
            if o is not None:
                d["other"].append(o)
            if t is not None and o is not None:
                d["sep"].append(t - o)
            continue
        m = ANSWER_RE.search(line)
        if m:
            d = model(m.group(1))
            d["ms"].append(int(m.group(5)))
            mg = _num(m.group(4))
            if mg is not None:
                d["margin"].append(mg)
    return out


def eer(genuine, impostor):
    """(eer, threshold): the rate where false accepts (impostor >= t) meet false rejects
    (genuine < t), over every observed score as a threshold; None with too few trials."""
    if len(genuine) < MIN_TRIALS or len(impostor) < MIN_TRIALS:
        return None
    best = None
    for t in sorted(set(genuine) | set(impostor)):
        far = sum(1 for s in impostor if s >= t) / len(impostor)
        frr = sum(1 for s in genuine if s < t) / len(genuine)
        gap = abs(far - frr)
        if best is None or gap < best[0]:
            best = (gap, (far + frr) / 2, t)
    return best[1], best[2]


def _mean(xs):
    return statistics.fmean(xs) if xs else None


def summarise(data):
    rows = []
    for name, d in sorted(data.items()):
        e = eer(d["true"], d["other"])
        rows.append({
            "model": name,
            "answers": len(d["ms"]),
            "ms_median": statistics.median(d["ms"]) if d["ms"] else None,
            "truths": len(d["true"]),
            "true_mean": _mean(d["true"]),
            "other_mean": _mean(d["other"]),
            "sep_mean": _mean(d["sep"]),
            "sep_min": min(d["sep"]) if d["sep"] else None,
            "sep_median": statistics.median(d["sep"]) if d["sep"] else None,
            "sep_positive": (sum(1 for s in d["sep"] if s > 0) / len(d["sep"])) if d["sep"] else None,
            "eer": e[0] if e else None,
            "eer_threshold": e[1] if e else None,
        })
    return rows


def recommend(rows):
    """The model with the lowest EER (ties: wider mean separation, then faster), or None."""
    scored = [r for r in rows if r["eer"] is not None]
    if not scored:
        return None
    return min(scored, key=lambda r: (round(r["eer"], 4), -(r["sep_mean"] or 0.0),
                                      r["ms_median"] if r["ms_median"] is not None else 1e9))["model"]


def _f(v, fmt="{:.3f}"):
    return "-" if v is None else fmt.format(v)


def format_report(rows):
    if not rows:
        return "no 'voice eval:' lines found (is debug.miko3.voice_eval=1 set and the launcher restarted?)"
    head = ("model", "answers", "ms(med)", "truths", "true", "other_best", "sep(mean)", "sep(med)", "sep(min)",
            "sep>0", "EER", "EER at")
    w = max(len(head[0]), *(len(r["model"]) for r in rows))
    lines = [f"{head[0]:<{w}}  " + "  ".join(f"{h:>10}" for h in head[1:])]
    for r in rows:
        cells = (str(r["answers"]), _f(r["ms_median"], "{:.0f}"), str(r["truths"]),
                 _f(r["true_mean"]), _f(r["other_mean"]), _f(r["sep_mean"]), _f(r["sep_median"]), _f(r["sep_min"]),
                 _f(r["sep_positive"], "{:.0%}"), _f(r["eer"], "{:.1%}"), _f(r["eer_threshold"]))
        lines.append(f"{r['model']:<{w}}  " + "  ".join(f"{c:>10}" for c in cells))
    best = recommend(rows)
    if best is None:
        lines.append(f"recommendation: not enough data (each model needs {MIN_TRIALS}+ truth lines with an "
                     "other_best, i.e. two people enrolled and a few named conversations each)")
    else:
        lines.append(f"recommendation: {best} (lowest EER; ties go to the wider separation, then the faster)")
    return "\n".join(lines)


def main(argv=None):
    ap = argparse.ArgumentParser(description="Compare the speaker models from 'voice eval:' log lines.")
    ap.add_argument("logs", nargs="+", help="log files, or - for stdin")
    args = ap.parse_args(argv)
    text = ""
    for p in args.logs:
        text += sys.stdin.read() if p == "-" else Path(p).read_text(errors="replace")
        text += "\n"
    print(format_report(summarise(parse(text))))
    return 0


if __name__ == "__main__":
    sys.exit(main())
