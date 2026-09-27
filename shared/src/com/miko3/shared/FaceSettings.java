package com.miko3.shared;

/**
 * The face thresholds the launcher's settings service tells Explore (face
 * plan U5, KTD5; R2, R17): the match bands, the near-tie margin and the
 * gates a crop must pass before it is matched (KTD3). The launcher stores
 * them beside the Claude settings and checks every save; the mode reads
 * them at each meeting, so an edit applies from the next meeting with no
 * rebuild.
 *
 * Bands are inclusive at their lower edge: a score of at least confident is
 * confident, at least close is close, and anything lower is weak. Plain Java
 * so host tests can build and inspect it; RobotSettings carries it across
 * Binder.
 */
public final class FaceSettings {
    /** Starting values until the bench (U9) fits them to office crops. close is
     * SFace's published 1:1 threshold. */
    public static final FaceSettings DEFAULTS = new FaceSettings(0.50f, 0.363f, 0.05f, 48, 40, 90, 30);

    /** Lowest cosine score greeted by name. */
    public final float confident;
    /** Lowest cosine score asked "Is that you?"; below it the face is weak. */
    public final float close;
    /** R17: two different people this close demote a confident result to close. */
    public final float margin;
    /** Minimum face box width, frame pixels. */
    public final int minWidth;
    /** Mean luma of the aligned crop below which it is too dark. */
    public final double darkFloor;
    /** Mean luma below which a usable crop is brightened. */
    public final double dimLevel;
    /** Laplacian variance of the aligned grey crop below which it is too blurry. */
    public final double blurFloor;

    public FaceSettings(float confident, float close, float margin, int minWidth, double darkFloor, double dimLevel,
                        double blurFloor) {
        this.confident = confident;
        this.close = close;
        this.margin = margin;
        this.minWidth = minWidth;
        this.darkFloor = darkFloor;
        this.dimLevel = dimLevel;
        this.blurFloor = blurFloor;
    }

    @Override
    public String toString() {
        return "FaceSettings{confident=" + confident + ", close=" + close + ", margin=" + margin + ", minWidth="
                + minWidth + ", darkFloor=" + darkFloor + ", dimLevel=" + dimLevel + ", blurFloor=" + blurFloor + "}";
    }
}
