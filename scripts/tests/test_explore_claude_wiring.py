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
                          r"boolean partialUtterance, int kind,\s+boolean called\)\s*\{(.*?)\n    \}", a, re.S)
        self.assertIsNotNone(heard)
        body = heard.group(1)
        self.assertNotIn("CueKinds", a)
        self.assertIn("side == RobotEars.SIDE_LEFT", body)
        self.assertIn("side == RobotEars.SIDE_RIGHT", body)
        self.assertIn("tier == RobotEars.TIER_STRONG", body)
        self.assertIn("Ears.Kind k = kindOf(kind, t)", body)
        self.assertIn("new Ears.Cue(k, t, s, angle, at, called)", body)
        self.assertIn("if (partialUtterance)", body)
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
        self.assertRegex(heard, r"if \(partialUtterance\) \{[^}]*partial = cue;")
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
                args = call.split(",", 1)[1] if "," in call else call
                # Drop the string literals: fixed text is fine.
                bare = re.sub(r'"(?:\\.|[^"\\])*"', "", args)
                if self.PRIVATE.search(bare):
                    offenders.append(f"{f}: Log({call.strip()})")
        self.assertEqual(offenders, [])


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
        self.assertEqual(self.a.count("new ClaudeApi.Backoff()"), 1)
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
        turn_of = self.body(r"private static Turn turnOf\(ClaudeApi\.MessageResult r\)")
        m = re.search(r"case OVERLOADED:\s*case RATE_LIMITED:.*?return Turn\.unreachable\(\);", turn_of, re.S)
        self.assertIsNotNone(m)


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
        java_props = re.findall(r'"(\w+)", (?:type|object|arrayOf)\(', prompts.split("REPLY_SCHEMA = object(")[1].split(";")[0])
        bench_props = list(bench.REPLY_SCHEMA["properties"]) + list(bench.REPLY_SCHEMA["properties"]["notes_update"]["properties"])
        self.assertEqual(java_props, bench_props)
        self.assertIn("closed_threads", java_props)
        # The prefix order the bench renders: guard, quoted persona, reminder, notes heading, preamble.
        prefix = re.search(r"static String systemPrefix\((.*?)\n    \}", prompts, re.S).group(1)
        for a, b in (("GUARD", "PERSONA_HEADING"), ("PERSONA_HEADING", "REMINDER"), ("REMINDER", "NOTES_HEADING"),
                     ("NOTES_HEADING", "SCHEMA_PREAMBLE")):
            self.assertLess(prefix.index(a), prefix.index(b), (a, b))
        self.assertIn('"\\n\\"\\"\\"\\n"', prefix)

    def test_the_adapter_binds_every_new_port_method(self):
        a = code_only(src("ClaudeCuriosity.java"))
        for method in ("turn", "turnAnswer", "cancelTurn", "notesDelta", "notesDeltaAnswer", "cancelNotesDelta", "forget",
                       "forgetAnswer", "cancelForget", "chatListen", "keep", "keptAnswer", "cancelKeep"):
            self.assertRegex(a, r"public [\w<>.]+ " + method + r"\(", method)
        turn = re.search(r"private Turn oneTurn\((.*?)\n    \}", a, re.S).group(1)
        self.assertIn("claude.conversation(fetchSettings(), system, messages, ExplorePrompts.REPLY_SCHEMA", turn)
        self.assertIn("TURN_EFFORT", turn)
        self.assertIn("ExplorePrompts.systemPrefix(request.persona, request.notes)", turn)
        self.assertIn("ExplorePrompts.openerAsk(request.name)", turn)
        self.assertIn("ExplorePrompts.avoidQuestion(request.avoidQuestion)", turn)
        self.assertIn("ClaudeApi.jpegBlock(face)", turn)
        self.assertIn("request.heard == null && request.transcript.isEmpty() && met != null\n                ? met.storeCrop : null", a)
        self.assertIn("NameExtractor.validName(t.nameGiven)", a)
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
        self.assertIn("if (busy || !wanted || parked)", cam)
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
        self.assertIn("NameResolver.resolve(name, probe, ids, entries, close)", resolve)
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
    """Robot 2026-10-01: from the floor the face was out of frame or too small, so he asked
    people names he could never keep (R19). A conversation that opens with no usable face
    invites them down to his level instead; the name is asked only once a face retry found a
    usable face. A face-in-hand stranger's opener still asks the name."""

    @staticmethod
    def constant(name):
        return ConversationWiringTest.java_string(src("ExplorePrompts.java"), name)

    def test_the_faceless_opener_invites_them_down_and_never_asks_the_name(self):
        text = self.constant("FACELESS_OPENER")
        for words in ("can't see their face from down here", "crouch down to his level", "Do not ask their name",
                      "do not ask their name either"):
            self.assertIn(words, text)

    def test_a_face_in_hand_strangers_opener_still_asks_the_name(self):
        p = src("ExplorePrompts.java")
        body = p[p.index("static String openerAsk("):]
        body = body[:body.index("\n    }\n")]
        self.assertIn("ask \"\n                    + \"their name", body)

    def test_a_face_seen_on_a_retry_lets_him_ask_the_name(self):
        self.assertIn("ask their name if he does not know it yet", self.constant("FACE_SEEN"))

    def test_the_turn_picks_the_opener_by_the_request_and_appends_face_seen(self):
        turn = re.search(r"private Turn oneTurn\((.*?)\n    \}", code_only(src("ClaudeCuriosity.java")), re.S).group(1)
        self.assertIn("request.faceless ? ExplorePrompts.FACELESS_OPENER : ExplorePrompts.openerAsk(request.name)", turn)
        self.assertIn("e.heard == null ? first : e.heard", turn)
        self.assertIn("request.heard == null ? first : request.heard", turn)
        self.assertRegex(turn, r"if \(request\.faceSeen\) \{\s*ask = ask \+ \"\\n\\n\" \+ ExplorePrompts\.FACE_SEEN;")
        port = code_only(src("CuriosityPort.java"))
        for sig in ("final boolean faceless;", "final boolean faceSeen;"):
            self.assertIn(sig, port)
        session = code_only(src("ChatSession.java"))
        self.assertIn(".face(openedFaceless, faceSeen && name == null)", session)


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


if __name__ == "__main__":
    unittest.main()
