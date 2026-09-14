package com.miko3.mode.remotecontrol;

/**
 * What the robot's own on-device screen shows while this mode is active —
 * deliberately just two states, an idle "eyes" face or the operator's video
 * (U13): a full interactive control page (drive buttons, the robot's own
 * camera preview) makes no sense on the robot's own screen — nobody drives a
 * robot by pressing buttons on the robot itself, and showing the robot's own
 * camera back to itself is pointless. No dedicated face asset exists in this
 * project, so the idle state is drawn with plain CSS/JS rather than shipping
 * a new image.
 *
 * Styled after WALL-E's eyes (U17), not generic cartoon eyeballs: real
 * WALL-E's eyes are a pair of circular binocular lenses mounted in a
 * weathered-metal housing, each one servo-driven — the character's
 * expressions come from the lens itself moving as a rigid unit (not a pupil
 * darting inside a socket), and the glass lenses' reflective glint is what
 * animators/builders credit with giving the design its "soul."
 *
 * U19 (by request, reference image): the lenses now move together toward a
 * shared, periodically-retargeted 2D point — both X and Y, not just the
 * original vertical-only drift — so they read as *watching something*
 * happening off-screen (tracking a point of interest, with a mix of quick
 * glances and longer held gazes) rather than idly wandering on their own.
 * A tiny per-eye offset keeps them from looking perfectly welded together.
 * The blink is now a blink/fade-out cross: alongside the vertical squash it
 * also dims toward near-transparent at the peak of the blink, rather than
 * just squashing at full opacity.
 *
 * The <img> stays hidden until its first "load" event — which a
 * multipart/x-mixed-replace stream (ModeApp.operatorVideoBroadcaster) fires
 * on every part, so the very first frame the operator actually uploads
 * swaps the eyes for video automatically, with nothing to configure on this
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
            + "#rig{width:100%;height:100%;display:flex;align-items:center;justify-content:center;"
            + "position:relative}"
            // The head housing — WALL-E's eyes sit in a boxy, worn/rusty binocular
            // unit, not floating on a thin bar; a wide plate behind everything else
            // (lowest z-index) makes the two lenses read as mounted INTO a single
            // weathered-metal head instead of two separate floating balls.
            // Centered via top/left + negative margins, not transform:translate(-50%,
            // -50%) — both work here, but margins are the more universally-safe bet
            // on this old WebView given how many newer CSS features have already
            // silently no-op'd on it this session (see below).
            + "#frame{position:absolute;top:50%;left:50%;width:126vmin;height:70vmin;"
            + "margin-top:-35vmin;margin-left:-63vmin;border-radius:6vmin;"
            + "background:linear-gradient(155deg,#9a7a48,#6b4f28 45%,#4a3618 100%);"
            + "border:0.6vmin solid #3a2a14;"
            + "box-shadow:inset 0 0 5vmin rgba(0,0,0,.5), inset 0 1vmin 1.5vmin rgba(255,255,255,.15),"
            + "0 1vmin 2vmin rgba(0,0,0,.6)}"
            // Corner rivets — small dark studs, the kind of worn-industrial detail
            // that reads as "WALL-E's battered chassis" rather than a clean modern
            // gadget.
            + ".rivet{position:absolute;width:3.2vmin;height:3.2vmin;border-radius:50%;"
            + "background:radial-gradient(circle at 35% 30%,#c9b088,#5a4020 70%,#2a1c0c 100%);"
            + "box-shadow:inset 0 0 0.6vmin rgba(0,0,0,.6)}"
            + "#r1{left:6vmin;top:6vmin}#r2{right:6vmin;top:6vmin}"
            + "#r3{left:6vmin;bottom:6vmin}#r4{right:6vmin;bottom:6vmin}"
            // The socket each lens sits in — a fixed, non-moving dark collar mounted
            // in the frame, so the lens above it reads as a tube telescoping out of a
            // round hole in the housing rather than a ball floating in front of it.
            + ".socket{width:56vmin;height:56vmin;margin:0 2vmin;border-radius:50%;position:relative;"
            + "background:radial-gradient(circle at 40% 35%,#2a2012,#100c06 75%);"
            + "box-shadow:inset 0 0.8vmin 2vmin rgba(0,0,0,.8)}"
            // Explicit vmin width+height rather than width+aspect-ratio, and margin
            // rather than the flex container's gap: this page renders inside a
            // low-level system WebView on an Android 9 device, old enough that
            // neither aspect-ratio nor flexbox gap is a safe bet — confirmed live,
            // both silently no-op here (zero-height boxes with aspect-ratio;
            // touching boxes with gap unchanged across several values).
            // .eye-mount is what actually moves (translateY, independently timed
            // per eye) — real WALL-E's expression comes from each lens being
            // raised/lowered on its own servo, not from anything moving inside a
            // fixed eye socket. Centered inside its .socket the same margin-based way
            // as #frame, so the lens (smaller than the socket) can drift within it
            // without ever fully leaving the hole it sits in.
            + ".eye-mount{width:50vmin;height:50vmin;position:absolute;top:50%;left:50%;"
            + "margin-top:-25vmin;margin-left:-25vmin;z-index:1;"
            // Transition duration is set per-move from JS (quick glances vs. slower
            // settles read differently), so no fixed duration here — see gazeTo().
            + "transition:transform ease}"
            // Blink/fade-out cross (U19): the squash alone read as a normal blink;
            // dropping opacity at the same peak moment gives it the "fade" half of
            // the requested cross, like the lens itself is briefly powering down
            // rather than just a mechanical eyelid.
            + "@keyframes blink{0%,90%,100%{transform:scaleY(1);opacity:1}"
            + "95%{transform:scaleY(0.08);opacity:0.2}}"
            // The lens itself: metallic bezel ring (radial-gradient, brushed-steel
            // look) around a dark glass center — no white sclera, no pupil.
            + ".eye{width:100%;height:100%;border-radius:50%;position:relative;overflow:hidden;"
            + "background:radial-gradient(circle at 35% 30%,#9aa0a6,#4a4e52 55%,#1c1e20 100%);"
            + "box-shadow:inset 0 0 3vmin rgba(0,0,0,.7), 0 0 1vmin rgba(0,0,0,.9);"
            + "animation:blink 6s infinite}"
            + ".socket:nth-child(2) .eye{animation-delay:0.15s}"
            + ".lens{position:absolute;left:13%;top:13%;width:74%;height:74%;border-radius:50%;"
            + "background:radial-gradient(circle at 38% 32%,#3d5a66,#0c1113 62%,#000 100%)}"
            // The glass glint — per the production design's own explanation for why
            // binocular lenses read as alive: a fixed reflective highlight, not
            // something that needs to move on its own.
            + ".glint{position:absolute;left:22%;top:16%;width:24%;height:14%;border-radius:50%;"
            + "background:rgba(255,255,255,.85);transform:rotate(-25deg)}"
            + "#video{display:none;width:100%;height:100%;object-fit:contain}"
            + "</style>"
            + "</head><body>"
            + "<div id=\"rig\">"
            + "<div id=\"frame\">"
            + "<div class=\"rivet\" id=\"r1\"></div><div class=\"rivet\" id=\"r2\"></div>"
            + "<div class=\"rivet\" id=\"r3\"></div><div class=\"rivet\" id=\"r4\"></div>"
            + "</div>"
            + "<div id=\"eyes\" style=\"display:flex;position:relative\">"
            + "<div class=\"socket\"><div class=\"eye-mount\"><div class=\"eye\"><div class=\"lens\">"
            + "<div class=\"glint\"></div></div></div></div></div>"
            + "<div class=\"socket\"><div class=\"eye-mount\"><div class=\"eye\"><div class=\"lens\">"
            + "<div class=\"glint\"></div></div></div></div></div>"
            + "</div>"
            + "</div>"
            + "<img id=\"video\" alt=\"\">"
            + "<script>"
            + "var img=document.getElementById('video');"
            + "var rig=document.getElementById('rig');"
            + "img.addEventListener('load',function(){rig.style.display='none';img.style.display='block';});"
            + "img.src='/operator-video-stream';"
            // U19: both eyes track one shared, periodically-retargeted point rather
            // than drifting independently — the "watching something happening" look
            // requested. A tiny per-eye offset (+/-0.4vmin) keeps them from reading
            // as one welded unit. Glance timing mixes quick look-overs (short hold,
            // fast move) with longer held gazes (as if watching something of actual
            // interest before moving on), rather than one uniform cadence.
            + "var mounts=document.getElementsByClassName('eye-mount');"
            + "function gazeTo(x,y,tilt,speedMs){"
            + "for(var i=0;i<mounts.length;i++){"
            + "var jx=x+(i===0?-0.4:0.4);"
            + "mounts[i].style.transition='transform '+speedMs+'ms cubic-bezier(.34,1.2,.4,1)';"
            + "mounts[i].style.transform='translate('+jx+'vmin,'+y+'vmin) rotate('+tilt+'deg)';"
            + "}"
            + "}"
            + "function nextGlance(){"
            + "var x=(Math.random()*8-4);"
            + "var y=(Math.random()*7-3.5);"
            + "var tilt=(Math.random()*6-3);"
            + "var quick=Math.random()<0.35;"
            + "var speedMs=quick?220+Math.random()*180:550+Math.random()*500;"
            + "gazeTo(x,y,tilt,speedMs);"
            + "var hold=quick?400+Math.random()*500:1500+Math.random()*2500;"
            + "setTimeout(nextGlance,speedMs+hold);"
            + "}"
            + "setTimeout(nextGlance,300);"
            + "</script>"
            + "</body></html>";
}
