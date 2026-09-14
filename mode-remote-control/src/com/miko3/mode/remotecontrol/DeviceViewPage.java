package com.miko3.mode.remotecontrol;

/**
 * What the robot's own on-device screen shows while this mode is active —
 * deliberately just two states, an idle "eyes" face or the operator's video
 * (U13): a full interactive control page (drive buttons, the robot's own
 * camera preview) makes no sense on the robot's own screen — nobody drives a
 * robot by pressing buttons on the robot itself, and showing the robot's own
 * camera back to itself is pointless. No dedicated face asset exists in this
 * project, so the idle state is drawn with plain CSS rather than shipping a
 * new image. The <img> stays hidden until its first "load" event — which a
 * multipart/x-mixed-replace stream (ModeApp.operatorVideoBroadcaster) fires
 * on every part, so the very first frame the operator actually uploads
 * swaps eyes for video automatically, with nothing to configure on this
 * side; if nobody ever uploads one, the connection just sits open with zero
 * parts and the eyes stay up indefinitely.
 */
final class DeviceViewPage {
    private DeviceViewPage() {
    }

    static final String HTML =
            "<!doctype html><html><head><meta charset=\"utf-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
            + "<title>Remote Control / Telepresence</title>"
            + "<style>"
            + "html,body{margin:0;height:100%;background:#000;overflow:hidden}"
            + "#eyes{width:100%;height:100%;display:flex;align-items:center;justify-content:center}"
            + "@keyframes blink{0%,96%,100%{transform:scaleY(1)}98%{transform:scaleY(0.1)}}"
            // Explicit vmin width+height rather than width+aspect-ratio, and margin
            // rather than the flex container's gap, for spacing between the two:
            // this page renders inside a low-level system WebView on an Android 9
            // device, old enough that neither aspect-ratio nor flexbox gap is a safe
            // bet — confirmed live, both silently no-op here (zero-height eyes with
            // aspect-ratio; touching eyes with gap unchanged across several values).
            + ".eye{width:16vmin;height:16vmin;margin:0 7vmin;border-radius:50%;background:#4fc3ff;"
            + "box-shadow:0 0 6vmin #1c8fd6;animation:blink 5s infinite}"
            + ".eye:nth-child(2){animation-delay:0.15s}"
            + "#video{display:none;width:100%;height:100%;object-fit:contain}"
            + "</style>"
            + "</head><body>"
            + "<div id=\"eyes\"><div class=\"eye\"></div><div class=\"eye\"></div></div>"
            + "<img id=\"video\" alt=\"\">"
            + "<script>"
            + "var img=document.getElementById('video');"
            + "var eyes=document.getElementById('eyes');"
            + "img.addEventListener('load',function(){eyes.style.display='none';img.style.display='block';});"
            + "img.src='/operator-video-stream';"
            + "</script>"
            + "</body></html>";
}
