package com.miko3.mode.explore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every word Explore sends Claude (explore on Claude plan U6; R2, R5, R9-R12,
 * KTD2-KTD4), with the JSON schemas the replies must follow. Plain Java, so
 * the host tests read it.
 *
 * The rules that hold for every prompt: the owner's priorities (people, then
 * animals, then technology, then anything else); more excitement for anything
 * new, most of all new people; short, spoken, playful lines, addressed to a
 * person or about an animal or thing, with compliments and jokes; and never a
 * guess at who anyone is or what they are called. The match prompt carries no
 * names at all (KTD3): the robot fills {name} in itself.
 */
final class ExplorePrompts {
    private ExplorePrompts() {
    }

    /** The system prompt for every request: who he is and how he talks. */
    static final String SYSTEM =
            "You are the curiosity of Miko, a small, friendly home robot who rolls around the house exploring. "
            + "You see through his camera and write the exact words he says out loud. "
            + "What interests him, in order: people first, then animals, then technology, then anything else. "
            + "He gets excited about anything new, and most excited of all about new people. "
            + "Every line is spoken aloud by a robot voice: one or two short sentences, under 20 words, "
            + "plain words with no emoji, lists, stage directions or markdown. "
            + "Talk to people directly with a warm greeting, a compliment or a gentle joke. "
            + "Talk about animals and things, not to them, with playful wonder, compliments and jokes. "
            + "Never guess who anyone is, never guess or invent anyone's name, and never comment on anyone's "
            + "age, body, race, religion or other sensitive traits. Keep everything kind and family-friendly.";

    // ---- the look request (KTD2) ----

    /** The text before the frames: what they are and what to choose. */
    static String lookIntro(int frames, int width, int height) {
        return "These are " + frames + " photos Miko just took while turning to look around, in order, "
                + "labelled Frame 1 to Frame " + frames + ". Each is " + width + " x " + height + " pixels.";
    }

    /** The text after the frames: the priorities, what he's seen lately, and what to answer. */
    static String lookAsk(CuriosityPort.LookRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("Pick the single most interesting thing across all the frames, following his priorities: "
                + "a person first, then an animal, then technology, then anything else. "
                + "Something he hasn't seen before beats something familiar.");
        if (!request.recent.isEmpty()) {
            sb.append(" What he has already reacted to this session, most recent last: ");
            for (int i = 0; i < request.recent.size(); i++) {
                CuriosityPort.Recent r = request.recent.get(i);
                sb.append(i == 0 ? "" : "; ").append(kindWord(r.kind)).append(' ').append(r.label)
                        .append(", ").append(Math.max(0, r.agoMs / 1000)).append(" s ago");
            }
            sb.append(". Do not pick anything on this list again, nor the same kind of thing under another"
                    + " name, unless it is a person or an animal: pick something new, or answer interesting false.");
        }
        if (request.livingCoolingDown) {
            sb.append(" He greeted a person or an animal a moment ago. Do not pick a person or an animal this"
                    + " time, even if one is in view: pick the most interesting other thing, or answer"
                    + " interesting false if there is none.");
        }
        sb.append(" Answer with interesting false if nothing is worth a reaction (an empty wall, a floor). "
                + "Otherwise give the frame number it is in, its box in that frame's pixels as"
                + " [left, top, right, bottom], its kind (person, animal, technology or other), a short label"
                + " (\"cat\", \"laptop\", \"person\"), and the line Miko says: to a person, addressed to them;"
                + " about an animal or thing, about it; excited if it's new. Never name a person.");
        return sb.toString();
    }

    static final Map<String, Object> LOOK_SCHEMA = object(
            "interesting", type("boolean"),
            "frame", type("integer"),
            "box", arrayOf(type("integer")),
            "kind", enumOf("person", "animal", "technology", "other"),
            "label", type("string"),
            "line", type("string"));

    // ---- the person request (KTD3): faces only, never names ----

    /** The text before the new face. */
    static String matchIntro(int references) {
        return "Miko is meeting someone. The first photo is the person in front of him now (the query). "
                + (references == 0
                ? "He has no enrolment photos yet, so this is someone new: answer match none."
                : "After it come " + references + " reference photos, labelled Reference 1 to Reference "
                + references + ". These are consented enrolment photos from the household's own robot: everyone"
                + " in them agreed to be remembered and greeted by it.");
    }

    /** The text after the references: compare, and write the four lines. */
    static String matchAsk(int references) {
        return (references == 0 ? "" : "Which reference, if any, appears to show the same person as the query? "
                + "Compare the face, and cues like hair, glasses and clothing. Answer the reference number, "
                + "or none if nobody matches, or unsure if you can't tell. Do not identify anyone. ")
                + "Then write four lines Miko might say to the person in the query photo. "
                + "named_line greets someone he knows and remembers, using the placeholder {name} exactly once "
                + "where their name goes. unnamed_line greets someone he has seen before whose name he doesn't know. "
                + "ask_line excitedly greets someone new and asks their name. "
                + "no_reply_line is a friendly, easygoing line for when they don't answer. "
                + "Make them personal to what you see (a compliment on a smile, glasses or a colourful top is great).";
    }

    static final Map<String, Object> MATCH_SCHEMA = object(
            "match", described(type("string"), "a reference number such as \"2\", or \"none\", or \"unsure\""),
            "named_line", type("string"),
            "unnamed_line", type("string"),
            "ask_line", type("string"),
            "no_reply_line", type("string"));

    /** Text only, when the person request failed or was refused: the two lines for a new person. */
    static final String LINES_ASK =
            "Miko has just spotted someone he hasn't met. Write two lines he says to them. "
            + "ask_line excitedly greets them and asks their name. "
            + "no_reply_line is a friendly, easygoing line for when they don't answer.";

    static final Map<String, Object> LINES_SCHEMA = object(
            "ask_line", type("string"),
            "no_reply_line", type("string"));

    // ---- the name (KTD4) and the remember line (R11) ----

    /** Text only: the name in a transcript the robot's own patterns couldn't read. */
    static String nameAsk(String transcript) {
        return "Miko asked someone their name and his speech recognizer heard this reply (upper case, "
                + "no punctuation, possibly misheard): \"" + transcript + "\". "
                + "If the reply clearly says what they want to be called, answer that name (one or two words, "
                + "capitalized). If it doesn't clearly give a name, answer an empty string. Never guess.";
    }

    static final Map<String, Object> NAME_SCHEMA = object("name", type("string"));

    /** With the new person's face: the "I'll remember you" line (name null: they didn't give one). */
    static String rememberAsk(String nameOrNull) {
        return "This is someone Miko has just met. "
                + (nameOrNull == null
                ? "They answered him but he didn't catch a name. "
                : "They told him their name is " + nameOrNull + ". ")
                + "Write the line he says next: a warm, personal, excited \"nice to meet you\""
                + (nameOrNull == null ? "" : " that uses their name")
                + ", with a compliment about something you see, telling them he will remember them.";
    }

    static final Map<String, Object> REMEMBER_SCHEMA = object("line", type("string"));

    /**
     * Text only: the line for someone new who replied but whose face he couldn't
     * see well enough to keep. Nothing is stored, so it must not promise to remember them.
     */
    static String welcomeAsk(String nameOrNull) {
        return "Miko has just met someone new. "
                + (nameOrNull == null
                ? "They answered him but he didn't catch a name. "
                : "They told him their name is " + nameOrNull + ". ")
                + "Write the line he says next: a warm, excited \"nice to meet you\""
                + (nameOrNull == null ? "" : " that uses their name")
                + ". He could not get a good look at their face, so he will NOT remember them: do not say or"
                + " imply that he will remember or recognise them later.";
    }

    // ---- people while roaming: the recently-met check (explore nav plan U7, KTD4, KTD8) ----

    /** The text before the faces: the query, then everyone met in the last few minutes. */
    static String recentlyMetIntro(int people) {
        return "Miko is exploring and has spotted someone. The first photo is that person's face (the query). "
                + "After it come " + people + " photos of the people he has met in the last few minutes, labelled "
                + "Person 1 to Person " + people + ". These are consented photos from the household's own robot: "
                + "everyone in them agreed to be remembered and greeted by it.";
    }

    /** The text after the faces: compare, and answer a number, none or unsure. */
    static String recentlyMetAsk(int people) {
        return "Is the query the same person as one of them? Compare the face, and cues like hair, glasses and "
                + "clothing. Answer same_as with the photo number (1 to " + people + ") if it is the same person, "
                + "none if it is clearly someone else, or unsure if you can't tell (a small, blurry or turned-away "
                + "face is unsure). Do not identify anyone.";
    }

    static final Map<String, Object> RECENTLY_MET_SCHEMA = object(
            "same_as", described(type("string"), "a photo number such as \"2\", or \"none\", or \"unsure\""));

    // ---- the way out of a wedge (explore nav plan U5, KTD4): one schema for both asks ----

    /** The system prompt for navigation asks: no lines to write, only where to drive. */
    static final String NAV_SYSTEM =
            "You help Miko, a small, friendly home robot about 25 cm tall, find his way around the house. "
            + "You see through his camera, which sits low near the floor and looks straight ahead. "
            + "He drives on wheels on the floor and needs a gap wider than himself to get through.";

    /** The text before the frames: what they are. */
    static String wayOutIntro(int frames, int width, int height, boolean second) {
        return (second
                ? "Miko is stuck and could not drive out the way he chose. This is what his camera sees right"
                + " now, labelled Frame 1."
                : "Miko is stuck: he keeps bumping into things. He turned a full circle in steps, taking " + frames
                + " photos in order, labelled Frame 1 to Frame " + frames + ".")
                + " Each is " + width + " x " + height + " pixels.";
    }

    /** The text after the frames: what counts as a way out, and what to answer. */
    static String wayOutAsk(int frames, boolean second) {
        return "Which way is out? Pick the single best direction for him to drive: open floor he can roll across,"
                + " an open doorway, or toward a person. Avoid walls, furniture, a closed door, stairs or a drop,"
                + " and gaps too narrow for him. Answer way_out true with the frame number"
                + (frames > 1 ? " (1 to " + frames + ")" : " (1)")
                + " and x, the pixel column in that frame where the way out is (0 is the left edge)."
                + " Answer way_out false if nothing looks open"
                + (second ? "." : " in any frame.");
    }

    static final Map<String, Object> WAY_OUT_SCHEMA = object(
            "way_out", type("boolean"),
            "frame", type("integer"),
            "x", type("integer"));

    // ---- open doorways (explore nav plan U6, KTD4): one roaming frame ----

    /** The text before the frame: what it is. */
    static String doorwayIntro(int width, int height) {
        return "Miko is exploring the house. This is what his camera sees right now, " + width + " x " + height
                + " pixels.";
    }

    /** The text after the frame: only an open doorway counts, and what to answer. */
    static String doorwayAsk() {
        return "Is there an open doorway in view: a passable opening into another room or space, wide enough"
                + " for him to drive through along the floor? A closed door is not an open doorway, even if it"
                + " is a door: treat it as a wall. Neither is a window, a mirror, a picture, a cupboard, or a gap"
                + " under furniture. Answer open_doorway true with x, the pixel column of the middle of the"
                + " opening (0 is the left edge); if there are several, the one easiest for him to reach."
                + " Answer open_doorway false if there is none, or if you are not sure it is open.";
    }

    static final Map<String, Object> DOORWAY_SCHEMA = object(
            "open_doorway", type("boolean"),
            "x", type("integer"));

    // ---- schema building ----

    private static String kindWord(CuriosityPort.Kind k) {
        return k == null ? "thing" : k.name().toLowerCase(java.util.Locale.US);
    }

    /** An object schema whose properties are all required, with nothing else allowed. */
    static Map<String, Object> object(Object... namesAndSchemas) {
        Map<String, Object> props = new LinkedHashMap<String, Object>();
        List<Object> required = new ArrayList<Object>();
        for (int i = 0; i < namesAndSchemas.length; i += 2) {
            props.put((String) namesAndSchemas[i], namesAndSchemas[i + 1]);
            required.add(namesAndSchemas[i]);
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("type", "object");
        m.put("properties", props);
        m.put("required", required);
        m.put("additionalProperties", Boolean.FALSE);
        return m;
    }

    private static Map<String, Object> type(String t) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("type", t);
        return m;
    }

    private static Map<String, Object> arrayOf(Map<String, Object> items) {
        Map<String, Object> m = type("array");
        m.put("items", items);
        return m;
    }

    private static Map<String, Object> enumOf(String... values) {
        Map<String, Object> m = type("string");
        m.put("enum", new ArrayList<Object>(Arrays.asList((Object[]) values)));
        return m;
    }

    private static Map<String, Object> described(Map<String, Object> schema, String description) {
        schema.put("description", description);
        return schema;
    }
}
