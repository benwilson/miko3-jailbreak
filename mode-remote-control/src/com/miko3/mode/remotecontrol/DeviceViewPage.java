package com.miko3.mode.remotecontrol;

/**
 * What the robot's own on-device screen shows while this mode is active —
 * deliberately just two states, an idle "eyes" face or the operator's video
 * (U13): a full interactive control page (drive buttons, the robot's own
 * camera preview) makes no sense on the robot's own screen — nobody drives a
 * robot by pressing buttons on the robot itself, and showing the robot's own
 * camera back to itself is pointless. No dedicated face asset exists in this
 * project, so the idle state is drawn with plain CSS rather than shipping a
 * new image: two glossy white eyeballs that blink together but whose black
 * pupils each wander independently on their own randomized timer, by
 * request ("can't control where each one is looking"). The <img> stays
 * hidden until its first "load" event — which a
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
            + "@keyframes blink{0%,92%,100%{transform:scaleY(1)}96%{transform:scaleY(0.05)}}"
            // Explicit vmin width+height rather than width+aspect-ratio, and margin
            // rather than the flex container's gap, for spacing between the two:
            // this page renders inside a low-level system WebView on an Android 9
            // device, old enough that neither aspect-ratio nor flexbox gap is a safe
            // bet — confirmed live, both silently no-op here (zero-height eyes with
            // aspect-ratio; touching eyes with gap unchanged across several values).
            // Glossy white eyeball look (per reference image) via a radial-gradient
            // instead of a flat fill, with overflow:hidden so the wandering pupil
            // below can never poke outside the round eyeball.
            + ".eye{width:54vmin;height:54vmin;margin:0 5vmin;border-radius:50%;position:relative;"
            + "overflow:hidden;background:radial-gradient(circle at 38% 32%,#fff,#e2e2e2 65%,#bbb 100%);"
            + "box-shadow:inset 0 -1.8vmin 3.6vmin rgba(0,0,0,0.25);animation:blink 4.5s infinite}"
            + ".eye:nth-child(2){animation-delay:0.2s}"
            // The pupil wanders (JS below moves left/top independently per eye,
            // uncoordinated on purpose); the transition is what makes each move a
            // smooth drift rather than a jump cut.
            + ".pupil{width:42%;height:42%;position:absolute;left:29%;top:29%;border-radius:50%;"
            + "background:#111;transition:left 0.7s ease,top 0.7s ease}"
            + ".highlight{width:32%;height:32%;position:absolute;left:14%;top:12%;border-radius:50%;"
            + "background:#fff;opacity:0.85}"
            + "#video{display:none;width:100%;height:100%;object-fit:contain}"
            + "</style>"
            + "</head><body>"
            + "<div id=\"eyes\">"
            + "<div class=\"eye\"><div class=\"pupil\"><div class=\"highlight\"></div></div></div>"
            + "<div class=\"eye\"><div class=\"pupil\"><div class=\"highlight\"></div></div></div>"
            + "</div>"
            + "<img id=\"video\" alt=\"\">"
            + "<script>"
            + "var img=document.getElementById('video');"
            + "var eyes=document.getElementById('eyes');"
            + "img.addEventListener('load',function(){eyes.style.display='none';img.style.display='block';});"
            + "img.src='/operator-video-stream';"
            // Each pupil wanders on its own independent, randomized timer — no
            // shared clock between the two — so they drift out of sync with each
            // other, per request ("can't control where each one is looking").
            + "var pupils=document.getElementsByClassName('pupil');"
            + "function wander(pupil){"
            + "pupil.style.left=(8+Math.random()*42)+'%';"
            + "pupil.style.top=(8+Math.random()*42)+'%';"
            + "setTimeout(function(){wander(pupil);},600+Math.random()*1600);"
            + "}"
            + "for(var i=0;i<pupils.length;i++){"
            + "(function(p,delay){setTimeout(function(){wander(p);},delay);})(pupils[i],Math.random()*500);"
            + "}"
            + "</script>"
            + "</body></html>";
}
