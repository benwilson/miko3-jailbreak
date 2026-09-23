package com.miko3.mode.explore;

/**
 * One thing a recognizer found in a camera frame (KTD1): a vocabulary name, a
 * confidence, and its box as fractions of the frame (0,0 top-left to 1,1
 * bottom-right). This is the only shape the brain and Sighting see, so a
 * different recognizer (a relay-backed one, R17) can produce it too.
 *
 * Plain Java so it runs on the host JVM.
 */
final class Detection {
    final String label;
    final float score;
    final float x0;
    final float y0;
    final float x1;
    final float y1;

    Detection(String label, float score, float x0, float y0, float x1, float y1) {
        this.label = label;
        this.score = score;
        this.x0 = clamp(Math.min(x0, x1));
        this.y0 = clamp(Math.min(y0, y1));
        this.x1 = clamp(Math.max(x0, x1));
        this.y1 = clamp(Math.max(y0, y1));
    }

    float width() {
        return x1 - x0;
    }

    float height() {
        return y1 - y0;
    }

    float area() {
        return width() * height();
    }

    /** Horizontal centre, -1 (left edge) to 1 (right edge): the gaze and steering signal. */
    float centerX() {
        return (x0 + x1) - 1f;
    }

    /** The asset that says a vocabulary name: name-<slug>.webm, where the slug is
     * the name lowercased with every run of other characters as one dash
     * ("rubik's cube" -> name-rubik-s-cube.webm). Must match slug() in
     * scripts/gen-explore-voice.py. */
    static String nameClip(String label) {
        StringBuilder out = new StringBuilder("name-");
        int start = out.length();
        boolean dash = false;
        for (char ch : label.toLowerCase(java.util.Locale.US).toCharArray()) {
            if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')) {
                if (dash && out.length() > start) {
                    out.append('-');
                }
                out.append(ch);
                dash = false;
            } else {
                dash = true;
            }
        }
        return out.append(".webm").toString();
    }

    private static float clamp(float v) {
        return Float.isNaN(v) ? 0f : Math.max(0f, Math.min(1f, v));
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.US, "%s %.2f [%.2f,%.2f,%.2f,%.2f]", label, score, x0, y0, x1, y1);
    }
}
