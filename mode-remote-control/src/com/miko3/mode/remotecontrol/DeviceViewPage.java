package com.miko3.mode.remotecontrol;

import com.miko3.shared.EyesPage;

/**
 * What the robot's own on-device screen shows while this mode is active —
 * deliberately just two states, an idle "eye" or the operator's video (U13):
 * a full interactive control page (drive buttons, the robot's own camera
 * preview) makes no sense on the robot's own screen — nobody drives a robot
 * by pressing buttons on the robot itself, and showing the robot's own
 * camera back to itself is pointless.
 *
 * U7: the eyes themselves (the U20 HAL-style lenses, blink, and gaze) now
 * live in the shared EyesPage builder, which the voice mode's device view
 * also uses. This page adds only the operator-video slot and its poll. The
 * composed page must stay byte-for-byte what this mode served before U7
 * (scripts/tests/test_eyes_page_golden.py), so a change to the eyes meant for
 * the voice mode alone belongs in that mode's own CSS/script, not EyesPage.
 *
 * The <img> stays hidden until its first "load" event — which a
 * multipart/x-mixed-replace stream (ModeApp.operatorVideoBroadcaster) fires
 * on every part, so the very first frame the operator actually uploads
 * swaps the eye for video automatically.
 *
 * U19n (2026-09-15): polls ModeApp's own /operator-video-active status every
 * second to know when to (re)connect the <img> or switch back to the eyes,
 * rather than driving that off the connection's own lifecycle (a fresh src on
 * poll-detected start, display swapped back and src cleared on poll-detected
 * stop) — confirmed live that this WebView's <img> does NOT reliably fire an
 * "error" event when ModeApp actively closes the underlying multipart
 * connection server-side (see SubscriberBroadcaster.disconnectAll()), so
 * relying on that event left this page stuck showing the operator's last
 * frame forever after they turned video off, with no client-side signal at
 * all that anything had changed. Do not go back to a load/error-event-only
 * design without confirming live that this specific WebView's multipart
 * <img> handling has changed.
 *
 * U19v (2026-09-15): the active/inactive poll alone still wasn't enough --
 * confirmed live the underlying connection can die silently WHILE the
 * operator keeps uploading the entire time (server logs showed continuous
 * /operator-video-upload traffic while this page sat on one frozen frame,
 * byte-identical across screenshots seconds apart), so the true/true case
 * never re-triggers a reconnect on its own. Now also tracks the last time
 * the <img>'s own "load" event fired (still reliable per-part, only "error"
 * on close is the unreliable one) and forces a fresh connection if none
 * fired in 5s despite the poll still reporting the operator active.
 */
final class DeviceViewPage {
    private DeviceViewPage() {
    }

    static final String HTML = EyesPage.build(
            "Remote Control / Telepresence",
            "#video{display:none;width:100%;height:100%;object-fit:contain}",
            "<img id=\"video\" alt=\"\">",
            // The operator-video slot: an <img> swapped in over the eyes, and its poll.
            "var img=document.getElementById('video');"
            + "var rig=document.getElementById('rig');"
            + "var lastFrameTime=0;"
            + "img.addEventListener('load',function(){"
            + "rig.style.display='none';img.style.display='block';lastFrameTime=Date.now();"
            + "});"
            // U19m/U19n: polls /operator-video-active (ModeApp's own operatorVideoActive
            // flag, set true on each /operator-video-upload and false on
            // /operator-video-stop) rather than relying on this <img>'s own "error" event
            // to notice the operator stopped -- confirmed live that closing the
            // connection server-side (SubscriberBroadcaster.disconnectAll(), called from
            // /operator-video-stop) does NOT reliably fire "error" on this WebView's
            // multipart <img> handling (same non-standard handling already noted for
            // "load" firing per-part, not just once): a real live test left this page
            // stuck showing the last frame indefinitely with zero client-side signal at
            // all that the connection had even closed. Polling a plain status endpoint
            // sidesteps that WebView-specific quirk entirely. wasActive tracks the
            // previous poll's result so img.src is only touched on an actual transition,
            // not on every poll (reassigning the same src is a wasted request at best).
            //
            // U19v (2026-09-15): that transition-only reconnect isn't enough on its own
            // -- confirmed live the underlying multipart connection can also die
            // SILENTLY while the operator is still actively uploading the whole time
            // (operatorVideoActive stays true throughout, so the false->true edge this
            // was built around never re-fires): server logs showed a steady stream of
            // /operator-video-upload requests arriving in real time while this page
            // displayed a single frozen frame, byte-identical across repeated
            // screenshots seconds apart. lastFrameTime (bumped by the "load" listener
            // above, which still fires per multipart part even though "error" doesn't
            // fire on close) lets the poll notice "no new frame in 5s despite still
            // being told the operator is active" and force a fresh connection itself,
            // rather than waiting on a server-side signal that has no way to know this
            // one subscriber's connection died independent of the operator's own upload
            // health.
            + "var wasActive=false;"
            + "function pollOperatorVideo(){"
            + "fetch('/operator-video-active').then(function(r){return r.text();}).then(function(t){"
            + "var active=(t==='1');"
            + "if(active&&!wasActive){img.src='/operator-video-stream?r='+Date.now();lastFrameTime=Date.now();}"
            + "else if(!active&&wasActive){img.style.display='none';rig.style.display='flex';img.src='';}"
            + "else if(active&&wasActive&&(Date.now()-lastFrameTime>5000)){"
            + "img.src='/operator-video-stream?r='+Date.now();lastFrameTime=Date.now();"
            + "}"
            + "wasActive=active;"
            + "}).catch(function(){});"
            + "}"
            + "pollOperatorVideo();"
            + "setInterval(pollOperatorVideo,1000);",
            "");
}
