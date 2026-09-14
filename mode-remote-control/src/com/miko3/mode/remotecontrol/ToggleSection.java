package com.miko3.mode.remotecontrol;

/**
 * The audio/video toggle section of the served page (R6/R17, U8): three
 * independent switches.
 *
 * Robot mic -> operator: works from any client (playback-only, KTD8) —
 * fetches raw PCM from /audio.pcm and schedules it through the Web Audio
 * API.
 *
 * Operator mic -> robot speaker: this device's WebView can't actually run
 * getUserMedia audio capture at all (verified: NotReadableError), so it
 * uses the native fallback (NativeCaptureBridge, exposed as
 * "AndroidCapture") instead of a browser API, and no-ops with a status
 * message when AndroidCapture isn't present (i.e. from a remote browser —
 * still on-device-only for this version, per R6/R7's scoping).
 *
 * Operator video -> robot screen: genuinely bidirectional now. Only a
 * *remote* browser has both a real webcam and the user sitting in front of
 * it to consent to it, so capture happens there via getUserMedia (secure
 * context: works from any https:// origin or, same as every other route
 * this project tunnels through, the http://127.0.0.1 loopback exception —
 * see docs/hardware — no TLS needed). Captured frames are periodically
 * canvas-encoded to JPEG and POSTed to /operator-video-upload; the
 * on-device WebView side (detected via window.AndroidCapture's presence,
 * same test the mic toggle uses) instead just points operator-video-img at
 * /operator-video-stream, the server-side re-broadcast of whatever was last
 * uploaded (ModeApp.operatorVideoBroadcaster, a second MjpegBroadcaster
 * instance reusing the same fan-out the main camera view uses). A remote
 * browser also gets a small local self-preview so it's obvious capture is
 * actually running.
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
            + "<label><input type=\"checkbox\" id=\"toggle-operator-video\">Operator video &rarr; robot screen</label>"
            + "<img id=\"operator-video-img\" hidden alt=\"operator video, as shown on the robot's screen\""
            + " style=\"max-width:100%\">"
            + "<video id=\"operator-video-preview\" hidden autoplay playsinline muted"
            + " style=\"max-width:200px\"></video>"
            + "<canvas id=\"operator-video-canvas\" hidden></canvas>"
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

            // Operator video -> robot screen. On-device WebView: subscribe to the
            // re-broadcast stream. Remote browser: capture the operator's own webcam
            // and upload frames to it.
            + "var operatorVideoStream=null;var operatorVideoUploadTimer=null;"
            + "function startOperatorVideoOnDevice(){"
            + "var img=document.getElementById('operator-video-img');"
            + "img.src='/operator-video-stream';img.hidden=false;"
            + "}"
            + "function stopOperatorVideoOnDevice(){"
            + "var img=document.getElementById('operator-video-img');"
            + "img.hidden=true;img.src='';"
            + "}"
            + "function startOperatorVideoRemote(){"
            + "if(!(navigator.mediaDevices&&navigator.mediaDevices.getUserMedia)){"
            + "reportError('This browser has no camera API (getUserMedia) available.');"
            + "return;"
            + "}"
            + "navigator.mediaDevices.getUserMedia({video:true}).then(function(stream){"
            + "operatorVideoStream=stream;"
            + "var preview=document.getElementById('operator-video-preview');"
            + "preview.srcObject=stream;preview.hidden=false;"
            + "var canvas=document.getElementById('operator-video-canvas');"
            + "var ctx=canvas.getContext('2d');"
            + "operatorVideoUploadTimer=setInterval(function(){"
            + "if(preview.videoWidth===0)return;"
            + "canvas.width=preview.videoWidth;canvas.height=preview.videoHeight;"
            + "ctx.drawImage(preview,0,0);"
            + "canvas.toBlob(function(blob){"
            + "if(!blob)return;"
            + "fetch('/operator-video-upload',{method:'POST',body:blob}).catch(function(){});"
            + "},'image/jpeg',0.7);"
            + "},200);"
            + "}).catch(function(err){"
            + "reportError('Camera access failed: '+err.message);"
            + "document.getElementById('toggle-operator-video').checked=false;"
            + "});"
            + "}"
            + "function stopOperatorVideoRemote(){"
            + "if(operatorVideoUploadTimer){clearInterval(operatorVideoUploadTimer);operatorVideoUploadTimer=null;}"
            + "if(operatorVideoStream){"
            + "operatorVideoStream.getTracks().forEach(function(t){t.stop();});"
            + "operatorVideoStream=null;"
            + "}"
            + "var preview=document.getElementById('operator-video-preview');"
            + "preview.hidden=true;preview.srcObject=null;"
            + "}"
            + "document.getElementById('toggle-operator-video').addEventListener('change',function(e){"
            + "var onDevice=!!(window.AndroidCapture);"
            + "if(e.target.checked){"
            + "if(onDevice){startOperatorVideoOnDevice();}else{startOperatorVideoRemote();}"
            + "}else{"
            + "if(onDevice){stopOperatorVideoOnDevice();}else{stopOperatorVideoRemote();}"
            + "}"
            + "});"
            + "})();"
            + "</script>"
            + "</section>";
}
