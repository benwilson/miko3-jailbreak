package com.miko3.mode.remotecontrol;

/**
 * The drive-control section of the served page (R5/R16/R17, U7): hold-to-drive
 * buttons (mouse/touch) and arrow keys (U13), both driving over one persistent
 * WebSocket connection to ModeApp's "/drive-ws" route instead of a plain HTTP
 * request per command (U7's original approach) — each of those paid a fresh
 * HTTPS/TLS handshake, which over WiFi was slow enough to routinely blow past
 * the server's ~750ms watchdog and cause visible start/stop jank ("straight,
 * jank, jank, straight"). The hold-and-repeat shape is unchanged (still resends
 * the current command every 250ms while held, comfortably under the watchdog,
 * so holding a control is its own keepalive, and sends an explicit stop on
 * release) — only the transport changed, to a cheap frame over an
 * already-open socket. Plus an "Exit mode" control that releases the lease
 * and returns to the launcher (R16) — reachable without physical access to
 * the robot.
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
            + "repeatTimer=setInterval(function(){drive(linear,angular);},250);"
            + "}"
            + "function stopHold(){"
            + "if(repeatTimer){clearInterval(repeatTimer);repeatTimer=null;drive(0,0);}"
            + "}"
            + "['btn-left','btn-forward','btn-right','btn-back'].forEach(function(id){"
            + "var btn=document.getElementById(id);"
            + "btn.addEventListener('pointerdown',function(){activeKey=null;startHold(btn);});"
            + "btn.addEventListener('pointerup',stopHold);"
            + "btn.addEventListener('pointerleave',stopHold);"
            + "btn.addEventListener('pointercancel',stopHold);"
            + "});"
            // Arrow-key driving (U13): keydown starts the same hold-and-repeat a
            // mouse/touch press would, keyup stops it. event.repeat is the OS's own
            // key-repeat firing every keydown while held — ignored here since our
            // own setInterval already handles the repeat; tracking activeKey (rather
            // than stopping on any keyup) means switching directly from one arrow
            // key to another doesn't send a spurious stop, and a keyup for a key
            // that isn't the one currently driving (e.g. released after another
            // input already took over) is a no-op.
            + "var arrowToButton={ArrowUp:'btn-forward',ArrowDown:'btn-back',"
            + "ArrowLeft:'btn-left',ArrowRight:'btn-right'};"
            + "var activeKey=null;"
            + "document.addEventListener('keydown',function(e){"
            + "var id=arrowToButton[e.key];"
            + "if(!id)return;"
            + "e.preventDefault();"
            + "if(e.repeat)return;"
            + "activeKey=e.key;"
            + "startHold(document.getElementById(id));"
            + "});"
            + "document.addEventListener('keyup',function(e){"
            + "if(!arrowToButton[e.key])return;"
            + "e.preventDefault();"
            + "if(activeKey===e.key){activeKey=null;stopHold();}"
            + "});"
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
