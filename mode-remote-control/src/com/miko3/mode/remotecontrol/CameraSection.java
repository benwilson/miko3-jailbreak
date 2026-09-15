package com.miko3.mode.remotecontrol;

/**
 * The camera-view section of the served page (R4/R11, KTD4/KTD5): an
 * <img> subresource pointed at the MJPEG stream — never a top-level
 * navigation, since browsers dropped multipart support for main-frame
 * navigation in 2013 but subresource usage remains standard. The onerror
 * handler surfaces a camera failure (permission denied, device busy) as
 * visible text rather than leaving a silently broken image.
 *
 * U19m (2026-09-15): also lays out the operator-video controls (the "Operator
 * video -> robot screen" checkbox, its on-robot display <img>, and the
 * capturing browser's own self-preview <video>) side by side with the robot
 * camera feed, at the operator's request — two feeds next to each other read
 * far better than one on top of the other, several sections down. Only the
 * HTML markup moved here; ToggleSection.java's own <script> still owns all
 * the actual start/stop/getUserMedia logic for these elements unchanged,
 * since it only ever looks them up by id — where an element is rendered in
 * the DOM doesn't matter to a getElementById() call, and ToggleSection.HTML
 * (which contains that script) is always appended after this section, so
 * these ids already exist in the DOM by the time that script runs.
 * operator-video-canvas (the hidden JPEG-encoding scratch canvas) has no
 * visible layout, so it stayed in ToggleSection.java.
 */
final class CameraSection {
    private CameraSection() {
    }

    // Both feeds share this exact box (same width/height, object-fit:cover) so they
    // read as one matched pair side by side, regardless of each source's own actual
    // resolution/aspect ratio (the robot camera is 640x480; a laptop's own webcam is
    // typically a different, wider ratio) — max-width:100% alone (the original rule)
    // still let each <img> size itself off its own natural aspect ratio, so the two
    // ended up visibly different sizes next to each other.
    private static final String MEDIA_BOX_STYLE =
            "width:100%;height:40vmin;object-fit:cover;border-radius:6px;background:#000";

    static final String HTML =
            "<section id=\"camera\">"
            + "<h2>Camera</h2>"
            + "<div style=\"display:flex;gap:1rem;flex-wrap:wrap;align-items:flex-start\">"
            + "<div style=\"flex:1;min-width:220px\">"
            + "<p><small>Robot camera</small></p>"
            + "<img id=\"camera-img\" src=\"/stream.mjpeg\" alt=\"robot camera view\" style=\"" + MEDIA_BOX_STYLE + "\""
            + " onerror=\"document.getElementById('camera-error').hidden=false;\">"
            + "<p id=\"camera-error\" hidden>Camera view unavailable — check the robot's screen for details.</p>"
            + "</div>"
            + "<div style=\"flex:1;min-width:220px\">"
            + "<label><input type=\"checkbox\" id=\"toggle-operator-video\">Operator video &rarr; robot screen</label>"
            + "<img id=\"operator-video-img\" hidden alt=\"operator video, as shown on the robot's screen\""
            + " style=\"" + MEDIA_BOX_STYLE + "\">"
            + "<video id=\"operator-video-preview\" hidden autoplay playsinline muted"
            + " style=\"" + MEDIA_BOX_STYLE + "\"></video>"
            + "</div>"
            + "</div>"
            + "</section>";
}
