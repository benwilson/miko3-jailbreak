package com.miko3.mode.explore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Host harness for Sighting (camera curiosity, KTD6): one PASS/FAIL line per scenario. */
public final class SightingHarness {
    private static final ExploreTuning T = new ExploreTuning.Builder()
            .recognition(0.35f, 0.2f).fill(0.7f, 0.4f).build();

    public static void main(String[] args) {
        check("largest_confident_box_wins", largestWins());
        check("tie_goes_to_more_centred", tieCentred());
        check("low_scores_only_is_unsure", unsure());
        check("no_boxes_is_nothing", nothing());
        check("below_unsure_floor_is_nothing", belowUnsure());
        check("confident_small_beats_unsure_large", confidentBeatsUnsure());
        check("background_is_never_a_target", background());
        check("cooldown_skips_people_and_pets", cooldown());
        check("people_and_pets_set", peopleAndPets());
        check("tall_box_fills_frame", tallFills());
        check("wide_large_box_fills_frame", areaFills());
        check("small_central_box_does_not_fill", smallDoesNot());
        if (args.length > 0) {
            System.out.println("PEOPLE_AND_PETS " + String.join("|", new java.util.TreeSet<String>(Sighting.PEOPLE_AND_PETS)));
            System.out.println("BACKGROUND " + String.join("|", new java.util.TreeSet<String>(Sighting.BACKGROUND)));
        }
    }

    private static Detection d(String label, float score, float x0, float y0, float x1, float y1) {
        return new Detection(label, score, x0, y0, x1, y1);
    }

    private static Sighting choose(boolean cooldown, Detection... ds) {
        return Sighting.choose(new ArrayList<Detection>(Arrays.asList(ds)), T, cooldown);
    }

    private static String largestWins() {
        Sighting s = choose(false, d("cup", 0.9f, 0.4f, 0.4f, 0.5f, 0.5f), d("plant", 0.5f, 0.1f, 0.1f, 0.6f, 0.9f));
        return s.kind == Sighting.Kind.THING && s.target.label.equals("plant") ? null : "got " + s;
    }

    private static String tieCentred() {
        Sighting s = choose(false, d("cup", 0.6f, 0.0f, 0.4f, 0.2f, 0.6f), d("mug", 0.6f, 0.4f, 0.4f, 0.6f, 0.6f));
        return s.target != null && s.target.label.equals("mug") ? null : "got " + s;
    }

    private static String unsure() {
        Sighting s = choose(false, d("cup", 0.25f, 0, 0, 0.1f, 0.1f), d("vase", 0.3f, 0, 0, 0.05f, 0.05f));
        return s.kind == Sighting.Kind.UNSURE && s.target.label.equals("vase") ? null : "got " + s;
    }

    private static String nothing() {
        Sighting s = choose(false);
        return s.kind == Sighting.Kind.NOTHING && s.target == null ? null : "got " + s;
    }

    private static String belowUnsure() {
        Sighting s = choose(false, d("cup", 0.1f, 0, 0, 0.5f, 0.5f));
        return s.kind == Sighting.Kind.NOTHING ? null : "got " + s;
    }

    private static String confidentBeatsUnsure() {
        Sighting s = choose(false, d("cup", 0.5f, 0.45f, 0.45f, 0.5f, 0.5f), d("vase", 0.3f, 0, 0, 1, 1));
        return s.kind == Sighting.Kind.THING && s.target.label.equals("cup") ? null : "got " + s;
    }

    private static String background() {
        Sighting s = choose(false, d("rug", 0.9f, 0, 0.6f, 1, 1), d("cup", 0.4f, 0.4f, 0.4f, 0.5f, 0.5f));
        if (s.target == null || !s.target.label.equals("cup")) return "rug chosen: " + s;
        Sighting only = choose(false, d("carpet", 0.9f, 0, 0.6f, 1, 1));
        return only.kind == Sighting.Kind.NOTHING ? null : "carpet alone gave " + only;
    }

    private static String cooldown() {
        Detection cat = d("cat", 0.9f, 0.1f, 0.1f, 0.9f, 0.9f);
        Detection cup = d("cup", 0.5f, 0.4f, 0.4f, 0.5f, 0.5f);
        Sighting warm = choose(false, cat, cup);
        Sighting cool = choose(true, cat, cup);
        if (!warm.isPersonOrPet()) return "without cool-down the cat should win: " + warm;
        return cool.target != null && cool.target.label.equals("cup") && !cool.isPersonOrPet() ? null : "got " + cool;
    }

    private static String peopleAndPets() {
        for (String yes : new String[]{"person", "cat", "dog", "bird", "guinea pig"}) {
            if (!Sighting.isPersonOrPet(yes)) return yes + " should be a person/pet";
        }
        for (String no : new String[]{"teddy bear", "stuffed animal", "plant", "toy dinosaur", "dog toy"}) {
            if (Sighting.isPersonOrPet(no)) return no + " should be an object";
        }
        return null;
    }

    private static String tallFills() {
        return Sighting.fillsFrame(d("plant", 0.9f, 0.4f, 0.2f, 0.55f, 0.95f), T) ? null : "75% tall should fill";
    }

    private static String areaFills() {
        return Sighting.fillsFrame(d("box", 0.9f, 0.0f, 0.5f, 0.9f, 0.99f), T) ? null : "0.44 area should fill";
    }

    private static String smallDoesNot() {
        return Sighting.fillsFrame(d("cup", 0.9f, 0.4f, 0.4f, 0.6f, 0.6f), T) ? "small box filled" : null;
    }

    private static void check(String name, String failure) {
        System.out.println(failure == null ? "PASS " + name : "FAIL " + name + ": " + failure);
    }
}
