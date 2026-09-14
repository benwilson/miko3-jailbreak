package com.miko3.mode.remotecontrol;

/**
 * What the robot's own on-device screen shows while this mode is active —
 * deliberately just two states, blank or the operator's video (U13): a full
 * interactive control page (drive buttons, the robot's own camera preview)
 * makes no sense on the robot's own screen — nobody drives a robot by
 * pressing buttons on the robot itself, and showing the robot's own camera
 * back to the robot is pointless. Static markup, no JS: the <img> just sits
 * subscribed to /operator-video-stream (ModeApp.operatorVideoBroadcaster) —
 * blank (this page's own black background shows through) until a remote
 * operator's browser is actually uploading frames via the "Operator video"
 * toggle, then it renders automatically the moment frames start arriving,
 * with no toggle or reload needed on this side.
 */
final class DeviceViewPage {
    private DeviceViewPage() {
    }

    static final String HTML =
            "<!doctype html><html><head><meta charset=\"utf-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
            + "<title>Remote Control / Telepresence</title>"
            + "<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}"
            + "img{display:block;width:100%;height:100%;object-fit:contain}</style>"
            + "</head><body>"
            + "<img src=\"/operator-video-stream\" alt=\"\">"
            + "</body></html>";
}
