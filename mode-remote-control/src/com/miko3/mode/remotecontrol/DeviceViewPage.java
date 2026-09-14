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
 * Styled after WALL-E's eyes (U17, by request), not generic cartoon
 * eyeballs: real WALL-E's eyes are a pair of circular binocular lenses (the
 * production design literally reused disassembled binocular lenses) mounted
 * on a connecting bar, each one independently raised/lowered by its own
 * servo — the character's expressions come almost entirely from that
 * eyebrow-like vertical tilt of the whole lens, not from a pupil darting
 * around inside a socket, and the glass lenses' reflective glint is what
 * animators/builders credit with giving the design its "soul." This page
 * reproduces that: two metallic-bezeled dark lenses on a bar, each one
 * independently drifting up/down on its own randomized timer (mostly
 * together, sometimes asymmetric for a curious/quizzical look), with a
 * fixed glint per lens and an occasional synchronized blink (a brief
 * vertical squash). No pupil, no independent left/right wandering inside a
 * socket — that was the previous (pre-U17) generic-eyeball design.
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
            // The bracket/yoke connecting the two lenses, like WALL-E's binocular
            // bar — sits behind the lenses (z-index below .eye-mount) so only the
            // segment between them peeks out.
            // Centered via top/left + negative margins, not transform:translate(-50%,
            // -50%) — both work here, but margins are the more universally-safe bet
            // on this old WebView given how many newer CSS features have already
            // silently no-op'd on it this session.
            + "#bar{position:absolute;top:50%;left:50%;width:62vmin;height:5vmin;"
            + "margin-top:-2.5vmin;margin-left:-31vmin;border-radius:2.5vmin;"
            + "background:linear-gradient(#4a4e52,#1c1e20);box-shadow:0 0.4vmin 1vmin rgba(0,0,0,.6)}"
            // Explicit vmin width+height rather than width+aspect-ratio, and margin
            // rather than the flex container's gap: this page renders inside a
            // low-level system WebView on an Android 9 device, old enough that
            // neither aspect-ratio nor flexbox gap is a safe bet — confirmed live,
            // both silently no-op here (zero-height boxes with aspect-ratio;
            // touching boxes with gap unchanged across several values).
            // .eye-mount is what actually moves (translateY, independently timed
            // per eye) — real WALL-E's expression comes from each lens being
            // raised/lowered on its own servo, not from anything moving inside a
            // fixed eye socket.
            + ".eye-mount{width:50vmin;height:50vmin;margin:0 2vmin;position:relative;z-index:1;"
            + "transition:transform 0.9s cubic-bezier(.4,0,.2,1)}"
            + "@keyframes blink{0%,92%,100%{transform:scaleY(1)}96%{transform:scaleY(0.08)}}"
            // The lens itself: metallic bezel ring (radial-gradient, brushed-steel
            // look) around a dark glass center — no white sclera, no pupil.
            + ".eye{width:100%;height:100%;border-radius:50%;position:relative;overflow:hidden;"
            + "background:radial-gradient(circle at 35% 30%,#9aa0a6,#4a4e52 55%,#1c1e20 100%);"
            + "box-shadow:inset 0 0 3vmin rgba(0,0,0,.7);animation:blink 6s infinite}"
            + ".eye-mount:nth-child(2) .eye{animation-delay:0.15s}"
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
            + "<div id=\"bar\"></div>"
            + "<div id=\"eyes\" style=\"display:flex;position:relative\">"
            + "<div class=\"eye-mount\"><div class=\"eye\"><div class=\"lens\"><div class=\"glint\">"
            + "</div></div></div></div>"
            + "<div class=\"eye-mount\"><div class=\"eye\"><div class=\"lens\"><div class=\"glint\">"
            + "</div></div></div></div>"
            + "</div>"
            + "</div>"
            + "<img id=\"video\" alt=\"\">"
            + "<script>"
            + "var img=document.getElementById('video');"
            + "var rig=document.getElementById('rig');"
            + "img.addEventListener('load',function(){rig.style.display='none';img.style.display='block';});"
            + "img.src='/operator-video-stream';"
            // Each mount drifts up/down on its own independent, randomized timer —
            // mostly a shared range so both eyes usually move together, but never
            // synchronized to the same clock, so they sometimes land asymmetric for
            // a curious/quizzical tilt (real WALL-E's two eyes are independently
            // servo-driven, not locked to each other).
            + "var mounts=document.getElementsByClassName('eye-mount');"
            + "function drift(mount){"
            + "var y=(Math.random()*10-5);"
            + "var tilt=(Math.random()*8-4);"
            + "mount.style.transform='translateY('+y+'vmin) rotate('+tilt+'deg)';"
            + "setTimeout(function(){drift(mount);},1200+Math.random()*2200);"
            + "}"
            + "for(var i=0;i<mounts.length;i++){"
            + "(function(m,delay){setTimeout(function(){drift(m);},delay);})(mounts[i],Math.random()*600);"
            + "}"
            + "</script>"
            + "</body></html>";
}
