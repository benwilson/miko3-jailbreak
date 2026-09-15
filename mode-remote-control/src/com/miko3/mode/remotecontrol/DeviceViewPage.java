package com.miko3.mode.remotecontrol;

/**
 * What the robot's own on-device screen shows while this mode is active —
 * deliberately just two states, an idle "eye" or the operator's video (U13):
 * a full interactive control page (drive buttons, the robot's own camera
 * preview) makes no sense on the robot's own screen — nobody drives a robot
 * by pressing buttons on the robot itself, and showing the robot's own
 * camera back to itself is pointless. No dedicated face asset exists in this
 * project, so the idle state is drawn with plain CSS/JS rather than shipping
 * a new image.
 *
 * U20 (by request, reference image — superseding U17/U19's WALL-E design):
 * styled after HAL 9000's eye (2001: A Space Odyssey) — a circular
 * chrome-bezeled lens with a glowing red/amber core (bright near-white hot
 * spot fading through orange to deep red) against black glass, plus a couple
 * of fixed curved highlights suggesting light catching the lens's convex
 * surface. Two lenses side by side (by request — the real HAL has one, but
 * this robot's face keeps a pair, styled in HAL's language instead of
 * WALL-E's), both tracking the same gaze target so they read as one set of
 * eyes looking together rather than two independent ones.
 *
 * "Looking around" is each glow drifting to a shared, periodically
 * retargeted 2D point within its lens (kept from U19's design, which mixed
 * quick glances with longer held gazes for a "watching something happening"
 * feel — that timing logic is unchanged, just driving two glows off one
 * shared target instead of two eye-mounts). The requested blink is a full
 * fade to transparent crossed with a squash, standing in for an eyelid
 * HAL's lens doesn't have — during the blink the glow briefly vanishes
 * entirely, not just dims.
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

    static final String HTML =
            "<!doctype html><html><head><meta charset=\"utf-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
            + "<title>Remote Control / Telepresence</title>"
            + "<style>"
            + "html,body{margin:0;height:100%;background:#000;overflow:hidden}"
            + "#rig{width:100%;height:100%;display:flex;align-items:center;justify-content:center}"
            // The chrome bezel: a metallic gradient filling the WHOLE circle (bright
            // highlight over gray-to-silver, per a real curved-metal-ring look).
            // Confirmed live: an earlier single-gradient attempt that faded straight
            // to black past the highlight never actually formed a visible ring —
            // once it reaches its last color stop, CSS holds that color all the way
            // to 100%, so there was no "ring" left uncovered by the black glass
            // above it, just an off-center gray blob. Stacking a smaller solid-black
            // .glass circle on top (below) is what actually leaves a ring of this
            // showing around its edge. Explicit vmin width/height, not aspect-ratio:
            // this page renders on an Android 9 device old enough that aspect-ratio
            // silently no-ops on its WebView (confirmed live earlier this session).
            + ".housing{width:76vmin;height:76vmin;margin:0 6vmin;border-radius:50%;position:relative;"
            + "background:radial-gradient(circle at 33% 28%,#f5f5f5,#c9c9c9 22%,#8a8a8a 45%,#5a5a5a 70%,"
            + "#3a3a3a 100%);"
            + "box-shadow:0 0 4vmin rgba(0,0,0,.8)}"
            // The black glass, sized smaller than .housing so a ring of the metal
            // bezel above shows around it. overflow:hidden clips the glow to this
            // circle rather than the full housing.
            + ".glass{position:absolute;left:9%;top:9%;width:82%;height:82%;border-radius:50%;"
            + "overflow:hidden;background:#000;box-shadow:inset 0 0 3vmin rgba(0,0,0,.9)}"
            // The glow is split into two nested elements on purpose: .glow (outer)
            // handles gaze position — JS sets its transform:translate(...) below —
            // and .glow-core (inner) handles the blink's transform:scale(...)/opacity
            // keyframes. Confirmed live: putting both on ONE element meant the blink
            // animation's own transform keyframes (running continuously) overrode
            // whatever translate JS had just set, so the gaze motion was barely
            // visible — a CSS animation's transform wins over one set via element
            // style for the same property on the same element. Sized larger than its
            // visible hot spot so the gradient's outer red has room to fall off to
            // nothing before it would hit the lens edge in any direction gaze sends it.
            + ".glow{position:absolute;width:70%;height:70%;left:15%;top:15%;"
            + "transition:transform ease}"
            + ".glow-core{width:100%;height:100%;border-radius:50%;"
            + "background:radial-gradient(circle at center,#fff6d6 0%,#ffd23f 8%,#ff8a1e 22%,"
            + "#e2331c 42%,#7a0f0a 62%,rgba(0,0,0,0) 78%)}"
            // Fixed curved highlights — light catching the lens's convex glass, per
            // the reference image's own arcing white streaks near the top. Static on
            // purpose (see class javadoc): the glow moves, these don't. Positioned
            // relative to .glass (they're inside it in the markup) so they sit over
            // the dark lens, not the metal ring.
            + ".hi{position:absolute;border:1.2vmin solid rgba(255,255,255,.65);border-radius:50%;"
            + "border-right-color:transparent;border-bottom-color:transparent;"
            + "box-shadow:0 0 1.6vmin rgba(255,255,255,.3)}"
            + ".hi1{width:34%;height:26%;left:14%;top:8%;transform:rotate(-30deg)}"
            + ".hi2{width:18%;height:14%;left:60%;top:14%;transform:rotate(50deg)}"
            // Blink crossed with a fade-out: the glow squashes AND fades all the way
            // to fully transparent at the peak (opacity:0, not just dimmed), standing
            // in for an eyelid HAL's lens doesn't physically have.
            + "@keyframes blink{0%,90%,100%{transform:scale(1);opacity:1}"
            + "95%{transform:scale(0.85,0.08);opacity:0}}"
            + "#video{display:none;width:100%;height:100%;object-fit:contain}"
            + "</style>"
            + "</head><body>"
            + "<div id=\"rig\">"
            + "<div class=\"housing\">"
            + "<div class=\"glass\">"
            + "<div class=\"glow\"><div class=\"glow-core\"></div></div>"
            + "<div class=\"hi hi1\"></div><div class=\"hi hi2\"></div>"
            + "</div>"
            + "</div>"
            + "<div class=\"housing\">"
            + "<div class=\"glass\">"
            + "<div class=\"glow\"><div class=\"glow-core\"></div></div>"
            + "<div class=\"hi hi1\"></div><div class=\"hi hi2\"></div>"
            + "</div>"
            + "</div>"
            + "</div>"
            + "<img id=\"video\" alt=\"\">"
            + "<script>"
            + "var img=document.getElementById('video');"
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
            + "setInterval(pollOperatorVideo,1000);"
            + "var glows=document.getElementsByClassName('glow');"
            + "var cores=document.getElementsByClassName('glow-core');"
            + "for(var g=0;g<cores.length;g++){"
            + "cores[g].style.animation='blink 6.5s infinite';"
            + "cores[g].style.animationDelay=(g*0.2)+'s';"
            + "}"
            // "Watching something happening": both glows drift together to one
            // shared, periodically retargeted point rather than idly wandering on a
            // uniform clock — mixes quick glances (short hold, fast move) with
            // longer held gazes, kept from this page's earlier design.
            + "function gazeTo(x,y,speedMs){"
            + "for(var g=0;g<glows.length;g++){"
            + "glows[g].style.transition='transform '+speedMs+'ms cubic-bezier(.34,1.2,.4,1)';"
            + "glows[g].style.transform='translate('+x+'vmin,'+y+'vmin)';"
            + "}"
            + "}"
            + "function nextGlance(){"
            + "var x=(Math.random()*20-10);"
            + "var y=(Math.random()*18-9);"
            + "var quick=Math.random()<0.35;"
            + "var speedMs=quick?200+Math.random()*160:500+Math.random()*450;"
            + "gazeTo(x,y,speedMs);"
            + "var hold=quick?400+Math.random()*500:1500+Math.random()*2500;"
            + "setTimeout(nextGlance,speedMs+hold);"
            + "}"
            + "setTimeout(nextGlance,300);"
            + "</script>"
            + "</body></html>";
}
