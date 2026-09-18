"""Tests for relay/relay/sleepword.py: both matchers in it (plan U5).

FarewellPhraseMatcher is the live one: it watches the model's own reply text for the
configured farewell phrase, because live mode has no user transcript to match.
SleepWordMatcher is the dormant "Goodbye Miko" transcript matcher (KTD2), kept for a future
recognizer; it is fed the person's transcript only and decides "match", "near_miss" or
"none". Time is passed in explicitly, so the half-second settling rule is tested without
sleeping.
"""
import sys
import unittest
from pathlib import Path

RELAY_ROOT = Path(__file__).resolve().parents[1]
if str(RELAY_ROOT) not in sys.path:
    sys.path.insert(0, str(RELAY_ROOT))

from relay.sleepword import (  # noqa: E402
    ASSISTANT_TEXT,
    DEFAULT_FAREWELL_PHRASE,
    MATCH,
    NEAR_MISS,
    NONE,
    FarewellPhraseMatcher,
    SleepWordMatcher,
    normalize,
    phrase_words,
    score,
)


class NormalizeTests(unittest.TestCase):
    def test_lowercases_strips_punctuation_and_joins_farewells(self):
        self.assertEqual(normalize("Good-bye, MIKO!"), ["goodbye", "miko"])
        self.assertEqual(normalize("Good night... Miko."), ["goodnight", "miko"])
        self.assertEqual(normalize("  bye   bye  "), ["bye", "bye"])

    def test_apostrophes_do_not_split_words(self):
        self.assertEqual(normalize("Miko's friend"), ["mikos", "friend"])


class ScoreTests(unittest.TestCase):
    def assertKind(self, text, kind):
        decision = score(text)
        self.assertEqual(decision.kind, kind, f"{text!r} -> {decision}")
        return decision

    def test_farewell_next_to_a_miko_variant_matches(self):
        for text in ("goodbye mico", "bye meeko", "good night miko", "Goodbye, Miko.",
                     "ok bye bye niko", "goodbye my miko", "miko goodbye", "bye mikko"):
            with self.subTest(text=text):
                self.assertEqual(self.assertKind(text, MATCH).score, 100)

    def test_fuzzy_variant_matches(self):
        # Not in the seeded list, but within the ~85 percent similarity fallback.
        self.assertKind("goodbye mikoo", MATCH)
        self.assertKind("goodby miko", MATCH)
        # A name split in two by the recognizer.
        self.assertKind("goodbye me ko", MATCH)

    def test_non_matches(self):
        for text in ("I said goodbye to my mother", "my friend Nico called", "goodbye",
                     "miko", "hello miko", "", "bye now, see you at the mall with Mike and Nico"):
            with self.subTest(text=text):
                self.assertNotEqual(score(text).kind, MATCH)

    def test_farewell_too_far_from_the_name_does_not_match(self):
        self.assertKind("goodbye to you and to miko", NONE)

    def test_near_miss_band(self):
        decision = self.assertKind("goodbye mike", NEAR_MISS)
        self.assertGreaterEqual(decision.score, 70)
        self.assertLess(decision.score, 85)
        self.assertIn("mike", decision.words)

    def test_match_anywhere_in_a_long_transcript(self):
        self.assertKind("ok that was fun goodbye miko see you tomorrow my friend", MATCH)


class MatcherTimingTests(unittest.TestCase):
    def test_final_transcript_matches_at_once(self):
        m = SleepWordMatcher()
        decision = m.feed_final("goodbye miko", now=10.0)
        self.assertEqual(decision.kind, MATCH)
        self.assertEqual(decision.source, "final")
        self.assertEqual(decision.transcript, "goodbye miko")

    def test_split_deltas_match_only_after_the_second_ages(self):
        m = SleepWordMatcher(settle=0.5)
        self.assertIsNone(m.feed_partial("goodbye ", now=0.0))
        self.assertIsNone(m.feed_partial("goodbye miko", now=0.2))
        self.assertIsNone(m.poll(now=0.6))  # "goodbye" alone has settled; "miko" has not
        self.assertEqual(m.next_due(), 0.7)
        decision = m.poll(now=0.7)
        self.assertEqual(decision.kind, MATCH)
        self.assertEqual(decision.source, "partial")

    def test_split_deltas_match_on_final_before_settling(self):
        m = SleepWordMatcher(settle=0.5)
        m.feed_partial("goodbye ", now=0.0)
        m.feed_partial("goodbye miko", now=0.2)
        self.assertEqual(m.feed_final("goodbye miko", now=0.3).kind, MATCH)

    def test_a_word_completed_by_a_later_delta_is_rechecked(self):
        m = SleepWordMatcher(settle=0.5)
        m.feed_partial("goodbye mi", now=0.0)
        first = m.poll(now=0.5)
        self.assertNotEqual(first.kind if first else NONE, MATCH)
        m.feed_partial("goodbye miko", now=0.6)
        self.assertEqual(m.poll(now=1.1).kind, MATCH)

    def test_one_match_per_turn_and_reset_between_turns(self):
        m = SleepWordMatcher()
        self.assertEqual(m.feed_final("goodbye miko", now=1.0).kind, MATCH)
        self.assertIsNone(m.poll(now=5.0))
        m.reset()
        self.assertEqual(m.feed_final("bye miko", now=6.0).kind, MATCH)

    def test_near_miss_reported_once(self):
        m = SleepWordMatcher(settle=0.5)
        m.feed_partial("goodbye mike", now=0.0)
        self.assertEqual(m.poll(now=0.5).kind, NEAR_MISS)
        self.assertIsNone(m.poll(now=0.9))
        self.assertIsNone(m.feed_final("goodbye mike", now=1.0))

    def test_no_decision_for_plain_speech(self):
        m = SleepWordMatcher()
        m.feed_partial("what is the weather", now=0.0)
        self.assertIsNone(m.poll(now=1.0))
        self.assertIsNone(m.feed_final("what is the weather", now=1.0))

    def test_matcher_has_no_assistant_input(self):
        # The only inputs are the person's transcript; there is no way to feed it the
        # model's own words (KTD2: never match on assistant_text_delta).
        public = {name for name in dir(SleepWordMatcher) if not name.startswith("_")}
        self.assertEqual(public, {"feed_partial", "feed_final", "poll", "next_due", "reset"})


class FarewellPhraseTests(unittest.TestCase):
    """The live matcher: the model's own farewell phrase ends the conversation."""

    def feed_all(self, matcher, *deltas):
        """Every delta in order; the first decision, or None."""
        first = None
        for delta in deltas:
            decision = matcher.feed(delta)
            first = first or decision
        return first

    def test_phrase_words_drops_case_punctuation_and_whitespace(self):
        self.assertEqual(phrase_words("  Talk to you  LATER!  "), ["talk", "to", "you", "later"])
        self.assertEqual(phrase_words("Talk to you later."), ["talk", "to", "you", "later"])
        self.assertEqual(phrase_words(""), [])

    def test_whole_phrase_in_one_delta_matches(self):
        decision = FarewellPhraseMatcher().feed("Bye for now. Talk to you later!")
        self.assertEqual((decision.kind, decision.source), (MATCH, ASSISTANT_TEXT))
        self.assertEqual(decision.score, 100)
        self.assertEqual(decision.words, ("talk", "to", "you", "later"))

    def test_phrase_split_across_deltas_matches(self):
        m = FarewellPhraseMatcher()
        self.assertIsNone(m.feed("Okay. Talk to "))
        decision = m.feed("you later.")
        self.assertEqual(decision.kind, MATCH)

    def test_phrase_split_mid_word_matches(self):
        m = FarewellPhraseMatcher()
        self.assertIsNone(m.feed("Talk to you la"))
        self.assertEqual(m.feed("ter.").kind, MATCH)

    def test_case_and_punctuation_are_ignored(self):
        for text in ("TALK TO YOU LATER", "talk, to you... later?", "Talk  to\n you\tlater"):
            with self.subTest(text=text):
                self.assertEqual(FarewellPhraseMatcher().feed(text).kind, MATCH)

    def test_other_speech_does_not_match(self):
        for text in ("I will talk to you tomorrow.", "Later, we can talk.",
                     "Goodbye, Miko!", "talk to you", "you later", ""):
            with self.subTest(text=text):
                self.assertIsNone(FarewellPhraseMatcher().feed(text))

    def test_the_phrase_must_be_whole_words(self):
        self.assertIsNone(FarewellPhraseMatcher().feed("I will talk to yourself later"))

    def test_it_matches_once_until_reset(self):
        m = FarewellPhraseMatcher()
        self.assertIsNotNone(m.feed("Talk to you later."))
        self.assertIsNone(m.feed("Talk to you later again."))
        m.reset()
        self.assertIsNotNone(m.feed("Talk to you later."))

    def test_the_phrase_is_configurable(self):
        m = FarewellPhraseMatcher("See you soon")
        self.assertIsNone(m.feed("Talk to you later."))
        self.assertEqual(m.feed("See you soon!").kind, MATCH)

    def test_an_empty_phrase_never_matches(self):
        m = FarewellPhraseMatcher("")
        self.assertIsNone(m.feed("Talk to you later."))
        self.assertIsNone(m.feed("anything at all"))

    def test_the_default_phrase_is_the_one_the_persona_is_told_to_say(self):
        self.assertEqual(DEFAULT_FAREWELL_PHRASE, "talk to you later")
        persona = (RELAY_ROOT / "personas" / "default.txt").read_text()
        self.assertIn(DEFAULT_FAREWELL_PHRASE, " ".join(phrase_words(persona)))
        full = (RELAY_ROOT / "personas" / "full.txt").read_text()
        self.assertIn(DEFAULT_FAREWELL_PHRASE, " ".join(phrase_words(full)))

    def test_a_long_reply_before_the_phrase_still_matches(self):
        m = FarewellPhraseMatcher()
        for _ in range(50):
            self.assertIsNone(m.feed("The weather is lovely today and I am glad you asked. "))
        self.assertEqual(m.feed("Talk to you later.").kind, MATCH)

    def test_it_takes_the_model_text_only(self):
        # No transcript path: the person's words never reach this matcher (the engine feeds
        # it from assistant_text_delta alone).
        public = {name for name in dir(FarewellPhraseMatcher) if not name.startswith("_")}
        self.assertEqual(public, {"feed", "reset"})


if __name__ == "__main__":
    unittest.main()
