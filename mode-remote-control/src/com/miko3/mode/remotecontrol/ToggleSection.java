package com.miko3.mode.remotecontrol;

/**
 * The audio/video toggle section of the served page (R6/R17, U8): three
 * independent switches.
 *
 * Robot mic -> operator: works from any client (playback-only, KTD8) —
 * fetches raw PCM from /audio.pcm and schedules it through the Web Audio
 * API.
 *
 * The other two toggles are the operator-capture ones KTD7's document-
 * review correction scoped to on-device-only for this version (they need
 * getUserMedia's secure-context exception, which only the on-device
 * loopback qualifies for) — and this device's WebView can't actually run
 * getUserMedia audio capture at all (verified: NotReadableError), so both
 * use the native fallback (NativeCaptureBridge, exposed as
 * "AndroidCapture") instead of browser APIs. They no-op with a status
 * message when AndroidCapture isn't present, i.e. from a remote browser.
 */
final class ToggleSection {
    private ToggleSection() {
    }

    static final String HTML =
            "<section id=\"toggles\">"
            + "<h2>Audio / Video</h2>"
            + "<p id=\"toggle-status\" role=\"status\"></p>"
            + "<label><input type=\"checkbox\" id=\"toggle-robot-mic\">Robot mic &rarr; operator</label>"
            + "<label><input type=\"checkbox\" id=\"toggle-operator-mic\">Operator mic &rarr; robot speaker"
            + " <small>(on-device only)</small></label>"
            + "<label><input type=\"checkbox\" id=\"toggle-operator-video\">Operator video &rarr; robot screen"
            + " <small>(on-device only)</small></label>"
            + "<img id=\"operator-video-img\" hidden alt=\"operator video (on-device loopback)\""
            + " style=\"max-width:100%\">"
            + "<audio id=\"robot-mic-audio\" hidden></audio>"
            + "<script>"
            + "(function(){"
            + "var status=document.getElementById('toggle-status');"
            + "function reportError(msg){status.textContent=msg;}"

            // Robot mic -> operator: raw PCM over fetch + Web Audio API scheduling.
            + "var audioCtx=null;var nextStartTime=0;var pcmReader=null;"
            + "function startRobotMicPlayback(){"
            + "audioCtx=new (window.AudioContext||window.webkitAudioContext)();"
            + "nextStartTime=0;"
            + "fetch('/audio.pcm').then(function(resp){"
            + "if(!resp.ok){reportError('Robot mic stream unavailable.');return;}"
            + "var reader=resp.body.getReader();pcmReader=reader;"
            + "var leftover=new Uint8Array(0);"
            + "function pump(){"
            + "reader.read().then(function(res){"
            + "if(res.done)return;"
            + "var chunk=new Uint8Array(leftover.length+res.value.length);"
            + "chunk.set(leftover,0);chunk.set(res.value,leftover.length);"
            + "var usableLen=chunk.length-(chunk.length%2);"
            + "leftover=chunk.slice(usableLen);"
            + "var samples=usableLen/2;"
            + "if(samples>0){"
            + "var view=new DataView(chunk.buffer,chunk.byteOffset,usableLen);"
            + "var buf=audioCtx.createBuffer(1,samples,16000);"
            + "var out=buf.getChannelData(0);"
            + "for(var i=0;i<samples;i++){out[i]=view.getInt16(i*2,true)/32768;}"
            + "var src=audioCtx.createBufferSource();src.buffer=buf;src.connect(audioCtx.destination);"
            + "var now=audioCtx.currentTime;"
            + "if(nextStartTime<now)nextStartTime=now;"
            + "src.start(nextStartTime);nextStartTime+=buf.duration;"
            + "}"
            + "pump();"
            + "}).catch(function(){});"
            + "}"
            + "pump();"
            + "});"
            + "}"
            + "function stopRobotMicPlayback(){"
            + "if(pcmReader){pcmReader.cancel();pcmReader=null;}"
            + "if(audioCtx){audioCtx.close();audioCtx=null;}"
            + "}"
            + "document.getElementById('toggle-robot-mic').addEventListener('change',function(e){"
            + "fetch('/toggle-mic?on='+e.target.checked+'&ct='+encodeURIComponent(CLIENT_TOKEN)).then(function(r){"
            + "if(r.status===409){e.target.checked=!e.target.checked;"
            + "reportError('Control taken by another connection.');return;}"
            + "if(e.target.checked){startRobotMicPlayback();}else{stopRobotMicPlayback();}"
            + "});"
            + "});"

            // Operator mic -> robot speaker: native fallback only.
            + "document.getElementById('toggle-operator-mic').addEventListener('change',function(e){"
            + "if(window.AndroidCapture&&window.AndroidCapture.toggleOperatorMic){"
            + "window.AndroidCapture.toggleOperatorMic(e.target.checked);"
            + "}else{"
            + "e.target.checked=false;"
            + "reportError('Operator mic is on-device only (not available from a remote browser yet).');"
            + "}"
            + "});"

            // Operator video -> robot screen: on-device-only loopback demo, reusing
            // the same MJPEG stream U6 already proved works, no separate capture.
            + "document.getElementById('toggle-operator-video').addEventListener('change',function(e){"
            + "var img=document.getElementById('operator-video-img');"
            // A distinct query string, not just the same /stream.mjpeg the main camera
            // <img> already holds open indefinitely, is required here: this WebView
            // appears to coalesce a second request to an identical URL behind the
            // first rather than opening its own connection, and since an MJPEG stream
            // never completes, the second request then never fires at all (confirmed
            // live: img.src/hidden were set correctly but no connection ever formed
            // until this was added). The query string is stripped server-side
            // (RoutingHttpServer routes by path only), so both still hit the same handler
            // and share the one capture session (KTD4).
            + "if(e.target.checked){img.src='/stream.mjpeg?viewer=operator';img.hidden=false;}"
            + "else{img.hidden=true;img.src='';}"
            + "});"
            + "})();"
            + "</script>"
            + "</section>";
}
