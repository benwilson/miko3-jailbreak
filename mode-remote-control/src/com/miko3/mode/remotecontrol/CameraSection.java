package com.miko3.mode.remotecontrol;

/**
 * The camera-view section of the served page (R4/R11, KTD4/KTD5): an
 * <img> subresource pointed at the MJPEG stream — never a top-level
 * navigation, since browsers dropped multipart support for main-frame
 * navigation in 2013 but subresource usage remains standard. The onerror
 * handler surfaces a camera failure (permission denied, device busy) as
 * visible text rather than leaving a silently broken image.
 */
final class CameraSection {
    private CameraSection() {
    }

    static final String HTML =
            "<section id=\"camera\">"
            + "<h2>Camera</h2>"
            + "<img id=\"camera-img\" src=\"/stream.mjpeg\" alt=\"robot camera view\" style=\"max-width:100%\""
            + " onerror=\"document.getElementById('camera-error').hidden=false;\">"
            + "<p id=\"camera-error\" hidden>Camera view unavailable — check the robot's screen for details.</p>"
            + "</section>";
}
