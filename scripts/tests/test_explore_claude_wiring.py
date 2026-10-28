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
        for call in ("RobotPeopleClient.recent", "RobotPeopleClient.addPerson(", "RobotPeopleClient.touch",
                     "RobotPeopleClient.nameOf", "NameExtractor.extract", "new FaceCropper(app)",
                     "new FaceEmbedder(app)", "RobotPeopleClient.gallery(", "RobotSettingsClient.fetchFaceSettings(",
                     "RobotPeopleClient.recordCheck(", "RobotPeopleClient.photo(", "RobotPeopleClient.setEmbedding(",
                     "RobotPeopleClient.markUnusable("):
            self.assertIn(call, a, call)

    def test_release_closes_both_clients(self):
        # A new ClaudeCuriosity per Explore start: release() must give back the
        # speech and listen clients' executor threads, or each start leaks them.
        a = code_only(src("ClaudeCuriosity.java"))
        body = re.search(r"void release\(\)\s*\{(.*?)\n    \}", a, re.S)
        self.assertIsNotNone(body)
        self.assertIn("speech.close()", body.group(1))
        self.assertIn("ears.close()", body.group(1))
        self.assertIn("worker.shutdownNow()", body.group(1))

    def test_every_way_a_spoken_line_ends_finishes_the_say(self):
        # SPEAK waits on sayFinished(): a finished, cancelled or failed line
        # (the launcher's failed(reason) callback) must all end the wait.
        a = code_only(src("ClaudeCuriosity.java"))
        say = re.search(r"public void say\(String line\)\s*\{(.*?)\n    \}", a, re.S)
        self.assertIsNotNone(say)
        for cb in ("onFinished", "onCancelled", "onFailed"):
            m = re.search(r"public void " + cb + r"\([^)]*\)\s*\{(.*?)\n            \}", say.group(1), re.S)
            self.assertIsNotNone(m, cb)
            self.assertIn("says.finish(g,", m.group(1), cb)

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
        for method in ("ask", "match", "lines", "findName", "remember", "wayOut", "doorway", "recentlyMet"):
            body = re.search(r"public void " + method + r"\((.*?)\n    \}", a, re.S)
            self.assertIsNotNone(body, method)
            self.assertIn("run(new Runnable()", body.group(1), method)

    def test_thinking_eyes_are_mapped(self):
        app = code_only(src("ModeApp.java"))
        self.assertRegex(app, r"case THINKING:\s*setExploreState\(ExploreState\.of\(ExploreState\.THINKING\)\)")

    def test_listening_and_glance_eyes_are_mapped(self):
        # Meeting plan U7, KTD12: the listening look and the glance to the voice's side.
        app = code_only(src("ModeApp.java"))
        self.assertRegex(app, r"case LISTENING:\s*setExploreState\(ExploreState\.of\(ExploreState\.LISTENING\)\)")
        glance = re.search(r"case GLANCE:(.*?)break;", app, re.S)
        self.assertIsNotNone(glance)
        for look in ("ExploreState.GLANCE_LEFT", "ExploreState.GLANCE_RIGHT", "ExploreState.LISTENING"):
            self.assertIn(look, glance.group(1), look)


class DockedQuietWiringTest(unittest.TestCase):
    """Docked means quiet (owner 2026-10-02): the brain shows EyeState.DOCKED on the charger,
    ModeApp sings only for RESTING (not EYES_ONLY: on the dock the floor sensor reads a fault, so
    he sat in EYES_ONLY there), and the song is occasional."""

    def test_docked_eyes_are_mapped_and_never_sing(self):
        app = code_only(src("ModeApp.java"))
        self.assertRegex(app, r"case DOCKED:\s*setExploreState\(ExploreState\.IDLE_STATE\)")
        sing = re.search(r"if \(([^{;]*?)\)\s*\{\s*c\.startSinging\(\);", app, re.S)
        self.assertIsNotNone(sing)
        self.assertNotIn("DOCKED", sing.group(1))
        self.assertIn("EyeState.RESTING", sing.group(1))
        self.assertNotIn("EYES_ONLY", sing.group(1))

    def test_the_song_gap_is_45_to_90_s_and_there_is_no_quick_first_phrase(self):
        player = code_only(src("ClipPlayer.java"))
        lo = int(re.search(r"SONG_GAP_MIN_MS\s*=\s*(\d+)", player).group(1))
        hi = int(re.search(r"SONG_GAP_MAX_MS\s*=\s*(\d+)", player).group(1))
        self.assertEqual((lo, hi), (45000, 90000))
        self.assertNotIn("FIRST_SONG_DELAY_MS", player)
        start = re.search(r"void startSinging\(\)\s*\{(.*?)\n    \}", player, re.S).group(1)
        self.assertIn("handler.postDelayed(singPhrase, songGap())", start)
        phrase = re.search(r"Runnable singPhrase = new Runnable\(\)(.*?)\n    \};", player, re.S).group(1)
        self.assertIn("songGap()", phrase)

    def test_the_brain_hands_the_charger_to_the_eyes_and_silences_the_startle_there(self):
        brain = code_only(src("ExploreBrain.java"))
        self.assertIn("enum EyeState { IDLE, LOOK, FLINCH, RESTING, EYES_ONLY, STARE, THINKING, GLANCE, LISTENING, DOCKED }",
                      brain)
        show = re.search(r"private void show\(EyeState s, Direction gaze\)\s*\{(.*?)eyes\.show", brain, re.S).group(1)
        self.assertIn("onCharger()", show)
        self.assertIn("EyeState.DOCKED", show)
        startle = re.search(r"if \(onCharger\(\)\)\s*\{[^}]*\}\s*else if \([^)]*\)\s*\{\s*sound\.playStartle\(\);",
                            brain)
        self.assertIsNotNone(startle)

    def test_on_the_charger_is_power_or_the_cpl_latch(self):
        # 2026-10-02: on the owner's dock the ToF reads 16383, so he never drives and the CPL=3
        # latch never comes; POWER's dock verdict is the other source, read on every poll.
        brain = code_only(src("ExploreBrain.java"))
        on = re.search(r"private boolean onCharger\(\)\s*\{(.*?)\n    \}", brain, re.S)
        self.assertIsNotNone(on)
        self.assertIn("powerDocked", on.group(1))
        self.assertIn("classifier.charger()", on.group(1))
        self.assertEqual(brain.count("classifier.charger()"), 1, "every other charger check goes through onCharger()")
        self.assertIn("trackPower(reading);", brain)
        drive = code_only(src("ExploreDrive.java"))
        self.assertIn("s.power == null ? null : Boolean.valueOf(s.power.docked())", drive)


class EarsAdapterWiringTest(unittest.TestCase):
    """The continuous ears as step input (meeting plan U7; KTD1, KTD5, KTD6): ModeApp builds the
    adapter and hands it to the loop and the port; the adapter maps every callback field, follows
    the charger latch, forwards shove stamps, and never logs the words."""

    def test_mode_app_builds_the_adapter_and_hands_it_to_the_loop_the_drive_and_the_port(self):
        app = code_only(src("ModeApp.java"))
        self.assertIn("new EarsAdapter(", app)
        self.assertIn("drive.setReadingListener(ears)", app)
        self.assertIn("curiosity.setEars(ears)", app)
        self.assertRegex(app, r"new ExploreLoop\([^;]*\bears\b")
        self.assertIn("loop.setGauges(gauges)", app)
        stop = re.search(r"void stopExplore\(\)\s*\{(.*?)\n    \}", app, re.S)
        self.assertIsNotNone(stop)
        self.assertIn("ears.release()", stop.group(1))
        loop = code_only(src("ExploreLoop.java"))
        self.assertRegex(loop, r"new ExploreBrain\([^;]*\bears\b")

    def test_the_adapter_maps_every_callback_field(self):
        """The kind is appended last on the wire (KTD1 shape, owner-approved
        2026-09-26) and mapped here by int, never guessed from the text."""
        a = code_only(src("EarsAdapter.java"))
        self.assertIn("implements Ears, RobotEarsClient.Listener, ExploreDrive.ReadingListener", a)
        heard = re.search(r"public void onHeard\(String text, int side, float angle, int tier, long at, "
                          r"boolean partialUtterance, int kind,\s+boolean called, String message\)\s*\{(.*?)\n    \}",
                          a, re.S)
        self.assertIsNotNone(heard)
        body = heard.group(1)
        self.assertNotIn("CueKinds", a)
        self.assertIn("side == RobotEars.SIDE_LEFT", body)
        self.assertIn("side == RobotEars.SIDE_RIGHT", body)
        self.assertIn("tier == RobotEars.TIER_STRONG", body)
        self.assertIn("Ears.Kind k = kindOf(kind, t)", body)
        self.assertIn("new Ears.Cue(k, t, s, angle, at, called, message)", body)
        self.assertIn("if (partialUtterance && !(reply != null && words && !newcomer))", body)
        self.assertIn("partial = cue", body)
        self.assertIn("queue.addLast(cue)", body)
        self.assertIn("QUEUE_MAX", body)

    def test_an_already_called_cue_keeps_its_mark_and_never_joins_a_later_cue(self):
        """Hey Miko plan U3 (KTD4): the launcher marks the end-of-utterance
        delivery of a wake word it already sent early. The mark rides the cue
        (a partial keeps it, being the same cue), an already-called partial is
        never joined into a later cue (that would be a second call with a new
        at), and an empty-text early cue never reaches an armed reply."""
        cue = code_only(src("Ears.java"))
        self.assertIn("final boolean called;", cue)
        self.assertRegex(cue, r"Cue\(Kind kind, Tier tier, Side side, float angleDeg, long at\)\s*\{\s*"
                              r"this\(kind, tier, side, angleDeg, at, false\);")
        self.assertRegex(cue, r"Cue\(Kind kind, Tier tier, Side side, float angleDeg, long at, boolean called\)")
        self.assertRegex(cue, r"boolean alreadyCalled\(\)\s*\{\s*return called;")
        a = code_only(src("EarsAdapter.java"))
        heard = re.search(r"public void onHeard\((.*?)\n    \}", a, re.S).group(1)
        self.assertRegex(heard, r"if \(partialUtterance && !\(reply != null && words && !newcomer\)\) \{[^}]*partial = cue;")
        join = re.search(r"if \(partial != null && ([^{]*)\) \{", heard)
        self.assertIsNotNone(join, "no partial join")
        self.assertIn("!partial.alreadyCalled()", join.group(1))
        # The reply takes only an utterance with words: an empty early cue goes to the queue.
        self.assertIn("boolean words = text != null && !text.trim().isEmpty();", heard)
        self.assertIn("if (reply != null && words && !newcomer)", heard)

    def test_the_adapter_maps_all_five_kinds_and_falls_back_by_tier(self):
        a = code_only(src("EarsAdapter.java"))
        m = re.search(r"static Ears\.Kind kindOf\(int (\w+), Ears\.Tier (\w+)\)\s*\{(.*?)\n    \}", a, re.S)
        self.assertIsNotNone(m, "no kindOf(int, Ears.Tier)")
        wire, tier, body = m.group(1), m.group(2), m.group(3)
        self.assertIn("switch (" + wire + ")", body)
        for kind in ("WAKE_WORD", "NAME", "GREETING", "APOLOGY", "VOICE"):
            self.assertRegex(body, r"case RobotEars\.KIND_" + kind + r":\s*return Ears\.Kind\." + kind + ";")
        default = re.search(r"default:(.*)", body, re.S)
        self.assertIsNotNone(default)
        self.assertIn("Log.w(", default.group(1))
        self.assertRegex(default.group(1),
                         tier + r" == Ears\.Tier\.STRONG \? Ears\.Kind\.GREETING : Ears\.Kind\.VOICE")

    def test_the_adapter_never_logs_the_text(self):
        a = code_only(src("EarsAdapter.java"))
        for call in re.findall(r"Log\.[diwe]\((.*?)\);", a, re.S):
            bare = re.sub(r'"(?:\\.|[^"\\])*"', "", call)
            self.assertNotRegex(bare, r"\b(text|transcript|cue|partial\b|queue)\b", call)
        self.assertNotIn("text +", a)
        self.assertNotIn("+ text", a)

    def test_the_port_ears_calls_reach_the_session_and_the_brain_forwards_shoves(self):
        a = code_only(src("ClaudeCuriosity.java"))
        for method, call in (("earsOpen", "s.open()"), ("earsClose", "s.close()"), ("clipWindow", "s.clipWindow(ms)"),
                             ("earsShoved", "s.shoved(atMs)")):
            body = re.search(r"public void " + method + r"\((.*?)\n    \}", a, re.S)
            self.assertIsNotNone(body, method)
            self.assertIn(call, body.group(1), method)
        listen = re.search(r"public void listen\((.*?)\n    \}", a, re.S).group(1)
        self.assertIn("s.isOpen()", listen)
        self.assertIn("earsListen(s, maxMs, Float.NaN)", listen)
        ears_listen = re.search(r"private void earsListen\((.*?)\n    \}", a, re.S).group(1)
        self.assertIn("s.listen(maxMs, newcomerAngleDeg,", ears_listen)
        port = code_only(src("CuriosityPort.java"))
        self.assertRegex(port, r"void earsShoved\(long atMs\);")
        brain = code_only(src("ExploreBrain.java"))
        self.assertIn("port.earsShoved(", brain)

    def test_the_ears_stay_open_on_the_charger(self):
        # Hey-miko plan KTD5 replaces the meeting plan's KTD6 dock rule: the brain no longer
        # closes the ears on the charger latch; a call there is met without moving.
        brain = code_only(src("ExploreBrain.java"))
        sync = re.search(r"private void syncEars\(long now\)\s*\{(.*?)\n    \}", brain, re.S)
        self.assertIsNotNone(sync)
        self.assertNotIn("classifier.charger()", sync.group(1))
        self.assertNotIn("onCharger()", sync.group(1))
        self.assertIn("port.earsOpen()", sync.group(1))
        self.assertIn("port.earsClose()", sync.group(1))
        # A session the launcher dropped is noticed here too (the reopen backoff below).
        self.assertIn("ears.listening()", sync.group(1))
        a = code_only(src("EarsAdapter.java"))
        reading = re.search(r"public void onReading\(SensorReading r\)\s*\{(.*?)\n    \}", a, re.S)
        self.assertIsNotNone(reading)
        self.assertIn("c.setCharger(r.charger)", reading.group(1))
        self.assertIn("new Ears.Shove(", reading.group(1))
        self.assertIn("c.open(charger, this)", a)

    def test_a_lost_ears_session_reopens_on_the_drive_lease_ladder(self):
        """One ladder for both: a launcher restart brings the ears back with the wheels (meeting plan, launcher restart)."""
        brain = code_only(src("ExploreBrain.java"))
        drive = code_only(src("ExploreDrive.java"))
        for ours, theirs in (("EARS_REOPEN_BASE_MS", "LEASE_RETRY_BASE_MS"), ("EARS_REOPEN_MAX_MS", "LEASE_RETRY_MAX_MS")):
            b = re.search(r"long " + ours + r" = (\d+);", brain)
            d = re.search(r"long " + theirs + r" = (\d+);", drive)
            self.assertIsNotNone(b, ours)
            self.assertIsNotNone(d, theirs)
            self.assertEqual(b.group(1), d.group(1), ours)
        # The same doubling, capped after the fourth step.
        self.assertRegex(brain, r"EARS_REOPEN_BASE_MS << Math\.min\(earsReopenAttempt, 4\), EARS_REOPEN_MAX_MS")
        self.assertRegex(drive, r"LEASE_RETRY_BASE_MS << Math\.min\(leaseRetryAttempt - 1, 4\), LEASE_RETRY_MAX_MS")

    def test_a_listen_that_ends_in_silence_retires_its_reply(self):
        a = code_only(src("EarsAdapter.java"))
        over = re.search(r"void listenOver\(Reply r\) \{(.*?)\n    \}", a, re.S)
        self.assertIsNotNone(over, "EarsAdapter has no listenOver(Reply)")
        self.assertRegex(over.group(1), r"synchronized \(lock\) \{\s*if \(reply == r\) \{\s*reply = null;\s*replyAngleDeg = Float\.NaN;")
        # onHeard routes an utterance to a reply only while one is armed, and disarms it as it does.
        heard = re.search(r"public void onHeard\((.*?)\n    \}", a, re.S).group(1)
        self.assertIn("if (reply != null && words && !newcomer)", heard)
        self.assertRegex(heard, r"r = reply;\s*reply = null;")
        c = code_only(src("ClaudeCuriosity.java"))
        ears_listen = re.search(r"private void earsListen\((.*?)\n    \}", c, re.S).group(1)
        self.assertIn("final EarsAdapter.Reply reply = new EarsAdapter.Reply()", ears_listen)
        self.assertIn("s.listen(maxMs, newcomerAngleDeg, reply);", ears_listen)
        silence = re.search(r"if \(hearings\.current\(g\) && hearings\.poll\(\) == null\) \{(.*?)\n\s*\}", ears_listen, re.S)
        self.assertIsNotNone(silence, "no silence branch")
        self.assertRegex(silence.group(1), r"s\.listenOver\(reply\);\s*hearings\.finish\(g, Heard\.NOTHING\);")
        self.assertEqual(ears_listen.count("hearings.finish(g, Heard.NOTHING)"), 1)

    def test_an_answer_that_has_started_holds_the_ears_listen_to_the_answer_hold(self):
        """Robot 2026-10-01: the launcher's "answering" (RobotEars.Callback,
        code 2) reaches the armed reply; the maxMs timer then does not end the
        listen, which ends at its words or at LauncherProtocol.EARS_ANSWER_HOLD_MS from its
        start. Without "answering" (silence, an older launcher) the timer ends
        it at maxMs exactly as before."""
        a = code_only(src("EarsAdapter.java"))
        on = re.search(r"public void onAnswering\(long at\) \{(.*?)\n    \}", a, re.S)
        self.assertIsNotNone(on, "EarsAdapter has no onAnswering")
        self.assertRegex(on.group(1), r"synchronized \(lock\) \{\s*r = open \? reply : null;")
        self.assertIn("r.answering(at);", on.group(1))
        c = code_only(src("ClaudeCuriosity.java"))
        ears_listen = re.search(r"private void earsListen\((.*?)\n    \}", c, re.S).group(1)
        self.assertIn("public void answering(long at)", ears_listen)
        self.assertIn("answeringGen = g;", ears_listen)
        self.assertIn("LauncherProtocol.EARS_ANSWER_HOLD_MS - maxMs", ears_listen)
        self.assertRegex(ears_listen, r"if \(answeringGen == g\) \{")
        port = re.search(r"public boolean answering\(\) \{(.*?)\n    \}", c, re.S)
        self.assertIsNotNone(port)
        self.assertIn("hearings.current(g) && hearings.poll() == null", port.group(1))
        self.assertIn("boolean answering();", code_only(src("CuriosityPort.java")))
        # The brain cannot see shared/: its mirror of the hold is pinned to the shared constant.
        proto = (REPO / "shared" / "src" / "com" / "miko3" / "shared" / "LauncherProtocol.java").read_text()
        cap = int(re.search(r"long EARS_LISTEN_HARD_CAP_MS = (\d+);", proto).group(1))
        extra = int(re.search(r"long EARS_ANSWER_HOLD_MS = EARS_LISTEN_HARD_CAP_MS \+ (\d+);", proto).group(1))
        tuning = src("ExploreTuning.java")
        self.assertEqual(int(re.search(r"private long answerHoldMs = (\d+);", tuning).group(1)), cap + extra)

    def test_an_answer_over_ends_the_held_ears_listen_as_silence(self):
        """Review 2026-10-01 (P2-2): the launcher's "answer over" (RobotEars.Callback,
        code 3) reaches the armed reply; it drops the hold (answering() turns false) and,
        once the maxMs timer has passed, ends the listen as silence at once instead of at
        EARS_ANSWER_HOLD_MS. Before maxMs the timer ends it as a silent listen."""
        a = code_only(src("EarsAdapter.java"))
        on = re.search(r"public void onAnswerOver\(long at\) \{(.*?)\n    \}", a, re.S)
        self.assertIsNotNone(on, "EarsAdapter has no onAnswerOver")
        self.assertRegex(on.group(1), r"synchronized \(lock\) \{\s*r = open \? reply : null;")
        self.assertIn("r.answerOver(at);", on.group(1))
        c = code_only(src("ClaudeCuriosity.java"))
        ears_listen = re.search(r"private void earsListen\((.*?)\n    \}", c, re.S).group(1)
        over = re.search(r"public void answerOver\(long at\) \{(.*?)\n            \}", ears_listen, re.S)
        self.assertIsNotNone(over, "the ears listen's reply has no answerOver")
        self.assertRegex(over.group(1), r"if \(answeringGen == g\) \{\s*answeringGen = 0;")
        self.assertRegex(over.group(1), r"if \(pastMax\.get\(\)\) \{\s*silence\[0\]\.run\(\);")
        # The maxMs timer marks itself passed before it looks at the hold, so neither order misses the end.
        self.assertRegex(ears_listen, r"pastMax\.set\(true\);\s*if \(answeringGen == g\) \{")

    def test_a_lost_session_closes_its_client_outside_the_lock(self):
        a = code_only(src("EarsAdapter.java"))
        lost = re.search(r"public void onLost\(String reason\) \{(.*?)\n    \}", a, re.S)
        self.assertIsNotNone(lost)
        lost = lost.group(1)
        self.assertRegex(lost, r"synchronized \(lock\) \{\s*c = client;\s*resetLocked\(\);\s*\}")
        self.assertRegex(lost, r"\}\s*if \(c != null\) \{\s*c\.close\(\);\s*\}")
        self.assertNotRegex(lost, r"synchronized \(lock\) \{[^}]*close\(\)")


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

    def test_no_image_match_prompt_or_schema_remains(self):
        """Face plan U6 (R3, R4): matching is on the robot; no match prompt, schema or reply parser."""
        for gone in ("matchIntro", "matchAsk", "MATCH_SCHEMA"):
            self.assertNotIn(gone, code_only(self.p), gone)
        replies = code_only(src("ClaudeReplies.java"))
        self.assertNotIn("static Match match(", replies)
        self.assertNotIn("class Match ", replies)

    def test_the_lines_request_carries_the_named_greeting_with_a_placeholder(self):
        """KTD7: the text-only lines gain the named greeting; the robot fills {name}, so names never leave."""
        ask = self.p[self.p.index("static final String LINES_ASK"):self.p.index("static final Map<String, Object> LINES_SCHEMA")]
        self.assertIn("named_line", ask)
        self.assertIn("{name}", ask)
        schema = self.p[self.p.index("LINES_SCHEMA = object("):]
        schema = schema[:schema.index(");")]
        for field in ("named_line", "ask_line", "no_reply_line"):
            self.assertIn('"' + field + '"', schema)

    def test_the_schemas_follow_ktd2_and_ktd3(self):
        look = self.p[self.p.index("LOOK_SCHEMA = object("):]
        look = look[:look.index(");")]
        for field in ("interesting", "frame", "box", "kind", "label", "line"):
            self.assertIn('"' + field + '"', look)
        self.assertIn('enumOf("person", "animal", "technology", "other")', look)


class WayOutRequestTest(unittest.TestCase):
    """The way-out ask (explore nav plan U5, KTD4): one schema for the circle's frames
    and the second ask's one frame, polled like the look request, never written to disk."""

    def test_the_port_and_its_no_claude_stand_in_have_the_request(self):
        port = code_only(src("CuriosityPort.java"))
        for sig in (r"void wayOut\(WayOutRequest request, long timeoutMs\);", r"WayOut wayOutAnswer\(\);",
                    r"void cancelWayOut\(\);"):
            self.assertRegex(port, sig)
        none = port[port.index("CuriosityPort NONE = new CuriosityPort()"):]
        body = re.search(r"public WayOut wayOutAnswer\(\)\s*\{(.*?)\}", none, re.S)
        self.assertIsNotNone(body)
        self.assertIn("WayOut.failed()", body.group(1))

    def test_the_adapter_polls_a_slot_with_generations(self):
        a = code_only(src("ClaudeCuriosity.java"))
        self.assertIn("Slot<WayOut> wayOuts = new Slot<WayOut>()", a)
        way = re.search(r"public void wayOut\((.*?)\n    \}", a, re.S).group(1)
        self.assertIn("wayOuts.start()", way)
        self.assertIn("wayOuts.finish(g,", way)
        self.assertRegex(a, r"public WayOut wayOutAnswer\(\)\s*\{\s*return wayOuts\.poll\(\);")
        self.assertRegex(a, r"public void cancelWayOut\(\)\s*\{\s*wayOuts\.cancel\(\);")

    def test_the_request_uses_its_schema_and_validation_and_writes_nothing(self):
        a = code_only(src("ClaudeCuriosity.java"))
        body = a[a.index("private WayOut findWayOut("):]
        body = body[:body.index("\n    }\n")]
        self.assertIn("ExplorePrompts.WAY_OUT_SCHEMA", body)
        self.assertIn("ClaudeReplies.wayOut(", body)
        self.assertIn("ClaudeApi.jpegBlock(", body)
        for disk in ("debugFace", "write(", "FileOutputStream", "getFilesDir"):
            self.assertNotIn(disk, body, disk)

    def test_one_schema_frame_and_x_for_both_asks(self):
        p = src("ExplorePrompts.java")
        schema = p[p.index("WAY_OUT_SCHEMA = object("):]
        schema = schema[:schema.index(");")]
        for field in ("way_out", "frame", "x"):
            self.assertIn('"' + field + '"', schema)
        self.assertEqual(p.count("WAY_OUT_SCHEMA = "), 1)
        ask = p[p.index("static String wayOutAsk("):]
        ask = ask[:ask.index("\n    }\n")]
        for words in ("open doorway", "person", "open floor", "closed door"):
            self.assertIn(words, ask)


class DoorwayRequestTest(unittest.TestCase):
    """The doorway ask (explore nav plan U6, KTD4): one roaming frame, answered with
    none or a horizontal position, polled like the way-out ask, never written to disk."""

    def test_the_port_and_its_no_claude_stand_in_have_the_request(self):
        port = code_only(src("CuriosityPort.java"))
        for sig in (r"void doorway\(byte\[\] jpeg, long timeoutMs\);", r"Doorway doorwayAnswer\(\);",
                    r"void cancelDoorway\(\);"):
            self.assertRegex(port, sig)
        none = port[port.index("CuriosityPort NONE = new CuriosityPort()"):]
        body = re.search(r"public Doorway doorwayAnswer\(\)\s*\{(.*?)\}", none, re.S)
        self.assertIsNotNone(body)
        self.assertIn("Doorway.failed()", body.group(1))

    def test_the_adapter_polls_a_slot_with_generations(self):
        a = code_only(src("ClaudeCuriosity.java"))
        self.assertIn("Slot<Doorway> doorways = new Slot<Doorway>()", a)
        ask = re.search(r"public void doorway\((.*?)\n    \}", a, re.S).group(1)
        self.assertIn("doorways.start()", ask)
        self.assertIn("doorways.finish(g,", ask)
        self.assertRegex(a, r"public Doorway doorwayAnswer\(\)\s*\{\s*return doorways\.poll\(\);")
        self.assertRegex(a, r"public void cancelDoorway\(\)\s*\{\s*doorways\.cancel\(\);")

    def test_the_request_sends_one_frame_with_its_schema_and_writes_nothing(self):
        a = code_only(src("ClaudeCuriosity.java"))
        body = a[a.index("private Doorway findDoorway("):]
        body = body[:body.index("\n    }\n")]
        self.assertIn("ExplorePrompts.DOORWAY_SCHEMA", body)
        self.assertIn("ClaudeReplies.doorway(", body)
        self.assertEqual(body.count("ClaudeApi.jpegBlock("), 1)
        self.assertIn("ExplorePrompts.NAV_SYSTEM", body)
        for disk in ("debugFace", "write(", "FileOutputStream", "getFilesDir"):
            self.assertNotIn(disk, body, disk)

    def test_the_schema_is_none_or_a_horizontal_position(self):
        p = src("ExplorePrompts.java")
        schema = p[p.index("DOORWAY_SCHEMA = object("):]
        schema = schema[:schema.index(");")]
        for field in ("open_doorway", "x"):
            self.assertIn('"' + field + '"', schema)
        self.assertNotIn('"frame"', schema)
        self.assertNotIn('"y"', schema)

    def test_the_prompt_asks_for_open_doorways_only_and_a_closed_door_is_not_one(self):
        p = src("ExplorePrompts.java")
        ask = p[p.index("static String doorwayAsk("):]
        ask = ask[:ask.index("\n    }\n")]
        for words in ("open doorway", "passable opening", "another room", "closed door is not"):
            self.assertIn(words, ask)


class SeekRequestTest(unittest.TestCase):
    """Seeking the unfamiliar (owner 2026-10-01): the scan's frames with their headings
    and how familiar each looked go to Claude in one small request, answered with a
    frame and x or none, polled like the way-out ask, never written to disk. Only
    numbers and detector labels describe the frames: never a name (R15)."""

    def test_the_port_and_its_no_claude_stand_in_have_the_request(self):
        port = code_only(src("CuriosityPort.java"))
        for sig in (r"void seek\(SeekRequest request, long timeoutMs\);", r"WayOut seekAnswer\(\);",
                    r"void cancelSeek\(\);"):
            self.assertRegex(port, sig)
        none = port[port.index("CuriosityPort NONE = new CuriosityPort()"):]
        body = re.search(r"public WayOut seekAnswer\(\)\s*\{(.*?)\}", none, re.S)
        self.assertIsNotNone(body)
        self.assertIn("WayOut.failed()", body.group(1))
        frame = port[port.index("final class SeekFrame"):]
        frame = frame[:frame.index("\n    }\n")]
        for field in ("final Frame frame;", "final double bearingDeg;", "final double novelty;", "final long seenAgoMs;",
                      "final boolean wentThere;", "final List<String> labels;"):
            self.assertIn(field, frame)
        self.assertNotRegex(frame, r"\bname\b")

    def test_the_adapter_polls_a_slot_with_generations(self):
        a = code_only(src("ClaudeCuriosity.java"))
        self.assertIn("Slot<WayOut> seeks = new Slot<WayOut>()", a)
        ask = re.search(r"public void seek\((.*?)\n    \}", a, re.S).group(1)
        self.assertIn("seeks.start()", ask)
        self.assertIn("seeks.finish(g,", ask)
        self.assertRegex(a, r"public WayOut seekAnswer\(\)\s*\{\s*return seeks\.poll\(\);")
        self.assertRegex(a, r"public void cancelSeek\(\)\s*\{\s*seeks\.cancel\(\);")

    def test_the_request_sends_the_frames_with_their_schema_and_writes_nothing(self):
        a = code_only(src("ClaudeCuriosity.java"))
        body = a[a.index("private WayOut findSeek("):]
        body = body[:body.index("\n    }\n")]
        self.assertIn("ExplorePrompts.SEEK_SCHEMA", body)
        self.assertIn("ExplorePrompts.seekFrame(", body)
        self.assertIn("ClaudeReplies.seek(", body)
        self.assertIn("ExplorePrompts.NAV_SYSTEM", body)
        self.assertIn("ClaudeApi.jpegBlock(", body)
        for disk in ("debugFace", "write(", "FileOutputStream", "getFilesDir"):
            self.assertNotIn(disk, body, disk)

    def test_the_prompt_asks_for_the_most_unexplored_place_by_frame_and_x(self):
        p = src("ExplorePrompts.java")
        schema = p[p.index("SEEK_SCHEMA = object("):]
        schema = schema[:schema.index(");")]
        for field in ("unexplored", "frame", "x"):
            self.assertIn('"' + field + '"', schema)
        ask = p[p.index("static String seekAsk("):]
        ask = ask[:ask.index("\n    }\n")]
        for words in ("unexplored", "open doorway", "corridor", "hasn't been", "frame number", "x, the pixel column",
                      "unexplored false"):
            self.assertIn(words, ask)
        label = p[p.index("static String seekFrame("):]
        label = label[:label.index("\n    }\n")]
        self.assertIn("f.labels", label)
        self.assertNotRegex(code_only(label), r"\bname\b")


class RecentlyMetCheckTest(unittest.TestCase):
    """The recently-met check (explore nav plan U7, KTD4, KTD8): the roaming face crop
    against the faces of everyone met in the last 10 minutes, modelled on the match
    request, polled from a slot, and never written to disk, even with the face debug
    switch on (R15)."""

    def test_the_port_and_its_no_claude_stand_in_have_the_check(self):
        port = code_only(src("CuriosityPort.java"))
        for sig in (r"void recentlyMet\(RecentlyMetRequest request, long timeoutMs\);",
                    r"Recently recentlyMetAnswer\(\);", r"void cancelRecentlyMet\(\);", r"String metId\(\);"):
            self.assertRegex(port, sig)
        none = port[port.index("CuriosityPort NONE = new CuriosityPort()"):]
        body = re.search(r"public Recently recentlyMetAnswer\(\)\s*\{(.*?)\}", none, re.S)
        self.assertIsNotNone(body)
        self.assertIn("Recently.failed()", body.group(1))

    def test_the_adapter_polls_a_slot_with_generations(self):
        a = code_only(src("ClaudeCuriosity.java"))
        self.assertIn("Slot<Recently> recents = new Slot<Recently>()", a)
        ask = re.search(r"public void recentlyMet\((.*?)\n    \}", a, re.S).group(1)
        self.assertIn("recents.start()", ask)
        self.assertIn("recents.finish(g,", ask)
        self.assertRegex(a, r"public Recently recentlyMetAnswer\(\)\s*\{\s*return recents\.poll\(\);")
        self.assertRegex(a, r"public void cancelRecentlyMet\(\)\s*\{\s*recents\.cancel\(\);")

    def test_the_check_crops_the_roaming_face_uses_its_schema_and_writes_nothing(self):
        a = code_only(src("ClaudeCuriosity.java"))
        body = a[a.index("private Recently checkRecentlyMet("):]
        body = body[:body.index("\n    }\n")]
        self.assertIn("cropper.crop(", body)
        self.assertIn("RobotPeopleClient.recent", body)
        self.assertIn("ExplorePrompts.RECENTLY_MET_SCHEMA", body)
        self.assertIn("ClaudeReplies.recentlyMet(", body)
        self.assertIn("ClaudeApi.jpegBlock(", body)
        # The face debug switch must never see a roaming crop (R15).
        for disk in ("debugFace", "write(", "FileOutputStream", "getFilesDir", "FACE_DEBUG_TAG"):
            self.assertNotIn(disk, body, disk)

    def test_the_schema_and_prompt_name_nobody(self):
        p = src("ExplorePrompts.java")
        schema = p[p.index("RECENTLY_MET_SCHEMA = object("):]
        schema = schema[:schema.index(");")]
        self.assertIn('"same_as"', schema)
        body = p[p.index("static String recentlyMetIntro"):p.index("static final Map<String, Object> RECENTLY_MET_SCHEMA")]
        for words in ("consented", "none", "unsure", "Do not identify anyone"):
            self.assertIn(words, body)
        self.assertNotIn("{name}", body)


class NothingPrivateIsLoggedTest(unittest.TestCase):
    """No Log call touches images, the key, Claude's reply text, or names (ids are fine)."""

    FILES = ("ClaudeCuriosity.java", "FaceCropper.java", "ModeApp.java", "ExploreLoop.java", "EarsAdapter.java")
    PRIVATE = re.compile(r"\b(jpeg|frameJpeg|face|crop|apiKey|access|json|line|text|transcript|name|named|"
                         r"heard|reply|answer|result\.json|prompt)\b", re.I)

    def test_log_calls_carry_only_fixed_text_reasons_and_counts(self):
        offenders = []
        for f in self.FILES:
            for call in re.findall(r"Log\.[diwe]\((.*?)\);", code_only(src(f)), re.S):
                if call.lstrip().startswith("SAY_DEBUG_TAG,"):
                    # Debug-gated test logging; SayDebugLogIsGatedTest checks the gate.
                    continue
                args = call.split(",", 1)[1] if "," in call else call
                # Drop the string literals: fixed text is fine.
                bare = re.sub(r'"(?:\\.|[^"\\])*"', "", args)
                if self.PRIVATE.search(bare):
                    offenders.append(f"{f}: Log({call.strip()})")
        self.assertEqual(offenders, [])


class SayDebugLogIsGatedTest(unittest.TestCase):
    """Owner 2026-10-02: robot-say.py test runs read each turn's line and tool calls back
    from the log. Those words are logged only under SAY_DEBUG_TAG, and only inside a
    Log.isLoggable(SAY_DEBUG_TAG, Log.DEBUG) check, which is off unless the script sets it."""

    def test_every_say_debug_log_is_inside_its_isloggable_gate(self):
        body = code_only(src("ClaudeCuriosity.java"))
        starts = [m.start() for m in re.finditer(r"Log\.[diwe]\(\s*SAY_DEBUG_TAG\s*,", body)]
        self.assertTrue(starts, "no SAY_DEBUG_TAG log found")
        for at in starts:
            before = body[max(0, at - 1500):at]
            self.assertIn("Log.isLoggable(SAY_DEBUG_TAG, Log.DEBUG)", before)

    def test_robot_say_sets_and_clears_the_tag(self):
        script = (REPO / "scripts" / "robot-say.py").read_text()
        self.assertIn('SAY_DEBUG_TAG = "MikoExploreSayDebug"', script)
        self.assertIn("setprop log.tag.{SAY_DEBUG_TAG} DEBUG", script)
        self.assertIn("setprop log.tag.{SAY_DEBUG_TAG} \"\"", script)


class ClaudeLatencyIsLoggedTest(unittest.TestCase):
    """Live test: two look tries timed out at 10 s while others took ~3 s. Every
    Claude call logs how long it took in ms (no content), so slow calls show up."""

    def test_every_claude_call_logs_its_latency(self):
        body = code_only(src("ClaudeCuriosity.java"))
        calls = body.split("claude.messages(")[1:]
        self.assertEqual(len(calls), 9)
        for i, after in enumerate(calls):
            window = after[:900]
            logs = re.findall(r"Log\.[diwe]\((.*?)\);", window, re.S)
            self.assertTrue(logs, f"call {i + 1}: no Log after it")
            self.assertRegex(logs[0], r'" ms"', f"call {i + 1}: its first Log has no latency: {logs[0]}")


class ClaudeRateLimitWiringTest(unittest.TestCase):
    """Robot 2026-10-01: the key hit 429s and each was retried 0.6 s later. One back-off
    clock (ClaudeApi.Backoff, host-tested in test_claude_api) gates every Claude request
    the adapter makes; the brain's side runs in the brain harness."""

    @classmethod
    def setUpClass(cls):
        cls.a = code_only(src("ClaudeCuriosity.java"))

    def body(self, signature):
        m = re.search(signature + r"\s*\{(.*?)\n    \}", self.a, re.S)
        self.assertIsNotNone(m, signature)
        return m.group(1)

    def test_one_clock_and_every_request_goes_through_the_gate(self):
        self.assertEqual(self.a.count("new ClaudeApi.Backoff(false)"), 1)  # off on the robot (owner, 2026-10-01)
        self.assertEqual(self.a.count("new ClaudeApi("), 1)
        self.assertRegex(self.a, r"private final ClaudeApi api = new ClaudeApi\(")
        self.assertRegex(self.a, r"private final Gated claude = new Gated\(\)")
        self.assertEqual(self.a.count("api.messages("), 1)
        self.assertEqual(self.a.count("api.conversation("), 1)
        gated = re.search(r"private final class Gated \{(.*?)\n    \}", self.a, re.S)
        self.assertIsNotNone(gated)
        # Look-type requests wait out a pause; conversation turns are always sent (owner, 2026-10-01).
        i = gated.group(1).index("api.messages(")
        before = gated.group(1)[max(0, i - 300):i]
        self.assertIn("if (held())", before)
        self.assertIn("return ClaudeApi.MessageResult.paused();", before)
        convo = gated.group(1)[gated.group(1).index("ClaudeApi.MessageResult conversation("):]
        self.assertNotIn("held()", convo)
        for call in ("api.messages(", "api.conversation("):
            self.assertIn("recorded(" + call, gated.group(1), call)

    def test_the_pause_holds_looks_only_and_never_closes_can_ask(self):
        self.assertNotIn("backoff.remainingMs(", self.body(r"public boolean canAsk\(\)"))
        self.assertIn("return 0;", self.body(r"public long claudePausedMs\(\)"))
        self.assertIn("backoff.remainingMs(", self.body(r"private boolean held\(\)"))

    def test_every_result_is_recorded_and_a_new_pause_is_logged_once(self):
        rec = self.body(r"private ClaudeApi\.MessageResult recorded\(ClaudeApi\.MessageResult r\)")
        self.assertIn("backoff.record(r,", rec)
        self.assertRegex(rec, r"if \(pause > 0\)\s*\{\s*Log\.w\(")
        self.assertIn('"Claude rate-limited"', rec)
        self.assertIn('" (HTTP "', rec)
        self.assertIn('"): pausing Claude requests for "', rec)

    def test_a_rate_limited_or_paused_turn_is_unreachable_so_the_conversation_can_wait(self):
        # Review 2026-10-03: turnOf lives in ChatRound, beside fly(), which the host harness runs.
        turn_of = re.search(r"static CuriosityPort\.Turn turnOf\(Outcome o\)(.*?)\n    \}",
                            code_only(src("ChatRound.java")), re.S).group(1)
        m = re.search(r"case OVERLOADED:\s*case RATE_LIMITED:.*?return CuriosityPort\.Turn\.unreachable\(\);", turn_of, re.S)
        self.assertIsNotNone(m)


class FasterTurnWiringTest(unittest.TestCase):
    """Robot 2026-10-02 (about 5.5 s from their last word to his first): the turn uses the
    settings already fetched instead of binding the launcher every turn, streams so the
    line goes as soon as the line, question and name are known (the notes follow as late
    notes), and a turn started on the launcher's provisional answer is used only by a
    turn() asking for exactly the same request. The turn's log line says the request's
    shape in counts and fixed words only."""

    @classmethod
    def setUpClass(cls):
        cls.a = code_only(src("ClaudeCuriosity.java"))

    def method(self, sig):
        m = re.search(re.escape(sig) + r"(.*?)\n    \}", self.a, re.S)
        self.assertIsNotNone(m, sig)
        return m.group(1)

    def test_a_turn_uses_the_settings_already_fetched(self):
        acc = self.method("private ClaudeAccess turnAccess(")
        self.assertRegex(acc, r"ClaudeAccess a = access;\s*if \(a != null && a\.isSetUp\(\)\) \{\s*refreshSettings\(\);\s*return a;")
        self.assertIn("return fetchSettings();", acc)
        self.assertIn("ClaudeAccess settings = turnAccess();", self.method("private void oneTurn("))

    def test_a_turn_streams_and_hands_its_line_over_early(self):
        turn = self.method("private void oneTurn(")
        self.assertIn("TURN_EFFORT, (int) timeoutMs,\n                        ChatRound.EARLY_FIELDS, early, tools)", turn)
        # Review 2026-10-03: the flight wiring is ChatRound.fly, which the host harness runs end to end.
        self.assertIn("ChatRound.fly(body, request, new ChatRound.Asker()", turn)
        cr = code_only(src("ChatRound.java"))
        fly = re.search(r"static Outcome fly\((.*?)\n    \}", cr, re.S).group(1)
        self.assertIn("flight.early(call, t)", fly)
        self.assertIn("flight.whole(call, turnOf(o), earlyIn[0] != 0 && earlyIn[0] < sends[0])", fly)
        self.assertIn("public Turn lateTurn()", self.a)
        # Owner 2026-10-02/03: "addressed" (a boolean, before the line) comes with the early fields
        # from the respond tool's streamed input (owner 2026-10-03: no action fields, they are tools now).
        self.assertRegex(code_only(src("ChatRound.java")),
                         r'EARLY_FIELDS\s*=\s*Arrays\.asList\("addressed", "line", "question_asked", "name_given"\)')
        early = re.search(r"static CuriosityPort\.Turn earlyTurn\((.*?)\n    \}", cr, re.S).group(1)
        self.assertIn("ClaudeReplies.turn(", early)
        self.assertIn("NameExtractor.validName(", early)

    def test_a_speculation_is_used_only_by_the_same_request(self):
        turn = self.method("public void turn(TurnRequest asked, final long timeoutMs)")
        self.assertIn("flight.adopt(body.key, g)", turn)
        self.assertIn("flight.start(body.key, g)", turn)
        spec = self.method("public void speculateTurn(TurnRequest asked, final long timeoutMs)")
        self.assertIn("flight.speculate(body.key)", spec)
        self.assertIn("ChatRound.body(request, null)", spec)
        body = re.search(r"static Body body\((.*?)\n    \}", code_only(src("ChatRound.java")), re.S).group(1)
        self.assertIn("Json.write(messages)", body)
        # Owner 2026-10-03: a speculation never says or looks at anything; a tool round needs a turn() asking.
        self.assertIn("return flight.claimForTools(call);", self.method("private void oneTurn("))
        cancel = self.method("public void cancelTurn()")
        self.assertIn("turns.cancel();", cancel)
        self.assertIn("flight.cancel();", cancel)
        self.assertIn("flight.clear();", self.method("    void release()"))
        self.assertIn("return flight.lateNotes();", self.method("public String lateNotes()"))
        self.assertIn("return flight.tailPending();", self.method("public boolean turnTailPending()"))

    def test_the_provisional_answer_reaches_the_port_for_its_own_listen_only(self):
        ears_listen = self.method("private void earsListen(")
        prov = re.search(r"public void provisional\(String transcript\) \{(.*?)\n            \}", ears_listen, re.S)
        self.assertIsNotNone(prov, "the ears listen's reply has no provisional")
        self.assertIn("hearings.poll() == null && hearings.current(g)", prov.group(1))
        self.assertIn("provisionalGen = g;", prov.group(1))
        port = self.method("public String provisional()")
        self.assertIn("hearings.current(g) && hearings.poll() == null", port)

    def test_the_connection_is_kept_warm_while_explore_runs(self):
        """Owner 2026-10-02: no cold first turn. A cold HTTPS connection costs about 0.4 s on
        the robot; after KEEP_WARM_IDLE_MS with no Claude request a free models-page GET
        keeps the pooled connection alive. Every request marks the time; the check runs on
        the timer from the start and stops with Explore."""
        self.assertRegex(self.a, r"KEEP_WARM_IDLE_MS = \d+;")
        self.assertRegex(self.a, r"KEEP_WARM_CHECK_MS = \d+;")
        ctor = self.method("    ClaudeCuriosity(Context context) {")
        self.assertRegex(ctor, r"timer\.scheduleWithFixedDelay\(")
        warm = self.method("private void keepWarmIfIdle(")
        self.assertIn("api.keepWarm(", warm)
        self.assertIn("now - lastRequestAt < KEEP_WARM_IDLE_MS", warm)
        self.assertIn("released", warm)
        gated = self.a.split("private final class Gated {", 1)[1].split("\n    }\n", 1)[0]
        self.assertEqual(gated.count("lastRequestAt = System.currentTimeMillis();"), 2)
        logs = " ".join(re.findall(r"Log\.[diwe]\((.*?)\);", warm, re.S))
        self.assertIn('"keep-warm ', logs)
        self.assertIn('" ms"', logs)

    def test_the_turn_log_says_the_request_shape_without_words(self):
        turn = self.method("private void oneTurn(")
        logs = " ".join(re.findall(r"Log\.[diwe]\((.*?)\);", turn, re.S))
        for word in ("system ", " chars, max_tokens ", "effort ", " ms", "line at "):
            self.assertIn(word, logs)


class FaceCropStoresOnlyFacesTest(unittest.TestCase):
    """Owner report: a stored face showed the wall. The top quarter of a loose or
    small person box stood in whenever FaceDetector found nothing, and was stored."""

    def test_the_cropper_has_no_stand_in_and_detects_with_yunet(self):
        # Android's FaceDetector missed an obvious frontal face in glasses (~115 px wide
        # in 640x480), so nothing was ever stored: YuNet on ONNX Runtime replaced it.
        cropper = code_only(src("FaceCropper.java"))
        self.assertNotIn("topOfPerson", cropper)
        self.assertNotIn("FaceDetector", cropper)
        self.assertIn('MODEL = "face_yunet.onnx"', cropper)
        self.assertIn("env.createSession(HttpUtil.readAssetBytes(context, MODEL)", cropper)
        self.assertIn("YuNetDecoder.largestInside(", cropper)
        self.assertIn("return Result.NONE;", cropper)

    def test_the_face_model_uses_few_threads_and_is_freed_on_release(self):
        cropper = code_only(src("FaceCropper.java"))
        threads = int(re.search(r"THREADS = (\d+);", cropper).group(1))
        self.assertIn(threads, (1, 2))
        self.assertIn("setIntraOpNumThreads(THREADS)", cropper)
        self.assertIn("synchronized void close()", cropper)
        self.assertIn("public synchronized Result crop(", cropper)
        a = code_only(src("ClaudeCuriosity.java"))
        body = re.search(r"void release\(\)\s*\{(.*?)\n    \}", a, re.S)
        self.assertIn("cropper.close()", body.group(1))

    def test_the_face_model_is_packaged(self):
        self.assertTrue((REPO / "mode-explore" / "assets" / "face_yunet.onnx").is_file())

    def test_no_face_skips_the_match_and_stores_nothing(self):
        a = code_only(src("ClaudeCuriosity.java"))
        person = a[a.index("private MatchAnswer person("):]
        person = person[:person.index("\n    }\n")]
        self.assertLess(person.index("if (found == null)"), person.index("RobotPeopleClient.gallery("))
        self.assertIn("return facelessMeeting(", person[:person.index("RobotPeopleClient.gallery(")])
        self.assertIn("MatchAnswer.faceless()", a[a.index("private MatchAnswer facelessMeeting("):])
        keep = a[a.index("private Answer keep("):]
        self.assertLess(keep.index("return hello("), keep.index("RobotPeopleClient.addPerson("))

    def test_a_blank_name_skips_the_store_and_gets_the_hello(self):
        """R19: no caller can store an unnamed face, even if the brain regresses."""
        a = code_only(src("ClaudeCuriosity.java"))
        keep = a[a.index("private Answer keep("):]
        keep = keep[:keep.index("RobotPeopleClient.addPerson(")]
        self.assertRegex(keep, r"name == null \|\| name\.trim\(\)\.isEmpty\(\)")
        self.assertEqual(keep.count("return hello("), 2)

    def test_the_hello_never_promises_to_remember(self):
        prompts = src("ExplorePrompts.java")
        hello = prompts[prompts.index("static String welcomeAsk("):]
        hello = hello[:hello.index("}")]
        self.assertIn("do not say or", hello)
        self.assertIn("will NOT remember", hello)
        # The reason is the true one for each caller: no name heard, or no face found.
        self.assertIn("no name to remember them by", hello)
        self.assertIn("could not get a good look at their face", hello)


class OnDeviceMatchWiringTest(unittest.TestCase):
    """Face plan U6: the match is made on the robot (KTD3, KTD5, KTD7, KTD8, KTD11, KTD12)."""

    def setUp(self):
        self.a = code_only(src("ClaudeCuriosity.java"))
        person = self.a[self.a.index("private MatchAnswer person("):]
        self.person = person[:person.index("\n    }\n")]

    def body(self, signature):
        b = self.a[self.a.index(signature):]
        return b[:b.index("\n    }\n")]

    def test_person_sends_no_image_request_and_calls_the_gallery_settings_and_checks(self):
        for gone in ("claude.messages(", "ClaudeApi.jpegBlock(", "RobotPeopleClient.recent("):
            self.assertNotIn(gone, self.person, gone)
        for call in ("cropper.locate(", "RobotSettingsClient.fetchFaceSettings(", "FaceAlign.align(",
                     "FaceQuality.check(", "embedder.embed(", "RobotPeopleClient.gallery(", "FaceMigration.ready(",
                     "FaceMatcher.match(", "record("):
            self.assertIn(call, self.person, call)
        record = self.body("private long record(")
        self.assertIn("RobotPeopleClient.recordCheck(", record)
        # Every outcome is recorded: no face, rejected with its reason, not ready and the bands.
        for code in ("FaceCheck.NO_FACE", "FaceCheck.REJECTED", "FaceCheck.NOT_READY", "FaceCheck.CONFIDENT",
                     "FaceCheck.CLOSE", "FaceCheck.WEAK", "FaceCheck.TOO_DARK", "FaceCheck.TOO_BLURRY",
                     "FaceCheck.TOO_SMALL"):
            self.assertIn(code, self.a, code)

    def test_the_answer_carries_no_lines(self):
        for lines in ("ExplorePrompts.LINES_ASK", "ClaudeReplies.lines(", "textOnly("):
            self.assertNotIn(lines, self.person, lines)
        self.assertIn(".withMatch(", self.person)

    def test_the_recently_met_check_still_uses_recent(self):
        self.assertIn("RobotPeopleClient.recent(", self.body("private Recently checkRecentlyMet("))

    def test_an_old_launcher_meets_facelessly_with_a_fixed_reason(self):
        self.assertIn("LauncherProtocol.LAUNCHER_TOO_OLD", self.a)
        self.assertIn("private MatchAnswer facelessBecause(", self.a)
        too_old = self.body("private MatchAnswer facelessBecause(")
        self.assertIn("return facelessMeeting(", too_old)

    def test_storing_needs_the_probe_and_uses_add_person_with_the_embedding(self):
        for sig in ("private Answer keep(", "public void keep("):
            b = self.body(sig)
            self.assertIn("RobotPeopleClient.addPerson(", b, sig)
            self.assertIn("FaceMatcher.MODEL_ID", b, sig)
            self.assertIn(".probe", b, sig)
        self.assertNotIn("RobotPeopleClient.add(", self.a)

    def test_a_late_match_cannot_reach_the_next_meeting(self):
        # PR #23 review P1: a match that outlives its meeting must not publish into the next one.
        # The opener's crop and touch()'s id come from the current meeting's own record,
        # never from adapter-wide fields a stale worker could still write.
        for gone in ("meetFace", "matchedId"):
            self.assertNotIn(gone, self.a, gone)
        turn = self.body("public void turn(")
        self.assertIn("meeting", turn)
        self.assertIn(".storeCrop", turn)
        touch = self.body("public void touch(")
        self.assertIn("meeting", touch)
        self.assertIn(".storeId", touch)

    def test_a_check_recorded_after_its_meeting_ended_is_closed(self):
        # match() publishes the new meeting before closing the old one's check, and record()
        # keeps the handle before asking whether its meeting is still current: whichever runs
        # second sees the other's write, so a late check never stays pending.
        match = self.body("public void match(")
        self.assertLess(match.index("meeting = mf;"), match.index("FaceCheck.ENDED_WITHOUT_ANSWER"))
        record = self.body("private long record(")
        self.assertLess(record.index("mf.checkHandle = h;"), record.index("meeting != mf"))
        self.assertIn("FaceCheck.ENDED_WITHOUT_ANSWER", record[record.index("meeting != mf"):])

    def test_the_embedder_is_freed_on_release(self):
        self.assertIn("embedder.close()", self.body("void release()"))

    def test_migration_starts_with_explore_and_runs_only_when_the_brain_allows(self):
        app = code_only(src("ModeApp.java"))
        start = app[app.index("void startExplore()"):]
        self.assertIn("curiosity.startMigration()", start[:start.index("\n    }\n")])
        work = self.body("public void faceWork(")
        self.assertIn("runMigration()", work)
        self.assertIn("new FaceMigration(", self.a)
        brain = code_only(src("ExploreBrain.java"))
        self.assertIn("port.faceWork(", brain)
        self.assertIn("port.migrated()", brain)

    def test_debug_frames_roll_fifty_in_private_storage(self):
        debug = self.body("private void debugFace(")
        self.assertIn("FACE_FRAMES", debug)
        self.assertIn('FACE_FRAMES = "face-frames"', self.a)
        self.assertIn("FACE_FRAMES_KEPT = 50", self.a)
        self.assertIn("app.getFilesDir()", debug)


class FaceDebugSwitchTest(unittest.TestCase):
    """The owner's crop check: behind log.tag.MikoExploreFaceDebug=DEBUG, the last crop
    and its frame go to Explore's private files directory, never shared storage or the log."""

    def test_the_switch_writes_private_files_only(self):
        a = code_only(src("ClaudeCuriosity.java"))
        self.assertIn('FACE_DEBUG_TAG = "MikoExploreFaceDebug"', a)
        self.assertIn("Log.isLoggable(FACE_DEBUG_TAG, Log.DEBUG)", a)
        self.assertIn('LAST_FACE = "last-face.jpg"', a)
        self.assertIn('LAST_FACE_SRC = "last-face-src.jpg"', a)
        self.assertIn("app.getFilesDir()", a)
        for shared in ("getExternal", "Environment.", "MediaStore", "Base64", "/sdcard"):
            self.assertNotIn(shared, a, shared)

    def test_the_tag_fits_androids_limit(self):
        self.assertLessEqual(len("MikoExploreFaceDebug"), 23)


class BrainTraceIsPrivateTest(unittest.TestCase):
    """The brain's notes go to logcat (ModeApp's trace), so they follow the same
    rule: no spoken lines, transcripts, or names, and a person pick's label
    (Claude's description of them) stays out. Detector class labels are fine."""

    PRIVATE = re.compile(r"\b(\w*[lL]ine|text|transcript|name|named|heard)\b|\.text\b")

    def test_notes_carry_no_lines_transcripts_or_names(self):
        offenders = []
        for call in re.findall(r"\bnote\((.*?)\);", code_only(src("ExploreBrain.java")), re.S):
            bare = re.sub(r'"(?:\\.|[^"\\])*"', "", call)
            bare = re.sub(r"[\w.]+\s*[!=]=\s*null", "", bare)
            if self.PRIVATE.search(bare):
                offenders.append(" ".join(call.split()))
        self.assertEqual(offenders, [])

    def test_a_person_picks_label_is_never_noted(self):
        brain = code_only(src("ExploreBrain.java"))
        for call in re.findall(r"\bnote\((.*?)\);", brain, re.S):
            if "a.toString()" in call or "pick.toString()" in call:
                self.assertIn("Kind.PERSON", call, f"a pick is noted without hiding a person: {call}")
            self.assertNotRegex(call, r"\b(a|pick)\.box\.label\b")

    def test_the_trace_is_what_reaches_logcat(self):
        self.assertIn('Log.i("ExploreBrain", message)', src("ModeApp.java"))


class ConversationWiringTest(unittest.TestCase):
    """Meeting plan U8: the live adapter binds every new port method, the prompt
    wording is the one source the chat bench copies byte for byte, the ears route
    a newcomer past the reply, the camera parks its detector, the state page
    counts repeats, and nothing heard or said reaches the trace."""

    BENCH = REPO / "scripts" / "claude-chat-bench.py"

    @staticmethod
    def java_string(src, name):
        """A static final String constant's value: its concatenated literals, unescaped."""
        m = re.search(r"static final String " + name + r"\s*=\s*(.*?);\n", src, re.S)
        assert m, name
        parts = re.findall(r'"((?:\\.|[^"\\])*)"', m.group(1))
        return "".join(parts).encode("utf-8").decode("unicode_escape")

    def bench(self):
        import importlib.util
        spec = importlib.util.spec_from_file_location("claude_chat_bench", self.BENCH)
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        return mod

    def test_the_prompt_wording_is_the_one_the_bench_uses_byte_for_byte(self):
        prompts = src("ExplorePrompts.java")
        bench = self.bench()
        for name in ("GUARD", "REMINDER", "NOTES_HEADING", "SCHEMA_PREAMBLE"):
            self.assertEqual(self.java_string(prompts, name), getattr(bench, name), name)
        self.assertIn("never say anything a coworker would be fired for saying", self.java_string(prompts, "GUARD"))
        java_props = re.findall(r'"(\w+)", (?:type|object|arrayOf|enumOf)\(', prompts.split("REPLY_SCHEMA = object(")[1].split(";")[0])
        bench_props = []
        for name, schema in bench.REPLY_SCHEMA["properties"].items():
            bench_props.append(name)
            bench_props += list(schema.get("properties", {}))
        self.assertEqual(java_props, bench_props)
        self.assertEqual(bench.REPLY_SCHEMA["properties"]["feedback"]["properties"]["kind"]["enum"],
                         ["none", "suggestion", "complaint", "praise", "bug"])
        # Owner 2026-10-03: the tools, their descriptions and order are the robot's (ChatTools).
        tools = src("ChatTools.java")
        for name in ("RESPOND_DESCRIPTION", "LOOK_DESCRIPTION", "RECALL_DESCRIPTION", "STATUS_DESCRIPTION",
                     "PLACES_DESCRIPTION"):
            self.assertEqual(self.java_string(tools, name), getattr(bench, name), name)
        order = re.findall(r'out\.add\(new Object\[\]\{(\w+),', tools)
        names = {k: self.java_string(tools, k) for k in ("RESPOND", "LOOK", "RECALL", "STATUS", "PLACES")}
        # Owner 2026-10-03: the action tools (ChatActions) follow, in their own order, byte for byte.
        actions = src("ChatActions.java")
        act_order = re.findall(r'out\.add\(new Object\[\]\{(\w+),', actions)
        self.assertIn("out.addAll(ChatActions.definitions());", tools)
        act_names = [self.java_string(actions, k) for k in act_order]
        self.assertEqual([names[k] for k in order] + act_names, [t["name"] for t in bench.TOOLS])
        self.assertEqual(act_names, bench.ACTION_NAMES)
        for k in act_order + ["PLAN"]:
            self.assertEqual(self.java_string(actions, k + "_DESCRIPTION"), getattr(bench, k + "_DESCRIPTION"), k)
        for name in ("OWNER_NOTE_HEADING", "OWNER_NOTE_GUARD", "TASK_SYSTEM", "CALL_OPENER", "CALL_WORDS",
                     "FACELESS_OPENER", "NAME_ASK", "NUDGE", "LAST_NAME_ASK"):
            self.assertEqual(self.java_string(prompts, name), getattr(bench, name), name)
        recall = re.search(r'RECALL_SCHEMA = ExplorePrompts\.object\("name", ExplorePrompts\.described\(\s*'
                           r'ExplorePrompts\.type\("string"\), "(.*?)"\)\);', tools, re.S)
        self.assertEqual(recall.group(1), bench.RECALL_SCHEMA["properties"]["name"]["description"])
        self.assertIn("closed_threads", java_props)
        # The prefix order the bench renders: guard, quoted persona, reminder, notes heading, preamble.
        prefix = re.search(r"static String systemPrefix\((.*?)\n    \}", prompts, re.S).group(1)
        for a, b in (("GUARD", "PERSONA_HEADING"), ("PERSONA_HEADING", "REMINDER"), ("REMINDER", "NOTES_HEADING"),
                     ("NOTES_HEADING", "SCHEMA_PREAMBLE")):
            self.assertLess(prefix.index(a), prefix.index(b), (a, b))
        self.assertIn('"\\n\\"\\"\\"\\n"', prefix)

    def test_the_adapter_binds_the_voice_port_methods_and_logs_no_ids(self):
        """Owner 2026-10-02: whose voice reaches the conversation through the ears session, and
        the people store enrols, counts and scores voices; ids never reach a log."""
        a = code_only(src("ClaudeCuriosity.java"))
        for method in ("voice", "recallPerson", "enrolVoice", "voiceScore", "voiceScored", "cancelVoiceScore"):
            self.assertRegex(a, r"public [\w<>.]+ " + method + r"\(", method)
        self.assertIn("public void recallName(final String name, final long[] voiceAts, final long timeoutMs)", a)
        self.assertIn("return s == null ? null : s.pollVoice();", a)
        for line in re.findall(r"Log\.\w\(TAG, (.*?)\);", a):
            if "voice" in line:
                self.assertNotIn("personId", line)
        ears = code_only(src("EarsAdapter.java"))
        self.assertIn("public void onVoice(long at, String person, float score, int band, float margin)", ears)

    def test_the_adapter_binds_every_new_port_method(self):
        a = code_only(src("ClaudeCuriosity.java"))
        for method in ("turn", "turnAnswer", "cancelTurn", "speculateTurn", "lateNotes", "turnTailPending",
                       "provisional", "notesDelta", "notesDeltaAnswer", "cancelNotesDelta", "forget",
                       "forgetAnswer", "cancelForget", "chatListen", "keep", "keptAnswer", "cancelKeep"):
            self.assertRegex(a, r"public [\w<>.]+ " + method + r"\(", method)
        turn = re.search(r"private void oneTurn\((.*?)\n    \}", a, re.S).group(1)
        # Owner 2026-10-03: the reply is the respond tool's input, so no JSON schema is sent.
        self.assertIn("claude.conversation(settings, body.system, messages, null, TURN_EFFORT", turn)
        self.assertIn("ChatRound.fly(body, request,", turn)
        for method in ("toolAsk", "lookAnswer"):
            self.assertRegex(a, r"public [\w<>.]+ " + method + r"\(", method)
        body = re.search(r"static Body body\((.*?)\n    \}", code_only(src("ChatRound.java")), re.S).group(1)
        self.assertIn("ExplorePrompts.systemPrefix(request.persona, request.notes, request.ownerName,\n"
                      "                request.ownerNote)", body)
        self.assertIn("ExplorePrompts.openerAsk(request.name)", body)
        self.assertIn("ExplorePrompts.avoidQuestion(request.avoidQuestion)", body)
        self.assertIn("userMessage(saidBefore, face, ask)", body)
        self.assertIn("ClaudeApi.jpegBlock(face)", code_only(src("ChatRound.java")))
        self.assertIn("request.heard == null && request.transcript.isEmpty() && met != null\n                ? met.storeCrop : null", a)
        self.assertIn("NameExtractor.validName(t.nameGiven)", code_only(src("ChatRound.java")))
        self.assertIn("RobotPeopleClient.mergeNotes(app, personId, notesUpdate)", a)
        self.assertIn("RobotPeopleClient.forget(app, personId)", a)
        keep = re.search(r"public void keep\((.*?)\n    \}", a, re.S).group(1)
        self.assertIn("RobotPeopleClient.addPerson(app, face, name, FaceMatcher.MODEL_ID, probe)", keep)
        self.assertNotIn("debugFace", keep)
        listen = re.search(r"public void chatListen\((.*?)\n    \}", a, re.S).group(1)
        self.assertIn("earsListen(s, maxMs, newcomerAngleDeg)", listen)
        ears_listen = re.search(r"private void earsListen\((.*?)\n    \}", a, re.S).group(1)
        self.assertIn("s.listen(maxMs, newcomerAngleDeg,", ears_listen)
        conv = re.search(r"private MatchAnswer forConversation\((.*?)\n    \}", a, re.S).group(1)
        self.assertIn("RobotSettingsClient.fetchConversation(app).persona", conv)
        self.assertIn("RobotPeopleClient.notesOf(app, personId)", conv)
        self.assertIn("a.withConversation(persona, personId, notesJson, asked)", conv)
        # Face plan U7: an added photo answers the joined person with their notes, too.
        self.assertEqual(a.count("forConversation("), 6)
        port = code_only(src("CuriosityPort.java"))
        for sig in ("void chatListen(long maxMs, float newcomerAngleDeg);", "void keep(String name, long timeoutMs);",
                    "Kept keptAnswer();", "final String avoidQuestion;"):
            self.assertIn(sig, port, sig)
        api = code_only((REPO / "shared" / "src" / "com" / "miko3" / "shared" / "ClaudeApi.java").read_text())
        self.assertIn("public MessageResult conversation(ClaudeAccess access, String system, List<Map<String, Object>> messages,", api)
        self.assertIn("private volatile boolean effortUnsupported;", api)

    def test_the_ears_route_a_newcomer_past_the_reply(self):
        a = code_only(src("EarsAdapter.java"))
        self.assertIn("void listen(long maxMs, float newcomerAngleDeg, Reply r)", a)
        heard = re.search(r"public void onHeard\((.*?)\n    \}", a, re.S).group(1)
        self.assertIn("Math.abs(angle) > replyAngleDeg", heard)
        self.assertIn("if (reply != null && words && !newcomer)", heard)

    def test_the_camera_parks_the_detector_through_the_conversation(self):
        cam = code_only(src("ExploreCamera.java"))
        self.assertIn("public void park(boolean p)", cam)
        # Review 2026-10-03: a parked frame is neither detected nor copied.
        self.assertIn("if (busy || parked) {\n                    return;\n                }", cam)
        brain = code_only(src("ExploreBrain.java"))
        self.assertIn("camera.park(parked)", brain)
        # Face plan U7: the close match's question keeps it open and parked on the conversation path too.
        self.assertIn("state.chats() || (state == State.MEET || state.confirms()) && chatLikely()", brain)

    def test_the_resolver_and_the_added_photo_run_on_the_robot(self):
        """Face plan U7 (KTD6, KTD10, KTD12): names resolve by id against the store, never through Claude."""
        a = code_only(src("ClaudeCuriosity.java"))
        for method in ("nameIn", "resolveName", "resolveLastName", "resolved", "cancelResolve", "addPhoto",
                       "photoAdded", "cancelAddPhoto", "checkOutcome", "meetingOver"):
            self.assertRegex(a, r"public [\w<>.]+ " + method + r"\(", method)
        resolve = re.search(r"private Resolved resolveNow\((.*?)\n    \}", a, re.S).group(1)
        self.assertIn("RobotPeopleClient.idsNamed(app, name)", resolve)
        self.assertIn("NameResolver.resolve(name, probe, ids, entries, close, nameOnly)", resolve)
        self.assertNotIn("claude.", resolve)
        last = re.search(r"private Resolved resolveLastNow\((.*?)\n    \}", a, re.S).group(1)
        self.assertIn("NameResolver.afterLastName(first, lastName, stored)", last)
        self.assertNotIn("claude.", last)
        photo = re.search(r"private MatchAnswer addPhotoNow\((.*?)\n    \}", a, re.S).group(1)
        self.assertIn("RobotPeopleClient.addPhoto(app, id, face, FaceMatcher.MODEL_ID, probe)", photo)
        self.assertNotIn("addPerson", photo)
        self.assertIn("NameExtractor.extract(transcript)", a)
        over = re.search(r"public void meetingOver\((.*?)\n    \}", a, re.S).group(1)
        self.assertIn("checkOutcome(meeting, FaceCheck.ENDED_WITHOUT_ANSWER, null)", over)
        self.assertIn("stranger.withConfirm(confirmName(r.bestId))", a)

    def test_the_state_page_counts_repeats_and_the_clips_are_reactions(self):
        self.assertIn('"repeats"', src("ExploreState.java"))
        self.assertIn('REPEATS("repeats")', src("ExploreBrain.java"))
        self.assertIn('name.endsWith(".webm")', src("ClipPlayer.java"))

    PRIVATE = re.compile(r"\b(\w*[lL]ine|text|transcript|name|named|heard|given|question)\b|\.text\b")

    def test_the_conversation_notes_carry_no_words_heard_or_said(self):
        offenders = []
        for call in re.findall(r"\bhost\.note\((.*?)\);", code_only(src("ChatSession.java")), re.S):
            bare = re.sub(r'"(?:\\.|[^"\\])*"', "", call)
            bare = re.sub(r"[\w.]+\s*[!=]=\s*null", "", bare)
            if self.PRIVATE.search(bare):
                offenders.append(" ".join(call.split()))
        self.assertEqual(offenders, [])
        session = code_only(src("ChatSession.java"))
        self.assertNotIn("import android", session)
        self.assertNotIn("System.out", session)


class FacelessOpenerTest(unittest.TestCase):
    """Owner 2026-10-02 (at home): "less interruptions as he tries to find your face ... he can
    just say 'What's your name?' and then base his conversation off the name." A conversation
    that opens with no usable face greets them and asks their name; he never asks to see their
    face (the crouch invitation is gone), and the face is checked silently in the background.
    A face-in-hand stranger's opener still asks the name."""

    @staticmethod
    def constant(name):
        return ConversationWiringTest.java_string(src("ExplorePrompts.java"), name)

    def test_the_faceless_opener_asks_the_name_and_never_asks_to_see_their_face(self):
        text = self.constant("FACELESS_OPENER")
        for words in ("ask their name naturally", "What's your name?", "Never mention their face",
                      "use it now and then for the rest of the conversation"):
            self.assertIn(words, text)
        for gone in ("crouch down to his level", "Do not ask their name", "can't see their face from down here",
                     "get a good look"):
            self.assertNotIn(gone, text)

    def test_the_crouch_invitation_and_face_seen_are_gone(self):
        p = code_only(src("ExplorePrompts.java"))
        for gone in ("CANT_SEE", "FACE_SEEN", "down to his level", "crouch down"):
            self.assertNotIn(gone, p)
        for f in ("ChatRound.java", "ChatSession.java", "CuriosityPort.java"):
            code = code_only(src(f))
            for gone in ("cantSee", "CANT_SEE", "FACE_SEEN", "faceSeen && name"):
                self.assertNotIn(gone, code, f)

    def test_the_faceless_opener_is_warm_curious_and_fits_a_roaming_meeting(self):
        """Owner 2026-10-02: flat openers; a roaming or cue pick may now be met faceless, so the
        opener no longer says they asked for him."""
        text = self.constant("FACELESS_OPENER")
        for words in ("at most two short sentences", "greet them warmly with one specific, curious thing",
                      "never invent anything"):
            self.assertIn(words, text)
        self.assertNotIn("asked for him", text)

    def test_a_face_in_hand_strangers_opener_still_asks_the_name(self):
        p = src("ExplorePrompts.java")
        body = p[p.index("static String openerAsk("):]
        body = body[:body.index("\n    }\n")]
        self.assertIn("ask \"\n                    + \"their name", body)

    def test_the_turn_picks_the_opener_by_the_request_and_says_once_who_they_turned_out_to_be(self):
        turn = re.search(r"static Body body\((.*?)\n    \}", code_only(src("ChatRound.java")), re.S).group(1)
        self.assertIn("request.faceless ? ExplorePrompts.FACELESS_OPENER : ExplorePrompts.openerAsk(request.name)", turn)
        self.assertIn("e.heard == null ? first : e.heard", turn)
        self.assertIn("request.heard == null ? first : request.heard", turn)
        self.assertRegex(turn, r"if \(request\.recalled\) \{\s*ask = ask \+ \"\\n\\n\" \+ \(request\.cue == "
                               r"CuriosityPort\.TurnRequest\.IdCue\.BY_VOICE\s*\? ExplorePrompts\.recalledByVoice\(request\.name\)"
                               r" : ExplorePrompts\.recalled\(request\.name\)\);")
        recalled = src("ExplorePrompts.java")
        recalled = recalled[recalled.index("static String recalled("):]
        recalled = recalled[:recalled.index("\n    }\n")]
        for words in ("someone he remembers", "Answer what they said first", "Never mention their face"):
            self.assertIn(words, recalled)
        port = code_only(src("CuriosityPort.java"))
        for sig in ("final boolean faceless;", "final boolean recalled;"):
            self.assertIn(sig, port)
        session = code_only(src("ChatSession.java"))
        self.assertIn(".face(openedFaceless).call(called).withFacts(host.toolFacts()).noteBy(noteName)\n"
                      "                .recalledNow(recalledDue).cue(idCue(heardText));", session)

    def test_the_face_checks_run_all_conversation_every_8_s(self):
        tuning = code_only(src("ExploreTuning.java"))
        self.assertNotIn("chatFaceTries", tuning)
        self.assertIn("private long chatFaceGapMs = 8000;", tuning)
        session = code_only(src("ChatSession.java"))
        self.assertNotIn("chatFaceTries", session)

    def test_the_adapter_looks_a_name_up_by_name_alone_and_never_logs_it(self):
        a = code_only(src("ClaudeCuriosity.java"))
        body = re.search(r"private CuriosityPort\.Recalled recallNow\((.*?)\n    \}", a, re.S).group(1)
        for needle in ("RobotPeopleClient.idsNamed(app, name)", "NameResolver.byName(name, stored)",
                       "RobotPeopleClient.addNamed(app, d.name)", "RobotPeopleClient.hasFace(app, d.personId)",
                       "RobotPeopleClient.notesOf(app, d.personId)"):
            self.assertIn(needle, body)
        # Logs carry fixed text, ids and counts only: never the name variable or a stored name.
        for log in re.findall(r"Log\.\w\(TAG, (.*?)\);", body, re.S):
            for leak in ("+ name", "d.name +", "stored.get(", "+ n "):
                self.assertNotIn(leak, log, log)
        resolve = re.search(r"private Resolved resolveNow\((.*?)\n    \}", a, re.S).group(1)
        self.assertIn("NameResolver.resolve(name, probe, ids, entries, close, nameOnly)", resolve)


class CallConversationTest(unittest.TestCase):
    """Owner 2026-10-02: conversation first. A call's conversation opens before he has seen
    them: its opener is a short greeting-question (never the name), the words said with the
    wake word are turn 1's message, the next turn may ask the name (never to see their face),
    and opening it costs no Claude request."""

    @staticmethod
    def constant(name):
        return ConversationWiringTest.java_string(src("ExplorePrompts.java"), name)

    def test_the_call_opener_greets_with_a_question_and_the_next_turn_may_ask_the_name(self):
        text = self.constant("CALL_OPENER")
        for words in ("greeting with a question", "has not seen them yet", "Do not ask their name yet",
                      # Owner 2026-10-02: "Hey! What's up?" every time; one curious thing, nothing invented.
                      "at most two short sentences", "one specific", "never invent anything",
                      'Not a bare "what\'s up"'):
            self.assertIn(words, text)
        self.assertNotIn("crouch", text)
        self.assertNotIn("Hey! What's up?", text)
        self.assertIn("Do not ask their name yet", self.constant("CALL_WORDS"))
        ask = self.constant("NAME_ASK")
        for words in ("may ask their name", "What's your name, by the way?", "Never mention their face"):
            self.assertIn(words, ask)

    def test_a_call_with_words_is_answered_warmly_with_one_curious_question(self):
        """Robot 2026-10-03: "Hey Miko he..." got a flat "Hey, what's up?": the call-with-words
        note answers what they said warmly, like CALL_OPENER, and lets a TV be not addressed."""
        text = self.constant("CALL_WORDS")
        for words in ("has not seen them yet", "Answer what they said warmly", "at most two short sentences",
                      "one specific, curious follow-up question", "never invent anything",
                      'Not a bare "what\'s up"', "Do not ask their name yet",
                      "addressed is true unless it is clearly not them (another voice, a TV or radio)"):
            self.assertIn(words, text)
        self.assertNotIn("answer them naturally, in a short line", text)
        self.assertTrue(text.startswith("(") and text.endswith(")"), text)

    def test_the_turn_picks_the_call_opener_first_and_appends_the_call_notes(self):
        turn = re.search(r"static Body body\((.*?)\n    \}", code_only(src("ChatRound.java")), re.S).group(1)
        self.assertRegex(turn, r"String first = request\.called \? ExplorePrompts\.CALL_OPENER\s*"
                               r": request\.faceless \? ExplorePrompts\.FACELESS_OPENER")
        self.assertRegex(turn, r"if \(request\.called && request\.heard != null && request\.transcript\.isEmpty\(\)\) \{\s*"
                               r"ask = ask \+ \"\\n\\n\" \+ ExplorePrompts\.CALL_WORDS;")
        # Owner 2026-10-02: the turn after a call's opener may ask the name while he doesn't know it.
        # Owner 2026-10-02: and so may the turn after a weak voice match (IdCue.ASK_NAME).
        self.assertRegex(turn, r"if \(request\.name == null && request\.heard != null && \(request\.called\s*"
                               r"&& request\.transcript\.size\(\) == 1\s*"
                               r"\|\| request\.cue == CuriosityPort\.TurnRequest\.IdCue\.ASK_NAME\)\) \{\s*"
                               r"ask = ask \+ \"\\n\\n\" \+ ExplorePrompts\.NAME_ASK;")

    def test_the_call_opener_lets_a_tv_or_another_voice_be_not_addressed(self):
        """Owner 2026-10-02: a TV in the background was answered because the call opener said
        addressed is always true; it is true unless it is clearly not them."""
        text = self.constant("CALL_OPENER")
        self.assertNotIn("addressed is always true", text)
        self.assertIn("addressed is true unless it is clearly not them (another voice, a TV or radio)", text)

    def test_voice_cues_ask_the_last_name_or_say_the_name_recognised_by_voice(self):
        last = self.constant("LAST_NAME_ASK")
        for words in ("ask their last name naturally", "Never say why he asks", "full name, first and last",
                      "name_given"):
            self.assertIn(words, last)
        prompts = src("ExplorePrompts.java")
        by_voice = re.search(r"static String recalledByVoice\(String name\) \{(.*?)\n    \}", prompts, re.S).group(1)
        for words in ("by their voice", "using their name", "naturally once", "wrong person"):
            self.assertIn(words, by_voice)
        turn = re.search(r"static Body body\((.*?)\n    \}", code_only(src("ChatRound.java")), re.S).group(1)
        self.assertIn("ExplorePrompts.recalledByVoice(request.name)", turn)
        self.assertRegex(turn, r"request\.cue == CuriosityPort\.TurnRequest\.IdCue\.ASK_LAST_NAME && request\.heard "
                               r"!= null\) \{\s*ask = ask \+ \"\\n\\n\" \+ ExplorePrompts\.LAST_NAME_ASK;")

    def test_a_run_of_unanswered_listens_gets_one_gentle_follow_up_before_the_end(self):
        """Owner 2026-10-02 ("oh hi and then he doesn't really talk to us"): instead of the
        sign-off he re-engages once with a gentle follow-up; words from them reset it."""
        nudge = self.constant("NUDGE")
        for words in ("gentle follow-up", "invites them to keep talking", "Never complain", "addressed is true"):
            self.assertIn(words, nudge)
        session = code_only(src("ChatSession.java"))
        self.assertIn("requestTurn(now, ExplorePrompts.NUDGE);", session)
        heard = re.search(r"private void onHeard\(long now, String text\) \{(.*?)\n    \}", session, re.S).group(1)
        self.assertIn("nudged = false;", heard)
        unanswered = re.search(r"private void onUnanswered\(long now, boolean wordless\) \{(.*?)\n    \}",
                               session, re.S).group(1)
        self.assertLess(unanswered.index("nudge(now);"), unanswered.index("signOff(now);"))
        # A reply judged not said to him ends any conversation only after a call's count.
        self.assertIn("int max = tuning.callChatUnansweredMax;", session)

    def test_chattier_tuning(self):
        """Owner 2026-10-02: a longer no-reply cap, faceless meetings for strong or spoken-to
        picks, and curiosity stops (and so remarks) about twice as often."""
        t = src("ExploreTuning.java")
        for decl in ("private long chatNoReplyMs = 60000;", "private float facelessMeetMinScore = 0.65f;",
                     "private long facelessMeetVoiceMs = 5000;", "private long curiosityMinMs = 9000;",
                     "private long curiosityMaxMs = 14000;",
                     # Coordinator 2026-10-02 (home 20:51-20:55): people pause longer; the recogniser
                     # sometimes gets no words, so two re-asks that ask them nearer or louder.
                     "private long unansweredListenMs = 7000;", "private int chatReasksMax = 2;"):
            self.assertIn(decl, t)
        self.assertIn('DIDNT_CATCH = "Sorry, I didn\'t catch that. Could you come a bit closer or speak up?";',
                      src("ChatSession.java"))

    def test_opening_a_call_conversation_reads_the_persona_and_sends_nothing_to_claude(self):
        c = code_only(src("ClaudeCuriosity.java"))
        body = re.search(r"public void callChat\(final long timeoutMs\) \{(.*?)\n    \}", c, re.S)
        self.assertIsNotNone(body)
        self.assertIn("meeting = null;", body.group(1))
        self.assertIn("facelessMeeting(null, -1L)", body.group(1))
        self.assertNotIn("claude.", body.group(1))
        self.assertIn("void callChat(long timeoutMs);", code_only(src("CuriosityPort.java")))

    def test_the_brain_logs_no_words_of_the_callers_message(self):
        brain = code_only(src("ExploreBrain.java"))
        session = code_only(src("ChatSession.java"))
        for text, field, pattern in ((brain, "callChatMessage", r"(?<![\w.])note\((.*?)\);"),
                                     (brain, "c.message", r"(?<![\w.])note\((.*?)\);"),
                                     (session, "message", r"host\.note\((.*?)\);")):
            for call in re.findall(pattern, text, re.S):
                bare = re.sub(r'"(?:\\.|[^"\\])*"', "", call)
                self.assertNotRegex(bare, r"\b" + re.escape(field) + r"\b", call)


class SlimUploadWiringTest(unittest.TestCase):
    """Every image Explore sends to Claude is slimmed in ClaudeApi.jpegBlock
    (test_jpeg_slim); the frames it keeps for face matching and the frame ring
    stay as captured."""

    def test_every_image_block_goes_through_jpeg_block(self):
        a = code_only(src("ClaudeCuriosity.java"))
        self.assertGreaterEqual(a.count("ClaudeApi.jpegBlock("), 5)
        for bypass in ('"image/jpeg"', '"base64"', "Base64."):
            self.assertNotIn(bypass, a, bypass)

    def test_kept_frames_are_not_slimmed(self):
        for name in ("ClaudeCuriosity.java", "ExploreCamera.java", "FrameRing.java", "FaceCropper.java"):
            self.assertNotIn("JpegSlim", code_only(src(name)), name)


class FeedbackWiringTest(unittest.TestCase):
    """Owner 2026-10-02: feedback about himself is detected in the turn, spoken past
    early, passed to the launcher's feedback log, and never traced."""

    def prompts(self):
        return src("ExplorePrompts.java")

    def test_the_preamble_asks_for_feedback_only_about_miko_and_an_acknowledgement(self):
        pre = ConversationWiringTest.java_string(self.prompts(), "SCHEMA_PREAMBLE")
        for phrase in ("feedback (only when the person gives feedback about Miko himself",
                       "suggestion, complaint, praise or bug", "word for word", "at most 25 words",
                       "never for small talk", "kind none", "I'll pass that on to my developer"):
            self.assertIn(phrase, pre, phrase)

    def test_the_feedback_field_comes_last_so_the_line_still_streams_first(self):
        body = self.prompts().split("REPLY_SCHEMA = object(")[1].split(";")[0]
        top = re.findall(r'^            "(\w+)",', body, re.M)
        self.assertEqual(top[0], "addressed")
        self.assertEqual(top[1], "line")
        self.assertEqual(top[-1], "feedback")
        self.assertIn('enumOf("none", "suggestion", "complaint", "praise", "bug")', body)
        early = re.search(r"EARLY_FIELDS = Arrays\.asList\((.*?)\);", code_only(src("ChatRound.java")), re.S).group(1)
        self.assertNotIn("feedback", early)

    def test_the_adapter_passes_feedback_to_the_launcher_and_never_logs_it(self):
        a = code_only(src("ClaudeCuriosity.java"))
        self.assertRegex(a, r"public CuriosityPort\.Feedback lateFeedback\(\)")
        self.assertIn("return flight.lateFeedback();", a)
        body = re.search(r"public void feedback\(final String personId, final CuriosityPort\.Feedback f, "
                         r"final String context\)(.*?)\n    \}", a, re.S)
        self.assertIsNotNone(body)
        b = body.group(1)
        self.assertIn("com.miko3.shared.Feedback.of(f.kind, f.summary, f.quote)", b)
        self.assertIn("RobotPeopleClient.recordFeedback(app, personId, shared, context)", b)
        for call in re.findall(r"Log\.[diwe]\((.*?)\);", b, re.S):
            bare = re.sub(r'"(?:\\.|[^"\\])*"', "", call)
            self.assertNotRegex(bare, r"summary|quote|context|\bshared\b(?!\.kind)|\bf\b", call)
        turn_of = re.search(r"static CuriosityPort\.Turn turnOf\((.*?)\n    \}", code_only(src("ChatRound.java")),
                            re.S).group(1)
        self.assertIn(".withFeedback(t.feedback)", turn_of)

    def test_the_conversation_traces_only_the_kind(self):
        chat = code_only(src("ChatSession.java"))
        passes = re.search(r"private void passOn\(CuriosityPort\.Feedback f\)(.*?)\n    \}", chat, re.S).group(1)
        self.assertIn("port.feedback(personId, f, feedbackContext())", passes)
        for call in re.findall(r"\bnote\((.*?)\);", passes, re.S):
            self.assertNotRegex(call, r"summary|quote", call)
        self.assertIn("passOn(t.feedback)", chat)
        self.assertIn("port.lateFeedback()", chat)



class InstructionsAndAddressedTest(unittest.TestCase):
    """Owner 2026-10-02: he follows a few explicit instructions (go away, go elsewhere, find
    someone, come here, be quiet) through the turn's action field, and a turn not said to him
    (office chatter nearby) is judged by Claude in "addressed", before the line, so a
    not-addressed turn never speaks and counts as unanswered."""

    def prompts(self):
        return src("ExplorePrompts.java")

    def test_the_schema_puts_addressed_first_and_the_action_after_the_name(self):
        body = self.prompts().split("REPLY_SCHEMA = object(")[1].split(";")[0]
        top = re.findall(r'^            "(\w+)",', body, re.M)
        self.assertEqual(top, ["addressed", "line", "question_asked", "name_given",
                               "ends_conversation", "deflected", "notes_update", "feedback"])
        self.assertIn('"addressed", type("boolean")', body)
        self.assertNotIn('"action"', body)

    def test_addressed_leans_true_when_unsure_and_after_he_spoke(self):
        """Owner 2026-10-02 (home log 20:42:53): the reply to his "Hey! What's up?" was judged
        not said to him and got silence."""
        pre = ConversationWiringTest.java_string(self.prompts(), "SCHEMA_PREAMBLE")
        for phrase in ("false only when it is clearly people talking to each other nearby", "when unsure, true",
                       "a reply right after Miko spoke to them is addressed unless it is clearly people talking "
                       "to each other"):
            self.assertIn(phrase, pre, phrase)

    def test_the_guard_makes_him_chatty_within_two_short_sentences(self):
        guard = ConversationWiringTest.java_string(self.prompts(), "GUARD")
        self.assertIn("at most two short sentences", guard)
        self.assertIn("most lines end with a question or an invitation to keep talking, unless the conversation "
                      "is wrapping up", guard)
        self.assertIn("never say anything a coworker would be fired for saying", guard)

    def test_the_preamble_says_when_to_set_the_action_and_addressed(self):
        pre = ConversationWiringTest.java_string(self.prompts(), "SCHEMA_PREAMBLE")
        for phrase in ("addressed (true when their latest message was said to Miko", "talking to each other nearby",
                       "line (what he says; empty when addressed is false)",
                       "only for when the person explicitly asks him to do that",
                       "move, stop, stay, come_here, go_away, be_quiet, find_person, find_thing, go_to_place, wait, "
                       "run_task", "call one alone, never with respond or another action",
                       "an errand of several steps is one run_task", "Write nothing outside a tool",
                       "honestly why he can't", "a kind, honest line that he can't"):
            self.assertIn(phrase, pre, phrase)
        self.assertNotIn("action (", pre)
        self.assertNotIn("target (", pre)
        guard = ConversationWiringTest.java_string(self.prompts(), "GUARD")
        # Robot 2026-10-02 15:22: "errands for other people" made Haiku decline "go to the kitchen and
        # see if anyone's there"; he declines only what he physically can't do.
        self.assertIn("he declines only what he physically can't do", guard)
        self.assertIn("he does with his action tools", guard)
        self.assertNotIn("errands for other people", guard)

    def test_the_early_line_waits_for_addressed_from_the_stream(self):
        a = code_only(src("ClaudeCuriosity.java"))
        cr = code_only(src("ChatRound.java"))
        early = re.search(r"static CuriosityPort\.Turn earlyTurn\((.*?)\n    \}", cr, re.S).group(1)
        self.assertIn("addressed", early)
        self.assertIn('"true".equals(fields.get("addressed"))', early)
        # Owner 2026-10-03: the shared client tells the streamed boolean itself; no transport wrapper.
        self.assertIn("new ClaudeApi(new ClaudeHttpsTransport())", a)
        self.assertNotIn("StreamedText", a)
        turn_of = re.search(r"static CuriosityPort\.Turn turnOf\((.*?)\n    \}", cr, re.S).group(1)
        self.assertIn(".withAddressed(t.addressed)", turn_of)
        # Owner 2026-10-03: the accepted action tool is the turn's act.
        self.assertIn("t = t.withAct(o.act);", turn_of)
        self.assertIn(".withAct(t.act)", turn_of)

    def test_the_brain_never_traces_the_target(self):
        brain = code_only(src("ExploreBrain.java"))
        for call in re.findall(r"\bnote\((.*?)\);", brain, re.S):
            bare = re.sub(r'"(?:\\.|[^"\\])*"', "", call)
            self.assertNotRegex(bare, r"\bintentTarget\b(?!\s*==|\s*!=)", call)
        chat = code_only(src("ChatSession.java"))
        for call in re.findall(r"\bnote\((.*?)\);", chat, re.S):
            bare = re.sub(r'"(?:\\.|[^"\\])*"', "", call)
            self.assertNotRegex(bare, r"\b(target|actionTarget)\b(?!\s*==|\s*!=)", call)


class ToolRoundWiringTest(unittest.TestCase):
    """Owner 2026-10-03: the conversation replies through the respond tool and may run one
    tool round (look, recall_person, robot_status, places). The adapter hands the round's
    ask to the turn that posted it only, a cancelled turn's look stops waiting, recall
    asks the store by name without logging it, and the conversation traces no words."""

    @classmethod
    def setUpClass(cls):
        cls.a = code_only(src("ClaudeCuriosity.java"))

    def method(self, sig):
        m = re.search(re.escape(sig) + r"(.*?)\n    \}", self.a, re.S)
        self.assertIsNotNone(m, sig)
        return m.group(1)

    def test_an_ask_goes_only_to_the_turn_that_posted_it_and_a_new_or_cancelled_turn_drops_it(self):
        ask = self.method("public CuriosityPort.ToolAsk toolAsk()")
        self.assertIn("turns.current(b.gen)", ask)
        self.assertIn("dropToolAsks();", self.method("public void cancelTurn()"))
        self.assertIn("dropToolAsks();", self.method("public void turn(TurnRequest asked, final long timeoutMs)"))
        self.assertIn("dropToolAsks();", self.method("    void release()"))
        self.assertIn("return turns.current(call.gen);", self.method("private void oneTurn("))

    def test_recall_asks_the_store_by_name_and_logs_nothing_of_it(self):
        turn = self.method("private void oneTurn(")
        self.assertIn("RobotPeopleClient.idsNamed(app, name)", turn)
        for call in re.findall(r"Log\.[diwe]\((.*?)\);", turn, re.S):
            bare = re.sub(r'"(?:\\.|[^"\\])*"', "", call)
            self.assertNotRegex(bare, r"\bname\b|preamble|messages|reply", call)
        self.assertIn('", tool round: "', turn)

    def test_the_conversation_traces_no_words_of_the_round_and_charger_ends_nothing(self):
        chat = code_only(src("ChatSession.java"))
        step = re.search(r"private void toolStep\(long now\) \{(.*?)\n    \}", chat, re.S).group(1)
        for call in re.findall(r"host\.note\((.*?)\);", step, re.S):
            bare = re.sub(r'"(?:\\.|[^"\\])*"', "", call)
            self.assertNotRegex(bare, r"ask\.preamble(?!\s*!=)|labels|jpeg|blocked(?!\s*==)", call)
        self.assertIn("port.say(ask.preamble);", step)
        self.assertIn("dropFaceLook(true);", step)
        self.assertNotIn("endOnCharger", chat)
        self.assertNotIn("charger connected", src("ChatSession.java"))

    def test_the_adapter_waits_longer_than_the_preamble_and_the_brains_look(self):
        tools = code_only(src("ChatTools.java"))
        look = int(re.search(r"LOOK_WAIT_MS = (\d+);", tools).group(1))
        pre = int(re.search(r"PREAMBLE_WAIT_MS = (\d+);", tools).group(1))
        tuning = code_only(src("ExploreTuning.java"))
        self.assertEqual(int(re.search(r"private long toolLookMs = (\d+);", tuning).group(1)), look)
        self.assertIn("ADAPTER_LOOK_WAIT_MS = PREAMBLE_WAIT_MS + LOOK_WAIT_MS + 1000;", tools)
        round_ms = int(re.search(r"private long toolRoundMs = (\d+);", tuning).group(1))
        budget = int(re.search(r"private long turnBudgetMs = (\d+);", tuning).group(1))
        self.assertGreaterEqual(round_ms, pre + look + budget)

    def test_the_look_tool_is_gated_by_privacy_first(self):
        brain = code_only(src("ExploreBrain.java"))
        body = re.search(r"public String lookBlocked\(\) \{(.*?)\n        \}", brain, re.S).group(1)
        # Review 2026-10-03: a single strong look holds frames too (bathHeld).
        self.assertLess(body.index("if (bathroom || bathHeld(clock.nowMs()))"), body.index("muted || quiet"))
        self.assertLess(body.index("muted || quiet"), body.index("!leaseHeld"))


class LearnLogWiringTest(unittest.TestCase):
    """The learning log (2026-10-03): ModeApp feeds it the brain's notes and stamps each start."""

    def test_the_trace_offers_every_note_and_only_the_log_filters_them(self):
        app = code_only(src("ModeApp.java"))
        m = re.search(r"ExploreBrain\.Trace trace = new ExploreBrain\.Trace\(\) \{(.*?)\n    \};", app, re.S)
        self.assertIsNotNone(m)
        self.assertIn('l.offer("ExploreBrain", message)', m.group(1))

    def test_the_log_opens_before_the_loop_with_the_build_and_tuning_and_closes_on_stop(self):
        app = code_only(src("ModeApp.java"))
        start = app.index("void startExplore()")
        opened = app.index("new LearnLog(getFilesDir()", start)
        self.assertLess(opened, app.index("new ExploreLoop(", start))
        self.assertIn("LearnLog.startRecord(buildId(), tuning)", app)
        self.assertIn("getPackageInfo(getPackageName(), 0).versionName", app)
        stop = app[app.index("void stopExplore()"):]
        self.assertLess(stop.index("loop.stop()"), stop.index("l.close()"))

    def test_the_log_writes_off_the_brain_thread_and_never_blocks(self):
        log = code_only(src("LearnLog.java"))
        offer = log[log.index("void offer("):log.index("void record(")]
        self.assertIn("queue.offer(", offer)
        self.assertNotIn("write(", offer)
        self.assertNotIn(".put(", log)
        self.assertIn("setDaemon(true)", log)
        self.assertIn('"learn.log"', log)

    def test_the_adapter_reports_a_turns_tools_and_speculation_for_its_own_generation_only(self):
        a = code_only(src("ClaudeCuriosity.java"))
        self.assertIn("public CuriosityPort.TurnInfo turnInfo()", a)
        self.assertRegex(a, r"answered != 0 && answered == turnInfoGen")
        self.assertIn("CuriosityPort.TurnInfo.joined(o.tools)", a)

    def test_turn_records_carry_no_heard_text_and_no_name(self):
        chat = code_only(src("ChatSession.java"))
        rec = chat[chat.index("static final class TurnRecord"):chat.index("public String toString()")]
        self.assertIsNone(re.search(r"\b(heard|heardText|name|text|transcript|pendingLine|personId)\b", rec))
        begin = chat[chat.index("private void learnBegin("):chat.index("private void learnTurnOver(")]
        # The heard words are only compared (to tell a used speculation), never put in the record.
        self.assertNotIn("new TurnRecord(learnNo, ++learnTurns, heard", begin)


if __name__ == "__main__":
    unittest.main()


class ActionToolsWiringTest(unittest.TestCase):
    """Owner 2026-10-03: the action tools and run_task. Claude picks, the robot drives; the
    trace and the learning log carry the tool, kind and amount only, never a name, a label
    Claude chose, a goal or words to say; a task's consult goes to Claude without a frame."""

    def test_the_brain_never_traces_an_acts_words(self):
        brain = code_only(src("ExploreBrain.java"))
        for call in re.findall(r"\bnote\((.*?)\);", brain, re.S):
            bare = re.sub(r'"(?:\\.|[^"\\])*"', "", call)
            self.assertNotRegex(bare, r"\.(goal|text|target|labels)\b|stepText\(|taskOutcomes|taskLine", call)

    def test_act_and_task_lines_are_learning_log_records(self):
        learn = code_only(src("LearnLog.java"))
        self.assertIn('"act: ", "task: "', learn)
        brain = code_only(src("ExploreBrain.java"))
        self.assertIn('note("task: n=" + taskNo + " steps=" + ChatActions.stepTools(taskSteps) + " outcomes="', brain)
        self.assertIn('" consults=" + taskConsults + " end=" + why', brain)

    def test_the_consult_sends_labels_never_a_frame_and_logs_only_its_outcome(self):
        a = code_only(src("ClaudeCuriosity.java"))
        consult = re.search(r"private TaskPlan consult\((.*?)\n    \}", a, re.S).group(1)
        self.assertIn("ExplorePrompts.taskAsk(request)", consult)
        self.assertIn("ExplorePrompts.TASK_SYSTEM", consult)
        self.assertNotIn("jpeg", consult)
        for call in re.findall(r"Log\.[diwe]\((.*?)\);", consult, re.S):
            bare = re.sub(r'"(?:\\.|[^"\\])*"', "", call)
            self.assertNotRegex(bare, r"request|goal|messages", call)
        port = src("CuriosityPort.java")
        consult_cls = port[port.index("final class TaskConsult"):port.index("final class TaskPlan")]
        self.assertNotIn("byte[]", consult_cls)

    def test_bathroom_privacy_ends_a_task_before_anything_else_in_its_step(self):
        brain = code_only(src("ExploreBrain.java"))
        step = re.search(r"private void taskStep\(long now\) \{(.*?)\n    \}", brain, re.S).group(1)
        self.assertLess(step.index("if (bathroom)"), step.index("switch (taskWait)"))
        self.assertIn('endTask(now, "bathroom"', step)

    def test_a_call_drops_a_task_and_stay_answers_in_place(self):
        brain = code_only(src("ExploreBrain.java"))
        call = re.search(r"private void callStep\(long now\) \{(.*?)\n    \}", brain, re.S).group(1)
        self.assertIn('endTask(now, "a call", null)', call)
        verdict = re.search(r"private CallVerdict callVerdict\((.*?)\n    \}", brain, re.S).group(1)
        self.assertIn("if (now < stayUntil)", verdict)

    def test_the_adapter_checks_labels_against_the_detectors_vocabulary(self):
        a = code_only(src("ClaudeCuriosity.java"))
        turn = re.search(r"private void oneTurn\((.*?)\n    \}", a, re.S).group(1)
        self.assertIn("asked.withFacts(asked.facts.withVocabulary(vocabulary()))", turn)
        self.assertIn('OnnxRecognizer.readVocabulary(app)', a)


class OwnerNotesWiringTest(unittest.TestCase):
    """Owner 2026-10-03: notes about people by name, written on the Settings page. When the
    person he is talking to has one (by their recognised or given name), it goes into that
    conversation's system context with its guard; it is never logged, never a tool's answer."""

    def test_the_adapter_reads_the_note_by_name_and_never_logs_it(self):
        a = code_only(src("ClaudeCuriosity.java"))
        self.assertIn("RobotPeopleClient.ownerNoteFor(app, name)", a)
        conv = re.search(r"private MatchAnswer forConversation\((.*?)\n    \}", a, re.S).group(1)
        self.assertIn("RobotPeopleClient.ownerNoteFor(app, a.name.trim())", conv)
        # Review 2026-10-03: only a face-matched known person's name is fetched at the meeting.
        self.assertIn("a.status == CuriosityPort.MatchAnswer.Status.KNOWN && !a.faceless", conv)
        w = re.search(r"private TurnRequest withOwnerNote\(TurnRequest request\) \{(.*?)\n    \}", a, re.S).group(1)
        self.assertIn("final String name = request.noteName;", w)
        self.assertNotIn("request.name", w)
        for sig in ("public void turn(TurnRequest asked, final long timeoutMs)",
                    "public void speculateTurn(TurnRequest asked, final long timeoutMs)"):
            body = re.search(re.escape(sig) + r"(.*?)\n    \}", a, re.S).group(1)
            self.assertIn("final TurnRequest request = withOwnerNote(asked);", body)
        for call in re.findall(r"Log\.[diwe]\((.*?)\);", a, re.S):
            bare = re.sub(r'"(?:\\.|[^"\\])*"', "", call)
            self.assertNotRegex(bare, r"\bnote\b|ownerNote", call)

    def test_the_note_goes_only_into_the_system_prefix(self):
        chat = code_only(src("ChatRound.java"))
        self.assertEqual(chat.count("ownerNote"), 1)
        self.assertNotIn("ownerNote", code_only(src("ChatTools.java")))
        prompts = src("ExplorePrompts.java")
        guard = ConversationWiringTest.java_string(prompts, "OWNER_NOTE_GUARD")
        for phrase in ("Follow it for how he approaches them", "never deceive them",
                       "never pressure them after they say no", "go_away, be_quiet and stop always win",
                       "never reveal or quote what the note says", "the owner mentioned them"):
            self.assertIn(phrase, guard, phrase)


class FastLookWiringTest(unittest.TestCase):
    """Robot 2026-10-03: the look tool sends the newest detected frame at once when it is fresh
    and its detection shows no bathroom label. Review 2026-10-03: only a frame the detector itself
    checked goes, so the camera copies only the frames it detects (no streamed copies), and the
    note logs the frame's age only."""

    def test_the_camera_copies_only_frames_it_detects(self):
        cam = code_only(src("ExploreCamera.java"))
        self.assertNotIn("latestRaw", cam)
        self.assertNotIn("RAW_EVERY_MS", cam)
        listener = re.search(r"public void onImageAvailable\(ImageReader r\) \{(.*?)\n        \}", cam, re.S).group(1)
        self.assertLess(listener.index("if (busy || parked)"), listener.index("new byte[buf.remaining()]"))
        self.assertNotIn("RawFrame", code_only(src("ExploreBrain.java")))

    def test_the_fast_path_checks_privacy_first_and_the_newest_detection(self):
        brain = code_only(src("ExploreBrain.java"))
        body = re.search(r"private CuriosityPort.LookResult fastToolLook\(long now\) \{(.*?)\n    \}", brain, re.S).group(1)
        self.assertIn("bathroom || muted || quiet", body)
        self.assertIn("Look d = camera.latest();", body)
        self.assertIn("LookResult.streamed(d.jpeg, labels, now - d.frameMs)", body)
        self.assertIn("BATHROOM_STRONG.contains(x.label) || BATHROOM_WEAK.contains(x.label)", body)
        chat = code_only(src("ChatSession.java"))
        step = re.search(r"private void toolStep\(long now\) \{(.*?)\n    \}", chat, re.S).group(1)
        self.assertLess(step.index("host.lookBlocked() == null"), step.index("host.fastLook(now)"))
        self.assertIn('" ms old, "', step)
