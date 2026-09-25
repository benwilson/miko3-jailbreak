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
                     "RobotPeopleClient.nameOf", "NameExtractor.extract", "new FaceCropper(app)"):
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
        for method in ("ask", "match", "lines", "findName", "remember", "wayOut"):
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


class ClaudeLatencyIsLoggedTest(unittest.TestCase):
    """Live test: two look tries timed out at 10 s while others took ~3 s. Every
    Claude call logs how long it took in ms (no content), so slow calls show up."""

    def test_every_claude_call_logs_its_latency(self):
        body = code_only(src("ClaudeCuriosity.java"))
        calls = body.split("claude.messages(")[1:]
        self.assertEqual(len(calls), 8)
        for i, after in enumerate(calls):
            window = after[:900]
            logs = re.findall(r"Log\.[diwe]\((.*?)\);", window, re.S)
            self.assertTrue(logs, f"call {i + 1}: no Log after it")
            self.assertRegex(logs[0], r'" ms"', f"call {i + 1}: its first Log has no latency: {logs[0]}")


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
        self.assertLess(person.index("if (!crop.found())"), person.index("RobotPeopleClient.recent"))
        self.assertIn("MatchAnswer.faceless(", person[:person.index("RobotPeopleClient.recent")])
        keep = a[a.index("private Answer keep("):]
        self.assertLess(keep.index("return hello("), keep.index("RobotPeopleClient.add"))

    def test_the_hello_never_promises_to_remember(self):
        prompts = src("ExplorePrompts.java")
        hello = prompts[prompts.index("static String welcomeAsk("):]
        hello = hello[:hello.index("}")]
        self.assertIn("do not say or", hello)
        self.assertIn("will NOT remember", hello)


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

if __name__ == "__main__":
    unittest.main()
