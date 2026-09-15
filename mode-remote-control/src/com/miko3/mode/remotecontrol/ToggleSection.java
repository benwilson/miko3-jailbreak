package com.miko3.mode.remotecontrol;

/**
 * The audio/video toggle section of the served page (R6/R17, U8): three
 * independent switches.
 *
 * Robot mic -> operator: works from any client (playback-only, KTD8) —
 * fetches raw PCM from /audio.pcm and schedules it through the Web Audio
 * API.
 *
 * Operator mic -> robot speaker (U19o, expanding R6/R7's original
 * on-device-only scoping): this device's WebView can't actually run
 * getUserMedia audio capture at all (verified: NotReadableError), so
 * on-device still uses the native fallback (NativeCaptureBridge, exposed as
 * "AndroidCapture") — a literal mic-to-speaker loopback on the same
 * physical unit, kept only as a structural test path (see its own class
 * comment on why it's never run as a sustained live loop). A *remote*
 * browser instead captures via getUserMedia, resamples to 16-bit/16kHz/mono
 * PCM with a ScriptProcessorNode, and POSTs raw chunks to
 * /operator-audio-upload — ModeApp.OperatorSpeakerPlayer writes each chunk
 * straight to an AudioTrack on the robot, no container/codec involved, same
 * KTD8 reasoning as the robot-mic direction. Genuinely two different
 * physical devices this time, so — unlike the on-device fallback — this
 * path itself can't feed back on its own; the only remaining feedback risk
 * is acoustic, on the operator's own end, if both mic toggles are on at
 * once and they're not on headphones (see checkFeedbackRisk()).
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
            + "<label><input type=\"checkbox\" id=\"toggle-operator-mic\">Operator mic &rarr; robot speaker</label>"
            + "<label><input type=\"checkbox\" id=\"toggle-song\">&#127925; Highway to the Danger Zone</label>"
            // U19m: the "Operator video -> robot screen" checkbox + its <img>/<video>
            // moved to CameraSection.java (next to the robot camera feed) -- do not
            // re-add them here, getElementById() only finds the first of a duplicate id
            // and a second copy would be a dead, unwired checkbox (confirmed live: this
            // exact mistake happened once already when the move was first made).
            // operator-video-canvas has no visible layout, so it stays here.
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
            + "checkFeedbackRisk();"
            + "});"
            + "});"

            // Song playback lives entirely server-side (MediaPlayer on the robot's own
            // speaker, see ModeApp.SongPlayer) -- this toggle is just an on/off switch,
            // nothing to start/stop in the browser itself.
            + "document.getElementById('toggle-song').addEventListener('change',function(e){"
            + "fetch('/toggle-song?on='+e.target.checked+'&ct='+encodeURIComponent(CLIENT_TOKEN)).then(function(r){"
            + "if(r.status===409){e.target.checked=!e.target.checked;"
            + "reportError('Control taken by another connection.');}"
            + "});"
            + "});"

            // Both mic directions can be live at once (robot mic -> this browser's
            // speakers, and this browser's mic -> robot speaker) -- if the operator
            // isn't on headphones that's a genuine acoustic loop (robot mic hears the
            // operator's own speakers, which are playing the operator's own mic, which
            // just came out the robot's speaker...). Nothing here can detect headphones
            // vs. speakers, so this is a warning, not a block.
            + "function checkFeedbackRisk(){"
            + "if(document.getElementById('toggle-robot-mic').checked"
            + "&&document.getElementById('toggle-operator-mic').checked){"
            + "status.textContent='Both mics are live -- use headphones on the operator side to avoid feedback.';"
            + "}else if(status.textContent.indexOf('feedback')!==-1){"
            + "status.textContent='';"
            + "}"
            + "}"

            // Operator mic -> robot speaker. On-device WebView: NativeCaptureBridge's
            // mic->speaker loopback fallback (see its own class comment -- getUserMedia
            // audio capture doesn't work in this WebView at all). Remote browser: a
            // genuine two-device path -- capture via getUserMedia, resample to
            // MicCapture's own 16000Hz mono s16le PCM with a ScriptProcessorNode (chosen
            // over MediaRecorder specifically so the server never has to decode a
            // compressed container -- ModeApp.OperatorSpeakerPlayer writes each chunk
            // straight to an AudioTrack), and POST each chunk to /operator-audio-upload.
            + "var operatorMicStream=null,operatorMicCtx=null,operatorMicProcessor=null,operatorMicSource=null;"
            + "function floatTo16(input){"
            + "var out=new Int16Array(input.length);"
            + "for(var i=0;i<input.length;i++){var s=Math.max(-1,Math.min(1,input[i]));"
            + "out[i]=s<0?s*0x8000:s*0x7fff;}"
            + "return out;"
            + "}"
            + "function resampleTo16k(input,inputRate){"
            + "if(inputRate===16000)return input;"
            + "var ratio=inputRate/16000;var outLen=Math.floor(input.length/ratio);"
            + "var out=new Float32Array(outLen);"
            + "for(var i=0;i<outLen;i++){out[i]=input[Math.floor(i*ratio)];}"
            + "return out;"
            + "}"
            + "function startOperatorMicRemote(){"
            + "if(!(navigator.mediaDevices&&navigator.mediaDevices.getUserMedia)){"
            + "reportError('This browser has no microphone API (getUserMedia) available.');"
            + "document.getElementById('toggle-operator-mic').checked=false;"
            + "return;"
            + "}"
            + "navigator.mediaDevices.getUserMedia({audio:true}).then(function(stream){"
            + "operatorMicStream=stream;"
            + "operatorMicCtx=new (window.AudioContext||window.webkitAudioContext)();"
            + "operatorMicSource=operatorMicCtx.createMediaStreamSource(stream);"
            + "operatorMicProcessor=operatorMicCtx.createScriptProcessor(4096,1,1);"
            // Routed through a silent (gain=0) node rather than left unconnected: Chrome
            // only keeps firing onaudioprocess while the node is part of a live graph
            // reaching the destination, and connecting it to the destination directly
            // would play the operator's own mic back out their own speakers.
            + "var mute=operatorMicCtx.createGain();mute.gain.value=0;"
            + "operatorMicSource.connect(operatorMicProcessor);"
            + "operatorMicProcessor.connect(mute);mute.connect(operatorMicCtx.destination);"
            + "operatorMicProcessor.onaudioprocess=function(e){"
            + "var resampled=resampleTo16k(e.inputBuffer.getChannelData(0),operatorMicCtx.sampleRate);"
            + "var pcm16=floatTo16(resampled);"
            + "fetch('/operator-audio-upload',{method:'POST',body:pcm16.buffer}).catch(function(){});"
            + "};"
            + "checkFeedbackRisk();"
            + "}).catch(function(err){"
            + "reportError('Microphone access failed: '+err.message);"
            + "document.getElementById('toggle-operator-mic').checked=false;"
            + "});"
            + "}"
            + "function stopOperatorMicRemote(){"
            + "if(operatorMicProcessor){operatorMicProcessor.onaudioprocess=null;operatorMicProcessor.disconnect();"
            + "operatorMicProcessor=null;}"
            + "if(operatorMicSource){operatorMicSource.disconnect();operatorMicSource=null;}"
            + "if(operatorMicCtx){operatorMicCtx.close();operatorMicCtx=null;}"
            + "if(operatorMicStream){operatorMicStream.getTracks().forEach(function(t){t.stop();});operatorMicStream=null;}"
            + "fetch('/operator-audio-stop?ct='+encodeURIComponent(CLIENT_TOKEN)).catch(function(){});"
            + "}"
            + "document.getElementById('toggle-operator-mic').addEventListener('change',function(e){"
            + "var onDevice=!!(window.AndroidCapture&&window.AndroidCapture.toggleOperatorMic);"
            + "if(e.target.checked){"
            + "if(onDevice){window.AndroidCapture.toggleOperatorMic(true);}else{startOperatorMicRemote();}"
            + "}else{"
            + "if(onDevice){window.AndroidCapture.toggleOperatorMic(false);}else{stopOperatorMicRemote();}"
            + "checkFeedbackRisk();"
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
            // Requested small on purpose (U19u, by request): the operator-video-img/
            // camera-img display box (CameraSection's own MEDIA_BOX_STYLE) uses
            // object-fit:cover, which already scales/crops ANY source resolution to
            // fill it -- so this isn't about "fitting the screen" (that's automatic),
            // it's purely to cut this feed's own bandwidth and per-frame encode/decode
            // cost. {width,height}:{ideal:...} is a soft constraint -- some cameras
            // won't hit it exactly -- so the draw loop below ALSO clamps its own
            // output size to the same cap regardless of what the camera actually
            // negotiated, rather than trusting the constraint alone.
            + "var OPVID_MAX_W=320,OPVID_MAX_H=240;"
            + "navigator.mediaDevices.getUserMedia({video:{width:{ideal:OPVID_MAX_W},"
            + "height:{ideal:OPVID_MAX_H}}}).then(function(stream){"
            + "operatorVideoStream=stream;"
            + "var preview=document.getElementById('operator-video-preview');"
            + "preview.srcObject=stream;preview.hidden=false;"
            + "var canvas=document.getElementById('operator-video-canvas');"
            + "var ctx=canvas.getContext('2d');"
            + "operatorVideoUploadTimer=setInterval(function(){"
            + "if(preview.videoWidth===0)return;"
            + "var scale=Math.min(1,OPVID_MAX_W/preview.videoWidth,OPVID_MAX_H/preview.videoHeight);"
            + "canvas.width=Math.round(preview.videoWidth*scale);"
            + "canvas.height=Math.round(preview.videoHeight*scale);"
            + "ctx.drawImage(preview,0,0,canvas.width,canvas.height);"
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
            // Tells the server to actively disconnect operatorVideoBroadcaster's current
            // subscribers -- notably DeviceViewPage's own on-device idle-eyes page, which
            // otherwise has no way to tell "the operator just stopped" apart from "no
            // frame yet" and would get stuck showing the last uploaded frame forever
            // instead of switching back to the eyes. Only called here (the actual
            // uploader stopping), not from stopOperatorVideoOnDevice() -- that path is a
            // mere VIEWER unsubscribing from the stream, which shouldn't tear down the
            // broadcast for every other subscriber.
            + "fetch('/operator-video-stop?ct='+encodeURIComponent(CLIENT_TOKEN)).catch(function(){});"
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
