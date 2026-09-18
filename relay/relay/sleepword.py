"""The sleep-word matcher (plan KTD2): does the person's transcript say "Goodbye Miko"?

The relay feeds it the person's words only, from whatever produces them: today the model
server's user_text_delta / user_text events (U3); a local recognizer could call the same
two methods if U3's transcript ever has to be replaced. The model's own words
(assistant_text_delta) are never fed in, so the robot cannot put itself to sleep.

Matching, over the normalized transcript: a farewell token (goodbye, bye, good night)
within two words of a "Miko" variant from a seeded list, anywhere a six-word window can
slide. Words the list does not know are scored with difflib similarity; a pair scoring at
least MATCH_THRESHOLD matches, one scoring from NEAR_MISS_THRESHOLD up to it is a near
miss for the log, so the variant list can grow from real transcripts.

Timing: a finished transcript (user_text, or the running one at user_end) is decided at
once. A running transcript is decided only once it is `settle` seconds old, because the
recognizer's latest words are the least certain; the caller polls at next_due().

    matcher = SleepWordMatcher()
    decision = matcher.feed_partial(running_transcript, now)   # usually None
    decision = matcher.poll(now)                               # at matcher.next_due()
    decision = matcher.feed_final(user_text, now)
    matcher.reset()                                            # after each turn
"""
import difflib
import re
from dataclasses import dataclass

MATCH = "match"
NEAR_MISS = "near_miss"
NONE = "none"

FAREWELLS = ("goodbye", "bye", "goodnight")  # "good bye" and "good night" are joined first
MIKO_VARIANTS = ("miko", "mico", "meeko", "meko", "mikko", "niko", "nico", "mika", "mica",
                 "meco", "micko", "mikoh", "meekoh", "miiko", "myko", "mikou")
WINDOW_WORDS = 6
MAX_DISTANCE = 2  # the farewell and the name at most this many words apart
MATCH_THRESHOLD = 0.85
NEAR_MISS_THRESHOLD = 0.70
SETTLE_SECONDS = 0.5

_JOINED = {("good", "bye"): "goodbye", ("good", "night"): "goodnight"}


@dataclass(frozen=True)
class Decision:
    kind: str  # MATCH, NEAR_MISS or NONE
    score: int  # 0-100: the weaker of the farewell's and the name's similarity
    words: tuple = ()  # the words of the best farewell-and-name pair, in order
    transcript: str = ""  # the transcript the decision was made on
    source: str = ""  # "final" or "partial"


def normalize(text):
    """Lower-case words with punctuation dropped; "good bye" and "good night" joined."""
    text = text.lower().replace("'", "").replace("’", "")
    raw = re.sub(r"[^a-z0-9]+", " ", text).split()
    words = []
    for word in raw:
        if words and (words[-1], word) in _JOINED:
            words[-1] = _JOINED[(words[-1], word)]
        else:
            words.append(word)
    return words


def _similarity(word, choices):
    if word in choices:
        return 1.0
    return max(difflib.SequenceMatcher(None, word, choice).ratio() for choice in choices)


def _best(words, start=0, variants=MIKO_VARIANTS):
    """Best (score, lo, hi) over farewell-and-name pairs using some word at index >= start;
    None if no pair reaches the near-miss floor. A name may be split across two words."""
    best = None
    # A pair's last word is at most MAX_DISTANCE + 1 past its farewell (a split name),
    # so farewells before this index only form pairs that end before start.
    for i in range(max(0, start - MAX_DISTANCE - 1), len(words)):
        farewell = _similarity(words[i], FAREWELLS)
        if farewell < NEAR_MISS_THRESHOLD:
            continue
        for j in range(max(0, i - MAX_DISTANCE), min(len(words), i + MAX_DISTANCE + 1)):
            if j == i:
                continue
            names = [(words[j], j)]
            if j + 1 < len(words) and j + 1 != i:
                names.append((words[j] + words[j + 1], j + 1))
            for name, last in names:
                lo, hi = min(i, j), max(i, last)
                if hi < start or hi - lo >= WINDOW_WORDS:
                    continue
                pair = min(farewell, _similarity(name, variants))
                if best is None or pair > best[0]:
                    best = (pair, lo, hi)
    if best is None or best[0] < NEAR_MISS_THRESHOLD:
        return None
    return best


def score(text, variants=MIKO_VARIANTS):
    """Decide one finished transcript on its own (no timing, no memory)."""
    words = normalize(text)
    best = _best(words, 0, variants)
    return _decision(best, words, text, "final")


def _decision(best, words, text, source):
    if best is None:
        return Decision(NONE, 0, (), text, source)
    value, lo, hi = best
    kind = MATCH if value >= MATCH_THRESHOLD else NEAR_MISS
    return Decision(kind, round(value * 100), tuple(words[lo:hi + 1]), text, source)


class SleepWordMatcher:
    """Streaming matcher for one conversation. Returns a Decision only when there is
    something to act on or log (a match, or a new near miss); None otherwise. After a
    match it stays quiet until reset()."""

    def __init__(self, variants=MIKO_VARIANTS, settle=SETTLE_SECONDS):
        self._variants = tuple(variants)
        self._settle = settle
        self._pending = []  # (arrival time, running transcript), oldest first
        self._words = []  # the words already decided on, so each pair is judged once
        self._matched = False

    def reset(self):
        """Forget the turn: call after the person's turn is over (user_text)."""
        self._pending.clear()
        self._words = []
        self._matched = False

    def feed_partial(self, transcript, now):
        """The person's running transcript for the turn so far, as of `now`."""
        decision = self.poll(now)
        if not self._matched:
            self._pending.append((now, transcript))
        return decision

    def feed_final(self, transcript, now):
        """The person's finished transcript for the turn: decided at once."""
        self._pending.clear()
        if self._matched:
            return None
        return self._evaluate(transcript, "final")

    def poll(self, now):
        """Decide the newest running transcript that is at least `settle` seconds old."""
        ripe = None
        while self._pending and self._pending[0][0] + self._settle <= now + 1e-9:
            ripe = self._pending.pop(0)
        if ripe is None or self._matched:
            return None
        return self._evaluate(ripe[1], "partial")

    def next_due(self):
        """When poll() next has something to decide (None if nothing is pending)."""
        return self._pending[0][0] + self._settle if self._pending else None

    def _evaluate(self, transcript, source):
        words = normalize(transcript)
        # Only pairs touching a word not already judged: a word the recognizer revised
        # (a partial "mi" completed to "miko") counts as new.
        start = 0
        while (start < len(words) and start < len(self._words)
               and words[start] == self._words[start]):
            start += 1
        self._words = words
        if start >= len(words):
            return None
        decision = _decision(_best(words, start, self._variants), words, transcript, source)
        if decision.kind == NONE:
            return None
        if decision.kind == MATCH:
            self._matched = True
        return decision
