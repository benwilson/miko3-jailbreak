"""Source-wiring checks for Explore on Claude (explore-on-claude plan U6).

The adapter and ModeApp need the Android SDK, so the host can't run them;
these read their sources instead. The brain's flows, the reply parsing and
the face-crop geometry run in the Explore brain harness (test_explore_brain).
"""
import re
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
PKG = REPO / "mode-explore" / "src" / "com" / "miko3" / "mode" / "explore"


def src(name):
    return (PKG / name).read_text()


def code_only(text):
    """The source with comments removed, so a doc comment can't satisfy a check."""
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


class AdapterWiringTest(unittest.TestCase):
    def test_mode_app_builds_the_real_port_and_hands_it_to_the_loop(self):
        app = code_only(src("ModeApp.java"))
        self.assertIn("new ClaudeCuriosity(", app)
        self.assertRegex(app, r"new ExploreLoop\([^;]*\bcuriosity\b")
        loop = code_only(src("ExploreLoop.java"))
        self.assertRegex(loop, r"new ExploreBrain\([^;]*\bport\b")

    def test_the_adapter_uses_the_five_clients(self):
        a = code_only(src("ClaudeCuriosity.java"))
        self.assertIn("implements CuriosityPort", a)
        for client in ("ClaudeApi", "RobotSettingsClient.fetch", "RobotSpeechClient", "RobotPeopleClient",
                       "RobotListenClient"):
            self.assertIn(client, a, client)
        for call in ("RobotPeopleClient.recent", "RobotPeopleClient.add", "RobotPeopleClient.touch",
                     "RobotPeopleClient.nameOf", "NameExtractor.extract", "new FaceCropper()"):
            self.assertIn(call, a, call)

    def test_settings_are_fetched_every_stop_and_unset_means_no_asking(self):
        a = code_only(src("ClaudeCuriosity.java"))
        can_ask = re.search(r"public boolean canAsk\(\)\s*\{(.*?)\n    \}", a, re.S)
        self.assertIsNotNone(can_ask)
        self.assertIn("refreshSettings()", can_ask.group(1))
        self.assertIn("isSetUp()", can_ask.group(1))

    def test_requests_run_off_the_brain_thread(self):
        a = code_only(src("ClaudeCuriosity.java"))
        self.assertIn("Executors.newCachedThreadPool", a)
        self.assertIn("worker.execute(job)", a)
        for method in ("ask", "match", "lines", "findName", "remember"):
            body = re.search(r"public void " + method + r"\((.*?)\n    \}", a, re.S)
            self.assertIsNotNone(body, method)
            self.assertIn("run(new Runnable()", body.group(1), method)

    def test_thinking_eyes_are_mapped(self):
        app = code_only(src("ModeApp.java"))
        self.assertRegex(app, r"case THINKING:\s*setExploreState\(ExploreState\.of\(ExploreState\.THINKING\)\)")


class PromptsTest(unittest.TestCase):
    def setUp(self):
        self.p = src("ExplorePrompts.java")

    def test_the_owners_priorities_and_tone(self):
        self.assertIn("people first, then animals, then technology, then anything else", self.p)
        self.assertIn("a person first, then an animal, then technology, then anything else", self.p)
        self.assertIn("most excited of all about new people", self.p)
        self.assertIn("compliment", self.p)
        self.assertIn("joke", self.p)
        self.assertIn("Never guess who anyone is, never guess or invent anyone's name", self.p)

    def test_the_match_prompt_is_consented_references_and_names_nobody(self):
        self.assertIn("consented enrolment photos from the household's own robot", self.p)
        self.assertRegex(self.p, r"static String matchIntro\(int references\)")
        self.assertRegex(self.p, r"static String matchAsk\(int references\)")
        body = self.p[self.p.index("static String matchIntro"):self.p.index("static final Map<String, Object> MATCH_SCHEMA")]
        self.assertIn("none", body)
        self.assertIn("unsure", body)
        self.assertIn("{name}", body)

    def test_the_schemas_follow_ktd2_and_ktd3(self):
        look = self.p[self.p.index("LOOK_SCHEMA = object("):]
        look = look[:look.index(");")]
        for field in ("interesting", "frame", "box", "kind", "label", "line"):
            self.assertIn('"' + field + '"', look)
        self.assertIn('enumOf("person", "animal", "technology", "other")', look)
        match = self.p[self.p.index("MATCH_SCHEMA = object("):]
        match = match[:match.index(");")]
        for field in ("match", "named_line", "unnamed_line", "ask_line", "no_reply_line"):
            self.assertIn('"' + field + '"', match)


class NothingPrivateIsLoggedTest(unittest.TestCase):
    """No Log call touches images, the key, Claude's reply text, or names (ids are fine)."""

    FILES = ("ClaudeCuriosity.java", "FaceCropper.java", "ModeApp.java", "ExploreLoop.java")
    PRIVATE = re.compile(r"\b(jpeg|frameJpeg|face|crop|apiKey|access|json|line|text|transcript|name|named|"
                         r"heard|reply|answer|result\.json|prompt)\b", re.I)

    def test_log_calls_carry_only_fixed_text_reasons_and_counts(self):
        offenders = []
        for f in self.FILES:
            for call in re.findall(r"Log\.[diwe]\((.*?)\);", code_only(src(f)), re.S):
                args = call.split(",", 1)[1] if "," in call else call
                # Drop the string literals: fixed text is fine.
                bare = re.sub(r'"(?:\\.|[^"\\])*"', "", args)
                if self.PRIVATE.search(bare):
                    offenders.append(f"{f}: Log({call.strip()})")
        self.assertEqual(offenders, [])


if __name__ == "__main__":
    unittest.main()
