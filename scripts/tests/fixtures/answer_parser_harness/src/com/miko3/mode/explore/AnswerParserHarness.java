package com.miko3.mode.explore;

import com.miko3.shared.NameExtractor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Host-JVM checks for AnswerParser and NameResolver (face plan U7; KTD6, KTD9,
 * KTD10), driven by scripts/tests/test_answer_parser.py. The parser runs with
 * the robot's own NameExtractor, as ClaudeCuriosity wires it.
 *
 * Prints one "PASS <name>" or "FAIL <name>: <detail>" line per case.
 */
public final class AnswerParserHarness {
    private static int failures;

    private static final AnswerParser.Names EXTRACTOR = new AnswerParser.Names() {
        @Override
        public String nameIn(String transcript) {
            return NameExtractor.extract(transcript);
        }
    };

    /** {case, reply, name asked, expected kind, expected name or null}. */
    private static final String[][] REPLIES = {
        {"yes_is_yes", "yes", "Ben", "YES", null},
        {"yeah_thats_me_is_yes", "yeah that's me", "Ben", "YES", null},
        {"its_me_is_yes", "it's me", "Ben", "YES", null},
        {"recognizer_its_me_is_yes", "IT'S ME", "Ben", "YES", null},
        {"yep_is_yes", "yep", "Ben", "YES", null},
        {"correct_is_yes", "correct", "Ben Wilson", "YES", null},
        {"name_asked_is_yes", "Ben", "Ben", "YES", null},
        {"im_name_asked_is_yes", "I'm Ben", "ben", "YES", null},
        {"full_name_asked_full_is_yes", "Ben Wilson", "Ben Wilson", "YES", null},
        {"first_name_when_asked_full_is_unclear", "Ben", "Ben Wilson", "UNCLEAR", null},
        {"no_im_sarah_is_no_with_name", "no I'm Sarah", "Ben", "NO_WITH_NAME", "Sarah"},
        {"no_comma_im_sarah_is_no_with_name", "No, I'm Sarah", "Ben", "NO_WITH_NAME", "Sarah"},
        {"im_sarah_is_no_with_name", "I'm Sarah", "Ben", "NO_WITH_NAME", "Sarah"},
        {"bare_other_name_is_no_with_name", "Sarah", "Ben", "NO_WITH_NAME", "Sarah"},
        {"nope_its_priya_is_no_with_name", "nope it's Priya", "Ben", "NO_WITH_NAME", "Priya"},
        {"other_full_name_is_no_with_name", "Ben Smith", "Ben", "NO_WITH_NAME", "Ben Smith"},
        {"nope_is_no", "nope", "Ben", "NO", null},
        {"no_is_no", "no", "Ben", "NO", null},
        {"not_me_is_no", "not me", "Ben", "NO", null},
        {"thats_not_me_is_no", "that's not me", "Ben", "NO", null},
        {"nah_is_no", "nah", "Ben", "NO", null},
        {"wrong_is_no", "wrong", "Ben", "NO", null},
        {"whos_ben_is_unclear", "who's Ben?", "Ben", "UNCLEAR", null},
        {"recognizer_whos_ben_is_unclear", "WHO'S BEN", "Ben", "UNCLEAR", null},
        {"empty_is_unclear", "", "Ben", "UNCLEAR", null},
        {"null_is_unclear", null, "Ben", "UNCLEAR", null},
        {"maybe_is_unclear", "maybe", "Ben", "UNCLEAR", null},
        {"sentence_is_unclear", "the coffee machine is broken", "Ben", "UNCLEAR", null},
    };

    /** {case, reply, pending first name, expected last name or null}. */
    private static final String[][] LAST_NAMES = {
        {"last_one_word_is_the_last_name", "Smith", "Ben", "Smith"},
        {"last_its_wilson", "it's Wilson", "Ben", "Wilson"},
        {"last_full_name_starting_with_first", "Ben Smith", "Ben", "Smith"},
        {"last_full_name_other_first_is_none", "Sam Smith", "Ben", null},
        {"last_first_name_again_is_none", "Ben", "Ben", null},
        {"last_nothing_is_none", "", "Ben", null},
        {"last_no_name_is_none", "why do you want to know", "Ben", null},
    };

    public static void main(String[] args) {
        for (String[] c : REPLIES) {
            AnswerParser.Reply r = AnswerParser.parse(c[1], c[2], EXTRACTOR);
            boolean ok = r != null && r.kind.name().equals(c[3])
                    && (c[4] == null ? r.name == null : c[4].equals(r.name));
            check(c[0], ok, "got " + r);
        }
        for (String[] c : LAST_NAMES) {
            String got = AnswerParser.lastName(c[1], c[2], EXTRACTOR);
            check(c[0], c[3] == null ? got == null : c[3].equals(got), "got " + got);
        }
        check("unclear_counts_as_no", !AnswerParser.parse("maybe", "Ben", EXTRACTOR).yes()
                && !AnswerParser.parse("", "Ben", EXTRACTOR).yes(), "");
        resolverCases();
        System.exit(failures == 0 ? 0 : 1);
    }

    // ---- NameResolver (KTD10) ----

    private static float[] unit(float... v) {
        return FaceMatcher.normalize(v);
    }

    private static void resolverCases() {
        float close = 0.363f;
        float[] probe = unit(1f, 0f, 0f);
        float[] near = unit(1f, 0.5f, 0f);    // cos 0.894: close
        float[] far = unit(0f, 1f, 0f);       // cos 0: weak
        List<FaceMatcher.Entry> gallery = Arrays.asList(
                new FaceMatcher.Entry("p-sarah", 0, far),
                new FaceMatcher.Entry("p-sarah", 1, near),
                new FaceMatcher.Entry("p-ben", 0, far));

        NameResolver.Decision none = NameResolver.resolve("Priya", probe, Collections.<String>emptyList(), gallery,
                close);
        check("resolve_no_stored_match_is_new", none.kind == NameResolver.Kind.NEW && "Priya".equals(none.name)
                && none.personId == null, "got " + none);

        NameResolver.Decision join = NameResolver.resolve("Sarah", probe, Arrays.asList("p-sarah"), gallery, close);
        check("resolve_close_to_any_photo_joins", join.kind == NameResolver.Kind.JOIN
                && "p-sarah".equals(join.personId), "got " + join);

        NameResolver.Decision ask = NameResolver.resolve("Ben", probe, Arrays.asList("p-ben"), gallery, close);
        check("resolve_weak_asks_the_last_name", ask.kind == NameResolver.Kind.ASK_LAST_NAME
                && ask.personId == null && "Ben".equals(ask.name), "got " + ask);

        NameResolver.Decision edge = NameResolver.resolve("Ben", probe, Arrays.asList("p-ben"),
                Arrays.asList(new FaceMatcher.Entry("p-ben", 0, unit(0.363f, (float) Math.sqrt(1 - 0.363 * 0.363), 0f))),
                close);
        check("resolve_close_edge_is_inclusive", edge.kind == NameResolver.Kind.JOIN, "got " + edge);

        NameResolver.Decision best = NameResolver.resolve("Ben", probe, Arrays.asList("p-ben", "p-ben2"),
                Arrays.asList(new FaceMatcher.Entry("p-ben", 0, far), new FaceMatcher.Entry("p-ben2", 0, near)), close);
        check("resolve_takes_the_best_matching_id", best.kind == NameResolver.Kind.JOIN
                && "p-ben2".equals(best.personId), "got " + best);

        NameResolver.Decision full = NameResolver.resolve("Ben Wilson", probe, Arrays.asList("p-ben"), gallery,
                close);
        check("resolve_full_name_weak_joins_that_full_name", full.kind == NameResolver.Kind.JOIN
                && "p-ben".equals(full.personId), "got " + full);

        NameResolver.Decision faceless = NameResolver.resolve("Ben", null, Arrays.asList("p-ben"), gallery, close);
        check("resolve_without_a_probe_asks_the_last_name", faceless.kind == NameResolver.Kind.ASK_LAST_NAME,
                "got " + faceless);

        Map<String, String> stored = new LinkedHashMap<String, String>();
        stored.put("p-ben", "Ben Wilson");
        stored.put("p-ben1", "Ben");
        NameResolver.Decision smith = NameResolver.afterLastName("Ben", "Smith", stored);
        check("last_name_ae4_smith_is_a_new_full_name", smith.kind == NameResolver.Kind.NEW
                && "Ben Smith".equals(smith.name), "got " + smith);
        NameResolver.Decision wilson = NameResolver.afterLastName("Ben", "wilson", stored);
        check("last_name_ae4_wilson_joins_ben_wilson", wilson.kind == NameResolver.Kind.JOIN
                && "p-ben".equals(wilson.personId), "got " + wilson);
        Map<String, String> onlyBen = new LinkedHashMap<String, String>();
        onlyBen.put("p-ben1", "Ben");
        NameResolver.Decision dup = NameResolver.afterLastName("Ben", "Smith", onlyBen);
        check("last_name_stored_without_a_last_name_is_new", dup.kind == NameResolver.Kind.NEW
                && "Ben Smith".equals(dup.name), "got " + dup);

        check("asked_name_first_word_when_unique", "Ben".equals(NameResolver.askedName("Ben Wilson", 1)),
                NameResolver.askedName("Ben Wilson", 1));
        check("asked_name_full_when_another_shares_the_first_name",
                "Ben Wilson".equals(NameResolver.askedName("Ben Wilson", 2)), NameResolver.askedName("Ben Wilson", 2));
        check("asked_name_one_word_stored", "Priya".equals(NameResolver.askedName("Priya", 3)),
                NameResolver.askedName("Priya", 3));
        check("asked_name_blank_is_none", NameResolver.askedName("  ", 1) == null, "");

        List<String> words = new ArrayList<String>();
        check("decision_to_string_carries_no_name", !NameResolver.resolve("Sarah", probe, Arrays.asList("p-sarah"),
                gallery, close).toString().contains("Sarah") && !smith.toString().contains("Smith"), words.toString());
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            System.out.println("PASS " + name);
        } else {
            failures++;
            System.out.println("FAIL " + name + ": " + detail);
        }
    }
}
