package com.miko3.mode.remotecontrol;

/**
 * The drive-control section of the served page (R5/R16/R17, U7): hold-to-drive
 * buttons (mouse/touch) and arrow keys (U13), both driving over one persistent
 * WebSocket connection to ModeApp's "/drive-ws" route instead of a plain HTTP
 * request per command (U7's original approach) — each of those paid a fresh
 * HTTPS/TLS handshake, slow enough over WiFi to contribute to visible
 * start/stop jank ("straight, jank, jank, straight").
 *
 * REPEAT INTERVAL — 500ms, not 80ms (U19c, 2026-09-15): the original 80ms
 * cadence assumed each drive command needed to arrive faster than its own
 * ~100ms motor-frame duration to avoid visible gaps between frames. Confirmed
 * live that resending that fast instead triggered what looks like the motor
 * firmware's own stall/overload protection for sustained FORWARD driving
 * specifically (moves a slight amount, stops, repeats on a fixed ~10s cycle
 * for as long as held) — turning has much less resistance and tolerated the
 * fast resends, forward didn't. See DriveController.drive()'s own comment and
 * DirectMotorDriver.driveSustained()/buildSustainedFrame() for the fix:
 * matching ServiceExam's own confirmed-live held-drive feature, which never
 * re-fires faster than ~500ms and packs more motion into each single message
 * (a 2-frame kick+sustain sequence) rather than resending faster. Do not
 * shorten this back toward 80ms without first confirming that stall pattern
 * is actually gone.
 *
 * Sends an explicit stop on release (now the real MTSTP command, not a
 * zero-velocity VEL1 frame — see DirectMotorDriver.stop()'s own comment).
 * Plus an "Exit mode" control that releases the lease and returns to the
 * launcher (R16) — reachable without physical access to the robot.
 */
final class DriveSection {
    private DriveSection() {
    }

    static final String HTML =
            "<section id=\"drive\">"
            + "<h2>Drive</h2>"
            + "<p id=\"drive-status\" role=\"status\"></p>"
            + "<div class=\"grid\">"
            + "<button id=\"btn-left\" data-linear=\"0\" data-angular=\"-20\">&#8630; Left</button>"
            + "<button id=\"btn-forward\" data-linear=\"20\" data-angular=\"0\">&#8593; Forward</button>"
            + "<button id=\"btn-right\" data-linear=\"0\" data-angular=\"20\">&#8631; Right</button>"
            + "</div>"
            + "<div class=\"grid\">"
            + "<button id=\"btn-back\" data-linear=\"-20\" data-angular=\"0\">&#8595; Back</button>"
            + "</div>"
            + "<p><small>Arrow keys also work while this page has focus.</small></p>"
            + "<button id=\"btn-exit\" class=\"secondary\">Exit mode</button>"
            + "<script>"
            + "(function(){"
            + "var status=document.getElementById('drive-status');"
            + "var repeatTimer=null;"
            + "var ws=null;"
            + "var wsOpen=false;"
            // Opened once and kept open for the page's whole lifetime, with
            // reconnect-on-drop — a network blip or the robot server restarting
            // shouldn't permanently break driving until the whole page reloads.
            + "function connectWs(){"
            + "ws=new WebSocket('wss://'+location.host+'/drive-ws?ct='+encodeURIComponent(CLIENT_TOKEN));"
            + "ws.onopen=function(){wsOpen=true;status.textContent='';};"
            + "ws.onclose=function(){wsOpen=false;setTimeout(connectWs,1000);};"
            + "ws.onerror=function(){wsOpen=false;};"
            + "ws.onmessage=function(ev){"
            + "if(ev.data==='conflict'){status.textContent='Control taken by another connection.';}"
            + "};"
            + "}"
            + "connectWs();"
            + "function drive(linear,angular){"
            + "if(!wsOpen){status.textContent='Connecting...';return;}"
            + "try{ws.send('drive '+linear+' '+angular);}"
            + "catch(e){status.textContent='Drive command failed (network error).';}"
            + "}"
            + "function startHold(btn){"
            // A second press (e.g. multi-touch, dragging between buttons, or
            // switching direction with a key while another is still held) before
            // the first release would otherwise overwrite the single repeatTimer
            // reference with no way left to clear the first interval — it would
            // then keep sending drive commands forever, even after the user
            // believes they released. Clearing any existing hold first (which also
            // sends its own stop) guarantees at most one interval is ever live.
            + "stopHold();"
            + "var linear=btn.getAttribute('data-linear');"
            + "var angular=btn.getAttribute('data-angular');"
            + "drive(linear,angular);"
            // 500ms — see this class's own javadoc (U19c) for why this changed from
            // 80ms: each driveSustained() call now packs a 150ms kick+sustain
            // sequence into one message, and resending faster than ServiceExam's own
            // confirmed-live held-drive cadence (~500ms) is what caused sustained
            // forward driving to trip the motor firmware's stall protection.
            + "repeatTimer=setInterval(function(){drive(linear,angular);},500);"
            + "}"
            + "function stopHold(){"
            + "if(repeatTimer){clearInterval(repeatTimer);repeatTimer=null;drive(0,0);}"
            + "}"
            + "['btn-left','btn-forward','btn-right','btn-back'].forEach(function(id){"
            + "var btn=document.getElementById(id);"
            + "btn.addEventListener('pointerdown',function(){heldKeys={};startHold(btn);});"
            + "btn.addEventListener('pointerup',stopHold);"
            + "btn.addEventListener('pointerleave',stopHold);"
            + "btn.addEventListener('pointercancel',stopHold);"
            + "});"
            // Arrow-key driving (U13): keydown starts the same hold-and-repeat a
            // mouse/touch press would, keyup stops it. event.repeat is the OS's own
            // key-repeat firing every keydown while held — ignored here since our
            // own setInterval already handles the repeat.
            //
            // Tracks a SET of physically-held arrow keys (heldKeys), not a single
            // "activeKey" variable — confirmed live (2026-09-15) that a single-variable
            // scheme drops the stop entirely under some real (if not fully pinned-down)
            // multi-key sequence: e.g. pressing a second arrow key while the first is
            // still physically held reassigns "the" active key, and if that first key's
            // own keyup arrives once it's no longer "the" active key, its check against
            // a single activeKey fails and stopHold() never runs for it — reported live
            // as arrow-key input sometimes never sending a stop pattern at all. Whichever
            // key is held most recently still drives (startHold on every non-repeat
            // keydown, matching the old behavior), but stopHold() now fires whenever
            // heldKeys becomes completely empty, independent of which specific key's
            // keyup that was — so a stop is guaranteed once every physically-held arrow
            // key is actually released, regardless of press/release order.
            + "var arrowToButton={ArrowUp:'btn-forward',ArrowDown:'btn-back',"
            + "ArrowLeft:'btn-left',ArrowRight:'btn-right'};"
            + "var heldKeys={};"
            + "document.addEventListener('keydown',function(e){"
            + "var id=arrowToButton[e.key];"
            + "if(!id)return;"
            + "e.preventDefault();"
            + "if(e.repeat)return;"
            + "heldKeys[e.key]=true;"
            + "startHold(document.getElementById(id));"
            + "});"
            + "document.addEventListener('keyup',function(e){"
            + "if(!arrowToButton[e.key])return;"
            + "e.preventDefault();"
            + "delete heldKeys[e.key];"
            + "if(Object.keys(heldKeys).length===0){stopHold();}"
            + "});"
            // A key held when focus leaves the page entirely (alt-tab, switching
            // windows) never gets a keyup at all — the browser simply stops sending
            // key events to a document that isn't focused. Without this, that key
            // stays in heldKeys forever and driving never gets a guaranteed stop.
            + "window.addEventListener('blur',function(){heldKeys={};stopHold();});"
            + "document.getElementById('btn-exit').addEventListener('click',function(){"
            // Previously just updated the status text and left the operator sitting
            // on this same page — the request succeeded server-side (the robot's own
            // screen did return to the launcher) but nothing told a *remote* browser
            // to go anywhere, so it looked broken from there. Navigates to the
            // launcher either way (success or failure): if /exit genuinely failed,
            // being on the launcher's own page to retry is still more useful than
            // being stuck here.
            + "status.textContent='Exiting...';"
            + "fetch('/exit?ct='+encodeURIComponent(CLIENT_TOKEN))"
            + ".catch(function(){})"
            + ".then(function(){window.location.href='https://'+location.hostname+':8443/';});"
            + "});"
            + "})();"
            + "</script>"
            + "</section>";
}
