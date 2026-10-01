package com.miko3.launcher;

import com.miko3.shared.ClaudeApi;
import com.miko3.shared.ConversationSettings;
import com.miko3.shared.FaceCheck;
import com.miko3.shared.FaceSettings;
import com.miko3.shared.HttpRequest;
import com.miko3.shared.HttpResponse;
import com.miko3.shared.Json;
import com.miko3.shared.LauncherProtocol;
import com.miko3.shared.PageToken;
import com.miko3.shared.PersonNotes;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The robot's Settings page at LauncherProtocol.SETTINGS_PATH (settings plan
 * U4, R1-R10, R16): a Home link, then one section per group of settings. v1
 * has the Claude API section: a Save form (base URL, key, model) and
 * three token-only buttons (Refresh models, Test connection, Forget key)
 * that act on what is already saved. Then the Conversation section (meeting
 * plan U4, KTD11): the persona box and the "answers when spoken to" switch,
 * saved together. Then the Voice section: type a line,
 * press Say it, and the robot speaks it through the launcher's own speech
 * queue (the request returns once it is queued, not once it has played).
 * Then the People section (explore-on-claude plan U2, R15, R16; meeting plan
 * U5, R18): everyone the robot remembers, with their face, name or "unnamed",
 * when he last saw them and their notes, each with a Rename form and a Forget
 * button that deletes face, name and notes. The same page serves the robot's
 * own WebView and a LAN browser.
 *
 * Face plan U5 (KTD5, KTD8; R13-R15, R19) adds the Face checks section (the
 * last few checks from FaceChecks, newest first: the crop, the best match's
 * photo and name, the score, the decision and what happened next), a
 * read-only Face thresholds block, and per person a strip of their photos
 * with a delete control for one photo and a mark on photos with no findable
 * face. Two more routes serve host tooling (scripts/robot-faces.py): the
 * thresholds save, which takes loopback callers only (the CLI arrives over
 * adb forward, so nobody on the office network can set confident to 0), and
 * a token-protected JSON view of the checks, people and thresholds.
 *
 * Follows voice mode's SettingsPage pattern: every action is a POST that
 * answers with a redirect to "/settings?status=<message>", and the GET that
 * follows re-renders from what is actually stored. Status messages are fixed
 * text, ClaudeApi's fixed reason text, or stored model ids, never anything
 * that was typed (KTD6), so a refused URL or key never lands in a URL, and
 * a person's name never does either.
 *
 * Faces: the page's <img> tags load SETTINGS_PEOPLE_FACE_PATH?id=<id>,
 * SETTINGS_PEOPLE_PHOTO_PATH (id, slot and added-at time) and
 * SETTINGS_FACE_CHECK_CROP_PATH?id=<handle>, the only GETs besides the page
 * itself. They are under SETTINGS_PATH, so TLS-only, and PeopleStore accepts
 * only its own 16-hex-digit id shape before it touches a file, so the id
 * can't name any other file. A check's crop lives only in memory.
 *
 * Secrecy (R4, R14): the page renders only from ClaudeSettings.status(), and
 * the key input is always empty. The key arrives only in a POST body, and
 * the page token only in the body too, because RoutingHttpServer logs every
 * request line. The full key is read (credentialsForRequests()) only to hand
 * to the Claude client for Refresh and Test, which run synchronously on the
 * server's per-connection thread within the client's timeouts.
 *
 * Plain Java (no android.*): LauncherApp's routes delegate to handle(), and
 * scripts/tests drive the same entry point on the host JVM with a fake
 * ClaudeApi transport.
 */
final class SettingsPage {
    private SettingsPage() {
    }

    // Far above anything the form sends (the persona box at its cap, URL-encoded
    // with non-ASCII text, is about 23 KB); refuses a body that would otherwise
    // be read into memory whole.
    static final int MAX_FORM_BYTES = 32 * 1024;

    static final String CONVERSATION_SAVED = "Conversation settings saved.";
    static final String CONVERSATION_DEFAULT = "Saved: the built-in persona is in use.";

    static final String SAY_EMPTY = "Nothing said: type something to say.";
    static final String SAY_TOO_LONG = "Nothing said: that is longer than " + SpeechQueue.MAX_CHARS + " characters.";
    static final String SAY_LOADING = "The voice is still loading; try again in a few seconds.";
    static final String SAY_SPEAKING = "Speaking.";
    static final String SAY_UNAVAILABLE = "Nothing said: the robot's voice is not available.";

    static final String PEOPLE_RENAMED = "Name changed.";
    static final String PEOPLE_UNNAMED = "Name cleared; that person is now unnamed.";
    static final String PEOPLE_FORGOTTEN = "Forgotten: their face, name and notes are deleted.";
    static final String PEOPLE_UNKNOWN = "Nothing changed: that person is not remembered.";
    static final String PEOPLE_NOT_SAVED = "Nothing changed: the change could not be saved.";
    static final String PEOPLE_NO_NOTES = "No notes yet.";
    /** A record from before names were required (KTD10): out of the matching
     * gallery, never given notes; the owner names it or deletes it. */
    static final String PEOPLE_LEGACY = "Legacy record: no name, so he no longer matches this face or keeps notes "
            + "on it. Give them a name, or Forget deletes it.";

    // Face plan U5: fixed text for the face checks, photos and thresholds.
    static final String CHECKS_EMPTY = "No face checks since the launcher started.";
    static final String CHECK_PHOTO_REPLACED = "That photo has since been replaced.";
    static final String CHECK_PERSON_GONE = "That person is no longer remembered.";
    static final String NEAR_TIE = "Near tie with";
    static final String PHOTO_UNUSABLE = "No face found in this photo: it is not used for matching. Delete it, "
            + "and he will take a new one.";
    static final String PHOTO_DELETED = "Photo deleted.";
    static final String PHOTO_LAST = "Nothing changed: a person keeps at least one photo. Forget removes them.";
    static final String PHOTO_UNKNOWN = "Nothing changed: that photo is not stored.";
    static final String FACE_THRESHOLDS_SAVED = "Face thresholds saved; they apply from the next meeting.";
    static final String FACE_NOT_A_NUMBER = "Not saved: every face threshold must be a number.";
    static final String FACE_THRESHOLDS_LOOPBACK_ONLY = "Face thresholds can only be set from the robot itself "
            + "(scripts/robot-faces.py over adb).";
    /** What the photo route answers for a slot whose photo was replaced or deleted. */
    static final String REPLACED_SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"112\" height=\"112\" "
            + "viewBox=\"0 0 112 112\"><rect width=\"112\" height=\"112\" fill=\"#ddd\"/>"
            + "<text x=\"56\" y=\"60\" font-size=\"14\" text-anchor=\"middle\" fill=\"#555\">replaced</text></svg>";

    /**
     * The robot's voice, as the Voice section sees it. LauncherApp backs it
     * with SpeechEngine's queue in-process; the host harness fakes it.
     */
    interface Speaker {
        /** True once the voice has loaded and warmed up. */
        boolean ready();

        /** True once the voice has failed to load: it will never be ready. */
        boolean failed();

        /** Which voice the build staged, e.g. "stock lessac medium" or "trained". */
        String voiceName();

        /** Queues text and returns at once, without waiting for playback.
         * Throws IllegalArgumentException (SpeechQueue's fixed reasons) when refused. */
        void say(String text);
    }

    /** Every settings route. GET the page; POST an action with the page token. */
    static void handle(HttpRequest req, HttpResponse res, PageToken token, ClaudeSettings settings,
                       ClaudeApi api, Speaker speaker, PeopleStore people, FaceChecks checks) throws IOException {
        if (LauncherProtocol.SETTINGS_PATH.equals(req.path)) {
            if (refusedUnlessGet(req, res)) {
                return;
            }
            res.sendText(200, "OK", "text/html; charset=utf-8", buildHtml(token.issue(), settings.status(),
                    settings.models(), settings.conversation(), speaker.voiceName(), people, checks,
                    settings.faceSettings(), req.queryParam("status", null)));
            return;
        }
        if (LauncherProtocol.SETTINGS_PEOPLE_FACE_PATH.equals(req.path)) {
            sendFace(req, res, people);
            return;
        }
        if (LauncherProtocol.SETTINGS_PEOPLE_PHOTO_PATH.equals(req.path)) {
            sendPhoto(req, res, people);
            return;
        }
        if (LauncherProtocol.SETTINGS_FACE_CHECK_CROP_PATH.equals(req.path)) {
            sendCheckCrop(req, res, checks);
            return;
        }
        // The action paths never act on a GET, so nothing in a URL (which the
        // server logs) can change a setting.
        if (!"POST".equals(req.method)) {
            res.sendText(405, "Method Not Allowed", "text/plain; charset=utf-8", "POST only");
            return;
        }
        if (LauncherProtocol.SETTINGS_FACE_STATE_PATH.equals(req.path)) {
            sendFaceState(req, res, token, settings, people, checks);
            return;
        }
        // The thresholds save (KTD5) is loopback-only, checked before the body is read.
        if (LauncherProtocol.SETTINGS_FACE_THRESHOLDS_PATH.equals(req.path) && !req.fromLoopback) {
            res.sendText(403, "Forbidden", "text/plain; charset=utf-8", FACE_THRESHOLDS_LOOPBACK_ONLY + "\n");
            return;
        }
        String status = act(req.path, readForm(req), token, settings, api, speaker, people, checks);
        res.redirect(LauncherProtocol.SETTINGS_PATH + "?status=" + urlEncode(status));
    }

    /** Sends 405 and returns true unless the request is a GET or HEAD. */
    private static boolean refusedUnlessGet(HttpRequest req, HttpResponse res) throws IOException {
        if ("GET".equals(req.method) || "HEAD".equals(req.method)) {
            return false;
        }
        res.sendText(405, "Method Not Allowed", "text/plain; charset=utf-8", "GET only");
        return true;
    }

    /** Runs one action; returns the status line to show. */
    static String act(String path, Map<String, String> form, PageToken token, ClaudeSettings settings,
                      ClaudeApi api, Speaker speaker, PeopleStore people, FaceChecks checks) {
        if (form == null) {
            return "Nothing changed: the form was too large.";
        }
        if (!token.check(form.get("t"))) {
            return "Nothing changed: this page had expired. Try again.";
        }
        if (LauncherProtocol.SETTINGS_CLAUDE_PATH.equals(path)) {
            return save(form, settings);
        }
        if (LauncherProtocol.SETTINGS_CLAUDE_MODELS_PATH.equals(path)) {
            return refreshModels(settings, api);
        }
        if (LauncherProtocol.SETTINGS_CLAUDE_TEST_PATH.equals(path)) {
            return testConnection(settings, api);
        }
        if (LauncherProtocol.SETTINGS_CLAUDE_FORGET_PATH.equals(path)) {
            settings.forgetKey();
            return "Key forgotten. No key is set.";
        }
        if (LauncherProtocol.SETTINGS_VOICE_SAY_PATH.equals(path)) {
            return say(form.get("text"), speaker);
        }
        if (LauncherProtocol.SETTINGS_CONVERSATION_PATH.equals(path)) {
            return saveConversation(form, settings);
        }
        if (LauncherProtocol.SETTINGS_PEOPLE_RENAME_PATH.equals(path)) {
            return rename(form.get("id"), form.get("name"), people);
        }
        if (LauncherProtocol.SETTINGS_PEOPLE_FORGET_PATH.equals(path)) {
            return forget(form.get("id"), people, checks);
        }
        if (LauncherProtocol.SETTINGS_PEOPLE_PHOTO_DELETE_PATH.equals(path)) {
            return deletePhoto(form.get("id"), form.get("slot"), people);
        }
        if (LauncherProtocol.SETTINGS_FACE_THRESHOLDS_PATH.equals(path)) {
            return saveFace(form, settings);
        }
        return "Nothing changed: unknown action.";
    }

    /** The face JPEG for ?id=, or 404 for anything that isn't a remembered
     * person's id. The id is checked by PeopleStore before any file access. */
    private static void sendFace(HttpRequest req, HttpResponse res, PeopleStore people) throws IOException {
        if (refusedUnlessGet(req, res)) {
            return;
        }
        byte[] face = people.face(req.queryParam("id", null));
        if (face == null) {
            res.sendText(404, "Not Found", "text/plain; charset=utf-8", "No such person.");
            return;
        }
        res.sendBytes(200, "OK", "image/jpeg", face);
    }

    /** Fixed text only: never the name that was typed or stored. */
    private static String rename(String id, String name, PeopleStore people) {
        if (people.nameOf(id) == null) {
            return PEOPLE_UNKNOWN;
        }
        if (!people.rename(id, name)) {
            return PEOPLE_NOT_SAVED;
        }
        return people.nameOf(id).isEmpty() ? PEOPLE_UNNAMED : PEOPLE_RENAMED;
    }

    private static String forget(String id, PeopleStore people, FaceChecks checks) {
        return FaceChecks.forget(people, checks, id) ? PEOPLE_FORGOTTEN : PEOPLE_UNKNOWN;
    }

    /** One photo by id and slot (KTD8); refused for the last one, since a
     * person with no photo would not load again. Fixed text only. */
    private static String deletePhoto(String id, String slotText, PeopleStore people) {
        int slot = parseSlot(slotText);
        if (slot < 0) {
            return PHOTO_UNKNOWN;
        }
        try {
            return people.deletePhoto(id, slot) ? PHOTO_DELETED : PHOTO_UNKNOWN;
        } catch (IllegalArgumentException e) {
            // PeopleStore.REFUSE_LAST_PHOTO is its only refusal here.
            return PHOTO_LAST;
        }
    }

    /** A slot index 0 to PeopleStore.MAX_PHOTOS - 1, or -1. */
    private static int parseSlot(String text) {
        if (text == null || !text.matches("[0-9]")) {
            return -1;
        }
        int slot = Integer.parseInt(text);
        return slot < PeopleStore.MAX_PHOTOS ? slot : -1;
    }

    /**
     * The photo at ?id=&slot= while that slot still holds the photo added at
     * ?at= (KTD8); otherwise, replaced or deleted since, a "replaced"
     * placeholder, never the newer photo. 404 for a malformed request or an
     * unknown person.
     */
    private static void sendPhoto(HttpRequest req, HttpResponse res, PeopleStore people) throws IOException {
        if (refusedUnlessGet(req, res)) {
            return;
        }
        String id = req.queryParam("id", null);
        int slot = parseSlot(req.queryParam("slot", null));
        long at = parseLong(req.queryParam("at", null));
        if (!PeopleStore.isValidId(id) || slot < 0 || at < 0 || people.nameOf(id) == null) {
            res.sendText(404, "Not Found", "text/plain; charset=utf-8", "No such photo.");
            return;
        }
        byte[] jpeg = people.photoIfAddedAt(id, slot, at);
        if (jpeg == null) {
            res.sendText(200, "OK", "image/svg+xml", REPLACED_SVG);
            return;
        }
        res.sendBytes(200, "OK", "image/jpeg", jpeg);
    }

    /** A check's crop by ?id=<handle>, or 404 once it has rolled off or had none. */
    private static void sendCheckCrop(HttpRequest req, HttpResponse res, FaceChecks checks) throws IOException {
        if (refusedUnlessGet(req, res)) {
            return;
        }
        long handle = parseLong(req.queryParam("id", null));
        byte[] crop = handle > 0 ? checks.crop(handle) : null;
        if (crop == null) {
            res.sendText(404, "Not Found", "text/plain; charset=utf-8", "No such face check.");
            return;
        }
        res.sendBytes(200, "OK", "image/jpeg", crop);
    }

    /** A non-negative decimal of at most 18 digits, or -1. */
    private static long parseLong(String text) {
        return text != null && text.matches("[0-9]{1,18}") ? Long.parseLong(text) : -1;
    }

    /**
     * The thresholds save (KTD5): fields left out keep their stored value, and
     * the whole set must pass ClaudeSettings' rules or nothing is stored.
     */
    private static String saveFace(Map<String, String> form, ClaudeSettings settings) {
        FaceSettings now = settings.faceSettings();
        FaceSettings next;
        try {
            next = new FaceSettings(
                    floatField(form, "confident", now.confident), floatField(form, "close", now.close),
                    floatField(form, "margin", now.margin), intField(form, "min_width", now.minWidth),
                    doubleField(form, "dark_floor", now.darkFloor), doubleField(form, "dim_level", now.dimLevel),
                    doubleField(form, "blur_floor", now.blurFloor));
        } catch (NumberFormatException e) {
            return FACE_NOT_A_NUMBER;
        }
        try {
            settings.saveFace(next);
        } catch (ClaudeSettings.InvalidException e) {
            // Fixed text by contract (ClaudeSettings), never what was typed.
            return "Not saved: " + e.getMessage();
        }
        return FACE_THRESHOLDS_SAVED;
    }

    private static float floatField(Map<String, String> form, String name, float current) {
        String v = trimmed(form.get(name));
        return v.isEmpty() ? current : Float.parseFloat(number(v));
    }

    private static double doubleField(Map<String, String> form, String name, double current) {
        String v = trimmed(form.get(name));
        return v.isEmpty() ? current : Double.parseDouble(number(v));
    }

    private static int intField(Map<String, String> form, String name, int current) {
        String v = trimmed(form.get(name));
        return v.isEmpty() ? current : Integer.parseInt(v);
    }

    /** Plain decimals only: Java would also parse "NaN", "Infinity" and "1f". */
    private static String number(String v) {
        if (!v.matches("-?[0-9]{1,6}(\\.[0-9]{1,6})?")) {
            throw new NumberFormatException();
        }
        return v;
    }

    private static String trimmed(String s) {
        return s == null ? "" : s.trim();
    }

    /**
     * The JSON view for scripts/robot-faces.py (R15): the checks newest first
     * with names, people with photo counts, and the thresholds; never an
     * image. POST with the page token in the body; 403 without it.
     */
    private static void sendFaceState(HttpRequest req, HttpResponse res, PageToken token, ClaudeSettings settings,
                                      PeopleStore people, FaceChecks checks) throws IOException {
        Map<String, String> form = readForm(req);
        if (form == null || !token.check(form.get("t"))) {
            res.sendText(403, "Forbidden", "text/plain; charset=utf-8", "page token missing or expired\n");
            return;
        }
        res.sendText(200, "OK", "application/json; charset=utf-8",
                faceStateJson(settings.faceSettings(), people, checks) + "\n");
    }

    static String faceStateJson(FaceSettings f, PeopleStore store, FaceChecks checks) {
        Map<String, Object> th = new LinkedHashMap<String, Object>();
        th.put("confident", decimal(f.confident));
        th.put("close", decimal(f.close));
        th.put("margin", decimal(f.margin));
        th.put("min_width", f.minWidth);
        th.put("dark_floor", f.darkFloor);
        th.put("dim_level", f.dimLevel);
        th.put("blur_floor", f.blurFloor);

        List<Object> checkRows = new ArrayList<Object>();
        for (FaceChecks.Entry e : checks.list()) {
            FaceCheck c = e.check;
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("handle", e.handle);
            row.put("at_ms", e.atMillis);
            row.put("decision", decisionName(c.decision));
            row.put("reason", reasonText(c.rejectReason));
            row.put("has_crop", c.cropJpeg != null);
            row.put("best_id", c.bestId);
            row.put("best_name", c.bestId.isEmpty() ? "" : nameOrEmpty(store, c.bestId));
            row.put("best_slot", c.bestSlot);
            row.put("best_photo", bestPhotoState(store, c));
            row.put("score", decimal(c.score));
            row.put("runner_up_id", c.runnerUpId);
            row.put("runner_up_name", c.runnerUpId.isEmpty() ? "" : nameOrEmpty(store, c.runnerUpId));
            row.put("runner_up_score", c.runnerUpId.isEmpty() ? null : decimal(c.runnerUpScore));
            row.put("near_tie", c.nearTie);
            row.put("outcome", outcomeText(e.outcome));
            row.put("joined_id", e.joinedId);
            row.put("joined_name", e.joinedId.isEmpty() ? "" : nameOrEmpty(store, e.joinedId));
            checkRows.add(row);
        }

        List<Object> peopleRows = new ArrayList<Object>();
        for (PeopleStore.Person p : store.all()) {
            int unusable = 0;
            int embedded = 0;
            List<PeopleStore.Photo> photos = store.photos(p.id);
            for (PeopleStore.Photo ph : photos) {
                unusable += ph.unusable ? 1 : 0;
                embedded += ph.embedding != null ? 1 : 0;
            }
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("id", p.id);
            row.put("name", p.name);
            row.put("last_seen_ms", p.lastSeenMillis);
            row.put("photos", photos.size());
            row.put("with_embedding", embedded);
            row.put("unusable", unusable);
            peopleRows.add(row);
        }

        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("thresholds", th);
        root.put("checks", checkRows);
        root.put("people", peopleRows);
        return Json.write(root);
    }

    /** A float as the decimal it was typed as (0.363, not 0.36300000548). */
    private static Double decimal(float f) {
        return Double.valueOf(Float.toString(f));
    }

    private static String nameOrEmpty(PeopleStore store, String id) {
        String name = store.nameOf(id);
        return name == null ? "" : name;
    }

    /** "current" while the matched slot still holds that photo, "replaced" once
     * it doesn't (or the person is gone), "none" with no best match. */
    private static String bestPhotoState(PeopleStore store, FaceCheck c) {
        if (c.bestId.isEmpty()) {
            return "none";
        }
        return store.addedAt(c.bestId, c.bestSlot) == c.bestAddedAtMillis ? "current" : "replaced";
    }

    private static String save(Map<String, String> form, ClaudeSettings settings) {
        try {
            // A model picked from the list wins; the default entry ("") keeps the typed name.
            String pick = form.get("model_pick");
            String model = pick != null && !pick.trim().isEmpty() ? pick.trim() : form.get("model");
            settings.save(form.get("base_url"), form.get("key"), model);
        } catch (ClaudeSettings.InvalidException e) {
            // Fixed text by contract (ClaudeSettings), never what was typed.
            return "Not saved: " + e.getMessage();
        }
        ClaudeSettings.Status st = settings.status();
        if (!st.keySet) {
            return "Saved. Enter an API key to use Claude.";
        }
        if (st.model.isEmpty()) {
            return "Saved. Press Refresh models, pick a model, and save again.";
        }
        return "Saved.";
    }

    /** The Conversation form (meeting plan U4, KTD11): the persona box and the
     * switch, which is off when its checkbox is absent from the post. Every
     * return is fixed text, never the persona. */
    private static String saveConversation(Map<String, String> form, ClaudeSettings settings) {
        try {
            settings.saveConversation(form.get("persona"), "on".equals(form.get("answers")));
        } catch (ClaudeSettings.InvalidException e) {
            return "Not saved: " + e.getMessage();
        }
        return settings.conversation().personaSet ? CONVERSATION_SAVED : CONVERSATION_DEFAULT;
    }

    private static String refreshModels(ClaudeSettings settings, ClaudeApi api) {
        ClaudeSettings.Credentials c = settings.credentialsForRequests();
        ClaudeApi.Result result = api.listModels(c.baseUrl, c.apiKey);
        if (!result.ok()) {
            // R9: the stored model and the last list stay as they were.
            return "Models not refreshed: " + result.describe();
        }
        int found = settings.saveModels(result.models);
        if (found == 0) {
            return "The endpoint listed no models; type a model name instead.";
        }
        return "Found " + found + (found == 1 ? " model." : " models.");
    }

    private static String testConnection(ClaudeSettings settings, ClaudeApi api) {
        ClaudeSettings.Credentials c = settings.credentialsForRequests();
        ClaudeApi.Result result = api.testConnection(c.baseUrl, c.apiKey, c.model);
        if (!result.ok()) {
            return "Test failed: " + result.describe();
        }
        // The stored model passed ClaudeSettings.checkModel(), so it is safe to show.
        return "Connection works: " + c.model + " answered.";
    }

    /** Queues the typed line. Every return is fixed text: never the line itself. */
    private static String say(String text, Speaker speaker) {
        if (text == null || text.trim().isEmpty()) {
            return SAY_EMPTY;
        }
        if (text.length() > SpeechQueue.MAX_CHARS) {
            return SAY_TOO_LONG;
        }
        if (speaker.failed()) {
            return SAY_UNAVAILABLE;
        }
        if (!speaker.ready()) {
            return SAY_LOADING;
        }
        try {
            speaker.say(text);
        } catch (IllegalArgumentException e) {
            // The queue shut down (the voice failed to load) or refused the line.
            return SpeechQueue.REFUSE_UNAVAILABLE.equals(e.getMessage())
                    ? SAY_UNAVAILABLE : SAY_EMPTY;
        }
        return SAY_SPEAKING;
    }

    static String buildHtml(String token, ClaudeSettings.Status st, List<String> models,
                            ConversationSettings conversation, String voiceName, PeopleStore people,
                            FaceChecks checks, FaceSettings face, String status) {
        String t = escapeHtml(token);
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html><head><meta charset=\"utf-8\">");
        html.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">");
        html.append("<title>Miko3 Settings</title>");
        html.append("<link rel=\"stylesheet\" href=\"/assets/pico.min.css\">");
        html.append("</head><body><main class=\"container\">");
        html.append("<nav><ul><li><a href=\"/\">&larr; Home</a></li></ul></nav>");
        html.append("<h1>Settings</h1>");
        if (status != null) {
            html.append("<p id=\"settings-status\" role=\"status\">").append(escapeHtml(status)).append("</p>");
        }

        // One <section> per group of settings (R1); later ones go after this.
        html.append("<section id=\"claude\"><h2>Claude API</h2>");
        html.append("<form method=\"post\" action=\"").append(LauncherProtocol.SETTINGS_CLAUDE_PATH).append("\">");
        html.append("<input type=\"hidden\" name=\"t\" value=\"").append(t).append("\">");
        html.append("<label for=\"base_url\">Base URL");
        html.append("<input type=\"url\" id=\"base_url\" name=\"base_url\" value=\"")
                .append(escapeHtml(st.baseUrl))
                .append("\" autocomplete=\"off\" autocapitalize=\"off\" spellcheck=\"false\">");
        html.append("<small>Changing it needs the key entered again in the same save.</small></label>");
        // Never pre-filled (R4): empty keeps the stored key (R5).
        html.append("<label for=\"key\">API key");
        html.append("<input type=\"password\" id=\"key\" name=\"key\" autocomplete=\"off\" "
                + "autocapitalize=\"off\" spellcheck=\"false\">");
        html.append("<small>Leave empty to keep the saved key.</small></label>");
        // A plain select, not a datalist: a datalist only suggests entries matching the
        // text already in the box (the current model), so it showed nothing (owner, 2026-10-01).
        if (!models.isEmpty()) {
            html.append("<label for=\"model_pick\">Pick a model");
            html.append("<select id=\"model_pick\" name=\"model_pick\">");
            html.append("<option value=\"\" selected>Use the name typed below</option>");
            for (String id : models) {
                html.append("<option value=\"").append(escapeHtml(id)).append("\">").append(escapeHtml(id))
                        .append(id.equals(st.model) ? " (current)" : "").append("</option>");
            }
            html.append("</select></label>");
        }
        html.append("<label for=\"model\">Model");
        html.append("<input type=\"text\" id=\"model\" name=\"model\" value=\"")
                .append(escapeHtml(st.model))
                .append("\" autocomplete=\"off\" autocapitalize=\"off\" spellcheck=\"false\">");
        if (!models.isEmpty() && !st.model.isEmpty() && !models.contains(st.model)) {
            // KTD5: kept, since the endpoint's list may be incomplete.
            html.append("<small id=\"model-unlisted\">").append(escapeHtml(st.model))
                    .append(" is not listed by endpoint.</small>");
        } else {
            html.append("<small>Pick from the list above after Refresh models, or type a model name.</small>");
        }
        html.append("</label>");
        html.append("<button type=\"submit\">Save</button>");
        html.append("</form>");

        html.append("<p id=\"claude-key-status\">").append(escapeHtml(keyStatus(st))).append("</p>");

        // Token-only forms: they act on the saved settings, never on the fields above.
        html.append("<p>These use the saved settings \u2014 save changes first.</p>");
        html.append("<div class=\"grid\">");
        tokenForm(html, t, LauncherProtocol.SETTINGS_CLAUDE_MODELS_PATH, "Refresh models", "secondary");
        tokenForm(html, t, LauncherProtocol.SETTINGS_CLAUDE_TEST_PATH, "Test connection", "secondary");
        tokenForm(html, t, LauncherProtocol.SETTINGS_CLAUDE_FORGET_PATH, "Forget key", "contrast");
        html.append("</div>");
        html.append("</section>");

        appendConversation(html, t, conversation);

        html.append("<section id=\"voice\"><h2>Voice</h2>");
        html.append("<p id=\"voice-name\">Voice: ").append(escapeHtml(voiceName)).append("</p>");
        html.append("<form method=\"post\" action=\"").append(LauncherProtocol.SETTINGS_VOICE_SAY_PATH)
                .append("\">");
        html.append("<input type=\"hidden\" name=\"t\" value=\"").append(t).append("\">");
        html.append("<label for=\"say-text\">Something to say");
        html.append("<input type=\"text\" id=\"say-text\" name=\"text\" maxlength=\"")
                .append(SpeechQueue.MAX_CHARS).append("\" autocomplete=\"off\">");
        html.append("<small>The robot says it out loud.</small></label>");
        html.append("<button type=\"submit\">Say it</button>");
        html.append("</form>");
        html.append("</section>");

        appendFaceChecks(html, people, checks);
        appendThresholds(html, face);
        appendPeople(html, t, people);

        html.append("</main></body></html>");
        return html.toString();
    }

    /**
     * Meeting plan U4 (KTD11; R5, R20): the persona box, showing the owner's
     * text or the built-in default, with a length hint, and the switch. The
     * box carries data-default="1" while the default is showing, so
     * scripts/robot-settings.py can toggle the switch without turning the
     * default into stored text. Explore reads both per conversation, so a
     * save here is heard in the next one.
     */
    private static void appendConversation(StringBuilder html, String t, ConversationSettings c) {
        html.append("<section id=\"conversation\"><h2>Conversation</h2>");
        html.append("<form method=\"post\" action=\"").append(LauncherProtocol.SETTINGS_CONVERSATION_PATH)
                .append("\">");
        html.append("<input type=\"hidden\" name=\"t\" value=\"").append(t).append("\">");
        html.append("<label for=\"persona\">Persona");
        html.append("<textarea id=\"persona\" name=\"persona\" rows=\"16\" maxlength=\"")
                .append(ConversationSettings.MAX_PERSONA_CHARS).append("\"")
                .append(c.personaSet ? "" : " data-default=\"1\"").append(">")
                .append(escapeHtml(c.persona)).append("</textarea>");
        html.append("<small id=\"persona-hint\">").append(c.persona.length()).append(" of ")
                .append(ConversationSettings.MAX_PERSONA_CHARS).append(" characters. ")
                .append(c.personaSet ? "Empty the box to go back to the built-in text."
                        : "This is the built-in text; edit it to make it his.")
                .append("</small></label>");
        html.append("<label for=\"answers\"><input type=\"checkbox\" id=\"answers\" name=\"answers\" value=\"on\" "
                + "role=\"switch\"").append(c.answersWhenSpokenTo ? " checked" : "").append("> Answers when spoken to");
        html.append("<br><small>Off: only “Hey Miko” opens a conversation.</small></label>");
        html.append("<button type=\"submit\">Save</button>");
        html.append("</form>");
        html.append("</section>");
    }

    /** R15, R16, R18: everyone he remembers, most recently seen first, each
     * with their notes (escaped: every entry came from the model or a person)
     * or a legacy mark when the record has no name (KTD10). */
    private static void appendPeople(StringBuilder html, String t, PeopleStore store) {
        List<PeopleStore.Person> people = store.all();
        html.append("<section id=\"people\"><h2>People</h2>");
        html.append("<p>The people the robot remembers. Forget deletes their face, name and notes for good.</p>");
        if (people.isEmpty()) {
            html.append("<p id=\"people-empty\">He hasn't met anyone yet.</p>");
        }
        for (PeopleStore.Person p : people) {
            // Ids are 16 hex digits (PeopleStore), so safe in a URL and an attribute.
            String id = escapeHtml(p.id);
            String name = escapeHtml(p.name);
            html.append("<article id=\"person-").append(id).append("\">");
            html.append("<img src=\"").append(LauncherProtocol.SETTINGS_PEOPLE_FACE_PATH).append("?id=").append(id)
                    .append("\" alt=\"").append(p.name.isEmpty() ? "unnamed person" : name)
                    .append("\" width=\"112\" height=\"112\">");
            html.append("<p><strong>").append(p.name.isEmpty() ? "<em>unnamed</em>" : name).append("</strong><br>");
            html.append("<small>Last seen ").append(escapeHtml(lastSeen(p.lastSeenMillis))).append("</small></p>");
            if (p.name.isEmpty()) {
                html.append("<p class=\"legacy\"><small>").append(PEOPLE_LEGACY).append("</small></p>");
            } else {
                appendNotes(html, store.notes(p.id));
            }
            appendPhotoStrip(html, t, p.id, store.photos(p.id));
            html.append("<form method=\"post\" action=\"").append(LauncherProtocol.SETTINGS_PEOPLE_RENAME_PATH)
                    .append("\">");
            html.append("<input type=\"hidden\" name=\"t\" value=\"").append(t).append("\">");
            html.append("<input type=\"hidden\" name=\"id\" value=\"").append(id).append("\">");
            html.append("<label>Name");
            html.append("<input type=\"text\" name=\"name\" value=\"").append(name).append("\" maxlength=\"")
                    .append(PeopleStore.MAX_NAME_CHARS).append("\" autocomplete=\"off\">");
            html.append("<small>Leave empty to make them unnamed.</small></label>");
            html.append("<button type=\"submit\" class=\"secondary\">Rename</button></form>");
            html.append("<form method=\"post\" action=\"").append(LauncherProtocol.SETTINGS_PEOPLE_FORGET_PATH)
                    .append("\">");
            html.append("<input type=\"hidden\" name=\"t\" value=\"").append(t).append("\">");
            html.append("<input type=\"hidden\" name=\"id\" value=\"").append(id).append("\">");
            html.append("<button type=\"submit\" class=\"contrast\">Forget</button></form>");
            html.append("</article>");
        }
        html.append("</section>");
    }

    /**
     * Face plan U5 (KTD8; R13, R14): the recent checks, newest first. Each
     * shows its crop (none for "no face found"), and for a match the best
     * person's photo as it was when matched (or a marker once that slot was
     * replaced), their name, the score to two decimals and the decision; a
     * rejected check shows its reason instead. A near tie names the
     * runner-up with their score. Names are escaped: they were typed or heard.
     */
    private static void appendFaceChecks(StringBuilder html, PeopleStore store, FaceChecks checks) {
        List<FaceChecks.Entry> entries = checks.list();
        html.append("<section id=\"face-checks\"><h2>Face checks</h2>");
        html.append("<p>The robot's last ").append(FaceChecks.CAPACITY).append(" looks at a face, newest first. "
                + "Kept in memory only: they are gone when the launcher restarts.</p>");
        if (entries.isEmpty()) {
            html.append("<p id=\"checks-empty\">").append(CHECKS_EMPTY).append("</p>");
        }
        for (FaceChecks.Entry e : entries) {
            FaceCheck c = e.check;
            html.append("<article id=\"check-").append(e.handle).append("\">");
            html.append("<div class=\"grid\">");
            if (c.cropJpeg != null) {
                html.append("<img src=\"").append(LauncherProtocol.SETTINGS_FACE_CHECK_CROP_PATH).append("?id=")
                        .append(e.handle).append("\" alt=\"the face he saw\" width=\"112\" height=\"112\">");
            } else {
                html.append("<p><small>No crop.</small></p>");
            }
            if (!c.bestId.isEmpty()) {
                appendBestMatch(html, store, c);
            }
            html.append("</div>");
            html.append("<p><strong>").append(escapeHtml(decisionText(c.decision, c.rejectReason))).append("</strong>");
            if (!c.bestId.isEmpty()) {
                html.append(", score ").append(score(c.score));
            }
            html.append("<br><small>").append(escapeHtml(lastSeen(e.atMillis))).append("</small></p>");
            if (c.nearTie && !c.runnerUpId.isEmpty()) {
                String other = store.nameOf(c.runnerUpId);
                html.append("<p class=\"near-tie\"><small>").append(NEAR_TIE).append(' ')
                        .append(other == null ? "someone since forgotten" : nameHtml(other)).append(" (")
                        .append(score(c.runnerUpScore)).append(")</small></p>");
            }
            html.append("<p class=\"outcome\">Then: ").append(escapeHtml(outcomeText(e.outcome)));
            if (!e.joinedId.isEmpty()) {
                String joined = store.nameOf(e.joinedId);
                if (joined != null) {
                    html.append(" (").append(nameHtml(joined)).append(")");
                }
            }
            html.append("</p>");
            html.append("</article>");
        }
        html.append("</section>");
    }

    /** The best match's photo as it was when matched, then their name. */
    private static void appendBestMatch(StringBuilder html, PeopleStore store, FaceCheck c) {
        String name = store.nameOf(c.bestId);
        if (name == null) {
            html.append("<p><small>").append(CHECK_PERSON_GONE).append("</small></p>");
            return;
        }
        html.append("<div>");
        if (store.addedAt(c.bestId, c.bestSlot) == c.bestAddedAtMillis) {
            html.append("<img src=\"").append(escapeHtml(photoUrl(c.bestId, c.bestSlot, c.bestAddedAtMillis)))
                    .append("\" alt=\"best match\" width=\"112\" height=\"112\">");
        } else {
            html.append("<p class=\"replaced\"><small>").append(CHECK_PHOTO_REPLACED).append("</small></p>");
        }
        html.append("<p>").append(nameHtml(name)).append("</p></div>");
    }

    /** Face plan U5 (KTD5): the thresholds, read-only. They are set with
     * scripts/robot-faces.py over adb, never from this page. */
    private static void appendThresholds(StringBuilder html, FaceSettings f) {
        html.append("<section id=\"face-thresholds\"><h2>Face thresholds</h2>");
        html.append("<p>Read-only here: set them with scripts/robot-faces.py over adb. "
                + "They apply from the next meeting.</p>");
        html.append("<table><tbody>");
        thresholdRow(html, "Confident (greets by name) at or above", Float.toString(f.confident));
        thresholdRow(html, "Close (asks \u201cIs that you?\u201d) at or above", Float.toString(f.close));
        thresholdRow(html, "Near-tie margin", Float.toString(f.margin));
        thresholdRow(html, "Smallest face width (pixels)", Integer.toString(f.minWidth));
        thresholdRow(html, "Too dark below (mean brightness)", plain(f.darkFloor));
        thresholdRow(html, "Brightened below (mean brightness)", plain(f.dimLevel));
        thresholdRow(html, "Too blurry below (sharpness)", plain(f.blurFloor));
        html.append("</tbody></table>");
        html.append("</section>");
    }

    private static void thresholdRow(StringBuilder html, String label, String value) {
        html.append("<tr><th scope=\"row\">").append(label).append("</th><td>").append(escapeHtml(value))
                .append("</td></tr>");
    }

    /** 40 for 40.0, 31.5 for 31.5. */
    private static String plain(double d) {
        return d == Math.rint(d) && Math.abs(d) < 1e15 ? Long.toString((long) d) : Double.toString(d);
    }

    /** A person's photos in slot order (KTD8, KTD11), each deletable while
     * they have another, and marked when no face was found in it. */
    private static void appendPhotoStrip(StringBuilder html, String t, String id, List<PeopleStore.Photo> photos) {
        if (photos.isEmpty()) {
            return;
        }
        String eid = escapeHtml(id);
        html.append("<div class=\"photos grid\">");
        for (PeopleStore.Photo ph : photos) {
            html.append("<figure id=\"photo-").append(eid).append('-').append(ph.slot).append("\">");
            html.append("<img src=\"").append(escapeHtml(photoUrl(id, ph.slot, ph.addedAtMillis)))
                    .append("\" alt=\"photo ").append(ph.slot + 1).append("\" width=\"80\" height=\"80\">");
            if (ph.unusable) {
                html.append("<figcaption><mark>").append(PHOTO_UNUSABLE).append("</mark></figcaption>");
            }
            if (photos.size() > 1) {
                html.append("<form method=\"post\" action=\"")
                        .append(LauncherProtocol.SETTINGS_PEOPLE_PHOTO_DELETE_PATH).append("\">");
                html.append("<input type=\"hidden\" name=\"t\" value=\"").append(t).append("\">");
                html.append("<input type=\"hidden\" name=\"id\" value=\"").append(eid).append("\">");
                html.append("<input type=\"hidden\" name=\"slot\" value=\"").append(ph.slot).append("\">");
                html.append("<button type=\"submit\" class=\"secondary outline\">Delete photo</button></form>");
            }
            html.append("</figure>");
        }
        html.append("</div>");
        if (photos.size() == 1) {
            html.append("<p><small>Their only photo; Forget removes it with them.</small></p>");
        }
    }

    private static String photoUrl(String id, int slot, long addedAtMillis) {
        return LauncherProtocol.SETTINGS_PEOPLE_PHOTO_PATH + "?id=" + id + "&slot=" + slot + "&at=" + addedAtMillis;
    }

    private static String nameHtml(String name) {
        return name.isEmpty() ? "<em>unnamed</em>" : escapeHtml(name);
    }

    /** A score to two decimals, e.g. "0.61". */
    static String score(float f) {
        return String.format(Locale.US, "%.2f", f);
    }

    /** The decision as the page says it: the band, or the rejection with its reason. */
    static String decisionText(int decision, int reason) {
        switch (decision) {
            case FaceCheck.CONFIDENT: return "confident";
            case FaceCheck.CLOSE: return "close";
            case FaceCheck.WEAK: return "weak";
            case FaceCheck.REJECTED: return "rejected: " + reasonText(reason);
            case FaceCheck.NOT_READY: return "store not ready";
            case FaceCheck.NO_FACE: return "no face found";
            default: return "unknown";
        }
    }

    /** The decision as the JSON view names it. */
    static String decisionName(int decision) {
        switch (decision) {
            case FaceCheck.REJECTED: return "rejected";
            case FaceCheck.NOT_READY: return "not ready";
            case FaceCheck.NO_FACE: return "no face";
            default: return decisionText(decision, FaceCheck.REASON_NONE);
        }
    }

    static String reasonText(int reason) {
        switch (reason) {
            case FaceCheck.TOO_DARK: return "too dark";
            case FaceCheck.TOO_BLURRY: return "too blurry";
            case FaceCheck.TOO_SMALL: return "too small";
            default: return "";
        }
    }

    static String outcomeText(int outcome) {
        switch (outcome) {
            case FaceCheck.YES: return "yes";
            case FaceCheck.NO: return "no";
            case FaceCheck.NAME_GIVEN: return "name given";
            case FaceCheck.JOINED: return "joined";
            case FaceCheck.NEW_PERSON: return "new person";
            case FaceCheck.NO_REPLY: return "no reply";
            case FaceCheck.ENDED_WITHOUT_ANSWER: return "ended without an answer";
            default: return "pending";
        }
    }

    /** The notes as four short lists, every entry escaped. */
    private static void appendNotes(StringBuilder html, PersonNotes notes) {
        if (notes.isEmpty()) {
            html.append("<p class=\"notes\"><small>").append(PEOPLE_NO_NOTES).append("</small></p>");
            return;
        }
        html.append("<div class=\"notes\">");
        notesList(html, "Interests", notes.interests);
        if (!notes.openThreads.isEmpty()) {
            html.append("<p><strong>Open threads</strong></p><ul>");
            for (PersonNotes.Thread thread : notes.openThreads) {
                html.append("<li>").append(escapeHtml(thread.text)).append(" <small>(since ")
                        .append(escapeHtml(lastSeen(thread.sinceMillis).substring(0, 10))).append(")</small></li>");
            }
            html.append("</ul>");
        }
        notesList(html, "Topics", notes.topics);
        notesList(html, "Questions asked", notes.questionsAsked);
        html.append("</div>");
    }

    private static void notesList(StringBuilder html, String label, List<String> entries) {
        if (entries.isEmpty()) {
            return;
        }
        html.append("<p><strong>").append(label).append("</strong></p><ul>");
        for (String entry : entries) {
            html.append("<li>").append(escapeHtml(entry)).append("</li>");
        }
        html.append("</ul>");
    }

    /** "2026-09-24 15:45", the robot's local time. */
    static String lastSeen(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(millis));
    }

    private static void tokenForm(StringBuilder html, String escapedToken, String action, String label,
                                  String cls) {
        html.append("<form method=\"post\" action=\"").append(action).append("\">");
        html.append("<input type=\"hidden\" name=\"t\" value=\"").append(escapedToken).append("\">");
        html.append("<button type=\"submit\" class=\"").append(cls).append("\">").append(label)
                .append("</button></form>");
    }

    /** "Key set, ends in …abcd, saved <time>" or "No key set" (R4). */
    static String keyStatus(ClaudeSettings.Status st) {
        if (!st.keySet) {
            return "No key set";
        }
        StringBuilder s = new StringBuilder("Key set");
        if (!st.keyLastFour.isEmpty()) {
            s.append(", ends in …").append(st.keyLastFour);
        }
        if (st.keySavedAtMillis > 0) {
            s.append(", saved ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                    .format(new Date(st.keySavedAtMillis)));
        }
        return s.toString();
    }

    /**
     * True when the URL is the launcher's home page ("/", any query). The
     * robot's WebView reloads on Wi-Fi broadcasts only then (KTD9), so a scan
     * never wipes a half-typed key here or re-issues the page token.
     */
    static boolean isHomePageUrl(String url) {
        if (url == null) {
            return false;
        }
        try {
            String path = new URI(url).getPath();
            return path == null || path.isEmpty() || "/".equals(path);
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /** The urlencoded form body, or null when it is larger than MAX_FORM_BYTES. */
    static Map<String, String> readForm(HttpRequest req) throws IOException {
        InputStream in = req.body;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
            if (out.size() > MAX_FORM_BYTES) {
                return null;
            }
        }
        return HttpRequest.parseQuery(new String(out.toByteArray(), StandardCharsets.UTF_8));
    }

    static String escapeHtml(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': out.append("&amp;"); break;
                case '<': out.append("&lt;"); break;
                case '>': out.append("&gt;"); break;
                case '"': out.append("&quot;"); break;
                case '\'': out.append("&#39;"); break;
                default: out.append(c);
            }
        }
        return out.toString();
    }

    private static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return "";
        }
    }
}
