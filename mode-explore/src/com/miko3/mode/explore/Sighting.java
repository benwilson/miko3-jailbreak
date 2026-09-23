package com.miko3.mode.explore;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What one look saw, reduced to the one thing the brain acts on (KTD1, KTD6):
 * the most prominent confident detection, or an unsure one when only
 * low-scoring boxes exist (R13), or nothing.
 *
 * Plain Java so it runs on the host JVM (scripts/tests/test_explore_sighting.py).
 */
final class Sighting {
    enum Kind { NOTHING, UNSURE, THING }

    /** The vocabulary's first section (assets/vocabulary.txt): always delighted (R11). */
    static final Set<String> PEOPLE_AND_PETS = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            "person", "baby", "cat", "dog", "puppy", "kitten", "bird", "parrot", "hamster", "rabbit",
            "guinea pig", "fish", "turtle", "lizard")));

    /**
     * Surfaces he stands on or sees past, never a thing to investigate: from the
     * floor a rug or mat fills the bottom of nearly every frame, and he cannot
     * drive up to a window.
     */
    static final Set<String> BACKGROUND = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            "rug", "carpet", "mat", "doormat", "stairs", "window", "curtain", "blinds")));

    static final Sighting NOTHING = new Sighting(Kind.NOTHING, null);

    final Kind kind;
    /** The chosen detection; null for NOTHING. For UNSURE, the best of the low-scoring boxes. */
    final Detection target;

    private Sighting(Kind kind, Detection target) {
        this.kind = kind;
        this.target = target;
    }

    /**
     * Pick from one look's detections. Background names are skipped, and so are
     * people and pets while ignorePeopleAndPets (the cool-down, R12). Prominent
     * is the largest box at or above the confidence floor, a tie going to the
     * more centred box; with none, the best box at or above the unsure floor
     * makes the look UNSURE.
     */
    static Sighting choose(List<Detection> detections, ExploreTuning t, boolean ignorePeopleAndPets) {
        Detection best = null;
        Detection unsure = null;
        for (Detection d : detections) {
            if (BACKGROUND.contains(d.label) || (ignorePeopleAndPets && isPersonOrPet(d.label))) {
                continue;
            }
            if (d.score >= t.confidenceFloor) {
                if (best == null || moreProminent(d, best)) {
                    best = d;
                }
            } else if (d.score >= t.unsureFloor && (unsure == null || d.score > unsure.score)) {
                unsure = d;
            }
        }
        if (best != null) {
            return new Sighting(Kind.THING, best);
        }
        return unsure != null ? new Sighting(Kind.UNSURE, unsure) : NOTHING;
    }

    private static boolean moreProminent(Detection a, Detection b) {
        float diff = a.area() - b.area();
        if (Math.abs(diff) > 1e-4f) {
            return diff > 0;
        }
        return Math.abs(a.centerX()) < Math.abs(b.centerX());
    }

    static boolean isPersonOrPet(String label) {
        return PEOPLE_AND_PETS.contains(label);
    }

    boolean isPersonOrPet() {
        return target != null && isPersonOrPet(target.label);
    }

    /** Close enough by the camera (R6): the box is most of the frame's height or area. */
    static boolean fillsFrame(Detection d, ExploreTuning t) {
        return d.height() >= t.fillHeight || d.area() >= t.fillArea;
    }

    @Override
    public String toString() {
        return kind + (target == null ? "" : " " + target);
    }
}
