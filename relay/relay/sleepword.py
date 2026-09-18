"""The two matchers that can end a conversation. Only the first one is live today.

FarewellPhraseMatcher (live) watches the MODEL's own reply text for an exact farewell
phrase, default "talk to you later". It is what ends a conversation on this build: live
mode transcribes nothing the person says (docs/model-server-protocol.md), so the sleep-word
matcher below has no input at all. Prompt-declared tools were tried first as the model's
"I am done" signal -- a plain instruction naming a tool, then a <TOOLS>[...]</TOOLS> JSON
block -- and neither ever produced a function call; the model just spoke the instruction.
Telling the persona to end its farewell with an exact phrase worked every time, so that is
what the relay listens for. The phrase lives in two places that must agree: the relay's
Config (--farewell-phrase, default below) and the persona file it is told to say it in.

SleepWordMatcher (dormant) is the KTD2 "Goodbye Miko" matcher over the PERSON's transcript.
It is kept, with its tests, because a local recognizer could feed it the same two methods
if a transcript ever appears; the engine no longer calls it against the real server. The
model's own words are never fed to it, so the robot cannot put itself to sleep that way.

Sleep-word matching, over the normalized transcript: a farewell token (goodbye, bye, good
night) within two words of a "Miko" variant from a seeded list, anywhere a six-word window
can slide. Words the list does not know are scored with difflib similarity; a pair scoring
at least MATCH_THRESHOLD matches, one scoring from NEAR_MISS_THRESHOLD up to it is a near
miss for the log, so the variant list can grow from real transcripts.

Timing: a finished transcript (user_text, or the running one at user_end) is decided at
once. A running transcript is decided only once it is `settle` seconds old, because the
recognizer's latest words are the least certain; the caller polls at next_due().

    matcher = FarewellPhraseMatcher(config.farewell_phrase)     # live
    decision = matcher.feed(assistant_text_delta)               # usually None
    matcher.reset()

    matcher = SleepWordMatcher()                                # dormant
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

# Decision.source: which matcher decided. "assistant_text" is the live one; "final" and
# "partial" are the dormant transcript matcher's finished and running transcripts.
ASSISTANT_TEXT = "assistant_text"

# The words the persona is told to end its farewell with (personas/default.txt and
# personas/full.txt). Change one and you must change the other.
DEFAULT_FAREWELL_PHRASE = "talk to you later"

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
    score: int  # 0-100: the sleep word's weaker similarity; always 100 for a phrase match
    words: tuple = ()  # the matched words in order: the phrase, or the farewell-and-name pair
    transcript: str = ""  # the text the decision was made on, normalized
    source: str = ""  # ASSISTANT_TEXT (live), or "final" / "partial" (dormant matcher)


def phrase_words(text):
    """Lower-case words with punctuation and whitespace dropped, for phrase matching.
    Unlike normalize() it joins nothing, so a phrase is compared exactly as written."""
    text = text.lower().replace("'", "").replace("\u2019", "")
    return re.sub(r"[^a-z0-9]+", " ", text).split()


class FarewellPhraseMatcher:
    """LIVE (see the module docstring): the model's own farewell phrase ends the
    conversation. Fed one assistant_text_delta at a time; returns a Decision the first time
    the phrase completes and None otherwise, so a phrase split across deltas -- even
    mid-word -- still matches. Case, punctuation and whitespace are ignored; the phrase must
    appear as whole words. An empty phrase never matches (it disables the feature).

    It is fed the model's text only. The person's words never reach it: there is no
    transcript to reach it with, and nothing the person says should be able to end the
    conversation by being quoted back."""

    def __init__(self, phrase=DEFAULT_FAREWELL_PHRASE):
        self._phrase = tuple(phrase_words(phrase))
        self._text = ""
        self._keep = 8 * (len(phrase) + 1)  # tail kept, however the deltas split the phrase
        self._matched = False

    def reset(self):
        """Forget the text so far: call when a conversation starts."""
        self._text = ""
        self._matched = False

    def feed(self, delta):
        """One assistant_text_delta. A Decision the first time the phrase completes, else
        None; after a match it stays quiet until reset()."""
        if self._matched or not self._phrase:
            return None
        self._text += delta
        words = phrase_words(self._text)
        # Trim only after matching, so one big delta carrying the phrase is never cut.
        self._text = self._text[-self._keep:]
        n = len(self._phrase)
        if not any(tuple(words[i:i + n]) == self._phrase
                   for i in range(len(words) - n + 1)):
            return None
        self._matched = True
        return Decision(MATCH, 100, self._phrase, " ".join(words), ASSISTANT_TEXT)


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
