package com.miko3.mode.explore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The action tools (owner 2026-10-03: "the AI response could control the robot damn near
 * completely, or at least launch it on a workflow"). Claude decides and plans; the robot's
 * own code drives and keeps its reflexes. Each tool is checked here against the turn's
 * facts (ToolFacts: whether he can drive, what his detector knows, the places he looked at)
 * and answers Claude with an honest result: "started ...", "done ...", "can't: ...", or
 * "don't know where that is ...". An accepted call becomes an Act the brain carries out
 * once the turn's line is said (ExploreBrain.startIntent, ExploreBrain's task runner).
 *
 * - move {kind, amount}: a turn (left, right, around, a spin) or a short drive forward or
 *   back, capped, through the normal drive safety (floor sensor, CPL, stall RECOVER);
 * - stop, stay {minutes}, wait {seconds}: hold still (calls are still answered in place);
 * - come_here, go_away, be_quiet {minutes}, find_person {name}: today's intents;
 * - find_thing {label}: seek until his detector names it, then go over to it;
 * - go_to_place {description, labels}: head for where he saw those things, or search for them;
 * - run_task {goal, steps}: a short workflow of these plus say, look and come_back.
 *
 * At most one action per reply; nothing drives while docked, in bathroom privacy, without
 * wheels or while stuck (ToolFacts.still says which). The wording and schemas here are the
 * source; scripts/claude-chat-bench.py carries them byte for byte. Plain Java, no Android.
 * Nothing here logs; Act.describe() is the trace's view of an act: never a name, a label
 * Claude chose, a goal or words to say.
 */
final class ChatActions {
    private ChatActions() {
    }

    static final String MOVE = "move";
    static final String STOP = "stop";
    static final String STAY = "stay";
    static final String COME_HERE = "come_here";
    static final String GO_AWAY = "go_away";
    static final String BE_QUIET = "be_quiet";
    static final String FIND_PERSON = "find_person";
    static final String FIND_THING = "find_thing";
    static final String GO_TO_PLACE = "go_to_place";
    static final String WAIT = "wait";
    static final String RUN_TASK = "run_task";
    /** Steps only a task takes. */
    static final String SAY = "say";
    static final String COME_BACK = "come_back";

    // ---- caps ----
    static final double MAX_TURN_DEG = 360;
    static final double MAX_FORWARD_M = 1.5;
    /** Backing up is blind (nothing watches behind him): shorter than forward. */
    static final double MAX_BACK_M = 0.5;
    static final int MAX_STAY_MIN = 30;
    static final int DEFAULT_STAY_MIN = 5;
    static final int MAX_QUIET_MIN = 30;
    static final int DEFAULT_QUIET_MIN = 10;
    static final int MAX_WAIT_S = 120;
    static final int MAX_STEPS = 8;
    static final int MAX_SAY_CHARS = 200;
    static final int MAX_TEXT_CHARS = 60;

    static final String MOVE_DESCRIPTION = "Move Miko himself: turn left or right by some degrees, turn around, "
            + "spin once, or drive a short way forward or back. Use it only when the person asks him to move like "
            + "that. amount is degrees for a turn (at most 360) or metres for forward (at most 1.5) and back (at most "
            + "0.5); 0 means the usual amount.";
    static final String STOP_DESCRIPTION = "Stop whatever Miko is doing (an errand or a task) and stay put. Use it "
            + "when they tell him to stop or to stop that.";
    static final String STAY_DESCRIPTION = "Stay here and don't roam for some minutes (at most 30); a call still "
            + "gets an answer. Use it when they ask him to stay or wait here.";
    static final String COME_HERE_DESCRIPTION = "Come over to the person he is talking to. Use it when they ask him "
            + "to come here or come over.";
    static final String GO_AWAY_DESCRIPTION = "Turn away and leave the person alone for ten minutes. Use it when they "
            + "ask him to go away or leave them alone.";
    static final String BE_QUIET_DESCRIPTION = "Do not disturb for some minutes (0: ten, at most 30): no remarks, "
            + "and a call only gets a glance. Use it when they ask him to be quiet.";
    static final String FIND_PERSON_DESCRIPTION = "Go and look for someone: by name, or anyone new when the name is "
            + "empty, for up to five minutes. Use it when they ask him to go and find someone.";
    static final String FIND_THING_DESCRIPTION = "Search for a thing his detector can name, like a printer or a "
            + "chair, and go over to it when he sees it, for up to five minutes. label is the plain name of the thing "
            + "in English, singular.";
    static final String GO_TO_PLACE_DESCRIPTION = "Go to a place they name, like the kitchen. Miko does not know "
            + "rooms by name, only what his camera saw: labels are the things his detector would see there (a "
            + "kitchen: refrigerator, microwave, oven); he heads for where he saw them lately, or searches through "
            + "doorways for them. Do not call places first: this looks them up itself.";
    static final String WAIT_DESCRIPTION = "Wait where he is for some seconds (at most 120), then carry on.";
    static final String RUN_TASK_DESCRIPTION = "Run a short errand of several steps in order, like \"go to the "
            + "kitchen and see if anyone's there\" (go_to_place, then look, then come_back and say what he saw). "
            + "Each step is one of move, stay, wait, come_here, go_away, find_person, find_thing, go_to_place, say "
            + "(text: what he says out loud there), look (what his detector sees now; he asks you again after it, "
            + "so later steps can use what he saw) or come_back (back to where the errand started), with that "
            + "tool's arguments in args. Mark check true on a step whose outcome should decide the rest; he asks "
            + "again then, and whenever a step fails. At most 8 steps; goal is the errand in their words.";

    /** The tools' definitions, in the order they are sent: {name, description, input schema}. */
    static List<Object[]> definitions() {
        List<Object[]> out = new ArrayList<Object[]>();
        out.add(new Object[]{MOVE, MOVE_DESCRIPTION, MOVE_SCHEMA});
        out.add(new Object[]{STOP, STOP_DESCRIPTION, ExplorePrompts.object()});
        out.add(new Object[]{STAY, STAY_DESCRIPTION, STAY_SCHEMA});
        out.add(new Object[]{COME_HERE, COME_HERE_DESCRIPTION, ExplorePrompts.object()});
        out.add(new Object[]{GO_AWAY, GO_AWAY_DESCRIPTION, ExplorePrompts.object()});
        out.add(new Object[]{BE_QUIET, BE_QUIET_DESCRIPTION, QUIET_SCHEMA});
        out.add(new Object[]{FIND_PERSON, FIND_PERSON_DESCRIPTION, FIND_PERSON_SCHEMA});
        out.add(new Object[]{FIND_THING, FIND_THING_DESCRIPTION, FIND_THING_SCHEMA});
        out.add(new Object[]{GO_TO_PLACE, GO_TO_PLACE_DESCRIPTION, GO_TO_PLACE_SCHEMA});
        out.add(new Object[]{WAIT, WAIT_DESCRIPTION, WAIT_SCHEMA});
        out.add(new Object[]{RUN_TASK, RUN_TASK_DESCRIPTION, RUN_TASK_SCHEMA});
        return out;
    }

    static final Map<String, Object> MOVE_SCHEMA = ExplorePrompts.object(
            "kind", ExplorePrompts.enumOf("turn_left", "turn_right", "turn_around", "spin", "forward", "back"),
            "amount", ExplorePrompts.type("number"));
    static final Map<String, Object> STAY_SCHEMA = ExplorePrompts.object("minutes", ExplorePrompts.type("integer"));
    static final Map<String, Object> QUIET_SCHEMA = ExplorePrompts.object("minutes", ExplorePrompts.type("integer"));
    static final Map<String, Object> FIND_PERSON_SCHEMA = ExplorePrompts.object("name", ExplorePrompts.type("string"));
    static final Map<String, Object> FIND_THING_SCHEMA = ExplorePrompts.object("label", ExplorePrompts.type("string"));
    static final Map<String, Object> GO_TO_PLACE_SCHEMA = ExplorePrompts.object(
            "description", ExplorePrompts.type("string"),
            "labels", ExplorePrompts.arrayOf(ExplorePrompts.type("string")));
    static final Map<String, Object> WAIT_SCHEMA = ExplorePrompts.object("seconds", ExplorePrompts.type("integer"));
    static final List<String> STEP_TOOLS = Collections.unmodifiableList(java.util.Arrays.asList(MOVE, STAY, WAIT,
            COME_HERE, GO_AWAY, FIND_PERSON, FIND_THING, GO_TO_PLACE, SAY, ChatTools.LOOK, COME_BACK));
    static final Map<String, Object> STEP_SCHEMA = ExplorePrompts.object(
            "tool", ExplorePrompts.enumOf(STEP_TOOLS.toArray(new String[0])),
            "args", freeObject(),
            "check", ExplorePrompts.type("boolean"));
    static final Map<String, Object> RUN_TASK_SCHEMA = ExplorePrompts.object(
            "goal", ExplorePrompts.type("string"),
            "steps", ExplorePrompts.arrayOf(STEP_SCHEMA));

    // ---- a task's consult (ExploreBrain's run_task runner, CuriosityPort.taskPlan) ----
    static final String PLAN = "revise_plan";
    static final String PLAN_DESCRIPTION = "The rest of Miko's errand: the steps still to do from now on (the "
            + "steps already done stay done), or abort true with line, a short sentence he says out loud when the "
            + "errand can't or shouldn't go on.";
    static final Map<String, Object> PLAN_SCHEMA = ExplorePrompts.object(
            "abort", ExplorePrompts.type("boolean"),
            "line", ExplorePrompts.type("string"),
            "steps", ExplorePrompts.arrayOf(STEP_SCHEMA));

    /** An object schema with any properties (a step's args: that tool's own). */
    private static Map<String, Object> freeObject() {
        return ExplorePrompts.type("object");
    }

    /** Whether this tool is one of the actions (one per reply). */
    static boolean isAction(String name) {
        for (Object[] d : definitions()) {
            if (d[0].equals(name)) {
                return true;
            }
        }
        return false;
    }

    // ---- an act ----

    /** One accepted action (or a task's step). Its words (target, labels, text, goal) are never traced. */
    static final class Act {
        final CuriosityPort.Action action;
        /** The tool it came from (a step's tool for a step). */
        final String tool;
        /** move's kind, or null. */
        final String kind;
        /** move: degrees for a turn, metres forward or back; 0 otherwise. */
        final double amount;
        /** stay, wait, be_quiet: how long; 0 otherwise. */
        final long ms;
        /** find_person's name, find_thing's label, go_to_place's description; null when none. */
        final String target;
        /** go_to_place's labels (lower case, ones his detector knows), find_thing's label as one. */
        final List<String> labels;
        /** say's text. */
        final String text;
        /** A task's step that consults Claude once done. */
        final boolean check;
        /** run_task's steps, and its goal (the person's words: never logged). */
        final List<Act> steps;
        final String goal;

        Act(CuriosityPort.Action action, String tool, String kind, double amount, long ms, String target,
                List<String> labels, String text, boolean check, List<Act> steps, String goal) {
            this.action = action == null ? CuriosityPort.Action.NONE : action;
            this.tool = tool;
            this.kind = kind;
            this.amount = amount;
            this.ms = ms;
            this.target = target == null || target.trim().isEmpty() ? null : target.trim();
            this.labels = labels == null ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(new ArrayList<String>(labels));
            this.text = text;
            this.check = check;
            this.steps = steps == null ? Collections.<Act>emptyList()
                    : Collections.unmodifiableList(new ArrayList<Act>(steps));
            this.goal = goal;
        }

        /** Today's intent with its target, as the older action field gave it. */
        static Act of(CuriosityPort.Action a, String target) {
            return new Act(a, a == null ? null : a.word(), null, 0, 0, target, null, null, false, null, null);
        }

        /** The same act with check set (a task's step). */
        Act checked(boolean c) {
            return new Act(action, tool, kind, amount, ms, target, labels, text, c, steps, goal);
        }

        /** Whether carrying it out drives the wheels. */
        boolean drives() {
            switch (action) {
                case MOVE:
                case COME_HERE:
                case FIND_PERSON:
                case FIND_THING:
                case GO_TO_PLACE:
                case GO_ELSEWHERE:
                case COME_BACK:
                    return true;
                case RUN_TASK:
                    for (Act s : steps) {
                        if (s.drives()) {
                            return true;
                        }
                    }
                    return false;
                default:
                    return false;
            }
        }

        /** Whether the conversation goes on after its line (stop); everything else ends it. */
        boolean continuesConversation() {
            return action == CuriosityPort.Action.STOP;
        }

        /**
         * The trace's view: the tool, move's kind and amount, durations, whether a target
         * was given, a task's step tools. Never a name, label, place, goal or words.
         */
        String describe() {
            StringBuilder b = new StringBuilder(tool == null ? action.word() : tool);
            if (kind != null) {
                b.append(' ').append(kind).append(' ').append(fmt(amount));
            }
            if (ms > 0) {
                b.append(' ').append(ms / 1000).append('s');
            }
            if (action == CuriosityPort.Action.FIND_PERSON || action == CuriosityPort.Action.FIND_THING
                    || action == CuriosityPort.Action.GO_TO_PLACE) {
                b.append(target == null ? " (no target)" : " (target given)");
            }
            if (action == CuriosityPort.Action.RUN_TASK) {
                b.append(' ').append(steps.size()).append(" steps: ").append(stepTools(steps));
            }
            return b.toString();
        }
    }

    /** "go_to_place,look,say" */
    static String stepTools(List<Act> steps) {
        StringBuilder b = new StringBuilder();
        for (Act s : steps) {
            b.append(b.length() == 0 ? "" : ",").append(s.tool).append(s.check ? "?" : "");
        }
        return b.length() == 0 ? "-" : b.toString();
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.US, "%.2f", v);
    }

    // ---- checking a call ----

    /** A call's verdict: the act to carry out (null when refused) and what Claude is told. */
    static final class Verdict {
        final Act act;
        final String result;

        Verdict(Act act, String result) {
            this.act = act;
            this.result = result;
        }

        boolean ok() {
            return act != null;
        }

        static Verdict cant(String why) {
            return new Verdict(null, "can't: " + why + ". Say so honestly in the line.");
        }
    }

    /** One action call checked against the turn's facts. */
    static Verdict check(String tool, Map<String, Object> input, CuriosityPort.ToolFacts facts) {
        Map<String, Object> in = input == null ? Collections.<String, Object>emptyMap() : input;
        CuriosityPort.ToolFacts f = facts == null ? CuriosityPort.ToolFacts.NONE : facts;
        if (RUN_TASK.equals(tool)) {
            return checkTask(in, f);
        }
        Verdict v = checkOne(tool, in, f, false);
        if (v.ok() && v.act.drives() && f.still != null) {
            return Verdict.cant(f.still);
        }
        return started(v);
    }

    /** What every started act adds: it runs after the line, so the line never says how it went. */
    static final String AFTER_THE_LINE = " It starts after your line: say what he is about to do, never how it went.";

    private static Verdict started(Verdict v) {
        return v.ok() && v.result.startsWith("started") ? new Verdict(v.act, v.result + AFTER_THE_LINE) : v;
    }

    private static Verdict checkOne(String tool, Map<String, Object> in, CuriosityPort.ToolFacts f, boolean step) {
        if (MOVE.equals(tool)) {
            return checkMove(in);
        }
        if (STOP.equals(tool) && !step) {
            return new Verdict(simple(CuriosityPort.Action.STOP, STOP), "done: he stopped whatever he was doing and "
                    + "stays put. The conversation goes on.");
        }
        if (STAY.equals(tool)) {
            int min = clamp(intOf(in.get("minutes"), DEFAULT_STAY_MIN), 1, MAX_STAY_MIN, DEFAULT_STAY_MIN);
            return new Verdict(timed(CuriosityPort.Action.STAY, STAY, min * 60000L), "started: he stays here for "
                    + min + " min; a call still gets an answer.");
        }
        if (WAIT.equals(tool)) {
            int s = clamp(intOf(in.get("seconds"), 10), 1, MAX_WAIT_S, 10);
            return new Verdict(timed(CuriosityPort.Action.WAIT, WAIT, s * 1000L), "started: he waits here " + s
                    + " s, then carries on.");
        }
        if (COME_HERE.equals(tool)) {
            return new Verdict(simple(CuriosityPort.Action.COME_HERE, COME_HERE), "started: he is coming over to "
                    + "them.");
        }
        if (GO_AWAY.equals(tool)) {
            return new Verdict(simple(CuriosityPort.Action.GO_AWAY, GO_AWAY), f.still == null
                    ? "started: he turns away and leaves them alone for 10 min."
                    : "done: he can't drive off (" + f.still + "), so he stays put and leaves them alone for 10 min.");
        }
        if (BE_QUIET.equals(tool)) {
            int min = clamp(intOf(in.get("minutes"), DEFAULT_QUIET_MIN), 1, MAX_QUIET_MIN, DEFAULT_QUIET_MIN);
            return new Verdict(timed(CuriosityPort.Action.BE_QUIET, BE_QUIET, min * 60000L), "started: do not "
                    + "disturb for " + min + " min.");
        }
        if (FIND_PERSON.equals(tool)) {
            String name = text(in.get("name"), MAX_TEXT_CHARS);
            return new Verdict(new Act(CuriosityPort.Action.FIND_PERSON, FIND_PERSON, null, 0, 0, name, null, null,
                    false, null, null), "started: he goes looking for " + (name == null ? "someone new" : name)
                    + " for up to 5 min.");
        }
        if (FIND_THING.equals(tool)) {
            String asked = text(in.get("label"), MAX_TEXT_CHARS);
            String label = known(asked, f);
            if (bathroomLabel(asked)) {
                return Verdict.cant(BATHROOM_THING);
            }
            if (label == null) {
                return Verdict.cant("his detector doesn't know what that looks like" + knownHint(f));
            }
            return new Verdict(new Act(CuriosityPort.Action.FIND_THING, FIND_THING, null, 0, 0, label,
                    Collections.singletonList(label), null, false, null, null), "started: he searches for a " + label
                    + " for up to 5 min and goes over to it when he sees one.");
        }
        if (GO_TO_PLACE.equals(tool)) {
            return checkPlace(in, f);
        }
        if (step && SAY.equals(tool)) {
            String t = text(in.get("text"), MAX_SAY_CHARS);
            if (t == null) {
                return Verdict.cant("a say step needs text");
            }
            return new Verdict(new Act(CuriosityPort.Action.SAY, SAY, null, 0, 0, null, null, t, false, null, null),
                    "ok");
        }
        if (step && ChatTools.LOOK.equals(tool)) {
            return new Verdict(simple(CuriosityPort.Action.LOOK, ChatTools.LOOK), "ok");
        }
        if (step && COME_BACK.equals(tool)) {
            return new Verdict(simple(CuriosityPort.Action.COME_BACK, COME_BACK), "ok");
        }
        return Verdict.cant("there is no " + (step ? "step" : "action") + " called that");
    }

    private static Verdict checkMove(Map<String, Object> in) {
        Object k = in.get("kind");
        String kind = k instanceof String ? ((String) k).trim().toLowerCase(Locale.US) : "";
        double asked = numberOf(in.get("amount"));
        double amount;
        String what;
        boolean capped = false;
        if ("turn_left".equals(kind) || "turn_right".equals(kind)) {
            amount = asked <= 0 ? 90 : Math.min(asked, MAX_TURN_DEG);
            capped = asked > MAX_TURN_DEG;
            what = "turning " + ("turn_left".equals(kind) ? "left " : "right ") + fmt(amount) + " degrees";
        } else if ("turn_around".equals(kind)) {
            amount = 180;
            what = "turning around";
        } else if ("spin".equals(kind)) {
            amount = MAX_TURN_DEG;
            what = "spinning round once";
        } else if ("forward".equals(kind)) {
            amount = asked <= 0 ? 0.5 : Math.min(asked, MAX_FORWARD_M);
            capped = asked > MAX_FORWARD_M;
            what = "driving forward about " + fmt(amount) + " m";
        } else if ("back".equals(kind)) {
            amount = asked <= 0 ? 0.3 : Math.min(asked, MAX_BACK_M);
            capped = asked > MAX_BACK_M;
            what = "backing up about " + fmt(amount) + " m";
        } else {
            return Verdict.cant("he only turns left, right or around, spins, or drives a short way forward or back");
        }
        return new Verdict(new Act(CuriosityPort.Action.MOVE, MOVE, kind, amount, 0, null, null, null, false, null,
                null), "started: " + what + (capped ? " (his most at once)" : "")
                + "; his floor sensor and bump rules still stop him.");
    }

    private static Verdict checkPlace(Map<String, Object> in, CuriosityPort.ToolFacts f) {
        String desc = text(in.get("description"), MAX_TEXT_CHARS);
        List<String> labels = new ArrayList<String>();
        Object raw = in.get("labels");
        boolean bathroomOnly = false;
        if (raw instanceof List) {
            for (Object o : (List<?>) raw) {
                String t = text(o, MAX_TEXT_CHARS);
                String l = known(t, f);
                if (bathroomLabel(t)) {
                    // Review 2026-10-03: never toward a bathroom thing; the place's other labels still count.
                    bathroomOnly = true;
                } else if (l != null && !labels.contains(l) && labels.size() < 6) {
                    labels.add(l);
                }
            }
        }
        if (labels.isEmpty() && bathroomOnly) {
            return Verdict.cant(BATHROOM_THING);
        }
        if (labels.isEmpty()) {
            return new Verdict(null, "don't know where that is: give labels of things his detector would see there"
                    + knownHint(f) + ". Say so honestly in the line.");
        }
        Act act = new Act(CuriosityPort.Action.GO_TO_PLACE, GO_TO_PLACE, null, 0, 0, desc, labels, null, false, null,
                null);
        for (ChatTools.Place p : f.placeList) {
            for (String l : labels) {
                if (p.labels != null && p.labels.contains(l)) {
                    return new Verdict(act, "started: he heads for where he saw a " + l + " " + ChatTools.span(p.agoMs)
                            + " ago, for up to 5 min.");
                }
            }
        }
        return new Verdict(act, "started: he hasn't seen that lately, so he searches through doorways for "
                + join(labels) + " for up to 5 min.");
    }

    private static Verdict checkTask(Map<String, Object> in, CuriosityPort.ToolFacts f) {
        String goal = text(in.get("goal"), 300);
        Object raw = in.get("steps");
        if (!(raw instanceof List) || ((List<?>) raw).isEmpty()) {
            return Verdict.cant("a task needs steps");
        }
        List<?> list = (List<?>) raw;
        if (list.size() > MAX_STEPS) {
            return Verdict.cant("a task has at most " + MAX_STEPS + " steps");
        }
        Verdict parsed = steps(list, f);
        if (!parsed.ok()) {
            return parsed;
        }
        Act task = new Act(CuriosityPort.Action.RUN_TASK, RUN_TASK, null, 0, 0, null, null, null, false,
                parsed.act.steps, goal);
        return started(new Verdict(task, "started: a task of " + task.steps.size() + " steps ("
                + stepTools(task.steps) + "); its own steps say what happened; a call or \"stop\" ends it."));
    }

    /**
     * A task's steps as Acts, every one checked; the first bad step refuses them all, and so
     * does a step that drives while he can't (facts.still: review 2026-10-03, a consult's
     * revised plan included). Answered as a verdict whose act carries the steps (ok) or why not.
     */
    static Verdict steps(List<?> list, CuriosityPort.ToolFacts facts) {
        CuriosityPort.ToolFacts f = facts == null ? CuriosityPort.ToolFacts.NONE : facts;
        List<Act> out = new ArrayList<Act>();
        int i = 0;
        for (Object o : list) {
            i++;
            if (!(o instanceof Map)) {
                return Verdict.cant("step " + i + " is not a step");
            }
            Map<?, ?> m = (Map<?, ?>) o;
            Object t = m.get("tool");
            String tool = t instanceof String ? ((String) t).trim() : "";
            if (!STEP_TOOLS.contains(tool)) {
                return Verdict.cant("step " + i + ": a task can't use " + (tool.isEmpty() ? "that" : tool));
            }
            Map<String, Object> args = new LinkedHashMap<String, Object>();
            if (m.get("args") instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) m.get("args")).entrySet()) {
                    args.put(String.valueOf(e.getKey()), e.getValue());
                }
            }
            Verdict v = checkOne(tool, args, f, true);
            if (!v.ok()) {
                return new Verdict(null, "can't: step " + i + " (" + tool + "): " + v.result.replaceFirst("^can't: ", ""));
            }
            out.add(v.act.checked(Boolean.TRUE.equals(m.get("check"))));
        }
        if (out.size() > MAX_STEPS) {
            return Verdict.cant("a task has at most " + MAX_STEPS + " steps");
        }
        if (f.still != null) {
            for (Act a : out) {
                if (a.drives()) {
                    return Verdict.cant(f.still);
                }
            }
        }
        return new Verdict(new Act(CuriosityPort.Action.RUN_TASK, RUN_TASK, null, 0, 0, null, null, null, false, out,
                null), "ok");
    }

    // ---- helpers ----

    private static Act simple(CuriosityPort.Action a, String tool) {
        return new Act(a, tool, null, 0, 0, null, null, null, false, null, null);
    }

    private static Act timed(CuriosityPort.Action a, String tool, long ms) {
        return new Act(a, tool, null, 0, ms, null, null, null, false, null, null);
    }

    /** What a bathroom thing is told (review 2026-10-03). */
    static final String BATHROOM_THING = "that is a bathroom thing, and he never goes looking for bathrooms";

    /** Whether this label (as Claude wrote it) is one bathroom privacy watches for (ExploreBrain), at any score. */
    static boolean bathroomLabel(String label) {
        String l = plain(label);
        if (l == null) {
            return false;
        }
        for (String s : new String[]{l, l.replaceAll("es$", ""), l.replaceAll("s$", "")}) {
            if (ExploreBrain.BATHROOM_STRONG.contains(s) || ExploreBrain.BATHROOM_WEAK.contains(s)) {
                return true;
            }
        }
        return false;
    }

    /** Lower case, spaces collapsed, a leading article dropped; null when nothing is left. */
    private static String plain(String label) {
        if (label == null) {
            return null;
        }
        String l = label.trim().toLowerCase(Locale.US).replaceAll("\\s+", " ");
        if (l.startsWith("a ")) {
            l = l.substring(2);
        } else if (l.startsWith("an ")) {
            l = l.substring(3);
        } else if (l.startsWith("the ")) {
            l = l.substring(4);
        }
        return l.isEmpty() ? null : l;
    }

    /** The label as his detector names it (lower case; a plural's singular), or null when it isn't one. */
    static String known(String label, CuriosityPort.ToolFacts f) {
        String l = plain(label);
        if (l == null) {
            return null;
        }
        if (f.vocabulary.isEmpty() || f.vocabulary.contains(l)) {
            return l;
        }
        for (String s : new String[]{l.replaceAll("es$", ""), l.replaceAll("s$", "")}) {
            if (f.vocabulary.contains(s)) {
                return s;
            }
        }
        return null;
    }

    private static String knownHint(CuriosityPort.ToolFacts f) {
        if (f.vocabulary.isEmpty()) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        int n = 0;
        for (String w : f.vocabulary) {
            if (bathroomLabel(w)) {
                continue;
            }
            if (n++ >= 40) {
                b.append(", ...");
                break;
            }
            b.append(b.length() == 0 ? "" : ", ").append(w);
        }
        return " (it knows: " + b + ")";
    }

    private static String text(Object v, int max) {
        if (!(v instanceof String)) {
            return null;
        }
        String t = ((String) v).replaceAll("[\\s\\p{Cntrl}]+", " ").trim();
        if (t.isEmpty()) {
            return null;
        }
        return t.length() > max ? t.substring(0, max).trim() : t;
    }

    private static int intOf(Object v, int dflt) {
        return v instanceof Number ? (int) Math.round(((Number) v).doubleValue()) : dflt;
    }

    private static double numberOf(Object v) {
        return v instanceof Number ? ((Number) v).doubleValue() : 0;
    }

    private static int clamp(int v, int lo, int hi, int dflt) {
        if (v <= 0) {
            return dflt;
        }
        return Math.max(lo, Math.min(hi, v));
    }

    private static String join(List<String> items) {
        StringBuilder b = new StringBuilder();
        for (String s : items) {
            b.append(b.length() == 0 ? "" : ", ").append(s);
        }
        return b.toString();
    }
}
